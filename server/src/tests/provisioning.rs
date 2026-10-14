//! The provisioning QR page and the Add Phone flow (design 31 step 15). Some admin handlers are
//! called directly with `TestApp::state`, to change the configuration.

use std::sync::Arc;

use axum::Form;
use axum::extract::{Path, State};
use axum::http::{Method, StatusCode, header};
use axum::response::IntoResponse;

use super::{TestApp, assert_redirect, read_response, settings_pages};
use crate::AppState;
use crate::config::ForkConfig;
use crate::handlers::provisioning::{self, AdminExtras};

/// The page after "Make QR code" (a POST since design 22).
async fn provision_page(state: AppState, device_id: i64) -> String {
    let response =
        provisioning::provision_qr(State(state), Path(device_id), Form(Default::default()))
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

    let before = code_hash(&app, id).await;
    let page = provision_page(state, id).await;
    // The POST made no code: the phone's live one still works (qa-31-s15-code L3).
    assert_eq!(code_hash(&app, id).await, before);
    assert!(page.contains("data-missing=\"checksum\""), "{page}");
    assert!(!page.contains("data-row=\"qr\""), "a QR code was rendered");
    // Upstream's checksum must never come back as a fallback.
    assert!(!page.contains("TLXcVaskQBZyh0S88O29PvHa9RaiCCl-7TybpGlbmkg"));
}

#[tokio::test]
async fn provision_page_with_checksum_shows_qr() {
    let app = TestApp::new().await;
    let (id, _) = app.create_device("phone").await;
    let page = provision_page(app.state.clone(), id).await;
    assert!(page.contains("data-row=\"qr\""), "no QR code rendered");
    assert!(!page.contains("data-missing=\"checksum\""));
    // The launcher's name (design 34 P11, qa-34-s12-code L1).
    assert!(
        page.contains("You type it in Vibb One's settings on the phone."),
        "{page}"
    );
    assert!(page.contains("make Vibb One the device owner"), "{page}");
    assert!(
        !page.contains("Vibb&#39;s") && !page.contains("Vibb's"),
        "{page}"
    );
}

#[test]
fn provision_payload_uses_configured_values() {
    let config = ForkConfig {
        server_release_repo: "someone/kid-phone-server".to_string(),
        launcher_admin_component: "org.example/org.example.Admin".to_string(),
        launcher_apk_url: "https://example.org/launcher.apk".to_string(),
        launcher_signature_checksum: Some("B".repeat(43)),
        sse_keepalive_secs: 120,
        github_api: "https://api.github.com".to_string(),
    };
    let payload = provisioning::provisioning_payload(
        &config,
        extras(),
        Some(("homewifi".to_string(), "secret".to_string())),
        "nb_NO",
        "Europe/Oslo",
    )
    .expect("payload with a checksum configured");
    assert_eq!(payload["android.app.extra.PROVISIONING_LOCALE"], "nb_NO");
    assert_eq!(
        payload["android.app.extra.PROVISIONING_TIME_ZONE"],
        "Europe/Oslo"
    );

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
        "",
        "",
    )
    .unwrap();
    // Not configured: no locale or zone in the QR (the phone keeps its own).
    assert!(
        open_wifi
            .get("android.app.extra.PROVISIONING_LOCALE")
            .is_none()
    );
    assert!(
        open_wifi
            .get("android.app.extra.PROVISIONING_TIME_ZONE")
            .is_none()
    );
    assert_eq!(
        open_wifi["android.app.extra.PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME"],
        "me.vibb.launcher/com.kidslauncher.mdm.server.MdmDeviceAdminReceiver"
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

    let no_wifi =
        provisioning::provisioning_payload(&config, extras(), None, "en", "Europe/../x").unwrap();
    // Malformed values never reach the QR.
    assert!(
        no_wifi
            .get("android.app.extra.PROVISIONING_LOCALE")
            .is_none()
    );
    assert!(
        no_wifi
            .get("android.app.extra.PROVISIONING_TIME_ZONE")
            .is_none()
    );
    assert!(
        no_wifi
            .get("android.app.extra.PROVISIONING_WIFI_SSID")
            .is_none()
    );

    let mut unconfigured = config.clone();
    unconfigured.launcher_signature_checksum = None;
    assert!(provisioning::provisioning_payload(&unconfigured, extras(), None, "", "").is_none());
}

#[test]
fn reinstall_hint_uses_configured_repo() {
    let hint = crate::security::reinstall_hint("someone/vibb-launcher");
    assert!(
        hint.contains(
            "raw.githubusercontent.com/someone/vibb-launcher/main/server/deploy/install.sh"
        )
    );
    assert!(hint.contains("KPS_REPO=someone/vibb-launcher"));
    // Security review L10: never piped into a root shell.
    assert!(!hint.contains("| sudo"), "{hint}");
    assert!(hint.ends_with("bash kps-install.sh"), "{hint}");
}

#[test]
fn locale_and_time_zone_validation() {
    for good in ["nb_NO", "en_US", "nn_NO", "fil_PH"] {
        assert!(provisioning::valid_locale(good), "{good}");
    }
    for bad in [
        "", "nb", "NB_no", "nb-NO", "nb_NOR", "n_NO", "nb_NO\"", "nb_NO,x",
    ] {
        assert!(!provisioning::valid_locale(bad), "{bad}");
    }
    for good in [
        "Europe/Oslo",
        "UTC",
        "America/Argentina/Buenos_Aires",
        "Etc/GMT+1",
    ] {
        assert!(provisioning::valid_time_zone(good), "{good}");
    }
    for bad in [
        "",
        "/Europe",
        "Europe/Oslo\"",
        "Europe Oslo",
        &"A".repeat(65),
    ] {
        assert!(!provisioning::valid_time_zone(bad), "{bad}");
    }
}

/// Defaults nb_NO / Europe/Oslo (migrations/0034), editable on the provisioning page, invalid
/// values refused without writing anything, and the device's QR page carries them.
#[tokio::test]
async fn provisioning_locale_settings() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let page = app.get_page("/settings/provisioning", &cookie).await.text();
    assert!(page.contains("value=\"nb_NO\""));
    assert!(page.contains("value=\"Europe/Oslo\""));

    let res = app
        .request_form(
            Method::POST,
            "/settings/provisioning",
            Some(&cookie),
            &[
                ("server_url", "https://x"),
                ("locale", "nb-NO"),
                ("time_zone", "Europe/Oslo"),
            ],
        )
        .await;
    assert_eq!(res.status, StatusCode::BAD_REQUEST);
    let res = app
        .request_form(
            Method::POST,
            "/settings/provisioning",
            Some(&cookie),
            &[
                ("server_url", "https://x"),
                ("locale", "en_GB"),
                ("time_zone", "Europe/London"),
            ],
        )
        .await;
    assert!(res.status.is_redirection());
    let page = app.get_page("/settings/provisioning", &cookie).await.text();
    assert!(page.contains("value=\"en_GB\""));
    assert!(page.contains("value=\"Europe/London\""));
}

// ---- Design 31 step 15: Add Phone (name → Connect → the QR code or the setup code) ----

async fn code_hash(app: &TestApp, id: i64) -> Option<String> {
    sqlx::query_scalar("SELECT enrollment_code_hash FROM devices WHERE id = ?")
        .bind(id)
        .fetch_one(&app.db)
        .await
        .unwrap()
}

async fn code_kind(app: &TestApp, id: i64) -> Option<String> {
    sqlx::query_scalar("SELECT enrollment_code_kind FROM devices WHERE id = ?")
        .bind(id)
        .fetch_one(&app.db)
        .await
        .unwrap()
}

/// §5.1's table for the add flow, built from the rendered forms: the name goes on to Connect, the
/// QR form returns to its own group (`#qr`, with the QR code in it - never the top of the page),
/// New Setup Code opens the code page (shown once, `no-store`). A form the table has no row for
/// fails.
#[tokio::test]
async fn every_form_in_the_add_flow_lands_where_it_should() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;

    let add = app.get_page("/devices/new", &cookie).await.text();
    let forms = settings_pages::forms(&add);
    assert_eq!(forms.len(), 1, "{forms:?}");
    assert_eq!(forms[0].0, "/devices/new");
    let res = app
        .request_form(
            Method::POST,
            "/devices/new",
            Some(&cookie),
            &[("name", "Anna")],
        )
        .await;
    let id: i64 = sqlx::query_scalar("SELECT id FROM devices WHERE name = 'Anna'")
        .fetch_one(&app.db)
        .await
        .unwrap();
    let connect = format!("/devices/{id}/provision");
    assert_redirect(&res, &connect, None);
    assert_eq!(code_kind(&app, id).await, None, "no code yet");

    let html = app.get_page(&connect, &cookie).await.text();
    assert!(
        html.contains("<h1 class=\"page-title\">Connect Anna</h1>"),
        "{html}"
    );
    // Not set up yet: back to the summary's Connect This Phone group, no "connected now" warning.
    assert!(html.contains(&format!("class=\"nav-back\" href=\"/devices/{id}#access\"")));
    assert!(!html.contains("data-warning=\"replaces\""));
    let qr_action = format!("/devices/{id}/provision#qr");
    let code_action = format!("/devices/{id}/regenerate-code");
    let actions: Vec<String> = settings_pages::forms(&html)
        .into_iter()
        .map(|(action, _)| action)
        .collect();
    assert_eq!(actions, [qr_action.clone(), code_action.clone()]);
    let form_at = html.find(&format!("action=\"{qr_action}\"")).unwrap();
    assert!(settings_pages::encloses(&html, "qr", form_at));

    for action in actions {
        let fields: &[(&str, &str)] = if action == qr_action {
            &[
                ("wifi_ssid", "Hjemme"),
                ("wifi_password", "not-a-real-pass"),
            ]
        } else {
            &[]
        };
        let res = app
            .request_form(Method::POST, &action, Some(&cookie), fields)
            .await;
        assert_eq!(res.status, StatusCode::OK, "{action}");
        assert_eq!(
            res.headers.get(header::CACHE_CONTROL).unwrap(),
            "no-store",
            "{action}"
        );
        let text = res.text();
        if action == qr_action {
            // The QR code is in the group the form posted to, with its end in local time.
            let qr_at = text.find("data-row=\"qr\"").unwrap();
            assert!(settings_pages::encloses(&text, "qr", qr_at));
            let until = settings_pages::part(&text, "data-row=\"until\"", "</div>");
            assert!(until.contains(", once") && until.contains(':'), "{until}");
            assert!(!until.contains("UTC"), "{until}");
            assert!(text.contains("Make a New QR Code"));
            // The network's name is kept, the password never comes back.
            assert!(text.contains("value=\"Hjemme\""));
            assert!(!text.contains("not-a-real-pass"));
            assert!(text.contains(&format!("href=\"/devices/{id}\" data-row=\"done\"")));
            assert_eq!(code_kind(&app, id).await.as_deref(), Some("qr"));
        } else {
            assert!(text.contains("class=\"enrollment-code\""));
            assert!(text.contains("<h1 class=\"page-title\">Setup Code</h1>"));
            assert!(settings_pages::forms(&text).is_empty());
            assert!(text.contains(&format!("href=\"/devices/{id}\" data-row=\"done\"")));
            let until = settings_pages::part(&text, "data-row=\"until\"", "</div>");
            assert!(!until.contains("UTC"), "{until}");
            assert_eq!(code_kind(&app, id).await.as_deref(), Some("typed"));
        }
    }
}

/// A phone that is connected: Connect and the code page go back to Advanced › Connection, say
/// that a new code cuts the phone off, and have no Done.
#[tokio::test]
async fn connecting_a_connected_phone_again_says_what_happens() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, _) = app.enrolled_device("Anna").await;
    let back = format!("class=\"nav-back\" href=\"/devices/{id}/advanced#connection\"");
    let connect = app
        .get_page(&format!("/devices/{id}/provision"), &cookie)
        .await
        .text();
    assert!(connect.contains(&back), "{connect}");
    assert!(connect.contains("data-warning=\"replaces\""));
    let code = app
        .request_form(
            Method::POST,
            &format!("/devices/{id}/regenerate-code"),
            Some(&cookie),
            &[],
        )
        .await
        .text();
    assert!(code.contains(&back), "{code}");
    assert!(code.contains("data-warning=\"replaces\""));
    assert!(!code.contains("data-row=\"done\""));
}

/// Add Phone refuses an empty or too long name in place (400, the reason under the field, what was
/// typed kept and escaped); nothing is made.
#[tokio::test]
async fn add_phone_refuses_a_bad_name_in_place() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let long = "A".repeat(crate::handlers::devices::MAX_DEVICE_NAME_CHARS + 1);
    for (name, why) in [
        ("", "Give the phone a name."),
        ("   ", "Give the phone a name."),
        (long.as_str(), "Use at most 40 characters."),
    ] {
        let res = app
            .request_form(
                Method::POST,
                "/devices/new",
                Some(&cookie),
                &[("name", name)],
            )
            .await;
        assert_eq!(res.status, StatusCode::BAD_REQUEST, "{name:?}");
        let text = res.text();
        assert!(
            text.contains(&format!(
                "<p class=\"field-error\" id=\"name-error\">{why}</p>"
            )),
            "{text}"
        );
        assert!(text.contains("aria-invalid=\"true\""));
        assert!(text.contains(&format!("value=\"{}\"", name.trim())));
    }
    let res = app
        .request_form(
            Method::POST,
            "/devices/new",
            Some(&cookie),
            &[("name", &format!("<b>{long}"))],
        )
        .await;
    assert_eq!(res.status, StatusCode::BAD_REQUEST);
    assert!(
        res.text().contains("value=\"&#60;b&#62;AAAA"),
        "{}",
        res.text()
    );
    let count: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM devices")
        .fetch_one(&app.db)
        .await
        .unwrap();
    assert_eq!(count, 0);
    // 40 characters is still a name.
    let forty = "Å".repeat(crate::handlers::devices::MAX_DEVICE_NAME_CHARS);
    let res = app
        .request_form(
            Method::POST,
            "/devices/new",
            Some(&cookie),
            &[("name", &forty)],
        )
        .await;
    assert!(res.status.is_redirection(), "{}", res.status);
    sqlx::query("DELETE FROM devices")
        .execute(&app.db)
        .await
        .unwrap();
    // A name with spaces around it is trimmed.
    app.request_form(
        Method::POST,
        "/devices/new",
        Some(&cookie),
        &[("name", "  Emil ")],
    )
    .await;
    let name: String = sqlx::query_scalar("SELECT name FROM devices")
        .fetch_one(&app.db)
        .await
        .unwrap();
    assert_eq!(name, "Emil");
}

/// A Wi-Fi field that can't be a real network is refused in its group before any code is made
/// (400); the password is never typed back.
#[tokio::test]
async fn a_wifi_field_that_cant_be_right_is_refused_before_any_code() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, _) = app.create_device("Anna").await;
    let before = code_hash(&app, id).await;
    let long_name = "N".repeat(33);
    let long_password = "p".repeat(64);
    for (ssid, password, field) in [
        (long_name.as_str(), "not-a-real-pass", "wifi_ssid"),
        ("Hjemme", long_password.as_str(), "wifi_password"),
        ("", "not-a-real-pass", "wifi_ssid"),
    ] {
        let res = app
            .request_form(
                Method::POST,
                &format!("/devices/{id}/provision"),
                Some(&cookie),
                &[("wifi_ssid", ssid), ("wifi_password", password)],
            )
            .await;
        assert_eq!(res.status, StatusCode::BAD_REQUEST, "{field}");
        let text = res.text();
        let input = settings_pages::part(&text, &format!("id=\"{field}\""), ">");
        assert!(input.contains("aria-invalid=\"true\""), "{input}");
        assert!(text.contains("Nothing was made."));
        assert!(!text.contains(password), "the password came back");
        // The other field was wrong: the password field says to type it again.
        assert_eq!(
            text.contains("Type the password again."),
            field == "wifi_ssid",
            "{field}"
        );
        assert!(!text.contains("data-row=\"qr\""));
        assert_eq!(
            code_hash(&app, id).await,
            before,
            "{field}: a code was made"
        );
    }
}

/// The Connect page's "Server setup needed": one red row, its env var and Phone Setup behind ⓘ
/// only; without the checksum there's no QR form at all. An unknown phone is a 404.
#[tokio::test]
async fn server_setup_needed_is_one_row_with_its_detail_behind_info() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, _) = app.create_device("Anna").await;
    // No server address yet (the default): the row, the QR form still there.
    let html = app
        .get_page(&format!("/devices/{id}/provision"), &cookie)
        .await
        .text();
    let row = settings_pages::part(&html, "data-row=\"server-setup\"", "</details>");
    assert!(row.contains("Server setup needed"), "{row}");
    assert!(row.contains("data-missing=\"server-url\"") && row.contains("/settings/provisioning"));
    assert!(!row.contains("data-missing=\"checksum\""));
    assert!(html.contains(&format!("action=\"/devices/{id}/provision#qr\"")));
    sqlx::query("UPDATE provisioning_settings SET server_url = 'https://pi.example.ts.net'")
        .execute(&app.db)
        .await
        .unwrap();
    let html = app
        .get_page(&format!("/devices/{id}/provision"), &cookie)
        .await
        .text();
    assert!(!html.contains("Server setup needed"), "{html}");

    // Without the checksum: the row says so, the env var only inside the ⓘ panel, no QR form.
    let mut state = app.state.clone();
    let mut config = (*state.config).clone();
    config.launcher_signature_checksum = None;
    state.config = Arc::new(config);
    let response = provisioning::provision_form(State(state), Path(id))
        .await
        .into_response();
    let html = read_response(response).await.text();
    let row = settings_pages::part(&html, "data-row=\"server-setup\"", "</details>");
    let panel = settings_pages::part(row, "<div class=\"info-panel\">", "</div>");
    assert!(panel.contains("LAUNCHER_SIGNATURE_CHECKSUM"), "{row}");
    assert_eq!(html.matches("LAUNCHER_SIGNATURE_CHECKSUM").count(), 1);
    assert!(
        !html.contains("/provision#qr"),
        "a QR form without a checksum"
    );
    // New Setup Code still works.
    assert!(html.contains(&format!("action=\"/devices/{id}/regenerate-code\"")));

    for res in [
        app.get_page("/devices/9999/provision", &cookie).await,
        app.request_form(Method::POST, "/devices/9999/provision", Some(&cookie), &[])
            .await,
        app.request_form(
            Method::POST,
            "/devices/9999/regenerate-code",
            Some(&cookie),
            &[],
        )
        .await,
    ] {
        assert_eq!(res.status, StatusCode::NOT_FOUND);
    }

    // Settings that can't be read are a 500 - never a QR code without the server address and key.
    let before = code_hash(&app, id).await;
    sqlx::query("ALTER TABLE provisioning_settings RENAME TO provisioning_settings_gone")
        .execute(&app.db)
        .await
        .unwrap();
    let res = app
        .request_form(
            Method::POST,
            &format!("/devices/{id}/provision"),
            Some(&cookie),
            &[],
        )
        .await;
    assert_eq!(res.status, StatusCode::INTERNAL_SERVER_ERROR);
    assert_eq!(code_hash(&app, id).await, before, "no code was made");
}

/// The Wi-Fi limits at their edges (qa-31-s15-code L2, L3): the name is 32 *bytes* (æ, ø, å count as
/// two), a WPA password 8-63 characters or the raw 64-hex-digit key.
#[test]
fn wifi_fields_are_judged_at_their_edges() {
    let refused =
        |ssid: &str, password: &str| provisioning::wifi_refusal(ssid, password).map(|r| r.0);
    assert_eq!(refused(&"N".repeat(32), ""), None);
    assert_eq!(refused(&"ø".repeat(16), ""), None, "16 × ø = 32 bytes");
    assert_eq!(
        refused(&"ø".repeat(17), ""),
        Some("wifi_ssid"),
        "17 letters, 34 bytes"
    );
    assert_eq!(refused("Hjemme", &"p".repeat(7)), Some("wifi_password"));
    assert_eq!(refused("Hjemme", &"p".repeat(8)), None);
    assert_eq!(refused("Hjemme", &"p".repeat(63)), None);
    assert_eq!(
        refused("Hjemme", &"0123456789abcdef".repeat(4)),
        None,
        "a raw key"
    );
    assert_eq!(refused("Hjemme", &"p".repeat(64)), Some("wifi_password"));
    assert_eq!(
        refused(
            "Hjemme",
            &"0123456789abcdef".repeat(4).replacen('0', "00", 1)
        ),
        Some("wifi_password")
    );
    assert_eq!(refused("Hjemme", ""), None, "an open network");
    assert_eq!(refused("", "long enough pass"), Some("wifi_ssid"));
}

/// A 32-byte name of æ/ø/å and a 63-character password make a QR code; one byte more is refused in
/// place with the name typed back, so it can be shortened (N2).
#[tokio::test]
async fn a_wifi_name_at_the_limit_makes_a_qr_and_one_over_is_typed_back() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, _) = app.create_device("Anna").await;
    let action = format!("/devices/{id}/provision");
    let name = "ø".repeat(16);
    let password = "p".repeat(63);
    let res = app
        .request_form(
            Method::POST,
            &action,
            Some(&cookie),
            &[("wifi_ssid", &name), ("wifi_password", &password)],
        )
        .await;
    assert_eq!(res.status, StatusCode::OK);
    let text = res.text();
    assert!(text.contains("data-row=\"qr\""));
    assert!(text.contains("data-row=\"keep-open\""));
    let long = format!("{name}ø");
    let res = app
        .request_form(
            Method::POST,
            &action,
            Some(&cookie),
            &[("wifi_ssid", &long), ("wifi_password", &password)],
        )
        .await;
    assert_eq!(res.status, StatusCode::BAD_REQUEST);
    let text = res.text();
    assert!(
        text.contains(&format!("value=\"{long}\"")),
        "the name typed back"
    );
    assert!(text.contains("shorten the name"));
    assert!(!text.contains(&password));
}

/// A payload too big for a QR code (a huge server address) is refused before a code is made: the
/// phone's live code survives (qa-31-s15-code L1).
#[tokio::test]
async fn a_payload_too_big_for_a_qr_code_keeps_the_live_code() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, _) = app.create_device("Anna").await;
    let huge = format!("https://{}.example.invalid", "a".repeat(3000));
    sqlx::query("UPDATE provisioning_settings SET server_url = ?")
        .bind(&huge)
        .execute(&app.db)
        .await
        .unwrap();
    let before = code_hash(&app, id).await;
    assert!(before.is_some());
    let res = app
        .request_form(
            Method::POST,
            &format!("/devices/{id}/provision"),
            Some(&cookie),
            &[],
        )
        .await;
    assert_eq!(res.status, StatusCode::INTERNAL_SERVER_ERROR);
    assert!(res.text().contains("too long for a QR code"));
    assert_eq!(
        code_hash(&app, id).await,
        before,
        "the live code was replaced"
    );
}
