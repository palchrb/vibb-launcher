//! Integrations behind one boundary (the pilot: map links, design 110; `docs/notes/server/integrations.md`).
//!
//! An integration is a thing this server does with an outside service on the phones' behalf. Each one:
//!
//! - lives in its own module here, behind its own Cargo feature (on by default), so a build can leave it out;
//! - keeps its own tables (its own migration, its own table names) and its own contract folder: the contract both
//!   sides read in the repo's `contracts/integrations/<name>/contract.json` (design 133), the server's own rows in
//!   `testdata/<name>/`;
//! - reaches the core only through [Integration]: its routes, its policy key, its part of a status report, its group
//!   on Device › Apps and its health. The core never names it.
//!
//! The trait is kept to what the first integration needs; Spotify, Storytel, Sonos and NRK move in later and add only
//! what they need. Every hook but the name, the routes and the health has a do-nothing default.

// A build without any integration (`--no-default-features`) keeps the boundary's pieces unused.
#![cfg_attr(
    not(any(feature = "map_links", feature = "wifi_lookup")),
    allow(dead_code, unused_imports)
)]

use std::sync::Arc;

use axum::Router;
use chrono::{DateTime, Utc};

use crate::AppState;
use crate::i18n::Lang;
use crate::models::StatusReportRequest;
use crate::music_net::BoxFut;

#[cfg(feature = "map_links")]
pub mod map_links;
#[cfg(feature = "wifi_lookup")]
pub mod wifi_lookup;

/// The last success and the last failure of an integration's calls out (C2, design 116, reads it for its generic
/// health rows). In memory: a restart starts it empty.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct Health {
    /// The last call out that did what it should.
    pub last_ok: Option<DateTime<Utc>>,
    /// The last one that didn't, with its outcome's code (the integration's own words for it, never a link or an id).
    pub last_error: Option<(DateTime<Utc>, &'static str)>,
}

/// [Health] kept as it changes: one lock, two fields.
#[derive(Default)]
pub struct HealthCell(std::sync::Mutex<Health>);

impl HealthCell {
    pub fn ok(&self, at: DateTime<Utc>) {
        self.0.lock().unwrap_or_else(|e| e.into_inner()).last_ok = Some(at);
    }

    pub fn error(&self, at: DateTime<Utc>, code: &'static str) {
        self.0.lock().unwrap_or_else(|e| e.into_inner()).last_error = Some((at, code));
    }

    pub fn get(&self) -> Health {
        self.0.lock().unwrap_or_else(|e| e.into_inner()).clone()
    }
}

/// An integration's part of the phone's setup steps (design 114): the need rule's inputs it turns on (their `when`
/// names, design 110's `map_links`) and its steps' readings, from what it keeps (`setup_status::inputs_of`,
/// `setup_status::readings_of`).
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct SetupPart {
    pub inputs: Vec<&'static str>,
    pub readings: Vec<(&'static str, Option<bool>)>,
}

/// Why [Integration::prune] runs.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Prune {
    /// The server's sweep (`retention::run_pruning`, at the start and every hour).
    Sweep,
    /// A phone was deleted (its own rows went with it by `ON DELETE CASCADE`).
    DeviceDeleted,
    /// A phone's location was turned off (`locate::update_location_policy`'s Off).
    LocationOff,
}

/// One integration, as the core sees it.
pub trait Integration: Send + Sync {
    /// Its name: the logs, its contract folder and C2's health rows.
    fn name(&self) -> &'static str;

    /// Its device routes (`/api/devices/<name>/...`), behind the device token and the device API's limits.
    fn device_routes(&self) -> Router<AppState>;

    /// Its admin routes, behind the admin session like every page.
    fn admin_routes(&self) -> Router<AppState>;

    /// The last success and failure of its calls out.
    fn health(&self) -> Health;

    /// Its key in this phone's policy, if any. A read error fails the
    /// policy, as every other part's does.
    fn policy<'a>(
        &'a self,
        _state: &'a AppState,
        _device_id: i64,
    ) -> BoxFut<'a, Result<Option<(&'static str, serde_json::Value)>, sqlx::Error>> {
        Box::pin(async { Ok(None) })
    }

    /// Its part of a status report, received now. Never fails the report: an error is logged.
    fn intake<'a>(
        &'a self,
        _state: &'a AppState,
        _device_id: i64,
        _report: &'a StatusReportRequest,
    ) -> BoxFut<'a, ()> {
        Box::pin(async {})
    }

    /// Its group on Device › Apps, rendered (`None`: nothing to show for this phone). `from` is the page's
    /// `AppsFrom` value, for the group's forms; `setup_warnings` the page's design 114 lines, for its own section.
    fn apps_group<'a>(
        &'a self,
        _state: &'a AppState,
        _lang: Lang,
        _device_id: i64,
        _from: Option<&'a str>,
        _setup_warnings: &'a crate::setup_status::Warnings,
    ) -> BoxFut<'a, Result<Option<String>, sqlx::Error>> {
        Box::pin(async { Ok(None) })
    }

    /// Its group on the phone's Location page, rendered (`None`: nothing to show). `from_device`: the page was opened
    /// from the phone's page, for the group's forms.
    fn location_group<'a>(
        &'a self,
        _state: &'a AppState,
        _lang: Lang,
        _device_id: i64,
        _from_device: bool,
    ) -> BoxFut<'a, Result<Option<String>, sqlx::Error>> {
        Box::pin(async { Ok(None) })
    }

    /// Drops what it keeps past its time. Never fails: an error is logged.
    fn prune<'a>(&'a self, _state: &'a AppState, _why: Prune) -> BoxFut<'a, ()> {
        Box::pin(async {})
    }

    /// Its part of this phone's setup steps (design 114). A read error leaves its part out (logged by the caller).
    fn setup<'a>(
        &'a self,
        _state: &'a AppState,
        _device_id: i64,
    ) -> BoxFut<'a, Result<SetupPart, sqlx::Error>> {
        Box::pin(async { Ok(SetupPart::default()) })
    }
}

/// Every integration this build has (`AppState.integrations`).
#[derive(Clone)]
pub struct Integrations {
    #[cfg(feature = "map_links")]
    pub map_links: Arc<map_links::MapLinks>,
    #[cfg(feature = "wifi_lookup")]
    pub wifi_lookup: Arc<wifi_lookup::WifiLookup>,
}

impl Integrations {
    /// The real ones: each with its own client to the outside.
    pub fn new() -> Self {
        #[allow(unused_variables)]
        let source: Arc<dyn crate::music_net::Source> =
            Arc::new(crate::music_net::HttpSource::new());
        Integrations {
            #[cfg(feature = "map_links")]
            map_links: Arc::new(map_links::MapLinks::new(source.clone())),
            #[cfg(feature = "wifi_lookup")]
            wifi_lookup: Arc::new(wifi_lookup::WifiLookup::from_env(source.clone())),
        }
    }

    /// The tests': every call out goes to `source` (the canned fetch), without the spacing.
    #[cfg(test)]
    pub fn with_source(source: Arc<dyn crate::music_net::Source>) -> Self {
        let _ = &source;
        Integrations {
            #[cfg(feature = "map_links")]
            map_links: Arc::new(map_links::MapLinks::for_tests(source.clone())),
            #[cfg(feature = "wifi_lookup")]
            wifi_lookup: Arc::new(wifi_lookup::WifiLookup::for_tests(source.clone())),
        }
    }

    /// Every integration, in a fixed order (one push per feature).
    #[allow(clippy::vec_init_then_push)]
    pub fn all(&self) -> Vec<&dyn Integration> {
        #[allow(unused_mut)]
        let mut all: Vec<&dyn Integration> = Vec::new();
        #[cfg(feature = "map_links")]
        all.push(self.map_links.as_ref());
        #[cfg(feature = "wifi_lookup")]
        all.push(self.wifi_lookup.as_ref());
        all
    }

    /// Every integration's device routes, merged.
    pub fn device_routes(&self) -> Router<AppState> {
        self.all().into_iter().fold(Router::new(), |router, one| {
            router.merge(one.device_routes())
        })
    }

    /// Every integration's admin routes, merged.
    pub fn admin_routes(&self) -> Router<AppState> {
        self.all().into_iter().fold(Router::new(), |router, one| {
            router.merge(one.admin_routes())
        })
    }

    /// Every integration's health, by name (C2).
    pub fn health(&self) -> Vec<(&'static str, Health)> {
        self.all()
            .into_iter()
            .map(|one| (one.name(), one.health()))
            .collect()
    }

    /// The policy's integration keys for this phone. The first read error fails the policy.
    pub async fn policy(
        &self,
        state: &AppState,
        device_id: i64,
    ) -> Result<serde_json::Map<String, serde_json::Value>, sqlx::Error> {
        let mut keys = serde_json::Map::new();
        for one in self.all() {
            if let Some((key, value)) = one.policy(state, device_id).await? {
                keys.insert(key.to_string(), value);
            }
        }
        Ok(keys)
    }

    /// Hands a status report to every integration.
    pub async fn intake(&self, state: &AppState, device_id: i64, report: &StatusReportRequest) {
        for one in self.all() {
            one.intake(state, device_id, report).await;
        }
    }

    /// Every integration's group on Device › Apps for this phone, rendered, in order.
    pub async fn apps_groups(
        &self,
        state: &AppState,
        lang: Lang,
        device_id: i64,
        from: Option<&str>,
        setup_warnings: &crate::setup_status::Warnings,
    ) -> Result<Vec<String>, sqlx::Error> {
        let mut groups = Vec::new();
        for one in self.all() {
            if let Some(group) = one
                .apps_group(state, lang, device_id, from, setup_warnings)
                .await?
            {
                groups.push(group);
            }
        }
        Ok(groups)
    }

    /// Every integration's group on the phone's Location page, rendered, in order.
    pub async fn location_groups(
        &self,
        state: &AppState,
        lang: Lang,
        device_id: i64,
        from_device: bool,
    ) -> Result<Vec<String>, sqlx::Error> {
        let mut groups = Vec::new();
        for one in self.all() {
            if let Some(group) = one
                .location_group(state, lang, device_id, from_device)
                .await?
            {
                groups.push(group);
            }
        }
        Ok(groups)
    }

    /// Every integration's prune.
    pub async fn prune(&self, state: &AppState, why: Prune) {
        for one in self.all() {
            one.prune(state, why).await;
        }
    }

    /// Every integration's part of this phone's setup steps, joined. Never fails: an integration whose read fails is
    /// left out and logged, so its steps read as not applying (as before its first report).
    pub async fn setup(&self, state: &AppState, device_id: i64) -> SetupPart {
        let mut part = SetupPart::default();
        for one in self.all() {
            match one.setup(state, device_id).await {
                Ok(one_part) => {
                    part.inputs.extend(one_part.inputs);
                    part.readings.extend(one_part.readings);
                }
                Err(err) => {
                    tracing::error!(device_id, %err, integration = one.name(), "can't read the setup part");
                }
            }
        }
        // Design 139 §5.3: the core's own input beside the integrations' - an app group limits some day.
        if crate::app_groups::setup_input(&state.db, device_id).await {
            part.inputs.push(crate::setup_status::APP_GROUPS_INPUT);
        }
        part
    }
}

impl Default for Integrations {
    fn default() -> Self {
        Self::new()
    }
}
