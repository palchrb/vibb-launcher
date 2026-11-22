//! Design 98 §4.4 (Foreldrekode, `docs/design/98-airplane-mode-assistant.md` §4.4.4-§4.4.6): the parent code's pause of
//! the rule in force, set offline on the phone's time-rule screen and posted at the start of its next sync.
//!
//! `POST /api/devices/rule-pause` ([intake]): `{key, minutes, started_at_ms, rules: [{rule_id, until_ms}]}`
//! (`testdata/rule_pause.json`, shared with the launcher). [check] refuses with a `400` a key that isn't 32 lowercase hex
//! characters, minutes outside `time_rules::RULE_LIFT_MINUTES`, a start past the clock slack ahead or older than 7 days,
//! no rules or more than 8, or a rule's end at or before the start or past the start plus the minutes and the slack. A
//! rule id the phone doesn't have is skipped; a design 93 copy's negative id is its `time_lock_now` row's rule (the
//! row the PWA's pause of a copy writes, whose twin `lock_now::twin_lifts` serves). None left: `200 {"accepted": false,
//! "reason": "unknown_rule"}`.
//!
//! One `time_lifts` row per rule: `target = 'rule'`, `expires_at` its end, `created_at` the pause's start (bounded by
//! now), `created_by` NULL and `phone_key` the key - the source marker the pages word ("Satt med foreldrekoden på
//! telefonen"). A retried post inserts nothing (the rows of the key are read first, in one `BEGIN IMMEDIATE`; the
//! unique index `(device_id, phone_key, rule_id)` holds it anyway) and answers the same ids, never reviving a row the
//! parent ended. A pause already over is stored as an over row and answered `"over": true`. The first post writes the
//! phone line `time_lift_phone` and nudges the phone.
//!
//! Security review 2026-11-17 M2: the parent code is checked on the phone only, so a token alone could lift a rule.
//! By the user's choice (2026-11-18) there is no cap: every new pause is told instead - it raises the time-rule
//! screen's parent-PIN event (`parent_events::Phone::rule_pause`, pushed under the "Parent PIN used" switch), keyed by
//! its end like the phone's own PIN event for it, so the inbox has one line per pause whichever comes first.

use axum::Extension;
use axum::Json;
use axum::extract::State;
use axum::http::StatusCode;
use axum::response::{IntoResponse, Response};
use serde_json::json;

use crate::AppState;
use crate::security::AuthedDevice;

/// `POST /api/devices/rule-pause` takes at most this much (8 rules).
pub const BODY_LIMIT: usize = 2048;
/// The clock slack the phone's instants may run ahead by (§4.4.5).
const CLOCK_SLACK_MS: i64 = 5 * 60_000;
/// The oldest start taken.
const MAX_AGE_MS: i64 = 7 * 24 * 60 * 60_000;
/// The most rules one pause names.
pub const MAX_RULES: usize = 8;

/// The phone's post (`rule_pause.json`'s `request`). Unknown keys are ignored.
#[derive(Debug, serde::Deserialize)]
pub struct Body {
    pub key: String,
    pub minutes: i64,
    pub started_at_ms: i64,
    pub rules: Vec<RuleEnd>,
}

/// One rule of a pause and its own end.
#[derive(Debug, serde::Deserialize)]
pub struct RuleEnd {
    pub rule_id: i64,
    pub until_ms: i64,
}

/// The pause's key: 32 lowercase hex characters, as the phone makes it.
pub fn key_ok(key: &str) -> bool {
    key.len() == 32
        && key
            .bytes()
            .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
}

/// §4.4.5's checks, field by field; `false` is the `400`.
pub fn check(body: &Body, now_ms: i64) -> bool {
    let started = body.started_at_ms;
    let latest_end = started
        .saturating_add(body.minutes.saturating_mul(60_000))
        .saturating_add(CLOCK_SLACK_MS);
    key_ok(&body.key)
        && crate::time_rules::valid_lift("rule", body.minutes)
        && started <= now_ms.saturating_add(CLOCK_SLACK_MS)
        && started >= now_ms.saturating_sub(MAX_AGE_MS)
        && (1..=MAX_RULES).contains(&body.rules.len())
        && body
            .rules
            .iter()
            .all(|r| r.until_ms > started && r.until_ms <= latest_end)
}

/// A rule of the pause as stored: its id (a copy's resolved to the rule it copies), its stored name and its end.
#[derive(Debug, Clone, PartialEq, Eq)]
struct Paused {
    rule_id: i64,
    name: String,
    until_ms: i64,
}

/// What the post came to.
enum Outcome {
    /// No rule the phone has.
    UnknownRule,
    /// Stored (or a retry's rows found): the ids, the rules this post inserted, and every rule of the pause.
    Stored(Vec<i64>, Vec<Paused>, Vec<Paused>),
}

/// `POST /api/devices/rule-pause` (bearer): see the module doc. A DB error is a `500` (the phone retries).
pub async fn intake(
    State(state): State<AppState>,
    Extension(AuthedDevice(device)): Extension<AuthedDevice>,
    body: axum::body::Bytes,
) -> Response {
    let Ok(body) = serde_json::from_slice::<Body>(&body) else {
        return StatusCode::BAD_REQUEST.into_response();
    };
    let now = chrono::Utc::now();
    let now_ms = now.timestamp_millis();
    if !check(&body, now_ms) {
        return StatusCode::BAD_REQUEST.into_response();
    }
    let result = async {
        let effective = crate::time_rules::effective_rule_rows(&state.db, device.id).await?;
        // A rule turned on now (design 93): its copy's id is -(time_lock_now.id), taken only for a lock that ran when
        // the pause started (the clock slack either side). Its rule is looked up in the phone's scope (design 123: one
        // switched off for the phone may be turned on), and the copy's `copy_of` row merges into it; every other
        // positive id must be one of the effective rules.
        let started_lo = crate::lock_now::db_time(
            chrono::DateTime::from_timestamp_millis(
                body.started_at_ms.saturating_sub(CLOCK_SLACK_MS),
            )
            .unwrap_or(now),
        );
        let started_hi = crate::lock_now::db_time(
            chrono::DateTime::from_timestamp_millis(
                body.started_at_ms.saturating_add(CLOCK_SLACK_MS),
            )
            .unwrap_or(now),
        );
        let mut through_lock: Vec<crate::time_rules::TimeRuleRow> = Vec::new();
        let mut phone: Option<Vec<crate::time_rules::TimeRuleRow>> = None;
        let mut resolved: Vec<(i64, i64)> = Vec::new();
        for asked in body.rules.iter().filter(|r| r.rule_id < 0) {
            let copied: Option<Option<i64>> = sqlx::query_scalar(
                "SELECT rule_id FROM time_lock_now WHERE id = ? AND device_id = ? \
                 AND created_at <= ? AND ends_at > ? \
                 AND (ended_early_at IS NULL OR ended_early_at > ?)",
            )
            .bind(-asked.rule_id)
            .bind(device.id)
            .bind(&started_hi)
            .bind(&started_lo)
            .bind(&started_lo)
            .fetch_optional(&state.db)
            .await?;
            let Some(rule_id) = copied.flatten() else {
                continue;
            };
            if phone.is_none() {
                phone = Some(crate::time_rules::phone_rule_rows(&state.db, device.id).await?);
            }
            if let Some(rule) = phone
                .as_deref()
                .unwrap_or_default()
                .iter()
                .find(|r| r.id == rule_id)
            {
                through_lock.push(rule.clone());
                resolved.push((asked.rule_id, rule_id));
            }
        }
        let mut paused: Vec<Paused> = Vec::new();
        for asked in &body.rules {
            let rule_id = if asked.rule_id < 0 {
                match resolved.iter().find(|(copy, _)| *copy == asked.rule_id) {
                    Some((_, rule_id)) => *rule_id,
                    None => continue,
                }
            } else {
                asked.rule_id
            };
            let Some(rule) = effective
                .iter()
                .chain(through_lock.iter())
                .find(|r| r.id == rule_id)
            else {
                continue;
            };
            match paused.iter_mut().find(|p| p.rule_id == rule_id) {
                // A rule and its copy: one row, to the later end.
                Some(p) => p.until_ms = p.until_ms.max(asked.until_ms),
                None => paused.push(Paused {
                    rule_id,
                    name: rule.name.clone(),
                    until_ms: asked.until_ms,
                }),
            }
        }
        if paused.is_empty() {
            return Ok::<_, sqlx::Error>(Outcome::UnknownRule);
        }
        let started =
            chrono::DateTime::from_timestamp_millis(body.started_at_ms.min(now_ms)).unwrap_or(now);
        let created_at = crate::lock_now::db_time(started);
        let mut tx = state.db.begin_with("BEGIN IMMEDIATE").await?;
        let existing: Vec<i64> = sqlx::query_scalar(
            "SELECT rule_id FROM time_lifts WHERE device_id = ? AND phone_key = ? AND rule_id IS NOT NULL",
        )
        .bind(device.id)
        .bind(&body.key)
        .fetch_all(&mut *tx)
        .await?;
        let mut inserted: Vec<&Paused> = Vec::new();
        for p in &paused {
            if existing.contains(&p.rule_id) {
                continue;
            }
            let expires_at = crate::lock_now::db_time(
                chrono::DateTime::from_timestamp_millis(p.until_ms).unwrap_or(now),
            );
            let done = sqlx::query(
                "INSERT INTO time_lifts (device_id, target, rule_id, rule_name, minutes, created_at, \
                 expires_at, created_by, phone_key) VALUES (?, 'rule', ?, ?, ?, ?, ?, NULL, ?) \
                 ON CONFLICT (device_id, phone_key, rule_id) WHERE phone_key IS NOT NULL DO NOTHING",
            )
            .bind(device.id)
            .bind(p.rule_id)
            .bind(&p.name)
            .bind(body.minutes)
            .bind(&created_at)
            .bind(&expires_at)
            .bind(&body.key)
            .execute(&mut *tx)
            .await?;
            if done.rows_affected() > 0 {
                inserted.push(p);
            }
        }
        let mut ids: Vec<i64> = Vec::new();
        for p in &paused {
            let id: Option<i64> = sqlx::query_scalar(
                "SELECT id FROM time_lifts WHERE device_id = ? AND phone_key = ? AND rule_id = ?",
            )
            .bind(device.id)
            .bind(&body.key)
            .bind(p.rule_id)
            .fetch_optional(&mut *tx)
            .await?;
            ids.extend(id);
        }
        tx.commit().await?;
        ids.sort_unstable();
        ids.dedup();
        let inserted: Vec<Paused> = inserted.into_iter().cloned().collect();
        Ok(Outcome::Stored(ids, inserted, paused))
    }
    .await;
    let (ids, inserted, paused) = match result {
        Ok(Outcome::Stored(ids, inserted, paused)) => (ids, inserted, paused),
        Ok(Outcome::UnknownRule) => {
            return Json(json!({"accepted": false, "reason": "unknown_rule"})).into_response();
        }
        Err(err) => {
            tracing::error!(device_id = device.id, %err, "can't store the phone's rule pause");
            return StatusCode::INTERNAL_SERVER_ERROR.into_response();
        }
    };
    let over = paused.iter().all(|p| p.until_ms <= now_ms);
    if !inserted.is_empty() {
        // The security log's line (as `lifts::record_lift`'s `time_lift`): the phone, each rule, the minutes.
        let device_word = device.id.to_string();
        let minutes = body.minutes.to_string();
        let params = [
            ("device", device_word.as_str()),
            ("minutes", minutes.as_str()),
        ];
        let mut line: Option<crate::security::Detail> = None;
        for p in &inserted {
            line = Some(
                match line {
                    None => crate::security::Detail::words("security.detail.lift.phone", &params),
                    Some(line) => line.and("security.detail.lift.phone", &params),
                }
                .rule("rule", &p.name),
            );
        }
        if let Some(line) = line {
            crate::security::record_phone_event_logged(
                &state.db,
                device.id,
                crate::security::PhoneLine::TimeLiftPhone,
                line,
            )
            .await;
        }
        // M2: the inbox's line (and the push, under "Parent PIN used"), one with the phone's own PIN event.
        let until_ms = body.rules.iter().map(|r| r.until_ms).max().unwrap_or(0);
        if let Some(phone) = crate::parent_events::Phone::load(&state.db, device.id).await {
            let started = chrono::DateTime::from_timestamp_millis(body.started_at_ms.min(now_ms))
                .unwrap_or(now);
            crate::parent_events::raise_logged(&state, &phone.rule_pause(started, until_ms)).await;
        }
        if !over {
            let _ = state.command_notify.send(device.id);
        }
    }
    if over {
        Json(json!({"accepted": true, "lift_ids": ids, "over": true})).into_response()
    } else {
        Json(json!({"accepted": true, "lift_ids": ids})).into_response()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn body(key: &str, minutes: i64, started: i64, ends: &[i64]) -> Body {
        Body {
            key: key.to_string(),
            minutes,
            started_at_ms: started,
            rules: ends
                .iter()
                .map(|until| RuleEnd {
                    rule_id: 2,
                    until_ms: *until,
                })
                .collect(),
        }
    }

    #[test]
    fn the_checks() {
        let now = 1_794_826_260_000;
        let key = "0123456789abcdef0123456789abcdef";
        let start = now - 60_000;
        let end = start + 30 * 60_000;
        assert!(check(&body(key, 30, start, &[end]), now));
        assert!(check(&body(key, 30, start, &[end + CLOCK_SLACK_MS]), now));
        assert!(!check(
            &body(key, 30, start, &[end + CLOCK_SLACK_MS + 1]),
            now
        ));
        assert!(!check(&body(key, 30, start, &[start]), now));
        assert!(!check(&body(key, 45, start, &[end]), now));
        assert!(!check(
            &body(key, 30, now + CLOCK_SLACK_MS + 1, &[now + 600_000 * 4]),
            now
        ));
        assert!(!check(&body(key, 30, now - MAX_AGE_MS - 1, &[now]), now));
        assert!(!check(&body(key, 30, start, &[]), now));
        assert!(!check(&body(key, 30, start, &[end; 9]), now));
        assert!(check(&body(key, 30, start, &[end; 8]), now));
        for bad in [
            "0123456789ABCDEF0123456789abcdef",
            "0123456789abcdef0123456789abcde",
            "0123456789abcdef0123456789abcdeg",
            "Emma's pause",
        ] {
            assert!(!check(&body(bad, 30, start, &[end]), now), "{bad}");
        }
    }
}
