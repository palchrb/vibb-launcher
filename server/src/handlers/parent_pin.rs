//! Lock Screen & PINs' Parent PIN group (`#parent-pin`, design 31 step 4; a card on the device
//! page before), its save, and the phone's reports about the PIN (design 24 §3-§5): the PIN the
//! phone checks offline - the launcher's Settings gate, the pause, Play install mode, the time
//! lock's "Enter unlock code" and the PIN lock's "Parent code".

use std::collections::HashMap;

use axum::Extension;
use axum::extract::{Form, Path, State};
use axum::http::StatusCode;
use axum::response::{IntoResponse, Redirect, Response};
use chrono::{DateTime, Utc};

use crate::display_time::Zone;
use crate::models::{DevicePolicy, DeviceStatus};
use crate::security::{self, AuthedDevice, CurrentAdmin};
use crate::{AppState, parent_pin};

/// What the Parent PIN group (and the device page's Status card lines) show.
pub struct ParentPinCard {
    pub pin_set: bool,
    /// The PIN is easy to guess (`device_policy.override_pin_easy`, saved or swept).
    pub easy: bool,
    /// The flash after a save, `(text, warning)` ([parent_pin::flash_text]).
    pub notice: Option<(&'static str, bool)>,
    /// The launcher reports parent-PIN events (`parent_pin_events_v1`) - or nothing reported yet.
    /// Without it the old `offline_override_used` flag shows instead.
    pub capable: bool,
    /// The last [parent_pin::SHOWN] events, newest first, as "Recent use" rows.
    pub events: Vec<parent_pin::EventRow>,
}

pub async fn card(
    db: &sqlx::SqlitePool,
    policy: &DevicePolicy,
    latest: Option<&DeviceStatus>,
    notice: Option<&str>,
    zone: &Zone,
    now: DateTime<Utc>,
) -> ParentPinCard {
    let pin_set = policy.override_pin_hash.is_some();
    let capable = latest.is_none_or(|s| {
        s.capabilities_json.as_deref().is_some_and(|caps| {
            caps.contains(&format!("\"{}\"", parent_pin::PARENT_PIN_EVENTS_CAPABILITY))
        })
    });
    let shown = device_events(db, policy.device_id, i64::MIN, parent_pin::SHOWN).await;
    ParentPinCard {
        pin_set,
        easy: pin_set && policy.override_pin_easy == Some(true),
        notice: notice.and_then(parent_pin::flash_text),
        capable,
        events: shown
            .iter()
            .map(|e| parent_pin::event_row(e, zone, now))
            .collect(),
    }
}

const SAVE_FAILED: &str = "Couldn't save - nothing was changed. Check the server log.";

/// `POST /devices/{id}/parent-pin` (behind full auth): `new_pin` (6-10 digits) or `clear_pin`.
/// - Refused, nothing written: not 6-10 digits (`parent_pin_invalid`), the kid's PIN
///   (`override_is_kid_pin`), removing it while the kid's lock needs it (`override_needed_by_lock`).
/// - An easy PIN is saved with a warning (`parent_pin_saved_easy`, `override_pin_easy = 1`).
/// - Security events `override_pin_changed`/`override_pin_cleared`; nudges the phone, which then
///   takes the new PIN - and with it a fresh lockout state (the launcher keys it to the PIN).
/// - Every outcome redirects to Lock Screen & PINs, `?notice=<code>#parent-pin` (the flash in its
///   group); 404 for an unknown device, 500 on a database error.
pub async fn update_parent_pin(
    State(state): State<AppState>,
    Path(id): Path<i64>,
    Extension(CurrentAdmin(admin)): Extension<CurrentAdmin>,
    Form(form): Form<HashMap<String, String>>,
) -> Response {
    let back = |code: &str| {
        Redirect::to(&format!("/devices/{id}/lock?notice={code}#parent-pin")).into_response()
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
             override_pin_set_at_ms = CASE WHEN kid_pin_hash IS NOT NULL THEN override_pin_set_at_ms ELSE ? END, \
             updated_at = datetime('now') WHERE device_id = ? RETURNING override_pin_hash",
        )
        .bind(chrono::Utc::now().timestamp_millis())
        .bind(id)
        .fetch_optional(&state.db)
        .await;
        match stored {
            Ok(Some(Some(_))) => {
                return crate::settings_log::refused(back("override_needed_by_lock"));
            }
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
            return crate::settings_log::refused(back("parent_pin_invalid"));
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
            Ok(None) => return crate::settings_log::refused(back("override_is_kid_pin")),
            Err(err) => {
                tracing::error!(device_id = id, %err, "parent PIN hashing failed");
                return (StatusCode::INTERNAL_SERVER_ERROR, SAVE_FAILED).into_response();
            }
        };
        let result = sqlx::query(
            "UPDATE device_policy SET override_pin_hash = ?, override_pin_salt = ?, \
             override_pin_easy = ?, override_pin_set_at_ms = ?, updated_at = datetime('now') \
             WHERE device_id = ?",
        )
        .bind(&hash)
        .bind(&salt)
        .bind(easy)
        .bind(chrono::Utc::now().timestamp_millis())
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

/// The newest events of a phone (or those after `since_ms`), newest first, at most `limit`; empty
/// on a database error (logged). Rows of an unknown kind or entry are skipped.
pub async fn device_events(
    db: &sqlx::SqlitePool,
    device_id: i64,
    since_ms: i64,
    limit: i64,
) -> Vec<parent_pin::PinEvent> {
    type Row = (
        String,
        String,
        String,
        i64,
        Option<i64>,
        Option<i64>,
        Option<i64>,
        bool,
    );
    let rows: Vec<Row> = sqlx::query_as(
        "SELECT event_id, kind, entry, occurred_at_ms, until_ms, failures, level, clock_off \
         FROM device_pin_events WHERE device_id = ? AND occurred_at_ms > ? \
         ORDER BY occurred_at_ms DESC, id DESC LIMIT ?",
    )
    .bind(device_id)
    .bind(since_ms)
    .bind(limit)
    .fetch_all(db)
    .await
    .unwrap_or_else(|err| {
        tracing::error!(device_id, %err, "couldn't load the parent PIN events");
        Vec::new()
    });
    rows.into_iter()
        .filter_map(
            |(event_id, kind, entry, occurred_at_ms, until_ms, failures, level, clock_off)| {
                Some(parent_pin::PinEvent {
                    event_id,
                    kind: parent_pin::Kind::parse(&kind)?,
                    entry: parent_pin::Entry::parse(&entry)?,
                    occurred_at_ms,
                    until_ms,
                    failures,
                    level,
                    clock_off,
                })
            },
        )
        .collect()
}

/// `POST /api/devices/pin-events` (bearer, design 24 §3.3) -> 204. Read leniently (QA #10): only
/// a body that isn't JSON or has no `events` array gets 400, more than
/// [parent_pin::PIN_EVENTS_MAX] events 413; each event is checked on its own
/// ([parent_pin::clean_event]) and a bad one skipped (logged). Each is stored once
/// (`INSERT OR IGNORE` on the phone's id) and, only when new, a use or lockout also gets its
/// security-log row in the same transaction (QA #13) - wrong tries stay on the device page. No
/// nudge: a sync would end the override (QA S1). 500 on a database error (nothing stored).
pub async fn pin_events(
    State(state): State<AppState>,
    Extension(AuthedDevice(device)): Extension<AuthedDevice>,
    body: axum::body::Bytes,
) -> StatusCode {
    let Ok(body) = serde_json::from_slice::<serde_json::Value>(&body) else {
        return StatusCode::BAD_REQUEST;
    };
    let Some(events) = body.get("events").and_then(|e| e.as_array()) else {
        return StatusCode::BAD_REQUEST;
    };
    if events.len() > parent_pin::PIN_EVENTS_MAX {
        return StatusCode::PAYLOAD_TOO_LARGE;
    }
    let received_ms = chrono::Utc::now().timestamp_millis();
    // The parent's events (design 35 §2.1): a newly stored use or lockout, in this transaction.
    let phone = crate::parent_events::Phone::load(&state.db, device.id).await;
    let Ok(mut tx) = state.db.begin().await else {
        return StatusCode::INTERNAL_SERVER_ERROR;
    };
    let mut raised = false;
    // Within the phone's daily budget (security review L4); the rest of today's are dropped.
    let left = match crate::retention::Budget::PinEvents
        .left_today(&mut tx, device.id)
        .await
    {
        Ok(left) => left,
        Err(_) => return StatusCode::INTERNAL_SERVER_ERROR,
    };
    for raw in events.iter().take(left) {
        let event = match parent_pin::clean_event(raw, received_ms) {
            Ok(event) => event,
            Err(why) => {
                tracing::warn!(device_id = device.id, why, "skipped a parent PIN event");
                continue;
            }
        };
        let inserted = sqlx::query(
            "INSERT OR IGNORE INTO device_pin_events \
             (device_id, event_id, kind, entry, occurred_at_ms, until_ms, failures, level, clock_off) \
             VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
        )
        .bind(device.id)
        .bind(&event.event_id)
        .bind(event.kind.wire())
        .bind(event.entry.wire())
        .bind(event.occurred_at_ms)
        .bind(event.until_ms)
        .bind(event.failures)
        .bind(event.level)
        .bind(event.clock_off)
        .execute(&mut *tx)
        .await;
        let logged = match (inserted, event.kind) {
            (Err(err), _) => Err(err),
            (Ok(done), parent_pin::Kind::Unlocked | parent_pin::Kind::Lockout)
                if done.rows_affected() == 1 =>
            {
                let event_type = if event.kind == parent_pin::Kind::Unlocked {
                    "parent_pin_used"
                } else {
                    "parent_pin_lockout"
                };
                // Within the phone's hourly budget of security lines (security review L1).
                let logged = crate::security::record_phone_event(
                    &mut tx,
                    device.id,
                    event_type,
                    &parent_pin::log_line(device.id, &event),
                )
                .await
                .map(|_| ());
                match phone.as_ref().and_then(|phone| phone.pin_event(&event)) {
                    Some(new) if logged.is_ok() => crate::parent_events::raise(&mut tx, &new)
                        .await
                        .map(|id| raised |= id.is_some()),
                    _ => logged,
                }
            }
            (Ok(_), _) => Ok(()),
        };
        if let Err(err) = logged {
            tracing::error!(device_id = device.id, %err, "couldn't store a parent PIN event");
            return StatusCode::INTERNAL_SERVER_ERROR;
        }
    }
    if tx.commit().await.is_err() {
        return StatusCode::INTERNAL_SERVER_ERROR;
    }
    if raised {
        state.push.wake();
    }
    StatusCode::NO_CONTENT
}
