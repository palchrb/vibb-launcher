use askama::Template;
use axum::extract::{Path, Query, State};
use axum::http::StatusCode;
use axum::response::{Html, IntoResponse, Redirect, Response};
use axum::{Extension, Form};
use serde::Deserialize;

use crate::AppState;
use crate::models::{Device, DevicePolicy, DeviceStatus, Hardening, InstalledApp, TrackedApp};
use crate::security::{self, CurrentAdmin};

/// One phone on the Devices list (design 31 §3.2): the avatar with the connection dot, the name,
/// the status line, today's screen time and the Attention count.
struct DeviceListRow {
    id: i64,
    name: String,
    /// The avatar's letter and colour class.
    initial: String,
    avatar: &'static str,
    connection: crate::streams::ConnectionState,
    /// After the state word: "seen 3 min ago", "access removed".
    detail: Option<String>,
    /// The exact last contact, for the detail's `title`.
    detail_title: Option<String>,
    /// While a rule locks the phone: "Skole until 14:30".
    lock: Option<String>,
    screen_time: Option<crate::time_state::ScreenTime>,
    /// Attention rows on the phone's page (the red badge).
    attention: usize,
}

/// The avatars' colours (white text 4.5:1 or more on each); a phone not set up is grey.
pub(crate) const AVATAR_COLOURS: [&str; 6] = [
    "avatar-indigo",
    "avatar-orange",
    "avatar-teal",
    "avatar-green",
    "avatar-pink",
    "avatar-purple",
];

#[derive(Template)]
#[template(path = "devices_list.html")]
struct DevicesListTemplate {
    title: String,
    devices: Vec<DeviceListRow>,
    /// Failed 2FA codes before this sign-in (design 22 §3.2.1), until dismissed; the full text
    /// is on Account.
    login_warning: Option<crate::handlers::auth::WarningRow>,
    /// Failed 2FA codes since this sign-in, and the lock (QA 22 Low 9).
    live_warning: Option<crate::handlers::auth::WarningRow>,
    warning_back: &'static str,
}

pub async fn list_devices(
    State(state): State<AppState>,
    session: tower_sessions::Session,
    Extension(CurrentAdmin(admin)): Extension<CurrentAdmin>,
) -> impl IntoResponse {
    let devices = sqlx::query_as::<_, Device>("SELECT * FROM devices ORDER BY name")
        .fetch_all(&state.db)
        .await
        .unwrap_or_default();
    let zone = crate::display_time::Zone::load(&state.db).await;
    let now = chrono::Utc::now();

    let mut rows = Vec::with_capacity(devices.len());
    for device in devices {
        rows.push(list_row(&state, device, &zone, now).await);
    }

    Html(
        DevicesListTemplate {
            title: "Devices".to_string(),
            devices: rows,
            login_warning: crate::handlers::auth::login_warning_row(
                crate::handlers::auth::login_warning_of(&session)
                    .await
                    .as_deref(),
            ),
            live_warning: crate::handlers::auth::live_warning_row(&admin, &zone, now),
            warning_back: "/devices",
        }
        .render()
        .unwrap(),
    )
}

/// A phone's row: one in-memory lookup for the connection, its last report, its rules when one
/// is locking, and the Attention count (about 10 small reads, §3.8).
async fn list_row(
    state: &AppState,
    device: Device,
    zone: &crate::display_time::Zone,
    now: chrono::DateTime<chrono::Utc>,
) -> DeviceListRow {
    use crate::streams::ConnectionState;
    let connection = connection_of(state, &device, now);
    let (detail, detail_title) = match connection {
        ConnectionState::Disconnected => (Some("access removed".to_string()), None),
        state => match state.last_contact() {
            Some(at) => (
                Some(format!("seen {}", zone.relative(at, now))),
                Some(zone.exact(at)),
            ),
            None => (None, None),
        },
    };
    let facts = crate::attention::facts(state, &device, connection).await;
    let attention = crate::attention::items(device.id, &facts).len();
    let (lock, screen_time) = if device.enrolled_at.is_some() {
        time_glance(
            state,
            device.id,
            &connection,
            facts.calls_enforced,
            zone,
            now,
        )
        .await
    } else {
        (None, None)
    };
    let lock = lock.map(|badge| match &detail {
        Some(seen) => badge.text_beside(seen.trim_start_matches("seen ")),
        None => badge.text(),
    });
    // Online with a lock: the lock says more than "seen just now" (the mockup's "Online · Skole
    // until 14:30").
    let (detail, detail_title) = match (&connection, &lock) {
        (ConnectionState::Online { .. }, Some(_)) => (None, None),
        _ => (detail, detail_title),
    };
    DeviceListRow {
        initial: initial(&device.name),
        avatar: avatar_class(&device),
        id: device.id,
        name: device.name,
        connection,
        detail,
        detail_title,
        lock,
        screen_time,
        attention,
    }
}

/// A phone's avatar colour (also on the Apps tab): grey until it is set up, else by its id.
pub(crate) fn avatar_class(device: &Device) -> &'static str {
    if device.enrolled_at.is_none() {
        "avatar-grey"
    } else {
        AVATAR_COLOURS[device.id.rem_euclid(AVATAR_COLOURS.len() as i64) as usize]
    }
}

/// The avatar's letter: the name's first letter or digit, upper-cased when that gives one
/// letter ("ß" stays "ß"); an emoji or a flag is skipped rather than cut in half; "?" for none.
pub(crate) fn initial(name: &str) -> String {
    name.chars()
        .find(|c| c.is_alphanumeric())
        .map(|c| {
            let mut upper = c.to_uppercase();
            match (upper.next(), upper.next()) {
                (Some(u), None) => u.to_string(),
                _ => c.to_string(),
            }
        })
        .unwrap_or_else(|| "?".to_string())
}

/// A phone's connection (design 31 §3.10) from the in-memory stream and last access, and the
/// database's last report (which survives a restart).
pub(crate) fn connection_of(
    state: &AppState,
    device: &Device,
    now: chrono::DateTime<chrono::Utc>,
) -> crate::streams::ConnectionState {
    crate::streams::connection_state(&connection_inputs(state, device), now)
}

/// The signals [connection_of] combines (About This Phone also reads the last contact from them).
pub(crate) fn connection_inputs(
    state: &AppState,
    device: &Device,
) -> crate::streams::ConnectionInputs {
    crate::streams::ConnectionInputs {
        enrolled: device.enrolled_at.is_some(),
        has_token: device.token_hash.is_some(),
        stream: state.command_streams.state(device.id),
        last_access: state.limits.last_access(device.id).map(|a| a.at),
        last_seen: device
            .last_seen_at
            .as_deref()
            .and_then(crate::display_time::parse_db_utc),
    }
}

/// The lock badge and today's screen time from the phone's last report (design 31 §3.10, M4).
pub(crate) async fn time_glance(
    state: &AppState,
    device_id: i64,
    connection: &crate::streams::ConnectionState,
    calls_enforced: bool,
    zone: &crate::display_time::Zone,
    now: chrono::DateTime<chrono::Utc>,
) -> (
    Option<crate::time_state::LockBadge>,
    Option<crate::time_state::ScreenTime>,
) {
    use crate::time_state::{BadgeContext, TimeReport, lock_badge, screen_time};
    let latest: Option<(String, String, Option<String>)> = sqlx::query_as(
        "SELECT reported_at, lock_reason, time_state_json FROM device_status \
         WHERE device_id = ? ORDER BY reported_at DESC, id DESC LIMIT 1",
    )
    .bind(device_id)
    .fetch_optional(&state.db)
    .await
    .unwrap_or_else(|err| {
        tracing::error!(device_id, %err, "can't read the last report for the time badge");
        None
    });
    let Some((reported_at, lock_reason, time_state_json)) = latest else {
        return (None, None);
    };
    let Some(reported_at) = crate::display_time::parse_db_utc(&reported_at) else {
        return (None, None);
    };
    let report = time_state_json
        .as_deref()
        .and_then(TimeReport::parse)
        .unwrap_or_else(|| TimeReport::from_lock_reason(&lock_reason));
    // Every effective rule's days (an end is shown only if none locks at it), only while a rule
    // locks the phone. A rule whose days don't read is kept as "always on" (no end).
    let mut rules = Vec::new();
    if report.active_rule_id.is_some() && report.locking() {
        let custom: bool = sqlx::query_scalar(
            "SELECT custom_schedule_enabled FROM device_policy WHERE device_id = ?",
        )
        .bind(device_id)
        .fetch_optional(&state.db)
        .await
        .ok()
        .flatten()
        .unwrap_or(false);
        rules = crate::time_rules::effective_rule_rows(&state.db, device_id, custom)
            .await
            .unwrap_or_default()
            .into_iter()
            .map(|rule| {
                let days = crate::time_rules::parse_days(&rule.days_json).unwrap_or_default();
                (rule.id, days)
            })
            .collect();
    }
    (
        lock_badge(
            &report,
            &BadgeContext {
                rules: &rules,
                reported_at,
                connection,
                calls_enforced,
            },
            zone,
            now,
        ),
        screen_time(&report, zone, now),
    )
}

/// A managed phone (an app list or managed calls) without a Parent PIN: its Settings can't be
/// opened on the phone (the device page's red card, an Attention alarm).
pub(crate) fn managed_without_pin(policy: &DevicePolicy) -> bool {
    (policy.allowlist_json.is_some() || policy.calls_managed) && policy.override_pin_hash.is_none()
}

/// The phone reported, without `time_rules_v1`: it still runs the old schedule.
pub(crate) fn launcher_without_rules(latest: Option<&DeviceStatus>) -> bool {
    latest.is_some_and(|s| {
        !s.capabilities_json
            .as_deref()
            .and_then(|j| serde_json::from_str::<Vec<String>>(j).ok())
            .unwrap_or_default()
            .iter()
            .any(|c| c == "time_rules_v1")
    })
}

#[derive(Template)]
#[template(path = "device_add.html")]
struct DeviceAddTemplate {
    title: String,
}

pub async fn new_device_form() -> impl IntoResponse {
    Html(
        DeviceAddTemplate {
            title: "Add a device".to_string(),
        }
        .render()
        .unwrap(),
    )
}

#[derive(Deserialize)]
pub struct CreateDeviceForm {
    pub(crate) name: String,
}

pub async fn create_device(
    State(state): State<AppState>,
    Form(form): Form<CreateDeviceForm>,
) -> axum::response::Response {
    match insert_device_with_policy(&state, &form.name).await {
        Ok(id) => Redirect::to(&format!("/devices/{id}")).into_response(),
        Err(err) => {
            tracing::error!(?err, "failed to create device");
            (
                axum::http::StatusCode::INTERNAL_SERVER_ERROR,
                "Couldn't create the device - nothing was saved. Check the server log.",
            )
                .into_response()
        }
    }
}

/// The device row and its `device_policy` row go in one transaction: a device without a policy
/// row can't exist, since `device_api::build_policy` refuses to serve one (500) rather than
/// invent a default.
///
/// No enrollment code yet: the device page makes one ("New code", shown once) or the setup QR
/// does (design 22 §3.1) - a code made here could never be shown again.
async fn insert_device_with_policy(state: &AppState, name: &str) -> Result<i64, sqlx::Error> {
    let mut tx = state.db.begin().await?;
    let id: i64 = sqlx::query_scalar("INSERT INTO devices (name) VALUES (?) RETURNING id")
        .bind(name)
        .fetch_one(&mut *tx)
        .await?;

    // Kiosk mode on, with the full always-on feature set, for every device - see
    // `update_policy` and this repo's CLAUDE.md (`kiosk_desired` is no longer admin-configurable).
    // Kid Settings (the Quick Controls screen) starts with Wi-Fi, Bluetooth and brightness on;
    // devices created before 2026-10-06 keep the column default (none).
    sqlx::query(
        "INSERT INTO device_policy (device_id, kiosk_desired, lock_task_features, \
         quick_controls_mask) VALUES (?, 1, ?, ?)",
    )
    .bind(id)
    .bind(DEFAULT_LOCK_TASK_FEATURES)
    .bind(DEFAULT_QUICK_CONTROLS)
    .execute(&mut *tx)
    .await?;

    tx.commit().await?;
    Ok(id)
}

#[derive(Template)]
#[template(path = "enroll_code.html")]
struct EnrollCodeTemplate {
    title: String,
    device: Device,
    code: String,
    expires_at: String,
    minutes: i64,
    /// The phone is enrolled: using the code cuts the enrolled phone off (recovery).
    enrolled: bool,
    /// Typed codes are paused right now (too many wrong ones this hour).
    paused: Option<String>,
}

/// "New code" on the device page (design 22 §3.1): a typed code (8 characters, 15 minutes),
/// shown on this response only - only its hash is stored. Replaces any live code. Also the
/// recovery path for an enrolled phone: using the code gives it a new token and retires the old
/// one. 409 while typed codes are paused (the page offers the QR).
pub async fn regenerate_code(
    State(state): State<AppState>,
    Path(id): Path<i64>,
    Extension(CurrentAdmin(admin)): Extension<CurrentAdmin>,
) -> Response {
    let Some(device) = sqlx::query_as::<_, Device>("SELECT * FROM devices WHERE id = ?")
        .bind(id)
        .fetch_optional(&state.db)
        .await
        .ok()
        .flatten()
    else {
        return StatusCode::NOT_FOUND.into_response();
    };
    let kind = crate::enrollment::Kind::Typed;
    let (code, expires_at) = match crate::enrollment::new_code(&state, id, kind).await {
        Ok(made) => made,
        Err(err) => {
            tracing::error!(device_id = id, %err, "couldn't make an enrollment code");
            return (
                StatusCode::INTERNAL_SERVER_ERROR,
                "Couldn't make a code - nothing was changed. Check the server log.",
            )
                .into_response();
        }
    };
    security::record_security_event(
        &state.db,
        "enroll_code_created",
        Some(&admin.username),
        None,
        Some(&format!("{} (device {id}), typed code", device.name)),
    )
    .await;
    let html = EnrollCodeTemplate {
        title: format!("Code for {}", device.name),
        code: crate::enrollment::display(&code),
        expires_at,
        minutes: kind.minutes(),
        enrolled: device.enrolled_at.is_some(),
        paused: state
            .enrollment
            .typed_paused_until(chrono::Utc::now())
            .map(|until| {
                format!(
                    "Typed codes are paused until {} UTC (too many wrong ones this hour): this code \
                     works only after that. A setup QR code (Provision) works now.",
                    until.format("%H:%M")
                )
            }),
        device,
    }
    .render()
    .unwrap();
    (
        [(axum::http::header::CACHE_CONTROL, "no-store")],
        Html(html),
    )
        .into_response()
}

#[derive(Deserialize)]
pub struct RevokeForm {
    #[serde(default)]
    confirm: String,
}

/// "Disconnect Phone" on Advanced › Connection ("Revoke access", design 22 §3.1): the phone's
/// token stops working at once and its open streams end. The phone keeps enforcing what it has and
/// says "access revoked - enroll again"; a new code (above) connects it again. Needs the confirm
/// box (else nothing happens). Back to the Connection group, with the flash in it.
pub async fn revoke_access(
    State(state): State<AppState>,
    Path(id): Path<i64>,
    Extension(CurrentAdmin(admin)): Extension<CurrentAdmin>,
    Form(form): Form<RevokeForm>,
) -> Response {
    if form.confirm != "yes" {
        return Redirect::to(&format!(
            "/devices/{id}/advanced?notice=revoke_unconfirmed#connection"
        ))
        .into_response();
    }
    let revoked = sqlx::query_scalar::<_, String>(
        "UPDATE devices SET token_hash = NULL WHERE id = ? RETURNING name",
    )
    .bind(id)
    .fetch_optional(&state.db)
    .await;
    let name = match revoked {
        Ok(Some(name)) => name,
        Ok(None) => return StatusCode::NOT_FOUND.into_response(),
        Err(err) => {
            tracing::error!(device_id = id, %err, "couldn't revoke the device's access");
            return StatusCode::INTERNAL_SERVER_ERROR.into_response();
        }
    };
    state.tokens.refresh(&state.db, id, None).await;
    state.command_streams.close_device(id);
    security::record_security_event(
        &state.db,
        "access_revoked",
        Some(&admin.username),
        None,
        Some(&format!("{name} (device {id})")),
    )
    .await;
    Redirect::to(&format!(
        "/devices/{id}/advanced?notice=access_revoked#connection"
    ))
    .into_response()
}

/// A phone's connection to this server, for Advanced › Connection and the not-enrolled card
/// (design 22 §3.1, design 31 §3.2): times in the Phone Setup zone.
pub struct AccessCard {
    /// The phone has a working token.
    pub has_token: bool,
    /// While a code is live: "Setup code" or "Setup QR code", and until when ("14:05").
    pub live_code: Option<(&'static str, String)>,
    /// Until when typed codes are paused after too many wrong ones this hour ("14:05").
    pub typed_paused_until: Option<String>,
    /// The flash after Disconnect Phone: the text, and whether it is a refusal.
    pub notice: Option<(&'static str, bool)>,
}

impl AccessCard {
    /// How many wrong typed codes in an hour pause them (for the warning's sentence).
    pub fn pause_failures(&self) -> u32 {
        crate::enrollment::TYPED_PAUSE_FAILURES
    }
}

pub(crate) fn access_card(
    state: &AppState,
    device: &Device,
    notice: Option<&str>,
    zone: &crate::display_time::Zone,
    now: chrono::DateTime<chrono::Utc>,
) -> AccessCard {
    let live_code = match (
        &device.enrollment_code_kind,
        &device.enrollment_code_expires_at,
    ) {
        (Some(kind), Some(expires)) => {
            chrono::NaiveDateTime::parse_from_str(expires, "%Y-%m-%d %H:%M:%S")
                .ok()
                .map(|t| t.and_utc())
                .filter(|t| *t > now)
                .map(|t| {
                    (
                        if kind == "qr" {
                            "Setup QR code"
                        } else {
                            "Setup code"
                        },
                        zone.until(t, now),
                    )
                })
        }
        _ => None,
    };
    AccessCard {
        has_token: device.token_hash.is_some(),
        live_code,
        typed_paused_until: state
            .enrollment
            .typed_paused_until(now)
            .map(|until| zone.until(until, now)),
        notice: notice.and_then(access_notice),
    }
}

fn access_notice(code: &str) -> Option<(&'static str, bool)> {
    Some(match code {
        "access_revoked" => ("Phone disconnected.", false),
        "revoke_unconfirmed" => ("Nothing changed: tick the box first.", true),
        _ => return None,
    })
}

/// One row in the unified Apps list - either an app the device has actually reported installed
/// (`status` is `Preinstalled` or `Installed`), or a catalog (`tracked_apps`) app it doesn't have
/// yet (`status` is `NotInstalled`). Replaces what used to be two separate lists/cards ("Allowed
/// apps" from `device_status.installed_apps_json`, "Apps to install" from the full `tracked_apps`
/// catalog) - an app that's both installed *and* in the catalog now gets exactly one row, not two
/// independently-checkable ones telling two different, sometimes-contradictory stories.
///
/// `package_name` can be empty only for a manual-upload catalog app nobody's typed a package name
/// for yet - it can never be matched against a real installed app by name, so it always shows as
/// `NotInstalled` until an admin adds one (see `tracked_apps.rs`).
pub(crate) struct UnifiedAppRow {
    pub(crate) package_name: String,
    pub(crate) label: String,
    pub(crate) tracked_app_id: Option<i64>,
    pub(crate) is_launcher: bool,
    pub(crate) checked: bool,
    /// Precomputed display text rather than a template-side call to `status.label()` - lets a
    /// `NotInstalled` row show live download progress ("Installing 42%") instead of the plain
    /// static label when a fresh `device_install_progress` row exists for it - see `app_rows`.
    pub(crate) status_label: String,
    /// True exactly when `status_label` carries a live percentage - drives Device › Apps'
    /// self-polling reload (there's no push mechanism to this page, so it has to ask again) rather
    /// than the template trying to parse `status_label`'s text back apart.
    pub(crate) is_installing: bool,
    /// True when the device reported a failed install attempt for this app that hasn't cleared yet
    /// (see `device_install_progress.failed`) - a separate flag from `is_installing` so
    /// Device › Apps can style it distinctly rather than parsing `status_label`'s text.
    pub(crate) install_failed: bool,
    /// Precomputed rather than compared in the template (`status == AppRowStatus::NotInstalled`) -
    /// flags a catalog app that's checked but has nothing to actually push yet (no GitHub release
    /// synced, or a manual-upload app nobody's uploaded a build to yet). Selecting it used to
    /// silently do nothing until a release showed up, with no indication why.
    pub(crate) show_no_release_hint: bool,
}

/// The six parent-facing LockTask features, decoded from/encoded into the
/// raw `lock_task_features` bitmask Android's `setLockTaskFeatures` expects.
/// Not exposing `LOCK_TASK_FEATURE_BLOCK_ACTIVITY_START_IN_TASK` (64) - no
/// clear parent-facing meaning.
const LOCK_FEATURE_SYSTEM_INFO: i64 = 1;
const LOCK_FEATURE_NOTIFICATIONS: i64 = 2;
const LOCK_FEATURE_HOME: i64 = 4;
const LOCK_FEATURE_OVERVIEW: i64 = 8;
const LOCK_FEATURE_GLOBAL_ACTIONS: i64 = 16;
const LOCK_FEATURE_KEYGUARD: i64 = 32;

/// None of the six are admin-configurable anymore - see `update_policy`'s doc comment on why each
/// one is always on whenever kiosk mode is - so this is just the one value `lock_task_features`
/// ever takes for a kiosk-mode device. Used both there and in `create_device`, so a newly enrolled
/// device starts with the exact same features a save from the device detail page would produce.
const DEFAULT_LOCK_TASK_FEATURES: i64 = LOCK_FEATURE_SYSTEM_INFO
    | LOCK_FEATURE_HOME
    | LOCK_FEATURE_OVERVIEW
    | LOCK_FEATURE_KEYGUARD
    | LOCK_FEATURE_NOTIFICATIONS
    | LOCK_FEATURE_GLOBAL_ACTIONS;

/// Bits for `quick_controls_mask` - which switches show up on the launcher's kid Settings page
/// (swipe left from Home; the launcher's `ui/kidsettings/KidSettingsActivity`, its
/// `QuickControlFeature`), the kid-facing replacement for Android's native Quick Settings shade.
pub(crate) const QUICK_CONTROL_WIFI: i64 = 1;
pub(crate) const QUICK_CONTROL_BLUETOOTH: i64 = 2;
pub(crate) const QUICK_CONTROL_BRIGHTNESS: i64 = 4;
/// The sound row (design 18): Sound / Silent (vibrate). It only shows the row - the volume keys
/// still change the sound. A launcher before design 18 ignores the bit.
pub(crate) const QUICK_CONTROL_SOUND: i64 = 8;
/// A new device's `quick_controls_mask`: every switch on (`insert_device_with_policy`).
pub(crate) const DEFAULT_QUICK_CONTROLS: i64 =
    QUICK_CONTROL_WIFI | QUICK_CONTROL_BLUETOOTH | QUICK_CONTROL_BRIGHTNESS | QUICK_CONTROL_SOUND;

#[derive(Template)]
#[template(path = "device_detail.html")]
struct DeviceDetailTemplate {
    title: String,
    device: Device,
    /// The Attention rows (design 31 §3.2), the same list the Devices list counts - at the top
    /// until step 10 builds the summary around them.
    attention: Vec<crate::attention::AttentionItem>,
    restrictions_paused: bool,
    latest_status: Option<DeviceStatus>,
    /// The About This Phone row's value: "Vibb 0.23.6" (design 31 step 3).
    about_value: Option<String>,
    /// One line for the "Calls & SMS" card, e.g. "Managed - 4 contacts".
    calls_summary: String,
    /// `calls::call_report` - emergency calls, a launcher that can't enforce calls, roles.
    call_warnings: Vec<String>,
    /// `calls::call_report`'s hints: good to know, nothing wrong (plain lines).
    call_hints: Vec<String>,
    /// The Apps row's value (design 31 step 6): "14 allowed", or "List unreadable".
    apps_value: String,
    /// The Restrictions switches (migrations/0023_hardening.sql), for the no-PIN warning.
    hardening: Hardening,
    /// Managed (an allowlist, or calls managed) but no override PIN: the launcher's Settings
    /// can't be opened on the phone, there is no offline override, and with USB debugging
    /// blocked a lost server means a reset (QA step 4 #4).
    managed_without_pin: bool,
    /// "Time rules" card (handy step 6).
    time: TimeCard,
    /// The Lock Screen & PINs row's value (design 31 step 4): "PIN on" with a Child PIN set.
    lock_value: &'static str,
    /// The Home Screen row's value (design 31 step 5): "3 per row".
    home_value: String,
    /// The Web Filter row's value: "On" / "Off".
    filter_value: &'static str,
    /// The Status card's Parent PIN notices and easy-PIN line (design 24); the PINs themselves
    /// are on Lock Screen & PINs.
    parent_pin: crate::handlers::parent_pin::ParentPinCard,
    /// Status card (design 24b): "Lock the phone again now", the last relock's findings.
    relock: crate::handlers::window_relock::RelockCard,
    /// "Music" card (design 21, `#music`); `None` when it couldn't be read (the card says so).
    music: Option<crate::handlers::music::MusicCard>,
    /// The not-enrolled card's codes (design 22 §3.1); an enrolled phone's are on Advanced.
    access: AccessCard,
}

/// The kiosk app block switch, "Block other apps' screens" on Advanced › App screens (handy step
/// 9; the "Play and kiosk" card until design 31 step 3): with it on, kiosk mode
/// stops every screen of an app that isn't allowed - also ones other apps open (Play, Google
/// sign-in sheets). The off switch exists for a phone where it breaks something it shouldn't.
pub async fn update_kiosk_block(
    State(state): State<AppState>,
    Path(id): Path<i64>,
    Form(form): Form<std::collections::HashMap<String, String>>,
) -> Response {
    let result = sqlx::query(
        "UPDATE device_policy SET block_activity_start = ?, updated_at = datetime('now') \
         WHERE device_id = ?",
    )
    .bind(form.contains_key("block_activity_start"))
    .bind(id)
    .execute(&state.db)
    .await;
    match result {
        Ok(r) if r.rows_affected() == 0 => {
            (StatusCode::NOT_FOUND, "Device not found").into_response()
        }
        Ok(_) => {
            let _ = state.command_notify.send(id);
            // Its own page, no fragment: scroll-restore.js keeps the exact position, so a save
            // never jumps (design 31 §5.1.4, qa-31-s3-code M3).
            Redirect::to(&format!("/devices/{id}/advanced")).into_response()
        }
        Err(err) => {
            tracing::error!(device_id = id, %err, "couldn't save the kiosk app block");
            (
                StatusCode::INTERNAL_SERVER_ERROR,
                "Couldn't save - nothing was changed.",
            )
                .into_response()
        }
    }
}

/// Lock Screen & PINs' Notifications group (design 25 §3.3; the Apps card's "On the lock screen"
/// before design 31 step 4): with an allowlist the allowed apps (the "Names and icons" set),
/// without one every app the phone reports (QA Low 7: a calls-only phone with a kid PIN still has
/// the lock), and always every ticked package outside that list, so any tick can be removed. Never
/// Play core or the launcher itself.
pub(crate) async fn lock_screen_card(
    state: &AppState,
    policy: &DevicePolicy,
    latest_status: Option<&DeviceStatus>,
    rows: &AppRows,
) -> crate::lock_glance::LockScreenCard {
    use crate::lock_glance::{self, LockScreenCard};
    let (apps, installed, allowed) = (&rows.apps, &rows.installed, &rows.allowed);
    // The parsed allowlist, when the phone has one: a ticked package outside the list is "not
    // allowed" only when it isn't in it (code QA Low 6).
    let allowlist: Option<std::collections::BTreeSet<String>> = policy
        .allowlist_json
        .is_some()
        .then(|| allowed.iter().cloned().collect());
    let empty_text = lock_glance::empty_text(latest_status.is_some(), allowlist.is_some());
    let launcher =
        |package: &str| crate::handlers::device_api::installed_by_launcher(state, Some(package));
    let capable = latest_status
        .and_then(|s| s.capabilities_json.as_deref())
        .is_some_and(|caps| caps.contains(&format!("\"{}\"", lock_glance::LOCK_GLANCE_CAPABILITY)));
    let notes = lock_glance::notes(
        crate::kid_lock::policy_kid_lock(policy).is_some(),
        latest_status.is_some(),
        capable,
        latest_status.and_then(|s| s.notification_listener_enabled),
    );
    let ticked = match lock_glance::ticked(&state.db, policy.device_id).await {
        Ok(ticked) => ticked.into_iter().collect(),
        Err(err) => {
            tracing::error!(device_id = policy.device_id, %err, "couldn't read the lock screen's apps");
            return LockScreenCard {
                show_text: policy.lock_glance_text,
                notes,
                apps: Vec::new(),
                unreadable: true,
                empty_text,
            };
        }
    };
    let apps = if policy.allowlist_json.is_some() {
        let listed = apps
            .iter()
            .filter(|a| a.checked && !a.is_launcher && !launcher(&a.package_name))
            .map(|a| (a.package_name.clone(), a.label.clone()));
        lock_glance::card_apps(listed, &ticked, allowlist.as_ref())
    } else {
        let mut listed: Vec<(String, String)> = installed
            .iter()
            .filter(|a| !launcher(&a.package_name))
            .map(|a| (a.package_name.clone(), a.label.clone()))
            .collect();
        listed.sort_by_key(|(_, label)| label.to_lowercase());
        lock_glance::card_apps(listed, &ticked, None)
    };
    LockScreenCard {
        show_text: policy.lock_glance_text,
        notes,
        apps,
        unreadable: false,
        empty_text,
    }
}

/// `POST /devices/{id}/apps/display` (design 14): this phone's name, icon and colour for one app.
/// `action` "own" = the app's own (on this phone, even with a catalog default), "catalog" =
/// follow the catalog default (the phone's row goes), else save the fields. A choice equal to the
/// catalog default, or "the app's own" without a default, keeps no row. Refused input: 400,
/// Device › Apps with that app's form open, the entered values and the error by the field, the
/// field focused (the form posts to `…#app-<package>`, so the answer lands on the app's row, not
/// the top). Saved: back to the app's row (`/devices/{id}/apps#app-<package>`); nudges.
pub async fn save_app_display(
    State(state): State<AppState>,
    Path(id): Path<i64>,
    Form(form): Form<std::collections::HashMap<String, String>>,
) -> Response {
    use crate::app_display::{DisplayValues, Entered, FieldError};
    let field = |name: &str| form.get(name).map(String::as_str).unwrap_or("");
    let package = field("package_name").trim().to_string();
    if !crate::app_display::valid_package_name(&package) || crate::play::is_play_core(&package) {
        return (StatusCode::BAD_REQUEST, "Not an app this page can name.").into_response();
    }
    let exists: Option<i64> = match sqlx::query_scalar("SELECT id FROM devices WHERE id = ?")
        .bind(id)
        .fetch_optional(&state.db)
        .await
    {
        Ok(found) => found,
        Err(err) => {
            tracing::error!(device_id = id, %err, "couldn't read the device");
            return (
                StatusCode::INTERNAL_SERVER_ERROR,
                "Couldn't save - nothing was changed.",
            )
                .into_response();
        }
    };
    if exists.is_none() {
        return (StatusCode::NOT_FOUND, "Device not found").into_response();
    }
    let refuse = |error: FieldError| {
        let entered = Entered {
            label: field("label").to_string(),
            icon: field("icon").to_string(),
            color: field("color").to_string(),
            error,
        };
        let state = state.clone();
        let package = package.clone();
        async move {
            let mut response = crate::handlers::device_pages::render_apps(
                &state,
                id,
                None,
                Some((package, entered)),
            )
            .await;
            // The page, as a refusal; a page that failed to render keeps its own status.
            if response.status() == StatusCode::OK {
                *response.status_mut() = StatusCode::BAD_REQUEST;
            }
            response
        }
    };
    let action = field("action");
    let values = match action {
        "own" => DisplayValues::own(),
        _ => match crate::app_display::validate(field("label"), field("icon"), field("color")) {
            Ok(values) => values,
            Err(error) => return refuse(error).await,
        },
    };
    let default = match crate::app_display::catalog_defaults(&state.db).await {
        Ok(defaults) => defaults.get(&package).cloned(),
        Err(err) => {
            tracing::error!(device_id = id, %err, "couldn't read the catalog's app names");
            return (
                StatusCode::INTERNAL_SERVER_ERROR,
                "Couldn't save - nothing was changed.",
            )
                .into_response();
        }
    };
    let keep_row = action != "catalog"
        && match &default {
            Some(default) => *default != values,
            None => !values.is_own(),
        };
    let result = if keep_row {
        let rows: i64 = match sqlx::query_scalar(
            "SELECT COUNT(*) FROM device_app_display WHERE device_id = ? AND package_name != ?",
        )
        .bind(id)
        .bind(&package)
        .fetch_one(&state.db)
        .await
        {
            Ok(rows) => rows,
            Err(err) => {
                tracing::error!(device_id = id, %err, "couldn't count the phone's app names");
                return (
                    StatusCode::INTERNAL_SERVER_ERROR,
                    "Couldn't save - nothing was changed.",
                )
                    .into_response();
            }
        };
        if rows >= crate::app_display::MAX_ROWS_PER_DEVICE {
            return refuse(FieldError {
                field: "label",
                message: format!(
                    "This phone already has {} names and icons - remove some first.",
                    crate::app_display::MAX_ROWS_PER_DEVICE
                ),
            })
            .await;
        }
        sqlx::query(
            "INSERT INTO device_app_display (device_id, package_name, label, icon_key, color_key)              VALUES (?, ?, ?, ?, ?) ON CONFLICT(device_id, package_name) DO UPDATE SET              label = excluded.label, icon_key = excluded.icon_key, color_key = excluded.color_key,              updated_at = datetime('now')",
        )
        .bind(id)
        .bind(&package)
        .bind(&values.label)
        .bind(&values.icon)
        .bind(&values.color)
        .execute(&state.db)
        .await
    } else {
        sqlx::query("DELETE FROM device_app_display WHERE device_id = ? AND package_name = ?")
            .bind(id)
            .bind(&package)
            .execute(&state.db)
            .await
    };
    match result {
        Ok(_) => {
            let _ = state.command_notify.send(id);
            Redirect::to(&format!("/devices/{id}/apps#app-{package}")).into_response()
        }
        Err(err) => {
            tracing::error!(device_id = id, %err, "couldn't save the app's name and icon");
            (
                StatusCode::INTERNAL_SERVER_ERROR,
                "Couldn't save - nothing was changed.",
            )
                .into_response()
        }
    }
}

/// "Download on Wi-Fi only" on Device › Apps (design 13; design 31 step 6): catalog apps download
/// on an unmetered network only, the launcher's own update after 3 days on any non-roaming one. An
/// auto-saving form, so a missing checkbox is off; back to its group at the bottom of the page
/// (`#downloads`: without JS a redirect without one would land at the top of a long list); 404 for
/// an unknown device; nudges the phone.
pub async fn update_app_updates(
    State(state): State<AppState>,
    Path(id): Path<i64>,
    Form(form): Form<std::collections::HashMap<String, String>>,
) -> Response {
    let result = sqlx::query(
        "UPDATE device_policy SET app_updates_wifi_only = ?, updated_at = datetime('now') \
         WHERE device_id = ?",
    )
    .bind(form.contains_key("app_updates_wifi_only"))
    .bind(id)
    .execute(&state.db)
    .await;
    match result {
        Ok(r) if r.rows_affected() == 0 => {
            (StatusCode::NOT_FOUND, "Device not found").into_response()
        }
        Ok(_) => {
            let _ = state.command_notify.send(id);
            Redirect::to(&format!("/devices/{id}/apps#downloads")).into_response()
        }
        Err(err) => {
            tracing::error!(device_id = id, %err, "couldn't save the app update switch");
            (
                StatusCode::INTERNAL_SERVER_ERROR,
                "Couldn't save - nothing was changed.",
            )
                .into_response()
        }
    }
}

/// Advanced › Updates & notifications (handy step 11; the "Launcher updates and notifications"
/// card until design 31 step 3): the update fence (other Home apps paused while the launcher
/// installs its own update), the notification auto-cancel rule and the boot cover (design 16b).
/// One auto-saving form, so a missing checkbox is off; 404 for an unknown device; nudges; back to
/// Advanced without a fragment (a save never jumps).
pub async fn update_kiosk_escapes(
    State(state): State<AppState>,
    Path(id): Path<i64>,
    Form(form): Form<std::collections::HashMap<String, String>>,
) -> Response {
    let result = sqlx::query(
        "UPDATE device_policy SET update_fence = ?, notification_auto_cancel = ?, boot_cover = ?, \
         updated_at = datetime('now') WHERE device_id = ?",
    )
    .bind(form.contains_key("update_fence"))
    .bind(form.contains_key("notification_auto_cancel"))
    .bind(form.contains_key("boot_cover"))
    .bind(id)
    .execute(&state.db)
    .await;
    match result {
        Ok(r) if r.rows_affected() == 0 => {
            (StatusCode::NOT_FOUND, "Device not found").into_response()
        }
        Ok(_) => {
            let _ = state.command_notify.send(id);
            // No fragment: scroll-restore.js keeps the exact position (design 31 §5.1.4,
            // qa-31-s3-code M3).
            Redirect::to(&format!("/devices/{id}/advanced")).into_response()
        }
        Err(err) => {
            tracing::error!(device_id = id, %err, "couldn't save the kiosk escape switches");
            (
                StatusCode::INTERNAL_SERVER_ERROR,
                "Couldn't save - nothing was changed.",
            )
                .into_response()
        }
    }
}

struct RuleOption {
    id: i64,
    name: String,
}

struct LiftView {
    id: i64,
    what: String,
    created_at: String,
    created_by: String,
    status: String,
    can_end: bool,
}

/// The device page's "Time rules" card: what the phone last reported, the lift forms and history.
struct TimeCard {
    /// Following its own rules (the override switch) rather than the global ones.
    custom: bool,
    /// "Skole is active - calls blocked", "No rule active", or none before the first report.
    now_line: Option<String>,
    /// "Screen time 2026-10-05: 42 of 90 min (incl. 30 extra)".
    screen_line: Option<String>,
    rules: Vec<RuleOption>,
    lifts: Vec<LiftView>,
    /// The launcher reported capabilities without `time_rules_v1`: it still runs the old schedule.
    launcher_without_rules: bool,
    rules_error: bool,
    /// A calls-off rule (school) applies but calls are unmanaged: incoming calls still ring.
    incoming_calls_unblocked: bool,
    /// The phone couldn't read its screen-time record and counts today's budget as used up.
    ledger_unreadable: bool,
}

/// Status of a rule lift that the server still considers running, from what the phone last
/// reported (`time_state.lifts_active`): the phone's view wins - a reboot or late delivery can
/// end a lift there before the server's expiry (QA step 6 #8).
pub(crate) fn rule_lift_status(
    lift_id: i64,
    created_at: &str,
    expires_at: &str,
    report: Option<(&str, &serde_json::Value)>,
) -> String {
    let expiry = format!("server expiry {expires_at} (UTC)");
    match report {
        Some((reported_at, state)) if reported_at >= created_at => {
            let active = state["lifts_active"]
                .as_array()
                .is_some_and(|ids| ids.iter().any(|id| id.as_i64() == Some(lift_id)));
            if active {
                format!("Active on the phone - {expiry}")
            } else {
                format!("Ended on the phone - {expiry}")
            }
        }
        _ => format!("Waiting for the phone - {expiry}"),
    }
}

/// The two summary lines from the launcher's `time_state` JSON (design 06 "Wire").
fn time_state_lines(json: &str) -> (Option<String>, Option<String>) {
    let Ok(state) = serde_json::from_str::<serde_json::Value>(json) else {
        return (None, None);
    };
    let reason = state["lock_reason"].as_str().unwrap_or("NONE");
    let calls = if state["calls_blocked"].as_bool() == Some(true) {
        " - calls blocked (emergency calls work)"
    } else {
        ""
    };
    let now_line = match state["active_rule_name"].as_str() {
        Some(name) if !name.is_empty() => format!("{name} is active{calls}"),
        _ if reason == "SCREEN_TIME" => {
            "Screen time is used up - calls and messages only".to_string()
        }
        _ if reason != "NONE" => format!("Locked ({reason}){calls}"),
        _ => "No rule active".to_string(),
    };
    let day = state["day"].as_str().unwrap_or("today");
    let used = state["used_minutes"].as_i64();
    let screen_line = used.map(|used| match state["budget_minutes"].as_i64() {
        Some(budget) => {
            let extra = state["extra_minutes"].as_i64().unwrap_or(0);
            let extra = if extra > 0 {
                format!(" (incl. {extra} extra)")
            } else {
                String::new()
            };
            format!("Screen time {day}: {used} of {budget} min{extra}")
        }
        None => format!("Screen time {day}: {used} min, no limit"),
    });
    (Some(now_line), screen_line)
}

async fn time_card(
    state: &AppState,
    policy: &DevicePolicy,
    latest: Option<&DeviceStatus>,
) -> TimeCard {
    let (now_line, screen_line) = latest
        .and_then(|s| s.time_state_json.as_deref())
        .map(time_state_lines)
        .unwrap_or((None, None));
    let reported_state: Option<(String, serde_json::Value)> = latest.and_then(|s| {
        s.time_state_json
            .as_deref()
            .and_then(|j| serde_json::from_str::<serde_json::Value>(j).ok())
            .map(|v| (s.reported_at.clone(), v))
    });
    let ledger_unreadable = reported_state
        .as_ref()
        .is_some_and(|(_, v)| v["ledger_unreadable"].as_bool() == Some(true));
    let launcher_without_rules = launcher_without_rules(latest);
    let rules = crate::time_rules::effective_rule_rows(
        &state.db,
        policy.device_id,
        policy.custom_schedule_enabled,
    )
    .await;
    let rules_error = rules.is_err();
    let rules = rules.unwrap_or_default();
    let incoming_calls_unblocked = !policy.calls_managed && rules.iter().any(|r| !r.calls_allowed);
    let rules = rules
        .into_iter()
        .map(|r| RuleOption {
            id: r.id,
            name: r.name,
        })
        .collect();
    #[derive(sqlx::FromRow)]
    struct LiftWithState {
        #[sqlx(flatten)]
        lift: crate::time_rules::TimeLiftRow,
        active: bool,
    }
    let lifts = sqlx::query_as::<_, LiftWithState>(
        "SELECT *, (ended_early_at IS NULL AND expires_at > datetime('now')) AS active \
         FROM time_lifts WHERE device_id = ? ORDER BY id DESC LIMIT 10",
    )
    .bind(policy.device_id)
    .fetch_all(&state.db)
    .await;
    let lifts = lifts
        .unwrap_or_default()
        .into_iter()
        .map(|LiftWithState { lift, active }| {
            let (what, status, can_end) = if lift.target == "rule" {
                let rule = lift
                    .rule_name
                    .clone()
                    .unwrap_or_else(|| "All rules".to_string());
                let status = match (&lift.ended_early_at, active) {
                    (Some(at), _) => format!("Ended early {at}"),
                    (None, true) => rule_lift_status(
                        lift.id,
                        &lift.created_at,
                        &lift.expires_at,
                        reported_state.as_ref().map(|(at, v)| (at.as_str(), v)),
                    ),
                    (None, false) => "Over".to_string(),
                };
                (
                    format!("{rule} lifted for {} min", lift.minutes),
                    status,
                    active,
                )
            } else {
                let status = if active {
                    "Sent - counts for the day the phone receives it".to_string()
                } else {
                    "Done".to_string()
                };
                (format!("+{} min screen time", lift.minutes), status, false)
            };
            LiftView {
                id: lift.id,
                what,
                created_at: lift.created_at,
                created_by: lift.created_by.unwrap_or_default(),
                status,
                can_end,
            }
        })
        .collect();
    TimeCard {
        custom: policy.custom_schedule_enabled,
        now_line,
        screen_line,
        rules,
        lifts,
        launcher_without_rules,
        rules_error,
        incoming_calls_unblocked,
        ledger_unreadable,
    }
}

pub async fn view_device(
    State(state): State<AppState>,
    Path(id): Path<i64>,
    Query(query): Query<std::collections::HashMap<String, String>>,
) -> impl IntoResponse {
    render_device(&state, id, &query).await
}

/// The Apps list a phone's pages build on: one row per app the phone reports and per catalog app
/// it doesn't have, the launcher first; the parsed allowlist (a corrupt one reads as empty and is
/// flagged), what the phone reports installed, and its downloads. Device › Apps shows them (design
/// 31 step 6), Lock Screen & PINs lists its Notifications from them (step 4) and the device page
/// counts them. A DB error is the caller's to report, never fewer rows: a catalog app that didn't
/// load would read as gone, a selection that didn't load as "not allowed".
pub(crate) struct AppRows {
    pub(crate) apps: Vec<UnifiedAppRow>,
    pub(crate) installed: Vec<InstalledApp>,
    pub(crate) allowed: std::collections::HashSet<String>,
    pub(crate) allowlist_corrupt: bool,
    pub(crate) downloads: Option<crate::app_downloads::AppDownloads>,
}

pub(crate) async fn app_rows(
    state: &AppState,
    id: i64,
    policy: &DevicePolicy,
    latest: Option<&DeviceStatus>,
) -> Result<AppRows, sqlx::Error> {
    // A corrupt stored allowlist is shown as a warning, not silently as "nothing allowed" -
    // `build_policy` answers the phone with a 500 for it.
    let parsed_allowlist = policy
        .allowlist_json
        .as_deref()
        .map(serde_json::from_str::<Vec<String>>)
        .transpose();
    let allowlist_corrupt = parsed_allowlist.is_err();
    let allowed: std::collections::HashSet<String> = parsed_allowlist
        .ok()
        .flatten()
        .unwrap_or_default()
        .into_iter()
        .collect();

    let installed: Vec<InstalledApp> = latest
        .and_then(|s| s.installed_apps_json.as_deref())
        .and_then(|j| serde_json::from_str(j).ok())
        .unwrap_or_default();

    let all_tracked = sqlx::query_as::<_, TrackedApp>("SELECT * FROM tracked_apps ORDER BY name")
        .fetch_all(&state.db)
        .await?;
    let selected_app_ids: std::collections::HashSet<i64> =
        sqlx::query_scalar("SELECT tracked_app_id FROM device_tracked_apps WHERE device_id = ?")
            .bind(id)
            .fetch_all(&state.db)
            .await?
            .into_iter()
            .collect();

    // Fresh (not stale) download-progress rows for this device - only ever meaningful for a
    // NotInstalled row below, so this is the only place they're consulted. The 10-minute window
    // matches the client's own in-flight-attempt timeout (TrackedAppUpdateState) for consistency -
    // long enough to cover a slow download, short enough that an abandoned attempt's last-reported
    // percentage doesn't linger looking "in progress" forever.
    let install_progress: std::collections::HashMap<i64, (i64, bool)> =
        sqlx::query_as::<_, (i64, i64, bool)>(
            "SELECT tracked_app_id, percent, failed FROM device_install_progress \
             WHERE device_id = ? AND updated_at > datetime('now', '-10 minutes')",
        )
        .bind(id)
        .fetch_all(&state.db)
        .await?
        .into_iter()
        .map(|(tracked_app_id, percent, failed)| (tracked_app_id, (percent, failed)))
        .collect();

    // What the phone said about its downloads (design 13): waiting for Wi-Fi since when, how far.
    // A fresh progress row above still wins for the live percentage - but not over a release the
    // phone refused (security review M10): its reason beats "Install failed".
    let zone = crate::display_time::Zone::load(&state.db).await;
    let now = chrono::Utc::now();
    let downloads =
        crate::app_downloads::parse(latest.and_then(|s| s.app_downloads_json.as_deref()));
    let download_of = |tracked_app_id: i64| {
        downloads
            .as_ref()
            .and_then(|d| d.entry(tracked_app_id))
            .filter(|e| {
                crate::app_downloads::refused(e) || !install_progress.contains_key(&tracked_app_id)
            })
    };

    let mut apps: Vec<UnifiedAppRow> = Vec::new();
    let mut seen_packages: std::collections::HashSet<String> = std::collections::HashSet::new();

    // First pass: every app the device actually reports installed (preinstalled or otherwise),
    // matched against the catalog by package name where possible so an app that's both installed
    // *and* trackable still gets exactly one row.
    for app in installed
        .iter()
        .filter(|a| !crate::play::is_play_core(&a.package_name))
    {
        let tracked_match = all_tracked
            .iter()
            .find(|t| !t.package_name.is_empty() && t.package_name == app.package_name);
        let update = tracked_match.and_then(|t| download_of(t.id));
        apps.push(UnifiedAppRow {
            status_label: if app.preinstalled {
                "Preinstalled".to_string()
            } else if app.installer.as_deref() == Some(crate::play::PLAY_STORE) {
                "Installed from Play".to_string()
            } else if let Some(update) = update {
                crate::app_downloads::update_label(update, &zone, now)
            } else {
                "Installed".to_string()
            },
            is_installing: false,
            // An update the phone refused (security review M10) shows like a failed install.
            install_failed: update.is_some_and(crate::app_downloads::refused),
            checked: allowed.contains(&app.package_name),
            tracked_app_id: tracked_match.map(|t| t.id),
            show_no_release_hint: false,
            package_name: app.package_name.clone(),
            label: app.label.clone(),
            is_launcher: false,
        });
        seen_packages.insert(app.package_name.clone());
    }

    // Second pass: catalog apps not currently installed (including manual-upload apps with no
    // package name typed yet, which can never match an installed app by name) - the launcher's
    // own row is handled separately below, it never belongs here.
    for t in &all_tracked {
        if t.is_launcher || crate::play::is_play_core(&t.package_name) {
            continue;
        }
        if !t.package_name.is_empty() && seen_packages.contains(&t.package_name) {
            continue;
        }
        let has_release = t.latest_release_tag.is_some();
        let progress = install_progress.get(&t.id).copied();
        let (status_label, is_installing, install_failed) = match (progress, download_of(t.id)) {
            (_, Some(download)) if crate::app_downloads::refused(download) => (
                crate::app_downloads::new_app_label(download, &zone, now),
                false,
                true,
            ),
            (Some((_, true)), _) => ("Install failed".to_string(), false, true),
            (Some((percent, false)), _) => (format!("Installing {percent}%"), true, false),
            (None, Some(download)) => (
                crate::app_downloads::new_app_label(download, &zone, now),
                false,
                false,
            ),
            (None, None) => ("Not installed".to_string(), false, false),
        };
        apps.push(UnifiedAppRow {
            status_label,
            is_installing,
            install_failed,
            checked: selected_app_ids.contains(&t.id),
            tracked_app_id: Some(t.id),
            show_no_release_hint: !has_release,
            package_name: t.package_name.clone(),
            label: t.name.clone(),
            is_launcher: false,
        });
    }

    apps.sort_by(|a, b| a.label.to_lowercase().cmp(&b.label.to_lowercase()));

    // The launcher's own row is pinned first rather than sorted alphabetically with everything
    // else - it's not really "one app among many" the way the rest of this list is, it's the
    // thing enforcing the rest of this list, so calling that out up top reads better than letting
    // it land wherever its name happens to sort.
    if let Some(launcher) = all_tracked.iter().find(|t| t.is_launcher) {
        apps.insert(
            0,
            UnifiedAppRow {
                // The launcher row never shows a percentage: its download line always.
                status_label: downloads
                    .as_ref()
                    .and_then(|d| d.entry(launcher.id))
                    .map(|e| crate::app_downloads::launcher_label(e, &zone, now))
                    .unwrap_or_else(|| "Installed".to_string()),
                is_installing: false,
                install_failed: false,
                checked: true,
                tracked_app_id: Some(launcher.id),
                show_no_release_hint: false,
                package_name: launcher.package_name.clone(),
                label: launcher.name.clone(),
                is_launcher: true,
            },
        );
    }

    Ok(AppRows {
        apps,
        installed,
        allowed,
        allowlist_corrupt,
        downloads,
    })
}

/// The device page.
async fn render_device(
    state: &AppState,
    id: i64,
    query: &std::collections::HashMap<String, String>,
) -> Response {
    let state = state.clone();
    let device = sqlx::query_as::<_, Device>("SELECT * FROM devices WHERE id = ?")
        .bind(id)
        .fetch_optional(&state.db)
        .await
        .ok()
        .flatten();

    let Some(device) = device else {
        return (axum::http::StatusCode::NOT_FOUND, "Device not found").into_response();
    };

    let policy =
        sqlx::query_as::<_, DevicePolicy>("SELECT * FROM device_policy WHERE device_id = ?")
            .bind(id)
            .fetch_optional(&state.db)
            .await
            .ok()
            .flatten()
            .unwrap_or(DevicePolicy {
                device_id: id,
                // See the matching comment in device_api::policy - bool::default() is false,
                // but a never-configured device must still show/default to filtering on.
                vpn_filter_enabled: true,
                ..Default::default()
            });

    let latest_status = sqlx::query_as::<_, DeviceStatus>(
        "SELECT * FROM device_status WHERE device_id = ? ORDER BY reported_at DESC, id DESC LIMIT 1",
    )
    .bind(id)
    .fetch_optional(&state.db)
    .await
    .ok()
    .flatten();

    // The Apps row's value (design 31 step 6): the switches on under Device › Apps, the launcher
    // not counted - the number the Apps tab shows.
    let rows = app_rows(&state, id, &policy, latest_status.as_ref())
        .await
        .map_err(|err| tracing::error!(device_id = id, %err, "can't read the phone's apps"))
        .ok();
    let apps_value = match &rows {
        Some(rows) if !rows.allowlist_corrupt => format!(
            "{} allowed",
            rows.apps
                .iter()
                .filter(|a| a.checked && !a.is_launcher)
                .count()
        ),
        // The stored list is corrupt; a failed read says so (qa-31-s6-code N2).
        Some(_) => "List unreadable".to_string(),
        None => "Couldn't read".to_string(),
    };
    let restrictions_paused = latest_status
        .as_ref()
        .is_some_and(|s| s.restrictions_paused);

    let contact_count: i64 =
        sqlx::query_scalar("SELECT COUNT(*) FROM device_contacts WHERE device_id = ?")
            .bind(id)
            .fetch_one(&state.db)
            .await
            .unwrap_or(0);
    let calls_summary = if !policy.calls_managed {
        "Not managed - calls and SMS work as on any phone.".to_string()
    } else {
        let contacts = match contact_count {
            1 => "1 contact".to_string(),
            n => format!("{n} contacts"),
        };
        let calls = if policy.calls_enabled {
            "calls on"
        } else {
            "calls off"
        };
        let sms = if policy.sms_enabled {
            "SMS on"
        } else {
            "SMS off"
        };
        format!("Managed - {contacts}, {calls}, {sms}.")
    };

    let (call_warnings, call_hints, call_problems) =
        match crate::handlers::calls::call_report(&state, &policy).await {
            Ok(report) => (report.warnings, report.hints, report.problems),
            Err(err) => {
                tracing::error!(device_id = id, %err, "failed to read call warnings");
                (
                    vec![
                        "Couldn't read this phone's call state - check the server log.".to_string(),
                    ],
                    Vec::new(),
                    Vec::new(),
                )
            }
        };

    let time = time_card(&state, &policy, latest_status.as_ref()).await;
    // Each card shows its own codes (design 24, QA #8); nothing at the top of the page.
    let notice = query.get("notice").map(String::as_str);
    let zone = crate::display_time::Zone::load(&state.db).await;
    let parent_pin = crate::handlers::parent_pin::card(
        &state.db,
        &policy,
        latest_status.as_ref(),
        None,
        &zone,
        chrono::Utc::now(),
        false,
    )
    .await;
    let relock = crate::handlers::window_relock::card(
        &state.db,
        id,
        latest_status.as_ref(),
        notice,
        chrono::Utc::now().timestamp_millis(),
        // Whether each blocked app is ticked under Apps (QA of cmd-expiry, Low 3).
        rows.as_ref()
            .filter(|rows| policy.allowlist_json.is_some() && !rows.allowlist_corrupt)
            .map(|rows| &rows.allowed),
    )
    .await;
    let music = crate::handlers::music::device_card(
        &state,
        &policy,
        latest_status.as_ref(),
        query.get("music_notice").map(String::as_str),
        query.get("music_entry").and_then(|e| e.parse().ok()),
    )
    .await
    .map_err(|err| tracing::error!(device_id = id, %err, "couldn't load the music card"))
    .ok();

    // The same rows the Devices list counts, from the cards built above (not read twice).
    let attention = crate::attention::items(
        id,
        &crate::attention::facts_of(
            &crate::attention::Sources {
                device: &device,
                policy: &policy,
                latest: latest_status.as_ref(),
                relock: &relock,
                call_problems: &call_problems,
                incoming_calls_not_blocked: time.incoming_calls_unblocked,
                connection: connection_of(&state, &device, chrono::Utc::now()),
            },
            chrono::Utc::now().timestamp_millis(),
        ),
    );

    Html(
        DeviceDetailTemplate {
            attention,
            access: access_card(
                &state,
                &device,
                query.get("notice").map(String::as_str),
                &zone,
                chrono::Utc::now(),
            ),
            about_value: latest_status
                .as_ref()
                .and_then(|s| s.app_version.as_deref())
                .filter(|v| !v.is_empty())
                .map(|v| format!("Vibb {}", crate::handlers::device_pages::short_version(v))),
            music,
            apps_value,
            time,
            lock_value: if crate::kid_lock::policy_kid_lock(&policy).is_some() {
                "PIN on"
            } else {
                "PIN off"
            },
            parent_pin,
            relock,
            title: device.name.clone(),
            calls_summary,
            call_warnings,
            call_hints,
            managed_without_pin: managed_without_pin(&policy),
            hardening: policy.hardening.clone(),
            home_value: format!("{} per row", policy.home_columns),
            filter_value: if policy.vpn_filter_enabled {
                "On"
            } else {
                "Off"
            },
            restrictions_paused,
            device,
            latest_status,
        }
        .render()
        .unwrap(),
    )
    .into_response()
}

/// Where an app's row is on Device › Apps (design 31 step 6): `app-<package>` - the anchor the
/// blocked apps, the Apps tab and the saves link to -, or `catalog-<id>` for a catalog app without
/// a package (or with an older catalog's free-text one). The page's ids and [toggle_app]'s
/// redirect both come from here.
pub(crate) fn app_anchor(package: &str, tracked_app_id: Option<i64>) -> String {
    match tracked_app_id {
        _ if crate::handlers::device_api::is_package_name(package) => format!("app-{package}"),
        Some(id) => format!("catalog-{id}"),
        // No row has this: the status intake drops unnamed apps and `toggle_app` refuses such a
        // post. Not a group's id either, so it can never duplicate one (qa-31-s6-code N3).
        None => "apps".to_string(),
    }
}

/// The reason under a refused switch's row (`?notice=<code>&app=<row>`, design 31 step 6);
/// `None` for a code this page doesn't know.
pub(crate) fn toggle_refusal(code: &str) -> Option<&'static str> {
    match code {
        "from_play" => Some(
            "Installed from the Play Store, so it can't also come from the catalog. Uninstall \
             that copy first.",
        ),
        "not_an_app" => Some("This app changed in the catalog since the page loaded. Try again."),
        _ => None,
    }
}

/// One app's switch on Device › Apps (and the music app's on the Music card): its own
/// auto-saving form, so a parent never has to find a Save button.
///
/// Takes `package_name` and `tracked_app_id` (each optional): a row may be a catalog app with no
/// package yet, an app the phone reports that the catalog doesn't have, or both
/// (`UnifiedAppRow`).
///
/// On: adds the `device_tracked_apps` row for a catalog app (so it is pushed and kept up to
/// date), allows the package, and cancels an uninstall an earlier "off" queued (the catalog app's
/// own package too). Off: removes both and queues a silent uninstall
/// (`device_pending_uninstalls`) when the phone's last report has the package installed and not
/// preinstalled (a preinstalled app can only be hidden).
///
/// Where it returns (§5.1): `back=music` (or `anchor=music`, a page from before design 31 step 6)
/// to the Music card (`/devices/{id}#music`, until step 7 builds Device › Music), anything else to
/// the app's row on Device › Apps (`#app-<package>`): the row moves between Allowed and Not
/// allowed, and the fragment finds it again, with or without JS.
///
/// The package must be a package name (the status intake's rule; it goes into the allowlist), or
/// exactly what the catalog row the post names stores - an older catalog's free text stays
/// switchable (qa-31-s6-code L4). Refused, nothing written: such a package that no longer matches
/// its catalog row (back to the row with `?notice=not_an_app`), turning on a catalog app whose
/// package the phone has from the Play Store (one source per package: `?notice=from_play`); a
/// post naming nothing or another package, Play's core or Vibb itself - by its catalog id or its
/// package (L3) - is a 400 (no page sends one), an unknown phone a 404. A catalog app deleted since
/// the page loaded changes nothing and returns to the group the tap came from (N1). A DB error is
/// a 500.
pub async fn toggle_app(
    State(state): State<AppState>,
    Path(id): Path<i64>,
    Form(form): Form<std::collections::HashMap<String, String>>,
) -> Response {
    let checked = form.contains_key("selected");
    let package_name = form
        .get("package_name")
        .map(|p| p.trim())
        .unwrap_or("")
        .to_string();
    let tracked_app_id: Option<i64> = form.get("tracked_app_id").and_then(|s| s.parse().ok());

    // Play Store/services/GSF are never the parent's to allow or remove (handy step 7): the
    // launcher keeps them installed, unhidden and out of the kiosk whatever the allowlist says.
    if crate::play::is_play_core(&package_name) {
        return (
            StatusCode::BAD_REQUEST,
            "The Play Store, Play services and Google Services Framework are managed by Vibb \
             itself and can't be allowed or removed here.",
        )
            .into_response();
    }
    if package_name.is_empty() && tracked_app_id.is_none() {
        return (StatusCode::BAD_REQUEST, "Not an app - nothing was changed.").into_response();
    }
    let failed = |err: sqlx::Error| {
        tracing::error!(device_id = id, package_name, %err, "couldn't change an app");
        (
            StatusCode::INTERNAL_SERVER_ERROR,
            "Couldn't change this app - check the server log.",
        )
            .into_response()
    };
    match sqlx::query_scalar::<_, i64>("SELECT id FROM devices WHERE id = ?")
        .bind(id)
        .fetch_optional(&state.db)
        .await
    {
        Ok(Some(_)) => {}
        Ok(None) => return (StatusCode::NOT_FOUND, "Device not found").into_response(),
        Err(err) => return failed(err),
    }
    // Vibb is on every phone and never switched: its row has no switch, and a post naming it by
    // its catalog id or its package is refused (qa-31-s6-code L3).
    match sqlx::query_scalar::<_, i64>(
        "SELECT COUNT(*) FROM tracked_apps WHERE is_launcher = 1 \
         AND (id = ? OR (package_name != '' AND package_name = ?))",
    )
    .bind(tracked_app_id)
    .bind(&package_name)
    .fetch_one(&state.db)
    .await
    {
        Ok(0) => {}
        Ok(_) => return (StatusCode::BAD_REQUEST, "Vibb is always on.").into_response(),
        Err(err) => return failed(err),
    }
    let page = format!("/devices/{id}/apps");
    // The catalog app's own package: an uninstall queued for it is cancelled too, and an older
    // catalog's free-text package is accepted only as exactly this.
    let catalog_package = match tracked_app_id {
        Some(tid) => {
            match sqlx::query_scalar::<_, String>(
                "SELECT package_name FROM tracked_apps WHERE id = ?",
            )
            .bind(tid)
            .fetch_optional(&state.db)
            .await
            {
                Ok(Some(package)) => Some(package).filter(|p| !p.is_empty()),
                // Removed from the catalog since the page loaded (a second tab, a double tap):
                // back to the group the tap came from, never the top of the page.
                Ok(None) => {
                    let group = if checked { "not-allowed" } else { "allowed" };
                    return Redirect::to(&format!("{page}#{group}")).into_response();
                }
                Err(err) => return failed(err),
            }
        }
        None => None,
    };
    let anchor = app_anchor(&package_name, tracked_app_id);
    if !package_name.is_empty()
        && !crate::handlers::device_api::is_package_name(&package_name)
        && catalog_package.as_deref() != Some(package_name.as_str())
    {
        if tracked_app_id.is_none() {
            return (StatusCode::BAD_REQUEST, "Not an app - nothing was changed.").into_response();
        }
        return Redirect::to(&format!("{page}?notice=not_an_app&app={anchor}#{anchor}"))
            .into_response();
    }
    let installed = match latest_installed(&state, id).await {
        Ok(installed) => installed,
        Err(err) => return failed(err),
    };

    if checked {
        // One source per package: Vibb can't update a Play-installed copy from the catalog
        // (another signature, Android 14's update ownership).
        if let Some(catalog) = &catalog_package
            && installed.iter().any(|a| {
                a.package_name == *catalog
                    && a.installer.as_deref() == Some(crate::play::PLAY_STORE)
            })
        {
            return Redirect::to(&format!("{page}?notice=from_play&app={anchor}#{anchor}"))
                .into_response();
        }
        if let Some(tid) = tracked_app_id
            && let Err(err) = sqlx::query(
                "INSERT OR IGNORE INTO device_tracked_apps (device_id, tracked_app_id) VALUES (?, ?)",
            )
            .bind(id)
            .bind(tid)
            .execute(&state.db)
            .await
        {
            return failed(err);
        }
        // On again before the phone synced: the uninstall the "off" queued is off too - else the
        // next sync would still remove the app ("deleted by your administrator").
        for package in [Some(&package_name), catalog_package.as_ref()]
            .into_iter()
            .flatten()
            .filter(|p| !p.is_empty())
        {
            if let Err(err) = sqlx::query(
                "DELETE FROM device_pending_uninstalls WHERE device_id = ? AND package_name = ?",
            )
            .bind(id)
            .bind(package)
            .execute(&state.db)
            .await
            {
                return failed(err);
            }
        }
        if !package_name.is_empty()
            && let Err(err) = add_to_allowlist(&state, id, &package_name).await
        {
            return allowlist_error_response(id, &package_name, err);
        }
    } else {
        if let Some(tid) = tracked_app_id
            && let Err(err) = sqlx::query(
                "DELETE FROM device_tracked_apps WHERE device_id = ? AND tracked_app_id = ?",
            )
            .bind(id)
            .bind(tid)
            .execute(&state.db)
            .await
        {
            return failed(err);
        }
        if !package_name.is_empty() {
            if let Err(err) = remove_from_allowlist(&state, id, &package_name).await {
                return allowlist_error_response(id, &package_name, err);
            }
            let removable = installed
                .iter()
                .any(|a| a.package_name == package_name && !a.preinstalled);
            if removable
                && let Err(err) = sqlx::query(
                    "INSERT OR IGNORE INTO device_pending_uninstalls (device_id, package_name) \
                     VALUES (?, ?)",
                )
                .bind(id)
                .bind(&package_name)
                .execute(&state.db)
                .await
            {
                return failed(err);
            }
        }
    }

    let _ = state.command_notify.send(id);
    let back = form.get("back").or_else(|| form.get("anchor"));
    if back.map(String::as_str) == Some("music") {
        return Redirect::to(&format!("/devices/{id}#music")).into_response();
    }
    Redirect::to(&format!("{page}#{anchor}")).into_response()
}

fn allowlist_error_response(
    device_id: i64,
    package_name: &str,
    err: AllowlistError,
) -> axum::response::Response {
    tracing::error!(device_id, package_name, %err, "failed to change allowlist");
    (
        axum::http::StatusCode::INTERNAL_SERVER_ERROR,
        format!(
            "Couldn't change the allowed apps for this device ({err}). Nothing was changed - \
             check the server log."
        ),
    )
        .into_response()
}

/// The apps the phone's last report lists installed (none before its first report): where each
/// came from (a Play copy can't also come from the catalog) and whether it is preinstalled (it can
/// only be hidden, never uninstalled).
async fn latest_installed(
    state: &AppState,
    device_id: i64,
) -> Result<Vec<InstalledApp>, sqlx::Error> {
    let json: Option<Option<String>> = sqlx::query_scalar(
        "SELECT installed_apps_json FROM device_status WHERE device_id = ? \
         ORDER BY reported_at DESC, id DESC LIMIT 1",
    )
    .bind(device_id)
    .fetch_optional(&state.db)
    .await?;
    Ok(json
        .flatten()
        .as_deref()
        .and_then(|j| serde_json::from_str(j).ok())
        .unwrap_or_default())
}

/// Why [add_to_allowlist]/[remove_from_allowlist] couldn't change the allowlist. Neither ever
/// guesses: a read error or a stored allowlist that isn't valid JSON leaves the row untouched and
/// is reported, instead of being treated as an empty list (which used to silently overwrite a
/// corrupt list on add, and silently keep an "unchecked" app allowed on remove).
#[derive(Debug)]
pub(crate) enum AllowlistError {
    Db(sqlx::Error),
    MissingPolicyRow,
    Corrupt(serde_json::Error),
}

impl std::fmt::Display for AllowlistError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            AllowlistError::Db(err) => write!(f, "database error: {err}"),
            AllowlistError::MissingPolicyRow => write!(f, "no device_policy row"),
            AllowlistError::Corrupt(err) => write!(f, "stored allowlist is not valid JSON: {err}"),
        }
    }
}

impl From<sqlx::Error> for AllowlistError {
    fn from(err: sqlx::Error) -> Self {
        AllowlistError::Db(err)
    }
}

/// Read-modify-write of one device's allowlist in a single transaction, so a concurrent writer
/// (another toggle, the heartbeat bootstrap) can't be lost in between. `edit` returns whether it
/// changed anything; nothing is written if not. A `NULL` allowlist (unmanaged) starts as empty.
async fn edit_allowlist(
    state: &AppState,
    device_id: i64,
    edit: impl FnOnce(&mut Vec<String>) -> bool,
) -> Result<(), AllowlistError> {
    let mut tx = state.db.begin().await?;
    let current: Option<Option<String>> =
        sqlx::query_scalar("SELECT allowlist_json FROM device_policy WHERE device_id = ?")
            .bind(device_id)
            .fetch_optional(&mut *tx)
            .await?;
    let current = current.ok_or(AllowlistError::MissingPolicyRow)?;
    let mut packages: Vec<String> = match current.as_deref() {
        Some(json) => serde_json::from_str(json).map_err(AllowlistError::Corrupt)?,
        None => Vec::new(),
    };
    if !edit(&mut packages) {
        return Ok(());
    }
    let json = serde_json::to_string(&packages).expect("a list of strings always serializes");
    sqlx::query(
        "UPDATE device_policy SET allowlist_json = ?, updated_at = datetime('now') \
         WHERE device_id = ?",
    )
    .bind(&json)
    .bind(device_id)
    .execute(&mut *tx)
    .await?;
    tx.commit().await?;
    Ok(())
}

/// Adds one package to a device's allowlist if it isn't already there. Used by
/// [toggle_app] - see its own doc comment for why. `updated_at` is bumped like every other
/// `device_policy` write, so the "changed since last sync" nudge story stays consistent even though
/// this isn't going through the normal `update_policy` form save.
pub(crate) async fn add_to_allowlist(
    state: &AppState,
    device_id: i64,
    package_name: &str,
) -> Result<(), AllowlistError> {
    edit_allowlist(state, device_id, |packages| {
        if packages.iter().any(|p| p == package_name) {
            return false;
        }
        packages.push(package_name.to_string());
        true
    })
    .await
}

/// Removes one package from a device's allowlist if present - the uncheck-side counterpart to
/// [add_to_allowlist], used by [toggle_app].
pub(crate) async fn remove_from_allowlist(
    state: &AppState,
    device_id: i64,
    package_name: &str,
) -> Result<(), AllowlistError> {
    edit_allowlist(state, device_id, |packages| {
        let original_len = packages.len();
        packages.retain(|p| p != package_name);
        packages.len() != original_len
    })
    .await
}

/// The quick controls (Home Screen) and the web filter switch (Web Filter). Since design 31 step 5
/// the two live on two pages, so the form says which part it carries (§5.6, H1): `section=quick`
/// writes only the quick controls and returns to `/devices/{id}/home`, `section=filter` only the
/// filter and returns to `/devices/{id}/filter` (no fragment: scroll-restore keeps the place). A
/// form without `section` (an older tab) writes both, as before, and returns to the device page;
/// an unknown section is a 400. Unticked = off, as every checkbox. 404 for an unknown phone, 500
/// on a DB error (nothing written either way); nudges the phone. `Form<HashMap<...>>` is safe:
/// nothing here is a repeated-name checkbox group.
pub async fn update_policy(
    State(state): State<AppState>,
    Path(id): Path<i64>,
    Form(fields): Form<std::collections::HashMap<String, String>>,
) -> axum::response::Response {
    let (quick, filter, back) = match fields.get("section").map(String::as_str) {
        None => (true, true, format!("/devices/{id}")),
        Some("quick") => (true, false, format!("/devices/{id}/home")),
        Some("filter") => (false, true, format!("/devices/{id}/filter")),
        Some(_) => {
            return (axum::http::StatusCode::BAD_REQUEST, "Unknown section").into_response();
        }
    };
    // Home, status bar info, recents, notifications, and the power button menu don't let a kid
    // reach anything outside the pinned/allowed app set - Home just re-navigates within it,
    // recents only lists apps already in it, status bar info is read-only, notifications can only
    // come from allowed apps, and the power button menu just offers power off/restart/etc, not
    // app access. None of these are real restrictions, just navigation/system convenience, so
    // they're always on rather than admin-configurable - previously notifications and the power
    // button menu had their own checkboxes, but there was never a real reason for a parent to want
    // either one off while kiosk mode is on, so those were removed (see device_detail.html).
    //
    // Keyguard is also forced on unconditionally, for a very different reason: a real device got
    // stuck at boot after GrapheneOS's own auto-reboot-after-inactivity feature re-locked storage
    // (Before First Unlock/FBE) while this bit was off. LOCK_TASK_FEATURE_KEYGUARD is disabled by
    // default in lock-task mode, and that suppression is a DevicePolicyManager-level setting
    // enforced by system_server itself - it keeps applying even before the device is decrypted,
    // when this app's own process can't run at all (its components aren't resolvable pre-unlock).
    // With keyguard off and this app the exclusive enforced Home app, there was no lock screen to
    // enter a PIN into *and* no launcher available either - a total deadlock recoverable only via
    // hardware-level recovery mode. Forcing this bit on guarantees Android's own (already
    // direct-boot-aware) keyguard can always come up after any reboot, regardless of kiosk
    // config. The real tradeoff: every kiosk-mode device now also requires a PIN to resume from
    // sleep, not just after a reboot - Android doesn't expose those as separate bits.
    let lock_task_features: i64 = DEFAULT_LOCK_TASK_FEATURES;

    let mut quick_controls_mask: i64 = 0;
    if fields.contains_key("quick_control_wifi") {
        quick_controls_mask |= QUICK_CONTROL_WIFI;
    }
    if fields.contains_key("quick_control_bluetooth") {
        quick_controls_mask |= QUICK_CONTROL_BLUETOOTH;
    }
    if fields.contains_key("quick_control_brightness") {
        quick_controls_mask |= QUICK_CONTROL_BRIGHTNESS;
    }
    if fields.contains_key("quick_control_sound") {
        quick_controls_mask |= QUICK_CONTROL_SOUND;
    }

    let vpn_filter_enabled = fields.contains_key("vpn_filter_enabled");

    // The parent PIN has its own card and save since design 24 (`handlers::parent_pin`): this
    // form's SQL never touches it. A part the form didn't carry binds NULL and keeps its value.
    let result = sqlx::query(
        "UPDATE device_policy SET kiosk_desired = 1, lock_task_features = ?, \
         quick_controls_mask = COALESCE(?, quick_controls_mask), \
         vpn_filter_enabled = COALESCE(?, vpn_filter_enabled), updated_at = datetime('now') \
         WHERE device_id = ?",
    )
    .bind(lock_task_features)
    .bind(quick.then_some(quick_controls_mask))
    .bind(filter.then_some(vpn_filter_enabled))
    .bind(id)
    .execute(&state.db)
    .await;
    match result {
        Ok(done) if done.rows_affected() == 0 => {
            (axum::http::StatusCode::NOT_FOUND, "Device not found").into_response()
        }
        Ok(_) => {
            // Nudges the device to re-sync at once over its SSE connection rather than waiting
            // out the background poll - the nudge carries no data, the device just re-fetches
            // /api/devices/policy, the same dispatch path as a scheduled sync.
            let _ = state.command_notify.send(id);
            Redirect::to(&back).into_response()
        }
        Err(err) => {
            tracing::error!(device_id = id, %err, "failed to save the policy form");
            (
                axum::http::StatusCode::INTERNAL_SERVER_ERROR,
                "Couldn't save - nothing was changed. Check the server log.",
            )
                .into_response()
        }
    }
}

/// Lock Screen & PINs' Child PIN (handy step 10; design 31 step 4): set or remove the kid's PIN
/// for handy's own lock screen. A PIN must be 4-6 digits, needs an override PIN (the lock's parent code) and must not
/// be the override PIN (`kid_lock::decide_kid_pin`); a refused save writes nothing and says why.
/// The first PIN also blocks safe mode (decision after QA review: safe mode skips our lock) -
/// the parent can switch that off again in Advanced › Restrictions. 404 for an unknown device, 500 on
/// a DB error; security events `kid_pin_changed`/`kid_pin_cleared`; nudges the phone. Every
/// outcome redirects to `/devices/{id}/lock?notice=<code>#child-pin` (the flash in its group).
pub async fn update_kid_lock(
    State(state): State<AppState>,
    Path(id): Path<i64>,
    Extension(CurrentAdmin(admin)): Extension<CurrentAdmin>,
    Form(form): Form<std::collections::HashMap<String, String>>,
) -> Response {
    let current =
        match sqlx::query_as::<_, DevicePolicy>("SELECT * FROM device_policy WHERE device_id = ?")
            .bind(id)
            .fetch_optional(&state.db)
            .await
        {
            Ok(Some(policy)) => policy,
            Ok(None) => return (StatusCode::NOT_FOUND, "Device not found").into_response(),
            Err(err) => {
                tracing::error!(device_id = id, %err, "can't read the policy for the kid lock");
                return (
                    StatusCode::INTERNAL_SERVER_ERROR,
                    "Couldn't save - nothing was changed. Check the server log.",
                )
                    .into_response();
            }
        };
    let back = |code: &str| {
        Redirect::to(&format!("/devices/{id}/lock?notice={code}#child-pin")).into_response()
    };

    let (result, event, code) = if form.contains_key("clear_kid_pin") {
        let result = sqlx::query(
            "UPDATE device_policy SET kid_pin_hash = NULL, kid_pin_salt = NULL, \
             kid_pin_length = NULL, updated_at = datetime('now') WHERE device_id = ?",
        )
        .bind(id)
        .execute(&state.db)
        .await;
        (result, "kid_pin_cleared", "cleared")
    } else {
        let new_pin = form
            .get("new_kid_pin")
            .map(|p| p.trim().to_string())
            .unwrap_or_default();
        let override_hash = current.override_pin_hash.clone();
        let override_salt = current.override_pin_salt.clone();
        // Two PBKDF2 runs: off the async workers.
        let decided = tokio::task::spawn_blocking(move || {
            crate::kid_lock::decide_kid_pin(
                &new_pin,
                override_hash.as_deref(),
                override_salt.as_deref(),
            )
        })
        .await;
        let (hash, salt, length) = match decided {
            Ok(Ok(new)) => new,
            Ok(Err(refusal)) => return back(refusal.code()),
            Err(err) => {
                tracing::error!(device_id = id, %err, "kid PIN hashing failed");
                return (
                    StatusCode::INTERNAL_SERVER_ERROR,
                    "Couldn't save - nothing was changed. Check the server log.",
                )
                    .into_response();
            }
        };
        // The first kid PIN turns the safe-boot block on; later changes leave the switch alone.
        let first = current.kid_pin_hash.is_none();
        let block_safe_boot = first || current.hardening.disallow_safe_boot;
        // Only while the override PIN still exists: a parallel "remove unlock code" can't leave a
        // kid PIN without its parent code (qa-10-code #9); zero rows = refused. When the switch
        // goes on is kept for the unseen-boots advice (QA 24b Low 12).
        let result = sqlx::query(
            "UPDATE device_policy SET kid_pin_hash = ?, kid_pin_salt = ?, kid_pin_length = ?, \
             disallow_safe_boot_set_at_ms = CASE WHEN ? AND NOT disallow_safe_boot THEN ? \
             ELSE disallow_safe_boot_set_at_ms END, \
             disallow_safe_boot = ?, updated_at = datetime('now') \
             WHERE device_id = ? AND override_pin_hash IS NOT NULL",
        )
        .bind(&hash)
        .bind(&salt)
        .bind(length)
        .bind(block_safe_boot)
        .bind(chrono::Utc::now().timestamp_millis())
        .bind(block_safe_boot)
        .bind(id)
        .execute(&state.db)
        .await;
        if matches!(&result, Ok(done) if done.rows_affected() == 0) {
            return back(crate::kid_lock::KidPinRefusal::NeedsOverride.code());
        }
        let code = if first && !current.hardening.disallow_safe_boot {
            "saved_safe_boot"
        } else {
            "saved"
        };
        (result, "kid_pin_changed", code)
    };
    if let Err(err) = result {
        tracing::error!(device_id = id, %err, "failed to save the kid PIN");
        return (
            StatusCode::INTERNAL_SERVER_ERROR,
            "Couldn't save - nothing was changed. Check the server log.",
        )
            .into_response();
    }
    security::record_security_event(
        &state.db,
        event,
        Some(&admin.username),
        None,
        Some(&format!("device {id}")),
    )
    .await;
    let _ = state.command_notify.send(id);
    back(code)
}

/// Advanced › Restrictions (the "Phone hardening" card until design 31 step 3): one
/// auto-submitting form with every switch, so a missing checkbox really means "off" (same pattern
/// as the calls switches). 404 for an unknown device, 500 (nothing written) on a DB error; nudges
/// the phone like every other policy change.
pub async fn update_hardening(
    State(state): State<AppState>,
    Path(id): Path<i64>,
    Form(form): Form<std::collections::HashMap<String, String>>,
) -> axum::response::Response {
    let on = |key: &str| form.contains_key(key);
    // When "Block safe mode" goes on (QA 24b Low 12: the unseen-boots advice); off clears it.
    // SET reads the old values, so `disallow_safe_boot` in the CASE is the switch before the save.
    let result = sqlx::query(
        "UPDATE device_policy SET \
         disallow_safe_boot_set_at_ms = CASE WHEN NOT ? THEN NULL \
         WHEN NOT disallow_safe_boot THEN ? ELSE disallow_safe_boot_set_at_ms END, \
         disallow_factory_reset = ?, disallow_add_user = ?, \
         disallow_modify_accounts = ?, disallow_config_vpn = ?, disallow_usb_file_transfer = ?, \
         disallow_debugging_features = ?, disallow_safe_boot = ?, lock_location = ?, \
         disallow_airplane_mode = ?, disallow_config_locale = ?, updated_at = datetime('now') \
         WHERE device_id = ?",
    )
    .bind(on("disallow_safe_boot"))
    .bind(chrono::Utc::now().timestamp_millis())
    .bind(on("disallow_factory_reset"))
    .bind(on("disallow_add_user"))
    .bind(on("disallow_modify_accounts"))
    .bind(on("disallow_config_vpn"))
    .bind(on("disallow_usb_file_transfer"))
    .bind(on("disallow_debugging_features"))
    .bind(on("disallow_safe_boot"))
    .bind(on("lock_location"))
    .bind(on("disallow_airplane_mode"))
    .bind(on("disallow_config_locale"))
    .bind(id)
    .execute(&state.db)
    .await;
    match result {
        Ok(done) if done.rows_affected() == 0 => {
            (axum::http::StatusCode::NOT_FOUND, "Device not found").into_response()
        }
        Ok(_) => {
            let _ = state.command_notify.send(id);
            // Its own page; no fragment: Restrictions is the first group, and scroll-restore.js
            // keeps the exact position (design 31 §5.1).
            Redirect::to(&format!("/devices/{id}/advanced")).into_response()
        }
        Err(err) => {
            tracing::error!(device_id = id, %err, "failed to save hardening switches");
            (
                axum::http::StatusCode::INTERNAL_SERVER_ERROR,
                "Couldn't save - nothing was changed. Check the server log.",
            )
                .into_response()
        }
    }
}

/// Home Screen's language ("system", "nb", "en") and apps per row (3 or 4): one auto-saving form
/// (design 31 §5.6); an unknown value is a 400 and nothing is written. Back to
/// `/devices/{id}/home` without a fragment (scroll-restore keeps the place).
pub async fn update_launcher_ui(
    State(state): State<AppState>,
    Path(id): Path<i64>,
    Form(form): Form<std::collections::HashMap<String, String>>,
) -> axum::response::Response {
    let language = form.get("language").map(String::as_str).unwrap_or("system");
    let columns = form
        .get("home_columns")
        .and_then(|c| c.parse::<i64>().ok())
        .unwrap_or(3);
    if !crate::models::LAUNCHER_LANGUAGES.contains(&language) || !(columns == 3 || columns == 4) {
        return (
            axum::http::StatusCode::BAD_REQUEST,
            "Unknown language or column count",
        )
            .into_response();
    }
    let result = sqlx::query(
        "UPDATE device_policy SET launcher_language = ?, home_columns = ?, \
         updated_at = datetime('now') WHERE device_id = ?",
    )
    .bind(language)
    .bind(columns)
    .bind(id)
    .execute(&state.db)
    .await;
    match result {
        Ok(done) if done.rows_affected() == 0 => {
            (axum::http::StatusCode::NOT_FOUND, "Device not found").into_response()
        }
        Ok(_) => {
            let _ = state.command_notify.send(id);
            Redirect::to(&format!("/devices/{id}/home")).into_response()
        }
        Err(err) => {
            tracing::error!(device_id = id, %err, "failed to save launcher settings");
            (
                axum::http::StatusCode::INTERNAL_SERVER_ERROR,
                "Couldn't save - nothing was changed. Check the server log.",
            )
                .into_response()
        }
    }
}

/// Lock Screen & PINs' "Turn off after" (migrations/0032; design 31 step 4): one auto-saving
/// select. Only a value from `SCREEN_TIMEOUTS` is accepted; anything else is a 400 and nothing is
/// written. Back to `/devices/{id}/lock` without a fragment (scroll-restore keeps the place).
pub async fn update_screen_timeout(
    State(state): State<AppState>,
    Path(id): Path<i64>,
    Form(form): Form<std::collections::HashMap<String, String>>,
) -> axum::response::Response {
    let Some(seconds) = form
        .get("screen_timeout_seconds")
        .and_then(|s| s.parse::<i64>().ok())
        .filter(|s| crate::models::SCREEN_TIMEOUTS.iter().any(|(v, _)| v == s))
    else {
        return (
            axum::http::StatusCode::BAD_REQUEST,
            "Unknown screen timeout",
        )
            .into_response();
    };
    let result = sqlx::query(
        "UPDATE device_policy SET screen_timeout_seconds = ?, updated_at = datetime('now') \
         WHERE device_id = ?",
    )
    .bind(seconds)
    .bind(id)
    .execute(&state.db)
    .await;
    match result {
        Ok(done) if done.rows_affected() == 0 => {
            (axum::http::StatusCode::NOT_FOUND, "Device not found").into_response()
        }
        Ok(_) => {
            let _ = state.command_notify.send(id);
            Redirect::to(&format!("/devices/{id}/lock")).into_response()
        }
        Err(err) => {
            tracing::error!(device_id = id, %err, "failed to save the screen timeout");
            (
                axum::http::StatusCode::INTERNAL_SERVER_ERROR,
                "Couldn't save - nothing was changed. Check the server log.",
            )
                .into_response()
        }
    }
}

pub async fn delete_device(
    State(state): State<AppState>,
    Path(id): Path<i64>,
    Extension(_admin): Extension<CurrentAdmin>,
) -> impl IntoResponse {
    sqlx::query("DELETE FROM devices WHERE id = ?")
        .bind(id)
        .execute(&state.db)
        .await
        .ok();
    // Its token stops working and its open command streams end (design 22 QA d: auth is checked
    // only when a stream opens).
    state.tokens.refresh(&state.db, id, None).await;
    state.command_streams.close_device(id);
    state.limits.forget_device(id);
    // device_contacts rows went with the device (ON DELETE CASCADE); drop address-book entries
    // no other device has.
    sqlx::query(
        "DELETE FROM contacts WHERE NOT EXISTS \
         (SELECT 1 FROM device_contacts WHERE contact_id = contacts.id)",
    )
    .execute(&state.db)
    .await
    .ok();
    crate::photos::prune(&state).await;

    Redirect::to("/devices")
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_avatar_letter_is_one_letter() {
        assert_eq!(initial("anna"), "A");
        assert_eq!(initial("  émil"), "É");
        // "ß" upper-cases to two letters: kept as it is.
        assert_eq!(initial("ßara"), "ß");
        // An emoji or a flag is skipped, never cut in half.
        assert_eq!(initial("🇳🇴 Nora"), "N");
        assert_eq!(initial("👩‍👧 Ella"), "E");
        assert_eq!(initial("2 phones"), "2");
        assert_eq!(initial("🦄"), "?");
        assert_eq!(initial(""), "?");
    }
}
