//! The device API of design 108 (§3.7), bearer routes in `device_routes.rs` (route class General: the phone's bucket
//! is enough, its pulls coalesce and each drains up to 50).
//!
//! - `POST /api/devices/up/registrations` `{reg, secret, package, vapid?}`: 200 `{endpoint}` (a retry of the same
//!   `reg` and secret gets it again), 409 `{refused}` with `off`, `stopped`, `not_allowed`, `limit`, `busy` or
//!   `conflict`, 400 for a malformed body, 413 above [REGISTER_LIMIT]. The server's side of the phone's own rules, so
//!   an older or a broken phone can't bypass them. The connection token never leaves the phone; the secret is stored
//!   only as its SHA-256.
//! - `DELETE /api/devices/up/registrations/{reg}`: 204, also for an unknown `reg`.
//! - `POST /api/devices/up/messages` `{ack}`: the acknowledged keys (this phone's only) deleted, then
//!   `{messages, more}` (`store::pull`); 413 above [MESSAGES_LIMIT].

use axum::Extension;
use axum::Json;
use axum::body::Bytes;
use axum::extract::{Path, State};
use axum::http::StatusCode;
use axum::response::{IntoResponse, Response};
use serde::Deserialize;
use serde_json::json;

use super::store;
use crate::AppState;
use crate::security::AuthedDevice;

/// A registration's body.
pub const REGISTER_LIMIT: usize = 1024;
/// A pull's body: up to 200 keys.
pub const MESSAGES_LIMIT: usize = 16 * 1024;

#[derive(Deserialize)]
struct NewRegistration {
    reg: String,
    secret: String,
    package: String,
    vapid: Option<String>,
}

fn refused(reason: &'static str) -> Response {
    (StatusCode::CONFLICT, Json(json!({ "refused": reason }))).into_response()
}

fn failed(err: sqlx::Error) -> Response {
    tracing::error!(%err, "app push: a device request failed");
    StatusCode::INTERNAL_SERVER_ERROR.into_response()
}

/// A VAPID public key's form (an uncompressed P-256 point, base64url without padding).
fn valid_vapid(key: &str) -> bool {
    key.len() == 87
        && key
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || b == b'-' || b == b'_')
}

pub async fn register(
    State(state): State<AppState>,
    Extension(AuthedDevice(device)): Extension<AuthedDevice>,
    body: Bytes,
) -> Response {
    let Ok(new) = serde_json::from_slice::<NewRegistration>(&body) else {
        return StatusCode::BAD_REQUEST.into_response();
    };
    if !store::valid_reg(&new.reg)
        || !store::valid_secret(&new.secret)
        || !crate::handlers::device_api::is_package_name(&new.package)
        || new.vapid.as_deref().is_some_and(|v| !valid_vapid(v))
    {
        return StatusCode::BAD_REQUEST.into_response();
    }
    match decide(&state, device.id, &new).await {
        Ok(Ok(endpoint)) => Json(json!({ "endpoint": endpoint })).into_response(),
        Ok(Err(reason)) => refused(reason),
        Err(err) => failed(err),
    }
}

/// The checks, in order, and the row, in one `BEGIN IMMEDIATE` transaction (R1-SR L4: two registrations at once
/// can't both pass the caps): the endpoint or the refusal's word.
async fn decide(
    state: &AppState,
    device_id: i64,
    new: &NewRegistration,
) -> Result<Result<String, &'static str>, sqlx::Error> {
    let mut tx = state.db.begin_with("BEGIN IMMEDIATE").await?;
    let Some(origin) = super::settings(&mut *tx).await?.public_origin else {
        return Ok(Err("off"));
    };
    if !super::switch(&mut *tx, device_id).await? {
        return Ok(Err("off"));
    }
    if super::stopped(&mut *tx, device_id)
        .await?
        .contains(&new.package)
    {
        return Ok(Err("stopped"));
    }
    // An unmanaged phone (NULL) lets any app register; a list that can't be read lets none.
    let allowlist: Option<String> =
        sqlx::query_scalar("SELECT allowlist_json FROM device_policy WHERE device_id = ?")
            .bind(device_id)
            .fetch_optional(&mut *tx)
            .await?
            .flatten();
    if let Some(json) = allowlist
        && !serde_json::from_str::<Vec<String>>(&json)
            .is_ok_and(|allowed| allowed.contains(&new.package))
    {
        return Ok(Err("not_allowed"));
    }
    let hash = super::secret_hash(&new.secret);
    let existing: Option<(Vec<u8>, String)> = sqlx::query_as(
        "SELECT secret_hash, package FROM up_registrations WHERE device_id = ? AND reg = ?",
    )
    .bind(device_id)
    .bind(&new.reg)
    .fetch_optional(&mut *tx)
    .await?;
    if let Some((stored, package)) = existing {
        // A retry after a lost answer: the same row, the same endpoint.
        return Ok(if stored == hash && package == new.package {
            Ok(store::endpoint(&origin, &new.secret))
        } else {
            Err("conflict")
        });
    }
    let taken: Option<i64> = sqlx::query_scalar(
        "SELECT 1 FROM up_registrations WHERE secret_hash = ?1 \
         UNION ALL SELECT 1 FROM up_gone WHERE secret_hash = ?1 LIMIT 1",
    )
    .bind(&hash)
    .fetch_optional(&mut *tx)
    .await?;
    if taken.is_some() {
        return Ok(Err("conflict"));
    }
    let (of_app, of_phone): (i64, i64) = sqlx::query_as(
        "SELECT COALESCE(SUM(package = ?), 0), COUNT(*) FROM up_registrations WHERE device_id = ?",
    )
    .bind(&new.package)
    .bind(device_id)
    .fetch_one(&mut *tx)
    .await?;
    if of_app >= super::PER_APP || of_phone >= super::PER_PHONE {
        return Ok(Err("limit"));
    }
    // Checked and counted at once (L4); the phone's own hourly cap too (L5).
    if !state
        .unified_push
        .admit_new_registration(device_id, &new.package)
    {
        return Ok(Err("busy"));
    }
    let inserted = async {
        sqlx::query(
            "INSERT INTO up_registrations (device_id, reg, secret_hash, package, vapid) \
             VALUES (?, ?, ?, ?, ?)",
        )
        .bind(device_id)
        .bind(&new.reg)
        .bind(&hash)
        .bind(&new.package)
        .bind(&new.vapid)
        .execute(&mut *tx)
        .await?;
        tx.commit().await
    }
    .await;
    match inserted {
        Ok(()) => {}
        Err(err) => {
            state
                .unified_push
                .undo_new_registration(device_id, &new.package);
            // The unique keys still decide a reg or secret taken some other way.
            if let sqlx::Error::Database(db) = &err
                && db.is_unique_violation()
            {
                return Ok(Err("conflict"));
            }
            return Err(err);
        }
    }
    tracing::debug!(device_id, "app push: a new registration");
    Ok(Ok(store::endpoint(&origin, &new.secret)))
}

pub async fn unregister(
    State(state): State<AppState>,
    Extension(AuthedDevice(device)): Extension<AuthedDevice>,
    Path(reg): Path<String>,
) -> Response {
    if !store::valid_reg(&reg) {
        return StatusCode::NO_CONTENT.into_response();
    }
    match store::delete_registrations(&state.db, device.id, store::Which::Reg(&reg)).await {
        Ok(_) => StatusCode::NO_CONTENT.into_response(),
        Err(err) => failed(err),
    }
}

#[derive(Deserialize)]
struct Pull {
    #[serde(default)]
    ack: Vec<String>,
}

pub async fn messages(
    State(state): State<AppState>,
    Extension(AuthedDevice(device)): Extension<AuthedDevice>,
    body: Bytes,
) -> Response {
    let Ok(pull) = serde_json::from_slice::<Pull>(&body) else {
        return StatusCode::BAD_REQUEST.into_response();
    };
    if pull.ack.len() > store::ACK_MAX {
        return StatusCode::BAD_REQUEST.into_response();
    }
    let now_ms = chrono::Utc::now().timestamp_millis();
    match store::pull(&state.db, device.id, &pull.ack, now_ms).await {
        Ok(pulled) => Json(pulled).into_response(),
        Err(err) => failed(err),
    }
}
