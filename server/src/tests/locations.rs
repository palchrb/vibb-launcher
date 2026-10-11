//! Location fixes from the phone (security review H2): only real fixes are stored, and the locate
//! page never turns a stored value into HTML or script.

use axum::http::{Method, StatusCode};
use serde_json::json;

use super::TestApp;

async fn report(app: &TestApp, token: &str, location: serde_json::Value) {
    let res = app
        .request(
            Method::POST,
            "/api/devices/status",
            Some(token),
            Some(json!({ "lock_reason": "NONE", "kiosk_engaged": true, "location": location })),
        )
        .await;
    assert_eq!(res.status, StatusCode::NO_CONTENT, "{}", res.text());
}

async fn stored(app: &TestApp, device: i64) -> Vec<(String, f64, f64)> {
    sqlx::query_as(
        "SELECT captured_at, latitude, longitude FROM device_locations WHERE device_id = ? \
         ORDER BY id",
    )
    .bind(device)
    .fetch_all(&app.db)
    .await
    .unwrap()
}

#[tokio::test]
async fn only_real_fixes_are_stored() {
    let app = TestApp::new().await;
    let (id, token) = app.enrolled_device("phone").await;
    for bad in [
        json!({"latitude": 59.9, "longitude": 10.7, "accuracy_meters": 5.0,
               "captured_at": "z<img src=x onerror=alert(1)>"}),
        json!({"latitude": 59.9, "longitude": 10.7, "accuracy_meters": 5.0, "captured_at": "z"}),
        json!({"latitude": 59.9, "longitude": 10.7, "accuracy_meters": 5.0,
               "captured_at": "2026-10-10T12:00:00Z<script>"}),
        json!({"latitude": 91.0, "longitude": 10.7, "accuracy_meters": 5.0,
               "captured_at": "2026-10-10T12:00:00Z"}),
        json!({"latitude": 59.9, "longitude": -180.5, "accuracy_meters": 5.0,
               "captured_at": "2026-10-10T12:00:00Z"}),
        json!({"latitude": 59.9, "longitude": 10.7, "accuracy_meters": -1.0,
               "captured_at": "2026-10-10T12:00:00Z"}),
    ] {
        report(&app, &token, bad.clone()).await;
        assert!(stored(&app, id).await.is_empty(), "stored {bad}");
    }
    // The rest of such a report is kept.
    let reports: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM device_status WHERE device_id = ?")
        .bind(id)
        .fetch_one(&app.db)
        .await
        .unwrap();
    assert_eq!(reports, 6);

    // What the launcher sends (Instant.toString()), stored normalised to UTC milliseconds.
    report(
        &app,
        &token,
        json!({"latitude": 59.9, "longitude": 10.7, "accuracy_meters": 12.4,
               "captured_at": "2026-10-10T12:00:00.123456789Z"}),
    )
    .await;
    report(
        &app,
        &token,
        json!({"latitude": -33.9, "longitude": 151.2, "accuracy_meters": null,
               "captured_at": "2026-10-10T14:00:01+02:00"}),
    )
    .await;
    assert_eq!(
        stored(&app, id).await,
        [
            ("2026-10-10T12:00:00.123Z".to_string(), 59.9, 10.7),
            ("2026-10-10T12:00:01.000Z".to_string(), -33.9, 151.2),
        ]
    );
}

#[tokio::test]
async fn the_locate_page_builds_no_html_from_stored_values() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, _) = app.enrolled_device("phone").await;
    // A row from before the intake check (as an older server stored it).
    sqlx::query(
        "INSERT INTO device_locations (device_id, latitude, longitude, captured_at) \
         VALUES (?, 1.0, 2.0, ?)",
    )
    .bind(id)
    .bind("\"</script><script>alert(1)</script>")
    .execute(&app.db)
    .await
    .unwrap();
    // The page's script: the popup is a text node, and the newest fix's time a JSON string
    // (the page waiting for a fresh fix is where it is used).
    let page = app
        .get_page(&format!("/devices/locate?device={id}&requested=1"), &cookie)
        .await
        .text();
    assert!(!page.contains("bindPopup('Last seen ' +"), "string popup");
    assert!(page.contains("popup.textContent = 'Last seen '"));
    assert!(
        page.contains(
            r#"var shown = "\"\u003c/script\u003e\u003cscript\u003ealert(1)\u003c/script\u003e";"#
        ),
        "{page}"
    );
    assert!(!page.contains("<script>alert(1)"));
}

#[test]
fn js_strings_cant_close_the_script() {
    use crate::handlers::locate::js_string;
    assert_eq!(
        js_string("2026-10-10T12:00:00.000Z"),
        "\"2026-10-10T12:00:00.000Z\""
    );
    assert_eq!(
        js_string("</script>&\"\u{2028}"),
        r#""\u003c/script\u003e\u0026\"\u2028""#
    );
}

/// Migration 0054 removes what the old intake let in.
#[tokio::test]
async fn the_cleanup_migration_removes_bad_rows() {
    let app = TestApp::new().await;
    let (id, _) = app.enrolled_device("phone").await;
    for (captured_at, latitude) in [
        ("2026-10-10T12:00:00.123Z", 59.9),
        ("2026-10-10T12:00:00+02:00", 59.9),
        ("z", 59.9),
        ("<img src=x onerror=alert(1)>", 59.9),
        ("2026-10-10T12:00:00Z<b>", 59.9),
        ("2026-10-10T12:00:00Z", 95.0),
    ] {
        sqlx::query(
            "INSERT INTO device_locations (device_id, latitude, longitude, captured_at) \
             VALUES (?, ?, 10.0, ?)",
        )
        .bind(id)
        .bind(latitude)
        .bind(captured_at)
        .execute(&app.db)
        .await
        .unwrap();
    }
    sqlx::query(include_str!("../../migrations/0054_location_cleanup.sql"))
        .execute(&app.db)
        .await
        .unwrap();
    let left: Vec<String> = stored(&app, id).await.into_iter().map(|r| r.0).collect();
    assert_eq!(
        left,
        ["2026-10-10T12:00:00.123Z", "2026-10-10T12:00:00+02:00"]
    );
}
