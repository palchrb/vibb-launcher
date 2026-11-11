//! Design 77 step 4 (`docs/design/77-sms-mms-client.md` §7, D12, D16, D17): the Calls page's SMS parts beside the
//! two channel rows and the contacts' "Meldinger" groups (`handlers::calls`):
//! - "Meldinger som venter" ("Messages waiting", D16): the held senders the phone posted (`device_sms_held`), who and
//!   when - never what -, each row labelled "Nummer …" or "Avsendernavn «…»", with the narrow allows ("Opprett
//!   kontakt…" opens Add Contact with only "Kan sende melding til" on; "Tillat meldinger fra {contact}" turns that
//!   switch alone on; "Tillat avsendernavnet" adds the name, behind D17's warning) and "Slett nå"; "Slett alle som
//!   venter" at the foot. An emergency number, or a name without a letter, offers only "Slett nå", with why (QA
//!   R2-H1); a row past its `expires_at` by the server's clock reads "Utløpt" with no action (R2-L2).
//! - "Blokkerte meldinger" (folded): the `blocked` rows of the last 30 days.
//! - The SMS settings (one form): the phone's own number per SIM (D12) and "Gruppemeldinger". The message text on
//!   the lock (§6.8's `sms_lock_text`) is no longer here: since design 82 Meldinger's lock level is a row under Lock
//!   Screen & PINs' Notifications, and this group has a link to it while SMS is managed (D8). A stale page's
//!   `sms_lock_text` is not read.
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
    /// Design 80 D7 (M5): on a group row, the condition that blocks every group now, if one does.
    pub reason: Option<&'static str>,
}

/// One of the phone's own numbers on the page (design 80 §4.5): a slot of an active SIM in the last report, or a
/// stored slot.
pub(super) struct OwnRow {
    pub slot: i64,
    /// The field's name, `own_number_<slot>`.
    pub field: String,
    /// "Telefonens nummer" (one SIM), "Telefonens nummer, SIM 2", or "SIM 2 - ikke i telefonen nå" (L5).
    pub label: String,
    /// The field's value: the stored number (or what a refused form sent), empty for none.
    pub value: String,
    /// What the page drew for the slot - the stored number, empty for none - posted back as `own_number_<n>_shown`, so
    /// a stale page never writes a slot it didn't change (code QA L3).
    pub shown: String,
    /// The number is known: drawn as a row with its source and "Endre…" around the field, instead of the field.
    pub known: Option<KnownOwn>,
    /// The field's hint (no number known, a conflict).
    pub hint: Option<String>,
    /// A refused save's why, under the field (its "Endre…" then open).
    pub error: Option<String>,
    /// Design 80 §4.4: a conflict's two numbers, each with its "Bruk" form: `(form id, source, number)`.
    pub uses: Vec<(String, String, String)>,
}

/// What the phone's newest report says about its own numbers (design 77 D12, design 80 §4.4): each active SIM's slot and
/// number (`None` while the SIM doesn't know; empty when the report has none - an older launcher, a new phone), and the
/// number it learned from a message.
pub(crate) struct ReportedOwn {
    pub sims: Vec<(i64, Option<String>)>,
    pub learned: Option<LearnedOwn>,
}

/// `sms_state.own_learned` (kept by `sms::sanitize_state`): the number in use (`None` during a conflict), its teacher
/// and the two numbers of a conflict.
pub(crate) struct LearnedOwn {
    pub number: Option<String>,
    pub from: String,
    pub conflict: Vec<String>,
}

/// A known own number: the number, where it is from ("Fra SIM-kortet", "Lagret"), and the "Bruk dette nummeret"
/// source when the parent can store it (`sim:<slot>`).
pub(super) struct KnownOwn {
    pub number: String,
    pub source: String,
    pub use_source: Option<String>,
}

/// Code QA L4: a reported number the page may offer with "Bruk dette nummeret" - one the tap stores: `phone::normalize`
/// with the default country code leaves it as it is, and it is a full `+` number ([own_number_ok]). One rule for the
/// page and the route.
fn usable_own(number: &str, cc: &str) -> bool {
    phone::normalize(number, cc).is_ok_and(|n| n == number) && own_number_ok(number, cc)
}

/// [ReportedOwn] from the phone's newest status report, every number by [usable_own] (`cc` the default country code):
/// a SIM number that isn't usable reads as unknown, a learned one as none.
pub(crate) async fn reported_own(
    db: &sqlx::SqlitePool,
    device_id: i64,
    cc: &str,
) -> Result<ReportedOwn, sqlx::Error> {
    let latest: Option<Option<String>> = sqlx::query_scalar(
        "SELECT sms_state_json FROM device_status WHERE device_id = ? \
         ORDER BY reported_at DESC, id DESC LIMIT 1",
    )
    .bind(device_id)
    .fetch_optional(db)
    .await?;
    let state: serde_json::Value = latest
        .flatten()
        .and_then(|json| serde_json::from_str(&json).ok())
        .unwrap_or_default();
    let mut sims: Vec<(i64, Option<String>)> = state
        .get("own_numbers")
        .and_then(serde_json::Value::as_array)
        .into_iter()
        .flatten()
        .filter_map(|row| {
            let slot = row.get("slot")?.as_i64().filter(|s| (1..=2).contains(s))?;
            let number = row
                .get("number")
                .and_then(serde_json::Value::as_str)
                .filter(|n| usable_own(n, cc))
                .map(str::to_string);
            Some((slot, number))
        })
        .collect();
    sims.sort();
    sims.dedup_by_key(|(slot, _)| *slot);
    let number = |v: &serde_json::Value| {
        v.as_str()
            .filter(|n| sms::sender_ok("number", n))
            .map(str::to_string)
    };
    let usable = |v: &serde_json::Value| number(v).filter(|n| usable_own(n, cc));
    let learned = state
        .get("own_learned")
        .filter(|l| l.is_object())
        .and_then(|l| {
            Some(LearnedOwn {
                number: l.get("number").and_then(usable),
                from: l.get("from").and_then(number)?,
                conflict: l
                    .get("conflict")
                    .and_then(serde_json::Value::as_array)
                    .into_iter()
                    .flatten()
                    .filter_map(usable)
                    .take(2)
                    .collect(),
            })
        })
        .filter(|l| l.number.is_some() || l.conflict.len() == 2);
    Ok(ReportedOwn { sims, learned })
}

/// The phone's own numbers a contact may never have (design 80 L6): the parent's and what its SIMs reported. A number
/// the phone only learned from a message is not one (code QA L1): anyone can send the message that taught it, so it
/// never blocks a contact - and a contact with it clears it on the phone (`OwnLearning.current`).
pub(crate) async fn phone_numbers(
    db: &sqlx::SqlitePool,
    device_id: i64,
) -> Result<Vec<String>, sqlx::Error> {
    let mut numbers: Vec<String> =
        sqlx::query_scalar("SELECT number FROM device_own_numbers WHERE device_id = ?")
            .bind(device_id)
            .fetch_all(db)
            .await?;
    let cc: String =
        sqlx::query_scalar("SELECT default_country_code FROM call_settings WHERE id = 1")
            .fetch_one(db)
            .await?;
    let reported = reported_own(db, device_id, &cc).await?;
    numbers.extend(reported.sims.into_iter().filter_map(|(_, number)| number));
    Ok(numbers)
}

/// §4.4/§4.5 (code QA V2): what the phone learned is offered - and "Bruk dette nummeret" stores it - only while nothing
/// is stored (else `own_number_no_slot`), no SIM reports a number and the learned number isn't a contact on this phone
/// (else `own_number_gone`). One rule for the page and the route.
fn learned_offer<'a>(
    stored: &[(i64, String)],
    reported: &'a ReportedOwn,
    contacts: &HashMap<String, (String, bool)>,
) -> Result<&'a LearnedOwn, &'static str> {
    if !stored.is_empty() {
        return Err("own_number_no_slot");
    }
    if reported.sims.iter().any(|(_, number)| number.is_some()) {
        return Err("own_number_gone");
    }
    let learned = reported.learned.as_ref().ok_or("own_number_gone")?;
    if learned
        .number
        .as_ref()
        .is_some_and(|number| contacts.contains_key(number))
    {
        return Err("own_number_gone");
    }
    Ok(learned)
}

/// §4.5's rows for the stored numbers (`stored`, by slot) and the last report's SIMs (`sims`): one per slot in
/// question - an active SIM's or a stored one - and one field for SIM 1 while there is neither. "SIM 1" / "SIM 2" is
/// said only on a phone that reports two, or for a stored slot 2. Also whether the number is missing (no source at
/// all).
fn own_rows(
    lang: Lang,
    stored: &[(i64, String)],
    reported: &ReportedOwn,
    names: &HashMap<String, (String, bool)>,
) -> (Vec<OwnRow>, bool) {
    let sims = &reported.sims;
    let mut slots: Vec<i64> = sims
        .iter()
        .map(|(slot, _)| *slot)
        .chain(stored.iter().map(|(slot, _)| *slot))
        .collect();
    slots.sort();
    slots.dedup();
    if slots.is_empty() {
        slots.push(1);
    }
    let two = sims.len() > 1 || slots.contains(&2);
    let rows = slots
        .into_iter()
        .map(|slot| {
            let stored = stored
                .iter()
                .find(|(s, _)| *s == slot)
                .map(|(_, n)| n.clone());
            let sim = sims.iter().find(|(s, _)| *s == slot);
            let label = match sim {
                None if !sims.is_empty() => t_params(
                    lang,
                    "device.sms.settings.own_number_absent",
                    &[("slot", &slot.to_string())],
                ),
                _ if two => t_params(
                    lang,
                    "device.sms.settings.own_number",
                    &[("slot", &slot.to_string())],
                ),
                _ => t(lang, "device.sms.settings.own_number_one").to_string(),
            };
            let reported = sim.and_then(|(_, number)| number.clone());
            let (known, hint) = match (&stored, &reported) {
                // The SIM knows it and nothing is stored: the row, "Bruk dette nummeret" stores it (M3).
                (None, Some(number)) => (
                    Some(KnownOwn {
                        number: number.clone(),
                        source: t(lang, "device.sms.own.from_sim").to_string(),
                        use_source: Some(format!("sim:{slot}")),
                    }),
                    None,
                ),
                // Stored, and the SIM agrees or doesn't know.
                (Some(number), Some(sim)) if number == sim => (
                    Some(KnownOwn {
                        number: number.clone(),
                        source: t(lang, "device.sms.own.saved").to_string(),
                        use_source: None,
                    }),
                    None,
                ),
                (Some(number), None) if sim.is_some() => (
                    Some(KnownOwn {
                        number: number.clone(),
                        source: t(lang, "device.sms.own.saved").to_string(),
                        use_source: None,
                    }),
                    None,
                ),
                // Stored and the SIM says another (the mismatch finding above the groups), or a stored slot no
                // longer in the phone (L5): the field, so it can be corrected or cleared.
                (Some(_), _) => (None, None),
                // Nothing known: the field, saying what it costs.
                (None, None) if sim.is_some() => (
                    None,
                    Some(t(lang, "device.sms.own.unknown_hint").to_string()),
                ),
                // No report (an older launcher, a new phone).
                (None, None) => (
                    None,
                    Some(t(lang, "device.sms.settings.own_number_hint").to_string()),
                ),
            };
            OwnRow {
                slot,
                field: format!("own_number_{slot}"),
                label,
                shown: stored.clone().unwrap_or_default(),
                value: stored.unwrap_or_default(),
                known,
                hint,
                error: None,
                uses: Vec::new(),
            }
        })
        .collect::<Vec<_>>();
    let mut rows = rows;
    let unknown = stored.is_empty() && !sims.iter().any(|(_, number)| number.is_some());
    // §4.4/§4.5: nothing stored and no SIM knows it - what the phone learned from a message, on the first slot's row:
    // the number with its teacher named by the contact, or a conflict's two numbers to choose from.
    // A learned number that is a contact here isn't "us" (the phone drops it at its next judgement, code QA L1). The
    // route takes the same rule (`learned_offer`, code QA V2).
    let learned = learned_offer(stored, reported, names).ok();
    if let (Some(learned), Some(row)) = (learned, rows.first_mut()) {
        let teacher = names
            .get(&learned.from)
            .map_or(learned.from.as_str(), |(name, _)| name.as_str());
        match (&learned.number, learned.conflict.as_slice()) {
            (Some(number), _) => {
                row.known = Some(KnownOwn {
                    number: number.clone(),
                    source: t_params(lang, "device.sms.own.learned", &[("contact", teacher)]),
                    use_source: Some("learned".to_string()),
                });
                row.hint = None;
            }
            (None, [a, b]) => {
                row.hint = Some(t_params(
                    lang,
                    "device.sms.own.conflict",
                    &[("a", a.as_str()), ("b", b.as_str())],
                ));
                row.uses = vec![
                    (
                        format!("own-use-{}-0", row.slot),
                        "conflict:0".to_string(),
                        a.clone(),
                    ),
                    (
                        format!("own-use-{}-1", row.slot),
                        "conflict:1".to_string(),
                        b.clone(),
                    ),
                ];
            }
            _ => {}
        }
    }
    let missing = unknown && learned.is_none_or(|l| l.number.is_none());
    (rows, missing)
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
    /// Design 82 D8: Meldinger's level on the lock as the link row's value (its catalog key) - `Some` only while SMS
    /// is managed, when Lock Screen & PINs lists the row the link points to.
    pub lock_level: Option<&'static str>,
    /// Design 80 §4.5: the phone's own numbers, a row per slot in question.
    pub own_rows: Vec<OwnRow>,
    /// No number from any source: every group text is blocked (said on view while SMS is managed, D7/D8).
    pub number_missing: bool,
    /// Design 80 D13: "Gruppemeldinger" is on, and the phone claims `sms_groups_v1` (else the switch is disabled).
    pub groups: bool,
    pub groups_capable: bool,
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

/// The phone's contacts by number (one query for the card, code QA N1): each one's name and whether its texts may
/// already come in.
async fn contacts_by_number(
    db: &sqlx::SqlitePool,
    device_id: i64,
) -> Result<HashMap<String, (String, bool)>, sqlx::Error> {
    let rows: Vec<(String, String, bool)> = sqlx::query_as(
        "SELECT c.phone_number, c.name, dc.sms_in FROM device_contacts dc JOIN contacts c ON c.id = dc.contact_id \
         WHERE dc.device_id = ?",
    )
    .bind(device_id)
    .fetch_all(db)
    .await?;
    Ok(rows
        .into_iter()
        .map(|(number, name, sms_in)| (number, (name, sms_in)))
        .collect())
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
    let contacts = contacts_by_number(db, device_id).await?;
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
                "number" | "short" => match contacts.get(&row.sender) {
                    Some((_, true)) => {
                        view.status = t(lang, "device.sms.held.allowed").to_string();
                    }
                    Some((name, false)) => view.allow_contact = Some(name.clone()),
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
    let (sms_mode, groups): (String, bool) =
        sqlx::query_as("SELECT sms_mode, sms_groups FROM device_policy WHERE device_id = ?")
            .bind(device_id)
            .fetch_optional(db)
            .await?
            .unwrap_or((String::new(), true));
    let lock_level = if sms_mode == "managed" {
        let stored: Option<bool> = sqlx::query_scalar(
            "SELECT show_text FROM device_lock_glance_apps WHERE device_id = ? AND package_name = ?",
        )
        .bind(device_id)
        .bind(crate::builtin::MESSAGES)
        .fetch_optional(db)
        .await?;
        Some(crate::lock_glance::level_label(stored))
    } else {
        None
    };
    let stored: Vec<(i64, String)> = sqlx::query_as(
        "SELECT slot, number FROM device_own_numbers WHERE device_id = ? ORDER BY slot",
    )
    .bind(device_id)
    .fetch_all(db)
    .await?;
    let reported = reported_own(db, device_id, cc).await?;
    let (own_rows, number_missing) = own_rows(lang, &stored, &reported, &contacts);
    // M5: a group row says what blocks every group now - it can't tell a stranger's group from the others: the switch
    // off (D13), else a missing number while SMS is managed.
    let group_reason = if !groups {
        Some(t(lang, "device.sms.blocked.groups_off"))
    } else {
        (sms_mode == "managed" && number_missing)
            .then(|| t(lang, "device.sms.blocked.group_no_number"))
    };
    let blocked = blocked_rows
        .into_iter()
        .map(|row| BlockedView {
            reason: group_reason.filter(|_| row.sender_class == "group"),
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
    Ok(SmsPage {
        held,
        waiting,
        blocked,
        names,
        lock_level,
        own_rows,
        number_missing,
        groups,
        groups_capable: sms::has_capability(db, device_id, sms::SMS_GROUPS_CAPABILITY).await?,
    })
}

/// The device page's row (§7): "2 meldinger venter · 3 blokkert denne uken" and the Calls page's anchor it leads to,
/// `None` when both are nought. A picture waiting for the parent's word on its MMS address (design 83 §4) is a message
/// waiting too, and with no message held the row leads to those questions (`mmsc`), not to an empty `held`.
pub async fn summary_value(
    db: &sqlx::SqlitePool,
    device_id: i64,
    lang: Lang,
    now: DateTime<Utc>,
) -> Result<Option<(String, &'static str)>, sqlx::Error> {
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
    let questions = super::calls_mmsc::open_count(db, device_id).await?;
    let anchor = if waiting == 0 && questions > 0 {
        "mmsc"
    } else {
        "held"
    };
    let waiting = waiting + questions;
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
    Ok((!parts.is_empty()).then(|| (parts.join(" · "), anchor)))
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
        "held_no_contact" => Some(t(lang, "device.sms.held.no_contact")),
        "names_full" => Some(t(lang, "device.sms.names.full")),
        _ => None,
    }
}

/// `?notice=` codes for the "Meldinger" settings in the page's Avansert (design 80 §4.5).
pub(super) fn settings_notice(lang: Lang, code: &str) -> Option<&'static str> {
    match code {
        "own_number_gone" => Some(t(lang, "device.sms.own.gone")),
        "own_number_no_slot" => Some(t(lang, "device.sms.own.no_slot")),
        _ => None,
    }
}

/// Code QA L4: a parent's own allow or delete settles the message for every parent - its `sms_held` event is read
/// (the rows `held_ids` names), as the phone's `released`/`dropped` would make it.
async fn mark_read(
    tx: &mut sqlx::SqliteConnection,
    id: i64,
    held_ids: &[String],
) -> Result<(), sqlx::Error> {
    sqlx::query(
        "INSERT OR IGNORE INTO parent_event_reads (admin_id, event_id) \
         SELECT a.id, e.id FROM admin_users a JOIN parent_events e \
         ON e.device_id = ? AND e.kind = 'sms_held' AND e.dedup_key IN (SELECT value FROM json_each(?))",
    )
    .bind(id)
    .bind(serde_json::to_string(held_ids).expect("strings serialize"))
    .execute(&mut *tx)
    .await?;
    Ok(())
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
/// is no longer waiting, or nothing to allow, lands on the card's notice ("Opprett kontakt…"'s number without a contact
/// its own, code QA N3); past 50 names, "the list is full". The row's event is read for every parent (L4).
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
                    Err("held_gone")
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
                    .ok_or("held_no_contact")
                }
            }
            Some((class, sender)) if class == "alphanumeric" && sms::valid_name(&sender) => {
                Ok(Allow::Name(sms::clean_name(&sender)))
            }
            _ => Err("held_gone"),
        };
        let outcome = match allow {
            Err(code) => code,
            Ok(Allow::Contact(contact)) => {
                sqlx::query(
                    "UPDATE device_contacts SET sms_in = 1 WHERE device_id = ? AND contact_id = ?",
                )
                .bind(id)
                .bind(contact)
                .execute(&mut *tx)
                .await?;
                ""
            }
            Ok(Allow::Name(name)) => insert_name(&mut tx, id, &name).await?,
        };
        if outcome.is_empty() {
            mark_read(&mut tx, id, std::slice::from_ref(&held_id)).await?;
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

/// "Slett nå" (D16, behind its confirm - code QA L1): the row's id goes into the policy's `sms_held_drop` (24 hours),
/// and the phone deletes the message at its next policy; its event is read for every parent (L4). Back to the row,
/// which then reads "Slettet"; a row no longer waiting (expired by the server's clock too) lands on the card's notice.
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
             AND state = 'held' AND dropped_at IS NULL AND expires_at > ?",
        )
        .bind(id)
        .bind(&held_id)
        .bind(Utc::now().timestamp_millis())
        .execute(&mut *tx)
        .await?
        .rows_affected();
        if dropped > 0 {
            mark_read(&mut tx, id, std::slice::from_ref(&held_id)).await?;
        }
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
            // Code QA L5: only rows still inside their window - an expired one is gone on the phone.
            let waiting: Vec<String> = sqlx::query_scalar(
                "SELECT held_id FROM device_sms_held WHERE device_id = ? \
                 AND state = 'held' AND dropped_at IS NULL AND expires_at > ?",
            )
            .bind(id)
            .bind(Utc::now().timestamp_millis())
            .fetch_all(&mut *tx)
            .await?;
            sqlx::query(
                "UPDATE device_sms_held SET dropped_at = datetime('now') WHERE device_id = ? \
                 AND held_id IN (SELECT value FROM json_each(?))",
            )
            .bind(id)
            .bind(serde_json::to_string(&waiting).expect("strings serialize"))
            .execute(&mut *tx)
            .await?;
            mark_read(&mut tx, id, &waiting).await?;
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
    // Design 80 §4.5: only the slots the form sent - an absent field keeps its slot, an empty one removes it.
    let mut own: Vec<(i64, Option<String>)> = Vec::new();
    for (slot, field) in [(1, "own_number_1"), (2, "own_number_2")] {
        let Some(raw) = form.get(field).map(|s| s.trim()) else {
            continue;
        };
        // Code QA L3: a slot the parent didn't change on this page is left as stored - a stale page (Back, a second
        // tab) never writes what it once drew over a number stored meanwhile. An older page has no `_shown`.
        if form
            .get(&format!("{field}_shown"))
            .is_some_and(|shown| shown.trim() == raw)
        {
            continue;
        }
        if raw.is_empty() {
            own.push((slot, None));
            continue;
        }
        // Code QA L7: a SIM's own number is a full number with its country code - never a short code or an
        // emergency number, which D12's group rule would then take for "us".
        match phone::normalize(raw, &cc) {
            Ok(number) if own_number_ok(&number, &cc) => own.push((slot, Some(number))),
            outcome => {
                let why = match outcome {
                    Err(err) => t(lang, err.key()),
                    Ok(_) => t(lang, "device.sms.settings.own_number_short"),
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
    // Design 80 D13: only a post from a page with the switch (`groups_shown`) writes "Gruppemeldinger" - an older page
    // keeps it - and only for a phone claiming `sms_groups_v1` (the switch is disabled before: it isn't sent).
    let groups = if form.contains_key("groups_shown") {
        match sms::has_capability(&state.db, id, sms::SMS_GROUPS_CAPABILITY).await {
            Ok(true) => Some(form.contains_key("sms_groups")),
            Ok(false) => None,
            Err(err) => return db_error(lang, id, err),
        }
    } else {
        None
    };
    let result = async {
        let mut tx = state.db.begin().await?;
        let updated = sqlx::query(
            "UPDATE device_policy SET sms_groups = COALESCE(?, sms_groups), \
             updated_at = datetime('now') WHERE device_id = ?",
        )
        .bind(groups)
        .bind(id)
        .execute(&mut *tx)
        .await?
        .rows_affected();
        if updated == 0 {
            return Ok::<_, sqlx::Error>(false);
        }
        for (slot, number) in &own {
            store_own(&mut tx, id, *slot, number.as_deref()).await?;
        }
        tx.commit().await?;
        Ok(true)
    }
    .await;
    match result {
        Ok(false) => not_found(lang),
        Ok(true) => saved(&state, id, sms_settings(id)),
        Err(err) => db_error(lang, id, err),
    }
}

/// Where the page's "Meldinger" settings return (design 80 D9): the group inside the page's Avansert, drawn open.
fn sms_settings(id: i64) -> String {
    format!("/devices/{id}/calls?advanced=1#sms-settings")
}

/// D12 and code QA L7: a phone's own number is a full `+` number - never a short code or an emergency number, which
/// the group rule would then take for "us".
fn own_number_ok(number: &str, cc: &str) -> bool {
    sms::sender_ok("number", number)
        && number.starts_with('+')
        && !emergency::is_emergency(number, cc)
}

/// Stores (or with `None` removes) the parent's number for a SIM slot.
async fn store_own(
    tx: &mut sqlx::SqliteConnection,
    id: i64,
    slot: i64,
    number: Option<&str>,
) -> Result<(), sqlx::Error> {
    match number {
        Some(number) => {
            sqlx::query(
                "INSERT INTO device_own_numbers (device_id, slot, number) VALUES (?, ?, ?) \
                 ON CONFLICT(device_id, slot) DO UPDATE SET number = excluded.number",
            )
            .bind(id)
            .bind(slot)
            .bind(number)
            .execute(&mut *tx)
            .await?;
        }
        None => {
            sqlx::query("DELETE FROM device_own_numbers WHERE device_id = ? AND slot = ?")
                .bind(id)
                .bind(slot)
                .execute(&mut *tx)
                .await?;
        }
    }
    Ok(())
}

/// "Bruk dette nummeret" (design 80 §4.5, M3): stores the number the phone's last report gave for `source` -
/// `sim:<slot>` into that slot, `learned` or `conflict:<0|1>` (§4.4) into the first active slot with no stored number -
/// never a number from the form, with [save_sms]'s checks. The policy then carries it
/// whatever the SIM says later (a momentary IMS reading). Back to `?advanced=1#sms-settings`; a source whose report
/// changed meanwhile lands on `?notice=own_number_gone`, nothing stored.
pub async fn use_own_number(
    State(state): State<AppState>,
    lang: Lang,
    Path(id): Path<i64>,
    Form(form): Form<HashMap<String, String>>,
) -> Response {
    let source = form.get("source").map(String::as_str).unwrap_or("");
    let result = async {
        let cc: Option<String> = sqlx::query_scalar(
            "SELECT s.default_country_code FROM device_policy p, call_settings s \
             WHERE p.device_id = ? AND s.id = 1",
        )
        .bind(id)
        .fetch_optional(&state.db)
        .await?;
        let Some(cc) = cc else {
            return Ok::<_, sqlx::Error>(None);
        };
        // Code QA L4: `reported_own` keeps only numbers the tap may store (`usable_own`) - the rule the page offers by.
        let reported = reported_own(&state.db, id, &cc).await?;
        let found = match source.strip_prefix("sim:") {
            Some(slot) => slot
                .parse::<i64>()
                .ok()
                .and_then(|slot| {
                    reported
                        .sims
                        .iter()
                        .find(|(s, _)| *s == slot)
                        .and_then(|(_, number)| number.clone())
                        .map(|number| (slot, number))
                })
                .ok_or("own_number_gone"),
            // Design 80 §4.4: a learned number, or one of a conflict's two, into the first active slot with no stored
            // number (slot 1 on a phone with one SIM).
            None => {
                let stored: Vec<(i64, String)> = sqlx::query_as(
                    "SELECT slot, number FROM device_own_numbers WHERE device_id = ? ORDER BY slot",
                )
                .bind(id)
                .fetch_all(&state.db)
                .await?;
                let contacts = contacts_by_number(&state.db, id).await?;
                // Code QA V2: the page's own rule again - a stale page's tap never stores the learned number over a
                // number stored or reported since, nor one that has become a contact. N4: a stored number says so.
                learned_offer(&stored, &reported, &contacts)
                    .and_then(|learned| {
                        match source {
                            "learned" => learned.number.clone(),
                            "conflict:0" => learned.conflict.first().cloned(),
                            "conflict:1" => learned.conflict.get(1).cloned(),
                            _ => None,
                        }
                        .filter(|number| !contacts.contains_key(number))
                        .ok_or("own_number_gone")
                    })
                    // Nothing stored: the first active slot (slot 1 on a phone with one SIM).
                    .map(|number| (reported.sims.first().map_or(1, |(slot, _)| *slot), number))
            }
        };
        let (slot, number) = match found {
            Ok(found) => found,
            Err(code) => return Ok(Some(Err(code))),
        };
        let mut tx = state.db.begin().await?;
        store_own(&mut tx, id, slot, Some(&number)).await?;
        tx.commit().await?;
        Ok(Some(Ok(())))
    }
    .await;
    match result {
        Ok(None) => not_found(lang),
        Ok(Some(Ok(()))) => saved(&state, id, sms_settings(id)),
        Ok(Some(Err(code))) => crate::settings_log::refused(Redirect::to(&format!(
            "/devices/{id}/calls?advanced=1&notice={code}#sms-settings"
        ))),
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
        Ok(Some("")) => saved(
            &state,
            id,
            format!("/devices/{id}/calls?advanced=1#sms-names"),
        ),
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
        Ok(true) => saved(
            &state,
            id,
            format!("/devices/{id}/calls?advanced=1#sms-names"),
        ),
        Err(err) => db_error(lang, id, err),
    }
}

/// The emergency-number rule for a contact's "Kan sende melding til" (D3, QA R2-H1): anyone can send as one.
pub(super) fn texts_in_refused(number: &str, cc: &str) -> bool {
    emergency::is_emergency(number, cc)
}
