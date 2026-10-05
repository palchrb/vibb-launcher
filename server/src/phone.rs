//! Phone-number normalisation shared with the launcher (design 02-calls.md 3.4, QA #16). The
//! server stores every contact number in this normalised form, and the launcher normalises only
//! the number of a ringing or dialled call, then matches by exact string equality - never by a
//! suffix ("last 8 digits") match, which would let foreign numbers through.
//!
//! Both sides run the same vectors: `testdata/phone_vectors.json` here, a byte-identical copy in
//! kids-launcher-mdm's `app/src/test/resources/`. Change the rules on both sides together.
//!
//! Rules, in order:
//! 1. Strip ` -.()/` and NBSP. Nothing else is stripped: `,`/`;` (dial pauses, extensions) are
//!    rejected below.
//! 2. A leading `00` becomes `+`.
//! 3. `+` followed by 7-15 ASCII digits is E.164.
//! 4. 3-6 ASCII digits with no `+` is a short number (112, 1881, a voicemail number), matched
//!    only exactly.
//! 5. Any other run of ASCII digits is national: one leading trunk `0` is dropped and
//!    `+<default_cc>` prefixed; the result must again be 7-15 digits.
//! 6. Anything else is rejected - `*`, `#` (MMI/USSD codes are never allowlisted), letters, a
//!    fullwidth `＋`, and non-ASCII digits (Arabic-Indic, fullwidth), which Kotlin's `isDigit()`
//!    would otherwise accept.

#[derive(Debug, PartialEq, Eq)]
pub enum PhoneError {
    Empty,
    /// A character other than ASCII digits, a leading `+` and the separators of rule 1.
    InvalidCharacter,
    /// Too few or too many digits for a short or an E.164 number.
    BadLength,
    /// `default_cc` is not 1-3 ASCII digits starting with 1-9.
    BadCountryCode,
}

impl std::fmt::Display for PhoneError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(match self {
            PhoneError::Empty => "the number is empty",
            PhoneError::InvalidCharacter => {
                "only digits, a leading +, spaces and - . ( ) / are allowed"
            }
            PhoneError::BadLength => "a phone number has 3-6 digits (short number) or 7-15 digits",
            PhoneError::BadCountryCode => "the default country code must be 1-3 digits",
        })
    }
}

/// `true` for 1-3 ASCII digits not starting with 0 - the only accepted default country codes.
pub fn valid_country_code(cc: &str) -> bool {
    (1..=3).contains(&cc.len()) && cc.bytes().all(|b| b.is_ascii_digit()) && !cc.starts_with('0')
}

pub fn normalize(raw: &str, default_cc: &str) -> Result<String, PhoneError> {
    if !valid_country_code(default_cc) {
        return Err(PhoneError::BadCountryCode);
    }
    let stripped: String = raw
        .chars()
        .filter(|c| !matches!(c, ' ' | '-' | '.' | '(' | ')' | '/' | '\u{a0}'))
        .collect();
    if stripped.is_empty() {
        return Err(PhoneError::Empty);
    }

    let (plus, digits) = if let Some(rest) = stripped.strip_prefix('+') {
        (true, rest)
    } else if let Some(rest) = stripped.strip_prefix("00") {
        (true, rest)
    } else {
        (false, stripped.as_str())
    };
    if digits.is_empty() || !digits.bytes().all(|b| b.is_ascii_digit()) {
        return Err(if digits.is_empty() {
            PhoneError::BadLength
        } else {
            PhoneError::InvalidCharacter
        });
    }

    if plus {
        return e164(digits);
    }
    if (3..=6).contains(&digits.len()) {
        return Ok(digits.to_string());
    }
    let national = digits.strip_prefix('0').unwrap_or(digits);
    e164(&format!("{default_cc}{national}"))
}

fn e164(digits: &str) -> Result<String, PhoneError> {
    if (7..=15).contains(&digits.len()) {
        Ok(format!("+{digits}"))
    } else {
        Err(PhoneError::BadLength)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const VECTORS: &str = include_str!("../testdata/phone_vectors.json");

    #[test]
    fn shared_vectors() {
        let vectors: serde_json::Value = serde_json::from_str(VECTORS).unwrap();
        let cases = vectors["normalize"].as_array().unwrap();
        assert!(cases.len() > 30);
        for case in cases {
            let input = case["input"].as_str().unwrap();
            let cc = case["cc"].as_str().unwrap();
            let expected = case["output"].as_str();
            assert_eq!(
                normalize(input, cc).ok().as_deref(),
                expected,
                "normalize({input:?}, {cc:?})"
            );
        }
    }

    /// The launcher carries a copy of the vectors; when both repos are checked out side by side
    /// (as in the handy workspace), they must not drift apart.
    #[test]
    fn vectors_match_the_launchers_copy() {
        let sibling = std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
            .join("../kids-launcher-mdm/app/src/test/resources/phone_vectors.json");
        match std::fs::read_to_string(&sibling) {
            Ok(copy) => assert_eq!(copy, VECTORS, "{} differs", sibling.display()),
            Err(_) => eprintln!("{} not found, skipping the comparison", sibling.display()),
        }
    }

    #[test]
    fn country_codes() {
        for ok in ["1", "47", "358"] {
            assert!(valid_country_code(ok), "{ok}");
        }
        for bad in ["", "0", "04", "4x", "1234", "+47", "٤٧"] {
            assert!(!valid_country_code(bad), "{bad}");
        }
        assert_eq!(normalize("91234567", "4x"), Err(PhoneError::BadCountryCode));
    }
}
