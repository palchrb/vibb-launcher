//! The parent PIN (design 24; the launcher's "unlock code", "Parent code", the offline override
//! PIN): the checks on a new PIN, the easy-PIN warning - never a refusal, the code is the parents'
//! choice - and its one-time sweep over the PINs stored before it existed. Pure apart from
//! [run_easy_sweep]; the card and the save are `handlers::parent_pin`.

use sqlx::SqlitePool;

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

/// The one-shot message after a parent-PIN save (`?notice=`), shown at the top of the
/// `#parent-pin` card; `true` = a warning or refusal (error style).
pub fn flash_text(code: &str) -> Option<(&'static str, bool)> {
    Some(match code {
        "parent_pin_saved" => (
            "Parent PIN saved. The phone uses it as soon as it checks in, and a lockout running \
             there ends then.",
            false,
        ),
        "parent_pin_saved_easy" => (
            "Parent PIN saved, but it is easy to guess (all one digit, a run like 123456, or a \
             common PIN). The phone uses it as soon as it checks in.",
            true,
        ),
        "parent_pin_cleared" => ("Parent PIN removed.", false),
        "parent_pin_invalid" => ("Parent PIN not changed: it must be 6 to 10 digits.", true),
        "override_is_kid_pin" => (
            "Parent PIN not changed: it must not be the kid's PIN.",
            true,
        ),
        "override_needed_by_lock" => (
            "Parent PIN not removed: the kid's lock needs it (it is the parent code on the lock \
             screen). Remove the kid's PIN first.",
            true,
        ),
        _ => return None,
    })
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
}
