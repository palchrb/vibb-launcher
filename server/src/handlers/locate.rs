//! The Locate tab (`/devices/locate`, `templates/device_locate.html`; design 31 §3.6, step 13):
//! the phones' chips (design 85), the map (Leaflet against public OSM tiles, design 33), the last
//! fix as age and accuracy, Update Location, then one group of rows (design 88), each to a page of
//! the phone's: Locations, Zones ([show_zones]), Find Phone ([show_find]: Ring, Stop Ringing,
//! Lock, the requests in flight, Erase Phone…) and Location Settings ([show_location_settings]). Commands are queued into `device_commands`, the phone is nudged over its
//! push channel and fetches them with its policy (see `handlers::device_api::policy`) - within
//! seconds while it's online, otherwise when it's back. The location policy (off / on request /
//! every N minutes, handy step 6) decides when the phone takes a fix on its own.
//!
//! Design 44: the trail shows a span ([SPANS], picked in the browser), and every kept fix is a row
//! on `/devices/{id}/locations` ([show_locations]). The map's script is `static/locate.js`. Design
//! 48: Requests ([requests]) lists this page's commands ([LOCATE_KINDS]) while they are in flight
//! or just went wrong; every command of the phone is in its summary's Activity
//! (`handlers::activity`).

use askama::Template;
use axum::extract::{Path, Query, State};
use axum::http::StatusCode;
use axum::response::{Html, IntoResponse, Json, Redirect, Response};
use axum::{Extension, Form};
use chrono::{DateTime, Utc};
use std::collections::HashMap;

use crate::AppState;
use crate::display_time::{Zone, parse_db_utc};
use crate::i18n::{Lang, Words, t, t_count, t_params};
use crate::models::{
    AdminUser, CancelledBy, CommandStatus, Device, DeviceCommand, DeviceLocation, Unconfirmed,
    command_label,
};
use crate::security::{self, CurrentAdmin};

/// The commands the Locate page sends, the only ones its Requests lists (design 44 §1.1).
pub(crate) const LOCATE_KINDS: &[&str] = &["locate", "ring", "stop_ring", "lock", "wipe"];

/// How long a request that went wrong stays in Requests (design 48 §1.4).
const REQUESTS_HOURS: i64 = 24;

/// The trail's spans in hours (0 = everything kept) and their words' keys, the first the default
/// (design 44 §1.5). The browser remembers the pick (`static/locate.js`).
pub(crate) const SPANS: [(i64, &str); 4] = [
    (12, "locate.span.12"),
    (24, "locate.span.24"),
    (168, "locate.span.168"),
    (0, "locate.span.0"),
];

/// How many fixes the Locations page lists at a time.
const FIXES_PER_PAGE: usize = 200;

#[derive(Template)]
#[template(path = "device_locate.html")]
struct LocateTemplate {
    lang: Lang,
    title: String,
    /// No phone yet: the empty state and Add Phone.
    no_phones: bool,
    selected: Option<Locate>,
    /// Two phones or more, none chosen: the Alle view (design 85).
    all: Option<AllView>,
    bell: crate::parent_events::Bell,
}
crate::i18n::words!(LocateTemplate);

/// The chosen phone's part of the page.
struct Locate {
    device: Device,
    /// Design 85 revision 3: every phone's chip over the map ("All" first, this one current), as
    /// the Alle view's; empty with one phone - no chips at all.
    chips: Vec<AllPhone>,
    /// The map's marker for the newest fix: this phone's Alle row as `data-marker-*`.
    marker: Marker,
    fix: Option<FixView>,
    /// The map's `data-` attributes for `static/locate.js` (Askama escapes them; the script reads
    /// them as text - security review H2).
    map: MapData,
    /// The span shown: the Locations row's value and the pick's checked link.
    span: i64,
    /// "14 in the last 12 hours", "None yet".
    locations_value: String,
    /// The phone's answer to the newest Update Location.
    answer: Option<AnswerView>,
    /// Just pressed Update Location: the page polls for a fresh fix.
    waiting_for_fix: bool,
    /// The location policy is off: the phone switches location off and never takes a fix.
    location_off: bool,
    /// Design 64 step 2 (decision 14): "3 made-up locations ignored" - the phone's running count
    /// of mock fixes a release build ignored; `None` at 0 or without a watch.
    mock_ignored: Option<String>,
    /// The phone has zones: the map draws them (`data-zones`).
    has_zones: bool,
    /// Design 88: the rows' values - Zones ("3 · Here now: School") and Location Settings
    /// ("Every 15 min").
    zones_value: String,
    settings_value: String,
}

/// The Zones page (design 88; design 64 §3.5): the phone's zones, each to its page.
#[derive(Template)]
#[template(path = "device_zones.html")]
struct ZonesTemplate {
    lang: Lang,
    title: String,
    device: Device,
    who: crate::handlers::devices::BandAvatar,
    zones: ZonesView,
}
crate::i18n::words!(ZonesTemplate);

/// The Find Phone page (design 88): Ring, Stop Ringing, Lock, the newest requests and Erase Phone….
#[derive(Template)]
#[template(path = "device_find.html")]
struct FindTemplate {
    lang: Lang,
    title: String,
    device: Device,
    who: crate::handlers::devices::BandAvatar,
    /// The newest [FIND_REQUESTS] of [requests]; the first is highlighted.
    recent: Recent,
    /// The phone's last report said it can't erase itself yet (QA of the launcher wave, Low 4).
    wipe_not_ready: bool,
    /// Design 59 §2.3: Android's lock is chosen - after a restart the actions wait for the PIN.
    android_lock: bool,
    /// The name typed into Erase Phone… didn't match: the confirm is open with the refusal.
    erase_refused: bool,
}
crate::i18n::words!(FindTemplate);

/// How many requests the Find Phone page lists; Show all leads to the summary's Activity.
const FIND_REQUESTS: usize = 3;

/// The Location Settings page (design 88): When to locate, Every, Keep history for.
#[derive(Template)]
#[template(path = "device_location_settings.html")]
struct LocationSettingsTemplate {
    lang: Lang,
    title: String,
    device: Device,
    who: crate::handlers::devices::BandAvatar,
    location_mode: String,
    location_interval: i64,
    location_retention: i64,
    modes: [(&'static str, &'static str); 3],
    intervals: Vec<(i64, String)>,
    retention_choices: Vec<(i64, String)>,
    /// Design 64 step 2 (the user's decision 3): under "Every", while the phone has zones and
    /// watches them - "30 min while the phone has zones" (the served interval, never written).
    interval_note: Option<String>,
}
crate::i18n::words!(LocationSettingsTemplate);

/// The Zones page's `#zones` (design 64 §3.5, §3.6; design 88): a row per zone, Add Zone, the line
/// after a save or a delete, what the location settings mean for the zones.
struct ZonesView {
    rows: Vec<ZoneRow>,
    /// 10 zones: Add Zone is replaced by a note, "10 zones is the most".
    full: Option<String>,
    /// After a delete (`?zone=deleted`): the group's line.
    flash: Option<&'static str>,
    /// Only when asked, with zones: the orange row and Locate Regularly.
    needs_regularly: bool,
    /// "Keep location on" is off, with zones: the kid can turn Location off.
    location_unlocked: bool,
    /// The footer under the group (in the reader's language).
    footer: &'static str,
    /// Regularly: "within about two intervals (here 60 min)" - not for a phone that watches the
    /// zones itself (design 64 step 2).
    confirm_within: Option<String>,
    /// Design 64 step 2: what the phone's zone watch says ([watch_line]), with zones and Regularly.
    watch_line: Option<String>,
}

/// A zone's row: "Home" over "150 m · arrive and leave", its value ([Presence]), and after its
/// save the line under it.
struct ZoneRow {
    id: i64,
    name: String,
    sub: String,
    presence: Presence,
    flash: Option<&'static str>,
}

/// What a zone's row says of the phone (code QA Q-1, Q-2).
enum Presence {
    /// Nothing: not in it, a crossing pending, or nothing known.
    None,
    /// "Here now": inside, no other side pending, the newest fix fresh.
    Here,
    /// "Last seen at Home 14:02": inside and nothing pending, but the newest fix (its time) is
    /// stale.
    LastSeen(String),
}

impl Presence {
    fn here(&self) -> bool {
        matches!(self, Presence::Here)
    }

    fn last_seen(&self) -> Option<&str> {
        match self {
            Presence::LastSeen(when) => Some(when),
            _ => None,
        }
    }
}

/// The zones the fix itself is surely inside (`zones::side`), "At Home · School": the fix row's
/// and the popup's words are that fix's own, never the zones' confirmed sides (code QA Q-1).
fn fix_badge(lang: Lang, zones: &[crate::zones::Zone], fix: &DeviceLocation) -> Option<String> {
    let inside: Vec<&str> = zones
        .iter()
        .filter(|z| {
            crate::zones::side(
                &z.circle(),
                fix.latitude,
                fix.longitude,
                fix.accuracy_meters,
            ) == Some(crate::zones::Side::Inside)
        })
        .map(|z| z.name.as_str())
        .collect();
    let key = match inside.len() {
        0 => return None,
        1 => "locate.fix.at_zone",
        _ => "locate.fix.at_zones",
    };
    Some(t_params(lang, key, &[("zones", &inside.join(" · "))]))
}

/// What a zone's row says it notifies about.
fn notify_words(lang: Lang, arrive: bool, leave: bool) -> &'static str {
    t(
        lang,
        match (arrive, leave) {
            (true, true) => "locate.zones.notify.both",
            (true, false) => "locate.zones.notify.arrive",
            (false, true) => "locate.zones.notify.leave",
            (false, false) => "locate.zones.notify.none",
        },
    )
}

/// The zones' part of the page for `zones` with the location setting (`mode`, `interval` - the
/// served one, design 64 step 2) and "Keep location on" (`locked`); `flash` is `?zone=`,
/// `flash_zone` `?zone_id=`; `newest` the newest fix's time and `seen` the later of it and its
/// last still re-send (Q-3); `watch` the phone's latest `zone_watch` (step 2).
#[allow(clippy::too_many_arguments)]
fn zones_view(
    zones: &[crate::zones::Zone],
    mode: &str,
    interval: i64,
    locked: bool,
    flash: Option<&str>,
    flash_zone: Option<i64>,
    newest: Option<DateTime<Utc>>,
    seen: Option<DateTime<Utc>>,
    watch: Option<&crate::zones::ZoneWatch>,
    zone: &Zone,
    now: DateTime<Utc>,
) -> ZonesView {
    let lang = zone.lang();
    // "Here now" counts from the newest fix's last still re-send too (Q-3): `seen`.
    let fresh = crate::zones::fresh(seen, mode, interval, now);
    let row_flash = match flash {
        Some("added") => Some(t(lang, "locate.zones.added")),
        Some("saved") => Some(t(lang, "common.saved")),
        _ => None,
    };
    let rows = zones
        .iter()
        .map(|z| ZoneRow {
            id: z.id,
            name: z.name.clone(),
            sub: t_params(
                lang,
                "locate.zones.sub",
                &[
                    ("radius", &z.radius_m.to_string()),
                    (
                        "notify",
                        notify_words(lang, z.notify_arrive, z.notify_leave),
                    ),
                ],
            ),
            presence: match newest {
                _ if z.here_now(fresh) => Presence::Here,
                Some(at) if z.here_now(true) => Presence::LastSeen(t_params(
                    lang,
                    "locate.zones.last_seen",
                    &[("zone", &z.name), ("at", &zone.at(at, now))],
                )),
                _ => Presence::None,
            },
            flash: row_flash.filter(|_| flash_zone == Some(z.id)),
        })
        .collect::<Vec<_>>();
    let some = !rows.is_empty();
    // Design 64 step 2 (§3.6's last rows): a phone whose watch holds fences sends a location as
    // soon as it notices a crossing, so the "two intervals" timing is an older launcher's.
    let watched = mode == "interval" && crate::zones::watching(watch);
    let footer = t(
        lang,
        match mode {
            "off" => "locate.zones.footer_off",
            "on_request" => "locate.zones.footer_on_request",
            _ if watched => "locate.zones.footer_watched",
            _ => "locate.zones.footer_interval",
        },
    );
    ZonesView {
        full: (rows.len() as i64 >= crate::zones::MAX_ZONES).then(|| {
            t_params(
                lang,
                "locate.zones.full",
                &[("max", &crate::zones::MAX_ZONES.to_string())],
            )
        }),
        flash: (flash == Some("deleted")).then(|| t(lang, "locate.zones.deleted")),
        needs_regularly: some && mode == "on_request",
        location_unlocked: some && !locked && mode != "off",
        footer,
        confirm_within: (mode == "interval" && !watched)
            .then(|| interval_label(lang, interval * 2)),
        watch_line: watch
            .filter(|_| some && mode == "interval")
            .map(|w| watch_line(lang, w, rows.len(), interval)),
        rows,
    }
}

/// What the phone's zone watch says (design 64 §4.1), under the zones while Regularly: the
/// fences it holds, or why it can't watch. Counts and switches only, never a place.
fn watch_line(lang: Lang, watch: &crate::zones::ZoneWatch, zones: usize, interval: i64) -> String {
    if !watch.location_on {
        return t(lang, "locate.watch.location_off").to_string();
    }
    if !watch.background_location {
        return t(lang, "locate.watch.no_background").to_string();
    }
    if watch.fences == 0 {
        return t(lang, "locate.watch.not_set_up").to_string();
    }
    let mut line = t_count(lang, "locate.watch.watching", watch.fences, &[]);
    if (watch.fences as usize) < zones {
        line.push(' ');
        line.push_str(t(lang, "locate.watch.pending"));
    }
    if !watch.motion_sensor {
        line.push(' ');
        line.push_str(&t_params(
            lang,
            "locate.watch.no_motion",
            &[("interval", &interval_label(lang, interval))],
        ));
    }
    line
}

/// What `static/locate.js` needs from the page, as `data-` attributes on the map box: the phone,
/// the newest fix shown (its `captured_at` and the popup's words), its age for the Locations row
/// ("newest 3 days ago"), the span rendered.
struct MapData {
    device_id: i64,
    shown: String,
    shown_text: String,
    newest_age: String,
    span: i64,
}

/// Find Phone's requests (`partials/recent.html`'s rows, design 48 §1.4, design 88), `#recent`.
pub(crate) struct Recent {
    pub(crate) device_id: i64,
    pub(crate) rows: Vec<CommandRow>,
    /// After Cancel (`?cancel=done|too_late`): the line in the group, and whether it was refused.
    pub(crate) flash: Option<(&'static str, bool)>,
}

/// The newest fix: "10 min ago", "Within 40 m", the exact time for `title`.
struct FixView {
    age: String,
    accuracy: String,
    exact: String,
    /// "Last position before the phone went off" ([before_off]).
    before_off: bool,
    /// "Last seen 9 days ago": the newest fix kept past the location window (the owner's decision
    /// on M1 of round S3), `None` inside it.
    aged: Option<String>,
    /// Design 85 §4.7: the dot before the age, `seen-fresh` or `seen-old` ([Seen]), and its
    /// legend word for screen readers; empty for a time that doesn't read.
    seen_class: &'static str,
    seen_word: &'static str,
    /// "Hasn't moved since 11:40" ([Seen::still_line]).
    still: Option<String>,
}

/// How long after a shutdown a report may come and its fix still be the last before the phone went
/// off: a report racing the shutdown, the phone's clock (the device page's "Off since" minute).
const BEFORE_OFF_SLACK_SECONDS: i64 = 60;

/// An off whose arrival is this close to its shutdown's instant (the phone's clock) arrived as the
/// phone shut down: [before_off] weighs the reports against the arrival. Further apart it was told
/// late, at the next start.
const ARRIVAL_NEAR_OFF_MINUTES: i64 = 5;

/// Locate's "Last position before the phone went off" (2026-10-25): the newest fix is a shutdown's
/// (`shutdown_at`, migration 0097), no status report came after that shutdown - once the phone
/// reports again it is on, whatever fix it re-sends - and the off's event may be said
/// (`off_row`: its title, parameters and arrival; `parent_events::off_said`, the device page's
/// "Off since" exclusions - not for a known restart, not while its push is held;
/// qa-last-position-code Q-5). An event gone with the inbox's 90 days leaves the line to the other
/// two.
fn before_off(
    shutdown_at: Option<DateTime<Utc>>,
    last_report: Option<DateTime<Utc>>,
    off_row: Option<(&str, Option<&str>, DateTime<Utc>)>,
    now: DateTime<Utc>,
) -> bool {
    let Some(off) = shutdown_at else {
        return false;
    };
    // The reports against the off's arrival, both this server's clock (`parent_events::offs_now`,
    // 2026-11-01: a phone clock behind hid the off) - while it arrived near the shutdown. A late off,
    // told with the next start, arrives at the boot: a report just after that boot is the phone on
    // again, so then (and without the event) the shutdown's own instant.
    let arrived = off_row
        .map(|(_, _, created)| created)
        .filter(|created| {
            (*created - off).abs() <= chrono::Duration::minutes(ARRIVAL_NEAR_OFF_MINUTES)
        })
        .unwrap_or(off);
    off_row.is_none_or(|(title, params, created)| {
        crate::parent_events::off_said(title, params, created, now)
    }) && last_report.is_none_or(|report| {
        report <= arrived + chrono::Duration::seconds(BEFORE_OFF_SLACK_SECONDS)
    })
}

/// [before_off] for the phone's newest fix `newest`: its shutdown, the phone's last report and the
/// off's event, read only for a fix that is a shutdown's.
async fn fix_before_off(
    state: &AppState,
    id: i64,
    newest: &DeviceLocation,
    now: DateTime<Utc>,
) -> Result<bool, sqlx::Error> {
    let Some(off) = newest.shutdown_at.as_deref().and_then(parse_db_utc) else {
        return Ok(false);
    };
    let last_report = sqlx::query_scalar::<_, Option<String>>(
        "SELECT MAX(reported_at) FROM device_status WHERE device_id = ?",
    )
    .bind(id)
    .fetch_one(&state.db)
    .await?
    .as_deref()
    .and_then(parse_db_utc);
    // The off's event by its key (the shutdown's instant, as `shutdown_at` is stored).
    let off_row = sqlx::query_as::<_, (String, Option<String>, String)>(
        "SELECT title, params, created_at FROM parent_events WHERE device_id = ? \
         AND kind = 'phone_off' AND dedup_key = ?",
    )
    .bind(id)
    .bind(crate::zones::instant(off))
    .fetch_optional(&state.db)
    .await?
    .and_then(|(title, params, created)| Some((title, params, parse_db_utc(&created)?)));
    Ok(before_off(
        Some(off),
        last_report,
        off_row
            .as_ref()
            .map(|(title, params, at)| (title.as_str(), params.as_deref(), *at)),
        now,
    ))
}

/// The newest Update Location's row under the fix (2026-10-25, PLAN "Locate: the \"Answer to
/// Update Location\" row goes"): only while it is being asked, for [ANSWER_SHOWN_MINUTES] after
/// the phone found itself, and after a failure until the next fix - otherwise no row, the
/// newest point and its time say it.
struct AnswerView {
    /// `asking`, `found` or `failed` (a `data-` hook for tests, never shown).
    state: &'static str,
    answer: Answer,
    when: String,
    exact: String,
}

/// How long "Found within 5 m · 14:29" stays after the phone's answer.
const ANSWER_SHOWN_MINUTES: i64 = 10;

/// The launcher's Update Location answers that say it found nothing (`locateResultMessage`, an
/// older launcher's): a "done" with these words is a failure all the same.
const LOCATE_FAILURES: [&str; 3] = [
    "location is off for this device",
    "no location fix",
    "unknown command - update the launcher",
];

/// The newest Update Location's row ([AnswerView]), or none.
#[derive(Debug, PartialEq)]
enum AnswerState {
    /// Asked; the phone hasn't answered yet.
    Asking,
    /// The phone found itself, `at` its answer: shown for [ANSWER_SHOWN_MINUTES], and only while
    /// the newest fix is the one it found - taken after the ask, not after the answer
    /// (qa-pwa-fixes-d Q-8: an older fix would contradict it, a newer one says it itself).
    Found { answer: Answer, at: DateTime<Utc> },
    /// The phone couldn't, or never answered (`at` its answer, else the ask): shown until a fix
    /// taken after it.
    Failed {
        answer: Answer,
        at: Option<DateTime<Utc>>,
    },
}

/// The newest Update Location `command` (its `status` now) as the row under the fix, given the
/// newest fix's own time `newest_fix` (pure, `tests::the_answer_row_comes_and_goes`).
fn answer_state(
    lang: Lang,
    command: &DeviceCommand,
    status: &CommandStatus,
    newest_fix: Option<DateTime<Utc>>,
    now: DateTime<Utc>,
) -> Option<AnswerState> {
    let asked = parse_db_utc(&command.requested_at);
    let answered = command.acknowledged_at.as_deref().and_then(parse_db_utc);
    let fixed_since = |since: Option<DateTime<Utc>>| matches!((newest_fix, since), (Some(fix), Some(since)) if fix > since);
    let said = |fallback: &str| {
        command
            .result
            .as_deref()
            .and_then(|raw| answer(lang, raw))
            .unwrap_or_else(|| Answer {
                text: t(lang, fallback).to_string(),
                raw: None,
            })
    };
    let failed = |answer: Answer, at: Option<DateTime<Utc>>| {
        (!fixed_since(at.or(asked))).then_some(AnswerState::Failed { answer, at })
    };
    match *status {
        CommandStatus::Waiting { .. }
        | CommandStatus::Received { overdue: false, .. }
        | CommandStatus::Sent {
            unconfirmed: Unconfirmed::NotYet | Unconfirmed::OfferedAgain | Unconfirmed::CantConfirm,
            ..
        } => Some(AnswerState::Asking),
        CommandStatus::Cancelled { .. } | CommandStatus::Erasing { .. } => None,
        CommandStatus::Done { .. }
            if !command
                .result
                .as_deref()
                .is_some_and(|r| LOCATE_FAILURES.contains(&r.trim())) =>
        {
            let at = answered?;
            let age = now - at;
            let its_fix = fixed_since(asked) && !fixed_since(Some(at));
            (its_fix
                && age < chrono::Duration::minutes(ANSWER_SHOWN_MINUTES)
                && age > -chrono::Duration::minutes(1))
            .then(|| AnswerState::Found {
                answer: said("locate.answer.found"),
                at,
            })
        }
        CommandStatus::Done { .. } | CommandStatus::Failed { .. } => {
            failed(said("locate.answer.no_fix"), answered)
        }
        CommandStatus::Expired => failed(
            Answer {
                text: t(lang, "locate.answer.offline").to_string(),
                raw: None,
            },
            asked,
        ),
        CommandStatus::Received { overdue: true, .. }
        | CommandStatus::Sent {
            unconfirmed: Unconfirmed::Lost,
            ..
        } => failed(
            Answer {
                text: t(lang, "locate.answer.no_answer").to_string(),
                raw: None,
            },
            asked,
        ),
    }
}

/// [answer_state] as the page's row, its time in the Phone Setup zone. The asking row is left to
/// the wait's own line right after Update Location (`waiting_for_fix`).
fn answer_view(
    state: AnswerState,
    waiting_for_fix: bool,
    zone: &Zone,
    now: DateTime<Utc>,
) -> Option<AnswerView> {
    let (name, answer, at) = match state {
        AnswerState::Asking if waiting_for_fix => return None,
        AnswerState::Asking => (
            "asking",
            Answer {
                text: t(zone.lang(), "locate.answer.asking").to_string(),
                raw: None,
            },
            None,
        ),
        AnswerState::Found { answer, at } => ("found", answer, Some(at)),
        AnswerState::Failed { answer, at } => ("failed", answer, at),
    };
    Some(AnswerView {
        state: name,
        answer,
        when: at.map(|t| zone.when(t, now)).unwrap_or_default(),
        exact: at.map(|t| zone.exact(t)).unwrap_or_default(),
    })
}

/// A command's row (Find Phone's requests, the summary's Activity, Install Now's): "Lock · 10:58 ·
/// Done", what happened under the label, Cancel while it waits.
pub(crate) struct CommandRow {
    /// The stored command (`ring`, ...): a `data-` hook for tests, never shown.
    pub(crate) kind: String,
    pub(crate) label: &'static str,
    pub(crate) asked: String,
    pub(crate) asked_exact: String,
    pub(crate) word: &'static str,
    /// The word's colour: `green`, `orange`, `red`, `grey`.
    pub(crate) tone: &'static str,
    pub(crate) detail: Option<String>,
    /// The phone's own words, when [detail] rewords them (for `title`).
    pub(crate) detail_title: Option<String>,
    /// The row's id while it can still be cancelled ([DeviceCommand::cancellable]).
    pub(crate) cancel_id: Option<i64>,
    /// Who asked for it, in a family of more than one parent (design 62 §6).
    pub(crate) by: Option<String>,
}

/// A fix as the parent reads it (design 31 step 13, M5): its age from the server's clock and
/// its accuracy.
#[derive(Debug, PartialEq)]
pub(crate) struct Fix {
    /// `None` for a stored time that doesn't read (the intake refuses those since 0054).
    at: Option<DateTime<Utc>>,
    accuracy_m: Option<i64>,
}

impl Fix {
    pub(crate) fn of(captured_at: &str, accuracy: Option<f64>) -> Fix {
        Fix {
            at: parse_db_utc(captured_at),
            accuracy_m: accuracy
                .filter(|a| a.is_finite() && *a >= 0.0)
                .map(|a| a.round() as i64),
        }
    }

    /// The fix as the phone last vouched for it (design 85 QA M1): its time the later of its own
    /// and its still re-send's ([DeviceLocation::seen_at]), so one page never tells two ages for it.
    pub(crate) fn seen(location: &DeviceLocation) -> Fix {
        Fix {
            at: location.seen_at(),
            ..Fix::of(&location.captured_at, location.accuracy_meters)
        }
    }

    /// "10 min ago", "yesterday 21:40", "12 Oct" (`Zone::relative`).
    pub(crate) fn age(&self, zone: &Zone, now: DateTime<Utc>) -> String {
        match self.at {
            Some(at) => zone.relative(at, now),
            None => t(zone.lang(), "locate.time_unknown").to_string(),
        }
    }

    /// "Within 40 m", or "Accuracy unknown".
    pub(crate) fn accuracy(&self, lang: Lang) -> String {
        match self.accuracy_m {
            Some(m) => t_params(lang, "locate.fix.within", &[("m", &m.to_string())]),
            None => t(lang, "locate.fix.accuracy_unknown").to_string(),
        }
    }
}

/// A phone's newest position as both Locate views tell it (design 85 §3.4): the fix, when the
/// phone last vouched for it (its own time, or a still re-send's - `resent_at`, design 64 Q-3), and
/// fresh by `zones::fresh` at `now` - the rule "Here now" uses, so the page never says two things.
/// The only place either view decides fresh or old.
pub(crate) struct Seen {
    fix: DeviceLocation,
    at: DateTime<Utc>,
    fresh: bool,
    /// The fix's own time while a re-send is newer: "Hasn't moved since 11:40".
    still_since: Option<DateTime<Utc>>,
}

impl Seen {
    /// The newest `fix` under the phone's location `mode` and served `interval` (as
    /// `zones::freshness_inputs` reads them); `None` for a stored time that doesn't read.
    fn of(fix: DeviceLocation, mode: &str, interval: i64, now: DateTime<Utc>) -> Option<Seen> {
        let captured = parse_db_utc(&fix.captured_at)?;
        let at = fix.seen_at().unwrap_or(captured);
        Some(Seen {
            fresh: crate::zones::fresh(Some(at), mode, interval, now),
            still_since: (at > captured).then_some(captured),
            at,
            fix,
        })
    }

    /// "Hasn't moved since 11:40", while a still re-send is newer than the fix.
    fn still_line(&self, zone: &Zone, now: DateTime<Utc>) -> Option<String> {
        self.still_since.map(|since| {
            t_params(
                zone.lang(),
                "locate.all.still",
                &[("at", &zone.since(since, now))],
            )
        })
    }
}

/// The Alle view's marker tag (design 85 §4.2): "now", "2 min", "3 h", "2 d" since `at`.
fn age_tag(lang: Lang, at: DateTime<Utc>, now: DateTime<Utc>) -> String {
    let minutes = (now - at).num_minutes().max(0);
    let (key, n) = match minutes {
        0 => return t(lang, "locate.all.tag_now").to_string(),
        m if m < 60 => ("locate.all.tag_min", m),
        m if m < 24 * 60 => ("locate.all.tag_h", m / 60),
        m => ("locate.all.tag_d", m / (24 * 60)),
    };
    t_params(lang, key, &[("n", &n.to_string())])
}

/// Locate's "Alle" view (design 85 §4, mockup B): every phone's chip over one map, the phones
/// without a position under it, Update All and its wait.
struct AllView {
    phones: Vec<AllPhone>,
    wait: Option<AllWait>,
}

impl AllView {
    fn has_none(&self) -> bool {
        self.phones.iter().any(|p| p.position.is_none())
    }
}

/// A phone of the Alle view: its chip and, as `phones.json`'s row, what `static/locate-all.js`
/// places - every word the server's (§4.6), the script only sets text.
#[derive(serde::Serialize)]
struct AllPhone {
    id: i64,
    name: String,
    initial: String,
    /// Its avatar class (`devices::avatar_class`).
    tone: &'static str,
    #[serde(skip_serializing_if = "Option::is_none")]
    photo: Option<String>,
    href: String,
    /// The chip's `aria-label`, the marker's too: "Emma, 2 min ago, within 12 m".
    label: String,
    /// "Show Emma", the bubble's link.
    show: String,
    #[serde(flatten)]
    position: Option<AllPosition>,
    #[serde(skip_serializing_if = "Option::is_none")]
    none: Option<NoPosition>,
    /// While a wait runs, for a phone asked since its start: whether its newest `locate` since
    /// then left Asking.
    #[serde(skip_serializing_if = "Option::is_none")]
    answered: Option<bool>,
    /// Why Update All doesn't ask it (a key: not set up, access removed, location off).
    #[serde(skip)]
    skipped: Option<&'static str>,
}

/// A phone's marker (§4.2) and bubble (§4.3).
#[derive(serde::Serialize)]
struct AllPosition {
    lat: f64,
    lng: f64,
    accuracy_m: Option<i64>,
    fresh: bool,
    /// The seen instant (RFC 3339).
    at: String,
    /// "2 min".
    tag: String,
    /// The bubble's lines after the name: "Last seen 3 h ago", "Within 60 m · At School", and when
    /// they apply "Hasn't moved since 11:40", "Last position before the phone went off".
    lines: Vec<String>,
}

/// The one-phone map's marker (design 85 revision 3): the Alle view's avatar, tag and fresh for the
/// fix the page shows, from the phone's own row; `static/locate.js` reads them as text.
struct Marker {
    tone: &'static str,
    initial: String,
    photo: Option<String>,
    /// "10 min"; `None` without a position.
    tag: Option<String>,
    fresh: bool,
    /// The chip's label: "Emma, 10 min ago, within 40 m".
    label: String,
}

impl AllPhone {
    fn marker(&self) -> Marker {
        Marker {
            tone: self.tone,
            initial: self.initial.clone(),
            photo: self.photo.clone(),
            tag: self.position.as_ref().map(|p| p.tag.clone()),
            fresh: self.position.as_ref().is_some_and(|p| p.fresh),
            label: self.label.clone(),
        }
    }
}

/// Why a phone has no marker (§1.4): its row under the map says it.
#[derive(serde::Serialize, Clone, Copy)]
#[serde(rename_all = "snake_case")]
enum NoPosition {
    NotSetUp,
    Off,
    NoFix,
}

impl NoPosition {
    /// `lang` by reference: the template passes its own.
    fn words(self, lang: &Lang) -> &'static str {
        t(
            *lang,
            match self {
                NoPosition::NotSetUp => "devices.status.not_set_up",
                NoPosition::Off => "locate.all.off",
                NoPosition::NoFix => "locate.all.none_yet",
            },
        )
    }
}

/// The line under Update All after a press (§4.6), and who it didn't ask and why.
struct AllWait {
    line: String,
    /// The press's instant as `phones.json` takes it; `None` when no phone could be asked.
    at: Option<String>,
    skipped: Vec<String>,
}

/// How long after Update All the page still waits (a reload later shows the plain page).
const ALL_WAIT_MINUTES: i64 = 2;

/// Why Update All skips `device` (design 85 O3), as a key: not set up, access removed (it can't
/// come back by itself), location off (no fix ever). `None` = it is asked.
fn skip_reason(
    state: &AppState,
    device: &Device,
    mode: &str,
    now: DateTime<Utc>,
) -> Option<&'static str> {
    if device.enrolled_at.is_none() {
        Some("devices.status.not_set_up")
    } else if matches!(
        crate::handlers::devices::connection_of(state, device, now),
        crate::streams::ConnectionState::Disconnected
    ) {
        Some("device.about.access_removed")
    } else if mode == "off" {
        Some("locate.all.off")
    } else {
        None
    }
}

/// The phone's location mode. A phone always has its policy row; a missing one reads as a new
/// phone's.
async fn location_mode(state: &AppState, device_id: i64) -> Result<String, sqlx::Error> {
    Ok(
        sqlx::query_scalar("SELECT location_mode FROM device_policy WHERE device_id = ?")
            .bind(device_id)
            .fetch_optional(&state.db)
            .await?
            .unwrap_or_else(|| "on_request".to_string()),
    )
}

/// The phone's newest fix.
async fn newest_fix(
    state: &AppState,
    device_id: i64,
) -> Result<Option<DeviceLocation>, sqlx::Error> {
    sqlx::query_as::<_, DeviceLocation>(
        "SELECT * FROM device_locations WHERE device_id = ? ORDER BY captured_at DESC, id DESC \
         LIMIT 1",
    )
    .bind(device_id)
    .fetch_optional(&state.db)
    .await
}

/// `device` as the Alle view tells it at `now`; `wait_at` = the start of a wait after Update All.
async fn all_phone(
    state: &AppState,
    device: &Device,
    zone: &Zone,
    now: DateTime<Utc>,
    wait_at: Option<DateTime<Utc>>,
) -> Result<AllPhone, sqlx::Error> {
    use crate::handlers::devices::{avatar_class, avatar_photo, initial};
    let lang = zone.lang();
    let id = device.id;
    let (_, mode, interval) = crate::zones::freshness_inputs(&state.db, id).await?;
    let seen = newest_fix(state, id)
        .await?
        .and_then(|fix| Seen::of(fix, &mode, interval, now));
    let (position, none, label) = match seen {
        Some(seen) => {
            let zones = crate::zones::list(&state.db, id).await?;
            let fix = Fix::of(&seen.fix.captured_at, seen.fix.accuracy_meters);
            let accuracy = match fix_badge(lang, &zones, &seen.fix) {
                Some(badge) => format!("{} · {badge}", fix.accuracy(lang)),
                None => fix.accuracy(lang),
            };
            let age = zone.relative(seen.at, now);
            let label = format!(
                "{}, {age}, {}",
                device.name,
                lowercase_first(&fix.accuracy(lang))
            );
            let mut lines = vec![
                t_params(lang, "locate.all.seen", &[("age", &age)]),
                accuracy,
            ];
            lines.extend(seen.still_line(zone, now));
            if fix_before_off(state, id, &seen.fix, now).await? {
                lines.push(t(lang, "locate.fix.before_off").to_string());
            }
            let position = AllPosition {
                lat: seen.fix.latitude,
                lng: seen.fix.longitude,
                accuracy_m: fix.accuracy_m,
                fresh: seen.fresh,
                at: crate::zones::instant(seen.at),
                tag: age_tag(lang, seen.at, now),
                lines,
            };
            (Some(position), None, label)
        }
        None => {
            let why = if device.enrolled_at.is_none() {
                NoPosition::NotSetUp
            } else if mode == "off" {
                NoPosition::Off
            } else {
                NoPosition::NoFix
            };
            let label = format!("{}, {}", device.name, why.words(&lang).to_lowercase());
            (None, Some(why), label)
        }
    };
    let answered = match wait_at {
        Some(at) => answered_since(state, id, at, now).await?,
        None => None,
    };
    Ok(AllPhone {
        id,
        name: device.name.clone(),
        initial: initial(&device.name),
        tone: avatar_class(device),
        photo: avatar_photo(device),
        href: format!("/devices/locate?device={id}"),
        label,
        show: t_params(lang, "locate.all.show", &[("name", &device.name)]),
        position,
        none,
        answered,
        skipped: skip_reason(state, device, &mode, now),
    })
}

/// Whether the phone answered Update All (design 85 §4.6): its newest `locate` queued at or after
/// `at` has left Asking - a fix came, a failure, or it expired. Fix times are not compared, so a
/// still re-send or a regular fix never counts. `None`: nothing was asked of it since `at`.
async fn answered_since(
    state: &AppState,
    device_id: i64,
    at: DateTime<Utc>,
    now: DateTime<Utc>,
) -> Result<Option<bool>, sqlx::Error> {
    let command = sqlx::query_as::<_, DeviceCommand>(
        "SELECT * FROM device_commands WHERE device_id = ? AND command = 'locate' \
         AND requested_at >= ? ORDER BY requested_at DESC, id DESC LIMIT 1",
    )
    .bind(device_id)
    .bind(at.format("%Y-%m-%d %H:%M:%S").to_string())
    .fetch_optional(&state.db)
    .await?;
    let Some(command) = command else {
        return Ok(None);
    };
    let receipts =
        crate::handlers::device_api::sends_command_receipts(&state.db, device_id).await?;
    let status = command.status(now.naive_utc(), receipts);
    Ok(Some(!matches!(
        answer_state(Lang::En, &command, &status, None, now),
        Some(AnswerState::Asking)
    )))
}

/// Every phone as the Alle view tells it, in the page's order.
async fn all_phones(
    state: &AppState,
    devices: &[Device],
    zone: &Zone,
    now: DateTime<Utc>,
    wait_at: Option<DateTime<Utc>>,
) -> Result<Vec<AllPhone>, sqlx::Error> {
    let mut phones = Vec::with_capacity(devices.len());
    for device in devices {
        phones.push(all_phone(state, device, zone, now, wait_at).await?);
    }
    Ok(phones)
}

/// The Alle view; `requested_at` is the address's `at` after Update All - a wait only while it
/// reads and is under [ALL_WAIT_MINUTES] old (a reload, Back or a bookmark later shows the plain
/// page).
async fn all_view(
    state: &AppState,
    devices: &[Device],
    zone: &Zone,
    now: DateTime<Utc>,
    requested_at: Option<&str>,
) -> Result<AllView, sqlx::Error> {
    let lang = zone.lang();
    let wait_at = requested_at.and_then(parse_db_utc).filter(|at| {
        let age = now - *at;
        age > -chrono::Duration::minutes(1) && age < chrono::Duration::minutes(ALL_WAIT_MINUTES)
    });
    let phones = all_phones(state, devices, zone, now, wait_at).await?;
    let wait = wait_at.map(|at| {
        let asked = phones.iter().filter(|p| p.answered.is_some()).count() as i64;
        let skipped = phones
            .iter()
            .filter_map(|p| {
                let why = lowercase_first(t(lang, p.skipped?));
                Some(t_params(
                    lang,
                    "locate.all.skipped",
                    &[("name", &p.name), ("why", &why)],
                ))
            })
            .collect();
        AllWait {
            line: if asked == 0 {
                t(lang, "locate.all.none_to_ask").to_string()
            } else {
                t_count(lang, "locate.all.asking", asked, &[])
            },
            at: (asked > 0).then(|| crate::zones::instant(at)),
            skipped,
        }
    });
    Ok(AllView { phones, wait })
}

/// A phone's answer to a command (`device_commands.result`, the launcher's words) in plain words.
/// `raw` keeps the phone's own words when they were reworded (for a `title`); an answer this
/// server doesn't know is shown as the phone sent it.
#[derive(Debug, PartialEq)]
struct Answer {
    text: String,
    raw: Option<String>,
}

/// The launcher's answers (`MdmSyncWorker.dispatchPendingCommand`, `locateResultMessage`,
/// `wipeResultMessage`, `endWindowsResult`) as §4 words in the reader's language. `None` for one it
/// doesn't know.
pub(crate) fn reword(lang: Lang, raw: &str) -> Option<String> {
    let known = match raw {
        "ringing" => "locate.answer.ringing",
        "stopped" => "locate.answer.stopped",
        "locked (handy lock)" => "locate.answer.locked_pin",
        "locked (Android)" => "locate.answer.screen_off",
        // Design 59 §2.3: Android's lock; a fingerprint opens it again only after the PIN.
        "locked (android pin)" => "locate.answer.locked_android",
        "failed to lock" => "locate.answer.lock_failed",
        "paused for 2 hours" => "locate.answer.unlocked_2h",
        "nothing was open" => "locate.answer.nothing_open",
        "location is off for this device" => "locate.answer.location_off",
        "no location fix" => "locate.answer.no_fix",
        "unknown command - update the launcher" => "locate.answer.too_old",
        // Install Now (design 37 §2.4).
        "not offered" => "locate.answer.not_offered",
        "refused: wrong_signer" => "locate.answer.wrong_signer",
        "refused: not_ours" => "locate.answer.not_ours",
        "refused: downgrade" => "locate.answer.downgrade",
        "refused: unparseable" => "locate.answer.unparseable",
        "refused: release" => "locate.answer.release",
        "download failed" => "locate.answer.download_failed",
        "download corrupt" => "locate.answer.download_corrupt",
        "no space" => "locate.answer.no_space",
        "superseded" => "locate.answer.superseded",
        "call never ended" => "locate.answer.call",
        // Leave Tailnet (security review S-L10, `testdata/leave_tailnet.json`).
        "left the tailnet" => "locate.answer.left_tailnet",
        "not on the tailnet" => "locate.answer.not_on_tailnet",
        "failed to leave the tailnet" => "locate.answer.leave_tailnet_failed",
        "server is reached only over the tailnet" => "locate.answer.tailnet_only",
        _ => "",
    };
    if !known.is_empty() {
        return Some(t(lang, known).to_string());
    }
    // Design 59: `set_kid_pin`'s answers, in the summary's Activity (the Lock page words them too).
    if let Some(answer) = crate::kid_lock::PinAnswer::parse(raw) {
        return Some(t(lang, answer.words()).to_string());
    }
    if let Some(count) = raw
        .strip_prefix("allowed: ")
        .and_then(|rest| rest.strip_suffix(" apps"))
        .and_then(|n| n.parse::<u32>().ok())
    {
        return Some(t_count(lang, "locate.answer.allowed", count.into(), &[]));
    }
    if raw.starts_with("ended: ") {
        return Some(t(lang, "locate.answer.locked_again").to_string());
    }
    if let Some(version) = raw.strip_prefix("installed: ") {
        return Some(t_params(
            lang,
            "locate.answer.installed",
            &[("version", version)],
        ));
    }
    if let Some(version) = raw.strip_prefix("already running: ") {
        return Some(t_params(
            lang,
            "locate.answer.already",
            &[("version", version)],
        ));
    }
    if raw.starts_with("install failed: ") {
        return Some(t(lang, "locate.answer.install_failed").to_string());
    }
    // "fix ±12 m, 3 s old" / "fix, 3 s old".
    if let Some(rest) = raw.strip_prefix("fix")
        && rest.ends_with(" s old")
    {
        let metres = rest
            .strip_prefix(" ±")
            .and_then(|r| r.split_once(" m,"))
            .and_then(|(m, _)| m.parse::<i64>().ok());
        return Some(match metres {
            Some(m) => t_params(lang, "locate.answer.found_within", &[("m", &m.to_string())]),
            None => t(lang, "locate.answer.found").to_string(),
        });
    }
    if raw.starts_with("wiping") {
        return Some(t(lang, "locate.status.erasing").to_string());
    }
    if raw.starts_with("wipe failed") {
        return Some(t(lang, "locate.answer.not_erased").to_string());
    }
    None
}

fn answer(lang: Lang, raw: &str) -> Option<Answer> {
    let raw = raw.trim();
    // "ok"/"failed" are this server's own words for an answer without a message
    // (`device_api::command_result`): the row's word says it already.
    if raw.is_empty() || raw == "ok" || raw == "failed" {
        return None;
    }
    Some(match reword(lang, raw) {
        Some(text) => Answer {
            text,
            raw: Some(raw.to_string()),
        },
        None => Answer {
            text: raw.to_string(),
            raw: None,
        },
    })
}

/// A line with the time it happened ("The phone got it (10:59)."), or the one without when the
/// time doesn't read.
fn line_at(
    at: Option<DateTime<Utc>>,
    (with, without): (&str, &str),
    zone: &Zone,
    now: DateTime<Utc>,
) -> String {
    let lang = zone.lang();
    match at {
        Some(at) => t_params(lang, with, &[("at", &zone.when(at, now))]),
        None => t(lang, without).to_string(),
    }
}

/// A status's word, its colour and the line under the row (times local, §3.10), in the zone's
/// language.
fn status_words(
    status: &CommandStatus,
    zone: &Zone,
    now: DateTime<Utc>,
) -> (&'static str, &'static str, Option<String>) {
    let lang = zone.lang();
    let line = |key: &str| Some(t(lang, key).to_string());
    let (word, tone, line) = match *status {
        CommandStatus::Done { .. } => ("locate.status.done", "green", None),
        CommandStatus::Failed { .. } => ("locate.status.failed", "red", None),
        CommandStatus::Expired => (
            "locate.status.expired",
            "grey",
            line("locate.status.line.expired"),
        ),
        CommandStatus::Cancelled { by, .. } => match by {
            CancelledBy::Parent => (
                "locate.status.cancelled",
                "grey",
                line("locate.status.line.cancelled"),
            ),
            CancelledBy::Restart => (
                "locate.status.cancelled",
                "grey",
                line("locate.status.line.restart"),
            ),
            CancelledBy::Enrolled => (
                "locate.status.cancelled",
                "grey",
                line("locate.status.line.enrolled"),
            ),
            CancelledBy::Replaced => (
                "locate.status.replaced",
                "grey",
                line("locate.status.line.replaced"),
            ),
        },
        CommandStatus::Erasing { at } => (
            "locate.status.erasing",
            "orange",
            Some(line_at(
                at,
                (
                    "locate.status.line.erasing_at",
                    "locate.status.line.erasing",
                ),
                zone,
                now,
            )),
        ),
        CommandStatus::Received { at, overdue: true } => (
            "locate.status.no_answer",
            "red",
            Some(line_at(
                at,
                (
                    "locate.status.line.overdue_at",
                    "locate.status.line.overdue",
                ),
                zone,
                now,
            )),
        ),
        CommandStatus::Received { at, overdue: false } => (
            "locate.status.received",
            "grey",
            Some(line_at(
                at,
                (
                    "locate.status.line.received_at",
                    "locate.status.line.received",
                ),
                zone,
                now,
            )),
        ),
        CommandStatus::Waiting {
            expires: Some(expires),
        } => (
            "locate.status.waiting",
            "orange",
            Some(t_params(
                lang,
                "locate.status.line.expires",
                &[("at", &zone.until(expires, now))],
            )),
        ),
        CommandStatus::Waiting { expires: None } => (
            "locate.status.waiting",
            "orange",
            line("locate.status.line.when_back"),
        ),
        CommandStatus::Sent { unconfirmed, .. } => match unconfirmed {
            Unconfirmed::NotYet => (
                "locate.status.sent",
                "grey",
                line("locate.status.line.not_yet"),
            ),
            Unconfirmed::OfferedAgain => (
                "locate.status.sent",
                "orange",
                line("locate.status.line.offered_again"),
            ),
            Unconfirmed::Lost => (
                "locate.status.not_confirmed",
                "red",
                line("locate.status.line.lost"),
            ),
            Unconfirmed::CantConfirm => (
                "locate.status.sent",
                "grey",
                line("locate.status.line.cant_confirm"),
            ),
        },
    };
    (t(lang, word), tone, line)
}

pub(crate) fn command_row(
    command: &DeviceCommand,
    names: &crate::parents::Names,
    zone: &Zone,
    now: DateTime<Utc>,
    receipts: bool,
) -> CommandRow {
    let lang = zone.lang();
    let naive_now = now.naive_utc();
    let status = command.status(naive_now, receipts);
    let (word, tone, line) = status_words(&status, zone, now);
    // A result is the row's line: what the phone did, in its own words reworded.
    // A result is the row's line: what the phone did, reworded. An erase the phone reported as
    // going through reads as one it confirmed (one wording for both).
    let answered = match status {
        CommandStatus::Erasing { .. } => None,
        _ => command.result.as_deref().and_then(|raw| answer(lang, raw)),
    };
    let (detail, detail_title) = match answered {
        Some(Answer { text, raw }) => (Some(text), raw),
        None => (line, None),
    };
    let asked = parse_db_utc(&command.requested_at);
    CommandRow {
        kind: command.command.clone(),
        label: t(lang, command_label(&command.command)),
        asked: asked.map_or_else(
            || t(lang, "locate.time_unknown").to_string(),
            |at| zone.at(at, now),
        ),
        asked_exact: asked.map(|t| zone.exact(t)).unwrap_or_default(),
        word,
        tone,
        detail,
        detail_title,
        cancel_id: command.cancellable(naive_now).then_some(command.id),
        by: names.requester(command.requested_by),
    }
}

/// The newest `locate` command for a device: `(id, result, acknowledged_at)`.
async fn latest_locate(
    state: &AppState,
    device_id: i64,
) -> Result<Option<(i64, Option<String>, Option<String>)>, sqlx::Error> {
    sqlx::query_as(
        "SELECT id, result, acknowledged_at FROM device_commands \
         WHERE device_id = ? AND command = 'locate' \
         ORDER BY requested_at DESC, id DESC LIMIT 1",
    )
    .bind(device_id)
    .fetch_optional(&state.db)
    .await
}

/// `{"id": .., "result": ".." | null, "text": ".." | null}` of the newest `locate` command (`{}`
/// if none) - the page's wait shows the phone's answer (`text`, reworded in the reader's language:
/// `static/locate.js` asks with the page's `?lang=`) as soon as it arrives.
pub async fn locate_result_json(
    State(state): State<AppState>,
    lang: Lang,
    Path(id): Path<i64>,
) -> Response {
    match latest_locate(&state, id).await {
        Ok(Some((cmd, result, _))) => {
            let text = result
                .as_deref()
                .and_then(|raw| answer(lang, raw))
                .map(|a| a.text);
            Json(serde_json::json!({ "id": cmd, "result": result, "text": text })).into_response()
        }
        Ok(None) => Json(serde_json::json!({})).into_response(),
        Err(err) => db_error(id, err, lang),
    }
}

/// Find Phone's requests for `device` (design 48 §1.4), newest first, with the line after a Cancel
/// (`cancel`, the page's `?cancel=`): every request still in flight, at any age; one that went
/// wrong (failed, expired, never confirmed, no answer) for [REQUESTS_HOURS]; and the newest Ring,
/// Stop Ringing, Lock and Erase whatever became of it. Never a request the parent cancelled (the
/// Cancel's line says so; it never counts as the newest, so it can't hide the last real one) nor
/// a finished Update Location (the map shows it). Every kept row is read - retention bounds them
/// (`retention::prune`).
pub(crate) async fn requests(
    state: &AppState,
    device_id: i64,
    cancel: Option<&str>,
    zone: &Zone,
    now: DateTime<Utc>,
) -> Result<Recent, sqlx::Error> {
    let list = vec!["?"; LOCATE_KINDS.len()].join(", ");
    let sql = format!(
        "SELECT * FROM device_commands WHERE device_id = ? AND command IN ({list}) \
         ORDER BY requested_at DESC, id DESC"
    );
    let mut query = sqlx::query_as::<_, DeviceCommand>(&sql).bind(device_id);
    for kind in LOCATE_KINDS {
        query = query.bind(*kind);
    }
    let commands = query.fetch_all(&state.db).await?;
    let receipts =
        crate::handlers::device_api::sends_command_receipts(&state.db, device_id).await?;
    let names = crate::parents::Names::load(&state.db).await?;
    let since = now - chrono::Duration::hours(REQUESTS_HOURS);
    let mut seen = std::collections::HashSet::new();
    let rows = commands
        .iter()
        .filter(|command| {
            let status = command.status(now.naive_utc(), receipts);
            // The parent's own Cancel never hides the last real one of its kind.
            if matches!(
                status,
                CommandStatus::Cancelled {
                    by: CancelledBy::Parent,
                    ..
                }
            ) {
                return false;
            }
            let newest = seen.insert(command.command.as_str()) && command.command != "locate";
            let lately = parse_db_utc(&command.requested_at).is_some_and(|t| t >= since);
            in_requests(&status, lately, newest)
        })
        .map(|command| command_row(command, &names, zone, now, receipts))
        .collect();
    Ok(Recent {
        device_id,
        rows,
        flash: cancel_flash(zone.lang(), cancel),
    })
}

/// Whether a request is a row of Requests (design 48 §1.4): `lately` = asked within
/// [REQUESTS_HOURS], `newest` = the newest Ring, Stop Ringing, Lock or Erase.
fn in_requests(status: &CommandStatus, lately: bool, newest: bool) -> bool {
    match status {
        CommandStatus::Cancelled {
            by: CancelledBy::Parent,
            ..
        } => false,
        CommandStatus::Waiting { .. }
        | CommandStatus::Erasing { .. }
        | CommandStatus::Received { overdue: false, .. }
        | CommandStatus::Sent {
            unconfirmed: Unconfirmed::NotYet | Unconfirmed::OfferedAgain | Unconfirmed::CantConfirm,
            ..
        } => true,
        _ if newest => true,
        CommandStatus::Failed { .. }
        | CommandStatus::Expired
        | CommandStatus::Sent {
            unconfirmed: Unconfirmed::Lost,
            ..
        }
        | CommandStatus::Received { overdue: true, .. } => lately,
        CommandStatus::Done { .. } | CommandStatus::Cancelled { .. } => false,
    }
}

/// The span in `?span=` / `?since=`-style text: one of [SPANS]' hours, else the default.
fn span_of(raw: Option<&String>) -> i64 {
    raw.and_then(|s| s.trim().parse::<i64>().ok())
        .filter(|hours| SPANS.iter().any(|(h, _)| h == hours))
        .unwrap_or(SPANS[0].0)
}

/// The first `captured_at` inside the last `hours` (stored as `YYYY-MM-DDTHH:MM:SS.mmmZ`, so the
/// text compares as the time), or `None` for everything (0, or more hours than a clock holds).
fn span_start(hours: i64, now: DateTime<Utc>) -> Option<String> {
    let back = chrono::Duration::try_hours(hours).filter(|_| hours > 0)?;
    now.checked_sub_signed(back)
        .map(|start| start.format("%Y-%m-%dT%H:%M:%S%.3fZ").to_string())
}

/// The Locations row's value (design 44 §1.4, §1.6): "14 in the last 12 hours", "3 kept" (All
/// kept), "None yet"; with nothing in the span but an older fix, "0 in the last 12 hours · newest
/// 3 days ago". `static/locate.js` words it the same way when the span changes.
fn locations_value(lang: Lang, count: i64, hours: i64, newest_age: Option<&str>) -> String {
    let n = count.to_string();
    let in_span = |n: &str| {
        let key = match hours {
            24 => "locate.locations.in_24",
            168 => "locate.locations.in_168",
            _ => "locate.locations.in_12",
        };
        t_params(lang, key, &[("count", n)])
    };
    match (count, newest_age) {
        (0, None) => t(lang, "locate.locations.none").to_string(),
        _ if hours == 0 => t_params(lang, "locate.locations.kept", &[("count", &n)]),
        (0, Some(age)) => format!(
            "{} · {}",
            in_span("0"),
            t_params(lang, "locate.locations.newest", &[("age", age)])
        ),
        _ => in_span(&n),
    }
}

/// The line after a Cancel (`?cancel=`), in Requests or the summary's Activity, and whether it
/// was refused.
pub(crate) fn cancel_flash(lang: Lang, code: Option<&str>) -> Option<(&'static str, bool)> {
    match code? {
        "done" => Some((t(lang, "locate.cancel.done"), false)),
        "too_late" => Some((t(lang, "locate.cancel.too_late"), true)),
        _ => None,
    }
}

/// A read failed: the page isn't shown with made-up values (qa-31-s4-code L4).
fn db_error(device_id: i64, err: sqlx::Error, lang: Lang) -> Response {
    tracing::error!(device_id, %err, "can't read the Locate page's data");
    crate::i18n::error(lang, StatusCode::INTERNAL_SERVER_ERROR, "error.read_phone")
}

/// "10 min", "1 hour", "4 hours".
fn interval_label(lang: Lang, minutes: i64) -> String {
    match minutes {
        m if m < 60 => t_params(lang, "common.n_min", &[("n", &m.to_string())]),
        m => t_count(lang, "locate.hours", m / 60, &[]),
    }
}

/// What the request asks the Locate page to show besides the phone.
struct Asked<'a> {
    /// Just pressed Update Location (`?requested=1`).
    waiting_for_fix: bool,
    /// `?span=` ([span_of]).
    span: i64,
    /// After Update All (`?requested=all&at=`): the press's instant as the address has it.
    all_at: Option<&'a str>,
}

/// The page. With one phone it is shown at once; with several and none (or an unknown one)
/// chosen, the Alle view (design 85). Every read that fails is a 500, never a default (an "Off"
/// that isn't true).
async fn render_locate_page(
    state: &AppState,
    admin_id: i64,
    selected_id: Option<i64>,
    asked: Asked<'_>,
    lang: Lang,
) -> Result<Html<String>, sqlx::Error> {
    let devices = sqlx::query_as::<_, Device>("SELECT * FROM devices ORDER BY name, id")
        .fetch_all(&state.db)
        .await?;
    let zone = Zone::load(&state.db, lang).await;
    let now = Utc::now();
    let device = selected_id
        .and_then(|id| devices.iter().find(|d| d.id == id))
        .or_else(|| (devices.len() == 1).then(|| &devices[0]))
        .cloned();
    let all = match device {
        None if devices.len() >= 2 => {
            Some(all_view(state, &devices, &zone, now, asked.all_at).await?)
        }
        _ => None,
    };
    let selected = match device {
        Some(device) => {
            // Design 85 revision 3: the chips and the marker as the Alle view draws them.
            let rows = all_phones(state, &devices, &zone, now, None).await?;
            Some(locate_view(state, device, rows, &zone, now, asked).await?)
        }
        None => None,
    };
    Ok(Html(
        LocateTemplate {
            lang,
            title: t(lang, "nav.locate").to_string(),
            no_phones: devices.is_empty(),
            selected,
            all,
            bell: crate::parent_events::Bell::load(&state.db, admin_id).await,
        }
        .render()
        .unwrap(),
    ))
}

/// A phone's location settings and locks as the Locate pages read them. A phone always has its
/// policy row (`insert_device_with_policy`); a missing one reads as the defaults a new phone gets.
struct LocationPolicy {
    mode: String,
    interval: i64,
    retention: i64,
    /// Design 59 §2.3: Android's lock with a child PIN.
    android_lock: bool,
    /// "Keep location on".
    locked: bool,
}

async fn location_policy(state: &AppState, id: i64) -> Result<LocationPolicy, sqlx::Error> {
    let row: Option<(String, i64, i64, bool, bool)> = sqlx::query_as(
        "SELECT location_mode, location_interval_minutes, location_retention_days, \
         kid_keyguard = ? AND kid_pin_hash IS NOT NULL, lock_location \
         FROM device_policy WHERE device_id = ?",
    )
    .bind(crate::kid_lock::KEYGUARD_ANDROID)
    .bind(id)
    .fetch_optional(&state.db)
    .await?;
    let (mode, interval, retention, android_lock, locked) = row.unwrap_or_else(|| {
        (
            "on_request".to_string(),
            30,
            crate::retention::DEFAULT_LOCATION_RETENTION_DAYS,
            false,
            true,
        )
    });
    Ok(LocationPolicy {
        mode,
        interval,
        retention,
        android_lock,
        locked,
    })
}

/// A phone's zones, its latest zone watch and the interval it is served (design 64 step 2: the
/// 30-min floor while it has zones and watches them).
struct PhoneZones {
    list: Vec<crate::zones::Zone>,
    watch: Option<crate::zones::ZoneWatch>,
    served_interval: i64,
}

async fn phone_zones(
    state: &AppState,
    id: i64,
    policy: &LocationPolicy,
) -> Result<PhoneZones, sqlx::Error> {
    let list = crate::zones::list(&state.db, id).await?;
    let watch = crate::zones::last_zone_watch(&state.db, id).await?;
    let served_interval = crate::zones::served_interval(
        &policy.mode,
        policy.interval,
        list.len(),
        crate::zones::watching(watch.as_ref()),
    );
    Ok(PhoneZones {
        list,
        watch,
        served_interval,
    })
}

/// The Zones part for `zones` ([zones_view]) with the newest fix's times; `flash` is `?zone=`,
/// `flash_zone` `?zone_id=`.
fn zones_of(
    zones: &PhoneZones,
    policy: &LocationPolicy,
    newest: Option<&DeviceLocation>,
    flash: Option<&str>,
    flash_zone: Option<i64>,
    zone: &Zone,
    now: DateTime<Utc>,
) -> ZonesView {
    zones_view(
        &zones.list,
        &policy.mode,
        zones.served_interval,
        policy.locked,
        flash,
        flash_zone,
        newest.and_then(|l| parse_db_utc(&l.captured_at)),
        newest.and_then(DeviceLocation::seen_at),
        zones.watch.as_ref(),
        zone,
        now,
    )
}

/// The Zones row's value (design 88): "None yet", "3", "3 · Here now: School" (the first zone the
/// phone is in now).
fn zones_value(lang: Lang, view: &ZonesView) -> String {
    let count = view.rows.len();
    if count == 0 {
        return t(lang, "locate.rows.zones_none").to_string();
    }
    match view.rows.iter().find(|z| z.presence.here()) {
        Some(here) => t_params(
            lang,
            "locate.rows.zones_here",
            &[("count", &count.to_string()), ("zone", &here.name)],
        ),
        None => count.to_string(),
    }
}

/// The Location Settings row's value (design 88): "Off", "Only when asked", "Every 15 min" - the
/// setting, not the interval the zones serve.
fn settings_value(lang: Lang, mode: &str, interval: i64) -> String {
    match mode {
        "off" => t(lang, "common.off").to_string(),
        "interval" => match interval {
            m if m < 60 => t_params(lang, "locate.rows.every_min", &[("n", &m.to_string())]),
            60 => t(lang, "locate.rows.every_hour").to_string(),
            m => t_params(
                lang,
                "locate.rows.every_hours",
                &[("n", &(m / 60).to_string())],
            ),
        },
        _ => t(lang, "locate.settings.on_request").to_string(),
    }
}

async fn locate_view(
    state: &AppState,
    device: Device,
    rows: Vec<AllPhone>,
    zone: &Zone,
    now: DateTime<Utc>,
    asked: Asked<'_>,
) -> Result<Locate, sqlx::Error> {
    let Asked {
        waiting_for_fix,
        span,
        all_at: _,
    } = asked;
    let id = device.id;
    let lang = zone.lang();
    let marker = rows
        .iter()
        .find(|p| p.id == id)
        .map(AllPhone::marker)
        .unwrap_or_else(|| Marker {
            tone: crate::handlers::devices::avatar_class(&device),
            initial: crate::handlers::devices::initial(&device.name),
            photo: crate::handlers::devices::avatar_photo(&device),
            tag: None,
            fresh: false,
            label: device.name.clone(),
        });
    let chips = if rows.len() >= 2 { rows } else { Vec::new() };

    let policy = location_policy(state, id).await?;
    let phone_zones = phone_zones(state, id, &policy).await?;
    let served_interval = phone_zones.served_interval;

    let (newest, mut map) = map_data(state, id, span, zone, now).await?;
    // The newest fix's badge (design 64 §3.5): the zones that fix is surely in.
    let badge = newest
        .as_ref()
        .and_then(|l| fix_badge(lang, &phone_zones.list, l));
    let fix_before_off = match &newest {
        Some(l) => fix_before_off(state, id, l, now).await?,
        None => false,
    };
    // Design 85 §4.7: fresh or old by the Alle view's rule, and the still re-send's age.
    let seen = newest
        .clone()
        .and_then(|l| Seen::of(l, &policy.mode, served_interval, now));
    let fix = newest.as_ref().map(|l| {
        // Its age, title and "Last seen 9 days ago" from the later of the fix and its re-send.
        let fix = Fix::seen(l);
        let accuracy = match &badge {
            Some(badge) => format!("{} · {badge}", fix.accuracy(lang)),
            None => fix.accuracy(lang),
        };
        let (seen_class, seen_word) = match &seen {
            Some(s) if s.fresh => ("seen-fresh", t(lang, "locate.all.fresh")),
            Some(_) => ("seen-old", t(lang, "locate.all.old")),
            None => ("", ""),
        };
        FixView {
            age: fix.age(zone, now),
            accuracy,
            exact: fix_title(lang, fix.at.map(|at| zone.exact(at)).unwrap_or_default(), l),
            before_off: fix_before_off,
            aged: fix
                .at
                .map(|at| (now - at).num_days())
                .filter(|days| *days >= policy.retention.max(1))
                .map(|days| t_count(lang, "locate.fix.aged", days, &[])),
            seen_class,
            seen_word,
            still: seen.as_ref().and_then(|s| s.still_line(zone, now)),
        }
    });
    if let Some(badge) = badge.as_ref().filter(|_| newest.is_some()) {
        map.shown_text = format!("{} · {}", map.shown_text, lowercase_first(badge));
    }
    let count = count_in_span(state, id, span, now).await?;
    let locations_value = locations_value(
        lang,
        count,
        span,
        newest.is_some().then_some(map.newest_age.as_str()),
    );

    let newest_locate = sqlx::query_as::<_, DeviceCommand>(
        // A cancelled request never hides the one before it (qa-pwa-fixes-d Q-9, as Requests).
        "SELECT * FROM device_commands WHERE device_id = ? AND command = 'locate' \
         AND cancelled_at IS NULL ORDER BY requested_at DESC, id DESC LIMIT 1",
    )
    .bind(id)
    .fetch_optional(&state.db)
    .await?;
    let answer = match newest_locate {
        Some(command) => {
            let receipts =
                crate::handlers::device_api::sends_command_receipts(&state.db, id).await?;
            let status = command.status(now.naive_utc(), receipts);
            let newest_fix = newest.as_ref().and_then(|l| parse_db_utc(&l.captured_at));
            answer_state(lang, &command, &status, newest_fix, now)
                .and_then(|state| answer_view(state, waiting_for_fix, zone, now))
        }
        None => None,
    };

    let zones = zones_of(
        &phone_zones,
        &policy,
        newest.as_ref(),
        None,
        None,
        zone,
        now,
    );
    // Q-9: with when the count last rose.
    let mock_at = crate::zones::mock_logged_at(&state.db, id).await?;
    let mock_ignored = phone_zones
        .watch
        .filter(|w| w.mock_ignored > 0)
        .map(|w| match mock_at {
            Some(at) => t_count(
                lang,
                "locate.mock.ignored_last",
                w.mock_ignored,
                &[("at", &zone.at(at, now))],
            ),
            None => t_count(lang, "locate.mock.ignored", w.mock_ignored, &[]),
        });
    Ok(Locate {
        device,
        chips,
        marker,
        fix,
        map,
        span,
        locations_value,
        answer,
        waiting_for_fix,
        location_off: policy.mode == "off",
        mock_ignored,
        has_zones: !zones.rows.is_empty(),
        zones_value: zones_value(lang, &zones),
        settings_value: settings_value(lang, &policy.mode, policy.interval),
    })
}

/// The phone for one of its Locate pages: 404 for an unknown one, 500 when the read fails.
async fn phone_or_error(state: &AppState, id: i64, lang: Lang) -> Result<Device, Box<Response>> {
    match sqlx::query_as::<_, Device>("SELECT * FROM devices WHERE id = ?")
        .bind(id)
        .fetch_optional(&state.db)
        .await
    {
        Ok(Some(device)) => Ok(device),
        Ok(None) => Err(Box::new(crate::i18n::error(
            lang,
            StatusCode::NOT_FOUND,
            "error.device_not_found",
        ))),
        Err(err) => Err(Box::new(db_error(id, err, lang))),
    }
}

/// `GET /devices/{id}/zones` (design 88; no-store, as every zones path): the phone's zones, each
/// to its page, Locate Regularly when asked, Add Zone. A zone's save lands on its row
/// (`?zone=added|saved&zone_id=N#zone-N`) with its line, a delete and Locate Regularly on `#zones`.
pub async fn show_zones(
    State(state): State<AppState>,
    lang: Lang,
    Path(id): Path<i64>,
    Query(params): Query<HashMap<String, String>>,
) -> Response {
    let device = match phone_or_error(&state, id, lang).await {
        Ok(device) => device,
        Err(answer) => return *answer,
    };
    let flash = params.get("zone").map(String::as_str);
    let flash_zone = params.get("zone_id").and_then(|s| s.parse::<i64>().ok());
    let zone = Zone::load(&state.db, lang).await;
    let now = Utc::now();
    let view = async {
        let policy = location_policy(&state, id).await?;
        let zones = phone_zones(&state, id, &policy).await?;
        let newest = newest_fix(&state, id).await?;
        Ok::<_, sqlx::Error>(zones_of(
            &zones,
            &policy,
            newest.as_ref(),
            flash,
            flash_zone,
            &zone,
            now,
        ))
    };
    let zones = match view.await {
        Ok(zones) => zones,
        Err(err) => return db_error(id, err, lang),
    };
    Html(
        ZonesTemplate {
            lang,
            title: t(lang, "locate.zones.title").to_string(),
            who: crate::handlers::devices::BandAvatar::of(&device),
            device,
            zones,
        }
        .render()
        .unwrap(),
    )
    .into_response()
}

/// The Find Phone page for `device`; `erase_refused` = a wrong name was typed into Erase Phone….
async fn render_find_page(
    state: &AppState,
    device: Device,
    cancel: Option<&str>,
    erase_refused: bool,
    lang: Lang,
) -> Result<Html<String>, sqlx::Error> {
    let id = device.id;
    let zone = Zone::load(&state.db, lang).await;
    let now = Utc::now();
    let mut recent = requests(state, id, cancel, &zone, now).await?;
    recent.rows.truncate(FIND_REQUESTS);
    let policy = location_policy(state, id).await?;
    let wipe_not_ready = sqlx::query_scalar::<_, Option<bool>>(
        "SELECT remote_wipe_ready FROM device_status WHERE device_id = ? \
         ORDER BY reported_at DESC, id DESC LIMIT 1",
    )
    .bind(id)
    .fetch_optional(&state.db)
    .await?
    .flatten()
        == Some(false);
    Ok(Html(
        FindTemplate {
            lang,
            title: t(lang, "locate.find.title").to_string(),
            who: crate::handlers::devices::BandAvatar::of(&device),
            device,
            recent,
            wipe_not_ready,
            android_lock: policy.android_lock,
            erase_refused,
        }
        .render()
        .unwrap(),
    ))
}

/// `GET /devices/{id}/find` (design 88): Ring, Stop Ringing and Lock as tiles, the newest
/// requests right under them (`?cancel=` the line after a Cancel), Erase Phone… at the bottom.
pub async fn show_find(
    State(state): State<AppState>,
    lang: Lang,
    Path(id): Path<i64>,
    Query(params): Query<HashMap<String, String>>,
) -> Response {
    let device = match phone_or_error(&state, id, lang).await {
        Ok(device) => device,
        Err(answer) => return *answer,
    };
    let cancel = params.get("cancel").map(String::as_str);
    match render_find_page(&state, device, cancel, false, lang).await {
        Ok(page) => page.into_response(),
        Err(err) => db_error(id, err, lang),
    }
}

/// `GET /devices/{id}/location-settings` (design 88): When to locate, Every and Keep history
/// for, each saving on change and landing back here (`#location-settings`).
pub async fn show_location_settings(
    State(state): State<AppState>,
    lang: Lang,
    Path(id): Path<i64>,
) -> Response {
    let device = match phone_or_error(&state, id, lang).await {
        Ok(device) => device,
        Err(answer) => return *answer,
    };
    let read = async {
        let policy = location_policy(&state, id).await?;
        let zones = phone_zones(&state, id, &policy).await?;
        Ok::<_, sqlx::Error>((policy, zones.served_interval))
    };
    let (policy, served_interval) = match read.await {
        Ok(read) => read,
        Err(err) => return db_error(id, err, lang),
    };
    let interval_note = (served_interval != policy.interval).then(|| {
        t_params(
            lang,
            "locate.settings.interval_note",
            &[("interval", &interval_label(lang, served_interval))],
        )
    });
    Html(
        LocationSettingsTemplate {
            lang,
            title: t(lang, "locate.settings.title").to_string(),
            who: crate::handlers::devices::BandAvatar::of(&device),
            device,
            location_mode: policy.mode,
            location_interval: policy.interval,
            location_retention: policy.retention,
            modes: [
                ("off", t(lang, "common.off")),
                ("on_request", t(lang, "locate.settings.on_request")),
                ("interval", t(lang, "locate.settings.interval")),
            ],
            intervals: crate::time_rules::LOCATION_INTERVALS
                .iter()
                .map(|m| (*m, interval_label(lang, *m)))
                .collect(),
            retention_choices: crate::retention::LOCATION_RETENTION_DAYS
                .iter()
                .map(|d| (*d, t_count(lang, "locate.days", *d, &[])))
                .collect(),
            interval_note,
        }
        .render()
        .unwrap(),
    )
    .into_response()
}

/// The newest fix row's title (design 64 step 2, migration 0093): its exact time, then the phone's
/// provider word and "test location" for a mock fix (only a debug launcher sends one) - "24 Oct
/// 2026, 08:14:05 · gps · test location". Only the title: the row's words stay as they are.
fn fix_title(lang: Lang, exact: String, fix: &DeviceLocation) -> String {
    let mut parts: Vec<String> = Vec::new();
    if !exact.is_empty() {
        parts.push(exact);
    }
    if let Some(provider) = fix.provider.as_deref() {
        parts.push(provider.to_string());
    }
    if fix.mock == Some(true) {
        parts.push(t(lang, "locate.fix.test_location").to_string());
    }
    parts.join(" · ")
}

/// "At Home" as the popup's tail: "10 min ago · within 40 m · at Home".
fn lowercase_first(text: &str) -> String {
    let mut chars = text.chars();
    match chars.next() {
        Some(first) => first.to_lowercase().chain(chars).collect(),
        None => String::new(),
    }
}

/// The phone's newest fix and the map's data for `span`: the popup's words for it ("10 min ago ·
/// within 40 m") and its age.
async fn map_data(
    state: &AppState,
    device_id: i64,
    span: i64,
    zone: &Zone,
    now: DateTime<Utc>,
) -> Result<(Option<DeviceLocation>, MapData), sqlx::Error> {
    let newest = newest_fix(state, device_id).await?;
    let fix = newest.as_ref().map(Fix::seen);
    let newest_age = fix.as_ref().map(|f| f.age(zone, now)).unwrap_or_default();
    let map = MapData {
        device_id,
        shown: newest
            .as_ref()
            .map(|l| l.captured_at.clone())
            .unwrap_or_default(),
        shown_text: fix
            .as_ref()
            .map(|f| format!("{newest_age} · {}", f.accuracy(zone.lang()).to_lowercase()))
            .unwrap_or_default(),
        newest_age,
        span,
    };
    Ok((newest, map))
}

/// How many fixes the phone has in the last `span` hours (0 = all kept).
async fn count_in_span(
    state: &AppState,
    device_id: i64,
    span: i64,
    now: DateTime<Utc>,
) -> Result<i64, sqlx::Error> {
    let start = span_start(span, now);
    sqlx::query_scalar(
        "SELECT COUNT(*) FROM device_locations WHERE device_id = ? \
         AND (?2 IS NULL OR captured_at >= ?2)",
    )
    .bind(device_id)
    .bind(start)
    .fetch_one(&state.db)
    .await
}

pub async fn show_locate(
    State(state): State<AppState>,
    lang: Lang,
    Extension(CurrentAdmin(admin)): Extension<CurrentAdmin>,
    Query(params): Query<HashMap<String, String>>,
) -> Response {
    let selected_id = params.get("device").and_then(|s| s.parse::<i64>().ok());
    // Update Location's `requested=1`; Update All's `requested=all` waits in the Alle view.
    let requested = params.get("requested").map(String::as_str);
    let waiting = requested.is_some_and(|r| r != "all");
    let all_at = params
        .get("at")
        .map(String::as_str)
        .filter(|_| requested == Some("all"));
    let span = span_of(params.get("span"));
    match render_locate_page(
        &state,
        admin.id,
        selected_id,
        Asked {
            waiting_for_fix: waiting,
            span,
            all_at,
        },
        lang,
    )
    .await
    {
        Ok(page) => page.into_response(),
        Err(err) => db_error(selected_id.unwrap_or(0), err, lang),
    }
}

/// `POST /devices/{id}/commands/{command_id}/cancel` (full auth): the "Cancel" of a command
/// the phone hasn't fetched yet (the user's decision, 2026-10-12). One statement, so it races the
/// policy's pop cleanly: whichever comes first wins - a cancelled command is never handed out (the
/// pop skips `cancelled_at`), and one the phone just fetched can't be cancelled (`too_late`, as is
/// an expired one, or another phone's). Logged in the security log. Back to the group it came
/// from, where the line says what happened: `back` = `summary` is the phone's summary page's
/// Activity (`#activity`, design 48 §1.5), anything else Find Phone's requests (`#recent`, design 88).
pub async fn cancel_command(
    State(state): State<AppState>,
    lang: Lang,
    Path((id, command_id)): Path<(i64, i64)>,
    Extension(CurrentAdmin(admin)): Extension<CurrentAdmin>,
    Form(form): Form<HashMap<String, String>>,
) -> impl IntoResponse {
    let outcome = match cancel_waiting(&state, id, command_id, None, &admin.username).await {
        Ok(true) => "done",
        Ok(false) => "too_late",
        Err(err) => {
            tracing::error!(device_id = id, command_id, %err, "can't cancel the command");
            return crate::i18n::error(
                lang,
                StatusCode::INTERNAL_SERVER_ERROR,
                "error.cancel_command",
            );
        }
    };
    let back = match form.get("back").map(String::as_str) {
        Some("summary") => format!("/devices/{id}?cancel={outcome}#activity"),
        _ => format!("/devices/{id}/find?cancel={outcome}#recent"),
    };
    Redirect::to(&back).into_response()
}

/// Cancels a command the phone hasn't fetched (Find Phone's Cancel, Install Now's - design
/// 37 -, which passes `only` = its kind): one statement, so it races the policy's pop cleanly -
/// whichever comes first wins. `Ok(false)` = too late (fetched, expired, cancelled, another
/// phone's, another kind). Logged `device_command_cancelled`.
pub(crate) async fn cancel_waiting(
    state: &AppState,
    device_id: i64,
    command_id: i64,
    only: Option<&str>,
    admin: &str,
) -> Result<bool, sqlx::Error> {
    let cancelled: Option<(String, String)> = sqlx::query_as(
        "UPDATE device_commands SET cancelled_at = datetime('now'), cancel_reason = 'parent' \
         WHERE id = ? AND device_id = ? AND delivered_at IS NULL AND cancelled_at IS NULL \
           AND (? IS NULL OR command = ?) \
           AND (command NOT IN (?, ?, ?, ?) OR requested_at > datetime('now', ?)) \
         RETURNING command, requested_at",
    )
    .bind(command_id)
    .bind(device_id)
    .bind(only)
    .bind(only)
    .bind(crate::models::EXPIRING_COMMANDS[0])
    .bind(crate::models::EXPIRING_COMMANDS[1])
    .bind(crate::models::EXPIRING_COMMANDS[2])
    .bind(crate::models::EXPIRING_COMMANDS[3])
    .bind(format!(
        "-{} minutes",
        crate::models::COMMAND_EXPIRY_MINUTES
    ))
    .fetch_optional(&state.db)
    .await?;
    let Some((command, requested_at)) = cancelled else {
        return Ok(false);
    };
    security::record_security_event(
        &state.db,
        "device_command_cancelled",
        Some(admin),
        None,
        Some({
            // `requested_at` is `datetime('now')` text: an instant, told in the reader's zone.
            let (asked, instant) = match parse_db_utc(&requested_at) {
                Some(at) => (String::new(), Some(at)),
                None => (format!("{requested_at} UTC"), None),
            };
            let detail = security::Detail::words(
                "security.detail.command_cancelled",
                &[
                    ("command", &command),
                    ("asked", &asked),
                    ("device", &device_id.to_string()),
                ],
            );
            match instant {
                Some(at) => detail.at("asked", at),
                None => detail,
            }
            .with_facts(format!("device {device_id}"))
        }),
    )
    .await;
    Ok(true)
}

/// A point of `locations.json`: the stored fix, and `"newest": true` on the newest one when it is
/// older than the span asked for (the trail doesn't join it to the span's points).
#[derive(serde::Serialize)]
struct Point {
    #[serde(flatten)]
    location: DeviceLocation,
    #[serde(skip_serializing_if = "std::ops::Not::not")]
    newest: bool,
}

/// `GET /devices/{id}/locations.json[?since=<hours>]` (no-store): the fixes of the last `since`
/// hours (a positive integer; anything else is everything kept), oldest first, and the newest
/// fix after them when none is that recent - the map's marker never disappears (design 44 §1.6,
/// §1.8).
pub async fn locations_json(
    State(state): State<AppState>,
    lang: Lang,
    Path(id): Path<i64>,
    Query(params): Query<HashMap<String, String>>,
) -> Response {
    let start = params
        .get("since")
        .and_then(|s| s.trim().parse::<i64>().ok())
        .and_then(|hours| span_start(hours, Utc::now()));
    let points = async {
        let mut points: Vec<Point> = sqlx::query_as::<_, DeviceLocation>(
            "SELECT * FROM device_locations WHERE device_id = ? \
             AND (?2 IS NULL OR captured_at >= ?2) ORDER BY captured_at ASC, id ASC",
        )
        .bind(id)
        .bind(&start)
        .fetch_all(&state.db)
        .await?
        .into_iter()
        .map(|location| Point {
            location,
            newest: false,
        })
        .collect();
        if points.is_empty() && start.is_some() {
            let newest = sqlx::query_as::<_, DeviceLocation>(
                "SELECT * FROM device_locations WHERE device_id = ? \
                 ORDER BY captured_at DESC, id DESC LIMIT 1",
            )
            .bind(id)
            .fetch_optional(&state.db)
            .await?;
            points.extend(newest.map(|location| Point {
                location,
                newest: true,
            }));
        }
        Ok::<_, sqlx::Error>(points)
    };
    match points.await {
        Ok(points) => Json(points).into_response(),
        Err(err) => db_error(id, err, lang),
    }
}

#[derive(Template)]
#[template(path = "device_locations.html")]
struct LocationsTemplate {
    lang: Lang,
    title: String,
    device: Device,
    map: MapData,
    span: i64,
    fixes: Vec<FixRow>,
    /// More fixes past this page: "Show older" (`?before=<captured_at>,<id>`, URL-encoded).
    older: Option<String>,
    /// This is an older page: "Newest" leads back.
    paged: bool,
}
crate::i18n::words!(LocationsTemplate);

/// A row of the Locations page: when ("10:58", "yesterday 21:40", "12 Oct 08:12", the exact time
/// in `title`), the accuracy ("± 25 m"), and the popup's words for `static/locate.js`.
struct FixRow {
    latitude: f64,
    longitude: f64,
    when: String,
    exact: String,
    accuracy: String,
    popup: String,
}

/// `before=<captured_at>,<id>`: the page after the row with that key (rows sort by
/// `captured_at DESC, id DESC`; fixes arrive out of order, so the key is both). `None` for the
/// first page or a key that doesn't read. The time is compared as stored text, so any stored
/// text pages on.
fn before_of(raw: Option<&String>) -> Option<(String, i64)> {
    let (at, id) = raw?.trim().rsplit_once(',')?;
    Some((at.to_string(), id.parse().ok()?)).filter(|(at, _)| !at.is_empty())
}

/// `GET /devices/{id}/locations` (no-store: every kept fix; design 44 §1.4): the map for the span
/// (`?span=`, the pick re-opens the page with it), then every fix of the span as a row, newest
/// first, [FIXES_PER_PAGE] at a time. Nothing posts here. 404 for an unknown phone, 500 when a
/// read fails.
pub async fn show_locations(
    State(state): State<AppState>,
    lang: Lang,
    Path(id): Path<i64>,
    Query(params): Query<HashMap<String, String>>,
) -> Response {
    let device = match sqlx::query_as::<_, Device>("SELECT * FROM devices WHERE id = ?")
        .bind(id)
        .fetch_optional(&state.db)
        .await
    {
        Ok(Some(device)) => device,
        Ok(None) => {
            return crate::i18n::error(lang, StatusCode::NOT_FOUND, "error.device_not_found");
        }
        Err(err) => return db_error(id, err, lang),
    };
    let span = span_of(params.get("span"));
    let before = before_of(params.get("before"));
    let zone = Zone::load(&state.db, lang).await;
    let now = Utc::now();
    let page = async {
        let (_, map) = map_data(&state, id, span, &zone, now).await?;
        let (before_at, before_id) = before.clone().unzip();
        let rows = sqlx::query_as::<_, DeviceLocation>(
            "SELECT * FROM device_locations WHERE device_id = ?1 \
             AND (?2 IS NULL OR captured_at >= ?2) \
             AND (?3 IS NULL OR captured_at < ?3 OR (captured_at = ?3 AND id < ?4)) \
             ORDER BY captured_at DESC, id DESC LIMIT ?5",
        )
        .bind(id)
        .bind(span_start(span, now))
        .bind(before_at)
        .bind(before_id)
        .bind(FIXES_PER_PAGE as i64 + 1)
        .fetch_all(&state.db)
        .await?;
        Ok::<_, sqlx::Error>((map, rows))
    };
    let (map, mut rows) = match page.await {
        Ok(page) => page,
        Err(err) => return db_error(id, err, lang),
    };
    let older = (rows.len() > FIXES_PER_PAGE).then(|| {
        rows.truncate(FIXES_PER_PAGE);
        let last = &rows[FIXES_PER_PAGE - 1];
        let key = format!("{},{}", last.captured_at, last.id);
        form_urlencoded::byte_serialize(key.as_bytes()).collect::<String>()
    });
    let fixes = rows
        .iter()
        .map(|l| {
            let at = parse_db_utc(&l.captured_at);
            let when = at.map_or_else(
                || t(lang, "locate.time_unknown").to_string(),
                |at| zone.at(at, now),
            );
            let accuracy = Fix::of(&l.captured_at, l.accuracy_meters)
                .accuracy_m
                .map_or_else(
                    || t(lang, "locate.locations.accuracy_unknown").to_string(),
                    |m| format!("± {m} m"),
                );
            FixRow {
                latitude: l.latitude,
                longitude: l.longitude,
                popup: format!("{when} · {accuracy}"),
                exact: at.map(|at| zone.exact(at)).unwrap_or_default(),
                when,
                accuracy,
            }
        })
        .collect();
    Html(
        LocationsTemplate {
            lang,
            title: t(lang, "locate.locations.title").to_string(),
            device,
            map,
            span,
            fixes,
            older,
            paged: before.is_some(),
        }
        .render()
        .unwrap(),
    )
    .into_response()
}

/// The commands a new one of [command] replaces while they wait (QA of cmd-expiry, Medium 1): only
/// its own kind - a ring or "stop ringing" the earlier ring or stop, "Update Location" the earlier
/// one. A wipe or a lock is never replaced (it waits for a lost phone however long it takes; the
/// parent cancels it with Cancel), and nothing else replaces anything.
fn replaced_kinds(command: &str) -> &'static [&'static str] {
    match command {
        "ring" | "stop_ring" => &["ring", "stop_ring"],
        "locate" => &["locate"],
        _ => &[],
    }
}

/// Queues a Find My Device command for an existing phone (`Ok(false)`: no such phone, nothing
/// queued), in one transaction: a waiting command of the same kind ([replaced_kinds]) is cancelled
/// as "replaced" - kept as history, logged -, and one of that kind the phone fetched but didn't
/// confirm is no longer offered again (security review L12). Broadcasts on `command_notify`
/// afterwards so a connected device's SSE listener (see `device_api::commands_stream`) wakes it
/// immediately; a `send` with no subscribers (device offline/asleep) is expected and harmless.
pub(crate) async fn queue_command(
    state: &AppState,
    device_id: i64,
    command: &str,
    admin: &AdminUser,
) -> Result<bool, sqlx::Error> {
    let mut tx = state.db.begin().await?;
    let exists: Option<i64> = sqlx::query_scalar("SELECT id FROM devices WHERE id = ?")
        .bind(device_id)
        .fetch_optional(&mut *tx)
        .await?;
    if exists.is_none() {
        return Ok(false);
    }
    let kinds = replaced_kinds(command);
    let mut replaced = 0;
    if !kinds.is_empty() {
        let list = vec!["?"; kinds.len()].join(", ");
        let replace_sql = format!(
            "UPDATE device_commands SET cancelled_at = datetime('now'), cancel_reason = 'replaced' \
             WHERE device_id = ? AND delivered_at IS NULL AND cancelled_at IS NULL AND command IN ({list})"
        );
        let mut replace = sqlx::query(&replace_sql).bind(device_id);
        for kind in kinds {
            replace = replace.bind(*kind);
        }
        replaced = replace.execute(&mut *tx).await?.rows_affected();
        let used_up_sql = format!(
            "UPDATE device_commands SET deliveries = MAX(deliveries, ?) WHERE device_id = ? \
             AND delivered_at IS NOT NULL AND received_at IS NULL AND acknowledged_at IS NULL \
             AND command IN ({list})"
        );
        let mut used_up = sqlx::query(&used_up_sql)
            .bind(crate::models::MAX_COMMAND_DELIVERIES)
            .bind(device_id);
        for kind in kinds {
            used_up = used_up.bind(*kind);
        }
        used_up.execute(&mut *tx).await?;
    }
    sqlx::query("INSERT INTO device_commands (device_id, command, requested_by) VALUES (?, ?, ?)")
        .bind(device_id)
        .bind(command)
        .bind(admin.id)
        .execute(&mut *tx)
        .await?;
    tx.commit().await?;
    if replaced > 0 {
        security::record_security_event(
            &state.db,
            "device_command_replaced",
            Some(&admin.username),
            None,
            Some(
                security::Detail::words(
                    "security.detail.command_replaced",
                    &[
                        ("count", &replaced.to_string()),
                        ("replaced", &kinds.join("/")),
                        ("command", command),
                        ("device", &device_id.to_string()),
                    ],
                )
                .with_facts(format!("device {device_id}")),
            ),
        )
        .await;
    }
    let _ = state.command_notify.send(device_id);
    Ok(true)
}

/// Queues `command`, logs it as `event` with `detail`, and returns to the page at `back`; 404 for
/// an unknown phone, 500 (nothing queued) when the database refuses.
async fn send(
    state: &AppState,
    id: i64,
    admin: &AdminUser,
    command: &str,
    (event, detail): (&str, &str),
    back: String,
    lang: Lang,
) -> Response {
    match queue_command(state, id, command, admin).await {
        Ok(true) => {
            security::record_security_event(
                &state.db,
                event,
                Some(&admin.username),
                None,
                Some(security::Detail::facts(detail)),
            )
            .await;
            Redirect::to(&back).into_response()
        }
        Ok(false) => crate::i18n::error(lang, StatusCode::NOT_FOUND, "error.device_not_found"),
        Err(err) => {
            tracing::error!(device_id = id, command, %err, "can't queue the command");
            crate::i18n::error(
                lang,
                StatusCode::INTERNAL_SERVER_ERROR,
                "error.command_send",
            )
        }
    }
}

/// Ring, Stop Ringing, Lock and Erase return to their group on Find Phone (`#actions`, design 88):
/// the requests, right under it, show the new row, with and without JS.
fn back_to_actions(id: i64) -> String {
    format!("/devices/{id}/find#actions")
}

pub async fn ring(
    State(state): State<AppState>,
    lang: Lang,
    Path(id): Path<i64>,
    Extension(CurrentAdmin(admin)): Extension<CurrentAdmin>,
) -> Response {
    let event = ("device_command_queued", "ring");
    send(&state, id, &admin, "ring", event, back_to_actions(id), lang).await
}

pub async fn stop_ring(
    State(state): State<AppState>,
    lang: Lang,
    Path(id): Path<i64>,
    Extension(CurrentAdmin(admin)): Extension<CurrentAdmin>,
) -> Response {
    let event = ("device_command_queued", "stop_ring");
    send(
        &state,
        id,
        &admin,
        "stop_ring",
        event,
        back_to_actions(id),
        lang,
    )
    .await
}

pub async fn lock(
    State(state): State<AppState>,
    lang: Lang,
    Path(id): Path<i64>,
    Extension(CurrentAdmin(admin)): Extension<CurrentAdmin>,
) -> Response {
    let event = ("device_command_queued", "lock");
    send(&state, id, &admin, "lock", event, back_to_actions(id), lang).await
}

/// Update Location: queues `locate`, which makes the phone take a fresh fix (bypassing its own
/// throttle and the location policy's interval) and report it; the page then waits for it, at
/// the map (`#location`).
pub async fn locate(
    State(state): State<AppState>,
    lang: Lang,
    Path(id): Path<i64>,
    Extension(CurrentAdmin(admin)): Extension<CurrentAdmin>,
) -> Response {
    let back = format!("/devices/locate?device={id}&requested=1#location");
    let event = ("device_command_queued", "locate");
    send(&state, id, &admin, "locate", event, back, lang).await
}

/// `GET /devices/locate/phones.json[?at=<instant>]` (no-store: every phone's last position;
/// design 85 §4.6): the Alle view's rows in the page's order, every word the server's, in the
/// reader's language (`?lang=`). With `at` (Update All's press), each phone asked since then also
/// says whether it answered. A failed read is a 500.
pub async fn phones_json(
    State(state): State<AppState>,
    lang: Lang,
    Query(params): Query<HashMap<String, String>>,
) -> Response {
    let wait_at = params.get("at").and_then(|at| parse_db_utc(at));
    let zone = Zone::load(&state.db, lang).await;
    let now = Utc::now();
    let rows = async {
        let devices = sqlx::query_as::<_, Device>("SELECT * FROM devices ORDER BY name, id")
            .fetch_all(&state.db)
            .await?;
        all_phones(&state, &devices, &zone, now, wait_at).await
    };
    match rows.await {
        Ok(rows) => Json(rows).into_response(),
        Err(err) => {
            tracing::error!(%err, "can't read the phones' positions");
            crate::i18n::error(lang, StatusCode::INTERNAL_SERVER_ERROR, "error.read_phone")
        }
    }
}

/// `POST /devices/locate/all` (design 85 §4.6): Update Location for every phone that can answer -
/// set up, its access not removed, location not Off ([skip_reason], O3) - each queued and logged
/// as the one-phone Update Location is (a waiting one replaced). A command, not a settings save
/// (`settings_log::NOT_SAVES`). Back to the map, `#all`, where the wait shows: `at` is the press's
/// second, so every command queued here counts as at or after it.
pub async fn locate_all(
    State(state): State<AppState>,
    lang: Lang,
    Extension(CurrentAdmin(admin)): Extension<CurrentAdmin>,
) -> Response {
    let now = Utc::now();
    let at = DateTime::from_timestamp(now.timestamp(), 0).unwrap_or(now);
    let asked = async {
        let devices = sqlx::query_as::<_, Device>("SELECT * FROM devices ORDER BY name, id")
            .fetch_all(&state.db)
            .await?;
        for device in &devices {
            let mode = location_mode(&state, device.id).await?;
            if skip_reason(&state, device, &mode, now).is_some()
                || !queue_command(&state, device.id, "locate", &admin).await?
            {
                continue;
            }
            security::record_security_event(
                &state.db,
                "device_command_queued",
                Some(&admin.username),
                None,
                Some(security::Detail::facts("locate")),
            )
            .await;
        }
        Ok::<_, sqlx::Error>(())
    };
    match asked.await {
        Ok(()) => Redirect::to(&format!(
            "/devices/locate?requested=all&at={}#all",
            at.format("%Y-%m-%dT%H:%M:%SZ")
        ))
        .into_response(),
        Err(err) => {
            tracing::error!(%err, "can't queue Update All");
            crate::i18n::error(
                lang,
                StatusCode::INTERNAL_SERVER_ERROR,
                "error.command_send",
            )
        }
    }
}

/// How long this phone's location history is kept (`POST /devices/{id}/location-retention`,
/// `days` = one of `retention::LOCATION_RETENTION_DAYS`; cleanup 2026-10-06). Older fixes are
/// deleted at once - the newest one stays while location is on - and by the hourly pruning from
/// then on.
/// 400 for another value, 404 for an unknown device; nothing is sent to the phone. Back to
/// the Location Settings page (`#location-settings`, design 88), so a `<noscript>` Save doesn't
/// land at the top (qa-31-s13-code M1).
pub async fn update_location_retention(
    State(state): State<AppState>,
    lang: Lang,
    Extension(CurrentAdmin(admin)): Extension<CurrentAdmin>,
    Path(id): Path<i64>,
    Form(form): Form<HashMap<String, String>>,
) -> Response {
    let Some(days) = form
        .get("days")
        .and_then(|d| d.trim().parse::<i64>().ok())
        .filter(|d| crate::retention::LOCATION_RETENTION_DAYS.contains(d))
    else {
        return crate::i18n::error(lang, StatusCode::BAD_REQUEST, "error.unknown_retention");
    };
    let result = sqlx::query(
        "UPDATE device_policy SET location_retention_days = ?, updated_at = datetime('now') \
         WHERE device_id = ?",
    )
    .bind(days)
    .bind(id)
    .execute(&state.db)
    .await;
    match result {
        Ok(done) if done.rows_affected() == 0 => {
            crate::i18n::error(lang, StatusCode::NOT_FOUND, "error.device_not_found")
        }
        Ok(_) => {
            security::record_security_event(
                &state.db,
                "location_retention_changed",
                Some(&admin.username),
                None,
                Some(
                    security::Detail::words(
                        "security.detail.location_retention",
                        &[("device", &id.to_string()), ("days", &days.to_string())],
                    )
                    .with_facts(format!("device {id}")),
                ),
            )
            .await;
            // A failing table is logged by the pass itself (security review L9).
            crate::retention::prune(&state.db).await;
            Redirect::to(&format!(
                "/devices/{id}/location-settings#location-settings"
            ))
            .into_response()
        }
        Err(err) => {
            tracing::error!(device_id = id, %err, "failed to save the location retention");
            crate::i18n::error(
                lang,
                StatusCode::INTERNAL_SERVER_ERROR,
                "error.save_failed_log",
            )
        }
    }
}

/// The location policy: `mode` off / on_request / interval, `interval_minutes` from
/// `time_rules::LOCATION_INTERVALS` (kept as is when absent: the page sends it only in
/// "Regularly"). 400 on an unknown value, 404 for an unknown device; nudges the phone. Off erases
/// the phone's every fix and shutdown position at once (the pruning pass; security review P1, the
/// owner's decision on M1 of round S3) - the hourly pass again if this one fails. Back to
/// the Location Settings page (`#location-settings`), as the history - or to the Zones page
/// (`#zones`) for the zones' Locate Regularly (`back=zones`, design 64 §3.6, design 88; anything
/// else is the settings).
pub async fn update_location_policy(
    State(state): State<AppState>,
    lang: Lang,
    Extension(CurrentAdmin(admin)): Extension<CurrentAdmin>,
    Path(id): Path<i64>,
    Form(form): Form<HashMap<String, String>>,
) -> Response {
    let mode = form.get("mode").map(String::as_str).unwrap_or("");
    let interval = form
        .get("interval_minutes")
        .and_then(|m| m.trim().parse::<i64>().ok());
    if !crate::time_rules::LOCATION_MODES.contains(&mode)
        || interval.is_some_and(|m| !crate::time_rules::LOCATION_INTERVALS.contains(&m))
    {
        return crate::i18n::error(lang, StatusCode::BAD_REQUEST, "error.unknown_location_mode");
    }
    // The mode before (design 75 D13): a change to Off erases the weather's caches too.
    let before: Option<String> =
        match sqlx::query_scalar("SELECT location_mode FROM device_policy WHERE device_id = ?")
            .bind(id)
            .fetch_optional(&state.db)
            .await
        {
            Ok(before) => before,
            Err(err) => {
                tracing::error!(device_id = id, %err, "failed to read the location policy");
                return crate::i18n::error(
                    lang,
                    StatusCode::INTERNAL_SERVER_ERROR,
                    "error.save_failed_log",
                );
            }
        };
    let result = sqlx::query(
        "UPDATE device_policy SET location_mode = ?, \
         location_interval_minutes = COALESCE(?, location_interval_minutes), \
         updated_at = datetime('now') WHERE device_id = ?",
    )
    .bind(mode)
    .bind(interval)
    .bind(id)
    .execute(&state.db)
    .await;
    match result {
        Ok(done) if done.rows_affected() == 0 => {
            crate::i18n::error(lang, StatusCode::NOT_FOUND, "error.device_not_found")
        }
        Ok(_) => {
            security::record_security_event(
                &state.db,
                "location_policy_changed",
                Some(&admin.username),
                None,
                Some(
                    match interval {
                        Some(minutes) => security::Detail::words(
                            "security.detail.location_policy_interval",
                            &[
                                ("device", &id.to_string()),
                                ("mode", mode),
                                ("minutes", &minutes.to_string()),
                            ],
                        ),
                        None => security::Detail::words(
                            "security.detail.location_policy",
                            &[("device", &id.to_string()), ("mode", mode)],
                        ),
                    }
                    .with_facts(format!("device {id}")),
                ),
            )
            .await;
            if mode == "off" {
                // A failing table is logged by the pass itself (security review L9).
                crate::retention::prune(&state.db).await;
                // Design 75 D13: the weather's cells are a coarse trail of where the family has
                // been - they go at the change to Off, whole (they carry no device id). Never in the prune.
                if before.as_deref() != Some("off")
                    && let Err(err) = crate::weather_cache::erase(&state).await
                {
                    tracing::error!(device_id = id, %err, "couldn't erase the weather's caches");
                }
            }
            let _ = state.command_notify.send(id);
            let back = match form.get("back").map(String::as_str) {
                Some("zones") => format!("/devices/{id}/zones#zones"),
                _ => format!("/devices/{id}/location-settings#location-settings"),
            };
            Redirect::to(&back).into_response()
        }
        Err(err) => {
            tracing::error!(device_id = id, %err, "failed to save the location policy");
            crate::i18n::error(
                lang,
                StatusCode::INTERNAL_SERVER_ERROR,
                "error.save_failed_log",
            )
        }
    }
}

/// Erase Phone…: gated the same way backup restore/delete are (`templates/backups.html`) - the
/// parent types the phone's exact name before anything is queued. An erase is irreversible and a
/// phone that erases never answers, so this confirmation is the only safety net there is. A name
/// that doesn't match renders Find Phone with the confirm open and the refusal in it (the form's
/// action ends in `#erase`, so the answer lands there); 404 for an unknown phone.
pub async fn wipe(
    State(state): State<AppState>,
    lang: Lang,
    Path(id): Path<i64>,
    Extension(CurrentAdmin(admin)): Extension<CurrentAdmin>,
    Form(form): Form<HashMap<String, String>>,
) -> Response {
    let device = match phone_or_error(&state, id, lang).await {
        Ok(device) => device,
        Err(answer) => return *answer,
    };

    let confirm = form.get("confirm_name").map(|s| s.trim()).unwrap_or("");
    if confirm != device.name {
        return match render_find_page(&state, device, None, true, lang).await {
            Ok(page) => page.into_response(),
            Err(err) => db_error(id, err, lang),
        };
    }
    let event = ("device_wipe_queued", device.name.as_str());
    send(&state, id, &admin, "wipe", event, back_to_actions(id), lang).await
}

#[cfg(test)]
mod tests {
    /// 2026-10-25: the line holds while the newest fix is a shutdown's and the phone hasn't
    /// reported since (a minute's slack for a report racing the shutdown).
    #[test]
    fn the_last_position_before_the_phone_went_off() {
        let off = crate::display_time::parse_db_utc("2026-10-25 21:14:00").unwrap();
        let now = off + chrono::Duration::minutes(10);
        let at = |secs: i64| Some(off + chrono::Duration::seconds(secs));
        let line = |report, event| super::before_off(Some(off), report, event, now);
        let told = Some(("Anna was turned off", None, off));
        assert!(line(None, told));
        assert!(line(at(-600), told), "a report before it");
        assert!(line(at(60), told), "racing it");
        assert!(!line(at(61), told), "on again");
        assert!(
            !super::before_off(None, None, told, now),
            "not a shutdown's fix"
        );
        // Q-5: as the device page's "Off since" - not for a known restart (an older row's stored
        // words, a newer row's parameters - design 70 §4), not while held.
        assert!(
            !line(None, Some(("Anna is restarting", None, off))),
            "a known restart"
        );
        assert!(
            !line(None, Some(("", Some(r#"{"reason":"restart"}"#), off))),
            "a known restart, from the parameters"
        );
        assert!(line(None, Some(("", Some(r#"{"reason":"user"}"#), off))));
        let held = off + crate::parent_events::OFF_HOLD - chrono::Duration::seconds(1);
        assert!(
            !super::before_off(Some(off), None, told, held),
            "the push still held"
        );
        assert!(super::before_off(
            Some(off),
            None,
            told,
            off + crate::parent_events::OFF_HOLD
        ));
        assert!(line(None, None), "the event gone with the inbox's days");
        // The phone's clock 3 min behind, its last report 30 s after the off arrived: still off;
        // a report a minute after the arrival is on again.
        let arrived = off + chrono::Duration::minutes(3);
        let behind = Some(("Anna was turned off", None, arrived));
        assert!(line(at(180 + 30), behind), "a clock behind");
        assert!(!line(at(180 + 61), behind), "on again");
        // A late off, told with the next start an hour on: a report 30 s after that boot is the
        // phone on again, not a report racing the shutdown.
        let boot = off + chrono::Duration::hours(1);
        let late = Some(("Anna was turned off", None, boot));
        let after_boot = super::before_off(
            Some(off),
            Some(boot + chrono::Duration::seconds(30)),
            late,
            boot + chrono::Duration::minutes(10),
        );
        assert!(!after_boot, "a late off");
    }

    use super::*;

    fn utc(text: &str) -> DateTime<Utc> {
        parse_db_utc(text).unwrap()
    }

    #[test]
    fn a_fix_reads_as_age_and_accuracy() {
        let oslo = Zone::from_setting("Europe/Oslo");
        let now = utc("2026-10-13 10:00:00");
        let fix = Fix::of("2026-10-13T09:50:00.000Z", Some(39.6));
        assert_eq!(fix.age(&oslo, now), "10 min ago");
        assert_eq!(fix.accuracy(Lang::En), "Within 40 m");
        // A day old: the date, in local time; no accuracy.
        let old = Fix::of("2026-10-10T22:30:00Z", None);
        assert_eq!(old.age(&oslo, now), "11 Oct");
        assert_eq!(old.accuracy(Lang::En), "Accuracy unknown");
        // Yesterday evening, local (21:40 UTC is 23:40 in Oslo).
        let late = Fix::of("2026-10-12T21:40:00Z", Some(5.0));
        assert_eq!(late.age(&oslo, now), "yesterday 23:40");
        // The fallback zone says UTC.
        assert_eq!(late.age(&Zone::utc_fallback(), now), "yesterday 21:40 UTC");
        // A time that doesn't read, a nonsense accuracy.
        let bad = Fix::of("z", Some(f64::NAN));
        assert_eq!(bad.age(&oslo, now), "Time unknown");
        assert_eq!(bad.accuracy(Lang::En), "Accuracy unknown");
    }

    /// 2026-10-25 (PLAN "Locate: the \"Answer to Update Location\" row goes"): asked → "Asking the
    /// phone…"; found → 10 minutes; a failure → until a fix taken after it; else no row.
    #[test]
    fn the_answer_row_comes_and_goes() {
        let oslo = Zone::from_setting("Europe/Oslo");
        let now = utc("2026-10-25 12:29:00");
        let command = |result: Option<&str>, answered: Option<&str>| DeviceCommand {
            id: 1,
            command: "locate".to_string(),
            requested_at: "2026-10-25 12:28:00".to_string(),
            delivered_at: None,
            acknowledged_at: answered.map(str::to_string),
            result: result.map(str::to_string),
            received_at: None,
            deliveries: 0,
            first_delivered_at: None,
            succeeded: None,
            cancelled_at: None,
            cancel_reason: None,
            requested_by: None,
        };
        let state = |cmd: &DeviceCommand, status: CommandStatus, fix: Option<&str>, at: &str| {
            answer_state(Lang::En, cmd, &status, fix.map(utc), utc(at))
        };
        let words = |state: Option<AnswerState>| {
            state
                .and_then(|s| answer_view(s, false, &oslo, now))
                .map(|v| (v.state, v.answer.text, v.when))
        };

        // Being asked: waiting, sent, received in time.
        let asked = command(None, None);
        for status in [
            CommandStatus::Waiting { expires: None },
            CommandStatus::Sent {
                at: None,
                unconfirmed: Unconfirmed::NotYet,
            },
            CommandStatus::Received {
                at: None,
                overdue: false,
            },
        ] {
            assert_eq!(
                state(&asked, status, None, "2026-10-25 12:29:00"),
                Some(AnswerState::Asking)
            );
        }
        assert_eq!(
            words(Some(AnswerState::Asking)),
            Some(("asking", "Asking the phone…".to_string(), String::new()))
        );
        // Right after Update Location the wait's own line says it.
        assert!(answer_view(AnswerState::Asking, true, &oslo, now).is_none());

        // Found: 13:29 local (CET), for 10 minutes after the answer; never "Answer to".
        let found = command(Some("fix ±5 m, 3 s old"), Some("2026-10-25 12:29:00"));
        let done = || CommandStatus::Done { at: None };
        assert_eq!(
            words(state(
                &found,
                done(),
                Some("2026-10-25 12:28:57"),
                "2026-10-25 12:29:00"
            )),
            Some(("found", "Found within 5 m".to_string(), "13:29".to_string()))
        );
        let its_fix = Some("2026-10-25 12:28:57");
        assert!(state(&found, done(), its_fix, "2026-10-25 12:38:59").is_some());
        assert_eq!(state(&found, done(), its_fix, "2026-10-25 12:39:00"), None);
        // Q-8: only while the newest fix is the one it found - not with none, not with one older
        // than the ask (its upload lagged: "40 min ago" over "Found within 5 m"), and not once a
        // fix newer than the answer came (the fix row says it; the user's case, a 14:29 answer
        // under a point from 20:41, too).
        assert_eq!(state(&found, done(), None, "2026-10-25 12:30:00"), None);
        let older = Some("2026-10-25 11:49:00");
        assert_eq!(state(&found, done(), older, "2026-10-25 12:30:00"), None);
        let newer = Some("2026-10-25 12:29:30");
        assert_eq!(state(&found, done(), newer, "2026-10-25 12:30:00"), None);
        // An older launcher's plain "ok" counts as found.
        let ok = command(Some("ok"), Some("2026-10-25 12:29:00"));
        assert_eq!(
            words(state(&ok, done(), its_fix, "2026-10-25 12:30:00")).map(|w| w.1),
            Some("Found".to_string())
        );

        // A failure stays until a fix taken after it, whatever its age.
        let off = command(
            Some("location is off for this device"),
            Some("2026-10-25 12:29:00"),
        );
        for status in [CommandStatus::Failed { at: None }, done()] {
            let later = "2026-10-26 09:00:00";
            assert_eq!(
                words(state(&off, status, Some("2026-10-25 12:00:00"), later)),
                Some((
                    "failed",
                    "Location is off on the phone".to_string(),
                    "13:29".to_string()
                ))
            );
        }
        let status = CommandStatus::Failed { at: None };
        assert_eq!(
            state(
                &off,
                status,
                Some("2026-10-25 12:30:00"),
                "2026-10-25 12:31:00"
            ),
            None,
            "a fix after it"
        );
        let silent = command(None, Some("2026-10-25 12:29:00"));
        assert_eq!(
            words(state(
                &silent,
                CommandStatus::Failed { at: None },
                None,
                "2026-10-25 13:00:00"
            ))
            .map(|w| w.1),
            Some("The phone couldn't find its location".to_string())
        );

        // Never fetched within its hour: the phone is offline - until a fix after the ask.
        assert_eq!(
            words(state(
                &asked,
                CommandStatus::Expired,
                None,
                "2026-10-25 14:00:00"
            )),
            Some((
                "failed",
                "No position - the phone is offline".to_string(),
                "13:28".to_string()
            ))
        );
        assert_eq!(
            state(
                &asked,
                CommandStatus::Expired,
                Some("2026-10-25 12:40:00"),
                "2026-10-25 14:00:00"
            ),
            None
        );
        for status in [
            CommandStatus::Received {
                at: None,
                overdue: true,
            },
            CommandStatus::Sent {
                at: None,
                unconfirmed: Unconfirmed::Lost,
            },
        ] {
            assert_eq!(
                words(state(&asked, status, None, "2026-10-25 14:00:00")).map(|w| w.1),
                Some("No position - the phone didn't answer".to_string())
            );
        }

        // Cancelled: no row.
        let status = CommandStatus::Cancelled {
            at: None,
            by: CancelledBy::Parent,
        };
        assert_eq!(state(&asked, status, None, "2026-10-25 12:30:00"), None);
    }

    #[test]
    fn the_phones_answers_are_reworded() {
        for (raw, text) in [
            ("ringing", "Ringing"),
            ("stopped", "Stopped ringing"),
            ("locked (handy lock)", "Locked with the Child PIN"),
            ("locked (Android)", "Screen off (no Child PIN set)"),
            // Design 59: Android's lock, and set_kid_pin's answers in Activity.
            (
                "locked (android pin)",
                "Locked with the Child PIN (fingerprint off until it's typed)",
            ),
            ("pin set", "Child PIN set on the phone"),
            (
                "refused: no_token",
                "Refused: Vibb One can't change Android's PIN yet",
            ),
            ("failed to lock", "The phone couldn't lock"),
            ("paused for 2 hours", "Unlocked for 2 hours"),
            ("allowed: 1 apps", "1 app allowed"),
            ("allowed: 3 apps", "3 apps allowed"),
            ("ended: pause, install mode", "Locked again"),
            ("nothing was open", "Nothing was unlocked"),
            (
                "location is off for this device",
                "Location is off on the phone",
            ),
            ("no location fix", "The phone couldn't find its location"),
            ("fix ±12 m, 3 s old", "Found within 12 m"),
            ("fix, 40 s old", "Found"),
            ("wiping (the system shut down during the call)", "Erasing"),
            (
                "wipe failed, the phone keeps its data: SecurityException",
                "Not erased: the phone keeps its data",
            ),
            (
                "unknown command - update the launcher",
                "Vibb One on the phone is too old for this",
            ),
            // Install Now (design 37 §2.4).
            ("installed: 1.4.0", "Installed 1.4.0"),
            ("already running: 1.4.0", "Already on 1.4.0"),
            ("not offered", "No update for this phone"),
            ("refused: wrong_signer", "Refused: signed with another key"),
            ("refused: not_ours", "Refused: not Vibb One"),
            (
                "refused: downgrade",
                "Refused: older than the phone's version",
            ),
            ("refused: unparseable", "Refused: not a readable app"),
            (
                "refused: release",
                "Refused: this release can't be installed",
            ),
            ("download failed", "The download kept failing"),
            ("download corrupt", "The download didn't match the release"),
            ("no space", "Not enough space on the phone"),
            ("install failed: 4", "Android refused the install"),
            ("install failed: start", "Android refused the install"),
            ("superseded", "Ended: a newer Install Now took over"),
            ("call never ended", "Not installed: a call stayed on"),
        ] {
            assert_eq!(
                answer(Lang::En, raw),
                Some(Answer {
                    text: text.to_string(),
                    raw: Some(raw.to_string())
                }),
                "{raw}"
            );
        }
        // An answer this server doesn't know is shown as sent; an empty one is none.
        assert_eq!(
            answer(Lang::En, " something new "),
            Some(Answer {
                text: "something new".to_string(),
                raw: None
            })
        );
        assert_eq!(answer(Lang::En, "  "), None);
    }

    #[test]
    fn every_status_has_a_word_and_local_times() {
        let oslo = Zone::from_setting("Europe/Oslo");
        let now = utc("2026-10-13 10:00:00");
        let at = Some(utc("2026-10-13 09:58:00"));
        let words = |status| status_words(&status, &oslo, now);
        assert_eq!(words(CommandStatus::Done { at }), ("Done", "green", None));
        assert_eq!(words(CommandStatus::Failed { at }).0, "Failed");
        let (word, _, line) = words(CommandStatus::Waiting {
            expires: Some(utc("2026-10-13 10:30:00")),
        });
        assert_eq!(word, "Waiting");
        assert_eq!(
            line.as_deref(),
            Some("Expires 12:30 if the phone isn't back.")
        );
        let (_, _, line) = words(CommandStatus::Received { at, overdue: true });
        assert_eq!(
            line.as_deref(),
            Some("The phone got it (11:58) but never said it finished.")
        );
        let (word, tone, _) = words(CommandStatus::Sent {
            at,
            unconfirmed: Unconfirmed::Lost,
        });
        assert_eq!((word, tone), ("Not confirmed", "red"));
        for by in [
            CancelledBy::Parent,
            CancelledBy::Restart,
            CancelledBy::Enrolled,
            CancelledBy::Replaced,
        ] {
            let (_, _, line) = words(CommandStatus::Cancelled { at, by });
            assert!(line.unwrap().starts_with("Never sent"), "{by:?}");
        }
        // No line mentions UTC, a raw id or the words §4.2 keeps out.
        for status in [
            CommandStatus::Expired,
            CommandStatus::Erasing { at },
            CommandStatus::Received { at, overdue: false },
            CommandStatus::Waiting { expires: None },
            CommandStatus::Sent {
                at,
                unconfirmed: Unconfirmed::CantConfirm,
            },
            CommandStatus::Sent {
                at,
                unconfirmed: Unconfirmed::OfferedAgain,
            },
        ] {
            let (word, _, line) = words(status);
            let text = format!("{word} {}", line.unwrap_or_default());
            for hidden in ["UTC", "sync", "check in", "launcher", "deliver"] {
                assert!(!text.contains(hidden), "{text}");
            }
        }
    }

    /// The states the page test can't set up at age: (status, shown anyway, shown only lately).
    #[test]
    fn requests_take_in_flight_at_any_age_and_failures_for_a_day() {
        let sent = |unconfirmed| CommandStatus::Sent {
            at: None,
            unconfirmed,
        };
        for (status, always) in [
            (sent(Unconfirmed::NotYet), true),
            (sent(Unconfirmed::OfferedAgain), true),
            (sent(Unconfirmed::CantConfirm), true),
            (CommandStatus::Erasing { at: None }, true),
            (
                CommandStatus::Received {
                    at: None,
                    overdue: false,
                },
                true,
            ),
            (
                CommandStatus::Received {
                    at: None,
                    overdue: true,
                },
                false,
            ),
            (sent(Unconfirmed::Lost), false),
        ] {
            assert!(in_requests(&status, true, false), "{status:?}");
            assert_eq!(in_requests(&status, false, false), always, "{status:?}");
        }
        let replaced = CommandStatus::Cancelled {
            at: None,
            by: CancelledBy::Replaced,
        };
        assert!(
            in_requests(&replaced, false, true),
            "the newest of its kind"
        );
        assert!(!in_requests(&replaced, true, false));
    }

    #[test]
    fn commands_have_labels_not_ids() {
        for (command, label) in [
            ("ring", "Ring"),
            ("stop_ring", "Stop Ringing"),
            ("lock", "Lock"),
            ("locate", "Update Location"),
            ("wipe", "Erase Phone"),
            ("end_windows", "Lock again"),
            ("start_pause", "Unlock for 2 h"),
            ("allow_blocked", "Allow these apps"),
            ("install_update", "Install Now"),
            ("made_up", "Other request"),
        ] {
            assert_eq!(t(Lang::En, command_label(command)), label);
        }
    }

    /// The Locate tab in Norwegian (design 70 §5.1, slice 3c): the Rust-built words follow the
    /// reader's language - the answers, a status and its line, the Locations row, the intervals.
    #[test]
    fn the_words_follow_the_readers_language() {
        let oslo = Zone::from_setting("Europe/Oslo").in_lang(Lang::Nb);
        let now = utc("2026-10-13 10:00:00");
        assert_eq!(
            answer(Lang::Nb, "fix ±12 m, 3 s old").map(|a| a.text),
            Some("Funnet innenfor 12 m".to_string())
        );
        assert_eq!(
            answer(Lang::Nb, "allowed: 3 apps").map(|a| a.text),
            Some("3 apper tillatt".to_string())
        );
        let (word, _, line) = status_words(
            &CommandStatus::Received {
                at: Some(utc("2026-10-13 09:58:00")),
                overdue: false,
            },
            &oslo,
            now,
        );
        assert_eq!(word, "Mottatt");
        assert_eq!(line.as_deref(), Some("Telefonen fikk den (11:58)."));
        assert_eq!(locations_value(Lang::Nb, 0, 12, None), "Ingen ennå");
        assert_eq!(
            locations_value(Lang::Nb, 14, 168, None),
            "14 de siste 7 dagene"
        );
        assert_eq!(
            locations_value(Lang::En, 0, 24, Some("3 days ago")),
            "0 in the last 24 hours · newest 3 days ago"
        );
        assert_eq!(locations_value(Lang::En, 3, 0, None), "3 kept");
        assert_eq!(interval_label(Lang::En, 60), "1 hour");
        assert_eq!(interval_label(Lang::En, 240), "4 hours");
        assert_eq!(interval_label(Lang::Nb, 120), "2 timer");
        assert_eq!(interval_label(Lang::Nb, 30), "30 min");
        assert_eq!(
            cancel_flash(Lang::Nb, Some("done")),
            Some(("Avbrutt. Telefonen får den aldri.", false))
        );
        assert_eq!(t(Lang::Nb, command_label("locate")), "Oppdater posisjon");
        // The watch's motion line reads with every interval (qa light 3c, 3).
        for (minutes, tail) in [
            (30, "hver 30 min."),
            (60, "hver 1 time."),
            (120, "hver 2 timer."),
        ] {
            let line = t_params(
                Lang::Nb,
                "locate.watch.no_motion",
                &[("interval", &interval_label(Lang::Nb, minutes))],
            );
            assert!(line.ends_with(tail), "{line}");
        }
    }
}
