//! A phone's Apps, Music, About This Phone, Lock Screen & PINs, Home Screen, Web Filter and
//! Advanced pages (design 31 §3.2, §8 steps 3-7).
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
//! - **Apps** (`GET /devices/{id}/apps`, `device_apps.html`, step 6): Allowed and Not allowed,
//!   each app a switch with its name and icon under it, and the Wi-Fi-only download switch
//!   ([view_apps]).
//! - **Music** (`GET /devices/{id}/music`, `device_music.html`, step 7): the music app, the
//!   library's switches, mobile data, the storage limit, the volume cap, Storytel, Sonos and what
//!   the phone reported ([view_music]; the parts are `music::device_card`'s).
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
    pub(crate) fn new(key: &'static str, label: &str, value: impl Into<String>) -> Row {
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

    pub(crate) fn detail(mut self, detail: Option<impl Into<String>>) -> Row {
        self.detail = detail.map(Into::into);
        self
    }

    fn title(mut self, title: Option<String>) -> Row {
        self.title = title;
        self
    }

    pub(crate) fn class(mut self, class: &'static str) -> Row {
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
                the last app list it had (or nothing but Vibb One), and takes no changes until it gets \
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
                this server; Vibb One and the server versions probably don't match. It keeps its \
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
                    "After a Vibb One update, Android hasn't yet given Vibb One what it needs to {}.",
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

/// The music app's first versionCode under today's rule (`music-v0.5.0-rc.4`: `MUSIC_FIRST` in
/// `scripts/release-version.sh`); every code of the old rule is below it. The shared contract's
/// `music_codes` rows (`testdata/launcher_tag_codes.json`, also checked by the script's
/// `--selftest`) tie the two.
const MUSIC_NEW_RULE_FROM: i64 = 50_004;

/// The music app's version from its versionCode (the repo's release scheme, root CLAUDE.md). Today's
/// rule, from [MUSIC_NEW_RULE_FROM] on: `X*10_000_000 + Y*10_000 + Z*10 + N`, N the RC number (1..8)
/// or 9 for the release - "0.5.0-rc.4" for 50004, "1.2.3" for 10020039; a code ending in 0 is no
/// release build (`None`). Below it the old rule, `X*1_000_000 + Y*1_000 + Z` with an RC one lower,
/// so a code ending in 999 reads as the next release, "0.3.0 (pre-release)" for 2999 (qa-31-s3-code
/// N2; a real x.y.999 would be mislabelled, which the scheme made unlikely). The ⓘ has the code itself.
pub(crate) fn version_of_code(code: i64) -> Option<String> {
    if code >= MUSIC_NEW_RULE_FROM {
        let version = format!(
            "{}.{}.{}",
            code / 10_000_000,
            (code / 10_000) % 1_000,
            (code / 10) % 1_000
        );
        return match code % 10 {
            0 => None,
            9 => Some(version),
            n => Some(format!("{version}-rc.{n}")),
        };
    }
    let parts = |c: i64| format!("{}.{}.{}", c / 1_000_000, (c / 1_000) % 1_000, c % 1_000);
    (code > 0).then(|| {
        if code % 1_000 == 999 {
            format!("{} (pre-release)", parts(code + 1))
        } else {
            parts(code)
        }
    })
}

/// The music app as the phone reported it: its version from the versionCode ("Installed" without
/// one), the package and the code behind ⓘ (§4.2). About This Phone (`key` "music-app") and
/// Device › Music ("app") show the same row (qa-31-s7-code N2).
pub(crate) fn music_app_row(key: &'static str, music: &crate::music::MusicState) -> Option<Row> {
    let package = music.package.as_deref()?;
    let version = music.version_code.and_then(version_of_code);
    Some(
        Row::new(
            key,
            "Music app",
            version.unwrap_or_else(|| "Installed".to_string()),
        )
        .detail(Some(match music.version_code {
            Some(code) => format!("{package}, version code {code}."),
            None => format!("{package}."),
        })),
    )
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
pub(crate) async fn load_device(
    state: &AppState,
    id: i64,
) -> Result<Device, (StatusCode, &'static str)> {
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
pub(crate) async fn load_policy(
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

/// The phone's last report (`None` before the first one); a DB error is the page's 500 - never
/// "no report yet", which would hide every reported app and state (design 31 step 6).
pub(crate) async fn load_latest(
    state: &AppState,
    id: i64,
) -> Result<Option<DeviceStatus>, sqlx::Error> {
    sqlx::query_as::<_, DeviceStatus>(
        "SELECT * FROM device_status WHERE device_id = ? ORDER BY reported_at DESC, id DESC LIMIT 1",
    )
    .bind(id)
    .fetch_optional(&state.db)
    .await
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
    let latest = match load_latest(&state, id).await {
        Ok(latest) => latest,
        Err(err) => return read_failed(id, "the last report", err),
    };
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
                Row::new("vibb", "Vibb One", short)
                    .detail((short != full).then(|| format!("Full version: {full}."))),
            );
        }
        if let Some(row) = crate::music::parse_music_state(status.music_state_json.as_deref())
            .and_then(|music| music_app_row("music-app", &music))
        {
            software.push(row);
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
                         Vibb One's Settings › Pause all restrictions › Background connection › \
                         Allow, then end the pause. Or, with USB debugging allowed: adb shell \
                         dumpsys deviceidle whitelist +me.vibb.launcher",
                    )),
            ),
            None => {}
        }
        match status.write_settings_granted {
            Some(true) => software.push(Row::new(
                "write-settings",
                "Modify system settings",
                "Allowed",
            )),
            Some(false) => software.push(
                Row::new("write-settings", "Modify system settings", "Not allowed")
                    .class("row-value-action")
                    .detail(Some(format!(
                        "The touch sounds switches and the clock format need it. {WRITE_SETTINGS_STEPS}"
                    ))),
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
            Some(boots) => Row::new("starts", "Starts without Vibb One", boots.value())
                .class("row-value-alarm")
                .detail(Some(boots.detail(&zone, now))),
            None => Row::new("starts", "Starts without Vibb One", "None"),
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
    let latest = match load_latest(&state, id).await {
        Ok(latest) => latest,
        Err(err) => return read_failed(id, "the last report", err),
    };
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
    /// Design 59 §2.3: Android's lock is chosen (the switch, the ⓘ's words).
    keyguard_android: bool,
    /// "Send the Child PIN again" ([crate::kid_lock::offers_send_again]).
    send_again: bool,
    /// What "Show Child PIN…" showed on this page (design 59 §2.2): the PIN or why there is
    /// none; `None` on a GET and after a wrong password, when the form shows (with the error).
    reveal_shown: Option<RevealView>,
    reveal_error: Option<String>,
    /// The page answers Show Child PIN: `static/reveal.js` turns its history entry into a GET
    /// (a reload never posts the password again) and hides the PIN when the page is left.
    reveal_answer: bool,
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
    /// "Wake the screen for a notification" (launcher round 7 §3, `/lock-wake`).
    lock_wake: bool,
    /// The Notifications group: one switch per app and, on a ticked one, its text switch.
    lock_screen: crate::lock_glance::LockScreenCard,
    lock_screen_warnings: Vec<Note>,
}

/// "Show Child PIN…"'s answer (design 59 §2.2): rendered into the page the POST came from, never
/// a redirect, so the PIN is in no address, session or log.
pub(crate) enum Reveal {
    /// A wrong password; `left` more before the session ends.
    Wrong {
        left: i64,
    },
    Shown(crate::kid_lock::ShownPin),
}

/// [Reveal] in the page's words.
struct RevealView {
    /// The PIN's digits, shown.
    pin: Option<String>,
    /// "Child PIN" or "New Child PIN".
    label: &'static str,
    /// The sentence under the PIN, and the PIN the phone still has (its digits, after it).
    line: Option<&'static str>,
    prev: Option<String>,
    /// Why nothing can be shown (no copy here, no key).
    message: Option<&'static str>,
    /// A wrong password: the error under the field.
    error: Option<String>,
}

impl RevealView {
    fn of(reveal: Reveal) -> RevealView {
        use crate::kid_lock::{PhoneHas, ShownPin};
        let mut view = RevealView {
            pin: None,
            label: "Child PIN",
            line: None,
            prev: None,
            message: None,
            error: None,
        };
        match reveal {
            Reveal::Wrong { left } => {
                view.error = Some(match left {
                    1 => "Wrong password. One more wrong one signs you out.".to_string(),
                    _ => "Wrong password.".to_string(),
                });
            }
            Reveal::Shown(ShownPin::Pin { pin, phone }) => {
                view.pin = Some(pin.as_str().to_string());
                match phone {
                    PhoneHas::This => {}
                    PhoneHas::Earlier(Some(prev)) => {
                        view.label = "New Child PIN";
                        view.line = Some("Until the phone gets it, its PIN is still");
                        view.prev = Some(prev.as_str().to_string());
                    }
                    PhoneHas::Earlier(None) => {
                        view.label = "New Child PIN";
                        view.line =
                            Some("The phone still has an earlier PIN until it gets this one.");
                    }
                    PhoneHas::Changed => {
                        view.line =
                            Some("The phone's PIN was changed on the phone: it isn't this one.");
                    }
                }
            }
            Reveal::Shown(ShownPin::NotStored) => {
                view.message =
                    Some("Not stored on this server. Set the Child PIN again to see it here.");
            }
            Reveal::Shown(ShownPin::Unreadable) => {
                view.message = Some(
                    "Not readable on this server: it was kept with another server's key. Set the \
                     Child PIN again to see it here.",
                );
            }
            Reveal::Shown(ShownPin::NoKey) => {
                view.message = Some(
                    "This server can't read its key file, so it keeps no Child PIN to show. The \
                     server log says why.",
                );
            }
        }
        view
    }
}

/// GET `/devices/{id}/lock`: Lock Screen & PINs (design 31 §3.2, step 4). The Child PIN
/// (`/kid-lock`: set in a `<details>`, remove behind a confirm; design 59: the lock switch
/// `/kid-lock/keyguard`, "Show Child PIN…" posting here, "Send the Child PIN again"
/// `/kid-pin/send`), the Parent PIN (`/parent-pin`, with what the phone reported), the screen
/// timeout and the lock's background (inline selects), one select per app - Off, Name only, Full
/// text (`/lock-screen/apps`). The forms return here. A phone not set up yet has the page too: its
/// PINs can be set before setup.
pub async fn view_lock(
    State(state): State<AppState>,
    Path(id): Path<i64>,
    Query(query): Query<std::collections::HashMap<String, String>>,
) -> Response {
    let notice = query.get("notice").map(String::as_str);
    render_lock(&state, id, notice, None).await
}

/// POST `/devices/{id}/lock`, `action=reveal_kid_pin` (design 59 §2.2, qa-59-design Q59-11): the
/// parent's password through [crate::admin_auth::reauth] (three wrong in a row end the session),
/// then the page itself with the Child PIN in its group - posted to the page's own path, so
/// `scroll-restore.js` keeps the place. No redirect: the PIN is in no address. `no_store_path`
/// covers the answer, and the security log names the phone only.
pub async fn lock_action(
    State(state): State<AppState>,
    Path(id): Path<i64>,
    session: tower_sessions::Session,
    axum::Extension(crate::security::CurrentAdmin(admin)): axum::Extension<
        crate::security::CurrentAdmin,
    >,
    axum::Form(form): axum::Form<std::collections::HashMap<String, String>>,
) -> Response {
    if form.get("action").map(String::as_str) != Some("reveal_kid_pin") {
        return (
            StatusCode::BAD_REQUEST,
            "This page is out of date - reload it.",
        )
            .into_response();
    }
    let device = match load_device(&state, id).await {
        Ok(device) => device,
        Err(answer) => return answer.into_response(),
    };
    let password = form.get("password").map(String::as_str).unwrap_or_default();
    let log = |event: &'static str| {
        let (db, username) = (state.db.clone(), admin.username.clone());
        async move {
            crate::security::record_security_event(
                &db,
                event,
                Some(&username),
                None,
                Some(&format!("device {id}")),
            )
            .await;
        }
    };
    let reveal = match crate::admin_auth::reauth(&state, &session, &admin, password).await {
        crate::admin_auth::Reauth::Busy => return crate::admin_auth::busy_response(),
        crate::admin_auth::Reauth::SignedOut => {
            log("kid_pin_reveal_refused").await;
            return crate::admin_auth::signed_out_response();
        }
        crate::admin_auth::Reauth::Wrong { left } => {
            log("kid_pin_reveal_refused").await;
            Reveal::Wrong { left }
        }
        crate::admin_auth::Reauth::Right => {
            let latest = match load_latest(&state, id).await {
                Ok(latest) => latest,
                Err(err) => return read_failed(id, "the last report", err),
            };
            let kept = match crate::kid_lock::KeptPin::load(&state.db, id).await {
                Ok(kept) => kept,
                Err(err) => return read_failed(id, "the Child PIN", err),
            };
            let lock_state = crate::kid_lock::parse_lock_state(
                latest.as_ref().and_then(|s| s.lock_state_json.as_deref()),
            );
            // What the phone has, from this install's reports only (S2P-4).
            let phone = phone_lock(
                since_enrolment(device.enrolled_at.as_deref(), latest.as_ref()),
                lock_state.as_ref(),
                false,
            );
            let shown = crate::kid_lock::shown_pin(state.kid_pin_key.as_deref(), &kept, id, &phone);
            log("kid_pin_shown").await;
            Reveal::Shown(shown)
        }
    };
    render_lock(&state, id, None, Some(reveal)).await
}

/// What the last report says about the lock ([crate::kid_lock::PhoneLock]).
fn phone_lock<'a>(
    latest: Option<&DeviceStatus>,
    state: Option<&'a crate::kid_lock::LockState>,
    pin_waiting: bool,
) -> crate::kid_lock::PhoneLock<'a> {
    crate::kid_lock::PhoneLock {
        state,
        reported: latest.is_some(),
        capable: has_capability(latest, crate::kid_lock::PIN_LOCK_CAPABILITY),
        android_capable: has_capability(latest, crate::kid_lock::ANDROID_KEYGUARD_CAPABILITY),
        pin_waiting,
    }
}

/// The last report when it came from this enrollment (`reported_at >= devices.enrolled_at`, the
/// text comparison `models::ENROLLED_AT` makes in SQL), else `None`: an earlier install's.
fn since_enrolment<'a>(
    enrolled_at: Option<&str>,
    latest: Option<&'a DeviceStatus>,
) -> Option<&'a DeviceStatus> {
    latest.filter(|s| s.reported_at.as_str() >= enrolled_at.unwrap_or(""))
}

/// The newest `set_kid_pin` of this enrollment, as the Child PIN group shows it.
async fn newest_pin_send(
    state: &AppState,
    id: i64,
    zone: &Zone,
    now: DateTime<Utc>,
) -> Result<Option<crate::kid_lock::PinSend>, sqlx::Error> {
    let newest: Option<crate::models::DeviceCommand> = sqlx::query_as(&format!(
        "SELECT * FROM device_commands WHERE device_id = ?1 AND command = ?2 \
         AND requested_at >= {} ORDER BY id DESC LIMIT 1",
        crate::models::ENROLLED_AT
    ))
    .bind(id)
    .bind(crate::kid_lock::SET_KID_PIN)
    .fetch_optional(&state.db)
    .await?;
    let Some(newest) = newest else {
        return Ok(None);
    };
    let receipts = crate::handlers::device_api::sends_command_receipts(&state.db, id).await?;
    Ok(crate::kid_lock::pin_send(&newest, receipts, zone, now))
}

/// Lock Screen & PINs, with `notice`'s flash and, after "Show Child PIN…", its answer (a wrong
/// password is a 400).
async fn render_lock(
    state: &AppState,
    id: i64,
    notice: Option<&str>,
    reveal: Option<Reveal>,
) -> Response {
    let device = match load_device(state, id).await {
        Ok(device) => device,
        Err(answer) => return answer.into_response(),
    };
    let policy = match load_policy(state, id).await {
        Ok(policy) => policy,
        Err(answer) => return answer.into_response(),
    };
    let latest = match load_latest(state, id).await {
        Ok(latest) => latest,
        Err(err) => return read_failed(id, "the last report", err),
    };
    let zone = Zone::load(&state.db).await;
    let now = Utc::now();

    let lock_state = crate::kid_lock::parse_lock_state(
        latest.as_ref().and_then(|s| s.lock_state_json.as_deref()),
    );
    let keyguard_android = crate::kid_lock::android_requested(&policy);
    // Design 59: the newest set_kid_pin, shown while Android's lock is chosen.
    let pin_send = if keyguard_android {
        match newest_pin_send(state, id, &zone, now).await {
            Ok(send) => send,
            Err(err) => return read_failed(id, "the Child PIN's command", err),
        }
    } else {
        None
    };
    let phone = phone_lock(
        latest.as_ref(),
        lock_state.as_ref(),
        pin_send == Some(crate::kid_lock::PinSend::Waiting),
    );
    let child = crate::kid_lock::lock_card(&policy, &phone, now.timestamp_millis());
    // "Send again" reads only this install's reports, as the queue rule does (qa-59-step2-code
    // S2P-4, step 1's S59-3): before a re-enrolled phone's first report, no button.
    let send_again = crate::kid_lock::offers_send_again(
        &policy,
        &phone_lock(
            since_enrolment(device.enrolled_at.as_deref(), latest.as_ref()),
            lock_state.as_ref(),
            phone.pin_waiting,
        ),
        pin_send.as_ref(),
    );
    // The report's time on "On the phone", so an old report never reads as now (qa-31-s4-code L3).
    let reported = latest
        .as_ref()
        .and_then(|s| crate::display_time::parse_db_utc(&s.reported_at))
        .map(|t| zone.when(t, now));
    let (child_warnings, mut child_rows): (Vec<Note>, Vec<Note>) = child
        .notes
        .iter()
        .map(|n| n.note(reported.as_deref()))
        .partition(|n| n.warning);
    if let Some(send) = pin_send.filter(|_| child.pin_set) {
        child_rows.push(crate::kid_lock::LockNote::PinSent(send).note(None));
    }
    let parent =
        crate::handlers::parent_pin::card(&state.db, &policy, latest.as_ref(), notice, &zone, now)
            .await;

    let backgrounds: Vec<_> = crate::handlers::wallpapers::device_choices(state, id)
        .await
        .unwrap_or_else(|err| {
            tracing::error!(device_id = id, %err, "couldn't load the lock screen's backgrounds");
            Vec::new()
        })
        .into_iter()
        .filter(|w| w.checked)
        .collect();
    let rows = match crate::handlers::devices::app_rows(state, id, &policy, latest.as_ref()).await {
        Ok(rows) => rows,
        Err(err) => return read_failed(id, "the phone's apps", err),
    };
    let lock_screen =
        crate::handlers::devices::lock_screen_card(state, &policy, latest.as_ref(), &rows).await;
    // Design r7 §1.1.3: which apps rang over the lock, named as the Apps page names them.
    let labels: std::collections::HashMap<String, String> = rows
        .apps
        .iter()
        .map(|app| (app.package_name.clone(), app.label.clone()))
        .collect();
    child_rows.extend(
        crate::kid_lock::rang_notes(&child, lock_state.as_ref(), &labels, &zone, now)
            .iter()
            .map(|n| n.note(None)),
    );
    let screen_timeout_seconds =
        crate::models::screen_timeout_seconds(policy.screen_timeout_seconds);
    let child_flash = notice.and_then(crate::kid_lock::flash_text);
    let reveal_answer = reveal.is_some();
    let mut reveal_view = reveal.map(RevealView::of);
    let reveal_error = reveal_view.as_mut().and_then(|r| r.error.take());
    let reveal_shown = reveal_view.filter(|r| r.pin.is_some() || r.message.is_some());
    let status = if reveal_error.is_some() {
        StatusCode::BAD_REQUEST
    } else {
        StatusCode::OK
    };

    let page = LockTemplate {
        title: format!("Lock Screen & PINs · {}", device.name),
        child_warnings,
        child_rows,
        child_flash,
        // A refusal opens the Child PIN's form again - not "Not sent", which it can't fix.
        child_open: child_flash.is_some_and(|(_, refused)| refused)
            && notice != Some("pin_not_sent"),
        keyguard_android,
        send_again,
        reveal_answer,
        reveal_error,
        reveal_shown,
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
        lock_wake: policy.lock_wake_on_notification,
        lock_screen_warnings: lock_screen.notes.iter().map(|n| n.note()).collect(),
        lock_screen,
        child,
        parent,
        device,
    };
    (status, Html(page.render().unwrap())).into_response()
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
                 calls."
                    .to_string(),
            ),
            // The second sentence lives behind the ⓘ (§4.2: a title and one sentence;
            // qa-31-s5-code N2).
            fix: Some(
                "Android can't turn this on for Vibb One. With USB debugging allowed, run: adb shell cmd notification allow_listener \
                 me.vibb.launcher/com.kidslauncher.mdm.badges.BadgeListenerService (a debug build: \
                 me.vibb.launcher.debug; a launcher from before 2026-10-06: com.kidslauncher.mdm)."
                    .to_string(),
            ),
        })
}

/// How to grant Vibb One "Modify system settings" (PLAN fix backlog, 2026-10-24): the on-phone
/// path first - Vibb One's Settings row that opens Android's page -, adb as the shortcut. About
/// This Phone's row (after a line saying what needs it) and the clock format's warning (code QA Q-9).
const WRITE_SETTINGS_STEPS: &str = "To fix it on the phone: Vibb One's Settings › Pause all \
     restrictions › Modify system settings › turn on Allow, then end the pause. Or, with USB \
     debugging allowed: adb shell appops set me.vibb.launcher WRITE_SETTINGS allow (a debug build: \
     me.vibb.launcher.debug).";

/// The phone should show the parent's clock format but can't (design 34 P12): the policy asks
/// for "24" or "12" and the last report says `clock_format_applied` false - no "Modify system
/// settings" access, or Android refused the write. Home Screen's warning above the Language
/// group; the adb grant and the Settings path behind its ⓘ. "Follow the phone" never warns, nor
/// does an older launcher that doesn't report it.
pub(crate) fn clock_format_note(
    policy: &DevicePolicy,
    latest: Option<&DeviceStatus>,
) -> Option<Note> {
    (crate::models::clock_format(&policy.clock_format) != "system"
        && latest.is_some_and(|s| s.clock_format_applied == Some(false)))
    .then(|| Note {
        warning: true,
        title: "Clock format not applied".to_string(),
        value: None,
        detail: Some("The phone can't change its clock format yet.".to_string()),
        fix: Some(format!(
            "Vibb One needs Android's \"Modify system settings\" access, which a device owner can't \
             grant. {WRITE_SETTINGS_STEPS}"
        )),
    })
}

#[derive(Template)]
#[template(path = "device_home.html")]
struct HomeTemplate {
    title: String,
    device: Device,
    /// Above the first group: Vibb can't read notifications.
    notification_warning: Option<Note>,
    /// Above the first group too: the phone can't apply the chosen clock format (design 34 P12).
    clock_warning: Option<Note>,
    launcher_language: String,
    home_columns: i64,
    /// "system", "24" or "12".
    clock_format: &'static str,
    /// Every wallpaper, ticked when this phone may use it.
    wallpapers: Vec<crate::handlers::wallpapers::DeviceWallpaperChoice>,
    /// The quick controls (`quick_controls_mask`).
    quick_wifi: bool,
    quick_bluetooth: bool,
    quick_brightness: bool,
    quick_sound: bool,
    quick_touch: bool,
}

/// GET `/devices/{id}/home`: Home Screen (design 31 §3.2, step 5). Language and apps per row (one
/// form, `/launcher`), the wallpapers this phone may use (one form, `/wallpapers`) and the quick
/// controls (`/policy` with `section=quick`). Every form auto-saves and returns here without a
/// fragment. A phone not set up has the page too: what is saved reaches it with its first
/// settings.
pub async fn view_home(State(state): State<AppState>, Path(id): Path<i64>) -> Response {
    use crate::handlers::devices::{
        QUICK_CONTROL_BLUETOOTH, QUICK_CONTROL_BRIGHTNESS, QUICK_CONTROL_SOUND,
        QUICK_CONTROL_TOUCH, QUICK_CONTROL_WIFI,
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
    let latest = match load_latest(&state, id).await {
        Ok(latest) => latest,
        Err(err) => return read_failed(id, "the last report", err),
    };
    let mask = policy.quick_controls_mask;
    Html(
        HomeTemplate {
            title: format!("Home Screen · {}", device.name),
            notification_warning: notification_access_note(latest.as_ref()),
            clock_warning: clock_format_note(&policy, latest.as_ref()),
            launcher_language: policy.launcher_language.clone(),
            home_columns: policy.home_columns,
            clock_format: crate::models::clock_format(&policy.clock_format),
            wallpapers,
            quick_wifi: mask & QUICK_CONTROL_WIFI != 0,
            quick_bluetooth: mask & QUICK_CONTROL_BLUETOOTH != 0,
            quick_brightness: mask & QUICK_CONTROL_BRIGHTNESS != 0,
            quick_sound: mask & QUICK_CONTROL_SOUND != 0,
            quick_touch: mask & QUICK_CONTROL_TOUCH != 0,
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
    /// The group whose add row was refused ("blocked", "allowed"), else "".
    refused: &'static str,
    /// The phone plays as a Spotify account: Spotify's hosts are never blocked on it (design 26c
    /// §6.2), whatever the lists and domains say.
    spotify_on: bool,
    /// This phone's blocked domains that binding overrides (their ids; qa-26c-step3-code L5: said
    /// where the parent types the block).
    spotify_overridden: Vec<i64>,
    /// The site-wide blocked domains it overrides on this phone.
    spotify_global: Vec<String>,
}

impl FilterTemplate {
    /// This phone's block `id` is one of Spotify's hosts while the phone plays Spotify.
    fn overridden(&self, id: &i64) -> bool {
        self.spotify_overridden.contains(id)
    }
}

/// GET `/devices/{id}/filter`: Web Filter (design 31 §3.2, step 5). The filter switch (`/policy`
/// with `section=filter`), each blocklist as everyone has it or on/off for this phone (`/dns/
/// devices/{id}/blocklists/{list}/override`), the domains blocked and allowed on this phone only
/// (`/dns/domains/new`, `/dns/domains/{id}/delete`, with `back=filter`) and the blocked-domain log
/// (`/dns/log/{id}` and its newest 200 entries, local times). Every form returns here without a
/// fragment, except a refused domain: `?notice=not_a_domain_block|allow#blocked|allowed`, the
/// refusal under its group's add row (qa-31-s5-code L1). `/dns?device=` and `/dns/log?device=`
/// redirect here.
pub async fn view_filter(
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
    // A phone that plays Spotify never gets Spotify's hosts as blocked (the delivered list and the
    // launcher's filter, design 26c §6.2): say so by the blocks it overrides.
    let spotify_on = policy.music_spotify_account.is_some();
    let spotify_overridden: Vec<i64> = blocked
        .iter()
        .filter(|d| spotify_on && crate::spotify::is_spotify_host(&d.domain))
        .map(|d| d.id)
        .collect();
    let spotify_global: Vec<String> = if spotify_on {
        match sqlx::query_scalar::<_, String>(
            "SELECT domain FROM dns_custom_domains \
             WHERE device_id IS NULL AND list_type = 'block' ORDER BY domain",
        )
        .fetch_all(&state.db)
        .await
        {
            Ok(domains) => domains
                .into_iter()
                .filter(|d| crate::spotify::is_spotify_host(d))
                .collect(),
            Err(err) => return read_failed(id, "the site-wide domains", err),
        }
    } else {
        Vec::new()
    };
    let events = if policy.dns_log_enabled {
        match sqlx::query_as::<_, crate::models::DeviceDnsEvent>(
            "SELECT * FROM device_dns_events WHERE device_id = ? \
             ORDER BY id DESC LIMIT 200",
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
    // The phone's own time (RFC 3339); newest first, one it sent unreadable last ("Time unknown",
    // never the raw text) - not first, as the text's order put it (qa-31-s5-code N5).
    let mut events: Vec<_> = events
        .into_iter()
        .map(|e| (crate::display_time::parse_db_utc(&e.blocked_at), e))
        .collect();
    events.sort_by_key(|e| std::cmp::Reverse(e.0));
    let events = events
        .into_iter()
        .map(|(at, e)| LogRow {
            when: at.map_or_else(|| "Time unknown".to_string(), |t| zone.when(t, now)),
            exact: at.map(|t| zone.exact(t)).unwrap_or_default(),
            domain: e.domain,
            category: e.category,
        })
        .collect();
    let refused = match query.get("notice").map(String::as_str) {
        Some("not_a_domain_block") => "blocked",
        Some("not_a_domain_allow") => "allowed",
        _ => "",
    };
    Html(
        FilterTemplate {
            title: format!("Web Filter · {}", device.name),
            refused,
            filter_on: policy.vpn_filter_enabled,
            spotify_on,
            spotify_overridden,
            spotify_global,
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

/// One app on Device › Apps: the row `devices::app_rows` built, where it is (`devices::app_anchor`),
/// its tile, its name-and-icon form and why a switch was refused.
pub struct AppView {
    pub row: crate::handlers::devices::UnifiedAppRow,
    pub anchor: String,
    /// The tile: the icon this phone shows for it (its own choice, else the catalog's) on that
    /// colour, else the name's first letter.
    pub icon: Option<(String, &'static str)>,
    pub letter: String,
    /// Name and icon: every allowed app with a package name but Vibb (design 14).
    pub display: Option<crate::app_display::AppDisplayForm>,
    /// A refused switch, under the row (`toggle_app`'s `?notice=<code>&app=<row>`,
    /// `devices::toggle_refusal`).
    pub refused: Option<&'static str>,
}

/// A tab of the bar (`partials/tab_bar.html`'s five), for the one page whose tab is a field
/// rather than a literal `{% let tab = "..." %}`: Device › Apps, opened from the Apps tab
/// (`shell::every_template_with_the_bar_sets_its_tab`). Only tab names can be set.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum Tab {
    Devices,
    Apps,
}

impl Tab {
    pub(crate) fn name(self) -> &'static str {
        match self {
            Tab::Devices => "devices",
            Tab::Apps => "apps",
        }
    }
}

/// Where Device › Apps was opened from (`?from=`; the user, 2026-10-24: Back from Apps › a phone
/// went to the phone's page): the Apps tab's list (`apps`) or a catalog app's page
/// (`app:<tracked app id>`). The page then lights the Apps tab and its back link returns there, at
/// the phone's row; any other value is the phone's own page, as before. Every form on the page
/// carries it (a hidden `from`) and its redirect keeps it.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum AppsFrom {
    List,
    App(i64),
}

impl AppsFrom {
    /// Only the two shapes, the id a positive number in digits: anything else is `None`.
    pub(crate) fn parse(raw: &str) -> Option<Self> {
        if raw == "apps" {
            return Some(Self::List);
        }
        let id = raw.strip_prefix("app:")?;
        if id.is_empty() || id.len() > 18 || !id.bytes().all(|b| b.is_ascii_digit()) {
            return None;
        }
        id.parse::<i64>().ok().filter(|id| *id > 0).map(Self::App)
    }

    /// The value in the address and in the forms' hidden `from`.
    pub(crate) fn value(self) -> String {
        match self {
            Self::List => "apps".to_string(),
            Self::App(id) => format!("app:{id}"),
        }
    }
}

/// Device › Apps' address: `params` (a refusal's `notice=…&app=…`, or empty), then `from` kept,
/// then the fragment (without `#`; empty for none).
pub(crate) fn apps_url(
    device_id: i64,
    params: &str,
    from: Option<AppsFrom>,
    fragment: &str,
) -> String {
    let from = from.map(|f| format!("from={}", f.value()));
    let query: Vec<&str> = [Some(params), from.as_deref()]
        .into_iter()
        .flatten()
        .filter(|p| !p.is_empty())
        .collect();
    let mut url = format!("/devices/{device_id}/apps");
    if !query.is_empty() {
        url.push('?');
        url.push_str(&query.join("&"));
    }
    if !fragment.is_empty() {
        url.push('#');
        url.push_str(fragment);
    }
    url
}

/// The page's tab and back link: the phone's (Devices, "‹ Anna") unless it was opened from the
/// Apps tab - then Apps, back to the list or the catalog app's page at the phone's row
/// (`#phone-<id>`); a catalog app deleted since is the list. Also the `from` the page keeps.
async fn apps_nav(
    state: &AppState,
    device: &Device,
    from: Option<AppsFrom>,
) -> Result<(Tab, String, String, Option<AppsFrom>), sqlx::Error> {
    let list = (
        Tab::Apps,
        format!("/apps#phone-{}", device.id),
        "Apps".to_string(),
        Some(AppsFrom::List),
    );
    Ok(match from {
        None => (
            Tab::Devices,
            format!("/devices/{}", device.id),
            device.name.clone(),
            None,
        ),
        Some(AppsFrom::List) => list,
        Some(AppsFrom::App(app)) => {
            match sqlx::query_scalar::<_, String>("SELECT name FROM tracked_apps WHERE id = ?")
                .bind(app)
                .fetch_optional(&state.db)
                .await?
            {
                Some(name) => (
                    Tab::Apps,
                    format!("/apps/tracked/{app}#phone-{}", device.id),
                    name,
                    from,
                ),
                None => list,
            }
        }
    })
}

#[derive(Template)]
#[template(path = "device_apps.html")]
struct AppsTemplate {
    title: String,
    device: Device,
    /// Devices, or Apps when opened from the Apps tab (`AppsFrom`).
    nav_tab: Tab,
    /// The `.nav-back` link and its words.
    back_href: String,
    back_label: String,
    /// `from` for every form's hidden field (`AppsFrom::value`), when opened from the Apps tab.
    from: Option<String>,
    /// The stored allowlist can't be read (the phone gets a 500 for its settings): above the list.
    corrupt: Option<Note>,
    /// Vibb first, then the apps switched on; then the rest.
    allowed: Vec<AppView>,
    not_allowed: Vec<AppView>,
    /// The phone has reported its apps at least once.
    reported: bool,
    /// Something installs now: the page reloads every 3 s for the percentage
    /// (`static/auto-reload.js`) - never on a refused save's page, the answer to a POST.
    installing: bool,
    wifi_only: bool,
    /// The phone's network at its last report, with its time (`app_downloads::network`).
    network: Option<Row>,
}

/// GET `/devices/{id}/apps`: Device › Apps (design 31 §3.2, step 6). The phone's apps and the
/// catalog's in two groups, Allowed and Not allowed, each a switch (`/apps/toggle`, `back=apps`);
/// an allowed app's Name and icon (`/apps/display`) in a `<details>` under its row; Download apps
/// on Wi-Fi only (`/app-updates`) and the phone's network at the bottom. A switch and a name save
/// return to the app's row (`#app-<package>`), the Wi-Fi switch to `#downloads`. While an app
/// installs, the page reloads itself every 3 s. A phone not set up has the page too: a catalog app
/// turned on installs with its first settings.
pub async fn view_apps(
    State(state): State<AppState>,
    Path(id): Path<i64>,
    Query(query): Query<std::collections::HashMap<String, String>>,
) -> Response {
    let refused = query.get("app").and_then(|row| {
        query
            .get("notice")
            .and_then(|code| crate::handlers::devices::toggle_refusal(code))
            .map(|text| (row.as_str(), text))
    });
    let from = query.get("from").and_then(|f| AppsFrom::parse(f));
    render_apps(&state, id, refused, None, from).await
}

/// Device › Apps; `refused` = the row whose switch was refused and why, `entered` = a refused Name
/// and icon save (that app's form open with the entered values and the error), `from` = opened
/// from the Apps tab (the tab, the back link and every form's hidden `from`).
pub(crate) async fn render_apps(
    state: &AppState,
    id: i64,
    refused: Option<(&str, &'static str)>,
    entered: Option<(String, crate::app_display::Entered)>,
    from: Option<AppsFrom>,
) -> Response {
    use crate::app_display::{self as display, AppDisplayForm, DisplayValues};
    let device = match load_device(state, id).await {
        Ok(device) => device,
        Err(answer) => return answer.into_response(),
    };
    let policy = match load_policy(state, id).await {
        Ok(policy) => policy,
        Err(answer) => return answer.into_response(),
    };
    let latest = match load_latest(state, id).await {
        Ok(latest) => latest,
        Err(err) => return read_failed(id, "the last report", err),
    };
    let rows = match crate::handlers::devices::app_rows(state, id, &policy, latest.as_ref()).await {
        Ok(rows) => rows,
        Err(err) => return read_failed(id, "the phone's apps", err),
    };
    // Names and icons: a failed read must not show every app as "its own".
    let defaults = match display::catalog_defaults(&state.db).await {
        Ok(defaults) => defaults,
        Err(err) => return read_failed(id, "the catalog's app names", err),
    };
    let own = match display::device_rows(&state.db, id).await {
        Ok(own) => own,
        Err(err) => return read_failed(id, "the phone's app names", err),
    };
    let (nav_tab, back_href, back_label, from) = match apps_nav(state, &device, from).await {
        Ok(nav) => nav,
        Err(err) => return read_failed(id, "the catalog app it came from", err),
    };
    let from = from.map(AppsFrom::value);
    let zone = Zone::load(&state.db).await;
    let now = Utc::now();

    let network = rows
        .downloads
        .as_ref()
        .and_then(crate::app_downloads::network)
        .map(|value| {
            let at = latest
                .as_ref()
                .and_then(|s| crate::display_time::parse_db_utc(&s.reported_at));
            Row::new(
                "network",
                "Network",
                match at {
                    Some(at) => format!("{value} · {}", zone.when(at, now)),
                    None => value.to_string(),
                },
            )
            .title(at.map(|t| zone.exact(t)))
            .detail(Some("At the phone's last report."))
        });
    // A refused save is the answer to a POST: a reload would ask to send it again (L1).
    let installing = entered.is_none() && rows.apps.iter().any(|a| a.is_installing);
    let (mut allowed, mut not_allowed) = (Vec::new(), Vec::new());
    for row in rows.apps {
        let package = row.package_name.clone();
        let anchor = crate::handlers::devices::app_anchor(&package, row.tracked_app_id);
        let phone_row = own.get(&package);
        let default = defaults.get(&package);
        let current = phone_row.or(default);
        let icon = current.and_then(|values| {
            values
                .icon
                .as_deref()
                .filter(|key| display::icon_known(key))
                .map(|key| (key.to_string(), display::tile_of(&values.color)))
        });
        let display = (row.checked && !row.is_launcher && display::valid_package_name(&package))
            .then(|| {
                let mut form = AppDisplayForm::new(
                    format!("/devices/{id}/apps/display#{anchor}"),
                    format!("display-{package}"),
                    package.clone(),
                    row.label.clone(),
                    &current.cloned().unwrap_or_else(DisplayValues::empty),
                    entered
                        .as_ref()
                        .filter(|(entered, _)| *entered == package)
                        .map(|(_, e)| e),
                );
                form.catalog_default = default.map(DisplayValues::describe);
                form.own_row = phone_row.is_some();
                form.from = from.clone();
                if phone_row.is_none() && default.is_some() {
                    form.summary = format!("{} (from the catalog)", form.summary);
                }
                form
            });
        let view = AppView {
            letter: crate::handlers::devices::initial(&row.label),
            refused: refused
                .filter(|(row, _)| *row == anchor)
                .map(|(_, text)| text),
            icon,
            display,
            anchor,
            row,
        };
        if view.row.checked {
            allowed.push(view);
        } else {
            not_allowed.push(view);
        }
    }
    Html(
        AppsTemplate {
            title: format!("Apps · {}", device.name),
            nav_tab,
            back_href,
            back_label,
            from,
            corrupt: rows.allowlist_corrupt.then(|| Note {
                warning: true,
                title: "App list unreadable".to_string(),
                value: None,
                detail: Some(
                    "The phone keeps the last list it had, and switching apps here fails until \
                     it is fixed."
                        .to_string(),
                ),
                fix: Some(
                    "The stored allowlist isn't valid JSON. Check the server log (\"failed to \
                     build device policy\"), or restore a backup."
                        .to_string(),
                ),
            }),
            allowed,
            not_allowed,
            reported: latest.is_some(),
            installing,
            wifi_only: policy.app_updates_wifi_only,
            network,
            device,
        }
        .render()
        .unwrap(),
    )
    .into_response()
}

#[derive(Template)]
#[template(path = "device_music.html")]
struct MusicTemplate {
    title: String,
    device: Device,
    music: crate::handlers::music::MusicCard,
    /// When the phone last reported (`On the phone`'s footer): its age, and the exact time for
    /// `title=`.
    reported: Option<(String, String)>,
}

/// GET `/devices/{id}/music`: Device › Music (design 31 §3.2, step 7) - the Music card as design
/// 30 step 1 left it (M7): the music app's switch (`/apps/toggle`, `back=music`), one switch per
/// library entry (`/music/entries/{entry}`, back to `#music-entry-<id>`), the one settings form
/// (`/music`, `v=2`: mobile data, the storage limit, the volume cap, Storytel; back to
/// `#music-settings`), the Sonos rooms (`/music/sonos-rooms`, back to `#music-sonos`) and what the
/// phone reported. A refused save shows in its group (`?music_notice=`). A phone not set up has
/// the page too: what is saved reaches it with its first settings. Every read is a 500 on a DB
/// error, never an empty library or default settings.
pub async fn view_music(
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
    let latest = match load_latest(&state, id).await {
        Ok(latest) => latest,
        Err(err) => return read_failed(id, "the last report", err),
    };
    let music = match crate::handlers::music::device_card(
        &state,
        &policy,
        latest.as_ref(),
        query.get("music_notice").map(String::as_str),
        query.get("music_entry").and_then(|e| e.parse().ok()),
    )
    .await
    {
        Ok(music) => music,
        Err(err) => return read_failed(id, "the phone's music", err),
    };
    let zone = Zone::load(&state.db).await;
    let reported = latest
        .as_ref()
        .and_then(|s| crate::display_time::parse_db_utc(&s.reported_at))
        .map(|at| (zone.relative(at, Utc::now()), zone.exact(at)));
    Html(
        MusicTemplate {
            title: format!("Music · {}", device.name),
            music,
            reported,
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

    /// `?from=` on Device › Apps: only `apps` and `app:<positive id in digits>`; the address
    /// keeps it between a refusal's own parameters and the fragment.
    #[test]
    fn apps_from_takes_only_its_two_shapes() {
        assert_eq!(AppsFrom::parse("apps"), Some(AppsFrom::List));
        assert_eq!(AppsFrom::parse("app:7"), Some(AppsFrom::App(7)));
        assert_eq!(AppsFrom::parse("app:007"), Some(AppsFrom::App(7)));
        for bad in [
            "",
            "app",
            "app:",
            "app:0",
            "app:-1",
            "app:+1",
            "app:1x",
            "app: 1",
            "app:1 ",
            "apps ",
            "Apps",
            "APP:1",
            "app:99999999999999999999",
            "devices",
            "/apps",
        ] {
            assert_eq!(AppsFrom::parse(bad), None, "{bad:?}");
        }
        for from in [AppsFrom::List, AppsFrom::App(12)] {
            assert_eq!(AppsFrom::parse(&from.value()), Some(from));
        }
        assert_eq!(apps_url(3, "", None, ""), "/devices/3/apps");
        assert_eq!(
            apps_url(3, "", Some(AppsFrom::List), "app-org.example"),
            "/devices/3/apps?from=apps#app-org.example"
        );
        assert_eq!(
            apps_url(
                3,
                "notice=from_play&app=catalog-4",
                Some(AppsFrom::App(4)),
                "catalog-4"
            ),
            "/devices/3/apps?notice=from_play&app=catalog-4&from=app:4#catalog-4"
        );
        assert_eq!(
            apps_url(3, "", None, "downloads"),
            "/devices/3/apps#downloads"
        );
        assert_eq!(Tab::Devices.name(), "devices");
        assert_eq!(Tab::Apps.name(), "apps");
    }

    /// S2P-4: an earlier install's report isn't this one's.
    #[test]
    fn only_a_report_since_the_enrolment_counts() {
        let report = DeviceStatus {
            reported_at: "2026-10-22 10:00:00".to_string(),
            ..Default::default()
        };
        assert!(since_enrolment(Some("2026-10-22 09:59:59"), Some(&report)).is_some());
        assert!(since_enrolment(Some("2026-10-22 10:00:00"), Some(&report)).is_some());
        assert!(since_enrolment(Some("2026-10-22 10:00:01"), Some(&report)).is_none());
        assert!(since_enrolment(None, Some(&report)).is_some());
        assert!(since_enrolment(Some("2026-10-22 09:00:00"), None).is_none());
    }

    #[test]
    fn versions_read_short() {
        assert_eq!(short_version("0.23.6-debug"), "0.23.6");
        assert_eq!(short_version("0.23.6"), "0.23.6");
        assert_eq!(short_version("1.0.0+build.5"), "1.0.0");
        // The old rule (every music tag up to music-v0.5.0-rc.3).
        assert_eq!(version_of_code(3_000), Some("0.3.0".to_string()));
        assert_eq!(version_of_code(4_000), Some("0.4.0".to_string()));
        assert_eq!(version_of_code(2), Some("0.0.2".to_string()));
        // A release candidate: one below its release.
        assert_eq!(
            version_of_code(2_999),
            Some("0.3.0 (pre-release)".to_string())
        );
        assert_eq!(
            version_of_code(4_999),
            Some("0.5.0 (pre-release)".to_string())
        );
        // Today's rule, from music-v0.5.0-rc.4 on (scripts/release-version.sh).
        assert_eq!(version_of_code(50_004), Some("0.5.0-rc.4".to_string()));
        assert_eq!(version_of_code(50_009), Some("0.5.0".to_string()));
        assert_eq!(version_of_code(10_020_039), Some("1.2.3".to_string()));
        assert_eq!(
            version_of_code(230_060_018),
            Some("23.6.1-rc.8".to_string())
        );
        assert_eq!(version_of_code(10_000_000), None);
        assert_eq!(version_of_code(0), None);
        assert_eq!(version_of_code(-5), None);
    }

    /// The shared contract's music rows: the version shown for each tag's code (the script's
    /// `--selftest` checks that it builds those codes).
    #[test]
    fn music_versions_read_as_the_shared_contract() {
        let contract: serde_json::Value =
            serde_json::from_str(include_str!("../../testdata/launcher_tag_codes.json")).unwrap();
        let rows = contract["music_codes"].as_array().unwrap();
        assert!(rows.len() >= 10);
        for row in rows {
            let code = row["code"].as_i64().unwrap();
            assert_eq!(
                version_of_code(code).as_deref(),
                row["version"].as_str(),
                "{}",
                row["music_tag"]
            );
        }
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
