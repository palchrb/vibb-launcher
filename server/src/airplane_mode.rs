//! Design 98: Flymodus through the Digital assistant role. The report's `airplane_mode` cleaned field by field
//! ([clean]), the emergency line's rule ([emergency_line]) and the capability. The shared contract is
//! `testdata/airplane_mode.json` (byte-identical in the launcher's test resources).
//!
//! The pages' parts (§6): the stored report read back ([report]), the summary's lines ([summary_lines]; "Flymodus på
//! siden 21:03" is the header's, `phone_status::with_airplane`), the Oppsett card's steps ([setup_steps]) and the
//! Home Screen row's sub-line ([home_sub]).

use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};

use crate::display_time::Zone;
use crate::i18n::{Lang, t, t_params};
use crate::models::{DevicePolicy, DeviceStatus};

/// The capability a launcher claims once it reports `airplane_mode` and shows the kid's Flymodus row (bit 64).
pub const CAPABILITY: &str = "airplane_mode_v1";

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

/// The latest report's `airplane_mode`, from a launcher that claims [CAPABILITY] (§6.4: an older launcher has no row
/// and no step).
pub fn report(latest: &DeviceStatus) -> Option<Report> {
    if !crate::handlers::device_pages::has_capability(Some(latest), CAPABILITY) {
        return None;
    }
    serde_json::from_str(latest.airplane_mode_json.as_deref()?).ok()
}

/// "Flymodus på siden 21:03" (§6.2, §6.3): since the open period's start while the report says `on` (the report's
/// time without one), or since a `going_on` report's time for [GOING_ON_MS] after it. `None` otherwise.
pub fn on_since(latest: &DeviceStatus, now: DateTime<Utc>) -> Option<DateTime<Utc>> {
    let report = report(latest)?;
    let reported_at = crate::display_time::parse_db_utc(&latest.reported_at)?;
    let at_ms = if report.on {
        report
            .periods
            .iter()
            .rev()
            .find(|p| p.off_ms.is_none())
            .map_or(reported_at.timestamp_millis(), |p| p.on_ms)
    } else if report.going_on && (now - reported_at).num_milliseconds() < GOING_ON_MS {
        reported_at.timestamp_millis()
    } else {
        return None;
    };
    DateTime::from_timestamp_millis(at_ms.min(now.timestamp_millis()))
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
/// ended the newest period within [ENDED_SHOWN_MS] (any other ended period is an Activity row since design 101), and the warning
/// while the kid's switch is on and the role, held before ([assistant_held_before]), is lost now. Empty for an older
/// launcher.
pub fn summary_lines(
    zone: &Zone,
    now: DateTime<Utc>,
    policy: &DevicePolicy,
    report: Option<&Report>,
    held_before: bool,
) -> Vec<Line> {
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
    if policy.quick_controls_mask & crate::handlers::devices::QUICK_CONTROL_AIRPLANE != 0
        && !report.assistant
        && held_before
    {
        lines.push(Line {
            key: "role-lost",
            title: t(lang, "device.summary.airplane.role_lost").to_string(),
            steps: Some(t(lang, "device.airplane.assistant_steps").to_string()),
            warn: true,
        });
    }
    lines
}

/// Whether any stored report of the phone said the role was held (§3.6: "held before, lost now" warns; a phone that
/// never had it gets the Oppsett card's step instead). Reports go with `device_status`' retention, so a role lost long
/// ago stops warning once its last `assistant: true` is pruned - the card's step stays.
pub async fn assistant_held_before(
    db: &sqlx::SqlitePool,
    device_id: i64,
) -> Result<bool, sqlx::Error> {
    sqlx::query_scalar(
        "SELECT EXISTS(SELECT 1 FROM device_status WHERE device_id = ? \
         AND airplane_mode_json IS NOT NULL AND json_valid(airplane_mode_json) \
         AND json_extract(airplane_mode_json, '$.assistant') = 1)",
    )
    .bind(device_id)
    .fetch_one(db)
    .await
}

/// The Home Screen row's sub-line (§6.1; design 113 QA M6), only when it says something, and whether it is orange: the
/// parent's "Blokker flymodus" (grey); with bit 64 on, the role not held yet (orange - the kid sees no switch) or, with
/// the role, no Wi-Fi in airplane mode (grey). With bit 64 off nothing is said about the role (the (i) says when the
/// switch shows). The parts join with " · ".
pub fn home_sub(lang: Lang, policy: &DevicePolicy, report: &Report) -> Option<(String, bool)> {
    let mut parts: Vec<String> = Vec::new();
    if policy.hardening.disallow_airplane_mode {
        parts.push(t(lang, "device.home.airplane_blocked").to_string());
    }
    let bit = policy.quick_controls_mask & crate::handlers::devices::QUICK_CONTROL_AIRPLANE != 0;
    let no_role = bit && !report.assistant;
    if no_role {
        parts.push(t(lang, "device.home.airplane_no_role").to_string());
    } else if bit && report.wifi_kept != Some(true) {
        parts.push(t(lang, "device.home.airplane_offline").to_string());
    }
    (!parts.is_empty()).then(|| (parts.join(" · "), no_role))
}

/// A role in `default_apps_changed`, in plain words ("Anropsfilter", "Nettleser", "Startskjerm").
pub fn role_words(lang: Lang, role: &str) -> &'static str {
    t(
        lang,
        match role {
            "home" => "device.summary.setup.role.home",
            "dialer" => "device.summary.setup.role.dialer",
            "sms" => "device.summary.setup.role.sms",
            "call_screening" => "device.summary.setup.role.call_screening",
            "call_redirection" => "device.summary.setup.role.call_redirection",
            _ => "device.summary.setup.role.browser",
        },
    )
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

/// One step of the Oppsett card (§6.2).
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct SetupStep {
    /// The step's part of the needed set ([setup_key]) and the page's hook (`data-setup-step`): `background`,
    /// `write_settings`, `assistant`, `wifi`, `default_apps:<roles>`, `call_filter`.
    pub id: String,
    pub label: String,
    pub sub: String,
}

/// The steps the phone needs and hasn't done (§6.2), in the card's order, from a launcher with [CAPABILITY] (the one
/// with "Fullfør oppsett" and its notification, which the card's words name):
/// - Bakgrunnstilkobling, always (the report's `battery_optimization_exempt` false);
/// - Endre systeminnstillinger, only with the touch switch (bit 16), the Sound bit (8) or a clock format in use;
/// - Digital assistent, only with the kid's Flymodus switch (bit 64);
/// - Wi-Fi i flymodus, only with bit 64 and the role held, done once the phone stored `wifi_kept` (true or false);
/// - Standardapper endret, while `default_apps_changed` names a role;
/// - Samtalefilter (D10), while calls are managed and the report's `call_state` says the call screening or the call
///   redirection role isn't held.
pub fn setup_steps(
    lang: Lang,
    policy: &DevicePolicy,
    latest: Option<&DeviceStatus>,
) -> Vec<SetupStep> {
    let Some(latest) = latest else {
        return Vec::new();
    };
    let Some(report) = report(latest) else {
        return Vec::new();
    };
    let step = |id: &str, label: &str, sub: String| SetupStep {
        id: id.to_string(),
        label: t(lang, label).to_string(),
        sub,
    };
    let mut steps = Vec::new();
    if latest.battery_optimization_exempt == Some(false) {
        steps.push(step(
            "background",
            "device.summary.setup.background",
            t(lang, "device.summary.setup.background_sub").to_string(),
        ));
    }
    if write_settings_needed(policy) && latest.write_settings_granted == Some(false) {
        steps.push(step(
            "write_settings",
            "apps.perms.access.write_settings",
            t(lang, "device.about.write_settings_sub").to_string(),
        ));
    }
    if assistant_needed(policy) {
        if !report.assistant {
            steps.push(step(
                "assistant",
                "device.about.assistant",
                t(lang, "device.summary.setup.assistant_sub").to_string(),
            ));
        } else if report.wifi_kept.is_none() {
            steps.push(step(
                "wifi",
                "device.about.wifi_airplane",
                t(lang, "device.summary.setup.wifi_sub").to_string(),
            ));
        }
    }
    if !report.default_apps_changed.is_empty() {
        let names: Vec<&str> = report
            .default_apps_changed
            .iter()
            .map(|role| role_words(lang, role))
            .collect();
        let mut sub = t_params(
            lang,
            "device.summary.setup.default_apps_sub",
            &[("apps", &names.join(", "))],
        );
        if report.default_apps_changed.iter().any(|r| r == "sms") {
            sub = format!("{sub} {}", t(lang, "device.summary.setup.default_apps_sms"));
        }
        steps.push(step(
            &format!("default_apps:{}", report.default_apps_changed.join("+")),
            "device.summary.setup.default_apps",
            sub,
        ));
    }
    let call_state: Option<Value> = latest
        .call_state_json
        .as_deref()
        .and_then(|json| serde_json::from_str(json).ok());
    let held = |key: &str| {
        call_state
            .as_ref()
            .and_then(|state| state.get(key))
            .and_then(Value::as_bool)
    };
    if policy.calls_managed()
        && (held("screening_role_held") == Some(false)
            || held("redirection_role_held") == Some(false))
    {
        steps.push(step(
            "call_filter",
            "device.summary.setup.call_filter",
            t(lang, "device.summary.setup.call_filter_sub").to_string(),
        ));
    }
    steps
}

/// The needed set as stored by "Ikke nå" (`device_policy.setup_card_hidden`): the card shows again once it changes.
pub fn setup_key(steps: &[SetupStep]) -> String {
    steps
        .iter()
        .map(|s| s.id.as_str())
        .collect::<Vec<_>>()
        .join(",")
}

/// The Oppsett card: its steps, and whether it shows (a step is needed and "Ikke nå" didn't hide this set).
pub struct SetupCard {
    pub steps: Vec<SetupStep>,
    pub shown: bool,
}

impl SetupCard {
    pub fn of(lang: Lang, policy: &DevicePolicy, latest: Option<&DeviceStatus>) -> SetupCard {
        let steps = setup_steps(lang, policy, latest);
        let shown = !steps.is_empty()
            && policy.setup_card_hidden.as_deref() != Some(setup_key(&steps).as_str());
        SetupCard { steps, shown }
    }
}
