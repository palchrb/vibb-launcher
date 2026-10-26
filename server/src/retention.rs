//! What the server keeps about a phone, and for how long (cleanup round 2026-10-06). Principle:
//! collect only what's needed to manage the phone, delete on a schedule, never store
//! notification or message content.

use std::time::Duration;

use sqlx::SqlitePool;

use crate::AppState;

/// While a phone's blocked-domain log is on (it is off by default, `device_policy.dns_log_enabled`),
/// its entries are kept this many days.
pub const DNS_LOG_RETENTION_DAYS: i64 = 7;
/// What a parent can choose for a phone's location history (`device_policy.location_retention_days`).
pub const LOCATION_RETENTION_DAYS: [i64; 4] = [1, 7, 14, 30];
pub const DEFAULT_LOCATION_RETENTION_DAYS: i64 = 7;
/// Status reports - the history of screen time (`time_state_json`), call state and app lists.
/// The newest report per phone is always kept (the device page shows it).
pub const STATUS_HISTORY_DAYS: i64 = 30;
/// Launcher crash reports, counted from when the phone last reported that crash.
pub const CRASH_REPORT_DAYS: i64 = 30;
/// The security log (`security_events`, design 22 QA c): sign-ins, refused requests, enrollments.
pub const SECURITY_EVENT_DAYS: i64 = 180;
/// Web Push deliveries, and subscriptions after they were turned off (design 35 §2.4).
pub const PUSH_DAYS: i64 = 30;
/// The phones' commands (`device_commands`, design 48 §1.6), by when they were asked - except one
/// that may still reach its phone.
pub const COMMAND_DAYS: i64 = 30;
/// How often [run_pruning] runs (and once at startup).
const PRUNE_INTERVAL: Duration = Duration::from_secs(60 * 60);

/// Rows [prune] deleted, per table.
#[derive(Debug, Default, PartialEq, Eq)]
pub struct Pruned {
    pub dns_events: u64,
    pub locations: u64,
    pub status_reports: u64,
    pub crashes: u64,
    pub security_events: u64,
    pub pin_events: u64,
    pub parent_events: u64,
    /// Push deliveries and turned-off subscriptions (design 35 §2.4).
    pub push_rows: u64,
    /// The phones' commands (design 48 §1.6).
    pub commands: u64,
    /// The tables whose statement failed in this pass (logged; security review L9): every other
    /// table was pruned all the same.
    pub failed: Vec<&'static str>,
}

/// One statement's rows, or 0 with the table noted in `failed` and the error logged - a busy or
/// broken table never keeps the pass from the others (security review L9).
fn rows(
    failed: &mut Vec<&'static str>,
    table: &'static str,
    done: Result<sqlx::sqlite::SqliteQueryResult, sqlx::Error>,
) -> u64 {
    match done {
        Ok(done) => done.rows_affected(),
        Err(err) => {
            tracing::error!(table, %err, "pruning a table failed; the pass goes on");
            if !failed.contains(&table) {
                failed.push(table);
            }
            0
        }
    }
}

/// One pruning pass:
/// - blocked domains older than [DNS_LOG_RETENTION_DAYS], and every entry of a phone whose log
///   is off (an older launcher may still send them; the server drops those at the door too);
/// - locations older than the phone's `location_retention_days` (by the server's receive time),
///   except the newest fix per phone, which Find My Device shows as "last seen";
/// - status reports older than [STATUS_HISTORY_DAYS], except the newest per phone and the newest
///   one that reported call enforcement (`call_policy_v1`);
/// - crash reports not reported again for [CRASH_REPORT_DAYS];
/// - security log entries older than [SECURITY_EVENT_DAYS];
/// - the phones' parent-PIN events after `parent_pin::PIN_EVENT_DAYS` (by when they arrived);
/// - a zone's arrivals and departures with the phone's location history (`location_retention_days`,
///   by when they were stored; design 64 decision 6);
/// - the parent's events after `parent_events::EVENT_DAYS`, push deliveries and turned-off
///   subscriptions after [PUSH_DAYS] (design 35 §2.4);
/// - commands asked more than [COMMAND_DAYS] ago, except one the phone may still get: never
///   delivered nor cancelled, and not an expiring kind (`models::EXPIRING_COMMANDS` - those are
///   past their hour) - a lost phone that comes back weeks later must still be wiped or locked
///   (the policy's pop, `device_api::build_policy`). A delivered one is long past its re-delivery
///   window; an acknowledged or cancelled one is done (design 48 §1.6).
///
/// Each statement runs on its own: one that fails is logged and named in [Pruned::failed], and the
/// rest of the pass goes on (security review L9).
pub async fn prune(db: &SqlitePool) -> Pruned {
    let mut failed = Vec::new();
    let failed = &mut failed;
    let dns_events = rows(
        failed,
        "device_dns_events",
        sqlx::query(
            "DELETE FROM device_dns_events \
         WHERE received_at < datetime('now', '-' || ? || ' days') \
            OR device_id NOT IN (SELECT device_id FROM device_policy WHERE dns_log_enabled = 1)",
        )
        .bind(DNS_LOG_RETENTION_DAYS)
        .execute(db)
        .await,
    );
    // A fix dated in the future (stored before the intake refused them - QA of the security wave,
    // Low 3) would be "the newest" for good, so it goes first.
    let future = rows(
        failed,
        "device_locations",
        sqlx::query(
            "DELETE FROM device_locations \
         WHERE captured_at > strftime('%Y-%m-%dT%H:%M:%fZ', 'now', '+5 minutes')",
        )
        .execute(db)
        .await,
    );
    let locations = future
        + rows(
            failed,
            "device_locations",
            sqlx::query(
                "DELETE FROM device_locations \
         WHERE received_at < datetime('now', '-' || MAX(1, COALESCE( \
                 (SELECT p.location_retention_days FROM device_policy p \
                  WHERE p.device_id = device_locations.device_id), ?)) || ' days') \
           AND id NOT IN (SELECT id FROM (SELECT id, ROW_NUMBER() OVER ( \
                 PARTITION BY device_id ORDER BY captured_at DESC, id DESC) AS newest \
               FROM device_locations) WHERE newest = 1)",
            )
            .bind(DEFAULT_LOCATION_RETENTION_DAYS)
            .execute(db)
            .await,
        );
    // Also kept: the newest report per phone that said it enforces calls - the calls warning
    // "stopped reporting that it enforces calls" (a downgraded launcher) rests on it (qa-cleanup #7).
    let status_reports = rows(
        failed,
        "device_status",
        sqlx::query(
            "DELETE FROM device_status \
         WHERE reported_at < datetime('now', '-' || ? || ' days') \
           AND id NOT IN (SELECT MAX(id) FROM device_status GROUP BY device_id) \
           AND id NOT IN (SELECT MAX(id) FROM device_status WHERE capabilities_json LIKE ? \
                          GROUP BY device_id)",
        )
        .bind(STATUS_HISTORY_DAYS)
        .bind(format!(
            "%\"{}\"%",
            crate::handlers::calls::CALL_POLICY_CAPABILITY
        ))
        .execute(db)
        .await,
    );
    let crashes = rows(
        failed,
        "device_crashes",
        sqlx::query(
            "DELETE FROM device_crashes WHERE reported_at < datetime('now', '-' || ? || ' days')",
        )
        .bind(CRASH_REPORT_DAYS)
        .execute(db)
        .await,
    );
    let security_events = rows(
        failed,
        "security_events",
        sqlx::query(
            "DELETE FROM security_events WHERE created_at < datetime('now', '-' || ? || ' days')",
        )
        .bind(SECURITY_EVENT_DAYS)
        .execute(db)
        .await,
    );
    let pin_events = rows(failed, "device_pin_events", sqlx::query(
        "DELETE FROM device_pin_events WHERE received_at < datetime('now', '-' || ? || ' days')",
    )
    .bind(crate::parent_pin::PIN_EVENT_DAYS)
    .execute(db)
    .await);
    // A zone's arrivals and departures are location records (design 64 decision 6): they go
    // with the phone's location history, not after the inbox's 90 days.
    let zone_rows = rows(
        failed,
        "parent_events",
        sqlx::query(
            "DELETE FROM parent_events WHERE kind IN (?, ?) \
           AND created_at < datetime('now', '-' || MAX(1, COALESCE( \
                 (SELECT p.location_retention_days FROM device_policy p \
                  WHERE p.device_id = parent_events.device_id), ?)) || ' days')",
        )
        .bind(crate::parent_events::Kind::ZoneArrived.wire())
        .bind(crate::parent_events::Kind::ZoneLeft.wire())
        .bind(DEFAULT_LOCATION_RETENTION_DAYS)
        .execute(db)
        .await,
    );
    // The shutdown's last position (2026-10-25) in a `phone_off`'s parameters and detail goes
    // with the location history too; the event itself stays its 90 days.
    rows(
        failed,
        "parent_events",
        sqlx::query(
            "UPDATE parent_events SET params = NULL, detail = NULL WHERE kind = ? \
           AND params IS NOT NULL \
           AND created_at < datetime('now', '-' || MAX(1, COALESCE( \
                 (SELECT p.location_retention_days FROM device_policy p \
                  WHERE p.device_id = parent_events.device_id), ?)) || ' days')",
        )
        .bind(crate::parent_events::Kind::PhoneOff.wire())
        .bind(DEFAULT_LOCATION_RETENTION_DAYS)
        .execute(db)
        .await,
    );
    let parent_events = zone_rows
        + rows(
            failed,
            "parent_events",
            sqlx::query(
                "DELETE FROM parent_events WHERE created_at < datetime('now', '-' || ? || ' days')",
            )
            .bind(crate::parent_events::EVENT_DAYS)
            .execute(db)
            .await,
        );
    let deliveries = rows(
        failed,
        "push_deliveries",
        sqlx::query(
            "DELETE FROM push_deliveries WHERE created_at < datetime('now', '-' || ? || ' days')",
        )
        .bind(PUSH_DAYS)
        .execute(db)
        .await,
    );
    let gone = rows(failed, "push_subscriptions", sqlx::query(
        "DELETE FROM push_subscriptions \
         WHERE state = 'gone' AND COALESCE(gone_at, created_at) < datetime('now', '-' || ? || ' days')",
    )
    .bind(PUSH_DAYS)
    .execute(db)
    .await);
    let expiring = crate::models::EXPIRING_COMMANDS;
    let sql = format!(
        "DELETE FROM device_commands WHERE requested_at < datetime('now', '-' || ? || ' days') \
           AND (acknowledged_at IS NOT NULL OR cancelled_at IS NOT NULL \
                OR delivered_at IS NOT NULL OR command IN ({}))",
        vec!["?"; expiring.len()].join(", ")
    );
    let commands = sqlx::query(&sql);
    let commands = rows(
        failed,
        "device_commands",
        expiring
            .iter()
            .fold(commands.bind(COMMAND_DAYS), |query, kind| query.bind(*kind))
            .execute(db)
            .await,
    );
    Pruned {
        dns_events,
        locations,
        status_reports,
        crashes,
        security_events,
        pin_events,
        parent_events,
        push_rows: deliveries + gone,
        commands,
        failed: std::mem::take(failed),
    }
}

/// Background task (spawned from `main` only): [prune] at startup and every hour.
pub async fn run_pruning(state: AppState) {
    let mut interval = tokio::time::interval(PRUNE_INTERVAL);
    loop {
        interval.tick().await;
        let pruned = prune(&state.db).await;
        if pruned != Pruned::default() {
            tracing::info!(?pruned, "Pruned old data");
        }
    }
}
