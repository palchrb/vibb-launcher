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
