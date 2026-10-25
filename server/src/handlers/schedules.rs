//! Time rules (handy step 6, docs/design/06-time-rules.md in the handy workspace): named rules
//! (school / bedtime / custom, per-weekday windows, calls on/off, exempt apps) and a daily
//! screen-time budget per weekday - a global set every device follows, plus an explicit per-device
//! override (`device_policy.custom_schedule_enabled`). The pre-step-6 weekday/weekend/bedtime
//! schedule was converted into rules once (`time_rules::migrate_legacy`).
//!
//! The pages (design 31 §3.5, step 9):
//! - **Settings › Time Rules** (`GET /schedules`, `schedules.html`): everyone's rules as rows, the
//!   daily limit's row, a row per phone. Links only, no form. `/schedules?device=X` (an old
//!   bookmark) redirects to the phone's Time page (L5).
//! - **Daily Limit** (`GET /schedules/screen-time`, `schedule_budget.html`): everyone's 7 limits,
//!   one form (`POST /schedules/global`).
//! - **The rule editor** (`GET /schedules/rules/{id}`, chained with its POST; `GET
//!   /schedules/rules/new`, `?device=X` for a phone's own rule; `schedule_rule.html`): one form with
//!   Save at the top, the exempt apps in a `<details>` inside it, Delete Rule… behind a confirm.
//! - **Device › Time** (`GET /devices/{id}/time`, `device_time.html`): what the phone last said, extra
//!   time, Pause a rule, the recent lifts (`handlers::lifts`, `back=time`), the own-rules switch, the
//!   phone's own rules and its own daily limits.
//!
//! Where the forms return (§5.1): a rule's save to its own page's form (`#rule`: Save sits at the
//! top, and scroll-restore keeps the place), a new rule to its new page, a delete to the list it was in
//! (`/schedules#rules`, a phone's own `/devices/{id}/time#own-rules`) with the flash there; a save or
//! delete of a rule deleted in another tab lands on that list with nothing written. The daily limit
//! to `/schedules/screen-time?saved=1#screen-time`. `/schedules/device/{id}` takes `section` (§5.6,
//! H1): `switch` writes only the own-rules switch (→ `#rules`), `budget` only the limits (→
//! `?saved=budget#screen-time`), none both (an older tab). A refused rule or limit renders its page
//! again (400) with what was typed and the reason under the field, which is focused.
//!
//! Every write validates first (nothing written on a refusal), answers 404 for an unknown device or
//! a rule outside the form's scope, 500 on a DB error, and nudges the devices it affects (a global
//! change: every device).

use std::collections::{BTreeMap, HashMap};

use askama::Template;
use axum::Extension;
use axum::body::Bytes;
use axum::extract::{Path, Query, State};
use axum::http::StatusCode;
use axum::response::{Html, IntoResponse, Redirect, Response};
use chrono::Utc;

use crate::AppState;
use crate::display_time::Zone;
use crate::handlers::device_pages::Note;
use crate::models::{Device, DevicePolicy, DeviceStatus, InstalledApp};
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

/// HTML `<input type="time">` "HH:MM" from minutes since midnight.
pub(crate) fn minutes_to_time_input(minutes: i64) -> String {
    format!("{:02}:{:02}", minutes / 60, minutes % 60)
}

/// Strict "HH:MM" (00:00-23:59) to minutes since midnight.
fn time_input_to_minutes(value: &str) -> Option<i64> {
    let (h, m) = value.trim().split_once(':')?;
    let digits = |t: &str| t.len() == 2 && t.bytes().all(|b| b.is_ascii_digit());
    if !digits(h) || !digits(m) {
        return None;
    }
    let (h, m) = (h.parse::<i64>().ok()?, m.parse::<i64>().ok()?);
    ((0..24).contains(&h) && (0..60).contains(&m)).then_some(h * 60 + m)
}

/// Runs of equal consecutive days, as "Mon-Fri" / "Sat" / "Every day" with their value.
fn day_runs<T: PartialEq + Copy>(days: &[T]) -> Vec<(String, T)> {
    let mut runs = Vec::new();
    let mut d = 0;
    while d < days.len() {
        let mut last = d;
        while last + 1 < days.len() && days[last + 1] == days[d] {
            last += 1;
        }
        let range = if d == 0 && last == 6 {
            "Every day".to_string()
        } else if last == d {
            DAY_SHORT[d].to_string()
        } else {
            format!("{}-{}", DAY_SHORT[d], DAY_SHORT[last])
        };
        runs.push((range, days[d]));
        d = last + 1;
    }
    runs
}

/// "Mon-Fri 08:15-14:30, Sat 10:00-12:00", "Every day 21:00-07:00" (an end before the start is
/// the next morning), "Sat 00:00 for 24 h", "Never" for a rule without days.
pub(crate) fn days_summary(days: &[Option<DayWindow>]) -> String {
    let parts: Vec<String> = day_runs(days)
        .into_iter()
        .filter_map(|(range, w)| {
            let w = w?;
            let span = if w.start == w.end {
                format!("{} for 24 h", minutes_to_time_input(w.start))
            } else {
                format!(
                    "{}-{}",
                    minutes_to_time_input(w.start),
                    minutes_to_time_input(w.end)
                )
            };
            Some(format!("{range} {span}"))
        })
        .collect();
    if parts.is_empty() {
        "Never".to_string()
    } else {
        parts.join(", ")
    }
}

/// "Mon-Fri 90 min · Sat-Sun 2 h", "Every day 45 min", "No limit".
pub(crate) fn budget_summary(budget: &[Option<i64>]) -> String {
    if budget.iter().all(Option::is_none) {
        return "No limit".to_string();
    }
    day_runs(budget)
        .into_iter()
        .map(|(range, minutes)| match minutes {
            Some(m) if m >= 60 && m % 60 == 0 => format!("{range} {} h", m / 60),
            Some(m) => format!("{range} {m} min"),
            None => format!("{range} no limit"),
        })
        .collect::<Vec<_>>()
        .join(" · ")
}

/// One rule as a navigation row: "Skole" over "Mon-Fri 08:15-14:30 · calls off".
pub(crate) struct RuleRow {
    pub id: i64,
    pub name: String,
    pub summary: String,
    /// The stored rule doesn't read: the phones get an error until it is saved again or deleted.
    pub corrupt: bool,
}

fn rule_rows(rows: &[TimeRuleRow]) -> Vec<RuleRow> {
    rows.iter()
        .map(|row| match row.to_policy() {
            Ok(rule) => RuleRow {
                id: rule.id,
                summary: format!(
                    "{} · {}",
                    days_summary(&rule.days),
                    if rule.calls_allowed {
                        "calls on"
                    } else {
                        "calls off"
                    }
                ),
                name: rule.name,
                corrupt: false,
            },
            Err(_) => RuleRow {
                id: row.id,
                name: row.name.clone(),
                summary: "Can't be read".to_string(),
                corrupt: true,
            },
        })
        .collect()
}

/// The flash in a rules list after a delete or a save of a gone rule (`?notice=`), as `(text,
/// refused)`; `None` for a code the page doesn't know.
fn rules_notice(code: Option<&str>) -> Option<(&'static str, bool)> {
    match code? {
        "deleted" => Some(("Rule deleted.", false)),
        "gone" => Some(("That rule was already deleted.", false)),
        "gone_unsaved" => Some((
            "That rule was deleted in the meantime, so nothing was saved.",
            true,
        )),
        _ => None,
    }
}

fn read_failed(err: sqlx::Error, what: &str) -> Response {
    tracing::error!(%err, "can't read {what}");
    (
        StatusCode::INTERNAL_SERVER_ERROR,
        "Couldn't read the time rules - check the server log.",
    )
        .into_response()
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

fn render(template: impl Template, status: StatusCode) -> Response {
    match template.render() {
        Ok(html) => (status, Html(html)).into_response(),
        Err(err) => server_error(err, "failed to render a time page"),
    }
}

// ------------------------------------------------------------------------------------------------
// Settings › Time Rules
// ------------------------------------------------------------------------------------------------

/// A phone on the Time Rules page: its Time page, and whether it follows its own rules.
struct PhoneRow {
    id: i64,
    name: String,
    initial: String,
    avatar: &'static str,
    photo: Option<String>,
    own: bool,
}

#[derive(Template)]
#[template(path = "schedules.html")]
struct SchedulesTemplate {
    title: String,
    rules: Vec<RuleRow>,
    /// Everyone's daily limit, `None` when the stored one doesn't read.
    budget: Option<String>,
    phones: Vec<PhoneRow>,
    notice: Option<(&'static str, bool)>,
}

/// GET `/schedules`: Settings › Time Rules. `?device=X` redirects to that phone's Time page.
pub async fn show_schedules(
    State(state): State<AppState>,
    Query(params): Query<HashMap<String, String>>,
) -> Response {
    if let Some(id) = params
        .get("device")
        .and_then(|v| v.trim().parse::<i64>().ok())
    {
        return Redirect::to(&format!("/devices/{id}/time")).into_response();
    }
    let budget_json: Option<String> =
        match sqlx::query_scalar("SELECT daily_budget_json FROM global_schedule WHERE id = 1")
            .fetch_optional(&state.db)
            .await
        {
            Ok(json) => json,
            Err(err) => return read_failed(err, "the global screen time"),
        };
    let rules = match time_rules::effective_rule_rows(&state.db, 0, false).await {
        Ok(rules) => rules,
        Err(err) => return read_failed(err, "the global rules"),
    };
    let devices = match sqlx::query_as::<_, Device>("SELECT * FROM devices ORDER BY name")
        .fetch_all(&state.db)
        .await
    {
        Ok(devices) => devices,
        Err(err) => return read_failed(err, "the devices"),
    };
    let own: Vec<i64> = match sqlx::query_scalar(
        "SELECT device_id FROM device_policy WHERE custom_schedule_enabled = 1",
    )
    .fetch_all(&state.db)
    .await
    {
        Ok(own) => own,
        Err(err) => return read_failed(err, "the own-rules switches"),
    };
    let budget_json = budget_json.unwrap_or_else(|| time_rules::UNLIMITED_BUDGET_JSON.to_string());
    render(
        SchedulesTemplate {
            title: "Time Rules".to_string(),
            rules: rule_rows(&rules),
            budget: time_rules::parse_budget(&budget_json)
                .ok()
                .map(|b| budget_summary(&b)),
            phones: devices
                .iter()
                .map(|d| PhoneRow {
                    id: d.id,
                    name: d.name.clone(),
                    initial: crate::handlers::devices::initial(&d.name),
                    avatar: crate::handlers::devices::avatar_class(d),
                    photo: crate::handlers::devices::avatar_photo(d),
                    own: own.contains(&d.id),
                })
                .collect(),
            notice: rules_notice(params.get("notice").map(String::as_str)),
        },
        StatusCode::OK,
    )
}

// ------------------------------------------------------------------------------------------------
// Daily limits (everyone's page and a phone's own group share the rows)
// ------------------------------------------------------------------------------------------------

/// One day's limit field; `error` is the refusal's reason under it.
pub(crate) struct BudgetInput {
    index: usize,
    name: &'static str,
    value: String,
    error: Option<String>,
}

/// The 7 fields from a stored budget (a corrupt one shows empty fields - its warning says so).
fn budget_inputs(json: &str) -> Vec<BudgetInput> {
    let values = time_rules::parse_budget(json).unwrap_or_else(|_| vec![None; 7]);
    (0..7)
        .map(|i| BudgetInput {
            index: i,
            name: DAY_NAMES[i],
            value: values[i].map(|m| m.to_string()).unwrap_or_default(),
            error: None,
        })
        .collect()
}

/// The 7 fields as typed, the refused one with its reason.
fn budget_entered(fields: &[(String, String)], error: &(usize, String)) -> Vec<BudgetInput> {
    (0..7)
        .map(|i| BudgetInput {
            index: i,
            name: DAY_NAMES[i],
            value: field(fields, &format!("b{i}"))
                .trim()
                .chars()
                .take(8)
                .collect(),
            error: (error.0 == i).then(|| error.1.clone()),
        })
        .collect()
}

#[derive(Template)]
#[template(path = "schedule_budget.html")]
struct BudgetTemplate {
    title: String,
    budget: Vec<BudgetInput>,
    corrupt: bool,
    saved: bool,
}

/// A refused limits form: the fields as posted, and the refused day with its reason.
type RefusedBudget<'a> = (&'a [(String, String)], (usize, String));

async fn render_budget(
    state: &AppState,
    entered: Option<RefusedBudget<'_>>,
    saved: bool,
) -> Response {
    let json: Option<String> =
        match sqlx::query_scalar("SELECT daily_budget_json FROM global_schedule WHERE id = 1")
            .fetch_optional(&state.db)
            .await
        {
            Ok(json) => json,
            Err(err) => return read_failed(err, "the global screen time"),
        };
    let json = json.unwrap_or_else(|| time_rules::UNLIMITED_BUDGET_JSON.to_string());
    let refused = entered.is_some();
    render(
        BudgetTemplate {
            title: "Daily Limit".to_string(),
            corrupt: time_rules::parse_budget(&json).is_err(),
            budget: match &entered {
                Some((fields, error)) => budget_entered(fields, error),
                None => budget_inputs(&json),
            },
            saved: saved && !refused,
        },
        if refused {
            StatusCode::BAD_REQUEST
        } else {
            StatusCode::OK
        },
    )
}

/// GET `/schedules/screen-time`: everyone's daily limits.
pub async fn show_budget(
    State(state): State<AppState>,
    Query(params): Query<HashMap<String, String>>,
) -> Response {
    render_budget(&state, None, params.contains_key("saved")).await
}

// ------------------------------------------------------------------------------------------------
// The rule editor
// ------------------------------------------------------------------------------------------------

/// An app the rule may leave usable.
pub(crate) struct AppChoice {
    package: String,
    label: String,
    checked: bool,
}

struct DayInput {
    index: usize,
    name: &'static str,
    short: &'static str,
    start: String,
    end: String,
    error: Option<String>,
}

/// The editor's fields, from the stored rule or as typed.
struct RuleForm {
    /// 0 for a new rule.
    id: i64,
    name: String,
    kind: String,
    calls_allowed: bool,
    days: Vec<DayInput>,
    apps: Vec<AppChoice>,
    name_error: Option<String>,
    apps_error: Option<String>,
}

impl RuleForm {
    /// "Klokke, Kalkulator", "None".
    fn apps_value(&self) -> String {
        let chosen: Vec<&str> = self
            .apps
            .iter()
            .filter(|a| a.checked)
            .map(|a| a.label.as_str())
            .collect();
        match chosen.len() {
            0 => "None".to_string(),
            1 | 2 => chosen.join(", "),
            n => format!("{n} apps"),
        }
    }
}

fn day_inputs(days: &[Option<DayWindow>]) -> Vec<DayInput> {
    (0..7)
        .map(|i| {
            let w = days.get(i).copied().flatten();
            DayInput {
                index: i,
                name: DAY_NAMES[i],
                short: DAY_SHORT[i],
                start: w
                    .map(|w| minutes_to_time_input(w.start))
                    .unwrap_or_default(),
                end: w.map(|w| minutes_to_time_input(w.end)).unwrap_or_default(),
                error: None,
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
async fn reported_apps(
    state: &AppState,
    device_ids: &[i64],
) -> Result<BTreeMap<String, String>, sqlx::Error> {
    let mut apps = BTreeMap::new();
    for id in device_ids {
        let json: Option<String> = sqlx::query_scalar(
            "SELECT installed_apps_json FROM device_status WHERE device_id = ? \
             AND installed_apps_json IS NOT NULL ORDER BY reported_at DESC, id DESC LIMIT 1",
        )
        .bind(id)
        .fetch_optional(&state.db)
        .await?;
        let installed: Vec<InstalledApp> = json
            .as_deref()
            .and_then(|j| serde_json::from_str(j).ok())
            .unwrap_or_default();
        for app in installed {
            if time_rules::valid_package_name(&app.package_name) {
                apps.entry(app.package_name).or_insert(app.label);
            }
        }
    }
    Ok(apps)
}

/// Where a rule belongs: everyone's (`None`) or one phone's own, with the phone's name.
struct RuleScope {
    device: Option<(i64, String)>,
    /// The phone follows its own rules (always true for everyone's).
    in_force: bool,
}

impl RuleScope {
    fn id(&self) -> Option<i64> {
        self.device.as_ref().map(|(id, _)| *id)
    }

    /// The list the rule is in: Settings › Time Rules, or the phone's Time page.
    fn list(&self) -> String {
        match &self.device {
            Some((id, _)) => format!("/devices/{id}/time"),
            None => "/schedules".to_string(),
        }
    }

    /// The list's group of rules.
    fn list_group(&self) -> &'static str {
        if self.device.is_some() {
            "own-rules"
        } else {
            "rules"
        }
    }

    /// The apps its exceptions offer: the phone's, or every phone's.
    async fn apps(&self, state: &AppState) -> Result<BTreeMap<String, String>, sqlx::Error> {
        let ids = match self.id() {
            Some(id) => vec![id],
            None => time_rules::all_device_ids(&state.db).await?,
        };
        reported_apps(state, &ids).await
    }
}

#[derive(Template)]
#[template(path = "schedule_rule.html")]
struct RuleTemplate {
    title: String,
    rule: RuleForm,
    /// Posted back with every form: "" for everyone's rules, else the phone's id.
    scope: String,
    back_href: String,
    back_label: String,
    /// "Anna" for a phone's own rule, for the delete's sentence.
    device_name: Option<String>,
    /// The flash in the top bar after a save: "Saved. The phones get it …".
    saved: Option<String>,
    /// A refusal's reason, in the top bar beside Save (in view wherever the page opens).
    refusal: Option<String>,
    /// The stored rule doesn't read: which parts (they open empty and are saved as shown).
    corrupt: Option<String>,
    /// The kind select's options: value and label.
    kinds: [(&'static str, &'static str); 3],
}

fn render_rule(
    scope: &RuleScope,
    rule: RuleForm,
    saved: Option<&'static str>,
    corrupt: Option<String>,
    status: StatusCode,
) -> Response {
    let refusal = rule
        .name_error
        .clone()
        .or_else(|| rule.days.iter().find_map(|d| d.error.clone()))
        .or_else(|| rule.apps_error.clone());
    // A phone's own rule while it follows everyone's: saved, but not in force yet (qa N2).
    let saved = saved.map(|text| {
        if scope.in_force {
            format!("{text} The phones get it within seconds when online.")
        } else {
            format!("{text} Used once Own rules is on.")
        }
    });
    let (back_href, back_label) = match &scope.device {
        Some((id, _)) => (format!("/devices/{id}/time"), "Time".to_string()),
        None => ("/schedules".to_string(), "Time Rules".to_string()),
    };
    render(
        RuleTemplate {
            title: if rule.id == 0 {
                "New Rule".to_string()
            } else if rule.name.trim().is_empty() {
                // A refused save with the name cleared.
                "Rule".to_string()
            } else {
                rule.name.clone()
            },
            scope: scope.id().map(|id| id.to_string()).unwrap_or_default(),
            back_href,
            back_label,
            device_name: scope.device.as_ref().map(|(_, name)| name.clone()),
            saved,
            refusal,
            corrupt,
            kinds: [
                ("school", "School"),
                ("bedtime", "Bedtime"),
                ("custom", "Other"),
            ],
            rule,
        },
        status,
    )
}

/// The rule's scope from its row or the form: a device that must exist (404 otherwise).
async fn scope_of(state: &AppState, device: Option<i64>) -> Result<RuleScope, Box<Response>> {
    let Some(id) = device else {
        return Ok(RuleScope {
            device: None,
            in_force: true,
        });
    };
    match sqlx::query_as::<_, (String, bool)>(
        "SELECT d.name, p.custom_schedule_enabled FROM devices d \
         JOIN device_policy p ON p.device_id = d.id WHERE d.id = ?",
    )
    .bind(id)
    .fetch_optional(&state.db)
    .await
    {
        Ok(Some((name, in_force))) => Ok(RuleScope {
            device: Some((id, name)),
            in_force,
        }),
        Ok(None) => Err(Box::new(
            (StatusCode::NOT_FOUND, "Device not found").into_response(),
        )),
        Err(err) => Err(Box::new(read_failed(err, "a device"))),
    }
}

/// `scope` from a form: "" = global, otherwise a device id that must exist (else 404).
async fn parse_scope(state: &AppState, raw: &str) -> Result<RuleScope, Box<Response>> {
    let raw = raw.trim();
    if raw.is_empty() {
        return scope_of(state, None).await;
    }
    let Ok(id) = raw.parse::<i64>() else {
        return Err(Box::new(bad_request("Unknown scope")));
    };
    scope_of(state, Some(id)).await
}

/// The parts of a stored rule that don't read, for the editor's warning: they open empty (or as
/// Other) and a save writes them as shown (qa-31-s9-code L3). `None` for a readable rule.
fn unreadable_parts(row: &TimeRuleRow) -> Option<String> {
    let mut parts = Vec::new();
    if !RULE_KINDS.contains(&row.kind.as_str()) {
        parts.push("type");
    }
    if time_rules::parse_days(&row.days_json).is_err() {
        parts.push("days");
    }
    if time_rules::parse_exempt_apps(&row.exempt_apps_json).is_err() {
        parts.push("apps");
    }
    match parts.as_slice() {
        [] => None,
        [one] => Some(format!("Its {one} couldn't be read")),
        [first @ .., last] => Some(format!(
            "Its {} and {last} couldn't be read",
            first.join(", ")
        )),
    }
}

/// GET `/schedules/rules/{id}`: the editor. A rule whose stored data doesn't read opens with what
/// does, and a warning; saving it again fixes it.
pub async fn show_rule(
    State(state): State<AppState>,
    Path(rule_id): Path<i64>,
    Query(params): Query<HashMap<String, String>>,
) -> Response {
    let row = match sqlx::query_as::<_, TimeRuleRow>("SELECT * FROM time_rules WHERE id = ?")
        .bind(rule_id)
        .fetch_optional(&state.db)
        .await
    {
        Ok(Some(row)) => row,
        Ok(None) => return (StatusCode::NOT_FOUND, "Rule not found").into_response(),
        Err(err) => return read_failed(err, "a time rule"),
    };
    let scope = match scope_of(&state, row.device_id).await {
        Ok(scope) => scope,
        Err(answer) => return *answer,
    };
    let available = match scope.apps(&state).await {
        Ok(apps) => apps,
        Err(err) => return read_failed(err, "the reported apps"),
    };
    let corrupt = unreadable_parts(&row);
    let days = time_rules::parse_days(&row.days_json).unwrap_or_else(|_| vec![None; 7]);
    let exempt = time_rules::parse_exempt_apps(&row.exempt_apps_json).unwrap_or_default();
    let rule = RuleForm {
        id: row.id,
        kind: if RULE_KINDS.contains(&row.kind.as_str()) {
            row.kind.clone()
        } else {
            "custom".to_string()
        },
        name: row.name,
        calls_allowed: row.calls_allowed,
        days: day_inputs(&days),
        apps: app_choices(&available, &exempt),
        name_error: None,
        apps_error: None,
    };
    let saved = match params.get("saved").map(String::as_str) {
        Some("added") => Some("Rule added."),
        Some(_) => Some("Saved."),
        None => None,
    };
    render_rule(&scope, rule, saved, corrupt, StatusCode::OK)
}

/// GET `/schedules/rules/new` (`?device=X` for a phone's own rule): the editor, empty.
pub async fn new_rule(
    State(state): State<AppState>,
    Query(params): Query<HashMap<String, String>>,
) -> Response {
    let scope = match parse_scope(&state, params.get("device").map_or("", String::as_str)).await {
        Ok(scope) => scope,
        Err(answer) => return *answer,
    };
    let available = match scope.apps(&state).await {
        Ok(apps) => apps,
        Err(err) => return read_failed(err, "the reported apps"),
    };
    let rule = RuleForm {
        id: 0,
        name: String::new(),
        kind: "school".to_string(),
        calls_allowed: false,
        days: day_inputs(&[]),
        apps: app_choices(&available, &[]),
        name_error: None,
        apps_error: None,
    };
    render_rule(&scope, rule, None, None, StatusCode::OK)
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

/// Why a rule form was refused, by the field it is shown under.
#[derive(Debug, PartialEq, Eq)]
pub(crate) enum RuleError {
    Name(String),
    Kind,
    Day(usize, String),
    Apps(String),
}

fn field<'a>(fields: &'a [(String, String)], key: &str) -> &'a str {
    fields
        .iter()
        .find(|(k, _)| k == key)
        .map(|(_, v)| v.as_str())
        .unwrap_or("")
}

pub(crate) fn parse_rule_form(fields: &[(String, String)]) -> Result<RuleInput, RuleError> {
    let name = field(fields, "name").trim().to_string();
    if name.is_empty() || name.chars().count() > MAX_RULE_NAME_CHARS {
        return Err(RuleError::Name(format!(
            "Give the rule a name of 1 to {MAX_RULE_NAME_CHARS} characters."
        )));
    }
    let kind = field(fields, "kind").to_string();
    if !RULE_KINDS.contains(&kind.as_str()) {
        return Err(RuleError::Kind);
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
            _ if start.is_empty() || end.is_empty() => {
                return Err(RuleError::Day(
                    i,
                    format!("{day}: give both times, or leave both empty."),
                ));
            }
            _ => {
                return Err(RuleError::Day(i, format!("{day}: use times like 08:15.")));
            }
        }
    }
    let mut exempt_apps: Vec<String> = Vec::new();
    for (k, v) in fields {
        if k == "exempt_apps" && !exempt_apps.contains(v) {
            if !time_rules::valid_package_name(v) {
                return Err(RuleError::Apps(
                    "One of the apps isn't an app. Pick them again.".to_string(),
                ));
            }
            exempt_apps.push(v.clone());
        }
    }
    if exempt_apps.len() > MAX_EXEMPT_APPS {
        return Err(RuleError::Apps(format!(
            "Pick at most {MAX_EXEMPT_APPS} apps."
        )));
    }
    Ok(RuleInput {
        calls_allowed: fields.iter().any(|(k, _)| k == "calls_allowed"),
        name,
        kind,
        exempt_apps,
        days,
    })
}

/// The editor as typed, with the refusal under its field. What was typed goes back escaped and
/// cut short; an app that isn't a package name is dropped.
fn rule_entered(
    id: i64,
    fields: &[(String, String)],
    available: &BTreeMap<String, String>,
    error: RuleError,
) -> RuleForm {
    let typed =
        |key: &str, max: usize| -> String { field(fields, key).chars().take(max).collect() };
    let chosen: Vec<String> = fields
        .iter()
        .filter(|(k, v)| k == "exempt_apps" && time_rules::valid_package_name(v))
        .map(|(_, v)| v.clone())
        .take(MAX_EXEMPT_APPS * 2)
        .collect();
    let kind = field(fields, "kind");
    let mut form = RuleForm {
        id,
        name: typed("name", MAX_RULE_NAME_CHARS * 2),
        kind: if RULE_KINDS.contains(&kind) {
            kind.to_string()
        } else {
            "custom".to_string()
        },
        calls_allowed: fields.iter().any(|(k, _)| k == "calls_allowed"),
        days: (0..7)
            .map(|i| DayInput {
                index: i,
                name: DAY_NAMES[i],
                short: DAY_SHORT[i],
                start: typed(&format!("d{i}_start"), 8),
                end: typed(&format!("d{i}_end"), 8),
                error: None,
            })
            .collect(),
        apps: app_choices(available, &chosen),
        name_error: None,
        apps_error: None,
    };
    match error {
        RuleError::Name(why) => form.name_error = Some(why),
        RuleError::Day(i, why) => form.days[i].error = Some(why),
        RuleError::Apps(why) => form.apps_error = Some(why),
        RuleError::Kind => {}
    }
    form
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

fn form_pairs(body: &Bytes) -> Vec<(String, String)> {
    form_urlencoded::parse(body).into_owned().collect()
}

/// A refused rule form: its page again (400), the typed values kept. An unknown kind is no page's
/// (the select offers the three), so it stays a plain 400.
async fn refuse_rule(
    state: &AppState,
    scope: &RuleScope,
    stored: Option<&TimeRuleRow>,
    fields: &[(String, String)],
    error: RuleError,
) -> Response {
    let id = stored.map_or(0, |row| row.id);
    if error == RuleError::Kind {
        return bad_request("Unknown kind of rule.");
    }
    let available = match scope.apps(state).await {
        Ok(apps) => apps,
        Err(err) => return read_failed(err, "the reported apps"),
    };
    let form = rule_entered(id, fields, &available, error);
    render_rule(
        scope,
        form,
        None,
        stored.and_then(unreadable_parts),
        StatusCode::BAD_REQUEST,
    )
}

/// POST `/schedules/rules`: a new rule, then its page.
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
        Err(error) => return refuse_rule(&state, &scope, None, &fields, error).await,
    };
    let device = scope.id();
    let result = sqlx::query_scalar::<_, i64>(
        "INSERT INTO time_rules (device_id, name, kind, calls_allowed, exempt_apps_json, \
         days_json, sort_order) VALUES (?, ?, ?, ?, ?, ?, \
         (SELECT COALESCE(MAX(sort_order), -1) + 1 FROM time_rules WHERE device_id IS ?)) \
         RETURNING id",
    )
    .bind(device)
    .bind(&input.name)
    .bind(&input.kind)
    .bind(input.calls_allowed)
    .bind(serde_json::to_string(&input.exempt_apps).expect("strings serialize"))
    .bind(serde_json::to_string(&input.days).expect("windows serialize"))
    .bind(device)
    .fetch_one(&state.db)
    .await;
    let rule_id = match result {
        Ok(id) => id,
        Err(err) => return server_error(err, "failed to add a time rule"),
    };
    log_change(&state, &admin, "time_rule_created", device, &input.name).await;
    nudge(&state, device).await;
    Redirect::to(&format!("/schedules/rules/{rule_id}?saved=added#rule")).into_response()
}

/// The rule, if it still exists and belongs to `scope` (a device's own rule can't be edited
/// through the global form and vice versa): `Ok(None)` when it is gone, a 404 for another scope's.
async fn rule_in_scope(
    state: &AppState,
    rule_id: i64,
    scope: Option<i64>,
) -> Result<Option<TimeRuleRow>, Box<Response>> {
    match sqlx::query_as::<_, TimeRuleRow>("SELECT * FROM time_rules WHERE id = ?")
        .bind(rule_id)
        .fetch_optional(&state.db)
        .await
    {
        Ok(Some(rule)) if rule.device_id == scope => Ok(Some(rule)),
        Ok(Some(_)) => Err(Box::new(
            (StatusCode::NOT_FOUND, "Rule not found").into_response(),
        )),
        Ok(None) => Ok(None),
        Err(err) => Err(Box::new(server_error(err, "failed to read a time rule"))),
    }
}

/// The rules list a gone rule's form lands on, with its flash.
fn to_list(scope: &RuleScope, notice: &str) -> Response {
    Redirect::to(&format!(
        "{}?notice={notice}#{}",
        scope.list(),
        scope.list_group()
    ))
    .into_response()
}

/// POST `/schedules/rules/{id}`: save, then the rule's page.
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
    let stored = match rule_in_scope(&state, rule_id, scope.id()).await {
        Ok(Some(row)) => row,
        Ok(None) => return to_list(&scope, "gone_unsaved"),
        Err(response) => return *response,
    };
    let input = match parse_rule_form(&fields) {
        Ok(input) => input,
        Err(error) => return refuse_rule(&state, &scope, Some(&stored), &fields, error).await,
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
    .bind(scope.id())
    .execute(&state.db)
    .await;
    match result {
        // Deleted between the check and the write.
        Ok(done) if done.rows_affected() == 0 => return to_list(&scope, "gone_unsaved"),
        Ok(_) => {}
        Err(err) => return server_error(err, "failed to save a time rule"),
    }
    log_change(&state, &admin, "time_rule_updated", scope.id(), &input.name).await;
    nudge(&state, scope.id()).await;
    // Its form (§5.1.4): Save is at the top, and scroll-restore keeps the exact place.
    Redirect::to(&format!("/schedules/rules/{rule_id}?saved=1#rule")).into_response()
}

/// POST `/schedules/rules/{id}/delete`: then the list it was in.
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
    let rule = match rule_in_scope(&state, rule_id, scope.id()).await {
        Ok(Some(rule)) => rule,
        Ok(None) => return to_list(&scope, "gone"),
        Err(response) => return *response,
    };
    if let Err(err) = sqlx::query("DELETE FROM time_rules WHERE id = ? AND device_id IS ?")
        .bind(rule_id)
        .bind(scope.id())
        .execute(&state.db)
        .await
    {
        return server_error(err, "failed to delete a time rule");
    }
    log_change(&state, &admin, "time_rule_deleted", scope.id(), &rule.name).await;
    nudge(&state, scope.id()).await;
    to_list(&scope, "deleted")
}

/// `b0`..`b6` (Monday first): minutes 0-1440, blank = unlimited; a refusal names its day.
pub(crate) fn parse_budget_form(fields: &[(String, String)]) -> Result<String, (usize, String)> {
    let mut budget: Vec<Option<i64>> = Vec::with_capacity(7);
    for (i, day) in DAY_NAMES.iter().enumerate() {
        let raw = field(fields, &format!("b{i}")).trim();
        if raw.is_empty() {
            budget.push(None);
            continue;
        }
        match raw.parse::<i64>() {
            Ok(m) if (0..=1440).contains(&m) => budget.push(Some(m)),
            _ => {
                return Err((
                    i,
                    format!("{day}: 0 to 1440 minutes, or empty for no limit."),
                ));
            }
        }
    }
    Ok(serde_json::to_string(&budget).expect("numbers serialize"))
}

/// POST `/schedules/global`: everyone's daily limits.
pub async fn save_global_schedule(
    State(state): State<AppState>,
    Extension(CurrentAdmin(admin)): Extension<CurrentAdmin>,
    body: Bytes,
) -> Response {
    let fields = form_pairs(&body);
    let budget = match parse_budget_form(&fields) {
        Ok(budget) => budget,
        Err(error) => return render_budget(&state, Some((&fields, error)), false).await,
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
    Redirect::to("/schedules/screen-time?saved=1#screen-time").into_response()
}

/// POST `/schedules/device/{id}`: a phone's own-rules switch (`section=switch`) or its own limits
/// (`section=budget`); without a section (a page from before design 31 step 9) both, as before.
pub async fn save_device_schedule(
    State(state): State<AppState>,
    Extension(CurrentAdmin(admin)): Extension<CurrentAdmin>,
    Path(id): Path<i64>,
    body: Bytes,
) -> Response {
    let fields = form_pairs(&body);
    let section = field(&fields, "section");
    let (switch, limits) = match section {
        "switch" => (true, false),
        "budget" => (false, true),
        "" => (true, true),
        _ => return bad_request("Unknown section"),
    };
    let budget = if limits {
        match parse_budget_form(&fields) {
            Ok(budget) => Some(budget),
            Err(error) => {
                return render_device_time(&state, id, &HashMap::new(), Some((&fields, error)))
                    .await;
            }
        }
    } else {
        None
    };
    let custom_enabled = switch.then(|| fields.iter().any(|(k, _)| k == "custom_schedule_enabled"));
    // A NULL bind keeps the column (COALESCE), so a section writes only its own.
    match sqlx::query(
        "UPDATE device_policy SET \
         custom_schedule_enabled = COALESCE(?, custom_schedule_enabled), \
         daily_budget_json = COALESCE(?, daily_budget_json), \
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
            let mut detail = Vec::new();
            if let Some(on) = custom_enabled {
                detail.push(format!("own rules {}", if on { "on" } else { "off" }));
            }
            if let Some(budget) = &budget {
                detail.push(format!("budget {budget}"));
            }
            log_change(
                &state,
                &admin,
                "screen_time_saved",
                Some(id),
                &detail.join(", "),
            )
            .await;
            nudge(&state, Some(id)).await;
            match section {
                "switch" => Redirect::to(&format!("/devices/{id}/time#rules")),
                "budget" => Redirect::to(&format!("/devices/{id}/time?saved=budget#screen-time")),
                _ => Redirect::to(&format!("/devices/{id}/time")),
            }
            .into_response()
        }
        Err(err) => server_error(err, "failed to save a device's time rules"),
    }
}

// ------------------------------------------------------------------------------------------------
// Device › Time
// ------------------------------------------------------------------------------------------------

#[derive(Template)]
#[template(path = "device_time.html")]
struct DeviceTimeTemplate {
    title: String,
    device: Device,
    /// Above the first group: Vibb too old, the record unreadable, incoming calls not blocked.
    warnings: Vec<Note>,
    /// "Skole · calls off", "None", "No report yet".
    lock: String,
    locking: bool,
    /// "42 of 90 min today" and "Includes 30 min extra".
    screen: Option<(String, Option<String>)>,
    /// The last report's age and exact time, for the Now group's footer.
    reported: Option<(String, String)>,
    /// "17:21": the kid's request for more time still open (design r13 §2.7), at the top of Extra
    /// Time Today until a lift or the day's end.
    asked: Option<String>,
    /// The rules a pause can name (the phone's effective ones).
    pause_rules: Vec<(i64, String)>,
    lifts: Vec<crate::handlers::lifts::LiftView>,
    /// A lift form's flash: its group, text, refused.
    done: Option<(&'static str, &'static str, bool)>,
    /// Pause a Rule shows for its flash even with no rule left to pause (qa-31-s9-code M1).
    pause_done: bool,
    custom: bool,
    /// Everyone's rules: how many (the row to Settings › Time Rules).
    everyone_rules: String,
    own_rules: Vec<RuleRow>,
    own_notice: Option<(&'static str, bool)>,
    budget: Vec<BudgetInput>,
    budget_corrupt: bool,
    budget_saved: bool,
}

/// GET `/devices/{id}/time`: Device › Time.
pub async fn view_device_time(
    State(state): State<AppState>,
    Path(id): Path<i64>,
    Query(query): Query<HashMap<String, String>>,
) -> Response {
    render_device_time(&state, id, &query, None).await
}

/// The page; `entered` is a refused own-limits save (its page again, 400, as typed).
async fn render_device_time(
    state: &AppState,
    id: i64,
    query: &HashMap<String, String>,
    entered: Option<RefusedBudget<'_>>,
) -> Response {
    let device = match sqlx::query_as::<_, Device>("SELECT * FROM devices WHERE id = ?")
        .bind(id)
        .fetch_optional(&state.db)
        .await
    {
        Ok(Some(device)) => device,
        Ok(None) => return (StatusCode::NOT_FOUND, "Device not found").into_response(),
        Err(err) => return read_failed(err, "the device"),
    };
    let policy =
        match sqlx::query_as::<_, DevicePolicy>("SELECT * FROM device_policy WHERE device_id = ?")
            .bind(id)
            .fetch_optional(&state.db)
            .await
        {
            Ok(Some(policy)) => policy,
            Ok(None) => return (StatusCode::NOT_FOUND, "Device not found").into_response(),
            Err(err) => return read_failed(err, "the device's settings"),
        };
    let latest = match sqlx::query_as::<_, DeviceStatus>(
        "SELECT * FROM device_status WHERE device_id = ? ORDER BY reported_at DESC, id DESC LIMIT 1",
    )
    .bind(id)
    .fetch_optional(&state.db)
    .await
    {
        Ok(latest) => latest,
        Err(err) => return read_failed(err, "the last report"),
    };
    let effective = match time_rules::effective_rule_rows(
        &state.db,
        id,
        policy.custom_schedule_enabled,
    )
    .await
    {
        Ok(rules) => rules,
        Err(err) => return read_failed(err, "the phone's rules"),
    };
    let own = match time_rules::effective_rule_rows(&state.db, id, true).await {
        Ok(rules) => rules,
        Err(err) => return read_failed(err, "the phone's own rules"),
    };
    let everyone_count: i64 =
        match sqlx::query_scalar("SELECT COUNT(*) FROM time_rules WHERE device_id IS NULL")
            .fetch_one(&state.db)
            .await
        {
            Ok(n) => n,
            Err(err) => return read_failed(err, "the global rules"),
        };
    let call_problems = match crate::handlers::calls::call_report(state, &policy).await {
        Ok(report) => report.problems(),
        Err(err) => return read_failed(err, "the call report"),
    };
    let zone = Zone::load(&state.db).await;
    let now = Utc::now();
    let lifts =
        match crate::handlers::lifts::recent(&state.db, id, latest.as_ref(), &zone, now).await {
            Ok(lifts) => lifts,
            Err(err) => return read_failed(err, "the lifts"),
        };
    let asked = match crate::parent_events::open_extra_time_request(&state.db, id, &zone, now).await
    {
        Ok(asked) => asked.map(|at| zone.clock(at)),
        Err(err) => return read_failed(err, "the request for more time"),
    };

    // What the phone last said (design 31 §3.10's badge; the end only when provably right).
    let report = latest.as_ref().and_then(|s| {
        crate::handlers::devices::time_report(
            &s.reported_at,
            &s.lock_reason,
            s.time_state_json.as_deref(),
        )
    });
    let reported = report
        .as_ref()
        .map(|(t, _)| (zone.relative(*t, now), zone.exact(*t)));
    let connection = crate::handlers::devices::connection_of(state, &device, now);
    let calls_enforced = crate::attention::calls_enforced(&policy, &call_problems);
    let (lock, locking, screen) = match &report {
        Some((reported_at, report)) => {
            let (badge, screen) = crate::handlers::devices::badge_and_screen(
                report,
                *reported_at,
                &effective,
                &connection,
                calls_enforced,
                &zone,
                now,
            );
            let lock = match &badge {
                // The footer says when the report came, so the badge needn't repeat it.
                Some(badge) => badge.text_beside(&zone.relative(*reported_at, now)),
                None => "None".to_string(),
            };
            let screen = screen.map(|st| {
                (
                    st.text(),
                    report.extra_minutes.map(|m| {
                        format!("Includes {} extra", crate::handlers::lifts::minutes_text(m))
                    }),
                )
            });
            (lock, badge.is_some(), screen)
        }
        _ => ("No report yet".to_string(), false, None),
    };

    let mut warnings = Vec::new();
    if crate::handlers::devices::launcher_without_rules(latest.as_ref()) {
        warnings.push(Note {
            warning: true,
            title: "Vibb One too old for time rules".to_string(),
            value: None,
            detail: Some(
                "Until Vibb One updates, the phone keeps its old schedule and changes here don't \
                 reach it."
                    .to_string(),
            ),
            fix: None,
        });
    }
    if report.as_ref().is_some_and(|(_, r)| r.ledger_unreadable) {
        warnings.push(Note {
            warning: true,
            title: "Screen-time record unreadable".to_string(),
            value: None,
            detail: Some("The phone counts today's screen time as used up.".to_string()),
            fix: None,
        });
    }
    if !policy.calls_managed && effective.iter().any(|r| !r.calls_allowed) {
        warnings.push(Note {
            warning: true,
            title: "Incoming calls aren't blocked".to_string(),
            value: None,
            detail: Some(
                "Calls aren't managed on this phone, so a rule without calls only stops calls \
                 out. Manage calls under Calls & Contacts."
                    .to_string(),
            ),
            fix: None,
        });
    }

    let refused = entered.is_some();
    let done = query
        .get("done")
        .and_then(|code| crate::handlers::lifts::done_flash(code));
    render(
        DeviceTimeTemplate {
            title: format!("Time · {}", device.name),
            warnings,
            lock,
            locking,
            screen,
            reported,
            asked,
            pause_rules: effective.iter().map(|r| (r.id, r.name.clone())).collect(),
            lifts,
            done,
            pause_done: done.is_some_and(|(group, _, _)| group == "pause"),
            custom: policy.custom_schedule_enabled,
            everyone_rules: match everyone_count {
                0 => "None".to_string(),
                1 => "1 rule".to_string(),
                n => format!("{n} rules"),
            },
            own_rules: rule_rows(&own),
            own_notice: rules_notice(query.get("notice").map(String::as_str)),
            budget_corrupt: time_rules::parse_budget(&policy.daily_budget_json).is_err(),
            budget: match &entered {
                Some((fields, error)) => budget_entered(fields, error),
                None => budget_inputs(&policy.daily_budget_json),
            },
            budget_saved: !refused && query.get("saved").map(String::as_str) == Some("budget"),
            device,
        },
        if refused {
            StatusCode::BAD_REQUEST
        } else {
            StatusCode::OK
        },
    )
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
        assert_eq!(days_summary(&bed), "Every day 21:00-07:00");
        let mut mixed = vec![None; 7];
        mixed[5] = Some(DayWindow { start: 0, end: 0 });
        mixed[0] = w;
        assert_eq!(days_summary(&mixed), "Mon 08:15-14:00, Sat 00:00 for 24 h");
        assert_eq!(days_summary(&[None; 7]), "Never");
    }

    #[test]
    fn budget_summary_groups_days() {
        let weekdays = [
            Some(90),
            Some(90),
            Some(90),
            Some(90),
            Some(90),
            Some(120),
            Some(120),
        ];
        assert_eq!(budget_summary(&weekdays), "Mon-Fri 90 min · Sat-Sun 2 h");
        assert_eq!(budget_summary(&[Some(90); 7]), "Every day 90 min");
        assert_eq!(budget_summary(&[Some(180); 7]), "Every day 3 h");
        assert_eq!(budget_summary(&[None; 7]), "No limit");
        let one = [Some(0), None, None, None, None, None, None];
        assert_eq!(budget_summary(&one), "Mon 0 min · Tue-Sun no limit");
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

        let refused = |list: &[(&str, &str)]| parse_rule_form(&pairs(list)).unwrap_err();
        assert!(matches!(
            refused(&[("name", ""), ("kind", "school")]),
            RuleError::Name(_)
        ));
        assert_eq!(refused(&[("name", "x"), ("kind", "nap")]), RuleError::Kind);
        assert!(matches!(
            refused(&[("name", "x"), ("kind", "custom"), ("d2_start", "08:00")]),
            RuleError::Day(2, _)
        ));
        assert!(matches!(
            refused(&[
                ("name", "x"),
                ("kind", "custom"),
                ("d3_start", "25:00"),
                ("d3_end", "08:00"),
            ]),
            RuleError::Day(3, _)
        ));
        assert_eq!(
            refused(&[
                ("name", "x"),
                ("kind", "custom"),
                ("d4_start", "8:00"),
                ("d4_end", "09:00")
            ]),
            RuleError::Day(4, "Friday: use times like 08:15.".to_string())
        );
        assert_eq!(
            refused(&[
                ("name", "x"),
                ("kind", "custom"),
                ("d4_start", "+8:00"),
                ("d4_end", "09:00")
            ]),
            RuleError::Day(4, "Friday: use times like 08:15.".to_string())
        );
        assert_eq!(
            refused(&[
                ("name", "x"),
                ("kind", "custom"),
                ("d4_start", ""),
                ("d4_end", "09:00")
            ]),
            RuleError::Day(
                4,
                "Friday: give both times, or leave both empty.".to_string()
            )
        );
        assert!(matches!(
            refused(&[("name", "x"), ("kind", "custom"), ("exempt_apps", "nope")]),
            RuleError::Apps(_)
        ));
        let long = "x".repeat(61);
        assert!(matches!(
            refused(&[("name", &long), ("kind", "custom")]),
            RuleError::Name(_)
        ));
    }

    #[test]
    fn budget_form_validation() {
        assert_eq!(
            parse_budget_form(&pairs(&[("b0", "60"), ("b6", "0")])).unwrap(),
            "[60,null,null,null,null,null,0]"
        );
        assert_eq!(
            parse_budget_form(&pairs(&[("b0", "1441")])).unwrap_err().0,
            0
        );
        assert_eq!(
            parse_budget_form(&pairs(&[("b3", "lots")])).unwrap_err().0,
            3
        );
    }

    #[test]
    fn every_rules_notice_reads_as_words() {
        for code in ["deleted", "gone", "gone_unsaved"] {
            assert!(rules_notice(Some(code)).is_some(), "{code}");
        }
        assert_eq!(rules_notice(Some("<b>")), None);
        assert_eq!(rules_notice(None), None);
    }
}
