//! Design 24b on the device page and in the status report: the Status card's "Lock the phone
//! again now" (`end_windows`), "Allow these apps" (`allow_blocked`) and the last relock's
//! findings (`crate::window_relock`).

use axum::Extension;
use axum::extract::{Path, State};
use axum::http::StatusCode;
use axum::response::{IntoResponse, Redirect, Response};

use crate::AppState;
use crate::models::{DevicePolicy, DeviceStatus};
use crate::security::{self, CurrentAdmin};
use crate::window_relock::{
    self, ALLOW_BLOCKED, END_WINDOWS, RelockNotes, WINDOW_RELOCK_CAPABILITY,
};

/// What the Status card shows for design 24b.
pub struct RelockCard {
    /// The launcher reports `relock` and takes the commands (`window_relock_v1`).
    pub capable: bool,
    /// "Lock the phone again now": a pause or Play install mode is open at the last report.
    pub lock_again: bool,
    /// The one-shot message after a command was queued (`?notice=`).
    pub notice: Option<&'static str>,
    /// The last relock's findings ([window_relock::relock_notes]).
    pub notes: RelockNotes,
}

pub fn card(
    policy: &DevicePolicy,
    latest: Option<&DeviceStatus>,
    notice: Option<&str>,
    now_ms: i64,
) -> RelockCard {
    let capable = latest.is_some_and(|s| {
        s.capabilities_json
            .as_deref()
            .is_some_and(|caps| caps.contains(&format!("\"{WINDOW_RELOCK_CAPABILITY}\"")))
    });
    let open = latest.is_some_and(|s| {
        s.restrictions_paused || s.install_mode_until_ms.is_some_and(|until| until > now_ms)
    });
    let allowlist: Vec<String> = policy
        .allowlist_json
        .as_deref()
        .and_then(|j| serde_json::from_str(j).ok())
        .unwrap_or_default();
    let notes = window_relock::parse_relock(latest.and_then(|s| s.relock_json.as_deref()))
        .map(|r| window_relock::relock_notes(&r, now_ms, &allowlist))
        .unwrap_or_default();
    RelockCard {
        capable,
        lock_again: capable && open,
        notice: notice.and_then(window_relock::flash_text),
        notes,
    }
}

/// One of this design's commands: its own insert, never deleted by another command and deleting
/// none (QA #8 - `locate::queue_command` leaves them alone). The phone syncs once more after
/// dispatching one, so a command queued behind it follows within seconds. Nudges the phone.
async fn queue_own_command(
    state: &AppState,
    device_id: i64,
    command: &str,
) -> Result<bool, sqlx::Error> {
    let exists: Option<i64> = sqlx::query_scalar("SELECT id FROM devices WHERE id = ?")
        .bind(device_id)
        .fetch_optional(&state.db)
        .await?;
    if exists.is_none() {
        return Ok(false);
    }
    sqlx::query("INSERT INTO device_commands (device_id, command) VALUES (?, ?)")
        .bind(device_id)
        .bind(command)
        .execute(&state.db)
        .await?;
    let _ = state.command_notify.send(device_id);
    Ok(true)
}

async fn queue(state: &AppState, id: i64, admin: &str, command: &str, notice: &str) -> Response {
    match queue_own_command(state, id, command).await {
        Ok(true) => {
            security::record_security_event(
                &state.db,
                "device_command_queued",
                Some(admin),
                None,
                Some(command),
            )
            .await;
            // No #fragment: scroll-restore.js keeps the place, the notice is in the Status card.
            Redirect::to(&format!("/devices/{id}?notice={notice}")).into_response()
        }
        Ok(false) => (StatusCode::NOT_FOUND, "Device not found").into_response(),
        Err(err) => {
            tracing::error!(device_id = id, %err, command, "can't queue the command");
            (
                StatusCode::INTERNAL_SERVER_ERROR,
                "Couldn't send it - nothing was queued. Check the server log.",
            )
                .into_response()
        }
    }
}

/// `POST /devices/{id}/end-windows` (full auth): "Lock the phone again now" - the phone ends a
/// pause or an override and Play install mode, and locks again.
pub async fn end_windows(
    State(state): State<AppState>,
    Path(id): Path<i64>,
    Extension(CurrentAdmin(admin)): Extension<CurrentAdmin>,
) -> Response {
    queue(&state, id, &admin.username, END_WINDOWS, "end_windows_sent").await
}

/// `POST /devices/{id}/allow-blocked` (full auth): "Allow these apps" - the phone drops every
/// block its relock set; apps on the allowed list show again.
pub async fn allow_blocked(
    State(state): State<AppState>,
    Path(id): Path<i64>,
    Extension(CurrentAdmin(admin)): Extension<CurrentAdmin>,
) -> Response {
    queue(
        &state,
        id,
        &admin.username,
        ALLOW_BLOCKED,
        "allow_blocked_sent",
    )
    .await
}

/// The latest report's relock time, read before a new report is stored.
pub async fn previous_relock_at(db: &sqlx::SqlitePool, device_id: i64) -> Option<i64> {
    let json: Option<Option<String>> = sqlx::query_scalar(
        "SELECT relock_json FROM device_status WHERE device_id = ? \
         ORDER BY reported_at DESC, id DESC LIMIT 1",
    )
    .bind(device_id)
    .fetch_optional(db)
    .await
    .unwrap_or_else(|err| {
        tracing::error!(device_id, %err, "can't read the previous relock");
        None
    });
    window_relock::parse_relock(json.flatten().as_deref()).map(|r| r.at_ms)
}

/// `phone_relock_findings` when the report carries a relock the previous one didn't (another
/// `at_ms`) and it found something.
pub async fn log_relock(
    db: &sqlx::SqlitePool,
    device_id: i64,
    previous_at: Option<i64>,
    relock_json: Option<&str>,
) {
    let Some(report) = window_relock::parse_relock(relock_json) else {
        return;
    };
    if previous_at == Some(report.at_ms) || !window_relock::found_anything(&report) {
        return;
    }
    security::record_security_event(
        db,
        "phone_relock_findings",
        None,
        None,
        Some(&window_relock::log_line(device_id, &report)),
    )
    .await;
}
