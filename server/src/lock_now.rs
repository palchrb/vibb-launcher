//! A rule turned on now, for the rest of a window today ("Slå på nå", design 93,
//! docs/design/93-lock-now-quick-actions.md). The phone gets one more entry in
//! `time_policy.rules`: a copy of the chosen rule whose days hold one window on today's weekday,
//! from the tap's minute to the chosen end in the phone's local time (§4.1). Its id is
//! `-(time_lock_now.id)`, so it never meets a rule's id and stays the same across a change of the
//! end. It carries `until_ms` (the end as an instant, design 93 §9.1) and `card_first` (§9.2).
//!
//! **Running** means not ended early and `ends_at` ahead: one condition read at every request,
//! with no timer (§3). At most one runs per phone. The copy follows its rule live; a rule deleted
//! or switched off for the phone is no longer served.

use chrono::{DateTime, Datelike, Duration, NaiveDate, NaiveDateTime, NaiveTime, TimeZone, Utc};
use chrono_tz::Tz;
use sqlx::SqlitePool;

use crate::time_rules::{DayWindow, LAST_MINUTE, PolicyLift, PolicyTimeRule, TimeRuleRow};

/// The capability a launcher claims once it honours `until_ms` and `card_first` (design 93 §9.3).
pub const CAPABILITY: &str = "lock_now_v1";
/// The lengths "Slå på nå" offers, in hours (§4.4); "Ut dagen" is the fourth choice.
pub const LENGTH_HOURS: [i64; 3] = [1, 2, 3];
/// The minutes "Forleng" adds (§6.2); "Ut dagen" is the third choice.
pub const ADD_MINUTES: [i64; 2] = [30, 60];

/// A `time_lock_now` row (migration 0142).
#[derive(sqlx::FromRow, Clone, Debug, PartialEq, Eq)]
pub struct LockNowRow {
    pub id: i64,
    pub device_id: i64,
    pub rule_id: Option<i64>,
    pub rule_name: String,
    pub zone: String,
    pub local_date: String,
    pub start_minute: i64,
    pub end_minute: i64,
    pub ends_at: String,
    pub created_at: String,
    pub created_by: Option<String>,
    pub changed_at: Option<String>,
    pub changed_by: Option<String>,
    pub ended_early_at: Option<String>,
    pub ended_by: Option<String>,
}

impl LockNowRow {
    /// The local date it runs on; `None` when the stored text doesn't read.
    pub fn date(&self) -> Option<NaiveDate> {
        NaiveDate::parse_from_str(&self.local_date, "%Y-%m-%d").ok()
    }

    /// `ends_at` as epoch milliseconds (`until_ms`).
    pub fn ends_at_ms(&self) -> Option<i64> {
        crate::display_time::parse_db_utc(&self.ends_at).map(|t| t.timestamp_millis())
    }

    /// The local end on the phone's wall clock: 1439 is the next day's midnight.
    pub fn local_end(&self) -> Option<NaiveDateTime> {
        Some(local_at(self.date()?, self.end_minute))
    }

    /// The days the copy is served with: its window on its weekday, nothing on the others.
    pub fn days(&self) -> Option<Vec<Option<DayWindow>>> {
        let weekday = self.date()?.weekday().num_days_from_monday() as usize;
        Some(
            (0..7)
                .map(|d| {
                    (d == weekday).then_some(DayWindow {
                        start: self.start_minute,
                        end: self.end_minute,
                    })
                })
                .collect(),
        )
    }
}

/// The wall time of `minute` on `date`; 1439 ("until midnight") is the next day's 00:00.
pub fn local_at(date: NaiveDate, minute: i64) -> NaiveDateTime {
    if minute >= LAST_MINUTE {
        return date.succ_opt().unwrap_or(date).and_time(NaiveTime::MIN);
    }
    let time = NaiveTime::from_hms_opt(
        u32::try_from(minute / 60).unwrap_or(0),
        u32::try_from(minute % 60).unwrap_or(0),
        0,
    )
    .unwrap_or(NaiveTime::MIN);
    date.and_time(time)
}

/// The instant of `minute` on `date` in `tz`, with the launcher's rule (`java.time`'s `atZone`,
/// §4.4): a wall time in a spring-forward gap moves later by the gap, one in a fall-back overlap
/// takes the earlier offset. 1439 is the next day's midnight.
pub fn instant_of(tz: Tz, date: NaiveDate, minute: i64) -> DateTime<Utc> {
    let local = local_at(date, minute);
    match tz.from_local_datetime(&local) {
        chrono::LocalResult::Single(t) => t.with_timezone(&Utc),
        chrono::LocalResult::Ambiguous(earlier, _) => earlier.with_timezone(&Utc),
        chrono::LocalResult::None => {
            // In the gap: the offset before it, so the wall time lands as many minutes past the jump.
            let before = (1..=4)
                .find_map(|h| {
                    tz.from_local_datetime(&(local - Duration::hours(h)))
                        .earliest()
                })
                .map(|t| chrono::Offset::fix(t.offset()).local_minus_utc())
                .unwrap_or(0);
            Utc.from_utc_datetime(&(local - Duration::seconds(i64::from(before))))
        }
    }
}

/// What the parent chose for the end (§5.1 step 2).
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Choice {
    /// 1, 2 or 3 hours from now, on the wall clock.
    Hours(i64),
    /// "Ut dagen": until midnight.
    Day,
    /// "Til HH:MM", as minutes; 0 (00:00) reads as "Ut dagen".
    Til(i64),
}

/// "HH:MM" from a `<input type="time">` as minutes; `None` when it isn't one.
pub fn parse_til(text: &str) -> Option<i64> {
    let (h, m) = text.trim().split_once(':')?;
    if h.len() != 2 || m.len() != 2 {
        return None;
    }
    let (h, m): (i64, i64) = (h.parse().ok()?, m.parse().ok()?);
    ((0..24).contains(&h) && (0..60).contains(&m)).then_some(h * 60 + m)
}

/// The local minute of day of `now_local`, rounded down.
pub fn minute_of(now_local: NaiveDateTime) -> i64 {
    use chrono::Timelike;
    i64::from(now_local.hour() * 60 + now_local.minute())
}

/// The end minute for a lock starting at `start` (§4.4): never past midnight, an end at exactly
/// 24:00 stored as 1439. The refusal is its flash code.
pub fn end_minute(start: i64, choice: Choice) -> Result<i64, &'static str> {
    if start >= LAST_MINUTE {
        return Err("day_over");
    }
    match choice {
        Choice::Day | Choice::Til(0) => Ok(LAST_MINUTE),
        Choice::Hours(h) => {
            let end = start + h * 60;
            if end > 24 * 60 {
                Err("past_midnight")
            } else {
                Ok(end.min(LAST_MINUTE))
            }
        }
        Choice::Til(m) if !(0..=LAST_MINUTE).contains(&m) => Err("til_invalid"),
        Choice::Til(m) if m <= start => Err("til_past"),
        Choice::Til(m) => Ok(m),
    }
}

/// Which of [LENGTH_HOURS] end by midnight from `start` (the others are `disabled`).
pub fn lengths_offered(start: i64) -> [bool; 3] {
    LENGTH_HOURS.map(|h| start + h * 60 <= 24 * 60)
}

/// The copy of `rule` for `lock` (§4.1): the rule's own name, kind and mode, one window on the
/// lock's weekday, `id` `-(lock.id)`. `None` when the lock's date doesn't read.
pub fn copy_row(lock: &LockNowRow, rule: &TimeRuleRow) -> Option<TimeRuleRow> {
    Some(TimeRuleRow {
        id: -lock.id,
        device_id: Some(lock.device_id),
        days_json: serde_json::to_string(&lock.days()?).ok()?,
        sort_order: 0,
        ..rule.clone()
    })
}

/// The copy goes first in `rules[]` (§4.2, for a launcher without `card_first`, which sorts by
/// kind, then index) unless a rule of its kind has an occurrence that overlaps its window and ends
/// after it - one from yesterday that runs past midnight included. Pure; `rules` are the served
/// rows without the copy.
pub fn copy_first(lock: &LockNowRow, kind: &str, rules: &[TimeRuleRow]) -> bool {
    let Some(date) = lock.date() else {
        return false;
    };
    let start = local_at(date, lock.start_minute);
    let end = local_at(date, lock.end_minute);
    rules.iter().filter(|r| r.kind == kind).all(|rule| {
        let days = crate::time_rules::parse_days(&rule.days_json)
            .map(|days| crate::time_rules::served_days(&days))
            .unwrap_or_default();
        // An occurrence overlapping [start, end) covers `start` or begins inside the window.
        let mut points = vec![start];
        if let Some(w) = days
            .get(date.weekday().num_days_from_monday() as usize)
            .copied()
            .flatten()
            && w.start > lock.start_minute
            && w.start < lock.end_minute
        {
            points.push(local_at(date, w.start));
        }
        points.iter().all(|at| {
            crate::time_state::covering_ends(&days, *at)
                .iter()
                .all(|e| *e <= end)
        })
    })
}

/// The twin of each pause of the turned-on rule (§4.3): `{id: -(lift.id), rule_id: -(lock.id)}`
/// with the same minutes and expiry, so the pause lifts the copy too. An "Alle regler" pause
/// (`rule_id` null) needs none.
pub fn twin_lifts(lifts: &[PolicyLift], lock: &LockNowRow) -> Vec<PolicyLift> {
    let Some(rule_id) = lock.rule_id else {
        return Vec::new();
    };
    lifts
        .iter()
        .filter(|l| l.target == "rule" && l.rule_id == Some(rule_id) && l.id > 0)
        .map(|l| PolicyLift {
            id: -l.id,
            rule_id: Some(-lock.id),
            ..l.clone()
        })
        .collect()
}

/// `now` in the stored `datetime()` form.
pub fn db_time(now: DateTime<Utc>) -> String {
    now.format("%Y-%m-%d %H:%M:%S").to_string()
}

/// The phone's running lock at `now`, if any.
pub async fn running(
    db: &SqlitePool,
    device_id: i64,
    now: DateTime<Utc>,
) -> Result<Option<LockNowRow>, sqlx::Error> {
    sqlx::query_as::<_, LockNowRow>(
        "SELECT * FROM time_lock_now WHERE device_id = ? AND ended_early_at IS NULL \
         AND ends_at > ? ORDER BY id DESC LIMIT 1",
    )
    .bind(device_id)
    .bind(db_time(now))
    .fetch_optional(db)
    .await
}

/// The rules the phone is served (§4.5): the effective rows, plus the running lock's copy at the
/// index of [copy_first]. `lock` is the running lock only while its rule still applies to the
/// phone (else nothing is served for it).
#[derive(Clone, Debug)]
pub struct ServedRules {
    pub rows: Vec<TimeRuleRow>,
    pub lock: Option<LockNowRow>,
}

impl ServedRules {
    /// The wire form: each row's `to_policy`, the copy with its `until_ms` and `card_first`.
    pub fn policy(&self) -> Result<Vec<PolicyTimeRule>, String> {
        self.rows
            .iter()
            .map(|row| {
                let mut rule = row.to_policy()?;
                if let Some(lock) = self.lock.as_ref().filter(|l| -l.id == row.id) {
                    rule.until_ms = Some(
                        lock.ends_at_ms()
                            .ok_or_else(|| format!("lock {}: unreadable end", lock.id))?,
                    );
                    rule.card_first = true;
                }
                Ok(rule)
            })
            .collect()
    }

    /// The lifts to serve beside `lifts` (the deliverable ones): their twins for the copy.
    pub fn with_twins(&self, mut lifts: Vec<PolicyLift>) -> Vec<PolicyLift> {
        if let Some(lock) = &self.lock {
            let twins = twin_lifts(&lifts, lock);
            lifts.extend(twins);
        }
        lifts
    }
}

/// [ServedRules] for the phone at `now`.
pub async fn served_rule_rows(
    db: &SqlitePool,
    device_id: i64,
    now: DateTime<Utc>,
) -> Result<ServedRules, sqlx::Error> {
    let mut rows = crate::time_rules::effective_rule_rows(db, device_id).await?;
    let Some(lock) = running(db, device_id, now).await? else {
        return Ok(ServedRules { rows, lock: None });
    };
    let copy = lock
        .rule_id
        .and_then(|id| rows.iter().find(|r| r.id == id))
        .and_then(|rule| copy_row(&lock, rule));
    let Some(copy) = copy else {
        return Ok(ServedRules { rows, lock: None });
    };
    if copy_first(&lock, &copy.kind, &rows) {
        rows.insert(0, copy);
    } else {
        rows.push(copy);
    }
    Ok(ServedRules {
        rows,
        lock: Some(lock),
    })
}

/// The rows the lock badge reads (§4.5): the served ones, so the badge names a copy by its rule
/// and can show its end; the effective ones when the lock can't be read (logged).
pub async fn badge_rows(db: &SqlitePool, device_id: i64, now: DateTime<Utc>) -> Vec<TimeRuleRow> {
    match served_rule_rows(db, device_id, now).await {
        Ok(served) => served.rows,
        Err(err) => {
            tracing::error!(device_id, %err, "can't read the phone's turned-on rule");
            crate::time_rules::effective_rule_rows(db, device_id)
                .await
                .unwrap_or_default()
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn d(s: &str) -> NaiveDate {
        NaiveDate::parse_from_str(s, "%Y-%m-%d").unwrap()
    }

    fn lock(date: &str, start: i64, end: i64) -> LockNowRow {
        LockNowRow {
            id: 7,
            device_id: 3,
            rule_id: Some(4),
            rule_name: "Skole".into(),
            zone: "Europe/Oslo".into(),
            local_date: date.into(),
            start_minute: start,
            end_minute: end,
            ends_at: "2026-11-16 17:00:00".into(),
            created_at: "2026-11-16 11:00:00".into(),
            created_by: None,
            changed_at: None,
            changed_by: None,
            ended_early_at: None,
            ended_by: None,
        }
    }

    fn rule(id: i64, kind: &str, days: &str, bedtime: bool) -> TimeRuleRow {
        TimeRuleRow {
            id,
            device_id: None,
            name: if bedtime { "Bedtime" } else { "Skole" }.into(),
            kind: kind.into(),
            calls_allowed: false,
            exempt_apps_json: r#"["org.fossify.clock"]"#.into(),
            days_json: days.into(),
            sort_order: 1,
            bedtime,
            calendar_allowed: true,
            silent: bedtime,
        }
    }

    const SCHOOL: &str = r#"[{"start":495,"end":870},{"start":495,"end":870},{"start":495,"end":870},{"start":495,"end":870},{"start":495,"end":870},null,null]"#;
    const NIGHTS: &str = r#"[{"start":1260,"end":420},{"start":1260,"end":420},{"start":1260,"end":420},{"start":1260,"end":420},{"start":1320,"end":480},{"start":1320,"end":480},{"start":1260,"end":420}]"#;

    /// §4.4: the lengths and Til, never past midnight, 00:00 as "Ut dagen", 24:00 as 1439.
    #[test]
    fn the_window() {
        assert_eq!(end_minute(990, Choice::Hours(2)), Ok(1110));
        assert_eq!(end_minute(990, Choice::Day), Ok(LAST_MINUTE));
        assert_eq!(end_minute(1260, Choice::Hours(3)), Ok(LAST_MINUTE));
        assert_eq!(end_minute(1261, Choice::Hours(3)), Err("past_midnight"));
        assert_eq!(end_minute(990, Choice::Til(1110)), Ok(1110));
        assert_eq!(end_minute(990, Choice::Til(0)), Ok(LAST_MINUTE));
        assert_eq!(end_minute(990, Choice::Til(990)), Err("til_past"));
        assert_eq!(end_minute(990, Choice::Til(600)), Err("til_past"));
        assert_eq!(end_minute(LAST_MINUTE, Choice::Day), Err("day_over"));
        assert_eq!(lengths_offered(1260), [true, true, true]);
        assert_eq!(lengths_offered(1290), [true, true, false]);
        assert_eq!(parse_til("18:30"), Some(1110));
        assert_eq!(parse_til("00:00"), Some(0));
        for bad in ["", "24:00", "7:30", "18:60", "ab:cd"] {
            assert_eq!(parse_til(bad), None, "{bad}");
        }
    }

    /// §4.4: Europe/Oslo's change days, against hand-written instants - the gap moves later, the
    /// overlap takes the earlier offset; 1439 is the next midnight.
    #[test]
    fn instants_in_the_zone() {
        let oslo: Tz = "Europe/Oslo".parse().unwrap();
        let utc = |s: &str| crate::display_time::parse_db_utc(s).unwrap();
        assert_eq!(
            instant_of(oslo, d("2026-11-16"), 1080),
            utc("2026-11-16 17:00:00")
        );
        assert_eq!(
            instant_of(oslo, d("2026-11-16"), LAST_MINUTE),
            utc("2026-11-16 23:00:00")
        );
        // 2026-03-29: 02:00 CET jumps to 03:00 CEST; 02:30 lands at 03:30 CEST.
        assert_eq!(
            instant_of(oslo, d("2026-03-29"), 150),
            utc("2026-03-29 01:30:00")
        );
        assert_eq!(
            instant_of(oslo, d("2026-03-29"), 210),
            utc("2026-03-29 01:30:00")
        );
        // 2026-10-25: 03:00 CEST falls back to 02:00 CET; 02:30 is the first one (CEST).
        assert_eq!(
            instant_of(oslo, d("2026-10-25"), 150),
            utc("2026-10-25 00:30:00")
        );
        assert_eq!(
            instant_of(oslo, d("2026-10-25"), 210),
            utc("2026-10-25 02:30:00")
        );
    }

    /// §4.2: first unless a rule of its kind overlaps and ends after it (yesterday's included).
    #[test]
    fn the_copys_index() {
        let school = [rule(4, "school", SCHOOL, false)];
        // Monday: Skole to 14:30. On until 13:00 ends earlier: last; until 15:00 ends later: first.
        assert!(!copy_first(
            &lock("2026-11-16", 600, 780),
            "school",
            &school
        ));
        assert!(copy_first(&lock("2026-11-16", 600, 900), "school", &school));
        // Starting before Skole and ending inside it: Skole begins inside and ends after.
        assert!(!copy_first(
            &lock("2026-11-16", 420, 600),
            "school",
            &school
        ));
        // Another kind never moves it.
        assert!(copy_first(
            &lock("2026-11-16", 600, 780),
            "bedtime",
            &school
        ));
        // Leggetid until 07:00 from Sunday night overlaps a copy from 06:30 to 06:50.
        let nights = [rule(9, "bedtime", NIGHTS, true)];
        assert!(!copy_first(
            &lock("2026-11-16", 390, 410),
            "bedtime",
            &nights
        ));
        assert!(copy_first(
            &lock("2026-11-16", 390, 600),
            "bedtime",
            &nights
        ));
        assert!(copy_first(
            &lock("2026-11-16", 720, 1080),
            "bedtime",
            &nights
        ));
    }

    /// §4.1, §4.3: the copy's id, one weekday, 23:59 served as midnight, a bedtime's blank name,
    /// the `until_ms` and `card_first`; the twin of a pause of its rule.
    #[test]
    fn the_served_copy_and_twin() {
        let mut lock = lock("2026-11-16", 990, LAST_MINUTE);
        lock.rule_id = Some(9);
        let bedtime = rule(9, "bedtime", NIGHTS, true);
        let copy = copy_row(&lock, &bedtime).unwrap();
        let served = ServedRules {
            rows: vec![copy, bedtime],
            lock: Some(lock.clone()),
        };
        let rules = served.policy().unwrap();
        assert_eq!(rules[0].id, -7);
        assert_eq!(rules[0].name, "");
        assert_eq!(rules[0].kind, "bedtime");
        assert_eq!(rules[0].days[0], Some(DayWindow { start: 990, end: 0 }));
        assert!(rules[0].days[1..].iter().all(Option::is_none));
        assert_eq!(rules[0].until_ms, Some(1_794_848_400_000));
        assert!(rules[0].card_first);
        assert_eq!((rules[1].until_ms, rules[1].card_first), (None, false));
        let lift = |id, rule_id| PolicyLift {
            id,
            target: "rule".into(),
            rule_id,
            minutes: 30,
            expires_at_ms: 5,
        };
        let lifts = served.with_twins(vec![lift(11, Some(9)), lift(12, None), lift(13, Some(4))]);
        assert_eq!(lifts.len(), 4);
        assert_eq!(lifts[3], lift(-11, Some(-7)));
    }

    /// A rule row: id, phone (None = everyone's), name, kind, calls, exempt apps, days. A bedtime
    /// is the phone's own and silent; Skole lets Kalender be used.
    type Rule<'a> = (i64, Option<i64>, &'a str, &'a str, bool, &'a str, &'a str);

    async fn add_rules(db: &SqlitePool, rules: &[Rule<'_>]) {
        for (id, device, name, kind, calls, apps, days) in rules {
            sqlx::query(
                "INSERT INTO time_rules (id, device_id, name, kind, calls_allowed, \
                 exempt_apps_json, days_json, sort_order, bedtime, calendar_allowed, silent) \
                 VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            )
            .bind(id)
            .bind(device)
            .bind(name)
            .bind(kind)
            .bind(calls)
            .bind(apps)
            .bind(days)
            .bind(id)
            .bind(*kind == "bedtime")
            .bind(*kind == "school")
            .bind(*kind == "bedtime")
            .execute(db)
            .await
            .unwrap();
        }
    }

    /// Design 93 §9.3: `policy` and `policy_end_locked` are what this server serves for the seeded
    /// rows at a fixed now - Emma's Leggetid turned on 12:00-18:00 on Monday 16 November (Oslo),
    /// everyone's Skole, a pause of Leggetid and its twin; then a Middag from 18:00 too.
    /// `LOCK_NOW_CONTRACT_WRITE=1` rewrites them.
    #[tokio::test]
    async fn the_contract() {
        let path = "testdata/lock_now.json";
        let mut contract: serde_json::Value =
            serde_json::from_str(&std::fs::read_to_string(path).unwrap()).unwrap();
        assert_eq!(contract["capability"], CAPABILITY);
        let dir = tempfile::tempdir().unwrap();
        let db =
            crate::connect_db(&format!("sqlite://{}", dir.path().join("t.db").display())).await;
        let device: i64 =
            sqlx::query_scalar("INSERT INTO devices (name) VALUES ('Emma') RETURNING id")
                .fetch_one(&db)
                .await
                .unwrap();
        let skole = (
            1,
            None,
            "Skole",
            "school",
            false,
            r#"["org.fossify.clock"]"#,
            SCHOOL,
        );
        let bedtime = (
            2,
            Some(device),
            "Bedtime",
            "bedtime",
            true,
            r#"["me.vibb.music"]"#,
            NIGHTS,
        );
        add_rules(&db, &[skole, bedtime]).await;
        sqlx::query(
            "INSERT INTO time_lock_now (id, device_id, rule_id, rule_name, zone, local_date, \
             start_minute, end_minute, ends_at) VALUES (1, ?, 2, 'Bedtime', 'Europe/Oslo', \
             '2026-11-16', 720, 1080, '2026-11-16 17:00:00')",
        )
        .bind(device)
        .execute(&db)
        .await
        .unwrap();
        let now = crate::display_time::parse_db_utc("2026-11-16 11:00:00").unwrap();
        let pause = PolicyLift {
            id: 5,
            target: "rule".into(),
            rule_id: Some(2),
            minutes: 30,
            expires_at_ms: 1_794_828_600_000,
        };
        let served = |served: ServedRules| {
            serde_json::to_value(crate::time_rules::TimePolicy {
                rules: served.policy().unwrap(),
                daily_budget_minutes: vec![None; 7],
                lifts: served.with_twins(vec![pause.clone()]),
            })
            .unwrap()
        };
        let policy = served(served_rule_rows(&db, device, now).await.unwrap());
        let middag = r#"[{"start":1080,"end":1110},null,null,null,null,null,null]"#;
        add_rules(&db, &[(3, None, "Middag", "custom", true, "[]", middag)]).await;
        let end_locked = served(served_rule_rows(&db, device, now).await.unwrap());
        if std::env::var("LOCK_NOW_CONTRACT_WRITE").is_ok() {
            contract["policy"] = policy.clone();
            contract["policy_end_locked"] = end_locked.clone();
            std::fs::write(
                path,
                serde_json::to_string_pretty(&contract).unwrap() + "\n",
            )
            .unwrap();
        }
        let changed =
            "the served policy changed - LOCK_NOW_CONTRACT_WRITE=1, then copy it to the launcher";
        assert_eq!(contract["policy"], policy, "{changed}");
        assert_eq!(contract["policy_end_locked"], end_locked, "{changed}");
        // Ended early or over: nothing is served for it.
        sqlx::query("UPDATE time_lock_now SET ended_early_at = '2026-11-16 11:30:00'")
            .execute(&db)
            .await
            .unwrap();
        let ended = served_rule_rows(&db, device, now).await.unwrap();
        assert!(ended.lock.is_none() && ended.rows.iter().all(|r| r.id > 0));
    }

    #[test]
    fn the_contract_is_identical_to_the_launchers_copy() {
        let ours = std::fs::read_to_string("testdata/lock_now.json").unwrap();
        let theirs = std::fs::read_to_string("../launcher/app/src/test/resources/lock_now.json")
            .expect("the launcher's copy");
        assert_eq!(ours, theirs);
    }
}
