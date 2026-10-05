//! Step 9 part B (design 09-sms-and-fixes.md, QA 09): the pending rule for role warnings after
//! "manage calls" changes, the kiosk app block switch and `play_store_suspendable`.

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

async fn page(app: &TestApp, cookie: &str, uri: &str) -> String {
    let res = app.get_page(uri, cookie).await;
    assert_eq!(res.status, StatusCode::OK);
    res.text()
}

async fn sql(app: &TestApp, query: &str, id: i64) {
    sqlx::query(query).bind(id).execute(&app.db).await.unwrap();
}

async fn roles_changed_at(app: &TestApp, id: i64) -> Option<String> {
    sqlx::query_scalar("SELECT roles_changed_at FROM device_policy WHERE device_id = ?")
        .bind(id)
        .fetch_one(&app.db)
        .await
        .unwrap()
}

#[tokio::test]
async fn managing_calls_stamps_roles_changed_at_only_on_a_change() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, _) = app.enrolled_device("phone").await;
    let uri = format!("/devices/{id}/calls/settings");
    assert_eq!(roles_changed_at(&app, id).await, None);

    app.request_form(Method::POST, &uri, Some(&cookie), &[("managed", "on")])
        .await;
    assert!(roles_changed_at(&app, id).await.is_some());

    sql(
        &app,
        "UPDATE device_policy SET roles_changed_at = 'x' WHERE device_id = ?",
        id,
    )
    .await;
    app.request_form(
        Method::POST,
        &uri,
        Some(&cookie),
        &[("managed", "on"), ("calls_enabled", "on")],
    )
    .await;
    assert_eq!(roles_changed_at(&app, id).await.as_deref(), Some("x"));
    app.request_form(Method::POST, &uri, Some(&cookie), &[])
        .await;
    assert_ne!(roles_changed_at(&app, id).await.as_deref(), Some("x"));
}

#[tokio::test]
async fn role_warnings_wait_for_a_newer_report_but_not_forever() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("phone").await;
    sql(
        &app,
        "UPDATE device_policy SET calls_managed = 1 WHERE device_id = ?",
        id,
    )
    .await;
    let state = json!({
        "state": "managed",
        "dialer_role_held": false,
        "redirection_role_held": true,
        "call_log_readable": true,
    });
    let report = json!({ "capabilities": ["call_policy_v1"], "call_state": state });
    post_status(&app, &token, report.clone()).await;
    let uri = format!("/devices/{id}/calls");

    // The report predates a change made just now: waiting, no role warning, the page reloads.
    sql(
        &app,
        "UPDATE device_status SET reported_at = datetime('now', '-1 minute') WHERE device_id = ?",
        id,
    )
    .await;
    sql(
        &app,
        "UPDATE device_policy SET roles_changed_at = datetime('now') WHERE device_id = ?",
        id,
    )
    .await;
    let html = page(&app, &cookie, &uri).await;
    assert!(html.contains("Waiting for the phone to confirm"), "{html}");
    assert!(!html.contains("Phone app role not active"));
    assert!(html.contains(r#"http-equiv="refresh""#));

    // Five minutes later without a newer report: the real warnings, plus "not confirmed".
    sql(
        &app,
        "UPDATE device_policy SET roles_changed_at = datetime('now', '-6 minutes') \
         WHERE device_id = ?",
        id,
    )
    .await;
    sql(
        &app,
        "UPDATE device_status SET reported_at = datetime('now', '-7 minutes') WHERE device_id = ?",
        id,
    )
    .await;
    let html = page(&app, &cookie, &uri).await;
    assert!(html.contains("confirmed the change made at"), "{html}");
    assert!(html.contains("Phone app role not active"));
    assert!(!html.contains(r#"http-equiv="refresh""#));

    // A newer report that still disagrees is a warning, never "waiting".
    sql(
        &app,
        "UPDATE device_policy SET roles_changed_at = datetime('now', '-1 minute') \
         WHERE device_id = ?",
        id,
    )
    .await;
    post_status(&app, &token, report).await;
    let html = page(&app, &cookie, &uri).await;
    assert!(!html.contains("Waiting for the phone"));
    assert!(html.contains("Phone app role not active"));
}

#[tokio::test]
async fn kiosk_block_switch_and_play_not_suspendable() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("phone").await;
    // The bit never travels inside lock_task_features (an older launcher has no helpers).
    sql(
        &app,
        "UPDATE device_policy SET lock_task_features = 127 WHERE device_id = ?",
        id,
    )
    .await;
    let p = policy(&app, &token).await;
    assert_eq!(p["block_activity_start"], json!(true));
    assert_eq!(p["lock_task_features"], json!(63));

    let mut nudges = app.state.command_notify.subscribe();
    let res = app
        .request_form(
            Method::POST,
            &format!("/devices/{id}/kiosk-block"),
            Some(&cookie),
            &[],
        )
        .await;
    assert!(res.status.is_redirection());
    assert_eq!(nudges.try_recv().ok(), Some(id));
    assert_eq!(
        policy(&app, &token).await["block_activity_start"],
        json!(false)
    );
    let res = app
        .request_form(
            Method::POST,
            "/devices/9999/kiosk-block",
            Some(&cookie),
            &[("block_activity_start", "on")],
        )
        .await;
    assert_eq!(res.status, StatusCode::NOT_FOUND);
    // No session: nothing written.
    let res = app
        .request_form(
            Method::POST,
            &format!("/devices/{id}/kiosk-block"),
            None,
            &[("block_activity_start", "on")],
        )
        .await;
    assert!(res.status.is_redirection());
    assert_eq!(
        policy(&app, &token).await["block_activity_start"],
        json!(false)
    );

    post_status(&app, &token, json!({ "play_store_suspendable": false })).await;
    let stored: Option<bool> =
        sqlx::query_scalar("SELECT play_store_suspendable FROM device_status WHERE device_id = ?")
            .bind(id)
            .fetch_one(&app.db)
            .await
            .unwrap();
    assert_eq!(stored, Some(false));
    let html = page(&app, &cookie, &format!("/devices/{id}")).await;
    assert!(html.contains("kiosk app block below is off"), "{html}");
}

#[tokio::test]
async fn an_error_page_never_reloads_itself() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("phone").await;
    sql(
        &app,
        "UPDATE device_policy SET calls_managed = 1 WHERE device_id = ?",
        id,
    )
    .await;
    post_status(&app, &token, json!({ "capabilities": ["call_policy_v1"] })).await;
    sql(
        &app,
        "UPDATE device_status SET reported_at = datetime('now', '-1 minute') WHERE device_id = ?",
        id,
    )
    .await;
    sql(
        &app,
        "UPDATE device_policy SET roles_changed_at = datetime('now') WHERE device_id = ?",
        id,
    )
    .await;
    // Waiting: the plain page reloads, a 400 with the typed contact doesn't (qa-09-code #9).
    assert!(
        page(&app, &cookie, &format!("/devices/{id}/calls"))
            .await
            .contains(r#"http-equiv="refresh""#)
    );
    let res = app
        .request_form(
            Method::POST,
            &format!("/devices/{id}/contacts"),
            Some(&cookie),
            &[("name", "Mamma"), ("number", "abc")],
        )
        .await;
    assert_eq!(res.status, StatusCode::BAD_REQUEST);
    let html = res.text();
    assert!(html.contains("Mamma"));
    assert!(!html.contains(r#"http-equiv="refresh""#));
}
