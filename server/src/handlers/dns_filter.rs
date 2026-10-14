//! Settings › Web Filter (`/dns`): the shared blocklists and blocked/allowed domains every phone's
//! own on-device filter resolves its effective list from (see `crate::dns_engine`), and the DNS
//! server phones forward to (at the bottom). Every mutating handler here: updates the DB, calls
//! `dns_engine::compile_blocklist` to recompute the compiled list devices fetch from
//! `GET /api/devices/dns-blocklist`, nudges the phones it reaches, then redirects back to its own
//! group on `/dns` (design 31 §5.1: a fragment, so a save near the bottom never lands at the top,
//! with or without JS). A DB error is a 500 that says nothing was changed.
//!
//! The per-phone parts (a list on or off for one phone, its own domains, the blocked-domain log)
//! are on the phone's Web Filter page since design 31 step 5 (`device_pages::view_filter`,
//! `/devices/{id}/filter`); `/dns?device=` and `/dns/log?device=` redirect there. Their POST
//! routes are shared and return to the page named in their `back` field ([back_to]).

use askama::Template;
use axum::extract::{Path, Query, State};
use axum::http::StatusCode;
use axum::response::{Html, IntoResponse, Redirect, Response};
use axum::{Extension, Form};
use std::collections::HashMap;
use std::time::Duration;

use crate::AppState;
use crate::dns_engine;
use crate::models::{Device, DnsBlocklist, DnsCustomDomain, DnsFilterSettings};
use crate::security::CurrentAdmin;

/// A feed on the page: its switch, a sub-line (its size, or its problem in a few words), and when
/// a download failed the whole story behind its ⓘ (security review M6: a feed that fails keeps
/// its previous domains, and phones using one that never loaded keep their own list).
struct FeedRow {
    list: DnsBlocklist,
    sub: String,
    failing: bool,
    detail: Option<String>,
}

/// How a feed's last download went (`dns_engine::CompiledBlocklist`), typed (design 31 M5): a
/// phone's Web Filter page shows a problem's short title, this page also the sentence behind ⓘ.
#[derive(Debug, PartialEq, Eq)]
pub(crate) enum FeedState<'a> {
    /// Its domains are in: the last download worked.
    Loaded(usize),
    /// The last download failed; its previous domains are kept.
    Failing { count: usize, error: &'a str },
    /// Never loaded, so no phone has it yet; the last error, if a download was tried.
    NotLoaded(Option<&'a str>),
}

impl FeedState<'_> {
    pub(crate) fn of(compiled: &dns_engine::CompiledBlocklist, id: i64) -> FeedState<'_> {
        let Some(list) = compiled.lists.iter().find(|list| list.blocklist_id == id) else {
            return FeedState::NotLoaded(None);
        };
        match (list.last_error.as_deref(), list.loaded) {
            (None, _) => FeedState::Loaded(list.domains.len()),
            (Some(error), true) => FeedState::Failing {
                count: list.domains.len(),
                error,
            },
            (Some(error), false) => FeedState::NotLoaded(Some(error)),
        }
    }

    /// A list's row: the problem in at most 4 words, `None` while the list is loaded.
    pub(crate) fn problem(&self) -> Option<&'static str> {
        match self {
            FeedState::Loaded(_) => None,
            FeedState::Failing { .. } => Some("Last download failed"),
            FeedState::NotLoaded(_) => Some("Not loaded yet"),
        }
    }

    /// What happened, for the ⓘ of a list with a problem (the download error in brackets).
    fn detail(&self) -> Option<String> {
        match self {
            FeedState::Loaded(_) => None,
            FeedState::Failing { count, error } => Some(format!(
                "The last download failed ({error}); its previous {count} domains are kept. It is \
                 tried again every 5 minutes."
            )),
            FeedState::NotLoaded(None) => {
                Some("Not loaded yet: it is downloaded within 5 minutes.".to_string())
            }
            FeedState::NotLoaded(Some(error)) => Some(format!(
                "Not loaded yet ({error}). Phones get the other lists and your own domains \
                 without it until it loads; it is tried again every 5 minutes."
            )),
        }
    }
}

/// The group a domain list is in on both Web Filter pages.
fn domain_group(list_type: &str) -> &'static str {
    if list_type == "allow" {
        "allowed"
    } else {
        "blocked"
    }
}

/// Where a Web Filter form returns (design 31 §5.1): `back=filter` with a phone goes to the
/// form's group on that phone's Web Filter page, anything else to the form's group on `/dns` - the
/// same group name on both, but a phone's list setting (`/dns`'s Phones group, the phone page's
/// Blocklists). The phone comes from the path or the stored row, never from `back`.
fn back_to(form: &HashMap<String, String>, device: Option<i64>, group: &str) -> String {
    match (form.get("back").map(String::as_str), device) {
        (Some("filter"), Some(id)) if group == "phones" => format!("/devices/{id}/filter#lists"),
        (Some("filter"), Some(id)) => format!("/devices/{id}/filter#{group}"),
        _ => format!("/dns#{group}"),
    }
}

/// A write that failed: logged, and the parent is told nothing changed. An unknown phone or list
/// (a foreign key) is a 404.
fn write_failed(err: sqlx::Error) -> Response {
    if let sqlx::Error::Database(db) = &err
        && db.is_foreign_key_violation()
    {
        return (StatusCode::NOT_FOUND, "Phone or list not found").into_response();
    }
    tracing::error!(%err, "couldn't save a web filter change");
    (
        StatusCode::INTERNAL_SERVER_ERROR,
        "Couldn't save - nothing was changed. Check the server log.",
    )
        .into_response()
}

#[derive(Template)]
#[template(path = "dns_filter.html")]
struct DnsFilterTemplate {
    title: String,
    settings: DnsFilterSettings,
    blocklists: Vec<FeedRow>,
    blocked_domains: Vec<DnsCustomDomain>,
    allowed_domains: Vec<DnsCustomDomain>,
    /// Each phone links to its own Web Filter page (its lists, domains and log).
    devices: Vec<Device>,
    /// The group whose add was refused ("blocked", "allowed", "lists"): its refusal under it, and
    /// Add a List… open.
    refused: &'static str,
    /// What a refused add sent (`typed`, `name`, `url` in the query, qa-31-s14-code N3): back in
    /// its field, so one wrong character needs no retyping.
    typed: String,
    typed_name: String,
    typed_url: String,
}

/// A typed value for a refusal's query: URL-encoded, at most 300 characters.
fn query_value(value: Option<&String>) -> String {
    let value: String = value
        .map(|v| v.chars().take(300).collect())
        .unwrap_or_default();
    form_urlencoded::byte_serialize(value.as_bytes()).collect()
}

/// `?device=X` (an old bookmark or link): the phone's parts are on its Web Filter page now
/// (design 31 §3.1, L5). Anything that isn't a phone id is ignored.
fn old_device_param(params: &HashMap<String, String>) -> Option<i64> {
    params.get("device").and_then(|s| s.parse::<i64>().ok())
}

fn read_failed(err: sqlx::Error) -> Response {
    tracing::error!(%err, "couldn't read the web filter");
    (
        StatusCode::INTERNAL_SERVER_ERROR,
        "Couldn't read the web filter - check the server log.",
    )
        .into_response()
}

/// The page. Every read is a 500 on a DB error, never an empty list (Remove and the switches would
/// act on what isn't shown).
pub async fn show_dns_filter(
    State(state): State<AppState>,
    Query(params): Query<HashMap<String, String>>,
) -> Response {
    if let Some(id) = old_device_param(&params) {
        return Redirect::to(&format!("/devices/{id}/filter")).into_response();
    }
    let read = async {
        let settings = sqlx::query_as::<_, DnsFilterSettings>(
            "SELECT * FROM dns_filter_settings WHERE id = 1",
        )
        .fetch_one(&state.db)
        .await?;
        let blocklists =
            sqlx::query_as::<_, DnsBlocklist>("SELECT * FROM dns_blocklists ORDER BY name")
                .fetch_all(&state.db)
                .await?;
        // Global (device_id IS NULL) custom domains only - the site-wide defaults. A phone's own
        // are on its Web Filter page.
        let custom = sqlx::query_as::<_, DnsCustomDomain>(
            "SELECT * FROM dns_custom_domains WHERE device_id IS NULL ORDER BY domain",
        )
        .fetch_all(&state.db)
        .await?;
        let devices = sqlx::query_as::<_, Device>("SELECT * FROM devices ORDER BY name, id")
            .fetch_all(&state.db)
            .await?;
        Ok::<_, sqlx::Error>((settings, blocklists, custom, devices))
    }
    .await;
    let (settings, blocklists, custom, devices) = match read {
        Ok(read) => read,
        Err(err) => return read_failed(err),
    };
    let (blocked_domains, allowed_domains): (Vec<_>, Vec<_>) =
        custom.into_iter().partition(|d| d.list_type == "block");

    let blocklists = {
        let compiled = state.dns_compiled.read().await;
        blocklists
            .into_iter()
            .map(|list| {
                let feed = FeedState::of(&compiled, list.id);
                let sub = match (&feed, feed.problem()) {
                    (_, Some(problem)) => problem.to_string(),
                    (FeedState::Loaded(1), None) => "1 domain".to_string(),
                    (FeedState::Loaded(n), None) => format!("{n} domains"),
                    (_, None) => String::new(),
                };
                FeedRow {
                    list,
                    failing: feed.problem().is_some(),
                    detail: feed.detail(),
                    sub,
                }
            })
            .collect()
    };
    let refused = match params.get("notice").map(String::as_str) {
        Some("not_a_domain_block") => "blocked",
        Some("not_a_domain_allow") => "allowed",
        Some("bad_list") => "lists",
        _ => "",
    };
    let typed = |key: &str| {
        params
            .get(key)
            .filter(|_| !refused.is_empty())
            .cloned()
            .unwrap_or_default()
    };

    Html(
        DnsFilterTemplate {
            title: "Web Filter".to_string(),
            settings,
            blocklists,
            blocked_domains,
            allowed_domains,
            devices,
            refused,
            typed: typed("typed"),
            typed_name: typed("name"),
            typed_url: typed("url"),
        }
        .render()
        .unwrap(),
    )
    .into_response()
}

/// Nudges the devices a DNS change affects (handy step 7 fix round, QA #9): without it the change
/// would wait for the phone's 30-minute backstop sync. `None` = a global change, every device.
async fn nudge(state: &AppState, device: Option<i64>) {
    match device {
        Some(id) => {
            let _ = state.command_notify.send(id);
        }
        None => match crate::time_rules::all_device_ids(&state.db).await {
            Ok(ids) => {
                for id in ids {
                    let _ = state.command_notify.send(id);
                }
            }
            Err(err) => tracing::error!(%err, "couldn't list devices to nudge"),
        },
    }
}

/// The DNS server phones forward what isn't blocked to: `cloudflare` or `quad9`, else a 400.
/// Back to its group at the bottom (`#server`).
pub async fn set_upstream(
    State(state): State<AppState>,
    Form(form): Form<HashMap<String, String>>,
) -> Response {
    let upstream = match form.get("upstream").map(String::as_str) {
        Some("quad9") => "quad9",
        Some("cloudflare") => "cloudflare",
        _ => return (StatusCode::BAD_REQUEST, "Unknown DNS server").into_response(),
    };
    let result = sqlx::query(
        "UPDATE dns_filter_settings SET upstream = ?, updated_at = datetime('now') WHERE id = 1",
    )
    .bind(upstream)
    .execute(&state.db)
    .await;
    if let Err(err) = result {
        return write_failed(err);
    }
    nudge(&state, None).await;
    Redirect::to("/dns#server").into_response()
}

/// Adds a shared list (on for every phone). A name and an http(s) URL, else nothing is added and
/// the page reopens Add a List… with why (`?notice=bad_list#lists`).
pub async fn create_blocklist(
    State(state): State<AppState>,
    Form(form): Form<HashMap<String, String>>,
) -> Response {
    let name = form.get("name").map(|s| s.trim()).unwrap_or_default();
    let url = form.get("url").map(|s| s.trim()).unwrap_or_default();
    let web = url.starts_with("https://") || url.starts_with("http://");
    if name.is_empty() || !web || url.len() > 2000 || name.chars().count() > 100 {
        return Redirect::to(&format!(
            "/dns?notice=bad_list&name={}&url={}#lists",
            query_value(form.get("name")),
            query_value(form.get("url")),
        ))
        .into_response();
    }
    let result = sqlx::query("INSERT INTO dns_blocklists (name, url, enabled) VALUES (?, ?, 1)")
        .bind(name)
        .bind(url)
        .execute(&state.db)
        .await;
    if let Err(err) = result {
        return write_failed(err);
    }
    dns_engine::compile_blocklist(&state, &state.dns_compiled).await;
    nudge(&state, None).await;
    Redirect::to("/dns#lists").into_response()
}

/// A shared list on or off for every phone (one auto-saving switch, missing = off); 404 for an
/// unknown list. Back to `#lists`.
pub async fn toggle_blocklist(
    State(state): State<AppState>,
    Path(id): Path<i64>,
    Form(form): Form<HashMap<String, String>>,
) -> Response {
    let enabled = form.contains_key("enabled");
    let result = sqlx::query("UPDATE dns_blocklists SET enabled = ? WHERE id = ?")
        .bind(enabled)
        .bind(id)
        .execute(&state.db)
        .await;
    match result {
        Ok(done) if done.rows_affected() == 0 => {
            return (StatusCode::NOT_FOUND, "List not found").into_response();
        }
        Ok(_) => {}
        Err(err) => return write_failed(err),
    }
    dns_engine::compile_blocklist(&state, &state.dns_compiled).await;
    nudge(&state, None).await;
    Redirect::to("/dns#lists").into_response()
}

/// Deletes a shared list (no page offers it today; kept for the route). Back to `#lists`.
pub async fn delete_blocklist(State(state): State<AppState>, Path(id): Path<i64>) -> Response {
    let result = sqlx::query("DELETE FROM dns_blocklists WHERE id = ?")
        .bind(id)
        .execute(&state.db)
        .await;
    if let Err(err) = result {
        return write_failed(err);
    }
    dns_engine::compile_blocklist(&state, &state.dns_compiled).await;
    nudge(&state, None).await;
    Redirect::to("/dns#lists").into_response()
}

/// Adds a blocked or allowed domain, for every phone or (`device_id`) one. Absent or empty
/// `device_id` = every phone (device_id NULL, migrations/0011); anything else that isn't a number
/// is a 400, never a silent "every phone". A name that isn't a domain (`dns_engine`'s check) adds
/// nothing and goes back to its group with `?notice=not_a_domain_block|allow`, on the phone's page
/// (`back=filter`) or on `/dns`, where the refusal shows under the add row. A domain already in
/// that list is kept once; an unknown phone is a 404. Back to `back` ([back_to]).
pub async fn create_custom_domain(
    State(state): State<AppState>,
    Form(form): Form<HashMap<String, String>>,
) -> Response {
    let domain = form.get("domain").map(String::as_str).unwrap_or_default();
    let domain = domain.trim().trim_end_matches('.').to_lowercase();
    let list_type = match form.get("list_type").map(String::as_str) {
        Some("allow") => "allow",
        _ => "block",
    };
    let group = domain_group(list_type);
    let device_id = match form.get("device_id").map(|s| s.trim()) {
        None | Some("") => None,
        Some(id) => match id.parse::<i64>() {
            Ok(id) => Some(id),
            Err(_) => return (StatusCode::BAD_REQUEST, "Unknown phone").into_response(),
        },
    };
    if !dns_engine::looks_like_a_domain(&domain) {
        // Back to the group, with the refusal there (qa-31-s5-code L1).
        let page = back_to(&form, device_id, group);
        let path = page.split('#').next().unwrap_or("/dns");
        // The global page gets what was typed back in its field; a phone's page doesn't take it.
        let typed = if path == "/dns" {
            format!("&typed={}", query_value(form.get("domain")))
        } else {
            String::new()
        };
        return Redirect::to(&format!(
            "{path}?notice=not_a_domain_{list_type}{typed}#{group}"
        ))
        .into_response();
    }
    // The same domain twice in one list is stored once (qa-31-s5-code N4).
    let result = sqlx::query(
        "INSERT INTO dns_custom_domains (domain, list_type, device_id) SELECT ?1, ?2, ?3 \
         WHERE NOT EXISTS (SELECT 1 FROM dns_custom_domains \
           WHERE domain = ?1 AND list_type = ?2 AND device_id IS ?3)",
    )
    .bind(&domain)
    .bind(list_type)
    .bind(device_id)
    .execute(&state.db)
    .await;
    if let Err(err) = result {
        return write_failed(err);
    }
    dns_engine::compile_blocklist(&state, &state.dns_compiled).await;
    nudge(&state, device_id).await;
    Redirect::to(&back_to(&form, device_id, group)).into_response()
}

/// One phone's setting for one blocklist: `setting` = `default` (no override: the list as it is
/// for everyone), `on` or `off`. An older page's form (`clear`, else the `enabled` checkbox) still
/// works. Anything else is a 400; an unknown phone or list a 404. Nudges the phone; back to `back`
/// ([back_to]).
pub async fn set_device_blocklist_override(
    State(state): State<AppState>,
    Path((device_id, blocklist_id)): Path<(i64, i64)>,
    Form(form): Form<HashMap<String, String>>,
) -> Response {
    let setting = match form.get("setting").map(String::as_str) {
        Some("default") => None,
        Some("on") => Some(true),
        Some("off") => Some(false),
        Some(_) => return (StatusCode::BAD_REQUEST, "Unknown setting").into_response(),
        None if form.contains_key("clear") => None,
        None => Some(form.contains_key("enabled")),
    };
    let result = match setting {
        None => {
            // A DELETE that matches nothing is no foreign-key error: check both first, so an
            // unknown phone or list is a 404 here too (qa-31-s5-code L4).
            let known: Result<bool, sqlx::Error> = sqlx::query_scalar(
                "SELECT EXISTS(SELECT 1 FROM devices WHERE id = ?) \
                 AND EXISTS(SELECT 1 FROM dns_blocklists WHERE id = ?)",
            )
            .bind(device_id)
            .bind(blocklist_id)
            .fetch_one(&state.db)
            .await;
            match known {
                Ok(true) => {}
                Ok(false) => {
                    return (StatusCode::NOT_FOUND, "Phone or list not found").into_response();
                }
                Err(err) => return write_failed(err),
            }
            sqlx::query(
                "DELETE FROM device_blocklist_overrides WHERE device_id = ? AND blocklist_id = ?",
            )
            .bind(device_id)
            .bind(blocklist_id)
            .execute(&state.db)
            .await
        }
        Some(enabled) => {
            sqlx::query(
                "INSERT INTO device_blocklist_overrides (device_id, blocklist_id, enabled, updated_at) \
                 VALUES (?, ?, ?, datetime('now')) \
                 ON CONFLICT (device_id, blocklist_id) DO UPDATE SET enabled = excluded.enabled, updated_at = excluded.updated_at",
            )
            .bind(device_id)
            .bind(blocklist_id)
            .bind(enabled)
            .execute(&state.db)
            .await
        }
    };
    if let Err(err) = result {
        return write_failed(err);
    }
    nudge(&state, Some(device_id)).await;
    Redirect::to(&back_to(&form, Some(device_id), "phones")).into_response()
}

/// A phone on the Blocked Activity page: its name, and with its log on how much it blocked today.
struct LogPhone {
    id: i64,
    name: String,
    /// "Off", "None today", "12 today".
    value: String,
}

#[derive(Template)]
#[template(path = "dns_log.html")]
struct DnsLogTemplate {
    title: String,
    phones: Vec<LogPhone>,
    retention_days: i64,
}

/// Blocked Activity (`/dns/log`): the phones, each with its log off or today's count (since local
/// midnight in the Phone Setup zone, as the Settings row counts), linking to the log on its Web
/// Filter page (design 31 steps 5 and 14). `?device=X` redirects there (L5). A DB error is a 500.
pub async fn show_dns_log(
    State(state): State<AppState>,
    Query(params): Query<HashMap<String, String>>,
) -> Response {
    if let Some(id) = old_device_param(&params) {
        return Redirect::to(&format!("/devices/{id}/filter#log")).into_response();
    }
    let zone = crate::display_time::Zone::load(&state.db).await;
    let since = crate::handlers::settings_hub::utc_start_of_day(zone.local(chrono::Utc::now()));
    let phones = sqlx::query_as::<_, (i64, String, Option<bool>, i64)>(
        "SELECT d.id, d.name, p.dns_log_enabled, \
           (SELECT COUNT(*) FROM device_dns_events e WHERE e.device_id = d.id \
              AND e.received_at >= ?) \
         FROM devices d LEFT JOIN device_policy p ON p.device_id = d.id ORDER BY d.name, d.id",
    )
    .bind(&since)
    .fetch_all(&state.db)
    .await;
    let phones = match phones {
        Ok(rows) => rows
            .into_iter()
            .map(|(id, name, on, today)| LogPhone {
                id,
                name,
                value: match (on == Some(true), today) {
                    (false, _) => "Off".to_string(),
                    (true, 0) => "None today".to_string(),
                    (true, n) => format!("{n} today"),
                },
            })
            .collect(),
        Err(err) => {
            tracing::error!(%err, "couldn't list the phones for Blocked Activity");
            return (
                StatusCode::INTERNAL_SERVER_ERROR,
                "Couldn't read the phones - check the server log.",
            )
                .into_response();
        }
    };
    Html(
        DnsLogTemplate {
            title: "Blocked Activity".to_string(),
            phones,
            retention_days: crate::retention::DNS_LOG_RETENTION_DAYS,
        }
        .render()
        .unwrap(),
    )
    .into_response()
}

/// The per-phone blocked-domain log switch (cleanup 2026-10-06), on the phone's Web Filter page
/// (design 31 step 5): off by default; while on, the phone reports blocked domains and they are
/// kept `retention::DNS_LOG_RETENTION_DAYS`. Switching it off deletes the phone's log at once.
/// One auto-saving checkbox (missing = off); 404 for an unknown device; nudges the phone. Back to
/// its group, `/devices/{id}/filter#log` (§5.1.4).
pub async fn set_dns_log(
    State(state): State<AppState>,
    Extension(CurrentAdmin(admin)): Extension<CurrentAdmin>,
    Path(device_id): Path<i64>,
    Form(form): Form<HashMap<String, String>>,
) -> axum::response::Response {
    let enabled = form.contains_key("dns_log_enabled");
    let result = async {
        let mut tx = state.db.begin().await?;
        let updated = sqlx::query(
            "UPDATE device_policy SET dns_log_enabled = ?, updated_at = datetime('now') \
             WHERE device_id = ?",
        )
        .bind(enabled)
        .bind(device_id)
        .execute(&mut *tx)
        .await?
        .rows_affected();
        if updated > 0 && !enabled {
            sqlx::query("DELETE FROM device_dns_events WHERE device_id = ?")
                .bind(device_id)
                .execute(&mut *tx)
                .await?;
        }
        tx.commit().await?;
        Ok::<u64, sqlx::Error>(updated)
    }
    .await;
    match result {
        Ok(0) => (StatusCode::NOT_FOUND, "Device not found").into_response(),
        Ok(_) => {
            crate::security::record_security_event(
                &state.db,
                "dns_log_changed",
                Some(&admin.username),
                None,
                Some(&format!(
                    "device {device_id}: {}",
                    if enabled { "on" } else { "off" }
                )),
            )
            .await;
            let _ = state.command_notify.send(device_id);
            Redirect::to(&format!("/devices/{device_id}/filter#log")).into_response()
        }
        Err(err) => {
            tracing::error!(device_id, %err, "couldn't save the blocked-domain log switch");
            (
                StatusCode::INTERNAL_SERVER_ERROR,
                "Couldn't save - nothing was changed.",
            )
                .into_response()
        }
    }
}

/// Removes a blocked or allowed domain (one tap: it can be added again, §5.6). The phone it
/// belonged to and its list come from the deleted row (the path's id picks the row), never from
/// the form; back to `back` ([back_to]), on `/dns` to the row's group. A row that is already gone
/// (a double tap, a second tab) changes nothing and returns where `back`, the form's `device_id`
/// and `list_type` say (qa-31-s5-code M1): with nothing written, they only build the redirect.
pub async fn delete_custom_domain(
    State(state): State<AppState>,
    Path(id): Path<i64>,
    Form(form): Form<HashMap<String, String>>,
) -> Response {
    let deleted: Result<Option<(Option<i64>, String)>, sqlx::Error> = sqlx::query_as(
        "DELETE FROM dns_custom_domains WHERE id = ? RETURNING device_id, list_type",
    )
    .bind(id)
    .fetch_optional(&state.db)
    .await;
    let (device_id, list_type) = match deleted {
        Ok(Some(row)) => row,
        Ok(None) => {
            let page = form.get("device_id").and_then(|s| s.parse::<i64>().ok());
            let group = domain_group(form.get("list_type").map_or("", String::as_str));
            return Redirect::to(&back_to(&form, page, group)).into_response();
        }
        Err(err) => return write_failed(err),
    };
    dns_engine::compile_blocklist(&state, &state.dns_compiled).await;
    nudge(&state, device_id).await;
    Redirect::to(&back_to(&form, device_id, domain_group(&list_type))).into_response()
}

/// Hourly refresh of every blocklist feed's remote content - same shape as
/// `tracked_apps::run_scheduled_tracked_app_sync`. Settings/domain-list
/// *changes* already trigger an immediate recompile themselves; this loop is
/// only for picking up upstream blocklist content changes over time.
pub async fn run_blocklist_refresh(state: AppState) {
    loop {
        // A feed whose download failed is tried again after 5 minutes, not an hour (security
        // review M6: until it loads, phones that use it keep their own list).
        let failing = state
            .dns_compiled
            .read()
            .await
            .lists
            .iter()
            .any(|list| list.last_error.is_some());
        let wait = if failing { 5 * 60 } else { 60 * 60 };
        tokio::time::sleep(Duration::from_secs(wait)).await;
        dns_engine::compile_blocklist(&state, &state.dns_compiled).await;
    }
}
