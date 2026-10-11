//! Design 24b on the device page and in the status report: the Status card's "Lock the phone
//! again now" (`end_windows`), "Allow these apps" (`allow_blocked`), "Pause restrictions for 2
//! hours" (`start_pause`), the last relock's findings and the warning when the phone refuses the
//! parent PIN (`foreign_access`, the fix round) - `crate::window_relock`.

use axum::Extension;
use axum::extract::{Path, State};
use axum::http::StatusCode;
use axum::response::{IntoResponse, Redirect, Response};

use crate::AppState;
use crate::models::DeviceStatus;
use crate::security::{self, CurrentAdmin};
use crate::window_relock::{
    self, ALLOW_BLOCKED, END_WINDOWS, ForeignNote, RelockNotes, START_PAUSE,
    WINDOW_RELOCK_CAPABILITY,
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
    /// "Pause restrictions for 2 hours": no pause at the last report.
    pub pause_button: bool,
    /// The phone refuses the parent PIN ([window_relock::foreign_note]).
    pub foreign: Option<ForeignNote>,
}

pub async fn card(
    db: &sqlx::SqlitePool,
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
    let notes = match window_relock::parse_relock(latest.and_then(|s| s.relock_json.as_deref())) {
        Some(r) => window_relock::relock_notes(&r, now_ms, &catalog_pins(db).await),
        None => RelockNotes::default(),
    };
    let foreign =
        window_relock::parse_foreign_access(latest.and_then(|s| s.foreign_access_json.as_deref()))
            .map(|f| {
                window_relock::foreign_note(&f, latest.is_some_and(|s| s.restrictions_paused))
            });
    RelockCard {
        capable,
        lock_again: capable && open,
        notice: notice.and_then(window_relock::flash_text),
        notes,
        pause_button: capable && !latest.is_some_and(|s| s.restrictions_paused),
        foreign,
    }
}

/// The catalog's pinned signers (M10): package -> signing key. Empty on a database error (then no
/// app is called "not the catalog's").
async fn catalog_pins(db: &sqlx::SqlitePool) -> std::collections::HashMap<String, String> {
    sqlx::query_as::<_, (String, String)>(
        "SELECT package_name, signer_sha256 FROM tracked_apps \
         WHERE signer_sha256 IS NOT NULL AND package_name != ''",
    )
    .fetch_all(db)
    .await
    .unwrap_or_else(|err| {
        tracing::error!(%err, "can't read the catalog's pinned signers");
        Vec::new()
    })
    .into_iter()
    .collect()
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

/// `POST /devices/{id}/start-pause` (full auth): "Pause restrictions for 2 hours" - the phone's
/// own pause (it locks again by itself), without the parent PIN on the phone (the fix round, QA
/// High 1: a phone that refuses the PIN, or one where it shouldn't be typed).
pub async fn start_pause(
    State(state): State<AppState>,
    Path(id): Path<i64>,
    Extension(CurrentAdmin(admin)): Extension<CurrentAdmin>,
) -> Response {
    queue(&state, id, &admin.username, START_PAUSE, "start_pause_sent").await
}

/// The latest report's `foreign_access`, read before a new report is stored.
pub async fn previous_foreign_access(db: &sqlx::SqlitePool, device_id: i64) -> Option<String> {
    let json: Option<Option<String>> = sqlx::query_scalar(
        "SELECT foreign_access_json FROM device_status WHERE device_id = ? \
         ORDER BY reported_at DESC, id DESC LIMIT 1",
    )
    .bind(device_id)
    .fetch_optional(db)
    .await
    .unwrap_or_else(|err| {
        tracing::error!(device_id, %err, "can't read the previous foreign access");
        None
    });
    json.flatten()
}

/// `phone_foreign_access` when the finding appears, changes or goes.
pub async fn log_foreign_access(
    db: &sqlx::SqlitePool,
    device_id: i64,
    previous: Option<&str>,
    now: Option<&str>,
) {
    let before = window_relock::parse_foreign_access(previous);
    let after = window_relock::parse_foreign_access(now);
    if before == after {
        return;
    }
    security::record_security_event(
        db,
        "phone_foreign_access",
        None,
        None,
        Some(&window_relock::foreign_log_line(device_id, after.as_ref())),
    )
    .await;
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
