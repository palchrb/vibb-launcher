//! Calendar links (design 72 step 1, `docs/design/72-calendar-feeds.md` at the monorepo root; migration
//! `0105_calendar_feeds.sql`): the parent pastes a calendar's ICS link in Device › Calendar, the server fetches and
//! parses it (`ics`), and each phone with the link writes the events into a local read-only calendar of its own
//! (Vibb One's `CalendarFeeds`). ICSx5 and DAVx5 stay possible.
//!
//! - **The model** (§2): a link is per phone, the fetch shared (`calendar_sources`, one row per normalized URL, its
//!   `uid` the hash of it; `device_calendar_feeds` names it per phone). The URL is sealed with the music key
//!   ([music_secret::MusicKey::seal_with], `calendar-v1:<fingerprint>:<uid>`); only its host is ever shown or logged.
//!   A source no phone names goes at once (the migration's trigger, QA H2).
//! - **The fetch** (§2.1, [refresh], [run]): each source every [INTERVAL] (most overdue first, [SPACING] apart), and
//!   "Fetch now" at most once per [FETCH_NOW_GAP]; through `music_fetch` (one request at a time server-wide) with
//!   `Kind::Calendar` (5 MB, 60 s), a public link never reaching a private address, a LAN link pinned to its judged
//!   addresses; redirects followed here one at a time ([get]), an https → http one refused; conditional GET; a
//!   failure backs off as the music sweep does and keeps the last set. A 200 with no events while the set had some is
//!   held (`empty`) until the [EMPTY_FETCHES]rd in a row or [EMPTY_HOURS] h later (QA M1). A feed whose every series
//!   is new at three fetches in a row (UID churn, QA L8) is fetched every [CHURN_INTERVAL].
//! - **Delivery** (§3): the policy's `calendar.feeds` (`[{id, name, rev}]`, [policy_feeds]) and
//!   `GET /api/devices/calendar/feeds/{uid}` ([device_feed], `ETag` = rev) for a phone with the link; a new rev nudges
//!   exactly the phones with it. Nothing historical is kept: each accepted fetch replaces the set.
//! - No URL, path or body text in any log, page or error: the host and the error's code only.

use std::collections::HashSet;
use std::net::IpAddr;

use chrono::{DateTime, NaiveDate, TimeDelta, Utc};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use sqlx::SqlitePool;

use crate::AppState;
use crate::ics::{self, WireEvent};
use crate::music_net::{FetchError, Kind, Reach, Source, SourceRequest, SourceResponse};
use crate::music_sweep::{parse_stamp, stamp};

/// A launcher that writes linked calendars reports this capability.
pub const CAPABILITY: &str = "calendar_feeds_v1";
/// Links a phone may have.
pub const MAX_LINKS_PER_PHONE: i64 = 10;
/// Sources a server keeps (a family's; more is a multi-tenant server).
pub const MAX_SOURCES: i64 = 50;
/// A pasted link, in characters.
pub const MAX_URL_CHARS: usize = 2000;
/// A link's name, in code points after `calendar::clean`.
pub const NAME_MAX: usize = 60;
/// Each source is fetched this often (decision 2).
pub const INTERVAL: TimeDelta = TimeDelta::minutes(60);
/// ... and this often while its UIDs churn (QA L8).
pub const CHURN_INTERVAL: TimeDelta = TimeDelta::hours(6);
/// Fetches in a row whose every series is new before [CHURN_INTERVAL].
pub const CHURN_FETCHES: i64 = 3;
/// "Fetch now" at most once per source in this.
pub const FETCH_NOW_GAP: TimeDelta = TimeDelta::minutes(5);
/// An empty answer is accepted at this many in a row ...
pub const EMPTY_FETCHES: i64 = 3;
/// ... or this many hours after the first.
pub const EMPTY_HOURS: i64 = 24;
/// The scheduler's requests at least this far apart.
pub const SPACING: std::time::Duration = std::time::Duration::from_secs(4);
/// A source failing this long is tried daily.
pub const DAILY_AFTER_DAYS: i64 = 7;
/// The phone's calendar: account `Vibb One` (LOCAL), NAME `vibb-feed-<uid>` - the key the server computes too.
pub const ACCOUNT: &str = "Vibb One";
pub const NAME_PREFIX: &str = "vibb-feed-";

// ---- the link ----------------------------------------------------------------------------------

/// A link the add form refuses before any fetch: the i18n key of the reason.
pub type Refusal = &'static str;

/// The normalized link (QA L3): scheme and host lowercased, `webcal(s)` as https, the default port and the fragment
/// dropped, path and query as written (a token is case-sensitive); http(s) only, no user or password, at most
/// [MAX_URL_CHARS].
pub fn normalize(raw: &str) -> Result<String, Refusal> {
    let raw = raw.trim();
    if raw.chars().count() > MAX_URL_CHARS {
        return Err("calendar.links.error.too_long");
    }
    let lower = raw.to_ascii_lowercase();
    let raw = ["webcals://", "webcal://"]
        .iter()
        .find(|p| lower.starts_with(*p))
        .map_or_else(
            || raw.to_string(),
            |p| format!("https://{}", &raw[p.len()..]),
        );
    let mut url = reqwest::Url::parse(&raw).map_err(|_| "calendar.links.error.not_link")?;
    if !matches!(url.scheme(), "http" | "https") || url.host_str().is_none_or(str::is_empty) {
        return Err("calendar.links.error.not_link");
    }
    if !url.username().is_empty() || url.password().is_some() {
        return Err("calendar.links.error.userinfo");
    }
    url.set_fragment(None);
    let url = url.to_string();
    if url.chars().count() > MAX_URL_CHARS {
        return Err("calendar.links.error.too_long");
    }
    Ok(url)
}

/// The source's uid: 32 hex of SHA-256 over `vibb-feed:` + the normalized link.
pub fn uid_of(normalized: &str) -> String {
    hex::encode(Sha256::digest(format!("vibb-feed:{normalized}").as_bytes()))[..32].to_string()
}

/// The phone's calendar key for a link (design 69's key over LOCAL, `Vibb One` and `vibb-feed-<uid>`).
pub fn calendar_key(uid: &str) -> String {
    hex::encode(Sha256::digest(
        format!("LOCAL\u{0}{ACCOUNT}\u{0}{NAME_PREFIX}{uid}").as_bytes(),
    ))[..16]
        .to_string()
}

/// A valid uid: 32 lowercase hex.
pub fn valid_uid(uid: &str) -> bool {
    uid.len() == 32 && uid.bytes().all(|b| matches!(b, b'0'..=b'9' | b'a'..=b'f'))
}

fn aad(fingerprint: &str, uid: &str) -> Vec<u8> {
    format!("calendar-v1:{fingerprint}:{uid}").into_bytes()
}

/// Tailscale's addresses (100.64.0.0/10, fd7a:115c:a1e0::/48).
fn tailscale(ip: IpAddr) -> bool {
    match ip {
        IpAddr::V4(v4) => {
            let [a, b, ..] = v4.octets();
            a == 100 && (64..=127).contains(&b)
        }
        IpAddr::V6(v6) => {
            let s = v6.segments();
            s[0] == 0xfd7a && s[1] == 0x115c && s[2] == 0xa1e0
        }
    }
}

// ---- the fetch ---------------------------------------------------------------------------------

/// A failed fetch: its code (worded at render, `calendar.links.status.<code>`) and an HTTP status for `status`.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct FeedError {
    pub code: &'static str,
    pub status: Option<u16>,
}

impl FeedError {
    fn code(code: &'static str) -> FeedError {
        FeedError { code, status: None }
    }
}

fn fetch_error(err: FetchError) -> FeedError {
    FeedError::code(match err {
        FetchError::Timeout => "timeout",
        FetchError::Refused(why) if why.contains("private") => "private_address",
        _ => "network",
    })
}

/// Where a link's requests may go.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Route {
    Public,
    /// The home LAN or the tailnet, pinned to these addresses.
    Lan(Vec<IpAddr>),
}

/// The route of a link (§5, QA L2): plain http only where the name resolves to the LAN or the tailnet (else
/// `insecure`); https public unless it was judged LAN (`lan`), and a LAN link judged again each time - one that now
/// resolves publicly goes public for good (the caller stores it).
pub async fn route(source: &dyn Source, url: &str, lan: bool) -> Result<Route, FeedError> {
    let http = url.starts_with("http://");
    if !http && !lan {
        return Ok(Route::Public);
    }
    match crate::music_sweep::lan_addresses(source, url).await {
        Ok(Some(addrs)) => Ok(Route::Lan(addrs)),
        Ok(None) if http => Err(FeedError::code("insecure")),
        Ok(None) => Ok(Route::Public),
        Err(err) => Err(fetch_error(err)),
    }
}

/// One GET of `url`, its redirects followed here one hop at a time (at most 5): http(s) only, never https → http
/// (QA L2), a LAN link only on its own host and port.
async fn get(
    source: &dyn Source,
    url: &str,
    route: &Route,
    etag: Option<&str>,
    last_modified: Option<&str>,
) -> Result<SourceResponse, FeedError> {
    let mut current = reqwest::Url::parse(url).map_err(|_| FeedError::code("network"))?;
    for hop in 0..=5 {
        let mut request = match route {
            Route::Public => SourceRequest::new(current.as_str(), Kind::Calendar, Reach::Public),
            Route::Lan(addrs) => {
                SourceRequest::new(current.as_str(), Kind::Calendar, Reach::Any).pinned_to(addrs)
            }
        };
        request.follow = false;
        request.etag = etag.map(str::to_string);
        request.last_modified = last_modified.map(str::to_string);
        let answer = source.get(request).await.map_err(fetch_error)?;
        let Some(location) = answer
            .location
            .as_deref()
            .filter(|_| matches!(answer.status, 301 | 302 | 303 | 307 | 308))
        else {
            return Ok(answer);
        };
        if hop == 5 {
            break;
        }
        let next = current
            .join(location)
            .map_err(|_| FeedError::code("network"))?;
        let insecure =
            next.scheme() == "http" && (current.scheme() == "https" || *route == Route::Public);
        if insecure {
            return Err(FeedError::code("insecure"));
        }
        if !matches!(next.scheme(), "http" | "https") {
            return Err(FeedError::code("network"));
        }
        if matches!(route, Route::Lan(_))
            && (next.host_str() != current.host_str()
                || next.port_or_known_default() != current.port_or_known_default())
        {
            return Err(FeedError::code("network"));
        }
        current = next;
    }
    Err(FeedError::code("network"))
}

/// What a fetch brought.
#[derive(Debug)]
pub enum Fetched {
    NotModified,
    Body {
        parsed: ics::Parsed,
        etag: Option<String>,
        last_modified: Option<String>,
    },
}

/// One fetch of a link (§2.1): 304 → [Fetched::NotModified]; a 200 read by `ics::parse` (more than 5 MB →
/// `too_big`, not a VCALENDAR → `not_calendar`); 404/410 `not_found`, other statuses `status`.
pub async fn fetch(
    source: &dyn Source,
    url: &str,
    route: &Route,
    validators: (Option<&str>, Option<&str>),
    today: NaiveDate,
) -> Result<Fetched, FeedError> {
    let answer = get(source, url, route, validators.0, validators.1).await?;
    match answer.status {
        304 => return Ok(Fetched::NotModified),
        200 => {}
        404 | 410 => return Err(FeedError::code("not_found")),
        other => {
            return Err(FeedError {
                code: "status",
                status: Some(other),
            });
        }
    }
    if answer.truncated {
        return Err(FeedError::code("too_big"));
    }
    // A parser over untrusted bytes: a panic in it is this link's failure, never the scheduler's or the add's end
    // (QA H1).
    let read = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        ics::parse(&answer.body, answer.content_type.as_deref(), today)
    }));
    let parsed = match read {
        Ok(Ok(parsed)) => parsed,
        Ok(Err(_)) => return Err(FeedError::code("not_calendar")),
        Err(_) => {
            tracing::error!("a calendar link's body made the parser panic");
            return Err(FeedError::code("unreadable"));
        }
    };
    Ok(Fetched::Body {
        parsed,
        etag: answer.etag,
        last_modified: answer.last_modified,
    })
}

// ---- the document ------------------------------------------------------------------------------

/// The document a phone gets (§3).
#[derive(Serialize, Deserialize, Debug, Clone, PartialEq, Eq)]
pub struct Document {
    pub v: u32,
    pub id: String,
    pub rev: String,
    pub events: Vec<WireEvent>,
}

/// The served document of a set and its rev.
pub fn document(uid: &str, events: Vec<WireEvent>) -> (String, String) {
    let rev = ics::feed_rev(&events);
    let doc = Document {
        v: 1,
        id: uid.to_string(),
        rev: rev.clone(),
        events,
    };
    (
        rev,
        serde_json::to_string(&doc).expect("a document serializes"),
    )
}

/// A link's name: the parent's (cleaned, at most [NAME_MAX]), else the feed's `X-WR-CALNAME`, else the host.
pub fn name_of(given: &str, feed_name: Option<&str>, host: &str) -> String {
    [Some(given), feed_name, Some(host)]
        .into_iter()
        .flatten()
        .map(|n| crate::calendar::clean(n, NAME_MAX))
        .find(|n| !n.is_empty())
        .unwrap_or_else(|| host.to_string())
}

// ---- storing a fetch ---------------------------------------------------------------------------

#[derive(sqlx::FromRow)]
struct SourceRow {
    uid: String,
    url_sealed: Vec<u8>,
    url_nonce: Vec<u8>,
    url_key_fp: String,
    lan: bool,
    etag: Option<String>,
    last_modified: Option<String>,
    rev: String,
    feed_json: String,
    events: i64,
    ok_at: Option<String>,
    created_at: String,
    failures: i64,
    empty_count: i64,
    empty_since: Option<String>,
    churn: i64,
}

/// What [refresh] did.
#[derive(Debug, PartialEq, Eq)]
pub enum Outcome {
    /// The source is gone (removed meanwhile).
    Gone,
    Unchanged,
    /// A new set (the phones were nudged).
    Changed,
    /// An empty answer held (QA M1).
    HeldEmpty,
    Failed(FeedError),
}

/// The phones with a source.
async fn phones_of(db: &SqlitePool, source_id: i64) -> Result<Vec<i64>, sqlx::Error> {
    sqlx::query_scalar("SELECT device_id FROM device_calendar_feeds WHERE source_id = ?")
        .bind(source_id)
        .fetch_all(db)
        .await
}

/// Nudges exactly the phones with a source.
pub async fn nudge(state: &AppState, source_id: i64) {
    match phones_of(&state.db, source_id).await {
        Ok(phones) => {
            for phone in phones {
                let _ = state.command_notify.send(phone);
            }
        }
        Err(err) => tracing::error!(source_id, %err, "couldn't read a calendar link's phones"),
    }
}

/// The series uids of a stored document.
fn uids_of(feed_json: &str) -> HashSet<String> {
    serde_json::from_str::<Document>(feed_json)
        .map(|d| d.events.into_iter().map(|e| e.uid).collect())
        .unwrap_or_default()
}

/// One fetch of source `id` at `now`, stored (§2.1). A DB error is returned; a fetch error is stored and returned
/// as [Outcome::Failed]. A source removed meanwhile writes nothing.
pub async fn refresh(
    state: &AppState,
    id: i64,
    now: DateTime<Utc>,
) -> Result<Outcome, sqlx::Error> {
    let row: Option<SourceRow> = sqlx::query_as(
        "SELECT uid, url_sealed, url_nonce, url_key_fp, lan, etag, last_modified, rev, feed_json, events, \
         ok_at, created_at, failures, empty_count, empty_since, churn FROM calendar_sources WHERE id = ?",
    )
    .bind(id)
    .fetch_optional(&state.db)
    .await?;
    let Some(row) = row else {
        return Ok(Outcome::Gone);
    };
    let url = state.music_key.as_ref().and_then(|key| {
        key.open_with(
            &aad(&row.url_key_fp, &row.uid),
            &row.url_sealed,
            &row.url_nonce,
            &row.url_key_fp,
        )
    });
    let result = match url.and_then(|u| String::from_utf8(u).ok()) {
        None => Err((FeedError::code("key_lost"), row.lan)),
        Some(url) => match route(state.music_fetch.as_ref(), &url, row.lan).await {
            Err(err) => Err((err, row.lan)),
            Ok(route) => {
                let lan = matches!(route, Route::Lan(_));
                // Plain http on the LAN is warned of as its name resolves now, not as it did at the add (QA L5).
                let lan_http = url.starts_with("http://")
                    && matches!(&route, Route::Lan(addrs) if !addrs.iter().all(|ip| tailscale(*ip)));
                sqlx::query("UPDATE calendar_sources SET lan_http = ? WHERE id = ?")
                    .bind(lan_http)
                    .bind(id)
                    .execute(&state.db)
                    .await?;
                fetch(
                    state.music_fetch.as_ref(),
                    &url,
                    &route,
                    (row.etag.as_deref(), row.last_modified.as_deref()),
                    now.date_naive(),
                )
                .await
                .map(|f| (f, lan))
                .map_err(|e| (e, lan))
            }
        },
    };
    let interval = if row.churn >= CHURN_FETCHES {
        CHURN_INTERVAL
    } else {
        INTERVAL
    };
    let (fetched, lan) = match result {
        Ok(found) => found,
        Err((err, lan)) => {
            let failures = row.failures + 1;
            let failing_since = row
                .ok_at
                .as_deref()
                .or(Some(row.created_at.as_str()))
                .and_then(parse_stamp)
                .unwrap_or(now);
            let wait = if now - failing_since >= TimeDelta::days(DAILY_AFTER_DAYS) {
                TimeDelta::hours(24)
            } else {
                crate::music_sweep::backoff(failures, interval)
            };
            sqlx::query(
                "UPDATE calendar_sources SET failures = ?, error = ?, error_status = ?, tried_at = ?, \
                 next_at = ?, lan = ? WHERE id = ?",
            )
            .bind(failures)
            .bind(err.code)
            .bind(err.status.map(i64::from))
            .bind(stamp(now))
            .bind(stamp(now + wait))
            .bind(lan && row.lan)
            .bind(id)
            .execute(&state.db)
            .await?;
            tracing::info!(
                source_id = id,
                code = err.code,
                "a calendar link's fetch failed"
            );
            return Ok(Outcome::Failed(err));
        }
    };
    let (parsed, etag, last_modified) = match fetched {
        Fetched::NotModified => {
            sqlx::query(
                "UPDATE calendar_sources SET failures = 0, error = NULL, error_status = NULL, empty_count = 0, \
                 empty_since = NULL, ok_at = ?, tried_at = ?, next_at = ?, lan = ? WHERE id = ?",
            )
            .bind(stamp(now))
            .bind(stamp(now))
            .bind(stamp(now + interval))
            .bind(lan)
            .bind(id)
            .execute(&state.db)
            .await?;
            return Ok(Outcome::Unchanged);
        }
        Fetched::Body {
            parsed,
            etag,
            last_modified,
        } => (parsed, etag, last_modified),
    };
    // An empty answer while the set had events is held (QA M1); its validators aren't kept, so the next fetch asks
    // again and counts.
    if parsed.events.is_empty() && row.events > 0 {
        let count = row.empty_count + 1;
        let since = row
            .empty_since
            .as_deref()
            .and_then(parse_stamp)
            .unwrap_or(now);
        if count < EMPTY_FETCHES && now - since < TimeDelta::hours(EMPTY_HOURS) {
            sqlx::query(
                "UPDATE calendar_sources SET failures = 0, error = 'empty', error_status = NULL, empty_count = ?, \
                 empty_since = ?, tried_at = ?, next_at = ?, lan = ? WHERE id = ?",
            )
            .bind(count)
            .bind(stamp(since))
            .bind(stamp(now))
            .bind(stamp(now + interval))
            .bind(lan)
            .bind(id)
            .execute(&state.db)
            .await?;
            return Ok(Outcome::HeldEmpty);
        }
    }
    let before = uids_of(&row.feed_json);
    let all_new = !before.is_empty()
        && !parsed.events.is_empty()
        && parsed.events.iter().all(|e| !before.contains(&e.uid));
    let churn = if all_new { row.churn + 1 } else { 0 };
    let interval = if churn >= CHURN_FETCHES {
        CHURN_INTERVAL
    } else {
        INTERVAL
    };
    let counts = parsed.counts.clone();
    let feed_name = parsed
        .name
        .as_deref()
        .map(|n| crate::calendar::clean(n, NAME_MAX));
    let (rev, json) = document(&row.uid, parsed.events);
    let changed = rev != row.rev;
    let written = sqlx::query(
        "UPDATE calendar_sources SET rev = ?, feed_json = ?, feed_name = ?, events = ?, cut = ?, unknown_tz = ?, \
         bad = ?, etag = ?, last_modified = ?, ok_at = ?, tried_at = ?, failures = 0, error = NULL, \
         error_status = NULL, empty_count = 0, empty_since = NULL, churn = ?, next_at = ?, lan = ? \
         WHERE id = ? AND uid = ?",
    )
    .bind(&rev)
    .bind(&json)
    .bind(feed_name)
    .bind(counts.events)
    .bind(counts.cut)
    .bind(counts.unknown_tz)
    .bind(counts.bad)
    .bind(etag)
    .bind(last_modified)
    .bind(stamp(now))
    .bind(stamp(now))
    .bind(churn)
    .bind(stamp(now + interval))
    .bind(lan)
    .bind(id)
    .bind(&row.uid)
    .execute(&state.db)
    .await?;
    if written.rows_affected() == 0 {
        return Ok(Outcome::Gone);
    }
    if changed {
        nudge(state, id).await;
        return Ok(Outcome::Changed);
    }
    Ok(Outcome::Unchanged)
}

/// The scheduler (§2.1): every due source, most overdue first, [SPACING] apart; then a minute's sleep.
pub async fn run(state: AppState) {
    tokio::time::sleep(std::time::Duration::from_secs(30)).await;
    loop {
        run_due(&state, SPACING).await;
        tokio::time::sleep(std::time::Duration::from_secs(60)).await;
    }
}

/// One pass over the sources due, `spacing` apart. Each fetch is its own task: one that panics is stored as that
/// source's failure (`unreadable`) and the pass goes on (QA H1).
pub async fn run_due(state: &AppState, spacing: std::time::Duration) {
    let due: Result<Vec<i64>, sqlx::Error> = sqlx::query_scalar(
        "SELECT id FROM calendar_sources WHERE next_at <= ? ORDER BY next_at LIMIT ?",
    )
    .bind(stamp(Utc::now()))
    .bind(MAX_SOURCES)
    .fetch_all(&state.db)
    .await;
    let ids = match due {
        Ok(ids) => ids,
        Err(err) => {
            tracing::error!(%err, "couldn't read the calendar links due");
            return;
        }
    };
    for id in ids {
        let task = tokio::spawn({
            let state = state.clone();
            async move { refresh(&state, id, Utc::now()).await }
        });
        match task.await {
            Ok(Ok(_)) => {}
            Ok(Err(err)) => {
                tracing::error!(source_id = id, %err, "couldn't store a calendar link's fetch");
                // Not again every minute (QA nit 6): the next try at the next interval; best effort.
                let _ = sqlx::query("UPDATE calendar_sources SET next_at = ? WHERE id = ?")
                    .bind(stamp(Utc::now() + INTERVAL))
                    .bind(id)
                    .execute(&state.db)
                    .await;
            }
            Err(err) => {
                tracing::error!(source_id = id, %err, "a calendar link's fetch panicked");
                let now = Utc::now();
                let marked = sqlx::query(
                    "UPDATE calendar_sources SET failures = failures + 1, error = 'unreadable', \
                     error_status = NULL, tried_at = ?, next_at = ? WHERE id = ?",
                )
                .bind(stamp(now))
                .bind(stamp(now + INTERVAL))
                .bind(id)
                .execute(&state.db)
                .await;
                if let Err(err) = marked {
                    tracing::error!(source_id = id, %err, "couldn't mark a calendar link's failed fetch");
                }
            }
        }
        tokio::time::sleep(spacing).await;
    }
}

// ---- adding a link -----------------------------------------------------------------------------

/// Why a link wasn't added.
#[derive(Debug)]
pub enum AddError {
    /// Refused before a fetch: an i18n key.
    Refused(Refusal),
    /// The add check's fetch failed.
    Fetch(FeedError),
    Db(sqlx::Error),
}

impl From<sqlx::Error> for AddError {
    fn from(err: sqlx::Error) -> AddError {
        AddError::Db(err)
    }
}

/// Adds a link to a phone (§5): normalized, attached when the source exists (no fetch), else judged (plain http only
/// on the LAN or the tailnet), fetched once - it must give a VCALENDAR, an empty one is fine - sealed and stored. The
/// name: the parent's, else the feed's, else the host. Returns the source's uid. The caller nudges the phone.
pub async fn add(
    state: &AppState,
    device_id: i64,
    raw_url: &str,
    given_name: &str,
    now: DateTime<Utc>,
) -> Result<String, AddError> {
    let Some(key) = state.music_key.clone() else {
        return Err(AddError::Refused("calendar.links.error.no_key"));
    };
    let url = normalize(raw_url).map_err(AddError::Refused)?;
    let uid = uid_of(&url);
    let host = crate::music_net::host_of(&url).unwrap_or_default();
    let existing: Option<(i64, Option<String>, String)> =
        sqlx::query_as("SELECT id, feed_name, url_key_fp FROM calendar_sources WHERE uid = ?")
            .bind(&uid)
            .fetch_optional(&state.db)
            .await?;
    if let Some((source_id, _, sealed_with)) = &existing {
        // A source sealed with a key the server no longer has (`key_lost`): the parent pasted the same link (its
        // hash is the uid), so it is sealed again onto that row and fetched at once (QA M1) - the siblings that
        // have it get it back too.
        if *sealed_with != key.fingerprint {
            let (sealed, nonce) = key.seal_with(&aad(&key.fingerprint, &uid), url.as_bytes());
            sqlx::query(
                "UPDATE calendar_sources SET url_sealed = ?, url_nonce = ?, url_key_fp = ?, error = NULL, \
                 error_status = NULL, failures = 0, next_at = ? WHERE id = ?",
            )
            .bind(sealed)
            .bind(nonce)
            .bind(&key.fingerprint)
            .bind(stamp(now))
            .bind(*source_id)
            .execute(&state.db)
            .await?;
            tracing::info!(
                source_id = *source_id,
                "a calendar link was sealed again with the current key"
            );
        }
    }
    let mine: Vec<String> = sqlx::query_scalar(
        "SELECT s.uid FROM device_calendar_feeds d JOIN calendar_sources s ON s.id = d.source_id \
         WHERE d.device_id = ?",
    )
    .bind(device_id)
    .fetch_all(&state.db)
    .await?;
    if mine.contains(&uid) {
        return Ok(uid);
    }
    if mine.len() as i64 >= MAX_LINKS_PER_PHONE {
        return Err(AddError::Refused("calendar.links.error.too_many"));
    }
    if let Some((source_id, feed_name, _)) = existing {
        attach(
            &state.db,
            device_id,
            source_id,
            &name_of(given_name, feed_name.as_deref(), &host),
        )
        .await?;
        return Ok(uid);
    }
    let sources: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM calendar_sources")
        .fetch_one(&state.db)
        .await?;
    if sources >= MAX_SOURCES {
        return Err(AddError::Refused("calendar.links.error.too_many_sources"));
    }
    // Judged as the sweep judges a pasted feed (§2.7 of design 21b): where the name resolves now.
    let lan_addrs = crate::music_sweep::lan_addresses(state.music_fetch.as_ref(), &url)
        .await
        .map_err(|e| AddError::Fetch(fetch_error(e)))?;
    let http = url.starts_with("http://");
    let route = match &lan_addrs {
        Some(addrs) => Route::Lan(addrs.clone()),
        None if http => return Err(AddError::Refused("calendar.links.error.http_public")),
        None => Route::Public,
    };
    let lan_http = http
        && lan_addrs
            .as_ref()
            .is_some_and(|a| !a.iter().all(|ip| tailscale(*ip)));
    let fetched = fetch(
        state.music_fetch.as_ref(),
        &url,
        &route,
        (None, None),
        now.date_naive(),
    )
    .await
    .map_err(AddError::Fetch)?;
    let Fetched::Body {
        parsed,
        etag,
        last_modified,
    } = fetched
    else {
        return Err(AddError::Fetch(FeedError::code("network")));
    };
    let counts = parsed.counts.clone();
    let feed_name = parsed
        .name
        .as_deref()
        .map(|n| crate::calendar::clean(n, NAME_MAX))
        .filter(|n| !n.is_empty());
    let name = name_of(given_name, feed_name.as_deref(), &host);
    let (rev, json) = document(&uid, parsed.events);
    let (sealed, nonce) = key.seal_with(&aad(&key.fingerprint, &uid), url.as_bytes());
    let mut tx = state.db.begin().await?;
    let inserted: Option<i64> = sqlx::query_scalar(
        "INSERT INTO calendar_sources (uid, url_sealed, url_nonce, url_key_fp, host, lan, lan_http, etag, \
         last_modified, rev, feed_json, feed_name, ok_at, tried_at, next_at, events, cut, unknown_tz, bad) \
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT(uid) DO NOTHING RETURNING id",
    )
    .bind(&uid)
    .bind(sealed)
    .bind(nonce)
    .bind(&key.fingerprint)
    .bind(&host)
    .bind(lan_addrs.is_some())
    .bind(lan_http)
    .bind(etag)
    .bind(last_modified)
    .bind(&rev)
    .bind(&json)
    .bind(&feed_name)
    .bind(stamp(now))
    .bind(stamp(now))
    .bind(stamp(now + INTERVAL))
    .bind(counts.events)
    .bind(counts.cut)
    .bind(counts.unknown_tz)
    .bind(counts.bad)
    .fetch_optional(&mut *tx)
    .await?;
    // Another add of the same link won the race: attach to its source.
    let source_id = match inserted {
        Some(id) => id,
        None => {
            sqlx::query_scalar("SELECT id FROM calendar_sources WHERE uid = ?")
                .bind(&uid)
                .fetch_one(&mut *tx)
                .await?
        }
    };
    sqlx::query(
        "INSERT INTO device_calendar_feeds (device_id, source_id, name) VALUES (?, ?, ?) \
         ON CONFLICT(device_id, source_id) DO NOTHING",
    )
    .bind(device_id)
    .bind(source_id)
    .bind(&name)
    .execute(&mut *tx)
    .await?;
    tx.commit().await?;
    tracing::info!(device_id, host = %host, "a calendar link was added");
    Ok(uid)
}

async fn attach(
    db: &SqlitePool,
    device_id: i64,
    source_id: i64,
    name: &str,
) -> Result<(), sqlx::Error> {
    sqlx::query(
        "INSERT INTO device_calendar_feeds (device_id, source_id, name) VALUES (?, ?, ?) \
         ON CONFLICT(device_id, source_id) DO NOTHING",
    )
    .bind(device_id)
    .bind(source_id)
    .bind(name)
    .execute(db)
    .await
    .map(|_| ())
}

// ---- reading -----------------------------------------------------------------------------------

/// One link of the policy's `calendar.feeds` (§3).
#[derive(Serialize, Clone, Debug, PartialEq, Eq, sqlx::FromRow)]
pub struct PolicyFeed {
    pub id: String,
    pub name: String,
    pub rev: String,
}

/// The phone's links, in the order added.
pub async fn policy_feeds(db: &SqlitePool, device_id: i64) -> Result<Vec<PolicyFeed>, sqlx::Error> {
    sqlx::query_as(
        "SELECT s.uid AS id, d.name, s.rev FROM device_calendar_feeds d \
         JOIN calendar_sources s ON s.id = d.source_id WHERE d.device_id = ? ORDER BY d.created_at, s.uid",
    )
    .bind(device_id)
    .fetch_all(db)
    .await
}

/// A link's document for a phone that has it: `(rev, json)`, `None` otherwise.
pub async fn device_feed(
    db: &SqlitePool,
    device_id: i64,
    uid: &str,
) -> Result<Option<(String, String)>, sqlx::Error> {
    sqlx::query_as(
        "SELECT s.rev, s.feed_json FROM calendar_sources s \
         JOIN device_calendar_feeds d ON d.source_id = s.id WHERE s.uid = ? AND d.device_id = ?",
    )
    .bind(uid)
    .bind(device_id)
    .fetch_optional(db)
    .await
}

/// One link on the page (§5).
#[derive(sqlx::FromRow, Clone, Debug)]
pub struct LinkRow {
    pub uid: String,
    pub name: String,
    pub host: String,
    pub lan_http: bool,
    pub ok_at: Option<String>,
    pub error: Option<String>,
    pub error_status: Option<i64>,
    pub events: i64,
    pub cut: i64,
    pub unknown_tz: i64,
    pub churn: i64,
}

pub async fn links(db: &SqlitePool, device_id: i64) -> Result<Vec<LinkRow>, sqlx::Error> {
    sqlx::query_as(
        "SELECT s.uid, d.name, s.host, s.lan_http, s.ok_at, s.error, s.error_status, s.events, s.cut, \
         s.unknown_tz, s.churn FROM device_calendar_feeds d \
         JOIN calendar_sources s ON s.id = d.source_id WHERE d.device_id = ? ORDER BY d.created_at, s.uid",
    )
    .bind(device_id)
    .fetch_all(db)
    .await
}

/// The contract's link (`testdata/calendar_feeds.json`, its feed `testdata/calendar_feeds/contract.ics`).
#[cfg(test)]
pub const CONTRACT_URL: &str = "https://skole.example.no/kalender/5b.ics?token=FAKE-TOKEN";

/// The day the contract's feed is read on (the horizon).
#[cfg(test)]
pub fn contract_today() -> NaiveDate {
    NaiveDate::from_ymd_opt(2026, 10, 27).expect("a date")
}

#[cfg(test)]
mod tests {
    use super::*;

    /// The shared contract (design 72 §3): `feed` is the document for `contract.ics`, `keys` the phone's calendar key
    /// of each link, `policy` and `report` carry its uid and rev; the launcher's `CalendarFeedsTest` maps `feed` to
    /// exactly `provider`. `CALENDAR_FEEDS_CONTRACT_WRITE=1` rewrites `feed`, `keys` and the uids and revs.
    #[test]
    fn the_contract() {
        let path = "testdata/calendar_feeds.json";
        let mut contract: serde_json::Value =
            serde_json::from_str(&std::fs::read_to_string(path).unwrap()).unwrap();
        let uid = uid_of(&normalize(CONTRACT_URL).unwrap());
        let body = std::fs::read("testdata/calendar_feeds/contract.ics").unwrap();
        let parsed = ics::parse(&body, None, contract_today()).unwrap();
        assert_eq!(parsed.name.as_deref(), Some("Skolen"));
        let (rev, json) = document(&uid, parsed.events);
        let feed: serde_json::Value = serde_json::from_str(&json).unwrap();
        let keys = serde_json::json!({ uid.clone(): calendar_key(&uid) });
        if std::env::var("CALENDAR_FEEDS_CONTRACT_WRITE").is_ok() {
            contract["feed"] = feed.clone();
            contract["keys"] = keys.clone();
            contract["policy"]["feeds"][0]["id"] = uid.clone().into();
            contract["policy"]["feeds"][0]["rev"] = rev.clone().into();
            contract["report"]["feeds"][0]["id"] = uid.clone().into();
            contract["report"]["feeds"][0]["applied_rev"] = rev.clone().into();
            let pretty = serde_json::to_string_pretty(&contract).unwrap() + "\n";
            std::fs::write(path, pretty).unwrap();
        }
        assert_eq!(
            contract["feed"], feed,
            "the feed changed - update both copies together"
        );
        assert_eq!(contract["keys"], keys);
        assert_eq!(contract["policy"]["feeds"][0]["id"], uid.as_str());
        assert_eq!(contract["policy"]["feeds"][0]["rev"], rev.as_str());
        assert_eq!(contract["report"]["feeds"][0]["id"], uid.as_str());
        assert_eq!(contract["report"]["feeds"][0]["applied_rev"], rev.as_str());
        // The override moved in another zone is on the wire in the series' (QA M4).
        let weekly = &contract["feed"]["events"]
            .as_array()
            .unwrap()
            .iter()
            .find(|e| e["uid"] == "contract-weekly")
            .unwrap()["overrides"][0];
        assert_eq!(weekly["start"], "20261116T101500");
    }

    #[test]
    fn the_contract_is_identical_to_the_launchers_copy() {
        let ours = std::fs::read_to_string("testdata/calendar_feeds.json").unwrap();
        let theirs =
            std::fs::read_to_string("../launcher/app/src/test/resources/calendar_feeds.json")
                .expect("the launcher's copy");
        assert_eq!(ours, theirs);
    }

    #[test]
    fn links_are_normalized() {
        let n = |raw: &str| normalize(raw);
        assert_eq!(
            n(" webcal://Skole.Kommune.NO:443/Feed.ics?Token=AbC#x ").unwrap(),
            "https://skole.kommune.no/Feed.ics?Token=AbC"
        );
        assert_eq!(
            n("WEBCALS://skole.kommune.no/a").unwrap(),
            "https://skole.kommune.no/a"
        );
        assert_eq!(
            n("HTTP://nas.home.arpa:80/k.ics").unwrap(),
            "http://nas.home.arpa/k.ics"
        );
        assert_eq!(n("https://x.no:8443/k").unwrap(), "https://x.no:8443/k");
        assert_eq!(
            n("https://user:pw@x.no/k"),
            Err("calendar.links.error.userinfo")
        );
        assert_eq!(n("ftp://x.no/k"), Err("calendar.links.error.not_link"));
        assert_eq!(n("skolen"), Err("calendar.links.error.not_link"));
        let long = format!("https://x.no/{}", "a".repeat(2000));
        assert_eq!(n(&long), Err("calendar.links.error.too_long"));
        // The same link, the same uid; a token's case matters.
        assert_eq!(
            uid_of(&n("webcal://x.no/a?t=K").unwrap()),
            uid_of(&n("https://X.no/a?t=K").unwrap())
        );
        assert_ne!(
            uid_of(&n("https://x.no/a?t=K").unwrap()),
            uid_of(&n("https://x.no/a?t=k").unwrap())
        );
    }

    #[test]
    fn names_fall_back_to_the_feed_then_the_host() {
        assert_eq!(name_of(" Skolen ", Some("itslearning"), "x.no"), "Skolen");
        assert_eq!(
            name_of("", Some("itslearning - 5B"), "x.no"),
            "itslearning - 5B"
        );
        assert_eq!(name_of("  ", None, "x.no"), "x.no");
        assert_eq!(
            name_of(&"å".repeat(80), None, "x.no").chars().count(),
            NAME_MAX
        );
    }
}
