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
    assert!(html.contains("<script src=\"/static/reveal.js?v=3\"></script>"));
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
    // A ready row's actions read Reset… and Remove… (step 3), never Withdraw….
    let actions = part(&rows, "data-parent=\"pappa\"", "</section>");
    assert!(
        actions.contains("Reset…</summary>") && actions.contains("Remove…</summary>"),
        "{actions}"
    );
    assert!(!actions.contains("Withdraw…"));
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
        "<details class=\"confirm cell\" id=\"remove-{}\" open>",
        pappa.id
    )));
    assert!(
        part(
            &html,
            &format!("id=\"remove_password_{}\"", pappa.id),
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
    // The shell's restore isn't held to the cap (P62-3), and says so.
    let message = crate::admin_auth::reset_2fa(&app.db, "parent8", app.state.data_dir.as_path())
        .await
        .unwrap();
    assert!(message.contains("now has 9 parents"), "{message}");
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

// ---- The code QA's guards (qa-62-step12-code P62-1, P62-2) ----------------------------------

/// P62-1: a session on the one-time password can't set up 2FA before its password change, so the
/// expiry can't be cleared while the one-time password still works.
#[tokio::test]
async fn otp_session_sets_the_password_before_2fa() {
    let app = TestApp::new().await;
    let (_, otp) = mamma_adds_pappa(&app).await;
    let pappa = onboarding_session(&app, "pappa", &otp).await;
    assert_eq!(
        app.get_page("/auth/setup-2fa", &pappa).await.location(),
        Some("/auth/change-password")
    );
    // A secret from an older page, and a right code for it: still the password first.
    sqlx::query("UPDATE admin_users SET totp_secret = ? WHERE username = 'pappa'")
        .bind(SECRET)
        .execute(&app.db)
        .await
        .unwrap();
    let code = app.next_totp_code("pappa", SECRET).await;
    let res = app
        .request_form(
            Method::POST,
            "/auth/setup-2fa",
            Some(&pappa),
            &[("code", &code)],
        )
        .await;
    assert_eq!(res.location(), Some("/auth/change-password"));
    let row = account(&app, "pappa").await;
    assert!(row.must_change_password && !row.totp_enabled);
    assert!(row.invite_expires_at.is_some());
}

/// N1: a removed account's session ends even without the epoch moving (a restore, a bug).
#[tokio::test]
async fn removed_session_without_epoch_bump_is_out() {
    let app = TestApp::new().await;
    app.admin_cookie().await;
    let pappa = app.admin_cookie_as("pappa", "Pappa").await;
    sqlx::query("UPDATE admin_users SET removed_at = datetime('now') WHERE username = 'pappa'")
        .execute(&app.db)
        .await
        .unwrap();
    assert_eq!(
        app.get_page("/devices", &pappa).await.location(),
        Some("/login")
    );
}

/// A removed actor's open session adds and renames nothing.
#[tokio::test]
async fn removed_actor_session_post_writes_nothing() {
    let app = TestApp::new().await;
    let mamma = app.admin_cookie().await;
    app.admin_cookie_as("pappa", "Pappa").await;
    let me = account(&app, "parent").await;
    let pappa = account(&app, "pappa").await;
    assert_eq!(
        crate::parents::remove_parent(&app.db, &pappa, me.id)
            .await
            .unwrap(),
        crate::parents::Removed::Done
    );
    assert_eq!(
        add(&app, &mamma, "third", "", PASSWORD).await.location(),
        Some("/login")
    );
    let res = app
        .request_form(
            Method::POST,
            "/account/name",
            Some(&mamma),
            &[("display_name", "X")],
        )
        .await;
    assert_eq!(res.location(), Some("/login"));
    let n: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM admin_users")
        .fetch_one(&app.db)
        .await
        .unwrap();
    assert_eq!(n, 2);
    assert_eq!(account(&app, "parent").await.display_name, None);
}

/// The actor condition in Add's and Remove's statements: a stale epoch writes nothing.
#[tokio::test]
async fn stale_actor_statements_write_nothing() {
    let app = TestApp::new().await;
    mamma_adds_pappa(&app).await;
    let mut mamma = account(&app, "parent").await;
    let pappa = account(&app, "pappa").await;
    mamma.session_epoch -= 1;
    assert_eq!(
        crate::parents::add_parent(&app.db, &mamma, "third", None, "x")
            .await
            .unwrap(),
        crate::parents::Added::SignedOut
    );
    assert_eq!(
        crate::parents::remove_parent(&app.db, &mamma, pappa.id)
            .await
            .unwrap(),
        crate::parents::Removed::SignedOut
    );
    let removed: Option<String> =
        sqlx::query_scalar("SELECT removed_at FROM admin_users WHERE username = 'pappa'")
            .fetch_one(&app.db)
            .await
            .unwrap();
    assert!(removed.is_none());
    let n: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM admin_users")
        .fetch_one(&app.db)
        .await
        .unwrap();
    assert_eq!(n, 2);
}

/// Remove deletes the removed account's push subscriptions, and only those.
#[tokio::test]
async fn remove_deletes_push_subscriptions() {
    let app = TestApp::new().await;
    mamma_adds_pappa(&app).await;
    let mamma = account(&app, "parent").await;
    let pappa = account(&app, "pappa").await;
    for (who, n) in [(pappa.id, 1), (mamma.id, 2)] {
        sqlx::query(
            "INSERT INTO push_subscriptions \
             (admin_id, endpoint, p256dh, auth, origin, label, vapid_fp, state) \
             VALUES (?, ?, x'00', x'00', 'https://pwa.example', 'Test', 'fp', 'active')",
        )
        .bind(who)
        .bind(format!("https://push.example/{n}"))
        .execute(&app.db)
        .await
        .unwrap();
    }
    crate::parents::remove_parent(&app.db, &mamma, pappa.id)
        .await
        .unwrap();
    let left: Vec<i64> = sqlx::query_scalar("SELECT admin_id FROM push_subscriptions")
        .fetch_all(&app.db)
        .await
        .unwrap();
    assert_eq!(left, vec![mamma.id]);
}

/// Q62-2 for the 2FA reset: held past another parent's Reset, it writes nothing.
#[tokio::test]
async fn held_reset_totp_cant_undo_a_reset() {
    let app = TestApp::new().await;
    app.admin_cookie().await;
    let pappa = app.admin_cookie_as("pappa", "Pappa").await;
    let (tx, task) = held(&app, "/account/reset-2fa", &pappa).await;
    reset_by_sql(&app, "pappa").await;
    // A secret on the row to see whether the held request clears it.
    sqlx::query("UPDATE admin_users SET totp_secret = 'KEEP' WHERE username = 'pappa'")
        .execute(&app.db)
        .await
        .unwrap();
    let answer = release(tx, task, "current_password=correct+horse+battery+staple").await;
    assert_eq!(location(&answer), Some("/login"));
    assert_eq!(
        account(&app, "pappa").await.totp_secret.as_deref(),
        Some("KEEP")
    );
}

// ---- Step 3: Reset and Remove (design 62 §4.2, §4.3) ----------------------------------------

/// A push subscription of `username`'s, as the PWA leaves one.
async fn a_subscription(app: &TestApp, username: &str, n: i64) {
    sqlx::query(
        "INSERT INTO push_subscriptions \
         (admin_id, endpoint, p256dh, auth, origin, label, vapid_fp, state) \
         SELECT id, ?, x'00', x'00', 'https://pwa.example', 'Test', 'fp', 'active' \
         FROM admin_users WHERE username = ?",
    )
    .bind(format!("https://push.example/{n}"))
    .bind(username)
    .execute(&app.db)
    .await
    .unwrap();
}

async fn subscriptions_of(app: &TestApp, username: &str) -> i64 {
    sqlx::query_scalar(
        "SELECT COUNT(*) FROM push_subscriptions s JOIN admin_users a ON a.id = s.admin_id \
         WHERE a.username = ?",
    )
    .bind(username)
    .fetch_one(&app.db)
    .await
    .unwrap()
}

async fn reset(app: &TestApp, cookie: &str, id: i64, pw: &str) -> TestResponse {
    app.request_form(
        Method::POST,
        &format!("/account/parents/{id}/reset"),
        Some(cookie),
        &[("current_password", pw)],
    )
    .await
}

async fn remove(app: &TestApp, cookie: &str, id: i64, pw: &str) -> TestResponse {
    app.request_form(
        Method::POST,
        &format!("/account/parents/{id}/remove"),
        Some(cookie),
        &[("current_password", pw)],
    )
    .await
}

/// Reset behind your password: a wrong one changes nothing (its confirm open, the reason under
/// it); a right one shows a new one-time password once, ends the parent's sessions and pushes,
/// and onboards them again. The answer reloads as the GET of its path.
#[tokio::test]
async fn reset_shows_a_new_otp_and_ends_the_sessions() {
    let app = TestApp::new().await;
    let mamma = app.admin_cookie().await;
    let pappa_session = app.admin_cookie_as("pappa", "Pappa").await;
    a_subscription(&app, "pappa", 1).await;
    let pappa = account(&app, "pappa").await;
    let page = app.get_page("/account", &mamma).await.text();
    check_forms("/account", &page);
    assert!(page.contains(&format!(
        "action=\"/account/parents/{}/reset#parents\"",
        pappa.id
    )));

    let res = reset(&app, &mamma, pappa.id, "wrong").await;
    assert_eq!(res.status, StatusCode::BAD_REQUEST);
    let html = res.text();
    assert!(html.contains(&format!(
        "<details class=\"confirm cell\" id=\"reset-{}\" open>",
        pappa.id
    )));
    assert!(
        part(
            &html,
            &format!("id=\"reset_password_{}\"", pappa.id),
            "</p>"
        )
        .contains("Nothing was changed.")
    );
    assert_eq!(
        account(&app, "pappa").await.password_hash,
        pappa.password_hash
    );
    assert_eq!(
        events(&app, "parent_change_refused").await,
        vec![Some("reset pappa".to_string())]
    );

    // A Spotify login Pappa's browser started can't finish after the Reset (P62b-3).
    app.state
        .spotify
        .start_login("pappa", "client", "https://x.example/cb");
    let res = reset(&app, &mamma, pappa.id, PASSWORD).await;
    assert_eq!(res.status, StatusCode::OK);
    assert_eq!(app.state.spotify.pending_count(), 0);
    let html = res.text();
    let otp = otp_of(&html);
    assert!(html.contains("New one-time password for Pappa"), "{html}");
    assert_eq!(
        events(&app, "parent_reset").await,
        vec![Some("pappa".to_string())]
    );
    let logged: i64 = sqlx::query_scalar(
        "SELECT COUNT(*) FROM security_events WHERE instr(COALESCE(detail, ''), ?) > 0",
    )
    .bind(&otp)
    .fetch_one(&app.db)
    .await
    .unwrap();
    assert_eq!(logged, 0);
    assert_eq!(
        app.get_page("/devices", &pappa_session).await.location(),
        Some("/login")
    );
    assert_eq!(subscriptions_of(&app, "pappa").await, 0);
    let reset_row = account(&app, "pappa").await;
    assert!(reset_row.must_change_password && !reset_row.totp_enabled);
    assert!(reset_row.invite_expires_at.is_some());
    // The reload of the answer: Account, without it; the row is waiting again.
    let reload = app
        .get_page(&format!("/account/parents/{}/reset", pappa.id), &mamma)
        .await;
    assert_eq!(reload.status, StatusCode::OK);
    assert!(!reload.text().contains(&otp));
    let row = part(&reload.text(), "data-parent=\"pappa\"", "</section>").to_string();
    assert!(row.contains("Waiting · until ") && row.contains("New One-Time Password…"));
    assert!(row.contains("Withdraw…"), "{row}");
    // The old password is gone; the new one onboards.
    assert!(
        login(&app, "pappa", PASSWORD)
            .await
            .text()
            .contains("Incorrect username or password.")
    );
    let again = onboarding_session(&app, "pappa", &otp).await;
    assert_eq!(
        app.get_page("/devices", &again).await.location(),
        Some("/auth/change-password")
    );
    // Your own account isn't reset here.
    let me = account(&app, "parent").await;
    let res = reset(&app, &mamma, me.id, PASSWORD).await;
    assert_eq!(res.status, StatusCode::BAD_REQUEST);
    assert!(res.text().contains("Your own account"));
}

/// Q62-1: Withdraw on a reset parent is the same soft Remove - the row, its name and history stay.
#[tokio::test]
async fn withdraw_a_reset_parent_keeps_the_row() {
    let app = TestApp::new().await;
    let mamma = app.admin_cookie().await;
    app.admin_cookie_as("pappa", "Pappa").await;
    let pappa = account(&app, "pappa").await;
    assert_eq!(
        reset(&app, &mamma, pappa.id, PASSWORD).await.status,
        StatusCode::OK
    );
    let res = remove(&app, &mamma, pappa.id, PASSWORD).await;
    assert_redirect(&res, "/account?parents=removed", Some("parents"));
    let row: (Option<String>, String) =
        sqlx::query_as("SELECT removed_at, username FROM admin_users WHERE id = ?")
            .bind(pappa.id)
            .fetch_one(&app.db)
            .await
            .unwrap();
    assert!(row.0.is_some() && row.1 == "pappa");
}

/// Remove ends the parent's sessions, pushes, Notify Me About, read state and Spotify login in
/// progress; what they did keeps their name ("· Pappa" on the phone's Activity).
#[tokio::test]
async fn remove_ends_sessions_and_push_keeps_history() {
    let app = TestApp::new().await;
    let mamma = app.admin_cookie().await;
    let pappa_session = app.admin_cookie_as("pappa", "Pappa").await;
    let (id, _) = app.enrolled_device("Anna").await;
    let res = app
        .request_form(
            Method::POST,
            &format!("/devices/{id}/command/ring"),
            Some(&pappa_session),
            &[],
        )
        .await;
    assert!(res.status.is_redirection(), "{}", res.status);
    a_subscription(&app, "pappa", 1).await;
    let pappa = account(&app, "pappa").await;
    sqlx::query("INSERT INTO notification_prefs (admin_id, push_low_battery) VALUES (?, 0)")
        .bind(pappa.id)
        .execute(&app.db)
        .await
        .unwrap();
    app.state
        .spotify
        .start_login("pappa", "client", "https://x.example/cb");
    app.state
        .spotify
        .start_login("parent", "client", "https://x.example/cb");

    let res = remove(&app, &mamma, pappa.id, PASSWORD).await;
    assert_redirect(&res, "/account?parents=removed", Some("parents"));
    assert_eq!(
        app.get_page("/devices", &pappa_session).await.location(),
        Some("/login")
    );
    assert_eq!(subscriptions_of(&app, "pappa").await, 0);
    let left: (i64, i64) = sqlx::query_as(
        "SELECT (SELECT COUNT(*) FROM notification_prefs WHERE admin_id = ?1), \
                (SELECT COUNT(*) FROM parent_event_reads WHERE admin_id = ?1)",
    )
    .bind(pappa.id)
    .fetch_one(&app.db)
    .await
    .unwrap();
    assert_eq!(left, (0, 0));
    assert_eq!(
        app.state.spotify.pending_count(),
        1,
        "only Mamma's login is left"
    );
    let summary = app.get_page(&format!("/devices/{id}"), &mamma).await.text();
    let ring = part(&summary, "data-command=\"ring\"", "</div>");
    assert!(ring.contains(" · Pappa · "), "{ring}");
    assert_eq!(
        events(&app, "parent_removed").await,
        vec![Some("pappa".to_string())]
    );
}

/// The two last ready parents remove each other at once: one wins, one ready parent is left - in
/// both orders (the guard is the statement; qa-62-step345-code P62b-4).
#[tokio::test]
async fn remove_each_other_leaves_one() {
    for mamma_first in [true, false] {
        let app = TestApp::new().await;
        app.admin_cookie().await;
        app.admin_cookie_as("pappa", "Pappa").await;
        let (mamma, pappa) = (account(&app, "parent").await, account(&app, "pappa").await);
        let mammas = crate::parents::remove_parent(&app.db, &mamma, pappa.id);
        let pappas = crate::parents::remove_parent(&app.db, &pappa, mamma.id);
        let (a, b) = if mamma_first {
            tokio::join!(mammas, pappas)
        } else {
            let (b, a) = tokio::join!(pappas, mammas);
            (a, b)
        };
        let done = [a.unwrap(), b.unwrap()]
            .into_iter()
            .filter(|r| *r == crate::parents::Removed::Done)
            .count();
        assert_eq!(done, 1, "mamma first: {mamma_first}");
        let ready: i64 = sqlx::query_scalar(
            "SELECT COUNT(*) FROM admin_users WHERE removed_at IS NULL \
             AND must_change_password = 0 AND totp_enabled = 1",
        )
        .fetch_one(&app.db)
        .await
        .unwrap();
        assert_eq!(ready, 1, "mamma first: {mamma_first}");
    }
}

/// Mamma resets Pappa while Pappa removes Mamma: exactly one of them happens, in both orders.
#[tokio::test]
async fn reset_vs_remove_one_wins() {
    for reset_first in [true, false] {
        let app = TestApp::new().await;
        app.admin_cookie().await;
        app.admin_cookie_as("pappa", "Pappa").await;
        let (mamma, pappa) = (account(&app, "parent").await, account(&app, "pappa").await);
        let reset = crate::parents::reset_parent(&app.db, &mamma, pappa.id, "otp-hash");
        let remove = crate::parents::remove_parent(&app.db, &pappa, mamma.id);
        let (reset, removed) = if reset_first {
            tokio::join!(reset, remove)
        } else {
            let (removed, reset) = tokio::join!(remove, reset);
            (reset, removed)
        };
        let reset_done = matches!(reset.unwrap(), crate::parents::Reset::Done { .. });
        let removed_done = removed.unwrap() == crate::parents::Removed::Done;
        assert!(
            reset_done != removed_done,
            "reset first {reset_first}: {reset_done} {removed_done}"
        );
        let (mamma_removed, pappa_hash): (bool, String) = sqlx::query_as(
            "SELECT (SELECT removed_at IS NOT NULL FROM admin_users WHERE id = ?), \
                    (SELECT password_hash FROM admin_users WHERE id = ?)",
        )
        .bind(mamma.id)
        .bind(pappa.id)
        .fetch_one(&app.db)
        .await
        .unwrap();
        assert_eq!(mamma_removed, removed_done);
        assert_eq!(pappa_hash == "otp-hash", reset_done);
    }
}

/// The same pairs one after the other, in both orders: whichever commits first wins, the other
/// writes nothing (qa-62-step345-code P62b-4; the joins above may interleave either way).
#[tokio::test]
async fn the_pairs_in_both_orders() {
    for reset_first in [true, false] {
        let app = TestApp::new().await;
        app.admin_cookie().await;
        app.admin_cookie_as("pappa", "Pappa").await;
        let (mamma, pappa) = (account(&app, "parent").await, account(&app, "pappa").await);
        if reset_first {
            let reset = crate::parents::reset_parent(&app.db, &mamma, pappa.id, "otp-hash");
            assert!(matches!(
                reset.await.unwrap(),
                crate::parents::Reset::Done { .. }
            ));
            let removed = crate::parents::remove_parent(&app.db, &pappa, mamma.id).await;
            assert_eq!(removed.unwrap(), crate::parents::Removed::SignedOut);
        } else {
            let removed = crate::parents::remove_parent(&app.db, &pappa, mamma.id).await;
            assert_eq!(removed.unwrap(), crate::parents::Removed::Done);
            let reset = crate::parents::reset_parent(&app.db, &mamma, pappa.id, "otp-hash");
            assert_eq!(reset.await.unwrap(), crate::parents::Reset::SignedOut);
            assert_ne!(account(&app, "pappa").await.password_hash, "otp-hash");
        }
    }
    for mamma_first in [true, false] {
        let app = TestApp::new().await;
        app.admin_cookie().await;
        app.admin_cookie_as("pappa", "Pappa").await;
        let (mamma, pappa) = (account(&app, "parent").await, account(&app, "pappa").await);
        let (first, second) = if mamma_first {
            ((&mamma, pappa.id), (&pappa, mamma.id))
        } else {
            ((&pappa, mamma.id), (&mamma, pappa.id))
        };
        let a = crate::parents::remove_parent(&app.db, first.0, first.1).await;
        assert_eq!(a.unwrap(), crate::parents::Removed::Done);
        let b = crate::parents::remove_parent(&app.db, second.0, second.1).await;
        assert_eq!(b.unwrap(), crate::parents::Removed::SignedOut);
    }
}

/// Mamma's Add, held past her removal by Pappa, adds no one.
#[tokio::test]
async fn held_request_of_a_removed_actor_changes_nothing() {
    let app = TestApp::new().await;
    let mamma_session = app.admin_cookie().await;
    app.admin_cookie_as("pappa", "Pappa").await;
    let (tx, task) = held(&app, "/account/parents", &mamma_session).await;
    let (mamma, pappa) = (account(&app, "parent").await, account(&app, "pappa").await);
    assert_eq!(
        crate::parents::remove_parent(&app.db, &pappa, mamma.id)
            .await
            .unwrap(),
        crate::parents::Removed::Done
    );
    let answer = release(
        tx,
        task,
        "new_username=oma&name=Oma&current_password=correct+horse+battery+staple",
    )
    .await;
    assert_eq!(location(&answer), Some("/login"));
    let n: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM admin_users")
        .fetch_one(&app.db)
        .await
        .unwrap();
    assert_eq!(n, 2);
}

// ---- Step 4: "by <name>" (design 62 §6) -------------------------------------------------------

/// A command says who asked for it once the family has two accounts, on Locate's Requests and the
/// summary's Activity; never with one, and never in the phone's policy.
#[tokio::test]
async fn command_by_shows_with_two_accounts() {
    let app = TestApp::new().await;
    let mamma = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("Anna").await;
    let ring = format!("/devices/{id}/command/ring");
    app.request_form(Method::POST, &ring, Some(&mamma), &[])
        .await;
    let locate = format!("/devices/locate?device={id}");
    let row = |html: &str| part(html, "data-command=\"ring\"", "</div>").to_string();
    let one = app.get_page(&locate, &mamma).await.text();
    assert!(!row(&one).contains(" · parent"), "{}", row(&one));

    let pappa = app.admin_cookie_as("pappa", "Pappa").await;
    app.request_form(Method::POST, &ring, Some(&pappa), &[])
        .await;
    let two = app.get_page(&locate, &mamma).await.text();
    assert!(row(&two).contains(" · Pappa · "), "{}", row(&two));
    let summary = app.get_page(&format!("/devices/{id}"), &mamma).await.text();
    assert!(row(&summary).contains(" · Pappa · "), "{}", row(&summary));

    // The phone's pending command is the same four keys as before: no requester.
    let policy = app
        .request(Method::GET, "/api/devices/policy", Some(&token), None)
        .await
        .json();
    let mut keys: Vec<&str> = policy["pending_command"]
        .as_object()
        .unwrap()
        .keys()
        .map(String::as_str)
        .collect();
    keys.sort();
    assert_eq!(keys, ["command", "id", "requested_at"]);
    assert!(!policy.to_string().contains("requested_by"));
}

/// The security log and a lift name a parent by display name: "Mamma (parent)", "· Mamma".
#[tokio::test]
async fn security_log_shows_display_names() {
    let app = TestApp::new().await;
    let mamma = app.admin_cookie().await;
    app.request_form(
        Method::POST,
        "/account/name",
        Some(&mamma),
        &[("display_name", "Mamma")],
    )
    .await;
    let log = app.get_page("/security", &mamma).await.text();
    assert!(log.contains("Mamma (parent)"), "{log}");
    let names = crate::parents::Names::load(&app.db).await.unwrap();
    assert_eq!(names.of_username("parent"), "Mamma");
    assert_eq!(names.of_username("nobody"), "nobody");
    assert_eq!(names.requester(Some(1)), None, "one account: no \"by\"");
}

// ---- Step 5: Notify Me About and the inbox per parent (design 62 §5) --------------------------

/// The bell and the inbox's read state are each parent's: Mamma reading the inbox leaves Pappa's
/// count, and a parent added later starts at zero.
#[tokio::test]
async fn inbox_read_is_per_parent() {
    let app = TestApp::new().await;
    let mamma = app.admin_cookie().await;
    let pappa = app.admin_cookie_as("pappa", "Pappa").await;
    let (id, _) = app.enrolled_device("Anna").await;
    let anna = crate::parent_events::Phone::load(&app.db, id)
        .await
        .unwrap();
    for count in [1, 2] {
        crate::parent_events::raise_logged(&app.state, &anna.unseen_boot(count, None)).await;
    }
    let unread = |who: &'static str| {
        let db = app.db.clone();
        async move {
            let id: i64 = sqlx::query_scalar("SELECT id FROM admin_users WHERE username = ?")
                .bind(who)
                .fetch_one(&db)
                .await
                .unwrap();
            crate::parent_events::unread_count(&db, id).await.unwrap()
        }
    };
    let events: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM parent_events")
        .fetch_one(&app.db)
        .await
        .unwrap();
    assert!(events > 0);
    assert_eq!(
        (unread("parent").await, unread("pappa").await),
        (events, events)
    );
    app.get_page("/notifications", &mamma).await;
    assert_eq!((unread("parent").await, unread("pappa").await), (0, events));
    let devices = app.get_page("/devices", &pappa).await.text();
    assert!(
        devices.contains(&format!(">{events}<")),
        "Pappa's bell still counts"
    );
    // Mark All as Read is Pappa's alone, too.
    app.request_form(Method::POST, "/notifications/read-all", Some(&pappa), &[])
        .await;
    assert_eq!(unread("pappa").await, 0);
    // A parent added now has nothing unread.
    let res = add(&app, &mamma, "oma", "Oma", PASSWORD).await;
    assert_eq!(res.status, StatusCode::OK);
    assert_eq!(unread("oma").await, 0);
}

/// Notify Me About is each parent's own: Pappa's switch off leaves Mamma's on; a page reads its
/// own; no row reads as every switch on.
#[tokio::test]
async fn notify_me_about_is_each_parents() {
    let app = TestApp::new().await;
    let mamma = app.admin_cookie().await;
    let pappa = app.admin_cookie_as("pappa", "Pappa").await;
    let res = app
        .request_form(
            Method::POST,
            "/notifications/settings",
            Some(&pappa),
            &[("push_pin_used", "on")],
        )
        .await;
    assert_redirect(&res, "/notifications/settings?from=devices", Some("events"));
    let mine = app.get_page("/notifications/settings", &pappa).await.text();
    assert_eq!(
        super::input_checked(&mine, "push_low_battery", "on"),
        Some(false)
    );
    assert!(mine.contains("Just for you - each parent chooses their own."));
    let hers = app.get_page("/notifications/settings", &mamma).await.text();
    assert_eq!(
        super::input_checked(&hers, "push_low_battery", "on"),
        Some(true)
    );
}

/// 0088 copies the family's switches and read state to every account that isn't removed, and
/// drops the old table and column.
#[tokio::test]
async fn migration_0088_copies_to_active_accounts() {
    let dir = tempfile::tempdir().unwrap();
    let options = sqlx::sqlite::SqliteConnectOptions::new()
        .filename(dir.path().join("m.db"))
        .create_if_missing(true)
        .foreign_keys(true);
    let db = sqlx::SqlitePool::connect_with(options).await.unwrap();
    for migration in crate::MIGRATOR.iter().filter(|m| m.version <= 87) {
        sqlx::raw_sql(&migration.sql).execute(&db).await.unwrap();
    }
    sqlx::raw_sql(
        "INSERT INTO admin_users (id, username, password_hash) VALUES (1, 'mamma', 'x'), \
         (2, 'pappa', 'x'); \
         INSERT INTO admin_users (id, username, password_hash, removed_at) \
           VALUES (3, 'gone', '!removed', datetime('now')); \
         UPDATE notification_settings SET push_low_battery = 0, push_phone_on = 0; \
         INSERT INTO devices (id, name) VALUES (1, 'Anna'); \
         INSERT INTO parent_events (id, device_id, kind, dedup_key, title, summary, target, \
           happened_at, read_at) VALUES \
           (1, 1, 'low_battery', 'a', 't', 's', '/', '2026-10-22T10:00:00.000Z', datetime('now')), \
           (2, 1, 'low_battery', 'b', 't', 's', '/', '2026-10-22T11:00:00.000Z', NULL);",
    )
    .execute(&db)
    .await
    .unwrap();
    let last = crate::MIGRATOR.iter().find(|m| m.version == 88).unwrap();
    sqlx::raw_sql(&last.sql).execute(&db).await.unwrap();
    let prefs: Vec<(i64, bool, bool, bool)> = sqlx::query_as(
        "SELECT admin_id, push_low_battery, push_phone_on, push_pin_used FROM notification_prefs \
         ORDER BY admin_id",
    )
    .fetch_all(&db)
    .await
    .unwrap();
    assert_eq!(prefs, [(1, false, false, true), (2, false, false, true)]);
    let reads: Vec<(i64, i64)> =
        sqlx::query_as("SELECT admin_id, event_id FROM parent_event_reads ORDER BY admin_id")
            .fetch_all(&db)
            .await
            .unwrap();
    assert_eq!(reads, [(1, 1), (2, 1)]);
    let old: i64 = sqlx::query_scalar(
        "SELECT COUNT(*) FROM sqlite_master WHERE name = 'notification_settings'",
    )
    .fetch_one(&db)
    .await
    .unwrap();
    assert_eq!(old, 0);
    assert!(
        sqlx::query("SELECT read_at FROM parent_events")
            .fetch_all(&db)
            .await
            .is_err()
    );
}

// ---- The code QA's guards (qa-62-step345-code P62b-4) ------------------------------------------

async fn unread_of(app: &TestApp, who: &str) -> i64 {
    let id = account(app, who).await.id;
    crate::parent_events::unread_count(&app.db, id)
        .await
        .unwrap()
}

/// Mamma, Pappa and two events neither has read: (Mamma's cookie, Pappa's, the newer event).
async fn two_with_events(app: &TestApp) -> (String, String, i64) {
    let mamma = app.admin_cookie().await;
    let pappa = app.admin_cookie_as("pappa", "Pappa").await;
    let (id, _) = app.enrolled_device("Anna").await;
    let anna = crate::parent_events::Phone::load(&app.db, id)
        .await
        .unwrap();
    for count in [1, 2] {
        crate::parent_events::raise_logged(&app.state, &anna.unseen_boot(count, None)).await;
    }
    let event: i64 = sqlx::query_scalar("SELECT MAX(id) FROM parent_events")
        .fetch_one(&app.db)
        .await
        .unwrap();
    (mamma, pappa, event)
}

/// A row's tap reads it for the parent who tapped, nobody else.
#[tokio::test]
async fn open_is_each_parents() {
    let app = TestApp::new().await;
    let (mamma, _, event) = two_with_events(&app).await;
    let before = unread_of(&app, "pappa").await;
    assert!(before > 0);
    app.request_form(
        Method::POST,
        &format!("/notifications/{event}/open"),
        Some(&mamma),
        &[],
    )
    .await;
    assert_eq!(unread_of(&app, "parent").await, before - 1);
    assert_eq!(unread_of(&app, "pappa").await, before);
}

/// Mark All as Read is the parent's own.
#[tokio::test]
async fn read_all_is_each_parents() {
    let app = TestApp::new().await;
    let (mamma, _, _) = two_with_events(&app).await;
    let before = unread_of(&app, "pappa").await;
    assert!(before > 0);
    app.request_form(Method::POST, "/notifications/read-all", Some(&mamma), &[])
        .await;
    assert_eq!(unread_of(&app, "parent").await, 0);
    assert_eq!(unread_of(&app, "pappa").await, before);
}

/// A Reset by a stale actor (held past its own Reset, removal or sign-out) writes nothing.
#[tokio::test]
async fn stale_actor_reset_writes_nothing() {
    let app = TestApp::new().await;
    app.admin_cookie().await;
    app.admin_cookie_as("pappa", "Pappa").await;
    let mut mamma = account(&app, "parent").await;
    let pappa = account(&app, "pappa").await;
    mamma.session_epoch -= 1;
    assert_eq!(
        crate::parents::reset_parent(&app.db, &mamma, pappa.id, "otp-hash")
            .await
            .unwrap(),
        crate::parents::Reset::SignedOut
    );
    assert_eq!(
        account(&app, "pappa").await.password_hash,
        pappa.password_hash
    );
}

/// Remove drops the parent's read state.
#[tokio::test]
async fn remove_drops_reads() {
    let app = TestApp::new().await;
    let (_, pappa, _) = two_with_events(&app).await;
    app.get_page("/notifications", &pappa).await;
    let (mamma, pappa) = (account(&app, "parent").await, account(&app, "pappa").await);
    let reads = || async {
        sqlx::query_scalar::<_, i64>("SELECT COUNT(*) FROM parent_event_reads WHERE admin_id = ?")
            .bind(pappa.id)
            .fetch_one(&app.db)
            .await
            .unwrap()
    };
    assert!(reads().await > 0);
    assert_eq!(
        crate::parents::remove_parent(&app.db, &mamma, pappa.id)
            .await
            .unwrap(),
        crate::parents::Removed::Done
    );
    assert_eq!(reads().await, 0);
}

/// A request with a session cookie and an optional `Accept-Language`.
async fn get_in(app: &TestApp, uri: &str, cookie: &str, accept: Option<&str>) -> TestResponse {
    let mut request = axum::http::Request::builder()
        .uri(uri)
        .header(header::COOKIE, cookie);
    if let Some(accept) = accept {
        request = request.header(header::ACCEPT_LANGUAGE, accept);
    }
    app.send(request.body(axum::body::Body::empty()).unwrap())
        .await
}

/// Design 70 §3, §8: each parent's own language. NULL until the first signed-in request stores its
/// `Accept-Language` (once; never `?lang=`), `?lang=` wins for one request, the Account row saves it (a save in the
/// log, back at #language in the new language), a bad one is a 400.
#[tokio::test]
async fn language_is_each_parents() {
    let app = TestApp::new().await;
    let mamma = app.admin_cookie().await;
    let pappa = app.admin_cookie_as("pappa", "Pappa").await;
    let stored = |username: &'static str| {
        let app = &app;
        async move { account(app, username).await.lang }
    };
    // Both NULL, as an account from before 0104 (signing in isn't a signed-in request).
    sqlx::query("UPDATE admin_users SET lang = NULL")
        .execute(&app.db)
        .await
        .unwrap();

    // `?lang=` is this request's alone: Mamma's first request stores her browser's language, not it.
    let page = get_in(&app, "/account?lang=en", &mamma, Some("nb-NO,nb;q=0.9")).await;
    assert!(page.text().contains(r#"<html lang="en">"#));
    assert_eq!(stored("parent").await.as_deref(), Some("nb"));
    // Pappa's browser says English; a later request in another language writes nothing.
    get_in(&app, "/devices", &pappa, Some("en-GB")).await;
    get_in(&app, "/devices", &pappa, Some("nb")).await;
    assert_eq!(stored("pappa").await.as_deref(), Some("en"));
    get_in(&app, "/devices", &mamma, Some("en")).await;
    assert_eq!(stored("parent").await.as_deref(), Some("nb"));

    // Each parent's page in their own language, whatever the browser says; `?lang=` still wins once.
    let nb = get_in(&app, "/account", &mamma, Some("en")).await.text();
    assert!(nb.contains(r#"<html lang="nb">"#) && nb.contains("Konto og foreldre"));
    let row = part(&nb, "<section id=\"language\"", "</section>");
    assert!(row.contains("Språk"), "{row}");
    assert!(
        row.contains(r#"<option value="nb" lang="nb" selected>Norsk</option>"#),
        "{row}"
    );
    assert!(
        row.contains(r#"<option value="en" lang="en">English</option>"#),
        "{row}"
    );
    assert!(super::autosaves(row, "lang"), "{row}");
    check_forms("/account", &nb);
    let en = get_in(&app, "/account", &pappa, Some("nb")).await.text();
    assert!(en.contains(r#"<html lang="en">"#) && en.contains("Account &#38; Parents"));
    assert!(en.contains(r#"<option value="en" lang="en" selected>English</option>"#));
    let once = get_in(&app, "/devices?lang=nb", &pappa, None).await.text();
    assert!(once.contains(r#"<html lang="nb">"#));
    assert_eq!(stored("pappa").await.as_deref(), Some("en"));
    // An unknown `?lang=` is ignored.
    let ignored = get_in(&app, "/account?lang=zz", &pappa, None).await.text();
    assert!(ignored.contains(r#"<html lang="en">"#));

    // Pappa chooses Norwegian: back to the group, now in Norwegian, and a save in the log.
    let saves = || async {
        sqlx::query_scalar::<_, i64>(
            "SELECT COUNT(*) FROM security_events WHERE event_type = 'settings_saved' \
             AND username = 'pappa' AND detail LIKE '%/account/language%'",
        )
        .fetch_one(&app.db)
        .await
        .unwrap()
    };
    let res = app
        .request_form(
            Method::POST,
            "/account/language",
            Some(&pappa),
            &[("lang", "nb")],
        )
        .await;
    assert_redirect(&res, "/account", Some("language"));
    app.assert_lands(&res, &pappa).await;
    assert_eq!(stored("pappa").await.as_deref(), Some("nb"));
    assert_eq!(saves().await, 1);
    let back = get_in(&app, "/account", &pappa, Some("en")).await.text();
    assert!(back.contains(r#"<html lang="nb">"#) && back.contains("Konto og foreldre"));
    // Mamma stays Norwegian; her choice is hers.
    assert_eq!(stored("parent").await.as_deref(), Some("nb"));

    // A language this server doesn't have: 400 in the page's language, nothing written.
    for bad in ["zz", "", "NB"] {
        let res = app
            .request_form(
                Method::POST,
                "/account/language",
                Some(&pappa),
                &[("lang", bad)],
            )
            .await;
        assert_eq!(res.status, StatusCode::BAD_REQUEST, "{bad:?}");
        assert_eq!(res.text(), "Ikke et språk denne serveren har.");
    }
    assert_eq!(stored("pappa").await.as_deref(), Some("nb"));

    // The CSRF refusal, before any session, in the browser's language.
    let request = axum::http::Request::builder()
        .method(Method::POST)
        .uri("/account/language")
        .header(header::COOKIE, &pappa)
        .header(header::ACCEPT_LANGUAGE, "nb")
        .header("sec-fetch-site", "cross-site")
        .header(header::CONTENT_TYPE, "application/x-www-form-urlencoded")
        .body(axum::body::Body::from("lang=en"))
        .unwrap();
    let refused = app.send(request).await;
    assert_eq!(refused.status, StatusCode::FORBIDDEN);
    assert_eq!(
        refused.text(),
        "Avvist: dette skjemaet ble sendt fra et annet nettsted."
    );
    assert_eq!(stored("pappa").await.as_deref(), Some("nb"));
}
