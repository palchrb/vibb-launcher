//! The phone summary's Activity (design 48 §1.2, §1.7): one history of the phone, newest first -
//! every command asked of it (the row Locate's Requests draws, with Cancel while it waits) and
//! its events (the inbox's rows, without the avatar), from the last [WINDOW_DAYS] days, at most
//! [MAX_ROWS]. A command that can still be cancelled is shown at any age, outside the window and
//! the cap: the summary is the only place to cancel Lock again, Unlock for 2 h or Allow these
//! apps. Each row carries the clock (`Zone::at`). The window is a filter; retention is
//! `retention::prune`'s.

use chrono::{DateTime, Utc};

use crate::AppState;
use crate::display_time::{Zone, parse_db_utc};
use crate::handlers::locate::{CommandRow, cancel_flash, command_row};
use crate::handlers::notifications::{InboxRow, ROW_COLUMNS, Row, inbox_row};
use crate::models::DeviceCommand;

/// How far back Activity reads.
const WINDOW_DAYS: i64 = 7;
/// How many rows Activity shows, besides the commands that can still be cancelled.
const MAX_ROWS: usize = 10;

/// One row of Activity.
pub(crate) enum ActivityRow {
    Command(CommandRow),
    Event(InboxRow),
}

/// The summary's `#activity` group.
pub(crate) struct Activity {
    pub(crate) rows: Vec<ActivityRow>,
    /// After a Cancel (`?cancel=`): the line in the group, and whether it was refused.
    pub(crate) flash: Option<(&'static str, bool)>,
}

/// Activity for `device_id` at `now`, with the line after a Cancel (`cancel`, the page's
/// `?cancel=`). Sorted by the row's own time: when a command was asked, when an event happened.
pub(crate) async fn rows(
    state: &AppState,
    device_id: i64,
    cancel: Option<&str>,
    zone: &Zone,
    now: DateTime<Utc>,
) -> Result<Activity, sqlx::Error> {
    let since = now - chrono::Duration::days(WINDOW_DAYS);
    // Stored as `datetime('now')` text: the window's start in the same form compares as the time.
    let commands: Vec<DeviceCommand> = sqlx::query_as(
        "SELECT * FROM device_commands WHERE device_id = ? \
           AND (requested_at >= ? OR (delivered_at IS NULL AND cancelled_at IS NULL)) \
         ORDER BY requested_at DESC, id DESC",
    )
    .bind(device_id)
    .bind(since.format("%Y-%m-%d %H:%M:%S").to_string())
    .fetch_all(&state.db)
    .await?;
    // `happened_at` is RFC 3339 in UTC with milliseconds (`parent_events::instant`).
    let events: Vec<Row> = sqlx::query_as(&format!(
        "SELECT {ROW_COLUMNS} FROM parent_events e JOIN devices d ON d.id = e.device_id \
         WHERE e.device_id = ? AND e.happened_at >= ? \
         ORDER BY e.happened_at DESC, e.id DESC LIMIT ?"
    ))
    .bind(device_id)
    .bind(since.format("%Y-%m-%dT%H:%M:%S%.3fZ").to_string())
    .bind(MAX_ROWS as i64)
    .fetch_all(&state.db)
    .await?;
    let receipts =
        crate::handlers::device_api::sends_command_receipts(&state.db, device_id).await?;

    // (when, outside the cap, the row)
    let mut all: Vec<(Option<DateTime<Utc>>, bool, ActivityRow)> = Vec::new();
    for command in &commands {
        let at = parse_db_utc(&command.requested_at);
        let cancellable = command.cancellable(now.naive_utc());
        if cancellable || at.is_some_and(|t| t >= since) {
            let row = command_row(command, zone, now, receipts);
            all.push((at, cancellable, ActivityRow::Command(row)));
        }
    }
    for event in events {
        let at = parse_db_utc(&event.5);
        let row = inbox_row(event, zone, |t| zone.at(t, now));
        all.push((at, false, ActivityRow::Event(row)));
    }
    // Newest first; a time that doesn't read goes last. Stable, so each table keeps its order.
    all.sort_by_key(|(at, _, _)| std::cmp::Reverse(*at));
    let mut capped = 0;
    all.retain(|(_, cancellable, _)| {
        *cancellable || {
            capped += 1;
            capped <= MAX_ROWS
        }
    });
    Ok(Activity {
        rows: all.into_iter().map(|(_, _, row)| row).collect(),
        flash: cancel_flash(cancel),
    })
}
