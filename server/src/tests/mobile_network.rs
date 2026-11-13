//! The round "Mobilnett" (design 94, design 95 rev. 3): the shared contract `testdata/mobile_network.json`, the
//! Mobilnett page and its saves, and its lines on the summary.

use axum::http::{Method, StatusCode};
use serde_json::{Value, json};

use super::{TestApp, assert_redirect};
use crate::mobile_network::{MOBILE_CAPABILITY, ROAMING_CAPABILITY, clean_mobile, clean_roaming};

const CONTRACT: &str = include_str!("../../testdata/mobile_network.json");

fn contract() -> Value {
    serde_json::from_str(CONTRACT).unwrap()
}

async fn save(
    app: &TestApp,
    cookie: &str,
    id: i64,
    fields: &[(&str, &str)],
) -> super::TestResponse {
    app.request_form(
        Method::POST,
        &format!("/devices/{id}/mobile"),
        Some(cookie),
        fields,
    )
    .await
}

async fn report(app: &TestApp, token: &str, extra: Value, capabilities: &[&str]) {
    let mut body = json!({
        "lock_reason": "NONE",
        "kiosk_engaged": true,
        "capabilities": capabilities,
    });
    for (key, value) in extra.as_object().unwrap() {
        body[key] = value.clone();
    }
    let res = app
        .request(Method::POST, "/api/devices/status", Some(token), Some(body))
        .await;
    // 204, or 200 for a launcher that claims app_logos_v1.
    assert!(res.status.is_success(), "{} {}", res.status, res.text());
}

async fn policy(app: &TestApp, token: &str) -> Value {
    app.request(Method::GET, "/api/devices/policy", Some(token), None)
        .await
        .json()
}

async fn stored(app: &TestApp, id: i64) -> (Option<String>, Option<String>) {
    sqlx::query_as(
        "SELECT data_roaming_json, mobile_data_json FROM device_status WHERE device_id = ? \
         ORDER BY id DESC LIMIT 1",
    )
    .bind(id)
    .fetch_one(&app.state.db)
    .await
    .unwrap()
}

/// Each `policy` row produced from the choices saved through the page; the report stored as the contract has it; the
/// `clean` rows; the copies identical.
#[tokio::test]
async fn the_contract() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("Emma").await;
    let contract = contract();
    assert_eq!(contract["capabilities"]["data_roaming"], ROAMING_CAPABILITY);
    assert_eq!(contract["capabilities"]["mobile_data"], MOBILE_CAPABILITY);

    // A new phone: the defaults are the first row (off and locked, apps on, the settings locked).
    let rows = contract["policy"].as_array().unwrap();
    let pick = |p: &Value| {
        json!({
            "data_roaming": p["data_roaming"],
            "apps_mobile": p["apps_mobile"],
            "mobile_settings_locked": p["mobile_settings_locked"],
        })
    };
    assert_eq!(pick(&policy(&app, &token).await), rows[0]);
    for (group, fields) in [
        ("data-roaming", vec![("data_roaming", "phone")]),
        ("mobile-data", vec![("apps_mobile", "vibb")]),
        ("mobile-lock", vec![]),
    ] {
        let mut sent = vec![("group", group)];
        sent.extend(fields);
        let res = save(&app, &cookie, id, &sent).await;
        assert_redirect(&res, &format!("/devices/{id}/mobile"), Some(group));
    }
    assert_eq!(pick(&policy(&app, &token).await), rows[1]);

    report(
        &app,
        &token,
        contract["report"].clone(),
        &[ROAMING_CAPABILITY, MOBILE_CAPABILITY],
    )
    .await;
    let (roaming, mobile) = stored(&app, id).await;
    let roaming: Value = serde_json::from_str(&roaming.unwrap()).unwrap();
    let mobile: Value = serde_json::from_str(&mobile.unwrap()).unwrap();
    assert_eq!(roaming, contract["report"]["data_roaming"]);
    assert_eq!(mobile, contract["report"]["mobile_data"]);

    for row in contract["clean"].as_array().unwrap() {
        let cleaned: Value = serde_json::from_str(&clean_roaming(&row["in"]).unwrap()).unwrap();
        assert_eq!(cleaned, row["stored"], "{row}");
    }
    for row in contract["clean_mobile"].as_array().unwrap() {
        let cleaned: Value = serde_json::from_str(&clean_mobile(&row["in"]).unwrap()).unwrap();
        assert_eq!(cleaned, row["stored"], "{row}");
    }
    assert_eq!(clean_roaming(&json!("not an object")), None);

    let launcher = std::fs::read_to_string(concat!(
        env!("CARGO_MANIFEST_DIR"),
        "/../launcher/app/src/test/resources/mobile_network.json"
    ))
    .unwrap();
    assert_eq!(launcher, CONTRACT, "the launcher's copy differs");
}

/// An unknown value, an unknown group: 400 with nothing written; an unknown phone 404.
#[tokio::test]
async fn a_bad_save_writes_nothing() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("Jack").await;
    for fields in [
        vec![("group", "data-roaming"), ("data_roaming", "eea")],
        vec![("group", "mobile-data"), ("apps_mobile", "off")],
        vec![("group", "elsewhere")],
    ] {
        let res = save(&app, &cookie, id, &fields).await;
        assert_eq!(res.status, StatusCode::BAD_REQUEST, "{fields:?}");
    }
    let served = policy(&app, &token).await;
    assert_eq!(served["data_roaming"], "off");
    assert_eq!(served["apps_mobile"], "on");
    let res = save(&app, &cookie, id + 99, &[("group", "mobile-lock")]).await;
    assert_eq!(res.status, StatusCode::NOT_FOUND);
}

/// The page: the update line before the capability; the phone's state under its group once it reports; the summary's
/// row value, its lines and the header's roaming part.
#[tokio::test]
async fn the_page_and_the_summary_say_what_the_phone_reports() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("Lily").await;
    let page = format!("/devices/{id}/mobile");

    report(&app, &token, json!({}), &[]).await;
    let html = app.get_page(&page, &cookie).await.text();
    assert!(html.contains("data-mobile-line=\"needs-update\""), "{html}");
    assert!(html.contains(" disabled data-autosave"));

    save(
        &app,
        &cookie,
        id,
        &[("group", "mobile-data"), ("apps_mobile", "vibb")],
    )
    .await;
    report(
        &app,
        &token,
        json!({
            "data_roaming": {"locked": true, "roaming": true, "country": "se", "network": "24001",
                             "enabled": false, "data_active": true, "modems": 1},
            "mobile_data": {"enabled": true, "sink": "parent", "settings_locked": true},
        }),
        &[ROAMING_CAPABILITY, MOBILE_CAPABILITY],
    )
    .await;
    let html = app.get_page(&page, &cookie).await.text();
    assert!(!html.contains("needs-update"));
    for line in ["only-vibb", "locked", "roaming", "lingering"] {
        assert!(
            html.contains(&format!("data-mobile-line=\"{line}\"")),
            "{line}"
        );
    }
    let summary = app
        .get_page(&format!("/devices/{id}"), &cookie)
        .await
        .text();
    assert!(summary.contains(&format!(
        "href=\"/devices/{id}/mobile\" data-row=\"mobile\""
    )));
    assert!(summary.contains("data-mobile-line=\"only-vibb\""));
    assert!(summary.contains("data-mobile-line=\"lingering\""));
    // Design 94 §6.2: the header's last part.
    assert!(summary.contains(" data-roaming>"), "{summary}");

    // Mobile data off on the phone: the summary says so, and the row's value too.
    report(
        &app,
        &token,
        json!({"mobile_data": {"enabled": false, "sink": "off", "settings_locked": true}}),
        &[MOBILE_CAPABILITY],
    )
    .await;
    let summary = app
        .get_page(&format!("/devices/{id}"), &cookie)
        .await
        .text();
    assert!(summary.contains("data-mobile-line=\"data-off\""));
    assert!(!summary.contains("data-mobile-line=\"only-vibb\""));
}
