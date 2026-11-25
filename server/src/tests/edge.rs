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
    let ips: Vec<String> = sqlx::query_scalar(
        "SELECT ip_address FROM security_events WHERE event_type = 'device_auth_failed' \
         ORDER BY id",
    )
    .fetch_all(&app.db)
    .await
    .unwrap();
    assert_eq!(ips.len(), 2, "{ips:?}");
    assert_eq!(ips[0], "203.0.113.7");
    let rows = super::security_words(&app, "device_auth_failed", crate::i18n::Lang::En).await;
    assert!(
        rows[0].contains(&format!("({IP_FAILURES} times in a minute)")),
        "{}",
        rows[0]
    );
    assert!(rows[0].starts_with("Invalid phone key"), "{}", rows[0]);
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
    // A wipe's receipt (security review L12) too.
    let res = app
        .request(
            Method::POST,
            "/api/devices/command-receipt",
            Some(&token),
            Some(serde_json::json!({"command_id": 1})),
        )
        .await;
    assert_eq!(res.status, StatusCode::NO_CONTENT);
    // Everything else waits.
    let res = app
        .request(Method::GET, "/api/devices/apps", Some(&token), None)
        .await;
    assert_eq!(res.status, StatusCode::TOO_MANY_REQUESTS);
    assert!(res.headers.contains_key(header::RETRY_AFTER));
    // The SSE stream isn't one of them (QA 22 Low 3): its own cap bounds it.
    let stream = app
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
    assert_eq!(stream.status(), StatusCode::OK);
}

/// A download's slot lasts as long as its body, not just its handler.
#[tokio::test]
async fn a_download_holds_its_slot_until_its_body_ends() {
    let limits = std::sync::Arc::new(crate::limits::Limits::default());
    let mut bodies = Vec::new();
    for _ in 0..LONG_IN_FLIGHT {
        let permit = limits.enter(1, RouteClass::Long).unwrap();
        let response = axum::response::Response::new(Body::from_stream(tokio_stream::pending::<
            Result<axum::body::Bytes, std::io::Error>,
        >()));
        bodies.push(crate::limits::hold_until_done(response, permit));
    }
    assert!(limits.enter(1, RouteClass::Long).is_err());
    drop(bodies);
    assert!(limits.enter(1, RouteClass::Long).is_ok());
}

/// QA 22 Low 3: a phone that changed networks twice while two downloads ran has two half-open
/// streams counted; its new stream is admitted and closes the oldest.
#[tokio::test]
async fn a_new_stream_gets_in_past_stale_ones_and_downloads() {
    let app = TestApp::new().await;
    let (id, token) = app.enrolled_device("phone").await;
    let open_stream = || {
        app.call_via(
            &app.router,
            LOOPBACK_PEER,
            device_request(
                Method::GET,
                "/api/devices/commands/stream",
                Some(&token),
                "",
            ),
        )
    };
    let stale_first = open_stream().await.into_body();
    let stale_second = open_stream().await.into_body();
    let _downloads: Vec<_> = (0..2)
        .map(|_| app.state.limits.enter(id, RouteClass::Long).unwrap())
        .collect();
    let fresh = open_stream().await;
    assert_eq!(fresh.status(), StatusCode::OK);
    assert!(
        tokio::time::timeout(Duration::from_secs(5), stale_first.collect())
            .await
            .is_ok(),
        "the oldest stream wasn't closed"
    );
    drop(stale_second);
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

async fn security_line_ids(db: &sqlx::SqlitePool) -> std::collections::BTreeSet<i64> {
    sqlx::query_scalar("SELECT id FROM security_events")
        .fetch_all(db)
        .await
        .unwrap()
        .into_iter()
        .collect()
}

/// Deleting a phone deletes its own security-log lines - those `record_phone_event` writes: a
/// phone line's kind, neither a username nor an address -; a parent's line about the phone, the
/// enrolment's (with its address), the server's own `device_key_conflict` line, the parent's line
/// about the delete and another phone's lines (`device 1` against `device 12`) stay.
#[tokio::test]
async fn delete_removes_the_phones_own_security_lines() {
    use crate::security::{Detail, PhoneLine, record_phone_event};
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, _token) = app.enrolled_device("phone").await;
    let (other, _) = app.enrolled_device("other").await;
    // A phone whose id starts with this one's digits: `device {id}` must not match `device {id}2`.
    let lookalike = id * 10 + 2;
    let newest = || async {
        sqlx::query_scalar::<_, i64>("SELECT MAX(id) FROM security_events")
            .fetch_one(&app.db)
            .await
            .unwrap()
    };
    let mut written = std::collections::BTreeMap::new();
    for (name, device) in [("own", id), ("other", other), ("lookalike", lookalike)] {
        let line = Detail::words(
            "security.detail.phone_lines_dropped",
            &[("device", &device.to_string()), ("max", "1")],
        );
        let mut conn = app.db.acquire().await.unwrap();
        assert!(
            record_phone_event(&mut conn, device, PhoneLine::SetupTickPhone, line)
                .await
                .unwrap()
        );
        drop(conn);
        written.insert(name, newest().await);
    }
    // By hand: `(name, event_type, username, ip_address, detail)`.
    let by_hand = [
        // The older spellings of a phone line, `device {id}:` and `device {id},`.
        (
            "own_colon",
            PhoneLine::RelockFindings.wire(),
            None,
            None,
            format!("device {id}: started"),
        ),
        (
            "own_comma",
            PhoneLine::AppInstalled.wire(),
            None,
            None,
            format!("device {id}, app"),
        ),
        // The server's own line about the phone: no phone line's kind.
        (
            "key_conflict",
            "device_key_conflict",
            None,
            None,
            format!("device {id}"),
        ),
        // A parent's line about the phone carries the parent's name.
        (
            "parent",
            "settings_saved",
            Some("admin"),
            None,
            format!("device {id}"),
        ),
        // A phone line's kind with an address: not one `record_phone_event` wrote.
        (
            "with_address",
            PhoneLine::AppInstalled.wire(),
            None,
            Some("192.0.2.7"),
            format!("device {id}"),
        ),
    ];
    for (name, kind, username, address, detail) in by_hand {
        sqlx::query(
            "INSERT INTO security_events (event_type, username, ip_address, detail) \
             VALUES (?, ?, ?, ?)",
        )
        .bind(kind)
        .bind(username)
        .bind(address)
        .bind(detail)
        .execute(&app.db)
        .await
        .unwrap();
        written.insert(name, newest().await);
    }
    let before = security_line_ids(&app.db).await;

    let res = app
        .request_form(
            Method::POST,
            &format!("/devices/{id}/delete"),
            Some(&cookie),
            &[],
        )
        .await;
    assert!(res.status.is_redirection(), "{}", res.status);

    let after = security_line_ids(&app.db).await;
    let gone: std::collections::BTreeSet<i64> = before.difference(&after).copied().collect();
    let own: std::collections::BTreeSet<i64> = ["own", "own_colon", "own_comma"]
        .iter()
        .map(|name| written[name])
        .collect();
    assert_eq!(gone, own);
    for name in [
        "other",
        "lookalike",
        "key_conflict",
        "parent",
        "with_address",
    ] {
        assert!(after.contains(&written[name]), "{name}");
    }
    // The enrolment's line (it carries the address it came from) stays; the parent's line about
    // the delete is written.
    let enrolled: i64 = sqlx::query_scalar(
        "SELECT COUNT(*) FROM security_events WHERE event_type = 'device_enrolled' AND detail = ?",
    )
    .bind(format!("device {id}"))
    .fetch_one(&app.db)
    .await
    .unwrap();
    assert_eq!(enrolled, 1);
    let deleted: i64 = sqlx::query_scalar(
        "SELECT COUNT(*) FROM security_events WHERE username IS NOT NULL AND detail LIKE '%/delete%'",
    )
    .fetch_one(&app.db)
    .await
    .unwrap();
    assert_eq!(deleted, 1);
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
            Some(crate::tests::enroll_body(&code)),
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
                    &format!("{{\"enrollment_code\": \"{}\"}}", "A".repeat(33 * 1024)),
                ),
            )
            .await;
        assert_eq!(
            res.status,
            StatusCode::PAYLOAD_TOO_LARGE,
            "enroll: 32 KiB (design 129)"
        );

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
async fn about_shows_the_last_access() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("phone").await;
    // About This Phone's Network row (design 31 step 3; the Access card's line until then).
    let network = |html: &str| {
        let row = &html[html.find("data-row=\"network\"").expect("Network row")..];
        row[..row.find("</details>").or(row.find("</div>")).unwrap()].to_string()
    };
    let page = app
        .get_page(&format!("/devices/{id}/about"), &cookie)
        .await
        .text();
    assert!(network(&page).contains("None since restart"), "{page}");
    // Through the local proxy from the internet, then over the tailnet.
    let reach = |client: &'static str| {
        let (app, token) = (&app, token.clone());
        async move {
            let mut request = device_request(Method::GET, "/api/devices/policy", Some(&token), "");
            request
                .headers_mut()
                .insert("x-forwarded-for", client.parse().unwrap());
            let res = app
                .send_via(&app.device_router(), LOOPBACK_PEER, request)
                .await;
            assert_eq!(res.status, StatusCode::OK);
        }
    };
    reach("203.0.113.7").await;
    reach("198.51.100.2").await;
    // "Outside the tailnet" is a quiet value (2026-11-18), the addresses behind the ⓘ.
    let text = app
        .get_page(&format!("/devices/{id}/about"), &cookie)
        .await
        .text();
    let row = network(&text);
    assert!(row.contains(">Outside the tailnet</span>"), "{text}");
    assert!(!row.contains("row-value-action"), "{text}");
    reach("100.101.102.103").await;
    let text = app
        .get_page(&format!("/devices/{id}/about"), &cookie)
        .await
        .text();
    let row = network(&text);
    assert!(row.contains(">Over the tailnet</span>"), "{text}");
    // The outside addresses from before, behind the row's ⓘ.
    assert!(
        row.contains("newest first: 198.51.100.2, 203.0.113.7."),
        "{text}"
    );
    // The contact made it Away (no stream).
    assert!(
        text.contains("<span class=\"state-away\">Away</span>"),
        "{text}"
    );
}

/// A request body that sends one byte and then nothing, forever.
fn stalled_body() -> Body {
    let stream = futures_stalled();
    Body::from_stream(stream)
}

fn futures_stalled()
-> impl tokio_stream::Stream<Item = Result<axum::body::Bytes, std::io::Error>> + Send + 'static {
    use tokio_stream::StreamExt;
    tokio_stream::once(Ok(axum::body::Bytes::from_static(b"{"))).chain(tokio_stream::pending())
}

/// QA 22 M2: slow enroll bodies from the internet hold only the guard for requests without a
/// valid token; a phone's own requests never wait behind them.
#[tokio::test]
async fn stalled_enroll_bodies_never_keep_a_phone_out() {
    let app = TestApp::new().await;
    let (_, token) = app.enrolled_device("phone").await;
    let router = app.device_router();
    let mut stalled = Vec::new();
    for n in 0..600u32 {
        let mut request = Request::builder()
            .method(Method::POST)
            .uri("/api/devices/enroll")
            .header(header::CONTENT_TYPE, "application/json")
            .body(stalled_body())
            .unwrap();
        request
            .extensions_mut()
            .insert(axum::extract::ConnectInfo(peer(&format!(
                "203.0.{}.{}",
                n / 250,
                n % 250 + 1
            ))));
        let router = router.clone();
        stalled.push(tokio::spawn(async move {
            tower::ServiceExt::oneshot(router, request).await
        }));
    }
    tokio::time::sleep(Duration::from_millis(300)).await;
    // The phone gets its policy at once ...
    let res = tokio::time::timeout(
        Duration::from_secs(2),
        app.send_via(
            &router,
            peer("198.51.100.7"),
            device_request(Method::GET, "/api/devices/policy", Some(&token), ""),
        ),
    )
    .await
    .expect("the phone waited");
    assert_eq!(res.status, StatusCode::OK);
    // ... while another request without a valid token is turned away.
    let res = app
        .send_via(
            &router,
            peer("198.51.100.8"),
            device_request(Method::GET, "/api/devices/policy", Some("guess"), ""),
        )
        .await;
    assert_eq!(res.status, StatusCode::SERVICE_UNAVAILABLE);
    for task in stalled {
        task.abort();
    }
}

/// An enrollment whose body doesn't arrive gets 408 after `enrollment::BODY_TIMEOUT`, freeing its
/// slot.
#[tokio::test]
async fn a_stalled_enroll_body_times_out() {
    let app = TestApp::new().await;
    let request = Request::builder()
        .method(Method::POST)
        .uri("/api/devices/enroll")
        .header(header::CONTENT_TYPE, "application/json")
        .body(stalled_body())
        .unwrap();
    let started = std::time::Instant::now();
    let res = app
        .send_via(&app.device_router(), peer("203.0.113.9"), request)
        .await;
    assert_eq!(res.status, StatusCode::REQUEST_TIMEOUT);
    assert!(started.elapsed() < Duration::from_secs(10));
}

/// QA 22 M3: a flood from many addresses writes at most `audit::ROWS_PER_MINUTE` rows of a kind a
/// minute, plus one summary row that counts the rest.
#[tokio::test]
async fn a_flood_from_many_addresses_has_a_row_budget() {
    let app = TestApp::new().await;
    for n in 0..10_000u32 {
        app.state
            .audit
            .record(
                &app.db,
                "device_auth_failed",
                &format!("2001:db8:{:x}:{:x}::/64", n / 0x10000, n % 0x10000),
                Some("2001:db8::1"),
                crate::security::Detail::words("security.detail.device_auth.invalid_token", &[]),
            )
            .await;
    }
    app.state.audit.flush(&app.db).await;
    let rows = super::security_words(&app, "device_auth_failed", crate::i18n::Lang::En).await;
    assert_eq!(rows.len() as u32, crate::audit::ROWS_PER_MINUTE + 1);
    let summary = rows.last().unwrap();
    assert!(
        summary.starts_with("9940 more in this minute from at least 1000 addresses"),
        "{summary}"
    );
    let nb = super::security_words(&app, "device_auth_failed", crate::i18n::Lang::Nb).await;
    assert!(
        nb.last()
            .unwrap()
            .starts_with("9940 til dette minuttet fra minst 1000 adresser"),
        "{nb:?}"
    );
    // Another kind has its own budget.
    app.state
        .audit
        .record(
            &app.db,
            "enroll_failed",
            "203.0.113.7",
            None,
            crate::security::Detail::words("security.detail.enroll_failed.typed", &[]),
        )
        .await;
    let enroll: i64 = sqlx::query_scalar(
        "SELECT COUNT(*) FROM security_events WHERE event_type = 'enroll_failed'",
    )
    .fetch_one(&app.db)
    .await
    .unwrap();
    assert_eq!(enroll, 1);
}
