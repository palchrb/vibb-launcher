//! The Locate tab (`/devices/locate`, `templates/device_locate.html`; design 31 §3.6, step 13): a
//! phone switcher, the map (Leaflet against public OSM tiles, design 33), the last fix as age and
//! accuracy, Update Location, Ring, Stop Ringing, Lock and Erase Phone…, the location settings and
//! the recent commands. Commands are queued into `device_commands`, the phone is nudged over its
//! push channel and fetches them with its policy (see `handlers::device_api::policy`) - within
//! seconds while it's online, otherwise when it's back. The location policy (off / on request /
//! every N minutes, handy step 6) decides when the phone takes a fix on its own.

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
    CancelledBy, CommandStatus, Device, DeviceCommand, DeviceLocation, Unconfirmed, command_label,
};
use crate::security::{self, CurrentAdmin};

/// How many commands Recent lists.
const RECENT_COMMANDS: i64 = 10;

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
    /// The newest fix's `captured_at` and its popup text as JavaScript string literals
    /// ([js_string]) for the page's script - never HTML-escaped text inside quotes (security
    /// review H2).
    last_fix_at_js: String,
    fix_popup_js: String,
    /// The phone's answer to the newest Update Location.
    answer: Option<AnswerView>,
    /// Just pressed Update Location: the page polls for a fresh fix.
    waiting_for_fix: bool,
    /// The location policy is off: the phone switches location off and never takes a fix.
    location_off: bool,
    /// The phone's last report said it can't erase itself yet (QA of the launcher wave, Low 4).
    wipe_not_ready: bool,
    /// The name typed into Erase Phone… didn't match: the confirm is open with the refusal.
    erase_refused: bool,
    location_mode: String,
    location_interval: i64,
    location_retention: i64,
    modes: [(&'static str, &'static str); 3],
    intervals: Vec<(i64, String)>,
    retention_choices: Vec<(i64, String)>,
    commands: Vec<CommandRow>,
    /// After Cancel (`?cancel=done|too_late`): the line in Recent, and whether it was refused.
    cancel_flash: Option<(&'static str, bool)>,
}

/// The newest fix: "10 min ago", "Within 40 m", the exact time for `title`.
struct FixView {
    age: String,
    accuracy: String,
    exact: String,
}

/// The phone's answer to the newest Update Location, and when it came.
struct AnswerView {
    answer: Answer,
    when: String,
    exact: String,
}

/// A Recent row: "Lock · 10:58 · Done", what happened under the label, Cancel while it waits.
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
        asked: asked.map_or_else(|| "Time unknown".to_string(), |t| zone.when(t, now)),
        asked_exact: asked.map(|t| zone.exact(t)).unwrap_or_default(),
        word,
        tone,
        detail,
        detail_title,
        cancel_id: command.cancellable(naive_now).then_some(command.id),
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

/// `value` as a JavaScript string literal safe inside a `<script>` element: JSON with `<`, `>`,
/// `&` and the line separators escaped, so no value can close the element or the string.
pub(crate) fn js_string(value: &str) -> String {
    serde_json::to_string(value)
        .unwrap_or_else(|_| "\"\"".to_string())
        .replace('<', "\\u003c")
        .replace('>', "\\u003e")
        .replace('&', "\\u0026")
        .replace('\u{2028}', "\\u2028")
        .replace('\u{2029}', "\\u2029")
}

/// The line in Recent after a Cancel (`?cancel=`), and whether it was refused.
fn cancel_flash(code: Option<&str>) -> Option<(&'static str, bool)> {
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

/// The page. With one phone it is shown at once; with several and none (or an unknown one)
/// chosen, the page lists them. Every read that fails is a 500, never a default (an "Off" or an
/// empty Recent that isn't true).
async fn render_locate_page(
    state: &AppState,
    selected_id: Option<i64>,
    erase_refused: bool,
    waiting_for_fix: bool,
    cancel: Option<&str>,
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
        Some(device) => Some(
            locate_view(
                state,
                device,
                &zone,
                now,
                erase_refused,
                waiting_for_fix,
                cancel,
            )
            .await?,
        ),
        None => None,
    };
    Ok(Html(
        LocateTemplate {
            title: "Locate".to_string(),
            phones,
            selected,
            bell: crate::parent_events::Bell::load(&state.db).await,
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
    erase_refused: bool,
    waiting_for_fix: bool,
    cancel: Option<&str>,
) -> Result<Locate, sqlx::Error> {
    let id = device.id;
    let commands = sqlx::query_as::<_, DeviceCommand>(
        "SELECT * FROM device_commands WHERE device_id = ? \
         ORDER BY requested_at DESC, id DESC LIMIT ?",
    )
    .bind(id)
    .bind(RECENT_COMMANDS)
    .fetch_all(&state.db)
    .await?;
    let receipts = crate::handlers::device_api::sends_command_receipts(&state.db, id).await?;
    let commands = commands
        .iter()
        .map(|command| command_row(command, zone, now, receipts))
        .collect();

    // A phone always has its policy row (`insert_device_with_policy`); a missing one reads as
    // the defaults a new phone gets.
    let (location_mode, location_interval, location_retention): (String, i64, i64) =
        sqlx::query_as(
            "SELECT location_mode, location_interval_minutes, location_retention_days \
             FROM device_policy WHERE device_id = ?",
        )
        .bind(id)
        .fetch_optional(&state.db)
        .await?
        .unwrap_or_else(|| {
            (
                "on_request".to_string(),
                30,
                crate::retention::DEFAULT_LOCATION_RETENTION_DAYS,
            )
        });

    let newest = sqlx::query_as::<_, DeviceLocation>(
        "SELECT * FROM device_locations WHERE device_id = ? ORDER BY captured_at DESC, id DESC \
         LIMIT 1",
    )
    .bind(id)
    .fetch_optional(&state.db)
    .await?;
    let fix = newest.as_ref().map(|l| {
        let fix = Fix::of(&l.captured_at, l.accuracy_meters);
        FixView {
            age: fix.age(zone, now),
            accuracy: fix.accuracy(),
            exact: fix.at.map(|t| zone.exact(t)).unwrap_or_default(),
        }
    });
    let fix_popup = fix
        .as_ref()
        .map(|f| format!("{} · {}", f.age, f.accuracy.to_lowercase()))
        .unwrap_or_default();
    let last_fix_at = newest.map(|l| l.captured_at).unwrap_or_default();

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

    Ok(Locate {
        device,
        fix,
        last_fix_at_js: js_string(&last_fix_at),
        fix_popup_js: js_string(&fix_popup),
        answer,
        waiting_for_fix,
        location_off: location_mode == "off",
        wipe_not_ready,
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
        commands,
        cancel_flash: cancel_flash(cancel),
    })
}

pub async fn show_locate(
    State(state): State<AppState>,
    Query(params): Query<HashMap<String, String>>,
) -> Response {
    let selected_id = params.get("device").and_then(|s| s.parse::<i64>().ok());
    let waiting = params.contains_key("requested");
    let cancel = params.get("cancel").map(String::as_str);
    match render_locate_page(&state, selected_id, false, waiting, cancel).await {
        Ok(page) => page.into_response(),
        Err(err) => db_error(selected_id.unwrap_or(0), err),
    }
}

/// `POST /devices/{id}/commands/{command_id}/cancel` (full auth): Recent's "Cancel" for a command
/// the phone hasn't fetched yet (the user's decision, 2026-10-12). One statement, so it races the
/// policy's pop cleanly: whichever comes first wins - a cancelled command is never handed out (the
/// pop skips `cancelled_at`), and one the phone just fetched can't be cancelled (`too_late`, as is
/// an expired one, or another phone's). Logged in the security log. Back to Recent (`#recent`),
/// where the line says what happened.
pub async fn cancel_command(
    State(state): State<AppState>,
    Path((id, command_id)): Path<(i64, i64)>,
    Extension(CurrentAdmin(admin)): Extension<CurrentAdmin>,
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
    Redirect::to(&format!(
        "/devices/locate?device={id}&cancel={outcome}#recent"
    ))
    .into_response()
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

pub async fn locations_json(State(state): State<AppState>, Path(id): Path<i64>) -> Response {
    match sqlx::query_as::<_, DeviceLocation>(
        "SELECT * FROM device_locations WHERE device_id = ? ORDER BY captured_at ASC",
    )
    .bind(id)
    .fetch_all(&state.db)
    .await
    {
        Ok(locations) => Json(locations).into_response(),
        Err(err) => db_error(id, err),
    }
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
    admin: &str,
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
    sqlx::query("INSERT INTO device_commands (device_id, command) VALUES (?, ?)")
        .bind(device_id)
        .bind(command)
        .execute(&mut *tx)
        .await?;
    tx.commit().await?;
    if replaced > 0 {
        security::record_security_event(
            &state.db,
            "device_command_replaced",
            Some(admin),
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
    admin: &str,
    command: &str,
    (event, detail): (&str, &str),
    back: String,
) -> Response {
    match queue_command(state, id, command, admin).await {
        Ok(true) => {
            security::record_security_event(&state.db, event, Some(admin), None, Some(detail))
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
    send(
        &state,
        id,
        &admin.username,
        "ring",
        event,
        back_to_actions(id),
    )
    .await
}

pub async fn stop_ring(
    State(state): State<AppState>,
    Path(id): Path<i64>,
    Extension(CurrentAdmin(admin)): Extension<CurrentAdmin>,
) -> Response {
    let event = ("device_command_queued", "stop_ring");
    send(
        &state,
        id,
        &admin.username,
        "stop_ring",
        event,
        back_to_actions(id),
    )
    .await
}

pub async fn lock(
    State(state): State<AppState>,
    Path(id): Path<i64>,
    Extension(CurrentAdmin(admin)): Extension<CurrentAdmin>,
) -> Response {
    let event = ("device_command_queued", "lock");
    send(
        &state,
        id,
        &admin.username,
        "lock",
        event,
        back_to_actions(id),
    )
    .await
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
    send(&state, id, &admin.username, "locate", event, back).await
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
/// `#location-settings`, as the history.
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
            Redirect::to(&format!("/devices/locate?device={id}#location-settings")).into_response()
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
        return match render_locate_page(&state, Some(id), true, false, None).await {
            Ok(page) => page.into_response(),
            Err(err) => db_error(id, err),
        };
    }
    let event = ("device_wipe_queued", device.name.as_str());
    send(
        &state,
        id,
        &admin.username,
        "wipe",
        event,
        back_to_actions(id),
    )
    .await
}

#[cfg(test)]
mod tests {
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
