//! The admin and phone listeners (design 22 §2, §9 "routers and the gate").

use std::net::SocketAddr;
use std::time::Duration;

use axum::body::Body;
use axum::http::{Method, Request, StatusCode, header};
use http_body_util::BodyExt;

use super::{LOOPBACK_PEER, TestApp};

/// Every device route lives in `device_routes.rs` (design 22 S0): a route added to `main.rs`
/// would reach the admin listener only, and the phone listener's tests (built from the same
/// `device_routes()`) would never see it.
#[test]
fn main_rs_has_no_device_route_literal() {
    let main = include_str!("../main.rs");
    assert!(
        !main.contains("\"/api/devices"),
        "a device route in main.rs - add it to device_routes.rs"
    );
}

/// `(method, path)` of every route in `device_routes.rs`, read from its source, so a route added
/// later is covered without editing this test. Path parameters become "1".
fn device_route_list() -> Vec<(Method, String)> {
    let source = include_str!("../device_routes.rs");
    let mut routes = Vec::new();
    let mut rest = source;
    while let Some(start) = rest.find(".route(") {
        rest = &rest[start + ".route(".len()..];
        let quote = rest.find('"').expect("route without a path");
        let path_and_more = &rest[quote + 1..];
        let end = path_and_more.find('"').unwrap();
        let path = &path_and_more[..end];
        let after = &path_and_more[end..];
        let method = if after.find("get(").unwrap_or(usize::MAX)
            < after.find("post(").unwrap_or(usize::MAX)
        {
            Method::GET
        } else {
            Method::POST
        };
        let mut concrete = String::new();
        let mut in_param = false;
        for c in path.chars() {
            match c {
                '{' => in_param = true,
                '}' => {
                    in_param = false;
                    concrete.push('1');
                }
                _ if !in_param => concrete.push(c),
                _ => {}
            }
        }
        routes.push((method, concrete));
    }
    assert!(
        routes.len() >= 18,
        "found only {} device routes",
        routes.len()
    );
    routes
}

fn peer(text: &str) -> SocketAddr {
    SocketAddr::new(text.parse().unwrap(), 50000)
}

fn get(path: &str) -> Request<Body> {
    Request::builder().uri(path).body(Body::empty()).unwrap()
}

#[tokio::test]
async fn the_device_router_serves_nothing_but_the_device_api() {
    let app = TestApp::new().await;
    let router = app.device_router();
    let paths = [
        "/",
        "/login",
        "/devices",
        "/devices/1",
        "/devices/1/command/wipe",
        "/settings",
        "/backups/x/download",
        "/update/trigger",
        "/security",
        "/music",
        "/apps",
        "/static/style.css",
        "/sw.js",
        "/auth/verify-2fa",
    ];
    for path in paths {
        for method in [Method::GET, Method::POST] {
            let request = Request::builder()
                .method(method.clone())
                .uri(path)
                .header(header::CONTENT_TYPE, "application/x-www-form-urlencoded")
                .body(Body::from("username=admin&password=x"))
                .unwrap();
            let res = app.send_via(&router, peer("203.0.113.7"), request).await;
            assert_eq!(res.status, StatusCode::NOT_FOUND, "{method} {path}");
            assert!(res.body.is_empty(), "{method} {path}: {}", res.text());
            assert!(
                !res.headers.contains_key(header::SET_COOKIE),
                "{method} {path} set a cookie"
            );
        }
    }

    // Every device route answers: 401 without a token (the auth layer ran, so the route exists),
    // and enroll a 4xx for an empty body.
    for (method, path) in device_route_list() {
        let request = Request::builder()
            .method(method.clone())
            .uri(&path)
            .header(header::CONTENT_TYPE, "application/json")
            .body(Body::from("{}"))
            .unwrap();
        let res = app.send_via(&router, peer("203.0.113.7"), request).await;
        if path == "/api/devices/enroll" {
            assert!(
                res.status.is_client_error() && res.status != StatusCode::NOT_FOUND,
                "{method} {path}: {}",
                res.status
            );
        } else {
            assert_eq!(res.status, StatusCode::UNAUTHORIZED, "{method} {path}");
        }
    }

    // ... and with a token the phone gets its policy, from any client.
    let (_, token) = app.enrolled_device("phone").await;
    let request = Request::builder()
        .uri("/api/devices/policy")
        .header(header::AUTHORIZATION, format!("Bearer {token}"))
        .body(Body::empty())
        .unwrap();
    let res = app.send_via(&router, peer("203.0.113.7"), request).await;
    assert_eq!(res.status, StatusCode::OK, "{}", res.text());

    let res = app
        .send_via(&router, peer("203.0.113.7"), get("/healthz"))
        .await;
    assert_eq!(res.status, StatusCode::OK);
    assert_eq!(res.text(), "ok");
}

#[tokio::test]
async fn admin_device_api_off_leaves_the_device_api_to_the_phone_listener() {
    let app = TestApp::with_env(&[("ADMIN_DEVICE_API", "off")]).await;
    let (_, token) = {
        // Enroll through the phone listener: the admin listener no longer has the route.
        let (id, code) = app.create_device("phone").await;
        let request = Request::builder()
            .method(Method::POST)
            .uri("/api/devices/enroll")
            .header(header::CONTENT_TYPE, "application/json")
            .body(Body::from(
                serde_json::json!({ "enrollment_code": code }).to_string(),
            ))
            .unwrap();
        let res = app
            .send_via(&app.device_router(), LOOPBACK_PEER, request)
            .await;
        assert_eq!(res.status, StatusCode::OK, "{}", res.text());
        (id, res.json()["device_token"].as_str().unwrap().to_string())
    };
    for (method, path) in device_route_list() {
        let request = Request::builder()
            .method(method.clone())
            .uri(&path)
            .header(header::AUTHORIZATION, format!("Bearer {token}"))
            .body(Body::empty())
            .unwrap();
        let res = app.send_via(&app.router, LOOPBACK_PEER, request).await;
        assert_eq!(res.status, StatusCode::NOT_FOUND, "{method} {path}");
    }
    // The admin pages are still there.
    let res = app
        .send_via(&app.router, LOOPBACK_PEER, get("/login"))
        .await;
    assert_eq!(res.status, StatusCode::OK);
}

async fn refused_rows(app: &TestApp) -> Vec<(String, Option<String>)> {
    sqlx::query_as(
        "SELECT ip_address, detail FROM security_events WHERE event_type = 'admin_refused' \
         ORDER BY id",
    )
    .fetch_all(&app.db)
    .await
    .unwrap()
}

#[tokio::test]
async fn the_admin_gate_refuses_public_clients_and_logs_it_once_a_minute() {
    let app = TestApp::new().await;
    let through_proxy = |client: &str, path: &str| {
        Request::builder()
            .uri(path)
            .header("x-forwarded-for", client)
            .body(Body::empty())
            .unwrap()
    };
    for _ in 0..5 {
        let res = app
            .send_via(
                &app.router,
                LOOPBACK_PEER,
                through_proxy("203.0.113.7", "/login"),
            )
            .await;
        assert_eq!(res.status, StatusCode::FORBIDDEN);
        assert!(!res.headers.contains_key(header::SET_COOKIE));
    }
    // A LAN client on a non-loopback bind is refused too, also for the device API.
    let res = app
        .send_via(
            &app.router,
            peer("192.168.1.20"),
            get("/api/devices/policy"),
        )
        .await;
    assert_eq!(res.status, StatusCode::FORBIDDEN);
    // An unknown path is no different (no probing which pages exist).
    let res = app
        .send_via(
            &app.router,
            LOOPBACK_PEER,
            through_proxy("203.0.113.7", "/no-such-page"),
        )
        .await;
    assert_eq!(res.status, StatusCode::FORBIDDEN);

    app.state.audit.flush(&app.db).await;
    let rows = refused_rows(&app).await;
    assert_eq!(rows.len(), 2, "one row per client per minute: {rows:?}");
    assert_eq!(rows[0].0, "203.0.113.7");
    let detail = rows[0].1.as_deref().unwrap();
    assert!(detail.contains("6 times in a minute"), "{detail}");
    assert_eq!(rows[1].0, "192.168.1.20");

    // Tailnet clients (behind the local proxy, or directly) and loopback pass.
    for (peer_addr, forwarded) in [
        (LOOPBACK_PEER, Some("100.101.102.103")),
        (LOOPBACK_PEER, Some("fd7a:115c:a1e0::5")),
        (peer("100.101.102.103"), None),
        (LOOPBACK_PEER, None),
    ] {
        let mut request = Request::builder().uri("/login");
        if let Some(client) = forwarded {
            request = request.header("x-forwarded-for", client);
        }
        let res = app
            .send_via(&app.router, peer_addr, request.body(Body::empty()).unwrap())
            .await;
        assert_eq!(res.status, StatusCode::OK, "{peer_addr} {forwarded:?}");
    }
}

#[tokio::test]
async fn admin_public_passes_everyone_and_funnel_is_refused() {
    let public = TestApp::with_env(&[("ADMIN_PUBLIC", "on")]).await;
    let request = Request::builder()
        .uri("/login")
        .header("x-forwarded-for", "203.0.113.7")
        .body(Body::empty())
        .unwrap();
    let res = public
        .send_via(&public.router, LOOPBACK_PEER, request)
        .await;
    assert_eq!(res.status, StatusCode::OK);

    let app = TestApp::new().await;
    let request = Request::builder()
        .uri("/login")
        .header("x-forwarded-for", "100.101.102.103")
        .header("tailscale-funnel-request", "?1")
        .body(Body::empty())
        .unwrap();
    let res = app.send_via(&app.router, LOOPBACK_PEER, request).await;
    assert_eq!(res.status, StatusCode::FORBIDDEN);
}

#[tokio::test]
async fn tailscale_user_login_from_a_public_client_is_ignored() {
    let app = TestApp::with_env(&[
        ("ADMIN_PUBLIC", "on"),
        ("ADMIN_TAILSCALE_USERS", "parent@example.com"),
    ])
    .await;
    let with = |client: &str| {
        Request::builder()
            .uri("/login")
            .header("x-forwarded-for", client)
            .header("tailscale-user-login", "parent@example.com")
            .body(Body::empty())
            .unwrap()
    };
    let res = app
        .send_via(&app.router, LOOPBACK_PEER, with("203.0.113.7"))
        .await;
    assert_eq!(res.status, StatusCode::FORBIDDEN);
    let res = app
        .send_via(&app.router, LOOPBACK_PEER, with("100.101.102.103"))
        .await;
    assert_eq!(res.status, StatusCode::OK);
}

#[tokio::test]
async fn healthz_passes_every_guard() {
    let app = TestApp::with_env(&[
        ("ADMIN_TAILSCALE_USERS", "parent@example.com"),
        ("ADMIN_DEVICE_API", "off"),
    ])
    .await;
    let request = Request::builder()
        .uri("/healthz")
        .header("x-forwarded-for", "203.0.113.7")
        .header("tailscale-funnel-request", "?1")
        .body(Body::empty())
        .unwrap();
    let res = app
        .send_via(&app.router, peer("198.51.100.4"), request)
        .await;
    assert_eq!(res.status, StatusCode::OK);
    assert_eq!(res.text(), "ok");
    assert!(!res.headers.contains_key(header::SET_COOKIE));
    app.state.audit.flush(&app.db).await;
    assert!(refused_rows(&app).await.is_empty());
}

/// A graceful stop doesn't wait for phones: `close_all` ends every command stream (design 22
/// §2), on both listeners.
#[tokio::test]
async fn shutdown_ends_the_command_streams() {
    let app = TestApp::new().await;
    let (_, token) = app.enrolled_device("phone").await;
    let mut bodies = Vec::new();
    for router in [app.router.clone(), app.device_router()] {
        let request = Request::builder()
            .uri("/api/devices/commands/stream")
            .header(header::AUTHORIZATION, format!("Bearer {token}"))
            .body(Body::empty())
            .unwrap();
        let response = app.call_via(&router, LOOPBACK_PEER, request).await;
        assert_eq!(response.status(), StatusCode::OK);
        bodies.push(response.into_body());
    }
    // Still open: nothing arrives within a moment.
    let mut first = bodies.remove(0);
    assert!(
        tokio::time::timeout(Duration::from_millis(200), first.frame())
            .await
            .is_err(),
        "the stream ended by itself"
    );
    app.state.command_streams.close_all();
    for body in std::iter::once(first).chain(bodies) {
        let collected = tokio::time::timeout(Duration::from_secs(5), body.collect()).await;
        assert!(collected.is_ok(), "the stream didn't end at shutdown");
    }
    assert_eq!(app.state.command_streams.state(1).map(|s| s.open), Some(0));
}
