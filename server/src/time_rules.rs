//! Named time rules, the daily screen-time budget, parent lifts and the location policy (handy
//! step 6, docs/design/06-time-rules.md in the handy workspace). The phone evaluates the rules
//! (kids-launcher-mdm `timerules/`); this module stores, validates and serves them, and converts
//! the old weekday/weekend/bedtime schedule into rules once ([migrate_legacy]).
//!
//! Wire format of a rule's days: 7 entries, Monday first, each `{"start": m, "end": m}` (minutes
//! 0-1439, the phone's local time) or `null` (not active that day). `start < end` = that day,
//! `start > end` = until `end` the next day, `start == end` = 24 h from `start`.
//!
//! Design 66: a day rule starts and ends on the same day (the editor refuses an end before its
//! start; 23:59 is stored for "until midnight" and served as `end: 0`, [served_days]). Only a
//! phone's **Bedtime** - the one `bedtime = 1` row per phone, outside the rule sets - runs past
//! midnight, and [migrate_crossing] turns the rules stored before that into it, or splits them.

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
/// The last minute of a day: a window ending here runs until midnight (served `end: 0`).
pub const LAST_MINUTE: i64 = 1439;
/// Noon: a bedtime starts at or after it and ends before it (design 66 §3.3).
pub const NOON: i64 = 720;
/// The stored name of a phone's bedtime (the lift's `rule_name`, Pause a Rule's picker); the
/// phone gets an empty name and says "Leggetid" itself (design 66 §3.4).
pub const BEDTIME_NAME: &str = "Bedtime";

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

/// `PolicyResponse.location_policy` - always sent. `interval_minutes` is the served one (design
/// 64 step 2: `zones::served_interval`); `zones` always the key, `[]` unless Regularly with zones.
#[derive(Serialize, Clone, Debug, PartialEq)]
pub struct LocationPolicy {
    pub mode: String,
    pub interval_minutes: i64,
    pub zones: Vec<crate::zones::PolicyZone>,
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
    pub sort_order: i64,
    /// The phone's Bedtime (design 66): one per phone, never in a rule set.
    pub bedtime: bool,
}

impl TimeRuleRow {
    /// The wire form; an unreadable stored value is an error (the policy endpoint answers 500,
    /// never a rule-less default). The bedtime goes with an empty name, so the phone names it in
    /// its own language ("Leggetid", design 66 §3.4).
    pub fn to_policy(&self) -> Result<PolicyTimeRule, String> {
        if !RULE_KINDS.contains(&self.kind.as_str()) {
            return Err(format!("rule {}: unknown kind {:?}", self.id, self.kind));
        }
        Ok(PolicyTimeRule {
            id: self.id,
            name: if self.bedtime {
                String::new()
            } else {
                self.name.clone()
            },
            kind: self.kind.clone(),
            calls_allowed: self.calls_allowed,
            exempt_apps: parse_exempt_apps(&self.exempt_apps_json)
                .map_err(|e| format!("rule {}: {e}", self.id))?,
            days: served_days(
                &parse_days(&self.days_json).map_err(|e| format!("rule {}: {e}", self.id))?,
            ),
        })
    }
}

/// The days as the phone gets them (design 66 §3.2): a window ending at 23:59 runs until
/// midnight, `end: 0` - `{s, 23:59}` is `{s, 0}`, `{00:00, 23:59}` the whole day `{0, 0}`, and
/// `{23:59, 23:59}` one minute `{23:59, 0}`. The phone's end is exclusive, so it would otherwise
/// leave every all-day rule open from 23:59 to 00:00. Used for the policy and the badge alike.
pub fn served_days(days: &[Option<DayWindow>]) -> Vec<Option<DayWindow>> {
    days.iter()
        .map(|w| {
            w.map(|w| DayWindow {
                start: w.start,
                end: if w.end == LAST_MINUTE { 0 } else { w.end },
            })
        })
        .collect()
}

/// A stored window that runs past midnight: an end at or before its start, other than 23:59
/// ("until midnight"; a stored `{23:59, 23:59}` is one minute, never a crossing).
pub fn crosses(w: &DayWindow) -> bool {
    w.start >= w.end && w.end != LAST_MINUTE
}

/// A window a bedtime may have: from 12:00-23:59 to 00:00-11:59 (design 66 §3.3).
pub fn bedtime_window(w: &DayWindow) -> bool {
    (NOON..=LAST_MINUTE).contains(&w.start) && (0..NOON).contains(&w.end)
}

/// A bedtime's two rows (design 66 §2): the Sun-Thu nights on days 6, 0-3 and the Fri-Sat
/// nights - the nights before Saturday and Sunday - on days 4 and 5. `None` = no bedtime those
/// nights.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Nights {
    pub school: Option<DayWindow>,
    pub free: Option<DayWindow>,
}

impl Nights {
    /// The 7 days, Monday first.
    pub fn days(&self) -> Vec<Option<DayWindow>> {
        (0..7)
            .map(|d| {
                if d == 4 || d == 5 {
                    self.free
                } else {
                    self.school
                }
            })
            .collect()
    }

    /// The two rows of 7 stored days; `None` when they aren't two rows (days 6 and 0-3, or 4
    /// and 5, differ).
    pub fn from_days(days: &[Option<DayWindow>]) -> Option<Nights> {
        if days.len() != 7 {
            return None;
        }
        let nights = Nights {
            school: days[6],
            free: days[4],
        };
        (nights.days() == days).then_some(nights)
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

// ---------------------------------------------------------------------------------------------
// Rules that cross midnight become the phone's Bedtime, or are split at midnight (design 66 §3.5)
// ---------------------------------------------------------------------------------------------

type Days = Vec<Option<DayWindow>>;

/// Step 1: a window ending at 00:00 ends at 23:59 instead, which is served the same (`end: 0`):
/// `{s, 0}` runs until midnight, `{0, 0}` is the whole day.
fn normalised(days: &[Option<DayWindow>]) -> Days {
    days.iter()
        .map(|w| {
            w.map(|w| DayWindow {
                start: w.start,
                end: if w.end == 0 { LAST_MINUTE } else { w.end },
            })
        })
        .collect()
}

/// Step 2: kind bedtime or custom (never school), every window from the evening to the morning,
/// the same on the Sun-Thu nights and on the Fri-Sat nights, not empty.
fn bedtime_shaped(kind: &str, days: &[Option<DayWindow>]) -> bool {
    matches!(kind, "bedtime" | "custom")
        && days.iter().any(Option::is_some)
        && days.iter().flatten().all(bedtime_window)
        && Nights::from_days(days).is_some()
}

/// Step 4, exact: the evening part keeps every day window and runs each crossing day D until
/// midnight; the morning part has `{0, end}` on D+1. `None` when nothing crosses. Takes
/// [normalised] days (an end of 00:00 would make a 24 h morning).
fn split_days(days: &[Option<DayWindow>]) -> Option<(Days, Days)> {
    let mut evening = days.to_vec();
    let mut morning = vec![None; days.len()];
    let mut split = false;
    for (d, w) in days.iter().enumerate() {
        if let Some(w) = w.filter(crosses) {
            evening[d] = Some(DayWindow {
                start: w.start,
                end: LAST_MINUTE,
            });
            morning[(d + 1) % days.len()] = Some(DayWindow {
                start: 0,
                end: w.end,
            });
            split = true;
        }
    }
    split.then_some((evening, morning))
}

/// "<name> (morning)", the name cut so it fits in [MAX_RULE_NAME_CHARS].
fn morning_name(name: &str) -> String {
    const SUFFIX: &str = " (morning)";
    let keep = MAX_RULE_NAME_CHARS - SUFFIX.chars().count();
    format!("{}{SUFFIX}", name.chars().take(keep).collect::<String>())
}

/// The index of the rule that becomes a bedtime: the first bedtime-shaped one, kind bedtime
/// before custom, then in the set's order.
fn first_bedtime(rules: &[(TimeRuleRow, Days)]) -> Option<usize> {
    rules
        .iter()
        .enumerate()
        .filter(|(_, (row, days))| bedtime_shaped(&row.kind, days))
        .min_by_key(|(_, (row, _))| row.kind != "bedtime")
        .map(|(i, _)| i)
}

/// A rule set's rows (`bedtime = 0`) whose days and apps read, with their days [normalised];
/// a row that doesn't read is left as it is (the startup's first pass warns about it).
async fn readable_rules(
    conn: &mut sqlx::SqliteConnection,
    device_id: Option<i64>,
) -> Result<Vec<(TimeRuleRow, Days)>, sqlx::Error> {
    let rows = sqlx::query_as::<_, TimeRuleRow>(
        "SELECT * FROM time_rules WHERE device_id IS ? AND bedtime = 0 ORDER BY sort_order, id",
    )
    .bind(device_id)
    .fetch_all(&mut *conn)
    .await?;
    Ok(rows
        .into_iter()
        .filter_map(|row| {
            let days = parse_days(&row.days_json).ok()?;
            parse_exempt_apps(&row.exempt_apps_json).ok()?;
            Some((row, normalised(&days)))
        })
        .collect())
}

async fn has_bedtime(
    conn: &mut sqlx::SqliteConnection,
    device_id: i64,
) -> Result<bool, sqlx::Error> {
    sqlx::query_scalar(
        "SELECT EXISTS (SELECT 1 FROM time_rules WHERE device_id = ? AND bedtime = 1)",
    )
    .bind(device_id)
    .fetch_one(&mut *conn)
    .await
}

/// A conversion's line in the security log, in its unit's transaction (no actor).
async fn log_conversion(
    conn: &mut sqlx::SqliteConnection,
    event: &str,
    device_id: Option<i64>,
    what: &str,
) -> Result<(), sqlx::Error> {
    let scope = device_id.map_or("global".to_string(), |id| format!("device {id}"));
    sqlx::query("INSERT INTO security_events (event_type, detail) VALUES (?, ?)")
        .bind(event)
        .bind(format!("{scope}: {what}"))
        .execute(&mut *conn)
        .await?;
    Ok(())
}

/// Step 4 for one rule: the row keeps its id and its evening part, a new "(morning)" row takes
/// the mornings, with the same kind, calls and apps. Nothing when nothing crosses.
async fn split_rule(
    conn: &mut sqlx::SqliteConnection,
    row: &TimeRuleRow,
    days: &[Option<DayWindow>],
) -> Result<(), sqlx::Error> {
    let Some((evening, morning)) = split_days(days) else {
        return Ok(());
    };
    sqlx::query("UPDATE time_rules SET days_json = ? WHERE id = ?")
        .bind(serde_json::to_string(&evening).expect("windows serialize"))
        .bind(row.id)
        .execute(&mut *conn)
        .await?;
    sqlx::query(
        "INSERT INTO time_rules (device_id, name, kind, calls_allowed, exempt_apps_json, \
         days_json, sort_order) VALUES (?, ?, ?, ?, ?, ?, ?)",
    )
    .bind(row.device_id)
    .bind(morning_name(&row.name))
    .bind(&row.kind)
    .bind(row.calls_allowed)
    .bind(&row.exempt_apps_json)
    .bind(serde_json::to_string(&morning).expect("windows serialize"))
    .bind(row.sort_order)
    .execute(&mut *conn)
    .await?;
    log_conversion(conn, "time_rule_split", row.device_id, &row.name).await
}

/// Step 1 for every rule (`bedtime = 0`): an end of 00:00 becomes 23:59. A rule whose days or
/// apps don't read is skipped with a warning (it stays as it is; a save in the editor fixes it).
async fn normalise_all(db: &SqlitePool) -> Result<(), sqlx::Error> {
    let rows = sqlx::query_as::<_, TimeRuleRow>("SELECT * FROM time_rules WHERE bedtime = 0")
        .fetch_all(db)
        .await?;
    for row in rows {
        let days = match (
            parse_days(&row.days_json),
            parse_exempt_apps(&row.exempt_apps_json),
        ) {
            (Ok(days), Ok(_)) => days,
            (Err(err), _) | (_, Err(err)) => {
                tracing::warn!(rule = row.id, %err, "a time rule that doesn't read is left as it is");
                continue;
            }
        };
        let days_normalised = normalised(&days);
        if days_normalised != days {
            sqlx::query("UPDATE time_rules SET days_json = ? WHERE id = ?")
                .bind(serde_json::to_string(&days_normalised).expect("windows serialize"))
                .bind(row.id)
                .execute(db)
                .await?;
        }
    }
    Ok(())
}

/// Step 3 for one phone, one transaction: on Own rules, its first bedtime-shaped rule becomes its
/// bedtime in place (id, calls and apps kept) unless it has one; every other own rule that still
/// crosses is split.
async fn convert_phone(db: &SqlitePool, device_id: i64, own: bool) -> Result<(), sqlx::Error> {
    let mut tx = db.begin().await?;
    let mut rules = readable_rules(&mut tx, Some(device_id)).await?;
    if own
        && !has_bedtime(&mut tx, device_id).await?
        && let Some(i) = first_bedtime(&rules)
    {
        let (row, _) = rules.remove(i);
        sqlx::query(
            "UPDATE time_rules SET bedtime = 1, kind = 'bedtime', name = ? WHERE id = ? \
             AND NOT EXISTS (SELECT 1 FROM time_rules WHERE device_id = ? AND bedtime = 1)",
        )
        .bind(BEDTIME_NAME)
        .bind(row.id)
        .bind(device_id)
        .execute(&mut *tx)
        .await?;
        log_conversion(&mut tx, "time_rule_to_bedtime", Some(device_id), &row.name).await?;
    }
    for (row, days) in &rules {
        split_rule(&mut tx, row, days).await?;
    }
    tx.commit().await
}

/// Step 3 for everyone's first bedtime-shaped rule, one transaction: copied into every phone that
/// follows everyone's rules as its bedtime (those phones' lifts on it repointed to the copy) and
/// deleted - or split, when no phone follows them or one already has a bedtime (no phone loses a
/// lock).
async fn convert_global_bedtime(
    db: &SqlitePool,
    row: &TimeRuleRow,
    days: &[Option<DayWindow>],
) -> Result<(), sqlx::Error> {
    let mut tx = db.begin().await?;
    let followers: Vec<(i64, bool)> = sqlx::query_as(
        "SELECT d.id, EXISTS (SELECT 1 FROM time_rules r WHERE r.device_id = d.id \
         AND r.bedtime = 1) FROM devices d LEFT JOIN device_policy p ON p.device_id = d.id \
         WHERE COALESCE(p.custom_schedule_enabled, 0) = 0 ORDER BY d.id",
    )
    .fetch_all(&mut *tx)
    .await?;
    if followers.is_empty() || followers.iter().any(|(_, has)| *has) {
        split_rule(&mut tx, row, days).await?;
        return tx.commit().await;
    }
    for (device_id, _) in &followers {
        if let Some(copy) = copy_as_bedtime(&mut tx, row.id, *device_id).await? {
            sqlx::query("UPDATE time_lifts SET rule_id = ? WHERE device_id = ? AND rule_id = ?")
                .bind(copy)
                .bind(device_id)
                .bind(row.id)
                .execute(&mut *tx)
                .await?;
        }
    }
    sqlx::query("DELETE FROM time_rules WHERE id = ?")
        .bind(row.id)
        .execute(&mut *tx)
        .await?;
    let to: Vec<String> = followers.iter().map(|(id, _)| id.to_string()).collect();
    let what = format!("{}, now the bedtime of device {}", row.name, to.join(", "));
    log_conversion(&mut tx, "time_rule_to_bedtime", None, &what).await?;
    tx.commit().await
}

/// Everyone's rule `rule_id` copied as the phone's bedtime, unless it has one: the copy's id.
async fn copy_as_bedtime(
    conn: &mut sqlx::SqliteConnection,
    rule_id: i64,
    device_id: i64,
) -> Result<Option<i64>, sqlx::Error> {
    sqlx::query_scalar(
        "INSERT INTO time_rules (device_id, name, kind, calls_allowed, exempt_apps_json, \
         days_json, bedtime) SELECT ?, ?, 'bedtime', calls_allowed, exempt_apps_json, \
         days_json, 1 FROM time_rules WHERE id = ? \
         AND NOT EXISTS (SELECT 1 FROM time_rules WHERE device_id = ? AND bedtime = 1) \
         RETURNING id",
    )
    .bind(device_id)
    .bind(BEDTIME_NAME)
    .bind(rule_id)
    .bind(device_id)
    .fetch_optional(&mut *conn)
    .await
}

/// Turns the rules that cross midnight into the phones' bedtimes, or splits them at midnight
/// (design 66 §3.5). Called from `connect_db` right after [migrate_legacy], before the server
/// listens, at every start: there is no marker, and once done it finds nothing (a restored older
/// backup is converted at its first start). Each unit - a phone, one of everyone's rules - is one
/// transaction, so a stop between two leaves whole units done; a phone that has a bedtime never
/// gets a second (the unique index is never hit). Only a database error is returned.
pub async fn migrate_crossing(db: &SqlitePool) -> Result<(), sqlx::Error> {
    normalise_all(db).await?;
    let phones: Vec<(i64, bool)> = sqlx::query_as(
        "SELECT d.id, COALESCE(p.custom_schedule_enabled, 0) FROM devices d \
         LEFT JOIN device_policy p ON p.device_id = d.id ORDER BY d.id",
    )
    .fetch_all(db)
    .await?;
    for (device_id, own) in phones {
        convert_phone(db, device_id, own).await?;
    }
    let mut everyone = readable_rules(&mut *db.acquire().await?, None).await?;
    if let Some(i) = first_bedtime(&everyone) {
        let (row, days) = everyone.remove(i);
        convert_global_bedtime(db, &row, &days).await?;
    }
    for (row, days) in &everyone {
        let mut tx = db.begin().await?;
        split_rule(&mut tx, row, days).await?;
        tx.commit().await?;
    }
    Ok(())
}

/// A rule set alone, without any bedtime: everyone's (`None`) or a phone's own. Order:
/// `sort_order`, then id.
pub async fn rule_set_rows(
    db: &SqlitePool,
    device_id: Option<i64>,
) -> Result<Vec<TimeRuleRow>, sqlx::Error> {
    sqlx::query_as::<_, TimeRuleRow>(
        "SELECT * FROM time_rules WHERE device_id IS ? AND bedtime = 0 ORDER BY sort_order, id",
    )
    .bind(device_id)
    .fetch_all(db)
    .await
}

/// The phone's bedtime row, if it has one.
pub async fn bedtime_row(
    db: &SqlitePool,
    device_id: i64,
) -> Result<Option<TimeRuleRow>, sqlx::Error> {
    sqlx::query_as::<_, TimeRuleRow>("SELECT * FROM time_rules WHERE device_id = ? AND bedtime = 1")
        .bind(device_id)
        .fetch_optional(db)
        .await
}

/// The rules in force for a device: its own set when `custom` (the override switch), else
/// everyone's, then the phone's bedtime - which the switch never adds or drops (design 66 §3.1).
pub async fn effective_rule_rows(
    db: &SqlitePool,
    device_id: i64,
    custom: bool,
) -> Result<Vec<TimeRuleRow>, sqlx::Error> {
    let mut rows = rule_set_rows(db, custom.then_some(device_id)).await?;
    rows.extend(bedtime_row(db, device_id).await?);
    Ok(rows)
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
        rules.iter().any(|r| covers(&r.days, day, m))
    }

    /// One rule's days (as the phone gets them) lock minute `m` of `day` (0 = Monday).
    fn covers(days: &[Option<DayWindow>], day: usize, m: i64) -> bool {
        let today = days[day].is_some_and(|w| {
            if w.start < w.end {
                m >= w.start && m < w.end
            } else {
                m >= w.start
            }
        });
        let yesterday = days[(day + 6) % 7].is_some_and(|w| {
            if w.start < w.end {
                false
            } else if w.start > w.end {
                m < w.end
            } else {
                m < w.start
            }
        });
        today || yesterday
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

    // Design 66: served windows, and the rules that cross midnight converted at startup.

    fn w(start: i64, end: i64) -> Option<DayWindow> {
        Some(DayWindow { start, end })
    }

    fn on(days: &[usize], window: Option<DayWindow>) -> Days {
        (0..7)
            .map(|d| if days.contains(&d) { window } else { None })
            .collect()
    }

    #[test]
    fn a_window_to_23_59_is_served_until_midnight() {
        let stored = [
            w(480, 1439),
            w(0, 1439),
            w(1439, 1439),
            w(1260, 420),
            w(495, 840),
            None,
            w(0, 0),
        ];
        assert_eq!(
            served_days(&stored),
            [
                w(480, 0),
                w(0, 0),
                w(1439, 0),
                w(1260, 420),
                w(495, 840),
                None,
                w(0, 0)
            ]
        );
    }

    #[test]
    fn the_two_rows_of_nights() {
        let nights = Nights {
            school: w(1260, 420),
            free: w(1350, 540),
        };
        let days = nights.days();
        // Friday and Saturday evenings are the nights before a free day.
        assert_eq!(days[4], w(1350, 540));
        assert_eq!(days[5], w(1350, 540));
        assert_eq!(days[6], w(1260, 420));
        assert_eq!(Nights::from_days(&days), Some(nights));
        let mut odd = days.clone();
        odd[2] = None;
        assert_eq!(Nights::from_days(&odd), None);
        assert_eq!(Nights::from_days(&days[..6]), None);
        assert_eq!(
            morning_name(&"x".repeat(60)).chars().count(),
            MAX_RULE_NAME_CHARS
        );
        assert_eq!(morning_name("Natt"), "Natt (morning)");
    }

    type Served = Vec<(Days, bool, Vec<String>)>;

    /// What the phone does at each minute of a week (Monday 00:00 first) with these rules, as
    /// `decideTimeLock` without the title: unlocked, or locked with calls allowed and the apps
    /// left usable.
    fn week(rules: &Served) -> Vec<Option<(bool, Vec<String>)>> {
        let mut minutes = Vec::with_capacity(7 * 1440);
        for day in 0..7 {
            for m in 0..1440 {
                let active: Vec<_> = rules.iter().filter(|r| covers(&r.0, day, m)).collect();
                minutes.push((!active.is_empty()).then(|| {
                    let mut apps: Vec<String> = active[0]
                        .2
                        .iter()
                        .filter(|a| active.iter().all(|r| r.2.contains(a)))
                        .cloned()
                        .collect();
                    apps.sort();
                    (active.iter().all(|r| r.1), apps)
                }));
            }
        }
        minutes
    }

    /// The minutes where two weeks differ, as (day, minute).
    fn differences<T: PartialEq>(a: &[T], b: &[T]) -> Vec<(usize, usize)> {
        (0..a.len())
            .filter(|&i| a[i] != b[i])
            .map(|i| (i / 1440, i % 1440))
            .collect()
    }

    /// A migrated database and two phones: the first follows everyone's rules, the second its own.
    async fn two_phones() -> (tempfile::TempDir, SqlitePool, i64, i64) {
        let dir = tempfile::tempdir().unwrap();
        let url = format!("sqlite://{}", dir.path().join("t.db").display());
        let db = crate::connect_db(&url).await;
        let mut ids = Vec::new();
        for (name, own) in [("Anna", false), ("Emil", true)] {
            let id: i64 = sqlx::query_scalar("INSERT INTO devices (name) VALUES (?) RETURNING id")
                .bind(name)
                .fetch_one(&db)
                .await
                .unwrap();
            sqlx::query(
                "INSERT INTO device_policy (device_id, kiosk_desired, custom_schedule_enabled) \
                 VALUES (?, 1, ?)",
            )
            .bind(id)
            .bind(own)
            .execute(&db)
            .await
            .unwrap();
            ids.push(id);
        }
        (dir, db, ids[0], ids[1])
    }

    /// A stored rule: calls off for school, the apps by kind (so a split's parts must keep them).
    async fn add(db: &SqlitePool, device: Option<i64>, kind: &str, days: &Days) -> i64 {
        let apps = match kind {
            "school" => "[]",
            "custom" => r#"["org.fossify.clock"]"#,
            _ => r#"["org.fossify.clock","me.vibb.music"]"#,
        };
        sqlx::query_scalar(
            "INSERT INTO time_rules (device_id, name, kind, calls_allowed, exempt_apps_json, \
             days_json, sort_order) VALUES (?, ?, ?, ?, ?, ?, \
             (SELECT COUNT(*) FROM time_rules)) RETURNING id",
        )
        .bind(device)
        .bind(format!("Rule {kind}"))
        .bind(kind)
        .bind(kind != "school")
        .bind(apps)
        .bind(serde_json::to_string(days).unwrap())
        .fetch_one(db)
        .await
        .unwrap()
    }

    /// The phone's rules as the server served them before design 66: its set's stored days.
    async fn served_before(db: &SqlitePool, device: i64, own: bool) -> Served {
        let rows: Vec<(String, bool, String)> = sqlx::query_as(
            "SELECT days_json, calls_allowed, exempt_apps_json FROM time_rules \
             WHERE device_id IS ? ORDER BY sort_order, id",
        )
        .bind(own.then_some(device))
        .fetch_all(db)
        .await
        .unwrap();
        rows.into_iter()
            .map(|(days, calls, apps)| {
                (
                    parse_days(&days).unwrap(),
                    calls,
                    parse_exempt_apps(&apps).unwrap(),
                )
            })
            .collect()
    }

    /// ... and as the policy serves them now.
    async fn served_after(db: &SqlitePool, device: i64, own: bool) -> Served {
        effective_rule_rows(db, device, own)
            .await
            .unwrap()
            .iter()
            .map(|row| {
                let rule = row.to_policy().unwrap();
                (rule.days, rule.calls_allowed, rule.exempt_apps)
            })
            .collect()
    }

    type StoredRow = (i64, Option<i64>, String, String, bool, String, String, bool);

    async fn stored(db: &SqlitePool) -> Vec<StoredRow> {
        sqlx::query_as(
            "SELECT id, device_id, name, kind, calls_allowed, exempt_apps_json, days_json, \
             bedtime FROM time_rules ORDER BY id",
        )
        .fetch_all(db)
        .await
        .unwrap()
    }

    async fn count(db: &SqlitePool, sql: &str) -> i64 {
        sqlx::query_scalar(sql).fetch_one(db).await.unwrap()
    }

    /// §3.5's proof: for each rule set, what both phones do at every minute of a week is the same
    /// before and after [migrate_crossing] (locked, calls, usable apps), the rows are the expected
    /// bedtimes and splits, and a second run changes nothing. The one exception is a 24 h window
    /// at 23:59, read as "23:59 to midnight".
    #[tokio::test]
    async fn crossing_rules_convert_exactly() {
        let night = w(1260, 420);
        let school_nights = [6, 0, 1, 2, 3];
        let mut two_rows = on(&school_nights, night);
        two_rows[4] = w(1350, 540);
        two_rows[5] = w(1350, 540);
        // (what, everyone's rules, the second phone's own, bedtime rows, "(morning)" rows)
        type Rules = Vec<(&'static str, Days)>;
        let cases: Vec<(&str, Rules, Rules, i64, i64)> = vec![
            (
                "everyone's bedtime every night",
                vec![("bedtime", vec![night; 7])],
                vec![],
                1,
                0,
            ),
            (
                "Sun-Thu and Fri-Sat nights, own",
                vec![],
                vec![("custom", two_rows.clone())],
                1,
                0,
            ),
            (
                "a legacy (evening) to 00:00",
                vec![("custom", vec![w(1200, 0); 7])],
                vec![("custom", on(&[0, 2], w(1290, 0)))],
                0,
                0,
            ),
            (
                "24 h on Saturday",
                vec![],
                vec![("custom", on(&[5], w(0, 0)))],
                0,
                0,
            ),
            (
                "a school rule 22:00-02:00",
                vec![("school", on(&[0, 1, 2, 3, 6], w(1320, 120)))],
                vec![("school", on(&[6], w(1320, 120)))],
                0,
                2,
            ),
            (
                "odd days",
                vec![],
                vec![("custom", on(&[0, 2], night))],
                0,
                1,
            ),
            (
                "two bedtime-shaped rules",
                vec![
                    ("custom", vec![w(1290, 390); 7]),
                    ("bedtime", two_rows.clone()),
                ],
                vec![("bedtime", vec![night; 7]), ("custom", two_rows.clone())],
                2,
                2,
            ),
            (
                "a night from 23:59",
                vec![],
                vec![("school", on(&[1], w(1439, 420)))],
                0,
                1,
            ),
            (
                "24 h from 10:00, and a bedtime plus a day rule",
                vec![
                    ("custom", on(&[3], w(600, 600))),
                    ("bedtime", vec![night; 7]),
                    ("custom", on(&[0, 1, 2, 3, 4], w(1200, 1290))),
                ],
                vec![],
                1,
                1,
            ),
        ];
        for (what, everyone, own, bedtimes, mornings) in cases {
            let (_dir, db, anna, emil) = two_phones().await;
            for (kind, days) in &everyone {
                add(&db, None, kind, days).await;
            }
            for (kind, days) in &own {
                add(&db, Some(emil), kind, days).await;
            }
            let before = [
                week(&served_before(&db, anna, false).await),
                week(&served_before(&db, emil, true).await),
            ];
            migrate_crossing(&db).await.unwrap();
            let after = [
                week(&served_after(&db, anna, false).await),
                week(&served_after(&db, emil, true).await),
            ];
            for phone in 0..2 {
                assert_eq!(
                    differences(&before[phone], &after[phone]),
                    Vec::<(usize, usize)>::new(),
                    "{what}: phone {phone} (day, minute)"
                );
            }
            assert_eq!(
                count(&db, "SELECT COUNT(*) FROM time_rules WHERE bedtime = 1").await,
                bedtimes,
                "{what}: {:?}",
                stored(&db).await
            );
            assert_eq!(
                count(
                    &db,
                    "SELECT COUNT(*) FROM time_rules WHERE name LIKE '% (morning)'"
                )
                .await,
                mornings,
                "{what}: {:?}",
                stored(&db).await
            );
            // Nothing in a rule set crosses any more, and only a bedtime does.
            for (_, _, _, _, _, _, days, bedtime) in stored(&db).await {
                let crossing = parse_days(&days).unwrap().iter().flatten().any(crosses);
                assert_eq!(crossing, bedtime, "{what}: {days}");
            }
            let once = stored(&db).await;
            let logged = count(&db, "SELECT COUNT(*) FROM security_events").await;
            migrate_crossing(&db).await.unwrap();
            assert_eq!(stored(&db).await, once, "{what}: a second run");
            assert_eq!(
                count(&db, "SELECT COUNT(*) FROM security_events").await,
                logged
            );
        }

        // A 24 h window at 23:59 can't be told from "23:59 to midnight": the one difference.
        let (_dir, db, _, emil) = two_phones().await;
        add(&db, Some(emil), "custom", &on(&[4], w(1439, 1439))).await;
        let before = week(&served_before(&db, emil, true).await);
        migrate_crossing(&db).await.unwrap();
        let after = week(&served_after(&db, emil, true).await);
        let saturday: Vec<(usize, usize)> = (0..1439).map(|m| (5, m)).collect();
        assert_eq!(differences(&before, &after), saturday);
        assert!(
            after[4 * 1440 + 1439].is_some(),
            "Friday 23:59 stays locked"
        );
    }

    /// The same rules for [a_stopped_conversion_ends_in_the_same_rows]: everyone's bedtime, a
    /// second bedtime-shaped rule and a crossing school rule, with a pause on the bedtime; the
    /// first phone's own crossing rule (not in force), the second phone's own bedtime-shaped rule
    /// and a crossing school rule.
    async fn a_house() -> (tempfile::TempDir, SqlitePool, i64) {
        let (dir, db, anna, emil) = two_phones().await;
        let bedtime = add(&db, None, "bedtime", &vec![w(1260, 420); 7]).await;
        add(&db, None, "custom", &vec![w(1320, 360); 7]).await;
        add(&db, None, "school", &on(&[0], w(1320, 60))).await;
        sqlx::query(
            "INSERT INTO time_lifts (device_id, target, rule_id, rule_name, minutes, expires_at) \
             VALUES (?, 'rule', ?, 'Rule bedtime', 30, datetime('now', '+30 minutes'))",
        )
        .bind(anna)
        .bind(bedtime)
        .execute(&db)
        .await
        .unwrap();
        add(&db, Some(anna), "custom", &on(&[2], w(1200, 300))).await;
        add(&db, Some(emil), "custom", &vec![w(1290, 390); 7]).await;
        add(&db, Some(emil), "school", &on(&[6], w(1380, 30))).await;
        (dir, db, anna)
    }

    /// Crash-safe (§3.5): a run that stopped at an awkward point, then ran again at the next
    /// start, ends in the same rows, lifts and log lines as one run. The stops: inside everyone's
    /// bedtime's transaction right after the follower's copy was inserted (before its lift was
    /// repointed and the global row deleted - the transaction never commits), and after that
    /// transaction committed with the second bedtime-shaped rule still waiting (the next run takes
    /// it as "first", but its follower now has a bedtime, so it is split as in one run).
    #[tokio::test]
    async fn a_stopped_conversion_ends_in_the_same_rows() {
        async fn state(db: &SqlitePool) -> (Vec<StoredRow>, Vec<(i64, Option<i64>)>, i64) {
            let lifts = sqlx::query_as("SELECT id, rule_id FROM time_lifts ORDER BY id")
                .fetch_all(db)
                .await
                .unwrap();
            let events = "SELECT COUNT(*) FROM security_events WHERE event_type LIKE 'time_rule_%'";
            (stored(db).await, lifts, count(db, events).await)
        }
        /// Everything before everyone's rules: the phones' units.
        async fn phones_done(db: &SqlitePool) -> (i64, Vec<(TimeRuleRow, Days)>) {
            normalise_all(db).await.unwrap();
            let phones: Vec<i64> = sqlx::query_scalar("SELECT id FROM devices ORDER BY id")
                .fetch_all(db)
                .await
                .unwrap();
            for (i, id) in phones.iter().enumerate() {
                convert_phone(db, *id, i == 1).await.unwrap();
            }
            let everyone = readable_rules(&mut db.acquire().await.unwrap(), None)
                .await
                .unwrap();
            (phones[0], everyone)
        }
        let (_one, straight, _) = a_house().await;
        migrate_crossing(&straight).await.unwrap();
        let expected = state(&straight).await;
        assert_eq!(
            count(
                &straight,
                "SELECT COUNT(*) FROM time_rules WHERE bedtime = 1"
            )
            .await,
            2
        );
        assert_eq!(
            count(
                &straight,
                "SELECT COUNT(*) FROM time_rules WHERE device_id IS NULL"
            )
            .await,
            4,
            "the second bedtime-shaped rule and the school rule, each split"
        );

        // Stopped inside the transaction, right after the copy was inserted.
        let (_two, stopped, _) = a_house().await;
        let (anna, everyone) = phones_done(&stopped).await;
        let first = first_bedtime(&everyone).unwrap();
        {
            let mut tx = stopped.begin().await.unwrap();
            let copy = copy_as_bedtime(&mut tx, everyone[first].0.id, anna)
                .await
                .unwrap();
            assert!(copy.is_some());
            // The process stops here: the transaction is dropped, never committed.
        }
        migrate_crossing(&stopped).await.unwrap();
        assert_eq!(state(&stopped).await, expected);

        // Stopped after everyone's bedtime was converted, the second one still waiting.
        let (_three, stopped, _) = a_house().await;
        let (_, mut everyone) = phones_done(&stopped).await;
        let (row, days) = everyone.remove(first_bedtime(&everyone).unwrap());
        convert_global_bedtime(&stopped, &row, &days).await.unwrap();
        assert!(
            first_bedtime(
                &readable_rules(&mut stopped.acquire().await.unwrap(), None)
                    .await
                    .unwrap()
            )
            .is_some(),
            "the second bedtime-shaped rule waits"
        );
        migrate_crossing(&stopped).await.unwrap();
        assert_eq!(state(&stopped).await, expected);
    }
}
