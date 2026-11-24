//! `POST /api/devices/wifi-lookup` (design 125 A4): the BSSIDs a phone heard, answered from the cache or Apple.
//!
//! - **Request**: `{"bssids": [...]}` (4 KB at most), checked by hand: only that key, 1..40 entries, each lowercase
//!   `xx:xx:xx:xx:xx:xx`, unicast with the locally administered bit clear (never all zeros); duplicates dropped. One
//!   bad entry is a 400; no input is ever echoed.
//! - **Answers**: 200 `{"aps": [{bssid, lat, lng, acc_m, fresh_months_left}], "unknown": [bssid],
//!   "retry_after_s"?}` - only asked BSSIDs (code QA M3): each one the cache holds a position for, with the whole
//!   calendar months (UTC) its row stays fresh (0-3; 0 = a stale row served while Apple can't be asked - never the
//!   month Apple answered), and each one Apple doesn't know (a negative, until the month ends: M2); `retry_after_s`
//!   when Apple was needed for some of them and couldn't be asked (M1); 409 `off`; 429 `limit` (this phone's 30 an
//!   hour) or `busy` (a third lookup at once, or the Apple call not free within the gate's wait) when the cache
//!   answered none of them; 503 `unavailable` with `retry_after_s` when the cache answered none and Apple can't be
//!   asked (paused, over the server-wide limit, or this call failed); 400 a bad body.
//! - **What a token holder still learns** (A6): whether a BSSID it names is in the cache, and roughly how fresh -
//!   never when it was asked or by which phone. The surrounding APs Apple sends are cached, never answered.
//! - **Apple**: asked only for BSSIDs the cache lacks or fetched three months ago or more, all in one call with 100
//!   surrounding (8 while GrapheneOS's throttle holds); one call at a time, the cache read again once the call is ours
//!   (a lookup that waited may find its BSSIDs fetched); 60 an hour and 10 in any 10 minutes.
//! - **Logs** (L9): each lookup at debug, counts only; an hourly line of the hour's counts at info (the sweep).
//! - The request's form, the headers and the URL are GrapheneOS's (MIT, its notice in [codec]).
//! - **Pauses** (L5): a transient failure (timeout, connect, proxy) pauses 5 minutes the first time, then 1 h doubling
//!   to 24 h; a non-200 or an answer that doesn't read goes to 1 h at once; a 200 resets. Each step logs one line.
//! - **Cells** (design 125b C5): a body is either `{"bssids"}` or `{"cells": [...]}` (1..12, [cells::Cell::parse]'s
//!   form), never both. **No cache oracle**: a cells answer says nothing about the cache - 200 `{"cells": [{cell, lat,
//!   lng, acc_m}], "unknown": [cell]}` with no age; 503 `unavailable` with `retry_after_s` for every cells request while
//!   Apple can't be asked (paused, over the server-wide limit) and when an uncached cell's call fails; and a 200 or 503
//!   leaves no sooner than [WifiLookup::cell_answer_after] after the request arrived (the Apple call's whole deadline),
//!   so a cache hit looks like a round trip to Apple. Two spellings of one cell count once, each asked string echoed.
//!   A brake on a walk through the cache: [CELLS_A_DAY] cells per phone a UTC day (memory only), past it 429 `limit`
//!   with `retry_after_s` to midnight. Its own Apple call ([cells]), its own throttle (10 towers -> 10 s at 4).

use std::time::Duration;

use axum::Extension;
use axum::body::Bytes;
use axum::extract::State;
use axum::http::StatusCode;
use axum::response::{IntoResponse, Response};
use chrono::Datelike;
use serde::Serialize;
use serde_json::{Value, json};
use tokio::time::Instant;

use super::cache::{self, Cached};
use super::cells::{self, Cell};
use super::codec::{self, Position};
use super::{NAME, Proxy, WifiLookup};
use crate::AppState;
use crate::integration_health::Class;
use crate::music_net::{FetchError, Kind, Reach, SourceRequest};
use crate::security::AuthedDevice;

pub const BODY_LIMIT: usize = 4096;
/// Design 125b C5: the cells a phone may name in a UTC day - an abuse brake, never reached by a road trip.
pub const CELLS_A_DAY: usize = 1000;
/// Design 125b C5: a cells answer's fixed time without a proxy - the Apple call's whole direct deadline
/// (`music_net::Kind::AppleWps`); through a proxy `music_net::PROXIED`.
pub const CELL_ANSWER_DIRECT: Duration = Duration::from_secs(5);
pub const MAX_BSSIDS: usize = 40;
pub const PHONE_LOOKUPS_AN_HOUR: usize = 30;
pub const CALLS_AN_HOUR: usize = 60;
pub const BURST_CALLS: usize = 10;
pub const BURST_WINDOW: Duration = Duration::from_secs(600);
pub const SURROUNDING: u32 = 100;
pub const SURROUNDING_THROTTLED: u32 = 8;
/// GrapheneOS's throttle: an answer of this many APs or more...
pub const THROTTLE_AT: usize = 17;
/// ...makes the next calls this long ask for [SURROUNDING_THROTTLED].
pub const THROTTLE_FOR: Duration = Duration::from_secs(10);
/// The pause steps: the first transient failure's, then an hour doubling to a day.
pub const PAUSE_STEPS: [Duration; 7] = [
    Duration::from_secs(300),
    Duration::from_secs(3600),
    Duration::from_secs(2 * 3600),
    Duration::from_secs(4 * 3600),
    Duration::from_secs(8 * 3600),
    Duration::from_secs(16 * 3600),
    Duration::from_secs(24 * 3600),
];

/// How long a partly answered lookup tells the phone to wait when Apple was busy with another lookup.
pub const BUSY_WAIT: Duration = Duration::from_secs(900);

/// Apple's Wi-Fi location service: a fixed URL, never from a request (GrapheneOS's, MIT - see [codec]).
pub const APPLE_URL: &str = "https://gs-loc.apple.com/clls/wloc";
/// GrapheneOS's headers: macOS's `locationd`.
const USER_AGENT: &str = "locationd/2960.0.57 CFNetwork/3826.500.111.1.1 Darwin/24.4.0";

/// A failed Apple call, by class (`integration_health`'s code and the pause's log line).
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Failure {
    Timeout,
    Connect,
    /// The proxy refused, didn't answer, or the setting is not a proxy address.
    Proxy,
    /// 403 or 429: Apple refuses this exit.
    Blocked,
    /// Any other status than 200.
    Http,
    /// A 200 that doesn't read (short, cut off, not the protobuf).
    Format,
    /// `WIFI_LOOKUP_PROXY` is set but isn't a `socks5h://` address: only the server's operator mends it (`stop`).
    ProxySetting,
}

impl Failure {
    pub fn code(self) -> &'static str {
        match self {
            Failure::Timeout => "timeout",
            Failure::Connect => "connect",
            Failure::Proxy => "proxy",
            Failure::Blocked => "blocked",
            Failure::Http => "http",
            Failure::Format => "format",
            Failure::ProxySetting => "proxy_setting",
        }
    }

    /// A non-200, an unreadable answer or a bad setting goes to an hour's pause at once.
    fn hard(self) -> bool {
        matches!(
            self,
            Failure::Blocked | Failure::Http | Failure::Format | Failure::ProxySetting
        )
    }

    /// Nothing but the operator mends it: `integration_health`'s `stop` at once (code QA L6).
    fn stops(self) -> bool {
        self == Failure::ProxySetting
    }
}

/// The pauses after failed calls (L5), in memory.
#[derive(Debug, Default)]
pub struct Pauses {
    /// The step reached since the last success: 0 none, 1 = [PAUSE_STEPS]\[0\].
    step: usize,
    until: Option<Instant>,
    /// Failures since the last success, for the log line.
    failures: u32,
}

impl Pauses {
    /// The pause left at `now`, if any.
    pub fn left(&self, now: Instant) -> Option<Duration> {
        self.until
            .filter(|until| *until > now)
            .map(|until| until - now)
    }

    /// One more failure at `now`: the pause it starts, and whether that is the 24-h step.
    pub fn failed(&mut self, failure: Failure, now: Instant) -> (Duration, bool) {
        self.failures += 1;
        self.step = (self.step + 1).max(if failure.hard() { 2 } else { 1 });
        self.step = self.step.min(PAUSE_STEPS.len());
        let pause = PAUSE_STEPS[self.step - 1];
        self.until = Some(now + pause);
        (pause, self.step == PAUSE_STEPS.len())
    }

    pub fn ok(&mut self) {
        *self = Pauses::default();
    }

    pub fn failures(&self) -> u32 {
        self.failures
    }
}

/// A pause as the log says it: `5m`, `4h`.
fn human(pause: Duration) -> String {
    let minutes = pause.as_secs() / 60;
    if minutes.is_multiple_of(60) {
        format!("{}h", minutes / 60)
    } else {
        format!("{minutes}m")
    }
}

/// Whole seconds, rounded up, at least one.
fn seconds(wait: Duration) -> u64 {
    (wait.as_secs() + u64::from(wait.subsec_nanos() > 0)).max(1)
}

/// A BSSID the route takes: lowercase `xx:xx:xx:xx:xx:xx`, unicast, the locally administered bit clear, not zero.
pub fn valid_bssid(text: &str) -> bool {
    let bytes = text.as_bytes();
    if bytes.len() != 17 {
        return false;
    }
    for (i, b) in bytes.iter().enumerate() {
        let ok = if i % 3 == 2 {
            *b == b':'
        } else {
            b.is_ascii_digit() || (b'a'..=b'f').contains(b)
        };
        if !ok {
            return false;
        }
    }
    let first = u8::from_str_radix(&text[..2], 16).unwrap_or(1);
    first & 0x03 == 0 && text != "00:00:00:00:00:00"
}

/// The request's cells (design 125b C5): `{"cells": [...]}` alone, 1..[cells::MAX_CELLS] entries, each of the wire
/// form ([Cell::parse]); the same string twice once. `None` for a bad body.
pub fn parse_cells(body: &[u8]) -> Option<Vec<String>> {
    let value: Value = serde_json::from_slice(body).ok()?;
    let object = value.as_object().filter(|o| o.len() == 1)?;
    let list = object.get("cells")?.as_array()?;
    if list.is_empty() || list.len() > cells::MAX_CELLS {
        return None;
    }
    let mut asked: Vec<String> = Vec::with_capacity(list.len());
    for item in list {
        let text = item.as_str().filter(|t| Cell::parse(t).is_some())?;
        if !asked.iter().any(|a| a == text) {
            asked.push(text.to_string());
        }
    }
    Some(asked)
}

/// The cells a phone named today (design 125b C5), in memory: `(UTC day, count)` per phone.
#[derive(Debug, Default)]
pub struct CellDays {
    days: std::sync::Mutex<std::collections::HashMap<i64, (i64, usize)>>,
}

impl CellDays {
    fn today(now: chrono::DateTime<chrono::Utc>) -> i64 {
        i64::from(now.date_naive().num_days_from_ce())
    }

    /// `n` more of the phone's [CELLS_A_DAY] fit at `now`, or the wait to the next UTC midnight.
    pub fn fits(
        &self,
        device: i64,
        n: usize,
        now: chrono::DateTime<chrono::Utc>,
    ) -> Result<(), Duration> {
        let day = Self::today(now);
        let days = self.days.lock().unwrap_or_else(|e| e.into_inner());
        let used = days
            .get(&device)
            .filter(|(d, _)| *d == day)
            .map_or(0, |(_, n)| *n);
        if used + n <= CELLS_A_DAY {
            return Ok(());
        }
        let midnight = (now.date_naive() + chrono::Days::new(1))
            .and_hms_opt(0, 0, 0)
            .expect("midnight exists")
            .and_utc();
        Err((midnight - now).to_std().unwrap_or(Duration::from_secs(1)))
    }

    /// The phone named `n` more cells at `now` (yesterday's counts go).
    pub fn add(&self, device: i64, n: usize, now: chrono::DateTime<chrono::Utc>) {
        let day = Self::today(now);
        let mut days = self.days.lock().unwrap_or_else(|e| e.into_inner());
        days.retain(|_, (d, _)| *d == day);
        days.entry(device).or_insert((day, 0)).1 += n;
    }
}

/// One row of a cells 200: the asked string, Apple's position - no age (C5).
#[derive(Clone, Debug, PartialEq, Serialize)]
pub struct CellRow {
    pub cell: String,
    pub lat: f64,
    pub lng: f64,
    pub acc_m: u16,
}

/// A cells lookup's 200.
#[derive(Debug, PartialEq)]
pub struct CellAnswer {
    pub cells: Vec<CellRow>,
    pub unknown: Vec<String>,
}

/// The request's BSSIDs, or `None` for a bad body.
pub fn parse(body: &[u8]) -> Option<Vec<String>> {
    let value: Value = serde_json::from_slice(body).ok()?;
    let object = value.as_object().filter(|o| o.len() == 1)?;
    let list = object.get("bssids")?.as_array()?;
    if list.is_empty() || list.len() > MAX_BSSIDS {
        return None;
    }
    let mut bssids: Vec<String> = Vec::with_capacity(list.len());
    for item in list {
        let text = item.as_str().filter(|t| valid_bssid(t))?;
        if !bssids.iter().any(|b| b == text) {
            bssids.push(text.to_string());
        }
    }
    Some(bssids)
}

/// One row of the 200's `aps`.
#[derive(Clone, Debug, PartialEq, Serialize)]
pub struct Row {
    pub bssid: String,
    pub lat: f64,
    pub lng: f64,
    pub acc_m: u16,
    /// The whole calendar months (UTC) the row stays fresh: 0-[cache::FRESH_MONTHS].
    pub fresh_months_left: i64,
}

fn row(bssid: &str, position: &Position, fetched_month: i64, now_month: i64) -> Row {
    Row {
        bssid: bssid.to_string(),
        lat: position.lat,
        lng: position.lng,
        acc_m: position.acc_m,
        fresh_months_left: (cache::FRESH_MONTHS - (now_month - fetched_month))
            .clamp(0, cache::FRESH_MONTHS),
    }
}

/// A lookup's 200: the rows, the unknowns, and the wait when Apple was needed and not asked.
#[derive(Debug, PartialEq)]
pub struct Answer {
    pub aps: Vec<Row>,
    pub unknown: Vec<String>,
    pub retry_after: Option<Duration>,
}

fn refusal(status: StatusCode, code: &str) -> Response {
    (status, axum::Json(json!({ "error": code }))).into_response()
}

fn unavailable(wait: Duration) -> Response {
    (
        StatusCode::SERVICE_UNAVAILABLE,
        axum::Json(json!({"error": "unavailable", "retry_after_s": seconds(wait)})),
    )
        .into_response()
}

/// Which call [WifiLookup::fetch] makes: BSSIDs or cells (design 125b C6).
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum Call {
    Wifi,
    Cells,
}

/// An answer's reader: the keys it names (BSSIDs or canonical cells) with their positions.
type Reader = fn(&[u8]) -> Result<Vec<(String, Option<Position>)>, codec::Malformed>;

/// Why Apple wasn't asked or didn't answer: the wait for the 503.
enum NotAsked {
    /// Paused, over the limits, or this call failed: the wait.
    Wait(Duration),
    /// A third lookup at once, or the gate's wait ran out.
    Busy,
}

/// The route.
pub async fn route(
    State(state): State<AppState>,
    Extension(AuthedDevice(device)): Extension<AuthedDevice>,
    body: Bytes,
) -> Response {
    let arrived = Instant::now();
    let bssids = match (parse(&body), parse_cells(&body)) {
        (Some(bssids), _) => bssids,
        (None, Some(asked)) => return cells_route(state, device.id, asked, arrived).await,
        (None, None) => return refusal(StatusCode::BAD_REQUEST, "invalid"),
    };
    let lookup = state.integrations.wifi_lookup.clone();
    match super::is_on(&state.db, device.id).await {
        Ok(true) => {}
        Ok(false) => return refusal(StatusCode::CONFLICT, "off"),
        Err(err) => {
            tracing::error!(%err, "wifi_lookup: couldn't read the switch");
            return StatusCode::INTERNAL_SERVER_ERROR.into_response();
        }
    }
    if lookup.phone_asks.ask(device.id, Instant::now()).is_err() {
        return refusal(StatusCode::TOO_MANY_REQUESTS, "limit");
    }
    match lookup.lookup(&state, &bssids).await {
        Ok(Some(answer)) => {
            let mut body = json!({ "aps": answer.aps, "unknown": answer.unknown });
            if let Some(wait) = answer.retry_after {
                body["retry_after_s"] = json!(seconds(wait));
            }
            (StatusCode::OK, axum::Json(body)).into_response()
        }
        Ok(None) => refusal(StatusCode::TOO_MANY_REQUESTS, "busy"),
        Err(wait) => unavailable(wait),
    }
}

/// Design 125b C5: a cells request - the switch, the day's brake, the hour's 30, then the lookup; its 200 or 503 at
/// the fixed answer time.
async fn cells_route(
    state: AppState,
    device: i64,
    asked: Vec<String>,
    arrived: Instant,
) -> Response {
    let lookup = state.integrations.wifi_lookup.clone();
    match super::is_on(&state.db, device).await {
        Ok(true) => {}
        Ok(false) => return refusal(StatusCode::CONFLICT, "off"),
        Err(err) => {
            tracing::error!(%err, "wifi_lookup: couldn't read the switch");
            return StatusCode::INTERNAL_SERVER_ERROR.into_response();
        }
    }
    let mut canonical: Vec<String> = asked
        .iter()
        .filter_map(|a| Cell::parse(a).map(|c| c.canonical()))
        .collect();
    canonical.sort();
    canonical.dedup();
    let now = chrono::Utc::now();
    // The day's brake first (a refused request takes nothing of either count), then the hour's 30.
    if let Err(wait) = lookup.cell_days.fits(device, canonical.len(), now) {
        return (
            StatusCode::TOO_MANY_REQUESTS,
            axum::Json(json!({"error": "limit", "retry_after_s": seconds(wait)})),
        )
            .into_response();
    }
    if lookup.phone_asks.ask(device, Instant::now()).is_err() {
        return refusal(StatusCode::TOO_MANY_REQUESTS, "limit");
    }
    lookup.cell_days.add(device, canonical.len(), now);
    let answer = match lookup.lookup_cells(&state, &asked).await {
        Ok(Some(answer)) => (
            StatusCode::OK,
            axum::Json(json!({ "cells": answer.cells, "unknown": answer.unknown })),
        )
            .into_response(),
        Ok(None) => return refusal(StatusCode::TOO_MANY_REQUESTS, "busy"),
        Err(wait) => unavailable(wait),
    };
    // C5: a cache hit looks like a round trip to Apple.
    tokio::time::sleep_until(arrived + lookup.cell_answer_after).await;
    answer
}

impl WifiLookup {
    /// Design 125b C5: the cells' answer - every asked cell placed or unknown, no age; `Ok(None)` = busy; `Err(wait)`
    /// = Apple can't be asked (paused or over the server-wide limit - for every cells request, cached or not), or the
    /// call for an uncached cell failed.
    pub(crate) async fn lookup_cells(
        &self,
        state: &AppState,
        asked: &[String],
    ) -> Result<Option<CellAnswer>, Duration> {
        let now = Instant::now();
        if let Some(left) = self.paused(now) {
            return Err(left);
        }
        let slot = self.burst.wait(now).max(self.outbound.wait(now));
        if !slot.is_zero() {
            return Err(slot);
        }
        let key = match self.key(state).await {
            Ok(key) => key,
            Err(err) => {
                tracing::error!("wifi_lookup: no cache key: {err}");
                self.key_failed(state).await;
                return Err(PAUSE_STEPS[1]);
            }
        };
        let mut canonical: Vec<String> = Vec::new();
        for a in asked {
            if let Some(cell) = Cell::parse(a)
                && !canonical.contains(&cell.canonical())
            {
                canonical.push(cell.canonical());
            }
        }
        let month = cache::month_of(chrono::Utc::now());
        let mut cached = cache::read(&state.db, &key, &canonical, month)
            .await
            .map_err(|err| {
                tracing::error!(%err, "wifi_lookup: couldn't read the cache");
                PAUSE_STEPS[0]
            })?;
        let wanted: Vec<String> = canonical
            .iter()
            .zip(&cached)
            .filter(|(_, c)| c.wanted(month))
            .map(|(b, _)| b.clone())
            .collect();
        if !wanted.is_empty() {
            match self.fetch(state, &key, &wanted, month, Call::Cells).await {
                Ok(fetched) => {
                    for (cell, c) in canonical.iter().zip(cached.iter_mut()) {
                        if let Some(at) = wanted.iter().position(|w| w == cell) {
                            *c = fetched[at];
                        }
                    }
                }
                Err(NotAsked::Busy) => return Ok(None),
                Err(NotAsked::Wait(wait)) => return Err(wait),
            }
        }
        self.counts.add_cells(canonical.len(), !wanted.is_empty());
        tracing::debug!(
            asked = asked.len(),
            wanted = wanted.len(),
            "wifi_lookup: cell lookup"
        );
        let mut answer = CellAnswer {
            cells: Vec::new(),
            unknown: Vec::new(),
        };
        for a in asked {
            let at =
                Cell::parse(a).and_then(|c| canonical.iter().position(|k| *k == c.canonical()));
            match at.map(|at| cached[at]) {
                Some(Cached::Known { position, .. }) => answer.cells.push(CellRow {
                    cell: a.clone(),
                    lat: position.lat,
                    lng: position.lng,
                    acc_m: position.acc_m,
                }),
                _ => answer.unknown.push(a.clone()),
            }
        }
        let used: Vec<&str> = canonical
            .iter()
            .zip(&cached)
            .filter(|(_, c)| matches!(c, Cached::Known { .. }))
            .map(|(k, _)| k.as_str())
            .collect();
        if let Err(err) = cache::touch(&state.db, &key, &used, month).await {
            tracing::error!(%err, "wifi_lookup: couldn't mark the rows used");
        }
        Ok(Some(answer))
    }

    /// The answer; `Ok(None)` = busy; `Err(wait)` = the cache answered none of them and Apple can't be asked.
    pub(crate) async fn lookup(
        &self,
        state: &AppState,
        bssids: &[String],
    ) -> Result<Option<Answer>, Duration> {
        let key = match self.key(state).await {
            Ok(key) => key,
            Err(err) => {
                tracing::error!("wifi_lookup: no cache key: {err}");
                self.key_failed(state).await;
                return Err(PAUSE_STEPS[1]);
            }
        };
        let month = cache::month_of(chrono::Utc::now());
        let mut cached = match cache::read(&state.db, &key, bssids, month).await {
            Ok(cached) => cached,
            Err(err) => {
                tracing::error!(%err, "wifi_lookup: couldn't read the cache");
                return Err(PAUSE_STEPS[0]);
            }
        };
        let wanted: Vec<String> = bssids
            .iter()
            .zip(&cached)
            .filter(|(_, c)| c.wanted(month))
            .map(|(b, _)| b.clone())
            .collect();
        let mut not_asked = None;
        if !wanted.is_empty() {
            match self.fetch(state, &key, &wanted, month, Call::Wifi).await {
                Ok(fetched) => {
                    for (bssid, c) in bssids.iter().zip(cached.iter_mut()) {
                        if let Some(at) = wanted.iter().position(|w| w == bssid) {
                            *c = fetched[at];
                        }
                    }
                }
                Err(why) => not_asked = Some(why),
            }
        }
        let aps: Vec<Row> = bssids
            .iter()
            .zip(&cached)
            .filter_map(|(bssid, c)| match c {
                Cached::Known {
                    position,
                    fetched_month,
                } => Some(row(bssid, position, *fetched_month, month)),
                _ => None,
            })
            .collect();
        let unknown: Vec<String> = bssids
            .iter()
            .zip(&cached)
            .filter(|(_, c)| **c == Cached::Unknown)
            .map(|(b, _)| b.clone())
            .collect();
        let asked = bssids.len();
        let unanswered = asked - aps.len() - unknown.len();
        self.counts
            .add(asked, wanted.len(), not_asked.is_none(), unanswered);
        tracing::debug!(
            asked,
            wanted = wanted.len(),
            known = aps.len(),
            unknown = unknown.len(),
            unanswered,
            "wifi_lookup: lookup"
        );
        let answered_none = aps.is_empty() && unknown.is_empty();
        let retry_after = match not_asked {
            // Code QA L4: busy, or the 503, only when the cache answered none of them.
            Some(NotAsked::Busy) if answered_none => return Ok(None),
            Some(NotAsked::Wait(wait)) if answered_none => return Err(wait),
            Some(NotAsked::Busy) => Some(BUSY_WAIT),
            Some(NotAsked::Wait(wait)) => Some(wait),
            None => None,
        };
        let used: Vec<&str> = aps.iter().map(|r| r.bssid.as_str()).collect();
        if let Err(err) = cache::touch(&state.db, &key, &used, month).await {
            tracing::error!(%err, "wifi_lookup: couldn't mark the rows used");
        }
        Ok(Some(Answer {
            aps,
            unknown,
            retry_after,
        }))
    }

    /// A key file the server can't read: `stop` at once (code QA L6); no pause - the next lookup reads the file again.
    async fn key_failed(&self, state: &AppState) {
        self.health.error(chrono::Utc::now(), "key");
        crate::integration_health::failed(&state.db, NAME, "", "key", Class::Stop).await;
    }

    /// Apple asked, behind the gate, the pauses and the limits, for what of `wanted` the cache still lacks once the
    /// call is ours: the cache's answer for each of `wanted` (in order) after it. `call`: BSSIDs, or cells in their
    /// canonical form (design 125b C6: their own call, never inside the Wi-Fi one, under the one gate and limits).
    async fn fetch(
        &self,
        state: &AppState,
        key: &cache::CacheKey,
        wanted: &[String],
        month: i64,
        call: Call,
    ) -> Result<Vec<Cached>, NotAsked> {
        if let Some(left) = self.paused(Instant::now()) {
            return Err(NotAsked::Wait(left));
        }
        let Ok(_admitted) = self.admit.try_acquire() else {
            return Err(NotAsked::Busy);
        };
        let Ok(_one) = tokio::time::timeout(self.gate_wait, self.apple.lock()).await else {
            return Err(NotAsked::Busy);
        };
        // The call before this one may have started a pause, or fetched these (code QA L5).
        let now = Instant::now();
        if let Some(left) = self.paused(now) {
            return Err(NotAsked::Wait(left));
        }
        let again = cache::read(&state.db, key, wanted, month)
            .await
            .map_err(|err| {
                tracing::error!(%err, "wifi_lookup: couldn't read the cache");
                NotAsked::Wait(PAUSE_STEPS[0])
            })?;
        let still: Vec<String> = wanted
            .iter()
            .zip(&again)
            .filter(|(_, c)| c.wanted(month))
            .map(|(b, _)| b.clone())
            .collect();
        if still.is_empty() {
            return Ok(again);
        }
        let slot = self.burst.wait(now).max(self.outbound.wait(now));
        if !slot.is_zero() {
            return Err(NotAsked::Wait(slot));
        }
        self.burst.take(now);
        self.outbound.take(now);
        let throttle = match call {
            Call::Wifi => &self.throttled_until,
            Call::Cells => &self.cell_throttled_until,
        };
        let throttled = throttle
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .is_some_and(|until| until > now);
        let (body, read): (Vec<u8>, Reader) = match call {
            Call::Wifi => (
                codec::request(
                    &still,
                    if throttled {
                        SURROUNDING_THROTTLED
                    } else {
                        SURROUNDING
                    },
                ),
                codec::answer,
            ),
            Call::Cells => {
                let towers: Vec<Cell> = still
                    .iter()
                    .filter_map(|c| Cell::from_canonical(c))
                    .collect();
                (cells::request(&towers, throttled), cells::answer)
            }
        };
        let aps = match self.ask_apple(body, read).await {
            Ok(aps) => aps,
            Err(failure) => return Err(NotAsked::Wait(self.failed(state, failure).await)),
        };
        let throttle_at = match call {
            Call::Wifi => THROTTLE_AT,
            Call::Cells => cells::CELL_THROTTLE_AT,
        };
        if aps.len() >= throttle_at {
            *throttle.lock().unwrap_or_else(|e| e.into_inner()) =
                Some(Instant::now() + THROTTLE_FOR);
        }
        self.pauses.lock().unwrap_or_else(|e| e.into_inner()).ok();
        self.health.ok(chrono::Utc::now());
        crate::integration_health::ok(&state.db, NAME, "").await;
        // Every AP Apple placed is kept (the surrounding ones for later lookups); only asked ones are answered.
        let known: Vec<(String, Position)> = aps
            .iter()
            .filter_map(|(b, p)| p.map(|p| (b.clone(), p)))
            .collect();
        let unknown: Vec<String> = still
            .iter()
            .filter(|w| !known.iter().any(|(b, _)| b == *w))
            .cloned()
            .collect();
        if let Err(err) = cache::store(&state.db, key, &known, &unknown, month).await {
            tracing::error!(%err, "wifi_lookup: couldn't keep Apple's answer");
        }
        Ok(wanted
            .iter()
            .zip(again)
            .map(|(bssid, before)| {
                if !still.contains(bssid) {
                    return before;
                }
                known
                    .iter()
                    .find(|(b, _)| b == bssid)
                    .map_or(Cached::Unknown, |(_, position)| Cached::Known {
                        position: *position,
                        fetched_month: month,
                    })
            })
            .collect())
    }

    fn paused(&self, now: Instant) -> Option<Duration> {
        self.pauses
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .left(now)
    }

    /// A failed call: its pause started and logged, the health recorded (`stop` once the pause reaches its day).
    pub(crate) async fn failed(&self, state: &AppState, failure: Failure) -> Duration {
        let (pause, day, failures) = {
            let mut pauses = self.pauses.lock().unwrap_or_else(|e| e.into_inner());
            let (pause, day) = pauses.failed(failure, Instant::now());
            (pause, day, pauses.failures())
        };
        tracing::warn!(
            "wifi_lookup: paused {} class={} failures={failures}",
            human(pause),
            failure.code()
        );
        self.health.error(chrono::Utc::now(), failure.code());
        let class = if day || failure.stops() {
            Class::Stop
        } else {
            Class::Transient
        };
        crate::integration_health::failed(&state.db, NAME, "", failure.code(), class).await;
        pause
    }

    /// One call to Apple with `body`, its answer read by `read`: through the proxy when one is set, never direct then.
    async fn ask_apple(
        &self,
        body: Vec<u8>,
        read: Reader,
    ) -> Result<Vec<(String, Option<Position>)>, Failure> {
        let proxy = match &self.proxy {
            Proxy::Invalid => return Err(Failure::ProxySetting),
            Proxy::Socks(url) => Some(url.clone()),
            Proxy::Direct => None,
        };
        let proxied = proxy.is_some();
        let mut request = SourceRequest::new(APPLE_URL, Kind::AppleWps, Reach::Public);
        request.method = reqwest::Method::POST;
        request.follow = false;
        request.proxy = proxy;
        request.headers = vec![
            (reqwest::header::ACCEPT, "*/*".to_string()),
            (
                reqwest::header::ACCEPT_LANGUAGE,
                "en-US,en;q=0.9".to_string(),
            ),
            (
                reqwest::header::CONTENT_TYPE,
                "application/x-www-form-urlencoded".to_string(),
            ),
            (reqwest::header::USER_AGENT, USER_AGENT.to_string()),
        ];
        request.body = Some(body);
        let answer = self.source.get(request).await.map_err(|err| match err {
            FetchError::Timeout => Failure::Timeout,
            _ if proxied => Failure::Proxy,
            _ => Failure::Connect,
        })?;
        tracing::debug!(status = answer.status, "wifi_lookup: Apple answered");
        match answer.status {
            200 if answer.truncated => Err(Failure::Format),
            200 => read(&answer.body).map_err(|_| Failure::Format),
            403 | 429 => Err(Failure::Blocked),
            _ => Err(Failure::Http),
        }
    }
}
