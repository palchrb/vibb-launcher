//! Stored times as the parent reads them (design 31 §3.10, M4): local time in the Phone Setup
//! time zone (`provisioning_settings.time_zone`, the zone the setup QR gives the phones), relative
//! to the server's clock, worded in the reader's language (design 70 §4, slice 2).
//!
//! - **The zone.** `chrono-tz` compiles the IANA data in, so the distroless image needs no
//!   `zoneinfo`. An empty or unknown zone falls back to UTC, and every clock time then says "UTC":
//!   `provisioning::valid_time_zone` checks only the characters, so "Europe/Olso" is stored and
//!   lands here.
//! - **The language.** A [Zone] carries the reader's [Lang] (`Zone::load(db, lang)`,
//!   [Zone::in_lang]), and every word - "just now", "yesterday", a month's or a weekday's name -
//!   comes from the catalog's `time.*` keys, never from chrono's locales: nb "nå nettopp", "for 3
//!   min siden", "i går 21:40", "27. okt.", "man. 14:30"; en as it always was. The clock is 24 h
//!   in both (D3).
//! - **Relative times** come from the server's clock only, never the phone's, so a phone with a
//!   wrong clock can't make itself "just now". The steps: under a minute "just now", under an hour
//!   "N min ago", the same local day "N h ago", the day before "yesterday HH:MM", older "12 Oct"
//!   ("12 Oct 2025" in another year). A time up to 5 minutes ahead (a clock that moved back) is
//!   "just now"; further ahead "today 16:00", or its date on another day.
//! - **The exact time** ("2026-10-13 10:59:13 CEST") is for a `title=` and the ⓘ panels, never
//!   the visible value.
//!
//! Every page time goes through here since design 31 step 10 (the relock findings were the last;
//! `parent_pin::when_text` is gone). The security log keeps its UTC lines.

use chrono::{DateTime, Datelike, NaiveDate, NaiveDateTime, TimeZone, Utc};
use chrono_tz::Tz;
use sqlx::SqlitePool;

use crate::i18n::{Lang, t, t_params};

/// A time "ahead" by at most this much still reads as "just now" (the server's clock moved back
/// a little since the time was stored).
const FUTURE_SLACK_SECS: i64 = 5 * 60;

/// The zone times are shown in, and the language they are worded in.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Zone {
    tz: Tz,
    /// The Phone Setup zone was empty or unknown: UTC, and every clock time says so.
    fallback: bool,
    lang: Lang,
}

impl Zone {
    /// The zone named `name` (an IANA id, as Phone Setup stores it); UTC, labelled, when it is
    /// empty or unknown. In English: a page's zone comes from [Zone::load] with the reader's
    /// language; this one is for day arithmetic (a phone's own zone, an intake) and the tests.
    pub fn from_setting(name: &str) -> Zone {
        match name.trim().parse::<Tz>() {
            Ok(tz) if !name.trim().is_empty() => Zone {
                tz,
                fallback: false,
                lang: Lang::default(),
            },
            _ => Zone::utc_fallback(),
        }
    }

    /// UTC, labelled "UTC", in English (see [Zone::from_setting]).
    pub fn utc_fallback() -> Zone {
        Zone {
            tz: Tz::UTC,
            fallback: true,
            lang: Lang::default(),
        }
    }

    /// The Phone Setup zone, worded in `lang`; UTC, labelled, when it can't be read.
    pub async fn load(db: &SqlitePool, lang: Lang) -> Zone {
        let zone = match sqlx::query_scalar::<_, String>(
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
        };
        zone.in_lang(lang)
    }

    /// The same zone worded in `lang` (the push sender loads once and words per subscriber).
    pub fn in_lang(self, lang: Lang) -> Zone {
        Zone { lang, ..self }
    }

    /// The language the times are worded in.
    pub fn lang(&self) -> Lang {
        self.lang
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

    /// The local day of `t` is the local today.
    pub fn is_today(&self, t: DateTime<Utc>, now: DateTime<Utc>) -> bool {
        self.local(t).date_naive() == self.today(now)
    }

    /// When the local today began, in UTC (the earliest midnight on a DST day; a day whose
    /// midnight doesn't exist starts at the first hour that does).
    pub fn start_of_today(&self, now: DateTime<Utc>) -> DateTime<Utc> {
        let today = self.today(now);
        (0..24)
            .find_map(|hour| {
                self.tz
                    .from_local_datetime(&today.and_hms_opt(hour, 0, 0)?)
                    .earliest()
            })
            .map_or(now, |t| t.with_timezone(&Utc))
    }

    /// The zone itself (the weather's hours, design 75 D12).
    pub fn tz(&self) -> Tz {
        self.tz
    }

    pub fn local(&self, t: DateTime<Utc>) -> DateTime<Tz> {
        t.with_timezone(&self.tz)
    }

    /// The local wall time `t` is exactly one instant in this zone: not in a spring-forward gap
    /// (it never shows) nor in a fall-back hour (it shows twice).
    pub fn single_instant(&self, t: NaiveDateTime) -> bool {
        self.tz.from_local_datetime(&t).single().is_some()
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
                return self.word("time.just_now");
            }
            // Only a server clock stepped back gets here: say the clock time, not "13 Oct".
            if self.local(t).date_naive() == self.today(now) {
                return self.with_clock("time.today", t);
            }
            return self.date(t, now);
        }
        if secs < 60 {
            return self.word("time.just_now");
        }
        if secs < 60 * 60 {
            return t_params(
                self.lang,
                "time.min_ago",
                &[("n", &(secs / 60).to_string())],
            );
        }
        let (day, today) = (self.local(t).date_naive(), self.today(now));
        if day == today {
            return t_params(
                self.lang,
                "time.h_ago",
                &[("n", &(secs / 3600).to_string())],
            );
        }
        if today.pred_opt() == Some(day) {
            return self.with_clock("time.yesterday", t);
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
            self.with_clock("time.yesterday", t)
        } else {
            self.date(t, now)
        }
    }

    /// [when] that always carries the clock time: "10:59" today, "yesterday 21:40", else
    /// "12 Oct 08:12" ("12 Oct 2025 08:12") - for a notification's summary, which may arrive days
    /// late (design 35).
    pub fn at(&self, t: DateTime<Utc>, now: DateTime<Utc>) -> String {
        let (day, today) = (self.local(t).date_naive(), self.today(now));
        if day == today || today.pred_opt() == Some(day) {
            self.when(t, now)
        } else {
            self.date_clock(t, now)
        }
    }

    /// Since when something holds, for the phone's status lines ("last seen 13:05", "Home since
    /// 22:19", "Turned off 21:14"; the user's pick of 2026-11-01): its clock alone today - never
    /// a date for today -, "yesterday 21:40", the weekday within the week ("Sat 22:19"), else
    /// "24 Oct 22:19" ("24 Oct 2025 22:19"). A time ahead of `now` reads as [at].
    pub fn since(&self, t: DateTime<Utc>, now: DateTime<Utc>) -> String {
        let (day, today) = (self.local(t).date_naive(), self.today(now));
        match (today - day).num_days() {
            0 => self.clock(t),
            1 => self.with_clock("time.yesterday", t),
            2..=6 => t_params(
                self.lang,
                "time.weekday_clock",
                &[
                    ("weekday", &self.weekday_name(day)),
                    ("clock", &self.clock(t)),
                ],
            ),
            _ if t > now => self.at(t, now),
            _ => self.date_clock(t, now),
        }
    }

    /// [until] for a stored time (`datetime('now')` text, UTC); a text that doesn't parse is shown
    /// as stored, labelled "UTC".
    pub fn until_db(&self, stored: &str, now: DateTime<Utc>) -> String {
        parse_db_utc(stored)
            .map(|t| self.until(t, now))
            .unwrap_or_else(|| format!("{stored} UTC"))
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
            1 => self.with_clock("time.tomorrow", t),
            2..=6 => t_params(
                self.lang,
                "time.weekday_clock",
                &[
                    ("weekday", &self.weekday_name(day)),
                    ("clock", &self.clock(t)),
                ],
            ),
            _ => self.date_clock(t, now),
        }
    }

    /// "12 Oct", or "12 Oct 2025" in another year than `now`'s.
    fn date(&self, t: DateTime<Utc>, now: DateTime<Utc>) -> String {
        self.date_of(self.local(t).date_naive(), self.today(now))
    }

    /// "12 Oct 08:12" ("12 Oct 2025 08:12").
    fn date_clock(&self, t: DateTime<Utc>, now: DateTime<Utc>) -> String {
        t_params(
            self.lang,
            "time.date_clock",
            &[("date", &self.date(t, now)), ("clock", &self.clock(t))],
        )
    }

    /// A local date as a value: "12 Oct", or "12 Oct 2025" in another year than `today`'s (nb
    /// "12. okt.", "12. okt. 2025").
    pub fn date_of(&self, day: NaiveDate, today: NaiveDate) -> String {
        let number = day.day().to_string();
        let month = self.month_name(day);
        if day.year() == today.year() {
            t_params(
                self.lang,
                "time.date",
                &[("day", &number), ("month", &month)],
            )
        } else {
            t_params(
                self.lang,
                "time.date_year",
                &[
                    ("day", &number),
                    ("month", &month),
                    ("year", &day.year().to_string()),
                ],
            )
        }
    }

    /// The month's short name from the catalog ("Oct", nb "okt.").
    pub fn month_name(&self, day: NaiveDate) -> String {
        self.word(&format!("time.month.{}", day.month()))
    }

    /// The weekday's short name from the catalog ("Mon", nb "man.").
    pub fn weekday_name(&self, day: NaiveDate) -> String {
        self.word(&format!(
            "time.weekday.{}",
            day.weekday().number_from_monday()
        ))
    }

    fn word(&self, key: &str) -> String {
        t(self.lang, key).to_string()
    }

    /// `key` with its `{clock}` filled with `t`'s clock time.
    fn with_clock(&self, key: &str, t: DateTime<Utc>) -> String {
        t_params(self.lang, key, &[("clock", &self.clock(t))])
    }
}

/// A length of time in minutes as a value: "45 min", "2 h", "1 h 30 min" (nb "1 t 30 min") -
/// design 70 §6's `duration`, the one for every page (the lifts, Device › Time, the events).
pub fn duration(lang: Lang, minutes: i64) -> String {
    let (h, m) = (minutes / 60, minutes % 60);
    let (h, m) = (h.to_string(), m.to_string());
    match (minutes / 60, minutes % 60) {
        (0, _) => t_params(lang, "time.duration.min", &[("min", &m)]),
        (_, 0) => t_params(lang, "time.duration.h", &[("h", &h)]),
        _ => t_params(lang, "time.duration.h_min", &[("h", &h), ("min", &m)]),
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

    fn nb() -> Zone {
        oslo().in_lang(Lang::Nb)
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
        assert_eq!(
            fallback
                .in_lang(Lang::Nb)
                .relative(utc("2026-10-12 21:40:00"), utc("2026-10-13 08:00:00")),
            "i går 21:40 UTC"
        );
        // The language rides along; the zone stays.
        assert_eq!(zone.in_lang(Lang::Nb).lang(), Lang::Nb);
        assert_eq!(zone.in_lang(Lang::Nb).name(), Some("Europe/Oslo"));
        assert_eq!(Zone::from_setting("Europe/Oslo").lang(), Lang::En);
    }

    /// Every form in both languages (design 70 §4): the relative steps, a row's value, the clock
    /// that always comes along, and an end - with "yesterday"/"tomorrow" at the zone's midnight,
    /// not UTC's.
    #[test]
    fn every_time_form_in_both_languages() {
        let now = utc("2026-10-13 12:00:00"); // 14:00 in Oslo, a Tuesday
        let late = utc("2026-10-13 21:50:00"); // 23:50 in Oslo
        for (lang, rows) in [
            (
                Lang::En,
                vec![
                    ("relative", "2026-10-13 12:00:00", "just now"),
                    ("relative", "2026-10-13 11:59:01", "just now"),
                    ("relative", "2026-10-13 11:59:00", "1 min ago"),
                    ("relative", "2026-10-13 11:57:00", "3 min ago"),
                    ("relative", "2026-10-13 11:00:01", "59 min ago"),
                    ("relative", "2026-10-13 11:00:00", "1 h ago"),
                    ("relative", "2026-10-13 10:00:00", "2 h ago"),
                    // Local midnight is 22:00 UTC the day before: still today in Oslo.
                    ("relative", "2026-10-12 22:00:00", "14 h ago"),
                    ("relative", "2026-10-12 21:59:00", "yesterday 23:59"),
                    ("relative", "2026-10-12 19:40:00", "yesterday 21:40"),
                    ("relative", "2026-10-11 21:59:00", "11 Oct"),
                    ("relative", "2025-12-31 12:00:00", "31 Dec 2025"),
                    // Ahead: within the slack "just now", then the clock time, then the date.
                    ("relative", "2026-10-13 12:05:00", "just now"),
                    ("relative", "2026-10-13 14:00:00", "today 16:00"),
                    ("relative", "2026-10-15 12:00:00", "15 Oct"),
                    ("when", "2026-10-13 06:12:00", "08:12"),
                    ("when", "2026-10-12 22:00:00", "00:00"),
                    ("when", "2026-10-12 21:59:00", "yesterday 23:59"),
                    ("when", "2026-10-11 21:59:00", "11 Oct"),
                    ("when", "2025-12-31 12:00:00", "31 Dec 2025"),
                    ("when", "2026-10-13 14:00:00", "16:00"),
                    ("at", "2026-10-13 06:12:00", "08:12"),
                    ("at", "2026-10-12 21:59:00", "yesterday 23:59"),
                    ("at", "2026-10-11 21:59:00", "11 Oct 23:59"),
                    ("at", "2025-12-31 12:00:00", "31 Dec 2025 13:00"),
                    ("until", "2026-10-13 21:55:00", "23:55"),
                    // A code made at 23:50 (15 min), install mode, a typed-code pause.
                    ("until", "2026-10-13 22:05:00", "00:05 tomorrow"),
                    ("until", "2026-10-14 21:59:00", "23:59 tomorrow"),
                    ("until", "2026-10-15 12:30:00", "Thu 14:30"),
                    ("until", "2026-10-19 12:30:00", "Mon 14:30"),
                    ("until", "2026-10-20 12:30:00", "20 Oct 14:30"),
                    ("until", "2027-01-20 12:30:00", "20 Jan 2027 13:30"),
                    // Already past: as `when`.
                    ("until", "2026-10-13 21:00:00", "23:00"),
                ],
            ),
            (
                Lang::Nb,
                vec![
                    ("relative", "2026-10-13 12:00:00", "nå nettopp"),
                    ("relative", "2026-10-13 11:59:00", "for 1 min siden"),
                    ("relative", "2026-10-13 11:57:00", "for 3 min siden"),
                    ("relative", "2026-10-13 11:00:00", "for 1 t siden"),
                    ("relative", "2026-10-13 10:00:00", "for 2 t siden"),
                    ("relative", "2026-10-12 22:00:00", "for 14 t siden"),
                    ("relative", "2026-10-12 21:59:00", "i går 23:59"),
                    ("relative", "2026-10-11 21:59:00", "11. okt."),
                    ("relative", "2025-12-31 12:00:00", "31. des. 2025"),
                    ("relative", "2026-10-13 12:05:00", "nå nettopp"),
                    ("relative", "2026-10-13 14:00:00", "i dag 16:00"),
                    ("relative", "2026-10-15 12:00:00", "15. okt."),
                    ("when", "2026-10-13 06:12:00", "08:12"),
                    ("when", "2026-10-12 22:00:00", "00:00"),
                    ("when", "2026-10-12 21:59:00", "i går 23:59"),
                    ("when", "2026-10-11 21:59:00", "11. okt."),
                    ("when", "2025-12-31 12:00:00", "31. des. 2025"),
                    ("at", "2026-10-12 21:59:00", "i går 23:59"),
                    ("at", "2026-10-11 21:59:00", "11. okt. 23:59"),
                    ("at", "2025-12-31 12:00:00", "31. des. 2025 13:00"),
                    ("until", "2026-10-13 21:55:00", "23:55"),
                    ("until", "2026-10-13 22:05:00", "i morgen 00:05"),
                    ("until", "2026-10-14 21:59:00", "i morgen 23:59"),
                    ("until", "2026-10-15 12:30:00", "tor. 14:30"),
                    ("until", "2026-10-19 12:30:00", "man. 14:30"),
                    ("until", "2026-10-20 12:30:00", "20. okt. 14:30"),
                    ("until", "2027-01-20 12:30:00", "20. jan. 2027 13:30"),
                    ("until", "2026-10-13 21:00:00", "23:00"),
                ],
            ),
        ] {
            let zone = oslo().in_lang(lang);
            for (form, t, want) in rows {
                let got = match form {
                    "relative" => zone.relative(utc(t), now),
                    "when" => zone.when(utc(t), now),
                    "at" => zone.at(utc(t), now),
                    "until" => zone.until(utc(t), late),
                    _ => unreachable!(),
                };
                assert_eq!(got, want, "{lang:?} {form} {t}");
            }
        }
        // An hour that crosses midnight is still minutes.
        let after_midnight = utc("2026-10-12 22:10:00"); // 00:10 on the 13th
        assert_eq!(
            oslo().relative(utc("2026-10-12 21:50:00"), after_midnight),
            "20 min ago"
        );
        assert_eq!(
            nb().relative(utc("2026-10-12 21:50:00"), after_midnight),
            "for 20 min siden"
        );
        assert_eq!(
            Zone::utc_fallback().when(utc("2026-10-13 06:12:00"), now),
            "06:12 UTC"
        );
        assert_eq!(
            Zone::utc_fallback().until(utc("2026-10-13 22:05:00"), late),
            "22:05 UTC"
        );
    }

    /// Every month's and weekday's name is in both catalogs, and a date on its own.
    #[test]
    fn months_weekdays_and_dates() {
        let today = NaiveDate::from_ymd_opt(2026, 10, 13).unwrap();
        let months_en = [
            "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
        ];
        let months_nb = [
            "jan.", "feb.", "mars", "apr.", "mai", "juni", "juli", "aug.", "sep.", "okt.", "nov.",
            "des.",
        ];
        for month in 1..=12 {
            let day = NaiveDate::from_ymd_opt(2026, month, 1).unwrap();
            assert_eq!(oslo().month_name(day), months_en[month as usize - 1]);
            assert_eq!(nb().month_name(day), months_nb[month as usize - 1]);
        }
        let days_en = ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"];
        let days_nb = ["man.", "tir.", "ons.", "tor.", "fre.", "lør.", "søn."];
        for i in 0..7 {
            // 2026-10-12 is a Monday.
            let day = NaiveDate::from_ymd_opt(2026, 10, 12 + i).unwrap();
            assert_eq!(oslo().weekday_name(day), days_en[i as usize]);
            assert_eq!(nb().weekday_name(day), days_nb[i as usize]);
        }
        let day = NaiveDate::from_ymd_opt(2026, 3, 5).unwrap();
        assert_eq!(oslo().date_of(day, today), "5 Mar");
        assert_eq!(nb().date_of(day, today), "5. mars");
        let other_year = NaiveDate::from_ymd_opt(2025, 3, 5).unwrap();
        assert_eq!(oslo().date_of(other_year, today), "5 Mar 2025");
        assert_eq!(nb().date_of(other_year, today), "5. mars 2025");
    }

    /// The status lines' "since" (2026-11-01): the clock alone today, never a date for today.
    #[test]
    fn since_says_the_clock_today_and_the_weekday_within_the_week() {
        // Sunday 1 Nov 2026, 13:00 in Oslo (CET).
        let now = utc("2026-11-01 12:00:00");
        for (at, en, nb_words) in [
            ("2026-11-01 08:05:00", "09:05", "09:05"),
            ("2026-10-31 20:40:00", "yesterday 21:40", "i går 21:40"),
            ("2026-10-29 21:19:00", "Thu 22:19", "tor. 22:19"),
            // Eight days back, still in summer time.
            ("2026-10-24 20:19:00", "24 Oct 22:19", "24. okt. 22:19"),
            (
                "2025-12-30 07:00:00",
                "30 Dec 2025 08:00",
                "30. des. 2025 08:00",
            ),
        ] {
            assert_eq!(oslo().since(utc(at), now), en, "{at}");
            assert_eq!(nb().since(utc(at), now), nb_words, "{at}");
        }
        // Later today (a clock that moved back): its clock; another day ahead: [Zone::at].
        assert_eq!(oslo().since(utc("2026-11-01 14:00:00"), now), "15:00");
        assert_eq!(
            oslo().since(utc("2026-11-03 14:00:00"), now),
            oslo().at(utc("2026-11-03 14:00:00"), now)
        );
    }

    #[test]
    fn a_duration_in_both_languages() {
        for (minutes, en, nb) in [
            (0, "0 min", "0 min"),
            (45, "45 min", "45 min"),
            (60, "1 h", "1 t"),
            (90, "1 h 30 min", "1 t 30 min"),
            (150, "2 h 30 min", "2 t 30 min"),
        ] {
            assert_eq!(duration(Lang::En, minutes), en);
            assert_eq!(duration(Lang::Nb, minutes), nb);
        }
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
