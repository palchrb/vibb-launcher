//! Enrollment codes (design 22 §3.1). One live code per phone, of one of two kinds:
//!
//! - **QR**: 26 characters of base32 (130 bits), only ever inside a setup QR, valid
//!   [QR_MINUTES] minutes;
//! - **typed**: 8 characters a person can type ([TYPED_ALPHABET], about 40 bits), valid
//!   [TYPED_MINUTES] minutes.
//!
//! Only the SHA-256 of the normalised code is stored ([code_hash]: upper-cased, spaces and dashes
//! stripped); the plaintext exists only on the page that made it. Using a code is one atomic
//! `UPDATE ... RETURNING` (`handlers::device_api::enroll`).
//!
//! Guessing: QR-shaped codes are always checked (one hash, one indexed lookup, at most
//! [IN_FLIGHT] at once, at most [PER_CLIENT] of them one client's). Typed-shaped failures count
//! toward the client's failure bucket (`limits`), and - only while a typed code is live, since
//! otherwise there is nothing to guess - [TYPED_CLIENT_FAILURES] of them from one client pause
//! that client's typed codes for the rest of the hour, and [TYPED_PAUSE_FAILURES] server-wide
//! (the backstop) everyone's ([Enrollment]).

use std::sync::{Arc, Mutex};

use argon2::password_hash::rand_core::{OsRng, RngCore};
use chrono::{DateTime, NaiveDateTime, Timelike, Utc};
use sha2::{Digest, Sha256};

/// No 0/O/1/I/L: unambiguous to read off a screen and type.
pub const TYPED_ALPHABET: &[u8] = b"23456789ABCDEFGHJKMNPQRSTUVWXYZ";
pub const TYPED_LEN: usize = 8;
pub const TYPED_MINUTES: i64 = 15;
/// RFC 4648 base32.
pub const QR_ALPHABET: &[u8] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
pub const QR_LEN: usize = 26;
pub const QR_MINUTES: i64 = 30;
/// Typed-shaped failures in one hour, server-wide, while a typed code is live, that pause typed
/// codes until the hour ends - the backstop behind [TYPED_CLIENT_FAILURES].
pub const TYPED_PAUSE_FAILURES: u32 = 60;
/// Typed-shaped failures in one hour from one client, while a typed code is live, that pause that
/// client's typed codes until the hour ends (another client's code still works).
pub const TYPED_CLIENT_FAILURES: u32 = 10;
/// Code checks at once, after the body has arrived (QA 22 M2).
pub const IN_FLIGHT: usize = 8;
/// Enrollments one client may have at once, its body's reading included: a slow client holds at
/// most this many, never every slot.
pub const PER_CLIENT: usize = 2;
/// How long an enrollment's body may take to arrive.
pub const BODY_TIMEOUT: std::time::Duration = std::time::Duration::from_secs(5);

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Kind {
    Qr,
    Typed,
}

impl Kind {
    pub fn as_str(self) -> &'static str {
        match self {
            Kind::Qr => "qr",
            Kind::Typed => "typed",
        }
    }

    pub fn minutes(self) -> i64 {
        match self {
            Kind::Qr => QR_MINUTES,
            Kind::Typed => TYPED_MINUTES,
        }
    }
}

/// One character from `alphabet`, uniformly (rejection sampling).
fn random_char(alphabet: &[u8]) -> char {
    let len = alphabet.len() as u32;
    let limit = u32::MAX - u32::MAX % len;
    loop {
        let n = OsRng.next_u32();
        if n < limit {
            return alphabet[(n % len) as usize] as char;
        }
    }
}

pub fn generate(kind: Kind) -> String {
    let (alphabet, len) = match kind {
        Kind::Qr => (QR_ALPHABET, QR_LEN),
        Kind::Typed => (TYPED_ALPHABET, TYPED_LEN),
    };
    (0..len).map(|_| random_char(alphabet)).collect()
}

/// How a typed code is shown: "ABCD-EFGH" (the dash is stripped again on the way in).
pub fn display(code: &str) -> String {
    if code.len() == TYPED_LEN {
        format!("{}-{}", &code[..4], &code[4..])
    } else {
        code.to_string()
    }
}

/// Upper-cased, spaces and dashes stripped.
pub fn normalize(code: &str) -> String {
    code.chars()
        .filter(|c| !c.is_whitespace() && *c != '-')
        .flat_map(char::to_uppercase)
        .collect()
}

/// What a submitted code looks like (after [normalize]); anything else can't be a code.
pub fn shape(normalized: &str) -> Option<Kind> {
    let all_in = |alphabet: &[u8]| normalized.bytes().all(|b| alphabet.contains(&b));
    if normalized.len() == QR_LEN && all_in(QR_ALPHABET) {
        Some(Kind::Qr)
    } else if normalized.len() == TYPED_LEN && all_in(TYPED_ALPHABET) {
        Some(Kind::Typed)
    } else {
        None
    }
}

/// What is stored: hex SHA-256 of the normalised code.
pub fn code_hash(code: &str) -> String {
    hex::encode(Sha256::digest(normalize(code).as_bytes()))
}

/// Gives `device_id` a new code of `kind`, replacing any live one, and returns the plaintext.
/// Also refreshes [Enrollment]'s idea of whether a typed code is live.
pub async fn new_code(
    state: &crate::AppState,
    device_id: i64,
    kind: Kind,
) -> Result<(String, String), sqlx::Error> {
    let code = generate(kind);
    // Design 129b D4 (M5): each code has its own tag, which the key exception's form carries; the old code's waiting
    // attempts, its wrong numbers and its older-launcher mark go with it.
    let tag = crate::device_key::random_bytes::<8>();
    let mut tx = state.db.begin().await?;
    let expires: String = sqlx::query_scalar(
        "UPDATE devices SET enrollment_code_hash = ?, enrollment_code_kind = ?, \
         enrollment_code_expires_at = datetime('now', ?), enrollment_code_tag = ?, enroll_wrong_numbers = 0, \
         enroll_old_app_at = NULL WHERE id = ? \
         RETURNING enrollment_code_expires_at",
    )
    .bind(code_hash(&code))
    .bind(kind.as_str())
    .bind(format!("+{} minutes", kind.minutes()))
    .bind(tag.as_slice())
    .bind(device_id)
    .fetch_one(&mut *tx)
    .await?;
    sqlx::query("DELETE FROM enroll_key_attempts WHERE device_id = ?")
        .bind(device_id)
        .execute(&mut *tx)
        .await?;
    tx.commit().await?;
    state.enrollment.refresh(&state.db).await;
    Ok((code, expires))
}

/// The server-wide typed-code state, in memory: until when a typed code is live (read from the
/// database whenever codes change), and this hour's typed-shaped failures.
pub struct Enrollment {
    inner: Mutex<Inner>,
    gate: Arc<tokio::sync::Semaphore>,
    /// Each client's enrollments in progress ([PER_CLIENT]); a key leaves at 0.
    per_client: Mutex<std::collections::HashMap<String, usize>>,
}

impl Default for Enrollment {
    fn default() -> Self {
        Enrollment {
            inner: Default::default(),
            gate: Arc::new(tokio::sync::Semaphore::new(IN_FLIGHT)),
            per_client: Default::default(),
        }
    }
}

/// One client's enrollment in progress ([Enrollment::enter_client]); given back on drop.
pub struct ClientSlot<'a> {
    enrollment: &'a Enrollment,
    key: String,
}

impl Drop for ClientSlot<'_> {
    fn drop(&mut self) {
        let mut slots = self
            .enrollment
            .per_client
            .lock()
            .unwrap_or_else(|e| e.into_inner());
        if let Some(count) = slots.get_mut(&self.key) {
            *count -= 1;
            if *count == 0 {
                slots.remove(&self.key);
            }
        }
    }
}

#[derive(Default)]
struct Inner {
    typed_live_until: Option<DateTime<Utc>>,
    /// The hour (unix seconds / 3600) `failures` and `client_failures` count.
    hour: i64,
    failures: u32,
    paused_hour: Option<i64>,
    /// This hour's typed-shaped failures per client key (at most `limits::MAX_KEYS` keys: past it a
    /// new key isn't counted on its own - the server-wide count still is).
    client_failures: std::collections::HashMap<String, u32>,
}

fn hour_of(now: DateTime<Utc>) -> i64 {
    now.timestamp().div_euclid(3600)
}

impl Enrollment {
    /// At most [IN_FLIGHT] code checks at once.
    pub fn gate(&self) -> Arc<tokio::sync::Semaphore> {
        Arc::clone(&self.gate)
    }

    /// One of this client's [PER_CLIENT] enrollments, `None` while it has them all.
    pub fn enter_client(&self, key: &str) -> Option<ClientSlot<'_>> {
        let mut slots = self.per_client.lock().unwrap_or_else(|e| e.into_inner());
        let count = slots.entry(key.to_string()).or_insert(0);
        if *count >= PER_CLIENT {
            return None;
        }
        *count += 1;
        Some(ClientSlot {
            enrollment: self,
            key: key.to_string(),
        })
    }

    /// Reads until when a typed code is live. A read error keeps the old value.
    pub async fn refresh(&self, db: &sqlx::SqlitePool) {
        let latest: Result<Option<String>, sqlx::Error> = sqlx::query_scalar(
            "SELECT MAX(enrollment_code_expires_at) FROM devices \
             WHERE enrollment_code_kind = 'typed' AND enrollment_code_hash IS NOT NULL \
             AND enrollment_code_expires_at > datetime('now')",
        )
        .fetch_one(db)
        .await;
        match latest {
            Ok(latest) => {
                let until = latest
                    .and_then(|t| NaiveDateTime::parse_from_str(&t, "%Y-%m-%d %H:%M:%S").ok())
                    .map(|t| t.and_utc());
                self.inner
                    .lock()
                    .unwrap_or_else(|e| e.into_inner())
                    .typed_live_until = until;
            }
            Err(err) => tracing::error!(%err, "couldn't read the live enrollment codes"),
        }
    }

    pub fn typed_live(&self, now: DateTime<Utc>) -> bool {
        self.inner
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .typed_live_until
            .is_some_and(|until| until > now)
    }

    /// When the typed-code pause ends, if one is on.
    pub fn typed_paused_until(&self, now: DateTime<Utc>) -> Option<DateTime<Utc>> {
        let inner = self.inner.lock().unwrap_or_else(|e| e.into_inner());
        (inner.paused_hour == Some(hour_of(now))).then(|| end_of_hour(now))
    }

    /// When this client's own typed-code pause ends, if one is on ([TYPED_CLIENT_FAILURES]).
    pub fn client_paused_until(&self, key: &str, now: DateTime<Utc>) -> Option<DateTime<Utc>> {
        let inner = self.inner.lock().unwrap_or_else(|e| e.into_inner());
        (inner.hour == hour_of(now)
            && inner
                .client_failures
                .get(key)
                .is_some_and(|n| *n >= TYPED_CLIENT_FAILURES))
        .then(|| end_of_hour(now))
    }

    /// Counts one typed-shaped failure from `key` while a typed code is live; `true` when it
    /// starts the server-wide pause.
    pub fn typed_failed(&self, key: &str, now: DateTime<Utc>) -> bool {
        let mut inner = self.inner.lock().unwrap_or_else(|e| e.into_inner());
        let hour = hour_of(now);
        if inner.hour != hour {
            inner.hour = hour;
            inner.failures = 0;
            inner.client_failures.clear();
        }
        if inner.client_failures.len() < crate::limits::MAX_KEYS
            || inner.client_failures.contains_key(key)
        {
            *inner.client_failures.entry(key.to_string()).or_insert(0) += 1;
        }
        inner.failures += 1;
        if inner.failures >= TYPED_PAUSE_FAILURES && inner.paused_hour != Some(hour) {
            inner.paused_hour = Some(hour);
            return true;
        }
        false
    }
}

pub fn end_of_hour(now: DateTime<Utc>) -> DateTime<Utc> {
    let start = now
        .with_minute(0)
        .and_then(|t| t.with_second(0))
        .and_then(|t| t.with_nanosecond(0))
        .unwrap_or(now);
    start + chrono::Duration::hours(1)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn codes_have_their_shapes() {
        for _ in 0..200 {
            let qr = generate(Kind::Qr);
            assert_eq!(qr.len(), 26);
            assert_eq!(shape(&qr), Some(Kind::Qr), "{qr}");
            let typed = generate(Kind::Typed);
            assert_eq!(shape(&typed), Some(Kind::Typed), "{typed}");
            assert_eq!(shape(&normalize(&display(&typed))), Some(Kind::Typed));
        }
        assert_eq!(normalize(" abcd-efgh\n"), "ABCDEFGH");
        assert_eq!(shape("ABCDEFGH"), Some(Kind::Typed));
        // 0, O, 1, I, L are never in a typed code.
        assert_eq!(shape("ABCDEFG0"), None);
        assert_eq!(shape("ABCDEFGO"), None);
        assert_eq!(shape("ABC"), None);
        assert_eq!(shape(&"A".repeat(27)), None);
        assert_eq!(
            shape(&format!("{}1", "A".repeat(25))),
            None,
            "1 isn't base32"
        );
        assert_eq!(code_hash("abcd-efgh"), code_hash("ABCDEFGH"));
        assert_ne!(code_hash("ABCDEFGH"), code_hash("ABCDEFGJ"));
        assert_eq!(display("ABCDEFGH"), "ABCD-EFGH");
    }

    #[test]
    fn typed_failures_pause_typed_codes_for_the_rest_of_the_hour() {
        let enrollment = Enrollment::default();
        let now = DateTime::parse_from_rfc3339("2026-10-09T14:20:00Z")
            .unwrap()
            .with_timezone(&Utc);
        for n in 1..TYPED_PAUSE_FAILURES {
            assert!(
                !enrollment.typed_failed(&format!("c{n}"), now),
                "failure {n}"
            );
        }
        assert_eq!(enrollment.typed_paused_until(now), None);
        assert!(enrollment.typed_failed("c0", now));
        assert!(!enrollment.typed_failed("c0", now), "starts once");
        assert_eq!(
            enrollment.typed_paused_until(now).unwrap().to_rfc3339(),
            "2026-10-09T15:00:00+00:00"
        );
        let next_hour = now + chrono::Duration::minutes(45);
        assert_eq!(enrollment.typed_paused_until(next_hour), None);
        assert!(
            !enrollment.typed_failed("c0", next_hour),
            "the count starts over"
        );
    }

    #[test]
    fn one_clients_typed_failures_pause_that_client_alone() {
        let enrollment = Enrollment::default();
        let now = DateTime::parse_from_rfc3339("2026-10-09T14:20:00Z")
            .unwrap()
            .with_timezone(&Utc);
        for _ in 1..TYPED_CLIENT_FAILURES {
            enrollment.typed_failed("203.0.113.7", now);
        }
        assert_eq!(enrollment.client_paused_until("203.0.113.7", now), None);
        enrollment.typed_failed("203.0.113.7", now);
        assert_eq!(
            enrollment
                .client_paused_until("203.0.113.7", now)
                .unwrap()
                .to_rfc3339(),
            "2026-10-09T15:00:00+00:00"
        );
        assert_eq!(enrollment.client_paused_until("203.0.113.8", now), None);
        assert_eq!(enrollment.typed_paused_until(now), None);
        let next_hour = now + chrono::Duration::minutes(45);
        assert_eq!(
            enrollment.client_paused_until("203.0.113.7", next_hour),
            None
        );
    }

    #[test]
    fn a_client_holds_at_most_its_share_of_the_slots() {
        let enrollment = Enrollment::default();
        let held: Vec<_> = (0..PER_CLIENT)
            .map(|_| enrollment.enter_client("a").unwrap())
            .collect();
        assert!(enrollment.enter_client("a").is_none());
        assert!(enrollment.enter_client("b").is_some());
        drop(held);
        assert!(enrollment.enter_client("a").is_some());
        assert!(enrollment.per_client.lock().unwrap().is_empty());
    }
}
