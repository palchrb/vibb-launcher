//! Design 95 §3.5 (step 95-T2): the heartbeat report, the gzip'd report, and the shared contract
//! `testdata/status_heartbeat.json` (the launcher's `StatusReportsTest` encodes and reads the same file).

use std::io::Write;

use axum::body::Body;
use axum::http::{Method, Request, StatusCode, header};
use serde_json::{Value, json};

use super::{TestApp, TestResponse};
use crate::status_heartbeat::{CAPABILITY, HEARTBEAT_FIELDS};

const CONTRACT: &str = include_str!("../../testdata/status_heartbeat.json");

fn contract() -> Value {
    serde_json::from_str(CONTRACT).expect("the contract is JSON")
}

async fn post(app: &TestApp, token: &str, body: &Value) -> TestResponse {
    app.request(
        Method::POST,
        "/api/devices/status",
        Some(token),
        Some(body.clone()),
    )
    .await
}

async fn post_gzip(app: &TestApp, token: &str, json: &[u8], encoding: &str) -> TestResponse {
    let mut encoder = flate2::write::GzEncoder::new(Vec::new(), flate2::Compression::default());
    encoder.write_all(json).unwrap();
    let request = Request::builder()
        .method(Method::POST)
        .uri("/api/devices/status")
        .header(header::AUTHORIZATION, format!("Bearer {token}"))
        .header(header::CONTENT_TYPE, "application/json")
        .header(header::CONTENT_ENCODING, encoding)
        .body(Body::from(encoder.finish().unwrap()))
        .unwrap();
    app.send(request).await
}

/// The latest row: lock reason, battery level and charging, the apps, the app version; and the row count.
async fn latest(
    app: &TestApp,
    device: i64,
) -> (
    String,
    Option<i64>,
    Option<bool>,
    Option<String>,
    Option<String>,
    i64,
) {
    let (lock, level, charging, apps, version): (
        String,
        Option<i64>,
        Option<bool>,
        Option<String>,
        Option<String>,
    ) = sqlx::query_as(
        "SELECT lock_reason, battery_level, battery_charging, installed_apps_json, app_version \
             FROM device_status WHERE device_id = ? ORDER BY reported_at DESC, id DESC LIMIT 1",
    )
    .bind(device)
    .fetch_one(&app.db)
    .await
    .unwrap();
    let rows: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM device_status WHERE device_id = ?")
        .bind(device)
        .fetch_one(&app.db)
        .await
        .unwrap();
    (lock, level, charging, apps, version, rows)
}

#[test]
fn the_contract_matches_the_launchers_copy_and_the_constants() {
    let copy_path = std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("../launcher/app/src/test/resources/status_heartbeat.json");
    let copy = std::fs::read_to_string(&copy_path)
        .unwrap_or_else(|e| panic!("{}: {e}", copy_path.display()));
    assert_eq!(copy, CONTRACT, "{} differs", copy_path.display());
    let contract = contract();
    assert_eq!(contract["capability"], CAPABILITY);
    let fields: Vec<&str> = contract["heartbeat_fields"]
        .as_array()
        .unwrap()
        .iter()
        .map(|f| f.as_str().unwrap())
        .collect();
    assert_eq!(fields, HEARTBEAT_FIELDS);
    // The heartbeat carries exactly the marker, the hash and heartbeat fields.
    for key in contract["heartbeat"].as_object().unwrap().keys() {
        assert!(
            key == "heartbeat" || key == "hash" || HEARTBEAT_FIELDS.contains(&key.as_str()),
            "{key}"
        );
    }
}

#[tokio::test]
async fn a_heartbeat_stands_for_the_full_report_it_names() {
    let app = TestApp::new().await;
    let (device, token) = app.enrolled_device("phone").await;
    let contract = contract();

    // The policy says the server takes heartbeats and gzip.
    let policy = app.policy(&token).await;
    for (key, value) in contract["policy"].as_object().unwrap() {
        assert_eq!(&policy[key], value, "{key}");
    }

    // A heartbeat before any full report: the full report is asked for, nothing stored.
    let early = post(&app, &token, &contract["heartbeat"]).await;
    assert_eq!(early.status, StatusCode::OK);
    assert_eq!(early.json(), contract["answers"]["full_report"]);
    let rows: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM device_status WHERE device_id = ?")
        .bind(device)
        .fetch_one(&app.db)
        .await
        .unwrap();
    assert_eq!(rows, 0);

    // The full report is stored as ever, and kept without its heartbeat fields.
    let full = post(&app, &token, &contract["full"]).await;
    assert_eq!(full.status, StatusCode::NO_CONTENT, "{}", full.text());
    let (lock, level, charging, apps, version, rows) = latest(&app, device).await;
    assert_eq!(
        (lock.as_str(), level, charging, rows),
        ("NONE", Some(80), Some(false), 1)
    );
    let kept: String =
        sqlx::query_scalar("SELECT body_json FROM device_report_base WHERE device_id = ?")
            .bind(device)
            .fetch_one(&app.db)
            .await
            .unwrap();
    let kept: Value = serde_json::from_str(&kept).unwrap();
    for field in HEARTBEAT_FIELDS {
        assert!(kept.get(*field).is_none(), "{field} kept");
    }
    assert!(kept.get("hash").is_none());

    // The heartbeat: one more row, its own fields, everything else the full report's.
    let beat = post(&app, &token, &contract["heartbeat"]).await;
    assert_eq!(beat.status, StatusCode::NO_CONTENT, "{}", beat.text());
    let (beat_lock, beat_level, beat_charging, beat_apps, beat_version, beat_rows) =
        latest(&app, device).await;
    assert_eq!(
        (beat_lock.as_str(), beat_level, beat_charging, beat_rows),
        ("TIME_RULE", Some(79), Some(true), 2)
    );
    assert_eq!((beat_apps, beat_version), (apps, version));

    // Another hash: the full report is asked for, nothing stored.
    let mut other = contract["heartbeat"].clone();
    other["hash"] = json!("another");
    let refused = post(&app, &token, &other).await;
    assert_eq!(refused.json(), contract["answers"]["full_report"]);
    assert_eq!(latest(&app, device).await.5, 2);

    // A kept body from before the phone's latest enrollment is never used.
    sqlx::query("UPDATE devices SET enrolled_at = datetime('now', '+1 minute') WHERE id = ?")
        .bind(device)
        .execute(&app.db)
        .await
        .unwrap();
    let after_enroll = post(&app, &token, &contract["heartbeat"]).await;
    assert_eq!(after_enroll.json(), contract["answers"]["full_report"]);
    sqlx::query("UPDATE devices SET enrolled_at = datetime('now', '-1 hour') WHERE id = ?")
        .bind(device)
        .execute(&app.db)
        .await
        .unwrap();

    // An older launcher's report (no hash) forgets what was kept.
    let mut plain = contract["full"].clone();
    plain.as_object_mut().unwrap().remove("hash");
    assert_eq!(
        post(&app, &token, &plain).await.status,
        StatusCode::NO_CONTENT
    );
    let after_plain = post(&app, &token, &contract["heartbeat"]).await;
    assert_eq!(after_plain.json(), contract["answers"]["full_report"]);
}

#[tokio::test]
async fn a_gzipd_report_is_taken_within_the_limit() {
    let app = TestApp::new().await;
    let (device, token) = app.enrolled_device("phone").await;
    let contract = contract();

    let body = serde_json::to_vec(&contract["full"]).unwrap();
    let res = post_gzip(&app, &token, &body, "gzip").await;
    assert_eq!(res.status, StatusCode::NO_CONTENT, "{}", res.text());
    assert_eq!(latest(&app, device).await.5, 1);

    // Another encoding is refused.
    assert_eq!(
        post_gzip(&app, &token, &body, "br").await.status,
        StatusCode::UNSUPPORTED_MEDIA_TYPE
    );

    // A body that inflates past the device limit stops there (a few KB on the wire).
    let mut bomb = br#"{"lock_reason": "NONE", "kiosk_engaged": true, "app_version": ""#.to_vec();
    bomb.extend(std::iter::repeat_n(
        b'a',
        crate::limits::DEVICE_BODY_LIMIT + 1024,
    ));
    bomb.extend(br#""}"#);
    let res = post_gzip(&app, &token, &bomb, "gzip").await;
    assert_eq!(res.status, StatusCode::PAYLOAD_TOO_LARGE, "{}", res.text());
    assert_eq!(latest(&app, device).await.5, 1);

    // Without a token nothing is inflated.
    let request = Request::builder()
        .method(Method::POST)
        .uri("/api/devices/status")
        .header(header::CONTENT_TYPE, "application/json")
        .header(header::CONTENT_ENCODING, "gzip")
        .body(Body::from(vec![0u8; 16]))
        .unwrap();
    assert_eq!(app.send(request).await.status, StatusCode::UNAUTHORIZED);
}
