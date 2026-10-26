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
    let event = |domain: &str, category: &str, at: &str| {
        serde_json::json!({ "domain": domain, "category": category, "blocked_at": at })
    };
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
                event(&format!("{}.example", "a.".repeat(130)), "Ads", &now.to_rfc3339()),
                event("x..example", "Ads", &now.to_rfc3339()),
                event("far.example", &format!("\u{7}{}", "C".repeat(100)), "2001-01-01T00:00:00Z"),
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
    assert_eq!(domains, ["ads.example.com", "far.example", "garbled.example"]);
    assert_eq!(rows[0].1, "Ads & tracking");
    assert_eq!(rows[1].1, "C".repeat(64));
    let at = |text: &str| chrono::DateTime::parse_from_rfc3339(text).unwrap();
    let day = chrono::Duration::days(1);
    assert!(at(&rows[1].2) >= now - day - chrono::Duration::seconds(5), "{rows:?}");
    assert!(at(&rows[2].2) >= now - chrono::Duration::seconds(5), "{rows:?}");
    assert!(rows.iter().all(|r| r.2.ends_with('Z') && r.2.len() == 24), "{rows:?}");
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
