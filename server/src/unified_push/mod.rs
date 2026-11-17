//! Vibb as the UnifiedPush distributor (design 108, `docs/design/108-unified-push.md`): the server is the push server
//! behind the endpoints the launcher hands to the phone's apps, and a Matrix push gateway (variant A: ntfy's logic
//! ported, `README.md`).
//!
//! - **The public routes** ([routes], on the phone listener only, behind [edge::edge]): `POST /up/{secret}`
//!   ([publish]) and `GET`/`POST /_matrix/push/v1/notify` ([matrix]). They make no outbound request at all: the
//!   gateway only queues for our own registrations.
//! - **The device API** ([device], bearer routes in `device_routes.rs`): a registration, its deletion, the pull.
//! - **The store** ([store]): registrations by the SHA-256 of their secret, the queue, the tombstones.
//! - **The nudge** ([notify]): a content-free `up` event on the phone's command stream, coalesced on the trailing
//!   edge; the phone fetches the queue.
//! - **The policy's `unified_push`** ([policy]) for a launcher with [CAPABILITY]; every allowlist writer calls
//!   [prune_disallowed].
//! - **The public origin** (`unified_push_settings`, `/settings/push`): saving one turns app push on for every phone
//!   whose own switch is on (on by default, Q3/A1); clearing it deletes every registration. A changed origin keeps the
//!   previous one accepted for [PREVIOUS_ORIGIN_DAYS] (M4).

pub mod device;
pub mod edge;
pub mod health;
pub mod matrix;
pub mod notify;
pub mod publish;
pub mod rate;
pub mod store;
pub mod vapid;

use std::collections::{HashMap, VecDeque};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use axum::Router;
use axum::http::{StatusCode, header};
use axum::middleware::from_fn_with_state;
use axum::response::{IntoResponse, Response};
use axum::routing::{get, post};
use serde::Serialize;

use crate::AppState;

/// The launcher's capability: it reads the policy's `unified_push` and talks to the device API (§3.7).
pub const CAPABILITY: &str = "unified_push_v1";
/// The previous origin is accepted this long after a change (M4).
pub const PREVIOUS_ORIGIN_DAYS: i64 = 30;
/// Registrations per app and per phone (§5.1: below the spec's "over 1000" - a runaway app must not mint thousands
/// of public endpoints).
pub const PER_APP: i64 = 100;
pub const PER_PHONE: i64 = 300;
/// New registrations of one app on one phone an hour (L10, `busy`).
pub const NEW_PER_HOUR: usize = 20;
/// New registrations on one phone an hour, every app together (R1-SR L5, `busy` too).
pub const NEW_PER_PHONE_HOUR: usize = 60;
/// Apps known to use UnifiedPush, for the PWA's "no push" warning (A3): the package, its name and whether it picks
/// Vibb One by itself once opened (FluffyChat, 108b §6.1; Element X's provider is switched by hand in the app).
pub const KNOWN_UP_APPS: &[(&str, &str, bool)] = &[
    ("io.element.android.x", "Element X", false),
    ("chat.fluffy.fluffychat", "FluffyChat", true),
];

/// The policy's `unified_push` (§3.8), only to a launcher whose last report claims [CAPABILITY].
#[derive(Serialize, Debug, Clone, PartialEq, Eq)]
pub struct UpPolicy {
    /// The phone's switch (on by default) and a public origin set.
    pub on: bool,
    /// The current public origin, `null` when unset (M4).
    pub origin: Option<String>,
    /// The phone's registration keys the server holds, oldest first.
    pub regs: Vec<String>,
    /// The packages the parent stopped on this phone, sorted.
    pub stopped: Vec<String>,
    /// The registrations the phone asks its app to check again (108b §3.3, §4.4), sorted by `reg`.
    pub refresh: Vec<Refresh>,
}

/// One `refresh` row: `why` is `outside` (the app's pushes come through an outside gateway) or `refused` (Q3 (d): a
/// push without the registered key was refused and no verified one came after).
#[derive(Serialize, Debug, Clone, PartialEq, Eq)]
pub struct Refresh {
    pub reg: String,
    pub why: &'static str,
}

/// The server-wide row (`unified_push_settings`, migration 0152).
#[derive(sqlx::FromRow, Debug, Clone, Default, PartialEq, Eq)]
pub struct Settings {
    pub public_origin: Option<String>,
    pub last_discovery_at: Option<String>,
}

/// What app push keeps in memory (`AppState::unified_push`): the `up` channel and its coalescing, the buckets, the
/// edge's slots and the per-app churn count. Nothing here is a secret.
pub struct UnifiedPush {
    /// The phones' `up` nudges (`device_api::commands_stream` merges it; design 91's `/locked/stream` never does).
    pub up_notify: tokio::sync::broadcast::Sender<i64>,
    nudge: Arc<dyn notify::Nudge>,
    coalescer: Arc<notify::Coalescer>,
    pub rates: rate::Rates,
    in_flight: Arc<tokio::sync::Semaphore>,
    body_reads: Arc<tokio::sync::Semaphore>,
    per_client: Mutex<HashMap<String, u32>>,
    new_registrations: Mutex<HashMap<(i64, String), VecDeque<Instant>>>,
    discovery_noted: Mutex<Option<Instant>>,
    /// The last [SERVED_KEPT] instants per phone at which the policy was answered (200 or 304; design 108c §1.1).
    /// In memory: it costs no write, and a restart forgets them.
    policy_served: Mutex<HashMap<i64, VecDeque<chrono::DateTime<chrono::Utc>>>>,
}

/// How many served-policy instants are kept per phone.
const SERVED_KEPT: usize = 4;

impl Default for UnifiedPush {
    fn default() -> Self {
        let (up_notify, _) = tokio::sync::broadcast::channel(64);
        UnifiedPush {
            nudge: Arc::new(up_notify.clone()),
            up_notify,
            coalescer: Default::default(),
            rates: Default::default(),
            in_flight: Arc::new(tokio::sync::Semaphore::new(edge::IN_FLIGHT)),
            body_reads: Arc::new(tokio::sync::Semaphore::new(edge::BODY_READS)),
            per_client: Default::default(),
            new_registrations: Default::default(),
            discovery_noted: Default::default(),
            policy_served: Default::default(),
        }
    }
}

/// One client's slot in [edge::PER_CLIENT]; freed when dropped.
pub struct ClientSlot<'a> {
    up: &'a UnifiedPush,
    key: String,
}

impl Drop for ClientSlot<'_> {
    fn drop(&mut self) {
        let mut slots = self.up.per_client.lock().unwrap_or_else(|e| e.into_inner());
        if let Some(count) = slots.get_mut(&self.key) {
            *count = count.saturating_sub(1);
            if *count == 0 {
                slots.remove(&self.key);
            }
        }
    }
}

const HOUR: Duration = Duration::from_secs(60 * 60);

impl UnifiedPush {
    pub fn subscribe(&self) -> tokio::sync::broadcast::Receiver<i64> {
        self.up_notify.subscribe()
    }

    /// A push for `device_id` was committed: its `up`, coalesced (M2).
    pub fn pushed(&self, device_id: i64) {
        self.coalescer.pushed(device_id, Arc::clone(&self.nudge));
    }

    /// The phone's policy was answered (200 or 304), now.
    pub fn note_policy_served(&self, device_id: i64) {
        self.note_policy_served_at(device_id, chrono::Utc::now());
    }

    pub fn note_policy_served_at(&self, device_id: i64, at: chrono::DateTime<chrono::Utc>) {
        let mut served = self.policy_served.lock().unwrap_or_else(|e| e.into_inner());
        let kept = served.entry(device_id).or_default();
        kept.push_back(at);
        while kept.len() > SERVED_KEPT {
            kept.pop_front();
        }
    }

    /// How many of the kept instants fall in `[from, to]`.
    pub fn policies_served(
        &self,
        device_id: i64,
        from: chrono::DateTime<chrono::Utc>,
        to: chrono::DateTime<chrono::Utc>,
    ) -> usize {
        self.policy_served
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .get(&device_id)
            .map_or(0, |kept| {
                kept.iter().filter(|at| **at >= from && **at <= to).count()
            })
    }

    fn in_flight(&self) -> Arc<tokio::sync::Semaphore> {
        Arc::clone(&self.in_flight)
    }

    fn body_reads(&self) -> Arc<tokio::sync::Semaphore> {
        Arc::clone(&self.body_reads)
    }

    fn enter_client(&self, key: &str) -> Option<ClientSlot<'_>> {
        let mut slots = self.per_client.lock().unwrap_or_else(|e| e.into_inner());
        let count = slots.entry(key.to_string()).or_insert(0);
        if *count >= edge::PER_CLIENT {
            return None;
        }
        *count += 1;
        Some(ClientSlot {
            up: self,
            key: key.to_string(),
        })
    }

    /// A new registration of `package` on this phone, checked and counted under one lock (R1-SR L4): `false`
    /// (`busy`) when the app made [NEW_PER_HOUR] within the hour, or the phone [NEW_PER_PHONE_HOUR] (L5). A counted
    /// one whose insert then fails is taken back ([UnifiedPush::undo_new_registration]).
    fn admit_new_registration(&self, device_id: i64, package: &str) -> bool {
        let mut counts = self
            .new_registrations
            .lock()
            .unwrap_or_else(|e| e.into_inner());
        if counts.len() >= crate::limits::MAX_KEYS {
            counts.retain(|_, times| times.back().is_some_and(|t| t.elapsed() < HOUR));
        }
        let mut of_phone = 0;
        let mut of_app = 0;
        for ((device, app), times) in counts.iter_mut() {
            if *device != device_id {
                continue;
            }
            while times.front().is_some_and(|t| t.elapsed() >= HOUR) {
                times.pop_front();
            }
            of_phone += times.len();
            if app == package {
                of_app = times.len();
            }
        }
        if of_app >= NEW_PER_HOUR || of_phone >= NEW_PER_PHONE_HOUR {
            return false;
        }
        let times = counts.entry((device_id, package.to_string())).or_default();
        times.push_back(Instant::now());
        while times.len() > NEW_PER_PHONE_HOUR {
            times.pop_front();
        }
        true
    }

    fn undo_new_registration(&self, device_id: i64, package: &str) {
        let mut counts = self
            .new_registrations
            .lock()
            .unwrap_or_else(|e| e.into_inner());
        if let Some(times) = counts.get_mut(&(device_id, package.to_string())) {
            times.pop_back();
        }
    }

    /// A discovery GET: its time for "last seen from outside", written at most once a minute.
    async fn note_discovery(&self, db: &sqlx::SqlitePool) {
        {
            let mut noted = self
                .discovery_noted
                .lock()
                .unwrap_or_else(|e| e.into_inner());
            if noted.is_some_and(|at| at.elapsed() < Duration::from_secs(60)) {
                return;
            }
            *noted = Some(Instant::now());
        }
        if let Err(err) = sqlx::query(
            "UPDATE unified_push_settings SET last_discovery_at = datetime('now') WHERE id = 1",
        )
        .execute(db)
        .await
        {
            tracing::warn!(%err, "app push: couldn't note a discovery");
        }
    }
}

/// The public push routes (§3.2), merged into the phone listener alone (`build_device_router`): the admin listener
/// never serves them. Other methods on them are the listener's bare 404.
pub fn routes(state: AppState) -> Router<AppState> {
    Router::new()
        .route(
            "/up/{secret}",
            post(publish::publish).fallback(crate::not_found),
        )
        .route(
            "/_matrix/push/v1/notify",
            get(matrix::discovery)
                .post(matrix::notify)
                .fallback(crate::not_found),
        )
        .layer(from_fn_with_state(state, edge::edge))
}

/// 429 with `Retry-After`.
pub fn too_many(refused: crate::limits::Refused) -> Response {
    (
        StatusCode::TOO_MANY_REQUESTS,
        [(header::RETRY_AFTER, refused.retry_after_secs.to_string())],
    )
        .into_response()
}

/// The answer to a push the VAPID check refused (108b §4.4, Q3 (c)): 401 for no signature, 403 for a wrong key or a bad
/// signature, 503 with `Retry-After: 300` for a correctly signed push whose time or address is off (our clock or origin
/// may be at fault, so the sender keeps its subscription).
pub fn vapid_refusal(outcome: vapid::Outcome) -> Response {
    match outcome {
        vapid::Outcome::Missing => (
            StatusCode::UNAUTHORIZED,
            [(header::WWW_AUTHENTICATE, "vapid")],
        )
            .into_response(),
        vapid::Outcome::Time | vapid::Outcome::Address => (
            StatusCode::SERVICE_UNAVAILABLE,
            [(header::RETRY_AFTER, "300")],
        )
            .into_response(),
        _ => StatusCode::FORBIDDEN.into_response(),
    }
}

/// The answer when the stored key's point check couldn't run (L1): the push is neither taken unchecked nor judged.
pub fn vapid_unavailable() -> Response {
    (
        StatusCode::SERVICE_UNAVAILABLE,
        [(header::RETRY_AFTER, "300")],
    )
        .into_response()
}

/// A push the routes accepted.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Accepted {
    Queued(String),
    /// TTL 0 while the phone's stream is closed: answered 201, never stored.
    Dropped,
}

/// Why [accept] refused a push: its answer is the route's ([IntoResponse]).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Refusal {
    /// The registration's or the phone's bucket is empty: 429.
    TooMany(crate::limits::Refused),
    /// The server-wide store is full: 507.
    Full,
    /// The database failed: 503, which every sender retries.
    Failed,
}

impl IntoResponse for Refusal {
    fn into_response(self) -> Response {
        match self {
            Refusal::TooMany(refused) => too_many(refused),
            Refusal::Full => StatusCode::INSUFFICIENT_STORAGE.into_response(),
            Refusal::Failed => StatusCode::SERVICE_UNAVAILABLE.into_response(),
        }
    }
}

/// One push to a live registration, after the route's own checks: charged to the phone and the registration (429),
/// queued (507 when the store is full), then the phone's `up`.
pub async fn accept(
    state: &AppState,
    registration: &store::Registration,
    push: &store::Push<'_>,
) -> Result<Accepted, Refusal> {
    if let Err(refused) = state
        .unified_push
        .rates
        .charge(registration.device_id, registration.id)
    {
        return Err(Refusal::TooMany(refused));
    }
    let stream = stream_generation(state, registration.device_id);
    if push.ttl_secs == 0 && stream.is_none() {
        // A verified push that wasn't stored still proves the key works (108c L3).
        store::end_refusal_run(&state.db, registration.id).await;
        return Ok(Accepted::Dropped);
    }
    let now_ms = chrono::Utc::now().timestamp_millis();
    match store::enqueue(&state.db, registration, push, now_ms, stream).await {
        Ok(store::Queued::Key(key)) => {
            tracing::debug!(
                device_id = registration.device_id,
                registration = registration.id,
                "app push queued"
            );
            state.unified_push.pushed(registration.device_id);
            Ok(Accepted::Queued(key))
        }
        Ok(store::Queued::Full) => {
            tracing::warn!("app push: the queue's store is full (507)");
            Err(Refusal::Full)
        }
        Err(err) => {
            tracing::error!(%err, "app push: couldn't queue a push");
            Err(Refusal::Failed)
        }
    }
}

/// Design 115 §2.8: the report's `unified_push`, cleaned for `device_status.up_held` - its `held` when that is a word of
/// 1-32 characters `[a-z_]` other than `none` (a reason a newer launcher adds still reads as held, on purpose); `None`
/// for `none` and anything else.
pub fn clean_report(value: &serde_json::Value) -> Option<String> {
    let held = value.get("held")?.as_str()?;
    let word =
        (1..=32).contains(&held.len()) && held.bytes().all(|b| b.is_ascii_lowercase() || b == b'_');
    (word && held != "none").then(|| held.to_string())
}

/// The phone's stream generation (design 108c §7.1): `StreamState::since` in ms while a stream is open, else `None`.
/// It moves whenever a stream opens, so a push handed out on the generation it was accepted on went to a stream that
/// was open the whole time.
pub fn stream_generation(state: &AppState, device_id: i64) -> Option<i64> {
    state
        .command_streams
        .state(device_id)
        .filter(|s| s.open > 0)
        .map(|s| s.since.timestamp_millis())
}

/// The settings row; its absence reads as unset.
pub async fn settings<'e>(db: impl sqlx::SqliteExecutor<'e>) -> Result<Settings, sqlx::Error> {
    Ok(sqlx::query_as::<_, Settings>(
        "SELECT public_origin, last_discovery_at FROM unified_push_settings WHERE id = 1",
    )
    .fetch_optional(db)
    .await?
    .unwrap_or_default())
}

/// The origins a gateway pushkey may name: the current one, and every one it replaced until that one's
/// [PREVIOUS_ORIGIN_DAYS] end (M4, R1-SR L7).
pub async fn accepted_origins(db: &sqlx::SqlitePool) -> Result<Vec<String>, sqlx::Error> {
    let current = settings(db).await?.public_origin;
    let previous = previous_origins(db).await?;
    Ok(current
        .into_iter()
        .chain(previous.into_iter().map(|(origin, _)| origin))
        .collect())
}

/// The replaced origins still accepted, with each one's end (`until_at`), the soonest end first.
pub async fn previous_origins(db: &sqlx::SqlitePool) -> Result<Vec<(String, String)>, sqlx::Error> {
    sqlx::query_as(
        "SELECT origin, until_at FROM up_previous_origins WHERE until_at > datetime('now') \
         ORDER BY until_at, origin",
    )
    .fetch_all(db)
    .await
}

/// The policy's `unified_push` for a phone whose last report has `capabilities`: `None` (the key left out) without
/// [CAPABILITY]. A read error fails the policy (the phone keeps its cache), never a made-up state.
pub async fn policy(
    db: &sqlx::SqlitePool,
    device_id: i64,
    capabilities: &[String],
) -> Result<Option<UpPolicy>, sqlx::Error> {
    if !capabilities.iter().any(|c| c == CAPABILITY) {
        return Ok(None);
    }
    let origin = settings(db).await?.public_origin;
    let switch = switch(db, device_id).await?;
    let regs: Vec<String> =
        sqlx::query_scalar("SELECT reg FROM up_registrations WHERE device_id = ? ORDER BY id")
            .bind(device_id)
            .fetch_all(db)
            .await?;
    // Marked (§3.2): an outside push within 7 days and no push through our own gateway since. Refused (§4.4): a
    // refusal within a day and no accepted push after it (a keyed registration accepts only verified pushes).
    let refresh: Vec<(String, String)> = sqlx::query_as(
        "SELECT reg, CASE WHEN vapid_refused_at > datetime('now', '-1 day') \
           AND (last_push_at IS NULL OR last_push_at < vapid_refused_at) \
           THEN 'refused' ELSE 'outside' END FROM up_registrations WHERE device_id = ?1 AND ( \
           (outside_gateway_at > datetime('now', '-7 days') \
            AND (gateway_at IS NULL OR gateway_at < outside_gateway_at)) \
           OR (vapid_refused_at > datetime('now', '-1 day') \
            AND (last_push_at IS NULL OR last_push_at < vapid_refused_at))) ORDER BY reg",
    )
    .bind(device_id)
    .fetch_all(db)
    .await?;
    Ok(Some(UpPolicy {
        on: switch && origin.is_some(),
        origin,
        regs,
        stopped: stopped(db, device_id).await?,
        refresh: refresh
            .into_iter()
            .map(|(reg, why)| Refresh {
                reg,
                why: if why == "refused" {
                    "refused"
                } else {
                    "outside"
                },
            })
            .collect(),
    }))
}

/// The phone's own switch (`devices.unified_push`, on by default); an unknown phone reads off.
pub async fn switch<'e>(
    db: impl sqlx::SqliteExecutor<'e>,
    device_id: i64,
) -> Result<bool, sqlx::Error> {
    Ok(
        sqlx::query_scalar("SELECT unified_push FROM devices WHERE id = ?")
            .bind(device_id)
            .fetch_optional(db)
            .await?
            .unwrap_or(false),
    )
}

/// The packages the parent stopped on this phone, sorted.
pub async fn stopped<'e>(
    db: impl sqlx::SqliteExecutor<'e>,
    device_id: i64,
) -> Result<Vec<String>, sqlx::Error> {
    sqlx::query_scalar("SELECT package FROM up_stopped WHERE device_id = ? ORDER BY package")
        .bind(device_id)
        .fetch_all(db)
        .await
}

/// What the server stores of an endpoint secret: its SHA-256 (§3.5). A plain hash is enough for a 160-bit random
/// value; the secret itself is never stored.
pub fn secret_hash(secret: &str) -> Vec<u8> {
    use sha2::Digest;
    sha2::Sha256::digest(secret.as_bytes()).to_vec()
}

/// The phones' public address for app push (§3.1), as typed: `https://` and an origin only (no path - a lone `/` is
/// dropped -, query, fragment or user), never a Tailscale IP literal. Element X probes
/// `scheme://host[:port]/_matrix/push/v1/notify`, so the phone listener must sit at the root of that name. The origin
/// as stored (`https://host[:port]`, the host lower-cased, the default port left out); `None` refused.
pub fn check_origin(typed: &str) -> Option<String> {
    let typed = typed.trim();
    if !typed
        .get(..8)
        .is_some_and(|s| s.eq_ignore_ascii_case("https://"))
        || typed.contains('?')
        || typed.contains('#')
    {
        return None;
    }
    let url = reqwest::Url::parse(typed).ok()?;
    if url.scheme() != "https"
        || !url.username().is_empty()
        || url.password().is_some()
        || url.path() != "/"
        || url.query().is_some()
        || url.fragment().is_some()
    {
        return None;
    }
    let host = url.host_str()?;
    if let Ok(ip) = host
        .trim_start_matches('[')
        .trim_end_matches(']')
        .parse::<std::net::IpAddr>()
        && crate::net::is_tailnet(ip)
    {
        return None;
    }
    Some(url.origin().ascii_serialization())
}

/// `/settings/push`'s prefill from Phone Setup's saved address (L7): its origin when it is `https://` and passes
/// [check_origin] by its origin; a `*.ts.net` name (a Funnel name is one) only on a port Funnel serves (443, 8443,
/// 10000). Never saved by itself.
pub fn prefill(server_url: &str) -> Option<String> {
    let url = reqwest::Url::parse(server_url.trim()).ok()?;
    if url.scheme() != "https" {
        return None;
    }
    let origin = check_origin(&url.origin().ascii_serialization())?;
    let host = url.host_str()?.to_ascii_lowercase();
    let port = url.port_or_known_default()?;
    if host.ends_with(".ts.net") && ![443, 8443, 10000].contains(&port) {
        return None;
    }
    Some(origin)
}

/// What [save_origin] did.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum OriginSaved {
    Unchanged,
    /// Set where there was none: every phone with its switch on gets app push.
    Set,
    /// A new origin: the old one is accepted [PREVIOUS_ORIGIN_DAYS] more, the phones rewrite their endpoints (M4).
    Changed,
    /// Cleared: app push is off for every phone, every registration deleted (tombstoned).
    Cleared,
}

/// Saves the public origin (`None` clears it) and nudges every phone when it changed.
pub async fn save_origin(
    state: &AppState,
    origin: Option<&str>,
) -> Result<OriginSaved, sqlx::Error> {
    let current = settings(&state.db).await?.public_origin;
    let saved = match (current.as_deref(), origin) {
        (current, new) if current == new => return Ok(OriginSaved::Unchanged),
        (None, Some(_)) => OriginSaved::Set,
        (Some(_), Some(_)) => OriginSaved::Changed,
        (_, None) => OriginSaved::Cleared,
    };
    let mut tx = state.db.begin().await?;
    match saved {
        OriginSaved::Cleared => {
            sqlx::query("UPDATE unified_push_settings SET public_origin = NULL WHERE id = 1")
                .execute(&mut *tx)
                .await?;
            sqlx::query("DELETE FROM up_previous_origins")
                .execute(&mut *tx)
                .await?;
            store::delete_everywhere(&mut *tx).await?;
        }
        _ => {
            // The replaced one is accepted 30 days from now, beside any replaced before it (L7); the new one is
            // current, never "previous" too.
            if let Some(old) = current.as_deref() {
                sqlx::query(
                    "INSERT OR REPLACE INTO up_previous_origins (origin, until_at) \
                     VALUES (?, datetime('now', ?))",
                )
                .bind(old)
                .bind(format!("+{PREVIOUS_ORIGIN_DAYS} days"))
                .execute(&mut *tx)
                .await?;
            }
            sqlx::query("DELETE FROM up_previous_origins WHERE origin = ?")
                .bind(origin)
                .execute(&mut *tx)
                .await?;
            sqlx::query("UPDATE unified_push_settings SET public_origin = ? WHERE id = 1")
                .bind(origin)
                .execute(&mut *tx)
                .await?;
        }
    }
    tx.commit().await?;
    nudge_every_phone(state).await;
    Ok(saved)
}

async fn nudge_every_phone(state: &AppState) {
    match sqlx::query_scalar::<_, i64>("SELECT id FROM devices WHERE token_hash IS NOT NULL")
        .fetch_all(&state.db)
        .await
    {
        Ok(devices) => {
            for device in devices {
                let _ = state.command_notify.send(device);
            }
        }
        Err(err) => tracing::warn!(%err, "app push: couldn't read the phones to nudge"),
    }
}

/// The phone's switch (Device › Apps): off deletes its registrations (tombstoned). Nudges the phone; `false` for an
/// unknown phone.
pub async fn set_switch(state: &AppState, device_id: i64, on: bool) -> Result<bool, sqlx::Error> {
    let mut tx = state.db.begin().await?;
    let found = sqlx::query("UPDATE devices SET unified_push = ? WHERE id = ?")
        .bind(on)
        .bind(device_id)
        .execute(&mut *tx)
        .await?
        .rows_affected()
        > 0;
    if found && !on {
        store::delete_registrations(&mut *tx, device_id, store::Which::All).await?;
    }
    tx.commit().await?;
    if found {
        let _ = state.command_notify.send(device_id);
    }
    Ok(found)
}

/// The parent's Stop: the app's registrations deleted (tombstoned), the package on the phone's stopped list. Nudges.
pub async fn stop(state: &AppState, device_id: i64, package: &str) -> Result<(), sqlx::Error> {
    let mut tx = state.db.begin().await?;
    sqlx::query("INSERT OR IGNORE INTO up_stopped (device_id, package) VALUES (?, ?)")
        .bind(device_id)
        .bind(package)
        .execute(&mut *tx)
        .await?;
    store::delete_registrations(&mut *tx, device_id, store::Which::Package(package)).await?;
    tx.commit().await?;
    let _ = state.command_notify.send(device_id);
    Ok(())
}

/// "Allow again": the package leaves the stopped list; the app registers at its next start. Nudges.
pub async fn allow_again(
    state: &AppState,
    device_id: i64,
    package: &str,
) -> Result<(), sqlx::Error> {
    sqlx::query("DELETE FROM up_stopped WHERE device_id = ? AND package = ?")
        .bind(device_id)
        .bind(package)
        .execute(&state.db)
        .await?;
    let _ = state.command_notify.send(device_id);
    Ok(())
}

/// Every allowlist writer calls this after its write, on the same connection - inside its own transaction when it has
/// one, before the commit (L10, R1-SR M2; `tests::unified_push::every_allowlist_writer_prunes`): the phone's
/// registrations of packages no longer allowed are deleted (tombstoned: the sender gets 410 / rejected). It reads the
/// list on that connection, so it sees the writer's uncommitted list and never waits on another pool connection. The
/// rows deleted: the writer nudges the phone after its commit when any went. An unmanaged phone (`NULL`) keeps every
/// registration; one whose list can't be read keeps them too (its policy is a 500 anyway, and the next writer prunes).
pub async fn prune_disallowed(
    conn: &mut sqlx::SqliteConnection,
    device_id: i64,
) -> Result<u64, sqlx::Error> {
    let allowlist: Option<Option<String>> =
        sqlx::query_scalar("SELECT allowlist_json FROM device_policy WHERE device_id = ?")
            .bind(device_id)
            .fetch_optional(&mut *conn)
            .await?;
    let Some(Some(json)) = allowlist else {
        return Ok(0);
    };
    let Ok(allowed) = serde_json::from_str::<Vec<String>>(&json) else {
        return Ok(0);
    };
    store::delete_registrations(&mut *conn, device_id, store::Which::NotIn(&allowed)).await
}

/// [prune_disallowed] for a writer without a transaction of its own (after its write committed): a pool connection,
/// the nudge when anything went, errors logged.
pub async fn prune_disallowed_now(state: &AppState, device_id: i64) {
    let pruned = match state.db.acquire().await {
        Ok(mut conn) => prune_disallowed(&mut conn, device_id).await,
        Err(err) => Err(err),
    };
    match pruned {
        Ok(0) => {}
        Ok(_) => {
            let _ = state.command_notify.send(device_id);
        }
        Err(err) => tracing::error!(device_id, %err, "app push: couldn't prune the registrations"),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// §3.1's check: https and an origin only, never a tailnet IP; the stored form.
    #[test]
    fn the_origin_check() {
        for (typed, stored) in [
            ("https://phones.example.com", "https://phones.example.com"),
            ("https://Phones.Example.com/", "https://phones.example.com"),
            (
                "  https://phones.example.com:8443 ",
                "https://phones.example.com:8443",
            ),
            (
                "https://phones.example.com:443",
                "https://phones.example.com",
            ),
            ("https://kid.tail1234.ts.net", "https://kid.tail1234.ts.net"),
            ("https://203.0.113.7", "https://203.0.113.7"),
        ] {
            assert_eq!(check_origin(typed).as_deref(), Some(stored), "{typed}");
        }
        for typed in [
            "http://phones.example.com",
            "phones.example.com",
            "https://phones.example.com/vibb",
            "https://phones.example.com/?x=1",
            "https://phones.example.com/#up",
            "https://user@phones.example.com",
            "https://100.101.102.103",
            "https://[fd7a:115c:a1e0::1]",
            "",
        ] {
            assert_eq!(check_origin(typed), None, "{typed}");
        }
        // The prefill (L7): Phone Setup's https address by its origin; a ts.net name only on Funnel's ports.
        assert_eq!(
            prefill("https://phones.example.com/").as_deref(),
            Some("https://phones.example.com")
        );
        assert_eq!(
            prefill("https://kid.tail1234.ts.net:8443").as_deref(),
            Some("https://kid.tail1234.ts.net:8443")
        );
        assert_eq!(prefill("https://kid.tail1234.ts.net:3101"), None);
        assert_eq!(prefill("http://100.101.102.103:3101"), None);
        assert_eq!(prefill(""), None);
    }

    /// L4/L5: the churn check counts and refuses under one lock - 20 new registrations of one app an hour, 60 of the
    /// phone across its apps; an undone one gives its place back.
    #[test]
    fn new_registrations_an_hour() {
        let up = UnifiedPush::default();
        for _ in 0..NEW_PER_HOUR {
            assert!(up.admit_new_registration(1, "a.b"));
        }
        assert!(!up.admit_new_registration(1, "a.b"), "the app's 20");
        up.undo_new_registration(1, "a.b");
        assert!(up.admit_new_registration(1, "a.b"));
        for _ in 0..NEW_PER_HOUR {
            assert!(up.admit_new_registration(1, "c.d"));
            assert!(up.admit_new_registration(1, "e.f"));
        }
        assert!(!up.admit_new_registration(1, "g.h"), "the phone's 60");
        assert!(up.admit_new_registration(2, "g.h"), "another phone");
    }
}
