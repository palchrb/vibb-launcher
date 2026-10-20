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

/// Round 7 §2.1.2: the launcher's body with `installed_apps[].battery_exempt`
/// (`InstalledAppReportTest` encodes exactly this; byte-identical in its test resources).
const INSTALLED_APPS_BATTERY: &str = include_str!("../../testdata/installed_apps_battery.json");

#[test]
fn the_battery_contract_matches_the_launchers_copy() {
    let copy_path = std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("../launcher/app/src/test/resources/installed_apps_battery.json");
    let copy = std::fs::read_to_string(&copy_path)
        .unwrap_or_else(|e| panic!("{}: {e}", copy_path.display()));
    assert_eq!(
        copy,
        INSTALLED_APPS_BATTERY,
        "{} differs",
        copy_path.display()
    );
}

/// `cap_installed_apps` keeps the battery state of a catalog app the launcher installed (and leaves it out for
/// the rest), and the Apps page says it quietly under the app's status - no button.
#[tokio::test]
async fn the_battery_state_of_a_catalog_app_is_kept() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("phone").await;
    let body: serde_json::Value = serde_json::from_str(INSTALLED_APPS_BATTERY).unwrap();
    let res = app
        .request(
            Method::POST,
            "/api/devices/status",
            Some(&token),
            Some(body),
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
    let exempt: Vec<(&str, Option<bool>)> = apps
        .iter()
        .map(|a| (a.package_name.as_str(), a.battery_exempt))
        .collect();
    assert_eq!(
        exempt,
        [
            ("org.fossify.clock", Some(false)),
            ("io.element.android.x", Some(true)),
            ("org.example.game", None),
            ("com.android.chrome", None),
        ]
    );
    assert!(
        !apps_json.contains("\"battery_exempt\":null"),
        "{apps_json}"
    );

    let html = app
        .get_page(&device_page(id, Section::Apps), &cookie)
        .await
        .text();
    let lines: Vec<&str> = html
        .match_indices("<span class=\"row-sub\" data-background>")
        .map(|(at, open)| {
            let rest = &html[at + open.len()..];
            &rest[..rest.find("</span>").unwrap()]
        })
        .collect();
    assert_eq!(
        lines,
        [
            "Background: optimized (Android&#39;s default) - change it during a pause (Settings › \
             Apps › Clock › Battery)",
            "Background: unrestricted",
        ],
        "{html}"
    );
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
