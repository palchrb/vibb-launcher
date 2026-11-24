//! Google's attestation status list (design 129 §3 item 3, §6.3): `https://android.googleapis.com/attestation/status`,
//! the serials Google has `REVOKED` or `SUSPENDED`. Fetched in the background - a plain GET with no device data -,
//! honouring its `Cache-Control` (at least hourly, at most daily), and the last good copy kept on disk
//! (`data/attestation/status.json`), so a restart without the network still has it.
//!
//! **A list older than [STALE_AFTER] stops registrations** (503 `list_stale` with `retry_after_s`; the phone retries)
//! and puts one orange line on Settings; keys already stored keep working (§6.3). Each good fetch is read against
//! every stored key's serials: a key whose chain is listed gets `revoked_at` (About This Phone's quiet value in S1).

use std::collections::HashSet;
use std::sync::{Arc, RwLock};
use std::time::Duration;

use chrono::{DateTime, Utc};

pub const URL: &str = "https://android.googleapis.com/attestation/status";
/// A list older than this stops registrations.
pub const STALE_AFTER: chrono::TimeDelta = chrono::TimeDelta::days(7);
/// The refresh, whatever `Cache-Control` says: at least hourly, at most daily.
pub const MIN_REFRESH: Duration = Duration::from_secs(3600);
pub const MAX_REFRESH: Duration = Duration::from_secs(24 * 3600);
/// What a refused registration waits before it asks again (`retry_after_s`).
pub const RETRY_AFTER_S: u64 = 3600;
/// The largest body read (the list was ~200 KB in 2026-11).
const MAX_BODY: usize = 16 * 1024 * 1024;

struct Loaded {
    revoked: Arc<HashSet<String>>,
    fetched_at: DateTime<Utc>,
}

/// The list in memory.
#[derive(Default)]
pub struct StatusList {
    loaded: RwLock<Option<Loaded>>,
    /// A fetch failed since the last good one (with no copy, the Settings line waits for this - not the first
    /// seconds after a start).
    failed: std::sync::atomic::AtomicBool,
}

impl StatusList {
    /// Takes a list's body (Google's JSON) fetched at `fetched_at`; the number of listed serials, or why not.
    pub fn take(&self, body: &[u8], fetched_at: DateTime<Utc>) -> Result<usize, String> {
        let revoked = parse(body)?;
        let fetched_at = fetched_at.min(Utc::now());
        let count = revoked.len();
        *self.loaded.write().unwrap_or_else(|e| e.into_inner()) = Some(Loaded {
            revoked: Arc::new(revoked),
            fetched_at,
        });
        self.failed
            .store(false, std::sync::atomic::Ordering::Relaxed);
        Ok(count)
    }

    /// The revoked serials, while the list is fresh at `now`; `None` = stale or never fetched.
    pub fn fresh(&self, now: DateTime<Utc>) -> Option<Arc<HashSet<String>>> {
        let loaded = self.loaded.read().unwrap_or_else(|e| e.into_inner());
        loaded
            .as_ref()
            .filter(|l| now - l.fetched_at <= STALE_AFTER)
            .map(|l| Arc::clone(&l.revoked))
    }

    /// The revoked serials whatever the age (the daily read of stored keys).
    pub fn any(&self) -> Option<Arc<HashSet<String>>> {
        let loaded = self.loaded.read().unwrap_or_else(|e| e.into_inner());
        loaded.as_ref().map(|l| Arc::clone(&l.revoked))
    }

    /// Settings' orange line (§9.1 `list_stale`): stale at `now`, and either a copy exists (it aged) or a fetch has
    /// failed.
    pub fn stale_line(&self, now: DateTime<Utc>) -> bool {
        let loaded = self.loaded.read().unwrap_or_else(|e| e.into_inner());
        match loaded.as_ref() {
            Some(l) => now - l.fetched_at > STALE_AFTER,
            None => self.failed.load(std::sync::atomic::Ordering::Relaxed),
        }
    }

    pub fn fetched_at(&self) -> Option<DateTime<Utc>> {
        let loaded = self.loaded.read().unwrap_or_else(|e| e.into_inner());
        loaded.as_ref().map(|l| l.fetched_at)
    }

    fn failed(&self) {
        self.failed
            .store(true, std::sync::atomic::Ordering::Relaxed);
    }
}

/// Google's form: `{"entries": {"<serial>": {"status": "REVOKED"|"SUSPENDED", ...}}}`. Both statuses refuse; the
/// serials are read lowercase. Anything else is not a list (the last good copy stays).
pub fn parse(body: &[u8]) -> Result<HashSet<String>, String> {
    let value: serde_json::Value =
        serde_json::from_slice(body).map_err(|e| format!("not JSON: {e}"))?;
    let entries = value
        .get("entries")
        .and_then(|e| e.as_object())
        .ok_or("no entries object")?;
    Ok(entries
        .iter()
        .filter(|(_, v)| {
            matches!(
                v.get("status").and_then(|s| s.as_str()),
                Some("REVOKED" | "SUSPENDED")
            )
        })
        .map(|(k, _)| k.to_ascii_lowercase())
        .collect())
}

/// `max-age` from a `Cache-Control`, clamped to [MIN_REFRESH, MAX_REFRESH]; [MAX_REFRESH] without one.
pub fn refresh_after(cache_control: Option<&str>) -> Duration {
    let age = cache_control.and_then(|value| {
        value.split(',').find_map(|part| {
            part.trim()
                .strip_prefix("max-age=")
                .and_then(|n| n.trim().parse::<u64>().ok())
        })
    });
    age.map(Duration::from_secs)
        .unwrap_or(MAX_REFRESH)
        .clamp(MIN_REFRESH, MAX_REFRESH)
}

#[derive(serde::Serialize, serde::Deserialize)]
struct OnDisk {
    fetched_at_ms: i64,
    list: serde_json::Value,
}

fn disk_path(data_dir: &std::path::Path) -> std::path::PathBuf {
    data_dir.join("attestation").join("status.json")
}

/// The last good copy from disk, if any.
pub async fn load(state: &crate::AppState) {
    let path = disk_path(&state.data_dir);
    let Ok(bytes) = tokio::fs::read(&path).await else {
        return;
    };
    let Ok(disk) = serde_json::from_slice::<OnDisk>(&bytes) else {
        tracing::warn!(
            "{} isn't readable - waiting for Google's list",
            path.display()
        );
        return;
    };
    let Some(at) = DateTime::<Utc>::from_timestamp_millis(disk.fetched_at_ms) else {
        return;
    };
    // A copy stamped in the future (a clock set back since) is read as fetched now, never as fresh for longer (L7).
    let at = at.min(Utc::now());
    match state
        .device_keys
        .status
        .take(disk.list.to_string().as_bytes(), at)
    {
        Ok(n) => tracing::info!("attestation status list from disk: {n} serials, fetched {at}"),
        Err(err) => tracing::warn!(%err, "the stored attestation status list isn't a list"),
    }
}

async fn store(data_dir: &std::path::Path, body: &[u8], at: DateTime<Utc>) -> std::io::Result<()> {
    let list: serde_json::Value = serde_json::from_slice(body)?;
    let path = disk_path(data_dir);
    if let Some(dir) = path.parent() {
        tokio::fs::create_dir_all(dir).await?;
    }
    let tmp = path.with_extension("json.tmp");
    let disk = OnDisk {
        fetched_at_ms: at.timestamp_millis(),
        list,
    };
    tokio::fs::write(&tmp, serde_json::to_vec(&disk)?).await?;
    tokio::fs::rename(&tmp, &path).await
}

/// Fetches the list now and then for as long as the server runs (started by `main`, never by the tests).
pub async fn run(state: crate::AppState, url: String) {
    load(&state).await;
    let client = match reqwest::Client::builder()
        .user_agent(crate::net::USER_AGENT)
        .timeout(Duration::from_secs(60))
        .build()
    {
        Ok(client) => client,
        Err(err) => {
            tracing::error!(%err, "no client for the attestation status list");
            return;
        }
    };
    loop {
        let wait = match fetch(&client, &url).await {
            Ok((body, cache_control)) => {
                let now = Utc::now();
                match state.device_keys.status.take(&body, now) {
                    Ok(n) => {
                        tracing::info!("attestation status list: {n} revoked or suspended serials");
                        if let Err(err) = store(&state.data_dir, &body, now).await {
                            tracing::warn!(%err, "can't keep the attestation status list on disk");
                        }
                        crate::device_key::mark_revoked(&state).await;
                        refresh_after(cache_control.as_deref())
                    }
                    Err(err) => {
                        tracing::warn!(%err, "Google's attestation status list isn't a list");
                        state.device_keys.status.failed();
                        MIN_REFRESH
                    }
                }
            }
            Err(err) => {
                tracing::warn!(%err, "can't fetch Google's attestation status list");
                state.device_keys.status.failed();
                MIN_REFRESH
            }
        };
        tokio::time::sleep(wait).await;
    }
}

async fn fetch(client: &reqwest::Client, url: &str) -> Result<(Vec<u8>, Option<String>), String> {
    let answer = client.get(url).send().await.map_err(|e| e.to_string())?;
    if !answer.status().is_success() {
        return Err(format!("status {}", answer.status()));
    }
    let cache_control = answer
        .headers()
        .get(reqwest::header::CACHE_CONTROL)
        .and_then(|v| v.to_str().ok())
        .map(str::to_string);
    if answer.content_length().is_some_and(|n| n > MAX_BODY as u64) {
        return Err("too large".into());
    }
    // Read as it streams, stopping at the cap (code QA L7): a list without a length can't grow without bound.
    let mut answer = answer;
    let mut body = Vec::new();
    while let Some(chunk) = answer.chunk().await.map_err(|e| e.to_string())? {
        if body.len() + chunk.len() > MAX_BODY {
            return Err("too large".into());
        }
        body.extend_from_slice(&chunk);
    }
    Ok((body, cache_control))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn reads_both_statuses_and_clamps_the_refresh() {
        let list = parse(
            br#"{"entries": {"ABC": {"status": "REVOKED", "reason": "KEY_COMPROMISE"},
                 "d1": {"status": "SUSPENDED"}, "e2": {"status": "SOMETHING_NEW"}}}"#,
        )
        .unwrap();
        assert_eq!(list, ["abc", "d1"].iter().map(|s| s.to_string()).collect());
        assert!(parse(b"[]").is_err());
        assert_eq!(refresh_after(Some("public, max-age=86400")), MAX_REFRESH);
        assert_eq!(refresh_after(Some("max-age=300")), MIN_REFRESH);
        assert_eq!(
            refresh_after(Some("max-age=7200")),
            Duration::from_secs(7200)
        );
        assert_eq!(refresh_after(None), MAX_REFRESH);
    }

    #[test]
    fn stale_after_a_week() {
        let list = StatusList::default();
        let now = Utc::now();
        assert!(list.fresh(now).is_none());
        assert!(
            !list.stale_line(now),
            "no line in a fresh start's first seconds"
        );
        list.failed();
        assert!(list.stale_line(now));
        list.take(br#"{"entries": {}}"#, now - chrono::TimeDelta::days(6))
            .unwrap();
        assert!(list.fresh(now).is_some());
        assert!(!list.stale_line(now));
        list.take(br#"{"entries": {}}"#, now - chrono::TimeDelta::days(8))
            .unwrap();
        assert!(list.fresh(now).is_none());
        assert!(list.stale_line(now));
    }
}
