//! Step 11 (design 11-kiosk-escapes.md, QA 11): the update fence and notification auto-cancel
//! switches (default off, always sent), the phone's `update_fence` and `notification_cancels`
//! (stored sanitized and capped - ids and counts only) and the device page's card.

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

async fn stored(app: &TestApp, column: &str, id: i64) -> Option<String> {
    sqlx::query_scalar::<_, Option<String>>(&format!(
        "SELECT {column} FROM device_status WHERE device_id = ? ORDER BY id DESC LIMIT 1"
    ))
    .bind(id)
    .fetch_one(&app.db)
    .await
    .unwrap()
}

#[tokio::test]
async fn switches_default_off_are_always_sent_and_saved_from_the_device_page() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("phone").await;
    let p = policy(&app, &token).await;
    assert_eq!(p["update_fence"], json!(false));
    assert_eq!(p["notification_auto_cancel"], json!(false));
    assert_eq!(p["boot_cover"], json!(false), "design 16b: off by default");

    let mut nudges = app.state.command_notify.subscribe();
    let res = app
        .request_form(
            Method::POST,
            &format!("/devices/{id}/kiosk-escapes"),
            Some(&cookie),
            &[
                ("update_fence", "on"),
                ("notification_auto_cancel", "on"),
                ("boot_cover", "on"),
            ],
        )
        .await;
    assert!(res.status.is_redirection());
    // Back to the card, never the top of the page.
    assert_eq!(
        res.headers.get("location").and_then(|v| v.to_str().ok()),
        Some(format!("/devices/{id}#kiosk-escapes").as_str())
    );
    assert_eq!(nudges.try_recv().ok(), Some(id));
    let p = policy(&app, &token).await;
    assert_eq!(p["update_fence"], json!(true));
    assert_eq!(p["notification_auto_cancel"], json!(true));
    assert_eq!(p["boot_cover"], json!(true));
    let page = app
        .get_page(&format!("/devices/{id}"), &cookie)
        .await
        .text();
    assert!(
        page.contains("name=\"boot_cover\" value=\"on\" checked"),
        "{page}"
    );
    assert!(page.contains("Test it on this phone first"));

    // The phone's state (qa-16b-code #5): a launcher without the boot cover is told to update; one
    // with it reports a tripped guard, which the card shows as a warning.
    post_status(
        &app,
        &token,
        json!({ "capabilities": ["kiosk_escapes_v1"] }),
    )
    .await;
    let page = app
        .get_page(&format!("/devices/{id}"), &cookie)
        .await
        .text();
    assert!(
        page.contains("doesn&#39;t have the boot cover yet"),
        "{page}"
    );
    post_status(
        &app,
        &token,
        json!({ "capabilities": ["kiosk_escapes_v1", "boot_cover_v1"],
                "boot_cover": { "wanted": true, "tripped": true, "last_handover": "nope" } }),
    )
    .await;
    let stored_cover = stored(&app, "boot_cover_json", id).await.unwrap();
    assert!(stored_cover.contains("\"tripped\":true"), "{stored_cover}");
    assert!(!stored_cover.contains("nope"));
    let page = app
        .get_page(&format!("/devices/{id}"), &cookie)
        .await
        .text();
    assert!(page.contains("crashed twice during one start"), "{page}");

    // One auto-saving form: a missing checkbox is off.
    app.request_form(
        Method::POST,
        &format!("/devices/{id}/kiosk-escapes"),
        Some(&cookie),
        &[("notification_auto_cancel", "on")],
    )
    .await;
    let p = policy(&app, &token).await;
    assert_eq!(p["update_fence"], json!(false));
    assert_eq!(p["notification_auto_cancel"], json!(true));
    assert_eq!(p["boot_cover"], json!(false));

    let res = app
        .request_form(
            Method::POST,
            "/devices/9999/kiosk-escapes",
            Some(&cookie),
            &[("update_fence", "on")],
        )
        .await;
    assert_eq!(res.status, StatusCode::NOT_FOUND);
    // No session: nothing written.
    let res = app
        .request_form(
            Method::POST,
            &format!("/devices/{id}/kiosk-escapes"),
            None,
            &[("update_fence", "on")],
        )
        .await;
    assert!(res.status.is_redirection());
    assert_eq!(policy(&app, &token).await["update_fence"], json!(false));
}

#[tokio::test]
async fn update_fence_and_notification_cancels_are_stored_sanitized_and_capped() {
    let app = TestApp::new().await;
    let (id, token) = app.enrolled_device("phone").await;
    let entries: Vec<Value> = (0..30)
        .map(|i| {
            json!({
                "package_name": format!("com.example.app{i}{}", "x".repeat(80)),
                "channel": "nag",
                "cancelled": 2,
                "snoozed": 1,
                "title": "Message from Grandma",
            })
        })
        .collect();
    post_status(
        &app,
        &token,
        json!({
            "update_fence": {
                "enabled": true, "state": "fenced", "unsuspendable": ["com.oem.home"],
                "last_release": null, "home_role_held": true, "pending_tag": "launcher-v1.4.0",
                "pending_since_ms": 1, "waiting_for": "outside_window", "session_key": "secret",
            },
            "notification_cancels": { "active": true, "entries": entries, "dropped": 2, "text": "x" },
        }),
    )
    .await;
    let fence: Value =
        serde_json::from_str(&stored(&app, "update_fence_json", id).await.unwrap()).unwrap();
    assert_eq!(fence["state"], "fenced");
    assert_eq!(fence["unsuspendable"], json!(["com.oem.home"]));
    assert!(fence.get("session_key").is_none(), "{fence}");
    let raw = stored(&app, "notification_cancels_json", id).await.unwrap();
    assert!(!raw.contains("Grandma"), "{raw}");
    let cancels: Value = serde_json::from_str(&raw).unwrap();
    let list = cancels["entries"].as_array().unwrap();
    assert_eq!(list.len(), 20);
    assert!(
        list.iter()
            .all(|e| e["package_name"].as_str().unwrap().chars().count() <= 64)
    );
    assert_eq!(cancels["dropped"], 12);
    assert!(cancels.get("text").is_none());

    // Not an object: nothing stored.
    post_status(
        &app,
        &token,
        json!({ "update_fence": "fenced", "notification_cancels": [1] }),
    )
    .await;
    assert_eq!(stored(&app, "update_fence_json", id).await, None);
    assert_eq!(stored(&app, "notification_cancels_json", id).await, None);
}

#[tokio::test]
async fn device_page_explains_the_fence_and_the_filter() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("phone").await;
    // A launcher without the capability.
    post_status(&app, &token, json!({ "capabilities": ["pin_lock_v1"] })).await;
    let html = app
        .get_page(&format!("/devices/{id}"), &cookie)
        .await
        .text();
    assert!(
        html.contains("Launcher updates and notifications"),
        "{html}"
    );
    assert!(
        html.contains("neither the update fence nor the notification filter"),
        "{html}"
    );

    app.request_form(
        Method::POST,
        &format!("/devices/{id}/kiosk-escapes"),
        Some(&cookie),
        &[("update_fence", "on"), ("notification_auto_cancel", "on")],
    )
    .await;
    post_status(
        &app,
        &token,
        json!({
            "capabilities": ["kiosk_escapes_v1"],
            "notification_listener_enabled": false,
            "update_fence": {
                "enabled": true, "state": "none", "unsuspendable": ["com.oem.home"],
                "last_release": "install_failed", "home_role_held": true,
                "pending_tag": "launcher-v1.4.0", "pending_since_ms": 1, "waiting_for": "call",
            },
            "notification_cancels": { "active": false, "entries": [
                { "package_name": "com.google.android.gms", "channel": "nag", "cancelled": 3, "snoozed": 1 },
            ], "dropped": 0 },
        }),
    )
    .await;
    let html = app
        .get_page(&format!("/devices/{id}"), &cookie)
        .await
        .text();
    assert!(!html.contains("neither the update fence"), "{html}");
    assert!(html.contains("com.oem.home"), "{html}");
    assert!(html.contains("failed to install"), "{html}");
    assert!(html.contains("launcher-v1.4.0"), "{html}");
    assert!(html.contains("a call was on"), "{html}");
    assert!(html.contains("no notification access"), "{html}");
    assert!(html.contains("com.google.android.gms (nag)"), "{html}");
    assert!(html.contains("never its title or text"), "{html}");
}
