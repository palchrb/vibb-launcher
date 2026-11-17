// Ported from ntfy (https://github.com/binwiederhier/ntfy), `server/server_matrix.go` (`matrixRequest`,
// `newRequestFromMatrixJSON`, `writeMatrixDiscoveryResponse`, `writeMatrixResponse`, `errMatrixPushkeyRejected`) and
// `server/server.go` (`transformMatrixJSON`, `handleMatrixDiscovery`, `handlePublishMatrix`) at commit a098f52, by
// Philipp C. Heckel and the ntfy contributors, licensed under the Apache License 2.0 (see `Apache-2.0.txt` in this
// directory); changed here: rewritten in Rust for Vibb's push server (see `README.md`). Distributed as part of this
// server under the GNU GPL v3.0 or later.

//! The Matrix push gateway (design 108 §3.3, §3.4): `GET /_matrix/push/v1/notify` is the UnifiedPush gateway
//! discovery, `POST` takes a homeserver's notify and queues it for the registration its pushkey names.
//!
//! ntfy's rule, ported: the body to the size limit, else **413**; invalid JSON or no `devices[0].pushkey` is **400**;
//! **only `devices[0]`** (ntfy #316: Synapse sends one device per request); a pushkey that doesn't start with our
//! base (`<origin>/up/`, the current origin or for 30 days the previous one, the host compared case-insensitively;
//! M4) is **rejected** (200 `{"rejected":[pushkey]}`, ntfy's no-open-relay rule), and so is a gone or never-seen
//! secret, as ntfy's abandoned topic is; every other refusal passes through as its status (a 429 or 507 is transient
//! to the homeserver). **Changed from ntfy (Q8):** the stored body is the notify JSON minimised ([minimise]).

use axum::Extension;
use axum::body::Bytes;
use axum::extract::State;
use axum::http::{StatusCode, header};
use axum::response::{IntoResponse, Response};
use serde::Deserialize;
use serde::de::{Deserializer, MapAccess, Visitor};
use serde_json::value::RawValue;

use super::edge::UpClient;
use super::store::{self, Lookup};
use crate::AppState;

/// `matrixRequest`: only the pushkey is read.
#[derive(Deserialize)]
struct MatrixRequest {
    notification: Option<Notification>,
}

#[derive(Deserialize)]
struct Notification {
    devices: Option<Vec<Option<Device>>>,
}

#[derive(Deserialize)]
struct Device {
    pushkey: Option<String>,
}

/// `errHTTPBadRequestMatrixMessageInvalid` (40019): the body isn't a Matrix notify with `devices[0].pushkey`.
#[derive(Debug, PartialEq, Eq)]
pub struct Invalid;

/// `devices[0].pushkey` of a notify body (ntfy ignores the other devices, #316).
pub fn pushkey_of(body: &[u8]) -> Result<String, Invalid> {
    let request: MatrixRequest = serde_json::from_slice(body).map_err(|_| Invalid)?;
    request
        .notification
        .and_then(|n| n.devices)
        .and_then(|devices| devices.into_iter().next().flatten())
        .and_then(|device| device.pushkey)
        .filter(|pushkey| !pushkey.is_empty())
        .ok_or(Invalid)
}

/// Whether a body is a Matrix notify (L1: one that came to `/up/` through an outside gateway).
pub fn is_matrix_shaped(body: &[u8]) -> bool {
    pushkey_of(body).is_ok()
}

/// What Q8 keeps of `notification` (R1-SR L6: an allow-list, so a key a homeserver adds is never stored): Element X's
/// `PushDataUnifiedPush` reads `event_id`, `room_id`, `counts.unread` and `prio` (its session comes from the
/// registration, never the body); `type` and the devices' `app_id`, `data` and `pushkey_ts` are kept for other Matrix
/// clients. Gone: a message's `content`, `sender`, `sender_display_name`, `room_name`, `room_alias`, `user_is_target`
/// and anything else - and each device's `pushkey` (the endpoint itself, which no client reads back) and `tweaks`.
pub const KEPT: [&str; 6] = ["event_id", "room_id", "counts", "prio", "type", "devices"];
/// What is kept of each `devices[]` entry.
pub const KEPT_DEVICE: [&str; 3] = ["app_id", "data", "pushkey_ts"];

/// A JSON object's members in their order, each value kept as its raw text.
struct Ordered(Vec<(String, Box<RawValue>)>);

impl<'de> Deserialize<'de> for Ordered {
    fn deserialize<D: Deserializer<'de>>(deserializer: D) -> Result<Self, D::Error> {
        struct Members;
        impl<'de> Visitor<'de> for Members {
            type Value = Ordered;
            fn expecting(&self, f: &mut std::fmt::Formatter) -> std::fmt::Result {
                f.write_str("a JSON object")
            }
            fn visit_map<A: MapAccess<'de>>(self, mut map: A) -> Result<Ordered, A::Error> {
                let mut members = Vec::new();
                while let Some((key, value)) = map.next_entry::<String, Box<RawValue>>()? {
                    members.push((key, value));
                }
                Ok(Ordered(members))
            }
        }
        deserializer.deserialize_map(Members)
    }
}

impl Ordered {
    fn write(&self, out: &mut String, keep: &[&str]) {
        out.push('{');
        let mut first = true;
        for (key, value) in &self.0 {
            if !keep.contains(&key.as_str()) {
                continue;
            }
            if !first {
                out.push(',');
            }
            first = false;
            out.push_str(&serde_json::to_string(key).expect("a string serializes"));
            out.push(':');
            out.push_str(value.get());
        }
        out.push('}');
    }
}

/// The notify JSON as Q8 keeps it: `{"notification": {...}}` with only [KEPT], each device only [KEPT_DEVICE] (a
/// device that isn't an object is dropped), every kept member in its order with its value's bytes as sent; only the
/// whitespace between members goes. `None` when the body isn't such an object.
pub fn minimise(body: &[u8]) -> Option<Vec<u8>> {
    let top: Ordered = serde_json::from_slice(body).ok()?;
    let notification = top
        .0
        .iter()
        .find(|(key, _)| key == "notification")
        .map(|(_, value)| value)?;
    let notification: Ordered = serde_json::from_str(notification.get()).ok()?;
    let mut out = String::with_capacity(body.len());
    out.push_str("{\"notification\":{");
    let mut first = true;
    for (key, value) in &notification.0 {
        if !KEPT.contains(&key.as_str()) {
            continue;
        }
        if !first {
            out.push(',');
        }
        first = false;
        out.push_str(&serde_json::to_string(key).expect("a string serializes"));
        out.push(':');
        if key == "devices" {
            let devices: Vec<Box<RawValue>> = serde_json::from_str(value.get()).unwrap_or_default();
            out.push('[');
            let mut first_device = true;
            for device in devices {
                let Ok(device) = serde_json::from_str::<Ordered>(device.get()) else {
                    continue;
                };
                if !first_device {
                    out.push(',');
                }
                first_device = false;
                device.write(&mut out, &KEPT_DEVICE);
            }
            out.push(']');
        } else {
            out.push_str(value.get());
        }
    }
    out.push_str("}}");
    Some(out.into_bytes())
}

/// `writeMatrixResponse`: 200 `{"rejected":[...]}` with the one rejected pushkey, or none.
pub fn response(rejected: Option<&str>) -> Response {
    let body = serde_json::json!({ "rejected": rejected.into_iter().collect::<Vec<_>>() });
    (
        StatusCode::OK,
        [(header::CONTENT_TYPE, "application/json")],
        format!("{body}\n"),
    )
        .into_response()
}

/// The secret a pushkey names, if it starts with `<origin>/up/` for one of `origins` (scheme and host compared
/// case-insensitively, M4); the rest up to a query or fragment. `None` = not ours: rejected.
pub fn secret_of<'a>(pushkey: &'a str, origins: &[String]) -> Option<&'a str> {
    origins.iter().find_map(|origin| {
        let prefix = format!("{origin}/up/");
        let head = pushkey.get(..prefix.len())?;
        if !head.eq_ignore_ascii_case(&prefix) {
            return None;
        }
        let rest = &pushkey[prefix.len()..];
        Some(rest.split(['?', '#']).next().unwrap_or(rest))
    })
}

/// `GET /_matrix/push/v1/notify` (`handleMatrixDiscovery`): `{"unifiedpush":{"gateway":"matrix"}}` while a public
/// origin is set, else 404 (Element X then uses the public gateway, which gets 404 from `/up/`). The time is kept for
/// `/settings/push`'s "last seen from outside".
pub async fn discovery(State(state): State<AppState>) -> Response {
    let settings = match super::settings(&state.db).await {
        Ok(settings) => settings,
        Err(err) => {
            tracing::error!(%err, "app push: couldn't read the settings");
            return StatusCode::SERVICE_UNAVAILABLE.into_response();
        }
    };
    if settings.public_origin.is_none() {
        return StatusCode::NOT_FOUND.into_response();
    }
    state.unified_push.note_discovery(&state.db).await;
    (
        StatusCode::OK,
        [(header::CONTENT_TYPE, "application/json")],
        "{\"unifiedpush\":{\"gateway\":\"matrix\"}}\n",
    )
        .into_response()
}

/// `POST /_matrix/push/v1/notify` (`transformMatrixJSON` + `handlePublishMatrix`).
pub async fn notify(
    State(state): State<AppState>,
    Extension(client): Extension<UpClient>,
    body: Bytes,
) -> Response {
    if body.len() > store::BODY_MAX {
        return StatusCode::PAYLOAD_TOO_LARGE.into_response();
    }
    let Ok(pushkey) = pushkey_of(&body) else {
        return StatusCode::BAD_REQUEST.into_response();
    };
    let origins = match super::accepted_origins(&state.db).await {
        Ok(origins) => origins,
        Err(err) => {
            tracing::error!(%err, "app push: couldn't read the settings");
            return StatusCode::SERVICE_UNAVAILABLE.into_response();
        }
    };
    let Some(secret) = secret_of(&pushkey, &origins) else {
        return response(Some(&pushkey));
    };
    let registration = match store::lookup(&state.db, secret).await {
        Ok(Lookup::Live(registration)) => registration,
        Ok(Lookup::Gone) => return response(Some(&pushkey)),
        Ok(Lookup::Unknown) => {
            if let Some(refused) = state.unified_push.rates.banned(&client.key) {
                return super::too_many(refused);
            }
            state.unified_push.rates.never_seen(&client.key);
            return response(Some(&pushkey));
        }
        Err(err) => {
            tracing::error!(%err, "app push: couldn't look a pushkey up");
            return StatusCode::SERVICE_UNAVAILABLE.into_response();
        }
    };
    // R3 (§4.5): a homeserver never signs, so a keyed registration's pushkey is not ours through here - else the
    // gateway would bypass the check. Stamped like a refused push (L9), so the PWA's warning shows.
    match registration.vapid.as_deref().map(super::vapid::stored_key) {
        Some(super::vapid::Stored::Key(_)) => {
            store::note_refusal(&state.db, &registration, "unsigned").await;
            return response(Some(&pushkey));
        }
        // The point check couldn't run: not taken unchecked (L1); the homeserver retries.
        Some(super::vapid::Stored::Unavailable) => return super::vapid_unavailable(),
        _ => {}
    }
    let Some(minimised) = minimise(&body) else {
        return StatusCode::BAD_REQUEST.into_response();
    };
    let urgency = if prio_is_high(&body) {
        "high"
    } else {
        "normal"
    };
    let push = store::Push {
        body: &minimised,
        urgency,
        topic: None,
        ttl_secs: store::TTL_MAX_SECS,
    };
    match super::accept(&state, &registration, &push).await {
        Ok(accepted) => {
            // R2: the push came through our own gateway, which ends the outside-gateway mark. Noted only for a
            // queued push, at most once a minute (as `outside_gateway_at` is).
            if matches!(accepted, super::Accepted::Queued(_))
                && let Err(err) = sqlx::query(
                    "UPDATE up_registrations SET gateway_at = datetime('now') WHERE id = ? \
                     AND (gateway_at IS NULL OR gateway_at < datetime('now', '-1 minute'))",
                )
                .bind(registration.id)
                .execute(&state.db)
                .await
            {
                tracing::warn!(%err, "app push: couldn't note our own gateway");
            }
            response(None)
        }
        Err(refusal) => refusal.into_response(),
    }
}

/// The notify's `prio` is `high` (Matrix's "high", else "low"): the message's urgency for the phone.
fn prio_is_high(body: &[u8]) -> bool {
    #[derive(Deserialize)]
    struct Prio {
        notification: Option<PrioOf>,
    }
    #[derive(Deserialize)]
    struct PrioOf {
        prio: Option<String>,
    }
    serde_json::from_slice::<Prio>(body)
        .ok()
        .and_then(|p| p.notification)
        .and_then(|n| n.prio)
        .is_some_and(|prio| prio == "high")
}

#[cfg(test)]
mod tests {
    use super::*;

    const BASE: &str = "https://ntfy.sh";
    /// ntfy's test body, its pushkey on our form.
    fn body(pushkey: &str) -> String {
        format!(
            r##"{{"notification":{{"content":{{"body":"I'm floating in a most peculiar way.","msgtype":"m.text"}},"counts":{{"missed_calls":1,"unread":2}},"devices":[{{"app_id":"org.matrix.matrixConsole.ios","data":{{}},"pushkey":"{pushkey}","pushkey_ts":12345678,"tweaks":{{"sound":"bing"}}}}],"event_id":"$3957tyerfgewrf384","prio":"high","room_alias":"#exampleroom:matrix.org","room_id":"!slw48wfj34rtnrf:example.com","room_name":"Mission Control","sender":"@exampleuser:matrix.org","sender_display_name":"Major Tom","type":"m.room.message"}}}}"##
        )
    }
    const SECRET: &str = "FakeSecretForTheMatrixTest1";

    fn origins() -> Vec<String> {
        vec![BASE.to_string()]
    }

    /// `TestMatrix_NewRequestFromMatrixJSON_Success`: the pushkey is read and names our secret.
    #[test]
    fn success() {
        let pushkey = format!("{BASE}/up/{SECRET}");
        assert_eq!(pushkey_of(body(&pushkey).as_bytes()).unwrap(), pushkey);
        assert_eq!(secret_of(&pushkey, &origins()), Some(SECRET));
    }

    /// `TestMatrix_NewRequestFromMatrixJSON_TooLarge`: the size check is the edge's and the handler's (4096; the
    /// gateway_rows test posts 4097 bytes); here the limit's constant.
    #[test]
    fn too_large() {
        assert_eq!(store::BODY_MAX, 4096);
        assert_eq!(super::super::edge::BODY_LIMIT, 4097);
    }

    /// `TestMatrix_NewRequestFromMatrixJSON_InvalidJSON`.
    #[test]
    fn invalid_json() {
        assert_eq!(pushkey_of(b"this is not json"), Err(Invalid));
    }

    /// `TestMatrix_NewRequestFromMatrixJSON_NotAMatrixMessage`, and the ways `devices[0].pushkey` can be missing.
    #[test]
    fn not_a_matrix_message() {
        for text in [
            r#"{"message":"this is not a matrix message, but valid json"}"#,
            r#"{"notification":{}}"#,
            r#"{"notification":{"devices":[]}}"#,
            r#"{"notification":{"devices":[null]}}"#,
            r#"{"notification":{"devices":[{"pushkey":""}]}}"#,
            r#"[1, 2]"#,
        ] {
            assert_eq!(pushkey_of(text.as_bytes()), Err(Invalid), "{text}");
            assert!(!is_matrix_shaped(text.as_bytes()));
        }
        // Only devices[0] counts (ntfy #316).
        let two = r#"{"notification":{"devices":[{"pushkey":"https://a.example/up/x"},{"pushkey":"https://b.example/up/y"}]}}"#;
        assert_eq!(
            pushkey_of(two.as_bytes()).unwrap(),
            "https://a.example/up/x"
        );
    }

    /// `TestMatrix_NewRequestFromMatrixJSON_MismatchingPushKey`: another host's pushkey isn't ours (rejected).
    #[test]
    fn mismatching_pushkey() {
        let pushkey = format!("https://ntfy.example.com/up/{SECRET}");
        assert_eq!(secret_of(&pushkey, &origins()), None);
        // Nor is our host without `/up/`, or another path.
        assert_eq!(secret_of(&format!("{BASE}/{SECRET}"), &origins()), None);
        assert_eq!(
            secret_of("https://ntfy.sh.evil.example/up/x", &origins()),
            None
        );
    }

    /// `TestMatrix_NewRequestFromMatrixJSON_InvalidTopic`, as our secrets have it: what follows `/up/` up to a query is
    /// the secret, and anything not a secret's form is never looked up (`store::lookup` answers Unknown).
    #[test]
    fn invalid_secret() {
        for pushkey in [
            format!("{BASE}/up/sec%00ret?up=1"),
            format!("{BASE}/up/one,two"),
            format!("{BASE}/up/{SECRET}/json"),
            format!("{BASE}/up/"),
            format!("{BASE}/up/{}", "a".repeat(65)),
        ] {
            let secret = secret_of(&pushkey, &origins()).unwrap();
            assert!(!store::valid_secret(secret), "{pushkey}");
        }
        assert_eq!(
            secret_of(&format!("{BASE}/up/{SECRET}?up=1"), &origins()),
            Some(SECRET)
        );
    }

    /// M4: the previous origin is one more accepted prefix (`accepted_origins` adds it for 30 days); the scheme and
    /// host are compared case-insensitively. `tests::unified_push::gateway_rows` runs it end to end.
    #[test]
    fn previous_origin() {
        let both = vec![
            "https://new.example".to_string(),
            "https://old.example".to_string(),
        ];
        assert_eq!(
            secret_of(&format!("https://OLD.Example/up/{SECRET}"), &both),
            Some(SECRET)
        );
        assert_eq!(
            secret_of(&format!("HTTPS://new.example/up/{SECRET}"), &both),
            Some(SECRET)
        );
        assert_eq!(
            secret_of(&format!("https://old.example/up/{SECRET}"), &both[..1]),
            None,
            "past its 30 days"
        );
    }

    /// `TestMatrix_WriteMatrixDiscoveryResponse` lives in `tests::unified_push` (the route answers it while an origin
    /// is set); `TestMatrix_WriteMatrixError` and `TestMatrix_WriteMatrixSuccess`:
    #[tokio::test]
    async fn the_responses() {
        use http_body_util::BodyExt;
        let read = |r: Response| async move {
            assert_eq!(r.status(), StatusCode::OK);
            String::from_utf8(r.into_body().collect().await.unwrap().to_bytes().to_vec()).unwrap()
        };
        assert_eq!(
            read(response(Some("https://ntfy.example.com/upABCDEFGHI?up=1"))).await,
            "{\"rejected\":[\"https://ntfy.example.com/upABCDEFGHI?up=1\"]}\n"
        );
        assert_eq!(read(response(None)).await, "{\"rejected\":[]}\n");
    }

    /// Q8 as an allow-list (L6): only `event_id`, `room_id`, `counts`, `prio`, `type` and the devices' `app_id`, `data`
    /// and `pushkey_ts` stay, each kept member in its order with its bytes.
    #[test]
    fn minimised() {
        let pushkey = format!("{BASE}/up/{SECRET}");
        let out = String::from_utf8(minimise(body(&pushkey).as_bytes()).unwrap()).unwrap();
        assert_eq!(
            out,
            r##"{"notification":{"counts":{"missed_calls":1,"unread":2},"devices":[{"app_id":"org.matrix.matrixConsole.ios","data":{},"pushkey_ts":12345678}],"event_id":"$3957tyerfgewrf384","prio":"high","room_id":"!slw48wfj34rtnrf:example.com","type":"m.room.message"}}"##
        );
        assert!(!out.contains(SECRET), "the endpoint isn't kept");
        // Whitespace between members goes; a value's own bytes stay; a top-level extra and a device that isn't an
        // object go.
        let spaced = br#"{ "notification" : { "room_name" : "x", "counts" : { "unread" : 2 }, "devices": [1, {"data": {"cs": "a"}, "pushkey": "p"}] } , "extra" : [1, 2] }"#;
        assert_eq!(
            String::from_utf8(minimise(spaced).unwrap()).unwrap(),
            r#"{"notification":{"counts":{ "unread" : 2 },"devices":[{"data":{"cs": "a"}}]}}"#
        );
        assert!(prio_is_high(body(&pushkey).as_bytes()));
        assert_eq!(minimise(b"[1]"), None);
    }
}
