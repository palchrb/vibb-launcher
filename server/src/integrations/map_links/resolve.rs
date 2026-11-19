//! `POST /api/devices/map-links/resolve` (design 110 §8.1): a short link to its long link, and a feature id to its
//! place, for a phone whose switch is on.
//!
//! - **Request**: `{"url": "<short link>"}` or `{"ftid": "0x…:0x…", "text": "<the link's text>"}`, 1 KB at most.
//! - **Answers**: 200 `{"url"?, "place"?: {lat, lon, name?, address?}}`; 409 `off`; 422 `not_short_link` / `dead`;
//!   429 `busy`; 503 `unreachable`; 400 `invalid`. A lookup that finds nothing is no error: 200 without `place`.
//! - **Calls out**: one GET per hop (redirects never followed, `Location` read, at most 3 hops, a hop to a short-link
//!   host rebuilt through [links::short_link]), then one GET to Google's place lookup - all within one 5 s deadline,
//!   through the integration's own gate (one at a time, 200 ms apart; a third caller is `busy` at once).
//! - **Limits**: 30 resolves per phone an hour; 100 calls out an hour and 10 in any 10 minutes, server-wide; after
//!   Google's wall (a 403/429 or its "Sorry" page) no lookup for an hour.
//! - **The cache**: a long link and a place 10 minutes; a dead link and Google's answer without a place 1 minute; a
//!   call that got no answer not at all. At most 500 entries; in memory only.
//! - **The log** has the host and the outcome only: no id, no text, no point.

use std::collections::{HashMap, VecDeque};
use std::time::Duration;

use axum::Extension;
use axum::body::Bytes;
use axum::extract::State;
use axum::http::StatusCode;
use axum::response::{IntoResponse, Response};
use serde::{Deserialize, Serialize};
use serde_json::json;
use tokio::time::Instant;

use super::preview::{self, Outcome, Place};
use super::{MapLinks, links};
use crate::AppState;
use crate::music_net::{Gated, Kind, Reach, Source, SourceRequest};
use crate::security::AuthedDevice;

pub const BODY_LIMIT: usize = 1024;
pub const PHONE_RESOLVES_AN_HOUR: usize = 30;
pub const CALLS_AN_HOUR: usize = 100;
/// The burst limit on top of the hour's (the emulator gate, 2026-11-18: Google walled the address after about 20
/// lookups in 5 minutes): at most 10 calls out in any 10 minutes, server-wide.
pub const BURST_CALLS: usize = 10;
pub const BURST_WINDOW: Duration = Duration::from_secs(600);
/// After Google's wall ([Outcome::Blocked]) no lookup is sent for this long (short links' hops still go: another
/// host); each one asked meanwhile is counted `blocked`.
pub const BLOCKED_PAUSE: Duration = Duration::from_secs(3600);
/// Hops and the lookup together: the phone gives up at 6 s.
pub const DEADLINE: Duration = Duration::from_secs(5);
const HOPS: usize = 3;
/// The link's text, used for the `text` check only (never sent on).
const MAX_TEXT: usize = 200;
const KEEP_FOUND: Duration = Duration::from_secs(600);
const KEEP_FAILED: Duration = Duration::from_secs(60);
const CACHE_MAX: usize = 500;

/// The integration's gate: one call out at a time, 200 ms apart ([Gated]); a resolve is admitted only while at most
/// one other is in flight or waiting, else `busy` at once (§8.1, QA 3).
pub struct Gate {
    pub(crate) admit: tokio::sync::Semaphore,
    pub(crate) source: Gated,
}

impl Gate {
    pub fn new(source: Gated) -> Self {
        Gate {
            admit: tokio::sync::Semaphore::new(2),
            source,
        }
    }
}

/// A rolling count of calls out: at most `limit` in `window`.
pub struct Window {
    limit: usize,
    window: Duration,
    times: std::sync::Mutex<VecDeque<Instant>>,
}

impl Window {
    pub fn new(limit: usize, window: Duration) -> Self {
        Window {
            limit,
            window,
            times: Default::default(),
        }
    }

    /// One call at `now`: `true` (and counted) within the limit.
    pub fn take(&self, now: Instant) -> bool {
        let mut times = self.times.lock().unwrap_or_else(|e| e.into_inner());
        while times
            .front()
            .is_some_and(|t| now.saturating_duration_since(*t) >= self.window)
        {
            times.pop_front();
        }
        if times.len() >= self.limit {
            return false;
        }
        times.push_back(now);
        true
    }
}

/// What the cache keeps: a short link's long link, or a lookup's place (or none).
#[derive(Clone, Debug)]
enum Cached {
    /// A short link's long link (or its `dead`).
    Short(Result<String, Refusal>),
    Place(Option<Place>),
}

/// A place's cache key: the language, the id and the link's text (the `text` check depends on it).
fn place_key(hl: &str, ftid: &str, text: Option<&str>) -> String {
    format!("place {hl} {ftid} {}", text.unwrap_or(""))
}

#[derive(Default)]
pub struct Cache {
    entries: std::sync::Mutex<HashMap<String, (Instant, Cached)>>,
}

impl Cache {
    fn get(&self, key: &str, now: Instant) -> Option<Cached> {
        let entries = self.entries.lock().unwrap_or_else(|e| e.into_inner());
        entries
            .get(key)
            .filter(|(until, _)| *until > now)
            .map(|(_, cached)| cached.clone())
    }

    fn put(&self, key: String, cached: Cached, keep: Duration, now: Instant) {
        let mut entries = self.entries.lock().unwrap_or_else(|e| e.into_inner());
        entries.retain(|_, (until, _)| *until > now);
        if entries.len() >= CACHE_MAX {
            // The one that ends first goes.
            if let Some(first) = entries
                .iter()
                .min_by_key(|(_, (until, _))| *until)
                .map(|(k, _)| k.clone())
            {
                entries.remove(&first);
            }
        }
        entries.insert(key, (now + keep, cached));
    }

    #[cfg(test)]
    pub fn len(&self) -> usize {
        self.entries.lock().unwrap().len()
    }
}

#[derive(Deserialize)]
struct Request {
    url: Option<String>,
    ftid: Option<String>,
    text: Option<String>,
}

/// The 200's body.
#[derive(Clone, Debug, Default, Serialize)]
pub struct ShortAnswer {
    #[serde(skip_serializing_if = "Option::is_none")]
    pub url: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub place: Option<Place>,
}

/// A refusal's status and code.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Refusal {
    Off,
    NotShortLink,
    Dead,
    Busy,
    Unreachable,
    Invalid,
}

impl Refusal {
    fn status(self) -> StatusCode {
        match self {
            Refusal::Off => StatusCode::CONFLICT,
            Refusal::NotShortLink | Refusal::Dead => StatusCode::UNPROCESSABLE_ENTITY,
            Refusal::Busy => StatusCode::TOO_MANY_REQUESTS,
            Refusal::Unreachable => StatusCode::SERVICE_UNAVAILABLE,
            Refusal::Invalid => StatusCode::BAD_REQUEST,
        }
    }

    pub fn code(self) -> &'static str {
        match self {
            Refusal::Off => "off",
            Refusal::NotShortLink => "not_short_link",
            Refusal::Dead => "dead",
            Refusal::Busy => "busy",
            Refusal::Unreachable => "unreachable",
            Refusal::Invalid => "invalid",
        }
    }
}

impl IntoResponse for Refusal {
    fn into_response(self) -> Response {
        (self.status(), axum::Json(json!({ "error": self.code() }))).into_response()
    }
}

fn answer(body: ShortAnswer) -> Response {
    (StatusCode::OK, axum::Json(body)).into_response()
}

/// The route.
pub async fn route(
    State(state): State<AppState>,
    Extension(AuthedDevice(device)): Extension<AuthedDevice>,
    body: Bytes,
) -> Response {
    let links = state.integrations.map_links.clone();
    let Ok(request) = serde_json::from_slice::<Request>(&body) else {
        return Refusal::Invalid.into_response();
    };
    // Exactly one of the two shapes.
    let shape = match (&request.url, &request.ftid) {
        (Some(url), None) => Shape::Short(url.clone()),
        (None, Some(ftid)) => match links::valid_ftid(ftid) {
            Some(ftid) => Shape::Ftid(
                ftid,
                request
                    .text
                    .as_deref()
                    .map(|t| t.chars().take(MAX_TEXT).collect::<String>())
                    .filter(|t| !t.trim().is_empty()),
            ),
            None => return Refusal::Invalid.into_response(),
        },
        _ => return Refusal::Invalid.into_response(),
    };
    match super::is_on(&state.db, device.id).await {
        Ok(true) => {}
        Ok(false) => return Refusal::Off.into_response(),
        Err(err) => {
            tracing::error!(device_id = device.id, %err, "map links: couldn't read the switch");
            return StatusCode::INTERNAL_SERVER_ERROR.into_response();
        }
    }
    let now = Instant::now();
    match shape {
        Shape::Short(url) => {
            let Some(short) = links::short_link(&url) else {
                return Refusal::NotShortLink.into_response();
            };
            if links.phone_asks.ask(device.id, now).is_err() {
                return Refusal::Busy.into_response();
            }
            let hl = language(&state.db, device.id).await;
            match links.short(&state.db, &short, &hl).await {
                Ok(body) => answer(body),
                Err(refusal) => refusal.into_response(),
            }
        }
        Shape::Ftid(ftid, text) => {
            if links.phone_asks.ask(device.id, now).is_err() {
                count(&state.db, Outcome::Busy).await;
                return answer(ShortAnswer::default());
            }
            let hl = language(&state.db, device.id).await;
            let place = links
                .place(
                    &state.db,
                    &ftid,
                    text.as_deref(),
                    &hl,
                    Instant::now() + links.deadline,
                )
                .await;
            answer(ShortAnswer { url: None, place })
        }
    }
}

enum Shape {
    Short(String),
    Ftid(String, Option<String>),
}

/// The lookup's `hl`: the phone's own language when it is English, else Norwegian (a "system" phone in a Norwegian
/// household reads Norwegian names best).
async fn language(db: &sqlx::SqlitePool, device_id: i64) -> String {
    let lang: Option<String> =
        sqlx::query_scalar("SELECT launcher_language FROM device_policy WHERE device_id = ?")
            .bind(device_id)
            .fetch_optional(db)
            .await
            .ok()
            .flatten();
    if lang.as_deref() == Some("en") {
        "en"
    } else {
        "no"
    }
    .to_string()
}

/// One lookup outcome counted (§8.4), and the old days pruned. A failure is logged, never the answer's.
async fn count(db: &sqlx::SqlitePool, outcome: Outcome) {
    let result = async {
        sqlx::query(
            "INSERT INTO map_link_lookups (day, outcome, n) VALUES (date('now'), ?, 1) \
             ON CONFLICT(day, outcome) DO UPDATE SET n = n + 1",
        )
        .bind(outcome.code())
        .execute(db)
        .await?;
        sqlx::query("DELETE FROM map_link_lookups WHERE day < date('now', '-30 days')")
            .execute(db)
            .await
    }
    .await;
    if let Err(err) = result {
        tracing::error!(%err, "map links: couldn't count a lookup");
    }
}

impl MapLinks {
    /// A short link's answer: its long link from the cache or its hops (a dead link kept a minute), then - when the long
    /// link needs one - the place through [MapLinks::cached_lookup], under the place's own key (code QA M2: a failed
    /// lookup never rides on the long link's 10 minutes).
    async fn short(
        &self,
        db: &sqlx::SqlitePool,
        short: &str,
        hl: &str,
    ) -> Result<ShortAnswer, Refusal> {
        let now = Instant::now();
        let deadline = now + self.deadline;
        let key = format!("short {short}");
        let mut admitted = None;
        let long = match self.cache.get(&key, now) {
            Some(Cached::Short(cached)) => cached?,
            _ => {
                let Ok(permit) = self.gate.admit.try_acquire() else {
                    return Err(Refusal::Busy);
                };
                admitted = Some(permit);
                match tokio::time::timeout_at(deadline, self.hops(short)).await {
                    Ok(Ok(long)) => {
                        self.cache.put(
                            key,
                            Cached::Short(Ok(long.clone())),
                            KEEP_FOUND,
                            Instant::now(),
                        );
                        long
                    }
                    Ok(Err(refusal)) => {
                        if refusal == Refusal::Dead {
                            self.cache.put(
                                key,
                                Cached::Short(Err(refusal)),
                                KEEP_FAILED,
                                Instant::now(),
                            );
                        }
                        return Err(refusal);
                    }
                    Err(_) => {
                        self.health.error(chrono::Utc::now(), "unreachable");
                        tracing::info!(host = %host_of(short), outcome = "unreachable", "map links: short link");
                        return Err(Refusal::Unreachable);
                    }
                }
            }
        };
        let facts = links::facts(&long);
        let place = match &facts.ftid {
            Some(ftid) if !facts.has_point || !links::has_address(facts.text.as_deref()) => {
                let text = facts.text.as_deref();
                match self.cache.get(&place_key(hl, ftid, text), Instant::now()) {
                    Some(Cached::Place(place)) => place,
                    _ => {
                        if admitted.is_none() {
                            admitted = self.gate.admit.try_acquire().ok();
                        }
                        if admitted.is_some() {
                            self.cached_lookup(db, ftid, text, hl, deadline).await
                        } else {
                            count(db, Outcome::Busy).await;
                            None
                        }
                    }
                }
            }
            _ => None,
        };
        drop(admitted);
        Ok(ShortAnswer {
            url: Some(long),
            place,
        })
    }

    /// A feature id's place: from the cache, or one lookup through the gate (`busy` counted when it can't be).
    async fn place(
        &self,
        db: &sqlx::SqlitePool,
        ftid: &str,
        text: Option<&str>,
        hl: &str,
        deadline: Instant,
    ) -> Option<Place> {
        if let Some(Cached::Place(place)) =
            self.cache.get(&place_key(hl, ftid, text), Instant::now())
        {
            return place;
        }
        let Ok(_admitted) = self.gate.admit.try_acquire() else {
            count(db, Outcome::Busy).await;
            return None;
        };
        self.cached_lookup(db, ftid, text, hl, deadline).await
    }

    /// One lookup, its answer kept: a place 10 minutes, Google's answer without one a minute; a lookup that never got
    /// an answer (unreachable, ours busy) isn't kept, so the next tap asks again.
    async fn cached_lookup(
        &self,
        db: &sqlx::SqlitePool,
        ftid: &str,
        text: Option<&str>,
        hl: &str,
        deadline: Instant,
    ) -> Option<Place> {
        let (outcome, place) = self.lookup_within(db, ftid, text, hl, deadline).await;
        let keep = match outcome {
            Outcome::Unreachable | Outcome::Busy => None,
            _ if place.is_some() => Some(KEEP_FOUND),
            _ => Some(KEEP_FAILED),
        };
        if let Some(keep) = keep {
            self.cache.put(
                place_key(hl, ftid, text),
                Cached::Place(place.clone()),
                keep,
                Instant::now(),
            );
        }
        place
    }

    /// One lookup before `deadline`, counted.
    async fn lookup_within(
        &self,
        db: &sqlx::SqlitePool,
        ftid: &str,
        text: Option<&str>,
        hl: &str,
        deadline: Instant,
    ) -> (Outcome, Option<Place>) {
        let (outcome, place) = tokio::time::timeout_at(deadline, self.lookup(ftid, text, hl))
            .await
            .unwrap_or((Outcome::Unreachable, None));
        // Google's wall: no lookup for the pause (a new pause only once the last one ended).
        if outcome == Outcome::Blocked
            && self
                .blocked_until()
                .is_none_or(|until| until <= Instant::now())
        {
            *self.blocked.lock().unwrap_or_else(|e| e.into_inner()) =
                Some(Instant::now() + BLOCKED_PAUSE);
        }
        count(db, outcome).await;
        let at = chrono::Utc::now();
        if outcome.found() {
            self.health.ok(at);
        } else if outcome != Outcome::Busy {
            self.health.error(at, outcome.code());
        }
        tracing::info!(
            host = "www.google.com",
            outcome = outcome.code(),
            "map links: place lookup"
        );
        (outcome, place)
    }

    async fn lookup(&self, ftid: &str, text: Option<&str>, hl: &str) -> (Outcome, Option<Place>) {
        if self
            .blocked_until()
            .is_some_and(|until| until > Instant::now())
        {
            return (Outcome::Blocked, None);
        }
        if !self.calls_out(Instant::now()) {
            return (Outcome::Busy, None);
        }
        let mut request = SourceRequest::new(
            preview::lookup_url(ftid, hl),
            Kind::PlacePreview,
            Reach::Public,
        );
        request.follow = false;
        match self.gate.source.get(request).await {
            Err(_) => (Outcome::Unreachable, None),
            Ok(answer) if answer.truncated => (Outcome::Format, None),
            Ok(answer) => preview::judge(
                answer.status,
                answer.location.as_deref(),
                answer.content_type.as_deref(),
                &answer.body,
                ftid,
                text,
            ),
        }
    }

    /// One call out at `now` within both limits (the burst's and the hour's), counted in both.
    fn calls_out(&self, now: Instant) -> bool {
        self.burst.take(now) && self.outbound.take(now)
    }

    /// The end of the pause after Google's wall, if one was met.
    fn blocked_until(&self) -> Option<Instant> {
        *self.blocked.lock().unwrap_or_else(|e| e.into_inner())
    }

    /// The hops from a short link to a long map link: `Dead` for anything else, `Unreachable` when a hop can't be
    /// asked, `Busy` past the hour's calls.
    pub(crate) async fn hops(&self, short: &str) -> Result<String, Refusal> {
        let mut url = short.to_string();
        for _ in 0..HOPS {
            if !self.calls_out(Instant::now()) {
                return Err(Refusal::Busy);
            }
            let mut request = SourceRequest::new(url.as_str(), Kind::ShortLink, Reach::Public);
            request.follow = false;
            let host = host_of(&url);
            let answer = match self.gate.source.get(request).await {
                Ok(answer) => answer,
                Err(_) => {
                    self.health.error(chrono::Utc::now(), "unreachable");
                    tracing::info!(host = %host, outcome = "unreachable", "map links: short link");
                    return Err(Refusal::Unreachable);
                }
            };
            let location = answer
                .location
                .filter(|_| (300..400).contains(&answer.status))
                .filter(|l| l.chars().count() <= links::MAX_LONG_URL);
            let Some(location) = location else {
                tracing::info!(host = %host, outcome = "dead", status = answer.status, "map links: short link");
                return Err(Refusal::Dead);
            };
            if let Some(next) = links::short_link(&location) {
                url = next;
                continue;
            }
            if links::long_map_url(&location) {
                self.health.ok(chrono::Utc::now());
                tracing::info!(host = %host, outcome = "long", "map links: short link");
                return Ok(location);
            }
            tracing::info!(host = %host, outcome = "dead", "map links: short link");
            return Err(Refusal::Dead);
        }
        tracing::info!(outcome = "dead", "map links: short link, too many hops");
        Err(Refusal::Dead)
    }
}

/// A link's host for the log (never its path or query).
fn host_of(url: &str) -> String {
    reqwest::Url::parse(url)
        .ok()
        .and_then(|u| u.host_str().map(str::to_string))
        .unwrap_or_default()
}

/// The last 24 hours' outcomes say Google's lookup is broken (§8.4): nothing found, no other check failing, and
/// either Google's wall (`blocked`, once is enough) or at least 3 lookups without a usable answer (`consent`, `http`,
/// `format`, `unreachable` - the emulator gate saw an outage booked as `unreachable` alone). `busy` is ours.
pub fn lookup_broken(counts: &[(String, i64)]) -> bool {
    let mut broken = 0;
    let mut blocked = false;
    for (outcome, n) in counts {
        match outcome.as_str() {
            "consent" | "http" | "format" | "unreachable" => broken += n,
            "blocked" => blocked |= *n > 0,
            "busy" => {}
            _ if *n > 0 => return false,
            _ => {}
        }
    }
    blocked || broken >= 3
}

/// Today's and yesterday's counts (the counter is by day).
pub async fn recent_counts(db: &sqlx::SqlitePool) -> Result<Vec<(String, i64)>, sqlx::Error> {
    sqlx::query_as(
        "SELECT outcome, SUM(n) FROM map_link_lookups WHERE day >= date('now', '-1 day') GROUP BY outcome",
    )
    .fetch_all(db)
    .await
}
