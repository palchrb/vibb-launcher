//! The provisioning QR page. Admin handlers are called directly with `TestApp::state`, since the
//! admin session/2FA middleware isn't what these tests are about.

use std::sync::Arc;

use axum::extract::{Path, Query, State};
use axum::response::IntoResponse;

use super::{TestApp, read_response};
use crate::AppState;
use crate::config::ForkConfig;
use crate::handlers::provisioning::{self, AdminExtras};

async fn provision_page(state: AppState, device_id: i64) -> String {
    let response =
        provisioning::provision_form(State(state), Path(device_id), Query(Default::default()))
            .await
            .into_response();
    let res = read_response(response).await;
    assert!(res.status.is_success(), "provision page: {}", res.status);
    res.text()
}

fn extras() -> AdminExtras {
    AdminExtras {
        server_url: "https://pi.example.ts.net".to_string(),
        tailscale_auth_key: "tskey-auth-x".to_string(),
        enrollment_code: "ABC123".to_string(),
    }
}

#[tokio::test]
async fn provision_page_without_checksum_has_no_qr() {
    let app = TestApp::new().await;
    let (id, _) = app.create_device("phone").await;
    let mut state = app.state.clone();
    let mut config = (*state.config).clone();
    config.launcher_signature_checksum = None;
    state.config = Arc::new(config);

    let page = provision_page(state, id).await;
    assert!(page.contains("signing checksum not configured"), "{page}");
    assert!(
        !page.contains("class=\"qr-code\""),
        "a QR code was rendered"
    );
    // Upstream's checksum must never come back as a fallback.
    assert!(!page.contains("TLXcVaskQBZyh0S88O29PvHa9RaiCCl-7TybpGlbmkg"));
}

#[tokio::test]
async fn provision_page_with_checksum_shows_qr() {
    let app = TestApp::new().await;
    let (id, _) = app.create_device("phone").await;
    let page = provision_page(app.state.clone(), id).await;
    assert!(page.contains("class=\"qr-code\""), "no QR code rendered");
    assert!(!page.contains("signing checksum not configured"));
}

#[test]
fn provision_payload_uses_configured_values() {
    let config = ForkConfig {
        server_release_repo: "someone/kid-phone-server".to_string(),
        launcher_admin_component: "org.example/org.example.Admin".to_string(),
        launcher_apk_url: "https://example.org/launcher.apk".to_string(),
        launcher_signature_checksum: Some("B".repeat(43)),
        sse_keepalive_secs: 120,
    };
    let payload = provisioning::provisioning_payload(
        &config,
        extras(),
        Some(("homewifi".to_string(), "secret".to_string())),
    )
    .expect("payload with a checksum configured");

    assert_eq!(
        payload["android.app.extra.PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME"],
        "org.example/org.example.Admin"
    );
    assert_eq!(
        payload["android.app.extra.PROVISIONING_DEVICE_ADMIN_SIGNATURE_CHECKSUM"],
        "B".repeat(43)
    );
    assert_eq!(
        payload["android.app.extra.PROVISIONING_DEVICE_ADMIN_PACKAGE_DOWNLOAD_LOCATION"],
        "https://example.org/launcher.apk"
    );
    assert_eq!(
        payload["android.app.extra.PROVISIONING_WIFI_SSID"],
        "homewifi"
    );
    assert_eq!(
        payload["android.app.extra.PROVISIONING_WIFI_PASSWORD"],
        "secret"
    );
    assert_eq!(
        payload["android.app.extra.PROVISIONING_WIFI_SECURITY_TYPE"],
        "WPA"
    );
    let extras = &payload["android.app.extra.PROVISIONING_ADMIN_EXTRAS_BUNDLE"];
    assert_eq!(extras["server_url"], "https://pi.example.ts.net");
    assert_eq!(extras["tailscale_auth_key"], "tskey-auth-x");
    assert_eq!(extras["enrollment_code"], "ABC123");
}

#[test]
fn provision_payload_defaults_and_wifi_handling() {
    let config = ForkConfig::for_tests();
    let open_wifi = provisioning::provisioning_payload(
        &config,
        extras(),
        Some(("cafe".to_string(), String::new())),
    )
    .unwrap();
    assert_eq!(
        open_wifi["android.app.extra.PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME"],
        "com.kidslauncher.mdm/com.kidslauncher.mdm.server.MdmDeviceAdminReceiver"
    );
    assert_eq!(
        open_wifi["android.app.extra.PROVISIONING_DEVICE_ADMIN_PACKAGE_DOWNLOAD_LOCATION"],
        "https://github.com/palchrb/vibb-launcher/releases/latest/download/kids-launcher-mdm.apk"
    );
    assert_eq!(
        open_wifi["android.app.extra.PROVISIONING_WIFI_SSID"],
        "cafe"
    );
    assert!(
        open_wifi
            .get("android.app.extra.PROVISIONING_WIFI_PASSWORD")
            .is_none()
    );
    assert!(
        open_wifi
            .get("android.app.extra.PROVISIONING_WIFI_SECURITY_TYPE")
            .is_none()
    );

    let no_wifi = provisioning::provisioning_payload(&config, extras(), None).unwrap();
    assert!(
        no_wifi
            .get("android.app.extra.PROVISIONING_WIFI_SSID")
            .is_none()
    );

    let mut unconfigured = config.clone();
    unconfigured.launcher_signature_checksum = None;
    assert!(provisioning::provisioning_payload(&unconfigured, extras(), None).is_none());
}

#[test]
fn reinstall_hint_uses_configured_repo() {
    let hint = crate::security::reinstall_hint("someone/vibb-launcher");
    assert!(hint.contains(
        "raw.githubusercontent.com/someone/vibb-launcher/master/server/deploy/install.sh"
    ));
    assert!(hint.contains("KPS_REPO=someone/vibb-launcher"));
}
