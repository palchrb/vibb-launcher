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
//! [IN_FLIGHT] at once). Typed-shaped failures count toward the client's failure bucket
//! (`limits`), and - only while a typed code is live, since otherwise there is nothing to guess -
//! [TYPED_PAUSE_FAILURES] of them server-wide pause typed codes for the rest of that hour
//! ([Enrollment]).

use std::sync::{Arc, Mutex, OnceLock};

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
/// codes until the hour ends.
pub const TYPED_PAUSE_FAILURES: u32 = 20;
/// Enrollment checks at once.
pub const IN_FLIGHT: usize = 8;

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
    let expires: String = sqlx::query_scalar(
        "UPDATE devices SET enrollment_code_hash = ?, enrollment_code_kind = ?, \
         enrollment_code_expires_at = datetime('now', ?) WHERE id = ? \
         RETURNING enrollment_code_expires_at",
    )
    .bind(code_hash(&code))
    .bind(kind.as_str())
    .bind(format!("+{} minutes", kind.minutes()))
    .bind(device_id)
    .fetch_one(&state.db)
    .await?;
    state.enrollment.refresh(&state.db).await;
    Ok((code, expires))
}

/// The server-wide typed-code state, in memory: until when a typed code is live (read from the
/// database whenever codes change), and this hour's typed-shaped failures.
#[derive(Default)]
pub struct Enrollment {
    inner: Mutex<Inner>,
    gate: OnceLock<Arc<tokio::sync::Semaphore>>,
}

#[derive(Default)]
struct Inner {
    typed_live_until: Option<DateTime<Utc>>,
    /// The hour (unix seconds / 3600) `failures` counts.
    hour: i64,
    failures: u32,
    paused_hour: Option<i64>,
}

fn hour_of(now: DateTime<Utc>) -> i64 {
    now.timestamp().div_euclid(3600)
}

impl Enrollment {
    /// At most [IN_FLIGHT] code checks at once.
    pub fn gate(&self) -> Arc<tokio::sync::Semaphore> {
        Arc::clone(
            self.gate
                .get_or_init(|| Arc::new(tokio::sync::Semaphore::new(IN_FLIGHT))),
        )
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

    /// Counts one typed-shaped failure while a typed code is live; `true` when it starts the
    /// pause.
    pub fn typed_failed(&self, now: DateTime<Utc>) -> bool {
        let mut inner = self.inner.lock().unwrap_or_else(|e| e.into_inner());
        let hour = hour_of(now);
        if inner.hour != hour {
            inner.hour = hour;
            inner.failures = 0;
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
            assert!(!enrollment.typed_failed(now), "failure {n}");
        }
        assert_eq!(enrollment.typed_paused_until(now), None);
        assert!(enrollment.typed_failed(now));
        assert!(!enrollment.typed_failed(now), "starts once");
        assert_eq!(
            enrollment.typed_paused_until(now).unwrap().to_rfc3339(),
            "2026-10-09T15:00:00+00:00"
        );
        let next_hour = now + chrono::Duration::minutes(45);
        assert_eq!(enrollment.typed_paused_until(next_hour), None);
        assert!(!enrollment.typed_failed(next_hour), "the count starts over");
    }
}
