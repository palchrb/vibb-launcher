//! The Locate tab (`/devices/locate`, `templates/device_locate.html`; design 31 §3.6, step 13): a
//! phone switcher, the map (Leaflet against public OSM tiles, design 33), the last fix as age and
//! accuracy, Update Location, Ring, Stop Ringing, Lock and Erase Phone…, the location settings and
//! the requests in flight. Commands are queued into `device_commands`, the phone is nudged over its
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
use crate::models::{
    AdminUser, CancelledBy, CommandStatus, Device, DeviceCommand, DeviceLocation, Unconfirmed,
    command_label,
};
use crate::security::{self, CurrentAdmin};

/// The commands the Locate page sends, the only ones its Requests lists (design 44 §1.1).
pub(crate) const LOCATE_KINDS: &[&str] = &["locate", "ring", "stop_ring", "lock", "wipe"];

/// How long a request that went wrong stays in Requests (design 48 §1.4).
const REQUESTS_HOURS: i64 = 24;

/// The trail's spans in hours (0 = everything kept) and their words, the first the default
/// (design 44 §1.5). The browser remembers the pick (`static/locate.js`).
pub(crate) const SPANS: [(i64, &str); 4] = [
    (12, "12 hours"),
    (24, "24 hours"),
    (168, "7 days"),
    (0, "All kept"),
];

/// How many fixes the Locations page lists at a time.
const FIXES_PER_PAGE: usize = 200;

#[derive(Template)]
#[template(path = "device_locate.html")]
struct LocateTemplate {
    title: String,
    /// Every phone: the switcher above the chosen one, or the list when none is chosen.
    phones: Vec<PhoneLink>,
    selected: Option<Locate>,
    bell: crate::parent_events::Bell,
}

/// A phone in the switcher (or the list): its state as a dot and, for screen readers, in its
/// `aria-label` ("Anna, online", §5.4).
struct PhoneLink {
    id: i64,
    name: String,
    initial: String,
    avatar: &'static str,
    connection: crate::streams::ConnectionState,
    /// "Anna, online".
    aria: String,
    /// "seen 3 min ago", "access removed" (the list's line), and the exact last contact.
    seen: Option<String>,
    seen_exact: Option<String>,
    current: bool,
}

/// The chosen phone's part of the page.
struct Locate {
    device: Device,
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
    /// The phone's last report said it can't erase itself yet (QA of the launcher wave, Low 4).
    wipe_not_ready: bool,
    /// Design 59 §2.3: Android's lock is chosen - after a restart the actions wait for the PIN.
    android_lock: bool,
    /// The name typed into Erase Phone… didn't match: the confirm is open with the refusal.
    erase_refused: bool,
    location_mode: String,
    location_interval: i64,
    location_retention: i64,
    modes: [(&'static str, &'static str); 3],
    intervals: Vec<(i64, String)>,
    retention_choices: Vec<(i64, String)>,
    recent: Recent,
    /// Design 64: the phone's zones (`#zones`).
    zones: ZonesView,
    /// Design 64 step 2 (the user's decision 3): under "Every", while the phone has zones and
    /// watches them - "30 min while the phone has zones" (the served interval, never written).
    interval_note: Option<String>,
    /// Design 64 step 2 (decision 14): "3 made-up locations ignored" - the phone's running count
    /// of mock fixes a release build ignored; `None` at 0 or without a watch.
    mock_ignored: Option<String>,
}

/// The Locate page's `#zones` (design 64 §3.5, §3.6): a row per zone, Add Zone, the line after a
/// save or a delete, what the location settings mean for the zones.
struct ZonesView {
    rows: Vec<ZoneRow>,
    /// 10 zones: Add Zone is replaced by a note.
    full: bool,
    /// After a delete (`?zone=deleted`): the group's line.
    flash: Option<&'static str>,
    /// Only when asked, with zones: the orange row and Locate Regularly.
    needs_regularly: bool,
    /// "Keep location on" is off, with zones: the kid can turn Location off.
    location_unlocked: bool,
    /// The footer under the group.
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
fn fix_badge(zones: &[crate::zones::Zone], fix: &DeviceLocation) -> Option<String> {
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
    (!inside.is_empty()).then(|| format!("At {}", inside.join(" · ")))
}

/// What a zone's row says it notifies about.
fn notify_words(arrive: bool, leave: bool) -> &'static str {
    match (arrive, leave) {
        (true, true) => "arrive and leave",
        (true, false) => "arrive",
        (false, true) => "leave",
        (false, false) => "no notifications",
    }
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
    // "Here now" counts from the newest fix's last still re-send too (Q-3): `seen`.
    let fresh = crate::zones::fresh(seen, mode, interval, now);
    let row_flash = match flash {
        Some("added") => Some("Added."),
        Some("saved") => Some("Saved."),
        _ => None,
    };
    let rows = zones
        .iter()
        .map(|z| ZoneRow {
            id: z.id,
            name: z.name.clone(),
            sub: format!(
                "{} m · {}",
                z.radius_m,
                notify_words(z.notify_arrive, z.notify_leave)
            ),
            presence: match newest {
                _ if z.here_now(fresh) => Presence::Here,
                Some(at) if z.here_now(true) => {
                    Presence::LastSeen(format!("Last seen at {} {}", z.name, zone.at(at, now)))
                }
                _ => Presence::None,
            },
            flash: row_flash.filter(|_| flash_zone == Some(z.id)),
        })
        .collect::<Vec<_>>();
    let some = !rows.is_empty();
    // Design 64 step 2 (§3.6's last rows): a phone whose watch holds fences sends a location as
    // soon as it notices a crossing, so the "two intervals" timing is an older launcher's.
    let watched = mode == "interval" && crate::zones::watching(watch);
    let footer = match mode {
        "off" => "Location is off for this phone, so the zones see nothing.",
        "on_request" => "The zones are judged on the locations Update Location brings.",
        _ if watched => {
            "The phone watches the zones itself and sends a location as soon as it notices a \
             crossing. Arrivals and departures show after two locations on the same side."
        }
        _ => "Arrivals and departures show after two locations on the same side.",
    };
    ZonesView {
        full: rows.len() as i64 >= crate::zones::MAX_ZONES,
        flash: (flash == Some("deleted")).then_some("Zone deleted."),
        needs_regularly: some && mode == "on_request",
        location_unlocked: some && !locked && mode != "off",
        footer,
        confirm_within: (mode == "interval" && !watched).then(|| interval_label(interval * 2)),
        watch_line: watch
            .filter(|_| some && mode == "interval")
            .map(|w| watch_line(w, rows.len(), interval)),
        rows,
    }
}

/// What the phone's zone watch says (design 64 §4.1), under the zones while Regularly: the
/// fences it holds, or why it can't watch. Counts and switches only, never a place.
fn watch_line(watch: &crate::zones::ZoneWatch, zones: usize, interval: i64) -> String {
    if !watch.location_on {
        return "Location is off on the phone, so it can't watch the zones.".to_string();
    }
    if !watch.background_location {
        return "The phone can't use its location in the background yet, so it can't watch the \
                zones."
            .to_string();
    }
    if watch.fences == 0 {
        return "The phone hasn't set up its zones yet; they are judged at each location it \
                reports."
            .to_string();
    }
    let watching = match watch.fences {
        1 => "Watching 1 zone on the phone.".to_string(),
        n => format!("Watching {n} zones on the phone."),
    };
    let pending = if (watch.fences as usize) < zones {
        " The newest zone is set up at its next sync."
    } else {
        ""
    };
    let motion = if watch.motion_sensor {
        String::new()
    } else {
        format!(
            " It has no motion sensor, so it takes a location every {}.",
            interval_label(interval)
        )
    };
    format!("{watching}{pending}{motion}")
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

/// The Locate page's Requests (`partials/recent.html`, design 48 §1.4), `#recent`.
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
}

/// How long after a shutdown a report may come and its fix still be the last before the phone went
/// off: a report racing the shutdown, the phone's clock (the device page's "Off since" minute).
const BEFORE_OFF_SLACK_SECONDS: i64 = 60;

/// Locate's "Last position before the phone went off" (2026-10-25): the newest fix is a shutdown's
/// (`shutdown_at`, migration 0097), no status report came after that shutdown - once the phone
/// reports again it is on, whatever fix it re-sends - and the off's event may be said
/// (`off_row`: its title and arrival; `parent_events::off_said`, the device page's "Off since"
/// exclusions - not for a known restart, not while its push is held; qa-last-position-code Q-5). An
/// event gone with the inbox's 90 days leaves the line to the other two.
fn before_off(
    shutdown_at: Option<DateTime<Utc>>,
    last_report: Option<DateTime<Utc>>,
    off_row: Option<(&str, DateTime<Utc>)>,
    now: DateTime<Utc>,
) -> bool {
    let Some(off) = shutdown_at else {
        return false;
    };
    off_row.is_none_or(|(title, created)| crate::parent_events::off_said(title, created, now))
        && last_report.is_none_or(|report| {
            report <= off + chrono::Duration::seconds(BEFORE_OFF_SLACK_SECONDS)
        })
}

/// The phone's answer to the newest Update Location, and when it came.
struct AnswerView {
    answer: Answer,
    when: String,
    exact: String,
}

/// A command's row (Locate's Requests, the summary's Activity, Install Now's): "Lock · 10:58 ·
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

    /// "10 min ago", "yesterday 21:40", "12 Oct" (`Zone::relative`).
    pub(crate) fn age(&self, zone: &Zone, now: DateTime<Utc>) -> String {
        match self.at {
            Some(at) => zone.relative(at, now),
            None => "Time unknown".to_string(),
        }
    }

    /// "Within 40 m", or "Accuracy unknown".
    pub(crate) fn accuracy(&self) -> String {
        match self.accuracy_m {
            Some(m) => format!("Within {m} m"),
            None => "Accuracy unknown".to_string(),
        }
    }
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
/// `wipeResultMessage`, `endWindowsResult`) as §4 words. `None` for one it doesn't know.
fn reword(raw: &str) -> Option<String> {
    let known = match raw {
        "ringing" => "Ringing",
        "stopped" => "Stopped ringing",
        "locked (handy lock)" => "Locked with the Child PIN",
        "locked (Android)" => "Screen off (no Child PIN set)",
        // Design 59 §2.3: Android's lock; a fingerprint opens it again only after the PIN.
        "locked (android pin)" => "Locked with the Child PIN (fingerprint off until it's typed)",
        "failed to lock" => "The phone couldn't lock",
        "paused for 2 hours" => "Unlocked for 2 hours",
        "nothing was open" => "Nothing was unlocked",
        "location is off for this device" => "Location is off on the phone",
        "no location fix" => "The phone couldn't find its location",
        "unknown command - update the launcher" => "Vibb One on the phone is too old for this",
        // Install Now (design 37 §2.4).
        "not offered" => "No update for this phone",
        "refused: wrong_signer" => "Refused: signed with another key",
        "refused: not_ours" => "Refused: not Vibb One",
        "refused: downgrade" => "Refused: older than the phone's version",
        "refused: unparseable" => "Refused: not a readable app",
        "refused: release" => "Refused: this release can't be installed",
        "download failed" => "The download kept failing",
        "download corrupt" => "The download didn't match the release",
        "no space" => "Not enough space on the phone",
        "superseded" => "Ended: a newer Install Now took over",
        "call never ended" => "Not installed: a call stayed on",
        _ => "",
    };
    if !known.is_empty() {
        return Some(known.to_string());
    }
    // Design 59: `set_kid_pin`'s answers, in the summary's Activity (the Lock page words them too).
    if let Some(answer) = crate::kid_lock::PinAnswer::parse(raw) {
        return Some(answer.words().to_string());
    }
    if let Some(count) = raw
        .strip_prefix("allowed: ")
        .and_then(|rest| rest.strip_suffix(" apps"))
        .and_then(|n| n.parse::<u32>().ok())
    {
        return Some(match count {
            1 => "1 app allowed".to_string(),
            n => format!("{n} apps allowed"),
        });
    }
    if raw.starts_with("ended: ") {
        return Some("Locked again".to_string());
    }
    if let Some(version) = raw.strip_prefix("installed: ") {
        return Some(format!("Installed {version}"));
    }
    if let Some(version) = raw.strip_prefix("already running: ") {
        return Some(format!("Already on {version}"));
    }
    if raw.starts_with("install failed: ") {
        return Some("Android refused the install".to_string());
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
            Some(m) => format!("Found within {m} m"),
            None => "Found".to_string(),
        });
    }
    if raw.starts_with("wiping") {
        return Some("Erasing".to_string());
    }
    if raw.starts_with("wipe failed") {
        return Some("Not erased: the phone keeps its data".to_string());
    }
    None
}

fn answer(raw: &str) -> Option<Answer> {
    let raw = raw.trim();
    // "ok"/"failed" are this server's own words for an answer without a message
    // (`device_api::command_result`): the row's word says it already.
    if raw.is_empty() || raw == "ok" || raw == "failed" {
        return None;
    }
    Some(match reword(raw) {
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

/// " (10:59)" after a past time, or nothing when it doesn't read.
fn bracket(at: Option<DateTime<Utc>>, zone: &Zone, now: DateTime<Utc>) -> String {
    at.map(|at| format!(" ({})", zone.when(at, now)))
        .unwrap_or_default()
}

/// A status's word, its colour and the line under the row (times local, §3.10).
fn status_words(
    status: &CommandStatus,
    zone: &Zone,
    now: DateTime<Utc>,
) -> (&'static str, &'static str, Option<String>) {
    match *status {
        CommandStatus::Done { .. } => ("Done", "green", None),
        CommandStatus::Failed { .. } => ("Failed", "red", None),
        CommandStatus::Expired => (
            "Expired",
            "grey",
            Some("Never sent: the phone wasn't back within an hour.".to_string()),
        ),
        CommandStatus::Cancelled { by, .. } => match by {
            CancelledBy::Parent => ("Cancelled", "grey", Some("Never sent.".to_string())),
            CancelledBy::Restart => (
                "Cancelled",
                "grey",
                Some("Never sent: the server restarted first.".to_string()),
            ),
            CancelledBy::Enrolled => (
                "Cancelled",
                "grey",
                Some("Never sent: the phone was set up again.".to_string()),
            ),
            CancelledBy::Replaced => (
                "Replaced",
                "grey",
                Some("Never sent: a newer one took its place.".to_string()),
            ),
        },
        CommandStatus::Erasing { at } => (
            "Erasing",
            "orange",
            Some(format!(
                "The phone got it{}. An erased phone stops reporting.",
                bracket(at, zone, now)
            )),
        ),
        CommandStatus::Received { at, overdue: true } => (
            "No answer",
            "red",
            Some(format!(
                "The phone got it{} but never said it finished.",
                bracket(at, zone, now)
            )),
        ),
        CommandStatus::Received { at, overdue: false } => (
            "Received",
            "grey",
            Some(format!("The phone got it{}.", bracket(at, zone, now))),
        ),
        CommandStatus::Waiting {
            expires: Some(expires),
        } => (
            "Waiting",
            "orange",
            Some(format!(
                "Expires {} if the phone isn't back.",
                zone.until(expires, now)
            )),
        ),
        CommandStatus::Waiting { expires: None } => (
            "Waiting",
            "orange",
            Some("Goes out when the phone is back.".to_string()),
        ),
        CommandStatus::Sent { unconfirmed, .. } => match unconfirmed {
            Unconfirmed::NotYet => ("Sent", "grey", Some("Not confirmed yet.".to_string())),
            Unconfirmed::OfferedAgain => (
                "Sent",
                "orange",
                Some("Not confirmed; sent again when the phone is back.".to_string()),
            ),
            Unconfirmed::Lost => (
                "Not confirmed",
                "red",
                Some("It may not have reached the phone. Send it again.".to_string()),
            ),
            Unconfirmed::CantConfirm => (
                "Sent",
                "grey",
                Some(
                    "This phone's Vibb One can't confirm an erase. An erased phone stops reporting."
                        .to_string(),
                ),
            ),
        },
    }
}

pub(crate) fn command_row(
    command: &DeviceCommand,
    names: &crate::parents::Names,
    zone: &Zone,
    now: DateTime<Utc>,
    receipts: bool,
) -> CommandRow {
    let naive_now = now.naive_utc();
    let status = command.status(naive_now, receipts);
    let (word, tone, line) = status_words(&status, zone, now);
    // A result is the row's line: what the phone did, in its own words reworded.
    // A result is the row's line: what the phone did, reworded. An erase the phone reported as
    // going through reads as one it confirmed (one wording for both).
    let answered = match status {
        CommandStatus::Erasing { .. } => None,
        _ => command.result.as_deref().and_then(answer),
    };
    let (detail, detail_title) = match answered {
        Some(Answer { text, raw }) => (Some(text), raw),
        None => (line, None),
    };
    let asked = parse_db_utc(&command.requested_at);
    CommandRow {
        kind: command.command.clone(),
        label: command_label(&command.command),
        asked: asked.map_or_else(|| "Time unknown".to_string(), |t| zone.at(t, now)),
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
/// if none) - the page's wait shows the phone's answer (`text`, reworded) as soon as it arrives.
pub async fn locate_result_json(State(state): State<AppState>, Path(id): Path<i64>) -> Response {
    match latest_locate(&state, id).await {
        Ok(Some((cmd, result, _))) => {
            let text = result.as_deref().and_then(answer).map(|a| a.text);
            Json(serde_json::json!({ "id": cmd, "result": result, "text": text })).into_response()
        }
        Ok(None) => Json(serde_json::json!({})).into_response(),
        Err(err) => db_error(id, err),
    }
}

/// Locate's Requests for `device` (design 48 §1.4), newest first, with the line after a Cancel
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
        flash: cancel_flash(cancel),
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
fn locations_value(count: i64, hours: i64, newest_age: Option<&str>) -> String {
    let words = SPANS
        .iter()
        .find(|(h, _)| *h == hours)
        .map_or("", |(_, words)| words);
    match (count, newest_age) {
        (0, None) => "None yet".to_string(),
        (n, _) if hours == 0 => format!("{n} kept"),
        (0, Some(age)) => format!("0 in the last {words} · newest {age}"),
        (n, _) => format!("{n} in the last {words}"),
    }
}

/// The line after a Cancel (`?cancel=`), in Requests or the summary's Activity, and whether it
/// was refused.
pub(crate) fn cancel_flash(code: Option<&str>) -> Option<(&'static str, bool)> {
    match code? {
        "done" => Some(("Cancelled. The phone will never get it.", false)),
        "too_late" => Some((
            "Too late to cancel: the phone already has it, or it expired.",
            true,
        )),
        _ => None,
    }
}

/// A read failed: the page isn't shown with made-up values (qa-31-s4-code L4).
fn db_error(device_id: i64, err: sqlx::Error) -> Response {
    tracing::error!(device_id, %err, "can't read the Locate page's data");
    (
        StatusCode::INTERNAL_SERVER_ERROR,
        "Couldn't read this phone - check the server log.",
    )
        .into_response()
}

/// "10 min", "1 hour", "4 hours".
fn interval_label(minutes: i64) -> String {
    match minutes {
        m if m < 60 => format!("{m} min"),
        60 => "1 hour".to_string(),
        m => format!("{} hours", m / 60),
    }
}

/// The phone switcher's entries, the chosen one marked.
fn phone_links(
    state: &AppState,
    devices: &[Device],
    selected: Option<i64>,
    zone: &Zone,
    now: DateTime<Utc>,
) -> Vec<PhoneLink> {
    use crate::handlers::devices::{avatar_class, connection_of, initial};
    use crate::streams::ConnectionState;
    devices
        .iter()
        .map(|device| {
            let connection = connection_of(state, device, now);
            let (seen, seen_exact) = match connection {
                ConnectionState::Disconnected => (Some("access removed".to_string()), None),
                c => match c.last_contact() {
                    Some(at) => (
                        Some(format!("seen {}", zone.relative(at, now))),
                        Some(zone.exact(at)),
                    ),
                    None => (None, None),
                },
            };
            PhoneLink {
                id: device.id,
                name: device.name.clone(),
                initial: initial(&device.name),
                avatar: avatar_class(device),
                aria: format!("{}, {}", device.name, connection.word().to_lowercase()),
                connection,
                seen,
                seen_exact,
                current: selected == Some(device.id),
            }
        })
        .collect()
}

/// What the request asks the Locate page to show besides the phone.
struct Asked<'a> {
    /// The name typed into Erase Phone… didn't match.
    erase_refused: bool,
    /// Just pressed Update Location (`?requested=1`).
    waiting_for_fix: bool,
    /// `?cancel=`, the line in Recent.
    cancel: Option<&'a str>,
    /// `?span=` ([span_of]).
    span: i64,
    /// `?zone=added|saved|deleted` and `?zone_id=`: the line after a zone's save (design 64).
    zone: Option<&'a str>,
    zone_id: Option<i64>,
}

/// The page. With one phone it is shown at once; with several and none (or an unknown one)
/// chosen, the page lists them. Every read that fails is a 500, never a default (an "Off" or an
/// empty Recent that isn't true).
async fn render_locate_page(
    state: &AppState,
    admin_id: i64,
    selected_id: Option<i64>,
    asked: Asked<'_>,
) -> Result<Html<String>, sqlx::Error> {
    let devices = sqlx::query_as::<_, Device>("SELECT * FROM devices ORDER BY name, id")
        .fetch_all(&state.db)
        .await?;
    let zone = Zone::load(&state.db).await;
    let now = Utc::now();
    let device = selected_id
        .and_then(|id| devices.iter().find(|d| d.id == id))
        .or_else(|| (devices.len() == 1).then(|| &devices[0]))
        .cloned();
    let phones = phone_links(state, &devices, device.as_ref().map(|d| d.id), &zone, now);
    let selected = match device {
        Some(device) => Some(locate_view(state, device, &zone, now, asked).await?),
        None => None,
    };
    Ok(Html(
        LocateTemplate {
            title: "Locate".to_string(),
            phones,
            selected,
            bell: crate::parent_events::Bell::load(&state.db, admin_id).await,
        }
        .render()
        .unwrap(),
    ))
}

async fn locate_view(
    state: &AppState,
    device: Device,
    zone: &Zone,
    now: DateTime<Utc>,
    asked: Asked<'_>,
) -> Result<Locate, sqlx::Error> {
    let Asked {
        erase_refused,
        waiting_for_fix,
        cancel,
        span,
        zone: zone_flash,
        zone_id: flash_zone,
    } = asked;
    let id = device.id;
    let recent = requests(state, id, cancel, zone, now).await?;

    // A phone always has its policy row (`insert_device_with_policy`); a missing one reads as
    // the defaults a new phone gets.
    let (location_mode, location_interval, location_retention, android_lock, location_locked): (
        String,
        i64,
        i64,
        bool,
        bool,
    ) = sqlx::query_as(
        "SELECT location_mode, location_interval_minutes, location_retention_days, \
         kid_keyguard = ? AND kid_pin_hash IS NOT NULL, lock_location \
         FROM device_policy WHERE device_id = ?",
    )
    .bind(crate::kid_lock::KEYGUARD_ANDROID)
    .bind(id)
    .fetch_optional(&state.db)
    .await?
    .unwrap_or_else(|| {
        (
            "on_request".to_string(),
            30,
            crate::retention::DEFAULT_LOCATION_RETENTION_DAYS,
            false,
            true,
        )
    });
    let zone_list = crate::zones::list(&state.db, id).await?;
    // Design 64 step 2: the phone's watch and the interval it is served (the 30-min floor).
    let watch = crate::zones::last_zone_watch(&state.db, id).await?;
    let served_interval = crate::zones::served_interval(
        &location_mode,
        location_interval,
        zone_list.len(),
        crate::zones::watching(watch.as_ref()),
    );

    let (newest, mut map) = map_data(state, id, span, zone, now).await?;
    // The newest fix's badge (design 64 §3.5): the zones that fix is surely in.
    let badge = newest.as_ref().and_then(|l| fix_badge(&zone_list, l));
    let shutdown_at = newest
        .as_ref()
        .and_then(|l| l.shutdown_at.as_deref())
        .and_then(parse_db_utc);
    let (last_report, off_row) = match shutdown_at {
        Some(off) => {
            let last = sqlx::query_scalar::<_, Option<String>>(
                "SELECT MAX(reported_at) FROM device_status WHERE device_id = ?",
            )
            .bind(id)
            .fetch_one(&state.db)
            .await?
            .as_deref()
            .and_then(parse_db_utc);
            // The off's event by its key (the shutdown's instant, as `shutdown_at` is stored).
            let row = sqlx::query_as::<_, (String, String)>(
                "SELECT title, created_at FROM parent_events WHERE device_id = ? \
                 AND kind = 'phone_off' AND dedup_key = ?",
            )
            .bind(id)
            .bind(crate::zones::instant(off))
            .fetch_optional(&state.db)
            .await?
            .and_then(|(title, created)| Some((title, parse_db_utc(&created)?)));
            (last, row)
        }
        None => (None, None),
    };
    let fix_before_off = before_off(
        shutdown_at,
        last_report,
        off_row.as_ref().map(|(title, at)| (title.as_str(), *at)),
        now,
    );
    let fix = newest.as_ref().map(|l| {
        let fix = Fix::of(&l.captured_at, l.accuracy_meters);
        let accuracy = match &badge {
            Some(badge) => format!("{} · {badge}", fix.accuracy()),
            None => fix.accuracy(),
        };
        FixView {
            age: fix.age(zone, now),
            accuracy,
            exact: fix_title(fix.at.map(|t| zone.exact(t)).unwrap_or_default(), l),
            before_off: fix_before_off,
        }
    });
    if let Some(badge) = badge.as_ref().filter(|_| newest.is_some()) {
        map.shown_text = format!("{} · {}", map.shown_text, lowercase_first(badge));
    }
    let count = count_in_span(state, id, span, now).await?;
    let locations_value = locations_value(
        count,
        span,
        newest.is_some().then_some(map.newest_age.as_str()),
    );

    let answer = latest_locate(state, id).await?.and_then(|(_, result, at)| {
        let answer = answer(result.as_deref()?)?;
        let at = at.as_deref().and_then(parse_db_utc);
        Some(AnswerView {
            answer,
            when: at.map(|t| zone.when(t, now)).unwrap_or_default(),
            exact: at.map(|t| zone.exact(t)).unwrap_or_default(),
        })
    });
    let wipe_not_ready = sqlx::query_scalar::<_, Option<bool>>(
        "SELECT remote_wipe_ready FROM device_status WHERE device_id = ? \
         ORDER BY reported_at DESC, id DESC LIMIT 1",
    )
    .bind(id)
    .fetch_optional(&state.db)
    .await?
    .flatten()
        == Some(false);

    let zones = zones_view(
        &zone_list,
        &location_mode,
        served_interval,
        location_locked,
        zone_flash,
        flash_zone,
        newest.as_ref().and_then(|l| parse_db_utc(&l.captured_at)),
        newest.as_ref().and_then(DeviceLocation::seen_at),
        watch.as_ref(),
        zone,
        now,
    );
    let interval_note = (served_interval != location_interval).then(|| {
        format!(
            "{} while the phone has zones",
            interval_label(served_interval)
        )
    });
    // Q-9: with when the count last rose.
    let mock_at = crate::zones::mock_logged_at(&state.db, id).await?;
    let mock_ignored = watch.filter(|w| w.mock_ignored > 0).map(|w| {
        let count = match w.mock_ignored {
            1 => "1 made-up location ignored".to_string(),
            n => format!("{n} made-up locations ignored"),
        };
        match mock_at {
            Some(at) => format!("{count}, the last {}", zone.at(at, now)),
            None => count,
        }
    });
    Ok(Locate {
        device,
        fix,
        map,
        span,
        locations_value,
        answer,
        waiting_for_fix,
        location_off: location_mode == "off",
        wipe_not_ready,
        android_lock,
        erase_refused,
        location_mode,
        location_interval,
        location_retention,
        modes: [
            ("off", "Off"),
            ("on_request", "Only when asked"),
            ("interval", "Regularly"),
        ],
        intervals: crate::time_rules::LOCATION_INTERVALS
            .iter()
            .map(|m| (*m, interval_label(*m)))
            .collect(),
        retention_choices: crate::retention::LOCATION_RETENTION_DAYS
            .iter()
            .map(|d| {
                let label = if *d == 1 {
                    "1 day".to_string()
                } else {
                    format!("{d} days")
                };
                (*d, label)
            })
            .collect(),
        recent,
        zones,
        interval_note,
        mock_ignored,
    })
}

/// The newest fix row's title (design 64 step 2, migration 0093): its exact time, then the phone's
/// provider word and "test location" for a mock fix (only a debug launcher sends one) - "24 Oct
/// 2026, 08:14:05 · gps · test location". Only the title: the row's words stay as they are.
fn fix_title(exact: String, fix: &DeviceLocation) -> String {
    let mut parts: Vec<String> = Vec::new();
    if !exact.is_empty() {
        parts.push(exact);
    }
    if let Some(provider) = fix.provider.as_deref() {
        parts.push(provider.to_string());
    }
    if fix.mock == Some(true) {
        parts.push("test location".to_string());
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
    let newest = sqlx::query_as::<_, DeviceLocation>(
        "SELECT * FROM device_locations WHERE device_id = ? ORDER BY captured_at DESC, id DESC \
         LIMIT 1",
    )
    .bind(device_id)
    .fetch_optional(&state.db)
    .await?;
    let fix = newest
        .as_ref()
        .map(|l| Fix::of(&l.captured_at, l.accuracy_meters));
    let newest_age = fix.as_ref().map(|f| f.age(zone, now)).unwrap_or_default();
    let map = MapData {
        device_id,
        shown: newest
            .as_ref()
            .map(|l| l.captured_at.clone())
            .unwrap_or_default(),
        shown_text: fix
            .as_ref()
            .map(|f| format!("{newest_age} · {}", f.accuracy().to_lowercase()))
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
    Extension(CurrentAdmin(admin)): Extension<CurrentAdmin>,
    Query(params): Query<HashMap<String, String>>,
) -> Response {
    let selected_id = params.get("device").and_then(|s| s.parse::<i64>().ok());
    let waiting = params.contains_key("requested");
    let cancel = params.get("cancel").map(String::as_str);
    let span = span_of(params.get("span"));
    let zone = params.get("zone").map(String::as_str);
    let zone_id = params.get("zone_id").and_then(|s| s.parse::<i64>().ok());
    match render_locate_page(
        &state,
        admin.id,
        selected_id,
        Asked {
            erase_refused: false,
            waiting_for_fix: waiting,
            cancel,
            span,
            zone,
            zone_id,
        },
    )
    .await
    {
        Ok(page) => page.into_response(),
        Err(err) => db_error(selected_id.unwrap_or(0), err),
    }
}

/// `POST /devices/{id}/commands/{command_id}/cancel` (full auth): the "Cancel" of a command
/// the phone hasn't fetched yet (the user's decision, 2026-10-12). One statement, so it races the
/// policy's pop cleanly: whichever comes first wins - a cancelled command is never handed out (the
/// pop skips `cancelled_at`), and one the phone just fetched can't be cancelled (`too_late`, as is
/// an expired one, or another phone's). Logged in the security log. Back to the group it came
/// from, where the line says what happened: `back` = `summary` is the phone's summary page's
/// Activity (`#activity`, design 48 §1.5), anything else the Locate page's Requests (`#recent`).
pub async fn cancel_command(
    State(state): State<AppState>,
    Path((id, command_id)): Path<(i64, i64)>,
    Extension(CurrentAdmin(admin)): Extension<CurrentAdmin>,
    Form(form): Form<HashMap<String, String>>,
) -> impl IntoResponse {
    let outcome = match cancel_waiting(&state, id, command_id, None, &admin.username).await {
        Ok(true) => "done",
        Ok(false) => "too_late",
        Err(err) => {
            tracing::error!(device_id = id, command_id, %err, "can't cancel the command");
            return (
                StatusCode::INTERNAL_SERVER_ERROR,
                "Couldn't cancel it - check the server log. The command may still reach the phone.",
            )
                .into_response();
        }
    };
    let back = match form.get("back").map(String::as_str) {
        Some("summary") => format!("/devices/{id}?cancel={outcome}#activity"),
        _ => format!("/devices/locate?device={id}&cancel={outcome}#recent"),
    };
    Redirect::to(&back).into_response()
}

/// Cancels a command the phone hasn't fetched (the Locate page's Cancel, Install Now's - design
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
        Some(&format!(
            "{command} (asked {requested_at} UTC), device {device_id}"
        )),
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
        Err(err) => db_error(id, err),
    }
}

#[derive(Template)]
#[template(path = "device_locations.html")]
struct LocationsTemplate {
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
    Path(id): Path<i64>,
    Query(params): Query<HashMap<String, String>>,
) -> Response {
    let device = match sqlx::query_as::<_, Device>("SELECT * FROM devices WHERE id = ?")
        .bind(id)
        .fetch_optional(&state.db)
        .await
    {
        Ok(Some(device)) => device,
        Ok(None) => return (StatusCode::NOT_FOUND, "Device not found").into_response(),
        Err(err) => return db_error(id, err),
    };
    let span = span_of(params.get("span"));
    let before = before_of(params.get("before"));
    let zone = Zone::load(&state.db).await;
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
        Err(err) => return db_error(id, err),
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
            let when = at.map_or_else(|| "Time unknown".to_string(), |t| zone.at(t, now));
            let accuracy = Fix::of(&l.captured_at, l.accuracy_meters)
                .accuracy_m
                .map_or_else(|| "accuracy unknown".to_string(), |m| format!("± {m} m"));
            FixRow {
                latitude: l.latitude,
                longitude: l.longitude,
                popup: format!("{when} · {accuracy}"),
                exact: at.map(|t| zone.exact(t)).unwrap_or_default(),
                when,
                accuracy,
            }
        })
        .collect();
    Html(
        LocationsTemplate {
            title: "Locations".to_string(),
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
async fn queue_command(
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
            Some(&format!(
                "{replaced} waiting {} replaced by a new {command}, device {device_id}",
                kinds.join("/")
            )),
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
) -> Response {
    match queue_command(state, id, command, admin).await {
        Ok(true) => {
            security::record_security_event(
                &state.db,
                event,
                Some(&admin.username),
                None,
                Some(detail),
            )
            .await;
            Redirect::to(&back).into_response()
        }
        Ok(false) => (StatusCode::NOT_FOUND, "Device not found").into_response(),
        Err(err) => {
            tracing::error!(device_id = id, command, %err, "can't queue the command");
            (
                StatusCode::INTERNAL_SERVER_ERROR,
                "Couldn't send it - nothing was queued. Check the server log.",
            )
                .into_response()
        }
    }
}

/// Ring, Stop Ringing and Lock return to their group (`#actions`): Recent, right under it, shows
/// the new row, with and without JS.
fn back_to_actions(id: i64) -> String {
    format!("/devices/locate?device={id}#actions")
}

pub async fn ring(
    State(state): State<AppState>,
    Path(id): Path<i64>,
    Extension(CurrentAdmin(admin)): Extension<CurrentAdmin>,
) -> Response {
    let event = ("device_command_queued", "ring");
    send(&state, id, &admin, "ring", event, back_to_actions(id)).await
}

pub async fn stop_ring(
    State(state): State<AppState>,
    Path(id): Path<i64>,
    Extension(CurrentAdmin(admin)): Extension<CurrentAdmin>,
) -> Response {
    let event = ("device_command_queued", "stop_ring");
    send(&state, id, &admin, "stop_ring", event, back_to_actions(id)).await
}

pub async fn lock(
    State(state): State<AppState>,
    Path(id): Path<i64>,
    Extension(CurrentAdmin(admin)): Extension<CurrentAdmin>,
) -> Response {
    let event = ("device_command_queued", "lock");
    send(&state, id, &admin, "lock", event, back_to_actions(id)).await
}

/// Update Location: queues `locate`, which makes the phone take a fresh fix (bypassing its own
/// throttle and the location policy's interval) and report it; the page then waits for it, at
/// the map (`#location`).
pub async fn locate(
    State(state): State<AppState>,
    Path(id): Path<i64>,
    Extension(CurrentAdmin(admin)): Extension<CurrentAdmin>,
) -> Response {
    let back = format!("/devices/locate?device={id}&requested=1#location");
    let event = ("device_command_queued", "locate");
    send(&state, id, &admin, "locate", event, back).await
}

/// How long this phone's location history is kept (`POST /devices/{id}/location-retention`,
/// `days` = one of `retention::LOCATION_RETENTION_DAYS`; cleanup 2026-10-06). Older fixes are
/// deleted at once - the newest one always stays - and by the hourly pruning from then on.
/// 400 for another value, 404 for an unknown device; nothing is sent to the phone. Back to
/// Location Settings (`#location-settings`): the last group of a long page, so a `<noscript>` Save
/// or a save after a refused erase doesn't land at the top (qa-31-s13-code M1).
pub async fn update_location_retention(
    State(state): State<AppState>,
    Extension(CurrentAdmin(admin)): Extension<CurrentAdmin>,
    Path(id): Path<i64>,
    Form(form): Form<HashMap<String, String>>,
) -> Response {
    let Some(days) = form
        .get("days")
        .and_then(|d| d.trim().parse::<i64>().ok())
        .filter(|d| crate::retention::LOCATION_RETENTION_DAYS.contains(d))
    else {
        return (StatusCode::BAD_REQUEST, "Unknown retention period").into_response();
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
            (StatusCode::NOT_FOUND, "Device not found").into_response()
        }
        Ok(_) => {
            security::record_security_event(
                &state.db,
                "location_retention_changed",
                Some(&admin.username),
                None,
                Some(&format!("device {id}: {days} days")),
            )
            .await;
            if let Err(err) = crate::retention::prune(&state.db).await {
                tracing::error!(%err, "pruning after a retention change failed");
            }
            Redirect::to(&format!("/devices/locate?device={id}#location-settings")).into_response()
        }
        Err(err) => {
            tracing::error!(device_id = id, %err, "failed to save the location retention");
            (
                StatusCode::INTERNAL_SERVER_ERROR,
                "Couldn't save - nothing was changed. Check the server log.",
            )
                .into_response()
        }
    }
}

/// The location policy: `mode` off / on_request / interval, `interval_minutes` from
/// `time_rules::LOCATION_INTERVALS` (kept as is when absent: the page sends it only in
/// "Regularly"). 400 on an unknown value, 404 for an unknown device; nudges the phone. Back to
/// `#location-settings`, as the history - or to `#zones` for the zones' Locate Regularly
/// (`back=zones`, design 64 §3.6; anything else is the settings).
pub async fn update_location_policy(
    State(state): State<AppState>,
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
        return (StatusCode::BAD_REQUEST, "Unknown location mode or interval").into_response();
    }
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
            (StatusCode::NOT_FOUND, "Device not found").into_response()
        }
        Ok(_) => {
            security::record_security_event(
                &state.db,
                "location_policy_changed",
                Some(&admin.username),
                None,
                Some(&format!(
                    "device {id}: {mode}{}",
                    interval
                        .map(|m| format!(", every {m} min"))
                        .unwrap_or_default()
                )),
            )
            .await;
            let _ = state.command_notify.send(id);
            let group = match form.get("back").map(String::as_str) {
                Some("zones") => "zones",
                _ => "location-settings",
            };
            Redirect::to(&format!("/devices/locate?device={id}#{group}")).into_response()
        }
        Err(err) => {
            tracing::error!(device_id = id, %err, "failed to save the location policy");
            (
                StatusCode::INTERNAL_SERVER_ERROR,
                "Couldn't save - nothing was changed. Check the server log.",
            )
                .into_response()
        }
    }
}

/// Erase Phone…: gated the same way backup restore/delete are (`templates/backups.html`) - the
/// parent types the phone's exact name before anything is queued. An erase is irreversible and a
/// phone that erases never answers, so this confirmation is the only safety net there is. A name
/// that doesn't match renders the page with the confirm open and the refusal in it (the form's
/// action ends in `#erase`, so the answer lands there); 404 for an unknown phone.
pub async fn wipe(
    State(state): State<AppState>,
    Path(id): Path<i64>,
    Extension(CurrentAdmin(admin)): Extension<CurrentAdmin>,
    Form(form): Form<HashMap<String, String>>,
) -> Response {
    let device = match sqlx::query_as::<_, Device>("SELECT * FROM devices WHERE id = ?")
        .bind(id)
        .fetch_optional(&state.db)
        .await
    {
        Ok(Some(device)) => device,
        Ok(None) => return (StatusCode::NOT_FOUND, "Device not found").into_response(),
        Err(err) => return db_error(id, err),
    };

    let confirm = form.get("confirm_name").map(|s| s.trim()).unwrap_or("");
    if confirm != device.name {
        return match render_locate_page(
            &state,
            admin.id,
            Some(id),
            Asked {
                erase_refused: true,
                waiting_for_fix: false,
                cancel: None,
                span: SPANS[0].0,
                zone: None,
                zone_id: None,
            },
        )
        .await
        {
            Ok(page) => page.into_response(),
            Err(err) => db_error(id, err),
        };
    }
    let event = ("device_wipe_queued", device.name.as_str());
    send(&state, id, &admin, "wipe", event, back_to_actions(id)).await
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
        let told = Some(("Anna was turned off", off));
        assert!(line(None, told));
        assert!(line(at(-600), told), "a report before it");
        assert!(line(at(60), told), "racing it");
        assert!(!line(at(61), told), "on again");
        assert!(
            !super::before_off(None, None, told, now),
            "not a shutdown's fix"
        );
        // Q-5: as the device page's "Off since" - not for a known restart, not while held.
        assert!(
            !line(None, Some(("Anna is restarting", off))),
            "a known restart"
        );
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
        assert_eq!(fix.accuracy(), "Within 40 m");
        // A day old: the date, in local time; no accuracy.
        let old = Fix::of("2026-10-10T22:30:00Z", None);
        assert_eq!(old.age(&oslo, now), "11 Oct");
        assert_eq!(old.accuracy(), "Accuracy unknown");
        // Yesterday evening, local (21:40 UTC is 23:40 in Oslo).
        let late = Fix::of("2026-10-12T21:40:00Z", Some(5.0));
        assert_eq!(late.age(&oslo, now), "yesterday 23:40");
        // The fallback zone says UTC.
        assert_eq!(late.age(&Zone::utc_fallback(), now), "yesterday 21:40 UTC");
        // A time that doesn't read, a nonsense accuracy.
        let bad = Fix::of("z", Some(f64::NAN));
        assert_eq!(bad.age(&oslo, now), "Time unknown");
        assert_eq!(bad.accuracy(), "Accuracy unknown");
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
                answer(raw),
                Some(Answer {
                    text: text.to_string(),
                    raw: Some(raw.to_string())
                }),
                "{raw}"
            );
        }
        // An answer this server doesn't know is shown as sent; an empty one is none.
        assert_eq!(
            answer(" something new "),
            Some(Answer {
                text: "something new".to_string(),
                raw: None
            })
        );
        assert_eq!(answer("  "), None);
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
            assert_eq!(command_label(command), label);
        }
    }
}
