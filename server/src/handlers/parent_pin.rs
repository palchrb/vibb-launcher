//! The device page's "Parent PIN" card (`#parent-pin`) and its save (design 24 §4, §5): the PIN
//! the phone checks offline - the launcher's Settings gate, the pause, Play install mode, the time
//! lock's "Enter unlock code" and the PIN lock's "Parent code".

use std::collections::HashMap;

use axum::Extension;
use axum::extract::{Form, Path, State};
use axum::http::StatusCode;
use axum::response::{IntoResponse, Redirect, Response};

use crate::models::DevicePolicy;
use crate::security::{self, CurrentAdmin};
use crate::{AppState, parent_pin};

/// What the card shows.
pub struct ParentPinCard {
    pub pin_set: bool,
    /// The PIN is easy to guess (`device_policy.override_pin_easy`, saved or swept).
    pub easy: bool,
    /// The one-shot message after a save, `(text, warning)` ([parent_pin::flash_text]).
    pub notice: Option<(&'static str, bool)>,
}

pub fn card(policy: &DevicePolicy, notice: Option<&str>) -> ParentPinCard {
    let pin_set = policy.override_pin_hash.is_some();
    ParentPinCard {
        pin_set,
        easy: pin_set && policy.override_pin_easy == Some(true),
        notice: notice.and_then(parent_pin::flash_text),
    }
}

const SAVE_FAILED: &str = "Couldn't save - nothing was changed. Check the server log.";

/// `POST /devices/{id}/parent-pin` (behind full auth): `new_pin` (6-10 digits) or `clear_pin`.
/// - Refused, nothing written: not 6-10 digits (`parent_pin_invalid`), the kid's PIN
///   (`override_is_kid_pin`), removing it while the kid's lock needs it (`override_needed_by_lock`).
/// - An easy PIN is saved with a warning (`parent_pin_saved_easy`, `override_pin_easy = 1`).
/// - Security events `override_pin_changed`/`override_pin_cleared`; nudges the phone, which then
///   takes the new PIN - and with it a fresh lockout state (the launcher keys it to the PIN).
/// - Every outcome redirects to `?notice=<code>#parent-pin`; 404 for an unknown device, 500 on a
///   database error.
pub async fn update_parent_pin(
    State(state): State<AppState>,
    Path(id): Path<i64>,
    Extension(CurrentAdmin(admin)): Extension<CurrentAdmin>,
    Form(form): Form<HashMap<String, String>>,
) -> Response {
    let back = |code: &str| {
        Redirect::to(&format!("/devices/{id}?notice={code}#parent-pin")).into_response()
    };
    let current =
        match sqlx::query_as::<_, DevicePolicy>("SELECT * FROM device_policy WHERE device_id = ?")
            .bind(id)
            .fetch_optional(&state.db)
            .await
        {
            Ok(Some(policy)) => policy,
            Ok(None) => return (StatusCode::NOT_FOUND, "Device not found").into_response(),
            Err(err) => {
                tracing::error!(device_id = id, %err, "can't read the policy for the parent PIN");
                return (StatusCode::INTERNAL_SERVER_ERROR, SAVE_FAILED).into_response();
            }
        };

    let (event, code) = if form.contains_key("clear_pin") {
        // The lock screen's parent code can't go while a kid PIN is set: the SQL itself keeps it,
        // so a kid PIN saved in parallel can't end up without it (qa-10-code #9).
        let stored: Result<Option<Option<String>>, sqlx::Error> = sqlx::query_scalar(
            "UPDATE device_policy SET \
             override_pin_hash = CASE WHEN kid_pin_hash IS NOT NULL THEN override_pin_hash ELSE NULL END, \
             override_pin_salt = CASE WHEN kid_pin_hash IS NOT NULL THEN override_pin_salt ELSE NULL END, \
             override_pin_easy = CASE WHEN kid_pin_hash IS NOT NULL THEN override_pin_easy ELSE NULL END, \
             updated_at = datetime('now') WHERE device_id = ? RETURNING override_pin_hash",
        )
        .bind(id)
        .fetch_optional(&state.db)
        .await;
        match stored {
            Ok(Some(Some(_))) => return back("override_needed_by_lock"),
            Ok(_) => ("override_pin_cleared", "parent_pin_cleared"),
            Err(err) => {
                tracing::error!(device_id = id, %err, "failed to remove the parent PIN");
                return (StatusCode::INTERNAL_SERVER_ERROR, SAVE_FAILED).into_response();
            }
        }
    } else {
        let new_pin = form
            .get("new_pin")
            .map(|p| p.trim().to_string())
            .unwrap_or_default();
        if !parent_pin::valid(&new_pin) {
            return back("parent_pin_invalid");
        }
        // PBKDF2 (the cross-check with the kid's PIN and the new hash): off the async workers.
        let decided = tokio::task::spawn_blocking(move || {
            if crate::kid_lock::override_conflicts(&new_pin, &current) {
                None
            } else {
                Some((
                    security::hash_pin(&new_pin),
                    parent_pin::easy(&new_pin).is_some(),
                ))
            }
        })
        .await;
        let ((hash, salt), easy) = match decided {
            Ok(Some(new)) => new,
            Ok(None) => return back("override_is_kid_pin"),
            Err(err) => {
                tracing::error!(device_id = id, %err, "parent PIN hashing failed");
                return (StatusCode::INTERNAL_SERVER_ERROR, SAVE_FAILED).into_response();
            }
        };
        let result = sqlx::query(
            "UPDATE device_policy SET override_pin_hash = ?, override_pin_salt = ?, \
             override_pin_easy = ?, updated_at = datetime('now') WHERE device_id = ?",
        )
        .bind(&hash)
        .bind(&salt)
        .bind(easy)
        .bind(id)
        .execute(&state.db)
        .await;
        if let Err(err) = result {
            tracing::error!(device_id = id, %err, "failed to save the parent PIN");
            return (StatusCode::INTERNAL_SERVER_ERROR, SAVE_FAILED).into_response();
        }
        let code = if easy {
            "parent_pin_saved_easy"
        } else {
            "parent_pin_saved"
        };
        ("override_pin_changed", code)
    };

    security::record_security_event(
        &state.db,
        event,
        Some(&admin.username),
        None,
        Some(&format!("device {id}")),
    )
    .await;
    // The phone re-syncs at once and takes the new PIN.
    let _ = state.command_notify.send(id);
    back(code)
}
