//! Design 83 §4: the Calls page's questions about MMS addresses (`#mmsc`, right under "Meldinger som venter") and the
//! answered ones (the folded "Bildeadresser" at the foot), over `device_mmsc_hosts` - `mmsc_hosts::intake` makes the
//! rows from the phone's reports, a parent's answer here is the only thing that lets an address into the policy.

use axum::Extension;
use axum::extract::{Path, Query, State};
use axum::response::{Redirect, Response};
use chrono::{DateTime, Utc};

use super::calls::{db_error, not_found, saved};
use crate::AppState;
use crate::display_time::{Zone, parse_db_utc};
use crate::i18n::{Lang, t, t_params};
use crate::mmsc_hosts::{self, ALLOWED_MAX, DECLINED_MAX};
use crate::security::CurrentAdmin;

/// An open question; its anchor is `mmsc-<id>`.
pub struct Question {
    pub id: i64,
    /// "Telefonen vil hente bilder fra {host}. Tillat?"
    pub ask: String,
    /// The picture it is about: the contact and when.
    pub about: String,
    /// What Android's list says about the address for this SIM's operator.
    pub list: String,
    /// The address isn't the list's: allow only if the contact really sent a picture then.
    pub warn: Option<String>,
    /// The phone no longer reports the picture: the next one is fetched once the address is allowed.
    pub gone: bool,
    /// Eight addresses are allowed for the SIM already: said in place of "Tillat".
    pub full: bool,
}

/// An answered address in "Bildeadresser"; its anchor is `mmsc-<id>` too.
pub struct Decided {
    pub id: i64,
    pub host: String,
    /// "Telia · Tillatt 10. nov. av Mamma" / "Telia · Ikke tillatt".
    pub sub: String,
    /// Allowed ("Fjern"), else declined ("Tillat likevel", unless the SIM's eight are taken: `full`).
    pub allowed: bool,
    pub full: bool,
}

#[derive(Default)]
pub struct MmscPage {
    pub open: Vec<Question>,
    pub decided: Vec<Decided>,
}

#[derive(sqlx::FromRow)]
struct Row {
    id: i64,
    mccmnc: String,
    host: String,
    state: String,
    picture_from: String,
    picture_at: i64,
    decided_at: Option<String>,
    decided_by: Option<i64>,
}

/// The page's rows: the open questions, newest picture first, then the allowed and the declined addresses. The
/// operator is named as the phone's SIM names it, the contact as it is named now.
pub async fn load(
    db: &sqlx::SqlitePool,
    device_id: i64,
    zone: &Zone,
    now: DateTime<Utc>,
) -> Result<MmscPage, sqlx::Error> {
    let rows: Vec<Row> = sqlx::query_as(
        "SELECT id, mccmnc, host, state, picture_from, picture_at, decided_at, decided_by FROM device_mmsc_hosts \
         WHERE device_id = ? ORDER BY state = 'allowed' DESC, picture_at DESC, id DESC",
    )
    .bind(device_id)
    .fetch_all(db)
    .await?;
    if rows.is_empty() {
        return Ok(MmscPage::default());
    }
    let lang = zone.lang();
    let reported = mmsc_hosts::Reported::load(db, device_id).await?;
    let contacts = mmsc_hosts::contacts(db, device_id).await?;
    let parents = crate::parents::Names::load(db).await?;
    let full = |mccmnc: &str| {
        let allowed = rows
            .iter()
            .filter(|row| row.state == "allowed" && row.mccmnc == mccmnc);
        allowed.count() as i64 >= ALLOWED_MAX
    };
    let mut page = MmscPage::default();
    for row in &rows {
        let operator = reported.operator_name(&row.mccmnc);
        if row.state != "open" {
            let allowed = row.state == "allowed";
            let decided = row.decided_at.as_deref().and_then(parse_db_utc);
            let words = if allowed {
                let date = zone.date_of(
                    zone.local(decided.unwrap_or(now)).date_naive(),
                    zone.today(now),
                );
                // An account that is gone has no name to put after "by".
                match row.decided_by.and_then(|id| parents.of_id(id)) {
                    Some(parent) => t_params(
                        lang,
                        "device.calls.mmsc.allowed",
                        &[("date", &date), ("parent", parent)],
                    ),
                    None => t_params(lang, "device.calls.mmsc.allowed_on", &[("date", &date)]),
                }
            } else {
                t(lang, "device.calls.mmsc.declined").to_string()
            };
            page.decided.push(Decided {
                id: row.id,
                host: row.host.clone(),
                sub: format!("{operator} · {words}"),
                allowed,
                full: !allowed && full(&row.mccmnc),
            });
            continue;
        }
        let name = contacts
            .get(&row.picture_from)
            .map_or(row.picture_from.as_str(), String::as_str);
        let listed: Vec<&str> = mmsc_hosts::listed(&row.mccmnc)
            .map(|l| l.unasked.iter().chain(&l.hints).copied().collect())
            .unwrap_or_default();
        let is_listed = listed.contains(&row.host.as_str());
        page.open.push(Question {
            id: row.id,
            ask: t_params(lang, "device.calls.mmsc.ask", &[("host", &row.host)]),
            about: t_params(
                lang,
                "device.calls.mmsc.about",
                &[
                    ("name", name),
                    (
                        "time",
                        &zone.at(
                            DateTime::from_timestamp_millis(row.picture_at).unwrap_or_default(),
                            now,
                        ),
                    ),
                ],
            ),
            list: if is_listed {
                t_params(lang, "device.calls.mmsc.listed", &[("operator", operator)])
            } else if listed.is_empty() {
                t_params(
                    lang,
                    "device.calls.mmsc.unlisted_none",
                    &[("operator", operator)],
                )
            } else {
                t_params(
                    lang,
                    "device.calls.mmsc.unlisted",
                    &[("operator", operator), ("hosts", &listed.join(", "))],
                )
            },
            warn: (!is_listed).then(|| t_params(lang, "device.calls.mmsc.warn", &[("name", name)])),
            gone: !reported
                .mmsc_unlisted
                .iter()
                .any(|u| u.mccmnc == row.mccmnc && u.host == row.host),
            full: full(&row.mccmnc),
        });
    }
    Ok(page)
}

/// A phone's open questions, for the lines that count what waits for the parent.
pub async fn open_count(db: &sqlx::SqlitePool, device_id: i64) -> Result<i64, sqlx::Error> {
    sqlx::query_scalar(
        "SELECT COUNT(*) FROM device_mmsc_hosts WHERE device_id = ? AND state = 'open'",
    )
    .bind(device_id)
    .fetch_one(db)
    .await
}

/// What an answer did.
enum Answer {
    NoPhone,
    Stored,
    /// The row is gone or has this answer already.
    Gone,
    /// The SIM's eight allowed addresses are taken.
    Full,
}

/// One answer, in one transaction scoped to the phone: the row takes `to` (`allowed` from `open` or `declined` while
/// its SIM has room, `declined` from `open` or `allowed`) with who and when, and its `mmsc_host` event is read for
/// every parent. Past [DECLINED_MAX] the oldest other decline goes.
async fn answer(
    db: &sqlx::SqlitePool,
    id: i64,
    row: i64,
    to: &str,
    admin_id: i64,
) -> Result<Answer, sqlx::Error> {
    let mut tx = db.begin_with("BEGIN IMMEDIATE").await?;
    let device: bool =
        sqlx::query_scalar("SELECT EXISTS(SELECT 1 FROM device_policy WHERE device_id = ?)")
            .bind(id)
            .fetch_one(&mut *tx)
            .await?;
    if !device {
        return Ok(Answer::NoPhone);
    }
    let from: Option<(String, String)> = sqlx::query_as(
        "SELECT state, mccmnc FROM device_mmsc_hosts WHERE id = ? AND device_id = ?",
    )
    .bind(row)
    .bind(id)
    .fetch_optional(&mut *tx)
    .await?;
    let Some((from, mccmnc)) = from.filter(|(from, _)| from != to) else {
        return Ok(Answer::Gone);
    };
    if to == "allowed" {
        let allowed: i64 = sqlx::query_scalar(
            "SELECT COUNT(*) FROM device_mmsc_hosts WHERE device_id = ? AND mccmnc = ? AND state = 'allowed'",
        )
        .bind(id)
        .bind(&mccmnc)
        .fetch_one(&mut *tx)
        .await?;
        if allowed >= ALLOWED_MAX {
            return Ok(Answer::Full);
        }
    }
    sqlx::query(
        "UPDATE device_mmsc_hosts SET state = ?, decided_at = datetime('now'), decided_by = ? WHERE id = ?",
    )
    .bind(to)
    .bind(admin_id)
    .bind(row)
    .execute(&mut *tx)
    .await?;
    if to == "declined" {
        sqlx::query(
            "DELETE FROM device_mmsc_hosts WHERE device_id = ?1 AND state = 'declined' AND id != ?2 AND id NOT IN \
             (SELECT id FROM device_mmsc_hosts WHERE device_id = ?1 AND state = 'declined' AND id != ?2 \
              ORDER BY decided_at DESC, id DESC LIMIT ?3)",
        )
        .bind(id)
        .bind(row)
        .bind(DECLINED_MAX - 1)
        .execute(&mut *tx)
        .await?;
    }
    if from == "open" {
        sqlx::query(
            "INSERT OR IGNORE INTO parent_event_reads (admin_id, event_id) \
             SELECT a.id, e.id FROM admin_users a JOIN parent_events e \
             ON e.device_id = ? AND e.kind = 'mmsc_host' AND e.dedup_key = ?",
        )
        .bind(id)
        .bind(row.to_string())
        .execute(&mut *tx)
        .await?;
    }
    tx.commit().await?;
    Ok(Answer::Stored)
}

/// Where the form was: "Bildeadresser"'s forms post with `?decided=1`, a question's without.
#[derive(serde::Deserialize)]
pub struct Posted {
    decided: Option<String>,
}

/// Back from an answer: to the row, now in "Bildeadresser" drawn open (`?mmsc=1`), with the phone nudged - its next
/// policy has the address, or no longer has it. An answer that stored nothing lands on the notice of the group its
/// form was in (a save never jumps to the top).
fn answered(
    state: &AppState,
    lang: Lang,
    id: i64,
    row: i64,
    posted: Posted,
    result: Result<Answer, sqlx::Error>,
) -> Response {
    let notice = |code: &str| {
        crate::settings_log::refused(Redirect::to(&if posted.decided.is_some() {
            format!("/devices/{id}/calls?mmsc=1&notice={code}#mmsc-decided")
        } else {
            format!("/devices/{id}/calls?notice={code}#mmsc")
        }))
    };
    match result {
        Ok(Answer::NoPhone) => not_found(lang),
        Ok(Answer::Stored) => saved(state, id, format!("/devices/{id}/calls?mmsc=1#mmsc-{row}")),
        Ok(Answer::Gone) => notice("mmsc_gone"),
        Ok(Answer::Full) => notice("mmsc_full"),
        Err(err) => db_error(lang, id, err),
    }
}

/// "Tillat" on a question and "Tillat likevel" on a declined address: the entry joins this phone's row for that SIM
/// operator in `call_policy.mmsc_hosts`, and the phone fetches the waiting picture at its next policy.
pub async fn allow(
    State(state): State<AppState>,
    lang: Lang,
    Path((id, row)): Path<(i64, i64)>,
    Query(posted): Query<Posted>,
    Extension(CurrentAdmin(admin)): Extension<CurrentAdmin>,
) -> Response {
    let result = answer(&state.db, id, row, "allowed", admin.id).await;
    answered(&state, lang, id, row, posted, result)
}

/// "Ikke tillat" on a question and "Fjern" on an allowed address: never asked about again, and out of the next
/// policy (pictures fetched already stay on the phone).
pub async fn decline(
    State(state): State<AppState>,
    lang: Lang,
    Path((id, row)): Path<(i64, i64)>,
    Query(posted): Query<Posted>,
    Extension(CurrentAdmin(admin)): Extension<CurrentAdmin>,
) -> Response {
    let result = answer(&state.db, id, row, "declined", admin.id).await;
    answered(&state, lang, id, row, posted, result)
}

/// `?notice=` codes for the questions.
pub fn notice(lang: Lang, code: &str) -> Option<&'static str> {
    match code {
        "mmsc_gone" => Some(t(lang, "device.calls.mmsc.notice")),
        "mmsc_full" => Some(t(lang, "device.calls.mmsc.full")),
        _ => None,
    }
}
