//! Step 10 (design 10-lock-and-call-ui.md, QA 10): the kid's PIN for handy's own lock screen -
//! validation, the override-PIN cross-checks, the policy's `kid_lock`, the safe-boot default,
//! the phone's `lock_state` (stored sanitized and capped) and what Lock Screen & PINs says about it
//! (design 31 step 4; the device page's Screen lock card before).

use axum::http::{Method, StatusCode};
use serde_json::{Value, json};

use super::{Section, TestApp, assert_redirect, device_page};

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
            &format!("/devices/{id}/parent-pin"),
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
    // Back to Lock Screen & PINs, on the Child PIN group, where the flash is.
    assert_eq!(
        location,
        format!(
            "{}?notice=needs_override#child-pin",
            device_page(id, Section::Lock)
        )
    );
    assert_eq!(kid_columns(&app, id).await, (None, None, None));
    assert!(app.policy(&token).await["kid_lock"].is_null());
    assert!(!safe_boot(&app, id).await);

    let html = app.get_page(&location, &cookie).await.text();
    assert!(
        html.contains("Not saved: set a Parent PIN first."),
        "{html}"
    );
    // The form says so before a save too.
    let html = app
        .get_page(&device_page(id, Section::Lock), &cookie)
        .await
        .text();
    assert!(
        html.contains("Set a Parent PIN first: it opens the lock"),
        "{html}"
    );
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
    // When it went on, for the unseen-boots advice (design 24b's fix round, QA Low 12).
    let since: Option<i64> = sqlx::query_scalar(
        "SELECT disallow_safe_boot_set_at_ms FROM device_policy WHERE device_id = ?",
    )
    .bind(id)
    .fetch_one(&app.db)
    .await
    .unwrap();
    assert!(since.is_some());

    let policy = app.policy(&token).await;
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
    // The inner keys (policy_json_keys_snapshot pins the top level only); design 59 §1 added the
    // keyguard and the rev, always sent - the launcher's PolicyResponseCompatTest reads them.
    assert_eq!(
        keys,
        ["keyguard", "pin_hash", "pin_length", "pin_rev", "pin_salt"]
    );
    assert_eq!(kid_lock["keyguard"], "own");
    assert_eq!(kid_lock["pin_rev"], 1);

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
        .get_page(&device_page(id, Section::Lock), &cookie)
        .await
        .text();
    assert!(html.contains("Safe mode is allowed"), "{html}");
    assert!(html.contains(">On · 6 digits</span>"), "{html}");
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
    let before = app.policy(&token).await["override_pin_hash"].clone();

    // Override PIN = kid PIN: refused, the old override stays.
    let location = set_override(&app, &cookie, id, &[("new_pin", "654321")]).await;
    assert!(
        location.contains("notice=override_is_kid_pin"),
        "{location}"
    );
    assert_eq!(app.policy(&token).await["override_pin_hash"], before);

    // The override can't be removed while the kid's lock needs it.
    let location = set_override(&app, &cookie, id, &[("clear_pin", "on")]).await;
    assert!(
        location.contains("notice=override_needed_by_lock"),
        "{location}"
    );
    assert_eq!(app.policy(&token).await["override_pin_hash"], before);

    // A different override PIN is fine.
    let location = set_override(&app, &cookie, id, &[("new_pin", "9876543")]).await;
    assert!(location.contains("notice=parent_pin_saved"), "{location}");
    assert_ne!(app.policy(&token).await["override_pin_hash"], before);
}

#[tokio::test]
async fn clearing_the_kid_pin_switches_the_lock_off() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("phone").await;
    set_override(&app, &cookie, id, &[("new_pin", "246810")]).await;
    set_kid_pin(&app, &cookie, id, "1357").await;
    assert!(app.policy(&token).await["kid_lock"].is_object());

    let res = app
        .request_form(
            Method::POST,
            &format!("/devices/{id}/kid-lock"),
            Some(&cookie),
            &[("clear_kid_pin", "on")],
        )
        .await;
    assert_redirect(
        &res,
        &format!("{}?notice=cleared", device_page(id, Section::Lock)),
        Some("child-pin"),
    );
    assert!(app.policy(&token).await["kid_lock"].is_null());
    assert_eq!(kid_columns(&app, id).await, (None, None, None));
    assert_eq!(events(&app, "kid_pin_cleared").await, 1);
    // Now the override can go.
    let location = set_override(&app, &cookie, id, &[("clear_pin", "on")]).await;
    assert!(location.contains("notice=parent_pin_cleared"), "{location}");
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

    app.post_status(
        &token,
        json!({ "lock_state": {
            "active": true, "inactive": null, "locked": true, "failures": 5,
            "backoff_until_ms": 1_700_000_030_000_i64, "exempt_yields": 2,
            "voip_fsi_denied": ["io.element.android.x"],
            "unlocked_at_ms": 1_700_000_000_000_i64, "pin": "1234",
        }}),
    )
    .await;
    let json: Value = serde_json::from_str(&stored(&app).await.unwrap()).unwrap();
    assert_eq!(json["failures"], 5);
    assert_eq!(json["voip_fsi_denied"], json!(["io.element.android.x"]));
    assert_eq!(json["locked"], true);
    assert!(json.get("unlocked_at_ms").is_none(), "{json}");
    assert!(json.get("pin").is_none(), "{json}");

    // Not an object, or an oversized `inactive`: nothing / capped.
    app.post_status(&token, json!({ "lock_state": "locked" }))
        .await;
    assert_eq!(stored(&app).await, None);
    app.post_status(
        &token,
        json!({ "lock_state": { "inactive": "x".repeat(10_000) } }),
    )
    .await;
    let json: Value = serde_json::from_str(&stored(&app).await.unwrap()).unwrap();
    assert_eq!(json["inactive"].as_str().unwrap().len(), 64);

    // Design r7 §1.1.3: `voip_rang` kept, a malformed entry dropped - never the rest of lock_state.
    let now = chrono::Utc::now().timestamp_millis();
    app.post_status(
        &token,
        json!({ "lock_state": {
            "active": true, "locked": true, "failures": 1,
            "voip_rang": [
                { "package": "org.thoughtcrime.securesms", "last_at_ms": now - 60_000 },
                { "package": "rm -rf", "last_at_ms": now },
                { "package": "com.whatsapp", "last_at_ms": "yesterday" },
                { "package": "com.example.future", "last_at_ms": now + 86_400_000 },
            ],
        }}),
    )
    .await;
    let json: Value = serde_json::from_str(&stored(&app).await.unwrap()).unwrap();
    assert_eq!(json["failures"], 1);
    assert_eq!(json["locked"], true);
    assert_eq!(
        json["voip_rang"],
        json!([{ "package": "org.thoughtcrime.securesms", "last_at_ms": now - 60_000 }])
    );
    // An older launcher sends none: an empty list.
    app.post_status(&token, json!({ "lock_state": { "active": true } }))
        .await;
    let json: Value = serde_json::from_str(&stored(&app).await.unwrap()).unwrap();
    assert_eq!(json["voip_rang"], json!([]));
}

#[tokio::test]
async fn lock_page_says_which_apps_rang_over_the_lock() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("phone").await;
    set_override(&app, &cookie, id, &[("new_pin", "246810")]).await;
    set_kid_pin(&app, &cookie, id, "1357").await;
    let now = chrono::Utc::now().timestamp_millis();
    app.post_status(&token,
        json!({ "capabilities": ["pin_lock_v1"],
                "installed_apps": [{ "package_name": "org.thoughtcrime.securesms", "label": "Signal" }],
                "lock_state": { "active": true, "locked": true,
                    "voip_rang": [
                        { "package": "org.thoughtcrime.securesms", "last_at_ms": now - 60_000 },
                        { "package": "com.example.school", "last_at_ms": now - 120_000 },
                    ] } }),
    )
    .await;
    let html = lock_page(&app, &cookie, id).await;
    let group = child_group(&html);
    // Quiet rows, the app's name (else its package), never a warning.
    assert!(!group.contains("data-warning"), "{group}");
    assert!(
        group.contains("<span class=\"row-label\">Calls from Signal</span>"),
        "{group}"
    );
    assert!(
        group.contains("<span class=\"row-label\">Calls from com.example.school</span>"),
        "{group}"
    );
    assert!(group.contains("Rang over the lock · "), "{group}");
    let signal = group.find("Calls from Signal").unwrap();
    assert!(
        signal < group.find("Calls from com.example.school").unwrap(),
        "newest first"
    );
}

async fn lock_page(app: &TestApp, cookie: &str, id: i64) -> String {
    app.get_page(&device_page(id, Section::Lock), cookie)
        .await
        .text()
}

/// The Child PIN group's HTML.
fn child_group(html: &str) -> &str {
    let start = html
        .find("<section id=\"child-pin\"")
        .expect("the Child PIN group");
    &html[start..start + html[start..].find("</section>").unwrap()]
}

#[tokio::test]
async fn lock_page_warns_about_the_lock_state() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("phone").await;
    set_override(&app, &cookie, id, &[("new_pin", "246810")]).await;
    set_kid_pin(&app, &cookie, id, "1357").await;
    // A launcher without the capability.
    app.post_status(&token, json!({ "capabilities": ["call_policy_v1"] }))
        .await;
    let html = lock_page(&app, &cookie, id).await;
    assert!(
        child_group(&html).contains("Vibb One needs an update"),
        "{html}"
    );

    for (inactive, text) in [
        ("crash_guard", "Lock turned off after crashes"),
        ("bad_hash", "Phone can&#39;t read the Child PIN"),
        (
            "android_credential",
            "Android&#39;s screen lock is still on",
        ),
    ] {
        app.post_status(
            &token,
            json!({ "capabilities": ["pin_lock_v1"], "lock_state": { "inactive": inactive } }),
        )
        .await;
        let html = lock_page(&app, &cookie, id).await;
        assert!(child_group(&html).contains(text), "{inactive}: {html}");
    }
    // The last one's adb command is behind the warning's ⓘ, not in its sentence.
    let html = lock_page(&app, &cookie, id).await;
    let group = child_group(&html);
    let command = group.find("adb shell locksettings clear").expect("the fix");
    assert!(
        group[..command].contains("aria-label=\"How to fix it\""),
        "{group}"
    );

    app.post_status(&token,
        json!({ "capabilities": ["pin_lock_v1"],
                "lock_state": { "active": true, "locked": false, "failures": 2, "inactive": null } }),
    )
    .await;
    let html = lock_page(&app, &cookie, id).await;
    let group = child_group(&html);
    assert!(!group.contains("data-warning"), "{group}");
    assert!(group.contains("<span class=\"row-label\">On the phone</span>"));
    assert!(
        group.contains("<span class=\"row-value\">Unlocked · "),
        "{group}"
    );
    assert!(group.contains("<span class=\"row-label\">Wrong PINs in a row</span>"));
    assert!(group.contains("Parent code"));
    assert!(
        group.contains("After 5 wrong PINs the phone waits 30 s, then longer"),
        "qa-10-code #8"
    );
    assert!(
        group.contains("It doesn't encrypt the phone's data"),
        "qa-pwa-polish-a Q-4"
    );
}

#[tokio::test]
async fn in_call_ui_failure_is_a_call_warning() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("phone").await;
    let at = chrono::Utc::now().to_rfc3339();
    app.post_status(
        &token,
        json!({ "capabilities": ["call_policy_v1"],
                "call_state": { "state": "unmanaged", "in_call_ui_failed_at": at } }),
    )
    .await;
    // An Attention row on the device page, its words on Calls & Contacts (design 31 step 8).
    let html = app
        .get_page(&format!("/devices/{id}"), &cookie)
        .await
        .text();
    assert!(html.contains("A call may have rung unanswered"), "{html}");
    let html = app
        .get_page(&super::device_page(id, super::Section::Calls), &cookie)
        .await
        .text();
    assert!(html.contains("couldn&#39;t show over the lock"), "{html}");
}
