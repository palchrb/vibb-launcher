//! Lock Screen & PINs' "Show message text" switch and its Notifications group (design 25 §3.3,
//! `crate::lock_glance`; design 31 step 4): one tick per app. Both are auto-saving forms behind
//! full admin auth (and the admin listener's CSRF check, as every form); each change is in the
//! security log, nudges the phone and goes back to its form's row on `/devices/{id}/lock`
//! (`#lock-screen-text`, `#lock-screen-app-<package>`): without JS the answer lands on the switch
//! in a long app list, with JS `scroll-restore.js` keeps the exact place (design 31 §5.1.4).

use std::collections::HashMap;

use axum::extract::{Path, State};
use axum::http::StatusCode;
use axum::response::{IntoResponse, Redirect, Response};
use axum::{Extension, Form};

use crate::AppState;
use crate::lock_glance;
use crate::security::{self, CurrentAdmin};

/// Back to the form's row, `#<form id>`.
fn saved(state: &AppState, id: i64, form: &str) -> Response {
    let _ = state.command_notify.send(id);
    Redirect::to(&format!("/devices/{id}/lock#{form}")).into_response()
}

fn failed() -> Response {
    (
        StatusCode::INTERNAL_SERVER_ERROR,
        "Couldn't save - nothing was changed. Check the server log.",
    )
        .into_response()
}

/// `POST /devices/{id}/lock-screen`: the checkbox `show_text` (missing = off) - the message text
/// shows on the lock's first page too, not only who it's from. 404 for an unknown device, 500
/// (nothing written) on a DB error.
pub async fn save_text(
    State(state): State<AppState>,
    Extension(CurrentAdmin(admin)): Extension<CurrentAdmin>,
    Path(id): Path<i64>,
    Form(form): Form<HashMap<String, String>>,
) -> Response {
    let on = form.contains_key("show_text");
    let result = sqlx::query(
        "UPDATE device_policy SET lock_glance_text = ?, updated_at = datetime('now') \
         WHERE device_id = ?",
    )
    .bind(on)
    .bind(id)
    .execute(&state.db)
    .await;
    match result {
        Ok(done) if done.rows_affected() == 0 => {
            (StatusCode::NOT_FOUND, "Device not found").into_response()
        }
        Ok(_) => {
            security::record_security_event(
                &state.db,
                "lock_screen_text_changed",
                Some(&admin.username),
                None,
                Some(&lock_glance::text_log_line(id, on)),
            )
            .await;
            saved(&state, id, "lock-screen-text")
        }
        Err(err) => {
            tracing::error!(device_id = id, %err, "couldn't save the lock screen's text switch");
            failed()
        }
    }
}

/// `POST /devices/{id}/lock-screen/apps`: `package_name` and the checkbox `on` (missing = off) -
/// that app's messages on the lock's first page. Idempotent (a tick is one row). 400 for a package
/// that isn't a package name or is Play core, 404 for an unknown device, 500 on a DB error - each
/// with nothing written.
pub async fn save_app(
    State(state): State<AppState>,
    Extension(CurrentAdmin(admin)): Extension<CurrentAdmin>,
    Path(id): Path<i64>,
    Form(form): Form<HashMap<String, String>>,
) -> Response {
    let package = form
        .get("package_name")
        .map(|p| p.trim())
        .unwrap_or_default()
        .to_string();
    if !lock_glance::valid_package(&package) {
        return (
            StatusCode::BAD_REQUEST,
            "Not an app that can show on the lock screen.",
        )
            .into_response();
    }
    let on = form.contains_key("on");
    let exists: Option<i64> = match sqlx::query_scalar("SELECT id FROM devices WHERE id = ?")
        .bind(id)
        .fetch_optional(&state.db)
        .await
    {
        Ok(found) => found,
        Err(err) => {
            tracing::error!(device_id = id, %err, "couldn't read the device");
            return failed();
        }
    };
    if exists.is_none() {
        return (StatusCode::NOT_FOUND, "Device not found").into_response();
    }
    let result = if on {
        sqlx::query(
            "INSERT INTO device_lock_glance_apps (device_id, package_name) VALUES (?, ?) \
             ON CONFLICT(device_id, package_name) DO NOTHING",
        )
        .bind(id)
        .bind(&package)
        .execute(&state.db)
        .await
    } else {
        sqlx::query("DELETE FROM device_lock_glance_apps WHERE device_id = ? AND package_name = ?")
            .bind(id)
            .bind(&package)
            .execute(&state.db)
            .await
    };
    match result {
        Ok(_) => {
            security::record_security_event(
                &state.db,
                "lock_screen_app_changed",
                Some(&admin.username),
                None,
                Some(&lock_glance::log_line(id, &package, on)),
            )
            .await;
            saved(&state, id, &format!("lock-screen-app-{package}"))
        }
        Err(err) => {
            tracing::error!(device_id = id, %err, "couldn't save the lock screen's app");
            failed()
        }
    }
}
