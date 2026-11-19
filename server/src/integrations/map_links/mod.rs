//! Map links (design 110, `docs/design/110-map-links.md`): Google Maps and Apple Maps links on the kid's phone open
//! in Organic Maps (or CoMaps) as a pin with the place's name and address. The integration-boundary pilot
//! (`docs/notes/server/integrations.md`): this module, its feature (`map_links`), its tables (migration 0159) and its
//! contract folder (`testdata/map_links/`) are the whole of it on the server.
//!
//! - **The phone parses every link itself.** The server does three things: turns a short link into its long link
//!   (one GET per hop, [resolve]), takes the feature id from it ([links::feature_id]) and looks the place up by that
//!   id ([preview]). The phone never talks to Google or Apple.
//! - **The switch** is the parent's, per phone (`map_link_devices.is_on`, Device › Apps' "Map links" group,
//!   [page]); the policy's `map_links: {"on"}` (always sent: no launcher before it runs anywhere but an emulator,
//!   the user's rule of 2026-11-18, so no capability gates it).
//! - **The phone's report** (`map_links`: the target app, its state, the link steps and what reaches Vibb One) is
//!   cleaned field by field ([clean]) and kept per phone; a pause's report never moves `reach`.
//! - **The counter** (`map_link_lookups`, 30 days): one row per lookup outcome and day, never a link, an id or a text
//!   - the "lookup isn't working" line and C2's health ([MapLinks::health]).

pub mod links;
pub mod page;
pub mod preview;
pub mod resolve;
#[cfg(test)]
mod tests;

use std::sync::Arc;
use std::time::Duration;

use axum::Router;
use axum::extract::DefaultBodyLimit;
use axum::routing::post;
use serde_json::{Value, json};

use super::{Health, HealthCell, Integration, SetupPart};
use crate::AppState;
use crate::i18n::Lang;
use crate::models::StatusReportRequest;
use crate::music_net::{BoxFut, Gated, PhoneAsks, Source};

/// The apps a map link may open, in order (Q3: CoMaps when Organic Maps isn't installed).
pub const TARGETS: [&str; 3] = ["app.organicmaps", "app.comaps.fdroid", "app.comaps"];
/// A target's state on the phone.
pub const TARGET_STATES: [&str; 4] = ["allowed", "hidden", "suspended", "missing"];

/// The integration's state: the client behind its own gate, the cache, the limits and the health.
pub struct MapLinks {
    /// Every call out: short-link hops and lookups, one at a time, 200 ms apart (`resolve::Gate`).
    pub(crate) gate: resolve::Gate,
    /// Answers kept for minutes, in memory only (§8.1).
    pub(crate) cache: resolve::Cache,
    /// 30 resolves per phone an hour.
    pub(crate) phone_asks: PhoneAsks,
    /// 100 calls to Google or Apple an hour, server-wide.
    pub(crate) outbound: resolve::Window,
    /// 10 calls out in any 10 minutes, server-wide (the burst under Google's wall).
    pub(crate) burst: resolve::Window,
    /// Lookups paused until this instant after Google's wall (`resolve::BLOCKED_PAUSE`).
    pub(crate) blocked: std::sync::Mutex<Option<tokio::time::Instant>>,
    /// The whole resolve's deadline (hops and the lookup together).
    pub(crate) deadline: Duration,
    pub(crate) health: HealthCell,
}

impl MapLinks {
    pub fn new(source: Arc<dyn Source>) -> Self {
        Self::with(source, crate::music_net::REQUEST_SPACING)
    }

    /// The tests': no spacing, and a deadline short enough that a held fetch runs out quickly.
    #[cfg(test)]
    pub fn for_tests(source: Arc<dyn Source>) -> Self {
        let mut links = Self::with(source, Duration::ZERO);
        links.deadline = Duration::from_millis(800);
        links
    }

    fn with(source: Arc<dyn Source>, spacing: Duration) -> Self {
        MapLinks {
            gate: resolve::Gate::new(Gated::new(source, spacing)),
            cache: Default::default(),
            phone_asks: PhoneAsks::new(resolve::PHONE_RESOLVES_AN_HOUR, Duration::from_secs(3600)),
            outbound: resolve::Window::new(resolve::CALLS_AN_HOUR, Duration::from_secs(3600)),
            burst: resolve::Window::new(resolve::BURST_CALLS, resolve::BURST_WINDOW),
            blocked: Default::default(),
            deadline: resolve::DEADLINE,
            health: Default::default(),
        }
    }
}

impl Integration for MapLinks {
    fn name(&self) -> &'static str {
        "map_links"
    }

    fn device_routes(&self) -> Router<AppState> {
        Router::new().route(
            "/api/devices/map-links/resolve",
            post(resolve::route).layer(DefaultBodyLimit::max(resolve::BODY_LIMIT)),
        )
    }

    fn admin_routes(&self) -> Router<AppState> {
        Router::new().route("/devices/{id}/map-links", post(page::save))
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
            Ok(Some(("map_links", json!({ "on": on }))))
        })
    }

    fn intake<'a>(
        &'a self,
        state: &'a AppState,
        device_id: i64,
        report: &'a StatusReportRequest,
    ) -> BoxFut<'a, ()> {
        Box::pin(async move {
            let Some(cleaned) = report.map_links.as_ref().and_then(clean) else {
                return;
            };
            if let Err(err) =
                store_report(&state.db, device_id, &cleaned, report.restrictions_paused).await
            {
                tracing::error!(device_id, %err, "map links: couldn't keep the report");
            }
        })
    }

    fn apps_group<'a>(
        &'a self,
        state: &'a AppState,
        lang: Lang,
        device_id: i64,
        from: Option<&'a str>,
        setup_warnings: &'a crate::setup_status::Warnings,
    ) -> BoxFut<'a, Result<Option<String>, sqlx::Error>> {
        Box::pin(page::group(state, lang, device_id, from, setup_warnings))
    }

    fn setup<'a>(
        &'a self,
        state: &'a AppState,
        device_id: i64,
    ) -> BoxFut<'a, Result<SetupPart, sqlx::Error>> {
        Box::pin(async move {
            let on = is_on(&state.db, device_id).await?;
            let report = report(&state.db, device_id).await?;
            Ok(setup_part(on, report.as_ref()))
        })
    }
}

/// Design 114 §2.6: the two one-time steps (§6.2) are 114's rows 15-16. The switch is the need rule's `map_links`
/// input (with a map app installed, which 114 reads from the installed apps); the readings come from the last report:
/// `map_play_links` done when Play services' links read off (`null` unreadable), `map_vibb_links` when every host the
/// parent can select is selected - as the phone's `SetupReadings` reads them.
pub fn setup_part(on: bool, report: Option<&Value>) -> SetupPart {
    let mut part = SetupPart::default();
    if on {
        part.inputs.push("map_links");
    }
    if let Some(report) = report {
        let hosts = |key: &str| report["vibb_hosts"][key].as_i64().unwrap_or(0);
        part.readings.push((
            "map_play_links",
            report["play_services_links"]
                .as_bool()
                .map(|allowed| !allowed),
        ));
        part.readings.push((
            "map_vibb_links",
            Some(hosts("declared") > 0 && hosts("selected") >= hosts("selectable")),
        ));
    }
    part
}

/// The phone's switch (off without a row).
pub async fn is_on(db: &sqlx::SqlitePool, device_id: i64) -> Result<bool, sqlx::Error> {
    Ok(
        sqlx::query_scalar::<_, i64>("SELECT is_on FROM map_link_devices WHERE device_id = ?")
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
        "INSERT INTO map_link_devices (device_id, is_on) VALUES (?, ?) \
         ON CONFLICT(device_id) DO UPDATE SET is_on = excluded.is_on",
    )
    .bind(device_id)
    .bind(on)
    .execute(&state.db)
    .await?;
    let _ = state.command_notify.send(device_id);
    Ok(true)
}

/// The report's `map_links` cleaned field by field (the contract's `clean` rows): every key present, an unknown
/// target `null` (its state `missing`), an unknown state `missing`, a count clipped to 0-64, a missing class `false`.
/// Anything but an object is no report.
pub fn clean(raw: &Value) -> Option<Value> {
    let object = raw.as_object()?;
    let target = object
        .get("target")
        .and_then(Value::as_str)
        .filter(|t| TARGETS.contains(t));
    let state = object
        .get("target_state")
        .and_then(Value::as_str)
        .filter(|s| TARGET_STATES.contains(s) && target.is_some())
        .unwrap_or("missing");
    let play = object.get("play_services_links").and_then(Value::as_bool);
    let count = |key: &str| {
        object
            .get("vibb_hosts")
            .and_then(|h| h.get(key))
            .and_then(Value::as_i64)
            .map_or(0, |n| n.clamp(0, 64))
    };
    let class = |key: &str| {
        object
            .get("reach")
            .and_then(|r| r.get(key))
            .and_then(Value::as_bool)
            .unwrap_or(false)
    };
    Some(json!({
        "target": target,
        "target_state": state,
        "play_services_links": play,
        "vibb_hosts": {
            "selected": count("selected"),
            "selectable": count("selectable"),
            "declared": count("declared"),
        },
        "reach": {"short": class("short"), "google": class("google"), "apple": class("apple")},
    }))
}

/// Keeps a cleaned report. A report sent during a pause (everything is unhidden then) keeps the last `reach` from
/// outside one (§7.5). `vibb_hosts` is the paused report's own: it doesn't depend on what is unhidden, and step 2 is done
/// in a pause or the setup hold, so its reading (design 114's row 16) must tick off then (code QA M1).
async fn store_report(
    db: &sqlx::SqlitePool,
    device_id: i64,
    report: &Value,
    paused: bool,
) -> Result<(), sqlx::Error> {
    let mut stored = report.clone();
    if paused {
        let before: Option<Option<String>> =
            sqlx::query_scalar("SELECT report_json FROM map_link_devices WHERE device_id = ?")
                .bind(device_id)
                .fetch_optional(db)
                .await?;
        let before: Option<Value> = before.flatten().and_then(|j| serde_json::from_str(&j).ok());
        if let Some(kept) = before.as_ref().and_then(|b| b.get("reach")) {
            stored["reach"] = kept.clone();
        }
    }
    sqlx::query(
        "INSERT INTO map_link_devices (device_id, report_json, reported_at) \
         VALUES (?, ?, datetime('now')) ON CONFLICT(device_id) DO UPDATE SET \
         report_json = excluded.report_json, reported_at = excluded.reported_at",
    )
    .bind(device_id)
    .bind(stored.to_string())
    .execute(db)
    .await?;
    Ok(())
}

/// The phone's last report, cleaned (`None` before its first).
pub async fn report(db: &sqlx::SqlitePool, device_id: i64) -> Result<Option<Value>, sqlx::Error> {
    let json: Option<Option<String>> =
        sqlx::query_scalar("SELECT report_json FROM map_link_devices WHERE device_id = ?")
            .bind(device_id)
            .fetch_optional(db)
            .await?;
    Ok(json.flatten().and_then(|j| serde_json::from_str(&j).ok()))
}
