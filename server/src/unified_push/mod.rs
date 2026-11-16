//! Vibb as the UnifiedPush distributor (design 108, `docs/design/108-unified-push.md`): the server is the push server
//! behind the endpoints the launcher hands to the phone's apps, and a Matrix push gateway.
//!
//! This round (R1-C) holds the shared contract's server side: the capability, the policy's `unified_push` and the
//! device API's answers, stubbed (every registration is refused `off`, the queue is empty). R1-S fills them in.

pub mod device;

use serde::Serialize;

/// The launcher's capability: it reads the policy's `unified_push` and talks to the device API (§3.7).
pub const CAPABILITY: &str = "unified_push_v1";

/// The policy's `unified_push` (§3.8), only to a launcher whose last report claims [CAPABILITY].
#[derive(Serialize, Debug, Clone, PartialEq, Eq)]
pub struct UpPolicy {
    /// The phone's switch (on by default) and a public origin set.
    pub on: bool,
    /// The current public origin, `null` when unset (M4).
    pub origin: Option<String>,
    /// The phone's registration keys the server holds, oldest first.
    pub regs: Vec<String>,
    /// The packages the parent stopped on this phone, sorted.
    pub stopped: Vec<String>,
}

/// The server-wide row (`unified_push_settings`, migration 0152).
#[derive(sqlx::FromRow, Debug, Clone, Default, PartialEq, Eq)]
pub struct Settings {
    pub public_origin: Option<String>,
    pub previous_origin: Option<String>,
    pub origin_changed_at: Option<String>,
    pub last_discovery_at: Option<String>,
}

/// The settings row; its absence reads as unset.
pub async fn settings(db: &sqlx::SqlitePool) -> Result<Settings, sqlx::Error> {
    Ok(sqlx::query_as::<_, Settings>(
        "SELECT public_origin, previous_origin, origin_changed_at, last_discovery_at \
         FROM unified_push_settings WHERE id = 1",
    )
    .fetch_optional(db)
    .await?
    .unwrap_or_default())
}

/// The policy's `unified_push` for a phone whose last report has `capabilities`: `None` (the key left out) without
/// [CAPABILITY]. A read error fails the policy (the phone keeps its cache), never a made-up state.
pub async fn policy(
    db: &sqlx::SqlitePool,
    device_id: i64,
    capabilities: &[String],
) -> Result<Option<UpPolicy>, sqlx::Error> {
    if !capabilities.iter().any(|c| c == CAPABILITY) {
        return Ok(None);
    }
    let origin = settings(db).await?.public_origin;
    let switch: bool = sqlx::query_scalar("SELECT unified_push FROM devices WHERE id = ?")
        .bind(device_id)
        .fetch_optional(db)
        .await?
        .unwrap_or(false);
    let regs: Vec<String> =
        sqlx::query_scalar("SELECT reg FROM up_registrations WHERE device_id = ? ORDER BY id")
            .bind(device_id)
            .fetch_all(db)
            .await?;
    let stopped: Vec<String> =
        sqlx::query_scalar("SELECT package FROM up_stopped WHERE device_id = ? ORDER BY package")
            .bind(device_id)
            .fetch_all(db)
            .await?;
    Ok(Some(UpPolicy {
        on: switch && origin.is_some(),
        origin,
        regs,
        stopped,
    }))
}

/// What the server stores of an endpoint secret: its SHA-256 (§3.5). A plain hash is enough for a 160-bit random
/// value; the secret itself is never stored.
#[cfg_attr(not(test), allow(dead_code))] // the store's (R1-S)
pub fn secret_hash(secret: &str) -> Vec<u8> {
    use sha2::Digest;
    sha2::Sha256::digest(secret.as_bytes()).to_vec()
}
