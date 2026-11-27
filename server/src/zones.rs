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
//!   parameters (the user's decision 5), raised for every crossing (design 135 decision 7), pushed
//!   only while news ([NEWS_FOR]) and to the parents whose "Zones" switch and own switch for that
//!   phone, zone and direction are on (`zone_alerts`, filtered in `push::queue`).
//! - **Design 135**: the zones are the family's (`family_zones`: the name and the circle); a phone
//!   links to the ones it uses (`device_zones`, the link: its id is the one the phone and the events
//!   see, and it holds the rule's state). [Zone] is a link read with its family zone.

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
/// Zones per phone (decision 2): links, design 135.
pub const MAX_ZONES: i64 = 10;
/// Design 135 decision 6: zones in the family - the phone's Zones page draws one cell per family
/// zone. A family over it (after the fold) loses only "Add Zone".
pub const MAX_FAMILY_ZONES: i64 = 50;
/// A zone's name, in characters (unique in the family ignoring case, checked only when it changes).
pub const NAME_MAX: usize = 40;
/// The second fix of a pair is at least this long after the first: a burst of fixes from one
/// moment isn't two observations.
pub const CONFIRM_GAP: Duration = Duration::seconds(60);
/// Design 125b C10: a pair with a coarse fix in it ([COARSE_PROVIDERS]) needs this between its
/// two fixes - two cell fixes a minute apart are one moment's guess, not two observations.
pub const COARSE_CONFIRM_GAP: Duration = Duration::minutes(10);
/// Design 125b C10: a coarse fix is surely outside only this far past the zone's edge at the
/// least (and three accuracies past it): the cabin case - the phone switched on far from home.
pub const COARSE_OUTSIDE_MIN_M: i64 = 20_000;
/// Design 125b C10: the providers of a coarse fix (a cell's position, hundreds of metres to
/// kilometres): never inside a zone, outside only far away ([fix_side]), never a seed
/// ([seed]), never what keeps "Here now" ([freshness_inputs]). A GLA-on phone's `network` fix
/// is not among them (Q8).
pub const COARSE_PROVIDERS: &[&str] = &["vibb_cell", "vibb_apple_cell"];
/// Design 131: a Wi-Fi fix with nothing that confirmed it ([FixClass::WifiAlone]) may confirm an arrival a fine fix
/// started at most this long after it - the satellites saw the approach, Wi-Fi confirms it indoors.
pub const WIFI_CONFIRM_WITHIN: Duration = Duration::minutes(10);
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
/// A stored `zone_watch` is at most this long (known keys only, so it never is; design 138's
/// `wifi` and `wifi_struck` within their caps fit).
const ZONE_WATCH_MAX: usize = 2048;

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

/// What a stored fix can say about a zone (design 131).
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub enum FixClass {
    /// A cell's position ([COARSE_PROVIDERS], design 125b C10): never inside, outside only far away.
    Coarse,
    /// A Wi-Fi fix (`models::WIFI_PROVIDERS`) that nothing confirmed: never starts an arrival, never keeps "Here now"
    /// ([step], [FINE_ONLY]); outside it counts as a fine fix.
    WifiAlone,
    /// The rest: satellites, `fused`, a GLA-on `network` fix (125b Q8), a Wi-Fi fix a satellite fix or a linked network
    /// confirmed.
    #[default]
    Fine,
}

impl FixClass {
    /// The class of a stored fix from its provider and its stored check (`models::fix_check`).
    pub fn of(provider: Option<&str>, check: Option<&str>) -> FixClass {
        match provider {
            Some(p) if COARSE_PROVIDERS.contains(&p) => FixClass::Coarse,
            Some(p) if crate::models::WIFI_PROVIDERS.contains(&p) && check.is_none() => {
                FixClass::WifiAlone
            }
            _ => FixClass::Fine,
        }
    }
}

/// A stored fix as the rule reads it.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Fix {
    pub id: i64,
    pub latitude: f64,
    pub longitude: f64,
    pub accuracy_m: Option<f64>,
    pub at: DateTime<Utc>,
    /// What it can say ([FixClass]).
    pub class: FixClass,
}

/// SQL: the row is a fine fix ([FixClass::Fine]): its `provider` not a coarse one ([COARSE_PROVIDERS];
/// NULL - an older row - is fine), and not a Wi-Fi fix without a check (design 131).
/// `zones::tests::the_fine_only_filter_names_every_coarse_provider` keeps the lists equal.
const FINE_ONLY: &str = "((provider IS NULL OR provider NOT IN ('vibb_cell', 'vibb_apple_cell')) \
    AND NOT (provider IS NOT NULL AND provider IN ('vibb_wifi', 'vibb_apple') AND fix_check IS NULL))";

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

/// The side of `zone` a coarse fix is on (design 125b C10): never inside; outside only when
/// `d - r >= max(3 x a, 20 km)`, in whole millimetres as [side]; else unsure. No accuracy is
/// unsure.
pub fn coarse_side(
    zone: &Circle,
    latitude: f64,
    longitude: f64,
    accuracy_m: Option<f64>,
) -> Option<Side> {
    let accuracy = accuracy_m.filter(|a| a.is_finite() && *a >= 0.0)?;
    let d = mm(distance_m(
        zone.latitude,
        zone.longitude,
        latitude,
        longitude,
    ));
    let clear = (3 * mm(accuracy)).max(COARSE_OUTSIDE_MIN_M * 1000);
    (d - zone.radius_m * 1000 >= clear).then_some(Side::Outside)
}

/// The side of `zone` the stored fix is on: [side], or [coarse_side] for a coarse fix.
pub fn fix_side(zone: &Circle, fix: &Fix) -> Option<Side> {
    if fix.class == FixClass::Coarse {
        coarse_side(zone, fix.latitude, fix.longitude, fix.accuracy_m)
    } else {
        side(zone, fix.latitude, fix.longitude, fix.accuracy_m)
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
    /// The class of the fix the pending side came from (design 125b C10: a coarse one's second
    /// needs [COARSE_CONFIRM_GAP]; design 131: a Wi-Fi fix alone confirms only a fine one's
    /// arrival). Not a column: [evaluate_stored] reads it from the pending fix's row.
    pub pending_class: FixClass,
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
/// - sure, other than the side, the pending side and at least [CONFIRM_GAP] after it
///   ([COARSE_CONFIRM_GAP] when either fix is coarse): the side changes, a [Crossing] at the
///   pending fix unless the side was unknown;
/// - the same but sooner: nothing;
/// - sure, other than the side and the pending one (or none): it becomes the pending side.
///
/// A coarse fix is sure only by [coarse_side] (design 125b C10). Design 131: a Wi-Fi fix alone
/// ([FixClass::WifiAlone]) surely inside never starts a pending side and never drops a pending
/// outside; it confirms only a pending inside a fine fix started, from [CONFIRM_GAP] to
/// [WIFI_CONFIRM_WITHIN] after it. Outside it counts as a fine fix.
pub fn step(state: &mut State, zone: &Circle, fix: &Fix) -> Option<Crossing> {
    let key = (fix.at, fix.id);
    if state.cursor.is_some_and(|cursor| key <= cursor) {
        return None;
    }
    state.cursor = Some(key);
    let sure = fix_side(zone, fix)?;
    if fix.class == FixClass::WifiAlone && sure == Side::Inside {
        return match state.pending {
            Some((Side::Inside, since))
                if state.pending_class == FixClass::Fine
                    && state.side != Some(Side::Inside)
                    && fix.at - since >= CONFIRM_GAP
                    && fix.at - since <= WIFI_CONFIRM_WITHIN =>
            {
                confirm(state, Side::Inside, since, fix.at)
            }
            _ => None,
        };
    }
    if state.side == Some(sure) {
        state.pending = None;
        state.pending_class = FixClass::Fine;
        return None;
    }
    match state.pending {
        Some((pending, since)) if pending == sure => {
            let gap = if fix.class == FixClass::Coarse || state.pending_class == FixClass::Coarse {
                COARSE_CONFIRM_GAP
            } else {
                CONFIRM_GAP
            };
            if fix.at - since < gap {
                return None;
            }
            confirm(state, sure, since, fix.at)
        }
        _ => {
            state.pending = Some((sure, fix.at));
            state.pending_class = fix.class;
            None
        }
    }
}

/// The pending side `sure` (first seen at `since`) confirmed at `at`: the crossing, unless the
/// side was unknown (the first side is silent).
fn confirm(
    state: &mut State,
    sure: Side,
    since: DateTime<Utc>,
    at: DateTime<Utc>,
) -> Option<Crossing> {
    let before = state.side.replace(sure);
    state.side_since = Some(since);
    state.pending = None;
    state.pending_class = FixClass::Fine;
    before.map(|_| Crossing {
        to: sure,
        at: since,
        confirmed_at: at,
    })
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

/// A phone's zone: the link (`device_zones`, design 135: its `id` is the policy's and the events'
/// zone id, and it holds the rule's state) read with its family zone (`zone_id`: the name and the
/// circle).
#[derive(Clone, Debug, sqlx::FromRow)]
pub struct Zone {
    pub id: i64,
    pub zone_id: i64,
    pub device_id: i64,
    pub name: String,
    pub latitude: f64,
    pub longitude: f64,
    pub radius_m: i64,
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
            pending_class: FixClass::Fine,
        }
    }

    /// The zone's confirmed side is inside (the rule's state; what the pages say is
    /// [Zone::here_now]).
    pub fn here(&self) -> bool {
        self.side.as_deref() == Some("inside")
    }

    /// The confirmed side is inside, no other side is pending (the newest sure fix agrees) and the
    /// newest fix is fresh ([fresh]): "Here now" and "At Home since" (code QA Q-1, Q-2). The
    /// freshness counts from the newest **fine** fix (design 125b C10, [newest_fine_seen]): a cell
    /// fix never keeps "Here now" alive.
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

/// The phone's newest **fine** fix (design 125b C10: never a coarse one) - its own time, and
/// the later of it and its last still re-send (`resent_at`, Q-3).
pub async fn newest_fine_seen(
    db: &sqlx::SqlitePool,
    device_id: i64,
) -> Result<(Option<DateTime<Utc>>, Option<DateTime<Utc>>), sqlx::Error> {
    let row: Option<(String, Option<String>)> = sqlx::query_as(&format!(
        "SELECT captured_at, resent_at FROM device_locations WHERE device_id = ? AND {FINE_ONLY} \
         ORDER BY captured_at DESC, id DESC LIMIT 1"
    ))
    .bind(device_id)
    .fetch_optional(db)
    .await?;
    let Some((captured, resent)) = row else {
        return Ok((None, None));
    };
    let captured = parse_db_utc(&captured);
    let seen = captured.max(resent.as_deref().and_then(parse_db_utc));
    Ok((captured, seen))
}

/// The phone's newest fine fix time - its own, or its last still re-send (`resent_at`, Q-3;
/// [newest_fine_seen]) - and its location setting (`location_mode`,
/// the interval as served - [served_interval_of]; a phone without a policy row reads as a new
/// phone's), for [fresh].
pub async fn freshness_inputs(
    db: &sqlx::SqlitePool,
    device_id: i64,
) -> Result<(Option<DateTime<Utc>>, String, i64), sqlx::Error> {
    // Q-3: a fix the phone re-sent as still current counts from that re-send.
    let (_, newest) = newest_fine_seen(db, device_id).await?;
    let (mode, interval): (String, i64) = sqlx::query_as(
        "SELECT location_mode, location_interval_minutes FROM device_policy WHERE device_id = ?",
    )
    .bind(device_id)
    .fetch_optional(db)
    .await?
    .unwrap_or_else(|| ("on_request".to_string(), 30));
    // Design 64 step 2: the interval the phone is served (the 30-min floor with zones).
    let interval = served_interval_of(db, device_id, &mode, interval).await?;
    Ok((newest, mode, interval))
}

/// The columns of a [Zone]: the link `d` with its family zone `f`.
const ZONE_COLUMNS: &str = "d.id, d.zone_id, d.device_id, f.name, f.latitude, f.longitude, f.radius_m, \
     d.side, d.side_since, d.pending_side, d.pending_at, d.evaluated_at, d.evaluated_fix_id \
     FROM device_zones d JOIN family_zones f ON f.id = d.zone_id";

/// The phone's zones (its links, design 135), oldest link first.
pub async fn list<'e, E: sqlx::SqliteExecutor<'e>>(
    db: E,
    device_id: i64,
) -> Result<Vec<Zone>, sqlx::Error> {
    sqlx::query_as(&format!(
        "SELECT {ZONE_COLUMNS} WHERE d.device_id = ? ORDER BY d.id"
    ))
    .bind(device_id)
    .fetch_all(db)
    .await
}

/// Every link of the family zone `zone_id` (design 135), oldest first.
pub async fn links_of<'e, E: sqlx::SqliteExecutor<'e>>(
    db: E,
    zone_id: i64,
) -> Result<Vec<Zone>, sqlx::Error> {
    sqlx::query_as(&format!(
        "SELECT {ZONE_COLUMNS} WHERE d.zone_id = ? ORDER BY d.id"
    ))
    .bind(zone_id)
    .fetch_all(db)
    .await
}

/// A family zone (design 135): the place, shared by every phone that links to it.
#[derive(Clone, Debug, PartialEq, sqlx::FromRow)]
pub struct FamilyZone {
    pub id: i64,
    pub name: String,
    pub latitude: f64,
    pub longitude: f64,
    pub radius_m: i64,
}

impl FamilyZone {
    pub fn circle(&self) -> Circle {
        Circle {
            latitude: self.latitude,
            longitude: self.longitude,
            radius_m: self.radius_m,
        }
    }
}

/// The family's zones, oldest first.
pub async fn family<'e, E: sqlx::SqliteExecutor<'e>>(
    db: E,
) -> Result<Vec<FamilyZone>, sqlx::Error> {
    sqlx::query_as("SELECT id, name, latitude, longitude, radius_m FROM family_zones ORDER BY id")
        .fetch_all(db)
        .await
}

/// One family zone, `None` when there is none with that id.
pub async fn family_zone<'e, E: sqlx::SqliteExecutor<'e>>(
    db: E,
    zone_id: i64,
) -> Result<Option<FamilyZone>, sqlx::Error> {
    sqlx::query_as("SELECT id, name, latitude, longitude, radius_m FROM family_zones WHERE id = ?")
        .bind(zone_id)
        .fetch_optional(db)
        .await
}

/// Links the family zone `zone` to the phone (design 135 §4: a new link, its side unknown and its
/// cursor at the phone's newest fix - [start_over], never a replay of the history; design 138: the
/// zone's confirmed routers copied to it), on the caller's transaction; the new link's id. The
/// caller checks the cap.
pub async fn link(
    conn: &mut SqliteConnection,
    device_id: i64,
    zone: &FamilyZone,
    admin_id: Option<i64>,
    now: DateTime<Utc>,
) -> Result<i64, sqlx::Error> {
    let link_id: i64 = sqlx::query_scalar(
        "INSERT INTO device_zones (device_id, zone_id, created_by) VALUES (?, ?, ?) RETURNING id",
    )
    .bind(device_id)
    .bind(zone.id)
    .bind(admin_id)
    .fetch_one(&mut *conn)
    .await?;
    start_over(conn, link_id, device_id, &zone.circle(), now).await?;
    // Design 138 with 135: the zone's confirmed routers are the family zone's - the new link gets them.
    crate::zone_networks::copy_to_link(conn, link_id, zone.id).await?;
    Ok(link_id)
}

/// Forgets a zone's side (a new zone, a moved centre, a changed radius): unknown again, the
/// cursor at the phone's newest fix, so the history isn't read and the next confirmed side is set
/// silently. The newest **fine** fix (design 125b C10), younger than [SEED_WITHIN] and sure for
/// `circle`, is the pending side ([seed]): one more fix on that side confirms it. A pending side
/// raises nothing.
pub async fn start_over(
    conn: &mut SqliteConnection,
    zone_id: i64,
    device_id: i64,
    circle: &Circle,
    now: DateTime<Utc>,
) -> Result<(), sqlx::Error> {
    let mut fixes = [None, None];
    for (slot, filter) in fixes
        .iter_mut()
        .zip([String::new(), format!("AND {FINE_ONLY}")])
    {
        let row: Option<FixRow> = sqlx::query_as(&format!(
            "SELECT id, latitude, longitude, accuracy_meters, captured_at, provider, fix_check \
             FROM device_locations WHERE device_id = ? {filter} \
             ORDER BY captured_at DESC, id DESC LIMIT 1"
        ))
        .bind(device_id)
        .fetch_optional(&mut *conn)
        .await?;
        *slot = row.and_then(fix_of_row);
    }
    let [newest, fine] = fixes;
    let state = seed(circle, newest.as_ref(), fine.as_ref(), now);
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
/// fix, and the newest **fine** fix's (`fine`; design 125b C10: a coarse fix never seeds) sure
/// side pending when it is younger than [SEED_WITHIN] (one ahead by the phone's clock counts as
/// young). No fix: everything empty, every later fix is read.
pub fn seed(
    circle: &Circle,
    newest: Option<&Fix>,
    fine: Option<&Fix>,
    now: DateTime<Utc>,
) -> State {
    let Some(newest) = newest else {
        return State::default();
    };
    let pending = fine
        .filter(|fix| fix.class == FixClass::Fine && now - fix.at < SEED_WITHIN)
        .and_then(|fix| {
            side(circle, fix.latitude, fix.longitude, fix.accuracy_m).map(|s| (s, fix.at))
        });
    State {
        pending,
        cursor: Some((newest.at, newest.id)),
        ..State::default()
    }
}

/// The class of a pending side's fix from the rows stored at its time (`provider`, `fix_check`):
/// a fine one when any is (design 131), else a Wi-Fi fix alone, else a coarse one; none - a row
/// gone to retention - reads as fine.
pub fn pending_class(rows: &[(Option<String>, Option<String>)]) -> FixClass {
    let classes: Vec<FixClass> = rows
        .iter()
        .map(|(provider, check)| FixClass::of(provider.as_deref(), check.as_deref()))
        .collect();
    [FixClass::Fine, FixClass::WifiAlone, FixClass::Coarse]
        .into_iter()
        .find(|c| classes.contains(c))
        .unwrap_or(FixClass::Fine)
}

/// A stored fix's columns as [evaluate_stored] and [start_over] read them.
type FixRow = (
    i64,
    f64,
    f64,
    Option<f64>,
    String,
    Option<String>,
    Option<String>,
);

/// A stored fix's row as the rule reads it; a time that doesn't read is no fix.
fn fix_of_row((id, latitude, longitude, accuracy_m, at, provider, check): FixRow) -> Option<Fix> {
    Some(Fix {
        id,
        latitude,
        longitude,
        accuracy_m,
        at: parse_db_utc(&at)?,
        class: FixClass::of(provider.as_deref(), check.as_deref()),
    })
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

/// One fix of the phone: the device `?1`, the place `?2`/`?3`, and the time the phone named - the stored `?5` plus
/// the offset `?8` - within a second of a row's own (an older row without an offset: its time alone).
/// The time window keeps the search on the index of `(device_id, captured_at)`: a re-sent fix is stored within a few
/// milliseconds of its first time.
const SAME_FIX: &str = "device_id = ?1 AND captured_at BETWEEN strftime('%Y-%m-%dT%H:%M:%fZ', ?5, '-10 minutes') \
    AND strftime('%Y-%m-%dT%H:%M:%fZ', ?5, '+10 minutes') AND latitude = ?2 AND longitude = ?3 AND (captured_at = ?5 \
    OR abs((julianday(captured_at) - julianday(?5)) * 86400000.0 + coalesce(phone_offset_ms, 0) - coalesce(?8, 0)) \
    < 1000)";

/// Stores the fix once: `Some(id)` for a new row, `None` when the phone has it already (the same
/// `captured_at`, latitude and longitude - the launcher re-sends its cached fix in every report).
async fn store_fix(
    conn: &mut SqliteConnection,
    device_id: i64,
    loc: &LocationReport,
    captured_at: &str,
) -> Result<Option<i64>, sqlx::Error> {
    // A fix with an age is stored at the receipt minus it, so a re-sent fix comes with another time: the same place
    // and the phone's own named time within a second of a stored one is that fix again ([SAME_FIX]).
    sqlx::query_scalar(&format!(
        "INSERT INTO device_locations \
           (device_id, latitude, longitude, accuracy_meters, captured_at, provider, mock, phone_offset_ms, fix_check) \
         SELECT ?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9 WHERE NOT EXISTS (SELECT 1 FROM device_locations \
           WHERE {SAME_FIX}) \
         RETURNING id"
    ))
    .bind(device_id)
    .bind(loc.latitude)
    .bind(loc.longitude)
    .bind(loc.accuracy_meters)
    .bind(captured_at)
    .bind(crate::models::location_provider(loc.provider.as_deref()))
    .bind(loc.mock)
    .bind(crate::models::phone_offset_ms(loc, captured_at))
    .bind(crate::models::fix_check(
        loc.provider.as_deref(),
        loc.check.as_deref(),
    ))
    .fetch_optional(&mut *conn)
    .await
}

/// The phone's location setting takes fixes (security review P1): anything but "Off". The phone
/// sends none under Off; a fix that comes all the same (a report sent before the phone had the new
/// policy, a rooted phone) is never stored. Under "Only when asked" the phone
/// sends a fix for an Update Location, at low battery and at shutdown (2026-10-25) - all asked for.
/// A phone without a policy row reads as a new phone's ("Only when asked"); a read that fails
/// takes nothing (logged).
pub async fn takes_fixes<'e, E: sqlx::SqliteExecutor<'e>>(db: E, device_id: i64) -> bool {
    match sqlx::query_scalar::<_, String>(
        "SELECT location_mode FROM device_policy WHERE device_id = ?",
    )
    .bind(device_id)
    .fetch_optional(db)
    .await
    {
        Ok(mode) => mode.as_deref() != Some("off"),
        Err(err) => {
            tracing::error!(device_id, %err, "can't read the location setting; no fix stored");
            false
        }
    }
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
///
/// `resent` (qa-design-64-step2-code Q-3): the report's own `location` when its `zone_watch` says
/// `still` - the phone re-sent its fix while its motion sensor says it hasn't moved since; that
/// row's `resent_at` becomes now, so "Here now" counts from this report ([freshness_inputs]), and
/// its `still_since` what the phone said ([Resent], [report_still_since]).
///
/// Nothing is stored or marked while the phone's location is Off ([takes_fixes]).
pub async fn intake(
    state: &AppState,
    device_id: i64,
    fixes: &[(&LocationReport, String)],
    resent: Option<&Resent<'_>>,
) {
    if fixes.is_empty() {
        return;
    }
    let mut stored_any = false;
    match state.db.acquire().await {
        Ok(mut conn) => {
            if !takes_fixes(&mut *conn, device_id).await {
                tracing::warn!(device_id, "dropped fixes: the phone's location is off");
                return;
            }
            // Within the phone's daily budget (security review L4): the newest that fit.
            let left = crate::retention::Budget::Locations
                .left_today(&mut conn, device_id)
                .await;
            for (loc, captured_at) in &fixes[fixes.len().saturating_sub(left)..] {
                match store_fix(&mut conn, device_id, loc, captured_at).await {
                    Ok(_) => stored_any = true,
                    Err(err) => tracing::error!(device_id, %err, "can't store the fix"),
                }
            }
            if let Some(resent) = resent
                && let Err(err) = mark_resent(&mut conn, device_id, resent).await
            {
                tracing::error!(device_id, %err, "can't mark the fix as re-sent");
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

/// A still report's own fix (Q-3): the report's `location`, its `captured_at` as stored, and since
/// when the phone is still when it said ([report_still_since]).
pub struct Resent<'a> {
    pub loc: &'a LocationReport,
    pub captured_at: String,
    pub still_since: Option<DateTime<Utc>>,
}

/// The stored fix (the phone, `captured_at` and the coordinates - [store_fix]'s identity) re-sent
/// as still current now (Q-3), with since when the phone is still (0185, [clamp_still_since]; NULL
/// when this re-send didn't say, so an older one never lingers). The fix's own time stays.
async fn mark_resent(
    conn: &mut SqliteConnection,
    device_id: i64,
    resent: &Resent<'_>,
) -> Result<(), sqlx::Error> {
    let (loc, captured_at) = (resent.loc, resent.captured_at.as_str());
    let still_since = match resent.still_since {
        Some(since) => Some(clamp_still_since(conn, device_id, resent, since).await?),
        None => None,
    };
    sqlx::query(&format!(
        "UPDATE device_locations SET resent_at = ?9, still_since = ?10 WHERE {SAME_FIX}"
    ))
    .bind(device_id)
    .bind(loc.latitude)
    .bind(loc.longitude)
    .bind(Option::<f64>::None)
    .bind(captured_at)
    .bind(Option::<String>::None)
    .bind(Option::<bool>::None)
    .bind(crate::models::phone_offset_ms(loc, captured_at))
    .bind(instant(Utc::now()))
    .bind(still_since.map(instant))
    .execute(&mut *conn)
    .await?;
    Ok(())
}

/// The stored fix (the phone, `captured_at` and the coordinates - [store_fix]'s identity) marked as
/// the last position before the shutdown at `off_at` (2026-10-25, migration 0097): Locate's line.
pub async fn mark_shutdown(
    db: &sqlx::SqlitePool,
    device_id: i64,
    loc: &LocationReport,
    captured_at: &str,
    off_at: DateTime<Utc>,
) -> Result<(), sqlx::Error> {
    sqlx::query(&format!(
        "UPDATE device_locations SET shutdown_at = ?9 WHERE {SAME_FIX}"
    ))
    .bind(device_id)
    .bind(loc.latitude)
    .bind(loc.longitude)
    .bind(Option::<f64>::None)
    .bind(captured_at)
    .bind(Option::<String>::None)
    .bind(Option::<bool>::None)
    .bind(crate::models::phone_offset_ms(loc, captured_at))
    .bind(instant(off_at))
    .execute(db)
    .await?;
    Ok(())
}

/// The report's `zone_watch.still` (Q-3): `true` only for a JSON `true`.
pub fn report_still(zone_watch: Option<&serde_json::Value>) -> bool {
    zone_watch
        .and_then(|w| w.get("still"))
        .and_then(serde_json::Value::as_bool)
        .unwrap_or(false)
}

/// The phone's enrolment ([report_still_since]'s floor); `None` for none or a read that fails (logged).
pub async fn enrolled_at(db: &sqlx::SqlitePool, device_id: i64) -> Option<DateTime<Utc>> {
    match sqlx::query_scalar::<_, Option<String>>("SELECT enrolled_at FROM devices WHERE id = ?")
        .bind(device_id)
        .fetch_optional(db)
        .await
    {
        Ok(at) => at.flatten().as_deref().and_then(parse_db_utc),
        Err(err) => {
            tracing::error!(device_id, %err, "can't read the enrolment");
            None
        }
    }
}

/// The report's `zone_watch.still_since_age_ms` (the owner's report, 2026-11-27): how long ago the
/// phone went still - its last motion before the stillness it re-sends its fix under. Read on the
/// server's clock as `received` minus it, as a fix's `age_ms` (code QA M3: one clock, whatever the
/// phone's says). Only beside `still` ([report_still]); a whole number from 0 to
/// `models::MAX_FIX_AGE_MS`, and the time not before the phone's enrolment (`enrolled_at`; none
/// known, none taken). Anything else is no value, never a refused report.
pub fn report_still_since(
    zone_watch: Option<&serde_json::Value>,
    enrolled_at: Option<DateTime<Utc>>,
    received: DateTime<Utc>,
) -> Option<DateTime<Utc>> {
    if !report_still(zone_watch) {
        return None;
    }
    let age = zone_watch?.get("still_since_age_ms")?.as_i64()?;
    if !(0..=crate::models::MAX_FIX_AGE_MS).contains(&age) {
        return None;
    }
    let at = received - Duration::milliseconds(age);
    (at >= enrolled_at?).then_some(at)
}

/// Defence in depth for `still_since` (code QA M4): never earlier than the newest stored fix of
/// the phone, older than the re-sent one, that lies apart from it - further than both accuracies
/// and [STILL_APART_SLACK_M] (design 124 D7's distance with each fix's real accuracy): the phone
/// was elsewhere then. A fix without an accuracy proves nothing. Only fixes after `since` are read.
async fn clamp_still_since(
    conn: &mut SqliteConnection,
    device_id: i64,
    resent: &Resent<'_>,
    since: DateTime<Utc>,
) -> Result<DateTime<Utc>, sqlx::Error> {
    let rows: Vec<(String, f64, f64, Option<f64>)> = sqlx::query_as(
        "SELECT captured_at, latitude, longitude, accuracy_meters FROM device_locations \
         WHERE device_id = ? AND captured_at > ? AND captured_at < ? \
         ORDER BY captured_at DESC, id DESC LIMIT 500",
    )
    .bind(device_id)
    .bind(instant(since))
    .bind(&resent.captured_at)
    .fetch_all(&mut *conn)
    .await?;
    let known = |a: Option<f64>| a.filter(|a| a.is_finite() && *a >= 0.0);
    let Some(b) = known(resent.loc.accuracy_meters) else {
        return Ok(since);
    };
    let apart = rows.iter().find(|(_, lat, lng, accuracy)| {
        known(*accuracy).is_some_and(|a| {
            distance_m(*lat, *lng, resent.loc.latitude, resent.loc.longitude)
                > a + b + STILL_APART_SLACK_M
        })
    });
    Ok(apart
        .and_then(|(at, ..)| parse_db_utc(at))
        .map_or(since, |at| at.max(since)))
}

/// [clamp_still_since]'s slack beyond both accuracies: the launcher's `CONTRADICTION_SLACK_M`.
const STILL_APART_SLACK_M: f64 = 200.0;

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
    // Design 125b C10, design 131: the class of the fix a pending side came from - the pending
    // fix's own rows at its stored time, a fine one preferred when several share it (one gone to
    // retention reads as fine).
    for (_, state) in states.iter_mut() {
        if let Some((_, at)) = state.pending {
            let rows: Vec<(Option<String>, Option<String>)> = sqlx::query_as(
                "SELECT provider, fix_check FROM device_locations \
                 WHERE device_id = ? AND captured_at = ?",
            )
            .bind(device_id)
            .bind(instant(at))
            .fetch_all(&mut *conn)
            .await?;
            state.pending_class = pending_class(&rows);
        }
    }
    let before: Vec<State> = states.iter().map(|(_, state)| state.clone()).collect();
    // From the oldest cursor on; a zone without one reads every stored fix.
    let from = states.iter().map(|(_, state)| state.cursor).min().flatten();
    let rows: Vec<FixRow> = sqlx::query_as(
        "SELECT id, latitude, longitude, accuracy_meters, captured_at, provider, fix_check \
         FROM device_locations \
         WHERE device_id = ?1 AND (?2 IS NULL OR captured_at > ?2 OR (captured_at = ?2 AND id > ?3)) \
         ORDER BY captured_at, id LIMIT ?4",
    )
    .bind(device_id)
    .bind(from.map(|(at, _)| instant(at)))
    .bind(from.map_or(0, |(_, id)| id))
    .bind(EVALUATE_AT_MOST)
    .fetch_all(&mut *conn)
    .await?;
    let fixes: Vec<Fix> = rows.into_iter().filter_map(fix_of_row).collect();
    if fixes.is_empty() {
        return Ok(Vec::new());
    }
    let crossings = evaluate(&mut states, &fixes);
    for ((zone, (_, state)), before) in zones.iter().zip(&states).zip(&before) {
        if state == before {
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
        .map(|(index, crossing)| event_of(&zones[index], &phone, &crossing, now))
        .collect())
}

/// The event of a crossing - every crossing is one (design 135 decision 7: each parent's own
/// switches for the link filter only the push, `push::queue`). Pushed only while it is news.
pub fn event_of(zone: &Zone, phone: &str, crossing: &Crossing, now: DateTime<Utc>) -> NewEvent {
    let arrived = crossing.to == Side::Inside;
    let push = if now - crossing.confirmed_at < NEWS_FOR {
        Push::Now
    } else {
        Push::No
    };
    parent_events::zone_crossing(
        zone.device_id,
        phone,
        zone.id,
        &zone.name,
        arrived,
        crossing.at,
        push,
    )
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
    /// Design 138 D13: the routers a parent confirmed for the zone, unblocked; always present.
    pub networks: Vec<crate::zone_networks::PolicyNetwork>,
}

/// The zones the policy carries: the phone's zones while the mode is Regularly, none otherwise
/// (decision 7: Off and Only when asked keep zones stored but unwatched).
pub fn policy_zones(
    mode: &str,
    zones: &[Zone],
    networks: &[crate::zone_networks::ZoneNetwork],
) -> Vec<PolicyZone> {
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
            networks: crate::zone_networks::policy_networks(networks, z.id),
        })
        .collect()
}

/// The Regularly interval the phone is served (decision 3): `max(chosen, 30)` while the mode is
/// Regularly, the phone has zones and its watch holds fences (`watching`); the parent's choice
/// otherwise - no zones, no watch reported yet, a phone whose fences couldn't be registered.
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
/// ignored since the launcher's data was created (a running count, so a lost report loses none);
/// design 131: the attempts in which Wi-Fi and the satellites disagreed, a running count too
/// (`wifi_disagreed`, 0 when a launcher doesn't send it).
#[derive(Serialize, Deserialize, Clone, Copy, Debug, PartialEq, Eq)]
pub struct ZoneWatch {
    pub fences: i64,
    pub motion_sensor: bool,
    pub location_on: bool,
    pub background_location: bool,
    pub mock_ignored: i64,
    #[serde(default)]
    pub wifi_disagreed: i64,
}

/// The phone's `zone_watch` through its known keys (the first five, of the right types; at most
/// 100 fences and 10^9 fixes; `wifi_disagreed` when it is one, else 0), as stored; `None` for
/// anything else.
pub fn sanitize_zone_watch(value: &serde_json::Value) -> Option<String> {
    let count = |key: &str, max: i64| value.get(key)?.as_i64().filter(|n| (0..=max).contains(n));
    let flag = |key: &str| value.get(key)?.as_bool();
    let watch = ZoneWatch {
        fences: count("fences", 100)?,
        motion_sensor: flag("motion_sensor")?,
        location_on: flag("location_on")?,
        background_location: flag("background_location")?,
        mock_ignored: count("mock_ignored", 1_000_000_000)?,
        wifi_disagreed: count("wifi_disagreed", 1_000_000_000).unwrap_or(0),
    };
    let mut stored = serde_json::to_value(watch).ok()?;
    // Design 138 D3: the connected network for the zone page's Confirm, and the struck
    // confirmations - each kept only in its shape, a bad one dropped alone.
    if let Some(wifi) = value.get("wifi").and_then(crate::zone_networks::clean_wifi) {
        stored["wifi"] = serde_json::to_value(wifi).ok()?;
    }
    let struck = value
        .get("wifi_struck")
        .map(crate::zone_networks::clean_struck)
        .unwrap_or_default();
    if !struck.is_empty() {
        stored["wifi_struck"] = serde_json::json!(struck);
    }
    serde_json::to_string(&stored)
        .ok()
        .filter(|json| json.len() <= ZONE_WATCH_MAX)
}

/// A stored `zone_watch` read back; `None` for nothing or anything [sanitize_zone_watch] didn't
/// write.
pub fn parse_zone_watch(stored: Option<&str>) -> Option<ZoneWatch> {
    serde_json::from_str(stored?).ok()
}

/// The security log's line when the phone's count of ignored mock fixes rose past the count
/// last logged (`mock_location_ignored`, "Made-up locations ignored"): the phone and the count,
/// never a place.
pub fn mock_log_line(logged: i64, count: i64) -> Option<crate::security::Detail> {
    let rose = count - logged;
    (rose > 0).then(|| {
        crate::security::Detail::counted(
            "security.detail.mock_locations",
            rose,
            &[("total", &count.to_string())],
        )
    })
}

/// The report's mock count against `devices.mock_logged` (qa-design-64-step2-code Q-9, F-5): the
/// count last logged is read, a rise written **and its security line inserted**
/// (`mock_location_ignored`, the columns `security::record_security_event` writes) in one
/// `BEGIN IMMEDIATE` transaction, so two parallel reports - or a report without `zone_watch` in
/// between - log each rise once, the line's "rose" is the real rise, and the counter never moves
/// without its line (a failure rolls both back; the next report tries again). `Some(line)` for
/// the report that wrote it. A lower count (the launcher's data cleared) is taken silently and
/// the date cleared (the row's "the last ..." belonged to the higher count). A re-enrollment
/// clears both (`device_api::enroll`).
pub async fn mock_rise(
    db: &sqlx::SqlitePool,
    device_id: i64,
    watch: Option<&ZoneWatch>,
) -> Result<Option<crate::security::Detail>, sqlx::Error> {
    let Some(count) = watch.map(|w| w.mock_ignored) else {
        return Ok(None);
    };
    let mut tx = db.begin_with("BEGIN IMMEDIATE").await?;
    let logged: Option<i64> =
        sqlx::query_scalar("SELECT COALESCE(mock_logged, 0) FROM devices WHERE id = ?")
            .bind(device_id)
            .fetch_optional(&mut *tx)
            .await?;
    let Some(logged) = logged else {
        return Ok(None);
    };
    let line = if count < logged {
        sqlx::query("UPDATE devices SET mock_logged = ?, mock_logged_at = NULL WHERE id = ?")
            .bind(count)
            .bind(device_id)
            .execute(&mut *tx)
            .await?;
        None
    } else if count > logged {
        sqlx::query("UPDATE devices SET mock_logged = ?, mock_logged_at = ? WHERE id = ?")
            .bind(count)
            .bind(instant(Utc::now()))
            .bind(device_id)
            .execute(&mut *tx)
            .await?;
        // Within the phone's hourly line budget (QA M2: a count that goes 1, 0, 1, 0 would
        // otherwise write a line on every other report).
        match mock_log_line(logged, count) {
            Some(line) => crate::security::record_phone_event(
                &mut tx,
                device_id,
                crate::security::PhoneLine::MockLocationIgnored,
                line.clone(),
            )
            .await?
            .then_some(line),
            None => None,
        }
    } else {
        None
    };
    tx.commit().await?;
    Ok(line)
}

/// Design 131: the quiet security-log line of the attempts in which Wi-Fi and the satellites
/// disagreed (`phone_wifi_disagreed`, "Wi-Fi and GPS disagreed"): how many since the last line,
/// and in all - never a place.
pub fn wifi_disagreed_line(logged: i64, count: i64) -> Option<crate::security::Detail> {
    let rose = count - logged;
    (rose > 0).then(|| {
        crate::security::Detail::counted(
            "security.detail.wifi_disagreed",
            rose,
            &[("total", &count.to_string())],
        )
    })
}

/// At most one `phone_wifi_disagreed` line in this long (the owner, 2026-11-24: a quiet line); the
/// rises between are summed into the next.
pub const WIFI_DISAGREED_LINE_EVERY: Duration = Duration::hours(24);

/// The report's `wifi_disagreed` against `devices.wifi_disagreed_logged`, as [mock_rise] does: in
/// one `BEGIN IMMEDIATE` transaction the count last logged is read and, when it rose and the last
/// line is older than [WIFI_DISAGREED_LINE_EVERY], the line written with the new count and its
/// time - so two parallel reports log a rise once, and a rise within the day waits for the next
/// line, summed. A lower count (the launcher's data cleared) is taken silently. A re-enrolment
/// clears both (`device_api::enroll`). `Some(line)` for the report that wrote it.
pub async fn wifi_disagreed_rise(
    db: &sqlx::SqlitePool,
    device_id: i64,
    watch: Option<&ZoneWatch>,
    now: DateTime<Utc>,
) -> Result<Option<crate::security::Detail>, sqlx::Error> {
    let Some(count) = watch.map(|w| w.wifi_disagreed) else {
        return Ok(None);
    };
    let mut tx = db.begin_with("BEGIN IMMEDIATE").await?;
    let row: Option<(i64, Option<String>)> = sqlx::query_as(
        "SELECT COALESCE(wifi_disagreed_logged, 0), wifi_disagreed_at FROM devices WHERE id = ?",
    )
    .bind(device_id)
    .fetch_optional(&mut *tx)
    .await?;
    let Some((logged, at)) = row else {
        return Ok(None);
    };
    let recent = at
        .as_deref()
        .and_then(parse_db_utc)
        .is_some_and(|at| now - at < WIFI_DISAGREED_LINE_EVERY && at <= now);
    let line = if count < logged {
        sqlx::query("UPDATE devices SET wifi_disagreed_logged = ? WHERE id = ?")
            .bind(count)
            .bind(device_id)
            .execute(&mut *tx)
            .await?;
        None
    } else if count > logged && !recent {
        sqlx::query(
            "UPDATE devices SET wifi_disagreed_logged = ?, wifi_disagreed_at = ? WHERE id = ?",
        )
        .bind(count)
        .bind(instant(now))
        .bind(device_id)
        .execute(&mut *tx)
        .await?;
        match wifi_disagreed_line(logged, count) {
            Some(line) => crate::security::record_phone_event(
                &mut tx,
                device_id,
                crate::security::PhoneLine::WifiDisagreed,
                line.clone(),
            )
            .await?
            .then_some(line),
            None => None,
        }
    } else {
        None
    };
    tx.commit().await?;
    Ok(line)
}

/// When the phone's mock count last rose (Q-9), for the Locate page's row.
pub async fn mock_logged_at(
    db: &sqlx::SqlitePool,
    device_id: i64,
) -> Result<Option<DateTime<Utc>>, sqlx::Error> {
    let at: Option<Option<String>> =
        sqlx::query_scalar("SELECT mock_logged_at FROM devices WHERE id = ?")
            .bind(device_id)
            .fetch_optional(db)
            .await?;
    Ok(at.flatten().as_deref().and_then(parse_db_utc))
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
/// [MAX_EXTRA_LOCATIONS] of the extras, then the report's own fix, all received at `received`
/// (`models::checked_location_at`). The checked fixes with their stored `captured_at`, how many were
/// refused (not a fix the server can use: `devices.fixes_dropped`) and how many more were left out over the cap.
pub fn report_fixes<'a>(
    extra: &'a [LocationReport],
    location: Option<&'a LocationReport>,
    received: DateTime<Utc>,
) -> (Vec<(&'a LocationReport, String)>, usize, usize) {
    let mut refused = 0;
    let mut checked =
        |loc: &'a LocationReport| match crate::models::checked_location_at(loc, received) {
            Some(at) => Some((loc, at)),
            None => {
                refused += 1;
                None
            }
        };
    let mut fixes: Vec<(&LocationReport, String)> = extra.iter().filter_map(&mut checked).collect();
    // The stored form sorts as text in time order; the newest stay.
    fixes.sort_by(|a, b| a.1.cmp(&b.1));
    let over = fixes.len().saturating_sub(MAX_EXTRA_LOCATIONS);
    fixes.drain(..over);
    fixes.extend(location.and_then(&mut checked));
    (fixes, refused, over)
}

/// Fixes a phone sent that the server refused (`report_fixes`' count): counted on the phone, with the instant, so the
/// Locate page can say so ([fix_refused]).
pub async fn count_refused(db: &sqlx::SqlitePool, device_id: i64, refused: usize) {
    if refused == 0 {
        return;
    }
    if let Err(err) = sqlx::query(
        "UPDATE devices SET fixes_dropped = fixes_dropped + ?, fix_dropped_at = ? WHERE id = ?",
    )
    .bind(i64::try_from(refused).unwrap_or(i64::MAX))
    .bind(instant(Utc::now()))
    .bind(device_id)
    .execute(db)
    .await
    {
        tracing::error!(device_id, %err, "can't count the refused fixes");
    }
}

/// The Locate page's "Position refused": a fix was refused after the newest one stored was received (or none is
/// stored), compared to the second (`received_at` has none finer): a fix stored in the refusal's second clears it.
pub async fn fix_refused(db: &sqlx::SqlitePool, device_id: i64) -> bool {
    sqlx::query_scalar::<_, i64>(
        "SELECT COUNT(*) FROM devices d WHERE d.id = ?1 AND d.fix_dropped_at IS NOT NULL AND NOT EXISTS \
         (SELECT 1 FROM device_locations l WHERE l.device_id = ?1 \
          AND CAST(strftime('%s', l.received_at) AS INTEGER) >= CAST(strftime('%s', d.fix_dropped_at) AS INTEGER))",
    )
    .bind(device_id)
    .fetch_one(db)
    .await
    .is_ok_and(|n| n > 0)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn utc(text: &str) -> DateTime<Utc> {
        parse_db_utc(text).unwrap()
    }

    /// The owner's report (2026-11-27): `still_since_age_ms` only beside `still`, a whole number
    /// of ms from 0 to the fix age's bound, read as the receipt minus it (code QA M3), not before
    /// the enrolment.
    #[test]
    fn still_since_is_taken_only_beside_still_and_in_range() {
        let enrolled = utc("2026-11-01 08:00:00");
        let now = utc("2026-11-27 03:30:00");
        let age = |t: DateTime<Utc>| (now - t).num_milliseconds();
        let went_still = utc("2026-11-26 22:26:00");
        let watch = |v: serde_json::Value| {
            let mut w = serde_json::json!({"fences": 1, "motion_sensor": true, "location_on": true,
                "background_location": true, "mock_ignored": 0, "still": true});
            w["still_since_age_ms"] = v;
            w
        };
        let read = |w: serde_json::Value| report_still_since(Some(&w), Some(enrolled), now);
        assert_eq!(read(watch(age(went_still).into())), Some(went_still));
        let mut not_still = watch(age(went_still).into());
        not_still["still"] = false.into();
        assert_eq!(read(not_still), None, "only beside still");
        assert_eq!(read(watch(0.into())), Some(now), "still from now");
        assert_eq!(read(watch((-1).into())), None, "a negative age");
        assert_eq!(
            read(watch(age(enrolled - Duration::seconds(1)).into())),
            None,
            "before the enrolment"
        );
        assert_eq!(read(watch(age(enrolled).into())), Some(enrolled));
        for junk in [
            serde_json::json!("2045000"),
            serde_json::json!(1.5),
            serde_json::json!(null),
            serde_json::json!(crate::models::MAX_FIX_AGE_MS + 1),
            serde_json::json!(i64::MAX),
        ] {
            assert_eq!(read(watch(junk.clone())), None, "{junk}");
        }
        let w = watch(age(went_still).into());
        assert_eq!(
            report_still_since(Some(&w), None, now),
            None,
            "no enrolment known"
        );
        assert_eq!(report_still_since(None, Some(enrolled), now), None);
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
            class: FixClass::Fine,
        }
    }

    /// A cell fix (design 125b C10) at `place` within `accuracy` metres.
    fn cell(id: i64, at: &str, place: (f64, f64), accuracy: f64) -> Fix {
        Fix {
            class: FixClass::Coarse,
            ..fix(id, at, place, Some(accuracy))
        }
    }

    /// About 21 km past HOME's edge (north).
    const FAR: (f64, f64) = (60.1041, 10.7522);

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
            pending_class: FixClass::Fine,
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
            #[serde(default)]
            provider: Option<String>,
            #[serde(default)]
            check: Option<String>,
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
            let stored = Fix {
                id: 1,
                latitude: row.fix.latitude,
                longitude: row.fix.longitude,
                accuracy_m: row.fix.accuracy_meters,
                at: utc("2026-10-24T08:00:00Z"),
                class: FixClass::of(row.fix.provider.as_deref(), row.fix.check.as_deref()),
            };
            let got = fix_side(&zone, &stored).map_or("unsure", Side::word);
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
        let state = seed(&HOME, Some(&fresh), Some(&fresh), now);
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
            let state = seed(&HOME, Some(&fix), Some(&fix), now);
            assert_eq!(state.pending, None);
            assert_eq!(state.cursor, Some((fix.at, 7)));
        }
        assert_eq!(seed(&HOME, None, None, now), State::default());
    }

    #[test]
    fn a_coarse_newest_fix_seeds_nothing() {
        // Design 125b C10: the newest fix a far cell fix, no fine fix: the cursor only.
        let now = utc("2026-10-24T08:30:00Z");
        let far = cell(9, "2026-10-24T08:25:00Z", FAR, 1000.0);
        let state = seed(&HOME, Some(&far), None, now);
        assert_eq!(state.pending, None);
        assert_eq!(state.cursor, Some((far.at, 9)));
        // A fresh fine fix before it seeds; the cursor stays at the newest.
        let fine = inside(8, "2026-10-24T08:20:00Z");
        let state = seed(&HOME, Some(&far), Some(&fine), now);
        assert_eq!(state.pending, Some((Side::Inside, fine.at)));
        assert_eq!(state.cursor, Some((far.at, 9)));
        // A coarse fix handed in as the fine one seeds nothing either.
        assert_eq!(seed(&HOME, Some(&far), Some(&far), now).pending, None);
    }

    #[test]
    fn a_coarse_fix_is_never_inside_and_outside_only_far() {
        // On the centre within 500 m of a 2 km zone: GPS would be inside, a cell fix is unsure.
        let big = Circle {
            radius_m: 2000,
            ..HOME
        };
        let centre = (HOME.latitude, HOME.longitude);
        assert_eq!(
            fix_side(&big, &fix(1, "2026-10-24T08:00:00Z", centre, Some(500.0))),
            Some(Side::Inside)
        );
        assert_eq!(
            fix_side(&big, &cell(1, "2026-10-24T08:00:00Z", centre, 500.0)),
            None
        );
        // 1 km away within 30 m: GPS outside, a cell fix (under 20 km) unsure.
        assert_eq!(
            fix_side(&HOME, &cell(1, "2026-10-24T08:00:00Z", AWAY, 30.0)),
            None
        );
        assert_eq!(
            fix_side(&HOME, &cell(1, "2026-10-24T08:00:00Z", FAR, 1000.0)),
            Some(Side::Outside)
        );
        // No accuracy: unsure.
        let mut no_accuracy = cell(1, "2026-10-24T08:00:00Z", FAR, 1000.0);
        no_accuracy.accuracy_m = None;
        assert_eq!(fix_side(&HOME, &no_accuracy), None);
        // A near cell fix inside moves the cursor and nothing else: no "arrived".
        let mut state = known(Side::Outside);
        assert_eq!(
            step(
                &mut state,
                &HOME,
                &cell(2, "2026-10-24T08:00:00Z", NEAR, 500.0)
            ),
            None
        );
        assert_eq!(state.pending, None);
        assert_eq!(state.cursor, Some((utc("2026-10-24T08:00:00Z"), 2)));
    }

    #[test]
    fn a_far_coarse_pair_needs_ten_minutes_and_crosses_at_the_first() {
        let mut state = known(Side::Inside);
        assert_eq!(
            step(
                &mut state,
                &HOME,
                &cell(2, "2026-10-24T18:05:00Z", FAR, 1000.0)
            ),
            None
        );
        assert_eq!(
            state.pending,
            Some((Side::Outside, utc("2026-10-24T18:05:00Z")))
        );
        assert_eq!(state.pending_class, FixClass::Coarse);
        // Nine minutes later: nothing - from a cell fix or a fine one.
        assert_eq!(
            step(
                &mut state,
                &HOME,
                &cell(3, "2026-10-24T18:14:00Z", FAR, 1000.0)
            ),
            None
        );
        assert_eq!(
            step(&mut state, &HOME, &outside(4, "2026-10-24T18:14:30Z")),
            None
        );
        assert_eq!(state.side, Some(Side::Inside));
        // Ten minutes after the first: "left", at the first coarse fix's time.
        assert_eq!(
            step(
                &mut state,
                &HOME,
                &cell(5, "2026-10-24T18:15:00Z", FAR, 1000.0)
            ),
            Some(Crossing {
                to: Side::Outside,
                at: utc("2026-10-24T18:05:00Z"),
                confirmed_at: utc("2026-10-24T18:15:00Z"),
            })
        );
        assert_eq!(state.pending_class, FixClass::Fine);
        // A fine fix surely outside confirms a coarse first too, but only after ten minutes.
        let mut state = known(Side::Inside);
        step(
            &mut state,
            &HOME,
            &cell(2, "2026-10-24T18:05:00Z", FAR, 1000.0),
        );
        assert_eq!(
            step(&mut state, &HOME, &outside(3, "2026-10-24T18:15:00Z")).map(|c| c.at),
            Some(utc("2026-10-24T18:05:00Z"))
        );
        // A fine first and a coarse second: ten minutes too.
        let mut state = known(Side::Inside);
        step(
            &mut state,
            &HOME,
            &fix(2, "2026-10-24T18:05:00Z", FAR, Some(30.0)),
        );
        assert_eq!(state.pending_class, FixClass::Fine);
        assert_eq!(
            step(
                &mut state,
                &HOME,
                &cell(3, "2026-10-24T18:07:00Z", FAR, 1000.0)
            ),
            None
        );
        assert!(
            step(
                &mut state,
                &HOME,
                &cell(4, "2026-10-24T18:15:00Z", FAR, 1000.0)
            )
            .is_some()
        );
    }

    #[test]
    fn a_fine_fix_inside_drops_a_coarse_pending_side() {
        let mut state = known(Side::Inside);
        step(
            &mut state,
            &HOME,
            &cell(2, "2026-10-24T18:05:00Z", FAR, 1000.0),
        );
        assert_eq!(
            step(&mut state, &HOME, &inside(3, "2026-10-24T18:08:00Z")),
            None
        );
        assert_eq!(state.pending, None);
        assert_eq!(state.pending_class, FixClass::Fine);
        assert_eq!(
            step(
                &mut state,
                &HOME,
                &cell(4, "2026-10-24T18:20:00Z", FAR, 1000.0)
            ),
            None
        );
        assert_eq!(state.side, Some(Side::Inside));
    }

    /// A Wi-Fi fix that nothing confirmed (design 131).
    fn wifi_alone(id: i64, at: &str, place: (f64, f64)) -> Fix {
        Fix {
            class: FixClass::WifiAlone,
            ..fix(id, at, place, Some(40.0))
        }
    }

    #[test]
    fn a_fix_class_from_its_provider_and_check() {
        assert_eq!(FixClass::of(Some("vibb_wifi"), None), FixClass::WifiAlone);
        assert_eq!(FixClass::of(Some("vibb_apple"), None), FixClass::WifiAlone);
        assert_eq!(
            FixClass::of(Some("vibb_wifi"), Some("link")),
            FixClass::Fine
        );
        assert_eq!(
            FixClass::of(Some("vibb_apple"), Some("gnss")),
            FixClass::Fine
        );
        assert_eq!(FixClass::of(Some("vibb_cell"), None), FixClass::Coarse);
        assert_eq!(FixClass::of(Some("gps"), None), FixClass::Fine);
        assert_eq!(FixClass::of(Some("network"), None), FixClass::Fine);
        assert_eq!(FixClass::of(None, None), FixClass::Fine);
        // The pending fix's class: a fine row preferred when several share its time.
        let row = |p: &str, c: Option<&str>| (Some(p.to_string()), c.map(str::to_string));
        assert_eq!(
            pending_class(&[row("vibb_wifi", None), row("gps", None)]),
            FixClass::Fine
        );
        assert_eq!(
            pending_class(&[row("vibb_cell", None), row("vibb_apple", None)]),
            FixClass::WifiAlone
        );
        assert_eq!(pending_class(&[row("vibb_cell", None)]), FixClass::Coarse);
        assert_eq!(pending_class(&[]), FixClass::Fine, "gone to retention");
    }

    #[test]
    fn a_wifi_fix_alone_inside_starts_nothing_and_drops_no_pending_outside() {
        // Outside, then the Wi-Fi alone inside twice: no pending side, no arrival.
        let mut state = known(Side::Outside);
        assert_eq!(
            step(
                &mut state,
                &HOME,
                &wifi_alone(2, "2026-10-24T18:00:00Z", NEAR)
            ),
            None
        );
        assert_eq!(state.pending, None);
        assert_eq!(
            step(
                &mut state,
                &HOME,
                &wifi_alone(3, "2026-10-24T18:05:00Z", NEAR)
            ),
            None
        );
        assert_eq!(state.side, Some(Side::Outside));
        // Inside, a fine fix outside pending: the Wi-Fi alone inside keeps it pending, and the
        // next fine fix outside confirms "left".
        let mut state = known(Side::Inside);
        step(&mut state, &HOME, &outside(2, "2026-10-24T18:00:00Z"));
        step(
            &mut state,
            &HOME,
            &wifi_alone(3, "2026-10-24T18:01:00Z", NEAR),
        );
        assert!(state.pending.is_some_and(|(s, _)| s == Side::Outside));
        assert_eq!(
            step(&mut state, &HOME, &outside(4, "2026-10-24T18:02:00Z")).map(|c| c.to),
            Some(Side::Outside)
        );
        // A new zone is never seeded from it.
        let alone = wifi_alone(9, "2026-10-24T18:00:00Z", NEAR);
        let seeded = seed(
            &HOME,
            Some(&alone),
            Some(&alone),
            utc("2026-10-24T18:05:00Z"),
        );
        assert_eq!(seeded.pending, None);
    }

    #[test]
    fn a_wifi_fix_alone_confirms_a_fine_arrival_within_ten_minutes() {
        // The satellites see the approach, Wi-Fi confirms it indoors 2 minutes later.
        let mut state = known(Side::Outside);
        step(&mut state, &HOME, &inside(2, "2026-10-24T18:00:00Z"));
        let crossing = step(
            &mut state,
            &HOME,
            &wifi_alone(3, "2026-10-24T18:02:00Z", NEAR),
        );
        assert_eq!(
            crossing,
            Some(Crossing {
                to: Side::Inside,
                at: utc("2026-10-24T18:00:00Z"),
                confirmed_at: utc("2026-10-24T18:02:00Z"),
            })
        );
        // At 11 minutes: nothing, the pending side stays.
        let mut state = known(Side::Outside);
        step(&mut state, &HOME, &inside(2, "2026-10-24T18:00:00Z"));
        assert_eq!(
            step(
                &mut state,
                &HOME,
                &wifi_alone(3, "2026-10-24T18:11:00Z", NEAR)
            ),
            None
        );
        assert!(state.pending.is_some());
        // Within the minute: nothing either.
        let mut state = known(Side::Outside);
        step(&mut state, &HOME, &inside(2, "2026-10-24T18:00:00Z"));
        assert_eq!(
            step(
                &mut state,
                &HOME,
                &wifi_alone(3, "2026-10-24T18:00:30Z", NEAR)
            ),
            None
        );
    }

    #[test]
    fn a_wifi_fix_alone_outside_counts_as_a_fine_fix() {
        let mut state = known(Side::Inside);
        step(
            &mut state,
            &HOME,
            &wifi_alone(2, "2026-10-24T18:00:00Z", AWAY),
        );
        assert_eq!(state.pending_class, FixClass::WifiAlone);
        assert_eq!(
            step(
                &mut state,
                &HOME,
                &wifi_alone(3, "2026-10-24T18:01:00Z", AWAY)
            )
            .map(|c| c.to),
            Some(Side::Outside)
        );
    }

    #[test]
    fn the_wifi_line_comes_with_a_rise() {
        assert_eq!(wifi_disagreed_line(0, 0), None);
        assert_eq!(
            wifi_disagreed_line(1, 3).map(|d| crate::security::worded_test(&d)),
            Some("2 times (3 in all)".to_string())
        );
        assert_eq!(wifi_disagreed_line(3, 1), None, "a reset count");
    }

    #[test]
    fn the_fine_only_filter_names_every_coarse_provider() {
        for provider in COARSE_PROVIDERS {
            assert!(FINE_ONLY.contains(&format!("'{provider}'")), "{provider}");
        }
        // Design 131: and the Wi-Fi providers, without a check.
        for provider in crate::models::WIFI_PROVIDERS {
            assert!(FINE_ONLY.contains(&format!("'{provider}'")), "{provider}");
        }
        assert!(FINE_ONLY.contains("fix_check IS NULL"));
        assert_eq!(
            FINE_ONLY.matches("'vibb_").count(),
            COARSE_PROVIDERS.len() + crate::models::WIFI_PROVIDERS.len()
        );
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
            wifi_disagreed: 0,
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
                wifi_disagreed: 0,
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
        // Design 131: `wifi_disagreed` kept when it is a count, else 0 (an older launcher's report).
        let with = serde_json::json!({"fences": 2, "motion_sensor": false, "location_on": true,
            "background_location": true, "mock_ignored": 7, "wifi_disagreed": 4});
        let kept = parse_zone_watch(sanitize_zone_watch(&with).as_deref()).unwrap();
        assert_eq!(kept.wifi_disagreed, 4);
        let mut bad = with.clone();
        bad["wifi_disagreed"] = serde_json::json!("four");
        assert_eq!(
            parse_zone_watch(sanitize_zone_watch(&bad).as_deref())
                .unwrap()
                .wifi_disagreed,
            0
        );
    }

    #[test]
    fn the_mock_line_comes_with_each_rise() {
        assert_eq!(mock_log_line(0, 0), None);
        assert_eq!(
            mock_log_line(0, 2).map(|d| crate::security::worded_test(&d)),
            Some(
                "The phone ignored 2 made-up locations - an app pretended to be somewhere else \
                 (2 in all)"
                    .to_string()
            )
        );
        assert!(
            crate::security::worded_test(&mock_log_line(2, 5).unwrap())
                .contains("ignored 3 made-up")
        );
        assert_eq!(mock_log_line(5, 5), None);
        assert_eq!(mock_log_line(5, 1), None, "a reset count");
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
            ..Default::default()
        };
        let mut extra: Vec<LocationReport> = (0..25).rev().map(|m| loc(m, 59.0)).collect();
        extra.push(loc(30, 95.0));
        let own = loc(40, 59.0);
        let (fixes, refused, over) = report_fixes(&extra, Some(&own), Utc::now());
        let dropped = refused + over;
        assert_eq!(dropped, 6, "5 over the cap and one off the Earth");
        assert_eq!(fixes.len(), 21);
        assert_eq!(fixes[0].1, "2026-10-24T08:05:00.000Z");
        assert_eq!(fixes[19].1, "2026-10-24T08:24:00.000Z");
        assert_eq!(fixes[20].1, "2026-10-24T08:40:00.000Z");
        let (none, refused, over) = report_fixes(&[], None, Utc::now());
        let dropped = refused + over;
        assert!(none.is_empty());
        assert_eq!(dropped, 0);
    }

    #[test]
    fn the_policy_carries_the_zones_only_while_regularly() {
        let zone = Zone {
            id: 4,
            zone_id: 2,
            device_id: 1,
            name: "Home".to_string(),
            latitude: HOME.latitude,
            longitude: HOME.longitude,
            radius_m: 150,
            side: None,
            side_since: None,
            pending_side: None,
            pending_at: None,
            evaluated_at: None,
            evaluated_fix_id: None,
        };
        let zones = [zone];
        assert_eq!(
            policy_zones("interval", &zones, &[]),
            [PolicyZone {
                id: 4,
                latitude: HOME.latitude,
                longitude: HOME.longitude,
                radius_m: 150,
                networks: Vec::new(),
            }]
        );
        assert!(policy_zones("on_request", &zones, &[]).is_empty());
        assert!(policy_zones("off", &zones, &[]).is_empty());
        let json = serde_json::to_string(&policy_zones("interval", &zones, &[])).unwrap();
        assert_eq!(
            json,
            "[{\"id\":4,\"latitude\":59.9139,\"longitude\":10.7522,\"radius_m\":150,\"networks\":[]}]"
        );
        // Design 138 D13: a zone's unblocked rows only.
        let row = |id: i64, blocked: bool| crate::zone_networks::ZoneNetwork {
            id,
            zone_id: 4,
            ssid: "Net-A".into(),
            bssid: format!("00:00:5e:00:53:0{id}"),
            mld: None,
            blocked_at: blocked.then(|| "2026-11-26 10:00:00".to_string()),
        };
        let served = policy_zones("interval", &zones, &[row(1, false), row(2, true)]);
        assert_eq!(served[0].networks.len(), 1);
        assert_eq!(served[0].networks[0].id, 1);
    }
}
