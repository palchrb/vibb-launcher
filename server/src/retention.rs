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

/// A phone's storage budget (security review L4): what one token can add in a rolling day, so a
/// leaked token or a rooted phone can't fill the disk at the device API's rate limit. Far above
/// what a phone sends: reports (every 10 minutes at the shortest location interval, 144 a day,
/// plus every nudge, zone crossing and request - QA L-c), fixes, the blocked-domain log's
/// 10-minute dedup per domain.
pub const STATUS_ROWS_A_DAY: i64 = 500;
/// Distinct crash hashes kept per phone; past it the oldest reported goes.
pub const CRASH_HASHES: i64 = 50;

/// The append-only tables with a daily budget per phone: past it, a phone's new rows are not
/// stored that day (logged).
#[derive(Clone, Copy, Debug)]
pub enum Budget {
    Locations,
    DnsEvents,
    PinEvents,
}

impl Budget {
    fn rows_a_day(self) -> i64 {
        match self {
            Budget::Locations => 3_000,
            Budget::DnsEvents => 10_000,
            Budget::PinEvents => 500,
        }
    }

    fn count_sql(self) -> &'static str {
        match self {
            Budget::Locations => {
                "SELECT COUNT(*) FROM device_locations \
                 WHERE device_id = ? AND received_at > datetime('now', '-1 day')"
            }
            Budget::DnsEvents => {
                "SELECT COUNT(*) FROM device_dns_events \
                 WHERE device_id = ? AND received_at > datetime('now', '-1 day')"
            }
            Budget::PinEvents => {
                "SELECT COUNT(*) FROM device_pin_events \
                 WHERE device_id = ? AND received_at > datetime('now', '-1 day')"
            }
        }
    }

    /// How many rows `device_id` may still add today; past the budget, logged. A count that can't
    /// be read is logged and drops nothing (QA nit: a passing database error never costs a
    /// phone its fixes).
    pub async fn left_today(self, conn: &mut sqlx::SqliteConnection, device_id: i64) -> usize {
        let today: i64 = match sqlx::query_scalar(self.count_sql())
            .bind(device_id)
            .fetch_one(&mut *conn)
            .await
        {
            Ok(today) => today,
            Err(err) => {
                tracing::error!(device_id, table = ?self, %err, "can't count a phone's rows today");
                return usize::MAX;
            }
        };
        let left = (self.rows_a_day() - today).max(0);
        if left == 0 {
            tracing::warn!(device_id, table = ?self, "a phone's daily storage budget is spent");
        }
        usize::try_from(left).unwrap_or(0)
    }
}

/// Past [STATUS_ROWS_A_DAY] reports in a rolling day, a new report replaces the one before it
/// (`previous_id`, just read) instead of adding to the history - so the newest report is always
/// the latest and a phone's day holds at most the budget plus one. Never the newest report that
/// said it enforces calls (`prune`'s keeper). A failure is logged: the row stays.
pub async fn replace_over_budget(db: &SqlitePool, device_id: i64, previous_id: i64) {
    let result = sqlx::query(
        "DELETE FROM device_status WHERE id = ?1 AND device_id = ?2 \
           AND reported_at > datetime('now', '-1 day') \
           AND (SELECT COUNT(*) FROM device_status \
                WHERE device_id = ?2 AND reported_at > datetime('now', '-1 day')) > ?3 \
           AND id IS NOT (SELECT MAX(id) FROM device_status \
                          WHERE device_id = ?2 AND capabilities_json LIKE ?4)",
    )
    .bind(previous_id)
    .bind(device_id)
    .bind(STATUS_ROWS_A_DAY)
    .bind(format!(
        "%\"{}\"%",
        crate::handlers::calls::CALL_POLICY_CAPABILITY
    ))
    .execute(db)
    .await;
    if let Err(err) = result {
        tracing::error!(device_id, %err, "can't keep a phone's status history within its budget");
    }
}

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
    let dns = rows(
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
    let security = rows(
        failed,
        "security_events",
        sqlx::query(
            "DELETE FROM security_events WHERE created_at < datetime('now', '-' || ? || ' days')",
        )
        .bind(SECURITY_EVENT_DAYS)
        .execute(db)
        .await,
    );
    let pins = rows(failed, "device_pin_events", sqlx::query(
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
    let inbox = zone_rows
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
    // (No local is named `*_events`: `admin::every_written_event_has_a_label` reads the table names
    // next to such a `let` as event ids.)
    Pruned {
        dns_events: dns,
        locations,
        status_reports,
        crashes,
        security_events: security,
        pin_events: pins,
        parent_events: inbox,
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
