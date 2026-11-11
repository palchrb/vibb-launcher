//! Design 77 D10 and design 83: the MMS servers a phone may fetch a picture from, by its SIM's MCC/MNC. The phone
//! fetches only from an entry in its SIM's row of `call_policy.mmsc_hosts` (`X-Mms-Content-Location`,
//! `MmsGate.location` in the launcher), and that row has two sources:
//!
//! - **the generated list** `testdata/mmsc_hosts.txt`, made by `scripts/mmsc-hosts.py` from AOSP's APN list and
//!   compiled in - never edited by hand (the script's `--selftest` holds its hash). Each MCC/MNC's entries are in two
//!   classes: an **unasked** one is sent to a phone with that SIM, a **hint** is only shown to the parent who is asked
//!   about an address. Which entries are unasked is the script's `UNASKED` (`"none"` until design 77's step 0 has
//!   shown on which network the platform fetches; design 83 §3);
//! - **the parent's allow** (`device_mmsc_hosts`, 0137): the phone reports a contact's picture whose address is not
//!   in its row (`sms_state.mmsc_unlisted`), [intake] stores it `open` and raises `mmsc_host`, and the Calls page
//!   asks (`handlers::calls_mmsc`). Allowed entries join the row of that phone and MCC/MNC.
//!
//! The row is made for the SIMs the phone reports (`sms_state.sim_operators`): without a report, or from a launcher
//! that reports none, the policy has no row and every picture fails closed. Never put a test or fake host in the list:
//! tests use `.invalid` names in rows of their own.

use crate::AppState;
use chrono::Utc;
use serde::{Deserialize, Serialize};
use sqlx::SqlitePool;
use std::collections::HashMap;
use std::sync::LazyLock;

/// One SIM operator's MMS servers: `mccmnc` as `TelephonyManager.getSimOperator()` gives it, each of `hosts` an entry
/// ([valid_entry]) matched exactly.
#[derive(Serialize, Clone, Debug, PartialEq, Eq)]
pub struct MmscHosts {
    pub mccmnc: String,
    pub hosts: Vec<String>,
}

/// A phone's open questions at most: a fifth address is not stored, so a row a parent is reading never goes.
pub const OPEN_MAX: i64 = 4;
/// The addresses a parent may allow for one phone and MCC/MNC.
pub const ALLOWED_MAX: i64 = 8;
/// A phone's declined addresses kept; past it the oldest goes (and may be asked about again).
pub const DECLINED_MAX: i64 = 32;
/// An open row goes this long after a report last named it (`retention::prune`).
pub const OPEN_DAYS: i64 = 30;

/// What the generated list has for one MCC/MNC.
#[derive(Debug, Default, PartialEq, Eq)]
pub struct Listed {
    pub unasked: Vec<&'static str>,
    pub hints: Vec<&'static str>,
}

static LIST: LazyLock<HashMap<&'static str, Listed>> =
    LazyLock::new(|| parse(include_str!("../testdata/mmsc_hosts.txt")));

/// The list's lines, `mccmnc unasked... | hints...` (`#` a comment): by MCC/MNC.
fn parse(text: &'static str) -> HashMap<&'static str, Listed> {
    text.lines()
        .filter(|line| !line.is_empty() && !line.starts_with('#'))
        .filter_map(|line| {
            let (left, hints) = line.split_once('|')?;
            let mut left = left.split_whitespace();
            let listed = Listed {
                unasked: left.clone().skip(1).collect(),
                hints: hints.split_whitespace().collect(),
            };
            Some((left.next()?, listed))
        })
        .collect()
}

/// What Android's list has for a SIM operator, `None` when it has no line.
pub fn listed(mccmnc: &str) -> Option<&'static Listed> {
    LIST.get(mccmnc)
}

/// A SIM operator as `getSimOperator()` gives it: 5 or 6 digits.
pub fn valid_mccmnc(mccmnc: &str) -> bool {
    (5..=6).contains(&mccmnc.len()) && mccmnc.bytes().all(|b| b.is_ascii_digit())
}

/// One decimal octet as an address has it: 0-255, no leading zero.
fn octet(label: &str) -> bool {
    (label == "0" || !label.starts_with('0')) && label.parse::<u8>().is_ok()
}

/// Design 83 §1's entry, one grammar on both sides (`testdata/sms_contract.json`'s `mmsc_entries`; the launcher's
/// `MmsGate.validEntry`): `host` or `host:port`. The host is 1-253 bytes of `[a-z0-9.-]` in non-empty labels, and
/// either its last label starts with a letter or it is exactly four decimal octets - so nothing a resolver could read
/// as another address (`010.0.0.1`, `2130706433`) - whose first is neither 0 nor 127: "this host" is no MMS server,
/// and no question to put to a parent. The port is 1-65535 without a leading zero and never 80, 443 or 8080: on those
/// the bare host is the entry.
pub fn valid_entry(entry: &str) -> bool {
    let (host, port) = match entry.split_once(':') {
        Some((host, port)) => (host, Some(port)),
        None => (entry, None),
    };
    if let Some(port) = port {
        let plain = !port.starts_with('0') && port.bytes().all(|b| b.is_ascii_digit());
        if !plain
            || !port
                .parse::<u16>()
                .is_ok_and(|n| ![80, 443, 8080].contains(&n))
        {
            return false;
        }
    }
    let shape = !host.is_empty()
        && host.len() <= 253
        && host
            .bytes()
            .all(|b| b.is_ascii_lowercase() || b.is_ascii_digit() || b == b'.' || b == b'-');
    let labels: Vec<&str> = host.split('.').collect();
    let address = labels.len() == 4
        && labels.iter().all(|label| octet(label))
        && !["0", "127"].contains(&labels[0]);
    shape
        && labels.iter().all(|label| !label.is_empty())
        && (labels[labels.len() - 1].as_bytes()[0].is_ascii_lowercase() || address)
}

/// A reported SIM operator: its MCC/MNC and the name the SIM gives (may be empty).
#[derive(Deserialize, Clone, Debug, PartialEq, Eq)]
pub struct Operator {
    pub mccmnc: String,
    #[serde(default)]
    pub name: String,
}

/// A reported picture whose address is not in the phone's row: the entry, the contact it came from and when.
#[derive(Deserialize, Clone, Debug, PartialEq, Eq)]
pub struct Unlisted {
    pub mccmnc: String,
    pub host: String,
    pub from: String,
    pub at: i64,
}

/// The two keys of a stored `sms_state` this module reads (`sms::sanitize_state` kept only well-formed rows).
#[derive(Deserialize, Default, Debug)]
pub struct Reported {
    /// `None` from a launcher that doesn't say (before design 83): no row is sent, nothing can be allowed.
    pub sim_operators: Option<Vec<Operator>>,
    #[serde(default)]
    pub mmsc_unlisted: Vec<Unlisted>,
}

impl Reported {
    /// Of a stored `sms_state`; one that doesn't read is no report.
    pub fn of(sms_state: &str) -> Reported {
        serde_json::from_str(sms_state).unwrap_or_default()
    }

    /// Of the newest report that carries an `sms_state` at all, as `sms::reported_history`: the launcher sends
    /// `sms_state: null` before the first unlock, and the SIM is the same then.
    pub async fn load(db: &SqlitePool, device_id: i64) -> Result<Reported, sqlx::Error> {
        let state: Option<Option<String>> = sqlx::query_scalar(
            "SELECT sms_state_json FROM device_status WHERE device_id = ? AND sms_state_json IS NOT NULL \
             ORDER BY reported_at DESC, id DESC LIMIT 1",
        )
        .bind(device_id)
        .fetch_optional(db)
        .await?;
        Ok(state
            .flatten()
            .map(|json| Reported::of(&json))
            .unwrap_or_default())
    }

    /// The reported MCC/MNCs, each once, sorted: what a SIM change is judged by.
    pub fn operators(&self) -> Vec<&str> {
        let mut out: Vec<&str> = self
            .sim_operators
            .iter()
            .flatten()
            .map(|o| o.mccmnc.as_str())
            .collect();
        out.sort_unstable();
        out.dedup();
        out
    }

    /// The name the phone's SIM gives the operator, else the MCC/MNC.
    pub fn operator_name<'a>(&'a self, mccmnc: &'a str) -> &'a str {
        self.sim_operators
            .iter()
            .flatten()
            .find(|o| o.mccmnc == mccmnc && !o.name.is_empty())
            .map_or(mccmnc, |o| o.name.as_str())
    }
}

/// `call_policy.mmsc_hosts`: a row for each SIM operator the phone reports that has an entry to send - the list's
/// unasked ones, then the parent's allowed ones for this phone and MCC/MNC. Never a hint, never another operator's
/// row; `[]` without a report.
pub async fn policy(db: &SqlitePool, device_id: i64) -> Result<Vec<MmscHosts>, sqlx::Error> {
    let reported = Reported::load(db, device_id).await?;
    let mut rows = Vec::new();
    for mccmnc in reported.operators() {
        let allowed: Vec<String> = sqlx::query_scalar(
            "SELECT host FROM device_mmsc_hosts WHERE device_id = ? AND mccmnc = ? AND state = 'allowed' \
             ORDER BY host",
        )
        .bind(device_id)
        .bind(mccmnc)
        .fetch_all(db)
        .await?;
        let mut hosts: Vec<String> = listed(mccmnc)
            .map(|l| l.unasked.iter().map(|h| h.to_string()).collect())
            .unwrap_or_default();
        for host in allowed {
            if !hosts.contains(&host) {
                hosts.push(host);
            }
        }
        if !hosts.is_empty() {
            rows.push(MmscHosts {
                mccmnc: mccmnc.to_string(),
                hosts,
            });
        }
    }
    Ok(rows)
}

/// The phone's contacts, normalised number -> name: who a reported picture may be from, and the name its question
/// says.
pub async fn contacts(
    db: &SqlitePool,
    device_id: i64,
) -> Result<HashMap<String, String>, sqlx::Error> {
    Ok(sqlx::query_as::<_, (String, String)>(
        "SELECT c.phone_number, c.name FROM device_contacts dc JOIN contacts c ON c.id = dc.contact_id \
         WHERE dc.device_id = ?",
    )
    .bind(device_id)
    .fetch_all(db)
    .await?
    .into_iter()
    .collect())
}

/// A status report's `sms_state` (as stored, through `sms::sanitize_state`), received now: each `mmsc_unlisted` row
/// that design 83 §4 keeps becomes an open question, or moves the one there is to this picture. Kept only when its
/// MCC/MNC is one of the report's SIMs, its sender is one of this phone's contacts, and the entry is not unasked for
/// that SIM; an entry already answered changes nothing (allowed: the policy has just not reached the phone;
/// declined: never asked again), and neither does a fifth open one. A new row raises `mmsc_host`, once. A failure is
/// logged and never fails the report.
pub async fn intake(state: &AppState, device_id: i64, sms_state: Option<&str>) {
    let reported = sms_state.map(Reported::of).unwrap_or_default();
    if reported.mmsc_unlisted.is_empty() {
        return;
    }
    let operators = reported.operators();
    let now = Utc::now();
    let result = async {
        let contacts = contacts(&state.db, device_id).await?;
        let Some(phone) = crate::parent_events::Phone::load(&state.db, device_id).await else {
            return Ok(false);
        };
        let mut raised = false;
        let mut tx = state.db.begin_with("BEGIN IMMEDIATE").await?;
        for row in &reported.mmsc_unlisted {
            let contact = contacts.get(&row.from);
            let unasked =
                listed(&row.mccmnc).is_some_and(|l| l.unasked.contains(&row.host.as_str()));
            let (Some(contact), true, false) =
                (contact, operators.contains(&row.mccmnc.as_str()), unasked)
            else {
                tracing::info!(
                    device_id,
                    mccmnc = row.mccmnc,
                    host = row.host,
                    "an unlisted MMS address dropped"
                );
                continue;
            };
            let at = row.at.min(now.timestamp_millis());
            let moved = sqlx::query(
                "UPDATE device_mmsc_hosts SET picture_from = ?, picture_at = ?, reported_at = ? \
                 WHERE device_id = ? AND mccmnc = ? AND host = ? AND state = 'open'",
            )
            .bind(&row.from)
            .bind(at)
            .bind(now.timestamp_millis())
            .bind(device_id)
            .bind(&row.mccmnc)
            .bind(&row.host)
            .execute(&mut *tx)
            .await?
            .rows_affected();
            if moved > 0 {
                continue;
            }
            // Not open: answered already (the unique key refuses the insert), or new - while there is room.
            let id: Option<i64> = sqlx::query_scalar(
                "INSERT INTO device_mmsc_hosts (device_id, mccmnc, host, picture_from, picture_at, reported_at) \
                 SELECT ?1, ?2, ?3, ?4, ?5, ?6 \
                 WHERE (SELECT COUNT(*) FROM device_mmsc_hosts WHERE device_id = ?1 AND state = 'open') < ?7 \
                 ON CONFLICT (device_id, mccmnc, host) DO NOTHING RETURNING id",
            )
            .bind(device_id)
            .bind(&row.mccmnc)
            .bind(&row.host)
            .bind(&row.from)
            .bind(at)
            .bind(now.timestamp_millis())
            .bind(OPEN_MAX)
            .fetch_optional(&mut *tx)
            .await?;
            if let Some(id) = id {
                let event = crate::parent_events::mmsc_host(
                    device_id,
                    &phone.name,
                    id,
                    contact,
                    chrono::DateTime::from_timestamp_millis(at).unwrap_or(now),
                );
                raised |= crate::parent_events::raise(&mut tx, &event)
                    .await?
                    .is_some();
            }
        }
        tx.commit().await?;
        Ok::<_, sqlx::Error>(raised)
    };
    match result.await {
        Ok(true) => state.push.wake(),
        Ok(false) => {}
        Err(err) => tracing::error!(device_id, %err, "can't store the unlisted MMS addresses"),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// The compiled-in list is what the phone can match: every MCC/MNC 5-6 digits and once, every entry of both
    /// classes in the grammar and once in its line, no line without an entry.
    #[test]
    fn the_list_is_well_formed() {
        let text = include_str!("../testdata/mmsc_hosts.txt");
        let lines = text
            .lines()
            .filter(|line| !line.is_empty() && !line.starts_with('#'))
            .count();
        assert!(lines > 0);
        assert_eq!(LIST.len(), lines, "an MCC/MNC twice, or a line without `|`");
        for (mccmnc, row) in LIST.iter() {
            assert!(valid_mccmnc(mccmnc), "{mccmnc}");
            let mut entries: Vec<&str> = row.unasked.iter().chain(&row.hints).copied().collect();
            assert!(!entries.is_empty(), "{mccmnc}: no entry");
            for entry in &entries {
                assert!(valid_entry(entry), "{mccmnc}: {entry}");
            }
            let all = entries.len();
            entries.sort_unstable();
            entries.dedup();
            assert_eq!(entries.len(), all, "{mccmnc}: an entry twice");
        }
    }

    #[test]
    fn a_line_has_its_two_classes() {
        let list = parse(
            "# a comment\n24299 mmsc 10.0.0.9:8002 | mmsc.example.invalid\n24298 | mms.example.invalid\n",
        );
        assert_eq!(list.len(), 2);
        assert_eq!(
            list["24299"],
            Listed {
                unasked: vec!["mmsc", "10.0.0.9:8002"],
                hints: vec!["mmsc.example.invalid"],
            }
        );
        assert_eq!(list["24298"].unasked, Vec::<&str>::new());
        assert_eq!(list["24298"].hints, vec!["mms.example.invalid"]);
    }

    /// The grammar's vectors, shared with the launcher (`SmsContractTest`).
    #[test]
    fn the_contracts_entries() {
        let contract: serde_json::Value =
            serde_json::from_str(include_str!("../testdata/sms_contract.json")).unwrap();
        let rows = contract["mmsc_entries"].as_array().unwrap();
        assert!(rows.len() >= 9);
        for row in rows {
            let entry = row["entry"].as_str().unwrap();
            assert_eq!(
                valid_entry(entry),
                row["valid"].as_bool().unwrap(),
                "{entry}"
            );
        }
    }
}
