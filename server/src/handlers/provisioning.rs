use askama::Template;
use axum::extract::{Path, Query, State};
use axum::response::{Html, IntoResponse};
use qrcode::QrCode;
use qrcode::render::svg;
use serde::{Deserialize, Serialize};

use crate::AppState;
use crate::config::ForkConfig;
use crate::models::{Device, ProvisioningSettings};

/// android.app.extra.PROVISIONING_ADMIN_EXTRAS_BUNDLE's contents - Android's own
/// documented mechanism for passing arbitrary DPC-defined data through zero-touch/QR/NFC
/// provisioning untouched. Field names here are this project's own choice (unlike the
/// top-level PROVISIONING_* keys, which are Android's), read back on the client via
/// `ProvisioningExtras.fromBundle`/`fromJson` - keep both sides in sync if these change.
#[derive(Serialize)]
pub(crate) struct AdminExtras {
    pub(crate) server_url: String,
    pub(crate) tailscale_auth_key: String,
    pub(crate) enrollment_code: String,
}

#[derive(Serialize)]
struct ProvisioningPayload {
    #[serde(rename = "android.app.extra.PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME")]
    admin_component: String,
    #[serde(rename = "android.app.extra.PROVISIONING_DEVICE_ADMIN_SIGNATURE_CHECKSUM")]
    signature_checksum: String,
    #[serde(rename = "android.app.extra.PROVISIONING_DEVICE_ADMIN_PACKAGE_DOWNLOAD_LOCATION")]
    download_location: String,
    #[serde(
        rename = "android.app.extra.PROVISIONING_WIFI_SSID",
        skip_serializing_if = "Option::is_none"
    )]
    wifi_ssid: Option<String>,
    #[serde(
        rename = "android.app.extra.PROVISIONING_WIFI_PASSWORD",
        skip_serializing_if = "Option::is_none"
    )]
    wifi_password: Option<String>,
    #[serde(
        rename = "android.app.extra.PROVISIONING_WIFI_SECURITY_TYPE",
        skip_serializing_if = "Option::is_none"
    )]
    wifi_security_type: Option<&'static str>,
    /// "xx_YY" - `DevicePolicyManager.EXTRA_PROVISIONING_LOCALE` ("Format: xx_yy, where xx is
    /// the language code, and yy the country code"; device owner provisioning, "can also be used
    /// for QR code provisioning"). Omitted when not configured.
    #[serde(
        rename = "android.app.extra.PROVISIONING_LOCALE",
        skip_serializing_if = "Option::is_none"
    )]
    locale: Option<String>,
    /// An IANA zone id - `DevicePolicyManager.EXTRA_PROVISIONING_TIME_ZONE` (same scope).
    #[serde(
        rename = "android.app.extra.PROVISIONING_TIME_ZONE",
        skip_serializing_if = "Option::is_none"
    )]
    time_zone: Option<String>,
    #[serde(rename = "android.app.extra.PROVISIONING_ADMIN_EXTRAS_BUNDLE")]
    admin_extras: AdminExtras,
}

/// A locale for `PROVISIONING_LOCALE`: "xx_YY" (2-3 lowercase letters, `_`, 2 uppercase).
pub(crate) fn valid_locale(locale: &str) -> bool {
    match locale.split_once('_') {
        Some((lang, country)) => {
            (2..=3).contains(&lang.len())
                && lang.chars().all(|c| c.is_ascii_lowercase())
                && country.len() == 2
                && country.chars().all(|c| c.is_ascii_uppercase())
        }
        None => false,
    }
}

/// An IANA time zone id for `PROVISIONING_TIME_ZONE` ("Europe/Oslo", "UTC"): letters, digits,
/// `/ _ + -`, at most 64 characters, starting with a letter.
pub(crate) fn valid_time_zone(zone: &str) -> bool {
    !zone.is_empty()
        && zone.len() <= 64
        && zone.starts_with(|c: char| c.is_ascii_alphabetic())
        && zone
            .chars()
            .all(|c| c.is_ascii_alphanumeric() || "/_+-".contains(c))
}

#[derive(Template)]
#[template(path = "provision_qr.html")]
struct ProvisionQrTemplate {
    title: String,
    device: Device,
    /// Empty when `missing_checksum` is set - no QR is rendered at all then.
    qr_svg: String,
    missing_server_url: bool,
    /// `LAUNCHER_SIGNATURE_CHECKSUM` isn't configured: the page shows a banner instead of a QR
    /// code, rather than falling back to any built-in (upstream's) checksum.
    missing_checksum: bool,
    wifi_ssid: String,
}

#[derive(Deserialize, Default)]
pub struct ProvisionQueryParams {
    #[serde(default)]
    wifi_ssid: String,
    #[serde(default)]
    wifi_password: String,
}

/// Regenerates this device's enrollment code every time the page loads (same "always fresh"
/// treatment `regenerate_code` already gives the plain-code flow) - simpler than reasoning about
/// whether a previously-generated code is still unexpired, and this page has no reason to prefer
/// reusing an old one. WiFi fields come in as query params from a plain GET form on the page
/// itself (not a separate POST route) so filling them in and regenerating stays one handler.
pub async fn provision_form(
    State(state): State<AppState>,
    Path(id): Path<i64>,
    Query(params): Query<ProvisionQueryParams>,
) -> impl IntoResponse {
    let code = crate::security::generate_enrollment_code();
    sqlx::query(
        "UPDATE devices SET enrollment_code = ?, \
         enrollment_code_expires_at = datetime('now', '+30 minutes') WHERE id = ?",
    )
    .bind(&code)
    .bind(id)
    .execute(&state.db)
    .await
    .ok();

    let device = sqlx::query_as::<_, Device>("SELECT * FROM devices WHERE id = ?")
        .bind(id)
        .fetch_one(&state.db)
        .await
        .expect("device must exist to provision it");

    let settings = sqlx::query_as::<_, ProvisioningSettings>(
        "SELECT * FROM provisioning_settings WHERE id = 1",
    )
    .fetch_optional(&state.db)
    .await
    .ok()
    .flatten()
    .unwrap_or_default();

    let ssid = params.wifi_ssid.trim();
    let password = params.wifi_password.trim();
    let wifi = (!ssid.is_empty()).then(|| (ssid.to_string(), password.to_string()));

    let payload = provisioning_payload(
        &state.config,
        AdminExtras {
            server_url: settings.server_url.clone(),
            tailscale_auth_key: settings.tailscale_auth_key,
            enrollment_code: code,
        },
        wifi,
        &settings.locale,
        &settings.time_zone,
    );

    let qr_svg = match &payload {
        Some(payload) => {
            let json = payload.to_string();
            QrCode::new(json.as_bytes())
                .expect("provisioning payload always fits a QR code")
                .render()
                .min_dimensions(320, 320)
                .dark_color(svg::Color("#000000"))
                .light_color(svg::Color("#ffffff"))
                .build()
        }
        None => String::new(),
    };

    Html(
        ProvisionQrTemplate {
            title: format!("Provision {}", device.name),
            missing_server_url: settings.server_url.is_empty(),
            missing_checksum: payload.is_none(),
            wifi_ssid: ssid.to_string(),
            device,
            qr_svg,
        }
        .render()
        .unwrap(),
    )
}

/// Android's standard zero-touch Device Owner provisioning flow: on a
/// factory-reset device, tap the Welcome screen 6 times in the same spot,
/// then scan a QR code encoding this JSON payload. Works on any Android
/// device whose setup wizard implements the standard `ManagedProvisioning`
/// hand-off (stock Android, most custom ROMs) - notably, as of this writing,
/// NOT on GrapheneOS, whose own setup wizard has no `ManagedProvisioning`
/// trigger of any kind (QR or NFC) built in yet - see
/// github.com/GrapheneOS/platform_packages_apps_SetupWizard2/pull/40
/// (open, unmerged). Built anyway so it works everywhere else already, and
/// on GrapheneOS the moment that PR (or equivalent) lands.
///
/// The same QR/JSON is also read by the launcher's own in-app "Scan setup QR" flow
/// (`ui/settings/launcher/SettingsFragmentLauncher` client-side) for exactly the
/// GrapheneOS case above - once Device Owner is granted some other way
/// (currently only `adb shell dpm set-device-owner`), scanning this same
/// code applies `admin_extras`'s three fields and enrolls, collapsing what
/// would otherwise be three manual Settings entries into one scan. The
/// native ManagedProvisioning path ignores `PROVISIONING_ADMIN_EXTRAS_BUNDLE`'s
/// contents entirely and just hands it to the app unopened via
/// `DeviceAdminReceiver.onProfileProvisioningComplete` - see
/// `MdmDeviceAdminReceiver.kt` on the client for that side.
///
/// The admin component, signature checksum and download URL come from `config::ForkConfig`
/// (env vars, defaulting to our own fork) - they only change if the receiver class is renamed or
/// the signing key is ever rotated, not per release. The default download URL is the
/// `palchrb/vibb-launcher` monorepo's `releases/latest/download/kids-launcher-mdm.apk`, a stable
/// asset name on every normal launcher release (server releases are never "latest"), so this QR code stays valid across releases with nothing to
/// regenerate.
///
/// The signature checksum is the SHA-256 digest of the launcher's signing *certificate* (not of
/// the APK), base64url-encoded with no padding - the format
/// `PROVISIONING_DEVICE_ADMIN_SIGNATURE_CHECKSUM` requires. It has no default on purpose: see
/// `ForkConfig`'s doc comment and DEPLOY.md for how to compute it.
///
/// The JSON the provisioning QR encodes, or `None` when no launcher signing checksum is
/// configured - there is deliberately no fallback value (see `config::ForkConfig`). `wifi` is
/// `(ssid, password)`; an empty password means an open network. `locale`/`time_zone` (the
/// provisioning settings, migrations/0034) set the phone's language and zone during QR setup;
/// an empty or invalid one is left out.
pub(crate) fn provisioning_payload(
    config: &ForkConfig,
    admin_extras: AdminExtras,
    wifi: Option<(String, String)>,
    locale: &str,
    time_zone: &str,
) -> Option<serde_json::Value> {
    let signature_checksum = config.launcher_signature_checksum.clone()?;
    let (wifi_ssid, wifi_password) = match wifi {
        Some((ssid, password)) if !password.is_empty() => (Some(ssid), Some(password)),
        Some((ssid, _)) => (Some(ssid), None),
        None => (None, None),
    };
    let payload = ProvisioningPayload {
        admin_component: config.launcher_admin_component.clone(),
        signature_checksum,
        download_location: config.launcher_apk_url.clone(),
        wifi_security_type: wifi_password.as_ref().map(|_| "WPA"),
        wifi_ssid,
        wifi_password,
        locale: valid_locale(locale).then(|| locale.to_string()),
        time_zone: valid_time_zone(time_zone).then(|| time_zone.to_string()),
        admin_extras,
    };
    Some(serde_json::to_value(&payload).expect("provisioning payload always serializes"))
}
