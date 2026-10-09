//! The phone's music routes (design 21 §1.3), all bearer-authenticated and scoped to the requesting
//! phone's ticked entries - anything else is a 404 like an unknown id:
//!
//! - `GET /api/devices/music/library`: this phone's library (`music::cached_library`), `ETag` = its
//!   version, `If-None-Match` -> 304, gzip when asked for. 404 while nothing is ticked.
//! - `GET /api/devices/music/covers/{hash}`: a cover the library names.
//! - `GET /api/devices/music/files/{id}`: an own file, through design 13's `ServeFile` (Range, strong
//!   ETag, `If-Match` -> 412, 416) plus `X-Content-SHA256`. A file flagged missing is a 404.
//! - `GET /api/devices/music/storytel`: the family's Storytel login, `no-store`; 404 while this
//!   phone's switch is off or none is stored, 503 without a usable key.

use std::io::Write;

use axum::Extension;
use axum::extract::{Path, State};
use axum::http::{HeaderMap, HeaderValue, StatusCode, header};
use axum::response::{IntoResponse, Response};

use crate::AppState;
use crate::security::AuthedDevice;

/// The header carrying an own file's SHA-256 (hex) - the phone checks the whole file against it.
pub const CONTENT_SHA256_HEADER: &str = "x-content-sha256";

fn server_error(device_id: i64, err: impl std::fmt::Display, what: &str) -> Response {
    tracing::error!(device_id, %err, "{what}");
    StatusCode::INTERNAL_SERVER_ERROR.into_response()
}

/// Whether an `If-None-Match` header names `etag` (`*`, a list, weak or strong).
fn etag_matches(headers: &HeaderMap, etag: &str) -> bool {
    headers
        .get_all(header::IF_NONE_MATCH)
        .iter()
        .filter_map(|v| v.to_str().ok())
        .flat_map(|v| v.split(','))
        .map(|tag| tag.trim().trim_start_matches("W/"))
        .any(|tag| tag == "*" || tag == etag)
}

fn accepts_gzip(headers: &HeaderMap) -> bool {
    headers
        .get_all(header::ACCEPT_ENCODING)
        .iter()
        .filter_map(|v| v.to_str().ok())
        .flat_map(|v| v.split(','))
        .any(|coding| {
            let mut parts = coding.split(';');
            let name = parts.next().unwrap_or("").trim();
            let refused = parts.any(|p| {
                p.trim()
                    .strip_prefix("q=")
                    .and_then(|q| q.trim().parse::<f32>().ok())
                    == Some(0.0)
            });
            name.eq_ignore_ascii_case("gzip") && !refused
        })
}

pub async fn library(
    State(state): State<AppState>,
    Extension(AuthedDevice(device)): Extension<AuthedDevice>,
    headers: HeaderMap,
) -> Response {
    let library =
        match crate::music::cached_library(&state.db, &state.music_libraries, device.id).await {
            Ok(Some(library)) => library,
            Ok(None) => return StatusCode::NOT_FOUND.into_response(),
            Err(err) => return server_error(device.id, err, "couldn't build the music library"),
        };
    let etag = format!("\"{}\"", library.version);
    let etag_value = HeaderValue::from_str(&etag).expect("hex in quotes is a valid header");
    let mut response_headers = HeaderMap::new();
    response_headers.insert(header::ETAG, etag_value);
    response_headers.insert(header::CACHE_CONTROL, HeaderValue::from_static("no-cache"));
    response_headers.insert(header::VARY, HeaderValue::from_static("Accept-Encoding"));
    if etag_matches(&headers, &etag) {
        return (StatusCode::NOT_MODIFIED, response_headers).into_response();
    }
    response_headers.insert(
        header::CONTENT_TYPE,
        HeaderValue::from_static("application/json"),
    );
    if accepts_gzip(&headers) {
        let mut encoder = flate2::write::GzEncoder::new(Vec::new(), flate2::Compression::default());
        let gzipped = encoder
            .write_all(&library.json)
            .and_then(|()| encoder.finish());
        match gzipped {
            Ok(body) => {
                response_headers.insert(header::CONTENT_ENCODING, HeaderValue::from_static("gzip"));
                return (response_headers, body).into_response();
            }
            Err(err) => tracing::warn!(device_id = device.id, %err, "couldn't gzip the library"),
        }
    }
    (response_headers, library.json.clone()).into_response()
}

pub async fn cover(
    State(state): State<AppState>,
    Path(hash): Path<String>,
    Extension(AuthedDevice(device)): Extension<AuthedDevice>,
) -> Response {
    let Some(path) = crate::photos::path_for(&state.music_cover_dir, &hash) else {
        return StatusCode::NOT_FOUND.into_response();
    };
    match crate::music::cover_in_library(&state.db, device.id, &hash).await {
        Ok(true) => {}
        Ok(false) => return StatusCode::NOT_FOUND.into_response(),
        Err(err) => return server_error(device.id, err, "music cover lookup failed"),
    }
    match tokio::fs::read(&path).await {
        Ok(bytes) => ([(header::CONTENT_TYPE, "image/jpeg")], bytes).into_response(),
        Err(_) => StatusCode::NOT_FOUND.into_response(),
    }
}

/// An own file of an entry this phone has ticked - through tower-http's `ServeFile` like a catalog
/// APK (design 13): `Content-Length`, strong `ETag`, one `Range` -> 206, `If-Match` -> 412, past
/// the end -> 416. `X-Content-SHA256` is read from the same row as the path.
pub async fn file(
    State(state): State<AppState>,
    Path(id): Path<i64>,
    Extension(AuthedDevice(device)): Extension<AuthedDevice>,
    request: axum::extract::Request,
) -> Response {
    let row: Option<(String, String, bool)> = match sqlx::query_as(
        "SELECT f.path, f.sha256, f.missing FROM music_files f \
         JOIN music_entries e ON e.id = f.entry_id \
         JOIN device_music_entries d ON d.entry_id = f.entry_id \
         WHERE f.id = ? AND d.device_id = ? AND e.source = 'own'",
    )
    .bind(id)
    .bind(device.id)
    .fetch_optional(&state.db)
    .await
    {
        Ok(row) => row,
        Err(err) => return server_error(device.id, err, "music file lookup failed"),
    };
    let Some((relative, sha256, missing)) = row else {
        return StatusCode::NOT_FOUND.into_response();
    };
    let Some(path) = crate::music::file_path(&state.music_files_dir, &relative) else {
        return StatusCode::NOT_FOUND.into_response();
    };
    if missing {
        return StatusCode::NOT_FOUND.into_response();
    }
    let response = match tower_http::services::ServeFile::new(&path)
        .try_call(request)
        .await
    {
        Ok(response) => response,
        Err(err) => return server_error(device.id, err, "couldn't serve a music file"),
    };
    let mut response = response.map(axum::body::Body::new);
    if response.status() == StatusCode::NOT_FOUND {
        // Gone since the last check: listed as missing from now on (QA #3).
        sqlx::query("UPDATE music_files SET missing = 1 WHERE id = ?")
            .bind(id)
            .execute(&state.db)
            .await
            .ok();
        return response;
    }
    if let Ok(value) = HeaderValue::from_str(&sha256) {
        response.headers_mut().insert(CONTENT_SHA256_HEADER, value);
    }
    response
}

#[derive(serde::Serialize)]
struct StorytelLogin {
    generation: i64,
    email: String,
    password: String,
}

#[derive(serde::Deserialize)]
pub(crate) struct StoredLogin {
    pub email: String,
    pub password: String,
}

pub async fn storytel(
    State(state): State<AppState>,
    Extension(AuthedDevice(device)): Extension<AuthedDevice>,
) -> Response {
    let switch: Option<bool> =
        match sqlx::query_scalar("SELECT music_storytel FROM device_policy WHERE device_id = ?")
            .bind(device.id)
            .fetch_optional(&state.db)
            .await
        {
            Ok(switch) => switch,
            Err(err) => return server_error(device.id, err, "storytel switch lookup failed"),
        };
    if switch != Some(true) {
        return StatusCode::NOT_FOUND.into_response();
    }
    type Row = (Option<Vec<u8>>, Option<Vec<u8>>, Option<String>, i64);
    let row: Row = match sqlx::query_as(
        "SELECT ciphertext, nonce, key_fingerprint, generation FROM music_storytel WHERE id = 1",
    )
    .fetch_one(&state.db)
    .await
    {
        Ok(row) => row,
        Err(err) => return server_error(device.id, err, "storytel login lookup failed"),
    };
    let (Some(ciphertext), Some(nonce), Some(fingerprint), generation) = row else {
        return StatusCode::NOT_FOUND.into_response();
    };
    let Some(key) = state.music_key.as_ref() else {
        return StatusCode::SERVICE_UNAVAILABLE.into_response();
    };
    let Some(login) = key
        .open(&ciphertext, &nonce, &fingerprint)
        .and_then(|plain| serde_json::from_slice::<StoredLogin>(&plain).ok())
    else {
        // Sealed with another key (a restored database, a new key file): re-enter it in the PWA.
        tracing::warn!(
            device_id = device.id,
            "the stored Storytel login can't be opened with this server's key"
        );
        return StatusCode::SERVICE_UNAVAILABLE.into_response();
    };
    crate::security::record_security_event(
        &state.db,
        "storytel_login_sent",
        None,
        None,
        Some(&format!("device {}: generation {generation}", device.id)),
    )
    .await;
    (
        [(header::CACHE_CONTROL, "no-store")],
        axum::Json(StorytelLogin {
            generation,
            email: login.email,
            password: login.password,
        }),
    )
        .into_response()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn if_none_match_and_accept_encoding_are_read_like_http() {
        let mut headers = HeaderMap::new();
        headers.insert(
            header::IF_NONE_MATCH,
            HeaderValue::from_static("\"aaa\", W/\"0123\""),
        );
        assert!(etag_matches(&headers, "\"0123\""));
        assert!(!etag_matches(&headers, "\"0124\""));
        headers.insert(header::IF_NONE_MATCH, HeaderValue::from_static("*"));
        assert!(etag_matches(&headers, "\"x\""));

        let mut headers = HeaderMap::new();
        assert!(!accepts_gzip(&headers));
        headers.insert(
            header::ACCEPT_ENCODING,
            HeaderValue::from_static("br, GZIP;q=0.8"),
        );
        assert!(accepts_gzip(&headers));
        headers.insert(
            header::ACCEPT_ENCODING,
            HeaderValue::from_static("gzip;q=0"),
        );
        assert!(!accepts_gzip(&headers));
    }
}
