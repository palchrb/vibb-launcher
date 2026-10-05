//! "Time rules" (`/schedules`, handy step 6, docs/design/06-time-rules.md in the handy
//! workspace): named rules (school / bedtime / custom, per-weekday windows, calls on/off, exempt
//! apps) and a daily screen-time budget per weekday - a global set every device follows, plus an
//! explicit per-device override (`device_policy.custom_schedule_enabled`), the same pattern as the
//! old schedule page (migrations/0017_schedules_page.sql). The pre-step-6 weekday/weekend/bedtime
//! schedule was converted into rules once (`time_rules::migrate_legacy`).
//!
//! Every write validates first (400, nothing written), answers 404 for an unknown device or a rule
//! outside the form's scope, 500 on a DB error, and nudges the devices it affects (a global change:
//! every device).

use std::collections::{BTreeMap, HashMap};

use askama::Template;
use axum::Extension;
use axum::body::Bytes;
use axum::extract::{Path, Query, State};
use axum::http::StatusCode;
use axum::response::{Html, IntoResponse, Redirect, Response};

use crate::AppState;
use crate::models::{Device, DevicePolicy, GlobalSchedule, InstalledApp};
use crate::security::{self, CurrentAdmin};
use crate::time_rules::{
    self, DayWindow, MAX_EXEMPT_APPS, MAX_RULE_NAME_CHARS, RULE_KINDS, TimeRuleRow,
};

const DAY_NAMES: [&str; 7] = [
    "Monday",
    "Tuesday",
    "Wednesday",
    "Thursday",
    "Friday",
    "Saturday",
    "Sunday",
];
const DAY_SHORT: [&str; 7] = ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"];

struct DeviceOption {
    id: i64,
    name: String,
    selected: bool,
}

pub(crate) struct AppChoice {
    package: String,
    label: String,
    checked: bool,
}

struct DayInput {
    index: usize,
    name: &'static str,
    start: String,
    end: String,
}

pub(crate) struct RuleForm {
    /// 0 for the "add a rule" form.
    id: i64,
    name: String,
    kind: String,
    calls_allowed: bool,
    summary: String,
    days: Vec<DayInput>,
    apps: Vec<AppChoice>,
}

struct BudgetInput {
    index: usize,
    name: &'static str,
    value: String,
}

struct ScopeView {
    /// "" for global, otherwise the device id - posted back with every form.
    scope: String,
    rules: Vec<RuleForm>,
    new_rule: RuleForm,
    budget: Vec<BudgetInput>,
    /// The stored budget doesn't parse: shown, and the phone gets a 500 for its policy.
    budget_corrupt: bool,
    /// Rules whose stored data doesn't parse (shown by name only).
    corrupt_rules: Vec<String>,
}

struct SelectedDevice {
    id: i64,
    name: String,
    custom_enabled: bool,
    /// A calls-off rule applies to this device but its calls are unmanaged (QA step 6 #5).
    incoming_calls_unblocked: bool,
    view: ScopeView,
}

#[derive(Template)]
#[template(path = "schedules.html")]
struct SchedulesTemplate {
    title: String,
    global: ScopeView,
    devices: Vec<DeviceOption>,
    selected: Option<SelectedDevice>,
}

/// HTML `<input type="time">` "HH:MM" from minutes since midnight.
pub(crate) fn minutes_to_time_input(minutes: i64) -> String {
    format!("{:02}:{:02}", minutes / 60, minutes % 60)
}

/// Strict "HH:MM" (00:00-23:59) to minutes since midnight.
fn time_input_to_minutes(value: &str) -> Option<i64> {
    let (h, m) = value.trim().split_once(':')?;
    let (h, m) = (h.parse::<i64>().ok()?, m.parse::<i64>().ok()?);
    ((0..24).contains(&h) && (0..60).contains(&m)).then_some(h * 60 + m)
}

/// "Mon-Fri 08:15-14:00, Sat 10:00-12:00" - consecutive days with the same window are grouped.
pub(crate) fn days_summary(days: &[Option<DayWindow>]) -> String {
    let mut parts: Vec<String> = Vec::new();
    let mut d = 0;
    while d < days.len() {
        let Some(w) = days[d] else {
            d += 1;
            continue;
        };
        let mut last = d;
        while last + 1 < days.len() && days[last + 1] == Some(w) {
            last += 1;
        }
        let range = if last == d {
            DAY_SHORT[d].to_string()
        } else {
            format!("{}-{}", DAY_SHORT[d], DAY_SHORT[last])
        };
        let span = if w.start == w.end {
            format!("{} for 24 h", minutes_to_time_input(w.start))
        } else {
            let next = if w.start > w.end { " (next day)" } else { "" };
            format!(
                "{}-{}{next}",
                minutes_to_time_input(w.start),
                minutes_to_time_input(w.end)
            )
        };
        parts.push(format!("{range} {span}"));
        d = last + 1;
    }
    if parts.is_empty() {
        "No days set - never active".to_string()
    } else {
        parts.join(", ")
    }
}

fn day_inputs(days: &[Option<DayWindow>]) -> Vec<DayInput> {
    (0..7)
        .map(|i| {
            let w = days.get(i).copied().flatten();
            DayInput {
                index: i,
                name: DAY_NAMES[i],
                start: w
                    .map(|w| minutes_to_time_input(w.start))
                    .unwrap_or_default(),
                end: w.map(|w| minutes_to_time_input(w.end)).unwrap_or_default(),
            }
        })
        .collect()
}

/// The apps a rule may exempt: what the devices in scope reported installed, plus whatever the
/// rule already has (so a choice never silently disappears from the form).
fn app_choices(available: &BTreeMap<String, String>, chosen: &[String]) -> Vec<AppChoice> {
    let mut all = available.clone();
    for package in chosen {
        all.entry(package.clone())
            .or_insert_with(|| package.clone());
    }
    let mut choices: Vec<AppChoice> = all
        .into_iter()
        .map(|(package, label)| AppChoice {
            checked: chosen.contains(&package),
            package,
            label,
        })
        .collect();
    choices.sort_by_key(|a| a.label.to_lowercase());
    choices
}

/// package -> label from the latest installed-apps report of each device in `device_ids`.
async fn reported_apps(state: &AppState, device_ids: &[i64]) -> BTreeMap<String, String> {
    let mut apps = BTreeMap::new();
    for id in device_ids {
        let json: Option<String> = sqlx::query_scalar(
            "SELECT installed_apps_json FROM device_status WHERE device_id = ? \
             AND installed_apps_json IS NOT NULL ORDER BY reported_at DESC, id DESC LIMIT 1",
        )
        .bind(id)
        .fetch_optional(&state.db)
        .await
        .ok()
        .flatten();
        let installed: Vec<InstalledApp> = json
            .as_deref()
            .and_then(|j| serde_json::from_str(j).ok())
            .unwrap_or_default();
        for app in installed {
            apps.entry(app.package_name).or_insert(app.label);
        }
    }
    apps
}

fn scope_view(
    scope: String,
    rows: &[TimeRuleRow],
    budget_json: &str,
    available: &BTreeMap<String, String>,
) -> ScopeView {
    let mut rules = Vec::new();
    let mut corrupt_rules = Vec::new();
    for row in rows {
        match row.to_policy() {
            Ok(rule) => rules.push(RuleForm {
                id: rule.id,
                summary: days_summary(&rule.days),
                days: day_inputs(&rule.days),
                apps: app_choices(available, &rule.exempt_apps),
                name: rule.name,
                kind: rule.kind,
                calls_allowed: rule.calls_allowed,
            }),
            Err(_) => corrupt_rules.push(row.name.clone()),
        }
    }
    let parsed_budget = time_rules::parse_budget(budget_json);
    let budget_values = parsed_budget.clone().unwrap_or_else(|_| vec![None; 7]);
    ScopeView {
        scope,
        rules,
        new_rule: RuleForm {
            id: 0,
            name: String::new(),
            kind: "school".to_string(),
            calls_allowed: false,
            summary: String::new(),
            days: day_inputs(&[]),
            apps: app_choices(available, &[]),
        },
        budget: (0..7)
            .map(|i| BudgetInput {
                index: i,
                name: DAY_NAMES[i],
                value: budget_values[i].map(|m| m.to_string()).unwrap_or_default(),
            })
            .collect(),
        budget_corrupt: parsed_budget.is_err(),
        corrupt_rules,
    }
}

fn server_error(err: impl std::fmt::Display, what: &str) -> Response {
    tracing::error!(%err, "{what}");
    (
        StatusCode::INTERNAL_SERVER_ERROR,
        "Couldn't save - nothing was changed. Check the server log.",
    )
        .into_response()
}

fn bad_request(message: impl Into<String>) -> Response {
    (StatusCode::BAD_REQUEST, message.into()).into_response()
}

pub async fn show_schedules(
    State(state): State<AppState>,
    Query(params): Query<HashMap<String, String>>,
) -> Response {
    let global = sqlx::query_as::<_, GlobalSchedule>("SELECT * FROM global_schedule WHERE id = 1")
        .fetch_optional(&state.db)
        .await;
    let all_devices = sqlx::query_as::<_, Device>("SELECT * FROM devices ORDER BY name")
        .fetch_all(&state.db)
        .await;
    let global_rules = time_rules::effective_rule_rows(&state.db, 0, false).await;
    let (Ok(global), Ok(all_devices), Ok(global_rules)) = (global, all_devices, global_rules)
    else {
        return (
            StatusCode::INTERNAL_SERVER_ERROR,
            "Couldn't read the time rules",
        )
            .into_response();
    };
    let global_budget = global
        .map(|g| g.daily_budget_json)
        .unwrap_or_else(|| time_rules::UNLIMITED_BUDGET_JSON.to_string());

    let all_ids: Vec<i64> = all_devices.iter().map(|d| d.id).collect();
    let global_apps = reported_apps(&state, &all_ids).await;
    let global_view = scope_view(String::new(), &global_rules, &global_budget, &global_apps);

    let selected_id: Option<i64> = params.get("device").and_then(|v| v.parse().ok());
    let devices = all_devices
        .iter()
        .map(|d| DeviceOption {
            id: d.id,
            name: d.name.clone(),
            selected: Some(d.id) == selected_id,
        })
        .collect();

    let mut selected = None;
    if let Some(device) = all_devices.into_iter().find(|d| Some(d.id) == selected_id) {
        let policy =
            sqlx::query_as::<_, DevicePolicy>("SELECT * FROM device_policy WHERE device_id = ?")
                .bind(device.id)
                .fetch_optional(&state.db)
                .await;
        let rules = time_rules::effective_rule_rows(&state.db, device.id, true).await;
        let (Ok(policy), Ok(rules)) = (policy, rules) else {
            return (
                StatusCode::INTERNAL_SERVER_ERROR,
                "Couldn't read the time rules",
            )
                .into_response();
        };
        let policy = policy.unwrap_or_default();
        let applied =
            time_rules::effective_rule_rows(&state.db, device.id, policy.custom_schedule_enabled)
                .await
                .unwrap_or_default();
        let incoming_calls_unblocked =
            !policy.calls_managed && applied.iter().any(|r| !r.calls_allowed);
        let apps = reported_apps(&state, &[device.id]).await;
        selected = Some(SelectedDevice {
            id: device.id,
            name: device.name,
            custom_enabled: policy.custom_schedule_enabled,
            incoming_calls_unblocked,
            view: scope_view(
                device.id.to_string(),
                &rules,
                &policy.daily_budget_json,
                &apps,
            ),
        });
    }

    Html(
        SchedulesTemplate {
            title: "Time rules".to_string(),
            global: global_view,
            devices,
            selected,
        }
        .render()
        .unwrap(),
    )
    .into_response()
}

/// Nudges the devices a change in `scope` affects: one device, or every device for a global one.
async fn nudge(state: &AppState, scope: Option<i64>) {
    match scope {
        Some(id) => {
            let _ = state.command_notify.send(id);
        }
        None => match time_rules::all_device_ids(&state.db).await {
            Ok(ids) => {
                for id in ids {
                    let _ = state.command_notify.send(id);
                }
            }
            Err(err) => tracing::error!(%err, "couldn't list devices to nudge"),
        },
    }
}

fn redirect_for(scope: Option<i64>) -> Response {
    match scope {
        Some(id) => Redirect::to(&format!("/schedules?device={id}")).into_response(),
        None => Redirect::to("/schedules").into_response(),
    }
}

/// A validated rule form.
#[derive(Debug, PartialEq, Eq)]
pub(crate) struct RuleInput {
    pub name: String,
    pub kind: String,
    pub calls_allowed: bool,
    pub exempt_apps: Vec<String>,
    pub days: Vec<Option<DayWindow>>,
}

fn field<'a>(fields: &'a [(String, String)], key: &str) -> &'a str {
    fields
        .iter()
        .find(|(k, _)| k == key)
        .map(|(_, v)| v.as_str())
        .unwrap_or("")
}

pub(crate) fn parse_rule_form(fields: &[(String, String)]) -> Result<RuleInput, String> {
    let name = field(fields, "name").trim().to_string();
    if name.is_empty() || name.chars().count() > MAX_RULE_NAME_CHARS {
        return Err(format!(
            "A rule needs a name of 1-{MAX_RULE_NAME_CHARS} characters."
        ));
    }
    let kind = field(fields, "kind").to_string();
    if !RULE_KINDS.contains(&kind.as_str()) {
        return Err("Unknown kind of rule.".to_string());
    }
    let mut exempt_apps: Vec<String> = Vec::new();
    for (k, v) in fields {
        if k == "exempt_apps" && !exempt_apps.contains(v) {
            if !time_rules::valid_package_name(v) {
                return Err(format!("{v:?} isn't an app package name."));
            }
            exempt_apps.push(v.clone());
        }
    }
    if exempt_apps.len() > MAX_EXEMPT_APPS {
        return Err(format!("At most {MAX_EXEMPT_APPS} exempt apps."));
    }
    let mut days = Vec::with_capacity(7);
    for (i, day) in DAY_NAMES.iter().enumerate() {
        let start = field(fields, &format!("d{i}_start")).trim();
        let end = field(fields, &format!("d{i}_end")).trim();
        if start.is_empty() && end.is_empty() {
            days.push(None);
            continue;
        }
        match (time_input_to_minutes(start), time_input_to_minutes(end)) {
            (Some(start), Some(end)) => days.push(Some(DayWindow { start, end })),
            _ => {
                return Err(format!(
                    "{day}: give both a start and an end time, or leave both empty."
                ));
            }
        }
    }
    Ok(RuleInput {
        calls_allowed: fields.iter().any(|(k, _)| k == "calls_allowed"),
        name,
        kind,
        exempt_apps,
        days,
    })
}

/// `scope` from a form: "" = global, otherwise a device id that must exist (else `Err(404)`).
async fn parse_scope(state: &AppState, raw: &str) -> Result<Option<i64>, Box<Response>> {
    let raw = raw.trim();
    if raw.is_empty() {
        return Ok(None);
    }
    let Ok(id) = raw.parse::<i64>() else {
        return Err(Box::new(bad_request("Unknown scope")));
    };
    match sqlx::query_scalar::<_, i64>("SELECT device_id FROM device_policy WHERE device_id = ?")
        .bind(id)
        .fetch_optional(&state.db)
        .await
    {
        Ok(Some(_)) => Ok(Some(id)),
        Ok(None) => Err(Box::new(
            (StatusCode::NOT_FOUND, "Device not found").into_response(),
        )),
        Err(err) => Err(Box::new(server_error(err, "failed to read a device"))),
    }
}

/// Parent edits to time rules and budgets go to the security log, like lifts (QA step 6 #9).
async fn log_change(
    state: &AppState,
    admin: &crate::models::AdminUser,
    event: &str,
    scope: Option<i64>,
    what: &str,
) {
    let scope = scope.map_or("global".to_string(), |id| format!("device {id}"));
    security::record_security_event(
        &state.db,
        event,
        Some(&admin.username),
        None,
        Some(&format!("{scope}: {what}")),
    )
    .await;
}

fn form_pairs(body: &Bytes) -> Vec<(String, String)> {
    form_urlencoded::parse(body).into_owned().collect()
}

pub async fn create_rule(
    State(state): State<AppState>,
    Extension(CurrentAdmin(admin)): Extension<CurrentAdmin>,
    body: Bytes,
) -> Response {
    let fields = form_pairs(&body);
    let scope = match parse_scope(&state, field(&fields, "scope")).await {
        Ok(scope) => scope,
        Err(response) => return *response,
    };
    let input = match parse_rule_form(&fields) {
        Ok(input) => input,
        Err(message) => return bad_request(message),
    };
    let result = sqlx::query(
        "INSERT INTO time_rules (device_id, name, kind, calls_allowed, exempt_apps_json, \
         days_json, sort_order) VALUES (?, ?, ?, ?, ?, ?, \
         (SELECT COALESCE(MAX(sort_order), -1) + 1 FROM time_rules WHERE device_id IS ?))",
    )
    .bind(scope)
    .bind(&input.name)
    .bind(&input.kind)
    .bind(input.calls_allowed)
    .bind(serde_json::to_string(&input.exempt_apps).expect("strings serialize"))
    .bind(serde_json::to_string(&input.days).expect("windows serialize"))
    .bind(scope)
    .execute(&state.db)
    .await;
    if let Err(err) = result {
        return server_error(err, "failed to add a time rule");
    }
    log_change(&state, &admin, "time_rule_created", scope, &input.name).await;
    nudge(&state, scope).await;
    redirect_for(scope)
}

/// The rule, if it exists and belongs to `scope` (a device's own rule can't be edited through the
/// global form and vice versa).
async fn rule_in_scope(
    state: &AppState,
    rule_id: i64,
    scope: Option<i64>,
) -> Result<TimeRuleRow, Box<Response>> {
    match sqlx::query_as::<_, TimeRuleRow>("SELECT * FROM time_rules WHERE id = ?")
        .bind(rule_id)
        .fetch_optional(&state.db)
        .await
    {
        Ok(Some(rule)) if rule.device_id == scope => Ok(rule),
        Ok(_) => Err(Box::new(
            (StatusCode::NOT_FOUND, "Rule not found").into_response(),
        )),
        Err(err) => Err(Box::new(server_error(err, "failed to read a time rule"))),
    }
}

pub async fn update_rule(
    State(state): State<AppState>,
    Extension(CurrentAdmin(admin)): Extension<CurrentAdmin>,
    Path(rule_id): Path<i64>,
    body: Bytes,
) -> Response {
    let fields = form_pairs(&body);
    let scope = match parse_scope(&state, field(&fields, "scope")).await {
        Ok(scope) => scope,
        Err(response) => return *response,
    };
    if let Err(response) = rule_in_scope(&state, rule_id, scope).await {
        return *response;
    }
    let input = match parse_rule_form(&fields) {
        Ok(input) => input,
        Err(message) => return bad_request(message),
    };
    let result = sqlx::query(
        "UPDATE time_rules SET name = ?, kind = ?, calls_allowed = ?, exempt_apps_json = ?, \
         days_json = ? WHERE id = ? AND device_id IS ?",
    )
    .bind(&input.name)
    .bind(&input.kind)
    .bind(input.calls_allowed)
    .bind(serde_json::to_string(&input.exempt_apps).expect("strings serialize"))
    .bind(serde_json::to_string(&input.days).expect("windows serialize"))
    .bind(rule_id)
    .bind(scope)
    .execute(&state.db)
    .await;
    if let Err(err) = result {
        return server_error(err, "failed to save a time rule");
    }
    log_change(&state, &admin, "time_rule_updated", scope, &input.name).await;
    nudge(&state, scope).await;
    redirect_for(scope)
}

pub async fn delete_rule(
    State(state): State<AppState>,
    Extension(CurrentAdmin(admin)): Extension<CurrentAdmin>,
    Path(rule_id): Path<i64>,
    body: Bytes,
) -> Response {
    let fields = form_pairs(&body);
    let scope = match parse_scope(&state, field(&fields, "scope")).await {
        Ok(scope) => scope,
        Err(response) => return *response,
    };
    let rule = match rule_in_scope(&state, rule_id, scope).await {
        Ok(rule) => rule,
        Err(response) => return *response,
    };
    if let Err(err) = sqlx::query("DELETE FROM time_rules WHERE id = ? AND device_id IS ?")
        .bind(rule_id)
        .bind(scope)
        .execute(&state.db)
        .await
    {
        return server_error(err, "failed to delete a time rule");
    }
    log_change(&state, &admin, "time_rule_deleted", scope, &rule.name).await;
    nudge(&state, scope).await;
    redirect_for(scope)
}

/// `b0`..`b6` (Monday first): minutes 0-1440, blank = unlimited.
pub(crate) fn parse_budget_form(fields: &[(String, String)]) -> Result<String, String> {
    let mut budget: Vec<Option<i64>> = Vec::with_capacity(7);
    for (i, day) in DAY_NAMES.iter().enumerate() {
        let raw = field(fields, &format!("b{i}")).trim();
        if raw.is_empty() {
            budget.push(None);
            continue;
        }
        match raw.parse::<i64>() {
            Ok(m) if (0..=1440).contains(&m) => budget.push(Some(m)),
            _ => return Err(format!("{day}: screen time is 0-1440 minutes, or empty.")),
        }
    }
    Ok(serde_json::to_string(&budget).expect("numbers serialize"))
}

/// The global screen-time budget.
pub async fn save_global_schedule(
    State(state): State<AppState>,
    Extension(CurrentAdmin(admin)): Extension<CurrentAdmin>,
    body: Bytes,
) -> Response {
    let budget = match parse_budget_form(&form_pairs(&body)) {
        Ok(budget) => budget,
        Err(message) => return bad_request(message),
    };
    if let Err(err) = sqlx::query(
        "UPDATE global_schedule SET daily_budget_json = ?, updated_at = datetime('now') \
         WHERE id = 1",
    )
    .bind(&budget)
    .execute(&state.db)
    .await
    {
        return server_error(err, "failed to save the global screen time");
    }
    log_change(&state, &admin, "screen_time_saved", None, &budget).await;
    nudge(&state, None).await;
    redirect_for(None)
}

/// A device's override switch and its own budget.
pub async fn save_device_schedule(
    State(state): State<AppState>,
    Extension(CurrentAdmin(admin)): Extension<CurrentAdmin>,
    Path(id): Path<i64>,
    body: Bytes,
) -> Response {
    let fields = form_pairs(&body);
    let budget = match parse_budget_form(&fields) {
        Ok(budget) => budget,
        Err(message) => return bad_request(message),
    };
    let custom_enabled = fields.iter().any(|(k, _)| k == "custom_schedule_enabled");
    match sqlx::query(
        "UPDATE device_policy SET custom_schedule_enabled = ?, daily_budget_json = ?, \
         updated_at = datetime('now') WHERE device_id = ?",
    )
    .bind(custom_enabled)
    .bind(&budget)
    .bind(id)
    .execute(&state.db)
    .await
    {
        Ok(done) if done.rows_affected() == 0 => {
            (StatusCode::NOT_FOUND, "Device not found").into_response()
        }
        Ok(_) => {
            let detail = format!(
                "own rules {}, budget {budget}",
                if custom_enabled { "on" } else { "off" }
            );
            log_change(&state, &admin, "screen_time_saved", Some(id), &detail).await;
            nudge(&state, Some(id)).await;
            redirect_for(Some(id))
        }
        Err(err) => server_error(err, "failed to save a device's time rules"),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn pairs(list: &[(&str, &str)]) -> Vec<(String, String)> {
        list.iter()
            .map(|(k, v)| (k.to_string(), v.to_string()))
            .collect()
    }

    #[test]
    fn summary_groups_days() {
        let w = Some(DayWindow {
            start: 495,
            end: 840,
        });
        let days = vec![w, w, w, w, w, None, None];
        assert_eq!(days_summary(&days), "Mon-Fri 08:15-14:00");
        let bed = vec![
            Some(DayWindow {
                start: 1260,
                end: 420
            });
            7
        ];
        assert_eq!(days_summary(&bed), "Mon-Sun 21:00-07:00 (next day)");
        assert_eq!(days_summary(&[None; 7]), "No days set - never active");
    }

    #[test]
    fn rule_form_validation() {
        let ok = parse_rule_form(&pairs(&[
            ("name", " Skole "),
            ("kind", "school"),
            ("exempt_apps", "org.fossify.calendar"),
            ("exempt_apps", "org.fossify.calendar"),
            ("d0_start", "08:15"),
            ("d0_end", "14:00"),
        ]))
        .unwrap();
        assert_eq!(ok.name, "Skole");
        assert!(!ok.calls_allowed);
        assert_eq!(ok.exempt_apps, vec!["org.fossify.calendar"]);
        assert_eq!(
            ok.days[0],
            Some(DayWindow {
                start: 495,
                end: 840
            })
        );
        assert!(ok.days[1..].iter().all(Option::is_none));

        for bad in [
            vec![("name", ""), ("kind", "school")],
            vec![("name", "x"), ("kind", "nap")],
            vec![("name", "x"), ("kind", "custom"), ("d2_start", "08:00")],
            vec![
                ("name", "x"),
                ("kind", "custom"),
                ("d2_start", "25:00"),
                ("d2_end", "08:00"),
            ],
            vec![("name", "x"), ("kind", "custom"), ("exempt_apps", "nope")],
        ] {
            assert!(parse_rule_form(&pairs(&bad)).is_err(), "{bad:?}");
        }
        let long = "x".repeat(61);
        assert!(parse_rule_form(&pairs(&[("name", &long), ("kind", "custom")])).is_err());
    }

    #[test]
    fn budget_form_validation() {
        assert_eq!(
            parse_budget_form(&pairs(&[("b0", "60"), ("b6", "0")])).unwrap(),
            "[60,null,null,null,null,null,0]"
        );
        assert!(parse_budget_form(&pairs(&[("b0", "1441")])).is_err());
        assert!(parse_budget_form(&pairs(&[("b3", "lots")])).is_err());
    }
}
