//! FCM nudges for the phones (handy step 7): the dispatcher that turns `AppState.command_notify`
//! into FCM sends, the FCM health check, and the `device_push` bookkeeping.
//!
//! - **Fan-out without touching the call sites**: every handler that changes something for a
//!   phone already sends its id on `command_notify` (for the SSE stream). The dispatcher subscribes
//!   to the same channel, coalesces per device for 1 s (a global edit nudges every device once) and
//!   sends one FCM message per device that has a registration token. A `Lagged` receiver nudges
//!   every device with a token. The SSE stream keeps getting the same broadcast, so a phone on
//!   either transport is reached.
//! - **Health** ([Health]): each successful send records its nonce; the phone's next status report
//!   echoes the last nonce it received (`push.last_nudge_id`) - that's the acknowledgement. A send
//!   still unacknowledged after [ACK_WINDOW_SECS] counts as missed; [MAX_UNACKED] misses in a row
//!   set `fcm_ok = false`, so the policy tells the phone to go back to SSE. A periodic test nudge
//!   (every [TEST_INTERVAL_OK_SECS] while ok, [TEST_INTERVAL_NOT_OK_SECS] otherwise) proves a new
//!   token and re-proves a failed one. Worst case for a silent FCM failure: ring/lock/lifts wait
//!   for the phone's next backstop sync (30 min), and the switch back to SSE comes after two
//!   missed sends plus that sync.
//! - Sends are spawned and bounded ([MAX_CONCURRENT_SENDS]); the HTTP sender has its own timeouts,
//!   so one hung send can't stall the others. A supervisor restarts the dispatcher if it ends.

use std::collections::HashMap;
use std::sync::Arc;
use std::time::Duration;

use tokio::sync::broadcast::error::RecvError;
use tokio::sync::{Semaphore, broadcast, mpsc};
use tokio::time::Instant;

use crate::AppState;
use crate::fcm::{SendOutcome, new_nonce, token_hash};

pub const DEBOUNCE: Duration = Duration::from_secs(1);
pub const ACK_WINDOW_SECS: i64 = 60;
pub const MAX_UNACKED: i64 = 2;
pub const TEST_INTERVAL_OK_SECS: i64 = 6 * 3600;
pub const TEST_INTERVAL_NOT_OK_SECS: i64 = 3600;
pub const HEALTH_TICK: Duration = Duration::from_secs(5 * 60);
pub const MAX_CONCURRENT_SENDS: usize = 8;

// ---------------------------------------------------------------------------------------------
// Health (pure)
// ---------------------------------------------------------------------------------------------

/// FCM health of one device's current token. Times are unix seconds.
#[derive(Debug, Clone, Default, PartialEq, Eq, sqlx::FromRow)]
pub struct Health {
    pub fcm_ok: bool,
    pub last_send_at: Option<i64>,
    pub last_send_nonce: Option<String>,
    /// The last send is still within its ack window and not yet acknowledged or counted.
    pub send_pending: bool,
    pub unacked_sends: i64,
    pub last_ack_at: Option<i64>,
}

impl Health {
    /// Counts a pending send that is past its ack window as missed.
    pub fn settle(mut self, now: i64) -> Self {
        if self.send_pending
            && self
                .last_send_at
                .is_some_and(|at| now - at >= ACK_WINDOW_SECS)
        {
            self.send_pending = false;
            self.unacked_sends += 1;
            if self.unacked_sends >= MAX_UNACKED {
                self.fcm_ok = false;
            }
        }
        self
    }

    pub fn on_send(self, now: i64, nonce: &str) -> Self {
        let mut h = self.settle(now);
        h.last_send_at = Some(now);
        h.last_send_nonce = Some(nonce.to_string());
        h.send_pending = true;
        h
    }

    /// A status report; `reported` is its `push.last_nudge_id`. Matching the last sent nonce (also
    /// late, after it was counted as missed) proves FCM works for this token.
    pub fn on_report(self, reported: Option<&str>, now: i64) -> Self {
        let acked = reported.is_some() && reported == self.last_send_nonce.as_deref();
        let mut h = self;
        if acked {
            h.fcm_ok = true;
            h.unacked_sends = 0;
            h.send_pending = false;
            h.last_ack_at = Some(now);
            h
        } else {
            h.settle(now)
        }
    }

    pub fn on_tick(self, now: i64) -> Self {
        self.settle(now)
    }

    /// A new (or no) token: nothing proven yet.
    pub fn reset() -> Self {
        Health::default()
    }

    /// Time for a periodic test nudge.
    pub fn test_due(&self, now: i64) -> bool {
        let interval = if self.fcm_ok {
            TEST_INTERVAL_OK_SECS
        } else {
            TEST_INTERVAL_NOT_OK_SECS
        };
        self.last_send_at.is_none_or(|at| now - at >= interval)
    }
}

// ---------------------------------------------------------------------------------------------
// device_push rows
// ---------------------------------------------------------------------------------------------

#[derive(Debug, Clone, Default, sqlx::FromRow)]
pub struct DevicePush {
    pub device_id: i64,
    pub fcm_token: Option<String>,
    pub fcm_token_updated_at: Option<String>,
    pub fcm_token_seen_at: Option<String>,
    pub push_transport: Option<String>,
    pub last_nudge_at: Option<String>,
    pub fcm_ok: bool,
    pub last_fcm_error: Option<String>,
    pub last_fcm_error_at: Option<String>,
    pub last_send_at: Option<i64>,
    pub last_send_nonce: Option<String>,
    pub send_pending: bool,
    pub unacked_sends: i64,
    pub last_ack_at: Option<i64>,
    /// Hash of the last token FCM rejected (see [record_report]).
    pub rejected_token_hash: Option<String>,
    /// "fid" or "token" as the phone reported it (migrations/0035); `None` = older launcher.
    pub fcm_token_kind: Option<String>,
}

impl DevicePush {
    pub fn health(&self) -> Health {
        Health {
            fcm_ok: self.fcm_ok,
            last_send_at: self.last_send_at,
            last_send_nonce: self.last_send_nonce.clone(),
            send_pending: self.send_pending,
            unacked_sends: self.unacked_sends,
            last_ack_at: self.last_ack_at,
        }
    }
}

pub async fn load(
    db: &sqlx::SqlitePool,
    device_id: i64,
) -> Result<Option<DevicePush>, sqlx::Error> {
    sqlx::query_as::<_, DevicePush>("SELECT * FROM device_push WHERE device_id = ?")
        .bind(device_id)
        .fetch_optional(db)
        .await
}

async fn store_health(
    db: &sqlx::SqlitePool,
    device_id: i64,
    h: &Health,
) -> Result<(), sqlx::Error> {
    sqlx::query(
        "UPDATE device_push SET fcm_ok = ?, last_send_at = ?, last_send_nonce = ?, \
         send_pending = ?, unacked_sends = ?, last_ack_at = ? WHERE device_id = ?",
    )
    .bind(h.fcm_ok)
    .bind(h.last_send_at)
    .bind(&h.last_send_nonce)
    .bind(h.send_pending)
    .bind(h.unacked_sends)
    .bind(h.last_ack_at)
    .bind(device_id)
    .execute(db)
    .await?;
    Ok(())
}

/// `PolicyResponse.push` - always sent.
#[derive(serde::Serialize, Debug, Clone, PartialEq)]
pub struct PushPolicy {
    pub fcm_enabled: bool,
    pub fcm_ok: bool,
    pub fcm_token_hash: Option<String>,
}

pub async fn push_policy(state: &AppState, device_id: i64) -> Result<PushPolicy, sqlx::Error> {
    let row = load(&state.db, device_id).await?;
    let enabled = state.fcm.is_some();
    Ok(PushPolicy {
        fcm_enabled: enabled,
        fcm_ok: enabled
            && row
                .as_ref()
                .is_some_and(|r| r.fcm_ok && r.fcm_token.is_some()),
        fcm_token_hash: row
            .and_then(|r| r.fcm_token)
            .map(|token| token_hash(&token)),
    })
}

/// `StatusReportRequest.push`.
#[derive(serde::Deserialize, Debug, Default, Clone)]
pub struct PushReport {
    #[serde(default)]
    pub fcm_token: Option<String>,
    /// "fid" (Firebase installation ID) or "token" (legacy registration token); absent from
    /// launchers before the FID switch.
    #[serde(default)]
    pub fcm_token_kind: Option<String>,
    #[serde(default)]
    pub transport: Option<String>,
    #[serde(default)]
    pub fcm_configured: bool,
    #[serde(default)]
    pub gms_available: bool,
    #[serde(default)]
    pub last_nudge_ms: Option<i64>,
    #[serde(default)]
    pub last_nudge_id: Option<String>,
    #[serde(default)]
    pub last_priority: Option<String>,
    #[serde(default)]
    pub last_original_priority: Option<String>,
    #[serde(default)]
    pub reason: Option<String>,
}

/// A plausible FCM registration token: printable ASCII without spaces, bounded length.
fn valid_token(token: &str) -> bool {
    !token.is_empty() && token.len() <= 4096 && token.chars().all(|c| c.is_ascii_graphic())
}

/// Applies a status report's `push` object: stores the token (a new one starts unproven and gets a
/// test nudge at once), the transport and last nudge, and checks the acknowledgement.
pub async fn record_report(
    state: &AppState,
    device_id: i64,
    report: &PushReport,
) -> Result<(), sqlx::Error> {
    let now = chrono::Utc::now().timestamp();
    let reported = report.fcm_token.as_deref().filter(|t| valid_token(t));
    let last_nudge_at = report
        .last_nudge_ms
        .and_then(chrono::DateTime::from_timestamp_millis)
        .map(|t| t.format("%Y-%m-%d %H:%M:%S").to_string());
    let transport = report
        .transport
        .as_deref()
        .filter(|t| matches!(*t, "fcm" | "sse"));

    sqlx::query("INSERT OR IGNORE INTO device_push (device_id) VALUES (?)")
        .bind(device_id)
        .execute(&state.db)
        .await?;
    let row = load(&state.db, device_id).await?.unwrap_or_default();
    // A token FCM already rejected for this device is ignored until the phone renews it (QA step
    // 7 #2): storing it again would test-nudge, fail and log it on every report for up to a day.
    let token =
        reported.filter(|t| row.rejected_token_hash.as_deref() != Some(token_hash(t).as_str()));

    let token_changed = row.fcm_token.as_deref() != token;
    let kind = token.and(
        report
            .fcm_token_kind
            .as_deref()
            .filter(|k| matches!(*k, "fid" | "token")),
    );
    let health = if token_changed {
        Health::reset()
    } else {
        row.health().on_report(report.last_nudge_id.as_deref(), now)
    };

    if token_changed {
        // One device per token (QA step 7 #3): a re-enrolled phone keeps its Firebase token, and
        // the old row would otherwise keep test-nudging it and its nonces would race this row's.
        if let Some(t) = token {
            sqlx::query(
                "UPDATE device_push SET fcm_token = NULL, fcm_ok = 0, send_pending = 0, \
                 unacked_sends = 0, last_send_nonce = NULL WHERE fcm_token = ? AND device_id != ?",
            )
            .bind(t)
            .bind(device_id)
            .execute(&state.db)
            .await?;
        }
        sqlx::query(
            "UPDATE device_push SET fcm_token = ?, fcm_token_kind = ?, \
             fcm_token_updated_at = datetime('now'), last_fcm_error = NULL, \
             last_fcm_error_at = NULL WHERE device_id = ?",
        )
        .bind(token)
        .bind(kind)
        .bind(device_id)
        .execute(&state.db)
        .await?;
    }
    sqlx::query(
        "UPDATE device_push SET fcm_token_seen_at = CASE WHEN ? IS NULL THEN fcm_token_seen_at \
         ELSE datetime('now') END, fcm_token_kind = COALESCE(?, fcm_token_kind), \
         push_transport = COALESCE(?, push_transport), \
         last_nudge_at = COALESCE(?, last_nudge_at) WHERE device_id = ?",
    )
    .bind(token)
    .bind(kind)
    .bind(transport)
    .bind(&last_nudge_at)
    .bind(device_id)
    .execute(&state.db)
    .await?;
    store_health(&state.db, device_id, &health).await?;

    if token_changed && token.is_some() && state.fcm.is_some() {
        tracing::info!(
            device_id,
            token = %token_hash(token.unwrap_or_default()),
            "new FCM token, sending a test nudge"
        );
        let state = state.clone();
        tokio::spawn(async move { nudge(&state, device_id).await });
    }
    Ok(())
}

// ---------------------------------------------------------------------------------------------
// Sending
// ---------------------------------------------------------------------------------------------

/// Sends one FCM nudge to a device if it has a token, and records the outcome. A no-op when FCM is
/// off or the device has no token.
pub async fn nudge(state: &AppState, device_id: i64) {
    let Some(sender) = state.fcm.clone() else {
        return;
    };
    let row = match load(&state.db, device_id).await {
        Ok(Some(row)) => row,
        Ok(None) => return,
        Err(err) => {
            tracing::error!(device_id, %err, "can't read device_push");
            return;
        }
    };
    let Some(token) = row.fcm_token.clone() else {
        return;
    };
    let nonce = new_nonce();
    let target = crate::fcm::Target {
        id: &token,
        kind: crate::fcm::TargetKind::of(&token, row.fcm_token_kind.as_deref()),
    };
    let outcome = sender.send(target, &nonce).await;
    let now = chrono::Utc::now().timestamp();
    let result = match &outcome {
        SendOutcome::Sent => {
            // Re-read: a status report may have changed the token meanwhile.
            match load(&state.db, device_id).await {
                Ok(Some(current)) if current.fcm_token.as_deref() == Some(token.as_str()) => {
                    store_health(&state.db, device_id, &current.health().on_send(now, &nonce)).await
                }
                Ok(_) => Ok(()),
                Err(err) => Err(err),
            }
        }
        SendOutcome::TokenDead(code) => clear_dead_token(state, device_id, &token, code).await,
        SendOutcome::Failed(reason) => sqlx::query(
            "UPDATE device_push SET last_fcm_error = ?, last_fcm_error_at = datetime('now') \
             WHERE device_id = ?",
        )
        .bind(reason)
        .bind(device_id)
        .execute(&state.db)
        .await
        .map(|_| ()),
    };
    if let Err(err) = result {
        tracing::error!(device_id, %err, "can't record an FCM send");
    }
    if let SendOutcome::Failed(reason) = outcome {
        tracing::warn!(device_id, %reason, "FCM nudge failed; the backstop sync covers it");
    }
}

/// FCM says the token is dead: forget it (only if it's still the stored one), mark FCM not ok and
/// log it once. The phone falls back to SSE and registers again.
async fn clear_dead_token(
    state: &AppState,
    device_id: i64,
    token: &str,
    code: &str,
) -> Result<(), sqlx::Error> {
    let cleared = sqlx::query(
        "UPDATE device_push SET fcm_token = NULL, fcm_token_updated_at = datetime('now'), \
         fcm_ok = 0, send_pending = 0, unacked_sends = 0, last_send_nonce = NULL, \
         last_fcm_error = ?, last_fcm_error_at = datetime('now'), rejected_token_hash = ? \
         WHERE device_id = ? AND fcm_token = ?",
    )
    .bind(format!("token rejected by FCM ({code})"))
    .bind(token_hash(token))
    .bind(device_id)
    .bind(token)
    .execute(&state.db)
    .await?
    .rows_affected();
    if cleared > 0 {
        crate::security::record_security_event(
            &state.db,
            "fcm_token_cleared",
            None,
            None,
            Some(&format!(
                "device {device_id}: FCM answered {code} for token {} - the phone uses SSE until \
                 it registers again",
                token_hash(token)
            )),
        )
        .await;
    }
    Ok(())
}

/// Every device that has a token.
async fn devices_with_token(db: &sqlx::SqlitePool) -> Vec<i64> {
    sqlx::query_scalar(
        "SELECT p.device_id FROM device_push p JOIN devices d ON d.id = p.device_id \
         WHERE p.fcm_token IS NOT NULL AND d.token_hash IS NOT NULL",
    )
    .fetch_all(db)
    .await
    .unwrap_or_else(|err| {
        tracing::error!(%err, "can't list devices with an FCM token");
        Vec::new()
    })
}

// ---------------------------------------------------------------------------------------------
// Dispatcher
// ---------------------------------------------------------------------------------------------

/// What the coalescer hands on.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Batch {
    Devices(Vec<i64>),
    /// The receiver lagged: some ids were lost, so nudge everyone.
    All,
}

/// Coalesces device ids from `rx` for `debounce` after each device's first event and emits them
/// as batches; `Lagged` emits [Batch::All] at once. Ends when `rx` closes or `out` is dropped.
pub async fn coalesce(
    mut rx: broadcast::Receiver<i64>,
    debounce: Duration,
    out: mpsc::UnboundedSender<Batch>,
) {
    let mut pending: HashMap<i64, Instant> = HashMap::new();
    loop {
        let next_due = pending.values().min().copied();
        tokio::select! {
            msg = rx.recv() => match msg {
                Ok(id) => {
                    pending.entry(id).or_insert_with(|| Instant::now() + debounce);
                }
                Err(RecvError::Lagged(skipped)) => {
                    tracing::warn!(skipped, "nudge dispatcher lagged, nudging every device");
                    pending.clear();
                    if out.send(Batch::All).is_err() {
                        return;
                    }
                }
                Err(RecvError::Closed) => return,
            },
            _ = async {
                match next_due {
                    Some(at) => tokio::time::sleep_until(at).await,
                    None => std::future::pending::<()>().await,
                }
            } => {
                let now = Instant::now();
                let mut due: Vec<i64> = pending
                    .iter()
                    .filter(|(_, at)| **at <= now)
                    .map(|(id, _)| *id)
                    .collect();
                due.sort_unstable();
                pending.retain(|_, at| *at > now);
                if !due.is_empty() && out.send(Batch::Devices(due)).is_err() {
                    return;
                }
            }
        }
    }
}

fn spawn_send(state: &AppState, permits: &Arc<Semaphore>, device_id: i64) {
    let state = state.clone();
    let permits = permits.clone();
    tokio::spawn(async move {
        let Ok(_permit) = permits.acquire_owned().await else {
            return;
        };
        nudge(&state, device_id).await;
    });
}

/// One dispatcher run: coalesce `command_notify` and send. Returns when the channel closes.
async fn run_dispatcher(state: AppState, permits: Arc<Semaphore>) {
    let rx = state.command_notify.subscribe();
    let (tx, mut batches) = mpsc::unbounded_channel();
    let coalescer = tokio::spawn(coalesce(rx, DEBOUNCE, tx));
    while let Some(batch) = batches.recv().await {
        let ids = match batch {
            Batch::Devices(ids) => ids,
            Batch::All => devices_with_token(&state.db).await,
        };
        for id in ids {
            spawn_send(&state, &permits, id);
        }
    }
    coalescer.abort();
}

/// One health pass: settle every device's pending send and send due test nudges.
pub async fn health_tick(state: &AppState, permits: &Arc<Semaphore>) {
    let now = chrono::Utc::now().timestamp();
    let rows = sqlx::query_as::<_, DevicePush>(
        "SELECT p.* FROM device_push p JOIN devices d ON d.id = p.device_id \
             WHERE p.fcm_token IS NOT NULL AND d.token_hash IS NOT NULL",
    )
    .fetch_all(&state.db)
    .await
    .unwrap_or_else(|err| {
        tracing::error!(%err, "FCM health tick: can't read device_push");
        Vec::new()
    });
    for row in rows {
        let before = row.health();
        let after = before.clone().on_tick(now);
        if after != before {
            if let Err(err) = store_health(&state.db, row.device_id, &after).await {
                tracing::error!(device_id = row.device_id, %err, "FCM health tick: write failed");
            }
            if before.fcm_ok && !after.fcm_ok {
                tracing::warn!(
                    device_id = row.device_id,
                    "FCM nudges aren't acknowledged - the phone goes back to SSE"
                );
            }
        }
        if after.test_due(now) {
            spawn_send(state, permits, row.device_id);
        }
    }
}

/// Spawns the dispatcher (restarted by a supervisor if it ever ends or panics) and the health tick.
/// Only when FCM is configured; called from `main`, never from `build_router`.
pub fn spawn(state: AppState) {
    if state.fcm.is_none() {
        return;
    }
    let permits = Arc::new(Semaphore::new(MAX_CONCURRENT_SENDS));
    {
        let state = state.clone();
        let permits = permits.clone();
        tokio::spawn(async move {
            loop {
                let run = tokio::spawn(run_dispatcher(state.clone(), permits.clone()));
                match run.await {
                    Ok(()) => tracing::warn!("nudge dispatcher ended, restarting"),
                    Err(err) => tracing::error!(%err, "nudge dispatcher crashed, restarting"),
                }
                tokio::time::sleep(Duration::from_secs(1)).await;
            }
        });
    }
    tokio::spawn(async move {
        let mut interval = tokio::time::interval(HEALTH_TICK);
        loop {
            interval.tick().await;
            health_tick(&state, &permits).await;
        }
    });
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn ack_proves_fcm() {
        let h = Health::reset().on_send(100, "a");
        assert!(h.send_pending && !h.fcm_ok);
        let h = h.on_report(Some("a"), 110);
        assert!(h.fcm_ok);
        assert_eq!(h.unacked_sends, 0);
        assert_eq!(h.last_ack_at, Some(110));
        assert!(!h.send_pending);
    }

    #[test]
    fn two_missed_sends_turn_fcm_off() {
        let ok = Health {
            fcm_ok: true,
            ..Health::default()
        };
        let h = ok.on_send(0, "a");
        // Within the window nothing counts yet.
        assert_eq!(h.clone().on_tick(59), h);
        let h = h.on_tick(60);
        assert_eq!(h.unacked_sends, 1);
        assert!(h.fcm_ok, "one miss is not enough");
        let h = h.on_send(100, "b").on_report(Some("a"), 120);
        assert!(h.send_pending, "an old nonce isn't an ack for the new send");
        let h = h.on_tick(160);
        assert_eq!(h.unacked_sends, 2);
        assert!(!h.fcm_ok);
        // Counted once only.
        assert_eq!(h.clone().on_tick(10_000).unacked_sends, 2);
        // A late ack still proves it again.
        let h = h.on_report(Some("b"), 200);
        assert!(h.fcm_ok);
        assert_eq!(h.unacked_sends, 0);
    }

    #[test]
    fn rapid_sends_dont_count_as_missed() {
        let h = Health::reset()
            .on_send(0, "a")
            .on_send(10, "b")
            .on_send(20, "c");
        assert_eq!(h.unacked_sends, 0);
        assert_eq!(h.last_send_nonce.as_deref(), Some("c"));
    }

    #[test]
    fn no_report_nonce_is_no_ack() {
        let h = Health::reset().on_report(None, 5);
        assert!(!h.fcm_ok);
        let h = Health::reset().on_send(0, "a").on_report(None, 5);
        assert!(!h.fcm_ok && h.send_pending);
    }

    #[test]
    fn test_nudge_cadence() {
        assert!(Health::reset().test_due(0));
        let not_ok = Health::reset().on_send(0, "a");
        assert!(!not_ok.test_due(TEST_INTERVAL_NOT_OK_SECS - 1));
        assert!(not_ok.test_due(TEST_INTERVAL_NOT_OK_SECS));
        let ok = not_ok.on_report(Some("a"), 1);
        assert!(!ok.test_due(TEST_INTERVAL_NOT_OK_SECS));
        assert!(ok.test_due(TEST_INTERVAL_OK_SECS));
    }

    #[tokio::test(start_paused = true)]
    async fn coalesces_per_device_for_one_second() {
        let (tx, rx) = broadcast::channel(64);
        let (out_tx, mut out) = mpsc::unbounded_channel();
        let task = tokio::spawn(coalesce(rx, DEBOUNCE, out_tx));
        tx.send(1).unwrap();
        tokio::time::sleep(Duration::from_millis(300)).await;
        tx.send(1).unwrap();
        tx.send(2).unwrap();
        tokio::time::sleep(Duration::from_millis(400)).await;
        assert!(out.try_recv().is_err(), "nothing before the first second");
        tokio::time::sleep(Duration::from_millis(400)).await;
        assert_eq!(out.recv().await, Some(Batch::Devices(vec![1])));
        tokio::time::sleep(Duration::from_millis(400)).await;
        assert_eq!(out.recv().await, Some(Batch::Devices(vec![2])));
        assert!(out.try_recv().is_err(), "device 1 was nudged once");
        drop(tx);
        task.await.unwrap();
    }

    #[tokio::test(start_paused = true)]
    async fn lag_nudges_everyone() {
        let (tx, rx) = broadcast::channel(2);
        for id in 0..10 {
            tx.send(id).unwrap();
        }
        let (out_tx, mut out) = mpsc::unbounded_channel();
        let task = tokio::spawn(coalesce(rx, DEBOUNCE, out_tx));
        assert_eq!(out.recv().await, Some(Batch::All));
        // What's left after the lag is still delivered.
        assert_eq!(out.recv().await, Some(Batch::Devices(vec![8, 9])));
        drop(tx);
        task.await.unwrap();
    }
}
