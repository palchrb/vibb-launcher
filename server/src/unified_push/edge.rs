//! The outer layer of the public push routes (design 108 §3.2), on the phone listener only - not
//! `limits::device_edge`, so a push flood can't block an enrolment and the other way round:
//!
//! - **the client first** (R1-SR M1): a per-client cap of [PER_CLIENT] requests at once (`net::limit_key` of the
//!   trusted-proxy client, as the rest of the phone listener counts) and its request bucket (`Rates::client_request`,
//!   L3), both before any database work;
//! - **then the body**, within [BODY_READ], at most [BODY_LIMIT] bytes (413 above), under its own bound of
//!   [BODY_READS] reads at once (M6);
//! - **only then a slot** of the routes' own [IN_FLIGHT] for the handler and its answer within [ANSWER_TIMEOUT]: a slow
//!   body never holds one, so a few slow clients can't keep every real push out (503);
//! - the device API's response headers (`nosniff`, `default-src 'none'; sandbox`, `no-store`).
//!
//! A proxy in front should buffer request bodies so it, not the server, holds slow clients (DEPLOY.md, Caddy's
//! `request_buffers`).
//!
//! The resolved client rides to the handlers as [UpClient]. Nothing here logs a path or a body: the path is a
//! capability.

use std::time::Duration;

use axum::body::Body;
use axum::extract::{Request, State};
use axum::http::{HeaderValue, StatusCode, header};
use axum::middleware::Next;
use axum::response::{IntoResponse, Response};

use crate::AppState;

/// Push requests in their handler at once, every client together (after their bodies are in).
pub const IN_FLIGHT: usize = 32;
/// Bodies being read at once, every client together.
pub const BODY_READS: usize = 256;
/// Push requests in flight at once from one client (`net::limit_key`).
pub const PER_CLIENT: u32 = 4;
/// The whole body must arrive within this.
pub const BODY_READ: Duration = Duration::from_secs(3);
/// Then the answer within this.
pub const ANSWER_TIMEOUT: Duration = Duration::from_secs(10);
/// One byte over a body's limit, so the handlers can say 413 ([super::store::BODY_MAX] + 1).
pub const BODY_LIMIT: usize = super::store::BODY_MAX + 1;

/// The client a push came from (`net::NetConfig::client`'s limit key), for the ban.
#[derive(Clone, Debug)]
pub struct UpClient {
    pub key: String,
}

fn status(code: StatusCode, retry_after: Option<&'static str>) -> Response {
    match retry_after {
        Some(secs) => (code, [(header::RETRY_AFTER, secs)]).into_response(),
        None => code.into_response(),
    }
}

pub async fn edge(State(state): State<AppState>, request: Request, next: Next) -> Response {
    let mut response = admit(&state, request, next).await;
    let headers = response.headers_mut();
    headers.insert(header::CACHE_CONTROL, HeaderValue::from_static("no-store"));
    headers.insert(
        header::X_CONTENT_TYPE_OPTIONS,
        HeaderValue::from_static("nosniff"),
    );
    headers.insert(
        header::CONTENT_SECURITY_POLICY,
        HeaderValue::from_static("default-src 'none'; sandbox"),
    );
    response
}

async fn admit(state: &AppState, request: Request, next: Next) -> Response {
    let up = &state.unified_push;
    let client =
        crate::gate::peer_of(&request).map(|peer| state.net.client(request.headers(), peer.ip()));
    if let Some(client) = &client {
        state.limits.observe_peer(client);
    }
    let key = client.map(|c| c.key()).unwrap_or_default();
    let Some(_client_slot) = up.enter_client(&key) else {
        return status(StatusCode::TOO_MANY_REQUESTS, Some("1"));
    };
    if let Err(refused) = up.rates.client_request(&key) {
        return super::too_many(refused);
    }
    let (mut parts, body) = request.into_parts();
    let bytes = {
        let Ok(_reading) = up.body_reads().try_acquire_owned() else {
            return status(StatusCode::SERVICE_UNAVAILABLE, Some("5"));
        };
        match tokio::time::timeout(BODY_READ, axum::body::to_bytes(body, BODY_LIMIT)).await {
            Ok(Ok(bytes)) => bytes,
            // Over the limit: the rest is never read.
            Ok(Err(_)) => return status(StatusCode::PAYLOAD_TOO_LARGE, None),
            // A slow body: 408, which Mastodon and Synapse retry.
            Err(_) => return status(StatusCode::REQUEST_TIMEOUT, None),
        }
    };
    let Ok(_slot) = up.in_flight().try_acquire_owned() else {
        return status(StatusCode::SERVICE_UNAVAILABLE, Some("5"));
    };
    parts.extensions.insert(UpClient { key });
    let request = Request::from_parts(parts, Body::from(bytes));
    match tokio::time::timeout(ANSWER_TIMEOUT, next.run(request)).await {
        Ok(response) => response,
        Err(_) => status(StatusCode::SERVICE_UNAVAILABLE, Some("30")),
    }
}
