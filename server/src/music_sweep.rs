//! The music sweep (design `docs/design/21b-music-server-sweep.md` §1-§2, with its QA review,
//! decisions and the user's answer to open question 1): the server lists every NRK, RSS and
//! Storytel entry, sequentially and incrementally, so the phones never list a source. One sweep
//! serves every phone; a change nudges only the phones with the entry.
//!
//! - **Cadence** ([schedule], [due]): the family-wide setting `I` (1/3/6/12/24 h, default 6 = the
//!   Pi); RSS every `I`, NRK every `max(I, min(2I, 12 h))`, an entry no phone has once a day after
//!   its first fill, and a **quiet** NRK/RSS entry - no new item in [QUIET_DAYS] days ([quiet]) -
//!   once a day too, back on its interval as soon as a check finds a new item (2026-10-21). Check
//!   now, a new entry, a flagged item or an interrupted fill go first. A failure backs off 15 min ·
//!   4^(n-1), capped at the interval, daily after 7 days.
//! - **One check** ([check_entry]): a start stamp (`checked_at`, `failures` + 1, so a crash backs off
//!   like a failure), then the work, then one transaction that first checks the entry still exists.
//!   A failing root, listing page or feed keeps the last good list; a failing manifest only leaves
//!   its item pending. 250 requests or 10 minutes commit what is resolved.
//! - **The load rule**: every request goes through `AppState.music_fetch` (one in flight
//!   server-wide, 200 ms apart, `music_net::Gated`); checks are 4 s apart. During an outage each
//!   entry backs off on its own: its listing request fails the check, and 3 network errors in a
//!   row among its manifests do too. A routine NRK check asks for a 5-episode page first.
//! - **Rechecks** ([flag_reports]): a phone's 401/403/404/410 for an item of the current list flags
//!   it; the next check re-resolves it (one manifest, or an unconditional feed GET), at most once
//!   per item a day, a whole entry once a day, 300 manifests a day server-wide.
//! - **Covers**: NRK's square image or the RSS channel image, as a 512 px JPEG in
//!   `photos::MUSIC_COVERS`, at most once a day.
//! - **Episode art** ([art_pass], design `docs/design/27-music-episode-art.md` §1 with its QA): an
//!   item's own picture as a 512 px JPEG in `photos::MUSIC_ART`, judged against the series
//!   pictures by print; every item in play order (design 39 §2.4), with no picture budget (the
//!   user's decision of 2026-10-19: all pictures as soon as possible) - only the check's own
//!   [MAX_CHECK_REQUESTS]/[MAX_CHECK_TIME] and the 200 ms gap; an entry with untried pictures left
//!   after a check is due again at once (`art_more`, [Why::Art]). The listing's `art` is its hash.
//! - **The listing** ([build_listing]): `{v, entry, version, items}`, the `ok` and `gone` items
//!   oldest first, at most [MAX_LISTING_BYTES]; served by `GET /api/devices/music/entries/{id}/items`.
//! - **Storytel** (design `26b-storytel.md` §2.4): a series or standalone book is listed from the
//!   family's shelf (`storytel::Account::shelf`, read once per 5 minutes, fresh for Check now):
//!   one `ok` item per book in reading order, key = the consumable id, no URL (the phone mints it),
//!   the shelf's duration and the book's cover; a book that leaves the shelf leaves the list; not
//!   on the shelf fails the check (`not_on_shelf`) and keeps the last good list. Inside the
//!   refused-login cooldown the check fails `login_refused` without a request; a refused login or
//!   an outage fails the check with its code (the sweep's read never falls back to a kept shelf).
//!   Saving the login makes the entries that failed for want of one due at once.
//! - **Spotify** (design `26c-spotify.md` §3.3-§3.5, [spotify_check]): a playlist, album, track,
//!   show or episode is listed with the Web API like a feed (one `ok` item per track or episode,
//!   key = the Spotify id, no URL - the phone plays `spotify:<type>:<key>`), read with the first
//!   account that may (the listing's last reader first); a playlist's `snapshot_id` gates its item
//!   read (one request when unchanged - the items only when it moves, or for Check now), a show's
//!   newest episode and count do the same ([spotify::show_mark]). An artist, and a playlist no account may
//!   list, is **context mode**: no items, the phone plays the Spotify context (a context
//!   playlist's items are tried again at most daily). No app, no usable account or Spotify's quota pause fail the check without a
//!   request; every failure keeps the last good list.
//!
//! Ported in behaviour from palchrb/vibb `pi/vibb/content.py` (`_catalog`, `_new_episodes`,
//! `_psapi_episodes`, `_manifest_url`, `_series`, `sync_feed`; for episode art, branch `pipewire`'s
//! `_episode_image_file`, `_download_image`, `_sync_episode_images`), `pi/vibb/storytel.py`
//! (`sync` minus the downloads, `write_shelf`) and `pi/vibb/library.py` (`_cache_sweeper`,
//! `SYNC_INTERVAL_S`, `SYNC_STAGGER_S`, `SYNC_DELAY_S`; MIT, Copyright (c) palchrb), reimplemented
//! in Rust under GPL-3.0 with 21b's and 26b's changes (see NOTICE.md).

use std::collections::{HashMap, HashSet};
use std::time::Duration;

use chrono::{DateTime, NaiveDateTime, TimeDelta, Utc};
use serde::Serialize;
use sha2::{Digest, Sha256};

use crate::AppState;
use crate::music::{CheckError, Link, PSAPI};
use crate::music_net::{self, FetchError, Kind, Reach, Source, SourceRequest, SourceResponse};
use crate::music_sources::{self as sources, Manifest};
use crate::spotify::{self, LinkKind, NoReader, Read, SpotifyError};

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
/// Network errors in a row among a check's manifests that end the check as a failure (an outage
/// that began mid-check backs the entry off instead of timing out every manifest).
pub const MANIFEST_NETWORK_FAILURES: u32 = 3;
/// A routine NRK check's first page (21b's follow-up "cheaper change checks"); the walk goes on
/// with 50-episode pages only if all of these are new.
pub const ROUTINE_PAGE_SIZE: usize = 5;
/// A DNS lookup of a feed's media host (§2.7) waits at most this long.
pub const LOOKUP_TIMEOUT: Duration = Duration::from_secs(5);
/// "Checking…" on a card while a check started this recently hasn't ended (a crash's lasts no
/// longer).
pub const CHECKING_MINUTES: i64 = 10;
/// Checks of one entry in one pass, at most (a budget-limited fill continues; nothing else is due
/// again so soon).
const CHECKS_PER_PASS: u32 = 5;
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
// Episode art (design 27 §1.4): no per-check or per-day picture budget since 2026-10-19 (the
// user: "all pictures ASAP"; design 39's addendum). A check fetches an entry's pictures until they
// are done or the check's [MAX_CHECK_REQUESTS]/[MAX_CHECK_TIME] end it; then it is due again at
// once ([Why::Art]).
/// A failed picture is tried again a day later, at most this many times for one URL.
pub const ART_MAX_FAILS: i64 = 3;
/// This many fetched pictures in a row that are the series picture, with none of the entry's own,
/// turn the backlog off (only new items are tried) ...
pub const ART_BACKLOG_SAME: i64 = 5;
/// ... until this many days later (QA L1 e: a carousel the entry would have isn't hidden for good).
pub const ART_BACKLOG_RETRY_DAYS: i64 = 30;
/// Network errors in a row (or 5xx answers) from one image host that end its part of a check's art
/// pass (code QA M2, like [MANIFEST_NETWORK_FAILURES]): a dead host costs 3 requests, not the
/// check's 10 minutes. Counted per host since 0.54.0 (Q26): a dead host no longer holds the
/// pictures on the entry's other hosts.
pub const ART_NETWORK_FAILURES: u32 = 3;
/// Host troubles in one art pass, over all hosts, that end it while no host has answered in it
/// (code QA of round S1, L3): a DNS or uplink fault after a good listing doesn't ask every host
/// three times.
pub const ART_PASS_FAILURES: u32 = 2 * ART_NETWORK_FAILURES;
/// A judged picture whose URL moved to another path is fetched again at most this often (code QA
/// M1: a feed that renames its pictures at every fetch). A change in the query alone keeps the
/// result with no request.
pub const ART_MOVED_RETRY_DAYS: i64 = 7;
/// Earlier covers kept as reference prints (QA M2 c).
const OLD_PRINTS: usize = 3;

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
/// clock (a fake one in the tests).
pub struct Sweep {
    notify: tokio::sync::Notify,
    current: std::sync::Mutex<Option<i64>>,
    /// [MAX_CHECK_REQUESTS] and [ENTRY_SPACING] (smaller in the tests).
    request_budget: std::sync::atomic::AtomicU32,
    spacing_ms: std::sync::atomic::AtomicU64,
    /// The tests' cap on a check's image requests (0 in the tests that count other requests);
    /// `u32::MAX` = none (production: no picture budget).
    art_per_check: std::sync::atomic::AtomicU32,
    /// The tests' fake clock; `None` = the real one.
    clock: std::sync::Mutex<Option<DateTime<Utc>>>,
    /// The tests' stop right before a check commits (`pause_before_commit`).
    #[cfg(test)]
    before_commit: std::sync::Mutex<Option<CommitPause>>,
}

/// Says "reached", then waits for "go".
#[cfg(test)]
type CommitPause = (
    tokio::sync::oneshot::Sender<()>,
    tokio::sync::oneshot::Receiver<()>,
);

impl Default for Sweep {
    fn default() -> Self {
        Sweep {
            notify: tokio::sync::Notify::new(),
            current: Default::default(),
            request_budget: MAX_CHECK_REQUESTS.into(),
            spacing_ms: (ENTRY_SPACING.as_millis() as u64).into(),
            art_per_check: u32::MAX.into(),
            clock: Default::default(),
            #[cfg(test)]
            before_commit: Default::default(),
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

    /// The tests' knob: image requests per check (0 = no episode art fetched).
    #[cfg(test)]
    pub fn set_art_per_check(&self, requests: u32) {
        self.art_per_check
            .store(requests, std::sync::atomic::Ordering::SeqCst);
    }

    /// The image requests a check may make: `u32::MAX` (no cap), or the tests' override.
    pub(crate) fn art_per_check(&self) -> u32 {
        self.art_per_check.load(std::sync::atomic::Ordering::SeqCst)
    }

    /// The tests' knob: the next check stops right before its commit (the store locks held, the
    /// pictures written) and says so on the first channel; it goes on when the second is sent to.
    #[cfg(test)]
    pub fn pause_before_commit(
        &self,
    ) -> (
        tokio::sync::oneshot::Receiver<()>,
        tokio::sync::oneshot::Sender<()>,
    ) {
        let (reached, reached_rx) = tokio::sync::oneshot::channel();
        let (go, go_rx) = tokio::sync::oneshot::channel();
        *self.before_commit.lock().unwrap() = Some((reached, go_rx));
        (reached_rx, go)
    }

    #[cfg(test)]
    async fn at_commit(&self) {
        let pause = self.before_commit.lock().unwrap().take();
        if let Some((reached, go)) = pause {
            let _ = reached.send(());
            let _ = go.await;
        }
    }

    /// The tests' fake clock (`None` = the real one again).
    #[cfg(test)]
    pub fn set_clock(&self, now: Option<DateTime<Utc>>) {
        *self.clock.lock().unwrap_or_else(|e| e.into_inner()) = now;
    }

    /// Now, by the sweep's clock: every stamp the sweep and the phones' reports write.
    pub fn now(&self) -> DateTime<Utc> {
        self.clock
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .unwrap_or_else(Utc::now)
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
    /// Its last check left untried episode pictures (`art_more`, cut short by the check's own
    /// budget or a host's trouble after a picture came): due again at once, after anything else
    /// due (the user's "all pictures ASAP", 2026-10-19).
    Art,
}

/// One NRK/RSS/Storytel entry's place in the schedule.
#[derive(Debug, Clone)]
pub struct Scheduled {
    pub entry_id: i64,
    pub why: Why,
    /// When it is due (for `Why::Interval`; the others are due now).
    pub at: DateTime<Utc>,
    /// Only flagged NRK items to re-resolve: no listing part (§2.2's restore of `failures`).
    pub recheck_only: bool,
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
    error: Option<String>,
    error_at: Option<String>,
    /// When the Storytel login was last saved (NULL without one).
    storytel_saved_at: Option<String>,
    /// When the Spotify app was last saved in the PWA, and the newest Spotify login (design 26c
    /// step 2b): either can cure a Spotify entry's [SPOTIFY_LOGIN_ERRORS].
    spotify_app_saved_at: Option<String>,
    spotify_login_at: Option<String>,
    art_more: i64,
    /// When the entry last had a new item ([quiet]).
    newest_at: Option<String>,
}

/// The check errors a saved login can cure: a Storytel entry failing with one of them since before
/// the last save is due at once (QA L1 of 2b).
const LOGIN_ERRORS: [&str; 4] = ["storytel_off", "login_refused", "storytel_key", "no_jwt"];

/// The Spotify check errors a saved app or a new login can cure (design 26c step 2b, as Storytel's
/// [LOGIN_ERRORS]): a Spotify entry failing with one of them since before the last app save or the
/// newest login is due at once, not after its backoff.
const SPOTIFY_LOGIN_ERRORS: [&str; 5] = [
    "spotify_off",
    "no_account",
    "reauthorize",
    "key",
    "app_refused",
];

/// How often a listed entry is checked (§2.2's table; design 26c §3.4: by the link's kind): NRK
/// every `max(I, min(2I, 12 h))`; a Spotify album, track, episode or artist daily (vibb's daily
/// re-ask of what doesn't change); everything else - RSS, Storytel, a Spotify playlist or show -
/// the setting `I`. An entry no phone has: daily.
pub fn interval(link: &Link, ticked: bool, setting_hours: i64) -> TimeDelta {
    if !ticked {
        return hours(24);
    }
    match link {
        Link::NrkPodcast { .. } | Link::NrkSeries { .. } => {
            hours(setting_hours.max((2 * setting_hours).min(12)))
        }
        Link::Spotify {
            kind: LinkKind::Album | LinkKind::Track | LinkKind::Episode | LinkKind::Artist,
            ..
        } => hours(24),
        _ => hours(setting_hours),
    }
}

/// An NRK or RSS entry with no new item for this many days is checked daily ([quiet]).
pub const QUIET_DAYS: i64 = 30;

/// A quiet NRK/RSS entry (2026-10-21): its newest item ([ScheduleRow]'s `newest_at`: the newest
/// `published_at`, an item without one by its first sighting, and any item a later check found by
/// that check's time) is [QUIET_DAYS] old or more - checked once a day instead of at its
/// [interval]. A check that finds a new item makes `newest_at` that check's time, so the entry is
/// back on its interval at once. No items yet: not quiet. Storytel and Spotify keep their rules.
pub fn quiet(link: &Link, newest_at: Option<DateTime<Utc>>, now: DateTime<Utc>) -> bool {
    matches!(
        link,
        Link::NrkPodcast { .. } | Link::NrkSeries { .. } | Link::Rss { .. }
    ) && newest_at.is_some_and(|at| at + TimeDelta::days(QUIET_DAYS) <= now)
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

fn schedule_one(
    row: &ScheduleRow,
    setting_hours: i64,
    recheck_room: bool,
    now: DateTime<Utc>,
) -> Option<Scheduled> {
    let Some(link) = crate::music::entry_link(&row.source, row.target.as_deref()?) else {
        tracing::debug!(
            entry_id = row.entry_id,
            "music sweep: an entry's target doesn't parse - not scheduled"
        );
        return None;
    };
    let checked = row.checked_at.as_deref().and_then(parse_stamp);
    let requested = row.requested_at.as_deref().and_then(parse_stamp);
    let created = parse_stamp(&row.created_at).unwrap_or(now);
    let newest_at = row.newest_at.as_deref().and_then(parse_stamp);
    let every = if quiet(&link, newest_at, now) {
        interval(&link, row.ticked, setting_hours).max(hours(24))
    } else {
        interval(&link, row.ticked, setting_hours)
    };
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
    let make = |why: Why, at: DateTime<Utc>, recheck_only: bool, order: (i64, String, i64)| {
        Some(Scheduled {
            entry_id: row.entry_id,
            why,
            at,
            recheck_only,
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
    let (cures, cured_at) = match row.source.as_str() {
        "storytel" => (
            &LOGIN_ERRORS[..],
            row.storytel_saved_at.as_deref().and_then(parse_stamp),
        ),
        "spotify" => (
            &SPOTIFY_LOGIN_ERRORS[..],
            [&row.spotify_app_saved_at, &row.spotify_login_at]
                .into_iter()
                .filter_map(|at| at.as_deref().and_then(parse_stamp))
                .max(),
        ),
        _ => (&[][..], None),
    };
    let login_saved_since = row.failures > 0
        && row
            .error
            .as_deref()
            .is_some_and(|code| cures.contains(&code))
        && match (row.error_at.as_deref().and_then(parse_stamp), cured_at) {
            (Some(failed), Some(saved)) => failed < saved,
            _ => false,
        };
    if login_saved_since {
        return make(
            Why::Rework,
            now,
            false,
            (row.sort, String::new(), row.entry_id),
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
    // A flag waits for the backoff of a failing entry like everything else (QA 1c #1: a feed that
    // keeps failing must not be asked again at once).
    if row.flagged && recheck_room && row.failures == 0 {
        // Due for its own sake too: a whole check that also handles the flags.
        let recheck_only = regular > now && row.source == "nrk";
        return make(
            Why::Rework,
            now,
            recheck_only,
            (row.sort, String::new(), row.entry_id),
        );
    }
    // Untried pictures left by the last check (2026-10-19, "all pictures ASAP"): due again at once,
    // unless its regular time is due anyway.
    if row.failures == 0 && row.art_more > 0 && regular > now {
        return make(
            Why::Art,
            now,
            false,
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

/// Every NRK/RSS/Storytel/Spotify entry with its due time and reason - a pure function of the stamps, the
/// setting, the ticks and `now` (§2.2). Sorted: what is due first (by reason), then by due time.
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
             AND i.attempts = 0) AS untried, l.error, l.error_at, \
           (SELECT s.saved_at FROM music_storytel s WHERE s.id = 1 AND s.ciphertext IS NOT NULL) \
             AS storytel_saved_at, \
           (SELECT a.saved_at FROM music_spotify_app a WHERE a.id = 1) AS spotify_app_saved_at, \
           (SELECT MAX(a.authorized_at) FROM music_spotify_accounts a) AS spotify_login_at, \
           COALESCE(l.art_more, 0) AS art_more, \
           NULLIF(MAX( \
             COALESCE((SELECT MAX(COALESCE(i.published_at, i.first_seen_at)) FROM music_items i \
               WHERE i.entry_id = e.id), ''), \
             COALESCE((SELECT MAX(i.first_seen_at) FROM music_items i WHERE i.entry_id = e.id \
               AND i.first_seen_at > (SELECT MIN(j.first_seen_at) FROM music_items j \
                 WHERE j.entry_id = e.id)), '')), '') AS newest_at \
         FROM music_entries e LEFT JOIN music_listings l ON l.entry_id = e.id \
         WHERE e.source IN ('nrk', 'rss', 'storytel', 'spotify') AND e.target IS NOT NULL",
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

/// One `music_items` row: `pending` (not resolved yet), `ok` (listed and playable) or `gone`
/// (listed with `url: null`, withdrawn at the source). A Storytel book (design 26b §2.4, migration
/// 0072's note) is `ok` with `url` NULL: listed and playable, the phone mints its audio URL itself
/// (a signed URL expires, so it is never listed).
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
    /// Episode art (design 27 §1.2): the item's own picture (`own`), else none.
    pub art_hash: Option<String>,
    /// The `art_url` the art fields describe; another one is untried.
    pub art_from: Option<String>,
    /// `own`, `same` (the series picture) or `failed`.
    pub art_state: Option<String>,
    pub art_print: Option<i64>,
    /// The picture's coarse colour (`photos::tone_image`), for the twin merge (code QA L4).
    pub art_tone: Option<i64>,
    pub art_at: Option<String>,
    /// The last attempt that sent a request.
    pub art_fetched_at: Option<String>,
    pub art_fails: i64,
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
            art_hash: None,
            art_from: None,
            art_state: None,
            art_print: None,
            art_tone: None,
            art_at: None,
            art_fetched_at: None,
            art_fails: 0,
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
     available_until, first_seen_at, attempts, reported_at, rechecked_at, recheck, art_hash, \
     art_from, art_state, art_print, art_tone, art_at, art_fetched_at, art_fails";
/// How many columns [ITEM_COLUMNS] names.
const ITEM_COLUMN_COUNT: usize = 22;

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
    /// The last check's requests: for the tests (the log has the check's own count).
    #[cfg_attr(not(test), allow(dead_code))]
    pub requests: i64,
    /// Episode art (design 27 §1.2, QA M2/M3).
    pub cover_print: Option<i64>,
    pub cover_alt_url: Option<String>,
    pub cover_alt_print: Option<i64>,
    pub cover_alt_at: Option<String>,
    /// JSON array of up to [OLD_PRINTS] earlier covers' prints.
    pub old_prints: Option<String>,
    pub art_refs: Option<String>,
    pub art_backlog: bool,
    pub art_same_run: i64,
    pub art_backlog_at: Option<String>,
    pub art_refresh: bool,
    /// Untried episode pictures the last check left (migration 0081): due again at once.
    pub art_more: i64,
    /// Spotify (design 26c §3.3-§3.4, migration 0074): `list` or `context`.
    pub spotify_mode: Option<String>,
    /// A playlist's `snapshot_id` at the last item read; a show's gate mark (`total:newest id`,
    /// [spotify::show_mark]).
    pub spotify_snapshot: Option<String>,
    /// The last full item read (list mode); in context mode the last time a playlist's items
    /// were tried (2026-10-21: at most daily).
    pub spotify_items_at: Option<String>,
    /// The account that last read it (no FK: a removed account's id counts for nothing).
    pub spotify_reader: Option<i64>,
    /// Tracks or episodes left out (not playable here, local files, podcast episodes).
    pub spotify_skipped: i64,
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
    /// The item's own picture (`GET /api/devices/music/covers/{hash}`), else null: none, the
    /// series picture, not fetched yet, or failed (design 27 §1.5).
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
            // The 64-hex hash of the item's own picture (design 27 §1.5), else null.
            art: i.art_hash.clone(),
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

/// A Spotify listing in context mode (design 26c §3.3): no items - the phone plays the Spotify
/// context - and a version that moves with the mode, the title and the cover's source URL (not
/// its stored file, so a cover forgotten after a restore doesn't move what is served).
pub fn build_context_listing(
    entry_id: i64,
    title: Option<&str>,
    cover_url: Option<&str>,
) -> BuiltListing {
    let seed = serde_json::json!({
        "v": LISTING_FORMAT,
        "entry": entry_id,
        "spotify": "context",
        "title": title,
        "cover": cover_url,
    });
    let version = hex::encode(Sha256::digest(seed.to_string().as_bytes()))[..16].to_string();
    let json = serde_json::to_vec(&ListingDocument {
        v: LISTING_FORMAT,
        entry: entry_id,
        version: &version,
        items: &[],
    })
    .expect("a listing always serializes");
    BuiltListing {
        version,
        json,
        count: 0,
        cut: false,
    }
}

/// A listing from its stored parts (the check, the route and the restamp build it alike): a
/// Spotify entry in context mode as [build_context_listing], every other as [build_listing]
/// (anchored at the start when `keep_end` is `first`) - and a Spotify list that became empty (a
/// playlist the parent emptied) as an empty list, so the route serves what the library names.
pub fn listing_for(
    entry_id: i64,
    items: &[Item],
    keep_end: Option<&str>,
    spotify_mode: Option<&str>,
    title: Option<&str>,
    cover_url: Option<&str>,
) -> Option<BuiltListing> {
    match spotify_mode {
        Some("context") => Some(build_context_listing(entry_id, title, cover_url)),
        Some(_) => Some(
            build_listing(entry_id, items, keep_end == Some("first"))
                .unwrap_or_else(|| empty_listing(entry_id)),
        ),
        None => build_listing(entry_id, items, keep_end == Some("first")),
    }
}

/// A listing of no items (a Spotify list that became empty), its version [build_listing]'s rule.
fn empty_listing(entry_id: i64) -> BuiltListing {
    let body = serde_json::to_vec(&ListingBody {
        v: LISTING_FORMAT,
        entry: entry_id,
        items: &[],
    })
    .expect("a listing always serializes");
    let version = hex::encode(Sha256::digest(&body))[..16].to_string();
    let json = serde_json::to_vec(&ListingDocument {
        v: LISTING_FORMAT,
        entry: entry_id,
        version: &version,
        items: &[],
    })
    .expect("a listing always serializes");
    BuiltListing {
        version,
        json,
        count: 0,
        cut: false,
    }
}

/// A listing row's parts that [listing_for] needs.
type StoredParts = (
    Option<String>,
    Option<String>,
    Option<String>,
    Option<String>,
    Option<String>,
);

/// The listing a phone gets for `entry_id` (`GET /api/devices/music/entries/{id}/items`): only for
/// an entry it has ticked (a Storytel one only through the library's gate, design 26b §2.7; a
/// Spotify one only while the phone plays as an account, 26c §3.6), and only once listed. Built
/// from the rows in one read transaction, the same way the sweep built the version it stored.
pub async fn device_listing(
    db: &sqlx::SqlitePool,
    device_id: i64,
    entry_id: i64,
) -> Result<Option<BuiltListing>, sqlx::Error> {
    let mut tx = db.begin().await?;
    let found: Option<StoredParts> = sqlx::query_as(concat!(
        "SELECT l.version, l.keep_end, l.spotify_mode, l.title, l.cover_url \
         FROM music_listings l \
         JOIN music_entries e ON e.id = l.entry_id \
         JOIN device_music_entries d ON d.entry_id = l.entry_id \
         WHERE l.entry_id = ? AND d.device_id = ? \
           AND e.source IN ('nrk', 'rss', 'storytel', 'spotify') AND ",
        crate::storytel_gate!(),
        " AND ",
        crate::spotify_gate!()
    ))
    .bind(entry_id)
    .bind(device_id)
    .bind(device_id)
    .bind(device_id)
    .fetch_optional(&mut *tx)
    .await?;
    let Some((Some(_version), keep_end, mode, title, cover_url)) = found else {
        return Ok(None);
    };
    let items = load_items(&mut tx, entry_id).await?;
    tx.commit().await?;
    Ok(listing_for(
        entry_id,
        &items,
        keep_end.as_deref(),
        mode.as_deref(),
        title.as_deref(),
        cover_url.as_deref(),
    ))
}

/// Stores every listed entry's version again from its rows, where they changed outside a check:
/// `photos::MUSIC_ART.recover_missing` forgot pictures after a restore (design 27 code QA L6).
/// The library then names the listing the route serves (the version moves the revision, so the
/// phones fetch it). Returns how many changed.
pub async fn restamp_versions(db: &sqlx::SqlitePool) -> Result<usize, sqlx::Error> {
    let listed: Vec<i64> = sqlx::query_scalar(
        "SELECT l.entry_id FROM music_listings l JOIN music_entries e ON e.id = l.entry_id \
         WHERE l.version IS NOT NULL AND e.source IN ('nrk', 'rss', 'storytel', 'spotify')",
    )
    .fetch_all(db)
    .await?;
    let mut changed = 0;
    for entry_id in listed {
        let mut tx = db.begin_with("BEGIN IMMEDIATE").await?;
        let row: Option<StoredParts> = sqlx::query_as(
            "SELECT version, keep_end, spotify_mode, title, cover_url FROM music_listings \
             WHERE entry_id = ?",
        )
        .bind(entry_id)
        .fetch_optional(&mut *tx)
        .await?;
        let Some((Some(version), keep_end, mode, title, cover_url)) = row else {
            continue;
        };
        let items = load_items(&mut tx, entry_id).await?;
        if let Some(built) = listing_for(
            entry_id,
            &items,
            keep_end.as_deref(),
            mode.as_deref(),
            title.as_deref(),
            cover_url.as_deref(),
        ) && built.version != version
        {
            sqlx::query(
                "UPDATE music_listings SET version = ?, item_count = ?, bytes = ?, cut = ? \
                 WHERE entry_id = ?",
            )
            .bind(&built.version)
            .bind(built.count as i64)
            .bind(built.json.len() as i64)
            .bind(built.cut)
            .bind(entry_id)
            .execute(&mut *tx)
            .await?;
            changed += 1;
        }
        tx.commit().await?;
    }
    Ok(changed)
}

// ------------------------------------------------------------------------------------------------
// Phone reports (§2.5)
// ------------------------------------------------------------------------------------------------

/// Flags the items a phone reports failing, when the report counts (§2.5): an entry it has ticked,
/// a key of that entry, the current listing version, a 401/403/404/410, and no re-resolve of the
/// item in the last 24 h. Never a Storytel entry's (design 26b §2.4: nothing to re-resolve - the
/// phone mints its own URL, a mint failure is the phone's), nor a Spotify one's (design 26c §3.4:
/// a track the phone can't play is the phone's `unplayable`). Returns how many were flagged; the
/// caller wakes the sweeper.
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
               AND entry_id NOT IN \
                 (SELECT id FROM music_entries WHERE source IN ('storytel', 'spotify')) \
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
    /// Episode pictures judged in this check: the item's own, the series picture.
    pub art_own: usize,
    pub art_same: usize,
    /// Pictures left for the next check (due again at once).
    pub art_more: usize,
}

impl CheckReport {
    /// The log line's art part (design 27 §1.4): ", art 7 (3 own, 4 same), 205 left", or nothing.
    pub fn art_line(&self) -> String {
        let all = self.art_own + self.art_same;
        let left = if self.art_more > 0 {
            format!(", {} left", self.art_more)
        } else {
            String::new()
        };
        if all == 0 {
            return if left.is_empty() {
                left
            } else {
                format!(", art 0{left}")
            };
        }
        format!(
            ", art {all} ({} own, {} same){left}",
            self.art_own, self.art_same
        )
    }
}

/// One request's outcome inside a check.
enum Got {
    Answer(SourceResponse),
    Failed(FetchError),
    /// Not sent: the check's budget is spent.
    Skipped,
}

/// The requests of one check: the source and the budget.
struct Job<'a> {
    source: &'a dyn Source,
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
        if self.spent() {
            return Got::Skipped;
        }
        self.requests += 1;
        match self.source.get(request).await {
            Ok(answer) => Got::Answer(answer),
            Err(err) => Got::Failed(err),
        }
    }

    /// A root, page or feed answer that must be 200.
    async fn page(
        &mut self,
        url: String,
        kind: Kind,
        reach: Reach,
    ) -> Result<SourceResponse, CheckError> {
        match self.get(SourceRequest::new(url, kind, reach)).await {
            Got::Answer(answer) if answer.status == 200 => Ok(answer),
            Got::Answer(answer) => Err(CheckError::Status(answer.status)),
            Got::Failed(err) => Err(err.into()),
            // The check's 10 minutes are up.
            Got::Skipped => Err(CheckError::Timeout),
        }
    }

    /// Every address of `host`, within [LOOKUP_TIMEOUT] and the check's budget.
    async fn lookup(&mut self, host: &str) -> Result<Vec<std::net::IpAddr>, FetchError> {
        if self.spent() {
            return Err(FetchError::Timeout);
        }
        tokio::time::timeout(LOOKUP_TIMEOUT, self.source.resolve(host))
            .await
            .unwrap_or(Err(FetchError::Timeout))
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
        let private = match self.lookup(&host).await {
            Ok(found) => !found.is_empty() && found.iter().all(|ip| music_net::is_private(*ip)),
            // Unknown here (no answer in time): the phone resolves it itself.
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
    /// The items re-asked in this check (a manifest, or the feed of a flagged RSS entry): their
    /// flags are cleared and `rechecked_at` stamped, even when the check then fails (QA 1c #1).
    rechecked: HashSet<String>,
    /// Episode art (design 27): the reference prints and the backlog.
    art: ArtPlan,
    /// A Spotify listing's state (design 26c §3.4).
    spotify: SpotifyPlan,
}

/// A check's Spotify state (design 26c §3.3-§3.4, migration 0074's columns), from and to
/// `music_listings`; kept as it was by a failed check.
#[derive(Clone, Default)]
struct SpotifyPlan {
    mode: Option<String>,
    snapshot: Option<String>,
    items_at: Option<String>,
    reader: Option<i64>,
    skipped: i64,
}

impl SpotifyPlan {
    fn from_listing(old: &Listing) -> SpotifyPlan {
        SpotifyPlan {
            mode: old.spotify_mode.clone(),
            snapshot: old.spotify_snapshot.clone(),
            items_at: old.spotify_items_at.clone(),
            reader: old.spotify_reader,
            skipped: old.spotify_skipped,
        }
    }
}

/// A check's episode-art state (design 27 §1.2-§1.4, QA M2/M3), from and to `music_listings`.
#[derive(Default)]
struct ArtPlan {
    /// The cover's URL as of this check (a cover fetched now, else the stored one's).
    cover_url: Option<String>,
    cover_print: Option<i64>,
    alt_url: Option<String>,
    alt_print: Option<i64>,
    alt_at: Option<String>,
    /// NRK's 16:9 image as read from the root in this check (`None` = not read).
    alt_candidate: Option<Option<String>>,
    old_prints: Vec<i64>,
    refs_key: Option<String>,
    backlog: bool,
    same_run: i64,
    backlog_at: Option<String>,
    refresh: bool,
    /// New pictures to store at the commit, and hashes copied from another item (checked at the
    /// commit, under the store's lock).
    new_files: Vec<crate::photos::ProcessedPhoto>,
    copied: HashSet<String>,
    own: usize,
    same: usize,
    /// Candidates this check's pass didn't reach ([Why::Art]).
    more: usize,
}

impl ArtPlan {
    fn from_listing(old: &Listing) -> ArtPlan {
        ArtPlan {
            cover_print: old.cover_print,
            alt_url: old.cover_alt_url.clone(),
            alt_print: old.cover_alt_print,
            alt_at: old.cover_alt_at.clone(),
            alt_candidate: None,
            old_prints: old
                .old_prints
                .as_deref()
                .and_then(|j| serde_json::from_str(j).ok())
                .unwrap_or_default(),
            refs_key: old.art_refs.clone(),
            // A listing row that doesn't exist yet: the column defaults.
            backlog: old.art_backlog || old.checked_at.is_none(),
            same_run: old.art_same_run,
            backlog_at: old.art_backlog_at.clone(),
            refresh: old.art_refresh,
            ..Default::default()
        }
    }

    /// Every print that is "the series picture": the cover's, NRK's 16:9 image's and the earlier
    /// covers' (QA M2), without repeats, sorted.
    fn refs(&self) -> Vec<i64> {
        let mut refs: Vec<i64> = self
            .cover_print
            .into_iter()
            .chain(self.alt_print)
            .chain(self.old_prints.iter().copied())
            .collect();
        refs.sort_unstable();
        refs.dedup();
        refs
    }

    /// A new cover's print: the old one joins the earlier ones (at most [OLD_PRINTS]).
    fn new_cover(&mut self, print: i64) {
        if let Some(old) = self.cover_print
            && !crate::photos::same_picture(old, print)
        {
            self.old_prints.retain(|p| *p != old);
            self.old_prints.insert(0, old);
            self.old_prints.truncate(OLD_PRINTS);
        }
        self.cover_print = Some(print);
    }
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

/// How a check runs, from the schedule.
#[derive(Clone, Copy, Default)]
struct Mode {
    /// Only flagged NRK items to re-resolve: no listing part.
    recheck_only: bool,
    /// Check now: the full 50-episode first page, not the routine 5.
    requested: bool,
}

/// Checks one entry now (§2.3): the start stamp, the listing (NRK, RSS, Storytel or Spotify), the
/// rechecks, the cover and one closing transaction. `Ok(None)` = the entry is gone (or isn't
/// NRK/RSS/Storytel/Spotify).
pub async fn check_entry(
    state: &AppState,
    entry_id: i64,
    now: DateTime<Utc>,
) -> Result<Option<CheckReport>, sqlx::Error> {
    let entry: Option<(String, String, Option<String>, String)> =
        sqlx::query_as("SELECT name, source, target, play_order FROM music_entries WHERE id = ?")
            .bind(entry_id)
            .fetch_optional(&state.db)
            .await?;
    let Some((name, source_kind, Some(target), play_order)) = entry else {
        return Ok(None);
    };
    let Some(link) = crate::music::entry_link(&source_kind, &target) else {
        return Ok(None);
    };
    let mode = schedule(&state.db, now)
        .await?
        .into_iter()
        .find(|s| s.entry_id == entry_id)
        .map(|s| Mode {
            recheck_only: s.recheck_only,
            requested: s.why == Why::CheckNow,
        })
        .unwrap_or_default();
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
    let outcome = run_check(state, entry_id, &link, &play_order, &old, mode, now).await;
    state.music_sweep.set_current(None);
    let (plan, result, requests) = outcome?;
    let mut report = finish(
        state,
        entry_id,
        now,
        &old,
        plan,
        result,
        mode.recheck_only,
        requests,
    )
    .await?;
    if let Some(report) = report.as_mut() {
        report.name = name;
    }
    Ok(report)
}

/// The work of a check, without writing anything.
async fn run_check(
    state: &AppState,
    entry_id: i64,
    link: &Link,
    play_order: &str,
    old: &Listing,
    mode: Mode,
    now: DateTime<Utc>,
) -> Result<(Plan, Result<CoverResult, CheckError>, u32), sqlx::Error> {
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
        rechecked: HashSet::new(),
        art: ArtPlan::from_listing(old),
        spotify: SpotifyPlan::from_listing(old),
    };
    let mut job = Job {
        source: state.music_fetch.as_ref(),
        requests: 0,
        budget: state.music_sweep.budget(),
        started: tokio::time::Instant::now(),
        now,
        private_hosts: HashMap::new(),
    };
    // A whole-entry re-resolve (user decision 5): a flag now, two or more distinct items reported
    // within 24 h (handled or not - streams fail one at a time, QA 1c #4), once a day.
    let reported_recently = plan
        .items
        .iter()
        .filter(|i| {
            i.reported_at
                .as_deref()
                .and_then(parse_stamp)
                .is_some_and(|at| at > now - hours(24))
        })
        .count();
    let any_flag = plan.items.iter().any(|i| i.recheck);
    let whole = any_flag
        && reported_recently >= 2
        && old
            .full_recheck_at
            .as_deref()
            .and_then(parse_stamp)
            .is_none_or(|at| at <= now - hours(24));
    let result = match link {
        Link::Rss { url } => rss_check(&mut job, &mut plan, url, old, any_flag).await,
        Link::NrkPodcast { slug } => {
            nrk_check(
                &mut job, &mut plan, link, slug, "podcast", play_order, mode, whole,
            )
            .await
        }
        Link::NrkSeries { slug, .. } => {
            nrk_check(
                &mut job, &mut plan, link, slug, "series", play_order, mode, whole,
            )
            .await
        }
        Link::Storytel { .. } => {
            storytel_check(state, &mut job, &mut plan, &link.target(), mode).await
        }
        Link::Spotify { kind, id } => {
            spotify_check(state, &mut job, &mut plan, *kind, id, old, mode).await?
        }
    };
    if whole {
        plan.full_recheck_at = Some(stamp(now));
    }
    // A flag is cleared once its item was asked about (QA #5); one that waits for the day's
    // budget stays (QA 1c #7).
    for item in plan.items.iter_mut() {
        if item.recheck && plan.rechecked.contains(&item.key) {
            item.recheck = false;
            item.rechecked_at = Some(stamp(now));
        }
    }
    let result = match result {
        Ok(()) => {
            let found = cover(&mut job, &mut plan, link).await;
            if !mode.recheck_only {
                // Episode art (design 27 §1.4): after the listing, the manifests and the cover.
                reference_prints(state, &mut job, &mut plan, old, &found).await?;
                let gets = state.music_sweep.art_per_check();
                art_pass(&state.db, &mut job, &mut plan, link, play_order, gets).await?;
            }
            Ok(found)
        }
        Err(err) => Err(err),
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

fn episodes_url(kind: &str, slug: &str, sort: &str, size: usize) -> String {
    format!("{PSAPI}/radio/catalog/{kind}/{slug}/episodes?page=1&pageSize={size}&sort={sort}")
}

/// The result of walking psapi's episode pages.
struct Walk {
    /// In walk order, repeated keys dropped.
    stubs: Vec<sources::Stub>,
    /// The source has more beyond what was taken.
    more: bool,
    /// The first page listed no episode at all (or answered 404/410).
    nothing: bool,
}

/// How a walk starts.
#[derive(Clone, Copy)]
struct WalkStart<'k> {
    sort: &'static str,
    /// Stop at the first of these (an incremental walk).
    known: Option<&'k HashSet<String>>,
    max: usize,
    /// A routine check: a 5-episode page first, 50-episode pages only if all five are new.
    routine: bool,
}

/// Walks `episodes?sort=<sort>` from page 1, taking stubs until `max`, a known key or the end. A
/// routine walk sends the stored validators with its small page; a 304 means nothing new.
async fn walk(
    job: &mut Job<'_>,
    plan: &mut Plan,
    kind: &str,
    slug: &str,
    start: WalkStart<'_>,
) -> Result<Walk, CheckError> {
    let mut size = if start.routine { ROUTINE_PAGE_SIZE } else { 50 };
    let mut url = episodes_url(kind, slug, start.sort, size);
    let mut seen = HashSet::new();
    let mut result = Walk {
        stubs: Vec::new(),
        more: false,
        nothing: false,
    };
    let mut first = true;
    loop {
        let mut request = SourceRequest::new(url.clone(), Kind::Page, Reach::Public);
        let small = first && size == ROUTINE_PAGE_SIZE;
        if small {
            // psapi sent no validators when this was written (Cache-Control only); if it ever
            // does, the routine page costs a 304.
            request.etag = plan.etag.clone();
            request.last_modified = plan.last_modified.clone();
        }
        let answer = match job.get(request).await {
            Got::Answer(answer) if small && answer.status == 304 => return Ok(result),
            Got::Answer(answer) if answer.status == 200 => answer,
            Got::Answer(answer) if first && matches!(answer.status, 404 | 410) => {
                result.nothing = true;
                return Ok(result);
            }
            Got::Answer(answer) => return Err(CheckError::Status(answer.status)),
            Got::Failed(err) => return Err(err.into()),
            // The budget: what was walked counts; the next check continues.
            Got::Skipped if !first => {
                result.more = true;
                return Ok(result);
            }
            Got::Skipped => return Err(CheckError::Timeout),
        };
        if small {
            plan.etag = answer.etag.clone();
            plan.last_modified = answer.last_modified.clone();
        }
        let Some(page) = sources::parse_page(&answer.body) else {
            return Err(if answer.truncated {
                CheckError::TooBig
            } else {
                CheckError::NotFound
            });
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
            if start.known.is_some_and(|k| k.contains(&stub.key)) {
                return Ok(result);
            }
            if result.stubs.len() == start.max {
                result.more = true;
                return Ok(result);
            }
            result.stubs.push(stub);
            if result.stubs.len() == start.max && (index + 1 < count || page.next.is_some()) {
                result.more = true;
                return Ok(result);
            }
        }
        if size == ROUTINE_PAGE_SIZE {
            // All five were new: go on with the normal pages, from the start (psapi's own next
            // link would continue in fives; `seen` skips the five already taken).
            if page.next.is_none() && count < size {
                return Ok(result);
            }
            size = 50;
            url = episodes_url(kind, slug, start.sort, size);
            continue;
        }
        match page.next.as_deref().and_then(psapi_url) {
            Some(next) => url = next,
            None => return Ok(result),
        }
    }
}

/// What psapi said about one manifest.
enum Asked {
    Found(Manifest),
    /// 404/410: unpublished.
    Unpublished,
    /// Another status, or an answer that isn't a manifest.
    Failed,
    /// A network error or a timeout.
    Unreachable,
    /// Not asked: the check's budget is spent.
    Skipped,
}

/// psapi's playback manifest of an episode (`podcast`) or programme (`program`).
async fn manifest(job: &mut Job<'_>, kind: &str, key: &str) -> Asked {
    let url = format!("{PSAPI}/playback/manifest/{kind}/{key}");
    match job
        .get(SourceRequest::new(url, Kind::Manifest, Reach::Public))
        .await
    {
        Got::Answer(answer) if answer.status == 200 => {
            match sources::parse_manifest(&answer.body) {
                Some(found) => Asked::Found(found),
                None => Asked::Failed,
            }
        }
        Got::Answer(answer) if matches!(answer.status, 404 | 410) => Asked::Unpublished,
        Got::Answer(_) => Asked::Failed,
        Got::Failed(_) => Asked::Unreachable,
        Got::Skipped => Asked::Skipped,
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
    mode: Mode,
    whole: bool,
) -> Result<(), CheckError> {
    let manifest_kind = if kind == "podcast" {
        "podcast"
    } else {
        "program"
    };
    let now = job.now;
    plan.lan = Some(false);
    if !mode.recheck_only {
        // The root: on the first fill and once a week (title and image); and once for the
        // episode-art references (NRK's 16:9 picture, code QA L1) before the one-time refresh
        // judges the back catalogue.
        let root_due = plan.items.is_empty()
            || plan.title.is_none()
            || plan.art.refresh
            || plan.art.alt_at.is_none()
            || plan
                .root_at
                .as_deref()
                .and_then(parse_stamp)
                .is_none_or(|at| at + TimeDelta::days(7) <= now);
        if root_due {
            let root_url = format!("{PSAPI}/radio/catalog/{kind}/{slug}");
            let answer = match job.page(root_url, Kind::Page, Reach::Public).await {
                Ok(answer) => answer,
                Err(CheckError::Status(404 | 410)) => return Err(CheckError::NotFound),
                Err(err) => return Err(err),
            };
            let root = sources::parse_root(&answer.body).ok_or(CheckError::NotFound)?;
            plan.title = root.title.or(plan.title.take());
            plan.cover_candidate = root.image;
            plan.art.alt_candidate = Some(root.wide_image);
            plan.root_at = Some(stamp(now));
        }
        match link {
            Link::NrkSeries {
                program: Some(program),
                ..
            } => program_list(job, plan, program).await?,
            Link::NrkSeries { program: None, .. } => {
                series_list(job, plan, link, slug, play_order, mode).await?
            }
            _ => podcast_list(job, plan, slug, mode).await?,
        }
        if plan.art.refresh {
            refresh_art_urls(job, plan, link, slug, kind).await;
        }
    }
    resolve(job, plan, manifest_kind, whole, !mode.recheck_only).await
}

/// The one-time refresh of episode pictures (design 27 QA M3): episodes listed before took the
/// 300 px picture, and a routine walk stops at the first known episode, so this walks the pages
/// once more from the kept end and takes the 512 px picture of every episode it has. Nothing is
/// added or dropped; a failure leaves the flag for the next check.
async fn refresh_art_urls(job: &mut Job<'_>, plan: &mut Plan, link: &Link, slug: &str, kind: &str) {
    // A programme chain has no pictures; the RSS fallback's keys aren't psapi's.
    if matches!(
        link,
        Link::NrkSeries {
            program: Some(_),
            ..
        }
    ) || plan.fallback
    {
        plan.art.refresh = false;
        return;
    }
    let sort = if plan.keep_end.as_deref() == Some("first") {
        "asc"
    } else {
        "desc"
    };
    let start = WalkStart {
        sort,
        known: None,
        max: MAX_NRK_ITEMS,
        routine: false,
    };
    let Ok(walked) = walk(job, plan, kind, slug, start).await else {
        return;
    };
    for stub in walked.stubs {
        let Some(at) = plan.position(&stub.key) else {
            continue;
        };
        let art = stub.art.filter(|a| music_net::media_url_ok(a));
        let item = &mut plan.items[at];
        if art.is_some() && item.art_url != art {
            item.art_url = art;
            // Untried for the new URL; the picture it has stays until the new one is judged.
            item.art_from = None;
        }
    }
    // A walk the budget cut short goes on next time.
    if !job.spent() {
        plan.art.refresh = false;
    }
}

/// NRK podkast: the newest 100, walked `sort=desc` to the first known key. The RSS fallback only
/// for a first fill or a list that came from it: for a list psapi built, a psapi page with nothing
/// on it is a failed check, and the last good list stays (QA 1c #2).
async fn podcast_list(
    job: &mut Job<'_>,
    plan: &mut Plan,
    slug: &str,
    mode: Mode,
) -> Result<(), CheckError> {
    let known = plan.keys();
    let routine = !plan.items.is_empty() && !mode.requested;
    let start = WalkStart {
        sort: "desc",
        known: Some(&known),
        max: MAX_NRK_ITEMS,
        routine,
    };
    let walked = walk(job, plan, "podcast", slug, start).await?;
    if walked.nothing {
        if !plan.items.is_empty() && !plan.fallback {
            return Err(CheckError::NotFound);
        }
        let url = format!("{RSS_FALLBACK}/{slug}.rss");
        let answer = job.page(url, Kind::Feed, Reach::Public).await?;
        let feed = match sources::parse_feed(&answer.body, true, MAX_NRK_ITEMS) {
            Ok(feed) => feed,
            Err(_) if answer.truncated => return Err(CheckError::TooBig),
            Err(sources::FeedError::NotFeed) => return Err(CheckError::NotFeed),
            Err(sources::FeedError::NoItems) => return Err(CheckError::NoItems),
        };
        plan.title = plan.title.take().or(feed.title.clone());
        if plan.cover_candidate.is_none() {
            plan.cover_candidate = feed.image.clone();
        }
        // The fallback is a short rolling window: what it lacks isn't gone.
        merge_feed(job, plan, feed, false, MAX_NRK_ITEMS, false).await;
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
    mode: Mode,
) -> Result<(), CheckError> {
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
    let full = |sort: &'static str| WalkStart {
        sort,
        known: None,
        max: MAX_NRK_ITEMS,
        routine: false,
    };
    if plan.items.is_empty() {
        // The first fill.
        let (sort, end) = match play_order {
            "oldest_first" => ("asc", "first"),
            _ => ("desc", "newest"),
        };
        let walked = walk(job, plan, "series", slug, full(sort)).await?;
        if walked.nothing {
            return Err(CheckError::NotFound);
        }
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
            let walked = walk(job, plan, "series", slug, full(sort)).await?;
            if walked.nothing {
                return Err(CheckError::NotFound);
            }
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
    let start = WalkStart {
        sort: "desc",
        known: Some(&known),
        max: MAX_NRK_ITEMS,
        routine: !mode.requested,
    };
    let walked = walk(job, plan, "series", slug, start).await?;
    if walked.nothing {
        return Err(CheckError::NotFound);
    }
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
async fn program_list(job: &mut Job<'_>, plan: &mut Plan, program: &str) -> Result<(), CheckError> {
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
            Asked::Found(found) => {
                if !apply_manifest(&mut item, &found) {
                    item.attempts = 1;
                }
            }
            Asked::Skipped => break,
            _ => item.attempts = 1,
        }
        let url = format!("{PSAPI}/playback/metadata/program/{id}");
        let meta = match job.page(url, Kind::Manifest, Reach::Public).await {
            Ok(answer) => sources::parse_metadata(&answer.body),
            Err(err) if first_fill && added == 0 => return Err(err),
            Err(_) => None,
        };
        let Some(meta) = meta else {
            if first_fill && added == 0 {
                return Err(CheckError::NotFound);
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
/// `gone`. [MANIFEST_NETWORK_FAILURES] network errors in a row fail the check (psapi went away
/// mid-check: the entry backs off instead of timing out every manifest).
async fn resolve(
    job: &mut Job<'_>,
    plan: &mut Plan,
    kind: &str,
    whole: bool,
    pending: bool,
) -> Result<(), CheckError> {
    let now = job.now;
    let day_ago = now - hours(24);
    let mut unreachable = 0;
    let mut outage = |asked: &Asked| -> Result<(), CheckError> {
        if matches!(asked, Asked::Unreachable) {
            unreachable += 1;
            if unreachable >= MANIFEST_NETWORK_FAILURES {
                return Err(CheckError::Network("psapi stopped answering".to_string()));
            }
        } else {
            unreachable = 0;
        }
        Ok(())
    };
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
        let asked = manifest(job, kind, &key).await;
        if matches!(asked, Asked::Skipped) {
            break;
        }
        plan.recheck_room -= 1;
        plan.rechecked.insert(key);
        let item = &mut plan.items[index];
        item.rechecked_at = Some(stamp(now));
        let gone = match &asked {
            Asked::Found(found) => !apply_manifest(item, found),
            Asked::Unpublished => true,
            // No answer: the phone keeps its URL until the source says otherwise.
            _ => false,
        };
        if gone {
            item.state = "gone".to_string();
            item.url = None;
        }
        outage(&asked)?;
    }
    if !pending {
        return Ok(());
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
        let asked = manifest(job, kind, &key).await;
        if matches!(asked, Asked::Skipped) {
            break;
        }
        let item = &mut plan.items[index];
        let resolved = matches!(&asked, Asked::Found(found) if apply_manifest(item, found));
        if !resolved {
            item.attempts += 1;
        }
        outage(&asked)?;
    }
    give_up_pending(plan, now);
    Ok(())
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

/// Where a pasted or swept feed lives (§2.7): `true` when its host resolves only to LAN addresses
/// (the home LAN or the tailnet, `music_net::is_lan`: never `0.0.0.0`/`::`, a blocker's answer).
/// The add check and the sweep's checks judge it alike (security review L2: a LAN feed is judged
/// again at each check, `rss_check`).
pub async fn feed_is_lan(source: &dyn Source, url: &str) -> Result<bool, FetchError> {
    let host = music_net::host_of(url)
        .ok_or_else(|| FetchError::Refused("only http and https links work".to_string()))?;
    let found = tokio::time::timeout(LOOKUP_TIMEOUT, source.resolve(&host))
        .await
        .unwrap_or(Err(FetchError::Timeout))?;
    Ok(!found.is_empty() && found.iter().all(|ip| music_net::is_lan(*ip)))
}

/// Whether a picture at `url` is fetched with the LAN reach (security review L2): only for a feed
/// judged LAN at this check, and only from the feed's own host and port - every other picture
/// goes out as a public one (`Reach::Public`, and never to a private host).
fn lan_art(plan: &Plan, link: &Link, url: &str) -> bool {
    let Link::Rss { url: feed } = link else {
        return false;
    };
    let origin = |url: &str| {
        reqwest::Url::parse(url).ok().and_then(|url| {
            Some((
                url.host_str()?.to_ascii_lowercase(),
                url.port_or_known_default()?,
            ))
        })
    };
    plan.lan == Some(true) && origin(feed).is_some() && origin(feed) == origin(url)
}

async fn rss_check(
    job: &mut Job<'_>,
    plan: &mut Plan,
    url: &str,
    old: &Listing,
    recheck: bool,
) -> Result<(), CheckError> {
    // Where the target lives (§2.7). Public stays public for good (a host that later answers a
    // private address is refused, never turned LAN); LAN is judged again at every check and
    // becomes public for good the first time the host answers otherwise (security review L2: one
    // private answer - a rebinding, a blocker's - never makes a feed LAN for good).
    let lan = match plan.lan {
        Some(false) => false,
        _ => feed_is_lan(job.source, url).await?,
    };
    plan.lan = Some(lan);
    let reach = if lan { Reach::Any } else { Reach::Public };
    let current_parser = old.parser == Some(sources::PARSER_VERSION);
    let mut request = SourceRequest::new(url, Kind::Feed, reach);
    if !recheck && current_parser {
        request.etag = old.etag.clone();
        request.last_modified = old.last_modified.clone();
    }
    let got = job.get(request).await;
    if recheck && !matches!(got, Got::Skipped) {
        // The flagged items were asked about (their feed), whatever it answered.
        let flagged: Vec<String> = plan
            .items
            .iter()
            .filter(|i| i.recheck)
            .map(|i| i.key.clone())
            .collect();
        plan.rechecked.extend(flagged);
    }
    let answer = match got {
        Got::Answer(answer) => answer,
        Got::Failed(err) => return Err(err.into()),
        Got::Skipped => return Err(CheckError::Timeout),
    };
    if answer.status == 304 {
        return Ok(());
    }
    if answer.status != 200 {
        return Err(CheckError::Status(answer.status));
    }
    let body_sha256 = hex::encode(Sha256::digest(&answer.body));
    plan.etag = answer.etag.clone();
    plan.last_modified = answer.last_modified.clone();
    if !recheck && current_parser && old.body_sha256.as_deref() == Some(body_sha256.as_str()) {
        // A feed without validators that hasn't changed.
        return Ok(());
    }
    let feed = match sources::parse_feed(&answer.body, false, MAX_RSS_ITEMS) {
        Ok(feed) => feed,
        Err(_) if answer.truncated => return Err(CheckError::TooBig),
        Err(sources::FeedError::NotFeed) => return Err(CheckError::NotFeed),
        Err(sources::FeedError::NoItems) => return Err(CheckError::NoItems),
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
    // A cut-off feed doesn't say what left it.
    let complete = !answer.truncated;
    merge_feed(job, plan, feed, complete, MAX_RSS_ITEMS, lan).await;
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
/// are placed where the feed has them (new ones at the newest end), an item missing from a
/// `complete` feed becomes `gone` but stays, a returning one is `ok` again; then the newest `cap`
/// are kept, `gone` ones dropped first. The feed holds at most `cap` items already (the parser
/// keeps the newest), and `feed.more` says whether it had more.
async fn merge_feed(
    job: &mut Job<'_>,
    plan: &mut Plan,
    feed: sources::Feed,
    complete: bool,
    cap: usize,
    lan: bool,
) {
    let now = job.now;
    let more = feed.more;
    let mut fresh = feed.items;
    if !feed.oldest_first {
        fresh.reverse();
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
    let tail = pending_new;
    let mut merged: Vec<Item> = Vec::with_capacity(plan.items.len() + tail.len());
    let mut new_count = tail.len();
    for (index, mut item) in std::mem::take(&mut plan.items).into_iter().enumerate() {
        if let Some(mut inserted) = before.remove(&index) {
            new_count += inserted.len();
            merged.append(&mut inserted);
        }
        if !fresh_keys.contains(&item.key) && complete {
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
// Storytel (design 26b §2.4)
// ------------------------------------------------------------------------------------------------

/// A Storytel entry's check (vibb `storytel.py` `sync` minus the downloads, `write_shelf`): the
/// refused-login cooldown read from the row first (no request inside it), then the shelf (5
/// minutes old at most; fresh for Check now; a failed read is the check's error, never the kept
/// shelf - QA M1 of 2b: the card must say why) and the group whose target is the entry's. The list is the group as it is now: one `ok` item per
/// book in reading order, key = the consumable id, `url` NULL, the shelf's duration, the book's
/// cover as `art_url` (the art pass stores it and lists its hash, step 4a); every book is listed,
/// locked and geo-flagged ones too (the phone tries each).
async fn storytel_check(
    state: &AppState,
    job: &mut Job<'_>,
    plan: &mut Plan,
    target: &str,
    mode: Mode,
) -> Result<(), CheckError> {
    let storytel_error = |err: crate::storytel::StorytelError| CheckError::Storytel(err.code());
    let credentials = crate::storytel::credentials(state)
        .await
        .map_err(storytel_error)?;
    if credentials.cooldown_until(job.now).is_some() {
        return Err(CheckError::Storytel("login_refused".to_string()));
    }
    if job.spent() {
        return Err(CheckError::Timeout);
    }
    let max_age = if mode.requested {
        Duration::ZERO
    } else {
        crate::storytel::SHELF_TTL
    };
    let (shelf, sent) = state.storytel.shelf_counted(state, max_age).await;
    job.requests += sent;
    let shelf = shelf.map_err(storytel_error)?;
    let Some(group) = shelf.iter().find(|g| g.target == target) else {
        return Err(CheckError::Storytel("not_on_shelf".to_string()));
    };
    let now = job.now;
    let items: Vec<Item> = group
        .books
        .iter()
        .map(|book| {
            // A book listed before keeps its row - its first sighting and what the art pass found
            // (the hash, the URL it describes, the print, the tries; step 4a), as a feed's known
            // item does in `merge_feed` - so a check never drops the hash or fetches the cover
            // again. A changed cover URL is the art pass's to judge, as for a feed (design 27
            // §1.3: the same picture under a new query is adopted, another path fetched again).
            let row = match plan.position(&book.consumable_id) {
                Some(at) => plan.items[at].clone(),
                None => Item::new(book.consumable_id.clone(), now),
            };
            Item {
                state: "ok".to_string(),
                title: crate::music::cut_title(&book.title, sources::MAX_TITLE_CHARS),
                url: None,
                hls: false,
                duration_ms: (book.duration_ms > 0).then_some(book.duration_ms),
                art_url: book.cover.clone().filter(|a| music_net::media_url_ok(a)),
                attempts: 0,
                ..row
            }
        })
        .collect();
    let known = plan.keys();
    plan.new_items += items.iter().filter(|i| !known.contains(&i.key)).count();
    plan.items = items;
    plan.title =
        crate::music::cut_title(&group.name, sources::MAX_TITLE_CHARS).or(plan.title.take());
    plan.cover_candidate = group.cover.clone();
    plan.lan = Some(false);
    plan.fallback = false;
    plan.capped = false;
    // The anchor is the start: a cut drops the end (QA L2); the play order is untouched.
    plan.keep_end = Some("first".to_string());
    Ok(())
}

// ------------------------------------------------------------------------------------------------
// Spotify (design 26c §3.3-§3.4)
// ------------------------------------------------------------------------------------------------

/// The Web API reads of one check, each counted against the check's budget (the token refreshes
/// too) through `job`.
struct SpotifyReads<'s, 'j, 'a> {
    state: &'s AppState,
    job: &'j mut Job<'a>,
}

impl SpotifyReads<'_, '_, '_> {
    /// One GET as `account`, within the check's budget (past it: `timeout`).
    async fn get(&mut self, account: i64, url: &str) -> Result<Vec<u8>, SpotifyError> {
        if self.job.spent() {
            return Err(SpotifyError::Fetch(FetchError::Timeout));
        }
        let mut sent = 0;
        let answer = self
            .state
            .spotify
            .api_get_counted(self.state, account, url, Kind::Page, &mut sent)
            .await;
        self.job.requests += sent;
        answer.map(|response| response.body)
    }

    /// The first reader that answers (§3.3), in `ids`' order: a reader whose failure says it may
    /// not see it, or can't be used now ([spotify::try_next_reader]), lets the next try; any other
    /// failure ends the read **as that failure, never a refusal** (qa-26c-step2a-code M1: a
    /// transient on the next reader after a 403 isn't "no account may see it"). Every reader
    /// failing: `refused` only when each answered 403/404; the error is the first account-level
    /// one (the login, the key: what the parent can mend), else 404 if one said so, else 403.
    async fn first(&mut self, ids: &[i64], url: &str) -> Result<(i64, Vec<u8>), NoReader> {
        let mut turns = spotify::ReaderTurns::default();
        for &account in ids {
            match self.get(account, url).await {
                Ok(body) => return Ok((account, body)),
                Err(err) => {
                    if let Some(end) = turns.failed(err) {
                        return Err(end);
                    }
                }
            }
        }
        Err(turns.end())
    }

    /// The pages after `page` (read as `account`): `next_read(offset)` for each next page, until
    /// `max` are kept (`capped` when more were left) or Spotify has no next page. A track twice is
    /// kept once, at its first place (21b's dedupe rule). `(kept, left out, capped)`.
    async fn pages(
        &mut self,
        account: i64,
        mut page: spotify::Page,
        max: usize,
        next_url: impl Fn(usize) -> String,
        parse: fn(&[u8]) -> Option<spotify::Page>,
    ) -> Result<(Vec<spotify::Listed>, usize, bool), SpotifyError> {
        let mut kept: Vec<spotify::Listed> = Vec::new();
        let mut seen: HashSet<String> = HashSet::new();
        let mut skipped = 0;
        let mut offset = 0;
        // A list of nothing but left-out entries still ends: at most twice the pages `max` needs.
        let most_pages = max.div_ceil(spotify::PAGE_LIMIT) * 2;
        let mut read = 0;
        loop {
            read += 1;
            skipped += page.skipped;
            let mut listed = std::mem::take(&mut page.listed).into_iter();
            for item in listed.by_ref() {
                if !seen.insert(item.id.clone()) {
                    continue;
                }
                if kept.len() == max {
                    return Ok((kept, skipped, true));
                }
                kept.push(item);
            }
            if !page.next {
                return Ok((kept, skipped, false));
            }
            if kept.len() == max || read >= most_pages {
                return Ok((kept, skipped, true));
            }
            offset += spotify::PAGE_LIMIT;
            let body = self.get(account, &next_url(offset)).await?;
            page = parse(&body).ok_or(SpotifyError::BadAnswer)?;
        }
    }
}

/// A Spotify entry's check (design 26c §3.4; vibb `library.py`'s precache gate and
/// `context_tracks`): without an app `spotify_off`, without a usable account `no_account` (or
/// `reauthorize` when every login ended), inside the quota pause `quota` - no request. Then the
/// kind's reads with the readers (§3.3): a playlist's metadata (its `snapshot_id` unchanged: done,
/// one request - not for Check now; in context mode and tried in the last day: done too), then its
/// items with each reader in turn until one may list them (none: context mode); an album's tracks
/// in disc and track order; a track or an episode as one item; a show's newest 100 episodes (its
/// newest episode and count unchanged: done after one small read - not for Check now); an artist in
/// context mode - or, with `SPOTIFY_ARTIST_ALBUMS`, as its albums' tracks. Every failure keeps the
/// last good list (`finish`). The items keep their rows (first sighting, the art pass's result) as
/// a feed's do; a track that left the playlist leaves the list.
async fn spotify_check(
    state: &AppState,
    job: &mut Job<'_>,
    plan: &mut Plan,
    kind: LinkKind,
    id: &str,
    old: &Listing,
    mode: Mode,
) -> Result<Result<(), CheckError>, sqlx::Error> {
    let fail = |err: SpotifyError| Ok(Err(CheckError::Spotify(err.code())));
    let Some(_app) = spotify::app(state).await? else {
        return fail(SpotifyError::Off);
    };
    let readers = spotify::readers(&state.db, old.spotify_reader).await?;
    if let Some(err) = readers.refusal() {
        return fail(err);
    }
    if state.spotify.quota_until().is_some() {
        return fail(SpotifyError::Quota);
    }
    let ids = readers.ids;
    let now = job.now;
    let accounts = &state.spotify;
    let mut reads = SpotifyReads { state, job };
    plan.lan = Some(false);
    plan.fallback = false;
    let mut listed: Vec<spotify::Listed>;
    let skipped: usize;
    let mut capped = false;
    let mut keep_end = "first";
    let mut snapshot = None;
    let reader;
    // The metadata read: the name, the picture (a playlist's snapshot).
    let meta_read = match kind {
        LinkKind::Playlist => Read::Playlist(id),
        LinkKind::Album => Read::Album(id),
        LinkKind::Track => Read::Track(id),
        LinkKind::Artist => Read::Artist(id),
        LinkKind::Show => Read::Show(id),
        LinkKind::Episode => Read::Episode(id),
    };
    let (meta_reader, body) = match reads.first(&ids, &accounts.read_url(meta_read)).await {
        Ok(found) => found,
        Err(none) => return fail(none.err),
    };
    // The metadata, and an album's, track's or episode's items with it.
    let (meta, first_page) = match kind {
        LinkKind::Playlist => (spotify::parse_playlist_meta(&body), None),
        LinkKind::Artist | LinkKind::Show => (spotify::parse_named(&body), None),
        LinkKind::Album => {
            spotify::parse_album(&body).map_or((None, None), |(m, p)| (Some(m), Some(p)))
        }
        LinkKind::Track => {
            spotify::parse_track(&body).map_or((None, None), |(m, p)| (Some(m), Some(p)))
        }
        LinkKind::Episode => {
            spotify::parse_episode(&body).map_or((None, None), |(m, p)| (Some(m), Some(p)))
        }
    };
    let Some(meta) = meta else {
        return fail(SpotifyError::BadAnswer);
    };
    plan.title = meta
        .name
        .as_deref()
        .and_then(|name| crate::music::cut_title(name, sources::MAX_TITLE_CHARS))
        .or(plan.title.take());
    plan.cover_candidate = meta.image.clone();
    match kind {
        LinkKind::Playlist => {
            // The snapshot gate (vibb `_precache_due`): an unchanged playlist costs this one
            // request - its items are never read again while `snapshot_id` stays the same
            // (Spotify moves it with any change to the list: a track added, removed or moved).
            // What that knowingly gives up: a track's availability, relinking or metadata
            // changing without a snapshot change goes unseen until the snapshot moves or Check now
            // (which always reads the items); the phone and the speaker skip an unplayable track
            // anyway.
            if !mode.requested
                && old.spotify_mode.as_deref() == Some("list")
                && meta.snapshot.is_some()
                && old.spotify_snapshot == meta.snapshot
            {
                return Ok(Ok(()));
            }
            // A playlist in context mode (no reader may list it; the phone plays it as a share
            // link, design 54): its items are tried again at most once a day - every reader
            // answers 403 again until one got access - unless Check now or a Spotify login newer
            // than the last try (that account may be the one that can list it).
            if !mode.requested
                && old.spotify_mode.as_deref() == Some("context")
                && context_tried_lately(&state.db, old, now).await?
            {
                return Ok(Ok(()));
            }
            snapshot = meta.snapshot.clone();
            let url = accounts.read_url(Read::PlaylistItems(id, 0));
            let (items_reader, body) = match reads.first(&ids, &url).await {
                Ok(found) => found,
                // No account owns it or collaborates on it: the phones play it as a context.
                Err(none) if none.refused => {
                    spotify_context(plan, Some(meta_reader), snapshot, Some(stamp(now)));
                    return Ok(Ok(()));
                }
                Err(none) => return fail(none.err),
            };
            let Some(page) = spotify::parse_playlist_items(&body) else {
                return fail(SpotifyError::BadAnswer);
            };
            let next = |offset| accounts.read_url(Read::PlaylistItems(id, offset));
            match reads
                .pages(
                    items_reader,
                    page,
                    spotify::MAX_LIST_TRACKS,
                    next,
                    spotify::parse_playlist_items,
                )
                .await
            {
                Ok(read) => (listed, skipped, capped) = read,
                Err(err) => return fail(err),
            }
            reader = items_reader;
        }
        LinkKind::Album => {
            let page = first_page.unwrap_or_default();
            let next = |offset| accounts.read_url(Read::AlbumTracks(id, offset));
            match reads
                .pages(
                    meta_reader,
                    page,
                    spotify::MAX_LIST_TRACKS,
                    next,
                    spotify::parse_album_tracks,
                )
                .await
            {
                Ok(read) => (listed, skipped, capped) = read,
                Err(err) => return fail(err),
            }
            // Disc and track order (Spotify's own; kept as read where a number is missing).
            if listed.iter().all(|t| t.disc_track.is_some()) {
                listed.sort_by_key(|t| t.disc_track);
            }
            reader = meta_reader;
        }
        LinkKind::Track | LinkKind::Episode => {
            let page = first_page.unwrap_or_default();
            skipped = page.skipped;
            listed = page.listed;
            reader = meta_reader;
        }
        LinkKind::Show => {
            // The show gate (as a playlist's snapshot): its newest episode and the episode count
            // in one small read; the same as at the last read - done. Check now reads anyway.
            // What it gives up, as the snapshot gate does: an older episode edited, made
            // unplayable or swapped without the count or the newest changing.
            if !mode.requested
                && old.spotify_mode.as_deref() == Some("list")
                && old.spotify_snapshot.is_some()
            {
                let mark = match reads
                    .get(meta_reader, &accounts.read_url(Read::ShowNewest(id)))
                    .await
                {
                    Ok(body) => match spotify::parse_show_episodes(&body) {
                        Some(_) => spotify::show_mark(&body),
                        None => return fail(SpotifyError::BadAnswer),
                    },
                    Err(err) => return fail(err),
                };
                if mark.is_some() && mark == old.spotify_snapshot {
                    return Ok(Ok(()));
                }
            }
            let url = accounts.read_url(Read::ShowEpisodes(id, 0));
            let page = match reads.get(meta_reader, &url).await {
                Ok(body) => match spotify::parse_show_episodes(&body) {
                    Some(page) => {
                        snapshot = spotify::show_mark(&body);
                        page
                    }
                    None => return fail(SpotifyError::BadAnswer),
                },
                Err(err) => return fail(err),
            };
            let next = |offset| accounts.read_url(Read::ShowEpisodes(id, offset));
            match reads
                .pages(
                    meta_reader,
                    page,
                    spotify::MAX_SHOW_EPISODES,
                    next,
                    spotify::parse_show_episodes,
                )
                .await
            {
                Ok(read) => (listed, skipped, capped) = read,
                Err(err) => return fail(err),
            }
            // Spotify lists a show newest first; the list is kept oldest first, as a podcast's.
            listed.reverse();
            keep_end = "newest";
            reader = meta_reader;
        }
        LinkKind::Artist => {
            if !accounts.config().artist_albums {
                // Context mode (the default, §3.3): the metadata only, one request a day.
                spotify_context(plan, Some(meta_reader), None, None);
                return Ok(Ok(()));
            }
            match artist_albums(&mut reads, &ids, id).await {
                Ok(Some((read_by, read))) => {
                    (listed, skipped, capped) = read;
                    reader = read_by;
                }
                // Spotify refused the artist's albums: played as Spotify picks.
                Ok(None) => {
                    spotify_context(plan, Some(meta_reader), None, None);
                    return Ok(Ok(()));
                }
                Err(err) => return fail(err),
            }
        }
    }
    // The items: a known one keeps its row (its first sighting, the art pass's result).
    let mut known: HashMap<String, Item> = std::mem::take(&mut plan.items)
        .into_iter()
        .map(|item| (item.key.clone(), item))
        .collect();
    plan.new_items += listed.iter().filter(|t| !known.contains_key(&t.id)).count();
    plan.items = listed
        .into_iter()
        .map(|track| {
            let row = known
                .remove(&track.id)
                .unwrap_or_else(|| Item::new(track.id.clone(), now));
            Item {
                state: "ok".to_string(),
                title: crate::music::cut_title(&track.title, sources::MAX_TITLE_CHARS),
                url: None,
                hls: false,
                duration_ms: track.duration_ms,
                art_url: track.art.filter(|a| music_net::media_url_ok(a)),
                attempts: 0,
                ..row
            }
        })
        .collect();
    plan.capped = capped;
    plan.keep_end = Some(keep_end.to_string());
    plan.spotify = SpotifyPlan {
        mode: Some("list".to_string()),
        snapshot,
        items_at: Some(stamp(now)),
        reader: Some(reader),
        skipped: skipped as i64,
    };
    Ok(Ok(()))
}

/// Context mode (design 26c §3.3): no items - the phone plays the Spotify context - and the
/// listing's mode, reader and snapshot; `tried` = when a playlist's items were last tried
/// (`spotify_items_at` in context mode, [context_tried_lately]).
fn spotify_context(
    plan: &mut Plan,
    reader: Option<i64>,
    snapshot: Option<String>,
    tried: Option<String>,
) {
    plan.items.clear();
    plan.capped = false;
    plan.keep_end = Some("first".to_string());
    plan.spotify = SpotifyPlan {
        mode: Some("context".to_string()),
        snapshot,
        items_at: tried,
        reader,
        skipped: 0,
    };
}

/// A context playlist's items were tried in the 24 h before `now` and no Spotify account logged in
/// since: not tried again yet (a listing with no try stamped - from before 2026-10-21 - is tried).
async fn context_tried_lately(
    db: &sqlx::SqlitePool,
    old: &Listing,
    now: DateTime<Utc>,
) -> Result<bool, sqlx::Error> {
    let Some(tried) = old.spotify_items_at.as_deref().and_then(parse_stamp) else {
        return Ok(false);
    };
    if tried + hours(24) <= now {
        return Ok(false);
    }
    let login: Option<String> =
        sqlx::query_scalar("SELECT MAX(authorized_at) FROM music_spotify_accounts")
            .fetch_one(db)
            .await?;
    Ok(login
        .as_deref()
        .and_then(parse_stamp)
        .is_none_or(|login| login <= tried))
}

/// The artist option (design 26c §3.3, `SPOTIFY_ARTIST_ALBUMS`): the artist's albums and singles
/// (with the first reader that may), newest first, at most [spotify::MAX_ARTIST_ALBUMS] of them
/// and [spotify::MAX_ARTIST_TRACKS] tracks, each album's tracks in order with the album's picture.
/// `Ok(None)` = every reader got 403/404 for the albums (context mode then); an album that is
/// gone (404) is passed over. `(reader, (kept, left out, capped))`.
#[allow(clippy::type_complexity)]
async fn artist_albums(
    reads: &mut SpotifyReads<'_, '_, '_>,
    ids: &[i64],
    id: &str,
) -> Result<Option<(i64, (Vec<spotify::Listed>, usize, bool))>, SpotifyError> {
    let accounts = &reads.state.spotify;
    let (reader, body) = match reads
        .first(ids, &accounts.read_url(Read::ArtistAlbums(id, 0)))
        .await
    {
        Ok(found) => found,
        Err(none) if none.refused => return Ok(None),
        Err(none) => return Err(none.err),
    };
    let (mut albums, mut next) =
        spotify::parse_artist_albums(&body).ok_or(SpotifyError::BadAnswer)?;
    let mut pages = 1;
    while next && pages < spotify::MAX_ARTIST_ALBUM_PAGES {
        let url = accounts.read_url(Read::ArtistAlbums(id, pages * spotify::ARTIST_ALBUMS_LIMIT));
        let body = reads.get(reader, &url).await?;
        let (more, more_next) =
            spotify::parse_artist_albums(&body).ok_or(SpotifyError::BadAnswer)?;
        albums.extend(more);
        next = more_next;
        pages += 1;
    }
    let mut seen_albums = HashSet::new();
    albums.retain(|album| seen_albums.insert(album.id.clone()));
    // Newest first (a stable sort: Spotify's order among albums of one date).
    albums.sort_by(|a, b| b.released.cmp(&a.released));
    let mut capped = next || albums.len() > spotify::MAX_ARTIST_ALBUMS;
    albums.truncate(spotify::MAX_ARTIST_ALBUMS);
    let mut kept: Vec<spotify::Listed> = Vec::new();
    let mut seen: HashSet<String> = HashSet::new();
    let mut skipped = 0;
    for album in albums {
        let room = spotify::MAX_ARTIST_TRACKS - kept.len();
        if room == 0 {
            capped = true;
            break;
        }
        let body = match reads
            .get(reader, &accounts.read_url(Read::Album(&album.id)))
            .await
        {
            Ok(body) => body,
            Err(SpotifyError::NotFound) => continue,
            Err(err) => return Err(err),
        };
        let (meta, page) = spotify::parse_album(&body).ok_or(SpotifyError::BadAnswer)?;
        let album_id = album.id.clone();
        let next = |offset| accounts.read_url(Read::AlbumTracks(&album_id, offset));
        let (mut tracks, left_out, more) = reads
            .pages(reader, page, room, next, spotify::parse_album_tracks)
            .await?;
        skipped += left_out;
        if tracks.iter().all(|t| t.disc_track.is_some()) {
            tracks.sort_by_key(|t| t.disc_track);
        }
        for mut track in tracks {
            if seen.insert(track.id.clone()) {
                track.art = meta.image.clone();
                kept.push(track);
            }
        }
        capped |= more;
    }
    Ok(Some((reader, (kept, skipped, capped))))
}

// ------------------------------------------------------------------------------------------------
// Covers (§2.6)
// ------------------------------------------------------------------------------------------------

/// A cover fetched and processed, to be stored under the store's lock at the commit.
struct CoverResult(Option<(String, crate::photos::ProcessedPhoto)>);

impl CoverResult {
    fn jpeg(&self) -> Option<&[u8]> {
        self.0.as_ref().map(|(_, photo)| photo.jpeg.as_slice())
    }
}

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
    let lan = lan_art(plan, link, &url);
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
// Episode art (design 27 §1.3-§1.4, with its QA M2, M3 and L1)
// ------------------------------------------------------------------------------------------------

/// The same picture's URL with another query (a signed or cache-busting URL, `?t=<n>`): the same
/// scheme, host, port and path (code QA M1).
fn same_resource(a: &str, b: &str) -> bool {
    match (reqwest::Url::parse(a), reqwest::Url::parse(b)) {
        (Ok(a), Ok(b)) => {
            a.scheme() == b.scheme()
                && a.host_str() == b.host_str()
                && a.port_or_known_default() == b.port_or_known_default()
                && a.path() == b.path()
        }
        _ => false,
    }
}

/// The series pictures' prints (QA M2): a cover fetched now, else the stored cover's file (read
/// once, no request); NRK's 16:9 image, fetched once when its URL changes (at most once a day,
/// counted against the day's requests).
async fn reference_prints(
    state: &AppState,
    job: &mut Job<'_>,
    plan: &mut Plan,
    old: &Listing,
    found: &CoverResult,
) -> Result<(), sqlx::Error> {
    let now = job.now;
    plan.art.cover_url = match &found.0 {
        Some((url, _)) => Some(url.clone()),
        None => plan.cover_url.clone(),
    };
    if let Some(jpeg) = found.jpeg() {
        if let Some(print) = crate::photos::print_limited(jpeg.to_vec()).await {
            plan.art.new_cover(print);
        }
    } else if plan.art.cover_print.is_none()
        && let Some(path) = old
            .cover_hash
            .as_deref()
            .and_then(|hash| crate::photos::path_for(&state.music_cover_dir, hash))
        && let Ok(bytes) = tokio::fs::read(&path).await
    {
        plan.art.cover_print = crate::photos::print_limited(bytes).await;
    }
    let Some(candidate) = plan.art.alt_candidate.take() else {
        return Ok(());
    };
    let Some(url) = candidate else {
        // Looked: the root has no 16:9 picture besides its cover (`cover_alt_at` set, code QA L1).
        plan.art.alt_url = None;
        plan.art.alt_print = None;
        plan.art.alt_at = Some(stamp(now));
        return Ok(());
    };
    let changed = plan.art.alt_url.as_deref() != Some(url.as_str()) || plan.art.alt_print.is_none();
    let tried_recently = plan
        .art
        .alt_at
        .as_deref()
        .and_then(parse_stamp)
        .is_some_and(|at| at > now - hours(24));
    if !changed || tried_recently {
        return Ok(());
    }
    plan.art.alt_at = Some(stamp(now));
    if !music_net::media_url_ok(&url) || !job.public_media(&url).await {
        return Ok(());
    }
    let got = job
        .get(SourceRequest::new(url.clone(), Kind::Image, Reach::Public))
        .await;
    let answer = match got {
        Got::Answer(answer) if answer.status == 200 && !answer.truncated => answer,
        _ => return Ok(()),
    };
    // Centre-cropped like an episode's 16:9 picture would be.
    let Ok(photo) =
        crate::photos::process_limited(answer.body.into(), crate::photos::Shape::Square).await
    else {
        return Ok(());
    };
    if let Some(print) = crate::photos::print_limited(photo.jpeg).await {
        plan.art.alt_url = Some(url);
        plan.art.alt_print = Some(print);
    }
    Ok(())
}

/// Whether a print is one of the series pictures.
fn series_picture(print: i64, refs: &[i64]) -> bool {
    refs.iter()
        .any(|reference| crate::photos::same_picture(print, *reference))
}

/// Whether an item takes part in the art pass at all: listed (`ok`) with a picture URL, and
/// playable from the list - an NRK/RSS episode with its source URL, or a Storytel book (design 26b
/// §5.5, step 4a: `ok` with `url` NULL by design, its `art_url` the shelf's cover) or a Spotify
/// track or episode (design 26c §3.5: `ok` with `url` NULL, the phone plays its URI). The entry's
/// link tells them apart: an NRK/RSS item without a URL isn't playable, and it is skipped.
pub fn art_candidate(item: &Item, link: &Link) -> bool {
    let playable =
        item.url.is_some() || matches!(link, Link::Storytel { .. } | Link::Spotify { .. });
    item.state == "ok" && playable && item.art_url.is_some()
}

/// Whether an entry's item pictures are judged against nothing (every fetched one its own: no
/// reference prints, no `same`, no twin merge): a Storytel book's (qa-26b-step4a-code M1), and a
/// Spotify playlist's, track's or artist's track (design 26c §3.5: a playlist's mosaic cover is no
/// reference for its tracks; their album pictures are their own). A Spotify show's episodes are
/// judged as an RSS episode's.
fn art_judged_alone(link: &Link) -> bool {
    matches!(
        link,
        Link::Storytel { .. }
            | Link::Spotify {
                kind: LinkKind::Playlist | LinkKind::Track | LinkKind::Album | LinkKind::Artist,
                ..
            }
    )
}

/// Whether an item's picture is to be tried now (§1.3): a URL not tried yet, or a failure a day
/// old with tries left; a judged picture's moved URL at most weekly.
fn art_due(item: &Item, now: DateTime<Utc>) -> bool {
    let Some(url) = item.art_url.as_deref() else {
        return false;
    };
    if let Some(from) = item.art_from.as_deref()
        && from != url
    {
        // A judged picture whose URL moved to another path (a query change is adopted before
        // this, with no request): fetched again at most weekly (code QA M1).
        let judged = matches!(item.art_state.as_deref(), Some("own" | "same"));
        let recent = item
            .art_at
            .as_deref()
            .and_then(parse_stamp)
            .is_some_and(|at| at > now - TimeDelta::days(ART_MOVED_RETRY_DAYS));
        return !(judged && recent);
    }
    if item.art_from.is_none() {
        return true;
    }
    let day_old = item
        .art_at
        .as_deref()
        .and_then(parse_stamp)
        .is_none_or(|at| at <= now - hours(24));
    match item.art_state.as_deref() {
        Some("failed") => item.art_fails < ART_MAX_FAILS && day_old,
        // Its host was down at the last try (code QA M2): a day later, no try used.
        None => day_old,
        _ => false,
    }
}

fn art_untried(item: &mut Item) {
    item.art_from = None;
    item.art_state = None;
    item.art_hash = None;
    item.art_print = None;
    item.art_tone = None;
}

fn art_same(item: &mut Item, url: String, print: Option<i64>, now: DateTime<Utc>) {
    item.art_from = Some(url);
    item.art_state = Some("same".to_string());
    item.art_hash = None;
    item.art_print = print;
    item.art_tone = None;
    item.art_at = Some(stamp(now));
    item.art_fails = 0;
}

fn art_failed(item: &mut Item, url: String, now: DateTime<Utc>) {
    item.art_from = Some(url);
    item.art_state = Some("failed".to_string());
    item.art_hash = None;
    item.art_print = None;
    item.art_tone = None;
    item.art_at = Some(stamp(now));
    item.art_fails += 1;
}

/// Two own pictures of an entry this close in print are one picture (QA M2's merge), when their
/// colours match too and the print has structure enough to tell (code QA L4): a tighter line
/// than the series picture's, since a wrong merge shows another episode's picture.
pub const TWIN_BITS: u32 = 2;

/// Whether two own pictures are one (the twin merge, [TWIN_BITS]).
pub fn twins(a: (i64, Option<i64>), b: (i64, Option<i64>)) -> bool {
    let (Some(tone_a), Some(tone_b)) = (a.1, b.1) else {
        return false;
    };
    crate::photos::print_distance(a.0, b.0) <= TWIN_BITS
        && !crate::photos::low_detail(a.0)
        && crate::photos::same_tone(tone_a, tone_b)
}

/// The item's own picture. A picture the entry already has under another hash ([twins]) reuses
/// that hash when `merge` is on, so the phone sees one picture as one (QA M2); off for Storytel
/// books (templated covers would merge, qa-26b-step4a-code M1).
#[allow(clippy::too_many_arguments)]
fn art_own(
    plan: &mut Plan,
    index: usize,
    url: String,
    hash: String,
    print: i64,
    tone: Option<i64>,
    file: Option<crate::photos::ProcessedPhoto>,
    merge: bool,
    now: DateTime<Utc>,
) {
    let twin = plan
        .items
        .iter()
        .enumerate()
        .filter(|_| merge)
        .filter(|(at, other)| *at != index && other.art_state.as_deref() == Some("own"))
        .find_map(|(_, other)| {
            let print_b = other.art_print?;
            twins((print, tone), (print_b, other.art_tone)).then(|| other.art_hash.clone())?
        });
    let hash = match twin {
        Some(twin) => twin,
        None => {
            match file {
                Some(file) if !plan.art.new_files.iter().any(|f| f.hash == file.hash) => {
                    plan.art.new_files.push(file)
                }
                Some(_) => {}
                None => {
                    plan.art.copied.insert(hash.clone());
                }
            }
            hash
        }
    };
    let item = &mut plan.items[index];
    item.art_from = Some(url);
    item.art_state = Some("own".to_string());
    item.art_hash = Some(hash);
    item.art_print = Some(print);
    item.art_tone = tone;
    item.art_at = Some(stamp(now));
    item.art_fails = 0;
    plan.art.own += 1;
    plan.art.backlog = true;
    plan.art.backlog_at = None;
    plan.art.same_run = 0;
}

/// What step 2 found: the stored picture (if any), its print and tone.
type KnownArt = (Option<String>, i64, Option<i64>);

/// Step 2: a result another item already has for this URL - this entry's first, else any entry's
/// (one with a stored picture first). `(art_hash, art_print, art_tone)`.
async fn known_art(
    db: &sqlx::SqlitePool,
    plan: &Plan,
    index: usize,
    url: &str,
) -> Result<Option<KnownArt>, sqlx::Error> {
    let judged = |item: &Item| matches!(item.art_state.as_deref(), Some("own" | "same"));
    let mut own_entry: Vec<KnownArt> = plan
        .items
        .iter()
        .enumerate()
        .filter(|(at, item)| *at != index && item.art_from.as_deref() == Some(url) && judged(item))
        .filter_map(|(_, item)| {
            item.art_print
                .map(|p| (item.art_hash.clone(), p, item.art_tone))
        })
        .collect();
    own_entry.sort_by_key(|(hash, _, _)| hash.is_none());
    if let Some(found) = own_entry.into_iter().next() {
        return Ok(Some(found));
    }
    sqlx::query_as(
        "SELECT art_hash, art_print, art_tone FROM music_items \
         WHERE art_from = ? AND art_print IS NOT NULL AND art_state IN ('own', 'same') \
         ORDER BY art_hash IS NULL LIMIT 1",
    )
    .bind(url)
    .fetch_optional(db)
    .await
}

/// Step 6 (QA M2): the series pictures changed - judge every item again from its stored print,
/// with no request. `own` that is now a series picture -> `same` (its file is pruned); `same` that
/// no longer is -> untried (it stored no file, so the budget fetches it).
fn rejudge(plan: &mut Plan, refs: &[i64]) {
    let cover_url = plan.art.cover_url.clone();
    let alt_url = plan.art.alt_url.clone();
    for item in plan.items.iter_mut() {
        match (item.art_state.as_deref(), item.art_print) {
            (Some("own"), Some(print)) if series_picture(print, refs) => {
                item.art_state = Some("same".to_string());
                item.art_hash = None;
            }
            (Some("same"), Some(print)) if !series_picture(print, refs) => art_untried(item),
            (Some("same"), None) if item.art_url != cover_url && item.art_url != alt_url => {
                art_untried(item)
            }
            _ => {}
        }
    }
}

/// The art pass of a check (§1.3-§1.4, design 39 §2.4): every `ok` item in play order, this
/// check's new items first, until done - no picture budget since 2026-10-19 (`gets` is the tests'
/// cap, else `u32::MAX`). The check's own budget or a host's trouble may cut it short: the items
/// left stay untried (QA L1 b) and are counted in `plan.art.more`, which makes the entry due again
/// at once ([Why::Art]) - only when the pass sent a picture request, and behind a host in trouble
/// only when that host answered in this pass, so a dead host waits for the regular check while the
/// entry's other hosts go on. Never fails the check: a failed picture shows the series cover.
async fn art_pass(
    db: &sqlx::SqlitePool,
    job: &mut Job<'_>,
    plan: &mut Plan,
    link: &Link,
    play_order: &str,
    mut gets: u32,
) -> Result<(), sqlx::Error> {
    let capped = gets > 0;
    let now = job.now;
    // A Storytel book is judged against nothing (qa-26b-step4a-code M1): its "series cover" is
    // only the shelf's first-seen book's cover, so `same` (show the entry cover) could show
    // another book's face - after the first book changes, with a parent's cover, or with
    // templated covers. Every fetched cover is the book's own, and two books are never twins. A
    // Spotify playlist's, track's or artist's track pictures likewise ([art_judged_alone]).
    let alone = art_judged_alone(link);
    let refs = if alone { Vec::new() } else { plan.art.refs() };
    if alone {
        for item in plan.items.iter_mut() {
            if item.art_state.as_deref() == Some("same") {
                art_untried(item);
            }
        }
    }
    // A picture the source no longer names.
    for item in plan.items.iter_mut() {
        if item.art_url.is_none() && (item.art_from.is_some() || item.art_state.is_some()) {
            art_untried(item);
            item.art_fails = 0;
        }
    }
    // The same picture under a new query (signed or cache-busting URLs): the result stays, no
    // request (code QA M1; vibb keys its picture by episode, not URL).
    for item in plan.items.iter_mut() {
        if let (Some(from), Some(url)) = (item.art_from.as_deref(), item.art_url.as_deref())
            && from != url
            && item.art_state.is_some()
            && same_resource(from, url)
        {
            item.art_from = Some(url.to_string());
        }
    }
    let refs_key = refs
        .iter()
        .map(i64::to_string)
        .collect::<Vec<_>>()
        .join(",");
    if plan.art.refs_key.as_deref() != Some(refs_key.as_str()) {
        rejudge(plan, &refs);
        plan.art.refs_key = Some(refs_key);
    }
    // The backlog comes back once a month (QA L1 e).
    if !plan.art.backlog
        && plan
            .art
            .backlog_at
            .as_deref()
            .and_then(parse_stamp)
            .is_none_or(|at| at + TimeDelta::days(ART_BACKLOG_RETRY_DAYS) <= now)
    {
        plan.art.backlog = true;
        plan.art.backlog_at = None;
        plan.art.same_run = 0;
    }
    // The play order (21 §4.4, `music::effective_order`): what the kid reaches first. `auto` is
    // newest first for a podcast or feed, from episode 1 for an NRK series that keeps its start,
    // and from book 1 for Storytel (reading order: the phone's `naturallyNewestFirst(STORYTEL)`
    // is false, design 26b §3.2).
    let newest_first = match play_order {
        "newest_first" => true,
        "oldest_first" => false,
        _ => match link {
            Link::Storytel { .. } => false,
            Link::NrkSeries { .. } => plan.keep_end.as_deref() == Some("newest"),
            Link::NrkPodcast { .. } | Link::Rss { .. } => true,
            // A show newest first (a podcast; the phone's `naturallyNewestFirst`), a playlist
            // from the top, an album in track order (design 26c §4.2).
            Link::Spotify { kind, .. } => *kind == LinkKind::Show,
        },
    };
    let mut order: Vec<usize> = (0..plan.items.len()).collect();
    if newest_first {
        order.reverse();
    }
    let this_check = stamp(now);
    let backlog = plan.art.backlog;
    // With the backlog off, an item first seen after it went off is still new: one that missed
    // its first check (the check's budget, a host outage) isn't left for a month (code QA L2).
    let off_since = if backlog {
        None
    } else {
        plan.art.backlog_at.as_deref().and_then(parse_stamp)
    };
    let is_new = |item: &Item| {
        item.first_seen_at == this_check
            || off_since
                .is_some_and(|off| parse_stamp(&item.first_seen_at).is_some_and(|f| f > off))
    };
    // Every candidate, the parent's near end first (design 39 §2.4: no window; since 2026-10-19
    // no picture budget either), the new ones first.
    let (new, older): (Vec<usize>, Vec<usize>) = order
        .into_iter()
        .filter(|at| art_candidate(&plan.items[*at], link))
        .partition(|at| is_new(&plan.items[*at]));
    let candidates: Vec<usize> = new
        .into_iter()
        .chain(older.into_iter().filter(|_| backlog))
        .filter(|at| art_due(&plan.items[*at], now))
        .collect();
    // Per image host: network errors (and 5xx answers) in a row, and whether it answered in this
    // pass (code QA M2; per host since Q26). A host at [ART_NETWORK_FAILURES] is down for the rest
    // of the pass: its pictures wait untried, the other hosts' go on.
    let mut hosts: HashMap<String, (u32, bool)> = HashMap::new();
    let down = |hosts: &HashMap<String, (u32, bool)>, url: &str| {
        let host = music_net::host_of(url).unwrap_or_default();
        hosts
            .get(&host)
            .filter(|(failures, _)| *failures >= ART_NETWORK_FAILURES)
            .map(|(_, answered)| *answered)
    };
    // Pictures left behind a host that answered in this pass and then went down (a blip): due
    // again at once. Behind a host that never answered they wait for the regular check.
    let mut blipped = 0;
    // Host troubles in this pass over all hosts ([ART_PASS_FAILURES]).
    let mut troubles = 0;
    // The pass's memo of URLs it sent a request for and got no picture from (design 26c §3.5: a
    // playlist repeats its albums' pictures): the next item naming one costs no request. A URL
    // judged in this pass is found again by step 2 (this entry's items first).
    let mut tried: HashMap<String, bool> = HashMap::new();
    // Untried candidates the pass leaves: held by the tests' cap, or the rest after a cut.
    let mut held = 0;
    let mut cut_at: Option<usize> = None;
    // Whether the pass sent a picture request at all (qa-art-asap-code L3): a check whose listing
    // spent its whole budget doesn't come back at once only to spend it again.
    let mut requested = false;
    for (step, &index) in candidates.iter().enumerate() {
        let Some(url) = plan.items[index].art_url.clone() else {
            continue;
        };
        if plan.items[index].art_from.as_deref() != Some(url.as_str()) {
            plan.items[index].art_fails = 0;
        }
        // 1. The series picture's own URL: no request (never for a book, see above).
        let series_print = if alone {
            None
        } else if plan.art.cover_url.as_deref() == Some(url.as_str()) {
            Some(plan.art.cover_print)
        } else if plan.art.alt_url.as_deref() == Some(url.as_str()) {
            Some(plan.art.alt_print)
        } else {
            None
        };
        if let Some(print) = series_print {
            art_same(&mut plan.items[index], url, print, now);
            plan.art.same += 1;
            continue;
        }
        // 2. A URL another item has a result for: judged for this entry, no request (a `same`
        // elsewhere stored no file, so this entry's `own` needs the request, QA L1 c).
        if let Some((hash, print, tone)) = known_art(db, plan, index, &url).await? {
            if series_picture(print, &refs) {
                art_same(&mut plan.items[index], url, Some(print), now);
                plan.art.same += 1;
                continue;
            }
            if let Some(hash) = hash {
                art_own(plan, index, url, hash, print, tone, None, !alone, now);
                continue;
            }
        }
        // The memo: this pass got no picture from the URL already - failed (a try used, as for
        // the first item), or its host was in trouble (a day later, no try used).
        if let Some(&host_trouble) = tried.get(&url) {
            let item = &mut plan.items[index];
            if host_trouble {
                item.art_at = Some(stamp(now));
                if item.art_state.is_none() {
                    item.art_from = Some(url);
                }
            } else {
                art_failed(item, url, now);
            }
            continue;
        }
        // 3. One request (the tests' cap aside, no picture budget: 2026-10-19), unless its host
        // is down in this pass.
        if let Some(answered) = down(&hosts, &url) {
            blipped += usize::from(answered);
            continue;
        }
        if gets == 0 {
            held += 1;
            continue;
        }
        if job.spent() {
            cut_at = Some(step);
            break;
        }
        let lan = lan_art(plan, link, &url);
        let reach = if lan { Reach::Any } else { Reach::Public };
        if !music_net::media_url_ok(&url) || (!lan && !job.public_media(&url).await) {
            tried.insert(url.clone(), false);
            art_failed(&mut plan.items[index], url, now);
            continue;
        }
        let got = job
            .get(SourceRequest::new(url.clone(), Kind::Image, reach))
            .await;
        if matches!(got, Got::Skipped) {
            cut_at = Some(step);
            break;
        }
        gets -= 1;
        requested = true;
        plan.items[index].art_fetched_at = Some(stamp(now));
        // A network error, a timeout or a 5xx is the host's trouble, not the picture's: it
        // doesn't use up one of the picture's tries (a result it has stays; it waits a day), and
        // a few in a row end the pass (code QA M2).
        let host_trouble = match &got {
            Got::Failed(_) => true,
            Got::Answer(answer) => answer.status >= 500,
            Got::Skipped => false,
        };
        let host = hosts
            .entry(music_net::host_of(&url).unwrap_or_default())
            .or_default();
        if host_trouble {
            host.0 += 1;
            tried.insert(url.clone(), true);
            let item = &mut plan.items[index];
            item.art_at = Some(stamp(now));
            if item.art_state.is_none() {
                item.art_from = Some(url);
            }
            troubles += 1;
            if troubles >= ART_PASS_FAILURES && !hosts.values().any(|(_, answered)| *answered) {
                // No host answers: the rest wait for the regular check, untried.
                break;
            }
            continue;
        }
        *host = (0, true);
        let photo = match got {
            Got::Answer(answer) if answer.status == 200 && !answer.truncated => {
                crate::photos::process_limited(answer.body.into(), crate::photos::Shape::Square)
                    .await
                    .ok()
            }
            _ => None,
        };
        let printed = match photo {
            Some(photo) => crate::photos::fingerprint_limited(photo.jpeg.clone())
                .await
                .map(|print| (photo, print)),
            None => None,
        };
        // 5. A failure never fails the check; the item shows the series cover.
        let Some((photo, (print, tone))) = printed else {
            tried.insert(url.clone(), false);
            art_failed(&mut plan.items[index], url, now);
            continue;
        };
        // 4. The print: the series picture under another URL, size or re-encode, or its own.
        if series_picture(print, &refs) {
            art_same(&mut plan.items[index], url, Some(print), now);
            plan.art.same += 1;
            plan.art.same_run += 1;
        } else {
            let hash = photo.hash.clone();
            art_own(
                plan,
                index,
                url,
                hash,
                print,
                Some(tone),
                Some(photo),
                !alone,
                now,
            );
        }
    }
    // What is left for the next check: the rest after a cut, and what the tests' cap held
    // (never with a cap of 0, which means "no art" in the tests).
    let rest = cut_at.filter(|_| requested).map_or(0, |at| {
        candidates[at..]
            .iter()
            .filter(|&&i| {
                let item = &plan.items[i];
                art_due(item, now)
                    && item
                        .art_url
                        .as_deref()
                        .is_none_or(|url| down(&hosts, url) != Some(false))
            })
            .count()
    });
    plan.art.more = rest + blipped + if capped { held } else { 0 };
    // An entry whose pictures are all the series picture: only new items from now on (§1.4).
    if plan.art.backlog
        && plan.art.same_run >= ART_BACKLOG_SAME
        && !plan
            .items
            .iter()
            .any(|i| i.art_state.as_deref() == Some("own"))
    {
        plan.art.backlog = false;
        plan.art.backlog_at = Some(stamp(now));
        // The older items it held aren't candidates any more.
        plan.art.more = 0;
    }
    Ok(())
}

// ------------------------------------------------------------------------------------------------
// The closing write
// ------------------------------------------------------------------------------------------------

/// Writes what a check found, in one transaction that first checks the entry still exists (a
/// delete mid-check drops the result). A failure keeps the last good list (§2.4), but the items it
/// asked about are stamped and their flags cleared (QA 1c #1).
#[allow(clippy::too_many_arguments)]
async fn finish(
    state: &AppState,
    entry_id: i64,
    now: DateTime<Utc>,
    old: &Listing,
    mut plan: Plan,
    result: Result<CoverResult, CheckError>,
    recheck_only: bool,
    requests: u32,
) -> Result<Option<CheckReport>, sqlx::Error> {
    let mut report = CheckReport {
        entry_id,
        new_items: plan.new_items,
        requests,
        art_own: plan.art.own,
        art_same: plan.art.same,
        art_more: plan.art.more,
        ..Default::default()
    };
    let (cover, failure) = match result {
        Ok(cover) => (cover.0, None),
        Err(err) => (None, Some(err)),
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
    let prune = |stored: bool| async move {
        if stored {
            crate::photos::MUSIC_COVERS
                .prune(&state.db, &state.music_cover_dir)
                .await;
        }
    };
    // Episode art (design 27 §1.3): the same rule in its own store. A picture copied from
    // another item must still be on disk (that item's entry may have been deleted and pruned).
    let art_lock = if plan.art.new_files.is_empty() && plan.art.copied.is_empty() {
        None
    } else {
        Some(crate::photos::MUSIC_ART.lock().await)
    };
    let mut art_lost: HashSet<String> = HashSet::new();
    for photo in &plan.art.new_files {
        if let Err(err) = crate::photos::store(&state.music_art_dir, photo).await {
            tracing::warn!(%err, "couldn't store an episode picture");
            art_lost.insert(photo.hash.clone());
        }
    }
    for hash in &plan.art.copied {
        let there = match crate::photos::path_for(&state.music_art_dir, hash) {
            Some(path) => tokio::fs::try_exists(&path).await.unwrap_or(false),
            None => false,
        };
        if !there {
            art_lost.insert(hash.clone());
        }
    }
    for item in plan.items.iter_mut() {
        if item.art_hash.as_ref().is_some_and(|h| art_lost.contains(h)) {
            art_untried(item);
        }
    }
    let stored_art = !plan.art.new_files.is_empty();
    let prune_art = |changed: bool| async move {
        if changed {
            crate::photos::MUSIC_ART
                .prune(&state.db, &state.music_art_dir)
                .await;
        }
    };

    let initial = {
        let mut conn = state.db.acquire().await?;
        load_items(&mut conn, entry_id).await?
    };
    let art_hashes = |items: &[Item]| -> HashSet<String> {
        items.iter().filter_map(|i| i.art_hash.clone()).collect()
    };
    let art_changed = stored_art || art_hashes(&initial) != art_hashes(&plan.items);
    let mut tx = state.db.begin_with("BEGIN IMMEDIATE").await?;
    let exists: bool =
        sqlx::query_scalar("SELECT EXISTS(SELECT 1 FROM music_entries WHERE id = ?)")
            .bind(entry_id)
            .fetch_one(&mut *tx)
            .await?;
    if !exists {
        tx.rollback().await?;
        // A cover stored for it is nobody's now (QA 1c #11).
        drop(cover_lock);
        drop(art_lock);
        prune(cover_hash.is_some()).await;
        prune_art(stored_art).await;
        return Ok(None);
    }
    // A Spotify entry in context mode has no items and is listed (design 26c §3.3).
    let context = plan.spotify.mode.as_deref() == Some("context");
    let built = listing_for(
        entry_id,
        &plan.items,
        plan.keep_end.as_deref(),
        plan.spotify.mode.as_deref(),
        plan.title.as_deref(),
        cover_url.as_deref(),
    );
    let has_ok = plan.items.iter().any(|i| i.state == "ok");
    let untried = plan
        .items
        .iter()
        .any(|i| i.state == "pending" && i.attempts == 0);
    // A first fill with no playable item is no list, unless it is still going (the budget). A
    // listed entry whose every item was withdrawn is listed so: the phones get `url: null`
    // (hard rule 5 over §2.4, QA 1c #3).
    let failure = match failure {
        None if !recheck_only && !has_ok && !untried && !context && old.version.is_none() => {
            Some(CheckError::NoItems)
        }
        other => other,
    };
    let listing_part = !recheck_only;
    let mut version = old.version.clone();
    let mut listed = (old.item_count, old.bytes, old.cut);
    // Never listed: a failed fill keeps its stubs and their attempts, so a retry continues (QA #2).
    let write_items = match &failure {
        Some(_) => old.version.is_none() && plan.items != initial,
        None => plan.items != initial,
    };
    match &failure {
        Some(err) => report.error = Some(err.code()),
        None => {
            if (has_ok || context || old.version.is_some())
                && let Some(built) = &built
            {
                version = Some(built.version.clone());
                listed = (built.count as i64, built.json.len() as i64, built.cut);
            }
        }
    }
    let now_text = stamp(now);
    if write_items {
        // A phone's flag that came in during the check is kept (QA 1c #6).
        let late_flags: Vec<(String, Option<String>)> = sqlx::query_as(
            "SELECT key, reported_at FROM music_items \
             WHERE entry_id = ? AND recheck = 1 AND reported_at > ?",
        )
        .bind(entry_id)
        .bind(&now_text)
        .fetch_all(&mut *tx)
        .await?;
        sqlx::query("DELETE FROM music_items WHERE entry_id = ?")
            .bind(entry_id)
            .execute(&mut *tx)
            .await?;
        for (seq, item) in plan.items.iter().enumerate() {
            sqlx::query(&format!(
                "INSERT INTO music_items (entry_id, seq, {ITEM_COLUMNS}) VALUES ({})",
                vec!["?"; ITEM_COLUMN_COUNT + 2].join(", ")
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
            .bind(&item.art_hash)
            .bind(&item.art_from)
            .bind(&item.art_state)
            .bind(item.art_print)
            .bind(item.art_tone)
            .bind(&item.art_at)
            .bind(&item.art_fetched_at)
            .bind(item.art_fails)
            .execute(&mut *tx)
            .await?;
        }
        for (key, reported_at) in late_flags {
            sqlx::query(
                "UPDATE music_items SET recheck = 1, reported_at = ? WHERE entry_id = ? AND key = ?",
            )
            .bind(reported_at)
            .bind(entry_id)
            .bind(key)
            .execute(&mut *tx)
            .await?;
        }
    } else {
        // The rows stay the last good list; what was asked about is stamped, its flag cleared -
        // unless a phone flagged it again during the check.
        for key in &plan.rechecked {
            sqlx::query(
                "UPDATE music_items SET recheck = 0, rechecked_at = ? \
                 WHERE entry_id = ? AND key = ? AND (reported_at IS NULL OR reported_at <= ?)",
            )
            .bind(&now_text)
            .bind(entry_id)
            .bind(key)
            .bind(&now_text)
            .execute(&mut *tx)
            .await?;
        }
    }
    // `failures`: zeroed by a listing, restored for a recheck only, kept (+1 from the start stamp)
    // after a failure.
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
           error_at = ?, failing_since = ?, failures = ?, requests = ?, cover_print = ?, \
           cover_alt_url = ?, cover_alt_print = ?, cover_alt_at = ?, old_prints = ?, \
           art_refs = ?, art_backlog = ?, art_same_run = ?, art_backlog_at = ?, art_refresh = ?, \
           art_more = ?, spotify_mode = ?, spotify_snapshot = ?, spotify_items_at = ?, spotify_reader = ?, \
           spotify_skipped = ? \
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
    .bind(plan.art.cover_print)
    .bind(&plan.art.alt_url)
    .bind(plan.art.alt_print)
    .bind(&plan.art.alt_at)
    .bind(serde_json::to_string(&plan.art.old_prints).ok())
    .bind(&plan.art.refs_key)
    .bind(plan.art.backlog)
    .bind(plan.art.same_run)
    .bind(&plan.art.backlog_at)
    // The refresh's new URLs are written only with the items.
    .bind(if keep_fields {
        old.art_refresh
    } else {
        plan.art.refresh
    })
    // A failed check's pass didn't run: nothing to come back for at once (it backs off).
    .bind(if recheck_only {
        old.art_more
    } else {
        plan.art.more as i64
    })
    // A failed check keeps the last good list's Spotify state (design 26c §3.4).
    .bind(if keep_fields {
        old.spotify_mode.clone()
    } else {
        plan.spotify.mode.clone()
    })
    .bind(if keep_fields {
        old.spotify_snapshot.clone()
    } else {
        plan.spotify.snapshot.clone()
    })
    .bind(if keep_fields {
        old.spotify_items_at.clone()
    } else {
        plan.spotify.items_at.clone()
    })
    .bind(if keep_fields {
        old.spotify_reader
    } else {
        plan.spotify.reader
    })
    .bind(if keep_fields {
        old.spotify_skipped
    } else {
        plan.spotify.skipped
    })
    .bind(entry_id)
    .execute(&mut *tx)
    .await?;
    #[cfg(test)]
    state.music_sweep.at_commit().await;
    tx.commit().await?;
    drop(cover_lock);
    drop(art_lock);
    prune(cover_hash.is_some()).await;
    prune_art(art_changed).await;
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
        let wait = next_wait(&state, state.music_sweep.now()).await.max(if ok {
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

/// How long to sleep: until the next entry is due, at most the setting's interval.
async fn next_wait(state: &AppState, now: DateTime<Utc>) -> Duration {
    let setting = sweep_hours(&state.db).await.unwrap_or(DEFAULT_SWEEP_HOURS);
    let longest = hours(setting);
    let next = match schedule(&state.db, now).await {
        Ok(all) => all.iter().map(|s| s.at).min(),
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
    let mut nudges: HashSet<i64> = HashSet::new();
    let mut last_flush = tokio::time::Instant::now();
    let mut last_check: Option<tokio::time::Instant> = None;
    let mut checks: HashMap<i64, u32> = HashMap::new();
    let mut ok = true;
    loop {
        let now = state.music_sweep.now();
        let due = match due(&state.db, now).await {
            Ok(due) => due,
            Err(err) => {
                tracing::error!(%err, "music sweep: can't read what is due");
                ok = false;
                break;
            }
        };
        let Some(next) = due
            .into_iter()
            .find(|s| checks.get(&s.entry_id).copied().unwrap_or(0) < CHECKS_PER_PASS)
        else {
            break;
        };
        if let Some(at) = last_check {
            tokio::time::sleep_until(at + state.music_sweep.spacing()).await;
        }
        last_check = Some(tokio::time::Instant::now());
        let count = checks.entry(next.entry_id).or_default();
        *count += 1;
        // A picture fill comes back at once by design (2026-10-19): no warning for it.
        if *count == CHECKS_PER_PASS && next.why != Why::Art {
            tracing::warn!(
                entry_id = next.entry_id,
                "music sweep: an entry stayed due through {CHECKS_PER_PASS} checks in one pass"
            );
        }
        match check_entry(state, next.entry_id, state.music_sweep.now()).await {
            Ok(Some(report)) => {
                tracing::info!(
                    "music sweep: entry {} \"{}\": {} request{}, {} new{}{}",
                    report.entry_id,
                    report.name,
                    report.requests,
                    if report.requests == 1 { "" } else { "s" },
                    report.new_items,
                    report.art_line(),
                    report
                        .error
                        .as_deref()
                        .map(|e| format!(", failed: {e}"))
                        .unwrap_or_default(),
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
    nudge(state, &mut nudges);
    ok
}

fn nudge(state: &AppState, phones: &mut HashSet<i64>) {
    for phone in phones.drain() {
        let _ = state.command_notify.send(phone);
    }
}
