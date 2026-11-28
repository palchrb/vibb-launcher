//! Calendar links (design 72 step 1, `docs/design/72-calendar-feeds.md` at the monorepo root; migration
//! `0105_calendar_feeds.sql`): the parent pastes a calendar's ICS link in Device › Calendar, the server fetches and
//! parses it (`ics`), and each phone with the link writes the events into a local read-only calendar of its own
//! (Vibb One's `CalendarFeeds`). ICSx5 and DAVx5 stay possible.
//!
//! - **The model** (§2): a link is per phone, the fetch shared (`calendar_sources`, one row per normalized URL, its
//!   `uid` the hash of it; `device_calendar_feeds` names it per phone). The URL is sealed with the music key
//!   ([music_secret::MusicKey::seal_with], `calendar-v1:<fingerprint>:<uid>`); only its host is ever shown or logged.
//!   A source no phone names goes at once (the migration's trigger, QA H2).
//! - **The fetch** (§2.1, [refresh], [run]): each source at its interval ([interval_of]: a link's [LINK_CHECKS], a
//!   CalDAV account's [CALDAV_CHECKS]; most overdue first, [SPACING] apart), and
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

/// Links a phone may have.
pub const MAX_LINKS_PER_PHONE: i64 = 10;
/// Sources a server keeps (a family's; more is a multi-tenant server).
pub const MAX_SOURCES: i64 = 50;
/// A pasted link, in characters.
pub const MAX_URL_CHARS: usize = 2000;
/// A link's name, in code points after `calendar::clean`.
pub const NAME_MAX: usize = 60;
/// A source whose interval can't be read is fetched this often (decision 2's 30, since 2026-11-09: a removed event
/// leaves the phone sooner), and a failing one backs off to at least this ([interval_of] gives the rest).
pub const INTERVAL: TimeDelta = TimeDelta::minutes(30);
/// How often a link is checked, the parent's choice per phone (2026-11-28, migration 0188), in minutes; the shared
/// fetch takes the shortest of the phones' rows that name it.
pub const LINK_CHECKS: [i64; 3] = [15, 30, 60];
/// ... and a CalDAV account's calendars, the account owner's choice (an unchanged calendar is one PROPFIND).
pub const CALDAV_CHECKS: [i64; 4] = [5, 15, 30, 60];
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

pub(crate) fn aad(fingerprint: &str, uid: &str) -> Vec<u8> {
    format!("calendar-v1:{fingerprint}:{uid}").into_bytes()
}

/// Tailscale's addresses (100.64.0.0/10, fd7a:115c:a1e0::/48).
pub(crate) fn tailscale(ip: IpAddr) -> bool {
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
    pub(crate) fn code(code: &'static str) -> FeedError {
        FeedError { code, status: None }
    }
}

pub(crate) fn fetch_error(err: FetchError) -> FeedError {
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
        /// A CalDAV calendar's resources one by one (design 72b D4.6); empty for a link.
        resources: Vec<crate::caldav::DavResource>,
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
        resources: Vec::new(),
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
    kind: String,
    account_id: Option<i64>,
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

/// One fetch of source `id` at `now`, stored (§2.1), its outcome recorded in the integration health (design 116:
/// `calendar_link`, or `caldav` for a CalDAV calendar). A DB error is returned; a fetch error is stored and returned
/// as [Outcome::Failed]. A source removed meanwhile writes nothing.
pub async fn refresh(
    state: &AppState,
    id: i64,
    now: DateTime<Utc>,
) -> Result<Outcome, sqlx::Error> {
    let source: Option<(String, Option<i64>)> =
        sqlx::query_as("SELECT kind, account_id FROM calendar_sources WHERE id = ?")
            .bind(id)
            .fetch_optional(&state.db)
            .await?;
    let outcome = fetch_and_store(state, id, now).await?;
    if let Some((kind, account)) = source {
        record_health(&state.db, id, &kind, account, &outcome).await;
    }
    Ok(outcome)
}

/// A fetch's code in the integration health (design 116 §2.1), `None` = not recorded. Codes are `&'static str`, so
/// `tests::every_feed_code_is_classed` reads every code the fetches write and checks each has its row here.
pub(crate) fn class_of(err: &FeedError) -> Option<crate::integration_health::Class> {
    use crate::integration_health::Class::*;
    match err.code {
        "network" | "timeout" | "empty" => Some(Transient),
        "status" => match err.status {
            Some(status) if status >= 500 || status == 408 || status == 429 => Some(Transient),
            _ => Some(Parent),
        },
        "not_found" | "not_listed" | "not_caldav" | "no_calendars" | "not_calendar"
        | "unreadable" | "insecure" | "private_address" | "too_big" => Some(Parent),
        "login_refused" | "host_changed" | "key_lost" => Some(Stop),
        // The codes are text: a new one the table misses shows in the log, never as silence (code QA L9).
        code => {
            tracing::warn!(code, "integration health: a calendar code with no class");
            None
        }
    }
}

/// Records [refresh]'s outcome: `calendar_link` at `source:<id>`; `caldav` at `source:<id>`, its stops
/// (`login_refused`, `host_changed`, `key_lost`) at `account:<id>`, and a success clears both.
async fn record_health(
    db: &SqlitePool,
    id: i64,
    kind: &str,
    account: Option<i64>,
    outcome: &Outcome,
) {
    use crate::integration_health::{Class, failed, ok};
    let caldav = kind == "caldav";
    let key = if caldav { "caldav" } else { "calendar_link" };
    let source = format!("source:{id}");
    let account = account.filter(|_| caldav).map(|a| format!("account:{a}"));
    let held;
    let err = match outcome {
        Outcome::Gone => return,
        Outcome::Unchanged | Outcome::Changed => {
            ok(db, key, &source).await;
            if let Some(account) = &account {
                ok(db, key, account).await;
            }
            return;
        }
        Outcome::HeldEmpty => {
            held = FeedError::code("empty");
            &held
        }
        Outcome::Failed(err) => err,
    };
    let Some(class) = class_of(err) else { return };
    let scope = match (&account, class) {
        (Some(account), Class::Stop) => account,
        _ => &source,
    };
    failed(db, key, scope, err.code, class).await;
}

/// [refresh]'s fetch and store.
async fn fetch_and_store(
    state: &AppState,
    id: i64,
    now: DateTime<Utc>,
) -> Result<Outcome, sqlx::Error> {
    let row: Option<SourceRow> = sqlx::query_as(
        "SELECT uid, url_sealed, url_nonce, url_key_fp, lan, etag, last_modified, rev, feed_json, events, \
         ok_at, created_at, failures, empty_count, empty_since, churn, kind, account_id FROM calendar_sources \
         WHERE id = ?",
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
    let mut report_at = now;
    let result = match url.and_then(|u| String::from_utf8(u).ok()) {
        None => Err((FeedError::code("key_lost"), row.lan)),
        // A CalDAV calendar (design 72 step 2): its account's login, its validator the collection's ctag. A calendar
        // a phone writes the kid's events to (design 72b D4.2) has its waiting writes made first.
        Some(url) if row.kind == "caldav" => {
            // A write since the last stored fetch reads the calendar whatever its ctag says (R-L2: the phone's
            // `pending` waits for it).
            let force = match row.account_id {
                Some(account) => {
                    crate::calendar_kid_events::before_fetch(state, id, account, now).await?
                }
                None => false,
            };
            let validator = if force { None } else { row.etag.as_deref() };
            // The instant the REPORT goes, after the writes made first (code QA H3): `after_fetch` judges nothing
            // written after it.
            report_at = state.kid_writes.now().max(now);
            crate::caldav::refresh_source(
                state,
                row.account_id,
                &url,
                (validator, row.last_modified.as_deref()),
                now,
            )
            .await?
            .map(|f| (f, row.lan))
            .map_err(|e| (e, row.lan))
        }
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
    // Read after the fetch: a choice saved while it ran counts from this check.
    let every = interval_of(&state.db, id).await?;
    let interval = if row.churn >= CHURN_FETCHES {
        CHURN_INTERVAL
    } else {
        every
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
                // The backoff as before the choice (15 min, then 30), never more often than the interval.
                crate::music_sweep::backoff(failures, interval.max(INTERVAL))
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
    let (parsed, etag, last_modified, resources) = match fetched {
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
            resources,
        } => (parsed, etag, last_modified, resources),
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
    // A CalDAV calendar's floor goes by the validator this answer gave, not the one stored before it.
    let every = if row.kind == "caldav" {
        interval_for(&state.db, id, Some(etag.is_some())).await?
    } else {
        every
    };
    let interval = if churn >= CHURN_FETCHES {
        CHURN_INTERVAL
    } else {
        every
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
    if row.kind == "caldav" {
        // Design 72b D4.6: the kid's events in this calendar read one by one - a parent's edit or delete handed back.
        crate::calendar_kid_events::after_fetch(state, id, &resources, now, report_at).await?;
    }
    if changed {
        nudge(state, id).await;
        return Ok(Outcome::Changed);
    }
    Ok(Outcome::Unchanged)
}

/// A source's interval as the parent chose it (migration 0188): a CalDAV calendar its account's, a link the shortest
/// of the phones' rows that name it (one fetch for all of them); [INTERVAL] for a source gone meanwhile. A CalDAV
/// calendar with no validator (its server gave neither `getctag` nor `sync-token`) reads the whole calendar at every
/// check, so it is checked at most every [INTERVAL] whatever the choice.
pub async fn interval_of(db: &SqlitePool, id: i64) -> Result<TimeDelta, sqlx::Error> {
    interval_for(db, id, None).await
}

/// [interval_of], with `validator` the CalDAV calendar's as its fetch just answered (`None`: the stored one's).
async fn interval_for(
    db: &SqlitePool,
    id: i64,
    validator: Option<bool>,
) -> Result<TimeDelta, sqlx::Error> {
    let row: Option<(String, Option<i64>, bool)> = sqlx::query_as(
        "SELECT s.kind, CASE WHEN s.kind = 'caldav' \
         THEN (SELECT a.check_minutes FROM caldav_accounts a WHERE a.id = s.account_id) \
         ELSE (SELECT MIN(d.check_minutes) FROM device_calendar_feeds d WHERE d.source_id = s.id) END, \
         COALESCE(s.etag, '') <> '' FROM calendar_sources s WHERE s.id = ?",
    )
    .bind(id)
    .fetch_optional(db)
    .await?;
    let Some((kind, minutes, stored)) = row else {
        return Ok(INTERVAL);
    };
    let every = minutes.map_or(INTERVAL, TimeDelta::minutes);
    Ok(if kind == "caldav" && !validator.unwrap_or(stored) {
        every.max(INTERVAL)
    } else {
        every
    })
}

/// After a source's interval may have changed (the parent's choice, a phone attaching to a shared link or leaving
/// it): one waiting its regular interval (no failure, no churn, not due yet) is next checked that long after its
/// last check - at the next pass when that is past. A failing source keeps its backoff and a due one stays due. Only
/// the `next_at` read here is replaced: a fetch stored in between wrote its own, counted with the interval as it
/// read it after its request, so it is read once more and judged again (once: a second race means the fetches are
/// counting with the new choice already).
pub async fn reschedule(db: &SqlitePool, id: i64, now: DateTime<Utc>) -> Result<(), sqlx::Error> {
    for _ in 0..2 {
        let row: Option<(Option<String>, String, i64, i64)> = sqlx::query_as(
            "SELECT tried_at, next_at, failures, churn FROM calendar_sources WHERE id = ?",
        )
        .bind(id)
        .fetch_optional(db)
        .await?;
        let Some((tried_at, next_at, failures, churn)) = row else {
            return Ok(());
        };
        let (Some(tried), Some(next)) = (
            tried_at.as_deref().and_then(parse_stamp),
            parse_stamp(&next_at),
        ) else {
            return Ok(());
        };
        if failures > 0 || churn >= CHURN_FETCHES || next <= now {
            return Ok(());
        }
        let at = tried + interval_of(db, id).await?;
        let written =
            sqlx::query("UPDATE calendar_sources SET next_at = ? WHERE id = ? AND next_at = ?")
                .bind(stamp(at))
                .bind(id)
                .bind(&next_at)
                .execute(db)
                .await?;
        if written.rows_affected() > 0 {
            break;
        }
    }
    Ok(())
}

/// The scheduler (§2.1): every due source, most overdue first, [SPACING] apart; then a minute's sleep. A source
/// checked every 5 minutes never starves one checked hourly: each pass takes the due ones by `next_at`, and a fetched
/// source's next is its interval on, so one due longer goes first in the next pass. [MAX_SOURCES] checked every 5
/// minutes is 50 fetches [SPACING] apart, under 4 of the 5 minutes; past that every source is checked later, in turn.
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
    // CalDAV accounts no phone has a calendar of go a day after their last Test (design 72 step 2).
    if let Err(err) = crate::caldav::sweep(&state.db, Utc::now()).await {
        tracing::error!(%err, "couldn't sweep the unused CalDAV accounts");
    }
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
        // Its new row's choice may be shorter than the siblings' (0188).
        reschedule(&state.db, source_id, now).await?;
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
        ..
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
    if inserted.is_none() {
        // Attached to the source another add made meanwhile: as above.
        reschedule(&state.db, source_id, now).await?;
    }
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
    /// The music key's fingerprint the link is sealed with: another than today's key's means the key file was made
    /// anew (E6's L3) - the link can't be fetched until it is pasted again, though `error` says so only after its
    /// next fetch.
    pub key_fp: String,
    /// `ics` or `caldav` (design 72 step 2).
    pub kind: String,
    /// A CalDAV calendar's account's state (`ok`, `login_refused`, `host_changed`).
    pub account_state: Option<String>,
    /// The CalDAV calendar's account row (its page, `/devices/{id}/calendar/caldav/{account}`).
    pub account_id: Option<i64>,
    /// When the next fetch is due (a failing link's retry).
    pub next_at: String,
    /// This phone's "Check" for a link, in minutes (0188; a CalDAV calendar's is its account's).
    pub check_minutes: i64,
}

pub async fn links(db: &SqlitePool, device_id: i64) -> Result<Vec<LinkRow>, sqlx::Error> {
    sqlx::query_as(
        "SELECT s.uid, d.name, s.host, s.lan_http, s.ok_at, s.error, s.error_status, s.events, s.cut, \
         s.unknown_tz, s.churn, s.url_key_fp AS key_fp, s.kind, a.state AS account_state, \
         s.account_id, s.next_at, d.check_minutes FROM device_calendar_feeds d JOIN calendar_sources s ON s.id = d.source_id \
         LEFT JOIN caldav_accounts a ON a.id = s.account_id WHERE d.device_id = ? ORDER BY d.created_at, s.uid",
    )
    .bind(device_id)
    .fetch_all(db)
    .await
}

/// The contract's link (`contracts/core/calendar_feeds.json`, its feed `testdata/calendar_feeds/contract.ics`).
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

    /// Design 116 §2.1 (QA M7): every code a calendar fetch writes has its class - the codes are text, so they are
    /// read from the fetches' source - and the status codes split as the table says.
    #[test]
    fn every_feed_code_is_classed() {
        use crate::integration_health::Class::*;
        let pattern = regex::Regex::new(
            r#"(?:FeedError::code\(|code: |not_multistatus\([^,]+, )"([a-z_]+)""#,
        )
        .unwrap();
        let mut codes = std::collections::BTreeSet::from(["empty"]);
        let files = ["src/calendar_feeds.rs", "src/caldav.rs", "src/ics.rs"]
            .map(|f| std::fs::read_to_string(f).unwrap());
        for text in &files {
            for found in pattern.captures_iter(text) {
                codes.insert(found.get(1).unwrap().as_str());
            }
        }
        assert!(codes.len() > 10, "{codes:?}");
        for code in codes {
            let code: &'static str = Box::leak(code.to_string().into_boxed_str());
            assert!(
                class_of(&FeedError::code(code)).is_some(),
                "{code} has no class"
            );
        }
        let status = |status| {
            class_of(&FeedError {
                code: "status",
                status: Some(status),
            })
        };
        for (code, class) in [
            (503, Transient),
            (408, Transient),
            (429, Transient),
            (401, Parent),
            (410, Parent),
        ] {
            assert_eq!(status(code), Some(class), "{code}");
        }
        for (code, class) in [
            ("network", Transient),
            ("timeout", Transient),
            ("empty", Transient),
            ("not_found", Parent),
            ("not_listed", Parent),
            ("not_caldav", Parent),
            ("unreadable", Parent),
            ("insecure", Parent),
            ("private_address", Parent),
            ("too_big", Parent),
            ("not_calendar", Parent),
            ("login_refused", Stop),
            ("host_changed", Stop),
            ("key_lost", Stop),
        ] {
            assert_eq!(class_of(&FeedError::code(code)), Some(class), "{code}");
        }
    }

    /// The shared contract (design 72 §3): `feed` is the document for `contract.ics`, `keys` the phone's calendar key
    /// of each link, `policy` and `report` carry its uid and rev; the launcher's `CalendarFeedsTest` maps `feed` to
    /// exactly `provider`. `CALENDAR_FEEDS_CONTRACT_WRITE=1` rewrites `feed`, `keys` and the uids and revs.
    #[test]
    fn the_contract() {
        let path = crate::contracts::path("core/calendar_feeds.json");
        let mut contract: serde_json::Value =
            serde_json::from_str(&std::fs::read_to_string(&path).unwrap()).unwrap();
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
            std::fs::write(&path, pretty).unwrap();
        }
        assert_eq!(
            contract["feed"], feed,
            "the feed changed - rewrite the contract"
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
