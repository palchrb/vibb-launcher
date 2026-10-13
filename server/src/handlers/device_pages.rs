//! A phone's About This Phone and Advanced pages (design 31 §3.2, §8 step 3).
//!
//! - **About This Phone** (`GET /devices/{id}/about`, `device_about.html`) is read-only, like iOS
//!   Settings › General › About: the connection (§3.10), the sound, the software, the crash
//!   reports, the starts without Vibb and the time lock - the facts the old Status, Access and
//!   "Play and kiosk" cards led with. Every time is local (Phone Setup's zone, `display_time`),
//!   with the exact one in a `title`.
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
                        .map(|t| zone.when(t, now))
                        .unwrap_or_default()
                ),
                value: None,
                detail: Some(
                    "Install mode is on. Apps installed now stay hidden until you allow them."
                        .to_string(),
                ),
            },
            PlayNote::UpdateWindow => Note {
                warning: false,
                title: "Play Store".to_string(),
                value: Some("Updating apps".to_string()),
                detail: Some(
                    "The nightly update window is open while the screen is off.".to_string(),
                ),
            },
            PlayNote::RestartOnce(effects) => Note {
                warning: true,
                title: "Restart the phone once".to_string(),
                value: None,
                detail: Some(format!(
                    "After a Vibb update, Android hasn't yet given Vibb what it needs to {}.",
                    effects.join(", and to ")
                )),
            },
            PlayNote::NotSuspendable { block_on } => Note {
                warning: true,
                title: "Play can't be paused on this phone".to_string(),
                value: None,
                detail: Some(if *block_on {
                    "Play is blocked only while the switch above is on.".to_string()
                } else {
                    "And the switch above is off: apps can open the Play Store.".to_string()
                }),
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
/// CLAUDE.md). A release candidate's code is one lower, so it reads one patch below (2999 →
/// "0.2.999"); the ⓘ has the code itself.
pub(crate) fn version_of_code(code: i64) -> Option<String> {
    (code > 0).then(|| {
        format!(
            "{}.{}.{}",
            code / 1_000_000,
            (code / 1_000) % 1_000,
            code % 1_000
        )
    })
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

async fn load_device(state: &AppState, id: i64) -> Option<Device> {
    sqlx::query_as::<_, Device>("SELECT * FROM devices WHERE id = ?")
        .bind(id)
        .fetch_optional(&state.db)
        .await
        .unwrap_or_else(|err| {
            tracing::error!(device_id = id, %err, "can't read the device");
            None
        })
}

async fn load_policy(state: &AppState, id: i64) -> DevicePolicy {
    sqlx::query_as::<_, DevicePolicy>("SELECT * FROM device_policy WHERE device_id = ?")
        .bind(id)
        .fetch_optional(&state.db)
        .await
        .ok()
        .flatten()
        .unwrap_or(DevicePolicy {
            device_id: id,
            vpn_filter_enabled: true,
            ..Default::default()
        })
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
            ConnectionState::Disconnected => "access removed".to_string(),
            _ => String::new(),
        },
    )
    .title(connection.online_since().map(|t| zone.exact(t)));
    status.lead = Some((connection.word(), connection.css()));
    rows.push(status);
    if matches!(connection, ConnectionState::NotSetUp) {
        return rows;
    }
    rows.push(match connection.last_contact() {
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
    let Some(device) = load_device(&state, id).await else {
        return (StatusCode::NOT_FOUND, "Device not found").into_response();
    };
    let policy = load_policy(&state, id).await;
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
                    name: c
                        .exception()
                        .split(':')
                        .next()
                        .and_then(|class| class.trim().rsplit('.').next())
                        .unwrap_or("")
                        .to_string(),
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
    let Some(device) = load_device(&state, id).await else {
        return (StatusCode::NOT_FOUND, "Device not found").into_response();
    };
    let policy = load_policy(&state, id).await;
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

/// The escapes card from the policy and the last report (capabilities, the fence, the filter,
/// the listener, the boot cover).
fn escapes_card(
    policy: &DevicePolicy,
    latest: Option<&DeviceStatus>,
    now_ms: i64,
) -> crate::kiosk_escapes::EscapesCard {
    use crate::kiosk_escapes as ke;
    let capability = |name: &str| {
        latest
            .and_then(|s| s.capabilities_json.as_deref())
            .is_some_and(|caps| caps.contains(&format!("\"{name}\"")))
    };
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
        assert_eq!(version_of_code(0), None);
        assert_eq!(version_of_code(-5), None);
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
                .contains("switch above is off")
        );
    }
}
