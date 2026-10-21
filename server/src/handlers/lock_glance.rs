//! Lock Screen & PINs' Notifications group (design 25 §3.3, `crate::lock_glance`; design 31 step
//! 4; one select per app since 2026-10-21): per app Off (not on the lock), Name only (on, design
//! 51's text off) or Full text (on, text on) - an auto-saving form behind full admin auth (and the
//! admin listener's CSRF check, as every form); each change is in the security log, nudges the
//! phone and goes back to the app's row on `/devices/{id}/lock` (`#lock-screen-app-<package>`;
//! `#notifications` for Off on a row listed only because it was on, which goes with it): without
//! JS the answer lands on the select in a long app list, with JS `scroll-restore.js` keeps the
//! exact place (design 31 §5.1.4). The stored data is design 25's and 51's as before (a
//! `device_lock_glance_apps` row or none, its `show_text`): the policy and the launcher are
//! unchanged.

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

/// What the lock shows of one app (the page's select): `off`, `name` or `text`.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Show {
    /// Not on the lock: no row.
    Off,
    /// On, the text off: the sender's name for a chat, else the app's name and how many.
    Name,
    /// On with the message text (design 51).
    Text,
}

impl Show {
    pub fn parse(value: &str) -> Option<Show> {
        match value {
            "off" => Some(Show::Off),
            "name" => Some(Show::Name),
            "text" => Some(Show::Text),
            _ => None,
        }
    }

    /// The stored row: none, or one with its `show_text`.
    fn stored(self) -> Option<bool> {
        match self {
            Show::Off => None,
            Show::Name => Some(false),
            Show::Text => Some(true),
        }
    }
}

/// `POST /devices/{id}/lock-screen/apps`: `package_name` and `show` (`off`, `name`, `text`) - that
/// app on the lock's first page or not, and with its message text or not. Idempotent. The
/// security log has a line for what changed: `lock_screen_app_changed` when it came on or went
/// off, `lock_screen_text_changed` when an app on the lock changed its text (coming on with Full
/// text logs both). 400 for a package that isn't a package name or is Play core, or a `show` that
/// isn't one of the three (none: [stale_page]); 404 for an unknown device; 500 on a DB error - each
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
    let Some(value) = form.get("show") else {
        // A page from before the select (it posts `on`): nothing written, and what to do.
        return stale_page().await;
    };
    let Some(show) = Show::parse(value) else {
        return (StatusCode::BAD_REQUEST, "Pick Off, Name only or Full text.").into_response();
    };
    match write_show(&state, id, &package, show).await {
        Ok(None) => (StatusCode::NOT_FOUND, "Device not found").into_response(),
        Ok(Some(before)) => {
            let after = show.stored();
            if before.is_some() != after.is_some() {
                security::record_security_event(
                    &state.db,
                    "lock_screen_app_changed",
                    Some(&admin.username),
                    None,
                    Some(&lock_glance::log_line(id, &package, after.is_some())),
                )
                .await;
            }
            if let Some(text) = after
                && text != before.unwrap_or(false)
            {
                security::record_security_event(
                    &state.db,
                    "lock_screen_text_changed",
                    Some(&admin.username),
                    None,
                    Some(&lock_glance::text_log_line(id, &package, text)),
                )
                .await;
            }
            // A row listed only because it was on is gone once Off: back to the group
            // (qa-browser-fixes-code L1), else to the row.
            if form.contains_key("unlisted") && show == Show::Off {
                saved(&state, id, "notifications")
            } else {
                saved(&state, id, &format!("lock-screen-app-{package}"))
            }
        }
        Err(err) => {
            tracing::error!(device_id = id, %err, "couldn't save the lock screen's app");
            failed()
        }
    }
}

/// A Lock page loaded before 2026-10-21's select posts the old fields (`on`) or to the removed
/// `/lock-screen/apps/text`: a 400 that says to reload, nothing written (qa-srv-sweep-resume-code
/// L6b). The route can go a release after.
pub async fn stale_page() -> Response {
    (
        StatusCode::BAD_REQUEST,
        "This page is out of date - reload it.",
    )
        .into_response()
}

/// [save_app]'s write in one transaction: `Ok(None)` = no such device (nothing written), else the
/// stored state before (`None` = not on the lock, else its `show_text`).
async fn write_show(
    state: &AppState,
    id: i64,
    package: &str,
    show: Show,
) -> Result<Option<Option<bool>>, sqlx::Error> {
    // IMMEDIATE: the read and the write under one write lock, so two saves racing wait for each
    // other instead of failing with SQLITE_BUSY (qa-srv-sweep-resume-code L7).
    let mut tx = state.db.begin_with("BEGIN IMMEDIATE").await?;
    let exists: Option<i64> = sqlx::query_scalar("SELECT id FROM devices WHERE id = ?")
        .bind(id)
        .fetch_optional(&mut *tx)
        .await?;
    if exists.is_none() {
        return Ok(None);
    }
    let before: Option<bool> = sqlx::query_scalar(
        "SELECT show_text FROM device_lock_glance_apps WHERE device_id = ? AND package_name = ?",
    )
    .bind(id)
    .bind(package)
    .fetch_optional(&mut *tx)
    .await?;
    match show.stored() {
        None => {
            sqlx::query(
                "DELETE FROM device_lock_glance_apps WHERE device_id = ? AND package_name = ?",
            )
            .bind(id)
            .bind(package)
            .execute(&mut *tx)
            .await?;
        }
        Some(text) if before.is_none() => {
            sqlx::query(
                "INSERT INTO device_lock_glance_apps (device_id, package_name, show_text) \
                 VALUES (?, ?, ?)",
            )
            .bind(id)
            .bind(package)
            .bind(text)
            .execute(&mut *tx)
            .await?;
        }
        Some(text) => {
            sqlx::query(
                "UPDATE device_lock_glance_apps SET show_text = ? \
                 WHERE device_id = ? AND package_name = ?",
            )
            .bind(text)
            .bind(id)
            .bind(package)
            .execute(&mut *tx)
            .await?;
        }
    }
    tx.commit().await?;
    Ok(Some(before))
}

/// `POST /devices/{id}/lock-wake` (launcher round 7 §3): the checkbox `on` (missing = off) - "Wake
/// the screen for a notification" in the Lock screen group. Back to `#lock-wake`, the phone nudged,
/// a security-log line when the value changed. 404 for an unknown device, 500 on a DB error - each with nothing
/// written.
pub async fn save_wake(
    State(state): State<AppState>,
    Extension(CurrentAdmin(admin)): Extension<CurrentAdmin>,
    Path(id): Path<i64>,
    Form(form): Form<HashMap<String, String>>,
) -> Response {
    let on = form.contains_key("on");
    // The value before: a save that changes nothing logs nothing (qa-l7-b-code 6).
    let before: Option<bool> = match sqlx::query_scalar(
        "SELECT lock_wake_on_notification FROM device_policy WHERE device_id = ?",
    )
    .bind(id)
    .fetch_optional(&state.db)
    .await
    {
        Ok(before) => before,
        Err(err) => {
            tracing::error!(device_id = id, %err, "couldn't read the lock screen's wake switch");
            return failed();
        }
    };
    let Some(before) = before else {
        return (StatusCode::NOT_FOUND, "Device not found").into_response();
    };
    let result =
        sqlx::query("UPDATE device_policy SET lock_wake_on_notification = ? WHERE device_id = ?")
            .bind(on)
            .bind(id)
            .execute(&state.db)
            .await;
    match result {
        Ok(_) => {
            if before != on {
                security::record_security_event(
                    &state.db,
                    "lock_wake_changed",
                    Some(&admin.username),
                    None,
                    Some(&lock_glance::wake_log_line(id, on)),
                )
                .await;
            }
            saved(&state, id, "lock-wake")
        }
        Err(err) => {
            tracing::error!(device_id = id, %err, "couldn't save the lock screen's wake switch");
            failed()
        }
    }
}
