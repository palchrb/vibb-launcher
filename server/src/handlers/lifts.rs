//! A parent pausing a time rule for a while ("Skole paused for 30 min") or adding screen time for
//! today ("+30 min") (handy step 6). Recorded in `time_lifts` and the security log, delivered in
//! `PolicyResponse.time_policy.lifts` after a nudge; the phone expires a rule pause by itself. A
//! rule pause can be ended early ("End now"); added screen time can't be taken back once the
//! phone has counted it.
//!
//! The forms live on the summary's Quick actions (step 10; End now there too since Now went, the
//! user 2026-10-20; Device › Time Rules had them too until 2026-11-01, the user: "det er bare
//! duplicat"). They return to the summary's `#quick` with a `done` code for its flash, whatever
//! `back` says (an older Time tab's `back=time` too). A second tap on a pause that is already
//! over, or a pause of a rule deleted or switched off in another tab, lands back there with
//! nothing written and says why.

use std::collections::HashMap;

use axum::extract::{Path, State};
use axum::http::StatusCode;
use axum::response::{IntoResponse, Redirect, Response};
use axum::{Extension, Form};
use chrono::{DateTime, Utc};

use crate::AppState;
use crate::display_time::{Zone, parse_db_utc};
use crate::i18n::{Lang, t, t_params};
use crate::models::DeviceStatus;
use crate::security::{self, CurrentAdmin};
use crate::time_rules::{self, BUDGET_LIFT_DELIVERY_HOURS, TimeLiftRow};

fn server_error(lang: Lang, err: impl std::fmt::Display, what: &str) -> Response {
    tracing::error!(%err, "{what}");
    crate::i18n::error(
        lang,
        StatusCode::INTERNAL_SERVER_ERROR,
        "error.save_failed_log",
    )
}

/// Where a lift form returns (§5.1): the summary's group for it ([summary_group]), with `done` for
/// its flash. Since Device › Time Rules lost Extra Time Today, Pause a Rule and Recent
/// (2026-11-01) every form is the summary's; an older Time tab's `back=time` lands there too.
fn back_to(id: i64, group: &str, done: &str) -> Response {
    Redirect::to(&format!(
        "/devices/{id}?done={done}#{}",
        summary_group(group)
    ))
    .into_response()
}

/// The summary's group for one of Device › Time's: the tiles, Pause a rule… and the lifts with End
/// now are all Quick actions (design 48's Now went, the user 2026-10-20).
pub(crate) fn summary_group(_time_group: &str) -> &'static str {
    "quick"
}

/// Form: `target` ("rule" or "budget"), `rule` ("all" or a rule id - only for "rule"), `minutes`
/// (15/30/60/120 for a rule, 15/30/60 for screen time), `back` (ignored since 2026-11-01). The
/// rule must be one of the device's effective rules (`time_rules::effective_rule_rows`).
pub async fn create_lift(
    State(state): State<AppState>,
    Path(id): Path<i64>,
    lang: Lang,
    Extension(CurrentAdmin(admin)): Extension<CurrentAdmin>,
    Form(form): Form<HashMap<String, String>>,
) -> Response {
    match sqlx::query_scalar::<_, i64>("SELECT device_id FROM device_policy WHERE device_id = ?")
        .bind(id)
        .fetch_optional(&state.db)
        .await
    {
        Ok(Some(_)) => {}
        Ok(None) => {
            return crate::i18n::error(lang, StatusCode::NOT_FOUND, "error.device_not_found");
        }
        Err(err) => return server_error(lang, err, "failed to read a device"),
    }

    let target = form.get("target").map(String::as_str).unwrap_or("");
    let minutes = form
        .get("minutes")
        .and_then(|m| m.trim().parse::<i64>().ok())
        .unwrap_or(0);
    if !time_rules::valid_lift(target, minutes) {
        return crate::i18n::error(lang, StatusCode::BAD_REQUEST, "error.unknown_lift");
    }

    let (rule_id, rule_name) = if target == "rule" {
        match form.get("rule").map(|r| r.trim()).unwrap_or("all") {
            "all" | "" => (None, None),
            raw => {
                let Ok(rule_id) = raw.parse::<i64>() else {
                    return crate::i18n::error(lang, StatusCode::BAD_REQUEST, "error.unknown_rule");
                };
                let rules = match time_rules::effective_rule_rows(&state.db, id).await {
                    Ok(rules) => rules,
                    Err(err) => return server_error(lang, err, "failed to read time rules"),
                };
                let Some(rule) = rules.into_iter().find(|r| r.id == rule_id) else {
                    // Deleted, or switched off for the phone, since the page loaded.
                    return back_to(id, "pause", "gone");
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
        return server_error(lang, err, "failed to record a lift");
    }

    let device = id.to_string();
    let detail = match (target, rule_name.as_deref()) {
        ("rule", Some(rule)) => security::Detail::words(
            "security.detail.lift.rule",
            &[
                ("device", &device),
                ("rule", rule),
                ("minutes", &minutes.to_string()),
            ],
        ),
        ("rule", None) => security::Detail::words(
            "security.detail.lift.all_rules",
            &[("device", &device), ("minutes", &minutes.to_string())],
        ),
        _ => security::Detail::words(
            "security.detail.lift.budget",
            &[("device", &device), ("minutes", &minutes.to_string())],
        ),
    }
    .with_facts(format!("device {id}"));
    security::record_security_event(
        &state.db,
        "time_lift",
        Some(&admin.username),
        None,
        Some(detail),
    )
    .await;
    let _ = state.command_notify.send(id);
    if target == "rule" {
        back_to(id, "pause", "pause")
    } else {
        back_to(id, "extra", "extra")
    }
}

/// "End now": an active rule pause stops being sent, and the phone drops it on its next sync. A
/// pause that is already over (a second tap, another tab) changes nothing and lands back; a lift
/// this phone never had is a 404.
pub async fn end_lift(
    State(state): State<AppState>,
    Path((id, lift_id)): Path<(i64, i64)>,
    lang: Lang,
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
            match sqlx::query_scalar::<_, i64>(
                "SELECT id FROM time_lifts WHERE id = ? AND device_id = ? AND target = 'rule'",
            )
            .bind(lift_id)
            .bind(id)
            .fetch_optional(&state.db)
            .await
            {
                Ok(Some(_)) => back_to(id, "lifts", "over"),
                Ok(None) => crate::i18n::error(lang, StatusCode::NOT_FOUND, "error.no_such_pause"),
                Err(err) => server_error(lang, err, "failed to read a lift"),
            }
        }
        Ok(_) => {
            security::record_security_event(
                &state.db,
                "time_lift_ended",
                Some(&admin.username),
                None,
                Some(
                    security::Detail::words(
                        "security.detail.lift_ended",
                        &[("device", &id.to_string()), ("lift", &lift_id.to_string())],
                    )
                    .with_facts(format!("device {id}")),
                ),
            )
            .await;
            let _ = state.command_notify.send(id);
            back_to(id, "lifts", "ended")
        }
        Err(err) => server_error(lang, err, "failed to end a lift"),
    }
}

/// A flash after a lift form (`?done=`): Device › Time's group it belongs to ([summary_group]
/// maps it on the summary), its key, and whether it is a refusal. `None` for an unknown code.
pub(crate) fn done_flash(code: &str) -> Option<(&'static str, &'static str, bool)> {
    Some(match code {
        "extra" => ("extra", "device.summary.flash.extra", false),
        "pause" => ("pause", "device.summary.flash.pause", false),
        "gone" => ("pause", "device.summary.flash.gone", true),
        "ended" => ("lifts", "device.summary.flash.ended", false),
        "over" => ("lifts", "device.summary.flash.over", false),
        _ => return None,
    })
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

    /// The row's value, in the Phone Setup zone and the reader's language: "Until 10:42", "Ended early 10:20".
    pub(crate) fn text(
        &self,
        expires_at: Option<DateTime<Utc>>,
        zone: &Zone,
        now: DateTime<Utc>,
    ) -> String {
        let lang = zone.lang();
        let at = |key, at: String| t_params(lang, key, &[("at", &at)]);
        match self {
            LiftState::Waiting => t(lang, "device.summary.lift_state.waiting").to_string(),
            LiftState::OnPhone => match expires_at {
                Some(end) => at("device.summary.lift_state.until", zone.until(end, now)),
                None => t(lang, "device.summary.lift_state.on_phone").to_string(),
            },
            LiftState::EndedOnPhone => {
                t(lang, "device.summary.lift_state.ended_on_phone").to_string()
            }
            LiftState::EndedEarly(when) => {
                at("device.summary.lift.ended_early", zone.when(*when, now))
            }
            LiftState::Over => match expires_at {
                Some(end) => at("device.summary.lift.ended", zone.when(end, now)),
                None => t(lang, "device.summary.lift.over").to_string(),
            },
            LiftState::Sent => t(lang, "device.summary.lift_state.sent").to_string(),
            LiftState::Done => t(lang, "device.summary.lift_state.done").to_string(),
        }
    }
}

/// One lift as the summary's Today lists it.
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

/// The summary's Today (qa-31-s10-code N3): the lifts made since the local midnight, and every
/// pause still running however many lifts came after it (at most 50), with their state (a DB
/// error is the caller's 500).
pub(crate) async fn today(
    db: &sqlx::SqlitePool,
    device_id: i64,
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
    let since = zone.start_of_today(now);
    // `created_at` is `datetime('now')` text, so it compares as text.
    let lifts = sqlx::query_as::<_, LiftWithState>(
        "SELECT *, (ended_early_at IS NULL AND expires_at > datetime('now')) AS active \
         FROM time_lifts WHERE device_id = ? AND (created_at >= ? OR (target = 'rule' \
         AND ended_early_at IS NULL AND expires_at > datetime('now'))) \
         ORDER BY id DESC LIMIT 50",
    )
    .bind(device_id)
    .bind(since.format("%Y-%m-%d %H:%M:%S").to_string())
    .fetch_all(db)
    .await?;
    // Who made each lift, by display name (design 62 §6).
    let names = crate::parents::Names::load(db).await?;
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
            let lang = zone.lang();
            let length = crate::display_time::duration(lang, lift.minutes);
            let (what, state) = if lift.target == "rule" {
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
                let what = match lift.rule_name.as_deref() {
                    Some(rule) => t_params(
                        lang,
                        "device.summary.lift.rule",
                        &[
                            ("rule", &crate::time_rules::shown_name(lang, rule)),
                            ("duration", &length),
                        ],
                    ),
                    None => t_params(
                        lang,
                        "device.summary.lift.all_rules",
                        &[("duration", &length)],
                    ),
                };
                (what, state)
            } else {
                (
                    t_params(lang, "device.summary.today.extra", &[("duration", &length)]),
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
                by: lift
                    .created_by
                    .as_deref()
                    .map(|by| names.of_username(by).to_string())
                    .unwrap_or_default(),
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
        use crate::display_time::duration;
        assert_eq!(duration(Lang::En, 15), "15 min");
        assert_eq!(duration(Lang::En, 60), "1 h");
        assert_eq!(duration(Lang::En, 120), "2 h");
        assert_eq!(duration(Lang::En, 90), "1 h 30 min");
        assert_eq!(duration(Lang::Nb, 90), "1 t 30 min");
    }

    #[test]
    fn lift_states_read_in_norwegian() {
        let oslo = Zone::from_setting("Europe/Oslo").in_lang(Lang::Nb);
        let now = utc("2026-10-05 10:00:00");
        let end = Some(utc("2026-10-05 10:30:00"));
        assert_eq!(LiftState::OnPhone.text(end, &oslo, now), "Til 12:30");
        assert_eq!(
            LiftState::Waiting.text(end, &oslo, now),
            "Venter på telefonen"
        );
        assert_eq!(LiftState::Done.text(end, &oslo, now), "Ferdig");
    }

    #[test]
    fn every_done_code_names_a_group_of_the_page() {
        for code in ["extra", "pause", "gone", "ended", "over"] {
            let (group, text, _) = done_flash(code).unwrap();
            assert!(["extra", "pause", "lifts"].contains(&group), "{code}");
            assert_eq!(summary_group(group), "quick", "{code}");
            for lang in Lang::ALL {
                assert!(crate::i18n::has(lang, text), "{text}");
            }
        }
        assert_eq!(done_flash("<script>"), None);
    }
}
