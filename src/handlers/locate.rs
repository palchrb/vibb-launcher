//! Find My Device admin UI: a device picker + map (see `templates/device_locate.html`,
//! Leaflet against public OSM tiles) plus Ring/Lock/Wipe and "Update location now". Commands are
//! queued into `device_commands`, the device is nudged over its push channel and fetches them
//! with its policy (see `handlers::device_api::policy`) - within seconds while it's online,
//! otherwise at its next check-in. The location policy (off / on request / every N minutes, handy
//! step 6) decides when the phone takes a fix on its own.

use askama::Template;
use axum::extract::{Path, Query, State};
use axum::response::{Html, IntoResponse, Json, Redirect};
use axum::{Extension, Form};
use std::collections::HashMap;
use std::time::Duration;

use crate::AppState;
use crate::models::{Device, DeviceCommand, DeviceLocation};
use crate::security::{self, CurrentAdmin};

#[derive(Template)]
#[template(path = "device_locate.html")]
struct LocateTemplate {
    title: String,
    devices: Vec<Device>,
    selected: Option<Device>,
    /// Same id as `selected.id`, but as a plain `i64` (0 = none selected) so
    /// the dropdown's `<option>` loop can compare without nested `if let`.
    selected_id: i64,
    commands: Vec<DeviceCommand>,
    wipe_error: Option<String>,
    /// The selected device's `location_mode` / `location_interval_minutes`.
    location_mode: String,
    location_interval: i64,
    intervals: Vec<i64>,
    /// The newest fix: "12 min ago (±25 m)", or none yet.
    last_fix: Option<String>,
    /// `captured_at` of the newest fix ("" if none) - the page waits for a newer one after
    /// "Update location now".
    last_fix_at: String,
    /// Just pressed "Update location now": the page polls for a fresh fix.
    waiting_for_fix: bool,
    /// The phone's answer to the newest `locate` command ("fix ±12 m, 3 s old", "location is
    /// off for this device", "no location fix"), once it has answered (QA step 6 #7).
    locate_result: Option<String>,
}

/// The newest `locate` command for a device: `(id, result)`.
async fn latest_locate(state: &AppState, device_id: i64) -> Option<(i64, Option<String>)> {
    sqlx::query_as(
        "SELECT id, result FROM device_commands WHERE device_id = ? AND command = 'locate' \
         ORDER BY requested_at DESC, id DESC LIMIT 1",
    )
    .bind(device_id)
    .fetch_optional(&state.db)
    .await
    .ok()
    .flatten()
}

/// `{"id": .., "result": ".." | null}` of the newest `locate` command (`{}` if none) - the
/// locate page's wait shows the phone's answer as soon as it arrives.
pub async fn locate_result_json(
    State(state): State<AppState>,
    Path(id): Path<i64>,
) -> impl IntoResponse {
    match latest_locate(&state, id).await {
        Some((cmd, result)) => Json(serde_json::json!({ "id": cmd, "result": result })),
        None => Json(serde_json::json!({})),
    }
}

/// "3 min ago (±12 m)" for a fix captured at `captured_at` (RFC 3339 from the phone).
fn describe_fix(
    captured_at: &str,
    accuracy: Option<f64>,
    now: chrono::DateTime<chrono::Utc>,
) -> String {
    let age = chrono::DateTime::parse_from_rfc3339(captured_at)
        .map(|t| now.signed_duration_since(t.with_timezone(&chrono::Utc)))
        .ok();
    let age_text = match age {
        Some(d) if d.num_seconds() < 60 => format!("{} s ago", d.num_seconds().max(0)),
        Some(d) if d.num_minutes() < 120 => format!("{} min ago", d.num_minutes()),
        Some(d) if d.num_hours() < 48 => format!("{} h ago", d.num_hours()),
        Some(d) => format!("{} days ago", d.num_days()),
        None => format!("at {captured_at}"),
    };
    match accuracy {
        Some(a) => format!("{age_text} (±{} m)", a.round() as i64),
        None => format!("{age_text} (accuracy unknown)"),
    }
}

async fn render_locate_page(
    state: &AppState,
    selected_id: Option<i64>,
    wipe_error: Option<String>,
    waiting_for_fix: bool,
) -> Html<String> {
    let devices = sqlx::query_as::<_, Device>("SELECT * FROM devices ORDER BY name")
        .fetch_all(&state.db)
        .await
        .unwrap_or_default();

    let selected = selected_id.and_then(|id| devices.iter().find(|d| d.id == id).cloned());

    let commands = if let Some(ref dev) = selected {
        sqlx::query_as::<_, DeviceCommand>(
            "SELECT * FROM device_commands WHERE device_id = ? \
             ORDER BY requested_at DESC LIMIT 20",
        )
        .bind(dev.id)
        .fetch_all(&state.db)
        .await
        .unwrap_or_default()
    } else {
        Vec::new()
    };

    let selected_id = selected.as_ref().map(|d| d.id).unwrap_or(0);

    let (location_mode, location_interval): (String, i64) = sqlx::query_as(
        "SELECT location_mode, location_interval_minutes FROM device_policy WHERE device_id = ?",
    )
    .bind(selected_id)
    .fetch_optional(&state.db)
    .await
    .ok()
    .flatten()
    .unwrap_or_else(|| ("on_request".to_string(), 30));

    let newest = sqlx::query_as::<_, DeviceLocation>(
        "SELECT * FROM device_locations WHERE device_id = ? ORDER BY captured_at DESC, id DESC \
         LIMIT 1",
    )
    .bind(selected_id)
    .fetch_optional(&state.db)
    .await
    .ok()
    .flatten();
    let last_fix = newest
        .as_ref()
        .map(|l| describe_fix(&l.captured_at, l.accuracy_meters, chrono::Utc::now()));
    let last_fix_at = newest.map(|l| l.captured_at).unwrap_or_default();
    let locate_result = latest_locate(state, selected_id).await.and_then(|(_, r)| r);

    Html(
        LocateTemplate {
            title: "Find My Device".to_string(),
            devices,
            selected,
            selected_id,
            commands,
            wipe_error,
            location_mode,
            location_interval,
            intervals: crate::time_rules::LOCATION_INTERVALS.to_vec(),
            last_fix,
            last_fix_at,
            waiting_for_fix,
            locate_result,
        }
        .render()
        .unwrap(),
    )
}

pub async fn show_locate(
    State(state): State<AppState>,
    Query(params): Query<HashMap<String, String>>,
) -> impl IntoResponse {
    let selected_id = params.get("device").and_then(|s| s.parse::<i64>().ok());
    let waiting = params.contains_key("requested");
    render_locate_page(&state, selected_id, None, waiting).await
}

pub async fn locations_json(
    State(state): State<AppState>,
    Path(id): Path<i64>,
) -> impl IntoResponse {
    let locations = sqlx::query_as::<_, DeviceLocation>(
        "SELECT * FROM device_locations WHERE device_id = ? ORDER BY captured_at ASC",
    )
    .bind(id)
    .fetch_all(&state.db)
    .await
    .unwrap_or_default();

    Json(locations)
}

/// Deletes any not-yet-delivered command for this device before inserting the
/// new one - "your last action wins", and doubles as a way to cancel a
/// queued-but-not-yet-delivered wipe by queuing something else before it
/// lands. Once a command is delivered it's no longer touched by this.
/// Broadcasts on `command_notify` afterwards so a connected device's SSE
/// listener (see `device_api::commands_stream`) wakes it immediately instead
/// of waiting for its next 2-minute poll - a `send` with no subscribers
/// (device offline/asleep) is expected and harmless, it just falls back to
/// that regular poll.
async fn queue_command(state: &AppState, device_id: i64, command: &str) {
    sqlx::query("DELETE FROM device_commands WHERE device_id = ? AND delivered_at IS NULL")
        .bind(device_id)
        .execute(&state.db)
        .await
        .ok();
    sqlx::query("INSERT INTO device_commands (device_id, command) VALUES (?, ?)")
        .bind(device_id)
        .bind(command)
        .execute(&state.db)
        .await
        .ok();

    let _ = state.command_notify.send(device_id);
}

pub async fn ring(
    State(state): State<AppState>,
    Path(id): Path<i64>,
    Extension(CurrentAdmin(admin)): Extension<CurrentAdmin>,
) -> impl IntoResponse {
    queue_command(&state, id, "ring").await;
    security::record_security_event(
        &state.db,
        "device_command_queued",
        Some(&admin.username),
        None,
        Some("ring"),
    )
    .await;
    Redirect::to(&format!("/devices/locate?device={id}"))
}

pub async fn stop_ring(
    State(state): State<AppState>,
    Path(id): Path<i64>,
    Extension(CurrentAdmin(admin)): Extension<CurrentAdmin>,
) -> impl IntoResponse {
    queue_command(&state, id, "stop_ring").await;
    security::record_security_event(
        &state.db,
        "device_command_queued",
        Some(&admin.username),
        None,
        Some("stop_ring"),
    )
    .await;
    Redirect::to(&format!("/devices/locate?device={id}"))
}

pub async fn lock(
    State(state): State<AppState>,
    Path(id): Path<i64>,
    Extension(CurrentAdmin(admin)): Extension<CurrentAdmin>,
) -> impl IntoResponse {
    queue_command(&state, id, "lock").await;
    security::record_security_event(
        &state.db,
        "device_command_queued",
        Some(&admin.username),
        None,
        Some("lock"),
    )
    .await;
    Redirect::to(&format!("/devices/locate?device={id}"))
}

/// "Update location now": queues `locate`, which makes the phone take a fresh fix (bypassing its
/// own throttle and the location policy's interval) and report it; the page then waits for it.
pub async fn locate(
    State(state): State<AppState>,
    Path(id): Path<i64>,
    Extension(CurrentAdmin(admin)): Extension<CurrentAdmin>,
) -> impl IntoResponse {
    queue_command(&state, id, "locate").await;
    security::record_security_event(
        &state.db,
        "device_command_queued",
        Some(&admin.username),
        None,
        Some("locate"),
    )
    .await;
    Redirect::to(&format!("/devices/locate?device={id}&requested=1"))
}

/// The location policy: `mode` off / on_request / interval, `interval_minutes` from
/// `time_rules::LOCATION_INTERVALS` (kept as is unless the mode is "interval"). 400 on an unknown
/// value, 404 for an unknown device; nudges the phone.
pub async fn update_location_policy(
    State(state): State<AppState>,
    Extension(CurrentAdmin(admin)): Extension<CurrentAdmin>,
    Path(id): Path<i64>,
    Form(form): Form<HashMap<String, String>>,
) -> axum::response::Response {
    let mode = form.get("mode").map(String::as_str).unwrap_or("");
    let interval = form
        .get("interval_minutes")
        .and_then(|m| m.trim().parse::<i64>().ok());
    if !crate::time_rules::LOCATION_MODES.contains(&mode)
        || interval.is_some_and(|m| !crate::time_rules::LOCATION_INTERVALS.contains(&m))
    {
        return (
            axum::http::StatusCode::BAD_REQUEST,
            "Unknown location mode or interval",
        )
            .into_response();
    }
    let result = sqlx::query(
        "UPDATE device_policy SET location_mode = ?, \
         location_interval_minutes = COALESCE(?, location_interval_minutes), \
         updated_at = datetime('now') WHERE device_id = ?",
    )
    .bind(mode)
    .bind(interval)
    .bind(id)
    .execute(&state.db)
    .await;
    match result {
        Ok(done) if done.rows_affected() == 0 => {
            (axum::http::StatusCode::NOT_FOUND, "Device not found").into_response()
        }
        Ok(_) => {
            security::record_security_event(
                &state.db,
                "location_policy_changed",
                Some(&admin.username),
                None,
                Some(&format!(
                    "device {id}: {mode}{}",
                    interval
                        .map(|m| format!(", every {m} min"))
                        .unwrap_or_default()
                )),
            )
            .await;
            let _ = state.command_notify.send(id);
            Redirect::to(&format!("/devices/locate?device={id}")).into_response()
        }
        Err(err) => {
            tracing::error!(device_id = id, %err, "failed to save the location policy");
            (
                axum::http::StatusCode::INTERNAL_SERVER_ERROR,
                "Couldn't save - nothing was changed. Check the server log.",
            )
                .into_response()
        }
    }
}

/// Gated the same way backup restore/delete already are in this project
/// (`templates/backups.html`) - the admin must type the device's exact name
/// before this actually queues anything. Wipe is irreversible and the device
/// can never acknowledge it (it's gone), so this confirmation is the only
/// safety net there is.
pub async fn wipe(
    State(state): State<AppState>,
    Path(id): Path<i64>,
    Extension(CurrentAdmin(admin)): Extension<CurrentAdmin>,
    Form(form): Form<HashMap<String, String>>,
) -> impl IntoResponse {
    let device = sqlx::query_as::<_, Device>("SELECT * FROM devices WHERE id = ?")
        .bind(id)
        .fetch_optional(&state.db)
        .await
        .ok()
        .flatten();

    let Some(device) = device else {
        return Redirect::to("/devices/locate").into_response();
    };

    let confirm = form.get("confirm_name").map(|s| s.trim()).unwrap_or("");
    if confirm != device.name {
        return render_locate_page(
            &state,
            Some(id),
            Some("That didn't match the device name - nothing was wiped.".to_string()),
            false,
        )
        .await
        .into_response();
    }

    queue_command(&state, id, "wipe").await;
    security::record_security_event(
        &state.db,
        "device_wipe_queued",
        Some(&admin.username),
        None,
        Some(&device.name),
    )
    .await;
    Redirect::to(&format!("/devices/locate?device={id}")).into_response()
}

/// Keeps the location trail bounded - same shape as the other scheduled
/// background loops in this project (`handlers::backups::run_scheduled_backups`,
/// `handlers::tracked_apps::run_scheduled_tracked_app_sync`).
pub async fn run_location_pruning(state: AppState) {
    let mut interval = tokio::time::interval(Duration::from_secs(60 * 60 * 24));
    loop {
        interval.tick().await;
        sqlx::query("DELETE FROM device_locations WHERE received_at < datetime('now', '-30 days')")
            .execute(&state.db)
            .await
            .ok();
    }
}
