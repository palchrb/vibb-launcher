//! Design 24b on the device page and in the status report: the Status card's "Lock the phone
//! again now" (`end_windows`), "Allow these apps" (`allow_blocked`), "Pause restrictions for 2
//! hours" (`start_pause`), the last relock's findings and the warning when the phone refuses the
//! parent PIN (`foreign_access`, the fix round) - `crate::window_relock`.

use axum::extract::{Path, State};
use axum::http::StatusCode;
use axum::response::{IntoResponse, Redirect, Response};
use axum::{Extension, Form};

use crate::AppState;
use crate::models::DeviceStatus;
use crate::security::{self, CurrentAdmin};
use crate::window_relock::{
    self, ALLOW_BLOCKED, AllowedBlock, BlocksNote, END_WINDOWS, ForeignNote, RelockNotes,
    START_PAUSE, WINDOW_RELOCK_CAPABILITY,
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
    /// The last answer to each command, under a week old ([window_relock::answer_line]).
    pub answers: Vec<String>,
    /// What the phone blocks now ([window_relock::blocks_note]; fix of 2026-10-12).
    pub blocks: BlocksNote,
    /// "Allow these apps": a current block it lets out, or - from an older launcher - the last
    /// relock's.
    pub allow_button: bool,
    /// Its hidden `blocks` value: the listed apps and the keys shown
    /// ([window_relock::allow_form_value], QA of cmd-expiry Low 1).
    pub allow_value: String,
}

pub async fn card(
    db: &sqlx::SqlitePool,
    device_id: i64,
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
    let answers = last_answers(db, device_id).await;
    let pins = catalog_pins(db).await;
    // The phone's current blocks (`relock_blocks`, fix of 2026-10-12): the install check's too.
    let blocks =
        window_relock::parse_relock_blocks(latest.and_then(|s| s.relock_blocks_json.as_deref()));
    let notes = match window_relock::parse_relock(latest.and_then(|s| s.relock_json.as_deref())) {
        Some(r) => {
            let mut notes = window_relock::relock_notes(&r, now_ms, &pins);
            if blocks.is_some() {
                // Listed - all of them, as they are now - with the button below.
                notes.lines.retain(|line| !line.starts_with("Blocked: "));
                notes.allow_button = false;
            } else {
                // An older launcher: pressed after this relock and answered, the blocks it lists
                // are gone (QA Low 10).
                let allowed = answers.iter().find(|a| a.command == ALLOW_BLOCKED);
                if window_relock::allowed_since_relock(allowed, r.at_ms) {
                    notes.allow_button = false;
                }
            }
            notes
        }
        None => RelockNotes::default(),
    };
    let blocks = blocks
        .map(|b| window_relock::blocks_note(&b, &pins, now_ms))
        .unwrap_or_default();
    let foreign =
        window_relock::parse_foreign_access(latest.and_then(|s| s.foreign_access_json.as_deref()))
            .map(|f| {
                window_relock::foreign_note(&f, latest.is_some_and(|s| s.restrictions_paused))
            });
    // One of the two lists is empty: with `relock_blocks` the relock's line yields to them.
    let allow: Vec<AllowedBlock> = blocks
        .allow
        .iter()
        .chain(notes.allow.iter())
        .cloned()
        .collect();
    RelockCard {
        capable,
        lock_again: capable && open,
        notice: notice.and_then(window_relock::flash_text),
        allow_button: notes.allow_button || blocks.allow_button,
        allow_value: window_relock::allow_form_value(&allow),
        blocks,
        notes,
        pause_button: capable && !latest.is_some_and(|s| s.restrictions_paused),
        foreign,
        answers: answers
            .iter()
            .filter_map(|a| window_relock::answer_line(a, now_ms))
            .collect(),
    }
}

/// A row of [last_answers]: command, requested_at, acknowledged_at, result, delivered, cancelled.
type AnswerRow = (String, String, Option<String>, Option<String>, bool, bool);

/// The newest of each of this design's commands for [device_id], with the phone's answer. Empty on
/// a database error.
async fn last_answers(db: &sqlx::SqlitePool, device_id: i64) -> Vec<window_relock::CommandAnswer> {
    let [end_windows, allow_blocked, start_pause] = window_relock::OWN_INSERT_COMMANDS;
    let rows: Vec<AnswerRow> = sqlx::query_as(
        "SELECT command, requested_at, acknowledged_at, result, delivered_at IS NOT NULL, \
         cancelled_at IS NOT NULL FROM device_commands \
         WHERE id IN (SELECT MAX(id) FROM device_commands WHERE device_id = ? \
                      AND command IN (?, ?, ?) GROUP BY command) \
         ORDER BY id",
    )
    .bind(device_id)
    .bind(end_windows)
    .bind(allow_blocked)
    .bind(start_pause)
    .fetch_all(db)
    .await
    .unwrap_or_else(|err| {
        tracing::error!(device_id, %err, "can't read the window commands");
        Vec::new()
    });
    // SQLite's datetime('now'): UTC, "YYYY-MM-DD HH:MM:SS".
    let ms = |text: &str| {
        chrono::NaiveDateTime::parse_from_str(text, "%Y-%m-%d %H:%M:%S")
            .ok()
            .map(|t| t.and_utc().timestamp_millis())
    };
    rows.into_iter()
        .filter_map(
            |(command, requested, acknowledged, result, delivered, cancelled)| {
                Some(window_relock::CommandAnswer {
                    command,
                    requested_ms: ms(&requested)?,
                    acknowledged_ms: acknowledged.as_deref().and_then(ms),
                    result,
                    delivered,
                    cancelled,
                })
            },
        )
        .collect()
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
/// [payload]: `allow_blocked`'s list (JSON of [AllowedBlock]s), else `None`.
async fn queue_own_command(
    state: &AppState,
    device_id: i64,
    command: &str,
    payload: Option<&str>,
) -> Result<bool, sqlx::Error> {
    let exists: Option<i64> = sqlx::query_scalar("SELECT id FROM devices WHERE id = ?")
        .bind(device_id)
        .fetch_optional(&state.db)
        .await?;
    if exists.is_none() {
        return Ok(false);
    }
    sqlx::query("INSERT INTO device_commands (device_id, command, payload) VALUES (?, ?, ?)")
        .bind(device_id)
        .bind(command)
        .bind(payload)
        .execute(&state.db)
        .await?;
    let _ = state.command_notify.send(device_id);
    Ok(true)
}

async fn queue(
    state: &AppState,
    id: i64,
    admin: &str,
    command: &str,
    notice: &str,
    allow: Option<&[AllowedBlock]>,
) -> Response {
    let payload = allow.and_then(|a| serde_json::to_string(a).ok());
    match queue_own_command(state, id, command, payload.as_deref()).await {
        Ok(true) => {
            let detail = match allow {
                Some(a) => format!(
                    "{command}: {}",
                    a.iter()
                        .map(|b| b.package_name.as_str())
                        .collect::<Vec<_>>()
                        .join(", ")
                ),
                None => command.to_string(),
            };
            security::record_security_event(
                &state.db,
                "device_command_queued",
                Some(admin),
                None,
                Some(&detail),
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
    queue(
        &state,
        id,
        &admin.username,
        END_WINDOWS,
        "end_windows_sent",
        None,
    )
    .await
}

#[derive(serde::Deserialize)]
pub struct AllowForm {
    /// The card's list ([window_relock::allow_form_value]).
    #[serde(default)]
    pub blocks: String,
}

/// `POST /devices/{id}/allow-blocked` (full auth): "Allow these apps" - the phone lets out the
/// blocks the card listed, each only while its signing key is still the one shown (QA of
/// cmd-expiry, Low 1); apps on the allowed list show again.
pub async fn allow_blocked(
    State(state): State<AppState>,
    Path(id): Path<i64>,
    Extension(CurrentAdmin(admin)): Extension<CurrentAdmin>,
    Form(form): Form<AllowForm>,
) -> Response {
    let allow = window_relock::parse_allow_form(&form.blocks);
    if allow.is_empty() {
        return (
            StatusCode::BAD_REQUEST,
            "No app to allow - reload the device page and press \"Allow these apps\" again.",
        )
            .into_response();
    }
    queue(
        &state,
        id,
        &admin.username,
        ALLOW_BLOCKED,
        "allow_blocked_sent",
        Some(&allow),
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
    queue(
        &state,
        id,
        &admin.username,
        START_PAUSE,
        "start_pause_sent",
        None,
    )
    .await
}

/// The latest report's `relock_blocks`, read before a new report is stored.
pub async fn previous_relock_blocks(db: &sqlx::SqlitePool, device_id: i64) -> Option<String> {
    let json: Option<Option<String>> = sqlx::query_scalar(
        "SELECT relock_blocks_json FROM device_status WHERE device_id = ? \
         ORDER BY reported_at DESC, id DESC LIMIT 1",
    )
    .bind(device_id)
    .fetch_optional(db)
    .await
    .unwrap_or_else(|err| {
        tracing::error!(device_id, %err, "can't read the previous blocks");
        None
    });
    json.flatten()
}

/// `phone_app_blocked` for each app the phone newly reports as blocked - an install-time block
/// reaches the security log here, a relock's also through `phone_relock_findings`.
pub async fn log_relock_blocks(
    db: &sqlx::SqlitePool,
    device_id: i64,
    previous: Option<&str>,
    now: Option<&str>,
) {
    let Some(blocks) = window_relock::parse_relock_blocks(now) else {
        return;
    };
    let before = window_relock::parse_relock_blocks(previous);
    if let Some(line) = window_relock::blocks_log_line(device_id, before.as_deref(), &blocks) {
        security::record_security_event(db, "phone_app_blocked", None, None, Some(&line)).await;
    }
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
