//! Sound mode (design 18): the kid's sound row is bit 8 of `quick_controls_mask`, and the phone's
//! ringer mode and Do Not Disturb filter from every status report show on the device page.

use axum::http::{Method, StatusCode};
use serde_json::{Value, json};

use super::TestApp;

async fn policy(app: &TestApp, token: &str) -> Value {
    app.request(Method::GET, "/api/devices/policy", Some(token), None)
        .await
        .json()
}

async fn report(app: &TestApp, token: &str, extra: Value) {
    let mut body = json!({ "lock_reason": "NONE", "kiosk_engaged": true });
    for (key, value) in extra.as_object().unwrap() {
        body[key] = value.clone();
    }
    let res = app
        .request(Method::POST, "/api/devices/status", Some(token), Some(body))
        .await;
    assert_eq!(res.status, StatusCode::NO_CONTENT, "{}", res.text());
}

async fn stored(app: &TestApp, id: i64) -> (Option<String>, Option<String>) {
    sqlx::query_as(
        "SELECT ringer_mode, interruption_filter FROM device_status WHERE device_id = ? \
         ORDER BY reported_at DESC, id DESC LIMIT 1",
    )
    .bind(id)
    .fetch_one(&app.db)
    .await
    .unwrap()
}

/// The device page's "Quick Controls" box for the sound row: saved with the card, reaches the
/// phone as bit 8, and the policy shape stays the same (the mask is still one integer).
#[tokio::test]
async fn the_sound_bit_saves_and_reaches_the_phone() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("phone").await;

    let res = app
        .request_form(
            Method::POST,
            &format!("/devices/{id}/policy"),
            Some(&cookie),
            &[("quick_control_wifi", "on"), ("quick_control_sound", "on")],
        )
        .await;
    assert!(res.status.is_redirection());
    // Back to the page; scroll-restore keeps the place (no jump to the top).
    assert_eq!(
        res.headers.get("location").unwrap(),
        &format!("/devices/{id}")
    );
    assert_eq!(
        policy(&app, &token).await["quick_controls_mask"],
        json!(1 | 8)
    );
    let html = app
        .get_page(&format!("/devices/{id}"), &cookie)
        .await
        .text();
    assert!(
        html.contains(r#"name="quick_control_sound" value="on" checked"#),
        "{html}"
    );
    assert!(html.contains("This box only shows the choice"));
    assert!(html.contains("/static/scroll-restore.js"));

    app.request_form(
        Method::POST,
        &format!("/devices/{id}/policy"),
        Some(&cookie),
        &[("quick_control_wifi", "on")],
    )
    .await;
    assert_eq!(policy(&app, &token).await["quick_controls_mask"], json!(1));
    let html = app
        .get_page(&format!("/devices/{id}"), &cookie)
        .await
        .text();
    assert!(!html.contains(r#"name="quick_control_sound" value="on" checked"#));
}

/// The phone's ringer mode and Do Not Disturb at the last sync: stored when known, NULL from an
/// older launcher or for a value the launcher never sends, and shown in the Status card.
#[tokio::test]
async fn sound_state_is_stored_and_shown() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("phone").await;
    let (other, _) = app.enrolled_device("other").await;
    let page = |device: i64| {
        let app = &app;
        let cookie = cookie.clone();
        async move {
            app.get_page(&format!("/devices/{device}"), &cookie)
                .await
                .text()
        }
    };

    // An older launcher: no fields, nothing shown.
    report(&app, &token, json!({})).await;
    assert_eq!(stored(&app, id).await, (None, None));
    assert!(!page(id).await.contains("at the last sync"));

    report(
        &app,
        &token,
        json!({ "ringer_mode": "vibrate", "interruption_filter": "priority" }),
    )
    .await;
    assert_eq!(
        stored(&app, id).await,
        (Some("vibrate".into()), Some("priority".into()))
    );
    let html = page(id).await;
    assert!(
        html.contains("Sound at the last sync: silent (vibrate)"),
        "{html}"
    );
    assert!(html.contains("Do Not Disturb at the last sync: on (priority only)"));
    // Only this phone.
    assert!(!page(other).await.contains("at the last sync"));

    // DND off is no line; sound on is one.
    report(
        &app,
        &token,
        json!({ "ringer_mode": "normal", "interruption_filter": "all" }),
    )
    .await;
    let html = page(id).await;
    assert!(html.contains("Sound at the last sync: on."));
    assert!(!html.contains("Do Not Disturb at the last sync"));

    // Unknown values (and explicit nulls) are stored as NULL, never shown.
    report(
        &app,
        &token,
        json!({ "ringer_mode": "<b>loud</b>", "interruption_filter": null }),
    )
    .await;
    assert_eq!(stored(&app, id).await, (None, None));
    let html = page(id).await;
    assert!(!html.contains("at the last sync") && !html.contains("loud"));
}
