//! A parent pausing a time rule for a while ("Skole paused for 30 min") or adding screen time for
//! today ("+30 min") (handy step 6). Recorded in `time_lifts` and the security log, delivered in
//! `PolicyResponse.time_policy.lifts` after a nudge; the phone expires a rule pause by itself. A
//! rule pause can be ended early ("End now"); added screen time can't be taken back once the
//! phone has counted it.
//!
//! The forms live on Device › Time (design 31 §3.5, step 9) and the summary's Quick actions (step
//! 10; End now there too since Now went, the user 2026-10-20). Where they return is §5.1's `back` table, always with a `done` code for the
//! flash of the group that posted: `back=time` → `/devices/{id}/time` (`#extra`, `#pause`,
//! `#lifts`); anything else (`summary`, an older page) → the summary (`#quick`). A second
//! tap on a pause that is already over, or a pause of a rule deleted or switched off in another
//! tab, lands back there with nothing written and says why.

use std::collections::HashMap;

use axum::extract::{Path, State};
use axum::http::StatusCode;
use axum::response::{IntoResponse, Redirect, Response};
use axum::{Extension, Form};
use chrono::{DateTime, Utc};

use crate::AppState;
use crate::display_time::{Zone, parse_db_utc};
use crate::models::DeviceStatus;
use crate::security::{self, CurrentAdmin};
use crate::time_rules::{self, BUDGET_LIFT_DELIVERY_HOURS, TimeLiftRow};

fn server_error(err: impl std::fmt::Display, what: &str) -> Response {
    tracing::error!(%err, "{what}");
    (
        StatusCode::INTERNAL_SERVER_ERROR,
        "Couldn't save - nothing was changed. Check the server log.",
    )
        .into_response()
}

/// Where a lift form returns (§5.1): Device › Time's `group`, or the summary's group for it
/// ([summary_group]), with `done` for its flash. `back` is checked against the one value that
/// leaves the default; the phone is the path's.
fn back_to(id: i64, form: &HashMap<String, String>, group: &str, done: &str) -> Response {
    if form.get("back").map(String::as_str) == Some("time") {
        Redirect::to(&format!("/devices/{id}/time?done={done}#{group}")).into_response()
    } else {
        Redirect::to(&format!(
            "/devices/{id}?done={done}#{}",
            summary_group(group)
        ))
        .into_response()
    }
}

/// The summary's group for one of Device › Time's: the tiles, Pause a rule… and the lifts with End
/// now are all Quick actions (design 48's Now went, the user 2026-10-20).
pub(crate) fn summary_group(_time_group: &str) -> &'static str {
    "quick"
}

/// Form: `target` ("rule" or "budget"), `rule` ("all" or a rule id - only for "rule"), `minutes`
/// (15/30/60/120 for a rule, 15/30/60 for screen time), `back`. The rule must be one of the
/// device's effective rules (its own with the override on, else the global ones).
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
                    // Deleted, or the own-rules switch flipped, since the page loaded.
                    return back_to(id, &form, "pause", "gone");
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
    if target == "rule" {
        back_to(id, &form, "pause", "pause")
    } else {
        back_to(id, &form, "extra", "extra")
    }
}

/// "End now": an active rule pause stops being sent, and the phone drops it on its next sync. A
/// pause that is already over (a second tap, another tab) changes nothing and lands back; a lift
/// this phone never had is a 404.
pub async fn end_lift(
    State(state): State<AppState>,
    Path((id, lift_id)): Path<(i64, i64)>,
    Extension(CurrentAdmin(admin)): Extension<CurrentAdmin>,
    Form(form): Form<HashMap<String, String>>,
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
            match sqlx::query_scalar::<_, i64>(
                "SELECT id FROM time_lifts WHERE id = ? AND device_id = ? AND target = 'rule'",
            )
            .bind(lift_id)
            .bind(id)
            .fetch_optional(&state.db)
            .await
            {
                Ok(Some(_)) => back_to(id, &form, "lifts", "over"),
                Ok(None) => (StatusCode::NOT_FOUND, "No such pause").into_response(),
                Err(err) => server_error(err, "failed to read a lift"),
            }
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
            back_to(id, &form, "lifts", "ended")
        }
        Err(err) => server_error(err, "failed to end a lift"),
    }
}

/// A flash after a lift form (`?done=`): Device › Time's group it belongs to ([summary_group]
/// maps it on the summary), its text, and whether it is a refusal. `None` for an unknown code.
pub(crate) fn done_flash(code: &str) -> Option<(&'static str, &'static str, bool)> {
    Some(match code {
        "extra" => (
            "extra",
            "Added. The phone gets it within seconds when online.",
            false,
        ),
        "pause" => ("pause", "Paused. The rule comes back by itself.", false),
        "gone" => (
            "pause",
            "That rule no longer applies to this phone. Nothing was paused.",
            true,
        ),
        "ended" => (
            "lifts",
            "Ended. The rule is back once the phone syncs.",
            false,
        ),
        "over" => ("lifts", "That pause was already over.", false),
        _ => return None,
    })
}

/// "15 min", "1 h", "1 h 30 min".
pub(crate) fn minutes_text(minutes: i64) -> String {
    match (minutes / 60, minutes % 60) {
        (0, m) => format!("{m} min"),
        (h, 0) => format!("{h} h"),
        (h, m) => format!("{h} h {m} min"),
    }
}

/// Where a lift stands, typed (design 31 M5). A rule pause the server still sends follows the
/// phone's last `time_state.lifts_active` once that report is newer than the pause: the phone's
/// view wins - a reboot or late delivery can end a pause there before the server's expiry (QA
/// step 6 #8).
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum LiftState {
    /// Sent, and the phone hasn't reported since.
    Waiting,
    /// The phone runs it, until the server's expiry.
    OnPhone,
    /// The phone reported after it and no longer runs it.
    EndedOnPhone,
    /// "End now" at this time.
    EndedEarly(DateTime<Utc>),
    /// Past its end.
    Over,
    /// Extra screen time, still offered to the phone.
    Sent,
    /// Extra screen time, no longer offered.
    Done,
}

impl LiftState {
    /// A running rule pause, from the phone's last report (`reported_at`, its `lifts_active`).
    pub(crate) fn of_running_pause(
        lift_id: i64,
        created_at: DateTime<Utc>,
        report: Option<(DateTime<Utc>, &[i64])>,
    ) -> LiftState {
        match report {
            Some((reported_at, active)) if reported_at >= created_at => {
                if active.contains(&lift_id) {
                    LiftState::OnPhone
                } else {
                    LiftState::EndedOnPhone
                }
            }
            _ => LiftState::Waiting,
        }
    }

    /// The row's value, in the Phone Setup zone: "Until 10:42", "Ended early 10:20".
    pub(crate) fn text(
        &self,
        expires_at: Option<DateTime<Utc>>,
        zone: &Zone,
        now: DateTime<Utc>,
    ) -> String {
        match self {
            LiftState::Waiting => "Waiting for the phone".to_string(),
            LiftState::OnPhone => match expires_at {
                Some(end) => format!("Until {}", zone.until(end, now)),
                None => "On the phone".to_string(),
            },
            LiftState::EndedOnPhone => "Ended on the phone".to_string(),
            LiftState::EndedEarly(at) => format!("Ended early {}", zone.when(*at, now)),
            LiftState::Over => match expires_at {
                Some(end) => format!("Ended {}", zone.when(end, now)),
                None => "Ended".to_string(),
            },
            LiftState::Sent => "Sent".to_string(),
            LiftState::Done => "Done".to_string(),
        }
    }
}

/// One row of Device › Time's Recent group.
pub(crate) struct LiftView {
    pub id: i64,
    /// "Skole paused for 30 min", "All rules paused for 1 h", "+15 min screen time".
    pub what: String,
    /// When it was made, local ("10:12", "yesterday 21:40"), the exact time for `title=`, by whom.
    pub when: String,
    pub exact: String,
    pub by: String,
    pub status: String,
    /// A rule pause the server still sends: "End now".
    pub can_end: bool,
}

/// The newest 10 lifts of the phone, with their state (a DB error is the caller's 500).
pub(crate) async fn recent(
    db: &sqlx::SqlitePool,
    device_id: i64,
    latest: Option<&DeviceStatus>,
    zone: &Zone,
    now: DateTime<Utc>,
) -> Result<Vec<LiftView>, sqlx::Error> {
    views(db, device_id, None, latest, zone, now).await
}

/// The summary's Today (qa-31-s10-code N3): the lifts made since the local midnight, and every
/// pause still running however many lifts came after it (at most 50).
pub(crate) async fn today(
    db: &sqlx::SqlitePool,
    device_id: i64,
    latest: Option<&DeviceStatus>,
    zone: &Zone,
    now: DateTime<Utc>,
) -> Result<Vec<LiftView>, sqlx::Error> {
    let since = zone.start_of_today(now);
    views(db, device_id, Some(since), latest, zone, now).await
}

/// [since]: `None` the newest 10, else the lifts made since then and the running pauses.
async fn views(
    db: &sqlx::SqlitePool,
    device_id: i64,
    since: Option<DateTime<Utc>>,
    latest: Option<&DeviceStatus>,
    zone: &Zone,
    now: DateTime<Utc>,
) -> Result<Vec<LiftView>, sqlx::Error> {
    #[derive(sqlx::FromRow)]
    struct LiftWithState {
        #[sqlx(flatten)]
        lift: TimeLiftRow,
        active: bool,
    }
    let lifts = match since {
        None => {
            sqlx::query_as::<_, LiftWithState>(
                "SELECT *, (ended_early_at IS NULL AND expires_at > datetime('now')) AS active \
                 FROM time_lifts WHERE device_id = ? ORDER BY id DESC LIMIT 10",
            )
            .bind(device_id)
            .fetch_all(db)
            .await?
        }
        // `created_at` is `datetime('now')` text, so it compares as text.
        Some(since) => {
            sqlx::query_as::<_, LiftWithState>(
                "SELECT *, (ended_early_at IS NULL AND expires_at > datetime('now')) AS active \
                 FROM time_lifts WHERE device_id = ? AND (created_at >= ? OR (target = 'rule' \
                 AND ended_early_at IS NULL AND expires_at > datetime('now'))) \
                 ORDER BY id DESC LIMIT 50",
            )
            .bind(device_id)
            .bind(since.format("%Y-%m-%d %H:%M:%S").to_string())
            .fetch_all(db)
            .await?
        }
    };
    // What the phone last said it runs, and when.
    let reported: Option<(DateTime<Utc>, Vec<i64>)> = latest.and_then(|s| {
        let at = parse_db_utc(&s.reported_at)?;
        let state: serde_json::Value = serde_json::from_str(s.time_state_json.as_deref()?).ok()?;
        let active = state["lifts_active"]
            .as_array()
            .map(|ids| ids.iter().filter_map(serde_json::Value::as_i64).collect())
            .unwrap_or_default();
        Some((at, active))
    });
    Ok(lifts
        .into_iter()
        .map(|LiftWithState { lift, active }| {
            let created = parse_db_utc(&lift.created_at);
            let expires = parse_db_utc(&lift.expires_at);
            let (what, state) = if lift.target == "rule" {
                let rule = lift.rule_name.as_deref().unwrap_or("All rules");
                let state = match (
                    lift.ended_early_at.as_deref().and_then(parse_db_utc),
                    active,
                ) {
                    (Some(at), _) => LiftState::EndedEarly(at),
                    (None, true) => LiftState::of_running_pause(
                        lift.id,
                        created.unwrap_or(now),
                        reported.as_ref().map(|(at, ids)| (*at, ids.as_slice())),
                    ),
                    (None, false) => LiftState::Over,
                };
                (
                    format!("{rule} paused for {}", minutes_text(lift.minutes)),
                    state,
                )
            } else {
                (
                    format!("+{} screen time", minutes_text(lift.minutes)),
                    if active {
                        LiftState::Sent
                    } else {
                        LiftState::Done
                    },
                )
            };
            LiftView {
                id: lift.id,
                what,
                when: created.map(|t| zone.when(t, now)).unwrap_or_default(),
                exact: created.map(|t| zone.exact(t)).unwrap_or_default(),
                by: lift.created_by.unwrap_or_default(),
                status: state.text(expires, zone, now),
                can_end: lift.target == "rule" && active,
            }
        })
        .collect())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn utc(s: &str) -> DateTime<Utc> {
        parse_db_utc(s).unwrap()
    }

    #[test]
    fn a_running_pause_follows_the_phone_once_it_reported_after_it() {
        let created = utc("2026-10-05 10:00:00");
        assert_eq!(
            LiftState::of_running_pause(7, created, None),
            LiftState::Waiting
        );
        // A report from before the pause says nothing about it.
        assert_eq!(
            LiftState::of_running_pause(7, created, Some((utc("2026-10-05 09:59:00"), &[]))),
            LiftState::Waiting
        );
        assert_eq!(
            LiftState::of_running_pause(7, created, Some((utc("2026-10-05 10:01:00"), &[7]))),
            LiftState::OnPhone
        );
        assert_eq!(
            LiftState::of_running_pause(7, created, Some((utc("2026-10-05 10:01:00"), &[8]))),
            LiftState::EndedOnPhone
        );
    }

    #[test]
    fn lift_states_read_in_local_time() {
        let oslo = Zone::from_setting("Europe/Oslo");
        let now = utc("2026-10-05 10:00:00");
        let end = Some(utc("2026-10-05 10:30:00"));
        // 10:30 UTC is 12:30 in Oslo (CEST): never the UTC clock.
        assert_eq!(LiftState::OnPhone.text(end, &oslo, now), "Until 12:30");
        assert_eq!(
            LiftState::EndedEarly(utc("2026-10-05 09:50:00")).text(end, &oslo, now),
            "Ended early 11:50"
        );
        assert_eq!(
            LiftState::Over.text(Some(utc("2026-10-04 19:30:00")), &oslo, now),
            "Ended yesterday 21:30"
        );
        // Past midnight in Oslo.
        assert_eq!(
            LiftState::OnPhone.text(Some(utc("2026-10-05 22:30:00")), &oslo, now),
            "Until 00:30 tomorrow"
        );
        // An empty Phone Setup zone says so.
        assert_eq!(
            LiftState::OnPhone.text(end, &Zone::utc_fallback(), now),
            "Until 10:30 UTC"
        );
        assert_eq!(
            LiftState::Waiting.text(end, &oslo, now),
            "Waiting for the phone"
        );
    }

    #[test]
    fn durations_read_short() {
        assert_eq!(minutes_text(15), "15 min");
        assert_eq!(minutes_text(60), "1 h");
        assert_eq!(minutes_text(120), "2 h");
        assert_eq!(minutes_text(90), "1 h 30 min");
    }

    #[test]
    fn every_done_code_names_a_group_of_the_page() {
        for code in ["extra", "pause", "gone", "ended", "over"] {
            let (group, text, _) = done_flash(code).unwrap();
            assert!(["extra", "pause", "lifts"].contains(&group), "{code}");
            assert_eq!(summary_group(group), "quick", "{code}");
            assert!(!text.is_empty());
        }
        assert_eq!(done_flash("<script>"), None);
    }
}
