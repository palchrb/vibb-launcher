//! Several parent accounts (design 62 steps 1-2, `docs/design/62-parent-accounts.md` §9): the
//! latent bugs, usernames and names, the guarded self-service writes, and Account's Name and
//! Parents groups (Add with its one-time password, Withdraw, the invitee's first sign-in).

use axum::http::{Method, StatusCode, header};
use tower::ServiceExt;

use super::settings_pages::{check_forms, part};
use super::{LOOPBACK_PEER, TestApp, TestResponse, assert_redirect};
use crate::models::AdminUser;
use crate::security;

/// `admin_cookie`'s and `admin_cookie_as`'s password.
const PASSWORD: &str = "correct horse battery staple";
const SECRET: &str = "JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP";
const PAPPAS_OWN: &str = "pappa's own long password";

fn cookie_of(res: &TestResponse) -> Option<String> {
    res.headers
        .get(header::SET_COOKIE)
        .and_then(|v| v.to_str().ok())
        .and_then(|v| v.split(';').next())
        .map(str::to_string)
}

async fn login(app: &TestApp, username: &str, password: &str) -> TestResponse {
    app.request_form(
        Method::POST,
        "/login",
        None,
        &[("username", username), ("password", password)],
    )
    .await
}

async fn add(app: &TestApp, cookie: &str, username: &str, name: &str, pw: &str) -> TestResponse {
    app.request_form(
        Method::POST,
        "/account/parents",
        Some(cookie),
        &[
            ("new_username", username),
            ("name", name),
            ("current_password", pw),
        ],
    )
    .await
}

/// The one-time password on Add's answer: the spans' text, no spaces.
fn otp_of(html: &str) -> String {
    let shown = part(html, "<p class=\"secret-once\"", "</p>");
    let inner = &shown[shown.find('>').unwrap() + 1..];
    inner
        .replace("<span>", "")
        .replace("</span>", "")
        .replace("</p>", "")
}

async fn account(app: &TestApp, username: &str) -> AdminUser {
    sqlx::query_as("SELECT * FROM admin_users WHERE username = ?")
        .bind(username)
        .fetch_one(&app.db)
        .await
        .unwrap()
}

async fn events(app: &TestApp, kind: &str) -> Vec<Option<String>> {
    sqlx::query_scalar("SELECT detail FROM security_events WHERE event_type = ? ORDER BY id")
        .bind(kind)
        .fetch_all(&app.db)
        .await
        .unwrap()
}

/// Mamma signed in ("parent", fully onboarded) and Pappa added: (Mamma's cookie, Pappa's OTP).
async fn mamma_adds_pappa(app: &TestApp) -> (String, String) {
    let mamma = app.admin_cookie().await;
    let res = add(app, &mamma, " Pappa ", "Pappa", PASSWORD).await;
    assert_eq!(res.status, StatusCode::OK, "{}", res.text());
    (mamma, otp_of(&res.text()))
}

/// Signs `username` in with a password, for an account without 2FA yet: the session cookie.
async fn onboarding_session(app: &TestApp, username: &str, password: &str) -> String {
    let res = login(app, username, password).await;
    assert_eq!(res.location(), Some("/"), "{}", res.text());
    cookie_of(&res).unwrap()
}

/// A form POST whose body is sent only when the returned sender is used: the request passes the
/// session middleware now and runs its handler after whatever the test does in between.
async fn held(
    app: &TestApp,
    uri: &str,
    cookie: &str,
) -> (
    tokio::sync::mpsc::Sender<Result<axum::body::Bytes, std::io::Error>>,
    tokio::task::JoinHandle<axum::response::Response>,
) {
    let (tx, rx) = tokio::sync::mpsc::channel(1);
    let mut request = axum::http::Request::builder()
        .method(Method::POST)
        .uri(uri)
        .header(header::CONTENT_TYPE, "application/x-www-form-urlencoded")
        .header(header::COOKIE, cookie)
        .body(axum::body::Body::from_stream(
            tokio_stream::wrappers::ReceiverStream::new(rx),
        ))
        .unwrap();
    request
        .extensions_mut()
        .insert(axum::extract::ConnectInfo(LOOPBACK_PEER));
    let router = app.router.clone();
    let task = tokio::spawn(async move { router.oneshot(request).await.unwrap() });
    // Past the session check, waiting for its body.
    tokio::time::sleep(std::time::Duration::from_millis(200)).await;
    (tx, task)
}

async fn release(
    tx: tokio::sync::mpsc::Sender<Result<axum::body::Bytes, std::io::Error>>,
    task: tokio::task::JoinHandle<axum::response::Response>,
    body: &str,
) -> axum::response::Response {
    tx.send(Ok(axum::body::Bytes::from(body.to_string())))
        .await
        .unwrap();
    drop(tx);
    task.await.unwrap()
}

fn location(response: &axum::response::Response) -> Option<&str> {
    response
        .headers()
        .get(header::LOCATION)
        .and_then(|v| v.to_str().ok())
}

/// What design 62 §4.3's Reset (step 3) writes, as a statement here.
async fn reset_by_sql(app: &TestApp, username: &str) -> String {
    let otp = crate::startup::new_password();
    sqlx::query(
        "UPDATE admin_users SET password_hash = ?, must_change_password = 1, totp_secret = NULL, \
         totp_enabled = 0, totp_last_step = NULL, invite_expires_at = datetime('now', '+72 hours'), \
         session_epoch = session_epoch + 1 WHERE username = ?",
    )
    .bind(security::hash_password(&otp))
    .bind(username)
    .execute(&app.db)
    .await
    .unwrap();
    otp
}

// ---- Step 1 ---------------------------------------------------------------------------------

/// Usernames are `[a-z0-9._-]{1,32}`; Add lowercases (" Pappa " -> "pappa", in
/// [the_otp_is_shown_once_and_never_stored]), the first account's name is only trimmed.
/// Display names refuse control and format characters.
#[test]
fn valid_username_rule() {
    for good in ["mamma", "a", "p.e_t-er2", &"x".repeat(32)] {
        assert!(security::valid_username(good), "{good}");
    }
    for bad in [
        "",
        "Mamma",
        "a:b",
        "a/b",
        "an na",
        " anna",
        &"x".repeat(33),
        "ånne",
    ] {
        assert!(!security::valid_username(bad), "{bad:?}");
    }
    assert_eq!(security::bootstrap_username(None).unwrap(), "admin");
    assert_eq!(security::bootstrap_username(Some("  ")).unwrap(), "admin");
    assert_eq!(
        security::bootstrap_username(Some(" mamma ")).unwrap(),
        "mamma"
    );
    let refused = security::bootstrap_username(Some("Mamma")).unwrap_err();
    assert!(refused.contains("ADMIN_USERNAME"), "{refused}");

    assert_eq!(security::display_name("  "), Ok(None));
    assert_eq!(
        security::display_name(" Mamma Ås "),
        Ok(Some("Mamma Ås".into()))
    );
    for bad in [
        "Pap\u{202E}pa",
        "Pap\u{200B}pa",
        "Pap\npa",
        "\u{2066}x",
        &"ø".repeat(33),
    ] {
        assert!(security::display_name(bad).is_err(), "{bad:?}");
    }
}

/// A `:` in a username panicked the 2FA setup (A §1.2); the label is only a label.
#[test]
fn colon_in_name_makes_a_code() {
    let with = security::totp_for_secret(SECRET, "ma:mma");
    let without = security::totp_for_secret(SECRET, "mamma");
    assert_eq!(with.generate(59), without.generate(59));
    assert!(with.get_qr_base64().is_ok());
}

/// `reset-2fa` writes `initial-admin-password-<id>`; the forced change spends its own file, and
/// the first account's shared one only for the first account.
#[tokio::test]
async fn cli_reset_writes_its_own_file_and_keeps_others() {
    let app = TestApp::new().await;
    mamma_adds_pappa(&app).await;
    let data = app.state.data_dir.as_path();
    std::fs::create_dir_all(data).unwrap();
    std::fs::write(data.join("initial-admin-password"), "the first one\n").unwrap();
    let (mamma, pappa) = (account(&app, "parent").await, account(&app, "pappa").await);
    crate::admin_auth::reset_2fa(&app.db, "pappa", data)
        .await
        .unwrap();
    crate::admin_auth::reset_2fa(&app.db, "parent", data)
        .await
        .unwrap();
    let read = |id: i64| {
        std::fs::read_to_string(data.join(format!("initial-admin-password-{id}")))
            .unwrap()
            .trim()
            .to_string()
    };
    let (mammas, pappas) = (read(mamma.id), read(pappa.id));
    assert_ne!(mammas, pappas);
    let first = data.join("initial-admin-password");
    assert!(first.exists(), "another account's file is kept");

    let change = |cookie: String, current: String| {
        let app = &app;
        async move {
            app.request_form(
                Method::POST,
                "/auth/change-password",
                Some(&cookie),
                &[
                    ("current_password", current.as_str()),
                    ("new_password", PAPPAS_OWN),
                    ("confirm_password", PAPPAS_OWN),
                ],
            )
            .await
        }
    };
    let res = change(onboarding_session(&app, "pappa", &pappas).await, pappas).await;
    assert_eq!(res.location(), Some("/"));
    assert!(
        !data
            .join(format!("initial-admin-password-{}", pappa.id))
            .exists()
    );
    assert!(
        first.exists(),
        "an invited account never had the first file"
    );
    assert!(
        data.join(format!("initial-admin-password-{}", mamma.id))
            .exists()
    );

    let res = change(onboarding_session(&app, "parent", &mammas).await, mammas).await;
    assert_eq!(res.location(), Some("/"));
    assert!(
        !data
            .join(format!("initial-admin-password-{}", mamma.id))
            .exists()
    );
    assert!(
        !first.exists(),
        "the first account spends the first file too"
    );
}

/// The shell restores a removed parent (the way back from the other parent's Remove) and always
/// clears a one-time password's expiry.
#[tokio::test]
async fn cli_reset_restores_a_removed_account() {
    let app = TestApp::new().await;
    mamma_adds_pappa(&app).await;
    let mamma = account(&app, "parent").await;
    let pappa = account(&app, "pappa").await;
    assert_eq!(
        crate::parents::remove_parent(&app.db, &mamma, pappa.id)
            .await
            .unwrap(),
        crate::parents::Removed::Done
    );
    let data = app.state.data_dir.as_path();
    let message = crate::admin_auth::reset_2fa(&app.db, "pappa", data)
        .await
        .unwrap();
    assert!(message.contains("restored"), "{message}");
    let password =
        std::fs::read_to_string(data.join(format!("initial-admin-password-{}", pappa.id))).unwrap();
    let restored = account(&app, "pappa").await;
    assert!(restored.invite_expires_at.is_none() && restored.must_change_password);
    assert_eq!(restored.name(), "Pappa", "its name comes back with it");
    assert_eq!(events(&app, "parent_restored").await.len(), 1);
    onboarding_session(&app, "pappa", password.trim()).await;
}

/// `reset-2fa` on a waiting account whose 72 hours ran out: the file's password works.
#[tokio::test]
async fn cli_reset_clears_the_expiry() {
    let app = TestApp::new().await;
    mamma_adds_pappa(&app).await;
    sqlx::query("UPDATE admin_users SET invite_expires_at = datetime('now', '-1 minute')")
        .execute(&app.db)
        .await
        .unwrap();
    let data = app.state.data_dir.as_path();
    crate::admin_auth::reset_2fa(&app.db, "pappa", data)
        .await
        .unwrap();
    let pappa = account(&app, "pappa").await;
    assert!(pappa.invite_expires_at.is_none());
    let password =
        std::fs::read_to_string(data.join(format!("initial-admin-password-{}", pappa.id))).unwrap();
    onboarding_session(&app, "pappa", password.trim()).await;
    assert_eq!(events(&app, "totp_reset").await.len(), 1);
}

/// A removed account is an unknown name at sign-in, even with a hash that would match.
#[tokio::test]
async fn removed_login_reads_as_unknown() {
    let app = TestApp::new().await;
    app.admin_cookie_as("pappa", "Pappa").await;
    sqlx::query("UPDATE admin_users SET removed_at = datetime('now') WHERE username = 'pappa'")
        .execute(&app.db)
        .await
        .unwrap();
    let res = login(&app, "pappa", PASSWORD).await;
    assert!(res.text().contains("Incorrect username or password."));
    assert_eq!(res.location(), None);
}

/// A right one-time password past its 72 hours says so (only to someone who knows it); a wrong one
/// stays "Incorrect".
#[tokio::test]
async fn expired_otp_says_expired() {
    let app = TestApp::new().await;
    let (_, otp) = mamma_adds_pappa(&app).await;
    sqlx::query("UPDATE admin_users SET invite_expires_at = datetime('now', '-1 second') WHERE username = 'pappa'")
        .execute(&app.db)
        .await
        .unwrap();
    let res = login(&app, "pappa", &otp).await;
    assert_eq!(res.location(), None);
    assert!(
        res.text()
            .contains("This invitation has expired - ask for a new one."),
        "{}",
        res.text()
    );
    assert_eq!(events(&app, "login_expired").await.len(), 1);
    let res = login(&app, "pappa", "not it at all").await;
    assert!(res.text().contains("Incorrect username or password."));
}

/// The expiry covers the whole onboarding: a session made with the one-time password ends when it
/// runs out, also after the new password was set.
#[tokio::test]
async fn expired_onboarding_session_ends() {
    let app = TestApp::new().await;
    let (_, otp) = mamma_adds_pappa(&app).await;
    let pappa = onboarding_session(&app, "pappa", &otp).await;
    let res = app
        .request_form(
            Method::POST,
            "/auth/change-password",
            Some(&pappa),
            &[
                ("current_password", otp.as_str()),
                ("new_password", PAPPAS_OWN),
                ("confirm_password", PAPPAS_OWN),
            ],
        )
        .await;
    assert_eq!(res.location(), Some("/"));
    assert_eq!(
        app.get_page("/auth/setup-2fa", &pappa).await.status,
        StatusCode::OK
    );
    sqlx::query("UPDATE admin_users SET invite_expires_at = datetime('now', '-1 second') WHERE username = 'pappa'")
        .execute(&app.db)
        .await
        .unwrap();
    assert_eq!(
        app.get_page("/auth/setup-2fa", &pappa).await.location(),
        Some("/login")
    );
    let res = login(&app, "pappa", PAPPAS_OWN).await;
    assert!(res.text().contains("This invitation has expired"));
}

/// Q62-2: Pappa's password change, held past the session check while Mamma resets him, can't undo
/// the Reset: nothing is written and he is signed out.
#[tokio::test]
async fn held_password_change_cant_undo_a_reset() {
    let app = TestApp::new().await;
    app.admin_cookie().await;
    let pappa = app.admin_cookie_as("pappa", "Pappa").await;
    let (tx, task) = held(&app, "/account/password", &pappa).await;
    let otp = reset_by_sql(&app, "pappa").await;
    let reset_hash = account(&app, "pappa").await.password_hash;
    let answer = release(
        tx,
        task,
        "current_password=correct+horse+battery+staple&new_password=a+password+of+his+own&\
         confirm_password=a+password+of+his+own",
    )
    .await;
    assert_eq!(location(&answer), Some("/login"));
    assert_eq!(account(&app, "pappa").await.password_hash, reset_hash);
    assert_eq!(
        app.get_page("/devices", &pappa).await.location(),
        Some("/login")
    );
    onboarding_session(&app, "pappa", &otp).await;
}

/// Q62-2: two sessions on one one-time password change it at once: the first keeps its session,
/// the second writes nothing and is signed out.
#[tokio::test]
async fn two_otp_sessions_one_keeps_its_session() {
    let app = TestApp::new().await;
    let (_, otp) = mamma_adds_pappa(&app).await;
    let first = onboarding_session(&app, "pappa", &otp).await;
    let second = onboarding_session(&app, "pappa", &otp).await;
    let (tx, task) = held(&app, "/auth/change-password", &second).await;
    let body =
        |new: &str| format!("current_password={otp}&new_password={new}&confirm_password={new}");
    let res = app
        .request_form(
            Method::POST,
            "/auth/change-password",
            Some(&first),
            &[
                ("current_password", otp.as_str()),
                ("new_password", PAPPAS_OWN),
                ("confirm_password", PAPPAS_OWN),
            ],
        )
        .await;
    assert_eq!(res.location(), Some("/"));
    let answer = release(tx, task, &body("the+other+long+password")).await;
    assert_eq!(location(&answer), Some("/login"));
    let pappa = account(&app, "pappa").await;
    assert!(security::verify_password(PAPPAS_OWN, &pappa.password_hash));
    assert_eq!(
        app.get_page("/auth/setup-2fa", &first).await.status,
        StatusCode::OK
    );
    assert_eq!(
        app.get_page("/auth/setup-2fa", &second).await.location(),
        Some("/login")
    );
    // The statement itself: a hash it didn't verify is never replaced.
    assert_eq!(
        crate::admin_auth::change_first_password(
            &app.db,
            pappa.id,
            pappa.session_epoch,
            "not the stored hash",
            "x"
        )
        .await
        .unwrap(),
        None
    );
}

/// Q62-2: a 2FA setup held past the account's removal writes no secret and no "on" onto the
/// removed row; and two setup tabs show one secret (qa-62-revision-check N6).
#[tokio::test]
async fn held_setup_2fa_writes_nothing_on_a_removed_row() {
    let app = TestApp::new().await;
    let (_, otp) = mamma_adds_pappa(&app).await;
    let pappa = onboarding_session(&app, "pappa", &otp).await;
    app.request_form(
        Method::POST,
        "/auth/change-password",
        Some(&pappa),
        &[
            ("current_password", otp.as_str()),
            ("new_password", PAPPAS_OWN),
            ("confirm_password", PAPPAS_OWN),
        ],
    )
    .await;
    let (a, b) = tokio::join!(
        app.get_page("/auth/setup-2fa", &pappa),
        app.get_page("/auth/setup-2fa", &pappa)
    );
    let secret_of =
        |page: &TestResponse| part(&page.text(), "class=\"secret-key\"", "</p>").to_string();
    assert_eq!(secret_of(&a), secret_of(&b));
    let secret = account(&app, "pappa").await.totp_secret.unwrap();

    let (tx, task) = held(&app, "/auth/setup-2fa", &pappa).await;
    let mamma = account(&app, "parent").await;
    let pappa_id = account(&app, "pappa").await.id;
    crate::parents::remove_parent(&app.db, &mamma, pappa_id)
        .await
        .unwrap();
    let code = security::totp_for_secret(&secret, "pappa").generate(crate::admin_auth::now_secs());
    let answer = release(tx, task, &format!("code={code}")).await;
    assert_eq!(location(&answer), Some("/login"));
    let row: (bool, Option<String>, String) = sqlx::query_as(
        "SELECT totp_enabled, totp_secret, password_hash FROM admin_users WHERE id = ?",
    )
    .bind(pappa_id)
    .fetch_one(&app.db)
    .await
    .unwrap();
    assert_eq!(row, (false, None, "!removed".to_string()));
}

// ---- Step 2 ---------------------------------------------------------------------------------

/// Add asks for your password: a wrong one adds no one, says so under the field with the form open
/// (400, at #parents) and is logged; the third in a row signs you out. A bad username is refused
/// before any password is checked.
#[tokio::test]
async fn add_needs_the_password() {
    let app = TestApp::new().await;
    let mamma = app.admin_cookie().await;
    let page = app.get_page("/account", &mamma).await.text();
    check_forms("/account", &page);
    assert!(page.contains("<details class=\"edit cell\" id=\"add-parent\">"));
    let new_username = tags_named(&page, "new_username");
    assert!(
        new_username.contains("autocomplete=\"off\""),
        "{new_username}"
    );

    let res = add(&app, &mamma, "pa:ppa", "Pappa", "wrong").await;
    assert_eq!(res.status, StatusCode::BAD_REQUEST);
    assert!(res.text().contains("Use 1-32 lowercase letters"));
    assert!(events(&app, "parent_change_refused").await.is_empty());

    for last_but_one in [false, true] {
        let res = add(&app, &mamma, "pappa", "Pappa", "wrong").await;
        assert_eq!(res.status, StatusCode::BAD_REQUEST);
        let html = res.text();
        assert!(html.contains("<details class=\"edit cell\" id=\"add-parent\" open>"));
        let field = part(&html, "id=\"add_password\"", "</p>");
        assert!(field.contains("aria-invalid=\"true\"") && field.contains("No one was added."));
        assert_eq!(
            field.contains("One more wrong one signs you out."),
            last_but_one
        );
        assert!(
            html.contains("value=\"pappa\"") && !html.contains("value=\"wrong\""),
            "what was typed is kept, never the password"
        );
    }
    let res = add(&app, &mamma, "pappa", "Pappa", "wrong").await;
    assert_eq!(res.location(), Some("/login"));
    assert_eq!(
        app.get_page("/account", &mamma).await.location(),
        Some("/login")
    );
    let count: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM admin_users")
        .fetch_one(&app.db)
        .await
        .unwrap();
    assert_eq!(count, 1);
    assert_eq!(
        events(&app, "parent_change_refused").await,
        vec![Some("add".to_string()); 3]
    );
}

/// The start tag of the input named `name`.
fn tags_named(html: &str, name: &str) -> String {
    let at = html.find(&format!("name=\"{name}\"")).unwrap();
    let start = html[..at].rfind('<').unwrap();
    html[start..start + html[start..].find('>').unwrap() + 1].to_string()
}

/// The one-time password is in Add's answer only: not in the database (only its Argon2 hash),
/// the security log, the reload's GET or a header; the answer is no-store and loads reveal.js.
#[tokio::test]
async fn the_otp_is_shown_once_and_never_stored() {
    let app = TestApp::new().await;
    let mamma = app.admin_cookie().await;
    let res = add(&app, &mamma, " Pappa ", " Pappa ", PASSWORD).await;
    assert_eq!(res.status, StatusCode::OK);
    assert_eq!(res.headers.get(header::CACHE_CONTROL).unwrap(), "no-store");
    let html = res.text();
    assert!(html.contains("<body data-post-answer"));
    assert!(html.contains("<script src=\"/static/reveal.js?v=2\"></script>"));
    let otp = otp_of(&html);
    assert_eq!(otp.len(), 24, "{otp}");
    let block = part(&html, "id=\"parent-otp\"", "</section>");
    assert!(block.contains("sign in as pappa") && block.contains("It works until "));
    // Lowercased and trimmed; named as typed, trimmed.
    let pappa = account(&app, "pappa").await;
    assert_eq!(pappa.name(), "Pappa");
    assert!(security::verify_password(&otp, &pappa.password_hash));
    assert!(pappa.must_change_password && !pappa.totp_enabled);
    assert_eq!(pappa.invited_by, Some(account(&app, "parent").await.id));
    let left: i64 = sqlx::query_scalar(
        "SELECT CAST((julianday(invite_expires_at) - julianday('now')) * 24 + 0.5 AS INTEGER) \
         FROM admin_users WHERE username = 'pappa'",
    )
    .fetch_one(&app.db)
    .await
    .unwrap();
    assert_eq!(left, 72);
    assert_eq!(
        events(&app, "parent_added").await,
        vec![Some("pappa (Pappa)".to_string())]
    );
    let logged: i64 = sqlx::query_scalar(
        "SELECT COUNT(*) FROM security_events WHERE instr(COALESCE(detail, '') || username, ?) > 0",
    )
    .bind(&otp)
    .fetch_one(&app.db)
    .await
    .unwrap();
    assert_eq!(logged, 0);
    // reveal.js turns the answer into a GET of its path: the page, without it.
    let reload = app.get_page("/account/parents", &mamma).await;
    assert_eq!(reload.status, StatusCode::OK);
    assert!(!reload.text().contains(&otp) && !reload.text().contains("secret-once"));
    let rows = part(&reload.text(), "<section id=\"parents\"", "</section>").to_string();
    assert!(rows.contains("pappa · Waiting · until "), "{rows}");
    assert!(rows.contains("parent · You"), "{rows}");
}

/// The invitee's first sign-in is the existing onboarding: the one-time password (logged so, and
/// shown on the waiting row), a new password, then two-factor login - which ends the expiry, and
/// the row reads "Signed in".
#[tokio::test]
async fn invitee_onboards_with_the_otp() {
    let app = TestApp::new().await;
    let (mamma, otp) = mamma_adds_pappa(&app).await;
    let pappa = onboarding_session(&app, "pappa", &otp).await;
    assert_eq!(
        events(&app, "login_success")
            .await
            .last()
            .unwrap()
            .as_deref(),
        Some("one-time password")
    );
    let rows = app.get_page("/account", &mamma).await.text();
    let row = part(&rows, "data-parent=\"pappa\"", "</div>");
    assert!(
        row.contains("Waiting · until ")
            && row.contains(" · signed in ")
            && row.contains("from 127.0.0.1"),
        "{row}"
    );
    assert_eq!(
        app.get_page("/devices", &pappa).await.location(),
        Some("/auth/change-password")
    );
    let res = app
        .request_form(
            Method::POST,
            "/auth/change-password",
            Some(&pappa),
            &[
                ("current_password", otp.as_str()),
                ("new_password", PAPPAS_OWN),
                ("confirm_password", PAPPAS_OWN),
            ],
        )
        .await;
    assert_eq!(res.location(), Some("/"));
    let pappa = cookie_of(&res).unwrap_or(pappa);
    assert_eq!(
        app.get_page("/devices", &pappa).await.location(),
        Some("/auth/setup-2fa")
    );
    app.get_page("/auth/setup-2fa", &pappa).await;
    assert!(account(&app, "pappa").await.invite_expires_at.is_some());
    let secret = account(&app, "pappa").await.totp_secret.unwrap();
    let code = app.next_totp_code("pappa", &secret).await;
    let res = app
        .request_form(
            Method::POST,
            "/auth/setup-2fa",
            Some(&pappa),
            &[("code", &code)],
        )
        .await;
    assert_eq!(res.location(), Some("/"));
    let ready = account(&app, "pappa").await;
    assert!(ready.ready() && ready.invite_expires_at.is_none());
    assert_eq!(
        app.get_page("/devices", &pappa).await.status,
        StatusCode::OK
    );
    // A full sign-in as Pappa, then Mamma's row for him.
    let res = login(&app, "pappa", PAPPAS_OWN).await;
    let pending = cookie_of(&res).unwrap();
    let code = app.next_totp_code("pappa", &secret).await;
    app.request_form(
        Method::POST,
        "/auth/verify-2fa",
        Some(&pending),
        &[("code", &code)],
    )
    .await;
    let rows = app.get_page("/account", &mamma).await.text();
    let row = part(&rows, "data-parent=\"pappa\"", "</div>");
    assert!(
        row.contains("Pappa<span class=\"row-sub\">pappa · Signed in "),
        "{row}"
    );
    assert!(
        !rows.contains("id=\"withdraw-"),
        "a ready row has no Withdraw"
    );
}

/// Reset's answer (step 3) reloads as a GET of its path: Account, never a 405.
#[tokio::test]
async fn reset_answer_reloads_as_get() {
    let app = TestApp::new().await;
    let mamma = app.admin_cookie().await;
    let res = app.get_page("/account/parents/2/reset", &mamma).await;
    assert_eq!(res.status, StatusCode::OK);
    assert!(res.text().contains("id=\"parents\""));
}

/// Withdraw is the soft Remove behind your password: the row stays (its name taken, in any letter
/// case - qa-62-revision-check N2), the one-time password and the invitee's session die. Removing
/// yourself as the only ready parent is refused.
#[tokio::test]
async fn withdraw_keeps_the_name_taken() {
    let app = TestApp::new().await;
    let (mamma, otp) = mamma_adds_pappa(&app).await;
    let pappa_session = onboarding_session(&app, "pappa", &otp).await;
    let pappa = account(&app, "pappa").await;
    let page = app.get_page("/account", &mamma).await.text();
    check_forms("/account", &page);
    let withdraw = format!("/account/parents/{}/remove", pappa.id);
    assert!(page.contains(&format!("action=\"{withdraw}#parents\"")));

    let res = app
        .request_form(
            Method::POST,
            &withdraw,
            Some(&mamma),
            &[("current_password", "wrong")],
        )
        .await;
    assert_eq!(res.status, StatusCode::BAD_REQUEST);
    let html = res.text();
    assert!(html.contains(&format!(
        "<details class=\"confirm cell\" id=\"withdraw-{}\" open>",
        pappa.id
    )));
    assert!(
        part(
            &html,
            &format!("id=\"withdraw_password_{}\"", pappa.id),
            "</p>"
        )
        .contains("Nothing was changed.")
    );
    assert_eq!(
        events(&app, "parent_change_refused").await,
        vec![Some("remove pappa".to_string())]
    );

    let res = app
        .request_form(
            Method::POST,
            &withdraw,
            Some(&mamma),
            &[("current_password", PASSWORD)],
        )
        .await;
    assert_redirect(&res, "/account?parents=removed", Some("parents"));
    app.assert_lands(&res, &mamma).await;
    let row: (Option<String>, String, Option<String>) = sqlx::query_as(
        "SELECT removed_at, password_hash, invite_expires_at FROM admin_users WHERE id = ?",
    )
    .bind(pappa.id)
    .fetch_one(&app.db)
    .await
    .unwrap();
    assert!(
        row.0.is_some() && row.1 == "!removed" && row.2.is_none(),
        "{row:?}"
    );
    assert_eq!(
        events(&app, "parent_removed").await,
        vec![Some("pappa".to_string())]
    );
    assert_eq!(
        app.get_page("/devices", &pappa_session).await.location(),
        Some("/login")
    );
    assert!(
        login(&app, "pappa", &otp)
            .await
            .text()
            .contains("Incorrect username or password.")
    );
    let page = app.get_page("/account", &mamma).await.text();
    assert!(
        !page.contains("data-parent=\"pappa\""),
        "a removed row isn't listed"
    );
    // Again: already gone.
    let res = app
        .request_form(
            Method::POST,
            &withdraw,
            Some(&mamma),
            &[("current_password", PASSWORD)],
        )
        .await;
    assert_redirect(&res, "/account?parents=gone", Some("parents"));

    // The name stays taken, in any letter case.
    for taken in ["pappa", "PAPPA"] {
        let res = add(&app, &mamma, taken, "", PASSWORD).await;
        assert_eq!(res.status, StatusCode::BAD_REQUEST);
        assert!(
            part(&res.text(), "id=\"new_username\"", "</p>").contains("That username is taken.")
        );
    }
    sqlx::query("UPDATE admin_users SET username = 'Mamma' WHERE username = 'parent'")
        .execute(&app.db)
        .await
        .unwrap();
    // (Mamma's session still works: the id is what it holds.)
    let res = add(&app, &mamma, "mamma", "", PASSWORD).await;
    assert!(
        res.text().contains("That username is taken."),
        "{}",
        res.status
    );

    // The only ready parent can't remove itself.
    let me = account(&app, "Mamma").await;
    let res = app
        .request_form(
            Method::POST,
            &format!("/account/parents/{}/remove", me.id),
            Some(&mamma),
            &[("current_password", PASSWORD)],
        )
        .await;
    assert_eq!(res.status, StatusCode::BAD_REQUEST);
    assert!(
        res.text()
            .contains("You are the only parent who can sign in - add another parent first.")
    );
}

/// At most 8 accounts that aren't removed.
#[tokio::test]
async fn cap_of_eight() {
    let app = TestApp::new().await;
    let mamma = app.admin_cookie().await;
    for n in 2..=8 {
        sqlx::query("INSERT INTO admin_users (username, password_hash) VALUES (?, 'x')")
            .bind(format!("parent{n}"))
            .execute(&app.db)
            .await
            .unwrap();
    }
    let res = add(&app, &mamma, "ninth", "", PASSWORD).await;
    assert_eq!(res.status, StatusCode::BAD_REQUEST);
    assert!(res.text().contains("A family can have at most 8 parents."));
    sqlx::query("UPDATE admin_users SET removed_at = datetime('now') WHERE username = 'parent8'")
        .execute(&app.db)
        .await
        .unwrap();
    assert_eq!(
        add(&app, &mamma, "ninth", "", PASSWORD).await.status,
        StatusCode::OK
    );
}

/// Your name: saved with post/redirect/get at #name and logged; a refused one under its field
/// with what was typed; empty reads as the username.
#[tokio::test]
async fn display_name_saves_at_its_group() {
    let app = TestApp::new().await;
    let mamma = app.admin_cookie().await;
    let res = app
        .request_form(
            Method::POST,
            "/account/name",
            Some(&mamma),
            &[("display_name", " Mamma ")],
        )
        .await;
    assert_redirect(&res, "/account?changed=name", Some("name"));
    app.assert_lands(&res, &mamma).await;
    assert_eq!(account(&app, "parent").await.name(), "Mamma");
    let page = app.get_page("/account?changed=name", &mamma).await.text();
    assert!(part(&page, "<section id=\"name\"", "</section>").contains("Name saved."));
    assert!(
        part(&page, "data-parent=\"parent\"", "</div>")
            .contains("Mamma<span class=\"row-sub\">parent · You")
    );
    assert_eq!(
        events(&app, "display_name_changed").await,
        vec![Some("Mamma".to_string())]
    );

    let res = app
        .request_form(
            Method::POST,
            "/account/name",
            Some(&mamma),
            &[("display_name", "Mam\u{202E}ma")],
        )
        .await;
    assert_eq!(res.status, StatusCode::BAD_REQUEST);
    let field = part(&res.text(), "id=\"display_name\"", "</p>").to_string();
    assert!(
        field.contains("aria-invalid=\"true\"") && field.contains("Use letters"),
        "{field}"
    );
    assert_eq!(account(&app, "parent").await.name(), "Mamma");

    app.request_form(
        Method::POST,
        "/account/name",
        Some(&mamma),
        &[("display_name", "")],
    )
    .await;
    assert_eq!(account(&app, "parent").await.name(), "parent");
}
