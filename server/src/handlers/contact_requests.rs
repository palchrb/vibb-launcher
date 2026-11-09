//! Design 77 §14 (step 6, `docs/design/77-sms-mms-client.md`): the kid asks for a new contact from the phone book's
//! "+", and a parent decides on the Calls page. It follows design r13's time request (`device_api::time_request`):
//! - `POST /api/devices/contact-request` ([intake]): `{id, name, number, matrix_id?, asked_at_ms}`
//!   (`testdata/contact_request.json`, shared with the launcher), checked by [check] - the name cleaned
//!   (`calendar::clean`, at most 40 characters), the number normalised again (`phone::normalize`) and never an
//!   emergency number, the Matrix ID by `valid_matrix_id` - and answered `accepted`, or not with `capped` (three a day
//!   in the phone's own day, or five open), `invalid` or `known` (the number is a contact on this phone, or another
//!   open request has it). One `BEGIN IMMEDIATE` transaction; a retried id is one row and one event.
//! - The policy's `call_policy.contact_requests` ([policy_rows]): `[{id, state}]` of the last 7 days, `open`,
//!   `declined` or `added`.
//! - The Calls page's "Forespørsler" ("Requests") card ([load]), each row `#request-<id>` with its number's kind,
//!   "Legg til…" - Add Contact prefilled ([prefill]), the kid's Matrix ID behind its own unticked "Bruk Matrix-ID-en"
//!   (QA R2-M1) - and "Avslå" ([decline]). Nothing is added by the request itself: the parent's Add Contact with the
//!   number marks it `added` ([mark_added]).

use axum::extract::{Path, State};
use axum::http::StatusCode;
use axum::response::{IntoResponse, Redirect, Response};
use axum::{Extension, Json};
use chrono::{DateTime, Utc};

use super::calls::{db_error, not_found, saved};
use crate::AppState;
use crate::display_time::Zone;
use crate::emergency;
use crate::i18n::{Lang, t};
use crate::phone;
use crate::security::AuthedDevice;

/// `POST /api/devices/contact-request` takes at most this much (§14).
pub const BODY_LIMIT: usize = 1024;
/// A name's length after cleaning, in characters.
pub const NAME_MAX: usize = 40;
/// Requests a phone may make in its own day (§14, as r13's three).
pub const REQUESTS_A_DAY: i64 = 3;
/// Requests a phone may have open at once.
pub const OPEN_MAX: i64 = 5;
/// The policy and the card carry a request this many days.
pub const SHOWN_DAYS: i64 = 7;
/// Retention keeps a request this many days.
pub const KEEP_DAYS: i64 = 30;
const DAY_MS: i64 = 86_400_000;

/// The phone's request (`contact_request.json`'s `request`).
#[derive(Debug, serde::Deserialize)]
pub struct Body {
    pub id: String,
    pub name: String,
    pub number: String,
    #[serde(default)]
    pub matrix_id: Option<String>,
    pub asked_at_ms: i64,
}

/// The answer (`contact_request.json`'s `answers`).
#[derive(Debug, PartialEq, Eq, serde::Serialize)]
pub struct Answer {
    pub accepted: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub reason: Option<&'static str>,
}

impl Answer {
    pub const ACCEPTED: Answer = Answer {
        accepted: true,
        reason: None,
    };
    pub const CAPPED: Answer = Answer {
        accepted: false,
        reason: Some("capped"),
    };
    pub const INVALID: Answer = Answer {
        accepted: false,
        reason: Some("invalid"),
    };
    pub const KNOWN: Answer = Answer {
        accepted: false,
        reason: Some("known"),
    };
}

/// A request as the intake keeps it.
#[derive(Debug, PartialEq, Eq)]
pub struct Checked {
    pub name: String,
    pub number: String,
    pub matrix_id: Option<String>,
}

/// §14's checks (QA R2-L5), `contact_request.json`'s `valid` and `invalid` rows: the name cleaned to 1-40
/// characters, the number normalised with the default country code `cc` and not an emergency number (anyone can be
/// one, and it is never a contact to ask for), an empty Matrix ID none and any other one a Matrix ID. `None` is
/// `invalid`.
pub fn check(name: &str, number: &str, matrix_id: Option<&str>, cc: &str) -> Option<Checked> {
    let name = crate::calendar::clean(name, NAME_MAX + 1);
    if name.is_empty() || name.chars().count() > NAME_MAX {
        return None;
    }
    let number = phone::normalize(number, cc).ok()?;
    if emergency::is_emergency(&number, cc) {
        return None;
    }
    let matrix_id = match matrix_id.map(str::trim) {
        None | Some("") => None,
        Some(id) if super::calls::valid_matrix_id(id) => Some(id.to_string()),
        Some(_) => return None,
    };
    Some(Checked {
        name,
        number,
        matrix_id,
    })
}

/// The start of the phone's own day (as r13's cap): the zone its last report names, else Phone Setup's.
async fn start_of_phone_day(
    db: &sqlx::SqlitePool,
    device_id: i64,
    setup: &Zone,
    now: DateTime<Utc>,
) -> Result<DateTime<Utc>, sqlx::Error> {
    let latest: Option<(String, String, Option<String>)> = sqlx::query_as(
        "SELECT reported_at, lock_reason, time_state_json FROM device_status \
         WHERE device_id = ? ORDER BY reported_at DESC, id DESC LIMIT 1",
    )
    .bind(device_id)
    .fetch_optional(db)
    .await?;
    let report = latest.and_then(|(at, reason, json)| {
        crate::handlers::devices::time_report(&at, &reason, json.as_deref())
    });
    let zone = super::device_api::phone_zone(report.as_ref().map(|(_, r)| r), setup);
    Ok(zone.start_of_today(now))
}

/// `POST /api/devices/contact-request` (design 77 §14): the kid's "Send til mamma og pappa". 200 with [Answer], 400
/// for a body that doesn't parse or an id that isn't 16-64 lowercase hex, 413 above [BODY_LIMIT], 500 on a database
/// error (the phone keeps its request and retries). A future `asked_at` counts as now; one older than
/// [SHOWN_DAYS] is `invalid` (the phone has dropped it). In one `BEGIN IMMEDIATE` transaction: a stored id answers
/// `accepted` again (a retry: no second row or event); a number already on the phone or in another open request is
/// `known`; past [REQUESTS_A_DAY] received in the phone's own day, or [OPEN_MAX] open, `capped` (one log line); else
/// the row and the event `contact_request` keyed by the id, pushed within its budget to every parent with "Asks for a
/// new contact" on.
pub async fn intake(
    State(state): State<AppState>,
    Extension(AuthedDevice(device)): Extension<AuthedDevice>,
    body: axum::body::Bytes,
) -> Response {
    let Ok(request) = serde_json::from_slice::<Body>(&body) else {
        return StatusCode::BAD_REQUEST.into_response();
    };
    if !super::device_api::time_request_id_ok(&request.id) {
        return StatusCode::BAD_REQUEST.into_response();
    }
    let Some(asked) = DateTime::from_timestamp_millis(request.asked_at_ms) else {
        return StatusCode::BAD_REQUEST.into_response();
    };
    let Some(phone) = crate::parent_events::Phone::load(&state.db, device.id).await else {
        return StatusCode::INTERNAL_SERVER_ERROR.into_response();
    };
    let now = phone.now;
    let asked = asked.min(now);
    let cc: String =
        match sqlx::query_scalar("SELECT default_country_code FROM call_settings WHERE id = 1")
            .fetch_one(&state.db)
            .await
        {
            Ok(cc) => cc,
            Err(err) => {
                tracing::error!(device_id = device.id, %err, "couldn't read the country code");
                return StatusCode::INTERNAL_SERVER_ERROR.into_response();
            }
        };
    let Some(checked) = check(
        &request.name,
        &request.number,
        request.matrix_id.as_deref(),
        &cc,
    ) else {
        return Json(Answer::INVALID).into_response();
    };
    if now - asked > chrono::Duration::days(SHOWN_DAYS) {
        return Json(Answer::INVALID).into_response();
    }
    let start_of_day = match start_of_phone_day(&state.db, device.id, &phone.zone, now).await {
        Ok(start) => start,
        Err(err) => {
            tracing::error!(device_id = device.id, %err, "couldn't read the last report");
            return StatusCode::INTERNAL_SERVER_ERROR.into_response();
        }
    };
    let result = async {
        let mut tx = state.db.begin_with("BEGIN IMMEDIATE").await?;
        let (stored, known, today, open): (bool, bool, i64, i64) = sqlx::query_as(
            "SELECT \
               EXISTS(SELECT 1 FROM device_contact_requests WHERE device_id = ?1 AND id = ?2), \
               EXISTS(SELECT 1 FROM device_contacts dc JOIN contacts c ON c.id = dc.contact_id \
                      WHERE dc.device_id = ?1 AND c.phone_number = ?3) \
               OR EXISTS(SELECT 1 FROM device_contact_requests WHERE device_id = ?1 AND state = 'open' \
                         AND number = ?3), \
               (SELECT COUNT(*) FROM device_contact_requests WHERE device_id = ?1 AND received_at >= ?4), \
               (SELECT COUNT(*) FROM device_contact_requests WHERE device_id = ?1 AND state = 'open')",
        )
        .bind(device.id)
        .bind(&request.id)
        .bind(&checked.number)
        .bind(start_of_day.timestamp_millis())
        .fetch_one(&mut *tx)
        .await?;
        let answer = if stored {
            Answer::ACCEPTED
        } else if known {
            Answer::KNOWN
        } else if today >= REQUESTS_A_DAY || open >= OPEN_MAX {
            Answer::CAPPED
        } else {
            sqlx::query(
                "INSERT INTO device_contact_requests \
                 (device_id, id, name, number, matrix_id, asked_at, received_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
            )
            .bind(device.id)
            .bind(&request.id)
            .bind(&checked.name)
            .bind(&checked.number)
            .bind(&checked.matrix_id)
            .bind(asked.timestamp_millis())
            .bind(now.timestamp_millis())
            .execute(&mut *tx)
            .await?;
            let event = crate::parent_events::contact_request(
                device.id,
                &phone.name,
                &request.id,
                &checked.name,
                &checked.number,
                asked,
            );
            let raised = crate::parent_events::raise(&mut tx, &event)
                .await?
                .is_some();
            tx.commit().await?;
            return Ok::<_, sqlx::Error>((Answer::ACCEPTED, raised));
        };
        tx.rollback().await?;
        Ok((answer, false))
    };
    match result.await {
        Ok((answer, raised)) => {
            if answer == Answer::CAPPED {
                tracing::info!(device_id = device.id, "a contact request over the cap");
            }
            if raised {
                state.push.wake();
            }
            Json(answer).into_response()
        }
        Err(err) => {
            tracing::error!(device_id = device.id, %err, "couldn't store a contact request");
            StatusCode::INTERNAL_SERVER_ERROR.into_response()
        }
    }
}

/// `call_policy.contact_requests`'s row (the launcher's `PolicyContactRequest`).
#[derive(serde::Serialize, Clone, Debug, PartialEq, Eq, sqlx::FromRow)]
pub struct PolicyRow {
    pub id: String,
    pub state: String,
}

/// The requests the phone hears about (§14): the last [SHOWN_DAYS]' rows, newest first, at most 50 - `open`,
/// `declined` (the phone shows "Ikke lagt til" once) or `added` (the contact itself arrives; the row only lets the
/// phone forget a request the parent added under another number).
pub async fn policy_rows(
    db: &sqlx::SqlitePool,
    device_id: i64,
) -> Result<Vec<PolicyRow>, sqlx::Error> {
    sqlx::query_as(
        "SELECT id, state FROM device_contact_requests WHERE device_id = ? AND received_at > ? \
         ORDER BY received_at DESC, id LIMIT 50",
    )
    .bind(device_id)
    .bind(Utc::now().timestamp_millis() - SHOWN_DAYS * DAY_MS)
    .fetch_all(db)
    .await
}

/// One row of the "Forespørsler" card.
pub struct RequestView {
    /// The phone's id; the row's anchor is `request-<id>`.
    pub id: String,
    pub name: String,
    pub number: String,
    /// "Kortnummer - kan koste penger" / "Nødnummer" (QA R2-L5).
    pub mark: Option<&'static str>,
    /// When the kid asked.
    pub when: String,
    /// The kid's suggested Matrix ID.
    pub matrix_id: Option<String>,
    /// Still waiting for a decision: "Legg til…" and "Avslå".
    pub open: bool,
    /// "Legg til…" is offered (never for an emergency number).
    pub can_add: bool,
    /// A decided row's line: "Lagt til" / "Avslått".
    pub status: Option<&'static str>,
}

/// The number's mark on the card: an emergency number (stored before the country code changed, say) or a short one.
fn mark(lang: Lang, number: &str, cc: &str) -> Option<&'static str> {
    if emergency::is_emergency(number, cc) {
        Some(t(lang, "device.requests.emergency"))
    } else if !number.starts_with('+') {
        Some(t(lang, "device.requests.short"))
    } else {
        None
    }
}

/// A stored request as the card and the prefill read it.
#[derive(sqlx::FromRow)]
struct Row {
    id: String,
    name: String,
    number: String,
    matrix_id: Option<String>,
    asked_at: i64,
    state: String,
}

/// The card's rows: the last [SHOWN_DAYS]' requests, the open ones first, newest first.
pub async fn load(
    db: &sqlx::SqlitePool,
    device_id: i64,
    zone: &Zone,
    now: DateTime<Utc>,
    cc: &str,
) -> Result<Vec<RequestView>, sqlx::Error> {
    let lang = zone.lang();
    let rows: Vec<Row> = sqlx::query_as(
        "SELECT id, name, number, matrix_id, asked_at, state FROM device_contact_requests \
         WHERE device_id = ? AND received_at > ? ORDER BY state = 'open' DESC, received_at DESC, id LIMIT 50",
    )
    .bind(device_id)
    .bind(now.timestamp_millis() - SHOWN_DAYS * DAY_MS)
    .fetch_all(db)
    .await?;
    Ok(rows
        .into_iter()
        .map(|row| {
            let open = row.state == "open";
            RequestView {
                mark: mark(lang, &row.number, cc),
                can_add: open && !emergency::is_emergency(&row.number, cc),
                when: zone.when(
                    DateTime::from_timestamp_millis(row.asked_at).unwrap_or_default(),
                    now,
                ),
                status: match row.state.as_str() {
                    "added" => Some(t(lang, "device.requests.added")),
                    "declined" => Some(t(lang, "device.requests.declined")),
                    _ => None,
                },
                open,
                id: row.id,
                name: row.name,
                number: row.number,
                matrix_id: row.matrix_id,
            }
        })
        .collect())
}

/// What "Legg til…" puts into Add Contact: an open request of this phone.
pub struct Prefill {
    pub id: String,
    pub name: String,
    pub number: String,
    pub matrix_id: Option<String>,
}

/// The open request `id` of the phone, for Add Contact's prefill; `None` when it isn't one (decided, gone, another
/// phone's, or not an id at all).
pub async fn prefill(
    db: &sqlx::SqlitePool,
    device_id: i64,
    id: &str,
) -> Result<Option<Prefill>, sqlx::Error> {
    if !super::device_api::time_request_id_ok(id) {
        return Ok(None);
    }
    let row: Option<Row> = sqlx::query_as(
        "SELECT id, name, number, matrix_id, asked_at, state FROM device_contact_requests \
         WHERE device_id = ? AND id = ? AND state = 'open'",
    )
    .bind(device_id)
    .bind(id)
    .fetch_optional(db)
    .await?;
    Ok(row.map(|row| Prefill {
        id: row.id,
        name: row.name,
        number: row.number,
        matrix_id: row.matrix_id,
    }))
}

/// Every parent has read the requests `ids` name (a decision settles them, as `calls_sms`'s held rows).
async fn mark_read(
    tx: &mut sqlx::SqliteConnection,
    device_id: i64,
    ids: &[String],
) -> Result<(), sqlx::Error> {
    sqlx::query(
        "INSERT OR IGNORE INTO parent_event_reads (admin_id, event_id) \
         SELECT a.id, e.id FROM admin_users a JOIN parent_events e \
         ON e.device_id = ? AND e.kind = 'contact_request' AND e.dedup_key IN (SELECT value FROM json_each(?))",
    )
    .bind(device_id)
    .bind(serde_json::to_string(ids).expect("strings serialize"))
    .execute(&mut *tx)
    .await?;
    Ok(())
}

/// Add Contact saved `number` on the phone (any path, in its transaction): every open request for it is `added`, and
/// so is the request "Legg til…" came from (`request`), whatever number the parent saved. Returns the stored Matrix ID
/// of `request` when it was still open - the caller saves it only when the parent ticked "Bruk Matrix-ID-en".
pub async fn mark_added(
    tx: &mut sqlx::SqliteConnection,
    device_id: i64,
    number: &str,
    request: Option<&str>,
) -> Result<Option<String>, sqlx::Error> {
    let matrix_id: Option<Option<String>> = match request {
        Some(id) => {
            sqlx::query_scalar(
                "SELECT matrix_id FROM device_contact_requests WHERE device_id = ? AND id = ? AND state = 'open'",
            )
            .bind(device_id)
            .bind(id)
            .fetch_optional(&mut *tx)
            .await?
        }
        None => None,
    };
    let ids: Vec<String> = sqlx::query_scalar(
        "UPDATE device_contact_requests SET state = 'added', decided_at = datetime('now') \
         WHERE device_id = ? AND state = 'open' AND (number = ? OR id = ?) RETURNING id",
    )
    .bind(device_id)
    .bind(number)
    .bind(request.unwrap_or(""))
    .fetch_all(&mut *tx)
    .await?;
    if !ids.is_empty() {
        mark_read(tx, device_id, &ids).await?;
    }
    Ok(matrix_id.flatten())
}

/// "Avslå" ("Decline", §14): an open request becomes `declined` - the phone shows "Ikke lagt til" once - and its
/// event is read for every parent. One transaction scoped to the phone; back to the row, which then reads "Avslått".
/// A request no longer open lands on the card's notice.
pub async fn decline(
    State(state): State<AppState>,
    lang: Lang,
    Path((id, request)): Path<(i64, String)>,
) -> Response {
    let result = async {
        let mut tx = state.db.begin().await?;
        let device: bool =
            sqlx::query_scalar("SELECT EXISTS(SELECT 1 FROM device_policy WHERE device_id = ?)")
                .bind(id)
                .fetch_one(&mut *tx)
                .await?;
        if !device {
            return Ok::<_, sqlx::Error>(None);
        }
        let declined = sqlx::query(
            "UPDATE device_contact_requests SET state = 'declined', decided_at = datetime('now') \
             WHERE device_id = ? AND id = ? AND state = 'open'",
        )
        .bind(id)
        .bind(&request)
        .execute(&mut *tx)
        .await?
        .rows_affected();
        if declined > 0 {
            mark_read(&mut tx, id, std::slice::from_ref(&request)).await?;
        }
        tx.commit().await?;
        Ok(Some(declined > 0))
    }
    .await;
    match result {
        Ok(None) => not_found(lang),
        Ok(Some(true)) => saved(&state, id, format!("/devices/{id}/calls#request-{request}")),
        Ok(Some(false)) => crate::settings_log::refused(Redirect::to(&format!(
            "/devices/{id}/calls?notice=request_gone#requests"
        ))),
        Err(err) => db_error(lang, id, err),
    }
}

/// `?notice=` codes for the card.
pub fn notice(lang: Lang, code: &str) -> Option<&'static str> {
    match code {
        "request_gone" => Some(t(lang, "device.requests.gone")),
        _ => None,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const CONTRACT: &str = include_str!("../../testdata/contact_request.json");

    /// §14's checks on the shared vectors: every `valid` row passes with its cleaned name and normalised number, every
    /// `invalid` one is refused - the launcher's `ContactRequestsTest` runs the same rows.
    #[test]
    fn the_shared_vectors() {
        let contract: serde_json::Value = serde_json::from_str(CONTRACT).unwrap();
        for row in contract["valid"].as_array().unwrap() {
            let checked = check(
                row["name"].as_str().unwrap(),
                row["number"].as_str().unwrap(),
                row["matrix_id"].as_str(),
                "47",
            )
            .unwrap_or_else(|| panic!("{row}"));
            assert_eq!(checked.name, row["cleaned"].as_str().unwrap(), "{row}");
            assert_eq!(checked.number, row["normalized"].as_str().unwrap(), "{row}");
            assert_eq!(
                checked.matrix_id.as_deref(),
                row["matrix_id"].as_str(),
                "{row}"
            );
        }
        for row in contract["invalid"].as_array().unwrap() {
            assert_eq!(
                check(
                    row["name"].as_str().unwrap(),
                    row["number"].as_str().unwrap(),
                    row["matrix_id"].as_str(),
                    "47",
                ),
                None,
                "{row}"
            );
        }
    }

    #[test]
    fn the_answers_are_the_contracts() {
        let contract: serde_json::Value = serde_json::from_str(CONTRACT).unwrap();
        let ours: Vec<serde_json::Value> = [
            Answer::ACCEPTED,
            Answer::CAPPED,
            Answer::INVALID,
            Answer::KNOWN,
        ]
        .iter()
        .map(|a| serde_json::to_value(a).unwrap())
        .collect();
        assert_eq!(serde_json::Value::Array(ours), contract["answers"]);
    }
}
