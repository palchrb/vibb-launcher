//! Design 98 (Flymodus through the Digital assistant role): the shared contract `testdata/airplane_mode.json` - the
//! report stored as the contract has it, the `clean` rows, the emergency line's rule, the copies identical.

use axum::http::Method;
use serde_json::{Value, json};

use super::TestApp;
use crate::airplane_mode::{CAPABILITY, Period, clean, emergency_line};

const CONTRACT: &str = include_str!("../../testdata/airplane_mode.json");

fn contract() -> Value {
    serde_json::from_str(CONTRACT).unwrap()
}

async fn stored(app: &TestApp, id: i64) -> Option<String> {
    sqlx::query_scalar(
        "SELECT airplane_mode_json FROM device_status WHERE device_id = ? ORDER BY id DESC LIMIT 1",
    )
    .bind(id)
    .fetch_one(&app.state.db)
    .await
    .unwrap()
}

/// The report rows are stored exactly; an older launcher's report stores nothing.
#[tokio::test]
async fn the_report_is_stored_as_the_contract_has_it() {
    let app = TestApp::new().await;
    let (id, token) = app.enrolled_device("Emma").await;
    let contract = contract();
    assert_eq!(contract["capability"], CAPABILITY);
    assert_eq!(contract["bit"], 64);
    for key in ["report", "report_open"] {
        let res = app
            .request(
                Method::POST,
                "/api/devices/status",
                Some(&token),
                Some(json!({
                    "lock_reason": "NONE",
                    "kiosk_engaged": true,
                    "capabilities": [CAPABILITY],
                    "airplane_mode": contract[key],
                })),
            )
            .await;
        assert!(res.status.is_success(), "{} {}", res.status, res.text());
        let kept: Value = serde_json::from_str(&stored(&app, id).await.unwrap()).unwrap();
        assert_eq!(kept, contract[key], "{key}");
    }
    let res = app
        .request(
            Method::POST,
            "/api/devices/status",
            Some(&token),
            Some(json!({"lock_reason": "NONE", "kiosk_engaged": true})),
        )
        .await;
    assert!(res.status.is_success(), "{}", res.text());
    assert_eq!(stored(&app, id).await, None);
}

#[test]
fn the_clean_rows() {
    for row in contract()["clean"].as_array().unwrap() {
        let now = row["now_ms"].as_i64().unwrap();
        let cleaned = clean(&row["in"], now).map(|s| serde_json::from_str::<Value>(&s).unwrap());
        assert_eq!(
            cleaned.unwrap_or(Value::Null),
            row["out"],
            "{}",
            row["case"]
        );
    }
}

#[test]
fn the_emergency_line_rows() {
    for row in contract()["emergency"]["line"].as_array().unwrap() {
        let period: Period = serde_json::from_value(json!({
            "on_ms": row["period"]["on_ms"],
            "on_by": "kid",
            "off_ms": row["period"]["off_ms"],
            "off_by": row["period"]["off_by"],
        }))
        .unwrap();
        assert_eq!(
            emergency_line(row["call_ms"].as_i64().unwrap(), &period),
            row["line"].as_bool().unwrap(),
            "{}",
            row["case"]
        );
    }
}

#[test]
fn the_contract_is_identical_to_the_launchers_copy() {
    let launcher = std::fs::read_to_string(concat!(
        env!("CARGO_MANIFEST_DIR"),
        "/../launcher/app/src/test/resources/airplane_mode.json"
    ))
    .unwrap();
    assert_eq!(launcher, CONTRACT, "the launcher's copy differs");
}
