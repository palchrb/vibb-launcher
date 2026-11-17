// Ported from ntfy (https://github.com/binwiederhier/ntfy), `server/server.go`'s `handlePublishInternal` (the
// UnifiedPush branch) and `parsePublishParams` (the UnifiedPush detection) at commit a098f52, by Philipp C. Heckel and
// the ntfy contributors, licensed under the Apache License 2.0 (see `Apache-2.0.txt` in this directory); changed
// here: rewritten in Rust for Vibb's push server (see `README.md`). Distributed as part of this server under the GNU
// GPL v3.0 or later.

//! `POST /up/{secret}` (design 108 §3.4): RFC 8030 publish to a capability URL, with ntfy's rules for app servers.
//!
//! - **201** `Location: <origin>/up/m/<key>` and `TTL` when queued (or when a TTL 0 push is dropped because the
//!   phone's stream is closed); the registration's oldest pending row goes when its queue is full.
//! - **400** for an empty body, **413** above 4096 bytes.
//! - **410** for a gone secret (tombstoned, not counted toward the ban, M5) - but **404** when the body is a Matrix
//!   notify (L1: common-proxies' gateway rejects a pushkey only on 401-404) - and for a never-seen one (counted).
//! - **429** with `Retry-After` when the registration's or the phone's bucket is empty (`rate`); **507** when the
//!   server-wide store is full. **A transient refusal is a 5xx, never a 4xx** (ntfy's comment: Mastodon's
//!   `push_notification_worker.rb` deletes the subscription on any 4xx but 408/429), and **gone is 410** (Misskey and
//!   its forks delete only on 410).
//!
//! Every push to `/up/` is a UnifiedPush push: ntfy's detection (`Content-Encoding: aes128gcm` or `?up=1`) isn't
//! needed. The body is kept as bytes (ntfy's base64 envelope isn't needed: the device API carries base64 anyway); a
//! Matrix notify that came through an outside gateway is minimised as the gateway's is (Q8) and noted
//! (`outside_gateway_at`, R2's warning). `Urgency` is stored and passed on; `Topic` replaces the pending row with the
//! same topic. VAPID is not checked yet (R3), as ntfy doesn't.

use axum::Extension;
use axum::body::Bytes;
use axum::extract::{Path, State};
use axum::http::{HeaderMap, HeaderValue, StatusCode, header};
use axum::response::{IntoResponse, Response};

use super::edge::UpClient;
use super::store::{self, Lookup};
use crate::AppState;

/// The `TTL` header clamped to 0..=a day (Q10); without one, the whole day (RFC 8030 requires the header; ntfy and
/// common-proxies tolerate its absence). A value that isn't a number reads as absent.
pub fn ttl(headers: &HeaderMap) -> i64 {
    headers
        .get("ttl")
        .and_then(|v| v.to_str().ok())
        .and_then(|v| v.trim().parse::<i64>().ok())
        .map_or(store::TTL_MAX_SECS, |ttl| ttl.clamp(0, store::TTL_MAX_SECS))
}

/// The `Urgency` header (RFC 8030 §5.3), anything else `normal`.
pub fn urgency(headers: &HeaderMap) -> &'static str {
    match headers
        .get("urgency")
        .and_then(|v| v.to_str().ok())
        .map(|v| v.trim().to_ascii_lowercase())
        .as_deref()
    {
        Some("very-low") => "very-low",
        Some("low") => "low",
        Some("high") => "high",
        _ => "normal",
    }
}

/// The `Topic` header (RFC 8030 §5.4): at most 32 base64url characters; anything else is ignored (a 4xx would cost
/// the app its subscription).
pub fn topic(headers: &HeaderMap) -> Option<String> {
    headers
        .get("topic")
        .and_then(|v| v.to_str().ok())
        .map(str::trim)
        .filter(|t| {
            !t.is_empty()
                && t.len() <= 32
                && t.bytes()
                    .all(|b| b.is_ascii_alphanumeric() || b == b'-' || b == b'_')
        })
        .map(str::to_string)
}

pub async fn publish(
    State(state): State<AppState>,
    Extension(client): Extension<UpClient>,
    Path(secret): Path<String>,
    headers: HeaderMap,
    body: Bytes,
) -> Response {
    if body.len() > store::BODY_MAX {
        return StatusCode::PAYLOAD_TOO_LARGE.into_response();
    }
    if body.is_empty() {
        return StatusCode::BAD_REQUEST.into_response();
    }
    let registration = match store::lookup(&state.db, &secret).await {
        Ok(Lookup::Live(registration)) => registration,
        Ok(Lookup::Gone) => {
            return if super::matrix::is_matrix_shaped(&body) {
                StatusCode::NOT_FOUND
            } else {
                StatusCode::GONE
            }
            .into_response();
        }
        Ok(Lookup::Unknown) => {
            if let Some(refused) = state.unified_push.rates.banned(&client.key) {
                return super::too_many(refused);
            }
            state.unified_push.rates.never_seen(&client.key);
            return StatusCode::GONE.into_response();
        }
        Err(err) => {
            tracing::error!(%err, "app push: couldn't look a secret up");
            return StatusCode::SERVICE_UNAVAILABLE.into_response();
        }
    };
    let matrix = super::matrix::is_matrix_shaped(&body);
    let minimised = if matrix {
        super::matrix::minimise(&body)
    } else {
        None
    };
    if matrix
        && let Err(err) = sqlx::query(
            "UPDATE up_registrations SET outside_gateway_at = datetime('now') WHERE id = ?",
        )
        .bind(registration.id)
        .execute(&state.db)
        .await
    {
        tracing::warn!(%err, "app push: couldn't note an outside gateway");
    }
    let ttl = ttl(&headers);
    let topic = topic(&headers);
    let push = store::Push {
        body: minimised.as_deref().unwrap_or(&body),
        urgency: urgency(&headers),
        topic: topic.as_deref(),
        ttl_secs: ttl,
    };
    let key = match super::accept(&state, &registration, &push).await {
        Ok(super::Accepted::Queued(key)) => key,
        // TTL 0 and the phone's stream closed: answered as accepted, never stored (RFC 8030 §5.2).
        Ok(super::Accepted::Dropped) => store::random_key(),
        Err(refusal) => return refusal.into_response(),
    };
    let origin = super::settings(&state.db)
        .await
        .ok()
        .and_then(|s| s.public_origin)
        .unwrap_or_default();
    let mut response = StatusCode::CREATED.into_response();
    if let Ok(location) = HeaderValue::from_str(&format!("{origin}/up/m/{key}")) {
        response.headers_mut().insert(header::LOCATION, location);
    }
    response.headers_mut().insert("ttl", HeaderValue::from(ttl));
    response
}

#[cfg(test)]
mod tests {
    use super::*;

    fn headers(pairs: &[(&'static str, &str)]) -> HeaderMap {
        let mut map = HeaderMap::new();
        for (name, value) in pairs {
            map.insert(*name, HeaderValue::from_str(value).unwrap());
        }
        map
    }

    /// §3.4's headers: TTL clamped (none = a day), Urgency's four words (else normal), Topic's form.
    #[test]
    fn the_headers() {
        assert_eq!(ttl(&headers(&[])), 86_400);
        assert_eq!(ttl(&headers(&[("ttl", "60")])), 60);
        assert_eq!(ttl(&headers(&[("ttl", "0")])), 0);
        assert_eq!(ttl(&headers(&[("ttl", "999999")])), 86_400);
        assert_eq!(ttl(&headers(&[("ttl", "-5")])), 0);
        assert_eq!(ttl(&headers(&[("ttl", "soon")])), 86_400);
        assert_eq!(urgency(&headers(&[])), "normal");
        for word in ["very-low", "low", "normal", "high"] {
            assert_eq!(urgency(&headers(&[("urgency", word)])), word);
        }
        assert_eq!(urgency(&headers(&[("urgency", "HIGH")])), "high");
        assert_eq!(urgency(&headers(&[("urgency", "now")])), "normal");
        assert_eq!(
            topic(&headers(&[("topic", "chat-1")])).as_deref(),
            Some("chat-1")
        );
        assert_eq!(topic(&headers(&[("topic", "has space")])), None);
        assert_eq!(topic(&headers(&[("topic", &"a".repeat(33))])), None);
        assert_eq!(topic(&headers(&[])), None);
    }
}
