//! Device › Calendar (design 69 §3, `crate::calendar`): the parent's side of Dagen, the kid's day view in Vibb One.
//! Every word through `crate::i18n` (the page's [Lang] from the request).
//!
//! - `GET /devices/{id}/calendar` (`device_calendar.html`): the groups top to bottom - Dagen (`#dagen`: on Home, the
//!   kid's own events), On the lock screen (`#lock`: the "Next" line, the reminders' master switch), Calendars shown
//!   (`#calendars`: the phone's last report, per calendar Shown and its reminder), Title rules (`#rules`).
//! - `POST .../calendar/dagen`, `.../calendar/lock`, `.../calendar/calendars` (one form, exactly the rows it was sent),
//!   `.../calendar/calendars/{key}/remove`: auto-saving forms, each back to its group (a save never jumps).
//! - The rule editor `GET .../calendar/rules/new` and `.../rules/{rule}`, saved by `POST .../calendar/rules` and
//!   `.../rules/{rule}` (303 to `/devices/{id}/calendar#rule-<id>`; a refusal renders the editor again, 400, as
//!   typed), `.../rules/{rule}/move` (up/down, back to `#rule-<id>`), `.../rules/{rule}/delete` (back to `#rules`).
//!   A rule gone since the page loaded is a 303 to `#rules` with `?notice=rule_gone`, marked
//!   `settings_log::refused`.
//! - `POST .../calendar/rules/preview`: the rule as typed on a pasted title (and description), among the phone's
//!   other rules - 200 with only the preview fragment for the page's script (`fragment=1`), else the editor with
//!   the preview filled. Never a save: nothing is written, nothing logged.
//!
//! Saves are logged by `settings_log` (a 303 back to a page of this app) and nudge the phone. An unknown phone is a
//! 404, a DB error a 500.

use std::collections::{BTreeMap, HashMap, HashSet};

use askama::Template;
use axum::body::Bytes;
use axum::extract::{Path, Query, State};
use axum::http::StatusCode;
use axum::response::{Html, IntoResponse, Redirect, Response};

use crate::AppState;
use crate::calendar::{
    self, Action, CalendarReport, Field, MAX_RULES, Match, Outcome, PATTERN_MAX, PolicyCalendar,
    REMIND_MINUTES, Rule, TEXT_MAX,
};
use crate::calendar_feeds;
use crate::handlers::schedules::form_pairs;
use crate::i18n::{Lang, Words, t, t_count, t_params};

fn field<'a>(fields: &'a [(String, String)], name: &str) -> &'a str {
    fields
        .iter()
        .find(|(k, _)| k == name)
        .map_or("", |(_, v)| v.as_str())
}

fn has(fields: &[(String, String)], name: &str) -> bool {
    fields.iter().any(|(k, _)| k == name)
}

fn read_failed(lang: Lang, err: sqlx::Error, what: &str) -> Response {
    tracing::error!(%err, "can't read {what}");
    (
        StatusCode::INTERNAL_SERVER_ERROR,
        t(lang, "calendar.error.read"),
    )
        .into_response()
}

/// A save (or a render) that failed: a 500 in the page's language, nothing changed.
fn failed(lang: Lang, err: impl std::fmt::Display, what: &str) -> Response {
    tracing::error!(%err, "{what}");
    (
        StatusCode::INTERNAL_SERVER_ERROR,
        t(lang, "calendar.error.save"),
    )
        .into_response()
}

/// A form this page never sends: a 400 with its reason.
fn bad(lang: Lang, key: &str) -> Response {
    crate::i18n::error(lang, StatusCode::BAD_REQUEST, key)
}

fn render(lang: Lang, template: impl Template, status: StatusCode) -> Response {
    match template.render() {
        Ok(html) => (status, Html(html)).into_response(),
        Err(err) => failed(lang, err, "failed to render a calendar page"),
    }
}

/// The phone's name, or the answer: 404 for an unknown phone, 500 on a DB error.
async fn phone(state: &AppState, lang: Lang, id: i64) -> Result<String, Box<Response>> {
    match sqlx::query_scalar(
        "SELECT d.name FROM devices d JOIN device_policy p ON p.device_id = d.id WHERE d.id = ?",
    )
    .bind(id)
    .fetch_optional(&state.db)
    .await
    {
        Ok(Some(name)) => Ok(name),
        Ok(None) => Err(Box::new(
            (StatusCode::NOT_FOUND, t(lang, "calendar.error.no_phone")).into_response(),
        )),
        Err(err) => Err(Box::new(read_failed(lang, err, "the device"))),
    }
}

fn saved(state: &AppState, id: i64, anchor: &str) -> Response {
    let _ = state.command_notify.send(id);
    Redirect::to(&format!("/devices/{id}/calendar#{anchor}")).into_response()
}

// ---- the page ----------------------------------------------------------------------------------

/// One reminder choice of a calendar's select.
pub struct RemindChoice {
    pub value: &'static str,
    pub label: String,
    pub selected: bool,
}

/// One calendar on the page.
pub struct CalendarView {
    pub key: String,
    pub name: String,
    /// "ICSx5 · skole.kommune.no".
    pub sub: String,
    pub shown: bool,
    pub reminds: Vec<RemindChoice>,
    /// In the phone's last report; else saved here and "Not on the phone now", with Remove.
    pub on_phone: bool,
}

/// One calendar link on the page (design 72 §5): its name, its host (the link itself is sealed, never shown again)
/// and its status lines, the first the fetch's.
pub struct LinkView {
    pub uid: String,
    pub name: String,
    pub host: String,
    pub lines: Vec<String>,
    /// The first line says something failed (or a held empty answer).
    pub failing: bool,
}

/// The add form as typed (a refusal renders it again).
#[derive(Default)]
pub struct AddForm {
    pub url: String,
    pub name: String,
    pub error: Option<String>,
}

/// One title rule in the list.
pub struct RuleView {
    pub id: i64,
    /// Its place in the list (1 first), which the preview's "Rule {n} matches first" names.
    pub n: usize,
    pub text: String,
    pub first: bool,
    pub last: bool,
}

/// What the Calendars group says instead of a list.
#[derive(Clone, Copy, PartialEq, Eq)]
pub enum ReportState {
    NoReport,
    Unreadable,
    NoCalendars,
    Listed,
}

/// The kid's own events (step 3): one choice of the select.
pub struct KidChoice {
    pub value: String,
    pub label: String,
    pub selected: bool,
}

#[derive(Template)]
#[template(path = "device_calendar.html")]
struct CalendarTemplate {
    lang: Lang,
    title: String,
    device_id: i64,
    device_name: String,
    capable: bool,
    on_home: bool,
    kid_choices: Vec<KidChoice>,
    glance_next: bool,
    reminders_row: bool,
    state: ReportState,
    calendars: Vec<CalendarView>,
    rules: Vec<RuleView>,
    rules_skipped: Vec<i64>,
    can_add_rule: bool,
    notice: Option<&'static str>,
    links: Vec<LinkView>,
    can_add_link: bool,
    /// No music key: nothing can be sealed, the form says why and has no submit (QA L11).
    links_off: bool,
    add: AddForm,
    links_notice: Option<&'static str>,
}
crate::i18n::words!(CalendarTemplate);

impl CalendarTemplate {
    fn skipped_line(&self) -> String {
        let ids: Vec<String> = self
            .rules_skipped
            .iter()
            .map(|id| format!("#{id}"))
            .collect();
        t_params(
            self.lang,
            "calendar.rules.skipped",
            &[("ids", &ids.join(", "))],
        )
    }
}

/// "ICSx5 · skole.kommune.no", "Phone".
fn calendar_sub(lang: Lang, account_type: &str, source: Option<&str>) -> String {
    let app = match account_type {
        "LOCAL" => t(lang, "calendar.app.phone"),
        other => calendar::app_of(other),
    };
    match source {
        Some(host) => format!("{app} · {host}"),
        None => app.to_string(),
    }
}

fn remind_value(saved: Option<&PolicyCalendar>) -> String {
    match saved {
        Some(c) if c.remind == "own" => "own".to_string(),
        Some(c) if c.remind == "before" => c
            .remind_minutes
            .filter(|m| REMIND_MINUTES.contains(m))
            .map_or("off".to_string(), |m| m.to_string()),
        _ => "off".to_string(),
    }
}

fn remind_choices(lang: Lang, current: &str) -> Vec<RemindChoice> {
    let mut out = vec![
        RemindChoice {
            value: "off",
            label: t(lang, "calendar.remind.off").to_string(),
            selected: current == "off",
        },
        RemindChoice {
            value: "own",
            label: t(lang, "calendar.remind.own").to_string(),
            selected: current == "own",
        },
    ];
    for (minutes, value) in REMIND_MINUTES.iter().zip(["5", "10", "15", "30", "60"]) {
        out.push(RemindChoice {
            value,
            label: t_params(
                lang,
                "calendar.remind.before",
                &[("minutes", &minutes.to_string())],
            ),
            selected: current == value,
        });
    }
    out
}

/// A rule in words: "Starts with «Gruppe 2-» · Skolen · show as «Skole:» + the rest".
pub fn rule_text(lang: Lang, rule: &Rule, names: &HashMap<&str, &str>) -> String {
    let how = match (rule.field, rule.match_kind) {
        (Field::Title, Match::Starts) => "calendar.rule.starts",
        (Field::Title, Match::Contains) => "calendar.rule.contains",
        (Field::Description, Match::Starts) => "calendar.rule.desc_starts",
        (Field::Description, Match::Contains) => "calendar.rule.desc_contains",
    };
    let scope = match rule.calendar.as_deref() {
        None => t(lang, "calendar.rule.any").to_string(),
        Some(key) => names.get(key).map_or_else(
            || t(lang, "calendar.rule.calendar_gone").to_string(),
            |n| n.to_string(),
        ),
    };
    let then = match rule.action {
        Action::Hide => t(lang, "calendar.rule.hidden").to_string(),
        Action::Replace => t_params(lang, "calendar.rule.show_as", &[("text", &rule.text)]),
        Action::Prefix if rule.text.is_empty() => t(lang, "calendar.rule.strip").to_string(),
        Action::Prefix => t_params(lang, "calendar.rule.show_as_rest", &[("text", &rule.text)]),
    };
    format!(
        "{} · {scope} · {then}",
        t_params(lang, how, &[("text", &rule.pattern)])
    )
}

/// The kid's own events (§4.5, PLAN's L7 decision): off, the phone's own "Min kalender" (the default), or one
/// calendar the phone reports writable (never ICSx5's, never a phone-local one - the phone's own is the second choice).
fn kid_choices(
    lang: Lang,
    kid_adds: bool,
    report: Option<&CalendarReport>,
    saved: &[PolicyCalendar],
) -> Vec<KidChoice> {
    let chosen = saved
        .iter()
        .find(|c| c.writable_for_kid)
        .map(|c| c.key.as_str());
    let mut out = vec![
        KidChoice {
            value: "off".to_string(),
            label: t(lang, "calendar.kid.off").to_string(),
            selected: !kid_adds,
        },
        KidChoice {
            value: "phone".to_string(),
            label: t(lang, "calendar.kid.phone").to_string(),
            selected: kid_adds && chosen.is_none(),
        },
    ];
    for c in report.map(|r| r.calendars.as_slice()).unwrap_or_default() {
        if writable_for_kid(c) {
            out.push(KidChoice {
                value: c.key.clone(),
                label: c.name.clone(),
                selected: kid_adds && chosen == Some(c.key.as_str()),
            });
        }
    }
    // QA M1: a pick the last report doesn't list (a re-login, a sync turned off, an unreadable provider) stays the
    // selected choice, so the form's next autosave keeps it.
    if let Some(key) = chosen.filter(|key| !out.iter().any(|c| c.value == *key)) {
        out.push(KidChoice {
            value: key.to_string(),
            label: t(lang, "calendar.kid.gone").to_string(),
            selected: kid_adds,
        });
    }
    out
}

/// A reported calendar the kid's events may go to: contributor or more (`CAL_ACCESS_CONTRIBUTOR` 500), not a
/// subscribed feed, not a phone-local one.
fn writable_for_kid(c: &calendar::ReportedCalendar) -> bool {
    c.access_level >= 500 && c.account_type != "at.bitfire.icsdroid" && c.account_type != "LOCAL"
}

/// GET `/devices/{id}/calendar`.
pub async fn view_calendar(
    State(state): State<AppState>,
    lang: Lang,
    Path(id): Path<i64>,
    Query(query): Query<HashMap<String, String>>,
) -> Response {
    let notice = query.get("notice").map(String::as_str);
    render_page(&state, lang, id, notice, AddForm::default(), StatusCode::OK).await
}

/// The page, with a notice from the query and the add form as typed.
async fn render_page(
    state: &AppState,
    lang: Lang,
    id: i64,
    notice: Option<&str>,
    add: AddForm,
    status: StatusCode,
) -> Response {
    let device_name = match phone(state, lang, id).await {
        Ok(name) => name,
        Err(answer) => return *answer,
    };
    let loaded = async {
        Ok::<_, sqlx::Error>((
            calendar::switches(&state.db, id).await?,
            calendar::calendars(&state.db, id).await?,
            calendar::rules(&state.db, id).await?,
            calendar::last_report(&state.db, id).await?,
            crate::handlers::device_api::last_capabilities(&state.db, id).await?,
            calendar_feeds::links(&state.db, id).await?,
        ))
    }
    .await;
    let (switches, saved, rules, report, capabilities, links) = match loaded {
        Ok(found) => found,
        Err(err) => return read_failed(lang, err, "the calendar settings"),
    };
    let zone = crate::display_time::Zone::load(&state.db).await;
    let now = chrono::Utc::now();
    let feeds_capable = capabilities.iter().any(|c| c == calendar_feeds::CAPABILITY);
    let link_keys: HashMap<String, &calendar_feeds::LinkRow> = links
        .iter()
        .map(|l| (calendar_feeds::calendar_key(&l.uid), l))
        .collect();
    let (on_home, kid_adds, glance_next, lock_reminders) =
        switches.unwrap_or_else(calendar::default_switches);
    let saved_by_key: BTreeMap<&str, &PolicyCalendar> =
        saved.iter().map(|c| (c.key.as_str(), c)).collect();
    let mut calendars: Vec<CalendarView> = Vec::new();
    let mut names: HashMap<&str, &str> = HashMap::new();
    if let Some(report) = &report {
        for c in &report.calendars {
            names.insert(c.key.as_str(), c.name.as_str());
            let saved = saved_by_key.get(c.key.as_str()).copied();
            let mut sub = calendar_sub(lang, &c.account_type, c.source.as_deref());
            if link_keys.contains_key(&c.key) {
                sub = t(lang, "calendar.links.kind").to_string();
            } else if let Some(link) = c
                .source
                .as_deref()
                .and_then(|host| links.iter().find(|l| l.host == host))
            {
                // Dagen shows an identical copy once (`DayModel`); the parent may want only one of them.
                sub = format!(
                    "{sub} · {}",
                    t_params(lang, "calendar.links.maybe_same", &[("name", &link.name)])
                );
            }
            calendars.push(CalendarView {
                key: c.key.clone(),
                name: c.name.clone(),
                sub,
                shown: saved.is_none_or(|s| s.shown),
                reminds: remind_choices(lang, &remind_value(saved)),
                on_phone: true,
            });
        }
    }
    // A link's calendar at once, by its key: its controls work before the phone has it (design 72 §5).
    for link in &links {
        let key = calendar_feeds::calendar_key(&link.uid);
        if names.contains_key(key.as_str()) {
            continue;
        }
        let saved = saved_by_key.get(key.as_str()).copied();
        calendars.push(CalendarView {
            name: link.name.clone(),
            sub: format!(
                "{} · {}",
                t(lang, "calendar.links.kind"),
                t(lang, "calendar.links.waiting")
            ),
            shown: saved.is_none_or(|s| s.shown),
            reminds: remind_choices(lang, &remind_value(saved)),
            on_phone: true,
            key,
        });
    }
    for s in &saved {
        if !names.contains_key(s.key.as_str()) && !link_keys.contains_key(&s.key) {
            calendars.push(CalendarView {
                key: s.key.clone(),
                name: t(lang, "calendar.calendars.gone_name").to_string(),
                sub: t(lang, "calendar.calendars.gone").to_string(),
                shown: s.shown,
                reminds: remind_choices(lang, &remind_value(Some(s))),
                on_phone: false,
            });
        }
    }
    let state_of = match &report {
        None => ReportState::NoReport,
        Some(r) if !r.readable => ReportState::Unreadable,
        Some(r) if r.calendars.is_empty() => ReportState::NoCalendars,
        Some(_) => ReportState::Listed,
    };
    let count = rules.len();
    let rule_views = rules
        .iter()
        .enumerate()
        .map(|(i, r)| RuleView {
            id: r.id,
            n: i + 1,
            text: rule_text(lang, r, &names),
            first: i == 0,
            last: i + 1 == count,
        })
        .collect();
    let ids: HashSet<i64> = rules.iter().map(|r| r.id).collect();
    let reported_keys: HashSet<&str> = report
        .iter()
        .flat_map(|r| &r.calendars)
        .map(|c| c.key.as_str())
        .collect();
    let link_views = links
        .iter()
        .map(|l| {
            let on_phone = reported_keys.contains(calendar_feeds::calendar_key(&l.uid).as_str());
            let applied = report.iter().flat_map(|r| &r.feeds).find(|f| f.id == l.uid);
            link_view(lang, &zone, now, l, feeds_capable, on_phone, applied)
        })
        .collect();
    let can_add_link = links.len() < calendar_feeds::MAX_LINKS_PER_PHONE as usize;
    render(
        lang,
        CalendarTemplate {
            lang,
            title: t(lang, "calendar.title").to_string(),
            device_id: id,
            device_name,
            capable: capabilities
                .iter()
                .any(|c| c == calendar::CALENDAR_CAPABILITY),
            on_home,
            kid_choices: kid_choices(lang, kid_adds, report.as_ref(), &saved),
            glance_next,
            reminders_row: lock_reminders == "row",
            state: state_of,
            calendars,
            rules: rule_views,
            rules_skipped: report
                .as_ref()
                .map(|r| {
                    r.rules_skipped
                        .iter()
                        .copied()
                        .filter(|i| ids.contains(i))
                        .collect()
                })
                .unwrap_or_default(),
            can_add_rule: count < MAX_RULES,
            notice: match notice {
                Some("rule_gone") => Some("calendar.rules.gone"),
                _ => None,
            },
            links: link_views,
            can_add_link,
            links_off: state.music_key.is_none(),
            add,
            links_notice: match notice {
                Some("fetch_wait") => Some("calendar.links.fetch_wait"),
                _ => None,
            },
        },
        status,
    )
}

// ---- calendar links (design 72 §5) -------------------------------------------------------------

/// A stored stamp as the parent reads it: the clock today, else the date and the clock (`calendar.links.when_date`:
/// "27.10. 14:05", "10/27 14:05").
fn when_text(
    lang: Lang,
    zone: &crate::display_time::Zone,
    stored: Option<&str>,
    now: chrono::DateTime<chrono::Utc>,
) -> String {
    use chrono::Datelike;
    let Some(at) = stored.and_then(crate::music_sweep::parse_stamp) else {
        return "-".to_string();
    };
    if zone.is_today(at, now) {
        return zone.clock(at);
    }
    let local = zone.local(at);
    t_params(
        lang,
        "calendar.links.when_date",
        &[
            ("day", &local.day().to_string()),
            ("month", &local.month().to_string()),
            ("time", &zone.clock(at)),
        ],
    )
}

/// A fetch error's code (`calendar_feeds::FeedError`) in words, never the link or the body.
pub fn why_text(lang: Lang, code: &str, status: Option<i64>) -> String {
    let key = match code {
        "not_found" => "calendar.links.why.not_found",
        "status" => "calendar.links.why.status",
        "timeout" => "calendar.links.why.timeout",
        "too_big" => "calendar.links.why.too_big",
        "not_calendar" => "calendar.links.why.not_calendar",
        "private_address" => "calendar.links.why.private_address",
        "key_lost" => "calendar.links.why.key_lost",
        "insecure" => "calendar.links.why.insecure",
        "unreadable" => "calendar.links.why.unreadable",
        _ => "calendar.links.why.network",
    };
    t_params(lang, key, &[("status", &status.unwrap_or(0).to_string())])
}

/// A link's status lines (§5): the fetch (or its error with the set's age), then the phone, the counts, the warning.
fn link_view(
    lang: Lang,
    zone: &crate::display_time::Zone,
    now: chrono::DateTime<chrono::Utc>,
    link: &calendar_feeds::LinkRow,
    capable: bool,
    on_phone: bool,
    applied: Option<&calendar::ReportedFeed>,
) -> LinkView {
    let when = when_text(lang, zone, link.ok_at.as_deref(), now);
    let (first, failing) = match link.error.as_deref() {
        Some("empty") => (
            t_params(lang, "calendar.links.status.held_empty", &[("when", &when)]),
            true,
        ),
        Some(code) => (
            t_params(
                lang,
                "calendar.links.status.failing",
                &[
                    ("why", &why_text(lang, code, link.error_status)),
                    ("when", &when),
                ],
            ),
            true,
        ),
        None => (
            t_count(
                lang,
                "calendar.links.status.fetched",
                link.events,
                &[("when", &when)],
            ),
            false,
        ),
    };
    let mut lines = vec![first];
    if !capable {
        lines.push(t(lang, "calendar.links.update_phone").to_string());
    } else if !on_phone {
        lines.push(t(lang, "calendar.links.waiting").to_string());
    }
    let count = |key: &str, n: i64| t_count(lang, key, n, &[]);
    if let Some(refused) = applied.map(|a| a.refused).filter(|n| *n > 0) {
        lines.push(count("calendar.links.status.refused", refused));
    }
    if link.unknown_tz > 0 {
        lines.push(count("calendar.links.status.unknown_tz", link.unknown_tz));
    }
    if link.cut > 0 {
        lines.push(count("calendar.links.status.cut", link.cut));
    }
    if link.churn >= calendar_feeds::CHURN_FETCHES {
        lines.push(t(lang, "calendar.links.status.churn").to_string());
    }
    if link.lan_http {
        lines.push(t(lang, "calendar.links.status.lan_http").to_string());
    }
    LinkView {
        uid: link.uid.clone(),
        name: link.name.clone(),
        host: link.host.clone(),
        lines,
        failing,
    }
}

/// The link `uid` of this phone: its source id, `None` when the phone hasn't it (removed meanwhile).
async fn link_of(state: &AppState, id: i64, uid: &str) -> Result<Option<i64>, sqlx::Error> {
    sqlx::query_scalar(
        "SELECT s.id FROM calendar_sources s JOIN device_calendar_feeds d ON d.source_id = s.id \
         WHERE s.uid = ? AND d.device_id = ?",
    )
    .bind(uid)
    .bind(id)
    .fetch_optional(&state.db)
    .await
}

fn back_to_links(id: i64) -> Response {
    Redirect::to(&format!("/devices/{id}/calendar#links")).into_response()
}

/// POST `/devices/{id}/calendar/links`: `url` and an optional `name` (design 72 §5). Added (or attached to the
/// source the link already is) → 303 to `#link-<uid>`; refused → the page again as typed (400, the reason in the
/// group, never the body). The phone is nudged.
pub async fn add_link(
    State(state): State<AppState>,
    lang: Lang,
    Path(id): Path<i64>,
    body: Bytes,
) -> Response {
    let fields = form_pairs(&body);
    if let Err(answer) = phone(&state, lang, id).await {
        return *answer;
    }
    let (url, name) = (field(&fields, "url"), field(&fields, "name"));
    let error = match calendar_feeds::add(&state, id, url, name, chrono::Utc::now()).await {
        Ok(uid) => return saved(&state, id, &format!("link-{uid}")),
        Err(calendar_feeds::AddError::Db(err)) => {
            return failed(lang, err, "couldn't add a calendar link");
        }
        Err(calendar_feeds::AddError::Refused(key)) => t(lang, key).to_string(),
        Err(calendar_feeds::AddError::Fetch(err)) => t_params(
            lang,
            "calendar.links.error.fetch",
            &[("why", &why_text(lang, err.code, err.status.map(i64::from)))],
        ),
    };
    let add = AddForm {
        url: crate::audit::clip(url, calendar_feeds::MAX_URL_CHARS).to_string(),
        name: crate::audit::clip(name, 200).to_string(),
        error: Some(error),
    };
    render_page(&state, lang, id, None, add, StatusCode::BAD_REQUEST).await
}

/// POST `/devices/{id}/calendar/links/{uid}/name`: `name` (1-60 after cleaning; empty → the feed's, else the host),
/// back to `#link-<uid>`; the phone renames its calendar from the policy alone.
pub async fn rename_link(
    State(state): State<AppState>,
    lang: Lang,
    Path((id, uid)): Path<(i64, String)>,
    body: Bytes,
) -> Response {
    let fields = form_pairs(&body);
    if let Err(answer) = phone(&state, lang, id).await {
        return *answer;
    }
    let found: Result<Option<(i64, Option<String>, String)>, sqlx::Error> = sqlx::query_as(
        "SELECT s.id, s.feed_name, s.host FROM calendar_sources s JOIN device_calendar_feeds d \
         ON d.source_id = s.id WHERE s.uid = ? AND d.device_id = ?",
    )
    .bind(&uid)
    .bind(id)
    .fetch_optional(&state.db)
    .await;
    let (source_id, feed_name, host) = match found {
        Ok(Some(found)) => found,
        Ok(None) => return back_to_links(id),
        Err(err) => return read_failed(lang, err, "a calendar link"),
    };
    let name = calendar_feeds::name_of(field(&fields, "name"), feed_name.as_deref(), &host);
    match sqlx::query(
        "UPDATE device_calendar_feeds SET name = ? WHERE device_id = ? AND source_id = ?",
    )
    .bind(&name)
    .bind(id)
    .bind(source_id)
    .execute(&state.db)
    .await
    {
        Ok(_) => saved(&state, id, &format!("link-{uid}")),
        Err(err) => failed(lang, err, "couldn't rename a calendar link"),
    }
}

/// POST `/devices/{id}/calendar/links/{uid}/fetch`: "Fetch now", at most once per source per 5 minutes (else back
/// with `?notice=fetch_wait`); the fetch runs before the answer (at most 60 s), back to `#link-<uid>`. Not a save.
pub async fn fetch_link(
    State(state): State<AppState>,
    lang: Lang,
    Path((id, uid)): Path<(i64, String)>,
) -> Response {
    if let Err(answer) = phone(&state, lang, id).await {
        return *answer;
    }
    let source_id = match link_of(&state, id, &uid).await {
        Ok(Some(source)) => source,
        Ok(None) => return back_to_links(id),
        Err(err) => return read_failed(lang, err, "a calendar link"),
    };
    let now = chrono::Utc::now();
    // One claim: two taps at once fetch once.
    let claimed = sqlx::query(
        "UPDATE calendar_sources SET fetch_now_at = ? WHERE id = ? \
         AND (fetch_now_at IS NULL OR fetch_now_at <= ?)",
    )
    .bind(crate::music_sweep::stamp(now))
    .bind(source_id)
    .bind(crate::music_sweep::stamp(
        now - calendar_feeds::FETCH_NOW_GAP,
    ))
    .execute(&state.db)
    .await;
    match claimed {
        Ok(done) if done.rows_affected() == 0 => {
            return Redirect::to(&format!(
                "/devices/{id}/calendar?notice=fetch_wait#link-{uid}"
            ))
            .into_response();
        }
        Ok(_) => {}
        Err(err) => return failed(lang, err, "couldn't start a calendar link's fetch"),
    }
    if let Err(err) = calendar_feeds::refresh(&state, source_id, now).await {
        return failed(lang, err, "couldn't store a calendar link's fetch");
    }
    Redirect::to(&format!("/devices/{id}/calendar#link-{uid}")).into_response()
}

/// POST `/devices/{id}/calendar/links/{uid}/remove`: the link goes off this phone (its calendar with it, at the
/// phone's next sync); a source no phone names goes at once (the migration's trigger). The calendar's settings and
/// rules stay, by its key, and come back with the same link. Back to `#links`.
pub async fn remove_link(
    State(state): State<AppState>,
    lang: Lang,
    Path((id, uid)): Path<(i64, String)>,
) -> Response {
    if let Err(answer) = phone(&state, lang, id).await {
        return *answer;
    }
    match sqlx::query(
        "DELETE FROM device_calendar_feeds WHERE device_id = ? \
         AND source_id = (SELECT id FROM calendar_sources WHERE uid = ?)",
    )
    .bind(id)
    .bind(&uid)
    .execute(&state.db)
    .await
    {
        Ok(_) => saved(&state, id, "links"),
        Err(err) => failed(lang, err, "couldn't remove a calendar link"),
    }
}

/// `GET /api/devices/calendar/feeds/{uid}` (design 72 §3, bearer): the link's set for a phone that has it - else
/// 404, as an unknown one -, `ETag` = its rev, 304, gzip when asked for.
pub async fn device_feed(
    State(state): State<AppState>,
    Path(uid): Path<String>,
    axum::Extension(crate::security::AuthedDevice(device)): axum::Extension<
        crate::security::AuthedDevice,
    >,
    headers: axum::http::HeaderMap,
) -> Response {
    if !calendar_feeds::valid_uid(&uid) {
        return StatusCode::NOT_FOUND.into_response();
    }
    match calendar_feeds::device_feed(&state.db, device.id, &uid).await {
        Ok(Some((rev, json))) => crate::handlers::music_api::versioned_json(
            device.id,
            &headers,
            &rev,
            json.as_bytes(),
            "calendar link's set",
        ),
        Ok(None) => StatusCode::NOT_FOUND.into_response(),
        Err(err) => {
            tracing::error!(device_id = device.id, %err, "couldn't read a calendar link's set");
            StatusCode::INTERNAL_SERVER_ERROR.into_response()
        }
    }
}

/// The summary row's value (the phone's page, "Calendar · 3 shown").
pub async fn summary_value(state: &AppState, lang: Lang, id: i64) -> String {
    let loaded = async {
        Ok::<_, sqlx::Error>((
            calendar::last_report(&state.db, id).await?,
            calendar::calendars(&state.db, id).await?,
        ))
    }
    .await;
    match loaded {
        Err(err) => {
            tracing::error!(device_id = id, %err, "couldn't read the calendar summary");
            t(lang, "calendar.summary.unreadable").to_string()
        }
        Ok((None, _)) => t(lang, "calendar.summary.no_report").to_string(),
        Ok((Some(r), _)) if !r.readable => t(lang, "calendar.summary.cant_read").to_string(),
        Ok((Some(r), _)) if r.calendars.is_empty() => t(lang, "calendar.summary.none").to_string(),
        Ok((Some(r), saved)) => {
            let hidden: HashSet<&str> = saved
                .iter()
                .filter(|c| !c.shown)
                .map(|c| c.key.as_str())
                .collect();
            let shown = r
                .calendars
                .iter()
                .filter(|c| !hidden.contains(c.key.as_str()))
                .count();
            t_params(
                lang,
                "calendar.summary.shown",
                &[("count", &shown.to_string())],
            )
        }
    }
}

// ---- the switches ------------------------------------------------------------------------------

/// POST `/devices/{id}/calendar/dagen`: `on_home` (missing = off) and `kid` (`off`, `phone` or a calendar key the
/// phone reports writable), back to `#dagen`. A `kid` it can't use is a 400, nothing written.
pub async fn save_dagen(
    State(state): State<AppState>,
    lang: Lang,
    Path(id): Path<i64>,
    body: Bytes,
) -> Response {
    let fields = form_pairs(&body);
    if let Err(answer) = phone(&state, lang, id).await {
        return *answer;
    }
    let on_home = has(&fields, "on_home");
    let kid = field(&fields, "kid");
    let pick: Option<Option<String>> = match kid {
        "" => None,
        "off" | "phone" => Some(None),
        key if calendar::valid_key(key) => {
            let report = match calendar::last_report(&state.db, id).await {
                Ok(report) => report,
                Err(err) => return read_failed(lang, err, "the calendar report"),
            };
            // The saved pick stays allowed while the report lacks it (QA M1).
            let saved_pick = match calendar::calendars(&state.db, id).await {
                Ok(saved) => saved.iter().any(|c| c.key == key && c.writable_for_kid),
                Err(err) => return read_failed(lang, err, "the saved calendars"),
            };
            if !saved_pick
                && !report
                    .iter()
                    .flat_map(|r| &r.calendars)
                    .any(|c| c.key == key && writable_for_kid(c))
            {
                return bad(lang, "calendar.error.kid_calendar");
            }
            Some(Some(key.to_string()))
        }
        _ => return bad(lang, "calendar.error.kid_calendar"),
    };
    let result = async {
        let mut tx = state.db.begin().await?;
        sqlx::query("UPDATE device_policy SET calendar_on_home = ? WHERE device_id = ?")
            .bind(on_home)
            .bind(id)
            .execute(&mut *tx)
            .await?;
        if let Some(pick) = &pick {
            sqlx::query("UPDATE device_policy SET calendar_kid_adds = ? WHERE device_id = ?")
                .bind(kid != "off")
                .bind(id)
                .execute(&mut *tx)
                .await?;
            sqlx::query("UPDATE device_calendars SET writable_for_kid = 0 WHERE device_id = ?")
                .bind(id)
                .execute(&mut *tx)
                .await?;
            if let Some(key) = pick {
                sqlx::query(
                    "INSERT INTO device_calendars (device_id, calendar_key, writable_for_kid) VALUES (?, ?, 1) \
                     ON CONFLICT(device_id, calendar_key) DO UPDATE SET writable_for_kid = 1",
                )
                .bind(id)
                .bind(key)
                .execute(&mut *tx)
                .await?;
            }
            prune_default_rows(&mut tx, id).await?;
        }
        tx.commit().await
    }
    .await;
    match result {
        Ok(()) => saved(&state, id, "dagen"),
        Err(err) => failed(lang, err, "couldn't save Dagen's switches"),
    }
}

/// A saved calendar that says only the defaults (shown, no reminder, not the kid's) is no row.
async fn prune_default_rows(
    tx: &mut sqlx::Transaction<'_, sqlx::Sqlite>,
    id: i64,
) -> Result<(), sqlx::Error> {
    sqlx::query(
        "DELETE FROM device_calendars WHERE device_id = ? AND shown = 1 AND remind = 'off' \
         AND writable_for_kid = 0",
    )
    .bind(id)
    .execute(&mut **tx)
    .await
    .map(|_| ())
}

/// POST `/devices/{id}/calendar/lock`: `glance_next` (missing = off) and `reminders` (`off` or `row`, the select
/// "Reminders on the lock screen": a select, not a switch, since a third form - a quiet row with a ping - is to come),
/// back to `#lock`. Any other `reminders` is a 400.
pub async fn save_lock(
    State(state): State<AppState>,
    lang: Lang,
    Path(id): Path<i64>,
    body: Bytes,
) -> Response {
    let fields = form_pairs(&body);
    if let Err(answer) = phone(&state, lang, id).await {
        return *answer;
    }
    let reminders = match field(&fields, "reminders") {
        "" | "off" => "off",
        "row" => "row",
        _ => return bad(lang, "calendar.error.reminders"),
    };
    match sqlx::query(
        "UPDATE device_policy SET calendar_glance_next = ?, calendar_lock_reminders = ? WHERE device_id = ?",
    )
    .bind(has(&fields, "glance_next"))
    .bind(reminders)
    .bind(id)
    .execute(&state.db)
    .await
    {
        Ok(_) => saved(&state, id, "lock"),
        Err(err) => failed(lang, err, "couldn't save the lock screen's calendar switches"),
    }
}

/// POST `/devices/{id}/calendar/calendars`: one form, exactly the rows it was sent (design 31 §5.6) - each `key`
/// (16 hex, at most 100) with `shown_<key>` (missing = hidden) and `remind_<key>` (`off`, `own`, 5/10/15/30/60;
/// missing = unchanged). The kid's choice is kept. Back to `#calendars`.
pub async fn save_calendars(
    State(state): State<AppState>,
    lang: Lang,
    Path(id): Path<i64>,
    body: Bytes,
) -> Response {
    let fields = form_pairs(&body);
    if let Err(answer) = phone(&state, lang, id).await {
        return *answer;
    }
    let keys: Vec<&str> = fields
        .iter()
        .filter(|(k, v)| k == "key" && calendar::valid_key(v))
        .map(|(_, v)| v.as_str())
        .take(100)
        .collect();
    let result = async {
        let mut tx = state.db.begin().await?;
        for key in keys {
            let remind = field(&fields, &format!("remind_{key}"));
            let (remind, minutes): (Option<&str>, Option<i64>) = match remind {
                "" => (None, None),
                "off" => (Some("off"), None),
                "own" => (Some("own"), None),
                m => match m.parse::<i64>() {
                    Ok(m) if REMIND_MINUTES.contains(&m) => (Some("before"), Some(m)),
                    _ => (Some("off"), None),
                },
            };
            sqlx::query(
                "INSERT INTO device_calendars (device_id, calendar_key, shown, remind, remind_minutes) \
                 VALUES (?1, ?2, ?3, COALESCE(?4, 'off'), ?5) ON CONFLICT(device_id, calendar_key) DO UPDATE SET \
                 shown = excluded.shown, \
                 remind = CASE WHEN ?4 IS NULL THEN remind ELSE excluded.remind END, \
                 remind_minutes = CASE WHEN ?4 IS NULL THEN remind_minutes ELSE excluded.remind_minutes END",
            )
            .bind(id)
            .bind(key)
            .bind(has(&fields, &format!("shown_{key}")))
            .bind(remind)
            .bind(minutes)
            .execute(&mut *tx)
            .await?;
        }
        prune_default_rows(&mut tx, id).await?;
        tx.commit().await
    }
    .await;
    match result {
        Ok(()) => saved(&state, id, "calendars"),
        Err(err) => failed(lang, err, "couldn't save the calendars"),
    }
}

/// POST `/devices/{id}/calendar/calendars/{key}/remove`: a saved calendar the phone no longer reports goes (rules
/// naming it stay and say so). Back to `#calendars`.
pub async fn remove_calendar(
    State(state): State<AppState>,
    lang: Lang,
    Path((id, key)): Path<(i64, String)>,
) -> Response {
    if let Err(answer) = phone(&state, lang, id).await {
        return *answer;
    }
    match sqlx::query("DELETE FROM device_calendars WHERE device_id = ? AND calendar_key = ?")
        .bind(id)
        .bind(&key)
        .execute(&state.db)
        .await
    {
        Ok(_) => saved(&state, id, "calendars"),
        Err(err) => failed(lang, err, "couldn't remove a calendar"),
    }
}

// ---- the rule editor ---------------------------------------------------------------------------

/// The editor's fields, as stored or as typed.
pub struct RuleForm {
    pub field: String,
    pub match_kind: String,
    pub pattern: String,
    /// `show` or `hide`.
    pub then: String,
    pub text: String,
    pub keep_rest: bool,
    pub calendar: String,
    pub preview_title: String,
    pub preview_description: String,
}

impl RuleForm {
    fn of(rule: &Rule) -> RuleForm {
        RuleForm {
            field: rule.field.as_str().to_string(),
            match_kind: rule.match_kind.as_str().to_string(),
            pattern: rule.pattern.clone(),
            then: if rule.action == Action::Hide {
                "hide"
            } else {
                "show"
            }
            .to_string(),
            text: rule.text.clone(),
            keep_rest: rule.action == Action::Prefix,
            calendar: rule.calendar.clone().unwrap_or_default(),
            preview_title: String::new(),
            preview_description: String::new(),
        }
    }

    fn new_rule() -> RuleForm {
        RuleForm {
            field: "title".to_string(),
            match_kind: "starts".to_string(),
            pattern: String::new(),
            then: "show".to_string(),
            text: String::new(),
            keep_rest: true,
            calendar: String::new(),
            preview_title: String::new(),
            preview_description: String::new(),
        }
    }

    /// As typed, each text cut so a page can't grow without bound.
    fn typed(fields: &[(String, String)]) -> RuleForm {
        let cut =
            |name: &str, max: usize| field(fields, name).chars().take(max).collect::<String>();
        RuleForm {
            field: cut("field", 20),
            match_kind: cut("match", 20),
            pattern: cut("pattern", 400),
            then: cut("then", 20),
            text: cut("text", 200),
            keep_rest: has(fields, "keep_rest"),
            calendar: cut("calendar", 16),
            preview_title: cut("preview_title", 1000),
            preview_description: cut("preview_description", 4000),
        }
    }
}

/// The rule as typed, checked (§3.3): an error key when refused.
fn parse_rule(form: &RuleForm, calendars: &HashSet<String>) -> Result<Rule, &'static str> {
    let field = Field::parse(&form.field).ok_or("calendar.rule.error.field")?;
    let match_kind = Match::parse(&form.match_kind).ok_or("calendar.rule.error.match")?;
    let pattern = calendar::clean(&form.pattern, usize::MAX);
    if pattern.is_empty() {
        return Err("calendar.rule.error.pattern_empty");
    }
    if pattern.chars().count() > PATTERN_MAX {
        return Err("calendar.rule.error.pattern_long");
    }
    let text = calendar::clean(&form.text, usize::MAX);
    let action = match form.then.as_str() {
        "hide" => Action::Hide,
        "show" if field == Field::Description => {
            return Err("calendar.rule.error.description_hides");
        }
        "show" if form.keep_rest => Action::Prefix,
        "show" if text.is_empty() => return Err("calendar.rule.error.text_empty"),
        "show" => Action::Replace,
        _ => return Err("calendar.rule.error.then"),
    };
    if action != Action::Hide && text.chars().count() > TEXT_MAX {
        return Err("calendar.rule.error.text_long");
    }
    let calendar = match form.calendar.as_str() {
        "" => None,
        key if calendar::valid_key(key) && calendars.contains(key) => Some(key.to_string()),
        _ => return Err("calendar.rule.error.calendar"),
    };
    Ok(Rule {
        id: 0,
        calendar,
        field,
        match_kind,
        pattern,
        action,
        text: if action == Action::Hide {
            String::new()
        } else {
            text
        },
    })
}

/// One calendar choice of the rule's "In" select.
pub struct ScopeChoice {
    pub key: String,
    pub label: String,
    pub selected: bool,
}

/// The preview's answer.
pub struct Preview {
    /// The title as pasted (cleaned), or empty for "nothing pasted".
    pub before: String,
    /// What the phone shows; `None` = hidden.
    pub after: Option<String>,
    /// A line under it: another rule first, no rule applies, or why the rule can't be used yet.
    pub note: Option<String>,
}

#[derive(Template)]
#[template(path = "device_calendar_rule.html")]
struct RuleTemplate {
    lang: Lang,
    title: String,
    device_id: i64,
    /// `None` = a new rule.
    rule_id: Option<i64>,
    form: RuleForm,
    scopes: Vec<ScopeChoice>,
    refusal: Option<&'static str>,
    preview: Preview,
}
crate::i18n::words!(RuleTemplate);

#[derive(Template)]
#[template(path = "partials/calendar_preview.html")]
struct PreviewTemplate {
    lang: Lang,
    preview: Preview,
}
crate::i18n::words!(PreviewTemplate);

impl RuleTemplate {
    fn action(&self) -> String {
        match self.rule_id {
            Some(rule) => format!(
                "/devices/{}/calendar/rules/{rule}#rule-form",
                self.device_id
            ),
            None => format!("/devices/{}/calendar/rules#rule-form", self.device_id),
        }
    }
}

/// The calendars a rule may name: the phone's report, and the rule's own key when it is no longer there.
fn scopes(lang: Lang, report: Option<&CalendarReport>, chosen: &str) -> Vec<ScopeChoice> {
    let mut out = vec![ScopeChoice {
        key: String::new(),
        label: t(lang, "calendar.rule.any").to_string(),
        selected: chosen.is_empty(),
    }];
    for c in report.map(|r| r.calendars.as_slice()).unwrap_or_default() {
        out.push(ScopeChoice {
            key: c.key.clone(),
            label: format!(
                "{} ({})",
                c.name,
                calendar_sub(lang, &c.account_type, c.source.as_deref())
            ),
            selected: c.key == chosen,
        });
    }
    if !chosen.is_empty() && !out.iter().any(|s| s.key == chosen) {
        out.push(ScopeChoice {
            key: chosen.to_string(),
            label: t(lang, "calendar.rule.calendar_gone").to_string(),
            selected: true,
        });
    }
    out
}

/// The editor's context: the phone's rules, its report and the keys a rule may name.
struct Context {
    rules: Vec<Rule>,
    report: Option<CalendarReport>,
    keys: HashSet<String>,
}

async fn context(state: &AppState, lang: Lang, id: i64) -> Result<Context, Box<Response>> {
    phone(state, lang, id).await?;
    let loaded = async {
        Ok::<_, sqlx::Error>((
            calendar::rules(&state.db, id).await?,
            calendar::last_report(&state.db, id).await?,
        ))
    }
    .await;
    let (rules, report) =
        loaded.map_err(|err| Box::new(read_failed(lang, err, "the calendar rules")))?;
    let mut keys: HashSet<String> = report
        .iter()
        .flat_map(|r| r.calendars.iter().map(|c| c.key.clone()))
        .collect();
    keys.extend(rules.iter().filter_map(|r| r.calendar.clone()));
    Ok(Context {
        rules,
        report,
        keys,
    })
}

/// The preview of `form` (§3.3) as rule `rule_id` (`None` = a new one, last) among the phone's rules: the event is
/// taken to be in the rule's calendar.
fn preview(lang: Lang, ctx: &Context, rule_id: Option<i64>, form: &RuleForm) -> Preview {
    let before = calendar::clean(&form.preview_title, calendar::TITLE_MAX);
    if before.is_empty() && form.preview_description.trim().is_empty() {
        return Preview {
            before,
            after: None,
            note: Some(t(lang, "calendar.preview.paste").to_string()),
        };
    }
    let edited = match parse_rule(form, &ctx.keys) {
        Ok(rule) => rule,
        Err(why) => {
            return Preview {
                after: Some(before.clone()),
                before,
                note: Some(t(lang, why).to_string()),
            };
        }
    };
    let mut rules = ctx.rules.clone();
    let at = match rule_id.and_then(|id| rules.iter().position(|r| r.id == id)) {
        Some(at) => {
            rules[at] = Rule {
                id: rules[at].id,
                ..edited.clone()
            };
            at
        }
        None => {
            rules.push(edited.clone());
            rules.len() - 1
        }
    };
    let outcome = calendar::apply(
        &rules,
        edited.calendar.as_deref(),
        &form.preview_title,
        &form.preview_description,
    );
    let note = match &outcome {
        Outcome::Unchanged(_) => Some(t(lang, "calendar.preview.no_match").to_string()),
        Outcome::Rule(i, _) if *i != at => Some(t_params(
            lang,
            "calendar.preview.other_first",
            &[("n", &(i + 1).to_string())],
        )),
        Outcome::Rule(..) => None,
    };
    Preview {
        before,
        after: outcome.shown(),
        note,
    }
}

async fn render_editor(
    state: &AppState,
    lang: Lang,
    id: i64,
    rule_id: Option<i64>,
    form: RuleForm,
    refusal: Option<&'static str>,
    status: StatusCode,
) -> Response {
    let ctx = match context(state, lang, id).await {
        Ok(ctx) => ctx,
        Err(answer) => return *answer,
    };
    let preview = preview(lang, &ctx, rule_id, &form);
    render(
        lang,
        RuleTemplate {
            lang,
            title: t(lang, "calendar.rule.title").to_string(),
            device_id: id,
            scopes: scopes(lang, ctx.report.as_ref(), &form.calendar),
            rule_id,
            form,
            refusal,
            preview,
        },
        status,
    )
}

/// The answer for a rule that is gone since the page loaded.
fn rule_gone(id: i64) -> Response {
    crate::settings_log::refused(Redirect::to(&format!(
        "/devices/{id}/calendar?notice=rule_gone#rules"
    )))
}

/// GET `/devices/{id}/calendar/rules/new`.
pub async fn new_rule(State(state): State<AppState>, lang: Lang, Path(id): Path<i64>) -> Response {
    render_editor(
        &state,
        lang,
        id,
        None,
        RuleForm::new_rule(),
        None,
        StatusCode::OK,
    )
    .await
}

/// GET `/devices/{id}/calendar/rules/{rule}`.
pub async fn show_rule(
    State(state): State<AppState>,
    lang: Lang,
    Path((id, rule_id)): Path<(i64, i64)>,
) -> Response {
    let ctx = match context(&state, lang, id).await {
        Ok(ctx) => ctx,
        Err(answer) => return *answer,
    };
    match ctx.rules.iter().find(|r| r.id == rule_id) {
        Some(rule) => {
            let form = RuleForm::of(rule);
            render_editor(&state, lang, id, Some(rule_id), form, None, StatusCode::OK).await
        }
        None => rule_gone(id),
    }
}

/// POST `/devices/{id}/calendar/rules`: a new rule, last. Refused (400, the editor as typed): a field it can't use,
/// or [MAX_RULES] already.
pub async fn create_rule(
    State(state): State<AppState>,
    lang: Lang,
    Path(id): Path<i64>,
    body: Bytes,
) -> Response {
    save_rule(&state, lang, id, None, &form_pairs(&body)).await
}

/// POST `/devices/{id}/calendar/rules/{rule}`.
pub async fn update_rule(
    State(state): State<AppState>,
    lang: Lang,
    Path((id, rule_id)): Path<(i64, i64)>,
    body: Bytes,
) -> Response {
    save_rule(&state, lang, id, Some(rule_id), &form_pairs(&body)).await
}

async fn save_rule(
    state: &AppState,
    lang: Lang,
    id: i64,
    rule_id: Option<i64>,
    fields: &[(String, String)],
) -> Response {
    let ctx = match context(state, lang, id).await {
        Ok(ctx) => ctx,
        Err(answer) => return *answer,
    };
    if rule_id.is_some_and(|r| !ctx.rules.iter().any(|x| x.id == r)) {
        return rule_gone(id);
    }
    let form = RuleForm::typed(fields);
    let refuse = |why: &'static str, form: RuleForm| async move {
        render_editor(
            state,
            lang,
            id,
            rule_id,
            form,
            Some(why),
            StatusCode::BAD_REQUEST,
        )
        .await
    };
    if rule_id.is_none() && ctx.rules.len() >= MAX_RULES {
        return refuse("calendar.rule.error.too_many", form).await;
    }
    let rule = match parse_rule(&form, &ctx.keys) {
        Ok(rule) => rule,
        Err(why) => return refuse(why, form).await,
    };
    let result: Result<i64, sqlx::Error> = match rule_id {
        Some(rule_id) => sqlx::query(
            "UPDATE device_calendar_rules SET calendar_key = ?, field = ?, match_kind = ?, pattern = ?, \
             action = ?, replacement = ? WHERE id = ? AND device_id = ?",
        )
        .bind(&rule.calendar)
        .bind(rule.field.as_str())
        .bind(rule.match_kind.as_str())
        .bind(&rule.pattern)
        .bind(rule.action.as_str())
        .bind(&rule.text)
        .bind(rule_id)
        .bind(id)
        .execute(&state.db)
        .await
        .map(|_| rule_id),
        None => sqlx::query_scalar(
            "INSERT INTO device_calendar_rules \
             (device_id, sort_order, calendar_key, field, match_kind, pattern, action, replacement) \
             VALUES (?1, (SELECT COALESCE(MAX(sort_order), 0) + 1 FROM device_calendar_rules WHERE device_id = ?1), \
             ?2, ?3, ?4, ?5, ?6, ?7) RETURNING id",
        )
        .bind(id)
        .bind(&rule.calendar)
        .bind(rule.field.as_str())
        .bind(rule.match_kind.as_str())
        .bind(&rule.pattern)
        .bind(rule.action.as_str())
        .bind(&rule.text)
        .fetch_one(&state.db)
        .await,
    };
    match result {
        Ok(saved_id) => saved(state, id, &format!("rule-{saved_id}")),
        Err(err) => failed(lang, err, "couldn't save a calendar rule"),
    }
}

/// POST `/devices/{id}/calendar/rules/preview` (see the module doc). The form's `rule` names the rule being edited
/// (empty for a new one).
pub async fn preview_rule(
    State(state): State<AppState>,
    lang: Lang,
    Path(id): Path<i64>,
    body: Bytes,
) -> Response {
    let fields = form_pairs(&body);
    let rule_id = field(&fields, "rule").parse::<i64>().ok();
    let form = RuleForm::typed(&fields);
    if field(&fields, "fragment") != "1" {
        return render_editor(&state, lang, id, rule_id, form, None, StatusCode::OK).await;
    }
    let ctx = match context(&state, lang, id).await {
        Ok(ctx) => ctx,
        Err(answer) => return *answer,
    };
    render(
        lang,
        PreviewTemplate {
            lang,
            preview: preview(lang, &ctx, rule_id, &form),
        },
        StatusCode::OK,
    )
}

/// POST `/devices/{id}/calendar/rules/{rule}/move`: `dir` `up` or `down` swaps it with its neighbour; back to
/// `#rule-<id>`.
pub async fn move_rule(
    State(state): State<AppState>,
    lang: Lang,
    Path((id, rule_id)): Path<(i64, i64)>,
    body: Bytes,
) -> Response {
    let fields = form_pairs(&body);
    if let Err(answer) = phone(&state, lang, id).await {
        return *answer;
    }
    let up = match field(&fields, "dir") {
        "up" => true,
        "down" => false,
        _ => return bad(lang, "calendar.error.move"),
    };
    let result = async {
        let mut tx = state.db.begin().await?;
        let order: Vec<(i64, i64)> = sqlx::query_as(
            "SELECT id, sort_order FROM device_calendar_rules WHERE device_id = ? ORDER BY sort_order, id",
        )
        .bind(id)
        .fetch_all(&mut *tx)
        .await?;
        let Some(at) = order.iter().position(|(r, _)| *r == rule_id) else {
            return Ok(false);
        };
        let other = if up { at.checked_sub(1) } else { Some(at + 1).filter(|o| *o < order.len()) };
        if let Some(other) = other {
            // Renumber in the new order, so equal sort orders can't stall a move.
            let mut ids: Vec<i64> = order.iter().map(|(r, _)| *r).collect();
            ids.swap(at, other);
            for (i, rule) in ids.iter().enumerate() {
                sqlx::query("UPDATE device_calendar_rules SET sort_order = ? WHERE id = ?")
                    .bind(i as i64 + 1)
                    .bind(rule)
                    .execute(&mut *tx)
                    .await?;
            }
        }
        tx.commit().await?;
        Ok::<_, sqlx::Error>(true)
    }
    .await;
    match result {
        Ok(true) => saved(&state, id, &format!("rule-{rule_id}")),
        Ok(false) => rule_gone(id),
        Err(err) => failed(lang, err, "couldn't move a calendar rule"),
    }
}

/// POST `/devices/{id}/calendar/rules/{rule}/delete`: back to `#rules` (a rule already gone lands there too).
pub async fn delete_rule(
    State(state): State<AppState>,
    lang: Lang,
    Path((id, rule_id)): Path<(i64, i64)>,
) -> Response {
    if let Err(answer) = phone(&state, lang, id).await {
        return *answer;
    }
    match sqlx::query("DELETE FROM device_calendar_rules WHERE id = ? AND device_id = ?")
        .bind(rule_id)
        .bind(id)
        .execute(&state.db)
        .await
    {
        Ok(_) => saved(&state, id, "rules"),
        Err(err) => failed(lang, err, "couldn't delete a calendar rule"),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn form(pairs: &[(&str, &str)]) -> RuleForm {
        let fields: Vec<(String, String)> = pairs
            .iter()
            .map(|(k, v)| (k.to_string(), v.to_string()))
            .collect();
        RuleForm::typed(&fields)
    }

    #[test]
    fn the_rule_form() {
        let keys: HashSet<String> = ["9f3a61c2d84be705".to_string()].into();
        let ok = parse_rule(
            &form(&[
                ("field", "title"),
                ("match", "starts"),
                ("pattern", " Gruppe 2-  skoleåret 2026/2027: "),
                ("then", "show"),
                ("text", "Skole:"),
                ("keep_rest", "on"),
                ("calendar", "9f3a61c2d84be705"),
            ]),
            &keys,
        )
        .unwrap();
        assert_eq!(ok.pattern, "Gruppe 2- skoleåret 2026/2027:");
        assert_eq!(ok.action, Action::Prefix);
        assert_eq!(ok.calendar.as_deref(), Some("9f3a61c2d84be705"));
        let refused = |pairs: &[(&str, &str)]| parse_rule(&form(pairs), &keys).unwrap_err();
        let base = [("field", "title"), ("match", "contains"), ("then", "show")];
        assert_eq!(
            refused(&[("field", "title"), ("match", "regex")]),
            "calendar.rule.error.match"
        );
        assert_eq!(
            refused(&[base[0], base[1], ("pattern", "  ")]),
            "calendar.rule.error.pattern_empty"
        );
        let long = "x".repeat(121);
        assert_eq!(
            refused(&[base[0], base[1], ("pattern", &long)]),
            "calendar.rule.error.pattern_long"
        );
        assert_eq!(
            refused(&[base[0], base[1], base[2], ("pattern", "a")]),
            "calendar.rule.error.text_empty"
        );
        let text = "y".repeat(41);
        assert_eq!(
            refused(&[base[0], base[1], base[2], ("pattern", "a"), ("text", &text)]),
            "calendar.rule.error.text_long"
        );
        assert_eq!(
            refused(&[
                ("field", "description"),
                base[1],
                base[2],
                ("pattern", "a"),
                ("text", "b")
            ]),
            "calendar.rule.error.description_hides"
        );
        assert_eq!(
            refused(&[
                base[0],
                base[1],
                ("then", "hide"),
                ("pattern", "a"),
                ("calendar", "0000000000000000")
            ]),
            "calendar.rule.error.calendar"
        );
        // Keep the rest with no text strips the match; hiding drops the text.
        let strip = parse_rule(
            &form(&[
                base[0],
                base[1],
                base[2],
                ("pattern", "a"),
                ("keep_rest", "on"),
            ]),
            &keys,
        );
        assert_eq!(strip.unwrap().action, Action::Prefix);
        let hide = parse_rule(
            &form(&[
                base[0],
                base[1],
                ("then", "hide"),
                ("pattern", "a"),
                ("text", "zz"),
            ]),
            &keys,
        );
        assert_eq!(hide.unwrap().text, "");
    }
}
