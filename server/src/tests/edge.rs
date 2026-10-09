//! The device API's edge (design 22 §2 "Request limits", §3.1, §9 "Server, edge"): limits by
//! token and by failing client, the token index, the stream cap, the response headers.

use std::net::SocketAddr;
use std::time::Duration;

use axum::body::Body;
use axum::http::{Method, Request, StatusCode, header};
use http_body_util::BodyExt;

use super::{LOOPBACK_PEER, TestApp};
use crate::limits::{GENERAL_IN_FLIGHT, IP_FAILURES, LONG_IN_FLIGHT, RouteClass};

fn peer(text: &str) -> SocketAddr {
    SocketAddr::new(text.parse().unwrap(), 50000)
}

fn device_request(method: Method, path: &str, token: Option<&str>, body: &str) -> Request<Body> {
    let mut builder = Request::builder()
        .method(method)
        .uri(path)
        .header(header::CONTENT_TYPE, "application/json");
    if let Some(token) = token {
        builder = builder.header(header::AUTHORIZATION, format!("Bearer {token}"));
    }
    builder.body(Body::from(body.to_string())).unwrap()
}

#[tokio::test]
async fn a_valid_token_never_touches_the_failure_bucket() {
    let app = TestApp::new().await;
    let router = app.device_router();
    let (_, token) = app.enrolled_device("phone").await;
    let attacker = peer("203.0.113.7");
    for n in 1..=IP_FAILURES {
        let res = app
            .send_via(
                &router,
                attacker,
                device_request(Method::GET, "/api/devices/policy", Some("guess"), ""),
            )
            .await;
        assert_eq!(res.status, StatusCode::UNAUTHORIZED, "failure {n}");
    }
    // The bucket is full: failures now get 429 with Retry-After ...
    let res = app
        .send_via(
            &router,
            attacker,
            device_request(Method::GET, "/api/devices/policy", None, ""),
        )
        .await;
    assert_eq!(res.status, StatusCode::TOO_MANY_REQUESTS);
    assert_eq!(res.headers.get(header::RETRY_AFTER).unwrap(), "600");
    // ... but the phone behind the same address (a CGNAT neighbour, the Docker gateway) still
    // gets its policy.
    let res = app
        .send_via(
            &router,
            attacker,
            device_request(Method::GET, "/api/devices/policy", Some(&token), ""),
        )
        .await;
    assert_eq!(res.status, StatusCode::OK, "{}", res.text());
    // An IPv6 client is counted by its /64.
    for n in 0..IP_FAILURES {
        let client = peer(&format!("2001:db8:1:2::{:x}", n + 1));
        app.send_via(
            &router,
            client,
            device_request(Method::GET, "/api/devices/policy", Some("guess"), ""),
        )
        .await;
    }
    let res = app
        .send_via(
            &router,
            peer("2001:db8:1:2:ffff::1"),
            device_request(Method::GET, "/api/devices/policy", Some("guess"), ""),
        )
        .await;
    assert_eq!(res.status, StatusCode::TOO_MANY_REQUESTS);

    // The audit trail has one row per client per minute, not one per request.
    app.state.audit.flush(&app.db).await;
    let rows: Vec<(String, String)> = sqlx::query_as(
        "SELECT ip_address, detail FROM security_events WHERE event_type = 'device_auth_failed' \
         ORDER BY id",
    )
    .fetch_all(&app.db)
    .await
    .unwrap();
    assert_eq!(rows.len(), 2, "{rows:?}");
    assert_eq!(rows[0].0, "203.0.113.7");
    assert!(
        rows[0]
            .1
            .contains(&format!("({IP_FAILURES} times in a minute)")),
        "{}",
        rows[0].1
    );
}

#[tokio::test]
async fn policy_and_command_result_get_in_while_every_slot_is_busy() {
    let app = TestApp::new().await;
    let (id, token) = app.enrolled_device("phone").await;
    // Every general and download slot taken (long downloads, a stream, music files).
    let _general: Vec<_> = (0..GENERAL_IN_FLIGHT)
        .map(|_| app.state.limits.enter(id, RouteClass::General).unwrap())
        .collect();
    let _long: Vec<_> = (0..LONG_IN_FLIGHT)
        .map(|_| app.state.limits.enter(id, RouteClass::Long).unwrap())
        .collect();
    let res = app
        .request(Method::GET, "/api/devices/policy", Some(&token), None)
        .await;
    assert_eq!(res.status, StatusCode::OK);
    let res = app
        .request(
            Method::POST,
            "/api/devices/command-result",
            Some(&token),
            Some(serde_json::json!({"command_id": 1, "success": true, "message": null})),
        )
        .await;
    assert_ne!(res.status, StatusCode::TOO_MANY_REQUESTS);
    // Everything else waits.
    let res = app
        .request(Method::GET, "/api/devices/apps", Some(&token), None)
        .await;
    assert_eq!(res.status, StatusCode::TOO_MANY_REQUESTS);
    assert!(res.headers.contains_key(header::RETRY_AFTER));
    let res = app
        .request(
            Method::GET,
            "/api/devices/commands/stream",
            Some(&token),
            None,
        )
        .await;
    assert_eq!(res.status, StatusCode::TOO_MANY_REQUESTS);
}

/// A stream's slot lasts as long as the stream, not just its handler.
#[tokio::test]
async fn a_stream_holds_its_slot_until_it_ends() {
    let app = TestApp::new().await;
    let (id, token) = app.enrolled_device("phone").await;
    let mut open = Vec::new();
    for _ in 0..LONG_IN_FLIGHT {
        let response = app
            .call_via(
                &app.router,
                LOOPBACK_PEER,
                device_request(
                    Method::GET,
                    "/api/devices/commands/stream",
                    Some(&token),
                    "",
                ),
            )
            .await;
        assert_eq!(response.status(), StatusCode::OK);
        open.push(response.into_body());
    }
    assert!(app.state.limits.enter(id, RouteClass::Long).is_err());
    drop(open);
    assert!(app.state.limits.enter(id, RouteClass::Long).is_ok());
}

#[tokio::test]
async fn a_third_stream_closes_the_first_and_delete_closes_the_rest() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("phone").await;
    let mut bodies = Vec::new();
    for _ in 0..3 {
        let response = app
            .call_via(
                &app.router,
                LOOPBACK_PEER,
                device_request(
                    Method::GET,
                    "/api/devices/commands/stream",
                    Some(&token),
                    "",
                ),
            )
            .await;
        assert_eq!(response.status(), StatusCode::OK);
        bodies.push(response.into_body());
    }
    let first = bodies.remove(0);
    assert!(
        tokio::time::timeout(Duration::from_secs(5), first.collect())
            .await
            .is_ok(),
        "the third stream didn't close the first"
    );
    let mut second = bodies.remove(0);
    assert!(
        tokio::time::timeout(Duration::from_millis(200), second.frame())
            .await
            .is_err(),
        "the second stream should stay open"
    );

    let res = app
        .request_form(
            Method::POST,
            &format!("/devices/{id}/delete"),
            Some(&cookie),
            &[],
        )
        .await;
    assert!(res.status.is_redirection(), "{}", res.status);
    for body in std::iter::once(second).chain(bodies) {
        assert!(
            tokio::time::timeout(Duration::from_secs(5), body.collect())
                .await
                .is_ok(),
            "deleting the device didn't end its stream"
        );
    }
    // Its token is gone from the index at once.
    let res = app
        .request(Method::GET, "/api/devices/policy", Some(&token), None)
        .await;
    assert_eq!(res.status, StatusCode::UNAUTHORIZED);
}

#[tokio::test]
async fn re_enrolling_a_phone_retires_its_old_token() {
    let app = TestApp::new().await;
    let (id, old_token) = app.enrolled_device("phone").await;
    let code = app.new_code(id, crate::enrollment::Kind::Typed).await;
    let res = app
        .request(
            Method::POST,
            "/api/devices/enroll",
            None,
            Some(serde_json::json!({"enrollment_code": code})),
        )
        .await;
    assert_eq!(res.status, StatusCode::OK);
    let new_token = res.json()["device_token"].as_str().unwrap().to_string();
    let res = app
        .request(Method::GET, "/api/devices/policy", Some(&old_token), None)
        .await;
    assert_eq!(res.status, StatusCode::UNAUTHORIZED);
    let res = app
        .request(Method::GET, "/api/devices/policy", Some(&new_token), None)
        .await;
    assert_eq!(res.status, StatusCode::OK);
}

#[tokio::test]
async fn bodies_are_capped_and_responses_are_private() {
    let app = TestApp::new().await;
    let (_, token) = app.enrolled_device("phone").await;
    let big = format!(
        "{{\"crashes\": [], \"pad\": \"{}\"}}",
        "x".repeat(600 * 1024)
    );
    for router in [app.router.clone(), app.device_router()] {
        let res = app
            .send_via(
                &router,
                LOOPBACK_PEER,
                device_request(Method::POST, "/api/devices/crashes", Some(&token), &big),
            )
            .await;
        assert_eq!(res.status, StatusCode::PAYLOAD_TOO_LARGE);
        let res = app
            .send_via(
                &router,
                LOOPBACK_PEER,
                device_request(
                    Method::POST,
                    "/api/devices/enroll",
                    None,
                    &format!("{{\"enrollment_code\": \"{}\"}}", "A".repeat(5000)),
                ),
            )
            .await;
        assert_eq!(res.status, StatusCode::PAYLOAD_TOO_LARGE, "enroll: 4 KiB");

        let res = app
            .send_via(
                &router,
                LOOPBACK_PEER,
                device_request(Method::GET, "/api/devices/policy", Some(&token), ""),
            )
            .await;
        assert_eq!(res.status, StatusCode::OK);
        assert_eq!(res.headers.get(header::CACHE_CONTROL).unwrap(), "no-store");
        assert_eq!(
            res.headers.get(header::X_CONTENT_TYPE_OPTIONS).unwrap(),
            "nosniff"
        );
        assert_eq!(
            res.headers.get(header::CONTENT_SECURITY_POLICY).unwrap(),
            "default-src 'none'; sandbox"
        );
        // A refused request gets them too.
        let res = app
            .send_via(
                &router,
                LOOPBACK_PEER,
                device_request(Method::GET, "/api/devices/apps", None, ""),
            )
            .await;
        assert_eq!(res.status, StatusCode::UNAUTHORIZED);
        assert_eq!(res.headers.get(header::CACHE_CONTROL).unwrap(), "no-store");
        assert_eq!(
            res.headers.get(header::CONTENT_SECURITY_POLICY).unwrap(),
            "default-src 'none'; sandbox"
        );
    }
}

#[tokio::test]
async fn the_device_page_shows_the_last_access() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("phone").await;
    let page = app.get_page(&format!("/devices/{id}"), &cookie).await;
    assert!(
        page.text()
            .contains("Last access: none since this server started."),
        "{}",
        page.text()
    );
    // Through the local proxy from the internet, then over the tailnet.
    for client in ["203.0.113.7", "198.51.100.2", "100.101.102.103"] {
        let mut request = device_request(Method::GET, "/api/devices/policy", Some(&token), "");
        request
            .headers_mut()
            .insert("x-forwarded-for", client.parse().unwrap());
        let res = app
            .send_via(&app.device_router(), LOOPBACK_PEER, request)
            .await;
        assert_eq!(res.status, StatusCode::OK);
    }
    let page = app.get_page(&format!("/devices/{id}"), &cookie).await;
    let text = page.text();
    assert!(text.contains("UTC over the tailnet."), "{text}");
    assert!(
        text.contains("newest first: 198.51.100.2, 203.0.113.7."),
        "{text}"
    );
}
