//! The phone's music routes (design 21 §1.3), all bearer-authenticated and scoped to the requesting
//! phone's ticked entries - anything else is a 404 like an unknown id:
//!
//! - `GET /api/devices/music/library`: this phone's library (`music::cached_library`), `ETag` = its
//!   version, `If-None-Match` -> 304, gzip when asked for. 404 while nothing is ticked.
//! - `GET /api/devices/music/entries/{id}/items`: an NRK/RSS entry's episode list (design 21b
//!   §4.2), the same way; 404 while the sweep hasn't listed it.
//! - `GET /api/devices/music/covers/{hash}`: a cover the library names.
//! - `GET /api/devices/music/files/{id}`: an own file, through design 13's `ServeFile` (Range, strong
//!   ETag, `If-Match` -> 412, 416) plus `X-Content-SHA256`. A file flagged missing is a 404.
//! - `GET /api/devices/music/storytel`: the family's Storytel login, `no-store`; 404 while this
//!   phone's switch is off or none is stored, 503 without a usable key.
//! - `POST /api/devices/music/spotify/token`: an access token of the Spotify account this phone
//!   plays as (design 26c §2.6), `no-store`, always a JSON `status`; at most 20 a rolling hour.
//! - `POST /api/devices/music/storytel/token`: the server's Storytel session (design 58 §2.2),
//!   `no-store`, always a JSON `status`; at most 20 a rolling hour. The phone never gets the login.

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
    versioned_json(
        device.id,
        &headers,
        &library.version,
        &library.json,
        "library",
    )
}

/// `GET /api/devices/music/entries/{id}/items` (design 21b §4.2): an entry's episode list, for an
/// entry this phone has ticked, once the sweep has listed it - 404 otherwise. ETag = its version.
pub async fn listing(
    State(state): State<AppState>,
    Path(id): Path<i64>,
    Extension(AuthedDevice(device)): Extension<AuthedDevice>,
    headers: HeaderMap,
) -> Response {
    let listing = match crate::music_sweep::device_listing(&state.db, device.id, id).await {
        Ok(Some(listing)) => listing,
        Ok(None) => return StatusCode::NOT_FOUND.into_response(),
        Err(err) => return server_error(device.id, err, "couldn't read a music listing"),
    };
    versioned_json(
        device.id,
        &headers,
        &listing.version,
        &listing.json,
        "listing",
    )
}

/// A JSON document under its version: `ETag`, `If-None-Match` -> 304, `no-cache`, gzip when asked
/// for.
fn versioned_json(
    device_id: i64,
    headers: &HeaderMap,
    version: &str,
    json: &[u8],
    what: &str,
) -> Response {
    let etag = format!("\"{version}\"");
    let etag_value = HeaderValue::from_str(&etag).expect("hex in quotes is a valid header");
    let mut response_headers = HeaderMap::new();
    response_headers.insert(header::ETAG, etag_value);
    response_headers.insert(header::CACHE_CONTROL, HeaderValue::from_static("no-cache"));
    response_headers.insert(header::VARY, HeaderValue::from_static("Accept-Encoding"));
    if etag_matches(headers, &etag) {
        return (StatusCode::NOT_MODIFIED, response_headers).into_response();
    }
    response_headers.insert(
        header::CONTENT_TYPE,
        HeaderValue::from_static("application/json"),
    );
    if accepts_gzip(headers) {
        let mut encoder = flate2::write::GzEncoder::new(Vec::new(), flate2::Compression::default());
        let gzipped = encoder.write_all(json).and_then(|()| encoder.finish());
        match gzipped {
            Ok(body) => {
                response_headers.insert(header::CONTENT_ENCODING, HeaderValue::from_static("gzip"));
                return (response_headers, body).into_response();
            }
            Err(err) => tracing::warn!(device_id, %err, "couldn't gzip the music {what}"),
        }
    }
    (response_headers, json.to_vec()).into_response()
}

pub async fn cover(
    State(state): State<AppState>,
    Path(hash): Path<String>,
    Extension(AuthedDevice(device)): Extension<AuthedDevice>,
) -> Response {
    let Some(path) = crate::photos::path_for(&state.music_cover_dir, &hash) else {
        return StatusCode::NOT_FOUND.into_response();
    };
    // An entry's cover, else an episode's own picture (design 27 §1.5: the same route).
    let path = match crate::music::cover_in_library(&state.db, device.id, &hash).await {
        Ok(true) => path,
        Ok(false) => match crate::music::art_in_library(&state.db, device.id, &hash).await {
            Ok(true) => match crate::photos::path_for(&state.music_art_dir, &hash) {
                Some(path) => path,
                None => return StatusCode::NOT_FOUND.into_response(),
            },
            Ok(false) => return StatusCode::NOT_FOUND.into_response(),
            Err(err) => return server_error(device.id, err, "music art lookup failed"),
        },
        Err(err) => return server_error(device.id, err, "music cover lookup failed"),
    };
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
        .and_then(|plain| serde_json::from_slice::<crate::storytel::StoredLogin>(&plain).ok())
    else {
        // Sealed with another key (a restored database, a new key file): re-enter it in the PWA.
        tracing::warn!(
            device_id = device.id,
            "the stored Storytel login can't be opened with this server's key"
        );
        return StatusCode::SERVICE_UNAVAILABLE.into_response();
    };
    crate::security::record_phone_event_logged(
        &state.db,
        device.id,
        crate::security::PhoneLine::StorytelLoginSent,
        &format!("device {}: generation {generation}", device.id),
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

/// One answer of the token route (design 26c §2.6): always JSON with its `status`, `no-store`.
fn spotify_answer(status: StatusCode, body: serde_json::Value) -> Response {
    (
        status,
        [(header::CACHE_CONTROL, "no-store")],
        axum::Json(body),
    )
        .into_response()
}

fn spotify_status(status: StatusCode, word: &str) -> Response {
    spotify_answer(status, serde_json::json!({ "status": word }))
}

/// `POST /api/devices/music/spotify/token` (design 26c §2.6): the access token of the account this
/// phone plays as, with at least [crate::spotify::PHONE_TOKEN_MIN_LEFT] left (refreshed if
/// needed). 200 `ok` with the token, what is left of it and `"<account>:<generation>"` (a change
/// tells the phone to clear its web player); 404 `off` (no app, or the phone isn't bound); 409
/// `reauthorize` (the login ended, or Spotify refused the app); 429 `busy` with `retry_after_s`
/// (Spotify's 429, or this phone's [crate::spotify::PHONE_TOKENS_PER_WINDOW] asks an hour); 502
/// `unreachable` (the accounts service failed, or [crate::spotify::PHONE_TOKEN_BUDGET] passed - a
/// refresh still running then finishes on its own and is kept for the next ask);
/// 503 `key` (the music key can't open the login). The token is never written anywhere: not the
/// database, a log, an event or `music_state`.
pub async fn spotify_token(
    State(state): State<AppState>,
    Extension(AuthedDevice(device)): Extension<AuthedDevice>,
) -> Response {
    use crate::spotify::SpotifyError;
    let bound: Option<Option<i64>> = match sqlx::query_scalar(
        "SELECT music_spotify_account FROM device_policy WHERE device_id = ?",
    )
    .bind(device.id)
    .fetch_optional(&state.db)
    .await
    {
        Ok(bound) => bound,
        Err(err) => {
            tracing::error!(device_id = device.id, %err, "spotify binding lookup failed");
            return spotify_status(StatusCode::INTERNAL_SERVER_ERROR, "unreachable");
        }
    };
    let Some(account) = bound.flatten() else {
        return spotify_status(StatusCode::NOT_FOUND, "off");
    };
    match crate::spotify::app(&state).await {
        Ok(Some(_)) => {}
        Ok(None) => return spotify_status(StatusCode::NOT_FOUND, "off"),
        Err(err) => {
            tracing::error!(device_id = device.id, %err, "spotify app lookup failed");
            return spotify_status(StatusCode::INTERNAL_SERVER_ERROR, "unreachable");
        }
    }
    if let Err(retry_after) = state.spotify.phone_ask(device.id) {
        tracing::info!(
            device_id = device.id,
            "spotify: a phone's token asks are over its limit"
        );
        return spotify_answer(
            StatusCode::TOO_MANY_REQUESTS,
            serde_json::json!({ "status": "busy", "retry_after_s": retry_after }),
        );
    }
    let minted = tokio::time::timeout(
        state.spotify.phone_token_budget(),
        state
            .spotify
            .access_token(&state, account, crate::spotify::PHONE_TOKEN_MIN_LEFT),
    )
    .await;
    let err = match minted {
        Ok(Ok(minted)) => {
            tracing::debug!(device_id = device.id, account, "spotify: a phone's token");
            return spotify_answer(
                StatusCode::OK,
                serde_json::json!({
                    "status": "ok",
                    "access_token": minted.token.secret(),
                    "expires_in_s": minted.left.as_secs(),
                    "account": format!("{}:{}", minted.account, minted.generation),
                }),
            );
        }
        Ok(Err(err)) => err,
        Err(_) => SpotifyError::Fetch(crate::music_net::FetchError::Timeout),
    };
    tracing::info!(device_id = device.id, account, code = %err.code(), "spotify: no token for a phone");
    match err {
        SpotifyError::Off | SpotifyError::NoAccount => spotify_status(StatusCode::NOT_FOUND, "off"),
        SpotifyError::Reauthorize | SpotifyError::AppRefused => {
            spotify_status(StatusCode::CONFLICT, "reauthorize")
        }
        SpotifyError::RateLimited(retry_after) => spotify_answer(
            StatusCode::TOO_MANY_REQUESTS,
            serde_json::json!({ "status": "busy", "retry_after_s": retry_after.unwrap_or(30).max(1) }),
        ),
        SpotifyError::Key => spotify_status(StatusCode::SERVICE_UNAVAILABLE, "key"),
        SpotifyError::Database(_) => {
            spotify_status(StatusCode::INTERNAL_SERVER_ERROR, "unreachable")
        }
        _ => spotify_status(StatusCode::BAD_GATEWAY, "unreachable"),
    }
}

/// `POST /api/devices/music/storytel/token`'s body limit (design 58 §2.2): `{"stale": "<g>:<16 hex>"}`.
pub const STORYTEL_TOKEN_BODY_LIMIT: usize = 256;

/// `stale` from the token route's body, read leniently (design 58 §2.2): anything but a JSON object
/// with a valid session is a plain ask.
fn stale_session(body: &[u8]) -> Option<String> {
    let body: serde_json::Value = serde_json::from_slice(body).ok()?;
    let stale = body.get("stale")?.as_str()?;
    crate::storytel::valid_session(stale).then(|| stale.to_string())
}

/// `POST /api/devices/music/storytel/token` (design 58 §2.2): the server's own Storytel JWT for
/// this phone, `{"stale": "<session>"}` after a 401 with that session (else `{}` or nothing; the
/// body is read as bytes, so any content type is a plain ask). Checked in this order: this phone's
/// switch and a stored login (404 `off`, not counted), the phone's 20 asks an hour (429 `busy`), the
/// key by its fingerprint only (503 `key`, nothing opened or written), then
/// [crate::storytel::Account::phone_jwt] within [crate::storytel::PHONE_JWT_BUDGET] as a task of
/// its own (a login still running then is kept for the next ask). 200 `ok` with the JWT,
/// `expires_in_s` (what is left minus a minute) and the session; 409 `refused` with
/// `retry_after_s`; 429 `busy` with `retry_after_s`; 502 `unreachable`; 500 `unreachable` on a
/// database error. The JWT, its session and the body are never logged or stored, and no security
/// event is written per ask (server review I11).
pub async fn storytel_token(
    State(state): State<AppState>,
    Extension(AuthedDevice(device)): Extension<AuthedDevice>,
    body: Result<axum::body::Bytes, axum::extract::rejection::BytesRejection>,
) -> Response {
    use crate::storytel::StorytelError;
    let stale = body.ok().and_then(|body| stale_session(&body));
    let answer = |status: StatusCode, body: serde_json::Value| {
        tracing::debug!(
            device_id = device.id,
            status = body["status"].as_str().unwrap_or_default(),
            "storytel: a phone's token ask"
        );
        spotify_answer(status, body)
    };
    let word =
        |status: StatusCode, word: &str| answer(status, serde_json::json!({ "status": word }));
    let switch: Result<Option<bool>, sqlx::Error> =
        sqlx::query_scalar("SELECT music_storytel FROM device_policy WHERE device_id = ?")
            .bind(device.id)
            .fetch_optional(&state.db)
            .await;
    let stored: Result<(bool, Option<String>), sqlx::Error> = sqlx::query_as(
        "SELECT ciphertext IS NOT NULL, key_fingerprint FROM music_storytel WHERE id = 1",
    )
    .fetch_one(&state.db)
    .await;
    let (switch, (stored, fingerprint)) = match (switch, stored) {
        (Ok(switch), Ok(stored)) => (switch, stored),
        (Err(err), _) | (_, Err(err)) => {
            tracing::error!(device_id = device.id, %err, "storytel token lookup failed");
            return word(StatusCode::INTERNAL_SERVER_ERROR, "unreachable");
        }
    };
    if switch != Some(true) || !stored {
        return word(StatusCode::NOT_FOUND, "off");
    }
    if let Err(retry_after) = state.storytel.phone_ask(device.id) {
        tracing::info!(
            device_id = device.id,
            "storytel: a phone's token asks are over its limit"
        );
        return answer(
            StatusCode::TOO_MANY_REQUESTS,
            serde_json::json!({ "status": "busy", "retry_after_s": retry_after }),
        );
    }
    let key_fits = state
        .music_key
        .as_ref()
        .is_some_and(|key| fingerprint.as_deref() == Some(key.fingerprint.as_str()));
    if !key_fits {
        return word(StatusCode::SERVICE_UNAVAILABLE, "key");
    }
    let task = tokio::spawn(
        state
            .storytel
            .clone()
            .phone_jwt(state.clone(), device.id, stale),
    );
    let err = match tokio::time::timeout(state.storytel.phone_budget(), task).await {
        Ok(Ok(Ok(jwt))) => {
            return answer(
                StatusCode::OK,
                serde_json::json!({
                    "status": "ok",
                    "jwt": jwt.jwt.secret(),
                    "expires_in_s": jwt.left.saturating_sub(crate::storytel::MARGIN).as_secs(),
                    "session": jwt.session,
                }),
            );
        }
        Ok(Ok(Err(err))) => err,
        Ok(Err(err)) => {
            tracing::error!(device_id = device.id, %err, "storytel: the token task failed");
            StorytelError::Database("the token task failed".to_string())
        }
        // The task goes on: a login it started is kept for the next ask.
        Err(_) => StorytelError::Fetch(crate::music_net::FetchError::Timeout),
    };
    tracing::info!(device_id = device.id, code = %err.code(), "storytel: no session for a phone");
    let busy = |seconds: u64| {
        answer(
            StatusCode::TOO_MANY_REQUESTS,
            serde_json::json!({ "status": "busy", "retry_after_s": seconds }),
        )
    };
    let refused = |seconds: u64| {
        answer(
            StatusCode::CONFLICT,
            serde_json::json!({ "status": "refused", "retry_after_s": seconds }),
        )
    };
    let until_secs = |until: chrono::DateTime<chrono::Utc>| {
        crate::storytel::wait_secs(
            (until - state.music_sweep.now())
                .to_std()
                .unwrap_or_default(),
        )
    };
    match err {
        StorytelError::NotConfigured => word(StatusCode::NOT_FOUND, "off"),
        StorytelError::KeyUnusable => word(StatusCode::SERVICE_UNAVAILABLE, "key"),
        StorytelError::Cooldown { until } => refused(until_secs(until)),
        StorytelError::Refused(_) => {
            refused(crate::storytel::LOGIN_COOLDOWN.num_seconds().unsigned_abs())
        }
        StorytelError::Busy { wait } => busy(crate::storytel::wait_secs(wait)),
        // Defensive: `phone_jwt` answers a running backoff as `Busy` under the login lock.
        StorytelError::Backoff { until } => busy(until_secs(until)),
        StorytelError::Database(_) => word(StatusCode::INTERNAL_SERVER_ERROR, "unreachable"),
        _ => word(StatusCode::BAD_GATEWAY, "unreachable"),
    }
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
