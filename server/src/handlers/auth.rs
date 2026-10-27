use askama::Template;
use axum::extract::{ConnectInfo, State};
use axum::http::{HeaderMap, StatusCode};
use axum::response::{Html, IntoResponse, Redirect};
use axum::{Extension, Form};
use serde::Deserialize;
use std::net::SocketAddr;
use tower_sessions::Session;

use crate::i18n::{Lang, Words, t, t_count};
use crate::models::AdminUser;
use crate::security::{self, CurrentAdmin};
use crate::{AppState, admin_auth};

/// Usernames in the security log are cut to this many characters (security review M7).
const MAX_LOGGED_USERNAME: usize = 64;

const BANNED_MESSAGE: &str =
    "Too many failed attempts from your network. Try again in about an hour.";

/// A right password past the one-time password's 72 hours (design 62 §4.1; it names no one, and
/// fits an invitee who already set their own password - qa-62-revision-check N5).
pub(crate) const INVITE_EXPIRED: &str = "This invitation has expired - ask for a new one.";

#[derive(Template)]
#[template(path = "login.html")]
struct LoginTemplate {
    lang: Lang,
    title: String,
    error: Option<String>,
}
crate::i18n::words!(LoginTemplate);

pub async fn login_form(lang: Lang) -> impl IntoResponse {
    render_login(None, lang)
}

#[derive(Deserialize)]
pub struct LoginForm {
    username: String,
    password: String,
}

fn render_login(error: Option<String>, lang: Lang) -> Html<String> {
    Html(
        LoginTemplate {
            lang,
            title: "Sign In".to_string(),
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
    lang: Lang,
    session: Session,
    ConnectInfo(addr): ConnectInfo<SocketAddr>,
    headers: HeaderMap,
    Form(form): Form<LoginForm>,
) -> axum::response::Response {
    let ip = state.net.client(&headers, addr.ip()).key();

    // Earlier attempts count before this one runs, also those whose client hung up mid-verify
    // (their row stays: QA 22 M1).
    security::check_and_ban_ip_if_needed(&state.db, &ip).await;
    if security::is_ip_banned(&state.db, &ip).await {
        return (
            StatusCode::TOO_MANY_REQUESTS,
            render_login(Some(BANNED_MESSAGE.to_string()), lang),
        )
            .into_response();
    }

    // A removed account reads as an unknown name: the dummy hash, the same message (design 62 §8).
    let admin = sqlx::query_as::<_, AdminUser>(
        "SELECT * FROM admin_users WHERE username = ? AND removed_at IS NULL",
    )
    .bind(&form.username)
    .fetch_optional(&state.db)
    .await
    .ok()
    .flatten();

    // The attempt is recorded before argon2 runs and taken back only when the password is right:
    // a client that hangs up mid-verify still counts toward the ban (QA 22 M1).
    let attempt: Option<i64> = sqlx::query_scalar(
        "INSERT INTO security_events (event_type, username, ip_address) \
         VALUES ('login_failed', ?, ?) RETURNING id",
    )
    .bind(crate::audit::clip(&form.username, MAX_LOGGED_USERNAME))
    .bind(&ip)
    .fetch_one(&state.db)
    .await
    .ok();
    let take_back = || async {
        if let Some(id) = attempt {
            sqlx::query("DELETE FROM security_events WHERE id = ?")
                .bind(id)
                .execute(&state.db)
                .await
                .ok();
        }
    };

    let valid = match state
        .passwords
        .verify(
            &form.password,
            admin.as_ref().map(|a| a.password_hash.as_str()),
        )
        .await
    {
        Ok(valid) => valid,
        Err(admin_auth::Busy) => {
            take_back().await;
            return admin_auth::busy_response();
        }
    };

    let Some(admin) = admin.filter(|_| valid) else {
        security::check_and_ban_ip_if_needed(&state.db, &ip).await;
        return render_login(Some("Incorrect username or password.".to_string()), lang)
            .into_response();
    };

    take_back().await;
    // Only someone who knows the password learns that it ran out (design 62 §4.1).
    if admin_auth::invite_expired(&state.db, admin.id).await {
        security::record_security_event(
            &state.db,
            "login_expired",
            Some(&admin.username),
            Some(&ip),
            Some("correct password after the one-time password's 72 hours"),
        )
        .await;
        return render_login(Some(INVITE_EXPIRED.to_string()), lang).into_response();
    }
    if let Some(minutes) = admin_auth::lock_minutes_left(&state.db, admin.id).await {
        security::record_security_event(
            &state.db,
            "login_locked",
            Some(&admin.username),
            Some(&ip),
            Some("correct password while the account is locked"),
        )
        .await;
        return render_login(Some(locked_message(minutes)), lang).into_response();
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
        // The waiting row on Account shows this sign-in (design 62 §3, §8).
        security::record_security_event(
            &state.db,
            "login_success",
            Some(&admin.username),
            Some(&ip),
            admin.must_change_password.then_some("one-time password"),
        )
        .await;
        session.insert("admin_id", admin.id).await.ok();
        session
            .insert(admin_auth::SIGNED_IN_AT_KEY, admin_auth::now_secs())
            .await
            .ok();
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
    lang: Lang,
    title: String,
    error: Option<String>,
}
crate::i18n::words!(Verify2faTemplate);

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

pub async fn verify_2fa_form(lang: Lang, session: Session) -> impl IntoResponse {
    if pending_admin(&session).await.is_none() {
        session.flush().await.ok();
        return Redirect::to("/login").into_response();
    }
    render_verify(None, lang).into_response()
}

fn render_verify(error: Option<String>, lang: Lang) -> Html<String> {
    Html(
        Verify2faTemplate {
            lang,
            title: "Two-Factor Code".to_string(),
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
/// (`admin_auth::check_totp`); every attempt is counted before the code is checked and locks at 5 (`admin_auth::take_totp_attempt`), and
/// only this complete login resets them. Then the session id is cycled again, the session stores
/// the account's epoch, and a banner names earlier failed codes (`admin_auth::login_warning`).
pub async fn verify_2fa(
    State(state): State<AppState>,
    lang: Lang,
    session: Session,
    ConnectInfo(addr): ConnectInfo<SocketAddr>,
    headers: HeaderMap,
    Form(form): Form<Verify2faForm>,
) -> axum::response::Response {
    let ip = state.net.client(&headers, addr.ip()).key();

    if security::is_ip_banned(&state.db, &ip).await {
        return (
            StatusCode::TOO_MANY_REQUESTS,
            render_verify(Some(BANNED_MESSAGE.to_string()), lang),
        )
            .into_response();
    }

    let Some(admin_id) = pending_admin(&session).await else {
        session.flush().await.ok();
        return render_login(Some(PENDING_EXPIRED.to_string()), lang).into_response();
    };

    let admin = sqlx::query_as::<_, AdminUser>(
        "SELECT * FROM admin_users WHERE id = ? AND removed_at IS NULL",
    )
    .bind(admin_id)
    .fetch_optional(&state.db)
    .await
    .ok()
    .flatten();

    let Some(admin) = admin else {
        session.flush().await.ok();
        return Redirect::to("/login").into_response();
    };

    let Some(secret) = admin.totp_secret.clone() else {
        session.flush().await.ok();
        return Redirect::to("/login").into_response();
    };

    // The attempt is taken before the code is looked at (QA 22 H2): while the account is locked no
    // code is checked, and parallel requests can't slip past the count.
    let attempt = match admin_auth::take_totp_attempt(&state.db, admin.id).await {
        Ok(Some(attempt)) => attempt,
        Ok(None) => {
            let minutes = admin_auth::lock_minutes_left(&state.db, admin.id)
                .await
                .unwrap_or(1);
            session.flush().await.ok();
            return render_login(Some(locked_message(minutes)), lang).into_response();
        }
        Err(err) => {
            tracing::error!(%err, "couldn't count the 2FA attempt");
            return StatusCode::INTERNAL_SERVER_ERROR.into_response();
        }
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
                return render_login(Some(locked_message(minutes)), lang).into_response();
            }
            return render_verify(Some("That code didn't match. Try again.".to_string()), lang)
                .into_response();
        }
        Err(err) => {
            tracing::error!(%err, "couldn't check the 2FA code");
            return StatusCode::INTERNAL_SERVER_ERROR.into_response();
        }
    }

    // The failures since the last complete login: every attempt before this one.
    let failures = attempt - 1;
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
        .insert(admin_auth::SIGNED_IN_AT_KEY, admin_auth::now_secs())
        .await
        .ok();
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

/// Settings' Sign Out. The form may carry this browser's push subscription (`endpoint`, filled by
/// `static/register-sw.js`, which also unsubscribes the browser): that one row goes too, only when
/// it is the signed-in parent's (the user, 2026-10-25). Without it - no JS, no subscription, an
/// empty or missing field - the session ends as before and every subscription stays.
pub async fn logout(
    State(state): State<AppState>,
    session: Session,
    Extension(CurrentAdmin(admin)): Extension<CurrentAdmin>,
    body: axum::body::Bytes,
) -> impl IntoResponse {
    let endpoint = form_urlencoded::parse(&body)
        .find(|(name, _)| name == "endpoint")
        .map(|(_, value)| value.trim().to_string())
        .filter(|e| !e.is_empty() && e.len() <= 1024);
    if let Some(endpoint) = endpoint
        && let Err(err) =
            sqlx::query("DELETE FROM push_subscriptions WHERE endpoint = ? AND admin_id = ?")
                .bind(&endpoint)
                .bind(admin.id)
                .execute(&state.db)
                .await
    {
        tracing::error!(%err, "couldn't delete this browser's notification subscription");
    }
    session.flush().await.ok();
    security::sign_in_redirect(security::SignInWhy::SignOut)
}

#[derive(Template)]
#[template(path = "change_password.html")]
struct ChangePasswordTemplate {
    lang: Lang,
    title: String,
    error: Option<String>,
}
crate::i18n::words!(ChangePasswordTemplate);

pub async fn change_password_form(
    Extension(CurrentAdmin(admin)): Extension<CurrentAdmin>,
    lang: Lang,
) -> impl IntoResponse {
    if !admin.must_change_password {
        return Redirect::to("/").into_response();
    }
    Html(
        ChangePasswordTemplate {
            lang,
            title: "New Password".to_string(),
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
    lang: Lang,
    session: Session,
    Extension(CurrentAdmin(admin)): Extension<CurrentAdmin>,
    Form(form): Form<ChangePasswordForm>,
) -> impl IntoResponse {
    let render_error = |msg: &str| -> axum::response::Response {
        Html(
            ChangePasswordTemplate {
                lang,
                title: "New Password".to_string(),
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
    let changed = match state
        .passwords
        .change(
            &form.current_password,
            &admin.password_hash,
            &form.new_password,
        )
        .await
    {
        Ok(changed) => changed,
        Err(admin_auth::Busy) => return admin_auth::busy_response(),
    };
    // Three wrong in a row end the session (design 59 §2.2's one re-auth rule); each wrong one is
    // in the security log (security review L7).
    if changed.is_none() {
        security::record_security_event(
            &state.db,
            "password_change_refused",
            Some(&admin.username),
            None,
            Some("wrong current password"),
        )
        .await;
    }
    let reauth = admin_auth::record_reauth(&state.db, &session, &admin, changed.is_some()).await;
    let new_hash = match (reauth, changed) {
        (admin_auth::Reauth::Right, Some(hash)) => hash,
        (admin_auth::Reauth::Wrong { left: 1 }, _) => {
            return render_error(
                "Current password is incorrect. One more wrong one signs you out.",
            );
        }
        (admin_auth::Reauth::Wrong { .. }, _) => {
            return render_error("Current password is incorrect.");
        }
        _ => return admin_auth::signed_out_response(),
    };

    // The new hash and the session epoch in one statement (qa-31-s14-code L2): every other session
    // is signed out exactly when the change is stored; a failure changes nothing and says so. It
    // writes only onto the hash it verified, for this session's epoch (design 62 §2).
    let epoch = admin_auth::change_first_password(
        &state.db,
        admin.id,
        admin.session_epoch,
        &admin.password_hash,
        &new_hash,
    )
    .await;
    let epoch = match epoch {
        Ok(Some(epoch)) => epoch,
        Ok(None) => {
            session.flush().await.ok();
            return admin_auth::signed_out_response();
        }
        Err(err) => {
            tracing::error!(%err, "couldn't save the first password");
            return (
                StatusCode::INTERNAL_SERVER_ERROR,
                "Couldn't save the new password - nothing was changed. Check the server log.",
            )
                .into_response();
        }
    };
    session.insert(admin_auth::EPOCH_KEY, epoch).await.ok();
    // A generated password is spent now: reset-2fa's for this account, and the first account's
    // (design 22 §6) - an invited one never had a file (design 62 §2).
    let _ = tokio::fs::remove_file(
        state
            .data_dir
            .join(crate::startup::reset_password_file(admin.id)),
    )
    .await;
    if admin.invited_by.is_none() {
        let _ = tokio::fs::remove_file(state.data_dir.join(crate::startup::INITIAL_PASSWORD_FILE))
            .await;
    }
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

#[derive(Template)]
#[template(path = "setup_2fa.html")]
struct Setup2faTemplate {
    lang: Lang,
    title: String,
    qr_base64: String,
    secret_base32: String,
    error: Option<String>,
}
crate::i18n::words!(Setup2faTemplate);

impl Setup2faTemplate {
    /// The key in blocks of 4 ("343I I2AD …"), so it wraps between blocks, never a lone character
    /// on a line (the browser pass of 2026-10-14, 6g); authenticator apps ignore the spaces.
    fn secret_blocks(&self) -> String {
        let chars: Vec<char> = self.secret_base32.chars().collect();
        chars
            .chunks(4)
            .map(|block| block.iter().collect::<String>())
            .collect::<Vec<_>>()
            .join(" ")
    }
}

/// The 2FA setup's QR. A new secret is written only onto an account without one, for this
/// session's epoch and not removed (design 62 §2, qa-62-revision-check N6): two tabs of one
/// onboarding show the same secret, and a request held past a Reset or a removal writes nothing.
pub async fn setup_2fa_form(
    State(state): State<AppState>,
    lang: Lang,
    session: Session,
    Extension(CurrentAdmin(admin)): Extension<CurrentAdmin>,
) -> axum::response::Response {
    if admin.totp_enabled {
        return Redirect::to("/").into_response();
    }
    // The password first (design 62 §4.1, qa-62-step12-code P62-1): an account still on its
    // one-time password can't set up 2FA past it.
    if admin.must_change_password {
        return Redirect::to("/auth/change-password").into_response();
    }

    let stored: Result<Option<String>, sqlx::Error> = sqlx::query_scalar(
        "UPDATE admin_users SET totp_secret = COALESCE(totp_secret, ?) \
         WHERE id = ? AND session_epoch = ? AND removed_at IS NULL RETURNING totp_secret",
    )
    .bind(security::generate_totp_secret_base32())
    .bind(admin.id)
    .bind(admin.session_epoch)
    .fetch_optional(&state.db)
    .await;
    let secret = match stored {
        Ok(Some(secret)) => secret,
        Ok(None) => {
            session.flush().await.ok();
            return admin_auth::signed_out_response();
        }
        Err(err) => {
            tracing::error!(%err, "couldn't store the 2FA secret");
            return StatusCode::INTERNAL_SERVER_ERROR.into_response();
        }
    };

    let totp = security::totp_for_secret(&secret, &admin.username);
    let qr_base64 = totp.get_qr_base64().expect("QR generation should not fail");

    Html(
        Setup2faTemplate {
            lang,
            title: "Two-Factor Login".to_string(),
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

/// Turns two-factor login on with a right code. The write is for this session's epoch and an
/// account not removed (design 62 §2), and it ends a one-time password's expiry once the account
/// is ready (§4.1, qa-62-design Q62-8; the password change comes first - P62-1).
pub async fn setup_2fa_verify(
    State(state): State<AppState>,
    lang: Lang,
    session: Session,
    Extension(CurrentAdmin(admin)): Extension<CurrentAdmin>,
    Form(form): Form<Setup2faForm>,
) -> axum::response::Response {
    if admin.must_change_password {
        return Redirect::to("/auth/change-password").into_response();
    }
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
                lang,
                title: "Two-Factor Login".to_string(),
                qr_base64,
                secret_base32: secret,
                error: Some("That code didn't match. Try again.".to_string()),
            }
            .render()
            .unwrap(),
        )
        .into_response();
    }

    let enabled = sqlx::query(
        "UPDATE admin_users SET totp_enabled = 1, \
         invite_expires_at = CASE WHEN must_change_password = 0 THEN NULL ELSE invite_expires_at END \
         WHERE id = ? AND session_epoch = ? AND removed_at IS NULL",
    )
    .bind(admin.id)
    .bind(admin.session_epoch)
    .execute(&state.db)
    .await;
    match enabled {
        Ok(done) if done.rows_affected() == 1 => Redirect::to("/").into_response(),
        Ok(_) => {
            session.flush().await.ok();
            admin_auth::signed_out_response()
        }
        Err(err) => {
            tracing::error!(%err, "couldn't turn two-factor login on");
            StatusCode::INTERNAL_SERVER_ERROR.into_response()
        }
    }
}

/// Voluntary account settings, reachable any time from Settings (unlike
/// `/auth/change-password` and `/auth/setup-2fa` above, which are only ever
/// reached as forced onboarding steps and bounce back to `/` once already
/// satisfied). Lets an admin change their password whenever they want, and
/// recover from a lost authenticator app by resetting 2FA and re-enrolling.
///
/// Its forms render their answer in the POST's response (a password never goes into a URL), each
/// posting to its group's fragment, so the answer lands on the form that sent it (design 31 §5.1).
#[derive(Template)]
#[template(path = "account.html")]
struct AccountTemplate {
    lang: Lang,
    title: String,
    admin_id: i64,
    username: String,
    totp_enabled: bool,
    /// A form's result in its group (`Flash::at`): done (green) or why it was refused.
    flash: Option<crate::handlers::settings::Flash>,
    /// The field a refused password change names ("current_password", ...).
    password_field: &'static str,
    /// Why the two-factor reset was refused; its confirm then renders open.
    reset_error: Option<&'static str>,
    login_warning: Option<String>,
    live_warning: Option<String>,
    /// The Name field: the stored display name, or what a refused save sent.
    name_value: String,
    parents: Vec<crate::parents::ParentRow>,
    /// The field a refused Name, Add Parent or Withdraw names, and why.
    field_error: Option<(String, String)>,
    add_open: bool,
    /// What a refused Add Parent sent: (username, name).
    add_typed: (String, String),
    otp: Option<OtpShown>,
    /// The Language select: the account's own language selected (never `?lang=`).
    languages: Vec<LanguageChoice>,
}
crate::i18n::words!(AccountTemplate);

/// An option of Account's Language select (design 70 §3), its label a key worded in its own language.
struct LanguageChoice {
    code: &'static str,
    label: &'static str,
    selected: bool,
}

impl AccountTemplate {
    fn error_for(&self, field: &str) -> Option<&str> {
        self.field_error
            .as_ref()
            .filter(|(f, _)| f == field)
            .map(|(_, why)| why.as_str())
    }

    fn remove_error(&self, id: &i64) -> Option<&str> {
        self.error_for(&remove_field(*id))
    }

    fn parent_reset_error(&self, id: &i64) -> Option<&str> {
        self.error_for(&reset_field(*id))
    }
}

/// The field name of a row's Remove/Withdraw password, for [AccountAnswer::field_error].
pub(crate) fn remove_field(id: i64) -> String {
    format!("remove-{id}")
}

/// The field name of a row's Reset password, for [AccountAnswer::field_error].
pub(crate) fn reset_field(id: i64) -> String {
    format!("reset-{id}")
}

/// Add Parent's and Reset's answer (design 62 §3): the one-time password, in blocks of 4 for reading (the
/// blocks are spans, so a copy has no spaces), and its end.
pub(crate) struct OtpShown {
    /// The line's key: `account.otp.first` or `account.otp.new` (with `{name}`, `{username}`).
    pub title: &'static str,
    pub name: String,
    pub username: String,
    pub chunks: Vec<String>,
    pub until: String,
}

/// What a form on Account sent back, besides the page.
#[derive(Default)]
pub(crate) struct AccountAnswer {
    pub flash: Option<crate::handlers::settings::Flash>,
    pub password_field: &'static str,
    pub reset_error: Option<&'static str>,
    pub name_typed: Option<String>,
    pub field_error: Option<(String, String)>,
    pub add_open: bool,
    pub add_typed: (String, String),
    pub otp: Option<OtpShown>,
}

pub(crate) async fn render_account(
    state: &AppState,
    session: &Session,
    admin: &AdminUser,
    status: StatusCode,
    answer: AccountAnswer,
    lang: Lang,
) -> axum::response::Response {
    let zone = crate::display_time::Zone::load(&state.db).await;
    let now = chrono::Utc::now();
    let parents = match crate::parents::rows(&state.db, admin, &zone, now, lang).await {
        Ok(rows) => rows,
        Err(err) => {
            tracing::error!(%err, "couldn't read the parent accounts");
            return StatusCode::INTERNAL_SERVER_ERROR.into_response();
        }
    };
    let page = AccountTemplate {
        lang,
        title: t(lang, "account.title").to_string(),
        admin_id: admin.id,
        username: admin.username.clone(),
        totp_enabled: admin.totp_enabled,
        flash: answer.flash,
        password_field: answer.password_field,
        reset_error: answer.reset_error,
        login_warning: login_warning_of(session).await,
        live_warning: live_warning(admin, &zone, now, lang),
        name_value: answer
            .name_typed
            .unwrap_or_else(|| admin.display_name.clone().unwrap_or_default()),
        parents,
        field_error: answer.field_error,
        add_open: answer.add_open,
        add_typed: answer.add_typed,
        otp: answer.otp,
        languages: [Lang::Nb, Lang::En]
            .into_iter()
            .map(|choice| LanguageChoice {
                code: choice.code(),
                label: match choice {
                    Lang::En => "account.language.en",
                    Lang::Nb => "account.language.nb",
                },
                selected: admin.lang.as_deref() == Some(choice.code()),
            })
            .collect(),
    };
    (status, Html(page.render().unwrap())).into_response()
}

/// The banner after a sign-in that followed failed 2FA codes, until dismissed.
pub async fn login_warning_of(session: &Session) -> Option<String> {
    session
        .get::<String>(admin_auth::LOGIN_WARNING_KEY)
        .await
        .ok()
        .flatten()
}

/// The page; `?changed=password` (after a change, post/redirect/get - qa-31-s14-code M1) shows
/// the result in the Password group, `?changed=name` in Name, `?parents=removed|gone` in Parents.
/// Also `GET /account/parents` and `/account/parents/{id}/reset`, where a reload of their POST's
/// answer lands (design 62 §3, `static/reveal.js`).
pub async fn account_page(
    State(state): State<AppState>,
    lang: Lang,
    session: Session,
    Extension(CurrentAdmin(admin)): Extension<CurrentAdmin>,
    axum::extract::Query(query): axum::extract::Query<std::collections::HashMap<String, String>>,
) -> impl IntoResponse {
    use crate::handlers::settings::Flash;
    let flash = match (
        query.get("changed").map(String::as_str),
        query.get("parents").map(String::as_str),
    ) {
        (Some("password"), _) => Some(Flash::done("password", t(lang, "account.password.changed"))),
        (Some("name"), _) => Some(Flash::done("name", t(lang, "account.name.saved"))),
        (_, Some("removed")) => Some(Flash::done("parents", t(lang, "account.parents.removed"))),
        (_, Some("gone")) => Some(Flash::done("parents", t(lang, "account.parents.gone"))),
        _ => None,
    };
    let answer = AccountAnswer {
        flash,
        ..AccountAnswer::default()
    };
    render_account(&state, &session, &admin, StatusCode::OK, answer, lang).await
}

#[derive(Deserialize)]
pub struct UpdatePasswordForm {
    current_password: String,
    new_password: String,
    confirm_password: String,
}

/// Changes the password. A refusal (too short, the two differ, a wrong current password) writes
/// nothing and says why under that field (400). Done: the new hash and the session epoch move in
/// one UPDATE, so either every other session is signed out with it or nothing changed (a DB error
/// is a 500, never "Password changed"). Every device's notifications end with it (security review
/// M1), as with Sign Out Everywhere.
pub async fn update_account_password(
    State(state): State<AppState>,
    lang: Lang,
    session: Session,
    Extension(CurrentAdmin(admin)): Extension<CurrentAdmin>,
    Form(form): Form<UpdatePasswordForm>,
) -> impl IntoResponse {
    use crate::handlers::settings::Flash;
    let refuse = |field: &'static str, why: &'static str| AccountAnswer {
        flash: Some(Flash::refused("password", t(lang, why))),
        password_field: field,
        ..AccountAnswer::default()
    };
    let refused = if form.new_password.len() < security::MIN_PASSWORD_LEN {
        Some(refuse("new_password", "account.password.too_short"))
    } else if form.new_password != form.confirm_password {
        Some(refuse("confirm_password", "account.password.mismatch"))
    } else {
        None
    };
    if let Some(answer) = refused {
        return render_account(
            &state,
            &session,
            &admin,
            StatusCode::BAD_REQUEST,
            answer,
            lang,
        )
        .await;
    }
    let changed = match state
        .passwords
        .change(
            &form.current_password,
            &admin.password_hash,
            &form.new_password,
        )
        .await
    {
        Ok(changed) => changed,
        Err(admin_auth::Busy) => return admin_auth::busy_response(),
    };
    // Three wrong in a row end the session (design 59 §2.2's one re-auth rule); each wrong one is
    // in the security log (security review L7).
    if changed.is_none() {
        security::record_security_event(
            &state.db,
            "password_change_refused",
            Some(&admin.username),
            None,
            Some("wrong current password"),
        )
        .await;
    }
    let reauth = admin_auth::record_reauth(&state.db, &session, &admin, changed.is_some()).await;
    let new_hash = match (reauth, changed) {
        (admin_auth::Reauth::Right, Some(hash)) => hash,
        (admin_auth::Reauth::Wrong { left }, _) => {
            let answer = refuse(
                "current_password",
                if left == 1 {
                    "account.password.wrong_last"
                } else {
                    "account.password.wrong"
                },
            );
            return render_account(
                &state,
                &session,
                &admin,
                StatusCode::BAD_REQUEST,
                answer,
                lang,
            )
            .await;
        }
        _ => return admin_auth::signed_out_response(),
    };

    // A new password makes the old one's wrong codes moot: the counter and the lock go too, so
    // the parent can sign in elsewhere again.
    let epoch: Result<Option<i64>, sqlx::Error> = sqlx::query_scalar(
        "UPDATE admin_users SET password_hash = ?, failed_login_attempts = 0, lock_count = 0, \
         locked_until = NULL, session_epoch = session_epoch + 1 \
         WHERE id = ? AND session_epoch = ? AND removed_at IS NULL RETURNING session_epoch",
    )
    .bind(&new_hash)
    .bind(admin.id)
    .bind(admin.session_epoch)
    .fetch_optional(&state.db)
    .await;
    // No row: a Reset, a removal or another session's change came first (design 62 §2) - this
    // request can't undo it.
    if let Ok(None) = epoch {
        session.flush().await.ok();
        return admin_auth::signed_out_response();
    }
    let Ok(Some(epoch)) = epoch else {
        tracing::error!(?epoch, "couldn't save the new password");
        return crate::i18n::error(
            lang,
            StatusCode::INTERNAL_SERVER_ERROR,
            "account.password.failed",
        );
    };
    session.insert(admin_auth::EPOCH_KEY, epoch).await.ok();
    // A browser a stolen session turned notifications on in stops getting them too (security
    // review M1), this one's as well: the parent turns them on again where they want them.
    crate::push::forget_subscriptions(&state.db, admin.id).await;
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

    // Post/redirect/get: the password stays in this POST's body, and a reload asks nothing again.
    Redirect::to("/account?changed=password#password").into_response()
}

/// The "Reset two-factor login" form: the current password.
#[derive(Deserialize)]
pub struct ResetTotpForm {
    #[serde(default)]
    current_password: String,
}

/// Clears the stored TOTP secret and drops `totp_enabled`, which sends the
/// admin straight back through mandatory 2FA setup (`require_full_auth`
/// redirects there for any account with `totp_enabled = false`) with a
/// brand-new QR code - the recovery path for a lost/uninstalled
/// authenticator app while still signed in. Every other session ends, in the same UPDATE, and
/// every device's notifications after it (security review M1). (Signed
/// out and locked out: `kid_phone_server reset-2fa <user>`.)
///
/// It asks for the current password, like a password change (user decision on QA 22 M5): a
/// stolen session can't swap in its own authenticator and sign the parent out. A wrong one renders
/// Account with the confirm open and the reason under the field (400).
pub async fn reset_totp(
    State(state): State<AppState>,
    lang: Lang,
    session: Session,
    Extension(CurrentAdmin(admin)): Extension<CurrentAdmin>,
    Form(form): Form<ResetTotpForm>,
) -> axum::response::Response {
    // Three wrong in a row end the session (design 59 §2.2's one re-auth rule).
    let reauth = admin_auth::reauth(&state, &session, &admin, &form.current_password).await;
    if reauth == admin_auth::Reauth::Busy {
        return admin_auth::busy_response();
    }
    if reauth != admin_auth::Reauth::Right {
        security::record_security_event(
            &state.db,
            "totp_reset_refused",
            Some(&admin.username),
            None,
            Some("wrong current password"),
        )
        .await;
        let reset_error = match reauth {
            admin_auth::Reauth::Wrong { left: 1 } => "account.totp.wrong_last",
            admin_auth::Reauth::Wrong { .. } => "account.totp.wrong",
            _ => return admin_auth::signed_out_response(),
        };
        let answer = AccountAnswer {
            reset_error: Some(t(lang, reset_error)),
            ..AccountAnswer::default()
        };
        return render_account(
            &state,
            &session,
            &admin,
            StatusCode::BAD_REQUEST,
            answer,
            lang,
        )
        .await;
    }
    let epoch: Result<Option<i64>, sqlx::Error> = sqlx::query_scalar(
        "UPDATE admin_users SET totp_secret = NULL, totp_enabled = 0, totp_last_step = NULL, \
         session_epoch = session_epoch + 1 \
         WHERE id = ? AND session_epoch = ? AND removed_at IS NULL RETURNING session_epoch",
    )
    .bind(admin.id)
    .bind(admin.session_epoch)
    .fetch_optional(&state.db)
    .await;
    if let Ok(None) = epoch {
        session.flush().await.ok();
        return admin_auth::signed_out_response();
    }
    let Ok(Some(epoch)) = epoch else {
        tracing::error!(?epoch, "couldn't reset two-factor login");
        return crate::i18n::error(
            lang,
            StatusCode::INTERNAL_SERVER_ERROR,
            "account.totp.failed",
        );
    };
    session.insert(admin_auth::EPOCH_KEY, epoch).await.ok();
    // Every device's notifications end with the other sessions (security review M1).
    crate::push::forget_subscriptions(&state.db, admin.id).await;

    security::record_security_event(
        &state.db,
        "totp_reset",
        Some(&admin.username),
        None,
        Some("every other session signed out"),
    )
    .await;

    Redirect::to("/auth/setup-2fa").into_response()
}

/// "Sign Out Everywhere…" (design 22 §3.2.2, behind a confirm since design 31): every session of
/// this account ends, this one too, and every device's notifications (design 35 §7). If the epoch can't be moved, nothing is signed out and the
/// parent is told so (500) - this session is kept, so they know it failed.
pub async fn sign_out_everywhere(
    State(state): State<AppState>,
    lang: Lang,
    session: Session,
    Extension(CurrentAdmin(admin)): Extension<CurrentAdmin>,
) -> axum::response::Response {
    if admin_auth::bump_epoch(&state.db, admin.id).await.is_none() {
        tracing::error!("couldn't sign out everywhere");
        return crate::i18n::error(
            lang,
            StatusCode::INTERNAL_SERVER_ERROR,
            "account.sessions.failed",
        );
    }
    // A lost device stops getting notifications too (design 35 §7).
    crate::push::forget_subscriptions(&state.db, admin.id).await;
    security::record_security_event(
        &state.db,
        "signed_out_everywhere",
        Some(&admin.username),
        None,
        None,
    )
    .await;
    session.flush().await.ok();
    Redirect::to("/login").into_response()
}

/// Hides the failed-2FA banner (it stays in the security log).
#[derive(Deserialize)]
pub struct DismissForm {
    #[serde(default)]
    back: String,
}

/// Hides the failed-2FA banner (it stays in the security log) and returns to the page it was
/// dismissed on - Devices or Account, nothing else (QA 22 Low 9).
pub async fn dismiss_login_warning(
    session: Session,
    Form(form): Form<DismissForm>,
) -> impl IntoResponse {
    session
        .remove::<String>(admin_auth::LOGIN_WARNING_KEY)
        .await
        .ok();
    Redirect::to(if form.back == "/account" {
        "/account"
    } else {
        "/devices"
    })
}

/// A sign-in warning as the Devices list's orange row (design 31 §3.2, qa-31-s2-code L3): a short
/// title and the action; the full sentence stays on Account.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct WarningRow {
    pub title: String,
    pub sub: String,
}

/// [live_warning] as a row: "3 wrong two-factor codes since sign-in", "Someone knows your
/// password - change it · sign-ins locked until 14:05" (the lock's end in the Phone Setup zone).
pub fn live_warning_row(
    admin: &AdminUser,
    zone: &crate::display_time::Zone,
    now: chrono::DateTime<chrono::Utc>,
) -> Option<WarningRow> {
    let n = admin.failed_login_attempts;
    if n <= 0 {
        return None;
    }
    let mut sub = "Someone knows your password - change it".to_string();
    if let Some(until) = admin
        .locked_until
        .as_deref()
        .and_then(crate::display_time::parse_db_utc)
        .filter(|until| *until > now)
    {
        let tomorrow = if zone.local(until).date_naive() == zone.today(now) {
            ""
        } else {
            " tomorrow"
        };
        sub.push_str(&format!(
            " · sign-ins locked until {}{tomorrow}",
            zone.clock(until)
        ));
    }
    Some(WarningRow {
        title: format!(
            "{n} wrong two-factor code{} since sign-in",
            if n == 1 { "" } else { "s" }
        ),
        sub,
    })
}

/// The post-sign-in banner ([login_warning_of]) as a row; its counts and addresses stay on
/// Account.
pub fn login_warning_row(warning: Option<&str>) -> Option<WarningRow> {
    warning.map(|_| WarningRow {
        title: "Wrong two-factor codes before this sign-in".to_string(),
        sub: "Someone knew your password - change it".to_string(),
    })
}

/// For a signed-in parent (QA 22 Low 9): wrong 2FA codes entered since their last complete
/// sign-in, and the lock they caused - the post-sign-in banner can't say it, since the lock keeps
/// new sign-ins out. `admin` is the row `require_full_auth` just read; the lock's end in the Phone
/// Setup zone ("until 14:05", "until 00:05 tomorrow").
pub fn live_warning(
    admin: &AdminUser,
    zone: &crate::display_time::Zone,
    now: chrono::DateTime<chrono::Utc>,
    lang: Lang,
) -> Option<String> {
    let n = admin.failed_login_attempts;
    if n <= 0 {
        return None;
    }
    let lock = admin
        .locked_until
        .as_deref()
        .and_then(crate::display_time::parse_db_utc)
        .filter(|until| *until > now);
    Some(match lock {
        Some(until) => t_count(
            lang,
            "account.warning.live_locked",
            n,
            &[("until", &zone.until(until, now))],
        ),
        None => t_count(lang, "account.warning.live", n, &[]),
    })
}

/// The Language form (design 70 §3).
#[derive(Deserialize)]
pub struct LanguageForm {
    #[serde(default)]
    lang: String,
}

/// `POST /account/language`: the account's language, `en` or `nb` (anything else is a 400 in the page's language,
/// nothing written), for this session's epoch and a live account; back to `/account#language`, where the page comes
/// back in the new language at the same place. `settings_log` logs it as a save.
pub async fn set_language(
    State(state): State<AppState>,
    lang: Lang,
    session: Session,
    Extension(CurrentAdmin(admin)): Extension<CurrentAdmin>,
    Form(form): Form<LanguageForm>,
) -> axum::response::Response {
    let Some(chosen) = Lang::from_code(&form.lang) else {
        return crate::i18n::error(lang, StatusCode::BAD_REQUEST, "account.language.refused");
    };
    let saved = sqlx::query(
        "UPDATE admin_users SET lang = ? \
         WHERE id = ? AND session_epoch = ? AND removed_at IS NULL",
    )
    .bind(chosen.code())
    .bind(admin.id)
    .bind(admin.session_epoch)
    .execute(&state.db)
    .await;
    match saved {
        Ok(done) if done.rows_affected() == 1 => Redirect::to("/account#language").into_response(),
        Ok(_) => {
            session.flush().await.ok();
            admin_auth::signed_out_response()
        }
        Err(err) => {
            tracing::error!(%err, "couldn't save the account's language");
            crate::i18n::error(lang, StatusCode::INTERNAL_SERVER_ERROR, "common.not_saved")
        }
    }
}
