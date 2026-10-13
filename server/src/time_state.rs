//! The phone's last `time_state` as typed values (design 31 §3.10, M4, M5): the lock badge
//! ("Skole until 14:30", "Leggetid until 07:00 tomorrow", "Screen time used up") and today's
//! screen time ("42 of 90 min today"), for the Devices list now and the summary's header later.
//!
//! The report has the rule's name and the phone's local `day`, never the window's end, so the end
//! comes from the rule's days on this server, and only when it can't be wrong:
//! - the phone's `day` and the day before must give the same end time for the rule (a rule active
//!   at 01:00 may be the day before's overnight window);
//! - the end is the first time of day after the report (in the Phone Setup zone) - "tomorrow" when
//!   that falls on the server's next local day;
//! - a report from another day than the server's local today shows no end and no bar, and says
//!   "at last report (yesterday 21:40)"; so does an end already passed.
//!
//! Offline always adds "at last report"; Away once the report is older than 15 minutes (L2).
//! A launcher `active_until` field would remove the guess (an API change, left out of design 31).

use chrono::{DateTime, Datelike, Duration, NaiveDate, NaiveDateTime, NaiveTime, Utc};

use crate::display_time::Zone;
use crate::streams::ConnectionState;
use crate::time_rules::DayWindow;

/// Away adds "at last report" once the report is older than this (L2).
pub const AWAY_STALE_MINUTES: i64 = 15;

/// The fields of the launcher's `time_state` the badge and the bar read.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct TimeReport {
    /// NONE, BEDTIME, SCHOOL, RULE or SCREEN_TIME (upper-cased here).
    pub lock_reason: String,
    pub active_rule_id: Option<i64>,
    pub active_rule_name: Option<String>,
    pub calls_blocked: bool,
    /// The phone's local date being counted.
    pub day: Option<NaiveDate>,
    pub used_minutes: Option<i64>,
    /// Today's budget including extra time; `None` = no limit.
    pub budget_minutes: Option<i64>,
}

impl TimeReport {
    /// From `device_status.time_state_json`; `None` when it isn't an object.
    pub fn parse(json: &str) -> Option<TimeReport> {
        let state: serde_json::Value = serde_json::from_str(json).ok()?;
        if !state.is_object() {
            return None;
        }
        Some(TimeReport {
            lock_reason: state["lock_reason"]
                .as_str()
                .unwrap_or("NONE")
                .to_ascii_uppercase(),
            active_rule_id: state["active_rule_id"].as_i64(),
            active_rule_name: state["active_rule_name"]
                .as_str()
                .map(str::trim)
                .filter(|name| !name.is_empty())
                .map(str::to_string),
            calls_blocked: state["calls_blocked"].as_bool() == Some(true),
            day: state["day"]
                .as_str()
                .and_then(|d| NaiveDate::parse_from_str(d, "%Y-%m-%d").ok()),
            used_minutes: state["used_minutes"].as_i64().filter(|m| *m >= 0),
            budget_minutes: state["budget_minutes"].as_i64().filter(|m| *m >= 0),
        })
    }

    /// An older launcher without `time_state`: only `device_status.lock_reason`.
    pub fn from_lock_reason(lock_reason: &str) -> TimeReport {
        TimeReport {
            lock_reason: lock_reason.to_ascii_uppercase(),
            ..Default::default()
        }
    }

    /// Something is locking the phone.
    pub fn locking(&self) -> bool {
        !self.lock_reason.is_empty() && self.lock_reason != "NONE"
    }

    /// The badge's name: the rule's, else the kind of lock.
    fn lock_name(&self) -> Option<String> {
        if !self.locking() {
            return None;
        }
        if let Some(name) = &self.active_rule_name {
            return Some(name.clone());
        }
        Some(
            match self.lock_reason.as_str() {
                "SCREEN_TIME" => "Screen time used up",
                "BEDTIME" => "Bedtime",
                "SCHOOL" => "School",
                "RULE" => "Time rule",
                _ => "Locked",
            }
            .to_string(),
        )
    }
}

/// The lock badge, while something locks the phone.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct LockBadge {
    pub name: String,
    /// "14:30" or "07:00 tomorrow", when it can't be wrong.
    pub until: Option<String>,
    pub calls_off: bool,
    /// "yesterday 21:40", "20 min ago": the report may be out of date.
    pub at_last_report: Option<String>,
}

impl LockBadge {
    /// "Skole until 14:30 · calls off", "Leggetid · at last report (yesterday 21:40)".
    pub fn text(&self) -> String {
        let mut text = self.name.clone();
        if let Some(until) = &self.until {
            text.push_str(&format!(" until {until}"));
        }
        if self.calls_off {
            text.push_str(" · calls off");
        }
        if let Some(when) = &self.at_last_report {
            text.push_str(&format!(" · at last report ({when})"));
        }
        text
    }

    /// As [LockBadge::text], without the time when the row already says it ("seen 3 h ago").
    pub fn text_beside(&self, seen: &str) -> String {
        match &self.at_last_report {
            Some(when) if when == seen => {
                let mut short = self.clone();
                short.at_last_report = None;
                format!("{} · at last report", short.text())
            }
            _ => self.text(),
        }
    }
}

/// Today's screen time.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct ScreenTime {
    pub used: i64,
    pub budget: Option<i64>,
}

impl ScreenTime {
    /// The bar's fill, 0-100; `None` without a limit (no bar).
    pub fn percent(&self) -> Option<i64> {
        self.budget.map(|budget| {
            if budget <= 0 {
                100
            } else {
                (self.used * 100 / budget).clamp(0, 100)
            }
        })
    }

    /// "42 of 90 min today", "42 min today" without a limit.
    pub fn text(&self) -> String {
        match self.budget {
            Some(budget) => format!("{} of {budget} min today", self.used),
            None => format!("{} min today", self.used),
        }
    }
}

/// Today's screen time, only when the report's `day` is the server's local today (M4); nothing
/// without a limit and no use.
pub fn screen_time(report: &TimeReport, zone: &Zone, now: DateTime<Utc>) -> Option<ScreenTime> {
    if report.day? != zone.today(now) {
        return None;
    }
    let used = report.used_minutes?;
    if report.budget_minutes.is_none() && used == 0 {
        return None;
    }
    Some(ScreenTime {
        used,
        budget: report.budget_minutes,
    })
}

/// The lock badge from the last report: `rule_days` are the active rule's 7 windows (Monday
/// first) when the rule is still one of the phone's, `reported_at` the report's time.
pub fn lock_badge(
    report: &TimeReport,
    rule_days: Option<&[Option<DayWindow>]>,
    reported_at: DateTime<Utc>,
    connection: &ConnectionState,
    zone: &Zone,
    now: DateTime<Utc>,
) -> Option<LockBadge> {
    let name = report.lock_name()?;
    let today = zone.today(now);
    let fresh_day = report.day == Some(today);
    let reported_local = zone.local(reported_at).naive_local();
    let end = match (fresh_day, rule_days, report.lock_reason.as_str()) {
        (true, Some(days), reason) if reason != "SCREEN_TIME" => {
            window_end(days, today, reported_local)
        }
        _ => None,
    };
    let now_local = zone.local(now).naive_local();
    let (until, ended) = match end {
        Some(end) if end > now_local => {
            let clock = zone.clock_of(end);
            let until = if end.date() == today {
                Some(clock)
            } else if end.date() == today.succ_opt()? {
                Some(format!("{clock} tomorrow"))
            } else {
                None
            };
            (until, false)
        }
        Some(_) => (None, true),
        None => (None, false),
    };
    // A report from another day; one without a day (an older launcher) can't be judged by it.
    let stale = report.day.is_some_and(|day| day != today)
        || ended
        || match connection {
            ConnectionState::Online { .. } => false,
            ConnectionState::Away { .. } => {
                now - reported_at > Duration::minutes(AWAY_STALE_MINUTES)
            }
            _ => true,
        };
    Some(LockBadge {
        name,
        until,
        calls_off: report.calls_blocked,
        at_last_report: stale.then(|| zone.relative(reported_at, now)),
    })
}

/// When the window active at `reported` (on `day`, local) ends: `None` when `day`'s window and
/// the day before's overnight one end at different times, or the report isn't from `day`.
fn window_end(
    days: &[Option<DayWindow>],
    day: NaiveDate,
    reported: NaiveDateTime,
) -> Option<NaiveDateTime> {
    if days.len() != 7 || reported.date() != day {
        return None;
    }
    let index = |d: NaiveDate| d.weekday().num_days_from_monday() as usize;
    // A window's end as a time of day: `start == end` lasts 24 h, so it ends at `start`.
    let end_of = |w: &DayWindow| if w.start == w.end { w.start } else { w.end };
    let mut ends = Vec::new();
    if let Some(w) = days[index(day)] {
        ends.push(end_of(&w));
    }
    // The day before's window reaches into `day` only when it wraps past midnight.
    if let Some(w) = day.pred_opt().and_then(|d| days[index(d)])
        && w.start >= w.end
    {
        ends.push(end_of(&w));
    }
    let end = *ends.first()?;
    if ends.iter().any(|e| *e != end) {
        return None;
    }
    let time = NaiveTime::from_hms_opt(
        u32::try_from(end / 60).ok()?,
        u32::try_from(end % 60).ok()?,
        0,
    )?;
    let same_day = day.and_time(time);
    Some(if same_day > reported {
        same_day
    } else {
        same_day + Duration::days(1)
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::display_time::parse_db_utc;

    fn utc(s: &str) -> DateTime<Utc> {
        parse_db_utc(s).unwrap()
    }

    fn oslo() -> Zone {
        Zone::from_setting("Europe/Oslo")
    }

    fn every_day(start: i64, end: i64) -> Vec<Option<DayWindow>> {
        vec![Some(DayWindow { start, end }); 7]
    }

    fn report(name: &str, reason: &str, day: &str) -> TimeReport {
        TimeReport {
            lock_reason: reason.to_string(),
            active_rule_id: Some(1),
            active_rule_name: Some(name.to_string()),
            day: NaiveDate::parse_from_str(day, "%Y-%m-%d").ok(),
            ..Default::default()
        }
    }

    fn online(now: DateTime<Utc>) -> ConnectionState {
        ConnectionState::Online {
            since: now,
            last_contact: now,
        }
    }

    #[test]
    fn the_report_parses_leniently() {
        let report = TimeReport::parse(
            r#"{"day":"2026-10-13","used_minutes":42,"budget_minutes":90,"extra_minutes":30,
               "active_rule_id":4,"active_rule_name":"Skole","calls_blocked":true,
               "lock_reason":"SCHOOL","lifts_active":[]}"#,
        )
        .unwrap();
        assert_eq!(report.active_rule_id, Some(4));
        assert_eq!(report.active_rule_name.as_deref(), Some("Skole"));
        assert!(report.calls_blocked && report.locking());
        assert_eq!(report.day, NaiveDate::from_ymd_opt(2026, 10, 13));
        assert_eq!(
            (report.used_minutes, report.budget_minutes),
            (Some(42), Some(90))
        );
        let empty = TimeReport::parse(r#"{"active_rule_name":"  ","day":"13.10.2026"}"#).unwrap();
        assert_eq!(empty.active_rule_name, None);
        assert_eq!(empty.day, None);
        assert!(!empty.locking());
        assert_eq!(TimeReport::parse("[]"), None);
        assert_eq!(TimeReport::parse("not json"), None);
        assert!(TimeReport::from_lock_reason("bedtime").locking());
        assert!(!TimeReport::from_lock_reason("none").locking());
    }

    #[test]
    fn a_school_day_ends_the_same_day() {
        let now = utc("2026-10-13 08:00:00"); // 10:00 in Oslo, a Tuesday
        let badge = lock_badge(
            &TimeReport {
                calls_blocked: true,
                ..report("Skole", "SCHOOL", "2026-10-13")
            },
            Some(&every_day(8 * 60, 14 * 60 + 30)),
            utc("2026-10-13 07:58:00"),
            &online(now),
            &oslo(),
            now,
        )
        .unwrap();
        assert_eq!(badge.text(), "Skole until 14:30 · calls off");
    }

    #[test]
    fn an_overnight_rule_ends_tomorrow_or_today() {
        let bedtime = every_day(21 * 60, 7 * 60);
        let zone = oslo();
        // 21:40 on the 13th: the window that started tonight ends tomorrow.
        let evening = utc("2026-10-13 19:40:00");
        let badge = lock_badge(
            &report("Leggetid", "BEDTIME", "2026-10-13"),
            Some(&bedtime),
            evening,
            &online(evening),
            &zone,
            evening,
        )
        .unwrap();
        assert_eq!(badge.text(), "Leggetid until 07:00 tomorrow");
        // 01:00 on the 14th: yesterday's window ends this morning.
        let night = utc("2026-10-13 23:00:00");
        let badge = lock_badge(
            &report("Leggetid", "BEDTIME", "2026-10-14"),
            Some(&bedtime),
            night,
            &online(night),
            &zone,
            night,
        )
        .unwrap();
        assert_eq!(badge.text(), "Leggetid until 07:00");
    }

    #[test]
    fn two_possible_ends_show_none() {
        // Monday 21:00-07:00, Tuesday 21:00-06:30: on Tuesday the rule may end at 07:00 (Monday's)
        // or 06:30 (Tuesday's) - the report doesn't say which window it is.
        let mut days = every_day(21 * 60, 7 * 60);
        days[1] = Some(DayWindow {
            start: 21 * 60,
            end: 6 * 60 + 30,
        });
        let now = utc("2026-10-13 20:00:00"); // Tuesday 22:00
        let badge = lock_badge(
            &report("Leggetid", "BEDTIME", "2026-10-13"),
            Some(&days),
            now,
            &online(now),
            &oslo(),
            now,
        )
        .unwrap();
        assert_eq!(badge.text(), "Leggetid");
        // A rule no longer on the phone's list: the name alone.
        let badge = lock_badge(
            &report("Leggetid", "BEDTIME", "2026-10-13"),
            None,
            now,
            &online(now),
            &oslo(),
            now,
        )
        .unwrap();
        assert_eq!(badge.text(), "Leggetid");
    }

    #[test]
    fn a_report_from_yesterday_says_so_and_shows_no_bar() {
        let now = utc("2026-10-14 06:00:00"); // 08:00 on the 14th
        let reported = utc("2026-10-13 19:40:00"); // 21:40 on the 13th
        let stale = TimeReport {
            used_minutes: Some(42),
            budget_minutes: Some(90),
            ..report("Leggetid", "BEDTIME", "2026-10-13")
        };
        let offline = ConnectionState::Offline {
            last_contact: reported,
        };
        let badge = lock_badge(
            &stale,
            Some(&every_day(21 * 60, 7 * 60)),
            reported,
            &offline,
            &oslo(),
            now,
        )
        .unwrap();
        assert_eq!(badge.text(), "Leggetid · at last report (yesterday 21:40)");
        assert_eq!(
            badge.text_beside("yesterday 21:40"),
            "Leggetid · at last report"
        );
        assert_eq!(badge.text_beside("2 min ago"), badge.text());
        assert_eq!(screen_time(&stale, &oslo(), now), None);
        let today = TimeReport {
            day: NaiveDate::from_ymd_opt(2026, 10, 14),
            ..stale
        };
        let bar = screen_time(&today, &oslo(), now).unwrap();
        assert_eq!(
            (bar.text(), bar.percent()),
            ("42 of 90 min today".to_string(), Some(46))
        );
    }

    #[test]
    fn offline_always_and_away_after_15_minutes_say_at_last_report() {
        let zone = oslo();
        let school = every_day(8 * 60, 14 * 60 + 30);
        let now = utc("2026-10-13 10:00:00"); // 12:00
        let badge = |reported: DateTime<Utc>, connection: ConnectionState| {
            lock_badge(
                &report("Skole", "SCHOOL", "2026-10-13"),
                Some(&school),
                reported,
                &connection,
                &zone,
                now,
            )
            .unwrap()
            .text()
        };
        let ten_ago = utc("2026-10-13 09:50:00");
        let twenty_ago = utc("2026-10-13 09:40:00");
        assert_eq!(
            badge(
                ten_ago,
                ConnectionState::Away {
                    last_contact: ten_ago
                }
            ),
            "Skole until 14:30"
        );
        assert_eq!(
            badge(
                twenty_ago,
                ConnectionState::Away {
                    last_contact: twenty_ago
                }
            ),
            "Skole until 14:30 · at last report (20 min ago)"
        );
        assert_eq!(
            badge(
                ten_ago,
                ConnectionState::Offline {
                    last_contact: ten_ago
                }
            ),
            "Skole until 14:30 · at last report (10 min ago)"
        );
        // An end already passed: the rule has probably ended since.
        let late = utc("2026-10-13 13:00:00"); // 15:00
        let badge = lock_badge(
            &report("Skole", "SCHOOL", "2026-10-13"),
            Some(&school),
            ten_ago,
            &online(late),
            &zone,
            late,
        )
        .unwrap();
        assert_eq!(badge.text(), "Skole · at last report (3 h ago)");
    }

    #[test]
    fn screen_time_and_plain_locks() {
        let zone = oslo();
        let now = utc("2026-10-13 10:00:00");
        let used_up = TimeReport {
            lock_reason: "SCREEN_TIME".to_string(),
            day: NaiveDate::from_ymd_opt(2026, 10, 13),
            used_minutes: Some(95),
            budget_minutes: Some(90),
            ..Default::default()
        };
        let badge = lock_badge(&used_up, None, now, &online(now), &zone, now).unwrap();
        assert_eq!(badge.text(), "Screen time used up");
        assert_eq!(
            screen_time(&used_up, &zone, now).unwrap().percent(),
            Some(100)
        );
        // No rule active: nothing.
        let free = TimeReport {
            lock_reason: "NONE".to_string(),
            ..used_up.clone()
        };
        assert_eq!(lock_badge(&free, None, now, &online(now), &zone, now), None);
        // No limit: a number without a bar, nothing at 0.
        let unlimited = TimeReport {
            budget_minutes: None,
            used_minutes: Some(42),
            ..free.clone()
        };
        let st = screen_time(&unlimited, &zone, now).unwrap();
        assert_eq!(
            (st.text(), st.percent()),
            ("42 min today".to_string(), None)
        );
        let idle = TimeReport {
            used_minutes: Some(0),
            ..unlimited
        };
        assert_eq!(screen_time(&idle, &zone, now), None);
        // An older launcher: the reason's word, no end.
        let old = TimeReport::from_lock_reason("BEDTIME");
        let badge = lock_badge(&old, None, now, &online(now), &zone, now).unwrap();
        assert_eq!(badge.text(), "Bedtime");
    }

    #[test]
    fn the_end_is_shown_in_the_fallback_zone_labelled() {
        let zone = Zone::from_setting("Europe/Olso");
        let now = utc("2026-10-13 10:00:00");
        let badge = lock_badge(
            &report("Skole", "SCHOOL", "2026-10-13"),
            Some(&every_day(8 * 60, 14 * 60 + 30)),
            now,
            &online(now),
            &zone,
            now,
        )
        .unwrap();
        assert_eq!(badge.text(), "Skole until 14:30 UTC");
    }
}
