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
//!   coordinates are the same fix - every report re-sends the cached one), then every zone of the
//!   phone is stepped through every stored fix after its cursor, in time order, the zones' state
//!   written and the events raised - one `BEGIN IMMEDIATE` transaction, so a crash or a restart
//!   leaves either all of it or none (the phone sends its fix again) and two parallel reports
//!   take turns. An event is keyed `<zone>:<side>:<first fix>` on top, so one crossing is one row.
//! - **The events** (`parent_events::Kind::ZoneArrived`/`ZoneLeft`) are stored as a key plus
//!   parameters (the user's decision 5), raised only when the zone's switch for that direction is
//!   on, pushed only while news ([NEWS_FOR]) and to the parents whose "Zones" switch is on.

use chrono::{DateTime, Duration, SecondsFormat, Utc};
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
/// At most this many stored fixes are evaluated in one intake; the cursor takes the rest the
/// next time.
const EVALUATE_AT_MOST: i64 = 1000;

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

    /// The parent is here now: the zone's confirmed side is inside.
    pub fn here(&self) -> bool {
        self.side.as_deref() == Some("inside")
    }
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

/// The phone's newest stored fix as a cursor `(captured_at, id)`: where a new or moved zone
/// starts, so the history before it is never judged against it (decision 4).
pub async fn newest_fix(
    conn: &mut SqliteConnection,
    device_id: i64,
) -> Result<Option<(String, i64)>, sqlx::Error> {
    sqlx::query_as(
        "SELECT captured_at, id FROM device_locations WHERE device_id = ? \
         ORDER BY captured_at DESC, id DESC LIMIT 1",
    )
    .bind(device_id)
    .fetch_optional(&mut *conn)
    .await
}

/// Forgets a zone's side (a moved centre, a changed radius): unknown again, the cursor at the
/// phone's newest fix, so the next confirmed side is set silently and the history isn't read.
pub async fn start_over(
    conn: &mut SqliteConnection,
    zone_id: i64,
    device_id: i64,
) -> Result<(), sqlx::Error> {
    let cursor = newest_fix(conn, device_id).await?;
    sqlx::query(
        "UPDATE device_zones SET side = NULL, side_since = NULL, pending_side = NULL, \
         pending_at = NULL, evaluated_at = ?, evaluated_fix_id = ? WHERE id = ?",
    )
    .bind(cursor.as_ref().map(|c| c.0.clone()))
    .bind(cursor.map(|c| c.1))
    .bind(zone_id)
    .execute(&mut *conn)
    .await?;
    Ok(())
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
/// in one `BEGIN IMMEDIATE` transaction: stored once ([store_fix]), every zone of the phone
/// evaluated on the stored fixes after its cursor ([evaluate_stored]), the events raised; then
/// the push sender's wake-up. A failure rolls all of it back and is logged (the report's other
/// parts are stored already; the phone sends the fix again).
pub async fn intake(state: &AppState, device_id: i64, loc: &LocationReport, captured_at: &str) {
    let now = Utc::now();
    let result = async {
        let mut tx = state.db.begin_with("BEGIN IMMEDIATE").await?;
        store_fix(&mut tx, device_id, loc, captured_at).await?;
        let events = evaluate_stored(&mut tx, device_id, now).await?;
        let mut raised = false;
        for event in &events {
            raised |= parent_events::raise(&mut tx, event).await?.is_some();
        }
        tx.commit().await?;
        Ok::<_, sqlx::Error>(raised)
    };
    match result.await {
        Ok(true) => state.push.wake(),
        Ok(false) => {}
        Err(err) => tracing::error!(device_id, %err, "can't store the fix or judge the zones"),
    }
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
    fn the_distance_is_haversine() {
        // A degree of latitude on the mean sphere.
        let d = distance_m(0.0, 0.0, 1.0, 0.0);
        assert!((d - 111_195.08).abs() < 0.01, "{d}");
        // Across the antimeridian and across the pole.
        assert!((distance_m(0.0, 179.9995, 0.0, -179.9995) - 111.2).abs() < 0.1);
        assert!((distance_m(89.9995, 0.0, 89.9995, 180.0) - 111.2).abs() < 0.1);
    }
}
