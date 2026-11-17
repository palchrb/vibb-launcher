//! VAPID (RFC 8292) at `POST /up/{secret}` (design 108b §4): a registration made with a `vapid` key only takes pushes
//! signed with that key. Pure: the caller passes the headers' text, the clock and the accepted origins.
//!
//! The order is the design's (§4.3, Q3's refinement): form, key, the JWT's shape, **the signature**, then `exp`, then
//! `aud`. So a bad signature is always [Outcome::Bad], and [Outcome::Time] / [Outcome::Address] only ever describe a
//! correctly signed push. The headers carry a 24-hour credential: they are **never logged**.

use std::collections::HashMap;
use std::sync::Mutex;

use base64::Engine as _;
use serde_json::Value;

/// The longest `Authorization` / `Crypto-Key` read.
const HEADER_MAX: usize = 1024;
/// `exp` may lie this far behind now (a slow sender clock) ...
const EXP_PAST_SECS: i64 = 5 * 60;
/// ... and this far ahead (RFC 8292's 24 h, and an hour for a fast sender clock, M3).
const EXP_AHEAD_SECS: i64 = 25 * 60 * 60;

/// What the check says about one push.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Outcome {
    /// Signed with the registered key, in time, for our address.
    Taken,
    /// No `Authorization` of either form, or no key in it: 401.
    Missing,
    /// A key that isn't the registered one: 403.
    WrongKey,
    /// Not a well-formed or correctly signed token: 403.
    Bad,
    /// Correctly signed, `exp` out of its window: 503 (our clock may be at fault).
    Time,
    /// Correctly signed, `aud` not our address: 503 (our origin setting may be at fault).
    Address,
}

impl Outcome {
    /// Why a refused push was refused, as `vapid_refused_why` stores it: `time` and `address` are the server's own
    /// clock and origin (a correctly signed push), `unsigned` is the sender's.
    pub fn why(self) -> &'static str {
        match self {
            Outcome::Time => "time",
            Outcome::Address => "address",
            _ => "unsigned",
        }
    }
}

/// Base64url, tolerating padding and the standard alphabet.
fn b64(text: &str) -> Option<Vec<u8>> {
    let text = text.trim().trim_end_matches('=');
    let url: String = text
        .chars()
        .map(|c| match c {
            '+' => '-',
            '/' => '_',
            c => c,
        })
        .collect();
    base64::engine::general_purpose::URL_SAFE_NO_PAD
        .decode(url)
        .ok()
}

/// A stored key as the check reads it.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Stored {
    /// A P-256 point (65 bytes): pushes to the registration are checked against it.
    Key(Vec<u8>),
    /// Not a point (L7: an R1 server stored any 87 characters): reads as no key, with one warning per key.
    NotAPoint,
    /// The point check couldn't run (the RNG failed): the push is refused with a 503, never taken unchecked (L1).
    Unavailable,
}

/// A stored key as the check uses it. The ECDH behind the point check runs once per key: the verdict is kept (a few
/// hundred keys at most; the map is emptied when it grows past [VERDICTS_MAX]), so a request to a keyed endpoint
/// costs no more than its own verify (L1). An error of the RNG is never kept and never read as "not a point".
pub fn stored_key(key: &str) -> Stored {
    static VERDICTS: Mutex<Option<HashMap<String, bool>>> = Mutex::new(None);
    const VERDICTS_MAX: usize = 4096;
    let Some(bytes) = b64(key).filter(|b| b.len() == 65 && b[0] == 0x04) else {
        return Stored::NotAPoint;
    };
    let mut verdicts = VERDICTS.lock().unwrap_or_else(|e| e.into_inner());
    let verdicts = verdicts.get_or_insert_default();
    let is_point = match verdicts.get(key) {
        Some(known) => *known,
        None => match crate::webpush::browser_key_check(&bytes) {
            Ok(is_point) => {
                if verdicts.len() >= VERDICTS_MAX {
                    verdicts.clear();
                }
                verdicts.insert(key.to_string(), is_point);
                if !is_point {
                    tracing::warn!(
                        "app push: a stored VAPID key isn't a P-256 point; its pushes are taken unchecked"
                    );
                }
                is_point
            }
            Err(_) => {
                tracing::error!("app push: the VAPID point check couldn't run; pushes are refused");
                return Stored::Unavailable;
            }
        },
    };
    if is_point {
        Stored::Key(bytes)
    } else {
        Stored::NotAPoint
    }
}

/// `key` (base64url, 65 bytes) is an uncompressed P-256 point: its bytes.
pub fn point(key: &str) -> Option<Vec<u8>> {
    let bytes = b64(key)?;
    (bytes.len() == 65 && bytes[0] == 0x04 && crate::webpush::valid_browser_key(&bytes))
        .then_some(bytes)
}

/// The token and the key a request carries, by either form; `None` when it has neither form.
fn token_and_key<'a>(
    authorization: &'a str,
    crypto_key: Option<&'a str>,
) -> Option<(&'a str, Option<&'a str>)> {
    let authorization = authorization.trim();
    let (scheme, rest) = authorization
        .split_once(|c: char| c.is_ascii_whitespace())
        .unwrap_or((authorization, ""));
    if scheme.eq_ignore_ascii_case("vapid") {
        let (mut t, mut k) = (None, None);
        for param in rest.split(',') {
            let Some((name, value)) = param.trim().split_once('=') else {
                continue;
            };
            let value = value.trim().trim_matches('"');
            match name.trim().to_ascii_lowercase().as_str() {
                "t" => t = Some(value),
                "k" => k = Some(value),
                _ => {}
            }
        }
        t.map(|t| (t, k))
    } else if scheme.eq_ignore_ascii_case("webpush") {
        let token = rest.trim().trim_matches('"');
        let key = crypto_key.and_then(|header| {
            header.split([';', ',']).find_map(|param| {
                let (name, value) = param.trim().split_once('=')?;
                name.trim()
                    .eq_ignore_ascii_case("p256ecdsa")
                    .then(|| value.trim().trim_matches('"'))
            })
        });
        (!token.is_empty()).then_some((token, key))
    } else {
        None
    }
}

/// `aud` as an origin (lower-case scheme and host, port 443 dropped), `None` when it isn't one.
fn origin_of(aud: &str) -> Option<String> {
    super::check_origin(aud)
}

/// The check. `registered` is the registered key's 65 bytes ([stored_key]); `origins` the accepted origins, the
/// current one and every replaced one still within its 30 days (M4); `now` in unix seconds.
pub fn check(
    registered: &[u8],
    authorization: Option<&str>,
    crypto_key: Option<&str>,
    now: i64,
    origins: &[String],
) -> Outcome {
    let Some(authorization) = authorization else {
        return Outcome::Missing;
    };
    if authorization.len() > HEADER_MAX || crypto_key.is_some_and(|c| c.len() > HEADER_MAX) {
        return Outcome::Bad;
    }
    // 1. The form.
    let Some((token, key)) = token_and_key(authorization, crypto_key) else {
        return Outcome::Missing;
    };
    let Some(key) = key else {
        return Outcome::Missing;
    };
    // 2. The key, compared as decoded bytes.
    match b64(key) {
        Some(bytes) if bytes == registered => {}
        _ => return Outcome::WrongKey,
    }
    // 3. The shape.
    let parts: Vec<&str> = token.split('.').collect();
    let [header, claims, signature] = parts[..] else {
        return Outcome::Bad;
    };
    let (Some(header_bytes), Some(claims_bytes), Some(signature)) =
        (b64(header), b64(claims), b64(signature))
    else {
        return Outcome::Bad;
    };
    let (Ok(header_json), Ok(claims_json)) = (
        serde_json::from_slice::<Value>(&header_bytes),
        serde_json::from_slice::<Value>(&claims_bytes),
    ) else {
        return Outcome::Bad;
    };
    // `kid`, `jku` and `x5u` are ignored and never fetched; `crit` is refused.
    if header_json.get("alg").and_then(Value::as_str) != Some("ES256")
        || header_json.get("crit").is_some()
        || !claims_json.is_object()
        || signature.len() != 64
    {
        return Outcome::Bad;
    }
    // 4. The signature, with the registered key and never the request's own.
    let signing_input = &token[..header.len() + 1 + claims.len()];
    if ring::signature::UnparsedPublicKey::new(
        &ring::signature::ECDSA_P256_SHA256_FIXED,
        registered,
    )
    .verify(signing_input.as_bytes(), &signature)
    .is_err()
    {
        return Outcome::Bad;
    }
    // 5. `exp`: any number, floored.
    let Some(exp) = claims_json
        .get("exp")
        .and_then(Value::as_f64)
        .filter(|e| e.is_finite())
        .map(|e| e.floor() as i64)
    else {
        return Outcome::Time;
    };
    if exp < now - EXP_PAST_SECS || exp > now + EXP_AHEAD_SECS {
        return Outcome::Time;
    }
    // 6. `aud`: a string, or an array holding our address.
    let ours = |aud: &Value| {
        aud.as_str()
            .and_then(origin_of)
            .is_some_and(|origin| origins.contains(&origin))
    };
    let address = match claims_json.get("aud") {
        Some(Value::Array(auds)) => auds.iter().any(ours),
        Some(aud) => ours(aud),
        None => false,
    };
    if !address {
        return Outcome::Address;
    }
    Outcome::Taken
}

#[cfg(test)]
mod tests {
    use super::*;

    const VECTORS: &str = include_str!("../../testdata/unified_push_vapid.json");

    fn vectors() -> Value {
        serde_json::from_str(VECTORS).unwrap()
    }

    /// Every row of `testdata/unified_push_vapid.json` through the pure check, with the file's clock and origins.
    #[test]
    fn the_rows() {
        let file = vectors();
        let now = file["now"].as_i64().unwrap();
        let registered = point(file["registered_key"].as_str().unwrap()).unwrap();
        let mut origins = vec![file["origin"].as_str().unwrap().to_string()];
        for previous in file["previous_origins"].as_array().unwrap() {
            if previous["until"].as_i64().unwrap() > now {
                origins.push(previous["origin"].as_str().unwrap().to_string());
            }
        }
        for row in file["rows"].as_array().unwrap() {
            let name = row["name"].as_str().unwrap();
            let expected = match row["outcome"].as_str().unwrap() {
                "taken" => Outcome::Taken,
                "missing" => Outcome::Missing,
                "wrong_key" => Outcome::WrongKey,
                "bad" => Outcome::Bad,
                "time" => Outcome::Time,
                "address" => Outcome::Address,
                other => panic!("{name}: an outcome {other}"),
            };
            let got = check(
                &registered,
                row["authorization"].as_str(),
                row["crypto_key"].as_str(),
                now,
                &origins,
            );
            assert_eq!(got, expected, "{name}");
        }
    }

    /// The contract's keys are points, a key that isn't one is refused, and a stored non-point reads as no key (L7).
    #[test]
    fn points() {
        let file = vectors();
        for key in ["registered_key", "other_key"] {
            assert!(point(file[key].as_str().unwrap()).is_some(), "{key}");
        }
        let bad = file["not_a_point"].as_str().unwrap();
        assert!(point(bad).is_none());
        assert_eq!(stored_key(bad), Stored::NotAPoint);
        assert_eq!(stored_key(bad), Stored::NotAPoint, "the kept verdict");
        assert!(matches!(
            stored_key(file["registered_key"].as_str().unwrap()),
            Stored::Key(_)
        ));
        assert!(point("short").is_none());
    }
}
