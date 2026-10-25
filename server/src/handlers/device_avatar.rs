//! A phone's own picture (server round C, 2026-10-25; migration 0100, `photos::DEVICE_AVATARS`).
//!
//! On the phone's summary the large avatar is a file picker: the chosen picture is saved at once
//! (`data-autosave`; a `<noscript>` "Save picture" without JS), processed exactly as a contact photo
//! (`photos::process`, [photos::Shape::Square]: decoded from the bytes, turned upright, cut square,
//! at most 512 px, re-encoded as JPEG - no EXIF, no GPS, nothing but pixels) and stored under its
//! SHA-256 in `data/device_avatars/`. Every form returns to `#avatar`, so a save never jumps the
//! page to the top; a refused file comes back as `?avatar=<code>#avatar` with its reason under the
//! picture and nothing changed. "Remove picture" goes back to the letter tile.
//!
//! The picture is the parent's PWA's alone - the Devices list, the phone's header, every phone
//! row, and the icon of a push about the phone (`push::send_due`) - and never sent to a phone.
//!
//! **Served without a session** at `/phone-avatars/<hash>` ([view]): a notification's icon is
//! fetched by the browser for the service worker (Chrome, Firefox) outside any page, where the
//! session cookie (SameSite=Strict) isn't reliably sent, so an icon behind the session would come
//! back as the login page. The path is the picture's own SHA-256 (256 bits, unguessable without
//! the picture itself), only a hash some phone names right now is served (a removed or replaced
//! picture is gone at once, its file pruned), there is no listing, and the admin gate (tailnet or
//! loopback) still applies like every route on this listener.

use axum::extract::{Multipart, Path, State};
use axum::http::{StatusCode, header};
use axum::response::{IntoResponse, Redirect, Response};

use crate::AppState;
use crate::photos::{self, DEVICE_AVATARS, PhotoError};

/// Where a stored picture is served ([view]).
pub const ROUTE: &str = "/phone-avatars";

/// The URL of a phone's picture, `None` for the letter tile (or a hash that isn't ours).
pub fn src_of(hash: Option<&str>) -> Option<String> {
    hash.filter(|h| photos::is_valid_hash(h))
        .map(|h| format!("{ROUTE}/{h}"))
}

/// The summary's `?avatar=` code of a refused upload.
fn code_of(err: &PhotoError) -> &'static str {
    match err {
        PhotoError::Empty => "empty",
        PhotoError::TooLarge => "too_large",
        PhotoError::UnsupportedType => "type",
        PhotoError::TooManyPixels => "pixels",
        PhotoError::Undecodable | PhotoError::TooDetailed => "unreadable",
    }
}

/// The reason under the picture for `?avatar=<code>`; `None` for anything else (never echoed).
pub fn error_text(code: Option<&str>) -> Option<&'static str> {
    let err = match code? {
        "empty" => PhotoError::Empty,
        "too_large" => PhotoError::TooLarge,
        "type" => PhotoError::UnsupportedType,
        "pixels" => PhotoError::TooManyPixels,
        "unreadable" => PhotoError::Undecodable,
        "failed" => return Some("Couldn't save the picture - nothing was changed. Try again."),
        _ => return None,
    };
    Some(err.message())
}

fn back(id: i64, code: Option<&str>) -> Response {
    match code {
        Some(code) => Redirect::to(&format!("/devices/{id}?avatar={code}#avatar")),
        None => Redirect::to(&format!("/devices/{id}#avatar")),
    }
    .into_response()
}

/// The answer when phone `id` isn't there (404, or 500 for a database error); `None` when it is.
async fn missing_device(state: &AppState, id: i64) -> Option<Response> {
    match sqlx::query_scalar::<_, i64>("SELECT id FROM devices WHERE id = ?")
        .bind(id)
        .fetch_optional(&state.db)
        .await
    {
        Ok(Some(_)) => None,
        Ok(None) => Some(StatusCode::NOT_FOUND.into_response()),
        Err(err) => {
            tracing::error!(device_id = id, %err, "couldn't read the phone for its picture");
            Some(StatusCode::INTERNAL_SERVER_ERROR.into_response())
        }
    }
}

/// Sets `devices.avatar_hash`; the caller holds the store's lock across its store and this, and
/// prunes after releasing it.
async fn commit(state: &AppState, id: i64, hash: Option<&str>) -> Result<(), sqlx::Error> {
    sqlx::query("UPDATE devices SET avatar_hash = ? WHERE id = ?")
        .bind(hash)
        .bind(id)
        .execute(&state.db)
        .await
        .map(|_| ())
}

/// The picker's upload (multipart field `picture`). Back to `#avatar` either way.
pub async fn upload(
    State(state): State<AppState>,
    Path(id): Path<i64>,
    mut multipart: Multipart,
) -> Response {
    if let Some(answer) = missing_device(&state, id).await {
        return answer;
    }
    let mut upload = None;
    loop {
        match multipart.next_field().await {
            Ok(Some(field)) if field.name() == Some("picture") => match field.bytes().await {
                Ok(bytes) => upload = Some(bytes),
                Err(_) => return back(id, Some(code_of(&PhotoError::TooLarge))),
            },
            Ok(Some(_)) => {}
            Ok(None) => break,
            Err(_) => return back(id, Some(code_of(&PhotoError::TooLarge))),
        }
    }
    let Some(bytes) = upload.filter(|b| !b.is_empty()) else {
        return back(id, Some(code_of(&PhotoError::Empty)));
    };
    let processed = match photos::process_limited(bytes, photos::Shape::Square).await {
        Ok(processed) => processed,
        Err(err) => return back(id, Some(code_of(&err))),
    };
    let result = {
        let _files = DEVICE_AVATARS.lock().await;
        if let Err(err) = photos::store(&state.avatar_dir, &processed).await {
            tracing::error!(device_id = id, %err, "couldn't store a phone's picture");
            return back(id, Some("failed"));
        }
        commit(&state, id, Some(&processed.hash)).await
    };
    if let Err(err) = result {
        tracing::error!(device_id = id, %err, "couldn't save a phone's picture");
        return back(id, Some("failed"));
    }
    DEVICE_AVATARS.prune(&state.db, &state.avatar_dir).await;
    back(id, None)
}

/// "Remove picture": the letter tile again. Back to `#avatar`.
pub async fn remove(State(state): State<AppState>, Path(id): Path<i64>) -> Response {
    if let Some(answer) = missing_device(&state, id).await {
        return answer;
    }
    let result = {
        let _files = DEVICE_AVATARS.lock().await;
        commit(&state, id, None).await
    };
    if let Err(err) = result {
        tracing::error!(device_id = id, %err, "couldn't remove a phone's picture");
        return back(id, Some("failed"));
    }
    DEVICE_AVATARS.prune(&state.db, &state.avatar_dir).await;
    back(id, None)
}

/// A stored picture (see the module's note: no session, only a hash a phone names now).
pub async fn view(State(state): State<AppState>, Path(hash): Path<String>) -> Response {
    let Some(path) = photos::path_for(&state.avatar_dir, &hash) else {
        return StatusCode::NOT_FOUND.into_response();
    };
    let named: Result<bool, _> =
        sqlx::query_scalar("SELECT EXISTS (SELECT 1 FROM devices WHERE avatar_hash = ?)")
            .bind(&hash)
            .fetch_one(&state.db)
            .await;
    if !matches!(named, Ok(true)) {
        return StatusCode::NOT_FOUND.into_response();
    }
    match tokio::fs::read(&path).await {
        Ok(bytes) => (
            [
                (header::CONTENT_TYPE, "image/jpeg"),
                // Content-addressed: a new picture is a new URL.
                (
                    header::CACHE_CONTROL,
                    "private, max-age=31536000, immutable",
                ),
            ],
            bytes,
        )
            .into_response(),
        Err(_) => StatusCode::NOT_FOUND.into_response(),
    }
}
