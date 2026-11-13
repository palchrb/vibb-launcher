//! Design 95 §3.5 (step 95-T1): `GET /api/devices/policy` carries a weak ETag over the exact bytes
//! served, and an `If-None-Match` naming it answers `304` with no body - hashed after the command
//! pop, so a body with a new command is never a 304.

use axum::body::Body;
use axum::http::{Method, Request, StatusCode, header};

use super::{TestApp, TestResponse};

async fn get_policy(app: &TestApp, token: &str, if_none_match: Option<&str>) -> TestResponse {
    let mut builder = Request::builder()
        .method(Method::GET)
        .uri("/api/devices/policy")
        .header(header::AUTHORIZATION, format!("Bearer {token}"))
        .header(header::ACCEPT_ENCODING, "gzip");
    if let Some(tag) = if_none_match {
        builder = builder.header(header::IF_NONE_MATCH, tag);
    }
    app.send(builder.body(Body::empty()).unwrap()).await
}

fn etag(res: &TestResponse) -> String {
    res.headers
        .get(header::ETAG)
        .and_then(|v| v.to_str().ok())
        .expect("the policy carries an ETag")
        .to_string()
}

#[tokio::test]
async fn an_unchanged_policy_answers_304_and_a_changed_one_200() {
    let app = TestApp::new().await;
    let (device, token) = app.enrolled_device("phone").await;

    let first = get_policy(&app, &token, None).await;
    assert_eq!(first.status, StatusCode::OK, "{}", first.text());
    let tag = etag(&first);
    assert!(tag.starts_with("W/\"") && tag.ends_with('"'), "{tag}");
    // The ETag is the hash of exactly the bytes served; never gzip'd (the PIN hashes), still no-store.
    assert_eq!(tag, crate::handlers::device_api::policy_etag(&first.body));
    assert!(first.headers.get(header::CONTENT_ENCODING).is_none());
    assert_eq!(first.headers[header::CACHE_CONTROL], "no-store");

    // The same body again: the same tag, and a 304 without a body for it.
    let again = get_policy(&app, &token, None).await;
    assert_eq!(again.body, first.body);
    assert_eq!(etag(&again), tag);
    let not_modified = get_policy(&app, &token, Some(&tag)).await;
    assert_eq!(not_modified.status, StatusCode::NOT_MODIFIED);
    assert!(not_modified.body.is_empty());
    assert_eq!(etag(&not_modified), tag);
    assert_eq!(not_modified.headers[header::CACHE_CONTROL], "no-store");
    // The strong form of the same tag matches too (weak comparison); `*` never does.
    let strong = tag.trim_start_matches("W/").to_string();
    assert_eq!(
        get_policy(&app, &token, Some(&strong)).await.status,
        StatusCode::NOT_MODIFIED
    );
    assert_eq!(
        get_policy(&app, &token, Some("*")).await.status,
        StatusCode::OK
    );

    // A command queued since: popped before the hash, so the old tag gets the 200 with it.
    sqlx::query("INSERT INTO device_commands (device_id, command) VALUES (?, 'ring')")
        .bind(device)
        .execute(&app.db)
        .await
        .unwrap();
    let with_command = get_policy(&app, &token, Some(&tag)).await;
    assert_eq!(with_command.status, StatusCode::OK);
    assert_eq!(with_command.json()["pending_command"]["command"], "ring");
    assert_ne!(etag(&with_command), tag);

    // The parent's change: a new body, a new tag.
    sqlx::query("UPDATE device_policy SET kiosk_desired = 1 - kiosk_desired WHERE device_id = ?")
        .bind(device)
        .execute(&app.db)
        .await
        .unwrap();
    let after = get_policy(&app, &token, None).await;
    let changed = get_policy(&app, &token, Some(&etag(&after))).await;
    assert_eq!(changed.status, StatusCode::NOT_MODIFIED);
    let old = get_policy(&app, &token, Some(&tag)).await;
    assert_eq!(old.status, StatusCode::OK);
    assert_ne!(etag(&old), tag);
}
