//! Design 98: Flymodus through the Digital assistant role. The report's `airplane_mode` cleaned field by field
//! ([clean]) and the emergency line's rule ([emergency_line]). The shared contract is
//! `testdata/airplane_mode.json` (byte-identical in the launcher's test resources).
//!
//! The pages' parts (§6): the stored report read back ([report]), the summary's lines ([summary_lines]; "Flymodus på
//! siden 21:03" is the header's, `phone_status::with_airplane`) and the Home Screen row's sub-line ([home_sub]). The
//! setup steps it needs (the role, Wi-Fi in airplane mode, the default apps) are design 114's `setup_status`.

use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};

use crate::display_time::Zone;
use crate::i18n::{Lang, t, t_params};
use crate::models::{DevicePolicy, DeviceStatus};

/// A report with `going_on` alone says "on" this long (§6.2, Q98-L2): a turn-on that failed after its report sends
/// another report at once, and one that never came back is no longer read as on.
pub const GOING_ON_MS: i64 = 10 * 60_000;
/// A period that ended at most this long ago and was ended by the emergency call is said on the summary.
pub const ENDED_SHOWN_MS: i64 = 12 * 60 * 60_000;

/// At most this many periods are kept (the newest).
pub const MAX_PERIODS: usize = 10;

/// The usual clock slack: a period starting later than this after the report's arrival is dropped.
const CLOCK_SLACK_MS: i64 = 5 * 60_000;

/// The emergency line (§7.1): the period ended at most this long after the reported call...
const EMERGENCY_AFTER_MS: i64 = 10 * 60_000;
/// ...or at most this long before it (the phone records the call as it places it, Android ends airplane mode for it).
const EMERGENCY_BEFORE_MS: i64 = 60_000;

/// `last_error`'s words the server keeps (`cooldown` stays on the phone).
const ERRORS: &[&str] = &["route_missing", "refused", "timeout", "emergency"];
/// `default_apps_changed`'s role names.
pub const ROLES: &[&str] = &[
    "home",
    "dialer",
    "sms",
    "call_screening",
    "call_redirection",
    "browser",
];
const ON_BY: &[&str] = &["kid", "other"];
const OFF_BY: &[&str] = &["kid", "parent", "emergency", "other"];

/// One airplane-mode period, as stored.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Period {
    pub on_ms: i64,
    pub on_by: String,
    pub off_ms: Option<i64>,
    pub off_by: Option<String>,
}

fn word(value: Option<&Value>, known: &[&str]) -> String {
    value
        .and_then(Value::as_str)
        .filter(|w| known.contains(w))
        .unwrap_or("other")
        .to_string()
}

/// The report's `airplane_mode`, re-serialised (§5.2's `clean`): booleans or their defaults, unknown `on_by` and
/// `off_by` words read as `other`, unknown errors and roles dropped; a period ending before it starts or starting
/// past the clock slack is dropped, the rest sorted oldest first, an open period that isn't the last closed at the
/// next one's start, and the newest [MAX_PERIODS] kept. `None` when it isn't an object.
pub fn clean(value: &Value, now_ms: i64) -> Option<String> {
    let object = value.as_object()?;
    let mut periods: Vec<Period> = object
        .get("periods")
        .and_then(Value::as_array)
        .map(|rows| {
            rows.iter()
                .filter_map(|row| {
                    let on_ms = row.get("on_ms")?.as_i64()?;
                    let off_ms = row.get("off_ms").and_then(Value::as_i64);
                    if on_ms > now_ms.saturating_add(CLOCK_SLACK_MS)
                        || off_ms.is_some_and(|off| off < on_ms)
                    {
                        return None;
                    }
                    Some(Period {
                        on_ms,
                        on_by: word(row.get("on_by"), ON_BY),
                        off_by: off_ms.map(|_| word(row.get("off_by"), OFF_BY)),
                        off_ms,
                    })
                })
                .collect()
        })
        .unwrap_or_default();
    periods.sort_by_key(|p| p.on_ms);
    let starts: Vec<i64> = periods.iter().map(|p| p.on_ms).collect();
    for (i, period) in periods.iter_mut().enumerate() {
        if period.off_ms.is_none()
            && let Some(next) = starts.get(i + 1)
        {
            period.off_ms = Some(*next);
            period.off_by = Some("other".into());
        }
    }
    let cut = periods.len().saturating_sub(MAX_PERIODS);
    periods.drain(..cut);
    let bool_of = |key: &str| object.get(key).and_then(Value::as_bool).unwrap_or(false);
    let mut roles: Vec<String> = Vec::new();
    for role in object
        .get("default_apps_changed")
        .and_then(Value::as_array)
        .into_iter()
        .flatten()
        .filter_map(Value::as_str)
    {
        if ROLES.contains(&role) && !roles.iter().any(|r| r == role) {
            roles.push(role.to_string());
        }
    }
    Some(
        json!({
            "on": bool_of("on"),
            "going_on": bool_of("going_on"),
            "assistant": bool_of("assistant"),
            "wifi_kept": object.get("wifi_kept").and_then(Value::as_bool),
            "last_error": object
                .get("last_error")
                .and_then(Value::as_str)
                .filter(|e| ERRORS.contains(e)),
            "default_apps_changed": roles,
            "periods": periods,
        })
        .to_string(),
    )
}

/// §7.1: whether the emergency call at `call_ms` gets the line "The call turned airplane mode off.": the period
/// ended by an emergency call, at most 10 minutes after the call (or a minute before the phone recorded it).
pub fn emergency_line(call_ms: i64, period: &Period) -> bool {
    period.off_by.as_deref() == Some("emergency")
        && period.off_ms.is_some_and(|off| {
            off >= call_ms.saturating_sub(EMERGENCY_BEFORE_MS)
                && off <= call_ms.saturating_add(EMERGENCY_AFTER_MS)
        })
}

/// §7.1 on a stored (cleaned) report: one of its periods was ended by the emergency call at `call_ms`.
pub fn emergency_off(stored: Option<&str>, call_ms: i64) -> bool {
    stored
        .and_then(|json| serde_json::from_str::<Report>(json).ok())
        .is_some_and(|report| report.periods.iter().any(|p| emergency_line(call_ms, p)))
}

/// The stored `airplane_mode`, as the pages read it (the cleaned shape, every key there).
#[derive(Debug, Clone, Default, Deserialize)]
#[serde(default)]
pub struct Report {
    pub on: bool,
    pub going_on: bool,
    pub assistant: bool,
    pub wifi_kept: Option<bool>,
    pub default_apps_changed: Vec<String>,
    pub periods: Vec<Period>,
}

/// The latest report's `airplane_mode`, when it has one.
pub fn report(latest: &DeviceStatus) -> Option<Report> {
    serde_json::from_str(latest.airplane_mode_json.as_deref()?).ok()
}

/// "Flymodus på siden 21:03" (§6.2, §6.3): since the open period's start while the report says `on` (the report's
/// time without one), or since a `going_on` report's time for [GOING_ON_MS] after it. `None` otherwise.
pub fn on_since(latest: &DeviceStatus, now: DateTime<Utc>) -> Option<DateTime<Utc>> {
    let report = report(latest)?;
    let reported_at = crate::display_time::parse_db_utc(&latest.reported_at)?;
    if !report.on && !(report.going_on && (now - reported_at).num_milliseconds() < GOING_ON_MS) {
        return None;
    }
    DateTime::from_timestamp_millis(since_ms(&report, reported_at).min(now.timestamp_millis()))
}

/// A contact this long after the turn-on's report is still the report's own (its intake, the database's whole
/// seconds): [held_since].
pub const HELD_SLACK_MS: i64 = 10_000;

/// Design 121 §4.3 (QA H2): Vibb's switch told the server before the radios went - the latest report says `going_on`
/// or `on` - and nothing has reached the server since (`last_contact` at most [HELD_SLACK_MS] after the report). The
/// phone is then in airplane mode for as long as that holds, all night if need be, unlike [on_since]'s 10 minutes
/// for a phone that is in touch: since the open period's start, or the report's time. The caller checks that no
/// stream is live (`phone_status::held`).
pub fn held_since(latest: &DeviceStatus, last_contact: DateTime<Utc>) -> Option<DateTime<Utc>> {
    let report = report(latest)?;
    let reported_at = crate::display_time::parse_db_utc(&latest.reported_at)?;
    if !(report.on || report.going_on)
        || (last_contact - reported_at).num_milliseconds() > HELD_SLACK_MS
    {
        return None;
    }
    DateTime::from_timestamp_millis(
        since_ms(&report, reported_at).min(reported_at.timestamp_millis()),
    )
}

/// The open period's start while the report says `on` (the report's time without one), else the report's time.
fn since_ms(report: &Report, reported_at: DateTime<Utc>) -> i64 {
    report
        .periods
        .iter()
        .rev()
        .find(|p| report.on && p.off_ms.is_none())
        .map_or(reported_at.timestamp_millis(), |p| p.on_ms)
}

/// One of the summary's Flymodus lines (§6.2): its words, the steps behind its ⓘ, and whether it is a warning.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Line {
    /// For tests and the page's hooks (`data-airplane-line`).
    pub key: &'static str,
    pub title: String,
    pub steps: Option<String>,
    pub warn: bool,
}

/// The summary's lines under the header (§6.2): "An emergency call turned airplane mode off at 14:02" when the call
/// ended the newest period within [ENDED_SHOWN_MS] (any other ended period is an Activity row since design 101). The
/// role lost is design 114's setup step `assistant`, counted in the summary's setup row. Empty without a report.
pub fn summary_lines(zone: &Zone, now: DateTime<Utc>, report: Option<&Report>) -> Vec<Line> {
    let lang = zone.lang();
    let Some(report) = report else {
        return Vec::new();
    };
    let mut lines = Vec::new();
    let now_ms = now.timestamp_millis();
    if let Some((period, off)) = report
        .periods
        .iter()
        .rev()
        .find_map(|p| p.off_ms.map(|off| (p, off)))
        .filter(|(_, off)| now_ms - off <= ENDED_SHOWN_MS)
        && let Some(off_at) = DateTime::from_timestamp_millis(off)
    {
        // Only the emergency call's end is said on the summary; every ended period is an Activity row (design 101).
        if period.off_by.as_deref() == Some("emergency") {
            lines.push(Line {
                key: "emergency",
                title: t_params(
                    lang,
                    "device.summary.airplane.emergency",
                    &[("at", &zone.since(off_at, now))],
                ),
                steps: None,
                warn: false,
            });
        }
    }
    lines
}

/// The Home Screen row's sub-line (§6.1), only when it says something: the parent's "Blokker flymodus" (grey). The role
/// and Wi-Fi in airplane mode are design 114's setup steps, said by their warning lines at `#quick-controls`.
pub fn home_sub(lang: Lang, policy: &DevicePolicy) -> Option<String> {
    policy
        .hardening
        .disallow_airplane_mode
        .then(|| t(lang, "device.home.airplane_blocked").to_string())
}

/// A feature the parent chose needs Android's "Modify system settings": the touch switch (bit 16), the Sound bit
/// (8: the kid's Ringetone and Meldingslyd picks) or a clock format in use. Without one, the grant's absence is only a quiet value (2026-11-18).
pub fn write_settings_needed(policy: &DevicePolicy) -> bool {
    policy.quick_controls_mask
        & (crate::handlers::devices::QUICK_CONTROL_TOUCH
            | crate::handlers::devices::QUICK_CONTROL_SOUND)
        != 0
        || crate::models::clock_format(&policy.clock_format) != "system"
}

/// The kid's Flymodus switch (bit 64) is on: it needs the Digital assistant role.
pub fn assistant_needed(policy: &DevicePolicy) -> bool {
    policy.quick_controls_mask & crate::handlers::devices::QUICK_CONTROL_AIRPLANE != 0
}
