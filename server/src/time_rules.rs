//! Named time rules, the daily screen-time budget, parent lifts and the location policy (handy
//! step 6, docs/design/06-time-rules.md in the handy workspace). The phone evaluates the rules
//! (kids-launcher-mdm `timerules/`); this module stores, validates and serves them, and converts
//! the old weekday/weekend/bedtime schedule into rules once ([migrate_legacy]).
//!
//! Wire format of a rule's days: 7 entries, Monday first, each `{"start": m, "end": m}` (minutes
//! 0-1439, the phone's local time) or `null` (not active that day). `start < end` = that day,
//! `start > end` = until `end` the next day, `start == end` = 24 h from `start`.

use serde::{Deserialize, Serialize};
use sqlx::SqlitePool;

/// The rule kinds; the launcher shows school > bedtime > custom when several are active.
pub const RULE_KINDS: [&str; 3] = ["school", "bedtime", "custom"];
/// `device_policy.location_mode` values.
pub const LOCATION_MODES: [&str; 3] = ["off", "on_request", "interval"];
/// Allowed `location_interval_minutes`.
pub const LOCATION_INTERVALS: [i64; 6] = [10, 15, 30, 60, 120, 240];
/// Minutes a parent may lift a rule for, and add to today's screen time.
pub const RULE_LIFT_MINUTES: [i64; 4] = [15, 30, 60, 120];
pub const BUDGET_LIFT_MINUTES: [i64; 3] = [15, 30, 60];
/// How long a budget lift ("+30 min") is offered to the phone; it applies to the day the phone
/// first sees it, once.
pub const BUDGET_LIFT_DELIVERY_HOURS: i64 = 12;
pub const MAX_EXEMPT_APPS: usize = 50;
pub const MAX_RULE_NAME_CHARS: usize = 60;
pub const UNLIMITED_BUDGET_JSON: &str = "[null,null,null,null,null,null,null]";

/// One day's window, minutes since local midnight.
#[derive(Serialize, Deserialize, Clone, Copy, Debug, PartialEq, Eq)]
pub struct DayWindow {
    pub start: i64,
    pub end: i64,
}

/// `PolicyResponse.time_policy.rules[]`.
#[derive(Serialize, Clone, Debug, PartialEq, Eq)]
pub struct PolicyTimeRule {
    pub id: i64,
    pub name: String,
    pub kind: String,
    pub calls_allowed: bool,
    pub exempt_apps: Vec<String>,
    pub days: Vec<Option<DayWindow>>,
}

/// `PolicyResponse.time_policy.lifts[]`. `rule_id` null with target "rule" = every rule.
#[derive(Serialize, Clone, Debug, PartialEq, Eq)]
pub struct PolicyLift {
    pub id: i64,
    pub target: String,
    pub rule_id: Option<i64>,
    pub minutes: i64,
    pub expires_at_ms: i64,
}

/// `PolicyResponse.time_policy` - always sent.
#[derive(Serialize, Clone, Debug, PartialEq, Eq)]
pub struct TimePolicy {
    pub rules: Vec<PolicyTimeRule>,
    /// 7 entries, Monday first; null = unlimited.
    pub daily_budget_minutes: Vec<Option<i64>>,
    pub lifts: Vec<PolicyLift>,
}

/// `PolicyResponse.location_policy` - always sent.
#[derive(Serialize, Clone, Debug, PartialEq, Eq)]
pub struct LocationPolicy {
    pub mode: String,
    pub interval_minutes: i64,
}

/// A `time_rules` row.
#[derive(sqlx::FromRow, Clone, Debug)]
pub struct TimeRuleRow {
    pub id: i64,
    pub device_id: Option<i64>,
    pub name: String,
    pub kind: String,
    pub calls_allowed: bool,
    pub exempt_apps_json: String,
    pub days_json: String,
}

impl TimeRuleRow {
    /// The wire form; an unreadable stored value is an error (the policy endpoint answers 500,
    /// never a rule-less default).
    pub fn to_policy(&self) -> Result<PolicyTimeRule, String> {
        if !RULE_KINDS.contains(&self.kind.as_str()) {
            return Err(format!("rule {}: unknown kind {:?}", self.id, self.kind));
        }
        Ok(PolicyTimeRule {
            id: self.id,
            name: self.name.clone(),
            kind: self.kind.clone(),
            calls_allowed: self.calls_allowed,
            exempt_apps: parse_exempt_apps(&self.exempt_apps_json)
                .map_err(|e| format!("rule {}: {e}", self.id))?,
            days: parse_days(&self.days_json).map_err(|e| format!("rule {}: {e}", self.id))?,
        })
    }
}

/// A `time_lifts` row.
#[derive(sqlx::FromRow, Clone, Debug)]
pub struct TimeLiftRow {
    pub id: i64,
    pub target: String,
    pub rule_name: Option<String>,
    pub minutes: i64,
    pub created_at: String,
    pub expires_at: String,
    pub created_by: Option<String>,
    pub ended_early_at: Option<String>,
}

pub fn valid_minute(m: i64) -> bool {
    (0..1440).contains(&m)
}

/// 7 entries, each null or a window with both ends in 0..1440.
pub fn parse_days(json: &str) -> Result<Vec<Option<DayWindow>>, String> {
    let days: Vec<Option<DayWindow>> =
        serde_json::from_str(json).map_err(|e| format!("days_json: {e}"))?;
    if days.len() != 7 {
        return Err(format!("days_json has {} entries, not 7", days.len()));
    }
    if days
        .iter()
        .flatten()
        .any(|w| !valid_minute(w.start) || !valid_minute(w.end))
    {
        return Err("days_json has a minute outside 0-1439".to_string());
    }
    Ok(days)
}

/// 7 entries, each null (unlimited) or 0-1440 minutes.
pub fn parse_budget(json: &str) -> Result<Vec<Option<i64>>, String> {
    let budget: Vec<Option<i64>> =
        serde_json::from_str(json).map_err(|e| format!("daily_budget_json: {e}"))?;
    if budget.len() != 7 {
        return Err(format!(
            "daily_budget_json has {} entries, not 7",
            budget.len()
        ));
    }
    if budget.iter().flatten().any(|m| !(0..=1440).contains(m)) {
        return Err("daily_budget_json has minutes outside 0-1440".to_string());
    }
    Ok(budget)
}

pub fn parse_exempt_apps(json: &str) -> Result<Vec<String>, String> {
    let apps: Vec<String> =
        serde_json::from_str(json).map_err(|e| format!("exempt_apps_json: {e}"))?;
    if apps.iter().any(|p| !valid_package_name(p)) {
        return Err("exempt_apps_json has an invalid package name".to_string());
    }
    Ok(apps)
}

/// An Android package name: dot-separated segments of letters, digits and underscores, each
/// starting with a letter, at least two segments.
pub fn valid_package_name(name: &str) -> bool {
    if name.is_empty() || name.len() > 255 {
        return false;
    }
    let segments: Vec<&str> = name.split('.').collect();
    segments.len() >= 2
        && segments.iter().all(|s| {
            s.chars().next().is_some_and(|c| c.is_ascii_alphabetic())
                && s.chars().all(|c| c.is_ascii_alphanumeric() || c == '_')
        })
}

/// What a parent may lift: a time-limited rule lift or extra screen time.
pub fn valid_lift(target: &str, minutes: i64) -> bool {
    match target {
        "rule" => RULE_LIFT_MINUTES.contains(&minutes),
        "budget" => BUDGET_LIFT_MINUTES.contains(&minutes),
        _ => false,
    }
}

// ---------------------------------------------------------------------------------------------
// Converting the old schedule (weekday/weekend allowed windows + bedtime) into rules
// ---------------------------------------------------------------------------------------------

/// The pre-step-6 schedule columns (`global_schedule` / `device_policy`).
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct LegacySchedule {
    pub weekday_start: Option<i64>,
    pub weekday_end: Option<i64>,
    pub weekend_start: Option<i64>,
    pub weekend_end: Option<i64>,
    pub bedtime_start: Option<i64>,
    pub bedtime_end: Option<i64>,
}

/// A rule produced by [legacy_to_rules].
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct NewRule {
    pub name: String,
    pub kind: &'static str,
    pub calls_allowed: bool,
    pub days: Vec<Option<DayWindow>>,
}

fn legacy_pair(start: Option<i64>, end: Option<i64>) -> Option<(i64, i64)> {
    match (start, end) {
        (Some(s), Some(e)) if s != e && valid_minute(s) && valid_minute(e) => Some((s, e)),
        _ => None,
    }
}

impl LegacySchedule {
    /// The allowed window on day `d` (0 = Monday), if it restricts anything.
    fn allowed(&self, d: usize) -> Option<(i64, i64)> {
        if d < 5 {
            legacy_pair(self.weekday_start, self.weekday_end)
        } else {
            legacy_pair(self.weekend_start, self.weekend_end)
        }
    }
}

/// The same lock, minute for minute, as rules (design 06 "Migration"): bedtime becomes a bedtime
/// rule on all 7 days; the allowed windows become the locked spans between them - one rule when
/// every day has a non-wrapping window and each next-day start is <= this day's end (so no span
/// is longer than 24 h), otherwise a "morning" and an "evening" rule. Calls stay allowed (step 4).
pub fn legacy_to_rules(legacy: &LegacySchedule) -> Vec<NewRule> {
    let mut rules = Vec::new();
    if let Some((bs, be)) = legacy_pair(legacy.bedtime_start, legacy.bedtime_end) {
        rules.push(NewRule {
            name: "Bedtime".to_string(),
            kind: "bedtime",
            calls_allowed: true,
            days: vec![Some(DayWindow { start: bs, end: be }); 7],
        });
    }

    let allowed: Vec<Option<(i64, i64)>> = (0..7).map(|d| legacy.allowed(d)).collect();
    if allowed.iter().all(Option::is_none) {
        return rules;
    }
    let combinable = allowed
        .iter()
        .all(|w| w.is_some_and(|(start, end)| start < end))
        && (0..7).all(|d| {
            let (_, end) = allowed[d].unwrap();
            let (next_start, _) = allowed[(d + 1) % 7].unwrap();
            next_start <= end
        });
    if combinable {
        let days = (0..7)
            .map(|d| {
                let (_, end) = allowed[d].unwrap();
                let (next_start, _) = allowed[(d + 1) % 7].unwrap();
                Some(DayWindow {
                    start: end,
                    end: next_start,
                })
            })
            .collect();
        rules.push(NewRule {
            name: "Outside allowed hours".to_string(),
            kind: "custom",
            calls_allowed: true,
            days,
        });
        return rules;
    }

    let morning: Vec<Option<DayWindow>> = allowed
        .iter()
        .map(|w| match *w {
            Some((start, end)) if start < end && start > 0 => Some(DayWindow {
                start: 0,
                end: start,
            }),
            _ => None,
        })
        .collect();
    let evening: Vec<Option<DayWindow>> = allowed
        .iter()
        .map(|w| match *w {
            // Until midnight: start > end wraps to the next day's 00:00.
            Some((start, end)) if start < end => Some(DayWindow { start: end, end: 0 }),
            // A wrapping allowed window locks the gap in the middle of the day.
            Some((start, end)) => Some(DayWindow {
                start: end,
                end: start,
            }),
            None => None,
        })
        .collect();
    for (name, days) in [
        ("Outside allowed hours (morning)", morning),
        ("Outside allowed hours (evening)", evening),
    ] {
        if days.iter().any(Option::is_some) {
            rules.push(NewRule {
                name: name.to_string(),
                kind: "custom",
                calls_allowed: true,
                days,
            });
        }
    }
    rules
}

#[derive(sqlx::FromRow)]
struct LegacyRow {
    weekday_start_minutes: Option<i64>,
    weekday_end_minutes: Option<i64>,
    weekend_start_minutes: Option<i64>,
    weekend_end_minutes: Option<i64>,
    bedtime_start_minutes: Option<i64>,
    bedtime_end_minutes: Option<i64>,
}

impl LegacyRow {
    fn schedule(&self) -> LegacySchedule {
        LegacySchedule {
            weekday_start: self.weekday_start_minutes,
            weekday_end: self.weekday_end_minutes,
            weekend_start: self.weekend_start_minutes,
            weekend_end: self.weekend_end_minutes,
            bedtime_start: self.bedtime_start_minutes,
            bedtime_end: self.bedtime_end_minutes,
        }
    }
}

async fn insert_converted(
    tx: &mut sqlx::SqliteConnection,
    device_id: Option<i64>,
    rules: &[NewRule],
) -> Result<(), sqlx::Error> {
    for (order, rule) in rules.iter().enumerate() {
        let days = serde_json::to_string(&rule.days).expect("windows always serialize");
        sqlx::query(
            "INSERT INTO time_rules (device_id, name, kind, calls_allowed, exempt_apps_json, \
             days_json, sort_order) VALUES (?, ?, ?, ?, '[]', ?, ?)",
        )
        .bind(device_id)
        .bind(&rule.name)
        .bind(rule.kind)
        .bind(rule.calls_allowed)
        .bind(&days)
        .bind(order as i64)
        .execute(&mut *tx)
        .await?;
    }
    Ok(())
}

/// Converts every not-yet-converted schedule (the global one and each device's own) into rules,
/// one transaction per row, then marks it converted - so it runs once per row and a crash midway
/// redoes only the unfinished rows. The old columns are left as they are (still sent to older
/// launchers). Called at startup right after the SQL migrations.
pub async fn migrate_legacy(db: &SqlitePool) -> Result<(), sqlx::Error> {
    let global: Option<LegacyRow> = sqlx::query_as(
        "SELECT weekday_start_minutes, weekday_end_minutes, weekend_start_minutes, \
         weekend_end_minutes, bedtime_start_minutes, bedtime_end_minutes \
         FROM global_schedule WHERE id = 1 AND rules_migrated = 0",
    )
    .fetch_optional(db)
    .await?;
    if let Some(row) = global {
        let mut tx = db.begin().await?;
        insert_converted(&mut tx, None, &legacy_to_rules(&row.schedule())).await?;
        sqlx::query("UPDATE global_schedule SET rules_migrated = 1 WHERE id = 1")
            .execute(&mut *tx)
            .await?;
        tx.commit().await?;
    }

    let device_ids: Vec<i64> =
        sqlx::query_scalar("SELECT device_id FROM device_policy WHERE rules_migrated = 0")
            .fetch_all(db)
            .await?;
    for device_id in device_ids {
        let mut tx = db.begin().await?;
        let row: Option<LegacyRow> = sqlx::query_as(
            "SELECT weekday_start_minutes, weekday_end_minutes, weekend_start_minutes, \
             weekend_end_minutes, bedtime_start_minutes, bedtime_end_minutes \
             FROM device_policy WHERE device_id = ? AND rules_migrated = 0",
        )
        .bind(device_id)
        .fetch_optional(&mut *tx)
        .await?;
        if let Some(row) = row {
            insert_converted(&mut tx, Some(device_id), &legacy_to_rules(&row.schedule())).await?;
            sqlx::query("UPDATE device_policy SET rules_migrated = 1 WHERE device_id = ?")
                .bind(device_id)
                .execute(&mut *tx)
                .await?;
        }
        tx.commit().await?;
    }
    Ok(())
}

/// The rules in force for a device: its own when `custom` (the override switch), else the global
/// ones. Order: `sort_order`, then id.
pub async fn effective_rule_rows(
    db: &SqlitePool,
    device_id: i64,
    custom: bool,
) -> Result<Vec<TimeRuleRow>, sqlx::Error> {
    if custom {
        sqlx::query_as::<_, TimeRuleRow>(
            "SELECT * FROM time_rules WHERE device_id = ? ORDER BY sort_order, id",
        )
        .bind(device_id)
        .fetch_all(db)
        .await
    } else {
        sqlx::query_as::<_, TimeRuleRow>(
            "SELECT * FROM time_rules WHERE device_id IS NULL ORDER BY sort_order, id",
        )
        .fetch_all(db)
        .await
    }
}

/// Lifts the phone should still see: not ended early, not expired.
pub async fn deliverable_lifts(
    db: &SqlitePool,
    device_id: i64,
) -> Result<Vec<PolicyLift>, sqlx::Error> {
    let rows: Vec<(i64, String, Option<i64>, i64, i64)> = sqlx::query_as(
        "SELECT id, target, rule_id, minutes, CAST(strftime('%s', expires_at) AS INTEGER) * 1000 \
         FROM time_lifts WHERE device_id = ? AND ended_early_at IS NULL \
         AND expires_at > datetime('now') ORDER BY id",
    )
    .bind(device_id)
    .fetch_all(db)
    .await?;
    Ok(rows
        .into_iter()
        .map(|(id, target, rule_id, minutes, expires_at_ms)| PolicyLift {
            id,
            target,
            rule_id,
            minutes,
            expires_at_ms,
        })
        .collect())
}

/// Every device id (a global change nudges them all).
pub async fn all_device_ids(db: &SqlitePool) -> Result<Vec<i64>, sqlx::Error> {
    sqlx::query_scalar("SELECT id FROM devices")
        .fetch_all(db)
        .await
}

#[cfg(test)]
mod tests {
    use super::*;

    /// The pre-step-6 launcher's `KidModeEnforcer.evaluate`: locked at (day, minute)?
    fn old_locked(l: &LegacySchedule, day: usize, m: i64) -> bool {
        fn in_window(m: i64, s: i64, e: i64) -> bool {
            if s < e {
                m >= s && m < e
            } else {
                m >= s || m < e
            }
        }
        if let (Some(s), Some(e)) = (l.bedtime_start, l.bedtime_end)
            && s != e
            && in_window(m, s, e)
        {
            return true;
        }
        let (s, e) = if day < 5 {
            (l.weekday_start, l.weekday_end)
        } else {
            (l.weekend_start, l.weekend_end)
        };
        match (s, e) {
            (Some(s), Some(e)) if s != e => !in_window(m, s, e),
            _ => false,
        }
    }

    /// The new semantics (design 06 "Model"), as the launcher evaluates them.
    fn new_locked(rules: &[NewRule], day: usize, m: i64) -> bool {
        rules.iter().any(|r| {
            let today = r.days[day].is_some_and(|w| {
                if w.start < w.end {
                    m >= w.start && m < w.end
                } else {
                    m >= w.start
                }
            });
            let yesterday = r.days[(day + 6) % 7].is_some_and(|w| {
                if w.start < w.end {
                    false
                } else if w.start > w.end {
                    m < w.end
                } else {
                    m < w.start
                }
            });
            today || yesterday
        })
    }

    fn assert_equivalent(l: LegacySchedule) {
        let rules = legacy_to_rules(&l);
        for rule in &rules {
            assert_eq!(rule.days.len(), 7);
            for w in rule.days.iter().flatten() {
                assert!(valid_minute(w.start) && valid_minute(w.end), "{rule:?}");
            }
        }
        for day in 0..7 {
            for m in 0..1440 {
                assert_eq!(
                    old_locked(&l, day, m),
                    new_locked(&rules, day, m),
                    "{l:?} day {day} minute {m} -> {rules:?}"
                );
            }
        }
    }

    fn sched(
        wd: Option<(i64, i64)>,
        we: Option<(i64, i64)>,
        bed: Option<(i64, i64)>,
    ) -> LegacySchedule {
        LegacySchedule {
            weekday_start: wd.map(|w| w.0),
            weekday_end: wd.map(|w| w.1),
            weekend_start: we.map(|w| w.0),
            weekend_end: we.map(|w| w.1),
            bedtime_start: bed.map(|w| w.0),
            bedtime_end: bed.map(|w| w.1),
        }
    }

    #[test]
    fn typical_schedule_is_one_rule_plus_bedtime() {
        let l = sched(
            Some((7 * 60, 20 * 60)),
            Some((8 * 60, 21 * 60)),
            Some((21 * 60, 7 * 60)),
        );
        let rules = legacy_to_rules(&l);
        assert_eq!(rules.len(), 2);
        assert_eq!(rules[0].kind, "bedtime");
        assert_eq!(rules[1].name, "Outside allowed hours");
        // Friday locks from 20:00 until Saturday 08:00.
        assert_eq!(
            rules[1].days[4],
            Some(DayWindow {
                start: 1200,
                end: 480
            })
        );
        assert_equivalent(l);
    }

    #[test]
    fn conversions_lock_exactly_like_the_old_schedule() {
        let cases = [
            sched(None, None, None),
            sched(None, None, Some((21 * 60, 7 * 60))),
            sched(None, None, Some((13 * 60, 14 * 60))),
            sched(None, None, Some((600, 600))),
            sched(Some((7 * 60, 20 * 60)), None, None),
            sched(None, Some((9 * 60, 22 * 60)), None),
            sched(Some((7 * 60, 20 * 60)), Some((8 * 60, 21 * 60)), None),
            // Next day's start after this day's end: two rules.
            sched(Some((7 * 60, 9 * 60)), Some((21 * 60, 23 * 60)), None),
            // A wrapping allowed window.
            sched(Some((22 * 60, 2 * 60)), Some((8 * 60, 21 * 60)), None),
            // Allowed from midnight.
            sched(Some((0, 20 * 60)), Some((0, 23 * 60 + 59)), None),
            // Next start == this end: 24 h spans.
            sched(Some((8 * 60, 8 * 60 + 1)), Some((8 * 60 + 1, 9 * 60)), None),
            // Equal start/end = no restriction.
            sched(Some((480, 480)), Some((8 * 60, 21 * 60)), Some((1260, 420))),
            // Only one end set = no restriction.
            LegacySchedule {
                weekday_start: Some(420),
                ..Default::default()
            },
        ];
        for l in cases {
            assert_equivalent(l);
        }
    }

    #[test]
    fn nothing_set_gives_no_rules() {
        assert!(legacy_to_rules(&LegacySchedule::default()).is_empty());
    }

    #[test]
    fn days_and_budget_validation() {
        assert!(parse_days("[null,null,null,null,null,null,null]").is_ok());
        assert!(parse_days("[null,null]").is_err());
        assert!(parse_days(r#"[{"start":0,"end":1440},null,null,null,null,null,null]"#).is_err());
        assert!(parse_days("not json").is_err());
        assert!(parse_budget("[60,60,60,60,60,null,null]").is_ok());
        assert!(parse_budget("[60,60,60,60,60,null,-1]").is_err());
        assert!(parse_budget("[60]").is_err());
        assert!(valid_package_name("org.fossify.calendar"));
        assert!(!valid_package_name("calendar"));
        assert!(!valid_package_name("org..x"));
        assert!(!valid_package_name("org.1x"));
        assert!(parse_exempt_apps(r#"["a.b","bad name"]"#).is_err());
    }
}
