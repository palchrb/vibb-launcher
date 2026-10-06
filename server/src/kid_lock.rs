//! Handy's own PIN lock (handy step 10, design `docs/design/10-lock-and-call-ui.md` in the handy
//! workspace, with `qa-10-design.md` on top): the kid's PIN rules, the cross-check with the
//! override PIN, and what the device page says about the phone's `lock_state`. Pure apart from
//! the PBKDF2 calls in [decide_kid_pin]/[override_conflicts]; unit-tested here, end to end in
//! `src/tests/step10.rs`.

use serde::{Deserialize, Serialize};

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
}

/// How many `voip_fsi_denied` packages are kept.
const MAX_FSI_DENIED: usize = 8;

/// `StatusReportRequest.lock_state` as stored: only the [LockState] fields, `inactive` capped at
/// 64 characters; `None` for anything that isn't such an object.
pub fn sanitize_lock_state(value: &serde_json::Value) -> Option<String> {
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
    serde_json::to_string(&state).ok()
}

pub fn parse_lock_state(json: Option<&str>) -> Option<LockState> {
    json.and_then(|j| serde_json::from_str(j).ok())
}

/// The device page's "Screen lock" card: status lines and warnings.
#[derive(Debug, Default, PartialEq, Eq)]
pub struct LockCard {
    pub pin_set: bool,
    pub pin_length: i64,
    pub lines: Vec<String>,
    pub warnings: Vec<String>,
}

/// What the parent should know: the phone's report (`state`, `None` = no report from a launcher
/// that has the lock), whether the reporting launcher has the lock at all (`capable`), and the
/// safe-boot switch (QA 10 #7: safe mode skips our lock). `now_ms` formats the backoff.
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
        card.lines
            .push("No kid PIN is set: the phone has no lock screen code.".to_string());
        return card;
    }
    if !policy.hardening.disallow_safe_boot {
        card.warnings.push(
            "Safe mode is allowed (Phone hardening). Safe mode starts the phone without the \
             launcher, so it skips the kid's lock - block safe mode while the lock is on."
                .to_string(),
        );
    }
    if reported && !capable {
        card.warnings.push(
            "This phone's launcher doesn't have handy's lock yet - update the launcher. Until \
             then the phone keeps whatever screen lock it has."
                .to_string(),
        );
        return card;
    }
    let Some(state) = state else {
        card.lines
            .push("Waiting for the phone to report its lock.".to_string());
        return card;
    };
    if state.active {
        card.lines.push(if state.locked {
            "The lock is on and the phone is locked right now.".to_string()
        } else {
            "The lock is on (the phone is unlocked right now).".to_string()
        });
    }
    if state.failures > 0 {
        card.lines
            .push(format!("Wrong PINs in a row: {}.", state.failures));
    }
    if let Some(until) = state.backoff_until_ms.filter(|&u| u > now_ms) {
        let mins = (until - now_ms + 59_999) / 60_000;
        card.lines.push(format!(
            "Too many wrong PINs: the phone waits about {mins} min before the next try. The \
             unlock code (override PIN) still works on the lock."
        ));
    }
    if state.exempt_yields > 0 {
        card.lines.push(format!(
            "The lock stepped aside {} time(s) for a call, the emergency dialer or an alarm \
             since the last report.",
            state.exempt_yields
        ));
    }
    if let Some(warning) = state.inactive.as_deref().and_then(inactive_warning) {
        card.warnings.push(warning);
    }
    for package in &state.voip_fsi_denied {
        card.warnings.push(format!(
            "{package} may not show full-screen notifications, so its calls can't ring over the \
             lock (they only ring once the phone is unlocked). Allow it once with adb: \
             adb shell appops set {package} USE_FULL_SCREEN_INTENT allow"
        ));
    }
    card
}

/// The warning for a `lock_state.inactive` value; `no_pin` (the parent hasn't set one) is none.
pub fn inactive_warning(inactive: &str) -> Option<String> {
    Some(match inactive {
        "no_pin" => return None,
        "unmanaged" => "The lock is off because this phone isn't managed (no app list and calls \
            not managed)."
            .to_string(),
        "android_credential" => "Remove the Android screen lock to switch to handy's lock. The \
            phone still has Android's own PIN/pattern, so handy's lock stays off (no double lock). \
            Enter the unlock code on the phone, then Settings > Security > Screen lock > None - or \
            with USB debugging allowed: adb shell locksettings clear --old <PIN>."
            .to_string(),
        "keyguard_not_disabled" => "Android refused to switch its own lock screen off, so \
            handy's lock stays off. Check the phone (is it still device owner?)."
            .to_string(),
        "crash_guard" => "Handy's lock crashed several times in a row and is switched off. The \
            phone tries it again at the next sync (at most every 10 minutes). Update the launcher \
            if this repeats."
            .to_string(),
        "bad_hash" => "The phone can't read the kid's PIN, so only the unlock code (override PIN) \
            opens the lock. Set the kid's PIN again."
            .to_string(),
        other => format!("The lock is off on the phone: {other}."),
    })
}

/// Flash text for `?kid_lock=<code>` after a save.
pub fn flash_text(code: &str) -> Option<&'static str> {
    Some(match code {
        "saved" => "The kid's PIN is saved. The phone uses it after its next sync.",
        "saved_safe_boot" => {
            "The kid's PIN is saved, and safe mode is now blocked (Phone \
            hardening) - safe mode would skip the lock. The phone uses the PIN after its next sync."
        }
        "cleared" => {
            "The kid's PIN is removed: the phone has no lock screen code after its next \
            sync."
        }
        "invalid" => "Not saved: the kid's PIN must be 4 to 6 digits.",
        "needs_override" => {
            "Not saved: set an unlock code (offline override PIN) first. It is \
            the parent code on the phone's lock screen - without it, a forgotten kid PIN would \
            lock the kid out."
        }
        "same_as_override" => "Not saved: the kid's PIN must not be the unlock code.",
        "override_is_kid_pin" => "Unlock code not changed: it must not be the kid's PIN.",
        "override_needed_by_lock" => {
            "Unlock code not removed: the kid's lock needs it (it is \
            the parent code on the lock screen). Remove the kid's PIN first."
        }
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
        assert!(
            card.warnings
                .iter()
                .any(|w| w.contains("Safe mode is allowed"))
        );
        assert!(
            card.warnings
                .iter()
                .any(|w| w.contains("doesn't have handy's lock"))
        );

        p.hardening.disallow_safe_boot = true;
        for inactive in [
            "crash_guard",
            "android_credential",
            "bad_hash",
            "keyguard_not_disabled",
        ] {
            let state = LockState {
                inactive: Some(inactive.to_string()),
                ..Default::default()
            };
            let card = lock_card(&p, Some(&state), true, true, 0);
            assert_eq!(card.warnings.len(), 1, "{inactive}");
        }
        let state = LockState {
            inactive: Some("no_pin".to_string()),
            ..Default::default()
        };
        assert!(
            lock_card(&p, Some(&state), true, true, 0)
                .warnings
                .is_empty()
        );

        let state = LockState {
            active: true,
            locked: true,
            failures: 6,
            backoff_until_ms: Some(90_000),
            ..Default::default()
        };
        let card = lock_card(&p, Some(&state), true, true, 0);
        assert!(card.warnings.is_empty());
        assert!(card.lines.iter().any(|l| l.contains("about 2 min")));
        assert!(
            card.lines
                .iter()
                .any(|l| l.contains("Wrong PINs in a row: 6"))
        );
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
        assert_eq!(card.warnings.len(), 1);
        assert!(card.warnings[0].contains("io.element.android.x"));
        assert!(card.warnings[0].contains("USE_FULL_SCREEN_INTENT"));
    }

    #[test]
    fn voip_fsi_denied_keeps_only_package_names_and_is_capped() {
        let many: Vec<String> = (0..20).map(|i| format!("org.example.app{i}")).collect();
        let stored = sanitize_lock_state(&serde_json::json!({ "voip_fsi_denied": many })).unwrap();
        let state: LockState = serde_json::from_str(&stored).unwrap();
        assert_eq!(state.voip_fsi_denied.len(), MAX_FSI_DENIED);
        // Nothing that could reach the parent's shell through the adb command.
        let long = format!("{}.y", "x".repeat(300));
        let hostile = serde_json::json!({ "voip_fsi_denied": [
            "io.element.android.x; rm -rf ~", "$(reboot)", "a.b`id`", "nodots", "1x.y", "io.element.android.x", long,
        ]});
        let stored = sanitize_lock_state(&hostile).unwrap();
        let state: LockState = serde_json::from_str(&stored).unwrap();
        assert_eq!(
            state.voip_fsi_denied,
            vec!["io.element.android.x".to_string()]
        );
        // An older launcher sends none.
        let old = sanitize_lock_state(&serde_json::json!({ "active": true })).unwrap();
        let state: LockState = serde_json::from_str(&old).unwrap();
        assert!(state.voip_fsi_denied.is_empty());
    }

    #[test]
    fn no_pin_no_warnings() {
        let card = lock_card(&DevicePolicy::default(), None, false, true, 0);
        assert!(!card.pin_set);
        assert!(card.warnings.is_empty());
    }
}
