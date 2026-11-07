//! The weather's outside requests and caches (design 75 §3.3): MET's forecast per grid cell in `weather_cells`, its
//! nowcast in memory. MET is the only outside service that sees a cell (the place-name lookup went 2026-11-04).
//!
//! - **On demand, politely** (D7): a cell is fetched only when a phone asks and its `next_fetch_at` has passed -
//!   `max(now + (Expires − Date), now + the cell's floor) + 0-120 s` (MET's clock never compared with ours; the floor
//!   is MET's per-location minimum of 2026-11-05: 30 min inside the Nordic region, [Cell::is_nordic], 3 h outside it,
//!   after a 200, a 304 and a cut body alike; the 5xx retry and the failure backoff are not under it), conditionally
//!   (`If-Modified-Since` = the stored `Last-Modified`, verbatim), one forecast fetch at a time server-wide (the row
//!   read again under the lock). A phone waits at most [FORECAST_WAIT]; the fetch carries on in its own task.
//! - **MET's terms**: its own User-Agent (`ForkConfig::met_user_agent`, D8), a 203 logged once a day, a 429 backs off
//!   (`Retry-After`, at least 10 min, doubling to 2 h), a 403 waits 6 h, other failures 5-60 min (never before a
//!   `Retry-After`, two hours at most). A 500, 502, 503 or 504 is first tried again within seconds ([quick_wait], 2026-11-04: MET's edge
//!   answers a cold point 503, `Retry-After: 5`, then serves it): after its `Retry-After` when that is 0-60 s (at
//!   least 1 s), else 5 s then 15 s, at most [QUICK_TRIES] requests a fetch (a phone whose cell has hours doesn't wait for them); the server-wide lock is let go while it
//!   waits, the row's `next_fetch_at` meanwhile set to the retry so no other ask fetches the cell. A body MET cut short
//!   keeps its whole hours (`weather::parse_cut_forecast`, code `cut`) and is asked again only after its `Expires`.
//!   The old hours stay.
//! - **Logs** name a cell by its row id and a code - never coordinates, never a body.
//! - **Location Off** erases every cache ([erase], `handlers::locate::update_location_policy`, D13): the cache has
//!   no device id, so it goes whole. The age prune is `retention::prune`'s.

use std::collections::HashMap;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::time::Duration as StdDuration;

use chrono::{DateTime, Duration, NaiveDate, SecondsFormat, Utc};

use crate::AppState;
use crate::music_net::{FetchError, Kind, PhoneAsks, Reach, SourceRequest, SourceResponse};
use crate::weather::{self, Cell, Hour, NowLine, Nowcast};

pub const MET_BASE: &str = "https://api.met.no";
/// How long a phone's forecast answer waits for a fetch (D7); the phone's own call times out at 15 s.
pub const FORECAST_WAIT: StdDuration = StdDuration::from_secs(6);
/// How long a card's nowcast answer waits for a fetch (D9).
pub const NOWCAST_WAIT: StdDuration = StdDuration::from_secs(3);
/// A phone's asks a rolling hour: the forecast (it pulls about hourly) and the card's nowcast.
pub const FORECAST_ASKS_AN_HOUR: usize = 20;
pub const NOWCAST_ASKS_AN_HOUR: usize = 60;
/// A cell nobody asked for in this long goes (`retention::prune`), or within the longest location retention when
/// that is shorter (a 1-day history: D13, the cache lives no longer than the fixes it came from).
pub const CELL_HOURS: i64 = 48;
/// A fetched forecast is never asked again before this (MET's terms of 2026-11-05: its cache headers are an absolute
/// minimum, and per location at most once per 30 min in the Nordic region, once per 3 h in the rest of the world).
const FORECAST_FLOOR_NORDIC_MINUTES: i64 = 30;
const FORECAST_FLOOR_WORLD_MINUTES: i64 = 180;
const FORECAST_JITTER_SECONDS: u32 = 120;
/// Nowcast is Nordic only (MET answers 422 elsewhere) and is not named in the per-location terms; its `Expires`
/// (~5 min) is honoured and this is only the least it waits.
const NOWCAST_FLOOR_MINUTES: i64 = 3;
const NOWCAST_JITTER_SECONDS: u32 = 20;
/// Nowcast steps from a fetch older than this are never used (radar data is stale after ~5 min).
const NOWCAST_USE_MINUTES: i64 = 10;
/// At most this many requests per forecast fetch on a 500/502/503/504 ([quick_wait]).
pub const QUICK_TRIES: u32 = 3;
/// The waits before the second and third request when MET sends no usable `Retry-After`, in seconds.
const QUICK_LADDER: [i64; 2] = [5, 15];
/// A `Retry-After` longer than this ends the quick tries (the 5-60 min backoff, never before it).
const QUICK_CAP_SECONDS: i64 = 60;

/// The weather's state in memory (`AppState.weather`).
pub struct Weather {
    pub forecast_asks: PhoneAsks,
    pub nowcast_asks: PhoneAsks,
    pub forecast_wait: StdDuration,
    pub nowcast_wait: StdDuration,
    /// A second of the quick retry's waits ([quick_wait]); the tests shorten it.
    pub quick_second: StdDuration,
    forecast_lock: tokio::sync::Mutex<()>,
    nowcast_lock: tokio::sync::Mutex<()>,
    nowcasts: std::sync::Mutex<HashMap<Cell, NowEntry>>,
    deprecation_warned: std::sync::Mutex<Option<NaiveDate>>,
    contact_warned: AtomicBool,
    /// Bumped by [erase] (Location Off, code QA L1): a nowcast fill that started before it never writes back what it
    /// found.
    erased: AtomicU64,
    /// The tests' clock; `None` = the real one.
    clock: std::sync::Mutex<Option<DateTime<Utc>>>,
}

/// A cell's nowcast in memory.
#[derive(Clone, Debug)]
struct NowEntry {
    nowcast: Option<Nowcast>,
    fetched_at: Option<DateTime<Utc>>,
    next_fetch_at: DateTime<Utc>,
    /// An error status (outside the Nordic area): no ask for this cell until then.
    outside_until: Option<DateTime<Utc>>,
}

impl Default for Weather {
    fn default() -> Self {
        let hour = StdDuration::from_secs(3600);
        Weather {
            forecast_asks: PhoneAsks::new(FORECAST_ASKS_AN_HOUR, hour),
            nowcast_asks: PhoneAsks::new(NOWCAST_ASKS_AN_HOUR, hour),
            forecast_wait: FORECAST_WAIT,
            nowcast_wait: NOWCAST_WAIT,
            quick_second: StdDuration::from_secs(1),
            forecast_lock: Default::default(),
            nowcast_lock: Default::default(),
            nowcasts: Default::default(),
            deprecation_warned: Default::default(),
            contact_warned: AtomicBool::new(false),
            erased: AtomicU64::new(0),
            clock: Default::default(),
        }
    }
}

impl Weather {
    pub fn now(&self) -> DateTime<Utc> {
        self.clock
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .unwrap_or_else(Utc::now)
    }

    /// The tests' clock.
    #[cfg(test)]
    pub fn set_now(&self, now: DateTime<Utc>) {
        *self.clock.lock().unwrap() = Some(now);
    }
}

fn stamp(t: DateTime<Utc>) -> String {
    t.to_rfc3339_opts(SecondsFormat::Secs, true)
}

/// [stamp] rounded up to the whole second: a wait stamped as `next_fetch_at` never ends before it is over.
fn stamp_up(t: DateTime<Utc>) -> String {
    stamp(t + Duration::nanoseconds(999_999_999))
}

fn parse_stamp(text: Option<&str>) -> Option<DateTime<Utc>> {
    DateTime::parse_from_rfc3339(text?)
        .ok()
        .map(|t| t.with_timezone(&Utc))
}

/// 0..`max` seconds, at random.
fn jitter(max: u32) -> Duration {
    use ring::rand::SecureRandom;
    let mut bytes = [0u8; 4];
    let _ = ring::rand::SystemRandom::new().fill(&mut bytes);
    Duration::seconds(i64::from(u32::from_le_bytes(bytes) % (max + 1)))
}

fn http_date(text: Option<&str>) -> Option<DateTime<Utc>> {
    DateTime::parse_from_rfc2822(text?.trim())
        .ok()
        .map(|t| t.with_timezone(&Utc))
}

/// `Expires − Date` as MET sent them (D7, server QA L2): never its clock against ours.
/// The least a cell's forecast waits after a successful fetch (a 200, 203, 304 or cut body): 30 min in the Nordic
/// region ([Cell::is_nordic]), 3 h elsewhere. A longer `Expires` still wins; the 5xx quick retry and the failure
/// backoff do not use it (they retry a failed request, not a cached answer).
fn forecast_floor(cell: Cell) -> Duration {
    Duration::minutes(if cell.is_nordic() {
        FORECAST_FLOOR_NORDIC_MINUTES
    } else {
        FORECAST_FLOOR_WORLD_MINUTES
    })
}

/// `max(now + (Expires − Date), now + the cell's floor)`, before the jitter.
fn forecast_next(cell: Cell, now: DateTime<Utc>, expires_in: Option<Duration>) -> DateTime<Utc> {
    let floor = now + forecast_floor(cell);
    expires_in.map_or(floor, |left| (now + left).max(floor))
}

fn expires_in(response: &SourceResponse) -> Option<Duration> {
    let left = http_date(response.expires.as_deref())? - http_date(response.date.as_deref())?;
    (left >= Duration::zero()).then_some(left)
}

/// `Retry-After` in seconds: delta-seconds, or an HTTP date against MET's own `Date` (else ours). None when it is
/// missing, not one of the two, or negative (a date in the past).
fn retry_after_seconds(response: &SourceResponse, now: DateTime<Utc>) -> Option<i64> {
    let text = response.retry_after.as_deref()?.trim();
    let seconds = match text.parse::<i64>() {
        Ok(seconds) => seconds,
        // Digits too many for a number are a very long wait, not garbage.
        Err(_) if text.bytes().all(|b| b.is_ascii_digit()) && !text.is_empty() => i64::MAX,
        Err(_) => {
            let at = http_date(Some(text))?;
            (at - http_date(response.date.as_deref()).unwrap_or(now)).num_seconds()
        }
    };
    (seconds >= 0).then_some(seconds)
}

/// `Retry-After` as a wait, at most a day.
fn retry_after(response: &SourceResponse, now: DateTime<Utc>) -> Option<Duration> {
    retry_after_seconds(response, now).map(|seconds| Duration::seconds(seconds.min(86_400)))
}

/// The wait before the next request of a forecast fetch that has made `tried` requests, when its answer is worth one
/// more within seconds: a 500, 502, 503 or 504 and fewer than [QUICK_TRIES] requests. MET's `Retry-After` when it is
/// 0-60 s (at least 1 s); a longer one ends the quick tries; none, or one we can't read, the ladder (5 s, then 15 s).
fn quick_wait(response: &SourceResponse, now: DateTime<Utc>, tried: u32) -> Option<Duration> {
    if !matches!(response.status, 500 | 502 | 503 | 504) || tried >= QUICK_TRIES {
        return None;
    }
    match retry_after_seconds(response, now) {
        Some(seconds) if seconds > QUICK_CAP_SECONDS => None,
        Some(seconds) => Some(Duration::seconds(seconds.max(1))),
        None => {
            let step = usize::try_from(tried.saturating_sub(1)).unwrap_or(usize::MAX);
            Some(Duration::seconds(
                QUICK_LADDER[step.min(QUICK_LADDER.len() - 1)],
            ))
        }
    }
}

fn met_request(state: &AppState, url: String) -> SourceRequest {
    let mut request = SourceRequest::new(url, Kind::Weather, Reach::Public);
    request
        .headers
        .push((reqwest::header::USER_AGENT, state.config.met_user_agent()));
    request
}

// ---- the forecast -------------------------------------------------------------------------------

/// A cell's row.
#[derive(sqlx::FromRow, Clone, Debug)]
pub struct CellRow {
    pub id: i64,
    pub hours_json: Option<String>,
    pub last_modified: Option<String>,
    pub checked_at: Option<String>,
    pub next_fetch_at: Option<String>,
    pub failures: i64,
}

impl CellRow {
    /// The stored hours; none before the first 200 (or when unreadable).
    pub fn hours(&self) -> Vec<Hour> {
        self.hours_json
            .as_deref()
            .and_then(|json| serde_json::from_str(json).ok())
            .unwrap_or_default()
    }

    pub fn checked_at(&self) -> Option<DateTime<Utc>> {
        parse_stamp(self.checked_at.as_deref())
    }

    fn due(&self, now: DateTime<Utc>) -> bool {
        parse_stamp(self.next_fetch_at.as_deref()).is_none_or(|at| now >= at)
    }
}

/// The cell's row; none when a Location Off erase took it meanwhile.
async fn read_row(db: &sqlx::SqlitePool, cell: Cell) -> Result<Option<CellRow>, sqlx::Error> {
    sqlx::query_as(
        "SELECT id, hours_json, last_modified, checked_at, next_fetch_at, failures \
         FROM weather_cells WHERE lat_i = ? AND lon_i = ?",
    )
    .bind(cell.lat_i)
    .bind(cell.lon_i)
    .fetch_optional(db)
    .await
}

/// The cell's forecast for a phone's ask: `asked_at` moved, then the row - after a fetch when it is due, awaited at
/// most `forecast_wait` (the fetch carries on past it and fills the row for the next ask). `None` when an erase took
/// the row while the ask ran (code QA L1: the answer is `unavailable`, never a 500).
pub async fn forecast(state: &AppState, cell: Cell) -> Result<Option<CellRow>, sqlx::Error> {
    let now = state.weather.now();
    sqlx::query(
        "INSERT INTO weather_cells (lat_i, lon_i, asked_at) VALUES (?, ?, ?) \
         ON CONFLICT(lat_i, lon_i) DO UPDATE SET asked_at = excluded.asked_at",
    )
    .bind(cell.lat_i)
    .bind(cell.lon_i)
    .bind(stamp(now))
    .execute(&state.db)
    .await?;
    let Some(row) = read_row(&state.db, cell).await? else {
        return Ok(None);
    };
    if !row.due(now) {
        return Ok(Some(row));
    }
    // The fetch carries on in its own task. The ask waits for it to end (the sender dropped), for its signal that the
    // row already has hours and only a quick retry is left, or for [FORECAST_WAIT].
    let (early, settled) = tokio::sync::oneshot::channel();
    tokio::spawn(fill_forecast(state.clone(), cell, Some(early)));
    let _ = tokio::time::timeout(state.weather.forecast_wait, settled).await;
    read_row(&state.db, cell).await
}

/// What one fetch did to the row.
enum Fetched {
    Hours {
        json: String,
        last_modified: Option<String>,
        next: DateTime<Utc>,
        /// `cut`: a body MET cut short, its whole hours kept (stored as `last_error`).
        note: Option<&'static str>,
    },
    NotModified {
        next: DateTime<Utc>,
    },
    Failed {
        code: &'static str,
        next: DateTime<Utc>,
    },
}

/// One forecast fetch for `cell` under the server-wide lock, when the row (read again inside it) is still due. A 5xx
/// worth a quick retry ([quick_wait]) lets the lock go for the wait, with the row's `next_fetch_at` set to the retry
/// (no other ask fetches the cell meanwhile), and goes on only while the row still says so (an erase, or a newer fetch,
/// ends it).
async fn fill_forecast(
    state: AppState,
    cell: Cell,
    mut early: Option<tokio::sync::oneshot::Sender<()>>,
) {
    let mut one = state.weather.forecast_lock.lock().await;
    let mut row = match read_row(&state.db, cell).await {
        Ok(Some(row)) => row,
        Ok(None) => return,
        Err(err) => {
            tracing::error!(%err, "weather: couldn't read a cell before its fetch");
            return;
        }
    };
    if !row.due(state.weather.now()) {
        return;
    }
    if state.config.met_contact.is_none()
        && !state.weather.contact_warned.swap(true, Ordering::SeqCst)
    {
        tracing::warn!(
            "weather: MET_CONTACT is not set - MET's User-Agent names only this project, and MET may block every \
             install that shares it. Set MET_CONTACT to your e-mail or website (DEPLOY.md)."
        );
    }
    let mut tried = 0;
    let result = loop {
        let mut request = met_request(
            &state,
            format!(
                "{MET_BASE}/weatherapi/locationforecast/2.0/complete?{}",
                cell.query()
            ),
        );
        request.last_modified = row.last_modified.clone();
        let result = state.weather_fetch.get(request).await;
        tried += 1;
        let now = state.weather.now();
        let Some(wait) = result
            .as_ref()
            .ok()
            .and_then(|response| quick_wait(response, now, tried))
        else {
            break result;
        };
        let retry_at = stamp_up(now + wait);
        let held = sqlx::query("UPDATE weather_cells SET next_fetch_at = ? WHERE id = ?")
            .bind(&retry_at)
            .bind(row.id)
            .execute(&state.db)
            .await;
        if let Err(err) = held {
            tracing::error!(cell = row.id, %err, "weather: couldn't store a forecast fetch");
            return;
        }
        // A phone that has hours to show needn't wait for the retry.
        if !row.hours().is_empty()
            && let Some(early) = early.take()
        {
            let _ = early.send(());
        }
        drop(one);
        let seconds = u32::try_from(wait.num_seconds()).unwrap_or(u32::MAX);
        tokio::time::sleep(state.weather.quick_second.saturating_mul(seconds)).await;
        one = state.weather.forecast_lock.lock().await;
        row = match read_row(&state.db, cell).await {
            Ok(Some(again))
                if again.id == row.id
                    && again.next_fetch_at.as_deref() == Some(retry_at.as_str()) =>
            {
                again
            }
            // Erased, or another fetch wrote the row meanwhile.
            Ok(_) => return,
            Err(err) => {
                tracing::error!(%err, "weather: couldn't read a cell before its fetch");
                return;
            }
        };
    };
    if tried > 1
        && result
            .as_ref()
            .is_ok_and(|response| matches!(response.status, 200 | 203 | 304))
    {
        tracing::info!(
            cell = row.id,
            tried,
            "weather: MET's forecast came on a quick retry after a 5xx"
        );
    }
    let now = state.weather.now();
    let next_after = |response: &SourceResponse| {
        forecast_next(cell, now, expires_in(response)) + jitter(FORECAST_JITTER_SECONDS)
    };
    let fetched = match &result {
        Ok(response) if matches!(response.status, 200 | 203) => {
            if response.status == 203 {
                let today = now.date_naive();
                let mut warned = state
                    .weather
                    .deprecation_warned
                    .lock()
                    .unwrap_or_else(|e| e.into_inner());
                if *warned != Some(today) {
                    *warned = Some(today);
                    tracing::warn!(
                        "MET says Locationforecast 2.0 is deprecated (203) - a new version is due"
                    );
                }
            }
            // Whole, else cut short by MET (its whole hours; asked again only after its `Expires`, which
            // `next_after` keeps: MET's cache serves the same cut body until then).
            let read = (!response.truncated)
                .then(|| match weather::parse_forecast(&response.body) {
                    Ok(new) => Some((weather::merge(&row.hours(), &new, now), None)),
                    Err(_) => weather::parse_cut_forecast(&response.body)
                        .ok()
                        .map(|new| (weather::merge_cut(&row.hours(), &new, now), Some("cut"))),
                })
                .flatten();
            match read.and_then(|(merged, note)| Some((serde_json::to_string(&merged).ok()?, note)))
            {
                Some((json, note)) => Fetched::Hours {
                    json,
                    // A cut body's validator is never sent back: MET could answer 304 and keep the cut.
                    last_modified: response.last_modified.clone().filter(|_| note.is_none()),
                    next: next_after(response),
                    note,
                },
                None => {
                    // Only the length and the kind of it, never the content.
                    let (bytes, kind) = (response.body.len(), weather::body_kind(&response.body));
                    tracing::warn!(
                        cell = row.id,
                        bytes,
                        kind,
                        truncated = response.truncated,
                        "weather: MET's forecast was unreadable"
                    );
                    unusable(&row, now, next_after(response))
                }
            }
        }
        Ok(response) if response.status == 304 => Fetched::NotModified {
            next: next_after(response),
        },
        Ok(response) if response.status == 429 => {
            failed(&row, "status_429", now, retry_after(response, now))
        }
        Ok(response) if response.status == 403 => failed(&row, "status_403", now, None),
        Ok(response) if response.status >= 500 => {
            failed(&row, "status_5xx", now, retry_after(response, now))
        }
        Ok(_) => failed(&row, "status_other", now, None),
        Err(FetchError::Timeout) => failed(&row, "timeout", now, None),
        Err(_) => failed(&row, "network", now, None),
    };
    let written = match &fetched {
        Fetched::Hours {
            json,
            last_modified,
            next,
            note,
        } => {
            if let Some(code) = note {
                tracing::warn!(
                    cell = row.id,
                    code,
                    "weather: MET's forecast came cut short - its whole hours are kept"
                );
            }
            sqlx::query(
                "UPDATE weather_cells SET hours_json = ?, last_modified = ?, checked_at = ?, \
                 next_fetch_at = ?, failures = 0, last_error = ? WHERE id = ?",
            )
            .bind(json)
            .bind(last_modified)
            .bind(stamp(now))
            .bind(stamp(*next))
            .bind(note)
            .bind(row.id)
            .execute(&state.db)
            .await
        }
        Fetched::NotModified { next } => {
            sqlx::query(
                "UPDATE weather_cells SET checked_at = ?, next_fetch_at = ?, failures = 0, \
                 last_error = CASE WHEN last_error = 'cut' THEN last_error END WHERE id = ?",
            )
            .bind(stamp(now))
            .bind(stamp(*next))
            .bind(row.id)
            .execute(&state.db)
            .await
        }
        Fetched::Failed { code, next } => {
            match *code {
                "status_403" => tracing::error!(
                    cell = row.id,
                    "MET refused the request (403) - check MET_CONTACT and the User-Agent"
                ),
                code => tracing::warn!(cell = row.id, code, "weather: MET's forecast failed"),
            }
            sqlx::query(
                "UPDATE weather_cells SET next_fetch_at = ?, failures = failures + 1, \
                 last_error = ? WHERE id = ?",
            )
            .bind(stamp(*next))
            .bind(code)
            .bind(row.id)
            .execute(&state.db)
            .await
        }
    };
    if let Err(err) = written {
        tracing::error!(cell = row.id, %err, "weather: couldn't store a forecast fetch");
    }
}

/// A 200/203 we can't use (a changed shape, cut at the size cap): `unreadable` with its backoff, but never before
/// the `Expires` MET sent with it (code QA M2: MET's terms forbid repeating a request before it).
fn unusable(row: &CellRow, now: DateTime<Utc>, not_before: DateTime<Utc>) -> Fetched {
    match failed(row, "unreadable", now, None) {
        Fetched::Failed { code, next } => Fetched::Failed {
            code,
            next: next.max(not_before),
        },
        other => other,
    }
}

/// A failed fetch's backoff: a 429 `Retry-After` but at least 10 min doubling per failure to 2 h; a 403 6 h; else
/// 5, 10, 20, 40, 60 min by the failures so far, or a longer `Retry-After` (a 5xx's).
fn failed(
    row: &CellRow,
    code: &'static str,
    now: DateTime<Utc>,
    retry: Option<Duration>,
) -> Fetched {
    let failures = u32::try_from(row.failures.clamp(0, 16)).unwrap_or(0);
    let wait = match code {
        "status_429" => {
            let doubled =
                Duration::minutes(10 * 2_i64.pow(failures.min(4))).min(Duration::hours(2));
            retry.unwrap_or(Duration::zero()).max(doubled)
        }
        "status_403" => Duration::hours(6),
        // A 5xx's `Retry-After` is a floor, but one header never holds a cell longer than two hours.
        _ => Duration::minutes([5, 10, 20, 40, 60][failures.min(4) as usize])
            .max(retry.unwrap_or(Duration::zero()).min(Duration::hours(2))),
    };
    Fetched::Failed {
        code,
        next: now + wait,
    }
}

// ---- the nowcast --------------------------------------------------------------------------------

/// The card's line for `cell` at the weather clock's now (D9): from the kept steps while fresh, else after a fetch
/// awaited at most `nowcast_wait`. Silence (none) for no coverage, outside the Nordic area, or any failure.
pub async fn nowcast(state: &AppState, cell: Cell) -> Option<NowLine> {
    let due = {
        let now = state.weather.now();
        let map = state
            .weather
            .nowcasts
            .lock()
            .unwrap_or_else(|e| e.into_inner());
        map.get(&cell)
            .is_none_or(|entry| now >= entry.next_fetch_at)
    };
    if due {
        let task = tokio::spawn(fill_nowcast(state.clone(), cell));
        let _ = tokio::time::timeout(state.weather.nowcast_wait, task).await;
    }
    let now = state.weather.now();
    let map = state
        .weather
        .nowcasts
        .lock()
        .unwrap_or_else(|e| e.into_inner());
    let entry = map.get(&cell)?;
    if entry.outside_until.is_some_and(|until| now < until) {
        return None;
    }
    let fresh = entry
        .fetched_at
        .is_some_and(|at| now - at <= Duration::minutes(NOWCAST_USE_MINUTES));
    weather::nowcast_line(entry.nowcast.as_ref().filter(|_| fresh)?, now)
}

async fn fill_nowcast(state: AppState, cell: Cell) {
    let _one = state.weather.nowcast_lock.lock().await;
    let generation = state.weather.erased.load(Ordering::SeqCst);
    let old = {
        let now = state.weather.now();
        let map = state
            .weather
            .nowcasts
            .lock()
            .unwrap_or_else(|e| e.into_inner());
        let old = map.get(&cell).cloned();
        if old.as_ref().is_some_and(|entry| now < entry.next_fetch_at) {
            return;
        }
        old
    };
    let request = met_request(
        &state,
        format!(
            "{MET_BASE}/weatherapi/nowcast/2.0/complete?{}",
            cell.query()
        ),
    );
    let result = state.weather_fetch.get(request).await;
    let now = state.weather.now();
    let keep = |next: DateTime<Utc>, outside_until: Option<DateTime<Utc>>| NowEntry {
        nowcast: old.as_ref().and_then(|e| e.nowcast.clone()),
        fetched_at: old.as_ref().and_then(|e| e.fetched_at),
        next_fetch_at: next,
        outside_until,
    };
    let entry = match &result {
        Ok(response) if matches!(response.status, 200 | 203) => {
            match (!response.truncated)
                .then(|| weather::parse_nowcast(&response.body))
                .flatten()
            {
                Some(nowcast) => {
                    let floor = now + Duration::minutes(NOWCAST_FLOOR_MINUTES);
                    NowEntry {
                        nowcast: Some(nowcast),
                        fetched_at: Some(now),
                        next_fetch_at: expires_in(response)
                            .map_or(floor, |left| (now + left).max(floor))
                            + jitter(NOWCAST_JITTER_SECONDS),
                        outside_until: None,
                    }
                }
                None => {
                    // Never again before the `Expires` it came with (code QA M2).
                    tracing::warn!("weather: MET's nowcast was unreadable");
                    let floor = now + Duration::minutes(2);
                    keep(
                        expires_in(response).map_or(floor, |left| (now + left).max(floor)),
                        None,
                    )
                }
            }
        }
        Ok(response) if response.status == 429 => {
            tracing::warn!("weather: MET throttled the nowcast (429)");
            let wait = retry_after(response, now)
                .unwrap_or(Duration::zero())
                .max(Duration::minutes(10));
            keep(now + wait, None)
        }
        Ok(response) if response.status == 403 => {
            tracing::error!("MET refused the nowcast (403) - check MET_CONTACT and the User-Agent");
            keep(now + Duration::hours(6), None)
        }
        Ok(response) if (400..500).contains(&response.status) => {
            // Outside the Nordic area (MET answers 422): silence for a day, no ask per card open.
            let until = now + Duration::hours(24);
            keep(until, Some(until))
        }
        Ok(_) | Err(_) => {
            tracing::warn!("weather: MET's nowcast failed");
            keep(now + Duration::minutes(2), None)
        }
    };
    let mut map = state
        .weather
        .nowcasts
        .lock()
        .unwrap_or_else(|e| e.into_inner());
    // An erase while the fetch ran: what it found isn't kept (code QA L1).
    if state.weather.erased.load(Ordering::SeqCst) != generation {
        return;
    }
    map.insert(cell, entry);
    let hour_ago = now - Duration::hours(1);
    map.retain(|_, e| e.next_fetch_at > hour_ago || e.fetched_at.is_some_and(|at| at > hour_ago));
}

// ---- erase --------------------------------------------------------------------------------------

/// A phone's Location turned Off (D13): every weather cache goes - the cells and the nowcasts; a nowcast fill in
/// flight writes nothing back (code QA L1).
pub async fn erase(state: &AppState) -> Result<(), sqlx::Error> {
    state.weather.erased.fetch_add(1, Ordering::SeqCst);
    sqlx::query("DELETE FROM weather_cells")
        .execute(&state.db)
        .await?;
    state
        .weather
        .nowcasts
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .clear();
    Ok(())
}

#[cfg(test)]
impl Weather {
    /// The nowcast map's cells (the tests).
    pub fn nowcast_cells(&self) -> usize {
        self.nowcasts.lock().unwrap().len()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn answer(status: u16, retry_after: Option<&str>) -> SourceResponse {
        SourceResponse {
            status,
            retry_after: retry_after.map(str::to_string),
            date: Some("Wed, 04 Nov 2026 20:00:00 GMT".to_string()),
            ..Default::default()
        }
    }

    #[test]
    fn the_floor_is_30_minutes_in_the_nordic_region_and_3_hours_outside() {
        let cell = |lat, lon| Cell::of(lat, lon).unwrap();
        let (oslo, cupertino) = (cell(59.9412, 10.7598), cell(37.42, -122.08));
        assert!(oslo.is_nordic() && !cupertino.is_nordic());
        let t = now();
        assert_eq!(forecast_next(oslo, t, None), t + Duration::minutes(30));
        assert_eq!(
            forecast_next(oslo, t, Some(Duration::minutes(5))),
            t + Duration::minutes(30)
        );
        assert_eq!(forecast_next(cupertino, t, None), t + Duration::hours(3));
        assert_eq!(
            forecast_next(cupertino, t, Some(Duration::minutes(30))),
            t + Duration::hours(3)
        );
        // A longer `Expires` wins in both.
        assert_eq!(
            forecast_next(oslo, t, Some(Duration::hours(2))),
            t + Duration::hours(2)
        );
        assert_eq!(
            forecast_next(cupertino, t, Some(Duration::hours(5))),
            t + Duration::hours(5)
        );
    }

    /// The box's edges are inclusive, to the cell: its lowest and highest cells are Nordic, one step beyond is not.
    #[test]
    fn the_nordic_box_edges() {
        let nordic = |lat, lon| Cell::of(lat, lon).unwrap().is_nordic();
        // Corners and one step out on each side (0.02° latitude, 0.04° longitude).
        assert!(nordic(54.5, -25.0) && nordic(81.0, 32.0));
        assert!(nordic(54.5, 32.0) && nordic(81.0, -25.0));
        assert!(!nordic(54.48, 10.0) && !nordic(81.02, 10.0));
        assert!(!nordic(60.0, -25.04) && !nordic(60.0, 32.04));
        // Places in the countries: Longyearbyen, Reykjavik, Gedser, Vardo, Helsinki, Stockholm, Copenhagen.
        for (lat, lon) in [
            (78.22, 15.65),
            (64.15, -21.94),
            (54.57, 11.93),
            (70.37, 31.1),
            (60.17, 24.94),
            (59.33, 18.07),
            (55.68, 12.57),
        ] {
            assert!(nordic(lat, lon), "{lat} {lon}");
        }
        // Not: Berlin, London, Madrid, the dateline, the far south.
        for (lat, lon) in [
            (52.52, 13.4),
            (51.5, -0.12),
            (40.4, -3.7),
            (60.0, 179.9),
            (-33.9, 18.4),
        ] {
            assert!(!nordic(lat, lon), "{lat} {lon}");
        }
    }

    fn now() -> DateTime<Utc> {
        DateTime::parse_from_rfc3339("2026-11-04T20:00:03Z")
            .unwrap()
            .with_timezone(&Utc)
    }

    /// `Retry-After` as seconds or a date (against MET's `Date`, not our clock), 0-60 s at least 1 s; a longer one ends
    /// the quick tries; one we can't read or a negative one is the ladder's.
    #[test]
    fn the_quick_wait_reads_retry_after() {
        let wait = |value: &str| quick_wait(&answer(503, Some(value)), now(), 1);
        assert_eq!(wait("5"), Some(Duration::seconds(5)));
        assert_eq!(wait(" 12 "), Some(Duration::seconds(12)));
        assert_eq!(wait("0"), Some(Duration::seconds(1)));
        assert_eq!(wait("60"), Some(Duration::seconds(60)));
        assert_eq!(wait("61"), None, "longer than the cap: the old backoff");
        assert_eq!(wait("86400"), None);
        assert_eq!(wait("99999999999999999999"), None, "too many digits: long");
        assert_eq!(
            wait("Wed, 04 Nov 2026 20:00:20 GMT"),
            Some(Duration::seconds(20))
        );
        assert_eq!(wait("Wed, 04 Nov 2026 20:05:00 GMT"), None);
        for garbage in ["-5", "soon", "5.5", "", "Wed, 04 Nov 2026 19:59:00 GMT"] {
            assert_eq!(wait(garbage), Some(Duration::seconds(5)), "{garbage:?}");
        }
    }

    /// No `Retry-After`: 5 s, then 15 s; at most [QUICK_TRIES] requests; only 500, 502, 503 and 504.
    #[test]
    fn the_quick_ladder_and_its_end() {
        let none = answer(503, None);
        assert_eq!(quick_wait(&none, now(), 1), Some(Duration::seconds(5)));
        assert_eq!(quick_wait(&none, now(), 2), Some(Duration::seconds(15)));
        assert_eq!(quick_wait(&none, now(), QUICK_TRIES), None);
        assert_eq!(
            quick_wait(&answer(503, Some("5")), now(), QUICK_TRIES),
            None
        );
        for status in [500, 502, 504] {
            assert!(quick_wait(&answer(status, None), now(), 1).is_some());
        }
        for status in [200, 304, 403, 429, 501, 505] {
            assert_eq!(
                quick_wait(&answer(status, None), now(), 1),
                None,
                "{status}"
            );
        }
        // The old backoff, at least a 5xx's longer `Retry-After` (two hours at most).
        let row = CellRow {
            id: 1,
            hours_json: None,
            last_modified: None,
            checked_at: None,
            next_fetch_at: None,
            failures: 0,
        };
        for (retry, wait) in [
            (None, 5),
            (Some(120), 5),
            (Some(600), 10),
            (Some(86_400), 120),
        ] {
            let Fetched::Failed { next, .. } =
                failed(&row, "status_5xx", now(), retry.map(Duration::seconds))
            else {
                panic!()
            };
            assert_eq!(next, now() + Duration::minutes(wait), "{retry:?}");
        }
    }
}
