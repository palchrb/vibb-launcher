//! Time rules, screen time, lifts and the location policy (handy step 6).

use axum::http::{Method, StatusCode};
use serde_json::json;

use super::TestApp;

async fn policy(app: &TestApp, token: &str) -> serde_json::Value {
    let res = app
        .request(Method::GET, "/api/devices/policy", Some(token), None)
        .await;
    assert_eq!(res.status, StatusCode::OK, "{}", res.text());
    res.json()
}

const SCHOOL_FORM: &[(&str, &str)] = &[
    ("scope", ""),
    ("name", "Skole"),
    ("kind", "school"),
    ("exempt_apps", "org.fossify.calendar"),
    ("d0_start", "08:15"),
    ("d0_end", "14:00"),
    ("d4_start", "08:15"),
    ("d4_end", "14:00"),
];

#[tokio::test]
async fn fresh_policy_has_no_rules_unlimited_budget_and_on_request_location() {
    let app = TestApp::new().await;
    let (_, token) = app.enrolled_device("phone").await;
    let p = policy(&app, &token).await;
    assert_eq!(
        p["time_policy"],
        json!({"rules": [], "daily_budget_minutes": [null, null, null, null, null, null, null], "lifts": []})
    );
    assert_eq!(
        p["location_policy"],
        json!({"mode": "on_request", "interval_minutes": 30})
    );
}

#[tokio::test]
async fn global_rule_reaches_every_device_and_nudges_them() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (a, token_a) = app.enrolled_device("a").await;
    let (b, token_b) = app.enrolled_device("b").await;
    let mut nudges = app.state.command_notify.subscribe();

    let res = app
        .request_form(Method::POST, "/schedules/rules", Some(&cookie), SCHOOL_FORM)
        .await;
    assert_eq!(res.location(), Some("/schedules"), "{}", res.text());
    let mut nudged = vec![nudges.try_recv().unwrap(), nudges.try_recv().unwrap()];
    nudged.sort_unstable();
    assert_eq!(nudged, vec![a.min(b), a.max(b)]);

    for token in [&token_a, &token_b] {
        let rules = policy(&app, token).await["time_policy"]["rules"].clone();
        assert_eq!(rules.as_array().unwrap().len(), 1);
        let rule = &rules[0];
        assert_eq!(rule["name"], "Skole");
        assert_eq!(rule["kind"], "school");
        assert_eq!(rule["calls_allowed"], false);
        assert_eq!(rule["exempt_apps"], json!(["org.fossify.calendar"]));
        assert_eq!(
            rule["days"],
            json!([{"start": 495, "end": 840}, null, null, null, {"start": 495, "end": 840}, null, null])
        );
        assert!(rule["id"].as_i64().unwrap() > 0);
    }

    let page = app.get_page("/schedules", &cookie).await;
    assert_eq!(page.status, StatusCode::OK);
    assert!(page.text().contains("Skole"));
    assert!(page.text().contains("Mon 08:15-14:00"));
}

#[tokio::test]
async fn device_override_uses_its_own_rules_and_budget() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("phone").await;
    app.request_form(Method::POST, "/schedules/rules", Some(&cookie), SCHOOL_FORM)
        .await;
    app.request_form(
        Method::POST,
        "/schedules/global",
        Some(&cookie),
        &[("b0", "60"), ("b5", "120")],
    )
    .await;
    let scope = id.to_string();
    let res = app
        .request_form(
            Method::POST,
            "/schedules/rules",
            Some(&cookie),
            &[
                ("scope", &scope),
                ("name", "Leggetid"),
                ("kind", "bedtime"),
                ("calls_allowed", "on"),
                ("d6_start", "21:00"),
                ("d6_end", "07:00"),
            ],
        )
        .await;
    assert_eq!(
        res.location(),
        Some(format!("/schedules?device={id}").as_str())
    );

    // Not in force until the override is on.
    let p = policy(&app, &token).await;
    assert_eq!(p["time_policy"]["rules"][0]["name"], "Skole");
    assert_eq!(
        p["time_policy"]["daily_budget_minutes"],
        json!([60, null, null, null, null, 120, null])
    );

    let mut nudges = app.state.command_notify.subscribe();
    let res = app
        .request_form(
            Method::POST,
            &format!("/schedules/device/{id}"),
            Some(&cookie),
            &[("custom_schedule_enabled", "on"), ("b2", "45")],
        )
        .await;
    assert!(res.status.is_redirection(), "{}", res.text());
    assert_eq!(nudges.try_recv().ok(), Some(id));
    let p = policy(&app, &token).await;
    let rules = p["time_policy"]["rules"].as_array().unwrap();
    assert_eq!(rules.len(), 1);
    assert_eq!(rules[0]["name"], "Leggetid");
    assert_eq!(rules[0]["calls_allowed"], true);
    assert_eq!(rules[0]["days"][6], json!({"start": 1260, "end": 420}));
    assert_eq!(
        p["time_policy"]["daily_budget_minutes"],
        json!([null, null, 45, null, null, null, null])
    );

    let page = app
        .get_page(&format!("/schedules?device={id}"), &cookie)
        .await;
    assert_eq!(page.status, StatusCode::OK);
    assert!(page.text().contains("Leggetid"));
}

#[tokio::test]
async fn rule_edit_and_delete_are_scoped() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("phone").await;
    app.request_form(Method::POST, "/schedules/rules", Some(&cookie), SCHOOL_FORM)
        .await;
    let rule_id: i64 = sqlx::query_scalar("SELECT id FROM time_rules")
        .fetch_one(&app.db)
        .await
        .unwrap();

    // Through a device's scope: 404, nothing changed.
    let scope = id.to_string();
    let mut wrong = SCHOOL_FORM.to_vec();
    wrong[0] = ("scope", &scope);
    wrong[1] = ("name", "Hacked");
    let res = app
        .request_form(
            Method::POST,
            &format!("/schedules/rules/{rule_id}"),
            Some(&cookie),
            &wrong,
        )
        .await;
    assert_eq!(res.status, StatusCode::NOT_FOUND);
    let res = app
        .request_form(
            Method::POST,
            &format!("/schedules/rules/{rule_id}/delete"),
            Some(&cookie),
            &[("scope", &scope)],
        )
        .await;
    assert_eq!(res.status, StatusCode::NOT_FOUND);
    // Unknown rule / unknown device scope.
    let res = app
        .request_form(
            Method::POST,
            "/schedules/rules/9999",
            Some(&cookie),
            SCHOOL_FORM,
        )
        .await;
    assert_eq!(res.status, StatusCode::NOT_FOUND);
    let mut ghost = SCHOOL_FORM.to_vec();
    ghost[0] = ("scope", "9999");
    let res = app
        .request_form(Method::POST, "/schedules/rules", Some(&cookie), &ghost)
        .await;
    assert_eq!(res.status, StatusCode::NOT_FOUND);

    // Edit through the right scope.
    let mut edit = SCHOOL_FORM.to_vec();
    edit[1] = ("name", "School");
    edit.push(("calls_allowed", "on"));
    let res = app
        .request_form(
            Method::POST,
            &format!("/schedules/rules/{rule_id}"),
            Some(&cookie),
            &edit,
        )
        .await;
    assert!(res.status.is_redirection(), "{}", res.text());
    let rule = policy(&app, &token).await["time_policy"]["rules"][0].clone();
    assert_eq!(rule["name"], "School");
    assert_eq!(rule["calls_allowed"], true);

    let res = app
        .request_form(
            Method::POST,
            &format!("/schedules/rules/{rule_id}/delete"),
            Some(&cookie),
            &[("scope", "")],
        )
        .await;
    assert!(res.status.is_redirection());
    assert_eq!(
        policy(&app, &token).await["time_policy"]["rules"],
        json!([])
    );
}

#[tokio::test]
async fn invalid_rule_and_budget_forms_are_rejected() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let mut nudges = app.state.command_notify.subscribe();
    for bad in [
        vec![("scope", ""), ("name", ""), ("kind", "school")],
        vec![("scope", ""), ("name", "x"), ("kind", "nap")],
        vec![
            ("scope", ""),
            ("name", "x"),
            ("kind", "custom"),
            ("d1_start", "08:00"),
        ],
        vec![
            ("scope", ""),
            ("name", "x"),
            ("kind", "custom"),
            ("exempt_apps", "not a package"),
        ],
    ] {
        let res = app
            .request_form(Method::POST, "/schedules/rules", Some(&cookie), &bad)
            .await;
        assert_eq!(res.status, StatusCode::BAD_REQUEST, "{bad:?}");
    }
    let res = app
        .request_form(
            Method::POST,
            "/schedules/global",
            Some(&cookie),
            &[("b0", "2000")],
        )
        .await;
    assert_eq!(res.status, StatusCode::BAD_REQUEST);
    let res = app
        .request_form(
            Method::POST,
            "/schedules/device/9999",
            Some(&cookie),
            &[("b0", "10")],
        )
        .await;
    assert_eq!(res.status, StatusCode::NOT_FOUND);
    let rules: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM time_rules")
        .fetch_one(&app.db)
        .await
        .unwrap();
    assert_eq!(rules, 0);
    assert!(nudges.try_recv().is_err());
}

#[tokio::test]
async fn corrupt_stored_time_data_is_a_500_not_a_default() {
    for statement in [
        "INSERT INTO time_rules (name, kind, calls_allowed, days_json) VALUES ('x', 'custom', 1, '[1,2]')",
        "INSERT INTO time_rules (name, kind, calls_allowed, exempt_apps_json, days_json) \
         VALUES ('x', 'custom', 1, 'nope', '[null,null,null,null,null,null,null]')",
        "UPDATE global_schedule SET daily_budget_json = '[60]'",
    ] {
        let app = TestApp::new().await;
        let (_, token) = app.enrolled_device("phone").await;
        sqlx::query(statement).execute(&app.db).await.unwrap();
        let res = app
            .request(Method::GET, "/api/devices/policy", Some(&token), None)
            .await;
        assert_eq!(res.status, StatusCode::INTERNAL_SERVER_ERROR, "{statement}");
        assert!(res.body.is_empty());
    }
}

#[tokio::test]
async fn lifts_are_recorded_delivered_and_end() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("phone").await;
    app.request_form(Method::POST, "/schedules/rules", Some(&cookie), SCHOOL_FORM)
        .await;
    let rule_id: i64 = sqlx::query_scalar("SELECT id FROM time_rules")
        .fetch_one(&app.db)
        .await
        .unwrap();

    let mut nudges = app.state.command_notify.subscribe();
    let rule = rule_id.to_string();
    let res = app
        .request_form(
            Method::POST,
            &format!("/devices/{id}/lifts"),
            Some(&cookie),
            &[("target", "rule"), ("rule", &rule), ("minutes", "30")],
        )
        .await;
    assert_eq!(
        res.location(),
        Some(format!("/devices/{id}").as_str()),
        "{}",
        res.text()
    );
    assert_eq!(nudges.try_recv().ok(), Some(id));
    let res = app
        .request_form(
            Method::POST,
            &format!("/devices/{id}/lifts"),
            Some(&cookie),
            &[("target", "budget"), ("minutes", "15")],
        )
        .await;
    assert!(res.status.is_redirection());

    let lifts = policy(&app, &token).await["time_policy"]["lifts"].clone();
    let lifts = lifts.as_array().unwrap();
    assert_eq!(lifts.len(), 2);
    assert_eq!(lifts[0]["target"], "rule");
    assert_eq!(lifts[0]["rule_id"], rule_id);
    assert_eq!(lifts[0]["minutes"], 30);
    let now_ms = chrono::Utc::now().timestamp_millis();
    let expires = lifts[0]["expires_at_ms"].as_i64().unwrap();
    assert!((expires - now_ms - 30 * 60_000).abs() < 10_000, "{expires}");
    assert_eq!(lifts[1]["target"], "budget");
    assert_eq!(lifts[1]["rule_id"], serde_json::Value::Null);
    assert_eq!(lifts[1]["minutes"], 15);

    let events: i64 =
        sqlx::query_scalar("SELECT COUNT(*) FROM security_events WHERE event_type = 'time_lift'")
            .fetch_one(&app.db)
            .await
            .unwrap();
    assert_eq!(events, 2);
    let page = app.get_page(&format!("/devices/{id}"), &cookie).await;
    assert!(page.text().contains("Skole lifted for 30 min"));
    assert!(page.text().contains("End now"));
    assert!(page.text().contains("+15 min screen time"));

    // End the rule lift early: no longer delivered; ending it again is a 404.
    let lift_id = lifts[0]["id"].as_i64().unwrap();
    let res = app
        .request_form(
            Method::POST,
            &format!("/devices/{id}/lifts/{lift_id}/end"),
            Some(&cookie),
            &[],
        )
        .await;
    assert!(res.status.is_redirection(), "{}", res.text());
    let lifts = policy(&app, &token).await["time_policy"]["lifts"].clone();
    assert_eq!(lifts.as_array().unwrap().len(), 1);
    let res = app
        .request_form(
            Method::POST,
            &format!("/devices/{id}/lifts/{lift_id}/end"),
            Some(&cookie),
            &[],
        )
        .await;
    assert_eq!(res.status, StatusCode::NOT_FOUND);

    // An expired lift isn't delivered.
    sqlx::query("UPDATE time_lifts SET expires_at = datetime('now', '-1 minute')")
        .execute(&app.db)
        .await
        .unwrap();
    assert_eq!(
        policy(&app, &token).await["time_policy"]["lifts"],
        json!([])
    );
    let page = app.get_page(&format!("/devices/{id}"), &cookie).await;
    assert!(page.text().contains("Ended early"));
}

#[tokio::test]
async fn invalid_lifts_are_rejected() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, _) = app.enrolled_device("phone").await;
    let (other, _) = app.enrolled_device("other").await;
    // A rule of another device (its own, override off) doesn't apply here.
    let scope = other.to_string();
    let mut form = SCHOOL_FORM.to_vec();
    form[0] = ("scope", &scope);
    app.request_form(Method::POST, "/schedules/rules", Some(&cookie), &form)
        .await;
    let foreign: i64 = sqlx::query_scalar("SELECT id FROM time_rules")
        .fetch_one(&app.db)
        .await
        .unwrap();
    let foreign = foreign.to_string();
    for bad in [
        vec![("target", "rule"), ("rule", "all"), ("minutes", "45")],
        vec![("target", "budget"), ("minutes", "120")],
        vec![("target", "everything"), ("minutes", "30")],
        vec![
            ("target", "rule"),
            ("rule", foreign.as_str()),
            ("minutes", "30"),
        ],
    ] {
        let res = app
            .request_form(
                Method::POST,
                &format!("/devices/{id}/lifts"),
                Some(&cookie),
                &bad,
            )
            .await;
        assert_eq!(res.status, StatusCode::BAD_REQUEST, "{bad:?}");
    }
    let res = app
        .request_form(
            Method::POST,
            "/devices/9999/lifts",
            Some(&cookie),
            &[("target", "rule"), ("minutes", "30")],
        )
        .await;
    assert_eq!(res.status, StatusCode::NOT_FOUND);
    let lifts: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM time_lifts")
        .fetch_one(&app.db)
        .await
        .unwrap();
    assert_eq!(lifts, 0);
}

#[tokio::test]
async fn time_state_is_stored_and_shown() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("phone").await;
    // An older launcher: no time_rules_v1 -> warning.
    app.request(
        Method::POST,
        "/api/devices/status",
        Some(&token),
        Some(json!({"lock_reason": "NONE", "kiosk_engaged": true, "capabilities": ["call_policy_v1"]})),
    )
    .await;
    let page = app.get_page(&format!("/devices/{id}"), &cookie).await;
    assert!(page.text().contains("doesn't know time rules yet"));

    let res = app
        .request(
            Method::POST,
            "/api/devices/status",
            Some(&token),
            Some(json!({
                "lock_reason": "SCHOOL",
                "kiosk_engaged": true,
                "capabilities": ["call_policy_v1", "time_rules_v1"],
                "time_state": {
                    "day": "2026-10-05", "used_minutes": 42, "budget_minutes": 90,
                    "extra_minutes": 30, "active_rule_id": 1, "active_rule_name": "Skole",
                    "calls_blocked": true, "lock_reason": "SCHOOL", "lifts_active": []
                }
            })),
        )
        .await;
    assert_eq!(res.status, StatusCode::NO_CONTENT);
    let stored: Option<String> = sqlx::query_scalar(
        "SELECT time_state_json FROM device_status WHERE device_id = ? ORDER BY id DESC LIMIT 1",
    )
    .bind(id)
    .fetch_one(&app.db)
    .await
    .unwrap();
    assert!(stored.unwrap().contains("\"used_minutes\":42"));
    let page = app.get_page(&format!("/devices/{id}"), &cookie).await;
    let text = page.text();
    assert!(text.contains("Skole is active - calls blocked"), "{text}");
    assert!(text.contains("Screen time 2026-10-05: 42 of 90 min (incl. 30 extra)"));
    assert!(!text.contains("doesn't know time rules yet"));

    // Not an object, or too big: not stored.
    for bad in [json!("SCHOOL"), json!({"x": "y".repeat(5000)})] {
        app.request(
            Method::POST,
            "/api/devices/status",
            Some(&token),
            Some(json!({"lock_reason": "NONE", "kiosk_engaged": true, "time_state": bad})),
        )
        .await;
        let stored: Option<String> = sqlx::query_scalar(
            "SELECT time_state_json FROM device_status WHERE device_id = ? ORDER BY id DESC LIMIT 1",
        )
        .bind(id)
        .fetch_one(&app.db)
        .await
        .unwrap();
        assert!(stored.is_none());
    }
}

#[tokio::test]
async fn update_location_now_queues_locate() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("phone").await;
    let mut nudges = app.state.command_notify.subscribe();
    let res = app
        .request_form(
            Method::POST,
            &format!("/devices/{id}/command/locate"),
            Some(&cookie),
            &[],
        )
        .await;
    assert_eq!(
        res.location(),
        Some(format!("/devices/locate?device={id}&requested=1").as_str())
    );
    assert_eq!(nudges.try_recv().ok(), Some(id));
    let p = policy(&app, &token).await;
    assert_eq!(p["pending_command"]["command"], "locate");

    let page = app
        .get_page(&format!("/devices/locate?device={id}&requested=1"), &cookie)
        .await;
    let text = page.text();
    assert!(text.contains("Waiting for a fresh fix"));
    assert!(text.contains("Update location now"));
    assert!(!text.contains("couple of minutes"));

    app.request(
        Method::POST,
        "/api/devices/status",
        Some(&token),
        Some(json!({
            "lock_reason": "NONE", "kiosk_engaged": true,
            "location": {"latitude": 59.9, "longitude": 10.7, "accuracy_meters": 12.4,
                         "captured_at": chrono::Utc::now().to_rfc3339()}
        })),
    )
    .await;
    let page = app
        .get_page(&format!("/devices/locate?device={id}"), &cookie)
        .await;
    assert!(page.text().contains("s ago (±12 m)"), "{}", page.text());
}

#[tokio::test]
async fn location_policy_form_round_trip() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("phone").await;
    let mut nudges = app.state.command_notify.subscribe();
    let res = app
        .request_form(
            Method::POST,
            &format!("/devices/{id}/location-policy"),
            Some(&cookie),
            &[("mode", "interval"), ("interval_minutes", "60")],
        )
        .await;
    assert!(res.status.is_redirection(), "{}", res.text());
    assert_eq!(nudges.try_recv().ok(), Some(id));
    assert_eq!(
        policy(&app, &token).await["location_policy"],
        json!({"mode": "interval", "interval_minutes": 60})
    );
    // Mode only keeps the interval.
    app.request_form(
        Method::POST,
        &format!("/devices/{id}/location-policy"),
        Some(&cookie),
        &[("mode", "off")],
    )
    .await;
    assert_eq!(
        policy(&app, &token).await["location_policy"],
        json!({"mode": "off", "interval_minutes": 60})
    );
    for bad in [
        vec![("mode", "always")],
        vec![("mode", "interval"), ("interval_minutes", "7")],
    ] {
        let res = app
            .request_form(
                Method::POST,
                &format!("/devices/{id}/location-policy"),
                Some(&cookie),
                &bad,
            )
            .await;
        assert_eq!(res.status, StatusCode::BAD_REQUEST);
    }
    let res = app
        .request_form(
            Method::POST,
            "/devices/9999/location-policy",
            Some(&cookie),
            &[("mode", "off")],
        )
        .await;
    assert_eq!(res.status, StatusCode::NOT_FOUND);
    let page = app
        .get_page(&format!("/devices/locate?device={id}"), &cookie)
        .await;
    assert!(page.text().contains("Location is off for this phone"));
}

#[tokio::test]
async fn legacy_schedules_become_rules_once() {
    // Every migration before 0025, the old schedule written the old way, then the rest.
    let dir = tempfile::tempdir().unwrap();
    let path = dir.path().join("old.db");
    let options = sqlx::sqlite::SqliteConnectOptions::new()
        .filename(&path)
        .create_if_missing(true)
        .foreign_keys(true);
    let db = sqlx::SqlitePool::connect_with(options).await.unwrap();
    let full = sqlx::migrate!("./migrations");
    let mut old = sqlx::migrate!("./migrations");
    old.migrations = full
        .migrations
        .iter()
        .filter(|m| m.version < 25)
        .cloned()
        .collect::<Vec<_>>()
        .into();
    old.run(&db).await.unwrap();
    sqlx::query(
        "UPDATE global_schedule SET weekday_start_minutes = 420, weekday_end_minutes = 1200, \
         weekend_start_minutes = 480, weekend_end_minutes = 1260, \
         bedtime_start_minutes = 1260, bedtime_end_minutes = 420",
    )
    .execute(&db)
    .await
    .unwrap();
    let custom: i64 = sqlx::query_scalar("INSERT INTO devices (name) VALUES ('a') RETURNING id")
        .fetch_one(&db)
        .await
        .unwrap();
    sqlx::query(
        "INSERT INTO device_policy (device_id, kiosk_desired, custom_schedule_enabled, \
         bedtime_start_minutes, bedtime_end_minutes) VALUES (?, 1, 1, 1200, 360)",
    )
    .bind(custom)
    .execute(&db)
    .await
    .unwrap();
    db.close().await;

    // connect_db runs the remaining migrations and the conversion - twice, like two restarts.
    let url = format!("sqlite://{}", path.display());
    let db = crate::connect_db(&url).await;
    db.close().await;
    let db = crate::connect_db(&url).await;

    let rows: Vec<(Option<i64>, String, String, bool, String)> = sqlx::query_as(
        "SELECT device_id, name, kind, calls_allowed, days_json FROM time_rules \
         ORDER BY device_id IS NOT NULL, sort_order",
    )
    .fetch_all(&db)
    .await
    .unwrap();
    assert_eq!(rows.len(), 3, "{rows:?}");
    assert_eq!(rows[0].0, None);
    assert_eq!(rows[0].2, "bedtime");
    assert!(rows[0].3);
    assert_eq!(rows[1].1, "Outside allowed hours");
    assert_eq!(rows[1].2, "custom");
    // Friday: locked from 20:00 until Saturday 08:00.
    let days: serde_json::Value = serde_json::from_str(&rows[1].4).unwrap();
    assert_eq!(days[4], json!({"start": 1200, "end": 480}));
    assert_eq!(rows[2].0, Some(custom));
    assert_eq!(rows[2].1, "Bedtime");
    let migrated: Vec<i64> = sqlx::query_scalar(
        "SELECT rules_migrated FROM global_schedule UNION ALL SELECT rules_migrated FROM device_policy",
    )
    .fetch_all(&db)
    .await
    .unwrap();
    assert_eq!(migrated, vec![1, 1]);

    // A device created afterwards has nothing to convert.
    let fresh: i64 = sqlx::query_scalar("INSERT INTO devices (name) VALUES ('b') RETURNING id")
        .fetch_one(&db)
        .await
        .unwrap();
    sqlx::query("INSERT INTO device_policy (device_id, kiosk_desired) VALUES (?, 1)")
        .bind(fresh)
        .execute(&db)
        .await
        .unwrap();
    let flag: i64 =
        sqlx::query_scalar("SELECT rules_migrated FROM device_policy WHERE device_id = ?")
            .bind(fresh)
            .fetch_one(&db)
            .await
            .unwrap();
    assert_eq!(flag, 1);
}

#[tokio::test]
async fn legacy_fields_are_still_sent_for_older_launchers() {
    let app = TestApp::new().await;
    let (_, token) = app.enrolled_device("phone").await;
    sqlx::query(
        "UPDATE global_schedule SET bedtime_start_minutes = 1260, bedtime_end_minutes = 420",
    )
    .execute(&app.db)
    .await
    .unwrap();
    let p = policy(&app, &token).await;
    assert_eq!(p["bedtime_start_minutes"], 1260);
    assert_eq!(p["bedtime_end_minutes"], 420);
}
