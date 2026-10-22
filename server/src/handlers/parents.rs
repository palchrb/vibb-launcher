//! Account's Name and Parents groups (design 62 §3, §4; the statements are in `crate::parents`).
//!
//! - `POST /account/name`: your own display name, PRG to `/account?changed=name#name`.
//! - `POST /account/parents`: Add Parent behind your password. The one-time password is only in
//!   this POST's own page (no redirect, no address, no file, never logged); its hash is made in
//!   the same gated Argon2 job that checks your password (`PasswordGate::change`), then counted
//!   by `record_reauth` - three wrong in a row sign you out.
//! - `POST /account/parents/{id}/remove`: Withdraw (Remove's label on a not-ready row), behind
//!   `admin_auth::reauth`; PRG to `/account?parents=removed#parents`.
//!
//! A refusal renders Account (400) with its form open and the reason under the field; every form
//! posts to its group's fragment, so no answer jumps to the top.

use axum::extract::{Path, State};
use axum::http::StatusCode;
use axum::response::{IntoResponse, Redirect, Response};
use axum::{Extension, Form};
use serde::Deserialize;
use tower_sessions::Session;

use crate::handlers::auth::{AccountAnswer, OtpShown, render_account, withdraw_field};
use crate::parents::{Added, Removed};
use crate::security::{self, CurrentAdmin};
use crate::{AppState, admin_auth};

/// The Name form.
#[derive(Deserialize)]
pub struct NameForm {
    #[serde(default)]
    display_name: String,
}

/// Sets your own display name (design 62 §3): no password asked, for this session's epoch and a
/// live account; logged `display_name_changed`.
pub async fn set_name(
    State(state): State<AppState>,
    session: Session,
    Extension(CurrentAdmin(admin)): Extension<CurrentAdmin>,
    Form(form): Form<NameForm>,
) -> Response {
    let name = match security::display_name(&form.display_name) {
        Ok(name) => name,
        Err(why) => {
            let answer = AccountAnswer {
                field_error: Some(("display_name".to_string(), why.to_string())),
                name_typed: Some(form.display_name),
                ..AccountAnswer::default()
            };
            return render_account(&state, &session, &admin, StatusCode::BAD_REQUEST, answer).await;
        }
    };
    let saved = sqlx::query(
        "UPDATE admin_users SET display_name = ? \
         WHERE id = ? AND session_epoch = ? AND removed_at IS NULL",
    )
    .bind(&name)
    .bind(admin.id)
    .bind(admin.session_epoch)
    .execute(&state.db)
    .await;
    match saved {
        Ok(done) if done.rows_affected() == 1 => {}
        Ok(_) => {
            session.flush().await.ok();
            return admin_auth::signed_out_response();
        }
        Err(err) => {
            tracing::error!(%err, "couldn't save the display name");
            return StatusCode::INTERNAL_SERVER_ERROR.into_response();
        }
    }
    security::record_security_event(
        &state.db,
        "display_name_changed",
        Some(&admin.username),
        None,
        Some(name.as_deref().unwrap_or("(the username)")),
    )
    .await;
    Redirect::to("/account?changed=name#name").into_response()
}

/// The Add Parent form (design 62 §3): `new_username` (never `username`, so a password manager
/// doesn't pair it with your password), the name, your password.
#[derive(Deserialize)]
pub struct AddForm {
    #[serde(default)]
    new_username: String,
    #[serde(default)]
    name: String,
    #[serde(default)]
    current_password: String,
}

const USERNAME_RULE: &str =
    "Use 1-32 lowercase letters, digits, '.', '_' or '-'. No one was added.";

/// The refusal text for the [admin_auth::Reauth::Wrong]th password.
fn wrong_password(left: i64, nothing: &str) -> String {
    if left == 1 {
        format!("The password is wrong. {nothing} One more wrong one signs you out.")
    } else {
        format!("The password is wrong. {nothing}")
    }
}

/// Add Parent (design 62 §4.1). The username is trimmed and lowercased, then checked; whether it
/// is taken is said only after your password was right (no enumeration).
pub async fn add(
    State(state): State<AppState>,
    session: Session,
    Extension(CurrentAdmin(actor)): Extension<CurrentAdmin>,
    Form(form): Form<AddForm>,
) -> Response {
    let refuse = |field: &str, why: String| AccountAnswer {
        field_error: Some((field.to_string(), why)),
        add_open: true,
        add_typed: (form.new_username.clone(), form.name.clone()),
        ..AccountAnswer::default()
    };
    let username = form.new_username.trim().to_lowercase();
    let display = match (
        security::valid_username(&username),
        security::display_name(&form.name),
    ) {
        (true, Ok(name)) => name,
        (valid, name) => {
            let answer = match name {
                Err(why) if valid => refuse("parent_name", format!("{why} No one was added.")),
                _ => refuse("new_username", USERNAME_RULE.to_string()),
            };
            return render_account(&state, &session, &actor, StatusCode::BAD_REQUEST, answer).await;
        }
    };

    // The one-time password's hash comes from the same gated job that checks yours.
    let otp = crate::startup::new_password();
    let changed = match state
        .passwords
        .change(&form.current_password, &actor.password_hash, &otp)
        .await
    {
        Ok(changed) => changed,
        Err(admin_auth::Busy) => return admin_auth::busy_response(),
    };
    if changed.is_none() {
        security::record_security_event(
            &state.db,
            "parent_change_refused",
            Some(&actor.username),
            None,
            Some("add"),
        )
        .await;
    }
    let reauth = admin_auth::record_reauth(&state.db, &session, &actor, changed.is_some()).await;
    let otp_hash = match (reauth, changed) {
        (admin_auth::Reauth::Right, Some(hash)) => hash,
        (admin_auth::Reauth::Wrong { left }, _) => {
            let answer = refuse("add_password", wrong_password(left, "No one was added."));
            return render_account(&state, &session, &actor, StatusCode::BAD_REQUEST, answer).await;
        }
        _ => return admin_auth::signed_out_response(),
    };

    let added =
        crate::parents::add_parent(&state.db, &actor, &username, display.as_deref(), &otp_hash)
            .await;
    let (expires_at, refused) = match added {
        Ok(Added::Done { expires_at, .. }) => (expires_at, None),
        Ok(Added::Taken) => (
            String::new(),
            Some("That username is taken. No one was added."),
        ),
        Ok(Added::Full) => (
            String::new(),
            Some("A family can have at most 8 parents. No one was added."),
        ),
        Ok(Added::SignedOut) => {
            session.flush().await.ok();
            return admin_auth::signed_out_response();
        }
        Err(err) => {
            tracing::error!(%err, "couldn't add the parent");
            return (
                StatusCode::INTERNAL_SERVER_ERROR,
                "Couldn't add the parent - no one was added. Check the server log.",
            )
                .into_response();
        }
    };
    if let Some(why) = refused {
        let answer = refuse("new_username", why.to_string());
        return render_account(&state, &session, &actor, StatusCode::BAD_REQUEST, answer).await;
    }
    let name = display.clone().unwrap_or_else(|| username.clone());
    security::record_security_event(
        &state.db,
        "parent_added",
        Some(&actor.username),
        None,
        Some(&format!("{username} ({name})")),
    )
    .await;
    let zone = crate::display_time::Zone::load(&state.db).await;
    let chars: Vec<char> = otp.chars().collect();
    let answer = AccountAnswer {
        otp: Some(OtpShown {
            until: zone.until_db(&expires_at, chrono::Utc::now()),
            chunks: chars.chunks(4).map(|c| c.iter().collect()).collect(),
            name,
            username,
        }),
        ..AccountAnswer::default()
    };
    render_account(&state, &session, &actor, StatusCode::OK, answer).await
}

/// The Withdraw (and, from step 3, Remove) form: your password.
#[derive(Deserialize)]
pub struct RemoveForm {
    #[serde(default)]
    current_password: String,
}

/// Withdraw an invitation (design 62 §4.2): the soft remove behind your password. Removing
/// yourself signs you out.
pub async fn remove(
    State(state): State<AppState>,
    session: Session,
    Extension(CurrentAdmin(actor)): Extension<CurrentAdmin>,
    Path(id): Path<i64>,
    Form(form): Form<RemoveForm>,
) -> Response {
    let target: Option<(String, Option<String>)> = match sqlx::query_as(
        "SELECT username, display_name FROM admin_users WHERE id = ? AND removed_at IS NULL",
    )
    .bind(id)
    .fetch_optional(&state.db)
    .await
    {
        Ok(target) => target,
        Err(err) => {
            tracing::error!(%err, "couldn't read the parent account");
            return StatusCode::INTERNAL_SERVER_ERROR.into_response();
        }
    };
    let refuse = |why: String| AccountAnswer {
        field_error: Some((withdraw_field(id), why)),
        ..AccountAnswer::default()
    };
    let target_username = target
        .as_ref()
        .map_or("", |(username, _)| username.as_str());
    match admin_auth::reauth(&state, &session, &actor, &form.current_password).await {
        admin_auth::Reauth::Right => {}
        admin_auth::Reauth::Busy => return admin_auth::busy_response(),
        reauth => {
            security::record_security_event(
                &state.db,
                "parent_change_refused",
                Some(&actor.username),
                None,
                Some(&format!("remove {target_username}")),
            )
            .await;
            let admin_auth::Reauth::Wrong { left } = reauth else {
                return admin_auth::signed_out_response();
            };
            let answer = refuse(wrong_password(left, "Nothing was changed."));
            return render_account(&state, &session, &actor, StatusCode::BAD_REQUEST, answer).await;
        }
    }
    match crate::parents::remove_parent(&state.db, &actor, id).await {
        Ok(Removed::Done) => {
            security::record_security_event(
                &state.db,
                "parent_removed",
                Some(&actor.username),
                None,
                Some(target_username),
            )
            .await;
            if id == actor.id {
                session.flush().await.ok();
                return admin_auth::signed_out_response();
            }
            Redirect::to("/account?parents=removed#parents").into_response()
        }
        Ok(Removed::Gone) => Redirect::to("/account?parents=gone#parents").into_response(),
        Ok(Removed::LastReady) => {
            // Only reachable for yourself (a waiting target never counts, and the actor is ready):
            // said in the group, since your own row has no form.
            let why = if id == actor.id {
                "You are the only parent who can sign in - add another parent first.".to_string()
            } else {
                let name = target
                    .as_ref()
                    .map_or(target_username, |(u, n)| n.as_deref().unwrap_or(u));
                format!("{name} is the only parent who can sign in - add another parent first.")
            };
            let answer = AccountAnswer {
                flash: Some(crate::handlers::settings::Flash::refused("parents", why)),
                ..AccountAnswer::default()
            };
            render_account(&state, &session, &actor, StatusCode::BAD_REQUEST, answer).await
        }
        Ok(Removed::SignedOut) => {
            session.flush().await.ok();
            admin_auth::signed_out_response()
        }
        Err(err) => {
            tracing::error!(%err, "couldn't remove the parent");
            (
                StatusCode::INTERNAL_SERVER_ERROR,
                "Couldn't remove the parent - nothing was changed. Check the server log.",
            )
                .into_response()
        }
    }
}
