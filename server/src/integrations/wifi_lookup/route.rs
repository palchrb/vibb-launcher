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

use std::time::Duration;

use axum::Extension;
use axum::body::Bytes;
use axum::extract::State;
use axum::http::StatusCode;
use axum::response::{IntoResponse, Response};
use serde::Serialize;
use serde_json::{Value, json};
use tokio::time::Instant;

use super::cache::{self, Cached};
use super::codec::{self, Position};
use super::{NAME, Proxy, WifiLookup};
use crate::AppState;
use crate::integration_health::Class;
use crate::music_net::{FetchError, Kind, Reach, SourceRequest};
use crate::security::AuthedDevice;

pub const BODY_LIMIT: usize = 4096;
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
    let Some(bssids) = parse(&body) else {
        return refusal(StatusCode::BAD_REQUEST, "invalid");
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

impl WifiLookup {
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
            match self.fetch(state, &key, &wanted, month).await {
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
    /// call is ours: the cache's answer for each of `wanted` (in order) after it.
    async fn fetch(
        &self,
        state: &AppState,
        key: &cache::CacheKey,
        wanted: &[String],
        month: i64,
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
        let throttled = self
            .throttled_until
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .is_some_and(|until| until > now);
        let surrounding = if throttled {
            SURROUNDING_THROTTLED
        } else {
            SURROUNDING
        };
        let aps = match self.ask_apple(&still, surrounding).await {
            Ok(aps) => aps,
            Err(failure) => return Err(NotAsked::Wait(self.failed(state, failure).await)),
        };
        if aps.len() >= THROTTLE_AT {
            *self
                .throttled_until
                .lock()
                .unwrap_or_else(|e| e.into_inner()) = Some(Instant::now() + THROTTLE_FOR);
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

    /// One call to Apple: through the proxy when one is set, never direct then.
    async fn ask_apple(
        &self,
        wanted: &[String],
        surrounding: u32,
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
        request.body = Some(codec::request(wanted, surrounding));
        let answer = self.source.get(request).await.map_err(|err| match err {
            FetchError::Timeout => Failure::Timeout,
            _ if proxied => Failure::Proxy,
            _ => Failure::Connect,
        })?;
        tracing::debug!(status = answer.status, "wifi_lookup: Apple answered");
        match answer.status {
            200 if answer.truncated => Err(Failure::Format),
            200 => codec::answer(&answer.body).map_err(|_| Failure::Format),
            403 | 429 => Err(Failure::Blocked),
            _ => Err(Failure::Http),
        }
    }
}
