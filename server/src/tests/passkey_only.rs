//! Design 74 step 2: "Kun passkey" - the switch and its guards counted in its statement, the dummy hash at /login,
//! the password and TOTP forms refused while on, the ways back clearing it, the last passkey kept, the switch-off
//! page's one write, the readiness predicate's passkey-only arm, and the sign-in page without passwords; and the
//! fixes from step 1's revision checks (the ban keyed by IPv6 /56, the pre-session's aged-out row).

use axum::body::Body;
use axum::http::{Method, Request, StatusCode, header};

use super::passkeys::{
    HOST, ORIGIN, PASSWORD, add_passkey, add_passkey_at, challenge_of, count_passkeys,
    reauth_assertion, scalar, send, sign_in,
};
use super::{LOOPBACK_PEER, TestApp, TestResponse};
use crate::webauthn::fake::FakeAuthenticator;

/// A page's text with the apostrophes as typed.
fn text(res: &TestResponse) -> String {
    res.text().replace("&#39;", "'")
}

async fn account_page(app: &TestApp, cookie: &str) -> String {
    let res = send(app, Method::GET, "/account", HOST, Some(cookie), &[]).await;
    assert_eq!(res.status, StatusCode::OK);
    text(&res)
}

/// "Kun passkey" on through the real form: a re-auth challenge for `/account/passkey-only`, the fake's assertion.
async fn switch_on(app: &TestApp, cookie: &str, fake: &mut FakeAuthenticator) -> TestResponse {
    let assertion = reauth_assertion(app, cookie, fake, "/account/passkey-only").await;
    send(
        app,
        Method::POST,
        "/account/passkey-only",
        HOST,
        Some(cookie),
        &[("assertion", &assertion)],
    )
    .await
}

async fn flag(app: &TestApp, username: &str) -> bool {
    sqlx::query_scalar("SELECT passkey_only FROM admin_users WHERE username = ?")
        .bind(username)
        .fetch_one(&app.db)
        .await
        .unwrap()
}

async fn admin(app: &TestApp, username: &str) -> crate::models::AdminUser {
    sqlx::query_as(concat!(crate::admin_select!(), " WHERE username = ?"))
        .bind(username)
        .fetch_one(&app.db)
        .await
        .unwrap()
}

/// D2.1, D2.2: the switch asks for a passkey (no password, no empty assertion), then takes the password and the TOTP
/// away in one write, ends the other sessions and keeps this one; the page then has one "Password and code: Off" row.
#[tokio::test]
async fn the_switch_needs_a_passkey_and_takes_the_password_and_code_away() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let other = app.admin_cookie().await;
    let mut fake = FakeAuthenticator::es256(b"");
    add_passkey(&app, &cookie, &mut fake, "iPhone").await;
    let epoch = scalar(
        &app,
        "SELECT session_epoch FROM admin_users WHERE username = 'parent'",
    )
    .await;

    // One passkey and no other parent: the warning ends with the shell.
    let page = account_page(&app, &cookie).await;
    assert!(page.contains("id=\"passkey-only\""), "{page}");
    assert!(page.contains(
        "We recommend two passkeys. If you lose the only one, another parent must reset you, or else the \
         server's command line is the way back."
    ));
    assert!(page.contains("action=\"/account/passkey-only#passkey-only\""));
    super::settings_pages::check_forms("/account", &page);

    // No assertion (a password instead) is refused, nothing written.
    for fields in [vec![], vec![("current_password", PASSWORD)]] {
        let res = send(
            &app,
            Method::POST,
            "/account/passkey-only",
            HOST,
            Some(&cookie),
            &fields,
        )
        .await;
        assert_eq!(res.status, StatusCode::BAD_REQUEST);
        assert!(text(&res).contains("Confirm with a passkey. Nothing was changed."));
        assert!(!flag(&app, "parent").await);
    }
    // A passkey assertion for another form's route proves nothing here (and counts as a wrong one).
    let elsewhere = reauth_assertion(&app, &cookie, &mut fake, "/account/reset-2fa").await;
    let res = send(
        &app,
        Method::POST,
        "/account/passkey-only",
        HOST,
        Some(&cookie),
        &[("assertion", &elsewhere)],
    )
    .await;
    assert_eq!(res.status, StatusCode::BAD_REQUEST);
    assert!(!flag(&app, "parent").await);

    let res = switch_on(&app, &cookie, &mut fake).await;
    assert_eq!(
        res.location(),
        Some("/account?done=passkey_only_on#passkey-only")
    );
    app.assert_lands(&res, &cookie).await;
    let row: (bool, String, Option<String>, bool, Option<i64>, i64) = sqlx::query_as(
        "SELECT passkey_only, password_hash, totp_secret, totp_enabled, totp_last_step, session_epoch \
         FROM admin_users WHERE username = 'parent'",
    )
    .fetch_one(&app.db)
    .await
    .unwrap();
    assert_eq!(
        row,
        (true, "!passkey".to_string(), None, false, None, epoch + 1)
    );
    assert_eq!(
        send(&app, Method::GET, "/devices", HOST, Some(&cookie), &[])
            .await
            .status,
        StatusCode::OK,
        "this session keeps it"
    );
    assert_eq!(
        send(&app, Method::GET, "/devices", HOST, Some(&other), &[])
            .await
            .location(),
        Some("/login")
    );
    assert_eq!(
        scalar(
            &app,
            "SELECT COUNT(*) FROM security_events WHERE event_type = 'passkey_only_on'"
        )
        .await,
        1
    );

    // The page: one row for the password and the code, the way back on, no password forms; the switch row leads to
    // the switch-off page; the re-auth fields take only the passkey.
    let page = account_page(&app, &cookie).await;
    assert!(page.contains("Passkey Only is on."), "{page}");
    assert!(page.contains("Password and code"));
    assert!(page.contains("href=\"/account/password-sign-in\""));
    assert!(!page.contains("action=\"/account/password#password\""));
    assert!(!page.contains("action=\"/account/reset-2fa#two-factor\""));
    assert!(!page.contains("action=\"/account/passkey-only#passkey-only\""));
    assert!(page.contains(
        "<input type=\"password\" id=\"add_password\" name=\"current_password\" autocomplete=\"current-password\" hidden"
    ));
    assert!(page.contains("The last passkey can't be removed while Passkey Only is on."));
    super::settings_pages::check_forms("/account", &page);

    // Switching it on again is a no-op.
    let res = switch_on(&app, &cookie, &mut fake).await;
    assert_eq!(res.location(), Some("/account#passkey-only"));
}

/// D2.1's words: without a passkey the row is disabled with its reason; two passkeys or another ready parent drop
/// the warning's parts; another parent sees "· Passkey Only" (§6.3).
#[tokio::test]
async fn the_switch_says_what_is_true_for_this_account() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let page = account_page(&app, &cookie).await;
    assert!(
        page.contains("data-row=\"passkey-only-disabled\""),
        "{page}"
    );
    assert!(page.contains("Add a passkey first."));

    let pappa = app.admin_cookie_as("pappa", "Pappa").await;
    let mut fake = FakeAuthenticator::es256(b"");
    add_passkey(&app, &cookie, &mut fake, "iPhone").await;
    let page = account_page(&app, &cookie).await;
    assert!(page.contains(
        "We recommend two passkeys. If you lose the only one, another parent must reset you.</p>"
    ));
    let mut second = FakeAuthenticator::es256(b"");
    add_passkey(&app, &cookie, &mut second, "Mac").await;
    let page = account_page(&app, &cookie).await;
    assert!(!page.contains("We recommend two passkeys"));
    assert!(page.contains("If you lose your passkeys, another parent can reset you"));

    let res = switch_on(&app, &cookie, &mut fake).await;
    assert_eq!(
        res.location(),
        Some("/account?done=passkey_only_on#passkey-only")
    );
    let page = account_page(&app, &pappa).await;
    assert!(
        page.contains(" · 2 passkeys · Passkey Only</span>"),
        "{page}"
    );
}

/// D2.1: at least one passkey is counted inside the switch's `UPDATE`, never by a read before it - a removal that
/// commits between the handler's reads and the write leaves the switch off; a removal raced against the switch never
/// leaves a passkey-only account without a passkey.
#[tokio::test]
async fn the_switch_counts_its_passkey_in_the_statement() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let mut fake = FakeAuthenticator::es256(b"");
    add_passkey(&app, &cookie, &mut fake, "iPhone").await;
    // The handler read the account (one passkey); then the passkey went (another request, its epoch untouched).
    let read = admin(&app, "parent").await;
    assert_eq!(read.passkey_count, 1);
    sqlx::query("DELETE FROM admin_passkeys")
        .execute(&app.db)
        .await
        .unwrap();
    assert_eq!(
        crate::passkeys::switch_on(&app.db, &read).await.unwrap(),
        crate::passkeys::SwitchedOn::NoPasskey
    );
    assert!(!flag(&app, "parent").await);

    // The switch and the removal of the only passkey at once: one of them, never both.
    let mut fake = FakeAuthenticator::es256(b"");
    let id = add_passkey(&app, &cookie, &mut fake, "iPhone").await;
    let assertion = reauth_assertion(&app, &cookie, &mut fake, "/account/passkey-only").await;
    let remove = format!("/account/passkeys/{id}/remove");
    let on_fields = [("assertion", assertion.as_str())];
    let remove_fields = [("current_password", PASSWORD)];
    let (on, removed) = tokio::join!(
        send(
            &app,
            Method::POST,
            "/account/passkey-only",
            HOST,
            Some(&cookie),
            &on_fields,
        ),
        send(
            &app,
            Method::POST,
            &remove,
            HOST,
            Some(&cookie),
            &remove_fields,
        ),
    );
    let only = flag(&app, "parent").await;
    let left = count_passkeys(&app, "parent").await;
    assert!(
        (only && left == 1) || (!only && left == 0),
        "passkey only {only} with {left} passkeys ({} / {})",
        on.status,
        removed.status
    );
}

/// D2.2: a passkey-only name at /login takes `PasswordGate`'s dummy hash - the unknown name's path - and the usual
/// refusal; only the log says "passkey only".
#[tokio::test]
async fn a_passkey_only_name_signs_in_with_no_password() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let mut fake = FakeAuthenticator::es256(b"");
    add_passkey(&app, &cookie, &mut fake, "iPhone").await;
    switch_on(&app, &cookie, &mut fake).await;
    let account = admin(&app, "parent").await;
    assert_eq!(crate::handlers::auth::login_hash(Some(&account)), None);
    assert_eq!(crate::handlers::auth::login_hash(None), None);

    let unknown = app
        .request_form(
            Method::POST,
            "/login",
            None,
            &[("username", "nobody"), ("password", PASSWORD)],
        )
        .await;
    for password in [PASSWORD, "!passkey", ""] {
        let res = app
            .request_form(
                Method::POST,
                "/login",
                None,
                &[("username", "parent"), ("password", password)],
            )
            .await;
        assert_eq!(res.status, unknown.status);
        assert!(res.text().contains("Incorrect username or password."));
        assert_eq!(
            res.text(),
            unknown.text(),
            "no hint which names are passkey-only"
        );
    }
    let said = super::security_words(&app, "login_failed", crate::i18n::Lang::En).await;
    assert!(
        said.iter()
            .any(|row| row.contains("Passkey only: no password")),
        "{said:?}"
    );
    // The passkey signs in, straight past the TOTP step it no longer has.
    let (res, _) = sign_in(&app, &mut fake).await;
    assert_eq!(res.location(), Some("/"));
}

/// D2.2: while it is on, the TOTP setup, the forced change and Account's password and 2FA forms refuse (back to
/// `#passkey-only`) and write nothing - only the switch-off page brings either back.
#[tokio::test]
async fn the_password_and_totp_forms_refuse_while_on() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let mut fake = FakeAuthenticator::es256(b"");
    add_passkey(&app, &cookie, &mut fake, "iPhone").await;
    switch_on(&app, &cookie, &mut fake).await;
    // Not sent to set up a TOTP although it has none.
    let res = send(&app, Method::GET, "/devices", HOST, Some(&cookie), &[]).await;
    assert_eq!(res.status, StatusCode::OK);

    let password = "a brand new password";
    let reset = reauth_assertion(&app, &cookie, &mut fake, "/account/reset-2fa").await;
    let change = reauth_assertion(&app, &cookie, &mut fake, "/account/password").await;
    for (method, path, fields) in [
        (Method::GET, "/auth/setup-2fa", vec![]),
        (Method::POST, "/auth/setup-2fa", vec![("code", "123456")]),
        (Method::GET, "/auth/change-password", vec![]),
        (
            Method::POST,
            "/auth/change-password",
            vec![
                ("current_password", "!passkey"),
                ("new_password", password),
                ("confirm_password", password),
            ],
        ),
        (
            Method::POST,
            "/account/password",
            vec![
                ("assertion", change.as_str()),
                ("new_password", password),
                ("confirm_password", password),
            ],
        ),
        (
            Method::POST,
            "/account/reset-2fa",
            vec![("assertion", reset.as_str())],
        ),
    ] {
        let res = send(&app, method.clone(), path, HOST, Some(&cookie), &fields).await;
        assert_eq!(
            res.location(),
            Some("/account#passkey-only"),
            "{method} {path}"
        );
    }
    let row: (bool, String, Option<String>, bool) = sqlx::query_as(
        "SELECT passkey_only, password_hash, totp_secret, totp_enabled FROM admin_users WHERE username = 'parent'",
    )
    .fetch_one(&app.db)
    .await
    .unwrap();
    assert_eq!(row, (true, "!passkey".to_string(), None, false));
    assert_eq!(
        send(&app, Method::GET, "/devices", HOST, Some(&cookie), &[])
            .await
            .status,
        StatusCode::OK
    );
}

/// D2.4: another parent's Reset…, a soft Remove and the shell's `reset-2fa`/`reset-sign-in` clear the flag (and
/// delete the passkeys, step 1) - none needs the password.
#[tokio::test]
async fn every_way_back_clears_passkey_only() {
    let app = TestApp::new().await;
    let mamma = app.admin_cookie().await;
    let pappa = app.admin_cookie_as("pappa", "Pappa").await;
    let mamma_id = scalar(&app, "SELECT id FROM admin_users WHERE username = 'parent'").await;
    let mut fake = FakeAuthenticator::es256(b"");
    add_passkey(&app, &mamma, &mut fake, "iPhone").await;
    switch_on(&app, &mamma, &mut fake).await;
    assert!(flag(&app, "parent").await);
    let page = account_page(&app, &pappa).await;
    assert!(
        page.contains("parent's passkeys are removed too."),
        "{page}"
    );

    let res = send(
        &app,
        Method::POST,
        &format!("/account/parents/{mamma_id}/reset"),
        HOST,
        Some(&pappa),
        &[("current_password", PASSWORD)],
    )
    .await;
    assert_eq!(res.status, StatusCode::OK, "{}", res.text());
    assert!(!flag(&app, "parent").await);
    assert_eq!(count_passkeys(&app, "parent").await, 0);
    assert_ne!(
        scalar(
            &app,
            "SELECT password_hash = '!passkey' FROM admin_users WHERE username = 'parent'"
        )
        .await,
        1
    );

    // Passkey only again (as the test accounts are onboarded), then the shell.
    let onboard = || async {
        sqlx::query(
            "UPDATE admin_users SET must_change_password = 0, totp_enabled = 1, invite_expires_at = NULL, \
             totp_secret = 'JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP', password_hash = ? WHERE username = 'parent'",
        )
        .bind(crate::security::hash_password(PASSWORD))
        .execute(&app.db)
        .await
        .unwrap();
        let cookie = app.admin_cookie().await;
        let mut fake = FakeAuthenticator::es256(b"");
        add_passkey(&app, &cookie, &mut fake, "iPhone").await;
        let res = switch_on(&app, &cookie, &mut fake).await;
        assert_eq!(
            res.location(),
            Some("/account?done=passkey_only_on#passkey-only")
        );
    };
    onboard().await;
    let said = crate::admin_auth::reset_2fa(&app.db, "parent", &app.state.data_dir)
        .await
        .unwrap();
    assert!(said.contains("its passkey is deleted"), "{said}");
    assert!(!flag(&app, "parent").await);
    assert_eq!(count_passkeys(&app, "parent").await, 0);

    onboard().await;
    let res = send(
        &app,
        Method::POST,
        &format!("/account/parents/{mamma_id}/remove"),
        HOST,
        Some(&pappa),
        &[("current_password", PASSWORD)],
    )
    .await;
    assert_eq!(res.location(), Some("/account?parents=removed#parents"));
    assert!(!flag(&app, "parent").await);
}

/// D2.3: a passkey-only account keeps at least one passkey - the last one's removal is refused by the `DELETE`'s
/// own condition; going down to one with no other parent says the shell is the way back.
#[tokio::test]
async fn the_last_passkey_stays_while_on() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let mut fake = FakeAuthenticator::es256(b"");
    let first = add_passkey(&app, &cookie, &mut fake, "iPhone").await;
    let mut second = FakeAuthenticator::es256(b"");
    let other = add_passkey(&app, &cookie, &mut second, "Mac").await;
    switch_on(&app, &cookie, &mut fake).await;
    let page = account_page(&app, &cookie).await;
    assert!(page.contains(
        "Only one passkey left, and no other parent can reset you. If you lose it, the server's command line is \
         the way back."
    ));

    let route = format!("/account/passkeys/{other}/remove");
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
    assert_eq!(
        res.location(),
        Some("/account?done=passkey_removed#passkeys")
    );
    assert_eq!(count_passkeys(&app, "parent").await, 1);

    // The last: refused, under its row, nothing removed; the page has no form for it.
    let route = format!("/account/passkeys/{first}/remove");
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
    assert_eq!(res.status, StatusCode::BAD_REQUEST);
    assert!(text(&res).contains("Turn the password on first"));
    assert_eq!(count_passkeys(&app, "parent").await, 1);
    let page = account_page(&app, &cookie).await;
    assert!(!page.contains(&format!("action=\"{route}#passkeys\"")));
    // The statement itself refuses, whatever the handler read.
    let account = admin(&app, "parent").await;
    assert_eq!(
        crate::passkeys::remove(&app.db, &account, first)
            .await
            .unwrap(),
        crate::passkeys::Removed::Last
    );
    assert_eq!(count_passkeys(&app, "parent").await, 1);
}

/// The TOTP key the switch-off page shows (its "Can't scan it?" panel).
fn shown_secret(page: &str) -> String {
    let start = page.find("<p class=\"secret-key\">").unwrap() + "<p class=\"secret-key\">".len();
    let end = start + page[start..].find("</p>").unwrap();
    page[start..end].replace(' ', "")
}

/// D2.5: the switch-off page - the passkey, a new password twice, the TOTP's QR and code - is one POST and one
/// `UPDATE`: a refusal writes nothing (and doesn't spend the passkey's challenge), done turns both on at once, the
/// passkeys stay, the code can't sign in again.
#[tokio::test]
async fn switching_off_is_one_write() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let mut fake = FakeAuthenticator::es256(b"");
    add_passkey(&app, &cookie, &mut fake, "iPhone").await;
    // Off: the page goes back to Account.
    let res = send(
        &app,
        Method::GET,
        "/account/password-sign-in",
        HOST,
        Some(&cookie),
        &[],
    )
    .await;
    assert_eq!(res.location(), Some("/account#password"));
    switch_on(&app, &cookie, &mut fake).await;
    let epoch = scalar(
        &app,
        "SELECT session_epoch FROM admin_users WHERE username = 'parent'",
    )
    .await;

    let res = send(
        &app,
        Method::GET,
        "/account/password-sign-in",
        HOST,
        Some(&cookie),
        &[],
    )
    .await;
    assert_eq!(res.status, StatusCode::OK);
    let page = text(&res);
    super::settings_pages::check_forms("/account/password-sign-in", &page);
    assert!(page.contains("data:image/png;base64,"));
    let secret = shown_secret(&page);
    // A reload shows the same key (it lives in the session until the write).
    let again = text(
        &send(
            &app,
            Method::GET,
            "/account/password-sign-in",
            HOST,
            Some(&cookie),
            &[],
        )
        .await,
    );
    assert_eq!(shown_secret(&again), secret);
    let code =
        crate::security::totp_for_secret(&secret, "parent").generate(crate::admin_auth::now_secs());
    let new = "a long new password";
    let assertion = reauth_assertion(&app, &cookie, &mut fake, "/account/password-sign-in").await;

    let post = |fields: Vec<(&'static str, String)>| {
        let app = &app;
        let cookie = cookie.clone();
        async move {
            let fields: Vec<(&str, &str)> = fields.iter().map(|(k, v)| (*k, v.as_str())).collect();
            send(
                app,
                Method::POST,
                "/account/password-sign-in",
                HOST,
                Some(&cookie),
                &fields,
            )
            .await
        }
    };
    let unchanged = || async {
        let row: (bool, String, Option<String>, bool) = sqlx::query_as(
            "SELECT passkey_only, password_hash, totp_secret, totp_enabled FROM admin_users WHERE username = 'parent'",
        )
        .fetch_one(&app.db)
        .await
        .unwrap();
        assert_eq!(row, (true, "!passkey".to_string(), None, false));
    };
    for (fields, said) in [
        (
            vec![
                ("new_password", "short".to_string()),
                ("confirm_password", "short".to_string()),
                ("code", code.clone()),
                ("assertion", assertion.clone()),
            ],
            "Use at least 12 characters",
        ),
        (
            vec![
                ("new_password", new.to_string()),
                ("confirm_password", "another long password".to_string()),
                ("code", code.clone()),
                ("assertion", assertion.clone()),
            ],
            "The new passwords don't match",
        ),
        (
            vec![
                ("new_password", new.to_string()),
                ("confirm_password", new.to_string()),
                ("code", "000000".to_string()),
                ("assertion", assertion.clone()),
            ],
            "That code didn't match. Nothing was changed.",
        ),
        (
            vec![
                ("new_password", new.to_string()),
                ("confirm_password", new.to_string()),
                ("code", code.clone()),
            ],
            "Confirm with a passkey. Nothing was changed.",
        ),
    ] {
        let res = post(fields).await;
        assert_eq!(res.status, StatusCode::BAD_REQUEST);
        assert!(text(&res).contains(said), "{said}: {}", text(&res));
        unchanged().await;
    }
    // The refusals before the passkey didn't spend its challenge: the same assertion turns it on now.
    let res = post(vec![
        ("new_password", new.to_string()),
        ("confirm_password", new.to_string()),
        ("code", code.clone()),
        ("assertion", assertion.clone()),
    ])
    .await;
    assert_eq!(res.location(), Some("/account?done=password_on#password"));
    app.assert_lands(&res, &cookie).await;
    let row: (bool, String, Option<String>, bool, Option<i64>, i64) = sqlx::query_as(
        "SELECT passkey_only, password_hash, totp_secret, totp_enabled, totp_last_step, session_epoch \
         FROM admin_users WHERE username = 'parent'",
    )
    .fetch_one(&app.db)
    .await
    .unwrap();
    assert!(!row.0);
    assert!(crate::security::verify_password(new, &row.1));
    assert_eq!(row.2.as_deref(), Some(secret.as_str()));
    assert!(row.3);
    assert_eq!(
        row.4,
        crate::admin_auth::totp_step(&secret, "parent", &code, crate::admin_auth::now_secs())
    );
    assert_eq!(row.5, epoch, "no session ends");
    assert_eq!(count_passkeys(&app, "parent").await, 1, "the passkeys stay");
    assert_eq!(
        scalar(
            &app,
            "SELECT COUNT(*) FROM security_events WHERE event_type = 'passkey_only_off'"
        )
        .await,
        1
    );
    let page = text(&app.get_page("/account?done=password_on", &cookie).await);
    assert!(
        page.contains("The password and two-factor login are on again."),
        "{page}"
    );
    assert!(page.contains("action=\"/account/password#password\""));
    // The new password signs in, through the TOTP step; the page's code doesn't work a second time.
    let login = app
        .request_form(
            Method::POST,
            "/login",
            None,
            &[("username", "parent"), ("password", new)],
        )
        .await;
    assert_eq!(login.location(), Some("/auth/verify-2fa"));
    let pending = super::session_cookie(&login).unwrap();
    let res = app
        .request_form(
            Method::POST,
            "/auth/verify-2fa",
            Some(&pending),
            &[("code", &code)],
        )
        .await;
    assert!(res.text().contains("That code didn"), "{}", res.text());

    // A held request past the switch-off writes nothing (the statement's own condition).
    let account = admin(&app, "parent").await;
    assert_eq!(
        crate::passkeys::switch_off(&app.db, &account, "x", "y", 1)
            .await
            .unwrap(),
        None
    );
}

/// D2.2: the readiness predicate's passkey-only arm in `require_full_auth` (and its SQL twin): passkey-only with a
/// passkey is ready though it has no TOTP; without one (no statement leaves that) it is no session at all, never the
/// TOTP setup.
#[tokio::test]
async fn the_predicate_has_a_passkey_only_arm() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let mut fake = FakeAuthenticator::es256(b"");
    add_passkey(&app, &cookie, &mut fake, "iPhone").await;
    switch_on(&app, &cookie, &mut fake).await;
    let account = admin(&app, "parent").await;
    assert!(account.ready());
    let ready: bool = sqlx::query_scalar(concat!(
        "SELECT ",
        crate::ready_sql!(),
        " FROM admin_users WHERE username = 'parent'"
    ))
    .fetch_one(&app.db)
    .await
    .unwrap();
    assert!(ready);
    assert_eq!(
        send(&app, Method::GET, "/devices", HOST, Some(&cookie), &[])
            .await
            .status,
        StatusCode::OK
    );

    sqlx::query("DELETE FROM admin_passkeys")
        .execute(&app.db)
        .await
        .unwrap();
    let account = admin(&app, "parent").await;
    assert!(!account.ready());
    let ready: bool = sqlx::query_scalar(concat!(
        "SELECT ",
        crate::ready_sql!(),
        " FROM admin_users WHERE username = 'parent'"
    ))
    .fetch_one(&app.db)
    .await
    .unwrap();
    assert!(!ready);
    let res = send(&app, Method::GET, "/devices", HOST, Some(&cookie), &[]).await;
    assert_eq!(res.location(), Some("/login"));
    let res = send(&app, Method::GET, "/api/session", HOST, Some(&cookie), &[]).await;
    assert_eq!(res.status, StatusCode::UNAUTHORIZED);
}

async fn login_page(app: &TestApp) -> String {
    text(&send(app, Method::GET, "/login", HOST, None, &[]).await)
}

/// §6.4: with every parent passkey-only and no one-time password pending, /login offers only the filled passkey
/// button (no username, no password, no "or"), says what a browser without passkeys should do, starts nothing on its
/// own; `POST /login` still answers as before. A pending invitation brings the form back; an expired one doesn't.
#[tokio::test]
async fn the_sign_in_page_without_passwords() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    assert!(login_page(&app).await.contains("name=\"password\""));
    let mut fake = FakeAuthenticator::es256(b"");
    add_passkey(&app, &cookie, &mut fake, "iPhone").await;
    switch_on(&app, &cookie, &mut fake).await;

    let page = login_page(&app).await;
    assert!(page.contains("data-passkey-only"), "{page}");
    assert!(!page.contains("name=\"password\""));
    assert!(!page.contains("name=\"username\""));
    assert!(!page.contains("or-divider"));
    assert!(page.contains("class=\"button-wide button-key\" data-passkey-login"));
    assert!(page.contains("This browser can't use passkeys. Open the page in Safari or Chrome."));
    let res = app
        .request_form(
            Method::POST,
            "/login",
            None,
            &[("username", "parent"), ("password", PASSWORD)],
        )
        .await;
    assert!(res.text().contains("Incorrect username or password."));
    // A refused passkey says nothing about a password.
    let res = send(
        &app,
        Method::POST,
        "/auth/passkey",
        HOST,
        None,
        &[("assertion", "{}")],
    )
    .await;
    let said = text(&res);
    assert!(
        said.contains("The passkey didn't work. Try again."),
        "{said}"
    );
    assert!(!said.contains("sign in with your password"));
    // It still signs in.
    let (res, _) = sign_in(&app, &mut fake).await;
    assert_eq!(res.location(), Some("/"));

    // An invitation pending: its one-time password needs the form.
    sqlx::query(
        "INSERT INTO admin_users (username, password_hash, must_change_password, totp_enabled, invite_expires_at) \
         VALUES ('pappa', 'x', 1, 0, datetime('now', '+72 hours'))",
    )
    .execute(&app.db)
    .await
    .unwrap();
    assert!(login_page(&app).await.contains("name=\"password\""));
    // Expired: no password signs in there either.
    sqlx::query("UPDATE admin_users SET invite_expires_at = datetime('now', '-1 hours') WHERE username = 'pappa'")
        .execute(&app.db)
        .await
        .unwrap();
    assert!(!login_page(&app).await.contains("name=\"password\""));
    // A ready parent with a password: the form.
    sqlx::query("UPDATE admin_users SET must_change_password = 0, totp_enabled = 1, invite_expires_at = NULL WHERE username = 'pappa'")
        .execute(&app.db)
        .await
        .unwrap();
    assert!(login_page(&app).await.contains("name=\"password\""));
}

/// qa-passkeys-1 A-R1: the ban counts by the IPv6 /56 - fresh /64s from one home line's range share one count, so
/// they can't keep a passkey-only parent's sign-in challenge evicted, nor dodge the ban.
#[tokio::test]
async fn fresh_64s_of_one_56_share_the_ban() {
    let app = TestApp::with_env(&[("ADMIN_PUBLIC", "on")]).await;
    let options = |n: u32| {
        Request::builder()
            .method(Method::POST)
            .uri("/auth/passkey/options")
            .header(header::HOST, HOST)
            .header("sec-fetch-site", "same-origin")
            .header("x-forwarded-for", format!("2001:db8:7:1{n:02x}::9"))
            .body(Body::empty())
            .unwrap()
    };
    for n in 0..crate::security::MAX_FAILED_PER_IP as u32 {
        let res = app.send_via(&app.router, LOOPBACK_PEER, options(n)).await;
        assert_eq!(res.status, StatusCode::OK);
    }
    let res = app
        .send_via(&app.router, LOOPBACK_PEER, options(0xee))
        .await;
    assert_eq!(res.status, StatusCode::TOO_MANY_REQUESTS);
    let banned: String = sqlx::query_scalar("SELECT ip_address FROM banned_ips")
        .fetch_one(&app.db)
        .await
        .unwrap();
    assert_eq!(banned, "2001:db8:7:100::/56");
}

/// qa-passkeys-1 B-RN1: a pre-session's counted row goes when its challenge aged out and the page asks again (it
/// counts once however long the page stays open); a row whose challenge was evicted by the client's other sessions
/// while young stays (churning sessions costs one each).
#[tokio::test]
async fn a_pre_sessions_aged_out_row_stops_counting() {
    let app = TestApp::new().await;
    let started = || async {
        scalar(
            &app,
            "SELECT COUNT(*) FROM security_events WHERE event_type = 'passkey_started'",
        )
        .await
    };
    let first = send(&app, Method::POST, "/auth/passkey/options", HOST, None, &[]).await;
    let a = super::session_cookie(&first).unwrap();
    let challenge = challenge_of(&first);
    // Three more sessions of the same client: the first one's challenge goes (the per-client cap).
    for _ in 0..crate::passkeys::SIGN_IN_PER_CLIENT {
        send(&app, Method::POST, "/auth/passkey/options", HOST, None, &[]).await;
    }
    assert_eq!(started().await, 4);
    // Asked again while its row is young: a new challenge and a new row; the old one stays.
    let again = send(
        &app,
        Method::POST,
        "/auth/passkey/options",
        HOST,
        Some(&a),
        &[],
    )
    .await;
    assert_ne!(challenge_of(&again), challenge);
    assert_eq!(started().await, 5);
    // Its rows aged past the reuse window (the page open for minutes); its challenge evicted once more.
    sqlx::query(
        "UPDATE security_events SET created_at = datetime('now', '-250 seconds') WHERE event_type = 'passkey_started'",
    )
    .execute(&app.db)
    .await
    .unwrap();
    for _ in 0..crate::passkeys::SIGN_IN_PER_CLIENT {
        send(&app, Method::POST, "/auth/passkey/options", HOST, None, &[]).await;
    }
    assert_eq!(started().await, 8);
    send(
        &app,
        Method::POST,
        "/auth/passkey/options",
        HOST,
        Some(&a),
        &[],
    )
    .await;
    assert_eq!(
        started().await,
        7,
        "its aged rows went (both), a new one came"
    );
}

/// qa-passkeys-2 M1: a passkey-only *actor*'s password proof on any re-auth form is answered "Confirm with a
/// passkey" and counts nothing (no `reauth_wrong`, never three that sign out); each form works by passkey.
#[tokio::test]
async fn a_passkey_only_parents_reauth_forms_take_only_the_passkey() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let pappa = app.admin_cookie_as("pappa", "Pappa").await;
    let pappa_id = scalar(&app, "SELECT id FROM admin_users WHERE username = 'pappa'").await;
    let mut fake = FakeAuthenticator::es256(b"");
    add_passkey(&app, &cookie, &mut fake, "iPhone").await;
    let mut second = FakeAuthenticator::es256(b"");
    let other = add_passkey(&app, &cookie, &mut second, "Mac").await;
    switch_on(&app, &cookie, &mut fake).await;
    let (device, _) = app.create_device("Anna").await;
    for (path, field, pin) in [
        ("parent-pin", "new_pin", "739146"),
        ("kid-lock", "new_kid_pin", "2580"),
    ] {
        let saved = send(
            &app,
            Method::POST,
            &format!("/devices/{device}/{path}"),
            HOST,
            Some(&cookie),
            &[(field, pin)],
        )
        .await;
        assert_eq!(saved.status, StatusCode::SEE_OTHER, "{}", saved.text());
    }
    let lock = format!("/devices/{device}/lock");
    let remove_passkey = format!("/account/passkeys/{other}/remove");
    let reset = format!("/account/parents/{pappa_id}/reset");
    let remove = format!("/account/parents/{pappa_id}/remove");
    let forms: Vec<(&str, Vec<(&str, &str)>)> = vec![
        (
            "/account/passkeys/options",
            vec![("name", "Laptop"), ("current_password", "guess")],
        ),
        (
            "/account/parents",
            vec![
                ("new_username", "mormor"),
                ("name", "Mormor"),
                ("current_password", ""),
            ],
        ),
        (&reset, vec![("current_password", "guess")]),
        (&remove, vec![("current_password", "")]),
        (&remove_passkey, vec![("current_password", "guess")]),
        (
            &lock,
            vec![("action", "reveal_kid_pin"), ("password", "guess")],
        ),
    ];
    for (path, fields) in &forms {
        let res = send(&app, Method::POST, path, HOST, Some(&cookie), fields).await;
        assert_eq!(res.status, StatusCode::BAD_REQUEST, "{path}");
        let said = text(&res);
        assert!(
            said.contains("Confirm with a passkey. Nothing was changed."),
            "{path}: {said}"
        );
        assert!(!said.contains("password is wrong"), "{path}");
    }
    // Nothing counted: no wrong re-auth, no sign-out, nothing written.
    assert_eq!(
        scalar(
            &app,
            "SELECT reauth_wrong FROM admin_users WHERE username = 'parent'"
        )
        .await,
        0
    );
    assert_eq!(
        send(&app, Method::GET, "/devices", HOST, Some(&cookie), &[])
            .await
            .status,
        StatusCode::OK
    );
    assert_eq!(count_passkeys(&app, "parent").await, 2);
    assert_eq!(
        scalar(
            &app,
            "SELECT COUNT(*) FROM admin_users WHERE username = 'mormor'"
        )
        .await,
        0
    );
    assert!(
        send(&app, Method::GET, "/devices", HOST, Some(&pappa), &[])
            .await
            .status
            == StatusCode::OK,
        "pappa untouched"
    );
    // The account page's forms mark their passkey button so a submit starts it (passkey.js).
    let page = account_page(&app, &cookie).await;
    assert!(page.contains("data-passkey-reauth data-passkey-only"));

    // By passkey each one works: a passkey added, a parent added, Show Child PIN, pappa reset.
    let assertion = reauth_assertion(&app, &cookie, &mut fake, "/account/passkeys/options").await;
    let mut third = FakeAuthenticator::es256(b"");
    add_passkey_at(
        &app,
        &cookie,
        &mut third,
        "Laptop",
        HOST,
        ORIGIN,
        &[("assertion", &assertion)],
    )
    .await;
    assert_eq!(count_passkeys(&app, "parent").await, 3);
    let assertion = reauth_assertion(&app, &cookie, &mut fake, "/account/parents").await;
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
    let assertion = reauth_assertion(&app, &cookie, &mut fake, &lock).await;
    let res = send(
        &app,
        Method::POST,
        &lock,
        HOST,
        Some(&cookie),
        &[("action", "reveal_kid_pin"), ("assertion", &assertion)],
    )
    .await;
    assert_eq!(res.status, StatusCode::OK, "{}", res.text());
    let assertion = reauth_assertion(&app, &cookie, &mut fake, &reset).await;
    let res = send(
        &app,
        Method::POST,
        &reset,
        HOST,
        Some(&cookie),
        &[("assertion", &assertion)],
    )
    .await;
    assert_eq!(res.status, StatusCode::OK, "{}", res.text());
    assert_eq!(
        scalar(
            &app,
            "SELECT must_change_password FROM admin_users WHERE username = 'pappa'"
        )
        .await,
        1
    );
}

/// qa-passkeys-2 L1, L2: a passkey-only parent's only passkey for one address says signing in there stops; the switch
/// names the addresses its passkeys are for.
#[tokio::test]
async fn the_last_passkey_for_an_address_says_so() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let mut fake = FakeAuthenticator::es256(b"");
    add_passkey(&app, &cookie, &mut fake, "iPhone").await;
    let page = account_page(&app, &cookie).await;
    assert!(
        page.contains("Then you can sign in only at handy.example.com, where you have a passkey."),
        "{page}"
    );
    let mut other = FakeAuthenticator::es256(b"");
    let elsewhere = add_passkey(&app, &cookie, &mut other, "Tailnet").await;
    sqlx::query("UPDATE admin_passkeys SET rp_id = 'phone.tail1234.ts.net' WHERE id = ?")
        .bind(elsewhere)
        .execute(&app.db)
        .await
        .unwrap();
    switch_on(&app, &cookie, &mut fake).await;
    let page = account_page(&app, &cookie).await;
    assert!(page.contains(
        "This is your only passkey for handy.example.com: after removing it you can't sign in there any more."
    ));
    assert!(page.contains(
        "This is your only passkey for phone.tail1234.ts.net: after removing it you can't sign in there any more."
    ));
}

/// qa-passkeys-2 L3: the shell's line says Passkey Only was turned off.
#[tokio::test]
async fn the_shell_says_passkey_only_is_off() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let mut fake = FakeAuthenticator::es256(b"");
    add_passkey(&app, &cookie, &mut fake, "iPhone").await;
    switch_on(&app, &cookie, &mut fake).await;
    let said = crate::admin_auth::reset_2fa(&app.db, "parent", &app.state.data_dir)
        .await
        .unwrap();
    assert!(
        said.starts_with("Passkey Only for parent is turned off,"),
        "{said}"
    );
    let said = crate::admin_auth::reset_2fa(&app.db, "parent", &app.state.data_dir)
        .await
        .unwrap();
    assert!(
        said.starts_with("Two-factor login for parent is reset,"),
        "{said}"
    );
}

/// qa-passkeys-2 limiter L1: a challenge handed out again says the time it has left as `timeout`.
#[tokio::test]
async fn a_reused_challenge_says_its_time_left() {
    let app = TestApp::new().await;
    let first = send(&app, Method::POST, "/auth/passkey/options", HOST, None, &[]).await;
    assert_eq!(first.json()["timeout"], 300_000);
    let cookie = super::session_cookie(&first).unwrap();
    tokio::time::sleep(std::time::Duration::from_millis(1100)).await;
    let again = send(
        &app,
        Method::POST,
        "/auth/passkey/options",
        HOST,
        Some(&cookie),
        &[],
    )
    .await;
    assert_eq!(challenge_of(&again), challenge_of(&first));
    let left = again.json()["timeout"].as_u64().unwrap();
    assert!(left < 300_000 && left > 290_000, "{left}");
}

/// qa-passkeys-2 limiter L3: a banned key (a shared address) gets one live challenge and a valid passkey signs in
/// through the ban; a bad assertion is still counted and answered 429; a second challenge is refused; /login stays
/// banned.
#[tokio::test]
async fn a_valid_passkey_signs_in_through_a_ban() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let mut fake = FakeAuthenticator::es256(b"");
    add_passkey(&app, &cookie, &mut fake, "iPhone").await;
    for _ in 0..crate::security::MAX_FAILED_PER_IP {
        app.request_form(
            Method::POST,
            "/login",
            None,
            &[("username", "parent"), ("password", "wrong")],
        )
        .await;
    }
    let res = app
        .request_form(
            Method::POST,
            "/login",
            None,
            &[("username", "parent"), ("password", PASSWORD)],
        )
        .await;
    assert_eq!(res.status, StatusCode::TOO_MANY_REQUESTS);
    // A bad assertion through the ban: counted, 429.
    let refused = scalar(
        &app,
        "SELECT COUNT(*) FROM security_events WHERE event_type = 'passkey_refused'",
    )
    .await;
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
    assert_eq!(
        scalar(
            &app,
            "SELECT COUNT(*) FROM security_events WHERE event_type = 'passkey_refused'"
        )
        .await,
        refused + 1
    );
    // One live challenge for the banned key; another session's ask is refused while it lives.
    let options = send(&app, Method::POST, "/auth/passkey/options", HOST, None, &[]).await;
    let challenge = challenge_of(&options);
    let pre = super::session_cookie(&options).unwrap();
    let second = send(&app, Method::POST, "/auth/passkey/options", HOST, None, &[]).await;
    assert_eq!(second.status, StatusCode::TOO_MANY_REQUESTS);
    // The valid passkey signs in.
    let assertion = fake.assert(&challenge, ORIGIN, HOST, true);
    let res = send(
        &app,
        Method::POST,
        "/auth/passkey",
        HOST,
        Some(&pre),
        &[("assertion", &assertion)],
    )
    .await;
    assert_eq!(res.location(), Some("/"), "{}", res.text());
}

/// qa-passkeys-2 limiter L4: a session's open rows are a list; a sign-in takes them all back, so a household whose
/// pages evicted each other's challenges pays nothing once it signs in.
#[tokio::test]
async fn a_sign_in_takes_back_every_open_row_of_its_session() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let mut fake = FakeAuthenticator::es256(b"");
    add_passkey(&app, &cookie, &mut fake, "iPhone").await;
    let started = || async {
        scalar(
            &app,
            "SELECT COUNT(*) FROM security_events WHERE event_type = 'passkey_started'",
        )
        .await
    };
    let first = send(&app, Method::POST, "/auth/passkey/options", HOST, None, &[]).await;
    let a = super::session_cookie(&first).unwrap();
    for _ in 0..crate::passkeys::SIGN_IN_PER_CLIENT {
        send(&app, Method::POST, "/auth/passkey/options", HOST, None, &[]).await;
    }
    // Evicted while young: asked again, a second open row of the same session.
    let again = send(
        &app,
        Method::POST,
        "/auth/passkey/options",
        HOST,
        Some(&a),
        &[],
    )
    .await;
    assert_eq!(started().await, 5);
    let assertion = fake.assert(&challenge_of(&again), ORIGIN, HOST, true);
    let res = send(
        &app,
        Method::POST,
        "/auth/passkey",
        HOST,
        Some(&a),
        &[("assertion", &assertion)],
    )
    .await;
    assert_eq!(res.location(), Some("/"));
    assert_eq!(
        started().await,
        3,
        "both of its rows went; the other sessions' stay"
    );
}
