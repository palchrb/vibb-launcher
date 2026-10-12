//! The server's Storytel client (design `docs/design/26b-storytel.md` §0, §1.1 and §2.1, with its
//! QA review and decisions): the family's login (the sealed `music_storytel` row, phase 1), the
//! bookshelf and its grouping into series and standalone books. It reads the **shelf only** -
//! never bookmarks, never audio: the phones log in by themselves and mint the audio URLs.
//!
//! - **One account** ([Account], `AppState.storytel`): a tokio `Mutex` so one login runs at a
//!   time; the JWT (1 h, re-login once on a 401) and the last shelf (5 minutes for a routine read)
//!   in memory only, both for the login generation they were made with.
//! - **The refused-login cooldown**: a refused login (any status but 200) is stamped in
//!   `music_storytel.login_refused_at` with its generation; for an hour after it no login is tried
//!   for that generation (no request at all), and saving the login again (a new generation) retries
//!   at once. A login the server can't reach (or one without a JWT) is no refusal.
//! - **Every request** goes through `AppState.music_fetch` (one in flight server-wide, 200 ms
//!   apart), [Reach::Public].
//! - **Secrets**: the password, the encrypted password (`pwd=`), the e-mail and the JWT never reach
//!   a log, an error text, an event, a page or `music_state`. [StoredLogin] and [Jwt] have no
//!   `Debug` and no `Display` (QA L9); errors carry codes and statuses only, and `music_net` drops
//!   the URL from every transport error.
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
//! a book without a `model.id` skipped instead of keyed "None", a consumable id seen twice kept once.

use std::collections::{HashMap, HashSet};
use std::sync::Arc;
use std::time::Duration;

use chrono::{DateTime, TimeDelta, Utc};
use serde::Deserialize;
use serde::de::{self, IgnoredAny, MapAccess, SeqAccess, Visitor};
use serde_json::Value;
use tokio::time::Instant;

use crate::AppState;
use crate::music_net::{FetchError, Kind, Reach, SourceRequest};
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
/// login.action gives no expiry: a JWT is used for an hour, and a 401 logs in again once.
pub const JWT_TTL: Duration = Duration::from_secs(3600);
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

// --- targets (no I/O) ----------------------------------------------------------------------------

#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum TargetKind {
    Series,
    Book,
}

fn id_ok(id: &str) -> bool {
    !id.is_empty()
        && id
            .chars()
            .all(|c| c.is_ascii_alphanumeric() || c == '-' || c == '_')
}

/// A consumable id usable as an item key (21b's keys: `[A-Za-z0-9_-]{1,64}`).
pub fn consumable_id_ok(id: &str) -> bool {
    id.len() <= 64 && id_ok(id)
}

/// `storytel:(series|book):<id>`, the id `[A-Za-z0-9_-]+`, nothing else (vibb `TARGET_RE`).
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
    /// No login stored, or no key on this server (`storytel_off`).
    NotConfigured,
    /// The login was sealed with another key (`storytel_key`): enter it again (QA L4).
    KeyUnusable,
    /// The login answered this status (not 200): a refusal, the cooldown starts.
    Refused(u16),
    /// Inside the refused-login cooldown: no request was made.
    Cooldown {
        until: DateTime<Utc>,
    },
    /// 200 without `accountInfo.jwt`.
    NoJwt,
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
            StorytelError::NoJwt => "no_jwt".to_string(),
            StorytelError::Fetch(err) => err.code().to_string(),
            StorytelError::Status(code) => format!("http_{code}"),
            StorytelError::TooBig => "too_big".to_string(),
            StorytelError::BadShelf => "bad_shelf".to_string(),
            StorytelError::Database(_) => "database".to_string(),
        }
    }

    /// The picker's text (UTC times).
    pub fn message(&self) -> String {
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
                until.format("%H:%M UTC")
            ),
            StorytelError::NoJwt => {
                "Storytel took the login but sent no session - try again later.".to_string()
            }
            StorytelError::Fetch(FetchError::Timeout) => {
                "Storytel didn't answer in time - try again later.".to_string()
            }
            StorytelError::Fetch(_) => {
                "Storytel couldn't be reached - try again later.".to_string()
            }
            StorytelError::Status(code) => format!("Storytel answered {code} - try again later."),
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
/// stored (QA L9).
pub struct Jwt(String);

impl Jwt {
    fn bearer(&self) -> String {
        format!("Bearer {}", self.0)
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
        tracing::warn!("storytel: the saved login can't be opened with this server's key");
        record(state, generation, Outcome::Failed("storytel_key")).await;
        return Err(StorytelError::KeyUnusable);
    };
    Ok(Credentials {
        login,
        generation,
        refused_at,
        refused_generation,
    })
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

/// The login request alone (vibb `login` minus the stored login and the cooldown): GET
/// `login.action?m=1&uid=..&pwd=..`, redirects followed. Not 200 = `Refused` (a 5xx too: "a
/// refusal, not an outage"); 200 without a non-empty `accountInfo.jwt` = `NoJwt`. Touches no
/// database: the save's probe uses it with the login being entered.
pub async fn login_with(
    state: &AppState,
    email: &str,
    password: &str,
) -> Result<Jwt, StorytelError> {
    let request = SourceRequest::new(
        login_url(LOGIN_URL, email, password),
        Kind::Login,
        Reach::Public,
    );
    let response = state
        .music_fetch
        .get(request)
        .await
        .map_err(StorytelError::Fetch)?;
    if response.status != 200 {
        tracing::info!(status = response.status, "storytel: login refused");
        return Err(StorytelError::Refused(response.status));
    }
    let jwt = serde_json::from_slice::<Value>(&response.body)
        .ok()
        .and_then(|body| {
            body.get("accountInfo")?
                .get("jwt")?
                .as_str()
                .filter(|jwt| !jwt.is_empty())
                .map(str::to_string)
        });
    match jwt {
        Some(jwt) => Ok(Jwt(jwt)),
        None => {
            tracing::warn!("storytel: login answered 200 without a session");
            Err(StorytelError::NoJwt)
        }
    }
}

fn shelf_request(jwt: &Jwt) -> SourceRequest {
    use reqwest::header::{ACCEPT, AUTHORIZATION, CONTENT_TYPE};
    let mut request = SourceRequest::new(BOOKSHELF, Kind::Shelf, Reach::Public);
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
    Accepted { jwt: Jwt, shelf: Vec<Group> },
    /// Not 200 (`status`), or 200 without a JWT (`None`): nothing is stored.
    Refused { status: Option<u16> },
    /// A transport error, the shelf failing, or past the budget: stored anyway, with this code.
    Unreachable { code: String, jwt: Option<Jwt> },
}

/// The save's probe (vibb `/storytel/credentials`): one login with the entered login and one
/// bookshelf, within `budget` in all (the wait for the gate included). No cooldown applies, and
/// none is stamped.
pub async fn probe(state: &AppState, email: &str, password: &str, budget: Duration) -> Probe {
    let attempt = async {
        let jwt = match login_with(state, email, password).await {
            Ok(jwt) => jwt,
            Err(StorytelError::Refused(status)) => {
                return Probe::Refused {
                    status: Some(status),
                };
            }
            Err(StorytelError::NoJwt) => return Probe::Refused { status: None },
            Err(err) => {
                return Probe::Unreachable {
                    code: err.code(),
                    jwt: None,
                };
            }
        };
        let shelf = match state.music_fetch.get(shelf_request(&jwt)).await {
            Ok(response) => read_shelf(response),
            Err(err) => Err(StorytelError::Fetch(err)),
        };
        match shelf {
            Ok(shelf) => Probe::Accepted { jwt, shelf },
            Err(err) => {
                tracing::warn!(code = %err.code(), "storytel: the probe's bookshelf failed");
                Probe::Unreachable {
                    code: err.code(),
                    jwt: Some(jwt),
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
                jwt: None,
            }
        }
    }
}

struct KeptJwt {
    jwt: Jwt,
    expires: Instant,
    generation: i64,
}

struct KeptShelf {
    groups: Arc<Vec<Group>>,
    at: Instant,
    generation: i64,
}

#[derive(Default)]
struct Session {
    jwt: Option<KeptJwt>,
    shelf: Option<KeptShelf>,
}

/// `AppState.storytel`: the server's one Storytel session.
#[derive(Default)]
pub struct Account {
    session: tokio::sync::Mutex<Session>,
    /// The tests move the JWT's and the shelf's clock forward.
    skew: std::sync::Mutex<Duration>,
}

impl Account {
    fn now(&self) -> Instant {
        Instant::now() + *self.skew.lock().unwrap_or_else(|e| e.into_inner())
    }

    /// Moves this account's clock (the JWT's expiry, the shelf's age) forward.
    #[cfg(test)]
    pub fn advance(&self, by: Duration) {
        *self.skew.lock().unwrap_or_else(|e| e.into_inner()) += by;
    }

    /// vibb `login`: the cooldown first (no request inside it), then [login_with] with the stored
    /// login; the outcome stamped for this generation.
    async fn login(
        &self,
        state: &AppState,
        session: &mut Session,
        credentials: &Credentials,
    ) -> Result<(), StorytelError> {
        if let Some(until) = credentials.cooldown_until(state.music_sweep.now()) {
            tracing::info!(until = %until.format("%H:%M UTC"), "storytel: login waits (refused earlier)");
            return Err(StorytelError::Cooldown { until });
        }
        let generation = credentials.generation;
        match login_with(state, &credentials.login.email, &credentials.login.password).await {
            Ok(jwt) => {
                tracing::info!(generation, "storytel: logged in");
                record(state, generation, Outcome::LoginOk).await;
                session.jwt = Some(KeptJwt {
                    jwt,
                    expires: self.now() + JWT_TTL,
                    generation,
                });
                Ok(())
            }
            Err(err) => {
                session.jwt = None;
                if matches!(err, StorytelError::Refused(_)) {
                    record(state, generation, Outcome::Refused).await;
                } else {
                    record(state, generation, Outcome::Failed(&err.code())).await;
                }
                Err(err)
            }
        }
    }

    /// vibb `_bearer`: the kept JWT while it is fresh and of this generation, else a login.
    async fn bearer<'s>(
        &self,
        state: &AppState,
        session: &'s mut Session,
        credentials: &Credentials,
        force: bool,
    ) -> Result<&'s Jwt, StorytelError> {
        let fresh = session.jwt.as_ref().is_some_and(|kept| {
            kept.generation == credentials.generation && self.now() < kept.expires
        });
        if force || !fresh {
            self.login(state, session, credentials).await?;
        }
        Ok(&session.jwt.as_ref().expect("a login keeps its JWT").jwt)
    }

    /// vibb `bookshelf` through `_api`: the bearer, one re-login and one retry on a 401, never two.
    async fn fetch_shelf(
        &self,
        state: &AppState,
        session: &mut Session,
        credentials: &Credentials,
    ) -> Result<Vec<Group>, StorytelError> {
        let mut response = None;
        for attempt in 0..2 {
            let jwt = self
                .bearer(state, session, credentials, attempt == 1)
                .await?;
            let answer = state
                .music_fetch
                .get(shelf_request(jwt))
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
        match self.fetch_shelf(state, &mut session, &credentials).await {
            Ok(groups) => {
                let groups = Arc::new(groups);
                session.shelf = Some(KeptShelf {
                    groups: groups.clone(),
                    at: self.now(),
                    generation: credentials.generation,
                });
                Ok(groups)
            }
            Err(err) => match kept {
                Some((groups, _)) => {
                    tracing::info!(code = %err.code(), "storytel: using the last good shelf");
                    Ok(groups)
                }
                None => Err(err),
            },
        }
    }

    /// After a save: the probe's JWT and shelf (when it got them) for the new generation; nothing
    /// else of the old login stays.
    ///
    /// Never waits (QA L1): while a read holds the session (it may sit behind the gate), what the
    /// probe got is dropped - the generation check keeps the old session from being used, so the
    /// cost is one more login.
    pub fn keep(&self, generation: i64, jwt: Option<Jwt>, shelf: Option<Vec<Group>>) {
        let now = self.now();
        let Ok(mut session) = self.session.try_lock() else {
            tracing::debug!("storytel: the session is busy, the probe's login isn't kept");
            return;
        };
        session.jwt = jwt.map(|jwt| KeptJwt {
            jwt,
            expires: now + JWT_TTL,
            generation,
        });
        session.shelf = shelf.map(|groups| KeptShelf {
            groups: Arc::new(groups),
            at: now,
            generation,
        });
    }

    /// Drops the JWT and the shelf (save and clear; the generation check covers a race).
    pub async fn forget(&self) {
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

        // Past the hour the JWT is renewed before it is used.
        app.fetch.answer(BOOKSHELF, 200, fixture("bookshelf.json"));
        app.fetch.clear_calls();
        account.bookshelf(&app.state).await.unwrap();
        assert_eq!(calls_to(&app, LOGIN_URL), 0);
        account.advance(JWT_TTL);
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
                err.message().contains("before 11:02 UTC"),
                "{}",
                err.message()
            );
            assert_eq!(app.fetch.count(), 0, "{minutes} min: no request");
        }
        // A 5xx is a refusal too; past the hour it is tried again.
        app.state
            .music_sweep
            .set_clock(Some(t0 + TimeDelta::minutes(61)));
        app.fetch.answer(&login(EMAIL, "stale-pw"), 503, "busy");
        app.fetch.clear_calls();
        assert_eq!(
            account.bookshelf(&app.state).await.unwrap_err(),
            StorytelError::Refused(503)
        );
        assert_eq!(app.fetch.count(), 1);
        app.fetch.clear_calls();
        assert!(matches!(
            account.bookshelf(&app.state).await.unwrap_err(),
            StorytelError::Cooldown { .. }
        ));
        assert_eq!(app.fetch.count(), 0);
        // A new password (the save's new generation) retries at once.
        store(&app, EMAIL, "fresh-pw", 2).await;
        app.fetch.answer(&login(EMAIL, "fresh-pw"), 200, LOGIN_OK);
        app.fetch.answer(BOOKSHELF, 200, fixture("bookshelf.json"));
        app.fetch.clear_calls();
        account.bookshelf(&app.state).await.unwrap();
        assert_eq!(calls_to(&app, LOGIN_URL), 1);
        // 200 without a JWT and an unreachable login: errors, no cooldown.
        for (status, body, code) in [
            (200, &br#"{"accountInfo":{}}"#[..], "no_jwt"),
            (200, &b"not json"[..], "no_jwt"),
            (200, &br#"{"accountInfo":{"jwt":""}}"#[..], "no_jwt"),
        ] {
            store(&app, EMAIL, "x", 3).await;
            app.fetch.answer(&login(EMAIL, "x"), status, body);
            account.forget().await;
            let err = account.bookshelf(&app.state).await.unwrap_err();
            assert_eq!(err.code(), code);
            assert_eq!(
                account.bookshelf(&app.state).await.unwrap_err().code(),
                code
            );
        }
        app.fetch.fail(&login(EMAIL, "x"), FetchError::Timeout);
        assert_eq!(
            account.bookshelf(&app.state).await.unwrap_err().code(),
            "timeout"
        );
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
            Probe::Unreachable { code, jwt } => {
                assert_eq!(code, "timeout");
                assert!(jwt.is_none());
            }
            _ => panic!("past the budget = unreachable"),
        }
        app.fetch.delay(0);
        app.fetch.answer(BOOKSHELF, 502, "");
        match probe(&app.state, EMAIL, PASSWORD, PROBE_BUDGET).await {
            Probe::Unreachable { code, jwt } => {
                assert_eq!(code, "http_502");
                assert!(jwt.is_some(), "the login itself was taken");
            }
            _ => panic!("the shelf failing = unreachable"),
        }
        app.fetch.answer(&login(EMAIL, PASSWORD), 401, "");
        assert!(matches!(
            probe(&app.state, EMAIL, PASSWORD, PROBE_BUDGET).await,
            Probe::Refused { status: Some(401) }
        ));
        app.fetch.answer(&login(EMAIL, PASSWORD), 200, "{}");
        assert!(matches!(
            probe(&app.state, EMAIL, PASSWORD, PROBE_BUDGET).await,
            Probe::Refused { status: None }
        ));
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
            assert!(!format!("{storytel:?} {}", storytel.message()).contains("pwd="));
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
            err.message(),
            "Storytel didn't answer in time - try again later."
        );
        app.fetch.delay(0);
        // A read holds the session: keep returns at once and keeps nothing.
        let held = account.session.lock().await;
        let kept = tokio::time::timeout(Duration::from_secs(1), async {
            account.keep(2, Some(Jwt("FAKE-JWT-2".into())), Some(Vec::new()))
        })
        .await;
        assert!(kept.is_ok());
        assert!(held.jwt.is_none() && held.shelf.is_none());
        drop(held);
        // Free: kept for its generation.
        account.keep(1, Some(Jwt("FAKE-JWT-2".into())), None);
        assert_eq!(
            account
                .session
                .lock()
                .await
                .jwt
                .as_ref()
                .map(|k| k.generation),
            Some(1)
        );
        app.fetch.clear_calls();
        account.bookshelf(&app.state).await.unwrap();
        assert_eq!(calls_to(&app, LOGIN_URL), 0, "the kept JWT is used");
    }
}
