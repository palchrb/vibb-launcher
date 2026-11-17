//! App push in the PWA (design 108 §3.10, Vibb as the UnifiedPush distributor):
//!
//! - **Settings › App push** (`/settings/push`, [show_settings], [save_settings]): the phones' public address, its
//!   check's reason when refused, "Saving turns on app push for every phone" above Save, the prefill from Phone Setup
//!   (never saved by itself, L7) and "Last seen from outside" (the newest discovery and the newest accepted push, times
//!   only: the server never probes itself).
//! - **Device › Apps' group** ([group], `partials/app_push_group.html`, `#app-push`): the phone's switch (on by
//!   default; turning it off goes through its sheet, A3), one row per app with registrations (accounts, last delivery,
//!   "no delivery in 7 days", Stop through its sheet), the stopped apps with Allow Again, and the "not connected"
//!   warning for an allowed app known to use UP ([crate::unified_push::KNOWN_UP_APPS]). Element X keeps its Play
//!   build (the user, 2026-11-16): its push provider is switched to Vibb One once per account in Element X itself, and
//!   Google stays the manual way back, so the words say where.
//!
//! Every save is a form that returns to its group (design 103: in place, never at the top).

use std::collections::HashMap;

use askama::Template;
use axum::Form;
use axum::extract::{Path, Query, State};
use axum::http::StatusCode;
use axum::response::{Html, IntoResponse, Redirect, Response};
use chrono::{DateTime, Utc};

use crate::AppState;
use crate::display_time::Zone;
use crate::handlers::device_pages::{AppsFrom, apps_url};
use crate::i18n::{Lang, Words, t, t_count, t_params};
use crate::unified_push as up;

// ---- Settings › App push ------------------------------------------------------------------------------------------

#[derive(Template)]
#[template(path = "settings_push.html")]
struct SettingsPushTemplate {
    lang: Lang,
    title: String,
    /// The field's value: what was typed (a refusal), the saved origin, or the prefill.
    value: String,
    /// The value is Phone Setup's address, not saved yet.
    prefilled: bool,
    /// A refused save's reason (its key).
    error: Option<&'static str>,
    saved: bool,
    /// The origin is set: the clear note shows.
    set: bool,
    /// The previous origin still accepted, worded ("… until 12 Dec").
    previous: Option<String>,
    /// "Last seen from outside": the newest discovery and push, worded; `None` = not yet.
    discovery: Option<String>,
    pushed: Option<String>,
    /// No `DEVICE_BIND_ADDR`: the push routes aren't served at all.
    no_listener: bool,
}
crate::i18n::words!(SettingsPushTemplate);

async fn render_settings(
    state: &AppState,
    lang: Lang,
    status: StatusCode,
    typed: Option<(String, &'static str)>,
    saved: bool,
) -> Response {
    let settings = match up::settings(&state.db).await {
        Ok(settings) => settings,
        Err(err) => return read_failed(lang, err),
    };
    let pushed: Option<String> =
        match sqlx::query_scalar("SELECT MAX(last_push_at) FROM up_registrations")
            .fetch_one(&state.db)
            .await
        {
            Ok(pushed) => pushed,
            Err(err) => return read_failed(lang, err),
        };
    let setup: Option<String> =
        match sqlx::query_scalar("SELECT server_url FROM provisioning_settings WHERE id = 1")
            .fetch_optional(&state.db)
            .await
        {
            Ok(url) => url,
            Err(err) => return read_failed(lang, err),
        };
    let zone = Zone::load(&state.db, lang).await;
    let now = Utc::now();
    let when = |text: Option<&str>| {
        text.and_then(crate::display_time::parse_db_utc)
            .map(|at| zone.when(at, now))
    };
    let prefill = settings
        .public_origin
        .is_none()
        .then(|| setup.as_deref().and_then(up::prefill))
        .flatten();
    let (value, error, prefilled) = match typed {
        Some((typed, why)) => (typed, Some(why), false),
        None => match (&settings.public_origin, prefill) {
            (Some(origin), _) => (origin.clone(), None, false),
            (None, Some(prefill)) => (prefill, None, true),
            (None, None) => (String::new(), None, false),
        },
    };
    let previous = match (&settings.previous_origin, &settings.origin_changed_at) {
        (Some(previous), Some(changed)) => crate::display_time::parse_db_utc(changed)
            .map(|at| at + chrono::Duration::days(up::PREVIOUS_ORIGIN_DAYS))
            .filter(|until| *until > now)
            .map(|until| {
                t_params(
                    lang,
                    "app_push.settings.previous",
                    &[("origin", previous), ("when", &zone.when(until, now))],
                )
            }),
        _ => None,
    };
    let page = SettingsPushTemplate {
        lang,
        title: t(lang, "app_push.settings.title").to_string(),
        value,
        prefilled,
        error,
        saved,
        set: settings.public_origin.is_some(),
        previous,
        discovery: when(settings.last_discovery_at.as_deref()),
        pushed: when(pushed.as_deref()),
        no_listener: state.net.device_bind_addr.is_none(),
    };
    (status, Html(page.render().unwrap())).into_response()
}

fn read_failed(lang: Lang, err: sqlx::Error) -> Response {
    tracing::error!(%err, "app push: couldn't read the page");
    crate::i18n::error(
        lang,
        StatusCode::INTERNAL_SERVER_ERROR,
        "app_push.error.read",
    )
}

/// `GET /settings/push`.
pub async fn show_settings(
    State(state): State<AppState>,
    lang: Lang,
    Query(query): Query<HashMap<String, String>>,
) -> Response {
    render_settings(
        &state,
        lang,
        StatusCode::OK,
        None,
        query.contains_key("saved"),
    )
    .await
}

/// `POST /settings/push`: an empty field clears the origin (app push off for every phone, every registration
/// tombstoned); a valid one is saved (every phone with its switch on gets app push; a change keeps the old origin
/// 30 days); a refused one writes nothing and renders the page (400) with what was typed and why. Saved: back to the
/// flash under Save (`#saved`).
pub async fn save_settings(
    State(state): State<AppState>,
    lang: Lang,
    Form(form): Form<HashMap<String, String>>,
) -> Response {
    let typed = form.get("public_origin").map(|s| s.trim()).unwrap_or("");
    let origin = if typed.is_empty() {
        None
    } else {
        match up::check_origin(typed) {
            Some(origin) => Some(origin),
            None => {
                let why = "app_push.settings.refused";
                let shown: String = typed.chars().take(200).collect();
                return render_settings(
                    &state,
                    lang,
                    StatusCode::BAD_REQUEST,
                    Some((shown, why)),
                    false,
                )
                .await;
            }
        }
    };
    match up::save_origin(&state, origin.as_deref()).await {
        Ok(_) => Redirect::to("/settings/push?saved=1#saved").into_response(),
        Err(err) => {
            tracing::error!(%err, "app push: couldn't save the address");
            crate::i18n::error(
                lang,
                StatusCode::INTERNAL_SERVER_ERROR,
                "error.save_failed_log",
            )
        }
    }
}

/// The Settings hub row's value: "On" while an origin is set, else "Off"; `None` on a read error.
pub async fn hub_value(db: &sqlx::SqlitePool, lang: Lang) -> Option<String> {
    let settings = up::settings(db).await.ok()?;
    Some(
        t(
            lang,
            if settings.public_origin.is_some() {
                "app_push.hub.on"
            } else {
                "app_push.hub.off"
            },
        )
        .to_string(),
    )
}

// ---- Device › Apps' group -----------------------------------------------------------------------------------------

/// An app as Device › Apps shows it: its name, letter and logo, and whether it is on the allowed list.
pub struct AppName {
    pub label: String,
    pub letter: String,
    pub logo: Option<String>,
    pub allowed: bool,
}

/// One app with registrations on this phone.
pub struct AppPushRow {
    pub package: String,
    pub label: String,
    pub letter: String,
    pub logo: Option<String>,
    /// "2 accounts · last delivered 14:02".
    pub line: String,
    /// "No delivery in 7 days".
    pub warning: Option<&'static str>,
    /// Stop's sheet, with the app's name.
    pub stop_question: String,
}

pub struct StoppedRow {
    pub package: String,
    pub label: String,
    pub letter: String,
    pub logo: Option<String>,
}

/// Device › Apps' "App push" group (`#app-push`).
pub struct AppPushGroup {
    /// The phone's own switch.
    pub on: bool,
    /// A public origin is set (otherwise the switch's row says where to set it).
    pub origin_set: bool,
    /// The phone reported without `unified_push_v1`: "Needs a newer Vibb One".
    pub needs_update: bool,
    pub apps: Vec<AppPushRow>,
    pub stopped: Vec<StoppedRow>,
    /// "Element X isn't using Vibb for notifications…", worded.
    pub missing: Vec<String>,
}

/// The group for one phone. `names` holds every app the page lists (by package); `capability` is whether the phone's
/// last report claims `unified_push_v1` (`None` before its first report).
pub(crate) async fn group(
    state: &AppState,
    lang: Lang,
    device_id: i64,
    names: &HashMap<String, AppName>,
    capability: Option<bool>,
    zone: &Zone,
) -> Result<AppPushGroup, sqlx::Error> {
    let on = up::switch(&state.db, device_id).await?;
    let origin_set = up::settings(&state.db).await?.public_origin.is_some();
    let rows: Vec<(String, i64, Option<String>, String)> = sqlx::query_as(
        "SELECT package, COUNT(*), MAX(last_delivered_at), MIN(created_at) FROM up_registrations \
         WHERE device_id = ? GROUP BY package ORDER BY package",
    )
    .bind(device_id)
    .fetch_all(&state.db)
    .await?;
    let stopped = up::stopped(&state.db, device_id).await?;
    let now = Utc::now();
    let week_ago = now - chrono::Duration::days(7);
    let name_of = |package: &str| -> (String, String, Option<String>) {
        match names.get(package) {
            Some(name) => (name.label.clone(), name.letter.clone(), name.logo.clone()),
            None => {
                let label = up::KNOWN_UP_APPS
                    .iter()
                    .find(|(p, _)| *p == package)
                    .map_or(package, |(_, name)| name)
                    .to_string();
                let letter = crate::handlers::devices::initial(&label);
                (label, letter, None)
            }
        }
    };
    let apps = rows
        .iter()
        .map(|(package, count, delivered, created)| {
            let (label, letter, logo) = name_of(package);
            let delivered: Option<DateTime<Utc>> = delivered
                .as_deref()
                .and_then(crate::display_time::parse_db_utc);
            let created = crate::display_time::parse_db_utc(created);
            let accounts = t_count(lang, "app_push.accounts", *count, &[]);
            let line = match delivered {
                Some(at) => format!(
                    "{accounts} · {}",
                    t_params(
                        lang,
                        "app_push.last_delivered",
                        &[("when", &zone.when(at, now))]
                    )
                ),
                None => format!("{accounts} · {}", t(lang, "app_push.never_delivered")),
            };
            let quiet = match delivered {
                Some(at) => at < week_ago,
                None => created.is_some_and(|at| at < week_ago),
            };
            AppPushRow {
                package: package.clone(),
                stop_question: t_params(lang, "app_push.stop_question", &[("app", &label)]),
                label,
                letter,
                logo,
                line,
                warning: quiet.then_some("app_push.no_delivery"),
            }
        })
        .collect();
    let missing = if on && origin_set {
        up::KNOWN_UP_APPS
            .iter()
            .filter(|(package, _)| {
                names.get(*package).is_some_and(|name| name.allowed)
                    && !rows.iter().any(|(p, ..)| p == package)
                    && !stopped.iter().any(|p| p == package)
            })
            .map(|(_, name)| t_params(lang, "app_push.missing", &[("app", name)]))
            .collect()
    } else {
        Vec::new()
    };
    let stopped = stopped
        .into_iter()
        .map(|package| {
            let (label, letter, logo) = name_of(&package);
            StoppedRow {
                package,
                label,
                letter,
                logo,
            }
        })
        .collect();
    Ok(AppPushGroup {
        on,
        origin_set,
        needs_update: capability == Some(false),
        apps,
        stopped,
        missing,
    })
}

fn back(id: i64, form: &HashMap<String, String>, fragment: &str) -> Response {
    let from = form.get("from").and_then(|f| AppsFrom::parse(f));
    Redirect::to(&apps_url(id, "", from, fragment)).into_response()
}

fn save_failed(lang: Lang, id: i64, err: sqlx::Error) -> Response {
    tracing::error!(device_id = id, %err, "app push: couldn't save");
    crate::i18n::error(lang, StatusCode::INTERNAL_SERVER_ERROR, "error.save_failed")
}

/// A package from a form: a real package name, else 400.
fn package_of(lang: Lang, form: &HashMap<String, String>) -> Result<String, Box<Response>> {
    form.get("package")
        .filter(|p| crate::handlers::device_api::is_package_name(p))
        .cloned()
        .ok_or_else(|| {
            Box::new(crate::i18n::error(
                lang,
                StatusCode::BAD_REQUEST,
                "error.not_an_app",
            ))
        })
}

/// `POST /devices/{id}/app-push`: the phone's switch (`on` present = on). Off deletes its registrations; both nudge.
/// Back to `#app-push`.
pub async fn save_switch(
    State(state): State<AppState>,
    lang: Lang,
    Path(id): Path<i64>,
    Form(form): Form<HashMap<String, String>>,
) -> Response {
    match up::set_switch(&state, id, form.contains_key("on")).await {
        Ok(true) => back(id, &form, "app-push"),
        Ok(false) => crate::i18n::error(lang, StatusCode::NOT_FOUND, "error.device_not_found"),
        Err(err) => save_failed(lang, id, err),
    }
}

/// `POST /devices/{id}/app-push/stop`: the parent's Stop of one app (`package`). Back to `#app-push`.
pub async fn stop(
    State(state): State<AppState>,
    lang: Lang,
    Path(id): Path<i64>,
    Form(form): Form<HashMap<String, String>>,
) -> Response {
    let package = match package_of(lang, &form) {
        Ok(package) => package,
        Err(refusal) => return *refusal,
    };
    if let Err(response) = known_device(&state, lang, id).await {
        return *response;
    }
    match up::stop(&state, id, &package).await {
        Ok(()) => back(id, &form, "app-push"),
        Err(err) => save_failed(lang, id, err),
    }
}

/// `POST /devices/{id}/app-push/allow`: Allow Again for one stopped app. Back to `#app-push`.
pub async fn allow(
    State(state): State<AppState>,
    lang: Lang,
    Path(id): Path<i64>,
    Form(form): Form<HashMap<String, String>>,
) -> Response {
    let package = match package_of(lang, &form) {
        Ok(package) => package,
        Err(refusal) => return *refusal,
    };
    if let Err(response) = known_device(&state, lang, id).await {
        return *response;
    }
    match up::allow_again(&state, id, &package).await {
        Ok(()) => back(id, &form, "app-push"),
        Err(err) => save_failed(lang, id, err),
    }
}

async fn known_device(state: &AppState, lang: Lang, id: i64) -> Result<(), Box<Response>> {
    match sqlx::query_scalar::<_, i64>("SELECT 1 FROM devices WHERE id = ?")
        .bind(id)
        .fetch_optional(&state.db)
        .await
    {
        Ok(Some(_)) => Ok(()),
        Ok(None) => Err(Box::new(crate::i18n::error(
            lang,
            StatusCode::NOT_FOUND,
            "error.device_not_found",
        ))),
        Err(err) => Err(Box::new(save_failed(lang, id, err))),
    }
}
