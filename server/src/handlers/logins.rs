//! Device › Sign-in (`/devices/{id}/logins`, design 102 §5), linked from the Web Filter page, and the Advanced page's
//! `#wifi-signin` row (revision 5, the user's placement). Server-rendered, no new client JS (Safari 14 is fine).
//!
//! - `#logins-wifi`: Part A's switch (`section=wifi`), the parent code's state with a link to Advanced, today's use and
//!   "Gi én innlogging til nå" (`section=grant`); `#logins-recent`: the last sign-ins (D6).
//! - `#logins-apps`: Part B's switch (`section=apps`), refused while Chrome is ticked under Apps.
//! - `#logins-sites`: the presets (`section=preset`) and the parent's own sites (`section=site`, `action=add|remove`).
//! - Advanced's `#wifi-signin`: "Krev foreldrekode for Wi-Fi-innlogging" (`section=wifi_signin`), answered with a 303
//!   back to its anchor there.
//!
//! One route, `POST /devices/{id}/logins`. Every save answers with a 303 to its group's anchor, so a save never jumps
//! to the top (with JS `scroll-restore.js` keeps the place; without, a `<noscript>` Save lands on the group). An
//! unknown section is a 400 with nothing written, an unknown phone a 404; every save nudges the phone.

use std::collections::HashMap;

use askama::Template;
use axum::Form;
use axum::extract::{Path, Query, State};
use axum::http::StatusCode;
use axum::response::{Html, IntoResponse, Redirect, Response};
use chrono::{DateTime, Utc};

use crate::AppState;
use crate::display_time::Zone;
use crate::handlers::device_pages::{has_capability, load_device, load_latest, load_policy};
use crate::i18n::{Lang, Words, t, t_params};
use crate::logins::{self, BrowserReport, PortalReport};
use crate::models::{Device, DevicePolicy, DeviceStatus};

/// One recent sign-in, as the page shows it.
pub struct RecentRow {
    pub network: String,
    pub when: String,
    /// "Signed in · with the parent code · from the notification".
    pub how: String,
    /// "Sites: a.example, b.example (+3)", empty without any.
    pub sites: String,
    /// "Blocked: x.example", empty without any.
    pub blocked: String,
}

/// A preset's row.
pub struct PresetRow {
    pub key: &'static str,
    pub name: &'static str,
    pub hosts: String,
    pub on: bool,
}

/// One of the parent's own sites.
pub struct SiteRow {
    pub entry: String,
    pub note: Option<String>,
    /// The phone's filter blocks it (§5.2).
    pub blocked: bool,
}

#[derive(Template)]
#[template(path = "device_logins.html")]
struct LoginsTemplate {
    lang: Lang,
    who: crate::handlers::devices::BandAvatar,
    title: String,
    device: Device,
    /// The phone has reported at least once.
    reported: bool,
    portal_capable: bool,
    browser_capable: bool,
    wifi_on: bool,
    require_code: bool,
    /// "I dag uten kode: …", only with a report that has `captive_portal`.
    today: Option<String>,
    granted: bool,
    recent: Vec<RecentRow>,
    apps_on: bool,
    chrome_ticked: bool,
    /// The phone's report lists Chrome (`None`: no report of its apps yet).
    chrome_present: Option<bool>,
    /// The role's state on the phone, while it is on (§5.2's table).
    apps_state: Option<&'static str>,
    presets: Vec<PresetRow>,
    sites: Vec<SiteRow>,
    /// A refused add: the notice's key.
    site_notice: Option<&'static str>,
    /// Presets whose hosts the filter blocks.
    blocked_presets: Vec<String>,
}
crate::i18n::words!(LoginsTemplate);

/// The stored `captive_portal`, from a launcher that claims it.
pub fn portal_report(latest: Option<&DeviceStatus>) -> Option<PortalReport> {
    if !has_capability(latest, logins::PORTAL_CAPABILITY) {
        return None;
    }
    Some(
        latest?
            .captive_portal_json
            .as_deref()
            .and_then(|json| serde_json::from_str(json).ok())
            .unwrap_or_default(),
    )
}

fn browser_report(latest: Option<&DeviceStatus>) -> Option<BrowserReport> {
    latest?
        .login_browser_json
        .as_deref()
        .and_then(|json| serde_json::from_str(json).ok())
}

/// The Web Filter page's row's sub-line: "Wi-Fi: på · Apper: av".
pub fn row_sub(lang: Lang, policy: &DevicePolicy) -> String {
    let word = |on: bool| {
        t(
            lang,
            if on {
                "logins.row.on"
            } else {
                "logins.row.off"
            },
        )
    };
    t_params(
        lang,
        "logins.row.sub",
        &[
            ("wifi", word(policy.captive_portal_enabled)),
            (
                "apps",
                word(policy.login_browser_enabled && !logins::chrome_ticked(policy)),
            ),
        ],
    )
}

/// Advanced's `#wifi-signin` row: the switch and its warning (§3.7).
pub struct CodeCard {
    pub require_code: bool,
    /// "No parent code is set …" or "Needs a newer Vibb One", when one applies.
    pub warning: Option<&'static str>,
}

/// The row from the phone's settings and its last report: the capability's line for a phone that reported without
/// it, else the no-code warning while the phone reports none set.
pub fn code_card(policy: &DevicePolicy, latest: Option<&DeviceStatus>) -> CodeCard {
    let warning = match (latest, portal_report(latest)) {
        (Some(_), None) => Some("logins.code.needs_update"),
        (_, Some(report)) if !report.parent_code_set => Some("logins.code.none_set"),
        _ => None,
    };
    CodeCard {
        require_code: policy.captive_portal_require_code,
        warning,
    }
}

fn recent_rows(
    lang: Lang,
    report: &PortalReport,
    zone: &Zone,
    now: DateTime<Utc>,
) -> Vec<RecentRow> {
    report
        .sessions
        .iter()
        .map(|s| {
            let when = DateTime::from_timestamp_millis(s.opened_ms)
                .map(|at| zone.at(at, now))
                .unwrap_or_default();
            let how = [
                outcome_key(&s.outcome),
                approved_key(&s.approved),
                start_key(&s.start),
            ]
            .iter()
            .map(|key| t(lang, key).to_string())
            .collect::<Vec<_>>()
            .join(" · ");
            let more = s.visited_count - s.visited.len() as i64;
            let sites = if s.visited.is_empty() {
                String::new()
            } else if more > 0 {
                t_params(
                    lang,
                    "logins.recent.sites_more",
                    &[
                        ("sites", &s.visited.join(", ")),
                        ("more", &more.to_string()),
                    ],
                )
            } else {
                t_params(
                    lang,
                    "logins.recent.sites",
                    &[("sites", &s.visited.join(", "))],
                )
            };
            let mut blocked: Vec<String> = s.blocked_frames.clone();
            for host in &s.blocked_hosts {
                if !blocked.contains(host) {
                    blocked.push(host.clone());
                }
            }
            let blocked = if blocked.is_empty() {
                String::new()
            } else {
                t_params(
                    lang,
                    "logins.recent.blocked",
                    &[("hosts", &blocked.join(", "))],
                )
            };
            RecentRow {
                network: s
                    .network
                    .clone()
                    .unwrap_or_else(|| t(lang, "logins.recent.no_name").to_string()),
                when,
                how,
                sites,
                blocked,
            }
        })
        .collect()
}

/// A session's outcome's words (the cleaned report holds only [logins::OUTCOMES]).
fn outcome_key(outcome: &str) -> &'static str {
    match outcome {
        "validated" => "logins.outcome.validated",
        "passes" => "logins.outcome.passes",
        "closed" => "logins.outcome.closed",
        "closed_by_vibb" => "logins.outcome.closed_by_vibb",
        "timeout" => "logins.outcome.timeout",
        "budget" => "logins.outcome.budget",
        _ => "logins.outcome.lost",
    }
}

/// How an opening was approved, in words.
fn approved_key(approved: &str) -> &'static str {
    match approved {
        "code" => "logins.approved.code",
        "grant" => "logins.approved.grant",
        "pause" => "logins.approved.pause",
        _ => "logins.approved.none",
    }
}

/// How a window was started, in words.
fn start_key(start: &str) -> &'static str {
    match start {
        "system" => "logins.start.system",
        "revisit" => "logins.start.revisit",
        _ => "logins.start.vibb",
    }
}

/// The role's state while it is on (§5.2): the capability missing, no report since the save, Chrome missing, or
/// failed; "Ready" once the phone says so.
fn apps_state(latest: Option<&DeviceStatus>, report: Option<&BrowserReport>) -> &'static str {
    if latest.is_some() && !has_capability(latest, logins::BROWSER_CAPABILITY) {
        return "logins.apps.state.needs_update";
    }
    match report.map(|r| r.state.as_str()) {
        Some("on") => "logins.apps.state.on",
        Some("absent") => "logins.apps.state.absent",
        Some("failed") => "logins.apps.state.failed",
        _ => "logins.apps.state.waiting",
    }
}

/// GET `/devices/{id}/logins`. A phone not set up yet has the page too.
pub async fn view(
    State(state): State<AppState>,
    lang: Lang,
    Path(id): Path<i64>,
    Query(query): Query<HashMap<String, String>>,
) -> Response {
    let device = match load_device(&state, id).await {
        Ok(device) => device,
        Err((status, key)) => return crate::i18n::error(lang, status, key),
    };
    let policy = match load_policy(&state, id).await {
        Ok(policy) => policy,
        Err((status, key)) => return crate::i18n::error(lang, status, key),
    };
    let latest = match load_latest(&state, id).await {
        Ok(latest) => latest,
        Err(err) => {
            tracing::error!(device_id = id, %err, "can't read the last report");
            return crate::i18n::error(lang, StatusCode::INTERNAL_SERVER_ERROR, "error.read_phone");
        }
    };
    let own = match logins::own_sites(&state.db, id).await {
        Ok(own) => own,
        Err(err) => {
            tracing::error!(device_id = id, %err, "can't read the sign-in sites");
            return crate::i18n::error(lang, StatusCode::INTERNAL_SERVER_ERROR, "error.read_phone");
        }
    };
    let zone = Zone::load(&state.db, lang).await;
    let now = Utc::now();
    let latest = latest.as_ref();
    let report = portal_report(latest);
    let ticked = logins::presets_of(&policy.login_browser_presets);
    // §5.2: the filter's lists still apply to Chrome - a listed site the filter blocks says so.
    let blocked = if policy.vpn_filter_enabled && policy.login_browser_enabled {
        let own_entries: Vec<String> = own.iter().map(|(e, _)| e.clone()).collect();
        let hosts: Vec<String> = logins::hosts_of(&ticked, &own_entries)
            .iter()
            .map(|entry| entry_host(entry))
            .collect();
        crate::handlers::device_api::filter_blocks(&state, id, &hosts)
            .await
            .unwrap_or_default()
    } else {
        Default::default()
    };
    let presets = logins::PRESETS
        .iter()
        .map(|p| PresetRow {
            key: p.key,
            name: p.name,
            hosts: p.hosts.join(", "),
            on: ticked.contains(&p.key),
        })
        .collect();
    let blocked_presets = logins::PRESETS
        .iter()
        .filter(|p| ticked.contains(&p.key))
        .flat_map(|p| p.hosts.iter())
        .filter(|h| blocked.contains(**h))
        .map(|h| h.to_string())
        .collect();
    let sites = own
        .into_iter()
        .map(|(entry, note)| SiteRow {
            blocked: blocked.contains(&entry_host(&entry)),
            entry,
            note,
        })
        .collect();
    let chrome_present = latest
        .and_then(|s| s.installed_apps_json.as_deref())
        .and_then(|json| serde_json::from_str::<Vec<crate::models::InstalledApp>>(json).ok())
        .map(|apps| apps.iter().any(|a| a.package_name == logins::CHROME));
    let today = report.as_ref().map(|r| {
        let grants = r.today.grant_used.clamp(0, logins::GRANTS_A_DAY);
        t_params(
            lang,
            "logins.wifi.today",
            &[
                ("openings", &r.today.openings.to_string()),
                ("day_openings", &(logins::DAY_OPENINGS + grants).to_string()),
                ("minutes", &(r.today.ms / 60_000).to_string()),
                (
                    "day_minutes",
                    &(logins::DAY_MINUTES + logins::MINUTES * grants).to_string(),
                ),
            ],
        )
    });
    let chrome_ticked = logins::chrome_ticked(&policy);
    let apps_on = policy.login_browser_enabled && !chrome_ticked;
    Html(
        LoginsTemplate {
            lang,
            who: crate::handlers::devices::BandAvatar::of(&device),
            title: format!("{} · {}", t(lang, "logins.page"), device.name),
            reported: latest.is_some(),
            portal_capable: has_capability(latest, logins::PORTAL_CAPABILITY),
            browser_capable: has_capability(latest, logins::BROWSER_CAPABILITY),
            wifi_on: policy.captive_portal_enabled,
            require_code: policy.captive_portal_require_code,
            today,
            granted: query.get("notice").map(String::as_str) == Some("granted"),
            recent: report
                .as_ref()
                .map(|r| recent_rows(lang, r, &zone, now))
                .unwrap_or_default(),
            apps_on,
            chrome_ticked,
            chrome_present,
            apps_state: apps_on.then(|| apps_state(latest, browser_report(latest).as_ref())),
            presets,
            sites,
            site_notice: match query.get("notice").map(String::as_str) {
                Some("entry_invalid") => Some("logins.sites.invalid"),
                Some("entry_full") => Some("logins.sites.full"),
                _ => None,
            },
            blocked_presets,
            device,
        }
        .render()
        .unwrap(),
    )
    .into_response()
}

/// An entry's host (no leading dot, port or path), for the filter's check.
fn entry_host(entry: &str) -> String {
    let entry = entry.trim_start_matches('.');
    let end = entry.find([':', '/']).unwrap_or(entry.len());
    entry[..end].to_string()
}

/// A switch's value: missing = off.
fn on(form: &HashMap<String, String>) -> bool {
    form.contains_key("on")
}

/// POST `/devices/{id}/logins`: one section's save, back to its anchor, the phone nudged.
pub async fn save(
    State(state): State<AppState>,
    lang: Lang,
    Path(id): Path<i64>,
    Form(form): Form<HashMap<String, String>>,
) -> Response {
    if let Err((status, key)) = load_device(&state, id).await {
        return crate::i18n::error(lang, status, key);
    }
    let policy = match load_policy(&state, id).await {
        Ok(policy) => policy,
        Err((status, key)) => return crate::i18n::error(lang, status, key),
    };
    let page = format!("/devices/{id}/logins");
    let result: Result<Response, sqlx::Error> = async {
        let section = form.get("section").map(String::as_str).unwrap_or_default();
        let (sql, anchor) = match section {
            "wifi" => (
                sqlx::query(
                    "UPDATE device_policy SET captive_portal_enabled = ?, updated_at = datetime('now') \
                     WHERE device_id = ?",
                )
                .bind(on(&form)),
                format!("{page}#logins-wifi"),
            ),
            "wifi_signin" => (
                sqlx::query(
                    "UPDATE device_policy SET captive_portal_require_code = ?, updated_at = datetime('now') \
                     WHERE device_id = ?",
                )
                .bind(on(&form)),
                format!("/devices/{id}/advanced#wifi-signin"),
            ),
            "grant" => (
                sqlx::query(
                    "UPDATE device_policy SET captive_portal_grant = captive_portal_grant + 1, \
                     updated_at = datetime('now') WHERE device_id = ?",
                ),
                format!("{page}?notice=granted#logins-wifi"),
            ),
            "apps" => {
                // §5.2: refused while Chrome is ticked under Apps (the switch is disabled then; this is a stale page).
                if on(&form) && logins::chrome_ticked(&policy) {
                    return Ok(crate::settings_log::refused(Redirect::to(&format!(
                        "{page}#logins-apps"
                    ))));
                }
                (
                    sqlx::query(
                        "UPDATE device_policy SET login_browser_enabled = ?, updated_at = datetime('now') \
                         WHERE device_id = ?",
                    )
                    .bind(on(&form)),
                    format!("{page}#logins-apps"),
                )
            }
            "preset" => {
                let Some(preset) = form.get("preset").and_then(|k| logins::preset(k)) else {
                    return Ok(crate::i18n::error(lang, StatusCode::BAD_REQUEST, "error.out_of_date"));
                };
                (
                    sqlx::query(
                        "UPDATE device_policy SET login_browser_presets = ?, updated_at = datetime('now') \
                         WHERE device_id = ?",
                    )
                    .bind(logins::presets_with(
                        &policy.login_browser_presets,
                        preset.key,
                        on(&form),
                    )),
                    format!("{page}#logins-sites"),
                )
            }
            "site" => return save_site(&state, id, &form, &page).await,
            _ => {
                return Ok(crate::i18n::error(lang, StatusCode::BAD_REQUEST, "error.out_of_date"));
            }
        };
        let done = sql.bind(id).execute(&state.db).await?;
        if done.rows_affected() == 0 {
            return Ok(crate::i18n::error(lang, StatusCode::NOT_FOUND, "error.device_not_found"));
        }
        let _ = state.command_notify.send(id);
        Ok(Redirect::to(&anchor).into_response())
    }
    .await;
    result.unwrap_or_else(|err| {
        tracing::error!(device_id = id, %err, "couldn't save the sign-in settings");
        crate::i18n::error(
            lang,
            StatusCode::INTERNAL_SERVER_ERROR,
            "error.save_failed_log",
        )
    })
}

/// `section=site`: add an own entry (with its note) or remove one. A refused entry goes back with its reason.
async fn save_site(
    state: &AppState,
    id: i64,
    form: &HashMap<String, String>,
    page: &str,
) -> Result<Response, sqlx::Error> {
    let back = format!("{page}#logins-sites");
    match form.get("action").map(String::as_str) {
        Some("remove") => {
            let entry = form.get("entry").map(String::as_str).unwrap_or_default();
            sqlx::query("DELETE FROM login_role_sites WHERE device_id = ? AND entry = ?")
                .bind(id)
                .bind(entry)
                .execute(&state.db)
                .await?;
        }
        _ => {
            let Some(entry) = form.get("entry").and_then(|e| logins::valid_entry(e)) else {
                return Ok(crate::settings_log::refused(Redirect::to(&format!(
                    "{page}?notice=entry_invalid#logins-sites"
                ))));
            };
            let note: Option<String> = form
                .get("note")
                .map(|n| n.trim().chars().take(logins::MAX_NOTE).collect::<String>())
                .filter(|n| !n.is_empty());
            let mut tx = state.db.begin().await?;
            let count: i64 =
                sqlx::query_scalar("SELECT COUNT(*) FROM login_role_sites WHERE device_id = ?")
                    .bind(id)
                    .fetch_one(&mut *tx)
                    .await?;
            let known: bool = sqlx::query_scalar(
                "SELECT EXISTS (SELECT 1 FROM login_role_sites WHERE device_id = ? AND entry = ?)",
            )
            .bind(id)
            .bind(&entry)
            .fetch_one(&mut *tx)
            .await?;
            if !known && count >= logins::MAX_OWN_ENTRIES {
                return Ok(crate::settings_log::refused(Redirect::to(&format!(
                    "{page}?notice=entry_full#logins-sites"
                ))));
            }
            // The same entry again keeps its place and takes the new note.
            sqlx::query(
                "INSERT INTO login_role_sites (device_id, entry, note, added_ms) VALUES (?, ?, ?, ?) \
                 ON CONFLICT (device_id, entry) DO UPDATE SET note = excluded.note",
            )
            .bind(id)
            .bind(&entry)
            .bind(&note)
            .bind(Utc::now().timestamp_millis())
            .execute(&mut *tx)
            .await?;
            tx.commit().await?;
        }
    }
    let _ = state.command_notify.send(id);
    Ok(Redirect::to(&back).into_response())
}
