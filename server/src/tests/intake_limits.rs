//! Security review M7 leftovers: a status report is stored within limits, and the security log
//! pages back past a flood.

use super::*;

#[tokio::test]
async fn a_status_report_is_stored_within_limits() {
    let app = TestApp::new().await;
    let (id, token) = app.enrolled_device("phone").await;
    let mut installed: Vec<serde_json::Value> = vec![
        serde_json::json!({ "package_name": "org.example.first", "label": "L".repeat(500),
                            "installer": "not a package!" }),
        serde_json::json!({ "package_name": "org.example.first", "label": "again" }),
        serde_json::json!({ "package_name": "", "label": "empty" }),
        serde_json::json!({ "package_name": "<script>alert(1)</script>", "label": "x" }),
        serde_json::json!({ "package_name": "a".repeat(300), "label": "long" }),
        serde_json::json!({ "package_name": "org.example.second", "label": "Second",
                            "installer": "me.vibb.launcher" }),
    ];
    installed.extend((0..2000).map(
        |i| serde_json::json!({ "package_name": format!("org.example.app{i}"), "label": "A" }),
    ));
    let res = app
        .request(
            Method::POST,
            "/api/devices/status",
            Some(&token),
            Some(serde_json::json!({
                "lock_reason": "R".repeat(1000),
                "kiosk_engaged": true,
                "app_version": "9".repeat(1000),
                "installed_apps": installed,
            })),
        )
        .await;
    assert!(res.status.is_success(), "{}", res.text());

    let (apps_json, lock_reason, app_version): (String, String, String) = sqlx::query_as(
        "SELECT installed_apps_json, lock_reason, app_version FROM device_status \
         WHERE device_id = ? ORDER BY id DESC LIMIT 1",
    )
    .bind(id)
    .fetch_one(&app.db)
    .await
    .unwrap();
    let apps: Vec<crate::models::InstalledApp> = serde_json::from_str(&apps_json).unwrap();
    assert_eq!(apps.len(), crate::handlers::device_api::MAX_INSTALLED_APPS);
    assert_eq!(apps[0].package_name, "org.example.first");
    assert_eq!(apps[0].label.chars().count(), 100);
    assert_eq!(apps[0].installer, None);
    assert_eq!(apps[1].package_name, "org.example.second");
    assert_eq!(apps[1].installer.as_deref(), Some("me.vibb.launcher"));
    assert!(
        apps.iter()
            .all(|app| crate::handlers::device_api::is_package_name(&app.package_name))
    );
    assert_eq!(lock_reason.chars().count(), 32);
    assert_eq!(app_version.chars().count(), 64);
}

/// Launcher round 7 §2 (the battery state) was removed on 2026-10-20, but launcher 0.32.0 still sends
/// `installed_apps[].battery_exempt`: the report is accepted, the field is dropped (never stored), and the
/// Apps page says nothing about the background.
/// Security review L3: a blocked domain is stored only as a hostname, its category short and its
/// time near the server's.
#[tokio::test]
async fn dns_events_are_stored_checked() {
    let app = TestApp::new().await;
    let (id, token) = app.enrolled_device("phone").await;
    sqlx::query("UPDATE device_policy SET dns_log_enabled = 1 WHERE device_id = ?")
        .bind(id)
        .execute(&app.db)
        .await
        .unwrap();
    let event = |domain: &str, category: &str, at: &str| serde_json::json!({ "domain": domain, "category": category, "blocked_at": at });
    let now = chrono::Utc::now();
    let res = app
        .request(
            Method::POST,
            "/api/devices/dns-events",
            Some(&token),
            Some(serde_json::json!([
                event("Ads.Example.COM.", "Ads & tracking", &now.to_rfc3339()),
                event("<b>bad</b>.example", "Ads", &now.to_rfc3339()),
                event(&"a".repeat(64), "Ads", &now.to_rfc3339()),
                event(
                    &format!("{}.example", "a.".repeat(130)),
                    "Ads",
                    &now.to_rfc3339()
                ),
                event("x..example", "Ads", &now.to_rfc3339()),
                event(
                    "far.example",
                    &format!("\u{7}{}", "C".repeat(100)),
                    "2001-01-01T00:00:00Z"
                ),
                event("garbled.example", "Custom", "yesterday"),
            ])),
        )
        .await;
    assert_eq!(res.status, StatusCode::NO_CONTENT);
    let rows: Vec<(String, String, String)> = sqlx::query_as(
        "SELECT domain, category, blocked_at FROM device_dns_events WHERE device_id = ? ORDER BY id",
    )
    .bind(id)
    .fetch_all(&app.db)
    .await
    .unwrap();
    let domains: Vec<&str> = rows.iter().map(|r| r.0.as_str()).collect();
    assert_eq!(
        domains,
        ["ads.example.com", "far.example", "garbled.example"]
    );
    assert_eq!(rows[0].1, "Ads & tracking");
    assert_eq!(rows[1].1, "C".repeat(64));
    let at = |text: &str| chrono::DateTime::parse_from_rfc3339(text).unwrap();
    let day = chrono::Duration::days(1);
    assert!(
        at(&rows[1].2) >= now - day - chrono::Duration::seconds(5),
        "{rows:?}"
    );
    assert!(
        at(&rows[2].2) >= now - chrono::Duration::seconds(5),
        "{rows:?}"
    );
    assert!(
        rows.iter().all(|r| r.2.ends_with('Z') && r.2.len() == 24),
        "{rows:?}"
    );
}

/// Security review L10: `call_state` and `time_state` are stored through their known keys - an
/// unknown key dropped, a value of the wrong shape dropped, `last_error` clipped to 100.
#[tokio::test]
async fn call_and_time_state_keep_their_known_keys() {
    let app = TestApp::new().await;
    let (id, token) = app.enrolled_device("phone").await;
    let res = app
        .request(
            Method::POST,
            "/api/devices/status",
            Some(&token),
            Some(serde_json::json!({
                "lock_reason": "NONE",
                "kiosk_engaged": true,
                "call_state": {
                    "state": "managed", "dialer_role_held": true, "last_error": "E".repeat(500),
                    "default_dialer": "d".repeat(300), "sms_restricted": "yes",
                    "last_emergency_call_at": null, "html": "<script>x</script>"
                },
                "time_state": {
                    "day": "2026-10-26", "used_minutes": 3, "lifts_active": [1, 2],
                    "active_rule_name": "Skole", "extra": {"nested": true}, "budget_minutes": 1.5
                }
            })),
        )
        .await;
    assert_eq!(res.status, StatusCode::NO_CONTENT);
    let (call, time): (String, String) = sqlx::query_as(
        "SELECT call_state_json, time_state_json FROM device_status WHERE device_id = ?",
    )
    .bind(id)
    .fetch_one(&app.db)
    .await
    .unwrap();
    let call: serde_json::Value = serde_json::from_str(&call).unwrap();
    assert_eq!(
        call,
        serde_json::json!({
            "state": "managed", "dialer_role_held": true, "last_error": "E".repeat(100),
            "last_emergency_call_at": null
        })
    );
    let time: serde_json::Value = serde_json::from_str(&time).unwrap();
    assert_eq!(
        time,
        serde_json::json!({
            "day": "2026-10-26", "used_minutes": 3, "lifts_active": [1, 2],
            "active_rule_name": "Skole"
        })
    );
}

/// Security review L4: one token's storage has a daily budget - past 500 reports a day each new one
/// replaces the last (the newest stays current), past the day's fixes and blocked domains new ones
/// aren't stored, and at most 50 crash hashes are kept, the oldest reported going first.
#[tokio::test]
async fn a_phones_storage_has_a_budget() {
    let app = TestApp::new().await;
    let (id, token) = app.enrolled_device("phone").await;
    let count = |sql: &'static str| {
        let db = app.db.clone();
        async move {
            sqlx::query_scalar::<_, i64>(sql)
                .bind(id)
                .fetch_one(&db)
                .await
                .unwrap()
        }
    };
    let fill = |sql: &'static str, rows: i64| {
        let db = app.db.clone();
        async move {
            sqlx::query(sql)
                .bind(rows)
                .bind(id)
                .execute(&db)
                .await
                .unwrap();
        }
    };
    fill(
        "WITH RECURSIVE n(i) AS (SELECT 1 UNION ALL SELECT i + 1 FROM n WHERE i < ?1) \
         INSERT INTO device_status (device_id, lock_reason, kiosk_engaged, reported_at) \
         SELECT ?2, 'OLD', 1, datetime('now', '-1 hour') FROM n",
        crate::retention::STATUS_ROWS_A_DAY,
    )
    .await;
    fill(
        "WITH RECURSIVE n(i) AS (SELECT 1 UNION ALL SELECT i + 1 FROM n WHERE i < ?1) \
         INSERT INTO device_locations (device_id, latitude, longitude, captured_at) \
         SELECT ?2, 59.9, 10.7, strftime('%Y-%m-%dT%H:%M:%fZ', 'now', '-' || i || ' seconds') FROM n",
        3_000,
    )
    .await;
    let location = serde_json::json!({ "latitude": 60.1, "longitude": 11.1, "accuracy_meters": 5.0,
        "captured_at": chrono::Utc::now().to_rfc3339() });
    for _ in 0..2 {
        let res = app
            .request(
                Method::POST,
                "/api/devices/status",
                Some(&token),
                Some(
                    serde_json::json!({ "lock_reason": "NONE", "kiosk_engaged": true,
                                         "location": location }),
                ),
            )
            .await;
        assert_eq!(res.status, StatusCode::NO_CONTENT);
    }
    assert_eq!(
        count("SELECT COUNT(*) FROM device_status WHERE device_id = ?").await,
        crate::retention::STATUS_ROWS_A_DAY
    );
    assert_eq!(
        count("SELECT COUNT(*) FROM device_status WHERE device_id = ? AND lock_reason = 'NONE'")
            .await,
        1,
        "the newest report is the latest"
    );
    assert_eq!(
        count("SELECT COUNT(*) FROM device_locations WHERE device_id = ? AND latitude > 60").await,
        0,
        "no fix past the day's budget"
    );
    // Never the newest report that said it enforces calls (QA L-c): it stays when a report
    // without the capability replaces the reports after it.
    for caps in [vec!["call_policy_v1"], vec![], vec![]] {
        let res = app
            .request(
                Method::POST,
                "/api/devices/status",
                Some(&token),
                Some(serde_json::json!({ "lock_reason": "CAPS", "kiosk_engaged": true,
                                         "capabilities": caps })),
            )
            .await;
        assert_eq!(res.status, StatusCode::NO_CONTENT);
    }
    assert_eq!(
        count(
            "SELECT COUNT(*) FROM device_status WHERE device_id = ? \
             AND capabilities_json LIKE '%call_policy_v1%'"
        )
        .await,
        1
    );

    sqlx::query("UPDATE device_policy SET dns_log_enabled = 1 WHERE device_id = ?")
        .bind(id)
        .execute(&app.db)
        .await
        .unwrap();
    fill(
        "WITH RECURSIVE n(i) AS (SELECT 1 UNION ALL SELECT i + 1 FROM n WHERE i < ?1) \
         INSERT INTO device_dns_events (device_id, domain, category, blocked_at) \
         SELECT ?2, 'ads.example', 'Ads', '' FROM n",
        9_999,
    )
    .await;
    let events: Vec<serde_json::Value> = (0..5)
        .map(
            |_| serde_json::json!({ "domain": "new.example", "category": "Ads", "blocked_at": "" }),
        )
        .collect();
    app.request(
        Method::POST,
        "/api/devices/dns-events",
        Some(&token),
        Some(events.into()),
    )
    .await;
    assert_eq!(
        count(
            "SELECT COUNT(*) FROM device_dns_events WHERE device_id = ? AND domain = 'new.example'"
        )
        .await,
        1
    );

    let trace = "java.lang.IllegalStateException\n    at a.B.c(B.kt:1)\n";
    for batch in 0..6 {
        let crashes: Vec<serde_json::Value> = (0..10)
            .map(|n| {
                serde_json::json!({ "hash": format!("{:016x}", batch * 10 + n), "trace": trace,
                    "count": 1, "first_at_ms": 1_790_000_000_000_i64,
                    "last_at_ms": 1_790_000_000_000_i64, "app_version_code": 1 })
            })
            .collect();
        let res = app
            .request(
                Method::POST,
                "/api/devices/crashes",
                Some(&token),
                Some(serde_json::json!({ "crashes": crashes })),
            )
            .await;
        assert_eq!(res.status, StatusCode::NO_CONTENT);
    }
    assert_eq!(
        count("SELECT COUNT(*) FROM device_crashes WHERE device_id = ?").await,
        50
    );
    assert_eq!(
        count(
            "SELECT COUNT(*) FROM device_crashes WHERE device_id = ? AND hash = '0000000000000000'"
        )
        .await,
        0,
        "the oldest went"
    );
}

#[tokio::test]
async fn an_older_launchers_battery_exempt_is_accepted_and_dropped() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("phone").await;
    let res = app
        .request(
            Method::POST,
            "/api/devices/status",
            Some(&token),
            Some(serde_json::json!({
                "lock_reason": "NONE",
                "kiosk_engaged": true,
                "installed_apps": [
                    { "package_name": "org.fossify.clock", "label": "Clock", "preinstalled": false,
                      "installer": "me.vibb.launcher", "battery_exempt": false },
                    { "package_name": "io.element.android.x", "label": "Element X", "preinstalled": false,
                      "installer": "me.vibb.launcher", "battery_exempt": true },
                    { "package_name": "com.android.chrome", "label": "Chrome", "preinstalled": true },
                ],
            })),
        )
        .await;
    assert!(res.status.is_success(), "{}", res.text());
    let apps_json: String = sqlx::query_scalar(
        "SELECT installed_apps_json FROM device_status WHERE device_id = ? ORDER BY id DESC LIMIT 1",
    )
    .bind(id)
    .fetch_one(&app.db)
    .await
    .unwrap();
    let apps: Vec<crate::models::InstalledApp> = serde_json::from_str(&apps_json).unwrap();
    let packages: Vec<&str> = apps.iter().map(|a| a.package_name.as_str()).collect();
    assert_eq!(
        packages,
        [
            "org.fossify.clock",
            "io.element.android.x",
            "com.android.chrome"
        ]
    );
    assert!(!apps_json.contains("battery_exempt"), "{apps_json}");

    let html = app
        .get_page(&device_page(id, Section::Apps), &cookie)
        .await
        .text();
    assert!(html.contains("Clock"), "{html}");
    assert!(!html.contains("Background:"), "{html}");
}

#[tokio::test]
async fn the_security_log_pages_back_past_a_flood() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    sqlx::query("DELETE FROM security_events")
        .execute(&app.db)
        .await
        .unwrap();
    for i in 0..250 {
        crate::security::record_security_event(
            &app.db,
            "probe",
            None,
            None,
            Some(&format!("event-{i:03}.")),
        )
        .await;
    }
    let first = app.get_page("/security", &cookie).await.text();
    assert!(first.contains("event-249.") && first.contains("event-050."));
    assert!(!first.contains("event-049."));
    let older = first
        .split("/security?before=")
        .nth(1)
        .and_then(|rest| rest.split(['"', '#']).next())
        .expect("a link to older events")
        .to_string();
    let second = app
        .get_page(&format!("/security?before={older}"), &cookie)
        .await
        .text();
    assert!(second.contains("event-049.") && second.contains("event-000."));
    assert!(!second.contains("event-050."));
    assert!(second.contains("Newest events"));
    assert!(
        !second.contains("/security?before="),
        "the oldest page has no older link"
    );
}
