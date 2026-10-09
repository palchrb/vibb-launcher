use askama::Template;
use axum::extract::{Path, Query, State};
use axum::http::StatusCode;
use axum::response::{Html, IntoResponse, Redirect, Response};
use axum::{Extension, Form};
use serde::Deserialize;

use crate::AppState;
use crate::models::{Device, DevicePolicy, DeviceStatus, Hardening, InstalledApp, TrackedApp};
use crate::security::{self, CurrentAdmin};

/// How long a freshly-generated enrollment code stays valid before it must
/// be regenerated - long enough to walk from the computer to the phone and
/// type it in, short enough that a code shown once on screen isn't a
/// standing credential.
const ENROLLMENT_CODE_MINUTES: i64 = 30;

struct DeviceListRow {
    id: i64,
    name: String,
    status_text: String,
}

#[derive(Template)]
#[template(path = "devices_list.html")]
struct DevicesListTemplate {
    title: String,
    devices: Vec<DeviceListRow>,
}

pub async fn list_devices(State(state): State<AppState>) -> impl IntoResponse {
    let devices = sqlx::query_as::<_, Device>("SELECT * FROM devices ORDER BY name")
        .fetch_all(&state.db)
        .await
        .unwrap_or_default();

    let rows = devices
        .into_iter()
        .map(|d| {
            let status_text = if d.enrolled_at.is_none() {
                "Not enrolled yet".to_string()
            } else {
                match &d.last_seen_at {
                    Some(t) => format!("Last seen {t}"),
                    None => "Enrolled, not seen yet".to_string(),
                }
            };
            DeviceListRow {
                id: d.id,
                name: d.name,
                status_text,
            }
        })
        .collect();

    Html(
        DevicesListTemplate {
            title: "Devices".to_string(),
            devices: rows,
        }
        .render()
        .unwrap(),
    )
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
async fn insert_device_with_policy(state: &AppState, name: &str) -> Result<i64, sqlx::Error> {
    let code = security::generate_enrollment_code();
    let mut tx = state.db.begin().await?;
    let id: i64 = sqlx::query_scalar(
        "INSERT INTO devices (name, enrollment_code, enrollment_code_expires_at) \
         VALUES (?, ?, datetime('now', ?)) RETURNING id",
    )
    .bind(name)
    .bind(&code)
    .bind(format!("+{ENROLLMENT_CODE_MINUTES} minutes"))
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

pub async fn regenerate_code(
    State(state): State<AppState>,
    Path(id): Path<i64>,
) -> impl IntoResponse {
    let code = security::generate_enrollment_code();
    sqlx::query(
        "UPDATE devices SET enrollment_code = ?, \
         enrollment_code_expires_at = datetime('now', ?) WHERE id = ?",
    )
    .bind(&code)
    .bind(format!("+{ENROLLMENT_CODE_MINUTES} minutes"))
    .bind(id)
    .execute(&state.db)
    .await
    .ok();

    Redirect::to(&format!("/devices/{id}"))
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
struct UnifiedAppRow {
    package_name: String,
    label: String,
    tracked_app_id: Option<i64>,
    is_launcher: bool,
    checked: bool,
    /// Precomputed display text rather than a template-side call to `status.label()` - lets a
    /// `NotInstalled` row show live download progress ("Installing 42%") instead of the plain
    /// static label when a fresh `device_install_progress` row exists for it - see `view_device`.
    status_label: String,
    /// True exactly when `status_label` carries a live percentage - drives device_detail.html's
    /// self-polling reload (there's no push mechanism to this page, so it has to ask again) rather
    /// than the template trying to parse `status_label`'s text back apart.
    is_installing: bool,
    /// True when the device reported a failed install attempt for this app that hasn't cleared yet
    /// (see `device_install_progress.failed`) - a separate flag from `is_installing` so
    /// device_detail.html can style it distinctly rather than parsing `status_label`'s text.
    install_failed: bool,
    /// Precomputed rather than compared in the template (`status == AppRowStatus::NotInstalled`) -
    /// flags a catalog app that's checked but has nothing to actually push yet (no GitHub release
    /// synced, or a manual-upload app nobody's uploaded a build to yet). Selecting it used to
    /// silently do nothing until a release showed up, with no indication why.
    show_no_release_hint: bool,
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
const QUICK_CONTROL_WIFI: i64 = 1;
const QUICK_CONTROL_BLUETOOTH: i64 = 2;
const QUICK_CONTROL_BRIGHTNESS: i64 = 4;
/// The sound row (design 18): Sound / Silent (vibrate). It only shows the row - the volume keys
/// still change the sound. A launcher before design 18 ignores the bit.
const QUICK_CONTROL_SOUND: i64 = 8;
/// A new device's `quick_controls_mask`: every switch on (`insert_device_with_policy`).
pub(crate) const DEFAULT_QUICK_CONTROLS: i64 =
    QUICK_CONTROL_WIFI | QUICK_CONTROL_BLUETOOTH | QUICK_CONTROL_BRIGHTNESS | QUICK_CONTROL_SOUND;

#[derive(Template)]
#[template(path = "device_detail.html")]
struct DeviceDetailTemplate {
    title: String,
    device: Device,
    apps: Vec<UnifiedAppRow>,
    /// Precomputed rather than an `{% if %}` expression in the template - Askama's expression
    /// grammar doesn't support closures, so `apps.iter().any(|a| a.is_installing)` can't be
    /// written directly there.
    any_app_installing: bool,
    pin_configured: bool,
    offline_override_used: bool,
    /// The last status report's `policy_state` when it isn't "ok" - the phone isn't applying
    /// the server's current policy (see migrations/0021_device_status_policy_state.sql).
    policy_problem: Option<String>,
    restrictions_paused: bool,
    vpn_filter_enabled: bool,
    quick_control_wifi: bool,
    quick_control_bluetooth: bool,
    quick_control_brightness: bool,
    quick_control_sound: bool,
    /// Status card (design 18): the phone's sound and Do Not Disturb at the last sync.
    sound_lines: Vec<String>,
    latest_status: Option<DeviceStatus>,
    /// One line for the "Calls & SMS" card, e.g. "Managed - 4 contacts".
    calls_summary: String,
    /// `calls::call_warnings` - emergency calls, a launcher that can't enforce calls, roles.
    call_warnings: Vec<String>,
    /// The stored allowlist isn't valid JSON: the phone gets a 500 for its policy (and keeps its
    /// cached one), and the app checkboxes below show nothing as allowed (QA step 1 #11).
    allowlist_corrupt: bool,
    /// The "Phone hardening" switches (migrations/0023_hardening.sql).
    hardening: Hardening,
    /// Managed (an allowlist, or calls managed) but no override PIN: the launcher's Settings
    /// can't be opened on the phone, there is no offline override, and with USB debugging
    /// blocked a lost server means a reset (QA step 4 #4).
    managed_without_pin: bool,
    /// Launcher card (migrations/0024): language and home-grid columns.
    launcher_language: String,
    home_columns: i64,
    /// The wallpaper card: every wallpaper, ticked when this phone may use it.
    wallpapers: Vec<crate::handlers::wallpapers::DeviceWallpaperChoice>,
    /// The last status report says the launcher's notification listener has no access, so the
    /// home screen shows no unread badges on apps.
    badges_without_access: bool,
    /// "Time rules" card (handy step 6).
    time: TimeCard,
    /// "Play and kiosk" card (handy step 7; "Push and Play" until design 19).
    play: PlayCard,
    /// "Screen lock" card (handy step 10): the kid's PIN, the phone's lock state, warnings.
    lock: crate::kid_lock::LockCard,
    /// "Launcher updates and notifications" card (handy step 11): the update fence and the
    /// notification auto-cancel switches, and what the phone reports about them.
    escapes: crate::kiosk_escapes::EscapesCard,
    /// One-shot message after a save (`?notice=`), see `kid_lock::flash_text`.
    notice: Option<&'static str>,
    /// "Screen timeout" card (migrations/0032): the choice, every option, and what the phone
    /// last reported it applied.
    screen_timeout_seconds: i64,
    screen_timeout_options: Vec<(i64, &'static str)>,
    screen_timeout_applied: Option<String>,
    /// "Phone hardening" card: the latest report's `backup_service_enabled` - `Some(false)` is a
    /// quiet line, `Some(true)` a warning, `None` (older launcher, unreadable) nothing.
    backup_service_enabled: Option<bool>,
    /// "Launcher crashes" card (cleanup 2026-10-06): the newest crash reports.
    crashes: Vec<crate::crashes::DeviceCrash>,
    /// Apps card (design 13): `device_policy.app_updates_wifi_only`, and the last report's line
    /// about the phone's network.
    app_updates_wifi_only: bool,
    app_downloads_line: Option<String>,
    /// Apps card (design 14): "Name and icon" for every allowed app with a package name.
    display_forms: Vec<crate::app_display::AppDisplayForm>,
    /// "Music" card (design 21, `#music`); `None` when it couldn't be read (the card says so).
    music: Option<crate::handlers::music::MusicCard>,
}

/// The kiosk app block switch on the "Play and kiosk" card (handy step 9): with it on, kiosk mode
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
            // Back to the card (qa-19-code #4), like the escapes card.
            Redirect::to(&format!("/devices/{id}#play-kiosk")).into_response()
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

/// The "Name and icon" forms of the device page (design 14): every allowed (checked) app with a
/// valid package name but the launcher - installed apps, Play apps and catalog apps not installed
/// yet. Each shows this phone's own choice, else the catalog default; a refused save shows what
/// was entered.
async fn app_display_forms(
    state: &AppState,
    device_id: i64,
    apps: &[UnifiedAppRow],
    entered: Option<&(String, crate::app_display::Entered)>,
) -> Vec<crate::app_display::AppDisplayForm> {
    use crate::app_display::{AppDisplayForm, DisplayValues};
    let defaults = crate::app_display::catalog_defaults(&state.db)
        .await
        .unwrap_or_else(|err| {
            tracing::error!(%err, "couldn't read the catalog's app names");
            Default::default()
        });
    let own = crate::app_display::device_rows(&state.db, device_id)
        .await
        .unwrap_or_else(|err| {
            tracing::error!(device_id, %err, "couldn't read the phone's app names");
            Default::default()
        });
    apps.iter()
        .filter(|a| {
            a.checked && !a.is_launcher && crate::app_display::valid_package_name(&a.package_name)
        })
        .map(|a| {
            let default = defaults.get(&a.package_name);
            let row = own.get(&a.package_name);
            let current = row.or(default).cloned().unwrap_or_else(DisplayValues::own);
            let mut form = AppDisplayForm::new(
                format!("/devices/{device_id}/apps/display"),
                format!("app-{}", a.package_name),
                a.package_name.clone(),
                a.label.clone(),
                &current,
                entered
                    .filter(|(package, _)| *package == a.package_name)
                    .map(|(_, e)| e),
            );
            form.catalog_default = default.map(DisplayValues::describe);
            form.own_row = row.is_some();
            if row.is_none() && default.is_some() {
                form.summary = format!("{} (from the catalog)", form.summary);
            }
            form
        })
        .collect()
}

/// `POST /devices/{id}/apps/display` (design 14): this phone's name, icon and colour for one app.
/// `action` "own" = the app's own (on this phone, even with a catalog default), "catalog" =
/// follow the catalog default (the phone's row goes), else save the fields. A choice equal to the
/// catalog default, or "the app's own" without a default, keeps no row. Refused input: 400, the
/// device page with that app's form open, the entered values and the error by the field, the field
/// focused (so the page doesn't jump to the top). Saved: back to the app (`#app-<package>`); nudges.
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
            let mut response =
                render_device(&state, id, &Default::default(), Some((package, entered))).await;
            *response.status_mut() = StatusCode::BAD_REQUEST;
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
        let rows: i64 = sqlx::query_scalar(
            "SELECT COUNT(*) FROM device_app_display WHERE device_id = ? AND package_name != ?",
        )
        .bind(id)
        .bind(&package)
        .fetch_one(&state.db)
        .await
        .unwrap_or(0);
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
            Redirect::to(&format!("/devices/{id}#app-{package}")).into_response()
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

/// "App updates only on Wi-Fi" on the Apps card (design 13): catalog apps download on an
/// unmetered network only, the launcher's own update after 3 days on any non-roaming one. An
/// auto-saving form, so a missing checkbox is off; back to the page (scroll-restore keeps the
/// place); 404 for an unknown device; nudges the phone.
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
            Redirect::to(&format!("/devices/{id}")).into_response()
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

/// The "Launcher updates and notifications" card (handy step 11): the update fence (other Home
/// apps paused while the launcher installs its own update), the notification auto-cancel rule and
/// the boot cover (design 16b). One auto-saving form, so a missing checkbox is off; 404 for an
/// unknown device; nudges; back to the card.
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
            Redirect::to(&format!("/devices/{id}#kiosk-escapes")).into_response()
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

/// The device page's "Play and kiosk" card: whether the phone holds the command stream (instant
/// changes), Play's state and the kiosk app block switch.
pub(crate) struct PlayCard {
    pub lines: Vec<String>,
    pub warnings: Vec<String>,
    /// `device_policy.block_activity_start` (handy step 9).
    pub block_activity_start: bool,
}

/// Builds [PlayCard] from the in-memory stream count (design 19 Q1) and the phone's latest status
/// report.
pub(crate) async fn play_card(
    state: &AppState,
    device_id: i64,
    latest: Option<&DeviceStatus>,
) -> PlayCard {
    let mut lines = vec![crate::streams::instant_changes_line(
        state.command_streams.state(device_id),
    )];
    let mut warnings = Vec::new();
    let now_ms = chrono::Utc::now().timestamp_millis();
    if let Some(until) = latest
        .and_then(|s| s.install_mode_until_ms)
        .filter(|until| *until > now_ms)
    {
        let until = chrono::DateTime::from_timestamp_millis(until)
            .map(|t| t.format("%H:%M UTC").to_string())
            .unwrap_or_default();
        warnings.push(format!(
            "Play install mode is on (until {until}): the Play Store can be opened on the phone. Apps installed now stay hidden until you allow them below."
        ));
    }
    if latest.is_some_and(|s| s.play_window_active) {
        lines.push(
            "The nightly Play update window is open (screen off): Play is updating apps."
                .to_string(),
        );
    }
    let block_activity_start: bool =
        sqlx::query_scalar("SELECT block_activity_start FROM device_policy WHERE device_id = ?")
            .bind(device_id)
            .fetch_optional(&state.db)
            .await
            .ok()
            .flatten()
            .unwrap_or(true);
    if latest.and_then(|s| s.play_store_suspendable) == Some(false) {
        warnings.push(if block_activity_start {
            "Play can't be suspended on this phone - Play is blocked only while kiosk is on."
                .to_string()
        } else {
            "Play can't be suspended on this phone, and the kiosk app block below is off: apps \
             can open the Play Store."
                .to_string()
        });
    }
    PlayCard {
        lines,
        warnings,
        block_activity_start,
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
    let launcher_without_rules = latest.is_some_and(|s| {
        !s.capabilities_json
            .as_deref()
            .and_then(|j| serde_json::from_str::<Vec<String>>(j).ok())
            .unwrap_or_default()
            .iter()
            .any(|c| c == "time_rules_v1")
    });
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
    render_device(&state, id, &query, None).await
}

/// The device page; `entered` = a refused "Name and icon" save (that app's form opens with the
/// entered values and the error, design 14).
async fn render_device(
    state: &AppState,
    id: i64,
    query: &std::collections::HashMap<String, String>,
    entered: Option<(String, crate::app_display::Entered)>,
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

    let installed: Vec<InstalledApp> = latest_status
        .as_ref()
        .and_then(|s| s.installed_apps_json.as_deref())
        .and_then(|j| serde_json::from_str(j).ok())
        .unwrap_or_default();

    let all_tracked = sqlx::query_as::<_, TrackedApp>("SELECT * FROM tracked_apps ORDER BY name")
        .fetch_all(&state.db)
        .await
        .unwrap_or_default();
    let selected_app_ids: std::collections::HashSet<i64> =
        sqlx::query_scalar("SELECT tracked_app_id FROM device_tracked_apps WHERE device_id = ?")
            .bind(id)
            .fetch_all(&state.db)
            .await
            .unwrap_or_default()
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
        .await
        .unwrap_or_default()
        .into_iter()
        .map(|(tracked_app_id, percent, failed)| (tracked_app_id, (percent, failed)))
        .collect();

    // What the phone said about its downloads (design 13): waiting for Wi-Fi since when, how far.
    // A fresh progress row above still wins for the live percentage.
    let downloads = crate::app_downloads::parse(
        latest_status
            .as_ref()
            .and_then(|s| s.app_downloads_json.as_deref()),
    );
    let download_of = |tracked_app_id: i64| {
        downloads
            .as_ref()
            .and_then(|d| d.entry(tracked_app_id))
            .filter(|_| !install_progress.contains_key(&tracked_app_id))
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
                crate::app_downloads::update_label(update)
            } else {
                "Installed".to_string()
            },
            is_installing: false,
            install_failed: false,
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
        let (status_label, is_installing, install_failed) = match progress {
            Some((_, true)) => ("Install failed".to_string(), false, true),
            Some((percent, false)) => (format!("Installing {percent}%"), true, false),
            None => match download_of(t.id) {
                Some(download) => (crate::app_downloads::new_app_label(download), false, false),
                None => ("Not installed".to_string(), false, false),
            },
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
                    .map(crate::app_downloads::launcher_label)
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

    let offline_override_used = latest_status
        .as_ref()
        .map(|s| s.offline_override_used)
        .unwrap_or(false);
    let any_app_installing = apps.iter().any(|a| a.is_installing);
    let display_forms = app_display_forms(&state, id, &apps, entered.as_ref()).await;
    let app_downloads_line = downloads
        .as_ref()
        .and_then(crate::app_downloads::network_line);
    let policy_problem = latest_status
        .as_ref()
        .and_then(|s| s.policy_state.clone())
        .filter(|state| state != "ok")
        .map(|state| policy_problem_text(&state));
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

    let call_warnings = crate::handlers::calls::call_warnings(&state, &policy)
        .await
        .unwrap_or_else(|err| {
            tracing::error!(device_id = id, %err, "failed to read call warnings");
            vec!["Couldn't read this phone's call state - check the server log.".to_string()]
        });

    let time = time_card(&state, &policy, latest_status.as_ref()).await;
    let play = play_card(&state, id, latest_status.as_ref()).await;
    let lock = {
        let capable = latest_status
            .as_ref()
            .and_then(|s| s.capabilities_json.as_deref())
            .is_some_and(|caps| {
                caps.contains(&format!("\"{}\"", crate::kid_lock::PIN_LOCK_CAPABILITY))
            });
        let lock_state = crate::kid_lock::parse_lock_state(
            latest_status
                .as_ref()
                .and_then(|s| s.lock_state_json.as_deref()),
        );
        crate::kid_lock::lock_card(
            &policy,
            lock_state.as_ref(),
            capable,
            latest_status.is_some(),
            chrono::Utc::now().timestamp_millis(),
        )
    };
    let escapes = {
        let capable = latest_status
            .as_ref()
            .and_then(|s| s.capabilities_json.as_deref())
            .is_some_and(|caps| {
                caps.contains(&format!(
                    "\"{}\"",
                    crate::kiosk_escapes::KIOSK_ESCAPES_CAPABILITY
                ))
            });
        let fence = crate::kiosk_escapes::parse_update_fence(
            latest_status
                .as_ref()
                .and_then(|s| s.update_fence_json.as_deref()),
        );
        let cancels = crate::kiosk_escapes::parse_notification_cancels(
            latest_status
                .as_ref()
                .and_then(|s| s.notification_cancels_json.as_deref()),
        );
        let mut card = crate::kiosk_escapes::escapes_card(
            policy.update_fence,
            policy.notification_auto_cancel,
            capable,
            latest_status.is_some(),
            fence.as_ref(),
            cancels.as_ref(),
            latest_status
                .as_ref()
                .and_then(|s| s.notification_listener_enabled),
            chrono::Utc::now().timestamp_millis(),
        );
        card.boot_cover = policy.boot_cover;
        // Design 16b (qa-16b-code #5): what the phone says about the boot cover.
        let cover_capable = latest_status
            .as_ref()
            .and_then(|s| s.capabilities_json.as_deref())
            .is_some_and(|caps| {
                caps.contains(&format!(
                    "\"{}\"",
                    crate::kiosk_escapes::BOOT_COVER_CAPABILITY
                ))
            });
        let cover_state = crate::kiosk_escapes::parse_boot_cover(
            latest_status
                .as_ref()
                .and_then(|s| s.boot_cover_json.as_deref()),
        );
        let (lines, warnings) = crate::kiosk_escapes::boot_cover_notes(
            policy.boot_cover,
            cover_capable,
            latest_status.is_some(),
            cover_state.as_ref(),
        );
        card.lines.extend(lines);
        card.warnings.extend(warnings);
        card
    };
    let notice = query
        .get("notice")
        .and_then(|code| crate::kid_lock::flash_text(code));
    let wallpapers = crate::handlers::wallpapers::device_choices(&state, id)
        .await
        .unwrap_or_else(|err| {
            tracing::error!(device_id = id, %err, "couldn't load the wallpaper card");
            Vec::new()
        });

    let crashes = crate::crashes::latest(&state.db, id).await;
    let music = crate::handlers::music::device_card(
        &state,
        &policy,
        latest_status.as_ref(),
        query.get("music_notice").map(String::as_str),
    )
    .await
    .map_err(|err| tracing::error!(device_id = id, %err, "couldn't load the music card"))
    .ok();

    Html(
        DeviceDetailTemplate {
            crashes,
            music,
            app_updates_wifi_only: policy.app_updates_wifi_only,
            app_downloads_line,
            display_forms,
            time,
            play,
            lock,
            escapes,
            notice,
            screen_timeout_seconds: crate::models::screen_timeout_seconds(
                policy.screen_timeout_seconds,
            ),
            screen_timeout_options: crate::models::SCREEN_TIMEOUTS.to_vec(),
            screen_timeout_applied: latest_status
                .as_ref()
                .and_then(|s| s.screen_timeout_seconds)
                .map(crate::models::screen_timeout_label),
            backup_service_enabled: latest_status
                .as_ref()
                .and_then(|s| s.backup_service_enabled),
            title: device.name.clone(),
            calls_summary,
            call_warnings,
            allowlist_corrupt,
            managed_without_pin: (policy.allowlist_json.is_some() || policy.calls_managed)
                && policy.override_pin_hash.is_none(),
            hardening: policy.hardening.clone(),
            launcher_language: policy.launcher_language.clone(),
            home_columns: policy.home_columns,
            wallpapers,
            badges_without_access: latest_status
                .as_ref()
                .is_some_and(|s| s.notification_listener_enabled == Some(false)),
            any_app_installing,
            pin_configured: policy.override_pin_hash.is_some(),
            offline_override_used,
            policy_problem,
            restrictions_paused,
            vpn_filter_enabled: policy.vpn_filter_enabled,
            quick_control_wifi: policy.quick_controls_mask & QUICK_CONTROL_WIFI != 0,
            quick_control_bluetooth: policy.quick_controls_mask & QUICK_CONTROL_BLUETOOTH != 0,
            quick_control_brightness: policy.quick_controls_mask & QUICK_CONTROL_BRIGHTNESS != 0,
            quick_control_sound: policy.quick_controls_mask & QUICK_CONTROL_SOUND != 0,
            sound_lines: latest_status
                .as_ref()
                .map(|s| {
                    crate::sound_mode::status_lines(
                        s.ringer_mode.as_deref(),
                        s.interruption_filter.as_deref(),
                    )
                })
                .unwrap_or_default(),
            device,
            apps,
            latest_status,
        }
        .render()
        .unwrap(),
    )
    .into_response()
}

/// Parent-facing explanation of a non-"ok" `device_status.policy_state`.
fn policy_problem_text(state: &str) -> String {
    match state {
        "cache_corrupt" => "The phone can't read its saved policy. It enforces the last app list \
            it had (or nothing but the launcher), and won't pick up changes until it gets a good \
            policy from this server."
            .to_string(),
        "server_error" => "This server answered the phone's policy request with an error, so the \
            phone keeps its last policy. Check this server's log (\"failed to build device \
            policy\")."
            .to_string(),
        "rejected_suspect" => "The phone ignored the last policy from this server because it \
            looked like a server falling back to defaults (no app list after it had one). Check \
            this server's log and version."
            .to_string(),
        "fresh_decode_failed" => "The phone couldn't read the last policy from this server - \
            the launcher and server versions probably don't match. It keeps its current \
            restrictions."
            .to_string(),
        other => format!("The phone reported a policy problem: {other}"),
    }
}

/// Flips one row of the unified Apps list for one device - a standalone auto-submitting toggle
/// (see device_detail.html), not folded into the big `update_policy` form like the rest of a
/// device's settings. Deliberately different from that form's "edit several things, then click
/// Save" pattern - live testing (back when this was two separate lists) showed an admin checking
/// a box and *not* separately scrolling down to hit an unrelated form's Save button, since every
/// other on/off switch in this app already auto-saves on change. The launcher's own row never
/// reaches this handler - its checkbox in the template is `disabled`, and a disabled control can't
/// be interacted with to submit a request in the first place.
///
/// Takes both `package_name` and `tracked_app_id` as (independently optional) form fields rather
/// than a single path-scoped id, since a row here might be catalog-only (no package name on file
/// yet), install-only (a preinstalled or otherwise-installed app never added to the catalog), or
/// both - see `UnifiedAppRow`'s own doc comment. At least one is expected to be present; a toggle
/// with neither is a no-op.
///
/// Checking: adds the `device_tracked_apps` row if there's a catalog entry to track (so a
/// not-yet-installed app actually gets pushed, and an already-installed one starts picking up
/// future updates), and allows the package if there's one on file - both no-ops if already in
/// that state.
///
/// Unchecking: removes the `device_tracked_apps` row and disallows the package the same way.
/// Whether it *also* queues a silent uninstall depends on current on-device status - a preinstalled
/// app can never actually be uninstalled (only suspended/hidden), so that step is skipped entirely
/// for one; any other currently-installed package gets queued via `device_pending_uninstalls`,
/// same as before this was generalized from tracked-apps-only.
pub async fn toggle_app(
    State(state): State<AppState>,
    Path(id): Path<i64>,
    Form(form): Form<std::collections::HashMap<String, String>>,
) -> axum::response::Response {
    let checked = form.contains_key("selected");
    let package_name = form
        .get("package_name")
        .map(String::as_str)
        .unwrap_or("")
        .to_string();
    let tracked_app_id: Option<i64> = form.get("tracked_app_id").and_then(|s| s.parse().ok());

    // Play Store/services/GSF are never the parent's to allow or remove (handy step 7): the
    // launcher keeps them installed, unhidden and out of the kiosk whatever the allowlist says.
    if crate::play::is_play_core(&package_name) {
        return (
            axum::http::StatusCode::BAD_REQUEST,
            "The Play Store, Play services and Google Services Framework are managed by the \
             launcher itself and can't be allowed or removed here.",
        )
            .into_response();
    }
    // One source per package: the launcher can't update a Play-installed copy from the catalog
    // (different signature, Android 14 update ownership).
    if checked && let Some(tid) = tracked_app_id {
        match catalog_conflicts_with_play(&state, id, tid).await {
            Ok(Some(name)) => {
                return (
                    axum::http::StatusCode::CONFLICT,
                    format!(
                        "{name} is installed on this phone from the Play Store, so it can't also \
                         be installed from the Apps catalog. Uninstall the Play copy first (uncheck \
                         it, wait for the phone to report it gone), then select it here."
                    ),
                )
                    .into_response();
            }
            Ok(None) => {}
            Err(err) => {
                tracing::error!(device_id = id, %err, "catalog source check failed");
                return (
                    axum::http::StatusCode::INTERNAL_SERVER_ERROR,
                    "Couldn't check where this app is installed from - nothing was changed.",
                )
                    .into_response();
            }
        }
    }

    if checked {
        if let Some(tid) = tracked_app_id {
            sqlx::query(
                "INSERT OR IGNORE INTO device_tracked_apps (device_id, tracked_app_id) VALUES (?, ?)",
            )
            .bind(id)
            .bind(tid)
            .execute(&state.db)
            .await
            .ok();
        }
        if !package_name.is_empty()
            && let Err(err) = add_to_allowlist(&state, id, &package_name).await
        {
            return allowlist_error_response(id, &package_name, err);
        }
    } else {
        if let Some(tid) = tracked_app_id {
            sqlx::query(
                "DELETE FROM device_tracked_apps WHERE device_id = ? AND tracked_app_id = ?",
            )
            .bind(id)
            .bind(tid)
            .execute(&state.db)
            .await
            .ok();
        }
        if !package_name.is_empty() {
            if let Err(err) = remove_from_allowlist(&state, id, &package_name).await {
                return allowlist_error_response(id, &package_name, err);
            }
            let (installed, preinstalled) = installed_app_status(&state, id, &package_name).await;
            if installed && !preinstalled {
                sqlx::query(
                    "INSERT OR IGNORE INTO device_pending_uninstalls (device_id, package_name) \
                     VALUES (?, ?)",
                )
                .bind(id)
                .bind(&package_name)
                .execute(&state.db)
                .await
                .ok();
            }
        }
    }

    let _ = state.command_notify.send(id);
    // The Music card's app switch (design 21) comes back to its card; the Apps card's to the
    // page (scroll-restore keeps the place).
    if form.get("anchor").map(String::as_str) == Some("music") {
        return Redirect::to(&format!("/devices/{id}#music")).into_response();
    }
    Redirect::to(&format!("/devices/{id}")).into_response()
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

/// The catalog app's name when the device's latest status report shows its package installed from
/// the Play Store; `None` when there's no conflict (no package name, not installed, other source).
async fn catalog_conflicts_with_play(
    state: &AppState,
    device_id: i64,
    tracked_app_id: i64,
) -> Result<Option<String>, sqlx::Error> {
    let app: Option<(String, String)> =
        sqlx::query_as("SELECT name, package_name FROM tracked_apps WHERE id = ?")
            .bind(tracked_app_id)
            .fetch_optional(&state.db)
            .await?;
    let Some((name, package)) = app.filter(|(_, package)| !package.is_empty()) else {
        return Ok(None);
    };
    let json: Option<Option<String>> = sqlx::query_scalar(
        "SELECT installed_apps_json FROM device_status WHERE device_id = ? \
         ORDER BY reported_at DESC, id DESC LIMIT 1",
    )
    .bind(device_id)
    .fetch_optional(&state.db)
    .await?;
    let installed: Vec<InstalledApp> = json
        .flatten()
        .as_deref()
        .and_then(|j| serde_json::from_str(j).ok())
        .unwrap_or_default();
    let from_play = installed.iter().any(|a| {
        a.package_name == package && a.installer.as_deref() == Some(crate::play::PLAY_STORE)
    });
    Ok(from_play.then_some(name))
}

/// Whether the device's most recent status report lists this package as installed, and if so,
/// whether it was reported as a preinstalled (`ApplicationInfo.FLAG_SYSTEM`) app - gates both
/// whether [toggle_app] has anything to uninstall at all, and whether it should even try (a
/// preinstalled app can only ever be suspended/hidden, never actually removed).
async fn installed_app_status(
    state: &AppState,
    device_id: i64,
    package_name: &str,
) -> (bool, bool) {
    let json: Option<String> = sqlx::query_scalar(
        "SELECT installed_apps_json FROM device_status WHERE device_id = ? \
         ORDER BY reported_at DESC LIMIT 1",
    )
    .bind(device_id)
    .fetch_optional(&state.db)
    .await
    .ok()
    .flatten();
    let installed: Vec<InstalledApp> = json
        .as_deref()
        .and_then(|j| serde_json::from_str(j).ok())
        .unwrap_or_default();
    match installed.iter().find(|a| a.package_name == package_name) {
        Some(a) => (true, a.preinstalled),
        None => (false, false),
    }
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

/// Handles everything on a device's page *except* the Apps list, which is now its own set of
/// per-row [toggle_app] saves - this form used to also carry "Allowed apps" checkboxes, which
/// needed the allowlist-reconciliation dance now gone from here entirely (see git history if that
/// logic is ever needed for reference). `Form<HashMap<...>>` is safe to use directly again now
/// that nothing here is a repeated-name checkbox group.
pub async fn update_policy(
    State(state): State<AppState>,
    Path(id): Path<i64>,
    Extension(CurrentAdmin(admin)): Extension<CurrentAdmin>,
    Form(fields): Form<std::collections::HashMap<String, String>>,
) -> impl IntoResponse {
    let field = |k: &str| fields.get(k).cloned().unwrap_or_default();

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

    // The PIN fields are optional on every save (this form saves everything
    // together) - leave the stored hash/salt untouched unless the admin
    // actually typed a new PIN or explicitly asked to clear it, so blank
    // fields on an unrelated save can't silently wipe an already-configured
    // PIN.
    let current =
        sqlx::query_as::<_, DevicePolicy>("SELECT * FROM device_policy WHERE device_id = ?")
            .bind(id)
            .fetch_optional(&state.db)
            .await
            .ok()
            .flatten();
    let current_pin = current.as_ref().and_then(|p| p.override_pin_hash.clone());
    let current_salt = current.as_ref().and_then(|p| p.override_pin_salt.clone());

    let new_pin = field("new_pin").trim().to_string();
    // Handy's lock (step 10): the override PIN is the lock screen's parent code, so it can't be
    // removed while a kid PIN is set, and it must never be the kid's PIN (QA 10 #6). PBKDF2 (the
    // cross-check and the new hash) runs off the async workers (qa-10-code #9).
    let kid_pin_set = current.as_ref().is_some_and(|p| p.kid_pin_hash.is_some());
    let clear = fields.contains_key("clear_pin");
    let decided = {
        let current = current.clone();
        tokio::task::spawn_blocking(
            move || -> (Option<(String, String)>, Option<&'static str>) {
                if clear || new_pin.is_empty() {
                    return (None, None);
                }
                if current
                    .as_ref()
                    .is_some_and(|p| crate::kid_lock::override_conflicts(&new_pin, p))
                {
                    return (None, Some("override_is_kid_pin"));
                }
                if new_pin.len() >= 6 && new_pin.chars().all(|c| c.is_ascii_digit()) {
                    (Some(security::hash_pin(&new_pin)), None)
                } else {
                    // Invalid PIN typed - ignore it rather than fail the whole save, keeping whatever
                    // was already configured.
                    (None, None)
                }
            },
        )
        .await
        .unwrap_or_else(|err| {
            tracing::error!(device_id = id, %err, "override PIN hashing failed");
            (None, None)
        })
    };
    let (new_hash, mut notice) = decided;
    let (override_pin_hash, override_pin_salt, mut pin_event) = if clear {
        if kid_pin_set {
            notice = Some("override_needed_by_lock");
            (current_pin, current_salt, None)
        } else {
            (None, None, Some("override_pin_cleared"))
        }
    } else if let Some((hash, salt)) = new_hash {
        (Some(hash), Some(salt), Some("override_pin_changed"))
    } else {
        (current_pin, current_salt, None)
    };

    // The SQL itself refuses to remove the override PIN while a kid PIN is set, so a kid PIN
    // saved in parallel can't end up without its parent code (qa-10-code #9).
    let stored: Option<Option<String>> = sqlx::query_scalar(
        "UPDATE device_policy SET kiosk_desired = 1, \
         lock_task_features = ?, \
         override_pin_hash = CASE WHEN ? IS NULL AND kid_pin_hash IS NOT NULL THEN override_pin_hash ELSE ? END, \
         override_pin_salt = CASE WHEN ? IS NULL AND kid_pin_hash IS NOT NULL THEN override_pin_salt ELSE ? END, \
         quick_controls_mask = ?, vpn_filter_enabled = ?, \
         updated_at = datetime('now') WHERE device_id = ? RETURNING override_pin_hash",
    )
    .bind(lock_task_features)
    .bind(&override_pin_hash)
    .bind(&override_pin_hash)
    .bind(&override_pin_salt)
    .bind(&override_pin_salt)
    .bind(quick_controls_mask)
    .bind(vpn_filter_enabled)
    .bind(id)
    .fetch_optional(&state.db)
    .await
    .ok()
    .flatten();
    if override_pin_hash.is_none() && stored.as_ref().is_some_and(|h| h.is_some()) {
        // Lost the race to a kid PIN: the override stayed.
        pin_event = None;
        notice = Some("override_needed_by_lock");
    }

    if let Some(event_type) = pin_event {
        security::record_security_event(
            &state.db,
            event_type,
            Some(&admin.username),
            None,
            Some(&format!("device {id}")),
        )
        .await;
    }

    // Nudges the device to re-sync immediately over the same SSE connection Find My Device uses
    // for ring/lock, rather than waiting out the rest of the background poll interval - the nudge
    // itself carries no data, the device just re-fetches /api/devices/policy on it, so this reuses
    // the exact same dispatch path as a normal scheduled sync.
    let _ = state.command_notify.send(id);

    match notice {
        Some(code) => Redirect::to(&format!("/devices/{id}?notice={code}#screen-lock")),
        None => Redirect::to(&format!("/devices/{id}")),
    }
}

/// The "Screen lock" card (handy step 10): set or remove the kid's PIN for handy's own lock
/// screen. A PIN must be 4-6 digits, needs an override PIN (the lock's parent code) and must not
/// be the override PIN (`kid_lock::decide_kid_pin`); a refused save writes nothing and says why.
/// The first PIN also blocks safe mode (decision after QA review: safe mode skips our lock) -
/// the parent can switch that off again in "Phone hardening". 404 for an unknown device, 500 on
/// a DB error; security events `kid_pin_changed`/`kid_pin_cleared`; nudges the phone.
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
        Redirect::to(&format!("/devices/{id}?notice={code}#screen-lock")).into_response()
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
        // kid PIN without its parent code (qa-10-code #9); zero rows = refused.
        let result = sqlx::query(
            "UPDATE device_policy SET kid_pin_hash = ?, kid_pin_salt = ?, kid_pin_length = ?, \
             disallow_safe_boot = ?, updated_at = datetime('now') \
             WHERE device_id = ? AND override_pin_hash IS NOT NULL",
        )
        .bind(&hash)
        .bind(&salt)
        .bind(length)
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

/// The "Phone hardening" card: one auto-submitting form with every switch, so a missing checkbox
/// really means "off" (same pattern as the calls switches). 404 for an unknown device, 500 (nothing
/// written) on a DB error; nudges the phone like every other policy change.
pub async fn update_hardening(
    State(state): State<AppState>,
    Path(id): Path<i64>,
    Form(form): Form<std::collections::HashMap<String, String>>,
) -> axum::response::Response {
    let on = |key: &str| form.contains_key(key);
    let result = sqlx::query(
        "UPDATE device_policy SET disallow_factory_reset = ?, disallow_add_user = ?, \
         disallow_modify_accounts = ?, disallow_config_vpn = ?, disallow_usb_file_transfer = ?, \
         disallow_debugging_features = ?, disallow_safe_boot = ?, lock_location = ?, \
         disallow_airplane_mode = ?, disallow_config_locale = ?, updated_at = datetime('now') \
         WHERE device_id = ?",
    )
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
            Redirect::to(&format!("/devices/{id}")).into_response()
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

/// The "Launcher" card: language ("system", "nb", "en") and home-grid columns (3 or 4). One
/// auto-submitting form; an unknown value is a 400 and nothing is written.
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
            Redirect::to(&format!("/devices/{id}")).into_response()
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

/// The "Screen timeout" card (migrations/0032): one auto-submitting select. Only a value from
/// `SCREEN_TIMEOUTS` is accepted; anything else is a 400 and nothing is written.
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
            Redirect::to(&format!("/devices/{id}")).into_response()
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

    Redirect::to("/")
}
