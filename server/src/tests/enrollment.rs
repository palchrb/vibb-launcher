//! Enrollment codes (design 22 §3.1, §9 "Server, enrollment").

use std::net::SocketAddr;
use std::time::Duration;

use axum::body::Body;
use axum::http::{Method, Request, StatusCode, header};
use http_body_util::BodyExt;
use serde_json::json;

use super::{LOOPBACK_PEER, TestApp};
use crate::enrollment::{self, Kind, TYPED_PAUSE_FAILURES};

fn peer(text: &str) -> SocketAddr {
    SocketAddr::new(text.parse().unwrap(), 50000)
}

fn enroll_request(code: &str) -> Request<Body> {
    Request::builder()
        .method(Method::POST)
        .uri("/api/devices/enroll")
        .header(header::CONTENT_TYPE, "application/json")
        .body(Body::from(json!({ "enrollment_code": code }).to_string()))
        .unwrap()
}

async fn enroll_from(app: &TestApp, client: &str, code: &str) -> super::TestResponse {
    app.send_via(&app.device_router(), peer(client), enroll_request(code))
        .await
}

#[tokio::test]
async fn a_qr_code_works_once_and_is_never_stored_in_plain() {
    let app = TestApp::new().await;
    let (id, code) = app.create_device("phone").await;
    assert_eq!(code.len(), 26);
    let stored: (Option<String>, Option<String>, Option<String>) = sqlx::query_as(
        "SELECT enrollment_code, enrollment_code_hash, enrollment_code_kind FROM devices \
         WHERE id = ?",
    )
    .bind(id)
    .fetch_one(&app.db)
    .await
    .unwrap();
    assert_eq!(stored.0, None, "no plaintext column value");
    assert_eq!(
        stored.1.as_deref(),
        Some(enrollment::code_hash(&code).as_str())
    );
    assert_eq!(stored.2.as_deref(), Some("qr"));
    let dump: Vec<String> = sqlx::query_scalar("SELECT quote(devices.*) FROM devices")
        .fetch_all(&app.db)
        .await
        .unwrap_or_default();
    assert!(!dump.iter().any(|row| row.contains(&code)));

    // Lower case with a dash in it is the same code.
    let typed_in = format!("{}-{}", &code[..13], &code[13..]).to_lowercase();
    let res = enroll_from(&app, "203.0.113.7", &typed_in).await;
    assert_eq!(res.status, StatusCode::OK, "{}", res.text());
    assert_eq!(res.json()["device_id"], id);
    let res = enroll_from(&app, "203.0.113.7", &code).await;
    assert_eq!(res.status, StatusCode::UNAUTHORIZED, "once");

    let event: (String, String) = sqlx::query_as(
        "SELECT ip_address, detail FROM security_events WHERE event_type = 'device_enrolled'",
    )
    .fetch_one(&app.db)
    .await
    .unwrap();
    assert_eq!(event.0, "203.0.113.7");
    assert!(event.1.contains("phone"), "{}", event.1);
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn two_phones_racing_for_one_code_get_one_token() {
    let app = TestApp::new().await;
    let (id, code) = app.create_device("phone").await;
    let router = app.device_router();
    let mut racing = tokio::task::JoinSet::new();
    for n in 0..8 {
        let router = router.clone();
        let mut request = enroll_request(&code);
        request
            .extensions_mut()
            .insert(axum::extract::ConnectInfo(peer(&format!(
                "203.0.113.{}",
                n + 1
            ))));
        racing.spawn(async move {
            let response = tower::ServiceExt::oneshot(router, request).await.unwrap();
            super::read_response(response).await
        });
    }
    let results = racing.join_all().await;
    let winners: Vec<_> = results
        .iter()
        .filter(|res| res.status == StatusCode::OK)
        .collect();
    assert_eq!(winners.len(), 1, "exactly one phone enrolls");
    // The winner's token is the one that works.
    let token = winners[0].json()["device_token"]
        .as_str()
        .unwrap()
        .to_string();
    let res = app
        .request(Method::GET, "/api/devices/policy", Some(&token), None)
        .await;
    assert_eq!(res.status, StatusCode::OK);
    let enrolled: Option<String> =
        sqlx::query_scalar("SELECT token_hash FROM devices WHERE id = ?")
            .bind(id)
            .fetch_one(&app.db)
            .await
            .unwrap();
    assert_eq!(
        enrolled.as_deref(),
        Some(crate::security::hash_token(&token).as_str())
    );
}

#[tokio::test]
async fn an_expired_code_is_refused() {
    let app = TestApp::new().await;
    let (id, code) = app.create_device("phone").await;
    sqlx::query(
        "UPDATE devices SET enrollment_code_expires_at = datetime('now', '-1 minute') WHERE id = ?",
    )
    .bind(id)
    .execute(&app.db)
    .await
    .unwrap();
    let res = enroll_from(&app, "203.0.113.7", &code).await;
    assert_eq!(res.status, StatusCode::UNAUTHORIZED);
    // A new code replaces it, and a typed one expires after 15 minutes.
    let typed = app.new_code(id, Kind::Typed).await;
    let minutes: i64 = sqlx::query_scalar(
        "SELECT CAST(ROUND((julianday(enrollment_code_expires_at) - julianday('now')) * 1440) \
         AS INTEGER) FROM devices WHERE id = ?",
    )
    .bind(id)
    .fetch_one(&app.db)
    .await
    .unwrap();
    assert_eq!(minutes, 15);
    let res = enroll_from(&app, "203.0.113.7", &enrollment::display(&typed)).await;
    assert_eq!(res.status, StatusCode::OK);
}

#[tokio::test]
async fn typed_guesses_without_a_live_typed_code_count_for_nothing() {
    let app = TestApp::new().await;
    let (id, _qr) = app.create_device("phone").await;
    for _ in 0..40 {
        let res = enroll_from(&app, "203.0.113.7", "ABCD-EFGH").await;
        assert_eq!(res.status, StatusCode::UNAUTHORIZED);
    }
    // Neither the client nor typed codes were penalised: a typed code made now works at once,
    // from the same address.
    let typed = app.new_code(id, Kind::Typed).await;
    let res = enroll_from(&app, "203.0.113.7", &typed).await;
    assert_eq!(res.status, StatusCode::OK, "{}", res.text());
    assert_eq!(
        sqlx::query_scalar::<_, i64>(
            "SELECT COUNT(*) FROM security_events WHERE event_type = 'enroll_typed_paused'"
        )
        .fetch_one(&app.db)
        .await
        .unwrap(),
        0
    );
}

#[tokio::test]
async fn twenty_typed_failures_pause_typed_codes_but_never_qr_codes() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (typed_device, _) = app.create_device("typed").await;
    let typed = app.new_code(typed_device, Kind::Typed).await;
    // Spread over many addresses, so no client's own bucket fills.
    for n in 0..TYPED_PAUSE_FAILURES {
        let res = enroll_from(&app, &format!("198.51.100.{}", n + 1), "WXYZ-2345").await;
        assert_eq!(res.status, StatusCode::UNAUTHORIZED, "failure {n}");
    }
    // Paused: even the right typed code gets 429 now.
    let res = enroll_from(&app, "203.0.113.50", &typed).await;
    assert_eq!(res.status, StatusCode::TOO_MANY_REQUESTS);
    assert!(res.headers.contains_key(header::RETRY_AFTER));
    let paused: (String, String) = sqlx::query_as(
        "SELECT ip_address, detail FROM security_events WHERE event_type = 'enroll_typed_paused'",
    )
    .fetch_one(&app.db)
    .await
    .unwrap();
    assert!(
        paused.1.contains("setup QR codes still work"),
        "{}",
        paused.1
    );

    // The device page says so and offers the QR.
    let page = app
        .get_page(&format!("/devices/{typed_device}"), &cookie)
        .await
        .text();
    assert!(page.contains("Typed codes are paused until"), "{page}");
    assert!(page.contains("/provision"));

    // A flood of QR-shaped junk first, then a real QR code still enrolls.
    let (qr_device, qr) = app.create_device("qr").await;
    let junk: Vec<String> = (0..200).map(|_| enrollment::generate(Kind::Qr)).collect();
    for code in &junk {
        let res = enroll_from(&app, "203.0.113.66", code).await;
        assert_eq!(res.status, StatusCode::UNAUTHORIZED);
    }
    let res = enroll_from(&app, "203.0.113.66", &qr).await;
    assert_eq!(res.status, StatusCode::OK, "{}", res.text());
    assert_eq!(res.json()["device_id"], qr_device);

    // enroll_failed is written once a minute per client, with a count.
    app.state.audit.flush(&app.db).await;
    let rows: Vec<(String, String)> = sqlx::query_as(
        "SELECT ip_address, detail FROM security_events WHERE event_type = 'enroll_failed' \
         AND ip_address = '203.0.113.66'",
    )
    .fetch_all(&app.db)
    .await
    .unwrap();
    assert_eq!(rows.len(), 1, "{rows:?}");
    assert!(rows[0].1.contains("200 times in a minute"), "{}", rows[0].1);
}

#[tokio::test]
async fn typed_failures_fill_the_clients_failure_bucket() {
    let app = TestApp::new().await;
    let (id, _) = app.create_device("phone").await;
    // Typed failures and bad device tokens fill the same bucket (5 typed ones stay under the
    // server-wide pause of 20).
    let typed = app.new_code(id, Kind::Typed).await;
    for _ in 0..(crate::limits::IP_FAILURES - 5) {
        let request = Request::builder()
            .uri("/api/devices/policy")
            .header(header::AUTHORIZATION, "Bearer guess")
            .body(Body::empty())
            .unwrap();
        app.send_via(&app.device_router(), peer("203.0.113.7"), request)
            .await;
    }
    for _ in 0..5 {
        let res = enroll_from(&app, "203.0.113.7", "WXYZ-2345").await;
        assert_eq!(res.status, StatusCode::UNAUTHORIZED);
    }
    // The bucket is full: typed codes from this client are refused, even the right one ...
    let res = enroll_from(&app, "203.0.113.7", &typed).await;
    assert_eq!(res.status, StatusCode::TOO_MANY_REQUESTS);
    // ... but not from anywhere else.
    let res = enroll_from(&app, "203.0.113.8", &typed).await;
    assert_eq!(res.status, StatusCode::OK);
}

#[tokio::test]
async fn new_code_is_shown_once_and_replaces_the_last() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let res = app
        .request_form(
            Method::POST,
            "/devices/new",
            Some(&cookie),
            &[("name", "Kid")],
        )
        .await;
    let location = res.location().unwrap().to_string();
    let id: i64 = location.trim_start_matches("/devices/").parse().unwrap();
    let kind: Option<String> =
        sqlx::query_scalar("SELECT enrollment_code_kind FROM devices WHERE id = ?")
            .bind(id)
            .fetch_one(&app.db)
            .await
            .unwrap();
    assert_eq!(kind, None, "no code until one is asked for");
    let page = app.get_page(&location, &cookie).await.text();
    assert!(page.contains("Not enrolled yet"));
    assert!(page.contains("regenerate-code"));

    let first = app
        .request_form(
            Method::POST,
            &format!("/devices/{id}/regenerate-code"),
            Some(&cookie),
            &[],
        )
        .await;
    assert_eq!(first.status, StatusCode::OK);
    assert_eq!(
        first.headers.get(header::CACHE_CONTROL).unwrap(),
        "no-store"
    );
    let code_of = |html: &str| {
        let start = html.find("class=\"enrollment-code\">").unwrap() + 24;
        html[start..start + 9].to_string()
    };
    let first_code = code_of(&first.text());
    assert_eq!(first_code.len(), 9, "ABCD-EFGH");
    // The device page says a code is live, but never shows it.
    let page = app.get_page(&location, &cookie).await.text();
    assert!(page.contains("A typed code is valid until"), "{page}");
    assert!(!page.contains(&first_code));

    let second = app
        .request_form(
            Method::POST,
            &format!("/devices/{id}/regenerate-code"),
            Some(&cookie),
            &[],
        )
        .await;
    let second_code = code_of(&second.text());
    assert_ne!(first_code, second_code);
    let res = enroll_from(&app, "203.0.113.7", &first_code).await;
    assert_eq!(res.status, StatusCode::UNAUTHORIZED, "replaced");
    let res = enroll_from(&app, "203.0.113.7", &second_code).await;
    assert_eq!(res.status, StatusCode::OK);
}

#[tokio::test]
async fn revoking_access_retires_the_token_and_closes_the_stream() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("phone").await;
    let stream = app
        .call_via(
            &app.router,
            LOOPBACK_PEER,
            Request::builder()
                .uri("/api/devices/commands/stream")
                .header(header::AUTHORIZATION, format!("Bearer {token}"))
                .body(Body::empty())
                .unwrap(),
        )
        .await;
    assert_eq!(stream.status(), StatusCode::OK);

    // Without the confirm box nothing happens.
    let res = app
        .request_form(
            Method::POST,
            &format!("/devices/{id}/revoke"),
            Some(&cookie),
            &[],
        )
        .await;
    assert_eq!(
        res.location(),
        Some(format!("/devices/{id}?notice=revoke_unconfirmed#access").as_str())
    );
    let res = app
        .request(Method::GET, "/api/devices/policy", Some(&token), None)
        .await;
    assert_eq!(res.status, StatusCode::OK);

    let res = app
        .request_form(
            Method::POST,
            &format!("/devices/{id}/revoke"),
            Some(&cookie),
            &[("confirm", "yes")],
        )
        .await;
    assert_eq!(
        res.location(),
        Some(format!("/devices/{id}?notice=access_revoked#access").as_str())
    );
    assert!(
        tokio::time::timeout(Duration::from_secs(5), stream.into_body().collect())
            .await
            .is_ok(),
        "revoking didn't end the stream"
    );
    let res = app
        .request(Method::GET, "/api/devices/policy", Some(&token), None)
        .await;
    assert_eq!(res.status, StatusCode::UNAUTHORIZED);
    let page = app
        .get_page(&format!("/devices/{id}?notice=access_revoked"), &cookie)
        .await
        .text();
    assert!(page.contains("Access revoked: the phone"), "{page}");
    assert!(page.contains("access is revoked: it can"));
    let events: i64 = sqlx::query_scalar(
        "SELECT COUNT(*) FROM security_events WHERE event_type = 'access_revoked'",
    )
    .fetch_one(&app.db)
    .await
    .unwrap();
    assert_eq!(events, 1);

    // A new code enrolls it again.
    let code = app.new_code(id, Kind::Typed).await;
    let res = enroll_from(&app, "203.0.113.7", &code).await;
    assert_eq!(res.status, StatusCode::OK);
}

#[tokio::test]
async fn the_setup_qr_carries_a_qr_code() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, old) = app.create_device("phone").await;
    let page = app
        .get_page(&format!("/devices/{id}/provision"), &cookie)
        .await;
    assert_eq!(page.status, StatusCode::OK);
    let kind: Option<String> =
        sqlx::query_scalar("SELECT enrollment_code_kind FROM devices WHERE id = ?")
            .bind(id)
            .fetch_one(&app.db)
            .await
            .unwrap();
    assert_eq!(kind.as_deref(), Some("qr"));
    let res = enroll_from(&app, "203.0.113.7", &old).await;
    assert_eq!(
        res.status,
        StatusCode::UNAUTHORIZED,
        "the QR page replaced it"
    );
}
