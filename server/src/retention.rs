//! What the server keeps about a phone, and for how long (cleanup round 2026-10-06). Principle:
//! collect only what's needed to manage the phone, delete on a schedule, never store
//! notification or message content.

use std::path::Path;
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
/// How often [run_pruning] runs (and once at startup).
const PRUNE_INTERVAL: Duration = Duration::from_secs(60 * 60);

/// Rows [prune] deleted, per table.
#[derive(Debug, Default, PartialEq, Eq)]
pub struct Pruned {
    pub dns_events: u64,
    pub locations: u64,
    pub status_reports: u64,
}

/// One pruning pass:
/// - blocked domains older than [DNS_LOG_RETENTION_DAYS], and every entry of a phone whose log
///   is off (an older launcher may still send them; the server drops those at the door too);
/// - locations older than the phone's `location_retention_days` (by the server's receive time),
///   except the newest fix per phone, which Find My Device shows as "last seen";
/// - status reports older than [STATUS_HISTORY_DAYS], except the newest per phone.
pub async fn prune(db: &SqlitePool) -> Result<Pruned, sqlx::Error> {
    let dns_events = sqlx::query(
        "DELETE FROM device_dns_events \
         WHERE received_at < datetime('now', '-' || ? || ' days') \
            OR device_id NOT IN (SELECT device_id FROM device_policy WHERE dns_log_enabled = 1)",
    )
    .bind(DNS_LOG_RETENTION_DAYS)
    .execute(db)
    .await?
    .rows_affected();
    let locations = sqlx::query(
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
    .await?
    .rows_affected();
    let status_reports = sqlx::query(
        "DELETE FROM device_status \
         WHERE reported_at < datetime('now', '-' || ? || ' days') \
           AND id NOT IN (SELECT MAX(id) FROM device_status GROUP BY device_id)",
    )
    .bind(STATUS_HISTORY_DAYS)
    .execute(db)
    .await?
    .rows_affected();
    Ok(Pruned {
        dns_events,
        locations,
        status_reports,
    })
}

/// Background task (spawned from `main` only): [prune] at startup and every hour.
pub async fn run_pruning(state: AppState) {
    let mut interval = tokio::time::interval(PRUNE_INTERVAL);
    loop {
        interval.tick().await;
        match prune(&state.db).await {
            Ok(pruned) if pruned != Pruned::default() => tracing::info!(?pruned, "Pruned old data"),
            Ok(_) => {}
            Err(err) => tracing::error!(%err, "Pruning old data failed"),
        }
    }
}

/// Where the removed conversation journal kept its media (photos, video, voice notes).
pub const JOURNAL_MEDIA_DIR: &str = "data/journal_media";

/// Deletes the removed conversation journal's media directory, if it is still there (the
/// tables went with migration 0038). Returns whether something was deleted; a failure is logged
/// and retried at the next start.
pub async fn remove_journal_media(dir: &Path) -> bool {
    match tokio::fs::remove_dir_all(dir).await {
        Ok(()) => {
            tracing::info!(
                "Deleted the removed conversation journal's media ({})",
                dir.display()
            );
            true
        }
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => false,
        Err(e) => {
            tracing::error!(%e, "Couldn't delete the old journal media in {}", dir.display());
            false
        }
    }
}
