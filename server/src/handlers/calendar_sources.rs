//! Device › Calendar's calendars the server fetches (design 72): the calendar links and the CalDAV accounts, in the
//! look the user approved on 2026-11-01 (variant B, drawn with the PWA's own rows). Every word through `crate::i18n`.
//!
//! - The Calendar page's "Fetched by the server" group (`#sources`, [source_rows]): one row per link and one per
//!   CalDAV account, each to its own page, then "Add Calendar…".
//! - `GET .../calendar/links/{uid}` (`device_calendar_link.html`): the name (auto-saving, `#name`), the host, Fetching
//!   (`#fetch`: last fetched, events, next, Fetch now), Remove… (back to the Calendar page's `#sources`). A CalDAV
//!   calendar's uid goes to its account's page; a link this phone hasn't (removed meanwhile) to `#sources`.
//! - `GET .../calendar/caldav/{account}` (`device_calendar_caldav.html`): the server, the sign-in, where the password
//!   goes, a switch per calendar for this phone (`#calendars`), Test Again… (`#test`), Remove Account….
//! - `GET .../calendar/add` (`device_calendar_add.html`, `?kind=caldav` picks CalDAV): the source (a link or a CalDAV
//!   account, the fields swapped by CSS alone), posting to `POST .../calendar/links` or `POST .../calendar/caldav`;
//!   a refusal renders this page again (400) as typed - never the password -, its kind chosen, the reason under its
//!   field; a pass goes to the new source's page.
//! - The POSTs (`.../links/{uid}/name|fetch|remove`, `.../caldav/{account}/test|calendars|remove`) go back to the
//!   page their form is on, at its group, so a save never jumps to the top.

use std::collections::HashSet;

use askama::Template;
use axum::body::Bytes;
use axum::extract::{Path, Query, State};
use axum::http::StatusCode;
use axum::response::{IntoResponse, Redirect, Response};

use super::calendar::{failed, field, has, phone, read_failed, render};
use crate::AppState;
use crate::caldav::{self, AccountView};
use crate::calendar::{self, assign_free_colors};
use crate::calendar_feeds::{self, LinkRow};
use crate::display_time::Zone;
use crate::handlers::schedules::form_pairs;
use crate::i18n::{Lang, Words, t, t_count, t_params};

type Now = chrono::DateTime<chrono::Utc>;
type NoticeQuery = Query<std::collections::HashMap<String, String>>;

// ---- words shared by the pages -----------------------------------------------------------------

/// A stored stamp as the parent reads it: the clock today, else the date and the clock (`calendar.links.when_date`:
/// "27.10. 14:05", "10/27 14:05").
pub(super) fn when_text(lang: Lang, zone: &Zone, stored: Option<&str>, now: Now) -> String {
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

/// A CalDAV fetch's or Test's code in words (`calendar.caldav.why.*`), never an address or a body.
pub fn caldav_why(lang: Lang, code: &str, status: Option<i64>) -> String {
    let key = match code {
        "login_refused" => "calendar.caldav.why.login_refused",
        "host_changed" => "calendar.caldav.why.host_changed",
        "not_caldav" => "calendar.caldav.why.not_caldav",
        "no_calendars" => "calendar.caldav.why.no_calendars",
        "not_listed" => "calendar.caldav.why.not_listed",
        "key_lost" => "calendar.caldav.why.key_lost",
        "not_found" => "calendar.caldav.why.not_found",
        "status" => "calendar.caldav.why.status",
        "timeout" => "calendar.caldav.why.timeout",
        "too_big" => "calendar.caldav.why.too_big",
        "not_calendar" => "calendar.caldav.why.not_calendar",
        "private_address" => "calendar.caldav.why.private_address",
        "insecure" => "calendar.caldav.why.insecure",
        "unreadable" => "calendar.caldav.why.unreadable",
        _ => "calendar.caldav.why.network",
    };
    t_params(lang, key, &[("status", &status.unwrap_or(0).to_string())])
}

/// "Kalenderlenke" or "CalDAV": what a link's calendar is in "Calendars shown".
pub fn link_kind(lang: Lang, link: &LinkRow) -> &'static str {
    if link.kind == "caldav" {
        t(lang, "calendar.caldav.kind")
    } else {
        t(lang, "calendar.links.kind")
    }
}

/// Sealed with a music key the server no longer has (E6's L3): the server made a new key at its start (the file was
/// gone), and the source turns `key_lost` only at its next fetch - so the pages say so at once.
fn sealed_with_an_old_key(state: &AppState, fingerprint: &str) -> bool {
    state
        .music_key
        .as_ref()
        .is_some_and(|key| key.fingerprint != fingerprint)
}

/// What keeps a link from being fetched now: an old key, its account stopped (a CalDAV calendar, QA M5), its stored
/// error (`empty`: a held empty answer).
fn error_of(link: &LinkRow, key_lost: bool) -> Option<&str> {
    if key_lost {
        return Some("key_lost");
    }
    link.account_state
        .as_deref()
        .filter(|s| matches!(*s, "login_refused" | "host_changed"))
        .or(link.error.as_deref())
}

/// The failure in words, with the age of the set the phone keeps: "{why} Showing what was fetched {when}.".
fn failing_line(lang: Lang, zone: &Zone, now: Now, link: &LinkRow, code: &str) -> String {
    let when = when_text(lang, zone, link.ok_at.as_deref(), now);
    if code == "empty" {
        return t_params(lang, "calendar.links.status.held_empty", &[("when", &when)]);
    }
    let why = if link.kind == "caldav" {
        caldav_why(lang, code, link.error_status)
    } else {
        why_text(lang, code, link.error_status)
    };
    if link.ok_at.is_none() {
        return why;
    }
    t_params(
        lang,
        "calendar.links.status.failing",
        &[("why", &why), ("when", &when)],
    )
}

/// A CalDAV calendar's own failure: a refused password or a new host is the account's (its alarm while it lasts, and
/// a passing Test makes the calendar due at once), so it is not said again for each calendar.
fn own_error(link: &LinkRow) -> Option<&str> {
    link.error
        .as_deref()
        .filter(|code| !matches!(*code, "login_refused" | "host_changed"))
}

/// What stops a CalDAV account (no request for any of its calendars): the key its password is sealed with gone, a
/// refused password, a new host. Each says "test again".
pub(super) fn account_stop(state: &AppState, account: &AccountView) -> Option<&'static str> {
    if sealed_with_an_old_key(state, &account.key_fp) {
        return Some("key_lost");
    }
    match account.state.as_str() {
        "login_refused" => Some("login_refused"),
        "host_changed" => Some("host_changed"),
        _ => None,
    }
}

/// The account's server as the parent knows it: iCloud's and Fastmail's names (where `caldav::KNOWN_DOMAINS` sends
/// their e-mail addresses), else the host.
pub(super) fn provider_of(server_url: &str) -> String {
    let host = crate::music_net::host_of(server_url).unwrap_or_default();
    let of = |domain: &str| host == domain || host.ends_with(&format!(".{domain}"));
    if of("icloud.com") {
        "iCloud".to_string()
    } else if of("fastmail.com") {
        "Fastmail".to_string()
    } else {
        host
    }
}

fn link_page(id: i64, uid: &str) -> String {
    format!("/devices/{id}/calendar/links/{uid}")
}

pub(super) fn caldav_page(id: i64, account: i64) -> String {
    format!("/devices/{id}/calendar/caldav/{account}")
}

/// Back to the Calendar page's sources group: after a removal, or for a source gone since the page loaded.
fn back_to_sources(id: i64) -> Response {
    Redirect::to(&format!("/devices/{id}/calendar#sources")).into_response()
}

/// A save's answer: the phone nudged, back to `location` (a page and its group).
fn nudged(state: &AppState, id: i64, location: &str) -> Response {
    let _ = state.command_notify.send(id);
    Redirect::to(location).into_response()
}

/// The auto-pick after a links form's save (add, rename, remove, ticks - not "Fetch now", where no calendar can
/// appear): a parent who sets the phone up from the links alone may never touch the calendars form, so every calendar
/// the phone reports - and every link's own calendar, whose key the server knows before the phone has it - gets its
/// colour here too, and a removed link's colour-only row goes. Best effort: the link's write is done, so a failure
/// here is logged, never a 500.
async fn colors_after_link_save(state: &AppState, id: i64) {
    let result = async {
        let mut tx = state.db.begin().await?;
        assign_free_colors(&mut tx, id, &[], 0).await?;
        tx.commit().await
    }
    .await;
    if let Err(err) = result {
        tracing::warn!(device_id = id, %err, "couldn't pick the calendars' colours after a link save");
    }
}

// ---- the Calendar page's group -----------------------------------------------------------------

/// One row of "Fetched by the server": a link (teal tile) or a CalDAV account (indigo), to its page.
pub struct SourceRow {
    pub caldav: bool,
    /// The link's uid or the account's id (`data-link` / `data-caldav`).
    pub id: String,
    pub href: String,
    pub name: String,
    /// "skole.example.no · Fetched 15:10", "iCloud · 1 of 3 calendars", or what is failing.
    pub sub: String,
    /// The sub says something failed (shown orange).
    pub failing: bool,
}

/// The rows, and whether a link is sealed with a key the server no longer has (the group's alarm).
pub(super) fn source_rows(
    state: &AppState,
    lang: Lang,
    zone: &Zone,
    now: Now,
    id: i64,
    links: &[LinkRow],
    accounts: &[AccountView],
) -> (Vec<SourceRow>, bool) {
    let mut rows = Vec::new();
    let mut key_new = false;
    for link in links.iter().filter(|l| l.kind != "caldav") {
        let key_lost = sealed_with_an_old_key(state, &link.key_fp);
        key_new |= key_lost;
        let (sub, failing) = match error_of(link, key_lost) {
            None => (
                t_params(
                    lang,
                    "calendar.sources.link_sub",
                    &[
                        ("host", &link.host),
                        ("when", &when_text(lang, zone, link.ok_at.as_deref(), now)),
                    ],
                ),
                false,
            ),
            Some("empty") => (failing_line(lang, zone, now, link, "empty"), true),
            Some(code @ "key_lost") => (why_text(lang, code, None), true),
            Some(code) => (
                t_params(
                    lang,
                    "calendar.sources.retry",
                    &[
                        ("why", &why_text(lang, code, link.error_status)),
                        ("when", &when_text(lang, zone, Some(&link.next_at), now)),
                    ],
                ),
                true,
            ),
        };
        rows.push(SourceRow {
            caldav: false,
            id: link.uid.clone(),
            href: link_page(id, &link.uid),
            name: link.name.clone(),
            sub,
            failing,
        });
    }
    for account in accounts {
        let provider = provider_of(&account.server_url);
        let mine: Vec<&LinkRow> = links
            .iter()
            .filter(|l| l.account_id == Some(account.id))
            .collect();
        let broken = mine.iter().filter(|l| own_error(l).is_some()).count() as i64;
        let (sub, failing) = match account_stop(state, account) {
            Some(stop) => (
                t(
                    lang,
                    match stop {
                        "key_lost" => "calendar.sources.stopped.key_lost",
                        "login_refused" => "calendar.sources.stopped.login_refused",
                        _ => "calendar.sources.stopped.host_changed",
                    },
                )
                .to_string(),
                true,
            ),
            None if broken > 0 => (
                t_count(
                    lang,
                    "calendar.sources.caldav_failing",
                    broken,
                    &[("provider", &provider)],
                ),
                true,
            ),
            None => {
                // Of the calendars the account lists, those on this phone (one it no longer lists is `broken`).
                let listed = account.calendars();
                let ticked = listed
                    .iter()
                    .filter(|c| mine.iter().any(|l| l.uid == account.uid(c)))
                    .count();
                (
                    t_count(
                        lang,
                        "calendar.sources.caldav_sub",
                        listed.len() as i64,
                        &[("provider", &provider), ("ticked", &ticked.to_string())],
                    ),
                    false,
                )
            }
        };
        rows.push(SourceRow {
            caldav: true,
            id: account.id.to_string(),
            href: caldav_page(id, account.id),
            name: account.username.clone(),
            sub,
            failing,
        });
    }
    (rows, key_new)
}

// ---- a link's page -----------------------------------------------------------------------------

#[derive(Template)]
#[template(path = "device_calendar_link.html")]
struct LinkTemplate {
    lang: Lang,
    /// The link's name (the head's title and the page's).
    title: String,
    device_id: i64,
    uid: String,
    name: String,
    host: String,
    /// What is failing, with the age of the set the phone keeps.
    failing: Option<String>,
    /// "Last fetched" and "Events": once a fetch was stored.
    last_fetched: Option<String>,
    events: i64,
    /// "Next": when the next fetch is due (none while the key is lost: no fetch can help).
    next: Option<String>,
    /// The phone's side and the counts: waiting for the phone, update Vibb One, refused, unknown zones, cut, churn,
    /// the LAN warning.
    notes: Vec<String>,
    notice: Option<&'static str>,
}
crate::i18n::words!(LinkTemplate);

/// GET `/devices/{id}/calendar/links/{uid}`.
pub async fn show_link(
    State(state): State<AppState>,
    lang: Lang,
    Path((id, uid)): Path<(i64, String)>,
    Query(query): NoticeQuery,
) -> Response {
    if let Err(answer) = phone(&state, lang, id).await {
        return *answer;
    }
    let loaded = async {
        Ok::<_, sqlx::Error>((
            calendar_feeds::links(&state.db, id).await?,
            calendar::last_report(&state.db, id).await?,
            crate::handlers::device_api::last_capabilities(&state.db, id).await?,
        ))
    }
    .await;
    let (links, report, capabilities) = match loaded {
        Ok(found) => found,
        Err(err) => return read_failed(lang, err, "a calendar link"),
    };
    let Some(link) = links.into_iter().find(|l| l.uid == uid) else {
        return back_to_sources(id);
    };
    if let (true, Some(account)) = (link.kind == "caldav", link.account_id) {
        return Redirect::to(&caldav_page(id, account)).into_response();
    }
    let zone = Zone::load(&state.db, lang).await;
    let now = chrono::Utc::now();
    let key_lost = sealed_with_an_old_key(&state, &link.key_fp);
    let failing = error_of(&link, key_lost).map(|code| failing_line(lang, &zone, now, &link, code));
    let on_phone = report
        .iter()
        .flat_map(|r| &r.calendars)
        .any(|c| c.key == calendar_feeds::calendar_key(&link.uid));
    let mut notes = Vec::new();
    if !capabilities.iter().any(|c| c == calendar_feeds::CAPABILITY) {
        notes.push(t(lang, "calendar.links.update_phone").to_string());
    } else if !on_phone {
        notes.push(t(lang, "calendar.links.waiting").to_string());
    }
    let count = |key: &str, n: i64| t_count(lang, key, n, &[]);
    let applied = report
        .iter()
        .flat_map(|r| &r.feeds)
        .find(|f| f.id == link.uid);
    if let Some(refused) = applied.map(|a| a.refused).filter(|n| *n > 0) {
        notes.push(count("calendar.links.status.refused", refused));
    }
    if link.unknown_tz > 0 {
        notes.push(count("calendar.links.status.unknown_tz", link.unknown_tz));
    }
    if link.cut > 0 {
        notes.push(count("calendar.links.status.cut", link.cut));
    }
    if link.churn >= calendar_feeds::CHURN_FETCHES {
        notes.push(t(lang, "calendar.links.status.churn").to_string());
    }
    if link.lan_http {
        notes.push(t(lang, "calendar.links.status.lan_http").to_string());
    }
    let template = LinkTemplate {
        lang,
        device_id: id,
        last_fetched: link
            .ok_at
            .as_deref()
            .map(|at| when_text(lang, &zone, Some(at), now)),
        events: link.events,
        next: (!key_lost).then(|| when_text(lang, &zone, Some(&link.next_at), now)),
        failing,
        notes,
        notice: match query.get("notice").map(String::as_str) {
            Some("fetch_wait") => Some("calendar.links.fetch_wait"),
            _ => None,
        },
        uid: link.uid,
        title: link.name.clone(),
        name: link.name,
        host: link.host,
    };
    render(lang, template, StatusCode::OK)
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

/// POST `/devices/{id}/calendar/links/{uid}/name`: `name` (1-60 after cleaning; empty → the feed's, else the host),
/// back to the link's page at `#name`; the phone renames its calendar from the policy alone.
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
        Ok(None) => return back_to_sources(id),
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
        Ok(_) => {
            colors_after_link_save(&state, id).await;
            nudged(&state, id, &format!("{}#name", link_page(id, &uid)))
        }
        Err(err) => failed(lang, err, "couldn't rename a calendar link"),
    }
}

/// POST `/devices/{id}/calendar/links/{uid}/fetch`: "Fetch now", at most once per source per 5 minutes (else back
/// with `?notice=fetch_wait`); the fetch runs before the answer (at most 60 s), back to the link's page at `#fetch`.
/// Not a save.
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
        Ok(None) => return back_to_sources(id),
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
            return Redirect::to(&format!("{}?notice=fetch_wait#fetch", link_page(id, &uid)))
                .into_response();
        }
        Ok(_) => {}
        Err(err) => return failed(lang, err, "couldn't start a calendar link's fetch"),
    }
    if let Err(err) = calendar_feeds::refresh(&state, source_id, now).await {
        return failed(lang, err, "couldn't store a calendar link's fetch");
    }
    Redirect::to(&format!("{}#fetch", link_page(id, &uid))).into_response()
}

/// POST `/devices/{id}/calendar/links/{uid}/remove`: the link goes off this phone (its calendar with it, at the
/// phone's next sync); a source no phone names goes at once (the migration's trigger). The calendar's settings and
/// rules stay, by its key, and come back with the same link. Back to the Calendar page's `#sources` - a CalDAV
/// calendar the account no longer lists (its page's Remove) back to the account's `#calendars`.
pub async fn remove_link(
    State(state): State<AppState>,
    lang: Lang,
    Path((id, uid)): Path<(i64, String)>,
) -> Response {
    if let Err(answer) = phone(&state, lang, id).await {
        return *answer;
    }
    // This phone's link only: another phone's (or none) is gone from here.
    let source_id = match link_of(&state, id, &uid).await {
        Ok(Some(source)) => source,
        Ok(None) => return back_to_sources(id),
        Err(err) => return read_failed(lang, err, "a calendar link"),
    };
    let account: Option<i64> =
        match sqlx::query_scalar("SELECT account_id FROM calendar_sources WHERE id = ?")
            .bind(source_id)
            .fetch_one(&state.db)
            .await
        {
            Ok(found) => found,
            Err(err) => return read_failed(lang, err, "a calendar link"),
        };
    let back = match account {
        Some(account) => format!("{}#calendars", caldav_page(id, account)),
        None => format!("/devices/{id}/calendar#sources"),
    };
    match sqlx::query("DELETE FROM device_calendar_feeds WHERE device_id = ? AND source_id = ?")
        .bind(id)
        .bind(source_id)
        .execute(&state.db)
        .await
    {
        Ok(_) => {
            colors_after_link_save(&state, id).await;
            nudged(&state, id, &back)
        }
        Err(err) => failed(lang, err, "couldn't remove a calendar link"),
    }
}

// ---- a CalDAV account's page -------------------------------------------------------------------

/// One calendar of the account, with its switch for this phone.
pub struct CaldavCalendar {
    pub id: String,
    pub name: String,
    pub ticked: bool,
    /// The kid's events are written here (design 72b D5.1).
    pub kid_writes: bool,
    /// A ticked calendar's fetch: "Fetched 15:10 · 12 events", or what is failing.
    pub sub: Option<String>,
    pub failing: bool,
}

/// A calendar this phone has from the account that the account no longer lists: its name on this phone, why, and
/// its source's uid for Remove (`.../links/{uid}/remove`, back to `#calendars`).
pub struct GoneCalendar {
    pub uid: String,
    pub name: String,
    pub why: String,
}

#[derive(Template)]
#[template(path = "device_calendar_caldav.html")]
struct CaldavTemplate {
    lang: Lang,
    /// The account's username.
    title: String,
    device_id: i64,
    account: i64,
    username: String,
    /// "iCloud", "Fastmail", else the host.
    provider: String,
    signed_in: String,
    /// The account is stopped (an old key, a refused password, a new host): the alarm at the top.
    stop: Option<&'static str>,
    hosts: String,
    lan_http: bool,
    calendars: Vec<CaldavCalendar>,
    /// This phone's calendars of the account that its last Test no longer listed (`not_listed`): no switch, a Remove.
    gone: Vec<GoneCalendar>,
    /// A refused tick (too many calendars on the phone, on the server).
    calendars_notice: Option<&'static str>,
    /// The marked calendar was unticked (design 72b D5.1): the kid's events are no longer written to it.
    kid_ended: Option<String>,
    /// Test Again's result or the last Test's failure, and its colour: `ok` (it passed, green), `failed` (a Test that
    /// failed, orange as the account's row) or `refused` (Test Again refused before testing - too soon, no password -,
    /// red as a refused save).
    test_line: Option<String>,
    test_tone: &'static str,
    /// Test Again's form shown open: its last answer asks for a password or a moment.
    test_open: bool,
}
crate::i18n::words!(CaldavTemplate);

/// GET `/devices/{id}/calendar/caldav/{account}`. Every account is the family's, shown on every phone's page.
pub async fn show_caldav(
    State(state): State<AppState>,
    lang: Lang,
    Path((id, account_id)): Path<(i64, i64)>,
    Query(query): NoticeQuery,
) -> Response {
    if let Err(answer) = phone(&state, lang, id).await {
        return *answer;
    }
    let loaded = async {
        Ok::<_, sqlx::Error>((
            caldav::accounts(&state.db).await?,
            calendar_feeds::links(&state.db, id).await?,
        ))
    }
    .await;
    let marked = match crate::calendar_kid_events::marked_uid(&state.db, id).await {
        Ok(marked) => marked,
        Err(err) => return read_failed(lang, err, "the kid's CalDAV calendar"),
    };
    let (accounts, links) = match loaded {
        Ok(found) => found,
        Err(err) => return read_failed(lang, err, "a CalDAV account"),
    };
    let Some(account) = accounts.into_iter().find(|a| a.id == account_id) else {
        return back_to_sources(id);
    };
    let zone = Zone::load(&state.db, lang).await;
    let now = chrono::Utc::now();
    let stop = account_stop(&state, &account);
    let calendars = account
        .calendars()
        .iter()
        .map(|c| {
            let uid = account.uid(c);
            let link = links.iter().find(|l| l.uid == uid);
            // The account's own stop is the page's alarm; each calendar says only its own failure.
            let (sub, failing) = match link {
                None => (None, false),
                Some(l) => match own_error(l) {
                    Some(code) => (Some(failing_line(lang, &zone, now, l, code)), true),
                    None if l.ok_at.is_some() => (
                        Some(t_count(
                            lang,
                            "calendar.links.status.fetched",
                            l.events,
                            &[("when", &when_text(lang, &zone, l.ok_at.as_deref(), now))],
                        )),
                        false,
                    ),
                    None => (None, false),
                },
            };
            CaldavCalendar {
                id: c.id.clone(),
                name: c.name.clone(),
                ticked: link.is_some(),
                kid_writes: marked.as_deref() == Some(uid.as_str()),
                sub,
                failing,
            }
        })
        .collect();
    let listed: HashSet<String> = account.calendars().iter().map(|c| account.uid(c)).collect();
    let gone = links
        .iter()
        .filter(|l| l.account_id == Some(account.id) && !listed.contains(&l.uid))
        .map(|l| GoneCalendar {
            uid: l.uid.clone(),
            name: l.name.clone(),
            why: caldav_why(lang, l.error.as_deref().unwrap_or("not_listed"), None),
        })
        .collect();
    let notice = query.get("notice").map(String::as_str);
    let asks = match notice {
        Some("caldav_wait") => Some("calendar.caldav.test_wait"),
        Some("caldav_retype") => Some("calendar.caldav.error.retype"),
        Some("caldav_too_long") => Some("calendar.caldav.error.too_long"),
        _ => None,
    };
    let tested = notice == Some("caldav_tested");
    let last_test = account
        .test_error
        .as_deref()
        .map(|code| caldav_why(lang, code, account.test_status));
    let signed_in = when_text(lang, &zone, account.ok_at.as_deref(), now);
    let (test_line, test_tone) = match (asks, stop, last_test) {
        (Some(key), _, _) => (Some(t(lang, key).to_string()), "refused"),
        (None, Some(stop), _) if tested => (Some(t(lang, stop_line(stop)).to_string()), "failed"),
        (None, None, Some(why)) => (
            Some(t_params(
                lang,
                "calendar.caldav.status.last_test",
                &[("why", &why)],
            )),
            "failed",
        ),
        (None, None, None) if tested => (
            Some(t_params(
                lang,
                "calendar.caldav.tested",
                &[("when", &signed_in)],
            )),
            "ok",
        ),
        _ => (None, "ok"),
    };
    let template = CaldavTemplate {
        lang,
        device_id: id,
        account: account.id,
        provider: provider_of(&account.server_url),
        signed_in,
        stop: stop.map(stop_line),
        hosts: t_params(
            lang,
            "calendar.caldav.hosts",
            &[("hosts", &account.hosts().join(", "))],
        ),
        lan_http: account.lan_http,
        calendars,
        gone,
        kid_ended: (notice == Some("kid_caldav_ended"))
            .then(|| query.get("cal"))
            .flatten()
            .and_then(|cal| account.calendars().into_iter().find(|c| c.id == *cal))
            .map(|c| t_params(lang, "calendar.kid_caldav.ended", &[("name", &c.name)])),
        calendars_notice: match notice {
            Some("caldav_full") => Some("calendar.caldav.error.too_many_calendars"),
            Some("caldav_sources_full") => Some("calendar.links.error.too_many_sources"),
            Some("caldav_no_key") => Some("calendar.caldav.error.no_key"),
            _ => None,
        },
        test_line,
        test_tone,
        test_open: asks.is_some(),
        title: account.username.clone(),
        username: account.username,
    };
    render(lang, template, StatusCode::OK)
}

/// The account page's alarm for a stop ([account_stop]).
fn stop_line(stop: &str) -> &'static str {
    match stop {
        "key_lost" => "calendar.caldav.status.key_lost",
        "login_refused" => "calendar.caldav.status.login_refused",
        _ => "calendar.caldav.status.host_changed",
    }
}

fn caldav_back(id: i64, account: i64, notice: Option<&str>, anchor: &str) -> Response {
    let query = notice.map(|n| format!("?notice={n}")).unwrap_or_default();
    Redirect::to(&format!("{}{query}#{anchor}", caldav_page(id, account))).into_response()
}

/// POST `/devices/{id}/calendar/caldav/{account}/test`: "Test again", with `password` when one is typed (else the
/// stored one). Back to the account's page at `#test`, which says how it went (`?notice=caldav_tested`); too soon →
/// `?notice=caldav_wait`, no password to use → `?notice=caldav_retype`. Not a save.
pub async fn test_caldav_again(
    State(state): State<AppState>,
    lang: Lang,
    Path((id, account)): Path<(i64, i64)>,
    body: Bytes,
) -> Response {
    let fields = form_pairs(&body);
    if let Err(answer) = phone(&state, lang, id).await {
        return *answer;
    }
    let tested = caldav::test_again(
        &state,
        account,
        field(&fields, "password"),
        chrono::Utc::now(),
    )
    .await;
    let notice = match tested {
        Ok(()) | Err(caldav::TestError::Failed(_)) => "caldav_tested",
        Err(caldav::TestError::Db(err)) => {
            return failed(lang, err, "couldn't test a CalDAV account");
        }
        Err(caldav::TestError::Wait) => "caldav_wait",
        Err(caldav::TestError::Refused("calendar.caldav.error.gone")) => {
            return back_to_sources(id);
        }
        Err(caldav::TestError::Refused("calendar.caldav.error.too_long")) => "caldav_too_long",
        Err(caldav::TestError::Refused(_)) => "caldav_retype",
    };
    caldav_back(id, account, Some(notice), "test")
}

/// POST `/devices/{id}/calendar/caldav/{account}/calendars`: `cal` (every listed calendar's id) and `on_<id>` for the
/// ticked ones - exactly the rows it was sent. A newly ticked calendar is fetched before the answer (each at most
/// 60 s), so the phone's first sync has its events; back to the account's page at `#calendars`, the phone nudged.
/// Too many calendars on the phone → `?notice=caldav_full`, nothing saved.
pub async fn save_caldav_calendars(
    State(state): State<AppState>,
    lang: Lang,
    Path((id, account)): Path<(i64, i64)>,
    body: Bytes,
) -> Response {
    let fields = form_pairs(&body);
    if let Err(answer) = phone(&state, lang, id).await {
        return *answer;
    }
    let listed: Vec<String> = fields
        .iter()
        .filter(|(k, _)| k == "cal")
        .map(|(_, v)| v.clone())
        .collect();
    let ticked: HashSet<String> = listed
        .iter()
        .filter(|c| has(&fields, &format!("on_{c}")))
        .cloned()
        .collect();
    let now = chrono::Utc::now();
    // Design 72b D5.1: unticking the marked calendar ends the mark by the cascade - the save says so.
    let marked = crate::calendar_kid_events::marked_uid(&state.db, id)
        .await
        .ok()
        .flatten();
    // The marked calendar's id in this account's list (the notice names it), when it is one of this account's.
    let marked_cal: Option<String> = match &marked {
        Some(uid) => caldav::accounts(&state.db)
            .await
            .ok()
            .and_then(|all| all.into_iter().find(|a| a.id == account))
            .and_then(|a| {
                a.calendars()
                    .into_iter()
                    .find(|c| a.uid(c) == *uid)
                    .map(|c| c.id)
            }),
        None => None,
    };
    match caldav::save_ticks(&state, id, account, &listed, &ticked, now).await {
        Ok(made) => {
            let still = crate::calendar_kid_events::marked_uid(&state.db, id)
                .await
                .ok()
                .flatten();
            let ended = marked_cal.filter(|_| still.is_none());
            for source in made {
                if let Err(err) = calendar_feeds::refresh(&state, source, now).await {
                    tracing::error!(source_id = source, %err, "couldn't store a CalDAV calendar's first fetch");
                }
            }
            colors_after_link_save(&state, id).await;
            let query = match ended {
                Some(cal) => format!("?notice=kid_caldav_ended&cal={cal}"),
                None => String::new(),
            };
            nudged(
                &state,
                id,
                &format!("{}{query}#calendars", caldav_page(id, account)),
            )
        }
        Err(caldav::TickError::Gone) => crate::settings_log::refused(back_to_sources(id)),
        Err(caldav::TickError::Refused("calendar.links.error.too_many_sources")) => {
            crate::settings_log::refused(caldav_back(
                id,
                account,
                Some("caldav_sources_full"),
                "calendars",
            ))
        }
        Err(caldav::TickError::Refused("calendar.caldav.error.no_key")) => {
            crate::settings_log::refused(caldav_back(
                id,
                account,
                Some("caldav_no_key"),
                "calendars",
            ))
        }
        Err(caldav::TickError::Refused(_)) => {
            crate::settings_log::refused(caldav_back(id, account, Some("caldav_full"), "calendars"))
        }
        Err(caldav::TickError::Db(err)) => {
            failed(lang, err, "couldn't save a phone's CalDAV calendars")
        }
    }
}

/// POST `/devices/{id}/calendar/caldav/{account}/remove`: the account goes (confirmed: its calendars leave every
/// phone, the password is deleted); every phone that had one is nudged. Back to the Calendar page's `#sources`.
pub async fn remove_caldav(
    State(state): State<AppState>,
    lang: Lang,
    Path((id, account)): Path<(i64, i64)>,
) -> Response {
    if let Err(answer) = phone(&state, lang, id).await {
        return *answer;
    }
    let marked_here = crate::calendar_kid_events::marked_uid(&state.db, id)
        .await
        .ok()
        .flatten();
    match caldav::remove(&state, account).await {
        Ok(phones) => {
            for phone in phones {
                let _ = state.command_notify.send(phone);
            }
            colors_after_link_save(&state, id).await;
            let ended = marked_here.is_some()
                && crate::calendar_kid_events::marked_uid(&state.db, id)
                    .await
                    .ok()
                    .flatten()
                    .is_none();
            if ended {
                // Design 72b D5.1: the kid's events are no longer written to that account's calendar.
                return Redirect::to(&format!(
                    "/devices/{id}/calendar?notice=kid_caldav_ended#sources"
                ))
                .into_response();
            }
            back_to_sources(id)
        }
        Err(err) => failed(lang, err, "couldn't remove a CalDAV account"),
    }
}

// ---- Add Calendar ------------------------------------------------------------------------------

/// The link form as typed (a refusal renders it again).
#[derive(Default)]
pub struct AddForm {
    pub url: String,
    pub name: String,
    pub error: Option<String>,
}

/// The CalDAV form as typed (a refusal renders it again - never the password), and the field the reason is under.
#[derive(Default)]
pub struct CaldavForm {
    pub server: String,
    pub username: String,
    pub error: Option<String>,
    /// `server`, `username` or `password`.
    pub error_field: &'static str,
}

#[derive(Template)]
#[template(path = "device_calendar_add.html")]
struct AddTemplate {
    lang: Lang,
    title: String,
    device_id: i64,
    /// The CalDAV account chosen (`?kind=caldav`, or a refused CalDAV form); else a link.
    caldav: bool,
    /// No music key: nothing can be sealed, the page says why and has no form (QA L11).
    off: bool,
    /// The phone has its 10 links and calendars: no link form.
    phone_full: bool,
    /// The server has its 10 accounts: no CalDAV form.
    accounts_full: bool,
    link: AddForm,
    account: CaldavForm,
}
crate::i18n::words!(AddTemplate);

/// GET `/devices/{id}/calendar/add` (`?kind=caldav` chooses the CalDAV account).
pub async fn show_add(
    State(state): State<AppState>,
    lang: Lang,
    Path(id): Path<i64>,
    Query(query): NoticeQuery,
) -> Response {
    let caldav = query.get("kind").is_some_and(|k| k == "caldav");
    let forms = (AddForm::default(), CaldavForm::default());
    render_add(&state, lang, id, caldav, forms, StatusCode::OK).await
}

async fn render_add(
    state: &AppState,
    lang: Lang,
    id: i64,
    caldav: bool,
    (link, account): (AddForm, CaldavForm),
    status: StatusCode,
) -> Response {
    if let Err(answer) = phone(state, lang, id).await {
        return *answer;
    }
    let loaded = async {
        Ok::<_, sqlx::Error>((
            calendar_feeds::links(&state.db, id).await?.len(),
            caldav::accounts(&state.db).await?.len(),
        ))
    }
    .await;
    let (links, accounts) = match loaded {
        Ok(found) => found,
        Err(err) => return read_failed(lang, err, "the calendar links"),
    };
    render(
        lang,
        AddTemplate {
            lang,
            title: t(lang, "calendar.sources.add").to_string(),
            device_id: id,
            caldav,
            off: state.music_key.is_none(),
            phone_full: links as i64 >= calendar_feeds::MAX_LINKS_PER_PHONE,
            accounts_full: accounts as i64 >= caldav::MAX_ACCOUNTS,
            link,
            account,
        },
        status,
    )
}

/// POST `/devices/{id}/calendar/links`: `url` and an optional `name` (design 72 §5). Added (or attached to the
/// source the link already is) → 303 to the link's page; refused → Add Calendar again as typed (400, the reason under
/// the link, never the body). The phone is nudged.
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
        Ok(uid) => {
            colors_after_link_save(&state, id).await;
            return nudged(&state, id, &link_page(id, &uid));
        }
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
    let link = AddForm {
        url: crate::audit::clip(url, calendar_feeds::MAX_URL_CHARS).to_string(),
        name: crate::audit::clip(name, 200).to_string(),
        error: Some(error),
    };
    let forms = (link, CaldavForm::default());
    render_add(&state, lang, id, false, forms, StatusCode::BAD_REQUEST).await
}

/// POST `/devices/{id}/calendar/caldav`: `server` (or an e-mail), `username` (empty: the e-mail), `password` (the app
/// password). Test: passed → 303 to the account's page, its calendars listed to tick; refused or failed → Add
/// Calendar again as typed (400, CalDAV chosen, the reason under its field; never the password). Not a save
/// (`settings_log::NOT_SAVES`): the phone gets nothing until a calendar is ticked.
pub async fn test_caldav(
    State(state): State<AppState>,
    lang: Lang,
    Path(id): Path<i64>,
    body: Bytes,
) -> Response {
    let fields = form_pairs(&body);
    if let Err(answer) = phone(&state, lang, id).await {
        return *answer;
    }
    let (server, username) = (field(&fields, "server"), field(&fields, "username"));
    let tested = caldav::test_new(
        &state,
        server,
        username,
        field(&fields, "password"),
        chrono::Utc::now(),
    )
    .await;
    let (error, error_field) = match tested {
        Ok(account) => return Redirect::to(&caldav_page(id, account)).into_response(),
        Err(caldav::TestError::Db(err)) => {
            return failed(lang, err, "couldn't test a CalDAV account");
        }
        Err(caldav::TestError::Refused(key)) => (
            t(lang, key).to_string(),
            match key {
                "calendar.caldav.error.no_username" => "username",
                "calendar.caldav.error.no_password" => "password",
                _ => "server",
            },
        ),
        Err(caldav::TestError::Wait) => {
            (t(lang, "calendar.caldav.test_wait").to_string(), "server")
        }
        Err(caldav::TestError::Failed(err)) => (
            caldav_why(lang, err.code, err.status.map(i64::from)),
            if err.code == "login_refused" {
                "password"
            } else {
                "server"
            },
        ),
    };
    let account = CaldavForm {
        server: crate::audit::clip(server, caldav::MAX_SERVER_CHARS).to_string(),
        username: crate::audit::clip(username, caldav::MAX_USERNAME_CHARS).to_string(),
        error: Some(error),
        error_field,
    };
    let forms = (AddForm::default(), account);
    render_add(&state, lang, id, true, forms, StatusCode::BAD_REQUEST).await
}
