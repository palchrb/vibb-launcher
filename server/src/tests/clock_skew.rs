//! The phone's clock as its decisions see it (time rules keep their own clock): the shared rows of
//! `testdata/clock_skew.json` (byte-identical in the launcher's test resources, `ClockSkewContractTest`), the report's
//! line on Device › Time, the summary's Attention row and the Devices list's badge, and a fix stored from its age.

use axum::http::{Method, StatusCode};
use serde_json::{Value, json};

use super::TestApp;
use crate::i18n::Lang;

const CONTRACT: &str = include_str!("../../testdata/clock_skew.json");

fn contract() -> Value {
    serde_json::from_str(CONTRACT).unwrap()
}

#[test]
fn the_contract_matches_the_launchers_copy() {
    let path = std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("../launcher/app/src/test/resources/clock_skew.json");
    let copy = std::fs::read_to_string(&path).unwrap_or_else(|e| panic!("{}: {e}", path.display()));
    assert_eq!(copy, CONTRACT, "{} differs", path.display());
}

#[test]
fn the_clean_rows() {
    for row in contract()["clean"].as_array().unwrap() {
        let report = crate::time_state::TimeReport::parse(&row["time_state"].to_string()).unwrap();
        let kept = &row["kept"];
        assert_eq!(
            report.clock_skew_ms,
            kept["clock_skew_ms"].as_i64(),
            "{}",
            row["note"]
        );
        assert_eq!(
            report.clock_trust.as_deref(),
            kept["clock_trust"].as_str(),
            "{}",
            row["note"]
        );
        assert_eq!(
            report.unanchored_ms,
            kept["unanchored_ms"].as_i64(),
            "{}",
            row["note"]
        );
    }
}

#[test]
fn the_page_rows() {
    for row in contract()["pages"].as_array().unwrap() {
        let lang = if row["lang"] == "en" {
            Lang::En
        } else {
            Lang::Nb
        };
        let line = crate::clock_skew::problem(
            row["skew"].as_i64(),
            row["trust"].as_str(),
            row["unanchored"].as_i64(),
        )
        .map(|p| p.words(lang));
        assert_eq!(line.as_deref(), row["line"].as_str(), "{row}");
    }
}

#[test]
fn the_fix_age_rows() {
    for row in contract()["fix_age"].as_array().unwrap() {
        let received = chrono::DateTime::parse_from_rfc3339(row["received"].as_str().unwrap())
            .unwrap()
            .with_timezone(&chrono::Utc);
        let loc = crate::models::LocationReport {
            latitude: 59.9,
            longitude: 10.7,
            captured_at: row["captured_at"].as_str().unwrap().to_string(),
            age_ms: row["age_ms"].as_i64(),
            ..Default::default()
        };
        let stored = crate::models::checked_location_at(&loc, received);
        assert_eq!(stored.as_deref(), row["stored"].as_str(), "{}", row["note"]);
        let offset = stored
            .as_deref()
            .and_then(|s| crate::models::phone_offset_ms(&loc, s));
        assert_eq!(offset, row["phone_offset_ms"].as_i64(), "{}", row["note"]);
    }
    // The shutdown's fix ahead of the receipt is kept, stamped at it.
    let received = chrono::DateTime::parse_from_rfc3339("2026-11-24T10:00:00Z")
        .unwrap()
        .with_timezone(&chrono::Utc);
    let ahead = crate::models::LocationReport {
        latitude: 59.9,
        longitude: 10.7,
        captured_at: "2026-11-25T12:00:00.000Z".to_string(),
        ..Default::default()
    };
    let stored = crate::models::checked_shutdown_location(&ahead, received).unwrap();
    assert_eq!(stored, "2026-11-24T10:00:00.000Z");
    assert_eq!(
        crate::models::phone_offset_ms(&ahead, &stored),
        Some(26 * 3_600_000)
    );
}

/// A report whose clock is 26 hours ahead: the orange line on Device › Time, one Attention row on the summary, the
/// Devices list's badge; a clock that is right says nothing again.
#[tokio::test]
async fn a_wrong_clock_is_one_orange_line() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (anna, token) = app.enrolled_device("Anna").await;
    let report = |skew: i64| {
        json!({"time_state": {"day": "2026-11-24", "used_minutes": 0, "extra_minutes": 0, "calls_blocked": false,
            "lock_reason": "NONE", "lifts_active": [], "zone": "Europe/Oslo", "clock_skew_ms": skew,
            "clock_trust": "carried", "unanchored_ms": 0}})
    };
    app.post_status(&token, report(93_600_000)).await;
    let time = app
        .get_page(&format!("/devices/{anna}/time?lang=en"), &cookie)
        .await
        .text();
    assert!(time.contains("Clock is wrong (26\u{a0}h ahead)"), "{time}");
    assert!(
        time.contains("Rules and screen time follow the server&#x27;s time.")
            || time.contains("Rules and screen time follow the server's time."),
        "{time}"
    );
    let summary = app
        .get_page(&format!("/devices/{anna}?lang=en"), &cookie)
        .await
        .text();
    assert!(summary.contains("data-attention=\"clock\""), "{summary}");
    let list = app.get_page("/devices?lang=en", &cookie).await.text();
    assert!(list.contains("data-attention-count=\"1\""), "{list}");

    app.post_status(&token, report(0)).await;
    let time = app
        .get_page(&format!("/devices/{anna}/time?lang=en"), &cookie)
        .await
        .text();
    assert!(!time.contains("Clock is wrong"), "{time}");
    let summary = app
        .get_page(&format!("/devices/{anna}?lang=en"), &cookie)
        .await
        .text();
    assert!(!summary.contains("data-attention=\"clock\""), "{summary}");
}

/// A fix with an age is stored at the receipt minus it, whatever the phone's clock says; re-sent, it is the same row.
/// A fix that isn't one is counted, and the Locate page says the position was refused until a fix is stored.
#[tokio::test]
async fn a_fix_is_stored_from_its_age() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (anna, token) = app.enrolled_device("Anna").await;
    // The phone's clock 26 hours ahead; the fix a minute old.
    let ahead = (chrono::Utc::now() + chrono::Duration::hours(26))
        .to_rfc3339_opts(chrono::SecondsFormat::Millis, true);
    let fix = json!({"latitude": 59.91, "longitude": 10.75, "accuracy_meters": 20.0, "captured_at": ahead,
        "age_ms": 60_000});
    app.post_status(&token, json!({"location": fix})).await;
    app.post_status(&token, json!({"location": fix})).await;
    let rows: Vec<(String, Option<i64>)> = sqlx::query_as(
        "SELECT captured_at, phone_offset_ms FROM device_locations WHERE device_id = ?",
    )
    .bind(anna)
    .fetch_all(&app.db)
    .await
    .unwrap();
    assert_eq!(rows.len(), 1, "{rows:?}");
    let stored = crate::display_time::parse_db_utc(&rows[0].0).unwrap();
    let age = chrono::Utc::now() - stored;
    assert!(
        age >= chrono::Duration::seconds(59) && age < chrono::Duration::seconds(90),
        "{rows:?}"
    );
    assert!(
        rows[0]
            .1
            .is_some_and(|ms| (ms - 26 * 3_600_000 - 60_000).abs() < 30_000),
        "{rows:?}"
    );

    // A fix off the Earth: refused, counted, said.
    let bad = json!({"latitude": 95.0, "longitude": 10.75, "captured_at": ahead, "age_ms": 0});
    let res = app
        .request(
            Method::POST,
            "/api/devices/status",
            Some(token.as_str()),
            Some(json!({"lock_reason": "NONE", "kiosk_engaged": true, "location": bad})),
        )
        .await;
    assert_eq!(res.status, StatusCode::NO_CONTENT);
    let dropped: i64 = sqlx::query_scalar("SELECT fixes_dropped FROM devices WHERE id = ?")
        .bind(anna)
        .fetch_one(&app.db)
        .await
        .unwrap();
    assert_eq!(dropped, 1);
    let page = app
        .get_page(&format!("/devices/locate?device={anna}&lang=en"), &cookie)
        .await
        .text();
    assert!(page.contains("Position refused"), "{page}");
}
