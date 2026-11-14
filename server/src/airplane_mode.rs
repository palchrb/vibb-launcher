//! Design 98: Flymodus through the Digital assistant role. The report's `airplane_mode` cleaned field by field
//! ([clean]), the emergency line's rule ([emergency_line]) and the capability. The shared contract is
//! `testdata/airplane_mode.json` (byte-identical in the launcher's test resources).

use serde::{Deserialize, Serialize};
use serde_json::{Value, json};

/// The capability a launcher claims once it reports `airplane_mode` and shows the kid's Flymodus row (bit 64).
#[cfg_attr(not(test), allow(dead_code))] // The pages read it from step 5.
pub const CAPABILITY: &str = "airplane_mode_v1";

/// At most this many periods are kept (the newest).
pub const MAX_PERIODS: usize = 10;

/// The usual clock slack: a period starting later than this after the report's arrival is dropped.
const CLOCK_SLACK_MS: i64 = 5 * 60_000;

/// The emergency line (§7.1): the period ended at most this long after the reported call...
#[cfg(test)]
const EMERGENCY_AFTER_MS: i64 = 10 * 60_000;
/// ...or at most this long before it (the phone records the call as it places it, Android ends airplane mode for it).
#[cfg(test)]
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
#[cfg(test)] // Step 5 raises the line from the intake.
pub fn emergency_line(call_ms: i64, period: &Period) -> bool {
    period.off_by.as_deref() == Some("emergency")
        && period.off_ms.is_some_and(|off| {
            off >= call_ms.saturating_sub(EMERGENCY_BEFORE_MS)
                && off <= call_ms.saturating_add(EMERGENCY_AFTER_MS)
        })
}
