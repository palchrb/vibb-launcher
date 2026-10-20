//! The parent PIN (design 24; the launcher's "unlock code", "Parent code", the offline override
//! PIN): the checks on a new PIN, the easy-PIN warning - never a refusal, the code is the parents'
//! choice - and its one-time sweep over the PINs stored before it existed; and what the phones
//! report about it (`POST /api/devices/pin-events`): the checks on each event and its line (the
//! summary's notices went with its Now, the user 2026-10-20: the uses are Activity's events). Pure apart from [run_easy_sweep]; the card, the save and the endpoint
//! are `handlers::parent_pin`.

use chrono::{DateTime, Utc};
use sqlx::SqlitePool;

use crate::display_time::Zone;
use crate::security;

/// A parent PIN is 6 to 10 ASCII digits.
pub fn valid(pin: &str) -> bool {
    (6..=10).contains(&pin.len()) && pin.bytes().all(|b| b.is_ascii_digit())
}

/// Why a PIN is easy to guess ([easy]).
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum EasyKind {
    /// All one digit: 000000, 7777777.
    Repeated,
    /// Every step +1, or every step -1, mod 10: 123456, 987654, 890123, 1234567890.
    Sequence,
    /// In [COMMON_PINS].
    Common,
}

/// Frequent PINs that are neither all one digit nor a run (design 24 §4 with QA #16's additions).
pub const COMMON_PINS: [&str; 37] = [
    "121212",
    "123123",
    "112233",
    "123321",
    "131313",
    "101010",
    "202020",
    "232323",
    "696969",
    "789789",
    "520520",
    "102030",
    "100000",
    "200000",
    "159753",
    "147258",
    "258369",
    "147369",
    "159357",
    "789456",
    "147852",
    "123654",
    "111222",
    "147147",
    "741852",
    "963852",
    "852456",
    "456123",
    "321654",
    "951753",
    "357159",
    "12121212",
    "12341234",
    "11223344",
    "123123123",
    "1122334455",
    "1212121212",
];

/// Whether `pin` (a [valid] one) is easy to guess - a warning only (user's answer to QA #9).
pub fn easy(pin: &str) -> Option<EasyKind> {
    let digits: Vec<u8> = pin.bytes().map(|b| b.wrapping_sub(b'0')).collect();
    if digits.len() < 2 || digits.iter().any(|d| *d > 9) {
        return None;
    }
    if digits.iter().all(|d| *d == digits[0]) {
        return Some(EasyKind::Repeated);
    }
    let steps = |delta: u8| digits.windows(2).all(|w| w[1] == (w[0] + delta) % 10);
    if steps(1) || steps(9) {
        return Some(EasyKind::Sequence);
    }
    if COMMON_PINS.contains(&pin) {
        return Some(EasyKind::Common);
    }
    None
}

/// Every PIN of 6-10 digits that [easy] flags: the repeated and run PINs of each length plus
/// [COMMON_PINS] - 187 in all, what the sweep tries against a stored hash.
pub fn easy_candidates() -> Vec<String> {
    let mut out = Vec::new();
    for len in 6..=10usize {
        for first in 0..10u8 {
            out.push(
                (0..len)
                    .map(|_| char::from(b'0' + first))
                    .collect::<String>(),
            );
            for delta in [1u8, 9] {
                out.push(
                    (0..len)
                        .map(|i| char::from(b'0' + (first + delta * (i % 10) as u8) % 10))
                        .collect(),
                );
            }
        }
    }
    out.extend(COMMON_PINS.iter().map(|p| p.to_string()));
    out
}

/// Whether a stored hash is one of `candidates` (PBKDF2 per candidate: off the async workers).
pub fn stored_pin_is_easy(hash_hex: &str, salt_hex: &str, candidates: &[String]) -> bool {
    candidates
        .iter()
        .any(|pin| security::verify_pin(pin, hash_hex, salt_hex))
}

/// Startup task (design 24 §4): every stored parent PIN not checked yet (`override_pin_easy IS
/// NULL`, saved before the check existed) gets the same easy-PIN check as a new one, one device at
/// a time, once - seconds to a minute of one core per device. A PIN changed meanwhile is not
/// overwritten (the update matches the hash it checked). Errors are logged; the next start retries.
pub async fn run_easy_sweep(db: SqlitePool, candidates: Vec<String>) {
    let rows: Vec<(i64, String, String)> = match sqlx::query_as(
        "SELECT device_id, override_pin_hash, override_pin_salt FROM device_policy \
         WHERE override_pin_hash IS NOT NULL AND override_pin_salt IS NOT NULL \
           AND override_pin_easy IS NULL",
    )
    .fetch_all(&db)
    .await
    {
        Ok(rows) => rows,
        Err(err) => {
            tracing::error!(%err, "can't list the parent PINs to check");
            return;
        }
    };
    let candidates = std::sync::Arc::new(candidates);
    for (device_id, hash, salt) in rows {
        let (checked_hash, list) = (hash.clone(), candidates.clone());
        let easy = match tokio::task::spawn_blocking(move || {
            stored_pin_is_easy(&checked_hash, &salt, &list)
        })
        .await
        {
            Ok(easy) => easy,
            Err(err) => {
                tracing::error!(device_id, %err, "the easy-PIN check failed");
                continue;
            }
        };
        let result = sqlx::query(
            "UPDATE device_policy SET override_pin_easy = ? \
             WHERE device_id = ? AND override_pin_hash = ?",
        )
        .bind(easy)
        .bind(device_id)
        .bind(&hash)
        .execute(&db)
        .await;
        match result {
            Ok(_) if easy => tracing::info!(device_id, "the stored parent PIN is easy to guess"),
            Ok(_) => {}
            Err(err) => tracing::error!(device_id, %err, "can't store the easy-PIN check"),
        }
    }
}

/// The flash after a Parent PIN save (`?notice=`), inside the Parent PIN group on Lock Screen &
/// PINs (design 31 §3.0); `true` = a warning or refusal.
pub fn flash_text(code: &str) -> Option<(&'static str, bool)> {
    Some(match code {
        "parent_pin_saved" => (
            "Parent PIN saved. The phone uses it as soon as it checks in, and a lockout running \
             there ends then.",
            false,
        ),
        "parent_pin_saved_easy" => (
            "Parent PIN saved, but it is easy to guess. The phone uses it as soon as it checks \
             in.",
            true,
        ),
        "parent_pin_cleared" => ("Parent PIN removed.", false),
        "parent_pin_invalid" => ("Parent PIN not changed: it must be 6 to 10 digits.", true),
        "override_is_kid_pin" => (
            "Parent PIN not changed: it must differ from the Child PIN.",
            true,
        ),
        "override_needed_by_lock" => (
            "Parent PIN not removed: the Child PIN needs it to open the lock. Remove the Child \
             PIN first.",
            true,
        ),
        _ => return None,
    })
}

// ------------------------------------------------------------------------------------------------
// Events reported by the phone (design 24 §3, §5)
// ------------------------------------------------------------------------------------------------

/// At most this many events per `POST /api/devices/pin-events` - the launcher's queue holds as
/// many. More gets 413: a guard against a launcher that sends more than it should, which then
/// drops that batch as refused (`pinEventsLeaveQueue`).
pub const PIN_EVENTS_MAX: usize = 100;
/// The launcher reports parent-PIN events (status `capabilities`).
pub const PARENT_PIN_EVENTS_CAPABILITY: &str = "parent_pin_events_v1";
/// Events are kept this many days (by `received_at`, `retention::prune`).
pub const PIN_EVENT_DAYS: i64 = 90;
/// The card lists this many, newest first.
pub const SHOWN: i64 = 10;

/// 2020-01-01T00:00:00Z: an event before this comes from a phone whose clock is off.
const EARLIEST_MS: i64 = 1_577_836_800_000;
const DAY_MS: i64 = 24 * 60 * 60 * 1000;
/// The longest window an event can carry: a lockout's 24 h, plus a minute of slack.
const MAX_UNTIL_MS: i64 = DAY_MS + 60_000;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Kind {
    /// A correct parent PIN.
    Unlocked,
    /// Tries 1-4 of a round.
    Wrong,
    /// The 5th: the parent PIN is locked on the phone.
    Lockout,
}

impl Kind {
    pub fn parse(wire: &str) -> Option<Kind> {
        Some(match wire {
            "unlocked" => Kind::Unlocked,
            "wrong" => Kind::Wrong,
            "lockout" => Kind::Lockout,
            _ => return None,
        })
    }

    pub fn wire(self) -> &'static str {
        match self {
            Kind::Unlocked => "unlocked",
            Kind::Wrong => "wrong",
            Kind::Lockout => "lockout",
        }
    }
}

/// Where on the phone the parent PIN was typed.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Entry {
    /// The time lock's "Enter unlock code": the offline override.
    Override,
    /// The PIN lock's "Parent code".
    PinLock,
    /// The launcher's Settings.
    Settings,
    /// "Pause all restrictions".
    Pause,
    /// Play install mode.
    InstallMode,
}

impl Entry {
    pub fn parse(wire: &str) -> Option<Entry> {
        Some(match wire {
            "override" => Entry::Override,
            "pin_lock" => Entry::PinLock,
            "settings" => Entry::Settings,
            "pause" => Entry::Pause,
            "install_mode" => Entry::InstallMode,
            _ => return None,
        })
    }

    pub fn wire(self) -> &'static str {
        match self {
            Entry::Override => "override",
            Entry::PinLock => "pin_lock",
            Entry::Settings => "settings",
            Entry::Pause => "pause",
            Entry::InstallMode => "install_mode",
        }
    }

    /// "on the time-lock screen", for the wrong-try and lockout lines.
    fn place(self) -> &'static str {
        match self {
            Entry::Override => "on the time-lock screen",
            Entry::PinLock => "on the lock screen",
            Entry::Settings => "for Settings",
            Entry::Pause => "for pausing restrictions",
            Entry::InstallMode => "for Play install mode",
        }
    }

    /// The same in the PWA's words (design 31 §4.3), for a "Recent use" row.
    fn where_(self) -> &'static str {
        match self {
            Entry::Override => "on the time-lock screen",
            Entry::PinLock => "on the lock screen",
            Entry::Settings => "for Vibb One's Settings",
            Entry::Pause => "for a pause",
            Entry::InstallMode => "for Play install mode",
        }
    }
}

/// One event as stored: every field checked, nothing free-form.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct PinEvent {
    pub event_id: String,
    pub kind: Kind,
    pub entry: Entry,
    pub occurred_at_ms: i64,
    pub until_ms: Option<i64>,
    pub failures: Option<i64>,
    pub level: Option<i64>,
    /// The phone's clock was off: `occurred_at_ms` is when the server received it.
    pub clock_off: bool,
}

/// One event of a batch, read field by field (QA #10): `Err` (with why, for the log) skips it - an
/// id that isn't 32 hex characters, or an unknown kind or entry. A time outside 2020..now + 1 day is
/// kept with the received time and `clock_off` (QA #12); an implausible `until_ms`, `failures`
/// (1..=4) or `level` (1..=8) becomes null.
pub fn clean_event(event: &serde_json::Value, received_ms: i64) -> Result<PinEvent, &'static str> {
    let id = event
        .get("id")
        .and_then(|v| v.as_str())
        .filter(|id| id.len() == 32 && id.bytes().all(|b| b.is_ascii_hexdigit()))
        .ok_or("bad id")?
        .to_ascii_lowercase();
    let kind = event
        .get("kind")
        .and_then(|v| v.as_str())
        .and_then(Kind::parse)
        .ok_or("unknown kind")?;
    let entry = event
        .get("entry")
        .and_then(|v| v.as_str())
        .and_then(Entry::parse)
        .ok_or("unknown entry")?;
    let int = |key: &str| event.get(key).and_then(|v| v.as_i64());
    let at = int("at_ms").filter(|at| (EARLIEST_MS..=received_ms + DAY_MS).contains(at));
    let (occurred_at_ms, until_ms, clock_off) = match at {
        Some(at) => (
            at,
            int("until_ms").filter(|until| (at..=at + MAX_UNTIL_MS).contains(until)),
            false,
        ),
        None => (received_ms, None, true),
    };
    Ok(PinEvent {
        event_id: id,
        kind,
        entry,
        occurred_at_ms,
        until_ms,
        failures: int("failures").filter(|f| (1..=4).contains(f)),
        level: int("level").filter(|l| (1..=8).contains(l)),
        clock_off,
    })
}

fn utc(ms: i64) -> Option<DateTime<Utc>> {
    DateTime::from_timestamp_millis(ms)
}

/// "10 Oct 14:02 UTC", for the security log.
fn until_text(until_ms: i64) -> String {
    utc(until_ms)
        .map(|until| until.format("%-d %b %H:%M UTC").to_string())
        .unwrap_or_else(|| "unknown".to_string())
}

/// The lockout of `level` (the launcher's `parentLockoutMs`): "15 minutes" .. "24 hours".
fn lockout_text(level: i64) -> String {
    let minutes = (15i64 << (level - 1).clamp(0, 7)).min(24 * 60);
    match minutes {
        m if m < 60 => format!("{m} minutes"),
        60 => "1 hour".to_string(),
        m => format!("{} hours", m / 60),
    }
}

/// The security log's detail for a use or lockout (qa-24-code Low 2): its row's own time is when
/// it arrived, so the line says when it happened on the phone and dates the window's end -
/// `device 3, 2026-10-09 14:02 UTC: ...`. Built only from the checked enums and numbers.
pub fn log_line(device_id: i64, event: &PinEvent) -> String {
    let at = utc(event.occurred_at_ms)
        .map(|t| t.format("%Y-%m-%d %H:%M UTC").to_string())
        .unwrap_or_else(|| "unknown".to_string());
    format!("device {device_id}, {at}: {}", line(event))
}

fn line(event: &PinEvent) -> String {
    let until = event.until_ms.map(until_text);
    let mut out = match event.kind {
        Kind::Unlocked => match (event.entry, until) {
            (Entry::Override, Some(until)) => {
                format!("Restrictions lifted on the time-lock screen, until {until} at the latest.")
            }
            (Entry::Override, None) => "Restrictions lifted on the time-lock screen.".to_string(),
            (Entry::PinLock, _) => "Lock screen opened with the parent code.".to_string(),
            (Entry::Settings, _) => "Vibb One's Settings opened.".to_string(),
            (Entry::Pause, Some(until)) => {
                format!("All restrictions paused from Settings, until {until}.")
            }
            (Entry::Pause, None) => "All restrictions paused from Settings.".to_string(),
            (Entry::InstallMode, Some(until)) => {
                format!("Play install mode started, until {until}.")
            }
            (Entry::InstallMode, None) => "Play install mode started.".to_string(),
        },
        Kind::Wrong => match event.failures {
            Some(n) => format!("Wrong parent PIN {} ({n} in a row).", event.entry.place()),
            None => format!("Wrong parent PIN {}.", event.entry.place()),
        },
        Kind::Lockout => {
            // The 5 may come from different screens (qa-24-code Low 3).
            let mut text = format!(
                "5 wrong parent PINs in a row (the last {}): the parent PIN is locked on the phone",
                event.entry.place()
            );
            if let Some(level) = event.level {
                text.push_str(&format!(" for {}", lockout_text(level)));
            }
            if let Some(until) = until {
                text.push_str(&format!(", until {until}"));
            }
            text.push_str(". Saving a new parent PIN ends it once the phone checks in.");
            text
        }
    };
    if event.clock_off {
        // Inside the sentence, before its final period.
        if out.ends_with('.') {
            out.pop();
        }
        out.push_str(" (the phone's clock was off; time of arrival shown).");
    }
    out
}

/// One reported event as a row of Lock Screen & PINs' "Recent use" (design 31 step 4): a short
/// title, the details under it, the local time as the value. Built only from the checked enums
/// and numbers.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct EventRow {
    pub title: &'static str,
    pub sub: Option<String>,
    /// A lockout: the title is red.
    pub alarm: bool,
    /// "07:58", "yesterday 21:40", "12 Oct".
    pub when: String,
    /// The exact local time, for `title=`.
    pub exact: String,
}

/// When a window that started at `at_ms` ends: its clock time on the same local day, else with
/// the date ("10 Oct 08:53") - never without the time (qa-31-s3-code M2).
fn end_text(zone: &Zone, until_ms: i64, at_ms: i64) -> String {
    match (utc(until_ms), utc(at_ms)) {
        (Some(until), Some(at))
            if zone.local(until).date_naive() == zone.local(at).date_naive() =>
        {
            zone.clock(until)
        }
        (Some(until), _) => format!(
            "{} {}",
            zone.local(until).format("%-d %b"),
            zone.clock(until)
        ),
        _ => "unknown".to_string(),
    }
}

/// "On the lock screen" from "on the lock screen".
fn capitalized(text: &str) -> String {
    let mut chars = text.chars();
    chars
        .next()
        .map(|first| first.to_uppercase().chain(chars).collect())
        .unwrap_or_default()
}

pub fn event_row(event: &PinEvent, zone: &Zone, now: DateTime<Utc>) -> EventRow {
    let until = event
        .until_ms
        .map(|until| end_text(zone, until, event.occurred_at_ms));
    let (title, mut parts): (&'static str, Vec<String>) = match event.kind {
        Kind::Unlocked => (
            match event.entry {
                Entry::Override => "Time lock lifted",
                Entry::PinLock => "Lock screen opened",
                Entry::Settings => "Vibb One's Settings opened",
                Entry::Pause => "Restrictions paused",
                Entry::InstallMode => "Play install mode",
            },
            until
                .map(|until| match event.entry {
                    Entry::Override => format!("Until {until} at the latest"),
                    _ => format!("Until {until}"),
                })
                .into_iter()
                .collect(),
        ),
        Kind::Wrong => (
            "Wrong Parent PIN",
            std::iter::once(capitalized(event.entry.where_()))
                .chain(event.failures.map(|n| format!("{n} in a row")))
                .collect(),
        ),
        Kind::Lockout => {
            let mut lock = String::from("Locked");
            if let Some(level) = event.level {
                lock.push_str(&format!(" for {}", lockout_text(level)));
            }
            if let Some(until) = until {
                lock.push_str(&format!(", until {until}"));
            }
            (
                "Parent PIN locked",
                vec![
                    format!("5 wrong in a row, the last {}", event.entry.where_()),
                    lock,
                ],
            )
        }
    };
    if event.clock_off {
        parts.push("The phone's clock was off: time of arrival".to_string());
    }
    let at = utc(event.occurred_at_ms);
    EventRow {
        title,
        sub: (!parts.is_empty()).then(|| parts.join(" · ")),
        alarm: event.kind == Kind::Lockout,
        when: at.map(|t| zone.when(t, now)).unwrap_or_default(),
        exact: at.map(|t| zone.exact(t)).unwrap_or_default(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn valid_is_6_to_10_digits() {
        for pin in ["583920", "4719305", "0000000000"] {
            assert!(valid(pin), "{pin}");
        }
        for pin in ["", "12345", "12345678901", "12345a", " 123456", "١٢٣٤٥٦"] {
            assert!(!valid(pin), "{pin}");
        }
    }

    #[test]
    fn easy_finds_each_kind() {
        for pin in ["000000", "777777", "9999999999"] {
            assert_eq!(easy(pin), Some(EasyKind::Repeated), "{pin}");
        }
        // Up and down, wrapping around, up to 10 digits.
        for pin in [
            "123456",
            "987654",
            "890123",
            "210987",
            "1234567890",
            "0987654321",
            "4567890123",
        ] {
            assert_eq!(easy(pin), Some(EasyKind::Sequence), "{pin}");
        }
        for pin in COMMON_PINS {
            assert_eq!(easy(pin), Some(EasyKind::Common), "{pin}");
        }
        // Accepted: no warning.
        for pin in ["583920", "4719305", "124578", "135792", "1357924680"] {
            assert_eq!(easy(pin), None, "{pin}");
        }
    }

    #[test]
    fn the_candidates_are_exactly_the_easy_pins() {
        let candidates = easy_candidates();
        assert_eq!(candidates.len(), 187);
        let set: std::collections::HashSet<&String> = candidates.iter().collect();
        assert_eq!(set.len(), 187, "no duplicates");
        for pin in &candidates {
            assert!(valid(pin), "{pin}");
            assert!(easy(pin).is_some(), "{pin}");
        }
        // Every 6-digit PIN that easy() flags is a candidate (the sweep can find it).
        for n in 0..1_000_000u32 {
            let pin = format!("{n:06}");
            if easy(&pin).is_some() {
                assert!(set.contains(&pin), "{pin}");
            }
        }
    }

    #[test]
    fn a_stored_hash_is_checked_against_the_candidates() {
        let candidates = vec!["111111".to_string(), "123456".to_string()];
        let (hash, salt) = security::hash_pin("123456");
        assert!(stored_pin_is_easy(&hash, &salt, &candidates));
        let (hash, salt) = security::hash_pin("583920");
        assert!(!stored_pin_is_easy(&hash, &salt, &candidates));
    }

    #[test]
    fn flash_texts() {
        for code in [
            "parent_pin_saved",
            "parent_pin_saved_easy",
            "parent_pin_cleared",
            "parent_pin_invalid",
            "override_is_kid_pin",
            "override_needed_by_lock",
        ] {
            let (text, _) = flash_text(code).unwrap();
            assert!(text.contains("Parent PIN"), "{code}: {text}");
        }
        assert!(!flash_text("parent_pin_saved").unwrap().1);
        assert!(flash_text("parent_pin_saved_easy").unwrap().1);
        // The kid lock's codes are not this card's.
        assert_eq!(flash_text("saved"), None);
    }

    // --------------------------------------------------------------------------------------------
    // Events
    // --------------------------------------------------------------------------------------------

    const NOW: i64 = 1_760_000_000_000; // 2025-10-09 08:53:20 UTC
    const H: i64 = 3_600_000;

    fn raw(id: &str, kind: &str, entry: &str, at: i64) -> serde_json::Value {
        serde_json::json!({ "id": id, "kind": kind, "entry": entry, "at_ms": at })
    }

    fn ev(kind: Kind, entry: Entry, at: i64) -> PinEvent {
        PinEvent {
            event_id: "0".repeat(32),
            kind,
            entry,
            occurred_at_ms: at,
            until_ms: None,
            failures: None,
            level: None,
            clock_off: false,
        }
    }

    #[test]
    fn clean_event_skips_bad_ids_and_unknown_names() {
        let id = "0123456789ABCDEF0123456789abcdef";
        let ok = clean_event(&raw(id, "unlocked", "pause", NOW - H), NOW).unwrap();
        assert_eq!(ok.event_id, id.to_ascii_lowercase());
        assert_eq!((ok.kind, ok.entry), (Kind::Unlocked, Entry::Pause));
        for bad in [
            raw("0123", "unlocked", "pause", NOW),
            raw(&"g".repeat(32), "unlocked", "pause", NOW),
            raw(id, "guessed", "pause", NOW),
            raw(id, "unlocked", "garage", NOW),
            serde_json::json!({ "kind": "wrong", "entry": "settings", "at_ms": NOW }),
            serde_json::json!("wrong"),
        ] {
            assert!(clean_event(&bad, NOW).is_err(), "{bad}");
        }
    }

    #[test]
    fn clean_event_keeps_an_implausible_time_and_nulls_bad_numbers() {
        let id = "0123456789abcdef0123456789abcdef";
        for at in [
            serde_json::json!(NOW + 2 * DAY_MS),
            serde_json::json!(1_000),
            serde_json::json!("soon"),
            serde_json::Value::Null,
        ] {
            let mut e = raw(id, "lockout", "override", 0);
            e["at_ms"] = at.clone();
            e["until_ms"] = serde_json::json!(NOW + H);
            let clean = clean_event(&e, NOW).unwrap();
            assert!(clean.clock_off, "{at}");
            assert_eq!(clean.occurred_at_ms, NOW);
            assert_eq!(clean.until_ms, None);
        }
        // Up to a day ahead is a plausible clock.
        assert!(
            !clean_event(&raw(id, "wrong", "settings", NOW + DAY_MS), NOW)
                .unwrap()
                .clock_off
        );

        let mut e = raw(id, "wrong", "settings", NOW);
        e["failures"] = serde_json::json!(5);
        e["level"] = serde_json::json!(0);
        e["until_ms"] = serde_json::json!(NOW - 1);
        let clean = clean_event(&e, NOW).unwrap();
        assert_eq!(
            (clean.failures, clean.level, clean.until_ms),
            (None, None, None)
        );
        e["failures"] = serde_json::json!(4);
        e["level"] = serde_json::json!(8);
        e["until_ms"] = serde_json::json!(NOW + DAY_MS + 60_000);
        let clean = clean_event(&e, NOW).unwrap();
        assert_eq!(
            (clean.failures, clean.level, clean.until_ms),
            (Some(4), Some(8), Some(NOW + DAY_MS + 60_000))
        );
        e["until_ms"] = serde_json::json!(NOW + DAY_MS + 60_001);
        assert_eq!(clean_event(&e, NOW).unwrap().until_ms, None);
    }

    #[test]
    fn every_log_line() {
        let until = Some(NOW + 2 * H); // 10:53 UTC, the same day
        let unlocked = |entry, until| PinEvent {
            until_ms: until,
            ..ev(Kind::Unlocked, entry, NOW)
        };
        let cases = [
            (
                unlocked(Entry::Override, until),
                "Restrictions lifted on the time-lock screen, until 9 Oct 10:53 UTC at the latest.",
            ),
            (
                unlocked(Entry::Override, None),
                "Restrictions lifted on the time-lock screen.",
            ),
            (
                unlocked(Entry::PinLock, None),
                "Lock screen opened with the parent code.",
            ),
            (
                unlocked(Entry::Settings, None),
                "Vibb One's Settings opened.",
            ),
            (
                unlocked(Entry::Pause, until),
                "All restrictions paused from Settings, until 9 Oct 10:53 UTC.",
            ),
            (
                unlocked(Entry::InstallMode, Some(NOW + 15 * 60_000)),
                "Play install mode started, until 9 Oct 09:08 UTC.",
            ),
            (
                PinEvent {
                    failures: Some(3),
                    ..ev(Kind::Wrong, Entry::PinLock, NOW)
                },
                "Wrong parent PIN on the lock screen (3 in a row).",
            ),
            (
                ev(Kind::Wrong, Entry::InstallMode, NOW),
                "Wrong parent PIN for Play install mode.",
            ),
            (
                PinEvent {
                    level: Some(8),
                    until_ms: Some(NOW + 24 * H),
                    ..ev(Kind::Lockout, Entry::Pause, NOW)
                },
                "5 wrong parent PINs in a row (the last for pausing restrictions): the parent PIN is \
                 locked on the phone for 24 hours, until 10 Oct 08:53 UTC. Saving a new parent PIN \
                 ends it once the phone checks in.",
            ),
            (
                PinEvent {
                    clock_off: true,
                    ..ev(Kind::Unlocked, Entry::Settings, NOW)
                },
                "Vibb One's Settings opened (the phone's clock was off; time of arrival \
                 shown).",
            ),
        ];
        // The security log says when it happened and dates the window's end (qa-24-code Low 2).
        for (event, line) in cases {
            assert_eq!(
                log_line(3, &event),
                format!("device 3, 2025-10-09 08:53 UTC: {line}")
            );
        }
        assert_eq!(lockout_text(3), "1 hour");
        assert_eq!(lockout_text(5), "4 hours");
        assert_eq!(lockout_text(2), "30 minutes");
    }

    /// Oslo, as Phone Setup has it: 08:53 UTC is 10:53 local (CEST).
    fn oslo() -> Zone {
        Zone::from_setting("Europe/Oslo")
    }

    fn at(ms: i64) -> DateTime<Utc> {
        DateTime::from_timestamp_millis(ms).unwrap()
    }

    #[test]
    fn every_event_row_in_local_time() {
        let zone = oslo();
        let now = at(NOW);
        let row = |event: PinEvent| {
            let row = event_row(&event, &zone, now);
            (row.title, row.sub, row.alarm)
        };
        let unlocked = |entry, until| PinEvent {
            until_ms: until,
            ..ev(Kind::Unlocked, entry, NOW)
        };
        assert_eq!(
            row(unlocked(Entry::Override, Some(NOW + 2 * H))),
            (
                "Time lock lifted",
                Some("Until 12:53 at the latest".to_string()),
                false
            )
        );
        assert_eq!(
            row(unlocked(Entry::PinLock, None)),
            ("Lock screen opened", None, false)
        );
        assert_eq!(
            row(unlocked(Entry::Settings, None)),
            ("Vibb One's Settings opened", None, false)
        );
        assert_eq!(
            row(unlocked(Entry::Pause, Some(NOW + 2 * H))),
            (
                "Restrictions paused",
                Some("Until 12:53".to_string()),
                false
            )
        );
        assert_eq!(
            row(unlocked(Entry::InstallMode, Some(NOW + 15 * 60_000))),
            ("Play install mode", Some("Until 11:08".to_string()), false)
        );
        assert_eq!(
            row(PinEvent {
                failures: Some(3),
                ..ev(Kind::Wrong, Entry::PinLock, NOW)
            }),
            (
                "Wrong Parent PIN",
                Some("On the lock screen · 3 in a row".to_string()),
                false
            )
        );
        assert_eq!(
            row(ev(Kind::Wrong, Entry::Settings, NOW)),
            (
                "Wrong Parent PIN",
                Some("For Vibb One's Settings".to_string()),
                false
            )
        );
        // A lockout past midnight keeps its clock time, with the date.
        assert_eq!(
            row(PinEvent {
                level: Some(8),
                until_ms: Some(NOW + 24 * H),
                ..ev(Kind::Lockout, Entry::Pause, NOW)
            }),
            (
                "Parent PIN locked",
                Some(
                    "5 wrong in a row, the last for a pause · Locked for 24 hours, until 10 Oct \
                     10:53"
                        .to_string()
                ),
                true
            )
        );
        assert_eq!(
            row(PinEvent {
                clock_off: true,
                ..ev(Kind::Unlocked, Entry::Settings, NOW)
            })
            .1
            .as_deref(),
            Some("The phone's clock was off: time of arrival")
        );
        // The time: local, the exact one for `title=`.
        let event = ev(Kind::Wrong, Entry::Override, NOW - 3 * H);
        let row = event_row(&event, &zone, now);
        assert_eq!(row.when, "07:53");
        assert_eq!(row.exact, "2025-10-09 07:53:20 CEST");
        // Nothing says UTC once Phone Setup has a zone.
        assert!(!format!("{row:?}").contains("UTC"));
    }
}
