//! Android's blocked-numbers list (`blocked_numbers`): the launcher's `call_state` counts from
//! `contracts/core/blocked_numbers.json`, the security-log line per class that rose, and the Calls page's
//! orange lines (never for the kid's own blocks the phone keeps).

use axum::http::StatusCode;
use serde_json::{Value, json};

use super::TestApp;

fn contract() -> Value {
    serde_json::from_str(
        &std::fs::read_to_string(crate::contracts::path("core/blocked_numbers.json")).unwrap(),
    )
    .unwrap()
}

async fn set_modes(app: &TestApp, id: i64, calls: &str, sms: &str) {
    sqlx::query("UPDATE device_policy SET calls_mode = ?, sms_mode = ? WHERE device_id = ?")
        .bind(calls)
        .bind(sms)
        .bind(id)
        .execute(&app.db)
        .await
        .unwrap();
    // Someone may call and text in, so the page has no "no contacts" line of its own.
    let contact: i64 = sqlx::query_scalar(
        "INSERT INTO contacts (name, phone_number) VALUES ('Mormor', '+4799999901') RETURNING id",
    )
    .fetch_one(&app.db)
    .await
    .unwrap();
    sqlx::query(
        "INSERT INTO device_contacts (device_id, contact_id, allow_inbound, allow_outbound, \
         show_on_home, sort_order, sms_in) VALUES (?, ?, 1, 1, 0, 0, 1)",
    )
    .bind(id)
    .bind(contact)
    .execute(&app.db)
    .await
    .unwrap();
}

fn call_state(blocked: &Value) -> Value {
    let mut state = json!({
        "state": "managed",
        "dialer_role_held": true,
        "redirection_role_held": true,
        "screening_role_held": true,
        "default_dialer": "me.vibb.launcher",
        "default_sms_package": "me.vibb.launcher",
        "call_log_readable": true,
    });
    for (key, value) in blocked.as_object().unwrap() {
        state[key] = value.clone();
    }
    state
}

async fn lines(app: &TestApp) -> Vec<String> {
    sqlx::query_scalar(
        "SELECT params FROM security_events WHERE event_type = 'phone_blocked_cleared' ORDER BY id",
    )
    .fetch_all(&app.db)
    .await
    .unwrap()
}

async fn calls_page(app: &TestApp, cookie: &str, id: i64) -> String {
    let res = app.get_page(&format!("/devices/{id}/calls"), cookie).await;
    assert_eq!(res.status, StatusCode::OK);
    res.text()
}

/// The contract's report is stored as sent; a rise is one line per class (the number never), the same counts again
/// none; the Calls page says it in orange with its time, and the device page's Attention has it.
#[tokio::test]
async fn a_cleared_number_is_logged_and_shown() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("phone").await;
    set_modes(&app, id, "managed", "managed").await;
    let contract = contract();

    // The first report: nothing to compare with, nothing logged.
    app.post_status(
        &token,
        json!({"call_state": call_state(&contract["call_state_unreadable"])}),
    )
    .await;
    assert!(lines(&app).await.is_empty());

    let mut report = contract["call_state"].clone();
    report["blocked_cleared_at"] = json!(
        (chrono::Utc::now() - chrono::Duration::minutes(2))
            .to_rfc3339_opts(chrono::SecondsFormat::Secs, true)
    );
    app.post_status(&token, json!({"call_state": call_state(&report)}))
        .await;
    let stored: String = sqlx::query_scalar(
        "SELECT call_state_json FROM device_status WHERE device_id = ? ORDER BY id DESC LIMIT 1",
    )
    .bind(id)
    .fetch_one(&app.db)
    .await
    .unwrap();
    let stored: Value = serde_json::from_str(&stored).unwrap();
    for (key, value) in report.as_object().unwrap() {
        assert_eq!(&stored[key], value, "{key}");
    }
    let logged = lines(&app).await;
    assert_eq!(logged.len(), 2, "{logged:?}");
    assert!(logged[0].contains("blocked_cleared.contact"), "{logged:?}");
    assert!(
        logged[1].contains("blocked_cleared.emergency"),
        "{logged:?}"
    );
    let detail: String = sqlx::query_scalar(
        "SELECT detail FROM security_events WHERE event_type = 'phone_blocked_cleared' LIMIT 1",
    )
    .fetch_one(&app.db)
    .await
    .unwrap();
    assert_eq!(detail, format!("device {id}"));

    // The same counts again: no new line.
    app.post_status(&token, json!({"call_state": call_state(&report)}))
        .await;
    assert_eq!(lines(&app).await.len(), 2);

    let page = calls_page(&app, &cookie, id).await;
    assert!(page.contains("Blocked number removed"), "{page}");
    assert!(page.contains("data-warning"), "{page}");
    let summary = app
        .get_page(&format!("/devices/{id}"), &cookie)
        .await
        .text();
    assert!(summary.contains("Blocked number removed"), "{summary}");
    let security = app.get_page("/security", &cookie).await.text();
    assert!(
        security.contains("Block removed: 1 contact number"),
        "{security}"
    );

    // Norwegian words.
    let page = app
        .get_page(&format!("/devices/{id}/calls?lang=nb"), &cookie)
        .await
        .text();
    assert!(page.contains("Blokkert nummer fjernet"), "{page}");
}

/// Two days on, the line has left Attention but stays on the Calls page; after 7 days it is gone.
#[tokio::test]
async fn a_cleared_number_leaves_attention_after_a_day() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("phone").await;
    set_modes(&app, id, "managed", "managed").await;
    for (days, on_page) in [(2, true), (8, false)] {
        let mut report = contract()["call_state"].clone();
        report["blocked_cleared_at"] =
            json!((chrono::Utc::now() - chrono::Duration::days(days)).to_rfc3339());
        app.post_status(&token, json!({"call_state": call_state(&report)}))
            .await;
        let summary = app
            .get_page(&format!("/devices/{id}"), &cookie)
            .await
            .text();
        assert!(
            !summary.contains("Blocked number removed"),
            "{days}: {summary}"
        );
        let page = calls_page(&app, &cookie, id).await;
        assert_eq!(page.contains("Blocked number removed"), on_page, "{days}");
    }
}

/// Rows the rule clears left in the list are orange only while a channel is managed (the phone should have cleared
/// them); with both channels unmanaged the list is the kid's own, the parent's choice: nothing. The kid's own blocks the
/// phone keeps are never a line, however many.
#[tokio::test]
async fn numbers_left_are_orange_only_while_managed() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("phone").await;
    set_modes(&app, id, "unmanaged", "managed").await;
    let mut report = contract()["call_state"].clone();
    report["blocked_left"] = json!(2);
    report.as_object_mut().unwrap().remove("blocked_cleared_at");
    app.post_status(&token, json!({"call_state": call_state(&report)}))
        .await;
    let page = calls_page(&app, &cookie, id).await;
    assert!(page.contains("Block not removed"), "{page}");
    // Kept rows alone: nothing.
    report["blocked_left"] = json!(0);
    report["blocked_kept"] = json!(9);
    app.post_status(&token, json!({"call_state": call_state(&report)}))
        .await;
    let page = calls_page(&app, &cookie, id).await;
    assert!(!page.contains("Block not removed"), "{page}");
    report["blocked_left"] = json!(2);
    app.post_status(&token, json!({"call_state": call_state(&report)}))
        .await;
    sqlx::query("UPDATE device_policy SET sms_mode = 'unmanaged' WHERE device_id = ?")
        .bind(id)
        .execute(&app.db)
        .await
        .unwrap();
    let page = calls_page(&app, &cookie, id).await;
    assert!(!page.contains("Block not removed"), "{page}");
}

/// The first sweep after the phone became managed (the parent's switch) is counted apart: no line, no orange, no
/// Attention, however much it cleared.
#[tokio::test]
async fn the_initial_clearing_is_quiet() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("phone").await;
    set_modes(&app, id, "managed", "managed").await;
    let contract = contract();
    app.post_status(
        &token,
        json!({"call_state": call_state(&contract["call_state_unreadable"])}),
    )
    .await;
    let mut report = contract["call_state_unreadable"].clone();
    report["blocked_readable"] = json!(true);
    report["blocked_left"] = json!(0);
    report["blocked_cleared_initial"] = json!(5);
    app.post_status(&token, json!({"call_state": call_state(&report)}))
        .await;
    let stored: String = sqlx::query_scalar(
        "SELECT call_state_json FROM device_status WHERE device_id = ? ORDER BY id DESC LIMIT 1",
    )
    .bind(id)
    .fetch_one(&app.db)
    .await
    .unwrap();
    let stored: Value = serde_json::from_str(&stored).unwrap();
    assert_eq!(stored["blocked_cleared_initial"], json!(5));
    assert!(lines(&app).await.is_empty());
    let page = calls_page(&app, &cookie, id).await;
    assert!(!page.contains("Blocked number removed"), "{page}");
    assert!(!page.contains("Block not removed"), "{page}");
    let summary = app
        .get_page(&format!("/devices/{id}"), &cookie)
        .await
        .text();
    assert!(!summary.contains("Blocked number removed"), "{summary}");
}
