use askama::Template;
use axum::Form;
use axum::extract::{Path, State};
use axum::http::StatusCode;
use axum::response::{Html, IntoResponse, Response};
use qrcode::QrCode;
use qrcode::render::svg;
use serde::{Deserialize, Serialize};

use crate::AppState;
use crate::config::ForkConfig;
use crate::display_time::Zone;
use crate::enrollment::Kind;
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

/// The phones' server address for the setup QR: the one saved on Settings › Phone Setup, else
/// `.env`'s `PHONE_SERVER_URL` (`ForkConfig::phone_server_url`); "" when neither is set.
pub(crate) fn server_url<'a>(
    settings: &'a ProvisioningSettings,
    config: &'a ForkConfig,
) -> &'a str {
    if settings.server_url.is_empty() {
        config.phone_server_url.as_deref().unwrap_or("")
    } else {
        &settings.server_url
    }
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

/// The longest Wi-Fi network name (32 bytes, IEEE 802.11) and the WPA passphrase's length (8-63
/// characters; or the raw key as 64 hex digits, which Android's provisioning takes as the PSK): a
/// field outside them can't be a real network, and could overflow the QR code.
const MAX_SSID_BYTES: usize = 32;
const MIN_WIFI_PASSWORD_CHARS: usize = 8;
const MAX_WIFI_PASSWORD_CHARS: usize = 63;

/// Connect (design 31 §3.7): steps 2 and 3 of Add Phone, and "Set Up with QR Code" on Advanced.
#[derive(Template)]
#[template(path = "provision_qr.html")]
struct ProvisionQrTemplate {
    title: String,
    device: Device,
    /// The phone has connected before: a new code cuts that connection off.
    enrolled: bool,
    /// Empty until the form is sent (and when `missing_checksum` is set) - no QR is rendered then.
    qr_svg: String,
    missing_server_url: bool,
    /// `LAUNCHER_SIGNATURE_CHECKSUM` isn't configured: "Server setup needed" instead of a QR code,
    /// rather than falling back to any built-in (upstream's) checksum.
    missing_checksum: bool,
    wifi_ssid: String,
    /// When the code in the QR expires (local, `Zone::until`), once it is made.
    until: Option<String>,
    /// Until when typed codes are paused (local), for the Code group's warning.
    paused_until: Option<String>,
    /// A refused Wi-Fi field ("wifi_ssid" / "wifi_password") and why; nothing was made then.
    error_field: &'static str,
    error: Option<&'static str>,
    /// A refused form carried a password, which is never echoed back.
    password_dropped: bool,
    qr_minutes: i64,
    code_minutes: i64,
    /// The Phone Setup zone and the time the page was built, for its "until"s.
    zone: Zone,
    now: chrono::DateTime<chrono::Utc>,
}

impl ProvisionQrTemplate {
    async fn new(state: &AppState, device: Device, settings: &ProvisioningSettings) -> Self {
        let zone = Zone::load(&state.db).await;
        let now = chrono::Utc::now();
        ProvisionQrTemplate {
            title: format!("Connect {}", device.name),
            enrolled: device.enrolled_at.is_some(),
            device,
            qr_svg: String::new(),
            missing_server_url: server_url(settings, &state.config).is_empty(),
            missing_checksum: state.config.launcher_signature_checksum.is_none(),
            wifi_ssid: String::new(),
            until: None,
            paused_until: state
                .enrollment
                .typed_paused_until(now)
                .map(|until| zone.until(until, now)),
            error_field: "",
            error: None,
            password_dropped: false,
            qr_minutes: Kind::Qr.minutes(),
            code_minutes: Kind::Typed.minutes(),
            zone,
            now,
        }
    }

    fn page(self, status: StatusCode) -> Response {
        match self.render() {
            Ok(html) => (status, Html(html)).into_response(),
            Err(err) => {
                tracing::error!(%err, "couldn't render the Connect page");
                StatusCode::INTERNAL_SERVER_ERROR.into_response()
            }
        }
    }
}

#[derive(Deserialize, Default)]
pub struct ProvisionForm {
    #[serde(default)]
    wifi_ssid: String,
    #[serde(default)]
    wifi_password: String,
}

/// The device and the provisioning settings: 404 for an unknown phone, 500 (logged) when either
/// can't be read - never a page or a QR code built on made-up defaults.
async fn load(state: &AppState, id: i64) -> Result<(Device, ProvisioningSettings), StatusCode> {
    let failed = |err: sqlx::Error| {
        tracing::error!(device_id = id, %err, "couldn't read the Connect page's data");
        StatusCode::INTERNAL_SERVER_ERROR
    };
    let device = sqlx::query_as::<_, Device>("SELECT * FROM devices WHERE id = ?")
        .bind(id)
        .fetch_optional(&state.db)
        .await
        .map_err(failed)?
        .ok_or(StatusCode::NOT_FOUND)?;
    let settings = sqlx::query_as::<_, ProvisioningSettings>(
        "SELECT * FROM provisioning_settings WHERE id = 1",
    )
    .fetch_optional(&state.db)
    .await
    .map_err(failed)?
    .unwrap_or_default();
    Ok((device, settings))
}

/// `GET /devices/{id}/provision`: the two ways to connect. No code is made by looking at the page
/// (design 22 §0 #7): "Make QR Code" posts the form ([provision_qr]), "New Setup Code" posts to
/// `regenerate-code`.
pub async fn provision_form(State(state): State<AppState>, Path(id): Path<i64>) -> Response {
    let (device, settings) = match load(&state, id).await {
        Ok(loaded) => loaded,
        Err(status) => return status.into_response(),
    };
    ProvisionQrTemplate::new(&state, device, &settings)
        .await
        .page(StatusCode::OK)
}

/// A Wi-Fi field that can't go into the QR code: the field and why.
pub(crate) fn wifi_refusal(ssid: &str, password: &str) -> Option<(&'static str, &'static str)> {
    if ssid.len() > MAX_SSID_BYTES {
        return Some((
            "wifi_ssid",
            "Too long for a Wi-Fi network: shorten the name (at most 32 bytes; æ, ø and å count \
             as two). Nothing was made.",
        ));
    }
    let raw_key = password.len() == 64 && password.chars().all(|c| c.is_ascii_hexdigit());
    let chars = password.chars().count();
    if !password.is_empty() && !raw_key && chars < MIN_WIFI_PASSWORD_CHARS {
        return Some((
            "wifi_password",
            "A Wi-Fi password has at least 8 characters. Nothing was made.",
        ));
    }
    if !raw_key && chars > MAX_WIFI_PASSWORD_CHARS {
        return Some((
            "wifi_password",
            "A Wi-Fi password has at most 63 characters (or is a 64-digit key). Nothing was made.",
        ));
    }
    if ssid.is_empty() && !password.is_empty() {
        return Some((
            "wifi_ssid",
            "Add the network's name, or leave the password empty. Nothing was made.",
        ));
    }
    None
}

/// `POST /devices/{id}/provision`: a fresh setup-QR enrollment code (design 22 §3.1: 130 bits,
/// valid 30 minutes, once; it replaces the device's live code) in a QR code with the provisioning
/// settings and the optional Wi-Fi, shown in the QR group (the form posts to `#qr`). A POST, so
/// the Wi-Fi password never sits in a URL (proxy logs, history); the page is `no-store`
/// (`admin_auth::no_store_path`). A Wi-Fi field that can't be right is refused before any code
/// is made (400, the reason under the field, the password never echoed).
pub async fn provision_qr(
    State(state): State<AppState>,
    Path(id): Path<i64>,
    Form(form): Form<ProvisionForm>,
) -> Response {
    let (device, settings) = match load(&state, id).await {
        Ok(loaded) => loaded,
        Err(status) => return status.into_response(),
    };
    let ssid = form.wifi_ssid.trim();
    let password = form.wifi_password.trim();
    let mut page = ProvisionQrTemplate::new(&state, device, &settings).await;

    if let Some((field, why)) = wifi_refusal(ssid, password) {
        page.error_field = field;
        page.error = Some(why);
        page.password_dropped = !password.is_empty();
        // The name isn't a secret: typed back (escaped, cut to a sane length) to be shortened.
        page.wifi_ssid = ssid.chars().take(MAX_SSID_BYTES * 4).collect();
        return page.page(StatusCode::BAD_REQUEST);
    }
    page.wifi_ssid = ssid.to_string();
    // No code at all without a checksum: there would be no QR code to carry it.
    if page.missing_checksum {
        return page.page(StatusCode::OK);
    }
    let wifi = (!ssid.is_empty()).then(|| (ssid.to_string(), password.to_string()));
    let payload_for = |code: String| {
        provisioning_payload(
            &state.config,
            AdminExtras {
                server_url: server_url(&settings, &state.config).to_string(),
                tailscale_auth_key: settings.tailscale_auth_key.clone(),
                enrollment_code: code,
            },
            wifi.clone(),
            &settings.locale,
            &settings.time_zone,
        )
    };
    // The fit is checked before a code is made (qa-31-s15-code L1): an overflow must never
    // replace the phone's live code. A placeholder of the real length in the code's worst case
    // for the QR's encoder (letters, no digits); a real code is never larger.
    let fits = |payload: &serde_json::Value| QrCode::new(payload.to_string().as_bytes()).is_ok();
    if !payload_for("A".repeat(crate::enrollment::QR_LEN)).is_some_and(|p| fits(&p)) {
        tracing::error!(
            device_id = id,
            "the provisioning payload doesn't fit a QR code"
        );
        return too_long();
    }

    let (code, expires_at) = match crate::enrollment::new_code(&state, id, Kind::Qr).await {
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

    let Some(payload) = payload_for(code) else {
        return page.page(StatusCode::OK);
    };
    let Ok(qr) = QrCode::new(payload.to_string().as_bytes()) else {
        tracing::error!(
            device_id = id,
            "the provisioning payload doesn't fit a QR code"
        );
        return too_long();
    };
    page.qr_svg = qr
        .render()
        .min_dimensions(320, 320)
        .dark_color(svg::Color("#000000"))
        .light_color(svg::Color("#ffffff"))
        .build();
    page.until = Some(page.zone.until_db(&expires_at, page.now));
    page.page(StatusCode::OK)
}

/// A QR code holds about 2.9 kB: only a very long server address or Tailscale key overflows it.
fn too_long() -> Response {
    (
        StatusCode::INTERNAL_SERVER_ERROR,
        "The setup details are too long for a QR code - check the server address and Tailscale \
         key in Settings › Phone Setup. Nothing was changed.",
    )
        .into_response()
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
