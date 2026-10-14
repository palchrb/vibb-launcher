//! Admin sign-in and sessions (design 22 §3.2, §9 "Server, admin auth").

use std::time::{Duration, Instant};

use axum::body::Body;
use axum::http::{Method, Request, StatusCode, header};
use http_body_util::BodyExt;

use super::{LOOPBACK_PEER, TestApp, TestResponse};
use crate::security;

const USER: &str = "parent";
const PASSWORD: &str = "correct horse battery staple";
const SECRET: &str = "JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP";

/// `name=value` of a response's session cookie.
fn cookie_of(res: &TestResponse) -> Option<String> {
    res.headers
        .get(header::SET_COOKIE)
        .and_then(|v| v.to_str().ok())
        .and_then(|v| v.split(';').next())
        .map(str::to_string)
}

/// The password step; the pending session's cookie.
async fn password_step(app: &TestApp, password: &str) -> (TestResponse, Option<String>) {
    let res = app
        .request_form(
            Method::POST,
            "/login",
            None,
            &[("username", USER), ("password", password)],
        )
        .await;
    let cookie = cookie_of(&res);
    (res, cookie)
}

async fn totp_step(app: &TestApp, cookie: &str, code: &str) -> TestResponse {
    app.request_form(
        Method::POST,
        "/auth/verify-2fa",
        Some(cookie),
        &[("code", code)],
    )
    .await
}

async fn counters(app: &TestApp) -> (i64, i64, bool) {
    sqlx::query_as(
        "SELECT failed_login_attempts, lock_count, \
         COALESCE(locked_until > datetime('now'), 0) FROM admin_users WHERE username = ?",
    )
    .bind(USER)
    .fetch_one(&app.db)
    .await
    .unwrap()
}

/// Lets the current lock run out (as if its time had passed).
async fn expire_lock(app: &TestApp) {
    sqlx::query("UPDATE admin_users SET locked_until = datetime('now', '-1 second')")
        .execute(&app.db)
        .await
        .unwrap();
}

async fn lock_minutes(app: &TestApp) -> i64 {
    sqlx::query_scalar(
        "SELECT CAST(ROUND((julianday(locked_until) - julianday('now')) * 1440) AS INTEGER) \
         FROM admin_users WHERE username = ?",
    )
    .bind(USER)
    .fetch_one(&app.db)
    .await
    .unwrap()
}

#[tokio::test]
async fn totp_failures_survive_a_correct_password_and_the_lock_doubles() {
    let app = TestApp::new().await;
    app.admin_cookie().await; // seeds the onboarded admin
    for n in 1..=4 {
        let (res, cookie) = password_step(&app, PASSWORD).await;
        assert_eq!(res.location(), Some("/auth/verify-2fa"));
        let res = totp_step(&app, &cookie.unwrap(), "000000").await;
        assert!(res.text().contains("match. Try again."), "{}", res.text());
        // A correct password doesn't reset the count (it did before: §0 #2).
        assert_eq!(counters(&app).await, (n, 0, false));
    }
    let (_, cookie) = password_step(&app, PASSWORD).await;
    let res = totp_step(&app, &cookie.unwrap(), "000000").await;
    assert!(res.text().contains("locked"), "{}", res.text());
    assert_eq!(counters(&app).await, (5, 1, true));
    assert_eq!(lock_minutes(&app).await, 15);

    // Locked: even the right code is refused, and hammering doesn't extend the lock.
    let (res, _) = password_step(&app, PASSWORD).await;
    assert!(res.text().contains("locked"), "{}", res.text());
    assert_eq!(counters(&app).await, (5, 1, true));

    // The lock runs out, the counter doesn't: the next failure locks again, for twice as long.
    expire_lock(&app).await;
    let (_, cookie) = password_step(&app, PASSWORD).await;
    totp_step(&app, &cookie.unwrap(), "000000").await;
    assert_eq!(counters(&app).await, (6, 2, true));
    assert_eq!(lock_minutes(&app).await, 30);
    for expected in [60, 120, 240, 480, 960, 1440, 1440] {
        expire_lock(&app).await;
        let (_, cookie) = password_step(&app, PASSWORD).await;
        totp_step(&app, &cookie.unwrap(), "000000").await;
        assert_eq!(lock_minutes(&app).await, expected);
    }

    // A complete login resets it all and shows the banner with the count and the address.
    expire_lock(&app).await;
    let (_, cookie) = password_step(&app, PASSWORD).await;
    let code = app.next_totp_code(USER, SECRET).await;
    let res = totp_step(&app, &cookie.unwrap(), &code).await;
    assert_eq!(res.location(), Some("/"), "{}", res.text());
    let session = cookie_of(&res).unwrap();
    assert_eq!(counters(&app).await, (0, 0, false));
    // The full sentence on Account; a short orange row on Devices (design 31 §3.2).
    let page = app.get_page("/account", &session).await.text();
    assert!(
        page.contains(
            "13 wrong two-factor codes were entered before this sign-in (from 127.0.0.1)"
        ),
        "{page}"
    );
    let page = app.get_page("/devices", &session).await.text();
    assert!(page.contains("id=\"login-warning\""), "{page}");
    assert!(page.contains("Wrong two-factor codes before this sign-in"));
    // Dismissed, it's gone.
    let res = app
        .request_form(
            Method::POST,
            "/account/dismiss-warning",
            Some(&session),
            &[],
        )
        .await;
    assert_eq!(res.location(), Some("/devices"));
    let page = app.get_page("/devices", &session).await.text();
    assert!(!page.contains("id=\"login-warning\""));
    let page = app.get_page("/account", &session).await.text();
    assert!(!page.contains("wrong two-factor codes were entered before"));
}

#[tokio::test]
async fn wrong_passwords_never_lock_the_account() {
    let app = TestApp::new().await;
    app.admin_cookie().await;
    for _ in 0..10 {
        let (res, _) = password_step(&app, "wrong password").await;
        assert!(res.text().contains("Incorrect username or password."));
    }
    assert_eq!(counters(&app).await, (0, 0, false));
    // An unknown name gets the same answer (after the same argon2 work).
    let res = app
        .request_form(
            Method::POST,
            "/login",
            None,
            &[("username", "nobody"), ("password", "x")],
        )
        .await;
    assert!(res.text().contains("Incorrect username or password."));
    let (res, _) = password_step(&app, PASSWORD).await;
    assert_eq!(res.location(), Some("/auth/verify-2fa"));
}

#[tokio::test]
async fn a_totp_code_works_once() {
    let app = TestApp::new().await;
    app.admin_cookie().await;
    let (_, first) = password_step(&app, PASSWORD).await;
    let (_, second) = password_step(&app, PASSWORD).await;
    let code = app.next_totp_code(USER, SECRET).await;
    let res = totp_step(&app, &first.unwrap(), &code).await;
    assert_eq!(res.location(), Some("/"));
    let res = totp_step(&app, &second.unwrap(), &code).await;
    assert_ne!(res.location(), Some("/"), "the same code twice");
    assert_eq!(counters(&app).await.0, 1, "a replay counts as a failure");
}

#[test]
fn the_pending_state_lasts_five_minutes() {
    use crate::admin_auth::pending_fresh;
    assert!(pending_fresh(1_000, 1_000));
    assert!(pending_fresh(1_000, 1_300));
    assert!(!pending_fresh(1_000, 1_301));
    // A clock that went backwards doesn't keep it alive forever.
    assert!(pending_fresh(1_000, 900));
}

#[tokio::test]
async fn a_code_without_a_pending_password_step_signs_nobody_in() {
    let app = TestApp::new().await;
    app.admin_cookie().await;
    let code = app.next_totp_code(USER, SECRET).await;
    let res = app
        .request_form(Method::POST, "/auth/verify-2fa", None, &[("code", &code)])
        .await;
    assert_ne!(res.location(), Some("/"));
    assert!(res.text().contains("Sign-in timed out"), "{}", res.text());
}

#[tokio::test]
async fn a_password_change_signs_out_every_other_session() {
    let app = TestApp::new().await;
    let first = app.admin_cookie().await;
    let second = app.admin_cookie().await;
    assert_eq!(
        app.get_page("/devices", &second).await.status,
        StatusCode::OK
    );
    let res = app
        .request_form(
            Method::POST,
            "/account/password",
            Some(&first),
            &[
                ("current_password", PASSWORD),
                ("new_password", "a brand new long password"),
                ("confirm_password", "a brand new long password"),
            ],
        )
        .await;
    // Post/redirect/get (qa-31-s14-code M1): the result is on the GET.
    super::assert_redirect(&res, "/account?changed=password", Some("password"));
    // This one stays, the other is signed out.
    assert_eq!(
        app.get_page("/devices", &first).await.status,
        StatusCode::OK
    );
    assert_eq!(
        app.get_page("/devices", &second).await.location(),
        Some("/login")
    );

    // "Sign out everywhere" ends this one too.
    let res = app
        .request_form(
            Method::POST,
            "/account/sign-out-everywhere",
            Some(&first),
            &[],
        )
        .await;
    assert_eq!(res.location(), Some("/login"));
    assert_eq!(
        app.get_page("/devices", &first).await.location(),
        Some("/login")
    );
}

#[tokio::test]
async fn a_2fa_reset_signs_out_every_other_session() {
    let app = TestApp::new().await;
    let first = app.admin_cookie().await;
    let second = app.admin_cookie().await;
    let res = app
        .request_form(Method::POST, "/account/reset-2fa", Some(&first), &[])
        .await;
    // QA 22 M5 (user decision): it asks for the current password, like a password change.
    assert_eq!(res.status, StatusCode::BAD_REQUEST);
    assert!(res.text().contains("Two-factor login was not reset."));
    let res = app
        .request_form(
            Method::POST,
            "/account/reset-2fa",
            Some(&first),
            &[("current_password", "not the password")],
        )
        .await;
    assert_eq!(res.status, StatusCode::BAD_REQUEST);
    assert_eq!(
        app.get_page("/devices", &second).await.status,
        StatusCode::OK,
        "nothing changed"
    );
    let res = app
        .request_form(
            Method::POST,
            "/account/reset-2fa",
            Some(&first),
            &[("current_password", PASSWORD)],
        )
        .await;
    assert_eq!(res.location(), Some("/auth/setup-2fa"));
    assert_eq!(
        app.get_page("/devices", &second).await.location(),
        Some("/login")
    );
    assert_eq!(
        app.get_page("/devices", &first).await.location(),
        Some("/auth/setup-2fa"),
        "this one goes on to set up a new authenticator"
    );
}

#[tokio::test]
async fn reset_2fa_from_the_command_line_clears_the_lock_and_the_totp() {
    let app = TestApp::new().await;
    let session = app.admin_cookie().await;
    for _ in 0..5 {
        let (_, cookie) = password_step(&app, PASSWORD).await;
        if let Some(cookie) = cookie {
            totp_step(&app, &cookie, "000000").await;
        }
    }
    assert!(counters(&app).await.2, "locked");
    let data = tempfile::tempdir().unwrap();
    let message = crate::admin_auth::reset_2fa(&app.db, USER, data.path())
        .await
        .unwrap();
    assert!(message.contains("reset"));
    let new_password = std::fs::read_to_string(data.path().join("initial-admin-password"))
        .unwrap()
        .trim()
        .to_string();
    assert_eq!(new_password.len(), 24);
    assert_eq!(counters(&app).await, (0, 0, false));
    let (enabled, secret): (bool, Option<String>) =
        sqlx::query_as("SELECT totp_enabled, totp_secret FROM admin_users WHERE username = ?")
            .bind(USER)
            .fetch_one(&app.db)
            .await
            .unwrap();
    assert!(!enabled && secret.is_none());
    // Every session is signed out, and the old password no longer signs in (QA 22 M4): whoever
    // knew it can't take the account by setting up an authenticator first.
    assert_eq!(
        app.get_page("/devices", &session).await.location(),
        Some("/login")
    );
    let (res, _) = password_step(&app, PASSWORD).await;
    assert!(res.text().contains("Incorrect username or password."));
    // The new one leads to a forced password change, then the 2FA setup.
    let (res, cookie) = password_step(&app, &new_password).await;
    assert_eq!(res.location(), Some("/"));
    assert_eq!(
        app.get_page("/devices", &cookie.unwrap()).await.location(),
        Some("/auth/change-password")
    );
    assert!(
        crate::admin_auth::reset_2fa(&app.db, "nobody", data.path())
            .await
            .is_err()
    );
}

/// argon2 runs on the blocking pool behind the gate: a burst of wrong passwords never stalls
/// the workers (this runtime has one), and what doesn't fit the queue gets 429.
#[tokio::test]
async fn a_burst_of_logins_never_stalls_the_stream() {
    let app = TestApp::new().await;
    app.admin_cookie().await;
    let (_, token) = app.enrolled_device("phone").await;
    let mut state = app.state.clone();
    let mut config = (*state.config).clone();
    config.sse_keepalive_secs = 1;
    state.config = std::sync::Arc::new(config);
    let router = app.admin_router(state);
    let stream = app
        .call_via(
            &router,
            LOOPBACK_PEER,
            Request::builder()
                .uri("/api/devices/commands/stream")
                .header(header::AUTHORIZATION, format!("Bearer {token}"))
                .body(Body::empty())
                .unwrap(),
        )
        .await;
    let mut body = stream.into_body();

    let mut logins = tokio::task::JoinSet::new();
    for _ in 0..20 {
        let router = router.clone();
        let request = Request::builder()
            .method(Method::POST)
            .uri("/login")
            .header(header::CONTENT_TYPE, "application/x-www-form-urlencoded")
            .body(Body::from(format!("username={USER}&password=wrong")))
            .unwrap();
        logins.spawn(async move {
            let mut request = request;
            request
                .extensions_mut()
                .insert(axum::extract::ConnectInfo(LOOPBACK_PEER));
            tower::ServiceExt::oneshot(router, request)
                .await
                .unwrap()
                .status()
        });
    }

    let started = Instant::now();
    let mut last = started;
    let mut keepalives = 0;
    while started.elapsed() < Duration::from_secs(4) {
        let frame = tokio::time::timeout(Duration::from_millis(2500), body.frame())
            .await
            .expect("no keepalive for 2.5 s: the workers are stalled");
        assert!(frame.is_some(), "the stream ended");
        let gap = last.elapsed();
        assert!(gap < Duration::from_millis(2500), "a {gap:?} gap");
        last = Instant::now();
        keepalives += 1;
    }
    assert!(keepalives >= 3, "{keepalives} keepalives");

    let statuses = logins.join_all().await;
    let busy = statuses
        .iter()
        .filter(|s| **s == StatusCode::TOO_MANY_REQUESTS)
        .count();
    assert!(busy >= 10, "only {busy} of 20 got 429: {statuses:?}");
    assert!(
        statuses
            .iter()
            .all(|s| *s == StatusCode::OK || *s == StatusCode::TOO_MANY_REQUESTS)
    );
}

#[tokio::test]
async fn cross_site_forms_are_refused() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let post = |headers: &[(&'static str, &'static str)], name: &str| {
        let mut builder = Request::builder()
            .method(Method::POST)
            .uri("/devices/new")
            .header(header::HOST, "pi.tail1234.ts.net")
            .header(header::COOKIE, &cookie)
            .header(header::CONTENT_TYPE, "application/x-www-form-urlencoded");
        for (k, v) in headers {
            builder = builder.header(*k, *v);
        }
        builder.body(Body::from(format!("name={name}"))).unwrap()
    };
    let count = |app: &TestApp| {
        let db = app.db.clone();
        async move {
            sqlx::query_scalar::<_, i64>("SELECT COUNT(*) FROM devices")
                .fetch_one(&db)
                .await
                .unwrap()
        }
    };
    let res = app
        .send_via(
            &app.router,
            LOOPBACK_PEER,
            post(&[("sec-fetch-site", "cross-site")], "a"),
        )
        .await;
    assert_eq!(res.status, StatusCode::FORBIDDEN);
    let res = app
        .send_via(
            &app.router,
            LOOPBACK_PEER,
            post(&[("origin", "https://evil.example")], "b"),
        )
        .await;
    assert_eq!(res.status, StatusCode::FORBIDDEN);
    assert_eq!(count(&app).await, 0, "nothing was created");

    for (headers, name) in [
        (
            &[
                ("sec-fetch-site", "same-origin"),
                ("origin", "https://pi.tail1234.ts.net"),
            ][..],
            "c",
        ),
        (&[("origin", "https://pi.tail1234.ts.net")][..], "d"),
        (&[][..], "e"),
    ] {
        let res = app
            .send_via(&app.router, LOOPBACK_PEER, post(headers, name))
            .await;
        assert!(res.status.is_redirection(), "{headers:?}: {}", res.status);
    }
    assert_eq!(count(&app).await, 3);

    // Phones post without either header, and are never refused.
    let (_, token) = app.enrolled_device("phone").await;
    let request = Request::builder()
        .method(Method::POST)
        .uri("/api/devices/crashes")
        .header(header::AUTHORIZATION, format!("Bearer {token}"))
        .header(header::CONTENT_TYPE, "application/json")
        .header("sec-fetch-site", "cross-site")
        .body(Body::from("{\"crashes\": []}"))
        .unwrap();
    let res = app.send_via(&app.router, LOOPBACK_PEER, request).await;
    assert_eq!(res.status, StatusCode::NO_CONTENT);
}

#[tokio::test]
async fn admin_responses_carry_the_security_headers_and_cache_rules() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, _) = app.create_device("phone").await;
    let check = |res: &TestResponse, cache: Option<&str>| {
        for (name, value) in [
            ("strict-transport-security", "max-age=31536000"),
            ("x-content-type-options", "nosniff"),
            ("referrer-policy", "same-origin"),
            ("x-frame-options", "DENY"),
            ("cross-origin-opener-policy", "same-origin"),
            (
                "permissions-policy",
                "camera=(), microphone=(), geolocation=()",
            ),
        ] {
            assert_eq!(
                res.headers.get(name).and_then(|v| v.to_str().ok()),
                Some(value),
                "{name}"
            );
        }
        let csp = res
            .headers
            .get(header::CONTENT_SECURITY_POLICY)
            .unwrap()
            .to_str()
            .unwrap();
        assert!(csp.contains("frame-ancestors 'none'"), "{csp}");
        // Images: our own, data: URIs and OSM's tiles (design 33) - nothing broader.
        let img_src: Vec<&str> = csp
            .split(';')
            .map(str::trim)
            .filter(|directive| directive.starts_with("img-src"))
            .collect();
        assert_eq!(
            img_src,
            ["img-src 'self' data: https://tile.openstreetmap.org"],
            "{csp}"
        );
        assert_eq!(
            res.headers
                .get(header::CACHE_CONTROL)
                .and_then(|v| v.to_str().ok()),
            cache
        );
    };
    let page = app.get_page("/devices", &cookie).await;
    check(&page, Some("private, no-cache"));
    let page = app.get_page(&format!("/devices/{id}"), &cookie).await;
    check(&page, Some("private, no-cache"));
    for path in [
        "/account".to_string(),
        "/settings/provisioning".to_string(),
        format!("/devices/{id}/provision"),
        format!("/devices/{id}/locations.json"),
        "/backups".to_string(),
    ] {
        let res = app.get_page(&path, &cookie).await;
        assert_eq!(res.status, StatusCode::OK, "{path}");
        check(&res, Some("no-store"));
    }
    let res = app
        .request_form(
            Method::POST,
            "/login",
            None,
            &[("username", "x"), ("password", "y")],
        )
        .await;
    check(&res, Some("private, no-cache"));
    let res = app.get_page("/static/style.css", &cookie).await;
    assert_eq!(res.status, StatusCode::OK);
    check(&res, None);
}

#[tokio::test]
async fn the_session_cookie_is_host_prefixed_unless_insecure() {
    let app = TestApp::new().await;
    // Tests run as INSECURE_COOKIES (plain http): a plain `handy`.
    let cookie = app.admin_cookie().await;
    assert!(cookie.starts_with("handy="), "{cookie}");
    // In production: `__Host-handy`, Secure, Path=/, no Domain, HttpOnly, SameSite=Strict.
    let state = app.state.clone();
    let store = tower_sessions_sqlx_store::SqliteStore::new(app.db.clone());
    let router = crate::build_admin_router(state, security::session_layer(store, false, 7));
    let res = app
        .send_via(
            &router,
            LOOPBACK_PEER,
            Request::builder()
                .method(Method::POST)
                .uri("/login")
                .header(header::CONTENT_TYPE, "application/x-www-form-urlencoded")
                .body(Body::from(format!("username={USER}&password={PASSWORD}")))
                .unwrap(),
        )
        .await;
    let set_cookie = res
        .headers
        .get(header::SET_COOKIE)
        .unwrap()
        .to_str()
        .unwrap()
        .to_string();
    assert!(set_cookie.starts_with("__Host-handy="), "{set_cookie}");
    for part in ["Secure", "HttpOnly", "SameSite=Strict", "Path=/"] {
        assert!(set_cookie.contains(part), "{set_cookie}");
    }
    assert!(!set_cookie.contains("Domain"), "{set_cookie}");
}

#[tokio::test]
async fn the_tailscale_key_is_write_only() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    fn save(key: &str, clear: bool) -> Vec<(&str, &str)> {
        let mut fields = vec![
            ("server_url", "https://pi.tail1234.ts.net:8444"),
            ("tailscale_auth_key", key),
            ("locale", "nb_NO"),
            ("time_zone", "Europe/Oslo"),
        ];
        if clear {
            fields.push(("clear_tailscale_auth_key", "on"));
        }
        fields
    }
    // Built from parts and without the real format's "CNTRL" marker, so secret scanners don't take a test value
    // for a real Tailscale key (GitHub flagged the earlier literal).
    let key = ["tskey-auth-", "TESTONLY", "-not-a-real-key-9z8y"].concat();
    let key = key.as_str();
    let res = app
        .request_form(
            Method::POST,
            "/settings/provisioning",
            Some(&cookie),
            &save(key, false),
        )
        .await;
    assert!(res.status.is_redirection());
    let page = app.get_page("/settings/provisioning", &cookie).await.text();
    assert!(!page.contains("supersecret"), "the key came back");
    assert!(page.contains("Set (tskey-auth-...9z8y)."), "{page}");
    // Empty keeps it.
    app.request_form(
        Method::POST,
        "/settings/provisioning",
        Some(&cookie),
        &save("", false),
    )
    .await;
    let stored: String =
        sqlx::query_scalar("SELECT tailscale_auth_key FROM provisioning_settings WHERE id = 1")
            .fetch_one(&app.db)
            .await
            .unwrap();
    assert_eq!(stored, key);
    // "Remove" clears it.
    app.request_form(
        Method::POST,
        "/settings/provisioning",
        Some(&cookie),
        &save("", true),
    )
    .await;
    let page = app.get_page("/settings/provisioning", &cookie).await.text();
    assert!(page.contains("Not set."));
}

#[tokio::test]
async fn the_security_log_is_pruned_after_180_days() {
    let app = TestApp::new().await;
    for days in [200, 100] {
        sqlx::query(
            "INSERT INTO security_events (event_type, created_at) \
             VALUES ('login_failed', datetime('now', ?))",
        )
        .bind(format!("-{days} days"))
        .execute(&app.db)
        .await
        .unwrap();
    }
    let pruned = crate::retention::prune(&app.db).await.unwrap();
    assert_eq!(pruned.security_events, 1);
    let left: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM security_events")
        .fetch_one(&app.db)
        .await
        .unwrap();
    assert_eq!(left, 1);
}

#[tokio::test]
async fn ipv6_login_failures_are_banned_by_their_64() {
    let app = TestApp::new().await;
    app.admin_cookie().await;
    for n in 0..security::MAX_FAILED_PER_IP {
        let request = Request::builder()
            .method(Method::POST)
            .uri("/login")
            .header(header::CONTENT_TYPE, "application/x-www-form-urlencoded")
            .header(
                "x-forwarded-for",
                format!("fd7a:115c:a1e0:1:2:3:4:{:x}", n + 1),
            )
            .body(Body::from(format!("username={USER}&password=wrong")))
            .unwrap();
        let res = app.send_via(&app.router, LOOPBACK_PEER, request).await;
        assert_eq!(res.status, StatusCode::OK);
    }
    let request = Request::builder()
        .method(Method::POST)
        .uri("/login")
        .header(header::CONTENT_TYPE, "application/x-www-form-urlencoded")
        .header("x-forwarded-for", "fd7a:115c:a1e0:1:ffff::1")
        .body(Body::from(format!("username={USER}&password={PASSWORD}")))
        .unwrap();
    let res = app.send_via(&app.router, LOOPBACK_PEER, request).await;
    assert_eq!(res.status, StatusCode::TOO_MANY_REQUESTS);
    let banned: String = sqlx::query_scalar("SELECT ip_address FROM banned_ips")
        .fetch_one(&app.db)
        .await
        .unwrap();
    assert_eq!(banned, "fd7a:115c:a1e0:1::/64");
    // The security page unbans it by a form field (a /64 can't be a path segment).
    let session = app.admin_cookie().await;
    let res = app
        .request_form(
            Method::POST,
            "/security/unban",
            Some(&session),
            &[("ip", &banned)],
        )
        .await;
    // Back to Blocked Addresses, the address in the flash (design 31 step 14).
    super::assert_redirect(
        &res,
        "/security?unbanned=fd7a%3A115c%3Aa1e0%3A1%3A%3A%2F64",
        Some("banned"),
    );
    let left: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM banned_ips")
        .fetch_one(&app.db)
        .await
        .unwrap();
    assert_eq!(left, 0);
}

/// QA 22 H2, as QA probed it: a burst of wrong codes on one pending session checks at most 5,
/// however many arrive at once.
#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_burst_of_codes_checks_at_most_five() {
    let app = TestApp::new().await;
    app.admin_cookie().await;
    let (_, cookie) = password_step(&app, PASSWORD).await;
    let cookie = cookie.unwrap();
    let mut burst = tokio::task::JoinSet::new();
    for n in 0..50 {
        let router = app.router.clone();
        let request = Request::builder()
            .method(Method::POST)
            .uri("/auth/verify-2fa")
            .header(header::COOKIE, &cookie)
            .header(header::CONTENT_TYPE, "application/x-www-form-urlencoded")
            .body(Body::from(format!("code={:06}", 100_000 + n)))
            .unwrap();
        burst.spawn(async move {
            let mut request = request;
            request
                .extensions_mut()
                .insert(axum::extract::ConnectInfo(LOOPBACK_PEER));
            tower::ServiceExt::oneshot(router, request).await.unwrap()
        });
    }
    burst.join_all().await;
    let checked: i64 =
        sqlx::query_scalar("SELECT COUNT(*) FROM security_events WHERE event_type = 'totp_failed'")
            .fetch_one(&app.db)
            .await
            .unwrap();
    assert!(checked <= 5, "{checked} codes were checked");
    let (failures, locks, locked) = counters(&app).await;
    assert_eq!((failures, locks, locked), (5, 1, true));
}

/// QA 22 M1: a client that hangs up while argon2 runs still leaves its failed attempt, so the IP
/// ban still comes.
#[tokio::test]
async fn a_login_dropped_mid_verify_still_counts() {
    let app = TestApp::new().await;
    app.admin_cookie().await;
    let router = app.router.clone();
    let mut request = Request::builder()
        .method(Method::POST)
        .uri("/login")
        .header(header::CONTENT_TYPE, "application/x-www-form-urlencoded")
        .body(Body::from(format!("username={USER}&password=wrong")))
        .unwrap();
    request
        .extensions_mut()
        .insert(axum::extract::ConnectInfo(LOOPBACK_PEER));
    let login = tokio::spawn(async move { tower::ServiceExt::oneshot(router, request).await });
    let rows = || async {
        sqlx::query_scalar::<_, i64>(
            "SELECT COUNT(*) FROM security_events WHERE event_type = 'login_failed'",
        )
        .fetch_one(&app.db)
        .await
        .unwrap()
    };
    let started = std::time::Instant::now();
    while rows().await == 0 && started.elapsed() < Duration::from_secs(10) {
        tokio::time::sleep(Duration::from_millis(5)).await;
    }
    login.abort();
    let _ = login.await;
    assert_eq!(rows().await, 1, "the dropped attempt counts");
    // A good login takes its own attempt back.
    let (res, _) = password_step(&app, PASSWORD).await;
    assert_eq!(res.location(), Some("/auth/verify-2fa"));
    assert_eq!(rows().await, 1);
}

/// QA 22 Low 1: the signed-out forms take a few fields, not axum's 2 MB default; usernames in the
/// log are cut to 64 characters.
#[tokio::test]
async fn signed_out_forms_are_small_and_log_short_names() {
    let app = TestApp::new().await;
    let long = "x".repeat(5000);
    let res = app
        .request_form(
            Method::POST,
            "/login",
            None,
            &[("username", long.as_str()), ("password", "y")],
        )
        .await;
    assert_eq!(res.status, StatusCode::PAYLOAD_TOO_LARGE);
    let res = app
        .request_form(
            Method::POST,
            "/auth/verify-2fa",
            None,
            &[("code", long.as_str())],
        )
        .await;
    assert_eq!(res.status, StatusCode::PAYLOAD_TOO_LARGE);
    let name = "é".repeat(500);
    app.request_form(
        Method::POST,
        "/login",
        None,
        &[("username", name.as_str()), ("password", "y")],
    )
    .await;
    let logged: String = sqlx::query_scalar(
        "SELECT username FROM security_events WHERE event_type = 'login_failed'",
    )
    .fetch_one(&app.db)
    .await
    .unwrap();
    assert_eq!(logged.chars().count(), 64);
}

/// QA 22 Low 9: a signed-in parent sees wrong codes and the lock as they happen, a new password
/// lifts them, and Dismiss returns to the page it was on.
#[tokio::test]
async fn a_live_session_sees_wrong_codes_and_the_lock() {
    let app = TestApp::new().await;
    let session = app.admin_cookie().await;
    for _ in 0..5 {
        let (_, cookie) = password_step(&app, PASSWORD).await;
        totp_step(&app, &cookie.unwrap(), "000000").await;
    }
    let text = app.get_page("/account", &session).await.text();
    assert!(
        text.contains("5 wrong two-factor codes were entered since your last sign-in, and new sign-ins are locked until"),
        "{text}"
    );
    // Devices: a short row, the lock's end as a local clock time (Phone Setup's Oslo).
    let text = app.get_page("/devices", &session).await.text();
    assert!(
        text.contains("5 wrong two-factor codes since sign-in"),
        "{text}"
    );
    assert!(
        text.contains("Someone knows your password - change it · sign-ins locked until "),
        "{text}"
    );
    assert!(
        !text.contains("locked until 2026"),
        "a clock time, not a UTC date"
    );
    let res = app
        .request_form(
            Method::POST,
            "/account/password",
            Some(&session),
            &[
                ("current_password", PASSWORD),
                ("new_password", "a brand new long password"),
                ("confirm_password", "a brand new long password"),
            ],
        )
        .await;
    super::assert_redirect(&res, "/account?changed=password", Some("password"));
    assert_eq!(counters(&app).await, (0, 0, false));
    assert!(
        !app.get_page("/devices", &session)
            .await
            .text()
            .contains("wrong two-factor")
    );

    let res = app
        .request_form(
            Method::POST,
            "/account/dismiss-warning",
            Some(&session),
            &[("back", "/account")],
        )
        .await;
    assert_eq!(res.location(), Some("/account"));
    let res = app
        .request_form(
            Method::POST,
            "/account/dismiss-warning",
            Some(&session),
            &[("back", "https://evil.example/")],
        )
        .await;
    assert_eq!(res.location(), Some("/devices"));
}
