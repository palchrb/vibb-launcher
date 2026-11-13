//! The heartbeat report (design 95 §3.5, step 95-T2; `testdata/status_heartbeat.json`).
//!
//! A launcher claiming [CAPABILITY] (and told `status_heartbeat: true` by the policy) sends its full status report with
//! a `"hash"`: its own SHA-256 over the report without the [HEARTBEAT_FIELDS]. The server keeps that body - without the
//! heartbeat fields, so no location is kept here - with the hash (`device_report_base`, one row per phone). While
//! nothing but the heartbeat fields changes, the phone sends `{"heartbeat": true, "hash": ..., <the heartbeat
//! fields>}` instead, and [expand] turns it back into the full report the phone would have sent: the kept body with the
//! heartbeat's fields over it. `device_api::status` then runs that through the one intake every full report takes, so
//! every consumer of a report - the stored row, the security log, the parent's events, the zones, the logos, the
//! certificate check - behaves exactly as if the full report had come. The server never computes the hash: it is the
//! phone's name for a body the server holds.
//!
//! A heartbeat the server can't expand - no kept body, another hash, a body kept by another server version, before the
//! phone's latest enrollment or older than [MAX_AGE_HOURS] - is answered `{"full_report": true}` ([full_report_wanted])
//! and stores nothing; the phone sends the full report at once. An older launcher never sends a hash, and its reports
//! drop whatever was kept.

use axum::Json;
use axum::response::{IntoResponse, Response};
use serde_json::{Map, Value};
use sqlx::SqlitePool;

/// The launcher's capability: it sends hashes and heartbeats when the policy says `status_heartbeat`. The server needs
/// no check of it (a heartbeat names itself); the contract test pins the name.
#[cfg_attr(not(test), allow(dead_code))]
pub const CAPABILITY: &str = "status_heartbeat_v1";

/// What a heartbeat carries and the hash leaves out: the fields that move on an idle phone (the screen time, the
/// battery, the lock, the location the phone re-sends, the certificate check's `seen.at_ms` of every sync, the mobile
/// network's state). Every other field changing makes the phone send a full report.
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

/// A kept body older than this is never expanded (the phone sends a full report every 6 h anyway).
pub const MAX_AGE_HOURS: i64 = 7;

/// The longest hash taken (the phone's is 64 hex characters).
const MAX_HASH_LEN: usize = 128;

/// What [take] made of a report's body.
pub enum Intake {
    /// The report to run, and the body to keep under its hash once it is read (`None`: forget what was kept).
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

/// Reads a status body: a heartbeat is expanded (or refused), a full report's `hash` taken off.
pub async fn take(db: &SqlitePool, device_id: i64, body: Value) -> Intake {
    let Value::Object(mut body) = body else {
        return Intake::Full {
            report: body,
            keep: None,
        };
    };
    let heartbeat = body.remove("heartbeat").and_then(|v| v.as_bool()) == Some(true);
    let hash = body
        .remove("hash")
        .and_then(|v| v.as_str().map(str::to_string))
        .filter(|h| !h.is_empty() && h.len() <= MAX_HASH_LEN);
    if heartbeat {
        return match hash {
            Some(hash) => match expand(db, device_id, &hash, &body).await {
                Some(report) => Intake::Expanded(report),
                None => Intake::FullReportWanted,
            },
            None => Intake::FullReportWanted,
        };
    }
    let keep = hash.map(|hash| (hash, Value::Object(without_heartbeat_fields(&body))));
    Intake::Full {
        report: Value::Object(body),
        keep,
    }
}

/// The body kept for a phone: the report without its heartbeat fields.
pub fn without_heartbeat_fields(body: &Map<String, Value>) -> Map<String, Value> {
    body.iter()
        .filter(|(key, _)| !HEARTBEAT_FIELDS.contains(&key.as_str()))
        .map(|(key, value)| (key.clone(), value.clone()))
        .collect()
}

/// The kept body with [heartbeat]'s fields over it: each heartbeat field is the heartbeat's (absent there: absent).
pub fn overlay(kept: &Map<String, Value>, heartbeat: &Map<String, Value>) -> Map<String, Value> {
    let mut report = without_heartbeat_fields(kept);
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

/// Keeps [body] under [hash] for the phone's next heartbeats (`None`: forgets what was kept - an older launcher's
/// report, or one sent without a hash). Called once the report was read.
pub async fn keep(db: &SqlitePool, device_id: i64, keep: Option<&(String, Value)>) {
    let result = match keep {
        Some((hash, body)) => {
            sqlx::query(
                "INSERT INTO device_report_base (device_id, hash, body_json, server_version, stored_at) \
                 VALUES (?, ?, ?, ?, datetime('now')) \
                 ON CONFLICT (device_id) DO UPDATE SET hash = excluded.hash, body_json = excluded.body_json, \
                   server_version = excluded.server_version, stored_at = excluded.stored_at",
            )
            .bind(device_id)
            .bind(hash)
            .bind(body.to_string())
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
}
