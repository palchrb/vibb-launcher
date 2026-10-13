//! A phone's About This Phone, Lock Screen & PINs, Home Screen, Web Filter and Advanced pages
//! (design 31 §3.2, §8 steps 3-5).
//!
//! - **About This Phone** (`GET /devices/{id}/about`, `device_about.html`) is read-only, like iOS
//!   Settings › General › About: the connection (§3.10), the sound, the software, the crash
//!   reports, the starts without Vibb and the time lock - the facts the old Status, Access and
//!   "Play and kiosk" cards led with. Every time is local (Phone Setup's zone, `display_time`),
//!   with the exact one in a `title`.
//! - **Lock Screen & PINs** (`GET /devices/{id}/lock`, `device_lock.html`, step 4): the Child and
//!   Parent PINs, the screen timeout, the lock's background and what shows on it ([view_lock]).
//! - **Home Screen** (`GET /devices/{id}/home`, step 5): language and apps per row, the
//!   wallpapers, the quick controls ([view_home]). **Web Filter** (`GET /devices/{id}/filter`):
//!   the filter switch, this phone's lists and domains, the blocked-domain log ([view_filter]).
//! - **Advanced** (`GET /devices/{id}/advanced`, `device_advanced.html`) holds what a parent sets
//!   once or never: Restrictions (`/hardening`, one form), App screens (`/kiosk-block` and the Play
//!   state), Updates & notifications (`/kiosk-escapes`, one form), Connection (`/regenerate-code`,
//!   the QR, `/revoke`) and Remove Phone (`/delete`). The forms and their POST routes are
//!   unchanged; only where they return to moved here.
//!
//! The texts come from typed values (M5): `sound_mode::state`, `streams::ConnectionState`,
//! `limits::LastAccess`, `boot_witness::unseen`, [PolicyProblem], [PlayNote] and
//! `kiosk_escapes::EscapeNote`, worded here as a [Note] or a [Row].

use askama::Template;
use axum::extract::{Path, Query, State};
use axum::http::StatusCode;
use axum::response::{Html, IntoResponse, Response};
use chrono::{DateTime, Utc};

use crate::AppState;
use crate::display_time::Zone;
use crate::models::{Device, DevicePolicy, DeviceStatus, Hardening};
use crate::streams::ConnectionState;

/// A row's words from a typed state: a short title, maybe a value, maybe the sentence behind its
/// ⓘ. A warning shows as an orange row above its group (title + the sentence), anything else as a
/// row in the group (title · value, the sentence behind ⓘ).
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Note {
    pub warning: bool,
    pub title: String,
    pub value: Option<String>,
    pub detail: Option<String>,
    /// An expert fix (an adb command, a package name) for a warning: behind its ⓘ, never in the
    /// visible text (design 31 §4.2).
    pub fix: Option<String>,
}

/// A value row on About: label · value, an optional coloured word before the value (the
/// connection state), the exact time for `title=`, and the ⓘ sentence.
pub struct Row {
    /// For tests and the page's hooks (`data-row`).
    pub key: &'static str,
    pub label: String,
    /// "Online" and its colour class (`state-online`, ...), before the value.
    pub lead: Option<(&'static str, &'static str)>,
    pub value: String,
    /// `row-value-alarm` / `row-value-action` for a value that is a problem, else "".
    pub value_class: &'static str,
    /// The exact time, for `title=`.
    pub title: Option<String>,
    pub detail: Option<String>,
}

impl Row {
    fn new(key: &'static str, label: &str, value: impl Into<String>) -> Row {
        Row {
            key,
            label: label.to_string(),
            lead: None,
            value: value.into(),
            value_class: "",
            title: None,
            detail: None,
        }
    }

    fn detail(mut self, detail: Option<impl Into<String>>) -> Row {
        self.detail = detail.map(Into::into);
        self
    }

    fn title(mut self, title: Option<String>) -> Row {
        self.title = title;
        self
    }

    fn class(mut self, class: &'static str) -> Row {
        self.value_class = class;
        self
    }
}

/// A non-"ok" `device_status.policy_state`: the phone isn't applying this server's settings
/// (migrations/0021). About's "Settings from server" row and an Attention alarm.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum PolicyProblem {
    CacheCorrupt,
    ServerError,
    RejectedSuspect,
    FreshDecodeFailed,
    /// A state this server doesn't know.
    Other(String),
}

impl PolicyProblem {
    /// `None` for "ok".
    pub fn parse(state: &str) -> Option<PolicyProblem> {
        Some(match state {
            "ok" => return None,
            "cache_corrupt" => PolicyProblem::CacheCorrupt,
            "server_error" => PolicyProblem::ServerError,
            "rejected_suspect" => PolicyProblem::RejectedSuspect,
            "fresh_decode_failed" => PolicyProblem::FreshDecodeFailed,
            other => PolicyProblem::Other(other.to_string()),
        })
    }

    /// The row's value: at most 4 words.
    pub fn title(&self) -> &'static str {
        match self {
            PolicyProblem::CacheCorrupt => "Saved settings unreadable",
            PolicyProblem::ServerError => "Server error",
            PolicyProblem::RejectedSuspect => "Last settings refused",
            PolicyProblem::FreshDecodeFailed => "New settings unreadable",
            PolicyProblem::Other(_) => "Problem reported",
        }
    }

    /// The ⓘ: what the phone does now, and what to check.
    pub fn detail(&self) -> String {
        match self {
            PolicyProblem::CacheCorrupt => "The phone can't read its saved settings. It enforces \
                the last app list it had (or nothing but Vibb), and takes no changes until it gets \
                good settings from this server."
                .to_string(),
            PolicyProblem::ServerError => "This server answered with an error, so the phone keeps \
                its last settings. Check the server log (\"failed to build device policy\")."
                .to_string(),
            PolicyProblem::RejectedSuspect => "The phone ignored the last settings from this \
                server: they looked like a server falling back to defaults (no app list after it \
                had one). Check the server log and version."
                .to_string(),
            PolicyProblem::FreshDecodeFailed => "The phone couldn't read the last settings from \
                this server; Vibb and the server versions probably don't match. It keeps its \
                current restrictions."
                .to_string(),
            PolicyProblem::Other(code) => format!("The phone reported \"{code}\"."),
        }
    }
}

/// What the phone's last report says about Play (Advanced › App screens), typed (M5).
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum PlayNote {
    /// Play install mode is on: the Play Store opens until then.
    InstallMode { until_ms: i64 },
    /// The nightly Play update window is open (screen off).
    UpdateWindow,
    /// A device-admin policy waits for a restart (`models::admin_policy_effects`).
    RestartOnce(Vec<&'static str>),
    /// Android won't suspend Play on this phone; `block_on`: the kiosk app block is on.
    NotSuspendable { block_on: bool },
}

impl PlayNote {
    pub fn note(&self, zone: &Zone, now: DateTime<Utc>) -> Note {
        match self {
            PlayNote::InstallMode { until_ms } => Note {
                warning: true,
                title: format!(
                    "Play Store open until {}",
                    DateTime::from_timestamp_millis(*until_ms)
                        .map(|t| zone.until(t, now))
                        .unwrap_or_default()
                ),
                value: None,
                detail: Some(
                    "Install mode is on. Apps installed now stay hidden until you allow them."
                        .to_string(),
                ),
                fix: None,
            },
            PlayNote::UpdateWindow => Note {
                warning: false,
                title: "Play Store".to_string(),
                value: Some("Updating apps".to_string()),
                detail: Some(
                    "The nightly update window is open while the screen is off.".to_string(),
                ),
                fix: None,
            },
            PlayNote::RestartOnce(effects) => Note {
                warning: true,
                title: "Restart the phone once".to_string(),
                value: None,
                detail: Some(format!(
                    "After a Vibb update, Android hasn't yet given Vibb what it needs to {}.",
                    effects.join(", and to ")
                )),
                fix: None,
            },
            PlayNote::NotSuspendable { block_on } => Note {
                warning: true,
                title: "Play can't be paused on this phone".to_string(),
                value: None,
                detail: Some(if *block_on {
                    "Play is blocked only while Block other apps' screens is on.".to_string()
                } else {
                    "Block other apps' screens is off, so apps can open the Play Store.".to_string()
                }),
                fix: None,
            },
        }
    }
}

/// Advanced › App screens: the kiosk app block switch and Play's state.
pub(crate) struct PlayCard {
    /// `device_policy.block_activity_start` (handy step 9).
    pub block_activity_start: bool,
    pub notes: Vec<PlayNote>,
}

/// Builds [PlayCard] from the phone's latest status report.
pub(crate) async fn play_card(
    state: &AppState,
    device_id: i64,
    latest: Option<&DeviceStatus>,
    now_ms: i64,
) -> PlayCard {
    let block_activity_start: bool =
        sqlx::query_scalar("SELECT block_activity_start FROM device_policy WHERE device_id = ?")
            .bind(device_id)
            .fetch_optional(&state.db)
            .await
            .ok()
            .flatten()
            .unwrap_or(true);
    PlayCard {
        block_activity_start,
        notes: play_notes(latest, block_activity_start, now_ms),
    }
}

/// [PlayCard]'s notes, in the old card's order. Pure.
pub(crate) fn play_notes(
    latest: Option<&DeviceStatus>,
    block_activity_start: bool,
    now_ms: i64,
) -> Vec<PlayNote> {
    let mut notes = Vec::new();
    let Some(status) = latest else {
        return notes;
    };
    if let Some(until_ms) = status.install_mode_until_ms.filter(|until| *until > now_ms) {
        notes.push(PlayNote::InstallMode { until_ms });
    }
    if status.play_window_active {
        notes.push(PlayNote::UpdateWindow);
    }
    let effects =
        crate::models::admin_policy_effects(status.admin_policies_pending_json.as_deref());
    if !effects.is_empty() {
        notes.push(PlayNote::RestartOnce(effects));
    }
    if status.play_store_suspendable == Some(false) {
        notes.push(PlayNote::NotSuspendable {
            block_on: block_activity_start,
        });
    }
    notes
}

/// "0.23.6" from "0.23.6-debug": the version without its build suffix.
pub(crate) fn short_version(full: &str) -> &str {
    full.split(['-', '+']).next().unwrap_or(full).trim()
}

/// "1.2.3" from a versionCode `X*1_000_000 + Y*1_000 + Z` (the repo's release scheme, root
/// CLAUDE.md). A release candidate's code is one lower than its release's, so a code ending in
/// 999 reads as the next release, "0.3.0 (pre-release)" for 2999 (qa-31-s3-code N2; a real
/// x.y.999 would be mislabelled, which the scheme makes unlikely). The ⓘ has the code itself.
pub(crate) fn version_of_code(code: i64) -> Option<String> {
    let parts = |c: i64| format!("{}.{}.{}", c / 1_000_000, (c / 1_000) % 1_000, c % 1_000);
    (code > 0).then(|| {
        if code % 1_000 == 999 {
            format!("{} (pre-release)", parts(code + 1))
        } else {
            parts(code)
        }
    })
}

/// A crash row's label from the trace's first stored line: the exception's class without its
/// package ("IllegalStateException"). When `crashes::sanitize` dropped the exception line (a
/// message with spaces), the trace starts with a frame or a cause: then the whole line, trimmed
/// (qa-31-s3-code N3), never "kt" or "Caused by".
pub(crate) fn crash_label(first_line: &str) -> String {
    let line = first_line.trim();
    if first_line.starts_with("    at ")
        || line.starts_with("Caused by:")
        || line.starts_with("...")
    {
        return line.to_string();
    }
    line.split(':')
        .next()
        .and_then(|class| class.trim().rsplit('.').next())
        .filter(|name| !name.is_empty())
        .unwrap_or(line)
        .to_string()
}

/// One crash report on About › Diagnostics.
pub struct CrashView {
    /// The exception's class without its package ("IllegalStateException"): the row's label.
    pub name: String,
    pub count: i64,
    /// When it last happened (the phone's clock, local): "12 Oct".
    pub when: String,
    pub exact: String,
    pub build: i64,
    pub hash: String,
    pub trace: String,
}

#[derive(Template)]
#[template(path = "device_about.html")]
struct AboutTemplate {
    title: String,
    device: Device,
    connection: Vec<Row>,
    sound: Vec<Row>,
    software: Vec<Row>,
    crashes: Vec<CrashView>,
    /// "None in 30 days", "1 kind", "3 kinds".
    crashes_value: String,
    diagnostics: Vec<Row>,
}

/// The phone, or the page's answer: 404 for an unknown one, 500 when the database fails
/// (qa-31-s3-code N4: not "Device not found" for a DB error).
async fn load_device(state: &AppState, id: i64) -> Result<Device, (StatusCode, &'static str)> {
    match sqlx::query_as::<_, Device>("SELECT * FROM devices WHERE id = ?")
        .bind(id)
        .fetch_optional(&state.db)
        .await
    {
        Ok(Some(device)) => Ok(device),
        Ok(None) => Err((StatusCode::NOT_FOUND, "Device not found")),
        Err(err) => {
            tracing::error!(device_id = id, %err, "can't read the device");
            Err((
                StatusCode::INTERNAL_SERVER_ERROR,
                "Couldn't read this phone - check the server log.",
            ))
        }
    }
}

/// The phone's settings: a missing row reads as the defaults, a DB error is a 500 (qa-31-s4-code
/// L4: never "Child PIN · Off" because a read failed).
async fn load_policy(
    state: &AppState,
    id: i64,
) -> Result<DevicePolicy, (StatusCode, &'static str)> {
    match sqlx::query_as::<_, DevicePolicy>("SELECT * FROM device_policy WHERE device_id = ?")
        .bind(id)
        .fetch_optional(&state.db)
        .await
    {
        Ok(policy) => Ok(policy.unwrap_or(DevicePolicy {
            device_id: id,
            vpn_filter_enabled: true,
            ..Default::default()
        })),
        Err(err) => {
            tracing::error!(device_id = id, %err, "can't read the device's settings");
            Err((
                StatusCode::INTERNAL_SERVER_ERROR,
                "Couldn't read this phone - check the server log.",
            ))
        }
    }
}

/// The last report lists the launcher capability `name`.
fn has_capability(latest: Option<&DeviceStatus>, name: &str) -> bool {
    latest
        .and_then(|s| s.capabilities_json.as_deref())
        .is_some_and(|caps| caps.contains(&format!("\"{name}\"")))
}

async fn load_latest(state: &AppState, id: i64) -> Option<DeviceStatus> {
    sqlx::query_as::<_, DeviceStatus>(
        "SELECT * FROM device_status WHERE device_id = ? ORDER BY reported_at DESC, id DESC LIMIT 1",
    )
    .bind(id)
    .fetch_optional(&state.db)
    .await
    .ok()
    .flatten()
}

/// The Connection group's rows (§3.10): the state, the last contact, the last full report and the
/// network of the last request.
fn connection_rows(
    connection: &ConnectionState,
    // From the signals, not the state: a Disconnected phone has one too (qa-31-s3-code M1).
    last_contact: Option<DateTime<Utc>>,
    reported_at: Option<DateTime<Utc>>,
    last_access: Option<&crate::limits::LastAccess>,
    zone: &Zone,
    now: DateTime<Utc>,
) -> Vec<Row> {
    let mut rows = Vec::new();
    let mut status = Row::new(
        "status",
        "Status",
        match connection {
            ConnectionState::Online { since, .. } => format!("since {}", zone.when(*since, now)),
            ConnectionState::Disconnected => "· access removed".to_string(),
            _ => String::new(),
        },
    )
    .title(connection.online_since().map(|t| zone.exact(t)));
    status.lead = Some((connection.word(), connection.css()));
    rows.push(status);
    if matches!(connection, ConnectionState::NotSetUp) {
        return rows;
    }
    rows.push(match last_contact {
        Some(t) => {
            let relative = zone.relative(t, now);
            let value = if zone.local(t).date_naive() == zone.today(now) && t <= now {
                format!("{} · {relative}", zone.clock(t))
            } else {
                relative
            };
            Row::new("last-contact", "Last contact", value).title(Some(zone.exact(t)))
        }
        None => Row::new("last-contact", "Last contact", "Never"),
    });
    rows.push(match reported_at {
        Some(t) => Row::new("last-report", "Last full report", zone.when(t, now))
            .title(Some(zone.exact(t))),
        None => Row::new("last-report", "Last full report", "None yet"),
    });
    rows.push(match last_access {
        Some(access) => Row::new("network", "Network", access.via.label())
            .class(if access.via == crate::limits::Via::Public {
                "row-value-action"
            } else {
                ""
            })
            .detail(access.detail()),
        None => Row::new("network", "Network", "None since restart").detail(Some(
            "This server keeps the last request in memory, so it starts empty after a restart.",
        )),
    });
    rows
}

/// GET `/devices/{id}/about`: About This Phone.
pub async fn view_about(State(state): State<AppState>, Path(id): Path<i64>) -> Response {
    let device = match load_device(&state, id).await {
        Ok(device) => device,
        Err(answer) => return answer.into_response(),
    };
    let policy = match load_policy(&state, id).await {
        Ok(policy) => policy,
        Err(answer) => return answer.into_response(),
    };
    let latest = load_latest(&state, id).await;
    let zone = Zone::load(&state.db).await;
    let now = Utc::now();
    let now_ms = now.timestamp_millis();
    let connection = crate::handlers::devices::connection_of(&state, &device, now);
    let reported_at = latest
        .as_ref()
        .and_then(|s| crate::display_time::parse_db_utc(&s.reported_at));
    let connection_rows = connection_rows(
        &connection,
        crate::streams::last_contact(&crate::handlers::devices::connection_inputs(
            &state, &device,
        )),
        reported_at,
        state.limits.last_access(id).as_ref(),
        &zone,
        now,
    );

    let mut sound = Vec::new();
    let mut software = Vec::new();
    let mut diagnostics = Vec::new();
    if let Some(status) = latest.as_ref() {
        let sound_state = crate::sound_mode::state(
            status.ringer_mode.as_deref(),
            status.interruption_filter.as_deref(),
        );
        if let Some(ringer) = sound_state.ringer {
            sound.push(Row::new("ringer", "Ringer", ringer.label()).detail(ringer.detail()));
        }
        if let Some(dnd) = sound_state.dnd {
            sound.push(
                Row::new("do-not-disturb", "Do Not Disturb", dnd.label())
                    .class(if dnd.is_on() { "row-value-alarm" } else { "" })
                    .detail(dnd.detail()),
            );
        }

        if let Some(full) = status.app_version.as_deref().filter(|v| !v.is_empty()) {
            let short = short_version(full);
            software.push(
                Row::new("vibb", "Vibb", short)
                    .detail((short != full).then(|| format!("Full version: {full}."))),
            );
        }
        if let Some(music) = crate::music::parse_music_state(status.music_state_json.as_deref())
            && let Some(package) = music.package
        {
            let version = music.version_code.and_then(version_of_code);
            software.push(
                Row::new(
                    "music-app",
                    "Music app",
                    version.unwrap_or_else(|| "Installed".to_string()),
                )
                .detail(Some(match music.version_code {
                    Some(code) => format!("{package}, version code {code}."),
                    None => format!("{package}."),
                })),
            );
        }
        if let Some(policy_state) = status.policy_state.as_deref() {
            software.push(match PolicyProblem::parse(policy_state) {
                None => Row::new("settings", "Settings from server", "Applied"),
                Some(problem) => Row::new("settings", "Settings from server", problem.title())
                    .class("row-value-alarm")
                    .detail(Some(problem.detail())),
            });
        }
        match status.battery_optimization_exempt {
            Some(true) => software.push(Row::new("battery", "Battery optimisation", "Off")),
            Some(false) => software.push(
                Row::new("battery", "Battery optimisation", "On")
                    .class("row-value-action")
                    .detail(Some(
                        "Changes may arrive late while the phone sleeps. To fix it on the phone: \
                         Vibb's Settings › Pause all restrictions › Background connection › \
                         Allow, then end the pause. Or, with USB debugging allowed: adb shell \
                         dumpsys deviceidle whitelist +me.vibb.launcher",
                    )),
            ),
            None => {}
        }
    }

    // When "Block safe mode" went on, for the advice (QA 24b Low 12).
    let safe_boot_blocked_since: Option<i64> = sqlx::query_scalar(
        "SELECT disallow_safe_boot_set_at_ms FROM device_policy WHERE device_id = ?",
    )
    .bind(id)
    .fetch_optional(&state.db)
    .await
    .unwrap_or_else(|err| {
        tracing::error!(device_id = id, %err, "can't read when safe mode was blocked");
        None
    })
    .flatten();
    if device.enrolled_at.is_some() {
        let boots = latest.as_ref().and_then(|s| {
            crate::boot_witness::unseen(
                s.unseen_boots_count,
                s.unseen_boots_last_at_ms,
                now_ms,
                policy.hardening.disallow_safe_boot,
                safe_boot_blocked_since,
            )
        });
        diagnostics.push(match boots {
            Some(boots) => Row::new("starts", "Starts without Vibb", boots.value())
                .class("row-value-alarm")
                .detail(Some(boots.detail(&zone, now))),
            None => Row::new("starts", "Starts without Vibb", "None"),
        });
        if let Some(status) = latest.as_ref() {
            let report = status
                .time_state_json
                .as_deref()
                .and_then(crate::time_state::TimeReport::parse)
                .unwrap_or_else(|| {
                    crate::time_state::TimeReport::from_lock_reason(&status.lock_reason)
                });
            let value = if !report.locking() {
                "None".to_string()
            } else if let Some(name) = report.active_rule_name.clone() {
                name
            } else {
                match report.lock_reason.as_str() {
                    "BEDTIME" => "Bedtime",
                    "SCHOOL" => "School",
                    "SCREEN_TIME" => "Screen time used up",
                    "RULE" => "A time rule",
                    _ => "Locked",
                }
                .to_string()
            };
            diagnostics.push(
                Row::new("time-lock", "Time lock", value)
                    .title(reported_at.map(|t| format!("At the last report, {}", zone.exact(t)))),
            );
        }
    }

    let crashes: Vec<CrashView> = if device.enrolled_at.is_some() {
        crate::crashes::latest(&state.db, id)
            .await
            .into_iter()
            .map(|c| {
                let at = DateTime::from_timestamp_millis(c.last_at_ms);
                CrashView {
                    name: crash_label(c.exception()),
                    count: c.count,
                    when: at.map(|t| zone.when(t, now)).unwrap_or_default(),
                    exact: at.map(|t| zone.exact(t)).unwrap_or_default(),
                    build: c.app_version_code,
                    hash: c.hash,
                    trace: c.trace,
                }
            })
            .collect()
    } else {
        Vec::new()
    };
    let crashes_value = match crashes.len() {
        0 => "None in 30 days".to_string(),
        1 => "1 kind".to_string(),
        n => format!("{n} kinds"),
    };

    Html(
        AboutTemplate {
            title: format!("About {}", device.name),
            device,
            connection: connection_rows,
            sound,
            software,
            crashes,
            crashes_value,
            diagnostics,
        }
        .render()
        .unwrap(),
    )
    .into_response()
}

#[derive(Template)]
#[template(path = "device_advanced.html")]
struct AdvancedTemplate {
    title: String,
    device: Device,
    hardening: Hardening,
    /// The last report's `backup_service_enabled`: `Some(true)` a warning, `Some(false)` "Off",
    /// `None` (older launcher, unreadable) no row.
    backup_service_enabled: Option<bool>,
    block_activity_start: bool,
    /// App screens: warnings above the group, rows in it.
    play_warnings: Vec<Note>,
    play_rows: Vec<Note>,
    escapes: crate::kiosk_escapes::EscapesCard,
    escape_warnings: Vec<Note>,
    escape_rows: Vec<Note>,
    access: crate::handlers::devices::AccessCard,
}

/// GET `/devices/{id}/advanced`: Advanced.
pub async fn view_advanced(
    State(state): State<AppState>,
    Path(id): Path<i64>,
    Query(query): Query<std::collections::HashMap<String, String>>,
) -> Response {
    let device = match load_device(&state, id).await {
        Ok(device) => device,
        Err(answer) => return answer.into_response(),
    };
    let policy = match load_policy(&state, id).await {
        Ok(policy) => policy,
        Err(answer) => return answer.into_response(),
    };
    let latest = load_latest(&state, id).await;
    let zone = Zone::load(&state.db).await;
    let now = Utc::now();
    let now_ms = now.timestamp_millis();

    let play = play_card(&state, id, latest.as_ref(), now_ms).await;
    let (play_warnings, play_rows): (Vec<Note>, Vec<Note>) = play
        .notes
        .iter()
        .map(|n| n.note(&zone, now))
        .partition(|n| n.warning);
    let escapes = escapes_card(&policy, latest.as_ref(), now_ms);
    let (escape_warnings, escape_rows): (Vec<Note>, Vec<Note>) = escapes
        .notes
        .iter()
        .map(|n| n.note(&zone, now))
        .partition(|n| n.warning);

    Html(
        AdvancedTemplate {
            title: format!("Advanced · {}", device.name),
            hardening: policy.hardening.clone(),
            backup_service_enabled: latest.as_ref().and_then(|s| s.backup_service_enabled),
            block_activity_start: play.block_activity_start,
            play_warnings,
            play_rows,
            escapes,
            escape_warnings,
            escape_rows,
            access: crate::handlers::devices::access_card(
                &state,
                &device,
                query.get("notice").map(String::as_str),
                &zone,
                now,
            ),
            device,
        }
        .render()
        .unwrap(),
    )
    .into_response()
}

#[derive(Template)]
#[template(path = "device_lock.html")]
struct LockTemplate {
    title: String,
    device: Device,
    /// Child PIN: set or not, its length; the phone's lock as warnings (above the group) and rows.
    child: crate::kid_lock::LockCard,
    child_warnings: Vec<Note>,
    child_rows: Vec<Note>,
    /// The flash after a Child PIN save, `(text, refused)`; a refusal opens the form again.
    child_flash: Option<(&'static str, bool)>,
    child_open: bool,
    /// Parent PIN: set, easy, its flash, the phone's reports ("Recent use").
    parent: crate::handlers::parent_pin::ParentPinCard,
    /// A refused (or easy) Parent PIN opens its form again.
    parent_open: bool,
    /// Managed without a Parent PIN (the Attention alarm's facts).
    no_parent_pin: bool,
    /// An older launcher's "the offline override was used" (no PIN events).
    offline_override_used: bool,
    /// Screen: "Turn off after", the options, and what the phone last applied when it differs.
    screen_timeout_seconds: i64,
    screen_timeout_options: Vec<(i64, &'static str)>,
    screen_timeout_applied: Option<String>,
    /// Lock screen: the background's choices (the wallpapers ticked for this phone) and whether
    /// it is Home's.
    backgrounds: Vec<crate::handlers::wallpapers::DeviceWallpaperChoice>,
    background_same_as_home: bool,
    /// The message-text switch and the Notifications group.
    lock_screen: crate::lock_glance::LockScreenCard,
    lock_screen_warnings: Vec<Note>,
}

/// GET `/devices/{id}/lock`: Lock Screen & PINs (design 31 §3.2, step 4). The Child PIN
/// (`/kid-lock`: set in a `<details>`, remove behind a confirm), the Parent PIN (`/parent-pin`,
/// with what the phone reported), the screen timeout and the lock's background (inline selects),
/// the message-text switch and one switch per app (`/lock-screen`, `/lock-screen/apps`). The
/// forms keep their POST routes; they return here. A phone not set up yet has the page too: its
/// PINs can be set before setup.
pub async fn view_lock(
    State(state): State<AppState>,
    Path(id): Path<i64>,
    Query(query): Query<std::collections::HashMap<String, String>>,
) -> Response {
    let device = match load_device(&state, id).await {
        Ok(device) => device,
        Err(answer) => return answer.into_response(),
    };
    let policy = match load_policy(&state, id).await {
        Ok(policy) => policy,
        Err(answer) => return answer.into_response(),
    };
    let latest = load_latest(&state, id).await;
    let zone = Zone::load(&state.db).await;
    let now = Utc::now();
    let notice = query.get("notice").map(String::as_str);

    let child = crate::kid_lock::lock_card(
        &policy,
        crate::kid_lock::parse_lock_state(
            latest.as_ref().and_then(|s| s.lock_state_json.as_deref()),
        )
        .as_ref(),
        has_capability(latest.as_ref(), crate::kid_lock::PIN_LOCK_CAPABILITY),
        latest.is_some(),
        now.timestamp_millis(),
    );
    // The report's time on "On the phone", so an old report never reads as now (qa-31-s4-code L3).
    let reported = latest
        .as_ref()
        .and_then(|s| crate::display_time::parse_db_utc(&s.reported_at))
        .map(|t| zone.when(t, now));
    let (child_warnings, child_rows): (Vec<Note>, Vec<Note>) = child
        .notes
        .iter()
        .map(|n| n.note(reported.as_deref()))
        .partition(|n| n.warning);
    let parent = crate::handlers::parent_pin::card(
        &state.db,
        &policy,
        latest.as_ref(),
        notice,
        &zone,
        now,
        true,
    )
    .await;

    let backgrounds: Vec<_> = crate::handlers::wallpapers::device_choices(&state, id)
        .await
        .unwrap_or_else(|err| {
            tracing::error!(device_id = id, %err, "couldn't load the lock screen's backgrounds");
            Vec::new()
        })
        .into_iter()
        .filter(|w| w.checked)
        .collect();
    let rows = crate::handlers::devices::app_rows(&state, id, &policy, latest.as_ref()).await;
    let lock_screen =
        crate::handlers::devices::lock_screen_card(&state, &policy, latest.as_ref(), &rows).await;
    let screen_timeout_seconds =
        crate::models::screen_timeout_seconds(policy.screen_timeout_seconds);
    let child_flash = notice.and_then(crate::kid_lock::flash_text);

    Html(
        LockTemplate {
            title: format!("Lock Screen & PINs · {}", device.name),
            child_warnings,
            child_rows,
            child_flash,
            child_open: child_flash.is_some_and(|(_, refused)| refused),
            parent_open: parent.notice.is_some_and(|(_, warning)| warning),
            no_parent_pin: crate::handlers::devices::managed_without_pin(&policy),
            offline_override_used: latest.as_ref().is_some_and(|s| s.offline_override_used),
            screen_timeout_seconds,
            screen_timeout_options: crate::models::SCREEN_TIMEOUTS.to_vec(),
            // Only when the phone runs another value than the chosen one (it applies it at its
            // next sync).
            screen_timeout_applied: latest
                .as_ref()
                .and_then(|s| s.screen_timeout_seconds)
                .filter(|applied| *applied != screen_timeout_seconds)
                .map(crate::models::screen_timeout_label),
            background_same_as_home: !backgrounds.iter().any(|w| w.lock),
            backgrounds,
            lock_screen_warnings: lock_screen.notes.iter().map(|n| n.note()).collect(),
            lock_screen,
            child,
            parent,
            device,
        }
        .render()
        .unwrap(),
    )
    .into_response()
}

/// A read a sub-page can't do without: logged, and the page is a 500 - never an empty list that
/// reads as "nothing set" (qa-31-s4-code L4).
fn read_failed(id: i64, what: &str, err: sqlx::Error) -> Response {
    tracing::error!(device_id = id, %err, "can't read {what}");
    (
        StatusCode::INTERNAL_SERVER_ERROR,
        "Couldn't read this phone - check the server log.",
    )
        .into_response()
}

/// The last report says Vibb can't read notifications (`notification_listener_enabled` false):
/// Home Screen's warning, and the Attention row that links to it.
pub(crate) fn notification_access_note(latest: Option<&DeviceStatus>) -> Option<Note> {
    latest
        .is_some_and(|s| s.notification_listener_enabled == Some(false))
        .then(|| Note {
            warning: true,
            title: "Notification access off".to_string(),
            value: None,
            detail: Some(
                "Unread counts and the music card are off, and the lock screen shows only missed \
                 calls. Android can't turn this on for Vibb."
                    .to_string(),
            ),
            fix: Some(
                "With USB debugging allowed, run: adb shell cmd notification allow_listener \
                 me.vibb.launcher/com.kidslauncher.mdm.badges.BadgeListenerService (a debug build: \
                 me.vibb.launcher.debug; a launcher from before 2026-10-06: com.kidslauncher.mdm)."
                    .to_string(),
            ),
        })
}

#[derive(Template)]
#[template(path = "device_home.html")]
struct HomeTemplate {
    title: String,
    device: Device,
    /// Above the first group: Vibb can't read notifications.
    notification_warning: Option<Note>,
    launcher_language: String,
    home_columns: i64,
    /// Every wallpaper, ticked when this phone may use it.
    wallpapers: Vec<crate::handlers::wallpapers::DeviceWallpaperChoice>,
    /// The quick controls (`quick_controls_mask`).
    quick_wifi: bool,
    quick_bluetooth: bool,
    quick_brightness: bool,
    quick_sound: bool,
}

/// GET `/devices/{id}/home`: Home Screen (design 31 §3.2, step 5). Language and apps per row (one
/// form, `/launcher`), the wallpapers this phone may use (one form, `/wallpapers`) and the quick
/// controls (`/policy` with `section=quick`). Every form auto-saves and returns here without a
/// fragment. A phone not set up has the page too: what is saved reaches it with its first
/// settings.
pub async fn view_home(State(state): State<AppState>, Path(id): Path<i64>) -> Response {
    use crate::handlers::devices::{
        QUICK_CONTROL_BLUETOOTH, QUICK_CONTROL_BRIGHTNESS, QUICK_CONTROL_SOUND, QUICK_CONTROL_WIFI,
    };
    let device = match load_device(&state, id).await {
        Ok(device) => device,
        Err(answer) => return answer.into_response(),
    };
    let policy = match load_policy(&state, id).await {
        Ok(policy) => policy,
        Err(answer) => return answer.into_response(),
    };
    // A failed read must not render an empty grid: its Save would untick every wallpaper.
    let wallpapers = match crate::handlers::wallpapers::device_choices(&state, id).await {
        Ok(wallpapers) => wallpapers,
        Err(err) => return read_failed(id, "the wallpapers", err),
    };
    let latest = load_latest(&state, id).await;
    let mask = policy.quick_controls_mask;
    Html(
        HomeTemplate {
            title: format!("Home Screen · {}", device.name),
            notification_warning: notification_access_note(latest.as_ref()),
            launcher_language: policy.launcher_language.clone(),
            home_columns: policy.home_columns,
            wallpapers,
            quick_wifi: mask & QUICK_CONTROL_WIFI != 0,
            quick_bluetooth: mask & QUICK_CONTROL_BLUETOOTH != 0,
            quick_brightness: mask & QUICK_CONTROL_BRIGHTNESS != 0,
            quick_sound: mask & QUICK_CONTROL_SOUND != 0,
            device,
        }
        .render()
        .unwrap(),
    )
    .into_response()
}

/// One blocklist on a phone's Web Filter page.
pub struct FilterList {
    pub id: i64,
    pub name: String,
    /// The picker's value: "default" (as for everyone), "on" or "off".
    pub setting: &'static str,
    /// Whether the list is on for everyone (the "Default (On)" option).
    pub everyone_on: bool,
    /// The list's download problem, at most 4 words (`dns_filter::FeedState::problem`).
    pub problem: Option<&'static str>,
}

/// One blocked domain in the phone's log: the time local and relative, the exact one for
/// `title=`.
pub struct LogRow {
    pub domain: String,
    pub category: String,
    pub when: String,
    pub exact: String,
}

#[derive(Template)]
#[template(path = "device_filter.html")]
struct FilterTemplate {
    title: String,
    device: Device,
    filter_on: bool,
    lists: Vec<FilterList>,
    blocked: Vec<crate::models::DnsCustomDomain>,
    allowed: Vec<crate::models::DnsCustomDomain>,
    log_on: bool,
    retention_days: i64,
    events: Vec<LogRow>,
}

/// GET `/devices/{id}/filter`: Web Filter (design 31 §3.2, step 5). The filter switch (`/policy`
/// with `section=filter`), each blocklist as everyone has it or on/off for this phone (`/dns/
/// devices/{id}/blocklists/{list}/override`), the domains blocked and allowed on this phone only
/// (`/dns/domains/new`, `/dns/domains/{id}/delete`, with `back=filter`) and the blocked-domain log
/// (`/dns/log/{id}` and its newest 200 entries, local times). Every form returns here without a
/// fragment. `/dns?device=` and `/dns/log?device=` redirect here.
pub async fn view_filter(State(state): State<AppState>, Path(id): Path<i64>) -> Response {
    let device = match load_device(&state, id).await {
        Ok(device) => device,
        Err(answer) => return answer.into_response(),
    };
    let policy = match load_policy(&state, id).await {
        Ok(policy) => policy,
        Err(answer) => return answer.into_response(),
    };
    let lists = match sqlx::query_as::<_, (i64, String, bool, Option<bool>)>(
        "SELECT b.id, b.name, b.enabled, o.enabled FROM dns_blocklists b \
         LEFT JOIN device_blocklist_overrides o ON o.blocklist_id = b.id AND o.device_id = ? \
         ORDER BY b.name",
    )
    .bind(id)
    .fetch_all(&state.db)
    .await
    {
        Ok(rows) => rows,
        Err(err) => return read_failed(id, "the blocklists", err),
    };
    let lists = {
        let compiled = state.dns_compiled.read().await;
        lists
            .into_iter()
            .map(|(list_id, name, everyone_on, own)| FilterList {
                setting: match own {
                    None => "default",
                    Some(true) => "on",
                    Some(false) => "off",
                },
                everyone_on,
                problem: crate::handlers::dns_filter::FeedState::of(&compiled, list_id).problem(),
                id: list_id,
                name,
            })
            .collect()
    };
    let domains = match sqlx::query_as::<_, crate::models::DnsCustomDomain>(
        "SELECT * FROM dns_custom_domains WHERE device_id = ? ORDER BY domain",
    )
    .bind(id)
    .fetch_all(&state.db)
    .await
    {
        Ok(domains) => domains,
        Err(err) => return read_failed(id, "the phone's domains", err),
    };
    let (blocked, allowed): (Vec<_>, Vec<_>) =
        domains.into_iter().partition(|d| d.list_type == "block");
    let events = if policy.dns_log_enabled {
        match sqlx::query_as::<_, crate::models::DeviceDnsEvent>(
            "SELECT * FROM device_dns_events WHERE device_id = ? \
             ORDER BY blocked_at DESC LIMIT 200",
        )
        .bind(id)
        .fetch_all(&state.db)
        .await
        {
            Ok(events) => events,
            Err(err) => return read_failed(id, "the blocked-domain log", err),
        }
    } else {
        Vec::new()
    };
    let zone = Zone::load(&state.db).await;
    let now = Utc::now();
    let events = events
        .into_iter()
        .map(|e| {
            // The phone's own time (RFC 3339); one it sent unreadable says so, never the raw text.
            let at = crate::display_time::parse_db_utc(&e.blocked_at);
            LogRow {
                when: at.map_or_else(|| "Time unknown".to_string(), |t| zone.when(t, now)),
                exact: at.map(|t| zone.exact(t)).unwrap_or_default(),
                domain: e.domain,
                category: e.category,
            }
        })
        .collect();
    Html(
        FilterTemplate {
            title: format!("Web Filter · {}", device.name),
            filter_on: policy.vpn_filter_enabled,
            lists,
            blocked,
            allowed,
            log_on: policy.dns_log_enabled,
            retention_days: crate::retention::DNS_LOG_RETENTION_DAYS,
            events,
            device,
        }
        .render()
        .unwrap(),
    )
    .into_response()
}

/// The escapes card from the policy and the last report (capabilities, the fence, the filter,
/// the listener, the boot cover).
pub(crate) fn escapes_card(
    policy: &DevicePolicy,
    latest: Option<&DeviceStatus>,
    now_ms: i64,
) -> crate::kiosk_escapes::EscapesCard {
    use crate::kiosk_escapes as ke;
    let capability = |name: &str| has_capability(latest, name);
    let mut card = ke::escapes_card(
        policy.update_fence,
        policy.notification_auto_cancel,
        capability(ke::KIOSK_ESCAPES_CAPABILITY),
        latest.is_some(),
        ke::parse_update_fence(latest.and_then(|s| s.update_fence_json.as_deref())).as_ref(),
        ke::parse_notification_cancels(latest.and_then(|s| s.notification_cancels_json.as_deref()))
            .as_ref(),
        latest.and_then(|s| s.notification_listener_enabled),
        now_ms,
    );
    card.boot_cover = policy.boot_cover;
    // Design 16b (qa-16b-code #5): what the phone says about the boot cover.
    card.notes.extend(ke::boot_cover_notes(
        policy.boot_cover,
        capability(ke::BOOT_COVER_CAPABILITY),
        latest.is_some(),
        ke::parse_boot_cover(latest.and_then(|s| s.boot_cover_json.as_deref())).as_ref(),
    ));
    card
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn versions_read_short() {
        assert_eq!(short_version("0.23.6-debug"), "0.23.6");
        assert_eq!(short_version("0.23.6"), "0.23.6");
        assert_eq!(short_version("1.0.0+build.5"), "1.0.0");
        assert_eq!(version_of_code(3_000), Some("0.3.0".to_string()));
        assert_eq!(version_of_code(1_002_003), Some("1.2.3".to_string()));
        assert_eq!(version_of_code(23_006_000), Some("23.6.0".to_string()));
        // A release candidate: one below its release.
        assert_eq!(
            version_of_code(2_999),
            Some("0.3.0 (pre-release)".to_string())
        );
        assert_eq!(
            version_of_code(999_999),
            Some("1.0.0 (pre-release)".to_string())
        );
        assert_eq!(version_of_code(0), None);
        assert_eq!(version_of_code(-5), None);
    }

    #[test]
    fn crash_labels_are_the_class_name_or_the_whole_line() {
        assert_eq!(
            crash_label("java.lang.IllegalStateException"),
            "IllegalStateException"
        );
        assert_eq!(crash_label("a.b.Boom: something"), "Boom");
        // The exception line was dropped: the first frame or cause, whole.
        assert_eq!(
            crash_label("    at com.x.Foo.bar(Foo.kt:3)"),
            "at com.x.Foo.bar(Foo.kt:3)"
        );
        assert_eq!(
            crash_label("Caused by: java.io.IOException"),
            "Caused by: java.io.IOException"
        );
    }

    #[test]
    fn policy_problems_have_short_titles() {
        assert_eq!(PolicyProblem::parse("ok"), None);
        for (state, problem) in [
            ("cache_corrupt", PolicyProblem::CacheCorrupt),
            ("server_error", PolicyProblem::ServerError),
            ("rejected_suspect", PolicyProblem::RejectedSuspect),
            ("fresh_decode_failed", PolicyProblem::FreshDecodeFailed),
        ] {
            let parsed = PolicyProblem::parse(state).unwrap();
            assert_eq!(parsed, problem);
            assert!(parsed.title().split_whitespace().count() <= 4);
            assert!(!parsed.detail().is_empty());
        }
        let other = PolicyProblem::parse("weird").unwrap();
        assert_eq!(other.title(), "Problem reported");
        assert!(other.detail().contains("weird"));
        assert!(
            PolicyProblem::ServerError
                .detail()
                .contains("failed to build device policy")
        );
    }

    #[test]
    fn play_notes_follow_the_report() {
        assert!(play_notes(None, true, 0).is_empty());
        let status = DeviceStatus {
            install_mode_until_ms: Some(10_000),
            play_window_active: true,
            admin_policies_pending_json: Some(r#"["disable-camera","wipe-data"]"#.to_string()),
            play_store_suspendable: Some(false),
            ..Default::default()
        };
        let notes = play_notes(Some(&status), false, 5_000);
        assert_eq!(
            notes,
            vec![
                PlayNote::InstallMode { until_ms: 10_000 },
                PlayNote::UpdateWindow,
                PlayNote::RestartOnce(vec![
                    "keep the power button's camera gesture from opening the camera over the lock"
                ]),
                PlayNote::NotSuspendable { block_on: false },
            ]
        );
        // Install mode that has ended: no note.
        let later = play_notes(Some(&status), true, 10_000);
        assert!(
            !later
                .iter()
                .any(|n| matches!(n, PlayNote::InstallMode { .. }))
        );
        assert!(later.contains(&PlayNote::NotSuspendable { block_on: true }));
        let zone = Zone::from_setting("Europe/Oslo");
        let now = DateTime::from_timestamp_millis(5_000).unwrap();
        let words: Vec<Note> = notes.iter().map(|n| n.note(&zone, now)).collect();
        assert_eq!(words[0].title, "Play Store open until 01:00");
        assert!(words[0].warning);
        assert!(!words[1].warning);
        assert_eq!(words[2].title, "Restart the phone once");
        assert!(
            words[3]
                .detail
                .as_deref()
                .unwrap()
                .contains("screens is off, so apps can open")
        );
    }
}
