//! Safe zones, step 2 (design 64 §4): the phone watches - `location_policy.zones` and the served
//! interval's floor, the status report's `extra_locations` (every fix stored, then one judging)
//! and `zone_watch` (the fences, the mock counter), and the shared contract
//! `testdata/zone_status_report.json`.

use axum::http::{Method, StatusCode};
use chrono::{DateTime, Duration, Utc};
use serde_json::{Value, json};

use super::TestApp;

/// The launcher's body (`ZoneReportTest` encodes exactly this; byte-identical in its test
/// resources).
const ZONE_REPORT: &str = include_str!("../../testdata/zone_status_report.json");

/// 30 m from Home's centre (59.9139, 10.7522): inside with a good fix.
const NEAR: (f64, f64) = (59.91417, 10.7522);
/// 1 km away.
const AWAY: (f64, f64) = (59.9229, 10.7522);

#[test]
fn the_zone_contract_matches_the_launchers_copy() {
    let copy_path = std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("../launcher/app/src/test/resources/zone_status_report.json");
    let copy = std::fs::read_to_string(&copy_path)
        .unwrap_or_else(|e| panic!("{}: {e}", copy_path.display()));
    assert_eq!(copy, ZONE_REPORT, "{} differs", copy_path.display());
}

fn at(minutes_ago: i64) -> String {
    (Utc::now() - Duration::minutes(minutes_ago)).to_rfc3339()
}

fn fix(place: (f64, f64), captured_at: &str) -> Value {
    json!({"latitude": place.0, "longitude": place.1, "accuracy_meters": 20.0,
           "captured_at": captured_at})
}

fn watch(fences: i64, mock_ignored: i64) -> Value {
    json!({"fences": fences, "motion_sensor": true, "location_on": true,
           "background_location": true, "mock_ignored": mock_ignored})
}

async fn post(app: &TestApp, token: &str, extra: Value) {
    let mut body = json!({"lock_reason": "NONE", "kiosk_engaged": true});
    body.as_object_mut()
        .unwrap()
        .extend(extra.as_object().unwrap().clone());
    let res = app
        .request(Method::POST, "/api/devices/status", Some(token), Some(body))
        .await;
    assert_eq!(res.status, StatusCode::NO_CONTENT, "{}", res.text());
}

async fn count(app: &TestApp, sql: &str) -> i64 {
    sqlx::query_scalar(sql).fetch_one(&app.db).await.unwrap()
}

/// Home (r = 150 m) on the phone, straight into the table; its id.
async fn add_home(app: &TestApp, device: i64) -> i64 {
    sqlx::query_scalar(
        "INSERT INTO device_zones (device_id, name, latitude, longitude, radius_m) \
         VALUES (?, 'Home', 59.9139, 10.7522, 150) RETURNING id",
    )
    .bind(device)
    .fetch_one(&app.db)
    .await
    .unwrap()
}

async fn set_location(app: &TestApp, device: i64, mode: &str, interval: i64) {
    sqlx::query(
        "UPDATE device_policy SET location_mode = ?, location_interval_minutes = ? \
         WHERE device_id = ?",
    )
    .bind(mode)
    .bind(interval)
    .bind(device)
    .execute(&app.db)
    .await
    .unwrap();
}

async fn location_policy(app: &TestApp, token: &str) -> Value {
    app.request(Method::GET, "/api/devices/policy", Some(token), None)
        .await
        .json()["location_policy"]
        .clone()
}

/// `(kind, happened_at)` of the phone's events, oldest first.
async fn events(app: &TestApp) -> Vec<(String, String)> {
    sqlx::query_as("SELECT kind, happened_at FROM parent_events ORDER BY id")
        .fetch_all(&app.db)
        .await
        .unwrap()
}

/// The stored form of `captured_at`.
fn stored(captured_at: &str) -> String {
    crate::models::checked_location(&crate::models::LocationReport {
        latitude: 0.0,
        longitude: 0.0,
        accuracy_meters: None,
        captured_at: captured_at.to_string(),
    })
    .unwrap()
}

/// The contract with its instants moved so its newest fix is a minute old (the gaps kept).
fn the_contract_now() -> Value {
    let mut report: Value = serde_json::from_str(ZONE_REPORT).unwrap();
    let newest = DateTime::parse_from_rfc3339(report["location"]["captured_at"].as_str().unwrap())
        .unwrap()
        .with_timezone(&Utc);
    let shift = Utc::now() - Duration::minutes(1) - newest;
    let moved = |fix: &mut Value| {
        let t = DateTime::parse_from_rfc3339(fix["captured_at"].as_str().unwrap())
            .unwrap()
            .with_timezone(&Utc);
        fix["captured_at"] = json!((t + shift).to_rfc3339());
    };
    moved(&mut report["location"]);
    for extra in report["extra_locations"].as_array_mut().unwrap() {
        moved(extra);
    }
    report
}

/// The launcher's report (the contract): all three fixes stored, the zones judged once in time
/// order - away, then two at Home 2 min apart - so "arrived" at the first Home fix; the watch
/// stored; its mock count logged.
#[tokio::test]
async fn a_launcher_report_raises_the_arrival() {
    let app = TestApp::new().await;
    let (id, token) = app.enrolled_device("Anna").await;
    let home = add_home(&app, id).await;
    // Away twice before: Home's side is known (outside), so the arrival isn't the silent first.
    post(&app, &token, json!({"location": fix(AWAY, &at(50))})).await;
    post(&app, &token, json!({"location": fix(AWAY, &at(45))})).await;
    let report = the_contract_now();
    let res = app
        .request(
            Method::POST,
            "/api/devices/status",
            Some(&token),
            Some(report.clone()),
        )
        .await;
    assert_eq!(res.status, StatusCode::NO_CONTENT);
    assert_eq!(
        count(&app, "SELECT COUNT(*) FROM device_locations").await,
        5
    );
    let first_home = report["extra_locations"][1]["captured_at"]
        .as_str()
        .unwrap();
    assert_eq!(
        events(&app).await,
        [("zone_arrived".to_string(), stored(first_home))]
    );
    let side: Option<String> = sqlx::query_scalar("SELECT side FROM device_zones WHERE id = ?")
        .bind(home)
        .fetch_one(&app.db)
        .await
        .unwrap();
    assert_eq!(side.as_deref(), Some("inside"));
    let kept = crate::zones::last_zone_watch(&app.db, id)
        .await
        .unwrap()
        .unwrap();
    assert_eq!(
        kept,
        crate::zones::ZoneWatch {
            fences: 1,
            motion_sensor: true,
            location_on: true,
            background_location: true,
            mock_ignored: 2,
        }
    );
    let logged: Vec<String> = sqlx::query_scalar(
        "SELECT detail FROM security_events WHERE event_type = 'mock_location_ignored'",
    )
    .fetch_all(&app.db)
    .await
    .unwrap();
    assert_eq!(logged.len(), 1);
    assert!(logged[0].contains("ignored 2 made-up"), "{logged:?}");
    // The same report again: nothing new (the fixes are stored, the count didn't rise).
    app.request(
        Method::POST,
        "/api/devices/status",
        Some(&token),
        Some(report),
    )
    .await;
    assert_eq!(
        count(&app, "SELECT COUNT(*) FROM device_locations").await,
        5
    );
    assert_eq!(events(&app).await.len(), 1);
    assert_eq!(
        count(
            &app,
            "SELECT COUNT(*) FROM security_events WHERE event_type = 'mock_location_ignored'"
        )
        .await,
        1
    );
}

/// The policy carries the zones while Regularly - id, centre and radius, never the name - and
/// none otherwise; the served interval is at least 30 min only with zones and a watch that holds
/// fences, and the parent's choice is never written.
#[tokio::test]
async fn the_policy_serves_the_zones_and_the_floor() {
    let app = TestApp::new().await;
    let (id, token) = app.enrolled_device("Anna").await;
    // No zones: Regularly every 10 min is served as chosen, `zones` always the key.
    set_location(&app, id, "interval", 10).await;
    assert_eq!(
        location_policy(&app, &token).await,
        json!({"mode": "interval", "interval_minutes": 10, "zones": []})
    );
    let home = add_home(&app, id).await;
    // Zones but a launcher without the watch (no zone_watch): as chosen, the zones sent.
    let policy = location_policy(&app, &token).await;
    assert_eq!(policy["interval_minutes"], 10);
    assert_eq!(
        policy["zones"],
        json!([{"id": home, "latitude": 59.9139, "longitude": 10.7522, "radius_m": 150}])
    );
    assert!(!policy.to_string().contains("Home"), "no name: {policy}");
    // A watch without fences (they couldn't be registered): still as chosen.
    post(&app, &token, json!({"zone_watch": watch(0, 0)})).await;
    assert_eq!(location_policy(&app, &token).await["interval_minutes"], 10);
    // The phone holds the fence: at least 30.
    post(&app, &token, json!({"zone_watch": watch(1, 0)})).await;
    assert_eq!(location_policy(&app, &token).await["interval_minutes"], 30);
    let chosen: i64 = sqlx::query_scalar(
        "SELECT location_interval_minutes FROM device_policy WHERE device_id = ?",
    )
    .bind(id)
    .fetch_one(&app.db)
    .await
    .unwrap();
    assert_eq!(chosen, 10, "never written");
    // A longer choice stays.
    set_location(&app, id, "interval", 60).await;
    assert_eq!(location_policy(&app, &token).await["interval_minutes"], 60);
    // Only when asked: no zones on the phone, the interval as stored.
    set_location(&app, id, "on_request", 10).await;
    assert_eq!(
        location_policy(&app, &token).await,
        json!({"mode": "on_request", "interval_minutes": 10, "zones": []})
    );
    // The last zone deleted: the parent's 10 back.
    set_location(&app, id, "interval", 10).await;
    sqlx::query("DELETE FROM device_zones")
        .execute(&app.db)
        .await
        .unwrap();
    assert_eq!(
        location_policy(&app, &token).await,
        json!({"mode": "interval", "interval_minutes": 10, "zones": []})
    );
}

/// Code QA N-4: every extra fix is stored before the zones are judged, once, in time order -
/// a pair sent out of order in one report confirms at the earlier fix; more than 20 keeps the
/// newest 20; an invalid entry is dropped, never the report.
#[tokio::test]
async fn extra_locations_are_all_stored_then_judged_once() {
    let app = TestApp::new().await;
    let (id, token) = app.enrolled_device("Anna").await;
    add_home(&app, id).await;
    post(&app, &token, json!({"location": fix(AWAY, &at(60))})).await;
    post(&app, &token, json!({"location": fix(AWAY, &at(55))})).await;
    // Home twice, the later one first, and junk among them.
    let (early, late) = (at(20), at(15));
    post(
        &app,
        &token,
        json!({"extra_locations": [
            fix(NEAR, &late),
            {"latitude": 91.0, "longitude": 0.0, "captured_at": at(18)},
            {"latitude": "north"},
            fix(NEAR, &early),
        ]}),
    )
    .await;
    assert_eq!(
        count(&app, "SELECT COUNT(*) FROM device_locations").await,
        4
    );
    assert_eq!(
        events(&app).await,
        [("zone_arrived".to_string(), stored(&early))]
    );
    // Not a list at all: the report is kept, no fix.
    post(&app, &token, json!({"extra_locations": {"latitude": 1.0}})).await;
    // 25 extras: the newest 20 stored.
    let many: Vec<Value> = (0..25)
        .map(|i| fix((59.0 + i as f64 / 1000.0, 10.0), &at(200 - i)))
        .collect();
    post(&app, &token, json!({"extra_locations": many})).await;
    assert_eq!(
        count(&app, "SELECT COUNT(*) FROM device_locations").await,
        24
    );
    // The extras run 200..176 min ago, each at its own latitude: the oldest five (59.000 ..
    // 59.004) are the ones dropped.
    let lowest_kept: f64 =
        sqlx::query_scalar("SELECT MIN(latitude) FROM device_locations WHERE latitude < 59.5")
            .fetch_one(&app.db)
            .await
            .unwrap();
    assert!((lowest_kept - 59.005).abs() < 1e-9, "{lowest_kept}");
}

/// Two reports at once carrying the same crossing's fixes: whichever judges first takes them,
/// the other finds nothing - one arrival.
#[tokio::test]
async fn two_parallel_reports_raise_one_crossing() {
    let app = TestApp::new().await;
    let (id, token) = app.enrolled_device("Anna").await;
    add_home(&app, id).await;
    post(&app, &token, json!({"location": fix(AWAY, &at(60))})).await;
    post(&app, &token, json!({"location": fix(AWAY, &at(55))})).await;
    let pair = json!({"extra_locations": [fix(NEAR, &at(10))], "location": fix(NEAR, &at(5))});
    tokio::join!(
        post(&app, &token, pair.clone()),
        post(&app, &token, pair.clone())
    );
    assert_eq!(
        count(&app, "SELECT COUNT(*) FROM device_locations").await,
        4
    );
    assert_eq!(events(&app).await.len(), 1);
}

/// The mock counter is the phone's running count: a rise is one security line with the rise,
/// the same count nothing, a lower one (the data cleared) starts again silently; a `zone_watch`
/// that isn't one stores nothing.
#[tokio::test]
async fn the_mock_counter_logs_each_rise() {
    let app = TestApp::new().await;
    let (id, token) = app.enrolled_device("Anna").await;
    let lines = || async {
        sqlx::query_scalar::<_, String>(
            "SELECT detail FROM security_events WHERE event_type = 'mock_location_ignored' \
             ORDER BY id",
        )
        .fetch_all(&app.db)
        .await
        .unwrap()
    };
    post(&app, &token, json!({"zone_watch": watch(0, 0)})).await;
    assert!(lines().await.is_empty());
    post(&app, &token, json!({"zone_watch": watch(0, 3)})).await;
    post(&app, &token, json!({"zone_watch": watch(0, 3)})).await;
    post(&app, &token, json!({"zone_watch": watch(0, 5)})).await;
    post(&app, &token, json!({"zone_watch": watch(0, 1)})).await;
    let logged = lines().await;
    assert_eq!(logged.len(), 2, "{logged:?}");
    assert!(logged[0].contains(&format!("device {id}: the phone ignored 3 ")));
    assert!(
        logged[1].contains("ignored 2 made-up location(s)") && logged[1].contains("(5 in all)")
    );
    // Junk is dropped: an unknown key's value, a wrong type, a count out of range.
    for junk in [
        json!({"fences": "1", "motion_sensor": true, "location_on": true,
               "background_location": true, "mock_ignored": 0}),
        json!({"fences": -1, "motion_sensor": true, "location_on": true,
               "background_location": true, "mock_ignored": 0}),
        json!({"fences": 1}),
        json!("watching"),
    ] {
        post(&app, &token, json!({"zone_watch": junk})).await;
        assert_eq!(
            crate::zones::last_zone_watch(&app.db, id).await.unwrap(),
            None
        );
    }
    let mut extra = watch(2, 1);
    extra["place"] = json!("59.9,10.7");
    post(&app, &token, json!({"zone_watch": extra})).await;
    let stored: String =
        sqlx::query_scalar("SELECT zone_watch_json FROM device_status ORDER BY id DESC LIMIT 1")
            .fetch_one(&app.db)
            .await
            .unwrap();
    assert!(!stored.contains("place"), "{stored}");
}

/// The Locate page while the phone watches (design 64 §3.6's last row, decision 3): the note under
/// "Every" while the floor applies, the footer without "two intervals", the watch's line, and the
/// mock count in the location group.
#[tokio::test]
async fn the_locate_page_tells_the_floor_the_watch_and_the_mock_count() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("Anna").await;
    set_location(&app, id, "interval", 10).await;
    let page = || async {
        app.get_page(&format!("/devices/locate?device={id}"), &cookie)
            .await
            .text()
    };
    // No zones, no watch: the parent's 10 min, no note, no line.
    let html = page().await;
    assert!(!html.contains("data-interval-note"), "{html}");
    assert!(!html.contains("data-zone-watch"));
    assert!(!html.contains("data-row=\"mock-ignored\""));
    add_home(&app, id).await;
    // Zones and an older launcher (no watch): as step 1 - judged at each fix, two intervals.
    let html = page().await;
    assert!(!html.contains("data-interval-note"));
    assert!(
        html.contains("within about two intervals (here 20 min)"),
        "{html}"
    );
    // The phone watches: the floor's note, the watch's own footer and line.
    post(&app, &token, json!({"zone_watch": watch(1, 0)})).await;
    let html = page().await;
    assert!(
        html.contains("data-interval-note>30 min while the phone has zones<"),
        "{html}"
    );
    assert!(html.contains("as soon as it notices a"));
    assert!(!html.contains("within about two intervals"));
    assert!(
        html.contains("data-zone-watch>Watching 1 zone on the phone.<"),
        "{html}"
    );
    // The parent's choice is still what the picker shows.
    assert!(html.contains("<option value=\"10\" selected>"), "{html}");
    // No motion sensor, Location off, no background grant, no fence yet: each says so.
    let mut no_sensor = watch(1, 0);
    no_sensor["motion_sensor"] = json!(false);
    post(&app, &token, json!({"zone_watch": no_sensor})).await;
    assert!(
        page()
            .await
            .contains("no motion sensor, so it takes a location every 30 min.")
    );
    let mut off = watch(1, 0);
    off["location_on"] = json!(false);
    post(&app, &token, json!({"zone_watch": off})).await;
    assert!(
        page()
            .await
            .contains("Location is off on the phone, so it can&#39;t watch the zones.")
    );
    let mut no_grant = watch(1, 0);
    no_grant["background_location"] = json!(false);
    post(&app, &token, json!({"zone_watch": no_grant})).await;
    assert!(
        page()
            .await
            .contains("use its location in the background yet")
    );
    post(&app, &token, json!({"zone_watch": watch(0, 0)})).await;
    let html = page().await;
    assert!(html.contains("set up its zones yet"));
    assert!(!html.contains("data-interval-note"), "no fences, no floor");
    // A longer choice than the floor: no note.
    post(&app, &token, json!({"zone_watch": watch(1, 0)})).await;
    set_location(&app, id, "interval", 60).await;
    assert!(!page().await.contains("data-interval-note"));
    // The mock count, from the phone's running number.
    post(&app, &token, json!({"zone_watch": watch(1, 1)})).await;
    assert!(page().await.contains("1 made-up location ignored<"));
    post(&app, &token, json!({"zone_watch": watch(1, 4)})).await;
    let html = page().await;
    assert!(html.contains("data-row=\"mock-ignored\""));
    assert!(html.contains("4 made-up locations ignored<"), "{html}");
}
