//! Cleanup round (2026-10-06): the launcher's package rename, the removed monitoring features,
//! retention/pruning and crash reports.

use sqlx::SqlitePool;

/// A database migrated up to (not including) `version`; [`finish`] runs the rest.
async fn migrated_before(version: i64) -> (SqlitePool, tempfile::TempDir) {
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

async fn finish(db: &SqlitePool) {
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
