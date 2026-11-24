//! Design 129b: a new phone's enrolment requires a proven hardware key (`docs/design/129b-enrolment-requires-a-key.md`,
//! `docs/notes/server/device-key.md`).
//!
//! - A try whose chain is refused, or with no key at all (the phone's `key_error`), never enrols: it answers `409
//!   {state: "waiting", reason, number, retry_after_s}`, keeps the code live and stores **one row per attempt** - a
//!   random secret the phone makes once per setup code and sends with every try ([waiting_try]).
//! - The PWA shows the orange line with "Sett opp uten maskinvarenøkkel…" (`partials/key_wait.html`, [Section]); the
//!   parent types the six-digit number off the phone and re-authenticates ([confirm]): that one attempt is allowed.
//! - The phone's next try with the allowed attempt enrols on the bearer (`key: {state: "exempt"}`), and About This
//!   Phone reads "Uten maskinvarenøkkel", quiet. A try whose chain passes always wins and enrols with the key.

use axum::extract::{Path, State};
use axum::http::StatusCode;
use axum::response::{IntoResponse, Redirect, Response};
use sha2::{Digest, Sha256};

use crate::AppState;
use crate::i18n::{Lang, t, t_count, t_params};

/// At most this many attempt rows per code (D4); a ninth attempt waits with no row.
pub const ROWS_PER_CODE: i64 = 8;
/// A row is tried at most once per this many seconds (D4): `429` with `Retry-After`.
pub const ROW_GAP_S: i64 = 10;
/// An allowed row moves the code's expiry to at least now + this (D7), and a used one keeps its lost answer this long
/// past the code's expiry (D9).
pub const EXEMPT_GRACE_MIN: i64 = 10;
/// Wrong numbers per code before the code ends (D7).
pub const WRONG_NUMBERS: i64 = 5;
/// How often a lost exempt answer may be asked for again (D9).
pub const EXEMPT_REPEATS: i64 = 2;
/// D2: every reason a new phone's enrolment waits for (§3's refusals; `boot` joins in S2), and the phone's no-key words.
pub const WAIT_REASONS: &[&str] = &[
    "chain",
    "root",
    "revoked",
    "expired",
    "software",
    "properties",
    "app",
    "lost",
    "challenge",
    "proof",
    "boot",
    "keystore",
    "keys_unavailable",
    "cap",
];

/// The retention sweep's statement: rows whose code is no longer the phone's live code, but a used row within its
/// `keep_until`.
pub const SWEEP_SQL: &str = "DELETE FROM enroll_key_attempts WHERE NOT ( \
     (used_at IS NOT NULL AND keep_until > datetime('now')) \
     OR (used_at IS NULL AND EXISTS (SELECT 1 FROM devices d WHERE d.id = enroll_key_attempts.device_id \
         AND d.enrollment_code_hash = enroll_key_attempts.code_hash \
         AND d.enrollment_code_expires_at > datetime('now'))))";

/// D8: the reasons the exception covers - all but `app` (help for an honest wrong install, not a boundary),
/// `challenge` and `proof` (the body doesn't match its own code).
pub fn covers(reason: &str) -> bool {
    WAIT_REASONS.contains(&reason) && !matches!(reason, "app" | "challenge" | "proof")
}

/// D2: the phone's no-key reason; a missing or unknown `key_error` is `keystore`, never a code refusal.
pub fn no_key_reason(key_error: Option<&str>) -> &'static str {
    match key_error {
        Some("keys_unavailable") => "keys_unavailable",
        Some("cap") => "cap",
        _ => "keystore",
    }
}

/// The attempt secret (16 bytes, Base64url) to what the server keeps: its SHA-256. `None` when it isn't 16 bytes.
pub fn attempt_hash(attempt_b64: &str) -> Option<[u8; 32]> {
    let raw = base64::Engine::decode(
        &base64::engine::general_purpose::URL_SAFE_NO_PAD,
        attempt_b64.trim_end_matches('='),
    )
    .ok()?;
    (raw.len() == 16).then(|| Sha256::digest(&raw).into())
}

/// D4: the attempt's six-digit number, `u32_be(SHA-256("vibb-attempt-v1" || 0x00 || attempt_hash)[0..4]) mod
/// 1 000 000`, as six digits ("089851").
pub fn number(attempt_hash: &[u8]) -> String {
    let mut hasher = Sha256::new();
    hasher.update(b"vibb-attempt-v1\x00");
    hasher.update(attempt_hash);
    let digest = hasher.finalize();
    let n = u32::from_be_bytes([digest[0], digest[1], digest[2], digest[3]]) % 1_000_000;
    format!("{n:06}")
}

/// §4.1: a waiting answer's pace - 15 s for the row's first 2 minutes, 30 s until 10 minutes, 60 s after.
pub fn wait_pace(since_first_s: i64) -> i64 {
    if since_first_s < 120 {
        15
    } else if since_first_s < 600 {
        30
    } else {
        60
    }
}

/// A typed number with its spaces (and anything but digits) dropped.
fn typed_number(input: &str) -> String {
    input.chars().filter(char::is_ascii_digit).collect()
}

/// A reason's words (`device.about.key.reason.*`, design 129 §9.1, with design 129b's two new words).
pub fn reason_key(reason: &str) -> &'static str {
    match reason {
        "keystore" | "cap" => "device.about.key.reason.keystore",
        "keys_unavailable" => "device.about.key.reason.keys_unavailable",
        other => crate::attestation::Refusal::from_word(other)
            .map(crate::handlers::device_pages::key_reason)
            .unwrap_or("device.about.key.reason.invalid"),
    }
}

/// What one not-passing try came to (§4.2 step 5).
#[derive(Debug, PartialEq, Eq)]
pub enum TryOutcome {
    /// `409 waiting`.
    Waiting {
        reason: String,
        number: String,
        retry_after_s: i64,
    },
    /// The row was tried under [ROW_GAP_S] ago: `429`.
    Throttled { retry_after_s: i64 },
    /// Enrolled on the bearer by the parent's exception (`repeat`: a lost answer asked for again, D9).
    Exempt {
        device_id: i64,
        name: String,
        reason: String,
        repeat: bool,
    },
    /// A right code without `attempt` (an older launcher, D13): `426`.
    OldApp,
    /// No live code: `401`, counted as a wrong code.
    NoCode,
}

/// The try's inputs (§4.2 step 5).
pub struct Try<'a> {
    pub code_hash: &'a str,
    pub attempt: Option<[u8; 32]>,
    pub reason: &'a str,
    pub spki_sha256: Option<&'a str>,
    /// `net::Client::describe` of the try.
    pub address: &'a str,
    /// The bearer's hash, for an exempt enrolment.
    pub token_hash: &'a str,
}

/// An attempt's row as a try reads it: id, allowed_at, allowed_by, seconds since its last and its first try, reason.
type AttemptRead = (i64, Option<String>, Option<i64>, i64, i64, String);

/// The phone's code as the confirm reads it: hash, tag, live, enrolled.
type CodeRead = (Option<String>, Option<Vec<u8>>, bool, bool);

/// The ninth-and-later attempts of a code logged once (D4, L2): the codes already logged.
static PAST_LIMIT_LOGGED: std::sync::Mutex<Option<std::collections::HashSet<String>>> =
    std::sync::Mutex::new(None);

/// §4.2 step 5, in one `BEGIN IMMEDIATE` (M6): the live code's phone, then the attempt's row - allowed: the exempt
/// enrolment (D9); else the row upserted (`429` under [ROW_GAP_S]; a new row only while the code has fewer than
/// [ROWS_PER_CODE]) and `409`. No live code: D9's repeat of a used row within its `keep_until`, else `NoCode`.
pub async fn waiting_try(state: &AppState, try_: &Try<'_>) -> Result<TryOutcome, sqlx::Error> {
    let mut tx = state.db.begin_with("BEGIN IMMEDIATE").await?;
    let device: Option<(i64, String, String)> = sqlx::query_as(
        "SELECT id, name, enrollment_code_expires_at FROM devices WHERE enrollment_code_hash = ? \
         AND enrollment_code_expires_at > datetime('now')",
    )
    .bind(try_.code_hash)
    .fetch_optional(&mut *tx)
    .await?;
    let Some((device_id, name, expires)) = device else {
        let Some(attempt) = try_.attempt else {
            return Ok(TryOutcome::NoCode);
        };
        // D9 (M7): a lost exempt answer - the same attempt again, within `keep_until`, at most [EXEMPT_REPEATS] times.
        let used: Option<(i64, i64, String, String)> = sqlx::query_as(
            "SELECT a.id, a.device_id, a.reason, d.name FROM enroll_key_attempts a \
             JOIN devices d ON d.id = a.device_id WHERE a.code_hash = ? AND a.attempt_hash = ? \
             AND a.used_at IS NOT NULL AND a.keep_until > datetime('now') AND a.repeats < ? \
             AND d.key_exempt_at IS NOT NULL",
        )
        .bind(try_.code_hash)
        .bind(attempt.as_slice())
        .bind(EXEMPT_REPEATS)
        .fetch_optional(&mut *tx)
        .await?;
        let Some((row, device_id, reason, name)) = used else {
            return Ok(TryOutcome::NoCode);
        };
        sqlx::query("UPDATE enroll_key_attempts SET repeats = repeats + 1 WHERE id = ?")
            .bind(row)
            .execute(&mut *tx)
            .await?;
        sqlx::query("UPDATE devices SET token_hash = ? WHERE id = ?")
            .bind(try_.token_hash)
            .bind(device_id)
            .execute(&mut *tx)
            .await?;
        tx.commit().await?;
        return Ok(TryOutcome::Exempt {
            device_id,
            name,
            reason,
            repeat: true,
        });
    };
    let Some(attempt) = try_.attempt else {
        sqlx::query("UPDATE devices SET enroll_old_app_at = datetime('now') WHERE id = ?")
            .bind(device_id)
            .execute(&mut *tx)
            .await?;
        tx.commit().await?;
        return Ok(TryOutcome::OldApp);
    };
    let row: Option<AttemptRead> = sqlx::query_as(
        "SELECT id, allowed_at, allowed_by, \
         CAST(strftime('%s', 'now') AS INTEGER) - CAST(strftime('%s', last_at) AS INTEGER), \
         CAST(strftime('%s', 'now') AS INTEGER) - CAST(strftime('%s', first_at) AS INTEGER), reason \
         FROM enroll_key_attempts WHERE device_id = ? AND attempt_hash = ? AND code_hash = ? AND used_at IS NULL",
    )
    .bind(device_id)
    .bind(attempt.as_slice())
    .bind(try_.code_hash)
    .fetch_optional(&mut *tx)
    .await?;
    let number = number(&attempt);
    match row {
        Some((row_id, Some(_), allowed_by, _, _, allowed_reason)) => {
            // D9: the exempt enrolment, in this transaction. The code's other rows go; this one is kept for a lost
            // answer until the code's expiry + the grace.
            let enrolled = sqlx::query(
                "UPDATE devices SET token_hash = ?1, enroll_used_code_hash = NULL, enroll_used_expires_at = NULL, \
                 enroll_used_spki_sha256 = NULL, enroll_used_proof_rs = NULL, enroll_repeats = 0, \
                 key_refused_reason = NULL, key_refused_at = NULL, key_conflict_at = NULL, \
                 key_exempt_at = datetime('now'), key_exempt_reason = ?3, key_exempt_by = ?4, \
                 enrollment_code_hash = NULL, enrollment_code_kind = NULL, enrollment_code_tag = NULL, \
                 enrollment_code_expires_at = NULL, enroll_old_app_at = NULL, \
                 enrolled_at = datetime('now'), mock_logged = NULL, mock_logged_at = NULL \
                 WHERE id = ?2 AND enrollment_code_hash = ?5 AND enrollment_code_expires_at > datetime('now') \
                 RETURNING enrolled_at",
            )
            .bind(try_.token_hash)
            .bind(device_id)
            .bind(&allowed_reason)
            .bind(allowed_by)
            .bind(try_.code_hash)
            .execute(&mut *tx)
            .await?
            .rows_affected();
            if enrolled == 0 {
                return Ok(TryOutcome::NoCode);
            }
            sqlx::query(
                "UPDATE enroll_key_attempts SET used_at = datetime('now'), keep_until = datetime(?, ?) \
                 WHERE id = ?",
            )
            .bind(&expires)
            .bind(format!("+{EXEMPT_GRACE_MIN} minutes"))
            .bind(row_id)
            .execute(&mut *tx)
            .await?;
            sqlx::query("DELETE FROM enroll_key_attempts WHERE device_id = ? AND id != ?")
                .bind(device_id)
                .bind(row_id)
                .execute(&mut *tx)
                .await?;
            tx.commit().await?;
            Ok(TryOutcome::Exempt {
                device_id,
                name,
                reason: allowed_reason,
                repeat: false,
            })
        }
        Some((row_id, None, _, since_last, since_first, _)) => {
            if since_last < ROW_GAP_S {
                return Ok(TryOutcome::Throttled {
                    retry_after_s: (ROW_GAP_S - since_last).max(1),
                });
            }
            sqlx::query(
                "UPDATE enroll_key_attempts SET attempts = attempts + 1, last_at = datetime('now'), reason = ?, \
                 spki_sha256 = ? WHERE id = ?",
            )
            .bind(try_.reason)
            .bind(try_.spki_sha256.map(str::as_bytes))
            .bind(row_id)
            .execute(&mut *tx)
            .await?;
            tx.commit().await?;
            Ok(TryOutcome::Waiting {
                reason: try_.reason.to_string(),
                number,
                retry_after_s: wait_pace(since_first),
            })
        }
        None => {
            let rows: i64 = sqlx::query_scalar(
                "SELECT COUNT(*) FROM enroll_key_attempts WHERE device_id = ? AND code_hash = ?",
            )
            .bind(device_id)
            .bind(try_.code_hash)
            .fetch_one(&mut *tx)
            .await?;
            if rows >= ROWS_PER_CODE {
                tx.commit().await?;
                let first = PAST_LIMIT_LOGGED
                    .lock()
                    .unwrap_or_else(|e| e.into_inner())
                    .get_or_insert_with(Default::default)
                    .insert(try_.code_hash.to_string());
                if first {
                    log_refused(state, device_id, &name, try_.reason, try_.address, true).await;
                }
                return Ok(TryOutcome::Waiting {
                    reason: try_.reason.to_string(),
                    number,
                    retry_after_s: wait_pace(600),
                });
            }
            sqlx::query(
                "INSERT INTO enroll_key_attempts (device_id, code_hash, attempt_hash, spki_sha256, reason, address, \
                 first_at, last_at) VALUES (?, ?, ?, ?, ?, ?, datetime('now'), datetime('now')) \
                 ON CONFLICT (device_id, attempt_hash) DO UPDATE SET attempts = attempts + 1, \
                 last_at = excluded.last_at, reason = excluded.reason",
            )
            .bind(device_id)
            .bind(try_.code_hash)
            .bind(attempt.as_slice())
            .bind(try_.spki_sha256.map(str::as_bytes))
            .bind(try_.reason)
            .bind(try_.address)
            .execute(&mut *tx)
            .await?;
            tx.commit().await?;
            log_refused(state, device_id, &name, try_.reason, try_.address, false).await;
            Ok(TryOutcome::Waiting {
                reason: try_.reason.to_string(),
                number,
                retry_after_s: wait_pace(0),
            })
        }
    }
}

/// `device_key_refused` at an attempt row's creation (once per attempt), or once per code past [ROWS_PER_CODE].
async fn log_refused(
    state: &AppState,
    device_id: i64,
    name: &str,
    reason: &str,
    address: &str,
    past_limit: bool,
) {
    let detail = if past_limit {
        crate::security::Detail::words(
            "security.detail.device_key_refused.past_limit",
            &[("name", name), ("count", &ROWS_PER_CODE.to_string())],
        )
    } else {
        crate::security::Detail::words(
            "security.detail.device_key_refused.setup",
            &[("name", name)],
        )
        .word("reason", reason_key(reason))
    };
    crate::security::record_security_event(
        &state.db,
        "device_key_refused",
        None,
        Some(address),
        Some(detail.with_facts(format!("device {device_id}"))),
    )
    .await;
}

/// The `409 waiting` answer.
pub fn waiting_answer(reason: &str, number: &str, retry_after_s: i64) -> Response {
    (
        StatusCode::CONFLICT,
        axum::Json(serde_json::json!({
            "state": "waiting",
            "reason": reason,
            "number": number,
            "retry_after_s": retry_after_s,
        })),
    )
        .into_response()
}

/// A `503 {reason, retry_after_s}` with `Retry-After` (D3: `server_setup`, `list_stale`).
pub fn unavailable(reason: &str, retry_after_s: i64) -> Response {
    (
        StatusCode::SERVICE_UNAVAILABLE,
        [(axum::http::header::RETRY_AFTER, retry_after_s.to_string())],
        axum::Json(serde_json::json!({ "reason": reason, "retry_after_s": retry_after_s })),
    )
        .into_response()
}

/// D13: the reason of the `426` a launcher without `attempt` gets.
pub const UPDATE_APP: &str = "update_app";

/// `server_setup`'s pace (D3).
pub const SERVER_SETUP_RETRY_S: i64 = 3600;
/// The stale list's pace for an enrolment whose chain would pass (D3).
pub const LIST_STALE_RETRY_S: i64 = 60;

// ---------------------------------------------------------------------------------------------
// The PWA: the line, the confirm and the landings (§5.1, §5.6)

/// One live attempt as the confirm lists it: "{time}, {address}" - never its number.
pub struct AttemptRow {
    pub text: String,
}

/// The waiting line of a phone with live attempts.
pub struct Wait {
    /// "{name} kunne ikke bevise nøkkelen sin: {reason}".
    pub line: String,
    /// The newest attempt's reason is `app`: the (i) says to install the official Vibb One, no button (D8).
    pub app: bool,
    /// The confirm is offered: a live, not allowed attempt with a reason D8 covers.
    pub button: bool,
    /// "{count} forsøk", more than one attempt.
    pub count: Option<String>,
    pub attempts: Vec<AttemptRow>,
    /// The code's tag (hex), the form's (M5).
    pub tag: String,
}

/// `partials/key_wait.html`'s state: always the section (`#key-exception`), empty when nothing shows.
#[derive(Default)]
pub struct Section {
    pub device_id: i64,
    /// `summary` or `provision`: where the confirm comes back to.
    pub back: &'static str,
    pub wait: Option<Wait>,
    /// A quiet row: "Venter på telefonen" (an allowed attempt), or a landing's.
    pub quiet: Option<String>,
    /// "{name} har en eldre Vibb One" (D13).
    pub old_app: Option<String>,
    /// The confirm open with its field's error.
    pub error: Option<String>,
    /// The re-auth's passkey (`partials/reauth_passkey.html`).
    pub passkey: bool,
    pub passkey_only: bool,
}

/// A live row as read for the page and the confirm.
#[derive(sqlx::FromRow)]
struct LiveRow {
    reason: String,
    address: String,
    last_at: String,
    allowed_at: Option<String>,
}

/// The phone's live rows: its live code's, not used, newest first.
async fn live_rows(db: &sqlx::SqlitePool, device_id: i64) -> Result<Vec<LiveRow>, sqlx::Error> {
    sqlx::query_as(
        "SELECT a.reason, a.address, a.last_at, a.allowed_at \
         FROM enroll_key_attempts a JOIN devices d ON d.id = a.device_id \
         WHERE a.device_id = ? AND a.code_hash = d.enrollment_code_hash \
         AND d.enrollment_code_expires_at > datetime('now') AND a.used_at IS NULL \
         ORDER BY a.last_at DESC, a.id DESC",
    )
    .bind(device_id)
    .fetch_all(db)
    .await
}

/// A stored `datetime('now')` as the page says a time ("14:02", "i går 14:02").
fn when_db(
    zone: &crate::display_time::Zone,
    stored: &str,
    now: chrono::DateTime<chrono::Utc>,
) -> String {
    chrono::NaiveDateTime::parse_from_str(stored, "%Y-%m-%d %H:%M:%S")
        .map(|t| zone.at(t.and_utc(), now))
        .unwrap_or_else(|_| stored.to_string())
}

/// The security log's words for a refused confirm's re-auth.
fn refused_reauth(passkey: bool) -> &'static str {
    if passkey {
        "security.detail.device_key_exempt_refused.passkey"
    } else {
        "security.detail.device_key_exempt_refused.password"
    }
}

/// Reads the section for a phone's page. `notice`/`key_error` are the confirm's landing (`?notice=`, `?key_error=`).
pub async fn section(
    state: &AppState,
    lang: Lang,
    device_id: i64,
    name: &str,
    back: &'static str,
    query: &std::collections::HashMap<String, String>,
) -> Section {
    let mut section = Section {
        device_id,
        back,
        ..Default::default()
    };
    let rows = live_rows(&state.db, device_id).await.unwrap_or_else(|err| {
        tracing::error!(device_id, %err, "can't read the phone's enrolment attempts");
        Vec::new()
    });
    let (tag, old_app): (Option<Vec<u8>>, bool) = sqlx::query_as(
        "SELECT enrollment_code_tag, enroll_old_app_at IS NOT NULL \
         AND enrollment_code_expires_at > datetime('now') FROM devices WHERE id = ?",
    )
    .bind(device_id)
    .fetch_optional(&state.db)
    .await
    .ok()
    .flatten()
    .unwrap_or_default();
    if old_app {
        section.old_app = Some(t_params(lang, "device.old_app", &[("name", name)]));
    }
    let waiting: Vec<&LiveRow> = rows.iter().filter(|r| r.allowed_at.is_none()).collect();
    if let Some(newest) = waiting.first() {
        let zone = crate::display_time::Zone::load(&state.db, lang).await;
        let now = chrono::Utc::now();
        section.wait = Some(Wait {
            line: t_params(
                lang,
                "device.key_wait",
                &[
                    ("name", name),
                    ("reason", t(lang, reason_key(&newest.reason))),
                ],
            ),
            app: newest.reason == "app",
            button: waiting.iter().any(|r| covers(&r.reason)),
            count: (rows.len() > 1)
                .then(|| t_count(lang, "device.key_wait.attempts", rows.len() as i64, &[])),
            attempts: waiting
                .iter()
                .map(|r| AttemptRow {
                    text: format!("{}, {}", when_db(&zone, &r.last_at, now), r.address),
                })
                .collect(),
            tag: tag.as_deref().map(hex::encode).unwrap_or_default(),
        });
    } else if rows.iter().any(|r| r.allowed_at.is_some()) {
        section.quiet = Some(t(lang, "device.key_exception.waiting").to_string());
    }
    if section.wait.is_none() && section.quiet.is_none() {
        section.quiet = match query.get("notice").map(String::as_str) {
            Some("key_exception_connected") => {
                Some(t(lang, "device.key_exception.connected").to_string())
            }
            Some("key_exception_new_code") => {
                Some(t(lang, "device.key_exception.new_code").to_string())
            }
            _ => None,
        };
    }
    if section.wait.is_some() {
        section.error = match query.get("key_error").map(String::as_str) {
            Some("number") => Some(t(lang, "device.key_exception.number_wrong").to_string()),
            Some("twice") => Some(t(lang, "device.key_exception.number_twice").to_string()),
            Some("password") => Some(t(lang, "device.lock.wrong").to_string()),
            Some("password_last") => Some(t(lang, "device.lock.wrong_last").to_string()),
            Some("passkey") => Some(t(lang, "passkeys.reauth.failed").to_string()),
            Some("passkey_last") => Some(t(lang, "passkeys.reauth.failed_last").to_string()),
            Some("needs_passkey") => Some(t(lang, "passkeys.only.needs_passkey").to_string()),
            _ => None,
        };
    }
    section
}

/// The Attention row's and the list badge's facts: the line's words, and the older launcher's.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct KeyFacts {
    /// The newest waiting attempt's reason, while one waits for the parent.
    pub waiting_reason: Option<String>,
    pub old_app: bool,
    pub name: String,
}

/// Reads [KeyFacts] (a read that fails says nothing; logged).
pub async fn facts(db: &sqlx::SqlitePool, device_id: i64, name: &str) -> KeyFacts {
    let rows = live_rows(db, device_id).await.unwrap_or_else(|err| {
        tracing::error!(device_id, %err, "attention: can't read the enrolment attempts");
        Vec::new()
    });
    let old_app: bool = sqlx::query_scalar(
        "SELECT enroll_old_app_at IS NOT NULL AND enrollment_code_expires_at > datetime('now') \
         FROM devices WHERE id = ?",
    )
    .bind(device_id)
    .fetch_optional(db)
    .await
    .ok()
    .flatten()
    .unwrap_or(false);
    KeyFacts {
        waiting_reason: rows
            .iter()
            .find(|r| r.allowed_at.is_none())
            .map(|r| r.reason.clone()),
        old_app,
        name: name.to_string(),
    }
}

#[derive(serde::Deserialize, Default)]
pub struct ConfirmForm {
    #[serde(default)]
    number: String,
    #[serde(default)]
    tag: String,
    #[serde(default)]
    back: String,
    #[serde(default)]
    password: String,
    #[serde(default)]
    assertion: String,
}

/// Where the confirm comes back to: the summary or Connect, at `#key-exception` with `query`.
fn back_to(id: i64, back: &str, query: &str) -> Response {
    let page = if back == "provision" {
        format!("/devices/{id}/provision")
    } else {
        format!("/devices/{id}")
    };
    Redirect::to(&format!("{page}?{query}#key-exception")).into_response()
}

/// What the confirm's transaction came to.
enum Confirmed {
    Allowed {
        reason: String,
        address: String,
        first_at: String,
    },
    WrongNumber,
    TooManyNumbers,
    Twice,
    NotCovered,
    Connected,
    NewCode,
}

/// `POST /devices/{id}/key-exception` (D7): the re-auth first, then in one `BEGIN IMMEDIATE` the live row of this
/// phone's live code (the form's tag) whose number is the typed one - allowed, the code's expiry moved to at least
/// now + [EXEMPT_GRACE_MIN] minutes, logged. Every answer ends at `#key-exception`.
pub async fn confirm(
    State(state): State<AppState>,
    lang: Lang,
    Path(id): Path<i64>,
    session: tower_sessions::Session,
    ceremony: crate::passkeys::Ceremony,
    axum::Extension(crate::security::CurrentAdmin(admin)): axum::Extension<
        crate::security::CurrentAdmin,
    >,
    axum::Form(form): axum::Form<ConfirmForm>,
) -> Response {
    let name: Option<String> = match sqlx::query_scalar("SELECT name FROM devices WHERE id = ?")
        .bind(id)
        .fetch_optional(&state.db)
        .await
    {
        Ok(name) => name,
        Err(err) => {
            tracing::error!(device_id = id, %err, "can't read the phone for its key exception");
            return StatusCode::INTERNAL_SERVER_ERROR.into_response();
        }
    };
    let Some(name) = name else {
        return StatusCode::NOT_FOUND.into_response();
    };
    let refused = |why: &'static str| {
        let (db, username, name) = (state.db.clone(), admin.username.clone(), name.clone());
        async move {
            crate::security::record_security_event(
                &db,
                "device_key_exempt_refused",
                Some(&username),
                None,
                Some(
                    crate::security::Detail::words(why, &[("name", &name)])
                        .with_facts(format!("device {id}")),
                ),
            )
            .await;
        }
    };
    let proof = crate::admin_auth::Proof::of(&form.password, &form.assertion, &ceremony);
    match crate::admin_auth::reauth(&state, &session, &admin, proof).await {
        crate::admin_auth::Reauth::Busy => return crate::admin_auth::busy_response(lang),
        crate::admin_auth::Reauth::SignedOut => {
            refused(refused_reauth(proof.is_passkey())).await;
            return crate::admin_auth::signed_out_response();
        }
        crate::admin_auth::Reauth::Wrong { left } => {
            refused(refused_reauth(proof.is_passkey())).await;
            let error = match (left, proof.is_passkey()) {
                (1, true) => "passkey_last",
                (_, true) => "passkey",
                (1, false) => "password_last",
                _ => "password",
            };
            return back_to(id, &form.back, &format!("key_error={error}"));
        }
        crate::admin_auth::Reauth::NeedsPasskey => {
            return back_to(id, &form.back, "key_error=needs_passkey");
        }
        crate::admin_auth::Reauth::Right => {}
    }
    let typed = typed_number(&form.number);
    let tag = hex::decode(form.tag.trim()).unwrap_or_default();
    let confirmed = match allow(&state, id, admin.id, &typed, &tag).await {
        Ok(confirmed) => confirmed,
        Err(err) => {
            tracing::error!(device_id = id, %err, "can't confirm the key exception");
            return StatusCode::INTERNAL_SERVER_ERROR.into_response();
        }
    };
    match confirmed {
        Confirmed::Allowed {
            reason,
            address,
            first_at,
        } => {
            state.enrollment.refresh(&state.db).await;
            let when = chrono::NaiveDateTime::parse_from_str(&first_at, "%Y-%m-%d %H:%M:%S")
                .map(|t| t.and_utc())
                .unwrap_or_else(|_| chrono::Utc::now());
            crate::security::record_security_event(
                &state.db,
                "device_key_exempted",
                Some(&admin.username),
                Some(&address),
                Some(
                    crate::security::Detail::words(
                        "security.detail.device_key_exempted",
                        &[("name", &name)],
                    )
                    .word("reason", reason_key(&reason))
                    .at("at", when)
                    .with_facts(format!("device {id}")),
                ),
            )
            .await;
            back_to(id, &form.back, "notice=key_exception")
        }
        Confirmed::WrongNumber => back_to(id, &form.back, "key_error=number"),
        Confirmed::Twice => back_to(id, &form.back, "key_error=twice"),
        Confirmed::TooManyNumbers => {
            refused("security.detail.device_key_exempt_refused.too_many").await;
            state.enrollment.refresh(&state.db).await;
            back_to(id, &form.back, "notice=key_exception_new_code")
        }
        Confirmed::NotCovered => {
            crate::i18n::error(lang, StatusCode::BAD_REQUEST, "error.out_of_date")
        }
        Confirmed::Connected => back_to(id, &form.back, "notice=key_exception_connected"),
        Confirmed::NewCode => back_to(id, &form.back, "notice=key_exception_new_code"),
    }
}

/// D7's transaction (M6): the compare-and-set on the row of the typed number.
async fn allow(
    state: &AppState,
    id: i64,
    admin_id: i64,
    typed: &str,
    tag: &[u8],
) -> Result<Confirmed, sqlx::Error> {
    let mut tx = state.db.begin_with("BEGIN IMMEDIATE").await?;
    let code: Option<CodeRead> = sqlx::query_as(
        "SELECT enrollment_code_hash, enrollment_code_tag, \
         enrollment_code_expires_at > datetime('now'), enrolled_at IS NOT NULL FROM devices WHERE id = ?",
    )
    .bind(id)
    .fetch_optional(&mut *tx)
    .await?;
    let Some((code_hash, code_tag, live, enrolled)) = code else {
        return Ok(Confirmed::NewCode);
    };
    let Some(code_hash) = code_hash.filter(|_| live && code_tag.as_deref() == Some(tag)) else {
        // A form drawn under another code (M5), or the code used or gone: the landing says which (L5).
        let used: bool = sqlx::query_scalar(
            "SELECT EXISTS (SELECT 1 FROM enroll_key_attempts WHERE device_id = ? AND used_at IS NOT NULL)",
        )
        .bind(id)
        .fetch_one(&mut *tx)
        .await?;
        return Ok(if enrolled && (used || code_tag.is_none()) {
            Confirmed::Connected
        } else {
            Confirmed::NewCode
        });
    };
    let rows: Vec<(i64, Vec<u8>, String, String, String)> = sqlx::query_as(
        "SELECT id, attempt_hash, reason, address, first_at FROM enroll_key_attempts \
         WHERE device_id = ? AND code_hash = ? AND allowed_at IS NULL AND used_at IS NULL",
    )
    .bind(id)
    .bind(&code_hash)
    .fetch_all(&mut *tx)
    .await?;
    let matching: Vec<_> = rows
        .into_iter()
        .filter(|(_, hash, ..)| !typed.is_empty() && number(hash) == typed)
        .collect();
    match matching.as_slice() {
        [] => {
            let wrong: i64 = sqlx::query_scalar(
                "UPDATE devices SET enroll_wrong_numbers = enroll_wrong_numbers + 1 WHERE id = ? \
                 RETURNING enroll_wrong_numbers",
            )
            .bind(id)
            .fetch_one(&mut *tx)
            .await?;
            if wrong >= WRONG_NUMBERS {
                sqlx::query(
                    "UPDATE devices SET enrollment_code_hash = NULL, enrollment_code_kind = NULL, \
                     enrollment_code_expires_at = NULL, enrollment_code_tag = NULL WHERE id = ?",
                )
                .bind(id)
                .execute(&mut *tx)
                .await?;
                sqlx::query("DELETE FROM enroll_key_attempts WHERE device_id = ?")
                    .bind(id)
                    .execute(&mut *tx)
                    .await?;
                tx.commit().await?;
                return Ok(Confirmed::TooManyNumbers);
            }
            tx.commit().await?;
            Ok(Confirmed::WrongNumber)
        }
        [(row, _, reason, address, first_at)] => {
            if !covers(reason) {
                return Ok(Confirmed::NotCovered);
            }
            let set = sqlx::query(
                "UPDATE enroll_key_attempts SET allowed_at = datetime('now'), allowed_by = ? \
                 WHERE id = ? AND allowed_at IS NULL AND code_hash = (SELECT enrollment_code_hash FROM devices \
                 WHERE id = ? AND enrollment_code_tag = ? AND enrollment_code_expires_at > datetime('now'))",
            )
            .bind(admin_id)
            .bind(row)
            .bind(id)
            .bind(tag)
            .execute(&mut *tx)
            .await?
            .rows_affected();
            if set == 0 {
                return Ok(Confirmed::NewCode);
            }
            sqlx::query(
                "UPDATE devices SET enrollment_code_expires_at = \
                 MAX(enrollment_code_expires_at, datetime('now', ?)) WHERE id = ?",
            )
            .bind(format!("+{EXEMPT_GRACE_MIN} minutes"))
            .bind(id)
            .execute(&mut *tx)
            .await?;
            tx.commit().await?;
            Ok(Confirmed::Allowed {
                reason: reason.clone(),
                address: address.clone(),
                first_at: first_at.clone(),
            })
        }
        _ => Ok(Confirmed::Twice),
    }
}

/// About This Phone's quiet "Uten maskinvarenøkkel" with "Valgt av {parent} {date} ({reason})." behind its (i), while
/// the phone is exempt and has no key.
pub async fn about_exempt(
    state: &AppState,
    lang: Lang,
    device_id: i64,
) -> Result<Option<String>, sqlx::Error> {
    let row: Option<(String, Option<String>, Option<String>)> = sqlx::query_as(
        "SELECT d.key_exempt_at, d.key_exempt_reason, COALESCE(a.display_name, a.username) FROM devices d \
         LEFT JOIN admin_users a ON a.id = d.key_exempt_by WHERE d.id = ? AND d.key_exempt_at IS NOT NULL",
    )
    .bind(device_id)
    .fetch_optional(&state.db)
    .await?;
    let Some((at, reason, parent)) = row else {
        return Ok(None);
    };
    let zone = crate::display_time::Zone::load(&state.db, lang).await;
    let date = chrono::NaiveDateTime::parse_from_str(&at, "%Y-%m-%d %H:%M:%S")
        .map(|t| {
            let now = chrono::Utc::now();
            zone.date_of(zone.local(t.and_utc()).date_naive(), zone.today(now))
        })
        .unwrap_or(at);
    Ok(Some(t_params(
        lang,
        "device.about.key.exempt.detail",
        &[
            ("parent", parent.as_deref().unwrap_or("?")),
            ("date", &date),
            (
                "reason",
                t(lang, reason_key(reason.as_deref().unwrap_or(""))),
            ),
        ],
    )))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn contract() -> serde_json::Value {
        serde_json::from_str(&std::fs::read_to_string("testdata/device_key.json").unwrap()).unwrap()
    }

    #[test]
    fn the_contract_numbers_and_pace() {
        let c = contract();
        for row in c["enroll"]["numbers"].as_array().unwrap() {
            let hash = attempt_hash(row["attempt"].as_str().unwrap()).unwrap();
            assert_eq!(hex::encode(hash), row["attempt_hash"].as_str().unwrap());
            assert_eq!(number(&hash), row["number"].as_str().unwrap());
            let shown = row["shown"].as_str().unwrap();
            assert_eq!(typed_number(shown), number(&hash), "spaces ignored");
        }
        for row in c["enroll"]["wait_pace"].as_array().unwrap() {
            assert_eq!(
                wait_pace(row["since_first_s"].as_i64().unwrap()),
                row["retry_after_s"].as_i64().unwrap()
            );
        }
        let reasons: Vec<&str> = c["enroll"]["wait_reasons"]
            .as_array()
            .unwrap()
            .iter()
            .map(|r| r.as_str().unwrap())
            .collect();
        for reason in &reasons {
            assert!(WAIT_REASONS.contains(reason), "{reason}");
        }
        let covered: Vec<&str> = c["enroll"]["exception_reasons"]
            .as_array()
            .unwrap()
            .iter()
            .map(|r| r.as_str().unwrap())
            .collect();
        for reason in reasons {
            assert_eq!(covers(reason), covered.contains(&reason), "{reason}");
        }
        for row in c["enroll"]["body_no_key"].as_array().unwrap() {
            assert_eq!(
                no_key_reason(row["body"]["key_error"].as_str()),
                row["reason"].as_str().unwrap()
            );
        }
        assert_eq!(attempt_hash("short"), None);
    }
}
