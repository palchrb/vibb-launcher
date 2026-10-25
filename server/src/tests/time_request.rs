//! Design r13 §2.5-2.8: `POST /api/devices/time-request` - the kid's "Be om mer tid" from the
//! phone's notification: the three answers of the shared contract `testdata/time_request.json`,
//! one row per request id, the day's cap and a retried third request, the news rule, a stale
//! request, the push and each parent's "Asks for more time" switch, and Device › Time's "Asked for
//! more time at …" until a lift.

use axum::http::{Method, StatusCode};
use chrono::{Duration, Utc};
use serde_json::{Value, json};

use super::web_push::{ENDPOINT, subscribe};
use super::{TestApp, assert_redirect};

/// The launcher's body and the three answers (`TimeRequestsTest` encodes and decodes exactly
/// these; byte-identical in its test resources).
const TIME_REQUEST: &str = include_str!("../../testdata/time_request.json");

const PAPPAS_ENDPOINT: &str = "https://web.push.apple.com/QK1-pappas-fake-endpoint";

fn contract() -> Value {
    serde_json::from_str(TIME_REQUEST).unwrap()
}

#[test]
fn the_time_request_contract_matches_the_launchers_copy() {
    let copy_path = std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("../launcher/app/src/test/resources/time_request.json");
    let copy = std::fs::read_to_string(&copy_path)
        .unwrap_or_else(|e| panic!("{}: {e}", copy_path.display()));
    assert_eq!(copy, TIME_REQUEST, "{} differs", copy_path.display());
}

/// A request `minutes_ago`, with an id made from `n`.
fn body(n: u32, minutes_ago: i64) -> Value {
    json!({
        "id": format!("{n:032x}"),
        "asked_at_ms": (Utc::now() - Duration::minutes(minutes_ago)).timestamp_millis(),
    })
}

async fn post(app: &TestApp, token: &str, body: Value) -> (StatusCode, Value) {
    let res = app
        .request(
            Method::POST,
            "/api/devices/time-request",
            Some(token),
            Some(body),
        )
        .await;
    let json = if res.status == StatusCode::OK {
        res.json()
    } else {
        Value::Null
    };
    (res.status, json)
}

async fn count(app: &TestApp, device: i64) -> i64 {
    sqlx::query_scalar(
        "SELECT COUNT(*) FROM parent_events WHERE device_id = ? AND kind = 'extra_time_request'",
    )
    .bind(device)
    .fetch_one(&app.db)
    .await
    .unwrap()
}

/// A status report saying how much screen time is left today.
async fn report(app: &TestApp, token: &str, lock_reason: &str, used: i64, budget: i64) {
    let res = app
        .request(
            Method::POST,
            "/api/devices/status",
            Some(token),
            Some(json!({
                "lock_reason": lock_reason,
                "kiosk_engaged": true,
                "capabilities": ["call_policy_v1", "time_rules_v1"],
                "time_state": {
                    "day": Utc::now().date_naive().to_string(),
                    "used_minutes": used, "budget_minutes": budget, "extra_minutes": 0,
                    "lock_reason": lock_reason, "calls_blocked": false, "lifts_active": [],
                },
            })),
        )
        .await;
    assert_eq!(res.status, StatusCode::NO_CONTENT, "{}", res.text());
}

/// Mamma and Pappa each with a subscription; Pappa's "Asks for more time" off.
async fn two_parents(app: &TestApp) -> String {
    let mamma = app.admin_cookie().await;
    subscribe(app, &mamma, ENDPOINT).await;
    let pappa = app.admin_cookie_as("pappa", "Pappa").await;
    subscribe(app, &pappa, PAPPAS_ENDPOINT).await;
    let on: Vec<(&str, &str)> = crate::parent_events::Kind::switches()
        .into_iter()
        .filter(|(name, _)| *name != "push_extra_time")
        .map(|(name, _)| (name, "on"))
        .collect();
    app.request_form(Method::POST, "/notifications/settings", Some(&pappa), &on)
        .await;
    mamma
}

/// The endpoints the sender pushes to now, and the titles.
async fn pushed(app: &TestApp) -> Vec<(String, String)> {
    crate::push::send_due(&app.state, Utc::now()).await.unwrap();
    let mut to: Vec<(String, String)> = app
        .pushes
        .take()
        .into_iter()
        .map(|r| {
            let title = super::web_push::payload(&r)["notification"]["title"]
                .as_str()
                .unwrap_or_default()
                .to_string();
            (r.endpoint, title)
        })
        .collect();
    to.sort();
    to
}

/// The contract's request (its instant moved to a minute ago) and every answer row: the first
/// post, a fourth of the day, one asked yesterday.
#[tokio::test]
async fn the_contract_answers() {
    let app = TestApp::new().await;
    let (_, token) = app.enrolled_device("Anna").await;
    let contract = contract();
    let answers = contract["answers"].as_array().unwrap();
    let mut request = contract["request"].clone();
    request["asked_at_ms"] = json!((Utc::now() - Duration::minutes(1)).timestamp_millis());
    assert_eq!(
        post(&app, &token, request).await,
        (StatusCode::OK, answers[0].clone())
    );
    for n in 1..=2 {
        assert_eq!(post(&app, &token, body(n, 1)).await.1, answers[0]);
    }
    assert_eq!(
        post(&app, &token, body(9, 1)).await,
        (StatusCode::OK, answers[1].clone())
    );
    let yesterday = body(10, 26 * 60);
    assert_eq!(
        post(&app, &token, yesterday).await,
        (StatusCode::OK, answers[2].clone())
    );
    // The handler's own encodings are the rows, byte for byte.
    use crate::handlers::device_api::TimeRequestAnswer;
    let encoded: Vec<Value> = [
        TimeRequestAnswer::ACCEPTED,
        TimeRequestAnswer::CAPPED,
        TimeRequestAnswer::STALE,
    ]
    .iter()
    .map(|a| serde_json::to_value(a).unwrap())
    .collect();
    assert_eq!(&encoded, answers);
}

/// The first request: one row, worded from the server's own report, a push for the parent with
/// the switch on and none for the one with it off; the same id twice is one row.
#[tokio::test]
async fn a_request_raises_one_event_and_pushes_by_each_parents_switch() {
    let app = TestApp::new().await;
    let mamma = two_parents(&app).await;
    let (id, token) = app.enrolled_device("Anna").await;
    report(&app, &token, "NONE", 10, 90).await;
    assert_eq!(
        post(&app, &token, body(1, 0)).await.1,
        json!({"accepted": true})
    );
    assert_eq!(count(&app, id).await, 1);
    let to = pushed(&app).await;
    assert_eq!(
        to,
        [(
            ENDPOINT.to_string(),
            "Anna asks for more screen time".to_string()
        )],
        "Mamma's switch is on, Pappa's off"
    );
    // A retried POST of the same id: one row, no second push.
    assert_eq!(
        post(&app, &token, body(1, 0)).await.1,
        json!({"accepted": true})
    );
    assert_eq!(count(&app, id).await, 1);
    assert!(pushed(&app).await.is_empty());
    // The inbox tells it from the report: 80 minutes left.
    let inbox = app.get_page("/notifications", &mamma).await.text();
    assert!(inbox.contains("Anna asks for more screen time"), "{inbox}");
    assert!(inbox.contains("1 h 20 min left"), "{inbox}");
    let target: String =
        sqlx::query_scalar("SELECT target FROM parent_events WHERE kind = 'extra_time_request'")
            .fetch_one(&app.db)
            .await
            .unwrap();
    assert_eq!(target, format!("/devices/{id}/time#extra"));
    // Notify Me About lists the switch once, on by default.
    let settings = app.get_page("/notifications/settings", &mamma).await.text();
    assert_eq!(settings.matches("name=\"push_extra_time\"").count(), 1);
    assert!(settings.contains("Asks for more time"));
    assert_eq!(
        super::input_checked(&settings, "push_extra_time", "on"),
        Some(true)
    );
}

/// The day's cap (D5): the fourth request isn't stored and answers `capped`; a retry of the third
/// is taken, not counted as a fourth.
#[tokio::test]
async fn three_a_day_and_a_retried_third_is_no_fourth() {
    let app = TestApp::new().await;
    let (id, token) = app.enrolled_device("Anna").await;
    for n in 1..=3 {
        assert_eq!(
            post(&app, &token, body(n, 0)).await.1,
            json!({"accepted": true})
        );
    }
    assert_eq!(
        post(&app, &token, body(3, 0)).await.1,
        json!({"accepted": true}),
        "the third again"
    );
    assert_eq!(count(&app, id).await, 3);
    assert_eq!(
        post(&app, &token, body(4, 0)).await.1,
        json!({"accepted": false, "reason": "capped"})
    );
    assert_eq!(count(&app, id).await, 3, "the fourth isn't stored");
    // Another phone has its own three.
    let (other, other_token) = app.enrolled_device("Ola").await;
    assert_eq!(
        post(&app, &other_token, body(4, 0)).await.1,
        json!({"accepted": true})
    );
    assert_eq!(count(&app, other).await, 1);
}

/// Asked 31 minutes ago: a row, no push (old news); asked yesterday: no row, `stale`; a phone clock
/// ahead counts as now; a bad id or body is 400.
#[tokio::test]
async fn news_stale_and_refused_bodies() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    subscribe(&app, &cookie, ENDPOINT).await;
    let (id, token) = app.enrolled_device("Anna").await;
    assert_eq!(
        post(&app, &token, body(1, 31)).await.1,
        json!({"accepted": true})
    );
    assert_eq!(count(&app, id).await, 1);
    assert!(pushed(&app).await.is_empty(), "no push after 30 min");
    assert_eq!(
        post(&app, &token, body(2, 26 * 60)).await.1,
        json!({"accepted": false, "reason": "stale"})
    );
    assert_eq!(count(&app, id).await, 1);
    // A future instant is stored as now.
    assert_eq!(
        post(&app, &token, body(3, -120)).await.1,
        json!({"accepted": true})
    );
    let at: String =
        sqlx::query_scalar("SELECT happened_at FROM parent_events WHERE dedup_key = ?")
            .bind(format!("{:032x}", 3))
            .fetch_one(&app.db)
            .await
            .unwrap();
    let at = crate::display_time::parse_db_utc(&at).unwrap();
    assert!(
        at <= Utc::now() && at > Utc::now() - Duration::minutes(1),
        "{at}"
    );
    for bad in [
        json!({"id": "0123456789ABCDEF0123456789ABCDEF", "asked_at_ms": 1}),
        json!({"id": "0123456789abcde", "asked_at_ms": 1}),
        json!({"id": "x".repeat(32), "asked_at_ms": 1}),
        json!({"id": "a".repeat(65), "asked_at_ms": 1}),
        json!({"asked_at_ms": 1}),
        json!({"id": "a".repeat(32)}),
        json!("nope"),
    ] {
        assert_eq!(
            post(&app, &token, bad.clone()).await.0,
            StatusCode::BAD_REQUEST,
            "{bad}"
        );
    }
    // Without a token, nothing.
    let res = app
        .request(
            Method::POST,
            "/api/devices/time-request",
            None,
            Some(body(5, 0)),
        )
        .await;
    assert_eq!(res.status, StatusCode::UNAUTHORIZED);
}

/// Device › Time: "Asked for more time at …" at the top of Extra Time Today; after a lift
/// (budget +15) it is gone and the save lands on `#extra`; a lift made before the request leaves
/// the row.
#[tokio::test]
async fn the_time_page_shows_the_open_request_until_a_lift() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("Anna").await;
    let page = format!("/devices/{id}/time");
    let html = app.get_page(&page, &cookie).await.text();
    assert!(!html.contains("data-asked"), "no request yet");
    // A lift before the request (a minute earlier) leaves the row.
    let lifted = app
        .request_form(
            Method::POST,
            &format!("/devices/{id}/lifts"),
            Some(&cookie),
            &[("target", "budget"), ("minutes", "15"), ("back", "time")],
        )
        .await;
    assert_redirect(&lifted, &format!("{page}?done=extra"), Some("extra"));
    sqlx::query("UPDATE time_lifts SET created_at = datetime('now', '-1 minute')")
        .execute(&app.db)
        .await
        .unwrap();
    assert_eq!(
        post(&app, &token, body(1, 0)).await.1,
        json!({"accepted": true})
    );
    let html = app.get_page(&page, &cookie).await.text();
    let extra = &html[html.find("<section id=\"extra\"").unwrap()..];
    let extra = &extra[..extra.find("</section>").unwrap()];
    assert!(
        extra.contains("data-asked>Asked for more time at "),
        "{extra}"
    );
    assert!(
        extra.find("data-asked").unwrap() < extra.find("act-grid").unwrap(),
        "above the buttons"
    );
    // The answer: +15 min, back at #extra, the row gone.
    let lifted = app
        .request_form(
            Method::POST,
            &format!("/devices/{id}/lifts"),
            Some(&cookie),
            &[("target", "budget"), ("minutes", "15"), ("back", "time")],
        )
        .await;
    assert_redirect(&lifted, &format!("{page}?done=extra"), Some("extra"));
    let html = app.get_page(&page, &cookie).await.text();
    assert!(!html.contains("data-asked"), "answered by the lift");
}

#[test]
fn request_ids_are_lowercase_hex_of_16_to_64() {
    use crate::handlers::device_api::time_request_id_ok;
    assert!(time_request_id_ok(&"a".repeat(16)));
    assert!(time_request_id_ok(&"0f".repeat(32)));
    assert!(!time_request_id_ok(&"a".repeat(15)));
    assert!(!time_request_id_ok(&"a".repeat(65)));
    assert!(!time_request_id_ok("0123456789abcdeG"));
    assert!(!time_request_id_ok(""));
}
