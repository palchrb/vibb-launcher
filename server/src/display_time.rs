//! Stored times as the parent reads them (design 31 §3.10, M4): local time in the Phone Setup
//! time zone (`provisioning_settings.time_zone`, the zone the setup QR gives the phones), relative
//! to the server's clock.
//!
//! - **The zone.** `chrono-tz` compiles the IANA data in, so the distroless image needs no
//!   `zoneinfo`. An empty or unknown zone falls back to UTC, and every clock time then says "UTC":
//!   `provisioning::valid_time_zone` checks only the characters, so "Europe/Olso" is stored and
//!   lands here.
//! - **Relative times** come from the server's clock only, never the phone's, so a phone with a
//!   wrong clock can't make itself "just now". The steps: under a minute "just now", under an hour
//!   "N min ago", the same local day "N h ago", the day before "yesterday HH:MM", older "12 Oct"
//!   ("12 Oct 2025" in another year). A time up to 5 minutes ahead (a clock that moved back) is
//!   "just now"; further ahead "today 16:00", or its date on another day.
//! - **The exact time** ("2026-10-13 10:59:13 CEST") is for a `title=` and the ⓘ panels, never
//!   the visible value.
//!
//! `parent_pin::when_text` ("2026-10-09 14:02 UTC (3 h ago)") is the older form; its callers
//! (the Status card's notices, the PIN events, the relock notes) move here as the steps that
//! rebuild their pages switch them to typed values (design 31 §8, steps 4 and 10; step 3 moved
//! the boot notes).

use chrono::{DateTime, Datelike, NaiveDate, NaiveDateTime, Utc};
use chrono_tz::Tz;
use sqlx::SqlitePool;

/// A time "ahead" by at most this much still reads as "just now" (the server's clock moved back
/// a little since the time was stored).
const FUTURE_SLACK_SECS: i64 = 5 * 60;

/// The zone times are shown in.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Zone {
    tz: Tz,
    /// The Phone Setup zone was empty or unknown: UTC, and every clock time says so.
    fallback: bool,
}

impl Zone {
    /// The zone named `name` (an IANA id, as Phone Setup stores it); UTC, labelled, when it is
    /// empty or unknown.
    pub fn from_setting(name: &str) -> Zone {
        match name.trim().parse::<Tz>() {
            Ok(tz) if !name.trim().is_empty() => Zone {
                tz,
                fallback: false,
            },
            _ => Zone::utc_fallback(),
        }
    }

    /// UTC, labelled "UTC".
    pub fn utc_fallback() -> Zone {
        Zone {
            tz: Tz::UTC,
            fallback: true,
        }
    }

    /// The Phone Setup zone; UTC, labelled, when it can't be read.
    pub async fn load(db: &SqlitePool) -> Zone {
        match sqlx::query_scalar::<_, String>(
            "SELECT time_zone FROM provisioning_settings WHERE id = 1",
        )
        .fetch_optional(db)
        .await
        {
            Ok(Some(name)) => Zone::from_setting(&name),
            Ok(None) => Zone::utc_fallback(),
            Err(err) => {
                tracing::error!(%err, "can't read the Phone Setup time zone; showing UTC");
                Zone::utc_fallback()
            }
        }
    }

    /// Times are UTC because the Phone Setup zone was empty or unknown.
    pub fn is_fallback(&self) -> bool {
        self.fallback
    }

    /// The Phone Setup zone's IANA name; `None` in the fallback (the phones' zone is unknown).
    pub fn name(&self) -> Option<&'static str> {
        (!self.fallback).then(|| self.tz.name())
    }

    /// The local date at `now`.
    pub fn today(&self, now: DateTime<Utc>) -> NaiveDate {
        self.local(now).date_naive()
    }

    pub fn local(&self, t: DateTime<Utc>) -> DateTime<Tz> {
        t.with_timezone(&self.tz)
    }

    /// " UTC" after a clock time in the fallback zone, else nothing.
    fn suffix(&self) -> &'static str {
        if self.fallback { " UTC" } else { "" }
    }

    /// "14:30" (local), "14:30 UTC" in the fallback zone.
    pub fn clock(&self, t: DateTime<Utc>) -> String {
        format!("{}{}", self.local(t).format("%H:%M"), self.suffix())
    }

    /// A local wall-clock time ("14:30", "14:30 UTC"), for a time that is already local (a rule's
    /// window end).
    pub fn clock_of(&self, t: NaiveDateTime) -> String {
        format!("{}{}", t.format("%H:%M"), self.suffix())
    }

    /// "2026-10-13 10:59:13 CEST": the exact time for a `title=` or an ⓘ panel.
    pub fn exact(&self, t: DateTime<Utc>) -> String {
        self.local(t).format("%Y-%m-%d %H:%M:%S %Z").to_string()
    }

    /// "just now", "3 min ago", "2 h ago", "yesterday 21:40", "12 Oct", "12 Oct 2025".
    pub fn relative(&self, t: DateTime<Utc>, now: DateTime<Utc>) -> String {
        let secs = (now - t).num_seconds();
        if secs < 0 {
            if -secs <= FUTURE_SLACK_SECS {
                return "just now".to_string();
            }
            // Only a server clock stepped back gets here: say the clock time, not "13 Oct".
            if self.local(t).date_naive() == self.today(now) {
                return format!("today {}", self.clock(t));
            }
            return self.date(t, now);
        }
        if secs < 60 {
            return "just now".to_string();
        }
        if secs < 60 * 60 {
            return format!("{} min ago", secs / 60);
        }
        let (day, today) = (self.local(t).date_naive(), self.today(now));
        if day == today {
            return format!("{} h ago", secs / 3600);
        }
        if today.pred_opt() == Some(day) {
            return format!("yesterday {}", self.clock(t));
        }
        self.date(t, now)
    }

    /// When something happened, as a value in a row: "10:59" today, "yesterday 21:40", else
    /// "12 Oct" ("12 Oct 2025" in another year). A time ahead of `now` on the same day is its
    /// clock time too.
    pub fn when(&self, t: DateTime<Utc>, now: DateTime<Utc>) -> String {
        let (day, today) = (self.local(t).date_naive(), self.today(now));
        if day == today {
            self.clock(t)
        } else if today.pred_opt() == Some(day) {
            format!("yesterday {}", self.clock(t))
        } else {
            self.date(t, now)
        }
    }

    /// When something ends, for "until …": "14:05" today, "00:05 tomorrow" (the lock badge's
    /// wording, §3.10), "Mon 14:30" within the week, else "20 Oct 14:30" - always with its clock
    /// time, the one thing an end is for (qa-31-s3-code M2). A time already past reads as [when].
    pub fn until(&self, t: DateTime<Utc>, now: DateTime<Utc>) -> String {
        if t <= now {
            return self.when(t, now);
        }
        let (day, today) = (self.local(t).date_naive(), self.today(now));
        let days = (day - today).num_days();
        match days {
            0 => self.clock(t),
            1 => format!("{} tomorrow", self.clock(t)),
            2..=6 => format!("{} {}", self.local(t).format("%a"), self.clock(t)),
            _ => format!("{} {}", self.date(t, now), self.clock(t)),
        }
    }

    /// "12 Oct", or "12 Oct 2025" in another year than `now`'s.
    fn date(&self, t: DateTime<Utc>, now: DateTime<Utc>) -> String {
        let local = self.local(t);
        if local.year() == self.local(now).year() {
            local.format("%-d %b").to_string()
        } else {
            local.format("%-d %b %Y").to_string()
        }
    }
}

/// A stored time: SQLite's `datetime('now')` text (UTC, no zone, optional fraction), or RFC 3339
/// (the locations' `captured_at`). `None` for anything else.
pub fn parse_db_utc(text: &str) -> Option<DateTime<Utc>> {
    let text = text.trim();
    if let Ok(t) = NaiveDateTime::parse_from_str(text, "%Y-%m-%d %H:%M:%S%.f") {
        return Some(t.and_utc());
    }
    DateTime::parse_from_rfc3339(text)
        .ok()
        .map(|t| t.with_timezone(&Utc))
}

#[cfg(test)]
mod tests {
    use super::*;
    use chrono::TimeZone;

    fn utc(s: &str) -> DateTime<Utc> {
        parse_db_utc(s).unwrap()
    }

    fn oslo() -> Zone {
        Zone::from_setting("Europe/Oslo")
    }

    #[test]
    fn stored_times_parse_with_and_without_fractions() {
        assert_eq!(
            parse_db_utc("2026-10-13 08:12:04"),
            Some(Utc.with_ymd_and_hms(2026, 10, 13, 8, 12, 4).unwrap())
        );
        assert_eq!(
            parse_db_utc("2026-10-13 08:12:04.250").map(|t| t.timestamp_millis() % 1000),
            Some(250)
        );
        assert_eq!(
            parse_db_utc("2026-10-13T08:12:04.000Z"),
            Some(Utc.with_ymd_and_hms(2026, 10, 13, 8, 12, 4).unwrap())
        );
        assert_eq!(parse_db_utc(""), None);
        assert_eq!(parse_db_utc("yesterday"), None);
    }

    #[test]
    fn the_zone_is_phone_setups_or_utc_labelled() {
        let zone = oslo();
        assert!(!zone.is_fallback());
        assert_eq!(zone.name(), Some("Europe/Oslo"));
        assert_eq!(Zone::from_setting("").name(), None);
        let t = utc("2026-10-13 08:12:04");
        assert_eq!(zone.clock(t), "10:12");
        assert_eq!(zone.exact(t), "2026-10-13 10:12:04 CEST");

        for name in ["", "   ", "Europe/Olso", "europe/oslo", "Mars/Olympus"] {
            let zone = Zone::from_setting(name);
            assert!(zone.is_fallback(), "{name:?}");
            assert_eq!(zone.clock(t), "08:12 UTC", "{name:?}");
            assert_eq!(zone.exact(t), "2026-10-13 08:12:04 UTC", "{name:?}");
        }
        // Yesterday says UTC too; "N min ago" has no clock time to label.
        let fallback = Zone::utc_fallback();
        assert_eq!(
            fallback.relative(utc("2026-10-12 21:40:00"), utc("2026-10-13 08:00:00")),
            "yesterday 21:40 UTC"
        );
        assert_eq!(
            fallback.relative(utc("2026-10-13 07:57:00"), utc("2026-10-13 08:00:00")),
            "3 min ago"
        );
    }

    #[test]
    fn relative_times_step_from_minutes_to_dates() {
        let zone = oslo();
        let now = utc("2026-10-13 12:00:00"); // 14:00 in Oslo
        let ago = |s: &str| zone.relative(utc(s), now);
        assert_eq!(ago("2026-10-13 12:00:00"), "just now");
        assert_eq!(ago("2026-10-13 11:59:01"), "just now");
        assert_eq!(ago("2026-10-13 11:59:00"), "1 min ago");
        assert_eq!(ago("2026-10-13 11:57:00"), "3 min ago");
        assert_eq!(ago("2026-10-13 11:00:01"), "59 min ago");
        assert_eq!(ago("2026-10-13 11:00:00"), "1 h ago");
        assert_eq!(ago("2026-10-13 10:00:00"), "2 h ago");
        // Local midnight is 22:00 UTC the day before: still today in Oslo.
        assert_eq!(ago("2026-10-12 22:00:00"), "14 h ago");
        assert_eq!(ago("2026-10-12 21:59:00"), "yesterday 23:59");
        assert_eq!(ago("2026-10-12 19:40:00"), "yesterday 21:40");
        assert_eq!(ago("2026-10-11 21:59:00"), "11 Oct");
        assert_eq!(ago("2025-12-31 12:00:00"), "31 Dec 2025");
        // An hour that crosses midnight is still minutes.
        let after_midnight = utc("2026-10-12 22:10:00"); // 00:10 on the 13th
        assert_eq!(
            zone.relative(utc("2026-10-12 21:50:00"), after_midnight),
            "20 min ago"
        );
    }

    #[test]
    fn when_is_a_clock_time_today_and_a_date_before() {
        let zone = oslo();
        let now = utc("2026-10-13 12:00:00"); // 14:00 in Oslo
        let when = |s: &str| zone.when(utc(s), now);
        assert_eq!(when("2026-10-13 06:12:00"), "08:12");
        assert_eq!(
            when("2026-10-12 22:00:00"),
            "00:00",
            "local midnight is today"
        );
        assert_eq!(when("2026-10-12 21:59:00"), "yesterday 23:59");
        assert_eq!(when("2026-10-11 21:59:00"), "11 Oct");
        assert_eq!(when("2025-12-31 12:00:00"), "31 Dec 2025");
        assert_eq!(when("2026-10-13 14:00:00"), "16:00", "ahead, same day");
        assert_eq!(
            Zone::utc_fallback().when(utc("2026-10-13 06:12:00"), now),
            "06:12 UTC"
        );
    }

    #[test]
    fn until_keeps_the_clock_time_past_midnight() {
        let zone = oslo();
        let now = utc("2026-10-13 21:50:00"); // 23:50 in Oslo, a Tuesday
        let until = |s: &str| zone.until(utc(s), now);
        assert_eq!(until("2026-10-13 21:55:00"), "23:55");
        // A code made at 23:50 (15 min), install mode, a typed-code pause.
        assert_eq!(until("2026-10-13 22:05:00"), "00:05 tomorrow");
        assert_eq!(until("2026-10-14 21:59:00"), "23:59 tomorrow");
        assert_eq!(until("2026-10-15 12:30:00"), "Thu 14:30");
        assert_eq!(until("2026-10-19 12:30:00"), "Mon 14:30");
        assert_eq!(until("2026-10-20 12:30:00"), "20 Oct 14:30");
        // Already past: as `when`.
        assert_eq!(until("2026-10-13 21:00:00"), "23:00");
        assert_eq!(
            Zone::utc_fallback().until(utc("2026-10-13 22:05:00"), now),
            "22:05 UTC"
        );
    }

    #[test]
    fn a_time_slightly_ahead_is_just_now() {
        let zone = oslo();
        let now = utc("2026-10-13 12:00:00");
        assert_eq!(zone.relative(utc("2026-10-13 12:02:00"), now), "just now");
        assert_eq!(zone.relative(utc("2026-10-13 12:05:00"), now), "just now");
        assert_eq!(
            zone.relative(utc("2026-10-13 14:00:00"), now),
            "today 16:00"
        );
        assert_eq!(zone.relative(utc("2026-10-15 12:00:00"), now), "15 Oct");
    }

    #[test]
    fn oslo_dst_starts_and_ends() {
        let zone = oslo();
        // Spring: 2026-03-29 02:00 CET becomes 03:00 CEST (01:00 UTC).
        let before = utc("2026-03-29 00:30:00");
        let after = utc("2026-03-29 01:30:00");
        assert_eq!(zone.clock(before), "01:30");
        assert_eq!(zone.clock(after), "03:30");
        assert_eq!(zone.exact(before), "2026-03-29 01:30:00 CET");
        assert_eq!(zone.exact(after), "2026-03-29 03:30:00 CEST");
        assert_eq!(zone.relative(before, after), "1 h ago");
        assert_eq!(
            zone.relative(utc("2026-03-28 22:00:00"), utc("2026-03-29 08:00:00")),
            "yesterday 23:00"
        );
        // Autumn: 2026-10-25 03:00 CEST becomes 02:00 CET (01:00 UTC): 02:30 twice.
        let first = utc("2026-10-25 00:30:00");
        let second = utc("2026-10-25 01:30:00");
        assert_eq!(zone.clock(first), "02:30");
        assert_eq!(zone.clock(second), "02:30");
        assert_eq!(zone.exact(first), "2026-10-25 02:30:00 CEST");
        assert_eq!(zone.exact(second), "2026-10-25 02:30:00 CET");
        assert_eq!(zone.relative(first, second), "1 h ago");
        assert_eq!(
            zone.today(utc("2026-10-24 22:30:00")),
            NaiveDate::from_ymd_opt(2026, 10, 25).unwrap()
        );
    }
}
