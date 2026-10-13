//! Location fixes from the phone (security review H2): only real fixes are stored, and the locate
//! page never turns a stored value into HTML or script.

use axum::http::{Method, StatusCode, header};
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
        // From the future (QA of the security wave, Low 3): it would stay "the newest fix".
        json!({"latitude": 59.9, "longitude": 10.7, "accuracy_meters": 5.0,
               "captured_at": "9999-12-31T23:59:59Z"}),
        json!({"latitude": 59.9, "longitude": 10.7, "accuracy_meters": 5.0,
               "captured_at": (chrono::Utc::now() + chrono::Duration::minutes(10)).to_rfc3339()}),
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
    assert_eq!(reports, 8);

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
    // A phone clock a minute ahead is still a fix.
    let ahead = chrono::Utc::now() + chrono::Duration::minutes(1);
    report(
        &app,
        &token,
        json!({"latitude": 1.0, "longitude": 2.0, "accuracy_meters": 3.0,
               "captured_at": ahead.to_rfc3339()}),
    )
    .await;
    assert_eq!(stored(&app, id).await.len(), 3);
    sqlx::query("DELETE FROM device_locations WHERE latitude = 1.0")
        .execute(&app.db)
        .await
        .unwrap();
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
    assert!(!page.contains("bindPopup('"), "string popup");
    assert!(page.contains("popup.textContent = last.captured_at === shown ? shownText"));
    // A stored time that doesn't read has no age, and is never shown as it is.
    assert!(page.contains("Time unknown"));
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

/// Security review L12 (server part): a command the phone fetched is "delivered", not done; one
/// it never confirms is shown as possibly lost, so the parent sends it again. A wipe can't confirm.
#[tokio::test]
async fn a_delivered_command_is_not_shown_as_done() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, _) = app.enrolled_device("phone").await;
    for (command, delivered, acknowledged, result) in [
        ("ring", None, None, None),
        ("lock", Some("datetime('now', '-1 minutes')"), None, None),
        (
            "stop_ring",
            Some("datetime('now', '-30 minutes')"),
            None,
            None,
        ),
        ("wipe", Some("datetime('now', '-30 minutes')"), None, None),
        (
            "locate",
            Some("datetime('now', '-30 minutes')"),
            Some("'2026-10-11 10:00:00'"),
            Some("'ok'"),
        ),
    ] {
        sqlx::query(&format!(
            "INSERT INTO device_commands (device_id, command, delivered_at, acknowledged_at, result) \
             VALUES (?, ?, {}, {}, {})",
            delivered.unwrap_or("NULL"),
            acknowledged.unwrap_or("NULL"),
            result.unwrap_or("NULL"),
        ))
        .bind(id)
        .bind(command)
        .execute(&app.db)
        .await
        .unwrap();
    }
    let page = app
        .get_page(&format!("/devices/locate?device={id}"), &cookie)
        .await
        .text();
    let row = |command: &str| super::locate_page::recent_row(&page, command).to_string();
    assert!(row("ring").contains(">Waiting<"), "{}", row("ring"));
    assert!(
        row("lock").contains("Not confirmed yet."),
        "{}",
        row("lock")
    );
    assert!(
        row("stop_ring").contains(">Not confirmed<"),
        "{}",
        row("stop_ring")
    );
    assert!(row("stop_ring").contains("Send it again."));
    assert!(
        row("wipe").contains("can&#39;t confirm an erase"),
        "{}",
        row("wipe")
    );
    assert!(!row("wipe").contains("Send it again"));
    // A result is done; an answer this server doesn't know is shown as the phone sent it.
    assert!(row("locate").contains(">Done<"), "{}", row("locate"));
    assert!(row("locate").contains(">ok<"), "{}", row("locate"));
}

/// QA of the security wave, Low 3: a future fix stored before the intake refused them is pruned,
/// so the real newest fix is the map's again.
#[tokio::test]
async fn a_stored_future_fix_is_pruned() {
    let app = TestApp::new().await;
    let (id, token) = app.enrolled_device("phone").await;
    report(
        &app,
        &token,
        json!({"latitude": 59.9, "longitude": 10.7, "accuracy_meters": 5.0,
               "captured_at": chrono::Utc::now().to_rfc3339()}),
    )
    .await;
    sqlx::query(
        "INSERT INTO device_locations (device_id, latitude, longitude, captured_at) \
         VALUES (?, 1.0, 2.0, '9999-12-31T23:59:59.000Z')",
    )
    .bind(id)
    .execute(&app.db)
    .await
    .unwrap();
    crate::retention::prune(&app.db).await.unwrap();
    let left = stored(&app, id).await;
    assert_eq!(left.len(), 1, "{left:?}");
    assert_eq!((left[0].1, left[0].2), (59.9, 10.7));
}

/// Design 33: OSM refuses tiles without a `Referer`, and the admin pages send `Referrer-Policy:
/// same-origin`. The map's tile layer alone asks for `strict-origin-when-cross-origin` (Leaflet
/// sets it on each tile's `<img>`), so OSM gets our origin and nothing more; the page's own policy
/// and the CSP's one image host stay as they are.
#[tokio::test]
async fn the_map_loads_osm_tiles_with_an_origin_only_referer() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (device, _) = app.create_device("Ella").await;
    let page = app
        .get_page(&format!("/devices/locate?device={device}"), &cookie)
        .await;
    assert_eq!(page.status, StatusCode::OK);
    let html = page.text();
    let layer = html
        .split("L.tileLayer(")
        .nth(1)
        .and_then(|rest| rest.split("}).addTo(map)").next())
        .expect("the tile layer");
    assert!(
        layer.starts_with("'https://tile.openstreetmap.org/{z}/{x}/{y}.png'"),
        "{layer}"
    );
    assert!(
        layer.contains("referrerPolicy: 'strict-origin-when-cross-origin'"),
        "{layer}"
    );
    assert!(
        layer.contains("https://www.openstreetmap.org/copyright"),
        "{layer}"
    );
    let header = |name| {
        page.headers
            .get(name)
            .and_then(|v| v.to_str().ok())
            .unwrap_or_default()
            .to_string()
    };
    assert_eq!(header(header::REFERRER_POLICY), "same-origin");
    assert!(
        header(header::CONTENT_SECURITY_POLICY)
            .contains("img-src 'self' data: https://tile.openstreetmap.org;"),
        "{}",
        header(header::CONTENT_SECURITY_POLICY)
    );
}
