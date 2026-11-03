//! Device › Calendar (design 69 §3, `crate::calendar`): the parent's side of Dagen, the kid's day view in Vibb One.
//! Every word through `crate::i18n` (the page's [Lang] from the request).
//!
//! - `GET /devices/{id}/calendar` (`device_calendar.html`): the groups top to bottom - Dagen (`#dagen`: on Home, the
//!   kid's own events), On the lock screen (`#lock`: the "Next" line, the reminders' master switch), Calendars shown
//!   (`#calendars`: the phone's last report, per calendar Shown and its reminder), Title rules (`#rules`); between
//!   them "Fetched by the server" (`#sources`: the calendar links and CalDAV accounts, each a row to its own page -
//!   `crate::handlers::calendar_sources`).
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
    self, Action, COLORS, CalendarReport, Field, KID_LOCAL_KEY, MAX_RULES, Match, Outcome,
    PATTERN_MAX, PolicyCalendar, REMIND_MINUTES, Rule, TEXT_MAX, assign_free_colors,
};
use crate::calendar_feeds;
use crate::handlers::calendar_sources::{self, SourceRow};
use crate::handlers::schedules::form_pairs;
use crate::i18n::{Lang, Words, t, t_params};

pub(super) fn field<'a>(fields: &'a [(String, String)], name: &str) -> &'a str {
    fields
        .iter()
        .find(|(k, _)| k == name)
        .map_or("", |(_, v)| v.as_str())
}

pub(super) fn has(fields: &[(String, String)], name: &str) -> bool {
    fields.iter().any(|(k, _)| k == name)
}

pub(super) fn read_failed(lang: Lang, err: sqlx::Error, what: &str) -> Response {
    tracing::error!(%err, "can't read {what}");
    (
        StatusCode::INTERNAL_SERVER_ERROR,
        t(lang, "calendar.error.read"),
    )
        .into_response()
}

/// A save (or a render) that failed: a 500 in the page's language, nothing changed.
pub(super) fn failed(lang: Lang, err: impl std::fmt::Display, what: &str) -> Response {
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

pub(super) fn render(lang: Lang, template: impl Template, status: StatusCode) -> Response {
    match template.render() {
        Ok(html) => (status, Html(html)).into_response(),
        Err(err) => failed(lang, err, "failed to render a calendar page"),
    }
}

/// The phone's name, or the answer: 404 for an unknown phone, 500 on a DB error.
pub(super) async fn phone(state: &AppState, lang: Lang, id: i64) -> Result<String, Box<Response>> {
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

pub(super) fn saved(state: &AppState, id: i64, anchor: &str) -> Response {
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

/// One colour choice of a calendar's select: the name (`calendar::COLORS`) and its word.
pub struct ColorChoice {
    pub value: &'static str,
    pub label: String,
    pub selected: bool,
    /// "No free colour" (QA L6): all eight in use on this phone - shown selected, posts nothing.
    pub disabled: bool,
}

/// The catalog key of each colour's word, by name - literals, so `i18n::tests::every_key_used_exists` sees them.
const COLOR_WORDS: [(&str, &str); 8] = [
    ("sky", "calendar.color.sky"),
    ("forest", "calendar.color.forest"),
    ("sun", "calendar.color.sun"),
    ("berry", "calendar.color.berry"),
    ("sea", "calendar.color.sea"),
    ("lavender", "calendar.color.lavender"),
    ("sand", "calendar.color.sand"),
    ("slate", "calendar.color.slate"),
];

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
    /// The Colour row: every calendar but the kid's own pick (peach, never a choice).
    pub color_row: bool,
    /// The saved colour's name (the swatch `cal-<name>`), none = no dot yet.
    pub color: Option<String>,
    pub colors: Vec<ColorChoice>,
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
    /// A CalDAV calendar offered to an older Vibb One (design 72b D5.3): shown, not choosable.
    pub disabled: bool,
}

/// One line of the status under the select while a CalDAV calendar is the pick (design 72b D5.2): counts only.
pub struct KidStatusLine {
    pub text: String,
    /// Orange: something the parent may act on.
    pub warn: bool,
    /// `data-kid-status` (the tests').
    pub data: &'static str,
    /// The account's page, for its stop.
    pub link: Option<String>,
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
    /// A CalDAV calendar is offered disabled: this phone's Vibb One can't hand its events over yet (D5.3).
    kid_caldav_older: bool,
    /// The status under the select while a CalDAV calendar is the pick (D5.2).
    kid_status: Vec<KidStatusLine>,
    /// The kid's mass delete is held: "Put them back on the phone" (D2.6).
    kid_restore: bool,
    /// Kalender's ⓘ: the server the kid's events go to (the marked calendar's, else a general word).
    kid_server: String,
    glance_next: bool,
    /// The select's value: `off`, `row` or `ping` (the row plus one sound, migration 0113).
    reminders: &'static str,
    /// `ping` chosen for a phone whose Vibb One shows the row but doesn't know the sound yet (no
    /// `calendar::PING_CAPABILITY`): it is sent `row`, and the row says so.
    ping_older: bool,
    state: ReportState,
    calendars: Vec<CalendarView>,
    rules: Vec<RuleView>,
    rules_skipped: Vec<i64>,
    can_add_rule: bool,
    notice: Option<&'static str>,
    /// The kid's events no longer written to a CalDAV calendar whose account was removed (design 72b D5.1).
    sources_notice: Option<&'static str>,
    /// "Fetched by the server" (`#sources`): the links and the CalDAV accounts, each a row to its own page.
    sources: Vec<SourceRow>,
    /// "Add Calendar…": the phone has room for another link or calendar.
    can_add_source: bool,
    /// No music key: nothing can be sealed, the group says why and has no add row (QA L11).
    sources_off: bool,
    /// A link is sealed with a music key the server no longer has (E6's L3): paste it again.
    links_key_new: bool,
}
crate::i18n::words!(CalendarTemplate);

impl CalendarTemplate {
    /// Kalender's ⓘ on the CalDAV choice (design 72b D5.4), naming the marked calendar's server.
    fn kid_info(&self) -> String {
        t_params(
            self.lang,
            "calendar.kid_caldav.info",
            &[("server", &self.kid_server)],
        )
    }

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

/// The eight colours, the saved one selected; none saved yet = "Pick for me" first (value `""`, which the save reads
/// as unchanged and the auto-pick fills) - a select of the eight alone would post the first for every calendar at
/// the first autosave of anything on the form -, or, with all eight in use on the phone, the disabled "No free
/// colour" (QA L6: the auto-pick gave this calendar none; the eight stay for a pick by hand).
fn color_choices(lang: Lang, current: Option<&str>, pool_full: bool) -> Vec<ColorChoice> {
    let auto = current.is_none().then(|| ColorChoice {
        value: "",
        label: t(
            lang,
            if pool_full {
                "calendar.color.none_free"
            } else {
                "calendar.color.auto"
            },
        )
        .to_string(),
        selected: true,
        disabled: pool_full,
    });
    auto.into_iter()
        .chain(COLOR_WORDS.iter().map(|(value, word)| ColorChoice {
            value,
            label: t(lang, word).to_string(),
            selected: current == Some(*value),
            disabled: false,
        }))
        .collect()
}

/// The Colour row shows for every calendar but the kid's own: the parent's pick, or the phone's own "Min kalender"
/// (`KID_LOCAL_KEY`) - both peach on the phone (QA L3).
fn color_row(key: &str, saved: Option<&PolicyCalendar>) -> bool {
    key != KID_LOCAL_KEY && !saved.is_some_and(|s| s.writable_for_kid)
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
    caldav: Vec<KidChoice>,
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
            disabled: false,
        },
        KidChoice {
            value: "phone".to_string(),
            label: t(lang, "calendar.kid.phone").to_string(),
            selected: kid_adds && chosen.is_none() && !caldav.iter().any(|c| c.selected),
            disabled: false,
        },
    ];
    for c in report.map(|r| r.calendars.as_slice()).unwrap_or_default() {
        if writable_for_kid(c) {
            out.push(KidChoice {
                value: c.key.clone(),
                label: c.name.clone(),
                selected: kid_adds && chosen == Some(c.key.as_str()),
                disabled: false,
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
            disabled: false,
        });
    }
    // Design 72b D5.1: the CalDAV calendars ticked for this phone, written by the server.
    out.extend(caldav);
    out
}

/// The CalDAV calendars ticked for this phone that the kid's events may be written to (design 72b D5.1): not one its
/// last Test says the account can't write to (`writable: false`) unless it is the pick already; disabled for an
/// older Vibb One unless picked (D5.3).
fn caldav_kid_choices(
    lang: Lang,
    links: &[calendar_feeds::LinkRow],
    accounts: &[crate::caldav::AccountView],
    marked: Option<&str>,
    capable: bool,
) -> Vec<KidChoice> {
    let mut out = Vec::new();
    for link in links.iter().filter(|l| l.kind == "caldav") {
        let account = accounts.iter().find(|a| Some(a.id) == link.account_id);
        let writable = account
            .and_then(|a| a.calendars().into_iter().find(|c| a.uid(c) == link.uid))
            .and_then(|c| c.writable);
        let selected = marked == Some(link.uid.as_str());
        if writable == Some(false) && !selected {
            continue;
        }
        let server = account
            .map(|a| calendar_sources::provider_of(&a.server_url))
            .unwrap_or_else(|| link.host.clone());
        out.push(KidChoice {
            value: format!("caldav:{}", link.uid),
            label: t_params(
                lang,
                "calendar.kid.caldav_option",
                &[("name", &link.name), ("server", &server)],
            ),
            selected,
            disabled: !capable && !selected,
        });
    }
    out
}

/// The status under the select (design 72b D5.2): one line per state, counts only - never a title.
fn kid_status_lines(
    lang: Lang,
    zone: &crate::display_time::Zone,
    now: chrono::DateTime<chrono::Utc>,
    status: &crate::calendar_kid_events::KidStatus,
    stop: Option<(&'static str, String)>,
    other_phone: Option<&str>,
    server: &str,
) -> Vec<KidStatusLine> {
    let line = |text: String, warn: bool, data: &'static str| KidStatusLine {
        text,
        warn,
        data,
        link: None,
    };
    let count = |key: &str, n: i64| crate::i18n::t_count(lang, key, n, &[]);
    let mut out = Vec::new();
    if let Some((key, page)) = stop {
        out.push(KidStatusLine {
            text: t(lang, key).to_string(),
            warn: true,
            data: "account",
            link: Some(page),
        });
    }
    match status.error.as_deref() {
        Some("write_refused") => out.push(line(
            t(lang, "calendar.kid_caldav.status.write_refused").to_string(),
            true,
            "write_refused",
        )),
        Some("full") => out.push(line(
            t(lang, "calendar.kid_caldav.status.full").to_string(),
            true,
            "full",
        )),
        Some("no_etag") => out.push(line(
            t(lang, "calendar.kid_caldav.status.no_etag").to_string(),
            true,
            "no_etag",
        )),
        Some("not_listed") => out.push(line(
            t_params(
                lang,
                "calendar.kid_caldav.status.not_listed",
                &[("server", server)],
            ),
            true,
            "not_listed",
        )),
        Some("too_big") => out.push(line(
            t(lang, "calendar.kid_caldav.status.too_big").to_string(),
            false,
            "too_big",
        )),
        _ => {}
    }
    if let Some((code, _)) = &status.rejected {
        let why = match (code.as_str(), other_phone) {
            ("tag_pinned", Some(name)) => t_params(
                lang,
                "calendar.kid_caldav.why.tag_pinned",
                &[("name", name)],
            ),
            ("tag_pinned", None) => t(lang, "calendar.kid_caldav.why.tag_gone").to_string(),
            _ => t(lang, "calendar.kid_caldav.why.bad_set").to_string(),
        };
        out.push(line(
            t_params(
                lang,
                "calendar.kid_caldav.status.rejected",
                &[("why", &why)],
            ),
            true,
            "rejected",
        ));
    }
    if status.clock {
        out.push(line(
            t(lang, "calendar.kid_caldav.status.clock").to_string(),
            true,
            "clock",
        ));
    }
    if let Some((n, until)) = status.held {
        out.push(line(
            crate::i18n::t_count(
                lang,
                "calendar.kid_caldav.status.held",
                n as i64,
                &[(
                    "when",
                    &calendar_sources::when_text(
                        lang,
                        zone,
                        Some(&crate::music_sweep::stamp(until)),
                        now,
                    ),
                )],
            ),
            true,
            "held",
        ));
    }
    if let Some((n, until)) = status.back_held {
        out.push(line(
            crate::i18n::t_count(
                lang,
                "calendar.kid_caldav.status.back_held",
                n,
                &[(
                    "when",
                    &calendar_sources::when_text(
                        lang,
                        zone,
                        Some(&crate::music_sweep::stamp(until)),
                        now,
                    ),
                )],
            ),
            true,
            "back_held",
        ));
    }
    if status.restoring > 0 {
        out.push(line(
            count("calendar.kid_caldav.status.restoring", status.restoring),
            false,
            "restoring",
        ));
    }
    if status.refused > 0 {
        out.push(line(
            count("calendar.kid_caldav.status.refused", status.refused),
            true,
            "refused",
        ));
    }
    if !status.received {
        out.push(line(
            t(lang, "calendar.kid_caldav.status.waiting_phone").to_string(),
            false,
            "waiting_phone",
        ));
        return out;
    }
    if let Some(at) = status.written_at.as_deref() {
        out.push(line(
            crate::i18n::t_count(
                lang,
                "calendar.kid_caldav.status.written",
                status.events,
                &[(
                    "when",
                    &calendar_sources::when_text(lang, zone, Some(at), now),
                )],
            ),
            false,
            "written",
        ));
    }
    if status.pending > 0 {
        out.push(line(
            count("calendar.kid_caldav.status.pending", status.pending),
            false,
            "pending",
        ));
    }
    if status.back > 0 {
        out.push(line(
            count("calendar.kid_caldav.status.back", status.back),
            false,
            "back",
        ));
    }
    if status.cut > 0 {
        out.push(line(
            count("calendar.kid_caldav.status.cut", status.cut),
            false,
            "cut",
        ));
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
    let state = &state;
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
            crate::caldav::accounts(&state.db).await?,
            crate::calendar_kid_events::marked_uid(&state.db, id).await?,
            crate::calendar_kid_events::status(&state.db, id, state.kid_writes.now()).await?,
        ))
    }
    .await;
    let (switches, saved, rules, report, capabilities, links, accounts, marked, kid_status) =
        match loaded {
            Ok(found) => found,
            Err(err) => return read_failed(lang, err, "the calendar settings"),
        };
    let zone = crate::display_time::Zone::load(&state.db, lang).await;
    let now = chrono::Utc::now();
    let link_keys: HashMap<String, &calendar_feeds::LinkRow> = links
        .iter()
        .map(|l| (calendar_feeds::calendar_key(&l.uid), l))
        .collect();
    let (on_home, kid_adds, glance_next, lock_reminders) =
        switches.unwrap_or_else(calendar::default_switches);
    let reminders = match lock_reminders.as_str() {
        "row" => "row",
        "ping" => "ping",
        _ => "off",
    };
    let saved_by_key: BTreeMap<&str, &PolicyCalendar> =
        saved.iter().map(|c| (c.key.as_str(), c)).collect();
    // All eight colours held by this phone's rows (hidden ones too): a calendar without one gets none (QA L6).
    let pool_full = saved
        .iter()
        .filter_map(|c| c.color.as_deref())
        .collect::<HashSet<&str>>()
        .len()
        >= COLORS.len();
    let mut calendars: Vec<CalendarView> = Vec::new();
    let mut names: HashMap<&str, &str> = HashMap::new();
    if let Some(report) = &report {
        for c in &report.calendars {
            names.insert(c.key.as_str(), c.name.as_str());
            let saved = saved_by_key.get(c.key.as_str()).copied();
            let mut sub = calendar_sub(lang, &c.account_type, c.source.as_deref());
            if let Some(link) = link_keys.get(&c.key) {
                sub = calendar_sources::link_kind(lang, link).to_string();
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
                color_row: color_row(&c.key, saved),
                color: saved.and_then(|s| s.color.clone()),
                colors: color_choices(lang, saved.and_then(|s| s.color.as_deref()), pool_full),
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
                calendar_sources::link_kind(lang, link),
                t(lang, "calendar.links.waiting")
            ),
            shown: saved.is_none_or(|s| s.shown),
            reminds: remind_choices(lang, &remind_value(saved)),
            on_phone: true,
            color_row: color_row(&key, saved),
            color: saved.and_then(|s| s.color.clone()),
            colors: color_choices(lang, saved.and_then(|s| s.color.as_deref()), pool_full),
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
                color_row: color_row(&s.key, Some(s)),
                color: s.color.clone(),
                colors: color_choices(lang, s.color.as_deref(), pool_full),
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
    let (sources, links_key_new) =
        calendar_sources::source_rows(state, lang, &zone, now, id, &links, &accounts);
    // Design 72b: the CalDAV calendars the kid's events may be written to, and the status while one is the pick.
    let kid_capable = capabilities
        .iter()
        .any(|c| c == crate::calendar_kid_events::CAPABILITY);
    let caldav_choices =
        caldav_kid_choices(lang, &links, &accounts, marked.as_deref(), kid_capable);
    let kid_caldav_older = caldav_choices.iter().any(|c| c.disabled);
    let marked_link = marked
        .as_deref()
        .and_then(|uid| links.iter().find(|l| l.uid == uid));
    let marked_account =
        marked_link.and_then(|l| accounts.iter().find(|a| Some(a.id) == l.account_id));
    let kid_server = marked_account
        .map(|a| calendar_sources::provider_of(&a.server_url))
        .unwrap_or_else(|| t(lang, "calendar.kid_caldav.info_server_any").to_string());
    let mut kid_lines = Vec::new();
    let mut kid_restore = false;
    if let Some(status) = &kid_status {
        let stop = marked_account.and_then(|a| {
            calendar_sources::account_stop(state, a).map(|code| {
                (
                    calendar_sources::stopped_line(code),
                    calendar_sources::caldav_page(id, a.id),
                )
            })
        });
        let other_phone = match status.rejected.as_ref().and_then(|(_, other)| *other) {
            Some(other) => sqlx::query_scalar::<_, String>("SELECT name FROM devices WHERE id = ?")
                .bind(other)
                .fetch_optional(&state.db)
                .await
                .ok()
                .flatten(),
            None => None,
        };
        kid_lines = kid_status_lines(
            lang,
            &zone,
            now,
            status,
            stop,
            other_phone.as_deref(),
            &kid_server,
        );
        kid_restore = status.held.is_some();
    }
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
            kid_choices: kid_choices(lang, kid_adds, report.as_ref(), &saved, caldav_choices),
            kid_caldav_older,
            kid_status: kid_lines,
            kid_restore,
            kid_server,
            glance_next,
            reminders,
            ping_older: reminders == "ping"
                && capabilities
                    .iter()
                    .any(|c| c == calendar::CALENDAR_CAPABILITY)
                && !capabilities.iter().any(|c| c == calendar::PING_CAPABILITY),
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
            sources_notice: (notice == Some("kid_caldav_ended"))
                .then_some("calendar.kid_caldav.ended_account"),
            can_add_source: links.len() < calendar_feeds::MAX_LINKS_PER_PHONE as usize,
            sources,
            sources_off: state.music_key.is_none(),
            links_key_new,
        },
        StatusCode::OK,
    )
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
    // Design 72b D5.1: `caldav:<source uid>` marks a CalDAV calendar ticked for this phone - not one its account's
    // last Test says can't be written to, and (unless it is the pick already) only for a Vibb One that hands its
    // events over.
    let caldav_pick: Option<i64> = match kid.strip_prefix("caldav:") {
        None => None,
        Some(uid) => match caldav_source(&state, id, uid).await {
            Ok(Some(source)) => Some(source),
            Ok(None) => return bad(lang, "calendar.error.kid_calendar"),
            Err(err) => return read_failed(lang, err, "the phone's CalDAV calendars"),
        },
    };
    let pick: Option<Option<String>> = match kid {
        "" => None,
        _ if caldav_pick.is_some() => Some(None),
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
            // The kid's events written by the server to the marked CalDAV calendar, or not (D4.1: one transaction).
            match caldav_pick {
                Some(source) => crate::calendar_kid_events::mark(&mut tx, id, source).await?,
                None => crate::calendar_kid_events::unmark(&mut tx, id).await?,
            }
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
            // The kid's calendar is peach: its colour goes back to the pool, and a calendar that stopped being the
            // kid's gets the first free one.
            sqlx::query(
                "UPDATE device_calendars SET color = NULL WHERE device_id = ? AND writable_for_kid = 1",
            )
            .bind(id)
            .execute(&mut *tx)
            .await?;
            assign_free_colors(&mut tx, id, &[], 0).await?;
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

/// The source id of `uid` when the phone may mark it for the kid's events (design 72b D5.5): a `caldav` source
/// ticked for this phone, not `writable: false` at its account's last Test, and a launcher with the capability -
/// or it is the pick already (an autosave of the form keeps it).
async fn caldav_source(state: &AppState, id: i64, uid: &str) -> Result<Option<i64>, sqlx::Error> {
    if !calendar_feeds::valid_uid(uid) {
        return Ok(None);
    }
    let found: Option<(i64, Option<i64>, bool)> = sqlx::query_as(
        "SELECT s.id, s.account_id, d.kid_writes FROM device_calendar_feeds d \
         JOIN calendar_sources s ON s.id = d.source_id WHERE d.device_id = ? AND s.uid = ? AND s.kind = 'caldav'",
    )
    .bind(id)
    .bind(uid)
    .fetch_optional(&state.db)
    .await?;
    let Some((source, account, already)) = found else {
        return Ok(None);
    };
    if already {
        return Ok(Some(source));
    }
    let capable = crate::handlers::device_api::last_capabilities(&state.db, id)
        .await?
        .iter()
        .any(|c| c == crate::calendar_kid_events::CAPABILITY);
    if !capable {
        return Ok(None);
    }
    let accounts = crate::caldav::accounts(&state.db).await?;
    let writable = accounts
        .iter()
        .find(|a| Some(a.id) == account)
        .and_then(|a| a.calendars().into_iter().find(|c| a.uid(c) == uid))
        .map(|c| c.writable);
    Ok(match writable {
        // Listed by the last Test and not refused by its privileges.
        Some(Some(false)) | None => None,
        Some(_) => Some(source),
    })
}

/// POST `/devices/{id}/calendar/dagen/restore` (design 72b D2.6): the kid's held deletes handed back to the phone
/// as the calendar has them; back to `#dagen`.
pub async fn restore_kid_events(
    State(state): State<AppState>,
    lang: Lang,
    Path(id): Path<i64>,
) -> Response {
    if let Err(answer) = phone(&state, lang, id).await {
        return *answer;
    }
    match crate::calendar_kid_events::restore_held(&state, id, state.kid_writes.now()).await {
        Ok(_) => saved(&state, id, "dagen"),
        Err(err) => failed(lang, err, "couldn't put the kid's events back"),
    }
}

/// A saved calendar that says only the defaults (shown, no reminder, not the kid's, no colour) is no row.
async fn prune_default_rows(
    tx: &mut sqlx::Transaction<'_, sqlx::Sqlite>,
    id: i64,
) -> Result<(), sqlx::Error> {
    sqlx::query(
        "DELETE FROM device_calendars WHERE device_id = ? AND shown = 1 AND remind = 'off' \
         AND writable_for_kid = 0 AND color IS NULL",
    )
    .bind(id)
    .execute(&mut **tx)
    .await
    .map(|_| ())
}

/// POST `/devices/{id}/calendar/lock`: `glance_next` (missing = off) and `reminders` (`off`, `row` or `ping` - the
/// select "Reminders on the lock screen": Off / Quiet row / Quiet row + sound; `ping` is stored as `row` with
/// `calendar_lock_ping`, migration 0113), back to `#lock`. Any other `reminders` is a 400.
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
    let (reminders, ping) = match field(&fields, "reminders") {
        "" | "off" => ("off", false),
        "row" => ("row", false),
        "ping" => ("row", true),
        _ => return bad(lang, "calendar.error.reminders"),
    };
    match sqlx::query(
        "UPDATE device_policy SET calendar_glance_next = ?, calendar_lock_reminders = ?, calendar_lock_ping = ? \
         WHERE device_id = ?",
    )
    .bind(has(&fields, "glance_next"))
    .bind(reminders)
    .bind(ping)
    .bind(id)
    .execute(&state.db)
    .await
    {
        Ok(_) => saved(&state, id, "lock"),
        Err(err) => failed(lang, err, "couldn't save the lock screen's calendar switches"),
    }
}

/// POST `/devices/{id}/calendar/calendars`: one form, exactly the rows it was sent (design 31 §5.6) - each `key`
/// (16 hex, at most 100) with `shown_<key>` (missing = hidden), `remind_<key>` (`off`, `own`, 5/10/15/30/60;
/// missing = unchanged) and `color_<key>` (one of the eight names; missing = unchanged; anything else a 400, nothing
/// written). Then every shown calendar without a colour gets the first free one ([assign_free_colors]). The kid's
/// choice is kept. Back to `#calendars`.
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
    let mut colors: Vec<(&str, &str)> = Vec::new();
    for key in &keys {
        match field(&fields, &format!("color_{key}")) {
            "" => {}
            name if calendar::valid_color(name) => colors.push((key, name)),
            _ => return bad(lang, "calendar.error.color"),
        }
    }
    let result = async {
        let mut tx = state.db.begin().await?;
        for key in &keys {
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
        for (key, color) in &colors {
            sqlx::query(
                "UPDATE device_calendars SET color = ? WHERE device_id = ? AND calendar_key = ? \
                 AND writable_for_kid = 0",
            )
            .bind(color)
            .bind(id)
            .bind(key)
            .execute(&mut *tx)
            .await?;
        }
        assign_free_colors(&mut tx, id, &keys, 0).await?;
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
