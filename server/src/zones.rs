//! Safe zones (design 64 step 1, `docs/design/64-safe-zones.md` §3): a phone's named circles on
//! the Locate map ("Home", "School"), and "arrived" / "left" for the parent. The server judges
//! every crossing from the stored fixes; the phone only sends fixes (decision 1).
//!
//! - **The rule** ([side], [step], [evaluate]; pure, unit-tested and pinned by
//!   `testdata/zone_vectors.json`): a fix is inside a zone when `d + a <= r`, outside when
//!   `d - a >= r`, else unsure (`d` the haversine distance to the centre, `a` the accuracy, `r`
//!   the radius; no accuracy is unsure), all in whole millimetres so the boundary is exact on
//!   both sides of the contract. A side counts after two consecutive sure fixes on it at least
//!   [CONFIRM_GAP] apart; only fixes after the zone's cursor (the newest fix evaluated) count; a
//!   new or moved zone's first side is set silently.
//! - **The intake** ([intake]): a status report's fix is stored once (the same phone, time and
//!   coordinates are the same fix - every report re-sends the cached one), on its own; then
//!   ([judge]) every zone of the phone is stepped through every stored fix after its cursor, in
//!   time order, the zones' state written and the events raised - one `BEGIN IMMEDIATE`
//!   transaction, so a crash or an error leaves the fix stored and the judging for the next
//!   report (the cursors didn't move), and two parallel reports take turns. An event is keyed
//!   `<zone>:<side>:<first fix>` on top, so one crossing is one row.
//! - **The events** (`parent_events::Kind::ZoneArrived`/`ZoneLeft`) are stored as a key plus
//!   parameters (the user's decision 5), raised only when the zone's switch for that direction is
//!   on, pushed only while news ([NEWS_FOR]) and to the parents whose "Zones" switch is on.

use chrono::{DateTime, Duration, SecondsFormat, Utc};
use serde::{Deserialize, Serialize};
use sqlx::SqliteConnection;

use crate::AppState;
use crate::display_time::parse_db_utc;
use crate::models::LocationReport;
use crate::parent_events::{self, NewEvent, Push};

/// A zone's radius in metres (the user's decision 1, 2026-10-24): 100 m - about twice a good Wi-Fi
/// fix's accuracy, below which the straddle rule rarely says "inside" - to 2 km.
pub const RADIUS: std::ops::RangeInclusive<i64> = 100..=2000;
/// A new zone's radius.
pub const DEFAULT_RADIUS: i64 = 150;
/// The slider's step.
pub const RADIUS_STEP: i64 = 10;
/// Zones per phone (decision 2).
pub const MAX_ZONES: i64 = 10;
/// A zone's name, in characters (unique per phone, ignoring case).
pub const NAME_MAX: usize = 40;
/// The second fix of a pair is at least this long after the first: a burst of fixes from one
/// moment isn't two observations.
pub const CONFIRM_GAP: Duration = Duration::seconds(60);
/// A crossing is pushed only when the fix that confirmed it is younger than this at the intake
/// (decision 8): a phone back from a day without data doesn't push "arrived at School 08:10" at
/// 15:00. The inbox gets it either way.
pub const NEWS_FOR: Duration = Duration::minutes(30);
/// The mean Earth radius (IUGG), in metres.
pub const EARTH_RADIUS_M: f64 = 6_371_008.8;
/// A new or moved zone takes the phone's newest fix as its pending side when the fix is younger
/// than this (code QA open question 2): the next sure fix on that side confirms it - silently, as
/// every first side is - so "Here now" waits for one new location, not two.
pub const SEED_WITHIN: Duration = Duration::minutes(30);
/// "Here now" outside Regularly: the newest fix is younger than this.
pub const FRESH_OUTSIDE_REGULARLY: Duration = Duration::hours(1);
/// At most this many stored fixes are evaluated in one intake; the cursor takes the rest the
/// next time.
const EVALUATE_AT_MOST: i64 = 1000;
/// Design 64 step 2: a status report's `extra_locations` are stored up to this many, the newest
/// (the phone keeps at most 20 queued).
pub const MAX_EXTRA_LOCATIONS: usize = 20;
/// Design 64 step 2 (the user's decision 3, 2026-10-24): with zones on a phone that watches them
/// itself (`zone_watch.fences > 0`), the served Regularly interval is at least this - the fences
/// see the crossings, the heartbeat only keeps the trail and "last seen". Computed when the
/// policy is served ([served_interval]), never written: the parent's choice comes back with the
/// last zone deleted.
pub const HEARTBEAT_FLOOR_MINUTES: i64 = 30;
/// A stored `zone_watch` is at most this long (known keys only, so it never is).
const ZONE_WATCH_MAX: usize = 512;

/// Which side of a zone a fix is on, when it is sure.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Side {
    Inside,
    Outside,
}

impl Side {
    pub fn word(self) -> &'static str {
        match self {
            Side::Inside => "inside",
            Side::Outside => "outside",
        }
    }

    pub fn of(word: &str) -> Option<Side> {
        match word {
            "inside" => Some(Side::Inside),
            "outside" => Some(Side::Outside),
            _ => None,
        }
    }
}

/// A zone's circle.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Circle {
    pub latitude: f64,
    pub longitude: f64,
    pub radius_m: i64,
}

/// A stored fix as the rule reads it.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Fix {
    pub id: i64,
    pub latitude: f64,
    pub longitude: f64,
    pub accuracy_m: Option<f64>,
    pub at: DateTime<Utc>,
}

/// The great-circle distance in metres (haversine on [EARTH_RADIUS_M]).
pub fn distance_m(lat1: f64, lng1: f64, lat2: f64, lng2: f64) -> f64 {
    let (p1, p2) = (lat1.to_radians(), lat2.to_radians());
    let dp = (lat2 - lat1).to_radians();
    let dl = (lng2 - lng1).to_radians();
    let h = (dp / 2.0).sin().powi(2) + p1.cos() * p2.cos() * (dl / 2.0).sin().powi(2);
    2.0 * EARTH_RADIUS_M * h.sqrt().min(1.0).asin()
}

/// Metres as whole millimetres, the unit every comparison is made in.
fn mm(metres: f64) -> i64 {
    (metres * 1000.0).round() as i64
}

/// The side of `zone` the fix at (`latitude`, `longitude`) with `accuracy_m` is on (decision 2):
/// inside when `d + a <= r`, outside when `d - a >= r`, `None` (unsure) otherwise and without an
/// accuracy. AOSP's own rule (`d <= max(r, a)`) would count a ±1 km cell fix as inside any small
/// zone - the loophole this rule doesn't have.
pub fn side(zone: &Circle, latitude: f64, longitude: f64, accuracy_m: Option<f64>) -> Option<Side> {
    let accuracy = accuracy_m.filter(|a| a.is_finite() && *a >= 0.0)?;
    let d = mm(distance_m(
        zone.latitude,
        zone.longitude,
        latitude,
        longitude,
    ));
    let a = mm(accuracy);
    let r = zone.radius_m * 1000;
    if d + a <= r {
        Some(Side::Inside)
    } else if d - a >= r {
        Some(Side::Outside)
    } else {
        None
    }
}

/// A zone's rule state (§3.2), as stored on its row.
#[derive(Clone, Debug, Default, PartialEq)]
pub struct State {
    /// `None` = not known yet: the first confirmed side raises nothing (decision 4).
    pub side: Option<Side>,
    /// The first fix of the pair that set `side`.
    pub side_since: Option<DateTime<Utc>>,
    /// A sure fix on the other side, waiting for its second.
    pub pending: Option<(Side, DateTime<Utc>)>,
    /// The newest fix evaluated, `(captured_at, id)`: only a later one counts.
    pub cursor: Option<(DateTime<Utc>, i64)>,
}

/// A confirmed change of side: `to`, at the pair's first fix (the earliest evidence, so
/// "arrived 15:42" stays true when the second fix comes 20 minutes later); `confirmed_at` is the
/// second fix's time.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Crossing {
    pub to: Side,
    pub at: DateTime<Utc>,
    pub confirmed_at: DateTime<Utc>,
}

/// One fix through one zone's rule. A fix not after the cursor changes nothing (stored for the
/// trail, never evaluated twice); otherwise the cursor moves to it and:
/// - unsure: nothing else (it neither confirms nor breaks);
/// - sure and on the zone's side: a pending other side is forgotten (one bad fix flaps nothing);
/// - sure, other than the side, the pending side and at least [CONFIRM_GAP] after it: the side
///   changes, a [Crossing] at the pending fix unless the side was unknown;
/// - the same but sooner: nothing;
/// - sure, other than the side and the pending one (or none): it becomes the pending side.
pub fn step(state: &mut State, zone: &Circle, fix: &Fix) -> Option<Crossing> {
    let key = (fix.at, fix.id);
    if state.cursor.is_some_and(|cursor| key <= cursor) {
        return None;
    }
    state.cursor = Some(key);
    let sure = side(zone, fix.latitude, fix.longitude, fix.accuracy_m)?;
    if state.side == Some(sure) {
        state.pending = None;
        return None;
    }
    match state.pending {
        Some((pending, since)) if pending == sure => {
            if fix.at - since < CONFIRM_GAP {
                return None;
            }
            let before = state.side.replace(sure);
            state.side_since = Some(since);
            state.pending = None;
            before.map(|_| Crossing {
                to: sure,
                at: since,
                confirmed_at: fix.at,
            })
        }
        _ => {
            state.pending = Some((sure, fix.at));
            None
        }
    }
}

/// Every zone stepped through every fix in time order (`captured_at`, then id - fixes of one
/// report may come out of order): the crossings, each with its zone's index.
pub fn evaluate(zones: &mut [(Circle, State)], fixes: &[Fix]) -> Vec<(usize, Crossing)> {
    let mut ordered: Vec<&Fix> = fixes.iter().collect();
    ordered.sort_by_key(|fix| (fix.at, fix.id));
    let mut crossings = Vec::new();
    for fix in ordered {
        for (index, (circle, state)) in zones.iter_mut().enumerate() {
            if let Some(crossing) = step(state, circle, fix) {
                crossings.push((index, crossing));
            }
        }
    }
    crossings
}

/// RFC 3339 with milliseconds, UTC: how `device_locations.captured_at` is stored.
pub fn instant(t: DateTime<Utc>) -> String {
    t.to_rfc3339_opts(SecondsFormat::Millis, true)
}

/// A zone's row.
#[derive(Clone, Debug, sqlx::FromRow)]
pub struct Zone {
    pub id: i64,
    pub device_id: i64,
    pub name: String,
    pub latitude: f64,
    pub longitude: f64,
    pub radius_m: i64,
    pub notify_arrive: bool,
    pub notify_leave: bool,
    pub side: Option<String>,
    pub side_since: Option<String>,
    pub pending_side: Option<String>,
    pub pending_at: Option<String>,
    pub evaluated_at: Option<String>,
    pub evaluated_fix_id: Option<i64>,
}

impl Zone {
    pub fn circle(&self) -> Circle {
        Circle {
            latitude: self.latitude,
            longitude: self.longitude,
            radius_m: self.radius_m,
        }
    }

    /// The stored state; a value that doesn't read is "not known" (the rule starts over).
    pub fn state(&self) -> State {
        let time = |t: &Option<String>| t.as_deref().and_then(parse_db_utc);
        State {
            side: self.side.as_deref().and_then(Side::of),
            side_since: time(&self.side_since),
            pending: self
                .pending_side
                .as_deref()
                .and_then(Side::of)
                .zip(time(&self.pending_at)),
            cursor: time(&self.evaluated_at).zip(self.evaluated_fix_id),
        }
    }

    /// The zone's confirmed side is inside (the rule's state; what the pages say is
    /// [Zone::here_now]).
    pub fn here(&self) -> bool {
        self.side.as_deref() == Some("inside")
    }

    /// The confirmed side is inside, no other side is pending (the newest sure fix agrees) and the
    /// newest fix is fresh ([fresh]): "Here now" and "At Home since" (code QA Q-1, Q-2).
    pub fn here_now(&self, fresh: bool) -> bool {
        self.here() && self.pending_side.is_none() && fresh
    }
}

/// How old the phone's newest fix may be for "Here now": twice the interval in Regularly, else
/// [FRESH_OUTSIDE_REGULARLY].
pub fn fresh_for(mode: &str, interval_minutes: i64) -> Duration {
    if mode == "interval" {
        Duration::minutes(2 * interval_minutes)
    } else {
        FRESH_OUTSIDE_REGULARLY
    }
}

/// The newest fix is younger than [fresh_for] at `now`.
pub fn fresh(
    newest: Option<DateTime<Utc>>,
    mode: &str,
    interval_minutes: i64,
    now: DateTime<Utc>,
) -> bool {
    newest.is_some_and(|at| now - at < fresh_for(mode, interval_minutes))
}

/// The phone's newest fix time and its location setting (`location_mode`,
/// the interval as served - [served_interval_of]; a phone without a policy row reads as a new
/// phone's), for [fresh].
pub async fn freshness_inputs(
    db: &sqlx::SqlitePool,
    device_id: i64,
) -> Result<(Option<DateTime<Utc>>, String, i64), sqlx::Error> {
    let newest: Option<String> =
        sqlx::query_scalar("SELECT MAX(captured_at) FROM device_locations WHERE device_id = ?")
            .bind(device_id)
            .fetch_one(db)
            .await?;
    let (mode, interval): (String, i64) = sqlx::query_as(
        "SELECT location_mode, location_interval_minutes FROM device_policy WHERE device_id = ?",
    )
    .bind(device_id)
    .fetch_optional(db)
    .await?
    .unwrap_or_else(|| ("on_request".to_string(), 30));
    // Design 64 step 2: the interval the phone is served (the 30-min floor with zones).
    let interval = served_interval_of(db, device_id, &mode, interval).await?;
    Ok((newest.as_deref().and_then(parse_db_utc), mode, interval))
}

/// The phone's zones, oldest first.
pub async fn list<'e, E: sqlx::SqliteExecutor<'e>>(
    db: E,
    device_id: i64,
) -> Result<Vec<Zone>, sqlx::Error> {
    sqlx::query_as("SELECT * FROM device_zones WHERE device_id = ? ORDER BY id")
        .bind(device_id)
        .fetch_all(db)
        .await
}

/// Forgets a zone's side (a new zone, a moved centre, a changed radius): unknown again, the
/// cursor at the phone's newest fix, so the history isn't read and the next confirmed side is set
/// silently. A newest fix younger than [SEED_WITHIN] that is sure for `circle` is the pending side
/// ([seed]): one more fix on that side confirms it. A pending side raises nothing.
pub async fn start_over(
    conn: &mut SqliteConnection,
    zone_id: i64,
    device_id: i64,
    circle: &Circle,
    now: DateTime<Utc>,
) -> Result<(), sqlx::Error> {
    let newest: Option<(i64, f64, f64, Option<f64>, String)> = sqlx::query_as(
        "SELECT id, latitude, longitude, accuracy_meters, captured_at FROM device_locations \
         WHERE device_id = ? ORDER BY captured_at DESC, id DESC LIMIT 1",
    )
    .bind(device_id)
    .fetch_optional(&mut *conn)
    .await?;
    let fix = newest.and_then(|(id, latitude, longitude, accuracy_m, at)| {
        Some(Fix {
            id,
            latitude,
            longitude,
            accuracy_m,
            at: parse_db_utc(&at)?,
        })
    });
    let state = seed(circle, fix.as_ref(), now);
    sqlx::query(
        "UPDATE device_zones SET side = NULL, side_since = NULL, pending_side = ?, \
         pending_at = ?, evaluated_at = ?, evaluated_fix_id = ? WHERE id = ?",
    )
    .bind(state.pending.map(|(side, _)| side.word()))
    .bind(state.pending.map(|(_, at)| instant(at)))
    .bind(state.cursor.map(|(at, _)| instant(at)))
    .bind(state.cursor.map(|(_, id)| id))
    .bind(zone_id)
    .execute(&mut *conn)
    .await?;
    Ok(())
}

/// A new or moved zone's state from the phone's newest fix: the side unknown, the cursor at the
/// fix, and the fix's sure side pending when it is younger than [SEED_WITHIN] (one ahead by the
/// phone's clock counts as young). No fix: everything empty, every later fix is read.
pub fn seed(circle: &Circle, newest: Option<&Fix>, now: DateTime<Utc>) -> State {
    let Some(fix) = newest else {
        return State::default();
    };
    let pending = (now - fix.at < SEED_WITHIN)
        .then(|| side(circle, fix.latitude, fix.longitude, fix.accuracy_m))
        .flatten()
        .map(|side| (side, fix.at));
    State {
        pending,
        cursor: Some((fix.at, fix.id)),
        ..State::default()
    }
}

/// Another phone was enrolled into the row (§3.1): every zone's side is forgotten; the cursors
/// stay, so the old phone's fixes are never judged again.
pub async fn forget_sides(db: &sqlx::SqlitePool, device_id: i64) -> Result<(), sqlx::Error> {
    sqlx::query(
        "UPDATE device_zones SET side = NULL, side_since = NULL, pending_side = NULL, \
         pending_at = NULL WHERE device_id = ?",
    )
    .bind(device_id)
    .execute(db)
    .await?;
    Ok(())
}

/// Stores the fix once: `Some(id)` for a new row, `None` when the phone has it already (the same
/// `captured_at`, latitude and longitude - the launcher re-sends its cached fix in every report).
async fn store_fix(
    conn: &mut SqliteConnection,
    device_id: i64,
    loc: &LocationReport,
    captured_at: &str,
) -> Result<Option<i64>, sqlx::Error> {
    sqlx::query_scalar(
        "INSERT INTO device_locations (device_id, latitude, longitude, accuracy_meters, captured_at) \
         SELECT ?1, ?2, ?3, ?4, ?5 WHERE NOT EXISTS (SELECT 1 FROM device_locations \
           WHERE device_id = ?1 AND captured_at = ?5 AND latitude = ?2 AND longitude = ?3) \
         RETURNING id",
    )
    .bind(device_id)
    .bind(loc.latitude)
    .bind(loc.longitude)
    .bind(loc.accuracy_meters)
    .bind(captured_at)
    .fetch_optional(&mut *conn)
    .await
}

/// A status report's fix (already through `models::checked_location`; `captured_at` as stored),
/// in two steps (code QA Q-6: a zone's error never costs the trail its fix):
/// 1. stored once ([store_fix]), on its own;
/// 2. [judge]: in one `BEGIN IMMEDIATE` transaction every zone of the phone evaluated on the
///    stored fixes after its cursor ([evaluate_stored]), the states written, the events raised;
///    then the push sender's wake-up.
///
/// A failure in step 2 rolls back step 2 only and is logged: the cursors didn't move, so the next
/// report's step 2 judges the fix - once. A crash between the two is the same. A parallel report's
/// step 2 may judge this fix first; the cursors make this one find nothing then.
///
/// Design 64 step 2 (code QA N-4): a report's fixes - its `extra_locations` and its `location`,
/// checked ([report_fixes]) - are **all** stored first, each on its own, and the zones judged
/// **once** after them, so a crossing's pair that arrives in one report is judged in time order,
/// and an extra fix is never left unjudged behind a newer one it came with.
pub async fn intake(state: &AppState, device_id: i64, fixes: &[(&LocationReport, String)]) {
    if fixes.is_empty() {
        return;
    }
    let mut stored_any = false;
    match state.db.acquire().await {
        Ok(mut conn) => {
            for (loc, captured_at) in fixes {
                match store_fix(&mut conn, device_id, loc, captured_at).await {
                    Ok(_) => stored_any = true,
                    Err(err) => tracing::error!(device_id, %err, "can't store the fix"),
                }
            }
        }
        Err(err) => tracing::error!(device_id, %err, "can't store the fixes"),
    }
    if !stored_any {
        return;
    }
    if let Err(err) = judge(state, device_id, Utc::now()).await {
        tracing::error!(device_id, %err, "can't judge the zones; the next report will");
    }
}

/// Step 2 of [intake]: the zones judged on the stored fixes, the events raised, the sender woken.
pub async fn judge(
    state: &AppState,
    device_id: i64,
    now: DateTime<Utc>,
) -> Result<(), sqlx::Error> {
    let mut tx = state.db.begin_with("BEGIN IMMEDIATE").await?;
    let events = evaluate_stored(&mut tx, device_id, now).await?;
    let mut raised = false;
    for event in &events {
        raised |= parent_events::raise(&mut tx, event).await?.is_some();
    }
    tx.commit().await?;
    if raised {
        state.push.wake();
    }
    Ok(())
}

/// Every zone of the phone through the stored fixes after its cursor (at most
/// [EVALUATE_AT_MOST] in one call), the new state written: the events to raise, at `now`. Runs on
/// the caller's transaction.
pub async fn evaluate_stored(
    conn: &mut SqliteConnection,
    device_id: i64,
    now: DateTime<Utc>,
) -> Result<Vec<NewEvent>, sqlx::Error> {
    let zones = list(&mut *conn, device_id).await?;
    if zones.is_empty() {
        return Ok(Vec::new());
    }
    let mut states: Vec<(Circle, State)> = zones.iter().map(|z| (z.circle(), z.state())).collect();
    // From the oldest cursor on; a zone without one reads every stored fix.
    let from = states.iter().map(|(_, state)| state.cursor).min().flatten();
    let rows: Vec<(i64, f64, f64, Option<f64>, String)> = sqlx::query_as(
        "SELECT id, latitude, longitude, accuracy_meters, captured_at FROM device_locations \
         WHERE device_id = ?1 AND (?2 IS NULL OR captured_at > ?2 OR (captured_at = ?2 AND id > ?3)) \
         ORDER BY captured_at, id LIMIT ?4",
    )
    .bind(device_id)
    .bind(from.map(|(at, _)| instant(at)))
    .bind(from.map_or(0, |(_, id)| id))
    .bind(EVALUATE_AT_MOST)
    .fetch_all(&mut *conn)
    .await?;
    let fixes: Vec<Fix> = rows
        .into_iter()
        .filter_map(|(id, latitude, longitude, accuracy_m, at)| {
            Some(Fix {
                id,
                latitude,
                longitude,
                accuracy_m,
                at: parse_db_utc(&at)?,
            })
        })
        .collect();
    if fixes.is_empty() {
        return Ok(Vec::new());
    }
    let crossings = evaluate(&mut states, &fixes);
    for (zone, (_, state)) in zones.iter().zip(&states) {
        if *state == zone.state() {
            continue;
        }
        sqlx::query(
            "UPDATE device_zones SET side = ?, side_since = ?, pending_side = ?, pending_at = ?, \
             evaluated_at = ?, evaluated_fix_id = ? WHERE id = ?",
        )
        .bind(state.side.map(Side::word))
        .bind(state.side_since.map(instant))
        .bind(state.pending.map(|(side, _)| side.word()))
        .bind(state.pending.map(|(_, at)| instant(at)))
        .bind(state.cursor.map(|(at, _)| instant(at)))
        .bind(state.cursor.map(|(_, id)| id))
        .bind(zone.id)
        .execute(&mut *conn)
        .await?;
    }
    if crossings.is_empty() {
        return Ok(Vec::new());
    }
    let phone: String = sqlx::query_scalar("SELECT name FROM devices WHERE id = ?")
        .bind(device_id)
        .fetch_optional(&mut *conn)
        .await?
        .unwrap_or_default();
    Ok(crossings
        .into_iter()
        .filter_map(|(index, crossing)| event_of(&zones[index], &phone, &crossing, now))
        .collect())
}

/// The event of a crossing, `None` when the zone's switch for that direction is off (decision 5:
/// the zone's switches decide whether there is an event at all). Pushed only while it is news.
pub fn event_of(
    zone: &Zone,
    phone: &str,
    crossing: &Crossing,
    now: DateTime<Utc>,
) -> Option<NewEvent> {
    let arrived = crossing.to == Side::Inside;
    if (arrived && !zone.notify_arrive) || (!arrived && !zone.notify_leave) {
        return None;
    }
    let push = if now - crossing.confirmed_at < NEWS_FOR {
        Push::Now
    } else {
        Push::No
    };
    Some(parent_events::zone_crossing(
        zone.device_id,
        phone,
        zone.id,
        &zone.name,
        arrived,
        crossing.at,
        push,
    ))
}

/// A zone as the phone gets it (`location_policy.zones`, design 64 step 2): the centre and the
/// radius - **never the name** (decision 9: "Home" stays on the server). Pinned by
/// `policy_json_keys_snapshot` and the launcher's `PolicyResponseCompatTest`.
#[derive(Serialize, Clone, Debug, PartialEq)]
pub struct PolicyZone {
    pub id: i64,
    pub latitude: f64,
    pub longitude: f64,
    pub radius_m: i64,
}

/// The zones the policy carries: the phone's zones while the mode is Regularly, none otherwise
/// (decision 7: Off and Only when asked keep zones stored but unwatched).
pub fn policy_zones(mode: &str, zones: &[Zone]) -> Vec<PolicyZone> {
    if mode != "interval" {
        return Vec::new();
    }
    zones
        .iter()
        .map(|z| PolicyZone {
            id: z.id,
            latitude: z.latitude,
            longitude: z.longitude,
            radius_m: z.radius_m,
        })
        .collect()
}

/// The Regularly interval the phone is served (decision 3): `max(chosen, 30)` while the mode is
/// Regularly, the phone has zones and its watch holds fences (`watching`); the parent's choice
/// otherwise - an older launcher, no zones, a phone whose fences couldn't be registered.
pub fn served_interval(mode: &str, chosen: i64, zones: usize, watching: bool) -> i64 {
    if mode == "interval" && zones > 0 && watching {
        chosen.max(HEARTBEAT_FLOOR_MINUTES)
    } else {
        chosen
    }
}

/// `StatusReportRequest.zone_watch` as stored (design 64 step 2): the fences the phone holds,
/// whether it has a significant-motion sensor (without one it fixes at every heartbeat), the
/// system Location switch, the background-location grant, and the mock fixes a release build
/// ignored since the launcher's data was created (a running count, so a lost report loses none).
#[derive(Serialize, Deserialize, Clone, Copy, Debug, PartialEq, Eq)]
pub struct ZoneWatch {
    pub fences: i64,
    pub motion_sensor: bool,
    pub location_on: bool,
    pub background_location: bool,
    pub mock_ignored: i64,
}

/// The phone's `zone_watch` through its known keys (all five, of the right types; at most 100
/// fences and 10^9 fixes), as stored; `None` for anything else.
pub fn sanitize_zone_watch(value: &serde_json::Value) -> Option<String> {
    let count = |key: &str, max: i64| value.get(key)?.as_i64().filter(|n| (0..=max).contains(n));
    let flag = |key: &str| value.get(key)?.as_bool();
    let watch = ZoneWatch {
        fences: count("fences", 100)?,
        motion_sensor: flag("motion_sensor")?,
        location_on: flag("location_on")?,
        background_location: flag("background_location")?,
        mock_ignored: count("mock_ignored", 1_000_000_000)?,
    };
    serde_json::to_string(&watch)
        .ok()
        .filter(|json| json.len() <= ZONE_WATCH_MAX)
}

/// A stored `zone_watch` read back; `None` for nothing or anything [sanitize_zone_watch] didn't
/// write.
pub fn parse_zone_watch(stored: Option<&str>) -> Option<ZoneWatch> {
    serde_json::from_str(stored?).ok()
}

/// The security log's line when the phone's count of ignored mock fixes rose since its previous
/// report (`mock_location_ignored`, "Made-up locations ignored"): the phone and the count, never
/// a place. A lower count (the launcher's data cleared, a new install) starts again silently.
pub fn mock_log_line(
    device_id: i64,
    previous: Option<&ZoneWatch>,
    now: Option<&ZoneWatch>,
) -> Option<String> {
    let count = now?.mock_ignored;
    let before = previous.map_or(0, |w| w.mock_ignored);
    let rose = count - before;
    (rose > 0).then(|| {
        format!(
            "device {device_id}: the phone ignored {rose} made-up location(s) - an app pretended \
             to be somewhere else ({count} in all)"
        )
    })
}

/// The phone's latest `zone_watch` since its enrollment (a phone enrolled into the row anew is
/// not the install that reported before it); `None` without one.
pub async fn last_zone_watch(
    db: &sqlx::SqlitePool,
    device_id: i64,
) -> Result<Option<ZoneWatch>, sqlx::Error> {
    let stored: Option<Option<String>> = sqlx::query_scalar(&format!(
        "SELECT zone_watch_json FROM device_status WHERE device_id = ?1 AND reported_at >= {} \
         ORDER BY reported_at DESC, id DESC LIMIT 1",
        crate::models::ENROLLED_AT
    ))
    .bind(device_id)
    .fetch_optional(db)
    .await?;
    Ok(parse_zone_watch(stored.flatten().as_deref()))
}

/// Whether the phone watches its zones itself: its latest report says it holds fences.
pub fn watching(watch: Option<&ZoneWatch>) -> bool {
    watch.is_some_and(|w| w.fences > 0)
}

/// The phone's served Regularly interval, read as the policy serves it ([served_interval]).
pub async fn served_interval_of(
    db: &sqlx::SqlitePool,
    device_id: i64,
    mode: &str,
    chosen: i64,
) -> Result<i64, sqlx::Error> {
    if mode != "interval" {
        return Ok(chosen);
    }
    let zones: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM device_zones WHERE device_id = ?")
        .bind(device_id)
        .fetch_one(db)
        .await?;
    let watch = last_zone_watch(db, device_id).await?;
    Ok(served_interval(
        mode,
        chosen,
        zones as usize,
        watching(watch.as_ref()),
    ))
}

/// The fixes of one report to store (design 64 step 2): each of `extra_locations` and `location`
/// through `models::checked_location` (an invalid one dropped), the newest
/// [MAX_EXTRA_LOCATIONS] of the extras, then the report's own fix. The checked fixes with their
/// stored `captured_at`, and how many were dropped.
pub fn report_fixes<'a>(
    extra: &'a [LocationReport],
    location: Option<&'a LocationReport>,
) -> (Vec<(&'a LocationReport, String)>, usize) {
    let mut dropped = 0;
    let mut checked = |loc: &'a LocationReport| match crate::models::checked_location(loc) {
        Some(at) => Some((loc, at)),
        None => {
            dropped += 1;
            None
        }
    };
    let mut fixes: Vec<(&LocationReport, String)> = extra.iter().filter_map(&mut checked).collect();
    // The stored form sorts as text in time order; the newest stay.
    fixes.sort_by(|a, b| a.1.cmp(&b.1));
    let over = fixes.len().saturating_sub(MAX_EXTRA_LOCATIONS);
    fixes.drain(..over);
    fixes.extend(location.and_then(&mut checked));
    (fixes, dropped + over)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn utc(text: &str) -> DateTime<Utc> {
        parse_db_utc(text).unwrap()
    }

    /// Home, r = 150 m, in Oslo.
    const HOME: Circle = Circle {
        latitude: 59.9139,
        longitude: 10.7522,
        radius_m: 150,
    };
    /// About 30 m north of HOME's centre (0.00027° of latitude).
    const NEAR: (f64, f64) = (59.91417, 10.7522);
    /// About 1 km north.
    const AWAY: (f64, f64) = (59.9229, 10.7522);

    fn fix(id: i64, at: &str, place: (f64, f64), accuracy: Option<f64>) -> Fix {
        Fix {
            id,
            latitude: place.0,
            longitude: place.1,
            accuracy_m: accuracy,
            at: utc(at),
        }
    }

    fn inside(id: i64, at: &str) -> Fix {
        fix(id, at, NEAR, Some(20.0))
    }

    fn outside(id: i64, at: &str) -> Fix {
        fix(id, at, AWAY, Some(30.0))
    }

    fn known(side: Side) -> State {
        State {
            side: Some(side),
            side_since: Some(utc("2026-10-24T06:00:00Z")),
            pending: None,
            cursor: Some((utc("2026-10-24T06:00:00Z"), 1)),
        }
    }

    #[test]
    fn the_shared_vectors() {
        #[derive(serde::Deserialize)]
        struct Row {
            note: String,
            zone: Circle0,
            fix: Point,
            side: String,
        }
        #[derive(serde::Deserialize)]
        struct Circle0 {
            latitude: f64,
            longitude: f64,
            radius_m: i64,
        }
        #[derive(serde::Deserialize)]
        struct Point {
            latitude: f64,
            longitude: f64,
            accuracy_meters: Option<f64>,
        }
        let text = include_str!("../testdata/zone_vectors.json");
        let file: serde_json::Value = serde_json::from_str(text).unwrap();
        let rows: Vec<Row> = serde_json::from_value(file["rows"].clone()).unwrap();
        assert!(rows.len() >= 10, "every case of §3.2 has a row");
        for row in rows {
            let zone = Circle {
                latitude: row.zone.latitude,
                longitude: row.zone.longitude,
                radius_m: row.zone.radius_m,
            };
            let got = side(
                &zone,
                row.fix.latitude,
                row.fix.longitude,
                row.fix.accuracy_meters,
            )
            .map_or("unsure", Side::word);
            assert_eq!(got, row.side, "{}", row.note);
        }
    }

    #[test]
    fn a_fix_is_inside_outside_or_unsure() {
        assert_eq!(side(&HOME, NEAR.0, NEAR.1, Some(20.0)), Some(Side::Inside));
        assert_eq!(side(&HOME, AWAY.0, AWAY.1, Some(30.0)), Some(Side::Outside));
        // The straddle: 30 m from the centre but ±200 m could be anywhere.
        assert_eq!(side(&HOME, NEAR.0, NEAR.1, Some(200.0)), None);
        // A ±1 km cell fix 1 km away: inside or out.
        assert_eq!(side(&HOME, AWAY.0, AWAY.1, Some(1000.0)), None);
        // No accuracy, or one that isn't a number: unsure.
        assert_eq!(side(&HOME, NEAR.0, NEAR.1, None), None);
        assert_eq!(side(&HOME, NEAR.0, NEAR.1, Some(f64::NAN)), None);
        assert_eq!(side(&HOME, NEAR.0, NEAR.1, Some(-1.0)), None);
        // On the centre with the accuracy the radius: d + a == r is inside.
        assert_eq!(
            side(&HOME, HOME.latitude, HOME.longitude, Some(150.0)),
            Some(Side::Inside)
        );
        assert_eq!(
            side(&HOME, HOME.latitude, HOME.longitude, Some(150.001)),
            None
        );
    }

    #[test]
    fn two_sure_fixes_a_minute_apart_cross_at_the_first() {
        let mut state = known(Side::Inside);
        assert_eq!(
            step(&mut state, &HOME, &outside(2, "2026-10-24T08:00:00Z")),
            None
        );
        assert_eq!(
            state.pending,
            Some((Side::Outside, utc("2026-10-24T08:00:00Z")))
        );
        let crossing = step(&mut state, &HOME, &outside(3, "2026-10-24T08:20:00Z"));
        assert_eq!(
            crossing,
            Some(Crossing {
                to: Side::Outside,
                at: utc("2026-10-24T08:00:00Z"),
                confirmed_at: utc("2026-10-24T08:20:00Z"),
            })
        );
        assert_eq!(state.side, Some(Side::Outside));
        assert_eq!(state.side_since, Some(utc("2026-10-24T08:00:00Z")));
        assert_eq!(state.pending, None);
        assert_eq!(state.cursor, Some((utc("2026-10-24T08:20:00Z"), 3)));
        // And back.
        assert_eq!(
            step(&mut state, &HOME, &inside(4, "2026-10-24T15:42:00Z")),
            None
        );
        let back = step(&mut state, &HOME, &inside(5, "2026-10-24T15:43:00Z")).unwrap();
        assert_eq!(
            (back.to, back.at),
            (Side::Inside, utc("2026-10-24T15:42:00Z"))
        );
    }

    #[test]
    fn the_second_fix_needs_sixty_seconds() {
        let mut state = known(Side::Inside);
        step(&mut state, &HOME, &outside(2, "2026-10-24T08:00:00Z"));
        // A burst from one moment: neither confirms nor breaks.
        assert_eq!(
            step(&mut state, &HOME, &outside(3, "2026-10-24T08:00:01Z")),
            None
        );
        assert_eq!(
            step(&mut state, &HOME, &outside(4, "2026-10-24T08:00:59.999Z")),
            None
        );
        assert_eq!(
            state.pending,
            Some((Side::Outside, utc("2026-10-24T08:00:00Z")))
        );
        assert_eq!(state.side, Some(Side::Inside));
        // Exactly 60 s after the first: it counts, at the first.
        let crossing = step(&mut state, &HOME, &outside(5, "2026-10-24T08:01:00Z")).unwrap();
        assert_eq!(crossing.at, utc("2026-10-24T08:00:00Z"));
    }

    #[test]
    fn one_bad_fix_flaps_nothing_and_unsure_breaks_nothing() {
        let mut state = known(Side::Inside);
        // inside, one outlier outside, inside again: the pending side is forgotten.
        step(&mut state, &HOME, &outside(2, "2026-10-24T08:00:00Z"));
        assert_eq!(
            step(&mut state, &HOME, &inside(3, "2026-10-24T08:10:00Z")),
            None
        );
        assert_eq!(state.pending, None);
        assert_eq!(
            step(&mut state, &HOME, &outside(4, "2026-10-24T08:20:00Z")),
            None
        );
        assert_eq!(
            state.side,
            Some(Side::Inside),
            "two outliers apart are two pendings"
        );
        // Unsure between the two of a pair: the pair still counts.
        let mut state = known(Side::Inside);
        step(&mut state, &HOME, &outside(2, "2026-10-24T08:00:00Z"));
        let unsure = fix(3, "2026-10-24T08:05:00Z", NEAR, Some(500.0));
        assert_eq!(step(&mut state, &HOME, &unsure), None);
        assert_eq!(
            state.pending,
            Some((Side::Outside, utc("2026-10-24T08:00:00Z")))
        );
        assert_eq!(state.cursor, Some((utc("2026-10-24T08:05:00Z"), 3)));
        assert!(step(&mut state, &HOME, &outside(4, "2026-10-24T08:10:00Z")).is_some());
    }

    #[test]
    fn a_fix_not_after_the_cursor_is_ignored() {
        let mut state = known(Side::Inside);
        step(&mut state, &HOME, &outside(5, "2026-10-24T08:00:00Z"));
        let before = state.clone();
        // The same fix again, an older one, and one at the same time with a lower id.
        assert_eq!(
            step(&mut state, &HOME, &outside(5, "2026-10-24T08:00:00Z")),
            None
        );
        assert_eq!(
            step(&mut state, &HOME, &outside(9, "2026-10-24T07:00:00Z")),
            None
        );
        assert_eq!(
            step(&mut state, &HOME, &outside(4, "2026-10-24T08:00:00Z")),
            None
        );
        assert_eq!(state, before);
        // The same time with a higher id is after it (and too close to confirm).
        assert_eq!(
            step(&mut state, &HOME, &outside(6, "2026-10-24T08:00:00Z")),
            None
        );
        assert_eq!(state.cursor, Some((utc("2026-10-24T08:00:00Z"), 6)));
    }

    #[test]
    fn the_first_side_is_silent() {
        let mut state = State::default();
        assert_eq!(
            step(&mut state, &HOME, &inside(1, "2026-10-24T08:00:00Z")),
            None
        );
        assert_eq!(
            step(&mut state, &HOME, &inside(2, "2026-10-24T08:30:00Z")),
            None
        );
        assert_eq!(state.side, Some(Side::Inside));
        assert_eq!(state.side_since, Some(utc("2026-10-24T08:00:00Z")));
        // From then on a crossing is told.
        step(&mut state, &HOME, &outside(3, "2026-10-24T09:00:00Z"));
        assert!(step(&mut state, &HOME, &outside(4, "2026-10-24T09:30:00Z")).is_some());
    }

    #[test]
    fn a_moved_zone_starts_over() {
        // The state a move leaves (`start_over`): unknown, the cursor at the newest fix.
        let mut state = State {
            cursor: Some((utc("2026-10-24T08:00:00Z"), 7)),
            ..State::default()
        };
        let school = Circle {
            latitude: AWAY.0,
            longitude: AWAY.1,
            radius_m: 200,
        };
        // History up to the cursor is not read; the next two set the side silently.
        assert_eq!(
            step(&mut state, &school, &outside(6, "2026-10-24T07:00:00Z")),
            None
        );
        assert_eq!(state.pending, None);
        assert_eq!(
            step(&mut state, &school, &inside(8, "2026-10-24T08:10:00Z")),
            None
        );
        assert_eq!(
            step(&mut state, &school, &inside(9, "2026-10-24T08:20:00Z")),
            None
        );
        assert_eq!(state.side, Some(Side::Outside), "NEAR is 1 km from School");
    }

    #[test]
    fn fixes_are_evaluated_in_time_order_for_every_zone() {
        let school = Circle {
            latitude: AWAY.0,
            longitude: AWAY.1,
            radius_m: 200,
        };
        let mut zones = [(HOME, known(Side::Inside)), (school, known(Side::Outside))];
        // One report's fixes out of order: by time they leave Home and arrive at School.
        let fixes = [
            outside(12, "2026-10-24T08:12:00Z"),
            outside(10, "2026-10-24T08:00:00Z"),
            outside(11, "2026-10-24T08:05:00Z"),
        ];
        let crossings = evaluate(&mut zones, &fixes);
        assert_eq!(
            crossings,
            [
                (
                    0,
                    Crossing {
                        to: Side::Outside,
                        at: utc("2026-10-24T08:00:00Z"),
                        confirmed_at: utc("2026-10-24T08:05:00Z"),
                    }
                ),
                (
                    1,
                    Crossing {
                        to: Side::Inside,
                        at: utc("2026-10-24T08:00:00Z"),
                        confirmed_at: utc("2026-10-24T08:05:00Z"),
                    }
                ),
            ]
        );
        assert_eq!(zones[0].1.cursor, Some((utc("2026-10-24T08:12:00Z"), 12)));
        // The same fixes again: nothing more.
        assert!(evaluate(&mut zones, &fixes).is_empty());
    }

    #[test]
    fn a_new_zone_is_seeded_from_a_fresh_sure_fix_only() {
        let now = utc("2026-10-24T08:30:00Z");
        let fresh = inside(7, "2026-10-24T08:10:00Z");
        let state = seed(&HOME, Some(&fresh), now);
        assert_eq!(
            state,
            State {
                pending: Some((Side::Inside, fresh.at)),
                cursor: Some((fresh.at, 7)),
                ..State::default()
            }
        );
        // The next sure fix on that side a minute later confirms it - silently.
        let mut state = state;
        assert_eq!(
            step(&mut state, &HOME, &inside(8, "2026-10-24T08:11:00Z")),
            None
        );
        assert_eq!(state.side, Some(Side::Inside));
        // 30 minutes or older, or unsure: only the cursor.
        for fix in [
            inside(7, "2026-10-24T08:00:00Z"),
            fix(7, "2026-10-24T08:20:00Z", NEAR, Some(500.0)),
        ] {
            let state = seed(&HOME, Some(&fix), now);
            assert_eq!(state.pending, None);
            assert_eq!(state.cursor, Some((fix.at, 7)));
        }
        assert_eq!(seed(&HOME, None, now), State::default());
    }

    #[test]
    fn here_now_needs_a_fresh_fix() {
        let now = utc("2026-10-24T12:00:00Z");
        let at = |t: &str| Some(utc(t));
        assert!(fresh(at("2026-10-24T11:01:00Z"), "on_request", 30, now));
        assert!(!fresh(at("2026-10-24T11:00:00Z"), "on_request", 30, now));
        assert!(fresh(at("2026-10-24T11:01:00Z"), "interval", 30, now));
        assert!(!fresh(at("2026-10-24T10:59:00Z"), "interval", 30, now));
        assert!(fresh(at("2026-10-24T08:01:00Z"), "interval", 120, now));
        assert!(!fresh(None, "interval", 120, now));
    }

    #[test]
    fn the_distance_is_haversine() {
        // A degree of latitude on the mean sphere.
        let d = distance_m(0.0, 0.0, 1.0, 0.0);
        assert!((d - 111_195.08).abs() < 0.01, "{d}");
        // Across the antimeridian and across the pole.
        assert!((distance_m(0.0, 179.9995, 0.0, -179.9995) - 111.2).abs() < 0.1);
        assert!((distance_m(89.9995, 0.0, 89.9995, 180.0) - 111.2).abs() < 0.1);
    }

    /// Design 64 step 2, decision 3: the floor only with Regularly, zones and a watch with fences.
    #[test]
    fn the_served_interval_has_the_floor_only_while_watched() {
        assert_eq!(served_interval("interval", 10, 1, true), 30);
        assert_eq!(served_interval("interval", 30, 1, true), 30);
        assert_eq!(served_interval("interval", 120, 10, true), 120);
        assert_eq!(served_interval("interval", 10, 0, true), 10, "no zones");
        assert_eq!(served_interval("interval", 10, 1, false), 10, "not watched");
        assert_eq!(served_interval("on_request", 10, 1, true), 10);
        assert_eq!(served_interval("off", 10, 1, true), 10);
        let watch = |fences| ZoneWatch {
            fences,
            motion_sensor: true,
            location_on: true,
            background_location: true,
            mock_ignored: 0,
        };
        assert!(watching(Some(&watch(1))));
        assert!(!watching(Some(&watch(0))));
        assert!(!watching(None));
    }

    #[test]
    fn the_zone_watch_keeps_its_five_keys_only() {
        let whole = serde_json::json!({"fences": 2, "motion_sensor": false, "location_on": true,
            "background_location": true, "mock_ignored": 7, "where": "59.9,10.7"});
        let stored = sanitize_zone_watch(&whole).unwrap();
        assert!(!stored.contains("where"), "{stored}");
        assert_eq!(
            parse_zone_watch(Some(&stored)),
            Some(ZoneWatch {
                fences: 2,
                motion_sensor: false,
                location_on: true,
                background_location: true,
                mock_ignored: 7,
            })
        );
        for bad in [
            serde_json::json!({"fences": 101, "motion_sensor": false, "location_on": true,
                "background_location": true, "mock_ignored": 0}),
            serde_json::json!({"fences": 1, "motion_sensor": "no", "location_on": true,
                "background_location": true, "mock_ignored": 0}),
            serde_json::json!({"fences": 1, "motion_sensor": true, "location_on": true,
                "background_location": true}),
            serde_json::json!([1, 2]),
        ] {
            assert_eq!(sanitize_zone_watch(&bad), None, "{bad}");
        }
        assert_eq!(parse_zone_watch(Some("{\"fences\":1}")), None);
    }

    #[test]
    fn the_mock_line_comes_with_each_rise() {
        let watch = |mock_ignored| ZoneWatch {
            fences: 0,
            motion_sensor: true,
            location_on: true,
            background_location: true,
            mock_ignored,
        };
        assert_eq!(mock_log_line(3, None, Some(&watch(0))), None);
        assert_eq!(
            mock_log_line(3, None, Some(&watch(2))).as_deref(),
            Some(
                "device 3: the phone ignored 2 made-up location(s) - an app pretended to be \
                 somewhere else (2 in all)"
            )
        );
        assert!(
            mock_log_line(3, Some(&watch(2)), Some(&watch(5)))
                .unwrap()
                .contains("ignored 3 made-up")
        );
        assert_eq!(mock_log_line(3, Some(&watch(5)), Some(&watch(5))), None);
        assert_eq!(
            mock_log_line(3, Some(&watch(5)), Some(&watch(1))),
            None,
            "a reset count"
        );
        assert_eq!(mock_log_line(3, Some(&watch(5)), None), None);
    }

    /// The report's fixes: every extra checked, the newest 20 of them in time order, then the
    /// report's own fix; the dropped ones counted.
    #[test]
    fn a_reports_fixes_are_checked_capped_and_ordered() {
        let loc = |minute: u32, latitude: f64| LocationReport {
            latitude,
            longitude: 10.0,
            accuracy_meters: Some(20.0),
            captured_at: format!("2026-10-24T08:{minute:02}:00Z"),
        };
        let mut extra: Vec<LocationReport> = (0..25).rev().map(|m| loc(m, 59.0)).collect();
        extra.push(loc(30, 95.0));
        let own = loc(40, 59.0);
        let (fixes, dropped) = report_fixes(&extra, Some(&own));
        assert_eq!(dropped, 6, "5 over the cap and one off the Earth");
        assert_eq!(fixes.len(), 21);
        assert_eq!(fixes[0].1, "2026-10-24T08:05:00.000Z");
        assert_eq!(fixes[19].1, "2026-10-24T08:24:00.000Z");
        assert_eq!(fixes[20].1, "2026-10-24T08:40:00.000Z");
        let (none, dropped) = report_fixes(&[], None);
        assert!(none.is_empty());
        assert_eq!(dropped, 0);
    }

    #[test]
    fn the_policy_carries_the_zones_only_while_regularly() {
        let zone = Zone {
            id: 4,
            device_id: 1,
            name: "Home".to_string(),
            latitude: HOME.latitude,
            longitude: HOME.longitude,
            radius_m: 150,
            notify_arrive: true,
            notify_leave: true,
            side: None,
            side_since: None,
            pending_side: None,
            pending_at: None,
            evaluated_at: None,
            evaluated_fix_id: None,
        };
        let zones = [zone];
        assert_eq!(
            policy_zones("interval", &zones),
            [PolicyZone {
                id: 4,
                latitude: HOME.latitude,
                longitude: HOME.longitude,
                radius_m: 150,
            }]
        );
        assert!(policy_zones("on_request", &zones).is_empty());
        assert!(policy_zones("off", &zones).is_empty());
        let json = serde_json::to_string(&policy_zones("interval", &zones)).unwrap();
        assert_eq!(
            json,
            "[{\"id\":4,\"latitude\":59.9139,\"longitude\":10.7522,\"radius_m\":150}]"
        );
    }
}
