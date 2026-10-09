//! The music sweep (design `docs/design/21b-music-server-sweep.md` §1-§2, with its QA review,
//! decisions and the user's answer to open question 1): the server lists every NRK and RSS entry,
//! sequentially and incrementally, so the phones never list a source. One sweep serves every phone;
//! a change nudges only the phones with the entry.
//!
//! - **Cadence** ([schedule], [due]): the family-wide setting `I` (1/3/6/12/24 h, default 6 = the
//!   Pi); RSS every `I`, NRK every `max(I, min(2I, 12 h))`, an entry no phone has once a day after
//!   its first fill. Check now, a new entry, a flagged item or an interrupted fill go first. A
//!   failure backs off 15 min · 4^(n-1), capped at the interval, daily after 7 days.
//! - **One check** ([check_entry]): a start stamp (`checked_at`, `failures` + 1, so a crash backs off
//!   like a failure), then the work, then one transaction that first checks the entry still exists.
//!   A failing root, listing page or feed keeps the last good list; a failing manifest only leaves
//!   its item pending. 250 requests or 10 minutes commit what is resolved.
//! - **The load rule**: every request goes through `AppState.music_fetch` (one in flight
//!   server-wide, 200 ms apart, `music_net::Gated`); checks are 4 s apart; 3 network or 5xx
//!   failures of one host in a pass defer that host's remaining entries ([Breaker]).
//! - **Rechecks** ([flag_reports]): a phone's 401/403/404/410 for an item of the current list flags
//!   it; the next check re-resolves it (one manifest, or an unconditional feed GET), at most once
//!   per item a day, a whole entry once a day, 300 manifests a day server-wide.
//! - **Covers**: NRK's square image or the RSS channel image, as a 512 px JPEG in
//!   `photos::MUSIC_COVERS`, at most once a day.
//! - **The listing** ([build_listing]): `{v, entry, version, items}`, the `ok` and `gone` items
//!   oldest first, at most [MAX_LISTING_BYTES]; served by `GET /api/devices/music/entries/{id}/items`.
//!
//! Ported in behaviour from palchrb/vibb `pi/vibb/content.py` (`_catalog`, `_new_episodes`,
//! `_psapi_episodes`, `_manifest_url`, `_series`, `sync_feed`) and `pi/vibb/library.py`
//! (`_cache_sweeper`, `SYNC_INTERVAL_S`, `SYNC_STAGGER_S`, `SYNC_DELAY_S`; MIT, Copyright (c)
//! palchrb), reimplemented in Rust under GPL-3.0 with 21b's changes (see NOTICE.md).

use std::collections::{HashMap, HashSet};
use std::time::Duration;

use chrono::{DateTime, NaiveDateTime, TimeDelta, Utc};
use serde::Serialize;
use sha2::{Digest, Sha256};

use crate::AppState;
use crate::music::{CheckError, Link, PSAPI};
use crate::music_net::{self, FetchError, Kind, Reach, Source, SourceRequest, SourceResponse};
use crate::music_sources::{self as sources, Manifest};

/// NRK lists keep at most this many episodes (vibb `MAX_EPISODES`), RSS lists this many.
pub const MAX_NRK_ITEMS: usize = 100;
pub const MAX_RSS_ITEMS: usize = 1000;
/// A served listing is at most this big; past it items are dropped from the end opposite the
/// anchor, `gone` first, and the card says so (`cut`).
pub const MAX_LISTING_BYTES: usize = 1_000_000;
/// One check's budget: at the limit what is resolved is committed and the rest stays pending.
pub const MAX_CHECK_REQUESTS: u32 = 250;
pub const MAX_CHECK_TIME: Duration = Duration::from_secs(600);
/// Items left pending after a failure are retried at most this many per check...
pub const MAX_PENDING_RETRIES: usize = 10;
/// ...and become `gone` after this long pending.
pub const PENDING_DAYS: i64 = 14;
/// Checks in a pass start at least this far apart (vibb `SYNC_STAGGER_S`).
pub const ENTRY_SPACING: Duration = Duration::from_secs(4);
/// The first pass after a start (vibb `SYNC_DELAY_S`).
pub const FIRST_PASS_DELAY: Duration = Duration::from_secs(60);
/// A long pass nudges the phones waiting for it this often.
pub const NUDGE_FLUSH: Duration = Duration::from_secs(300);
/// Network or 5xx failures of one host in a pass that defer its remaining entries...
pub const BREAKER_FAILURES: u32 = 3;
/// ...until this much later.
pub const BREAKER_PAUSE_MINUTES: i64 = 15;
/// Re-resolve manifests a day, server-wide (§2.5; expiry re-resolves count too).
pub const RECHECKS_PER_DAY: i64 = 300;
/// The sweep settings (hours) and the default (the Pi's).
pub const SWEEP_HOURS: [i64; 5] = [1, 3, 6, 12, 24];
pub const DEFAULT_SWEEP_HOURS: i64 = 6;
/// Check now: once per entry per 15 min, 12 an hour and 48 a day in all.
pub const CHECK_NOW_GAP_MINUTES: i64 = 15;
pub const CHECK_NOW_PER_HOUR: i64 = 12;
pub const CHECK_NOW_PER_DAY: i64 = 48;
/// The listing format (`v`).
pub const LISTING_FORMAT: u32 = 1;

const RSS_FALLBACK: &str = "https://podkast.nrk.no/program";

// ------------------------------------------------------------------------------------------------
// Time
// ------------------------------------------------------------------------------------------------

/// A stamp as stored: UTC "YYYY-MM-DD HH:MM:SS" (the format `datetime('now')` writes).
pub fn stamp(time: DateTime<Utc>) -> String {
    time.format("%Y-%m-%d %H:%M:%S").to_string()
}

pub fn parse_stamp(text: &str) -> Option<DateTime<Utc>> {
    NaiveDateTime::parse_from_str(text, "%Y-%m-%d %H:%M:%S")
        .ok()
        .map(|t| t.and_utc())
}

fn hours(n: i64) -> TimeDelta {
    TimeDelta::hours(n)
}

// ------------------------------------------------------------------------------------------------
// The sweeper's shared state
// ------------------------------------------------------------------------------------------------

/// `AppState.music_sweep`: the wake-up, the entry being checked (the cards' "Checking…") and the
/// hosts a breaker paused.
pub struct Sweep {
    notify: tokio::sync::Notify,
    current: std::sync::Mutex<Option<i64>>,
    paused: std::sync::Mutex<HashMap<String, DateTime<Utc>>>,
    /// [MAX_CHECK_REQUESTS] and [ENTRY_SPACING] (smaller in the tests).
    request_budget: std::sync::atomic::AtomicU32,
    spacing_ms: std::sync::atomic::AtomicU64,
}

impl Default for Sweep {
    fn default() -> Self {
        Sweep {
            notify: tokio::sync::Notify::new(),
            current: Default::default(),
            paused: Default::default(),
            request_budget: MAX_CHECK_REQUESTS.into(),
            spacing_ms: (ENTRY_SPACING.as_millis() as u64).into(),
        }
    }
}

impl Sweep {
    /// The tests' knobs: a smaller check budget, no spacing between checks.
    #[cfg(test)]
    pub fn set_limits(&self, request_budget: u32, spacing: Duration) {
        use std::sync::atomic::Ordering;
        self.request_budget.store(request_budget, Ordering::SeqCst);
        self.spacing_ms
            .store(spacing.as_millis() as u64, Ordering::SeqCst);
    }

    /// Whether a wake is waiting (taking it).
    #[cfg(test)]
    pub async fn woken(&self) -> bool {
        tokio::time::timeout(Duration::from_millis(20), self.notify.notified())
            .await
            .is_ok()
    }

    fn budget(&self) -> u32 {
        self.request_budget
            .load(std::sync::atomic::Ordering::SeqCst)
    }

    fn spacing(&self) -> Duration {
        Duration::from_millis(self.spacing_ms.load(std::sync::atomic::Ordering::SeqCst))
    }

    /// Runs a pass soon. `notify_one` keeps a wake that arrives during a pass.
    pub fn wake(&self) {
        self.notify.notify_one();
    }

    /// The entry being checked right now.
    pub fn current(&self) -> Option<i64> {
        *self.current.lock().unwrap_or_else(|e| e.into_inner())
    }

    fn set_current(&self, entry: Option<i64>) {
        *self.current.lock().unwrap_or_else(|e| e.into_inner()) = entry;
    }

    fn paused_hosts(&self, now: DateTime<Utc>) -> HashMap<String, DateTime<Utc>> {
        let mut paused = self.paused.lock().unwrap_or_else(|e| e.into_inner());
        paused.retain(|_, until| *until > now);
        paused.clone()
    }

    fn pause(&self, host: String, until: DateTime<Utc>) {
        self.paused
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .insert(host, until);
    }
}

/// The per-host breaker of one pass: [BREAKER_FAILURES] network or 5xx failures from a host
/// defer its remaining work in the pass, without counting a failure for those entries.
#[derive(Default, Debug)]
pub struct Breaker {
    failures: HashMap<String, u32>,
}

impl Breaker {
    pub fn broken(&self, host: &str) -> bool {
        self.failures.get(host).copied().unwrap_or(0) >= BREAKER_FAILURES
    }

    fn fail(&mut self, host: &str) {
        *self.failures.entry(host.to_string()).or_default() += 1;
    }

    fn broken_hosts(&self) -> Vec<String> {
        self.failures
            .iter()
            .filter(|(_, n)| **n >= BREAKER_FAILURES)
            .map(|(host, _)| host.clone())
            .collect()
    }
}

// ------------------------------------------------------------------------------------------------
// Settings and Check now
// ------------------------------------------------------------------------------------------------

pub async fn sweep_hours(db: &sqlx::SqlitePool) -> Result<i64, sqlx::Error> {
    let hours: Option<i64> =
        sqlx::query_scalar("SELECT sweep_hours FROM music_settings WHERE id = 1")
            .fetch_optional(db)
            .await?;
    Ok(hours
        .filter(|h| SWEEP_HOURS.contains(h))
        .unwrap_or(DEFAULT_SWEEP_HOURS))
}

/// When Check now on `entry_id` is next allowed; `None` = now (§3: 15 min per entry, 12 an hour,
/// 48 a day in all).
pub async fn check_now_blocked_until(
    db: &sqlx::SqlitePool,
    entry_id: i64,
    now: DateTime<Utc>,
) -> Result<Option<DateTime<Utc>>, sqlx::Error> {
    let requested: Option<Option<String>> =
        sqlx::query_scalar("SELECT requested_at FROM music_listings WHERE entry_id = ?")
            .bind(entry_id)
            .fetch_optional(db)
            .await?;
    let mut until: Option<DateTime<Utc>> = requested
        .flatten()
        .as_deref()
        .and_then(parse_stamp)
        .map(|at| at + TimeDelta::minutes(CHECK_NOW_GAP_MINUTES))
        .filter(|at| *at > now);
    let recent: Vec<String> =
        sqlx::query_scalar("SELECT at FROM music_check_requests WHERE at > ? ORDER BY at DESC")
            .bind(stamp(now - hours(24)))
            .fetch_all(db)
            .await?;
    let times: Vec<DateTime<Utc>> = recent.iter().filter_map(|t| parse_stamp(t)).collect();
    let in_hour: Vec<&DateTime<Utc>> = times.iter().filter(|t| **t > now - hours(1)).collect();
    if in_hour.len() as i64 >= CHECK_NOW_PER_HOUR {
        let oldest = in_hour[CHECK_NOW_PER_HOUR as usize - 1];
        until = until.max(Some(*oldest + hours(1)));
    }
    if times.len() as i64 >= CHECK_NOW_PER_DAY {
        let oldest = times[CHECK_NOW_PER_DAY as usize - 1];
        until = until.max(Some(oldest + hours(24)));
    }
    Ok(until)
}

/// Records a Check now (the caller checked [check_now_blocked_until]) and marks the entry as
/// requested; the caller wakes the sweeper.
pub async fn request_check(
    db: &sqlx::SqlitePool,
    entry_id: i64,
    now: DateTime<Utc>,
) -> Result<(), sqlx::Error> {
    let mut tx = db.begin().await?;
    sqlx::query("DELETE FROM music_check_requests WHERE at <= ?")
        .bind(stamp(now - hours(24)))
        .execute(&mut *tx)
        .await?;
    sqlx::query("INSERT INTO music_check_requests (at) VALUES (?)")
        .bind(stamp(now))
        .execute(&mut *tx)
        .await?;
    sqlx::query(
        "INSERT INTO music_listings (entry_id, requested_at) VALUES (?, ?) \
         ON CONFLICT(entry_id) DO UPDATE SET requested_at = excluded.requested_at",
    )
    .bind(entry_id)
    .bind(stamp(now))
    .execute(&mut *tx)
    .await?;
    tx.commit().await
}

// ------------------------------------------------------------------------------------------------
// Cadence
// ------------------------------------------------------------------------------------------------

/// Why an entry is due, most urgent first (§2.2).
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord)]
pub enum Why {
    /// Check now: `requested_at > checked_at`.
    CheckNow,
    /// Never listed and never failed: a new or imported entry.
    New,
    /// A flagged item, pending items never tried (an interrupted fill) or a refill.
    Rework,
    /// Its interval, or its backoff after a failure.
    Interval,
}

/// One NRK/RSS entry's place in the schedule.
#[derive(Debug, Clone)]
pub struct Scheduled {
    pub entry_id: i64,
    pub why: Why,
    /// When it is due (for `Why::Interval`; the others are due now).
    pub at: DateTime<Utc>,
    /// Only flagged NRK items to re-resolve: no listing part (§2.2's restore of `failures`).
    pub recheck_only: bool,
    /// The host its root, pages or feed come from (the breaker's key).
    pub host: String,
    order: (i64, String, i64),
}

#[derive(sqlx::FromRow)]
struct ScheduleRow {
    entry_id: i64,
    source: String,
    target: Option<String>,
    play_order: String,
    created_at: String,
    sort: i64,
    ticked: bool,
    version: Option<String>,
    listed_at: Option<String>,
    checked_at: Option<String>,
    requested_at: Option<String>,
    failing_since: Option<String>,
    failures: i64,
    keep_end: Option<String>,
    capped: bool,
    flagged: bool,
    untried: bool,
}

/// How often a listed entry is checked (§2.2's table).
pub fn interval(source: &str, ticked: bool, setting_hours: i64) -> TimeDelta {
    if !ticked {
        return hours(24);
    }
    match source {
        "nrk" => hours(setting_hours.max((2 * setting_hours).min(12))),
        _ => hours(setting_hours),
    }
}

/// The wait after `failures` failed checks in a row: 15 min · 4^(n-1), at most `interval`.
pub fn backoff(failures: i64, interval: TimeDelta) -> TimeDelta {
    let steps = (failures - 1).clamp(0, 10) as u32;
    let minutes = 15i64.saturating_mul(4i64.saturating_pow(steps));
    TimeDelta::minutes(minutes).min(interval)
}

/// Which end an NRK list keeps (the user's answer to open question 1): a podcast the newest 100,
/// `serie/<slug>/<programId>` the first 100 from that programme; a series by its play order, and
/// `auto` the newest when the series has more than 100 episodes, else all of it from the start.
pub fn desired_end(link: &Link, play_order: &str, capped: bool) -> &'static str {
    match link {
        Link::NrkSeries {
            program: Some(_), ..
        } => "first",
        Link::NrkSeries { program: None, .. } => match play_order {
            "newest_first" => "newest",
            "oldest_first" => "first",
            _ if capped => "newest",
            _ => "first",
        },
        _ => "newest",
    }
}

fn host_for(link: &Link) -> String {
    match link {
        Link::Rss { url } => music_net::host_of(url).unwrap_or_default(),
        _ => "psapi.nrk.no".to_string(),
    }
}

fn schedule_one(
    row: &ScheduleRow,
    setting_hours: i64,
    recheck_room: bool,
    now: DateTime<Utc>,
) -> Option<Scheduled> {
    let link = crate::music::parse_link(row.target.as_deref()?).ok()?;
    let checked = row.checked_at.as_deref().and_then(parse_stamp);
    let requested = row.requested_at.as_deref().and_then(parse_stamp);
    let created = parse_stamp(&row.created_at).unwrap_or(now);
    let every = interval(&row.source, row.ticked, setting_hours);
    // Its regular time: after a success the interval, after a failure the backoff (daily after a
    // week of failures).
    let regular = if row.failures > 0 {
        let at = checked.unwrap_or(now);
        let since = row
            .failing_since
            .as_deref()
            .and_then(parse_stamp)
            .unwrap_or(at);
        if since + TimeDelta::days(7) <= now {
            at + hours(24)
        } else {
            at + backoff(row.failures, every)
        }
    } else {
        row.listed_at
            .as_deref()
            .and_then(parse_stamp)
            .or(checked)
            .map_or(now, |at| at + every)
    };
    let refill = row.source == "nrk"
        && row.keep_end.is_some()
        && row.keep_end.as_deref() != Some(desired_end(&link, &row.play_order, row.capped));
    let host = host_for(&link);
    let make = |why: Why, at: DateTime<Utc>, recheck_only: bool, order: (i64, String, i64)| {
        Some(Scheduled {
            entry_id: row.entry_id,
            why,
            at,
            recheck_only,
            host: host.clone(),
            order,
        })
    };
    if let Some(requested) = requested
        && checked.is_none_or(|c| requested > c)
    {
        return make(
            Why::CheckNow,
            requested.min(now),
            false,
            (0, stamp(requested), row.sort),
        );
    }
    if row.version.is_none() && row.failures == 0 {
        // Newest first, so a single add doesn't wait behind an import's backlog.
        return make(
            Why::New,
            created.min(now),
            false,
            (-created.timestamp(), String::new(), row.sort),
        );
    }
    if row.failures == 0 && (row.untried || refill) {
        return make(
            Why::Rework,
            now,
            false,
            (row.sort, String::new(), row.entry_id),
        );
    }
    if row.flagged && recheck_room {
        // Due for its own sake too: a whole check that also handles the flags.
        let recheck_only = regular > now && row.source == "nrk";
        return make(
            Why::Rework,
            now,
            recheck_only,
            (row.sort, String::new(), row.entry_id),
        );
    }
    make(
        Why::Interval,
        regular,
        false,
        (regular.timestamp(), String::new(), row.sort),
    )
}

/// Recheck manifests made in the 24 h before `now`, server-wide.
async fn rechecks_today(db: &sqlx::SqlitePool, now: DateTime<Utc>) -> Result<i64, sqlx::Error> {
    sqlx::query_scalar("SELECT COUNT(*) FROM music_items WHERE rechecked_at > ?")
        .bind(stamp(now - hours(24)))
        .fetch_one(db)
        .await
}

/// Every NRK/RSS entry with its due time and reason - a pure function of the stamps, the setting,
/// the ticks and `now` (§2.2). Sorted: what is due first (by reason), then by due time.
pub async fn schedule(
    db: &sqlx::SqlitePool,
    now: DateTime<Utc>,
) -> Result<Vec<Scheduled>, sqlx::Error> {
    let setting = sweep_hours(db).await?;
    let recheck_room = rechecks_today(db, now).await? < RECHECKS_PER_DAY;
    let rows: Vec<ScheduleRow> = sqlx::query_as(
        "SELECT e.id AS entry_id, e.source, e.target, e.play_order, e.created_at, e.sort, \
           EXISTS(SELECT 1 FROM device_music_entries d WHERE d.entry_id = e.id) AS ticked, \
           l.version, l.listed_at, l.checked_at, l.requested_at, l.failing_since, \
           COALESCE(l.failures, 0) AS failures, l.keep_end, COALESCE(l.capped, 0) AS capped, \
           EXISTS(SELECT 1 FROM music_items i WHERE i.entry_id = e.id AND i.recheck = 1) \
             AS flagged, \
           EXISTS(SELECT 1 FROM music_items i WHERE i.entry_id = e.id AND i.state = 'pending' \
             AND i.attempts = 0) AS untried \
         FROM music_entries e LEFT JOIN music_listings l ON l.entry_id = e.id \
         WHERE e.source IN ('nrk', 'rss') AND e.target IS NOT NULL",
    )
    .fetch_all(db)
    .await?;
    let mut all: Vec<Scheduled> = rows
        .iter()
        .filter_map(|row| schedule_one(row, setting, recheck_room, now))
        .collect();
    all.sort_by(|a, b| {
        let a_due = a.at <= now;
        let b_due = b.at <= now;
        b_due
            .cmp(&a_due)
            .then(a.why.cmp(&b.why))
            .then(a.order.cmp(&b.order))
            .then(a.at.cmp(&b.at))
            .then(a.entry_id.cmp(&b.entry_id))
    });
    Ok(all)
}

/// What is due at `now`, most urgent first.
pub async fn due(db: &sqlx::SqlitePool, now: DateTime<Utc>) -> Result<Vec<Scheduled>, sqlx::Error> {
    Ok(schedule(db, now)
        .await?
        .into_iter()
        .filter(|s| s.at <= now)
        .collect())
}

// ------------------------------------------------------------------------------------------------
// Rows
// ------------------------------------------------------------------------------------------------

/// One `music_items` row.
#[derive(sqlx::FromRow, Debug, Clone, PartialEq, Eq)]
pub struct Item {
    pub key: String,
    pub state: String,
    pub title: Option<String>,
    pub url: Option<String>,
    pub hls: bool,
    pub duration_ms: Option<i64>,
    pub art_url: Option<String>,
    pub published_at: Option<String>,
    pub available_until: Option<String>,
    pub first_seen_at: String,
    pub attempts: i64,
    pub reported_at: Option<String>,
    pub rechecked_at: Option<String>,
    pub recheck: bool,
}

impl Item {
    fn new(key: String, now: DateTime<Utc>) -> Item {
        Item {
            key,
            state: "pending".to_string(),
            title: None,
            url: None,
            hls: false,
            duration_ms: None,
            art_url: None,
            published_at: None,
            available_until: None,
            first_seen_at: stamp(now),
            attempts: 0,
            reported_at: None,
            rechecked_at: None,
            recheck: false,
        }
    }

    fn from_stub(stub: &sources::Stub, now: DateTime<Utc>) -> Item {
        Item {
            title: stub.title.clone(),
            duration_ms: stub.duration_ms,
            art_url: stub.art.clone().filter(|a| music_net::media_url_ok(a)),
            published_at: stub.published_at.map(stamp),
            available_until: stub.available_until.map(stamp),
            ..Item::new(stub.key.clone(), now)
        }
    }

    fn listed(&self) -> bool {
        self.state != "pending"
    }
}

const ITEM_COLUMNS: &str = "key, state, title, url, hls, duration_ms, art_url, published_at, \
     available_until, first_seen_at, attempts, reported_at, rechecked_at, recheck";

pub async fn load_items(
    db: &mut sqlx::SqliteConnection,
    entry_id: i64,
) -> Result<Vec<Item>, sqlx::Error> {
    sqlx::query_as(&format!(
        "SELECT {ITEM_COLUMNS} FROM music_items WHERE entry_id = ? ORDER BY seq"
    ))
    .bind(entry_id)
    .fetch_all(&mut *db)
    .await
}

/// One `music_listings` row (the defaults before the first check), every column as stored.
#[derive(sqlx::FromRow, Debug, Clone, Default)]
#[allow(dead_code)] // `checked_at` and `requests` are for the log and the tests
pub struct Listing {
    pub entry_id: i64,
    pub title: Option<String>,
    pub lan: Option<bool>,
    pub fallback: bool,
    pub capped: bool,
    pub cut: bool,
    pub keep_end: Option<String>,
    pub item_count: i64,
    pub bytes: i64,
    pub cover_url: Option<String>,
    pub cover_hash: Option<String>,
    pub cover_at: Option<String>,
    pub root_at: Option<String>,
    pub etag: Option<String>,
    pub last_modified: Option<String>,
    pub body_sha256: Option<String>,
    pub parser: Option<i64>,
    pub version: Option<String>,
    pub listed_at: Option<String>,
    pub checked_at: Option<String>,
    pub requested_at: Option<String>,
    pub full_recheck_at: Option<String>,
    pub error: Option<String>,
    pub error_at: Option<String>,
    pub failing_since: Option<String>,
    pub failures: i64,
    pub requests: i64,
}

pub async fn load_listing(
    db: &sqlx::SqlitePool,
    entry_id: i64,
) -> Result<Option<Listing>, sqlx::Error> {
    sqlx::query_as("SELECT * FROM music_listings WHERE entry_id = ?")
        .bind(entry_id)
        .fetch_optional(db)
        .await
}

// ------------------------------------------------------------------------------------------------
// The listing document
// ------------------------------------------------------------------------------------------------

#[derive(Serialize, Debug, Clone, PartialEq, Eq)]
pub struct ListingItem {
    pub key: String,
    pub title: Option<String>,
    /// `null` = not available at the source now (`gone`).
    pub url: Option<String>,
    pub hls: bool,
    pub duration_ms: Option<i64>,
    pub art: Option<String>,
}

#[derive(Serialize)]
struct ListingBody<'a> {
    v: u32,
    entry: i64,
    items: &'a [ListingItem],
}

#[derive(Serialize)]
struct ListingDocument<'a> {
    v: u32,
    entry: i64,
    version: &'a str,
    items: &'a [ListingItem],
}

/// A built listing: what is served and what the library names.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct BuiltListing {
    pub version: String,
    pub json: Vec<u8>,
    pub count: usize,
    pub cut: bool,
}

/// The listing of `items` (oldest first): the `ok` and `gone` ones, `gone` sent with `url: null`.
/// Past [MAX_LISTING_BYTES] items are dropped from the end opposite the anchor (the front for a
/// list that keeps the newest, the end for one anchored at the start), `gone` ones first. `None`
/// when nothing is listed.
pub fn build_listing(
    entry_id: i64,
    items: &[Item],
    anchored_at_start: bool,
) -> Option<BuiltListing> {
    let mut listed: Vec<ListingItem> = items
        .iter()
        .filter(|i| i.listed())
        .map(|i| ListingItem {
            key: i.key.clone(),
            title: i.title.clone(),
            url: if i.state == "ok" { i.url.clone() } else { None },
            hls: i.hls,
            duration_ms: i.duration_ms,
            art: i.art_url.clone(),
        })
        .collect();
    if listed.is_empty() {
        return None;
    }
    let body_len = |items: &[ListingItem]| {
        serde_json::to_vec(&ListingDocument {
            v: LISTING_FORMAT,
            entry: entry_id,
            version: "0123456789abcdef",
            items,
        })
        .map_or(usize::MAX, |b| b.len())
    };
    let mut cut = false;
    let mut size = body_len(&listed);
    if size > MAX_LISTING_BYTES {
        let sizes: Vec<usize> = listed
            .iter()
            .map(|i| serde_json::to_vec(i).map_or(0, |b| b.len()) + 1)
            .collect();
        let mut keep: Vec<bool> = vec![true; listed.len()];
        // The drop order: gone items from the dropping end, then everything from that end.
        let from_end: Vec<usize> = if anchored_at_start {
            (0..listed.len()).rev().collect()
        } else {
            (0..listed.len()).collect()
        };
        let order: Vec<usize> = from_end
            .iter()
            .copied()
            .filter(|i| listed[*i].url.is_none())
            .chain(
                from_end
                    .iter()
                    .copied()
                    .filter(|i| listed[*i].url.is_some()),
            )
            .collect();
        for index in order {
            if size <= MAX_LISTING_BYTES {
                break;
            }
            keep[index] = false;
            size = size.saturating_sub(sizes[index]);
        }
        let mut position = 0;
        listed.retain(|_| {
            let kept = keep[position];
            position += 1;
            kept
        });
        while body_len(&listed) > MAX_LISTING_BYTES && !listed.is_empty() {
            if anchored_at_start {
                listed.pop();
            } else {
                listed.remove(0);
            }
        }
        cut = true;
    }
    if listed.is_empty() {
        return None;
    }
    let body = serde_json::to_vec(&ListingBody {
        v: LISTING_FORMAT,
        entry: entry_id,
        items: &listed,
    })
    .expect("a listing always serializes");
    let version = hex::encode(Sha256::digest(&body))[..16].to_string();
    let json = serde_json::to_vec(&ListingDocument {
        v: LISTING_FORMAT,
        entry: entry_id,
        version: &version,
        items: &listed,
    })
    .expect("a listing always serializes");
    Some(BuiltListing {
        version,
        json,
        count: listed.len(),
        cut,
    })
}

/// The listing a phone gets for `entry_id` (`GET /api/devices/music/entries/{id}/items`): only for
/// an entry it has ticked, and only once listed. Built from the rows in one read transaction, the
/// same way the sweep built the version it stored.
pub async fn device_listing(
    db: &sqlx::SqlitePool,
    device_id: i64,
    entry_id: i64,
) -> Result<Option<BuiltListing>, sqlx::Error> {
    let mut tx = db.begin().await?;
    let found: Option<(Option<String>, Option<String>)> = sqlx::query_as(
        "SELECT l.version, l.keep_end FROM music_listings l \
         JOIN music_entries e ON e.id = l.entry_id \
         JOIN device_music_entries d ON d.entry_id = l.entry_id \
         WHERE l.entry_id = ? AND d.device_id = ? AND e.source IN ('nrk', 'rss')",
    )
    .bind(entry_id)
    .bind(device_id)
    .fetch_optional(&mut *tx)
    .await?;
    let Some((Some(_version), keep_end)) = found else {
        return Ok(None);
    };
    let items = load_items(&mut tx, entry_id).await?;
    tx.commit().await?;
    Ok(build_listing(
        entry_id,
        &items,
        keep_end.as_deref() == Some("first"),
    ))
}

// ------------------------------------------------------------------------------------------------
// Phone reports (§2.5)
// ------------------------------------------------------------------------------------------------

/// Flags the items a phone reports failing, when the report counts (§2.5): an entry it has ticked,
/// a key of that entry, the current listing version, a 401/403/404/410, and no re-resolve of the
/// item in the last 24 h. Returns how many were flagged; the caller wakes the sweeper.
pub async fn flag_reports(
    db: &sqlx::SqlitePool,
    device_id: i64,
    report: &crate::music::MusicState,
    now: DateTime<Utc>,
) -> Result<u64, sqlx::Error> {
    let mut flagged = 0;
    for error in &report.item_errors {
        if !matches!(
            error.error.as_str(),
            "http_401" | "http_403" | "http_404" | "http_410"
        ) {
            continue;
        }
        flagged += sqlx::query(
            "UPDATE music_items SET recheck = 1, reported_at = ? \
             WHERE entry_id = ? AND key = ? AND recheck = 0 \
               AND (rechecked_at IS NULL OR rechecked_at <= ?) \
               AND entry_id IN (SELECT entry_id FROM device_music_entries WHERE device_id = ?) \
               AND EXISTS (SELECT 1 FROM music_listings l \
                           WHERE l.entry_id = music_items.entry_id AND l.version = ?)",
        )
        .bind(stamp(now))
        .bind(error.entry)
        .bind(&error.item)
        .bind(stamp(now - hours(24)))
        .bind(device_id)
        .bind(&error.version)
        .execute(db)
        .await?
        .rows_affected();
    }
    Ok(flagged)
}

// ------------------------------------------------------------------------------------------------
// One check
// ------------------------------------------------------------------------------------------------

/// What a check did (logged, and the tests read it).
#[derive(Debug, Default, Clone)]
pub struct CheckReport {
    pub entry_id: i64,
    pub name: String,
    pub requests: u32,
    pub new_items: usize,
    /// The listing version moved: `phones` (the entry's) get a nudge.
    pub changed: bool,
    pub phones: Vec<i64>,
    pub error: Option<String>,
    /// The breaker deferred it: no failure counted.
    pub deferred: bool,
}

/// One request's outcome inside a check.
enum Got {
    Answer(SourceResponse),
    Failed(FetchError),
    /// Not sent: the check's budget is spent, or the host's breaker is open.
    Skipped,
}

/// Why the listing part stopped.
enum Stop {
    Failed(CheckError),
    Deferred,
}

impl From<CheckError> for Stop {
    fn from(err: CheckError) -> Stop {
        Stop::Failed(err)
    }
}

/// The requests of one check: the source, the budget and the breaker.
struct Job<'a> {
    source: &'a dyn Source,
    breaker: &'a mut Breaker,
    requests: u32,
    budget: u32,
    started: tokio::time::Instant,
    now: DateTime<Utc>,
    /// Host -> resolves only to private addresses (a public feed's media hosts, §2.7).
    private_hosts: HashMap<String, bool>,
}

impl Job<'_> {
    fn spent(&self) -> bool {
        self.requests >= self.budget || self.started.elapsed() >= MAX_CHECK_TIME
    }

    async fn get(&mut self, request: SourceRequest) -> Got {
        let host = music_net::host_of(&request.url).unwrap_or_default();
        if self.spent() || self.breaker.broken(&host) {
            return Got::Skipped;
        }
        self.requests += 1;
        // A listing request's 5xx says the host is down; a manifest's says that one programme is
        // broken (QA #2), so it only counts against the item.
        let listing = matches!(request.kind, Kind::Page | Kind::Feed);
        match self.source.get(request).await {
            Ok(answer) => {
                if answer.status >= 500 && listing {
                    self.breaker.fail(&host);
                }
                Got::Answer(answer)
            }
            Err(err) => {
                if !matches!(err, FetchError::Refused(_)) {
                    self.breaker.fail(&host);
                }
                Got::Failed(err)
            }
        }
    }

    /// A page, root or feed answer that must be 200 (404/410 = not found for psapi).
    async fn page(
        &mut self,
        url: String,
        kind: Kind,
        reach: Reach,
    ) -> Result<SourceResponse, Stop> {
        match self.get(SourceRequest::new(url, kind, reach)).await {
            Got::Answer(answer) if answer.status == 200 => Ok(answer),
            Got::Answer(answer) => Err(Stop::Failed(CheckError::Status(answer.status))),
            Got::Failed(err) => Err(Stop::Failed(err.into())),
            Got::Skipped => Err(Stop::Deferred),
        }
    }

    /// Whether a URL from a public feed may reach the phones: not a private IP literal, and not a
    /// host that resolves only to private addresses (looked up once per host per check).
    async fn public_media(&mut self, url: &str) -> bool {
        let Some(host) = music_net::host_of(url) else {
            return false;
        };
        if let Some(ip) = music_net::literal_ip(&host) {
            return !music_net::is_private(ip);
        }
        if let Some(private) = self.private_hosts.get(&host) {
            return !private;
        }
        let private = match self.source.resolve(&host).await {
            Ok(found) => !found.is_empty() && found.iter().all(|ip| music_net::is_private(*ip)),
            // Unknown here: the phone resolves it itself.
            Err(_) => false,
        };
        self.private_hosts.insert(host, private);
        !private
    }
}

/// The working state of a check: the list and the listing fields it will write.
struct Plan {
    items: Vec<Item>,
    title: Option<String>,
    lan: Option<bool>,
    fallback: bool,
    capped: bool,
    keep_end: Option<String>,
    /// The URL the stored cover was fetched from (set only when a fetch succeeds).
    cover_url: Option<String>,
    /// The source's image as read in this check (the root or the feed), if it was read.
    cover_candidate: Option<String>,
    has_cover: bool,
    cover_at: Option<String>,
    root_at: Option<String>,
    etag: Option<String>,
    last_modified: Option<String>,
    body_sha256: Option<String>,
    parser: Option<i64>,
    full_recheck_at: Option<String>,
    new_items: usize,
    /// Recheck manifests still allowed today, server-wide.
    recheck_room: i64,
}

impl Plan {
    fn keys(&self) -> HashSet<String> {
        self.items.iter().map(|i| i.key.clone()).collect()
    }

    fn position(&self, key: &str) -> Option<usize> {
        self.items.iter().position(|i| i.key == key)
    }

    /// Drops the oldest `gone` items, then the oldest, until at most `cap` are left (a list that
    /// keeps the newest). Returns whether anything was dropped.
    fn cap_newest(&mut self, cap: usize) -> bool {
        let mut dropped = false;
        while self.items.len() > cap {
            let index = self
                .items
                .iter()
                .position(|i| i.state == "gone")
                .unwrap_or(0);
            self.items.remove(index);
            dropped = true;
        }
        dropped
    }
}

/// Checks one entry now (§2.3): the start stamp, the listing (NRK or RSS), the rechecks, the cover
/// and one closing transaction. `Ok(None)` = the entry is gone (or isn't NRK/RSS).
pub async fn check_entry(
    state: &AppState,
    entry_id: i64,
    now: DateTime<Utc>,
    breaker: &mut Breaker,
) -> Result<Option<CheckReport>, sqlx::Error> {
    let entry: Option<(String, String, Option<String>, String)> =
        sqlx::query_as("SELECT name, source, target, play_order FROM music_entries WHERE id = ?")
            .bind(entry_id)
            .fetch_optional(&state.db)
            .await?;
    let Some((name, source_kind, Some(target), play_order)) = entry else {
        return Ok(None);
    };
    if !matches!(source_kind.as_str(), "nrk" | "rss") {
        return Ok(None);
    }
    let Ok(link) = crate::music::parse_link(&target) else {
        return Ok(None);
    };
    let recheck_only = schedule(&state.db, now)
        .await?
        .into_iter()
        .find(|s| s.entry_id == entry_id)
        .is_some_and(|s| s.recheck_only);
    let old = load_listing(&state.db, entry_id).await?.unwrap_or_default();

    // The start stamp, in its own write: a crash from here on backs off like a failure.
    let started = sqlx::query(
        "INSERT INTO music_listings (entry_id, checked_at, failures) VALUES (?, ?, 1) \
         ON CONFLICT(entry_id) DO UPDATE SET checked_at = excluded.checked_at, \
           failures = failures + 1",
    )
    .bind(entry_id)
    .bind(stamp(now))
    .execute(&state.db)
    .await;
    match started {
        Ok(_) => {}
        // Deleted since it was read.
        Err(sqlx::Error::Database(err)) if err.is_foreign_key_violation() => return Ok(None),
        Err(err) => return Err(err),
    }
    state.music_sweep.set_current(Some(entry_id));
    let outcome = run_check(
        state,
        entry_id,
        &link,
        &play_order,
        &old,
        recheck_only,
        now,
        breaker,
    )
    .await;
    state.music_sweep.set_current(None);
    let (plan, result, requests) = outcome?;
    let mut report = finish(
        state,
        entry_id,
        now,
        &old,
        plan,
        result,
        recheck_only,
        requests,
    )
    .await?;
    if let Some(report) = report.as_mut() {
        report.name = name;
    }
    Ok(report)
}

/// The work of a check, without writing anything.
#[allow(clippy::too_many_arguments)]
async fn run_check(
    state: &AppState,
    entry_id: i64,
    link: &Link,
    play_order: &str,
    old: &Listing,
    recheck_only: bool,
    now: DateTime<Utc>,
    breaker: &mut Breaker,
) -> Result<(Plan, Result<CoverResult, Stop>, u32), sqlx::Error> {
    let items = {
        let mut conn = state.db.acquire().await?;
        load_items(&mut conn, entry_id).await?
    };
    let recheck_room = (RECHECKS_PER_DAY - rechecks_today(&state.db, now).await?).max(0);
    let mut plan = Plan {
        items,
        title: old.title.clone(),
        lan: old.lan,
        fallback: old.fallback,
        capped: old.capped,
        keep_end: old.keep_end.clone(),
        cover_url: old.cover_url.clone(),
        cover_candidate: None,
        has_cover: old.cover_hash.is_some(),
        cover_at: old.cover_at.clone(),
        root_at: old.root_at.clone(),
        etag: old.etag.clone(),
        last_modified: old.last_modified.clone(),
        body_sha256: old.body_sha256.clone(),
        parser: old.parser,
        full_recheck_at: old.full_recheck_at.clone(),
        new_items: 0,
        recheck_room,
    };
    let mut job = Job {
        source: state.music_fetch.as_ref(),
        breaker,
        requests: 0,
        budget: state.music_sweep.budget(),
        started: tokio::time::Instant::now(),
        now,
        private_hosts: HashMap::new(),
    };
    // A whole-entry re-resolve: two or more distinct items flagged within 24 h, once a day.
    let flagged_recently = plan
        .items
        .iter()
        .filter(|i| {
            i.recheck
                && i.reported_at
                    .as_deref()
                    .and_then(parse_stamp)
                    .is_some_and(|at| at > now - hours(24))
        })
        .count();
    let whole = flagged_recently >= 2
        && old
            .full_recheck_at
            .as_deref()
            .and_then(parse_stamp)
            .is_none_or(|at| at <= now - hours(24));
    let any_flag = plan.items.iter().any(|i| i.recheck);
    let result = match link {
        Link::Rss { url } => rss_check(&mut job, &mut plan, url, old, any_flag).await,
        Link::NrkPodcast { slug } => {
            nrk_check(
                &mut job,
                &mut plan,
                link,
                slug,
                "podcast",
                play_order,
                recheck_only,
                whole,
            )
            .await
        }
        Link::NrkSeries { slug, .. } => {
            nrk_check(
                &mut job,
                &mut plan,
                link,
                slug,
                "series",
                play_order,
                recheck_only,
                whole,
            )
            .await
        }
    };
    if whole {
        plan.full_recheck_at = Some(stamp(now));
    }
    // Flags are cleared after any attempt, so a failing source can't keep the entry due (QA #5).
    for item in plan.items.iter_mut().filter(|i| i.recheck) {
        item.recheck = false;
        item.rechecked_at = Some(stamp(now));
    }
    let result = match result {
        Ok(()) => Ok(cover(&mut job, &mut plan, link).await),
        Err(stop) => Err(stop),
    };
    let requests = job.requests;
    Ok((plan, result, requests))
}

// ------------------------------------------------------------------------------------------------
// NRK
// ------------------------------------------------------------------------------------------------

/// A psapi `_links.next` href as a URL on psapi (anything else is ignored).
fn psapi_url(href: &str) -> Option<String> {
    if let Some(path) = href.strip_prefix(PSAPI) {
        return path.starts_with('/').then(|| format!("{PSAPI}{path}"));
    }
    (href.starts_with("/radio/") || href.starts_with("/playback/"))
        .then(|| format!("{PSAPI}{href}"))
}

/// The result of walking psapi's episode pages.
struct Walk {
    /// In walk order, repeated keys dropped.
    stubs: Vec<sources::Stub>,
    /// The source has more beyond what was taken.
    more: bool,
    /// The first page listed no episode at all (podkast: use the RSS fallback).
    nothing: bool,
}

/// Walks `episodes?sort=<sort>` from page 1, taking stubs until `max`, a known key (when
/// `known` is given) or the end.
async fn walk(
    job: &mut Job<'_>,
    kind: &str,
    slug: &str,
    sort: &str,
    known: Option<&HashSet<String>>,
    max: usize,
) -> Result<Walk, Stop> {
    let mut url =
        format!("{PSAPI}/radio/catalog/{kind}/{slug}/episodes?page=1&pageSize=50&sort={sort}");
    let mut seen = HashSet::new();
    let mut result = Walk {
        stubs: Vec::new(),
        more: false,
        nothing: false,
    };
    let mut first = true;
    loop {
        let answer = match job
            .get(SourceRequest::new(url.clone(), Kind::Page, Reach::Public))
            .await
        {
            Got::Answer(answer) if answer.status == 200 => answer,
            Got::Answer(answer) if first && matches!(answer.status, 404 | 410) => {
                result.nothing = true;
                return Ok(result);
            }
            Got::Answer(answer) => return Err(Stop::Failed(CheckError::Status(answer.status))),
            Got::Failed(err) => return Err(Stop::Failed(err.into())),
            // The budget: what was walked counts; the next check continues.
            Got::Skipped if !first => {
                result.more = true;
                return Ok(result);
            }
            Got::Skipped => return Err(Stop::Deferred),
        };
        let Some(page) = sources::parse_page(&answer.body) else {
            return Err(Stop::Failed(if answer.truncated {
                CheckError::TooBig
            } else {
                CheckError::NotFound
            }));
        };
        if first && page.stubs.is_empty() {
            result.nothing = true;
            return Ok(result);
        }
        first = false;
        let count = page.stubs.len();
        for (index, stub) in page.stubs.into_iter().enumerate() {
            if !seen.insert(stub.key.clone()) {
                continue;
            }
            if known.is_some_and(|k| k.contains(&stub.key)) {
                return Ok(result);
            }
            if result.stubs.len() == max {
                result.more = true;
                return Ok(result);
            }
            result.stubs.push(stub);
            if result.stubs.len() == max && (index + 1 < count || page.next.is_some()) {
                result.more = true;
                return Ok(result);
            }
        }
        match page.next.as_deref().and_then(psapi_url) {
            Some(next) => url = next,
            None => return Ok(result),
        }
    }
}

/// psapi's playback manifest of an episode (`podcast`) or programme (`program`).
async fn manifest(job: &mut Job<'_>, kind: &str, key: &str) -> Option<Result<Manifest, ()>> {
    let url = format!("{PSAPI}/playback/manifest/{kind}/{key}");
    match job
        .get(SourceRequest::new(url, Kind::Manifest, Reach::Public))
        .await
    {
        Got::Answer(answer) if answer.status == 200 => {
            Some(sources::parse_manifest(&answer.body).ok_or(()))
        }
        Got::Answer(_) | Got::Failed(_) => Some(Err(())),
        Got::Skipped => None,
    }
}

/// Applies a manifest to an item: a playable URL -> `ok`.
fn apply_manifest(item: &mut Item, found: &Manifest) -> bool {
    match found {
        Manifest::Playable {
            url,
            hls,
            available_until,
            duration_ms,
        } if music_net::media_url_ok(url)
            && !music_net::host_of(url)
                .and_then(|h| music_net::literal_ip(&h))
                .is_some_and(music_net::is_private) =>
        {
            item.state = "ok".to_string();
            item.url = Some(url.clone());
            item.hls = *hls;
            item.available_until = available_until.map(stamp);
            if item.duration_ms.is_none() {
                item.duration_ms = *duration_ms;
            }
            item.attempts = 0;
            true
        }
        _ => false,
    }
}

#[allow(clippy::too_many_arguments)]
async fn nrk_check(
    job: &mut Job<'_>,
    plan: &mut Plan,
    link: &Link,
    slug: &str,
    kind: &str,
    play_order: &str,
    recheck_only: bool,
    whole: bool,
) -> Result<(), Stop> {
    let manifest_kind = if kind == "podcast" {
        "podcast"
    } else {
        "program"
    };
    let now = job.now;
    plan.lan = Some(false);
    if !recheck_only {
        // The root: on the first fill and once a week (title and image).
        let root_due = plan.items.is_empty()
            || plan.title.is_none()
            || plan
                .root_at
                .as_deref()
                .and_then(parse_stamp)
                .is_none_or(|at| at + TimeDelta::days(7) <= now);
        if root_due {
            let root_url = format!("{PSAPI}/radio/catalog/{kind}/{slug}");
            let answer = match job.page(root_url, Kind::Page, Reach::Public).await {
                Ok(answer) => answer,
                Err(Stop::Failed(CheckError::Status(404 | 410))) => {
                    return Err(Stop::Failed(CheckError::NotFound));
                }
                Err(stop) => return Err(stop),
            };
            let root = sources::parse_root(&answer.body).ok_or(CheckError::NotFound)?;
            plan.title = root.title.or(plan.title.take());
            plan.cover_candidate = root.image;
            plan.root_at = Some(stamp(now));
        }
        match link {
            Link::NrkSeries {
                program: Some(program),
                ..
            } => program_list(job, plan, program).await?,
            Link::NrkSeries { program: None, .. } => {
                series_list(job, plan, link, slug, play_order).await?
            }
            _ => podcast_list(job, plan, slug).await?,
        }
    }
    resolve(job, plan, manifest_kind, whole, !recheck_only).await;
    Ok(())
}

/// NRK podkast: the newest 100, walked `sort=desc` to the first known key; the RSS fallback when
/// psapi lists nothing.
async fn podcast_list(job: &mut Job<'_>, plan: &mut Plan, slug: &str) -> Result<(), Stop> {
    let known = plan.keys();
    let walked = walk(job, "podcast", slug, "desc", Some(&known), MAX_NRK_ITEMS).await?;
    if walked.nothing {
        let url = format!("{RSS_FALLBACK}/{slug}.rss");
        let answer = job.page(url, Kind::Feed, Reach::Public).await?;
        let feed = match sources::parse_feed(&answer.body, true) {
            Ok(feed) => feed,
            Err(_) if answer.truncated => return Err(Stop::Failed(CheckError::TooBig)),
            Err(sources::FeedError::NotFeed) => return Err(Stop::Failed(CheckError::NotFeed)),
            Err(sources::FeedError::NoItems) => return Err(Stop::Failed(CheckError::NoItems)),
        };
        plan.title = plan.title.take().or(feed.title.clone());
        if plan.cover_candidate.is_none() {
            plan.cover_candidate = feed.image.clone();
        }
        merge_feed(job, plan, feed, answer.truncated, MAX_NRK_ITEMS, false).await;
        plan.fallback = true;
        plan.keep_end = Some("newest".to_string());
        return Ok(());
    }
    plan.fallback = false;
    let now = job.now;
    let new: Vec<Item> = walked
        .stubs
        .iter()
        .rev()
        .map(|s| Item::from_stub(s, now))
        .collect();
    plan.new_items += new.len();
    plan.items.extend(new);
    let dropped = plan.cap_newest(MAX_NRK_ITEMS);
    plan.capped = plan.capped || walked.more || dropped;
    plan.keep_end = Some("newest".to_string());
    Ok(())
}

/// NRK serie: the window follows the play order (the user's answer to open question 1).
async fn series_list(
    job: &mut Job<'_>,
    plan: &mut Plan,
    link: &Link,
    slug: &str,
    play_order: &str,
) -> Result<(), Stop> {
    let now = job.now;
    let desired = desired_end(link, play_order, plan.capped);
    let to_items = |stubs: &[sources::Stub], reverse: bool, plan: &Plan| -> Vec<Item> {
        let ordered: Vec<&sources::Stub> = if reverse {
            stubs.iter().rev().collect()
        } else {
            stubs.iter().collect()
        };
        ordered
            .into_iter()
            .map(|s| match plan.position(&s.key) {
                Some(at) => plan.items[at].clone(),
                None => Item::from_stub(s, now),
            })
            .collect()
    };
    if plan.items.is_empty() {
        // The first fill.
        let (sort, end) = match play_order {
            "oldest_first" => ("asc", "first"),
            _ => ("desc", "newest"),
        };
        let walked = walk(job, "series", slug, sort, None, MAX_NRK_ITEMS).await?;
        plan.items = to_items(&walked.stubs, sort == "desc", plan);
        plan.new_items += plan.items.len();
        plan.capped = walked.more;
        // auto: the newest 100 of a long series, else all of it (kept from the start).
        plan.keep_end = Some(if play_order == "auto" && !walked.more {
            "first".to_string()
        } else {
            end.to_string()
        });
        return Ok(());
    }
    if plan.keep_end.as_deref() != Some(desired) {
        if plan.capped {
            // A refill from the other end.
            let sort = if desired == "newest" { "desc" } else { "asc" };
            let walked = walk(job, "series", slug, sort, None, MAX_NRK_ITEMS).await?;
            let refilled = to_items(&walked.stubs, sort == "desc", plan);
            let known = plan.keys();
            plan.new_items += refilled.iter().filter(|i| !known.contains(&i.key)).count();
            plan.items = refilled;
            plan.capped = walked.more;
            plan.keep_end = Some(desired.to_string());
            return Ok(());
        }
        // The whole series is listed either way: only the kept end changes.
        plan.keep_end = Some(desired.to_string());
    }
    if plan.keep_end.as_deref() == Some("first") && plan.capped {
        // Anchored at the start and full: nothing can be added.
        return Ok(());
    }
    let known = plan.keys();
    let walked = walk(job, "series", slug, "desc", Some(&known), MAX_NRK_ITEMS).await?;
    let new: Vec<Item> = walked
        .stubs
        .iter()
        .rev()
        .map(|s| Item::from_stub(s, now))
        .collect();
    let gap = walked.more;
    if plan.keep_end.as_deref() == Some("newest") {
        plan.new_items += new.len();
        plan.items.extend(new);
        let dropped = plan.cap_newest(MAX_NRK_ITEMS);
        plan.capped = plan.capped || dropped || gap;
        return Ok(());
    }
    let room = MAX_NRK_ITEMS.saturating_sub(plan.items.len());
    if new.len() <= room && !gap {
        plan.new_items += new.len();
        plan.items.extend(new);
    } else if play_order == "auto" {
        // Past 100 episodes: an auto series keeps the newest from now on.
        plan.new_items += new.len();
        plan.items.extend(new);
        plan.cap_newest(MAX_NRK_ITEMS);
        plan.capped = true;
        plan.keep_end = Some("newest".to_string());
    } else {
        // Oldest first: the list stays anchored at its start and is now full.
        let take = new.len().min(room);
        plan.new_items += take;
        plan.items.extend(new.into_iter().take(take));
        plan.capped = true;
    }
    Ok(())
}

/// NRK `serie/<slug>/<programId>`: the programmes from that one on, along the metadata's
/// `_links.next` (vibb `_series`), at most 100; anchored at the start.
async fn program_list(job: &mut Job<'_>, plan: &mut Plan, program: &str) -> Result<(), Stop> {
    plan.keep_end = Some("first".to_string());
    let known = plan.keys();
    let mut next = if plan.items.is_empty() {
        Some(program.to_string())
    } else if plan.items.len() >= MAX_NRK_ITEMS {
        return Ok(());
    } else {
        // Continue from the last programme: one request when nothing is new.
        let last = plan.items.last().map(|i| i.key.clone()).unwrap_or_default();
        let url = format!("{PSAPI}/playback/metadata/program/{last}");
        let answer = job.page(url, Kind::Manifest, Reach::Public).await?;
        sources::parse_metadata(&answer.body)
            .ok_or(CheckError::NotFound)?
            .next
            .filter(|n| !known.contains(n))
    };
    let first_fill = plan.items.is_empty();
    let mut added = 0;
    while let Some(id) = next.take() {
        if plan.items.len() >= MAX_NRK_ITEMS {
            plan.capped = true;
            break;
        }
        if !sources::valid_key(&id) || plan.position(&id).is_some() {
            break;
        }
        let mut item = Item::new(id.clone(), job.now);
        match manifest(job, "program", &id).await {
            Some(Ok(found)) => {
                if !apply_manifest(&mut item, &found) {
                    item.attempts = 1;
                }
            }
            Some(Err(())) => item.attempts = 1,
            None => break,
        }
        let url = format!("{PSAPI}/playback/metadata/program/{id}");
        let meta = match job.page(url, Kind::Manifest, Reach::Public).await {
            Ok(answer) => sources::parse_metadata(&answer.body),
            Err(stop) if first_fill && added == 0 => return Err(stop),
            Err(_) => None,
        };
        let Some(meta) = meta else {
            if first_fill && added == 0 {
                return Err(Stop::Failed(CheckError::NotFound));
            }
            plan.items.push(item);
            added += 1;
            break;
        };
        item.title = meta.title;
        if item.duration_ms.is_none() {
            item.duration_ms = meta.duration_ms;
        }
        plan.items.push(item);
        added += 1;
        next = meta.next;
    }
    plan.new_items += added;
    Ok(())
}

/// Manifests (§2.3, §2.5): expired items first, then flagged ones (or every item for a whole-entry
/// re-resolve), then new pending ones, then at most 10 earlier failures; pending for 14 days ->
/// `gone`.
async fn resolve(job: &mut Job<'_>, plan: &mut Plan, kind: &str, whole: bool, pending: bool) {
    let now = job.now;
    let day_ago = now - hours(24);
    let recently_rechecked = |item: &Item| {
        item.rechecked_at
            .as_deref()
            .and_then(parse_stamp)
            .is_some_and(|at| at > day_ago)
    };
    // 1. Expired: NRK sometimes extends the rights, so ask before calling it gone.
    // 2. Rechecks: a flagged item, or all of them once a day.
    let mut rechecks: Vec<usize> = Vec::new();
    for (index, item) in plan.items.iter().enumerate() {
        let expired = item.state == "ok"
            && item
                .available_until
                .as_deref()
                .and_then(parse_stamp)
                .is_some_and(|until| until <= now)
            && !recently_rechecked(item);
        if expired {
            rechecks.push(index);
        }
    }
    for (index, item) in plan.items.iter().enumerate() {
        if (whole && item.state != "pending" || item.recheck) && !rechecks.contains(&index) {
            rechecks.push(index);
        }
    }
    for index in rechecks {
        if plan.recheck_room <= 0 {
            break;
        }
        let key = plan.items[index].key.clone();
        let Some(found) = manifest(job, kind, &key).await else {
            break;
        };
        plan.recheck_room -= 1;
        let item = &mut plan.items[index];
        item.rechecked_at = Some(stamp(now));
        // A failed request: the phone keeps its URL until the source says otherwise.
        if let Ok(found) = found
            && !apply_manifest(item, &found)
        {
            item.state = "gone".to_string();
            item.url = None;
        }
    }
    if !pending {
        return;
    }
    // 3. New stubs, oldest first; 4. at most 10 earlier failures.
    let untried: Vec<usize> = (0..plan.items.len())
        .filter(|i| plan.items[*i].state == "pending" && plan.items[*i].attempts == 0)
        .collect();
    let retries: Vec<usize> = (0..plan.items.len())
        .filter(|i| plan.items[*i].state == "pending" && plan.items[*i].attempts > 0)
        .take(MAX_PENDING_RETRIES)
        .collect();
    for index in untried.into_iter().chain(retries) {
        let key = plan.items[index].key.clone();
        let Some(found) = manifest(job, kind, &key).await else {
            break;
        };
        let item = &mut plan.items[index];
        let resolved = matches!(&found, Ok(found) if apply_manifest(item, found));
        if !resolved {
            item.attempts += 1;
        }
    }
    give_up_pending(plan, now);
}

/// Pending for [PENDING_DAYS] (tried at least once): `gone`.
fn give_up_pending(plan: &mut Plan, now: DateTime<Utc>) {
    for item in plan.items.iter_mut() {
        let old = parse_stamp(&item.first_seen_at)
            .is_some_and(|seen| seen + TimeDelta::days(PENDING_DAYS) <= now);
        if item.state == "pending" && item.attempts > 0 && old {
            item.state = "gone".to_string();
            item.url = None;
        }
    }
}

// ------------------------------------------------------------------------------------------------
// RSS
// ------------------------------------------------------------------------------------------------

async fn rss_check(
    job: &mut Job<'_>,
    plan: &mut Plan,
    url: &str,
    old: &Listing,
    recheck: bool,
) -> Result<(), Stop> {
    // Where the target lives, fixed at the first check (§2.7).
    let lan = match plan.lan {
        Some(lan) => lan,
        None => {
            let host = music_net::host_of(url).ok_or(CheckError::NotFeed)?;
            let found = job
                .source
                .resolve(&host)
                .await
                .map_err(|e| Stop::Failed(e.into()))?;
            let lan = !found.is_empty() && found.iter().all(|ip| music_net::is_private(*ip));
            plan.lan = Some(lan);
            lan
        }
    };
    let reach = if lan { Reach::Any } else { Reach::Public };
    let current_parser = old.parser == Some(sources::PARSER_VERSION);
    let mut request = SourceRequest::new(url, Kind::Feed, reach);
    if !recheck && current_parser {
        request.etag = old.etag.clone();
        request.last_modified = old.last_modified.clone();
    }
    let answer = match job.get(request).await {
        Got::Answer(answer) => answer,
        Got::Failed(err) => return Err(Stop::Failed(err.into())),
        Got::Skipped => return Err(Stop::Deferred),
    };
    if answer.status == 304 {
        return Ok(());
    }
    if answer.status != 200 {
        return Err(Stop::Failed(CheckError::Status(answer.status)));
    }
    let body_sha256 = hex::encode(Sha256::digest(&answer.body));
    plan.etag = answer.etag.clone();
    plan.last_modified = answer.last_modified.clone();
    if !recheck && current_parser && old.body_sha256.as_deref() == Some(body_sha256.as_str()) {
        // A feed without validators that hasn't changed.
        return Ok(());
    }
    let feed = match sources::parse_feed(&answer.body, false) {
        Ok(feed) => feed,
        Err(_) if answer.truncated => return Err(Stop::Failed(CheckError::TooBig)),
        Err(sources::FeedError::NotFeed) => return Err(Stop::Failed(CheckError::NotFeed)),
        Err(sources::FeedError::NoItems) => return Err(Stop::Failed(CheckError::NoItems)),
    };
    plan.title = feed.title.clone().or(plan.title.take());
    let image = match feed.image.clone() {
        Some(image)
            if music_net::media_url_ok(&image) && (lan || job.public_media(&image).await) =>
        {
            Some(image)
        }
        _ => None,
    };
    plan.cover_candidate = image;
    merge_feed(job, plan, feed, answer.truncated, MAX_RSS_ITEMS, lan).await;
    plan.body_sha256 = Some(body_sha256);
    plan.parser = Some(sources::PARSER_VERSION);
    Ok(())
}

/// A URL from a feed the phones may get: http(s) without userinfo, at most 2000 characters, and
/// for a public feed not on a private host.
async fn feed_url(job: &mut Job<'_>, url: Option<&str>, lan: bool) -> Option<String> {
    let url = url?.trim();
    if !music_net::media_url_ok(url) {
        return None;
    }
    if !lan && !job.public_media(url).await {
        return None;
    }
    Some(url.to_string())
}

/// Merges a fresh feed into the list (§2.3, QA #9): items still in the feed are updated, new keys
/// are placed where the feed has them (new ones at the newest end), an item missing from a fresh,
/// complete feed becomes `gone` but stays, a returning one is `ok` again; then the newest `cap`
/// are kept, `gone` ones dropped first.
async fn merge_feed(
    job: &mut Job<'_>,
    plan: &mut Plan,
    feed: sources::Feed,
    truncated: bool,
    cap: usize,
    lan: bool,
) {
    let now = job.now;
    let mut fresh = feed.items;
    let more = fresh.len() > cap;
    if !feed.oldest_first {
        fresh.reverse();
    }
    if fresh.len() > cap {
        fresh.drain(..fresh.len() - cap);
    }
    let mut candidates: Vec<Item> = Vec::with_capacity(fresh.len());
    for entry in &fresh {
        let stripped = music_net::strip_tracking(&entry.enclosure);
        let url = feed_url(job, Some(&stripped), lan).await;
        let art = feed_url(job, entry.art.as_deref(), lan).await;
        let hls = url.as_deref().is_some_and(|u| {
            reqwest::Url::parse(u).is_ok_and(|parsed| parsed.path().ends_with(".m3u8"))
        });
        candidates.push(Item {
            state: if url.is_some() { "ok" } else { "gone" }.to_string(),
            title: entry.title.clone(),
            url,
            hls,
            duration_ms: entry.duration_ms,
            art_url: art,
            published_at: entry.published_at.map(stamp),
            ..Item::new(entry.key.clone(), now)
        });
    }
    let fresh_keys: HashSet<String> = candidates.iter().map(|c| c.key.clone()).collect();
    let known: HashMap<String, usize> = plan
        .items
        .iter()
        .enumerate()
        .map(|(index, item)| (item.key.clone(), index))
        .collect();
    // New items go before the next known one in feed order, or at the end.
    let mut before: HashMap<usize, Vec<Item>> = HashMap::new();
    let mut tail: Vec<Item> = Vec::new();
    let mut pending_new: Vec<Item> = Vec::new();
    for candidate in candidates {
        match known.get(&candidate.key).copied() {
            Some(index) => {
                before.entry(index).or_default().append(&mut pending_new);
                let item = &mut plan.items[index];
                item.title = candidate.title.or(item.title.take());
                item.state = candidate.state;
                item.url = candidate.url;
                item.hls = candidate.hls;
                item.duration_ms = candidate.duration_ms.or(item.duration_ms);
                item.art_url = candidate.art_url;
                item.published_at = candidate.published_at.or(item.published_at.take());
                item.attempts = 0;
            }
            None => pending_new.push(candidate),
        }
    }
    tail.append(&mut pending_new);
    let mut merged: Vec<Item> = Vec::with_capacity(plan.items.len() + tail.len());
    let mut new_count = tail.len();
    for (index, mut item) in std::mem::take(&mut plan.items).into_iter().enumerate() {
        if let Some(mut inserted) = before.remove(&index) {
            new_count += inserted.len();
            merged.append(&mut inserted);
        }
        if !fresh_keys.contains(&item.key) && !truncated {
            item.state = "gone".to_string();
            item.url = None;
        }
        merged.push(item);
    }
    merged.extend(tail);
    plan.items = merged;
    plan.new_items += new_count;
    let dropped = plan.cap_newest(cap);
    plan.capped = more || dropped;
}

// ------------------------------------------------------------------------------------------------
// Covers (§2.6)
// ------------------------------------------------------------------------------------------------

/// A cover fetched and processed, to be stored under the store's lock at the commit.
struct CoverResult(Option<(String, crate::photos::ProcessedPhoto)>);

/// The source's cover (§2.6): fetched when there is none or its URL changed, at most once a day;
/// a failure never fails the check (the old cover stays).
async fn cover(job: &mut Job<'_>, plan: &mut Plan, link: &Link) -> CoverResult {
    let now = job.now;
    let Some(url) = plan.cover_candidate.clone().or(plan.cover_url.clone()) else {
        return CoverResult(None);
    };
    let tried_recently = plan
        .cover_at
        .as_deref()
        .and_then(parse_stamp)
        .is_some_and(|at| at > now - hours(24));
    let changed = plan.cover_url.as_deref() != Some(url.as_str());
    if (plan.has_cover && !changed) || tried_recently {
        return CoverResult(None);
    }
    let lan = plan.lan == Some(true) && matches!(link, Link::Rss { .. });
    plan.cover_at = Some(stamp(now));
    if !music_net::media_url_ok(&url) || (!lan && !job.public_media(&url).await) {
        return CoverResult(None);
    }
    let reach = if lan { Reach::Any } else { Reach::Public };
    let answer = match job
        .get(SourceRequest::new(url.clone(), Kind::Image, reach))
        .await
    {
        Got::Answer(answer) if answer.status == 200 && !answer.truncated => answer,
        _ => return CoverResult(None),
    };
    match crate::photos::process_limited(answer.body.into(), crate::photos::Shape::Square).await {
        Ok(photo) => CoverResult(Some((url, photo))),
        Err(err) => {
            tracing::warn!(?err, url, "a music cover from the source couldn't be read");
            CoverResult(None)
        }
    }
}

// ------------------------------------------------------------------------------------------------
// The closing write
// ------------------------------------------------------------------------------------------------

/// Writes what a check found, in one transaction that first checks the entry still exists (a
/// delete mid-check drops the result). A failure keeps the last good list (§2.4).
#[allow(clippy::too_many_arguments)]
async fn finish(
    state: &AppState,
    entry_id: i64,
    now: DateTime<Utc>,
    old: &Listing,
    plan: Plan,
    result: Result<CoverResult, Stop>,
    recheck_only: bool,
    requests: u32,
) -> Result<Option<CheckReport>, sqlx::Error> {
    let mut report = CheckReport {
        entry_id,
        new_items: plan.new_items,
        requests,
        ..Default::default()
    };
    let (cover, failure) = match result {
        Ok(cover) => (cover.0, None),
        Err(Stop::Deferred) => {
            report.deferred = true;
            (None, None)
        }
        Err(Stop::Failed(err)) => (None, Some(err)),
    };
    // The store's lock from writing the file to committing its hash (photos' rule).
    let cover_lock = match &cover {
        Some(_) => Some(crate::photos::MUSIC_COVERS.lock().await),
        None => None,
    };
    let mut cover_hash: Option<String> = None;
    let mut cover_url = plan.cover_url.clone();
    if let Some((url, photo)) = &cover {
        match crate::photos::store(&state.music_cover_dir, photo).await {
            Ok(()) => {
                cover_hash = Some(photo.hash.clone());
                cover_url = Some(url.clone());
            }
            Err(err) => tracing::warn!(%err, "couldn't store a music cover"),
        }
    }

    let initial = {
        let mut conn = state.db.acquire().await?;
        load_items(&mut conn, entry_id).await?
    };
    let mut tx = state.db.begin_with("BEGIN IMMEDIATE").await?;
    let exists: bool =
        sqlx::query_scalar("SELECT EXISTS(SELECT 1 FROM music_entries WHERE id = ?)")
            .bind(entry_id)
            .fetch_one(&mut *tx)
            .await?;
    if !exists {
        tx.rollback().await?;
        return Ok(None);
    }
    let anchored = plan.keep_end.as_deref() == Some("first");
    let built = build_listing(entry_id, &plan.items, anchored);
    let has_ok = plan.items.iter().any(|i| i.state == "ok");
    let untried = plan
        .items
        .iter()
        .any(|i| i.state == "pending" && i.attempts == 0);
    // A list with no playable item is no list, unless a fill is still going (budget, breaker).
    let failure = match failure {
        None if !report.deferred && !recheck_only && !has_ok && !untried => {
            Some(CheckError::NoItems)
        }
        other => other,
    };
    let listing_part = !recheck_only && !report.deferred;
    let mut version = old.version.clone();
    let mut listed = (old.item_count, old.bytes, old.cut);
    let write_items = match &failure {
        // Never listed: keep the stubs and their attempts, so a retry continues (QA #2).
        Some(_) => old.version.is_none() && plan.items != initial,
        None => plan.items != initial,
    };
    match &failure {
        Some(err) => report.error = Some(err.code()),
        None if report.deferred => {}
        None => {
            if (has_ok || old.version.is_some())
                && let Some(built) = &built
            {
                version = Some(built.version.clone());
                listed = (built.count as i64, built.json.len() as i64, built.cut);
            }
        }
    }
    if write_items {
        sqlx::query("DELETE FROM music_items WHERE entry_id = ?")
            .bind(entry_id)
            .execute(&mut *tx)
            .await?;
        for (seq, item) in plan.items.iter().enumerate() {
            sqlx::query(&format!(
                "INSERT INTO music_items (entry_id, seq, {ITEM_COLUMNS}) \
                 VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
            ))
            .bind(entry_id)
            .bind(seq as i64)
            .bind(&item.key)
            .bind(&item.state)
            .bind(&item.title)
            .bind(&item.url)
            .bind(item.hls)
            .bind(item.duration_ms)
            .bind(&item.art_url)
            .bind(&item.published_at)
            .bind(&item.available_until)
            .bind(&item.first_seen_at)
            .bind(item.attempts)
            .bind(&item.reported_at)
            .bind(&item.rechecked_at)
            .bind(item.recheck)
            .execute(&mut *tx)
            .await?;
        }
    }
    let now_text = stamp(now);
    // `failures`: zeroed by a listing, restored for a recheck only or a deferral, kept (+1 from
    // the start stamp) after a failure.
    let failures = match (&failure, listing_part) {
        (Some(_), true) => old.failures + 1,
        (None, true) => 0,
        _ => old.failures,
    };
    let success = failure.is_none() && listing_part;
    let (error, error_at, failing_since) = match &failure {
        Some(err) if listing_part => (
            Some(err.code()),
            Some(now_text.clone()),
            Some(
                old.failing_since
                    .clone()
                    .unwrap_or_else(|| now_text.clone()),
            ),
        ),
        _ if success => (None, None, None),
        _ => (
            old.error.clone(),
            old.error_at.clone(),
            old.failing_since.clone(),
        ),
    };
    let keep_fields = failure.is_some();
    sqlx::query(
        "UPDATE music_listings SET title = ?, lan = ?, fallback = ?, capped = ?, cut = ?, \
           keep_end = ?, item_count = ?, bytes = ?, cover_url = ?, \
           cover_hash = COALESCE(?, cover_hash), cover_at = ?, root_at = ?, etag = ?, \
           last_modified = ?, body_sha256 = ?, parser = ?, version = ?, \
           listed_at = CASE WHEN ? THEN ? ELSE listed_at END, full_recheck_at = ?, error = ?, \
           error_at = ?, failing_since = ?, failures = ?, requests = ? \
         WHERE entry_id = ?",
    )
    .bind(if keep_fields {
        old.title.clone()
    } else {
        plan.title.clone()
    })
    .bind(plan.lan)
    .bind(if keep_fields {
        old.fallback
    } else {
        plan.fallback
    })
    .bind(if keep_fields { old.capped } else { plan.capped })
    .bind(listed.2)
    .bind(if keep_fields {
        old.keep_end.clone()
    } else {
        plan.keep_end.clone()
    })
    .bind(listed.0)
    .bind(listed.1)
    .bind(&cover_url)
    .bind(&cover_hash)
    .bind(&plan.cover_at)
    .bind(if keep_fields {
        old.root_at.clone()
    } else {
        plan.root_at.clone()
    })
    .bind(if keep_fields {
        old.etag.clone()
    } else {
        plan.etag.clone()
    })
    .bind(if keep_fields {
        old.last_modified.clone()
    } else {
        plan.last_modified.clone()
    })
    .bind(if keep_fields {
        old.body_sha256.clone()
    } else {
        plan.body_sha256.clone()
    })
    .bind(if keep_fields { old.parser } else { plan.parser })
    .bind(&version)
    .bind(success)
    .bind(&now_text)
    .bind(&plan.full_recheck_at)
    .bind(&error)
    .bind(&error_at)
    .bind(&failing_since)
    .bind(failures)
    .bind(report.requests as i64)
    .bind(entry_id)
    .execute(&mut *tx)
    .await?;
    tx.commit().await?;
    drop(cover_lock);
    if cover_hash.is_some() {
        crate::photos::MUSIC_COVERS
            .prune(&state.db, &state.music_cover_dir)
            .await;
    }
    report.changed = version != old.version
        || cover_hash.is_some()
        || (success && plan.keep_end != old.keep_end);
    if report.changed {
        report.phones = crate::music::phones_with_entry(&state.db, entry_id).await?;
    }
    Ok(Some(report))
}

// ------------------------------------------------------------------------------------------------
// The loop
// ------------------------------------------------------------------------------------------------

/// The sweeper task (spawned by `main`, never by `app()`): the first pass 60 s after the start,
/// then a pass whenever something is due or a `wake()` comes.
pub async fn run(state: AppState) {
    tokio::time::sleep(FIRST_PASS_DELAY).await;
    loop {
        let ok = run_pass(&state).await;
        let wait = next_wait(&state, Utc::now()).await.max(if ok {
            Duration::from_secs(1)
        } else {
            Duration::from_secs(60)
        });
        tokio::select! {
            _ = tokio::time::sleep(wait) => {}
            _ = state.music_sweep.notify.notified() => {}
        }
    }
}

/// How long to sleep: until the next entry is due (a paused host's entries at the pause's end), at
/// most the setting's interval.
async fn next_wait(state: &AppState, now: DateTime<Utc>) -> Duration {
    let setting = sweep_hours(&state.db).await.unwrap_or(DEFAULT_SWEEP_HOURS);
    let longest = hours(setting);
    let paused = state.music_sweep.paused_hosts(now);
    let next = match schedule(&state.db, now).await {
        Ok(all) => all
            .iter()
            .map(|s| match paused.get(&s.host) {
                Some(until) => s.at.max(*until),
                None => s.at,
            })
            .min(),
        Err(err) => {
            tracing::error!(%err, "music sweep: can't read the schedule");
            None
        }
    };
    let until = next.map_or(now + longest, |at| at.min(now + longest));
    (until - now).to_std().unwrap_or(Duration::ZERO)
}

/// One pass: check the most urgent due entry, re-read what is due, until nothing is; nudge the
/// phones whose library changed once at the end (or every 5 minutes of a long pass). `false` after
/// a database error.
pub async fn run_pass(state: &AppState) -> bool {
    let mut breaker = Breaker::default();
    let mut nudges: HashSet<i64> = HashSet::new();
    let mut last_flush = tokio::time::Instant::now();
    let mut last_check: Option<tokio::time::Instant> = None;
    let mut checks: HashMap<i64, u32> = HashMap::new();
    let mut ok = true;
    loop {
        let now = Utc::now();
        let paused = state.music_sweep.paused_hosts(now);
        let due = match due(&state.db, now).await {
            Ok(due) => due,
            Err(err) => {
                tracing::error!(%err, "music sweep: can't read what is due");
                ok = false;
                break;
            }
        };
        let Some(next) = due.into_iter().find(|s| {
            !breaker.broken(&s.host)
                && !paused.contains_key(&s.host)
                && checks.get(&s.entry_id).copied().unwrap_or(0) < 20
        }) else {
            break;
        };
        if let Some(at) = last_check {
            tokio::time::sleep_until(at + state.music_sweep.spacing()).await;
        }
        last_check = Some(tokio::time::Instant::now());
        *checks.entry(next.entry_id).or_default() += 1;
        match check_entry(state, next.entry_id, Utc::now(), &mut breaker).await {
            Ok(Some(report)) => {
                tracing::info!(
                    "music sweep: entry {} \"{}\": {} request{}, {} new{}{}",
                    report.entry_id,
                    report.name,
                    report.requests,
                    if report.requests == 1 { "" } else { "s" },
                    report.new_items,
                    report
                        .error
                        .as_deref()
                        .map(|e| format!(", failed: {e}"))
                        .unwrap_or_default(),
                    if report.deferred { ", deferred" } else { "" },
                );
                nudges.extend(report.phones);
            }
            Ok(None) => {}
            Err(err) => {
                tracing::error!(%err, entry_id = next.entry_id, "music sweep: a check failed");
                ok = false;
                break;
            }
        }
        if !nudges.is_empty() && last_flush.elapsed() >= NUDGE_FLUSH {
            nudge(state, &mut nudges);
            last_flush = tokio::time::Instant::now();
        }
    }
    let until = Utc::now() + TimeDelta::minutes(BREAKER_PAUSE_MINUTES);
    for host in breaker.broken_hosts() {
        tracing::warn!(
            host,
            "music sweep: {host} keeps failing - its entries wait 15 minutes"
        );
        state.music_sweep.pause(host, until);
    }
    nudge(state, &mut nudges);
    ok
}

fn nudge(state: &AppState, phones: &mut HashSet<i64>) {
    for phone in phones.drain() {
        let _ = state.command_notify.send(phone);
    }
}
