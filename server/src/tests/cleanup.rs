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

#[tokio::test]
async fn dns_log_is_off_by_default_and_opt_in_per_phone() {
    use axum::http::{Method, StatusCode};
    let app = super::TestApp::new().await;
    let (id, token) = app.enrolled_device("kid").await;
    assert_eq!(app.policy(&token).await["dns_log_enabled"], false);
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
    let filter = super::device_page(id, super::Section::Filter);
    assert_ne!(res.location(), Some(filter.as_str()));
    assert_eq!(app.policy(&token).await["dns_log_enabled"], false);

    let cookie = app.admin_cookie().await;
    let res = app
        .request_form(
            Method::POST,
            &format!("/dns/log/{id}"),
            Some(&cookie),
            &[("dns_log_enabled", "on")],
        )
        .await;
    // Back to its group on the phone's Web Filter page (design 31 §5.1.4).
    super::assert_redirect(&res, &filter, Some("log"));
    assert_eq!(app.policy(&token).await["dns_log_enabled"], true);
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
    let page = app.get_page(&filter, &cookie).await.text();
    assert!(page.contains("ads.example"));
    assert!(page.contains("Kept 7 days."));
    // The phone's time in local time, never the raw text it sent.
    assert!(!page.contains("2026-10-06T10:00:00Z"));

    // Off again: the phone's log is deleted at once.
    let res = app
        .request_form(Method::POST, &format!("/dns/log/{id}"), Some(&cookie), &[])
        .await;
    super::assert_redirect(&res, &filter, Some("log"));
    assert_eq!(
        count(&app.db, "SELECT COUNT(*) FROM device_dns_events").await,
        0
    );
    assert_eq!(app.policy(&token).await["dns_log_enabled"], false);
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
    // Locations: the default 7 days, the newest fix kept past it (the last known position - the
    // owner's decision on M1 of round S3); 30 days for `long`.
    location(db, logged, "2026-09-01T10:00:00Z", "-20 days").await;
    location(db, logged, "2026-09-02T10:00:00Z", "-10 days").await;
    location(db, quiet, "2026-10-05T10:00:00Z", "-1 days").await;
    location(db, quiet, "2026-09-20T10:00:00Z", "-8 days").await;
    location(db, long, "2026-09-20T10:00:00Z", "-8 days").await;
    location(db, long, "2026-09-01T10:00:00Z", "-31 days").await;
    location(db, long, "2026-09-25T10:00:00Z", "-2 days").await;
    // Status history (security review P2): 48 hours, and each phone's newest report from before
    // them (QA L2) - a silent phone keeps only its newest.
    status(db, logged, "-40 days").await;
    status(db, logged, "-35 days").await;
    status(db, quiet, "-40 days").await;
    status(db, quiet, "-49 hours").await;
    status(db, quiet, "-47 hours").await;
    status(db, quiet, "-1 hours").await;
    // Commands (design 48 §1.6): 30 days, but never one that may still reach the phone - an
    // undelivered wipe or lock waits however long it takes; an expired locate, a finished ring, a
    // cancelled lock and a delivered one never confirmed go.
    // (command, asked, delivered, answered, cancelled)
    for (command, age, delivered, answered, cancelled) in [
        ("wipe", "-31 days", false, false, false),
        ("lock", "-31 days", false, false, false),
        ("locate", "-31 days", false, false, false),
        ("ring", "-31 days", true, true, false),
        ("lock", "-31 days", false, false, true),
        // Delivered and never confirmed: long past its re-delivery window.
        ("lock", "-31 days", true, false, false),
        ("ring", "-29 days", true, true, false),
    ] {
        sqlx::query(
            "INSERT INTO device_commands (device_id, command, requested_at, delivered_at, \
             acknowledged_at, cancelled_at) VALUES (?1, ?2, datetime('now', ?3), \
             CASE WHEN ?4 THEN datetime('now', ?3) END, CASE WHEN ?5 THEN datetime('now', ?3) END, \
             CASE WHEN ?6 THEN datetime('now', ?3) END)",
        )
        .bind(logged)
        .bind(command)
        .bind(age)
        .bind(delivered)
        .bind(answered)
        .bind(cancelled)
        .execute(db)
        .await
        .unwrap();
    }

    let pruned = crate::retention::prune(db).await;
    assert_eq!(
        pruned,
        crate::retention::Pruned {
            dns_events: 2,
            locations: 3,
            status_reports: 2,
            crashes: 0,
            security_events: 0,
            pin_events: 0,
            parent_events: 0,
            push_rows: 0,
            commands: 4,
            lifts: 0,
            bans: 0,
            install_progress: 0,
            failed: vec![],
        }
    );
    let commands: Vec<(String, bool)> = sqlx::query_as(
        "SELECT command, requested_at < datetime('now', '-30 days') FROM device_commands ORDER BY id",
    )
    .fetch_all(db)
    .await
    .unwrap();
    assert_eq!(
        commands,
        [("wipe", true), ("lock", true), ("ring", false)].map(|(c, old)| (c.to_string(), old))
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
            // `logged` has only old fixes: the newest stays (Locate shows it with its age).
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
    assert_eq!(statuses, vec![(logged, 1), (quiet, 3)]);
    // A second pass finds nothing more.
    assert_eq!(
        crate::retention::prune(db).await,
        crate::retention::Pruned::default()
    );
}

/// One table that can't be pruned (here: gone) never keeps the pass from the others (security
/// review L9): it is named in `failed`, the rest are pruned.
#[tokio::test]
async fn a_failing_table_leaves_the_rest_of_the_pass() {
    let app = super::TestApp::new().await;
    let db = &app.db;
    let (logged, _) = app.enrolled_device("logged").await;
    sqlx::query("UPDATE device_policy SET dns_log_enabled = 1 WHERE device_id = ?")
        .bind(logged)
        .execute(db)
        .await
        .unwrap();
    location(db, logged, "2026-01-01T00:00:00.000Z", "-40 days").await;
    location(db, logged, "2026-01-02T00:00:00.000Z", "-40 days").await;
    status(db, logged, "-41 days").await;
    status(db, logged, "-40 days").await;
    status(db, logged, "-1 days").await;
    sqlx::query("DROP TABLE device_dns_events")
        .execute(db)
        .await
        .unwrap();
    let pruned = crate::retention::prune(db).await;
    assert_eq!(pruned.failed, ["device_dns_events"]);
    assert_eq!((pruned.locations, pruned.status_reports), (1, 1));
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
        Some(format!("/devices/locate?device={id}#location-settings").as_str())
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

    // About This Phone › Diagnostics (design 31 step 3; the device page's card until then).
    let cookie = app.admin_cookie().await;
    let about = format!("/devices/{id}/about");
    let page = app.get_page(&about, &cookie).await.text();
    assert!(page.contains("Crash reports"));
    assert!(page.contains("1 kind"));
    assert!(page.contains("java.lang.IllegalStateException"));
    assert!(page.contains("3&times;") || page.contains("3×"));
    assert!(!page.contains("4791234567"));

    // Pruned 30 days after the phone last reported it.
    sqlx::query("UPDATE device_crashes SET reported_at = datetime('now', '-31 days')")
        .execute(&app.db)
        .await
        .unwrap();
    assert_eq!(crate::retention::prune(&app.db).await.crashes, 1);
    let page = app.get_page(&about, &cookie).await.text();
    assert!(page.contains("None in 30 days"));
    assert!(!page.contains("java.lang.IllegalStateException"));
}

/// A crash trace is phone-supplied text, and `crashes::sanitize` lets `<` and `>` through in
/// identifiers (real traces have `<init>` and `<clinit>` frames): About This Phone must escape it,
/// the trace in its `<pre>` and the row's label alike (qa-31-s3-code L3).
#[tokio::test]
async fn crash_traces_are_escaped_on_about() {
    use axum::http::{Method, StatusCode};
    let app = super::TestApp::new().await;
    let (id, token) = app.enrolled_device("kid").await;
    let res = app
        .request(
            Method::POST,
            "/api/devices/crashes",
            Some(&token),
            Some(serde_json::json!({ "crashes": [{
                "hash": "fedcba9876543210",
                "trace": "<b>Boom\n    at com.x.Foo.<init>(Foo.kt:3)\n",
                "count": 1, "first_at_ms": 1_790_000_000_000_i64,
                "last_at_ms": 1_790_000_000_000_i64, "app_version_code": 23_007_000
            }]})),
        )
        .await;
    assert_eq!(res.status, StatusCode::NO_CONTENT);
    let stored: String = sqlx::query_scalar("SELECT trace FROM device_crashes")
        .fetch_one(&app.db)
        .await
        .unwrap();
    assert!(stored.contains("<init>"), "the frame is kept: {stored}");
    let cookie = app.admin_cookie().await;
    let page = app
        .get_page(&format!("/devices/{id}/about"), &cookie)
        .await
        .text();
    assert!(
        page.contains("com.x.Foo.&#60;init&#62;(Foo.kt:3)"),
        "{page}"
    );
    assert!(page.contains("&#60;b&#62;Boom"), "{page}");
    assert!(!page.contains("<b>"), "{page}");
    assert!(!page.contains("<init>"), "{page}");
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
    // The -50 days row goes; -35 is the newest from before the window, -40 the call keeper.
    assert_eq!(crate::retention::prune(db).await.status_reports, 1);
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
        .get_page(&super::device_page(id, super::Section::Calls), &cookie)
        .await
        .text();
    assert!(
        page.contains("stopped reporting that it screens calls"),
        "{page}"
    );
}

/// Security review P1, P6, P7: a fix the phone re-sent as still current counts from that re-send;
/// ended lifts go after 90 days, expired bans and stale download progress at once; a phone's own
/// security-log lines after 30 days (its parent-PIN lines after 90), the rest after 180, and a
/// wrong sign-in's typed name after a day.
#[tokio::test]
async fn pruning_follows_what_each_row_describes() {
    let app = super::TestApp::new().await;
    let db = &app.db;
    let (id, _) = app.create_device("kid").await;
    location(db, id, "2026-10-01T10:00:00.000Z", "-8 days").await;
    location(db, id, "2026-10-02T10:00:00.000Z", "-8 days").await;
    location(db, id, "2026-10-03T10:00:00.000Z", "-8 days").await;
    sqlx::query(
        "UPDATE device_locations SET resent_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now', '-1 hours') \
         WHERE captured_at = '2026-10-02T10:00:00.000Z'",
    )
    .execute(db)
    .await
    .unwrap();
    for ends in ["-91 days", "-89 days", "+1 hours"] {
        sqlx::query(
            "INSERT INTO time_lifts (device_id, target, minutes, expires_at) \
             VALUES (?, 'budget', 30, datetime('now', ?))",
        )
        .bind(id)
        .bind(ends)
        .execute(db)
        .await
        .unwrap();
    }
    sqlx::query(
        "INSERT INTO banned_ips (ip_address, banned_until) VALUES \
         ('192.0.2.1', datetime('now', '-1 minutes')), ('192.0.2.2', datetime('now', '+1 hours'))",
    )
    .execute(db)
    .await
    .unwrap();
    let app_id: i64 = sqlx::query_scalar(
        "INSERT INTO tracked_apps (name, package_name, github_repo) \
         VALUES ('App', 'org.example.app', 'o/r') RETURNING id",
    )
    .fetch_one(db)
    .await
    .unwrap();
    let other: i64 = sqlx::query_scalar(
        "INSERT INTO tracked_apps (name, package_name, github_repo) \
         VALUES ('Other', 'org.example.other', 'o/r2') RETURNING id",
    )
    .fetch_one(db)
    .await
    .unwrap();
    for (app_row, age) in [(app_id, "-11 minutes"), (other, "-1 minutes")] {
        sqlx::query(
            "INSERT INTO device_install_progress (device_id, tracked_app_id, percent, updated_at) \
             VALUES (?, ?, 40, datetime('now', ?))",
        )
        .bind(id)
        .bind(app_row)
        .bind(age)
        .execute(db)
        .await
        .unwrap();
    }
    sqlx::query("DELETE FROM security_events")
        .execute(db)
        .await
        .unwrap();
    // (kind, username, age, kept)
    let lines = [
        ("app_installed", None, "-31 days", false),
        ("boot_unseen", None, "-29 days", true),
        ("phone_lines_dropped", None, "-31 days", false),
        ("parent_pin_used", None, "-91 days", false),
        ("parent_pin_lockout", None, "-89 days", true),
        ("storytel_login_sent", None, "-100 days", true),
        ("login_success", Some("mamma"), "-179 days", true),
        ("login_success", Some("mamma"), "-181 days", false),
    ];
    for (kind, name, age, _) in lines {
        sqlx::query(
            "INSERT INTO security_events (event_type, username, created_at) \
             VALUES (?, ?, datetime('now', ?))",
        )
        .bind(kind)
        .bind(name)
        .bind(age)
        .execute(db)
        .await
        .unwrap();
    }
    for age in ["-25 hours", "-23 hours"] {
        sqlx::query(
            "INSERT INTO security_events (event_type, username, ip_address, created_at) \
             VALUES ('login_failed', 'a typed password', '192.0.2.3', datetime('now', ?))",
        )
        .bind(age)
        .execute(db)
        .await
        .unwrap();
    }

    let pruned = crate::retention::prune(db).await;
    assert_eq!(
        (
            pruned.locations,
            pruned.lifts,
            pruned.bans,
            pruned.install_progress
        ),
        (1, 1, 1, 1),
        "{pruned:?}"
    );
    assert_eq!(pruned.security_events, 4, "{pruned:?}");
    let fixes: Vec<String> = sqlx::query_scalar("SELECT captured_at FROM device_locations")
        .fetch_all(db)
        .await
        .unwrap();
    // The re-sent one by its re-send, the newest as the last known position.
    assert_eq!(
        fixes,
        ["2026-10-02T10:00:00.000Z", "2026-10-03T10:00:00.000Z"]
    );
    let left: Vec<(String, Option<String>)> =
        sqlx::query_as("SELECT event_type, username FROM security_events ORDER BY id")
            .fetch_all(db)
            .await
            .unwrap();
    let mut expected: Vec<(String, Option<String>)> = lines
        .iter()
        .filter(|line| line.3)
        .map(|(kind, name, _, _)| (kind.to_string(), name.map(str::to_string)))
        .collect();
    expected.push(("login_failed".to_string(), None));
    expected.push((
        "login_failed".to_string(),
        Some("a typed password".to_string()),
    ));
    assert_eq!(left, expected);
    assert_eq!(
        count(
            db,
            "SELECT COUNT(*) FROM time_lifts WHERE expires_at > datetime('now', '-90 days')"
        )
        .await,
        2
    );
    assert_eq!(
        count(
            db,
            "SELECT COUNT(*) FROM banned_ips WHERE ip_address = '192.0.2.2'"
        )
        .await,
        1
    );
}

/// Security review P1, the owner's decision on M1 of round S3: a phone's newest fix outlives the
/// window while its location is on; an Off phone keeps none, and choosing Off erases every fix and
/// the shutdown positions in its `phone_off`s at once.
#[tokio::test]
async fn off_erases_every_fix_and_on_keeps_the_last_known() {
    use axum::http::Method;
    let app = super::TestApp::new().await;
    let db = &app.db;
    let (on, _) = app.enrolled_device("on").await;
    let (off, _) = app.enrolled_device("off").await;
    for device in [on, off] {
        location(db, device, "2026-09-01T10:00:00.000Z", "-20 days").await;
        location(db, device, "2026-09-02T10:00:00.000Z", "-10 days").await;
        location(db, device, "2026-10-25T10:00:00.000Z", "-1 hours").await;
    }
    // The aged fixes go, the newest stays on both while neither is Off.
    sqlx::query("DELETE FROM device_locations WHERE captured_at = '2026-10-25T10:00:00.000Z'")
        .execute(db)
        .await
        .unwrap();
    assert_eq!(crate::retention::prune(db).await.locations, 2);
    let left = |device: i64| {
        sqlx::query_scalar::<_, String>(
            "SELECT captured_at FROM device_locations WHERE device_id = ? ORDER BY captured_at",
        )
        .bind(device)
        .fetch_all(db)
    };
    assert_eq!(left(on).await.unwrap(), ["2026-09-02T10:00:00.000Z"]);
    location(db, off, "2026-10-25T10:00:00.000Z", "-1 hours").await;
    for device in [on, off] {
        sqlx::query(
            "INSERT INTO parent_events (device_id, kind, dedup_key, title, summary, target, detail, \
             params, happened_at) VALUES (?, 'phone_off', ?, 'off', 'off', '/devices', \
             'Last position: shown on Locate', \
             '{\"location\":{\"lat\":59.9,\"lng\":10.7}}', datetime('now'))",
        )
        .bind(device)
        .bind(format!("off-{device}"))
        .execute(db)
        .await
        .unwrap();
    }

    let cookie = app.admin_cookie().await;
    let res = app
        .request_form(
            Method::POST,
            &format!("/devices/{off}/location-policy"),
            Some(&cookie),
            &[("mode", "off")],
        )
        .await;
    assert_eq!(
        res.location(),
        Some(format!("/devices/locate?device={off}#location-settings").as_str())
    );
    assert!(left(off).await.unwrap().is_empty());
    assert_eq!(left(on).await.unwrap(), ["2026-09-02T10:00:00.000Z"]);
    let positions: Vec<(i64, Option<String>, Option<String>)> = sqlx::query_as(
        "SELECT device_id, params, detail FROM parent_events WHERE kind = 'phone_off' \
         ORDER BY device_id",
    )
    .fetch_all(db)
    .await
    .unwrap();
    assert!(
        positions[0].1.is_some() && positions[0].2.is_some(),
        "{positions:?}"
    );
    assert_eq!(positions[1], (off, None, None));
    // An Off phone keeps none, even one that arrives some other way (a restored backup).
    location(db, off, "2026-10-26T10:00:00.000Z", "-1 minutes").await;
    crate::retention::prune(db).await;
    assert!(left(off).await.unwrap().is_empty());
    let page = app
        .get_page(&format!("/devices/locate?device={off}"), &cookie)
        .await
        .text();
    assert!(
        page.contains("No position kept while location is off."),
        "{page}"
    );
}
