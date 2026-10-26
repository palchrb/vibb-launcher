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
    let id: i64 = sqlx::query_scalar("SELECT id FROM admin_users WHERE username = ?")
        .bind(USER)
        .fetch_one(&app.db)
        .await
        .unwrap();
    // Its own file (design 62 §2), never the first account's shared one.
    let new_password =
        std::fs::read_to_string(data.path().join(format!("initial-admin-password-{id}")))
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
    let pruned = crate::retention::prune(&app.db).await;
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

/// The sign-in pages in the new components (design 31 step 15): one column with the name, a
/// grouped form and a wide button, no tab bar; a refusal is one red line, and nothing typed comes
/// back - no password, no code. The forced first steps (a new password, then two-factor login) keep
/// their behaviour; the key shows once, behind "Can't scan it?".
#[tokio::test]
async fn the_sign_in_pages_use_the_new_components() {
    let app = TestApp::new().await;
    let login = app.get_page("/login", "").await.text();
    for needle in [
        "<title>Sign In · Vibb Phone</title>",
        "<h1 class=\"auth-title\">Vibb Phone</h1>",
        "<form method=\"post\" action=\"/login\">",
        "<label class=\"field-label\" for=\"username\">",
        "<label class=\"field-label\" for=\"password\">",
        "<button type=\"submit\" class=\"button-wide\">Sign In</button>",
    ] {
        assert!(login.contains(needle), "{needle}: {login}");
    }
    assert!(!login.contains("tab-bar"));

    const FIRST: &str = "generated first password";
    sqlx::query(
        "INSERT INTO admin_users (username, password_hash, must_change_password, totp_enabled) \
         VALUES (?, ?, 1, 0)",
    )
    .bind(USER)
    .bind(security::hash_password(FIRST))
    .execute(&app.db)
    .await
    .unwrap();
    let (res, _) = password_step(&app, "not the password").await;
    let text = res.text();
    assert!(
        text.contains(
            "<p class=\"flash flash-error auth-error\" role=\"alert\">Incorrect username or password.</p>"
        ),
        "{text}"
    );
    assert!(!text.contains("not the password"));

    let (res, cookie) = password_step(&app, FIRST).await;
    assert_eq!(res.location(), Some("/"));
    let cookie = cookie.unwrap();
    let page = app.get_page("/auth/change-password", &cookie).await.text();
    assert!(
        page.contains("<h1 class=\"auth-title\">New Password</h1>"),
        "{page}"
    );
    assert!(page.contains("<p class=\"auth-brand\">Vibb Phone</p>"));
    let res = app
        .request_form(
            Method::POST,
            "/auth/change-password",
            Some(&cookie),
            &[
                ("current_password", FIRST),
                ("new_password", "a brand new password"),
                ("confirm_password", "a different new password"),
            ],
        )
        .await;
    let text = res.text();
    assert!(
        text.contains("role=\"alert\">New passwords don&#39;t match.</p>"),
        "{text}"
    );
    assert!(!text.contains("brand new password") && !text.contains(FIRST));
    let res = app
        .request_form(
            Method::POST,
            "/auth/change-password",
            Some(&cookie),
            &[
                ("current_password", FIRST),
                ("new_password", "a brand new password"),
                ("confirm_password", "a brand new password"),
            ],
        )
        .await;
    assert_eq!(res.location(), Some("/"));

    let page = app.get_page("/auth/setup-2fa", &cookie).await.text();
    let secret: String =
        sqlx::query_scalar("SELECT totp_secret FROM admin_users WHERE username = ?")
            .bind(USER)
            .fetch_one(&app.db)
            .await
            .unwrap();
    assert!(
        page.contains("<h1 class=\"auth-title\">Two-Factor Login</h1>"),
        "{page}"
    );
    // Once, in blocks of 4 so it wraps between blocks (the browser pass of 2026-10-14, 6g).
    let blocks: Vec<String> = secret
        .as_bytes()
        .chunks(4)
        .map(|block| String::from_utf8(block.to_vec()).unwrap())
        .collect();
    let blocks = blocks.join(" ");
    assert_eq!(page.matches(blocks.as_str()).count(), 1, "the key once");
    let panel = &page[page.find("Can't scan it?").unwrap()..];
    assert!(panel[..panel.find("</details>").unwrap()].contains(&blocks));
    let res = app
        .request_form(
            Method::POST,
            "/auth/setup-2fa",
            Some(&cookie),
            &[("code", "000000")],
        )
        .await;
    let text = res.text();
    assert!(
        text.contains("role=\"alert\">That code didn&#39;t match. Try again.</p>"),
        "{text}"
    );
    assert!(!text.contains("value=\"000000\""));
    let code = app.next_totp_code(USER, &secret).await;
    let res = app
        .request_form(
            Method::POST,
            "/auth/setup-2fa",
            Some(&cookie),
            &[("code", &code)],
        )
        .await;
    assert_eq!(res.location(), Some("/"));

    // The code step, after the next password.
    let (_, pending) = password_step(&app, "a brand new password").await;
    let page = app
        .get_page("/auth/verify-2fa", &pending.unwrap())
        .await
        .text();
    assert!(
        page.contains("<h1 class=\"auth-title\">Two-Factor Code</h1>"),
        "{page}"
    );
    assert!(page.contains("autocomplete=\"one-time-code\""));
    assert!(!page.contains("tab-bar"));
}

/// A GET's raw answer (its extensions kept) with an optional cookie.
async fn raw_get(app: &TestApp, uri: &str, cookie: Option<&str>) -> axum::response::Response {
    let mut builder = Request::builder().uri(uri);
    if let Some(cookie) = cookie {
        builder = builder.header(header::COOKIE, cookie);
    }
    app.call_via(
        &app.router,
        LOOPBACK_PEER,
        builder.body(Body::empty()).unwrap(),
    )
    .await
}

/// Why a page sent the browser to `/login` (`security::SignInWhy` on its redirect).
async fn why_signed_out(app: &TestApp, cookie: Option<&str>) -> Option<security::SignInWhy> {
    let res = raw_get(app, "/devices", cookie).await;
    assert_eq!(res.status(), StatusCode::SEE_OTHER);
    assert_eq!(
        res.headers()
            .get(header::LOCATION)
            .unwrap()
            .to_str()
            .unwrap(),
        "/login"
    );
    res.extensions().get::<security::SignInWhy>().copied()
}

/// 2026-10-25 (PLAN "PWA shows the login page after a Tailscale outage"): every redirect to the
/// sign-in page says why, for `security::log_sign_in_redirects`' one log line.
#[tokio::test]
async fn a_redirect_to_the_sign_in_page_says_why() {
    use security::SignInWhy;
    let app = TestApp::new().await;
    assert_eq!(why_signed_out(&app, None).await, Some(SignInWhy::NoCookie));
    assert_eq!(
        why_signed_out(&app, Some("other=1")).await,
        Some(SignInWhy::NoCookie),
        "another cookie isn't the session's"
    );
    assert_eq!(
        why_signed_out(&app, Some("handy=not-a-session-id")).await,
        Some(SignInWhy::CookieNotASession)
    );

    // A session whose row is past its expiry, then one whose row is gone.
    let cookie = app.admin_cookie().await;
    assert_eq!(
        app.get_page("/devices", &cookie).await.status,
        StatusCode::OK
    );
    sqlx::query("UPDATE tower_sessions SET expiry_date = '2000-01-01 00:00:00.0 +00:00:00'")
        .execute(&app.db)
        .await
        .unwrap();
    assert_eq!(
        why_signed_out(&app, Some(&cookie)).await,
        Some(SignInWhy::Expired)
    );
    sqlx::query("DELETE FROM tower_sessions")
        .execute(&app.db)
        .await
        .unwrap();
    assert_eq!(
        why_signed_out(&app, Some(&cookie)).await,
        Some(SignInWhy::NoSessionRow)
    );

    // The account's epoch moved on (Sign Out Everywhere, a password change).
    let cookie = app.admin_cookie().await;
    sqlx::query("UPDATE admin_users SET session_epoch = session_epoch + 1")
        .execute(&app.db)
        .await
        .unwrap();
    assert_eq!(
        why_signed_out(&app, Some(&cookie)).await,
        Some(SignInWhy::Epoch)
    );

    // A removed account.
    let cookie = app.admin_cookie().await;
    sqlx::query("UPDATE admin_users SET removed_at = datetime('now')")
        .execute(&app.db)
        .await
        .unwrap();
    assert_eq!(
        why_signed_out(&app, Some(&cookie)).await,
        Some(SignInWhy::AccountGone)
    );
    sqlx::query("UPDATE admin_users SET removed_at = NULL")
        .execute(&app.db)
        .await
        .unwrap();

    // A sign-in that stopped after the password step: a session without an account.
    let (_, pending) = password_step(&app, PASSWORD).await;
    assert_eq!(
        why_signed_out(&app, Some(&pending.unwrap())).await,
        Some(SignInWhy::NotSignedIn)
    );

    // Sign Out, and a re-auth's sign-out (`admin_auth::signed_out_response`).
    let cookie = app.admin_cookie().await;
    let request = Request::builder()
        .method(Method::POST)
        .uri("/logout")
        .header(header::COOKIE, &cookie)
        .header(header::CONTENT_TYPE, "application/x-www-form-urlencoded")
        .body(Body::empty())
        .unwrap();
    let res = app.call_via(&app.router, LOOPBACK_PEER, request).await;
    assert_eq!(
        res.extensions().get::<SignInWhy>().copied(),
        Some(SignInWhy::SignOut)
    );
    assert_eq!(
        crate::admin_auth::signed_out_response()
            .extensions()
            .get::<SignInWhy>()
            .copied(),
        Some(SignInWhy::Reauth)
    );
}

#[test]
fn the_session_cookie_is_found_by_its_name_alone() {
    let headers = |value: &str| {
        let mut map = axum::http::HeaderMap::new();
        map.append(header::COOKIE, value.parse().unwrap());
        map
    };
    let secure = security::session_cookie_name(false);
    let insecure = security::session_cookie_name(true);
    assert_eq!((secure, insecure), ("__Host-handy", "handy"));
    assert!(security::has_session_cookie(
        &headers("handy=abc"),
        insecure
    ));
    assert!(security::has_session_cookie(
        &headers("__Host-handy=abc"),
        secure
    ));
    assert!(security::has_session_cookie(
        &headers("a=1; handy="),
        insecure
    ));
    assert!(security::has_session_cookie(
        &headers("a=1;  __Host-handy=x; b=2"),
        secure
    ));
    // qa-pwa-fixes-d N-1: only this server's name - a leftover cookie of the other name is none.
    assert!(!security::has_session_cookie(&headers("handy=abc"), secure));
    assert!(!security::has_session_cookie(
        &headers("__Host-handy=abc"),
        insecure
    ));
    assert!(!security::has_session_cookie(
        &headers("handyx=1; spotify_login=2"),
        insecure
    ));
    assert!(!security::has_session_cookie(
        &axum::http::HeaderMap::new(),
        secure
    ));
}

/// `GET /api/session` (2026-10-25, static/reconnect.js's sign-in check): 204 with a signed-in,
/// onboarded session, 401 otherwise - no body, no-store, the session left as it is; the admin
/// listener's alone.
#[tokio::test]
async fn the_session_check_answers_204_or_401_and_changes_nothing() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    // qa-pwa-fixes-d Q-3: a check never saves the session - no Set-Cookie, the row's expiry as it
    // was (a saved session would be extended).
    let expiry = || async {
        sqlx::query_scalar::<_, String>("SELECT CAST(expiry_date AS TEXT) FROM tower_sessions")
            .fetch_all(&app.db)
            .await
            .unwrap()
    };
    let before = expiry().await;
    assert_eq!(before.len(), 1);
    let res = app.get_page("/api/session", &cookie).await;
    assert_eq!(res.status, StatusCode::NO_CONTENT);
    assert!(res.body.is_empty());
    assert_eq!(res.headers.get(header::CACHE_CONTROL).unwrap(), "no-store");
    assert!(res.headers.get(header::SET_COOKIE).is_none(), "204 saved");
    assert_eq!(expiry().await, before, "204 extended the session");

    for cookie in [None, Some("handy=not-a-session-id")] {
        let res = raw_get(&app, "/api/session", cookie).await;
        assert_eq!(res.status(), StatusCode::UNAUTHORIZED, "{cookie:?}");
        assert!(res.headers().get(header::LOCATION).is_none());
        // Never a new session; a dead cookie gets the session layer's own removal, as on any page.
        let set = res
            .headers()
            .get(header::SET_COOKIE)
            .map(|v| v.to_str().unwrap().to_string());
        assert!(
            set.as_deref()
                .is_none_or(|v| v.starts_with("handy=;") && v.contains("Max-Age=0")),
            "{cookie:?}: {set:?}"
        );
        assert_eq!(set.is_some(), cookie.is_some(), "{cookie:?}: {set:?}");
        assert_eq!(
            res.headers().get(header::CACHE_CONTROL).unwrap(),
            "no-store"
        );
    }

    // Onboarding not finished: 401, and the session stays (the next page still sends it on).
    sqlx::query("UPDATE admin_users SET must_change_password = 1")
        .execute(&app.db)
        .await
        .unwrap();
    let onboarding = app.get_page("/api/session", &cookie).await;
    assert_eq!(onboarding.status, StatusCode::UNAUTHORIZED);
    assert!(onboarding.headers.get(header::SET_COOKIE).is_none());
    assert_eq!(expiry().await, before, "a 401 extended the session");
    let next = app.get_page("/devices", &cookie).await;
    assert_eq!(next.location(), Some("/auth/change-password"));

    // A 401 for an old epoch flushes nothing: the row is still there.
    sqlx::query(
        "UPDATE admin_users SET must_change_password = 0, session_epoch = session_epoch + 1",
    )
    .execute(&app.db)
    .await
    .unwrap();
    let rows = "SELECT COUNT(*) FROM tower_sessions";
    let before: i64 = sqlx::query_scalar(rows).fetch_one(&app.db).await.unwrap();
    assert_eq!(
        app.get_page("/api/session", &cookie).await.status,
        StatusCode::UNAUTHORIZED
    );
    let after: i64 = sqlx::query_scalar(rows).fetch_one(&app.db).await.unwrap();
    assert_eq!(after, before);

    // The phone listener has no such route.
    let res = app
        .send_via(
            &app.device_router(),
            LOOPBACK_PEER,
            Request::builder()
                .uri("/api/session")
                .body(Body::empty())
                .unwrap(),
        )
        .await;
    assert_eq!(res.status, StatusCode::NOT_FOUND);
}

/// qa-pwa-fixes-d Q-2: the sign-in log lines and the gate's warning name the reason, the path and
/// that a cookie came - never the cookie's value, never the query; the gate warns only for a
/// request with this server's session cookie, at most once a minute per client (Q-4).
#[tokio::test]
async fn the_sign_in_log_lines_hold_no_cookie_value_and_no_query() {
    use super::music_spotify::{CAPTURE, install_capture};
    install_capture();
    CAPTURE.with(|capture| *capture.borrow_mut() = Some(Vec::new()));
    let app = TestApp::new().await;
    let get = |uri: &str, cookie: Option<&str>| {
        let mut builder = Request::builder().uri(uri.to_string());
        if let Some(cookie) = cookie {
            builder = builder.header(header::COOKIE, cookie.to_string());
        }
        builder.body(Body::empty()).unwrap()
    };
    // Two redirects to the sign-in page: a cookie that is no session, and none.
    let res = app
        .send_via(
            &app.router,
            LOOPBACK_PEER,
            get(
                "/devices?q=SECRETQ",
                Some("other=1; handy=SECRETVALUE; x=SECRETX"),
            ),
        )
        .await;
    assert_eq!(res.location(), Some("/login"));
    let res = app
        .send_via(&app.router, LOOPBACK_PEER, get("/ping?t=SECRETQ", None))
        .await;
    assert_eq!(res.location(), Some("/login"));
    // The gate: a refused client (a LAN address) with the session cookie, twice in the minute,
    // and another refused client without one.
    let lan: std::net::SocketAddr = "192.168.1.20:40000".parse().unwrap();
    for _ in 0..2 {
        let res = app
            .send_via(
                &app.router,
                lan,
                get("/devices?q=SECRETQ", Some("handy=SECRETVALUE")),
            )
            .await;
        assert_eq!(res.status, StatusCode::FORBIDDEN);
    }
    let other: std::net::SocketAddr = "192.168.1.21:40000".parse().unwrap();
    let res = app
        .send_via(&app.router, other, get("/settings?q=SECRETQ", None))
        .await;
    assert_eq!(res.status, StatusCode::FORBIDDEN);

    let lines: Vec<String> = CAPTURE
        .with(|capture| capture.borrow_mut().take())
        .unwrap_or_default();
    for line in &lines {
        assert!(!line.contains("SECRETVALUE"), "a cookie value: {line}");
        assert!(!line.contains("SECRETX"), "another cookie's value: {line}");
        assert!(!line.contains("SECRETQ"), "a query: {line}");
    }
    let sign_in: Vec<&String> = lines
        .iter()
        .filter(|l| l.contains("sent to the sign-in page"))
        .collect();
    assert_eq!(sign_in.len(), 2, "{lines:#?}");
    assert!(
        sign_in[0].contains("with a session cookie")
            && sign_in[0].contains("the cookie isn't a session id")
            && sign_in[0].contains("path=\"/devices\""),
        "{}",
        sign_in[0]
    );
    assert!(
        sign_in[1].contains("no session cookie")
            && sign_in[1].contains("cookie=false")
            && sign_in[1].contains("path=\"/ping\""),
        "{}",
        sign_in[1]
    );
    let gate: Vec<&String> = lines
        .iter()
        .filter(|l| l.contains("refused by the admin gate"))
        .collect();
    assert_eq!(
        gate.len(),
        1,
        "once a minute, never without the cookie: {lines:#?}"
    );
    assert!(
        gate[0].contains("not a tailnet or loopback client")
            && gate[0].contains("path=\"/devices\"")
            && gate[0].contains("192.168.1.20"),
        "{}",
        gate[0]
    );
}

/// Security review L6: a session ends `SESSION_MAX_DAYS` (90) after its sign-in however often it is
/// used; one from before the cap (no sign-in time) starts its clock at its next request, so an
/// upgrade signs nobody out. A real sign-in sets the time.
#[tokio::test]
async fn a_session_ends_after_its_absolute_lifetime() {
    use crate::admin_auth::{EPOCH_KEY, SIGNED_IN_AT_KEY, now_secs};
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    assert_eq!(app.get_page("/devices", &cookie).await.status, StatusCode::OK);
    let (admin_id, epoch): (i64, i64) =
        sqlx::query_as("SELECT id, session_epoch FROM admin_users LIMIT 1")
            .fetch_one(&app.db)
            .await
            .unwrap();
    let store = std::sync::Arc::new(tower_sessions_sqlx_store::SqliteStore::new(app.db.clone()));
    let session = tower_sessions::Session::new(None, store, None);
    session.insert("admin_id", admin_id).await.unwrap();
    session.insert(EPOCH_KEY, epoch).await.unwrap();
    assert!(security::load_active_admin(&app.state, &session).await.is_ok());
    let started: Option<u64> = session.get(SIGNED_IN_AT_KEY).await.unwrap();
    assert!(started.is_some_and(|at| at.abs_diff(now_secs()) < 60));
    let day = 86_400;
    session
        .insert(SIGNED_IN_AT_KEY, now_secs() - 89 * day)
        .await
        .unwrap();
    assert!(security::load_active_admin(&app.state, &session).await.is_ok());
    session
        .insert(SIGNED_IN_AT_KEY, now_secs() - 91 * day)
        .await
        .unwrap();
    assert_eq!(
        security::load_active_admin(&app.state, &session).await.err(),
        Some(security::SignInWhy::TooOld)
    );
    assert!(!security::session_too_old(1_000, 1_000 + 90 * day, 90));
    assert!(security::session_too_old(1_000, 1_001 + 90 * day, 90));
}

/// The CSP allows no inline script (security review L5, design 22 S7): no template - partials
/// included - has an inline `<script>`, an `on*=` handler or a `javascript:` link, and the policy
/// says `script-src 'self'` alone.
#[test]
fn no_template_has_inline_script() {
    fn inline_scripts(html: &str) -> Vec<String> {
        let lower = html.to_ascii_lowercase();
        let mut found = Vec::new();
        if lower.contains("javascript:") {
            found.push("javascript: link".to_string());
        }
        for (at, _) in lower.match_indices('<') {
            let tag = &lower[at + 1..];
            if !tag.starts_with(|c: char| c.is_ascii_alphabetic()) {
                continue;
            }
            let tag = &tag[..tag.find('>').unwrap_or(tag.len())];
            if tag.starts_with("script") && !tag.contains("src=") {
                found.push(format!("<{tag}>"));
            }
            for word in tag.split(|c: char| c.is_whitespace() || c == '}') {
                if let Some((name, _)) = word.split_once('=')
                    && name.len() > 2
                    && name.starts_with("on")
                    && name.chars().all(|c| c.is_ascii_alphabetic())
                {
                    found.push(format!("{name}= in <{tag}>"));
                }
            }
        }
        found
    }
    assert_eq!(
        inline_scripts(r#"<select onchange="x()"><script>y()</script><a href="javascript:z">"#)
            .len(),
        3
    );
    assert!(inline_scripts(r#"<script src="/static/a.js" defer></script><p>on=1</p>"#).is_empty());
    let mut bad = Vec::new();
    let mut dirs = vec![std::path::PathBuf::from("templates")];
    while let Some(dir) = dirs.pop() {
        for entry in std::fs::read_dir(&dir).unwrap() {
            let path = entry.unwrap().path();
            if path.is_dir() {
                dirs.push(path);
            } else if path.extension().is_some_and(|e| e == "html") {
                let html = std::fs::read_to_string(&path).unwrap();
                bad.extend(
                    inline_scripts(&html)
                        .into_iter()
                        .map(|what| format!("{}: {what}", path.display())),
                );
            }
        }
    }
    assert!(bad.is_empty(), "inline script the CSP blocks: {bad:#?}");
    let script_src: Vec<&str> = crate::admin_auth::ADMIN_CSP
        .split(';')
        .map(str::trim)
        .filter(|directive| directive.starts_with("script-src"))
        .collect();
    assert_eq!(script_src, ["script-src 'self'"]);
}
