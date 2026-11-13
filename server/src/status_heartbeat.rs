//! The heartbeat report (design 95 §3.5, step 95-T2; `testdata/status_heartbeat.json`).
//!
//! A launcher claiming [CAPABILITY] sends its full status report with a `"hash"` - its own SHA-256 over the report
//! without the [HEARTBEAT_FIELDS] - while the policy says `status_heartbeat: true` and names the same
//! `status_heartbeat_fields`. After the report was taken (2xx), [keep] stores its body: only the report's known
//! top-level keys ([REPORT_KEYS]), without the heartbeat fields (so no location is kept here), the lock state and the
//! call state through their sanitizers, at most [MAX_KEPT_BYTES] (`device_report_base`, one row per phone).
//!
//! While nothing but the heartbeat fields changes, the phone sends `{"heartbeat": true, "hash", "fields_tag", <the
//! heartbeat fields>}` instead, and [take] turns it back into the full report it stands for: the kept body with the
//! heartbeat's fields over it. `device_api::status` then runs that through the one intake every full report takes, so
//! every consumer of a report - the stored row, the security log, the parent's events, the zones, the logos, the
//! certificate check - behaves as if the full report had come. The server never computes the hash: it is the phone's
//! name for a body the server holds.
//!
//! A heartbeat the server can't expand - another list of fields ([fields_tag]), no kept body, another hash, a body kept
//! by another server version, before the phone's latest enrollment or older than [MAX_AGE_HOURS], or the switch
//! `STATUS_HEARTBEAT=off` - is answered `{"full_report": true}` ([full_report_wanted]) and stores nothing; the phone
//! sends the full report at once. An older launcher never sends a hash, and its reports drop whatever was kept.

use axum::Json;
use axum::response::{IntoResponse, Response};
use serde_json::{Map, Value};
use sha2::{Digest, Sha256};
use sqlx::SqlitePool;

/// The launcher's capability: it sends hashes and heartbeats when the policy says `status_heartbeat`. The server needs
/// no check of it (a heartbeat names itself); the contract test pins the name.
#[cfg_attr(not(test), allow(dead_code))]
pub const CAPABILITY: &str = "status_heartbeat_v1";

/// What a heartbeat carries and the hash leaves out: the fields that move on an idle phone (the screen time, the
/// battery, the lock, the location the phone re-sends, the certificate check's `seen.at_ms` of every sync, the mobile
/// network's state). Every other field changing makes the phone send a full report. Sent in the policy
/// (`status_heartbeat_fields`); a change here is safe across versions: a launcher with another list sends no heartbeat,
/// and a heartbeat naming another list ([fields_tag]) is answered with a full-report request.
pub const HEARTBEAT_FIELDS: &[&str] = &[
    "lock_reason",
    "battery",
    "time_state",
    "location",
    "extra_locations",
    "zone_watch",
    "server_tls",
    "data_roaming",
    "mobile_data",
];

/// Every top-level key of `models::StatusReportRequest` (`the_report_keys_are_the_structs` reads the struct): the only
/// keys a kept body holds.
pub const REPORT_KEYS: &[&str] = &[
    "lock_reason",
    "kiosk_engaged",
    "installed_apps",
    "app_version",
    "app_version_code",
    "offline_override_used",
    "location",
    "policy_state",
    "restrictions_paused",
    "capabilities",
    "call_state",
    "sms_state",
    "notification_listener_enabled",
    "time_state",
    "install_mode",
    "play_window_active",
    "play_store_suspendable",
    "lock_state",
    "screen_timeout_seconds",
    "ringer_mode",
    "interruption_filter",
    "backup_service_enabled",
    "update_fence",
    "notification_cancels",
    "boot_cover",
    "app_downloads",
    "music_state",
    "battery_optimization_exempt",
    "remote_wipe_ready",
    "admin_policies_pending",
    "relock",
    "unseen_boots",
    "foreign_access",
    "relock_blocks",
    "relock_blocks_total",
    "clock_format_applied",
    "dns_upstream",
    "write_settings_granted",
    "tailnet_joined",
    "data_roaming",
    "mobile_data",
    "server_tls",
    "battery",
    "extra_locations",
    "zone_watch",
    "calendar",
];

/// A kept body older than this is never expanded (the phone sends a full report every 6 h anyway); retention deletes it.
pub const MAX_AGE_HOURS: i64 = 7;

/// The largest body kept. A phone whose report is larger (hundreds of apps) gets no heartbeats: it keeps sending full
/// reports, as before.
pub const MAX_KEPT_BYTES: usize = 128 * 1024;

/// The longest hash taken (the phone's is 64 hex characters).
const MAX_HASH_LEN: usize = 128;

/// The name of [HEARTBEAT_FIELDS] a heartbeat carries (`fields_tag`): the first 16 hex characters of the SHA-256 of
/// the names joined by `,`. The launcher computes it from its own list.
pub fn fields_tag() -> String {
    let digest = Sha256::digest(HEARTBEAT_FIELDS.join(",").as_bytes());
    hex::encode(&digest[..8])
}

/// What [take] made of a report's body.
pub enum Intake {
    /// The report to run, and the body to keep under its hash once it is taken (`None`: forget what was kept).
    Full {
        report: Value,
        keep: Option<(String, Value)>,
    },
    /// A heartbeat expanded into the full report the phone would have sent.
    Expanded(Value),
    /// A heartbeat that can't be expanded: answer [full_report_wanted].
    FullReportWanted,
}

/// The answer to a heartbeat the server can't expand.
pub fn full_report_wanted() -> Response {
    Json(serde_json::json!({ "full_report": true })).into_response()
}

/// Reads a status body: a heartbeat is expanded (or refused), a full report's `hash` taken off. [enabled] is the
/// `STATUS_HEARTBEAT` switch.
pub async fn take(db: &SqlitePool, device_id: i64, body: Value, enabled: bool) -> Intake {
    let Value::Object(mut body) = body else {
        return Intake::Full {
            report: body,
            keep: None,
        };
    };
    let heartbeat = body.remove("heartbeat").and_then(|v| v.as_bool()) == Some(true);
    let tag = body.remove("fields_tag");
    let hash = body
        .remove("hash")
        .and_then(|v| v.as_str().map(str::to_string))
        .filter(|h| !h.is_empty() && h.len() <= MAX_HASH_LEN);
    if heartbeat {
        let same_fields = tag.as_ref().and_then(Value::as_str) == Some(fields_tag().as_str());
        return match hash.filter(|_| enabled && same_fields) {
            Some(hash) => match expand(db, device_id, &hash, &body).await {
                Some(report) => Intake::Expanded(report),
                None => Intake::FullReportWanted,
            },
            None => Intake::FullReportWanted,
        };
    }
    let keep = hash
        .filter(|_| enabled)
        .map(|hash| (hash, Value::Object(body.clone())));
    Intake::Full {
        report: Value::Object(body),
        keep,
    }
}

/// The body kept for a phone (M2 of the code QA): the report's known keys without the heartbeat fields, the lock state
/// and the call state as their sanitizers leave them (both idempotent, so the expanded report reads the same).
pub fn kept_body(body: &Map<String, Value>, now_ms: i64) -> Map<String, Value> {
    let mut kept: Map<String, Value> = body
        .iter()
        .filter(|(key, _)| {
            REPORT_KEYS.contains(&key.as_str()) && !HEARTBEAT_FIELDS.contains(&key.as_str())
        })
        .map(|(key, value)| (key.clone(), value.clone()))
        .collect();
    let lock_state = kept
        .get("lock_state")
        .and_then(|v| crate::kid_lock::sanitize_lock_state(v, now_ms));
    let call_state = kept.get("call_state").and_then(|v| {
        crate::handlers::device_api::known_fields(v, crate::handlers::device_api::CALL_STATE_FIELDS)
    });
    for (key, sanitized) in [("lock_state", lock_state), ("call_state", call_state)] {
        match sanitized.and_then(|json| serde_json::from_str::<Value>(&json).ok()) {
            Some(value) => {
                kept.insert(key.to_string(), value);
            }
            None => {
                kept.remove(key);
            }
        }
    }
    kept
}

/// The kept body with [heartbeat]'s fields over it: each heartbeat field is the heartbeat's (absent there: absent).
pub fn overlay(kept: &Map<String, Value>, heartbeat: &Map<String, Value>) -> Map<String, Value> {
    let mut report: Map<String, Value> = kept
        .iter()
        .filter(|(key, _)| !HEARTBEAT_FIELDS.contains(&key.as_str()))
        .map(|(key, value)| (key.clone(), value.clone()))
        .collect();
    for field in HEARTBEAT_FIELDS {
        if let Some(value) = heartbeat.get(*field).filter(|v| !v.is_null()) {
            report.insert(field.to_string(), value.clone());
        }
    }
    report
}

async fn expand(
    db: &SqlitePool,
    device_id: i64,
    hash: &str,
    heartbeat: &Map<String, Value>,
) -> Option<Value> {
    let kept: Option<String> = sqlx::query_scalar(
        "SELECT b.body_json FROM device_report_base b JOIN devices d ON d.id = b.device_id \
         WHERE b.device_id = ? AND b.hash = ? AND b.server_version = ? \
           AND b.stored_at > datetime('now', ?) \
           AND (d.enrolled_at IS NULL OR b.stored_at >= d.enrolled_at)",
    )
    .bind(device_id)
    .bind(hash)
    .bind(env!("CARGO_PKG_VERSION"))
    .bind(format!("-{MAX_AGE_HOURS} hours"))
    .fetch_optional(db)
    .await
    .unwrap_or_else(|err| {
        tracing::error!(device_id, %err, "can't read the kept report");
        None
    });
    let Value::Object(kept) = serde_json::from_str(&kept?).ok()? else {
        return None;
    };
    Some(Value::Object(overlay(&kept, heartbeat)))
}

/// After a full report was taken: keeps its [kept_body] under the hash for the phone's next heartbeats, or forgets what
/// was kept (`None`: an older launcher's report, one sent without a hash, the switch off; a body past
/// [MAX_KEPT_BYTES]).
pub async fn keep(db: &SqlitePool, device_id: i64, keep: Option<&(String, Value)>) {
    let kept = keep.and_then(|(hash, body)| {
        let body = kept_body(body.as_object()?, chrono::Utc::now().timestamp_millis());
        let json = Value::Object(body).to_string();
        (json.len() <= MAX_KEPT_BYTES).then_some((hash, json))
    });
    let result = match kept {
        Some((hash, json)) => {
            sqlx::query(
                "INSERT INTO device_report_base (device_id, hash, body_json, server_version, stored_at) \
                 VALUES (?, ?, ?, ?, datetime('now')) \
                 ON CONFLICT (device_id) DO UPDATE SET hash = excluded.hash, body_json = excluded.body_json, \
                   server_version = excluded.server_version, stored_at = excluded.stored_at",
            )
            .bind(device_id)
            .bind(hash)
            .bind(json)
            .bind(env!("CARGO_PKG_VERSION"))
            .execute(db)
            .await
        }
        None => {
            sqlx::query("DELETE FROM device_report_base WHERE device_id = ?")
                .bind(device_id)
                .execute(db)
                .await
        }
    };
    if let Err(err) = result {
        tracing::error!(device_id, %err, "can't keep the report for heartbeats");
    }
}

/// Retention (code QA L2): a kept body past [MAX_AGE_HOURS] is never expanded again, so it goes.
pub async fn prune(db: &SqlitePool) -> Result<sqlx::sqlite::SqliteQueryResult, sqlx::Error> {
    sqlx::query("DELETE FROM device_report_base WHERE stored_at <= datetime('now', ?)")
        .bind(format!("-{MAX_AGE_HOURS} hours"))
        .execute(db)
        .await
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn a_heartbeat_overlays_its_fields_and_only_those() {
        let kept = json!({"kiosk_engaged": true, "battery": {"level": 90, "charging": false},
                          "installed_apps": [], "location": {"latitude": 1.0}});
        let heartbeat = json!({"lock_reason": "NONE", "battery": {"level": 80, "charging": true},
                               "kiosk_engaged": false});
        let report = overlay(kept.as_object().unwrap(), heartbeat.as_object().unwrap());
        assert_eq!(
            Value::Object(report),
            json!({"kiosk_engaged": true, "installed_apps": [], "lock_reason": "NONE",
                   "battery": {"level": 80, "charging": true}})
        );
    }

    #[test]
    fn the_kept_body_holds_known_keys_only_and_sanitized_blobs() {
        let body = json!({"kiosk_engaged": true, "lock_reason": "NONE", "not_a_report_key": "x" ,
                          "lock_state": {"active": true, "unlock_pin": "1234"},
                          "call_state": {"managed": true, "anything_else": 1}});
        let kept = kept_body(body.as_object().unwrap(), 0);
        assert!(kept.get("not_a_report_key").is_none());
        assert!(kept.get("lock_reason").is_none());
        assert!(
            !kept
                .get("lock_state")
                .is_some_and(|v| v.get("unlock_pin").is_some())
        );
        assert!(kept["call_state"].get("anything_else").is_none());
    }

    /// Every `pub <name>:` of `StatusReportRequest` in `models.rs` is a [REPORT_KEYS] entry and no other.
    #[test]
    fn the_report_keys_are_the_structs() {
        let models = include_str!("models.rs");
        let body = models
            .split("pub struct StatusReportRequest {")
            .nth(1)
            .and_then(|rest| rest.split("\n}\n").next())
            .expect("the struct");
        let mut fields: Vec<&str> = body
            .lines()
            .filter_map(|line| line.trim().strip_prefix("pub "))
            .filter_map(|rest| rest.split(':').next())
            .collect();
        let mut keys = REPORT_KEYS.to_vec();
        fields.sort_unstable();
        keys.sort_unstable();
        assert_eq!(fields, keys);
        for field in HEARTBEAT_FIELDS {
            assert!(REPORT_KEYS.contains(field), "{field}");
        }
    }

    #[test]
    fn the_fields_tag_names_the_list() {
        assert_eq!(fields_tag().len(), 16);
    }
}
