//! Handy's own PIN lock (handy step 10, design `docs/design/10-lock-and-call-ui.md` in the handy
//! workspace, with `qa-10-design.md` on top): the kid's PIN rules, the cross-check with the
//! override PIN, and what Lock Screen & PINs says about the phone's `lock_state`. Pure apart from
//! the PBKDF2 calls in [decide_kid_pin]/[override_conflicts]; unit-tested here, end to end in
//! `src/tests/step10.rs`.

use serde::{Deserialize, Serialize};

use crate::handlers::device_pages::Note;
use crate::models::{DevicePolicy, KidLock};
use crate::security;

/// The launcher capability that understands `kid_lock`.
pub const PIN_LOCK_CAPABILITY: &str = "pin_lock_v1";
/// Kid PINs are 4-6 digits; the parent chooses (default 4, decision after QA review).
pub const KID_PIN_MIN: usize = 4;
pub const KID_PIN_MAX: usize = 6;

pub fn valid_kid_pin(pin: &str) -> bool {
    (KID_PIN_MIN..=KID_PIN_MAX).contains(&pin.len()) && pin.chars().all(|c| c.is_ascii_digit())
}

/// `PolicyResponse.kid_lock` from the stored columns: all three present and the length in range,
/// else `None` (lock off - a privacy feature, so a half-written row fails open, design §8).
pub fn policy_kid_lock(policy: &DevicePolicy) -> Option<KidLock> {
    let (Some(hash), Some(salt), Some(len)) = (
        policy.kid_pin_hash.as_ref(),
        policy.kid_pin_salt.as_ref(),
        policy.kid_pin_length,
    ) else {
        return None;
    };
    let len_ok = usize::try_from(len).is_ok_and(|l| (KID_PIN_MIN..=KID_PIN_MAX).contains(&l));
    len_ok.then(|| KidLock {
        pin_hash: hash.clone(),
        pin_salt: salt.clone(),
        pin_length: len,
    })
}

/// Why a kid PIN save was refused - shown on the device page; nothing is written.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum KidPinRefusal {
    /// Not 4-6 digits.
    Invalid,
    /// No override PIN: without the "Foreldrekode" a forgotten kid PIN plus an unreachable
    /// server would leave only calls (QA 10 #6).
    NeedsOverride,
    /// The kid PIN verifies against the override hash - it would open the override.
    SameAsOverride,
}

impl KidPinRefusal {
    pub fn code(self) -> &'static str {
        match self {
            KidPinRefusal::Invalid => "invalid",
            KidPinRefusal::NeedsOverride => "needs_override",
            KidPinRefusal::SameAsOverride => "same_as_override",
        }
    }
}

/// A new kid PIN: refused unless 4-6 digits, an override PIN is set, and it doesn't verify
/// against the override hash. Returns the hash, salt and length to store.
pub fn decide_kid_pin(
    new_pin: &str,
    override_hash: Option<&str>,
    override_salt: Option<&str>,
) -> Result<(String, String, i64), KidPinRefusal> {
    if !valid_kid_pin(new_pin) {
        return Err(KidPinRefusal::Invalid);
    }
    let (Some(hash), Some(salt)) = (override_hash, override_salt) else {
        return Err(KidPinRefusal::NeedsOverride);
    };
    if security::verify_pin(new_pin, hash, salt) {
        return Err(KidPinRefusal::SameAsOverride);
    }
    let (kid_hash, kid_salt) = security::hash_pin(new_pin);
    Ok((kid_hash, kid_salt, new_pin.len() as i64))
}

/// A new override PIN must not verify against the kid's hash (it would then be the kid's PIN).
pub fn override_conflicts(new_override: &str, policy: &DevicePolicy) -> bool {
    match (&policy.kid_pin_hash, &policy.kid_pin_salt) {
        (Some(hash), Some(salt)) => security::verify_pin(new_override, hash, salt),
        _ => false,
    }
}

/// The phone's `lock_state` report. Unknown fields are ignored; missing ones default. Stored
/// re-serialized ([sanitize_lock_state]), so nothing but these fields reaches the database -
/// never unlock times or PIN material, whatever a launcher sends.
#[derive(Deserialize, Serialize, Default, Debug, Clone, PartialEq, Eq)]
#[serde(default)]
pub struct LockState {
    pub active: bool,
    /// null | "no_pin" | "unmanaged" | "android_credential" | "keyguard_not_disabled" |
    /// "crash_guard" | "bad_hash"
    pub inactive: Option<String>,
    pub locked: bool,
    pub failures: i64,
    pub backoff_until_ms: Option<i64>,
    /// Times the lock stepped aside for an exempt screen (call, emergency dialer, alarm) since
    /// the last report (QA 10 #2: these are the only fights that are counted).
    pub exempt_yields: i64,
    /// Design 17 (QA #11): allowed VoIP apps (Element X, Signal) whose last ring had no
    /// full-screen intent - Android dropped it (no USE_FULL_SCREEN_INTENT), so their calls can't
    /// ring over the lock. Valid package names only, at most [MAX_FSI_DENIED].
    pub voip_fsi_denied: Vec<String>,
    /// Design r7 §1.1.3: the apps whose ring the lock accepted while LOCKED (or that rang while the
    /// phone locked) in the last 7 days, newest first. Parsed leniently (r7-4): an entry that isn't
    /// `{package, last_at_ms}` is dropped, never the whole `lock_state`; a missing field is empty.
    #[serde(deserialize_with = "lenient_voip_rang")]
    pub voip_rang: Vec<VoipRang>,
}

/// One `lock_state.voip_rang` entry: a package name and the wall time (ms) of its last ring over
/// the lock.
#[derive(Deserialize, Serialize, Debug, Clone, PartialEq, Eq)]
pub struct VoipRang {
    pub package: String,
    pub last_at_ms: i64,
}

/// `voip_rang` entry by entry: anything that isn't a list is empty, an entry that doesn't parse is
/// dropped (r7-4).
fn lenient_voip_rang<'de, D>(deserializer: D) -> Result<Vec<VoipRang>, D::Error>
where
    D: serde::Deserializer<'de>,
{
    let value = serde_json::Value::deserialize(deserializer)?;
    Ok(match value {
        serde_json::Value::Array(entries) => entries
            .into_iter()
            .filter_map(|entry| serde_json::from_value(entry).ok())
            .collect(),
        _ => Vec::new(),
    })
}

/// How many `voip_fsi_denied` packages (and `voip_rang` entries) are kept.
const MAX_FSI_DENIED: usize = 8;

/// A `voip_rang` time this far ahead of the server's clock is still taken (the phone's clock may
/// run a little ahead); later ones are dropped.
const RANG_CLOCK_SKEW_MS: i64 = 5 * 60_000;

/// `StatusReportRequest.lock_state` as stored: only the [LockState] fields, `inactive` capped at
/// 64 characters, `voip_rang` checked entry by entry against `now_ms`; `None` for anything that
/// isn't such an object.
pub fn sanitize_lock_state(value: &serde_json::Value, now_ms: i64) -> Option<String> {
    if !value.is_object() {
        return None;
    }
    let mut state: LockState = serde_json::from_value(value.clone()).ok()?;
    state.inactive = state.inactive.map(|i| i.chars().take(64).collect());
    // Only real package names: they end up in a copy-paste adb command on the device page, and
    // Askama escapes HTML, not the shell (qa-16-17-code #9).
    state.voip_fsi_denied = state
        .voip_fsi_denied
        .into_iter()
        .filter(|p| crate::time_rules::valid_package_name(p))
        .take(MAX_FSI_DENIED)
        .collect();
    // Real package names (shown on the Lock page) and plausible times only, one per package (the
    // newest), newest first.
    let mut rang: Vec<VoipRang> = state
        .voip_rang
        .into_iter()
        .filter(|r| crate::time_rules::valid_package_name(&r.package))
        .filter(|r| r.last_at_ms > 0 && r.last_at_ms <= now_ms.saturating_add(RANG_CLOCK_SKEW_MS))
        .collect();
    rang.sort_by(|a, b| {
        b.last_at_ms
            .cmp(&a.last_at_ms)
            .then_with(|| a.package.cmp(&b.package))
    });
    let mut seen = std::collections::HashSet::new();
    rang.retain(|r| seen.insert(r.package.clone()));
    rang.truncate(MAX_FSI_DENIED);
    state.voip_rang = rang;
    serde_json::to_string(&state).ok()
}

pub fn parse_lock_state(json: Option<&str>) -> Option<LockState> {
    json.and_then(|j| serde_json::from_str(j).ok())
}

/// Lock Screen & PINs' Child PIN group (design 31 step 4): whether a Child PIN is set, its
/// length, and what the phone says about its lock, typed (M5).
#[derive(Debug, Default, PartialEq, Eq)]
pub struct LockCard {
    pub pin_set: bool,
    pub pin_length: i64,
    pub notes: Vec<LockNote>,
}

/// Why the phone's lock is off (`lock_state.inactive`). `no_pin` (no Child PIN yet) is none.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum Inactive {
    /// Not managed: no app list and calls not managed.
    Unmanaged,
    /// The phone still has Android's own PIN or pattern (no double lock).
    AndroidCredential,
    /// Android refused to switch its own lock screen off.
    KeyguardNotDisabled,
    /// The lock crashed several times in a row.
    CrashGuard,
    /// The phone can't read the Child PIN's hash.
    BadHash,
    /// A reason this server doesn't know (at most 64 characters, as stored).
    Other(String),
}

impl Inactive {
    pub fn parse(code: &str) -> Option<Inactive> {
        Some(match code {
            "no_pin" => return None,
            "unmanaged" => Inactive::Unmanaged,
            "android_credential" => Inactive::AndroidCredential,
            "keyguard_not_disabled" => Inactive::KeyguardNotDisabled,
            "crash_guard" => Inactive::CrashGuard,
            "bad_hash" => Inactive::BadHash,
            other => Inactive::Other(other.to_string()),
        })
    }
}

/// What the Child PIN group says about the lock: warnings above the group, states as rows in it.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum LockNote {
    /// Safe mode is allowed while a Child PIN is set: safe mode skips the lock (QA 10 #7).
    SafeModeAllowed,
    /// The reporting launcher doesn't have the lock.
    NeedsUpdate,
    /// The lock is off on the phone, and why.
    Off(Inactive),
    /// An allowed VoIP app (a valid package name) can't show full-screen notifications, so its
    /// calls can't ring over the lock (design 17, QA #11).
    CallsCantRing(String),
    /// No report yet from a launcher that has the lock.
    Waiting,
    /// The lock is on; whether the phone is locked right now.
    On { locked: bool },
    /// Wrong Child PINs in a row.
    WrongPins(i64),
    /// Too many wrong PINs: the next try in about this many minutes.
    Backoff { minutes: i64 },
    /// Times the lock stepped aside for a call, the emergency dialer or an alarm since the last
    /// report.
    SteppedAside(i64),
    /// Design r7 §1.1.3: an allowed app's call rang over the lock - a quiet line, never a
    /// warning. `label` is the app's name on this server (else its package), `when` the time as
    /// the device page shows times.
    RangOverLock { label: String, when: String },
}

impl LockNote {
    /// The words (§4.2): a warning's title and sentence, or a row's label and value; an adb fix
    /// behind the ⓘ. `reported`: when the phone sent this ("10:59", "yesterday 21:40"), on the
    /// "On the phone" row, so an old report doesn't read as now.
    pub fn note(&self, reported: Option<&str>) -> Note {
        let warning = |title: &str, detail: String, fix: Option<String>| Note {
            warning: true,
            title: title.to_string(),
            value: None,
            detail: Some(detail),
            fix,
        };
        let row = |title: &str, value: String, detail: Option<&str>| Note {
            warning: false,
            title: title.to_string(),
            value: Some(value),
            detail: detail.map(str::to_string),
            fix: None,
        };
        match self {
            LockNote::SafeModeAllowed => warning(
                "Safe mode is allowed",
                "Safe mode starts the phone without Vibb One, so it skips the lock. Turn on Block \
                 safe mode in Advanced › Restrictions."
                    .to_string(),
                None,
            ),
            LockNote::NeedsUpdate => warning(
                "Vibb One needs an update",
                "This version has no lock. Until it updates, the phone keeps its own screen lock, if any."
                    .to_string(),
                None,
            ),
            LockNote::Off(Inactive::Unmanaged) => warning(
                "Lock off: phone not managed",
                "The lock works only on a phone with an app list or managed calls.".to_string(),
                None,
            ),
            LockNote::Off(Inactive::AndroidCredential) => warning(
                "Android's screen lock is still on",
                "Remove it so Vibb One's lock can take over: enter the Parent PIN on the phone, then \
                 Settings › Security › Screen lock › None."
                    .to_string(),
                Some("With USB debugging allowed: adb shell locksettings clear --old <PIN>".to_string()),
            ),
            LockNote::Off(Inactive::KeyguardNotDisabled) => warning(
                "Android's lock can't be turned off",
                "Android refused, so Vibb One's lock stays off. Vibb One may no longer manage this phone."
                    .to_string(),
                Some("Check that Vibb One is still the device owner: adb shell dpm list-owners".to_string()),
            ),
            LockNote::Off(Inactive::CrashGuard) => warning(
                "Lock turned off after crashes",
                "It crashed several times in a row. The phone tries it again within 10 minutes of \
                 its next check-in; update Vibb One if this repeats."
                    .to_string(),
                None,
            ),
            LockNote::Off(Inactive::BadHash) => warning(
                "Phone can't read the Child PIN",
                "Only the Parent PIN (\"Parent code\" on the lock) opens it now. Set the Child PIN \
                 again."
                    .to_string(),
                None,
            ),
            LockNote::Off(Inactive::Other(code)) => warning(
                "Lock is off on the phone",
                "Vibb One reported a reason this server doesn't know.".to_string(),
                Some(format!("The phone reported \"{code}\".")),
            ),
            LockNote::CallsCantRing(package) => warning(
                "Calls can't ring over the lock",
                "An allowed call app rings only once the phone is unlocked.".to_string(),
                Some(format!(
                    "{package} may not show full-screen notifications. Allow it once with adb: \
                     adb shell appops set {package} USE_FULL_SCREEN_INTENT allow"
                )),
            ),
            LockNote::Waiting => row("On the phone", "Waiting for a report".to_string(), None),
            LockNote::On { locked } => {
                let state = if *locked { "Locked" } else { "Unlocked" };
                row(
                    "On the phone",
                    match reported {
                        Some(when) => format!("{state} · {when}"),
                        None => state.to_string(),
                    },
                    reported.is_some().then_some("At the phone's last report."),
                )
            }
            LockNote::WrongPins(n) => row("Wrong PINs in a row", n.to_string(), None),
            LockNote::Backoff { minutes } => row(
                "Next try in",
                format!("About {minutes} min"),
                Some("Too many wrong PINs. The Parent PIN (\"Parent code\" on the lock) still opens it."),
            ),
            LockNote::SteppedAside(n) => row(
                "Stepped aside",
                if *n == 1 {
                    "1 time".to_string()
                } else {
                    format!("{n} times")
                },
                Some("For a call, the emergency dialer or an alarm, since the last report."),
            ),
            LockNote::RangOverLock { label, when } => row(
                &format!("Calls from {label}"),
                format!("Rang over the lock · {when}"),
                Some(
                    "An allowed app rang while the phone was locked. Answering opens that app \
                     without the Child PIN, for the call.",
                ),
            ),
        }
    }
}

/// The Child PIN group's "rang over the lock" lines (design r7 §1.1.3): one per package in the
/// phone's `voip_rang`, newest first, named by `labels` (package -> the name the Apps page shows)
/// or else by the package, the time in the device page's zone. None without a Child PIN.
pub fn rang_notes(
    card: &LockCard,
    state: Option<&LockState>,
    labels: &std::collections::HashMap<String, String>,
    zone: &crate::display_time::Zone,
    now: chrono::DateTime<chrono::Utc>,
) -> Vec<LockNote> {
    if !card.pin_set {
        return Vec::new();
    }
    state
        .map(|s| s.voip_rang.as_slice())
        .unwrap_or_default()
        .iter()
        .filter_map(|r| {
            let at = chrono::DateTime::from_timestamp_millis(r.last_at_ms)?;
            let label = labels
                .get(&r.package)
                .map(|l| l.trim())
                .filter(|l| !l.is_empty())
                .unwrap_or(r.package.as_str())
                .to_string();
            Some(LockNote::RangOverLock {
                label,
                when: zone.when(at, now),
            })
        })
        .collect()
}

/// The Child PIN group from the policy and the phone's report (`state`, `None` = no report from a
/// launcher that has the lock), whether the reporting launcher has the lock at all (`capable`),
/// and the safe-boot switch (QA 10 #7: safe mode skips our lock). `now_ms` for the backoff.
pub fn lock_card(
    policy: &DevicePolicy,
    state: Option<&LockState>,
    capable: bool,
    reported: bool,
    now_ms: i64,
) -> LockCard {
    let kid_lock = policy_kid_lock(policy);
    let mut card = LockCard {
        pin_set: kid_lock.is_some(),
        pin_length: kid_lock.as_ref().map_or(4, |k| k.pin_length),
        ..Default::default()
    };
    if kid_lock.is_none() {
        return card;
    }
    if !policy.hardening.disallow_safe_boot {
        card.notes.push(LockNote::SafeModeAllowed);
    }
    if reported && !capable {
        card.notes.push(LockNote::NeedsUpdate);
        return card;
    }
    let Some(state) = state else {
        card.notes.push(LockNote::Waiting);
        return card;
    };
    if let Some(off) = state.inactive.as_deref().and_then(Inactive::parse) {
        card.notes.push(LockNote::Off(off));
    }
    card.notes.extend(
        state
            .voip_fsi_denied
            .iter()
            .map(|package| LockNote::CallsCantRing(package.clone())),
    );
    if state.active {
        card.notes.push(LockNote::On {
            locked: state.locked,
        });
    }
    if state.failures > 0 {
        card.notes.push(LockNote::WrongPins(state.failures));
    }
    if let Some(until) = state.backoff_until_ms.filter(|&u| u > now_ms) {
        card.notes.push(LockNote::Backoff {
            minutes: (until - now_ms + 59_999) / 60_000,
        });
    }
    if state.exempt_yields > 0 {
        card.notes.push(LockNote::SteppedAside(state.exempt_yields));
    }
    card
}

/// A Child PIN is set, but the phone's own lock is off (design 31 L2, an Attention alarm): the
/// reporting launcher doesn't have the lock, or reports it inactive for a reason other than "no
/// PIN yet" - [lock_card]'s warnings about the lock itself.
pub fn lock_off(
    policy: &DevicePolicy,
    state: Option<&LockState>,
    capable: bool,
    reported: bool,
) -> bool {
    if policy_kid_lock(policy).is_none() {
        return false;
    }
    if reported && !capable {
        return true;
    }
    state
        .and_then(|s| s.inactive.as_deref())
        .is_some_and(|inactive| Inactive::parse(inactive).is_some())
}

/// The flash after a Child PIN save (`?notice=<code>`), inside the Child PIN group (design 31
/// §3.0); `true` = a refusal. The Parent PIN's codes are `parent_pin::flash_text`'s.
pub fn flash_text(code: &str) -> Option<(&'static str, bool)> {
    Some(match code {
        "saved" => (
            "Child PIN saved. The phone uses it as soon as it checks in.",
            false,
        ),
        "saved_safe_boot" => (
            "Child PIN saved, and Block safe mode is now on: safe mode would skip the lock.",
            false,
        ),
        "cleared" => (
            "Child PIN removed. The phone opens without a code once it checks in.",
            false,
        ),
        "invalid" => ("Not saved: a Child PIN is 4 to 6 digits.", true),
        "needs_override" => (
            "Not saved: set a Parent PIN first. It opens the lock if the Child PIN is forgotten.",
            true,
        ),
        "same_as_override" => (
            "Not saved: the Child PIN must differ from the Parent PIN.",
            true,
        ),
        _ => return None,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    fn policy_with_pins(kid: Option<&str>, over: Option<&str>) -> DevicePolicy {
        let mut p = DevicePolicy::default();
        if let Some(k) = kid {
            let (h, s) = security::hash_pin(k);
            p.kid_pin_hash = Some(h);
            p.kid_pin_salt = Some(s);
            p.kid_pin_length = Some(k.len() as i64);
        }
        if let Some(o) = over {
            let (h, s) = security::hash_pin(o);
            p.override_pin_hash = Some(h);
            p.override_pin_salt = Some(s);
        }
        p
    }

    #[test]
    fn the_lock_is_off_only_with_a_child_pin_and_a_reason() {
        let with_pin = policy_with_pins(Some("1234"), Some("123456"));
        let without = policy_with_pins(None, Some("123456"));
        let inactive = |reason: Option<&str>| LockState {
            inactive: reason.map(str::to_string),
            ..Default::default()
        };
        // No Child PIN: nothing to be off.
        assert!(!lock_off(
            &without,
            Some(&inactive(Some("crash_guard"))),
            true,
            true
        ));
        // An older launcher without the lock.
        assert!(lock_off(&with_pin, None, false, true));
        // Not reported yet.
        assert!(!lock_off(&with_pin, None, false, false));
        assert!(!lock_off(&with_pin, Some(&inactive(None)), true, true));
        assert!(!lock_off(
            &with_pin,
            Some(&inactive(Some("no_pin"))),
            true,
            true
        ));
        for reason in [
            "unmanaged",
            "android_credential",
            "crash_guard",
            "bad_hash",
            "odd",
        ] {
            assert!(
                lock_off(&with_pin, Some(&inactive(Some(reason))), true, true),
                "{reason}"
            );
        }
    }

    #[test]
    fn kid_pin_validation() {
        assert!(valid_kid_pin("1234"));
        assert!(valid_kid_pin("123456"));
        assert!(!valid_kid_pin("123"));
        assert!(!valid_kid_pin("1234567"));
        assert!(!valid_kid_pin("12a4"));
        assert!(!valid_kid_pin("１２３４"));
        assert!(!valid_kid_pin(""));
    }

    #[test]
    fn kid_pin_needs_and_differs_from_override() {
        assert_eq!(
            decide_kid_pin("1234", None, None),
            Err(KidPinRefusal::NeedsOverride)
        );
        let p = policy_with_pins(None, Some("123456"));
        let (h, s) = (
            p.override_pin_hash.as_deref(),
            p.override_pin_salt.as_deref(),
        );
        assert_eq!(
            decide_kid_pin("123456", h, s),
            Err(KidPinRefusal::SameAsOverride)
        );
        assert_eq!(decide_kid_pin("12", h, s), Err(KidPinRefusal::Invalid));
        let (hash, salt, len) = decide_kid_pin("4321", h, s).unwrap();
        assert!(security::verify_pin("4321", &hash, &salt));
        assert_eq!(len, 4);
    }

    #[test]
    fn override_cross_check() {
        let p = policy_with_pins(Some("654321"), None);
        assert!(override_conflicts("654321", &p));
        assert!(!override_conflicts("6543210", &p));
        assert!(!override_conflicts("654321", &DevicePolicy::default()));
    }

    #[test]
    fn half_written_kid_lock_is_off() {
        let mut p = policy_with_pins(Some("1234"), Some("999999"));
        assert!(policy_kid_lock(&p).is_some());
        p.kid_pin_length = Some(9);
        assert!(policy_kid_lock(&p).is_none());
        p.kid_pin_length = None;
        assert!(policy_kid_lock(&p).is_none());
    }

    #[test]
    fn card_warns_about_safe_boot_capability_and_inactive_states() {
        let mut p = policy_with_pins(Some("1234"), Some("999999"));
        let card = lock_card(&p, None, false, true, 0);
        assert_eq!(
            card.notes,
            [LockNote::SafeModeAllowed, LockNote::NeedsUpdate]
        );

        p.hardening.disallow_safe_boot = true;
        for (inactive, typed) in [
            ("crash_guard", Inactive::CrashGuard),
            ("android_credential", Inactive::AndroidCredential),
            ("bad_hash", Inactive::BadHash),
            ("keyguard_not_disabled", Inactive::KeyguardNotDisabled),
            ("unmanaged", Inactive::Unmanaged),
            ("odd", Inactive::Other("odd".to_string())),
        ] {
            let state = LockState {
                inactive: Some(inactive.to_string()),
                ..Default::default()
            };
            let card = lock_card(&p, Some(&state), true, true, 0);
            assert_eq!(card.notes, [LockNote::Off(typed)], "{inactive}");
            assert!(card.notes[0].note(None).warning, "{inactive}");
        }
        let state = LockState {
            inactive: Some("no_pin".to_string()),
            ..Default::default()
        };
        assert!(lock_card(&p, Some(&state), true, true, 0).notes.is_empty());
        // No report yet from a launcher with the lock.
        assert_eq!(
            lock_card(&p, None, true, false, 0).notes,
            [LockNote::Waiting]
        );

        let state = LockState {
            active: true,
            locked: true,
            failures: 6,
            backoff_until_ms: Some(90_000),
            exempt_yields: 2,
            ..Default::default()
        };
        let card = lock_card(&p, Some(&state), true, true, 0);
        assert_eq!(
            card.notes,
            [
                LockNote::On { locked: true },
                LockNote::WrongPins(6),
                LockNote::Backoff { minutes: 2 },
                LockNote::SteppedAside(2),
            ]
        );
        let words: Vec<(String, Option<String>)> = card
            .notes
            .iter()
            .map(|n| n.note(None))
            .map(|n| {
                assert!(!n.warning);
                (n.title, n.value)
            })
            .collect();
        assert_eq!(
            words,
            [
                ("On the phone".to_string(), Some("Locked".to_string())),
                ("Wrong PINs in a row".to_string(), Some("6".to_string())),
                ("Next try in".to_string(), Some("About 2 min".to_string())),
                ("Stepped aside".to_string(), Some("2 times".to_string())),
            ]
        );
    }

    #[test]
    fn warnings_are_short_and_keep_commands_behind_the_info() {
        let notes = [
            LockNote::SafeModeAllowed,
            LockNote::NeedsUpdate,
            LockNote::Off(Inactive::Unmanaged),
            LockNote::Off(Inactive::AndroidCredential),
            LockNote::Off(Inactive::KeyguardNotDisabled),
            LockNote::Off(Inactive::CrashGuard),
            LockNote::Off(Inactive::BadHash),
            LockNote::Off(Inactive::Other("odd".to_string())),
            LockNote::CallsCantRing("io.element.android.x".to_string()),
        ];
        for note in notes {
            let words = note.note(None);
            assert!(words.warning, "{note:?}");
            // §4.2: a bold title of up to 6 words + 1 sentence; commands and packages in the ⓘ.
            assert!(words.title.split_whitespace().count() <= 6, "{note:?}");
            let detail = words.detail.unwrap();
            for hidden in [
                "adb ",
                "io.element",
                "appops",
                "sync",
                "device owner",
                "\"odd\"",
            ] {
                assert!(!detail.contains(hidden), "{note:?}: {detail}");
            }
        }
        let fix = LockNote::Off(Inactive::AndroidCredential)
            .note(None)
            .fix
            .unwrap();
        assert!(fix.contains("adb shell locksettings clear --old <PIN>"));
    }

    #[test]
    fn card_warns_about_voip_apps_without_full_screen_intents() {
        let mut p = policy_with_pins(Some("1234"), Some("999999"));
        p.hardening.disallow_safe_boot = true;
        let state = LockState {
            active: true,
            voip_fsi_denied: vec!["io.element.android.x".to_string()],
            ..Default::default()
        };
        let card = lock_card(&p, Some(&state), true, true, 0);
        assert_eq!(
            card.notes,
            [
                LockNote::CallsCantRing("io.element.android.x".to_string()),
                LockNote::On { locked: false },
            ]
        );
        let fix = card.notes[0].note(None).fix.unwrap();
        assert!(fix.contains("io.element.android.x"));
        assert!(fix.contains("USE_FULL_SCREEN_INTENT"));
    }

    #[test]
    fn voip_fsi_denied_keeps_only_package_names_and_is_capped() {
        let many: Vec<String> = (0..20).map(|i| format!("org.example.app{i}")).collect();
        let stored =
            sanitize_lock_state(&serde_json::json!({ "voip_fsi_denied": many }), 0).unwrap();
        let state: LockState = serde_json::from_str(&stored).unwrap();
        assert_eq!(state.voip_fsi_denied.len(), MAX_FSI_DENIED);
        // Nothing that could reach the parent's shell through the adb command.
        let long = format!("{}.y", "x".repeat(300));
        let hostile = serde_json::json!({ "voip_fsi_denied": [
            "io.element.android.x; rm -rf ~", "$(reboot)", "a.b`id`", "nodots", "1x.y", "io.element.android.x", long,
        ]});
        let stored = sanitize_lock_state(&hostile, 0).unwrap();
        let state: LockState = serde_json::from_str(&stored).unwrap();
        assert_eq!(
            state.voip_fsi_denied,
            vec!["io.element.android.x".to_string()]
        );
        // An older launcher sends none.
        let old = sanitize_lock_state(&serde_json::json!({ "active": true }), 0).unwrap();
        let state: LockState = serde_json::from_str(&old).unwrap();
        assert!(state.voip_fsi_denied.is_empty());
        assert!(state.voip_rang.is_empty());
    }

    #[test]
    fn voip_rang_is_checked_entry_by_entry() {
        let now = 1_800_000_000_000_i64;
        let mut entries: Vec<serde_json::Value> = (0..12)
            .map(|i| serde_json::json!({ "package": format!("org.example.app{i}"), "last_at_ms": now - 60_000 * i }))
            .collect();
        entries.extend([
            // Not a package name, a float time, no time, a string, a future time, a duplicate.
            serde_json::json!({ "package": "$(reboot)", "last_at_ms": now }),
            serde_json::json!({ "package": "org.example.float", "last_at_ms": 1.5 }),
            serde_json::json!({ "package": "org.example.none" }),
            serde_json::json!("org.example.string"),
            serde_json::json!({ "package": "org.example.future", "last_at_ms": now + 3_600_000 }),
            serde_json::json!({ "package": "org.example.app3", "last_at_ms": now - 3_600_000 }),
        ]);
        let stored = sanitize_lock_state(
            &serde_json::json!({ "active": true, "failures": 3, "voip_rang": entries }),
            now,
        )
        .unwrap();
        let state: LockState = serde_json::from_str(&stored).unwrap();
        // The rest of lock_state stays.
        assert!(state.active);
        assert_eq!(state.failures, 3);
        let packages: Vec<&str> = state.voip_rang.iter().map(|r| r.package.as_str()).collect();
        assert_eq!(
            packages,
            (0..8)
                .map(|i| format!("org.example.app{i}"))
                .collect::<Vec<_>>(),
            "newest first, capped, one per package (its newest), bad entries dropped"
        );
        assert_eq!(state.voip_rang[3].last_at_ms, now - 180_000);
        // A little clock skew is taken.
        let stored = sanitize_lock_state(
            &serde_json::json!({ "voip_rang": [{ "package": "org.example.a", "last_at_ms": now + 60_000 }] }),
            now,
        )
        .unwrap();
        assert_eq!(
            serde_json::from_str::<LockState>(&stored)
                .unwrap()
                .voip_rang
                .len(),
            1
        );
        // Not a list: empty, never the whole lock_state.
        let stored = sanitize_lock_state(
            &serde_json::json!({ "locked": true, "voip_rang": "x" }),
            now,
        )
        .unwrap();
        let state: LockState = serde_json::from_str(&stored).unwrap();
        assert!(state.locked);
        assert!(state.voip_rang.is_empty());
    }

    #[test]
    fn rang_notes_name_the_app_and_its_time() {
        let zone = crate::display_time::Zone::from_setting("Europe/Oslo");
        let now = chrono::DateTime::parse_from_rfc3339("2026-10-20T12:30:00Z")
            .unwrap()
            .with_timezone(&chrono::Utc);
        let at = |rfc: &str| {
            chrono::DateTime::parse_from_rfc3339(rfc)
                .unwrap()
                .timestamp_millis()
        };
        let state = LockState {
            active: true,
            voip_rang: vec![
                VoipRang {
                    package: "org.thoughtcrime.securesms".to_string(),
                    last_at_ms: at("2026-10-20T12:02:00Z"),
                },
                VoipRang {
                    package: "com.whatsapp".to_string(),
                    last_at_ms: at("2026-10-19T19:40:00Z"),
                },
            ],
            ..Default::default()
        };
        let labels = std::collections::HashMap::from([(
            "org.thoughtcrime.securesms".to_string(),
            "Signal".to_string(),
        )]);
        let p = policy_with_pins(Some("1234"), Some("999999"));
        let card = lock_card(&p, Some(&state), true, true, 0);
        // Not in lock_card's own notes (the page adds them with the names).
        assert!(
            !card
                .notes
                .iter()
                .any(|n| matches!(n, LockNote::RangOverLock { .. }))
        );
        let notes = rang_notes(&card, Some(&state), &labels, &zone, now);
        let words: Vec<(String, Option<String>, bool)> = notes
            .iter()
            .map(|n| n.note(None))
            .map(|n| (n.title, n.value, n.warning))
            .collect();
        assert_eq!(
            words,
            [
                (
                    "Calls from Signal".to_string(),
                    Some("Rang over the lock · 14:02".to_string()),
                    false
                ),
                (
                    "Calls from com.whatsapp".to_string(),
                    Some("Rang over the lock · yesterday 21:40".to_string()),
                    false
                ),
            ]
        );
        // No Child PIN: nothing.
        let card = lock_card(&DevicePolicy::default(), Some(&state), true, true, 0);
        assert!(rang_notes(&card, Some(&state), &labels, &zone, now).is_empty());
    }

    #[test]
    fn no_pin_no_warnings() {
        let card = lock_card(&DevicePolicy::default(), None, false, true, 0);
        assert!(!card.pin_set);
        assert!(card.notes.is_empty());
    }
}
