//! The Apple Wi-Fi lookup through the family's own server (design 125 step 2, §4a; `docs/notes/server/integrations.md`).
//!
//! With Google's location service off, a phone that doesn't know a place itself sends the usable BSSIDs it hears here;
//! the server answers from its sealed cache ([cache]) or asks Apple's Wi-Fi location service the way GrapheneOS's
//! NetworkLocation does ([codec]) - **through a SOCKS5 proxy** when `WIFI_LOOKUP_PROXY` is set (a Tor sidecar or the
//! family's own edge), never falling back to a direct call - and the phone estimates its position itself. This module,
//! its feature (`wifi_lookup`), its tables (migration 0163) and its contract folder (`testdata/wifi_lookup/`) are the
//! whole of it on the server.
//!
//! - **The switch** is the parent's, per phone, off by default (`wifi_lookup_devices.is_on`, the Location page's group,
//!   [page]); the policy's `wifi_lookup: {"on"}`.
//! - **No lookup log**: no device id, no lookup time, no query history - only the sealed rows with their months and
//!   the integration's health. Logs carry counts and Apple's status, never a BSSID, a coordinate or a device id.
//! - **The route** ([route]): `POST /api/devices/wifi-lookup`, its limits, pauses and answers.
//! - **Cells** (design 125b, [cells]): the same route with `{"cells"}`, the same sealed cache keyed by a cell's
//!   canonical form, a call of their own to Apple; no cache oracle (a fixed answer time, no age).

pub mod cache;
pub mod cells;
pub mod codec;
pub mod page;
pub mod route;
#[cfg(test)]
mod tests;

use std::sync::Arc;
use std::sync::atomic::{AtomicU64, Ordering};
use std::time::Duration;

use axum::Router;
use axum::extract::DefaultBodyLimit;
use axum::routing::post;
use serde_json::{Value, json};

use super::{Health, HealthCell, Integration, Prune};
use crate::AppState;
use crate::i18n::Lang;
use crate::music_net::{BoxFut, PhoneAsks, Source, Window};

/// The name: the logs, the contract folder and `integration_health`'s key.
pub const NAME: &str = "wifi_lookup";

/// The tests' fixed answer time for cells (the server's is 5 or 10 s).
#[cfg(test)]
pub const CELL_ANSWER_AFTER_IN_TESTS: Duration = Duration::from_millis(250);

/// Where Apple calls go: the server setting `WIFI_LOOKUP_PROXY`.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum Proxy {
    /// No setting: the calls go from the server's own address (the PWA's ⓘ says so).
    Direct,
    /// `socks5h://host:port` (remote DNS); each call adds its own random credentials.
    Socks(String),
    /// A setting that isn't a `socks5h://` address: every call fails as a proxy failure, never direct.
    Invalid,
}

impl Proxy {
    /// The setting's value read: unset or blank is [Proxy::Direct].
    pub fn parse(value: Option<&str>) -> Proxy {
        let Some(value) = value.map(str::trim).filter(|v| !v.is_empty()) else {
            return Proxy::Direct;
        };
        match reqwest::Url::parse(value) {
            Ok(url)
                if url.scheme() == "socks5h"
                    && url.host_str().is_some_and(|h| !h.is_empty())
                    && url.username().is_empty()
                    && url.password().is_none()
                    && matches!(url.path(), "" | "/")
                    && url.query().is_none() =>
            {
                Proxy::Socks(format!(
                    "socks5h://{}:{}",
                    url.host_str().unwrap_or_default(),
                    url.port().unwrap_or(1080)
                ))
            }
            _ => Proxy::Invalid,
        }
    }
}

/// The hour's counts for the sweep's one info line (code QA L9): no phone, no BSSID, no time.
#[derive(Debug, Default)]
pub struct Counts {
    lookups: AtomicU64,
    asked: AtomicU64,
    wanted: AtomicU64,
    fetched: AtomicU64,
    unanswered: AtomicU64,
    /// Design 125b: the cells named and the cell calls made.
    cells: AtomicU64,
    cell_calls: AtomicU64,
}

impl Counts {
    /// One lookup: the BSSIDs it named, those the cache couldn't answer, whether Apple was asked or the cache
    /// sufficed after all, and those left unanswered.
    pub fn add(&self, asked: usize, wanted: usize, fetched: bool, unanswered: usize) {
        self.lookups.fetch_add(1, Ordering::Relaxed);
        self.asked.fetch_add(asked as u64, Ordering::Relaxed);
        self.wanted.fetch_add(wanted as u64, Ordering::Relaxed);
        if fetched && wanted > 0 {
            self.fetched.fetch_add(1, Ordering::Relaxed);
        }
        self.unanswered
            .fetch_add(unanswered as u64, Ordering::Relaxed);
    }

    /// One cells lookup: the cells it named (canonical) and whether a cell call was made.
    pub fn add_cells(&self, cells: usize, called: bool) {
        self.lookups.fetch_add(1, Ordering::Relaxed);
        self.cells.fetch_add(cells as u64, Ordering::Relaxed);
        if called {
            self.cell_calls.fetch_add(1, Ordering::Relaxed);
        }
    }

    /// The counts since the last take, and back to zero: (lookups, named, not cached, Apple calls, unanswered, cells,
    /// cell calls).
    pub fn take(&self) -> [u64; 7] {
        [
            &self.lookups,
            &self.asked,
            &self.wanted,
            &self.fetched,
            &self.unanswered,
            &self.cells,
            &self.cell_calls,
        ]
        .map(|n| n.swap(0, Ordering::Relaxed))
    }
}

/// The integration's state: the client, the proxy, the limits, the pauses, the key and the health.
pub struct WifiLookup {
    pub(crate) source: Arc<dyn Source>,
    pub(crate) proxy: Proxy,
    /// A lookup that needs Apple is admitted while at most one other is in flight or waiting; a third is `busy`.
    pub(crate) admit: tokio::sync::Semaphore,
    /// One Apple call at a time.
    pub(crate) apple: tokio::sync::Mutex<()>,
    /// How long a second lookup waits for the first's Apple call.
    pub(crate) gate_wait: Duration,
    /// 30 lookups per phone an hour.
    pub(crate) phone_asks: PhoneAsks,
    /// 60 Apple calls an hour and 10 in any 10 minutes, server-wide.
    pub(crate) outbound: Window,
    pub(crate) burst: Window,
    pub(crate) pauses: std::sync::Mutex<route::Pauses>,
    /// GrapheneOS's own throttle: after an answer of 17 or more APs, the next 10 s ask for 8 surrounding.
    pub(crate) throttled_until: std::sync::Mutex<Option<tokio::time::Instant>>,
    /// Design 125b C6: the cell call's own throttle - after 10 towers, the next 10 s ask for 4 in all.
    pub(crate) cell_throttled_until: std::sync::Mutex<Option<tokio::time::Instant>>,
    /// Design 125b C5: the cells each phone named today (the abuse brake).
    pub(crate) cell_days: route::CellDays,
    /// Design 125b C5: a cells answer leaves no sooner than this after the request arrived - the Apple call's whole
    /// deadline (`music_net::PROXIED` through a proxy, [route::CELL_ANSWER_DIRECT] direct).
    pub(crate) cell_answer_after: Duration,
    /// `K`, loaded at the first use.
    pub(crate) key: tokio::sync::OnceCell<Arc<cache::CacheKey>>,
    pub(crate) health: HealthCell,
    pub(crate) counts: Counts,
}

impl WifiLookup {
    /// The server's: the proxy from `WIFI_LOOKUP_PROXY`, logged once at start.
    pub fn from_env(source: Arc<dyn Source>) -> Self {
        let proxy = Proxy::parse(std::env::var("WIFI_LOOKUP_PROXY").ok().as_deref());
        match &proxy {
            Proxy::Direct => tracing::info!(
                "wifi_lookup: no WIFI_LOOKUP_PROXY - Apple lookups go from this server's own address"
            ),
            Proxy::Socks(url) => tracing::info!("wifi_lookup: Apple lookups go through {url}"),
            Proxy::Invalid => tracing::error!(
                "wifi_lookup: WIFI_LOOKUP_PROXY is not a socks5h://host:port address - Apple lookups fail until it \
                 is fixed (never a direct call)"
            ),
        }
        // The gate's wait and Apple's 10 s stay within the phone's 15 s (code QA L5).
        Self::with(source, proxy, Duration::from_secs(3))
    }

    pub fn with(source: Arc<dyn Source>, proxy: Proxy, gate_wait: Duration) -> Self {
        let cell_answer_after = match proxy {
            Proxy::Socks(_) => crate::music_net::PROXIED,
            _ => route::CELL_ANSWER_DIRECT,
        };
        WifiLookup {
            cell_answer_after,
            cell_throttled_until: Default::default(),
            cell_days: Default::default(),
            source,
            proxy,
            admit: tokio::sync::Semaphore::new(2),
            apple: Default::default(),
            gate_wait,
            phone_asks: PhoneAsks::new(route::PHONE_LOOKUPS_AN_HOUR, Duration::from_secs(3600)),
            outbound: Window::new(route::CALLS_AN_HOUR, Duration::from_secs(3600)),
            burst: Window::new(route::BURST_CALLS, route::BURST_WINDOW),
            pauses: Default::default(),
            throttled_until: Default::default(),
            key: Default::default(),
            health: Default::default(),
            counts: Default::default(),
        }
    }

    /// The tests': a short wait at the gate, a short fixed answer time for cells.
    #[cfg(test)]
    pub fn for_tests(source: Arc<dyn Source>) -> Self {
        Self {
            cell_answer_after: CELL_ANSWER_AFTER_IN_TESTS,
            ..Self::with(source, Proxy::Direct, Duration::from_millis(300))
        }
    }

    /// The cache's key, loaded (and made) at the first use. `Err` is logged by the caller.
    pub(crate) async fn key(&self, state: &AppState) -> Result<Arc<cache::CacheKey>, String> {
        self.key
            .get_or_try_init(|| async {
                cache::load_key(&state.db, &state.data_dir.join("keys").join(NAME))
                    .await
                    .map(Arc::new)
            })
            .await
            .cloned()
    }
}

impl Integration for WifiLookup {
    fn name(&self) -> &'static str {
        NAME
    }

    fn device_routes(&self) -> Router<AppState> {
        Router::new().route(
            "/api/devices/wifi-lookup",
            post(route::route).layer(DefaultBodyLimit::max(route::BODY_LIMIT)),
        )
    }

    fn admin_routes(&self) -> Router<AppState> {
        Router::new().route("/devices/{id}/wifi-lookup", post(page::save))
    }

    fn health(&self) -> Health {
        self.health.get()
    }

    fn policy<'a>(
        &'a self,
        state: &'a AppState,
        device_id: i64,
    ) -> BoxFut<'a, Result<Option<(&'static str, Value)>, sqlx::Error>> {
        Box::pin(async move {
            let on = is_on(&state.db, device_id).await?;
            Ok(Some((NAME, json!({ "on": on }))))
        })
    }

    fn location_group<'a>(
        &'a self,
        state: &'a AppState,
        lang: Lang,
        device_id: i64,
        from_device: bool,
    ) -> BoxFut<'a, Result<Option<String>, sqlx::Error>> {
        Box::pin(page::group(state, lang, device_id, from_device))
    }

    fn prune<'a>(&'a self, state: &'a AppState, why: Prune) -> BoxFut<'a, ()> {
        Box::pin(async move {
            if why == Prune::Sweep {
                let [
                    lookups,
                    named,
                    uncached,
                    calls,
                    unanswered,
                    cells,
                    cell_calls,
                ] = self.counts.take();
                if lookups > 0 {
                    tracing::info!(
                        lookups,
                        named,
                        uncached,
                        calls,
                        unanswered,
                        cells,
                        cell_calls,
                        "wifi_lookup: since the last sweep"
                    );
                }
            }
            let result = match why {
                Prune::Sweep => cache::prune(&state.db, cache::month_of(chrono::Utc::now())).await,
                Prune::DeviceDeleted => cache::empty_unless_used(&state.db).await,
            };
            match result {
                Ok(0) => {}
                Ok(rows) => tracing::info!(rows, "wifi_lookup: pruned the cache"),
                Err(err) => tracing::error!(%err, "wifi_lookup: couldn't prune the cache"),
            }
        })
    }
}

/// The phone's switch (off without a row).
pub async fn is_on(db: &sqlx::SqlitePool, device_id: i64) -> Result<bool, sqlx::Error> {
    Ok(
        sqlx::query_scalar::<_, i64>("SELECT is_on FROM wifi_lookup_devices WHERE device_id = ?")
            .bind(device_id)
            .fetch_optional(db)
            .await?
            .unwrap_or(0)
            != 0,
    )
}

/// Saves the switch; `false` when there is no such phone. Nudges the phone.
pub async fn set_on(state: &AppState, device_id: i64, on: bool) -> Result<bool, sqlx::Error> {
    let found = sqlx::query_scalar::<_, i64>("SELECT 1 FROM devices WHERE id = ?")
        .bind(device_id)
        .fetch_optional(&state.db)
        .await?
        .is_some();
    if !found {
        return Ok(false);
    }
    sqlx::query(
        "INSERT INTO wifi_lookup_devices (device_id, is_on) VALUES (?, ?) \
         ON CONFLICT(device_id) DO UPDATE SET is_on = excluded.is_on",
    )
    .bind(device_id)
    .bind(on)
    .execute(&state.db)
    .await?;
    let _ = state.command_notify.send(device_id);
    Ok(true)
}
