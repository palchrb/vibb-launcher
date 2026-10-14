//! The phone's last `time_state` as typed values (design 31 §3.10, M4, M5): the lock badge
//! ("Skole until 14:30", "Leggetid until 07:00 tomorrow", "Screen time used up") and today's
//! screen time ("42 of 90 min today"), for the Devices list now and the summary's header later.
//!
//! **The end time is shown only when it is provably right** (the parent's rule, qa-31-s2-code M1).
//! The report has the rule's name and the phone's local `day`, never the window's end, so the end
//! comes from the rules' days on this server, and only when every one of these holds:
//! - the phone says its own time zone (`time_state.zone`) and it is the Phone Setup zone: the
//!   rules' windows are minutes of the *phone's* local day. The launcher sends its
//!   `ZoneId.systemDefault().id` since design 34 P13; an older launcher sends none, and then no end
//!   shows. A zone that isn't exactly Phone Setup's IANA name (another zone, an offset id, garbage,
//!   another case) shows none either;
//! - the report is fresh (below), from the server's local today, and its local time lies inside
//!   one window of the active rule;
//! - at that window's end no rule of the phone has a window running or starting (the same
//!   rule's next day, an all-day rule, a legacy morning/evening pair, an adjacent rule), and the
//!   day's screen time isn't used up: the end is a real unlock;
//! - the end is exactly one instant in the zone (`Zone::single_instant`): not in a spring-forward
//!   gap, where the phone unlocks at the jump, nor in a fall-back hour, which comes twice;
//! - the end is still ahead (else the report is out of date: "at last report").
//!
//! **Stale** ("at last report (yesterday 21:40)", never an end): a report from another day than
//! the server's local today (in the fallback zone: more than a day from the UTC date, since the
//! phone's zone is unknown), Offline always, Away once the report is older than 15 minutes (L2).
//!
//! "· calls off" only when the phone blocks calls for real: the rule's flag, calls managed, and
//! no call report saying the launcher doesn't screen them (M3).

use chrono::{DateTime, Datelike, Duration, NaiveDate, NaiveDateTime, NaiveTime, Timelike, Utc};

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
    /// The parent's extra time counted into today's budget.
    pub extra_minutes: Option<i64>,
    /// The phone couldn't read its screen-time record and counts today as used up.
    pub ledger_unreadable: bool,
    /// The phone's own IANA time zone (design 34 P13; `None` from an older launcher, or a value
    /// that isn't a non-empty string of at most 64 bytes). Only compared with Phone Setup's name.
    pub zone: Option<String>,
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
            extra_minutes: state["extra_minutes"].as_i64().filter(|m| *m > 0),
            ledger_unreadable: state["ledger_unreadable"].as_bool() == Some(true),
            zone: state["zone"]
                .as_str()
                .filter(|z| !z.is_empty() && z.len() <= 64)
                .map(str::to_string),
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

    /// The day's screen time is used up: when a rule ends, the screen-time lock goes on.
    fn budget_used_up(&self) -> bool {
        matches!((self.used_minutes, self.budget_minutes), (Some(used), Some(budget)) if used >= budget)
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

/// Each rule's id and its 7 windows, for [BadgeContext::rules]. A rule whose days don't read is
/// kept as "always on" (an empty list), which refuses any end.
pub fn rule_days(rules: &[crate::time_rules::TimeRuleRow]) -> Vec<(i64, Vec<Option<DayWindow>>)> {
    rules
        .iter()
        .map(|rule| {
            let days = crate::time_rules::parse_days(&rule.days_json).unwrap_or_default();
            (rule.id, days)
        })
        .collect()
}

/// The lock badge, while something locks the phone.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct LockBadge {
    pub name: String,
    /// "14:30" or "07:00 tomorrow", only when provably right.
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
    /// The phone's day ("13 Oct") when "today" can't be known (the fallback zone).
    pub day: Option<String>,
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

    /// "42 of 90 min today", "42 min today" without a limit, "42 of 90 min on 13 Oct" in the
    /// fallback zone.
    pub fn text(&self) -> String {
        let when = match &self.day {
            Some(day) => format!("on {day}"),
            None => "today".to_string(),
        };
        match self.budget {
            Some(budget) => format!("{} of {budget} min {when}", self.used),
            None => format!("{} min {when}", self.used),
        }
    }
}

/// The report's `day` is the current day: the server's local today, or - in the fallback zone,
/// where the phone's zone is unknown - within a day of the UTC date.
fn current_day(day: NaiveDate, zone: &Zone, now: DateTime<Utc>) -> bool {
    let today = zone.today(now);
    if zone.is_fallback() {
        (day - today).num_days().abs() <= 1
    } else {
        day == today
    }
}

/// Today's screen time, only for a report of the current day (M4); nothing without a limit and
/// no use. In the fallback zone it names the phone's day instead of "today" (M2).
pub fn screen_time(report: &TimeReport, zone: &Zone, now: DateTime<Utc>) -> Option<ScreenTime> {
    let day = report.day?;
    if !current_day(day, zone, now) {
        return None;
    }
    let used = report.used_minutes?;
    if report.budget_minutes.is_none() && used == 0 {
        return None;
    }
    Some(ScreenTime {
        used,
        budget: report.budget_minutes,
        day: zone.is_fallback().then(|| day.format("%-d %b").to_string()),
    })
}

/// What the badge needs besides the report.
pub struct BadgeContext<'a> {
    /// The phone's effective rules: id and 7 windows (Monday first).
    pub rules: &'a [(i64, Vec<Option<DayWindow>>)],
    /// When the report reached this server.
    pub reported_at: DateTime<Utc>,
    pub connection: &'a ConnectionState,
    /// Calls are managed and the launcher screens them (the call report has no
    /// `NotEnforced`/`AnyoneCanCall`).
    pub calls_enforced: bool,
}

/// The lock badge from the last report.
pub fn lock_badge(
    report: &TimeReport,
    ctx: &BadgeContext,
    zone: &Zone,
    now: DateTime<Utc>,
) -> Option<LockBadge> {
    let name = report.lock_name()?;
    // A report from another day; one without a day (an older launcher) can't be judged by it.
    let mut stale = report.day.is_some_and(|day| !current_day(day, zone, now))
        || match ctx.connection {
            ConnectionState::Online { .. } => false,
            ConnectionState::Away { .. } => {
                now - ctx.reported_at > Duration::minutes(AWAY_STALE_MINUTES)
            }
            _ => true,
        };
    let mut until = None;
    if !stale && let Some(end) = provable_end(report, ctx.rules, ctx.reported_at, zone) {
        let now_local = zone.local(now).naive_local();
        let today = zone.today(now);
        if end <= now_local {
            // The rule should have ended since: the report is out of date.
            stale = true;
        } else if end.date() == today {
            until = Some(zone.clock_of(end));
        } else if Some(end.date()) == today.succ_opt() {
            until = Some(format!("{} tomorrow", zone.clock_of(end)));
        }
    }
    Some(LockBadge {
        name,
        until,
        calls_off: report.calls_blocked && ctx.calls_enforced,
        at_last_report: stale.then(|| zone.relative(ctx.reported_at, now)),
    })
}

/// The local time the active rule's lock ends, when it can't be wrong (the module's list).
fn provable_end(
    report: &TimeReport,
    rules: &[(i64, Vec<Option<DayWindow>>)],
    reported_at: DateTime<Utc>,
    zone: &Zone,
) -> Option<NaiveDateTime> {
    // The windows are the phone's wall clock: only with the phone in the Phone Setup zone.
    if report.zone.as_deref()? != zone.name()? {
        return None;
    }
    if report.lock_reason == "SCREEN_TIME" || report.budget_used_up() {
        return None;
    }
    let rule_id = report.active_rule_id?;
    let (_, days) = rules.iter().find(|(id, _)| *id == rule_id)?;
    let reported = zone.local(reported_at).naive_local();
    if Some(reported.date()) != report.day {
        return None;
    }
    let ends = covering_ends(days, reported);
    let end = *ends.first()?;
    if ends.iter().any(|e| *e != end) {
        return None;
    }
    // The end is one instant on the phone's clock (qa-34-s13-code L2): an end in the
    // spring-forward gap never shows (the phone unlocks at the jump), and one in the fall-back
    // hour shows twice - neither is a clock time to promise.
    if !zone.single_instant(end) {
        return None;
    }
    // A real unlock: nothing locks at the end instant (the same rule's next window included).
    if rules
        .iter()
        .any(|(_, days)| !covering_ends(days, end).is_empty())
    {
        return None;
    }
    Some(end)
}

/// The ends of the windows of `days` that cover the local time `at` (to the minute). A window is
/// `[start, end)` on its day; `start > end` ends the next day, `start == end` lasts 24 h. A rule
/// whose days aren't 7 entries is always on (the launcher's fail-closed rule): it covers
/// everything and ends never, which refuses any end.
fn covering_ends(days: &[Option<DayWindow>], at: NaiveDateTime) -> Vec<NaiveDateTime> {
    if days.len() != 7 {
        return vec![NaiveDateTime::MAX];
    }
    let index = |d: NaiveDate| d.weekday().num_days_from_monday() as usize;
    let minute = i64::from(at.hour() * 60 + at.minute());
    let date = at.date();
    let clock = |m: i64| {
        NaiveTime::from_hms_opt(
            u32::try_from(m / 60).unwrap_or(0),
            u32::try_from(m % 60).unwrap_or(0),
            0,
        )
        .unwrap_or(NaiveTime::MIN)
    };
    let mut ends = Vec::new();
    if let Some(w) = days[index(date)] {
        if w.start < w.end {
            if w.start <= minute && minute < w.end {
                ends.push(date.and_time(clock(w.end)));
            }
        } else if minute >= w.start {
            // Wraps past midnight (or 24 h, ending at its start).
            let end = if w.start == w.end { w.start } else { w.end };
            if let Some(next) = date.succ_opt() {
                ends.push(next.and_time(clock(end)));
            }
        }
    }
    if let Some(w) = date.pred_opt().and_then(|d| days[index(d)])
        && w.start >= w.end
    {
        let end = if w.start == w.end { w.start } else { w.end };
        if minute < end {
            ends.push(date.and_time(clock(end)));
        }
    }
    ends
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

    fn window(start: i64, end: i64) -> Option<DayWindow> {
        Some(DayWindow { start, end })
    }

    fn every_day(start: i64, end: i64) -> Vec<Option<DayWindow>> {
        vec![window(start, end); 7]
    }

    /// A report from a phone in Oslo, locked by rule 1.
    fn report(name: &str, reason: &str, day: &str) -> TimeReport {
        TimeReport {
            lock_reason: reason.to_string(),
            active_rule_id: Some(1),
            active_rule_name: Some(name.to_string()),
            day: NaiveDate::parse_from_str(day, "%Y-%m-%d").ok(),
            zone: Some("Europe/Oslo".to_string()),
            ..Default::default()
        }
    }

    fn online(now: DateTime<Utc>) -> ConnectionState {
        ConnectionState::Online {
            since: now,
            last_contact: now,
        }
    }

    /// The badge's text for `report` sent at `reported` (Online, calls enforced), at `now`.
    fn badge_at(
        report: &TimeReport,
        rules: &[(i64, Vec<Option<DayWindow>>)],
        reported: &str,
        now: &str,
    ) -> String {
        let now = utc(now);
        lock_badge(
            report,
            &BadgeContext {
                rules,
                reported_at: utc(reported),
                connection: &online(now),
                calls_enforced: true,
            },
            &oslo(),
            now,
        )
        .unwrap()
        .text()
    }

    #[test]
    fn the_report_parses_leniently() {
        let report = TimeReport::parse(
            r#"{"day":"2026-10-13","used_minutes":42,"budget_minutes":90,"extra_minutes":30,
               "active_rule_id":4,"active_rule_name":"Skole","calls_blocked":true,
               "lock_reason":"SCHOOL","lifts_active":[],"zone":"Europe/Oslo"}"#,
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
        assert_eq!(report.zone.as_deref(), Some("Europe/Oslo"));
        assert_eq!(report.extra_minutes, Some(30));
        assert!(!report.ledger_unreadable);
        let empty = TimeReport::parse(r#"{"active_rule_name":"  ","day":"13.10.2026"}"#).unwrap();
        assert_eq!(empty.active_rule_name, None);
        assert_eq!((empty.day, empty.zone.as_deref()), (None, None));
        assert_eq!(empty.extra_minutes, None);
        assert!(
            TimeReport::parse(r#"{"ledger_unreadable":true,"extra_minutes":0}"#)
                .is_some_and(|r| r.ledger_unreadable && r.extra_minutes.is_none())
        );
        assert!(!empty.locking());
        assert_eq!(TimeReport::parse("[]"), None);
        assert_eq!(TimeReport::parse("not json"), None);
        assert!(TimeReport::from_lock_reason("bedtime").locking());
        assert!(!TimeReport::from_lock_reason("none").locking());
    }

    #[test]
    fn a_school_day_ends_the_same_day() {
        let rules = [(1, every_day(8 * 60, 14 * 60 + 30))];
        // 09:58 on Tuesday 13 Oct in Oslo.
        let skole = TimeReport {
            calls_blocked: true,
            ..report("Skole", "SCHOOL", "2026-10-13")
        };
        assert_eq!(
            badge_at(&skole, &rules, "2026-10-13 07:58:00", "2026-10-13 08:00:00"),
            "Skole until 14:30 · calls off"
        );
        // A minute before the end, still the end.
        assert_eq!(
            badge_at(&skole, &rules, "2026-10-13 12:29:00", "2026-10-13 12:29:30"),
            "Skole until 14:30 · calls off"
        );
    }

    #[test]
    fn a_report_just_after_the_end_never_says_tomorrow() {
        // M1 (1): the report reaches the server at 14:30:05 - outside the window.
        let rules = [(1, every_day(8 * 60, 14 * 60 + 30))];
        let skole = report("Skole", "SCHOOL", "2026-10-13");
        assert_eq!(
            badge_at(&skole, &rules, "2026-10-13 12:30:05", "2026-10-13 12:30:10"),
            "Skole"
        );
        // Hours later, offline: no end at all, never "14:30 tomorrow".
        let now = utc("2026-10-13 20:00:00");
        let reported = utc("2026-10-13 12:30:05");
        let text = lock_badge(
            &skole,
            &BadgeContext {
                rules: &rules,
                reported_at: reported,
                connection: &ConnectionState::Offline {
                    last_contact: reported,
                },
                calls_enforced: true,
            },
            &oslo(),
            now,
        )
        .unwrap()
        .text();
        assert_eq!(text, "Skole · at last report (7 h ago)");
    }

    #[test]
    fn a_phone_in_another_zone_or_one_that_doesnt_say_gets_no_end() {
        // M1 (2): a London phone at 14:00 local is 15:00 in Oslo.
        let rules = [(1, every_day(8 * 60, 14 * 60 + 30))];
        let london = TimeReport {
            zone: Some("Europe/London".to_string()),
            ..report("Skole", "SCHOOL", "2026-10-13")
        };
        assert_eq!(
            badge_at(
                &london,
                &rules,
                "2026-10-13 13:00:00",
                "2026-10-13 13:00:00"
            ),
            "Skole"
        );
        // A launcher from before design 34 P13 doesn't say its zone.
        let silent = TimeReport {
            zone: None,
            ..report("Skole", "SCHOOL", "2026-10-13")
        };
        assert_eq!(
            badge_at(
                &silent,
                &rules,
                "2026-10-13 08:00:00",
                "2026-10-13 08:00:00"
            ),
            "Skole"
        );
    }

    #[test]
    fn an_end_that_isnt_one_instant_shows_no_end() {
        // qa-34-s13-code L2: a rule 00:00-02:30 every night, Oslo.
        let rules = [(1, every_day(0, 2 * 60 + 30))];
        // An ordinary night: 01:30 CEST (23:30 UTC the day before) - the end shows.
        assert_eq!(
            badge_at(
                &report("Leggetid", "BEDTIME", "2026-10-14"),
                &rules,
                "2026-10-13 23:30:00",
                "2026-10-13 23:30:00"
            ),
            "Leggetid until 02:30"
        );
        // Spring forward, 2026-03-29: 01:30 CET (00:30 UTC); 02:00-03:00 doesn't exist, so 02:30
        // never shows - the phone unlocks at the jump. No end.
        assert_eq!(
            badge_at(
                &report("Leggetid", "BEDTIME", "2026-03-29"),
                &rules,
                "2026-03-29 00:30:00",
                "2026-03-29 00:30:00"
            ),
            "Leggetid"
        );
        // Fall back, 2026-10-25: 01:30 CEST (23:30 UTC the day before); 02:30 comes twice. No end.
        assert_eq!(
            badge_at(
                &report("Leggetid", "BEDTIME", "2026-10-25"),
                &rules,
                "2026-10-24 23:30:00",
                "2026-10-24 23:30:00"
            ),
            "Leggetid"
        );
        // The zone's own answer.
        let at = |s: &str| NaiveDateTime::parse_from_str(s, "%Y-%m-%d %H:%M").unwrap();
        assert!(!oslo().single_instant(at("2026-03-29 02:30")));
        assert!(!oslo().single_instant(at("2026-10-25 02:30")));
        assert!(oslo().single_instant(at("2026-10-25 03:30")));
    }

    #[test]
    fn the_zone_is_kept_only_as_a_short_non_empty_string() {
        // qa-34-s13-code N2: the 64-byte cap is `parse`'s own (the badge only compares).
        let zone = |z: serde_json::Value| {
            TimeReport::parse(&serde_json::json!({"lock_reason": "NONE", "zone": z}).to_string())
                .unwrap()
                .zone
        };
        assert_eq!(zone("Europe/Oslo".into()).as_deref(), Some("Europe/Oslo"));
        let longest = "A".repeat(64);
        assert_eq!(zone(longest.clone().into()), Some(longest));
        assert_eq!(zone("A".repeat(65).into()), None);
        assert_eq!(zone("".into()), None);
        assert_eq!(zone(serde_json::Value::Null), None);
        assert_eq!(zone(42.into()), None);
    }

    #[test]
    fn windows_that_meet_keep_the_phone_locked_so_no_end() {
        // M1 (3): an all-day rule every day is locked around the clock.
        let all_day = [(1, every_day(0, 0))];
        let rule = report("Alltid", "RULE", "2026-10-13");
        assert_eq!(
            badge_at(
                &rule,
                &all_day,
                "2026-10-13 08:00:00",
                "2026-10-13 08:00:00"
            ),
            "Alltid"
        );
        // The same 24 h window on one day only does end: Tuesday 00:00 to Wednesday 00:00.
        let mut tuesday = vec![None; 7];
        tuesday[1] = window(0, 0);
        assert_eq!(
            badge_at(
                &rule,
                &[(1, tuesday)],
                "2026-10-13 08:00:00",
                "2026-10-13 08:00:00"
            ),
            "Alltid until 00:00 tomorrow"
        );
        // A 24 h window from Monday 12:00 reaches into Tuesday morning (the day before's).
        let mut monday_noon = vec![None; 7];
        monday_noon[0] = window(12 * 60, 12 * 60);
        assert_eq!(
            badge_at(
                &rule,
                &[(1, monday_noon)],
                "2026-10-13 06:00:00",
                "2026-10-13 06:00:00"
            ),
            "Alltid until 12:00"
        );
        // A legacy evening/morning pair: the evening rule hands over to the morning one.
        let pair = [(1, every_day(21 * 60, 0)), (2, every_day(0, 7 * 60))];
        let evening = report("Outside allowed hours (evening)", "RULE", "2026-10-13");
        assert_eq!(
            badge_at(
                &evening,
                &pair,
                "2026-10-13 19:00:00",
                "2026-10-13 19:00:00"
            ),
            "Outside allowed hours (evening)"
        );
        // Two of the parent's rules that overlap: Skole to 14:30, a custom rule 14:00-16:00.
        let overlap = [
            (1, every_day(8 * 60, 14 * 60 + 30)),
            (2, every_day(14 * 60, 16 * 60)),
        ];
        let skole = report("Skole", "SCHOOL", "2026-10-13");
        assert_eq!(
            badge_at(
                &skole,
                &overlap,
                "2026-10-13 12:15:00",
                "2026-10-13 12:15:00"
            ),
            "Skole"
        );
        // An adjacent one starting right at the end.
        let adjacent = [
            (1, every_day(8 * 60, 14 * 60 + 30)),
            (2, every_day(14 * 60 + 30, 15 * 60)),
        ];
        assert_eq!(
            badge_at(
                &skole,
                &adjacent,
                "2026-10-13 10:00:00",
                "2026-10-13 10:00:00"
            ),
            "Skole"
        );
        // Screen time used up: the screen-time lock takes over at the end.
        let used_up = TimeReport {
            used_minutes: Some(90),
            budget_minutes: Some(90),
            ..skole.clone()
        };
        assert_eq!(
            badge_at(
                &used_up,
                &[(1, every_day(8 * 60, 14 * 60 + 30))],
                "2026-10-13 10:00:00",
                "2026-10-13 10:00:00"
            ),
            "Skole"
        );
    }

    #[test]
    fn an_overnight_rule_ends_tomorrow_or_today() {
        let bedtime = [(1, every_day(21 * 60, 7 * 60))];
        // 21:40 on the 13th: the window that started tonight ends tomorrow.
        assert_eq!(
            badge_at(
                &report("Leggetid", "BEDTIME", "2026-10-13"),
                &bedtime,
                "2026-10-13 19:40:00",
                "2026-10-13 19:40:00"
            ),
            "Leggetid until 07:00 tomorrow"
        );
        // 01:00 on the 14th: yesterday's window ends this morning.
        assert_eq!(
            badge_at(
                &report("Leggetid", "BEDTIME", "2026-10-14"),
                &bedtime,
                "2026-10-13 23:00:00",
                "2026-10-13 23:00:00"
            ),
            "Leggetid until 07:00"
        );
        // Sent at 23:59 on the 13th, read at 00:00 on the 14th, but the phone's day already says
        // the 14th: the clocks disagree about the day, so no end (L2: the report's date check).
        assert_eq!(
            badge_at(
                &report("Leggetid", "BEDTIME", "2026-10-14"),
                &bedtime,
                "2026-10-13 21:59:00",
                "2026-10-13 22:00:30"
            ),
            "Leggetid"
        );
    }

    #[test]
    fn two_possible_windows_or_none_show_no_end() {
        // Monday 21:00-07:00, Tuesday 21:00-06:30: at 01:00 on Wednesday Tuesday's window
        // covers; at 05:00 on Tuesday only Monday's does - but at Tuesday 22:00 only Tuesday's.
        let mut days = every_day(21 * 60, 7 * 60);
        days[1] = window(21 * 60, 6 * 60 + 30);
        let rules = [(1, days)];
        assert_eq!(
            badge_at(
                &report("Leggetid", "BEDTIME", "2026-10-13"),
                &rules,
                "2026-10-13 20:00:00",
                "2026-10-13 20:00:00"
            ),
            "Leggetid until 06:30 tomorrow"
        );
        // A rule no longer on the phone's list: the name alone.
        assert_eq!(
            badge_at(
                &report("Leggetid", "BEDTIME", "2026-10-13"),
                &[],
                "2026-10-13 20:00:00",
                "2026-10-13 20:00:00"
            ),
            "Leggetid"
        );
        // A report outside every window of the rule (12:00): no end.
        assert_eq!(
            badge_at(
                &report("Leggetid", "BEDTIME", "2026-10-13"),
                &rules,
                "2026-10-13 10:00:00",
                "2026-10-13 10:00:00"
            ),
            "Leggetid"
        );
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
        let rules = [(1, every_day(21 * 60, 7 * 60))];
        let badge = lock_badge(
            &stale,
            &BadgeContext {
                rules: &rules,
                reported_at: reported,
                connection: &online(now),
                calls_enforced: true,
            },
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
    fn offline_always_and_away_after_15_minutes_say_at_last_report_without_an_end() {
        let zone = oslo();
        let rules = [(1, every_day(8 * 60, 14 * 60 + 30))];
        let now = utc("2026-10-13 10:00:00"); // 12:00
        let badge = |minutes_ago: i64, away: bool| {
            let reported = now - Duration::minutes(minutes_ago);
            let connection = if away {
                ConnectionState::Away {
                    last_contact: reported,
                }
            } else {
                ConnectionState::Offline {
                    last_contact: reported,
                }
            };
            lock_badge(
                &report("Skole", "SCHOOL", "2026-10-13"),
                &BadgeContext {
                    rules: &rules,
                    reported_at: reported,
                    connection: &connection,
                    calls_enforced: true,
                },
                &zone,
                now,
            )
            .unwrap()
            .text()
        };
        assert_eq!(badge(10, true), "Skole until 14:30");
        assert_eq!(badge(15, true), "Skole until 14:30");
        assert_eq!(badge(16, true), "Skole · at last report (16 min ago)");
        assert_eq!(badge(10, false), "Skole · at last report (10 min ago)");
        // An end already passed: the rule has probably ended since.
        let late = utc("2026-10-13 13:00:00"); // 15:00
        assert_eq!(
            badge_at(
                &report("Skole", "SCHOOL", "2026-10-13"),
                &rules,
                "2026-10-13 09:50:00",
                "2026-10-13 13:00:00"
            ),
            format!(
                "Skole · at last report ({})",
                zone.relative(utc("2026-10-13 09:50:00"), late)
            )
        );
    }

    #[test]
    fn calls_off_only_while_calls_are_enforced() {
        let rules = [(1, every_day(8 * 60, 14 * 60 + 30))];
        let skole = TimeReport {
            calls_blocked: true,
            ..report("Skole", "SCHOOL", "2026-10-13")
        };
        let now = utc("2026-10-13 08:00:00");
        let badge = |calls_enforced: bool| {
            lock_badge(
                &skole,
                &BadgeContext {
                    rules: &rules,
                    reported_at: now,
                    connection: &online(now),
                    calls_enforced,
                },
                &oslo(),
                now,
            )
            .unwrap()
            .text()
        };
        assert_eq!(badge(true), "Skole until 14:30 · calls off");
        assert_eq!(badge(false), "Skole until 14:30");
    }

    #[test]
    fn screen_time_and_plain_locks() {
        let zone = oslo();
        let now = utc("2026-10-13 10:00:00");
        let ctx = BadgeContext {
            rules: &[],
            reported_at: now,
            connection: &online(now),
            calls_enforced: true,
        };
        let used_up = TimeReport {
            lock_reason: "SCREEN_TIME".to_string(),
            day: NaiveDate::from_ymd_opt(2026, 10, 13),
            used_minutes: Some(95),
            budget_minutes: Some(90),
            ..Default::default()
        };
        let badge = lock_badge(&used_up, &ctx, &zone, now).unwrap();
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
        assert_eq!(lock_badge(&free, &ctx, &zone, now), None);
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
        assert_eq!(
            lock_badge(&old, &ctx, &zone, now).unwrap().text(),
            "Bedtime"
        );
    }

    #[test]
    fn the_fallback_zone_shows_no_end_and_names_the_phones_day() {
        // M2: the Phone Setup zone left empty; phones in New York (UTC-4 in October).
        let zone = Zone::from_setting("");
        let rules = [(1, every_day(20 * 60, 7 * 60))];
        // 20:30 in New York is 00:30 UTC the next day: the phone's day is "yesterday" in UTC.
        let now = utc("2026-10-14 00:30:00");
        let bedtime = TimeReport {
            used_minutes: Some(42),
            budget_minutes: Some(90),
            zone: Some("America/New_York".to_string()),
            ..report("Bedtime", "BEDTIME", "2026-10-13")
        };
        let badge = lock_badge(
            &bedtime,
            &BadgeContext {
                rules: &rules,
                reported_at: now,
                connection: &online(now),
                calls_enforced: true,
            },
            &zone,
            now,
        )
        .unwrap();
        assert_eq!(badge.text(), "Bedtime", "no end, and not stale");
        let bar = screen_time(&bedtime, &zone, now).unwrap();
        assert_eq!(bar.text(), "42 of 90 min on 13 Oct");
        // Two days off is another day.
        let old = TimeReport {
            day: NaiveDate::from_ymd_opt(2026, 10, 11),
            ..bedtime
        };
        assert_eq!(screen_time(&old, &zone, now), None);
    }
}
