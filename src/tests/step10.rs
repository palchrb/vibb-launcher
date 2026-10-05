//! Step 10 (design 10-lock-and-call-ui.md, QA 10): the kid's PIN for handy's own lock screen -
//! validation, the override-PIN cross-checks, the policy's `kid_lock`, the safe-boot default,
//! the phone's `lock_state` (stored sanitized and capped) and the device page's warnings.

use axum::http::{Method, StatusCode};
use serde_json::{Value, json};

use super::TestApp;

async fn policy(app: &TestApp, token: &str) -> Value {
    let res = app
        .request(Method::GET, "/api/devices/policy", Some(token), None)
        .await;
    assert_eq!(res.status, StatusCode::OK);
    res.json()
}

async fn post_status(app: &TestApp, token: &str, extra: Value) {
    let mut report = json!({ "lock_reason": "none", "kiosk_engaged": true });
    for (key, value) in extra.as_object().unwrap() {
        report[key] = value.clone();
    }
    let res = app
        .request(
            Method::POST,
            "/api/devices/status",
            Some(token),
            Some(report),
        )
        .await;
    assert_eq!(res.status, StatusCode::NO_CONTENT);
}

async fn kid_columns(app: &TestApp, id: i64) -> (Option<String>, Option<String>, Option<i64>) {
    sqlx::query_as(
        "SELECT kid_pin_hash, kid_pin_salt, kid_pin_length FROM device_policy WHERE device_id = ?",
    )
    .bind(id)
    .fetch_one(&app.db)
    .await
    .unwrap()
}

async fn safe_boot(app: &TestApp, id: i64) -> bool {
    sqlx::query_scalar("SELECT disallow_safe_boot FROM device_policy WHERE device_id = ?")
        .bind(id)
        .fetch_one(&app.db)
        .await
        .unwrap()
}

async fn events(app: &TestApp, event_type: &str) -> i64 {
    sqlx::query_scalar("SELECT COUNT(*) FROM security_events WHERE event_type = ?")
        .bind(event_type)
        .fetch_one(&app.db)
        .await
        .unwrap()
}

async fn set_kid_pin(app: &TestApp, cookie: &str, id: i64, pin: &str) -> Option<String> {
    let res = app
        .request_form(
            Method::POST,
            &format!("/devices/{id}/kid-lock"),
            Some(cookie),
            &[("new_kid_pin", pin)],
        )
        .await;
    assert_eq!(res.status, StatusCode::SEE_OTHER, "{}", res.text());
    res.location().map(str::to_string)
}

async fn set_override(app: &TestApp, cookie: &str, id: i64, fields: &[(&str, &str)]) -> String {
    let res = app
        .request_form(
            Method::POST,
            &format!("/devices/{id}/policy"),
            Some(cookie),
            fields,
        )
        .await;
    assert_eq!(res.status, StatusCode::SEE_OTHER);
    res.location().unwrap_or_default().to_string()
}

#[tokio::test]
async fn kid_pin_needs_an_override_pin_first() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("phone").await;

    let location = set_kid_pin(&app, &cookie, id, "1234").await.unwrap();
    assert!(location.contains("notice=needs_override"), "{location}");
    assert_eq!(kid_columns(&app, id).await, (None, None, None));
    assert!(policy(&app, &token).await["kid_lock"].is_null());
    assert!(!safe_boot(&app, id).await);

    let html = app.get_page(&location, &cookie).await.text();
    assert!(html.contains("set an unlock code"), "{html}");
}

#[tokio::test]
async fn kid_pin_saved_sent_and_safe_boot_blocked() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("phone").await;
    set_override(&app, &cookie, id, &[("new_pin", "246810")]).await;

    let location = set_kid_pin(&app, &cookie, id, "1357").await.unwrap();
    assert!(location.contains("notice=saved_safe_boot"), "{location}");
    assert!(safe_boot(&app, id).await);
    assert_eq!(events(&app, "kid_pin_changed").await, 1);

    let policy = policy(&app, &token).await;
    let kid_lock = &policy["kid_lock"];
    assert_eq!(kid_lock["pin_length"], 4);
    let hash = kid_lock["pin_hash"].as_str().unwrap();
    let salt = kid_lock["pin_salt"].as_str().unwrap();
    assert!(crate::security::verify_pin("1357", hash, salt));
    assert!(!crate::security::verify_pin("246810", hash, salt));
    // The kid's hash is only ever under `kid_lock` - not in hardening, call_policy or anything
    // the launcher mirrors to device-protected storage.
    assert_eq!(policy.to_string().matches(hash).count(), 1);
    assert_eq!(policy.to_string().matches(salt).count(), 1);
    let mut keys: Vec<&String> = kid_lock.as_object().unwrap().keys().collect();
    keys.sort();
    assert_eq!(keys, ["pin_hash", "pin_length", "pin_salt"]);

    // The parent switches safe-boot blocking off; changing the PIN later leaves it off.
    let res = app
        .request_form(
            Method::POST,
            &format!("/devices/{id}/hardening"),
            Some(&cookie),
            &[],
        )
        .await;
    assert_eq!(res.status, StatusCode::SEE_OTHER);
    let location = set_kid_pin(&app, &cookie, id, "864213").await.unwrap();
    assert!(location.contains("notice=saved"), "{location}");
    assert!(!location.contains("safe_boot"));
    assert!(!safe_boot(&app, id).await);
    assert_eq!(kid_columns(&app, id).await.2, Some(6));
    let html = app
        .get_page(&format!("/devices/{id}"), &cookie)
        .await
        .text();
    assert!(html.contains("Safe mode is allowed"), "{html}");
}

#[tokio::test]
async fn invalid_kid_pins_write_nothing() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, _) = app.enrolled_device("phone").await;
    set_override(&app, &cookie, id, &[("new_pin", "246810")]).await;
    for bad in ["123", "1234567", "12a4", "", " "] {
        let location = set_kid_pin(&app, &cookie, id, bad).await.unwrap();
        assert!(location.contains("notice=invalid"), "{bad}: {location}");
    }
    assert_eq!(kid_columns(&app, id).await, (None, None, None));
    assert_eq!(events(&app, "kid_pin_changed").await, 0);
}

#[tokio::test]
async fn kid_and_override_pins_reject_each_other() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("phone").await;
    set_override(&app, &cookie, id, &[("new_pin", "123456")]).await;

    // Kid PIN = override PIN: refused.
    let location = set_kid_pin(&app, &cookie, id, "123456").await.unwrap();
    assert!(location.contains("notice=same_as_override"), "{location}");
    assert_eq!(kid_columns(&app, id).await.0, None);

    set_kid_pin(&app, &cookie, id, "654321").await;
    let before = policy(&app, &token).await["override_pin_hash"].clone();

    // Override PIN = kid PIN: refused, the old override stays.
    let location = set_override(&app, &cookie, id, &[("new_pin", "654321")]).await;
    assert!(
        location.contains("notice=override_is_kid_pin"),
        "{location}"
    );
    assert_eq!(policy(&app, &token).await["override_pin_hash"], before);

    // The override can't be removed while the kid's lock needs it.
    let location = set_override(&app, &cookie, id, &[("clear_pin", "on")]).await;
    assert!(
        location.contains("notice=override_needed_by_lock"),
        "{location}"
    );
    assert_eq!(policy(&app, &token).await["override_pin_hash"], before);

    // A different override PIN is fine.
    let location = set_override(&app, &cookie, id, &[("new_pin", "9876543")]).await;
    assert!(!location.contains("notice"), "{location}");
    assert_ne!(policy(&app, &token).await["override_pin_hash"], before);
}

#[tokio::test]
async fn clearing_the_kid_pin_switches_the_lock_off() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("phone").await;
    set_override(&app, &cookie, id, &[("new_pin", "246810")]).await;
    set_kid_pin(&app, &cookie, id, "1357").await;
    assert!(policy(&app, &token).await["kid_lock"].is_object());

    let res = app
        .request_form(
            Method::POST,
            &format!("/devices/{id}/kid-lock"),
            Some(&cookie),
            &[("clear_kid_pin", "on")],
        )
        .await;
    assert!(res.location().unwrap().contains("notice=cleared"));
    assert!(policy(&app, &token).await["kid_lock"].is_null());
    assert_eq!(kid_columns(&app, id).await, (None, None, None));
    assert_eq!(events(&app, "kid_pin_cleared").await, 1);
    // Now the override can go.
    let location = set_override(&app, &cookie, id, &[("clear_pin", "on")]).await;
    assert!(!location.contains("notice"));
}

#[tokio::test]
async fn kid_lock_routes_need_a_session_and_a_device() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, _) = app.enrolled_device("phone").await;
    let res = app
        .request_form(
            Method::POST,
            &format!("/devices/{id}/kid-lock"),
            None,
            &[("new_kid_pin", "1234")],
        )
        .await;
    // The session middleware sends it to the login page; nothing is written.
    assert!(
        res.location().is_none_or(|l| l.contains("login")),
        "{:?}",
        res.location()
    );
    assert_eq!(kid_columns(&app, id).await, (None, None, None));

    let res = app
        .request_form(
            Method::POST,
            "/devices/9999/kid-lock",
            Some(&cookie),
            &[("new_kid_pin", "1234")],
        )
        .await;
    assert_eq!(res.status, StatusCode::NOT_FOUND);
}

#[tokio::test]
async fn lock_state_is_stored_sanitized_and_capped() {
    let app = TestApp::new().await;
    let (id, token) = app.enrolled_device("phone").await;
    let stored = |app: &TestApp| {
        let db = app.db.clone();
        async move {
            sqlx::query_scalar::<_, Option<String>>(
                "SELECT lock_state_json FROM device_status WHERE device_id = ? \
                 ORDER BY id DESC LIMIT 1",
            )
            .bind(id)
            .fetch_one(&db)
            .await
            .unwrap()
        }
    };

    post_status(
        &app,
        &token,
        json!({ "lock_state": {
            "active": true, "inactive": null, "locked": true, "failures": 5,
            "backoff_until_ms": 1_700_000_030_000_i64, "exempt_yields": 2,
            "unlocked_at_ms": 1_700_000_000_000_i64, "pin": "1234",
        }}),
    )
    .await;
    let json: Value = serde_json::from_str(&stored(&app).await.unwrap()).unwrap();
    assert_eq!(json["failures"], 5);
    assert_eq!(json["locked"], true);
    assert!(json.get("unlocked_at_ms").is_none(), "{json}");
    assert!(json.get("pin").is_none(), "{json}");

    // Not an object, or an oversized `inactive`: nothing / capped.
    post_status(&app, &token, json!({ "lock_state": "locked" })).await;
    assert_eq!(stored(&app).await, None);
    post_status(
        &app,
        &token,
        json!({ "lock_state": { "inactive": "x".repeat(10_000) } }),
    )
    .await;
    let json: Value = serde_json::from_str(&stored(&app).await.unwrap()).unwrap();
    assert_eq!(json["inactive"].as_str().unwrap().len(), 64);
}

async fn device_page(app: &TestApp, cookie: &str, id: i64) -> String {
    app.get_page(&format!("/devices/{id}"), cookie).await.text()
}

#[tokio::test]
async fn device_page_warns_about_the_lock_state() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("phone").await;
    set_override(&app, &cookie, id, &[("new_pin", "246810")]).await;
    set_kid_pin(&app, &cookie, id, "1357").await;
    // A launcher without the capability.
    post_status(&app, &token, json!({ "capabilities": ["call_policy_v1"] })).await;
    assert!(
        device_page(&app, &cookie, id)
            .await
            .contains("lock yet - update the launcher")
    );

    for (inactive, text) in [
        ("crash_guard", "crashed several times"),
        ("android_credential", "Remove the Android screen lock"),
        (
            "bad_hash",
            "only the unlock code (override PIN) opens the lock",
        ),
    ] {
        post_status(
            &app,
            &token,
            json!({ "capabilities": ["pin_lock_v1"], "lock_state": { "inactive": inactive } }),
        )
        .await;
        let html = device_page(&app, &cookie, id).await;
        assert!(html.contains(text), "{inactive}: {html}");
    }

    post_status(
        &app,
        &token,
        json!({ "capabilities": ["pin_lock_v1"],
                "lock_state": { "active": true, "locked": false, "inactive": null } }),
    )
    .await;
    let html = device_page(&app, &cookie, id).await;
    assert!(html.contains("The lock is on"));
    assert!(!html.contains("Safe mode is allowed"));
    assert!(html.contains("Parent code"));
}

#[tokio::test]
async fn in_call_ui_failure_is_a_call_warning() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("phone").await;
    let at = chrono::Utc::now().to_rfc3339();
    post_status(
        &app,
        &token,
        json!({ "capabilities": ["call_policy_v1"],
                "call_state": { "state": "unmanaged", "in_call_ui_failed_at": at } }),
    )
    .await;
    let html = app
        .get_page(&format!("/devices/{id}"), &cookie)
        .await
        .text();
    assert!(
        html.contains("show the incoming-call screen over its lock"),
        "{html}"
    );
}
