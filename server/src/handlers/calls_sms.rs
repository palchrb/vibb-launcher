//! Design 77 step 4 (`docs/design/77-sms-mms-client.md` §7, D12, D16, D17): the Calls page's SMS parts beside the
//! two channel rows and the contacts' "Meldinger" groups (`handlers::calls`):
//! - "Meldinger som venter" ("Messages waiting", D16): the held senders the phone posted (`device_sms_held`), who and
//!   when - never what -, each row labelled "Nummer …" or "Avsendernavn «…»", with the narrow allows ("Opprett
//!   kontakt…" opens Add Contact with only "Kan sende melding til" on; "Tillat meldinger fra {contact}" turns that
//!   switch alone on; "Tillat avsendernavnet" adds the name, behind D17's warning) and "Slett nå"; "Slett alle som
//!   venter" at the foot. An emergency number, or a name without a letter, offers only "Slett nå", with why (QA
//!   R2-H1); a row past its `expires_at` by the server's clock reads "Utløpt" with no action (R2-L2).
//! - "Blokkerte meldinger" (folded): the `blocked` rows of the last 30 days.
//! - The SMS settings (one form): the phone's own number per SIM (D12) and "Vis meldingstekst på låseskjermen"
//!   (`sms_lock_text`, §6.8).
//! - "Avsendernavn som slipper gjennom" (D17): the names, Add and Remove, the honest warning.
//!
//! Every write is one transaction scoped to the phone, answers 500 on a database error and nudges the phone
//! (`calls::saved`: the policy and `command_notify`), and returns to its own group (`#held-<id>`, `#held`,
//! `#sms-settings`, `#sms-names`); a refusal renders the page in place (400) or lands on the group's notice.

use std::collections::HashMap;

use axum::Form;
use axum::extract::{Path, State};
use axum::http::StatusCode;
use axum::response::{Redirect, Response};
use chrono::{DateTime, Utc};

use super::calls::{Shown, db_error, not_found, render, saved};
use crate::AppState;
use crate::display_time::Zone;
use crate::emergency;
use crate::i18n::{Lang, t, t_params};
use crate::phone;
use crate::sms;

/// A held or changed row stays on the card this long after the phone received it (§7: "goes after 7 days").
const SHOWN_DAYS: i64 = 7;
/// The blocked list shows this many days (retention keeps 30).
const BLOCKED_DAYS: i64 = 30;
/// At most this many rows on the card and in the blocked list.
const ROWS_MAX: i64 = 100;
/// D17: at most this many sender names a phone.
pub const NAMES_MAX: i64 = 50;

/// One row of "Meldinger som venter".
pub(super) struct HeldView {
    /// The phone's id; the row's anchor is `held-<id>`.
    pub id: String,
    /// "Nummer +4791234567" / "Avsendernavn «DNB»" (D16: the sender's type on every row).
    pub label: String,
    /// "SMS" / "Bilde".
    pub kind: &'static str,
    /// When the phone received it.
    pub when: String,
    /// "Venter til 14:05", "Levert", "Utløpt", ...
    pub status: String,
    /// The status is an end (delivered, expired, deleted): quieter.
    pub done: bool,
    /// Why nothing but "Slett nå" is offered (an emergency number, a name without a letter).
    pub reason: Option<&'static str>,
    /// "Opprett kontakt…": the Add Contact link's query value (the number, URL-encoded).
    pub create_number: Option<String>,
    /// "Tillat meldinger fra {contact}": the contact's name.
    pub allow_contact: Option<String>,
    /// "Tillat avsendernavnet".
    pub allow_name: bool,
    /// "Slett nå".
    pub can_drop: bool,
}

/// One row of "Blokkerte meldinger": who (or what kind of sender), the kind, when.
pub(super) struct BlockedView {
    pub label: String,
    pub kind: &'static str,
    pub when: String,
}

/// One listed sender name and its fold (the remove form's key).
pub(super) struct NameView {
    pub name: String,
    pub fold: String,
}

/// The SMS parts of the page.
pub(super) struct SmsPage {
    pub held: Vec<HeldView>,
    /// Rows still waiting for a decision ("Slett alle som venter" shows while there is one).
    pub waiting: usize,
    pub blocked: Vec<BlockedView>,
    pub names: Vec<NameView>,
    pub lock_text: bool,
    /// The parent's own numbers, slot 1 and 2 (empty: none).
    pub own: [String; 2],
}

/// A stored row as the card reads it.
#[derive(sqlx::FromRow)]
struct HeldRow {
    held_id: String,
    at: i64,
    kind: String,
    sender_class: String,
    sender: String,
    expires_at: i64,
    state: String,
    dropped_at: Option<String>,
}

fn kind_word(lang: Lang, kind: &str) -> &'static str {
    t(
        lang,
        if kind == "mms" {
            "device.sms.kind.mms"
        } else {
            "device.sms.kind.sms"
        },
    )
}

/// The sender with its type (D16, QA R2-H1/R2-M2): a number, a name in «», or a class word for a sender the phone
/// couldn't read (blocked rows only).
fn sender_label(lang: Lang, class: &str, sender: &str) -> String {
    match class {
        "number" | "short" => t_params(lang, "device.sms.sender.number", &[("number", sender)]),
        "alphanumeric" => t_params(lang, "device.sms.sender.name", &[("name", sender)]),
        "group" => t(lang, "device.sms.sender.group").to_string(),
        "bad_location" => t(lang, "device.sms.sender.bad_location").to_string(),
        "empty" => t(lang, "device.sms.sender.empty").to_string(),
        _ => t(lang, "device.sms.sender.malformed").to_string(),
    }
}

fn instant_of(ms: i64) -> DateTime<Utc> {
    DateTime::from_timestamp_millis(ms).unwrap_or_default()
}

/// Whether a held number is a phone-book contact here: its id, name and whether its texts may already come in.
async fn contact_by_number(
    db: &sqlx::SqlitePool,
    device_id: i64,
    number: &str,
) -> Result<Option<(i64, String, bool)>, sqlx::Error> {
    sqlx::query_as(
        "SELECT c.id, c.name, dc.sms_in FROM device_contacts dc JOIN contacts c ON c.id = dc.contact_id \
         WHERE dc.device_id = ? AND c.phone_number = ? LIMIT 1",
    )
    .bind(device_id)
    .bind(number)
    .fetch_optional(db)
    .await
}

/// The listed names' folds.
async fn listed_folds(db: &sqlx::SqlitePool, device_id: i64) -> Result<Vec<String>, sqlx::Error> {
    sqlx::query_scalar("SELECT fold FROM device_sms_names WHERE device_id = ?")
        .bind(device_id)
        .fetch_all(db)
        .await
}

/// Reads the SMS parts for the page in `zone`'s language at `now`, `cc` the default country code.
pub(super) async fn load(
    db: &sqlx::SqlitePool,
    device_id: i64,
    zone: &Zone,
    now: DateTime<Utc>,
    cc: &str,
) -> Result<SmsPage, sqlx::Error> {
    let lang = zone.lang();
    let now_ms = now.timestamp_millis();
    let rows: Vec<HeldRow> = sqlx::query_as(
        "SELECT held_id, at, kind, sender_class, sender, expires_at, state, dropped_at FROM device_sms_held \
         WHERE device_id = ? AND state != 'blocked' AND at > ? ORDER BY at DESC, held_id LIMIT ?",
    )
    .bind(device_id)
    .bind(now_ms - SHOWN_DAYS * 86_400_000)
    .bind(ROWS_MAX)
    .fetch_all(db)
    .await?;
    let folds = listed_folds(db, device_id).await?;
    let mut held = Vec::with_capacity(rows.len());
    let mut waiting = 0;
    for row in rows {
        let open = row.state == "held" && row.dropped_at.is_none() && row.expires_at > now_ms;
        let mut view = HeldView {
            label: sender_label(lang, &row.sender_class, &row.sender),
            kind: kind_word(lang, &row.kind),
            when: zone.when(instant_of(row.at), now),
            status: String::new(),
            done: !open,
            reason: None,
            create_number: None,
            allow_contact: None,
            allow_name: false,
            can_drop: open,
            id: row.held_id,
        };
        view.status = match row.state.as_str() {
            "released" => t(lang, "device.sms.held.released").to_string(),
            "expired" => t(lang, "device.sms.held.expired_deleted").to_string(),
            "dropped" => t(lang, "device.sms.held.dropped").to_string(),
            _ if row.dropped_at.is_some() => t(lang, "device.sms.held.dropped").to_string(),
            // R2-L2: past its window by the server's clock, even before the phone says so.
            _ if !open => t(lang, "device.sms.held.expired").to_string(),
            _ => t_params(
                lang,
                "device.sms.held.waits_until",
                &[("until", &zone.until(instant_of(row.expires_at), now))],
            ),
        };
        if open {
            waiting += 1;
            match row.sender_class.as_str() {
                "number" | "short" if emergency::is_emergency(&row.sender, cc) => {
                    view.reason = Some(t(lang, "device.sms.emergency_reason"));
                }
                "number" | "short" => match contact_by_number(db, device_id, &row.sender).await? {
                    Some((_, _, true)) => {
                        view.status = t(lang, "device.sms.held.allowed").to_string();
                    }
                    Some((_, name, false)) => view.allow_contact = Some(name),
                    None => {
                        view.create_number =
                            Some(form_urlencoded::byte_serialize(row.sender.as_bytes()).collect())
                    }
                },
                "alphanumeric" if !sms::valid_name(&row.sender) => {
                    view.reason = Some(t(lang, "device.sms.letterless_reason"));
                }
                "alphanumeric" => {
                    if folds.contains(&sms::fold(&sms::clean_name(&row.sender))) {
                        view.status = t(lang, "device.sms.held.allowed").to_string();
                    } else {
                        view.allow_name = true;
                    }
                }
                _ => {}
            }
        }
        held.push(view);
    }
    let blocked_rows: Vec<HeldRow> = sqlx::query_as(
        "SELECT held_id, at, kind, sender_class, sender, expires_at, state, dropped_at FROM device_sms_held \
         WHERE device_id = ? AND state = 'blocked' AND at > ? ORDER BY at DESC, held_id LIMIT ?",
    )
    .bind(device_id)
    .bind(now_ms - BLOCKED_DAYS * 86_400_000)
    .bind(ROWS_MAX)
    .fetch_all(db)
    .await?;
    let blocked = blocked_rows
        .into_iter()
        .map(|row| BlockedView {
            label: sender_label(lang, &row.sender_class, &row.sender),
            kind: kind_word(lang, &row.kind),
            when: zone.when(instant_of(row.at), now),
        })
        .collect();
    let names = sqlx::query_as::<_, (String, String)>(
        "SELECT name, fold FROM device_sms_names WHERE device_id = ? ORDER BY fold, name",
    )
    .bind(device_id)
    .fetch_all(db)
    .await?
    .into_iter()
    .map(|(name, fold)| NameView { name, fold })
    .collect();
    let lock_text: bool =
        sqlx::query_scalar("SELECT sms_lock_text FROM device_policy WHERE device_id = ?")
            .bind(device_id)
            .fetch_optional(db)
            .await?
            .unwrap_or(false);
    let mut own = [String::new(), String::new()];
    for number in sqlx::query_as::<_, sms::OwnNumber>(
        "SELECT slot, number FROM device_own_numbers WHERE device_id = ? ORDER BY slot",
    )
    .bind(device_id)
    .fetch_all(db)
    .await?
    {
        if let Some(cell) = usize::try_from(number.slot - 1)
            .ok()
            .and_then(|i| own.get_mut(i))
        {
            *cell = number.number;
        }
    }
    Ok(SmsPage {
        held,
        waiting,
        blocked,
        names,
        lock_text,
        own,
    })
}

/// The device page's row (§7): "2 meldinger venter · 3 blokkert denne uken", `None` when both are nought.
pub async fn summary_value(
    db: &sqlx::SqlitePool,
    device_id: i64,
    lang: Lang,
    now: DateTime<Utc>,
) -> Result<Option<String>, sqlx::Error> {
    let now_ms = now.timestamp_millis();
    let (waiting, blocked): (i64, i64) = sqlx::query_as(
        "SELECT COALESCE(SUM(state = 'held' AND dropped_at IS NULL AND expires_at > ?), 0), \
         COALESCE(SUM(state = 'blocked' AND at > ?), 0) FROM device_sms_held WHERE device_id = ?",
    )
    .bind(now_ms)
    .bind(now_ms - 7 * 86_400_000)
    .bind(device_id)
    .fetch_one(db)
    .await?;
    let mut parts = Vec::new();
    if waiting > 0 {
        parts.push(crate::i18n::t_count(
            lang,
            "device.summary.value.sms_waiting",
            waiting,
            &[],
        ));
    }
    if blocked > 0 {
        parts.push(crate::i18n::t_count(
            lang,
            "device.summary.value.sms_blocked",
            blocked,
            &[],
        ));
    }
    Ok((!parts.is_empty()).then(|| parts.join(" · ")))
}

/// When a full hold pool frees a place (§7's "Ventelisten er full … før {time}"): the earliest end of the rows still
/// held in the full pools (`numbers`: numbers and short codes, `names`: sender names) by the server's copy, `None`
/// when it has none.
pub(crate) async fn hold_full_until(
    db: &sqlx::SqlitePool,
    device_id: i64,
    numbers: bool,
    names: bool,
    now: DateTime<Utc>,
) -> Result<Option<DateTime<Utc>>, sqlx::Error> {
    let end: Option<i64> = sqlx::query_scalar(
        "SELECT MIN(expires_at) FROM device_sms_held WHERE device_id = ? AND state = 'held' \
         AND expires_at > ? AND ((? AND sender_class IN ('number', 'short')) \
         OR (? AND sender_class = 'alphanumeric'))",
    )
    .bind(device_id)
    .bind(now.timestamp_millis())
    .bind(numbers)
    .bind(names)
    .fetch_one(db)
    .await?;
    Ok(end.and_then(DateTime::from_timestamp_millis))
}

/// A held row's own group, or the card when it is gone.
fn held_row(id: i64, held_id: &str) -> String {
    format!("/devices/{id}/calls#held-{held_id}")
}

/// The card with a notice: a row that is no longer waiting, a full list of names. A refusal: nothing was written.
fn held_notice(id: i64, code: &str) -> Response {
    crate::settings_log::refused(Redirect::to(&format!(
        "/devices/{id}/calls?notice={code}#held"
    )))
}

/// `?notice=` codes for the waiting card.
pub(super) fn notice(lang: Lang, code: &str) -> Option<&'static str> {
    match code {
        "held_gone" => Some(t(lang, "device.sms.held.gone")),
        "names_full" => Some(t(lang, "device.sms.names.full")),
        _ => None,
    }
}

/// A held id as the phone writes it (16-64 lowercase hex); anything else is no row.
fn held_id_ok(held_id: &str) -> bool {
    crate::handlers::device_api::time_request_id_ok(held_id)
}

/// What an allow does, decided from the stored row - never from the form (QA R2-H1, R2-M1).
enum Allow {
    /// A contact on the phone without "Kan sende melding til": that switch alone.
    Contact(i64),
    /// A sender name with a letter: added to the list (D17).
    Name(String),
}

/// "Tillat meldinger fra {contact}" or "Tillat avsendernavnet" (D16 "The allow"): grants exactly one thing, this
/// sender's texts. Only a row still waiting by the server's clock; never an emergency number or a name without a
/// letter; a number not in the phone book has "Opprett kontakt…" instead. Back to the row (`#held-<id>`); a row that
/// is no longer waiting, or nothing to allow, lands on the card's notice; past 50 names, "the list is full".
pub async fn allow_held(
    State(state): State<AppState>,
    lang: Lang,
    Path((id, held_id)): Path<(i64, String)>,
) -> Response {
    if !held_id_ok(&held_id) {
        return held_notice(id, "held_gone");
    }
    let result = async {
        let mut tx = state.db.begin().await?;
        let cc: Option<String> = sqlx::query_scalar(
            "SELECT s.default_country_code FROM device_policy p, call_settings s \
             WHERE p.device_id = ? AND s.id = 1",
        )
        .bind(id)
        .fetch_optional(&mut *tx)
        .await?;
        let Some(cc) = cc else {
            return Ok::<_, sqlx::Error>(None);
        };
        let row: Option<(String, String)> = sqlx::query_as(
            "SELECT sender_class, sender FROM device_sms_held WHERE device_id = ? AND held_id = ? \
             AND state = 'held' AND dropped_at IS NULL AND expires_at > ?",
        )
        .bind(id)
        .bind(&held_id)
        .bind(Utc::now().timestamp_millis())
        .fetch_optional(&mut *tx)
        .await?;
        let allow = match row {
            Some((class, sender)) if class == "number" || class == "short" => {
                if emergency::is_emergency(&sender, &cc) {
                    None
                } else {
                    sqlx::query_scalar::<_, i64>(
                        "SELECT dc.contact_id FROM device_contacts dc JOIN contacts c ON c.id = dc.contact_id \
                         WHERE dc.device_id = ? AND c.phone_number = ? LIMIT 1",
                    )
                    .bind(id)
                    .bind(&sender)
                    .fetch_optional(&mut *tx)
                    .await?
                    .map(Allow::Contact)
                }
            }
            Some((class, sender)) if class == "alphanumeric" && sms::valid_name(&sender) => {
                Some(Allow::Name(sms::clean_name(&sender)))
            }
            _ => None,
        };
        let outcome = match allow {
            None => "held_gone",
            Some(Allow::Contact(contact)) => {
                sqlx::query(
                    "UPDATE device_contacts SET sms_in = 1 WHERE device_id = ? AND contact_id = ?",
                )
                .bind(id)
                .bind(contact)
                .execute(&mut *tx)
                .await?;
                ""
            }
            Some(Allow::Name(name)) => insert_name(&mut tx, id, &name).await?,
        };
        if outcome.is_empty() {
            tx.commit().await?;
        }
        Ok(Some(outcome))
    }
    .await;
    match result {
        Ok(None) => not_found(lang),
        Ok(Some("")) => saved(&state, id, held_row(id, &held_id)),
        Ok(Some(code)) => held_notice(id, code),
        Err(err) => db_error(lang, id, err),
    }
}

/// Adds a cleaned, valid name unless its fold is listed already (a no-op, still a success) or the phone has 50:
/// `""` on success, else the notice code.
async fn insert_name(
    tx: &mut sqlx::SqliteConnection,
    id: i64,
    name: &str,
) -> Result<&'static str, sqlx::Error> {
    let fold = sms::fold(name);
    let (count, listed): (i64, bool) = sqlx::query_as(
        "SELECT COUNT(*), COALESCE(MAX(fold = ?), 0) FROM device_sms_names WHERE device_id = ?",
    )
    .bind(&fold)
    .bind(id)
    .fetch_one(&mut *tx)
    .await?;
    if listed {
        return Ok("");
    }
    if count >= NAMES_MAX {
        return Ok("names_full");
    }
    sqlx::query("INSERT INTO device_sms_names (device_id, name, fold) VALUES (?, ?, ?)")
        .bind(id)
        .bind(name)
        .bind(&fold)
        .execute(&mut *tx)
        .await?;
    Ok("")
}

/// "Slett nå" (D16): the row's id goes into the policy's `sms_held_drop` (24 hours), and the phone deletes the
/// message at its next policy. Back to the row, which then reads "Slettet"; a row no longer waiting lands on the
/// card's notice.
pub async fn drop_held(
    State(state): State<AppState>,
    lang: Lang,
    Path((id, held_id)): Path<(i64, String)>,
) -> Response {
    if !held_id_ok(&held_id) {
        return held_notice(id, "held_gone");
    }
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
        let dropped = sqlx::query(
            "UPDATE device_sms_held SET dropped_at = datetime('now') WHERE device_id = ? AND held_id = ? \
             AND state = 'held' AND dropped_at IS NULL",
        )
        .bind(id)
        .bind(&held_id)
        .execute(&mut *tx)
        .await?
        .rows_affected();
        tx.commit().await?;
        Ok(Some(dropped > 0))
    }
    .await;
    match result {
        Ok(None) => not_found(lang),
        Ok(Some(true)) => saved(&state, id, held_row(id, &held_id)),
        Ok(Some(false)) => held_notice(id, "held_gone"),
        Err(err) => db_error(lang, id, err),
    }
}

/// "Slett alle som venter" (D16, QA R2-M4): every row still waiting goes into `sms_held_drop` at once. Back to the
/// card.
pub async fn drop_all_held(
    State(state): State<AppState>,
    lang: Lang,
    Path(id): Path<i64>,
) -> Response {
    let result = async {
        let mut tx = state.db.begin().await?;
        let device: bool =
            sqlx::query_scalar("SELECT EXISTS(SELECT 1 FROM device_policy WHERE device_id = ?)")
                .bind(id)
                .fetch_one(&mut *tx)
                .await?;
        if device {
            sqlx::query(
                "UPDATE device_sms_held SET dropped_at = datetime('now') WHERE device_id = ? \
                 AND state = 'held' AND dropped_at IS NULL",
            )
            .bind(id)
            .execute(&mut *tx)
            .await?;
        }
        tx.commit().await?;
        Ok::<_, sqlx::Error>(device)
    }
    .await;
    match result {
        Ok(false) => not_found(lang),
        Ok(true) => saved(&state, id, format!("/devices/{id}/calls#held")),
        Err(err) => db_error(lang, id, err),
    }
}

/// A refused SMS settings save: the field (`own_number_1`/`own_number_2`), why, and what the form sent.
pub(super) struct SmsRefusal<'a> {
    pub field: &'static str,
    pub why: String,
    pub sent: &'a HashMap<String, String>,
}

/// The SMS settings, one form with every field (§5.6: a missing switch is off, a missing number is none): the
/// lock-text switch and the phone's own number per SIM (D12), each normalised like a contact (`phone::normalize`
/// with the default country code). A number that doesn't normalise renders the page (400) with what was typed and
/// the reason under its field. Back to `#sms-settings`.
pub async fn save_sms(
    State(state): State<AppState>,
    lang: Lang,
    Path(id): Path<i64>,
    Form(form): Form<HashMap<String, String>>,
) -> Response {
    let cc: String =
        match sqlx::query_scalar("SELECT default_country_code FROM call_settings WHERE id = 1")
            .fetch_one(&state.db)
            .await
        {
            Ok(cc) => cc,
            Err(err) => return db_error(lang, id, err),
        };
    let mut own: Vec<(i64, String)> = Vec::new();
    for (slot, field) in [(1, "own_number_1"), (2, "own_number_2")] {
        let raw = form.get(field).map(|s| s.trim()).unwrap_or("");
        if raw.is_empty() {
            continue;
        }
        match phone::normalize(raw, &cc) {
            Ok(number) if sms::sender_ok("number", &number) => own.push((slot, number)),
            outcome => {
                let why = match outcome {
                    Err(err) => t(lang, err.key()),
                    Ok(_) => t(lang, "device.calls.number.bad_length"),
                };
                let shown = Shown {
                    sms: Some(SmsRefusal {
                        field,
                        why: t_params(lang, "device.calls.number_refused", &[("why", why)]),
                        sent: &form,
                    }),
                    ..Default::default()
                };
                return render(&state, id, StatusCode::BAD_REQUEST, shown, lang).await;
            }
        }
    }
    let lock_text = form.contains_key("sms_lock_text");
    let result = async {
        let mut tx = state.db.begin().await?;
        let updated = sqlx::query(
            "UPDATE device_policy SET sms_lock_text = ?, updated_at = datetime('now') WHERE device_id = ?",
        )
        .bind(lock_text)
        .bind(id)
        .execute(&mut *tx)
        .await?
        .rows_affected();
        if updated == 0 {
            return Ok::<_, sqlx::Error>(false);
        }
        sqlx::query("DELETE FROM device_own_numbers WHERE device_id = ?")
            .bind(id)
            .execute(&mut *tx)
            .await?;
        for (slot, number) in &own {
            sqlx::query("INSERT INTO device_own_numbers (device_id, slot, number) VALUES (?, ?, ?)")
                .bind(id)
                .bind(slot)
                .bind(number)
                .execute(&mut *tx)
                .await?;
        }
        tx.commit().await?;
        Ok(true)
    }
    .await;
    match result {
        Ok(false) => not_found(lang),
        Ok(true) => saved(&state, id, format!("/devices/{id}/calls#sms-settings")),
        Err(err) => db_error(lang, id, err),
    }
}

/// "Legg til navn…" (D17): 1-11 characters after the shared cleaner and a letter (`sms::valid_name`), not listed
/// already by the fold, at most 50 a phone. A refusal renders the page (400) with the form open, what was typed and
/// why. Back to `#sms-names`.
pub async fn add_name(
    State(state): State<AppState>,
    lang: Lang,
    Path(id): Path<i64>,
    Form(form): Form<HashMap<String, String>>,
) -> Response {
    let raw = form.get("name").map(String::as_str).unwrap_or("");
    let refuse = |why: &'static str| {
        let state = &state;
        async move {
            let shown = Shown {
                name_add: Some((raw, t(lang, why))),
                ..Default::default()
            };
            render(state, id, StatusCode::BAD_REQUEST, shown, lang).await
        }
    };
    if !sms::valid_name(raw) {
        return refuse("device.sms.names.invalid").await;
    }
    let name = sms::clean_name(raw);
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
        let listed: bool = sqlx::query_scalar(
            "SELECT EXISTS(SELECT 1 FROM device_sms_names WHERE device_id = ? AND fold = ?)",
        )
        .bind(id)
        .bind(sms::fold(&name))
        .fetch_one(&mut *tx)
        .await?;
        if listed {
            return Ok(Some("device.sms.names.listed"));
        }
        match insert_name(&mut tx, id, &name).await? {
            "" => {
                tx.commit().await?;
                Ok(Some(""))
            }
            _ => Ok(Some("device.sms.names.full")),
        }
    }
    .await;
    match result {
        Ok(None) => not_found(lang),
        Ok(Some("")) => saved(&state, id, format!("/devices/{id}/calls#sms-names")),
        Ok(Some(why)) => refuse(why).await,
        Err(err) => db_error(lang, id, err),
    }
}

/// Removes a listed name by its fold. Back to `#sms-names`, also when it was already gone.
pub async fn remove_name(
    State(state): State<AppState>,
    lang: Lang,
    Path(id): Path<i64>,
    Form(form): Form<HashMap<String, String>>,
) -> Response {
    let fold = form.get("fold").map(String::as_str).unwrap_or("");
    let result = async {
        let mut tx = state.db.begin().await?;
        let device: bool =
            sqlx::query_scalar("SELECT EXISTS(SELECT 1 FROM device_policy WHERE device_id = ?)")
                .bind(id)
                .fetch_one(&mut *tx)
                .await?;
        if device {
            sqlx::query("DELETE FROM device_sms_names WHERE device_id = ? AND fold = ?")
                .bind(id)
                .bind(fold)
                .execute(&mut *tx)
                .await?;
        }
        tx.commit().await?;
        Ok::<_, sqlx::Error>(device)
    }
    .await;
    match result {
        Ok(false) => not_found(lang),
        Ok(true) => saved(&state, id, format!("/devices/{id}/calls#sms-names")),
        Err(err) => db_error(lang, id, err),
    }
}

/// The emergency-number rule for a contact's "Kan sende melding til" (D3, QA R2-H1): anyone can send as one.
pub(super) fn texts_in_refused(number: &str, cc: &str) -> bool {
    emergency::is_emergency(number, cc)
}
