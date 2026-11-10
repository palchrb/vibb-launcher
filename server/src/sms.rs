//! Design 77 step 1, the server's side of the SMS engine (`docs/design/77-sms-mms-client.md` §7): the policy's SMS
//! parts (`call_policy.sms_names`, `own_numbers`, `sms_held_drop`), the status report's `sms_state` through its known
//! keys, the parent's sender names (D17: [valid_name], [fold]) and the held and blocked senders the phone posts
//! (`POST /api/devices/sms-held`, D16) - who and when, never what. The shapes are `testdata/sms_contract.json`,
//! byte-identical in the launcher's test resources. Step 4: the intake raises the parent's `sms_held` event at a held
//! row's first sight (`parent_events::sms_held`, its push within `Kind::push_budget`) and marks it read when the row
//! moves on; the PWA's cards are `handlers::calls_sms`.

use axum::Extension;
use axum::extract::State;
use axum::http::StatusCode;
use axum::response::{IntoResponse, Response};
use serde::{Deserialize, Serialize};
use unicode_normalization::UnicodeNormalization;

use crate::AppState;
use crate::handlers::device_api::{Field, known_fields};
use crate::security::{self, AuthedDevice, Detail, PhoneLine};

/// The launcher's capability: it has the engine and reads `calls_mode`/`sms_mode` (design 77 §7).
pub const SMS_ENGINE_CAPABILITY: &str = "sms_engine_v1";

/// D2, R2-L7: whether a launcher without the engine does a channel pair exactly as asked from the derived old keys -
/// calls managed or off with SMS off or not managed, or both not managed. Anything else it does stricter (SMS lost,
/// calls contacts-only), so the Calls page offers it only once the phone reports [SMS_ENGINE_CAPABILITY] (code QA M2).
pub fn older_launcher_expresses(calls_mode: &str, sms_mode: &str) -> bool {
    matches!(
        (calls_mode, sms_mode),
        ("unmanaged", "unmanaged") | ("managed" | "off", "off" | "unmanaged")
    )
}

/// What a launcher without the engine makes of a stored pair it can't express (`None`: none): whether SMS the parent
/// wanted is lost (SMS "Styrt av Vibb" reads as SMS off) and whether calls the parent left alone are contacts-only
/// (calls "Ikke styrt" with SMS restricted reads as managed calls).
pub fn older_launcher_loses(calls_mode: &str, sms_mode: &str) -> Option<(bool, bool)> {
    (!older_launcher_expresses(calls_mode, sms_mode))
        .then(|| (sms_mode == "managed", calls_mode == "unmanaged"))
}

/// Design 80 D13: the launcher honours `call_policy.sms_groups` ("Gruppemeldinger") and reports
/// `sms_state.own_learned`. Until a phone claims it the Calls page draws the switch disabled - an older engine would
/// ignore an "off".
pub const SMS_GROUPS_CAPABILITY: &str = "sms_groups_v1";

/// Whether the phone's newest status report claims the engine (no report: no).
pub async fn has_engine(db: &sqlx::SqlitePool, device_id: i64) -> Result<bool, sqlx::Error> {
    has_capability(db, device_id, SMS_ENGINE_CAPABILITY).await
}

/// Whether the phone's newest status report claims `capability` (no report: no).
pub async fn has_capability(
    db: &sqlx::SqlitePool,
    device_id: i64,
    capability: &str,
) -> Result<bool, sqlx::Error> {
    let latest: Option<Option<String>> = sqlx::query_scalar(
        "SELECT capabilities_json FROM device_status WHERE device_id = ? \
         ORDER BY reported_at DESC, id DESC LIMIT 1",
    )
    .bind(device_id)
    .fetch_optional(db)
    .await?;
    Ok(latest
        .flatten()
        .and_then(|json| serde_json::from_str::<Vec<String>>(&json).ok())
        .is_some_and(|caps| caps.iter().any(|c| c == capability)))
}

/// D17: a sender name is at most 11 characters (an alphanumeric TP-OA holds 11 GSM-7 characters).
pub const NAME_MAX: usize = 11;

/// The name as the shared cleaner leaves it (`calendar::clean`: controls, bidi and invisible characters out, every
/// whitespace run one space, trimmed, NFC), never cut.
pub fn clean_name(raw: &str) -> String {
    crate::calendar::clean(raw, usize::MAX)
}

/// D17 (QA R2-H1): 1-11 characters after the cleaner and at least one letter, so "+4791234567", "112" or
/// "91 23 45 67" - anything a number could read as - is never a name the parent can let through.
pub fn valid_name(raw: &str) -> bool {
    let name = clean_name(raw);
    (1..=NAME_MAX).contains(&name.chars().count()) && name.chars().any(char::is_alphabetic)
}

/// D17 (QA R2-L1): the one fold rule both sides use - NFC, then Unicode's default lowercase mapping (Kotlin's
/// `lowercase(Locale.ROOT)`), no full case folding. The phone matches a sender on it; the server keeps a phone's
/// names unique on it.
pub fn fold(name: &str) -> String {
    name.nfc().collect::<String>().to_lowercase()
}

/// The classes a held or blocked row's sender may have (§7).
pub const SENDER_CLASSES: [&str; 7] = [
    "number",
    "short",
    "alphanumeric",
    "malformed",
    "empty",
    "bad_location",
    "group",
];

/// QA R2-M2: a posted sender by its class - a number `+` and ASCII digits, 3-16 characters; a name 1-11 characters
/// that the shared cleaner leaves unchanged (no control, bidi or invisible character, no doubled space); every other
/// class empty. So a stranger's text can't pose as Vibb's own words in a push or a page.
pub fn sender_ok(class: &str, sender: &str) -> bool {
    match class {
        "number" | "short" => {
            let digits = sender.strip_prefix('+').unwrap_or(sender);
            (3..=16).contains(&sender.len())
                && !digits.is_empty()
                && digits.bytes().all(|b| b.is_ascii_digit())
        }
        "alphanumeric" => {
            (1..=NAME_MAX).contains(&sender.chars().count())
                && crate::calendar::clean(sender, NAME_MAX) == sender
        }
        "malformed" | "empty" | "bad_location" | "group" => sender.is_empty(),
        _ => false,
    }
}

/// D12: one of the phone's own numbers as the parent typed it, `call_policy.own_numbers`.
#[derive(Serialize, Clone, Debug, PartialEq, Eq, sqlx::FromRow)]
pub struct OwnNumber {
    pub slot: i64,
    pub number: String,
}

/// How long a parent's "Slett nå" is sent back as `sms_held_drop` (D16), and at most how many ids.
const DROP_HOURS: i64 = 24;
const DROP_MAX: i64 = 100;

/// The policy's SMS lists for a phone: its sender names (sorted by their fold), its own numbers (by slot) and the
/// held ids the parent deleted in the last 24 hours (newest first, at most 100) - a row the phone already released
/// or let expire needs no drop.
pub async fn policy_parts(
    db: &sqlx::SqlitePool,
    device_id: i64,
) -> Result<(Vec<String>, Vec<OwnNumber>, Vec<String>), sqlx::Error> {
    let stored: Vec<String> = sqlx::query_scalar(
        "SELECT name FROM device_sms_names WHERE device_id = ? ORDER BY fold, name",
    )
    .bind(device_id)
    .fetch_all(db)
    .await?;
    // Only valid names, once each by the fold (the writer refuses the rest; a row written another way never reaches
    // a phone as a number-like "name").
    let mut seen = std::collections::HashSet::new();
    let names = stored
        .into_iter()
        .filter(|name| valid_name(name) && seen.insert(fold(&clean_name(name))))
        .collect();
    let own = sqlx::query_as(
        "SELECT slot, number FROM device_own_numbers WHERE device_id = ? ORDER BY slot",
    )
    .bind(device_id)
    .fetch_all(db)
    .await?;
    let drop = sqlx::query_scalar(
        "SELECT held_id FROM device_sms_held WHERE device_id = ? AND dropped_at IS NOT NULL \
         AND state IN ('held', 'dropped') AND datetime(dropped_at) >= datetime('now', ?) \
         AND expires_at > ? ORDER BY datetime(dropped_at) DESC, held_id LIMIT ?",
    )
    .bind(device_id)
    .bind(format!("-{DROP_HOURS} hours"))
    // Code QA L5: a row past its window is gone on the phone (it expires first); the 100 ids are for the rest.
    .bind(chrono::Utc::now().timestamp_millis())
    .bind(DROP_MAX)
    .fetch_all(db)
    .await?;
    Ok((names, own, drop))
}

/// The launcher's `SmsState` (`sms_contract.json`'s `report.sms_state`), its flat keys.
const SMS_STATE_FIELDS: &[(&str, Field)] = &[
    ("role_held", Field::Bool),
    ("mode_applied", Field::Word(16)),
    ("received", Field::Int),
    ("sent", Field::Int),
    ("held", Field::Int),
    ("released", Field::Int),
    ("expired", Field::Int),
    ("parse_failures", Field::Int),
    ("mms_waiting", Field::Int),
    ("mms_data_allowed", Field::Bool),
    ("mmsc_known", Field::Bool),
    // The launcher keeps 300, as `call_state.last_error`.
    ("last_error", Field::Text(300)),
    // Design 82 W2: the phone has threads to read - its Meldinger tile shows even while SMS is not managed.
    ("has_history", Field::Bool),
];

/// The last report's `sms_state.has_history` (design 82 W2): `false` without a report or from a launcher that
/// doesn't say.
pub async fn reported_history(db: &sqlx::SqlitePool, device_id: i64) -> Result<bool, sqlx::Error> {
    let state: Option<Option<String>> = sqlx::query_scalar(
        "SELECT sms_state_json FROM device_status WHERE device_id = ? \
         ORDER BY reported_at DESC, id DESC LIMIT 1",
    )
    .bind(device_id)
    .fetch_optional(db)
    .await?;
    Ok(state
        .flatten()
        .and_then(|json| serde_json::from_str::<serde_json::Value>(&json).ok())
        .and_then(|state| state.get("has_history")?.as_bool())
        .unwrap_or(false))
}

/// `sms_state.blocked`'s keys: the blocked messages counted by sender class, and those past a hold cap.
const BLOCKED_KEYS: [&str; 8] = [
    "number",
    "short",
    "alphanumeric",
    "malformed",
    "empty",
    "group",
    "bad_location",
    "over_cap",
];

/// A phone's `sms_state` through its known keys (security review L10, as `call_state`): the flat ones by
/// [SMS_STATE_FIELDS], `blocked` as counts by class, `own_numbers` at most two `{slot, number}` (a number by
/// [sender_ok]'s rule, or `null` when the SIM doesn't know), `hold_full` two booleans. `None` when nothing is kept.
/// No content: the phone sends none, and nothing else gets through.
pub fn sanitize_state(value: &serde_json::Value) -> Option<String> {
    use serde_json::{Map, Value};
    let object = value.as_object()?;
    let mut kept: Map<String, Value> = known_fields(value, SMS_STATE_FIELDS)
        .and_then(|json| serde_json::from_str(&json).ok())
        .unwrap_or_default();
    if let Some(Value::Object(blocked)) = object.get("blocked") {
        let counts: Map<String, Value> = BLOCKED_KEYS
            .iter()
            .filter_map(|key| {
                let n = blocked.get(*key)?.as_i64().filter(|n| *n >= 0)?;
                Some((key.to_string(), Value::from(n)))
            })
            .collect();
        kept.insert("blocked".into(), Value::Object(counts));
    }
    if let Some(Value::Array(own)) = object.get("own_numbers") {
        let rows: Vec<Value> = own
            .iter()
            .take(2)
            .filter_map(|row| {
                let slot = row.get("slot")?.as_i64().filter(|s| (1..=2).contains(s))?;
                let number = match row.get("number")? {
                    Value::Null => Value::Null,
                    Value::String(n) if sender_ok("number", n) => Value::from(n.as_str()),
                    _ => return None,
                };
                Some(serde_json::json!({ "slot": slot, "number": number }))
            })
            .collect();
        kept.insert("own_numbers".into(), Value::Array(rows));
    }
    if let Some(Value::Object(full)) = object.get("hold_full") {
        let flags: Map<String, Value> = ["numbers", "names"]
            .iter()
            .filter_map(|key| Some((key.to_string(), Value::from(full.get(*key)?.as_bool()?))))
            .collect();
        kept.insert("hold_full".into(), Value::Object(flags));
    }
    // Design 80 §4.4: the own number learned from a message - `null` for none, else every number by [sender_ok]'s
    // rule (`number` may be `null` during a conflict), `at` a non-negative integer, at most two in `conflict`. Anything
    // else drops the key.
    match object.get("own_learned") {
        Some(Value::Null) => {
            kept.insert("own_learned".into(), Value::Null);
        }
        Some(learned) => {
            if let Some(clean) = own_learned(learned) {
                kept.insert("own_learned".into(), clean);
            }
        }
        None => {}
    }
    (!kept.is_empty()).then(|| Value::Object(kept).to_string())
}

/// [sanitize_state]'s `own_learned` (design 80 §4.4), `None` when it isn't the shape.
fn own_learned(value: &serde_json::Value) -> Option<serde_json::Value> {
    use serde_json::Value;
    let number_ok = |n: &str| sender_ok("number", n) && n.starts_with('+');
    let number = match value.get("number")? {
        Value::Null => Value::Null,
        Value::String(n) if number_ok(n) => Value::from(n.as_str()),
        _ => return None,
    };
    let from = value
        .get("from")?
        .as_str()
        .filter(|n| sender_ok("number", n))?;
    let at = value.get("at")?.as_i64().filter(|at| *at >= 0)?;
    let conflict: Vec<Value> = value
        .get("conflict")?
        .as_array()?
        .iter()
        .take(2)
        .map(|n| n.as_str().filter(|n| number_ok(n)).map(Value::from))
        .collect::<Option<_>>()?;
    Some(serde_json::json!({ "number": number, "from": from, "at": at, "conflict": conflict }))
}

/// `POST /api/devices/sms-held`'s body limit (§7).
pub const HELD_BODY_LIMIT: usize = 16 * 1024;
/// At most this many rows a post.
pub const HELD_ROWS_MAX: usize = 50;
/// QA R2-M3: at most this many held rows (`held`/`released`/`expired`/`dropped`) a phone (retention keeps 30 days of
/// them). Blocked rows have their own bound ([BLOCKED_ROWS_A_PHONE], code QA L5): a flood of blocked senders never
/// takes the places of later held messages the parent must hear of.
pub const HELD_ROWS_A_PHONE: i64 = 500;
/// Code QA L5: at most this many `blocked` rows a phone; their counts are in `sms_state.blocked` anyway.
pub const BLOCKED_ROWS_A_PHONE: i64 = 500;
/// How long the held rows are kept after their `at` (retention).
pub const HELD_KEEP_DAYS: i64 = 30;
/// A row's `at` may be this far past the server's clock (the phone's clock is locked while managed).
const AT_AHEAD_MS: i64 = 5 * 60 * 1000;
/// `expires_at - at`: the phone's 24-hour window, plus a minute.
const WINDOW_MAX_MS: i64 = 24 * 60 * 60 * 1000 + 60 * 1000;

/// One posted row (`sms_contract.json`'s `held.request.rows`): the phone's `id`, its instants in epoch ms.
#[derive(Deserialize, Debug, Clone)]
struct HeldRow {
    id: String,
    at: i64,
    kind: String,
    sender_class: String,
    sender: String,
    expires_at: i64,
    state: String,
}

#[derive(Deserialize)]
struct HeldBody {
    rows: Vec<serde_json::Value>,
}

/// The answer: how many rows were stored, updated or already known, and whether new ids were past the bound.
#[derive(Serialize, Debug, PartialEq, Eq)]
pub struct HeldAnswer {
    pub accepted: usize,
    #[serde(skip_serializing_if = "std::ops::Not::not")]
    pub capped: bool,
}

/// A 400: the first refused row and why; nothing of the post is stored.
#[derive(Serialize, Debug, PartialEq, Eq)]
pub struct HeldRefusal {
    pub refused: usize,
    pub reason: &'static str,
}

const STATES: [&str; 5] = ["held", "released", "expired", "dropped", "blocked"];

/// Why `row` is refused, or `None` (§7's intake checks, QA R2-M2/M3): the id, the kind, the class, the sender by
/// its class, the times against the server's clock (`now_ms`), the state.
fn refusal(row: &HeldRow, now_ms: i64) -> Option<&'static str> {
    if !crate::handlers::device_api::time_request_id_ok(&row.id) {
        return Some("id");
    }
    if row.kind != "sms" && row.kind != "mms" {
        return Some("kind");
    }
    if !SENDER_CLASSES.contains(&row.sender_class.as_str()) {
        return Some("class");
    }
    if !sender_ok(&row.sender_class, &row.sender) {
        return Some("sender");
    }
    let window = row.expires_at.checked_sub(row.at);
    if row.at > now_ms.saturating_add(AT_AHEAD_MS)
        || !window.is_some_and(|w| (0..=WINDOW_MAX_MS).contains(&w))
    {
        return Some("time");
    }
    if !STATES.contains(&row.state.as_str()) {
        return Some("state");
    }
    // Code QA L2 (D4 rule 8): a sender the phone couldn't read, a group with a stranger or a picture from an unknown
    // server has no allow path, so it is only ever `blocked` - never held, pushed or shown with an empty sender.
    if matches!(
        row.sender_class.as_str(),
        "malformed" | "empty" | "group" | "bad_location"
    ) && row.state != "blocked"
    {
        return Some("state");
    }
    None
}

/// Forward only (§7): `held` may become `released`, `expired` or `dropped`; every other state is final.
fn moves_forward(from: &str, to: &str) -> bool {
    from == "held" && matches!(to, "released" | "expired" | "dropped")
}

/// `POST /api/devices/sms-held` (design 77 D16, §7): the phone's held and blocked senders. Every row is checked
/// first ([refusal]); one refused row answers 400 [HeldRefusal] and stores nothing. Then, in one `BEGIN IMMEDIATE`
/// transaction: a new id is stored unless the phone already has [HELD_ROWS_A_PHONE] rows (then `capped`, and one
/// `sms_held_capped` line in the security log, within the phone's hourly budget); a known id moves only forward,
/// never changing its sender or time - any other change is ignored and counted
/// (`devices.sms_ignored_transitions`). 200 [HeldAnswer]; 400 for a body that isn't the shape or more than
/// [HELD_ROWS_MAX] rows; 413 above [HELD_BODY_LIMIT]; 500 on a database error (the phone posts again).
pub async fn held(
    State(state): State<AppState>,
    Extension(AuthedDevice(device)): Extension<AuthedDevice>,
    body: axum::body::Bytes,
) -> Response {
    let Ok(body) = serde_json::from_slice::<HeldBody>(&body) else {
        return StatusCode::BAD_REQUEST.into_response();
    };
    let refuse = |refused: usize, reason: &'static str| {
        (
            StatusCode::BAD_REQUEST,
            axum::Json(HeldRefusal { refused, reason }),
        )
            .into_response()
    };
    if body.rows.len() > HELD_ROWS_MAX {
        return refuse(HELD_ROWS_MAX, "rows");
    }
    let now_ms = chrono::Utc::now().timestamp_millis();
    let mut rows = Vec::with_capacity(body.rows.len());
    for (index, value) in body.rows.into_iter().enumerate() {
        let Ok(row) = serde_json::from_value::<HeldRow>(value) else {
            return refuse(index, "rows");
        };
        if let Some(reason) = refusal(&row, now_ms) {
            return refuse(index, reason);
        }
        rows.push(row);
    }
    match store(&state.db, device.id, &rows).await {
        Ok((answer, raised)) => {
            if raised {
                state.push.wake();
            }
            axum::Json(answer).into_response()
        }
        Err(err) => {
            tracing::error!(device_id = device.id, %err, "can't store the held senders");
            StatusCode::INTERNAL_SERVER_ERROR.into_response()
        }
    }
}

/// Stores the rows (see [held]); `true` when a parent event was raised (the caller wakes the push sender after the
/// commit). Design 77 step 4: the first sight of a `held` row raises `sms_held` (pushed while its window is open,
/// within the kind's budget); a row that moves on (`released`, `expired`, `dropped`) marks its event read for every
/// parent.
async fn store(
    db: &sqlx::SqlitePool,
    device_id: i64,
    rows: &[HeldRow],
) -> Result<(HeldAnswer, bool), sqlx::Error> {
    let mut tx = db.begin_with("BEGIN IMMEDIATE").await?;
    let phone: String = sqlx::query_scalar("SELECT name FROM devices WHERE id = ?")
        .bind(device_id)
        .fetch_optional(&mut *tx)
        .await?
        .unwrap_or_default();
    let now = chrono::Utc::now();
    let mut raised = false;
    // Code QA L5: held and blocked rows are counted, and bounded, apart.
    let (mut held_stored, mut blocked_stored): (i64, i64) = sqlx::query_as(
        "SELECT COALESCE(SUM(state != 'blocked'), 0), COALESCE(SUM(state = 'blocked'), 0) \
         FROM device_sms_held WHERE device_id = ?",
    )
    .bind(device_id)
    .fetch_one(&mut *tx)
    .await?;
    let mut answer = HeldAnswer {
        accepted: 0,
        capped: false,
    };
    let mut ignored = 0_i64;
    for row in rows {
        let known: Option<String> = sqlx::query_scalar(
            "SELECT state FROM device_sms_held WHERE device_id = ? AND held_id = ?",
        )
        .bind(device_id)
        .bind(&row.id)
        .fetch_optional(&mut *tx)
        .await?;
        match known {
            None if row.state == "blocked" && blocked_stored >= BLOCKED_ROWS_A_PHONE => {
                answer.capped = true
            }
            None if row.state != "blocked" && held_stored >= HELD_ROWS_A_PHONE => {
                answer.capped = true
            }
            None => {
                sqlx::query(
                    "INSERT INTO device_sms_held \
                     (device_id, held_id, at, kind, sender_class, sender, expires_at, state) \
                     VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                )
                .bind(device_id)
                .bind(&row.id)
                .bind(row.at)
                .bind(&row.kind)
                .bind(&row.sender_class)
                .bind(&row.sender)
                .bind(row.expires_at)
                .bind(&row.state)
                .execute(&mut *tx)
                .await?;
                if row.state == "blocked" {
                    blocked_stored += 1;
                } else {
                    held_stored += 1;
                }
                if row.state == "held" {
                    raised |= raise_held(&mut tx, device_id, &phone, row, now).await?;
                }
                answer.accepted += 1;
            }
            Some(from) => {
                if moves_forward(&from, &row.state) {
                    sqlx::query(
                        "UPDATE device_sms_held SET state = ? WHERE device_id = ? AND held_id = ?",
                    )
                    .bind(&row.state)
                    .bind(device_id)
                    .bind(&row.id)
                    .execute(&mut *tx)
                    .await?;
                    // §7: a released, expired or dropped row's event is read - nothing is left to decide.
                    sqlx::query(
                        "INSERT OR IGNORE INTO parent_event_reads (admin_id, event_id) \
                         SELECT a.id, e.id FROM admin_users a JOIN parent_events e \
                         ON e.device_id = ? AND e.kind = 'sms_held' AND e.dedup_key = ?",
                    )
                    .bind(device_id)
                    .bind(&row.id)
                    .execute(&mut *tx)
                    .await?;
                } else if from != row.state {
                    ignored += 1;
                }
                answer.accepted += 1;
            }
        }
    }
    if ignored > 0 {
        sqlx::query(
            "UPDATE devices SET sms_ignored_transitions = sms_ignored_transitions + ? WHERE id = ?",
        )
        .bind(ignored)
        .bind(device_id)
        .execute(&mut *tx)
        .await?;
    }
    if answer.capped {
        security::record_phone_event(
            &mut tx,
            device_id,
            PhoneLine::SmsHeldCapped,
            Detail::words(
                "security.detail.sms_held_capped",
                &[
                    ("device", &device_id.to_string()),
                    ("max", &HELD_ROWS_A_PHONE.to_string()),
                ],
            ),
        )
        .await?;
    }
    tx.commit().await?;
    Ok((answer, raised))
}

/// D16's push at once (§7): a held row seen for the first time raises `sms_held` - pushed while its window is still
/// open by the server's clock (a late post of an expired one goes to the inbox only), within `Kind::push_budget`.
/// `true` when an event was stored.
async fn raise_held(
    conn: &mut sqlx::SqliteConnection,
    device_id: i64,
    phone: &str,
    row: &HeldRow,
    now: chrono::DateTime<chrono::Utc>,
) -> Result<bool, sqlx::Error> {
    let (Some(at), Some(expires_at)) = (
        chrono::DateTime::from_timestamp_millis(row.at),
        chrono::DateTime::from_timestamp_millis(row.expires_at),
    ) else {
        return Ok(false);
    };
    let push = if expires_at > now {
        crate::parent_events::Push::Now
    } else {
        crate::parent_events::Push::No
    };
    let event = crate::parent_events::sms_held(
        device_id,
        phone,
        &row.id,
        at.min(now),
        expires_at,
        &row.sender_class,
        &row.sender,
        push,
    );
    Ok(crate::parent_events::raise(conn, &event).await?.is_some())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn contract() -> serde_json::Value {
        serde_json::from_str(&std::fs::read_to_string("testdata/sms_contract.json").unwrap())
            .unwrap()
    }

    #[test]
    fn the_contract_matches_the_launchers_copy() {
        assert_eq!(
            std::fs::read("testdata/sms_contract.json").unwrap(),
            std::fs::read("../launcher/app/src/test/resources/sms_contract.json").unwrap(),
            "server/testdata/sms_contract.json and the launcher's copy differ"
        );
    }

    /// Code QA M2: a launcher without the engine does a pair exactly when the derived old keys say it - every
    /// `modes` row of the contract: what the old keys read back as (D2's table) equals the pair asked.
    #[test]
    fn the_older_launcher_expresses_what_the_old_keys_say() {
        assert_eq!(contract()["capability"], SMS_ENGINE_CAPABILITY);
        assert_eq!(contract()["groups_capability"], SMS_GROUPS_CAPABILITY);
        for row in contract()["modes"].as_array().unwrap() {
            let (calls, sms) = (
                row["calls_mode"].as_str().unwrap(),
                row["sms_mode"].as_str().unwrap(),
            );
            let (managed, calls_on, sms_on) = crate::models::derived_old_keys(calls, sms);
            let read_back = match (managed, calls_on, sms_on) {
                (false, _, _) => ("unmanaged", "unmanaged"),
                (true, c, s) => (
                    if c { "managed" } else { "off" },
                    if s { "unmanaged" } else { "off" },
                ),
            };
            assert_eq!(
                older_launcher_expresses(calls, sms),
                read_back == (calls, sms),
                "{calls} {sms}"
            );
        }
        assert_eq!(older_launcher_loses("managed", "off"), None);
        assert_eq!(
            older_launcher_loses("unmanaged", "managed"),
            Some((true, true))
        );
        assert_eq!(
            older_launcher_loses("managed", "managed"),
            Some((true, false))
        );
        assert_eq!(older_launcher_loses("off", "managed"), Some((true, false)));
        assert_eq!(
            older_launcher_loses("unmanaged", "off"),
            Some((false, true))
        );
    }

    /// Design 82 D3: Meldinger's lock level. Every `app.lock` row - the stored choice and `sms_mode` - gives its
    /// `lock_glance.sms` and the derived `call_policy.sms_lock_text`; and in `app.lock_read` (the launcher's reading,
    /// D4) each word this server sends is read as itself, whatever the call policy says.
    #[test]
    fn the_app_lock_rows() {
        use crate::lock_glance::{LockGlancePolicy, SMS_NAME, SMS_OFF, SMS_TEXT, sms_level};
        let app = &contract()["app"];
        assert_eq!(app["key"], crate::builtin::MESSAGES);
        assert_eq!(
            app["capability"],
            crate::lock_glance::MESSAGES_APP_CAPABILITY
        );
        for row in app["lock"].as_array().unwrap() {
            let sms = sms_level(row["stored"].as_bool(), row["sms_mode"].as_str().unwrap());
            assert_eq!(row["sms"], sms, "{row}");
            let policy = LockGlancePolicy {
                apps: Vec::new(),
                sms,
            };
            assert_eq!(row["sms_lock_text"], policy.sms_lock_text(), "{row}");
        }
        let mut sent = std::collections::BTreeSet::new();
        for row in app["lock_read"].as_array().unwrap() {
            let word = row["lock_glance"].get("sms").and_then(|word| word.as_str());
            if let Some(word) = word.filter(|word| [SMS_OFF, SMS_NAME, SMS_TEXT].contains(word)) {
                assert_eq!(row["level"], word, "{row}");
                assert_eq!(row["sms"], word, "{row}");
                sent.insert(word.to_string());
            }
        }
        assert_eq!(sent.len(), 3, "every word the server sends has a row");
    }

    /// Whether two names are the same sender name: equal folds of the cleaned texts.
    fn same_name(a: &str, b: &str) -> bool {
        fold(&clean_name(a)) == fold(&clean_name(b))
    }

    #[test]
    fn the_name_rows() {
        let contract = contract();
        for row in contract["names"].as_array().unwrap() {
            let name = row["name"].as_str().unwrap();
            assert_eq!(
                valid_name(name),
                row["valid"].as_bool().unwrap(),
                "{name:?}"
            );
            if let Some(folded) = row["fold"].as_str() {
                assert_eq!(fold(&clean_name(name)), folded, "{name:?}");
            }
        }
        for row in contract["name_matches"].as_array().unwrap() {
            let (listed, sender) = (
                row["listed"].as_str().unwrap(),
                row["sender"].as_str().unwrap(),
            );
            assert_eq!(
                same_name(listed, sender),
                row["match"].as_bool().unwrap(),
                "{listed:?} {sender:?}"
            );
        }
    }

    #[test]
    fn the_sender_rows() {
        let contract = contract();
        for row in contract["intake"].as_array().unwrap() {
            let (class, sender) = (
                row["class"].as_str().unwrap(),
                row["sender"].as_str().unwrap(),
            );
            assert_eq!(
                sender_ok(class, sender),
                row["ok"].as_bool().unwrap(),
                "{class} {sender:?}"
            );
        }
        // What the phone's cleaning gives always passes the intake.
        for row in contract["clean"].as_array().unwrap() {
            let (class, sender) = (
                row["class"].as_str().unwrap(),
                row["sender"].as_str().unwrap(),
            );
            assert!(sender_ok(class, sender), "{class} {sender:?}");
        }
    }

    #[test]
    fn the_report_keeps_every_key() {
        let contract = contract();
        let state = &contract["report"]["sms_state"];
        let kept: serde_json::Value =
            serde_json::from_str(&sanitize_state(state).unwrap()).unwrap();
        assert_eq!(&kept, state);
        // Content or an unknown key never gets through; a bad number is dropped.
        let mut hostile = state.clone();
        hostile["body"] = "hei".into();
        hostile["blocked"]["texts"] = 3.into();
        hostile["own_numbers"][0]["number"] = "<b>".into();
        let kept: serde_json::Value =
            serde_json::from_str(&sanitize_state(&hostile).unwrap()).unwrap();
        assert!(kept.get("body").is_none());
        assert!(kept["blocked"].get("texts").is_none());
        assert_eq!(kept["own_numbers"].as_array().unwrap().len(), 1);
        // Design 80 §4.4: `own_learned` - the conflict row and `null` kept exactly; a bad number drops the key; at most
        // two in the conflict.
        let mut conflict = state.clone();
        conflict["own_learned"] = contract["report"]["own_learned_conflict"].clone();
        let kept: serde_json::Value =
            serde_json::from_str(&sanitize_state(&conflict).unwrap()).unwrap();
        assert_eq!(kept, conflict);
        conflict["own_learned"] = serde_json::Value::Null;
        let kept: serde_json::Value =
            serde_json::from_str(&sanitize_state(&conflict).unwrap()).unwrap();
        assert!(kept["own_learned"].is_null());
        for bad in [
            serde_json::json!({"number": "<b>", "from": "+4791234567", "at": 1, "conflict": []}),
            serde_json::json!({"number": "1881", "from": "+4791234567", "at": 1, "conflict": []}),
            serde_json::json!({"number": "+4790000002", "from": "x", "at": 1, "conflict": []}),
            serde_json::json!({"number": "+4790000002", "from": "+4791234567", "at": -1, "conflict": []}),
            serde_json::json!({"number": null, "from": "+4791234567", "at": 1, "conflict": ["<b>"]}),
        ] {
            conflict["own_learned"] = bad.clone();
            let kept: serde_json::Value =
                serde_json::from_str(&sanitize_state(&conflict).unwrap()).unwrap();
            assert!(kept.get("own_learned").is_none(), "{bad}");
        }
        conflict["own_learned"] = serde_json::json!({"number": null, "from": "+4791234567", "at": 1,
            "conflict": ["+4790000002", "+4790000003", "+4790000004"]});
        let kept: serde_json::Value =
            serde_json::from_str(&sanitize_state(&conflict).unwrap()).unwrap();
        assert_eq!(kept["own_learned"]["conflict"].as_array().unwrap().len(), 2);
    }

    #[test]
    fn states_move_only_forward() {
        for to in ["released", "expired", "dropped"] {
            assert!(moves_forward("held", to));
        }
        for from in ["released", "expired", "dropped", "blocked"] {
            for to in STATES {
                assert!(!moves_forward(from, to), "{from} -> {to}");
            }
        }
        assert!(!moves_forward("held", "blocked"));
    }

    #[test]
    fn the_times_are_checked_against_the_servers_clock() {
        let now = 1_793_800_000_000;
        let row = |at: i64, expires_at: i64| HeldRow {
            id: "0123456789abcdef".into(),
            at,
            kind: "sms".into(),
            sender_class: "number".into(),
            sender: "+4791234567".into(),
            expires_at,
            state: "held".into(),
        };
        assert_eq!(refusal(&row(now, now + 86_400_000), now), None);
        assert_eq!(
            refusal(&row(now + AT_AHEAD_MS, now + AT_AHEAD_MS), now),
            None
        );
        assert_eq!(
            refusal(&row(now + AT_AHEAD_MS + 1, now + AT_AHEAD_MS + 1), now),
            Some("time")
        );
        assert_eq!(refusal(&row(now, now - 1), now), Some("time"));
        assert_eq!(
            refusal(&row(now, now + WINDOW_MAX_MS + 1), now),
            Some("time")
        );
        assert_eq!(refusal(&row(i64::MIN, i64::MAX), now), Some("time"));
    }
}
