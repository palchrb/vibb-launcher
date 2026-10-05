//! A parent lifting a time rule for a while ("end school mode for 30 min") or adding screen time
//! for today ("+30 min") from the device page (handy step 6). Recorded in `time_lifts` and the
//! security log, delivered in `PolicyResponse.time_policy.lifts` after a nudge; the phone expires a
//! rule lift by itself. A rule lift can be ended early ("End now"); added screen time can't be
//! taken back once the phone has counted it.

use std::collections::HashMap;

use axum::extract::{Path, State};
use axum::http::StatusCode;
use axum::response::{IntoResponse, Redirect, Response};
use axum::{Extension, Form};

use crate::AppState;
use crate::security::{self, CurrentAdmin};
use crate::time_rules::{self, BUDGET_LIFT_DELIVERY_HOURS};

fn server_error(err: impl std::fmt::Display, what: &str) -> Response {
    tracing::error!(%err, "{what}");
    (
        StatusCode::INTERNAL_SERVER_ERROR,
        "Couldn't save - nothing was changed. Check the server log.",
    )
        .into_response()
}

/// Form: `target` ("rule" or "budget"), `rule` ("all" or a rule id - only for "rule"), `minutes`
/// (15/30/60/120 for a rule, 15/30/60 for screen time). The rule must be one of the device's
/// effective rules (its own with the override on, else the global ones).
pub async fn create_lift(
    State(state): State<AppState>,
    Path(id): Path<i64>,
    Extension(CurrentAdmin(admin)): Extension<CurrentAdmin>,
    Form(form): Form<HashMap<String, String>>,
) -> Response {
    let custom: Option<bool> = match sqlx::query_scalar(
        "SELECT custom_schedule_enabled FROM device_policy WHERE device_id = ?",
    )
    .bind(id)
    .fetch_optional(&state.db)
    .await
    {
        Ok(custom) => custom,
        Err(err) => return server_error(err, "failed to read a device"),
    };
    let Some(custom) = custom else {
        return (StatusCode::NOT_FOUND, "Device not found").into_response();
    };

    let target = form.get("target").map(String::as_str).unwrap_or("");
    let minutes = form
        .get("minutes")
        .and_then(|m| m.trim().parse::<i64>().ok())
        .unwrap_or(0);
    if !time_rules::valid_lift(target, minutes) {
        return (StatusCode::BAD_REQUEST, "Unknown lift or length").into_response();
    }

    let (rule_id, rule_name) = if target == "rule" {
        match form.get("rule").map(|r| r.trim()).unwrap_or("all") {
            "all" | "" => (None, None),
            raw => {
                let Ok(rule_id) = raw.parse::<i64>() else {
                    return (StatusCode::BAD_REQUEST, "Unknown rule").into_response();
                };
                let rules = match time_rules::effective_rule_rows(&state.db, id, custom).await {
                    Ok(rules) => rules,
                    Err(err) => return server_error(err, "failed to read time rules"),
                };
                let Some(rule) = rules.into_iter().find(|r| r.id == rule_id) else {
                    return (
                        StatusCode::BAD_REQUEST,
                        "That rule doesn't apply to this phone",
                    )
                        .into_response();
                };
                (Some(rule.id), Some(rule.name))
            }
        }
    } else {
        (None, None)
    };

    let delivery = if target == "rule" {
        format!("+{minutes} minutes")
    } else {
        format!("+{BUDGET_LIFT_DELIVERY_HOURS} hours")
    };
    if let Err(err) = sqlx::query(
        "INSERT INTO time_lifts (device_id, target, rule_id, rule_name, minutes, expires_at, \
         created_by) VALUES (?, ?, ?, ?, ?, datetime('now', ?), ?)",
    )
    .bind(id)
    .bind(target)
    .bind(rule_id)
    .bind(&rule_name)
    .bind(minutes)
    .bind(&delivery)
    .bind(&admin.username)
    .execute(&state.db)
    .await
    {
        return server_error(err, "failed to record a lift");
    }

    let detail = if target == "rule" {
        format!(
            "device {id}: {} lifted for {minutes} min",
            rule_name.as_deref().unwrap_or("all rules")
        )
    } else {
        format!("device {id}: +{minutes} min screen time")
    };
    security::record_security_event(
        &state.db,
        "time_lift",
        Some(&admin.username),
        None,
        Some(&detail),
    )
    .await;
    let _ = state.command_notify.send(id);
    Redirect::to(&format!("/devices/{id}")).into_response()
}

/// "End now": an active rule lift stops being sent, and the phone drops it on its next sync.
pub async fn end_lift(
    State(state): State<AppState>,
    Path((id, lift_id)): Path<(i64, i64)>,
    Extension(CurrentAdmin(admin)): Extension<CurrentAdmin>,
) -> Response {
    match sqlx::query(
        "UPDATE time_lifts SET ended_early_at = datetime('now') WHERE id = ? AND device_id = ? \
         AND target = 'rule' AND ended_early_at IS NULL AND expires_at > datetime('now')",
    )
    .bind(lift_id)
    .bind(id)
    .execute(&state.db)
    .await
    {
        Ok(done) if done.rows_affected() == 0 => {
            (StatusCode::NOT_FOUND, "No active lift with that id").into_response()
        }
        Ok(_) => {
            security::record_security_event(
                &state.db,
                "time_lift_ended",
                Some(&admin.username),
                None,
                Some(&format!("device {id}: lift {lift_id} ended early")),
            )
            .await;
            let _ = state.command_notify.send(id);
            Redirect::to(&format!("/devices/{id}")).into_response()
        }
        Err(err) => server_error(err, "failed to end a lift"),
    }
}
