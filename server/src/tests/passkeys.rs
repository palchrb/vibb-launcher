//! Design 74 step 1: passkeys for parents - add behind the re-auth, the sign-in that is both factors, the re-auth by
//! passkey, removal, the ways back deleting them, the challenge store's bindings and the counter, all through the
//! real router with a `FakeAuthenticator` (ring-generated keys signing real authenticatorData).

use axum::body::Body;
use axum::http::{Method, Request, StatusCode, header};

use super::{LOOPBACK_PEER, TestApp, TestResponse};
use crate::webauthn::fake::FakeAuthenticator;
use crate::webauthn::{Refused, b64url};

pub(super) const HOST: &str = "handy.example.com";
pub(super) const ORIGIN: &str = "https://handy.example.com";
/// The password `TestApp::admin_cookie` gives every account it makes.
pub(super) const PASSWORD: &str = "correct horse battery staple";

/// A browser's request at `host`: Host, Sec-Fetch-Site, the cookie, a urlencoded body for a POST.
pub(super) async fn send(
    app: &TestApp,
    method: Method,
    uri: &str,
    host: &str,
    cookie: Option<&str>,
    fields: &[(&str, &str)],
) -> TestResponse {
    let mut builder = Request::builder()
        .method(method.clone())
        .uri(uri)
        .header(header::HOST, host)
        .header("sec-fetch-site", "same-origin");
    if let Some(cookie) = cookie {
        builder = builder.header(header::COOKIE, cookie);
    }
    let body = if method == Method::POST {
        builder = builder.header(header::CONTENT_TYPE, "application/x-www-form-urlencoded");
        Body::from(
            form_urlencoded::Serializer::new(String::new())
                .extend_pairs(fields)
                .finish(),
        )
    } else {
        Body::empty()
    };
    app.send_via(&app.router, LOOPBACK_PEER, builder.body(body).unwrap())
        .await
}

pub(super) fn challenge_of(res: &TestResponse) -> Vec<u8> {
    assert_eq!(res.status, StatusCode::OK, "{}", res.text());
    let json = res.json();
    base64::Engine::decode(
        &base64::engine::general_purpose::URL_SAFE_NO_PAD,
        json["challenge"].as_str().unwrap(),
    )
    .unwrap()
}

fn unb64(text: &str) -> Vec<u8> {
    base64::Engine::decode(&base64::engine::general_purpose::URL_SAFE_NO_PAD, text).unwrap()
}

/// Adds a passkey for the signed-in parent of `cookie` through the real forms: the options behind the password,
/// then the fake's registration. Returns the new row's id.
pub(super) async fn add_passkey(
    app: &TestApp,
    cookie: &str,
    fake: &mut FakeAuthenticator,
    name: &str,
) -> i64 {
    add_passkey_at(
        app,
        cookie,
        fake,
        name,
        HOST,
        ORIGIN,
        &[("current_password", PASSWORD)],
    )
    .await
}

/// [add_passkey] at `host`, made at `origin`, with the re-auth `proof` (the password or an `assertion`).
async fn add_passkey_at(
    app: &TestApp,
    cookie: &str,
    fake: &mut FakeAuthenticator,
    name: &str,
    host: &str,
    origin: &str,
    proof: &[(&str, &str)],
) -> i64 {
    let mut fields = vec![("name", name)];
    fields.extend_from_slice(proof);
    let options = send(
        app,
        Method::POST,
        "/account/passkeys/options",
        host,
        Some(cookie),
        &fields,
    )
    .await;
    let challenge = challenge_of(&options);
    let json = options.json();
    assert_eq!(
        json["authenticatorSelection"]["userVerification"],
        "required"
    );
    assert_eq!(json["authenticatorSelection"]["residentKey"], "required");
    assert_eq!(json["attestation"], "none");
    let rp = json["rp"]["id"].as_str().unwrap().to_string();
    fake.user_handle = unb64(json["user"]["id"].as_str().unwrap());
    assert_eq!(fake.user_handle.len(), 16);
    let credential = fake.register(&challenge, origin, &rp);
    let res = send(
        app,
        Method::POST,
        "/account/passkeys",
        host,
        Some(cookie),
        &[("credential", &credential)],
    )
    .await;
    let location = res
        .location()
        .unwrap_or_else(|| panic!("{}: {}", res.status, res.text()))
        .to_string();
    let id = location
        .strip_prefix("/account?done=passkey_added#passkey-")
        .unwrap_or_else(|| panic!("{location}"));
    id.parse().unwrap()
}

/// A re-auth assertion by `fake` for the form at `route` (D1.6), without the user handle.
pub(super) async fn reauth_assertion(
    app: &TestApp,
    cookie: &str,
    fake: &mut FakeAuthenticator,
    route: &str,
) -> String {
    let options = send(
        app,
        Method::POST,
        "/account/passkeys/reauth-options",
        HOST,
        Some(cookie),
        &[("route", route)],
    )
    .await;
    fake.assert(&challenge_of(&options), ORIGIN, HOST, false)
}

/// A passkey sign-in at `host` with the fake's assertion (made at `origin`): the options, then the POST. Returns
/// the POST's answer and the cookie it was sent with.
pub(super) async fn sign_in_at(
    app: &TestApp,
    fake: &mut FakeAuthenticator,
    host: &str,
    origin: &str,
    with_handle: bool,
) -> (TestResponse, String) {
    let options = send(app, Method::POST, "/auth/passkey/options", host, None, &[]).await;
    let challenge = challenge_of(&options);
    let json = options.json();
    assert_eq!(json["allowCredentials"], serde_json::json!([]));
    assert_eq!(json["userVerification"], "required");
    let cookie = super::session_cookie(&options).expect("the options set a session");
    let rp = json["rpId"].as_str().unwrap().to_string();
    let assertion = fake.assert(&challenge, origin, &rp, with_handle);
    let res = send(
        app,
        Method::POST,
        "/auth/passkey",
        host,
        Some(&cookie),
        &[("assertion", &assertion)],
    )
    .await;
    (res, cookie)
}

pub(super) async fn sign_in(app: &TestApp, fake: &mut FakeAuthenticator) -> (TestResponse, String) {
    sign_in_at(app, fake, HOST, ORIGIN, true).await
}

/// The signed-in page `/devices` answers 200 with the cookie a sign-in left.
pub(super) async fn signed_in(app: &TestApp, res: &TestResponse, cookie: &str) -> bool {
    let cookie = super::session_cookie(res).unwrap_or_else(|| cookie.to_string());
    send(app, Method::GET, "/devices", HOST, Some(&cookie), &[])
        .await
        .status
        == StatusCode::OK
}

pub(super) async fn scalar(app: &TestApp, sql: &str) -> i64 {
    sqlx::query_scalar(sql).fetch_one(&app.db).await.unwrap()
}

pub(super) async fn count_passkeys(app: &TestApp, username: &str) -> i64 {
    sqlx::query_scalar(
        "SELECT COUNT(*) FROM admin_passkeys p JOIN admin_users a ON a.id = p.admin_id WHERE a.username = ?",
    )
    .bind(username)
    .fetch_one(&app.db)
    .await
    .unwrap()
}

async fn a_subscription(app: &TestApp, username: &str, n: i64) {
    sqlx::query(
        "INSERT INTO push_subscriptions (admin_id, endpoint, p256dh, auth, origin, label, vapid_fp, state) \
         SELECT id, ?, x'00', x'00', 'https://pwa.example', 'Test', 'fp', 'active' FROM admin_users WHERE username = ?",
    )
    .bind(format!("https://push.example/{n}"))
    .bind(username)
    .execute(&app.db)
    .await
    .unwrap();
}

/// D1.2: the options need the re-auth (a wrong password counts in `reauth_wrong`); the add is logged, pushed to
/// every parent and put in the inbox; the row stores the COSE key, BE/BS and the transports.
#[tokio::test]
async fn adding_needs_the_reauth_and_tells_every_parent() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    app.admin_cookie_as("pappa", "Pappa").await;
    a_subscription(&app, "parent", 1).await;
    a_subscription(&app, "pappa", 2).await;

    let wrong = send(
        &app,
        Method::POST,
        "/account/passkeys/options",
        HOST,
        Some(&cookie),
        &[("name", "iPhone"), ("current_password", "not it")],
    )
    .await;
    assert_eq!(wrong.status, StatusCode::BAD_REQUEST);
    assert_eq!(wrong.json()["field"], "password");
    assert_eq!(
        scalar(
            &app,
            "SELECT reauth_wrong FROM admin_users WHERE username = 'parent'"
        )
        .await,
        1
    );

    let mut fake = FakeAuthenticator::es256(b"");
    let id = add_passkey(&app, &cookie, &mut fake, "iPhone").await;
    let (name, rp, be, bs, transports): (String, String, bool, bool, Option<String>) = sqlx::query_as(
        "SELECT name, rp_id, backup_eligible, backed_up, transports FROM admin_passkeys WHERE id = ?",
    )
    .bind(id)
    .fetch_one(&app.db)
    .await
    .unwrap();
    assert_eq!(
        (name.as_str(), rp.as_str(), be, bs),
        ("iPhone", HOST, true, true)
    );
    assert_eq!(transports.as_deref(), Some(r#"["internal","hybrid"]"#));
    assert_eq!(
        scalar(
            &app,
            "SELECT COUNT(*) FROM security_events WHERE event_type = 'passkey_added'"
        )
        .await,
        1
    );
    // One inbox event, a push queued for both parents' subscriptions.
    let event: i64 = scalar(
        &app,
        "SELECT id FROM parent_events WHERE kind = 'passkey_added' AND device_id IS NULL",
    )
    .await;
    assert_eq!(
        scalar(
            &app,
            &format!("SELECT COUNT(*) FROM push_deliveries WHERE event_id = {event}")
        )
        .await,
        2
    );
    let inbox = send(
        &app,
        Method::GET,
        "/notifications",
        HOST,
        Some(&cookie),
        &[],
    )
    .await;
    assert!(
        inbox.text().contains("parent added a passkey “iPhone”"),
        "{}",
        inbox.text()
    );

    // The page: the row, its Remove's confirm, the group's flash; the Parents row counts it.
    let page = send(
        &app,
        Method::GET,
        "/account?done=passkey_added",
        HOST,
        Some(&cookie),
        &[],
    )
    .await;
    let html = page.text();
    assert!(html.contains(&format!("id=\"passkey-{id}\"")), "{html}");
    assert!(html.contains("Passkey added"));
    assert!(html.contains("parent · You · 1 passkey"), "{html}");
    super::settings_pages::check_forms("/account", &html);
    // Removing it asks for the password again: the re-auth's passkey button now shows (hidden until JS).
    assert!(html.contains("data-passkey-reauth"));
}

/// D1.4/D1.5: a passkey sign-in goes straight in (no TOTP page), logged "passkey · <name>", the use stored; the
/// signed-out options call is taken back.
#[tokio::test]
async fn a_passkey_sign_in_is_both_factors() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let mut fake = FakeAuthenticator::ed25519(b"");
    add_passkey(&app, &cookie, &mut fake, "Mac").await;

    // Two wrong codes (someone with the password) before it: the sign-in says so, as after a 2FA sign-in.
    sqlx::query("UPDATE admin_users SET failed_login_attempts = 2 WHERE username = 'parent'")
        .execute(&app.db)
        .await
        .unwrap();
    let (res, pre) = sign_in(&app, &mut fake).await;
    assert_eq!(res.location(), Some("/"), "{}", res.text());
    let account = send(
        &app,
        Method::GET,
        "/account",
        HOST,
        super::session_cookie(&res).as_deref(),
        &[],
    )
    .await;
    assert!(
        account.text().contains("id=\"login-warning\""),
        "{}",
        account.text()
    );
    assert_eq!(
        scalar(&app, "SELECT failed_login_attempts FROM admin_users").await,
        0
    );
    let after = super::session_cookie(&res).expect("a new session id");
    assert_ne!(after, pre, "the session id is cycled");
    assert!(signed_in(&app, &res, &pre).await);
    let (detail, ip): (Option<String>, Option<String>) = sqlx::query_as(
        "SELECT params, ip_address FROM security_events WHERE event_type = 'login_success' ORDER BY id DESC LIMIT 1",
    )
    .fetch_one(&app.db)
    .await
    .unwrap();
    assert!(detail.unwrap().contains("security.detail.login.passkey"));
    assert_eq!(ip.as_deref(), Some("127.0.0.1"));
    assert_eq!(
        scalar(
            &app,
            "SELECT COUNT(*) FROM security_events WHERE event_type = 'passkey_started'"
        )
        .await,
        0,
        "the options call is taken back"
    );
    assert_eq!(
        scalar(&app, "SELECT COUNT(*) FROM admin_passkeys WHERE last_used_at IS NOT NULL AND last_used_from = '127.0.0.1'").await,
        1
    );
}

/// D1.4: the account must be ready by the one predicate - a removed, invited (forced change) or expired account is
/// refused with the same red line; another parent's passkey can't sign in as you.
#[tokio::test]
async fn only_a_ready_account_signs_in_and_only_with_its_own_passkey() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let mut fake = FakeAuthenticator::es256(b"");
    add_passkey(&app, &cookie, &mut fake, "iPhone").await;
    for (set, unset) in [
        ("must_change_password = 1", "must_change_password = 0"),
        (
            "invite_expires_at = datetime('now', '+1 hour')",
            "invite_expires_at = NULL",
        ),
        (
            "invite_expires_at = datetime('now', '-1 hour')",
            "invite_expires_at = NULL",
        ),
        ("removed_at = datetime('now')", "removed_at = NULL"),
        ("totp_enabled = 0", "totp_enabled = 1"),
    ] {
        sqlx::query(&format!(
            "UPDATE admin_users SET {set} WHERE username = 'parent'"
        ))
        .execute(&app.db)
        .await
        .unwrap();
        let (res, _) = sign_in(&app, &mut fake).await;
        assert_eq!(res.status, StatusCode::OK, "{set}");
        assert!(
            res.text()
                .contains("The passkey didn&#39;t work. Try again, or sign in with your password."),
            "{set}"
        );
        sqlx::query(&format!(
            "UPDATE admin_users SET {unset} WHERE username = 'parent'"
        ))
        .execute(&app.db)
        .await
        .unwrap();
    }
    let (res, _) = sign_in(&app, &mut fake).await;
    assert_eq!(res.location(), Some("/"));

    // Pappa's user handle on Mamma's passkey: refused.
    let pappa = app.admin_cookie_as("pappa", "Pappa").await;
    let mut other = FakeAuthenticator::es256(b"");
    add_passkey(&app, &pappa, &mut other, "Pixel").await;
    fake.user_handle = other.user_handle.clone();
    let (res, _) = sign_in(&app, &mut fake).await;
    assert_eq!(res.status, StatusCode::OK);
    assert!(res.text().contains("The passkey didn&#39;t work"));
    // Without a user handle (required here, allowCredentials was empty): refused.
    let (res, _) = sign_in_at(&app, &mut other, HOST, ORIGIN, false).await;
    assert!(res.text().contains("The passkey didn&#39;t work"));
    let codes: Vec<String> = sqlx::query_scalar(
        "SELECT detail FROM security_events WHERE event_type = 'passkey_refused' ORDER BY id",
    )
    .fetch_all(&app.db)
    .await
    .unwrap();
    assert!(
        codes.iter().any(|c| c == Refused::NotReady.code()),
        "{codes:?}"
    );
    assert!(
        codes.iter().any(|c| c == Refused::UserHandle.code()),
        "{codes:?}"
    );
}

/// D1.6: a re-auth by passkey - `allowCredentials` this parent's, a null user handle passes, a wrong one fails and
/// counts in `reauth_wrong`; three bad ones sign out.
#[tokio::test]
async fn a_reauth_by_passkey() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let mut fake = FakeAuthenticator::es256(b"");
    add_passkey(&app, &cookie, &mut fake, "iPhone").await;
    let mut second = FakeAuthenticator::ed25519(b"");
    let second_id = add_passkey(&app, &cookie, &mut second, "Key").await;
    let route = format!("/account/passkeys/{second_id}/remove");

    // A route that asks for no password gets no challenge.
    let res = send(
        &app,
        Method::POST,
        "/account/passkeys/reauth-options",
        HOST,
        Some(&cookie),
        &[("route", "/account/name")],
    )
    .await;
    assert_eq!(res.status, StatusCode::BAD_REQUEST);

    let options = send(
        &app,
        Method::POST,
        "/account/passkeys/reauth-options",
        HOST,
        Some(&cookie),
        &[("route", &route)],
    )
    .await;
    let challenge = challenge_of(&options);
    let allowed = options.json()["allowCredentials"].as_array().unwrap().len();
    assert_eq!(allowed, 2);
    // A wrong user handle: refused, counted.
    fake.user_handle = vec![9; 16];
    let assertion = fake.assert(&challenge, ORIGIN, HOST, true);
    let res = send(
        &app,
        Method::POST,
        &route,
        HOST,
        Some(&cookie),
        &[("assertion", &assertion)],
    )
    .await;
    assert_eq!(res.status, StatusCode::BAD_REQUEST, "{}", res.text());
    assert!(
        res.text()
            .contains("The passkey didn&#39;t work. Nothing was changed.")
    );
    assert_eq!(
        scalar(
            &app,
            "SELECT reauth_wrong FROM admin_users WHERE username = 'parent'"
        )
        .await,
        1
    );

    // No user handle (allowCredentials was set): passes, and the passkey is removed.
    let options = send(
        &app,
        Method::POST,
        "/account/passkeys/reauth-options",
        HOST,
        Some(&cookie),
        &[("route", &route)],
    )
    .await;
    let challenge = challenge_of(&options);
    let assertion = fake.assert(&challenge, ORIGIN, HOST, false);
    let res = send(
        &app,
        Method::POST,
        &route,
        HOST,
        Some(&cookie),
        &[("assertion", &assertion)],
    )
    .await;
    assert_eq!(
        res.location(),
        Some("/account?done=passkey_removed#passkeys"),
        "{}",
        res.text()
    );
    assert_eq!(count_passkeys(&app, "parent").await, 1);
}

/// D1.6: three bad passkey re-auths in a row end every session, as three wrong passwords.
#[tokio::test]
async fn three_bad_passkey_reauths_sign_out() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let mut fake = FakeAuthenticator::es256(b"");
    add_passkey(&app, &cookie, &mut fake, "iPhone").await;
    let stranger = FakeAuthenticator::es256(b"");
    let mut last = None;
    for _ in 0..3 {
        let options = send(
            &app,
            Method::POST,
            "/account/passkeys/reauth-options",
            HOST,
            Some(&cookie),
            &[("route", "/account/reset-2fa")],
        )
        .await;
        let challenge = challenge_of(&options);
        // A signature by another key under the passkey's id.
        let auth = fake.auth_data(HOST, false, None);
        let client = crate::webauthn::fake::client_json("webauthn.get", &challenge, ORIGIN, false);
        let mut json: serde_json::Value =
            serde_json::from_str(&stranger.assertion_json(&auth, &client, None)).unwrap();
        json["id"] = serde_json::json!(b64url(&fake.credential_id));
        json["rawId"] = json["id"].clone();
        last = Some(
            send(
                &app,
                Method::POST,
                "/account/reset-2fa",
                HOST,
                Some(&cookie),
                &[("assertion", &json.to_string())],
            )
            .await,
        );
    }
    assert_eq!(last.unwrap().location(), Some("/login"));
    assert_eq!(
        send(&app, Method::GET, "/devices", HOST, Some(&cookie), &[])
            .await
            .location(),
        Some("/login")
    );
    assert_eq!(
        scalar(
            &app,
            "SELECT COUNT(*) FROM security_events WHERE event_type = 'reauth_signed_out'"
        )
        .await,
        1
    );
}

/// D1.3: removing a passkey ends the other sessions (this one keeps going), and every device's notifications.
#[tokio::test]
async fn removing_a_passkey_ends_the_other_sessions() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let other = app.admin_cookie().await;
    a_subscription(&app, "parent", 1).await;
    let mut fake = FakeAuthenticator::es256(b"");
    let id = add_passkey(&app, &cookie, &mut fake, "Lost phone").await;
    let res = send(
        &app,
        Method::POST,
        &format!("/account/passkeys/{id}/remove"),
        HOST,
        Some(&cookie),
        &[("current_password", PASSWORD)],
    )
    .await;
    assert_eq!(
        res.location(),
        Some("/account?done=passkey_removed#passkeys")
    );
    app.assert_lands(&res, &cookie).await;
    assert_eq!(
        send(&app, Method::GET, "/devices", HOST, Some(&cookie), &[])
            .await
            .status,
        StatusCode::OK
    );
    assert_eq!(
        send(&app, Method::GET, "/devices", HOST, Some(&other), &[])
            .await
            .location(),
        Some("/login")
    );
    assert_eq!(count_passkeys(&app, "parent").await, 0);
    assert_eq!(
        scalar(&app, "SELECT COUNT(*) FROM push_subscriptions").await,
        0
    );
    // The passkey no longer signs in.
    let (res, _) = sign_in(&app, &mut fake).await;
    assert!(res.text().contains("The passkey didn&#39;t work"));
    // A wrong password removes nothing and says so under the row's field.
    let mut again = FakeAuthenticator::es256(b"");
    let id = add_passkey(&app, &cookie, &mut again, "Mac").await;
    let res = send(
        &app,
        Method::POST,
        &format!("/account/passkeys/{id}/remove"),
        HOST,
        Some(&cookie),
        &[("current_password", "nope")],
    )
    .await;
    assert_eq!(res.status, StatusCode::BAD_REQUEST);
    assert!(
        res.text()
            .contains(&format!("id=\"passkey-remove-error-{id}\""))
    );
    assert_eq!(count_passkeys(&app, "parent").await, 1);
}

/// D1.3b: another parent's Reset…, a soft Remove and the shell's `reset-2fa` delete the account's passkeys; Reset's
/// confirm says so.
#[tokio::test]
async fn every_way_back_deletes_the_passkeys() {
    let app = TestApp::new().await;
    let mamma = app.admin_cookie().await;
    let pappa = app.admin_cookie_as("pappa", "Pappa").await;
    let mut fake = FakeAuthenticator::es256(b"");
    add_passkey(&app, &pappa, &mut fake, "Pixel").await;
    let pappa_id: i64 = scalar(&app, "SELECT id FROM admin_users WHERE username = 'pappa'").await;
    let page = send(&app, Method::GET, "/account", HOST, Some(&mamma), &[])
        .await
        .text();
    assert!(
        page.contains("Pappa&#39;s passkeys are removed too."),
        "{page}"
    );
    assert!(page.contains(" · 1 passkey</span>"), "{page}");

    let res = send(
        &app,
        Method::POST,
        &format!("/account/parents/{pappa_id}/reset"),
        HOST,
        Some(&mamma),
        &[("current_password", PASSWORD)],
    )
    .await;
    assert_eq!(res.status, StatusCode::OK, "{}", res.text());
    assert_eq!(count_passkeys(&app, "pappa").await, 0);
    let (res, _) = sign_in(&app, &mut fake).await;
    assert!(res.text().contains("The passkey didn&#39;t work"));

    // A soft Remove (pappa onboarded again first, as the test accounts are).
    sqlx::query(
        "UPDATE admin_users SET must_change_password = 0, totp_enabled = 1, invite_expires_at = NULL, \
         totp_secret = 'JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP', password_hash = ? WHERE username = 'pappa'",
    )
    .bind(crate::security::hash_password(PASSWORD))
    .execute(&app.db)
    .await
    .unwrap();
    let pappa = app.admin_cookie_as("pappa", "Pappa").await;
    let mut fake = FakeAuthenticator::es256(b"");
    add_passkey(&app, &pappa, &mut fake, "Pixel").await;
    let res = send(
        &app,
        Method::POST,
        &format!("/account/parents/{pappa_id}/remove"),
        HOST,
        Some(&mamma),
        &[("current_password", PASSWORD)],
    )
    .await;
    assert_eq!(res.location(), Some("/account?parents=removed#parents"));
    assert_eq!(count_passkeys(&app, "pappa").await, 0);

    // The shell's way back (and its clearer alias's: the same function).
    let mut fake = FakeAuthenticator::es256(b"");
    add_passkey(&app, &mamma, &mut fake, "iPhone").await;
    let said = crate::admin_auth::reset_2fa(&app.db, "parent", &app.state.data_dir)
        .await
        .unwrap();
    assert!(said.contains("its passkey is deleted"), "{said}");
    assert_eq!(count_passkeys(&app, "parent").await, 0);
}

/// D1.4: each signed-out options call and each bad assertion counts in the per-IP ban; a banned key's options are
/// 429.
#[tokio::test]
async fn options_and_bad_assertions_count_in_the_ban() {
    let app = TestApp::new().await;
    for _ in 0..crate::security::MAX_FAILED_PER_IP {
        let res = send(&app, Method::POST, "/auth/passkey/options", HOST, None, &[]).await;
        assert_eq!(res.status, StatusCode::OK);
    }
    let res = send(&app, Method::POST, "/auth/passkey/options", HOST, None, &[]).await;
    assert_eq!(res.status, StatusCode::TOO_MANY_REQUESTS);
    // The counting rows never show in the Security Log.
    sqlx::query("DELETE FROM banned_ips")
        .execute(&app.db)
        .await
        .unwrap();
    sqlx::query("DELETE FROM security_events")
        .execute(&app.db)
        .await
        .unwrap();
    let cookie = app.admin_cookie().await;
    let log = send(&app, Method::GET, "/security", HOST, Some(&cookie), &[])
        .await
        .text();
    assert!(!log.contains("Passkey sign-in started"));

    // Bad assertions: a garbled one, posted again and again.
    sqlx::query("DELETE FROM security_events")
        .execute(&app.db)
        .await
        .unwrap();
    for _ in 0..crate::security::MAX_FAILED_PER_IP {
        send(
            &app,
            Method::POST,
            "/auth/passkey",
            HOST,
            None,
            &[("assertion", "{}")],
        )
        .await;
    }
    let res = send(
        &app,
        Method::POST,
        "/auth/passkey",
        HOST,
        None,
        &[("assertion", "{}")],
    )
    .await;
    assert_eq!(res.status, StatusCode::TOO_MANY_REQUESTS);
}

/// D1.7: two parallel sign-ins with one assertion - one wins.
#[tokio::test]
async fn one_challenge_is_taken_once() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let mut fake = FakeAuthenticator::es256(b"");
    add_passkey(&app, &cookie, &mut fake, "iPhone").await;
    let options = send(&app, Method::POST, "/auth/passkey/options", HOST, None, &[]).await;
    let challenge = challenge_of(&options);
    let pre = super::session_cookie(&options).unwrap();
    let assertion = fake.assert(&challenge, ORIGIN, HOST, true);
    let fields = [("assertion", assertion.as_str())];
    let (a, b) = tokio::join!(
        send(
            &app,
            Method::POST,
            "/auth/passkey",
            HOST,
            Some(&pre),
            &fields
        ),
        send(
            &app,
            Method::POST,
            "/auth/passkey",
            HOST,
            Some(&pre),
            &fields
        ),
    );
    let wins = [&a, &b]
        .iter()
        .filter(|r| r.location() == Some("/"))
        .count();
    assert_eq!(wins, 1, "{} / {}", a.status, b.status);
}

/// D5.3: two parallel uses of one counter value - one wins, the other is `passkey_cloned`.
#[tokio::test]
async fn one_counter_value_is_used_once() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let mut fake = FakeAuthenticator::es256(b"");
    fake.counter = 4;
    add_passkey(&app, &cookie, &mut fake, "YubiKey").await;
    let mut assertions = Vec::new();
    for _ in 0..2 {
        let options = send(&app, Method::POST, "/auth/passkey/options", HOST, None, &[]).await;
        let challenge = challenge_of(&options);
        let pre = super::session_cookie(&options).unwrap();
        fake.counter = 4;
        assertions.push((pre, fake.assert(&challenge, ORIGIN, HOST, true)));
    }
    let first = [("assertion", assertions[0].1.as_str())];
    let second = [("assertion", assertions[1].1.as_str())];
    let (a, b) = tokio::join!(
        send(
            &app,
            Method::POST,
            "/auth/passkey",
            HOST,
            Some(&assertions[0].0),
            &first
        ),
        send(
            &app,
            Method::POST,
            "/auth/passkey",
            HOST,
            Some(&assertions[1].0),
            &second
        ),
    );
    let wins = [&a, &b]
        .iter()
        .filter(|r| r.location() == Some("/"))
        .count();
    assert_eq!(wins, 1);
    assert_eq!(
        scalar(&app, "SELECT sign_count FROM admin_passkeys").await,
        5
    );
    assert_eq!(
        scalar(
            &app,
            "SELECT COUNT(*) FROM security_events WHERE event_type = 'passkey_cloned'"
        )
        .await,
        1
    );
}

/// D1.7: a re-auth challenge from another session, route or epoch is refused.
#[tokio::test]
async fn a_challenge_works_only_where_it_was_bound() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let other = app.admin_cookie().await;
    let mut fake = FakeAuthenticator::es256(b"");
    add_passkey(&app, &cookie, &mut fake, "iPhone").await;
    let challenge = |cookie: String, route: &'static str| {
        let app = &app;
        async move {
            challenge_of(
                &send(
                    app,
                    Method::POST,
                    "/account/passkeys/reauth-options",
                    HOST,
                    Some(&cookie),
                    &[("route", route)],
                )
                .await,
            )
        }
    };
    // Another session.
    let c = challenge(other.clone(), "/account/reset-2fa").await;
    let assertion = fake.assert(&c, ORIGIN, HOST, false);
    let res = send(
        &app,
        Method::POST,
        "/account/reset-2fa",
        HOST,
        Some(&cookie),
        &[("assertion", &assertion)],
    )
    .await;
    assert_eq!(res.status, StatusCode::BAD_REQUEST, "another session's");
    // Another route.
    let c = challenge(cookie.clone(), "/account/password").await;
    let assertion = fake.assert(&c, ORIGIN, HOST, false);
    let res = send(
        &app,
        Method::POST,
        "/account/reset-2fa",
        HOST,
        Some(&cookie),
        &[("assertion", &assertion)],
    )
    .await;
    assert_eq!(res.status, StatusCode::BAD_REQUEST, "another route's");
    // Another epoch: a password change in this session moves it.
    let c = challenge(cookie.clone(), "/account/reset-2fa").await;
    let res = send(
        &app,
        Method::POST,
        "/account/password",
        HOST,
        Some(&cookie),
        &[
            ("current_password", PASSWORD),
            ("new_password", "another long password"),
            ("confirm_password", "another long password"),
        ],
    )
    .await;
    assert_eq!(res.location(), Some("/account?changed=password#password"));
    let assertion = fake.assert(&c, ORIGIN, HOST, false);
    let res = send(
        &app,
        Method::POST,
        "/account/reset-2fa",
        HOST,
        Some(&cookie),
        &[("assertion", &assertion)],
    )
    .await;
    assert_eq!(res.status, StatusCode::BAD_REQUEST, "another epoch's");
    let codes: Vec<String> = sqlx::query_scalar(
        "SELECT detail FROM security_events WHERE event_type = 'passkey_refused'",
    )
    .fetch_all(&app.db)
    .await
    .unwrap();
    assert_eq!(codes, ["challenge", "challenge", "challenge"]);
    // And the right one passes: the 2FA reset goes on to its setup.
    let c = challenge(cookie.clone(), "/account/reset-2fa").await;
    let assertion = fake.assert(&c, ORIGIN, HOST, false);
    let res = send(
        &app,
        Method::POST,
        "/account/reset-2fa",
        HOST,
        Some(&cookie),
        &[("assertion", &assertion)],
    )
    .await;
    assert_eq!(res.location(), Some("/auth/setup-2fa"));
}

/// D4.1/D4.2: a forged Host proposes another RP ID but can't use a credential made for this one, nor pass this
/// origin; a sibling subdomain's origin is refused.
#[tokio::test]
async fn a_forged_host_cant_use_a_credential_of_another_rp_id() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let mut fake = FakeAuthenticator::es256(b"");
    add_passkey(&app, &cookie, &mut fake, "iPhone").await;
    // At evil.example.org: the RP ID offered is the host's; the passkey's is handy.example.com.
    let (res, _) = sign_in_at(
        &app,
        &mut fake,
        "evil.example.org",
        "https://evil.example.org",
        true,
    )
    .await;
    assert!(res.text().contains("The passkey didn&#39;t work"));
    // This origin through the forged host: not this request's origin.
    let (res, _) = sign_in_at(&app, &mut fake, "evil.example.org", ORIGIN, true).await;
    assert!(res.text().contains("The passkey didn&#39;t work"));
    // A sibling under a shared RP ID relays nothing (D4.2).
    let (res, _) = sign_in_at(&app, &mut fake, HOST, "https://wiki.example.com", true).await;
    assert!(res.text().contains("The passkey didn&#39;t work"));
    let (res, _) = sign_in(&app, &mut fake).await;
    assert_eq!(res.location(), Some("/"));
}

/// D4.3/D4.1: the button is on the sign-in page at a host that can have passkeys, with none made there - never at
/// an IP address; the username field offers passkeys in autofill.
#[tokio::test]
async fn the_sign_in_page_offers_passkeys_at_a_named_host() {
    let app = TestApp::new().await;
    let page = send(&app, Method::GET, "/login", HOST, None, &[])
        .await
        .text();
    assert!(page.contains("data-passkey-login"));
    assert!(page.contains("autocomplete=\"username webauthn\""));
    assert!(page.contains("/static/passkey.js"));
    let page = send(&app, Method::GET, "/login", "192.168.1.10:3100", None, &[])
        .await
        .text();
    assert!(!page.contains("data-passkey-login"));
    let res = send(
        &app,
        Method::POST,
        "/auth/passkey/options",
        "192.168.1.10:3100",
        None,
        &[],
    )
    .await;
    assert_eq!(res.status, StatusCode::NOT_FOUND);
    // Signed in at an IP address: Account says passkeys don't work here, and offers no add.
    let cookie = app.admin_cookie().await;
    let page = send(
        &app,
        Method::GET,
        "/account",
        "192.168.1.10:3100",
        Some(&cookie),
        &[],
    )
    .await
    .text();
    assert!(page.contains("Passkeys don&#39;t work at this address"));
    assert!(!page.contains("id=\"add-passkey\""));
}

/// D1.1: ten at most; a credential registered twice is refused; a registration's challenge works once.
#[tokio::test]
async fn adding_keeps_to_its_limits() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let mut fake = FakeAuthenticator::es256(b"");
    add_passkey(&app, &cookie, &mut fake, "iPhone").await;
    // The same credential again.
    let options = send(
        &app,
        Method::POST,
        "/account/passkeys/options",
        HOST,
        Some(&cookie),
        &[("name", "Again"), ("current_password", PASSWORD)],
    )
    .await;
    let credential = fake.register(&challenge_of(&options), ORIGIN, HOST);
    let res = send(
        &app,
        Method::POST,
        "/account/passkeys",
        HOST,
        Some(&cookie),
        &[("credential", &credential)],
    )
    .await;
    assert_eq!(res.status, StatusCode::BAD_REQUEST);
    assert!(res.text().contains("This passkey is added already."));
    // Its challenge is spent.
    let res = send(
        &app,
        Method::POST,
        "/account/passkeys",
        HOST,
        Some(&cookie),
        &[("credential", &credential)],
    )
    .await;
    assert!(res.text().contains("The passkey couldn&#39;t be made."));
    for i in 1..crate::passkeys::MAX_PASSKEYS {
        let mut more = FakeAuthenticator::ed25519(b"");
        add_passkey(&app, &cookie, &mut more, &format!("Key {i}")).await;
    }
    let res = send(
        &app,
        Method::POST,
        "/account/passkeys/options",
        HOST,
        Some(&cookie),
        &[("name", "Eleven"), ("current_password", PASSWORD)],
    )
    .await;
    assert_eq!(res.status, StatusCode::BAD_REQUEST);
    assert_eq!(res.json()["field"], "name");
}

/// The re-auth by passkey reaches Show Child PIN and Add Parent too (§6.2), with the button on their forms.
#[tokio::test]
async fn show_child_pin_and_add_parent_take_a_passkey() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let mut fake = FakeAuthenticator::es256(b"");
    add_passkey(&app, &cookie, &mut fake, "iPhone").await;
    let (device, _) = app.create_device("Anna").await;
    let route = format!("/devices/{device}/lock");
    let saved = send(
        &app,
        Method::POST,
        &format!("/devices/{device}/parent-pin"),
        HOST,
        Some(&cookie),
        &[("new_pin", "739146")],
    )
    .await;
    assert_eq!(saved.status, StatusCode::SEE_OTHER, "{}", saved.text());
    let saved = send(
        &app,
        Method::POST,
        &format!("/devices/{device}/kid-lock"),
        HOST,
        Some(&cookie),
        &[("new_kid_pin", "2580")],
    )
    .await;
    assert_eq!(saved.status, StatusCode::SEE_OTHER, "{}", saved.text());
    let page = send(&app, Method::GET, &route, HOST, Some(&cookie), &[])
        .await
        .text();
    assert!(page.contains("data-passkey-reauth"), "{page}");
    let options = send(
        &app,
        Method::POST,
        "/account/passkeys/reauth-options",
        HOST,
        Some(&cookie),
        &[("route", &route)],
    )
    .await;
    let assertion = fake.assert(&challenge_of(&options), ORIGIN, HOST, false);
    let res = send(
        &app,
        Method::POST,
        &route,
        HOST,
        Some(&cookie),
        &[("action", "reveal_kid_pin"), ("assertion", &assertion)],
    )
    .await;
    assert_eq!(res.status, StatusCode::OK, "{}", res.text());
    assert_eq!(
        scalar(
            &app,
            "SELECT COUNT(*) FROM security_events WHERE event_type = 'kid_pin_shown'"
        )
        .await,
        1
    );

    let options = send(
        &app,
        Method::POST,
        "/account/passkeys/reauth-options",
        HOST,
        Some(&cookie),
        &[("route", "/account/parents")],
    )
    .await;
    let assertion = fake.assert(&challenge_of(&options), ORIGIN, HOST, false);
    let res = send(
        &app,
        Method::POST,
        "/account/parents",
        HOST,
        Some(&cookie),
        &[
            ("new_username", "mormor"),
            ("name", "Mormor"),
            ("assertion", &assertion),
        ],
    )
    .await;
    assert_eq!(res.status, StatusCode::OK, "{}", res.text());
    assert_eq!(
        scalar(
            &app,
            "SELECT COUNT(*) FROM admin_users WHERE username = 'mormor'"
        )
        .await,
        1
    );
}

// ---- The fix round (qa-passkeys-1 A and B) ---------------------------------------------------------------

/// A-M1: under a shared `PASSKEY_RP_ID` no origin is built from the Host: a sibling's (here a forged Host's) is
/// refused; another name under the RP ID works only when `PASSKEY_ORIGINS` lists it.
#[tokio::test]
async fn a_shared_rp_id_takes_only_its_own_and_the_listed_origins() {
    let app = TestApp::with_env(&[
        ("PASSKEY_RP_ID", "example.com"),
        ("PASSKEY_ORIGINS", "https://handy.example.com"),
    ])
    .await;
    let cookie = app.admin_cookie().await;
    let mut fake = FakeAuthenticator::es256(b"");
    let proof = [("current_password", PASSWORD)];
    add_passkey_at(&app, &cookie, &mut fake, "iPhone", HOST, ORIGIN, &proof).await;
    assert_eq!(
        scalar(
            &app,
            "SELECT COUNT(*) FROM admin_passkeys WHERE rp_id = 'example.com'"
        )
        .await,
        1
    );
    // A sibling that isn't listed gets no passkey UI.
    let res = send(
        &app,
        Method::POST,
        "/auth/passkey/options",
        "wiki.example.com",
        None,
        &[],
    )
    .await;
    assert_eq!(res.status, StatusCode::NOT_FOUND);
    // A forged Host (a listed name, or the RP ID itself) can't make the sibling's origin this request's.
    for host in [HOST, "example.com"] {
        let (res, _) = sign_in_at(&app, &mut fake, host, "https://wiki.example.com", true).await;
        assert!(res.text().contains("The passkey didn&#39;t work"), "{host}");
    }
    let (res, _) = sign_in_at(&app, &mut fake, HOST, ORIGIN, true).await;
    assert_eq!(res.location(), Some("/"));
    // Without the list a name under the RP ID gets none; the RP ID's own host signs in.
    let app = TestApp::with_env(&[("PASSKEY_RP_ID", "example.com")]).await;
    let res = send(&app, Method::POST, "/auth/passkey/options", HOST, None, &[]).await;
    assert_eq!(res.status, StatusCode::NOT_FOUND);
    let res = send(
        &app,
        Method::POST,
        "/auth/passkey/options",
        "example.com",
        None,
        &[],
    )
    .await;
    assert_eq!(res.json()["rpId"], "example.com");
}

/// B-M2: the options call is a POST through `csrf_guard`: a cross-site one writes nothing.
#[tokio::test]
async fn a_cross_site_options_call_writes_nothing() {
    let app = TestApp::new().await;
    let request = Request::builder()
        .method(Method::POST)
        .uri("/auth/passkey/options")
        .header(header::HOST, HOST)
        .header("sec-fetch-site", "cross-site")
        .body(Body::empty())
        .unwrap();
    let res = app.send_via(&app.router, LOOPBACK_PEER, request).await;
    assert_eq!(res.status, StatusCode::FORBIDDEN);
    assert_eq!(
        scalar(
            &app,
            "SELECT COUNT(*) FROM security_events WHERE event_type = 'passkey_started'"
        )
        .await,
        0
    );
    let res = send(&app, Method::GET, "/auth/passkey/options", HOST, None, &[]).await;
    assert_eq!(res.status, StatusCode::METHOD_NOT_ALLOWED);
}

async fn counted(app: &TestApp) -> i64 {
    let sql = format!(
        "SELECT COUNT(*) FROM security_events WHERE event_type IN {} OR event_type = 'passkey_started'",
        crate::security::BAN_FAILURES
    );
    scalar(app, &sql).await
}

async fn started(app: &TestApp) -> i64 {
    scalar(
        app,
        "SELECT COUNT(*) FROM security_events WHERE event_type = 'passkey_started'",
    )
    .await
}

/// B-M3/A-L1: a session's options count once (it gets its live challenge back); a password sign-in's POST takes
/// the count back (a wrong password costs what it cost before passkeys), and a failed passkey try counts once.
#[tokio::test]
async fn the_sign_in_page_costs_nothing_once_its_ceremony_ends() {
    let app = TestApp::new().await;
    app.admin_cookie().await;
    sqlx::query("DELETE FROM security_events")
        .execute(&app.db)
        .await
        .unwrap();
    let options = send(&app, Method::POST, "/auth/passkey/options", HOST, None, &[]).await;
    let cookie = super::session_cookie(&options).unwrap();
    let again = send(
        &app,
        Method::POST,
        "/auth/passkey/options",
        HOST,
        Some(&cookie),
        &[],
    )
    .await;
    assert_eq!(
        again.json()["challenge"],
        options.json()["challenge"],
        "the live one again"
    );
    assert_eq!(started(&app).await, 1);
    // A wrong password from that page: exactly one counted row, as before passkeys.
    let res = send(
        &app,
        Method::POST,
        "/login",
        HOST,
        Some(&cookie),
        &[("username", "parent"), ("password", "nope")],
    )
    .await;
    assert_eq!(res.status, StatusCode::OK);
    assert_eq!(started(&app).await, 0);
    assert_eq!(counted(&app).await, 1);
    // A failed passkey try: its refusal alone.
    sqlx::query("DELETE FROM security_events")
        .execute(&app.db)
        .await
        .unwrap();
    let options = send(&app, Method::POST, "/auth/passkey/options", HOST, None, &[]).await;
    let cookie = super::session_cookie(&options).unwrap();
    send(
        &app,
        Method::POST,
        "/auth/passkey",
        HOST,
        Some(&cookie),
        &[("assertion", "{}")],
    )
    .await;
    assert_eq!(started(&app).await, 0);
    assert_eq!(counted(&app).await, 1);
    // A ban's words say what was counted.
    sqlx::query("DELETE FROM security_events")
        .execute(&app.db)
        .await
        .unwrap();
    for _ in 0..=crate::security::MAX_FAILED_PER_IP {
        send(&app, Method::POST, "/auth/passkey/options", HOST, None, &[]).await;
    }
    let reason: String = sqlx::query_scalar("SELECT reason FROM banned_ips")
        .fetch_one(&app.db)
        .await
        .unwrap();
    assert!(
        reason.contains("security.detail.ban.failed_and_started"),
        "{reason}"
    );
}

/// A-M2/B-L2: signed-out clients filling the sign-in pool never stop a signed-in ceremony, nor a sign-in.
#[tokio::test]
async fn a_flood_of_sign_ins_leaves_room_for_the_rest() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let mut fake = FakeAuthenticator::es256(b"");
    add_passkey(&app, &cookie, &mut fake, "iPhone").await;
    for i in 0..crate::passkeys::MAX_SIGN_IN + 100 {
        let bound = crate::passkeys::Bound {
            kind: crate::passkeys::Kind::Login,
            session: format!("flood-{i}"),
            admin: None,
            rp_id: HOST.to_string(),
            route: crate::passkeys::SIGN_IN_ROUTE.to_string(),
        };
        let client = format!("2001:db8:{i:x}::/56");
        let asker = crate::passkeys::Asker {
            client: &client,
            crowd: &client,
        };
        assert!(
            app.state
                .passkeys
                .challenges
                .issue(bound, asker, Default::default())
                .is_some()
        );
    }
    let route = "/account/reset-2fa";
    let res = send(
        &app,
        Method::POST,
        "/account/passkeys/reauth-options",
        HOST,
        Some(&cookie),
        &[("route", route)],
    )
    .await;
    assert_eq!(res.status, StatusCode::OK);
    let (res, _) = sign_in(&app, &mut fake).await;
    assert_eq!(res.location(), Some("/"));
}

/// B-M1: Add Passkey with "Confirm with passkey": the re-auth challenge is for `/account/passkeys/options`.
#[tokio::test]
async fn a_second_passkey_is_added_behind_a_passkey() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let mut first = FakeAuthenticator::es256(b"");
    add_passkey(&app, &cookie, &mut first, "iPhone").await;
    let assertion = reauth_assertion(&app, &cookie, &mut first, "/account/passkeys/options").await;
    let mut second = FakeAuthenticator::ed25519(b"");
    add_passkey_at(
        &app,
        &cookie,
        &mut second,
        "YubiKey",
        HOST,
        ORIGIN,
        &[("assertion", &assertion)],
    )
    .await;
    assert_eq!(count_passkeys(&app, "parent").await, 2);
    // A re-auth for another route doesn't do.
    let assertion = reauth_assertion(&app, &cookie, &mut first, "/account/password").await;
    let res = send(
        &app,
        Method::POST,
        "/account/passkeys/options",
        HOST,
        Some(&cookie),
        &[("name", "X"), ("assertion", &assertion)],
    )
    .await;
    assert_eq!(res.status, StatusCode::BAD_REQUEST);
    assert_eq!(res.json()["field"], "password");
}

/// §7 step 1: the password change, Reset… and Withdraw/Remove take a passkey too.
#[tokio::test]
async fn the_password_change_reset_and_remove_take_a_passkey() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let mut fake = FakeAuthenticator::es256(b"");
    add_passkey(&app, &cookie, &mut fake, "iPhone").await;
    let assertion = reauth_assertion(&app, &cookie, &mut fake, "/account/password").await;
    let res = send(
        &app,
        Method::POST,
        "/account/password",
        HOST,
        Some(&cookie),
        &[
            ("assertion", &assertion),
            ("new_password", "a brand new password"),
            ("confirm_password", "a brand new password"),
        ],
    )
    .await;
    assert_eq!(
        res.location(),
        Some("/account?changed=password#password"),
        "{}",
        res.text()
    );
    app.admin_cookie_as("pappa", "Pappa").await;
    let pappa: i64 = scalar(&app, "SELECT id FROM admin_users WHERE username = 'pappa'").await;
    let route = format!("/account/parents/{pappa}/reset");
    let assertion = reauth_assertion(&app, &cookie, &mut fake, &route).await;
    let res = send(
        &app,
        Method::POST,
        &route,
        HOST,
        Some(&cookie),
        &[("assertion", &assertion)],
    )
    .await;
    assert_eq!(res.status, StatusCode::OK, "{}", res.text());
    assert!(res.text().contains("secret-once"));
    let route = format!("/account/parents/{pappa}/remove");
    let assertion = reauth_assertion(&app, &cookie, &mut fake, &route).await;
    let res = send(
        &app,
        Method::POST,
        &route,
        HOST,
        Some(&cookie),
        &[("assertion", &assertion)],
    )
    .await;
    assert_eq!(res.location(), Some("/account?parents=removed#parents"));
}

/// Another parent's passkey id: nothing is removed.
#[tokio::test]
async fn another_parents_passkey_cant_be_removed() {
    let app = TestApp::new().await;
    let mamma = app.admin_cookie().await;
    let pappa = app.admin_cookie_as("pappa", "Pappa").await;
    let mut fake = FakeAuthenticator::es256(b"");
    let id = add_passkey(&app, &pappa, &mut fake, "Pixel").await;
    let res = send(
        &app,
        Method::POST,
        &format!("/account/passkeys/{id}/remove"),
        HOST,
        Some(&mamma),
        &[("current_password", PASSWORD)],
    )
    .await;
    assert_eq!(res.location(), Some("/account?done=passkey_gone#passkeys"));
    assert_eq!(count_passkeys(&app, "pappa").await, 1);
    // A re-auth challenge can't sign in (another kind, no account bound).
    let options = send(
        &app,
        Method::POST,
        "/account/passkeys/reauth-options",
        HOST,
        Some(&pappa),
        &[("route", "/account/reset-2fa")],
    )
    .await;
    let assertion = fake.assert(&challenge_of(&options), ORIGIN, HOST, true);
    let res = send(
        &app,
        Method::POST,
        "/auth/passkey",
        HOST,
        Some(&pappa),
        &[("assertion", &assertion)],
    )
    .await;
    assert!(res.text().contains("The passkey didn&#39;t work"));
}

/// D2.4: `reset-sign-in` runs the same command as `reset-2fa`.
#[test]
fn reset_sign_in_is_the_reset_command() {
    assert!(crate::is_reset_command("reset-2fa"));
    assert!(crate::is_reset_command("reset-sign-in"));
    assert!(!crate::is_reset_command("reset"));
}

/// The body limits (4 KiB for the sign-in, 8 KiB for a new passkey), and the largest assertion a registration
/// allows (a 512-byte id, an RSA-4096 signature) fits the sign-in's (B-L7).
#[tokio::test]
async fn the_bodies_keep_to_their_limits() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let big = "a".repeat(5000);
    let res = send(
        &app,
        Method::POST,
        "/auth/passkey",
        HOST,
        None,
        &[("assertion", &big)],
    )
    .await;
    assert_eq!(res.status, StatusCode::PAYLOAD_TOO_LARGE);
    let bigger = "a".repeat(9000);
    let res = send(
        &app,
        Method::POST,
        "/account/passkeys",
        HOST,
        Some(&cookie),
        &[("credential", &bigger)],
    )
    .await;
    assert_eq!(res.status, StatusCode::PAYLOAD_TOO_LARGE);
    let mut fake = FakeAuthenticator::es256(&[7; 16]);
    fake.credential_id = vec![0xab; crate::webauthn::MAX_CREDENTIAL_ID];
    let auth = fake.auth_data(HOST, false, None);
    let client = crate::webauthn::fake::client_json("webauthn.get", &[9; 32], ORIGIN, false);
    let mut json: serde_json::Value =
        serde_json::from_str(&fake.assertion_json(&auth, &client, Some(&[7; 16]))).unwrap();
    json["response"]["signature"] = serde_json::json!(b64url(&[0xcd; 512]));
    let body = form_urlencoded::Serializer::new(String::new())
        .append_pair("assertion", &json.to_string())
        .finish();
    assert!(body.len() < 4096, "{}", body.len());
}

/// After a passkey sign-in the session has the usual lifetime and its sign-in time (SESSION_MAX_DAYS counts from
/// it); a running TOTP lock and its doubling stay (B-L1).
#[tokio::test]
async fn a_passkey_sign_in_starts_a_normal_session_and_keeps_a_totp_lock() {
    use tower_sessions::SessionStore;
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let mut fake = FakeAuthenticator::es256(b"");
    add_passkey(&app, &cookie, &mut fake, "iPhone").await;
    sqlx::query(
        "UPDATE admin_users SET failed_login_attempts = 6, lock_count = 1, \
         locked_until = datetime('now', '+15 minutes')",
    )
    .execute(&app.db)
    .await
    .unwrap();
    let (res, _) = sign_in(&app, &mut fake).await;
    assert_eq!(res.location(), Some("/"));
    let set = res
        .headers
        .get(header::SET_COOKIE)
        .unwrap()
        .to_str()
        .unwrap()
        .to_string();
    assert!(set.contains(&format!("Max-Age={}", 30 * 86_400)), "{set}");
    let value = super::session_cookie(&res).unwrap();
    let id: tower_sessions::session::Id = value.split_once('=').unwrap().1.parse().unwrap();
    let store = tower_sessions_sqlx_store::SqliteStore::new(app.db.clone());
    let record = store
        .load(&id)
        .await
        .unwrap()
        .expect("the session's record");
    let at = record.data[crate::admin_auth::SIGNED_IN_AT_KEY]
        .as_u64()
        .unwrap();
    assert!(at.abs_diff(crate::admin_auth::now_secs()) < 60);
    assert!(!record.data.contains_key(crate::passkeys::PRE_SIGN_IN_KEY));
    let (lock_count, failed, locked): (i64, i64, bool) = sqlx::query_as(
        "SELECT lock_count, failed_login_attempts, locked_until > datetime('now') FROM admin_users",
    )
    .fetch_one(&app.db)
    .await
    .unwrap();
    assert_eq!((lock_count, failed, locked), (1, 6, true));
}

/// D2.2's predicate in `require_full_auth` and `/api/session`: an account in a one-time password's state that
/// looks onboarded is no session.
#[tokio::test]
async fn an_account_that_isnt_ready_has_no_session() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    sqlx::query("UPDATE admin_users SET invite_expires_at = datetime('now', '+1 hour')")
        .execute(&app.db)
        .await
        .unwrap();
    let res = send(&app, Method::GET, "/api/session", HOST, Some(&cookie), &[]).await;
    assert_eq!(res.status, StatusCode::UNAUTHORIZED);
    let res = send(&app, Method::GET, "/devices", HOST, Some(&cookie), &[]).await;
    assert_eq!(res.location(), Some("/login"));
}

/// B-L4: a waiting row's New One-Time Password… and Withdraw… say the passkeys go; B-L5: a wrong password at a
/// passkey's add is logged as one.
#[tokio::test]
async fn the_confirms_and_the_log_say_what_happens() {
    let app = TestApp::new().await;
    let mamma = app.admin_cookie().await;
    let pappa = app.admin_cookie_as("pappa", "Pappa").await;
    let mut fake = FakeAuthenticator::es256(b"");
    add_passkey(&app, &pappa, &mut fake, "Pixel").await;
    sqlx::query("UPDATE admin_users SET totp_enabled = 0 WHERE username = 'pappa'")
        .execute(&app.db)
        .await
        .unwrap();
    let page = send(&app, Method::GET, "/account", HOST, Some(&mamma), &[])
        .await
        .text();
    assert_eq!(
        page.matches("Pappa&#39;s passkeys are removed too.")
            .count(),
        2,
        "{page}"
    );
    send(
        &app,
        Method::POST,
        "/account/passkeys/options",
        HOST,
        Some(&mamma),
        &[("name", "X"), ("current_password", "nope")],
    )
    .await;
    assert_eq!(
        scalar(
            &app,
            "SELECT COUNT(*) FROM security_events WHERE event_type = 'passkey_change_refused'"
        )
        .await,
        1
    );
    assert_eq!(
        scalar(
            &app,
            "SELECT COUNT(*) FROM security_events WHERE event_type = 'passkey_refused'"
        )
        .await,
        0
    );
}
