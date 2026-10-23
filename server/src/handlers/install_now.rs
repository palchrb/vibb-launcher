//! Install Now (design 37, `docs/design/37-install-now.md` at the monorepo root): the launcher's
//! page under Apps (`/apps/tracked/{id}` with `is_launcher`) has an Install Now button per phone
//! that is behind. It queues a one-time `install_update` command ([queue_install], one transaction,
//! the release tag the row showed as its payload); the phone downloads the newest release on any
//! network, checks it as every self-update and installs it at once, waiting only for a call or an
//! emergency. Cancel stops it while the phone hasn't fetched it. Plain forms, full auth; every
//! answer returns to the phone's row (`#phone-{id}`) or, for a phone without a row, to the group
//! (`#phones`), with and without JS. The row's words are `crate::install_now`'s.

use axum::Extension;
use axum::extract::{Path, State};
use axum::http::StatusCode;
use axum::response::{IntoResponse, Json, Redirect, Response};
use chrono::{DateTime, Utc};

use crate::AppState;
use crate::display_time::Zone;
use crate::install_now::{self, Offer, Report};
use crate::models::{Device, DeviceCommand, DeviceStatus, TrackedApp};
use crate::security::{self, CurrentAdmin};

/// One phone's Install Now part of its row on the launcher's page.
pub(crate) struct InstallRow {
    /// The running version (the row's value).
    pub(crate) value: Option<String>,
    /// The row's sub-line (the poll replaces it).
    pub(crate) sub: String,
    /// Install Now.
    pub(crate) install: bool,
    /// Cancel, for a command the phone hasn't fetched.
    pub(crate) cancel_id: Option<i64>,
    /// The poll's state word (`data-install-state`).
    pub(crate) state: &'static str,
    /// The newest Install Now's answer, for a day: "Install Now · 14:31 · Done", its line inside.
    pub(crate) answer: Option<crate::handlers::locate::CommandRow>,
    /// `?install=<word>` for this phone: the text and whether it is good news.
    pub(crate) flash: Option<(&'static str, bool)>,
}

/// The phone's latest report as Install Now reads it.
fn report_of<'a>(
    status: &'a DeviceStatus,
    fence: &'a Option<crate::kiosk_escapes::UpdateFenceState>,
    downloads: &'a Option<crate::app_downloads::AppDownloads>,
    app_id: i64,
) -> Report<'a> {
    let claims = |capability: &str| {
        status
            .capabilities_json
            .as_deref()
            .is_some_and(|caps| caps.contains(&format!("\"{capability}\"")))
    };
    Report {
        app_version: status.app_version.as_deref(),
        app_version_code: status.app_version_code,
        capable: claims(install_now::CAPABILITY),
        same_code_capable: claims(install_now::SAME_CODE_CAPABILITY),
        pending_tag: fence.as_ref().and_then(|f| f.pending_tag.as_deref()),
        waiting_for: fence.as_ref().and_then(|f| f.waiting_for.as_deref()),
        download: downloads.as_ref().and_then(|d| d.entry(app_id)),
        reported_at: crate::display_time::parse_db_utc(&status.reported_at),
    }
}

/// The launcher row's release as a phone would get it: none while the row is off.
fn release_tag(app: &TrackedApp) -> Option<String> {
    if !app.enabled {
        return None;
    }
    crate::handlers::device_api::device_release_tag(app)
}

/// The newest `install_update` of a phone.
async fn newest_command(
    state: &AppState,
    device_id: i64,
) -> Result<Option<DeviceCommand>, sqlx::Error> {
    sqlx::query_as::<_, DeviceCommand>(
        "SELECT * FROM device_commands WHERE device_id = ? AND command = ? \
         ORDER BY id DESC LIMIT 1",
    )
    .bind(device_id)
    .bind(install_now::COMMAND)
    .fetch_optional(&state.db)
    .await
}

/// What the row says for `device` (its report and newest command read here; a DB error is the
/// caller's 500, never a made-up row).
async fn phone_offer(
    state: &AppState,
    app: &TrackedApp,
    device_id: i64,
    zone: &Zone,
    now: DateTime<Utc>,
) -> Result<(Offer, Option<DeviceCommand>), sqlx::Error> {
    let latest = crate::handlers::device_pages::load_latest(state, device_id).await?;
    let newest = newest_command(state, device_id).await?;
    let tag = release_tag(app);
    let offer = match &latest {
        Some(status) => {
            let fence =
                crate::kiosk_escapes::parse_update_fence(status.update_fence_json.as_deref());
            let downloads = crate::app_downloads::parse(status.app_downloads_json.as_deref());
            let report = report_of(status, &fence, &downloads, app.id);
            install_now::offer(tag.as_deref(), Some(&report), newest.as_ref(), zone, now)
        }
        None => install_now::offer(tag.as_deref(), None, newest.as_ref(), zone, now),
    };
    Ok((offer, newest))
}

/// The launcher page's row for one set-up phone; `flash` = `?install=` when it names this phone.
pub(crate) async fn install_row(
    state: &AppState,
    app: &TrackedApp,
    device_id: i64,
    zone: &Zone,
    now: DateTime<Utc>,
    flash: Option<&str>,
) -> Result<InstallRow, sqlx::Error> {
    let (offer, newest) = phone_offer(state, app, device_id, zone, now).await?;
    let answer = newest
        .as_ref()
        .filter(|c| install_now::answer_fresh(c, now.naive_utc()))
        .map(|c| {
            crate::handlers::locate::command_row(
                c,
                &crate::parents::Names::default(),
                zone,
                now,
                true,
            )
        });
    Ok(InstallRow {
        value: offer.value().map(str::to_string),
        sub: offer.sub().unwrap_or_default(),
        install: offer.install(),
        cancel_id: offer.cancel_id(),
        state: offer.state(),
        answer,
        flash: flash.and_then(install_now::flash),
    })
}

fn db_error(app_id: i64, err: sqlx::Error) -> Response {
    tracing::error!(app_id, %err, "Install Now can't read the phones");
    (
        StatusCode::INTERNAL_SERVER_ERROR,
        "Couldn't read the phones - check the server log. Nothing was sent.",
    )
        .into_response()
}

/// The launcher's row, or the 404 every other app (or none) gets.
async fn launcher_app(state: &AppState, app_id: i64) -> Result<TrackedApp, Box<Response>> {
    match sqlx::query_as::<_, TrackedApp>("SELECT * FROM tracked_apps WHERE id = ?")
        .bind(app_id)
        .fetch_optional(&state.db)
        .await
    {
        Ok(Some(app)) if app.is_launcher => Ok(app),
        Ok(_) => Err(Box::new(
            (StatusCode::NOT_FOUND, "Not the launcher's page").into_response(),
        )),
        Err(err) => Err(Box::new(db_error(app_id, err))),
    }
}

/// Where an answer lands: the phone's row while it has one (a set-up phone), else the group -
/// always rendered (design 37 §2.1, QA M4).
fn back(app_id: i64, word: &str, device: Option<&Device>) -> Response {
    let to = match device.filter(|d| d.enrolled_at.is_some()) {
        Some(d) => format!(
            "/apps/tracked/{app_id}?install={word}&phone={id}#phone-{id}",
            id = d.id
        ),
        None => format!("/apps/tracked/{app_id}?install={word}#phones"),
    };
    Redirect::to(&to).into_response()
}

async fn load_device(state: &AppState, device_id: i64) -> Result<Option<Device>, sqlx::Error> {
    sqlx::query_as::<_, Device>("SELECT * FROM devices WHERE id = ?")
        .bind(device_id)
        .fetch_optional(&state.db)
        .await
}

/// `POST /apps/tracked/{app_id}/install-now/{device_id}`: queues Install Now only when the row
/// offers it ([install_now::refusal]); a waiting one is replaced.
pub async fn install(
    State(state): State<AppState>,
    Path((app_id, device_id)): Path<(i64, i64)>,
    Extension(CurrentAdmin(admin)): Extension<CurrentAdmin>,
) -> Response {
    let app = match launcher_app(&state, app_id).await {
        Ok(app) => app,
        Err(response) => return *response,
    };
    let device = match load_device(&state, device_id).await {
        Ok(Some(device)) => device,
        Ok(None) => return back(app_id, "unknown_phone", None),
        Err(err) => return db_error(app_id, err),
    };
    if device.enrolled_at.is_none() {
        return back(app_id, "not_set_up", Some(&device));
    }
    match queue_install(&state, &app, device_id, &admin).await {
        Ok(Queued::Sent(tag)) => {
            security::record_security_event(
                &state.db,
                "device_command_queued",
                Some(&admin.username),
                None,
                Some(&format!("install_update {tag}, device {device_id}")),
            )
            .await;
            back(app_id, "sent", Some(&device))
        }
        Ok(Queued::Refused(word)) => back(app_id, word, Some(&device)),
        Err(err) => {
            tracing::error!(device_id, %err, "can't queue Install Now");
            (
                StatusCode::INTERNAL_SERVER_ERROR,
                "Couldn't send it - nothing was queued. Check the server log.",
            )
                .into_response()
        }
    }
}

/// What [queue_install] did.
enum Queued {
    /// Queued, with the release tag as its payload.
    Sent(String),
    /// Nothing queued: [install_now::refusal]'s word.
    Refused(&'static str),
}

/// The refusal check and the queue in one transaction (qa-37-code nit 3): its first statement
/// cancels a waiting Install Now of the phone as "replaced" - a write, so SQLite holds the write
/// lock from there and the policy's pop (an UPDATE) can't hand out a command between the check
/// and the insert. A refusal rolls the replace back. The phone's nudge and the replace's event
/// follow the commit, as `locate::queue_command`'s do.
async fn queue_install(
    state: &AppState,
    app: &TrackedApp,
    device_id: i64,
    admin: &crate::models::AdminUser,
) -> Result<Queued, sqlx::Error> {
    let mut tx = state.db.begin().await?;
    let replaced = sqlx::query(
        "UPDATE device_commands SET cancelled_at = datetime('now'), cancel_reason = 'replaced' \
         WHERE device_id = ? AND command = ? AND delivered_at IS NULL AND cancelled_at IS NULL",
    )
    .bind(device_id)
    .bind(install_now::COMMAND)
    .execute(&mut *tx)
    .await?
    .rows_affected();
    let latest: Option<DeviceStatus> = sqlx::query_as(
        "SELECT * FROM device_status WHERE device_id = ? ORDER BY reported_at DESC, id DESC LIMIT 1",
    )
    .bind(device_id)
    .fetch_optional(&mut *tx)
    .await?;
    // The newest one that wasn't just replaced (a replaced one is no longer open either way).
    let newest: Option<DeviceCommand> = sqlx::query_as(
        "SELECT * FROM device_commands WHERE device_id = ? AND command = ? ORDER BY id DESC LIMIT 1",
    )
    .bind(device_id)
    .bind(install_now::COMMAND)
    .fetch_optional(&mut *tx)
    .await?;
    let tag = release_tag(app);
    let now = Utc::now().naive_utc();
    let refused = match &latest {
        Some(status) => {
            let fence =
                crate::kiosk_escapes::parse_update_fence(status.update_fence_json.as_deref());
            let downloads = crate::app_downloads::parse(status.app_downloads_json.as_deref());
            let report = report_of(status, &fence, &downloads, app.id);
            install_now::refusal(tag.as_deref(), Some(&report), newest.as_ref(), now)
        }
        None => install_now::refusal(tag.as_deref(), None, newest.as_ref(), now),
    };
    // `refusal` gives the button only with a release.
    let tag = match (refused, tag) {
        (None, Some(tag)) => tag,
        (word, _) => {
            tx.rollback().await?;
            return Ok(Queued::Refused(word.unwrap_or("no_release")));
        }
    };
    sqlx::query(
        "INSERT INTO device_commands (device_id, command, payload, requested_by) \
         VALUES (?, ?, ?, ?)",
    )
    .bind(device_id)
    .bind(install_now::COMMAND)
    .bind(&tag)
    .bind(admin.id)
    .execute(&mut *tx)
    .await?;
    tx.commit().await?;
    if replaced > 0 {
        security::record_security_event(
            &state.db,
            "device_command_replaced",
            Some(&admin.username),
            None,
            Some(&format!(
                "{replaced} waiting install_update replaced by a new install_update, device {device_id}"
            )),
        )
        .await;
    }
    let _ = state.command_notify.send(device_id);
    Ok(Queued::Sent(tag))
}

/// `POST /apps/tracked/{app_id}/install-now/{device_id}/cancel/{command_id}`: Install Now's
/// Cancel, while the phone hasn't fetched it (`locate::cancel_waiting`, only `install_update`).
pub async fn cancel(
    State(state): State<AppState>,
    Path((app_id, device_id, command_id)): Path<(i64, i64, i64)>,
    Extension(CurrentAdmin(admin)): Extension<CurrentAdmin>,
) -> Response {
    if let Err(response) = launcher_app(&state, app_id).await {
        return *response;
    }
    let device = match load_device(&state, device_id).await {
        Ok(device) => device,
        Err(err) => return db_error(app_id, err),
    };
    match crate::handlers::locate::cancel_waiting(
        &state,
        device_id,
        command_id,
        Some(install_now::COMMAND),
        &admin.username,
    )
    .await
    {
        Ok(true) => back(app_id, "cancelled", device.as_ref()),
        Ok(false) => back(app_id, "too_late", device.as_ref()),
        Err(err) => {
            tracing::error!(device_id, command_id, %err, "can't cancel Install Now");
            (
                StatusCode::INTERNAL_SERVER_ERROR,
                "Couldn't cancel it - check the server log. It may still reach the phone.",
            )
                .into_response()
        }
    }
}

/// One phone in the poll's answer.
#[derive(serde::Serialize)]
struct PolledPhone {
    id: i64,
    state: &'static str,
    sub: String,
}

/// `GET /apps/tracked/{app_id}/install-now.json`: every set-up phone's state word and sub-line,
/// for `static/install-now.js` (it swaps the sub-lines and reloads the page when a state word
/// changes). A DB error is a 500.
pub async fn poll(State(state): State<AppState>, Path(app_id): Path<i64>) -> Response {
    let app = match launcher_app(&state, app_id).await {
        Ok(app) => app,
        Err(response) => return *response,
    };
    let devices: Vec<Device> =
        match sqlx::query_as("SELECT * FROM devices WHERE enrolled_at IS NOT NULL ORDER BY name")
            .fetch_all(&state.db)
            .await
        {
            Ok(devices) => devices,
            Err(err) => return db_error(app_id, err),
        };
    let zone = Zone::load(&state.db).await;
    let now = Utc::now();
    let mut phones = Vec::with_capacity(devices.len());
    for device in devices {
        match phone_offer(&state, &app, device.id, &zone, now).await {
            Ok((offer, _)) => phones.push(PolledPhone {
                id: device.id,
                state: offer.state(),
                sub: offer.sub().unwrap_or_default(),
            }),
            Err(err) => return db_error(app_id, err),
        }
    }
    Json(serde_json::json!({ "phones": phones })).into_response()
}
