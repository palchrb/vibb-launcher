//! Security events that can arrive in floods (design 22 §3.2.7, QA c): a refused admin request, a
//! failed enrollment, a failed device auth. Each one is written at most once per kind and key
//! (the client's limit key) per minute: the first of a minute is inserted at once, the rest are
//! only counted, and [EventThrottle::flush] (every 30 s from `main`, [run_flusher]) writes the
//! count into that row's detail. So a flood adds one row a minute per client, never one per
//! request.

use std::collections::HashMap;
use std::sync::Mutex;
use std::time::{Duration, Instant};

use sqlx::SqlitePool;

/// One row per kind and key per this long.
pub const WINDOW: Duration = Duration::from_secs(60);
/// At most this many keys are tracked; past it the oldest window is dropped (its count may be
/// lost - only under a flood from 10k clients at once).
pub const MAX_KEYS: usize = 10_000;

struct Window {
    started: Instant,
    /// The row written for this window, once its insert finished.
    row_id: Option<i64>,
    detail: String,
    count: u64,
    /// The count the row says.
    written: u64,
}

#[derive(Default)]
pub struct EventThrottle {
    windows: Mutex<HashMap<(String, String), Window>>,
}

impl EventThrottle {
    /// Records one event of `event_type` for `key`. `ip` and `detail` go into the row of the
    /// minute's first event.
    pub async fn record(
        &self,
        db: &SqlitePool,
        event_type: &str,
        key: &str,
        ip: Option<&str>,
        detail: &str,
    ) {
        let map_key = (event_type.to_string(), key.to_string());
        let finished = {
            let mut windows = self.windows.lock().unwrap_or_else(|e| e.into_inner());
            if let Some(window) = windows.get_mut(&map_key)
                && window.started.elapsed() < WINDOW
            {
                window.count += 1;
                return;
            }
            if windows.len() >= MAX_KEYS && !windows.contains_key(&map_key) {
                evict_oldest(&mut windows);
            }
            windows.insert(
                map_key.clone(),
                Window {
                    started: Instant::now(),
                    row_id: None,
                    detail: detail.to_string(),
                    count: 1,
                    written: 1,
                },
            )
        };
        // The minute before this one, if its count never reached the row.
        if let Some(old) = finished {
            write_count(db, &old).await;
        }
        let row_id: Option<i64> = sqlx::query_scalar(
            "INSERT INTO security_events (event_type, username, ip_address, detail) \
             VALUES (?, NULL, ?, ?) RETURNING id",
        )
        .bind(event_type)
        .bind(ip)
        .bind(detail)
        .fetch_one(db)
        .await
        .ok();
        let mut windows = self.windows.lock().unwrap_or_else(|e| e.into_inner());
        if let Some(window) = windows.get_mut(&map_key) {
            window.row_id = row_id;
        }
    }

    /// Writes every window's count that its row doesn't show yet, and forgets windows that are
    /// over.
    pub async fn flush(&self, db: &SqlitePool) {
        let due: Vec<(i64, String, u64)> = {
            let mut windows = self.windows.lock().unwrap_or_else(|e| e.into_inner());
            let mut due = Vec::new();
            windows.retain(|_, window| {
                if let Some(row_id) = window.row_id
                    && window.count != window.written
                {
                    due.push((row_id, window.detail.clone(), window.count));
                    window.written = window.count;
                }
                window.started.elapsed() < WINDOW || window.row_id.is_none()
            });
            due
        };
        for (row_id, detail, count) in due {
            update_detail(db, row_id, &detail, count).await;
        }
    }
}

fn evict_oldest(windows: &mut HashMap<(String, String), Window>) {
    if let Some(oldest) = windows
        .iter()
        .min_by_key(|(_, window)| window.started)
        .map(|(key, _)| key.clone())
    {
        windows.remove(&oldest);
    }
}

async fn write_count(db: &SqlitePool, window: &Window) {
    if let Some(row_id) = window.row_id
        && window.count != window.written
    {
        update_detail(db, row_id, &window.detail, window.count).await;
    }
}

async fn update_detail(db: &SqlitePool, row_id: i64, detail: &str, count: u64) {
    sqlx::query("UPDATE security_events SET detail = ? WHERE id = ?")
        .bind(counted(detail, count))
        .bind(row_id)
        .execute(db)
        .await
        .ok();
}

/// The detail a row ends up with: "<detail> (N times in a minute)".
pub fn counted(detail: &str, count: u64) -> String {
    if count <= 1 {
        detail.to_string()
    } else if detail.is_empty() {
        format!("{count} times in a minute")
    } else {
        format!("{detail} ({count} times in a minute)")
    }
}

/// Spawned from `main` only: [EventThrottle::flush] every 30 s.
pub async fn run_flusher(state: crate::AppState) {
    let mut ticker = tokio::time::interval(Duration::from_secs(30));
    loop {
        ticker.tick().await;
        state.audit.flush(&state.db).await;
    }
}
