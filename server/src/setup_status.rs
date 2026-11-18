//! Design 114: the phone's setup status - one step table for the phone and the PWA
//! (`docs/design/114-setup-status.md`). The shared contract is `testdata/setup_steps.json`, byte-identical in the
//! launcher's test resources: `steps` ([STEPS]), `features` ([FEATURES]), the report's `setup` ([clean]), the policy's
//! `setup` ([policy]), the phone's tick ([tick]) and the `needed` rows the one rule runs ([missing], [count],
//! [alarm], [setup_row]).
//!
//! The rule (§2.3): a step applies by its `when` over [Inputs]; it is missing when it applies and its reading is
//! `false` (a `parent` step: when it isn't ticked). N ([count]) is the missing required steps with `summary: count`;
//! the summary's row is raised while N > 0 and the phone isn't known not to be device owner ([setup_row]). The
//! server's readings come from what the phone already reports ([readings_of]); the report's own `setup` carries only
//! what no other field does (§4.1).

use std::collections::BTreeMap;

use axum::Extension;
use axum::extract::State;
use axum::http::StatusCode;
use axum::response::{IntoResponse, Response};
use serde_json::{Map, Value, json};

use crate::AppState;
use crate::handlers::devices::{QUICK_CONTROL_AIRPLANE, QUICK_CONTROL_SOUND, QUICK_CONTROL_TOUCH};
use crate::models::{DevicePolicy, DeviceStatus};
use crate::security::AuthedDevice;

/// `POST /api/devices/setup/ticks` takes at most this much (§4.4, QA L7).
pub const TICK_BODY_LIMIT: usize = 256;

/// Counted (orange) or only listed (§2.1).
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Need {
    Required,
    Optional,
}

/// Who reads a step: the phone's report, the server's own data, or the parent's tick.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum ReadBy {
    Phone,
    Server,
    Parent,
}

/// When a step applies (§2.3).
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum When {
    Always,
    CallsManaged,
    WriteSettingsUsed,
    Airplane,
    AirplaneRole,
    AndroidLock,
    MapLinks,
}

/// In the summary's N, or its own Attention row (§2.1).
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Summary {
    Count,
    OwnRow,
}

/// Orange or red.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Level {
    Action,
    Alarm,
}

/// One step of the table (§2.2). Its name is `setup.step.<key>`, its ⓘ `setup.how.<key>`, its warning line (a step
/// with `features`) `setup.warn.<key>`.
#[derive(Debug)]
pub struct Step {
    pub key: &'static str,
    pub need: Need,
    pub read_by: ReadBy,
    pub when: When,
    pub summary: Summary,
    pub level: Level,
    /// The PWA choices it stops (§5.4), keys of [FEATURES].
    pub features: &'static [&'static str],
    /// `setup.step.<key>`.
    pub name: &'static str,
    /// `setup.how.<key>`: what to do on Android's page (the page's ⓘ).
    pub how: &'static str,
    /// `setup.warn.<key>`: the line at a choice it stops; `None` for a step that stops none.
    pub warn: Option<&'static str>,
}

macro_rules! step {
    ($key:literal, $need:ident, $read_by:ident, $when:ident, $summary:ident, $level:ident, [$($f:literal),*],
     $name:literal, $how:literal, $warn:expr) => {
        Step {
            key: $key,
            need: Need::$need,
            read_by: ReadBy::$read_by,
            when: When::$when,
            summary: Summary::$summary,
            level: Level::$level,
            features: &[$($f),*],
            name: $name,
            how: $how,
            warn: $warn,
        }
    };
}

/// The table in display order (§2.2): the flow, the PWA page and the contract.
pub const STEPS: &[Step] = &[
    step!(
        "parent_pin",
        Required,
        Server,
        Always,
        OwnRow,
        Alarm,
        [],
        "setup.step.parent_pin",
        "setup.how.parent_pin",
        None
    ),
    step!(
        "device_owner",
        Required,
        Phone,
        Always,
        OwnRow,
        Alarm,
        [],
        "setup.step.device_owner",
        "setup.how.device_owner",
        None
    ),
    step!(
        "location",
        Required,
        Phone,
        Always,
        Count,
        Action,
        ["locate", "wifi_signin"],
        "setup.step.location",
        "setup.how.location",
        Some("setup.warn.location")
    ),
    step!(
        "notification_access",
        Required,
        Phone,
        Always,
        Count,
        Action,
        ["badges", "glance", "music_card", "notification_filter"],
        "setup.step.notification_access",
        "setup.how.notification_access",
        Some("setup.warn.notification_access")
    ),
    step!(
        "mobile_data",
        Required,
        Phone,
        Always,
        Count,
        Action,
        ["mobile_choices"],
        "setup.step.mobile_data",
        "setup.how.mobile_data",
        Some("setup.warn.mobile_data")
    ),
    step!(
        "data_saver",
        Required,
        Phone,
        Always,
        Count,
        Action,
        ["mobile_choices", "app_push"],
        "setup.step.data_saver",
        "setup.how.data_saver",
        Some("setup.warn.data_saver")
    ),
    step!(
        "background",
        Required,
        Phone,
        Always,
        Count,
        Action,
        ["app_push"],
        "setup.step.background",
        "setup.how.background",
        Some("setup.warn.background")
    ),
    step!(
        "vibb_notifications",
        Required,
        Phone,
        Always,
        Count,
        Action,
        [],
        "setup.step.vibb_notifications",
        "setup.how.vibb_notifications",
        None
    ),
    step!(
        "full_screen_intent",
        Required,
        Phone,
        CallsManaged,
        Count,
        Action,
        ["calls"],
        "setup.step.full_screen_intent",
        "setup.how.full_screen_intent",
        Some("setup.warn.full_screen_intent")
    ),
    step!(
        "write_settings",
        Required,
        Phone,
        WriteSettingsUsed,
        Count,
        Action,
        ["touch", "clock_format"],
        "setup.step.write_settings",
        "setup.how.write_settings",
        Some("setup.warn.write_settings")
    ),
    step!(
        "assistant",
        Required,
        Phone,
        Airplane,
        Count,
        Action,
        ["airplane"],
        "setup.step.assistant",
        "setup.how.assistant",
        Some("setup.warn.assistant")
    ),
    step!(
        "wifi_airplane",
        Required,
        Phone,
        AirplaneRole,
        Count,
        Action,
        ["airplane"],
        "setup.step.wifi_airplane",
        "setup.how.wifi_airplane",
        Some("setup.warn.wifi_airplane")
    ),
    step!(
        "call_filter",
        Required,
        Phone,
        CallsManaged,
        Count,
        Action,
        ["calls"],
        "setup.step.call_filter",
        "setup.how.call_filter",
        Some("setup.warn.call_filter")
    ),
    step!(
        "default_apps",
        Required,
        Phone,
        Always,
        Count,
        Action,
        [],
        "setup.step.default_apps",
        "setup.how.default_apps",
        None
    ),
    step!(
        "map_play_links",
        Required,
        Phone,
        MapLinks,
        Count,
        Action,
        ["map_links"],
        "setup.step.map_play_links",
        "setup.how.map_play_links",
        Some("setup.warn.map_play_links")
    ),
    step!(
        "map_vibb_links",
        Required,
        Phone,
        MapLinks,
        Count,
        Action,
        ["map_links"],
        "setup.step.map_vibb_links",
        "setup.how.map_vibb_links",
        Some("setup.warn.map_vibb_links")
    ),
    step!(
        "fingerprint",
        Optional,
        Phone,
        AndroidLock,
        Count,
        Action,
        [],
        "setup.step.fingerprint",
        "setup.how.fingerprint",
        None
    ),
    step!(
        "vibration",
        Optional,
        Parent,
        Always,
        Count,
        Action,
        [],
        "setup.step.vibration",
        "setup.how.vibration",
        None
    ),
    step!(
        "touch_feedback",
        Optional,
        Parent,
        Always,
        Count,
        Action,
        [],
        "setup.step.touch_feedback",
        "setup.how.touch_feedback",
        None
    ),
    step!(
        "lock_clock",
        Optional,
        Parent,
        AndroidLock,
        Count,
        Action,
        [],
        "setup.step.lock_clock",
        "setup.how.lock_clock",
        None
    ),
    step!(
        "media_on_lock",
        Optional,
        Parent,
        AndroidLock,
        Count,
        Action,
        [],
        "setup.step.media_on_lock",
        "setup.how.media_on_lock",
        None
    ),
    step!(
        "message_banners",
        Optional,
        Parent,
        Always,
        Count,
        Action,
        [],
        "setup.step.message_banners",
        "setup.how.message_banners",
        None
    ),
];

/// The step with `key`.
pub fn step(key: &str) -> Option<&'static Step> {
    STEPS.iter().find(|step| step.key == key)
}

/// A PWA choice a step can stop, and the section its warning line goes to (§5.4): `page` is the phone's sub-page
/// (`/devices/{id}/<page>`; `locate` is the Locate tab), `anchor` the section's id.
#[derive(Debug)]
pub struct Feature {
    pub key: &'static str,
    pub page: &'static str,
    pub anchor: &'static str,
}

/// The contract's `features`, in its order.
pub const FEATURES: &[Feature] = &[
    Feature {
        key: "mobile_choices",
        page: "mobile",
        anchor: "mobile-data",
    },
    Feature {
        key: "airplane",
        page: "home",
        anchor: "quick-controls",
    },
    Feature {
        key: "touch",
        page: "home",
        anchor: "quick-controls",
    },
    Feature {
        key: "clock_format",
        page: "home",
        anchor: "layout",
    },
    Feature {
        key: "badges",
        page: "home",
        anchor: "layout",
    },
    Feature {
        key: "glance",
        page: "lock",
        anchor: "lock-screen",
    },
    Feature {
        key: "music_card",
        page: "music",
        anchor: "on-phone",
    },
    Feature {
        key: "notification_filter",
        page: "advanced",
        anchor: "updates",
    },
    Feature {
        key: "calls",
        page: "calls",
        anchor: "settings",
    },
    Feature {
        key: "app_push",
        page: "apps",
        anchor: "app-push",
    },
    Feature {
        key: "map_links",
        page: "apps",
        anchor: "map-links",
    },
    Feature {
        key: "locate",
        page: "locate",
        anchor: "location",
    },
    Feature {
        key: "wifi_signin",
        page: "logins",
        anchor: "logins-wifi",
    },
];

/// The need rule's inputs (§2.3), from the policy and, for `map_links`, the installed apps.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct Inputs {
    pub mask: i64,
    pub clock_format: String,
    pub calls_managed: bool,
    pub android_lock: bool,
    pub map_links_on: bool,
    pub map_app_installed: bool,
}

/// The readings: step key to `Some(true)`/`Some(false)`, `None` unreadable; a key left out is absent (not on this
/// phone, or not reported).
pub type Readings = BTreeMap<String, Option<bool>>;

/// Design 110's map apps (110 §7.1's targets): the `map_links` steps apply only while one is installed.
pub const MAP_APPS: &[&str] = &["app.organicmaps", "app.comaps.fdroid", "app.comaps"];

impl Inputs {
    /// The Sound bit (8), the touch bit (16) or a clock format in use: Android's "Modify system settings" is needed
    /// (`airplane_mode::write_settings_needed`'s rule).
    fn write_settings_used(&self) -> bool {
        self.mask & (QUICK_CONTROL_TOUCH | QUICK_CONTROL_SOUND) != 0
            || self.clock_format != "system"
    }

    fn airplane(&self) -> bool {
        self.mask & QUICK_CONTROL_AIRPLANE != 0
    }
}

/// Whether `step` applies on this phone now (§2.3).
pub fn applies(step: &Step, inputs: &Inputs, readings: &Readings) -> bool {
    match step.when {
        When::Always => true,
        When::CallsManaged => inputs.calls_managed,
        When::WriteSettingsUsed => inputs.write_settings_used(),
        When::Airplane => inputs.airplane(),
        When::AirplaneRole => {
            inputs.airplane() && readings.get("assistant").copied().flatten() == Some(true)
        }
        When::AndroidLock => inputs.android_lock,
        When::MapLinks => inputs.map_links_on && inputs.map_app_installed,
    }
}

/// The steps that apply and aren't done, in table order (§2.3): a reading present and `false`; a `parent` step not
/// ticked. An unreadable (`null`) or absent reading is never missing.
pub fn missing(inputs: &Inputs, readings: &Readings, ticked: &[String]) -> Vec<&'static Step> {
    STEPS
        .iter()
        .filter(|step| applies(step, inputs, readings))
        .filter(|step| match step.read_by {
            ReadBy::Parent => !ticked.iter().any(|key| key == step.key),
            ReadBy::Phone | ReadBy::Server => {
                readings.get(step.key).copied().flatten() == Some(false)
            }
        })
        .collect()
}

/// N: the missing required steps the summary counts.
pub fn count(missing: &[&Step]) -> usize {
    missing
        .iter()
        .filter(|step| step.need == Need::Required && step.summary == Summary::Count)
        .count()
}

/// An `own_row` alarm step is missing.
pub fn alarm(missing: &[&Step]) -> bool {
    missing
        .iter()
        .any(|step| step.summary == Summary::OwnRow && step.level == Level::Alarm)
}

/// The summary's row (§2.3, QA M4): N > 0 and the phone not known to be outside device owner.
pub fn setup_row(missing: &[&Step], readings: &Readings) -> bool {
    count(missing) > 0 && readings.get("device_owner").copied().flatten() != Some(false)
}

/// The readings the report's `setup` carries (§4.1).
const REPORT_READINGS: &[&str] = &[
    "device_owner",
    "mobile_data",
    "location",
    "data_saver",
    "full_screen_intent",
    "fingerprint",
];

/// The report's `setup`, cleaned field by field (the contract's `clean`): the readings kept as booleans or `null`,
/// `hold_until_ms` and `hold_left_ms` only as non-negative integers, every other key and type dropped. `None` when it
/// isn't an object.
pub fn clean(value: &Value) -> Option<String> {
    clean_value(value).map(|value| value.to_string())
}

fn clean_value(value: &Value) -> Option<Value> {
    let object = value.as_object()?;
    let mut kept = Map::new();
    for key in REPORT_READINGS {
        match object.get(*key) {
            Some(Value::Bool(b)) => {
                kept.insert(key.to_string(), Value::Bool(*b));
            }
            Some(Value::Null) => {
                kept.insert(key.to_string(), Value::Null);
            }
            _ => {}
        }
    }
    for key in ["hold_until_ms", "hold_left_ms"] {
        if let Some(n) = object.get(key).and_then(Value::as_i64).filter(|n| *n >= 0)
            && object.get(key).is_some_and(|v| v.is_i64() || v.is_u64())
        {
            kept.insert(key.to_string(), json!(n));
        }
    }
    Some(Value::Object(kept))
}

/// The hold's end the server keeps (§4.2, QA L6): `min(hold_until_ms, arrived_ms + hold_left_ms)` - the phone's wall
/// clock and its elapsed clock, whichever ends first; one of them when only it is there. `None` without the hold.
pub fn hold_until(cleaned: Option<&str>, arrived_ms: i64) -> Option<i64> {
    let value: Value = serde_json::from_str(cleaned?).ok()?;
    let until = value.get("hold_until_ms").and_then(Value::as_i64);
    let left = value
        .get("hold_left_ms")
        .and_then(Value::as_i64)
        .map(|left| arrived_ms.saturating_add(left));
    match (until, left) {
        (Some(a), Some(b)) => Some(a.min(b)),
        (a, b) => a.or(b),
    }
}

/// The phone holds a setup hold at `now_ms` by its latest report: paused, with the hold's end stored and ahead
/// (`DeviceStatus::paused_at` keeps it open up to that end).
pub fn in_hold(latest: &DeviceStatus, now_ms: i64) -> bool {
    latest.setup_hold_until_ms.is_some() && latest.paused_at(now_ms)
}

/// The need rule's inputs from the stored policy and the latest report's installed apps (§2.3).
pub fn inputs_of(policy: &DevicePolicy, latest: Option<&DeviceStatus>) -> Inputs {
    let map_app_installed = latest
        .and_then(|st| st.installed_apps_json.as_deref())
        .and_then(|json| serde_json::from_str::<Vec<Value>>(json).ok())
        .is_some_and(|apps| {
            apps.iter().any(|app| {
                app.get("package_name")
                    .and_then(Value::as_str)
                    .is_some_and(|p| MAP_APPS.contains(&p))
            })
        });
    Inputs {
        mask: policy.quick_controls_mask,
        clock_format: crate::models::clock_format(&policy.clock_format).to_string(),
        calls_managed: policy.calls_managed(),
        android_lock: crate::kid_lock::policy_kid_lock(policy)
            .is_some_and(|lock| lock.keyguard == crate::kid_lock::KEYGUARD_ANDROID),
        // Design 110 isn't built: its switch reads as off.
        map_links_on: false,
        map_app_installed,
    }
}

/// The server's readings (§2.2's "Reading" column), from the policy and the latest report. Without a report only
/// `parent_pin` is read.
pub fn readings_of(policy: &DevicePolicy, latest: Option<&DeviceStatus>) -> Readings {
    let mut readings = Readings::new();
    readings.insert(
        "parent_pin".to_string(),
        Some(policy.override_pin_hash.is_some()),
    );
    let Some(latest) = latest else {
        return readings;
    };
    let mut put = |key: &str, value: Option<Option<bool>>| {
        if let Some(value) = value {
            readings.insert(key.to_string(), value);
        }
    };
    let setup: Option<Map<String, Value>> = latest
        .setup_json
        .as_deref()
        .and_then(|json| serde_json::from_str(json).ok());
    for key in REPORT_READINGS {
        put(
            key,
            setup
                .as_ref()
                .and_then(|setup| setup.get(*key))
                .map(Value::as_bool),
        );
    }
    put(
        "notification_access",
        latest.notification_listener_enabled.map(Some),
    );
    put("background", latest.battery_optimization_exempt.map(Some));
    put(
        "vibb_notifications",
        crate::notification_channels::report(latest).map(|report| {
            Some(
                !crate::notification_channels::about(crate::i18n::Lang::En, policy, &report).orange,
            )
        }),
    );
    put("write_settings", latest.write_settings_granted.map(Some));
    if let Some(airplane) = crate::airplane_mode::report(latest) {
        put("assistant", Some(Some(airplane.assistant)));
        put("wifi_airplane", Some(Some(airplane.wifi_kept.is_some())));
        put(
            "default_apps",
            Some(Some(airplane.default_apps_changed.is_empty())),
        );
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
    if let (Some(screening), Some(redirection)) =
        (held("screening_role_held"), held("redirection_role_held"))
    {
        put("call_filter", Some(Some(screening && redirection)));
    }
    readings
}

/// The parent steps ticked for a phone, in table order.
pub async fn ticked(db: &sqlx::SqlitePool, device_id: i64) -> Result<Vec<String>, sqlx::Error> {
    let stored: Vec<String> =
        sqlx::query_scalar("SELECT step FROM device_setup_ticks WHERE device_id = ?")
            .bind(device_id)
            .fetch_all(db)
            .await?;
    Ok(STEPS
        .iter()
        .filter(|step| stored.iter().any(|key| key == step.key))
        .map(|step| step.key.to_string())
        .collect())
}

/// The policy's `setup` (§4.3): `{"ticked": [keys]}`, always sent.
#[derive(Clone, Debug, Default, serde::Serialize)]
pub struct SetupPolicy {
    pub ticked: Vec<String>,
}

/// The policy's `setup`; a read error fails the policy (the phone keeps its cache).
pub async fn policy(db: &sqlx::SqlitePool, device_id: i64) -> Result<SetupPolicy, sqlx::Error> {
    Ok(SetupPolicy {
        ticked: ticked(db, device_id).await?,
    })
}

/// Ticks `key` for a phone (`INSERT OR IGNORE`: a tick already there stays as it is). `false` when `key` isn't a
/// `parent` step.
pub async fn store_tick(
    db: &sqlx::SqlitePool,
    device_id: i64,
    key: &str,
    by_phone: bool,
) -> Result<bool, sqlx::Error> {
    if !step(key).is_some_and(|step| step.read_by == ReadBy::Parent) {
        return Ok(false);
    }
    sqlx::query(
        "INSERT OR IGNORE INTO device_setup_ticks (device_id, step, ticked_at, by_phone) \
         VALUES (?, ?, strftime('%Y-%m-%dT%H:%M:%fZ', 'now'), ?)",
    )
    .bind(device_id)
    .bind(key)
    .bind(by_phone)
    .execute(db)
    .await?;
    Ok(true)
}

/// `POST /api/devices/setup/ticks` (§4.4): the flow's "Gjort" on a `parent` step. `{"step": key}` -> 204 (ticked now
/// or before; the line in the security log and the nudge only for a new tick), 400 `{"error": "not_a_parent_step"}`
/// for anything else. The phone never unticks.
pub async fn tick(
    State(state): State<AppState>,
    Extension(AuthedDevice(device)): Extension<AuthedDevice>,
    body: axum::body::Bytes,
) -> Response {
    let refused = || {
        (
            StatusCode::BAD_REQUEST,
            axum::Json(json!({"error": "not_a_parent_step"})),
        )
            .into_response()
    };
    let Some(key) = serde_json::from_slice::<Value>(&body)
        .ok()
        .and_then(|body| body.get("step").and_then(Value::as_str).map(str::to_string))
    else {
        return refused();
    };
    let Some(step) = step(&key).filter(|step| step.read_by == ReadBy::Parent) else {
        return refused();
    };
    let new = match sqlx::query(
        "INSERT OR IGNORE INTO device_setup_ticks (device_id, step, ticked_at, by_phone) \
         VALUES (?, ?, strftime('%Y-%m-%dT%H:%M:%fZ', 'now'), 1)",
    )
    .bind(device.id)
    .bind(step.key)
    .execute(&state.db)
    .await
    {
        Ok(done) => done.rows_affected() > 0,
        Err(err) => {
            tracing::error!(device_id = device.id, %err, "couldn't store a setup tick");
            return StatusCode::INTERNAL_SERVER_ERROR.into_response();
        }
    };
    if new {
        let device_word = device.id.to_string();
        crate::security::record_phone_event_logged(
            &state.db,
            device.id,
            crate::security::PhoneLine::SetupTickPhone,
            crate::security::Detail::words(
                "security.detail.setup_tick.phone",
                &[("device", device_word.as_str())],
            )
            .word("step", step.name),
        )
        .await;
        let _ = state.command_notify.send(device.id);
    }
    StatusCode::NO_CONTENT.into_response()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn contract() -> Value {
        let text = std::fs::read_to_string(
            std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("testdata/setup_steps.json"),
        )
        .unwrap();
        serde_json::from_str(&text).unwrap()
    }

    fn wire_need(need: Need) -> &'static str {
        match need {
            Need::Required => "required",
            Need::Optional => "optional",
        }
    }

    fn wire_read_by(read_by: ReadBy) -> &'static str {
        match read_by {
            ReadBy::Phone => "phone",
            ReadBy::Server => "server",
            ReadBy::Parent => "parent",
        }
    }

    fn wire_when(when: When) -> &'static str {
        match when {
            When::Always => "always",
            When::CallsManaged => "calls_managed",
            When::WriteSettingsUsed => "write_settings_used",
            When::Airplane => "airplane",
            When::AirplaneRole => "airplane_role",
            When::AndroidLock => "android_lock",
            When::MapLinks => "map_links",
        }
    }

    #[test]
    fn the_steps_are_the_contracts() {
        let contract = contract();
        let steps = contract["steps"].as_array().unwrap();
        assert_eq!(steps.len(), STEPS.len());
        for (row, step) in steps.iter().zip(STEPS) {
            assert_eq!(row["key"], step.key);
            assert_eq!(row["need"], wire_need(step.need), "{}", step.key);
            assert_eq!(row["read_by"], wire_read_by(step.read_by), "{}", step.key);
            assert_eq!(row["when"], wire_when(step.when), "{}", step.key);
            let summary = match step.summary {
                Summary::Count => "count",
                Summary::OwnRow => "own_row",
            };
            assert_eq!(row["summary"], summary, "{}", step.key);
            let level = match step.level {
                Level::Action => "action",
                Level::Alarm => "alarm",
            };
            assert_eq!(row["level"], level, "{}", step.key);
            assert_eq!(row["features"], json!(step.features), "{}", step.key);
            assert_eq!(step.name, format!("setup.step.{}", step.key));
            assert_eq!(step.how, format!("setup.how.{}", step.key));
            assert_eq!(
                step.warn.is_some(),
                !step.features.is_empty(),
                "{}: a warning line exactly for a step with features",
                step.key
            );
            if let Some(warn) = step.warn {
                assert_eq!(warn, format!("setup.warn.{}", step.key));
            }
            for lang in [crate::i18n::Lang::Nb, crate::i18n::Lang::En] {
                assert_eq!(
                    row["names"][lang.code()],
                    crate::i18n::t(lang, step.name),
                    "{}'s name in {}",
                    step.key,
                    lang.code()
                );
            }
            // Required steps are never ticked (§4.4: no tick can hide one).
            assert!(step.read_by != ReadBy::Parent || step.need == Need::Optional);
        }
    }

    #[test]
    fn the_features_are_the_contracts() {
        let contract = contract();
        let rows = contract["features"].as_array().unwrap();
        assert_eq!(rows.len(), FEATURES.len());
        for (row, feature) in rows.iter().zip(FEATURES) {
            assert_eq!(row["key"], feature.key);
            assert_eq!(row["page"], feature.page);
            assert_eq!(row["anchor"], feature.anchor);
        }
        for step in STEPS {
            for key in step.features {
                assert!(
                    FEATURES.iter().any(|f| f.key == *key),
                    "{}: unknown feature {key}",
                    step.key
                );
            }
        }
    }

    fn inputs(row: &Value) -> Inputs {
        Inputs {
            mask: row["mask"].as_i64().unwrap(),
            clock_format: row["clock_format"].as_str().unwrap().to_string(),
            calls_managed: row["calls_managed"].as_bool().unwrap(),
            android_lock: row["android_lock"].as_bool().unwrap(),
            map_links_on: row["map_links_on"].as_bool().unwrap(),
            map_app_installed: row["map_app_installed"].as_bool().unwrap(),
        }
    }

    #[test]
    fn the_needed_rows() {
        let contract = contract();
        for row in contract["needed"].as_array().unwrap() {
            let name = row["name"].as_str().unwrap();
            let readings: Readings = row["readings"]
                .as_object()
                .unwrap()
                .iter()
                .map(|(key, value)| (key.clone(), value.as_bool()))
                .collect();
            let ticked: Vec<String> = row["ticked"]
                .as_array()
                .unwrap()
                .iter()
                .map(|key| key.as_str().unwrap().to_string())
                .collect();
            let missing = missing(&inputs(&row["inputs"]), &readings, &ticked);
            let keys: Vec<&str> = missing.iter().map(|step| step.key).collect();
            assert_eq!(json!(keys), row["missing"], "{name}: missing");
            assert_eq!(json!(count(&missing)), row["count"], "{name}: count");
            assert_eq!(json!(alarm(&missing)), row["alarm"], "{name}: alarm");
            assert_eq!(
                json!(setup_row(&missing, &readings)),
                row["setup_row"],
                "{name}: setup_row"
            );
        }
    }

    #[test]
    fn the_clean_rows() {
        let contract = contract();
        for row in contract["clean"].as_array().unwrap() {
            assert_eq!(
                clean_value(&row["in"]).unwrap_or(Value::Null),
                row["out"],
                "{}",
                row["in"]
            );
        }
        // The report rows survive as they are.
        for key in ["report", "report_no_hold"] {
            assert_eq!(clean_value(&contract[key]).unwrap(), contract[key], "{key}");
        }
    }

    #[test]
    fn the_hold_ends_at_the_earlier_clock() {
        let both = r#"{"hold_until_ms": 5000, "hold_left_ms": 1000}"#;
        assert_eq!(hold_until(Some(both), 3000), Some(4000));
        assert_eq!(hold_until(Some(both), 4500), Some(5000));
        assert_eq!(hold_until(Some(r#"{"hold_left_ms": 10}"#), 100), Some(110));
        assert_eq!(hold_until(Some(r#"{"hold_until_ms": 10}"#), 100), Some(10));
        assert_eq!(hold_until(Some(r#"{"device_owner": true}"#), 100), None);
        assert_eq!(hold_until(None, 100), None);
    }

    #[test]
    fn the_copies_are_identical() {
        let dir = std::path::Path::new(env!("CARGO_MANIFEST_DIR"));
        let ours = std::fs::read_to_string(dir.join("testdata/setup_steps.json")).unwrap();
        let launcher = dir.join("../launcher/app/src/test/resources/setup_steps.json");
        let theirs = std::fs::read_to_string(&launcher)
            .unwrap_or_else(|e| panic!("{}: {e}", launcher.display()));
        assert_eq!(ours, theirs, "testdata/setup_steps.json differs");
    }
}
