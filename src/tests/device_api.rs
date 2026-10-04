use axum::http::{Method, StatusCode};
use serde_json::json;

use super::TestApp;

#[tokio::test]
async fn enroll_returns_token_and_code_is_single_use() {
    let app = TestApp::new().await;
    let (id, code) = app.create_device("phone").await;

    let res = app
        .request(
            Method::POST,
            "/api/devices/enroll",
            None,
            Some(json!({ "enrollment_code": code })),
        )
        .await;
    assert_eq!(res.status, StatusCode::OK);
    let body = res.json();
    assert_eq!(body["device_id"], id);
    assert!(body["device_token"].as_str().is_some_and(|t| !t.is_empty()));

    let replay = app
        .request(
            Method::POST,
            "/api/devices/enroll",
            None,
            Some(json!({ "enrollment_code": code })),
        )
        .await;
    assert_eq!(replay.status, StatusCode::UNAUTHORIZED);
}

#[tokio::test]
async fn enroll_rejects_expired_code() {
    let app = TestApp::new().await;
    let (id, code) = app.create_device("phone").await;
    sqlx::query(
        "UPDATE devices SET enrollment_code_expires_at = datetime('now', '-1 minute') WHERE id = ?",
    )
    .bind(id)
    .execute(&app.db)
    .await
    .unwrap();

    let res = app
        .request(
            Method::POST,
            "/api/devices/enroll",
            None,
            Some(json!({ "enrollment_code": code })),
        )
        .await;
    assert_eq!(res.status, StatusCode::UNAUTHORIZED);
}

#[tokio::test]
async fn device_api_requires_valid_bearer_token() {
    let app = TestApp::new().await;
    app.enrolled_device("phone").await;

    let missing = app
        .request(Method::GET, "/api/devices/policy", None, None)
        .await;
    assert_eq!(missing.status, StatusCode::UNAUTHORIZED);

    let wrong = app
        .request(
            Method::GET,
            "/api/devices/policy",
            Some("not-a-token"),
            None,
        )
        .await;
    assert_eq!(wrong.status, StatusCode::UNAUTHORIZED);
}

#[tokio::test]
async fn policy_reflects_stored_allowlist_and_kiosk() {
    let app = TestApp::new().await;
    let (id, token) = app.enrolled_device("phone").await;
    sqlx::query("UPDATE device_policy SET allowlist_json = ? WHERE device_id = ?")
        .bind(r#"["org.example.music","org.example.chat"]"#)
        .bind(id)
        .execute(&app.db)
        .await
        .unwrap();

    let res = app
        .request(Method::GET, "/api/devices/policy", Some(&token), None)
        .await;
    assert_eq!(res.status, StatusCode::OK);
    let policy = res.json();
    assert_eq!(policy["kiosk_desired"], true);
    assert_eq!(
        policy["allowlist"],
        json!(["org.example.music", "org.example.chat"])
    );
}

#[tokio::test]
async fn pending_command_is_delivered_once() {
    let app = TestApp::new().await;
    let (id, token) = app.enrolled_device("phone").await;
    sqlx::query("INSERT INTO device_commands (device_id, command) VALUES (?, 'ring')")
        .bind(id)
        .execute(&app.db)
        .await
        .unwrap();

    let first = app
        .request(Method::GET, "/api/devices/policy", Some(&token), None)
        .await
        .json();
    assert_eq!(first["pending_command"]["command"], "ring");

    let second = app
        .request(Method::GET, "/api/devices/policy", Some(&token), None)
        .await
        .json();
    assert!(second["pending_command"].is_null());
}

#[tokio::test]
async fn devices_only_see_their_own_commands() {
    let app = TestApp::new().await;
    let (a, _) = app.enrolled_device("a").await;
    let (_, token_b) = app.enrolled_device("b").await;
    sqlx::query("INSERT INTO device_commands (device_id, command) VALUES (?, 'wipe')")
        .bind(a)
        .execute(&app.db)
        .await
        .unwrap();

    let policy_b = app
        .request(Method::GET, "/api/devices/policy", Some(&token_b), None)
        .await
        .json();
    assert!(policy_b["pending_command"].is_null());
}

/// A device whose policy row is missing must not be handed an unrestricted policy: today
/// `device_api::policy` falls back to `DevicePolicy::default()` (kiosk off, no allowlist) and
/// answers 200, which the launcher applies as "no restrictions". Fixed by the fail-closed PR.
#[tokio::test]
#[ignore = "known fail-open bug: missing policy row yields an unrestricted policy"]
async fn missing_policy_row_does_not_unlock() {
    let app = TestApp::new().await;
    let (id, token) = app.enrolled_device("phone").await;
    sqlx::query("DELETE FROM device_policy WHERE device_id = ?")
        .bind(id)
        .execute(&app.db)
        .await
        .unwrap();

    let res = app
        .request(Method::GET, "/api/devices/policy", Some(&token), None)
        .await;
    assert!(
        res.status != StatusCode::OK || res.json()["kiosk_desired"] == true,
        "missing policy row produced an unrestricted policy: {}",
        String::from_utf8_lossy(&res.body)
    );
}

#[tokio::test]
async fn admin_pages_redirect_to_login_without_session() {
    let app = TestApp::new().await;
    let res = app.request(Method::GET, "/devices", None, None).await;
    assert!(
        res.status.is_redirection(),
        "expected a redirect, got {}",
        res.status
    );
}
