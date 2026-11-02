use argon2::password_hash::SaltString;
use argon2::password_hash::rand_core::{OsRng, RngCore};
use argon2::{Argon2, PasswordHash, PasswordHasher, PasswordVerifier};
use axum::extract::{Request, State};
use axum::middleware::Next;
use axum::response::{IntoResponse, Redirect, Response};
use pbkdf2::pbkdf2_hmac;
use sha2::{Digest, Sha256};
use totp_rs::{Algorithm, Secret, TOTP};
use tower_sessions::Session;

use crate::AppState;
use crate::models::AdminUser;

pub const MIN_PASSWORD_LEN: usize = 12;

/// Failed attempts from one client (`net::limit_key`: an IPv4 address or an IPv6 /56, across any
/// account) within the window below before it is banned from reaching the login page at all.
/// The account lock is the TOTP step's alone (`admin_auth`).
pub const MAX_FAILED_PER_IP: i64 = 15;
pub const IP_FAILURE_WINDOW_MINUTES: i64 = 15;
/// How long an IP ban lasts before auto-expiring.
pub const IP_BAN_HOURS: i64 = 1;

pub fn hash_password(password: &str) -> String {
    let salt = SaltString::generate(&mut OsRng);
    Argon2::default()
        .hash_password(password.as_bytes(), &salt)
        .expect("hashing a non-empty password should not fail")
        .to_string()
}

pub fn verify_password(password: &str, hash: &str) -> bool {
    let Ok(parsed_hash) = PasswordHash::new(hash) else {
        return false;
    };
    Argon2::default()
        .verify_password(password.as_bytes(), &parsed_hash)
        .is_ok()
}

/// A parent's username (design 62 §2): 1-32 of `a-z 0-9 . _ -`. Add Parent lowercases what was
/// typed before it asks; the first account's `ADMIN_USERNAME` is only trimmed, since sign-in is
/// an exact match ([bootstrap_username]).
pub fn valid_username(name: &str) -> bool {
    (1..=32).contains(&name.len())
        && name
            .bytes()
            .all(|b| b.is_ascii_lowercase() || b.is_ascii_digit() || b"._-".contains(&b))
}

/// A display name as typed (design 62 §2): trimmed, empty is `None` (the username shows), else
/// 1-32 characters with no control, line-break or Unicode format character (bidi overrides,
/// zero-width), so a "by Mamma" line can't be spoofed. A refusal is its catalog key (`account.name.*`).
pub fn display_name(raw: &str) -> Result<Option<String>, &'static str> {
    let name = raw.trim();
    if name.is_empty() {
        return Ok(None);
    }
    if name.chars().count() > 32 {
        return Err("account.name.too_long");
    }
    if name.chars().any(|c| c.is_control() || is_format_char(c)) {
        return Err("account.name.bad_chars");
    }
    Ok(Some(name.to_string()))
}

/// Unicode's format characters (category Cf, Unicode 15.1) and the line and paragraph separators.
fn is_format_char(c: char) -> bool {
    matches!(c as u32,
        0x00AD | 0x0600..=0x0605 | 0x061C | 0x06DD | 0x070F | 0x0890..=0x0891 | 0x08E2 | 0x180E
        | 0x200B..=0x200F | 0x2028..=0x202E | 0x2060..=0x2064 | 0x2066..=0x206F | 0xFEFF
        | 0xFFF9..=0xFFFB | 0x110BD | 0x110CD | 0x13430..=0x1343F | 0x1BCA0..=0x1BCA3
        | 0x1D173..=0x1D17A | 0xE0001 | 0xE0020..=0xE007F)
}

/// The first account's name from `ADMIN_USERNAME` (unset or blank: "admin"), trimmed and never
/// lowercased (sign-in is an exact match); one that isn't [valid_username] is refused with the
/// reason, and the server doesn't start. It is read only while `admin_users` is empty, so an
/// existing install keeps its name whatever it is.
pub fn bootstrap_username(configured: Option<&str>) -> Result<String, String> {
    let name = configured
        .map(str::trim)
        .filter(|v| !v.is_empty())
        .unwrap_or("admin");
    if valid_username(name) {
        Ok(name.to_string())
    } else {
        Err(format!(
            "ADMIN_USERNAME {name:?} isn't a valid username: use 1-32 lowercase letters, digits, \
             '.', '_' or '-'"
        ))
    }
}

/// A schedule time as the forms send it: exactly `HH:MM`, 00:00-23:59 (security review H1). The
/// value goes into files a root-run script reads, so nothing else may ever reach them.
pub fn valid_clock_time(value: &str) -> bool {
    let bytes = value.as_bytes();
    if bytes.len() != 5 || bytes[2] != b':' {
        return false;
    }
    let digits = [bytes[0], bytes[1], bytes[3], bytes[4]];
    if !digits.iter().all(u8::is_ascii_digit) {
        return false;
    }
    let hour = (bytes[0] - b'0') * 10 + (bytes[1] - b'0');
    let minute = (bytes[3] - b'0') * 10 + (bytes[4] - b'0');
    hour < 24 && minute < 60
}

/// High-entropy bearer token handed to a device once, at enrollment. Only
/// its SHA-256 hash (see `hash_token`) is ever stored - the plaintext value
/// is shown/returned exactly once and can't be recovered afterward, the same
/// way a password reset works.
pub fn generate_device_token() -> String {
    let mut bytes = [0u8; 32];
    OsRng.fill_bytes(&mut bytes);
    hex::encode(bytes)
}

pub fn hash_token(token: &str) -> String {
    let mut hasher = Sha256::new();
    hasher.update(token.as_bytes());
    hex::encode(hasher.finalize())
}

/// What a security-log row says besides its label (design 70 §4, slice 2): **facts** - identifiers
/// only (a route, a tag, an address, a phone line's `device {id}`), stored in `detail` as typed -
/// and **words** - sentences stored in `params` as catalog keys with their parameters, worded in
/// the reader's language when the log is read ([Detail::shown]: the words, then " · " and the
/// facts). A row from before slice 2 has its prose in `detail` and no `params`: shown as stored.
///
/// A `security.detail.*` key is **frozen once a release wrote it**: old rows name it for 180 days,
/// and a key the catalog no longer has reads as itself. A rename keeps the old key in both
/// catalogs (or maps it when reading) until the rows are gone.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct Detail {
    facts: Option<String>,
    words: Vec<Sentence>,
}

/// One sentence of a row's words: a key (`security.detail.<name>`, with `.one`/`.other` when
/// `n` counts), its text parameters, and its instants (`t`, RFC 3339 UTC) - told as times in the
/// reader's zone and language when read.
#[derive(Clone, Debug, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
pub struct Sentence {
    key: String,
    #[serde(default, skip_serializing_if = "std::collections::BTreeMap::is_empty")]
    p: std::collections::BTreeMap<String, String>,
    #[serde(default, skip_serializing_if = "std::collections::BTreeMap::is_empty")]
    t: std::collections::BTreeMap<String, String>,
    /// Parameters that are words of ours: the key of each (`{state}` = `security.detail.on`).
    #[serde(default, skip_serializing_if = "std::collections::BTreeMap::is_empty")]
    w: std::collections::BTreeMap<String, String>,
    /// Parameters that are lengths of time, in minutes (`display_time::duration`).
    #[serde(default, skip_serializing_if = "std::collections::BTreeMap::is_empty")]
    d: std::collections::BTreeMap<String, i64>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    n: Option<i64>,
}

impl Sentence {
    fn new(key: &str, params: &[(&str, &str)]) -> Sentence {
        Sentence {
            key: key.to_string(),
            p: params
                .iter()
                .map(|(name, value)| (name.to_string(), value.to_string()))
                .collect(),
            t: Default::default(),
            w: Default::default(),
            d: Default::default(),
            n: None,
        }
    }

    /// The sentence in `zone`'s language, its instants in `zone` at `now`.
    fn worded(
        &self,
        zone: &crate::display_time::Zone,
        now: chrono::DateTime<chrono::Utc>,
    ) -> String {
        let times: Vec<(String, String)> = self
            .t
            .iter()
            .map(|(name, stored)| {
                let told = crate::display_time::parse_db_utc(stored)
                    .map(|t| zone.at(t, now))
                    .unwrap_or_else(|| stored.clone());
                (name.clone(), told)
            })
            .collect();
        let words: Vec<(String, String)> = self
            .w
            .iter()
            .map(|(name, key)| (name.clone(), crate::i18n::t(zone.lang(), key).to_string()))
            .collect();
        let durations: Vec<(String, String)> = self
            .d
            .iter()
            .map(|(name, minutes)| {
                (
                    name.clone(),
                    crate::display_time::duration(zone.lang(), *minutes),
                )
            })
            .collect();
        let params: Vec<(&str, &str)> = self
            .p
            .iter()
            .map(|(name, value)| (name.as_str(), value.as_str()))
            .chain(
                times
                    .iter()
                    .map(|(name, value)| (name.as_str(), value.as_str())),
            )
            .chain(
                words
                    .iter()
                    .map(|(name, value)| (name.as_str(), value.as_str())),
            )
            .chain(
                durations
                    .iter()
                    .map(|(name, value)| (name.as_str(), value.as_str())),
            )
            .collect();
        match self.n {
            Some(n) => crate::i18n::t_count(zone.lang(), &self.key, n, &params),
            None => crate::i18n::t_params(zone.lang(), &self.key, &params),
        }
    }
}

impl Detail {
    /// A sentence: `key` with `params` (user data and identifiers; a word of ours is its own key).
    pub fn words(key: &str, params: &[(&str, &str)]) -> Detail {
        Detail::default().and(key, params)
    }

    /// A counted sentence: `key.one`/`key.other` with `{count}` = `n` (and `params`).
    pub fn counted(key: &str, n: i64, params: &[(&str, &str)]) -> Detail {
        let mut detail = Detail::words(key, params);
        detail.words.last_mut().expect("just added").n = Some(n);
        detail
    }

    /// Identifiers only, shown as typed.
    pub fn facts(text: impl AsRef<str>) -> Detail {
        Detail {
            facts: Some(text.as_ref().to_string()),
            words: Vec::new(),
        }
    }

    /// Another sentence after the last.
    pub fn and(mut self, key: &str, params: &[(&str, &str)]) -> Detail {
        self.words.push(Sentence::new(key, params));
        self
    }

    /// Another, counted sentence after the last ([Detail::counted]).
    pub fn and_counted(mut self, key: &str, n: i64, params: &[(&str, &str)]) -> Detail {
        let mut sentence = Sentence::new(key, params);
        sentence.n = Some(n);
        self.words.push(sentence);
        self
    }

    /// A word of ours as the last sentence's `{name}`: `key`'s text in the reader's language
    /// ("on"/"off", a place). A parameter needs a sentence: on a [Detail] without one it is lost
    /// (a bug at the call, caught in a debug build).
    pub fn word(mut self, name: &str, key: &str) -> Detail {
        debug_assert!(
            !self.words.is_empty(),
            "a word for no sentence: {name} = {key}"
        );
        if let Some(last) = self.words.last_mut() {
            last.w.insert(name.to_string(), key.to_string());
        }
        self
    }

    /// A length of time as the last sentence's `{name}`, told as "1 h 30 min" (nb "1 t 30 min").
    /// Needs a sentence, as [Detail::word].
    pub fn duration(mut self, name: &str, minutes: i64) -> Detail {
        debug_assert!(!self.words.is_empty(), "a duration for no sentence: {name}");
        if let Some(last) = self.words.last_mut() {
            last.d.insert(name.to_string(), minutes);
        }
        self
    }

    /// An instant for the last sentence's `{name}`, told as a time in the reader's zone and
    /// language when read ("yesterday 21:40", "12 Oct 08:12"). Needs a sentence, as [Detail::word].
    pub fn at(mut self, name: &str, instant: chrono::DateTime<chrono::Utc>) -> Detail {
        debug_assert!(!self.words.is_empty(), "an instant for no sentence: {name}");
        if let Some(last) = self.words.last_mut() {
            last.t.insert(
                name.to_string(),
                instant.to_rfc3339_opts(chrono::SecondsFormat::Millis, true),
            );
        }
        self
    }

    /// The facts beside the words.
    pub fn with_facts(mut self, text: impl AsRef<str>) -> Detail {
        self.facts = Some(text.as_ref().to_string());
        self
    }

    /// The row's `detail` and `params` columns: the facts, and the words as one `{"key", "p"}`
    /// object or a list of them.
    pub fn columns(&self) -> (Option<String>, Option<String>) {
        let params = match self.words.as_slice() {
            [] => None,
            [one] => serde_json::to_string(one).ok(),
            many => serde_json::to_string(many).ok(),
        };
        (self.facts.clone(), params)
    }

    /// The words of a stored `params` (a sentence or a list of them) as the reader reads them, the
    /// sentences joined by a space; `None` for nothing stored or anything that isn't ours.
    pub fn worded(
        params: &str,
        zone: &crate::display_time::Zone,
        now: chrono::DateTime<chrono::Utc>,
    ) -> Option<String> {
        let sentences: Vec<Sentence> = match serde_json::from_str::<Sentence>(params) {
            Ok(one) => vec![one],
            Err(_) => serde_json::from_str(params).ok()?,
        };
        (!sentences.is_empty()).then(|| {
            sentences
                .iter()
                .map(|s| s.worded(zone, now))
                .collect::<Vec<_>>()
                .join(" ")
        })
    }

    /// A row as the log page shows it (and the tests read it): its words in `zone`'s language, then
    /// " · " and its facts when it has both (a refused request's words and its `METHOD /path`); the
    /// words alone, the facts alone, or an older row's prose; `None` for a row that says nothing
    /// besides its label. Two facts are ids kept for the greps and the phone lines' hourly budget,
    /// never shown raw beside words: `entry {id}` (every worded entry sentence names it) and
    /// `device {id}` - the words say the phone in the reader's language ("phone 3", "telefon 3"),
    /// and a sentence that doesn't (a phone's own line, a phone's Screen Time save) gets it as a
    /// lead, "phone 3: …".
    pub fn shown(
        detail: Option<&str>,
        params: Option<&str>,
        zone: &crate::display_time::Zone,
        now: chrono::DateTime<chrono::Utc>,
    ) -> Option<String> {
        let facts = detail.map(str::trim).filter(|d| !d.is_empty());
        let words = params.and_then(|p| Detail::worded(p, zone, now));
        match (words, facts) {
            (Some(words), Some(facts)) => Some(match id_fact(facts) {
                Some(("entry", _)) => words,
                Some(("device", id)) => {
                    let lang = zone.lang();
                    let phone =
                        crate::i18n::t_params(lang, "security.detail.phone", &[("device", id)]);
                    if names(&words, &phone) {
                        words
                    } else {
                        crate::i18n::t_params(
                            lang,
                            "security.detail.on_phone",
                            &[("device", id), ("words", &words)],
                        )
                    }
                }
                _ => format!("{words} · {facts}"),
            }),
            (Some(words), None) => Some(words),
            (None, facts) => facts.map(str::to_string),
        }
    }

    /// A stored text that is either our words (a sentence's JSON, [Detail::columns]) or prose from
    /// before design 70, shown as stored - `banned_ips.reason`, an older row's `detail`.
    pub fn worded_or_stored(
        stored: &str,
        zone: &crate::display_time::Zone,
        now: chrono::DateTime<chrono::Utc>,
    ) -> String {
        Detail::worded(stored, zone, now).unwrap_or_else(|| stored.to_string())
    }
}

/// A fact that is a bare id, `device 3` or `entry 12`: `(kind, id)`.
fn id_fact(facts: &str) -> Option<(&str, &str)> {
    let (kind, id) = facts.split_once(' ')?;
    (matches!(kind, "device" | "entry") && !id.is_empty() && id.bytes().all(|b| b.is_ascii_digit()))
        .then_some((kind, id))
}

/// `words` names `phone` ("phone 3") as a whole: not "phone 31".
fn names(words: &str, phone: &str) -> bool {
    words.match_indices(phone).any(|(at, _)| {
        !words[at + phone.len()..]
            .chars()
            .next()
            .is_some_and(|c| c.is_ascii_digit())
    })
}

/// A [Detail]'s words in English, UTC, now - what a unit test reads.
#[cfg(test)]
pub fn worded_test(detail: &Detail) -> String {
    worded_in(detail, crate::i18n::Lang::En)
}

/// A [Detail]'s words in `lang`, UTC, now.
#[cfg(test)]
pub fn worded_in(detail: &Detail, lang: crate::i18n::Lang) -> String {
    let zone = crate::display_time::Zone::utc_fallback().in_lang(lang);
    let (_, params) = detail.columns();
    Detail::worded(&params.expect("words"), &zone, chrono::Utc::now()).expect("worded")
}

/// One row of the security log: its kind (the label is `security.event.<kind>`, `admin::event_label`),
/// who and from where, and what it says ([Detail]).
pub async fn record_security_event(
    db: &sqlx::SqlitePool,
    event_type: &str,
    username: Option<&str>,
    ip: Option<&str>,
    detail: Option<Detail>,
) {
    let (facts, params) = detail.map(|d| d.columns()).unwrap_or_default();
    sqlx::query(
        "INSERT INTO security_events (event_type, username, ip_address, detail, params) \
         VALUES (?, ?, ?, ?, ?)",
    )
    .bind(event_type)
    .bind(username)
    .bind(ip)
    .bind(facts)
    .bind(params)
    .execute(db)
    .await
    .ok();
}

/// The security-log lines one phone's reports may write in a rolling hour (security review L1).
pub const PHONE_LINES_AN_HOUR: i64 = 60;

/// Declares [PhoneLine] from one list, so its variants, [PhoneLine::ALL], the wire name and the
/// window can't drift apart.
macro_rules! phone_lines {
    ($($(#[$doc:meta])* $variant:ident = $wire:literal, $days:expr;)*) => {
        /// The kinds of line a phone's reports write ([record_phone_event]), each with how long the
        /// log keeps it (security review P7: what the kid's phone did goes with the data it
        /// describes, never the sign-ins' 180 days; `retention::prune`).
        #[derive(Clone, Copy, Debug, PartialEq, Eq)]
        pub enum PhoneLine {
            $($(#[$doc])* $variant,)*
        }

        impl PhoneLine {
            pub const ALL: &[PhoneLine] = &[$(PhoneLine::$variant,)*];

            /// The `security_events.event_type`.
            pub fn wire(self) -> &'static str {
                match self {
                    $(PhoneLine::$variant => $wire,)*
                }
            }

            /// Days the security log keeps the line.
            pub fn keep_days(self) -> i64 {
                match self {
                    $(PhoneLine::$variant => $days,)*
                }
            }
        }
    };
}

phone_lines! {
    AppInstalled = "app_installed", crate::retention::PHONE_LOG_DAYS;
    PlayInstallMode = "play_install_mode", crate::retention::PHONE_LOG_DAYS;
    BootUnseen = "boot_unseen", crate::retention::PHONE_LOG_DAYS;
    AppBlocked = "phone_app_blocked", crate::retention::PHONE_LOG_DAYS;
    ForeignAccess = "phone_foreign_access", crate::retention::PHONE_LOG_DAYS;
    RelockFindings = "phone_relock_findings", crate::retention::PHONE_LOG_DAYS;
    MockLocationIgnored = "mock_location_ignored", crate::retention::PHONE_LOG_DAYS;
    /// The line in place of an hour's lines past [PHONE_LINES_AN_HOUR].
    LinesDropped = "phone_lines_dropped", crate::retention::PHONE_LOG_DAYS;
    /// With the parent-PIN events they describe.
    ParentPinUsed = "parent_pin_used", crate::parent_pin::PIN_EVENT_DAYS;
    ParentPinLockout = "parent_pin_lockout", crate::parent_pin::PIN_EVENT_DAYS;
    /// The parent's Storytel credential handed to a phone: a sign-in's 180 days.
    StorytelLoginSent = "storytel_login_sent", crate::retention::SECURITY_EVENT_DAYS;
}

/// A line a phone's report raised - a PIN use, a start without the launcher, an app installed, a
/// relock's findings -, within [PHONE_LINES_AN_HOUR] a rolling hour per phone (security review L1:
/// a leaked token or a rooted phone can't bury the sign-ins under rows kept 180 days). `line`
/// starts with `device {device_id}:` or `device {device_id},`, as every phone line does, and has
/// neither a username nor an address - what the hour's count matches, so a parent's lines about
/// the phone (which carry the parent's name) never use up its budget (QA L-a). The line over the
/// budget is one `phone_lines_dropped` row; the rest of that hour's lines are not written (the
/// phone's own pages keep what they show). `true` when `line` itself was written. On the caller's
/// connection, so a caller's transaction holds it.
pub async fn record_phone_event(
    conn: &mut sqlx::SqliteConnection,
    device_id: i64,
    kind: PhoneLine,
    line: Detail,
) -> Result<bool, sqlx::Error> {
    // The fact every phone line carries, which the hour's count matches - `device {id}` alone
    // since design 70 (the words are in `params`), `device {id}:`/`device {id},` before it.
    let fact = format!("device {device_id}");
    let written: i64 = sqlx::query_scalar(
        "SELECT COUNT(*) FROM security_events WHERE created_at > datetime('now', '-1 hour') \
         AND username IS NULL AND ip_address IS NULL \
         AND (detail = ?1 OR detail LIKE ?1 || ':%' OR detail LIKE ?1 || ',%')",
    )
    .bind(&fact)
    .fetch_one(&mut *conn)
    .await?;
    let (kind, line) = match written {
        n if n < PHONE_LINES_AN_HOUR => (kind, line),
        n if n == PHONE_LINES_AN_HOUR => (
            PhoneLine::LinesDropped,
            Detail::words(
                "security.detail.phone_lines_dropped",
                &[
                    ("device", &device_id.to_string()),
                    ("max", &PHONE_LINES_AN_HOUR.to_string()),
                ],
            ),
        ),
        _ => return Ok(false),
    };
    let own = kind != PhoneLine::LinesDropped;
    // A phone line's facts are the server's `fact` alone (H3): a caller's `with_facts` has no
    // place here and is dropped - said in a debug build, never written.
    debug_assert!(
        line.facts.is_none(),
        "a phone line's facts are `device {{id}}`, never the caller's"
    );
    let (_, params) = line.columns();
    sqlx::query("INSERT INTO security_events (event_type, detail, params) VALUES (?, ?, ?)")
        .bind(kind.wire())
        .bind(fact)
        .bind(params)
        .execute(&mut *conn)
        .await?;
    Ok(own)
}

/// [record_phone_event] on its own connection; a failure is logged.
pub async fn record_phone_event_logged(
    db: &sqlx::SqlitePool,
    device_id: i64,
    kind: PhoneLine,
    line: Detail,
) {
    let written = match db.acquire().await {
        Ok(mut conn) => record_phone_event(&mut conn, device_id, kind, line).await,
        Err(err) => Err(err),
    };
    if let Err(err) = written {
        tracing::error!(device_id, kind = kind.wire(), %err, "can't write a phone's security-log line");
    }
}

pub async fn is_ip_banned(db: &sqlx::SqlitePool, ip: &str) -> bool {
    sqlx::query_scalar::<_, bool>(
        "SELECT EXISTS(SELECT 1 FROM banned_ips WHERE ip_address = ? AND banned_until > datetime('now'))",
    )
    .bind(ip)
    .fetch_one(db)
    .await
    .unwrap_or(false)
}

/// The security events that count toward an address's ban as failures: a wrong password or code,
/// a refused passkey sign-in.
pub const BAN_FAILURES: &str =
    "('login_failed', 'totp_failed', 'passkey_refused', 'passkey_cloned')";

/// Counts recent failed attempts from this IP and bans it if over threshold. A passkey sign-in
/// started and not ended (`passkey_started`, design 74 D1.4: one per signed-out session's options,
/// taken back when its ceremony ends) counts too, so the challenge store can't be flooded - and the
/// ban's words say how many of each (qa-passkeys-1 B-M3: never "16 failed sign-ins" for page loads).
/// Call after recording a failed-login-type security event.
pub async fn check_and_ban_ip_if_needed(db: &sqlx::SqlitePool, ip: &str) {
    let (failures, started): (i64, i64) = sqlx::query_as(&format!(
        "SELECT COALESCE(SUM(event_type IN {BAN_FAILURES}), 0), \
                COALESCE(SUM(event_type = 'passkey_started'), 0) \
         FROM security_events \
         WHERE ip_address = ? AND (event_type IN {BAN_FAILURES} OR event_type = 'passkey_started') \
         AND created_at > datetime('now', ?)"
    ))
    .bind(ip)
    .bind(format!("-{IP_FAILURE_WINDOW_MINUTES} minutes"))
    .fetch_one(db)
    .await
    .unwrap_or((0, 0));

    if failures + started >= MAX_FAILED_PER_IP {
        let (count, started_text, minutes) = (
            failures.to_string(),
            started.to_string(),
            IP_FAILURE_WINDOW_MINUTES.to_string(),
        );
        let (reason, line) = if started == 0 {
            (
                Detail::words(
                    "security.detail.ban.failed_logins",
                    &[("count", &count), ("minutes", &minutes)],
                ),
                Detail::words("security.detail.ip_banned", &[("count", &count)]),
            )
        } else {
            (
                Detail::words(
                    "security.detail.ban.failed_and_started",
                    &[
                        ("count", &count),
                        ("started", &started_text),
                        ("minutes", &minutes),
                    ],
                ),
                Detail::words(
                    "security.detail.ip_banned_started",
                    &[("count", &count), ("started", &started_text)],
                ),
            )
        };
        sqlx::query(
            "INSERT INTO banned_ips (ip_address, banned_until, reason) VALUES (?, datetime('now', ?), ?) \
             ON CONFLICT(ip_address) DO UPDATE SET banned_until = excluded.banned_until, reason = excluded.reason",
        )
        .bind(ip)
        .bind(format!("+{IP_BAN_HOURS} hours"))
        // The reason as words (design 70 §4): a sentence's JSON, worded when Blocked Addresses is
        // read; a reason from before is prose, shown as stored.
        .bind(reason.columns().1)
        .execute(db)
        .await
        .ok();

        record_security_event(db, "ip_banned", None, Some(ip), Some(line)).await;
    }
}

/// Builds a TOTP object for a given admin from a stored (or freshly
/// generated) base32 secret. All accounts share the same algorithm/digits/
/// step, so the secret alone is enough to reconstruct it. The username is only the
/// authenticator's label, where totp-rs refuses a `:` (design 62 §2: it panicked the 2FA setup of
/// such a name); a code comes from the secret alone, so the `_` changes no existing entry.
pub fn totp_for_secret(secret_base32: &str, username: &str) -> TOTP {
    let secret = Secret::Encoded(secret_base32.to_string());
    TOTP::new(
        Algorithm::SHA1,
        6,
        1,
        30,
        secret
            .to_bytes()
            .expect("stored secret should be valid base32"),
        Some("Kids Device MDM".to_string()),
        username.replace(':', "_"),
    )
    .expect("fixed TOTP parameters should always be valid")
}

/// Rounds recommended (OWASP, 2023) as a PBKDF2-HMAC-SHA256 minimum.
const PIN_PBKDF2_ROUNDS: u32 = 210_000;
const PIN_SALT_LEN: usize = 16;
const PIN_HASH_LEN: usize = 32;

/// Hashes a device's offline-override PIN (and, since handy step 10, the kid's lock-screen PIN)
/// with PBKDF2-HMAC-SHA256 and a fresh random salt, returning `(hash_hex, salt_hex)`.
/// Deliberately not the Argon2 used for admin passwords: this hash+salt pair gets shipped down to
/// the device in its policy payload so the launcher can verify a locally-entered PIN with zero
/// network at all, and PBKDF2 is available on Android via the built-in
/// `javax.crypto.SecretKeyFactory` with no extra client dependency, unlike Argon2. The launcher's
/// `server/PinHash.kt` must use the same parameters (`pin_hash_shared_vector` pins them).
pub fn hash_pin(pin: &str) -> (String, String) {
    let mut salt = [0u8; PIN_SALT_LEN];
    OsRng.fill_bytes(&mut salt);
    (hash_pin_with_salt(pin, &salt), hex::encode(salt))
}

/// [hash_pin] with a given salt (hex of the hash).
pub fn hash_pin_with_salt(pin: &str, salt: &[u8]) -> String {
    let mut hash = [0u8; PIN_HASH_LEN];
    pbkdf2_hmac::<Sha256>(pin.as_bytes(), salt, PIN_PBKDF2_ROUNDS, &mut hash);
    hex::encode(hash)
}

/// Whether `pin` matches a stored [hash_pin] pair. Only used to keep the kid's PIN and the
/// override PIN apart (handy step 10: one must never verify against the other's hash); an
/// unparseable pair never matches. Constant-time comparison.
pub fn verify_pin(pin: &str, hash_hex: &str, salt_hex: &str) -> bool {
    let (Ok(salt), Ok(expected)) = (hex::decode(salt_hex), hex::decode(hash_hex)) else {
        return false;
    };
    if expected.len() != PIN_HASH_LEN {
        return false;
    }
    let mut hash = [0u8; PIN_HASH_LEN];
    pbkdf2_hmac::<Sha256>(pin.as_bytes(), &salt, PIN_PBKDF2_ROUNDS, &mut hash);
    hash.iter()
        .zip(expected.iter())
        .fold(0u8, |acc, (a, b)| acc | (a ^ b))
        == 0
}

pub fn generate_totp_secret_base32() -> String {
    match Secret::generate_secret().to_encoded() {
        Secret::Encoded(s) => s,
        Secret::Raw(_) => unreachable!("to_encoded() always returns the Encoded variant"),
    }
}

/// Plain read of whatever version install.sh last stamped into
/// `data/watcher_version`, purely for display ("System helper scripts:
/// vX.Y.Z"). Whether that's actually a *problem* is answered separately by
/// `watcher_needs_update` below.
pub async fn installed_watcher_version() -> Option<String> {
    let installed = tokio::fs::read_to_string("data/watcher_version")
        .await
        .ok()?;
    let installed = installed.trim();
    if installed.is_empty() {
        None
    } else {
        Some(installed.to_string())
    }
}

/// The watcher "schema" version: a small counter, independent of the app's
/// own release version, bumped only when install.sh's root-side scripts
/// (actions.sh/watcher.sh/scheduler.sh/backup_sync.sh) actually gain or
/// change a privileged action - see board-game-tracker's CLAUDE.md for why
/// comparing raw version strings directly was the wrong check (it fired on
/// every single app release regardless of whether the watcher itself had
/// changed at all).
/// 6: actions.sh fetches update.sh from the monorepo's server/deploy/.
pub const REQUIRED_WATCHER_SCHEMA: u32 = 6;

async fn installed_watcher_schema() -> Option<u32> {
    let raw = tokio::fs::read_to_string("data/watcher_schema_version")
        .await
        .ok()?;
    raw.trim().parse::<u32>().ok()
}

/// Whether the installed watcher is missing a privileged action the app
/// might need to request. An unknown schema (no marker at all - a fresh
/// install that hasn't run install.sh's watcher setup yet) is treated as
/// needing an update too, since it can't be confirmed safe either way.
pub async fn watcher_needs_update() -> bool {
    installed_watcher_schema()
        .await
        .is_none_or(|schema| schema < REQUIRED_WATCHER_SCHEMA)
}

/// Re-run hint shown wherever the watcher version is displayed - the
/// root-side watcher/scheduler scripts are only ever refreshed by re-running
/// install.sh (the in-app "Update now" button only swaps the app binary). `repo` is
/// `config::ForkConfig::server_release_repo`. The `KPS_REPO=` prefix keeps a re-run installing
/// from the same repo even when it isn't install.sh's built-in default. Downloaded to a file and
/// then run, never piped into a root shell (security review L10), from `main` - the only branch.
pub fn reinstall_hint(repo: &str) -> String {
    format!(
        "curl -fsSLo kps-install.sh https://raw.githubusercontent.com/{repo}/main/server/deploy/install.sh && sudo KPS_REPO={repo} bash kps-install.sh"
    )
}

/// Creates the first admin account if `admin_users` is empty - there's no self-registration, so
/// this is the only way to get a first account onto a fresh install. The name is
/// `ADMIN_USERNAME` (default "admin"); the password `ADMIN_PASSWORD`, else the file
/// `ADMIN_PASSWORD_FILE` names, else a generated one written to `data/initial-admin-password`
/// (0600, design 22 §6) - the log names the file, never the password. Either way it must be
/// changed at the first sign-in.
pub async fn bootstrap_admin(db: &sqlx::SqlitePool, data_dir: &std::path::Path) {
    let count: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM admin_users")
        .fetch_one(db)
        .await
        .expect("failed to count admin_users");
    if count > 0 {
        return;
    }

    let username = match bootstrap_username(std::env::var("ADMIN_USERNAME").ok().as_deref()) {
        Ok(name) => name,
        Err(err) => {
            tracing::error!("{err}");
            std::process::exit(1);
        }
    };
    let configured = std::env::var("ADMIN_PASSWORD")
        .ok()
        .filter(|v| !v.is_empty());
    let from_file = || -> Option<String> {
        let path = std::env::var("ADMIN_PASSWORD_FILE")
            .ok()
            .filter(|v| !v.trim().is_empty())?;
        match std::fs::read_to_string(path.trim()) {
            Ok(password) if !password.trim().is_empty() => Some(password.trim().to_string()),
            Ok(_) => {
                tracing::error!("ADMIN_PASSWORD_FILE {path} is empty");
                std::process::exit(1);
            }
            Err(err) => {
                tracing::error!("ADMIN_PASSWORD_FILE {path} can't be read: {err}");
                std::process::exit(1);
            }
        }
    };
    let (password, generated) = match configured.or_else(from_file) {
        Some(password) => (password, None),
        None => match crate::startup::initial_admin_password(data_dir) {
            Ok((password, path)) => (password, Some(path)),
            Err(err) => {
                tracing::error!("no ADMIN_PASSWORD, and {err}");
                std::process::exit(1);
            }
        },
    };

    let hash = hash_password(&password);
    sqlx::query(
        "INSERT INTO admin_users (username, password_hash, must_change_password) VALUES (?, ?, 1)",
    )
    .bind(&username)
    .bind(&hash)
    .execute(db)
    .await
    .expect("failed to create bootstrap admin");

    match generated {
        Some(path) => tracing::warn!(
            "created the first admin account {username:?}; its password is in {} - sign in, \
             change it (the file is deleted then)",
            path.display()
        ),
        None => tracing::info!("bootstrapped initial admin account: {username}"),
    }
}

#[derive(Clone)]
pub struct CurrentAdmin(pub AdminUser);

/// Why a response sends the browser to the sign-in page (2026-10-25, PLAN "PWA shows the login page
/// after a Tailscale outage, though the session is valid"): put on the redirect as a response
/// extension ([sign_in_redirect]) and read by [log_sign_in_redirects] for its one log line. The
/// words never hold a cookie's value.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum SignInWhy {
    /// The request carried no session cookie at all (a SameSite=Strict cookie isn't sent on a
    /// navigation the browser counts as cross-site).
    NoCookie,
    /// A session cookie whose value isn't a session id.
    CookieNotASession,
    /// The cookie's session has no row: never made, flushed, or expired and deleted since (the
    /// store deletes expired rows hourly).
    NoSessionRow,
    /// The cookie's session row is there but past its expiry.
    Expired,
    /// The session holds no account: a sign-in that wasn't finished.
    NotSignedIn,
    /// The account is removed, or its one-time password's onboarding ran out (design 62 §2).
    AccountGone,
    /// The session's epoch isn't the account's: Sign Out Everywhere, a password change, a 2FA
    /// reset moved it on.
    Epoch,
    /// The session store couldn't be read.
    StoreError,
    /// The account's row couldn't be read.
    DbError,
    /// A form that asks for the password again took its third wrong one in a row
    /// (`admin_auth::signed_out_response`).
    Reauth,
    /// Settings' Sign Out.
    SignOut,
    /// The sign-in is older than `SESSION_MAX_DAYS` (security review L6).
    TooOld,
}

impl SignInWhy {
    pub fn as_str(self) -> &'static str {
        match self {
            SignInWhy::NoCookie => "no session cookie",
            SignInWhy::CookieNotASession => "the cookie isn't a session id",
            SignInWhy::NoSessionRow => {
                "no session row (never made, signed out, or expired and deleted)"
            }
            SignInWhy::Expired => "the session expired",
            SignInWhy::NotSignedIn => "the session has no account (a sign-in not finished)",
            SignInWhy::AccountGone => "the account is removed or its one-time password ran out",
            SignInWhy::Epoch => {
                "the session's epoch isn't the account's (signed out everywhere, a password \
                 change, a 2FA reset)"
            }
            SignInWhy::StoreError => "the session store couldn't be read",
            SignInWhy::DbError => "the account couldn't be read",
            SignInWhy::Reauth => {
                "re-auth: the third wrong password in a row signed every session out"
            }
            SignInWhy::SignOut => "Sign Out",
            SignInWhy::TooOld => "the sign-in is older than SESSION_MAX_DAYS",
        }
    }
}

/// `/login` as a redirect that says why ([SignInWhy], for [log_sign_in_redirects]).
pub fn sign_in_redirect(why: SignInWhy) -> Response {
    let mut response = Redirect::to("/login").into_response();
    response.extensions_mut().insert(why);
    response
}

/// The admin session cookie's name ([session_layer]): `__Host-handy`, or `handy` with
/// `INSECURE_COOKIES`. `AppState::session_cookie` holds the one this server uses.
pub fn session_cookie_name(insecure: bool) -> &'static str {
    if insecure { "handy" } else { "__Host-handy" }
}

/// Whether the request carries the admin session cookie `name` (this server's,
/// `AppState::session_cookie` - a leftover cookie of the other name reads as none, qa-pwa-fixes-d
/// N-1), whatever its value.
pub fn has_session_cookie(headers: &axum::http::HeaderMap, name: &str) -> bool {
    headers
        .get_all(axum::http::header::COOKIE)
        .iter()
        .filter_map(|value| value.to_str().ok())
        .flat_map(|value| value.split(';'))
        .filter_map(|pair| pair.split_once('=').map(|(cookie, _)| cookie.trim()))
        .any(|cookie| cookie == name)
}

/// The signed-in admin, if the session's epoch is still the account's (a password change, a 2FA
/// reset or "Sign out everywhere" moved it on - design 22 QA a), the account isn't removed and a
/// one-time password's onboarding hasn't run out (design 62 §2; the removal is read here too, not
/// only through its epoch bump - qa-62-revision-check N1). Otherwise why not
/// ([SignInWhy::NotSignedIn] for a session without an account; [why_signed_out] tells the
/// cookie's cases apart).
pub(crate) async fn load_active_admin(
    state: &AppState,
    session: &Session,
) -> Result<AdminUser, SignInWhy> {
    let admin_id: i64 = session
        .get("admin_id")
        .await
        .map_err(|_| SignInWhy::StoreError)?
        .ok_or(SignInWhy::NotSignedIn)?;
    let epoch: i64 = session
        .get(crate::admin_auth::EPOCH_KEY)
        .await
        .map_err(|_| SignInWhy::StoreError)?
        .ok_or(SignInWhy::Epoch)?;
    let admin = sqlx::query_as::<_, AdminUser>(concat!(
        crate::admin_select!(),
        " WHERE id = ? AND removed_at IS NULL \
         AND (invite_expires_at IS NULL OR invite_expires_at > datetime('now'))"
    ))
    .bind(admin_id)
    .fetch_optional(&state.db)
    .await
    .map_err(|_| SignInWhy::DbError)?
    .ok_or(SignInWhy::AccountGone)?;
    if admin.session_epoch != epoch {
        return Err(SignInWhy::Epoch);
    }
    // The absolute lifetime (security review L6): however often it is used, a session ends
    // `session_max_days` after its sign-in. A session from before 0.55.0 has no sign-in time: its
    // clock starts now (one sign-in again after the cap, not one at the upgrade).
    let now = crate::admin_auth::now_secs();
    let signed_in_at: Option<u64> = session
        .get(crate::admin_auth::SIGNED_IN_AT_KEY)
        .await
        .map_err(|_| SignInWhy::StoreError)?;
    match signed_in_at {
        None => session
            .insert(crate::admin_auth::SIGNED_IN_AT_KEY, now)
            .await
            .map_err(|_| SignInWhy::StoreError)?,
        Some(at) if session_too_old(at, now, state.net.session_max_days) => {
            return Err(SignInWhy::TooOld);
        }
        Some(_) => {}
    }
    Ok(admin)
}

/// A sign-in at `signed_in_at` (Unix seconds) is past `max_days` at `now`.
pub fn session_too_old(signed_in_at: u64, now: u64, max_days: i64) -> bool {
    let max_secs = u64::try_from(max_days).unwrap_or(0).saturating_mul(86_400);
    now.saturating_sub(signed_in_at) > max_secs
}

/// [load_active_admin]'s [SignInWhy::NotSignedIn] told apart by the cookie: none, one that isn't
/// a session id, one whose row is gone or expired. `cookie_id` is the session's id before it was
/// read (the session layer drops it when its row isn't found). The id is only looked up, never
/// logged.
async fn why_signed_out(
    state: &AppState,
    session: &Session,
    cookie: bool,
    cookie_id: Option<tower_sessions::session::Id>,
    why: SignInWhy,
) -> SignInWhy {
    if why != SignInWhy::NotSignedIn {
        return why;
    }
    if !cookie {
        return SignInWhy::NoCookie;
    }
    let Some(id) = cookie_id else {
        return SignInWhy::CookieNotASession;
    };
    if session.id().is_some() {
        return SignInWhy::NotSignedIn;
    }
    // The store's load asks for `expiry_date > now`: a row still there is an expired one.
    match sqlx::query_scalar::<_, i64>("SELECT 1 FROM tower_sessions WHERE id = ?")
        .bind(id.to_string())
        .fetch_optional(&state.db)
        .await
    {
        Ok(Some(_)) => SignInWhy::Expired,
        Ok(None) => SignInWhy::NoSessionRow,
        Err(_) => SignInWhy::StoreError,
    }
}

/// The signed-in admin of a request, or why it is sent to the sign-in page.
async fn admin_or_why(
    state: &AppState,
    session: &Session,
    headers: &axum::http::HeaderMap,
) -> Result<AdminUser, SignInWhy> {
    let cookie_id = session.id();
    match load_active_admin(state, session).await {
        Ok(admin) => Ok(admin),
        Err(why) => {
            let cookie = has_session_cookie(headers, state.session_cookie);
            Err(why_signed_out(state, session, cookie, cookie_id, why).await)
        }
    }
}

/// A request header for the log: at most 20 characters, `-` when absent.
fn header_for_log(headers: &axum::http::HeaderMap, name: &str) -> String {
    headers
        .get(name)
        .and_then(|value| value.to_str().ok())
        .map_or_else(
            || "-".to_string(),
            |value| crate::audit::clip(value, 20).to_string(),
        )
}

/// One log line for every answer that sends the browser to `/login` (2026-10-25, PLAN "PWA shows
/// the login page after a Tailscale outage"): why ([SignInWhy] on the response; a sign-in step's
/// own redirect has none), the method and path (no query), whether a session cookie came, and the
/// browser's `Sec-Fetch-Site`/`Sec-Fetch-Mode` (a SameSite=Strict cookie stays home on a
/// cross-site navigation). Never a cookie's value. A warning when a session cookie came (not for
/// Sign Out), else info. A layer on the admin router, outside the session layer.
pub async fn log_sign_in_redirects(
    State(state): State<AppState>,
    request: Request,
    next: Next,
) -> Response {
    let cookie = has_session_cookie(request.headers(), state.session_cookie);
    let site = header_for_log(request.headers(), "sec-fetch-site");
    let mode = header_for_log(request.headers(), "sec-fetch-mode");
    let method = request.method().clone();
    let path = crate::audit::clip(request.uri().path(), 100).to_string();
    let response = next.run(request).await;
    let to_login = response.status().is_redirection()
        && response
            .headers()
            .get(axum::http::header::LOCATION)
            .and_then(|value| value.to_str().ok())
            .is_some_and(|to| to == "/login" || to.starts_with("/login?"));
    if !to_login {
        return response;
    }
    let why = response.extensions().get::<SignInWhy>().copied();
    let reason = why.map_or("a sign-in step", SignInWhy::as_str);
    if cookie && why != Some(SignInWhy::SignOut) {
        tracing::warn!(
            reason,
            %method,
            path,
            sec_fetch_site = site,
            sec_fetch_mode = mode,
            "sent to the sign-in page with a session cookie"
        );
    } else {
        tracing::info!(
            reason,
            %method,
            path,
            cookie,
            sec_fetch_site = site,
            sec_fetch_mode = mode,
            "sent to the sign-in page"
        );
    }
    response
}

/// `GET /api/session` (2026-10-25, `static/reconnect.js`): 204 while the request's session is a
/// signed-in, onboarded admin's (what [require_full_auth] lets through), else 401 - no body,
/// `no-store`, nothing written but a session from before 0.55.0's first sign-in time
/// (`load_active_admin`, security review L6; the session isn't flushed). The sign-in page asks it before it
/// stays on screen; each answer is logged with its reason.
pub async fn session_check(
    State(state): State<AppState>,
    session: Session,
    headers: axum::http::HeaderMap,
) -> Response {
    let cookie = has_session_cookie(&headers, state.session_cookie);
    let site = header_for_log(&headers, "sec-fetch-site");
    let (status, reason) = match admin_or_why(&state, &session, &headers).await {
        Ok(admin) if admin.ready() => (axum::http::StatusCode::NO_CONTENT, "signed in"),
        Ok(_) => (
            axum::http::StatusCode::UNAUTHORIZED,
            "the account's onboarding isn't finished",
        ),
        Err(why) => (axum::http::StatusCode::UNAUTHORIZED, why.as_str()),
    };
    tracing::info!(
        status = status.as_u16(),
        reason,
        cookie,
        sec_fetch_site = site,
        "session check"
    );
    (status, [(axum::http::header::CACHE_CONTROL, "no-store")]).into_response()
}

/// The admin session cookie (design 22 §3.2.2): `__Host-handy` (Secure, `Path=/`, no `Domain`,
/// HttpOnly, SameSite=Strict); with `INSECURE_COOKIES` (the emulator loop over plain http) a
/// plain `handy`, since browsers drop a `__Host-` cookie that isn't Secure. Sessions end after
/// `inactivity_days` without use, and `NetConfig::session_max_days` after their sign-in whatever
/// the use ([load_active_admin]).
pub fn session_layer(
    store: tower_sessions_sqlx_store::SqliteStore,
    insecure: bool,
    inactivity_days: i64,
) -> tower_sessions::SessionManagerLayer<tower_sessions_sqlx_store::SqliteStore> {
    tower_sessions::SessionManagerLayer::new(store)
        .with_name(session_cookie_name(insecure))
        .with_path("/")
        .with_http_only(true)
        .with_same_site(tower_sessions::cookie::SameSite::Strict)
        .with_secure(!insecure)
        .with_expiry(tower_sessions::Expiry::OnInactivity(
            tower_sessions::cookie::time::Duration::days(inactivity_days),
        ))
}

/// Requires a valid session, but does not enforce the forced-password-change
/// gate - used for the change-password page itself and logout, which must
/// stay reachable mid-onboarding.
pub async fn require_session(
    State(state): State<AppState>,
    session: Session,
    mut request: Request,
    next: Next,
) -> Response {
    match admin_or_why(&state, &session, request.headers()).await {
        Ok(admin) => {
            request.extensions_mut().insert(CurrentAdmin(admin));
            next.run(request).await
        }
        Err(why) => {
            session.flush().await.ok();
            sign_in_redirect(why)
        }
    }
}

/// Requires a logged-in admin who has completed the forced password change
/// and mandatory 2FA setup. Use this on every route in the actual admin UI. An account without a
/// language gets this request's `Accept-Language` one, once ([store_first_lang], design 70).
pub async fn require_full_auth(
    State(state): State<AppState>,
    session: Session,
    mut request: Request,
    next: Next,
) -> Response {
    match admin_or_why(&state, &session, request.headers()).await {
        Ok(admin) if admin.must_change_password => {
            Redirect::to("/auth/change-password").into_response()
        }
        // A passkey-only account has no TOTP by design (D2.2): never sent to set one up.
        Ok(admin) if !admin.totp_enabled && !admin.passkey_only => {
            Redirect::to("/auth/setup-2fa").into_response()
        }
        // Design 74 D2.2's one predicate: anything else that isn't ready (a one-time password's state on an account
        // that looks onboarded, a passkey-only account without a passkey - no statement leaves either) is no
        // session.
        Ok(admin) if !admin.ready() => {
            session.flush().await.ok();
            sign_in_redirect(SignInWhy::AccountGone)
        }
        Ok(mut admin) => {
            if admin.lang.is_none() {
                admin.lang = store_first_lang(
                    &state.db,
                    admin.id,
                    request.headers(),
                    state.config.pwa_default_lang,
                )
                .await;
            }
            // Past here `CurrentAdmin.lang` is always a language this server has: a value it can't read (a
            // rollback from a build with another language; kept in the row) or a failed store reads as the
            // header's, as the extractor would - so Account's select always has one selected (qa-s-i18n-1-code L3).
            if admin
                .lang
                .as_deref()
                .and_then(crate::i18n::Lang::from_code)
                .is_none()
            {
                admin.lang = Some(
                    crate::i18n::Lang::fallback(request.headers(), state.config.pwa_default_lang)
                        .code()
                        .to_string(),
                );
            }
            request.extensions_mut().insert(CurrentAdmin(admin));
            next.run(request).await
        }
        Err(why) => {
            session.flush().await.ok();
            sign_in_redirect(why)
        }
    }
}

/// An account's language before the parent chose one (design 70 §3, D2): the first signed-in request's
/// language - `PWA_DEFAULT_LANG` when set, else `Accept-Language` (never `?lang=`, which is that request's alone) - written once - a later request, or one
/// racing it, finds the column set. Today's sessions and push subscriptions are covered at their next page.
async fn store_first_lang(
    db: &sqlx::SqlitePool,
    id: i64,
    headers: &axum::http::HeaderMap,
    default: Option<crate::i18n::Lang>,
) -> Option<String> {
    let code = crate::i18n::Lang::fallback(headers, default).code();
    match sqlx::query("UPDATE admin_users SET lang = ? WHERE id = ? AND lang IS NULL")
        .bind(code)
        .bind(id)
        .execute(db)
        .await
    {
        Ok(_) => Some(code.to_string()),
        Err(err) => {
            tracing::warn!(%err, "couldn't store the account's language");
            None
        }
    }
}

/// The device a request's bearer token belongs to. Only the id: the token check is in memory
/// ([TokenIndex]), and handlers read what they need.
#[derive(Clone, Copy, Debug)]
pub struct DeviceRef {
    pub id: i64,
}

#[derive(Clone)]
pub struct AuthedDevice(pub DeviceRef);

/// `token_hash -> device id` for every enrolled phone (design 22 §3.1), so checking a token never
/// touches the database: a failed check costs one SHA-256 and one hash probe. Rebuilt at start
/// ([TokenIndex::reload]) and after every write to `devices.token_hash` - enroll, revoke, delete
/// ([TokenIndex::refresh]).
#[derive(Default)]
pub struct TokenIndex {
    map: std::sync::RwLock<std::collections::HashMap<String, i64>>,
    /// Reloads run one at a time, each reading after its own write, so the last one to finish
    /// holds every write.
    reload: tokio::sync::Mutex<()>,
}

impl TokenIndex {
    pub fn lookup(&self, token: &str) -> Option<i64> {
        let hash = hash_token(token);
        self.map
            .read()
            .unwrap_or_else(|e| e.into_inner())
            .get(&hash)
            .copied()
    }

    /// Reads every token hash from the database.
    pub async fn reload(&self, db: &sqlx::SqlitePool) -> Result<(), sqlx::Error> {
        let _one_at_a_time = self.reload.lock().await;
        let rows: Vec<(String, i64)> =
            sqlx::query_as("SELECT token_hash, id FROM devices WHERE token_hash IS NOT NULL")
                .fetch_all(db)
                .await?;
        *self.map.write().unwrap_or_else(|e| e.into_inner()) = rows.into_iter().collect();
        Ok(())
    }

    /// After a write to one device's `token_hash` (`token_hash` = what was written, `None` =
    /// cleared or deleted): [TokenIndex::reload], and if the database can't be read, the same
    /// change applied by hand - a phone must never keep a revoked token, nor be refused the one it
    /// was just given.
    pub async fn refresh(&self, db: &sqlx::SqlitePool, device_id: i64, token_hash: Option<&str>) {
        if let Err(err) = self.reload(db).await {
            tracing::error!(device_id, %err, "couldn't reload the device tokens - patching");
            let mut map = self.map.write().unwrap_or_else(|e| e.into_inner());
            map.retain(|_, id| *id != device_id);
            if let Some(hash) = token_hash {
                map.insert(hash.to_string(), device_id);
            }
        }
    }
}

fn too_many(refused: crate::limits::Refused) -> Response {
    (
        axum::http::StatusCode::TOO_MANY_REQUESTS,
        [(
            axum::http::header::RETRY_AFTER,
            refused.retry_after_secs.to_string(),
        )],
    )
        .into_response()
}

/// Bearer-token auth for the device-facing API (`/api/devices/*`, excluding enroll) - completely
/// separate from the admin session system above, since a kid's phone is never an admin session.
///
/// Design 22 §3.1, in this order:
/// - the token is looked up in memory ([TokenIndex]);
/// - a bad or missing one counts against the client's failure bucket (`limits`; 429 once it's
///   full, else 401) and a throttled `device_auth_failed` event;
/// - a valid one never touches that bucket: it takes from the device's own bucket, then an
///   in-flight slot for its route class, held until the response body is done (an SSE stream or
///   a download holds it for its whole life).
pub async fn require_device_token(
    State(state): State<AppState>,
    mut request: Request,
    next: Next,
) -> Response {
    use crate::limits;

    let client =
        crate::gate::peer_of(&request).map(|peer| state.net.client(request.headers(), peer.ip()));
    if let Some(client) = &client {
        state.limits.observe_peer(client);
    }
    let token = request
        .headers()
        .get(axum::http::header::AUTHORIZATION)
        .and_then(|v| v.to_str().ok())
        .and_then(|v| v.strip_prefix("Bearer "));
    let device_id = token.and_then(|token| state.tokens.lookup(token));

    let Some(device_id) = device_id else {
        let key = client.map(|c| c.key()).unwrap_or_default();
        if let Some(refused) = state.limits.ip_blocked(&key) {
            return too_many(refused);
        }
        let blocked = state.limits.ip_failed(&key);
        let what = if token.is_some() {
            "invalid device token"
        } else {
            "missing bearer token"
        };
        let ip = client.map(|c| c.describe());
        let mut detail = Detail::words(
            if token.is_some() {
                "security.detail.device_auth.invalid_token"
            } else {
                "security.detail.device_auth.missing_token"
            },
            &[],
        )
        .with_facts(crate::audit::clip(request.uri().path(), 100));
        if blocked {
            detail = detail.and("security.detail.client_refused", &[]);
        }
        state
            .audit
            .record(&state.db, "device_auth_failed", &key, ip.as_deref(), detail)
            .await;
        return (axum::http::StatusCode::UNAUTHORIZED, what).into_response();
    };

    if let Err(refused) = state.limits.take_token(device_id) {
        return too_many(refused);
    }
    let class = limits::route_class(request.uri().path());
    let permit = match state.limits.enter(device_id, class) {
        Ok(permit) => permit,
        Err(refused) => return too_many(refused),
    };
    if let Some(client) = &client {
        state.limits.record_access(device_id, client);
    }
    request
        .extensions_mut()
        .insert(AuthedDevice(DeviceRef { id: device_id }));
    let response = next.run(request).await;
    limits::hold_until_done(response, permit)
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Shared with the launcher's `PinHashTest` (same PIN, salt and expected hash): both sides
    /// must derive the same PBKDF2 parameters or no PIN would ever verify on the phone.
    #[test]
    fn pin_hash_shared_vector() {
        let salt: Vec<u8> = (0u8..16).collect();
        assert_eq!(
            hash_pin_with_salt("1234", &salt),
            "942eed8586f04aa8cc4b14537eb02bb601b671749b6f95c07a2d2261f83e75a7"
        );
        assert!(verify_pin(
            "1234",
            "942eed8586f04aa8cc4b14537eb02bb601b671749b6f95c07a2d2261f83e75a7",
            "000102030405060708090a0b0c0d0e0f"
        ));
    }

    #[test]
    fn clock_times_are_exactly_hh_mm() {
        for good in ["00:00", "03:00", "23:59", "09:05"] {
            assert!(valid_clock_time(good), "{good}");
        }
        for bad in [
            "",
            "3:00",
            "24:00",
            "23:60",
            "03:00 ",
            " 03:00",
            "03:00$(x)",
            "03:00\nx",
            "03:00;reboot",
            "0300",
            "03-00",
            "ab:cd",
            "03:0",
            "003:00",
            "\u{0663}3:00",
        ] {
            assert!(!valid_clock_time(bad), "{bad:?}");
        }
    }

    #[test]
    fn verify_pin_round_trip_and_garbage() {
        let (hash, salt) = hash_pin("4711");
        assert!(verify_pin("4711", &hash, &salt));
        assert!(!verify_pin("4712", &hash, &salt));
        assert!(!verify_pin("4711", "zz", &salt));
        assert!(!verify_pin("4711", &hash, "not hex"));
        assert!(!verify_pin("4711", &hash[..10], &salt));
    }
}
