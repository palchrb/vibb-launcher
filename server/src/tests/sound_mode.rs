//! Sound mode (design 18): the kid's sound row is bit 8 of `quick_controls_mask`, and the phone's
//! ringer mode and Do Not Disturb filter from every status report show on About This Phone.

use axum::http::{Method, StatusCode};
use serde_json::{Value, json};

use super::{Section, TestApp, device_page};

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

/// Home Screen's "Sound or silent" quick control: saved with the quick controls
/// (`section=quick`), reaches the phone as bit 8, and the policy shape stays the same (the mask is
/// still one integer).
#[tokio::test]
async fn the_sound_bit_saves_and_reaches_the_phone() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("phone").await;
    let home = device_page(id, Section::Home);

    let res = app
        .request_form(
            Method::POST,
            &format!("/devices/{id}/policy"),
            Some(&cookie),
            &[
                ("section", "quick"),
                ("quick_control_wifi", "on"),
                ("quick_control_sound", "on"),
            ],
        )
        .await;
    // Back to its group (§5.1.4); scroll-restore keeps the place (no jump to the top).
    super::assert_redirect(&res, &home, Some("quick-controls"));
    assert_eq!(
        policy(&app, &token).await["quick_controls_mask"],
        json!(1 | 8)
    );
    let html = app.get_page(&home, &cookie).await.text();
    assert_eq!(
        super::input_checked(&html, "quick_control_sound", "on"),
        Some(true)
    );
    assert!(html.contains("<span class=\"row-label\">Sound or silent</span>"));
    assert!(html.contains("/static/scroll-restore.js"));

    app.request_form(
        Method::POST,
        &format!("/devices/{id}/policy"),
        Some(&cookie),
        &[("section", "quick"), ("quick_control_wifi", "on")],
    )
    .await;
    assert_eq!(policy(&app, &token).await["quick_controls_mask"], json!(1));
    let html = app.get_page(&home, &cookie).await.text();
    assert_eq!(
        super::input_checked(&html, "quick_control_sound", "on"),
        Some(false)
    );
}

/// The phone's ringer mode and Do Not Disturb at the last sync: stored when known, NULL from an
/// older launcher or for a value the launcher never sends, and shown as About This Phone's Sound
/// rows (design 31 step 3; the Status card's lines until then). Do Not Disturb on is also an
/// Attention alarm linking there.
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
            app.get_page(&device_page(device, Section::About), &cookie)
                .await
                .text()
        }
    };
    // The value of the row `key`, if there is one.
    let value = |html: &str, key: &str| -> Option<String> {
        let row = &html[html.find(&format!("data-row=\"{key}\""))?..];
        let value = &row[row.find("row-value")?..];
        let value = &value[value.find('>')? + 1..];
        Some(value[..value.find('<')?].to_string())
    };

    // An older launcher: no fields, nothing shown.
    report(&app, &token, json!({})).await;
    assert_eq!(stored(&app, id).await, (None, None));
    let html = page(id).await;
    assert!(!html.contains("id=\"sound\""), "{html}");

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
    assert_eq!(value(&html, "ringer").as_deref(), Some("Vibrate"), "{html}");
    assert_eq!(
        value(&html, "do-not-disturb").as_deref(),
        Some("Priority only")
    );
    assert!(
        html.contains("Only the calls it lets through ring"),
        "{html}"
    );
    let summary = app
        .get_page(&device_page(id, Section::Summary), &cookie)
        .await
        .text();
    assert!(summary.contains(&format!("href=\"/devices/{id}/about#sound\"")));
    // Only this phone.
    assert!(!page(other).await.contains("id=\"sound\""));

    // Off and on are plain rows.
    report(
        &app,
        &token,
        json!({ "ringer_mode": "normal", "interruption_filter": "all" }),
    )
    .await;
    let html = page(id).await;
    assert_eq!(value(&html, "ringer").as_deref(), Some("On"));
    assert_eq!(value(&html, "do-not-disturb").as_deref(), Some("Off"));
    assert!(!html.contains("row-value-alarm"), "{html}");

    // Unknown values (and explicit nulls) are stored as NULL, never shown.
    report(
        &app,
        &token,
        json!({ "ringer_mode": "<b>loud</b>", "interruption_filter": null }),
    )
    .await;
    assert_eq!(stored(&app, id).await, (None, None));
    let html = page(id).await;
    assert!(!html.contains("id=\"sound\"") && !html.contains("loud"));
}
