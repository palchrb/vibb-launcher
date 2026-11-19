//! The round "Mobilnett" (design 94, design 95 rev. 3): the shared contract `testdata/mobile_network.json`, the
//! Mobilnett page and its saves, and its lines on the summary.

use axum::http::{Method, StatusCode};
use serde_json::{Value, json};

use super::{TestApp, assert_redirect};
use crate::mobile_network::{clean_mobile, clean_roaming};

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

async fn report(app: &TestApp, token: &str, extra: Value) {
    let mut body = json!({"lock_reason": "NONE", "kiosk_engaged": true});
    for (key, value) in extra.as_object().unwrap() {
        body[key] = value.clone();
    }
    let res = app
        .request(Method::POST, "/api/devices/status", Some(token), Some(body))
        .await;
    // 204, or 200 when logos are wanted.
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

    // A new phone: the defaults are the first row (design 97 D1: Bare Vibb abroad; apps on, the settings locked).
    let rows = contract["policy"].as_array().unwrap();
    let pick = |p: &Value| {
        json!({
            "abroad": p["abroad"],
            "apps_mobile": p["apps_mobile"],
            "mobile_settings_locked": p["mobile_settings_locked"],
        })
    };
    assert_eq!(pick(&policy(&app, &token).await), rows[0]);
    for (group, fields) in [
        ("abroad", vec![("abroad", "home")]),
        ("mobile-data", vec![("apps_mobile", "vibb")]),
        ("mobile-lock", vec![]),
    ] {
        let mut sent = vec![("group", group)];
        sent.extend(fields);
        let res = save(&app, &cookie, id, &sent).await;
        assert_redirect(&res, &format!("/devices/{id}/mobile"), Some(group));
    }
    assert_eq!(pick(&policy(&app, &token).await), rows[1]);
    let res = save(&app, &cookie, id, &[("group", "abroad"), ("abroad", "off")]).await;
    assert_redirect(&res, &format!("/devices/{id}/mobile"), Some("abroad"));
    assert_eq!(pick(&policy(&app, &token).await), rows[2]);

    report(&app, &token, contract["report"].clone()).await;
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

    // Design 116 §3.5: `vpn_filter` - each report stored as it is, the raise rule's rows, the cleaning.
    let vpn = &contract["vpn_filter"];
    assert_eq!(
        vpn["report_after_ms"].as_i64(),
        Some(crate::mobile_network::VPN_REPORT_AFTER_MS)
    );
    for (name, sent) in vpn["reports"].as_object().unwrap() {
        report(&app, &token, json!({"vpn_filter": sent})).await;
        let kept: Option<String> = sqlx::query_scalar(
            "SELECT vpn_filter_json FROM device_status WHERE device_id = ? ORDER BY id DESC LIMIT 1",
        )
        .bind(id)
        .fetch_one(&app.db)
        .await
        .unwrap();
        let kept: Value = serde_json::from_str(&kept.unwrap()).unwrap();
        assert_eq!(&kept, sent, "{name}");
    }
    for row in vpn["raise"].as_array().unwrap() {
        assert_eq!(
            crate::mobile_network::filter_down_raised(
                row["state"].as_str().unwrap(),
                row["down_ms"].as_i64(),
                row["filter_enabled"].as_bool().unwrap(),
                row["paused"].as_bool().unwrap(),
            ),
            row["row"].as_bool().unwrap(),
            "{row}"
        );
    }
    for row in vpn["clean"].as_array().unwrap() {
        let cleaned = crate::mobile_network::clean_vpn_filter(&row["in"])
            .map(|json| serde_json::from_str::<Value>(&json).unwrap())
            .unwrap_or(Value::Null);
        assert_eq!(cleaned, row["stored"], "{row}");
    }

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
        vec![("group", "abroad"), ("abroad", "eea")],
        vec![("group", "mobile-data"), ("apps_mobile", "off")],
        vec![("group", "elsewhere")],
    ] {
        let res = save(&app, &cookie, id, &fields).await;
        assert_eq!(res.status, StatusCode::BAD_REQUEST, "{fields:?}");
    }
    let served = policy(&app, &token).await;
    assert_eq!(served["abroad"], "vibb");
    assert_eq!(served["apps_mobile"], "on");
    let res = save(&app, &cookie, id + 99, &[("group", "mobile-lock")]).await;
    assert_eq!(res.status, StatusCode::NOT_FOUND);
}

/// The page: no update line and no disabled control, before a report and after one; the phone's state under its group
/// once it reports; the summary's row value, its lines and the header's roaming part. Design 113: no "only Vibb" line
/// restating the chosen radio, and mobile data off is orange on the page and the summary's Attention row, not a
/// Mobilnett line or a part of its value.
#[tokio::test]
async fn the_page_and_the_summary_say_what_the_phone_reports() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("Lily").await;
    let page = format!("/devices/{id}/mobile");

    for reported in [false, true] {
        if reported {
            report(&app, &token, json!({})).await;
        }
        let html = app.get_page(&page, &cookie).await.text();
        assert!(!html.contains("Vibb One is updated"), "{html}");
        assert!(!html.contains(" disabled data-autosave"), "{html}");
    }

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
    )
    .await;
    let html = app.get_page(&page, &cookie).await.text();
    // Bare Vibb (the default) with Android's roaming switch off: the orange switch line (design 113).
    for line in ["switch", "roaming", "lingering"] {
        assert!(
            html.contains(&format!("data-mobile-line=\"{line}\"")),
            "{line}"
        );
    }
    assert!(!html.contains("data-mobile-line=\"only-vibb\""), "{html}");
    let summary = app
        .get_page(&format!("/devices/{id}"), &cookie)
        .await
        .text();
    assert!(summary.contains(&format!(
        "href=\"/devices/{id}/mobile\" data-row=\"mobile\""
    )));
    assert!(!summary.contains("data-mobile-line=\"only-vibb\""));
    assert!(summary.contains("data-mobile-line=\"lingering\""));
    // Design 94 §6.2: the header's last part.
    assert!(summary.contains(" data-roaming>"), "{summary}");

    // Mobile data off on the phone (design 114: the setup reading, the user's own switch): the summary's setup row
    // counts it, once - no Mobilnett line, no part of the row's value (QA L6); the page's line is the setup warning
    // at #mobile-data, said once (QA M1).
    report(
        &app,
        &token,
        json!({"mobile_data": {"enabled": false, "sink": "off", "settings_locked": true},
               "setup": {"mobile_data": false}}),
    )
    .await;
    let summary = app
        .get_page(&format!("/devices/{id}"), &cookie)
        .await
        .text();
    assert!(
        summary.contains(&format!(
            "href=\"/devices/{id}/setup\" data-attention=\"setup\""
        )),
        "{summary}"
    );
    assert!(!summary.contains("mobile-data-off"));
    assert!(!summary.contains("data-mobile-line=\"data-off\""));
    assert!(!summary.contains("data-mobile-line=\"only-vibb\""));
    let row = summary.split("data-row=\"mobile\"").nth(1).unwrap();
    let row = row.split("</a>").next().unwrap();
    assert!(row.contains("Only Vibb"), "{row}");
    assert!(!row.contains("Mobile data off"), "{row}");
    let html = app.get_page(&page, &cookie).await.text();
    assert!(!html.contains("data-mobile-line=\"data-off\""));
    let line = format!(
        "<a class=\"attention attention-action\" href=\"/devices/{id}/setup#step-mobile_data\" data-setup-warning=\"mobile_data\">"
    );
    assert_eq!(html.matches(&line).count(), 1, "{html}");
    let at = html.find(&line).unwrap();
    assert!(html[..at].contains("<section id=\"mobile-data\""));
    assert!(!html[..at].contains("<section id=\"abroad\""));
}

/// Migration 0145 (design 97 §7, D1/D2): every existing row is set explicitly - `phone` becomes `home` only where the
/// newest roaming report read Android's switch on (true; false, null and never reported become `off`), and `off` stays
/// `off`; a phone created after it is Bare Vibb with the kid's switch shown (D3).
#[tokio::test]
async fn the_migration_follows_d2_and_a_new_phone_gets_d1() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let mut phones = Vec::new();
    for (name, data_roaming, enabled) in [
        ("Emma", "phone", Some("true")),
        ("Jack", "phone", Some("false")),
        ("Lily", "phone", Some("null")),
        ("Mamma", "phone", None),
        ("Pappa", "off", Some("true")),
    ] {
        let (id, _) = app.create_device(name).await;
        sqlx::query("UPDATE device_policy SET data_roaming = ? WHERE device_id = ?")
            .bind(data_roaming)
            .bind(id)
            .execute(&app.db)
            .await
            .unwrap();
        if let Some(enabled) = enabled {
            // An older report first, then the newest one, which decides.
            for value in ["true", enabled] {
                sqlx::query(
                    "INSERT INTO device_status (device_id, lock_reason, kiosk_engaged, data_roaming_json) \
                     VALUES (?, 'NONE', 1, ?)",
                )
                .bind(id)
                .bind(format!(
                    r#"{{"locked":false,"roaming":false,"enabled":{value},"data_active":false}}"#
                ))
                .execute(&app.db)
                .await
                .unwrap();
            }
        }
        phones.push(id);
    }
    // The rows were stored after the migrations ran (with the column's default `vibb`): its UPDATE again.
    let migration = include_str!("../../migrations/0145_abroad.sql");
    let update = migration
        .split(';')
        .map(|statement| {
            statement
                .lines()
                .filter(|line| !line.trim_start().starts_with("--"))
                .collect::<Vec<_>>()
                .join("\n")
        })
        .find(|sql| sql.trim_start().starts_with("UPDATE device_policy"))
        .unwrap();
    sqlx::query(&update).execute(&app.db).await.unwrap();
    let mut abroad = Vec::new();
    for id in &phones {
        let value: String =
            sqlx::query_scalar("SELECT abroad FROM device_policy WHERE device_id = ?")
                .bind(id)
                .fetch_one(&app.db)
                .await
                .unwrap();
        abroad.push(value);
    }
    assert_eq!(abroad, ["home", "off", "off", "off", "off"]);

    // A new phone through the page's own create.
    let res = app
        .request_form(
            Method::POST,
            "/devices/new",
            Some(&cookie),
            &[("name", "Lily 2")],
        )
        .await;
    assert!(res.status.is_redirection(), "{}", res.text());
    let (abroad, mask): (String, i64) = sqlx::query_as(
        "SELECT p.abroad, p.quick_controls_mask FROM device_policy p JOIN devices d ON d.id = p.device_id \
         WHERE d.name = 'Lily 2'",
    )
    .fetch_one(&app.db)
    .await
    .unwrap();
    assert_eq!(abroad, "vibb");
    assert_eq!(mask, crate::handlers::devices::DEFAULT_QUICK_CONTROLS);
    assert_ne!(
        mask & crate::handlers::devices::QUICK_CONTROL_MOBILE_DATA,
        0
    );
}

async fn mask(app: &TestApp, token: &str) -> i64 {
    app.policy(token).await["quick_controls_mask"]
        .as_i64()
        .unwrap()
}

/// Design 97 §5: each group saves to its own anchor - the kid's switch to `#mobile-data`, writing bit 32 alone - and the
/// Home Screen form keeps bit 32 on `section=quick` and on the whole form; the page is a phone sub-page (no bell), in
/// both languages.
#[tokio::test]
async fn the_page_saves_on_its_anchors_and_the_home_form_keeps_bit_32() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("Emma").await;
    sqlx::query("UPDATE device_policy SET quick_controls_mask = 5 WHERE device_id = ?")
        .bind(id)
        .execute(&app.db)
        .await
        .unwrap();
    let res = save(&app, &cookie, id, &[("group", "kid-mobile"), ("on", "on")]).await;
    assert_redirect(&res, &format!("/devices/{id}/mobile"), Some("mobile-data"));
    assert_eq!(mask(&app, &token).await, 5 | 32);
    app.request_form(
        Method::POST,
        &format!("/devices/{id}/policy"),
        Some(&cookie),
        &[("section", "quick"), ("quick_control_wifi", "on")],
    )
    .await;
    assert_eq!(mask(&app, &token).await, 1 | 32);
    save(&app, &cookie, id, &[("group", "kid-mobile")]).await;
    assert_eq!(mask(&app, &token).await, 1);
    let res = save(
        &app,
        &cookie,
        id,
        &[("group", "abroad"), ("abroad", "home")],
    )
    .await;
    assert_redirect(&res, &format!("/devices/{id}/mobile"), Some("abroad"));

    report(
        &app,
        &token,
        json!({
            "data_roaming": {"locked": false, "roaming": false, "enabled": true, "data_active": false},
            "mobile_data": {"enabled": true, "sink": "off", "settings_locked": true},
        }),
    )
    .await;
    let page = format!("/devices/{id}/mobile");
    let html = app.get_page(&page, &cookie).await.text();
    assert!(html.contains("<section id=\"abroad\""), "{html}");
    assert!(
        html.contains(
            "action=\"/devices/{id}/mobile#abroad\""
                .replace("{id}", &id.to_string())
                .as_str()
        )
    );
    assert!(!html.contains("title-bell"), "a phone sub-page has no bell");
    assert!(
        !html.contains("id=\"data-roaming\""),
        "the old group is gone"
    );
    // Android's switch on under På: as it should be, so nothing is said about it (design 113).
    assert!(!html.contains("data-mobile-line=\"switch\""), "{html}");
    // The kid's Off and the abroad sink are worded in Norwegian too.
    report(
        &app,
        &token,
        json!({
            "data_roaming": {"locked": false, "roaming": true, "country": "se", "network": "24001",
                             "enabled": false, "data_active": false, "modems": 1, "where": "abroad"},
            "mobile_data": {"enabled": true, "sink": "kid", "settings_locked": true, "kid": "off"},
        }),
    )
    .await;
    let html = app
        .get_page(&format!("{page}?lang=nb"), &cookie)
        .await
        .text();
    // Code QA L6: the kid's Off is said once on the page.
    assert!(html.contains("data-mobile-line=\"kid-off\""));
    assert!(!html.contains("data-mobile-line=\"only-vibb-kid\""));
    for words in [
        "Emma har slått mobildata av",
        "Roamer i Sverige – Androids roamingbryter er av",
        // Behind the line's ⓘ, still in the page.
        "har ingen mobildata nå, heller ikke Vibb",
        "Roaming",
    ] {
        assert!(html.contains(words), "{words}");
    }
    let summary = app
        .get_page(&format!("/devices/{id}?lang=nb"), &cookie)
        .await
        .text();
    assert!(
        summary.contains("Mobildata: bare Vibb – Emma har slått av"),
        "{summary}"
    );
    assert!(
        summary.contains("Roaming på · Mobildata av (Emma)"),
        "{summary}"
    );
}

/// Design 97 §6: a report abroad raises one notice per crossing (its key the place and the instant, to "Roaming"), a
/// repeat of the same place none, and a report without `where` none.
#[tokio::test]
async fn a_crossing_raises_the_roaming_notice_once() {
    let app = TestApp::new().await;
    let (id, token) = app.enrolled_device("Emma").await;
    let (_, other) = app.enrolled_device("Jack").await;
    let abroad = |country: &str| {
        json!({"data_roaming": {"locked": false, "roaming": true, "country": country, "network": "24001",
                                "enabled": true, "data_active": true, "modems": 1, "where": "abroad"}})
    };
    report(&app, &token, abroad("se")).await;
    report(&app, &token, abroad("se")).await;
    let mut without_where = abroad("se");
    without_where["data_roaming"]
        .as_object_mut()
        .unwrap()
        .remove("where");
    report(&app, &other, without_where).await;
    let events: Vec<(i64, String, String, String)> = sqlx::query_as(
        "SELECT device_id, dedup_key, target, params FROM parent_events WHERE kind = 'roaming_started'",
    )
    .fetch_all(&app.db)
    .await
    .unwrap();
    assert_eq!(events.len(), 1, "{events:?}");
    let (device, key, target, params) = &events[0];
    assert_eq!(*device, id);
    assert!(key.starts_with("se:"), "{key}");
    assert_eq!(target, &format!("/devices/{id}/mobile#abroad"));
    let params: Value = serde_json::from_str(params).unwrap();
    assert_eq!(params["place"], "se");
    assert_eq!(params["phone"], "Emma");
    let place: Option<String> =
        sqlx::query_scalar("SELECT roaming_place FROM devices WHERE id = ?")
            .bind(id)
            .fetch_one(&app.db)
            .await
            .unwrap();
    assert_eq!(place.as_deref(), Some("se"));
}
