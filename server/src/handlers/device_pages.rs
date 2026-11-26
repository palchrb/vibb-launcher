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
use axum::Form;
use axum::extract::{Path, Query, State};
use axum::http::StatusCode;
use axum::response::{Html, IntoResponse, Redirect, Response};
use chrono::{DateTime, Utc};
use serde::Deserialize;

use crate::AppState;
use crate::display_time::Zone;
use crate::i18n::{Lang, Words, t, t_params};
use crate::models::{Device, DevicePolicy, DeviceStatus, Hardening};
use crate::streams::ConnectionState;

/// A row's words from a typed state: a short title, maybe a value, maybe the sentence behind its
/// ⓘ. A warning shows as an orange row above its group (its title; then `sub` when set; the sentence
/// and the fix behind one ⓘ - design 113 D1 P1), anything else as a row in the group (title · value,
/// the sentence behind ⓘ).
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Note {
    pub warning: bool,
    pub title: String,
    pub value: Option<String>,
    /// A warning's short visible consequence under its title, set only where the title alone
    /// doesn't carry the news (design 113 D1: Anrop's KEEPs, needs-update, the ledger, two lock
    /// notes); `None` everywhere else. A row never shows it.
    pub sub: Option<String>,
    pub detail: Option<String>,
    /// An expert fix (an adb command, a package name) for a warning: behind its ⓘ with the sentence,
    /// never in the visible text (design 31 §4.2).
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
    /// Design 114 §5.5: a row whose setup step is missing links to it on Device › Oppsett (no detail then).
    pub href: Option<String>,
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
            href: None,
        }
    }

    /// Design 114 §5.5: while `step` is missing the row links to it on Device › Oppsett and says no more (the how is
    /// there); otherwise the row is as built.
    fn step_link(
        mut self,
        device_id: i64,
        missing: &[&crate::setup_status::Step],
        step: &str,
    ) -> Row {
        if missing.iter().any(|s| s.key == step) {
            self.href = Some(format!("/devices/{device_id}/setup#step-{step}"));
            self.detail = None;
        }
        self
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
    pub fn title(&self, lang: Lang) -> &'static str {
        t(
            lang,
            match self {
                PolicyProblem::CacheCorrupt => "device.about.policy.cache_corrupt",
                PolicyProblem::ServerError => "device.about.policy.server_error",
                PolicyProblem::RejectedSuspect => "device.about.policy.rejected",
                PolicyProblem::FreshDecodeFailed => "device.about.policy.decode_failed",
                PolicyProblem::Other(_) => "device.about.policy.unknown",
            },
        )
    }

    /// The ⓘ: what the phone does now, and what to check.
    pub fn detail(&self, lang: Lang) -> String {
        let key = match self {
            PolicyProblem::CacheCorrupt => "device.about.policy.cache_corrupt_sub",
            PolicyProblem::ServerError => "device.about.policy.server_error_sub",
            PolicyProblem::RejectedSuspect => "device.about.policy.rejected_sub",
            PolicyProblem::FreshDecodeFailed => "device.about.policy.decode_failed_sub",
            PolicyProblem::Other(code) => {
                return t_params(lang, "device.about.policy.unknown_sub", &[("code", code)]);
            }
        };
        t(lang, key).to_string()
    }
}

/// What the phone's last report says about Play (Advanced › App screens), typed (M5).
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum PlayNote {
    /// Play install mode is on: the Play Store opens until then.
    InstallMode { until_ms: i64 },
    /// A device-admin policy waits for a restart (`models::admin_policy_effects`).
    RestartOnce(Vec<&'static str>),
}

impl PlayNote {
    pub fn note(&self, zone: &Zone, now: DateTime<Utc>) -> Note {
        let lang = zone.lang();
        match self {
            // Install mode is the parent's own choice: a quiet line, never a warning (2026-11-18).
            PlayNote::InstallMode { until_ms } => Note {
                warning: false,
                title: t_params(
                    lang,
                    "device.advanced.play.install_mode",
                    &[(
                        "until",
                        &DateTime::from_timestamp_millis(*until_ms)
                            .map(|t| zone.until(t, now))
                            .unwrap_or_default(),
                    )],
                ),
                value: None,
                sub: None,
                detail: Some(t(lang, "device.advanced.play.install_mode_sub").to_string()),
                fix: None,
            },
            PlayNote::RestartOnce(effects) => Note {
                warning: true,
                title: t(lang, "device.advanced.play.restart").to_string(),
                value: None,
                sub: None,
                detail: Some(t_params(
                    lang,
                    "device.advanced.play.restart_sub",
                    &[(
                        "effects",
                        &effects
                            .iter()
                            .map(|key| t(lang, key))
                            .collect::<Vec<_>>()
                            .join(t(lang, "device.advanced.play.restart_and")),
                    )],
                )),
                fix: None,
            },
        }
    }
}

/// Advanced › App screens: the kiosk app block switch and Play's state.
pub(crate) struct PlayCard {
    /// `device_policy.block_activity_start` (handy step 9).
    pub block_activity_start: bool,
    /// Android won't suspend Play and the block is off: apps can open the Play Store. The parent's
    /// choice, a sentence behind the block switch's ⓘ (2026-11-18). With the block on, Play's
    /// missing suspension shows nothing.
    pub openable: bool,
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
        openable: !block_activity_start
            && latest.is_some_and(|st| st.play_store_suspendable == Some(false)),
        notes: play_notes(latest, now_ms),
    }
}

/// [PlayCard]'s notes, in the old card's order. Pure.
pub(crate) fn play_notes(latest: Option<&DeviceStatus>, now_ms: i64) -> Vec<PlayNote> {
    let mut notes = Vec::new();
    let Some(status) = latest else {
        return notes;
    };
    if let Some(until_ms) = status.install_mode_until_ms.filter(|until| *until > now_ms) {
        notes.push(PlayNote::InstallMode { until_ms });
    }
    // The nightly Play update window (`play_window_active`) is the good state: no row (design 113, P4).
    let effects =
        crate::models::admin_policy_effects(status.admin_policies_pending_json.as_deref());
    if !effects.is_empty() {
        notes.push(PlayNote::RestartOnce(effects));
    }
    notes
}

/// "0.23.6" from "0.23.6-debug": the version without its build suffix.
pub(crate) fn short_version(full: &str) -> &str {
    full.split(['-', '+']).next().unwrap_or(full).trim()
}

/// The music app's version from its versionCode (the repo's release scheme, root CLAUDE.md,
/// `scripts/release-version.sh`): `X*10_000_000 + Y*10_000 + Z*10 + N`, N the RC number (1..8) or 9
/// for the release - "0.5.0-rc.4" for 50004, "1.2.3" for 10020039; a code ending in 0, or not above
/// 0, is no release build (`None`). The shared contract's `music_codes` rows
/// (`contracts/core/launcher_tag_codes.json`) are its cases. The ⓘ has the code itself.
pub(crate) fn version_of_code(code: i64) -> Option<String> {
    if code <= 0 {
        return None;
    }
    let version = format!(
        "{}.{}.{}",
        code / 10_000_000,
        (code / 10_000) % 1_000,
        (code / 10) % 1_000
    );
    match code % 10 {
        0 => None,
        9 => Some(version),
        n => Some(format!("{version}-rc.{n}")),
    }
}

/// The music app as the phone reported it: its version from the versionCode ("Installed" without
/// one), the package and the code behind ⓘ (§4.2). About This Phone (`key` "music-app") and
/// Device › Music ("app") show the same row (qa-31-s7-code N2).
pub(crate) fn music_app_row(
    lang: Lang,
    key: &'static str,
    music: &crate::music::MusicState,
) -> Option<Row> {
    let package = music.package.as_deref()?;
    let version = music.version_code.and_then(version_of_code);
    Some(
        Row::new(
            key,
            t(lang, "device.about.music_app"),
            version.unwrap_or_else(|| t(lang, "device.apps.installed").to_string()),
        )
        .detail(Some(match music.version_code {
            Some(code) => t_params(
                lang,
                "device.about.music_app_code",
                &[("package", package), ("code", &code.to_string())],
            ),
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
    lang: Lang,
    /// The phone's small picture on the band (design 84, `band::phone`).
    who: crate::handlers::devices::BandAvatar,
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
crate::i18n::words!(AboutTemplate);

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
        Ok(None) => Err((StatusCode::NOT_FOUND, "error.device_not_found")),
        Err(err) => {
            tracing::error!(device_id = id, %err, "can't read the device");
            Err((StatusCode::INTERNAL_SERVER_ERROR, "error.read_phone"))
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
            // Design 102: migration 0150's defaults (D1, revision 5).
            captive_portal_enabled: true,
            captive_portal_require_code: true,
            ..Default::default()
        })),
        Err(err) => {
            tracing::error!(device_id = id, %err, "can't read the device's settings");
            Err((StatusCode::INTERNAL_SERVER_ERROR, "error.read_phone"))
        }
    }
}

/// The last report lists the launcher capability `name`: the one check of a claim (C1 kept the mechanism for the
/// first feature after v0.40.0; today only C3's `android_keyguard_v1` is read).
pub(crate) fn has_capability(latest: Option<&DeviceStatus>, name: &str) -> bool {
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
/// network of the last request. `held`: a quiet the phone or the parent chose (design 121 §4.3) - its word ("Flymodus",
/// "I utlandet"; the time is the header's), grey, never orange (code QA L4).
fn connection_rows(
    connection: &ConnectionState,
    held: Option<crate::phone_status::Held>,
    // From the signals, not the state: a Disconnected phone has one too (qa-31-s3-code M1).
    last_contact: Option<DateTime<Utc>>,
    reported_at: Option<DateTime<Utc>>,
    last_access: Option<&crate::limits::LastAccess>,
    zone: &Zone,
    now: DateTime<Utc>,
) -> Vec<Row> {
    let lang = zone.lang();
    let mut rows = Vec::new();
    let mut status = Row::new(
        "status",
        t(lang, "device.about.status"),
        match connection {
            ConnectionState::Online { since, .. } => t_params(
                lang,
                "device.about.since",
                &[("at", &zone.when(*since, now))],
            ),
            ConnectionState::Disconnected => {
                format!("· {}", t(lang, "device.about.access_removed"))
            }
            _ => String::new(),
        },
    )
    .title(connection.online_since().map(|t| zone.exact(t)));
    let word = match (held, connection) {
        (Some(crate::phone_status::Held::Airplane(_)), _) => "device.home.airplane",
        (Some(crate::phone_status::Held::AbroadNoData), _) => "devices.status.abroad",
        (None, connection) => match connection {
            ConnectionState::NotSetUp => "devices.status.not_set_up",
            ConnectionState::Disconnected => "device.about.disconnected",
            ConnectionState::NotSeenYet => "devices.status.not_seen_yet",
            ConnectionState::Online { .. } => "devices.status.online",
            ConnectionState::Away { .. } => "devices.status.away",
            ConnectionState::Offline { .. } => "devices.status.offline",
        },
    };
    status.lead = Some((
        t(lang, word),
        if held.is_some() {
            "offline"
        } else {
            connection.css()
        },
    ));
    rows.push(status);
    if matches!(connection, ConnectionState::NotSetUp) {
        return rows;
    }
    rows.push(match last_contact {
        Some(at) => {
            let relative = zone.relative(at, now);
            let value = if zone.local(at).date_naive() == zone.today(now) && at <= now {
                format!("{} · {relative}", zone.clock(at))
            } else {
                relative
            };
            Row::new("last-contact", t(lang, "device.about.last_contact"), value)
                .title(Some(zone.exact(at)))
        }
        None => Row::new(
            "last-contact",
            t(lang, "device.about.last_contact"),
            t(lang, "device.about.never"),
        ),
    });
    rows.push(match reported_at {
        Some(at) => Row::new(
            "last-report",
            t(lang, "device.about.last_report"),
            zone.when(at, now),
        )
        .title(Some(zone.exact(at))),
        None => Row::new(
            "last-report",
            t(lang, "device.about.last_report"),
            t(lang, "device.about.none_yet"),
        ),
    });
    rows.push(match last_access {
        Some(access) => Row::new(
            "network",
            t(lang, "device.about.network"),
            access.via.label(lang),
        )
        // Outside the tailnet is a quiet value (2026-11-18), the addresses behind the ⓘ.
        .detail(access.detail(lang)),
        None => Row::new(
            "network",
            t(lang, "device.about.network"),
            t(lang, "device.about.network_none"),
        )
        .detail(Some(t(lang, "device.about.network_none_sub"))),
    });
    rows
}

/// About This Phone's "Sign-in" row (design 129 S1, §9.1): a quiet value in every state - "Older sign-in", "Hardware
/// key" (what the attestation said behind the ⓘ: step 0 reads the Jelly Star's verdict here), "Key refused (reason)"
/// or "Another key registered" - the parent can't act on them yet. One is orange: "Emulator test key", a key the
/// `emulator_keys` profile took (design 129b §7, H3).
async fn key_row(state: &AppState, id: i64, lang: Lang) -> Result<Row, sqlx::Error> {
    let label = t(lang, "device.about.key");
    let (refused, conflict): (Option<String>, Option<String>) =
        sqlx::query_as("SELECT key_refused_reason, key_conflict_at FROM devices WHERE id = ?")
            .bind(id)
            .fetch_optional(&state.db)
            .await?
            .unwrap_or_default();
    if conflict.is_some() {
        return Ok(Row::new("key", label, t(lang, "device.about.key.conflict")));
    }
    if let Some(key) = crate::device_key::current_key(&state.db, id).await? {
        // Design 129b §7 (H3): a key the `emulator_keys` profile took is a test key anyone can make - orange.
        if key.root == crate::attestation::roots::Root::Emulator.word() {
            return Ok(Row::new("key", label, t(lang, "device.about.key.emulator"))
                .class("row-value-action")
                .detail(Some(key_detail(&key, lang))));
        }
        return Ok(Row::new("key", label, t(lang, "device.about.key.hardware"))
            .detail(Some(key_detail(&key, lang))));
    }
    // Design 129b §5.2: the parent's exception - a choice, quiet; who and when behind the (i).
    if let Some(detail) = crate::enrol_key::about_exempt(state, lang, id).await? {
        return Ok(Row::new("key", label, t(lang, "device.about.key.exempt")).detail(Some(detail)));
    }
    if let Some(reason) = refused
        .as_deref()
        .and_then(crate::attestation::Refusal::from_word)
    {
        return Ok(Row::new(
            "key",
            label,
            t_params(
                lang,
                "device.about.key.refused",
                &[("reason", t(lang, key_reason(reason)))],
            ),
        ));
    }
    Ok(Row::new("key", label, t(lang, "device.about.key.none")))
}

/// What the key's attestation said (§3 item 12), for the ⓘ.
fn key_detail(key: &crate::device_key::StoredKey, lang: Lang) -> String {
    let level = match key.security_level.as_str() {
        "software" => "device.about.key.level.software",
        "strongbox" => "device.about.key.level.strongbox",
        _ => "device.about.key.level.tee",
    };
    let system = match key.reading() {
        crate::attestation::boot::Reading::Verified => "device.about.key.system.verified",
        crate::attestation::boot::Reading::GrapheneOs => "device.about.key.system.grapheneos",
        crate::attestation::boot::Reading::Unknown => "device.about.key.system.unknown",
    };
    let root = match key.root.as_str() {
        "google_ec_ca1" => "device.about.key.root.google_ec_ca1",
        "emulator" => "device.about.key.root.emulator",
        _ => "device.about.key.root.google_rsa",
    };
    let patch = key
        .os_patch_level
        .map(|p| format!("{}-{:02}", p / 100, p % 100))
        .unwrap_or_else(|| "?".to_string());
    let detail = t_params(
        lang,
        "device.about.key.detail",
        &[
            ("level", t(lang, level)),
            ("version", &key.attestation_version.to_string()),
            ("patch", &patch),
            ("system", t(lang, system)),
            ("root", t(lang, root)),
        ],
    );
    // What is wrong, and only that (code QA L10): revoked, not signing yet, an unusual address.
    let mut detail = detail;
    for (wrong, key) in [
        (key.revoked_at.is_some(), "device.about.key.revoked"),
        (key.last_signed_at.is_none(), "device.about.key.not_signed"),
        (key.unusual, "device.about.key.unusual"),
    ] {
        if wrong {
            detail.push(' ');
            detail.push_str(t(lang, key));
        }
    }
    detail
}

/// A refusal's words (§9.1's `{reason}`): `chain`/`root` one, `revoked`, `software`, `app`, `boot`, the rest "invalid".
pub(crate) fn key_reason(refusal: crate::attestation::Refusal) -> &'static str {
    use crate::attestation::Refusal;
    match refusal {
        Refusal::Chain | Refusal::Root => "device.about.key.reason.issuer",
        Refusal::Revoked => "device.about.key.reason.revoked",
        Refusal::Software => "device.about.key.reason.software",
        Refusal::App => "device.about.key.reason.app",
        Refusal::Boot => "device.about.key.reason.boot",
        _ => "device.about.key.reason.invalid",
    }
}

/// GET `/devices/{id}/about`: About This Phone.
pub async fn view_about(
    State(state): State<AppState>,
    lang: Lang,
    Path(id): Path<i64>,
) -> Response {
    let device = match load_device(&state, id).await {
        Ok(device) => device,
        Err((status, key)) => return crate::i18n::error(lang, status, key),
    };
    let policy = match load_policy(&state, id).await {
        Ok(policy) => policy,
        Err((status, key)) => return crate::i18n::error(lang, status, key),
    };
    let latest = match load_latest(&state, id).await {
        Ok(latest) => latest,
        Err(err) => return read_failed(lang, id, "the last report", err),
    };
    let zone = Zone::load(&state.db, lang).await;
    let now = Utc::now();
    let now_ms = now.timestamp_millis();
    let connection = crate::handlers::devices::connection_of(&state, &device, now);
    let reported_at = latest
        .as_ref()
        .and_then(|s| crate::display_time::parse_db_utc(&s.reported_at));
    let held = latest
        .as_ref()
        .filter(|_| device.enrolled_at.is_some())
        .and_then(|s| crate::phone_status::held(&connection, Some(s), &policy.abroad, now));
    let connection_rows = connection_rows(
        &connection,
        held,
        crate::streams::last_contact(&crate::handlers::devices::connection_inputs(
            &state, &device,
        )),
        reported_at,
        state.limits.last_access(id).as_ref(),
        &zone,
        now,
    );
    let mut connection_rows = connection_rows;
    // Design 129 S1 §9.1: the phone's sign-in as a quiet value - the parent can't act on it yet.
    if device.enrolled_at.is_some() {
        match key_row(&state, id, lang).await {
            Ok(row) => connection_rows.push(row),
            Err(err) => return read_failed(lang, id, "the phone's key", err),
        }
    }

    let mut sound = Vec::new();
    let mut software = Vec::new();
    let mut diagnostics = Vec::new();
    let setup_part = state.integrations.setup(&state, id).await;
    let (setup_missing, _) =
        crate::setup_status::state_of(&policy, latest.as_ref(), &setup_part, &[]);
    if let Some(status) = latest.as_ref() {
        let sound_state = crate::sound_mode::state(
            status.ringer_mode.as_deref(),
            status.interruption_filter.as_deref(),
        );
        if let Some(ringer) = sound_state.ringer {
            sound.push(
                Row::new("ringer", t(lang, "device.about.ringer"), ringer.label(lang))
                    .detail(ringer.detail(lang)),
            );
        }
        if let Some(dnd) = sound_state.dnd {
            sound.push(
                Row::new(
                    "do-not-disturb",
                    t(lang, "device.about.dnd"),
                    dnd.label(lang),
                )
                .class(if dnd.is_on() { "row-value-alarm" } else { "" })
                .detail(dnd.detail(lang)),
            );
        }
        // Design 107 §5.2: the Vibb One notifications turned off on the phone, from a report that sent its reading.
        if let Some(channels) = crate::notification_channels::report(status) {
            let row = crate::notification_channels::about(lang, &policy, &channels);
            sound.push(
                Row::new(
                    "notifications",
                    t(lang, "device.about.notifications"),
                    row.value,
                )
                .class(if row.orange { "row-value-action" } else { "" })
                .detail(row.detail)
                .step_link(id, &setup_missing, "vibb_notifications"),
            );
        }

        if let Some(full) = status.app_version.as_deref().filter(|v| !v.is_empty()) {
            let short = short_version(full);
            software.push(
                Row::new("vibb", "Vibb One", short).detail(
                    (short != full)
                        .then(|| t_params(lang, "device.about.full_version", &[("full", full)])),
                ),
            );
        }
        if let Some(row) = crate::music::parse_music_state(status.music_state_json.as_deref())
            .and_then(|music| music_app_row(lang, "music-app", &music))
        {
            software.push(row);
        }
        if let Some(policy_state) = status.policy_state.as_deref() {
            software.push(match PolicyProblem::parse(policy_state) {
                None => Row::new(
                    "settings",
                    t(lang, "device.about.settings"),
                    t(lang, "device.about.applied"),
                ),
                Some(problem) => Row::new(
                    "settings",
                    t(lang, "device.about.settings"),
                    problem.title(lang),
                )
                .class("row-value-alarm")
                .detail(Some(problem.detail(lang))),
            });
        }
        match status.battery_optimization_exempt {
            Some(true) => software.push(Row::new(
                "battery",
                t(lang, "device.about.battery"),
                t(lang, "common.off"),
            )),
            Some(false) => software.push(
                Row::new(
                    "battery",
                    t(lang, "device.about.battery"),
                    t(lang, "common.on"),
                )
                .class("row-value-action")
                .step_link(id, &setup_missing, "background"),
            ),
            None => {}
        }
        match status.write_settings_granted {
            Some(true) => software.push(Row::new(
                "write-settings",
                t(lang, "apps.perms.access.write_settings"),
                t(lang, "device.summary.blocked.allowed"),
            )),
            Some(false) => software.push(
                Row::new(
                    "write-settings",
                    t(lang, "apps.perms.access.write_settings"),
                    t(lang, "device.summary.blocked.not_allowed"),
                )
                // Orange only while a feature the parent chose needs the grant (2026-11-18).
                .class(if crate::airplane_mode::write_settings_needed(&policy) {
                    "row-value-action"
                } else {
                    ""
                })
                .detail(Some(t(lang, "device.about.write_settings_sub")))
                .step_link(id, &setup_missing, "write_settings"),
            ),
            None => {}
        }
        // Design 98 §6.2: the Digital assistant role (Flymodus' route) and Wi-Fi in airplane mode, from the report's
        // `airplane_mode`.
        if let Some(airplane) = crate::airplane_mode::report(status) {
            software.push(if airplane.assistant {
                Row::new("assistant", t(lang, "device.about.assistant"), "Vibb One")
            } else {
                Row::new(
                    "assistant",
                    t(lang, "device.about.assistant"),
                    t(lang, "device.about.assistant_not_chosen"),
                )
                // Orange only while the kid's Flymodus switch is on (2026-11-18).
                .class(if crate::airplane_mode::assistant_needed(&policy) {
                    "row-value-action"
                } else {
                    ""
                })
                .step_link(id, &setup_missing, "assistant")
            });
            software.push(
                Row::new(
                    "wifi-airplane",
                    t(lang, "device.about.wifi_airplane"),
                    t(
                        lang,
                        match airplane.wifi_kept {
                            Some(true) => "device.about.wifi_airplane_kept",
                            Some(false) => "device.about.wifi_airplane_off",
                            None => "device.about.wifi_airplane_not_set_up",
                        },
                    ),
                )
                .step_link(id, &setup_missing, "wifi_airplane"),
            );
        }
        // Design 99 §5.3: Snarveier - what the kid picked, hidden by the kid, or off - from the report's `shortcuts`.
        if let Some(shortcuts) = crate::shortcuts::report(status) {
            software.push(Row::new(
                "shortcuts",
                t(lang, "device.about.shortcuts"),
                crate::shortcuts::about_value(lang, &device.name, &policy, &shortcuts),
            ));
        }
        // The apps that can read the phone's notifications; orange for one the parent didn't give through Vibb.
        if let Some(listeners) = crate::notification_listeners::report(status) {
            let known = crate::notification_listeners::known_packages(&state.db, id, &policy).await;
            let installed: Vec<crate::models::InstalledApp> = status
                .installed_apps_json
                .as_deref()
                .and_then(|json| serde_json::from_str(json).ok())
                .unwrap_or_default();
            let row = crate::notification_listeners::about(lang, &listeners, &known, &installed);
            software.push(
                Row::new("listeners", t(lang, "device.about.listeners"), row.value)
                    .class(if row.orange { "row-value-action" } else { "" })
                    .detail(row.detail),
            );
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
            Some(boots) => Row::new("starts", t(lang, "device.about.starts"), boots.value(lang))
                .class("row-value-alarm")
                .detail(Some(boots.detail(&zone, now))),
            None => Row::new(
                "starts",
                t(lang, "device.about.starts"),
                t(lang, "device.about.none"),
            ),
        });
        if let Some(status) = latest.as_ref() {
            let report = status
                .time_state_json
                .as_deref()
                .and_then(crate::time_state::TimeReport::parse)
                .unwrap_or_else(|| {
                    crate::time_state::TimeReport::from_lock_reason(&status.lock_reason)
                });
            // The active rule by its id, as the schedules page words it (design 70 §7).
            let report = if report.locking() && report.active_rule_id.is_some() {
                // Design 93 §4.5: the served rows, a rule turned on now named by its rule.
                let rules = crate::lock_now::badge_rows(&state.db, id, chrono::Utc::now()).await;
                report.named_by(&rules, lang)
            } else {
                report
            };
            let value = if !report.locking() {
                t(lang, "device.about.none").to_string()
            } else if let Some(name) = report.active_rule_name.clone() {
                name
            } else {
                t(
                    lang,
                    match report.lock_reason.as_str() {
                        "BEDTIME" => "devices.status.lock.bedtime",
                        "SCHOOL" => "devices.status.lock.school",
                        "SCREEN_TIME" => "devices.status.lock.screen_time",
                        "RULE" => "device.about.lock_rule",
                        _ => "devices.status.lock.locked",
                    },
                )
                .to_string()
            };
            diagnostics.push(
                Row::new("time-lock", t(lang, "device.time.lock"), value).title(reported_at.map(
                    |at| t_params(lang, "device.about.at_report", &[("at", &zone.exact(at))]),
                )),
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
        0 => t(lang, "device.about.no_crashes").to_string(),
        n => crate::i18n::t_count(lang, "device.about.crash_kinds", n as i64, &[]),
    };

    Html(
        AboutTemplate {
            lang,
            who: crate::handlers::devices::BandAvatar::of(&device),
            title: t_params(lang, "device.about.title", &[("name", &device.name)]),
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
    lang: Lang,
    /// The phone's small picture on the band (design 84, `band::phone`).
    who: crate::handlers::devices::BandAvatar,
    title: String,
    device: Device,
    hardening: Hardening,
    /// The last report's `backup_service_enabled`: `Some(true)` a warning; `Some(false)` and `None`
    /// (unreadable) nothing (design 113: no row in any state).
    backup_service_enabled: Option<bool>,
    block_activity_start: bool,
    /// Advanced › Android Auto (migration 0170): the parent allows it on this phone.
    allow_android_auto: bool,
    /// App screens: Play is openable (block off, Play not suspendable): a sentence behind the block switch's ⓘ.
    play_openable: bool,
    /// App screens: warnings above the group, rows in it.
    play_warnings: Vec<Note>,
    play_rows: Vec<Note>,
    escapes: crate::kiosk_escapes::EscapesCard,
    escape_warnings: Vec<Note>,
    /// Design 114 §5.4: notification access off, at the notification filter (`#updates`).
    setup_warnings: crate::setup_status::Warnings,
    /// Design 114 §5.8: no Parent PIN - no New Setup Code, the line to the PIN in its place.
    no_parent_pin: bool,
    escape_rows: Vec<Note>,
    access: crate::handlers::devices::AccessCard,
    /// The Spotify diagnostic log ([SpotifyDiagCard]); `None` on a phone that doesn't play Spotify.
    spotify_diag: Option<SpotifyDiagCard>,
    /// Design 76: the certificate check's group; `None` until the phone has reported.
    server_tls: Option<crate::server_tls::Card>,
    /// Design 93 §6.6: "Åpne for oppsett", the first row (once the phone has reported).
    setup: Option<crate::handlers::quick_actions::SetupCard>,
    /// Design 102 revision 5: "Krev foreldrekode for Wi-Fi-innlogging" (`#wifi-signin`).
    wifi_signin: crate::handlers::logins::CodeCard,
    /// A refused rename: what was typed and why (the row opens, the reason under the field).
    name_refusal: Option<(String, &'static str)>,
    max_name: usize,
}
crate::i18n::words!(AdvancedTemplate);

/// Advanced's Spotify group (2026-11-01): [until] the local "hh:mm" the diagnostic log runs to, `None` = not running
/// (the button shows).
struct SpotifyDiagCard {
    until: Option<String>,
}

/// The Spotify group for a phone that plays Spotify (bound to an account), from its stored end at [now].
fn spotify_diag_card(
    policy: &DevicePolicy,
    zone: &Zone,
    now: DateTime<Utc>,
) -> Option<SpotifyDiagCard> {
    policy.music_spotify_account?;
    let until = crate::music::spotify_diag_until(policy.music_spotify_diag_until, now.timestamp())
        .and_then(|s| DateTime::<Utc>::from_timestamp(s, 0))
        .map(|t| zone.clock(t));
    Some(SpotifyDiagCard { until })
}

/// GET `/devices/{id}/advanced`: Advanced.
pub async fn view_advanced(
    State(state): State<AppState>,
    lang: Lang,
    Path(id): Path<i64>,
    Query(query): Query<std::collections::HashMap<String, String>>,
) -> Response {
    advanced_page(&state, lang, id, &query, StatusCode::OK, None).await
}

/// Advanced, rendered with [status]; [name_refusal] a refused rename's typed name and reason.
async fn advanced_page(
    state: &AppState,
    lang: Lang,
    id: i64,
    query: &std::collections::HashMap<String, String>,
    status: StatusCode,
    name_refusal: Option<(String, &'static str)>,
) -> Response {
    let state = state.clone();
    let device = match load_device(&state, id).await {
        Ok(device) => device,
        Err((status, key)) => return crate::i18n::error(lang, status, key),
    };
    let policy = match load_policy(&state, id).await {
        Ok(policy) => policy,
        Err((status, key)) => return crate::i18n::error(lang, status, key),
    };
    let latest = match load_latest(&state, id).await {
        Ok(latest) => latest,
        Err(err) => return read_failed(lang, id, "the last report", err),
    };
    let zone = Zone::load(&state.db, lang).await;
    let now = Utc::now();
    let now_ms = now.timestamp_millis();

    let play = play_card(&state, id, latest.as_ref(), now_ms).await;
    let (play_warnings, play_rows): (Vec<Note>, Vec<Note>) = play
        .notes
        .iter()
        .map(|n| n.note(&zone, now))
        .partition(|n| n.warning);
    // Design 76: the group once the phone has reported; a read error hides it (logged) - it never costs the page.
    let server_tls = if latest.is_some() {
        match crate::server_tls::load(&state.db, id).await {
            Ok(row) => Some(crate::server_tls::card(
                &row,
                &zone,
                now,
                query.get("notice").map(String::as_str),
                crate::server_tls::recovery_url(&state).await.as_deref(),
            )),
            Err(err) => {
                tracing::error!(device_id = id, %err, "can't read the certificate check");
                None
            }
        }
    } else {
        None
    };
    let setup = match crate::handlers::quick_actions::setup_card(
        &state.db,
        &device,
        latest.as_ref(),
        query.get("notice").map(String::as_str),
        &zone,
        now,
    )
    .await
    {
        Ok(setup) => setup,
        Err(err) => return read_failed(lang, id, "the opening for setup", err),
    };
    let last_phone_on_ms = crate::parent_events::last_phone_on_ms(&state.db, id)
        .await
        .unwrap_or_else(|err| {
            tracing::error!(device_id = id, %err, "can't read the last start");
            None
        });
    let escapes = escapes_card(&policy, latest.as_ref(), now_ms, last_phone_on_ms);
    let (escape_warnings, escape_rows): (Vec<Note>, Vec<Note>) = escapes
        .notes
        .iter()
        .map(|n| n.note(&zone, now))
        .partition(|n| n.warning);

    (
        status,
        Html(
            AdvancedTemplate {
                lang,
                name_refusal,
                max_name: crate::handlers::devices::MAX_DEVICE_NAME_CHARS,
                who: crate::handlers::devices::BandAvatar::of(&device),
                title: format!("{} · {}", t(lang, "device.page.advanced"), device.name),
                hardening: policy.hardening.clone(),
                backup_service_enabled: latest.as_ref().and_then(|s| s.backup_service_enabled),
                block_activity_start: play.block_activity_start,
                allow_android_auto: policy.allow_android_auto,
                play_openable: play.openable,
                play_warnings,
                play_rows,
                escapes,
                escape_warnings,
                escape_rows,
                setup_warnings: crate::setup_status::page_warnings(
                    lang,
                    &policy,
                    latest.as_ref(),
                    &state.integrations.setup(&state, id).await,
                    "advanced",
                ),
                no_parent_pin: policy.override_pin_hash.is_none(),
                access: crate::handlers::devices::AccessCard {
                    // Security review S-L10's fix round: Leave Tailnet only for a phone on the tailnet.
                    tailnet_joined: latest.as_ref().and_then(|s| s.tailnet_joined) == Some(true),
                    ..crate::handlers::devices::access_card(
                        &state,
                        &device,
                        query.get("notice").map(String::as_str),
                        &zone,
                        now,
                    )
                },
                spotify_diag: spotify_diag_card(&policy, &zone, now),
                server_tls,
                setup,
                wifi_signin: crate::handlers::logins::code_card(&policy, latest.as_ref()),
                device,
            }
            .render()
            .unwrap(),
        ),
    )
        .into_response()
}

#[derive(Deserialize)]
pub struct RenameForm {
    #[serde(default)]
    name: String,
}

/// POST `/devices/{id}/name`: Advanced's "Name" row. The same rule as Add Phone
/// ([crate::handlers::devices::device_name_refusal]); a refusal shows Advanced again (400) with the typed name
/// and the reason under the field, nothing changed. The name is the PWA's alone - the phone never gets it.
pub async fn rename_device(
    State(state): State<AppState>,
    lang: Lang,
    Path(id): Path<i64>,
    Form(form): Form<RenameForm>,
) -> Response {
    let name = form.name.trim();
    if let Err((status, key)) = load_device(&state, id).await {
        return crate::i18n::error(lang, status, key);
    }
    if let Some(why) = crate::handlers::devices::device_name_refusal(lang, name) {
        let typed: String = name
            .chars()
            .take(crate::handlers::devices::MAX_DEVICE_NAME_CHARS * 4)
            .collect();
        return advanced_page(
            &state,
            lang,
            id,
            &std::collections::HashMap::new(),
            StatusCode::BAD_REQUEST,
            Some((typed, why)),
        )
        .await;
    }
    match sqlx::query("UPDATE devices SET name = ? WHERE id = ?")
        .bind(name)
        .bind(id)
        .execute(&state.db)
        .await
    {
        Ok(_) => Redirect::to(&format!("/devices/{id}/advanced#name")).into_response(),
        Err(err) => {
            tracing::error!(device_id = id, %err, "couldn't rename the phone");
            crate::i18n::error(lang, StatusCode::INTERNAL_SERVER_ERROR, "error.save_failed")
        }
    }
}

#[derive(Template)]
#[template(path = "device_lock.html")]
struct LockTemplate {
    lang: Lang,
    /// The phone's small picture on the band (design 84, `band::phone`).
    who: crate::handlers::devices::BandAvatar,
    title: String,
    device: Device,
    /// Child PIN: set or not, its length; the phone's lock as warnings (above the group) and rows.
    child: crate::kid_lock::LockCard,
    child_warnings: Vec<Note>,
    child_rows: Vec<Note>,
    /// The flash after a Child PIN save, `(text, refused)`; a refusal opens the form again.
    child_flash: Option<(&'static str, bool)>,
    child_open: bool,
    /// "Send the Child PIN again" ([crate::kid_lock::offers_send_again]).
    send_again: bool,
    /// What "Show Child PIN…" showed on this page (design 59 §2.2): the PIN or why there is
    /// none; `None` on a GET and after a wrong password, when the form shows (with the error).
    reveal_shown: Option<RevealView>,
    reveal_error: Option<String>,
    /// The page answers Show Child PIN: `static/reveal.js` turns its history entry into a GET
    /// (a reload never posts the password again) and hides the PIN when the page is left.
    reveal_answer: bool,
    /// Parent PIN: set, easy, its flash.
    parent: crate::handlers::parent_pin::ParentPinCard,
    /// A refused (or easy) Parent PIN opens its form again.
    parent_open: bool,
    /// Design 114 §5.8: opened from Connect (`?back=provision`) - the form carries it, so the save lands on the QR.
    back_provision: bool,
    /// Managed without a Parent PIN (the Attention alarm's facts).
    no_parent_pin: bool,
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
    /// Av / Vibrer / Lyd for a notification (design 130, `/lock-alert`).
    lock_alert: crate::lock_glance::NoticeAlert,
    /// The Notifications group: one switch per app and, on a ticked one, its text switch.
    lock_screen: crate::lock_glance::LockScreenCard,
    lock_screen_warnings: Vec<Note>,
    /// Design 114 §5.4: notification access off, at the glance (`#notifications`).
    setup_warnings: crate::setup_status::Warnings,
    /// Each lock-screen row's tile, as the Apps page shows it (package -> [AppTile]).
    lock_tiles: std::collections::HashMap<String, AppTile>,
    /// The parent has a passkey for this host: Show Child PIN offers "Confirm with passkey"
    /// (design 74 D1.6).
    passkey_reauth: bool,
    /// The parent is passkey-only (design 74 D2.2): Show Child PIN takes only the passkey.
    passkey_only: bool,
    /// Design 75 §6: the weather on the lock screen - its switch, the stand-in zone, the notes.
    weather: crate::handlers::weather_page::WeatherCard,
}
crate::i18n::words!(LockTemplate);

/// "Show Child PIN…"'s answer (design 59 §2.2): rendered into the page the POST came from, never
/// a redirect, so the PIN is in no address, session or log.
pub(crate) enum Reveal {
    /// A wrong password (or a passkey that didn't prove the parent, design 74 D1.6); `left` more
    /// before the session ends.
    Wrong {
        left: i64,
        passkey: bool,
    },
    /// A passkey-only parent sent no passkey (design 74 D2.2): nothing was checked.
    NeedsPasskey,
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
    fn of(lang: Lang, reveal: Reveal) -> RevealView {
        use crate::kid_lock::{PhoneHas, ShownPin};
        let mut view = RevealView {
            pin: None,
            label: t(lang, "device.lock.child_pin"),
            line: None,
            prev: None,
            message: None,
            error: None,
        };
        match reveal {
            Reveal::Wrong { left, passkey } => {
                view.error = Some(
                    t(
                        lang,
                        match (left, passkey) {
                            (1, true) => "passkeys.reauth.failed_last",
                            (_, true) => "passkeys.reauth.failed",
                            (1, false) => "device.lock.wrong_last",
                            _ => "device.lock.wrong",
                        },
                    )
                    .to_string(),
                );
            }
            Reveal::NeedsPasskey => {
                view.error = Some(t(lang, "passkeys.only.needs_passkey").to_string());
            }
            Reveal::Shown(ShownPin::Pin { pin, phone }) => {
                view.pin = Some(pin.as_str().to_string());
                match phone {
                    PhoneHas::This => {}
                    PhoneHas::Earlier(Some(prev)) => {
                        view.label = t(lang, "device.lock.new_child_pin");
                        view.line = Some(t(lang, "device.lock.still_prev"));
                        view.prev = Some(prev.as_str().to_string());
                    }
                    PhoneHas::Earlier(None) => {
                        view.label = t(lang, "device.lock.new_child_pin");
                        view.line = Some(t(lang, "device.lock.still_earlier"));
                    }
                    PhoneHas::Changed => {
                        view.line = Some(t(lang, "device.lock.changed_on_phone"));
                    }
                }
            }
            Reveal::Shown(ShownPin::NotStored) => {
                view.message = Some(t(lang, "device.lock.not_stored"));
            }
            Reveal::Shown(ShownPin::Unreadable) => {
                view.message = Some(t(lang, "device.lock.unreadable_key"));
            }
            Reveal::Shown(ShownPin::NoKey) => {
                view.message = Some(t(lang, "device.lock.no_key"));
            }
        }
        view
    }
}

/// GET `/devices/{id}/lock`: Lock Screen & PINs (design 31 §3.2, step 4). The Child PIN
/// (`/kid-lock`: set in a `<details>`, never removed - design 132; design 59: "Show Child PIN…" posting here,
/// "Send the Child PIN again"
/// `/kid-pin/send`), the Parent PIN (`/parent-pin`, with what the phone reported), the screen
/// timeout and the lock's background (inline selects), one select per app - Off, Name only, Full
/// text (`/lock-screen/apps`). The forms return here. A phone not set up yet has the page too: its
/// PINs can be set before setup.
pub async fn view_lock(
    State(state): State<AppState>,
    lang: Lang,
    Path(id): Path<i64>,
    ceremony: crate::passkeys::Ceremony,
    axum::Extension(crate::security::CurrentAdmin(admin)): axum::Extension<
        crate::security::CurrentAdmin,
    >,
    Query(query): Query<std::collections::HashMap<String, String>>,
) -> Response {
    let notice = query.get("notice").map(String::as_str);
    let passkey = crate::passkeys::has_here(&state.db, admin.id, &ceremony).await;
    render_lock(
        &state,
        id,
        notice,
        None,
        (passkey, admin.passkey_only),
        lang,
        query.get("back").map(String::as_str) == Some("provision"),
    )
    .await
}

/// POST `/devices/{id}/lock`, `action=reveal_kid_pin` (design 59 §2.2, qa-59-design Q59-11): the
/// parent's password through [crate::admin_auth::reauth] (three wrong in a row end the session),
/// then the page itself with the Child PIN in its group - posted to the page's own path, so
/// `scroll-restore.js` keeps the place. No redirect: the PIN is in no address. `no_store_path`
/// covers the answer, and the security log names the phone only.
pub async fn lock_action(
    State(state): State<AppState>,
    lang: Lang,
    Path(id): Path<i64>,
    session: tower_sessions::Session,
    ceremony: crate::passkeys::Ceremony,
    axum::Extension(crate::security::CurrentAdmin(admin)): axum::Extension<
        crate::security::CurrentAdmin,
    >,
    axum::Form(form): axum::Form<std::collections::HashMap<String, String>>,
) -> Response {
    if form.get("action").map(String::as_str) != Some("reveal_kid_pin") {
        return crate::i18n::error(lang, StatusCode::BAD_REQUEST, "error.out_of_date");
    }
    let device = match load_device(&state, id).await {
        Ok(device) => device,
        Err((status, key)) => return crate::i18n::error(lang, status, key),
    };
    let password = form.get("password").map(String::as_str).unwrap_or_default();
    let assertion = form
        .get("assertion")
        .map(String::as_str)
        .unwrap_or_default();
    let proof = crate::admin_auth::Proof::of(password, assertion, &ceremony);
    let log = |event: &'static str| {
        let (db, username) = (state.db.clone(), admin.username.clone());
        async move {
            crate::security::record_security_event(
                &db,
                event,
                Some(&username),
                None,
                Some(crate::security::Detail::facts(format!("device {id}"))),
            )
            .await;
        }
    };
    let reveal = match crate::admin_auth::reauth(&state, &session, &admin, proof).await {
        crate::admin_auth::Reauth::Busy => return crate::admin_auth::busy_response(lang),
        crate::admin_auth::Reauth::SignedOut => {
            log("kid_pin_reveal_refused").await;
            return crate::admin_auth::signed_out_response();
        }
        crate::admin_auth::Reauth::Wrong { left } => {
            log("kid_pin_reveal_refused").await;
            Reveal::Wrong {
                left,
                passkey: proof.is_passkey(),
            }
        }
        // A passkey-only parent's empty password: nothing checked, nothing counted (qa-passkeys-2 M1).
        crate::admin_auth::Reauth::NeedsPasskey => Reveal::NeedsPasskey,
        crate::admin_auth::Reauth::Right => {
            let latest = match load_latest(&state, id).await {
                Ok(latest) => latest,
                Err(err) => return read_failed(lang, id, "the last report", err),
            };
            let kept = match crate::kid_lock::KeptPin::load(&state.db, id).await {
                Ok(kept) => kept,
                Err(err) => return read_failed(lang, id, "the Child PIN", err),
            };
            let lock_state = crate::kid_lock::parse_lock_state(
                latest.as_ref().and_then(|s| s.lock_state_json.as_deref()),
            );
            // What the phone has, from this install's reports only (S2P-4).
            let phone = phone_lock(
                device.enrolled_at.as_deref(),
                since_enrolment(device.enrolled_at.as_deref(), latest.as_ref()),
                lock_state.as_ref(),
                false,
            );
            let shown = crate::kid_lock::shown_pin(state.kid_pin_key.as_deref(), &kept, id, &phone);
            log("kid_pin_shown").await;
            Reveal::Shown(shown)
        }
    };
    let passkey = crate::passkeys::has_here(&state.db, admin.id, &ceremony).await;
    render_lock(
        &state,
        id,
        None,
        Some(reveal),
        (passkey, admin.passkey_only),
        lang,
        false,
    )
    .await
}

/// What the last report says about the lock ([crate::kid_lock::PhoneLock]).
fn phone_lock<'a>(
    enrolled_at: Option<&str>,
    latest: Option<&DeviceStatus>,
    state: Option<&'a crate::kid_lock::LockState>,
    pin_on_its_way: bool,
) -> crate::kid_lock::PhoneLock<'a> {
    crate::kid_lock::PhoneLock {
        state,
        android_capable: has_capability(latest, crate::kid_lock::ANDROID_KEYGUARD_CAPABILITY),
        pin_on_its_way,
        this_enrolment: since_enrolment(enrolled_at, latest).is_some(),
        no_pin: crate::setup_status::reported_child_pin(latest) == Some(Some(false)),
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

/// Lock Screen & PINs, with `notice`'s flash and, after "Show Child PIN…", its answer (a wrong
/// password is a 400).
async fn render_lock(
    state: &AppState,
    id: i64,
    notice: Option<&str>,
    reveal: Option<Reveal>,
    (passkey_reauth, passkey_only): (bool, bool),
    lang: Lang,
    back_provision: bool,
) -> Response {
    let device = match load_device(state, id).await {
        Ok(device) => device,
        Err((status, key)) => return crate::i18n::error(lang, status, key),
    };
    let policy = match load_policy(state, id).await {
        Ok(policy) => policy,
        Err((status, key)) => return crate::i18n::error(lang, status, key),
    };
    let latest = match load_latest(state, id).await {
        Ok(latest) => latest,
        Err(err) => return read_failed(lang, id, "the last report", err),
    };
    let zone = Zone::load(&state.db, lang).await;
    let now = Utc::now();

    let lock_state = crate::kid_lock::parse_lock_state(
        latest.as_ref().and_then(|s| s.lock_state_json.as_deref()),
    );
    // Design 59: the newest set_kid_pin.
    let pin_send = match crate::kid_lock::newest_pin_send(&state.db, id, &zone, now).await {
        Ok(send) => send,
        Err(err) => return read_failed(lang, id, "the Child PIN's command", err),
    };
    let sending = crate::kid_lock::on_its_way(pin_send.as_ref());
    let phone = phone_lock(
        device.enrolled_at.as_deref(),
        latest.as_ref(),
        lock_state.as_ref(),
        sending,
    );
    let child = crate::kid_lock::lock_card(&policy, &phone);
    // "Send again" reads only this install's reports, as the queue rule does (qa-59-step2-code
    // S2P-4, step 1's S59-3): before a re-enrolled phone's first report, no button.
    let send_again = crate::kid_lock::offers_send_again(&policy, &phone);
    // The report's time on "On the phone", so an old report never reads as now (qa-31-s4-code L3).
    let reported = latest
        .as_ref()
        .and_then(|s| crate::display_time::parse_db_utc(&s.reported_at))
        .map(|t| zone.when(t, now));
    let (mut child_warnings, mut child_rows): (Vec<Note>, Vec<Note>) = child
        .notes
        .iter()
        .map(|n| n.note(lang, reported.as_deref()))
        .partition(|n| n.warning);
    let setup_part = state.integrations.setup(state, id).await;
    // Design 114b: the setup step `child_pin` missing, said once at the Child PIN, the fix behind its ⓘ. No Child PIN
    // stored (whatever PIN the phone has, code QA M2): "Ingen barne-PIN". One stored but the phone has no PIN (code QA
    // L1: in place of "not the Child PIN", which `PhoneLock::no_pin` holds back): "Telefonen har ingen PIN" - unless a
    // send is waiting or under way, which the card's own row says, or the card says it already (design 132 §4.5's
    // no-Android-PIN line, the lock report's own).
    let card_says_it = child
        .notes
        .iter()
        .any(|n| matches!(n, crate::kid_lock::LockNote::NoAndroidPin { .. }));
    if crate::setup_status::child_pin_missing(&policy, latest.as_ref(), &setup_part)
        && (!child.pin_set || !sending)
        && !card_says_it
    {
        let (title, fix) = crate::kid_lock::no_pin_line(child.pin_set, send_again);
        child_warnings.insert(
            0,
            Note {
                warning: true,
                title: t(lang, title).to_string(),
                value: None,
                sub: None,
                detail: None,
                fix: Some(t(lang, fix).to_string()),
            },
        );
    }
    if let Some(send) = pin_send.filter(|_| child.pin_set) {
        child_rows.push(crate::kid_lock::LockNote::PinSent(send).note(lang, None));
    }
    let parent = crate::handlers::parent_pin::card(lang, &policy, notice);
    let weather = match crate::handlers::weather_page::card(state, id, &policy).await {
        Ok(weather) => weather,
        Err(err) => return read_failed(lang, id, "the phone's zones", err),
    };

    let backgrounds: Vec<_> = crate::handlers::wallpapers::device_choices(state, lang, id)
        .await
        .unwrap_or_else(|err| {
            tracing::error!(device_id = id, %err, "couldn't load the lock screen's backgrounds");
            Vec::new()
        })
        .into_iter()
        .filter(|w| w.checked)
        .collect();
    let rows =
        match crate::handlers::devices::app_rows(state, id, &policy, latest.as_ref(), lang).await {
            Ok(rows) => rows,
            Err(err) => return read_failed(lang, id, "the phone's apps", err),
        };
    let lock_screen =
        crate::handlers::devices::lock_screen_card(state, lang, &policy, latest.as_ref(), &rows)
            .await;
    let lock_tiles = match lock_tiles(state, id, &lock_screen).await {
        Ok(tiles) => tiles,
        Err(err) => return read_failed(lang, id, "the phone's app tiles", err),
    };
    // Design r7 §1.1.3: which apps rang over the lock, named as the Apps page names them.
    let labels: std::collections::HashMap<String, String> = rows
        .apps
        .iter()
        .map(|app| (app.package_name.clone(), app.label.clone()))
        .collect();
    child_rows.extend(
        crate::kid_lock::rang_notes(&child, lock_state.as_ref(), &labels, &zone, now)
            .iter()
            .map(|n| n.note(lang, None)),
    );
    let screen_timeout_seconds =
        crate::models::screen_timeout_seconds(policy.screen_timeout_seconds);
    let child_flash = notice.and_then(|code| crate::kid_lock::flash_text(lang, code));
    let reveal_answer = reveal.is_some();
    let mut reveal_view = reveal.map(|reveal| RevealView::of(lang, reveal));
    let reveal_error = reveal_view.as_mut().and_then(|r| r.error.take());
    let reveal_shown = reveal_view.filter(|r| r.pin.is_some() || r.message.is_some());
    let status = if reveal_error.is_some() {
        StatusCode::BAD_REQUEST
    } else {
        StatusCode::OK
    };

    let page = LockTemplate {
        passkey_reauth,
        passkey_only,
        lang,
        who: crate::handlers::devices::BandAvatar::of(&device),
        title: format!("{} · {}", t(lang, "device.page.lock"), device.name),
        child_warnings,
        child_rows,
        child_flash,
        // A refusal opens the Child PIN's form again - not "Not sent", which it can't fix.
        child_open: child_flash.is_some_and(|(_, refused)| refused)
            && notice != Some("pin_not_sent"),
        send_again,
        reveal_answer,
        reveal_error,
        reveal_shown,
        parent_open: parent.notice.is_some_and(|(_, warning)| warning) || back_provision,
        back_provision,
        no_parent_pin: crate::handlers::devices::managed_without_pin(&policy),
        screen_timeout_seconds,
        screen_timeout_options: crate::models::SCREEN_TIMEOUTS.to_vec(),
        // Only when the phone runs another value than the chosen one (it applies it at its
        // next sync).
        screen_timeout_applied: latest
            .as_ref()
            .and_then(|s| s.screen_timeout_seconds)
            .filter(|applied| *applied != screen_timeout_seconds)
            .map(|seconds| crate::models::screen_timeout_label(lang, seconds)),
        background_same_as_home: !backgrounds.iter().any(|w| w.lock),
        backgrounds,
        lock_wake: policy.lock_wake_on_notification,
        lock_alert: crate::lock_glance::NoticeAlert::stored(&policy.lock_notice_alert),
        lock_screen_warnings: lock_screen.notes.iter().map(|n| n.note(lang)).collect(),
        setup_warnings: crate::setup_status::page_warnings(
            lang,
            &policy,
            latest.as_ref(),
            &setup_part,
            "lock",
        ),
        lock_tiles,
        lock_screen,
        child,
        parent,
        weather,
        device,
    };
    (status, Html(page.render().unwrap())).into_response()
}

/// A read a sub-page can't do without: logged, and the page is a 500 - never an empty list that
/// reads as "nothing set" (qa-31-s4-code L4).
fn read_failed(lang: Lang, id: i64, what: &str, err: sqlx::Error) -> Response {
    tracing::error!(device_id = id, %err, "can't read {what}");
    crate::i18n::error(lang, StatusCode::INTERNAL_SERVER_ERROR, "error.read_phone")
}

#[derive(Template)]
#[template(path = "device_home.html")]
struct HomeTemplate {
    lang: Lang,
    /// The phone's small picture on the band (design 84, `band::phone`).
    who: crate::handlers::devices::BandAvatar,
    title: String,
    device: Device,
    /// Design 114 §5.4: the missing setup steps at the choices they stop (`#layout`: the clock format and the badges;
    /// `#quick-controls`: Flymodus and the touch and sound switches).
    setup_warnings: crate::setup_status::Warnings,
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
    /// Security review S-L5: the Bluetooth page pairs any device (`bluetooth_any_device`).
    bluetooth_any_device: bool,
    /// Design 98 §6.1: the kid's Flymodus switch (bit 64) - "Blokkert under Begrensninger" when it is
    /// (`airplane_mode::home_sub`).
    airplane_sub: Option<String>,
    quick_airplane: bool,
    /// Design 99 §5.2: Snarveier (bit 128) - its sub-line when it says something (`shortcuts::home_sub`, from a
    /// report that has `shortcuts`).
    shortcuts_sub: Option<String>,
    quick_shortcuts: bool,
}
crate::i18n::words!(HomeTemplate);

/// GET `/devices/{id}/home`: Home Screen (design 31 §3.2, step 5). Language and apps per row (one
/// form, `/launcher`), the wallpapers this phone may use (one form, `/wallpapers`) and the quick
/// controls (`/policy` with `section=quick`). Every form auto-saves and returns here without a
/// fragment. A phone not set up has the page too: what is saved reaches it with its first
/// settings.
pub async fn view_home(State(state): State<AppState>, lang: Lang, Path(id): Path<i64>) -> Response {
    use crate::handlers::devices::{
        QUICK_CONTROL_BLUETOOTH, QUICK_CONTROL_BRIGHTNESS, QUICK_CONTROL_SOUND,
        QUICK_CONTROL_TOUCH, QUICK_CONTROL_WIFI,
    };
    let device = match load_device(&state, id).await {
        Ok(device) => device,
        Err((status, key)) => return crate::i18n::error(lang, status, key),
    };
    let policy = match load_policy(&state, id).await {
        Ok(policy) => policy,
        Err((status, key)) => return crate::i18n::error(lang, status, key),
    };
    // A failed read must not render an empty grid: its Save would untick every wallpaper.
    let wallpapers = match crate::handlers::wallpapers::device_choices(&state, lang, id).await {
        Ok(wallpapers) => wallpapers,
        Err(err) => return read_failed(lang, id, "the wallpapers", err),
    };
    let latest = match load_latest(&state, id).await {
        Ok(latest) => latest,
        Err(err) => return read_failed(lang, id, "the last report", err),
    };
    let mask = policy.quick_controls_mask;
    let airplane_sub = crate::airplane_mode::home_sub(lang, &policy);
    let setup_warnings = crate::setup_status::page_warnings(
        lang,
        &policy,
        latest.as_ref(),
        &state.integrations.setup(&state, id).await,
        "home",
    );
    let shortcuts_sub = latest
        .as_ref()
        .and_then(crate::shortcuts::report)
        .and_then(|report| crate::shortcuts::home_sub(lang, &device.name, &policy, &report));
    Html(
        HomeTemplate {
            lang,
            who: crate::handlers::devices::BandAvatar::of(&device),
            title: format!("{} · {}", t(lang, "device.page.home"), device.name),
            setup_warnings,
            launcher_language: policy.launcher_language.clone(),
            home_columns: policy.home_columns,
            clock_format: crate::models::clock_format(&policy.clock_format),
            wallpapers,
            quick_wifi: mask & QUICK_CONTROL_WIFI != 0,
            quick_bluetooth: mask & QUICK_CONTROL_BLUETOOTH != 0,
            quick_brightness: mask & QUICK_CONTROL_BRIGHTNESS != 0,
            quick_sound: mask & QUICK_CONTROL_SOUND != 0,
            quick_touch: mask & QUICK_CONTROL_TOUCH != 0,
            bluetooth_any_device: policy.bluetooth_any_device,
            airplane_sub,
            quick_airplane: mask & crate::handlers::devices::QUICK_CONTROL_AIRPLANE != 0,
            shortcuts_sub,
            quick_shortcuts: mask & crate::handlers::devices::QUICK_CONTROL_SHORTCUTS != 0,
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
    lang: Lang,
    /// The phone's small picture on the band (design 84, `band::phone`).
    who: crate::handlers::devices::BandAvatar,
    title: String,
    /// An allowed host the phone blocks anyway (`dns_filter::doh_allowed_notice`, design 67).
    doh_allowed: Option<String>,
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
    /// Design 102 §5: the Sign-in row's value ("Wi-Fi: på · Apper: av"; design 113: was its sub-line).
    logins_sub: String,
    /// Design 116 §3.4: "Down for 25 min" (" · at last report" while the phone is Offline or Disconnected) - the
    /// Attention row's fact as this page's orange line; `None` while the filter isn't down.
    filter_down: Option<String>,
    /// The filter switch is on but the phone's latest report is paused (the setup hold or a pause,
    /// `DeviceStatus::paused_at`): the row's quiet value, "Off until 14:30" ("Off" when the end
    /// isn't known). The parent's choice, never orange.
    paused_value: Option<String>,
}
crate::i18n::words!(FilterTemplate);

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
/// refusal under its group's add row (qa-31-s5-code L1).
pub async fn view_filter(
    State(state): State<AppState>,
    lang: Lang,
    Path(id): Path<i64>,
    Query(query): Query<std::collections::HashMap<String, String>>,
) -> Response {
    let device = match load_device(&state, id).await {
        Ok(device) => device,
        Err((status, key)) => return crate::i18n::error(lang, status, key),
    };
    let policy = match load_policy(&state, id).await {
        Ok(policy) => policy,
        Err((status, key)) => return crate::i18n::error(lang, status, key),
    };
    let lists = match sqlx::query_as::<_, (i64, String, String, bool, Option<bool>)>(
        "SELECT b.id, b.name, b.url, b.enabled, o.enabled FROM dns_blocklists b \
         LEFT JOIN device_blocklist_overrides o ON o.blocklist_id = b.id AND o.device_id = ? \
         ORDER BY b.name",
    )
    .bind(id)
    .fetch_all(&state.db)
    .await
    {
        Ok(rows) => rows,
        Err(err) => return read_failed(lang, id, "the blocklists", err),
    };
    let lists = {
        let compiled = state.dns_compiled.read().await;
        lists
            .into_iter()
            .map(|(list_id, name, url, everyone_on, own)| FilterList {
                setting: match own {
                    None => "default",
                    Some(true) => "on",
                    Some(false) => "off",
                },
                everyone_on,
                problem: crate::handlers::dns_filter::FeedState::of(&compiled, list_id)
                    .in_force(own.unwrap_or(everyone_on))
                    .problem(lang),
                id: list_id,
                name: crate::models::blocklist_name(lang, &name, &url),
            })
            .collect::<Vec<FilterList>>()
    };
    // In the order the reader reads them (qa-s-i18n-4-code L6).
    let mut lists = lists;
    lists.sort_by_key(|list| list.name.to_lowercase());
    let domains = match sqlx::query_as::<_, crate::models::DnsCustomDomain>(
        "SELECT * FROM dns_custom_domains WHERE device_id = ? ORDER BY domain",
    )
    .bind(id)
    .fetch_all(&state.db)
    .await
    {
        Ok(domains) => domains,
        Err(err) => return read_failed(lang, id, "the phone's domains", err),
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
            Err(err) => return read_failed(lang, id, "the site-wide domains", err),
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
            Err(err) => return read_failed(lang, id, "the blocked-domain log", err),
        }
    } else {
        Vec::new()
    };
    let zone = Zone::load(&state.db, lang).await;
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
            when: at.map_or_else(
                || t(lang, "device.filter.time_unknown").to_string(),
                |at| zone.when(at, now),
            ),
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
    let latest = match load_latest(&state, id).await {
        Ok(latest) => latest,
        Err(err) => return read_failed(lang, id, "the last report", err),
    };
    let paused = policy.vpn_filter_enabled
        && latest
            .as_ref()
            .is_some_and(|s| s.paused_at(now.timestamp_millis()));
    let paused_value = if paused {
        match crate::handlers::quick_actions::setup_until(&state.db, id, latest.as_ref(), now).await
        {
            Ok(Some(at)) => Some(crate::i18n::t_params(
                lang,
                "device.filter.off_until",
                &[("at", &zone.clock(at))],
            )),
            Ok(None) => Some(t(lang, "device.filter.off").to_string()),
            Err(err) => return read_failed(lang, id, "the pause's end", err),
        }
    } else {
        None
    };
    let filter_down =
        crate::mobile_network::filter_down(&policy, latest.as_ref(), now.timestamp_millis()).map(
            |report| {
                let stale = matches!(
                    crate::handlers::devices::connection_of(&state, &device, now),
                    crate::streams::ConnectionState::Offline { .. }
                        | crate::streams::ConnectionState::Disconnected
                );
                // While the phone is connected the period runs on past its report (code QA L2): the phone's length
                // plus the server's time since the report - two durations, each on its own clock, never two instants
                // subtracted across clocks.
                let since_ms = if stale {
                    0
                } else {
                    latest
                        .as_ref()
                        .and_then(|s| crate::display_time::parse_db_utc(&s.reported_at))
                        .map_or(0, |at| (now - at).num_milliseconds().max(0))
                };
                let minutes = (report.down_ms.unwrap_or(0) + since_ms) / 60_000;
                let line = crate::i18n::t_params(
                    lang,
                    "device.filter.down",
                    &[("duration", &crate::display_time::duration(lang, minutes))],
                );
                if stale {
                    format!("{line} · {}", t(lang, "devices.status.lock.at_last_report"))
                } else {
                    line
                }
            },
        );
    Html(
        FilterTemplate {
            lang,
            who: crate::handlers::devices::BandAvatar::of(&device),
            title: format!("{} · {}", t(lang, "device.page.filter"), device.name),
            refused,
            doh_allowed: crate::handlers::dns_filter::doh_allowed_notice(&query),
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
            logins_sub: crate::handlers::logins::row_sub(lang, &policy),
            filter_down,
            paused_value,
            device,
        }
        .render()
        .unwrap(),
    )
    .into_response()
}

/// An app's tile (design 87 §8.1, revision 3): the real logo over the letter, else the chosen glyph on its colour,
/// else the letter - what `partials/device_app_row.html` draws, here for the Lock page's list.
pub struct AppTile {
    pub logo: Option<String>,
    pub icon: Option<(String, &'static str)>,
    pub letter: String,
}

/// The tiles of the lock-screen list's rows, from the same reads and rule as the Apps page.
async fn lock_tiles(
    state: &AppState,
    id: i64,
    card: &crate::lock_glance::LockScreenCard,
) -> Result<std::collections::HashMap<String, AppTile>, sqlx::Error> {
    use crate::app_display as display;
    let defaults = display::catalog_defaults(&state.db).await?;
    let own = display::device_rows(&state.db, id).await?;
    let logos = crate::app_logos::phone_logos(state, id).await?;
    Ok(card
        .apps
        .iter()
        .map(|app| {
            let package = &app.package_name;
            let tile = AppTile {
                logo: logos.get(package).cloned(),
                icon: display::icon_of(&own, &defaults, package),
                letter: crate::handlers::devices::initial(&app.label),
            };
            (package.clone(), tile)
        })
        .collect())
}

/// One app on Device › Apps: the row `devices::app_rows` built, where it is (`devices::app_anchor`),
/// its tile, where its own page is and why a switch was refused.
pub struct AppView {
    pub row: crate::handlers::devices::UnifiedAppRow,
    pub anchor: String,
    /// The tile without a logo: the icon this phone shows for it (its own choice, else the catalog's) on that
    /// colour, else the name's first letter.
    pub icon: Option<(String, &'static str)>,
    /// Design 87 §8.1 (revision 3): the app's real logo this phone sent (its URL; Vibb One's row the server's Vibb
    /// mark) over the letter, which shows if it doesn't load - the row's tile whatever glyph was chosen.
    pub logo: Option<String>,
    pub letter: String,
    /// Design 127 §2.2: the app's own page (`/devices/{id}/apps/{package}`, with `from`) - every allowed app with a
    /// package name but Vibb, Kalender and the managed dialer. The row's name opens it; its switch stays on the row.
    pub page: Option<String>,
    /// Design 82 D10: a part of Vibb One (Meldinger), not an app on the phone - no switch, only its row and its page.
    pub builtin: bool,
    /// A part of Vibb One whose settings live on a page of their own (Kalender, 2026-11-12: Device › Calendar): the
    /// row is a link there, with its value (On Home or not).
    pub link: Option<(String, &'static str)>,
    /// A refused switch, under the row (`toggle_app`'s `?notice=<code>&app=<row>`,
    /// `devices::toggle_refusal`).
    pub refused: Option<&'static str>,
    /// Design 109: a line under the system Phone app's switch while calls are unmanaged - what off costs, and whether
    /// it is an alarm (`text-alarm`).
    pub dialer_note: Option<(&'static str, bool)>,
    /// Design 127 §2.2: the one thing on the list that waits for the parent - an update held for approval
    /// ("7.25.0 venter på deg", orange).
    pub waits: Option<String>,
    /// Design 127 (code QA L4): an allowed app the parent keeps off Home - a quiet "Ikke på Hjem" on its row, never
    /// orange (a parent's choice).
    pub off_home: bool,
}

/// A tab of the bar (`partials/tab_bar.html`'s five), for the pages whose tab is a field rather than a literal
/// `{% let tab = "..." %}`: Device › Apps and an app's own page (design 127), opened from the Apps tab
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
/// (`#phone-<id>`), or at its phones' group (`#phones`, always there) when the app's page doesn't
/// list this phone (unticked here since: the page lists the phones with it ticked, the launcher's
/// every set-up phone; qa-pwa-polish-a Q-10); a catalog app deleted since is the list. Also the
/// `from` the page keeps.
async fn apps_nav(
    state: &AppState,
    device: &Device,
    from: Option<AppsFrom>,
    lang: Lang,
) -> Result<(Tab, String, String, Option<AppsFrom>), sqlx::Error> {
    let list = (
        Tab::Apps,
        format!("/apps#phone-{}", device.id),
        t(lang, "nav.apps").to_string(),
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
            match sqlx::query_as::<_, (String, bool, bool)>(
                "SELECT name, is_launcher, EXISTS(SELECT 1 FROM device_tracked_apps \
                 WHERE device_id = ? AND tracked_app_id = tracked_apps.id) \
                 FROM tracked_apps WHERE id = ?",
            )
            .bind(device.id)
            .bind(app)
            .fetch_optional(&state.db)
            .await?
            {
                Some((name, is_launcher, ticked)) => {
                    let listed = if is_launcher {
                        device.enrolled_at.is_some()
                    } else {
                        ticked
                    };
                    let row = if listed {
                        format!("phone-{}", device.id)
                    } else {
                        "phones".to_string()
                    };
                    (Tab::Apps, format!("/apps/tracked/{app}#{row}"), name, from)
                }
                None => list,
            }
        }
    })
}

#[derive(Template)]
#[template(path = "device_apps.html")]
struct AppsTemplate {
    lang: Lang,
    /// Design 114 §5.4: the background connection and Data Saver, at App push (`#app-push`).
    setup_warnings: crate::setup_status::Warnings,
    /// The phone's small picture on the band (design 84, `band::phone`).
    who: crate::handlers::devices::BandAvatar,
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
    /// Design 102 §5.2: Chrome is the phone's login browser (the role on, Chrome not ticked) - its row says so.
    login_role: bool,
    /// Something installs now: the page reloads every 3 s for the percentage
    /// (`static/auto-reload.js`) - never on a refused save's page, the answer to a POST.
    installing: bool,
    wifi_only: bool,
    /// The phone's network at its last report, with its time (`app_downloads::network`).
    network: Option<Row>,
    /// Design 108 §3.10: app push on this phone (`#app-push`).
    app_push: crate::handlers::app_push::AppPushGroup,
    /// The integrations' groups for this phone, rendered by each (`integrations::Integration::apps_group`; design
    /// 110's "Map links"), after App push.
    integration_groups: Vec<String>,
}
crate::i18n::words!(AppsTemplate);

/// GET `/devices/{id}/apps`: Device › Apps (design 31 §3.2, step 6; design 127 §2.2). The phone's apps and the
/// catalog's in two groups, Allowed and Not allowed, each a switch (`/apps/toggle`, `back=apps`); an allowed app's
/// name opens its own page (`app_page`), its switch stays on the row; Download apps on Wi-Fi only (`/app-updates`) and
/// the phone's network at the bottom. A switch returns to the app's row (`#app-<package>`), the Wi-Fi switch to
/// `#downloads`. While an app installs, the page reloads itself every 3 s. A phone not set up has the page too: a
/// catalog app turned on installs with its first settings. The old `?perms=<package>` goes to the app's page.
pub async fn view_apps(
    State(state): State<AppState>,
    lang: crate::i18n::Lang,
    Path(id): Path<i64>,
    Query(query): Query<std::collections::HashMap<String, String>>,
) -> Response {
    let from = query.get("from").and_then(|f| AppsFrom::parse(f));
    // Design 127 §2.1: Permissions moved to the app's own page.
    if let Some(package) = query
        .get("perms")
        .filter(|p| crate::app_display::valid_package_name(p))
    {
        return Redirect::to(&crate::handlers::app_page::page_url(
            id, package, "", from, "access",
        ))
        .into_response();
    }
    let refused = query.get("app").and_then(|row| {
        query
            .get("notice")
            .and_then(|code| crate::handlers::devices::toggle_refusal(lang, code))
            .map(|text| (row.as_str(), text))
    });
    render_apps(&state, lang, id, refused, from).await
}

/// What Device › Apps and an app's own page (design 127) read for a phone, read once and the same way: a failed read
/// is the page's 500, never every app shown as "its own" or every switch off.
pub(crate) struct AppsData {
    pub device: Device,
    pub policy: DevicePolicy,
    pub latest: Option<DeviceStatus>,
    pub rows: crate::handlers::devices::AppRows,
    pub defaults: std::collections::BTreeMap<String, crate::app_display::DisplayValues>,
    pub own: std::collections::BTreeMap<String, crate::app_display::DisplayValues>,
    pub off_home: std::collections::BTreeSet<String>,
    /// Design 127 §3.4: the keys the parent switched off per app (the policy's `app_permissions`).
    pub denials: Vec<crate::app_permissions::AppPermission>,
    /// 2026-11-04: the apps shown as media players on the lock screen.
    pub lock_media: Vec<String>,
    /// Design 87: the apps' logos this phone sent, and the catalog's for a ticked app not installed (2026-11-19).
    pub logos: std::collections::HashMap<String, String>,
    /// Design 82 W2: the phone still has threads to read.
    pub messages_history: bool,
    /// The phone's last report says restrictions are paused.
    pub paused: bool,
    /// Design 109: the phone's system dialer, from its last report.
    pub dialer: Option<String>,
}

impl AppsData {
    pub(crate) async fn load(state: &AppState, lang: Lang, id: i64) -> Result<Self, Box<Response>> {
        let device = load_device(state, id)
            .await
            .map_err(|(status, key)| Box::new(crate::i18n::error(lang, status, key)))?;
        let policy = load_policy(state, id)
            .await
            .map_err(|(status, key)| Box::new(crate::i18n::error(lang, status, key)))?;
        let latest = load_latest(state, id)
            .await
            .map_err(|err| Box::new(read_failed(lang, id, "the last report", err)))?;
        let rows = crate::handlers::devices::app_rows(state, id, &policy, latest.as_ref(), lang)
            .await
            .map_err(|err| Box::new(read_failed(lang, id, "the phone's apps", err)))?;
        let defaults = crate::app_display::catalog_defaults(&state.db)
            .await
            .map_err(|err| Box::new(read_failed(lang, id, "the catalog's app names", err)))?;
        let own = crate::app_display::device_rows(&state.db, id)
            .await
            .map_err(|err| Box::new(read_failed(lang, id, "the phone's app names", err)))?;
        let off_home = crate::app_display::off_home(&state.db, id)
            .await
            .map_err(|err| Box::new(read_failed(lang, id, "the phone's apps off Home", err)))?;
        let denials = crate::app_permissions::policy_app_permissions(&state.db, id)
            .await
            .map_err(|err| Box::new(read_failed(lang, id, "the phone's app permissions", err)))?;
        let lock_media = crate::app_permissions::lock_media_apps(&state.db, id)
            .await
            .map_err(|err| {
                Box::new(read_failed(
                    lang,
                    id,
                    "the phone's lock-screen media apps",
                    err,
                ))
            })?;
        let mut logos = crate::app_logos::phone_logos(state, id)
            .await
            .map_err(|err| Box::new(read_failed(lang, id, "the phone's app logos", err)))?;
        // 2026-11-19: a catalog app ticked for this phone and not installed on it shows the catalog's logo (its kept
        // one included); an installed app only ever its own (design 87 Q3). A failed catalog read is logged.
        let missing: Vec<String> = rows
            .apps
            .iter()
            .filter(|a| {
                a.tracked_app_id.is_some()
                    && !logos.contains_key(&a.package_name)
                    && !rows
                        .installed
                        .iter()
                        .any(|i| i.package_name == a.package_name)
            })
            .map(|a| a.package_name.clone())
            .collect();
        match crate::app_logos::catalog_logos_for(state, &missing).await {
            Ok(catalog) => logos.extend(catalog),
            Err(err) => tracing::error!(device_id = id, %err, "can't read the catalog's app logos"),
        }
        let messages_history = crate::sms::reported_history(&state.db, id)
            .await
            .map_err(|err| Box::new(read_failed(lang, id, "the phone's message state", err)))?;
        let paused = latest
            .as_ref()
            .is_some_and(|s| s.paused_at(Utc::now().timestamp_millis()));
        let dialer = crate::handlers::device_api::system_dialer(
            latest
                .as_ref()
                .and_then(|s| s.call_state_json.as_deref())
                .and_then(|json| serde_json::from_str::<serde_json::Value>(json).ok())
                .as_ref(),
        );
        Ok(AppsData {
            device,
            policy,
            latest,
            rows,
            defaults,
            own,
            off_home,
            denials,
            lock_media,
            logos,
            messages_history,
            paused,
            dialer,
        })
    }

    /// Calls are managed and `package` is the system dialer: its row is a link to Calls, with no page (design 109).
    pub(crate) fn managed_dialer(&self, package: &str) -> bool {
        self.policy.calls_managed() && self.dialer.as_deref() == Some(package)
    }

    /// Meldinger is on the list (design 82 D10): SMS managed, a row of its own, or threads left to read.
    pub(crate) fn messages_listed(&self) -> bool {
        let key = crate::builtin::MESSAGES;
        self.policy.sms_mode == "managed"
            || self.own.contains_key(key)
            || self.off_home.contains(key)
            || self.messages_history
    }

    /// Design 109: the note under the system Phone app while calls are unmanaged - what off costs, and whether it is
    /// an alarm.
    pub(crate) fn dialer_note(
        &self,
        lang: Lang,
        row: &crate::handlers::devices::UnifiedAppRow,
    ) -> Option<(&'static str, bool)> {
        if self.policy.calls_managed() || self.dialer.as_deref() != Some(row.package_name.as_str())
        {
            return None;
        }
        let readable = self.policy.allowlist_json.is_some() && !self.rows.allowlist_corrupt;
        if !row.checked {
            readable.then(|| (t(lang, "apps.dialer.not_allowed"), false))
        } else if self.off_home.contains(&row.package_name) {
            Some((t(lang, "apps.dialer.off_home"), true))
        } else {
            None
        }
    }
}

/// Device › Apps; `refused` = the row whose switch was refused and why, `from` = opened from the Apps tab (the tab,
/// the back link and every form's hidden `from`).
pub(crate) async fn render_apps(
    state: &AppState,
    lang: crate::i18n::Lang,
    id: i64,
    refused: Option<(&str, &'static str)>,
    from: Option<AppsFrom>,
) -> Response {
    use crate::app_display as display;
    let data = match AppsData::load(state, lang, id).await {
        Ok(data) => data,
        Err(response) => return *response,
    };
    // Design 127 §2.2: an update held for the parent, the list's one orange part.
    let held = match crate::handlers::tracked_apps::held_versions(state).await {
        Ok(held) => held,
        Err(err) => return read_failed(lang, id, "the held updates", err),
    };
    let (nav_tab, back_href, back_label, from) =
        match apps_nav(state, &data.device, from, lang).await {
            Ok(nav) => nav,
            Err(err) => return read_failed(lang, id, "the catalog app it came from", err),
        };
    let page_from = from;
    let from = from.map(AppsFrom::value);
    let zone = Zone::load(&state.db, lang).await;
    let now = Utc::now();
    let latest = data.latest.as_ref();
    let policy = &data.policy;

    let network = data
        .rows
        .downloads
        .as_ref()
        .and_then(|downloads| crate::app_downloads::network(lang, downloads))
        .map(|value| {
            let at = latest.and_then(|s| crate::display_time::parse_db_utc(&s.reported_at));
            Row::new(
                "network",
                t(lang, "device.about.network"),
                match at {
                    Some(at) => format!("{value} · {}", zone.when(at, now)),
                    None => value.to_string(),
                },
            )
            .title(at.map(|t| zone.exact(t)))
            .detail(Some(t(lang, "device.apps.network_sub")))
        });
    let installing = data.rows.apps.iter().any(|a| a.is_installing);
    let (mut allowed, mut not_allowed) = (Vec::new(), Vec::new());
    let mut dialer_view: Option<AppView> = None;
    for row in data.rows.apps.iter() {
        let package = row.package_name.clone();
        let anchor = crate::handlers::devices::app_anchor(&package, row.tracked_app_id);
        let refused = refused
            .filter(|(row, _)| *row == anchor)
            .map(|(_, text)| text);
        // Calls managed: the Phone app is a link to the Calls page, whatever the allowlist says (the launcher never
        // shows it then); no switch, no page.
        if data.managed_dialer(&package) {
            let app_name = row.own_label.clone().unwrap_or_else(|| row.label.clone());
            let label =
                crate::i18n::t_params(lang, "apps.dialer.managed_label", &[("app", &app_name)]);
            let off = policy.calls_mode == "off";
            dialer_view = Some(AppView {
                builtin: true,
                // Managed: the title says so - no value, no sub-line, only the chevron (design 113).
                link: Some((
                    format!("/devices/{id}/calls#settings"),
                    if off {
                        t(lang, "device.calls.mode.off")
                    } else {
                        ""
                    },
                )),
                logo: data.logos.get(&package).cloned(),
                page: None,
                letter: crate::handlers::devices::initial(&app_name),
                refused,
                dialer_note: None,
                waits: None,
                off_home: false,
                icon: None,
                anchor,
                row: crate::handlers::devices::UnifiedAppRow {
                    status_label: if off {
                        t(lang, "apps.dialer.off_sub").to_string()
                    } else {
                        String::new()
                    },
                    own_label: None,
                    label,
                    checked: true,
                    ..row.clone()
                },
            });
            continue;
        }
        let page = (row.checked && !row.is_launcher && display::valid_package_name(&package))
            .then(|| crate::handlers::app_page::page_url(id, &package, "", page_from, ""));
        let waits = row
            .tracked_app_id
            .filter(|_| row.checked && !row.is_launcher)
            .and_then(|tid| held.get(&tid))
            .map(|version| {
                crate::i18n::t_params(lang, "apps.update.held_title", &[("version", version)])
            });
        let logo = if row.is_launcher {
            Some(crate::app_logos::VIBB_MARK.to_string())
        } else {
            data.logos.get(&package).cloned()
        };
        let view = AppView {
            builtin: false,
            link: None,
            logo,
            page,
            letter: crate::handlers::devices::initial(&row.label),
            refused,
            dialer_note: data.dialer_note(lang, row),
            waits,
            off_home: row.checked && data.off_home.contains(&package),
            icon: display::icon_of(&data.own, &data.defaults, &package),
            anchor,
            row: row.clone(),
        };
        if view.row.checked {
            allowed.push(view);
        } else {
            not_allowed.push(view);
        }
    }
    // Design 82 D10: Meldinger's row, directly under Vibb One's - a link to its page.
    if data.messages_listed() {
        let key = crate::builtin::MESSAGES;
        let current = data.own.get(key);
        let (glyph, tile) = display::MESSAGES_LOOK.shown(current);
        let row = messages_row(lang, policy, current);
        let at = usize::from(allowed.first().is_some_and(|app| app.row.is_launcher));
        allowed.insert(
            at,
            AppView {
                builtin: true,
                link: None,
                logo: None,
                page: Some(crate::handlers::app_page::page_url(
                    id, key, "", page_from, "",
                )),
                letter: crate::handlers::devices::initial(&row.label),
                refused: None,
                dialer_note: None,
                waits: None,
                off_home: false,
                icon: Some((glyph.to_string(), tile)),
                anchor: format!("app-{key}"),
                row,
            },
        );
    }
    // 2026-11-12: Kalender (Dagen, design 69), under Meldinger - whether it is on Home, and a link to Device › Calendar
    // where that and the rest are set. It has no page of its own (the phone reads no Name and icon for it).
    {
        let on_home = match crate::calendar::switches(&state.db, id).await {
            Ok(switches) => switches.map_or(crate::calendar::default_switches().0, |s| s.0),
            Err(err) => return read_failed(lang, id, "the phone's calendar switches", err),
        };
        let name = t(lang, "calendar.summary.label");
        let at = allowed
            .iter()
            .take_while(|app| app.row.is_launcher || app.builtin)
            .count();
        allowed.insert(
            at,
            AppView {
                builtin: true,
                link: Some((
                    format!("/devices/{id}/calendar#dagen"),
                    t(
                        lang,
                        if on_home {
                            "apps.on_home"
                        } else {
                            "apps.builtin.off_home"
                        },
                    ),
                )),
                logo: None,
                page: None,
                letter: crate::handlers::devices::initial(name),
                refused: None,
                dialer_note: None,
                waits: None,
                off_home: false,
                icon: Some((display::DAY_LOOK.glyph.to_string(), display::DAY_LOOK.tile)),
                anchor: "app-calendar".to_string(),
                row: crate::handlers::devices::UnifiedAppRow {
                    status_label: String::new(),
                    own_label: None,
                    label: name.to_string(),
                    package_name: String::new(),
                    tracked_app_id: None,
                    is_launcher: false,
                    checked: true,
                    is_installing: false,
                    install_failed: false,
                    show_no_release_hint: false,
                    access: None,
                    third_party: false,
                },
            },
        );
    }
    // Design 109: the managed Phone app's link row, directly under Vibb One (Home's order: the phone book first).
    if let Some(view) = dialer_view {
        let at = usize::from(allowed.first().is_some_and(|app| app.row.is_launcher));
        allowed.insert(at, view);
    }
    // Design 108 §3.10: the "App push" group, named from the rows above.
    let names: std::collections::HashMap<String, crate::handlers::app_push::AppName> = allowed
        .iter()
        .map(|app| (app, true))
        .chain(not_allowed.iter().map(|app| (app, false)))
        .filter(|(app, _)| !app.builtin && !app.row.package_name.is_empty())
        .map(|(app, allowed)| {
            (
                app.row.package_name.clone(),
                crate::handlers::app_push::AppName {
                    label: app.row.label.clone(),
                    letter: app.letter.clone(),
                    logo: app.logo.clone(),
                    allowed,
                },
            )
        })
        .collect();
    let app_push = match crate::handlers::app_push::group(
        state,
        lang,
        id,
        &names,
        &zone,
        latest.map(|st| st.reported_at.as_str()),
    )
    .await
    {
        Ok(group) => group,
        Err(err) => return read_failed(lang, id, "the phone's app push", err),
    };
    // Design 114 §5.4: the page's warning lines, the map links' group's (`#map-links`) among them.
    let setup_warnings = crate::setup_status::page_warnings_app_push(
        lang,
        policy,
        latest,
        &state.integrations.setup(state, id).await,
        "apps",
        app_push.on,
    );
    let integration_groups = match state
        .integrations
        .apps_groups(state, lang, id, from.as_deref(), &setup_warnings)
        .await
    {
        Ok(groups) => groups,
        Err(err) => return read_failed(lang, id, "the phone's integrations", err),
    };
    let AppsData {
        device,
        policy,
        latest,
        rows,
        ..
    } = data;
    Html(
        AppsTemplate {
            lang,
            setup_warnings,
            app_push,
            integration_groups,
            who: crate::handlers::devices::BandAvatar::of(&device),
            title: format!("{} · {}", t(lang, "nav.apps"), device.name),
            nav_tab,
            back_href,
            back_label,
            from,
            corrupt: rows.allowlist_corrupt.then(|| Note {
                warning: true,
                title: t(lang, "device.apps.corrupt").to_string(),
                value: None,
                sub: None,
                detail: Some(t(lang, "device.apps.corrupt_sub").to_string()),
                fix: Some(t(lang, "device.apps.corrupt_fix").to_string()),
            }),
            allowed,
            not_allowed,
            reported: latest.is_some(),
            login_role: policy.login_browser_enabled && !crate::logins::chrome_ticked(&policy),
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

/// Meldinger's row (design 82 D10): its name - the phone's own, else "Meldinger" - and, while SMS isn't managed, the
/// line that says so (design 113).
pub(crate) fn messages_row(
    lang: Lang,
    policy: &DevicePolicy,
    current: Option<&crate::app_display::DisplayValues>,
) -> crate::handlers::devices::UnifiedAppRow {
    let name = t(lang, "device.sms.messages");
    let label = current.and_then(|values| values.label.clone());
    crate::handlers::devices::UnifiedAppRow {
        status_label: if policy.sms_mode == "managed" {
            String::new()
        } else {
            t(lang, "apps.builtin.sms_unmanaged").to_string()
        },
        own_label: label.is_some().then(|| name.to_string()),
        label: label.unwrap_or_else(|| name.to_string()),
        package_name: crate::builtin::MESSAGES.to_string(),
        tracked_app_id: None,
        is_launcher: false,
        checked: true,
        is_installing: false,
        install_failed: false,
        show_no_release_hint: false,
        access: None,
        third_party: false,
    }
}

#[derive(Template)]
#[template(path = "device_music.html")]
struct MusicTemplate {
    lang: Lang,
    /// The phone's small picture on the band (design 84, `band::phone`).
    who: crate::handlers::devices::BandAvatar,
    title: String,
    device: Device,
    music: crate::handlers::music::MusicCard,
    /// When the phone last reported (`On the phone`'s footer): its age, and the exact time for
    /// `title=`.
    reported: Option<(String, String)>,
    /// Design 114 §5.4: notification access off, at what the phone reports (`#on-phone`: the music card).
    setup_warnings: crate::setup_status::Warnings,
}
crate::i18n::words!(MusicTemplate);

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
    lang: Lang,
    Path(id): Path<i64>,
    Query(query): Query<std::collections::HashMap<String, String>>,
) -> Response {
    let device = match load_device(&state, id).await {
        Ok(device) => device,
        Err((status, key)) => return crate::i18n::error(lang, status, key),
    };
    let policy = match load_policy(&state, id).await {
        Ok(policy) => policy,
        Err((status, key)) => return crate::i18n::error(lang, status, key),
    };
    let latest = match load_latest(&state, id).await {
        Ok(latest) => latest,
        Err(err) => return read_failed(lang, id, "the last report", err),
    };
    let music = match crate::handlers::music::device_card(
        &state,
        &policy,
        latest.as_ref(),
        query.get("music_notice").map(String::as_str),
        query.get("music_entry").and_then(|e| e.parse().ok()),
        lang,
    )
    .await
    {
        Ok(music) => music,
        Err(err) => return read_failed(lang, id, "the phone's music", err),
    };
    let zone = Zone::load(&state.db, lang).await;
    let reported = latest
        .as_ref()
        .and_then(|s| crate::display_time::parse_db_utc(&s.reported_at))
        .map(|at| (zone.relative(at, Utc::now()), zone.exact(at)));
    Html(
        MusicTemplate {
            lang,
            who: crate::handlers::devices::BandAvatar::of(&device),
            title: t_params(lang, "device.music.title", &[("name", &device.name)]),
            music,
            reported,
            setup_warnings: crate::setup_status::page_warnings(
                lang,
                &policy,
                latest.as_ref(),
                &state.integrations.setup(&state, id).await,
                "music",
            ),
            device,
        }
        .render()
        .unwrap(),
    )
    .into_response()
}

/// The escapes card from the policy and the last report (the fence, the filter, the listener,
/// the boot cover).
pub(crate) fn escapes_card(
    policy: &DevicePolicy,
    latest: Option<&DeviceStatus>,
    now_ms: i64,
    last_phone_on_ms: Option<i64>,
) -> crate::kiosk_escapes::EscapesCard {
    use crate::kiosk_escapes as ke;
    let mut card = ke::escapes_card(
        policy.update_fence,
        policy.notification_auto_cancel,
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
        ke::parse_boot_cover(latest.and_then(|s| s.boot_cover_json.as_deref())).as_ref(),
        last_phone_on_ms,
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
        // scripts/release-version.sh's rule.
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
            serde_json::from_str(crate::contracts::contract!("core/launcher_tag_codes.json"))
                .unwrap();
        let rows = contract["music_codes"].as_array().unwrap();
        assert!(rows.len() >= 3);
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
            for lang in crate::i18n::Lang::ALL {
                assert!(parsed.title(lang).split_whitespace().count() <= 4);
                assert!(!parsed.detail(lang).is_empty());
            }
        }
        let other = PolicyProblem::parse("weird").unwrap();
        assert_eq!(other.title(crate::i18n::Lang::En), "Problem reported");
        assert!(other.detail(crate::i18n::Lang::Nb).contains("weird"));
        assert!(
            PolicyProblem::ServerError
                .detail(crate::i18n::Lang::En)
                .contains("failed to build device policy")
        );
    }

    #[test]
    fn play_notes_follow_the_report() {
        assert!(play_notes(None, 0).is_empty());
        let status = DeviceStatus {
            install_mode_until_ms: Some(10_000),
            admin_policies_pending_json: Some(r#"["disable-camera","wipe-data"]"#.to_string()),
            play_store_suspendable: Some(false),
            ..Default::default()
        };
        let notes = play_notes(Some(&status), 5_000);
        assert_eq!(
            notes,
            vec![
                PlayNote::InstallMode { until_ms: 10_000 },
                PlayNote::RestartOnce(vec!["device.advanced.play.effect.camera"]),
            ]
        );
        // Install mode that has ended: no note.
        let later = play_notes(Some(&status), 10_000);
        assert!(
            !later
                .iter()
                .any(|n| matches!(n, PlayNote::InstallMode { .. }))
        );
        let zone = Zone::from_setting("Europe/Oslo");
        let now = DateTime::from_timestamp_millis(5_000).unwrap();
        let words: Vec<Note> = notes.iter().map(|n| n.note(&zone, now)).collect();
        assert_eq!(words[0].title, "Play Store open until 01:00");
        let nb: Vec<Note> = notes
            .iter()
            .map(|n| n.note(&zone.in_lang(crate::i18n::Lang::Nb), now))
            .collect();
        assert_eq!(nb[0].title, "Play Butikk er åpen til 01:00");
        // Install mode is the parent's choice: a quiet line; the restart is a warning.
        assert!(!words[0].warning);
        assert_eq!(words[1].title, "Restart the phone once");
        assert!(words[1].warning);
    }
}
