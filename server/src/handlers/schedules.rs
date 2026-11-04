//! Time rules (handy step 6, docs/design/06-time-rules.md in the handy workspace): named rules
//! (school / bedtime / custom, per-weekday windows, calls on/off, exempt apps) and a daily
//! screen-time budget per weekday. Everyone's rules and each phone's own rules both apply, each
//! unless switched off for the phone (`device_rule_off`, migration 0115; a rule added later is on
//! for every phone); the daily limit is everyone's unless the phone's Own limit switch
//! (`device_policy.custom_schedule_enabled`) is on. The pre-step-6 weekday/weekend/bedtime
//! schedule was converted into rules once (`time_rules::migrate_legacy`).
//!
//! The pages (design 31 §3.5, step 9):
//! - **Settings › Time Rules** (`GET /schedules`, `schedules.html`): everyone's rules as rows ("Off
//!   for Emil" where a phone has one off), the daily limit's row, a row per phone with its rules
//!   in force. Links only, no form. `/schedules?device=X` (an old bookmark) redirects to the
//!   phone's Time Rules page (L5).
//! - **Daily Limit** (`GET /schedules/screen-time`, `schedule_budget.html`): everyone's 7 limits,
//!   one form (`POST /schedules/global`).
//! - **The rule editor** (`GET /schedules/rules/{id}`, chained with its POST; `GET
//!   /schedules/rules/new`, `?device=X` for a phone's own rule; `schedule_rule.html`): one form with
//!   Save at the top, the exempt apps in a `<details>` inside it, Delete Rule… behind a confirm.
//! - **Device › Time Rules** ("Tidsregler", `GET /devices/{id}/time`, `device_time.html`; tidied
//!   2026-11-01): what the phone last said, the bedtime, everyone's rules with a switch each, the
//!   phone's own rules, the daily limit (the Own limit switch, its 7 days while on). Extra time,
//!   Pause a rule and the lifts are the summary's Quick actions (`handlers::lifts`).
//!
//! Where the forms return (§5.1): a rule's save to its own page's form (`#rule`: Save sits at the
//! top, and scroll-restore keeps the place), a new rule to its new page, a delete to the list it was in
//! (`/schedules#rules`, a phone's own `/devices/{id}/time#own-rules`) with the flash there; a save or
//! delete of a rule deleted in another tab lands on that list with nothing written. The daily limit
//! to `/schedules/screen-time?saved=1#screen-time`. A rule's switch for a phone
//! (`/devices/{id}/time/rules/{rule_id}`) to `#everyone` / `#own-rules`. `/schedules/device/{id}`
//! takes `section` (§5.6, H1): `limit` writes only the Own limit switch (→ `#screen-time`; an
//! older tab's `switch` the same), `budget` only the limits (→ `?saved=budget#screen-time`), none
//! both (an older tab). A refused rule or limit renders its page
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
use crate::i18n::{Lang, Words, t, t_count, t_params};
use crate::models::{Device, DevicePolicy, DeviceStatus, InstalledApp};
use crate::security::{self, CurrentAdmin};
use crate::time_rules::{
    self, DayWindow, LAST_MINUTE, MAX_EXEMPT_APPS, MAX_RULE_NAME_CHARS, RULE_KINDS, TimeRuleRow,
};

/// The days' keys, Monday first: the full names (a field's label, a refusal's start), the short ones (the editor's
/// rows) and the ones inside a summary ("Mon-Fri 08:15-14:30, Sat …", nb lower-case: [capitalized] makes the first
/// word upper-case).
const DAY_NAMES: [&str; 7] = [
    "settings.time_rules.weekday.1",
    "settings.time_rules.weekday.2",
    "settings.time_rules.weekday.3",
    "settings.time_rules.weekday.4",
    "settings.time_rules.weekday.5",
    "settings.time_rules.weekday.6",
    "settings.time_rules.weekday.7",
];
const DAY_SHORT: [&str; 7] = [
    "settings.time_rules.day_short.1",
    "settings.time_rules.day_short.2",
    "settings.time_rules.day_short.3",
    "settings.time_rules.day_short.4",
    "settings.time_rules.day_short.5",
    "settings.time_rules.day_short.6",
    "settings.time_rules.day_short.7",
];
const DAY_IN_TEXT: [&str; 7] = [
    "settings.time_rules.day_in_text.1",
    "settings.time_rules.day_in_text.2",
    "settings.time_rules.day_in_text.3",
    "settings.time_rules.day_in_text.4",
    "settings.time_rules.day_in_text.5",
    "settings.time_rules.day_in_text.6",
    "settings.time_rules.day_in_text.7",
];

/// `text` with its first letter upper-case: a summary that starts with a day ("Man-fre …").
fn capitalized(text: String) -> String {
    let mut chars = text.chars();
    match chars.next() {
        Some(first) if first.is_lowercase() => first.to_uppercase().chain(chars).collect(),
        _ => text,
    }
}

/// HTML `<input type="time">` "HH:MM" from minutes since midnight.
pub(crate) fn minutes_to_time_input(minutes: i64) -> String {
    format!("{:02}:{:02}", minutes / 60, minutes % 60)
}

/// Strict "HH:MM" (00:00-23:59) to minutes since midnight.
pub(crate) fn time_input_to_minutes(value: &str) -> Option<i64> {
    let (h, m) = value.trim().split_once(':')?;
    let digits = |t: &str| t.len() == 2 && t.bytes().all(|b| b.is_ascii_digit());
    if !digits(h) || !digits(m) {
        return None;
    }
    let (h, m) = (h.parse::<i64>().ok()?, m.parse::<i64>().ok()?);
    ((0..24).contains(&h) && (0..60).contains(&m)).then_some(h * 60 + m)
}

/// Runs of equal consecutive days, as "Mon-Fri" / "Sat" / "Every day" with their value, in `lang` (nb lower-case).
fn day_runs<T: PartialEq + Copy>(lang: Lang, days: &[T]) -> Vec<(String, T)> {
    let mut runs = Vec::new();
    let mut d = 0;
    while d < days.len() {
        let mut last = d;
        while last + 1 < days.len() && days[last + 1] == days[d] {
            last += 1;
        }
        let range = if d == 0 && last == 6 {
            t(lang, "settings.time_rules.every_day").to_string()
        } else if last == d {
            t(lang, DAY_IN_TEXT[d]).to_string()
        } else {
            format!("{}-{}", t(lang, DAY_IN_TEXT[d]), t(lang, DAY_IN_TEXT[last]))
        };
        runs.push((range, days[d]));
        d = last + 1;
    }
    runs
}

/// "Mon-Fri 08:15-14:30, Sat 00:00-23:59" (a day rule starts and ends on its day, design 66),
/// "Never" for a rule without days.
pub(crate) fn days_summary(lang: Lang, days: &[Option<DayWindow>]) -> String {
    let parts: Vec<String> = day_runs(lang, days)
        .into_iter()
        .filter_map(|(range, w)| Some(format!("{range} {}", window_text(w?))))
        .collect();
    if parts.is_empty() {
        t(lang, "settings.time_rules.never").to_string()
    } else {
        capitalized(parts.join(", "))
    }
}

/// "08:15-14:30".
pub(crate) fn window_text(w: DayWindow) -> String {
    format!(
        "{}-{}",
        minutes_to_time_input(w.start),
        minutes_to_time_input(w.end)
    )
}

/// "Mon-Fri 90 min · Sat-Sun 2 h", "Every day 45 min", "No limit".
pub(crate) fn budget_summary(lang: Lang, budget: &[Option<i64>]) -> String {
    if budget.iter().all(Option::is_none) {
        return t(lang, "device.time.no_limit").to_string();
    }
    let runs = day_runs(lang, budget)
        .into_iter()
        .map(|(range, minutes)| {
            let value = match minutes {
                Some(m) if m >= 60 && m % 60 == 0 => {
                    t_params(lang, "common.n_h", &[("n", &(m / 60).to_string())])
                }
                Some(m) => t_params(lang, "common.n_min", &[("n", &m.to_string())]),
                None => t(lang, "settings.time_rules.run_no_limit").to_string(),
            };
            format!("{range} {value}")
        })
        .collect::<Vec<_>>()
        .join(" · ");
    capitalized(runs)
}

/// One rule as a navigation row: "Skole" over "Mon-Fri 08:15-14:30 · calls off · silent".
pub(crate) struct RuleRow {
    pub id: i64,
    pub name: String,
    pub summary: String,
    /// The stored rule doesn't read: the phones get an error until it is saved again or deleted.
    pub corrupt: bool,
    /// A phone's own rule (else everyone's): its switch lands on `#own-rules`.
    pub own: bool,
    /// Device › Time Rules: switched off for this phone (`device_rule_off`), dimmed.
    pub off: bool,
    /// Settings › Time Rules: "Off for Emil" - the phones that have everyone's rule off.
    pub off_for: Option<String>,
}

/// The rules as rows. The summary is of the stored days, as the editor shows them ("00:00-23:59"),
/// not the served ones (`end: 0`, qa-66-code M1); `to_policy` only says whether the row reads.
/// A silent rule (0121) ends with "· silent" ("· stille").
fn rule_rows(lang: Lang, rows: &[TimeRuleRow]) -> Vec<RuleRow> {
    rows.iter()
        .map(
            |row| match (row.to_policy(), time_rules::parse_days(&row.days_json)) {
                (Ok(_), Ok(days)) => RuleRow {
                    id: row.id,
                    summary: format!(
                        "{} · {}{}",
                        days_summary(lang, &days),
                        t(
                            lang,
                            if row.calls_allowed {
                                "settings.time_rules.calls_on"
                            } else {
                                "settings.time_rules.calls_off"
                            }
                        ),
                        silent_tag(lang, row.silent)
                    ),
                    name: time_rules::shown_name(lang, &row.name),
                    corrupt: false,
                    own: row.device_id.is_some(),
                    off: false,
                    off_for: None,
                },
                _ => RuleRow {
                    id: row.id,
                    name: time_rules::shown_name(lang, &row.name),
                    summary: t(lang, "settings.time_rules.unreadable").to_string(),
                    corrupt: true,
                    own: row.device_id.is_some(),
                    off: false,
                    off_for: None,
                },
            },
        )
        .collect()
}

/// " · silent" after a silent rule's or bedtime's summary (0121), "" otherwise.
pub(crate) fn silent_tag(lang: Lang, silent: bool) -> String {
    if silent {
        format!(" · {}", t(lang, "settings.time_rules.silent_tag"))
    } else {
        String::new()
    }
}

/// The flash in a rules list after a delete or a save of a gone rule (`?notice=`), as `(text,
/// refused)`; `None` for a code the page doesn't know.
fn rules_notice(lang: Lang, code: Option<&str>) -> Option<(&'static str, bool)> {
    let (key, refused) = match code? {
        "deleted" => ("settings.time_rules.deleted", false),
        "gone" => ("settings.time_rules.gone", false),
        "gone_unsaved" => ("settings.time_rules.gone_unsaved", true),
        _ => return None,
    };
    Some((t(lang, key), refused))
}

/// [read_failed] in the reader's language (Device › Time and the bedtime, design 70 slice 3a).
pub(crate) fn read_failed_in(lang: Lang, err: sqlx::Error, what: &str) -> Response {
    tracing::error!(%err, "can't read {what}");
    crate::i18n::error(lang, StatusCode::INTERNAL_SERVER_ERROR, "error.read_rules")
}

/// A failed save or read, in the reader's language (Device › Time's saves and the bedtime, design 70 slice 3a).
pub(crate) fn server_error_in(lang: Lang, err: impl std::fmt::Display, what: &str) -> Response {
    tracing::error!(%err, "{what}");
    crate::i18n::error(
        lang,
        StatusCode::INTERNAL_SERVER_ERROR,
        "error.save_failed_log",
    )
}

pub(crate) fn render(template: impl Template + Words, status: StatusCode) -> Response {
    match template.render() {
        Ok(html) => (status, Html(html)).into_response(),
        Err(err) => server_error_in(template.lang(), err, "failed to render a time page"),
    }
}

// ------------------------------------------------------------------------------------------------
// Settings › Time Rules
// ------------------------------------------------------------------------------------------------

/// A phone on the Time Rules page: its Time page, whether it follows its own rules, and its
/// bedtime as the sub-line (design 66 §2).
struct PhoneRow {
    id: i64,
    name: String,
    initial: String,
    avatar: &'static str,
    photo: Option<String>,
    /// The rules in force on the phone, its bedtime too: "3 rules".
    rules: String,
    bedtime: Bedtime,
}

/// A phone's bedtime as a row says it: "Sun-Thu 21:00-07:00 · Fri-Sat 22:30-09:00", none, or a
/// stored one that doesn't read (`text-alarm`).
struct Bedtime {
    unreadable: bool,
    /// Device › Time's row value.
    value: String,
    /// Settings › Time Rules' sub-line under the phone.
    line: String,
}

impl Bedtime {
    fn of(lang: Lang, row: Option<&TimeRuleRow>) -> Bedtime {
        let (unreadable, value, line) =
            match row.map(|row| crate::handlers::bedtime::bedtime_summary(lang, row)) {
                None => (
                    false,
                    t(lang, "device.time.bedtime_not_set").to_string(),
                    t(lang, "settings.time_rules.no_bedtime").to_string(),
                ),
                Some(Some(summary)) => (
                    false,
                    summary.clone(),
                    t_params(lang, "settings.time_rules.bedtime", &[("nights", &summary)]),
                ),
                Some(None) => (
                    true,
                    t(lang, "device.time.bedtime_unreadable").to_string(),
                    t(lang, "settings.time_rules.bedtime_unreadable").to_string(),
                ),
            };
        Bedtime {
            unreadable,
            value,
            line,
        }
    }

    fn unreadable(&self) -> bool {
        self.unreadable
    }

    fn value(&self) -> &str {
        &self.value
    }

    fn line(&self) -> &str {
        &self.line
    }
}

#[derive(Template)]
#[template(path = "schedules.html")]
struct SchedulesTemplate {
    lang: Lang,
    title: String,
    rules: Vec<RuleRow>,
    /// Everyone's daily limit, `None` when the stored one doesn't read.
    budget: Option<String>,
    phones: Vec<PhoneRow>,
    notice: Option<(&'static str, bool)>,
}
crate::i18n::words!(SchedulesTemplate);

/// GET `/schedules`: Settings › Time Rules. `?device=X` redirects to that phone's Time page.
pub async fn show_schedules(
    State(state): State<AppState>,
    lang: Lang,
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
            Err(err) => return read_failed_in(lang, err, "the global screen time"),
        };
    let rules = match time_rules::rule_set_rows(&state.db, None).await {
        Ok(rules) => rules,
        Err(err) => return read_failed_in(lang, err, "the global rules"),
    };
    let devices = match sqlx::query_as::<_, Device>("SELECT * FROM devices ORDER BY name")
        .fetch_all(&state.db)
        .await
    {
        Ok(devices) => devices,
        Err(err) => return read_failed_in(lang, err, "the devices"),
    };
    // Everyone's rules switched off on a phone (migration 0115): "Off for Emil" under the rule.
    let off: Vec<(i64, String)> = match sqlx::query_as(
        "SELECT o.rule_id, d.name FROM device_rule_off o JOIN devices d ON d.id = o.device_id \
         JOIN time_rules r ON r.id = o.rule_id WHERE r.device_id IS NULL ORDER BY d.name, d.id",
    )
    .fetch_all(&state.db)
    .await
    {
        Ok(off) => off,
        Err(err) => return read_failed_in(lang, err, "the rule switches"),
    };
    // Each phone's rules in force (its bedtime too), the row's value.
    let mut in_force = HashMap::new();
    for d in &devices {
        match time_rules::effective_rule_rows(&state.db, d.id).await {
            Ok(rules) => in_force.insert(d.id, rules.len() as i64),
            Err(err) => return read_failed_in(lang, err, "a phone's rules"),
        };
    }
    let bedtimes: HashMap<Option<i64>, TimeRuleRow> =
        match sqlx::query_as::<_, TimeRuleRow>("SELECT * FROM time_rules WHERE bedtime = 1")
            .fetch_all(&state.db)
            .await
        {
            Ok(rows) => rows.into_iter().map(|row| (row.device_id, row)).collect(),
            Err(err) => return read_failed_in(lang, err, "the bedtimes"),
        };
    let budget_json = budget_json.unwrap_or_else(|| time_rules::UNLIMITED_BUDGET_JSON.to_string());
    render(
        SchedulesTemplate {
            lang,
            title: t(lang, "settings.time_rules.title").to_string(),
            rules: rule_rows(lang, &rules)
                .into_iter()
                .map(|mut row| {
                    let names: Vec<&str> = off
                        .iter()
                        .filter(|(rule, _)| *rule == row.id)
                        .map(|(_, name)| name.as_str())
                        .collect();
                    if !names.is_empty() {
                        row.off_for = Some(t_params(
                            lang,
                            "settings.time_rules.off_for",
                            &[("names", &names.join(", "))],
                        ));
                    }
                    row
                })
                .collect(),
            budget: time_rules::parse_budget(&budget_json)
                .ok()
                .map(|b| budget_summary(lang, &b)),
            phones: devices
                .iter()
                .map(|d| PhoneRow {
                    id: d.id,
                    name: d.name.clone(),
                    initial: crate::handlers::devices::initial(&d.name),
                    avatar: crate::handlers::devices::avatar_class(d),
                    photo: crate::handlers::devices::avatar_photo(d),
                    rules: match in_force.get(&d.id).copied().unwrap_or(0) {
                        0 => t(lang, "device.about.none").to_string(),
                        n => t_count(lang, "device.time.rule_count", n, &[]),
                    },
                    bedtime: Bedtime::of(lang, bedtimes.get(&Some(d.id))),
                })
                .collect(),
            notice: rules_notice(lang, params.get("notice").map(String::as_str)),
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
fn budget_inputs(lang: Lang, json: &str) -> Vec<BudgetInput> {
    let values = time_rules::parse_budget(json).unwrap_or_else(|_| vec![None; 7]);
    (0..7)
        .map(|i| BudgetInput {
            index: i,
            name: t(lang, DAY_NAMES[i]),
            value: values[i].map(|m| m.to_string()).unwrap_or_default(),
            error: None,
        })
        .collect()
}

/// The 7 fields as typed, the refused one with its reason.
fn budget_entered(
    lang: Lang,
    fields: &[(String, String)],
    error: &(usize, String),
) -> Vec<BudgetInput> {
    (0..7)
        .map(|i| BudgetInput {
            index: i,
            name: t(lang, DAY_NAMES[i]),
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
    lang: Lang,
    title: String,
    budget: Vec<BudgetInput>,
    corrupt: bool,
    saved: bool,
}
crate::i18n::words!(BudgetTemplate);

/// A refused limits form: the fields as posted, and the refused day with its reason.
type RefusedBudget<'a> = (&'a [(String, String)], (usize, String));

async fn render_budget(
    state: &AppState,
    entered: Option<RefusedBudget<'_>>,
    saved: bool,
    lang: Lang,
) -> Response {
    let json: Option<String> =
        match sqlx::query_scalar("SELECT daily_budget_json FROM global_schedule WHERE id = 1")
            .fetch_optional(&state.db)
            .await
        {
            Ok(json) => json,
            Err(err) => return read_failed_in(lang, err, "the global screen time"),
        };
    let json = json.unwrap_or_else(|| time_rules::UNLIMITED_BUDGET_JSON.to_string());
    let refused = entered.is_some();
    render(
        BudgetTemplate {
            lang,
            title: t(lang, "device.time.limit").to_string(),
            corrupt: time_rules::parse_budget(&json).is_err(),
            budget: match &entered {
                Some((fields, error)) => budget_entered(lang, fields, error),
                None => budget_inputs(lang, &json),
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
    lang: Lang,
    Query(params): Query<HashMap<String, String>>,
) -> Response {
    render_budget(&state, None, params.contains_key("saved"), lang).await
}

// ------------------------------------------------------------------------------------------------
// The rule editor
// ------------------------------------------------------------------------------------------------

/// An app the rule may leave usable.
pub(crate) struct AppChoice {
    pub(crate) package: String,
    pub(crate) label: String,
    pub(crate) checked: bool,
    /// A ticked app the scope doesn't offer (not allowed, or not on the phones): the i18n key of
    /// its note under the name, so the tick stays in view and can be taken off - the page never
    /// drops a stored choice by itself.
    pub(crate) note: Option<&'static str>,
}

/// A rule's or the bedtime's Exceptions (`partials/exceptions.html`): the built-in Vibb calendar
/// row (Kalender on the phone; its own `calendar_allowed`, never a package) first, then the apps.
pub(crate) struct Exceptions {
    /// The Vibb calendar row shows: Kalender is on for a phone in scope, or the rule has it on.
    pub(crate) calendar_shown: bool,
    pub(crate) calendar: bool,
    pub(crate) apps: Vec<AppChoice>,
    /// The i18n key of the line when no app is listed.
    pub(crate) empty: &'static str,
    /// The i18n key of the group's footer: one phone's rule, or everyone's.
    pub(crate) footer: &'static str,
}

impl Exceptions {
    /// "Vibb-kalender, Klokke", "None", "Vibb-kalender, 3 apps": what the Exceptions row shows.
    /// (`&Lang`: Askama passes a template's arguments by reference.)
    pub(crate) fn value(&self, lang: &Lang) -> String {
        let lang = *lang;
        let apps: Vec<&str> = self
            .apps
            .iter()
            .filter(|a| a.checked)
            .map(|a| a.label.as_str())
            .collect();
        let calendar = t(lang, "device.exceptions.calendar");
        let mut chosen: Vec<&str> = Vec::new();
        if self.calendar {
            chosen.push(calendar);
        }
        chosen.extend(&apps);
        match chosen.len() {
            0 => t(lang, "device.exceptions.value_none").to_string(),
            1 | 2 => chosen.join(", "),
            _ => {
                let count = t_count(lang, "device.exceptions.value_apps", apps.len() as i64, &[]);
                if self.calendar {
                    format!("{calendar}, {count}")
                } else {
                    count
                }
            }
        }
    }
}

/// What a scope's Exceptions may offer (PLAN "the Exceptions list shows only apps the phone
/// allows", 2026-11-02): a phone's own rule and its bedtime the apps that phone allows and reported
/// installed; everyone's rules the apps allowed on at least one phone that reported them. A phone
/// without an allowlist (not managed yet) allows what it reports; one whose allowlist doesn't read
/// allows nothing here (its policy is a 500 until that is fixed).
pub(crate) struct ScopeApps {
    /// package -> label: offered.
    pub(crate) offered: BTreeMap<String, String>,
    /// package -> label: every app a phone in scope reported, allowed or not (a ticked one's note).
    pub(crate) reported: BTreeMap<String, String>,
    /// Kalender is on (`calendar_on_home`, the Calendar page's "Kalender on Home") for a phone in scope.
    pub(crate) calendar_on: bool,
    /// The scope is one phone: the notes and the footer say "the phone".
    pub(crate) one_phone: bool,
}

impl ScopeApps {
    /// The Exceptions for the rule's [chosen] apps and its [calendar] switch: every offered app,
    /// and every chosen one the scope doesn't offer with its note (so nothing stored disappears
    /// from the form - a save writes exactly what is shown ticked).
    pub(crate) fn exceptions(&self, chosen: &[String], calendar: bool) -> Exceptions {
        let mut apps: Vec<AppChoice> = self
            .offered
            .iter()
            .map(|(package, label)| AppChoice {
                checked: chosen.contains(package),
                package: package.clone(),
                label: label.clone(),
                note: None,
            })
            .collect();
        let mut seen: Vec<&String> = Vec::new();
        for package in chosen {
            if self.offered.contains_key(package) || seen.contains(&package) {
                continue;
            }
            seen.push(package);
            let reported = self.reported.get(package);
            apps.push(AppChoice {
                package: package.clone(),
                label: reported.cloned().unwrap_or_else(|| package.clone()),
                checked: true,
                note: Some(match (reported.is_some(), self.one_phone) {
                    (true, true) => "device.exceptions.not_allowed",
                    (true, false) => "device.exceptions.not_allowed_any",
                    (false, true) => "device.exceptions.not_installed",
                    (false, false) => "device.exceptions.not_installed_any",
                }),
            });
        }
        apps.sort_by_key(|a| a.label.to_lowercase());
        Exceptions {
            calendar_shown: self.calendar_on || calendar,
            calendar,
            apps,
            empty: if self.reported.is_empty() {
                "device.exceptions.none"
            } else {
                "device.exceptions.none_allowed"
            },
            footer: if self.one_phone {
                "device.exceptions.footer"
            } else {
                "device.exceptions.footer_everyone"
            },
        }
    }
}

/// package -> label from one device's latest installed-apps report.
async fn reported_by(state: &AppState, id: i64) -> Result<BTreeMap<String, String>, sqlx::Error> {
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
    let mut apps = BTreeMap::new();
    for app in installed {
        if time_rules::valid_package_name(&app.package_name) {
            apps.entry(app.package_name).or_insert(app.label);
        }
    }
    Ok(apps)
}

/// [ScopeApps] for the devices in `device_ids` (one phone when [one_phone]).
pub(crate) async fn scope_apps(
    state: &AppState,
    device_ids: &[i64],
    one_phone: bool,
) -> Result<ScopeApps, sqlx::Error> {
    let mut scope = ScopeApps {
        offered: BTreeMap::new(),
        reported: BTreeMap::new(),
        calendar_on: false,
        one_phone,
    };
    for &id in device_ids {
        let policy: Option<(Option<String>, bool)> = sqlx::query_as(
            "SELECT allowlist_json, calendar_on_home FROM device_policy WHERE device_id = ?",
        )
        .bind(id)
        .fetch_optional(&state.db)
        .await?;
        let Some((allowlist, calendar_on)) = policy else {
            continue;
        };
        scope.calendar_on |= calendar_on;
        let allowed: Option<Vec<String>> = match allowlist.as_deref().map(serde_json::from_str) {
            None => None,
            Some(Ok(list)) => Some(list),
            Some(Err(err)) => {
                tracing::warn!(device_id = id, %err, "an unreadable allowlist offers no exceptions");
                Some(Vec::new())
            }
        };
        for (package, label) in reported_by(state, id).await? {
            if allowed.as_ref().is_none_or(|list| list.contains(&package)) {
                scope
                    .offered
                    .entry(package.clone())
                    .or_insert_with(|| label.clone());
            }
            scope.reported.entry(package).or_insert(label);
        }
    }
    Ok(scope)
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
    /// The "Silent" switch (0121).
    silent: bool,
    days: Vec<DayInput>,
    /// For `partials/exceptions.html`: the Vibb calendar row and the apps.
    exceptions: Exceptions,
    name_error: Option<String>,
    apps_error: Option<String>,
}

fn day_inputs(lang: Lang, days: &[Option<DayWindow>]) -> Vec<DayInput> {
    (0..7)
        .map(|i| {
            let w = days.get(i).copied().flatten();
            DayInput {
                index: i,
                name: t(lang, DAY_NAMES[i]),
                short: t(lang, DAY_SHORT[i]),
                start: w
                    .map(|w| minutes_to_time_input(w.start))
                    .unwrap_or_default(),
                end: w.map(|w| minutes_to_time_input(w.end)).unwrap_or_default(),
                error: None,
            }
        })
        .collect()
}

/// Where a rule belongs: everyone's (`None`) or one phone's own, with the phone's name.
struct RuleScope {
    device: Option<(i64, String)>,
    /// The rule applies to the phone: not switched off for it (always true for everyone's and a
    /// new rule).
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

    /// What its exceptions offer: the phone's allowed apps, or those of every phone ([ScopeApps]).
    async fn apps(&self, state: &AppState) -> Result<ScopeApps, sqlx::Error> {
        match self.id() {
            Some(id) => scope_apps(state, &[id], true).await,
            None => scope_apps(state, &time_rules::all_device_ids(&state.db).await?, false).await,
        }
    }
}

#[derive(Template)]
#[template(path = "schedule_rule.html")]
struct RuleTemplate {
    lang: Lang,
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
    /// The kind select's options: value and label. Bedtime only on a day rule of that kind kept
    /// from before (design 66 §2), so a save keeps it.
    kinds: Vec<(&'static str, &'static str)>,
}
crate::i18n::words!(RuleTemplate);

fn render_rule(
    scope: &RuleScope,
    rule: RuleForm,
    saved: Option<&'static str>,
    corrupt: Option<String>,
    status: StatusCode,
    lang: Lang,
) -> Response {
    let refusal = rule
        .name_error
        .clone()
        .or_else(|| rule.days.iter().find_map(|d| d.error.clone()))
        .or_else(|| rule.apps_error.clone());
    // A phone's own rule switched off for it (migration 0115): saved, but not in force (qa N2).
    let saved = saved.map(|text| match &scope.device {
        Some((_, name)) if !scope.in_force => format!(
            "{text} {}",
            t_params(lang, "settings.time_rules.saved_off", &[("name", name)])
        ),
        _ => format!("{text} {}", t(lang, "settings.time_rules.phones_get_it")),
    });
    let (back_href, back_label) = match &scope.device {
        Some((id, _)) => (
            format!("/devices/{id}/time"),
            t(lang, "device.page.time").to_string(),
        ),
        None => (
            "/schedules".to_string(),
            t(lang, "settings.time_rules.title").to_string(),
        ),
    };
    render(
        RuleTemplate {
            lang,
            title: if rule.id == 0 {
                t(lang, "settings.time_rules.new_rule").to_string()
            } else if rule.name.trim().is_empty() {
                // A refused save with the name cleared.
                t(lang, "settings.time_rules.rule").to_string()
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
            kinds: if rule.kind == "bedtime" {
                vec![
                    ("school", t(lang, "settings.time_rules.kind_school")),
                    ("bedtime", t(lang, "device.bedtime.title")),
                    ("custom", t(lang, "settings.time_rules.kind_other")),
                ]
            } else {
                vec![
                    ("school", t(lang, "settings.time_rules.kind_school")),
                    ("custom", t(lang, "settings.time_rules.kind_other")),
                ]
            },
            rule,
        },
        status,
    )
}

/// The rule's scope from its row or the form: a device that must exist (404 otherwise).
async fn scope_of(
    state: &AppState,
    device: Option<i64>,
    lang: Lang,
) -> Result<RuleScope, Box<Response>> {
    let Some(id) = device else {
        return Ok(RuleScope {
            device: None,
            in_force: true,
        });
    };
    match sqlx::query_scalar::<_, String>(
        "SELECT d.name FROM devices d JOIN device_policy p ON p.device_id = d.id WHERE d.id = ?",
    )
    .bind(id)
    .fetch_optional(&state.db)
    .await
    {
        // A phone's own rules apply unless switched off ([show_rule] reads its switch).
        Ok(Some(name)) => Ok(RuleScope {
            device: Some((id, name)),
            in_force: true,
        }),
        Ok(None) => Err(Box::new(crate::i18n::error(
            lang,
            StatusCode::NOT_FOUND,
            "error.device_not_found",
        ))),
        Err(err) => Err(Box::new(read_failed_in(lang, err, "a device"))),
    }
}

/// `scope` from a form: "" = global, otherwise a device id that must exist (else 404).
async fn parse_scope(state: &AppState, raw: &str, lang: Lang) -> Result<RuleScope, Box<Response>> {
    let raw = raw.trim();
    if raw.is_empty() {
        return scope_of(state, None, lang).await;
    }
    let Ok(id) = raw.parse::<i64>() else {
        return Err(Box::new(crate::i18n::error(
            lang,
            StatusCode::BAD_REQUEST,
            "error.unknown_scope",
        )));
    };
    scope_of(state, Some(id), lang).await
}

/// The parts of a stored rule that don't read, for the editor's warning: they open empty (or as
/// Other) and a save writes them as shown (qa-31-s9-code L3). `None` for a readable rule.
fn unreadable_parts(lang: Lang, row: &TimeRuleRow) -> Option<String> {
    let kind = !RULE_KINDS.contains(&row.kind.as_str());
    let days = time_rules::parse_days(&row.days_json).is_err();
    let apps = time_rules::parse_exempt_apps(&row.exempt_apps_json).is_err();
    let key = match (kind, days, apps) {
        (false, false, false) => return None,
        (true, false, false) => "settings.time_rules.unreadable_type",
        (false, true, false) => "settings.time_rules.unreadable_days",
        (false, false, true) => "settings.time_rules.unreadable_apps",
        (true, true, false) => "settings.time_rules.unreadable_type_days",
        (true, false, true) => "settings.time_rules.unreadable_type_apps",
        (false, true, true) => "settings.time_rules.unreadable_days_apps",
        (true, true, true) => "settings.time_rules.unreadable_all",
    };
    Some(t(lang, key).to_string())
}

/// GET `/schedules/rules/{id}`: the editor. A rule whose stored data doesn't read opens with what
/// does, and a warning; saving it again fixes it. A phone's bedtime row (a rule converted in place
/// keeps its id, so an old link names it) goes to its Bedtime page (design 66 §3.3).
pub async fn show_rule(
    State(state): State<AppState>,
    lang: Lang,
    Path(rule_id): Path<i64>,
    Query(params): Query<HashMap<String, String>>,
) -> Response {
    let row = match sqlx::query_as::<_, TimeRuleRow>("SELECT * FROM time_rules WHERE id = ?")
        .bind(rule_id)
        .fetch_optional(&state.db)
        .await
    {
        Ok(Some(row)) => row,
        Ok(None) => {
            return crate::i18n::error(lang, StatusCode::NOT_FOUND, "error.rule_not_found");
        }
        Err(err) => return read_failed_in(lang, err, "a time rule"),
    };
    if row.bedtime {
        return match row.device_id {
            Some(device) => Redirect::to(&format!("/devices/{device}/bedtime")).into_response(),
            None => crate::i18n::error(lang, StatusCode::NOT_FOUND, "error.rule_not_found"),
        };
    }
    let mut scope = match scope_of(&state, row.device_id, lang).await {
        Ok(scope) => scope,
        Err(answer) => return *answer,
    };
    if let Some(device) = row.device_id {
        scope.in_force = match time_rules::rules_off(&state.db, device).await {
            Ok(off) => !off.contains(&row.id),
            Err(err) => return read_failed_in(lang, err, "the phone's rule switches"),
        };
    }
    let available = match scope.apps(&state).await {
        Ok(apps) => apps,
        Err(err) => return read_failed_in(lang, err, "the reported apps"),
    };
    let corrupt = unreadable_parts(lang, &row);
    let days = time_rules::parse_days(&row.days_json).unwrap_or_else(|_| vec![None; 7]);
    let exempt = time_rules::parse_exempt_apps(&row.exempt_apps_json).unwrap_or_default();
    let rule = RuleForm {
        id: row.id,
        kind: if RULE_KINDS.contains(&row.kind.as_str()) {
            row.kind.clone()
        } else {
            "custom".to_string()
        },
        // A name the server made in the parent's words (design 70 §7); saved unchanged, it stays.
        name: time_rules::shown_name(lang, &row.name),
        calls_allowed: row.calls_allowed,
        silent: row.silent,
        days: day_inputs(lang, &days),
        exceptions: available.exceptions(&exempt, row.calendar_allowed),
        name_error: None,
        apps_error: None,
    };
    let saved = match params.get("saved").map(String::as_str) {
        Some("added") => Some(t(lang, "settings.time_rules.added")),
        Some(_) => Some(t(lang, "common.saved")),
        None => None,
    };
    render_rule(&scope, rule, saved, corrupt, StatusCode::OK, lang)
}

/// GET `/schedules/rules/new` (`?device=X` for a phone's own rule): the editor, empty.
pub async fn new_rule(
    State(state): State<AppState>,
    lang: Lang,
    Query(params): Query<HashMap<String, String>>,
) -> Response {
    let scope = match parse_scope(
        &state,
        params.get("device").map_or("", String::as_str),
        lang,
    )
    .await
    {
        Ok(scope) => scope,
        Err(answer) => return *answer,
    };
    let available = match scope.apps(&state).await {
        Ok(apps) => apps,
        Err(err) => return read_failed_in(lang, err, "the reported apps"),
    };
    let rule = RuleForm {
        id: 0,
        name: String::new(),
        kind: "school".to_string(),
        calls_allowed: false,
        // School is silent by definition (the user, 2026-11-02); the kind select presets it.
        silent: true,
        days: day_inputs(lang, &[]),
        exceptions: available.exceptions(&[], false),
        name_error: None,
        apps_error: None,
    };
    render_rule(&scope, rule, None, None, StatusCode::OK, lang)
}

/// A validated rule form.
#[derive(Debug, PartialEq, Eq)]
pub(crate) struct RuleInput {
    pub name: String,
    pub kind: String,
    pub calls_allowed: bool,
    pub exempt_apps: Vec<String>,
    /// The Vibb calendar row (`calendar_allowed`, absent = off).
    pub calendar_allowed: bool,
    /// The "Silent" switch (`silent`, absent = off).
    pub silent: bool,
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

pub(crate) fn field<'a>(fields: &'a [(String, String)], key: &str) -> &'a str {
    fields
        .iter()
        .find(|(k, _)| k == key)
        .map(|(_, v)| v.as_str())
        .unwrap_or("")
}

/// The ticked `exempt_apps` (a rule's or the bedtime's): package names, each once, at most
/// [MAX_EXEMPT_APPS]; the refusal's words otherwise.
pub(crate) fn parse_exempt_apps_form(
    lang: Lang,
    fields: &[(String, String)],
) -> Result<Vec<String>, String> {
    let mut exempt_apps: Vec<String> = Vec::new();
    for (k, v) in fields {
        if k == "exempt_apps" && !exempt_apps.contains(v) {
            if !time_rules::valid_package_name(v) {
                return Err(t(lang, "settings.time_rules.not_an_app").to_string());
            }
            exempt_apps.push(v.clone());
        }
    }
    if exempt_apps.len() > MAX_EXEMPT_APPS {
        return Err(t_params(
            lang,
            "settings.time_rules.too_many_apps",
            &[("max", &MAX_EXEMPT_APPS.to_string())],
        ));
    }
    Ok(exempt_apps)
}

/// The rule form, or why not in `lang`.
pub(crate) fn parse_rule_form(
    lang: Lang,
    fields: &[(String, String)],
) -> Result<RuleInput, RuleError> {
    let name = field(fields, "name").trim().to_string();
    if name.is_empty() || name.chars().count() > MAX_RULE_NAME_CHARS {
        return Err(RuleError::Name(t_params(
            lang,
            "settings.time_rules.name_length",
            &[("max", &MAX_RULE_NAME_CHARS.to_string())],
        )));
    }
    let kind = field(fields, "kind").to_string();
    if !RULE_KINDS.contains(&kind.as_str()) {
        return Err(RuleError::Kind);
    }
    let mut days = Vec::with_capacity(7);
    for (i, day) in DAY_NAMES.iter().map(|key| t(lang, key)).enumerate() {
        let refused = |why: &str| RuleError::Day(i, t_params(lang, why, &[("day", day)]));
        let start = field(fields, &format!("d{i}_start")).trim();
        let end = field(fields, &format!("d{i}_end")).trim();
        if start.is_empty() && end.is_empty() {
            days.push(None);
            continue;
        }
        match (time_input_to_minutes(start), time_input_to_minutes(end)) {
            // A day rule starts and ends on its day (design 66 §3.3). An end of 00:00 is "until
            // midnight" and is stored as 23:59, as the conversion stores it (qa-66-code L2;
            // 00:00-00:00 is the whole day); 23:59-23:59 is "23:59 to midnight", so it saves
            // unchanged.
            (Some(start), Some(0)) => days.push(Some(DayWindow {
                start,
                end: LAST_MINUTE,
            })),
            (Some(start), Some(end))
                if end > start || (start, end) == (LAST_MINUTE, LAST_MINUTE) =>
            {
                days.push(Some(DayWindow { start, end }))
            }
            (Some(_), Some(_)) => return Err(refused("settings.time_rules.end_after_start")),
            _ if start.is_empty() || end.is_empty() => {
                return Err(refused("settings.time_rules.both_times"));
            }
            _ => return Err(refused("settings.time_rules.time_format")),
        }
    }
    let exempt_apps = parse_exempt_apps_form(lang, fields).map_err(RuleError::Apps)?;
    Ok(RuleInput {
        calls_allowed: fields.iter().any(|(k, _)| k == "calls_allowed"),
        calendar_allowed: fields.iter().any(|(k, _)| k == "calendar_allowed"),
        silent: fields.iter().any(|(k, _)| k == "silent"),
        name,
        kind,
        exempt_apps,
        days,
    })
}

/// The editor as typed, with the refusal under its field. What was typed goes back escaped and
/// cut short; an app that isn't a package name is dropped.
fn rule_entered(
    lang: Lang,
    id: i64,
    fields: &[(String, String)],
    available: &ScopeApps,
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
        silent: fields.iter().any(|(k, _)| k == "silent"),
        days: (0..7)
            .map(|i| DayInput {
                index: i,
                name: t(lang, DAY_NAMES[i]),
                short: t(lang, DAY_SHORT[i]),
                start: typed(&format!("d{i}_start"), 8),
                end: typed(&format!("d{i}_end"), 8),
                error: None,
            })
            .collect(),
        exceptions: available
            .exceptions(&chosen, fields.iter().any(|(k, _)| k == "calendar_allowed")),
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
pub(crate) async fn log_change(
    state: &AppState,
    admin: &crate::models::AdminUser,
    event: &str,
    scope: Option<i64>,
    what: &str,
) {
    security::record_security_event(
        &state.db,
        event,
        Some(&admin.username),
        None,
        Some(crate::time_rules::scoped_detail(scope, what)),
    )
    .await;
}

/// Nudges the devices a change in `scope` affects: one device, or every device for a global one.
pub(crate) async fn nudge(state: &AppState, scope: Option<i64>) {
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

pub(crate) fn form_pairs(body: &Bytes) -> Vec<(String, String)> {
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
    lang: Lang,
) -> Response {
    let id = stored.map_or(0, |row| row.id);
    if error == RuleError::Kind {
        return crate::i18n::error(lang, StatusCode::BAD_REQUEST, "error.unknown_rule_kind");
    }
    let available = match scope.apps(state).await {
        Ok(apps) => apps,
        Err(err) => return read_failed_in(lang, err, "the reported apps"),
    };
    let form = rule_entered(lang, id, fields, &available, error);
    render_rule(
        scope,
        form,
        None,
        stored.and_then(|row| unreadable_parts(lang, row)),
        StatusCode::BAD_REQUEST,
        lang,
    )
}

/// POST `/schedules/rules`: a new rule, then its page.
pub async fn create_rule(
    State(state): State<AppState>,
    lang: Lang,
    Extension(CurrentAdmin(admin)): Extension<CurrentAdmin>,
    body: Bytes,
) -> Response {
    let fields = form_pairs(&body);
    let scope = match parse_scope(&state, field(&fields, "scope"), lang).await {
        Ok(scope) => scope,
        Err(response) => return *response,
    };
    let input = match parse_rule_form(lang, &fields) {
        Ok(input) => input,
        Err(error) => return refuse_rule(&state, &scope, None, &fields, error, lang).await,
    };
    let device = scope.id();
    let result = sqlx::query_scalar::<_, i64>(
        "INSERT INTO time_rules (device_id, name, kind, calls_allowed, exempt_apps_json, \
         calendar_allowed, silent, days_json, sort_order) VALUES (?, ?, ?, ?, ?, ?, ?, ?, \
         (SELECT COALESCE(MAX(sort_order), -1) + 1 FROM time_rules WHERE device_id IS ?)) \
         RETURNING id",
    )
    .bind(device)
    .bind(&input.name)
    .bind(&input.kind)
    .bind(input.calls_allowed)
    .bind(serde_json::to_string(&input.exempt_apps).expect("strings serialize"))
    .bind(input.calendar_allowed)
    .bind(input.silent)
    .bind(serde_json::to_string(&input.days).expect("windows serialize"))
    .bind(device)
    .fetch_one(&state.db)
    .await;
    let rule_id = match result {
        Ok(id) => id,
        Err(err) => return server_error_in(lang, err, "failed to add a time rule"),
    };
    log_change(&state, &admin, "time_rule_created", device, &input.name).await;
    nudge(&state, device).await;
    Redirect::to(&format!("/schedules/rules/{rule_id}?saved=added#rule")).into_response()
}

/// The rule, if it still exists and belongs to `scope` (a device's own rule can't be edited
/// through the global form and vice versa): `Ok(None)` when it is gone, a 404 for another scope's
/// and for a phone's bedtime (its own page writes it; day windows never go into it, design 66).
async fn rule_in_scope(
    state: &AppState,
    rule_id: i64,
    scope: Option<i64>,
    lang: Lang,
) -> Result<Option<TimeRuleRow>, Box<Response>> {
    match sqlx::query_as::<_, TimeRuleRow>("SELECT * FROM time_rules WHERE id = ?")
        .bind(rule_id)
        .fetch_optional(&state.db)
        .await
    {
        Ok(Some(rule)) if rule.device_id == scope && !rule.bedtime => Ok(Some(rule)),
        Ok(Some(_)) => Err(Box::new(crate::i18n::error(
            lang,
            StatusCode::NOT_FOUND,
            "error.rule_not_found",
        ))),
        Ok(None) => Ok(None),
        Err(err) => Err(Box::new(read_failed_in(lang, err, "a time rule"))),
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
    lang: Lang,
    Extension(CurrentAdmin(admin)): Extension<CurrentAdmin>,
    Path(rule_id): Path<i64>,
    body: Bytes,
) -> Response {
    let fields = form_pairs(&body);
    let scope = match parse_scope(&state, field(&fields, "scope"), lang).await {
        Ok(scope) => scope,
        Err(response) => return *response,
    };
    let stored = match rule_in_scope(&state, rule_id, scope.id(), lang).await {
        Ok(Some(row)) => row,
        Ok(None) => return crate::settings_log::refused(to_list(&scope, "gone_unsaved")),
        Err(response) => return *response,
    };
    let mut input = match parse_rule_form(lang, &fields) {
        Ok(input) => input,
        Err(error) => {
            return refuse_rule(&state, &scope, Some(&stored), &fields, error, lang).await;
        }
    };
    // The name the form showed (`shown_name`) is no rename: the server's own name stays, and the
    // phone keeps naming the rule itself (design 70 §7).
    if input.name == time_rules::shown_name(lang, &stored.name) {
        input.name = stored.name.clone();
    }
    let result = sqlx::query(
        "UPDATE time_rules SET name = ?, kind = ?, calls_allowed = ?, exempt_apps_json = ?, \
         calendar_allowed = ?, silent = ?, days_json = ? WHERE id = ? AND device_id IS ? \
         AND bedtime = 0",
    )
    .bind(&input.name)
    .bind(&input.kind)
    .bind(input.calls_allowed)
    .bind(serde_json::to_string(&input.exempt_apps).expect("strings serialize"))
    .bind(input.calendar_allowed)
    .bind(input.silent)
    .bind(serde_json::to_string(&input.days).expect("windows serialize"))
    .bind(rule_id)
    .bind(scope.id())
    .execute(&state.db)
    .await;
    match result {
        // Deleted between the check and the write.
        Ok(done) if done.rows_affected() == 0 => {
            return crate::settings_log::refused(to_list(&scope, "gone_unsaved"));
        }
        Ok(_) => {}
        Err(err) => return server_error_in(lang, err, "failed to save a time rule"),
    }
    log_change(&state, &admin, "time_rule_updated", scope.id(), &input.name).await;
    nudge(&state, scope.id()).await;
    // Its form (§5.1.4): Save is at the top, and scroll-restore keeps the exact place.
    Redirect::to(&format!("/schedules/rules/{rule_id}?saved=1#rule")).into_response()
}

/// POST `/schedules/rules/{id}/delete`: then the list it was in.
pub async fn delete_rule(
    State(state): State<AppState>,
    lang: Lang,
    Extension(CurrentAdmin(admin)): Extension<CurrentAdmin>,
    Path(rule_id): Path<i64>,
    body: Bytes,
) -> Response {
    let fields = form_pairs(&body);
    let scope = match parse_scope(&state, field(&fields, "scope"), lang).await {
        Ok(scope) => scope,
        Err(response) => return *response,
    };
    let rule = match rule_in_scope(&state, rule_id, scope.id(), lang).await {
        Ok(Some(rule)) => rule,
        Ok(None) => return crate::settings_log::refused(to_list(&scope, "gone")),
        Err(response) => return *response,
    };
    if let Err(err) =
        sqlx::query("DELETE FROM time_rules WHERE id = ? AND device_id IS ? AND bedtime = 0")
            .bind(rule_id)
            .bind(scope.id())
            .execute(&state.db)
            .await
    {
        return server_error_in(lang, err, "failed to delete a time rule");
    }
    log_change(&state, &admin, "time_rule_deleted", scope.id(), &rule.name).await;
    nudge(&state, scope.id()).await;
    to_list(&scope, "deleted")
}

/// `b0`..`b6` (Monday first): minutes 0-1440, blank = unlimited; a refusal names its day.
pub(crate) fn parse_budget_form(
    lang: Lang,
    fields: &[(String, String)],
) -> Result<String, (usize, String)> {
    let mut budget: Vec<Option<i64>> = Vec::with_capacity(7);
    for (i, day) in DAY_NAMES.iter().map(|key| t(lang, key)).enumerate() {
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
                    t_params(lang, "settings.time_rules.limit_range", &[("day", day)]),
                ));
            }
        }
    }
    Ok(serde_json::to_string(&budget).expect("numbers serialize"))
}

/// POST `/schedules/global`: everyone's daily limits.
pub async fn save_global_schedule(
    State(state): State<AppState>,
    lang: Lang,
    Extension(CurrentAdmin(admin)): Extension<CurrentAdmin>,
    body: Bytes,
) -> Response {
    let fields = form_pairs(&body);
    let budget = match parse_budget_form(lang, &fields) {
        Ok(budget) => budget,
        Err(error) => return render_budget(&state, Some((&fields, error)), false, lang).await,
    };
    if let Err(err) = sqlx::query(
        "UPDATE global_schedule SET daily_budget_json = ?, updated_at = datetime('now') \
         WHERE id = 1",
    )
    .bind(&budget)
    .execute(&state.db)
    .await
    {
        return server_error_in(lang, err, "failed to save the global screen time");
    }
    security::record_security_event(
        &state.db,
        "screen_time_saved",
        Some(&admin.username),
        None,
        Some(security::Detail::words(
            "security.detail.screen_time.budget",
            &[("budget", &budget)],
        )),
    )
    .await;
    nudge(&state, None).await;
    Redirect::to("/schedules/screen-time?saved=1#screen-time").into_response()
}

/// POST `/schedules/device/{id}`: a phone's Own limit switch (`section=limit`, field `own_limit`)
/// or its own limits (`section=budget`). An older tab's `section=switch` (the Own rules switch
/// until migration 0115, field `custom_schedule_enabled`) writes the same column, now the limit's
/// switch alone; without a section (a page from before design 31 step 9) both.
pub async fn save_device_schedule(
    State(state): State<AppState>,
    lang: Lang,
    Extension(CurrentAdmin(admin)): Extension<CurrentAdmin>,
    Path(id): Path<i64>,
    body: Bytes,
) -> Response {
    let fields = form_pairs(&body);
    let section = field(&fields, "section");
    let (switch, limits, switch_field) = match section {
        "limit" => (true, false, "own_limit"),
        "switch" => (true, false, "custom_schedule_enabled"),
        "budget" => (false, true, ""),
        "" => (true, true, "custom_schedule_enabled"),
        _ => {
            return crate::i18n::error(lang, StatusCode::BAD_REQUEST, "error.unknown_section");
        }
    };
    let budget = if limits {
        match parse_budget_form(lang, &fields) {
            Ok(budget) => Some(budget),
            Err(error) => {
                return render_device_time(
                    &state,
                    id,
                    &HashMap::new(),
                    Some((&fields, error)),
                    lang,
                )
                .await;
            }
        }
    } else {
        None
    };
    let custom_enabled = switch.then(|| fields.iter().any(|(k, _)| k == switch_field));
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
            crate::i18n::error(lang, StatusCode::NOT_FOUND, "error.device_not_found")
        }
        Ok(_) => {
            let mut detail = security::Detail::facts(format!("device {id}"));
            if let Some(on) = custom_enabled {
                detail = detail.and(
                    if on {
                        "security.detail.screen_time.own_limit.on"
                    } else {
                        "security.detail.screen_time.own_limit.off"
                    },
                    &[],
                );
            }
            if let Some(budget) = &budget {
                detail = detail.and("security.detail.screen_time.budget", &[("budget", budget)]);
            }
            security::record_security_event(
                &state.db,
                "screen_time_saved",
                Some(&admin.username),
                None,
                Some(detail),
            )
            .await;
            nudge(&state, Some(id)).await;
            match section {
                "limit" | "switch" => Redirect::to(&format!("/devices/{id}/time#screen-time")),
                "budget" => Redirect::to(&format!("/devices/{id}/time?saved=budget#screen-time")),
                _ => Redirect::to(&format!("/devices/{id}/time")),
            }
            .into_response()
        }
        Err(err) => server_error_in(lang, err, "failed to save a device's time rules"),
    }
}

/// POST `/devices/{id}/time/rules/{rule_id}`: one rule's switch for the phone (migration 0115),
/// the checkbox `on` (absent = off): everyone's rules and the phone's own, never its bedtime or
/// another phone's rule (404). A rule deleted in another tab lands back with nothing written.
/// Lands on its group: `#everyone`, an own rule `#own-rules`.
pub async fn switch_device_rule(
    State(state): State<AppState>,
    lang: Lang,
    Extension(CurrentAdmin(admin)): Extension<CurrentAdmin>,
    Path((id, rule_id)): Path<(i64, i64)>,
    body: Bytes,
) -> Response {
    let on = form_pairs(&body).iter().any(|(k, _)| k == "on");
    match sqlx::query_scalar::<_, i64>("SELECT device_id FROM device_policy WHERE device_id = ?")
        .bind(id)
        .fetch_optional(&state.db)
        .await
    {
        Ok(Some(_)) => {}
        Ok(None) => {
            return crate::i18n::error(lang, StatusCode::NOT_FOUND, "error.device_not_found");
        }
        Err(err) => return read_failed_in(lang, err, "a device"),
    }
    let rule = match sqlx::query_as::<_, TimeRuleRow>("SELECT * FROM time_rules WHERE id = ?")
        .bind(rule_id)
        .fetch_optional(&state.db)
        .await
    {
        Ok(Some(rule)) if !rule.bedtime && rule.device_id.is_none_or(|d| d == id) => rule,
        Ok(Some(_)) => {
            return crate::i18n::error(lang, StatusCode::NOT_FOUND, "error.rule_not_found");
        }
        Ok(None) => return Redirect::to(&format!("/devices/{id}/time#everyone")).into_response(),
        Err(err) => return read_failed_in(lang, err, "a time rule"),
    };
    let group = if rule.device_id.is_some() {
        "own-rules"
    } else {
        "everyone"
    };
    let written = if on {
        sqlx::query("DELETE FROM device_rule_off WHERE device_id = ? AND rule_id = ?")
            .bind(id)
            .bind(rule_id)
            .execute(&state.db)
            .await
    } else {
        sqlx::query("INSERT OR IGNORE INTO device_rule_off (device_id, rule_id) VALUES (?, ?)")
            .bind(id)
            .bind(rule_id)
            .execute(&state.db)
            .await
    };
    match written {
        // Already so (a second tap, another tab): nothing to log or send.
        Ok(done) if done.rows_affected() == 0 => {}
        Ok(_) => {
            security::record_security_event(
                &state.db,
                "time_rule_updated",
                Some(&admin.username),
                None,
                Some(
                    security::Detail::words(
                        if on {
                            "security.detail.rule.on_for"
                        } else {
                            "security.detail.rule.off_for"
                        },
                        &[("device", &id.to_string()), ("what", &rule.name)],
                    )
                    .with_facts(format!("device {id}")),
                ),
            )
            .await;
            nudge(&state, Some(id)).await;
        }
        Err(err) => return server_error_in(lang, err, "failed to switch a rule for a phone"),
    }
    Redirect::to(&format!("/devices/{id}/time#{group}")).into_response()
}

// ------------------------------------------------------------------------------------------------
// Device › Time
// ------------------------------------------------------------------------------------------------

#[derive(Template)]
#[template(path = "device_time.html")]
struct DeviceTimeTemplate {
    lang: Lang,
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
    /// The phone's bedtime (design 66), its row to the Bedtime page.
    bedtime: Bedtime,
    /// The flash after Remove Bedtime.
    bedtime_removed: bool,
    /// Everyone's rules, each with its switch for this phone (`off`: switched off here).
    everyone: Vec<RuleRow>,
    /// The phone's own rules: rows to the editor, a switch while one is off.
    own_rules: Vec<RuleRow>,
    /// One of them is off: the footer says why.
    own_off: bool,
    own_notice: Option<(&'static str, bool)>,
    /// The Own limit switch (`custom_schedule_enabled`): on, the 7 day rows; off, everyone's.
    own_limit: bool,
    /// Everyone's daily limit as the off row's value, `None` when it doesn't read.
    everyone_limit: Option<String>,
    budget: Vec<BudgetInput>,
    budget_corrupt: bool,
    budget_saved: bool,
}
crate::i18n::words!(DeviceTimeTemplate);

/// GET `/devices/{id}/time`: Device › Time.
pub async fn view_device_time(
    State(state): State<AppState>,
    lang: Lang,
    Path(id): Path<i64>,
    Query(query): Query<HashMap<String, String>>,
) -> Response {
    render_device_time(&state, id, &query, None, lang).await
}

/// The page; `entered` is a refused own-limits save (its page again, 400, as typed).
async fn render_device_time(
    state: &AppState,
    id: i64,
    query: &HashMap<String, String>,
    entered: Option<RefusedBudget<'_>>,
    lang: Lang,
) -> Response {
    let device = match sqlx::query_as::<_, Device>("SELECT * FROM devices WHERE id = ?")
        .bind(id)
        .fetch_optional(&state.db)
        .await
    {
        Ok(Some(device)) => device,
        Ok(None) => {
            return crate::i18n::error(lang, StatusCode::NOT_FOUND, "error.device_not_found");
        }
        Err(err) => return read_failed_in(lang, err, "the device"),
    };
    let policy =
        match sqlx::query_as::<_, DevicePolicy>("SELECT * FROM device_policy WHERE device_id = ?")
            .bind(id)
            .fetch_optional(&state.db)
            .await
        {
            Ok(Some(policy)) => policy,
            Ok(None) => {
                return crate::i18n::error(lang, StatusCode::NOT_FOUND, "error.device_not_found");
            }
            Err(err) => return read_failed_in(lang, err, "the device's settings"),
        };
    let latest = match sqlx::query_as::<_, DeviceStatus>(
        "SELECT * FROM device_status WHERE device_id = ? ORDER BY reported_at DESC, id DESC LIMIT 1",
    )
    .bind(id)
    .fetch_optional(&state.db)
    .await
    {
        Ok(latest) => latest,
        Err(err) => return read_failed_in(lang, err, "the last report"),
    };
    let effective = match time_rules::effective_rule_rows(&state.db, id).await {
        Ok(rules) => rules,
        Err(err) => return read_failed_in(lang, err, "the phone's rules"),
    };
    let off = match time_rules::rules_off(&state.db, id).await {
        Ok(off) => off,
        Err(err) => return read_failed_in(lang, err, "the phone's rule switches"),
    };
    let switched = |rows: &[TimeRuleRow]| -> Vec<RuleRow> {
        rule_rows(lang, rows)
            .into_iter()
            .map(|mut row| {
                row.off = off.contains(&row.id);
                row
            })
            .collect()
    };
    let everyone = match time_rules::rule_set_rows(&state.db, None).await {
        Ok(rules) => switched(&rules),
        Err(err) => return read_failed_in(lang, err, "the global rules"),
    };
    let own = match time_rules::rule_set_rows(&state.db, Some(id)).await {
        Ok(rules) => switched(&rules),
        Err(err) => return read_failed_in(lang, err, "the phone's own rules"),
    };
    let everyone_limit: Option<String> = match sqlx::query_scalar::<_, String>(
        "SELECT daily_budget_json FROM global_schedule WHERE id = 1",
    )
    .fetch_optional(&state.db)
    .await
    {
        Ok(json) => {
            time_rules::parse_budget(json.as_deref().unwrap_or(time_rules::UNLIMITED_BUDGET_JSON))
                .ok()
                .map(|b| budget_summary(lang, &b))
        }
        Err(err) => return read_failed_in(lang, err, "the global screen time"),
    };
    let call_problems = match crate::handlers::calls::call_report(state, &policy).await {
        Ok(report) => report.problems(),
        Err(err) => return read_failed_in(lang, err, "the call report"),
    };
    let zone = Zone::load(&state.db, lang).await;
    let now = Utc::now();

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
                Some(badge) => badge.words(lang, Some(&zone.relative(*reported_at, now))),
                None => t(lang, "device.about.none").to_string(),
            };
            let screen = screen.map(|st| {
                (
                    st.text(lang),
                    report.extra_minutes.map(|m| {
                        t_params(
                            lang,
                            "device.time.includes_extra",
                            &[("extra", &crate::display_time::duration(lang, m))],
                        )
                    }),
                )
            });
            (lock, badge.is_some(), screen)
        }
        _ => (t(lang, "device.time.no_report").to_string(), false, None),
    };

    let mut warnings = Vec::new();
    if crate::handlers::devices::launcher_without_rules(latest.as_ref()) {
        warnings.push(Note {
            warning: true,
            title: t(lang, "device.time.too_old").to_string(),
            value: None,
            detail: Some(t(lang, "device.time.too_old_sub").to_string()),
            fix: None,
        });
    }
    if report.as_ref().is_some_and(|(_, r)| r.ledger_unreadable) {
        warnings.push(Note {
            warning: true,
            title: t(lang, "device.time.ledger_unreadable").to_string(),
            value: None,
            detail: Some(t(lang, "device.time.ledger_unreadable_sub").to_string()),
            fix: None,
        });
    }
    if !policy.calls_managed && effective.iter().any(|r| !r.calls_allowed) {
        warnings.push(Note {
            warning: true,
            title: t(lang, "device.time.incoming_not_blocked").to_string(),
            value: None,
            detail: Some(t(lang, "device.time.incoming_not_blocked_sub").to_string()),
            fix: None,
        });
    }

    let refused = entered.is_some();
    render(
        DeviceTimeTemplate {
            lang,
            title: format!("{} · {}", t(lang, "device.page.time"), device.name),
            warnings,
            lock,
            locking,
            screen,
            reported,
            bedtime: Bedtime::of(lang, effective.iter().find(|r| r.bedtime)),
            bedtime_removed: query.get("notice").map(String::as_str) == Some("bedtime_removed"),
            everyone,
            own_off: own.iter().any(|r| r.off),
            own_rules: own,
            own_notice: rules_notice(lang, query.get("notice").map(String::as_str)),
            // A refused limits save keeps the rows open, as typed.
            own_limit: policy.custom_schedule_enabled || refused,
            everyone_limit,
            budget_corrupt: time_rules::parse_budget(&policy.daily_budget_json).is_err(),
            budget: match &entered {
                Some((fields, error)) => budget_entered(lang, fields, error),
                None => budget_inputs(lang, &policy.daily_budget_json),
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
        assert_eq!(days_summary(Lang::En, &days), "Mon-Fri 08:15-14:00");
        let bed = vec![
            Some(DayWindow {
                start: 1260,
                end: 420
            });
            7
        ];
        assert_eq!(days_summary(Lang::En, &bed), "Every day 21:00-07:00");
        let mut mixed = vec![None; 7];
        mixed[5] = Some(DayWindow {
            start: 0,
            end: LAST_MINUTE,
        });
        mixed[0] = w;
        assert_eq!(
            days_summary(Lang::En, &mixed),
            "Mon 08:15-14:00, Sat 00:00-23:59"
        );
        assert_eq!(days_summary(Lang::En, &[None; 7]), "Never");
        // In Norwegian, the days are lower-case but the first.
        assert_eq!(days_summary(Lang::Nb, &days), "Man-fre 08:15-14:00");
        assert_eq!(
            days_summary(Lang::Nb, &mixed),
            "Man 08:15-14:00, lør 00:00-23:59"
        );
        assert_eq!(days_summary(Lang::Nb, &bed), "Hver dag 21:00-07:00");
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
        assert_eq!(
            budget_summary(Lang::En, &weekdays),
            "Mon-Fri 90 min · Sat-Sun 2 h"
        );
        assert_eq!(budget_summary(Lang::En, &[Some(90); 7]), "Every day 90 min");
        assert_eq!(budget_summary(Lang::En, &[Some(180); 7]), "Every day 3 h");
        assert_eq!(budget_summary(Lang::En, &[None; 7]), "No limit");
        let one = [Some(0), None, None, None, None, None, None];
        assert_eq!(
            budget_summary(Lang::En, &one),
            "Mon 0 min · Tue-Sun no limit"
        );
        assert_eq!(
            budget_summary(Lang::Nb, &weekdays),
            "Man-fre 90 min · lør-søn 2 t"
        );
        assert_eq!(
            budget_summary(Lang::Nb, &one),
            "Man 0 min · tir-søn ingen grense"
        );
    }

    #[test]
    fn rule_form_validation() {
        let ok = parse_rule_form(
            Lang::En,
            &pairs(&[
                ("name", " Skole "),
                ("kind", "school"),
                ("exempt_apps", "org.fossify.calendar"),
                ("exempt_apps", "org.fossify.calendar"),
                ("d0_start", "08:15"),
                ("d0_end", "14:00"),
            ]),
        )
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

        let refused = |list: &[(&str, &str)]| parse_rule_form(Lang::En, &pairs(list)).unwrap_err();
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

        // Design 66: a day rule ends after its start, on the same day; a night is Bedtime's.
        let night = "Sunday: the end must be after the start. For a night, use Bedtime.";
        for (start, end) in [("21:00", "07:00"), ("10:00", "10:00"), ("23:59", "00:01")] {
            assert_eq!(
                refused(&[
                    ("name", "x"),
                    ("kind", "custom"),
                    ("d6_start", start),
                    ("d6_end", end)
                ]),
                RuleError::Day(6, night.to_string()),
                "{start}-{end}"
            );
        }
        for (start, end) in [(0, LAST_MINUTE), (LAST_MINUTE, LAST_MINUTE)] {
            let ok = parse_rule_form(
                Lang::En,
                &pairs(&[
                    ("name", "x"),
                    ("kind", "custom"),
                    ("d5_start", &minutes_to_time_input(start)),
                    ("d5_end", &minutes_to_time_input(end)),
                ]),
            )
            .unwrap();
            assert_eq!(ok.days[5], Some(DayWindow { start, end }));
        }
        // An end of 00:00 is "until midnight", stored as 23:59 (qa-66-code L2).
        for (start, stored) in [("20:00", 1200), ("00:00", 0)] {
            let ok = parse_rule_form(
                Lang::En,
                &pairs(&[
                    ("name", "x"),
                    ("kind", "custom"),
                    ("d5_start", start),
                    ("d5_end", "00:00"),
                ]),
            )
            .unwrap();
            assert_eq!(
                ok.days[5],
                Some(DayWindow {
                    start: stored,
                    end: LAST_MINUTE
                })
            );
        }
    }

    #[test]
    fn budget_form_validation() {
        assert_eq!(
            parse_budget_form(Lang::En, &pairs(&[("b0", "60"), ("b6", "0")])).unwrap(),
            "[60,null,null,null,null,null,0]"
        );
        assert_eq!(
            parse_budget_form(Lang::En, &pairs(&[("b0", "1441")]))
                .unwrap_err()
                .0,
            0
        );
        assert_eq!(
            parse_budget_form(Lang::En, &pairs(&[("b3", "lots")]))
                .unwrap_err()
                .0,
            3
        );
    }

    #[test]
    fn every_rules_notice_reads_as_words() {
        for code in ["deleted", "gone", "gone_unsaved"] {
            assert!(rules_notice(Lang::En, Some(code)).is_some(), "{code}");
        }
        assert_eq!(rules_notice(Lang::En, Some("<b>")), None);
        assert_eq!(rules_notice(Lang::En, None), None);
    }
}
