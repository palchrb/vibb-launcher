//! Device › Weather (design 75 §6, the user: "en egen Vær settings ... inne på en spesifikk device sin side"):
//! `GET /devices/{id}/weather`, `device_weather.html`. Two rows, each an auto-saving form that answers on its own
//! anchor (with JS `scroll-restore.js` keeps the exact place; a save never jumps to the top):
//! - `#weather-lock`: "Weather on the lock screen" (`POST /devices/{id}/weather/lock`, as the Lock page's wake
//!   switch), `device_policy.lock_weather`, sent as `launcher_ui.lock_weather`;
//! - `#weather-zone`: while the switch is on, the zone whose centre stands in when the phone has no fresh fix (`POST
//!   /devices/{id}/weather/zone`, one of this phone's zones or none), `device_policy.weather_zone_id` - never in the
//!   policy.
//!
//! Each change is in the security log (the zone by its name, never coordinates) and nudges the phone. The page never
//! shows the cell or the forecast.

use std::collections::HashMap;

use askama::Template;
use axum::extract::{Path, State};
use axum::http::StatusCode;
use axum::response::{Html, IntoResponse, Redirect, Response};
use axum::{Extension, Form};

use crate::AppState;
use crate::handlers::device_pages::{has_capability, load_device, load_latest, load_policy};
use crate::i18n::{Lang, Words, t};
use crate::models::Device;
use crate::security::{self, CurrentAdmin};

/// The launcher capability of a Vibb One that shows the weather.
pub const CAPABILITY: &str = "lock_weather_v1";

/// One choice of the zone select.
pub struct ZoneChoice {
    pub id: i64,
    pub name: String,
    pub selected: bool,
}

#[derive(Template)]
#[template(path = "device_weather.html")]
struct WeatherTemplate {
    lang: Lang,
    title: String,
    device: Device,
    on: bool,
    zones: Vec<ZoneChoice>,
    /// No zone chosen (the select's "None").
    no_zone: bool,
    /// On, Location isn't Regularly and no zone: the lock shows no weather now.
    needs_position: bool,
    /// On and the phone's Vibb One doesn't report [CAPABILITY].
    needs_update: bool,
}
crate::i18n::words!(WeatherTemplate);

/// The phone's page's row value: "On" / "Off".
pub fn summary_value(lang: Lang, on: bool) -> &'static str {
    t(lang, if on { "common.on" } else { "common.off" })
}

/// GET `/devices/{id}/weather`. A phone not set up has the page too.
pub async fn view(State(state): State<AppState>, lang: Lang, Path(id): Path<i64>) -> Response {
    let device = match load_device(&state, id).await {
        Ok(device) => device,
        Err((status, key)) => return crate::i18n::error(lang, status, key),
    };
    let policy = match load_policy(&state, id).await {
        Ok(policy) => policy,
        Err((status, key)) => return crate::i18n::error(lang, status, key),
    };
    let zones = match crate::zones::list(&state.db, id).await {
        Ok(zones) => zones,
        Err(err) => return read_failed(lang, id, err),
    };
    let latest = match load_latest(&state, id).await {
        Ok(latest) => latest,
        Err(err) => return read_failed(lang, id, err),
    };
    let on = policy.lock_weather;
    let chosen = policy
        .weather_zone_id
        .filter(|zone| zones.iter().any(|z| z.id == *zone));
    Html(
        WeatherTemplate {
            lang,
            title: format!("{} · {}", t(lang, "device.page.weather"), device.name),
            on,
            no_zone: chosen.is_none(),
            needs_position: on && policy.location_mode != "interval" && chosen.is_none(),
            needs_update: on && !has_capability(latest.as_ref(), CAPABILITY),
            zones: zones
                .into_iter()
                .map(|z| ZoneChoice {
                    selected: Some(z.id) == chosen,
                    id: z.id,
                    name: z.name,
                })
                .collect(),
            device,
        }
        .render()
        .unwrap(),
    )
    .into_response()
}

fn read_failed(lang: Lang, id: i64, err: sqlx::Error) -> Response {
    tracing::error!(device_id = id, %err, "can't read the weather page");
    crate::i18n::error(lang, StatusCode::INTERNAL_SERVER_ERROR, "error.read_phone")
}

fn save_failed(lang: Lang, id: i64, err: sqlx::Error) -> Response {
    tracing::error!(device_id = id, %err, "couldn't save the weather settings");
    crate::i18n::error(
        lang,
        StatusCode::INTERNAL_SERVER_ERROR,
        "error.save_failed_log",
    )
}

/// Back to the form's row, the phone nudged.
fn saved(state: &AppState, id: i64, anchor: &str) -> Response {
    let _ = state.command_notify.send(id);
    Redirect::to(&format!("/devices/{id}/weather#{anchor}")).into_response()
}

fn detail(id: i64, key: &str, params: &[(&str, &str)]) -> security::Detail {
    let device = id.to_string();
    let mut all = vec![("device", device.as_str())];
    all.extend_from_slice(params);
    security::Detail::words(key, &all).with_facts(format!("device {id}"))
}

/// `POST /devices/{id}/weather/lock`: the checkbox `on` (missing = off). Back to `#weather-lock`, the phone nudged, a
/// `lock_weather_changed` line when the value changed. 404 for an unknown phone, 500 on a DB error - each with
/// nothing written.
pub async fn save_lock(
    State(state): State<AppState>,
    lang: Lang,
    Extension(CurrentAdmin(admin)): Extension<CurrentAdmin>,
    Path(id): Path<i64>,
    Form(form): Form<HashMap<String, String>>,
) -> Response {
    let on = form.contains_key("on");
    let before: Option<bool> =
        match sqlx::query_scalar("SELECT lock_weather FROM device_policy WHERE device_id = ?")
            .bind(id)
            .fetch_optional(&state.db)
            .await
        {
            Ok(before) => before,
            Err(err) => return save_failed(lang, id, err),
        };
    let Some(before) = before else {
        return crate::i18n::error(lang, StatusCode::NOT_FOUND, "error.device_not_found");
    };
    if let Err(err) = sqlx::query("UPDATE device_policy SET lock_weather = ? WHERE device_id = ?")
        .bind(on)
        .bind(id)
        .execute(&state.db)
        .await
    {
        return save_failed(lang, id, err);
    }
    if before != on {
        security::record_security_event(
            &state.db,
            "lock_weather_changed",
            Some(&admin.username),
            None,
            Some(detail(
                id,
                if on {
                    "security.detail.lock_weather.on"
                } else {
                    "security.detail.lock_weather.off"
                },
                &[],
            )),
        )
        .await;
    }
    saved(&state, id, "weather-lock")
}

/// `POST /devices/{id}/weather/zone`: `zone` = one of this phone's zones by id, or empty for none. Back to
/// `#weather-zone`, the phone nudged, a `lock_weather_zone_changed` line (the zone's name) when it changed. 400 for a
/// zone that isn't this phone's, 404 for an unknown phone, 500 on a DB error - each with nothing written.
pub async fn save_zone(
    State(state): State<AppState>,
    lang: Lang,
    Extension(CurrentAdmin(admin)): Extension<CurrentAdmin>,
    Path(id): Path<i64>,
    Form(form): Form<HashMap<String, String>>,
) -> Response {
    let wanted = form.get("zone").map(|z| z.trim()).unwrap_or_default();
    let zone: Option<(i64, String)> = if wanted.is_empty() {
        None
    } else {
        let Ok(zone_id) = wanted.parse::<i64>() else {
            return crate::i18n::error(lang, StatusCode::BAD_REQUEST, "error.zone_not_found");
        };
        match sqlx::query_scalar::<_, String>(
            "SELECT name FROM device_zones WHERE id = ? AND device_id = ?",
        )
        .bind(zone_id)
        .bind(id)
        .fetch_optional(&state.db)
        .await
        {
            Ok(Some(name)) => Some((zone_id, name)),
            Ok(None) => {
                return crate::i18n::error(lang, StatusCode::BAD_REQUEST, "error.zone_not_found");
            }
            Err(err) => return save_failed(lang, id, err),
        }
    };
    let before: Option<Option<i64>> =
        match sqlx::query_scalar("SELECT weather_zone_id FROM device_policy WHERE device_id = ?")
            .bind(id)
            .fetch_optional(&state.db)
            .await
        {
            Ok(before) => before,
            Err(err) => return save_failed(lang, id, err),
        };
    let Some(before) = before else {
        return crate::i18n::error(lang, StatusCode::NOT_FOUND, "error.device_not_found");
    };
    let after = zone.as_ref().map(|(zone_id, _)| *zone_id);
    if let Err(err) =
        sqlx::query("UPDATE device_policy SET weather_zone_id = ? WHERE device_id = ?")
            .bind(after)
            .bind(id)
            .execute(&state.db)
            .await
    {
        return save_failed(lang, id, err);
    }
    if before != after {
        let line = match &zone {
            Some((_, name)) => detail(id, "security.detail.lock_weather_zone", &[("zone", name)]),
            None => detail(id, "security.detail.lock_weather_zone_none", &[]),
        };
        security::record_security_event(
            &state.db,
            "lock_weather_zone_changed",
            Some(&admin.username),
            None,
            Some(line),
        )
        .await;
    }
    saved(&state, id, "weather-zone")
}
