//! Admin sign-in and session hardening (design 22 §3.2; no step-up - user decision U2).
//!
//! - [PasswordGate]: argon2 runs on the blocking pool, one at a time with a queue of four (more
//!   get 429), so a burst of logins never stalls the tokio workers (SSE keepalives, the device
//!   API); an unknown username verifies against a dummy hash, so timing doesn't tell which names
//!   exist.
//! - TOTP ([check_totp]): a code is accepted only for a later 30-second step than the last one
//!   accepted (`admin_users.totp_last_step`), so a code can't be replayed. Failures lock the
//!   account ([take_totp_attempt], taken before the code is checked): 5 lock it for 15 minutes, every further lock doubles up to
//!   24 hours, and only a complete login resets the counter (password failures never lock - they
//!   only count toward the IP ban, or anyone who knows "admin" could lock the parent out).
//! - Sessions: the id is cycled after the password and after the TOTP; the session stores
//!   `admin_users.session_epoch` ([EPOCH_KEY]), and a password change, a 2FA reset or "Sign out
//!   everywhere" bump it, which ends every other session. The pending (password-only) state
//!   lasts [PENDING_SECS].
//! - [csrf_guard] and [admin_headers]: middleware on the admin listener.
//! - [reset_2fa]: the `kid_phone_server reset-2fa <user>` recovery command.

use std::sync::{Arc, OnceLock};

use tokio::sync::Semaphore;

use axum::extract::{Request, State};
use axum::http::{HeaderMap, HeaderValue, Method, StatusCode, header};
use axum::middleware::Next;
use axum::response::{IntoResponse, Response};

use crate::AppState;

/// argon2 verifications at once.
const PASSWORD_RUNNING: usize = 1;
/// Waiting behind them; past this a login gets 429.
const PASSWORD_QUEUE: usize = 4;
/// TOTP failures before the account locks.
pub const TOTP_FAILURES_TO_LOCK: i64 = 5;
/// The first lock; each later one doubles, up to [MAX_LOCK_MINUTES].
pub const FIRST_LOCK_MINUTES: i64 = 15;
pub const MAX_LOCK_MINUTES: i64 = 24 * 60;
/// How long the password-only (pending) state lasts before the TOTP must be entered.
pub const PENDING_SECS: i64 = 5 * 60;
pub const PENDING_ID_KEY: &str = "pending_admin_id";
pub const PENDING_AT_KEY: &str = "pending_at";
pub const EPOCH_KEY: &str = "session_epoch";
/// When the session's sign-in finished (Unix seconds): its absolute lifetime counts from here
/// (security review L6, `NetConfig::session_max_days`).
pub const SIGNED_IN_AT_KEY: &str = "signed_in_at";
/// The banner text after a complete login that followed failed 2FA attempts.
pub const LOGIN_WARNING_KEY: &str = "login_warning";

/// Too many logins at once.
#[derive(Debug)]
pub struct Busy;

/// Two semaphores: admission (one running plus the queue; past it, 429) and the run slot. Both
/// permits are owned and move into the blocking task, so they are released when argon2 ends - not
/// when the request does. A client that hangs up mid-login can't start argon2 runs past the
/// bound (QA 22 M1).
pub struct PasswordGate {
    admission: Arc<Semaphore>,
    running: Arc<Semaphore>,
}

impl Default for PasswordGate {
    fn default() -> Self {
        PasswordGate {
            admission: Arc::new(Semaphore::new(PASSWORD_RUNNING + PASSWORD_QUEUE)),
            running: Arc::new(Semaphore::new(PASSWORD_RUNNING)),
        }
    }
}

/// A hash with the same parameters as every real one, made once: an unknown username costs the
/// same as a known one.
fn dummy_hash() -> &'static str {
    static DUMMY: OnceLock<String> = OnceLock::new();
    DUMMY.get_or_init(|| crate::security::hash_password("not a real password - timing only"))
}

impl PasswordGate {
    /// Runs `work` (argon2) on the blocking pool behind the gate.
    async fn run<T: Send + 'static>(
        &self,
        work: impl FnOnce() -> T + Send + 'static,
    ) -> Result<T, Busy> {
        let admitted = Arc::clone(&self.admission)
            .try_acquire_owned()
            .map_err(|_| Busy)?;
        let slot = Arc::clone(&self.running)
            .acquire_owned()
            .await
            .map_err(|_| Busy)?;
        tokio::task::spawn_blocking(move || {
            let _held = (admitted, slot);
            work()
        })
        .await
        .map_err(|_| Busy)
    }

    /// Whether `password` matches `hash`; `None` (an unknown user) verifies against a dummy hash
    /// and is `false`.
    pub async fn verify(&self, password: &str, hash: Option<&str>) -> Result<bool, Busy> {
        let password = password.to_string();
        let hash = hash.map(str::to_string);
        self.run(move || match hash {
            Some(hash) => crate::security::verify_password(&password, &hash),
            None => {
                crate::security::verify_password(&password, dummy_hash());
                false
            }
        })
        .await
    }

    /// Hashes `new` - a gated job: a form whose re-auth was a passkey still makes its new secret's
    /// hash behind the gate (design 74 D1.6).
    pub async fn hash(&self, new: &str) -> Result<String, Busy> {
        let new = new.to_string();
        self.run(move || crate::security::hash_password(&new)).await
    }

    /// Checks `current` against `hash` and, when it matches, hashes `new` - one gated job.
    pub async fn change(
        &self,
        current: &str,
        hash: &str,
        new: &str,
    ) -> Result<Option<String>, Busy> {
        let (current, hash, new) = (current.to_string(), hash.to_string(), new.to_string());
        self.run(move || {
            crate::security::verify_password(&current, &hash)
                .then(|| crate::security::hash_password(&new))
        })
        .await
    }
}

/// 429 for [Busy].
pub fn busy_response() -> Response {
    (
        StatusCode::TOO_MANY_REQUESTS,
        [(header::RETRY_AFTER, "5")],
        "Too many sign-ins at once - try again in a few seconds.",
    )
        .into_response()
}

/// The wrong password in a row that ends the sessions.
pub const REAUTH_MAX_WRONG: i64 = 3;

/// A form that asks a signed-in parent for the password again ([reauth]).
#[derive(Debug, PartialEq, Eq)]
pub enum Reauth {
    /// The password; the count is reset.
    Right,
    /// Wrong; `left` more wrong ones end the sessions.
    Wrong { left: i64 },
    /// The [REAUTH_MAX_WRONG]th wrong one in a row: every session of the account is ended (sign in
    /// again, with 2FA). Answer with [signed_out_response].
    SignedOut,
    /// Too many password checks at once (429, [busy_response]); nothing counted.
    Busy,
    /// A passkey-only account sent a password (design 74 D2.2, qa-passkeys-2 M1): it has none, so nothing was
    /// checked or counted - the form says "Confirm with a passkey".
    NeedsPasskey,
}

/// What a re-auth form sent (design 74 D1.6): the password, or - "Confirm with passkey" - a
/// passkey's assertion for a `reauth` challenge bound to the form's route.
#[derive(Clone, Copy)]
pub enum Proof<'a> {
    Password(&'a str),
    Passkey {
        assertion: &'a str,
        ceremony: &'a crate::passkeys::Ceremony,
    },
}

impl<'a> Proof<'a> {
    /// The form's fields: a non-empty `assertion` is the passkey's, else the password.
    pub fn of(
        password: &'a str,
        assertion: &'a str,
        ceremony: &'a crate::passkeys::Ceremony,
    ) -> Proof<'a> {
        if assertion.trim().is_empty() {
            Proof::Password(password)
        } else {
            Proof::Passkey {
                assertion,
                ceremony,
            }
        }
    }

    pub fn is_passkey(&self) -> bool {
        matches!(self, Proof::Passkey { .. })
    }

    /// The security log's words for a refused one (`security.detail.*`).
    pub fn refused_detail(&self) -> &'static str {
        match self {
            Proof::Password(_) => "security.detail.wrong_current_password",
            Proof::Passkey { .. } => "security.detail.passkey_failed",
        }
    }
}

/// The one re-auth check (design 59 §2.2, qa-59-design Q59-11) for every form that asks a
/// signed-in parent for the password again - Show Child PIN, the password change (both forms), the
/// 2FA reset, Withdraw/Remove, a passkey's add and removal: the gated Argon2, or a passkey's
/// assertion (design 74 D1.6, `passkeys::reauth`; a bad one is logged and counts as a wrong
/// password), then [record_reauth].
pub async fn reauth(
    state: &AppState,
    session: &tower_sessions::Session,
    admin: &crate::models::AdminUser,
    proof: Proof<'_>,
) -> Reauth {
    if admin.passkey_only && !proof.is_passkey() {
        return Reauth::NeedsPasskey;
    }
    let right = match proof {
        Proof::Password(password) => match state
            .passwords
            .verify(password, Some(&admin.password_hash))
            .await
        {
            Ok(right) => right,
            Err(Busy) => return Reauth::Busy,
        },
        Proof::Passkey {
            assertion,
            ceremony,
        } => passkey_proves(state, session, admin, assertion, ceremony).await,
    };
    record_reauth(&state.db, session, admin, right).await
}

/// Whether `assertion` proves the signed-in parent; a refusal is logged.
async fn passkey_proves(
    state: &AppState,
    session: &tower_sessions::Session,
    admin: &crate::models::AdminUser,
    assertion: &str,
    ceremony: &crate::passkeys::Ceremony,
) -> bool {
    match crate::passkeys::reauth(state, session, ceremony, admin, assertion).await {
        Ok(_) => true,
        Err(why) => {
            crate::passkeys::log_refused(
                &state.db,
                why,
                "security.detail.passkey.reauth",
                Some(&admin.username),
                None,
            )
            .await;
            false
        }
    }
}

/// [reauth] for a form that also makes a new secret's hash (the password change, Add Parent's and
/// Reset's one-time password): with the password, the one gated job [PasswordGate::change];
/// with a passkey, the assertion, then [PasswordGate::hash]. The hash is `Some` only with
/// [Reauth::Right].
pub async fn reauth_and_hash(
    state: &AppState,
    session: &tower_sessions::Session,
    admin: &crate::models::AdminUser,
    proof: Proof<'_>,
    new: &str,
) -> Result<(Reauth, Option<String>), Busy> {
    if admin.passkey_only && !proof.is_passkey() {
        return Ok((Reauth::NeedsPasskey, None));
    }
    match proof {
        Proof::Password(current) => {
            let changed = state
                .passwords
                .change(current, &admin.password_hash, new)
                .await?;
            let reauth = record_reauth(&state.db, session, admin, changed.is_some()).await;
            Ok((reauth, changed))
        }
        Proof::Passkey {
            assertion,
            ceremony,
        } => {
            let right = passkey_proves(state, session, admin, assertion, ceremony).await;
            let reauth = record_reauth(&state.db, session, admin, right).await;
            if reauth != Reauth::Right {
                return Ok((reauth, None));
            }
            let hash = state.passwords.hash(new).await?;
            Ok((reauth, Some(hash)))
        }
    }
}

/// Counts a re-auth's outcome (also for a form whose check is [PasswordGate::change]) in the
/// account's row, `admin_users.reauth_wrong`, one statement each (qa-59-step2-code S2P-1: a count
/// in the session was each request's own copy, so parallel requests, or an older request writing
/// its copy back, could reset it). A right password sets it to 0. The [REAUTH_MAX_WRONG]th wrong
/// one in a row sets it to 0 and moves the session epoch in the same `UPDATE`, which ends every
/// session of the account - also one a request still in flight writes back later -, flushes this
/// one and logs `reauth_signed_out`. A database error ends the session too: the limit never lapses.
pub async fn record_reauth(
    db: &sqlx::SqlitePool,
    session: &tower_sessions::Session,
    admin: &crate::models::AdminUser,
    right: bool,
) -> Reauth {
    if right {
        match sqlx::query("UPDATE admin_users SET reauth_wrong = 0 WHERE id = ?")
            .bind(admin.id)
            .execute(db)
            .await
        {
            Ok(_) => return Reauth::Right,
            Err(err) => {
                tracing::error!(%err, "couldn't reset the wrong-password count; signing out");
                bump_epoch(db, admin.id).await;
            }
        }
    } else {
        // The new count, or 0 when this one ended the sessions.
        let counted: Result<Option<i64>, sqlx::Error> = sqlx::query_scalar(
            "UPDATE admin_users SET \
             reauth_wrong = CASE WHEN reauth_wrong + 1 >= ?1 THEN 0 ELSE reauth_wrong + 1 END, \
             session_epoch = CASE WHEN reauth_wrong + 1 >= ?1 THEN session_epoch + 1 \
               ELSE session_epoch END \
             WHERE id = ?2 RETURNING reauth_wrong",
        )
        .bind(REAUTH_MAX_WRONG)
        .bind(admin.id)
        .fetch_optional(db)
        .await;
        match counted {
            Ok(Some(wrong)) if wrong > 0 => {
                return Reauth::Wrong {
                    left: REAUTH_MAX_WRONG - wrong,
                };
            }
            Ok(Some(_)) => {}
            _ => {
                tracing::error!(?counted, "couldn't count a wrong password; signing out");
                bump_epoch(db, admin.id).await;
            }
        }
    }
    // Every device's notifications end with the sessions, at once (security review M1,
    // qa-pwa-fixes-a-code Q-1): whoever guessed may have turned them on in their own browser.
    crate::push::forget_subscriptions(db, admin.id).await;
    session.flush().await.ok();
    crate::security::record_security_event(
        db,
        "reauth_signed_out",
        Some(&admin.username),
        None,
        Some(crate::security::Detail::words(
            "security.detail.reauth_signed_out",
            &[],
        )),
    )
    .await;
    Reauth::SignedOut
}
/// The answer after [Reauth::SignedOut]: the sign-in page (logged as a re-auth's,
/// `security::log_sign_in_redirects`).
pub fn signed_out_response() -> Response {
    crate::security::sign_in_redirect(crate::security::SignInWhy::Reauth)
}

/// The result of a TOTP check.
#[derive(Debug, PartialEq, Eq)]
pub enum Totp {
    /// Accepted; this step is now the last one used.
    Accepted,
    /// Not a current code, or one already used (replay).
    Refused,
}

/// Steps a code may be from (one either side of now, as authenticator clocks drift).
const SKEW: i64 = 1;
const STEP_SECS: u64 = 30;

/// Checks `code` for `admin_id`'s `secret` at `now_secs`: it must match a step within [SKEW] of
/// now that is later than `totp_last_step`, and that step is stored in the same statement, so two
/// requests with one code can't both pass.
pub async fn check_totp(
    db: &sqlx::SqlitePool,
    admin_id: i64,
    secret: &str,
    username: &str,
    code: &str,
    now_secs: u64,
) -> Result<Totp, sqlx::Error> {
    let Some(step) = totp_step(secret, username, code, now_secs) else {
        return Ok(Totp::Refused);
    };
    let stored = sqlx::query(
        "UPDATE admin_users SET totp_last_step = ? \
         WHERE id = ? AND (totp_last_step IS NULL OR totp_last_step < ?)",
    )
    .bind(step)
    .bind(admin_id)
    .bind(step)
    .execute(db)
    .await?;
    Ok(if stored.rows_affected() == 1 {
        Totp::Accepted
    } else {
        Totp::Refused
    })
}

/// The 30-second step `code` (trimmed) matches for `secret` within [SKEW] of `now_secs`, read only: [check_totp]
/// stores it, and the switch-off page (design 74 D2.5) stores it in its one `UPDATE` with the new secret.
pub fn totp_step(secret: &str, username: &str, code: &str, now_secs: u64) -> Option<i64> {
    let code = code.trim();
    let totp = crate::security::totp_for_secret(secret, username);
    let now_step = (now_secs / STEP_SECS) as i64;
    let mut matched = None;
    for step in (now_step - SKEW)..=(now_step + SKEW) {
        if step < 0 {
            continue;
        }
        let expected = totp.generate(step as u64 * STEP_SECS);
        if constant_time_eq(expected.as_bytes(), code.as_bytes()) {
            matched = Some(step);
        }
    }
    matched
}

fn constant_time_eq(a: &[u8], b: &[u8]) -> bool {
    a.len() == b.len() && a.iter().zip(b).fold(0u8, |acc, (x, y)| acc | (x ^ y)) == 0
}

/// Whether a password step made at `at` still counts at `now` ([PENDING_SECS]).
pub fn pending_fresh(at: u64, now: u64) -> bool {
    now.saturating_sub(at) as i64 <= PENDING_SECS
}

pub fn now_secs() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0)
}

/// Takes one TOTP attempt before the code is checked (QA 22 H2): counts it, and from the fifth on
/// locks the account - 15 minutes, doubled for every lock since the last complete login, at most
/// 24 hours. Returns the attempt's number since the last complete login, or `None` while the
/// account is locked: then the code must not be checked at all. One conditional `UPDATE ...
/// RETURNING`, so parallel requests are counted one by one and at most 5 codes are checked per
/// lock, however many arrive at once. A lock running out resets nothing, so after the first lock
/// every further attempt locks again (for longer); only a complete login resets the count
/// ([reset_after_login]).
pub async fn take_totp_attempt(
    db: &sqlx::SqlitePool,
    admin_id: i64,
) -> Result<Option<i64>, sqlx::Error> {
    sqlx::query_scalar(
        "UPDATE admin_users SET \
            failed_login_attempts = failed_login_attempts + 1, \
            lock_count = CASE WHEN failed_login_attempts + 1 >= ?1 \
                THEN lock_count + 1 ELSE lock_count END, \
            locked_until = CASE WHEN failed_login_attempts + 1 >= ?1 \
                THEN datetime('now', '+' || MIN(?2 * (1 << MIN(lock_count, 10)), ?3) || ' minutes') \
                ELSE locked_until END \
         WHERE id = ?4 AND (locked_until IS NULL OR locked_until <= datetime('now')) \
         RETURNING failed_login_attempts",
    )
    .bind(TOTP_FAILURES_TO_LOCK)
    .bind(FIRST_LOCK_MINUTES)
    .bind(MAX_LOCK_MINUTES)
    .bind(admin_id)
    .fetch_optional(db)
    .await
}

/// After a complete login: counter, locks, lock count and the re-auth forms' wrong count start over.
pub async fn reset_after_login(db: &sqlx::SqlitePool, admin_id: i64) {
    sqlx::query(
        "UPDATE admin_users SET failed_login_attempts = 0, lock_count = 0, locked_until = NULL, \
         reauth_wrong = 0 \
         WHERE id = ?",
    )
    .bind(admin_id)
    .execute(db)
    .await
    .ok();
}

/// After a passkey sign-in (design 74 D1.5): the re-auth forms' count starts over; the TOTP
/// failures, a running lock and its doubling stay while any lock was taken (they guard the TOTP
/// step, which a passkey doesn't take - qa-passkeys-1 B-L1), else the failure count starts over.
pub async fn reset_after_passkey(db: &sqlx::SqlitePool, admin_id: i64) {
    sqlx::query(
        "UPDATE admin_users SET reauth_wrong = 0, failed_login_attempts = CASE \
           WHEN lock_count > 0 OR locked_until > datetime('now') THEN failed_login_attempts ELSE 0 END \
         WHERE id = ?",
    )
    .bind(admin_id)
    .execute(db)
    .await
    .ok();
}

/// Minutes left on the account's lock, if it is locked.
pub async fn lock_minutes_left(db: &sqlx::SqlitePool, admin_id: i64) -> Option<i64> {
    sqlx::query_scalar::<_, Option<i64>>(
        "SELECT CASE WHEN locked_until > datetime('now') \
            THEN CAST(ROUND((julianday(locked_until) - julianday('now')) * 1440 + 0.5) AS INTEGER) \
            END FROM admin_users WHERE id = ?",
    )
    .bind(admin_id)
    .fetch_optional(db)
    .await
    .ok()
    .flatten()
    .flatten()
    .map(|minutes| minutes.max(1))
}

/// The banner after a complete login (§3.2.1): how many 2FA codes failed since the last complete
/// login, and from where - whoever typed them knew the password. Kept in the session as facts and
/// worded by the page that shows it (design 70 slice 3a), so it reads in the parent's language.
#[derive(Clone, Debug, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
pub struct LoginWarning {
    pub failures: i64,
    /// The addresses the codes came from, newest first (at most 5).
    pub ips: Vec<String>,
    /// A session from before slice 3a kept the sentence itself (English): shown as it is.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub said: Option<String>,
}

impl LoginWarning {
    /// Account's sentence: "3 wrong two-factor codes were entered before this sign-in (from
    /// 203.0.113.7). Whoever entered them knew your password - change it now."
    pub fn words(&self, lang: crate::i18n::Lang) -> String {
        if let Some(said) = &self.said {
            said.clone()
        } else if self.ips.is_empty() {
            crate::i18n::t_count(lang, "account.warning.login", self.failures, &[])
        } else {
            crate::i18n::t_count(
                lang,
                "account.warning.login_from",
                self.failures,
                &[("ips", &self.ips.join(", "))],
            )
        }
    }
}

pub async fn login_warning(
    db: &sqlx::SqlitePool,
    username: &str,
    failures: i64,
) -> Option<LoginWarning> {
    if failures <= 0 {
        return None;
    }
    let ips: Vec<String> = sqlx::query_scalar(
        "SELECT ip_address FROM security_events \
         WHERE event_type = 'totp_failed' AND username = ?1 AND ip_address IS NOT NULL \
           AND id > COALESCE((SELECT MAX(id) FROM security_events \
                              WHERE event_type = 'login_success' AND username = ?1), 0) \
         GROUP BY ip_address ORDER BY MAX(id) DESC LIMIT 5",
    )
    .bind(username)
    .fetch_all(db)
    .await
    .unwrap_or_default();
    Some(LoginWarning {
        failures,
        ips,
        said: None,
    })
}

/// The forced first password change's write (design 62 §2, qa-62-design Q62-2): the new hash and
/// the next epoch, only while the session's epoch is still the account's, the account isn't
/// removed and its hash is still the one the request verified (a compare-and-swap: of two sessions
/// on one one-time password the first wins). It ends a one-time password's expiry only when 2FA
/// is already on (the account is ready then; qa-62-step12-code P62-1). `None`: nothing written -
/// sign this session out.
pub async fn change_first_password(
    db: &sqlx::SqlitePool,
    admin_id: i64,
    epoch: i64,
    verified_hash: &str,
    new_hash: &str,
) -> Result<Option<i64>, sqlx::Error> {
    sqlx::query_scalar(
        "UPDATE admin_users SET password_hash = ?, must_change_password = 0, \
         invite_expires_at = CASE WHEN totp_enabled = 1 THEN NULL ELSE invite_expires_at END, \
         session_epoch = session_epoch + 1 \
         WHERE id = ? AND session_epoch = ? AND removed_at IS NULL AND password_hash = ? \
           AND passkey_only = 0 RETURNING session_epoch",
    )
    .bind(new_hash)
    .bind(admin_id)
    .bind(epoch)
    .bind(verified_hash)
    .fetch_optional(db)
    .await
}

/// Whether the account's one-time password ran out (`invite_expires_at` passed; design 62
/// §4.1), compared in SQL against `datetime('now')`.
pub async fn invite_expired(db: &sqlx::SqlitePool, admin_id: i64) -> bool {
    sqlx::query_scalar::<_, Option<bool>>(
        "SELECT invite_expires_at <= datetime('now') FROM admin_users WHERE id = ?",
    )
    .bind(admin_id)
    .fetch_optional(db)
    .await
    .ok()
    .flatten()
    .flatten()
    .unwrap_or(false)
}

/// Bumps the account's session epoch: every session that stored the old one is signed out at its
/// next request. Returns the new epoch (for the session that made the change), `None` on error.
pub async fn bump_epoch(db: &sqlx::SqlitePool, admin_id: i64) -> Option<i64> {
    sqlx::query_scalar(
        "UPDATE admin_users SET session_epoch = session_epoch + 1 WHERE id = ? \
         RETURNING session_epoch",
    )
    .bind(admin_id)
    .fetch_optional(db)
    .await
    .ok()
    .flatten()
}

/// `kid_phone_server reset-2fa <user>` (§3.2.3): clears the TOTP (the next sign-in sets it up
/// again), the lock and its counter, signs out every session, removes every device's
/// notifications (design 35 §7) - and replaces the password with a
/// new one in `data_dir/initial-admin-password-<id>` (0600, to be changed at the next sign-in;
/// one file per account, design 62 §2). The CLI
/// exists for someone who knows the password keeping the account locked; keeping the password
/// would hand the account to whoever signs in first and sets up an authenticator (QA 22 M4).
/// It also clears a one-time password's expiry, and restores a removed account (logged
/// `parent_restored`): the shell is the way back for a parent the other one removed. The account's
/// passkeys are deleted in the same transaction (design 74 D1.3b: no way back leaves a lost
/// phone's passkey working), and "Kun passkey" goes with them (D2.4: the way back for the last parent, never
/// needing the password). `reset-sign-in <user>` is the same command (D2.4).
/// Returns a line for the terminal.
pub async fn reset_2fa(
    db: &sqlx::SqlitePool,
    username: &str,
    data_dir: &std::path::Path,
) -> Result<String, String> {
    let account: Option<(i64, bool)> =
        sqlx::query_as("SELECT id, removed_at IS NOT NULL FROM admin_users WHERE username = ?")
            .bind(username)
            .fetch_optional(db)
            .await
            .map_err(|err| format!("couldn't read the account: {err}"))?;
    let Some((id, removed)) = account else {
        return Err(format!("there is no admin named {username:?}"));
    };
    let (password, path) =
        crate::startup::new_password_file(data_dir, &crate::startup::reset_password_file(id))?;
    let hash = crate::security::hash_password(&password);
    // The shell is the root of trust (design 62 §2): a removed parent comes back with its
    // username, and no one-time password's expiry stays on the account.
    let passkeys = async {
        let mut tx = db.begin().await?;
        // Read in the transaction for the terminal's line (qa-passkeys-2 L3).
        let passkey_only: bool =
            sqlx::query_scalar("SELECT passkey_only FROM admin_users WHERE id = ?")
                .bind(id)
                .fetch_one(&mut *tx)
                .await?;
        sqlx::query(
            "UPDATE admin_users SET totp_secret = NULL, totp_enabled = 0, totp_last_step = NULL, \
             failed_login_attempts = 0, locked_until = NULL, lock_count = 0, \
             password_hash = ?, must_change_password = 1, invite_expires_at = NULL, \
             removed_at = NULL, passkey_only = 0, session_epoch = session_epoch + 1 WHERE id = ?",
        )
        .bind(&hash)
        .bind(id)
        .execute(&mut *tx)
        .await?;
        let passkeys = crate::passkeys::delete_all(&mut *tx, id).await?;
        tx.commit().await.map(|()| (passkeys, passkey_only))
    }
    .await
    .map_err(|err: sqlx::Error| format!("couldn't reset: {err}"))?;
    let (passkeys, passkey_only) = passkeys;
    // A lost authenticator may be a lost device: it stops getting notifications (design 35 §7).
    sqlx::query("DELETE FROM push_subscriptions WHERE admin_id = ?")
        .bind(id)
        .execute(db)
        .await
        .map_err(|err| format!("couldn't remove the notification subscriptions: {err}"))?;
    if removed {
        // A restored parent starts with nothing unread, as an added one (design 62 §2, step 5).
        crate::parent_events::read_everything(db, id)
            .await
            .map_err(|err| format!("couldn't mark the events read: {err}"))?;
    }
    let (event, detail) = if removed {
        ("parent_restored", "security.detail.shell.restored")
    } else {
        ("totp_reset", "security.detail.shell.reset")
    };
    crate::security::record_security_event(
        db,
        event,
        Some(username),
        None,
        Some(crate::security::Detail::words(detail, &[])),
    )
    .await;
    // The restore doesn't refuse past Add's cap (qa-62-step12-code P62-3): the shell is the way back
    // for a parent the other one removed, and a full family must not lock it out. It says so.
    let mut restored = String::new();
    if removed {
        restored = format!("{username} was removed and is restored. ");
        let active: i64 =
            sqlx::query_scalar("SELECT COUNT(*) FROM admin_users WHERE removed_at IS NULL")
                .fetch_one(db)
                .await
                .unwrap_or(0);
        if active > crate::parents::MAX_PARENTS {
            restored.push_str(&format!(
                "The family now has {active} parents, more than the {} Add Parent allows: \
                 Add refuses until enough are removed. ",
                crate::parents::MAX_PARENTS
            ));
        }
    }
    let passkeys = match passkeys {
        0 => String::new(),
        1 => "its passkey is deleted, ".to_string(),
        n => format!("its {n} passkeys are deleted, "),
    };
    let what = if passkey_only {
        format!("Passkey Only for {username} is turned off")
    } else {
        format!("Two-factor login for {username} is reset")
    };
    Ok(format!(
        "{}{what}, every session is signed out, {passkeys}notifications are \
         turned off on every device, and the password is replaced: the new one is in {} (readable by the server's user only). Sign in with it, \
         then set a new password and a new authenticator - the file is deleted then.",
        restored,
        path.display()
    ))
}

/// The admin listener's CSRF check for unsafe methods (§3.2.5), before the session is read:
/// - `Sec-Fetch-Site: same-origin` or `none` passes; any other value is refused;
/// - without it, an `Origin` must name this host (host[:port] only - a proxy may not say the
///   scheme); "null" or another host is refused;
/// - neither header: passes (not a browser, and a client without them has no cookie to abuse).
///
/// The device API (bearer tokens) and `/healthz` are exempt.
pub async fn csrf_guard(State(state): State<AppState>, request: Request, next: Next) -> Response {
    let method = request.method();
    let safe = matches!(*method, Method::GET | Method::HEAD | Method::OPTIONS);
    let path = request.uri().path();
    // The push receipt (design 35 §7) is posted by the service worker with no cookie: the
    // per-delivery token is its proof, so a forged cross-site POST gains nothing - and a WebKit
    // service worker's fetch carries no Sec-Fetch-Site while a proxy may rewrite Host, which made
    // every iPhone receipt a `csrf_refused` (the user, 2026-10-19: pushes shown, "Nothing shown yet").
    if safe
        || path.starts_with("/api/devices/")
        || path == "/healthz"
        || path == "/notifications/receipt"
    {
        return next.run(request).await;
    }
    let peer_trusted =
        crate::gate::peer_of(&request).is_some_and(|peer| state.net.is_trusted(peer.ip()));
    if let Err(reason) = csrf_check(request.headers(), peer_trusted) {
        let client = crate::gate::peer_of(&request)
            .map(|peer| state.net.client(request.headers(), peer.ip()));
        let ip = client.map(|c| c.describe());
        state
            .audit
            .record(
                &state.db,
                "csrf_refused",
                &client.map(|c| c.key()).unwrap_or_default(),
                ip.as_deref(),
                crate::security::Detail::words(reason, &[])
                    .with_facts(format!("{method} {}", crate::audit::clip(path, 100))),
            )
            .await;
        // Before the session layer: `?lang=` or the browser's language.
        let lang = crate::i18n::Lang::of_request(
            request.uri(),
            request.headers(),
            None,
            state.config.pwa_default_lang,
        );
        return crate::i18n::error(lang, StatusCode::FORBIDDEN, "error.csrf");
    }
    next.run(request).await
}

/// [csrf_guard]'s decision; a refusal is its reason's key (`security.detail.csrf.*`, the log's
/// words). `peer_trusted`: `X-Forwarded-Host` from it counts as the host too.
pub fn csrf_check(headers: &HeaderMap, peer_trusted: bool) -> Result<(), &'static str> {
    if let Some(site) = headers.get("sec-fetch-site") {
        return match site.to_str().unwrap_or("") {
            "same-origin" | "none" => Ok(()),
            _ => Err("security.detail.csrf.cross_site"),
        };
    }
    let Some(origin) = headers.get(header::ORIGIN) else {
        return Ok(());
    };
    let origin = origin.to_str().unwrap_or("");
    let Some((_, origin_host)) = origin.split_once("://") else {
        return Err("security.detail.csrf.no_site");
    };
    let origin_host = normalize_host(origin_host);
    let host_matches = |value: Option<&HeaderValue>| {
        value
            .and_then(|v| v.to_str().ok())
            .is_some_and(|host| normalize_host(host.split(',').next().unwrap_or("")) == origin_host)
    };
    if host_matches(headers.get(header::HOST))
        || (peer_trusted && host_matches(headers.get("x-forwarded-host")))
    {
        Ok(())
    } else {
        Err("security.detail.csrf.other_host")
    }
}

/// host[:port], lower-case, without a trailing slash or a default port.
fn normalize_host(host: &str) -> String {
    let host = host.trim().trim_end_matches('/').to_ascii_lowercase();
    host.strip_suffix(":443")
        .or_else(|| host.strip_suffix(":80"))
        .map(str::to_string)
        .unwrap_or(host)
}

/// The admin pages' CSP (design 22 §3.2.4; phase 2, S7 and security review L5): scripts only from
/// `/static` - no template has an inline script or an `on*=` handler
/// (`tests::admin_auth::no_template_has_inline_script`). Inline styles stay allowed.
pub const ADMIN_CSP: &str = "default-src 'self'; script-src 'self'; \
     style-src 'self' 'unsafe-inline'; img-src 'self' data: https://tile.openstreetmap.org; \
     connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'; \
     object-src 'none'";

/// Admin pages that must never be stored: live locations and the Locations page (every kept fix,
/// design 44), backups, the setup QR (an enrollment code), the provisioning settings, the account
/// and 2FA pages, Lock Screen & PINs (design 59 §2.2: "Show Child PIN…" answers on its path with
/// the PIN; its place comes back from `scroll-restore.js`'s record); and the inbox (design 35,
/// qa-35-s1-code L4), so Back never shows a read event as unread from the back-forward cache. The
/// tab pages with the bell keep their cache and scroll restore (the count may lag one navigation
/// after Back - accepted).
pub fn no_store_path(path: &str) -> bool {
    let segments: Vec<&str> = path.trim_start_matches('/').split('/').collect();
    matches!(
        segments.as_slice(),
        ["devices", _, "locations.json"]
            | ["devices", _, "locations"]
            | ["devices", _, "locate-result.json"]
            // Design 64: a zone's centre is a child's home or school.
            | ["devices", _, "zones.json"]
            | ["devices", _, "zones", ..]
            | ["devices", _, "lock"]
            | ["devices", _, "provision"]
            | ["devices", _, "regenerate-code"]
            | ["backups", ..]
            | ["settings", "provisioning"]
            | ["account", ..]
            | ["auth", ..]
            // Design 74 §6.5: the lock screen.
            | ["lock", ..]
            | ["notifications", ..]
    )
}

/// A page that is a POST's answer - rendered by the POST itself, not reached by its redirect: the
/// setup code, a refused form shown again, the sign-in steps' errors, a music import's preview -
/// gets `<body data-post-answer`, so `static/reconnect.js` never reloads it in place after the PWA
/// was away: a reload would resend the form (qa-srv-sweep-resume-code M1). Every HTML answer to a
/// POST outside the device API, whatever its status; the body is the server's own page, read
/// whole. One rule here instead of a flag in each of the ~45 handlers that render on a POST.
pub async fn mark_post_answer(request: Request, next: Next) -> Response {
    let post =
        request.method() == Method::POST && !request.uri().path().starts_with("/api/devices/");
    let response = next.run(request).await;
    let html = response
        .headers()
        .get(header::CONTENT_TYPE)
        .and_then(|value| value.to_str().ok())
        .is_some_and(|value| value.starts_with("text/html"));
    if !post || !html {
        return response;
    }
    let (mut parts, body) = response.into_parts();
    let bytes = match axum::body::to_bytes(body, usize::MAX).await {
        Ok(bytes) => bytes,
        Err(err) => {
            tracing::error!(%err, "couldn't read a POST's page to mark it");
            return StatusCode::INTERNAL_SERVER_ERROR.into_response();
        }
    };
    parts.headers.remove(header::CONTENT_LENGTH);
    Response::from_parts(parts, axum::body::Body::from(mark_body(&bytes)))
}

/// `html` with ` data-post-answer` after its first `<body` (unchanged without one).
pub fn mark_body(html: &[u8]) -> Vec<u8> {
    const BODY: &[u8] = b"<body";
    const MARK: &[u8] = b" data-post-answer";
    match html.windows(BODY.len()).position(|w| w == BODY) {
        Some(at) => {
            let split = at + BODY.len();
            let mut out = Vec::with_capacity(html.len() + MARK.len());
            out.extend_from_slice(&html[..split]);
            out.extend_from_slice(MARK);
            out.extend_from_slice(&html[split..]);
            out
        }
        None => html.to_vec(),
    }
}

/// Headers on every admin response (§3.2.4); the device API sets its own (`limits`).
pub async fn admin_headers(request: Request, next: Next) -> Response {
    let path = request.uri().path().to_string();
    let mut response = next.run(request).await;
    if path.starts_with("/api/devices/") {
        return response;
    }
    let success = response.status().is_success();
    let headers = response.headers_mut();
    let set = |headers: &mut HeaderMap, name: header::HeaderName, value: &'static str| {
        headers.insert(name, HeaderValue::from_static(value));
    };
    set(
        headers,
        header::STRICT_TRANSPORT_SECURITY,
        "max-age=31536000",
    );
    set(headers, header::X_CONTENT_TYPE_OPTIONS, "nosniff");
    set(headers, header::REFERRER_POLICY, "same-origin");
    set(headers, header::X_FRAME_OPTIONS, "DENY");
    headers.insert(
        "cross-origin-opener-policy",
        HeaderValue::from_static("same-origin"),
    );
    headers.insert(
        "permissions-policy",
        HeaderValue::from_static("camera=(), microphone=(), geolocation=()"),
    );
    // A page that sets its own policy keeps it: only the Spotify page does (design 26c §2.3),
    // ADMIN_CSP with its login form's `form-action` widened to Spotify's accounts service - a
    // browser enforces `form-action` on the 303 the form's answer is.
    if !headers.contains_key(header::CONTENT_SECURITY_POLICY) {
        set(headers, header::CONTENT_SECURITY_POLICY, ADMIN_CSP);
    }
    let is_html = headers
        .get(header::CONTENT_TYPE)
        .and_then(|v| v.to_str().ok())
        .is_some_and(|t| t.starts_with("text/html"));
    if no_store_path(&path) {
        set(headers, header::CACHE_CONTROL, "no-store");
    } else if path.starts_with("/static/startup/") && success {
        // The iPhone start-up images (head.html's links carry `?v=`, bumped when they change):
        // fetched once, never again.
        set(
            headers,
            header::CACHE_CONTROL,
            "public, max-age=31536000, immutable",
        );
    } else if is_html && !headers.contains_key(header::CACHE_CONTROL) {
        // Not no-store: the back-forward cache and the PWA's scroll restore stay.
        set(headers, header::CACHE_CONTROL, "private, no-cache");
    }
    if !no_store_path(&path) {
        response.extensions_mut().insert(Compressible);
    }
    response
}

/// An admin answer [admin_headers] lets the admin listener gzip (2026-11-03: Security Log was
/// ~190 KB of HTML): every one but the device API and the [no_store_path] pages, which carry the
/// secrets - the setup QR and enrollment code, the Child PIN, 2FA setup, a reset's one-time
/// password, backups. BREACH needs the attacker to make the parent's browser fetch a page with a
/// secret and with words the attacker chose; the session cookie is SameSite=Strict (no cross-site
/// request carries it) and CSRF is header-based (no token in any page), so leaving those pages
/// out is defence in depth.
#[derive(Clone, Copy)]
pub struct Compressible;

/// [Compressible] text (and the PWA's JSON): never an image, audio, a zip or an event stream.
pub fn compress_when(
    _: StatusCode,
    _: axum::http::Version,
    headers: &HeaderMap,
    extensions: &axum::http::Extensions,
) -> bool {
    let text = headers
        .get(header::CONTENT_TYPE)
        .and_then(|v| v.to_str().ok())
        .is_some_and(|t| {
            [
                "text/",
                "application/javascript",
                "application/json",
                "application/manifest+json",
                "image/svg+xml",
            ]
            .iter()
            .any(|kind| t.starts_with(kind))
                && !t.starts_with("text/event-stream")
        });
    text && extensions.get::<Compressible>().is_some()
}

#[cfg(test)]
mod tests {
    use super::*;

    /// The banner is worded by the page; a session from before keeps its English sentence (qa-s-i18n-3a L3).
    #[test]
    fn the_login_warning_reads_in_the_language_or_as_it_was_said() {
        let warning = LoginWarning {
            failures: 3,
            ips: vec!["203.0.113.7".to_string()],
            said: None,
        };
        assert_eq!(
            warning.words(crate::i18n::Lang::En),
            "3 wrong two-factor codes were entered before this sign-in (from 203.0.113.7). Whoever \
             entered them knew your password - change it now."
        );
        assert!(
            warning
                .words(crate::i18n::Lang::Nb)
                .starts_with("3 feil tofaktorkoder")
        );
        let stored = serde_json::to_value(&warning).unwrap();
        assert_eq!(
            stored,
            serde_json::json!({"failures": 3, "ips": ["203.0.113.7"]})
        );
        let old = LoginWarning {
            failures: 0,
            ips: Vec::new(),
            said: Some("1 wrong two-factor code was entered.".to_string()),
        };
        assert_eq!(
            old.words(crate::i18n::Lang::Nb),
            "1 wrong two-factor code was entered."
        );
    }

    fn headers(pairs: &[(&'static str, &str)]) -> HeaderMap {
        let mut headers = HeaderMap::new();
        for (name, value) in pairs {
            headers.insert(*name, HeaderValue::from_str(value).unwrap());
        }
        headers
    }

    /// QA 22 M1: a request dropped while argon2 runs keeps its slot until the work ends.
    #[tokio::test]
    async fn a_cancelled_run_keeps_its_slot_until_the_work_ends() {
        let gate = Arc::new(PasswordGate::default());
        let (release, wait) = std::sync::mpsc::channel::<()>();
        let first = {
            let gate = Arc::clone(&gate);
            tokio::spawn(async move {
                gate.run(move || {
                    let _ = wait.recv();
                })
                .await
            })
        };
        tokio::time::sleep(std::time::Duration::from_millis(100)).await;
        first.abort();
        assert!(first.await.unwrap_err().is_cancelled());
        // The first job's work still runs: a second can't start.
        let second =
            tokio::time::timeout(std::time::Duration::from_millis(300), gate.run(|| 42)).await;
        assert!(
            second.is_err(),
            "ran while the cancelled job's argon2 was running"
        );
        release.send(()).unwrap();
        assert_eq!(gate.run(|| 7).await.unwrap(), 7);
    }

    #[tokio::test]
    async fn past_the_queue_a_run_is_refused() {
        let gate = Arc::new(PasswordGate::default());
        let (release, wait) = std::sync::mpsc::channel::<()>();
        let wait = Arc::new(std::sync::Mutex::new(wait));
        let mut jobs = Vec::new();
        for _ in 0..(PASSWORD_RUNNING + PASSWORD_QUEUE) {
            let gate = Arc::clone(&gate);
            let wait = Arc::clone(&wait);
            jobs.push(tokio::spawn(async move {
                gate.run(move || {
                    let _ = wait.lock().unwrap().recv();
                })
                .await
            }));
        }
        tokio::time::sleep(std::time::Duration::from_millis(100)).await;
        assert!(gate.run(|| ()).await.is_err(), "one more than the queue");
        for _ in 0..(PASSWORD_RUNNING + PASSWORD_QUEUE) {
            release.send(()).unwrap();
        }
        for job in jobs {
            assert!(job.await.unwrap().is_ok());
        }
    }

    #[test]
    fn csrf_rules() {
        let same = headers(&[("sec-fetch-site", "same-origin"), ("host", "pi.ts.net")]);
        assert_eq!(csrf_check(&same, false), Ok(()));
        assert_eq!(
            csrf_check(&headers(&[("sec-fetch-site", "none")]), false),
            Ok(())
        );
        for site in ["cross-site", "same-site"] {
            assert!(csrf_check(&headers(&[("sec-fetch-site", site)]), false).is_err());
        }
        // An older browser: Origin against Host, host[:port] only, default ports ignored.
        let ok = headers(&[("origin", "https://pi.ts.net"), ("host", "pi.ts.net")]);
        assert_eq!(csrf_check(&ok, false), Ok(()));
        let port = headers(&[
            ("origin", "https://pi.ts.net:8443"),
            ("host", "pi.ts.net:8443"),
        ]);
        assert_eq!(csrf_check(&port, false), Ok(()));
        let default_port = headers(&[("origin", "https://pi.ts.net"), ("host", "pi.ts.net:443")]);
        assert_eq!(csrf_check(&default_port, false), Ok(()));
        let other = headers(&[("origin", "https://evil.example"), ("host", "pi.ts.net")]);
        assert!(csrf_check(&other, false).is_err());
        let wrong_port = headers(&[("origin", "https://pi.ts.net:9999"), ("host", "pi.ts.net")]);
        assert!(csrf_check(&wrong_port, false).is_err());
        assert!(csrf_check(&headers(&[("origin", "null"), ("host", "pi")]), false).is_err());
        // Behind a proxy that rewrote Host, its X-Forwarded-Host counts - only from a trusted peer.
        let proxied = headers(&[
            ("origin", "https://pi.ts.net"),
            ("host", "127.0.0.1:3100"),
            ("x-forwarded-host", "pi.ts.net"),
        ]);
        assert_eq!(csrf_check(&proxied, true), Ok(()));
        assert!(csrf_check(&proxied, false).is_err());
        // Neither header: not a browser.
        assert_eq!(csrf_check(&headers(&[("host", "pi")]), false), Ok(()));
    }

    #[test]
    fn no_store_paths() {
        for path in [
            "/devices/3/locations.json",
            // Every kept fix (design 44 §1.4).
            "/devices/3/locations",
            "/devices/3/locate-result.json",
            "/devices/3/zones.json",
            "/devices/3/zones/new",
            "/devices/3/zones/7",
            // Show Child PIN's answer (design 59 §2.2), and so the page it is rendered into.
            "/devices/3/lock",
            "/devices/3/provision",
            "/devices/3/regenerate-code",
            "/backups",
            "/backups/backup-1.zip/download",
            "/settings/provisioning",
            "/account",
            "/auth/setup-2fa",
            "/notifications",
            "/notifications/settings",
            "/notifications/subscriptions.json",
        ] {
            assert!(no_store_path(path), "{path}");
        }
        for path in [
            "/devices",
            "/devices/3",
            "/devices/3/lock-wake",
            "/devices/locate",
            "/apps",
            "/settings",
            "/music",
            "/login",
        ] {
            assert!(!no_store_path(path), "{path}");
        }
    }
}
