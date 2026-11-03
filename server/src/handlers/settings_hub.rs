//! The Settings tab (`/settings`, design 31 §3.6): a grouped list of navigation rows - Time Rules,
//! Web Filter, Blocked Activity, Wallpapers, Phone Setup, Backups, Software Updates, Account &
//! Parents, Security Log, About - and Sign Out.
//!
//! Every value on a row comes from the database, the backup directory or the compiled-in version,
//! never from the network (M3): the hub must open at once on a Pi without internet, so Software
//! Updates shows no value (no check result is cached anywhere) and About shows `APP_VERSION`.
//! `tests::settings_hub` checks that this file names no network client.
//!
//! Times ("Today 03:00") and "today" are in the Phone Setup time zone (`display_time`, design 31
//! §3.10), and say "UTC" when that zone is empty or unknown.

use crate::i18n::Words;
use askama::Template;
use axum::extract::State;
use axum::response::{Html, IntoResponse};
use chrono::{DateTime, TimeZone, Utc};
use sqlx::SqlitePool;
use std::path::Path;

use crate::AppState;
use crate::display_time::Zone;
use crate::handlers::backups::{BACKUP_DIR, is_safe_backup_filename};
use crate::i18n::{Lang, t, t_count, t_params};

#[derive(Template)]
#[template(path = "settings_hub.html")]
struct SettingsHubTemplate {
    lang: Lang,
    title: String,
    values: HubValues,
    /// The bell and the Notifications row's "3 unread" (design 35 §5.1).
    bell: crate::parent_events::Bell,
}
crate::i18n::words!(SettingsHubTemplate);

/// The value at the end of each hub row; `None` shows nothing (none yet, or a read failed - the
/// row still leads to its page).
#[derive(Debug, Default, PartialEq)]
pub struct HubValues {
    /// Everyone's rules: "2 rules".
    pub time_rules: Option<String>,
    /// Shared blocklists switched on: "3 lists", "No lists" (custom domains and a phone's own
    /// lists may still filter, so never "Off").
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

pub async fn settings_hub(
    State(state): State<AppState>,
    lang: Lang,
    axum::Extension(crate::security::CurrentAdmin(admin)): axum::Extension<
        crate::security::CurrentAdmin,
    >,
) -> impl IntoResponse {
    let zone = Zone::load(&state.db, lang).await;
    let values = hub_values(
        &state.db,
        &state.data_dir.join(BACKUP_DIR),
        &zone,
        Utc::now(),
    )
    .await;
    Html(
        SettingsHubTemplate {
            lang,
            title: t(lang, "nav.settings").to_string(),
            values,
            bell: crate::parent_events::Bell::load(&state.db, admin.id).await,
        }
        .render()
        .unwrap(),
    )
}

/// The hub's values: four counts from SQLite, one directory listing, the version.
pub async fn hub_values(
    db: &SqlitePool,
    backup_dir: &Path,
    zone: &Zone,
    now: DateTime<Utc>,
) -> HubValues {
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
    let since = utc_start_of_day(zone.local(now));
    let blocked = count(
        db,
        "SELECT COUNT(*) FROM device_dns_events WHERE received_at >= ?",
        Some(&since),
    )
    .await;
    let wallpapers = count(db, "SELECT COUNT(*) FROM wallpapers", None).await;
    let lang = zone.lang();
    HubValues {
        time_rules: rules.map(|n| match n {
            0 => t(lang, "settings.hub.no_rules").to_string(),
            n => t_count(lang, "settings.hub.rules", n, &[]),
        }),
        web_filter: lists.map(|n| match n {
            0 => t(lang, "settings.hub.no_lists").to_string(),
            n => t_count(lang, "settings.hub.lists", n, &[]),
        }),
        blocked_today: blocked.map(|n| blocked_today(lang, n)),
        wallpapers: wallpapers.map(|n| n.to_string()),
        backups: newest_backup(backup_dir)
            .await
            .map(|t| backup_label(zone, t, now)),
        about: t_params(
            lang,
            "settings.hub.server_version",
            &[("version", crate::APP_VERSION.trim_start_matches('v'))],
        ),
    }
}

/// "None today", "12 today": the Blocked Activity row's value, and each phone's on that page.
pub(crate) fn blocked_today(lang: Lang, n: i64) -> String {
    match n {
        0 => t(lang, "settings.hub.none_today").to_string(),
        n => t_params(lang, "settings.hub.n_today", &[("n", &n.to_string())]),
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

/// Midnight of `now`'s day in its own zone, as the UTC text `received_at` is stored in
/// (`datetime('now')`).
pub(crate) fn utc_start_of_day<Tz: TimeZone>(now: DateTime<Tz>) -> String {
    let zone = now.timezone();
    let midnight = now
        .date_naive()
        .and_hms_opt(0, 0, 0)
        .and_then(|m| m.and_local_timezone(zone).earliest())
        // No midnight that day (a DST gap at 00:00): the last 24 hours.
        .unwrap_or(now - chrono::Duration::hours(24));
    midnight
        .with_timezone(&Utc)
        .format("%Y-%m-%d %H:%M:%S")
        .to_string()
}

/// The modification time of the newest backup zip.
async fn newest_backup(dir: &Path) -> Option<DateTime<Utc>> {
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
    newest.map(DateTime::<Utc>::from)
}

/// The Backups row's value: "Today 03:00", "Yesterday 03:00", "12 Oct" (this year), "12 Oct 2025" (earlier), in
/// `zone`'s language; a time ahead of `now` (a clock that moved back) by its date. The clock time says "UTC" in the
/// fallback zone.
pub fn backup_label(zone: &Zone, t: DateTime<Utc>, now: DateTime<Utc>) -> String {
    let (day, today) = (zone.local(t).date_naive(), zone.today(now));
    let clock = || zone.clock(t);
    match (today - day).num_days() {
        0 => t_params(
            zone.lang(),
            "settings.hub.backup_today",
            &[("clock", &clock())],
        ),
        1 => t_params(
            zone.lang(),
            "settings.hub.backup_yesterday",
            &[("clock", &clock())],
        ),
        _ => zone.date_of(day, today),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use chrono::NaiveDateTime;

    fn at(s: &str) -> NaiveDateTime {
        NaiveDateTime::parse_from_str(s, "%Y-%m-%d %H:%M").unwrap()
    }

    #[test]
    fn backup_times_are_phone_setups_or_utc_labelled() {
        let utc = |s: &str| at(s).and_utc();
        let now = utc("2026-10-13 12:00");
        // 01:00 UTC is 03:00 in Oslo.
        let oslo = Zone::from_setting("Europe/Oslo");
        assert_eq!(
            backup_label(&oslo, utc("2026-10-13 01:00"), now),
            "Today 03:00"
        );
        // 23:30 UTC the day before is already today in Oslo.
        assert_eq!(
            backup_label(&oslo, utc("2026-10-12 23:30"), now),
            "Today 01:30"
        );
        let fallback = Zone::from_setting("");
        assert_eq!(
            backup_label(&fallback, utc("2026-10-12 23:30"), now),
            "Yesterday 23:30 UTC"
        );
        assert_eq!(
            backup_label(&fallback, utc("2026-10-01 01:00"), now),
            "1 Oct"
        );
    }

    #[test]
    fn backup_times_read_as_days_in_both_languages() {
        let utc = |s: &str| at(s).and_utc();
        let now = utc("2026-10-13 14:05");
        let oslo = Zone::from_setting("Europe/Oslo");
        let nb = Zone::from_setting("Europe/Oslo").in_lang(Lang::Nb);
        for (t, en, no) in [
            ("2026-10-13 01:00", "Today 03:00", "I dag 03:00"),
            ("2026-10-12 21:59", "Yesterday 23:59", "I går 23:59"),
            ("2026-10-01 01:00", "1 Oct", "1. okt."),
            ("2025-12-31 01:00", "31 Dec 2025", "31. des. 2025"),
            // A clock that moved back: by its date.
            ("2026-10-14 01:00", "14 Oct", "14. okt."),
        ] {
            assert_eq!(backup_label(&oslo, utc(t), now), en, "{t}");
            assert_eq!(backup_label(&nb, utc(t), now), no, "{t}");
        }
    }

    #[test]
    fn counts_read_as_short_values() {
        assert_eq!(blocked_today(Lang::En, 0), "None today");
        assert_eq!(blocked_today(Lang::En, 12), "12 today");
        assert_eq!(blocked_today(Lang::Nb, 12), "12 i dag");
        assert_eq!(t_count(Lang::En, "settings.hub.rules", 1, &[]), "1 rule");
        assert_eq!(t_count(Lang::Nb, "settings.hub.lists", 3, &[]), "3 lister");
    }

    fn zoned(offset_hours: i32, local: &str) -> DateTime<chrono::FixedOffset> {
        chrono::FixedOffset::east_opt(offset_hours * 3600)
            .unwrap()
            .from_local_datetime(&at(local))
            .unwrap()
    }

    #[test]
    fn today_starts_at_the_zones_midnight_in_stored_utc() {
        // Oslo in summer: midnight local is 22:00 UTC the day before.
        assert_eq!(
            utc_start_of_day(zoned(2, "2026-10-13 10:00")),
            "2026-10-12 22:00:00"
        );
        // New York: midnight local is 05:00 UTC the same day.
        assert_eq!(
            utc_start_of_day(zoned(-5, "2026-10-13 10:00")),
            "2026-10-13 05:00:00"
        );
        assert_eq!(
            utc_start_of_day(zoned(0, "2026-10-13 00:00")),
            "2026-10-13 00:00:00"
        );
    }

    /// A zone whose clocks jump from 00:00 to 01:00 on 2026-03-29 (UTC+1 before, UTC+2 after):
    /// that day has no midnight.
    #[derive(Clone, Copy, Debug)]
    struct MidnightGap;

    impl MidnightGap {
        fn switch() -> NaiveDateTime {
            at("2026-03-28 23:00") // 00:00 local, in UTC
        }
        fn offset_at_utc(utc: &NaiveDateTime) -> chrono::FixedOffset {
            let hours = if *utc < Self::switch() { 1 } else { 2 };
            chrono::FixedOffset::east_opt(hours * 3600).unwrap()
        }
    }

    impl TimeZone for MidnightGap {
        type Offset = chrono::FixedOffset;
        fn from_offset(_: &chrono::FixedOffset) -> Self {
            MidnightGap
        }
        fn offset_from_local_date(
            &self,
            local: &chrono::NaiveDate,
        ) -> chrono::LocalResult<chrono::FixedOffset> {
            self.offset_from_local_datetime(&local.and_hms_opt(12, 0, 0).unwrap())
        }
        fn offset_from_local_datetime(
            &self,
            local: &NaiveDateTime,
        ) -> chrono::LocalResult<chrono::FixedOffset> {
            let gap_start = at("2026-03-29 00:00");
            let gap_end = at("2026-03-29 01:00");
            if *local >= gap_start && *local < gap_end {
                chrono::LocalResult::None
            } else if *local < gap_start {
                chrono::LocalResult::Single(chrono::FixedOffset::east_opt(3600).unwrap())
            } else {
                chrono::LocalResult::Single(chrono::FixedOffset::east_opt(7200).unwrap())
            }
        }
        fn offset_from_utc_date(&self, utc: &chrono::NaiveDate) -> chrono::FixedOffset {
            Self::offset_at_utc(&utc.and_hms_opt(0, 0, 0).unwrap())
        }
        fn offset_from_utc_datetime(&self, utc: &NaiveDateTime) -> chrono::FixedOffset {
            Self::offset_at_utc(utc)
        }
    }

    #[test]
    fn a_day_without_midnight_counts_the_last_24_hours() {
        // 10:00 local (UTC+2) on the gap day = 08:00 UTC; 24 h before that.
        let now = MidnightGap
            .from_local_datetime(&at("2026-03-29 10:00"))
            .unwrap();
        assert_eq!(utc_start_of_day(now), "2026-03-28 08:00:00");
        // The next day has its midnight again: 00:00 local = 22:00 UTC.
        let next = MidnightGap
            .from_local_datetime(&at("2026-03-30 10:00"))
            .unwrap();
        assert_eq!(utc_start_of_day(next), "2026-03-29 22:00:00");
    }
}
