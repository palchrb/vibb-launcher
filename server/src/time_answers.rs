//! The answer to the kid's request for more time (design 100 §14, revision 2). A request is open from the intake
//! until a parent answers it or the phone's day ends: the newest `extra_time_request` of the phone's day with no
//! `time_request_answers` row. The summary shows it as the answer card (`handlers::time_answer`); a parent's "+15 min"
//! / "+30 min" / "+1 t" or a fitting lift from Quick actions answers it `yes`, "Nei" `no`, a newer request of the same
//! day `superseded`. The phone learns the answer from its policy (`time_policy.answer`, `lifts[].by`/`at_ms`,
//! `time_policy.request_answers`; the shared contract `testdata/time_request.json`).

use chrono::{DateTime, Utc};
use sqlx::{SqliteConnection, SqlitePool};

use crate::display_time::Zone;
use crate::parent_events::{ExtraTimeParams, Kind, TimeAsk};
use crate::time_state::TimeReport;

/// The launcher's status capability (`time_request.json`'s `capability`): it shows a parent's "Nei" and the name. Read
/// only for the "Nei" flash's words (§14.4, §14.7).
pub const CAPABILITY: &str = "time_answer_v1";

/// The phone's newest report as `(reported_at, its time report)`; `None` before the first.
pub async fn latest_report(
    db: &SqlitePool,
    device_id: i64,
) -> Result<Option<(DateTime<Utc>, TimeReport)>, sqlx::Error> {
    let latest: Option<(String, String, Option<String>)> = sqlx::query_as(
        "SELECT reported_at, lock_reason, time_state_json FROM device_status \
         WHERE device_id = ? ORDER BY reported_at DESC, id DESC LIMIT 1",
    )
    .bind(device_id)
    .fetch_optional(db)
    .await?;
    Ok(latest.and_then(|(at, reason, json)| {
        crate::handlers::devices::time_report(&at, &reason, json.as_deref())
    }))
}

/// The start of the phone's today - the intake's day (qa-r13-rule-card-code Q-2): the zone its report names, else
/// Phone Setup's. The cap, `stale`, the open request, the policy's answer and the supersede all count this day.
pub fn day_start(report: Option<&TimeReport>, setup: &Zone, now: DateTime<Utc>) -> DateTime<Utc> {
    crate::handlers::device_api::phone_zone(report, setup).start_of_today(now)
}

/// [day_start] from the database: Phone Setup's zone and the phone's newest report.
pub async fn phone_day_start(
    db: &SqlitePool,
    device_id: i64,
    now: DateTime<Utc>,
) -> Result<DateTime<Utc>, sqlx::Error> {
    let setup = Zone::load(db, crate::i18n::Lang::default()).await;
    let report = latest_report(db, device_id).await?;
    Ok(day_start(report.as_ref().map(|(_, r)| r), &setup, now))
}

/// The phone's open request: its id (the event's `dedup_key`, the phone's own), when the kid asked, and where the phone
/// stood then.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct OpenRequest {
    pub id: String,
    pub at: DateTime<Utc>,
    pub params: Option<ExtraTimeParams>,
}

/// The open request: the newest request asked in the phone's day (from `day_start`) - the newest stored, so the one a
/// supersede left - while it has no answer row. One rule for every row (the migration answered the old ones, QA
/// R2-M4).
pub async fn open_request(
    conn: &mut SqliteConnection,
    device_id: i64,
    day_start: DateTime<Utc>,
) -> Result<Option<OpenRequest>, sqlx::Error> {
    let row: Option<(String, String, Option<String>)> = sqlx::query_as(
        "SELECT e.dedup_key, e.happened_at, e.params FROM parent_events e \
         WHERE e.id = (SELECT MAX(n.id) FROM parent_events n WHERE n.device_id = ?1 AND n.kind = ?2 \
                       AND datetime(n.happened_at) >= datetime(?3)) \
         AND NOT EXISTS (SELECT 1 FROM time_request_answers a \
                         WHERE a.device_id = e.device_id AND a.request_id = e.dedup_key)",
    )
    .bind(device_id)
    .bind(Kind::ExtraTimeRequest.wire())
    .bind(crate::push::db_time(day_start))
    .fetch_optional(&mut *conn)
    .await?;
    Ok(row.and_then(|(id, at, params)| {
        Some(OpenRequest {
            id,
            at: crate::display_time::parse_db_utc(&at)?,
            params: params.and_then(|p| serde_json::from_str(&p).ok()),
        })
    }))
}

/// Where a card's request stands when a parent answers it (§14.4, QA R2-L6).
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum Standing {
    /// The phone's open request: answer it.
    Open(Box<OpenRequest>),
    /// A parent answered it already (yes or no).
    Answered,
    /// A newer request of the day took its place (`superseded`, or no longer the newest).
    AskedAgain,
    /// Asked before the phone's today: no longer open.
    Gone,
    /// The phone never asked it.
    Unknown,
}

/// [Standing] of `request_id`, inside the caller's transaction.
pub async fn standing(
    conn: &mut SqliteConnection,
    device_id: i64,
    request_id: &str,
    day_start: DateTime<Utc>,
) -> Result<Standing, sqlx::Error> {
    let asked: Option<String> = sqlx::query_scalar(
        "SELECT happened_at FROM parent_events WHERE device_id = ? AND kind = ? AND dedup_key = ?",
    )
    .bind(device_id)
    .bind(Kind::ExtraTimeRequest.wire())
    .bind(request_id)
    .fetch_optional(&mut *conn)
    .await?;
    let Some(asked) = asked else {
        return Ok(Standing::Unknown);
    };
    let answer: Option<String> = sqlx::query_scalar(
        "SELECT answer FROM time_request_answers WHERE device_id = ? AND request_id = ?",
    )
    .bind(device_id)
    .bind(request_id)
    .fetch_optional(&mut *conn)
    .await?;
    match answer.as_deref() {
        Some("yes" | "no") => return Ok(Standing::Answered),
        Some(_) => return Ok(Standing::AskedAgain),
        None => {}
    }
    if crate::display_time::parse_db_utc(&asked).is_none_or(|at| at < day_start) {
        return Ok(Standing::Gone);
    }
    Ok(match open_request(conn, device_id, day_start).await? {
        Some(open) if open.id == request_id => Standing::Open(Box::new(open)),
        _ => Standing::AskedAgain,
    })
}

/// What can unlock the phone now (QA R2-M2, D9): more screen time, or a pause of the rules in force.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Need {
    Time,
    /// A rule locks; `active_rule` is the fresh report's rule, `None` when only the ask said "rule".
    Rules {
        active_rule: Option<i64>,
    },
}

/// [Need] decided at the answer (QA R2-M2): from the phone's newest fresh report (`report_is_fresh`) when there is one,
/// else from where the phone stood at the ask (`asked_state`, the event's `state`).
pub fn need(
    fresh: Option<&TimeReport>,
    asked_state: Option<&str>,
    zone: &Zone,
    now: DateTime<Utc>,
) -> Need {
    match fresh {
        Some(report) => match TimeAsk::of(report, zone, now) {
            TimeAsk::Rule(_) => Need::Rules {
                active_rule: report.active_rule_id,
            },
            _ => Need::Time,
        },
        None if asked_state == Some("rule") => Need::Rules { active_rule: None },
        None => Need::Time,
    }
}

/// A lift (`target`, `rule_id`) answers a request only when it can unlock what locks (QA R2-M2): screen time for a
/// spent budget; a pause of every rule, or of the very rule the fresh report names, for a rule.
pub fn fits(need: Need, target: &str, rule_id: Option<i64>) -> bool {
    match need {
        Need::Time => target == "budget",
        Need::Rules { active_rule } => {
            target == "rule" && (rule_id.is_none() || (rule_id.is_some() && rule_id == active_rule))
        }
    }
}

/// The phone's newest report when it can say what is true now (design 101 §4.1's `report_is_fresh`, in the Phone
/// Setup `zone`); else `None`.
pub async fn fresh_report(
    state: &crate::AppState,
    device: &crate::models::Device,
    zone: &Zone,
    now: DateTime<Utc>,
) -> Result<Option<TimeReport>, sqlx::Error> {
    let Some((reported_at, report)) = latest_report(&state.db, device.id).await? else {
        return Ok(None);
    };
    let connection = crate::handlers::devices::connection_of(state, device, now);
    Ok(
        crate::time_state::report_is_fresh(&report, &connection, reported_at, zone, now)
            .then_some(report),
    )
}

/// The answer as a parent gave it.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Given {
    /// A lift: its id and minutes.
    Yes {
        lift_id: i64,
        minutes: i64,
    },
    No,
}

/// Writes the request's answer row and the answer into its event's `params` (QA R2-L7: every read site words the
/// event from its params as before), in the caller's transaction. `false` when the request had an answer already (the
/// caller rolls back: two parents never both give). `by_name` is the parent's display name (the phone's, D11: none,
/// no name), `pwa_name` the parents' own word for them (display name, else login) - a snapshot in the params, like
/// `by_name`: the inbox's words are made from the params alone.
pub async fn write_answer(
    conn: &mut SqliteConnection,
    device_id: i64,
    request_id: &str,
    given: Given,
    admin: &crate::models::AdminUser,
) -> Result<bool, sqlx::Error> {
    let by_name = admin
        .display_name
        .as_deref()
        .map(str::trim)
        .filter(|n| !n.is_empty());
    let (answer, lift_id, minutes) = match given {
        Given::Yes { lift_id, minutes } => ("yes", Some(lift_id), Some(minutes)),
        Given::No => ("no", None, None),
    };
    let written = sqlx::query(
        "INSERT INTO time_request_answers (device_id, request_id, answer, lift_id, answered_by, by_name) \
         VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING",
    )
    .bind(device_id)
    .bind(request_id)
    .bind(answer)
    .bind(lift_id)
    .bind(admin.id)
    .bind(by_name)
    .execute(&mut *conn)
    .await?;
    if written.rows_affected() == 0 {
        return Ok(false);
    }
    let words = match minutes {
        Some(minutes) => sqlx::query(
            "UPDATE parent_events SET params = json_set(COALESCE(params, '{}'), \
             '$.answer', ?, '$.by', ?, '$.minutes', ?) WHERE device_id = ? AND kind = ? AND dedup_key = ?",
        )
        .bind(answer)
        .bind(admin.name())
        .bind(minutes),
        None => sqlx::query(
            "UPDATE parent_events SET params = json_set(COALESCE(params, '{}'), '$.answer', ?, '$.by', ?) \
             WHERE device_id = ? AND kind = ? AND dedup_key = ?",
        )
        .bind(answer)
        .bind(admin.name()),
    };
    words
        .bind(device_id)
        .bind(Kind::ExtraTimeRequest.wire())
        .bind(request_id)
        .execute(&mut *conn)
        .await?;
    Ok(true)
}

/// A lift written in the caller's transaction answers the phone's open request when it fits (QA R2-M2): `need` is
/// decided before the transaction from the fresh report (`None`: no fresh report - the request's own state decides).
/// `true` when it answered one.
#[allow(clippy::too_many_arguments)]
pub async fn answer_with_lift(
    conn: &mut SqliteConnection,
    device_id: i64,
    day_start: DateTime<Utc>,
    fresh: Option<&TimeReport>,
    zone: &Zone,
    now: DateTime<Utc>,
    lift: (i64, &str, Option<i64>, i64),
    admin: &crate::models::AdminUser,
) -> Result<bool, sqlx::Error> {
    let (lift_id, target, rule_id, minutes) = lift;
    let Some(open) = open_request(conn, device_id, day_start).await? else {
        return Ok(false);
    };
    let asked = open.params.as_ref().map(|p| p.state.as_str());
    let mut need = need(fresh, asked, zone, now);
    // Code QA L7: a rule turned on now (design 93) is in force as its copy, a negative id; a pause of the rule it
    // copies lifts the copy too (its twin), so it answers a request asked under the copy.
    if let Need::Rules {
        active_rule: Some(copy),
    } = need
        && copy < 0
    {
        need = Need::Rules {
            active_rule: copied_rule(conn, device_id, copy).await?,
        };
    }
    if !fits(need, target, rule_id) {
        return Ok(false);
    }
    write_answer(
        conn,
        device_id,
        &open.id,
        Given::Yes { lift_id, minutes },
        admin,
    )
    .await
}

/// The rule a turned-on rule's copy (`copy_id`, the negative id the phone runs it as: `-(time_lock_now.id)`) copies;
/// `None` for a copy of a rule deleted since, or one this phone never had.
pub async fn copied_rule(
    conn: &mut SqliteConnection,
    device_id: i64,
    copy_id: i64,
) -> Result<Option<i64>, sqlx::Error> {
    let rule: Option<Option<i64>> =
        sqlx::query_scalar("SELECT rule_id FROM time_lock_now WHERE id = ? AND device_id = ?")
            .bind(-copy_id)
            .bind(device_id)
            .fetch_optional(&mut *conn)
            .await?;
    Ok(rule.flatten())
}

/// A request taken at intake closes the phone's other unanswered requests of the day (`superseded`, §14.4, QA R2-L6):
/// called only after the new one was stored, in the intake's transaction. An older launcher asks again after its 30
/// minutes; the card then shows the newest.
pub async fn supersede(
    conn: &mut SqliteConnection,
    device_id: i64,
    request_id: &str,
    day_start: DateTime<Utc>,
) -> Result<(), sqlx::Error> {
    sqlx::query(
        "INSERT OR IGNORE INTO time_request_answers (device_id, request_id, answer) \
         SELECT e.device_id, e.dedup_key, 'superseded' FROM parent_events e \
         WHERE e.device_id = ? AND e.kind = ? AND e.dedup_key != ? \
         AND datetime(e.happened_at) >= datetime(?) \
         AND NOT EXISTS (SELECT 1 FROM time_request_answers a \
                         WHERE a.device_id = e.device_id AND a.request_id = e.dedup_key)",
    )
    .bind(device_id)
    .bind(Kind::ExtraTimeRequest.wire())
    .bind(request_id)
    .bind(crate::push::db_time(day_start))
    .execute(&mut *conn)
    .await?;
    Ok(())
}

/// The policy's `time_policy.answer` (§14.2): the newest answered request (yes or no, never `superseded`) asked in
/// the phone's day (QA R2-L5: a request of yesterday answered after midnight is not today's), with the lift's minutes
/// for a yes (QA R2-L1: the note never depends on the lift's delivery). Code QA L8: a yes whose lift a parent ended
/// early ("End now" on D9's pause) names no lift and no minutes - the phone then says nothing of time given (no
/// "Mamma ga deg 30 min" for time taken back) and the kid may ask again; so does a backfilled rule pause (0149).
pub async fn policy_answer(
    db: &SqlitePool,
    device_id: i64,
    day_start: DateTime<Utc>,
) -> Result<Option<crate::time_rules::PolicyAnswer>, sqlx::Error> {
    type Row = (
        String,
        String,
        Option<String>,
        i64,
        Option<i64>,
        Option<i64>,
    );
    let row: Option<Row> = sqlx::query_as(
        "SELECT a.request_id, a.answer, a.by_name, \
                CAST(strftime('%s', a.answered_at) AS INTEGER) * 1000, \
                CASE WHEN l.ended_early_at IS NULL THEN a.lift_id END, \
                CASE WHEN l.ended_early_at IS NULL THEN l.minutes END \
         FROM time_request_answers a \
         JOIN parent_events e ON e.device_id = a.device_id AND e.kind = ? AND e.dedup_key = a.request_id \
         LEFT JOIN time_lifts l ON l.id = a.lift_id \
         WHERE a.device_id = ? AND a.answer IN ('yes', 'no') AND datetime(e.happened_at) >= datetime(?) \
         ORDER BY datetime(a.answered_at) DESC, e.id DESC LIMIT 1",
    )
    .bind(Kind::ExtraTimeRequest.wire())
    .bind(device_id)
    .bind(crate::push::db_time(day_start))
    .fetch_optional(db)
    .await?;
    Ok(
        row.map(|(request_id, answer, by, at_ms, lift_id, minutes)| {
            crate::time_rules::PolicyAnswer {
                request_id,
                by: by.filter(|b| !b.trim().is_empty()),
                at_ms,
                lift_id: lift_id.filter(|_| answer == "yes"),
                minutes: minutes.filter(|_| answer == "yes"),
                answer,
            }
        }),
    )
}

/// Who answered `request` (the stale card's own, code QA L6), else the phone's newest answered request (a link
/// without it): the parents' own word for them - the display name, else the login; the name at the answer for a
/// parent gone since; "" for none.
pub async fn last_answerer(
    db: &SqlitePool,
    device_id: i64,
    request: Option<&str>,
) -> Result<String, sqlx::Error> {
    let name: Option<Option<String>> = sqlx::query_scalar(
        "SELECT COALESCE(NULLIF(TRIM(u.display_name), ''), u.username, a.by_name) \
         FROM time_request_answers a LEFT JOIN admin_users u ON u.id = a.answered_by \
         WHERE a.device_id = ? AND a.answer IN ('yes', 'no') AND (? IS NULL OR a.request_id = ?) \
         ORDER BY datetime(a.answered_at) DESC, a.rowid DESC LIMIT 1",
    )
    .bind(device_id)
    .bind(request)
    .bind(request)
    .fetch_optional(db)
    .await?;
    Ok(name.flatten().unwrap_or_default())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_lift_fits_only_what_locks() {
        assert!(fits(Need::Time, "budget", None));
        assert!(
            !fits(Need::Time, "rule", None),
            "a rule pause doesn't give time"
        );
        let bedtime = Need::Rules {
            active_rule: Some(4),
        };
        assert!(fits(bedtime, "rule", None), "every rule");
        assert!(fits(bedtime, "rule", Some(4)));
        assert!(!fits(bedtime, "rule", Some(5)), "another rule");
        assert!(!fits(bedtime, "budget", None), "time doesn't open a rule");
        let unknown = Need::Rules { active_rule: None };
        assert!(fits(unknown, "rule", None));
        assert!(!fits(unknown, "rule", Some(4)));
    }

    #[test]
    fn the_need_is_the_fresh_reports_else_the_asks() {
        let zone = Zone::from_setting("Europe/Oslo");
        let now = Utc::now();
        let bedtime = TimeReport {
            lock_reason: "BEDTIME".into(),
            active_rule_id: Some(4),
            ..Default::default()
        };
        assert_eq!(
            need(Some(&bedtime), Some("used_up"), &zone, now),
            Need::Rules {
                active_rule: Some(4)
            },
            "the report at the answer wins"
        );
        let spent = TimeReport {
            lock_reason: "SCREEN_TIME".into(),
            ..Default::default()
        };
        assert_eq!(need(Some(&spent), Some("rule"), &zone, now), Need::Time);
        assert_eq!(
            need(None, Some("rule"), &zone, now),
            Need::Rules { active_rule: None }
        );
        assert_eq!(need(None, Some("used_up"), &zone, now), Need::Time);
        assert_eq!(need(None, None, &zone, now), Need::Time);
    }
}
