//! The Settings tab (`/settings`, design 31 §3.6): a grouped list of navigation rows - Time Rules,
//! Web Filter, Blocked Activity, Wallpapers, Phone Setup, Backups, Software Updates, Account &
//! Password, Security Log, About - and Sign Out.
//!
//! Every value on a row comes from the database, the backup directory or the compiled-in version,
//! never from the network (M3): the hub must open at once on a Pi without internet, so Software
//! Updates shows no value (no check result is cached anywhere) and About shows `APP_VERSION`.
//! `tests::settings_hub` checks that this file names no network client.
//!
//! Times ("Today 03:00") are the server's local time, like the Backups page next to it, until
//! design 31 step 2's `display_time` (the Phone Setup time zone) replaces it.

use askama::Template;
use axum::extract::State;
use axum::response::{Html, IntoResponse};
use chrono::{DateTime, Local, NaiveDateTime, Utc};
use sqlx::SqlitePool;
use std::path::Path;

use crate::AppState;
use crate::handlers::backups::{BACKUP_DIR, is_safe_backup_filename};

#[derive(Template)]
#[template(path = "settings_hub.html")]
struct SettingsHubTemplate {
    title: String,
    values: HubValues,
}

/// The value at the end of each hub row; `None` shows nothing (none yet, or a read failed - the
/// row still leads to its page).
#[derive(Debug, Default, PartialEq)]
pub struct HubValues {
    /// Everyone's rules: "2 rules".
    pub time_rules: Option<String>,
    /// Shared blocklists switched on: "3 lists", "Off".
    pub web_filter: Option<String>,
    /// Blocked domains the phones reported today: "12 today".
    pub blocked_today: Option<String>,
    /// Wallpapers on the server, built-ins included: "5".
    pub wallpapers: Option<String>,
    /// The newest backup file: "Today 03:00".
    pub backups: Option<String>,
    /// "Server 0.24.0".
    pub about: String,
}

pub async fn settings_hub(State(state): State<AppState>) -> impl IntoResponse {
    let values = hub_values(&state.db, Path::new(BACKUP_DIR), Local::now()).await;
    Html(
        SettingsHubTemplate {
            title: "Settings".to_string(),
            values,
        }
        .render()
        .unwrap(),
    )
}

/// The hub's values: four counts from SQLite, one directory listing, the version.
pub async fn hub_values(db: &SqlitePool, backup_dir: &Path, now: DateTime<Local>) -> HubValues {
    let rules = count(
        db,
        "SELECT COUNT(*) FROM time_rules WHERE device_id IS NULL",
        None,
    )
    .await;
    let lists = count(
        db,
        "SELECT COUNT(*) FROM dns_blocklists WHERE enabled = 1",
        None,
    )
    .await;
    let since = utc_start_of_local_day(now);
    let blocked = count(
        db,
        "SELECT COUNT(*) FROM device_dns_events WHERE received_at >= ?",
        Some(&since),
    )
    .await;
    let wallpapers = count(db, "SELECT COUNT(*) FROM wallpapers", None).await;
    HubValues {
        time_rules: rules.map(|n| plural(n, "No rules", "rule", "rules")),
        web_filter: lists.map(|n| plural(n, "Off", "list", "lists")),
        blocked_today: blocked.map(|n| {
            if n == 0 {
                "None today".to_string()
            } else {
                format!("{n} today")
            }
        }),
        wallpapers: wallpapers.map(|n| n.to_string()),
        backups: newest_backup(backup_dir)
            .await
            .map(|t| day_time_label(t, now.naive_local())),
        about: format!("Server {}", crate::APP_VERSION.trim_start_matches('v')),
    }
}

async fn count(db: &SqlitePool, sql: &str, bind: Option<&str>) -> Option<i64> {
    let mut query = sqlx::query_scalar::<_, i64>(sql);
    if let Some(value) = bind {
        query = query.bind(value);
    }
    match query.fetch_one(db).await {
        Ok(n) => Some(n),
        Err(e) => {
            tracing::error!("settings hub: {sql}: {e}");
            None
        }
    }
}

/// "No rules", "1 rule", "3 rules".
fn plural(n: i64, zero: &str, one: &str, many: &str) -> String {
    match n {
        0 => zero.to_string(),
        1 => format!("1 {one}"),
        n => format!("{n} {many}"),
    }
}

/// Local midnight today as the UTC text `received_at` is stored in (`datetime('now')`).
fn utc_start_of_local_day(now: DateTime<Local>) -> String {
    let midnight = now
        .date_naive()
        .and_hms_opt(0, 0, 0)
        .and_then(|m| m.and_local_timezone(Local).earliest())
        // No local midnight (a DST gap at 00:00): the last 24 hours.
        .unwrap_or(now - chrono::Duration::hours(24));
    midnight
        .with_timezone(&Utc)
        .format("%Y-%m-%d %H:%M:%S")
        .to_string()
}

/// The modification time of the newest backup zip, in local time.
async fn newest_backup(dir: &Path) -> Option<NaiveDateTime> {
    let mut entries = tokio::fs::read_dir(dir).await.ok()?;
    let mut newest: Option<std::time::SystemTime> = None;
    while let Ok(Some(entry)) = entries.next_entry().await {
        if !is_safe_backup_filename(&entry.file_name().to_string_lossy()) {
            continue;
        }
        let Ok(modified) = entry.metadata().await.and_then(|m| m.modified()) else {
            continue;
        };
        if newest.is_none_or(|n| modified > n) {
            newest = Some(modified);
        }
    }
    newest.map(|t| DateTime::<Local>::from(t).naive_local())
}

/// "Today 03:00", "Yesterday 03:00", "12 Oct" (this year), "12 Oct 2025" (earlier); a time
/// ahead of `now` (a clock that moved back) by its date.
pub fn day_time_label(t: NaiveDateTime, now: NaiveDateTime) -> String {
    let days = (now.date() - t.date()).num_days();
    match days {
        0 => format!("Today {}", t.format("%H:%M")),
        1 => format!("Yesterday {}", t.format("%H:%M")),
        _ if t.format("%Y").to_string() == now.format("%Y").to_string() => {
            t.format("%-d %b").to_string()
        }
        _ => t.format("%-d %b %Y").to_string(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn at(s: &str) -> NaiveDateTime {
        NaiveDateTime::parse_from_str(s, "%Y-%m-%d %H:%M").unwrap()
    }

    #[test]
    fn backup_times_read_as_days() {
        let now = at("2026-10-13 14:05");
        assert_eq!(day_time_label(at("2026-10-13 03:00"), now), "Today 03:00");
        assert_eq!(
            day_time_label(at("2026-10-12 23:59"), now),
            "Yesterday 23:59"
        );
        assert_eq!(day_time_label(at("2026-10-01 03:00"), now), "1 Oct");
        assert_eq!(day_time_label(at("2025-12-31 03:00"), now), "31 Dec 2025");
        assert_eq!(day_time_label(at("2026-10-14 03:00"), now), "14 Oct");
    }

    #[test]
    fn counts_read_as_short_values() {
        assert_eq!(plural(0, "No rules", "rule", "rules"), "No rules");
        assert_eq!(plural(1, "No rules", "rule", "rules"), "1 rule");
        assert_eq!(plural(3, "Off", "list", "lists"), "3 lists");
    }

    #[test]
    fn today_starts_at_local_midnight_in_stored_utc() {
        let now = Local::now();
        let start = utc_start_of_local_day(now);
        let parsed = NaiveDateTime::parse_from_str(&start, "%Y-%m-%d %H:%M:%S").unwrap();
        let age = now.with_timezone(&Utc).naive_utc() - parsed;
        assert!(age >= chrono::Duration::zero() && age <= chrono::Duration::hours(25));
    }
}
