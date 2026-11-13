//! The Regler sheet's routes (design 93 §5): "Slå på nå" turns a rule on now for the rest of a
//! window today, "Sett på pause" pauses a rule (the lifts' own core), and a running on-lock is
//! extended, shortened or ended early. Every route returns to the summary's `#quick` with a `done`
//! code; a refusal adds `open=rules`, the mode and the rule posted, so the sheet renders open as it
//! was, with its reason (§6.5). Each logs its own security event (`settings_log::NOT_SAVES`) and
//! nudges the phone; none makes an inbox event or a push.

use std::collections::HashMap;

use axum::extract::{Path, State};
use axum::http::StatusCode;
use axum::response::{IntoResponse, Redirect, Response};
use axum::{Extension, Form};
use chrono::Utc;
use chrono_tz::Tz;

use crate::AppState;
use crate::i18n::Lang;
use crate::lock_now::{self, Choice, LockNowRow};
use crate::models::DeviceStatus;
use crate::security::{self, CurrentAdmin};

fn server_error(lang: Lang, err: impl std::fmt::Display, what: &str) -> Response {
    tracing::error!(%err, "{what}");
    crate::i18n::error(
        lang,
        StatusCode::INTERNAL_SERVER_ERROR,
        "error.save_failed_log",
    )
}

/// Back to the summary's Quick Actions with `done`; a refusal ([Some] mode and rule) reopens the
/// Regler sheet as it was posted. Only a rule id or "all" goes back into the address.
fn back(id: i64, done: &str, sheet: Option<(&str, &str)>) -> Response {
    let open = match sheet {
        Some((mode, rule)) => {
            let mode = if mode == "pause" { "pause" } else { "on" };
            let rule = if rule == "all" || rule.parse::<i64>().is_ok() {
                format!("&rule={rule}")
            } else {
                String::new()
            };
            format!("&open=rules&mode={mode}{rule}")
        }
        None => String::new(),
    };
    Redirect::to(&format!("/devices/{id}?done={done}{open}#quick")).into_response()
}

/// The phone's newest report.
async fn latest_status(
    db: &sqlx::SqlitePool,
    id: i64,
) -> Result<Option<DeviceStatus>, sqlx::Error> {
    sqlx::query_as::<_, DeviceStatus>(
        "SELECT * FROM device_status WHERE device_id = ? ORDER BY reported_at DESC, id DESC LIMIT 1",
    )
    .bind(id)
    .fetch_optional(db)
    .await
}

/// The phone is open for setup at its newest report: the restrictions pause or Play install mode
/// (`RelockCard::lock_again`'s condition).
pub(crate) fn open_for_setup(status: &DeviceStatus, now_ms: i64) -> bool {
    status.paused_at(now_ms) || status.install_mode_until_ms.is_some_and(|u| u > now_ms)
}

/// "16:30" for a stored minute; 1439 is midnight, "24:00" in the security log.
fn clock(minute: i64) -> String {
    let minute = if minute >= crate::time_rules::LAST_MINUTE {
        24 * 60
    } else {
        minute
    };
    format!("{:02}:{:02}", minute / 60, minute % 60)
}

/// `POST /devices/{id}/rules-now` (full auth): the Regler sheet's form. `mode=pause` is a rule
/// pause (`lifts::record_lift`), `mode=on` turns a rule on now (§5.1).
pub async fn rules_now(
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
    let rule = form.get("rule").map(|r| r.trim()).unwrap_or("");
    match form.get("mode").map(String::as_str) {
        Some("pause") => {
            let minutes = form
                .get("pause_minutes")
                .and_then(|m| m.trim().parse::<i64>().ok())
                .unwrap_or(0);
            let rule = if rule.is_empty() { "all" } else { rule };
            match crate::handlers::lifts::record_lift(
                &state, lang, id, &admin, "rule", rule, minutes,
            )
            .await
            {
                Ok("gone") => back(id, "gone", Some(("pause", rule))),
                Ok(done) => back(id, done, None),
                Err(refused) => refused,
            }
        }
        Some("on") => turn_on(&state, lang, id, &admin, rule, &form).await,
        _ => crate::i18n::error(lang, StatusCode::BAD_REQUEST, "error.unknown_lift"),
    }
}

/// §5.1: one running on-lock per phone, the pauses that would lift it ended in the same
/// transaction, and the phone's opening for setup ended by `end_windows` when it shows one.
async fn turn_on(
    state: &AppState,
    lang: Lang,
    id: i64,
    admin: &crate::models::AdminUser,
    rule: &str,
    form: &HashMap<String, String>,
) -> Response {
    let refuse = |code: &str| back(id, code, Some(("on", rule)));
    if rule.is_empty() || rule == "all" {
        return refuse("on_needs_rule");
    }
    let Ok(rule_id) = rule.parse::<i64>() else {
        return crate::i18n::error(lang, StatusCode::BAD_REQUEST, "error.unknown_rule");
    };
    let latest = match latest_status(&state.db, id).await {
        Ok(latest) => latest,
        Err(err) => return server_error(lang, err, "failed to read the last report"),
    };
    let Some(tz) = lock_now::phone_zone(latest.as_ref()) else {
        return refuse("on_no_zone");
    };
    let rules = match crate::time_rules::effective_rule_rows(&state.db, id).await {
        Ok(rules) => rules,
        Err(err) => return server_error(lang, err, "failed to read time rules"),
    };
    let Some(rule_row) = rules.into_iter().find(|r| r.id == rule_id) else {
        return refuse("on_gone");
    };
    let now = Utc::now();
    let local = now.with_timezone(&tz).naive_local();
    let start = lock_now::minute_of(local);
    let til = form.get("til").map(|t| t.trim()).unwrap_or("");
    let choice = if !til.is_empty() {
        match lock_now::parse_til(til) {
            Some(minute) => Choice::Til(minute),
            None => return refuse("til_invalid"),
        }
    } else {
        match form.get("on_len").map(|l| l.trim()) {
            Some("60") => Choice::Hours(1),
            Some("120") => Choice::Hours(2),
            Some("180") => Choice::Hours(3),
            Some("day") => Choice::Day,
            _ => return refuse("til_invalid"),
        }
    };
    let end = match lock_now::end_minute(start, choice) {
        Ok(end) => end,
        Err(code) => return refuse(code),
    };
    let date = local.date();
    let ends_at = lock_now::db_time(lock_now::instant_of(tz, date, end));

    let written = async {
        let mut tx = state.db.begin().await?;
        let inserted = sqlx::query(
            "INSERT INTO time_lock_now (device_id, rule_id, rule_name, zone, local_date, \
             start_minute, end_minute, ends_at, created_by) \
             SELECT ?, ?, ?, ?, ?, ?, ?, ?, ? WHERE NOT EXISTS (SELECT 1 FROM time_lock_now \
             WHERE device_id = ? AND ended_early_at IS NULL AND rule_id IS NOT NULL \
             AND ends_at > datetime('now'))",
        )
        .bind(id)
        .bind(rule_row.id)
        .bind(&rule_row.name)
        .bind(tz.name())
        .bind(date.format("%Y-%m-%d").to_string())
        .bind(start)
        .bind(end)
        .bind(&ends_at)
        .bind(&admin.username)
        .bind(id)
        .execute(&mut *tx)
        .await?
        .rows_affected();
        if inserted == 0 {
            return Ok::<bool, sqlx::Error>(false);
        }
        // The pauses that would lift it end now (§4.3): this rule's and "Alle regler".
        sqlx::query(
            "UPDATE time_lifts SET ended_early_at = datetime('now') WHERE device_id = ? \
             AND target = 'rule' AND (rule_id IS NULL OR rule_id = ?) \
             AND ended_early_at IS NULL AND expires_at > datetime('now')",
        )
        .bind(id)
        .bind(rule_row.id)
        .execute(&mut *tx)
        .await?;
        tx.commit().await?;
        Ok(true)
    }
    .await;
    match written {
        Ok(true) => {}
        Ok(false) => return refuse("on_busy"),
        Err(err) => return server_error(lang, err, "failed to turn a rule on"),
    }

    // The phone's opening for setup (a pause or Play install mode) ends in the same tap, on a
    // launcher that takes the command (§5.1 step 4).
    let now_ms = now.timestamp_millis();
    let open = latest.as_ref().is_some_and(|s| {
        crate::handlers::window_relock::takes_commands(Some(s)) && open_for_setup(s, now_ms)
    });
    if open
        && let Err(err) = crate::handlers::window_relock::queue_end_windows(state, id, admin).await
    {
        tracing::error!(device_id = id, %err, "can't end the opening for a rule turned on");
    }

    security::record_security_event(
        &state.db,
        "time_lock_now",
        Some(&admin.username),
        None,
        Some(
            security::Detail::words(
                "security.detail.lock_now.on",
                &[
                    ("device", &id.to_string()),
                    ("from", &clock(start)),
                    ("to", &clock(end)),
                ],
            )
            .rule("rule", &rule_row.name)
            .with_facts(format!("device {id}")),
        ),
    )
    .await;
    let _ = state.command_notify.send(id);
    back(id, "on", None)
}

/// The phone's on-lock `lock_id`, if it has one by that id.
async fn lock_row(
    db: &sqlx::SqlitePool,
    id: i64,
    lock_id: i64,
) -> Result<Option<LockNowRow>, sqlx::Error> {
    sqlx::query_as::<_, LockNowRow>("SELECT * FROM time_lock_now WHERE id = ? AND device_id = ?")
        .bind(lock_id)
        .bind(id)
        .fetch_optional(db)
        .await
}

/// Whether the lock is over at `now`: ended early, or past its end.
fn over(lock: &LockNowRow, now: chrono::DateTime<Utc>) -> bool {
    lock.ended_early_at.is_some() || lock.ends_at <= lock_now::db_time(now)
}

/// `POST /devices/{id}/lock-now/{lock_id}` (full auth): "Lagre ny tid" (§5.2). `add=30|60|day` or
/// `til=HH:MM` (wins when not empty), `seen` the end the page showed: another parent's change in
/// between is refused, not overwritten.
pub async fn change_lock(
    State(state): State<AppState>,
    Path((id, lock_id)): Path<(i64, i64)>,
    lang: Lang,
    Extension(CurrentAdmin(admin)): Extension<CurrentAdmin>,
    Form(form): Form<HashMap<String, String>>,
) -> Response {
    let lock = match lock_row(&state.db, id, lock_id).await {
        Ok(Some(lock)) => lock,
        Ok(None) => return crate::i18n::error(lang, StatusCode::NOT_FOUND, "error.no_such_lock"),
        Err(err) => return server_error(lang, err, "failed to read a turned-on rule"),
    };
    let rule = lock.rule_id.map(|r| r.to_string()).unwrap_or_default();
    let refuse = |code: &str| back(id, code, Some(("on", &rule)));
    let now = Utc::now();
    if over(&lock, now) {
        return back(id, "on_over", None);
    }
    let (Ok(tz), Some(date)) = (lock.zone.parse::<Tz>(), lock.date()) else {
        return server_error(
            lang,
            &lock.zone,
            "a turned-on rule's zone or date doesn't read",
        );
    };
    let now_minute = lock_now::minute_of(now.with_timezone(&tz).naive_local());
    let til = form.get("til").map(|t| t.trim()).unwrap_or("");
    let end = if !til.is_empty() {
        match lock_now::parse_til(til) {
            None => return refuse("til_invalid"),
            Some(0) => crate::time_rules::LAST_MINUTE,
            Some(m) if m <= now_minute || m <= lock.start_minute => return refuse("til_past"),
            Some(m) => m,
        }
    } else {
        match form.get("add").map(|a| a.trim()) {
            Some("day") => crate::time_rules::LAST_MINUTE,
            Some(add) => match add.parse::<i64>() {
                Ok(add) if lock_now::ADD_MINUTES.contains(&add) => {
                    match lock_now::extended(lock.end_minute, add) {
                        Some(end) => end,
                        None => return refuse("past_midnight"),
                    }
                }
                _ => return refuse("til_invalid"),
            },
            None => return refuse("til_invalid"),
        }
    };
    let ends_at = lock_now::db_time(lock_now::instant_of(tz, date, end));
    let seen = form.get("seen").map(|s| s.trim()).unwrap_or("");
    let changed = sqlx::query(
        "UPDATE time_lock_now SET end_minute = ?, ends_at = ?, changed_at = datetime('now'), \
         changed_by = ? WHERE id = ? AND device_id = ? AND ended_early_at IS NULL \
         AND ends_at > datetime('now') AND ends_at = ?",
    )
    .bind(end)
    .bind(&ends_at)
    .bind(&admin.username)
    .bind(lock_id)
    .bind(id)
    .bind(seen)
    .execute(&state.db)
    .await;
    match changed {
        Ok(done) if done.rows_affected() == 0 => match lock_row(&state.db, id, lock_id).await {
            Ok(Some(lock)) if over(&lock, Utc::now()) => back(id, "on_over", None),
            Ok(Some(_)) => refuse("changed_elsewhere"),
            Ok(None) => crate::i18n::error(lang, StatusCode::NOT_FOUND, "error.no_such_lock"),
            Err(err) => server_error(lang, err, "failed to read a turned-on rule"),
        },
        Ok(_) => {
            security::record_security_event(
                &state.db,
                "time_lock_now_changed",
                Some(&admin.username),
                None,
                Some(
                    security::Detail::words(
                        "security.detail.lock_now.changed",
                        &[("device", &id.to_string()), ("to", &clock(end))],
                    )
                    .rule("rule", &lock.rule_name)
                    .with_facts(format!("device {id}")),
                ),
            )
            .await;
            let _ = state.command_notify.send(id);
            back(id, "on_changed", None)
        }
        Err(err) => server_error(lang, err, "failed to change a turned-on rule"),
    }
}

/// `POST /devices/{id}/lock-now/{lock_id}/end` (full auth): "Avslutt nå" (§5.3). A lock already
/// over changes nothing and lands back; one this phone never had is a 404.
pub async fn end_lock(
    State(state): State<AppState>,
    Path((id, lock_id)): Path<(i64, i64)>,
    lang: Lang,
    Extension(CurrentAdmin(admin)): Extension<CurrentAdmin>,
) -> Response {
    let lock = match lock_row(&state.db, id, lock_id).await {
        Ok(Some(lock)) => lock,
        Ok(None) => return crate::i18n::error(lang, StatusCode::NOT_FOUND, "error.no_such_lock"),
        Err(err) => return server_error(lang, err, "failed to read a turned-on rule"),
    };
    let ended = sqlx::query(
        "UPDATE time_lock_now SET ended_early_at = datetime('now'), ended_by = ? \
         WHERE id = ? AND device_id = ? AND ended_early_at IS NULL AND ends_at > datetime('now')",
    )
    .bind(&admin.username)
    .bind(lock_id)
    .bind(id)
    .execute(&state.db)
    .await;
    match ended {
        Ok(done) if done.rows_affected() == 0 => back(id, "on_over", None),
        Ok(_) => {
            security::record_security_event(
                &state.db,
                "time_lock_now_ended",
                Some(&admin.username),
                None,
                Some(
                    security::Detail::words(
                        "security.detail.lock_now.ended",
                        &[("device", &id.to_string())],
                    )
                    .rule("rule", &lock.rule_name)
                    .with_facts(format!("device {id}")),
                ),
            )
            .await;
            let _ = state.command_notify.send(id);
            back(id, "on_ended", None)
        }
        Err(err) => server_error(lang, err, "failed to end a turned-on rule"),
    }
}
