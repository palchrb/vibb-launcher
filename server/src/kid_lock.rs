//! Handy's own PIN lock (handy step 10, design `docs/design/10-lock-and-call-ui.md` in the handy
//! workspace, with `qa-10-design.md` on top): the kid's PIN rules, the cross-check with the
//! override PIN, and what Lock Screen & PINs says about the phone's `lock_state`. Pure apart from
//! the PBKDF2 calls in [decide_kid_pin]/[override_conflicts] and design 59's command queue
//! ([queue_pin_command], [cancel_pin_command], [after_lock_report]); unit-tested here, end to end
//! in `src/tests/step10.rs` and `src/tests/kid_keyguard.rs`.

use serde::{Deserialize, Serialize};

use crate::handlers::device_pages::Note;
use crate::kid_pin::KidPin;
use crate::models::{DevicePolicy, KidLock};
use crate::music_secret::MusicKey;
use crate::security;

/// The launcher capability that understands `kid_lock`.
pub const PIN_LOCK_CAPABILITY: &str = "pin_lock_v1";
/// Design 59: the launcher capability that runs [SET_KID_PIN] and can hold Android's lock.
pub const ANDROID_KEYGUARD_CAPABILITY: &str = "android_keyguard_v1";
/// Design 59 §2.1: the one-shot command that hands the phone the Child PIN for Android's lock.
/// It never expires (like `lock`) and its row holds no PIN (`pending_command.pin` is filled at
/// delivery).
pub const SET_KID_PIN: &str = "set_kid_pin";
/// `device_policy.kid_keyguard` and `kid_lock.keyguard`: Android's own lock screen.
pub const KEYGUARD_ANDROID: &str = "android";
/// Vibb One's own lock (today's); anything stored but [KEYGUARD_ANDROID] is sent as this.
pub const KEYGUARD_OWN: &str = "own";
/// The highest `lock_state.keyguard_relocks` kept.
const MAX_RELOCKS: i64 = 1000;
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
        keyguard: if policy.kid_keyguard == KEYGUARD_ANDROID {
            KEYGUARD_ANDROID
        } else {
            KEYGUARD_OWN
        },
        pin_rev: policy.kid_pin_rev,
    })
}

/// "Waiting" (design 59 §2.1): a [SET_KID_PIN] row the pop will still hand out for the first time.
/// One that went out is never waiting: a save after a delivery queues a new row (a new id).
fn waiting_pin_row() -> String {
    format!(
        "device_id = ?1 AND command = ?2 AND delivered_at IS NULL AND {}",
        crate::models::COMMAND_OPEN
    )
}

/// Design 59 §2.1's queue rule, the one place a [SET_KID_PIN] row is made: the keyguard is
/// android, a sealed PIN is stored, the phone's last report has [ANDROID_KEYGUARD_CAPABILITY]
/// (no row for an older launcher at all) and none is waiting. After a Child PIN save, the switch
/// to android and "Send the Child PIN again" (`report: None`), and at a status report (`report`,
/// [after_lock_report]): then only when the phone needs the PIN - it reports Vibb One's lock (a
/// phone updated after the switch), or a rev that is a number other than the current one (never
/// `null`, a PIN changed on the phone: open question 4) - and while the newest row of this
/// enrollment is neither in flight (delivered within the re-delivery window and not used up, no
/// answer yet: no duplicate) nor answered (a refusal, or an answer that didn't change the report,
/// e.g. an unmanaged phone keeps reporting its own lock: never a loop; the parent's "Send again"
/// retries). An answered row carried the PIN of the rev stored when it went out: the pop hands a
/// row out only at the rev it read (S59-1). Reports and rows from before the enrollment are an
/// earlier install's (S59-2, S59-3). One statement, so a save can run it in its own transaction.
/// Returns whether a row was made.
pub async fn queue_pin_command<'e, E: sqlx::SqliteExecutor<'e>>(
    db: E,
    device_id: i64,
    report: Option<&LockState>,
) -> Result<bool, sqlx::Error> {
    // The last report's capabilities, as `device_api::last_capabilities` reads them.
    let sql = format!(
        "INSERT INTO device_commands (device_id, command) \
         SELECT device_id, ?2 FROM device_policy \
         WHERE device_id = ?1 AND kid_keyguard = ?3 AND kid_pin_sealed IS NOT NULL \
           AND EXISTS (SELECT 1 FROM device_status s, json_each(CASE \
               WHEN json_valid(s.capabilities_json) THEN s.capabilities_json ELSE '[]' END) c \
             WHERE s.id = (SELECT id FROM device_status WHERE device_id = ?1 \
               AND reported_at >= {enrolled} ORDER BY reported_at DESC, id DESC LIMIT 1) \
               AND c.value = ?8) \
           AND NOT EXISTS (SELECT 1 FROM device_commands WHERE {waiting}) \
           AND (NOT ?4 OR ((?5 OR (?6 IS NOT NULL AND ?6 != kid_pin_rev)) \
             AND NOT EXISTS (SELECT 1 FROM device_commands WHERE id = \
               (SELECT MAX(id) FROM device_commands WHERE device_id = ?1 AND command = ?2 \
                 AND requested_at >= {enrolled}) \
               AND (acknowledged_at IS NOT NULL OR (cancelled_at IS NULL AND deliveries < ?9 \
                    AND first_delivered_at > datetime('now', ?7))))))",
        waiting = waiting_pin_row(),
        enrolled = crate::models::ENROLLED_AT
    );
    let done = sqlx::query(&sql)
        .bind(device_id)
        .bind(SET_KID_PIN)
        .bind(KEYGUARD_ANDROID)
        .bind(report.is_some())
        .bind(report.is_some_and(|r| r.keyguard.as_deref() == Some(KEYGUARD_OWN)))
        .bind(report.and_then(|r| r.pin_rev))
        .bind(format!(
            "-{} minutes",
            crate::models::REDELIVER_WINDOW_MINUTES
        ))
        .bind(ANDROID_KEYGUARD_CAPABILITY)
        .bind(crate::models::MAX_COMMAND_DELIVERIES)
        .execute(db)
        .await?;
    Ok(done.rows_affected() > 0)
}

/// A waiting [SET_KID_PIN] row is cancelled (`reason`: `kid_pin_cleared`, `keyguard_own`): no PIN
/// to hand over, or Vibb One's lock again.
pub async fn cancel_pin_command<'e, E: sqlx::SqliteExecutor<'e>>(
    db: E,
    device_id: i64,
    reason: &str,
) -> Result<u64, sqlx::Error> {
    let sql = format!(
        "UPDATE device_commands SET cancelled_at = datetime('now'), cancel_reason = ?3 \
         WHERE {}",
        waiting_pin_row()
    );
    Ok(sqlx::query(&sql)
        .bind(device_id)
        .bind(SET_KID_PIN)
        .bind(reason)
        .execute(db)
        .await?
        .rows_affected())
}

/// A status report's `lock_state` (design 59 §2.1), from a launcher with
/// [ANDROID_KEYGUARD_CAPABILITY] (`capable`, its own report): the PIN the phone last confirmed is
/// forgotten once it reports the current rev, and [queue_pin_command] runs with the report.
/// Returns whether a command was queued (the caller nudges the phone).
pub async fn after_lock_report(
    db: &sqlx::SqlitePool,
    device_id: i64,
    state: &LockState,
    capable: bool,
) -> Result<bool, sqlx::Error> {
    if !capable {
        return Ok(false);
    }
    if let Some(rev) = state.pin_rev {
        sqlx::query(
            "UPDATE device_policy SET kid_pin_prev_sealed = NULL, kid_pin_prev_rev = NULL \
             WHERE device_id = ? AND kid_pin_rev = ? AND kid_pin_prev_rev IS NOT NULL",
        )
        .bind(device_id)
        .bind(rev)
        .execute(db)
        .await?;
    }
    queue_pin_command(db, device_id, Some(state)).await
}

/// `pending_command.pin` for a popped [SET_KID_PIN] (design 59 §2.1): the PIN sealed in `policy`
/// (the row the answer's `kid_lock` comes from), only in android mode and for a launcher whose
/// last report has [ANDROID_KEYGUARD_CAPABILITY]. `None` otherwise, or when this server can't
/// open it (no key, another key, a legacy PIN) - the phone then answers `refused: no_pin`.
pub fn deliverable_pin(
    key: Option<&MusicKey>,
    policy: &DevicePolicy,
    device_id: i64,
    capabilities: &[String],
) -> Option<KidPin> {
    if policy.kid_keyguard != KEYGUARD_ANDROID
        || !capabilities
            .iter()
            .any(|c| c == ANDROID_KEYGUARD_CAPABILITY)
    {
        return None;
    }
    let pin = key
        .zip(policy.kid_pin_sealed.as_deref())
        .and_then(|(key, sealed)| crate::kid_pin::open(key, device_id, sealed));
    if pin.is_none() {
        tracing::warn!(
            device_id,
            "set_kid_pin goes without the Child PIN: this server holds none it can open"
        );
    }
    pin
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
    /// Design 59 §1: the gate the phone runs - [KEYGUARD_ANDROID] while Android's PIN is the gate
    /// (also while a rollback waits for the token: then `inactive` is `android_credential`, why
    /// Vibb One's lock is off), else [KEYGUARD_OWN]; any other word is dropped. `None` from a
    /// launcher before design 59 - told apart by [ANDROID_KEYGUARD_CAPABILITY], never by this.
    pub keyguard: Option<String>,
    /// The `kid_lock.pin_rev` the gate enforces; `None` = unknown (a PIN changed on the phone) or
    /// an older launcher. Negative values are dropped.
    pub pin_rev: Option<i64>,
    /// The launcher's reset token is active (it can change or clear Android's PIN).
    pub reset_token: bool,
    /// Times Vibb One locked Android's keyguard under its glance since the last report (§3.3),
    /// 0..=1000.
    pub keyguard_relocks: i64,
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
    state.keyguard = state
        .keyguard
        .filter(|k| k == KEYGUARD_ANDROID || k == KEYGUARD_OWN);
    state.pin_rev = state.pin_rev.filter(|&rev| rev >= 0);
    state.keyguard_relocks = state.keyguard_relocks.clamp(0, MAX_RELOCKS);
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
    /// The lock is on; whether the phone is locked right now, and whether Android's lock is the
    /// gate (design 59).
    On { locked: bool, android: bool },
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
    /// Design 59 §2.3: Android's lock is chosen, but the reporting launcher can't hold it.
    AndroidNeedsUpdate,
    /// Android's lock is chosen and the phone still runs Vibb One's: it starts the next time the
    /// Child PIN is typed there, or when the PIN being sent (`sending`) arrives.
    AndroidWaiting { sending: bool },
    /// The phone runs Android's lock with a PIN that isn't the Child PIN (changed on the phone);
    /// `stored`: this server keeps a copy it can send again.
    PinNotChildPin { stored: bool },
    /// The phone's lock still checks another Child PIN than the one saved here.
    PinBehind,
    /// Android's lock is on without Vibb One's reset token: the PIN can't be changed or cleared
    /// until the child unlocks the phone once.
    NoResetToken,
    /// Vibb One's lock is chosen again and the phone still runs Android's; `stuck`: without the
    /// token (qa-59-revision N-C: `keyguard: android` with `inactive: android_credential`).
    SwitchingBack { stuck: bool },
    /// Times Vibb One locked Android's lock under its own lock screen since the last report.
    Relocked(i64),
    /// The newest `set_kid_pin` ([pin_send]).
    PinSent(PinSend),
}

/// Where the newest [SET_KID_PIN] stands (design 59 §2.3), the times as the page shows them.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum PinSend {
    /// Not fetched yet.
    Waiting,
    /// Fetched, not confirmed yet (in flight).
    Sent { at: String },
    /// Fetched and never confirmed: it may not have reached the phone.
    Unconfirmed { at: String },
    /// The phone confirmed it, no answer yet (in flight).
    Taken { sent: String, taken: String },
    /// Confirmed long ago, never answered.
    NoAnswer { taken: String },
    /// The phone's answer.
    Answered { answer: PinAnswer, at: String },
    /// An answer this server doesn't know, as the phone sent it.
    Other { raw: String, at: String },
}

/// The launcher's answers to [SET_KID_PIN] (design 59 §1), typed: worded here for the Lock page
/// and the summary's Activity alike.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum PinAnswer {
    /// `pin set`: Android's PIN is the Child PIN now.
    Set,
    /// `own lock`: Vibb One's lock was chosen again before the phone ran it.
    OwnLock,
    /// `refused: no_token`.
    NoToken,
    /// `refused: mismatch`: the PIN didn't verify against the policy's hash.
    Mismatch,
    /// `refused: no_pin`: the command came without the PIN.
    NoPin,
    /// `refused: failed`: Android refused it.
    Failed,
}

impl PinAnswer {
    pub fn parse(raw: &str) -> Option<PinAnswer> {
        Some(match raw.trim() {
            "pin set" => PinAnswer::Set,
            "own lock" => PinAnswer::OwnLock,
            "refused: no_token" => PinAnswer::NoToken,
            "refused: mismatch" => PinAnswer::Mismatch,
            "refused: no_pin" => PinAnswer::NoPin,
            "refused: failed" => PinAnswer::Failed,
            _ => return None,
        })
    }

    /// The answer in a few words (Activity's line, `locate::reword`).
    pub fn words(self) -> &'static str {
        match self {
            PinAnswer::Set => "Child PIN set on the phone",
            PinAnswer::OwnLock => "Not needed: Vibb One's lock is on",
            PinAnswer::NoToken => "Refused: Vibb One can't change Android's PIN yet",
            PinAnswer::Mismatch => "Refused: not the phone's new Child PIN",
            PinAnswer::NoPin => "Refused: the server sent no PIN",
            PinAnswer::Failed => "Android refused the new PIN",
        }
    }

    /// The Lock page's value word and the sentence behind the row's ⓘ.
    fn row(self) -> (&'static str, Option<&'static str>) {
        match self {
            PinAnswer::Set => ("Set", None),
            PinAnswer::OwnLock => (
                "Not needed",
                Some("Vibb One's lock was chosen again before the phone got it."),
            ),
            PinAnswer::NoToken => (
                "Refused",
                Some(
                    "Vibb One can't change Android's PIN until the child unlocks the phone once. \
                     Then send it again.",
                ),
            ),
            PinAnswer::Mismatch => (
                "Refused",
                Some("It didn't match the Child PIN the phone had just been given. Send it again."),
            ),
            PinAnswer::NoPin => (
                "Refused",
                Some(
                    "This server had no copy of the Child PIN it could send. Set the Child PIN \
                     again.",
                ),
            ),
            PinAnswer::Failed => (
                "Failed",
                Some("Android didn't take it. Send it again, or set another Child PIN."),
            ),
        }
    }
}

/// The newest [SET_KID_PIN] of this enrollment as a [PinSend]; `None` for another command, or one
/// cancelled or expired (nothing to say). `receipts`: the phone confirms commands.
pub fn pin_send(
    command: &crate::models::DeviceCommand,
    receipts: bool,
    zone: &crate::display_time::Zone,
    now: chrono::DateTime<chrono::Utc>,
) -> Option<PinSend> {
    use crate::models::{CommandStatus, Unconfirmed};
    if command.command != SET_KID_PIN {
        return None;
    }
    let at = |t: Option<chrono::DateTime<chrono::Utc>>| {
        t.map_or_else(|| "time unknown".to_string(), |t| zone.at(t, now))
    };
    Some(match command.status(now.naive_utc(), receipts) {
        CommandStatus::Done { at: answered } | CommandStatus::Failed { at: answered } => {
            let raw = command.result.as_deref().unwrap_or_default();
            match PinAnswer::parse(raw) {
                Some(answer) => PinSend::Answered {
                    answer,
                    at: at(answered),
                },
                None => PinSend::Other {
                    raw: raw.trim().chars().take(100).collect(),
                    at: at(answered),
                },
            }
        }
        CommandStatus::Received {
            at: taken,
            overdue: false,
        } => PinSend::Taken {
            sent: at(command
                .delivered_at
                .as_deref()
                .and_then(crate::display_time::parse_db_utc)),
            taken: at(taken),
        },
        CommandStatus::Received {
            at: taken,
            overdue: true,
        } => PinSend::NoAnswer { taken: at(taken) },
        CommandStatus::Waiting { .. } => PinSend::Waiting,
        CommandStatus::Sent {
            at: sent,
            unconfirmed: Unconfirmed::Lost,
        } => PinSend::Unconfirmed { at: at(sent) },
        CommandStatus::Sent { at: sent, .. } => PinSend::Sent { at: at(sent) },
        CommandStatus::Cancelled { .. }
        | CommandStatus::Expired
        | CommandStatus::Erasing { .. } => {
            return None;
        }
    })
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
            LockNote::On { locked, android } => {
                let state = match (*locked, *android) {
                    (true, false) => "Locked",
                    (false, false) => "Unlocked",
                    (true, true) => "Locked · Android's lock",
                    (false, true) => "Unlocked · Android's lock",
                };
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
            LockNote::AndroidNeedsUpdate => warning(
                "Update Vibb One for Android's lock",
                "Until Vibb One on this phone is updated, it keeps Vibb One's lock.".to_string(),
                None,
            ),
            LockNote::AndroidWaiting { sending: false } => row(
                "Android's lock",
                "Waiting".to_string(),
                Some("It starts the next time the Child PIN is typed on the phone."),
            ),
            LockNote::AndroidWaiting { sending: true } => row(
                "Android's lock",
                "Sending the PIN".to_string(),
                Some("It starts when the phone gets the Child PIN."),
            ),
            LockNote::PinNotChildPin { stored } => warning(
                "Phone's PIN isn't the Child PIN",
                if *stored {
                    "It was probably changed on the phone. Sending the Child PIN again puts it back."
                } else {
                    "It was probably changed on the phone. Set a new Child PIN above."
                }
                .to_string(),
                None,
            ),
            LockNote::PinBehind => row(
                "Child PIN on the phone",
                "An earlier one".to_string(),
                Some("The phone still uses an earlier Child PIN until it gets the new one."),
            ),
            LockNote::NoResetToken => warning(
                "Vibb One can't change Android's PIN",
                "Until the child unlocks the phone once, Vibb One can't change the PIN or switch \
                 back to its own lock."
                    .to_string(),
                None,
            ),
            LockNote::SwitchingBack { stuck: false } => row(
                "Android's lock",
                "Switching back…".to_string(),
                Some("Vibb One's lock takes over when the phone checks in."),
            ),
            LockNote::SwitchingBack { stuck: true } => warning(
                "Android's PIN is still on",
                "It goes after the child next unlocks the phone, or clear it in Settings › \
                 Security while the phone is open (Parent PIN › pause)."
                    .to_string(),
                None,
            ),
            LockNote::Relocked(n) => row(
                "Locked again by Vibb One",
                if *n == 1 {
                    "1 time".to_string()
                } else {
                    format!("{n} times")
                },
                Some(
                    "Android's lock wasn't locked under Vibb One's lock screen, so Vibb One \
                     locked it. Since the last report.",
                ),
            ),
            LockNote::PinSent(send) => {
                let (value, detail): (String, Option<&str>) = match send {
                    PinSend::Waiting => (
                        "Waiting".to_string(),
                        Some("It goes out when the phone checks in."),
                    ),
                    PinSend::Sent { at } => (format!("Sent · {at}"), Some("Not confirmed yet.")),
                    PinSend::Unconfirmed { at } => (
                        format!("Sent · {at}"),
                        Some("Never confirmed: it may not have reached the phone. Send it again."),
                    ),
                    PinSend::Taken { sent, taken } => (format!("Sent {sent} · taken {taken}"), None),
                    PinSend::NoAnswer { taken } => (
                        format!("Taken · {taken}"),
                        Some("The phone got it but never answered. Send it again."),
                    ),
                    PinSend::Answered { answer, at } => {
                        let (word, detail) = answer.row();
                        (format!("{word} · {at}"), detail)
                    }
                    PinSend::Other { raw, at } => {
                        return Note {
                            warning: false,
                            title: "Child PIN to the phone".to_string(),
                            value: Some(format!("Answered · {at}")),
                            detail: Some(format!("The phone said \"{raw}\".")),
                            fix: None,
                        };
                    }
                };
                row("Child PIN to the phone", value, detail)
            }
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

/// Design 59 §2.3: the parent chose Android's lock for this phone.
pub fn android_requested(policy: &DevicePolicy) -> bool {
    policy.kid_keyguard == KEYGUARD_ANDROID
}

/// What the phone's last report says about its lock, for [lock_card].
#[derive(Clone, Copy, Debug, Default)]
pub struct PhoneLock<'a> {
    /// The report's `lock_state` (`None`: none from a launcher that has the lock).
    pub state: Option<&'a LockState>,
    /// The phone has reported at all.
    pub reported: bool,
    /// The reporting launcher has the lock ([PIN_LOCK_CAPABILITY]).
    pub capable: bool,
    /// ... and Android's lock ([ANDROID_KEYGUARD_CAPABILITY]).
    pub android_capable: bool,
    /// A [SET_KID_PIN] waits to go out.
    pub pin_waiting: bool,
}

impl PhoneLock<'_> {
    /// The phone says Android's PIN is its gate (a launcher with Android's lock).
    fn runs_android(&self) -> bool {
        self.android_capable
            && self
                .state
                .is_some_and(|s| s.keyguard.as_deref() == Some(KEYGUARD_ANDROID))
    }
}

/// The Child PIN group from the policy and the phone's report, and the safe-boot switch (QA 10 #7:
/// safe mode skips our lock). Design 59 §2.3 adds Android's lock: what the parent chose
/// ([android_requested]) against what the phone runs. `now_ms` for the backoff.
pub fn lock_card(policy: &DevicePolicy, phone: &PhoneLock, now_ms: i64) -> LockCard {
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
    if phone.reported && !phone.capable {
        card.notes.push(LockNote::NeedsUpdate);
        return card;
    }
    let android = android_requested(policy);
    if android && phone.reported && !phone.android_capable {
        card.notes.push(LockNote::AndroidNeedsUpdate);
    }
    let Some(state) = phone.state else {
        card.notes.push(LockNote::Waiting);
        return card;
    };
    let runs_android = phone.runs_android();
    // Android's own credential is no warning while the phone runs Android's lock: its gate is
    // Android's PIN, and it reports the credential while a rollback waits for the token -
    // "Switching back" says that (qa-59-revision N-C). A phone still on Vibb One's lock with an
    // Android PIN it didn't set keeps the warning and the alarm, whichever lock is chosen
    // (qa-59-step2-code S2P-2): Vibb One's lock is off there.
    let credential = state.inactive.as_deref() == Some("android_credential");
    if let Some(off) = state.inactive.as_deref().and_then(Inactive::parse)
        && !(credential && runs_android)
    {
        card.notes.push(LockNote::Off(off));
    }
    card.notes.extend(
        state
            .voip_fsi_denied
            .iter()
            .map(|package| LockNote::CallsCantRing(package.clone())),
    );
    if phone.android_capable {
        if android && !runs_android {
            card.notes.push(LockNote::AndroidWaiting {
                sending: phone.pin_waiting,
            });
        }
        if android && runs_android && state.pin_rev.is_none() {
            card.notes.push(LockNote::PinNotChildPin {
                stored: policy.kid_pin_sealed.is_some(),
            });
        }
        if android && runs_android && !state.reset_token {
            card.notes.push(LockNote::NoResetToken);
        }
        if !android && runs_android {
            card.notes.push(LockNote::SwitchingBack {
                stuck: !state.reset_token || credential,
            });
        }
        if state.pin_rev.is_some_and(|rev| rev != policy.kid_pin_rev) {
            card.notes.push(LockNote::PinBehind);
        }
        if state.keyguard_relocks > 0 {
            card.notes.push(LockNote::Relocked(state.keyguard_relocks));
        }
    }
    if state.active {
        card.notes.push(LockNote::On {
            locked: state.locked,
            android: runs_android,
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
/// reporting launcher doesn't have the lock, or reports it inactive for a reason [lock_card] warns
/// about - the same notes, so the row and the page agree.
pub fn lock_off(policy: &DevicePolicy, phone: &PhoneLock) -> bool {
    lock_card(policy, phone, 0)
        .notes
        .iter()
        .any(|n| matches!(n, LockNote::NeedsUpdate | LockNote::Off(_)))
}

/// Design 59 §2.3: the phone runs Android's lock with a PIN that isn't the Child PIN - the Lock
/// page's warning, and an Attention row.
pub fn pin_changed_on_phone(policy: &DevicePolicy, phone: &PhoneLock) -> bool {
    lock_card(policy, phone, 0)
        .notes
        .iter()
        .any(|n| matches!(n, LockNote::PinNotChildPin { .. }))
}

/// "Send the Child PIN again" (design 59 §2.3): Android's lock is chosen, the phone can take the
/// PIN, this server keeps a copy, the phone doesn't run the current one (it reports Vibb One's
/// lock, a PIN changed on the phone or another rev) and no command is waiting or in flight - the
/// newest was answered (a refusal, or a success the phone's report didn't follow), never
/// confirmed, or there is none.
pub fn offers_send_again(
    policy: &DevicePolicy,
    phone: &PhoneLock,
    newest: Option<&PinSend>,
) -> bool {
    let Some(state) = phone.state else {
        return false;
    };
    let in_flight = matches!(
        newest,
        Some(PinSend::Waiting | PinSend::Sent { .. } | PinSend::Taken { .. })
    );
    let current = state.keyguard.as_deref() == Some(KEYGUARD_ANDROID)
        && state.pin_rev == Some(policy.kid_pin_rev);
    android_requested(policy)
        && policy_kid_lock(policy).is_some()
        && policy.kid_pin_sealed.is_some()
        && phone.android_capable
        && !phone.pin_waiting
        && !in_flight
        && !current
}

/// The Child PIN as this server keeps it (design 59 §2.1), read in one statement for the reveal.
#[derive(Debug, Default, sqlx::FromRow)]
pub struct KeptPin {
    pub kid_pin_sealed: Option<String>,
    pub kid_pin_prev_sealed: Option<String>,
    pub kid_pin_prev_rev: Option<i64>,
    pub kid_pin_rev: i64,
}

impl KeptPin {
    /// The phone's row; none (no row, no Child PIN) reads as nothing kept.
    pub async fn load(db: &sqlx::SqlitePool, device_id: i64) -> Result<KeptPin, sqlx::Error> {
        Ok(sqlx::query_as(
            "SELECT kid_pin_sealed, kid_pin_prev_sealed, kid_pin_prev_rev, kid_pin_rev \
             FROM device_policy WHERE device_id = ? AND kid_pin_hash IS NOT NULL",
        )
        .bind(device_id)
        .fetch_optional(db)
        .await?
        .unwrap_or_default())
    }
}

/// What "Show Child PIN" shows (design 59 §2.2).
#[derive(Debug, PartialEq, Eq)]
pub enum ShownPin {
    /// The Child PIN, and what the phone has when it isn't this one.
    Pin { pin: KidPin, phone: PhoneHas },
    /// No copy here: no Child PIN, one saved before this version, or one saved while the key file
    /// couldn't be read.
    NotStored,
    /// A copy this server can't open (sealed under another key: a restore elsewhere).
    Unreadable,
    /// This server couldn't read its key file at the start (the server log says why).
    NoKey,
}

/// The PIN the phone runs, next to the one shown.
#[derive(Debug, PartialEq, Eq)]
pub enum PhoneHas {
    /// This one, or nothing known (a launcher without Android's lock reports no rev).
    This,
    /// An earlier Child PIN: the one it last confirmed, when this server still keeps it.
    Earlier(Option<KidPin>),
    /// Android's PIN was changed on the phone.
    Changed,
}

/// Design 59 §2.2: the current Child PIN opened with `key`, and, for a phone with Android's lock
/// that reports another rev, the one it still has: the confirmed PIN kept in `prev` when its rev
/// is the phone's, else "an earlier PIN".
pub fn shown_pin(
    key: Option<&MusicKey>,
    kept: &KeptPin,
    device_id: i64,
    phone: &PhoneLock,
) -> ShownPin {
    let Some(key) = key else {
        return ShownPin::NoKey;
    };
    let Some(sealed) = kept.kid_pin_sealed.as_deref() else {
        return ShownPin::NotStored;
    };
    let Some(pin) = crate::kid_pin::open(key, device_id, sealed) else {
        return ShownPin::Unreadable;
    };
    let reported = phone.state.filter(|_| phone.android_capable);
    let phone_has = match reported.map(|s| s.pin_rev) {
        Some(Some(rev)) if rev != kept.kid_pin_rev => PhoneHas::Earlier(
            kept.kid_pin_prev_sealed
                .as_deref()
                .filter(|_| kept.kid_pin_prev_rev == Some(rev))
                .and_then(|prev| crate::kid_pin::open(key, device_id, prev)),
        ),
        Some(None) if phone.runs_android() => PhoneHas::Changed,
        _ => PhoneHas::This,
    };
    ShownPin::Pin {
        pin,
        phone: phone_has,
    }
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
        // Design 59 §2.1: the key file couldn't be read at the start (the server log says why).
        "saved_not_kept" => (
            "Child PIN saved. This server can't keep a copy to show later or send for Android's \
             lock: its key file couldn't be read.",
            false,
        ),
        "saved_safe_boot_not_kept" => (
            "Child PIN saved, and Block safe mode is now on: safe mode would skip the lock. This \
             server can't keep a copy to show later or send for Android's lock: its key file \
             couldn't be read.",
            false,
        ),
        "cleared" => (
            "Child PIN removed. The phone opens without a code once it checks in.",
            false,
        ),
        // Design 59 §2.3: "Send the Child PIN again" and the lock switch.
        "pin_sent" => (
            "Sent. The phone takes the Child PIN as soon as it checks in.",
            false,
        ),
        "pin_not_sent" => (
            "Not sent: one is already on its way, or Vibb One on the phone can't take it yet.",
            true,
        ),
        "pin_not_kept" => (
            "Not sent: this server keeps no copy of the Child PIN. Set it again, then it goes \
             out by itself.",
            true,
        ),
        "keyguard_no_pin" => ("Set a Child PIN first: Android's lock needs one.", true),
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

    /// A report from a launcher before design 59 (or none, `reported` false).
    fn phone(state: Option<&LockState>, capable: bool, reported: bool) -> PhoneLock<'_> {
        PhoneLock {
            state,
            reported,
            capable,
            ..Default::default()
        }
    }

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
            &phone(Some(&inactive(Some("crash_guard"))), true, true)
        ));
        // An older launcher without the lock.
        assert!(lock_off(&with_pin, &phone(None, false, true)));
        // Not reported yet.
        assert!(!lock_off(&with_pin, &phone(None, false, false)));
        assert!(!lock_off(
            &with_pin,
            &phone(Some(&inactive(None)), true, true)
        ));
        assert!(!lock_off(
            &with_pin,
            &phone(Some(&inactive(Some("no_pin"))), true, true)
        ));
        for reason in [
            "unmanaged",
            "android_credential",
            "crash_guard",
            "bad_hash",
            "odd",
        ] {
            assert!(
                lock_off(&with_pin, &phone(Some(&inactive(Some(reason))), true, true)),
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
        let card = lock_card(&p, &phone(None, false, true), 0);
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
            let card = lock_card(&p, &phone(Some(&state), true, true), 0);
            assert_eq!(card.notes, [LockNote::Off(typed)], "{inactive}");
            assert!(card.notes[0].note(None).warning, "{inactive}");
        }
        let state = LockState {
            inactive: Some("no_pin".to_string()),
            ..Default::default()
        };
        assert!(
            lock_card(&p, &phone(Some(&state), true, true), 0)
                .notes
                .is_empty()
        );
        // No report yet from a launcher with the lock.
        assert_eq!(
            lock_card(&p, &phone(None, true, false), 0).notes,
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
        let card = lock_card(&p, &phone(Some(&state), true, true), 0);
        assert_eq!(
            card.notes,
            [
                LockNote::On {
                    locked: true,
                    android: false
                },
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
        let card = lock_card(&p, &phone(Some(&state), true, true), 0);
        assert_eq!(
            card.notes,
            [
                LockNote::CallsCantRing("io.element.android.x".to_string()),
                LockNote::On {
                    locked: false,
                    android: false
                },
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
        // A bad entry between good ones drops only itself (r7-4, qa-l7-a-code 2), and a duplicate
        // inside the cap keeps only the package's newest.
        let stored = sanitize_lock_state(
            &serde_json::json!({ "voip_rang": [
                { "package": "org.example.a", "last_at_ms": now - 1_000 },
                { "package": "org.example.bad" },
                { "package": "org.example.b", "last_at_ms": now - 2_000 },
                { "package": "org.example.a", "last_at_ms": now - 3_000 },
            ] }),
            now,
        )
        .unwrap();
        let state: LockState = serde_json::from_str(&stored).unwrap();
        assert_eq!(
            state.voip_rang,
            vec![
                VoipRang {
                    package: "org.example.a".to_string(),
                    last_at_ms: now - 1_000
                },
                VoipRang {
                    package: "org.example.b".to_string(),
                    last_at_ms: now - 2_000
                },
            ]
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

    /// Design 59 §1: the keyguard's two words, a rev that isn't negative, relocks capped; an older
    /// launcher's report reads as unknown, not as "own".
    #[test]
    fn the_keyguard_fields_are_kept_typed() {
        let stored = |value: serde_json::Value| {
            serde_json::from_str::<LockState>(&sanitize_lock_state(&value, 0).unwrap()).unwrap()
        };
        let state = stored(serde_json::json!({ "active": true, "keyguard": "android",
            "pin_rev": 7, "reset_token": true, "keyguard_relocks": 5_000 }));
        assert_eq!(
            (
                state.keyguard.as_deref(),
                state.pin_rev,
                state.reset_token,
                state.keyguard_relocks
            ),
            (Some("android"), Some(7), true, 1000)
        );
        let odd = stored(serde_json::json!({ "keyguard": "<b>x</b>", "pin_rev": -1,
            "keyguard_relocks": -3 }));
        assert_eq!(
            (odd.keyguard, odd.pin_rev, odd.keyguard_relocks),
            (None, None, 0)
        );
        let older = stored(serde_json::json!({ "active": true }));
        assert_eq!(
            (older.keyguard, older.pin_rev, older.reset_token),
            (None, None, false)
        );
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
        let card = lock_card(&p, &phone(Some(&state), true, true), 0);
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
        let card = lock_card(
            &DevicePolicy::default(),
            &phone(Some(&state), true, true),
            0,
        );
        assert!(rang_notes(&card, Some(&state), &labels, &zone, now).is_empty());
    }

    #[test]
    fn no_pin_no_warnings() {
        let card = lock_card(&DevicePolicy::default(), &phone(None, false, true), 0);
        assert!(!card.pin_set);
        assert!(card.notes.is_empty());
    }

    /// A launcher with Android's lock (design 59), reporting `state`.
    fn android_phone(state: &LockState) -> PhoneLock<'_> {
        PhoneLock {
            state: Some(state),
            reported: true,
            capable: true,
            android_capable: true,
            pin_waiting: false,
        }
    }

    fn reported(keyguard: &str, pin_rev: Option<i64>, reset_token: bool) -> LockState {
        LockState {
            active: true,
            locked: true,
            keyguard: Some(keyguard.to_string()),
            pin_rev,
            reset_token,
            ..Default::default()
        }
    }

    /// Design 59 §2.3, case by case: what the parent chose against what the phone runs.
    #[test]
    fn the_card_words_android_lock_case_by_case() {
        let mut p = policy_with_pins(Some("1234"), Some("999999"));
        p.hardening.disallow_safe_boot = true;
        p.kid_pin_rev = 4;
        p.kid_pin_sealed = Some("sealed".to_string());
        let own = p.clone();
        p.kid_keyguard = KEYGUARD_ANDROID.to_string();
        let on = |android| LockNote::On {
            locked: true,
            android,
        };

        // Chosen, an older launcher: the update warning, its lock as before.
        let old = LockState {
            active: true,
            locked: true,
            ..Default::default()
        };
        assert_eq!(
            lock_card(&p, &phone(Some(&old), true, true), 0).notes,
            [LockNote::AndroidNeedsUpdate, on(false)]
        );
        // Chosen, the phone still on Vibb One's: waiting for a PIN typed, or for the one sent.
        let state = reported("own", Some(4), true);
        let mut waiting = android_phone(&state);
        assert_eq!(
            lock_card(&p, &waiting, 0).notes,
            [LockNote::AndroidWaiting { sending: false }, on(false)]
        );
        waiting.pin_waiting = true;
        assert_eq!(
            lock_card(&p, &waiting, 0).notes[0],
            LockNote::AndroidWaiting { sending: true }
        );
        // Running it with the current PIN: just the state.
        let state = reported("android", Some(4), true);
        assert_eq!(lock_card(&p, &android_phone(&state), 0).notes, [on(true)]);
        // A PIN changed on the phone: the warning, "send again" when a copy is kept.
        let state = reported("android", None, true);
        assert_eq!(
            lock_card(&p, &android_phone(&state), 0).notes,
            [LockNote::PinNotChildPin { stored: true }, on(true)]
        );
        assert!(pin_changed_on_phone(&p, &android_phone(&state)));
        let mut unkept = p.clone();
        unkept.kid_pin_sealed = None;
        assert_eq!(
            lock_card(&unkept, &android_phone(&state), 0).notes[0],
            LockNote::PinNotChildPin { stored: false }
        );
        // Behind, without the token, locked again under the glance.
        let mut state = reported("android", Some(3), false);
        state.keyguard_relocks = 2;
        assert_eq!(
            lock_card(&p, &android_phone(&state), 0).notes,
            [
                LockNote::NoResetToken,
                LockNote::PinBehind,
                LockNote::Relocked(2),
                on(true)
            ]
        );
        // Vibb One's chosen again, the phone still on Android's: switching back, stuck without
        // the token - also as the pair with `android_credential` (qa-59-revision N-C), which is
        // never own mode's "remove Android's lock", nor the lock-off alarm.
        let state = reported("android", Some(4), true);
        assert_eq!(
            lock_card(&own, &android_phone(&state), 0).notes,
            [LockNote::SwitchingBack { stuck: false }, on(true)]
        );
        let mut pair = reported("android", Some(4), true);
        pair.inactive = Some("android_credential".to_string());
        assert_eq!(
            lock_card(&own, &android_phone(&pair), 0).notes,
            [LockNote::SwitchingBack { stuck: true }, on(true)]
        );
        assert!(!lock_off(&own, &android_phone(&pair)));
        // Own mode with Android's credential on a phone running Vibb One's: the old warning.
        let mut credential = reported("own", Some(4), true);
        credential.inactive = Some("android_credential".to_string());
        credential.active = false;
        assert_eq!(
            lock_card(&own, &android_phone(&credential), 0).notes,
            [LockNote::Off(Inactive::AndroidCredential)]
        );
        assert!(lock_off(&own, &android_phone(&credential)));
        // ...and with Android's lock chosen, while the phone still runs Vibb One's (S2P-2).
        assert_eq!(
            lock_card(&p, &android_phone(&credential), 0).notes,
            [
                LockNote::Off(Inactive::AndroidCredential),
                LockNote::AndroidWaiting { sending: false }
            ]
        );
        assert!(lock_off(&p, &android_phone(&credential)));
        // The words: warnings short, the commands nowhere; the rows' values.
        for note in [
            LockNote::AndroidNeedsUpdate,
            LockNote::PinNotChildPin { stored: true },
            LockNote::NoResetToken,
            LockNote::SwitchingBack { stuck: true },
        ] {
            let words = note.note(None);
            assert!(words.warning, "{note:?}");
            assert!(words.title.split_whitespace().count() <= 6, "{note:?}");
        }
        assert_eq!(
            on(true).note(Some("10:59")).value.as_deref(),
            Some("Locked · Android's lock · 10:59")
        );
        assert_eq!(
            LockNote::Relocked(1).note(None).value.as_deref(),
            Some("1 time")
        );
    }

    fn command(
        result: Option<&str>,
        delivered: bool,
        received: bool,
    ) -> crate::models::DeviceCommand {
        crate::models::DeviceCommand {
            id: 1,
            device_id: 1,
            command: SET_KID_PIN.to_string(),
            requested_at: "2026-10-20 12:00:00".to_string(),
            delivered_at: delivered.then(|| "2026-10-20 12:02:00".to_string()),
            first_delivered_at: delivered.then(|| "2026-10-20 12:02:00".to_string()),
            deliveries: i64::from(delivered),
            received_at: received.then(|| "2026-10-20 12:03:00".to_string()),
            acknowledged_at: result.map(|_| "2026-10-20 12:03:00".to_string()),
            result: result.map(str::to_string),
            succeeded: result.map(|r| r == "pin set"),
            cancelled_at: None,
            cancel_reason: None,
        }
    }

    /// The command row (§2.3): waiting, sent, taken, answered in words; nothing for a cancelled
    /// one; the answers typed for Activity too.
    #[test]
    fn the_pin_command_reads_as_its_state() {
        let zone = crate::display_time::Zone::from_setting("Europe/Oslo");
        let now = chrono::DateTime::parse_from_rfc3339("2026-10-20T12:04:00Z")
            .unwrap()
            .with_timezone(&chrono::Utc);
        let value = |c: &crate::models::DeviceCommand| {
            pin_send(c, true, &zone, now)
                .map(|send| LockNote::PinSent(send).note(None))
                .map(|n| (n.value.unwrap(), n.detail))
        };
        assert_eq!(value(&command(None, false, false)).unwrap().0, "Waiting");
        assert_eq!(
            value(&command(None, true, false)).unwrap().0,
            "Sent · 14:02"
        );
        assert_eq!(
            value(&command(None, true, true)).unwrap().0,
            "Sent 14:02 · taken 14:03"
        );
        assert_eq!(
            value(&command(Some("pin set"), true, true)),
            Some(("Set · 14:03".to_string(), None))
        );
        let (word, detail) = value(&command(Some("refused: no_token"), true, true)).unwrap();
        assert_eq!(word, "Refused · 14:03");
        assert!(
            detail
                .unwrap()
                .contains("until the child unlocks the phone once")
        );
        let (word, detail) = value(&command(Some("odd answer"), true, true)).unwrap();
        assert_eq!(
            (word.as_str(), detail.as_deref()),
            ("Answered · 14:03", Some("The phone said \"odd answer\"."))
        );
        let mut cancelled = command(None, false, false);
        cancelled.cancelled_at = Some("2026-10-20 12:01:00".to_string());
        cancelled.cancel_reason = Some("keyguard_own".to_string());
        assert_eq!(pin_send(&cancelled, true, &zone, now), None);
        for (raw, answer) in [
            ("pin set", PinAnswer::Set),
            ("own lock", PinAnswer::OwnLock),
            ("refused: no_token", PinAnswer::NoToken),
            ("refused: mismatch", PinAnswer::Mismatch),
            ("refused: no_pin", PinAnswer::NoPin),
            ("refused: failed", PinAnswer::Failed),
        ] {
            assert_eq!(PinAnswer::parse(raw), Some(answer), "{raw}");
        }
        assert_eq!(PinAnswer::parse("pin"), None);
    }

    /// "Send the Child PIN again": only for a phone that can take it and doesn't run the current
    /// PIN, with a copy kept and nothing on its way.
    #[test]
    fn send_again_is_offered_only_when_it_can_help() {
        let mut p = policy_with_pins(Some("1234"), Some("999999"));
        p.kid_keyguard = KEYGUARD_ANDROID.to_string();
        p.kid_pin_rev = 4;
        p.kid_pin_sealed = Some("sealed".to_string());
        let changed = reported("android", None, true);
        let refused = PinSend::Answered {
            answer: PinAnswer::NoToken,
            at: "14:03".to_string(),
        };
        assert!(offers_send_again(&p, &android_phone(&changed), None));
        assert!(offers_send_again(
            &p,
            &android_phone(&changed),
            Some(&refused)
        ));
        for in_flight in [
            PinSend::Waiting,
            PinSend::Sent {
                at: "14:02".to_string(),
            },
            PinSend::Taken {
                sent: "14:02".to_string(),
                taken: "14:03".to_string(),
            },
        ] {
            assert!(!offers_send_again(
                &p,
                &android_phone(&changed),
                Some(&in_flight)
            ));
        }
        let current = reported("android", Some(4), true);
        assert!(!offers_send_again(&p, &android_phone(&current), None));
        // Behind, or still on Vibb One's lock: yes.
        assert!(offers_send_again(
            &p,
            &android_phone(&reported("android", Some(3), true)),
            None
        ));
        assert!(offers_send_again(
            &p,
            &android_phone(&reported("own", Some(4), true)),
            None
        ));
        // Vibb One's lock chosen, no copy, an older launcher: no.
        let mut own = p.clone();
        own.kid_keyguard = KEYGUARD_OWN.to_string();
        assert!(!offers_send_again(&own, &android_phone(&changed), None));
        let mut unkept = p.clone();
        unkept.kid_pin_sealed = None;
        assert!(!offers_send_again(&unkept, &android_phone(&changed), None));
        assert!(!offers_send_again(
            &p,
            &phone(Some(&changed), true, true),
            None
        ));
    }

    /// Design 59 §2.2: the shown PIN, the one the phone still has, and why nothing shows.
    #[test]
    fn the_shown_pin_names_what_the_phone_has() {
        let key = MusicKey::from_bytes(&[7; 32]);
        let seal = |pin: &str| crate::kid_pin::seal(&key, 3, &KidPin::new(pin.to_string()));
        let kept = KeptPin {
            kid_pin_sealed: Some(seal("7391")),
            kid_pin_prev_sealed: Some(seal("4826")),
            kid_pin_prev_rev: Some(1),
            kid_pin_rev: 2,
        };
        let pin = |digits: &str| KidPin::new(digits.to_string());
        let shown = |state: &LockState, android: bool| {
            let mut phone = android_phone(state);
            phone.android_capable = android;
            shown_pin(Some(&key), &kept, 3, &phone)
        };
        let this = ShownPin::Pin {
            pin: pin("7391"),
            phone: PhoneHas::This,
        };
        assert_eq!(shown(&reported("own", Some(2), true), true), this);
        assert_eq!(
            shown(&reported("own", Some(1), true), true),
            ShownPin::Pin {
                pin: pin("7391"),
                phone: PhoneHas::Earlier(Some(pin("4826")))
            }
        );
        assert_eq!(
            shown(&reported("android", Some(0), true), true),
            ShownPin::Pin {
                pin: pin("7391"),
                phone: PhoneHas::Earlier(None)
            }
        );
        assert_eq!(
            shown(&reported("android", None, true), true),
            ShownPin::Pin {
                pin: pin("7391"),
                phone: PhoneHas::Changed
            }
        );
        // A launcher without Android's lock: the newest alone.
        assert_eq!(shown(&reported("own", Some(1), true), false), this);
        // Another phone's row, no copy, no key.
        let phone_state = reported("own", Some(2), true);
        let phone = android_phone(&phone_state);
        assert_eq!(
            shown_pin(Some(&key), &kept, 4, &phone),
            ShownPin::Unreadable
        );
        assert_eq!(
            shown_pin(Some(&key), &KeptPin::default(), 3, &phone),
            ShownPin::NotStored
        );
        assert_eq!(shown_pin(None, &kept, 3, &phone), ShownPin::NoKey);
    }
}
