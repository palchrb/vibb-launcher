//! Cleanup round (2026-10-06): the launcher's package rename, the removed monitoring features,
//! retention/pruning and crash reports.

use sqlx::SqlitePool;

/// A database migrated up to (not including) `version`; [`finish`] runs the rest.
pub(super) async fn migrated_before(version: i64) -> (SqlitePool, tempfile::TempDir) {
    let dir = tempfile::tempdir().unwrap();
    let options = sqlx::sqlite::SqliteConnectOptions::new()
        .filename(dir.path().join("old.db"))
        .create_if_missing(true)
        .foreign_keys(true);
    let db = sqlx::SqlitePool::connect_with(options).await.unwrap();
    let full = sqlx::migrate!("./migrations");
    let mut old = sqlx::migrate!("./migrations");
    old.migrations = full
        .migrations
        .iter()
        .filter(|m| m.version < version)
        .cloned()
        .collect::<Vec<_>>()
        .into();
    old.run(&db).await.unwrap();
    (db, dir)
}

pub(super) async fn finish(db: &SqlitePool) {
    sqlx::migrate!("./migrations").run(db).await.unwrap();
}

#[tokio::test]
async fn package_rename_migration_renames_only_the_launcher_row() {
    let (db, _dir) = migrated_before(37).await;
    for (name, package, is_launcher) in [
        ("Launcher", "com.kidslauncher.mdm", 1),
        ("Launcher debug", "com.kidslauncher.mdm.debug", 1),
        ("Not the launcher", "com.kidslauncher.mdm", 0),
        ("Other", "org.example.app", 1),
    ] {
        sqlx::query(
            "INSERT INTO tracked_apps (name, package_name, github_repo, is_launcher) \
             VALUES (?, ?, 'palchrb/vibb-launcher', ?)",
        )
        .bind(name)
        .bind(package)
        .bind(is_launcher)
        .execute(&db)
        .await
        .unwrap();
    }
    finish(&db).await;
    let rows: Vec<(String, String)> =
        sqlx::query_as("SELECT name, package_name FROM tracked_apps ORDER BY id")
            .fetch_all(&db)
            .await
            .unwrap();
    let rows: Vec<(&str, &str)> = rows.iter().map(|(a, b)| (a.as_str(), b.as_str())).collect();
    assert_eq!(
        rows,
        vec![
            ("Launcher", "me.vibb.launcher"),
            ("Launcher debug", "me.vibb.launcher.debug"),
            ("Not the launcher", "com.kidslauncher.mdm"),
            ("Other", "org.example.app"),
        ]
    );
}

#[tokio::test]
async fn monitoring_migration_drops_the_journal_and_browser_history() {
    let (db, _dir) = migrated_before(38).await;
    let device: i64 = sqlx::query_scalar("INSERT INTO devices (name) VALUES ('kid') RETURNING id")
        .fetch_one(&db)
        .await
        .unwrap();
    sqlx::query(
        "INSERT INTO device_journal_entries (device_id, remote_id, thread_id, recipient_id, \
         direction, entry_type, occurred_at, body, device_created_at) \
         VALUES (?, 1, 1, 'r', 'in', 'MESSAGE', 0, 'secret message', 0)",
    )
    .bind(device)
    .execute(&db)
    .await
    .unwrap();
    sqlx::query(
        "INSERT INTO device_browser_history_entries (device_id, remote_id, url, visited_at, \
         device_created_at) VALUES (?, 1, 'https://example.org/secret', 0, 0)",
    )
    .bind(device)
    .execute(&db)
    .await
    .unwrap();
    finish(&db).await;
    let tables: Vec<String> = sqlx::query_scalar(
        "SELECT name FROM sqlite_master WHERE name LIKE '%journal%' OR name LIKE '%browser%'",
    )
    .fetch_all(&db)
    .await
    .unwrap();
    assert!(tables.is_empty(), "left over: {tables:?}");
}

#[tokio::test]
async fn journal_and_history_routes_are_gone() {
    use axum::http::{Method, StatusCode};
    let app = super::TestApp::new().await;
    let (id, token) = app.enrolled_device("kid").await;
    for uri in [
        "/api/devices/journal",
        "/api/devices/journal/media/1",
        "/api/devices/browser-history",
    ] {
        let res = app
            .request(Method::POST, uri, Some(&token), Some(serde_json::json!([])))
            .await;
        assert_eq!(res.status, StatusCode::NOT_FOUND, "{uri}");
    }
    let cookie = app.admin_cookie().await;
    for uri in [
        format!("/devices/{id}/journal"),
        format!("/devices/{id}/browser-history"),
    ] {
        // Unmatched: whatever the router's fallback answers, never the old viewer.
        let res = app.get_page(&uri, &cookie).await;
        assert!(!res.status.is_success(), "{uri}: {}", res.status);
    }
    let page = app.get_page(&format!("/devices/{id}"), &cookie).await;
    assert!(!page.text().contains("/journal"));
    assert!(!page.text().contains("browser-history"));
}

#[tokio::test]
async fn journal_media_is_deleted_once() {
    let dir = tempfile::tempdir().unwrap();
    let media = dir.path().join("journal_media");
    std::fs::create_dir_all(media.join("1")).unwrap();
    std::fs::write(media.join("1").join("5.jpg"), b"photo").unwrap();
    assert!(crate::retention::remove_journal_media(&media).await);
    assert!(!media.exists());
    // Nothing there any more: a no-op at every later start.
    assert!(!crate::retention::remove_journal_media(&media).await);
}

async fn count(db: &SqlitePool, sql: &str) -> i64 {
    sqlx::query_scalar(sql).fetch_one(db).await.unwrap()
}

#[tokio::test]
async fn retention_migration_deletes_the_old_dns_log_and_defaults() {
    let (db, _dir) = migrated_before(39).await;
    let device: i64 = sqlx::query_scalar("INSERT INTO devices (name) VALUES ('kid') RETURNING id")
        .fetch_one(&db)
        .await
        .unwrap();
    sqlx::query("INSERT INTO device_policy (device_id, kiosk_desired) VALUES (?, 1)")
        .bind(device)
        .execute(&db)
        .await
        .unwrap();
    sqlx::query(
        "INSERT INTO device_dns_events (device_id, domain, category, blocked_at) \
         VALUES (?, 'ads.example', 'Ads', '2026-10-01T10:00:00Z')",
    )
    .bind(device)
    .execute(&db)
    .await
    .unwrap();
    finish(&db).await;
    assert_eq!(
        count(&db, "SELECT COUNT(*) FROM device_dns_events").await,
        0
    );
    let (log, days): (bool, i64) = sqlx::query_as(
        "SELECT dns_log_enabled, location_retention_days FROM device_policy WHERE device_id = ?",
    )
    .bind(device)
    .fetch_one(&db)
    .await
    .unwrap();
    assert!(!log);
    assert_eq!(days, 7);
}

async fn policy(app: &super::TestApp, token: &str) -> serde_json::Value {
    app.request(
        axum::http::Method::GET,
        "/api/devices/policy",
        Some(token),
        None,
    )
    .await
    .json()
}

#[tokio::test]
async fn dns_log_is_off_by_default_and_opt_in_per_phone() {
    use axum::http::{Method, StatusCode};
    let app = super::TestApp::new().await;
    let (id, token) = app.enrolled_device("kid").await;
    assert_eq!(policy(&app, &token).await["dns_log_enabled"], false);
    let events = serde_json::json!([
        { "domain": "ads.example", "category": "Ads", "blocked_at": "2026-10-06T10:00:00Z" }
    ]);
    // Off: answered, never stored (an older launcher still reports).
    let res = app
        .request(
            Method::POST,
            "/api/devices/dns-events",
            Some(&token),
            Some(events.clone()),
        )
        .await;
    assert_eq!(res.status, StatusCode::NO_CONTENT);
    assert_eq!(
        count(&app.db, "SELECT COUNT(*) FROM device_dns_events").await,
        0
    );

    // Unauthenticated: nothing changes.
    let res = app
        .request_form(
            Method::POST,
            &format!("/dns/log/{id}"),
            None,
            &[("dns_log_enabled", "on")],
        )
        .await;
    // Not signed in: sent to the login page, nothing written.
    assert_ne!(
        res.location(),
        Some(format!("/dns/log?device={id}").as_str())
    );
    assert_eq!(policy(&app, &token).await["dns_log_enabled"], false);

    let cookie = app.admin_cookie().await;
    let res = app
        .request_form(
            Method::POST,
            &format!("/dns/log/{id}"),
            Some(&cookie),
            &[("dns_log_enabled", "on")],
        )
        .await;
    assert_eq!(
        res.location(),
        Some(format!("/dns/log?device={id}").as_str())
    );
    assert_eq!(policy(&app, &token).await["dns_log_enabled"], true);
    app.request(
        Method::POST,
        "/api/devices/dns-events",
        Some(&token),
        Some(events),
    )
    .await;
    assert_eq!(
        count(&app.db, "SELECT COUNT(*) FROM device_dns_events").await,
        1
    );
    let page = app
        .get_page(&format!("/dns/log?device={id}"), &cookie)
        .await;
    assert!(page.text().contains("ads.example"));
    assert!(page.text().contains("keeps it for 7 days"));

    // Off again: the phone's log is deleted at once.
    let res = app
        .request_form(Method::POST, &format!("/dns/log/{id}"), Some(&cookie), &[])
        .await;
    assert_eq!(
        res.location(),
        Some(format!("/dns/log?device={id}").as_str())
    );
    assert_eq!(
        count(&app.db, "SELECT COUNT(*) FROM device_dns_events").await,
        0
    );
    assert_eq!(policy(&app, &token).await["dns_log_enabled"], false);
    let res = app
        .request_form(
            Method::POST,
            "/dns/log/999",
            Some(&cookie),
            &[("dns_log_enabled", "on")],
        )
        .await;
    assert_eq!(res.status, StatusCode::NOT_FOUND);
}

/// Inserts a row with a `received_at`/`reported_at` `age` in the past (an SQLite modifier such
/// as "-8 days").
async fn dns_event(db: &SqlitePool, device: i64, age: &str) {
    sqlx::query(
        "INSERT INTO device_dns_events (device_id, domain, category, blocked_at, received_at) \
         VALUES (?, 'ads.example', 'Ads', '', datetime('now', ?))",
    )
    .bind(device)
    .bind(age)
    .execute(db)
    .await
    .unwrap();
}

async fn location(db: &SqlitePool, device: i64, captured_at: &str, age: &str) {
    sqlx::query(
        "INSERT INTO device_locations (device_id, latitude, longitude, captured_at, received_at) \
         VALUES (?, 59.9, 10.7, ?, datetime('now', ?))",
    )
    .bind(device)
    .bind(captured_at)
    .bind(age)
    .execute(db)
    .await
    .unwrap();
}

async fn status(db: &SqlitePool, device: i64, age: &str) {
    sqlx::query(
        "INSERT INTO device_status (device_id, time_state_json, reported_at) \
         VALUES (?, '{\"used_minutes\": 5}', datetime('now', ?))",
    )
    .bind(device)
    .bind(age)
    .execute(db)
    .await
    .unwrap();
}

#[tokio::test]
async fn pruning_keeps_only_what_retention_allows() {
    let app = super::TestApp::new().await;
    let db = &app.db;
    let (logged, _) = app.create_device("logged").await;
    let (quiet, _) = app.create_device("quiet").await;
    let (long, _) = app.create_device("long").await;
    sqlx::query("UPDATE device_policy SET dns_log_enabled = 1 WHERE device_id = ?")
        .bind(logged)
        .execute(db)
        .await
        .unwrap();
    sqlx::query("UPDATE device_policy SET location_retention_days = 30 WHERE device_id = ?")
        .bind(long)
        .execute(db)
        .await
        .unwrap();

    // DNS log: 7 days while on; nothing at all for a phone with the log off.
    dns_event(db, logged, "-8 days").await;
    dns_event(db, logged, "-6 days").await;
    dns_event(db, quiet, "-1 hours").await;
    // Locations: the default 7 days, the newest fix always kept; 30 days for `long`.
    location(db, logged, "2026-09-01T10:00:00Z", "-20 days").await;
    location(db, logged, "2026-09-02T10:00:00Z", "-10 days").await;
    location(db, quiet, "2026-10-05T10:00:00Z", "-1 days").await;
    location(db, quiet, "2026-09-20T10:00:00Z", "-8 days").await;
    location(db, long, "2026-09-20T10:00:00Z", "-8 days").await;
    location(db, long, "2026-09-01T10:00:00Z", "-31 days").await;
    location(db, long, "2026-09-25T10:00:00Z", "-2 days").await;
    // Status history (screen time): 30 days, the newest report per phone always kept.
    status(db, logged, "-40 days").await;
    status(db, logged, "-35 days").await;
    status(db, quiet, "-40 days").await;
    status(db, quiet, "-1 days").await;

    let pruned = crate::retention::prune(db).await.unwrap();
    assert_eq!(
        pruned,
        crate::retention::Pruned {
            dns_events: 2,
            locations: 3,
            status_reports: 2,
            crashes: 0,
        }
    );
    assert_eq!(count(db, "SELECT COUNT(*) FROM device_dns_events").await, 1);
    let kept: Vec<(i64, String)> = sqlx::query_as(
        "SELECT device_id, captured_at FROM device_locations ORDER BY device_id, captured_at",
    )
    .fetch_all(db)
    .await
    .unwrap();
    assert_eq!(
        kept,
        vec![
            // `logged` has only old fixes: the newest stays ("last seen").
            (logged, "2026-09-02T10:00:00Z".to_string()),
            (quiet, "2026-10-05T10:00:00Z".to_string()),
            (long, "2026-09-20T10:00:00Z".to_string()),
            (long, "2026-09-25T10:00:00Z".to_string()),
        ]
    );
    let statuses: Vec<(i64, i64)> = sqlx::query_as(
        "SELECT device_id, COUNT(*) FROM device_status GROUP BY device_id ORDER BY device_id",
    )
    .fetch_all(db)
    .await
    .unwrap();
    assert_eq!(statuses, vec![(logged, 1), (quiet, 1)]);
    // A second pass finds nothing more.
    assert_eq!(
        crate::retention::prune(db).await.unwrap(),
        crate::retention::Pruned::default()
    );
}

#[tokio::test]
async fn location_retention_is_chosen_per_phone_and_applied_at_once() {
    use axum::http::{Method, StatusCode};
    let app = super::TestApp::new().await;
    let (id, _) = app.enrolled_device("kid").await;
    location(&app.db, id, "2026-10-01T10:00:00Z", "-3 days").await;
    location(&app.db, id, "2026-10-05T10:00:00Z", "-1 hours").await;
    let uri = format!("/devices/{id}/location-retention");

    let res = app
        .request_form(Method::POST, &uri, None, &[("days", "1")])
        .await;
    assert_ne!(
        res.location(),
        Some(format!("/devices/locate?device={id}").as_str())
    );
    assert_eq!(
        count(&app.db, "SELECT COUNT(*) FROM device_locations").await,
        2
    );

    let cookie = app.admin_cookie().await;
    let page = app
        .get_page(&format!("/devices/locate?device={id}"), &cookie)
        .await;
    assert!(
        page.text()
            .contains(r#"<option value="7" selected>7 days</option>"#)
    );
    for bad in ["5", "0", "", "x"] {
        let res = app
            .request_form(Method::POST, &uri, Some(&cookie), &[("days", bad)])
            .await;
        assert_eq!(res.status, StatusCode::BAD_REQUEST, "{bad:?}");
    }
    let res = app
        .request_form(
            Method::POST,
            "/devices/999/location-retention",
            Some(&cookie),
            &[("days", "1")],
        )
        .await;
    assert_eq!(res.status, StatusCode::NOT_FOUND);

    let res = app
        .request_form(Method::POST, &uri, Some(&cookie), &[("days", "1")])
        .await;
    assert_eq!(
        res.location(),
        Some(format!("/devices/locate?device={id}").as_str())
    );
    assert_eq!(
        count(&app.db, "SELECT COUNT(*) FROM device_locations").await,
        1
    );
    let page = app
        .get_page(&format!("/devices/locate?device={id}"), &cookie)
        .await;
    assert!(
        page.text()
            .contains(r#"<option value="1" selected>1 day</option>"#)
    );
    assert_eq!(
        count(
            &app.db,
            "SELECT COUNT(*) FROM security_events WHERE event_type = 'location_retention_changed'"
        )
        .await,
        1
    );
}

#[tokio::test]
async fn crash_reports_reach_the_device_page_without_free_text() {
    use axum::http::{Method, StatusCode};
    let app = super::TestApp::new().await;
    let (id, token) = app.enrolled_device("kid").await;
    let trace = "java.lang.IllegalStateException\n    at com.kidslauncher.mdm.calls.PhoneBookActivity.call(PhoneBookActivity.kt:10)\nMamma +4791234567\n";
    let batch = |count: i64, last: i64| {
        serde_json::json!({ "crashes": [{
            "hash": "0123456789abcdef", "trace": trace, "count": count,
            "first_at_ms": 1_790_000_000_000_i64, "last_at_ms": last, "app_version_code": 23_007_000
        }, {
            "hash": "not a hash", "trace": trace, "count": 1,
            "first_at_ms": 0, "last_at_ms": 0, "app_version_code": 1
        }]})
    };
    // Without a token: refused, nothing stored.
    let res = app
        .request(
            Method::POST,
            "/api/devices/crashes",
            None,
            Some(batch(1, 1)),
        )
        .await;
    assert_eq!(res.status, StatusCode::UNAUTHORIZED);
    let res = app
        .request(
            Method::POST,
            "/api/devices/crashes",
            Some(&token),
            Some(batch(2, 1_790_000_100_000)),
        )
        .await;
    assert_eq!(res.status, StatusCode::NO_CONTENT);
    let res = app
        .request(
            Method::POST,
            "/api/devices/crashes",
            Some(&token),
            Some(batch(1, 1_790_000_200_000)),
        )
        .await;
    assert_eq!(res.status, StatusCode::NO_CONTENT);
    let rows: Vec<(String, String, i64, i64)> =
        sqlx::query_as("SELECT hash, trace, count, last_at_ms FROM device_crashes")
            .fetch_all(&app.db)
            .await
            .unwrap();
    assert_eq!(
        rows,
        vec![(
            "0123456789abcdef".to_string(),
            "java.lang.IllegalStateException\n    at com.kidslauncher.mdm.calls.PhoneBookActivity.call(PhoneBookActivity.kt:10)\n".to_string(),
            3,
            1_790_000_200_000
        )]
    );
    let too_many = serde_json::json!({ "crashes": vec![batch(1, 1)["crashes"][0].clone(); 11] });
    let res = app
        .request(
            Method::POST,
            "/api/devices/crashes",
            Some(&token),
            Some(too_many),
        )
        .await;
    assert_eq!(res.status, StatusCode::BAD_REQUEST);

    let cookie = app.admin_cookie().await;
    let page = app
        .get_page(&format!("/devices/{id}"), &cookie)
        .await
        .text();
    assert!(page.contains("Launcher crashes"));
    assert!(page.contains("java.lang.IllegalStateException"));
    assert!(page.contains("3&times;") || page.contains("3×"));
    assert!(!page.contains("4791234567"));

    // Pruned 30 days after the phone last reported it.
    sqlx::query("UPDATE device_crashes SET reported_at = datetime('now', '-31 days')")
        .execute(&app.db)
        .await
        .unwrap();
    assert_eq!(crate::retention::prune(&app.db).await.unwrap().crashes, 1);
    let page = app
        .get_page(&format!("/devices/{id}"), &cookie)
        .await
        .text();
    assert!(page.contains("No crashes reported in the last 30 days."));
}

#[tokio::test]
async fn pruning_keeps_the_last_report_that_enforced_calls() {
    let app = super::TestApp::new().await;
    let db = &app.db;
    let (id, _) = app.create_device("kid").await;
    sqlx::query("UPDATE device_policy SET calls_managed = 1 WHERE device_id = ?")
        .bind(id)
        .execute(db)
        .await
        .unwrap();
    for (caps, age) in [
        (Some(r#"["call_policy_v1"]"#), "-50 days"),
        (Some(r#"["call_policy_v1"]"#), "-40 days"),
        (None, "-35 days"),
        (None, "-1 days"),
    ] {
        sqlx::query(
            "INSERT INTO device_status (device_id, capabilities_json, reported_at) \
             VALUES (?, ?, datetime('now', ?))",
        )
        .bind(id)
        .bind(caps)
        .bind(age)
        .execute(db)
        .await
        .unwrap();
    }
    assert_eq!(crate::retention::prune(db).await.unwrap().status_reports, 2);
    assert_eq!(
        count(
            db,
            "SELECT COUNT(*) FROM device_status WHERE capabilities_json LIKE '%call_policy_v1%'"
        )
        .await,
        1
    );
    // A launcher that stopped enforcing calls still reads as a downgrade, not "not yet".
    let cookie = app.admin_cookie().await;
    let page = app
        .get_page(&format!("/devices/{id}"), &cookie)
        .await
        .text();
    assert!(page.contains("stopped reporting that it enforces calls"));
}
