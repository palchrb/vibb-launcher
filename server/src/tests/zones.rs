//! Safe zones, step 1 (design 64): the fix stored once, every stored fix judged once in time
//! order, one event per crossing (inbox always, push while news, the switches), the zone's page
//! and its refusals, the Locate page's group, retention and the security log.

use axum::http::{Method, StatusCode, header};
use chrono::{Duration, Utc};
use serde_json::json;

use super::web_push::{ENDPOINT, subscribe};
use super::{TestApp, assert_redirect, form_action_of};

/// 30 m from Home's centre (59.9139, 10.7522): inside with a good fix.
const NEAR: (f64, f64) = (59.91417, 10.7522);
/// 1 km away.
const AWAY: (f64, f64) = (59.9229, 10.7522);

const PAPPAS_ENDPOINT: &str = "https://web.push.apple.com/QK1-pappas-fake-endpoint";

fn at(minutes_ago: i64) -> String {
    (Utc::now() - Duration::minutes(minutes_ago)).to_rfc3339()
}

/// A status report carrying one fix (accuracy 20 m).
async fn report(app: &TestApp, token: &str, place: (f64, f64), captured_at: &str) {
    let res = app
        .request(
            Method::POST,
            "/api/devices/status",
            Some(token),
            Some(json!({
                "lock_reason": "NONE",
                "kiosk_engaged": true,
                "location": {"latitude": place.0, "longitude": place.1,
                             "accuracy_meters": 20.0, "captured_at": captured_at},
            })),
        )
        .await;
    assert_eq!(res.status, StatusCode::NO_CONTENT, "{}", res.text());
}

async fn count(app: &TestApp, sql: &str) -> i64 {
    sqlx::query_scalar(sql).fetch_one(&app.db).await.unwrap()
}

/// Adds a zone through its form; its id.
async fn add_zone(app: &TestApp, cookie: &str, device: i64, fields: &[(&str, &str)]) -> i64 {
    let res = app
        .request_form(
            Method::POST,
            &format!("/devices/{device}/zones"),
            Some(cookie),
            fields,
        )
        .await;
    assert_eq!(res.status, StatusCode::SEE_OTHER, "{}", res.text());
    sqlx::query_scalar("SELECT MAX(id) FROM device_zones")
        .fetch_one(&app.db)
        .await
        .unwrap()
}

async fn add_home(app: &TestApp, cookie: &str, device: i64) -> i64 {
    add_zone(
        app,
        cookie,
        device,
        &[
            ("name", "Home"),
            ("latitude", "59.9139"),
            ("longitude", "10.7522"),
            ("radius_m", "150"),
            ("notify_arrive", "on"),
            ("notify_leave", "on"),
        ],
    )
    .await
}

async fn side(app: &TestApp, zone: i64) -> Option<String> {
    sqlx::query_scalar("SELECT side FROM device_zones WHERE id = ?")
        .bind(zone)
        .fetch_one(&app.db)
        .await
        .unwrap()
}

/// Mamma and Pappa each with a subscription; Pappa's "Zones" switch off.
async fn two_parents(app: &TestApp) -> String {
    let mamma = app.admin_cookie().await;
    subscribe(app, &mamma, ENDPOINT).await;
    let pappa = app.admin_cookie_as("pappa", "Pappa").await;
    subscribe(app, &pappa, PAPPAS_ENDPOINT).await;
    let on: Vec<(&str, &str)> = crate::parent_events::Kind::switches()
        .into_iter()
        .filter(|(name, _)| *name != "push_zones")
        .map(|(name, _)| (name, "on"))
        .collect();
    app.request_form(Method::POST, "/notifications/settings", Some(&pappa), &on)
        .await;
    mamma
}

async fn pushed_to(app: &TestApp) -> Vec<String> {
    crate::push::send_due(&app.state, Utc::now()).await.unwrap();
    let mut to: Vec<String> = app.pushes.take().into_iter().map(|r| r.endpoint).collect();
    to.sort();
    to
}

#[tokio::test]
async fn a_resent_fix_is_stored_once() {
    let app = TestApp::new().await;
    let (id, token) = app.enrolled_device("Anna").await;
    let fix = at(10);
    for _ in 0..3 {
        report(&app, &token, NEAR, &fix).await;
    }
    let rows = "SELECT COUNT(*) FROM device_locations";
    assert_eq!(
        count(&app, rows).await,
        1,
        "the cached fix in every report is one fix"
    );
    // The same instant written another way is the same fix (stored normalised).
    let same = chrono::DateTime::parse_from_rfc3339(&fix)
        .unwrap()
        .with_timezone(&chrono::FixedOffset::east_opt(3600).unwrap())
        .to_rfc3339();
    report(&app, &token, NEAR, &same).await;
    assert_eq!(count(&app, rows).await, 1);
    // Another time, or another place at that time, is another fix.
    report(&app, &token, NEAR, &at(9)).await;
    report(&app, &token, AWAY, &fix).await;
    assert_eq!(count(&app, rows).await, 3);
    // Another phone's identical fix is its own.
    let (_, other) = app.enrolled_device("Emil").await;
    report(&app, &other, NEAR, &fix).await;
    assert_eq!(count(&app, rows).await, 4);
    let mine: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM device_locations WHERE device_id = ?")
        .bind(id)
        .fetch_one(&app.db)
        .await
        .unwrap();
    assert_eq!(mine, 3);
}

/// The whole pipeline: the first side silent, a crossing raised once at its first fix, the inbox
/// worded from the parameters, the push to the parent whose switch is on.
#[tokio::test]
async fn a_report_pair_raises_one_arrival_for_the_parents_who_want_it() {
    let app = TestApp::new().await;
    let mamma = two_parents(&app).await;
    let (id, token) = app.enrolled_device("Anna").await;
    let home = add_home(&app, &mamma, id).await;
    // Away twice, a minute or more apart: the first side, silent.
    report(&app, &token, AWAY, &at(40)).await;
    report(&app, &token, AWAY, &at(35)).await;
    assert_eq!(side(&app, home).await.as_deref(), Some("outside"));
    assert_eq!(count(&app, "SELECT COUNT(*) FROM parent_events").await, 0);
    // Home: one fix is pending, the second confirms - at the first's time.
    let first = at(10);
    report(&app, &token, NEAR, &first).await;
    assert_eq!(count(&app, "SELECT COUNT(*) FROM parent_events").await, 0);
    let second = at(5);
    report(&app, &token, NEAR, &second).await;
    // The cached fix again and again: nothing more.
    report(&app, &token, NEAR, &second).await;
    report(&app, &token, NEAR, &second).await;
    let events: Vec<(String, String, String, String, Option<String>, String)> = sqlx::query_as(
        "SELECT kind, dedup_key, title, summary, params, happened_at FROM parent_events",
    )
    .fetch_all(&app.db)
    .await
    .unwrap();
    assert_eq!(events.len(), 1, "{events:?}");
    let (kind, key, title, summary, params, happened) = &events[0];
    assert_eq!(kind, "zone_arrived");
    let stored_first = crate::models::checked_location(&crate::models::LocationReport {
        latitude: NEAR.0,
        longitude: NEAR.1,
        accuracy_meters: Some(20.0),
        captured_at: first.clone(),
    })
    .unwrap();
    assert_eq!(key, &format!("{home}:inside:{stored_first}"));
    assert_eq!(
        happened, &stored_first,
        "the crossing's time is the first fix of the pair"
    );
    assert_eq!(
        (title.as_str(), summary.as_str()),
        ("", ""),
        "no prose stored"
    );
    let params: serde_json::Value = serde_json::from_str(params.as_deref().unwrap()).unwrap();
    assert_eq!(params["zone"], "Home");
    assert_eq!(params["phone"], "Anna");
    assert_eq!(params["direction"], "arrived");
    assert_eq!(params["zone_id"], home);
    assert_eq!(side(&app, home).await.as_deref(), Some("inside"));
    // Pushed to Mamma only (Pappa's "Zones" is off), with the words.
    let to = pushed_to(&app).await;
    assert_eq!(to, [ENDPOINT]);
    // The inbox says it, from the parameters.
    let inbox = app.get_page("/notifications", &mamma).await;
    assert!(
        inbox.text().contains("Anna arrived at Home"),
        "{}",
        inbox.text()
    );
    // And leaving again is one more.
    report(&app, &token, AWAY, &at(3)).await;
    report(&app, &token, AWAY, &at(1)).await;
    let kinds: Vec<String> = sqlx::query_scalar("SELECT kind FROM parent_events ORDER BY id")
        .fetch_all(&app.db)
        .await
        .unwrap();
    assert_eq!(kinds, ["zone_arrived", "zone_left"]);
    let push: serde_json::Value = {
        crate::push::send_due(&app.state, Utc::now()).await.unwrap();
        let sent = app.pushes.take();
        assert_eq!(sent.len(), 1);
        super::web_push::payload(&sent[0])
    };
    assert_eq!(push["notification"]["title"], "Anna left Home", "{push}");
}

#[tokio::test]
async fn the_switches_and_old_news() {
    let app = TestApp::new().await;
    let mamma = two_parents(&app).await;
    let (id, token) = app.enrolled_device("Anna").await;
    // Arrive off: an arrival is no event at all; leaving still is.
    let school = add_zone(
        &app,
        &mamma,
        id,
        &[
            ("name", "School"),
            ("latitude", "59.9139"),
            ("longitude", "10.7522"),
            ("radius_m", "150"),
            ("notify_leave", "on"),
        ],
    )
    .await;
    report(&app, &token, AWAY, &at(50)).await;
    report(&app, &token, AWAY, &at(45)).await;
    report(&app, &token, NEAR, &at(40)).await;
    report(&app, &token, NEAR, &at(39)).await;
    assert_eq!(side(&app, school).await.as_deref(), Some("inside"));
    assert_eq!(count(&app, "SELECT COUNT(*) FROM parent_events").await, 0);
    // A departure confirmed by a fix 3 hours old (a phone back after a day without data): in the
    // inbox, never pushed.
    sqlx::query("DELETE FROM device_locations")
        .execute(&app.db)
        .await
        .unwrap();
    sqlx::query("UPDATE device_zones SET evaluated_at = NULL, evaluated_fix_id = NULL")
        .execute(&app.db)
        .await
        .unwrap();
    report(&app, &token, AWAY, &at(200)).await;
    report(&app, &token, AWAY, &at(180)).await;
    assert_eq!(
        count(
            &app,
            "SELECT COUNT(*) FROM parent_events WHERE kind = 'zone_left'"
        )
        .await,
        1
    );
    assert_eq!(count(&app, "SELECT COUNT(*) FROM push_deliveries").await, 0);
    assert!(pushed_to(&app).await.is_empty());
}

#[tokio::test]
async fn a_moved_zone_forgets_its_side_and_a_rename_keeps_it() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("Anna").await;
    report(&app, &token, AWAY, &at(60)).await;
    let home = add_home(&app, &cookie, id).await;
    // The history before the zone is never judged: its cursor is at the newest fix.
    let cursor: Option<i64> = sqlx::query_scalar("SELECT evaluated_fix_id FROM device_zones")
        .fetch_one(&app.db)
        .await
        .unwrap();
    assert_eq!(cursor, Some(1));
    report(&app, &token, NEAR, &at(20)).await;
    report(&app, &token, NEAR, &at(10)).await;
    assert_eq!(side(&app, home).await.as_deref(), Some("inside"));
    assert_eq!(count(&app, "SELECT COUNT(*) FROM parent_events").await, 0);
    let save = |fields: Vec<(&'static str, &'static str)>| {
        let app = &app;
        let cookie = cookie.clone();
        async move {
            let res = app
                .request_form(
                    Method::POST,
                    &format!("/devices/{id}/zones/{home}"),
                    Some(&cookie),
                    &fields,
                )
                .await;
            assert_redirect(
                &res,
                &format!("/devices/locate?device={id}&zone=saved&zone_id={home}"),
                Some(&format!("zone-{home}")),
            );
            app.assert_lands(&res, &cookie).await;
        }
    };
    // A rename and a switch: the side stays.
    save(vec![
        ("name", "Hjemme"),
        ("latitude", "59.913900"),
        ("longitude", "10.752200"),
        ("radius_m", "150"),
        ("notify_arrive", "on"),
    ])
    .await;
    assert_eq!(side(&app, home).await.as_deref(), Some("inside"));
    // A wider circle: unknown again, silently set by the next pair.
    save(vec![
        ("name", "Hjemme"),
        ("latitude", "59.913900"),
        ("longitude", "10.752200"),
        ("radius_m", "300"),
        ("notify_arrive", "on"),
    ])
    .await;
    assert_eq!(side(&app, home).await, None);
    report(&app, &token, NEAR, &at(4)).await;
    report(&app, &token, NEAR, &at(2)).await;
    assert_eq!(side(&app, home).await.as_deref(), Some("inside"));
    assert_eq!(count(&app, "SELECT COUNT(*) FROM parent_events").await, 0);
    // The security log names the phone and the zone, never a coordinate.
    let log: Vec<(String, String)> = sqlx::query_as(
        "SELECT event_type, detail FROM security_events WHERE event_type LIKE 'zone_%' ORDER BY id",
    )
    .fetch_all(&app.db)
    .await
    .unwrap();
    assert_eq!(
        log,
        [
            (
                "zone_added".to_string(),
                format!("device {id} (Anna): Home, 150 m")
            ),
            (
                "zone_changed".to_string(),
                format!("device {id} (Anna): Hjemme - renamed from Home, leave off")
            ),
            (
                "zone_changed".to_string(),
                format!("device {id} (Anna): Hjemme - radius 150 -> 300 m")
            ),
        ]
    );
    assert!(
        log.iter()
            .all(|(_, d)| !d.contains("59.") && !d.contains("10.75"))
    );
    // A new phone in the row: the sides go, the old phone's fixes are never judged again.
    let code = app.new_code(id, crate::enrollment::Kind::Qr).await;
    let res = app
        .request(
            Method::POST,
            "/api/devices/enroll",
            None,
            Some(json!({ "enrollment_code": code })),
        )
        .await;
    assert_eq!(res.status, StatusCode::OK, "{}", res.text());
    assert_eq!(side(&app, home).await, None);
}

/// A fix stored but not judged (a crash between the two - they are one transaction, so this is
/// the self-healing for anything else) is judged at the next report, once.
#[tokio::test]
async fn a_stored_fix_the_zones_have_not_judged_is_judged_next_time_once() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("Anna").await;
    let home = add_home(&app, &cookie, id).await;
    let t45 = at(45);
    report(&app, &token, AWAY, &at(50)).await;
    report(&app, &token, AWAY, &t45).await;
    // Two fixes at Home written past the intake.
    for minutes in [20, 15] {
        sqlx::query(
            "INSERT INTO device_locations (device_id, latitude, longitude, accuracy_meters, \
             captured_at) VALUES (?, ?, ?, 20, ?)",
        )
        .bind(id)
        .bind(NEAR.0)
        .bind(NEAR.1)
        .bind(crate::zones::instant(
            Utc::now() - Duration::minutes(minutes),
        ))
        .execute(&app.db)
        .await
        .unwrap();
    }
    assert_eq!(side(&app, home).await.as_deref(), Some("outside"));
    // The next report re-sends an old fix: nothing new stored, the two judged.
    report(&app, &token, AWAY, &t45).await;
    assert_eq!(
        count(&app, "SELECT COUNT(*) FROM device_locations").await,
        4
    );
    assert_eq!(side(&app, home).await.as_deref(), Some("inside"));
    assert_eq!(count(&app, "SELECT COUNT(*) FROM parent_events").await, 1);
    report(&app, &token, AWAY, &t45).await;
    assert_eq!(count(&app, "SELECT COUNT(*) FROM parent_events").await, 1);
    // A late fix older than the cursor is stored for the trail, never judged.
    report(&app, &token, AWAY, &at(30)).await;
    report(&app, &token, AWAY, &at(29)).await;
    assert_eq!(side(&app, home).await.as_deref(), Some("inside"));
    assert_eq!(
        count(&app, "SELECT COUNT(*) FROM device_locations").await,
        6
    );
}

#[tokio::test]
async fn zone_events_go_with_the_location_history() {
    let app = TestApp::new().await;
    let (id, _) = app.enrolled_device("Anna").await;
    for (kind, key) in [
        ("zone_arrived", "a"),
        ("zone_left", "b"),
        ("unseen_boot", "c"),
    ] {
        sqlx::query(
            "INSERT INTO parent_events (device_id, kind, dedup_key, title, summary, target, \
             happened_at, created_at) VALUES (?, ?, ?, '', '', '/x', '', datetime('now', '-8 days'))",
        )
        .bind(id)
        .bind(kind)
        .bind(key)
        .execute(&app.db)
        .await
        .unwrap();
    }
    // Kept 14 days: all stay.
    sqlx::query("UPDATE device_policy SET location_retention_days = 14")
        .execute(&app.db)
        .await
        .unwrap();
    crate::retention::prune(&app.db).await.unwrap();
    assert_eq!(count(&app, "SELECT COUNT(*) FROM parent_events").await, 3);
    // 7 days: the zone events go, the other kind keeps its 90 days.
    sqlx::query("UPDATE device_policy SET location_retention_days = 7")
        .execute(&app.db)
        .await
        .unwrap();
    let pruned = crate::retention::prune(&app.db).await.unwrap();
    assert_eq!(pruned.parent_events, 2);
    let left: Vec<String> = sqlx::query_scalar("SELECT kind FROM parent_events")
        .fetch_all(&app.db)
        .await
        .unwrap();
    assert_eq!(left, ["unseen_boot"]);
}

#[tokio::test]
async fn the_zone_page_adds_saves_and_deletes_landing_on_its_row() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("Anna").await;
    let (other, _) = app.enrolled_device("Emil").await;
    // No fix: the form says so, the coordinates empty.
    let page = app
        .get_page(&format!("/devices/{id}/zones/new"), &cookie)
        .await;
    assert_eq!(page.status, StatusCode::OK);
    let html = page.text();
    assert!(html.contains("data-note=\"no-fix\""));
    assert_eq!(
        form_action_of(&html, "name"),
        Some(format!("/devices/{id}/zones#zone-form").as_str())
    );
    assert!(html.contains("id=\"zone-form\""));
    assert!(html.contains("value=\"150\""), "the default radius");
    assert_eq!(
        page.headers
            .get(header::CACHE_CONTROL)
            .map(|v| v.to_str().unwrap()),
        Some("no-store")
    );
    // With a fix the new zone starts there.
    report(&app, &token, NEAR, &at(5)).await;
    let html = app
        .get_page(&format!("/devices/{id}/zones/new"), &cookie)
        .await
        .text();
    assert!(html.contains("value=\"59.914170\""), "{html}");
    assert!(html.contains("data-use-phone"));
    // Added: back at its row on Locate, with its line there.
    let res = app
        .request_form(
            Method::POST,
            &format!("/devices/{id}/zones"),
            Some(&cookie),
            &[
                ("name", "Home"),
                ("latitude", "59.9139"),
                ("longitude", "10.7522"),
                ("radius_m", "150"),
                ("notify_arrive", "on"),
                ("notify_leave", "on"),
            ],
        )
        .await;
    let home: i64 = count(&app, "SELECT MAX(id) FROM device_zones").await;
    assert_redirect(
        &res,
        &format!("/devices/locate?device={id}&zone=added&zone_id={home}"),
        Some(&format!("zone-{home}")),
    );
    app.assert_lands(&res, &cookie).await;
    let locate = app.get_page(res.location().unwrap(), &cookie).await.text();
    assert!(locate.contains("data-flash=\"zone\">Added."));
    assert!(locate.contains("150 m · arrive and leave"));
    // Its page, the delete confirm; another phone's path to it is a 404.
    let html = app
        .get_page(&format!("/devices/{id}/zones/{home}"), &cookie)
        .await
        .text();
    assert!(html.contains("Delete Home?"));
    assert_eq!(
        form_action_of(&html, "name"),
        Some(format!("/devices/{id}/zones/{home}#zone-form").as_str())
    );
    for path in [
        format!("/devices/{other}/zones/{home}"),
        format!("/devices/{id}/zones/999"),
        "/devices/999/zones/new".to_string(),
    ] {
        assert_eq!(
            app.get_page(&path, &cookie).await.status,
            StatusCode::NOT_FOUND,
            "{path}"
        );
    }
    let res = app
        .request_form(
            Method::POST,
            &format!("/devices/{other}/zones/{home}/delete"),
            Some(&cookie),
            &[],
        )
        .await;
    assert_eq!(res.status, StatusCode::NOT_FOUND);
    // zones.json: the circles, no-store.
    let json = app
        .get_page(&format!("/devices/{id}/zones.json"), &cookie)
        .await;
    assert_eq!(
        json.headers
            .get(header::CACHE_CONTROL)
            .map(|v| v.to_str().unwrap()),
        Some("no-store")
    );
    assert_eq!(
        json.json(),
        json!([{"id": home, "name": "Home", "latitude": 59.9139, "longitude": 10.7522,
                "radius_m": 150, "here": false}])
    );
    // Deleted: back to #zones with the line.
    let res = app
        .request_form(
            Method::POST,
            &format!("/devices/{id}/zones/{home}/delete"),
            Some(&cookie),
            &[],
        )
        .await;
    assert_redirect(
        &res,
        &format!("/devices/locate?device={id}&zone=deleted"),
        Some("zones"),
    );
    app.assert_lands(&res, &cookie).await;
    assert_eq!(count(&app, "SELECT COUNT(*) FROM device_zones").await, 0);
    let log = "SELECT detail FROM security_events WHERE event_type = 'zone_deleted'";
    let detail: String = sqlx::query_scalar(log).fetch_one(&app.db).await.unwrap();
    assert_eq!(detail, format!("device {id} (Anna): Home"));
}

#[tokio::test]
async fn every_refusal_comes_back_at_the_form_with_nothing_written() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, _) = app.enrolled_device("Anna").await;
    let home = add_home(&app, &cookie, id).await;
    let long = "x".repeat(41);
    let base = |name: &'static str| {
        vec![
            ("name", name),
            ("latitude", "59.95"),
            ("longitude", "10.7"),
            ("radius_m", "200"),
        ]
    };
    let with = |mut fields: Vec<(&'static str, &'static str)>, key: &str, value: &'static str| {
        for field in fields.iter_mut() {
            if field.0 == key {
                field.1 = value;
            }
        }
        fields
    };
    let long: &'static str = Box::leak(long.into_boxed_str());
    let cases: Vec<(Vec<(&str, &str)>, &str)> = vec![
        (base(""), "name-error"),
        (base(long), "name-error"),
        (base("HOME"), "name-error"),
        (with(base("School"), "latitude", "95"), "latitude-error"),
        (with(base("School"), "latitude", "abc"), "latitude-error"),
        (with(base("School"), "longitude", "181"), "longitude-error"),
        (with(base("School"), "radius_m", "99"), "radius-error"),
        (with(base("School"), "radius_m", "2001"), "radius-error"),
    ];
    for path in [
        format!("/devices/{id}/zones"),
        format!("/devices/{id}/zones/{home}"),
    ] {
        for (fields, error) in &cases {
            if path.ends_with(&home.to_string()) && fields[0].1 == "HOME" {
                // Its own name in another case is the same zone renamed, not taken.
                continue;
            }
            let res = app
                .request_form(Method::POST, &path, Some(&cookie), fields)
                .await;
            assert_eq!(res.status, StatusCode::BAD_REQUEST, "{path} {fields:?}");
            let html = res.text();
            assert!(
                html.contains(&format!("id=\"{error}\"")),
                "{path} {fields:?}"
            );
            assert!(html.contains("aria-invalid=\"true\""));
            assert!(
                html.contains("#zone-form\" id=\"zone-form\""),
                "the answer is at the form"
            );
            assert!(html.contains("data-flash=\"refused\""));
        }
    }
    let stored: (String, f64, i64) =
        sqlx::query_as("SELECT name, latitude, radius_m FROM device_zones")
            .fetch_one(&app.db)
            .await
            .unwrap();
    assert_eq!(
        stored,
        ("Home".to_string(), 59.9139, 150),
        "nothing written"
    );
    // Renaming to its own name in other letters is fine.
    let res = app
        .request_form(
            Method::POST,
            &format!("/devices/{id}/zones/{home}"),
            Some(&cookie),
            &base("HOME"),
        )
        .await;
    assert_eq!(res.status, StatusCode::SEE_OTHER);
    // The 11th zone.
    for n in 1..10 {
        let name: &'static str = Box::leak(format!("Zone {n}").into_boxed_str());
        add_zone(&app, &cookie, id, &base(name)).await;
    }
    assert_eq!(count(&app, "SELECT COUNT(*) FROM device_zones").await, 10);
    let res = app
        .request_form(
            Method::POST,
            &format!("/devices/{id}/zones"),
            Some(&cookie),
            &base("Zone 11"),
        )
        .await;
    assert_eq!(res.status, StatusCode::BAD_REQUEST);
    assert!(res.text().contains("10 zones is the most for one phone"));
    assert_eq!(count(&app, "SELECT COUNT(*) FROM device_zones").await, 10);
    let locate = app
        .get_page(&format!("/devices/locate?device={id}"), &cookie)
        .await
        .text();
    assert!(locate.contains("data-row=\"zones-full\""));
    assert!(!locate.contains("data-row=\"add-zone\""));
    // Another phone may have its own Home.
    let (emil, _) = app.enrolled_device("Emil").await;
    add_home(&app, &cookie, emil).await;
}

#[tokio::test]
async fn the_locate_page_says_what_the_location_setting_means_for_zones() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, _) = app.enrolled_device("Anna").await;
    let page = || async {
        app.get_page(&format!("/devices/locate?device={id}"), &cookie)
            .await
            .text()
    };
    // No zones: Add Zone and a line, no warnings.
    let html = page().await;
    assert!(html.contains("id=\"zones\""));
    assert!(html.contains("data-row=\"add-zone\""));
    assert!(!html.contains("data-warning=\"zones-need-regularly\""));
    assert!(!html.contains(" data-zones"));
    add_home(&app, &cookie, id).await;
    // Only when asked (a new phone's default): the orange row and Locate Regularly.
    let html = page().await;
    assert!(html.contains("data-warning=\"zones-need-regularly\""));
    assert!(html.contains(" data-zones"), "the map asks for the circles");
    let action = format!("/devices/{id}/location-policy#zones");
    assert!(html.contains(&format!("action=\"{action}\"")));
    let res = app
        .request_form(
            Method::POST,
            &format!("/devices/{id}/location-policy"),
            Some(&cookie),
            &[("mode", "interval"), ("back", "zones")],
        )
        .await;
    assert_redirect(&res, &format!("/devices/locate?device={id}"), Some("zones"));
    app.assert_lands(&res, &cookie).await;
    let mode: (String, i64) = sqlx::query_as(
        "SELECT location_mode, location_interval_minutes FROM device_policy WHERE device_id = ?",
    )
    .bind(id)
    .fetch_one(&app.db)
    .await
    .unwrap();
    assert_eq!(mode, ("interval".to_string(), 30), "the interval kept");
    // Regularly: the footer's timing, no warning.
    let html = page().await;
    assert!(!html.contains("data-warning=\"zones-need-regularly\""));
    assert!(
        html.contains("within about two intervals (here 1 hour)"),
        "{html}"
    );
    // "Keep location on" off: the kid can turn it off.
    assert!(!html.contains("data-note=\"location-unlocked\""));
    sqlx::query("UPDATE device_policy SET lock_location = 0")
        .execute(&app.db)
        .await
        .unwrap();
    assert!(page().await.contains("data-note=\"location-unlocked\""));
    // Off: the location-off row says the zones see nothing.
    sqlx::query("UPDATE device_policy SET location_mode = 'off'")
        .execute(&app.db)
        .await
        .unwrap();
    let html = page().await;
    assert!(html.contains("Zones can't see where the phone is."));
    assert!(!html.contains("data-note=\"location-unlocked\""));
}

#[tokio::test]
async fn here_now_the_fix_badge_and_the_summary_line() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("Anna").await;
    let home = add_home(&app, &cookie, id).await;
    report(&app, &token, NEAR, &at(20)).await;
    report(&app, &token, NEAR, &at(10)).await;
    assert_eq!(side(&app, home).await.as_deref(), Some("inside"));
    let html = app
        .get_page(&format!("/devices/locate?device={id}"), &cookie)
        .await
        .text();
    assert!(html.contains("Within 20 m · At Home"), "{html}");
    assert!(
        html.contains("· within 20 m · at Home\""),
        "the popup's words"
    );
    assert!(html.contains("data-here>Here now"));
    let summary = app
        .get_page(&format!("/devices/{id}"), &cookie)
        .await
        .text();
    assert!(
        summary.contains("data-zone-now>At Home since "),
        "{summary}"
    );
    // Notify Me About has the switch once.
    let settings = app
        .get_page("/notifications/settings", &cookie)
        .await
        .text();
    assert_eq!(settings.matches("name=\"push_zones\"").count(), 1);
    assert!(settings.contains("Zones: arrived and left"));
}

#[tokio::test]
async fn a_zone_needs_a_session() {
    let app = TestApp::new().await;
    let (id, _) = app.enrolled_device("Anna").await;
    for (method, path) in [
        (Method::GET, format!("/devices/{id}/zones.json")),
        (Method::GET, format!("/devices/{id}/zones/new")),
        (Method::POST, format!("/devices/{id}/zones")),
    ] {
        let res = app.request_form(method, &path, None, &[]).await;
        assert_eq!(res.location(), Some("/login"), "{path}");
    }
}
