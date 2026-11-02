//! Passkeys' routes (design 74 step 1; the verifier is `crate::webauthn`, the store and the table
//! `crate::passkeys`). `static/passkey.js` drives them; without JS (or without passkeys in the
//! browser, D4.4) nothing here is reached and the password stays the way in.
//!
//! - `GET /auth/passkey/options` (signed out, behind the admin gate and the per-IP ban): a `login`
//!   challenge for this session, `allowCredentials: []` (D1.4). Each call is a `passkey_started`
//!   row that counts in the ban (taken back by its sign-in), so the store can't be flooded; a
//!   banned key gets 429.
//! - `POST /auth/passkey` (form `assertion`, 4 KiB): the sign-in, both factors (D1.5) - the same
//!   completion as `verify_2fa`, no TOTP page. A refusal is one red line and counts in the ban.
//! - `POST /account/passkeys/reauth-options` (form `route`): a `reauth` challenge bound to the
//!   form's route, `allowCredentials` = this parent's passkeys here (D1.6).
//! - `POST /account/passkeys/options` (name + the re-auth): the creation options with a
//!   `register` challenge (D1.2); `POST /account/passkeys` (form `credential`, 8 KiB): the
//!   browser's answer, PRG `/account?done=passkey_added#passkey-<id>`, `passkey_added` logged,
//!   pushed to every parent and put in their inbox.
//! - `POST /account/passkeys/{id}/remove` (the re-auth): the row goes and the session epoch moves
//!   keeping this session (D1.3), PRG `/account?done=passkey_removed#passkeys`.
//!
//! The JSON answers are `no-store`. A JSON refusal is `{"error": words, "field": "name" |
//! "password"}`, or `{"signed_out": true}` after the third wrong re-auth.

use axum::extract::{Path, State};
use axum::http::{StatusCode, header};
use axum::response::{IntoResponse, Redirect, Response};
use axum::{Extension, Form};
use serde::Deserialize;
use tower_sessions::Session;

use crate::AppState;
use crate::admin_auth::{self, Proof, Reauth};
use crate::handlers::auth::{AccountAnswer, render_account};
use crate::i18n::{Lang, t};
use crate::passkeys::{self, Bound, Ceremony, Kind, Taken};
use crate::security::{self, CurrentAdmin};
use crate::webauthn::{Refused, Registration};

/// A signed-out session made for a passkey sign-in lives this long (its challenge is 5 minutes);
/// the sign-in gives it the usual lifetime.
const PRE_SIGN_IN_MINUTES: i64 = 10;
/// The session's marker that it was made for a passkey sign-in.
const PRE_SIGN_IN_KEY: &str = "passkey_sign_in";

fn json(status: StatusCode, body: serde_json::Value) -> Response {
    (
        status,
        [(header::CACHE_CONTROL, "no-store")],
        axum::Json(body),
    )
        .into_response()
}

/// A JSON refusal: the words, and the field they belong under.
fn json_refused(status: StatusCode, words: &str, field: Option<&str>) -> Response {
    json(
        status,
        serde_json::json!({ "error": words, "field": field }),
    )
}

fn signed_out_json() -> Response {
    json(
        StatusCode::UNAUTHORIZED,
        serde_json::json!({ "signed_out": true }),
    )
}

// ---------------------------------------------------------------------------------------------
// Sign-in

/// `GET /auth/passkey/options` (D1.4).
pub async fn login_options(
    State(state): State<AppState>,
    lang: Lang,
    session: Session,
    ceremony: Ceremony,
) -> Response {
    let ip = ceremony.client_key.clone();
    security::check_and_ban_ip_if_needed(&state.db, &ip).await;
    if security::is_ip_banned(&state.db, &ip).await {
        return json_refused(
            StatusCode::TOO_MANY_REQUESTS,
            t(lang, "passkeys.sign_in.banned"),
            None,
        );
    }
    let Some(rp_id) = ceremony.rp_id.clone() else {
        return json_refused(
            StatusCode::NOT_FOUND,
            t(lang, "passkeys.sign_in.failed"),
            None,
        );
    };
    // Counted before anything is issued (and taken back by the sign-in it starts).
    let attempt: Option<i64> = sqlx::query_scalar(
        "INSERT INTO security_events (event_type, ip_address) VALUES ('passkey_started', ?) \
         RETURNING id",
    )
    .bind(&ip)
    .fetch_one(&state.db)
    .await
    .ok();
    // The challenge is bound to this session: a browser without one gets a short-lived one.
    if session.id().is_none() {
        session.insert(PRE_SIGN_IN_KEY, true).await.ok();
        session.set_expiry(Some(tower_sessions::Expiry::OnInactivity(
            tower_sessions::cookie::time::Duration::minutes(PRE_SIGN_IN_MINUTES),
        )));
        if let Err(err) = session.save().await {
            tracing::error!(%err, "couldn't save the session for a passkey sign-in");
            return StatusCode::INTERNAL_SERVER_ERROR.into_response();
        }
    }
    let Some(session_id) = passkeys::session_id(&session) else {
        return StatusCode::INTERNAL_SERVER_ERROR.into_response();
    };
    let bound = Bound {
        kind: Kind::Login,
        session: session_id,
        admin: None,
        rp_id: rp_id.clone(),
        route: passkeys::SIGN_IN_ROUTE.to_string(),
    };
    let taken = Taken {
        label: None,
        attempt,
    };
    let Some(challenge) = state.passkeys.challenges.issue(bound, taken) else {
        return json_refused(
            StatusCode::SERVICE_UNAVAILABLE,
            t(lang, "passkeys.sign_in.failed"),
            None,
        );
    };
    json(
        StatusCode::OK,
        passkeys::request_options(&challenge, &rp_id, &[]),
    )
}

#[derive(Deserialize)]
pub struct SignInForm {
    #[serde(default)]
    assertion: String,
}

/// `POST /auth/passkey` (D1.4, D1.5).
pub async fn login(
    State(state): State<AppState>,
    lang: Lang,
    session: Session,
    ceremony: Ceremony,
    Form(form): Form<SignInForm>,
) -> Response {
    let ip = ceremony.client_key.clone();
    security::check_and_ban_ip_if_needed(&state.db, &ip).await;
    if security::is_ip_banned(&state.db, &ip).await {
        return (
            StatusCode::TOO_MANY_REQUESTS,
            crate::handlers::auth::render_login(
                Some(t(lang, "passkeys.sign_in.banned").to_string()),
                lang,
                ceremony.rp_id.is_some(),
            ),
        )
            .into_response();
    }
    let signed_in = passkeys::sign_in(
        &state,
        passkeys::session_id(&session),
        &ceremony,
        &form.assertion,
    )
    .await;
    let signed_in = match signed_in {
        Ok(signed_in) => signed_in,
        Err(refused) => {
            // One red line whatever it was, counted in the ban as a wrong password (D1.5).
            passkeys::log_refused(
                &state.db,
                refused.why,
                "security.detail.passkey.sign_in",
                refused.username.as_deref(),
                Some(&ip),
            )
            .await;
            security::check_and_ban_ip_if_needed(&state.db, &ip).await;
            return crate::handlers::auth::render_login(
                Some(t(lang, "passkeys.sign_in.failed").to_string()),
                lang,
                ceremony.rp_id.is_some(),
            )
            .into_response();
        }
    };
    let admin = signed_in.admin;
    if let Some(attempt) = signed_in.taken.attempt {
        sqlx::query("DELETE FROM security_events WHERE id = ? AND event_type = 'passkey_started'")
            .bind(attempt)
            .execute(&state.db)
            .await
            .ok();
    }
    // The same completion as `verify_2fa`: the counters start over, the sign-in is logged, the
    // session id is cycled and the session stores the account, its epoch and the sign-in's time.
    // Wrong two-factor codes since the last complete sign-in are told as after `verify_2fa`: whoever typed them
    // knew the password, and this sign-in resets their count.
    let warning =
        admin_auth::login_warning(&state.db, &admin.username, admin.failed_login_attempts).await;
    admin_auth::reset_after_login(&state.db, admin.id).await;
    security::record_security_event(
        &state.db,
        "login_success",
        Some(&admin.username),
        Some(&ip),
        Some(security::Detail::words(
            "security.detail.login.passkey",
            &[("name", &signed_in.passkey.name)],
        )),
    )
    .await;
    session.cycle_id().await.ok();
    session.remove::<bool>(PRE_SIGN_IN_KEY).await.ok();
    session.remove::<i64>(admin_auth::PENDING_ID_KEY).await.ok();
    session.remove::<u64>(admin_auth::PENDING_AT_KEY).await.ok();
    session.set_expiry(Some(tower_sessions::Expiry::OnInactivity(
        tower_sessions::cookie::time::Duration::days(state.net.session_inactivity_days),
    )));
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

// ---------------------------------------------------------------------------------------------
// Re-auth

#[derive(Deserialize)]
pub struct ReauthOptionsForm {
    #[serde(default)]
    route: String,
}

/// `POST /account/passkeys/reauth-options` (D1.6): the form's route must be one that asks for the
/// password again (`passkeys::reauth_route`).
pub async fn reauth_options(
    State(state): State<AppState>,
    lang: Lang,
    session: Session,
    ceremony: Ceremony,
    Extension(CurrentAdmin(admin)): Extension<CurrentAdmin>,
    Form(form): Form<ReauthOptionsForm>,
) -> Response {
    if !passkeys::reauth_route(&form.route) {
        return json_refused(
            StatusCode::BAD_REQUEST,
            t(lang, "passkeys.reauth.failed"),
            None,
        );
    }
    let (Some(rp_id), Some(session_id)) = (ceremony.rp_id.clone(), passkeys::session_id(&session))
    else {
        return json_refused(
            StatusCode::NOT_FOUND,
            t(lang, "passkeys.reauth.none_here"),
            None,
        );
    };
    let here: Vec<passkeys::PasskeyRow> = match passkeys::of_admin(&state.db, admin.id).await {
        Ok(rows) => rows.into_iter().filter(|row| row.rp_id == rp_id).collect(),
        Err(err) => {
            tracing::error!(%err, "couldn't read the passkeys");
            return StatusCode::INTERNAL_SERVER_ERROR.into_response();
        }
    };
    if here.is_empty() {
        return json_refused(
            StatusCode::NOT_FOUND,
            t(lang, "passkeys.reauth.none_here"),
            None,
        );
    }
    let bound = Bound {
        kind: Kind::Reauth,
        session: session_id,
        admin: Some((admin.id, admin.session_epoch)),
        rp_id: rp_id.clone(),
        route: form.route,
    };
    let Some(challenge) = state.passkeys.challenges.issue(bound, Taken::default()) else {
        return json_refused(
            StatusCode::SERVICE_UNAVAILABLE,
            t(lang, "passkeys.reauth.failed"),
            None,
        );
    };
    json(
        StatusCode::OK,
        passkeys::request_options(&challenge, &rp_id, &here),
    )
}

/// A refused re-auth's words: the password's, or the passkey's (D1.6); "one more signs you out"
/// before the last.
pub(crate) fn wrong_words(
    lang: Lang,
    proof: &Proof,
    left: i64,
    password: &str,
    password_last: &str,
) -> String {
    let key = match (proof.is_passkey(), left == 1) {
        (true, false) => "passkeys.reauth.failed",
        (true, true) => "passkeys.reauth.failed_last",
        (false, false) => password,
        (false, true) => password_last,
    };
    t(lang, key).to_string()
}

/// A wrong password at a passkey's add or removal in the log (`passkey_refused`, what it was for); a
/// passkey that didn't prove the parent was logged by `admin_auth::reauth` already.
async fn log_wrong(
    state: &AppState,
    admin: &crate::models::AdminUser,
    proof: &Proof<'_>,
    what: &str,
) {
    if proof.is_passkey() {
        return;
    }
    security::record_security_event(
        &state.db,
        "passkey_refused",
        Some(&admin.username),
        None,
        Some(security::Detail::words(what, &[]).and(proof.refused_detail(), &[])),
    )
    .await;
}

// ---------------------------------------------------------------------------------------------
// Adding

#[derive(Deserialize)]
pub struct AddOptionsForm {
    #[serde(default)]
    name: String,
    #[serde(default)]
    current_password: String,
    #[serde(default)]
    assertion: String,
}

/// A passkey's name as typed (security::display_name's rule, 1-32 characters); empty is "Passkey".
fn passkey_name(raw: &str, lang: Lang) -> Result<String, &'static str> {
    match security::display_name(raw)? {
        Some(name) => Ok(name),
        None => Ok(t(lang, "passkeys.default_name").to_string()),
    }
}

/// `POST /account/passkeys/options` (D1.2): the name and the re-auth, then the creation options
/// with a `register` challenge that keeps the name.
pub async fn add_options(
    State(state): State<AppState>,
    lang: Lang,
    session: Session,
    ceremony: Ceremony,
    Extension(CurrentAdmin(admin)): Extension<CurrentAdmin>,
    Form(form): Form<AddOptionsForm>,
) -> Response {
    let (Some(rp_id), Some(session_id)) = (ceremony.rp_id.clone(), passkeys::session_id(&session))
    else {
        return json_refused(
            StatusCode::NOT_FOUND,
            t(lang, "passkeys.add.not_here"),
            None,
        );
    };
    let name = match passkey_name(&form.name, lang) {
        Ok(name) => name,
        Err(why) => return json_refused(StatusCode::BAD_REQUEST, t(lang, why), Some("name")),
    };
    let existing = match passkeys::of_admin(&state.db, admin.id).await {
        Ok(rows) => rows,
        Err(err) => {
            tracing::error!(%err, "couldn't read the passkeys");
            return StatusCode::INTERNAL_SERVER_ERROR.into_response();
        }
    };
    if existing.len() as i64 >= passkeys::MAX_PASSKEYS {
        return json_refused(
            StatusCode::BAD_REQUEST,
            t(lang, "passkeys.add.full"),
            Some("name"),
        );
    }
    let proof = Proof::of(&form.current_password, &form.assertion, &ceremony);
    match admin_auth::reauth(&state, &session, &admin, proof).await {
        Reauth::Right => {}
        Reauth::Busy => return admin_auth::busy_response(),
        Reauth::SignedOut => return signed_out_json(),
        Reauth::Wrong { left } => {
            log_wrong(&state, &admin, &proof, "security.detail.passkey.add").await;
            let words = wrong_words(
                lang,
                &proof,
                left,
                "account.password.wrong",
                "account.password.wrong_last",
            );
            return json_refused(StatusCode::BAD_REQUEST, &words, Some("password"));
        }
    }
    let user_id = match passkeys::user_id(&state.db, &admin).await {
        Ok(Some(user_id)) => user_id,
        Ok(None) => {
            session.flush().await.ok();
            return signed_out_json();
        }
        Err(err) => {
            tracing::error!(%err, "couldn't make the account's passkey user id");
            return StatusCode::INTERNAL_SERVER_ERROR.into_response();
        }
    };
    let exclude: Vec<passkeys::PasskeyRow> = existing
        .into_iter()
        .filter(|row| row.rp_id == rp_id)
        .collect();
    let bound = Bound {
        kind: Kind::Register,
        session: session_id,
        admin: Some((admin.id, admin.session_epoch)),
        rp_id: rp_id.clone(),
        route: passkeys::REGISTER_ROUTE.to_string(),
    };
    let taken = Taken {
        label: Some(name),
        attempt: None,
    };
    let Some(challenge) = state.passkeys.challenges.issue(bound, taken) else {
        return json_refused(
            StatusCode::SERVICE_UNAVAILABLE,
            t(lang, "passkeys.add.failed"),
            None,
        );
    };
    json(
        StatusCode::OK,
        passkeys::creation_options(&challenge, &rp_id, &user_id, &admin, &exclude),
    )
}

#[derive(Deserialize)]
pub struct AddForm {
    #[serde(default)]
    credential: String,
}

/// `POST /account/passkeys` (D1.2): the browser's answer to a `register` challenge of this
/// session, account, epoch and RP ID. A refusal renders Account with the add form open and the
/// reason in it (400).
pub async fn add(
    State(state): State<AppState>,
    lang: Lang,
    session: Session,
    ceremony: Ceremony,
    Extension(CurrentAdmin(admin)): Extension<CurrentAdmin>,
    Form(form): Form<AddForm>,
) -> Response {
    let refuse = |why: &str| AccountAnswer {
        field_error: Some(("passkey_name".to_string(), t(lang, why).to_string())),
        passkey_add_open: true,
        ..AccountAnswer::default()
    };
    let verified = verify_registration(&state, &session, &ceremony, &admin, &form.credential);
    let (name, credential) = match verified {
        Ok(verified) => verified,
        Err(why) => {
            passkeys::log_refused(
                &state.db,
                why,
                "security.detail.passkey.add",
                Some(&admin.username),
                None,
            )
            .await;
            let answer = refuse("passkeys.add.failed");
            return render_account(
                &state,
                &session,
                &admin,
                &ceremony,
                StatusCode::BAD_REQUEST,
                answer,
                lang,
            )
            .await;
        }
    };
    let rp_id = ceremony.rp_id.clone().unwrap_or_default();
    let id = match passkeys::add(&state.db, &admin, &rp_id, &name, &credential).await {
        Ok(passkeys::Added::Done(id)) => id,
        Ok(passkeys::Added::SignedOut) => {
            session.flush().await.ok();
            return admin_auth::signed_out_response();
        }
        Ok(refused) => {
            let why = if refused == passkeys::Added::Full {
                "passkeys.add.full"
            } else {
                "passkeys.add.duplicate"
            };
            let answer = refuse(why);
            return render_account(
                &state,
                &session,
                &admin,
                &ceremony,
                StatusCode::BAD_REQUEST,
                answer,
                lang,
            )
            .await;
        }
        Err(err) => {
            tracing::error!(%err, "couldn't store the passkey");
            return crate::i18n::error(
                lang,
                StatusCode::INTERNAL_SERVER_ERROR,
                "passkeys.add.not_saved",
            );
        }
    };
    security::record_security_event(
        &state.db,
        "passkey_added",
        Some(&admin.username),
        None,
        Some(security::Detail::facts(&name)),
    )
    .await;
    // Every parent is told at once, in the inbox and by a push (D1.2).
    let event = crate::parent_events::passkey_added(id, admin.name(), &name, chrono::Utc::now());
    crate::parent_events::raise_logged(&state, &event).await;
    Redirect::to(&format!("/account?done=passkey_added#passkey-{id}")).into_response()
}

/// The registration's checks: the `register` challenge (its name kept with it), then
/// [Registration::verify] against this request's RP ID and origins.
fn verify_registration(
    state: &AppState,
    session: &Session,
    ceremony: &Ceremony,
    admin: &crate::models::AdminUser,
    json: &str,
) -> Result<(String, crate::webauthn::NewCredential), Refused> {
    let registration = Registration::parse(json)?;
    let (Some(rp_id), Some(session_id)) = (ceremony.rp_id.clone(), passkeys::session_id(session))
    else {
        return Err(Refused::Challenge);
    };
    let bound = Bound {
        kind: Kind::Register,
        session: session_id,
        admin: Some((admin.id, admin.session_epoch)),
        rp_id: rp_id.clone(),
        route: ceremony.route.clone(),
    };
    let taken = state
        .passkeys
        .challenges
        .take(&registration.client.challenge, &bound)
        .ok_or(Refused::Challenge)?;
    let credential = registration.verify(&rp_id, &ceremony.origins)?;
    Ok((taken.label.unwrap_or_default(), credential))
}

// ---------------------------------------------------------------------------------------------
// Removing

#[derive(Deserialize)]
pub struct RemoveForm {
    #[serde(default)]
    current_password: String,
    #[serde(default)]
    assertion: String,
}

/// The field a passkey row's Remove names, for [AccountAnswer::field_error].
pub(crate) fn remove_field(id: i64) -> String {
    format!("remove-passkey-{id}")
}

/// `POST /account/passkeys/{id}/remove` (D1.3): the re-auth, then the row and the session epoch in
/// one transaction - this session keeps the new epoch, every other one ends, and every device's
/// notifications end with them (as a password change).
pub async fn remove(
    State(state): State<AppState>,
    lang: Lang,
    session: Session,
    ceremony: Ceremony,
    Extension(CurrentAdmin(admin)): Extension<CurrentAdmin>,
    Path(id): Path<i64>,
    Form(form): Form<RemoveForm>,
) -> Response {
    let proof = Proof::of(&form.current_password, &form.assertion, &ceremony);
    match admin_auth::reauth(&state, &session, &admin, proof).await {
        Reauth::Right => {}
        Reauth::Busy => return admin_auth::busy_response(),
        Reauth::SignedOut => return admin_auth::signed_out_response(),
        Reauth::Wrong { left } => {
            log_wrong(&state, &admin, &proof, "security.detail.passkey.remove").await;
            let words = wrong_words(
                lang,
                &proof,
                left,
                "account.password.wrong",
                "account.password.wrong_last",
            );
            let answer = AccountAnswer {
                field_error: Some((remove_field(id), words)),
                ..AccountAnswer::default()
            };
            return render_account(
                &state,
                &session,
                &admin,
                &ceremony,
                StatusCode::BAD_REQUEST,
                answer,
                lang,
            )
            .await;
        }
    }
    match passkeys::remove(&state.db, &admin, id).await {
        Ok(passkeys::Removed::Done { name, epoch }) => {
            session.insert(admin_auth::EPOCH_KEY, epoch).await.ok();
            crate::push::forget_subscriptions(&state.db, admin.id).await;
            security::record_security_event(
                &state.db,
                "passkey_removed",
                Some(&admin.username),
                None,
                Some(
                    security::Detail::words("security.detail.other_sessions_signed_out", &[])
                        .with_facts(&name),
                ),
            )
            .await;
            Redirect::to("/account?done=passkey_removed#passkeys").into_response()
        }
        Ok(passkeys::Removed::Gone) => {
            crate::settings_log::refused(Redirect::to("/account?done=passkey_gone#passkeys"))
        }
        Ok(passkeys::Removed::SignedOut) => {
            session.flush().await.ok();
            admin_auth::signed_out_response()
        }
        Err(err) => {
            tracing::error!(%err, "couldn't remove the passkey");
            crate::i18n::error(
                lang,
                StatusCode::INTERNAL_SERVER_ERROR,
                "passkeys.remove.failed",
            )
        }
    }
}
