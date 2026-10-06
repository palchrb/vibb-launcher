use serde::{Deserialize, Serialize};

#[derive(sqlx::FromRow, Clone)]
pub struct AdminUser {
    pub id: i64,
    pub username: String,
    pub password_hash: String,
    pub must_change_password: bool,
    pub totp_secret: Option<String>,
    pub totp_enabled: bool,
    pub failed_login_attempts: i64,
    pub locked_until: Option<String>,
}

#[derive(sqlx::FromRow, Clone)]
pub struct Device {
    pub id: i64,
    pub name: String,
    pub enrollment_code: Option<String>,
    pub enrollment_code_expires_at: Option<String>,
    pub token_hash: Option<String>,
    pub enrolled_at: Option<String>,
    pub last_seen_at: Option<String>,
}

#[derive(sqlx::FromRow, Clone, Default)]
pub struct DevicePolicy {
    pub device_id: i64,
    pub allowlist_json: Option<String>,
    pub weekday_start_minutes: Option<i64>,
    pub weekday_end_minutes: Option<i64>,
    pub weekend_start_minutes: Option<i64>,
    pub weekend_end_minutes: Option<i64>,
    pub bedtime_start_minutes: Option<i64>,
    pub bedtime_end_minutes: Option<i64>,
    pub kiosk_desired: bool,
    pub lock_task_features: Option<i64>,
    pub override_pin_hash: Option<String>,
    pub override_pin_salt: Option<String>,
    pub quick_controls_mask: i64,
    pub vpn_filter_enabled: bool,
    /// The per-device override switch: when set, this device uses its own time rules
    /// (`time_rules.device_id` = this device) and its own `daily_budget_json` instead of the
    /// global ones. The weekday/weekend/bedtime *_minutes columns above are the pre-step-6
    /// schedule, converted into rules once (`time_rules::migrate_legacy`) and still sent to older
    /// launchers. See `handlers::schedules`.
    pub custom_schedule_enabled: bool,
    /// Calls & SMS (migrations/0022_calls.sql). `calls_managed = false` sends
    /// `call_policy.managed = false`; the other three only matter when it's true.
    pub calls_managed: bool,
    pub calls_enabled: bool,
    pub sms_enabled: bool,
    /// "none", "sms", "element" or "signal" - see [MESSAGE_APPS].
    pub default_message_app: String,
    /// When `calls_managed` last changed (UTC, SQLite `datetime('now')`) - status reports older
    /// than this may still show the previous role state (migrations/0029).
    pub roles_changed_at: Option<String>,
    /// `LOCK_TASK_FEATURE_BLOCK_ACTIVITY_START_IN_TASK` in kiosk (handy step 9, default on).
    pub block_activity_start: bool,
    /// The hardening switches (migrations/0023_hardening.sql), sent as `PolicyResponse.hardening`.
    #[sqlx(flatten)]
    pub hardening: Hardening,
    /// "system", "nb" or "en" - see [LAUNCHER_LANGUAGES] (migrations/0024_launcher_ui_photos.sql).
    pub launcher_language: String,
    /// Columns of the launcher's home-screen app grid, 3 or 4.
    pub home_columns: i64,
    /// This device's own daily screen-time budget (migrations/0025_time_rules.sql), used while
    /// `custom_schedule_enabled` is set: 7 entries Monday first, minutes or null (unlimited).
    pub daily_budget_json: String,
    /// "off", "on_request" or "interval" - see `time_rules::LOCATION_MODES`.
    pub location_mode: String,
    /// Minutes between active location fixes in "interval" mode.
    pub location_interval_minutes: i64,
    /// The kid's PIN for handy's own lock screen (migrations/0030, handy step 10), hashed like
    /// the override PIN. `None` = lock off. Sent as `PolicyResponse.kid_lock`.
    pub kid_pin_hash: Option<String>,
    pub kid_pin_salt: Option<String>,
    /// 4-6; the keypad submits at the last digit.
    pub kid_pin_length: Option<i64>,
    /// Screen timeout in seconds (migrations/0032), one of [SCREEN_TIMEOUTS]; see
    /// [screen_timeout_seconds].
    pub screen_timeout_seconds: i64,
    /// The launcher's update fence (handy step 11, migrations/0036, default off).
    pub update_fence: bool,
    /// The launcher's notification auto-cancel rule (handy step 11, migrations/0036, default
    /// off).
    pub notification_auto_cancel: bool,
    /// The blocked-domain log (migrations/0039): off by default, a per-phone opt-in; while on,
    /// entries are kept `retention::DNS_LOG_RETENTION_DAYS`.
    pub dns_log_enabled: bool,
}

/// The screen timeouts a parent can choose (seconds) and their labels; the default is 1 minute.
pub const SCREEN_TIMEOUTS: [(i64, &str); 6] = [
    (15, "15 seconds"),
    (30, "30 seconds"),
    (60, "1 minute"),
    (120, "2 minutes"),
    (300, "5 minutes"),
    (600, "10 minutes"),
];
pub const DEFAULT_SCREEN_TIMEOUT_SECONDS: i64 = 60;

/// The stored timeout if it is one of [SCREEN_TIMEOUTS], else the default (a row written by
/// hand, or `DevicePolicy::default()` in tests).
pub fn screen_timeout_seconds(stored: i64) -> i64 {
    if SCREEN_TIMEOUTS.iter().any(|(s, _)| *s == stored) {
        stored
    } else {
        DEFAULT_SCREEN_TIMEOUT_SECONDS
    }
}

/// "1 minute", or "45 s" for a value that isn't one of the choices (what a phone reported).
pub fn screen_timeout_label(seconds: i64) -> String {
    SCREEN_TIMEOUTS
        .iter()
        .find(|(s, _)| *s == seconds)
        .map(|(_, label)| (*label).to_string())
        .unwrap_or_else(|| format!("{seconds} s"))
}

/// The launcher languages a parent can choose; "system" follows the phone's language.
pub const LAUNCHER_LANGUAGES: [&str; 3] = ["system", "nb", "en"];

/// `PolicyResponse.launcher_ui` - always sent with every field.
#[derive(Serialize, Clone, Debug, PartialEq, Eq)]
pub struct LauncherUi {
    pub language: String,
    pub home_columns: i64,
    /// The wallpapers this phone may use, in the parent's order (design 08-ui-polish.md); the kid
    /// picks one on the phone. Always sent, possibly empty (the launcher then uses navy).
    pub wallpapers: Vec<PolicyWallpaper>,
}

/// One allowed wallpaper in `launcher_ui.wallpapers` - every key always present.
#[derive(Serialize, Clone, Debug, PartialEq, Eq)]
pub struct PolicyWallpaper {
    pub id: i64,
    /// "color", "gradient" (two colours at 160°) or "image".
    pub kind: String,
    /// "#RRGGBB" - one for a colour, two for a gradient, none for an image.
    pub colors: Vec<String>,
    /// SHA-256 of the image (`GET /api/devices/wallpapers/{hash}`), null unless "image".
    pub image: Option<String>,
    /// The parent's label for an upload; the built-ins' English name.
    pub label: String,
    /// "navy", "forest", ... for the built-ins (the launcher labels them in the kid's language),
    /// null for uploads.
    pub builtin_key: Option<String>,
    /// An image also goes on the lock screen (the parent ticked it); otherwise the lock screen
    /// gets a colour.
    pub lock_screen: bool,
}

/// Android user restrictions the launcher sets while the phone is managed - one switch each,
/// stored as `device_policy` columns of the same name and always sent with explicit values
/// (`PolicyResponse.hardening`). The phone's offline override and pause don't lift them; only
/// turning a switch off here (or unmanaging the phone) does. See docs/design/04-hardening.md in
/// the handy workspace.
#[derive(sqlx::FromRow, Serialize, Clone, Debug, PartialEq, Eq)]
pub struct Hardening {
    /// `DISALLOW_FACTORY_RESET` (from Settings; a recovery-mode wipe can't be blocked).
    pub disallow_factory_reset: bool,
    /// `DISALLOW_ADD_USER` (no guest or second user).
    pub disallow_add_user: bool,
    /// `DISALLOW_MODIFY_ACCOUNTS`.
    pub disallow_modify_accounts: bool,
    /// `DISALLOW_CONFIG_VPN`.
    pub disallow_config_vpn: bool,
    /// `DISALLOW_USB_FILE_TRANSFER` (MTP/PTP; doesn't affect adb).
    pub disallow_usb_file_transfer: bool,
    /// `DISALLOW_DEBUGGING_FEATURES`: no adb and no developer options - which also removes adb as
    /// the recovery path for a broken launcher.
    pub disallow_debugging_features: bool,
    /// `DISALLOW_SAFE_BOOT`: off by default until checked on the phone. Safe mode bypasses the
    /// launcher but repairs nothing (restrictions persist there).
    pub disallow_safe_boot: bool,
    /// `DISALLOW_CONFIG_LOCATION` with location turned on (Find my device).
    pub lock_location: bool,
    /// `DISALLOW_AIRPLANE_MODE` (migrations/0024). Off by default: the family travels.
    pub disallow_airplane_mode: bool,
    /// `DISALLOW_CONFIG_LOCALE` (migrations/0034): the system language stays as set up. On by
    /// default; the launcher's own language (`launcher_ui.language`) is per app and unaffected.
    pub disallow_config_locale: bool,
}

impl Default for Hardening {
    /// The column defaults in migrations/0023_hardening.sql.
    fn default() -> Self {
        Hardening {
            disallow_factory_reset: true,
            disallow_add_user: true,
            disallow_modify_accounts: true,
            disallow_config_vpn: true,
            disallow_usb_file_transfer: true,
            disallow_debugging_features: true,
            disallow_safe_boot: false,
            lock_location: true,
            disallow_airplane_mode: false,
            disallow_config_locale: true,
        }
    }
}

/// The values `device_policy.default_message_app` and `device_contacts.message_app` may take.
pub const MESSAGE_APPS: [&str; 4] = ["none", "sms", "element", "signal"];

/// One contact attached to one device - `contacts` joined with `device_contacts`.
#[derive(sqlx::FromRow, Clone)]
pub struct DeviceContactRow {
    pub contact_id: i64,
    pub name: String,
    pub phone_number: String,
    pub allow_inbound: bool,
    pub allow_outbound: bool,
    pub show_on_home: bool,
    /// `None` = the device's `default_message_app`.
    pub message_app: Option<String>,
    pub message_address: Option<String>,
    pub photo_hash: Option<String>,
}

/// Singleton (always `id = 1`) - the schedule every device follows unless it has its own
/// `device_policy.custom_schedule_enabled` override. See `handlers::schedules`.
#[derive(sqlx::FromRow, Clone, Default)]
pub struct GlobalSchedule {
    pub id: i64,
    pub weekday_start_minutes: Option<i64>,
    pub weekday_end_minutes: Option<i64>,
    pub weekend_start_minutes: Option<i64>,
    pub weekend_end_minutes: Option<i64>,
    pub bedtime_start_minutes: Option<i64>,
    pub bedtime_end_minutes: Option<i64>,
    pub updated_at: String,
    /// The global daily screen-time budget (migrations/0025), see `DevicePolicy.daily_budget_json`.
    pub daily_budget_json: String,
}

#[derive(sqlx::FromRow, Clone)]
pub struct DeviceStatus {
    pub id: i64,
    pub device_id: i64,
    pub lock_reason: String,
    pub kiosk_engaged: bool,
    pub installed_apps_json: Option<String>,
    pub app_version: Option<String>,
    pub app_version_code: Option<i64>,
    pub offline_override_used: bool,
    pub reported_at: String,
    /// See migrations/0021_device_status_policy_state.sql. `None` from older launchers.
    pub policy_state: Option<String>,
    pub restrictions_paused: bool,
    /// The launcher's notification listener (app badges) has access - migrations/0024. `None`
    /// from older launchers.
    pub notification_listener_enabled: Option<bool>,
    /// What the launcher can enforce, JSON list (migrations/0022). `None` from older launchers.
    pub capabilities_json: Option<String>,
    /// The launcher's `time_state` (active rule, screen time used/budget) - migrations/0025.
    pub time_state_json: Option<String>,
    /// The launcher's `push` object (migrations/0026).
    pub push_state_json: Option<String>,
    /// Play install mode end (wall-clock ms) while active (migrations/0026).
    pub install_mode_until_ms: Option<i64>,
    pub play_window_active: bool,
    /// See `StatusReportRequest.play_store_suspendable` (migrations/0029).
    pub play_store_suspendable: Option<bool>,
    /// The launcher's `lock_state` (handy step 10, migrations/0030), see `kid_lock::LockState`.
    pub lock_state_json: Option<String>,
    /// The screen timeout the phone applied, seconds (migrations/0032). `None` from older
    /// launchers.
    pub screen_timeout_seconds: Option<i64>,
    /// The phone's `update_fence` (handy step 11, migrations/0036), see
    /// `kiosk_escapes::UpdateFenceState`.
    pub update_fence_json: Option<String>,
    /// The phone's `notification_cancels` (handy step 11), see
    /// `kiosk_escapes::NotificationCancels`.
    pub notification_cancels_json: Option<String>,
    // call_state_json (migrations/0022_calls.sql) is read directly by
    // handlers::calls::call_warnings.
}

#[derive(sqlx::FromRow, Clone)]
pub struct SecurityEvent {
    pub id: i64,
    pub event_type: String,
    pub username: Option<String>,
    pub ip_address: Option<String>,
    pub detail: Option<String>,
    pub created_at: String,
}

#[derive(sqlx::FromRow, Clone)]
pub struct BannedIp {
    pub ip_address: String,
    pub banned_until: String,
    pub reason: Option<String>,
}

#[derive(sqlx::FromRow, Clone)]
pub struct TrackedApp {
    pub id: i64,
    pub name: String,
    pub package_name: String,
    /// "github" or "manual" - see migrations/0008_tracked_apps_source_type.sql.
    pub source_type: String,
    /// Empty string (not NULL) for manual-source apps - avoids a SQLite
    /// table rebuild to loosen the original NOT NULL constraint; a real
    /// repo string is never empty, so it's an unambiguous sentinel.
    pub github_repo: String,
    pub asset_pattern: Option<String>,
    pub include_prereleases: bool,
    pub enabled: bool,
    pub latest_release_tag: Option<String>,
    pub latest_release_asset_id: Option<i64>,
    pub latest_release_file_path: Option<String>,
    pub last_checked_at: Option<String>,
    pub created_at: String,
    /// See migrations/0013_device_tracked_apps.sql - marks the one row that
    /// is the launcher itself, which can't be deleted or deselected on any
    /// device.
    pub is_launcher: bool,
}

/// Singleton row (id always 1) - see migrations/0009_dns_filter.sql.
#[derive(sqlx::FromRow, Clone)]
pub struct DnsFilterSettings {
    pub id: i64,
    pub upstream: String,
    pub updated_at: String,
}

/// Singleton row (id always 1) - see migrations/0018_provisioning_settings.sql. Embedded into
/// every device's QR provisioning payload (handlers::provisioning) - one server and one reusable
/// Tailscale pre-auth key cover every device, so neither is per-device.
#[derive(sqlx::FromRow, Clone, Default)]
pub struct ProvisioningSettings {
    pub id: i64,
    pub server_url: String,
    pub tailscale_auth_key: String,
    pub updated_at: String,
    /// `android.app.extra.PROVISIONING_LOCALE` in the setup QR, "xx_YY" (migrations/0034,
    /// default "nb_NO"); empty = not sent.
    pub locale: String,
    /// `android.app.extra.PROVISIONING_TIME_ZONE`, an IANA zone id (default "Europe/Oslo");
    /// empty = not sent.
    pub time_zone: String,
}

#[derive(sqlx::FromRow, Clone)]
pub struct DnsBlocklist {
    pub id: i64,
    pub name: String,
    pub url: String,
    pub enabled: bool,
    pub created_at: String,
}

#[derive(sqlx::FromRow, Clone)]
pub struct DnsCustomDomain {
    pub id: i64,
    pub domain: String,
    pub list_type: String,
    /// NULL = applies to all devices, set = this device only. See
    /// migrations/0011_client_side_dns_filtering.sql.
    pub device_id: Option<i64>,
    pub created_at: String,
}

/// Per-device on/off override for a curated blocklist feed - absence of a
/// row for a given device means "use `DnsBlocklist.enabled`". See
/// migrations/0011_client_side_dns_filtering.sql.
#[derive(sqlx::FromRow, Clone)]
pub struct DeviceBlocklistOverride {
    pub device_id: i64,
    pub blocklist_id: i64,
    pub enabled: bool,
    pub updated_at: String,
}

/// One blocked-domain event, self-reported by the device's on-device filter -
/// `blocked_at` is the device's own timestamp, `received_at` is server
/// ingest time (mirrors `DeviceLocation`'s captured_at/received_at split).
/// See migrations/0011_client_side_dns_filtering.sql.
#[derive(sqlx::FromRow, Clone, Serialize)]
pub struct DeviceDnsEvent {
    pub id: i64,
    pub device_id: i64,
    pub domain: String,
    pub category: String,
    pub blocked_at: String,
    pub received_at: String,
}

/// One point in a device's location trail - see migrations/0010_find_my_device.sql.
/// `captured_at` is the device's own fix timestamp, not when the server received it.
#[derive(sqlx::FromRow, Clone, Serialize)]
pub struct DeviceLocation {
    pub id: i64,
    pub device_id: i64,
    pub latitude: f64,
    pub longitude: f64,
    pub accuracy_meters: Option<f64>,
    pub captured_at: String,
    pub received_at: String,
}

/// A queued remote command (ring/lock/wipe/locate) - `delivered_at` is set the
/// instant `policy()` serves it to the device, `acknowledged_at` when the
/// device reports back (never, for `wipe`). See
/// migrations/0010_find_my_device.sql.
#[derive(sqlx::FromRow, Clone)]
pub struct DeviceCommand {
    pub id: i64,
    pub device_id: i64,
    pub command: String,
    pub requested_at: String,
    pub delivered_at: Option<String>,
    pub acknowledged_at: Option<String>,
    pub result: Option<String>,
}

/// One entry in a device's self-reported installed-app list, used to build
/// the admin UI's allowlist checkboxes from real data instead of asking a
/// parent to type raw Android package names.
///
/// `preinstalled` mirrors the client's own `ApplicationInfo.FLAG_SYSTEM` check (already used
/// there to decide what's controllable at all - see `controllablePackages()`) - it's what lets
/// the unified Apps list tell "can't be uninstalled, only suspended" apps apart from ones this
/// server pushed itself. `#[serde(default)]` since old `device_status.installed_apps_json` rows
/// (an append-only log) predate this field and won't have it - they should read as `false`, not
/// fail to deserialize.
#[derive(Serialize, Deserialize, Clone)]
pub struct InstalledApp {
    pub package_name: String,
    pub label: String,
    #[serde(default)]
    pub preinstalled: bool,
    /// `InstallSourceInfo.installingPackageName` (handy step 7) - `com.android.vending` for an app
    /// from Play. Absent from older launchers.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub installer: Option<String>,
}

// ---------------------------------------------------------------------
// Device-facing API wire types (plain JSON, no wrapper envelope)
// ---------------------------------------------------------------------

#[derive(Deserialize)]
pub struct EnrollRequest {
    pub enrollment_code: String,
}

#[derive(Serialize)]
pub struct EnrollResponse {
    pub device_id: i64,
    pub device_token: String,
}

#[derive(Serialize)]
pub struct PolicyResponse {
    pub allowlist: Option<Vec<String>>,
    pub weekday_start_minutes: Option<i64>,
    pub weekday_end_minutes: Option<i64>,
    pub weekend_start_minutes: Option<i64>,
    pub weekend_end_minutes: Option<i64>,
    pub bedtime_start_minutes: Option<i64>,
    pub bedtime_end_minutes: Option<i64>,
    pub kiosk_desired: bool,
    pub lock_task_features: i64,
    pub override_pin_hash: Option<String>,
    pub override_pin_salt: Option<String>,
    pub quick_controls_mask: i64,
    pub pending_command: Option<PendingCommand>,
    /// Per-device on/off for the on-device DNS filter's VPN piece
    /// (KidVpnService) - see `AppEnforcer.applyVpnRestrictions` on the
    /// client. Defaults true; a parent can turn it off per kid from the
    /// device detail page.
    pub vpn_filter_enabled: bool,
    /// Opaque token summarizing this device's fully-resolved blocklist
    /// (global feeds + this device's overrides + global/device-scoped custom
    /// domains). The client compares this against its last-fetched value and
    /// only calls `GET /api/devices/dns-blocklist` (a potentially 100k+
    /// domain payload) when it actually changes, rather than on every
    /// 2-minute sync. See `handlers::device_api::policy`.
    pub dns_filter_version: String,
    /// Which public DoT resolver the client's on-device filter should send
    /// allowed (non-blocked) queries to - "cloudflare" or "quad9", mirrors
    /// `dns_filter_settings.upstream`.
    pub dns_upstream_provider: String,
    /// Packages the device should silently uninstall - see migrations/0014_device_pending_uninstalls.sql
    /// and `handlers::devices::toggle_tracked_app`. Populated fresh on every fetch (not a one-shot
    /// queue like `pending_command`); the row backing an entry here is only cleared once a status
    /// report confirms the package is actually gone, so the instruction survives being missed by
    /// any single sync cycle.
    pub packages_to_uninstall: Vec<String>,
    /// Calls & SMS rules - always present, with an explicit `managed` (QA blocker 3): once a
    /// launcher has had managed calls, it rejects a response without this key, so a rolled-back
    /// or buggy server can't silently unmanage calls. See `handlers::device_api::build_policy`.
    pub call_policy: CallPolicy,
    /// User restrictions while managed - always present with every switch explicit.
    pub hardening: Hardening,
    /// Launcher language and home-grid columns - always present.
    pub launcher_ui: LauncherUi,
    /// Named time rules, the daily screen-time budget and active lifts - always present; the
    /// launcher rejects a response without it once it has had one (handy step 6).
    pub time_policy: crate::time_rules::TimePolicy,
    /// When the phone takes a location fix - always present.
    pub location_policy: crate::time_rules::LocationPolicy,
    /// Whether the phone may rely on FCM nudges instead of the SSE stream (handy step 7) - always
    /// present. See `push::push_policy`.
    pub push: crate::push::PushPolicy,
    /// `LOCK_TASK_FEATURE_BLOCK_ACTIVITY_START_IN_TASK` while kiosk is on (handy step 9): the
    /// launcher ORs it in itself, together with the system helpers it pins for emergency calls,
    /// permission dialogs and pickers. A separate key (never a `lock_task_features` bit) so an
    /// older launcher without those helpers never gets it. The per-device off switch is the
    /// remote kill switch.
    pub block_activity_start: bool,
    /// Handy's own PIN lock (handy step 10): `{pin_hash, pin_salt, pin_length}`, or `null` = lock
    /// off. Always sent. A launcher without `pin_lock_v1` ignores it. Only ever in this response
    /// (CE storage on the phone) - never in a status report, a log or a page.
    pub kid_lock: Option<KidLock>,
    /// Screen timeout in seconds (migrations/0032), always sent: one of [SCREEN_TIMEOUTS]. The
    /// launcher applies it with `DevicePolicyManager.setSystemSetting(SCREEN_OFF_TIMEOUT)`; a
    /// launcher without the key in its DTO (older) ignores it, and an older server's response
    /// without it leaves the phone's setting alone.
    pub screen_timeout_seconds: i64,
    /// The update fence (handy step 11), always sent: while the launcher installs its own update
    /// every other Home app is suspended and the status bar disabled; `false` also releases a
    /// fence that is up. A launcher on a server without the key treats it as off.
    pub update_fence: bool,
    /// Notification auto-cancel (handy step 11), always sent: the launcher's listener removes
    /// other apps' nags (not allowed, not essential). Missing = off on the launcher.
    pub notification_auto_cancel: bool,
    /// The blocked-domain log (cleanup 2026-10-06), always sent: only while it is on does the
    /// launcher record and report blocked domains. Missing = off on the launcher.
    pub dns_log_enabled: bool,
}

/// `PolicyResponse.kid_lock`.
#[derive(Serialize, Clone, Debug, PartialEq, Eq)]
pub struct KidLock {
    pub pin_hash: String,
    pub pin_salt: String,
    pub pin_length: i64,
}

/// `PolicyResponse.call_policy`. With `managed = false` the launcher leaves calls alone (and
/// hands the dialer role back); the other fields are then defaults and `contacts` is empty.
#[derive(Serialize)]
pub struct CallPolicy {
    pub managed: bool,
    pub calls_enabled: bool,
    pub sms_enabled: bool,
    pub default_country_code: String,
    pub contacts: Vec<PolicyContact>,
}

/// One contact as the launcher sees it. `number` is normalised (src/phone.rs) - the launcher
/// normalises the other side of a call and compares strings. `message_app` is already resolved
/// against the device default ("none", "sms", "element" or "signal"); `message_address` is the
/// Matrix ID for "element".
#[derive(Serialize)]
pub struct PolicyContact {
    pub id: i64,
    pub name: String,
    pub number: String,
    pub inbound: bool,
    pub outbound: bool,
    pub show_on_home: bool,
    pub message_app: String,
    pub message_address: Option<String>,
    /// SHA-256 of the contact's photo (`GET /api/devices/contact-photos/{hash}`), or none.
    pub photo: Option<String>,
}

/// The oldest undelivered [DeviceCommand] for this device, if any - `policy()`
/// marks it delivered the instant it's serialized into a response, so a
/// second poll before the device acknowledges never hands out the same
/// command twice. See `handlers::device_api::policy`.
#[derive(Serialize)]
pub struct PendingCommand {
    pub id: i64,
    pub command: String,
}

#[derive(Deserialize)]
pub struct StatusReportRequest {
    pub lock_reason: String,
    pub kiosk_engaged: bool,
    pub installed_apps: Option<Vec<InstalledApp>>,
    pub app_version: Option<String>,
    pub app_version_code: Option<i64>,
    #[serde(default)]
    pub offline_override_used: bool,
    pub location: Option<LocationReport>,
    /// `"ok"` or why the launcher didn't apply a fresh policy - see
    /// migrations/0021_device_status_policy_state.sql. Absent from older launchers.
    #[serde(default)]
    pub policy_state: Option<String>,
    /// The launcher's PIN-gated "pause all restrictions" switch is on.
    #[serde(default)]
    pub restrictions_paused: bool,
    /// What this launcher can enforce, e.g. "call_policy_v1". Empty from older launchers.
    #[serde(default)]
    pub capabilities: Vec<String>,
    /// The launcher's applied call state (dialer role, restrictions, emergency calls, call-log access), stored as
    /// JSON text. Kept as an opaque value so a newer launcher's extra fields aren't lost.
    #[serde(default)]
    pub call_state: Option<serde_json::Value>,
    /// The launcher's notification listener (app badges) has access. Absent from older launchers.
    #[serde(default)]
    pub notification_listener_enabled: Option<bool>,
    /// Active rule, screen time used/budget, lifts in force (handy step 6) - stored as JSON text,
    /// opaque like `call_state`.
    #[serde(default)]
    pub time_state: Option<serde_json::Value>,
    /// FCM token, transport, last nudge (handy step 7) - see `push::PushReport`. Kept raw so the
    /// whole object can be stored capped, like `time_state`.
    #[serde(default)]
    pub push: Option<serde_json::Value>,
    /// Play install mode: `{until_ms}` while active.
    #[serde(default)]
    pub install_mode: Option<InstallModeReport>,
    /// The nightly Play update window is in force (Play Store unsuspended, screen off).
    #[serde(default)]
    pub play_window_active: bool,
    /// `false` when the platform refused to suspend the Play Store (it is the package verifier
    /// on GMS phones) - Play is then blocked only while kiosk is on (handy step 9).
    #[serde(default)]
    pub play_store_suspendable: Option<bool>,
    /// Handy's own PIN lock (handy step 10): `{active, inactive, locked, failures,
    /// backoff_until_ms, ...}` - opaque, stored capped. Never unlock times.
    #[serde(default)]
    pub lock_state: Option<serde_json::Value>,
    /// The screen timeout the phone has now (read back after applying the policy's), seconds.
    #[serde(default)]
    pub screen_timeout_seconds: Option<i64>,
    /// The update fence and the pending launcher update (handy step 11) - opaque, stored
    /// re-serialized and capped (`kiosk_escapes::sanitize_update_fence`).
    #[serde(default)]
    pub update_fence: Option<serde_json::Value>,
    /// What the notification rule removed since the last report (handy step 11) - package and
    /// channel ids with counts, stored capped (`kiosk_escapes::sanitize_notification_cancels`).
    #[serde(default)]
    pub notification_cancels: Option<serde_json::Value>,
}

/// `StatusReportRequest.install_mode`.
#[derive(Deserialize, Debug, Clone, Copy)]
pub struct InstallModeReport {
    pub until_ms: i64,
}

/// Attached to a status report whenever the device has a location reading
/// available - on every regular heartbeat, not just after a `locate` command
/// (see kids-launcher-mdm's `MdmSyncWorker`) - so the trail on the admin map
/// stays reasonably fresh without needing repeated explicit requests.
#[derive(Deserialize)]
pub struct LocationReport {
    pub latitude: f64,
    pub longitude: f64,
    pub accuracy_meters: Option<f64>,
    pub captured_at: String,
}

#[derive(Deserialize)]
pub struct CommandResultRequest {
    pub command_id: i64,
    pub success: bool,
    pub message: Option<String>,
}

/// Reported by the device during a tracked app's download, or once if the install ultimately
/// fails - see migrations/0019_device_install_progress.sql, 0020_device_install_failed.sql, and
/// `handlers::device_api::install_progress`. `percent` is meaningless when `failed` is true (the
/// device sends 0); kept as a plain required field rather than `Option` since every call site
/// already has a value on hand either way.
#[derive(Deserialize)]
pub struct InstallProgressReport {
    pub tracked_app_id: i64,
    pub percent: i64,
    #[serde(default)]
    pub failed: bool,
}

/// One blocked-domain event as reported by the device - see
/// `POST /api/devices/dns-events` and migrations/0011_client_side_dns_filtering.sql.
/// The client already knows which category caused the block (it evaluated
/// the domain against its own locally-cached, categorized list), so this
/// carries that through rather than the server re-deriving it.
#[derive(Deserialize)]
pub struct DnsEventReport {
    pub domain: String,
    pub category: String,
    pub blocked_at: String,
}

/// Response body for `GET /api/devices/dns-blocklist` - this device's fully
/// resolved effective blocklist, grouped by category rather than a flat
/// domain->category map, since at ~100k+ domains a flat JSON object would
/// repeat far more per-key overhead (quoted key + colon per domain) than
/// writing the category name once per group.
#[derive(Serialize)]
pub struct DnsBlocklistCategory {
    pub category: String,
    pub domains: Vec<String>,
}

#[derive(Serialize)]
pub struct TrackedAppUpdate {
    pub id: i64,
    /// The admin-facing name from the Apps catalog (e.g. "Tailscale") - used client-side for the
    /// install-progress notification, since [package_name] can no longer be relied on to be
    /// present or human-meaningful.
    pub name: String,
    /// Kept for the admin's own reference and for backward compat with
    /// already-installed older client builds that still key their local
    /// install-state tracking off it - no longer load-bearing on the server
    /// side, and may be empty (see tracked_apps_add.html - typing a real
    /// Android package name is optional now). A current client keys off
    /// [id]/[is_launcher] instead - see kids-launcher-mdm's
    /// `MdmSyncWorker.checkForTrackedAppUpdates`.
    pub package_name: String,
    pub release_tag: String,
    pub download_url: String,
    pub is_launcher: bool,
}
