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
use crate::handlers::notifications::{InboxRow, ROW_COLUMNS, Row, inbox_row, row_from};
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

/// Activity for `device_id` at `now` as `admin` reads it (unread is theirs; design 62 §5), with
/// the line after a Cancel (`cancel`, the page's `?cancel=`). Sorted by the row's own time: when a
/// command was asked, when an event happened. A command says who asked for it when the family has
/// more than one parent (§6).
pub(crate) async fn rows(
    state: &AppState,
    admin_id: i64,
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
        "SELECT {ROW_COLUMNS} FROM {} \
         WHERE e.device_id = ?1 AND e.happened_at >= ?2 \
         ORDER BY e.happened_at DESC, e.id DESC LIMIT ?3",
        row_from(4)
    ))
    .bind(device_id)
    .bind(since.format("%Y-%m-%dT%H:%M:%S%.3fZ").to_string())
    .bind(MAX_ROWS as i64)
    .bind(admin_id)
    .fetch_all(&state.db)
    .await?;
    let receipts =
        crate::handlers::device_api::sends_command_receipts(&state.db, device_id).await?;
    let names = crate::parents::Names::load(&state.db).await?;

    // (when, outside the cap, the row)
    let mut all: Vec<(Option<DateTime<Utc>>, bool, ActivityRow)> = Vec::new();
    for command in &commands {
        let at = parse_db_utc(&command.requested_at);
        let cancellable = command.cancellable(now.naive_utc());
        if cancellable || at.is_some_and(|t| t >= since) {
            let row = command_row(command, &names, zone, now, receipts);
            all.push((at, cancellable, ActivityRow::Command(row)));
        }
    }
    for event in events {
        let at = parse_db_utc(&event.5);
        let mut row = inbox_row(event, zone, now, |t| zone.at(t, now));
        // One clock per row: the row shows `when` after the summary.
        row.summary = without_own_clock(&row.summary, &row.when);
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

/// The clock at the end of a row's `when` ("14:09", "yesterday 14:09", "15 Oct 14:02", with
/// [Zone]'s " UTC" when it has no zone): from its last "HH:MM" to the end. `None` without one.
fn clock_of(when: &str) -> Option<&str> {
    let bytes = when.as_bytes();
    (0..bytes.len().saturating_sub(4)).rev().find_map(|i| {
        let b = &bytes[i..i + 5];
        let digits = [0, 1, 3, 4].iter().all(|&k| b[k].is_ascii_digit());
        let starts = i == 0 || bytes[i - 1] == b' ';
        (digits && b[2] == b':' && starts).then(|| &when[i..])
    })
}

/// An event's summary without the clock it carries itself, when the row shows that clock anyway
/// (`· {{ e.when }}`): design 38's "14:09 · no shutdown seen …" and the Parent PIN's "Vibb One's
/// Settings · 09:20" lose their leading "HH:MM · " or trailing " · HH:MM" when it is `when`'s own
/// clock (or all of `when`). Anything else is left as it is.
pub(crate) fn without_own_clock(summary: &str, when: &str) -> String {
    let Some(clock) = clock_of(when) else {
        return summary.to_string();
    };
    // A summary that is only the clock (design 64's "15:42", "Yesterday 21:14") says nothing more.
    if summary.eq_ignore_ascii_case(clock) || summary.eq_ignore_ascii_case(when) {
        return String::new();
    }
    for own in [clock, when] {
        if let Some(rest) = summary
            .strip_prefix(own)
            .and_then(|s| s.strip_prefix(" · "))
        {
            return rest.to_string();
        }
        if let Some(rest) = summary
            .strip_suffix(own)
            .and_then(|s| s.strip_suffix(" · "))
        {
            return rest.to_string();
        }
    }
    summary.to_string()
}

#[cfg(test)]
mod tests {
    use super::without_own_clock;

    #[test]
    fn a_leading_own_clock_goes() {
        assert_eq!(
            without_own_clock("14:09 · no shutdown seen · last report 13:50", "14:09"),
            "no shutdown seen · last report 13:50"
        );
        assert_eq!(
            without_own_clock("14:09 · off since 13:00", "yesterday 14:09"),
            "off since 13:00"
        );
    }

    #[test]
    fn a_trailing_own_clock_goes() {
        assert_eq!(
            without_own_clock("Vibb One's Settings · 09:20", "09:20"),
            "Vibb One's Settings"
        );
        assert_eq!(
            without_own_clock("Lock screen · 09:20", "15 Oct 09:20"),
            "Lock screen"
        );
        assert_eq!(
            without_own_clock("Lock screen · yesterday 09:20", "yesterday 09:20"),
            "Lock screen"
        );
        assert_eq!(without_own_clock("Pause · 09:20 UTC", "09:20 UTC"), "Pause");
    }

    #[test]
    fn another_clock_stays() {
        let summary = "14:09 · no shutdown seen · last report 13:50";
        assert_eq!(without_own_clock(summary, "14:10"), summary);
        assert_eq!(
            without_own_clock("Vibb One's Settings · 09:20", "09:21"),
            "Vibb One's Settings · 09:20"
        );
        // A clock in the middle is the summary's own (the last report), never the row's.
        assert_eq!(
            without_own_clock("off · last report 13:50 · battery 10 %", "13:50"),
            "off · last report 13:50 · battery 10 %"
        );
    }

    #[test]
    fn a_summary_without_a_clock_stays() {
        assert_eq!(
            without_own_clock("Battery at 15 %", "14:09"),
            "Battery at 15 %"
        );
        assert_eq!(without_own_clock("", "14:09"), "");
        // Only the clock (a zone's "15:42", design 64): nothing more to say, the row shows it.
        assert_eq!(without_own_clock("14:09", "14:09"), "");
        assert_eq!(without_own_clock("Yesterday 14:09", "yesterday 14:09"), "");
        assert_eq!(
            without_own_clock("Lock screen · 09:20", ""),
            "Lock screen · 09:20"
        );
    }
}
