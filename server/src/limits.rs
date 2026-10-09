//! Limits on the device API (design 22 §3.1, QA High 2), on both listeners. Two key kinds:
//!
//! - **A valid token** (the device): a bucket of [TOKEN_BURST] requests refilling at
//!   [TOKEN_REFILL_PER_SEC], and in-flight caps - [LONG_IN_FLIGHT] for the SSE stream and file
//!   downloads, [GENERAL_IN_FLIGHT] for the rest, while `policy`, `status` and `command-result` are
//!   always admitted (the bucket still applies), so a ring or a lock never waits behind downloads.
//! - **A client IP** (`net::limit_key`: IPv4 address, IPv6 /64), counted only for failed requests -
//!   a bad or missing token, a typed-shaped enrollment code that didn't match: [IP_FAILURES] in
//!   [IP_WINDOW] get 429 for [IP_BLOCK]. **A valid token never touches this bucket**, so a
//!   carrier-CGNAT neighbour, or every client looking like the Docker gateway, can't lock a phone
//!   out.
//!
//! There is no global bucket before auth (one internet host could otherwise starve every phone);
//! [GLOBAL_IN_FLIGHT] is a memory guard only. Every map is capped at [MAX_KEYS] keys, the
//! oldest dropped first. Nothing here touches the database.

use std::collections::{HashMap, VecDeque};
use std::net::IpAddr;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use chrono::{DateTime, Utc};

pub const TOKEN_BURST: f64 = 600.0;
pub const TOKEN_REFILL_PER_SEC: f64 = 2.0;
pub const GENERAL_IN_FLIGHT: u32 = 8;
pub const LONG_IN_FLIGHT: u32 = 4;
pub const IP_FAILURES: u32 = 30;
pub const IP_WINDOW: Duration = Duration::from_secs(10 * 60);
pub const IP_BLOCK: Duration = Duration::from_secs(10 * 60);
pub const GLOBAL_IN_FLIGHT: usize = 512;
pub const MAX_KEYS: usize = 10_000;
/// Device request bodies (status reports, DNS events, crash reports are far smaller).
pub const DEVICE_BODY_LIMIT: usize = 512 * 1024;
/// Non-streaming device requests get this long to answer.
pub const REQUEST_TIMEOUT: Duration = Duration::from_secs(30);
/// Public client IPs remembered per device for the device page.
pub const LAST_IPS: usize = 5;

/// What kind of device request this is, by path.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum RouteClass {
    /// `policy`, `status`, `command-result`: never refused for being in flight.
    Always,
    /// The SSE stream and file downloads (APKs, music files): their own cap, and no timeout.
    Long,
    General,
}

pub fn route_class(path: &str) -> RouteClass {
    match path {
        "/api/devices/policy" | "/api/devices/status" | "/api/devices/command-result" => {
            RouteClass::Always
        }
        "/api/devices/commands/stream" => RouteClass::Long,
        _ if (path.starts_with("/api/devices/apps/") && path.ends_with("/download"))
            || path.starts_with("/api/devices/music/files/") =>
        {
            RouteClass::Long
        }
        _ => RouteClass::General,
    }
}

/// Why a request was refused: 429 with `Retry-After` (seconds).
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Refused {
    pub retry_after_secs: u64,
}

struct Bucket {
    tokens: f64,
    last: Instant,
}

#[derive(Default, Clone, Copy)]
struct InFlight {
    general: u32,
    long: u32,
}

struct IpFailures {
    window_start: Instant,
    count: u32,
    blocked_until: Option<Instant>,
}

/// How a phone last reached this server, for the device page (§3.1 "last access").
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct LastAccess {
    pub at: DateTime<Utc>,
    pub via: Via,
    /// The last [LAST_IPS] distinct public client IPs, newest first.
    pub public_ips: Vec<String>,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Via {
    Tailnet,
    Loopback,
    Public,
}

#[derive(Default)]
pub struct Limits {
    buckets: Mutex<HashMap<i64, Bucket>>,
    in_flight: Mutex<HashMap<i64, InFlight>>,
    ip_failures: Mutex<HashMap<String, IpFailures>>,
    last_access: Mutex<HashMap<i64, (LastAccess, VecDeque<String>)>>,
    /// The global in-flight guard; `None` until first use (a `Semaphore` has no `Default`).
    global: std::sync::OnceLock<Arc<tokio::sync::Semaphore>>,
    exposure: ExposureWatch,
}

impl Limits {
    /// One request from a device with a valid token: refused when its bucket is empty.
    pub fn take_token(&self, device_id: i64) -> Result<(), Refused> {
        self.take_token_at(device_id, Instant::now())
    }

    fn take_token_at(&self, device_id: i64, now: Instant) -> Result<(), Refused> {
        let mut buckets = self.buckets.lock().unwrap_or_else(|e| e.into_inner());
        if buckets.len() >= MAX_KEYS && !buckets.contains_key(&device_id) {
            evict_oldest(&mut buckets, |bucket| bucket.last);
        }
        let bucket = buckets.entry(device_id).or_insert(Bucket {
            tokens: TOKEN_BURST,
            last: now,
        });
        let elapsed = now.saturating_duration_since(bucket.last).as_secs_f64();
        bucket.tokens = (bucket.tokens + elapsed * TOKEN_REFILL_PER_SEC).min(TOKEN_BURST);
        bucket.last = now;
        if bucket.tokens >= 1.0 {
            bucket.tokens -= 1.0;
            Ok(())
        } else {
            let wait = ((1.0 - bucket.tokens) / TOKEN_REFILL_PER_SEC).ceil() as u64;
            Err(Refused {
                retry_after_secs: wait.max(1),
            })
        }
    }

    /// A slot for one request of `class` from a device; the permit frees it when dropped (the
    /// response body finished). `Always` requests get a permit that counts nothing.
    pub fn enter(self: &Arc<Self>, device_id: i64, class: RouteClass) -> Result<Permit, Refused> {
        if class == RouteClass::Always {
            return Ok(Permit {
                limits: None,
                device_id,
                class,
            });
        }
        let mut in_flight = self.in_flight.lock().unwrap_or_else(|e| e.into_inner());
        let entry = in_flight.entry(device_id).or_default();
        let (count, cap) = match class {
            RouteClass::Long => (&mut entry.long, LONG_IN_FLIGHT),
            _ => (&mut entry.general, GENERAL_IN_FLIGHT),
        };
        if *count >= cap {
            return Err(Refused {
                retry_after_secs: if class == RouteClass::Long { 30 } else { 2 },
            });
        }
        *count += 1;
        Ok(Permit {
            limits: Some(Arc::clone(self)),
            device_id,
            class,
        })
    }

    fn leave(&self, device_id: i64, class: RouteClass) {
        let mut in_flight = self.in_flight.lock().unwrap_or_else(|e| e.into_inner());
        if let Some(entry) = in_flight.get_mut(&device_id) {
            match class {
                RouteClass::Long => entry.long = entry.long.saturating_sub(1),
                _ => entry.general = entry.general.saturating_sub(1),
            }
            if entry.long == 0 && entry.general == 0 {
                in_flight.remove(&device_id);
            }
        }
    }

    /// Whether failed requests from this client are refused right now (429, not 401).
    pub fn ip_blocked(&self, key: &str) -> Option<Refused> {
        self.ip_blocked_at(key, Instant::now())
    }

    fn ip_blocked_at(&self, key: &str, now: Instant) -> Option<Refused> {
        let failures = self.ip_failures.lock().unwrap_or_else(|e| e.into_inner());
        let until = failures.get(key)?.blocked_until?;
        (until > now).then(|| Refused {
            retry_after_secs: (until.duration_since(now).as_secs_f64().ceil() as u64).max(1),
        })
    }

    /// Counts one failed request from this client. `true` when that blocks it.
    pub fn ip_failed(&self, key: &str) -> bool {
        self.ip_failed_at(key, Instant::now())
    }

    fn ip_failed_at(&self, key: &str, now: Instant) -> bool {
        let mut failures = self.ip_failures.lock().unwrap_or_else(|e| e.into_inner());
        if failures.len() >= MAX_KEYS && !failures.contains_key(key) {
            evict_oldest(&mut failures, |f| f.window_start);
        }
        let entry = failures.entry(key.to_string()).or_insert(IpFailures {
            window_start: now,
            count: 0,
            blocked_until: None,
        });
        if entry.blocked_until.is_some_and(|until| until <= now) {
            entry.blocked_until = None;
            entry.count = 0;
            entry.window_start = now;
        }
        if now.saturating_duration_since(entry.window_start) >= IP_WINDOW {
            entry.window_start = now;
            entry.count = 0;
        }
        entry.count += 1;
        if entry.count >= IP_FAILURES && entry.blocked_until.is_none() {
            entry.blocked_until = Some(now + IP_BLOCK);
            return true;
        }
        false
    }

    /// The memory guard over every device request (both listeners).
    pub fn global(&self) -> Arc<tokio::sync::Semaphore> {
        Arc::clone(
            self.global
                .get_or_init(|| Arc::new(tokio::sync::Semaphore::new(GLOBAL_IN_FLIGHT))),
        )
    }

    /// Notes a phone's authenticated request from `client` (`peer` is the TCP peer).
    pub fn record_access(&self, device_id: i64, client: &crate::net::Client) {
        let via = if crate::net::is_tailnet(client.ip) {
            Via::Tailnet
        } else if crate::net::is_loopback(client.ip) {
            Via::Loopback
        } else {
            Via::Public
        };
        let mut access = self.last_access.lock().unwrap_or_else(|e| e.into_inner());
        if access.len() >= MAX_KEYS && !access.contains_key(&device_id) {
            evict_oldest(&mut access, |(last, _)| last.at);
        }
        let (last, ips) = access.entry(device_id).or_insert_with(|| {
            (
                LastAccess {
                    at: Utc::now(),
                    via,
                    public_ips: Vec::new(),
                },
                VecDeque::new(),
            )
        });
        last.at = Utc::now();
        last.via = via;
        if via == Via::Public {
            let ip = client.ip.to_string();
            ips.retain(|known| *known != ip);
            ips.push_front(ip);
            ips.truncate(LAST_IPS);
            last.public_ips = ips.iter().cloned().collect();
        }
        drop(access);
        self.exposure.observe(device_id, client);
    }

    pub fn last_access(&self, device_id: i64) -> Option<LastAccess> {
        self.last_access
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .get(&device_id)
            .map(|(last, _)| last.clone())
    }

    pub fn forget_device(&self, device_id: i64) {
        self.buckets
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .remove(&device_id);
        self.last_access
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .remove(&device_id);
    }

    /// Notes an untrusted peer that sent `X-Forwarded-For` (any device request).
    pub fn observe_peer(&self, client: &crate::net::Client) {
        self.exposure.observe_untrusted_forward(client);
    }

    /// The exposure warning, if any (startup log, the Connection page).
    pub fn exposure_warning(&self) -> Option<String> {
        self.exposure.warning()
    }
}

/// The device page's "last access" line (§3.1), from memory: since this server started.
pub fn last_access_line(last: Option<&LastAccess>) -> String {
    let Some(last) = last else {
        return "Last access: none since this server started.".to_string();
    };
    let at = last.at.format("%Y-%m-%d %H:%M:%S");
    let how = match last.via {
        Via::Tailnet => "over the tailnet".to_string(),
        Via::Loopback => {
            "from this machine (no proxy, or a proxy that sent no client address)".to_string()
        }
        Via::Public => "from outside the tailnet".to_string(),
    };
    let ips = if last.public_ips.is_empty() {
        String::new()
    } else {
        format!(
            " Addresses outside the tailnet, newest first: {}.",
            last.public_ips.join(", ")
        )
    };
    format!("Last access: {at} UTC {how}.{ips}")
}

/// Held for one device request; dropping it frees the in-flight slot.
pub struct Permit {
    limits: Option<Arc<Limits>>,
    device_id: i64,
    class: RouteClass,
}

impl Drop for Permit {
    fn drop(&mut self) {
        if let Some(limits) = &self.limits {
            limits.leave(self.device_id, self.class);
        }
    }
}

/// A response body that holds `guard` until it is done (or dropped): the in-flight slot of an
/// SSE stream or a download lasts as long as the transfer, not just the handler.
pub struct GuardedBody<G> {
    inner: axum::body::Body,
    _guard: G,
}

impl<G: Send + Unpin + 'static> http_body::Body for GuardedBody<G> {
    type Data = axum::body::Bytes;
    type Error = axum::Error;

    fn poll_frame(
        mut self: std::pin::Pin<&mut Self>,
        cx: &mut std::task::Context<'_>,
    ) -> std::task::Poll<Option<Result<http_body::Frame<Self::Data>, Self::Error>>> {
        std::pin::Pin::new(&mut self.inner).poll_frame(cx)
    }

    fn is_end_stream(&self) -> bool {
        self.inner.is_end_stream()
    }

    fn size_hint(&self) -> http_body::SizeHint {
        self.inner.size_hint()
    }
}

pub fn hold_until_done<G: Send + Unpin + 'static>(
    response: axum::response::Response,
    guard: G,
) -> axum::response::Response {
    response.map(|inner| {
        axum::body::Body::new(GuardedBody {
            inner,
            _guard: guard,
        })
    })
}

/// A device response's `Cache-Control`: `no-store` unless the route set one; an `ETag` route
/// without one gets `private, no-cache` (revalidate every time); `private` is added to any other
/// value - everything here is one phone's.
pub fn device_cache_control(existing: Option<&str>, has_etag: bool) -> String {
    match existing {
        None if has_etag => "private, no-cache".to_string(),
        None => "no-store".to_string(),
        Some(value) if value.contains("private") || value.contains("no-store") => value.to_string(),
        Some(value) => format!("private, {value}"),
    }
}

/// The outer layer of every device route (both listeners, enroll included):
/// - [GLOBAL_IN_FLIGHT] requests at once, a memory guard only (503 past it);
/// - [REQUEST_TIMEOUT] for everything but the SSE stream and file downloads;
/// - the response headers: `Cache-Control` (`no-store` unless the route set one; an `ETag` route
///   gets `private, no-cache`; `private` is added to any other), `X-Content-Type-Options: nosniff`
///   and `Content-Security-Policy: default-src 'none'; sandbox` - on the tailnet the phone site
///   shares the admin's hostname, and cookies aren't scoped by port.
pub async fn device_edge(
    axum::extract::State(state): axum::extract::State<crate::AppState>,
    request: axum::extract::Request,
    next: axum::middleware::Next,
) -> axum::response::Response {
    use axum::http::{HeaderValue, StatusCode, header};
    use axum::response::IntoResponse;

    let Ok(permit) = state.limits.global().try_acquire_owned() else {
        return (
            StatusCode::SERVICE_UNAVAILABLE,
            [(header::RETRY_AFTER, "5")],
        )
            .into_response();
    };
    let class = route_class(request.uri().path());
    let mut response = if class == RouteClass::Long {
        next.run(request).await
    } else {
        match tokio::time::timeout(REQUEST_TIMEOUT, next.run(request)).await {
            Ok(response) => response,
            Err(_) => (
                StatusCode::SERVICE_UNAVAILABLE,
                [(header::RETRY_AFTER, "30")],
            )
                .into_response(),
        }
    };
    let headers = response.headers_mut();
    let cache = device_cache_control(
        headers
            .get(header::CACHE_CONTROL)
            .and_then(|v| v.to_str().ok()),
        headers.contains_key(header::ETAG),
    );
    if let Ok(value) = HeaderValue::from_str(&cache) {
        headers.insert(header::CACHE_CONTROL, value);
    }
    headers.insert(
        header::X_CONTENT_TYPE_OPTIONS,
        HeaderValue::from_static("nosniff"),
    );
    headers.insert(
        header::CONTENT_SECURITY_POLICY,
        HeaderValue::from_static("default-src 'none'; sandbox"),
    );
    hold_until_done(response, permit)
}

fn evict_oldest<K: Clone + Eq + std::hash::Hash, V, T: Ord>(
    map: &mut HashMap<K, V>,
    age: impl Fn(&V) -> T,
) {
    if let Some(oldest) = map
        .iter()
        .min_by_key(|(_, value)| age(value))
        .map(|(key, _)| key.clone())
    {
        map.remove(&oldest);
    }
}

/// "Every public request resolves to one private IP" (§3.1): an untrusted proxy, or Docker
/// hiding the client. Two signals, each logged once:
/// - an untrusted peer sent `X-Forwarded-For` (a proxy missing from `TRUSTED_PROXIES`);
/// - two phones' requests from outside the tailnet both resolve to the same private address.
#[derive(Default)]
struct ExposureWatch {
    /// Per device, the private address its last non-tailnet, non-loopback request came from.
    private_seen: Mutex<HashMap<i64, IpAddr>>,
    warning: Mutex<Option<String>>,
    logged_proxy: AtomicBool,
    logged_shared: AtomicBool,
}

impl ExposureWatch {
    fn observe_untrusted_forward(&self, client: &crate::net::Client) {
        if !client.untrusted_forward {
            return;
        }
        let text = format!(
            "{} sends X-Forwarded-For but isn't in TRUSTED_PROXIES: every request through it \
             counts as {} (limits, bans, last access). Add it to TRUSTED_PROXIES if it is your \
             proxy.",
            client.peer, client.peer
        );
        if !self.logged_proxy.swap(true, Ordering::Relaxed) {
            tracing::warn!("{text}");
        }
        *self.warning.lock().unwrap_or_else(|e| e.into_inner()) = Some(text);
    }

    fn observe(&self, device_id: i64, client: &crate::net::Client) {
        if crate::net::is_tailnet(client.ip) || crate::net::is_loopback(client.ip) {
            return;
        }
        let mut seen = self.private_seen.lock().unwrap_or_else(|e| e.into_inner());
        if !crate::net::is_private(client.ip) {
            seen.remove(&device_id);
            return;
        }
        if seen.len() >= 64 && !seen.contains_key(&device_id) {
            seen.clear();
        }
        seen.insert(device_id, client.ip);
        let shared = seen
            .iter()
            .filter(|(id, ip)| **id != device_id && **ip == client.ip)
            .count();
        if shared > 0 {
            let text = format!(
                "Phones reach this server from the one private address {}: a proxy that isn't \
                 in TRUSTED_PROXIES, or Docker's network hiding the clients. Limits and the \
                 device pages can't tell the phones apart.",
                client.ip
            );
            if !self.logged_shared.swap(true, Ordering::Relaxed) {
                tracing::warn!("{text}");
            }
            *self.warning.lock().unwrap_or_else(|e| e.into_inner()) = Some(text);
        }
    }

    fn warning(&self) -> Option<String> {
        self.warning
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .clone()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn routes_are_classed_by_path() {
        assert_eq!(route_class("/api/devices/policy"), RouteClass::Always);
        assert_eq!(route_class("/api/devices/status"), RouteClass::Always);
        assert_eq!(
            route_class("/api/devices/command-result"),
            RouteClass::Always
        );
        assert_eq!(
            route_class("/api/devices/commands/stream"),
            RouteClass::Long
        );
        assert_eq!(
            route_class("/api/devices/apps/7/download"),
            RouteClass::Long
        );
        assert_eq!(route_class("/api/devices/music/files/3"), RouteClass::Long);
        assert_eq!(route_class("/api/devices/apps"), RouteClass::General);
        assert_eq!(
            route_class("/api/devices/music/library"),
            RouteClass::General
        );
    }

    #[test]
    fn device_responses_are_never_shared_caches() {
        assert_eq!(device_cache_control(None, false), "no-store");
        assert_eq!(device_cache_control(None, true), "private, no-cache");
        // The music library's ETag answers say "no-cache" themselves.
        assert_eq!(
            device_cache_control(Some("no-cache"), true),
            "private, no-cache"
        );
        assert_eq!(device_cache_control(Some("no-store"), false), "no-store");
        assert_eq!(
            device_cache_control(Some("private, max-age=60"), false),
            "private, max-age=60"
        );
    }

    #[test]
    fn the_token_bucket_refills() {
        let limits = Limits::default();
        let start = Instant::now();
        for _ in 0..600 {
            assert!(limits.take_token_at(1, start).is_ok());
        }
        let refused = limits.take_token_at(1, start).unwrap_err();
        assert_eq!(refused.retry_after_secs, 1);
        // Another device has its own bucket.
        assert!(limits.take_token_at(2, start).is_ok());
        // Two a second come back.
        let later = start + Duration::from_secs(1);
        assert!(limits.take_token_at(1, later).is_ok());
        assert!(limits.take_token_at(1, later).is_ok());
        assert!(limits.take_token_at(1, later).is_err());
    }

    #[test]
    fn in_flight_caps_per_class() {
        let limits = Arc::new(Limits::default());
        let general: Vec<_> = (0..GENERAL_IN_FLIGHT)
            .map(|_| limits.enter(1, RouteClass::General).unwrap())
            .collect();
        assert!(limits.enter(1, RouteClass::General).is_err());
        // Downloads have their own slots, and policy/status/command-result always get in.
        let long: Vec<_> = (0..LONG_IN_FLIGHT)
            .map(|_| limits.enter(1, RouteClass::Long).unwrap())
            .collect();
        assert!(limits.enter(1, RouteClass::Long).is_err());
        for _ in 0..50 {
            assert!(limits.enter(1, RouteClass::Always).is_ok());
        }
        assert!(limits.enter(2, RouteClass::General).is_ok(), "per device");
        drop(general);
        assert!(limits.enter(1, RouteClass::General).is_ok());
        drop(long);
        assert!(limits.enter(1, RouteClass::Long).is_ok());
    }

    #[test]
    fn ip_failures_block_for_ten_minutes() {
        let limits = Limits::default();
        let start = Instant::now();
        for n in 1..IP_FAILURES {
            assert!(!limits.ip_failed_at("203.0.113.7", start), "failure {n}");
        }
        assert!(limits.ip_blocked_at("203.0.113.7", start).is_none());
        assert!(limits.ip_failed_at("203.0.113.7", start));
        let refused = limits.ip_blocked_at("203.0.113.7", start).unwrap();
        assert_eq!(refused.retry_after_secs, 600);
        assert!(limits.ip_blocked_at("203.0.113.8", start).is_none());
        let after = start + IP_BLOCK + Duration::from_secs(1);
        assert!(limits.ip_blocked_at("203.0.113.7", after).is_none());
        // A slow trickle never blocks: the window starts over.
        let limits = Limits::default();
        for n in 0..100u64 {
            let at = start + Duration::from_secs(n * 60);
            assert!(!limits.ip_failed_at("198.51.100.1", at));
        }
    }

    #[test]
    fn last_access_keeps_five_public_ips() {
        let limits = Limits::default();
        let client = |ip: &str| crate::net::Client {
            ip: ip.parse().unwrap(),
            peer: "127.0.0.1".parse().unwrap(),
            forwarded: true,
            untrusted_forward: false,
        };
        assert_eq!(limits.last_access(1), None);
        for n in 1..=7 {
            limits.record_access(1, &client(&format!("203.0.113.{n}")));
        }
        limits.record_access(1, &client("203.0.113.5"));
        let last = limits.last_access(1).unwrap();
        assert_eq!(last.via, Via::Public);
        assert_eq!(
            last.public_ips,
            [
                "203.0.113.5",
                "203.0.113.7",
                "203.0.113.6",
                "203.0.113.4",
                "203.0.113.3"
            ]
        );
        limits.record_access(1, &client("100.101.102.103"));
        let last = limits.last_access(1).unwrap();
        assert_eq!(last.via, Via::Tailnet);
        assert_eq!(last.public_ips.len(), 5, "kept from before");
    }

    #[test]
    fn the_last_access_line() {
        assert_eq!(
            last_access_line(None),
            "Last access: none since this server started."
        );
        let at = DateTime::from_timestamp(1_790_000_000, 0).unwrap();
        let line = last_access_line(Some(&LastAccess {
            at,
            via: Via::Public,
            public_ips: vec!["203.0.113.7".into(), "198.51.100.2".into()],
        }));
        assert!(line.starts_with("Last access: 2026-"), "{line}");
        assert!(line.contains("from outside the tailnet"));
        assert!(line.ends_with("newest first: 203.0.113.7, 198.51.100.2."));
        let line = last_access_line(Some(&LastAccess {
            at,
            via: Via::Tailnet,
            public_ips: Vec::new(),
        }));
        assert!(line.ends_with("UTC over the tailnet."), "{line}");
    }

    #[test]
    fn two_phones_behind_one_private_address_warn() {
        let limits = Limits::default();
        let client = |ip: &str| crate::net::Client {
            ip: ip.parse().unwrap(),
            peer: ip.parse().unwrap(),
            forwarded: false,
            untrusted_forward: false,
        };
        limits.record_access(1, &client("172.17.0.1"));
        limits.record_access(1, &client("172.17.0.1"));
        assert_eq!(limits.exposure_warning(), None, "one phone can't tell");
        limits.record_access(2, &client("100.101.102.103"));
        assert_eq!(limits.exposure_warning(), None, "tailnet");
        limits.record_access(3, &client("172.17.0.1"));
        assert!(limits.exposure_warning().unwrap().contains("172.17.0.1"));

        let limits = Limits::default();
        limits.observe_peer(&crate::net::Client {
            ip: "172.30.0.5".parse().unwrap(),
            peer: "172.30.0.5".parse().unwrap(),
            forwarded: false,
            untrusted_forward: true,
        });
        assert!(
            limits
                .exposure_warning()
                .unwrap()
                .contains("isn't in TRUSTED_PROXIES")
        );
    }
}
