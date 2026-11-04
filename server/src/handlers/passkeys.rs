//! Passkeys' routes (design 74 step 1; the verifier is `crate::webauthn`, the store and the table
//! `crate::passkeys`). `static/passkey.js` drives them; without JS (or without passkeys in the
//! browser, D4.4) nothing here is reached and the password stays the way in.
//!
//! - `POST /auth/passkey/options` (signed out, through `csrf_guard`, behind the admin gate and the
//!   per-IP ban): a `login` challenge for this session, `allowCredentials: []` (D1.4). A session's
//!   first call is a `passkey_started` row that counts in the ban until its ceremony ends; later
//!   calls get its live challenge back. A banned key gets 429.
//! - `POST /auth/passkey` (form `assertion`, 4 KiB): the sign-in, both factors (D1.5) - the same
//!   completion as `verify_2fa`, no TOTP page. A refusal is one red line and counts in the ban.
//! - `POST /account/passkeys/reauth-options` (form `route`): a `reauth` challenge bound to the
//!   form's route, `allowCredentials` = this parent's passkeys here (D1.6).
//! - `POST /account/passkeys/options` (name + the re-auth): the creation options with a
//!   `register` challenge (D1.2); `POST /account/passkeys` (form `credential`, 8 KiB): the
//!   browser's answer, PRG `/account?done=passkey_added#passkey-<id>`, `passkey_added` logged,
//!   pushed to every parent and put in their inbox.
//! - `POST /account/passkeys/{id}/remove` (the re-auth): the row goes and the session epoch moves
//!   keeping this session (D1.3), PRG `/account?done=passkey_removed#passkeys`; never a passkey-only
//!   account's last one (D2.3).
//! - Step 2, "Kun passkey": `POST /account/passkey-only` (a passkey confirms it; the switch's one
//!   `UPDATE`, D2.1/D2.2), and its switch-off page `GET`/`POST /account/password-sign-in` (the
//!   passkey, a new password and the TOTP, one `UPDATE`, D2.5).
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
use crate::i18n::{Lang, Words, t};
use crate::passkeys::{self, Bound, Ceremony, Kind, Taken};
use crate::security::{self, CurrentAdmin};
use crate::webauthn::{Refused, Registration};

/// A signed-out session made for a passkey sign-in lives this long (its challenge is 5 minutes);
/// the sign-in gives it the usual lifetime.
const PRE_SIGN_IN_MINUTES: i64 = 10;

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

/// `POST /auth/passkey/options` (D1.4): a POST, so `csrf_guard` refuses a cross-site call - it
/// writes a counted row (qa-passkeys-1 B-M2). A session counts once: it gets its live challenge
/// again (a reload, the conditional get and the button share one), and its row is taken back by
/// whatever ends the ceremony - a passkey sign-in (which counts its own refusal) or a password
/// sign-in's POST (B-M3, `passkeys::end_sign_in_attempt`). An abandoned one counts in the ban's
/// window: that, the per-client cap and the sign-in pool's own room keep the store from flooding.
pub async fn login_options(
    State(state): State<AppState>,
    lang: Lang,
    session: Session,
    ceremony: Ceremony,
) -> Response {
    let ip = ceremony.client_key.clone();
    security::check_and_ban_ip_if_needed(&state.db, &ip).await;
    let banned = security::is_ip_banned(&state.db, &ip).await;
    let Some(rp_id) = ceremony.rp_id.clone() else {
        return json_refused(
            StatusCode::NOT_FOUND,
            t(lang, "passkeys.sign_in.failed"),
            None,
        );
    };
    // Loads the record first: a cookie whose session is gone gets a new id (qa-passkeys-1 B-N6).
    let fresh = session.is_empty().await || session.id().is_none();
    if !fresh
        && let Some(session_id) = passkeys::session_id(&session)
        && let Some((challenge, left)) = state.passkeys.challenges.live_sign_in(&session_id, &rp_id)
    {
        return json(
            StatusCode::OK,
            passkeys::request_options(&challenge, &rp_id, &[], left),
        );
    }
    if banned {
        // A banned key (a shared address someone else got banned) still gets one live challenge at a time
        // (qa-passkeys-2 limiter L3): a passkey can't be guessed, and `/auth/passkey` lets only a valid one through,
        // every refusal counted as before. Nothing more is counted here - the key is banned already.
        if state.passkeys.challenges.live_sign_ins_of(&ip) >= 1 {
            return json_refused(
                StatusCode::TOO_MANY_REQUESTS,
                t(lang, "passkeys.sign_in.banned"),
                None,
            );
        }
    } else {
        // Counted before anything is issued; the session's aged-out rows go first, a younger one whose challenge
        // was evicted keeps counting until a sign-in (qa-passkeys-1 B-RN1, qa-passkeys-2 limiter L4).
        let attempt: Option<i64> = sqlx::query_scalar(
            "INSERT INTO security_events (event_type, ip_address) VALUES ('passkey_started', ?) \
             RETURNING id",
        )
        .bind(&ip)
        .fetch_one(&state.db)
        .await
        .ok();
        if let Some(attempt) = attempt {
            passkeys::note_started(&state.db, &session, attempt).await;
        }
    }
    // The challenge is bound to this session: a browser without one gets a short-lived one.
    if session.id().is_none() {
        session.set_expiry(Some(tower_sessions::Expiry::OnInactivity(
            tower_sessions::cookie::time::Duration::minutes(PRE_SIGN_IN_MINUTES),
        )));
    }
    if let Err(err) = session.save().await {
        tracing::error!(%err, "couldn't save the session for a passkey sign-in");
        return StatusCode::INTERNAL_SERVER_ERROR.into_response();
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
    // The sign-in pool makes room for it (its oldest go): never refused.
    let Some(challenge) =
        state
            .passkeys
            .challenges
            .issue(bound, passkeys::Asker::of(&ceremony), Taken::default())
    else {
        return StatusCode::INTERNAL_SERVER_ERROR.into_response();
    };
    json(
        StatusCode::OK,
        passkeys::request_options(&challenge, &rp_id, &[], passkeys::CHALLENGE_TTL),
    )
}

#[derive(Deserialize)]
pub struct SignInForm {
    #[serde(default)]
    assertion: String,
    /// Where the sign-in goes on to (step 3: a local path, `lock::local_next`; else Devices).
    #[serde(default)]
    next: String,
}

/// `POST /auth/passkey` (D1.4, D1.5).
pub async fn login(
    State(state): State<AppState>,
    lang: Lang,
    session: Session,
    ceremony: Ceremony,
    headers: axum::http::HeaderMap,
    Form(form): Form<SignInForm>,
) -> Response {
    let next = crate::lock::local_next(&form.next);
    let ip = ceremony.client_key.clone();
    // The options' count ends here whatever comes: a refusal counts on its own (B-M3).
    passkeys::end_sign_in_attempt(&state.db, &session).await;
    security::check_and_ban_ip_if_needed(&state.db, &ip).await;
    // A banned key's assertion is verified all the same (qa-passkeys-2 limiter L3): a valid passkey signs in through a
    // ban on a shared address; a refusal is counted as always and answered 429.
    let banned = security::is_ip_banned(&state.db, &ip).await;
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
            let view =
                crate::handlers::auth::LoginView::load(&state.db, ceremony.rp_id.is_some()).await;
            if banned || security::is_ip_banned(&state.db, &ip).await {
                return (
                    StatusCode::TOO_MANY_REQUESTS,
                    crate::handlers::auth::render_login(
                        Some(t(lang, "passkeys.sign_in.banned").to_string()),
                        lang,
                        view,
                    ),
                )
                    .into_response();
            }
            return crate::handlers::auth::render_login_next(
                Some(t(lang, view.passkey_failed()).to_string()),
                lang,
                view,
                next.as_deref(),
            )
            .into_response();
        }
    };
    let admin = signed_in.admin;
    // Signed in: every open row of this pre-session goes (qa-passkeys-2 limiter L4).
    passkeys::sign_in_done(&state.db, &session).await;
    // A fresh session id first (qa-passkeys-1 A-N3): on a store error nothing is signed in.
    if let Err(err) = session.cycle_id().await {
        tracing::error!(%err, "couldn't cycle the session id at a passkey sign-in");
        return StatusCode::INTERNAL_SERVER_ERROR.into_response();
    }
    // The same completion as `verify_2fa`: the sign-in is logged and the session stores the
    // account, its epoch and the sign-in's time. Wrong two-factor codes since the last complete
    // sign-in are told as after `verify_2fa` (whoever typed them knew the password); a running
    // TOTP lock and its doubling stay (qa-passkeys-1 B-L1: they guard the TOTP step, which this
    // sign-in didn't take).
    let warning =
        admin_auth::login_warning(&state.db, &admin.username, admin.failed_login_attempts).await;
    admin_auth::reset_after_passkey(&state.db, admin.id).await;
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
    // Design 74 D3.1: this browser's app lock comes back with the new sign-in (the parent has a passkey here: this
    // one).
    crate::lock::carry(&state, &session, &headers, admin.id, &ceremony).await;
    // Step 3: a local `next` (the lock screen's Sign Out keeps one), else Devices (D1.5).
    Redirect::to(next.as_deref().unwrap_or("/")).into_response()
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
    let Some(challenge) =
        state
            .passkeys
            .challenges
            .issue(bound, passkeys::Asker::of(&ceremony), Taken::default())
    else {
        return json_refused(
            StatusCode::SERVICE_UNAVAILABLE,
            t(lang, "passkeys.reauth.failed"),
            None,
        );
    };
    json(
        StatusCode::OK,
        passkeys::request_options(&challenge, &rp_id, &here, passkeys::CHALLENGE_TTL),
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

/// A wrong password at a passkey's add or removal in the log: `passkey_change_refused` (what it was
/// for, "wrong current password"; qa-passkeys-1 B-L5 - no passkey was tried); a passkey that didn't
/// prove the parent was logged `passkey_refused` by `admin_auth::reauth` already.
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
        "passkey_change_refused",
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
        Reauth::Busy => return admin_auth::busy_response(lang),
        Reauth::SignedOut => return signed_out_json(),
        // A passkey-only parent's empty password: nothing counted (qa-passkeys-2 M1).
        Reauth::NeedsPasskey => {
            return json_refused(
                StatusCode::BAD_REQUEST,
                t(lang, "passkeys.only.needs_passkey"),
                Some("password"),
            );
        }
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
    let taken = Taken { label: Some(name) };
    let Some(challenge) =
        state
            .passkeys
            .challenges
            .issue(bound, passkeys::Asker::of(&ceremony), taken)
    else {
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
        Reauth::Busy => return admin_auth::busy_response(lang),
        Reauth::SignedOut => return admin_auth::signed_out_response(),
        reauth @ (Reauth::Wrong { .. } | Reauth::NeedsPasskey) => {
            let words = if let Reauth::Wrong { left } = reauth {
                log_wrong(&state, &admin, &proof, "security.detail.passkey.remove").await;
                wrong_words(
                    lang,
                    &proof,
                    left,
                    "account.password.wrong",
                    "account.password.wrong_last",
                )
            } else {
                t(lang, "passkeys.only.needs_passkey").to_string()
            };
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
            // The other sessions end, their notifications too; this browser keeps its own (2026-11-04).
            let this_browser = crate::push::this_browser(&session).await;
            crate::push::forget_subscriptions(
                &state,
                &admin,
                crate::push::Forget::PasskeyRemoved,
                this_browser.as_deref(),
            )
            .await;
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
            let to = Redirect::to("/account?done=passkey_removed#passkeys").into_response();
            // Design 74 D3.1: the last passkey for this address gone, this browser's app lock goes with it - it could
            // never be opened (the other sessions ended with the epoch).
            if crate::lock::off_without_passkey(&state, &session, admin.id, &ceremony).await {
                return crate::lock::with_cookie(to, crate::lock::insecure(&state), 0);
            }
            to
        }
        Ok(passkeys::Removed::Gone) => {
            crate::settings_log::refused(Redirect::to("/account?done=passkey_gone#passkeys"))
        }
        // D2.3: a passkey-only account keeps at least one.
        Ok(passkeys::Removed::Last) => {
            let answer = AccountAnswer {
                field_error: Some((remove_field(id), t(lang, "passkeys.only.last").to_string())),
                ..AccountAnswer::default()
            };
            render_account(
                &state,
                &session,
                &admin,
                &ceremony,
                StatusCode::BAD_REQUEST,
                answer,
                lang,
            )
            .await
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

// ---------------------------------------------------------------------------------------------
// "Kun passkey" (step 2)

#[derive(Deserialize)]
pub struct PasskeyOnlyForm {
    #[serde(default)]
    assertion: String,
}

/// The field "Kun passkey"'s confirm shows a refusal under, for [AccountAnswer::field_error].
const PASSKEY_ONLY_FIELD: &str = "passkey-only";

/// `POST /account/passkey-only` (D2.1, D2.2): a passkey that works here confirms it (a `reauth` challenge bound to
/// this route; a bad one counts in `reauth_wrong`), then [passkeys::switch_on]'s one `UPDATE` - its guards, at least
/// one passkey included, counted in the statement. This session keeps the new epoch; every other session and every
/// device's notifications end. PRG `/account?done=passkey_only_on#passkey-only`.
pub async fn passkey_only_on(
    State(state): State<AppState>,
    lang: Lang,
    session: Session,
    ceremony: Ceremony,
    Extension(CurrentAdmin(admin)): Extension<CurrentAdmin>,
    Form(form): Form<PasskeyOnlyForm>,
) -> Response {
    if admin.passkey_only {
        return Redirect::to("/account#passkey-only").into_response();
    }
    let refuse = |why: String| AccountAnswer {
        field_error: Some((PASSKEY_ONLY_FIELD.to_string(), why)),
        ..AccountAnswer::default()
    };
    // Only a passkey proves one works on this host (D2.1): the password is no proof here.
    if form.assertion.trim().is_empty() {
        let answer = refuse(t(lang, "passkeys.only.needs_passkey").to_string());
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
    let proof = Proof::Passkey {
        assertion: &form.assertion,
        ceremony: &ceremony,
    };
    match admin_auth::reauth(&state, &session, &admin, proof).await {
        Reauth::Right => {}
        Reauth::Busy => return admin_auth::busy_response(lang),
        Reauth::SignedOut => return admin_auth::signed_out_response(),
        // Never with a passkey's proof; answered as a missing one.
        Reauth::NeedsPasskey => {
            let answer = refuse(t(lang, "passkeys.only.needs_passkey").to_string());
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
        Reauth::Wrong { left } => {
            let words = wrong_words(lang, &proof, left, "", "");
            return render_account(
                &state,
                &session,
                &admin,
                &ceremony,
                StatusCode::BAD_REQUEST,
                refuse(words),
                lang,
            )
            .await;
        }
    }
    match passkeys::switch_on(&state.db, &admin).await {
        Ok(passkeys::SwitchedOn::Done(epoch)) => {
            session.insert(admin_auth::EPOCH_KEY, epoch).await.ok();
            // The password that is gone may be known: whatever it opened ends, its notifications too (as a
            // password change, security review M1) - but this browser's, the session that made the change and goes
            // on (Push never lost silently, 2026-11-04).
            let this_browser = crate::push::this_browser(&session).await;
            crate::push::forget_subscriptions(
                &state,
                &admin,
                crate::push::Forget::PasskeyOnly,
                this_browser.as_deref(),
            )
            .await;
            security::record_security_event(
                &state.db,
                "passkey_only_on",
                Some(&admin.username),
                None,
                Some(security::Detail::words(
                    "security.detail.other_sessions_signed_out",
                    &[],
                )),
            )
            .await;
            Redirect::to("/account?done=passkey_only_on#passkey-only").into_response()
        }
        Ok(passkeys::SwitchedOn::Already) => Redirect::to("/account#passkey-only").into_response(),
        Ok(passkeys::SwitchedOn::NoPasskey) => {
            let answer = refuse(t(lang, "passkeys.only.add_first").to_string());
            render_account(
                &state,
                &session,
                &admin,
                &ceremony,
                StatusCode::BAD_REQUEST,
                answer,
                lang,
            )
            .await
        }
        Ok(passkeys::SwitchedOn::SignedOut) => {
            session.flush().await.ok();
            admin_auth::signed_out_response()
        }
        Err(err) => {
            tracing::error!(%err, "couldn't turn passkey only on");
            crate::i18n::error(
                lang,
                StatusCode::INTERNAL_SERVER_ERROR,
                "passkeys.only.failed",
            )
        }
    }
}

/// The switch-off page's TOTP secret until its POST stores it (D2.5): in this session, so a reload shows the same
/// QR and nothing is written to the account before the one `UPDATE`.
const PASSWORD_ON_SECRET_KEY: &str = "password_on_totp";

#[derive(askama::Template)]
#[template(path = "password_sign_in.html")]
struct PasswordOnTemplate {
    lang: Lang,
    title: String,
    username: String,
    qr_base64: String,
    secret_base32: String,
    /// A passkey for this host (the confirm), else the reason.
    passkey_reauth: bool,
    /// The field a refusal names ("new_password", "confirm_password", "code", "passkey") and why.
    field_error: Option<(&'static str, String)>,
}
crate::i18n::words!(PasswordOnTemplate);

impl PasswordOnTemplate {
    fn error_for(&self, field: &str) -> Option<&str> {
        self.field_error
            .as_ref()
            .filter(|(f, _)| *f == field)
            .map(|(_, why)| why.as_str())
    }

    /// The key in blocks of 4, as the 2FA setup shows it.
    fn secret_blocks(&self) -> String {
        let chars: Vec<char> = self.secret_base32.chars().collect();
        chars
            .chunks(4)
            .map(|block| block.iter().collect::<String>())
            .collect::<Vec<_>>()
            .join(" ")
    }
}

/// The page, with this session's secret (made on the first view).
async fn render_password_on(
    state: &AppState,
    session: &Session,
    admin: &crate::models::AdminUser,
    ceremony: &Ceremony,
    status: StatusCode,
    field_error: Option<(&'static str, String)>,
    lang: Lang,
) -> Response {
    let secret = match session.get::<String>(PASSWORD_ON_SECRET_KEY).await {
        Ok(Some(secret)) => secret,
        _ => {
            let secret = security::generate_totp_secret_base32();
            if let Err(err) = session.insert(PASSWORD_ON_SECRET_KEY, &secret).await {
                tracing::error!(%err, "couldn't keep the new two-factor secret");
                return StatusCode::INTERNAL_SERVER_ERROR.into_response();
            }
            secret
        }
    };
    let Ok(qr_base64) = security::totp_for_secret(&secret, &admin.username).get_qr_base64() else {
        return StatusCode::INTERNAL_SERVER_ERROR.into_response();
    };
    let page = PasswordOnTemplate {
        lang,
        title: t(lang, "password_on.title").to_string(),
        username: admin.username.clone(),
        qr_base64,
        secret_base32: secret,
        passkey_reauth: passkeys::has_here(&state.db, admin.id, ceremony).await,
        field_error,
    };
    match askama::Template::render(&page) {
        Ok(html) => (status, axum::response::Html(html)).into_response(),
        Err(err) => {
            tracing::error!(%err, "couldn't render the switch-off page");
            StatusCode::INTERNAL_SERVER_ERROR.into_response()
        }
    }
}

/// `GET /account/password-sign-in` (D2.5): only while "Kun passkey" is on.
pub async fn password_sign_in_form(
    State(state): State<AppState>,
    lang: Lang,
    session: Session,
    ceremony: Ceremony,
    Extension(CurrentAdmin(admin)): Extension<CurrentAdmin>,
) -> Response {
    if !admin.passkey_only {
        return Redirect::to("/account#password").into_response();
    }
    render_password_on(
        &state,
        &session,
        &admin,
        &ceremony,
        StatusCode::OK,
        None,
        lang,
    )
    .await
}

#[derive(Deserialize)]
pub struct PasswordOnForm {
    #[serde(default)]
    new_password: String,
    #[serde(default)]
    confirm_password: String,
    #[serde(default)]
    code: String,
    #[serde(default)]
    assertion: String,
}

/// `POST /account/password-sign-in` (D2.5): the fields first (the password twice, the TOTP code against this
/// session's secret - read only), then the passkey (it is spent last, so a typo doesn't cost a prompt), the new
/// hash behind the gate, and [passkeys::switch_off]'s one `UPDATE`. The passkeys stay, no session ends.
/// PRG `/account?done=password_on#password`, `passkey_only_off`.
pub async fn password_sign_in(
    State(state): State<AppState>,
    lang: Lang,
    session: Session,
    ceremony: Ceremony,
    Extension(CurrentAdmin(admin)): Extension<CurrentAdmin>,
    Form(form): Form<PasswordOnForm>,
) -> Response {
    if !admin.passkey_only {
        return Redirect::to("/account#password").into_response();
    }
    let refuse = |field: &'static str, why: String| {
        render_password_on(
            &state,
            &session,
            &admin,
            &ceremony,
            StatusCode::BAD_REQUEST,
            Some((field, why)),
            lang,
        )
    };
    if form.new_password.len() < security::MIN_PASSWORD_LEN {
        return refuse(
            "new_password",
            t(lang, "account.password.too_short").to_string(),
        )
        .await;
    }
    if form.new_password != form.confirm_password {
        return refuse(
            "confirm_password",
            t(lang, "account.password.mismatch").to_string(),
        )
        .await;
    }
    let secret = session
        .get::<String>(PASSWORD_ON_SECRET_KEY)
        .await
        .ok()
        .flatten();
    let step = secret.as_deref().and_then(|secret| {
        admin_auth::totp_step(secret, &admin.username, &form.code, admin_auth::now_secs())
    });
    let (Some(secret), Some(step)) = (secret, step) else {
        return refuse("code", t(lang, "password_on.code_wrong").to_string()).await;
    };
    if form.assertion.trim().is_empty() {
        return refuse(
            "passkey",
            t(lang, "passkeys.only.needs_passkey").to_string(),
        )
        .await;
    }
    let proof = Proof::Passkey {
        assertion: &form.assertion,
        ceremony: &ceremony,
    };
    match admin_auth::reauth(&state, &session, &admin, proof).await {
        Reauth::Right => {}
        Reauth::Busy => return admin_auth::busy_response(lang),
        Reauth::SignedOut => return admin_auth::signed_out_response(),
        // Never with a passkey's proof; answered as a missing one.
        Reauth::NeedsPasskey => {
            return refuse(
                "passkey",
                t(lang, "passkeys.only.needs_passkey").to_string(),
            )
            .await;
        }
        Reauth::Wrong { left } => {
            return refuse("passkey", wrong_words(lang, &proof, left, "", "")).await;
        }
    }
    let hash = match state.passwords.hash(&form.new_password).await {
        Ok(hash) => hash,
        Err(admin_auth::Busy) => return admin_auth::busy_response(lang),
    };
    match passkeys::switch_off(&state.db, &admin, &hash, &secret, step).await {
        Ok(Some(_)) => {
            session.remove::<String>(PASSWORD_ON_SECRET_KEY).await.ok();
            security::record_security_event(
                &state.db,
                "passkey_only_off",
                Some(&admin.username),
                None,
                None,
            )
            .await;
            Redirect::to("/account?done=password_on#password").into_response()
        }
        // Nothing written: switched off already in another tab (this session is still the account's), or signed out.
        Ok(None) => {
            let still: bool = sqlx::query_scalar(
                "SELECT EXISTS (SELECT 1 FROM admin_users WHERE id = ? AND session_epoch = ? \
                 AND removed_at IS NULL)",
            )
            .bind(admin.id)
            .bind(admin.session_epoch)
            .fetch_one(&state.db)
            .await
            .unwrap_or(false);
            if still {
                session.remove::<String>(PASSWORD_ON_SECRET_KEY).await.ok();
                Redirect::to("/account#password").into_response()
            } else {
                session.flush().await.ok();
                admin_auth::signed_out_response()
            }
        }
        Err(err) => {
            tracing::error!(%err, "couldn't turn the password on again");
            crate::i18n::error(
                lang,
                StatusCode::INTERNAL_SERVER_ERROR,
                "password_on.failed",
            )
        }
    }
}
