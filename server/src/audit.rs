//! Security events that can arrive in floods (design 22 §3.2.7, QA c): a refused admin request, a
//! failed enrollment, a failed device auth. Each one is written at most once per kind and key
//! (the client's limit key) per minute: the first of a minute is inserted at once, the rest are
//! only counted, and [EventThrottle::flush] (every 30 s from `main`, [run_flusher]) writes the
//! count into that row's detail. So a flood adds one row a minute per client, never one per
//! request. And per kind at most [ROWS_PER_MINUTE] such rows a minute across all clients: past it,
//! the minute's further clients are counted into one summary row ("N more from M addresses"), so
//! a client with many addresses (an IPv6 /48, a botnet) can't fill the disk (QA 22 M3).

use std::collections::HashMap;
use std::sync::Mutex;
use std::time::{Duration, Instant};

use sqlx::SqlitePool;

/// One row per kind and key per this long.
pub const WINDOW: Duration = Duration::from_secs(60);
/// At most this many keys are tracked; past it the oldest window is dropped (its count may be
/// lost). With [ROWS_PER_MINUTE] it isn't reached in practice.
pub const MAX_KEYS: usize = 10_000;
/// Rows per kind and minute across all keys; past it, one summary row counts the rest.
pub const ROWS_PER_MINUTE: u32 = 60;
/// Distinct addresses a summary row counts exactly; past it, "at least" this many.
const SUMMARY_KEYS: usize = 1_000;

/// One kind's budget for the current minute.
struct Budget {
    started: Instant,
    rows: u32,
    /// The summary row of this minute, once written; its counts.
    summary_row: Option<i64>,
    summary_count: u64,
    summary_written: u64,
    summary_keys: std::collections::HashSet<String>,
}

impl Budget {
    fn new() -> Self {
        Budget {
            started: Instant::now(),
            rows: 0,
            summary_row: None,
            summary_count: 0,
            summary_written: 0,
            summary_keys: Default::default(),
        }
    }

    fn summary_detail(&self) -> String {
        let addresses = self.summary_keys.len();
        let more = if addresses >= SUMMARY_KEYS {
            format!("at least {addresses}")
        } else {
            addresses.to_string()
        };
        format!(
            "{} more in this minute from {more} addresses (past {ROWS_PER_MINUTE} rows a minute, \
             only counted)",
            self.summary_count
        )
    }
}

/// What `record` does with one event, decided under the lock.
enum Plan {
    /// Counted in an open window (or summary): nothing to write.
    Counted,
    /// A new row for this key; the window it replaced, if its count isn't written yet.
    Row(Option<Window>),
    /// The minute's first summary row of this kind.
    Summary(String),
}

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
    budgets: Mutex<HashMap<String, Budget>>,
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
        let mut old_summary: Option<(i64, String)> = None;
        let plan = {
            let mut windows = self.windows.lock().unwrap_or_else(|e| e.into_inner());
            let mut budgets = self.budgets.lock().unwrap_or_else(|e| e.into_inner());
            if let Some(window) = windows.get_mut(&map_key)
                && window.started.elapsed() < WINDOW
            {
                window.count += 1;
                Plan::Counted
            } else {
                let budget = budgets
                    .entry(event_type.to_string())
                    .or_insert_with(Budget::new);
                if budget.started.elapsed() >= WINDOW {
                    // A new minute; the old summary's count is written below if the flush
                    // hasn't yet.
                    let old = std::mem::replace(budget, Budget::new());
                    if let Some(row_id) = old.summary_row
                        && old.summary_count != old.summary_written
                    {
                        old_summary = Some((row_id, old.summary_detail()));
                    }
                }
                if budget.rows < ROWS_PER_MINUTE {
                    budget.rows += 1;
                    if windows.len() >= MAX_KEYS && !windows.contains_key(&map_key) {
                        crate::limits::evict_oldest(&mut windows, |window| window.started);
                    }
                    Plan::Row(windows.insert(
                        map_key.clone(),
                        Window {
                            started: Instant::now(),
                            row_id: None,
                            detail: detail.to_string(),
                            count: 1,
                            written: 1,
                        },
                    ))
                } else {
                    budget.summary_count += 1;
                    if budget.summary_keys.len() < SUMMARY_KEYS {
                        budget.summary_keys.insert(key.to_string());
                    }
                    if budget.summary_count == 1 {
                        budget.summary_written = 1;
                        Plan::Summary(budget.summary_detail())
                    } else {
                        Plan::Counted
                    }
                }
            }
        };
        if let Some((row_id, detail)) = old_summary {
            update_raw(db, row_id, &detail).await;
        }
        match plan {
            Plan::Counted => {}
            Plan::Row(finished) => {
                // The minute before this one, if its count never reached the row.
                if let Some(old) = finished {
                    write_count(db, &old).await;
                }
                let row_id = insert(db, event_type, ip, detail).await;
                let mut windows = self.windows.lock().unwrap_or_else(|e| e.into_inner());
                if let Some(window) = windows.get_mut(&map_key) {
                    window.row_id = row_id;
                }
            }
            Plan::Summary(summary) => {
                let row_id = insert(db, event_type, None, &summary).await;
                let mut budgets = self.budgets.lock().unwrap_or_else(|e| e.into_inner());
                if let Some(budget) = budgets.get_mut(event_type) {
                    budget.summary_row = row_id;
                }
            }
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
        let summaries: Vec<(i64, String)> = {
            let mut budgets = self.budgets.lock().unwrap_or_else(|e| e.into_inner());
            let mut due = Vec::new();
            budgets.retain(|_, budget| {
                if let Some(row_id) = budget.summary_row
                    && budget.summary_count != budget.summary_written
                {
                    due.push((row_id, budget.summary_detail()));
                    budget.summary_written = budget.summary_count;
                }
                budget.started.elapsed() < WINDOW
                    || budget.summary_row.is_none() && budget.summary_count > 0
            });
            due
        };
        for (row_id, detail) in summaries {
            update_raw(db, row_id, &detail).await;
        }
    }
}

async fn insert(db: &SqlitePool, event_type: &str, ip: Option<&str>, detail: &str) -> Option<i64> {
    sqlx::query_scalar(
        "INSERT INTO security_events (event_type, username, ip_address, detail) \
         VALUES (?, NULL, ?, ?) RETURNING id",
    )
    .bind(event_type)
    .bind(ip)
    .bind(detail)
    .fetch_one(db)
    .await
    .ok()
}

async fn update_raw(db: &SqlitePool, row_id: i64, detail: &str) {
    sqlx::query("UPDATE security_events SET detail = ? WHERE id = ?")
        .bind(detail)
        .bind(row_id)
        .execute(db)
        .await
        .ok();
}

/// `text` cut to at most `max` characters - what a log row keeps of a path or a name.
pub fn clip(text: &str, max: usize) -> &str {
    match text.char_indices().nth(max) {
        Some((index, _)) => &text[..index],
        None => text,
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
