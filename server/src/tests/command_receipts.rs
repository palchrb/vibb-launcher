//! Security review L12: the phone confirms each remote command before running it
//! (`POST /api/devices/command-receipt`), and a command it didn't confirm is offered again.

use axum::http::{Method, StatusCode};
use serde_json::{Value, json};

use super::TestApp;

async fn policy(app: &TestApp, token: &str) -> Value {
    let res = app
        .request(Method::GET, "/api/devices/policy", Some(token), None)
        .await;
    assert_eq!(res.status, StatusCode::OK, "{}", res.text());
    res.json()
}

/// A status report that says the launcher confirms commands.
async fn report_receipts(app: &TestApp, token: &str) {
    let res = app
        .request(
            Method::POST,
            "/api/devices/status",
            Some(token),
            Some(json!({ "lock_reason": "NONE", "kiosk_engaged": true,
                         "capabilities": ["call_policy_v1", "command_receipt_v1"] })),
        )
        .await;
    assert_eq!(res.status, StatusCode::NO_CONTENT, "{}", res.text());
}

async fn queue(app: &TestApp, device: i64, command: &str) -> i64 {
    sqlx::query_scalar(
        "INSERT INTO device_commands (device_id, command) VALUES (?, ?) RETURNING id",
    )
    .bind(device)
    .bind(command)
    .fetch_one(&app.db)
    .await
    .unwrap()
}

/// Moves the command's last delivery `minutes` into the past.
async fn age_delivery(app: &TestApp, id: i64, minutes: i64) {
    sqlx::query("UPDATE device_commands SET delivered_at = datetime('now', ?) WHERE id = ?")
        .bind(format!("-{minutes} minutes"))
        .bind(id)
        .execute(&app.db)
        .await
        .unwrap();
}

async fn row(app: &TestApp, id: i64) -> (Option<String>, i64, Option<bool>, Option<String>) {
    sqlx::query_as(
        "SELECT received_at, deliveries, succeeded, result FROM device_commands WHERE id = ?",
    )
    .bind(id)
    .fetch_one(&app.db)
    .await
    .unwrap()
}

async fn receipt(app: &TestApp, token: &str, id: i64) -> StatusCode {
    app.request(
        Method::POST,
        "/api/devices/command-receipt",
        Some(token),
        Some(json!({ "command_id": id })),
    )
    .await
    .status
}

#[tokio::test]
async fn a_receipt_is_stored_for_the_phones_own_command_only() {
    let app = TestApp::new().await;
    let (a, token_a) = app.enrolled_device("a").await;
    let (_, token_b) = app.enrolled_device("b").await;
    let id = queue(&app, a, "wipe").await;

    assert_eq!(receipt(&app, &token_b, id).await, StatusCode::NO_CONTENT);
    assert_eq!(
        row(&app, id).await.0,
        None,
        "another phone can't confirm it"
    );

    assert_eq!(receipt(&app, &token_a, id).await, StatusCode::NO_CONTENT);
    let first = row(&app, id).await.0.expect("confirmed");
    sqlx::query("UPDATE device_commands SET received_at = '2026-10-11 10:00:00' WHERE id = ?")
        .bind(id)
        .execute(&app.db)
        .await
        .unwrap();
    assert_eq!(receipt(&app, &token_a, id).await, StatusCode::NO_CONTENT);
    assert_eq!(
        row(&app, id).await.0.as_deref(),
        Some("2026-10-11 10:00:00"),
        "the first receipt counts (was {first})"
    );

    let unauthenticated = app
        .request(
            Method::POST,
            "/api/devices/command-receipt",
            None,
            Some(json!({ "command_id": id })),
        )
        .await;
    assert_eq!(unauthenticated.status, StatusCode::UNAUTHORIZED);
}

#[tokio::test]
async fn an_unconfirmed_command_is_offered_again_to_a_phone_that_confirms() {
    let app = TestApp::new().await;
    let (device, token) = app.enrolled_device("phone").await;
    report_receipts(&app, &token).await;
    let id = queue(&app, device, "lock").await;

    assert_eq!(policy(&app, &token).await["pending_command"]["id"], id);
    assert!(
        policy(&app, &token).await["pending_command"].is_null(),
        "not twice within two minutes"
    );
    // The answer was lost: after two minutes it comes again, the same id.
    age_delivery(&app, id, 3).await;
    assert_eq!(policy(&app, &token).await["pending_command"]["id"], id);
    assert_eq!(row(&app, id).await.1, 2);
    // A third and last time.
    age_delivery(&app, id, 3).await;
    assert_eq!(policy(&app, &token).await["pending_command"]["id"], id);
    age_delivery(&app, id, 3).await;
    assert!(
        policy(&app, &token).await["pending_command"].is_null(),
        "at most three deliveries"
    );
    assert_eq!(row(&app, id).await.1, 3);

    // A confirmed one is never offered again.
    let ring = queue(&app, device, "ring").await;
    assert_eq!(policy(&app, &token).await["pending_command"]["id"], ring);
    assert_eq!(receipt(&app, &token, ring).await, StatusCode::NO_CONTENT);
    age_delivery(&app, ring, 30).await;
    assert!(policy(&app, &token).await["pending_command"].is_null());
}

#[tokio::test]
async fn an_older_launcher_gets_a_command_once() {
    let app = TestApp::new().await;
    let (device, token) = app.enrolled_device("phone").await;
    let id = queue(&app, device, "lock").await;
    assert_eq!(policy(&app, &token).await["pending_command"]["id"], id);
    age_delivery(&app, id, 30).await;
    assert!(policy(&app, &token).await["pending_command"].is_null());
    assert_eq!(row(&app, id).await.1, 1);
}

#[tokio::test]
async fn a_result_confirms_and_says_whether_it_worked() {
    let app = TestApp::new().await;
    let (device, token) = app.enrolled_device("phone").await;
    let id = queue(&app, device, "wipe").await;
    policy(&app, &token).await;
    let res = app
        .request(
            Method::POST,
            "/api/devices/command-result",
            Some(&token),
            Some(json!({ "command_id": id, "success": false,
                         "message": "wipe failed, the phone keeps its data: SecurityException: x" })),
        )
        .await;
    assert_eq!(res.status, StatusCode::NO_CONTENT);
    let (received, _, succeeded, result) = row(&app, id).await;
    assert!(received.is_some(), "a result implies the receipt");
    assert_eq!(succeeded, Some(false));
    assert!(result.unwrap().starts_with("wipe failed"));
}

#[tokio::test]
async fn a_new_command_stops_an_unconfirmed_one_coming_again() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (device, token) = app.enrolled_device("phone").await;
    report_receipts(&app, &token).await;
    let ring = queue(&app, device, "ring").await;
    assert_eq!(policy(&app, &token).await["pending_command"]["id"], ring);

    let res = app
        .request_form(
            Method::POST,
            &format!("/devices/{device}/command/lock"),
            Some(&cookie),
            &[],
        )
        .await;
    assert!(res.status.is_redirection(), "{}", res.status);
    age_delivery(&app, ring, 3).await;
    assert_eq!(
        policy(&app, &token).await["pending_command"]["command"],
        "lock"
    );
    age_delivery(&app, ring, 30).await;
    assert!(
        policy(&app, &token).await["pending_command"].is_null(),
        "the ring stays history"
    );
    assert!(row(&app, ring).await.1 >= crate::models::MAX_COMMAND_DELIVERIES);
}

#[tokio::test]
async fn the_locate_page_reads_receipts_and_failures() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (device, token) = app.enrolled_device("phone").await;
    report_receipts(&app, &token).await;
    for (command, delivered, deliveries, received, result, succeeded) in [
        (
            "wipe",
            "datetime('now', '-1 minutes')",
            1,
            "datetime('now')",
            "NULL",
            "NULL",
        ),
        (
            "lock",
            "datetime('now', '-30 minutes')",
            1,
            "datetime('now', '-30 minutes')",
            "NULL",
            "NULL",
        ),
        (
            "ring",
            "datetime('now', '-5 minutes')",
            1,
            "NULL",
            "NULL",
            "NULL",
        ),
        (
            "stop_ring",
            "datetime('now', '-5 minutes')",
            3,
            "NULL",
            "NULL",
            "NULL",
        ),
        (
            "locate",
            "datetime('now', '-1 minutes')",
            1,
            "NULL",
            "NULL",
            "NULL",
        ),
        (
            "end_windows",
            "datetime('now', '-1 minutes')",
            1,
            "datetime('now')",
            "'wipe failed, the phone keeps its data: SecurityException'",
            "0",
        ),
    ] {
        sqlx::query(&format!(
            "INSERT INTO device_commands (device_id, command, delivered_at, deliveries, received_at, \
             acknowledged_at, result, succeeded) VALUES (?, ?, {delivered}, ?, {received}, \
             CASE WHEN {result} IS NULL THEN NULL ELSE '2026-10-11 10:00:00' END, {result}, {succeeded})"
        ))
        .bind(device)
        .bind(command)
        .bind(deliveries)
        .execute(&app.db)
        .await
        .unwrap();
    }
    let page = app
        .get_page(&format!("/devices/locate?device={device}"), &cookie)
        .await
        .text();
    let line = |command: &str| -> String {
        page.split(&format!("<td>{command}</td>"))
            .nth(1)
            .and_then(|rest| rest.split("</tr>").next())
            .unwrap_or_else(|| panic!("no row for {command}"))
            .to_string()
    };
    assert!(
        line("wipe").contains("the phone is wiping now"),
        "{}",
        line("wipe")
    );
    assert!(
        line("lock").contains("never said it finished"),
        "{}",
        line("lock")
    );
    assert!(line("ring").contains("offered again"), "{}", line("ring"));
    assert!(
        line("stop_ring").contains("send it again"),
        "{}",
        line("stop_ring")
    );
    assert!(
        line("locate").contains("not confirmed yet"),
        "{}",
        line("locate")
    );
    assert!(
        line("end_windows").contains("Failed 2026-10-11 10:00:00: wipe failed"),
        "{}",
        line("end_windows")
    );
}
