//! What is wrong with a phone's app push, for Attention and Device › Apps (design 108c), and the delivery-time figure.
//!
//! Each signal is computed when a page renders, from facts that happened: a push, a pull, a refusal. A quiet app is
//! never a fault. Every read is small (the Devices list renders often): one `LIMIT 1` on the queue, one small read of
//! the phone's registrations, and the rest only when a push is 30 minutes old.

use std::collections::BTreeSet;

use chrono::{DateTime, Duration, Utc};
use sqlx::SqlitePool;

use crate::display_time::parse_db_utc;

/// The oldest queued push must be this old before the "waiting" row can be raised.
pub const WAITING_MIN_AGE_MIN: i64 = 30;
/// Arm (a): policies served after the push was queued (plus [WAITING_SETTLE_MIN]) and before two minutes ago.
pub const WAITING_POLICIES: usize = 3;
pub const WAITING_SETTLE_MIN: i64 = 10;
/// Arm (b): a later pull handed the push out again this long after the first.
pub const REHANDED_MIN: i64 = 2;
/// A run of refusals or outside pushes must go on this long, with the phone in touch a day in.
pub const RUN_HOURS: i64 = 24;
/// The pushes' marks fade after this many days (the page's line window).
pub const MARK_DAYS: i64 = 7;
/// Below this many samples there is no 95th percentile.
pub const P95_MIN_SAMPLES: usize = 20;

/// What a phone's app push needs from the parent; see the module's rules in design 108c §1.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct AppPushAttention {
    /// When the oldest queued push was accepted, while it is raised (§1.1).
    pub waiting_since: Option<DateTime<Utc>>,
    /// Packages with an `unsigned` refusal run past a day, with the raised registration's stamp (`vapid_refused_at`).
    pub refused: Vec<(String, String)>,
    /// Packages whose pushes still went outside a day or more into the run.
    pub outside: Vec<String>,
}

/// The stamps' text form, so the `(device_id, reported_at)` index range is used.
fn db_text(at: DateTime<Utc>) -> String {
    at.format("%Y-%m-%d %H:%M:%S").to_string()
}

/// A registration's package and its run stamps: refused at / since / why, outside at / since, gateway at.
type RunRow = (
    String,
    Option<String>,
    Option<String>,
    Option<String>,
    Option<String>,
    Option<String>,
    Option<String>,
);

/// A phone's app push problems. `reported` is the latest status report's `reported_at` (never `last_seen_at`:
/// design 91's locked report bumps that before the first unlock, where UP neither pulls nor heals).
pub async fn attention(
    state: &crate::AppState,
    device_id: i64,
    reported: Option<&str>,
    now: DateTime<Utc>,
) -> Result<AppPushAttention, sqlx::Error> {
    let reported = reported.and_then(parse_db_utc);
    let mut out = AppPushAttention::default();
    let rows: Vec<RunRow> =
        sqlx::query_as(
            "SELECT package, vapid_refused_at, vapid_refused_since, vapid_refused_why, \
               outside_gateway_at, outside_since, gateway_at \
             FROM up_registrations WHERE device_id = ? \
             AND (vapid_refused_since IS NOT NULL OR outside_since IS NOT NULL) ORDER BY package, id",
        )
        .bind(device_id)
        .fetch_all(&state.db)
        .await?;
    let day = Duration::hours(RUN_HOURS);
    let week = Duration::days(MARK_DAYS);
    let mut outside = BTreeSet::new();
    for (package, refused_at, refused_since, why, outside_at, outside_since, gateway_at) in &rows {
        // A run's end must come a day or more after its start, and the phone must have reported that long after it.
        let in_touch = |since: DateTime<Utc>| reported.is_some_and(|r| r >= since + day);
        if let (Some(at), Some(since)) = (
            refused_at.as_deref().and_then(parse_db_utc),
            refused_since.as_deref().and_then(parse_db_utc),
        ) && why.as_deref().unwrap_or("unsigned") == "unsigned"
            && at > now - week
            && at >= since + day
            && in_touch(since)
            && !out.refused.iter().any(|(p, _)| p == package)
        {
            out.refused
                .push((package.clone(), refused_at.clone().unwrap_or_default()));
        }
        if let (Some(at), Some(since)) = (
            outside_at.as_deref().and_then(parse_db_utc),
            outside_since.as_deref().and_then(parse_db_utc),
        ) {
            let marked = at > now - week
                && gateway_at
                    .as_deref()
                    .and_then(parse_db_utc)
                    .is_none_or(|g| g < at);
            if marked && at >= since + day && in_touch(since) {
                outside.insert(package.clone());
            }
        }
    }
    out.outside = outside.into_iter().collect();
    out.waiting_since = waiting_since(state, device_id, now).await?;
    Ok(out)
}

/// §1.1: when the phone's oldest live push was accepted, if it is raised.
async fn waiting_since(
    state: &crate::AppState,
    device_id: i64,
    now: DateTime<Utc>,
) -> Result<Option<DateTime<Utc>>, sqlx::Error> {
    let now_ms = now.timestamp_millis();
    let oldest: Option<(i64, Option<i64>, Option<i64>)> = sqlx::query_as(
        "SELECT created_at_ms, fetched_ms, handed_ms FROM up_messages \
         WHERE device_id = ? AND expires_at_ms > ? ORDER BY id LIMIT 1",
    )
    .bind(device_id)
    .bind(now_ms)
    .fetch_optional(&state.db)
    .await?;
    let Some((created, fetched, handed)) = oldest else {
        return Ok(None);
    };
    if now_ms - created < WAITING_MIN_AGE_MIN * 60_000 {
        return Ok(None);
    }
    let raised = match (fetched, handed) {
        // (b) Fetched but never acknowledged: a later pull handed it out again well after the ack flush.
        // The phone also had 2 minutes to acknowledge the latest hand-out: a phone catching up after a lost ack
        // acknowledges within seconds and never blinks the row.
        (Some(fetched), Some(handed)) => {
            handed >= fetched + REHANDED_MIN * 60_000 && now_ms - handed >= REHANDED_MIN * 60_000
        }
        (Some(_), None) => false,
        // (a) Never fetched although the phone took its policy three times, with no report saying it couldn't apply it.
        // Design 115 §2.8: only the policies served after the last report that said the phone holds on purpose.
        (None, _) => {
            let mut from = DateTime::from_timestamp_millis(created).unwrap_or(now)
                + Duration::minutes(WAITING_SETTLE_MIN);
            let to = now - Duration::minutes(2);
            if let Some(held) = last_held_report(&state.db, device_id, from, to).await? {
                from = from.max(held + Duration::seconds(1));
            }
            state.unified_push.policies_served(device_id, from, to) >= WAITING_POLICIES
                && !policy_problem_between(&state.db, device_id, from, to).await?
        }
    };
    Ok(raised.then(|| DateTime::from_timestamp_millis(created).unwrap_or(now)))
}

/// Design 115 §2.8: the last report in the window that said the phone holds its queue on purpose - only with a sink
/// reason in the same report (QA M2: the server's own reading, so a hold that is wrong never hides the row).
async fn last_held_report(
    db: &SqlitePool,
    device_id: i64,
    from: DateTime<Utc>,
    to: DateTime<Utc>,
) -> Result<Option<DateTime<Utc>>, sqlx::Error> {
    let last: Option<String> = sqlx::query_scalar(
        "SELECT MAX(reported_at) FROM device_status WHERE device_id = ? AND reported_at >= ? \
         AND reported_at <= ? AND up_held IS NOT NULL \
         AND COALESCE(json_extract(mobile_data_json, '$.sink'), 'off') <> 'off'",
    )
    .bind(device_id)
    .bind(db_text(from))
    .bind(db_text(to))
    .fetch_one(db)
    .await?;
    Ok(last.as_deref().and_then(parse_db_utc))
}

async fn policy_problem_between(
    db: &SqlitePool,
    device_id: i64,
    from: DateTime<Utc>,
    to: DateTime<Utc>,
) -> Result<bool, sqlx::Error> {
    let found: Option<i64> = sqlx::query_scalar(
        "SELECT 1 FROM device_status WHERE device_id = ? AND reported_at >= ? AND reported_at <= ? \
         AND policy_state IS NOT NULL AND policy_state <> 'ok' LIMIT 1",
    )
    .bind(device_id)
    .bind(db_text(from))
    .bind(db_text(to))
    .fetch_optional(db)
    .await?;
    Ok(found.is_some())
}

/// The delivery-time figure (design 108c §7.3).
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Figure {
    pub count: usize,
    pub median_ms: i64,
    /// Only from [P95_MIN_SAMPLES] samples: below that the nearest-rank 95th percentile is just the slowest push.
    pub p95_ms: Option<i64>,
    /// A phone's samples reached the cap, so "the newest" are counted.
    pub capped: bool,
}

/// Both values are nearest-rank (element `ceil(p * n)`, 1-based), so each is a time that really happened.
/// `samples` is sorted ascending; `None` for no samples.
pub fn delivery_figure(samples: &[i64], capped: bool) -> Option<Figure> {
    let n = samples.len();
    if n == 0 {
        return None;
    }
    let rank =
        |numerator: usize, denominator: usize| samples[(n * numerator).div_ceil(denominator) - 1];
    Some(Figure {
        count: n,
        median_ms: rank(1, 2),
        p95_ms: (n >= P95_MIN_SAMPLES).then(|| rank(19, 20)),
        capped,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    /// §7.3: nearest-rank values, no 95% below 20 samples.
    #[test]
    fn the_figure() {
        let samples = |n: i64| (1..=n).map(|i| i * 10).collect::<Vec<_>>();
        assert_eq!(delivery_figure(&[], false), None);
        let one = delivery_figure(&[70], false).unwrap();
        assert_eq!((one.count, one.median_ms, one.p95_ms), (1, 70, None));
        let nineteen = delivery_figure(&samples(19), false).unwrap();
        assert_eq!((nineteen.median_ms, nineteen.p95_ms), (100, None));
        let twenty = delivery_figure(&samples(20), true).unwrap();
        assert_eq!((twenty.median_ms, twenty.p95_ms), (100, Some(190)));
        assert!(twenty.capped);
        let forty = delivery_figure(&samples(40), false).unwrap();
        assert_eq!(
            (forty.count, forty.median_ms, forty.p95_ms),
            (40, 200, Some(380))
        );
    }
}
