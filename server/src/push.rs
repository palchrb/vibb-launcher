//! Web Push to the parents' devices (design 35 §4, §6, step 2): the VAPID key file, the queue
//! (`raise` stores a delivery per active subscription when the kind's push is on), the one sender
//! ([run_sender] → [send_due]) and what each push service's answer does, and delivery health,
//! derived when read from the delivery rows. The cryptography is `webpush`, the routes and pages
//! `handlers::notifications`.
//!
//! A delivery's receipt token is made at each send (16 random bytes in the encrypted payload,
//! stored only as its SHA-256): a retry carries a fresh one, the worker posts it back
//! (`/notifications/receipt`) when the notification shows.

use std::collections::HashMap;
use std::sync::{Arc, Mutex};
use std::time::Duration;

use base64::Engine;
use base64::engine::general_purpose::URL_SAFE_NO_PAD as B64;
use chrono::{DateTime, Utc};
use ring::rand::SecureRandom;
use sha2::{Digest, Sha256};

use crate::AppState;
use crate::music_net::BoxFut;
use crate::parent_events::Kind;
use crate::webpush::{self, VapidKey};

/// Where the VAPID key is kept: inside `data/` (writable under the unit), in the directory no
/// backup copies.
pub const DEFAULT_KEY_FILE: &str = "data/keys/vapid-p256.pk8";
/// At most this many active subscriptions per admin (§5.2).
pub const MAX_SUBSCRIPTIONS: i64 = 10;
/// A JWT is reused while `now` is within this long after its `iat` (§4.3).
const JWT_REUSE_SECS: i64 = 60 * 60;
/// Retries after a 429, a 5xx or a network error (§4.4), then `failed`.
const RETRY_SECS: [i64; 4] = [60, 5 * 60, 30 * 60, 2 * 60 * 60];
/// More due event pushes than this for one subscription in one pass are folded (§4.6).
const FOLD_ABOVE: usize = 3;
/// A test push per subscription at most this often (§6).
pub const TEST_SPACING_SECS: i64 = 30;
/// Unconfirmed pushes that make "may not be showing" (§6): this many, or the oldest this old.
const UNCONFIRMED_MAX: i64 = 3;
const UNCONFIRMED_HOURS: i64 = 24;

/// One push request: the endpoint, headers and the encrypted body.
#[derive(Clone, Debug)]
pub struct PushRequest {
    pub endpoint: String,
    pub headers: Vec<(&'static str, String)>,
    pub body: Vec<u8>,
}

impl PushRequest {
    pub fn header(&self, name: &str) -> Option<&str> {
        self.headers
            .iter()
            .find(|(n, _)| n.eq_ignore_ascii_case(name))
            .map(|(_, v)| v.as_str())
    }
}

/// A push service's answer: its status, `Retry-After`, the JSON `reason` and Apple's `apns-id`.
#[derive(Clone, Debug, Default)]
pub struct PushAnswer {
    pub status: u16,
    pub retry_after: Option<String>,
    pub reason: Option<String>,
    pub apns_id: Option<String>,
}

/// Sends one push: [HttpTransport] in the server, a scripted one in the tests. `Err` is a network
/// error (retried).
pub trait Transport: Send + Sync {
    fn send(&self, request: PushRequest) -> BoxFut<'_, Result<PushAnswer, String>>;
}

/// The real transport (§4.3): HTTP/1.1 over rustls, redirects never followed, connect 5 s, all
/// 15 s. The sender sends one push at a time.
pub struct HttpTransport {
    client: reqwest::Client,
}

impl HttpTransport {
    pub fn new() -> Self {
        HttpTransport {
            client: reqwest::Client::builder()
                .redirect(reqwest::redirect::Policy::none())
                .connect_timeout(Duration::from_secs(5))
                .timeout(Duration::from_secs(15))
                .build()
                .expect("the push client builds"),
        }
    }
}

impl Transport for HttpTransport {
    fn send(&self, request: PushRequest) -> BoxFut<'_, Result<PushAnswer, String>> {
        Box::pin(async move {
            let mut builder = self.client.post(&request.endpoint).body(request.body);
            for (name, value) in &request.headers {
                builder = builder.header(*name, value);
            }
            let response = builder.send().await.map_err(|err| {
                // reqwest's error names the URL - the endpoint's path is the subscription's
                // secret name.
                err.without_url().to_string()
            })?;
            let header = |name: &str| {
                response
                    .headers()
                    .get(name)
                    .and_then(|v| v.to_str().ok())
                    .map(str::to_string)
            };
            let mut answer = PushAnswer {
                status: response.status().as_u16(),
                retry_after: header("retry-after"),
                apns_id: header("apns-id"),
                reason: None,
            };
            if !response.status().is_success()
                && let Ok(body) = response.bytes().await
            {
                answer.reason = serde_json::from_slice::<serde_json::Value>(&body)
                    .ok()
                    .and_then(|v| v.get("reason")?.as_str().map(str::to_string));
            }
            Ok(answer)
        })
    }
}

/// Web Push's state in `AppState.push`: the key (`None` = push is off: the key file is broken),
/// the contact, the transport, the sender's wake-up, the JWT cache and the test pushes' spacing.
pub struct Push {
    pub key: Option<Arc<VapidKey>>,
    /// `VAPID_SUBJECT` (`mailto:` or `https:`); else each subscription's `https://` origin, else
    /// none (RFC 8292 takes no `http:` contact).
    subject: Option<String>,
    transport: Arc<dyn Transport>,
    wake: tokio::sync::Notify,
    /// `(aud, sub)` → `(iat, jwt)`.
    jwts: Mutex<HashMap<(String, String), (i64, String)>>,
    /// Subscription id → when its last test push was asked for (Unix seconds).
    tests: Mutex<HashMap<i64, i64>>,
}

impl Push {
    pub fn new(
        key: Option<Arc<VapidKey>>,
        subject: Option<String>,
        transport: Arc<dyn Transport>,
    ) -> Self {
        Push {
            key,
            subject,
            transport,
            wake: tokio::sync::Notify::new(),
            jwts: Mutex::default(),
            tests: Mutex::default(),
        }
    }

    /// Wakes the sender: something was queued (after the caller's transaction committed).
    pub fn wake(&self) {
        self.wake.notify_one();
    }

    /// The JWT for `(aud, sub)` at `now`: the cached one while `now` is within
    /// `[iat, iat + 1 h]` (a clock jump either way mints a new one), else a fresh one.
    fn jwt(&self, key: &VapidKey, aud: &str, sub: Option<&str>, now: i64) -> String {
        let mut jwts = self.jwts.lock().unwrap_or_else(|e| e.into_inner());
        let slot = (aud.to_string(), sub.unwrap_or("").to_string());
        if let Some((iat, jwt)) = jwts.get(&slot)
            && (*iat..=*iat + JWT_REUSE_SECS).contains(&now)
        {
            return jwt.clone();
        }
        let jwt = webpush::vapid_jwt(key, aud, sub, now);
        jwts.insert(slot, (now, jwt.clone()));
        jwt
    }

    /// Whether [Push::wake] was called since the last look (tests).
    #[cfg(test)]
    pub async fn woken(&self) -> bool {
        tokio::time::timeout(Duration::from_millis(1), self.wake.notified())
            .await
            .is_ok()
    }

    fn forget_jwt(&self, aud: &str, sub: Option<&str>) {
        self.jwts
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .remove(&(aud.to_string(), sub.unwrap_or("").to_string()));
    }

    /// Whether a test push for `subscription` may go now (one per [TEST_SPACING_SECS]); takes
    /// the slot when it may.
    pub fn take_test_slot(&self, subscription: i64, now: i64) -> bool {
        let mut tests = self.tests.lock().unwrap_or_else(|e| e.into_inner());
        match tests.get(&subscription) {
            Some(last) if now - last < TEST_SPACING_SECS && now >= *last => false,
            _ => {
                tests.insert(subscription, now);
                true
            }
        }
    }
}

/// `VAPID_SUBJECT` when it is a `mailto:` or `https:` URL; anything else is ignored (logged by
/// the caller).
pub fn subject_of(value: Option<&str>) -> Result<Option<String>, String> {
    match value.map(str::trim).filter(|v| !v.is_empty()) {
        None => Ok(None),
        Some(v) if v.starts_with("mailto:") || v.starts_with("https://") => Ok(Some(v.to_string())),
        Some(v) => Err(format!(
            "VAPID_SUBJECT {v:?} is not a mailto: or https: URL - ignored, each device's own \
             address is sent instead"
        )),
    }
}

/// The VAPID key from `file`, made (PKCS#8, 0600, in the 0700 `data/keys/`) the first time
/// (§4.1). `Err` = push stays off: a file that doesn't parse is refused, never replaced.
pub fn load_key(file: &std::path::Path) -> Result<VapidKey, String> {
    let loaded = crate::keys::load_or_create(file, VapidKey::generate_pkcs8)?;
    if loaded.created {
        tracing::info!(
            "created the Web Push key file {} (no backup holds it)",
            file.display()
        );
    }
    VapidKey::from_pkcs8(&loaded.bytes).ok_or_else(|| {
        format!(
            "{} is not a P-256 key (PKCS#8) - fix it, or remove it to make a new key (every \
             device then turns notifications on again)",
            file.display()
        )
    })
}

/// Right after the key is loaded (§4.1): every active subscription made for another key can't be
/// pushed to (a restore onto a new machine, a removed key file) - it is turned off.
pub async fn forget_other_keys(db: &sqlx::SqlitePool, key: &VapidKey) {
    let done = sqlx::query(
        "UPDATE push_subscriptions SET state = 'gone', gone_at = datetime('now') \
         WHERE state = 'active' AND vapid_fp != ?",
    )
    .bind(&key.fingerprint)
    .execute(db)
    .await;
    match done {
        Ok(done) if done.rows_affected() > 0 => tracing::warn!(
            "{} notification subscription(s) were made for another Web Push key and are turned \
             off - turn notifications on again on those devices",
            done.rows_affected()
        ),
        Ok(_) => {}
        Err(err) => tracing::error!(%err, "can't check the subscriptions' key"),
    }
}

/// Deletes every subscription of an admin: Sign Out Everywhere and `reset-2fa` (§7) - a lost
/// device stops getting titles.
pub async fn forget_subscriptions(db: &sqlx::SqlitePool, admin_id: i64) {
    if let Err(err) = sqlx::query("DELETE FROM push_subscriptions WHERE admin_id = ?")
        .bind(admin_id)
        .execute(db)
        .await
    {
        tracing::error!(%err, "can't delete the notification subscriptions");
    }
}

/// Queues `event`'s push to every active subscription when the kind's push is on (the emergency
/// call always), on `raise`'s connection (§4.6).
pub async fn queue(
    conn: &mut sqlx::SqliteConnection,
    event_id: i64,
    kind: Kind,
) -> Result<(), sqlx::Error> {
    // The column name is compiled in (`Kind::switch`), never input.
    let on = match kind.switch() {
        Some((column, _)) => {
            format!("(SELECT {column} FROM notification_settings WHERE id = 1) = 1")
        }
        None => "1".to_string(),
    };
    sqlx::query(&format!(
        "INSERT INTO push_deliveries (subscription_id, event_id, kind, token_hash) \
         SELECT id, ?, 'event', X'' FROM push_subscriptions WHERE state = 'active' AND {on}"
    ))
    .bind(event_id)
    .execute(conn)
    .await?;
    Ok(())
}

/// How SQLite's `datetime('now')` writes a time; every push column uses it, so they compare.
pub fn db_time(t: DateTime<Utc>) -> String {
    t.format("%Y-%m-%d %H:%M:%S").to_string()
}

/// What a delivery is: an event's push, a summary of folded ones, or a test.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum What {
    Event(Kind),
    Summary,
    Test,
}

impl What {
    /// Seconds the push service keeps it (§4.3).
    fn ttl(self) -> i64 {
        match self {
            What::Event(kind) => kind.ttl(),
            What::Summary => 43_200,
            What::Test => 600,
        }
    }

    fn urgency(self) -> &'static str {
        match self {
            What::Event(kind) => kind.urgency(),
            What::Summary => "normal",
            What::Test => "high",
        }
    }
}

/// A due delivery, with what its push says.
struct Due {
    id: i64,
    subscription_id: i64,
    what: What,
    attempts: i64,
    created_at: String,
    endpoint: String,
    p256dh: Vec<u8>,
    auth: Vec<u8>,
    origin: String,
    label: String,
    event_id: Option<i64>,
    title: String,
    body: String,
    target: String,
}

type DueRow = (
    i64,
    i64,
    String,
    i64,
    String,
    String,
    Vec<u8>,
    Vec<u8>,
    String,
    String,
    String,
    Option<i64>,
    Option<String>,
    Option<String>,
    Option<String>,
    Option<String>,
);

/// One pass of the sender at `now` (tests drive it directly): every due delivery is sent once,
/// with more than [FOLD_ABOVE] event pushes for one subscription folded into its newest two and
/// one summary (an urgent kind - the emergency call, a PIN lockout, a foreign app - always goes on
/// its own). Deliveries to a turned-off
/// subscription or of a pruned event are dropped. Nothing happens without a key.
pub async fn send_due(state: &AppState, now: DateTime<Utc>) -> Result<(), sqlx::Error> {
    let Some(key) = state.push.key.clone() else {
        return Ok(());
    };
    // A push the service took although its request looked failed (a network error after the
    // body went): the worker's receipt came before the retry, so it is accepted, never sent
    // again (qa-35-s2-code L3).
    sqlx::query(
        "UPDATE push_deliveries SET status = 'accepted', \
         accepted_at = COALESCE(accepted_at, receipt_at), next_try_at = NULL, reason = NULL \
         WHERE status = 'queued' AND receipt_at IS NOT NULL",
    )
    .execute(&state.db)
    .await?;
    let rows: Vec<DueRow> = sqlx::query_as(
        "SELECT d.id, d.subscription_id, d.kind, d.attempts, d.created_at, s.endpoint, s.p256dh, \
                s.auth, s.origin, s.label, s.state, d.event_id, e.kind, e.title, e.summary, e.target \
         FROM push_deliveries d JOIN push_subscriptions s ON s.id = d.subscription_id \
         LEFT JOIN parent_events e ON e.id = d.event_id \
         WHERE d.status = 'queued' AND (d.next_try_at IS NULL OR d.next_try_at <= ?) \
         ORDER BY d.id",
    )
    .bind(db_time(now))
    .fetch_all(&state.db)
    .await?;
    let mut due: Vec<Due> = Vec::new();
    for row in rows {
        let (id, subscription_id, kind, attempts, created_at, endpoint, p256dh, auth) =
            (row.0, row.1, row.2, row.3, row.4, row.5, row.6, row.7);
        let (origin, label, sub_state, event_id) = (row.8, row.9, row.10, row.11);
        let what = match (kind.as_str(), row.12.as_deref().and_then(Kind::of)) {
            ("event", Some(kind)) => What::Event(kind),
            ("summary", _) => What::Summary,
            ("test", _) => What::Test,
            // The event went with its phone or its age, or a row we don't know.
            _ => {
                finish(state, id, "dropped", None, Some("event_gone")).await?;
                continue;
            }
        };
        if sub_state != "active" {
            finish(state, id, "dropped", None, Some("gone")).await?;
            continue;
        }
        let (title, body, target) = match what {
            What::Event(_) => (
                row.13.unwrap_or_default(),
                row.14.unwrap_or_default(),
                row.15.unwrap_or_default(),
            ),
            What::Summary => (
                "New notifications".to_string(),
                "More than 3 at once - open Notifications to see them".to_string(),
                "/notifications".to_string(),
            ),
            What::Test => (
                "Test from Vibb Phone".to_string(),
                format!("If you see this, notifications work on {label}"),
                "/notifications#devices".to_string(),
            ),
        };
        due.push(Due {
            id,
            subscription_id,
            what,
            attempts,
            created_at,
            endpoint,
            p256dh,
            auth,
            origin,
            label,
            event_id,
            title,
            body,
            target,
        });
    }
    let mut sends = fold(state, due).await?;
    // The emergency call first, then the urgent kinds, each in order.
    sends.sort_by_key(|d| {
        (
            !matches!(d.what, What::Event(Kind::EmergencyCall)),
            d.what.urgency() != "high",
            d.id,
        )
    });
    for delivery in sends {
        send_one(state, &key, delivery, now).await?;
    }
    Ok(())
}

/// [send_due]'s cap per pass (§4.6): a subscription with more than [FOLD_ABOVE] due event pushes
/// of the normal kinds gets the newest two and one summary; the rest are `dropped`, `folded`.
/// The urgent kinds (`Kind::urgent`) are never folded into a normal-urgency summary
/// (qa-35-s2-code L2).
async fn fold(state: &AppState, due: Vec<Due>) -> Result<Vec<Due>, sqlx::Error> {
    let mut sends = Vec::new();
    let mut by_subscription: Vec<(i64, Vec<Due>)> = Vec::new();
    for delivery in due {
        let foldable = matches!(delivery.what, What::Event(kind) if !kind.urgent());
        if !foldable {
            sends.push(delivery);
            continue;
        }
        match by_subscription
            .iter_mut()
            .find(|(id, _)| *id == delivery.subscription_id)
        {
            Some((_, list)) => list.push(delivery),
            None => by_subscription.push((delivery.subscription_id, vec![delivery])),
        }
    }
    for (subscription_id, mut list) in by_subscription {
        if list.len() <= FOLD_ABOVE {
            sends.extend(list);
            continue;
        }
        // Oldest first: keep the newest two.
        let newest = list.split_off(list.len() - 2);
        for folded in &list {
            finish(state, folded.id, "dropped", None, Some("folded")).await?;
        }
        let summary_id: i64 = sqlx::query_scalar(
            "INSERT INTO push_deliveries (subscription_id, kind, token_hash) \
             VALUES (?, 'summary', X'') RETURNING id",
        )
        .bind(subscription_id)
        .fetch_one(&state.db)
        .await?;
        let created_at: String =
            sqlx::query_scalar("SELECT created_at FROM push_deliveries WHERE id = ?")
                .bind(summary_id)
                .fetch_one(&state.db)
                .await?;
        let first = &list[0];
        sends.push(Due {
            id: summary_id,
            subscription_id,
            what: What::Summary,
            attempts: 0,
            created_at,
            endpoint: first.endpoint.clone(),
            p256dh: first.p256dh.clone(),
            auth: first.auth.clone(),
            origin: first.origin.clone(),
            label: first.label.clone(),
            event_id: None,
            title: "New notifications".to_string(),
            body: "More than 3 at once - open Notifications to see them".to_string(),
            target: "/notifications".to_string(),
        });
        sends.extend(newest);
    }
    Ok(sends)
}

/// Ends a delivery: `status`, the push service's status and the reason.
async fn finish(
    state: &AppState,
    id: i64,
    status: &str,
    http_status: Option<u16>,
    reason: Option<&str>,
) -> Result<(), sqlx::Error> {
    sqlx::query(
        "UPDATE push_deliveries SET status = ?, http_status = COALESCE(?, http_status), \
         reason = ?, next_try_at = NULL WHERE id = ?",
    )
    .bind(status)
    .bind(http_status)
    .bind(reason)
    .bind(id)
    .execute(&state.db)
    .await?;
    Ok(())
}

/// What a push service's answer does (§4.4; H1: a 401/403 is the server's signature - usually
/// the clock -, never a sign the subscription is gone).
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum Outcome {
    Accepted,
    /// 404, 410: the subscription is gone.
    Gone,
    /// 401, 403: the JWT or the key. Retried once at once with a fresh JWT, then failed.
    BadSignature,
    /// 429, 500, 502, 503, 504, a network error: retried after this many seconds, if any.
    Retry(Option<i64>),
    Failed,
}

pub fn outcome(answer: &Result<PushAnswer, String>) -> Outcome {
    let answer = match answer {
        Ok(answer) => answer,
        Err(_) => return Outcome::Retry(None),
    };
    match answer.status {
        200..=299 => Outcome::Accepted,
        404 | 410 => Outcome::Gone,
        401 | 403 => Outcome::BadSignature,
        429 | 500 | 502 | 503 | 504 => Outcome::Retry(
            answer
                .retry_after
                .as_deref()
                .and_then(|v| v.trim().parse::<i64>().ok())
                .map(|secs| secs.clamp(0, RETRY_SECS[3])),
        ),
        _ => Outcome::Failed,
    }
}

/// Sends one delivery and stores what came of it.
async fn send_one(
    state: &AppState,
    key: &VapidKey,
    delivery: Due,
    now: DateTime<Utc>,
) -> Result<(), sqlx::Error> {
    let ttl = delivery.what.ttl();
    let expires = crate::display_time::parse_db_utc(&delivery.created_at)
        .map(|created| created + chrono::Duration::seconds(ttl));
    if expires.is_some_and(|end| end <= now) {
        return finish(state, delivery.id, "failed", None, Some("expired")).await;
    }
    let host = webpush::host_of(&delivery.endpoint);
    let Some(aud) = webpush::allowed(&delivery.endpoint)
        .then(|| webpush::aud_of(&delivery.endpoint))
        .flatten()
    else {
        tracing::warn!(host, "a subscription names a push service not on the list");
        return finish(
            state,
            delivery.id,
            "failed",
            None,
            Some("endpoint_not_allowed"),
        )
        .await;
    };
    let mut token = [0u8; 16];
    ring::rand::SystemRandom::new()
        .fill(&mut token)
        .expect("the system's random source works");
    let unread: i64 =
        sqlx::query_scalar("SELECT COUNT(*) FROM parent_events WHERE read_at IS NULL")
            .fetch_one(&state.db)
            .await?;
    let target = if crate::parent_events::local_target(&delivery.target) {
        delivery.target.as_str()
    } else {
        "/notifications"
    };
    let tag = match delivery.what {
        What::Event(_) => format!("e{}", delivery.event_id.unwrap_or(0)),
        What::Summary => format!("s{}", delivery.id),
        What::Test => format!("t{}", delivery.id),
    };
    let payload = serde_json::json!({
        "web_push": 8030,
        "mutable": true,
        "notification": {
            "title": crate::audit::clip(&delivery.title, crate::parent_events::TITLE_MAX),
            "body": crate::audit::clip(&delivery.body, crate::parent_events::SUMMARY_MAX),
            "lang": "en",
            "navigate": format!("{}{target}", delivery.origin.trim_end_matches('/')),
            "tag": tag,
            "data": { "d": delivery.id, "t": B64.encode(token), "b": unread },
        },
    })
    .to_string();
    let body = match webpush::encrypt(
        &ring::rand::SystemRandom::new(),
        &delivery.p256dh,
        &delivery.auth,
        payload.as_bytes(),
    ) {
        Ok(body) => body,
        Err(webpush::EncryptError::TooLarge) => {
            tracing::error!(delivery = delivery.id, "a push is over the size cap");
            return finish(state, delivery.id, "failed", None, Some("too_large")).await;
        }
        Err(webpush::EncryptError::BadKey) => {
            return finish(state, delivery.id, "failed", None, Some("bad_key")).await;
        }
    };
    // The token's hash is stored before the push goes: a quick receipt finds it.
    sqlx::query("UPDATE push_deliveries SET token_hash = ?, attempts = attempts + 1 WHERE id = ?")
        .bind(Sha256::digest(token).to_vec())
        .bind(delivery.id)
        .execute(&state.db)
        .await?;
    let sub = state.push.subject.clone().or_else(|| {
        delivery
            .origin
            .starts_with("https://")
            .then(|| delivery.origin.clone())
    });
    let sub = sub.as_deref();
    let request = |jwt: &str| PushRequest {
        endpoint: delivery.endpoint.clone(),
        headers: vec![
            ("Authorization", webpush::authorization(jwt, key)),
            ("Content-Encoding", "aes128gcm".to_string()),
            ("Content-Type", "application/octet-stream".to_string()),
            ("TTL", ttl.to_string()),
            ("Urgency", delivery.what.urgency().to_string()),
        ],
        body: body.clone(),
    };
    let at = now.timestamp();
    let mut answer = state
        .push
        .transport
        .send(request(&state.push.jwt(key, &aud, sub, at)))
        .await;
    if outcome(&answer) == Outcome::BadSignature {
        log_refusal(&host, &answer);
        state.push.forget_jwt(&aud, sub);
        answer = state
            .push
            .transport
            .send(request(&state.push.jwt(key, &aud, sub, at)))
            .await;
    }
    let status = answer.as_ref().ok().map(|a| a.status);
    let reason = answer
        .as_ref()
        .ok()
        .and_then(|a| a.reason.clone())
        .or_else(|| answer.as_ref().err().map(|_| "network".to_string()));
    let result = outcome(&answer);
    if result != Outcome::Accepted {
        log_refusal(&host, &answer);
    }
    match result {
        Outcome::Accepted => {
            sqlx::query(
                "UPDATE push_deliveries SET status = 'accepted', accepted_at = ?, \
                 http_status = ?, reason = NULL, next_try_at = NULL WHERE id = ?",
            )
            .bind(db_time(now))
            .bind(status)
            .bind(delivery.id)
            .execute(&state.db)
            .await?;
        }
        Outcome::Gone => {
            finish(state, delivery.id, "dropped", status, reason.as_deref()).await?;
            let turned_off = sqlx::query(
                "UPDATE push_subscriptions SET state = 'gone', gone_at = datetime('now') \
                 WHERE id = ? AND state = 'active'",
            )
            .bind(delivery.subscription_id)
            .execute(&state.db)
            .await?
            .rows_affected();
            if turned_off == 1 {
                crate::security::record_security_event(
                    &state.db,
                    "push_subscription_gone",
                    None,
                    None,
                    Some(&format!(
                        "{}: the push service says it is gone ({})",
                        delivery.label,
                        status.unwrap_or(0)
                    )),
                )
                .await;
            }
        }
        Outcome::BadSignature | Outcome::Failed => {
            finish(state, delivery.id, "failed", status, reason.as_deref()).await?;
        }
        Outcome::Retry(after) => {
            let attempt = delivery.attempts as usize; // before this one
            let next = RETRY_SECS
                .get(attempt)
                .map(|scheduled| now + chrono::Duration::seconds(after.unwrap_or(*scheduled)));
            match next {
                Some(next) if expires.is_none_or(|end| next < end) => {
                    sqlx::query(
                        "UPDATE push_deliveries SET next_try_at = ?, http_status = ?, reason = ? \
                         WHERE id = ?",
                    )
                    .bind(db_time(next))
                    .bind(status)
                    .bind(&reason)
                    .bind(delivery.id)
                    .execute(&state.db)
                    .await?;
                }
                _ => finish(state, delivery.id, "failed", status, reason.as_deref()).await?,
            }
        }
    }
    Ok(())
}

/// A push service's refusal in the log: the status, its reason, `apns-id` and the endpoint's
/// host - never the path, a key, the JWT or a token (§4.4).
fn log_refusal(host: &str, answer: &Result<PushAnswer, String>) {
    match answer {
        Ok(answer) => tracing::warn!(
            host,
            status = answer.status,
            reason = answer.reason.as_deref().unwrap_or(""),
            apns_id = answer.apns_id.as_deref().unwrap_or(""),
            "a push service refused a push"
        ),
        Err(err) => tracing::warn!(host, %err, "a push didn't reach its push service"),
    }
}

/// The sender (spawned by `main` only): a pass at start, then at every wake-up or when the
/// earliest retry is due (at most an hour apart).
pub async fn run_sender(state: AppState) {
    loop {
        let now = Utc::now();
        let wait = match send_due(&state, now).await {
            Ok(()) => {
                let next: Option<String> = sqlx::query_scalar(
                    "SELECT MIN(next_try_at) FROM push_deliveries WHERE status = 'queued'",
                )
                .fetch_one(&state.db)
                .await
                .unwrap_or(None);
                next.and_then(|t| crate::display_time::parse_db_utc(&t))
                    .map(|t| (t - now).num_seconds().clamp(1, 3600) as u64)
                    .unwrap_or(3600)
            }
            Err(err) => {
                tracing::error!(%err, "the push sender couldn't read its queue");
                60
            }
        };
        tokio::select! {
            _ = state.push.wake.notified() => {}
            _ = tokio::time::sleep(Duration::from_secs(wait)) => {}
        }
    }
}

/// A subscription's delivery health (§6), derived when read.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Health {
    Ok,
    /// 3 or more pushes without a receipt since the last one, or the oldest a day old.
    MayNotShow,
    /// The newest finished push to it failed and none was accepted after it: they don't reach
    /// the push service (a blocked outbound 443, its refusal) - qa-35-s2-code M1.
    NotGettingThrough,
    Gone,
    NeverSent,
}

/// [Health] from a subscription's state, whether its newest finished push failed, and its
/// unconfirmed pushes (accepted, no receipt, after its last receipt).
pub fn health(
    gone: bool,
    latest_failed: bool,
    ever_accepted: bool,
    unconfirmed: i64,
    oldest_unconfirmed: Option<DateTime<Utc>>,
    now: DateTime<Utc>,
) -> Health {
    if gone {
        Health::Gone
    } else if latest_failed {
        Health::NotGettingThrough
    } else if unconfirmed >= UNCONFIRMED_MAX
        || oldest_unconfirmed.is_some_and(|t| now - t >= chrono::Duration::hours(UNCONFIRMED_HOURS))
    {
        Health::MayNotShow
    } else if !ever_accepted {
        Health::NeverSent
    } else {
        Health::Ok
    }
}

/// One of the admin's subscriptions, for Getting Notifications and the warnings.
pub struct Subscription {
    pub id: i64,
    pub label: String,
    pub last_receipt_at: Option<DateTime<Utc>>,
    pub health: Health,
    /// Why the newest finished push failed ([failure_text]), with [Health::NotGettingThrough].
    pub failure: Option<String>,
    /// A push waits to go (queued, or to be tried again).
    pub sending: bool,
}

/// Why a push failed, in words: the push service out of reach (a network error, 429, 5xx - also
/// once retries ran past the TTL), its refusal of the server's signature, another refusal, or
/// the server's own problem.
pub fn failure_text(http_status: Option<i64>, reason: Option<&str>) -> String {
    match (http_status, reason) {
        (_, Some("too_large" | "bad_key" | "endpoint_not_allowed")) => {
            "The server couldn't send it".to_string()
        }
        (None | Some(429) | Some(500..), _) => "The push service couldn't be reached".to_string(),
        (Some(401 | 403), _) => "The push service refused the server's signature".to_string(),
        (Some(status), _) => format!("The push service refused it ({status})"),
    }
}

/// The admin's subscriptions, oldest first, with their health.
pub async fn subscriptions(
    db: &sqlx::SqlitePool,
    admin_id: i64,
    now: DateTime<Utc>,
) -> Result<Vec<Subscription>, sqlx::Error> {
    type Row = (
        i64,
        String,
        String,
        Option<String>,
        i64,
        Option<String>,
        bool,
        Option<String>,
        Option<i64>,
        Option<String>,
        bool,
    );
    // The newest finished delivery (accepted or failed) by id: its status, push-service status
    // and reason.
    const LATEST: &str = "FROM push_deliveries f WHERE f.subscription_id = s.id \
         AND f.status IN ('accepted', 'failed') ORDER BY f.id DESC LIMIT 1";
    let rows: Vec<Row> = sqlx::query_as(&format!(
        "SELECT s.id, s.label, s.state, s.last_receipt_at, \
                COUNT(u.id), MIN(u.accepted_at), \
                EXISTS (SELECT 1 FROM push_deliveries a \
                        WHERE a.subscription_id = s.id AND a.accepted_at IS NOT NULL), \
                (SELECT f.status {LATEST}), (SELECT f.http_status {LATEST}), \
                (SELECT f.reason {LATEST}), \
                EXISTS (SELECT 1 FROM push_deliveries q \
                        WHERE q.subscription_id = s.id AND q.status = 'queued') \
         FROM push_subscriptions s \
         LEFT JOIN push_deliveries u ON u.subscription_id = s.id AND u.status = 'accepted' \
              AND u.receipt_at IS NULL AND u.accepted_at > COALESCE(s.last_receipt_at, '') \
         WHERE s.admin_id = ? GROUP BY s.id ORDER BY s.id"
    ))
    .bind(admin_id)
    .fetch_all(db)
    .await?;
    Ok(rows
        .into_iter()
        .map(
            |(
                id,
                label,
                state,
                last_receipt,
                unconfirmed,
                oldest,
                ever,
                latest,
                latest_status,
                latest_reason,
                sending,
            )| {
                let parse =
                    |t: Option<String>| t.as_deref().and_then(crate::display_time::parse_db_utc);
                let failed = latest.as_deref() == Some("failed");
                Subscription {
                    id,
                    label,
                    last_receipt_at: parse(last_receipt),
                    health: health(
                        state == "gone",
                        failed,
                        ever,
                        unconfirmed,
                        parse(oldest),
                        now,
                    ),
                    failure: failed.then(|| failure_text(latest_status, latest_reason.as_deref())),
                    sending,
                }
            },
        )
        .collect())
}

/// The hashes ([endpoint_hash]) of every active subscription, every admin's: This Device drops a
/// browser's subscription only when no account knows it (qa-35-s2-code L1: two parents on one
/// family iPad).
pub async fn active_endpoint_hashes(db: &sqlx::SqlitePool) -> Result<Vec<String>, sqlx::Error> {
    let endpoints: Vec<String> =
        sqlx::query_scalar("SELECT endpoint FROM push_subscriptions WHERE state = 'active'")
            .fetch_all(db)
            .await?;
    Ok(endpoints.iter().map(|e| endpoint_hash(e)).collect())
}

/// The 16-hex SHA-256 prefix of an endpoint: what the page names a subscription by (§5.2; the
/// endpoint itself never reaches a page).
pub fn endpoint_hash(endpoint: &str) -> String {
    hex::encode(&Sha256::digest(endpoint.as_bytes())[..8])
}

/// The server-level warning (§4.4): a push refused for the server's signature (401/403) in the
/// last day, and none accepted since.
pub async fn signature_refused(db: &sqlx::SqlitePool, now: DateTime<Utc>) -> bool {
    sqlx::query_scalar(
        "SELECT EXISTS (SELECT 1 FROM push_deliveries f \
           WHERE f.status = 'failed' AND f.http_status IN (401, 403) \
             AND f.created_at > datetime(?, '-1 day') \
             AND NOT EXISTS (SELECT 1 FROM push_deliveries a WHERE a.accepted_at >= f.created_at))",
    )
    .bind(db_time(now))
    .fetch_one(db)
    .await
    .unwrap_or_else(|err| {
        tracing::error!(%err, "can't read the push failures");
        false
    })
}

/// What a receipt matched.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Receipt {
    /// The delivery's current token: marked.
    Matched,
    /// Not its current token, for a delivery sent more than once: a late receipt for an earlier
    /// attempt's token (a retry replaced it) - nothing marked, and not a miss against the client
    /// (qa-35-s2-code L3).
    Stale,
    /// No such delivery, or not a token of it.
    Unknown,
}

/// A receipt from the worker (§7): `token` (base64url) must match delivery `id`'s hash. On a
/// match the delivery, its subscription and - clicked - the event are marked.
pub async fn receipt(
    db: &sqlx::SqlitePool,
    id: i64,
    token: &str,
    clicked: bool,
) -> Result<Receipt, sqlx::Error> {
    let row: Option<(Vec<u8>, i64, Option<i64>, i64)> = sqlx::query_as(
        "SELECT token_hash, subscription_id, event_id, attempts FROM push_deliveries WHERE id = ?",
    )
    .bind(id)
    .fetch_optional(db)
    .await?;
    let Some((stored, subscription_id, event_id, attempts)) = row else {
        return Ok(Receipt::Unknown);
    };
    let miss = if attempts > 1 {
        Receipt::Stale
    } else {
        Receipt::Unknown
    };
    let Some(token) = B64
        .decode(token.trim_end_matches('='))
        .ok()
        .filter(|t| t.len() == 16)
    else {
        return Ok(Receipt::Unknown);
    };
    let hash = Sha256::digest(&token);
    // Constant time over the 32 bytes; an unsent delivery's empty hash never matches.
    if stored.len() != hash.len()
        || stored
            .iter()
            .zip(hash.iter())
            .fold(0u8, |acc, (a, b)| acc | (a ^ b))
            != 0
    {
        return Ok(miss);
    }
    let mut tx = db.begin().await?;
    sqlx::query(
        "UPDATE push_deliveries SET receipt_at = COALESCE(receipt_at, datetime('now')), \
         clicked_at = CASE WHEN ? THEN COALESCE(clicked_at, datetime('now')) ELSE clicked_at END \
         WHERE id = ?",
    )
    .bind(clicked)
    .bind(id)
    .execute(&mut *tx)
    .await?;
    sqlx::query("UPDATE push_subscriptions SET last_receipt_at = datetime('now') WHERE id = ?")
        .bind(subscription_id)
        .execute(&mut *tx)
        .await?;
    if clicked && let Some(event_id) = event_id {
        sqlx::query(
            "UPDATE parent_events SET read_at = COALESCE(read_at, datetime('now')) WHERE id = ?",
        )
        .bind(event_id)
        .execute(&mut *tx)
        .await?;
    }
    tx.commit().await?;
    Ok(Receipt::Matched)
}

/// "iPhone · Safari" from a User-Agent (the raw string is never stored, §3).
pub fn label_of(user_agent: &str) -> String {
    let ua = user_agent;
    let device = if ua.contains("iPhone") {
        "iPhone"
    } else if ua.contains("iPad") {
        "iPad"
    } else if ua.contains("Android") {
        "Android"
    } else if ua.contains("Macintosh") {
        "Mac"
    } else if ua.contains("Windows") {
        "Windows"
    } else if ua.contains("Linux") || ua.contains("CrOS") {
        "Linux"
    } else {
        "Device"
    };
    let browser = if ua.contains("Edg/") || ua.contains("EdgA/") {
        "Edge"
    } else if ua.contains("Firefox/") || ua.contains("FxiOS/") {
        "Firefox"
    } else if ua.contains("SamsungBrowser/") {
        "Samsung Internet"
    } else if ua.contains("Chrome/") || ua.contains("CriOS/") {
        "Chrome"
    } else if ua.contains("Safari/") || matches!(device, "iPhone" | "iPad") {
        // A Home Screen web app on iOS says no "Safari/"; it is WebKit's either way.
        "Safari"
    } else {
        "Browser"
    };
    format!("{device} · {browser}")
}

#[cfg(test)]
mod tests {
    use super::*;

    fn utc(s: &str) -> DateTime<Utc> {
        crate::display_time::parse_db_utc(s).unwrap()
    }

    #[test]
    fn the_answer_table() {
        let answer = |status: u16, retry_after: Option<&str>, reason: Option<&str>| {
            Ok(PushAnswer {
                status,
                retry_after: retry_after.map(str::to_string),
                reason: reason.map(str::to_string),
                apns_id: None,
            })
        };
        assert_eq!(outcome(&answer(201, None, None)), Outcome::Accepted);
        assert_eq!(outcome(&answer(404, None, None)), Outcome::Gone);
        assert_eq!(
            outcome(&answer(410, None, Some("Unregistered"))),
            Outcome::Gone
        );
        // H1: the server's signature, never the subscription's end.
        assert_eq!(
            outcome(&answer(403, None, Some("BadJwtToken"))),
            Outcome::BadSignature
        );
        assert_eq!(outcome(&answer(401, None, None)), Outcome::BadSignature);
        assert_eq!(
            outcome(&answer(429, Some("120"), None)),
            Outcome::Retry(Some(120))
        );
        assert_eq!(
            outcome(&answer(503, Some("99999"), None)),
            Outcome::Retry(Some(7200))
        );
        assert_eq!(
            outcome(&answer(500, Some("a date"), None)),
            Outcome::Retry(None)
        );
        assert_eq!(outcome(&Err("timed out".to_string())), Outcome::Retry(None));
        for status in [400, 413, 501] {
            assert_eq!(
                outcome(&answer(status, None, None)),
                Outcome::Failed,
                "{status}"
            );
        }
    }

    #[test]
    fn health_rules() {
        let now = utc("2026-10-18 12:00:00");
        let hour_ago = Some(utc("2026-10-18 11:00:00"));
        assert_eq!(health(true, true, true, 5, hour_ago, now), Health::Gone);
        assert_eq!(health(false, false, false, 0, None, now), Health::NeverSent);
        assert_eq!(health(false, false, true, 0, None, now), Health::Ok);
        assert_eq!(health(false, false, true, 2, hour_ago, now), Health::Ok);
        assert_eq!(
            health(false, false, true, 3, hour_ago, now),
            Health::MayNotShow
        );
        assert_eq!(
            health(false, false, true, 1, Some(utc("2026-10-17 12:00:00")), now),
            Health::MayNotShow
        );
        // M1: the newest finished push failed - before it was ever accepted too.
        assert_eq!(
            health(false, true, false, 0, None, now),
            Health::NotGettingThrough
        );
        assert_eq!(
            health(false, true, true, 3, hour_ago, now),
            Health::NotGettingThrough
        );
        assert_eq!(
            failure_text(None, Some("network")),
            "The push service couldn't be reached"
        );
        assert_eq!(
            failure_text(Some(503), Some("expired")),
            "The push service couldn't be reached"
        );
        assert_eq!(
            failure_text(Some(400), Some("BadDeviceToken")),
            "The push service refused it (400)"
        );
        assert_eq!(
            failure_text(Some(403), None),
            "The push service refused the server's signature"
        );
        assert_eq!(
            failure_text(None, Some("too_large")),
            "The server couldn't send it"
        );
    }

    #[test]
    fn the_jwt_is_reused_for_an_hour_per_aud_and_sub() {
        struct Never;
        impl Transport for Never {
            fn send(&self, _: PushRequest) -> BoxFut<'_, Result<PushAnswer, String>> {
                unreachable!()
            }
        }
        let key = VapidKey::from_pkcs8(&VapidKey::generate_pkcs8()).unwrap();
        let push = Push::new(None, None, Arc::new(Never));
        let (aud, sub) = (
            "https://fcm.googleapis.com",
            Some("https://kid.example.ts.net"),
        );
        let first = push.jwt(&key, aud, sub, 1_000_000);
        assert_eq!(push.jwt(&key, aud, sub, 1_000_000 + 3600), first);
        // Another service or contact, an hour on, or a clock that went back: a new one.
        assert_ne!(
            push.jwt(&key, "https://web.push.apple.com", sub, 1_000_000),
            first
        );
        assert_ne!(
            push.jwt(&key, aud, Some("mailto:p@example.org"), 1_000_000),
            first
        );
        assert_ne!(push.jwt(&key, aud, sub, 1_000_000 + 3601), first);
        let later = push.jwt(&key, aud, sub, 1_000_000 + 3601);
        assert_ne!(push.jwt(&key, aud, sub, 999_000), later);
        let again = push.jwt(&key, aud, sub, 999_000);
        push.forget_jwt(aud, sub);
        assert_ne!(push.jwt(&key, aud, sub, 999_000), again);
    }

    #[test]
    fn subjects_labels_and_test_spacing() {
        assert_eq!(subject_of(None), Ok(None));
        assert_eq!(subject_of(Some(" ")), Ok(None));
        assert_eq!(
            subject_of(Some("mailto:parent@example.org")),
            Ok(Some("mailto:parent@example.org".to_string()))
        );
        assert!(subject_of(Some("parent@example.org")).is_err());
        for (ua, label) in [
            (
                "Mozilla/5.0 (iPhone; CPU iPhone OS 18_4 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Mobile/15E148",
                "iPhone · Safari",
            ),
            (
                "Mozilla/5.0 (Linux; Android 15; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36",
                "Android · Chrome",
            ),
            (
                "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Safari/605.1.15",
                "Mac · Safari",
            ),
            (
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:131.0) Gecko/20100101 Firefox/131.0",
                "Windows · Firefox",
            ),
            ("", "Device · Browser"),
        ] {
            assert_eq!(label_of(ua), label);
        }
        struct Never;
        impl Transport for Never {
            fn send(&self, _: PushRequest) -> BoxFut<'_, Result<PushAnswer, String>> {
                unreachable!()
            }
        }
        let push = Push::new(None, None, Arc::new(Never));
        assert!(push.take_test_slot(1, 100));
        assert!(!push.take_test_slot(1, 129));
        assert!(push.take_test_slot(2, 129));
        assert!(push.take_test_slot(1, 130));
    }
}
