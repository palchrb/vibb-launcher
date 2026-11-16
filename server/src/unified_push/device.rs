//! The device API of design 108 (§3.7), bearer routes in `device_routes.rs`. Stubbed in R1-C: every registration is
//! refused `off` and the queue is empty, so a launcher built against the contract talks to this server safely.

use axum::Extension;
use axum::Json;
use axum::extract::Path;
use axum::http::StatusCode;
use axum::response::{IntoResponse, Response};
use serde_json::json;

use crate::security::AuthedDevice;

/// `POST /api/devices/up/registrations`: refused `off` until R1-S.
pub async fn register(Extension(AuthedDevice(_device)): Extension<AuthedDevice>) -> Response {
    (StatusCode::CONFLICT, Json(json!({"refused": "off"}))).into_response()
}

/// `DELETE /api/devices/up/registrations/{reg}`: 204, also for an unknown `reg`.
pub async fn unregister(
    Extension(AuthedDevice(_device)): Extension<AuthedDevice>,
    Path(_reg): Path<String>,
) -> StatusCode {
    StatusCode::NO_CONTENT
}

/// `POST /api/devices/up/messages`: nothing pending until R1-S.
pub async fn messages(Extension(AuthedDevice(_device)): Extension<AuthedDevice>) -> Response {
    Json(json!({"messages": [], "more": false})).into_response()
}
