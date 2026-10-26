//! Dagen (design 69): the policy's `calendar`, the status report's `calendar` and the shared contract
//! `testdata/calendar_status_report.json`; the Calendar pages further down.

use axum::http::{Method, StatusCode};
use serde_json::{Value, json};

use super::TestApp;

/// The launcher's body (`CalendarReportTest` encodes exactly this; byte-identical in its test resources).
const CALENDAR_REPORT: &str = include_str!("../../testdata/calendar_status_report.json");

/// A rule with this id, in order.
async fn add_rule(app: &TestApp, device: i64, id: i64, sort: i64, sql_values: &str) {
    sqlx::query(&format!(
        "INSERT INTO device_calendar_rules \
         (id, device_id, sort_order, calendar_key, field, match_kind, pattern, action, replacement) \
         VALUES ({id}, {device}, {sort}, {sql_values})"
    ))
    .execute(&app.db)
    .await
    .unwrap();
}

#[tokio::test]
async fn a_launcher_report_lists_the_calendars() {
    let app = TestApp::new().await;
    let (id, token) = app.enrolled_device("Anna").await;
    add_rule(
        &app,
        id,
        9,
        0,
        "NULL, 'title', 'contains', '6. og 7. trinn', 'hide', ''",
    )
    .await;
    let report: Value = serde_json::from_str(CALENDAR_REPORT).unwrap();
    let res = app
        .request(
            Method::POST,
            "/api/devices/status",
            Some(&token),
            Some(report.clone()),
        )
        .await;
    assert_eq!(res.status, StatusCode::NO_CONTENT);
    let kept = crate::calendar::last_report(&app.db, id)
        .await
        .unwrap()
        .unwrap();
    assert!(kept.readable);
    let keys: Vec<&str> = kept.calendars.iter().map(|c| c.key.as_str()).collect();
    assert_eq!(
        keys,
        ["9f3a61c2d84be705", "c04e7b19a2f6d853", "51d8e0aa3b7c9462"]
    );
    assert_eq!(
        kept.calendars[0].source.as_deref(),
        Some("skole.kommune.no")
    );
    assert_eq!(kept.calendars[2].source, None);
    assert_eq!(kept.calendars[1].access_level, 700);
    assert_eq!(kept.rules_skipped, [9]);

    // A report without `calendar` (an older launcher) leaves the last one; a rule id the phone doesn't have is
    // dropped from the next.
    let res = app
        .request(
            Method::POST,
            "/api/devices/status",
            Some(&token),
            Some(json!({"lock_reason": "NONE", "kiosk_engaged": true})),
        )
        .await;
    assert_eq!(res.status, StatusCode::NO_CONTENT);
    assert_eq!(
        crate::calendar::last_report(&app.db, id).await.unwrap(),
        Some(kept)
    );
    let mut again = report;
    again["calendar"]["rules_skipped"] = json!([9, 12]);
    again["calendar"]["readable"] = json!(false);
    app.request(
        Method::POST,
        "/api/devices/status",
        Some(&token),
        Some(again),
    )
    .await;
    let kept = crate::calendar::last_report(&app.db, id)
        .await
        .unwrap()
        .unwrap();
    assert!(!kept.readable);
    assert_eq!(kept.rules_skipped, [9]);
}

#[test]
fn the_contract_is_identical_to_the_launchers_copy() {
    let theirs =
        std::fs::read_to_string("../launcher/app/src/test/resources/calendar_status_report.json")
            .expect("the launcher's copy");
    assert_eq!(CALENDAR_REPORT, theirs);
}

#[tokio::test]
async fn the_policy_carries_the_rules_and_the_calendars() {
    let app = TestApp::new().await;
    let (id, token) = app.enrolled_device("Anna").await;
    add_rule(
        &app,
        id,
        7,
        1,
        "'9f3a61c2d84be705', 'title', 'starts', 'Gruppe 2- skoleåret 2026/2027:', 'prefix', 'Skole:'",
    )
    .await;
    add_rule(
        &app,
        id,
        9,
        0,
        "NULL, 'description', 'contains', '6. og 7. trinn', 'hide', ''",
    )
    .await;
    sqlx::query(
        "INSERT INTO device_calendars (device_id, calendar_key, shown, remind, remind_minutes, writable_for_kid) \
         VALUES (?, 'c04e7b19a2f6d853', 1, 'before', 15, 1)",
    )
    .bind(id)
    .execute(&app.db)
    .await
    .unwrap();
    sqlx::query(
        "UPDATE device_policy SET calendar_glance_next = 1, calendar_lock_reminders = 'row', \
         calendar_on_home = 0, calendar_kid_adds = 0 WHERE device_id = ?",
    )
    .bind(id)
    .execute(&app.db)
    .await
    .unwrap();
    let policy = app
        .request(Method::GET, "/api/devices/policy", Some(&token), None)
        .await
        .json();
    assert_eq!(
        policy["calendar"],
        json!({
            "on_home": false,
            "kid_adds": false,
            "glance_next": true,
            "lock_reminders": "row",
            "calendars": [{"key": "c04e7b19a2f6d853", "shown": true, "remind": "before", "remind_minutes": 15,
                           "writable_for_kid": true}],
            "rules": [
                {"id": 9, "calendar": null, "field": "description", "match": "contains",
                 "pattern": "6. og 7. trinn", "action": "hide", "text": ""},
                {"id": 7, "calendar": "9f3a61c2d84be705", "field": "title", "match": "starts",
                 "pattern": "Gruppe 2- skoleåret 2026/2027:", "action": "prefix", "text": "Skole:"}
            ]
        })
    );
    // At most one calendar a phone is the kid's.
    let second = sqlx::query(
        "INSERT INTO device_calendars (device_id, calendar_key, writable_for_kid) VALUES (?, '9f3a61c2d84be705', 1)",
    )
    .bind(id)
    .execute(&app.db)
    .await;
    assert!(second.is_err());
}
