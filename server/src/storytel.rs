//! The server's Storytel client (design `docs/design/26b-storytel.md` §0, §1.1 and §2.1, with its
//! QA review and decisions; design 58 for the phones' session): the family's login (the sealed
//! `music_storytel` row, phase 1), the bookshelf and its grouping into series and standalone books.
//! It reads the **shelf only** - never bookmarks, never audio. The phones mint the audio URLs
//! themselves; from design 58 they ask this server for its JWT (`POST
//! /api/devices/music/storytel/token`, [Account::phone_jwt]) instead of logging in.
//!
//! - **One account** ([Account], `AppState.storytel`): the JWT in a std `Mutex` never held across
//!   an `.await` (design 58 §2.1: a phone never waits behind a shelf read), one login at a time
//!   server-wide (`login_lock`), the last shelf (5 minutes for a routine read) under its own tokio
//!   `Mutex`; all in memory only, each for the login generation it was made with. The JWT's life
//!   comes from its own claims ([jwt_life]), and a 401 logs in again only while the kept session is
//!   the one refused.
//! - **The refused-login cooldown**: a refused login (a 4xx other than 408 and 429,
//!   [classify_login]) is stamped in `music_storytel.login_refused_at` with its generation; for an
//!   hour after it no login is tried for that generation (no request at all), and saving the login
//!   again (a new generation) retries at once.
//! - **The login backoff** (design 58 §2.3): any other failed login (a 5xx, 408, 429, any other
//!   non-200, a transport error, a timeout, no JWT, a too-short life) is an outage: no login for 1
//!   minute, doubling to 30, reset by a login or a new generation. The phones' logins are capped
//!   server-wide too ([Account::phone_jwt]).
//! - **Every request** goes through `AppState.music_fetch` (one in flight server-wide, 200 ms
//!   apart), [Reach::Public] - but the login and the bookshelf under `STORYTEL_BASE` ([Config]).
//! - **Secrets**: the password, the encrypted password (`pwd=`), the e-mail, the JWT, its payload and
//!   its session never reach a log, an error text, an event, a page or `music_state`.
//!   [StoredLogin], [Jwt], [Login], [KeptJwt] and [PhoneJwt] have no `Debug` and no `Display` (QA
//!   L9); errors carry codes and statuses only, and `music_net` drops the URL from every transport
//!   error.
//!
//! UPSTREAM: storytel-player, tag 1.3.0 (github.com/debba/storytel-player, `server/storytelApi.ts`,
//! `server/passwordCrypt.ts`). Every URL, header and body quirk lives in the constants below.
//!
//! Ported in behaviour from palchrb/vibb `pi/vibb/storytel.py` (`_encrypt_password`,
//! `parse_target`, `series_target`, `book_target`, `login`, `_bearer`, `_api`, `bookshelf`,
//! `bookshelf_cached`, `_cover`, `normalize_shelf` and the refused-login cooldown; MIT, Copyright
//! (c) palchrb; itself after storytel-player 1.3.0 by debba, MIT), reimplemented in Rust under
//! GPL-3.0 with 26b's changes (see NOTICE.md and 26b §9): AES in-process instead of `openssl`, the
//! JWT and the shelf in memory only, the cooldown in the database and tied to the login generation,
//! a book without a `model.id` skipped instead of keyed "None", a consumable id seen twice kept once;
//! and design 58's: the JWT's life from its claims (storytel-player 2ca1e2d's rule), an outage
//! backs off instead of waiting vibb's hour.

use std::collections::{HashMap, HashSet, VecDeque};
use std::sync::Arc;
use std::time::Duration;

use chrono::{DateTime, Datelike, TimeDelta, Utc};
use serde::Deserialize;
use serde::de::{self, IgnoredAny, MapAccess, SeqAccess, Visitor};
use serde_json::Value;
use tokio::time::Instant;

use crate::AppState;
use crate::music_net::{FetchError, Kind, PhoneAsks, Reach, SourceRequest};
use crate::music_sweep::{parse_stamp, stamp};

// --- CONSTANTS: everything upstream might move lives here ---------------------------------------

pub const LOGIN_URL: &str = "https://www.storytel.com/api/login.action";
/// `api.storytel.net`: the bookshelf (the phones also call its asset endpoint).
pub const BOOKSHELF: &str = "https://api.storytel.net/libraries/bookshelf";
/// The bookshelf's body: JSON under a *form* content type (a JSON content type is a 400,
/// field-verified by vibb 2026-08-14).
pub const BOOKSHELF_BODY: &[u8] = br#"{"items":[]}"#;
pub const BOOKSHELF_CONTENT_TYPE: &str = "application/x-www-form-urlencoded";
/// passwordCrypt.ts: AES-128-CBC with a public key and IV - `pwd=` is as good as the password.
const AES_KEY: [u8; 16] = *b"VQZBJ6TD8M9WBUWT";
const AES_IV: [u8; 16] = *b"joiwef08u23j341a";
/// A kept JWT is used while more than this is left of it, and a phone is told its life minus this
/// (design 58 §2.1).
pub const MARGIN: Duration = Duration::from_secs(60);
/// A JWT's life when its claims can't be read (storytel-player's 1800 s).
pub const JWT_LIFE_FALLBACK: Duration = Duration::from_secs(30 * 60);
/// The shortest life `exp - iat` gives while the server's wall clock can't be trusted.
pub const JWT_LIFE_FLOOR: Duration = Duration::from_secs(300);
/// The shortest life with a sane wall clock (`exp - now` of a still-valid older token).
pub const JWT_LIFE_SANE_FLOOR: Duration = Duration::from_secs(60);
/// The longest life we use a JWT for, whatever its claims say (ours).
pub const JWT_LIFE_CAP: Duration = Duration::from_secs(24 * 3600);
/// A wall clock before this year isn't trusted for `exp - now` (a Pi without its time yet).
const SANE_YEAR: i32 = 2026;
/// A phone is answered a JWT only with more than `2 x` [MARGIN] left, so `expires_in_s` is at
/// least 61 (design 58 QA N-6); a fresh login leaving less is an outage (`short_life`).
pub const PHONE_FLOOR: Duration = Duration::from_secs(2 * 60 + 1);
/// The phones' token route's budget (design 58 §2.2), under the launcher's 10 s: a login still
/// running then finishes on its own and is kept.
pub const PHONE_JWT_BUDGET: Duration = Duration::from_secs(8);
/// A phone's asks in [PHONE_WINDOW] (design 58 §7.3), then `busy`.
pub const PHONE_JWT_ASKS_PER_WINDOW: usize = 20;
/// A phone's forced logins (after its 401) in [PHONE_WINDOW] (design 58 §2.3).
pub const PHONE_FORCES_PER_WINDOW: usize = 3;
/// The rolling window of the phones' limits and the server-wide phone-login cap.
pub const PHONE_WINDOW: Duration = Duration::from_secs(3600);
/// The login backoff after an outage: 1 minute, doubling to 30 (design 58 §2.3).
pub const LOGIN_BACKOFF_MIN: Duration = Duration::from_secs(60);
pub const LOGIN_BACKOFF_MAX: Duration = Duration::from_secs(30 * 60);
/// The server-wide cap on logins the phones' asks cause: at least this many an hour.
pub const PHONE_LOGINS_MIN: usize = 6;
/// A forced login needs the kept JWT this old at most (`min(5 min, life / 2)`; 10 s under
/// `STORYTEL_BASE`, design 58 §2.6).
const FORCE_MIN_AGE_CAP: Duration = Duration::from_secs(5 * 60);
const FORCE_MIN_AGE_FAKE: Duration = Duration::from_secs(10);
/// A phone gets the kept JWT while it has `min(15 min, life / 2)` left: above the phone's prefetch
/// point, so a prefetch gets a new one (design 58 QA 58-1).
const PHONE_MIN_LEFT_CAP: Duration = Duration::from_secs(15 * 60);
/// No login is tried this long after a refused one (for the same login generation).
pub const LOGIN_COOLDOWN: TimeDelta = TimeDelta::hours(1);
/// A routine read of the shelf is served from memory this long (vibb `RAW_SHELF_TTL_S`).
pub const SHELF_TTL: Duration = Duration::from_secs(300);
/// The save's probe (login + shelf), the wait for the gate included (QA L4): past it the probe
/// counts as "couldn't be reached".
pub const PROBE_BUDGET: Duration = Duration::from_secs(45);
/// The picker's fresh read of the shelf (QA L1): the wait for the session and the gate included,
/// so the page answers within this whatever the sweep is fetching.
pub const READ_BUDGET: Duration = Duration::from_secs(45);

/// A Storytel entry's offline choices (vibb `app.js` 513-535: download-only, so no "none"): value
/// and label. A stored or posted 0 means all ([cache_value]).
pub const CACHE_CHOICES: [(i64, &str); 4] = [
    (-1, "All books"),
    (5, "The first 5"),
    (10, "The first 10"),
    (20, "The first 20"),
];

/// A Storytel entry's `cache`: 0 is all (vibb `library.py` 87-89: download-only, an entry that
/// keeps nothing plays nothing).
pub fn cache_value(cache: i64) -> i64 {
    if cache == 0 { -1 } else { cache }
}

// --- AES for the login password (passwordCrypt.ts encryptPassword) ------------------------------

/// AES-128-CBC with Storytel's public key and IV, PKCS#7, UPPERCASE hex (lower case fails on
/// Storytel's side - vibb's test 1). CBC and the padding by hand over the `aes` block cipher.
pub fn encrypt_password(password: &str) -> String {
    use aes::cipher::{BlockCipherEncrypt, KeyInit};
    let cipher = aes::Aes128::new(&AES_KEY.into());
    let mut data = password.as_bytes().to_vec();
    let pad = 16 - data.len() % 16;
    data.extend(std::iter::repeat_n(pad as u8, pad));
    let mut previous = AES_IV;
    let mut out = String::with_capacity(data.len() * 2);
    for chunk in data.chunks_exact(16) {
        let mut block = aes::Block::default();
        for (i, byte) in block.iter_mut().enumerate() {
            *byte = chunk[i] ^ previous[i];
        }
        cipher.encrypt_block(&mut block);
        previous = block.into();
        out.push_str(&hex::encode_upper(previous));
    }
    out
}

/// `base?m=1&uid=<email>&pwd=<encrypted>`, encoded like Python's `urlencode` (`@` is `%40`).
/// The result is a secret: it never reaches a log or an error text.
pub fn login_url(base: &str, email: &str, password: &str) -> String {
    let query = form_urlencoded::Serializer::new(String::new())
        .append_pair("m", "1")
        .append_pair("uid", email)
        .append_pair("pwd", &encrypt_password(password))
        .finish();
    format!("{base}?{query}")
}

// --- STORYTEL_BASE: the emulator chain (design 58 §2.6) ------------------------------------------

/// Where the login and the bookshelf go. `STORYTEL_BASE` in `.env` (a loopback URL only, its host
/// compared exactly) sends both to the fake Storytel with [Reach::Any], and then only a fake login
/// (an e-mail at `example.invalid` or a name under it) is ever sent.
#[derive(Clone)]
pub struct Config {
    pub login_url: String,
    pub bookshelf: String,
    /// The fake's base while `STORYTEL_BASE` is set.
    pub fake_base: Option<String>,
    /// Why `STORYTEL_BASE` was ignored, for the start's log.
    pub warnings: Vec<String>,
}

impl Default for Config {
    fn default() -> Self {
        Config {
            login_url: LOGIN_URL.to_string(),
            bookshelf: BOOKSHELF.to_string(),
            fake_base: None,
            warnings: Vec::new(),
        }
    }
}

/// `http://<127.0.0.1|[::1]|localhost>:<port>` with nothing else, as Spotify's overrides are read
/// (QA N-12: the host is compared exactly, so `127.0.0.1.evil.example` is no loopback).
fn loopback_base(value: &str) -> Option<String> {
    let url = reqwest::Url::parse(value.trim()).ok()?;
    let host_ok = matches!(
        url.host_str(),
        Some("127.0.0.1") | Some("[::1]") | Some("localhost")
    );
    let clean = url.scheme() == "http"
        && host_ok
        && url.port().is_some()
        && url.username().is_empty()
        && url.password().is_none()
        && url.query().is_none()
        && url.fragment().is_none()
        && url.path() == "/";
    clean.then(|| url.as_str().trim_end_matches('/').to_string())
}

impl Config {
    pub fn from_env() -> Self {
        Config::from_vars(&std::env::vars().collect())
    }

    pub fn from_vars(vars: &HashMap<String, String>) -> Self {
        let Some(value) = vars
            .get("STORYTEL_BASE")
            .map(|v| v.trim())
            .filter(|v| !v.is_empty())
        else {
            return Config::default();
        };
        match loopback_base(value) {
            Some(base) => Config {
                login_url: format!("{base}/api/login.action"),
                bookshelf: format!("{base}/libraries/bookshelf"),
                fake_base: Some(base),
                warnings: Vec::new(),
            },
            None => Config {
                warnings: vec![
                    "STORYTEL_BASE is honoured only as http://127.0.0.1:<port>, http://[::1]:<port> \
                     or http://localhost:<port> (the fake Storytel, design 58 §2.6) - ignored"
                        .to_string(),
                ],
                ..Config::default()
            },
        }
    }

    /// The login's and the bookshelf's reach: [Reach::Any] only with the loopback override.
    pub fn reach(&self) -> Reach {
        if self.fake_base.is_some() {
            Reach::Any
        } else {
            Reach::Public
        }
    }
}

/// An e-mail the fake may get: one at `example.invalid` or a name under it (RFC 2606 names, never a
/// real one), compared on the whole domain.
fn fake_login(email: &str) -> bool {
    email.rsplit_once('@').is_some_and(|(_, domain)| {
        let domain = domain.to_ascii_lowercase();
        domain == "example.invalid" || domain.ends_with(".example.invalid")
    })
}

// --- the JWT's claims (design 58 §2.1, §2.7) ------------------------------------------------------

/// A JWT's second part: URL-safe base64, padded or not (storytel-player decodes it the same way).
const PAYLOAD_BASE64: base64::engine::GeneralPurpose = base64::engine::GeneralPurpose::new(
    &base64::alphabet::URL_SAFE,
    base64::engine::GeneralPurposeConfig::new()
        .with_decode_padding_mode(base64::engine::DecodePaddingMode::Indifferent),
);

/// The JWT's payload as JSON, `None` when there are no dots or the base64 or the JSON is bad. Never
/// logged, stored or kept: read into [jwt_life] and [payload_has_email], then dropped.
pub fn jwt_payload(jwt: &str) -> Option<Value> {
    use base64::Engine;
    let payload = jwt.split('.').nth(1)?;
    let bytes = PAYLOAD_BASE64.decode(payload).ok()?;
    serde_json::from_slice(&bytes).ok()
}

/// How long a JWT is used, from its claims (design 58 §2.1).
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct JwtLife {
    /// `exp - iat` (at most the cap), or the fallback: the token's own life.
    pub life: Duration,
    /// What we use of it from the login on: `min(exp - iat, exp - now)` with a sane wall clock
    /// (at least 60 s), else `exp - iat` (at least 300 s); never past [JWT_LIFE_CAP].
    pub left: Duration,
    /// `exp` and `iat` were read as numbers (else the fallback applied).
    pub claims: bool,
}

fn seconds(value: f64) -> Duration {
    Duration::from_secs_f64(value.clamp(0.0, JWT_LIFE_CAP.as_secs_f64()))
}

/// [JwtLife] from the payload, `wall_now` the server's clock. Pure.
pub fn jwt_life(payload: Option<&Value>, wall_now: DateTime<Utc>) -> JwtLife {
    let claims = payload.and_then(|p| Some((p.get("exp")?.as_f64()?, p.get("iat")?.as_f64()?)));
    let Some((exp, iat)) = claims else {
        return JwtLife {
            life: JWT_LIFE_FALLBACK,
            left: JWT_LIFE_FALLBACK,
            claims: false,
        };
    };
    let span = exp - iat;
    let left = if wall_now.year() >= SANE_YEAR {
        seconds(span.min(exp - wall_now.timestamp() as f64)).max(JWT_LIFE_SANE_FLOOR)
    } else {
        seconds(span).max(JWT_LIFE_FLOOR)
    };
    JwtLife {
        life: seconds(span),
        left: left.min(JWT_LIFE_CAP),
        claims: true,
    }
}

/// Whether the JWT's payload holds an e-mail address (design 58 §2.7, QA N-5): a word for the log
/// and the card, never a value or a claim name.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Presence {
    Yes,
    No,
    Unknown,
}

impl Presence {
    pub fn as_str(self) -> &'static str {
        match self {
            Presence::Yes => "yes",
            Presence::No => "no",
            Presence::Unknown => "unknown",
        }
    }
}

/// `yes` when any string value at any depth equals `email` (case-insensitive) or holds an `@`;
/// `no` when the payload is a JSON object and none does; `unknown` when it couldn't be read.
pub fn payload_has_email(payload: Option<&Value>, email: &str) -> Presence {
    fn holds(value: &Value, email: &str) -> bool {
        match value {
            Value::String(s) => s.contains('@') || s.eq_ignore_ascii_case(email),
            Value::Array(items) => items.iter().any(|v| holds(v, email)),
            Value::Object(map) => map.values().any(|v| holds(v, email)),
            _ => false,
        }
    }
    match payload {
        Some(value) if holds(value, email) => Presence::Yes,
        Some(Value::Object(_)) => Presence::No,
        _ => Presence::Unknown,
    }
}

/// A session string: `<generation>:<16 lowercase hex>` (design 58 §2.2; the phones' `stale`).
pub fn valid_session(session: &str) -> bool {
    session.split_once(':').is_some_and(|(generation, hex)| {
        (1..=18).contains(&generation.len())
            && generation.bytes().all(|b| b.is_ascii_digit())
            && hex.len() == 16
            && hex.bytes().all(|b| matches!(b, b'0'..=b'9' | b'a'..=b'f'))
    })
}

/// A new session for a JWT of `generation`: random per login, so a restart never repeats one (QA
/// 58-5). It names the JWT and nothing else.
fn new_session(generation: i64) -> String {
    use argon2::password_hash::rand_core::{OsRng, RngCore};
    format!("{generation}:{:016x}", OsRng.next_u64())
}

// --- targets (no I/O) ----------------------------------------------------------------------------

#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum TargetKind {
    Series,
    Book,
}

/// A target's or a consumable's id: 1 to 64 of `[A-Za-z0-9_-]` (the shelf never has longer ones;
/// the bound keeps an imported target small, QA L4 of 2b).
fn id_ok(id: &str) -> bool {
    (1..=64).contains(&id.len())
        && id
            .chars()
            .all(|c| c.is_ascii_alphanumeric() || c == '-' || c == '_')
}

/// A consumable id usable as an item key (21b's keys: `[A-Za-z0-9_-]{1,64}`).
pub fn consumable_id_ok(id: &str) -> bool {
    id_ok(id)
}

/// `storytel:(series|book):<id>`, the id `[A-Za-z0-9_-]{1,64}`, nothing else (vibb `TARGET_RE`,
/// bounded).
pub fn parse_target(target: &str) -> Option<(TargetKind, String)> {
    let rest = target.strip_prefix("storytel:")?;
    let (kind, id) = rest.split_once(':')?;
    let kind = match kind {
        "series" => TargetKind::Series,
        "book" => TargetKind::Book,
        _ => return None,
    };
    id_ok(id).then(|| (kind, id.to_string()))
}

pub fn series_target(series_id: &str) -> String {
    format!("storytel:series:{series_id}")
}

pub fn book_target(consumable_id: &str) -> String {
    format!("storytel:book:{consumable_id}")
}

// --- the shelf, grouped (vibb normalize_shelf: ours, upstream has none of this) ------------------

/// One audiobook on the shelf.
#[derive(Clone, Debug, PartialEq)]
pub struct Book {
    /// `model.id` - never the abook format's id, which is another id space (vibb: a Harry Potter
    /// tile played a Swedish adult book).
    pub consumable_id: String,
    pub title: String,
    /// `seriesInfo.orderInSeries` (0 without one).
    pub order: f64,
    pub duration_ms: i64,
    pub cover: Option<String>,
    pub kids: bool,
    /// Storytel's flags, carried as data: every book is tried anyway (a Premium account plays
    /// "locked" ones).
    pub locked: bool,
    pub geo: bool,
}

/// A series, or a standalone book as a one-book group.
#[derive(Clone, Debug, PartialEq)]
pub struct Group {
    pub kind: TargetKind,
    pub series_id: Option<String>,
    pub target: String,
    pub name: String,
    pub kids: bool,
    pub cover: Option<String>,
    /// In reading order: `(order, title)`.
    pub books: Vec<Book>,
}

/// The shelf isn't the JSON vibb reads (`bad_shelf`).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ShelfError;

/// `items` in **document order** (QA: `serde_json::Map` sorts keys in this build, and vibb's
/// groups and each group's cover come in first-seen order). Python's `raw.get("items") or {}`:
/// a falsy value is no items, any other non-object fails.
#[derive(Default)]
struct Items(Vec<(String, Value)>);

struct ItemsVisitor;

impl<'de> Visitor<'de> for ItemsVisitor {
    type Value = Items;

    fn expecting(&self, f: &mut std::fmt::Formatter) -> std::fmt::Result {
        f.write_str("the bookshelf's items object")
    }

    fn visit_map<A: MapAccess<'de>>(self, mut map: A) -> Result<Items, A::Error> {
        let mut items = Vec::new();
        while let Some((key, value)) = map.next_entry::<String, Value>()? {
            items.push((key, value));
        }
        Ok(Items(items))
    }

    fn visit_unit<E: de::Error>(self) -> Result<Items, E> {
        Ok(Items::default())
    }

    fn visit_none<E: de::Error>(self) -> Result<Items, E> {
        Ok(Items::default())
    }

    fn visit_bool<E: de::Error>(self, v: bool) -> Result<Items, E> {
        falsy_or(!v, de::Unexpected::Bool(v))
    }

    fn visit_u64<E: de::Error>(self, v: u64) -> Result<Items, E> {
        falsy_or(v == 0, de::Unexpected::Unsigned(v))
    }

    fn visit_i64<E: de::Error>(self, v: i64) -> Result<Items, E> {
        falsy_or(v == 0, de::Unexpected::Signed(v))
    }

    fn visit_f64<E: de::Error>(self, v: f64) -> Result<Items, E> {
        falsy_or(v == 0.0, de::Unexpected::Float(v))
    }

    fn visit_str<E: de::Error>(self, v: &str) -> Result<Items, E> {
        falsy_or(v.is_empty(), de::Unexpected::Str(v))
    }

    fn visit_seq<A: SeqAccess<'de>>(self, mut seq: A) -> Result<Items, A::Error> {
        match seq.next_element::<IgnoredAny>()? {
            None => Ok(Items::default()),
            Some(_) => Err(de::Error::invalid_type(de::Unexpected::Seq, &self)),
        }
    }
}

fn falsy_or<E: de::Error>(falsy: bool, unexpected: de::Unexpected) -> Result<Items, E> {
    if falsy {
        Ok(Items::default())
    } else {
        Err(E::invalid_type(unexpected, &ItemsVisitor))
    }
}

impl<'de> Deserialize<'de> for Items {
    fn deserialize<D: de::Deserializer<'de>>(deserializer: D) -> Result<Self, D::Error> {
        deserializer.deserialize_any(ItemsVisitor)
    }
}

/// The top-level object; only `items` is read (the last one, as Python's `json.loads` keeps).
struct RawShelf(Items);

struct RawShelfVisitor;

impl<'de> Visitor<'de> for RawShelfVisitor {
    type Value = RawShelf;

    fn expecting(&self, f: &mut std::fmt::Formatter) -> std::fmt::Result {
        f.write_str("the bookshelf object")
    }

    fn visit_map<A: MapAccess<'de>>(self, mut map: A) -> Result<RawShelf, A::Error> {
        let mut items = Items::default();
        while let Some(key) = map.next_key::<String>()? {
            if key == "items" {
                items = map.next_value()?;
            } else {
                map.next_value::<IgnoredAny>()?;
            }
        }
        Ok(RawShelf(items))
    }
}

impl<'de> Deserialize<'de> for RawShelf {
    fn deserialize<D: de::Deserializer<'de>>(deserializer: D) -> Result<Self, D::Error> {
        deserializer.deserialize_map(RawShelfVisitor)
    }
}

/// Python's `bool(value)`.
fn truthy(value: &Value) -> bool {
    match value {
        Value::Null => false,
        Value::Bool(b) => *b,
        Value::Number(n) => n.as_f64().is_some_and(|f| f != 0.0),
        Value::String(s) => !s.is_empty(),
        Value::Array(a) => !a.is_empty(),
        Value::Object(o) => !o.is_empty(),
    }
}

/// `object.get(key)` when the value is truthy (Python's `x.get(key) or ...`).
fn get_truthy<'a>(object: &'a Value, key: &str) -> Option<&'a Value> {
    object.get(key).filter(|v| truthy(v))
}

/// Python's `str(id)` for the ids Storytel sends: a number in decimal, a string as is (QA L1).
/// Never `Value::to_string()`, whose quotes would fail every target. Anything else (a boolean, a
/// list) is no id: Python would key it "True" or fail the shelf.
fn id_text(value: &Value) -> Option<String> {
    match value {
        Value::Number(n) => Some(n.to_string()),
        Value::String(s) => Some(s.clone()),
        _ => None,
    }
}

/// A title or a name as text.
fn text(value: &Value) -> Option<String> {
    match value {
        Value::String(s) => Some(s.clone()),
        Value::Number(n) => Some(n.to_string()),
        _ => None,
    }
}

/// vibb `_cover`: the abook format's cover, else the model's.
fn cover(model: &Value, format: &Value) -> Option<String> {
    let url = |holder: &Value| {
        get_truthy(holder, "cover")
            .and_then(|c| c.get("url"))
            .and_then(Value::as_str)
            .filter(|u| !u.is_empty())
            .map(str::to_string)
    };
    url(format).or_else(|| url(model))
}

/// vibb `normalize_shelf` (`storytel.py` 424-490): the raw bookshelf grouped into series (by
/// `seriesInfo.id`) and standalone books, in first-seen document order, each group's books in
/// reading order. Ebook-only rows are skipped; kids/locked/geo are data, never filters. Pure.
pub fn normalize_shelf(raw: &[u8]) -> Result<Vec<Group>, ShelfError> {
    let RawShelf(Items(items)) = serde_json::from_slice(raw).map_err(|_| ShelfError)?;
    let mut groups: Vec<Group> = Vec::new();
    let mut index: HashMap<(TargetKind, String), usize> = HashMap::new();
    let mut seen: HashSet<String> = HashSet::new();
    for (key, item) in &items {
        // `item.get("model") or item`; a row that isn't an object is skipped (Python would fail
        // the whole shelf).
        let model = get_truthy(item, "model").unwrap_or(item);
        if !model.is_object() {
            tracing::warn!(key = %key, "storytel: a shelf row that isn't an object, skipped");
            continue;
        }
        let Some(format) = model
            .get("formats")
            .and_then(Value::as_array)
            .and_then(|formats| {
                formats
                    .iter()
                    .find(|f| f.get("type").and_then(Value::as_str) == Some("abook"))
            })
        else {
            continue; // an ebook-only shelf entry
        };
        let Some(consumable_id) = model
            .get("id")
            .and_then(id_text)
            .filter(|id| consumable_id_ok(id))
        else {
            // vibb keys it "None" (`str(None)`), a book nobody can play (26b §9).
            tracing::warn!(key = %key, "storytel: a shelf book without a usable id, skipped");
            continue;
        };
        if !seen.insert(consumable_id.clone()) {
            // 21b's dedupe rule: the first one stays (`music_items`' primary key).
            tracing::debug!(id = %consumable_id, "storytel: a book on the shelf twice");
            continue;
        }
        let series = get_truthy(model, "seriesInfo").filter(|s| s.is_object());
        let title = model.get("title").and_then(text).unwrap_or_default();
        let book = Book {
            title: title.clone(),
            order: series
                .and_then(|s| get_truthy(s, "orderInSeries"))
                .and_then(Value::as_f64)
                .unwrap_or(0.0),
            duration_ms: get_truthy(format, "durationInMilliseconds")
                .and_then(|v| v.as_i64().or_else(|| v.as_f64().map(|f| f as i64)))
                .unwrap_or(0),
            cover: cover(model, format),
            kids: model.get("kidsBook").is_some_and(truthy),
            locked: format.get("isLockedContent").is_some_and(truthy),
            geo: format.get("isGeoRestricted").is_some_and(truthy),
            consumable_id,
        };
        // Python's falsy rules decide "no series": absent, null, "" or 0 (QA L1).
        let (kind, group_id) = match series.and_then(|s| get_truthy(s, "id")) {
            Some(sid) => match id_text(sid) {
                Some(sid) => (TargetKind::Series, sid),
                None => {
                    tracing::warn!(id = %book.consumable_id, "storytel: a series id that isn't one, skipped");
                    continue;
                }
            },
            None => (TargetKind::Book, book.consumable_id.clone()),
        };
        let slot = *index.entry((kind, group_id.clone())).or_insert_with(|| {
            groups.push(Group {
                kind,
                series_id: (kind == TargetKind::Series).then(|| group_id.clone()),
                target: match kind {
                    TargetKind::Series => series_target(&group_id),
                    TargetKind::Book => book_target(&group_id),
                },
                name: series
                    .and_then(|s| get_truthy(s, "name"))
                    .and_then(text)
                    .unwrap_or_else(|| title.clone()),
                kids: book.kids,
                cover: book.cover.clone(),
                books: Vec::new(),
            });
            groups.len() - 1
        });
        groups[slot].books.push(book);
    }
    for group in &mut groups {
        group.books.sort_by(|a, b| {
            a.order
                .total_cmp(&b.order)
                .then_with(|| a.title.cmp(&b.title))
        });
        group.kids = group.books.iter().any(|b| b.kids);
    }
    groups.retain(|group| {
        let ok = parse_target(&group.target).is_some();
        if !ok {
            tracing::warn!(
                books = group.books.len(),
                "storytel: a series whose id fails the target grammar, dropped"
            );
        }
        ok
    });
    Ok(groups)
}

// --- errors --------------------------------------------------------------------------------------

/// What went wrong, as a code for the card and the sweep (`login_error` holds the code, never a
/// message) and a text for the picker. Nothing here holds a URL, the login or the JWT.
#[derive(Debug, Clone, PartialEq)]
pub enum StorytelError {
    /// No login stored, or no key on this server (`storytel_off`); also a real login while
    /// `STORYTEL_BASE` is set (design 58 §2.6).
    NotConfigured,
    /// The login was sealed with another key (`storytel_key`): enter it again (QA L4).
    KeyUnusable,
    /// The login was refused with this status (a 4xx other than 408 and 429): the cooldown starts.
    Refused(u16),
    /// Inside the refused-login cooldown: no request was made.
    Cooldown {
        until: DateTime<Utc>,
    },
    /// The login answered this other non-200 status: an outage (design 58 §2.3), the backoff starts.
    LoginStatus(u16),
    /// 200 without `accountInfo.jwt`: an outage.
    NoJwt,
    /// Storytel's JWT would leave a phone less than [PHONE_FLOOR]: an outage (design 58 QA N-6).
    ShortLife,
    /// Inside the login backoff (design 58 §2.3): no request was made.
    Backoff {
        until: DateTime<Utc>,
    },
    /// A phone's ask that can't log in now: past the phone-login cap, or a force that isn't
    /// allowed yet (design 58 §2.3). Ask again after `wait`.
    Busy {
        wait: Duration,
    },
    Fetch(FetchError),
    /// The bookshelf answered this status.
    Status(u16),
    TooBig,
    BadShelf,
    Database(String),
}

impl StorytelError {
    pub fn code(&self) -> String {
        match self {
            StorytelError::NotConfigured => "storytel_off".to_string(),
            StorytelError::KeyUnusable => "storytel_key".to_string(),
            StorytelError::Refused(_) | StorytelError::Cooldown { .. } => {
                "login_refused".to_string()
            }
            StorytelError::LoginStatus(code) | StorytelError::Status(code) => {
                format!("http_{code}")
            }
            StorytelError::NoJwt => "no_jwt".to_string(),
            StorytelError::ShortLife => "short_life".to_string(),
            StorytelError::Backoff { .. } => "login_backoff".to_string(),
            StorytelError::Busy { .. } => "busy".to_string(),
            StorytelError::Fetch(err) => err.code().to_string(),
            StorytelError::TooBig => "too_big".to_string(),
            StorytelError::BadShelf => "bad_shelf".to_string(),
            StorytelError::Database(_) => "database".to_string(),
        }
    }

    /// A failed login that is an outage, not a refusal (design 58 §2.3): it starts the backoff.
    fn is_outage(&self) -> bool {
        matches!(
            self,
            StorytelError::LoginStatus(_)
                | StorytelError::NoJwt
                | StorytelError::ShortLife
                | StorytelError::Fetch(_)
        )
    }

    /// The picker's text, times in `zone` (design 31 M4) and relative to `now`.
    pub fn message(&self, zone: &crate::display_time::Zone, now: DateTime<Utc>) -> String {
        match self {
            StorytelError::NotConfigured => {
                "No Storytel login is saved on this server.".to_string()
            }
            StorytelError::KeyUnusable => {
                "The saved Storytel login was locked with another key (a restored backup or a new \
                 key file) - enter it again."
                    .to_string()
            }
            StorytelError::Refused(_) => {
                "Storytel refused the saved login. Nothing is tried again for an hour - save the \
                 password again to try now."
                    .to_string()
            }
            StorytelError::Cooldown { until } => format!(
                "Storytel refused the saved login. Nothing is tried again before {} - save the \
                 password again to try now.",
                zone.until(*until, now)
            ),
            StorytelError::NoJwt => {
                "Storytel took the login but sent no session - try again later.".to_string()
            }
            StorytelError::ShortLife => {
                "Storytel sent a session that ends at once - try again later.".to_string()
            }
            StorytelError::Backoff { until } => format!(
                "Storytel couldn't be reached - trying again at {}.",
                zone.until(*until, now)
            ),
            StorytelError::Busy { .. } => "Storytel is busy - try again later.".to_string(),
            StorytelError::Fetch(FetchError::Timeout) => {
                "Storytel didn't answer in time - try again later.".to_string()
            }
            StorytelError::Fetch(_) => {
                "Storytel couldn't be reached - try again later.".to_string()
            }
            StorytelError::LoginStatus(code) | StorytelError::Status(code) => {
                format!("Storytel answered {code} - try again later.")
            }
            StorytelError::TooBig => "The Storytel shelf is too big to read.".to_string(),
            StorytelError::BadShelf => "Storytel sent a shelf this server can't read.".to_string(),
            StorytelError::Database(_) => {
                "Couldn't read the Storytel login - check the server log.".to_string()
            }
        }
    }
}

impl From<sqlx::Error> for StorytelError {
    fn from(err: sqlx::Error) -> Self {
        StorytelError::Database(err.to_string())
    }
}

// --- the login and the account -------------------------------------------------------------------

/// The family's login as sealed in `music_storytel` (phase 1). No `Debug`, no `Display` (QA L9).
#[derive(Deserialize)]
pub struct StoredLogin {
    pub email: String,
    pub password: String,
}

/// A Storytel session token: a bearer for the family's account. No `Debug`, no `Display`, never
/// stored or logged (QA L9); cloned out of the account for one use (design 58 §2.1).
#[derive(Clone)]
pub struct Jwt(String);

impl Jwt {
    fn bearer(&self) -> String {
        format!("Bearer {}", self.0)
    }

    /// The token itself, for the phones' answer only (design 58 §2.2).
    pub fn secret(&self) -> &str {
        &self.0
    }
}

/// The stored login, opened, with what the cooldown needs.
pub struct Credentials {
    pub login: StoredLogin,
    pub generation: i64,
    refused_at: Option<String>,
    refused_generation: Option<i64>,
}

impl Credentials {
    /// The end of the refused-login cooldown when it applies to this login now: `0 <= now - at <
    /// 1 h` (whole-second stamps: a retry in the same second is inside it) and refused for this
    /// generation.
    pub fn cooldown_until(&self, now: DateTime<Utc>) -> Option<DateTime<Utc>> {
        cooldown_until(
            self.refused_at.as_deref(),
            self.refused_generation,
            self.generation,
            now,
        )
    }
}

/// See [Credentials::cooldown_until] (the card reads the row itself).
pub fn cooldown_until(
    refused_at: Option<&str>,
    refused_generation: Option<i64>,
    generation: i64,
    now: DateTime<Utc>,
) -> Option<DateTime<Utc>> {
    let at = parse_stamp(refused_at?)?;
    let age = now - at;
    (refused_generation == Some(generation) && age >= TimeDelta::zero() && age < LOGIN_COOLDOWN)
        .then_some(at + LOGIN_COOLDOWN)
}

/// The `music_storytel` row opened with the server's key. `NotConfigured` without a login or a
/// key, `KeyUnusable` (stored as the card's `storytel_key`) when it was sealed with another key.
pub async fn credentials(state: &AppState) -> Result<Credentials, StorytelError> {
    open_login(state, true).await
}

/// [credentials]; `report` = warn and record `storytel_key` when the key can't open the row (a
/// phone's ask does that once per generation, design 58 §2.2).
async fn open_login(state: &AppState, report: bool) -> Result<Credentials, StorytelError> {
    type Row = (
        Option<Vec<u8>>,
        Option<Vec<u8>>,
        Option<String>,
        i64,
        Option<String>,
        Option<i64>,
    );
    let (ciphertext, nonce, fingerprint, generation, refused_at, refused_generation): Row =
        sqlx::query_as(
            "SELECT ciphertext, nonce, key_fingerprint, generation, login_refused_at, \
             login_refused_generation FROM music_storytel WHERE id = 1",
        )
        .fetch_one(&state.db)
        .await?;
    let (Some(ciphertext), Some(nonce), Some(fingerprint)) = (ciphertext, nonce, fingerprint)
    else {
        return Err(StorytelError::NotConfigured);
    };
    let Some(key) = state.music_key.as_ref() else {
        return Err(StorytelError::NotConfigured);
    };
    let Some(login) = key
        .open(&ciphertext, &nonce, &fingerprint)
        .and_then(|plain| serde_json::from_slice::<StoredLogin>(&plain).ok())
    else {
        if report {
            tracing::warn!("storytel: the saved login can't be opened with this server's key");
            record(state, generation, Outcome::Failed("storytel_key")).await;
        }
        return Err(StorytelError::KeyUnusable);
    };
    Ok(Credentials {
        login,
        generation,
        refused_at,
        refused_generation,
    })
}

/// The row's plain columns (design 58 QA N-8): what a phone's ask needs before any login - never
/// an open of the sealed login.
struct Plain {
    generation: i64,
    refused_at: Option<String>,
    refused_generation: Option<i64>,
}

impl Plain {
    async fn read(state: &AppState) -> Result<Plain, StorytelError> {
        let (stored, generation, refused_at, refused_generation): (
            bool,
            i64,
            Option<String>,
            Option<i64>,
        ) = sqlx::query_as(
            "SELECT ciphertext IS NOT NULL, generation, login_refused_at, \
             login_refused_generation FROM music_storytel WHERE id = 1",
        )
        .fetch_one(&state.db)
        .await?;
        if !stored {
            return Err(StorytelError::NotConfigured);
        }
        Ok(Plain {
            generation,
            refused_at,
            refused_generation,
        })
    }

    fn cooldown_until(&self, now: DateTime<Utc>) -> Option<DateTime<Utc>> {
        cooldown_until(
            self.refused_at.as_deref(),
            self.refused_generation,
            self.generation,
            now,
        )
    }
}

/// What an attempt did, for the row (only ever for the generation it was made with).
enum Outcome<'a> {
    LoginOk,
    Refused,
    Failed(&'a str),
    ShelfOk,
}

async fn record(state: &AppState, generation: i64, outcome: Outcome<'_>) {
    let now = stamp(state.music_sweep.now());
    let query = match outcome {
        Outcome::LoginOk => sqlx::query(
            "UPDATE music_storytel SET login_ok_at = ?, login_error = NULL, login_error_at = NULL \
             WHERE id = 1 AND generation = ?",
        )
        .bind(now),
        Outcome::Refused => sqlx::query(
            "UPDATE music_storytel SET login_refused_at = ?1, login_refused_generation = generation, \
             login_error = 'login_refused', login_error_at = ?1 WHERE id = 1 AND generation = ?2",
        )
        .bind(now),
        Outcome::Failed(code) => sqlx::query(
            "UPDATE music_storytel SET login_error = ?, login_error_at = ? \
             WHERE id = 1 AND generation = ?",
        )
        .bind(code.to_string())
        .bind(now),
        Outcome::ShelfOk => sqlx::query(
            "UPDATE music_storytel SET login_error = NULL, login_error_at = NULL \
             WHERE id = 1 AND generation = ? AND login_error IS NOT NULL",
        ),
    };
    if let Err(err) = query.bind(generation).execute(&state.db).await {
        tracing::error!(%err, "storytel: couldn't record the login's outcome");
    }
}

/// The two facts of a login the card shows and the log line prints (design 58 §2.7): numbers and
/// words only, never the payload.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct LoginFacts {
    pub life_s: u64,
    pub left_s: u64,
    pub claims: bool,
    pub has_email: Presence,
}

/// A login Storytel took: the JWT, how long it is used from now ([jwt_life]) and its facts. No
/// `Debug`, no `Display`.
pub struct Login {
    jwt: Jwt,
    life: Duration,
    facts: LoginFacts,
}

/// The login's answer (design 58 §2.3, QA N-4): 200 with a non-empty `accountInfo.jwt` is the JWT;
/// a 4xx other than 408 and 429 is a refusal ([StorytelError::Refused]); any other status is an
/// outage ([StorytelError::LoginStatus]), and 200 without a JWT too ([StorytelError::NoJwt]).
pub fn classify_login(status: u16, body: &[u8]) -> Result<String, StorytelError> {
    match status {
        200 => serde_json::from_slice::<Value>(body)
            .ok()
            .and_then(|body| {
                body.get("accountInfo")?
                    .get("jwt")?
                    .as_str()
                    .filter(|jwt| !jwt.is_empty())
                    .map(str::to_string)
            })
            .ok_or_else(|| {
                tracing::warn!("storytel: login answered 200 without a session");
                StorytelError::NoJwt
            }),
        400..=499 if status != 408 && status != 429 => {
            tracing::info!(status, "storytel: login refused");
            Err(StorytelError::Refused(status))
        }
        _ => {
            tracing::warn!(
                status,
                "storytel: the login failed (an outage, not a refusal)"
            );
            Err(StorytelError::LoginStatus(status))
        }
    }
}

/// The login request alone (vibb `login` minus the stored login and the cooldown): GET
/// `login.action?m=1&uid=..&pwd=..`, redirects followed, read by [classify_login]; the JWT's life
/// from its claims and one `info` line with the facts (design 58 §2.7). Touches no database: the
/// save's probe uses it with the login being entered. Under `STORYTEL_BASE` only a fake login is
/// sent (§2.6).
pub async fn login_with(
    state: &AppState,
    email: &str,
    password: &str,
) -> Result<Login, StorytelError> {
    let config = state.storytel.config();
    if config.fake_base.is_some() && !fake_login(email) {
        tracing::warn!(
            "storytel: STORYTEL_BASE is set: the stored login isn't a fake one, not sent"
        );
        return Err(StorytelError::NotConfigured);
    }
    let request = SourceRequest::new(
        login_url(&config.login_url, email, password),
        Kind::Login,
        config.reach(),
    );
    let response = state
        .music_fetch
        .get(request)
        .await
        .map_err(StorytelError::Fetch)?;
    let jwt = classify_login(response.status, &response.body)?;
    let payload = jwt_payload(&jwt);
    let life = jwt_life(payload.as_ref(), state.music_sweep.now());
    let facts = LoginFacts {
        life_s: life.life.as_secs(),
        left_s: life.left.as_secs(),
        claims: life.claims,
        has_email: payload_has_email(payload.as_ref(), email),
    };
    drop(payload);
    if life.claims && life.left + Duration::from_secs(300) < life.life {
        // A fresh session that is already old by the server's clock: the clock is probably ahead
        // (design 58 code QA nit 6). Numbers only.
        tracing::warn!(
            life_s = facts.life_s,
            left_at_login_s = facts.left_s,
            "storytel: the session looks older than a fresh one - is the server's clock ahead?"
        );
    }
    tracing::info!(
        "storytel: logged in (life_s={}, left_at_login_s={}, claims={}, payload_has_email={})",
        facts.life_s,
        facts.left_s,
        if facts.claims { "yes" } else { "no" },
        facts.has_email.as_str()
    );
    Ok(Login {
        jwt: Jwt(jwt),
        life: life.left,
        facts,
    })
}

fn shelf_request(config: &Config, jwt: &Jwt) -> SourceRequest {
    use reqwest::header::{ACCEPT, AUTHORIZATION, CONTENT_TYPE};
    let mut request = SourceRequest::new(config.bookshelf.clone(), Kind::Shelf, config.reach());
    request.method = reqwest::Method::POST;
    request.headers = vec![
        (AUTHORIZATION, jwt.bearer()),
        (ACCEPT, "*/*".to_string()),
        (CONTENT_TYPE, BOOKSHELF_CONTENT_TYPE.to_string()),
    ];
    request.body = Some(BOOKSHELF_BODY.to_vec());
    request
}

/// The shelf's answer read: not 200 = `Status`, past the cap = `TooBig`, then grouped.
fn read_shelf(response: crate::music_net::SourceResponse) -> Result<Vec<Group>, StorytelError> {
    if response.status != 200 {
        return Err(StorytelError::Status(response.status));
    }
    if response.truncated {
        return Err(StorytelError::TooBig);
    }
    normalize_shelf(&response.body).map_err(|_| StorytelError::BadShelf)
}

/// What the save's probe found (§2.3).
pub enum Probe {
    /// Storytel took the login and sent the shelf: both are kept for the new generation.
    Accepted { login: Login, shelf: Vec<Group> },
    /// Not 200 (`status`), or 200 without a JWT (`None`): nothing is stored. `outage` = Storytel's
    /// trouble, not a wrong password (design 58 §2.3's split; the wording follows it).
    Refused { status: Option<u16>, outage: bool },
    /// A transport error, the shelf failing, or past the budget: stored anyway, with this code.
    Unreachable { code: String, login: Option<Login> },
}

/// The save's probe (vibb `/storytel/credentials`): one login with the entered login and one
/// bookshelf, within `budget` in all (the wait for the gate included). No cooldown or backoff
/// applies, and none is stamped (design 58 §2.3: the save is the parent's own act).
pub async fn probe(state: &AppState, email: &str, password: &str, budget: Duration) -> Probe {
    let attempt = async {
        let login = match login_with(state, email, password).await {
            Ok(login) => login,
            Err(StorytelError::Refused(status)) => {
                return Probe::Refused {
                    status: Some(status),
                    outage: false,
                };
            }
            Err(StorytelError::LoginStatus(status)) => {
                return Probe::Refused {
                    status: Some(status),
                    outage: true,
                };
            }
            Err(StorytelError::NoJwt) => {
                return Probe::Refused {
                    status: None,
                    outage: true,
                };
            }
            Err(err) => {
                return Probe::Unreachable {
                    code: err.code(),
                    login: None,
                };
            }
        };
        let shelf = match state
            .music_fetch
            .get(shelf_request(state.storytel.config(), &login.jwt))
            .await
        {
            Ok(response) => read_shelf(response),
            Err(err) => Err(StorytelError::Fetch(err)),
        };
        match shelf {
            Ok(shelf) => Probe::Accepted { login, shelf },
            Err(err) => {
                tracing::warn!(code = %err.code(), "storytel: the probe's bookshelf failed");
                Probe::Unreachable {
                    code: err.code(),
                    login: Some(login),
                }
            }
        }
    };
    match tokio::time::timeout(budget, attempt).await {
        Ok(probe) => probe,
        Err(_) => {
            tracing::warn!("storytel: the probe took too long");
            Probe::Unreachable {
                code: "timeout".to_string(),
                login: None,
            }
        }
    }
}

/// The JWT the account keeps (design 58 §2.1). No `Debug`, no `Display`.
pub struct KeptJwt {
    jwt: Jwt,
    made: Instant,
    life: Duration,
    generation: i64,
    session: String,
}

impl KeptJwt {
    fn left(&self, now: Instant) -> Duration {
        (self.made + self.life).saturating_duration_since(now)
    }

    fn handed(&self, now: Instant) -> PhoneJwt {
        PhoneJwt {
            jwt: self.jwt.clone(),
            left: self.left(now),
            session: self.session.clone(),
        }
    }
}

/// A JWT handed out of the account: to a phone (design 58 §2.2) or to the sweep. No `Debug`, no
/// `Display`.
pub struct PhoneJwt {
    pub jwt: Jwt,
    /// What is left of it (the phone is told this minus [MARGIN]).
    pub left: Duration,
    /// `"<generation>:<16 hex>"`: names this JWT, nothing else.
    pub session: String,
}

/// `min(cap, life / 2)`.
fn half_life(life: Duration, cap: Duration) -> Duration {
    (life / 2).min(cap)
}

struct KeptShelf {
    groups: Arc<Vec<Group>>,
    at: Instant,
    generation: i64,
}

#[derive(Default)]
struct Session {
    shelf: Option<KeptShelf>,
    /// Requests sent through this session (logins and shelves): the sweep's check counts what one
    /// read cost ([Account::shelf_counted]).
    sent: u32,
}

/// The login backoff after an outage (design 58 §2.3), for one generation.
struct Backoff {
    generation: i64,
    until: Instant,
    step: Duration,
}

/// What bounds the logins (design 58 §2.3) and what the card knows of the last one (§2.7).
#[derive(Default)]
struct Gate {
    backoff: Option<Backoff>,
    /// Logins the phones' asks caused, server-wide, in the last [PHONE_WINDOW].
    phone_logins: VecDeque<Instant>,
    /// The last login's facts, in memory only ("not known yet" after a restart).
    last: Option<LoginFacts>,
    /// The generation a phone's ask recorded `storytel_key` for (at most once each, QA 58-9).
    key_reported: Option<i64>,
}

/// `AppState.storytel`: the server's one Storytel session.
pub struct Account {
    config: Config,
    /// The shelf (the shelf read holds it across its fetch; no phone needs it).
    session: tokio::sync::Mutex<Session>,
    /// The kept JWT: never held across an `.await` (design 58 §2.1).
    jwt: std::sync::Mutex<Option<KeptJwt>>,
    /// One login at a time server-wide. Nothing takes `session` while holding it.
    login_lock: tokio::sync::Mutex<()>,
    gate: std::sync::Mutex<Gate>,
    /// Each phone's token asks ([PHONE_JWT_ASKS_PER_WINDOW]) and forced logins
    /// ([PHONE_FORCES_PER_WINDOW]).
    asks: PhoneAsks,
    forces: PhoneAsks,
    /// The token route's budget ([PHONE_JWT_BUDGET]; the tests shorten it).
    phone_budget: std::sync::Mutex<Duration>,
    /// The tests move the JWT's and the shelf's clock forward.
    skew: std::sync::Mutex<Duration>,
}

impl Default for Account {
    fn default() -> Self {
        Account::new(Config::default())
    }
}

fn lock<T>(mutex: &std::sync::Mutex<T>) -> std::sync::MutexGuard<'_, T> {
    mutex.lock().unwrap_or_else(|e| e.into_inner())
}

/// Whole seconds, rounded up, at least 1 (a wait a phone honours).
pub fn wait_secs(wait: Duration) -> u64 {
    (wait.as_secs() + u64::from(wait.subsec_nanos() > 0)).max(1)
}

impl Account {
    pub fn new(config: Config) -> Self {
        Account {
            config,
            session: Default::default(),
            jwt: Default::default(),
            login_lock: Default::default(),
            gate: Default::default(),
            asks: PhoneAsks::new(PHONE_JWT_ASKS_PER_WINDOW, PHONE_WINDOW),
            forces: PhoneAsks::new(PHONE_FORCES_PER_WINDOW, PHONE_WINDOW),
            phone_budget: std::sync::Mutex::new(PHONE_JWT_BUDGET),
            skew: Default::default(),
        }
    }

    pub fn config(&self) -> &Config {
        &self.config
    }

    fn now(&self) -> Instant {
        Instant::now() + *lock(&self.skew)
    }

    /// Moves this account's clock (the JWT's expiry, the shelf's age, the backoff and the limits)
    /// forward.
    #[cfg(test)]
    pub fn advance(&self, by: Duration) {
        *lock(&self.skew) += by;
    }

    /// The token route's budget for one ask ([PHONE_JWT_BUDGET]).
    pub fn phone_budget(&self) -> Duration {
        *lock(&self.phone_budget)
    }

    /// A shorter token route budget (the tests).
    #[cfg(test)]
    pub fn set_phone_budget(&self, budget: Duration) {
        *lock(&self.phone_budget) = budget;
    }

    /// One token ask of `device` (design 58 §7.3): `Ok` within [PHONE_JWT_ASKS_PER_WINDOW] asks a
    /// rolling hour, else `Err(seconds)` to wait. A refused ask isn't counted.
    pub fn phone_ask(&self, device: i64) -> Result<(), u64> {
        self.asks.ask(device, self.now())
    }

    /// The last login's facts (design 58 §2.7), `None` until a login since the start.
    pub fn facts(&self) -> Option<LoginFacts> {
        lock(&self.gate).last
    }

    /// `FORCE_MIN_AGE` for a JWT of `life`: `min(5 min, life / 2)`, 10 s under `STORYTEL_BASE`.
    fn force_min_age(&self, life: Duration) -> Duration {
        if self.config.fake_base.is_some() {
            FORCE_MIN_AGE_FAKE
        } else {
            half_life(life, FORCE_MIN_AGE_CAP)
        }
    }

    /// The kept JWT handed out when `usable` says so.
    fn kept_if(&self, usable: impl FnOnce(&KeptJwt, Instant) -> bool) -> Option<PhoneJwt> {
        let now = self.now();
        let kept = lock(&self.jwt);
        kept.as_ref()
            .filter(|kept| usable(kept, now))
            .map(|kept| kept.handed(now))
    }

    /// The backoff's wait for `generation` now, if it runs.
    fn backoff_wait(gate: &Gate, generation: i64, now: Instant) -> Option<Duration> {
        gate.backoff
            .as_ref()
            .filter(|b| b.generation == generation && now < b.until)
            .map(|b| b.until - now)
    }

    /// The phone-login cap's wait now, if it is reached: `max(6, 2 x ceil(3600 / life))` a rolling
    /// hour, the life the last login's (30 minutes before the first).
    fn cap_wait(gate: &mut Gate, now: Instant) -> Option<Duration> {
        while gate
            .phone_logins
            .front()
            .is_some_and(|at| now.saturating_duration_since(*at) >= PHONE_WINDOW)
        {
            gate.phone_logins.pop_front();
        }
        let life = gate
            .last
            .map_or(JWT_LIFE_FALLBACK.as_secs(), |facts| facts.left_s)
            .max(PHONE_FLOOR.as_secs());
        let bound = PHONE_LOGINS_MIN.max(2 * PHONE_WINDOW.as_secs().div_ceil(life) as usize);
        (gate.phone_logins.len() >= bound).then(|| {
            let oldest = gate.phone_logins.front().copied().unwrap_or(now);
            PHONE_WINDOW.saturating_sub(now.saturating_duration_since(oldest))
        })
    }

    /// vibb `login` under `login_lock` (the caller holds it): the cooldown and the backoff first
    /// (no request inside either), then [login_with] with the stored login, the outcome stamped for
    /// this generation. A login is kept; a refusal drops the kept JWT and stamps the cooldown; an
    /// outage starts the backoff and keeps the old JWT for the others (design 58 QA 58-7).
    async fn login(
        &self,
        state: &AppState,
        credentials: &Credentials,
        sent: &mut u32,
    ) -> Result<PhoneJwt, StorytelError> {
        let generation = credentials.generation;
        let wall = state.music_sweep.now();
        if let Some(until) = credentials.cooldown_until(wall) {
            tracing::info!(until = %until.format("%H:%M UTC"), "storytel: login waits (refused earlier)");
            return Err(StorytelError::Cooldown { until });
        }
        if let Some(wait) = Self::backoff_wait(&lock(&self.gate), generation, self.now()) {
            let until = wall + TimeDelta::seconds(wait_secs(wait) as i64);
            tracing::info!(until = %until.format("%H:%M UTC"), "storytel: login waits (Storytel couldn't be reached)");
            return Err(StorytelError::Backoff { until });
        }
        *sent += 1;
        let result = login_with(state, &credentials.login.email, &credentials.login.password)
            .await
            .and_then(|login| {
                if login.life < PHONE_FLOOR {
                    tracing::warn!(
                        left_s = login.life.as_secs(),
                        "storytel: the session Storytel sent ends at once"
                    );
                    Err(StorytelError::ShortLife)
                } else {
                    Ok(login)
                }
            });
        let now = self.now();
        match result {
            Ok(login) => {
                record(state, generation, Outcome::LoginOk).await;
                let kept = KeptJwt {
                    jwt: login.jwt,
                    made: now,
                    life: login.life,
                    generation,
                    session: new_session(generation),
                };
                let handed = kept.handed(now);
                // A save may have kept a newer generation's JWT while this login ran (design 58
                // code QA L2): this one is then not kept, and its facts don't replace the save's.
                let stored = {
                    let mut jwt = lock(&self.jwt);
                    let newer = jwt.as_ref().is_some_and(|k| k.generation > generation);
                    if !newer {
                        *jwt = Some(kept);
                    }
                    !newer
                };
                {
                    let mut gate = lock(&self.gate);
                    gate.backoff = None;
                    if stored {
                        gate.last = Some(login.facts);
                    }
                }
                Ok(handed)
            }
            Err(err) => {
                if matches!(err, StorytelError::Refused(_)) {
                    {
                        let mut jwt = lock(&self.jwt);
                        if jwt.as_ref().is_some_and(|k| k.generation <= generation) {
                            jwt.take();
                        }
                    }
                    record(state, generation, Outcome::Refused).await;
                } else if err.is_outage() {
                    record(state, generation, Outcome::Failed(&err.code())).await;
                    let mut gate = lock(&self.gate);
                    let step = match &gate.backoff {
                        Some(b) if b.generation == generation => {
                            (b.step * 2).min(LOGIN_BACKOFF_MAX)
                        }
                        _ => LOGIN_BACKOFF_MIN,
                    };
                    gate.backoff = Some(Backoff {
                        generation,
                        until: now + step,
                        step,
                    });
                    tracing::info!(code = %err.code(), backoff_s = step.as_secs(), "storytel: no login for a while");
                }
                Err(err)
            }
        }
    }

    /// vibb `_bearer` for the sweep: the kept JWT while it is of this generation with more than
    /// [MARGIN] left - and, after a 401 (`rejected`, the session refused), only when it is another
    /// one (design 58 QA 58-7: a concurrent re-login is used as it is) - else one login, its request
    /// counted in `sent`.
    async fn bearer(
        &self,
        state: &AppState,
        credentials: &Credentials,
        rejected: Option<&str>,
        sent: &mut u32,
    ) -> Result<PhoneJwt, StorytelError> {
        let usable = |kept: &KeptJwt, now: Instant| {
            kept.generation == credentials.generation
                && kept.left(now) > MARGIN
                && rejected.is_none_or(|session| kept.session != session)
        };
        if let Some(jwt) = self.kept_if(usable) {
            return Ok(jwt);
        }
        let _one = self.login_lock.lock().await;
        if let Some(jwt) = self.kept_if(usable) {
            return Ok(jwt);
        }
        self.login(state, credentials, sent).await
    }

    /// A phone's JWT (design 58 §2.1, §2.3), `stale` the session it got a 401 with. In order:
    /// 1. the row's plain columns (never an open of the login, QA N-8);
    /// 2. the kept JWT of this generation with at least `min(15 min, life / 2)` left, unless this
    ///    ask forces (its `stale` is the kept session);
    /// 3. no login now (the cooldown, the backoff, the phone-login cap; for a force also the kept
    ///    JWT younger than `FORCE_MIN_AGE` or this phone's 3 forces an hour): a plain ask still
    ///    gets a kept JWT with more than 2 minutes left (QA N-9), else `Cooldown` or `Busy` with
    ///    the wait; a declined force never gets the JWT it names (QA N-1);
    /// 4. one login, counted in the phone-login cap (and as this phone's force when it forces).
    ///
    /// The route runs it as a task of its own, so a login outliving the route's budget is kept.
    pub async fn phone_jwt(
        self: Arc<Self>,
        state: AppState,
        device: i64,
        stale: Option<String>,
    ) -> Result<PhoneJwt, StorytelError> {
        let stale = stale.as_deref();
        let plain = Plain::read(&state).await?;
        let fresh = |generation: i64| {
            move |kept: &KeptJwt, now: Instant| {
                kept.generation == generation
                    && stale != Some(kept.session.as_str())
                    && kept.left(now) >= half_life(kept.life, PHONE_MIN_LEFT_CAP).max(PHONE_FLOOR)
            }
        };
        if let Some(jwt) = self.kept_if(fresh(plain.generation)) {
            return Ok(jwt);
        }
        // A login in flight (it may wait in the fetch gate behind a feed for minutes): a plain ask
        // gets the kept JWT while it still serves, as when no login can be made (design 58 code QA
        // L1). A force waits for the lock and its own re-check.
        let _one = match self.login_lock.try_lock() {
            Ok(one) => one,
            Err(_) => {
                let forcing = stale.is_some_and(|stale| {
                    lock(&self.jwt)
                        .as_ref()
                        .is_some_and(|k| k.generation == plain.generation && k.session == stale)
                });
                let serving = (!forcing)
                    .then(|| {
                        self.kept_if(|kept, now| {
                            kept.generation == plain.generation && kept.left(now) >= PHONE_FLOOR
                        })
                    })
                    .flatten();
                if let Some(jwt) = serving {
                    return Ok(jwt);
                }
                self.login_lock.lock().await
            }
        };
        // Again under the lock: a login or a refusal may have landed while this ask waited.
        let plain = Plain::read(&state).await?;
        if let Some(jwt) = self.kept_if(fresh(plain.generation)) {
            return Ok(jwt);
        }
        let now = self.now();
        let wall = state.music_sweep.now();
        let (force, life, age) = match lock(&self.jwt).as_ref() {
            Some(kept) if kept.generation == plain.generation => (
                stale == Some(kept.session.as_str()),
                kept.life,
                now.saturating_duration_since(kept.made),
            ),
            _ => (false, Duration::ZERO, Duration::ZERO),
        };
        // A plain ask while no login can be made: the kept JWT while it still serves (QA N-9).
        let kept_on = || {
            (!force)
                .then(|| {
                    self.kept_if(|kept, now| {
                        kept.generation == plain.generation && kept.left(now) >= PHONE_FLOOR
                    })
                })
                .flatten()
        };
        if let Some(until) = plain.cooldown_until(wall) {
            return kept_on().ok_or(StorytelError::Cooldown { until });
        }
        let wait = {
            let mut gate = lock(&self.gate);
            let mut wait = Self::backoff_wait(&gate, plain.generation, now)
                .into_iter()
                .chain(Self::cap_wait(&mut gate, now))
                .max();
            if force {
                let young = self
                    .force_min_age(life)
                    .checked_sub(age)
                    .filter(|left| !left.is_zero());
                let spent = self.forces.wait(device, now).map(Duration::from_secs);
                wait = wait.into_iter().chain(young).chain(spent).max();
            }
            wait
        };
        if let Some(wait) = wait {
            return kept_on().ok_or(StorytelError::Busy {
                wait: wait.max(Duration::from_secs(1)),
            });
        }
        let first_report = lock(&self.gate).key_reported != Some(plain.generation);
        let credentials = match open_login(&state, first_report).await {
            Err(StorytelError::KeyUnusable) => {
                lock(&self.gate).key_reported = Some(plain.generation);
                return Err(StorytelError::KeyUnusable);
            }
            other => other?,
        };
        // Under STORYTEL_BASE a login that isn't a fake one is never sent: refused here, before it
        // counts in the cap (design 58 code QA L5); `login_with` refuses it again.
        if self.config.fake_base.is_some() && !fake_login(&credentials.login.email) {
            tracing::warn!(
                "storytel: STORYTEL_BASE is set: the stored login isn't a fake one, not sent"
            );
            return Err(StorytelError::NotConfigured);
        }
        lock(&self.gate).phone_logins.push_back(now);
        if force {
            self.forces.record(device, now);
        }
        self.login(&state, &credentials, &mut 0).await
    }

    /// vibb `bookshelf` through `_api`: the bearer, one re-login and one retry on a 401 while the
    /// refused session is still the kept one, never two.
    async fn fetch_shelf(
        &self,
        state: &AppState,
        session: &mut Session,
        credentials: &Credentials,
    ) -> Result<Vec<Group>, StorytelError> {
        let mut response = None;
        let mut rejected: Option<String> = None;
        for attempt in 0..2 {
            let jwt = self
                .bearer(state, credentials, rejected.as_deref(), &mut session.sent)
                .await?;
            let request = shelf_request(&self.config, &jwt.jwt);
            session.sent += 1;
            let answer = state
                .music_fetch
                .get(request)
                .await
                .map_err(StorytelError::Fetch);
            let answer = match answer {
                Ok(answer) => answer,
                Err(err) => {
                    record(state, credentials.generation, Outcome::Failed(&err.code())).await;
                    return Err(err);
                }
            };
            let unauthorized = answer.status == 401;
            response = Some(answer);
            if !unauthorized {
                break;
            }
            tracing::info!(attempt, "storytel: bookshelf 401, logging in again");
            rejected = Some(jwt.session);
        }
        let response = response.expect("at least one attempt");
        match read_shelf(response) {
            Ok(groups) => {
                record(state, credentials.generation, Outcome::ShelfOk).await;
                Ok(groups)
            }
            Err(err) => {
                tracing::warn!(code = %err.code(), "storytel: bookshelf failed");
                record(state, credentials.generation, Outcome::Failed(&err.code())).await;
                Err(err)
            }
        }
    }

    /// A fresh bookshelf (the picker): fetched now, kept as the last good shelf.
    /// The picker's fresh read: [Account::bookshelf_within] [READ_BUDGET].
    pub async fn bookshelf(&self, state: &AppState) -> Result<Arc<Vec<Group>>, StorytelError> {
        self.bookshelf_within(state, READ_BUDGET).await
    }

    /// A fresh bookshelf within `budget` (the session's lock and the gate's wait included); past
    /// it a `timeout` ("Storytel didn't answer in time").
    pub async fn bookshelf_within(
        &self,
        state: &AppState,
        budget: Duration,
    ) -> Result<Arc<Vec<Group>>, StorytelError> {
        match tokio::time::timeout(budget, self.read_fresh(state)).await {
            Ok(result) => result,
            Err(_) => {
                tracing::warn!("storytel: the shelf took too long");
                Err(StorytelError::Fetch(FetchError::Timeout))
            }
        }
    }

    async fn read_fresh(&self, state: &AppState) -> Result<Arc<Vec<Group>>, StorytelError> {
        let mut session = self.session.lock().await;
        let credentials = credentials(state).await?;
        let groups = Arc::new(self.fetch_shelf(state, &mut session, &credentials).await?);
        session.shelf = Some(KeptShelf {
            groups: groups.clone(),
            at: self.now(),
            generation: credentials.generation,
        });
        Ok(groups)
    }

    /// vibb `bookshelf_cached`: the kept shelf while younger than `max_age` ([SHELF_TTL] for a
    /// routine read, zero for Check now), else a fetch; a failed fetch falls back to the last good
    /// shelf of this login whatever its age ("stale beats dead"), else the error.
    pub async fn shelf(
        &self,
        state: &AppState,
        max_age: Duration,
    ) -> Result<Arc<Vec<Group>>, StorytelError> {
        let mut session = self.session.lock().await;
        self.read_shelf(state, &mut session, max_age, true).await
    }

    /// The sweep's read (design 26b §2.4): the kept shelf while younger than `max_age`, else a
    /// fetch - and a failed fetch is the error, **never** the last good shelf (the sweep keeps its
    /// own last good list, and the card must say why the check failed: QA M1 of 2b). With how many
    /// requests it sent, which the check counts against its budget.
    pub async fn shelf_counted(
        &self,
        state: &AppState,
        max_age: Duration,
    ) -> (Result<Arc<Vec<Group>>, StorytelError>, u32) {
        let mut session = self.session.lock().await;
        let before = session.sent;
        let result = self.read_shelf(state, &mut session, max_age, false).await;
        (result, session.sent.wrapping_sub(before))
    }

    /// `stale_ok`: a failed fetch falls back to the last good shelf (the picker's Add, vibb
    /// `bookshelf_cached`); the sweep passes `false`.
    async fn read_shelf(
        &self,
        state: &AppState,
        session: &mut Session,
        max_age: Duration,
        stale_ok: bool,
    ) -> Result<Arc<Vec<Group>>, StorytelError> {
        let credentials = credentials(state).await?;
        let kept = session
            .shelf
            .as_ref()
            .filter(|kept| kept.generation == credentials.generation)
            .map(|kept| (kept.groups.clone(), kept.at));
        if let Some((groups, at)) = &kept
            && !max_age.is_zero()
            && self.now().saturating_duration_since(*at) < max_age
        {
            return Ok(groups.clone());
        }
        match self.fetch_shelf(state, session, &credentials).await {
            Ok(groups) => {
                let groups = Arc::new(groups);
                session.shelf = Some(KeptShelf {
                    groups: groups.clone(),
                    at: self.now(),
                    generation: credentials.generation,
                });
                Ok(groups)
            }
            Err(err) => match kept.filter(|_| stale_ok) {
                Some((groups, _)) => {
                    tracing::info!(code = %err.code(), "storytel: using the last good shelf");
                    Ok(groups)
                }
                None => Err(err),
            },
        }
    }

    /// After a save: the probe's login (when it got one) and shelf for the new generation; nothing
    /// else of the old login stays.
    ///
    /// Never waits (QA L1): the JWT is kept at once (its lock is never held across an `.await`);
    /// while a read holds the session (it may sit behind the gate) the probe's shelf is dropped -
    /// the generation check keeps the old one from being used.
    pub fn keep(&self, generation: i64, login: Option<Login>, shelf: Option<Vec<Group>>) {
        let now = self.now();
        let kept = login.map(|login| {
            lock(&self.gate).last = Some(login.facts);
            KeptJwt {
                jwt: login.jwt,
                made: now,
                life: login.life,
                generation,
                session: new_session(generation),
            }
        });
        *lock(&self.jwt) = kept;
        let Ok(mut session) = self.session.try_lock() else {
            tracing::debug!("storytel: the session is busy, the probe's shelf isn't kept");
            return;
        };
        session.shelf = shelf.map(|groups| KeptShelf {
            groups: Arc::new(groups),
            at: now,
            generation,
        });
    }

    /// Drops the JWT and the shelf (save and clear; the generation check covers a race).
    pub async fn forget(&self) {
        lock(&self.jwt).take();
        *self.session.lock().await = Session::default();
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::music_net::Source;
    use crate::tests::TestApp;

    const EMAIL: &str = "k@example.invalid";
    const PASSWORD: &str = "hemmelig-pw";
    const LOGIN_OK: &[u8] = br#"{"accountInfo":{"jwt":"FAKE-JWT-1"}}"#;

    fn fixture(name: &str) -> Vec<u8> {
        std::fs::read(format!("testdata/storytel/{name}")).unwrap()
    }

    fn login(email: &str, password: &str) -> String {
        login_url(LOGIN_URL, email, password)
    }

    /// A stored login (sealed with the test key) for generation `generation`.
    async fn store(app: &TestApp, email: &str, password: &str, generation: i64) {
        let key = app.state.music_key.clone().unwrap();
        let plain = serde_json::json!({"email": email, "password": password}).to_string();
        let (ciphertext, nonce) = key.seal(plain.as_bytes());
        sqlx::query(
            "UPDATE music_storytel SET ciphertext = ?, nonce = ?, key_fingerprint = ?, \
             saved_at = datetime('now'), generation = ? WHERE id = 1",
        )
        .bind(ciphertext)
        .bind(nonce)
        .bind(&key.fingerprint)
        .bind(generation)
        .execute(&app.db)
        .await
        .unwrap();
    }

    fn calls_to(app: &TestApp, prefix: &str) -> usize {
        app.fetch
            .calls
            .lock()
            .unwrap()
            .iter()
            .filter(|u| u.starts_with(prefix))
            .count()
    }

    /// A login as `login_with` makes it, for `keep` (no claims: the fallback life).
    fn fake_login_ok(jwt: &str) -> Login {
        Login {
            jwt: Jwt(jwt.to_string()),
            life: JWT_LIFE_FALLBACK,
            facts: LoginFacts {
                life_s: JWT_LIFE_FALLBACK.as_secs(),
                left_s: JWT_LIFE_FALLBACK.as_secs(),
                claims: false,
                has_email: Presence::Unknown,
            },
        }
    }

    fn kept_generation(account: &Account) -> Option<i64> {
        lock(&account.jwt).as_ref().map(|kept| kept.generation)
    }

    #[test]
    fn password_vectors() {
        // vibb's own vector, and three more printed by `openssl enc -aes-128-cbc` (26b §7).
        for (plain, hex) in [
            ("hemmelig", "AC6C4AAACBF142D09469371FC6BB8BE6"),
            ("", "CC463B52007F99B86F54B5776C8018CF"),
            (
                "0123456789abcdef",
                "2C9375205F4CAAD6C54EE8424656C741FE9BB4A407D56646E9CD60C083BF84A3",
            ),
            ("pass æøå", "4A2C4FFEE84E168CEAEB27B612E2EB30"),
        ] {
            assert_eq!(encrypt_password(plain), hex, "{plain:?}");
        }
        // Python's urlencode: `@` as %40, the hex as is.
        assert_eq!(
            login_url(LOGIN_URL, EMAIL, "hemmelig"),
            "https://www.storytel.com/api/login.action?m=1&uid=k%40example.invalid\
             &pwd=AC6C4AAACBF142D09469371FC6BB8BE6"
        );
    }

    /// The autoref probe (code QA L3): `has_debug()` resolves to the inherent method when `T:
    /// Debug`, else to the trait's `false` - decided by the compiler, whatever the spelling of a
    /// derive or an impl.
    struct Has<T>(std::marker::PhantomData<T>);
    trait Lacks {
        fn has_debug(&self) -> bool {
            false
        }
        fn has_display(&self) -> bool {
            false
        }
    }
    impl<T> Lacks for Has<T> {}
    impl<T: std::fmt::Debug> Has<T> {
        fn has_debug(&self) -> bool {
            true
        }
    }
    impl<T: std::fmt::Display> Has<T> {
        fn has_display(&self) -> bool {
            true
        }
    }

    /// QA L9: the types that hold the login, the JWT or the login URL have no `Debug` and no
    /// `Display` (the compiler then refuses a derived `Debug` on anything holding them).
    #[test]
    fn secret_types_have_no_debug() {
        use std::marker::PhantomData;
        // The probe itself works.
        assert!(Has::<String>(PhantomData).has_debug());
        assert!(Has::<String>(PhantomData).has_display());
        assert!(!Has::<StoredLogin>(PhantomData).has_debug());
        assert!(!Has::<StoredLogin>(PhantomData).has_display());
        assert!(!Has::<Jwt>(PhantomData).has_debug());
        assert!(!Has::<Jwt>(PhantomData).has_display());
        assert!(!Has::<Login>(PhantomData).has_debug());
        assert!(!Has::<Login>(PhantomData).has_display());
        assert!(!Has::<KeptJwt>(PhantomData).has_debug());
        assert!(!Has::<KeptJwt>(PhantomData).has_display());
        assert!(!Has::<PhoneJwt>(PhantomData).has_debug());
        assert!(!Has::<PhoneJwt>(PhantomData).has_display());
        assert!(!Has::<Credentials>(PhantomData).has_debug());
        assert!(!Has::<Credentials>(PhantomData).has_display());
        assert!(!Has::<SourceRequest>(PhantomData).has_debug());
        assert!(!Has::<SourceRequest>(PhantomData).has_display());
        // The response holds the login's body (the JWT): its Debug leaves the body out (L4).
        let response = crate::music_net::SourceResponse {
            status: 200,
            body: LOGIN_OK.to_vec(),
            ..Default::default()
        };
        let printed = format!("{response:?}");
        assert!(
            !printed.contains("FAKE") && !printed.contains("70, 65"),
            "{printed}"
        );
        assert!(printed.contains("body_len"), "{printed}");
    }

    #[test]
    fn targets() {
        assert_eq!(
            parse_target("storytel:series:26175"),
            Some((TargetKind::Series, "26175".to_string()))
        );
        assert_eq!(
            parse_target("storytel:book:160661"),
            Some((TargetKind::Book, "160661".to_string()))
        );
        assert_eq!(
            parse_target("storytel:book:a_B-9"),
            Some((TargetKind::Book, "a_B-9".to_string()))
        );
        for junk in [
            "storytel:junk",
            "nonsense",
            "",
            "storytel:series:",
            "storytel:album:1",
            "storytel:series:1:2",
            "storytel:series:1 2",
            "storytel:series:\"1\"",
            "storytel:book:1\n",
            "Storytel:book:1",
            "https://open.spotify.com/x",
        ] {
            assert_eq!(parse_target(junk), None, "{junk:?}");
        }
        // At most 64 characters, like the shelf's ids (QA L4 of 2b).
        let longest = "9".repeat(64);
        assert_eq!(
            parse_target(&format!("storytel:series:{longest}")),
            Some((TargetKind::Series, longest.clone()))
        );
        assert_eq!(parse_target(&format!("storytel:series:{longest}9")), None);
        assert_eq!(
            parse_target(&format!("storytel:book:{}", "a".repeat(100_000))),
            None
        );
        assert_eq!(series_target("26175"), "storytel:series:26175");
        assert_eq!(book_target("160661"), "storytel:book:160661");
        assert_eq!(cache_value(0), -1);
        assert_eq!(cache_value(5), 5);
        assert_eq!(cache_value(-1), -1);
    }

    #[test]
    fn normalize_groups_by_model_id_in_reading_order() {
        let groups = normalize_shelf(&fixture("bookshelf.json")).unwrap();
        let targets: Vec<&str> = groups.iter().map(|g| g.target.as_str()).collect();
        // First-seen DOCUMENT order (the keys are deliberately not sorted), not key order.
        assert_eq!(
            targets,
            [
                "storytel:series:26175",
                "storytel:book:999",
                "storytel:series:4242",
                "storytel:book:777",
            ]
        );
        let series = &groups[0];
        assert_eq!(series.kind, TargetKind::Series);
        assert_eq!(series.series_id.as_deref(), Some("26175"));
        assert_eq!(series.name, "Kokosbananas");
        assert!(series.kids);
        // model.id, never the format id; reading order whatever the shelf's order.
        let ids: Vec<&str> = series
            .books
            .iter()
            .map(|b| b.consumable_id.as_str())
            .collect();
        assert_eq!(ids, ["160661", "160662", "160663"]);
        assert_eq!(series.books[0].duration_ms, 721_893);
        assert_eq!(
            series.books[0].cover.as_deref(),
            Some("http://c.example.invalid/1.jpg")
        );
        // The group's cover is the first-seen book's (book 2 comes first in the document).
        assert_eq!(
            series.cover.as_deref(),
            Some("http://c.example.invalid/2-model.jpg")
        );
        assert_eq!(
            series.books[1].cover.as_deref(),
            Some("http://c.example.invalid/2-model.jpg"),
            "the model's cover when the format has none"
        );
        // The locked third book is listed, as data.
        assert!(series.books[2].locked);
        let standalone = &groups[1];
        assert_eq!(standalone.kind, TargetKind::Book);
        assert_eq!(standalone.series_id, None);
        assert_eq!(standalone.name, "Voksenkrim");
        assert_eq!(standalone.books.len(), 1);
        assert_eq!(standalone.books[0].consumable_id, "999");
        assert!(standalone.books[0].locked && !standalone.kids);
        // A numeric model.id and series id, stringified in decimal; the geo flag from the format.
        let numeric = &groups[2];
        assert_eq!(numeric.series_id.as_deref(), Some("4242"));
        assert_eq!(numeric.books[0].consumable_id, "555");
        assert!(numeric.books[0].geo);
        assert_eq!(numeric.name, "Tallserien");
        // The ebook-only row is dropped; no book is keyed by a format id.
        assert!(!targets.contains(&"storytel:book:888"));
        for group in &groups {
            for book in &group.books {
                assert!(!book.consumable_id.starts_with("fmt-"));
            }
        }
    }

    #[test]
    fn normalize_follows_pythons_rules() {
        let shelf = serde_json::json!({"items": {
            // A series id given as a string and as a number lands in one group.
            "z": {"model": {"id": 1, "title": "B", "seriesInfo": {"id": "77", "name": "S", "orderInSeries": 2},
                  "formats": [{"type": "abook"}]}},
            "a": {"model": {"id": "2", "title": "A", "seriesInfo": {"id": 77, "orderInSeries": 1},
                  "formats": [{"type": "abook"}]}},
            // "" and 0 as a series id: standalone books; null order is 0, no name -> the title.
            "m": {"model": {"id": 3, "title": "C", "seriesInfo": {"id": "", "orderInSeries": null},
                  "formats": [{"type": "ebook"}, {"type": "abook", "isGeoRestricted": 1}]}},
            "b": {"model": {"id": 4, "title": "D", "seriesInfo": {"id": 0}, "kidsBook": "yes",
                  "formats": [{"type": "abook", "isLockedContent": 0}]}},
            // A duplicate consumable id is kept once (the first).
            "c": {"model": {"id": "2", "title": "A again", "formats": [{"type": "abook"}]}},
            // No model.id: skipped (vibb would key it "None"); an id that isn't a key: skipped.
            "d": {"model": {"title": "No id", "formats": [{"type": "abook"}]}},
            "e": {"model": {"id": "a b", "title": "Bad id", "formats": [{"type": "abook"}]}},
            // No `model`: the item is the model. A series id failing the grammar: dropped.
            "f": {"id": 9, "title": "Flat", "formats": [{"type": "abook"}]},
            "g": {"model": {"id": 10, "title": "T", "seriesInfo": {"id": "x y"},
                  "formats": [{"type": "abook"}]}},
            "h": {"model": {"id": 11, "title": "T", "seriesInfo": {"id": true},
                  "formats": [{"type": "abook"}]}},
        }})
        .to_string();
        let groups = normalize_shelf(shelf.as_bytes()).unwrap();
        // serde_json's `json!` sorts the keys, so this is key order here; the fixture test pins
        // document order.
        let summary: Vec<(String, String, Vec<String>)> = groups
            .iter()
            .map(|g| {
                (
                    g.target.clone(),
                    g.name.clone(),
                    g.books.iter().map(|b| b.consumable_id.clone()).collect(),
                )
            })
            .collect();
        assert_eq!(
            summary,
            vec![
                (
                    "storytel:series:77".into(),
                    "A".into(),
                    vec!["2".into(), "1".into()]
                ),
                ("storytel:book:4".into(), "D".into(), vec!["4".into()]),
                ("storytel:book:9".into(), "Flat".into(), vec!["9".into()]),
                ("storytel:book:3".into(), "C".into(), vec!["3".into()]),
            ]
        );
        let flags = |id: &str| {
            groups
                .iter()
                .flat_map(|g| &g.books)
                .find(|b| b.consumable_id == id)
                .map(|b| (b.order, b.kids, b.locked, b.geo))
                .unwrap()
        };
        assert_eq!(flags("3"), (0.0, false, false, true));
        assert_eq!(flags("4"), (0.0, true, false, false));
        assert!(groups[1].kids);
        // Nothing, falsy and broken shelves.
        assert_eq!(normalize_shelf(b"{}").unwrap(), vec![]);
        assert_eq!(normalize_shelf(br#"{"items": null}"#).unwrap(), vec![]);
        assert_eq!(normalize_shelf(br#"{"items": []}"#).unwrap(), vec![]);
        assert_eq!(normalize_shelf(br#"{"items": [1]}"#), Err(ShelfError));
        assert_eq!(normalize_shelf(b"[]"), Err(ShelfError));
        assert_eq!(normalize_shelf(b"<html>"), Err(ShelfError));
    }

    #[tokio::test]
    async fn bookshelf_request_shape() {
        let app = TestApp::new().await;
        store(&app, EMAIL, PASSWORD, 1).await;
        app.fetch.answer(&login(EMAIL, PASSWORD), 200, LOGIN_OK);
        app.fetch.answer(BOOKSHELF, 200, fixture("bookshelf.json"));
        let groups = app.state.storytel.bookshelf(&app.state).await.unwrap();
        assert_eq!(groups.len(), 4);
        let requests = app.fetch.requests.lock().unwrap().clone();
        assert_eq!(requests.len(), 2);
        assert_eq!(requests[0].method, "GET");
        assert!(requests[0].url.starts_with(LOGIN_URL));
        let shelf = &requests[1];
        assert_eq!(shelf.url, BOOKSHELF);
        assert_eq!(shelf.method, "POST");
        assert_eq!(shelf.body.as_deref(), Some(&br#"{"items":[]}"#[..]));
        assert_eq!(
            shelf.header("content-type"),
            Some("application/x-www-form-urlencoded")
        );
        assert_eq!(shelf.header("authorization"), Some("Bearer FAKE-JWT-1"));
        assert_eq!(shelf.header("accept"), Some("*/*"));
        assert_eq!(app.fetch.reaches.lock().unwrap()[1].1, Reach::Public);
        // The stamps: the login accepted, no error.
        let (ok_at, error): (Option<String>, Option<String>) =
            sqlx::query_as("SELECT login_ok_at, login_error FROM music_storytel")
                .fetch_one(&app.db)
                .await
                .unwrap();
        assert!(ok_at.is_some());
        assert_eq!(error, None);
    }

    #[tokio::test]
    async fn a_401_relogs_once() {
        let app = TestApp::new().await;
        store(&app, EMAIL, PASSWORD, 1).await;
        app.fetch.answer(&login(EMAIL, PASSWORD), 200, LOGIN_OK);
        app.fetch.answer(BOOKSHELF, 200, fixture("bookshelf.json"));
        let account = &app.state.storytel;
        account.bookshelf(&app.state).await.unwrap();
        // The JWT is kept: a second read logs in no more.
        account.bookshelf(&app.state).await.unwrap();
        assert_eq!(calls_to(&app, LOGIN_URL), 1);
        assert_eq!(calls_to(&app, BOOKSHELF), 2);

        // A 401 once: one re-login, one retry.
        app.fetch.clear_calls();
        app.fetch.answer_once(BOOKSHELF, 401, "expired");
        account.bookshelf(&app.state).await.unwrap();
        assert_eq!(calls_to(&app, LOGIN_URL), 1);
        assert_eq!(calls_to(&app, BOOKSHELF), 2);

        // A 401 every time: still one re-login and one retry, then the 401 is the answer.
        app.fetch.clear_calls();
        app.fetch.answer(BOOKSHELF, 401, "expired");
        let err = account.bookshelf(&app.state).await.unwrap_err();
        assert_eq!(err, StorytelError::Status(401));
        assert_eq!(err.code(), "http_401");
        assert_eq!(calls_to(&app, LOGIN_URL), 1);
        assert_eq!(calls_to(&app, BOOKSHELF), 2);

        // Past its life (no claims: the 30-minute fallback, less the margin) the JWT is renewed
        // before it is used.
        app.fetch.answer(BOOKSHELF, 200, fixture("bookshelf.json"));
        app.fetch.clear_calls();
        account.bookshelf(&app.state).await.unwrap();
        assert_eq!(calls_to(&app, LOGIN_URL), 0);
        account.advance(JWT_LIFE_FALLBACK - MARGIN);
        account.bookshelf(&app.state).await.unwrap();
        assert_eq!(calls_to(&app, LOGIN_URL), 1);

        // A new login generation never uses the old JWT.
        store(&app, EMAIL, PASSWORD, 2).await;
        app.fetch.clear_calls();
        account.bookshelf(&app.state).await.unwrap();
        assert_eq!(calls_to(&app, LOGIN_URL), 1);
    }

    #[tokio::test]
    async fn refused_login_cooldown() {
        let app = TestApp::new().await;
        let t0 = parse_stamp("2026-10-12 10:02:00").unwrap();
        app.state.music_sweep.set_clock(Some(t0));
        store(&app, EMAIL, "stale-pw", 1).await;
        app.fetch.answer(&login(EMAIL, "stale-pw"), 403, "no");
        let account = &app.state.storytel;
        let err = account.bookshelf(&app.state).await.unwrap_err();
        assert_eq!(err, StorytelError::Refused(403));
        assert_eq!(err.code(), "login_refused");
        let row: (Option<String>, Option<i64>, Option<String>) = sqlx::query_as(
            "SELECT login_refused_at, login_refused_generation, login_error FROM music_storytel",
        )
        .fetch_one(&app.db)
        .await
        .unwrap();
        assert_eq!(
            row,
            (
                Some("2026-10-12 10:02:00".to_string()),
                Some(1),
                Some("login_refused".to_string())
            )
        );
        // Inside the hour: refused locally, no request at all (the same second too).
        for minutes in [0, 1, 59] {
            app.state
                .music_sweep
                .set_clock(Some(t0 + TimeDelta::minutes(minutes)));
            app.fetch.clear_calls();
            let err = account.bookshelf(&app.state).await.unwrap_err();
            assert_eq!(
                err,
                StorytelError::Cooldown {
                    until: t0 + LOGIN_COOLDOWN
                }
            );
            assert!(
                err.message(&crate::display_time::Zone::utc_fallback(), t0)
                    .contains("before 11:02 UTC"),
                "{}",
                err.message(&crate::display_time::Zone::utc_fallback(), t0)
            );
            assert_eq!(app.fetch.count(), 0, "{minutes} min: no request");
        }
        // Past the hour it is tried again; a 5xx is an outage now (design 58 §2.3): no cooldown,
        // the backoff instead.
        app.state
            .music_sweep
            .set_clock(Some(t0 + TimeDelta::minutes(61)));
        app.fetch.answer(&login(EMAIL, "stale-pw"), 503, "busy");
        app.fetch.clear_calls();
        let err = account.bookshelf(&app.state).await.unwrap_err();
        assert_eq!(err, StorytelError::LoginStatus(503));
        assert_eq!(err.code(), "http_503");
        assert_eq!(app.fetch.count(), 1);
        app.fetch.clear_calls();
        assert!(matches!(
            account.bookshelf(&app.state).await.unwrap_err(),
            StorytelError::Backoff { .. }
        ));
        assert_eq!(app.fetch.count(), 0);
        // A new password (the save's new generation) retries at once.
        store(&app, EMAIL, "fresh-pw", 2).await;
        app.fetch.answer(&login(EMAIL, "fresh-pw"), 200, LOGIN_OK);
        app.fetch.answer(BOOKSHELF, 200, fixture("bookshelf.json"));
        app.fetch.clear_calls();
        account.bookshelf(&app.state).await.unwrap();
        assert_eq!(calls_to(&app, LOGIN_URL), 1);
        // 200 without a JWT and an unreachable login: errors, no cooldown; the next read inside
        // the backoff sends nothing.
        for (generation, (status, body, code)) in [
            (200, &br#"{"accountInfo":{}}"#[..], "no_jwt"),
            (200, &b"not json"[..], "no_jwt"),
            (200, &br#"{"accountInfo":{"jwt":""}}"#[..], "no_jwt"),
        ]
        .into_iter()
        .enumerate()
        {
            store(&app, EMAIL, "x", 3 + generation as i64).await;
            app.fetch.answer(&login(EMAIL, "x"), status, body);
            account.forget().await;
            let err = account.bookshelf(&app.state).await.unwrap_err();
            assert_eq!(err.code(), code);
            app.fetch.clear_calls();
            assert_eq!(
                account.bookshelf(&app.state).await.unwrap_err().code(),
                "login_backoff"
            );
            assert_eq!(app.fetch.count(), 0);
        }
        store(&app, EMAIL, "x", 10).await;
        app.fetch.fail(&login(EMAIL, "x"), FetchError::Timeout);
        assert_eq!(
            account.bookshelf(&app.state).await.unwrap_err().code(),
            "timeout"
        );
        store(&app, EMAIL, "x", 11).await;
        app.fetch
            .fail(&login(EMAIL, "x"), FetchError::Network("reset".to_string()));
        app.fetch.clear_calls();
        assert_eq!(
            account.bookshelf(&app.state).await.unwrap_err().code(),
            "network"
        );
        assert_eq!(app.fetch.count(), 1, "no cooldown after an outage");
        let refused_generation: Option<i64> =
            sqlx::query_scalar("SELECT login_refused_generation FROM music_storytel")
                .fetch_one(&app.db)
                .await
                .unwrap();
        assert_eq!(refused_generation, Some(1));
    }

    #[tokio::test]
    async fn not_configured() {
        let app = TestApp::new().await;
        let account = &app.state.storytel;
        // No login.
        assert_eq!(
            account.bookshelf(&app.state).await.unwrap_err(),
            StorytelError::NotConfigured
        );
        assert_eq!(StorytelError::NotConfigured.code(), "storytel_off");
        // No key.
        store(&app, EMAIL, PASSWORD, 1).await;
        let mut keyless = app.state.clone();
        keyless.music_key = None;
        assert_eq!(
            account.bookshelf(&keyless).await.unwrap_err(),
            StorytelError::NotConfigured
        );
        // Sealed with another key: its own code, stored for the card.
        let other = crate::music_secret::MusicKey::from_bytes(&[1; 32]);
        let mut other_state = app.state.clone();
        other_state.music_key = Some(Arc::new(other));
        let err = account.bookshelf(&other_state).await.unwrap_err();
        assert_eq!(err, StorytelError::KeyUnusable);
        assert_eq!(err.code(), "storytel_key");
        let error: Option<String> = sqlx::query_scalar("SELECT login_error FROM music_storytel")
            .fetch_one(&app.db)
            .await
            .unwrap();
        assert_eq!(error.as_deref(), Some("storytel_key"));
        assert_eq!(app.fetch.count(), 0, "nothing was asked of Storytel");
    }

    #[tokio::test]
    async fn the_shelf_cache_and_stale_beats_dead() {
        let app = TestApp::new().await;
        store(&app, EMAIL, PASSWORD, 1).await;
        app.fetch.answer(&login(EMAIL, PASSWORD), 200, LOGIN_OK);
        app.fetch.answer(BOOKSHELF, 200, fixture("bookshelf.json"));
        let account = &app.state.storytel;
        assert_eq!(account.shelf(&app.state, SHELF_TTL).await.unwrap().len(), 4);
        assert_eq!(account.shelf(&app.state, SHELF_TTL).await.unwrap().len(), 4);
        assert_eq!(calls_to(&app, BOOKSHELF), 1, "5 minutes from memory");
        // Check now (max age 0) always fetches.
        account.shelf(&app.state, Duration::ZERO).await.unwrap();
        assert_eq!(calls_to(&app, BOOKSHELF), 2);
        account.advance(SHELF_TTL);
        account.shelf(&app.state, SHELF_TTL).await.unwrap();
        assert_eq!(calls_to(&app, BOOKSHELF), 3);
        // A failing fetch: the last good shelf, whatever its age...
        app.fetch.answer(BOOKSHELF, 500, "down");
        account.advance(Duration::from_secs(86_400));
        assert_eq!(account.shelf(&app.state, SHELF_TTL).await.unwrap().len(), 4);
        // ...but not the old login's, and the picker's fresh read reports the error.
        assert_eq!(
            account.bookshelf(&app.state).await.unwrap_err(),
            StorytelError::Status(500)
        );
        store(&app, EMAIL, PASSWORD, 2).await;
        assert_eq!(
            account.shelf(&app.state, SHELF_TTL).await.unwrap_err(),
            StorytelError::Status(500)
        );
        let error: Option<String> = sqlx::query_scalar("SELECT login_error FROM music_storytel")
            .fetch_one(&app.db)
            .await
            .unwrap();
        assert_eq!(error.as_deref(), Some("http_500"));
        // A truncated or unreadable shelf.
        app.fetch.answer(BOOKSHELF, 200, vec![b' '; 8_000_001]);
        assert_eq!(
            account.bookshelf(&app.state).await.unwrap_err(),
            StorytelError::TooBig
        );
        app.fetch.answer(BOOKSHELF, 200, "<html>");
        assert_eq!(
            account.bookshelf(&app.state).await.unwrap_err(),
            StorytelError::BadShelf
        );
        // forget() drops the shelf.
        app.fetch.answer(BOOKSHELF, 200, fixture("bookshelf.json"));
        account.bookshelf(&app.state).await.unwrap();
        account.forget().await;
        app.fetch.answer(BOOKSHELF, 500, "down");
        assert!(account.shelf(&app.state, SHELF_TTL).await.is_err());
    }

    #[tokio::test]
    async fn the_probe_is_bounded() {
        let app = TestApp::new().await;
        assert_eq!(PROBE_BUDGET, Duration::from_secs(45));
        app.fetch.answer(&login(EMAIL, PASSWORD), 200, LOGIN_OK);
        app.fetch.answer(BOOKSHELF, 200, fixture("bookshelf.json"));
        match probe(&app.state, EMAIL, PASSWORD, PROBE_BUDGET).await {
            Probe::Accepted { shelf, .. } => assert_eq!(shelf.len(), 4),
            _ => panic!("accepted"),
        }
        app.fetch.delay(300);
        match probe(&app.state, EMAIL, PASSWORD, Duration::from_millis(100)).await {
            Probe::Unreachable { code, login } => {
                assert_eq!(code, "timeout");
                assert!(login.is_none());
            }
            _ => panic!("past the budget = unreachable"),
        }
        app.fetch.delay(0);
        app.fetch.answer(BOOKSHELF, 502, "");
        match probe(&app.state, EMAIL, PASSWORD, PROBE_BUDGET).await {
            Probe::Unreachable { code, login } => {
                assert_eq!(code, "http_502");
                assert!(login.is_some(), "the login itself was taken");
            }
            _ => panic!("the shelf failing = unreachable"),
        }
        app.fetch.answer(&login(EMAIL, PASSWORD), 401, "");
        assert!(matches!(
            probe(&app.state, EMAIL, PASSWORD, PROBE_BUDGET).await,
            Probe::Refused {
                status: Some(401),
                outage: false
            }
        ));
        app.fetch.answer(&login(EMAIL, PASSWORD), 200, "{}");
        assert!(matches!(
            probe(&app.state, EMAIL, PASSWORD, PROBE_BUDGET).await,
            Probe::Refused {
                status: None,
                outage: true
            }
        ));
        // Design 58 §2.3: the probe classifies like every login, but touches no backoff.
        app.fetch.answer(&login(EMAIL, PASSWORD), 503, "");
        for _ in 0..2 {
            assert!(matches!(
                probe(&app.state, EMAIL, PASSWORD, PROBE_BUDGET).await,
                Probe::Refused {
                    status: Some(503),
                    outage: true
                }
            ));
        }
        assert!(lock(&app.state.storytel.gate).backoff.is_none());
        // The probe never stamps the cooldown.
        let refused: Option<String> =
            sqlx::query_scalar("SELECT login_refused_at FROM music_storytel")
                .fetch_one(&app.db)
                .await
                .unwrap();
        assert_eq!(refused, None);
    }

    /// A failing login over a loopback server (a dropped connection, a redirect loop that keeps
    /// the query): the error text holds neither the e-mail nor `pwd=`.
    #[tokio::test]
    async fn no_secret_in_errors() {
        let _ = rustls::crypto::ring::default_provider().install_default();
        use axum::response::IntoResponse;
        let app = axum::Router::new().route(
            "/api/login.action",
            axum::routing::get(|uri: axum::http::Uri| async move {
                axum::response::Redirect::temporary(&uri.to_string()).into_response()
            }),
        );
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let addr = listener.local_addr().unwrap();
        tokio::spawn(async move { axum::serve(listener, app).await.unwrap() });
        // Accepts and hangs up at once.
        let dropper = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let dropped = dropper.local_addr().unwrap();
        tokio::spawn(async move {
            while let Ok((socket, _)) = dropper.accept().await {
                drop(socket);
            }
        });
        let source = crate::music_net::HttpSource::new();
        for base in [
            format!("http://{addr}/api/login.action"),
            format!("http://{dropped}/api/login.action"),
        ] {
            let url = login_url(&base, EMAIL, PASSWORD);
            assert!(url.contains("pwd="));
            let err = source
                .get(SourceRequest::new(url, Kind::Login, Reach::Any))
                .await
                .expect_err("the login fails");
            let text = format!("{} {:?}", err.message(), err);
            assert!(!text.contains("pwd="), "{text}");
            assert!(!text.contains("example.invalid"), "{text}");
            assert!(!text.contains("k%40"), "{text}");
            assert!(!text.contains(&encrypt_password(PASSWORD)), "{text}");
            let storytel = StorytelError::Fetch(err);
            assert!(
                !format!(
                    "{storytel:?} {}",
                    storytel.message(&crate::display_time::Zone::utc_fallback(), Utc::now())
                )
                .contains("pwd=")
            );
        }
    }

    /// Code QA M1: a redirect of the login never carries the login URL (e-mail, `pwd=`) in a
    /// `Referer` to the next host.
    #[tokio::test]
    async fn the_login_sends_no_referer() {
        let _ = rustls::crypto::ring::default_provider().install_default();
        use axum::response::IntoResponse;
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let addr = listener.local_addr().unwrap();
        let next = format!("http://localhost:{}/next", addr.port());
        let app = axum::Router::new()
            .route(
                "/api/login.action",
                axum::routing::get(move || {
                    let next = next.clone();
                    async move { axum::response::Redirect::temporary(&next).into_response() }
                }),
            )
            .route(
                "/next",
                axum::routing::get(|headers: axum::http::HeaderMap| async move {
                    let referer = headers
                        .get("referer")
                        .map(|v| v.to_str().unwrap_or("?").to_string())
                        .unwrap_or_default();
                    format!(r#"{{"referer":"{referer}"}}"#)
                }),
            );
        tokio::spawn(async move { axum::serve(listener, app).await.unwrap() });
        let url = login_url(&format!("http://{addr}/api/login.action"), EMAIL, PASSWORD);
        let response = crate::music_net::HttpSource::new()
            .get(SourceRequest::new(url, Kind::Login, Reach::Any))
            .await
            .unwrap();
        assert_eq!(response.status, 200);
        assert_eq!(
            String::from_utf8_lossy(&response.body),
            r#"{"referer":""}"#,
            "the redirect target got a Referer"
        );
    }

    /// Code QA L1: the picker's read is bounded (the session's lock and the gate included), and
    /// a save's `keep` never waits for a read in progress.
    #[tokio::test]
    async fn the_read_is_bounded_and_keep_never_waits() {
        let app = TestApp::new().await;
        assert_eq!(READ_BUDGET, Duration::from_secs(45));
        store(&app, EMAIL, PASSWORD, 1).await;
        app.fetch.answer(&login(EMAIL, PASSWORD), 200, LOGIN_OK);
        app.fetch.answer(BOOKSHELF, 200, fixture("bookshelf.json"));
        let account = &app.state.storytel;
        app.fetch.delay(300);
        let err = account
            .bookshelf_within(&app.state, Duration::from_millis(100))
            .await
            .unwrap_err();
        assert_eq!(err, StorytelError::Fetch(FetchError::Timeout));
        assert_eq!(
            err.message(&crate::display_time::Zone::utc_fallback(), Utc::now()),
            "Storytel didn't answer in time - try again later."
        );
        app.fetch.delay(0);
        // A read holds the session: keep returns at once; the JWT is kept (its lock is never held
        // across an await), the shelf isn't.
        let held = account.session.lock().await;
        let kept = tokio::time::timeout(Duration::from_secs(1), async {
            account.keep(2, Some(fake_login_ok("FAKE-JWT-2")), Some(Vec::new()))
        })
        .await;
        assert!(kept.is_ok());
        assert!(held.shelf.is_none());
        assert_eq!(kept_generation(account), Some(2));
        drop(held);
        // Free: kept for its generation.
        account.keep(1, Some(fake_login_ok("FAKE-JWT-2")), None);
        assert_eq!(kept_generation(account), Some(1));
        app.fetch.clear_calls();
        account.bookshelf(&app.state).await.unwrap();
        assert_eq!(calls_to(&app, LOGIN_URL), 0, "the kept JWT is used");
    }

    // --- design 58: the phones' session ----------------------------------------------------------

    /// The wall clock the claims tests and the phones' asks run at (a sane year).
    fn wall() -> DateTime<Utc> {
        parse_stamp("2026-10-21 10:00:00").unwrap()
    }

    /// A JWT whose claims a test reads, built at run time (no `eyJ` literal is committed, design
    /// 58 §8): `FAKE-JWT-HEAD.<base64url payload>.FAKE-SIG`.
    fn claims_jwt(payload: serde_json::Value) -> String {
        use base64::Engine;
        let payload = base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(payload.to_string());
        format!("FAKE-JWT-HEAD.{payload}.FAKE-SIG")
    }

    fn login_body(jwt: &str) -> Vec<u8> {
        serde_json::json!({"accountInfo": {"jwt": jwt}})
            .to_string()
            .into_bytes()
    }

    /// A JWT living `life_s` from [wall] on, issued `age_s` before it.
    fn living(life_s: i64, age_s: i64) -> String {
        let iat = wall().timestamp() - age_s;
        claims_jwt(serde_json::json!({"iat": iat, "exp": iat + life_s, "sub": "1"}))
    }

    /// An app with the login stored for generation 1 at [wall], Storytel answering `body`.
    async fn phone_app(body: &[u8]) -> TestApp {
        let app = TestApp::new().await;
        app.state.music_sweep.set_clock(Some(wall()));
        store(&app, EMAIL, PASSWORD, 1).await;
        app.fetch
            .answer(&login(EMAIL, PASSWORD), 200, body.to_vec());
        app
    }

    async fn ask(
        app: &TestApp,
        device: i64,
        stale: Option<&str>,
    ) -> Result<PhoneJwt, StorytelError> {
        app.state
            .storytel
            .clone()
            .phone_jwt(app.state.clone(), device, stale.map(str::to_string))
            .await
    }

    /// A `busy` answer's wait in whole seconds, rounded up as the route does (the test's own
    /// milliseconds don't count).
    fn busy_wait(result: Result<PhoneJwt, StorytelError>) -> u64 {
        match result {
            Err(StorytelError::Busy { wait }) => wait_secs(wait),
            Err(err) => panic!("busy, not {err:?}"),
            Ok(_) => panic!("busy, not a JWT"),
        }
    }

    #[test]
    fn jwt_life_from_claims() {
        let now = wall();
        let t = now.timestamp();
        let life = |payload: serde_json::Value, at: DateTime<Utc>| jwt_life(Some(&payload), at);
        let secs = Duration::from_secs;
        // A fresh token: its own life.
        let fresh = life(serde_json::json!({"iat": t, "exp": t + 3600}), now);
        assert_eq!(
            (fresh.life, fresh.left, fresh.claims),
            (secs(3600), secs(3600), true)
        );
        // An older one handed back: what is left of it, not exp - iat (QA 58-16).
        let old = life(serde_json::json!({"iat": t - 3000, "exp": t + 600}), now);
        assert_eq!((old.life, old.left), (secs(3600), secs(600)));
        // Expired already: the sane floor.
        let gone = life(serde_json::json!({"iat": t - 4000, "exp": t - 400}), now);
        assert_eq!(gone.left, JWT_LIFE_SANE_FLOOR);
        // A clock that can't be trusted: exp - iat, at least 300 s.
        let early = parse_stamp("2001-01-01 00:00:00").unwrap();
        let short = life(serde_json::json!({"iat": t, "exp": t + 100}), early);
        assert_eq!((short.life, short.left), (secs(100), JWT_LIFE_FLOOR));
        let long = life(serde_json::json!({"iat": t - 3000, "exp": t + 600}), early);
        assert_eq!(long.left, secs(3600), "no wall clock: exp - iat");
        // Never past the cap; floats are numbers too.
        let huge = life(serde_json::json!({"iat": t, "exp": t + 10 * 86_400}), now);
        assert_eq!((huge.life, huge.left), (JWT_LIFE_CAP, JWT_LIFE_CAP));
        let float = life(
            serde_json::json!({"iat": t as f64, "exp": t as f64 + 900.5}),
            now,
        );
        assert_eq!(float.left.as_secs(), 900);
        // The fallback: a claim missing or not a number, no payload.
        for payload in [
            serde_json::json!({"iat": t}),
            serde_json::json!({"exp": t + 60}),
            serde_json::json!({"iat": t, "exp": "later"}),
            serde_json::json!([t, t + 60]),
        ] {
            let read = life(payload.clone(), now);
            assert_eq!(
                (read.life, read.left, read.claims),
                (JWT_LIFE_FALLBACK, JWT_LIFE_FALLBACK, false),
                "{payload}"
            );
        }
        assert_eq!(jwt_life(None, now).left, JWT_LIFE_FALLBACK);
        // The payload: the second part, URL-safe base64 with or without padding.
        assert_eq!(jwt_payload("FAKE-JWT-1"), None, "no dots");
        assert_eq!(jwt_payload("a.%%%.b"), None, "not base64");
        assert_eq!(jwt_payload("a.bm90IGpzb24.b"), None, "not JSON");
        let padded = {
            use base64::Engine;
            base64::engine::general_purpose::URL_SAFE.encode(r#"{"iat":12}"#)
        };
        assert!(padded.ends_with('='));
        assert_eq!(
            jwt_payload(&format!("h.{padded}.s")),
            Some(serde_json::json!({"iat": 12}))
        );
        assert_eq!(
            jwt_payload(&claims_jwt(serde_json::json!({"exp": 5}))),
            Some(serde_json::json!({"exp": 5}))
        );
    }

    #[test]
    fn payload_has_email_is_a_word() {
        let has = |payload: serde_json::Value| payload_has_email(Some(&payload), EMAIL);
        assert_eq!(
            has(serde_json::json!({"sub": "1", "deep": {"list": ["K@Example.Invalid"]}})),
            Presence::Yes
        );
        assert_eq!(
            has(serde_json::json!({"who": "someone@elsewhere"})),
            Presence::Yes,
            "any @"
        );
        assert_eq!(
            has(serde_json::json!({"sub": "1", "exp": 5, "roles": ["a"], "n": null})),
            Presence::No
        );
        assert_eq!(payload_has_email(None, EMAIL), Presence::Unknown);
        assert_eq!(
            has(serde_json::json!(["a", 1])),
            Presence::Unknown,
            "decoded, but no object"
        );
        assert_eq!(
            [Presence::Yes, Presence::No, Presence::Unknown].map(Presence::as_str),
            ["yes", "no", "unknown"]
        );
    }

    /// §2.7: every login logs one line of numbers and words, the save's probe's too - never the
    /// e-mail, a claim's name or the JWT.
    #[tokio::test]
    async fn the_login_line_holds_no_value() {
        use crate::tests::music_spotify::{CAPTURE, install_capture};
        install_capture();
        CAPTURE.with(|capture| *capture.borrow_mut() = Some(Vec::new()));
        let jwt = claims_jwt(serde_json::json!({
            "iat": wall().timestamp(), "exp": wall().timestamp() + 3600, "preferred_mailbox": EMAIL,
        }));
        let app = phone_app(&login_body(&jwt)).await;
        app.fetch.answer(BOOKSHELF, 200, fixture("bookshelf.json"));
        assert!(matches!(
            probe(&app.state, EMAIL, PASSWORD, PROBE_BUDGET).await,
            Probe::Accepted { .. }
        ));
        ask(&app, 1, None).await.unwrap();
        let lines = CAPTURE.with(|capture| capture.borrow_mut().take()).unwrap();
        let logged: Vec<&String> = lines
            .iter()
            .filter(|l| l.contains("storytel: logged in"))
            .collect();
        assert_eq!(logged.len(), 2, "the probe's and the phone's: {lines:?}");
        for line in &logged {
            assert!(
                line.contains(
                    "(life_s=3600, left_at_login_s=3600, claims=yes, payload_has_email=yes)"
                ),
                "{line}"
            );
        }
        for line in &lines {
            for secret in [
                EMAIL,
                "example.invalid",
                "preferred_mailbox",
                "FAKE-JWT",
                &jwt,
            ] {
                assert!(!line.contains(secret), "{line}");
            }
        }
        // The card's facts, from memory.
        assert_eq!(
            app.state.storytel.facts(),
            Some(LoginFacts {
                life_s: 3600,
                left_s: 3600,
                claims: true,
                has_email: Presence::Yes
            })
        );
        assert_eq!(Account::default().facts(), None, "not known after a start");
    }

    #[test]
    fn classify_login_splits_refusals_from_outages() {
        assert_eq!(classify_login(200, LOGIN_OK).unwrap(), "FAKE-JWT-1");
        for status in [400, 401, 403, 404, 451, 499] {
            assert_eq!(
                classify_login(status, LOGIN_OK).unwrap_err(),
                StorytelError::Refused(status),
                "{status}"
            );
        }
        for status in [100, 204, 302, 304, 408, 429, 500, 502, 503] {
            let err = classify_login(status, LOGIN_OK).unwrap_err();
            assert_eq!(err, StorytelError::LoginStatus(status), "{status}");
            assert!(err.is_outage());
        }
        for body in [&b"{}"[..], br#"{"accountInfo":{"jwt":""}}"#, b"<html>"] {
            assert_eq!(classify_login(200, body).unwrap_err(), StorytelError::NoJwt);
        }
        for outage in [
            StorytelError::NoJwt,
            StorytelError::ShortLife,
            StorytelError::Fetch(FetchError::Timeout),
            StorytelError::Fetch(FetchError::Network("reset".into())),
        ] {
            assert!(outage.is_outage(), "{outage:?}");
        }
        assert!(!StorytelError::Refused(403).is_outage());
    }

    /// QA N-8: a kept JWT is answered from the plain columns - a key that can't open the row
    /// still gets it, with no `login_error` write.
    #[tokio::test]
    async fn a_kept_jwt_needs_no_open() {
        let app = phone_app(LOGIN_OK).await;
        let first = ask(&app, 1, None).await.unwrap();
        assert_eq!(first.jwt.secret(), "FAKE-JWT-1");
        assert!(valid_session(&first.session) && first.session.starts_with("1:"));
        let mut other = app.state.clone();
        other.music_key = Some(Arc::new(crate::music_secret::MusicKey::from_bytes(
            &[1; 32],
        )));
        app.fetch.clear_calls();
        let again = other
            .storytel
            .clone()
            .phone_jwt(other.clone(), 2, None)
            .await
            .unwrap();
        assert_eq!(again.session, first.session);
        assert_eq!(app.fetch.count(), 0);
        let error: Option<String> = sqlx::query_scalar("SELECT login_error FROM music_storytel")
            .fetch_one(&app.db)
            .await
            .unwrap();
        assert_eq!(error, None);
        // A login it must make with that key: KeyUnusable, recorded once per generation.
        app.state.storytel.forget().await;
        for _ in 0..2 {
            assert_eq!(
                other
                    .storytel
                    .clone()
                    .phone_jwt(other.clone(), 2, None)
                    .await
                    .err(),
                Some(StorytelError::KeyUnusable)
            );
        }
        let error: Option<String> = sqlx::query_scalar("SELECT login_error FROM music_storytel")
            .fetch_one(&app.db)
            .await
            .unwrap();
        assert_eq!(error.as_deref(), Some("storytel_key"));
        assert_eq!(app.fetch.count(), 0);
    }

    /// QA N-9: through a backoff a plain ask gets the kept JWT while it has more than 2 minutes
    /// left, then `busy` to the backoff's end.
    #[tokio::test]
    async fn a_kept_jwt_is_handed_out_during_a_backoff() {
        let app = phone_app(&login_body(&living(600, 0))).await;
        let account = &app.state.storytel;
        let first = ask(&app, 1, None).await.unwrap();
        // Below min(15 min, life / 2) = 300 s left, an ask logs in - and Storytel is down.
        account.advance(Duration::from_secs(430));
        app.fetch.answer(&login(EMAIL, PASSWORD), 503, "");
        assert_eq!(
            ask(&app, 1, None).await.err(),
            Some(StorytelError::LoginStatus(503))
        );
        app.fetch.clear_calls();
        let kept = ask(&app, 2, None).await.unwrap();
        assert_eq!(kept.session, first.session, "170 s left: still answered");
        account.advance(Duration::from_secs(55));
        let wait = busy_wait(ask(&app, 2, None).await);
        assert_eq!(wait, 5, "115 s left: busy to the backoff's end");
        assert_eq!(app.fetch.count(), 0, "nothing sent inside the backoff");
    }

    /// QA N-6: a login leaving less than 2 minutes is an outage: the phone gets nothing and the
    /// backoff starts.
    #[tokio::test]
    async fn a_short_fresh_life_is_an_outage() {
        let app = phone_app(&login_body(&living(3600, 3540))).await;
        assert_eq!(
            ask(&app, 1, None).await.err(),
            Some(StorytelError::ShortLife)
        );
        let error: Option<String> = sqlx::query_scalar("SELECT login_error FROM music_storytel")
            .fetch_one(&app.db)
            .await
            .unwrap();
        assert_eq!(error.as_deref(), Some("short_life"));
        app.fetch.clear_calls();
        assert_eq!(
            busy_wait(ask(&app, 1, None).await),
            LOGIN_BACKOFF_MIN.as_secs()
        );
        assert_eq!(app.fetch.count(), 0);
        // 121 s left is enough.
        let app = phone_app(&login_body(&living(3600, 3479))).await;
        assert_eq!(ask(&app, 1, None).await.unwrap().left, PHONE_FLOOR);
    }

    /// QA N-1: a force that isn't allowed answers a wait, never the JWT it names.
    #[tokio::test]
    async fn a_declined_force_answers_a_wait() {
        let app = phone_app(LOGIN_OK).await;
        let account = &app.state.storytel;
        let first = ask(&app, 1, None).await.unwrap();
        // Too young: FORCE_MIN_AGE = min(5 min, 30 min / 2).
        account.advance(Duration::from_secs(100));
        assert_eq!(busy_wait(ask(&app, 1, Some(&first.session)).await), 200);
        // Old enough: one login, another JWT.
        app.fetch
            .answer(&login(EMAIL, PASSWORD), 200, login_body("FAKE-JWT-2"));
        account.advance(Duration::from_secs(200));
        let second = ask(&app, 1, Some(&first.session)).await.unwrap();
        assert_eq!(second.jwt.secret(), "FAKE-JWT-2");
        assert_ne!(second.session, first.session);
        // This phone's three forces an hour: the fourth waits for the oldest to leave.
        let mut current = second.session;
        for n in 3..=4 {
            app.fetch.answer(
                &login(EMAIL, PASSWORD),
                200,
                login_body(&format!("FAKE-JWT-{n}")),
            );
            account.advance(Duration::from_secs(300));
            current = ask(&app, 1, Some(&current)).await.unwrap().session;
        }
        account.advance(Duration::from_secs(300));
        app.fetch.clear_calls();
        assert_eq!(
            busy_wait(ask(&app, 1, Some(&current)).await),
            3600 - 900,
            "the first force was 900 s ago"
        );
        assert_eq!(app.fetch.count(), 0);
        // Another phone may still force.
        app.fetch
            .answer(&login(EMAIL, PASSWORD), 200, login_body("FAKE-JWT-5"));
        current = ask(&app, 2, Some(&current)).await.unwrap().session;
        // Inside a cooldown: `refused` to its end; a plain ask still gets the kept JWT.
        sqlx::query(
            "UPDATE music_storytel SET login_refused_at = '2026-10-21 09:40:00', \
             login_refused_generation = 1",
        )
        .execute(&app.db)
        .await
        .unwrap();
        account.advance(Duration::from_secs(300));
        assert_eq!(
            ask(&app, 3, Some(&current)).await.err(),
            Some(StorytelError::Cooldown {
                until: parse_stamp("2026-10-21 10:40:00").unwrap()
            })
        );
        assert_eq!(ask(&app, 3, None).await.unwrap().session, current);
        assert_eq!(app.fetch.count(), 1, "only phone 2's force logged in");
    }

    /// Design 58 §2.1: a phone's ask never waits behind a shelf read sitting in the gate.
    #[tokio::test]
    async fn a_kept_jwt_is_answered_while_a_shelf_read_waits() {
        let app = phone_app(LOGIN_OK).await;
        let first = ask(&app, 1, None).await.unwrap();
        app.fetch.answer(BOOKSHELF, 200, fixture("bookshelf.json"));
        app.fetch.hold();
        let state = app.state.clone();
        let read = tokio::spawn(async move { state.storytel.bookshelf(&state).await });
        app.fetch.wait_in_flight().await;
        let answered = tokio::time::timeout(Duration::from_secs(1), ask(&app, 2, None))
            .await
            .expect("not behind the shelf read")
            .unwrap();
        assert_eq!(answered.session, first.session);
        app.fetch.release();
        read.await.unwrap().unwrap();
    }

    /// QA 58-7: the session is re-checked under the lock; a newer kept JWT is answered without a
    /// login; a force that fails as an outage keeps the JWT for the other phones.
    #[tokio::test]
    async fn a_force_relogs_only_for_the_stale_kept_old_jwt() {
        let app = phone_app(LOGIN_OK).await;
        let account = &app.state.storytel;
        let first = ask(&app, 1, None).await.unwrap();
        account.advance(Duration::from_secs(300));
        app.fetch
            .answer(&login(EMAIL, PASSWORD), 200, login_body("FAKE-JWT-2"));
        let second = ask(&app, 1, Some(&first.session)).await.unwrap();
        // Phone 2's 401 on the first JWT: the newer one, no login.
        app.fetch.clear_calls();
        let newer = ask(&app, 2, Some(&first.session)).await.unwrap();
        assert_eq!(
            (newer.jwt.secret(), newer.session.as_str()),
            ("FAKE-JWT-2", second.session.as_str())
        );
        assert_eq!(app.fetch.count(), 0);
        // A force that fails as an outage: that phone gets nothing, the others the kept JWT.
        account.advance(Duration::from_secs(300));
        app.fetch.answer(&login(EMAIL, PASSWORD), 502, "");
        assert_eq!(
            ask(&app, 3, Some(&second.session)).await.err(),
            Some(StorytelError::LoginStatus(502))
        );
        assert_eq!(ask(&app, 4, None).await.unwrap().session, second.session);
        // A refusal drops it.
        account.advance(Duration::from_secs(60));
        store(&app, EMAIL, PASSWORD, 2).await;
        app.fetch.answer(&login(EMAIL, PASSWORD), 403, "");
        assert_eq!(
            ask(&app, 4, None).await.err(),
            Some(StorytelError::Refused(403))
        );
        assert!(lock(&account.jwt).is_none());
    }

    /// QA 58-7: the sweep logs in after a 401 only while the refused session is the kept one.
    #[tokio::test]
    async fn the_sweeps_401_follows_the_kept_session() {
        let app = phone_app(LOGIN_OK).await;
        let account = &app.state.storytel;
        let first = ask(&app, 1, None).await.unwrap();
        account.advance(Duration::from_secs(300));
        app.fetch
            .answer(&login(EMAIL, PASSWORD), 200, login_body("FAKE-JWT-2"));
        let second = ask(&app, 1, Some(&first.session)).await.unwrap();
        // The sweep's 401 was on the first JWT: the phone's re-login serves it.
        let credentials = credentials(&app.state).await.unwrap();
        app.fetch.clear_calls();
        let mut sent = 0;
        let jwt = account
            .bearer(&app.state, &credentials, Some(&first.session), &mut sent)
            .await
            .unwrap();
        assert_eq!((jwt.session, sent), (second.session.clone(), 0));
        assert_eq!(app.fetch.count(), 0);
        // A 401 on the kept one logs in once; two 401s on one JWT make one login.
        app.fetch.answer(BOOKSHELF, 401, "expired");
        assert_eq!(
            account.bookshelf(&app.state).await.unwrap_err(),
            StorytelError::Status(401)
        );
        assert_eq!(calls_to(&app, LOGIN_URL), 1);
        assert_eq!(calls_to(&app, BOOKSHELF), 2);
    }

    /// §2.3: one failed login backs every phone off - 1 minute, doubling - reset by a login and by
    /// a new generation.
    #[tokio::test]
    async fn a_failed_login_backs_off_for_every_phone() {
        let app = phone_app(LOGIN_OK).await;
        let account = &app.state.storytel;
        app.fetch.answer(&login(EMAIL, PASSWORD), 503, "");
        assert!(ask(&app, 1, None).await.is_err());
        app.fetch.clear_calls();
        assert_eq!(busy_wait(ask(&app, 2, None).await), 60);
        account.advance(Duration::from_secs(60));
        assert!(ask(&app, 2, None).await.is_err());
        assert_eq!(app.fetch.count(), 1, "one login after the first minute");
        assert_eq!(busy_wait(ask(&app, 3, None).await), 120);
        // The sweep waits too, and says when it tries again.
        let err = account.bookshelf(&app.state).await.unwrap_err();
        assert_eq!(err.code(), "login_backoff");
        assert!(
            err.message(&crate::display_time::Zone::utc_fallback(), wall())
                .contains("trying again at 10:02 UTC"),
            "{}",
            err.message(&crate::display_time::Zone::utc_fallback(), wall())
        );
        for _ in 0..6 {
            let wait = busy_wait(ask(&app, 3, None).await);
            account.advance(Duration::from_secs(wait));
            assert!(ask(&app, 3, None).await.is_err());
        }
        assert_eq!(
            busy_wait(ask(&app, 3, None).await),
            LOGIN_BACKOFF_MAX.as_secs()
        );
        // A login resets it.
        account.advance(LOGIN_BACKOFF_MAX);
        app.fetch.answer(&login(EMAIL, PASSWORD), 200, LOGIN_OK);
        ask(&app, 3, None).await.unwrap();
        account.forget().await;
        app.fetch.answer(&login(EMAIL, PASSWORD), 503, "");
        assert!(ask(&app, 3, None).await.is_err());
        assert_eq!(
            busy_wait(ask(&app, 3, None).await),
            LOGIN_BACKOFF_MIN.as_secs()
        );
        // So does a new generation (a save).
        store(&app, EMAIL, PASSWORD, 2).await;
        app.fetch.clear_calls();
        assert!(ask(&app, 3, None).await.is_err());
        assert_eq!(app.fetch.count(), 1, "logged in at once");
    }

    /// Q-B: a 5xx at login is an outage - no cooldown stamp, its code recorded, the backoff.
    #[tokio::test]
    async fn a_5xx_login_is_an_outage_not_a_refusal() {
        let app = phone_app(LOGIN_OK).await;
        app.fetch.answer(&login(EMAIL, PASSWORD), 503, "");
        assert_eq!(
            ask(&app, 1, None).await.err(),
            Some(StorytelError::LoginStatus(503))
        );
        let row: (Option<String>, Option<i64>, Option<String>) = sqlx::query_as(
            "SELECT login_refused_at, login_refused_generation, login_error FROM music_storytel",
        )
        .fetch_one(&app.db)
        .await
        .unwrap();
        assert_eq!(row, (None, None, Some("http_503".to_string())));
        assert!(lock(&app.state.storytel.gate).backoff.is_some());
    }

    /// §2.3: the logins the phones cause are capped server-wide: max(6, 2 x logins an hour).
    #[tokio::test]
    async fn phone_logins_are_capped_server_wide() {
        let app = phone_app(&login_body(&living(3600, 0))).await;
        let account = &app.state.storytel;
        let mut current = ask(&app, 0, None).await.unwrap().session;
        for device in 1..=5 {
            account.advance(Duration::from_secs(300));
            current = ask(&app, device, Some(&current)).await.unwrap().session;
        }
        assert_eq!(calls_to(&app, LOGIN_URL), 6);
        account.advance(Duration::from_secs(300));
        assert_eq!(
            busy_wait(ask(&app, 6, Some(&current)).await),
            3600 - 1800,
            "until the first login leaves the hour"
        );
        assert_eq!(calls_to(&app, LOGIN_URL), 6);
        assert_eq!(
            ask(&app, 7, None).await.unwrap().session,
            current,
            "a plain ask gets the kept JWT"
        );
        // A shorter life raises the bound: 2 x ceil(3600 / 600) = 12.
        lock(&account.gate).last.as_mut().unwrap().left_s = 600;
        assert!(ask(&app, 6, Some(&current)).await.is_ok());
    }

    #[tokio::test]
    async fn sessions_are_unique_across_an_account_rebuild() {
        let app = phone_app(LOGIN_OK).await;
        let first = ask(&app, 1, None).await.unwrap();
        let mut rebuilt = app.state.clone();
        rebuilt.storytel = Arc::new(Account::default());
        let second = rebuilt
            .storytel
            .clone()
            .phone_jwt(rebuilt.clone(), 1, None)
            .await
            .unwrap();
        assert_ne!(first.session, second.session);
        assert!(valid_session(&second.session));
        for bad in [
            "1:00000000000000a",
            "1:00000000000000A1",
            ":0000000000000001",
            "x:0000000000000001",
            "1;0000000000000001",
            "1234567890123456789:0000000000000001",
        ] {
            assert!(!valid_session(bad), "{bad}");
        }
        assert!(valid_session("123456789012345678:0123456789abcdef"));
    }

    #[test]
    fn the_life_fits_the_launchers_bound() {
        // The launcher takes expires_in_s in 1..86400; the route says what is left minus MARGIN.
        assert!((JWT_LIFE_CAP - MARGIN).as_secs() <= 86_400);
        assert!((PHONE_FLOOR - MARGIN).as_secs() >= 61);
        assert!(PHONE_FLOOR > 2 * MARGIN);
    }

    /// §2.6, QA 58-11, N-12: the override only on loopback (the host compared exactly), only
    /// then `Reach::Any`, and then only a fake login is ever sent - the save's probe's too.
    #[tokio::test]
    async fn the_override_sends_only_a_fake_login() {
        let vars = |value: &str| {
            Config::from_vars(&HashMap::from([(
                "STORYTEL_BASE".to_string(),
                value.to_string(),
            )]))
        };
        for (value, base) in [
            ("http://127.0.0.1:8730", "http://127.0.0.1:8730"),
            ("http://127.0.0.1:8730/", "http://127.0.0.1:8730"),
            (" http://[::1]:8730 ", "http://[::1]:8730"),
            ("http://localhost:8730", "http://localhost:8730"),
        ] {
            let config = vars(value);
            assert_eq!(config.fake_base.as_deref(), Some(base), "{value}");
            assert_eq!(config.login_url, format!("{base}/api/login.action"));
            assert_eq!(config.bookshelf, format!("{base}/libraries/bookshelf"));
            assert_eq!(config.reach(), Reach::Any);
            assert!(config.warnings.is_empty());
        }
        for value in [
            "http://127.0.0.1.evil.example:8730",
            "http://localhost.example.invalid:8730",
            "https://127.0.0.1:8730",
            "http://127.0.0.1",
            "http://127.0.0.1:8730/api",
            "http://user@127.0.0.1:8730",
            "http://10.0.2.2:8730",
            "127.0.0.1:8730",
        ] {
            let config = vars(value);
            assert_eq!(config.fake_base, None, "{value}");
            assert_eq!(config.login_url, LOGIN_URL);
            assert_eq!(config.reach(), Reach::Public);
            assert_eq!(config.warnings.len(), 1, "{value}");
        }
        assert!(vars("").warnings.is_empty());
        assert_eq!(Config::default().reach(), Reach::Public);

        // The fake-login guard reads the whole domain.
        for email in [
            "k@example.invalid",
            "K@Example.Invalid",
            "x@kids.example.invalid",
        ] {
            assert!(super::fake_login(email), "{email}");
        }
        for email in [
            "parent@example.org",
            "x@evilexample.invalid",
            "example.invalid@example.org",
            "x@example.invalid.example.org",
            "no-at-sign.example.invalid",
        ] {
            assert!(!super::fake_login(email), "{email}");
        }
        let fake = vars("http://127.0.0.1:8730");
        let fake_login = |email: &str| login_url(&fake.login_url, email, PASSWORD);
        let app = TestApp::new().await;
        let mut state = app.state.clone();
        state.storytel = Arc::new(Account::new(fake.clone()));
        assert_eq!(
            state.storytel.force_min_age(JWT_LIFE_FALLBACK),
            FORCE_MIN_AGE_FAKE
        );
        // A login that isn't a fake one: nothing sent, the phone's ask and the probe alike.
        let real = "parent@example.org";
        store(&app, real, PASSWORD, 1).await;
        app.fetch.answer(&fake_login(real), 200, LOGIN_OK);
        for _ in 0..8 {
            assert_eq!(
                state
                    .storytel
                    .clone()
                    .phone_jwt(state.clone(), 1, None)
                    .await
                    .err(),
                Some(StorytelError::NotConfigured),
                "never counted in the cap (code QA L5)"
            );
        }
        assert!(lock(&state.storytel.gate).phone_logins.is_empty());
        assert!(matches!(
            probe(&state, real, PASSWORD, PROBE_BUDGET).await,
            Probe::Unreachable { code, login: None } if code == "storytel_off"
        ));
        assert_eq!(app.fetch.count(), 0);
        // A fake one goes to the fake, with Reach::Any; the bookshelf too.
        store(&app, EMAIL, PASSWORD, 2).await;
        app.fetch.answer(&fake_login(EMAIL), 200, LOGIN_OK);
        app.fetch
            .answer(&fake.bookshelf, 200, fixture("bookshelf.json"));
        state
            .storytel
            .clone()
            .phone_jwt(state.clone(), 1, None)
            .await
            .unwrap();
        state.storytel.bookshelf(&state).await.unwrap();
        let reaches = app.fetch.reaches.lock().unwrap().clone();
        assert_eq!(
            reaches,
            vec![
                (fake_login(EMAIL), Reach::Any),
                (fake.bookshelf.clone(), Reach::Any)
            ]
        );
        // Without the override nothing changes.
        assert_eq!(
            app.state.storytel.force_min_age(JWT_LIFE_FALLBACK),
            Duration::from_secs(300)
        );
    }

    /// Code QA L1: while a login waits in the fetch gate, a plain ask gets the kept JWT that still
    /// serves (10 minutes left, under `PHONE_MIN_LEFT`) at once; a force waits for the lock.
    #[tokio::test]
    async fn a_plain_ask_never_waits_behind_a_login_in_flight() {
        let app = phone_app(LOGIN_OK).await;
        let account = &app.state.storytel;
        let first = ask(&app, 1, None).await.unwrap();
        account.advance(JWT_LIFE_FALLBACK - Duration::from_secs(600));
        app.fetch.hold();
        let state = app.state.clone();
        let refresh = tokio::spawn(state.storytel.clone().phone_jwt(state.clone(), 1, None));
        app.fetch.wait_in_flight().await;
        let kept = tokio::time::timeout(Duration::from_secs(1), ask(&app, 2, None))
            .await
            .expect("not behind the login in flight")
            .unwrap();
        assert_eq!(kept.session, first.session);
        assert!(kept.left <= Duration::from_secs(600));
        let force = tokio::time::timeout(
            Duration::from_millis(200),
            ask(&app, 3, Some(&first.session)),
        )
        .await;
        assert!(force.is_err(), "a force waits for the lock");
        app.fetch.release();
        let fresh = refresh.await.unwrap().unwrap();
        assert_ne!(fresh.session, first.session);
        assert_eq!(calls_to(&app, LOGIN_URL), 2);
    }

    /// Code QA L2: a login started before a save never replaces or drops the save's newer JWT,
    /// nor its facts.
    #[tokio::test]
    async fn a_login_in_flight_never_replaces_a_newer_save() {
        let probe_facts = fake_login_ok("FAKE-JWT-9").facts;
        for (status, body) in [(200, login_body(&living(3600, 0))), (403, Vec::new())] {
            let app = phone_app(LOGIN_OK).await;
            app.fetch.answer(&login(EMAIL, PASSWORD), status, body);
            app.fetch.hold();
            let state = app.state.clone();
            let old = tokio::spawn(state.storytel.clone().phone_jwt(state.clone(), 1, None));
            app.fetch.wait_in_flight().await;
            // The parent saves meanwhile: generation 2 and the probe's JWT.
            store(&app, EMAIL, PASSWORD, 2).await;
            app.state
                .storytel
                .keep(2, Some(fake_login_ok("FAKE-JWT-9")), None);
            app.fetch.release();
            let _ = old.await.unwrap();
            assert_eq!(kept_generation(&app.state.storytel), Some(2), "{status}");
            assert_eq!(app.state.storytel.facts(), Some(probe_facts), "{status}");
            app.fetch.clear_calls();
            assert_eq!(
                ask(&app, 1, None).await.unwrap().jwt.secret(),
                "FAKE-JWT-9",
                "{status}"
            );
            assert_eq!(app.fetch.count(), 0, "{status}: no extra login");
        }
    }
}
