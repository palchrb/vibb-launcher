//! App push in the PWA (design 108 §3.10, Vibb as the UnifiedPush distributor):
//!
//! - **Settings › App push** (`/settings/push`, [show_settings], [save_settings]): the phones' public address, its
//!   check's reason when refused, "Saving turns app push on for every phone with its switch on" above Save while no
//!   address is saved, the prefill from Phone Setup (never saved by itself, L7), and one "Delivery" group (designs 112,
//!   113): "Last seen from outside" (the later of the newest discovery and the newest accepted push, a time only: the
//!   server never probes itself) and "Usually delivered in".
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
    /// The origin is set: the save note ("Saving turns app push on …") shows only while it isn't.
    set: bool,
    /// The replaced origins still accepted, each worded ("… until 12 Dec"; R1-SR L7: every one within its 30 days).
    previous: Vec<String>,
    /// "Last seen from outside": the later of the newest discovery and the newest push, worded; `None` = not yet.
    seen: Option<String>,
    /// Nothing seen yet although a registration is over an hour old: "Not yet" is orange (design 113).
    seen_late: bool,
    /// No `DEVICE_BIND_ADDR`: the push routes aren't served at all.
    no_listener: bool,
    /// "Usually delivered in" (design 108c §7.4, design 112): `None` with no samples.
    delivery: Option<DeliveryRows>,
}

/// The delivery row, worded (design 112): the median, and under it the 95th percentile (from 20 pushes) and the
/// count, the cap note appended when the count was cut.
pub struct DeliveryRows {
    pub usually: String,
    pub sub: String,
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
    // Design 112: either one proves the address is reached, so the later of the two.
    let seen_at = [settings.last_discovery_at.as_deref(), pushed.as_deref()]
        .into_iter()
        .flatten()
        .filter_map(crate::display_time::parse_db_utc)
        .max();
    // Design 113: "Not yet" only speaks up once an app has had an hour to reach the address.
    let seen_late = match seen_at {
        Some(_) => false,
        None => match sqlx::query_scalar::<_, i64>(
            "SELECT 1 FROM up_registrations WHERE created_at < datetime('now', '-1 hour') LIMIT 1",
        )
        .fetch_optional(&state.db)
        .await
        {
            Ok(row) => row.is_some(),
            Err(err) => return read_failed(lang, err),
        },
    };
    let setup = setup_address(setup, &state.config);
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
    let previous = match up::previous_origins(&state.db).await {
        Ok(previous) => previous
            .into_iter()
            .filter_map(|(origin, until)| {
                let until = crate::display_time::parse_db_utc(&until)?;
                Some(t_params(
                    lang,
                    "app_push.settings.previous",
                    &[("origin", &origin), ("when", &zone.when(until, now))],
                ))
            })
            .collect(),
        Err(err) => return read_failed(lang, err),
    };
    let now_ms = now.timestamp_millis();
    let delivery = match async {
        let samples = up::store::delivery_samples(&state.db, None, now_ms).await?;
        let capped = !samples.is_empty() && up::store::delivery_capped(&state.db, now_ms).await?;
        Ok::<_, sqlx::Error>((samples, capped))
    }
    .await
    {
        Ok((samples, capped)) => up::health::delivery_figure(&samples, capped).map(|figure| {
            let count = figure.count.to_string();
            let sub = match figure.p95_ms {
                Some(p95) => t_params(
                    lang,
                    "app_push.delivery.sub_p95",
                    &[("p95", &duration_words(lang, p95)), ("count", &count)],
                ),
                None => t_count(lang, "app_push.delivery.sub", figure.count as i64, &[]),
            };
            DeliveryRows {
                usually: duration_words(lang, figure.median_ms),
                sub: if figure.capped {
                    format!("{sub} {}", t(lang, "app_push.delivery.cap_note"))
                } else {
                    sub
                },
            }
        }),
        Err(err) => return read_failed(lang, err),
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
        seen: seen_at.map(|at| zone.when(at, now)),
        seen_late,
        no_listener: state.net.device_bind_addr.is_none(),
        delivery,
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

/// A delivery time in words (design 108c §7.3), each unit floored so 59.6 s reads "59 s": under a second, "{n} s", "{n} min",
/// then "{h} h {m} min" (nb "t").
pub fn duration_words(lang: Lang, ms: i64) -> String {
    let ms = ms.max(0);
    let n = |v: i64| v.to_string();
    if ms < 1_000 {
        t(lang, "app_push.delivery.under_second").to_string()
    } else if ms < 60_000 {
        t_params(lang, "app_push.delivery.seconds", &[("n", &n(ms / 1_000))])
    } else if ms < 3_600_000 {
        t_params(lang, "app_push.delivery.minutes", &[("n", &n(ms / 60_000))])
    } else {
        t_params(
            lang,
            "app_push.delivery.hours",
            &[
                ("h", &n(ms / 3_600_000)),
                ("m", &n(ms % 3_600_000 / 60_000)),
            ],
        )
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
    /// "About 400 pushes a day" and its detail for the row's ⓘ (R1-SR L2, [noisy_per_day]).
    pub noisy: Option<(String, String)>,
    /// The app's pushes come through an outside gateway (108b §3.4): "Goes via an outside service" and its detail,
    /// worded with the app's name.
    pub outside: Option<(String, String)>,
    /// A push was refused within 7 days (108b §4.7): "Pushes refused, last {when}" and its cause (or the fix, once
    /// Attention raised it).
    pub refused: Option<(String, String)>,
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
    /// A public origin is set (otherwise the switch's row says it is missing).
    pub origin_set: bool,
    /// The phone reported without `unified_push_v1`: "Vibb One needs an update".
    pub needs_update: bool,
    pub apps: Vec<AppPushRow>,
    pub stopped: Vec<StoppedRow>,
    /// "Element X isn't using Vibb for notifications" and the fix behind its ⓘ, worded.
    pub missing: Vec<(String, String)>,
    /// Design 108c §2: shown exactly when Attention's "pushes waiting" row is raised; its line and its ⓘ's text.
    pub waiting: Option<(String, String)>,
    /// Design 108c §7.4: "Usually delivered in …", only when the phone has samples.
    pub delivery: Option<String>,
}

/// An app is "noisy" from this many pushes a day (R1-SR L2): its average since its oldest registration on the phone,
/// counted over at least a day. Each push wakes the phone and the app; a busy family group chat stays well below it.
pub const NOISY_PER_DAY: i64 = 300;

/// The app's pushes a day when that is at least [NOISY_PER_DAY], else `None`.
pub(crate) fn noisy_per_day(
    pushes: i64,
    since: Option<DateTime<Utc>>,
    now: DateTime<Utc>,
) -> Option<i64> {
    let days = since.map_or(1.0, |at| {
        ((now - at).num_seconds() as f64 / 86_400.0).max(1.0)
    });
    let per_day = (pushes as f64 / days).round() as i64;
    (per_day >= NOISY_PER_DAY).then_some(per_day)
}

/// One app's row of the group's query: package, registrations, last delivery, oldest registration, pushes, marked
/// as outside, last refusal within 7 days as `<stamp>|<why>`.
type AppRow = (
    String,
    i64,
    Option<String>,
    String,
    i64,
    bool,
    Option<String>,
);

/// The group for one phone. `names` holds every app the page lists (by package); `capability` is whether the phone's
/// last report claims `unified_push_v1` (`None` before its first report).
pub(crate) async fn group(
    state: &AppState,
    lang: Lang,
    device_id: i64,
    names: &HashMap<String, AppName>,
    capability: Option<bool>,
    zone: &Zone,
    reported_at: Option<&str>,
) -> Result<AppPushGroup, sqlx::Error> {
    let on = up::switch(&state.db, device_id).await?;
    let origin_set = up::settings(&state.db).await?.public_origin.is_some();
    let rows: Vec<AppRow> = sqlx::query_as(
        "SELECT package, COUNT(*), MAX(last_delivered_at), MIN(created_at), SUM(pushes), \
           COALESCE(MAX(outside_gateway_at > datetime('now', '-7 days') \
               AND (gateway_at IS NULL OR gateway_at < outside_gateway_at)), 0), \
           MAX(CASE WHEN vapid_refused_at > datetime('now', '-7 days') THEN vapid_refused_at || '|' || COALESCE(vapid_refused_why, 'unsigned') END) \
         FROM up_registrations WHERE device_id = ? GROUP BY package ORDER BY package",
    )
    .bind(device_id)
    .fetch_all(&state.db)
    .await?;
    let stopped = up::stopped(&state.db, device_id).await?;
    let now = Utc::now();
    // The same function Attention reads, so the row and the page agree (design 108c §3).
    let problems = up::health::attention(state, device_id, reported_at, now).await?;
    let waiting = problems.waiting_since.map(|since| {
        (
            t_params(
                lang,
                "app_push.waiting",
                &[("when", &zone.when(since, now))],
            ),
            t(lang, "app_push.waiting_info").to_string(),
        )
    });
    // Nothing under an Off switch (its registrations are gone; the samples may linger up to a day).
    let samples = if on {
        up::store::delivery_samples(&state.db, Some(device_id), now.timestamp_millis()).await?
    } else {
        Vec::new()
    };
    let capped = samples.len() as i64 >= up::store::DELIVERY_CAP;
    let delivery = up::health::delivery_figure(&samples, capped).map(|figure| {
        // The 95th percentile lives on Settings › App push (design 113).
        let line = t_count(
            lang,
            "app_push.delivery_line",
            figure.count as i64,
            &[("median", &duration_words(lang, figure.median_ms))],
        );
        if figure.capped {
            format!("{line} {}", t(lang, "app_push.delivery.cap_note"))
        } else {
            line
        }
    });
    let week_ago = now - chrono::Duration::days(7);
    let name_of = |package: &str| -> (String, String, Option<String>) {
        match names.get(package) {
            Some(name) => (name.label.clone(), name.letter.clone(), name.logo.clone()),
            None => {
                let label = up::KNOWN_UP_APPS
                    .iter()
                    .find(|(p, ..)| *p == package)
                    .map_or(package, |(_, name, _)| name)
                    .to_string();
                let letter = crate::handlers::devices::initial(&label);
                (label, letter, None)
            }
        }
    };
    let apps = rows
        .iter()
        .map(
            |(package, count, delivered, created, pushes, outside, refused)| {
                let (label, letter, logo) = name_of(package);
                let name = label.clone();
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
                let per_day = noisy_per_day(*pushes, created, now);
                AppPushRow {
                    package: package.clone(),
                    stop_question: t_params(lang, "app_push.stop_question", &[("app", &label)]),
                    label,
                    letter,
                    logo,
                    line,
                    warning: quiet.then_some("app_push.no_delivery"),
                    noisy: per_day.map(|n| {
                        (
                            t_params(lang, "app_push.noisy", &[("count", &n.to_string())]),
                            t(lang, "app_push.noisy_info").to_string(),
                        )
                    }),
                    outside: (*outside).then(|| {
                        (
                            t(lang, "app_push.outside").to_string(),
                            t_params(lang, "app_push.outside_info", &[("app", &name)]),
                        )
                    }),
                    // Every refusal's visible line is "Pushes refused, last {when}"; the cause sits behind the
                    // row's ⓘ (design 113).
                    refused: problems
                        .refused
                        .iter()
                        .find(|(p, _)| p == package)
                        .and_then(|(_, stamp)| {
                            // Raised for this app (108c §2): worded from the raised registration's own stamp, which
                            // is an `unsigned` one, so the fix can't land on a `time` line.
                            let at = crate::display_time::parse_db_utc(stamp)?;
                            Some((
                                at,
                                t_params(lang, "app_push.refused_fix", &[("app", &name)]),
                            ))
                        })
                        .or_else(|| {
                            refused.as_deref().and_then(|stamp| {
                                let (at, why) =
                                    stamp.split_once('|').unwrap_or((stamp, "unsigned"));
                                let at = crate::display_time::parse_db_utc(at)?;
                                // Our own clock or origin is not the sender's fault: worded apart (L3).
                                let key = match why {
                                    "time" => "app_push.vapid_refused_time",
                                    "address" => "app_push.vapid_refused_address",
                                    _ => "app_push.vapid_refused_unsigned",
                                };
                                Some((at, t(lang, key).to_string()))
                            })
                        })
                        .map(|(at, cause)| {
                            (
                                t_params(
                                    lang,
                                    "app_push.vapid_refused",
                                    &[("when", &zone.when(at, now))],
                                ),
                                cause,
                            )
                        }),
                }
            },
        )
        .collect();
    let missing = if on && origin_set {
        up::KNOWN_UP_APPS
            .iter()
            .filter(|(package, ..)| {
                names.get(*package).is_some_and(|name| name.allowed)
                    && !rows.iter().any(|(p, ..)| p == package)
                    && !stopped.iter().any(|p| p == package)
            })
            .map(|(_, name, opens)| {
                let key = if *opens {
                    "app_push.missing_open"
                } else {
                    "app_push.missing"
                };
                (
                    t_params(lang, "app_push.missing_title", &[("app", name)]),
                    t_params(lang, key, &[("app", name)]),
                )
            })
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
        waiting,
        delivery,
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

/// The address the prefill (L7) starts from: Phone Setup's saved one, else `PHONE_SERVER_URL` - the same rule as the
/// setup QR's (`provisioning::server_url`), so a server set up by `.env` alone is prefilled too. The prefill still
/// takes it only when it can be public (`up::prefill`), and only the parent's Save sets it.
fn setup_address(saved: Option<String>, config: &crate::config::ForkConfig) -> Option<String> {
    match saved {
        Some(saved)
            if !saved.is_empty() && !crate::handlers::provisioning::saved_refused(&saved) =>
        {
            Some(saved)
        }
        _ => config.phone_server_url.clone(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::config::ForkConfig;

    #[test]
    fn the_prefill_falls_back_to_the_env_address() {
        let mut config = ForkConfig::for_tests();
        config.phone_server_url = Some("https://phones.example.com".to_string());
        let saved = |url: &str| Some(url.to_string());
        assert_eq!(
            setup_address(None, &config).as_deref(),
            Some("https://phones.example.com")
        );
        assert_eq!(
            setup_address(saved(""), &config).as_deref(),
            Some("https://phones.example.com")
        );
        assert_eq!(
            setup_address(saved("ftp://saved.example"), &config).as_deref(),
            Some("https://phones.example.com"),
            "a saved address the rule refuses loses, as in the QR"
        );
        assert_eq!(
            setup_address(saved("https://saved.example"), &config).as_deref(),
            Some("https://saved.example"),
            "the saved one wins"
        );
        assert_eq!(setup_address(None, &ForkConfig::for_tests()), None);
    }

    /// §7.3: each unit floored, at every boundary, in both languages.
    #[test]
    fn durations_are_floored_words() {
        for (ms, en, nb) in [
            (999, "under a second", "under ett sekund"),
            (1_000, "1 s", "1 s"),
            (59_600, "59 s", "59 s"),
            (60_000, "1 min", "1 min"),
            (3_540_000, "59 min", "59 min"),
            (3_600_000, "1 h 0 min", "1 t 0 min"),
            (5_400_000, "1 h 30 min", "1 t 30 min"),
        ] {
            assert_eq!(duration_words(Lang::En, ms), en, "{ms}");
            assert_eq!(duration_words(Lang::Nb, ms), nb, "{ms}");
        }
    }
}
