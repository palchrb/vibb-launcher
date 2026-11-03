//! The app lock's routes (design 74 §3, step 3; the rules are `crate::lock`).
//!
//! - `GET /lock?next=` (§6.5): the lock screen - no tab bar, `no-store`, nothing private (no name, no phone, no
//!   count). With the lock off it goes on to `next` (a local path) or Devices.
//! - `POST /lock/options` (form `hint`, optional): an `unlock` challenge bound to `/lock`, `allowCredentials` = the
//!   signed-in parent's passkeys for this RP ID (D3.5) - only the `hint`'s one when it names one of them (the passkey
//!   this browser unlocked with last, so the prompt needs no pick; anything else is ignored). `static/passkey.js`
//!   asks with the hint on the first tap of the screen, and without it for every tap after a cancel or a failure.
//! - `POST /lock` (form `assertion`, `next`): the parent's own credential only; done, the session's use moves, its
//!   id is cycled (D3.7) and the answer is `{"next": <local path>}` (the page replaces itself with it, so Back never
//!   returns to the lock); a bad assertion is `app_unlock_failed` and counts in `reauth_wrong` (3 end every session).
//! - `POST /api/used`: the page's input beacon (D3.3) - the lock's check moved the session's use already; 204.
//! - `POST /account/lock` (form `minutes`, `assertion`): the lock time for this browser (D3.1, D3.2) behind a passkey
//!   that works here, PRG `/account?done=lock#app-lock`, `app_lock_set`, the lock cookie set or deleted.

use axum::extract::{Query, State};
use axum::http::{StatusCode, header};
use axum::response::{IntoResponse, Redirect, Response};
use axum::{Extension, Form};
use serde::Deserialize;
use tower_sessions::Session;

use crate::AppState;
use crate::admin_auth::{self, Proof, Reauth};
use crate::handlers::auth::{AccountAnswer, render_account};
use crate::i18n::{Lang, Words, t};
use crate::lock;
use crate::passkeys::{self, Bound, Ceremony, Kind, Taken};
use crate::security::{self, CurrentAdmin};

fn json(status: StatusCode, body: serde_json::Value) -> Response {
    (
        status,
        [(header::CACHE_CONTROL, "no-store")],
        axum::Json(body),
    )
        .into_response()
}

/// Where an unlock goes on to: `next` when it is a local path, else Devices.
fn next_or_home(next: Option<&str>) -> String {
    next.and_then(lock::local_next)
        .unwrap_or_else(|| "/".to_string())
}

#[derive(askama::Template)]
#[template(path = "lock.html")]
struct LockTemplate {
    lang: Lang,
    /// Empty: the head says only "Vibb" (§6.5).
    title: String,
    /// The local path the unlock goes on to (empty: Devices).
    next: String,
    /// The parent has a passkey for this RP ID: "Unlock" works here.
    passkey_here: bool,
}
crate::i18n::words!(LockTemplate);

#[derive(Deserialize)]
pub struct NextQuery {
    #[serde(default)]
    next: String,
}

/// `GET /lock` (§6.5).
pub async fn page(
    State(state): State<AppState>,
    lang: Lang,
    session: Session,
    ceremony: Ceremony,
    Extension(CurrentAdmin(admin)): Extension<CurrentAdmin>,
    Query(query): Query<NextQuery>,
) -> Response {
    let next = lock::local_next(&query.next);
    // The page asks for the lock whenever its own clock says so (it locks a minute before the server, D3.3): the
    // screen shows while the lock is on, due or not; with it off there is nothing to open.
    if !lock::Held::of(&session).await.on() {
        return Redirect::to(&next_or_home(next.as_deref())).into_response();
    }
    let page = LockTemplate {
        lang,
        title: String::new(),
        next: next.unwrap_or_default(),
        passkey_here: passkeys::has_here(&state.db, admin.id, &ceremony).await,
    };
    match askama::Template::render(&page) {
        Ok(html) => (
            [(header::CACHE_CONTROL, "no-store")],
            axum::response::Html(html),
        )
            .into_response(),
        Err(err) => {
            tracing::error!(%err, "couldn't render the lock screen");
            StatusCode::INTERNAL_SERVER_ERROR.into_response()
        }
    }
}

#[derive(Deserialize)]
pub struct OptionsForm {
    /// The credential id (base64url, as `rawId`) this browser unlocked with last.
    #[serde(default)]
    hint: String,
}

/// The passkeys an unlock's prompt offers: only the hint's one when it is one of `here` (the parent's own passkeys for
/// this RP ID), else all of them - a hint narrows within the parent's set and never adds to it.
fn offered(mut here: Vec<passkeys::PasskeyRow>, hint: &str) -> Vec<passkeys::PasskeyRow> {
    let hint = hint.trim();
    match here
        .iter()
        .position(|row| !hint.is_empty() && crate::webauthn::b64url(&row.credential_id) == hint)
    {
        Some(at) => vec![here.swap_remove(at)],
        None => here,
    }
}

/// `POST /lock/options` (D3.5): the signed-in parent's passkeys here (the hint's one alone when it names one), an
/// `unlock` challenge bound to `/lock`.
pub async fn options(
    State(state): State<AppState>,
    lang: Lang,
    session: Session,
    ceremony: Ceremony,
    Extension(CurrentAdmin(admin)): Extension<CurrentAdmin>,
    form: Result<Form<OptionsForm>, axum::extract::rejection::FormRejection>,
) -> Response {
    // The hint is a convenience: a form that can't be read asks with every passkey, as without one.
    let hint = form.map(|Form(form)| form.hint).unwrap_or_default();
    let refused = |status| {
        json(
            status,
            serde_json::json!({ "error": t(lang, "lock.page.failed") }),
        )
    };
    let (Some(rp_id), Some(session_id)) = (ceremony.rp_id.clone(), passkeys::session_id(&session))
    else {
        return refused(StatusCode::NOT_FOUND);
    };
    let here: Vec<passkeys::PasskeyRow> = match passkeys::of_admin(&state.db, admin.id).await {
        Ok(rows) => rows.into_iter().filter(|row| row.rp_id == rp_id).collect(),
        Err(err) => {
            tracing::error!(%err, "couldn't read the passkeys");
            return StatusCode::INTERNAL_SERVER_ERROR.into_response();
        }
    };
    if here.is_empty() {
        return refused(StatusCode::NOT_FOUND);
    }
    let bound = Bound {
        kind: Kind::Unlock,
        session: session_id,
        admin: Some((admin.id, admin.session_epoch)),
        rp_id: rp_id.clone(),
        route: lock::LOCK_ROUTE.to_string(),
    };
    let Some(challenge) =
        state
            .passkeys
            .challenges
            .issue(bound, passkeys::Asker::of(&ceremony), Taken::default())
    else {
        return refused(StatusCode::SERVICE_UNAVAILABLE);
    };
    json(
        StatusCode::OK,
        passkeys::request_options(
            &challenge,
            &rp_id,
            &offered(here, &hint),
            passkeys::CHALLENGE_TTL,
        ),
    )
}

#[derive(Deserialize)]
pub struct UnlockForm {
    #[serde(default)]
    assertion: String,
    #[serde(default)]
    next: String,
}

/// `POST /lock` (D3.5, D3.7).
pub async fn unlock(
    State(state): State<AppState>,
    lang: Lang,
    session: Session,
    ceremony: Ceremony,
    Extension(CurrentAdmin(admin)): Extension<CurrentAdmin>,
    Form(form): Form<UnlockForm>,
) -> Response {
    let next = next_or_home(Some(&form.next));
    if !lock::Held::of(&session).await.on() {
        return json(StatusCode::OK, serde_json::json!({ "next": next }));
    }
    let proved = passkeys::unlock(&state, &session, &ceremony, &admin, &form.assertion).await;
    if let Err(why) = proved {
        // A copied passkey's counter is its own line (D5.3); every bad assertion is an unlock that failed (D3.5).
        if why == crate::webauthn::Refused::Counter {
            passkeys::log_refused(
                &state.db,
                why,
                "security.detail.app_lock.unlock",
                Some(&admin.username),
                Some(&ceremony.client_key),
            )
            .await;
        }
        // The client as the sign-in events name it (its limit key, qa-passkeys-3 server N1).
        security::record_security_event(
            &state.db,
            "app_unlock_failed",
            Some(&admin.username),
            Some(&ceremony.client_key),
            Some(security::Detail::facts(why.code())),
        )
        .await;
    }
    match admin_auth::record_reauth(&state.db, &session, &admin, proved.is_ok()).await {
        Reauth::Right => {}
        Reauth::SignedOut => {
            return json(
                StatusCode::UNAUTHORIZED,
                serde_json::json!({ "signed_out": true }),
            );
        }
        _ => {
            return json(
                StatusCode::BAD_REQUEST,
                serde_json::json!({ "error": t(lang, "lock.page.failed") }),
            );
        }
    }
    // An unlock is no sign-in (D3.7): the sign-in time stays, the 90-day clock runs on; the id is cycled.
    if let Err(err) = session.cycle_id().await {
        tracing::error!(%err, "couldn't cycle the session id at an unlock");
        return StatusCode::INTERNAL_SERVER_ERROR.into_response();
    }
    lock::used_now(&session).await;
    lock::record_unlocked(&state.db, &admin.username, Some(&ceremony.client_key)).await;
    json(StatusCode::OK, serde_json::json!({ "next": next }))
}

/// `POST /api/used` (D3.3): the page's input beacon. `lock::check` moved the session's use on the way in.
pub async fn used() -> Response {
    (
        StatusCode::NO_CONTENT,
        [(header::CACHE_CONTROL, "no-store")],
    )
        .into_response()
}

#[derive(Deserialize)]
pub struct LockForm {
    #[serde(default)]
    minutes: String,
    #[serde(default)]
    assertion: String,
}

/// The field Account shows a refused lock time under ([AccountAnswer::field_error]).
pub(crate) const LOCK_FIELD: &str = "app-lock";

/// A lock time in the security log: "5 min", "1 hour", "Off".
fn minutes_detail(minutes: u32) -> security::Detail {
    let minutes_text = minutes.to_string();
    match minutes {
        0 => security::Detail::words("security.detail.app_lock.off", &[]),
        60 => security::Detail::words("security.detail.app_lock.hour", &[]),
        _ => security::Detail::words(
            "security.detail.app_lock.minutes",
            &[("minutes", &minutes_text)],
        ),
    }
}

/// `POST /account/lock` (D3.1, D3.2): a passkey that works here confirms every change (turning it on proves the unlock
/// will work; off and another time too, so a session someone holds for a minute can't turn it off for good).
pub async fn set(
    State(state): State<AppState>,
    lang: Lang,
    session: Session,
    ceremony: Ceremony,
    Extension(CurrentAdmin(admin)): Extension<CurrentAdmin>,
    Form(form): Form<LockForm>,
) -> Response {
    let Some(minutes) = form
        .minutes
        .trim()
        .parse::<u32>()
        .ok()
        .filter(|m| *m == 0 || lock::valid_minutes(*m))
    else {
        return crate::i18n::error(lang, StatusCode::BAD_REQUEST, "lock.set.bad_time");
    };
    if lock::Held::of(&session).await.minutes == minutes {
        return Redirect::to("/account#app-lock").into_response();
    }
    let refuse = |why: String| AccountAnswer {
        field_error: Some((LOCK_FIELD.to_string(), why)),
        ..AccountAnswer::default()
    };
    let render = |answer| {
        render_account(
            &state,
            &session,
            &admin,
            &ceremony,
            StatusCode::BAD_REQUEST,
            answer,
            lang,
        )
    };
    if !passkeys::has_here(&state.db, admin.id, &ceremony).await {
        return render(refuse(t(lang, "lock.add_first").to_string())).await;
    }
    if form.assertion.trim().is_empty() {
        return render(refuse(t(lang, "passkeys.only.needs_passkey").to_string())).await;
    }
    let proof = Proof::Passkey {
        assertion: &form.assertion,
        ceremony: &ceremony,
    };
    match admin_auth::reauth(&state, &session, &admin, proof).await {
        Reauth::Right => {}
        Reauth::Busy => return admin_auth::busy_response(),
        Reauth::SignedOut => return admin_auth::signed_out_response(),
        Reauth::NeedsPasskey => {
            return render(refuse(t(lang, "passkeys.only.needs_passkey").to_string())).await;
        }
        Reauth::Wrong { left } => {
            let words = crate::handlers::passkeys::wrong_words(lang, &proof, left, "", "");
            return render(refuse(words)).await;
        }
    }
    if let Err(err) = lock::set(&session, minutes).await {
        tracing::error!(%err, "couldn't keep the app lock in the session");
        return StatusCode::INTERNAL_SERVER_ERROR.into_response();
    }
    security::record_security_event(
        &state.db,
        "app_lock_set",
        Some(&admin.username),
        None,
        Some(minutes_detail(minutes)),
    )
    .await;
    let mut done = lock::with_cookie(
        Redirect::to("/account?done=lock#app-lock").into_response(),
        lock::insecure(&state),
        minutes,
    );
    // Turned on: the pages this browser kept from before go (qa-passkeys-3 client M1) - they carry no lock, and a Back
    // could show one from the cache. From now on a lock-on session's pages are never kept (`lock::mark_page`).
    if minutes > 0 {
        done.headers_mut().insert(
            "clear-site-data",
            axum::http::HeaderValue::from_static("\"cache\""),
        );
    }
    done
}
