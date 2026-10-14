use askama::Template;
use axum::extract::{Path, Query, State};
use axum::http::StatusCode;
use axum::response::{Html, IntoResponse, Redirect, Response};
use axum::{Extension, Form};
use serde::Deserialize;

use crate::AppState;
use crate::models::{Device, DevicePolicy, DeviceStatus, InstalledApp, TrackedApp};
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

/// The lock badge and today's screen time from the phone's last report (design 31 §3.10, M4), for
/// the Devices list: one small read, and the rules only while one locks the phone. A failed read
/// is logged and shows neither.
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
    let Some((reported_at, report)) = latest
        .as_ref()
        .and_then(|(at, reason, json)| time_report(at, reason, json.as_deref()))
    else {
        return (None, None);
    };
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
            .unwrap_or_default();
    }
    badge_and_screen(
        &report,
        reported_at,
        &rules,
        connection,
        calls_enforced,
        zone,
        now,
    )
}

/// A report's time and its typed `time_state` (or, from an older launcher, its lock reason).
pub(crate) fn time_report(
    reported_at: &str,
    lock_reason: &str,
    time_state_json: Option<&str>,
) -> Option<(chrono::DateTime<chrono::Utc>, crate::time_state::TimeReport)> {
    use crate::time_state::TimeReport;
    let reported_at = crate::display_time::parse_db_utc(reported_at)?;
    let report = time_state_json
        .and_then(TimeReport::parse)
        .unwrap_or_else(|| TimeReport::from_lock_reason(lock_reason));
    Some((reported_at, report))
}

/// The badge and the bar from a report and the phone's effective rules (the Devices list's and the
/// summary's header).
pub(crate) fn badge_and_screen(
    report: &crate::time_state::TimeReport,
    reported_at: chrono::DateTime<chrono::Utc>,
    rules: &[crate::time_rules::TimeRuleRow],
    connection: &crate::streams::ConnectionState,
    calls_enforced: bool,
    zone: &crate::display_time::Zone,
    now: chrono::DateTime<chrono::Utc>,
) -> (
    Option<crate::time_state::LockBadge>,
    Option<crate::time_state::ScreenTime>,
) {
    use crate::time_state::{BadgeContext, lock_badge, screen_time};
    (
        lock_badge(
            report,
            &BadgeContext {
                rules: &crate::time_state::rule_days(rules),
                reported_at,
                connection,
                calls_enforced,
            },
            zone,
            now,
        ),
        screen_time(report, zone, now),
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

/// A phone's name is at most this many characters (it fills a row and a page title).
pub(crate) const MAX_DEVICE_NAME_CHARS: usize = 40;

/// Add Phone, step 1 of 3 (design 31 §3.7): the name.
#[derive(Template)]
#[template(path = "device_add.html")]
struct DeviceAddTemplate {
    title: String,
    /// What was typed, after a refusal.
    name: String,
    error: Option<&'static str>,
    max_name: usize,
}

fn device_add_page(status: StatusCode, name: String, error: Option<&'static str>) -> Response {
    let page = DeviceAddTemplate {
        title: "Add Phone".to_string(),
        name,
        error,
        max_name: MAX_DEVICE_NAME_CHARS,
    };
    match page.render() {
        Ok(html) => (status, Html(html)).into_response(),
        Err(err) => {
            tracing::error!(%err, "couldn't render Add Phone");
            StatusCode::INTERNAL_SERVER_ERROR.into_response()
        }
    }
}

pub async fn new_device_form() -> Response {
    device_add_page(StatusCode::OK, String::new(), None)
}

#[derive(Deserialize)]
pub struct CreateDeviceForm {
    #[serde(default)]
    pub(crate) name: String,
}

/// "Next" on Add Phone: the phone is made (no code yet) and the flow goes on to Connect
/// (`/devices/{id}/provision`: the QR code or a setup code). An empty or too long name renders
/// the form again (400) with what was typed and the reason under the field.
pub async fn create_device(
    State(state): State<AppState>,
    Form(form): Form<CreateDeviceForm>,
) -> axum::response::Response {
    let name = form.name.trim();
    let refusal = if name.is_empty() {
        Some("Give the phone a name.")
    } else if name.chars().count() > MAX_DEVICE_NAME_CHARS {
        Some("Use at most 40 characters.")
    } else {
        None
    };
    if let Some(why) = refusal {
        let typed: String = name.chars().take(MAX_DEVICE_NAME_CHARS * 4).collect();
        return device_add_page(StatusCode::BAD_REQUEST, typed, Some(why));
    }
    match insert_device_with_policy(&state, name).await {
        Ok(id) => Redirect::to(&format!("/devices/{id}/provision")).into_response(),
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
/// No enrollment code yet: Connect makes one ("New Setup Code", shown once) or the setup QR does
/// (design 22 §3.1) - a code made here could never be shown again.
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
    /// When it stops working (local, `Zone::until`).
    until: String,
    /// The phone is enrolled: using the code cuts the enrolled phone off (recovery).
    enrolled: bool,
    /// Until when typed codes are paused (local), when they are (too many wrong ones this hour).
    paused_until: Option<String>,
}

/// "New Setup Code" on Connect and Advanced › Connection (design 22 §3.1): a typed code (8
/// characters, 15 minutes), shown on this response only - only its hash is stored, and the page is
/// `no-store`. Replaces any live code. Also the recovery path for an enrolled phone: using the
/// code gives it a new token and retires the old one. While typed codes are paused the code is
/// still made, and the page says from when it works (a setup QR code works at once).
pub async fn regenerate_code(
    State(state): State<AppState>,
    Path(id): Path<i64>,
    Extension(CurrentAdmin(admin)): Extension<CurrentAdmin>,
) -> Response {
    let device = match sqlx::query_as::<_, Device>("SELECT * FROM devices WHERE id = ?")
        .bind(id)
        .fetch_optional(&state.db)
        .await
    {
        Ok(Some(device)) => device,
        Ok(None) => return StatusCode::NOT_FOUND.into_response(),
        Err(err) => {
            tracing::error!(device_id = id, %err, "couldn't read the phone for a setup code");
            return StatusCode::INTERNAL_SERVER_ERROR.into_response();
        }
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
    let zone = crate::display_time::Zone::load(&state.db).await;
    let now = chrono::Utc::now();
    let page = EnrollCodeTemplate {
        title: format!("Setup Code for {}", device.name),
        code: crate::enrollment::display(&code),
        until: zone.until_db(&expires_at, now),
        enrolled: device.enrolled_at.is_some(),
        paused_until: state
            .enrollment
            .typed_paused_until(now)
            .map(|until| zone.until(until, now)),
        device,
    };
    match page.render() {
        Ok(html) => (
            [(axum::http::header::CACHE_CONTROL, "no-store")],
            Html(html),
        )
            .into_response(),
        Err(err) => {
            tracing::error!(%err, "couldn't render the setup code page");
            StatusCode::INTERNAL_SERVER_ERROR.into_response()
        }
    }
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
    /// The header (design 31 §3.2 item 1): the avatar, the connection word and "seen 3 min ago"
    /// with the exact time, what the state means for changes, the lock badge and today's bar.
    avatar: &'static str,
    initial: String,
    connection: crate::streams::ConnectionState,
    seen: Option<(String, String)>,
    /// "changes may be delayed" (Away), "changes wait until it's back" (Offline).
    seen_tail: Option<&'static str>,
    lock: Option<String>,
    screen_time: Option<crate::time_state::ScreenTime>,
    /// The Attention rows, the same list the Devices list counts.
    attention: Vec<crate::attention::AttentionItem>,
    /// Design 24b: the quick actions Lock again and Unlock for 2 h…, the foreign-app warning, the
    /// blocked apps with Allow these apps…, the last relock's findings, the commands' answers.
    relock: crate::handlers::window_relock::RelockCard,
    /// Pause a rule…'s rules (the phone's effective ones); empty: no tile.
    pause_rules: Vec<(i64, String)>,
    /// A lift form's flash (`?done=`): the summary group ("quick", "today"), text, refused.
    done: Option<(&'static str, &'static str, bool)>,
    /// The last report has the phone's restrictions paused: said at the top of Quick actions,
    /// where the Attention row lands (qa-31-s10-code M1).
    paused: bool,
    /// Managed without a Parent PIN: a phone not set up says so (it has no Attention rows,
    /// qa-31-s10-code L1).
    no_parent_pin: bool,
    /// Today: the lifts made today and the pauses still running, with End now.
    lifts: Vec<crate::handlers::lifts::LiftView>,
    /// Today: the Parent PIN's notices from the last 24 hours (design 24 §5).
    pin_notices: Vec<crate::parent_pin::Notice>,
    /// The not-set-up variant's Connect This Phone group (design 22 §3.1).
    access: AccessCard,
    /// The Sections rows' values.
    calls_value: String,
    apps_value: String,
    music_value: String,
    /// Device › Music or Device › Time shows a warning: the value in orange ("Needs attention").
    music_attention: bool,
    time_value: &'static str,
    time_attention: bool,
    lock_value: &'static str,
    home_value: String,
    filter_value: &'static str,
    about_value: Option<String>,
}

impl DeviceDetailTemplate {
    /// The flash of summary group [group]: a command's (`?notice=`) or a lift's (`?done=`), and
    /// whether it is a refusal.
    fn flash(&self, group: &str) -> Option<(&'static str, bool)> {
        self.relock
            .flash_in(group)
            .map(|text| (text, false))
            .or(self
                .done
                .filter(|(g, _, _)| *g == group)
                .map(|(_, text, refused)| (text, refused)))
    }

    /// Access removed: the header says so and links to Advanced › Connection (L2).
    fn disconnected(&self) -> bool {
        matches!(
            self.connection,
            crate::streams::ConnectionState::Disconnected
        )
    }

    /// Today shows: something happened, or its flash lands there.
    fn show_today(&self) -> bool {
        !self.lifts.is_empty()
            || !self.relock.answers.is_empty()
            || !self.pin_notices.is_empty()
            || self.flash("today").is_some()
    }
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
            // Its group (design 31 §5.1.4): without JS the answer lands on the switch, with JS
            // scroll-restore.js keeps the exact position.
            Redirect::to(&format!("/devices/{id}/advanced#app-screens")).into_response()
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
/// `action` "reset" = the phone's row goes (it follows the catalog default, else Vibb style;
/// design 34 P1 - an older page's "own" and "catalog" do the same), else save the fields (Vibb
/// style with no name on a phone with a catalog default keeps an empty row: Vibb style there). A
/// choice equal to the catalog default, or nothing without a default, keeps no row. Refused input: 400,
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
    let reset = matches!(field("action"), "reset" | "own" | "catalog");
    let values = if reset {
        DisplayValues::empty()
    } else {
        match crate::app_display::validate(field("label"), field("icon"), field("color")) {
            Ok(values) => values,
            Err(error) => return refuse(error).await,
        }
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
    let keep_row = !reset
        && match &default {
            Some(default) => *default != values,
            None => !values.is_empty(),
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
/// its group, Advanced's `#updates` (a save never jumps, §5.1.4).
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
            // Its group (design 31 §5.1.4; the browser pass of 2026-10-14: without one a save
            // without JS landed at the top, the switches out of view).
            Redirect::to(&format!("/devices/{id}/advanced#updates")).into_response()
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

/// The phone's summary (design 31 §3.2, step 10). The phone, its settings and its last report are a
/// 500 on a DB error, as are the lifts (Today would read as "nothing today"); a Sections row whose
/// read fails says "Couldn't read" (logged).
async fn render_device(
    state: &AppState,
    id: i64,
    query: &std::collections::HashMap<String, String>,
) -> Response {
    use crate::handlers::device_pages::{load_device, load_latest, load_policy};
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
        Err(err) => {
            tracing::error!(device_id = id, %err, "can't read the last report");
            return (
                StatusCode::INTERNAL_SERVER_ERROR,
                "Couldn't read this phone - check the server log.",
            )
                .into_response();
        }
    };
    let zone = crate::display_time::Zone::load(&state.db).await;
    let now = chrono::Utc::now();
    let lifts =
        match crate::handlers::lifts::today(&state.db, id, latest.as_ref(), &zone, now).await {
            Ok(lifts) => lifts,
            Err(err) => {
                tracing::error!(device_id = id, %err, "can't read the lifts");
                return (
                    StatusCode::INTERNAL_SERVER_ERROR,
                    "Couldn't read this phone - check the server log.",
                )
                    .into_response();
            }
        };

    // The Apps row (design 31 step 6): the switches on under Device › Apps, the launcher not
    // counted - the number the Apps tab shows.
    let rows = app_rows(state, id, &policy, latest.as_ref())
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

    // The Calls & Contacts row: "8 contacts" while calls are managed; a failed read says so,
    // never "0 contacts". The call report's findings feed Attention; a report that can't be read
    // says so on the row, so an empty Attention never reads as "all good" (qa-31-s8-code N4).
    let contact_count: Option<i64> = if policy.calls_managed {
        sqlx::query_scalar("SELECT COUNT(*) FROM device_contacts WHERE device_id = ?")
            .bind(id)
            .fetch_one(&state.db)
            .await
            .map_err(|err| tracing::error!(device_id = id, %err, "can't count the contacts"))
            .ok()
    } else {
        None
    };
    let call_problems = crate::handlers::calls::call_report(state, &policy)
        .await
        .map(|report| report.problems())
        .map_err(|err| tracing::error!(device_id = id, %err, "failed to read the call report"))
        .ok();
    let calls_value = match (policy.calls_managed, contact_count) {
        _ if call_problems.is_none() => "Couldn't read".to_string(),
        (false, _) => "Not managed".to_string(),
        (true, None) => "Couldn't read".to_string(),
        (true, Some(1)) => "1 contact".to_string(),
        (true, Some(n)) => format!("{n} contacts"),
    };
    let call_problems = call_problems.unwrap_or_default();

    // The phone's effective rules: the Time row, Pause a rule…, the badge, and whether a calls-off
    // rule applies while calls aren't managed (an Attention row).
    let time_rules =
        crate::time_rules::effective_rule_rows(&state.db, id, policy.custom_schedule_enabled)
            .await
            .map_err(|err| tracing::error!(device_id = id, %err, "can't read the phone's rules"));
    let incoming_calls_unblocked = !policy.calls_managed
        && time_rules
            .as_ref()
            .is_ok_and(|rules| rules.iter().any(|r| !r.calls_allowed));
    let parent_pin = crate::handlers::parent_pin::card(
        &state.db,
        &policy,
        latest.as_ref(),
        None,
        &zone,
        now,
        false,
    )
    .await;
    let relock = crate::handlers::window_relock::card(
        &state.db,
        id,
        latest.as_ref(),
        query.get("notice").map(String::as_str),
        &zone,
        now,
        // Whether each blocked app is allowed under Apps (QA of cmd-expiry, Low 3).
        rows.as_ref()
            .filter(|rows| policy.allowlist_json.is_some() && !rows.allowlist_corrupt)
            .map(|rows| &rows.allowed),
    )
    .await;
    // Without the catalog's pins or the answers it would offer an imposter or an answered Allow
    // (qa-31-s10-code L4).
    let mut relock = match relock {
        Ok(relock) => relock,
        Err(err) => {
            tracing::error!(device_id = id, %err, "can't read the relock parts");
            return (
                StatusCode::INTERNAL_SERVER_ERROR,
                "Couldn't read this phone - check the server log.",
            )
                .into_response();
        }
    };
    // The Music row (design 31 step 7): the entries switched on for this phone.
    let music_ticked = crate::handlers::music::ticked_entries(&state.db, id).await;
    if let Err(err) = &music_ticked {
        tracing::error!(device_id = id, %err, "can't read the phone's music");
    }

    // The Spotify account it plays as (qa-26c-merge2-code L3): the badge's rows read it too.
    let spotify_account =
        match crate::handlers::music::spotify_account_warnings(state, &policy).await {
            Ok(warnings) => warnings,
            Err(err) => {
                tracing::error!(device_id = id, %err, "can't read the phone's Spotify account");
                Default::default()
            }
        };

    // The same rows the Devices list counts, from the parts built above (not read twice).
    let connection = connection_of(state, &device, now);
    let facts = crate::attention::facts_of(
        &crate::attention::Sources {
            device: &device,
            policy: &policy,
            latest: latest.as_ref(),
            relock: &relock,
            call_problems: &call_problems,
            incoming_calls_not_blocked: incoming_calls_unblocked,
            connection,
            music_ticked: music_ticked.as_deref().unwrap_or(&[]),
            spotify_account,
        },
        now.timestamp_millis(),
    );
    let attention = crate::attention::items(id, &facts);
    // "Needs attention" while Device › Music shows a warning (qa-31-s7-code M1).
    let (music_value, music_attention) = match &music_ticked {
        Err(_) => ("Couldn't read".to_string(), false),
        Ok(_) if facts.music.any() => ("Needs attention".to_string(), true),
        Ok(ticked) if ticked.is_empty() => ("None".to_string(), false),
        Ok(ticked) => (format!("{} in library", ticked.len()), false),
    };
    // "Needs attention" while Device › Time shows a warning, as the Music row does.
    let (time_value, time_attention) = match &time_rules {
        Err(_) => ("Couldn't read", false),
        Ok(_) if facts.ledger_unreadable || facts.launcher_without_rules => {
            ("Needs attention", true)
        }
        // A rule or the own limit in force that doesn't read: the phone gets a 500 for its
        // settings until it is saved again (qa-31-s9-code N5).
        Ok(rules)
            if rules.iter().any(|r| r.to_policy().is_err())
                || (policy.custom_schedule_enabled
                    && crate::time_rules::parse_budget(&policy.daily_budget_json).is_err()) =>
        {
            ("Needs attention", true)
        }
        Ok(_) if policy.custom_schedule_enabled => ("Own rules", false),
        Ok(_) => ("Everyone's rules", false),
    };

    // The header (§3.2 item 1, §3.10): "Online · seen 3 min ago", the lock only while a rule
    // locks, today's bar only for today's report.
    use crate::streams::ConnectionState;
    let seen = connection
        .last_contact()
        .map(|at| (zone.relative(at, now), zone.exact(at)));
    let seen_tail = match connection {
        ConnectionState::Away { .. } => Some("changes may be delayed"),
        ConnectionState::Offline { .. } => Some("changes wait until it's back"),
        _ => None,
    };
    let (lock, screen_time) = match latest
        .as_ref()
        .and_then(|s| time_report(&s.reported_at, &s.lock_reason, s.time_state_json.as_deref()))
    {
        Some((reported_at, report)) if device.enrolled_at.is_some() => {
            let (badge, screen) = badge_and_screen(
                &report,
                reported_at,
                time_rules.as_deref().unwrap_or(&[]),
                &connection,
                facts.calls_enforced,
                &zone,
                now,
            );
            let lock = badge.map(|badge| match &seen {
                Some((seen, _)) => badge.text_beside(seen),
                None => badge.text(),
            });
            (lock, screen)
        }
        _ => (None, None),
    };

    // Today keeps today's answers and any still waiting (qa-31-s10-code N2); older ones are on
    // the Locate tab's Recent.
    relock.answers.retain(|a| {
        a.state == crate::window_relock::AnswerState::Waiting || zone.is_today(a.at, now)
    });
    let paused = latest.as_ref().is_some_and(|s| s.restrictions_paused);
    let done = query
        .get("done")
        .and_then(|code| crate::handlers::lifts::done_flash(code))
        .map(|(group, text, refused)| {
            (crate::handlers::lifts::summary_group(group), text, refused)
        });
    Html(
        DeviceDetailTemplate {
            title: device.name.clone(),
            avatar: avatar_class(&device),
            initial: initial(&device.name),
            connection,
            seen,
            seen_tail,
            lock,
            screen_time,
            attention,
            pause_rules: time_rules
                .as_deref()
                .unwrap_or(&[])
                .iter()
                .map(|r| (r.id, r.name.clone()))
                .collect(),
            done,
            lifts,
            paused,
            no_parent_pin: managed_without_pin(&policy),
            pin_notices: parent_pin.notices,
            relock,
            access: access_card(
                state,
                &device,
                query.get("notice").map(String::as_str),
                &zone,
                now,
            ),
            about_value: latest
                .as_ref()
                .and_then(|s| s.app_version.as_deref())
                .filter(|v| !v.is_empty())
                .map(|v| format!("Vibb {}", crate::handlers::device_pages::short_version(v))),
            calls_value,
            apps_value,
            music_value,
            music_attention,
            time_value,
            time_attention,
            lock_value: if crate::kid_lock::policy_kid_lock(&policy).is_some() {
                "PIN on"
            } else {
                "PIN off"
            },
            home_value: format!("{} per row", policy.home_columns),
            filter_value: if policy.vpn_filter_enabled {
                "On"
            } else {
                "Off"
            },
            device,
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

/// One app's switch on Device › Apps (and the music app's on Device › Music): its own
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
/// to Device › Music (`/devices/{id}/music#music-app`, its group; a refusal
/// to `?music_notice=<code>#music-app`, the reason in that group), anything else to the app's row
/// on Device › Apps (`#app-<package>`): the row moves between Allowed and Not allowed, and the
/// fragment finds it again, with or without JS.
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
    // Device › Music's switch (`back=music`, or an older page's `anchor=music`) returns there: the
    // page on a save, its group with the reason on a refusal (design 31 step 7).
    let music = form
        .get("back")
        .or_else(|| form.get("anchor"))
        .map(String::as_str)
        == Some("music");
    let refused = |code: &str, anchor: &str| {
        Redirect::to(&if music {
            format!("/devices/{id}/music?music_notice={code}#music-app")
        } else {
            format!("{page}?notice={code}&app={anchor}#{anchor}")
        })
        .into_response()
    };
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
                    let to = match (music, checked) {
                        (true, _) => format!("/devices/{id}/music#music-app"),
                        (false, true) => format!("{page}#not-allowed"),
                        (false, false) => format!("{page}#allowed"),
                    };
                    return Redirect::to(&to).into_response();
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
        return refused("not_an_app", &anchor);
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
            return refused("from_play", &anchor);
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
    if music {
        return Redirect::to(&format!("/devices/{id}/music#music-app")).into_response();
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
/// writes only the quick controls and returns to their group, `/devices/{id}/home#quick-controls`,
/// `section=filter` only the filter and returns to `/devices/{id}/filter#filter` (§5.1.4). A
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
        Some("quick") => (true, false, format!("/devices/{id}/home#quick-controls")),
        Some("filter") => (false, true, format!("/devices/{id}/filter#filter")),
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
            // Its group (design 31 §5.1.4); scroll-restore.js keeps the exact position.
            Redirect::to(&format!("/devices/{id}/advanced#restrictions")).into_response()
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
/// (design 31 §5.6); an unknown value is a 400 and nothing is written. Back to its group,
/// `/devices/{id}/home#layout` (§5.1.4).
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
            Redirect::to(&format!("/devices/{id}/home#layout")).into_response()
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
/// written. Back to its group, `/devices/{id}/lock#screen` (§5.1.4).
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
            Redirect::to(&format!("/devices/{id}/lock#screen")).into_response()
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
