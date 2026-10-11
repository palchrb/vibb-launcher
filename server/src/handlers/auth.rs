use askama::Template;
use axum::extract::{ConnectInfo, State};
use axum::http::{HeaderMap, StatusCode};
use axum::response::{Html, IntoResponse, Redirect};
use axum::{Extension, Form};
use serde::Deserialize;
use std::net::SocketAddr;
use tower_sessions::Session;

use crate::models::AdminUser;
use crate::security::{self, CurrentAdmin};
use crate::{AppState, admin_auth};

const BANNED_MESSAGE: &str =
    "Too many failed attempts from your network. Try again in about an hour.";

#[derive(Template)]
#[template(path = "login.html")]
struct LoginTemplate {
    title: String,
    error: Option<String>,
}

pub async fn login_form() -> impl IntoResponse {
    render_login(None)
}

#[derive(Deserialize)]
pub struct LoginForm {
    username: String,
    password: String,
}

fn render_login(error: Option<String>) -> Html<String> {
    Html(
        LoginTemplate {
            title: "Sign in".to_string(),
            error,
        }
        .render()
        .unwrap(),
    )
}

fn locked_message(minutes: i64) -> String {
    let wait = if minutes >= 120 {
        format!("about {} hours", (minutes + 59) / 60)
    } else {
        format!("about {minutes} minutes")
    };
    format!(
        "This account is locked after too many wrong two-factor codes. Try again in {wait}. If \
         that wasn't you, your password is known: change it after signing in."
    )
}

/// The password step (design 22 §3.2.1): the per-IP ban as before (on the resolved client's key),
/// argon2 off the async workers behind `AppState.passwords` (429 when four are already waiting),
/// a dummy verify for an unknown username, and no account lock for wrong passwords. A correct
/// password on an account the 2FA lock holds says so (whoever sees that knows the password). The
/// session id is cycled, and the pending state lasts `admin_auth::PENDING_SECS`.
pub async fn login(
    State(state): State<AppState>,
    session: Session,
    ConnectInfo(addr): ConnectInfo<SocketAddr>,
    headers: HeaderMap,
    Form(form): Form<LoginForm>,
) -> axum::response::Response {
    let ip = state.net.client(&headers, addr.ip()).key();

    if security::is_ip_banned(&state.db, &ip).await {
        return (
            StatusCode::TOO_MANY_REQUESTS,
            render_login(Some(BANNED_MESSAGE.to_string())),
        )
            .into_response();
    }

    let admin = sqlx::query_as::<_, AdminUser>("SELECT * FROM admin_users WHERE username = ?")
        .bind(&form.username)
        .fetch_optional(&state.db)
        .await
        .ok()
        .flatten();

    let valid = match state
        .passwords
        .verify(
            &form.password,
            admin.as_ref().map(|a| a.password_hash.as_str()),
        )
        .await
    {
        Ok(valid) => valid,
        Err(admin_auth::Busy) => return admin_auth::busy_response(),
    };

    let Some(admin) = admin.filter(|_| valid) else {
        security::record_security_event(
            &state.db,
            "login_failed",
            Some(&form.username),
            Some(&ip),
            None,
        )
        .await;
        security::check_and_ban_ip_if_needed(&state.db, &ip).await;
        return render_login(Some("Incorrect username or password.".to_string())).into_response();
    };

    if let Some(minutes) = admin_auth::lock_minutes_left(&state.db, admin.id).await {
        security::record_security_event(
            &state.db,
            "login_locked",
            Some(&admin.username),
            Some(&ip),
            Some("correct password while the account is locked"),
        )
        .await;
        return render_login(Some(locked_message(minutes))).into_response();
    }

    session.cycle_id().await.ok();
    if admin.totp_enabled {
        // Password is correct, but 2FA is enrolled - hold the login in a "pending" state
        // (deliberately NOT "admin_id") until a fresh TOTP code also passes, so every login
        // requires both factors.
        session
            .insert(admin_auth::PENDING_ID_KEY, admin.id)
            .await
            .ok();
        session
            .insert(admin_auth::PENDING_AT_KEY, admin_auth::now_secs())
            .await
            .ok();
        Redirect::to("/auth/verify-2fa").into_response()
    } else {
        // Not yet enrolled in 2FA - password alone is the full login so this
        // IS the success event; require_full_auth routes them through
        // mandatory setup next.
        security::record_security_event(
            &state.db,
            "login_success",
            Some(&admin.username),
            Some(&ip),
            None,
        )
        .await;
        session.insert("admin_id", admin.id).await.ok();
        session
            .insert(admin_auth::EPOCH_KEY, admin.session_epoch)
            .await
            .ok();
        Redirect::to("/").into_response()
    }
}

#[derive(Template)]
#[template(path = "verify_2fa.html")]
struct Verify2faTemplate {
    title: String,
    error: Option<String>,
}

/// The pending admin id, while the password step is at most `admin_auth::PENDING_SECS` old.
async fn pending_admin(session: &Session) -> Option<i64> {
    let id: i64 = session
        .get(admin_auth::PENDING_ID_KEY)
        .await
        .ok()
        .flatten()?;
    let at: u64 = session
        .get(admin_auth::PENDING_AT_KEY)
        .await
        .ok()
        .flatten()?;
    admin_auth::pending_fresh(at, admin_auth::now_secs()).then_some(id)
}

const PENDING_EXPIRED: &str = "Sign-in timed out - enter your password again.";

pub async fn verify_2fa_form(session: Session) -> impl IntoResponse {
    if pending_admin(&session).await.is_none() {
        session.flush().await.ok();
        return Redirect::to("/login").into_response();
    }
    render_verify(None).into_response()
}

fn render_verify(error: Option<String>) -> Html<String> {
    Html(
        Verify2faTemplate {
            title: "Verify your code".to_string(),
            error,
        }
        .render()
        .unwrap(),
    )
}

#[derive(Deserialize)]
pub struct Verify2faForm {
    code: String,
}

/// The TOTP step (design 22 §3.2.1): a code for a later step than the last one used
/// (`admin_auth::check_totp`); failures count and lock (`admin_auth::record_totp_failure`), and
/// only this complete login resets them. Then the session id is cycled again, the session stores
/// the account's epoch, and a banner names earlier failed codes (`admin_auth::login_warning`).
pub async fn verify_2fa(
    State(state): State<AppState>,
    session: Session,
    ConnectInfo(addr): ConnectInfo<SocketAddr>,
    headers: HeaderMap,
    Form(form): Form<Verify2faForm>,
) -> axum::response::Response {
    let ip = state.net.client(&headers, addr.ip()).key();

    if security::is_ip_banned(&state.db, &ip).await {
        return (
            StatusCode::TOO_MANY_REQUESTS,
            render_verify(Some(BANNED_MESSAGE.to_string())),
        )
            .into_response();
    }

    let Some(admin_id) = pending_admin(&session).await else {
        session.flush().await.ok();
        return render_login(Some(PENDING_EXPIRED.to_string())).into_response();
    };

    let admin = sqlx::query_as::<_, AdminUser>("SELECT * FROM admin_users WHERE id = ?")
        .bind(admin_id)
        .fetch_optional(&state.db)
        .await
        .ok()
        .flatten();

    let Some(admin) = admin else {
        session.flush().await.ok();
        return Redirect::to("/login").into_response();
    };

    if let Some(minutes) = admin_auth::lock_minutes_left(&state.db, admin.id).await {
        session.flush().await.ok();
        return render_login(Some(locked_message(minutes))).into_response();
    }

    let Some(secret) = admin.totp_secret.clone() else {
        session.flush().await.ok();
        return Redirect::to("/login").into_response();
    };

    // Copying a code from a phone's authenticator app commonly brings along a trailing space or
    // newline (`check_totp` trims it).
    let checked = admin_auth::check_totp(
        &state.db,
        admin.id,
        &secret,
        &admin.username,
        &form.code,
        admin_auth::now_secs(),
    )
    .await;
    match checked {
        Ok(admin_auth::Totp::Accepted) => {}
        Ok(admin_auth::Totp::Refused) => {
            admin_auth::record_totp_failure(&state.db, admin.id).await;
            security::record_security_event(
                &state.db,
                "totp_failed",
                Some(&admin.username),
                Some(&ip),
                None,
            )
            .await;
            security::check_and_ban_ip_if_needed(&state.db, &ip).await;
            if let Some(minutes) = admin_auth::lock_minutes_left(&state.db, admin.id).await {
                session.flush().await.ok();
                return render_login(Some(locked_message(minutes))).into_response();
            }
            return render_verify(Some("That code didn't match. Try again.".to_string()))
                .into_response();
        }
        Err(err) => {
            tracing::error!(%err, "couldn't check the 2FA code");
            return StatusCode::INTERNAL_SERVER_ERROR.into_response();
        }
    }

    // Read before the reset: the failures since the last complete login.
    let failures: i64 =
        sqlx::query_scalar("SELECT failed_login_attempts FROM admin_users WHERE id = ?")
            .bind(admin.id)
            .fetch_one(&state.db)
            .await
            .unwrap_or(0);
    let warning = admin_auth::login_warning(&state.db, &admin.username, failures).await;
    admin_auth::reset_after_login(&state.db, admin.id).await;
    security::record_security_event(
        &state.db,
        "login_success",
        Some(&admin.username),
        Some(&ip),
        None,
    )
    .await;

    session.cycle_id().await.ok();
    session.remove::<i64>(admin_auth::PENDING_ID_KEY).await.ok();
    session.remove::<u64>(admin_auth::PENDING_AT_KEY).await.ok();
    session.insert("admin_id", admin.id).await.ok();
    session
        .insert(admin_auth::EPOCH_KEY, admin.session_epoch)
        .await
        .ok();
    if let Some(warning) = warning {
        session
            .insert(admin_auth::LOGIN_WARNING_KEY, warning)
            .await
            .ok();
    }
    Redirect::to("/").into_response()
}

pub async fn logout(session: Session) -> impl IntoResponse {
    session.flush().await.ok();
    Redirect::to("/login")
}

#[derive(Template)]
#[template(path = "change_password.html")]
struct ChangePasswordTemplate {
    title: String,
    error: Option<String>,
}

pub async fn change_password_form(
    Extension(CurrentAdmin(admin)): Extension<CurrentAdmin>,
) -> impl IntoResponse {
    if !admin.must_change_password {
        return Redirect::to("/").into_response();
    }
    Html(
        ChangePasswordTemplate {
            title: "Change password".to_string(),
            error: None,
        }
        .render()
        .unwrap(),
    )
    .into_response()
}

#[derive(Deserialize)]
pub struct ChangePasswordForm {
    current_password: String,
    new_password: String,
    confirm_password: String,
}

pub async fn change_password(
    State(state): State<AppState>,
    session: Session,
    Extension(CurrentAdmin(admin)): Extension<CurrentAdmin>,
    Form(form): Form<ChangePasswordForm>,
) -> impl IntoResponse {
    let render_error = |msg: &str| -> axum::response::Response {
        Html(
            ChangePasswordTemplate {
                title: "Change password".to_string(),
                error: Some(msg.to_string()),
            }
            .render()
            .unwrap(),
        )
        .into_response()
    };

    if form.new_password.len() < security::MIN_PASSWORD_LEN {
        return render_error("New password must be at least 12 characters.");
    }
    if form.new_password != form.confirm_password {
        return render_error("New passwords don't match.");
    }
    let new_hash = match state
        .passwords
        .change(
            &form.current_password,
            &admin.password_hash,
            &form.new_password,
        )
        .await
    {
        Ok(Some(hash)) => hash,
        Ok(None) => return render_error("Current password is incorrect."),
        Err(admin_auth::Busy) => return admin_auth::busy_response(),
    };

    sqlx::query("UPDATE admin_users SET password_hash = ?, must_change_password = 0 WHERE id = ?")
        .bind(&new_hash)
        .bind(admin.id)
        .execute(&state.db)
        .await
        .ok();
    signed_out_elsewhere(&state, &session, admin.id).await;
    // A generated first password (design 22 §6) is spent now.
    let _ = tokio::fs::remove_file(
        std::path::Path::new("data").join(crate::startup::INITIAL_PASSWORD_FILE),
    )
    .await;
    security::record_security_event(
        &state.db,
        "password_changed",
        Some(&admin.username),
        None,
        None,
    )
    .await;

    Redirect::to("/").into_response()
}

/// After a password change or a 2FA reset: every other session ends (design 22 QA a), this one
/// stays.
async fn signed_out_elsewhere(state: &AppState, session: &Session, admin_id: i64) {
    if let Some(epoch) = admin_auth::bump_epoch(&state.db, admin_id).await {
        session.insert(admin_auth::EPOCH_KEY, epoch).await.ok();
    }
}

#[derive(Template)]
#[template(path = "setup_2fa.html")]
struct Setup2faTemplate {
    title: String,
    qr_base64: String,
    secret_base32: String,
    error: Option<String>,
}

pub async fn setup_2fa_form(
    State(state): State<AppState>,
    Extension(CurrentAdmin(admin)): Extension<CurrentAdmin>,
) -> impl IntoResponse {
    if admin.totp_enabled {
        return Redirect::to("/").into_response();
    }

    let secret = match admin.totp_secret.clone() {
        Some(s) => s,
        None => {
            let s = security::generate_totp_secret_base32();
            sqlx::query("UPDATE admin_users SET totp_secret = ? WHERE id = ?")
                .bind(&s)
                .bind(admin.id)
                .execute(&state.db)
                .await
                .ok();
            s
        }
    };

    let totp = security::totp_for_secret(&secret, &admin.username);
    let qr_base64 = totp.get_qr_base64().expect("QR generation should not fail");

    Html(
        Setup2faTemplate {
            title: "Set up two-factor login".to_string(),
            qr_base64,
            secret_base32: secret,
            error: None,
        }
        .render()
        .unwrap(),
    )
    .into_response()
}

#[derive(Deserialize)]
pub struct Setup2faForm {
    code: String,
}

pub async fn setup_2fa_verify(
    State(state): State<AppState>,
    Extension(CurrentAdmin(admin)): Extension<CurrentAdmin>,
    Form(form): Form<Setup2faForm>,
) -> impl IntoResponse {
    let Some(secret) = admin.totp_secret.clone() else {
        return Redirect::to("/auth/setup-2fa").into_response();
    };
    let totp = security::totp_for_secret(&secret, &admin.username);

    // The same step rule as at login: a code used here can't be used again to sign in.
    let valid = matches!(
        admin_auth::check_totp(
            &state.db,
            admin.id,
            &secret,
            &admin.username,
            &form.code,
            admin_auth::now_secs(),
        )
        .await,
        Ok(admin_auth::Totp::Accepted)
    );
    if !valid {
        let qr_base64 = totp.get_qr_base64().expect("QR generation should not fail");
        return Html(
            Setup2faTemplate {
                title: "Set up two-factor login".to_string(),
                qr_base64,
                secret_base32: secret,
                error: Some("That code didn't match. Try again.".to_string()),
            }
            .render()
            .unwrap(),
        )
        .into_response();
    }

    sqlx::query("UPDATE admin_users SET totp_enabled = 1 WHERE id = ?")
        .bind(admin.id)
        .execute(&state.db)
        .await
        .ok();

    Redirect::to("/").into_response()
}

/// Voluntary account settings, reachable any time from the nav (unlike
/// `/auth/change-password` and `/auth/setup-2fa` above, which are only ever
/// reached as forced onboarding steps and bounce back to `/` once already
/// satisfied). Lets an admin change their password whenever they want, and
/// recover from a lost authenticator app by resetting 2FA and re-enrolling.
#[derive(Template)]
#[template(path = "account.html")]
struct AccountTemplate {
    title: String,
    username: String,
    totp_enabled: bool,
    error: Option<String>,
    success: Option<String>,
    login_warning: Option<String>,
}

async fn render_account(
    session: &Session,
    admin: &AdminUser,
    error: Option<String>,
    success: Option<String>,
) -> axum::response::Response {
    Html(
        AccountTemplate {
            title: "Account".to_string(),
            username: admin.username.clone(),
            totp_enabled: admin.totp_enabled,
            error,
            success,
            login_warning: login_warning_of(session).await,
        }
        .render()
        .unwrap(),
    )
    .into_response()
}

/// The banner after a sign-in that followed failed 2FA codes, until dismissed.
pub async fn login_warning_of(session: &Session) -> Option<String> {
    session
        .get::<String>(admin_auth::LOGIN_WARNING_KEY)
        .await
        .ok()
        .flatten()
}

pub async fn account_page(
    session: Session,
    Extension(CurrentAdmin(admin)): Extension<CurrentAdmin>,
) -> impl IntoResponse {
    render_account(&session, &admin, None, None).await
}

#[derive(Deserialize)]
pub struct UpdatePasswordForm {
    current_password: String,
    new_password: String,
    confirm_password: String,
}

pub async fn update_account_password(
    State(state): State<AppState>,
    session: Session,
    Extension(CurrentAdmin(admin)): Extension<CurrentAdmin>,
    Form(form): Form<UpdatePasswordForm>,
) -> impl IntoResponse {
    if form.new_password.len() < security::MIN_PASSWORD_LEN {
        return render_account(
            &session,
            &admin,
            Some("New password must be at least 12 characters.".to_string()),
            None,
        )
        .await;
    }
    if form.new_password != form.confirm_password {
        return render_account(
            &session,
            &admin,
            Some("New passwords don't match.".to_string()),
            None,
        )
        .await;
    }
    let new_hash = match state
        .passwords
        .change(
            &form.current_password,
            &admin.password_hash,
            &form.new_password,
        )
        .await
    {
        Ok(Some(hash)) => hash,
        Ok(None) => {
            return render_account(
                &session,
                &admin,
                Some("Current password is incorrect.".to_string()),
                None,
            )
            .await;
        }
        Err(admin_auth::Busy) => return admin_auth::busy_response(),
    };

    sqlx::query("UPDATE admin_users SET password_hash = ? WHERE id = ?")
        .bind(&new_hash)
        .bind(admin.id)
        .execute(&state.db)
        .await
        .ok();
    signed_out_elsewhere(&state, &session, admin.id).await;
    // The warning said to change the password: done.
    session
        .remove::<String>(admin_auth::LOGIN_WARNING_KEY)
        .await
        .ok();

    security::record_security_event(
        &state.db,
        "password_changed",
        Some(&admin.username),
        None,
        Some("every other session signed out"),
    )
    .await;

    render_account(
        &session,
        &admin,
        None,
        Some("Password changed. Every other session is signed out.".to_string()),
    )
    .await
}

/// Clears the stored TOTP secret and drops `totp_enabled`, which sends the
/// admin straight back through mandatory 2FA setup (`require_full_auth`
/// redirects there for any account with `totp_enabled = false`) with a
/// brand-new QR code - the recovery path for a lost/uninstalled
/// authenticator app while still signed in. Every other session ends. (Signed out and locked
/// out: `kid_phone_server reset-2fa <user>`.)
pub async fn reset_totp(
    State(state): State<AppState>,
    session: Session,
    Extension(CurrentAdmin(admin)): Extension<CurrentAdmin>,
) -> impl IntoResponse {
    sqlx::query(
        "UPDATE admin_users SET totp_secret = NULL, totp_enabled = 0, totp_last_step = NULL \
         WHERE id = ?",
    )
    .bind(admin.id)
    .execute(&state.db)
    .await
    .ok();
    signed_out_elsewhere(&state, &session, admin.id).await;

    security::record_security_event(
        &state.db,
        "totp_reset",
        Some(&admin.username),
        None,
        Some("every other session signed out"),
    )
    .await;

    Redirect::to("/auth/setup-2fa")
}

/// "Sign out everywhere" (design 22 §3.2.2): every session of this account ends, this one too.
pub async fn sign_out_everywhere(
    State(state): State<AppState>,
    session: Session,
    Extension(CurrentAdmin(admin)): Extension<CurrentAdmin>,
) -> impl IntoResponse {
    admin_auth::bump_epoch(&state.db, admin.id).await;
    security::record_security_event(
        &state.db,
        "signed_out_everywhere",
        Some(&admin.username),
        None,
        None,
    )
    .await;
    session.flush().await.ok();
    Redirect::to("/login")
}

/// Hides the failed-2FA banner (it stays in the security log).
pub async fn dismiss_login_warning(session: Session) -> impl IntoResponse {
    session
        .remove::<String>(admin_auth::LOGIN_WARNING_KEY)
        .await
        .ok();
    Redirect::to("/devices")
}
