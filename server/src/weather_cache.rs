//! The weather's outside requests and caches (design 75 §3.3): MET's forecast per grid cell in `weather_cells`, its
//! nowcast in memory. MET is the only outside service that sees a cell (the place-name lookup went 2026-11-04).
//!
//! - **On demand, politely** (D7): a cell is fetched only when a phone asks and its `next_fetch_at` has passed -
//!   `max(now + (Expires − Date), now + 30 min) + 0-120 s` (MET's clock never compared with ours), conditionally
//!   (`If-Modified-Since` = the stored `Last-Modified`, verbatim), one forecast fetch at a time server-wide (the row
//!   read again under the lock). A phone waits at most [FORECAST_WAIT]; the fetch carries on in its own task.
//! - **MET's terms**: its own User-Agent (`ForkConfig::met_user_agent`, D8), a 203 logged once a day, a 429 backs off
//!   (`Retry-After`, at least 10 min, doubling to 2 h), a 403 waits 6 h, other failures 5-60 min. The old hours stay.
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
/// A fetched forecast is never asked again before this (MET's `Expires` is ~30 min).
const FORECAST_FLOOR_MINUTES: i64 = 30;
const FORECAST_JITTER_SECONDS: u32 = 120;
const NOWCAST_FLOOR_MINUTES: i64 = 3;
const NOWCAST_JITTER_SECONDS: u32 = 20;
/// Nowcast steps from a fetch older than this are never used (radar data is stale after ~5 min).
const NOWCAST_USE_MINUTES: i64 = 10;

/// The weather's state in memory (`AppState.weather`).
pub struct Weather {
    pub forecast_asks: PhoneAsks,
    pub nowcast_asks: PhoneAsks,
    pub forecast_wait: StdDuration,
    pub nowcast_wait: StdDuration,
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
fn expires_in(response: &SourceResponse) -> Option<Duration> {
    let left = http_date(response.expires.as_deref())? - http_date(response.date.as_deref())?;
    (left >= Duration::zero()).then_some(left)
}

/// `Retry-After` as seconds or an HTTP date (against MET's own `Date`, else ours).
fn retry_after(response: &SourceResponse, now: DateTime<Utc>) -> Option<Duration> {
    let text = response.retry_after.as_deref()?.trim();
    if let Ok(seconds) = text.parse::<i64>() {
        return Some(Duration::seconds(seconds.clamp(0, 86_400)));
    }
    let at = http_date(Some(text))?;
    let from = http_date(response.date.as_deref()).unwrap_or(now);
    Some((at - from).max(Duration::zero()))
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
    let task = tokio::spawn(fill_forecast(state.clone(), cell));
    let _ = tokio::time::timeout(state.weather.forecast_wait, task).await;
    read_row(&state.db, cell).await
}

/// What one fetch did to the row.
enum Fetched {
    Hours {
        json: String,
        last_modified: Option<String>,
        next: DateTime<Utc>,
    },
    NotModified {
        next: DateTime<Utc>,
    },
    Failed {
        code: &'static str,
        next: DateTime<Utc>,
    },
}

/// One forecast fetch for `cell` under the server-wide lock, when the row (read again inside it) is still due.
async fn fill_forecast(state: AppState, cell: Cell) {
    let _one = state.weather.forecast_lock.lock().await;
    let row = match read_row(&state.db, cell).await {
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
    let mut request = met_request(
        &state,
        format!(
            "{MET_BASE}/weatherapi/locationforecast/2.0/complete?{}",
            cell.query()
        ),
    );
    request.last_modified = row.last_modified.clone();
    let result = state.weather_fetch.get(request).await;
    let now = state.weather.now();
    let next_after = |response: &SourceResponse| {
        let floor = now + Duration::minutes(FORECAST_FLOOR_MINUTES);
        expires_in(response).map_or(floor, |left| (now + left).max(floor))
            + jitter(FORECAST_JITTER_SECONDS)
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
            match (!response.truncated)
                .then(|| weather::parse_forecast(&response.body).ok())
                .flatten()
            {
                Some(new) => {
                    let merged = weather::merge(&row.hours(), &new, now);
                    match serde_json::to_string(&merged) {
                        Ok(json) => Fetched::Hours {
                            json,
                            last_modified: response.last_modified.clone(),
                            next: next_after(response),
                        },
                        Err(_) => unusable(&row, now, next_after(response)),
                    }
                }
                None => unusable(&row, now, next_after(response)),
            }
        }
        Ok(response) if response.status == 304 => Fetched::NotModified {
            next: next_after(response),
        },
        Ok(response) if response.status == 429 => {
            failed(&row, "status_429", now, retry_after(response, now))
        }
        Ok(response) if response.status == 403 => failed(&row, "status_403", now, None),
        Ok(response) if response.status >= 500 => failed(&row, "status_5xx", now, None),
        Ok(_) => failed(&row, "status_other", now, None),
        Err(FetchError::Timeout) => failed(&row, "timeout", now, None),
        Err(_) => failed(&row, "network", now, None),
    };
    let written = match &fetched {
        Fetched::Hours {
            json,
            last_modified,
            next,
        } => {
            sqlx::query(
                "UPDATE weather_cells SET hours_json = ?, last_modified = ?, checked_at = ?, \
                 next_fetch_at = ?, failures = 0, last_error = NULL WHERE id = ?",
            )
            .bind(json)
            .bind(last_modified)
            .bind(stamp(now))
            .bind(stamp(*next))
            .bind(row.id)
            .execute(&state.db)
            .await
        }
        Fetched::NotModified { next } => {
            sqlx::query(
                "UPDATE weather_cells SET checked_at = ?, next_fetch_at = ?, failures = 0, \
                 last_error = NULL WHERE id = ?",
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
/// 5, 10, 20, 40, 60 min by the failures so far.
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
        _ => Duration::minutes([5, 10, 20, 40, 60][failures.min(4) as usize]),
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
