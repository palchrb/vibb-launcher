//! Design 114 (the phone setup status): the shared contract `testdata/setup_steps.json` through the routes - the
//! report's `setup` stored with the hold's end, the phone's tick (`POST /api/devices/setup/ticks`) and the policy's
//! `setup`.

use axum::body::Body;
use axum::http::{Method, Request, StatusCode, header};
use serde_json::{Value, json};

use super::TestApp;

const CONTRACT: &str = include_str!("../../testdata/setup_steps.json");

pub(super) fn contract() -> Value {
    serde_json::from_str(CONTRACT).unwrap()
}

async fn stored(app: &TestApp, id: i64) -> (Option<String>, Option<i64>) {
    sqlx::query_as(
        "SELECT setup_json, setup_hold_until_ms FROM device_status WHERE device_id = ? \
         ORDER BY id DESC LIMIT 1",
    )
    .bind(id)
    .fetch_one(&app.state.db)
    .await
    .unwrap()
}

/// `report` is stored as the contract has it, and the hold's end is the earlier of its two clocks (QA L6);
/// `report_no_hold` stores no end; a report without `setup` stores nothing.
#[tokio::test]
async fn the_report_is_stored_with_the_holds_end() {
    let app = TestApp::new().await;
    let (id, token) = app.enrolled_device("Testtelefon").await;
    let contract = contract();
    let before = chrono::Utc::now().timestamp_millis();
    app.post_status(&token, json!({"setup": contract["report"]}))
        .await;
    let after = chrono::Utc::now().timestamp_millis();
    let (kept, until) = stored(&app, id).await;
    let kept: Value = serde_json::from_str(&kept.unwrap()).unwrap();
    assert_eq!(kept, contract["report"]);
    let left = contract["report"]["hold_left_ms"].as_i64().unwrap();
    let wall = contract["report"]["hold_until_ms"].as_i64().unwrap();
    let until = until.unwrap();
    // The contract's wall-clock end lies in the past of any test run: it wins the min.
    assert!(until <= wall.max(after + left));
    assert!(until == wall || (before + left..=after + left).contains(&until));

    app.post_status(&token, json!({"setup": contract["report_no_hold"]}))
        .await;
    let (kept, until) = stored(&app, id).await;
    let kept: Value = serde_json::from_str(&kept.unwrap()).unwrap();
    assert_eq!(kept, contract["report_no_hold"]);
    assert_eq!(until, None);

    app.post_status(&token, json!({})).await;
    assert_eq!(stored(&app, id).await, (None, None));
}

/// The hold's end comes from the elapsed clock when the phone's wall clock says later (QA L6): a report whose
/// `hold_until_ms` lies past `now + hold_left_ms` ends at the latter.
#[tokio::test]
async fn a_wall_clock_ahead_ends_at_the_time_left() {
    let app = TestApp::new().await;
    let (id, token) = app.enrolled_device("Testtelefon").await;
    let before = chrono::Utc::now().timestamp_millis();
    app.post_status(
        &token,
        json!({"restrictions_paused": true,
               "setup": {"device_owner": true, "hold_until_ms": before + 10 * 3_600_000_i64,
                         "hold_left_ms": 600_000}}),
    )
    .await;
    let after = chrono::Utc::now().timestamp_millis();
    let (_, until) = stored(&app, id).await;
    let until = until.unwrap();
    assert!((before + 600_000..=after + 600_000).contains(&until));
}

async fn ticks(app: &TestApp, id: i64) -> Vec<(String, bool)> {
    sqlx::query_as(
        "SELECT step, by_phone FROM device_setup_ticks WHERE device_id = ? ORDER BY step",
    )
    .bind(id)
    .fetch_all(&app.state.db)
    .await
    .unwrap()
}

/// Every `tick.answers` row through the phone's route: its status and body, the stored `by_phone` row, the phone line
/// for a new tick only, and the policy's `setup.ticked`.
#[tokio::test]
async fn the_phones_tick_answers_as_the_contract() {
    let app = TestApp::new().await;
    let (id, token) = app.enrolled_device("Testtelefon").await;
    let contract = contract();
    // "already ticked": the parent ticked it before.
    assert!(
        crate::setup_status::store_tick(&app.state.db, id, "vibration", false)
            .await
            .unwrap()
    );
    assert_eq!(
        app.policy(&token).await["setup"],
        json!({"ticked": ["vibration"]})
    );
    // The request is the contract's exact body.
    assert_eq!(
        contract["tick"]["request"],
        contract["tick"]["answers"][0]["body"]
    );
    for row in contract["tick"]["answers"].as_array().unwrap() {
        let res = app
            .request(
                Method::POST,
                "/api/devices/setup/ticks",
                Some(&token),
                Some(row["body"].clone()),
            )
            .await;
        assert_eq!(
            res.status.as_u16(),
            row["status"].as_u64().unwrap() as u16,
            "{}",
            row["name"]
        );
        match row.get("answer") {
            Some(answer) => assert_eq!(&res.json(), answer, "{}", row["name"]),
            None => assert!(res.body.is_empty(), "{}", row["name"]),
        }
    }
    assert_eq!(
        ticks(&app, id).await,
        [
            ("touch_feedback".to_string(), true),
            ("vibration".to_string(), false)
        ]
    );
    assert_eq!(
        app.policy(&token).await["setup"],
        json!({"ticked": ["vibration", "touch_feedback"]})
    );
    // One line, for the new tick only, in the reader's language.
    let lines = super::security_words(&app, "setup_tick_phone", crate::i18n::Lang::Nb).await;
    assert_eq!(lines.len(), 1, "{lines:?}");
    assert!(
        lines[0].contains("Vibrasjon ved trykk avkrysset på telefonen"),
        "{lines:?}"
    );
    // A retry ticks nothing new and writes no line.
    let res = app
        .request(
            Method::POST,
            "/api/devices/setup/ticks",
            Some(&token),
            Some(contract["tick"]["request"].clone()),
        )
        .await;
    assert_eq!(res.status, StatusCode::NO_CONTENT);
    assert_eq!(
        super::security_words(&app, "setup_tick_phone", crate::i18n::Lang::En)
            .await
            .len(),
        1
    );
}

/// A body past 256 bytes is refused before the handler (QA L7); no token, no tick.
#[tokio::test]
async fn the_tick_routes_edges() {
    let app = TestApp::new().await;
    let (id, token) = app.enrolled_device("Testtelefon").await;
    let long = format!(r#"{{"step": "vibration", "pad": "{}"}}"#, "x".repeat(220));
    assert!(long.len() > 256);
    let res = app
        .send(
            Request::builder()
                .method(Method::POST)
                .uri("/api/devices/setup/ticks")
                .header(header::AUTHORIZATION, format!("Bearer {token}"))
                .header(header::CONTENT_TYPE, "application/json")
                .body(Body::from(long))
                .unwrap(),
        )
        .await;
    assert_eq!(res.status, StatusCode::PAYLOAD_TOO_LARGE);
    let res = app
        .request(
            Method::POST,
            "/api/devices/setup/ticks",
            None,
            Some(json!({"step": "vibration"})),
        )
        .await;
    assert_eq!(res.status, StatusCode::UNAUTHORIZED);
    assert!(ticks(&app, id).await.is_empty());
}
