//! The weather's two device routes (design 75 §3.4): `GET /api/devices/weather` (the glance's line and the card,
//! pulled about hourly) and `GET /api/devices/weather/now` (the card's nowcast line, at an opening). Both answer
//! `Cache-Control: no-store` JSON, gzip'd when asked; neither returns coordinates or the cell.
//!
//! The position is D5's, in order: the phone's newest real fix (`COALESCE(mock, 0) = 0`) at most 6 h old and not in
//! the future while its Location is Regularly; else the zone the parent chose on Device › Weather; else none. The
//! words and hours are in the phone's own zone (`device_api::phone_zone`).

use std::io::Write as _;

use axum::Extension;
use axum::extract::State;
use axum::http::{HeaderMap, HeaderValue, StatusCode, header};
use axum::response::{IntoResponse, Response};
use chrono::{DateTime, Duration, SecondsFormat, Utc};

use crate::AppState;
use crate::security::AuthedDevice;
use crate::weather::{self, Cell};
use crate::weather_cache;

/// A fix older than this isn't the phone's position for the weather (D5: the longest interval with one missed
/// report).
pub const FRESH_FIX_HOURS: i64 = 6;

/// The phone's weather settings.
struct Settings {
    on: bool,
    location_mode: String,
    zone_id: Option<i64>,
}

async fn settings(state: &AppState, device_id: i64) -> Result<Option<Settings>, sqlx::Error> {
    let row: Option<(bool, String, Option<i64>)> = sqlx::query_as(
        "SELECT lock_weather, location_mode, weather_zone_id FROM device_policy WHERE device_id = ?",
    )
    .bind(device_id)
    .fetch_optional(&state.db)
    .await?;
    Ok(row.map(|(on, location_mode, zone_id)| Settings {
        on,
        location_mode,
        zone_id,
    }))
}

/// D5's cell: the fresh stored fix (Location Regularly only), else the weather zone's centre, else none.
async fn cell_of(
    state: &AppState,
    device_id: i64,
    settings: &Settings,
    now: DateTime<Utc>,
) -> Result<Option<Cell>, sqlx::Error> {
    if settings.location_mode == "interval" {
        let fix: Option<(f64, f64)> = sqlx::query_as(
            "SELECT latitude, longitude FROM device_locations \
             WHERE device_id = ? AND COALESCE(mock, 0) = 0 AND captured_at >= ? AND captured_at <= ? \
             ORDER BY captured_at DESC, id DESC LIMIT 1",
        )
        .bind(device_id)
        .bind(fix_stamp(now - Duration::hours(FRESH_FIX_HOURS)))
        .bind(fix_stamp(now))
        .fetch_optional(&state.db)
        .await?;
        if let Some(cell) = fix.and_then(|(lat, lon)| Cell::of(lat, lon)) {
            return Ok(Some(cell));
        }
    }
    let Some(zone_id) = settings.zone_id else {
        return Ok(None);
    };
    let zone: Option<(f64, f64)> = sqlx::query_as(
        "SELECT latitude, longitude FROM device_zones WHERE id = ? AND device_id = ?",
    )
    .bind(zone_id)
    .bind(device_id)
    .fetch_optional(&state.db)
    .await?;
    Ok(zone.and_then(|(lat, lon)| Cell::of(lat, lon)))
}

/// `device_locations.captured_at`'s form (`models::checked_location`): UTC with milliseconds.
fn fix_stamp(t: DateTime<Utc>) -> String {
    t.to_rfc3339_opts(SecondsFormat::Millis, true)
}

/// The phone's own zone: its newest report's `time_state.zone`, else Phone Setup's.
async fn zone_of(state: &AppState, device_id: i64) -> Result<chrono_tz::Tz, sqlx::Error> {
    let latest: Option<(String, String, Option<String>)> = sqlx::query_as(
        "SELECT reported_at, lock_reason, time_state_json FROM device_status \
         WHERE device_id = ? ORDER BY reported_at DESC, id DESC LIMIT 1",
    )
    .bind(device_id)
    .fetch_optional(&state.db)
    .await?;
    let report = latest.and_then(|(at, reason, json)| {
        crate::handlers::devices::time_report(&at, &reason, json.as_deref())
    });
    let setup = crate::display_time::Zone::load(&state.db, crate::i18n::Lang::default()).await;
    Ok(crate::handlers::device_api::phone_zone(report.as_ref().map(|(_, r)| r), &setup).tz())
}

/// JSON, `no-store`, gzip'd when the phone asks for it.
fn answer(headers: &HeaderMap, body: &serde_json::Value) -> Response {
    let json = body.to_string().into_bytes();
    let mut out = HeaderMap::new();
    out.insert(header::CACHE_CONTROL, HeaderValue::from_static("no-store"));
    out.insert(
        header::CONTENT_TYPE,
        HeaderValue::from_static("application/json"),
    );
    out.insert(header::VARY, HeaderValue::from_static("Accept-Encoding"));
    if crate::handlers::music_api::accepts_gzip(headers) {
        let mut encoder = flate2::write::GzEncoder::new(Vec::new(), flate2::Compression::default());
        if let Ok(body) = encoder.write_all(&json).and_then(|()| encoder.finish()) {
            out.insert(header::CONTENT_ENCODING, HeaderValue::from_static("gzip"));
            return (out, body).into_response();
        }
    }
    (out, json).into_response()
}

fn too_many(seconds: u64) -> Response {
    (
        StatusCode::TOO_MANY_REQUESTS,
        [(header::RETRY_AFTER, seconds.to_string())],
    )
        .into_response()
}

fn failed(device_id: i64, err: sqlx::Error) -> Response {
    tracing::error!(device_id, %err, "weather: a phone's answer failed");
    StatusCode::INTERNAL_SERVER_ERROR.into_response()
}

/// `GET /api/devices/weather` (§3.4): `off` (no outside request), `no_position`, `unavailable` (no stored hour
/// covers now: the fetch failed or still runs) or `ok` with the hour schedule and the card's days. 20 asks a rolling
/// hour per phone (429 with `Retry-After`); a DB error is a 500 (the phone keeps what it has).
pub async fn forecast(
    State(state): State<AppState>,
    Extension(AuthedDevice(device)): Extension<AuthedDevice>,
    headers: HeaderMap,
) -> Response {
    if let Err(seconds) = state
        .weather
        .forecast_asks
        .ask(device.id, tokio::time::Instant::now())
    {
        return too_many(seconds);
    }
    let settings = match settings(&state, device.id).await {
        Ok(Some(settings)) if settings.on => settings,
        Ok(_) => return answer(&headers, &weather::status("off")),
        Err(err) => return failed(device.id, err),
    };
    let now = state.weather.now();
    let cell = match cell_of(&state, device.id, &settings, now).await {
        Ok(Some(cell)) => cell,
        Ok(None) => return answer(&headers, &weather::status("no_position")),
        Err(err) => return failed(device.id, err),
    };
    let row = match weather_cache::forecast(&state, cell).await {
        Ok(Some(row)) => row,
        // A Location Off erase took the cell while this ask ran.
        Ok(None) => return answer(&headers, &weather::status("unavailable")),
        Err(err) => return failed(device.id, err),
    };
    let hours = row.hours();
    let Some(checked_at) = row.checked_at().filter(|_| !hours.is_empty()) else {
        return answer(&headers, &weather::status("unavailable"));
    };
    let place = match weather_cache::place(&state, cell).await {
        Ok(place) => place,
        Err(err) => return failed(device.id, err),
    };
    let tz = match zone_of(&state, device.id).await {
        Ok(tz) => tz,
        Err(err) => return failed(device.id, err),
    };
    let now = state.weather.now();
    let body = weather::answer(&hours, tz, now, checked_at, place.as_deref(), cell);
    let status = body["status"].as_str().unwrap_or_default();
    tracing::debug!(device_id = device.id, status, "weather: a phone's pull");
    answer(&headers, &body)
}

/// `GET /api/devices/weather/now` (§3.4): `none` (off, no position) or `ok` with the card's nowcast line or null.
/// 60 asks a rolling hour per phone.
pub async fn now(
    State(state): State<AppState>,
    Extension(AuthedDevice(device)): Extension<AuthedDevice>,
    headers: HeaderMap,
) -> Response {
    if let Err(seconds) = state
        .weather
        .nowcast_asks
        .ask(device.id, tokio::time::Instant::now())
    {
        return too_many(seconds);
    }
    let settings = match settings(&state, device.id).await {
        Ok(Some(settings)) if settings.on => settings,
        Ok(_) => return answer(&headers, &weather::status("none")),
        Err(err) => return failed(device.id, err),
    };
    let cell = match cell_of(&state, device.id, &settings, state.weather.now()).await {
        Ok(Some(cell)) => cell,
        Ok(None) => return answer(&headers, &weather::status("none")),
        Err(err) => return failed(device.id, err),
    };
    let line = weather_cache::nowcast(&state, cell).await;
    answer(&headers, &weather::now_answer(line))
}
