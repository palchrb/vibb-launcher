//! The registration routes (design 129 §5.2), behind the device auth (bearer, or the key once the phone adopted
//! it):
//!
//! - `GET /api/devices/key` -> `{state: none|observed|bound|held, key_id?, spki_sha256?, rotate}`: the one answer the
//!   phone's registration worker acts on.
//! - `GET /api/devices/key/challenge` -> `{challenge, expires_in_s: 600}`: 32 random bytes, one live per phone,
//!   used once.
//! - `POST /api/devices/key {challenge, chain, proof}` -> 200 `{key_id, state}`; idempotent on the SPKI (the same key
//!   again is the same answer, whatever the challenge's state); 409 `{reason: "exists"}` for another key while one is
//!   stored; 400 `{reason}` for a refused chain; 503 `{reason: "list_stale", retry_after_s}`.
//! - `POST /api/devices/key/conflict` -> 204: the phone holds none of the key the server names (§5.2 step 3); 409
//!   `{reason: "none"}` while the server names none.
//!
//! The chain is checked before any transaction (M4). S1 stores the key `observed`; the bearer keeps working.

use axum::Extension;
use axum::extract::{ConnectInfo, State};
use axum::http::{StatusCode, header};
use axum::response::{IntoResponse, Response};
use base64::Engine;

use crate::AppState;
use crate::attestation::{self, Attested, Expect, Refusal};
use crate::security::{self, AuthedDevice, Detail};

/// A registration's body: the challenge (Base64url), the chain (standard Base64 DER, leaf first) and the proof
/// (Base64url DER ECDSA). At most 32 KiB (§3 item 1).
pub const BODY_LIMIT: usize = 32 * 1024;

#[derive(serde::Deserialize)]
pub struct RegisterRequest {
    pub challenge: String,
    pub chain: Vec<String>,
    pub proof: String,
}

/// Whether `LAUNCHER_SIGNATURE_CHECKSUM` is set and readable: without it no key can be checked (§3 item 10).
pub fn checksum_set(state: &AppState) -> bool {
    attestation::signature_digest(state.config.launcher_signature_checksum.as_deref()).is_some()
}

/// A refusal's answer: 400 `{reason}`, or 503 `{reason: "list_stale", retry_after_s}` with `Retry-After`.
pub fn refused(refusal: Refusal) -> Response {
    if refusal == Refusal::ListStale {
        let wait = attestation::status_list::RETRY_AFTER_S;
        return (
            StatusCode::SERVICE_UNAVAILABLE,
            [(header::RETRY_AFTER, wait.to_string())],
            axum::Json(serde_json::json!({ "reason": refusal.word(), "retry_after_s": wait })),
        )
            .into_response();
    }
    (
        StatusCode::BAD_REQUEST,
        axum::Json(serde_json::json!({ "reason": refusal.word() })),
    )
        .into_response()
}

/// The chain decoded from its Base64 strings (`chain` when one isn't Base64 or there are too many).
pub fn decode_chain(chain: &[String]) -> Result<Vec<Vec<u8>>, Refusal> {
    if chain.len() > attestation::MAX_CERTS {
        return Err(Refusal::Chain);
    }
    chain
        .iter()
        .map(|c| {
            base64::engine::general_purpose::STANDARD
                .decode(c.trim_end_matches('='))
                .or_else(|_| base64::engine::general_purpose::STANDARD.decode(c))
                .map_err(|_| Refusal::Chain)
        })
        .collect()
}

/// §3 in full for a key the phone says it made for `challenge`, with the proof of possession (`kind`: `enroll` or
/// `register`). No database: the list's freshness and the server's settings only.
pub fn check(
    state: &AppState,
    chain: &[String],
    challenge: &[u8],
    kind: &str,
    proof_b64: &str,
) -> Result<Attested, Refusal> {
    let now = chrono::Utc::now();
    let Some(revoked) = state.device_keys.status.fresh(now) else {
        return Err(Refusal::ListStale);
    };
    let chain = decode_chain(chain)?;
    let digest = attestation::signature_digest(state.config.launcher_signature_checksum.as_deref())
        .unwrap_or_default();
    let expect = Expect {
        challenge,
        package: attestation::LAUNCHER_PACKAGE,
        signature_digest: &digest,
        now: now.timestamp(),
    };
    let attested = (state.device_keys.attest)(&chain, &expect, revoked.as_ref())?;
    let proof = base64::engine::general_purpose::URL_SAFE_NO_PAD
        .decode(proof_b64)
        .map_err(|_| Refusal::Proof)?;
    if !attestation::verify_proof(&attested.public_key, challenge, kind, &proof) {
        return Err(Refusal::Proof);
    }
    Ok(attested)
}

/// `GET /api/devices/key`.
pub async fn get_key(
    State(state): State<AppState>,
    Extension(AuthedDevice(device)): Extension<AuthedDevice>,
) -> Response {
    match super::current_key(&state.db, device.id).await {
        Ok(key) => axum::Json(super::key_answer(key.as_ref())).into_response(),
        Err(err) => {
            tracing::error!(device_id = device.id, %err, "can't read the phone's key");
            StatusCode::INTERNAL_SERVER_ERROR.into_response()
        }
    }
}

/// `GET /api/devices/key/challenge`: a new challenge replaces the phone's last one.
pub async fn get_challenge(
    State(state): State<AppState>,
    Extension(AuthedDevice(device)): Extension<AuthedDevice>,
) -> Response {
    let challenge = super::random_bytes::<32>();
    let stored = sqlx::query(
        "INSERT INTO device_key_challenges (device_id, challenge, created_at, expires_at) \
         VALUES (?, ?, datetime('now'), datetime('now', ?)) ON CONFLICT (device_id) DO UPDATE SET \
         challenge = excluded.challenge, created_at = excluded.created_at, expires_at = excluded.expires_at",
    )
    .bind(device.id)
    .bind(challenge.as_slice())
    .bind(format!("+{} seconds", super::CHALLENGE_TTL_S))
    .execute(&state.db)
    .await;
    if let Err(err) = stored {
        tracing::error!(device_id = device.id, %err, "can't store a key challenge");
        return StatusCode::INTERNAL_SERVER_ERROR.into_response();
    }
    axum::Json(serde_json::json!({
        "challenge": super::b64url(&challenge),
        "expires_in_s": super::CHALLENGE_TTL_S,
    }))
    .into_response()
}

/// `POST /api/devices/key`.
pub async fn post_key(
    State(state): State<AppState>,
    Extension(AuthedDevice(device)): Extension<AuthedDevice>,
    ConnectInfo(addr): ConnectInfo<std::net::SocketAddr>,
    headers: axum::http::HeaderMap,
    body: axum::body::Bytes,
) -> Response {
    let Ok(req) = serde_json::from_slice::<RegisterRequest>(&body) else {
        return StatusCode::BAD_REQUEST.into_response();
    };
    let client = state.net.client(&headers, addr.ip());
    let Ok(challenge) = base64::engine::general_purpose::URL_SAFE_NO_PAD.decode(&req.challenge)
    else {
        return refused(Refusal::Challenge);
    };
    // §3 first, with no database (M4): the attestation must be for the challenge the body names.
    let attested = match check(&state, &req.chain, &challenge, "register", &req.proof) {
        Ok(attested) => attested,
        Err(refusal) => {
            note_refusal(&state, device.id, refusal, &client).await;
            return refused(refusal);
        }
    };
    let spki_sha256 = attestation::spki_sha256(&attested.spki);

    let answer = register(
        &state,
        device.id,
        &attested,
        &spki_sha256,
        &challenge,
        &client,
    )
    .await;
    match answer {
        Ok(Registered::New { key_id, unusual }) => {
            state.device_keys.index.refresh(&state.db, device.id).await;
            log_bound(&state, device.id, &client, unusual, "register").await;
            axum::Json(serde_json::json!({ "key_id": key_id, "state": "observed" })).into_response()
        }
        Ok(Registered::Same(key)) => {
            axum::Json(serde_json::json!({ "key_id": key.key_id, "state": key.state }))
                .into_response()
        }
        Ok(Registered::Exists) => {
            mark_conflict(&state, device.id, "exists").await;
            (
                StatusCode::CONFLICT,
                axum::Json(serde_json::json!({ "reason": "exists" })),
            )
                .into_response()
        }
        Ok(Registered::Refused(refusal)) => {
            note_refusal(&state, device.id, refusal, &client).await;
            refused(refusal)
        }
        Err(err) => {
            tracing::error!(device_id = device.id, %err, "can't store the phone's key");
            StatusCode::INTERNAL_SERVER_ERROR.into_response()
        }
    }
}

enum Registered {
    New { key_id: String, unusual: bool },
    Same(super::StoredKey),
    Exists,
    Refused(Refusal),
}

/// The transaction (§5.2 step 5): the same SPKI again is the stored answer; another while one is stored is
/// `exists`; else the live, unexpired challenge must be the attested one (used once) and the key is stored.
async fn register(
    state: &AppState,
    device_id: i64,
    attested: &Attested,
    spki_sha256: &str,
    challenge: &[u8],
    client: &crate::net::Client,
) -> Result<Registered, sqlx::Error> {
    let mut tx = state.db.begin().await?;
    let stored: Option<(String, String, String)> = sqlx::query_as(
        "SELECT key_id, spki_sha256, state FROM device_keys WHERE device_id = ? AND state != 'retired' \
         ORDER BY created_at DESC LIMIT 1",
    )
    .bind(device_id)
    .fetch_optional(&mut *tx)
    .await?;
    if let Some((_, stored_spki, _)) = &stored {
        if stored_spki == spki_sha256 {
            drop(tx);
            let key = super::current_key(&state.db, device_id).await?;
            return Ok(match key {
                Some(key) => Registered::Same(key),
                None => Registered::Exists,
            });
        }
        return Ok(Registered::Exists);
    }
    let live: Option<String> = sqlx::query_scalar(
        "DELETE FROM device_key_challenges WHERE device_id = ? AND challenge = ? \
         AND expires_at > datetime('now') RETURNING created_at",
    )
    .bind(device_id)
    .bind(challenge)
    .fetch_optional(&mut *tx)
    .await?;
    let Some(issued_at) = live else {
        return Ok(Registered::Refused(Refusal::Challenge));
    };
    let unusual = super::unusual_address(&state.db, device_id, &client.key(), &issued_at).await?;
    let key_id = super::insert_key(
        &mut tx,
        device_id,
        attested,
        "register",
        &client.key(),
        unusual,
    )
    .await?;
    sqlx::query("UPDATE devices SET key_refused_reason = NULL, key_refused_at = NULL WHERE id = ?")
        .bind(device_id)
        .execute(&mut *tx)
        .await?;
    tx.commit().await?;
    Ok(Registered::New { key_id, unusual })
}

/// `POST /api/devices/key/conflict`: the server names a key the phone doesn't hold.
pub async fn post_conflict(
    State(state): State<AppState>,
    Extension(AuthedDevice(device)): Extension<AuthedDevice>,
) -> Response {
    // A conflict is about a key the server names (§5.2 step 3): with none stored there is nothing to report (L8).
    match super::current_key(&state.db, device.id).await {
        Ok(Some(_)) => {}
        Ok(None) => {
            return (
                StatusCode::CONFLICT,
                axum::Json(serde_json::json!({ "reason": "none" })),
            )
                .into_response();
        }
        Err(err) => {
            tracing::error!(device_id = device.id, %err, "can't read the phone's key");
            return StatusCode::INTERNAL_SERVER_ERROR.into_response();
        }
    }
    mark_conflict(&state, device.id, "report").await;
    StatusCode::NO_CONTENT.into_response()
}

/// A key conflict (an `exists` answer or the phone's report), kept until a new setup code (§8.1).
async fn mark_conflict(state: &AppState, device_id: i64, how: &str) {
    let newly: Result<Option<i64>, _> = sqlx::query_scalar(
        "UPDATE devices SET key_conflict_at = datetime('now') WHERE id = ? AND key_conflict_at IS NULL \
         RETURNING id",
    )
    .bind(device_id)
    .fetch_optional(&state.db)
    .await;
    match newly {
        Ok(Some(_)) => {
            security::record_security_event(
                &state.db,
                "device_key_conflict",
                None,
                None,
                Some(
                    Detail::words(
                        &format!("security.detail.device_key_conflict.{how}"),
                        &[("name", &device_name(state, device_id).await)],
                    )
                    .with_facts(format!("device {device_id}")),
                ),
            )
            .await;
        }
        Ok(None) => {}
        Err(err) => tracing::error!(device_id, %err, "can't note the key conflict"),
    }
}

/// The phone's name for the security log's words (the numeric id stays in the facts).
async fn device_name(state: &AppState, device_id: i64) -> String {
    sqlx::query_scalar("SELECT name FROM devices WHERE id = ?")
        .bind(device_id)
        .fetch_optional(&state.db)
        .await
        .ok()
        .flatten()
        .unwrap_or_else(|| device_id.to_string())
}

/// A refused registration: kept on the phone's row for About This Phone (`Nøkkel avvist (reason)`), and the
/// security log's `device_key_refused` (throttled per client). `list_stale` is the server's own state, not the
/// phone's: neither.
pub async fn note_refusal(
    state: &AppState,
    device_id: i64,
    refusal: Refusal,
    client: &crate::net::Client,
) {
    if refusal == Refusal::ListStale {
        return;
    }
    // Without LAUNCHER_SIGNATURE_CHECKSUM every key is refused `app`: the server's setting, said once on Settings -
    // never the phone's blame (code QA L10).
    if refusal == Refusal::App && !checksum_set(state) {
        return;
    }
    if let Err(err) = sqlx::query(
        "UPDATE devices SET key_refused_reason = ?, key_refused_at = datetime('now') WHERE id = ?",
    )
    .bind(refusal.word())
    .bind(device_id)
    .execute(&state.db)
    .await
    {
        tracing::error!(device_id, %err, "can't note the key refusal");
    }
    let ip = client.describe();
    state
        .audit
        .record(
            &state.db,
            "device_key_refused",
            &client.key(),
            Some(&ip),
            Detail::words(
                "security.detail.device_key_refused",
                &[("name", &device_name(state, device_id).await)],
            )
            .word("reason", crate::handlers::device_pages::key_reason(refusal)),
        )
        .await;
}

/// The security log's `device_key_bound`: the registering address and whether it was unusual.
pub async fn log_bound(
    state: &AppState,
    device_id: i64,
    client: &crate::net::Client,
    unusual: bool,
    via: &str,
) {
    let mut detail = Detail::words(
        "security.detail.device_key_bound",
        &[("name", &device_name(state, device_id).await)],
    )
    .with_facts(format!("device {device_id}"));
    if unusual {
        detail = detail.and("security.detail.device_key_bound.unusual", &[]);
    }
    if via == "enroll" {
        detail = detail.and("security.detail.device_key_bound.enroll", &[]);
    }
    security::record_security_event(
        &state.db,
        "device_key_bound",
        None,
        Some(&client.describe()),
        Some(detail),
    )
    .await;
}
