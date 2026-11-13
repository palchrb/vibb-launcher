//! A phone's Activity (design 48 §1.2, §1.7): one history of the phone, newest first - every
//! command asked of it (the row Locate's Requests draws, with Cancel while it waits) and its
//! events (the inbox's rows, without the avatar), from the last [WINDOW_DAYS] days, at most
//! [MAX_ROWS]. A command that can still be cancelled is shown at any age, outside the window and
//! the cap. Each row carries the clock (`Zone::at`). The window is a filter; retention is
//! `retention::prune`'s.
//!
//! The phone's lifts (`time_lifts`) are rows too since 2026-11-02 (Device › Time Rules' Recent went
//! on 2026-11-01): every rule pause that is over and every extra screen time, at when it was
//! granted, read-only. A pause still running isn't one - Quick actions has it with End now - so
//! each lift shows once.
//!
//! Since 2026-11-13 (the user's pick B, "Én rad, minimal") the list is a page of its own,
//! `GET /devices/{id}/activity` ([view], `device_activity.html`), and the summary keeps one row
//! of it ([Summary]): the newest row's words and clock and how many rows the page has - plus each
//! command that can still be cancelled, with its Cancel back to the summary's `#activity`, so
//! Lock again, Unlock for 2 h and Allow these apps stay one tap from the summary.

use askama::Template;
use axum::Extension;
use axum::extract::{Path, Query, State};
use axum::http::StatusCode;
use axum::response::{Html, IntoResponse, Response};
use chrono::{DateTime, Utc};

use crate::AppState;
use crate::display_time::{Zone, duration, parse_db_utc};
use crate::handlers::locate::{CommandRow, cancel_flash, command_row};
use crate::handlers::notifications::{InboxRow, ROW_COLUMNS, Row, inbox_row, row_from};
use crate::i18n::{Lang, Words, t, t_count, t_params};
use crate::models::{Device, DeviceCommand};
use crate::security::CurrentAdmin;
use crate::time_rules::TimeLiftRow;

/// How far back Activity reads.
const WINDOW_DAYS: i64 = 7;
/// How many rows Activity's page shows, besides the commands that can still be cancelled (ten
/// on the summary until 2026-11-13; the page has the room).
pub(crate) const MAX_ROWS: usize = 50;

/// One row of Activity.
pub(crate) enum ActivityRow {
    Command(CommandRow),
    Event(InboxRow),
    Lift(LiftRow),
}

/// A lift in Activity, laid out as a command's row without Cancel: "Skole paused for 30 min",
/// "14:00 · Mamma · Ended 14:30".
pub(crate) struct LiftRow {
    pub(crate) id: i64,
    pub(crate) what: String,
    /// When it was granted (`Zone::at`), the exact time for `title=`.
    pub(crate) when: String,
    pub(crate) exact: String,
    /// Who granted it, only in a family with more than one account (as a command's).
    pub(crate) by: Option<String>,
    /// "Ended 14:30", "Ended early 14:20", "Added", and its tone class.
    pub(crate) word: String,
    pub(crate) tone: &'static str,
}

/// Activity: its page's `#activity` group, and what the summary's row is made of ([Summary]).
pub(crate) struct Activity {
    pub(crate) rows: Vec<ActivityRow>,
    /// How many of [rows](Activity::rows) are of the window: all but the commands still cancellable
    /// that were asked before it.
    pub(crate) in_window: usize,
    /// More rows in the window than [MAX_ROWS] (the badge says "50+").
    pub(crate) more: bool,
    /// After a Cancel (`?cancel=`): the line in the group, and whether it was refused.
    pub(crate) flash: Option<(&'static str, bool)>,
}

/// The newest row's words on the summary: what (a command's label and how it went, "Lock again ·
/// Cancelled"; a lift's words; an event's title), when (`Zone::at`) and the exact time for `title=`.
pub(crate) struct Newest {
    pub(crate) what: String,
    pub(crate) when: String,
    pub(crate) exact: String,
}

/// The summary's `#activity` (the user's pick B, 2026-11-13): each command that can still be
/// cancelled as its own row with Cancel, then one row to the page - the newest other row's words
/// and clock under "Activity", how many of the week's rows the page lists in a badge (an older
/// command shown above with Cancel isn't one of them). The section shows while it has a row or a
/// Cancel's line to say; the row to the page while the week has a row.
pub(crate) struct Summary {
    pub(crate) waiting: Vec<CommandRow>,
    pub(crate) newest: Option<Newest>,
    /// How many of the week's rows the page lists (0: no row to it).
    pub(crate) count: usize,
    /// The badge, also its `data-activity-count`: "12", or "50+" past the page's cap.
    pub(crate) badge: String,
    /// The badge as spoken: "12 in the last 7 days".
    pub(crate) spoken: String,
    pub(crate) flash: Option<(&'static str, bool)>,
}

impl Summary {
    pub(crate) fn of(activity: Activity, lang: Lang) -> Summary {
        let count = activity.in_window;
        let (badge, spoken) = if activity.more {
            (
                format!("{MAX_ROWS}+"),
                t_params(
                    lang,
                    "device.activity.count_more",
                    &[("count", &MAX_ROWS.to_string())],
                ),
            )
        } else {
            (
                count.to_string(),
                t_count(lang, "device.activity.count", count as i64, &[]),
            )
        };
        let mut waiting = Vec::new();
        let mut newest = None;
        for row in activity.rows {
            match row {
                ActivityRow::Command(c) if c.cancel_id.is_some() => waiting.push(c),
                row if newest.is_none() => newest = Some(row.newest()),
                _ => {}
            }
        }
        Summary {
            waiting,
            newest,
            count,
            badge,
            spoken,
            flash: activity.flash,
        }
    }

    pub(crate) fn shown(&self) -> bool {
        self.count > 0 || !self.waiting.is_empty() || self.flash.is_some()
    }
}

impl ActivityRow {
    fn newest(self) -> Newest {
        match self {
            ActivityRow::Command(c) => Newest {
                what: format!("{} · {}", c.label, c.word),
                when: c.asked,
                exact: c.asked_exact,
            },
            ActivityRow::Lift(l) => Newest {
                what: l.what,
                when: l.when,
                exact: l.exact,
            },
            ActivityRow::Event(e) => Newest {
                what: e.title,
                when: e.when,
                exact: e.exact,
            },
        }
    }
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
    .bind(MAX_ROWS as i64 + 1)
    .bind(admin_id)
    .fetch_all(&state.db)
    .await?;
    // Every lift but a rule pause the server still sends (Quick actions' End now rows): the same
    // `datetime('now')` test as `lifts::today`, so a pause at its expiry is in one of the two.
    let lifts: Vec<TimeLiftRow> = sqlx::query_as(
        "SELECT * FROM time_lifts WHERE device_id = ?1 AND created_at >= ?2 \
           AND NOT (target = 'rule' AND ended_early_at IS NULL AND expires_at > datetime('now')) \
         ORDER BY created_at DESC, id DESC LIMIT ?3",
    )
    .bind(device_id)
    .bind(since.format("%Y-%m-%d %H:%M:%S").to_string())
    .bind(MAX_ROWS as i64 + 1)
    .fetch_all(&state.db)
    .await?;
    // Design 93 §8: every rule turned on now that is no longer running (the running one is Quick actions').
    let locks: Vec<crate::lock_now::LockNowRow> = sqlx::query_as(
        "SELECT * FROM time_lock_now WHERE device_id = ?1 AND created_at >= ?2 \
           AND NOT (ended_early_at IS NULL AND rule_id IS NOT NULL AND ends_at > datetime('now')) \
         ORDER BY created_at DESC, id DESC LIMIT ?3",
    )
    .bind(device_id)
    .bind(since.format("%Y-%m-%d %H:%M:%S").to_string())
    .bind(MAX_ROWS as i64 + 1)
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
    for lift in &lifts {
        let at = parse_db_utc(&lift.created_at);
        all.push((
            at,
            false,
            ActivityRow::Lift(lift_row(lift, &names, zone, now)),
        ));
    }
    for lock in &locks {
        let at = parse_db_utc(&lock.created_at);
        all.push((
            at,
            false,
            ActivityRow::Lift(lock_row(lock, &names, zone, now)),
        ));
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
    // Every row but a cancellable command is of the window (each read starts at `since`).
    let in_window = all
        .iter()
        .filter(|(at, cancellable, _)| !*cancellable || at.is_some_and(|t| t >= since))
        .count();
    Ok(Activity {
        rows: all.into_iter().map(|(_, _, row)| row).collect(),
        in_window,
        // Each table reads one row past the cap, so one more anywhere shows here.
        more: capped > MAX_ROWS,
        flash: cancel_flash(zone.lang(), cancel),
    })
}

#[derive(Template)]
#[template(path = "device_activity.html")]
struct ActivityTemplate {
    lang: Lang,
    /// The phone's small picture on the band (design 84, `band::phone`).
    who: crate::handlers::devices::BandAvatar,
    /// The tab's name: "Activity · Emma".
    title: String,
    device: Device,
    activity: Activity,
}
crate::i18n::words!(ActivityTemplate);

/// GET `/devices/{id}/activity` (2026-11-13): the phone's whole Activity, as the summary's group
/// drew it until then - the commands with Cancel while they wait (back here, `back=activity`), the
/// lifts, the events - then "Everything for this phone ›" to its inbox. 404 for an unknown phone,
/// 500 when a read fails (never a shorter history that reads as true).
pub async fn view(
    State(state): State<AppState>,
    Extension(CurrentAdmin(admin)): Extension<CurrentAdmin>,
    lang: Lang,
    Path(id): Path<i64>,
    Query(query): Query<std::collections::HashMap<String, String>>,
) -> Response {
    let device = match crate::handlers::device_pages::load_device(&state, id).await {
        Ok(device) => device,
        Err((status, key)) => return crate::i18n::error(lang, status, key),
    };
    let zone = Zone::load(&state.db, lang).await;
    let cancel = query.get("cancel").map(String::as_str);
    let activity = match rows(&state, admin.id, id, cancel, &zone, Utc::now()).await {
        Ok(activity) => activity,
        Err(err) => {
            tracing::error!(device_id = id, %err, "can't read the phone's activity");
            return crate::i18n::error(lang, StatusCode::INTERNAL_SERVER_ERROR, "error.read_phone");
        }
    };
    Html(
        ActivityTemplate {
            lang,
            who: crate::handlers::devices::BandAvatar::of(&device),
            title: format!("{} · {}", t(lang, "device.summary.activity"), device.name),
            device,
            activity,
        }
        .render()
        .unwrap(),
    )
    .into_response()
}

/// A rule turned on now in Activity (design 93 §8), as a lift's row: "Skole slått på 16:30-18:30",
/// "16:30 · Mamma · Sluttet 18:30" / "Avsluttet 17:10" / "Regelen ble fjernet" / "Regelen ble slått av". Its id is the copy's,
/// negative, so it never meets a lift's.
fn lock_row(
    lock: &crate::lock_now::LockNowRow,
    names: &crate::parents::Names,
    zone: &Zone,
    now: DateTime<Utc>,
) -> LiftRow {
    let lang = zone.lang();
    let what = t_params(
        lang,
        "device.activity.lock_now",
        &[
            (
                "rule",
                &crate::time_rules::shown_name(lang, &lock.rule_name),
            ),
            ("from", &crate::lock_now::end_words(lang, lock.start_minute)),
            ("to", &crate::lock_now::end_words(lang, lock.end_minute)),
        ],
    );
    let early = lock.ended_early_at.as_deref().and_then(parse_db_utc);
    let word = match (early, lock.rule_id) {
        // Ended with no parent named: the rule was switched off for the phone (migration 0142's trigger).
        (Some(_), _) if lock.ended_by.is_none() => {
            t(lang, "device.activity.lock_now.switched_off").to_string()
        }
        (Some(at), _) => t_params(
            lang,
            "device.activity.lock_now.ended_early",
            &[("at", &zone.at(at, now))],
        ),
        (None, None) => t(lang, "device.activity.lock_now.removed").to_string(),
        (None, Some(_)) => t_params(
            lang,
            "device.activity.lock_now.over",
            &[("at", &crate::lock_now::end_words(lang, lock.end_minute))],
        ),
    };
    let made = parse_db_utc(&lock.created_at);
    LiftRow {
        id: -lock.id,
        what,
        when: made.map(|t| zone.at(t, now)).unwrap_or_default(),
        exact: made.map(|t| zone.exact(t)).unwrap_or_default(),
        by: names.granter(lock.created_by.as_deref()),
        word,
        tone: "grey",
    }
}

/// A lift's row in the reader's language: what it was, when and by whom it was granted, how it went.
fn lift_row(
    lift: &TimeLiftRow,
    names: &crate::parents::Names,
    zone: &Zone,
    now: DateTime<Utc>,
) -> LiftRow {
    let lang = zone.lang();
    let length = duration(lang, lift.minutes);
    let what = match (lift.target.as_str(), lift.rule_name.as_deref()) {
        ("rule", Some(rule)) => t_params(
            lang,
            "device.summary.lift.rule",
            &[
                ("rule", &crate::time_rules::shown_name(lang, rule)),
                ("duration", &length),
            ],
        ),
        ("rule", None) => t_params(
            lang,
            "device.summary.lift.all_rules",
            &[("duration", &length)],
        ),
        _ => t_params(lang, "device.summary.lift.extra", &[("duration", &length)]),
    };
    let (word, tone) = if lift.target == "rule" {
        let early = lift.ended_early_at.as_deref().and_then(parse_db_utc);
        let word = match (early, parse_db_utc(&lift.expires_at)) {
            (Some(at), _) => t_params(
                lang,
                "device.summary.lift.ended_early",
                &[("at", &zone.at(at, now))],
            ),
            (None, Some(end)) => t_params(
                lang,
                "device.summary.lift.ended",
                &[("at", &zone.at(end, now))],
            ),
            (None, None) => t(lang, "device.summary.lift.over").to_string(),
        };
        (word, "grey")
    } else {
        (t(lang, "device.summary.lift.added").to_string(), "green")
    };
    let made = parse_db_utc(&lift.created_at);
    LiftRow {
        id: lift.id,
        what,
        when: made.map(|t| zone.at(t, now)).unwrap_or_default(),
        exact: made.map(|t| zone.exact(t)).unwrap_or_default(),
        by: names.granter(lift.created_by.as_deref()),
        word,
        tone,
    }
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
    // Case-insensitively (design r13 R13-13): an event told the day after says "Yesterday 21:40 · …"
    // while `when` is "yesterday 21:40".
    for own in [clock, when] {
        if let Some(rest) =
            strip_prefix_ignore_case(summary, own).and_then(|s| s.strip_prefix(" · "))
        {
            return rest.to_string();
        }
        if let Some(rest) =
            strip_suffix_ignore_case(summary, own).and_then(|s| s.strip_suffix(" · "))
        {
            return rest.to_string();
        }
    }
    summary.to_string()
}

/// `text` without a leading `prefix`, ASCII case ignored.
fn strip_prefix_ignore_case<'a>(text: &'a str, prefix: &str) -> Option<&'a str> {
    let head = text.get(..prefix.len())?;
    head.eq_ignore_ascii_case(prefix)
        .then(|| &text[prefix.len()..])
}

/// `text` without a trailing `suffix`, ASCII case ignored.
fn strip_suffix_ignore_case<'a>(text: &'a str, suffix: &str) -> Option<&'a str> {
    let start = text.len().checked_sub(suffix.len())?;
    let tail = text.get(start..)?;
    tail.eq_ignore_ascii_case(suffix).then(|| &text[..start])
}

#[cfg(test)]
mod tests {
    use super::without_own_clock;

    #[test]
    fn the_own_clock_goes_whatever_its_case() {
        // Design r13 R13-13: a request told the day after.
        assert_eq!(
            without_own_clock("Yesterday 21:40 · Bedtime", "yesterday 21:40"),
            "Bedtime"
        );
        assert_eq!(
            without_own_clock("Bedtime · YESTERDAY 21:40", "yesterday 21:40"),
            "Bedtime"
        );
        assert_eq!(without_own_clock("Ø · 21:40", "21:40"), "Ø");
    }

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
