//! Security review M2: no admin session leaves the live database - not in a backup's snapshot, the
//! live mirror or a pre-migration copy (`tests/docker.rs`) - and a restore brings none back.

use axum::http::StatusCode;

use super::TestApp;
use crate::startup;

fn contains(haystack: &[u8], needle: &str) -> bool {
    haystack
        .windows(needle.len())
        .any(|window| window == needle.as_bytes())
}

#[tokio::test]
async fn a_snapshot_holds_no_session_and_still_the_rest() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let session_id = cookie.split_once('=').unwrap().1.to_string();
    let in_store: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM tower_sessions WHERE id = ?")
        .bind(&session_id)
        .fetch_one(&app.db)
        .await
        .unwrap();
    assert_eq!(in_store, 1, "the probe looks for the id the store keeps");

    let dir = tempfile::tempdir().unwrap();
    let scratch = dir.path().join("scratch.db");
    let target = dir.path().join("snapshot.db");
    crate::handlers::backups::snapshot(&app.db, &scratch, &target)
        .await
        .unwrap();
    assert!(!scratch.exists());
    let bytes = std::fs::read(&target).unwrap();
    assert!(
        !contains(&bytes, &session_id),
        "the session id is in the file"
    );

    let copy = sqlx::SqlitePool::connect(&format!("sqlite://{}", target.display()))
        .await
        .unwrap();
    let sessions: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM tower_sessions")
        .fetch_one(&copy)
        .await
        .unwrap();
    assert_eq!(sessions, 0);
    let admins: Vec<String> = sqlx::query_scalar("SELECT username FROM admin_users")
        .fetch_all(&copy)
        .await
        .unwrap();
    assert_eq!(admins, ["parent"]);
    copy.close().await;
    // A single file: the zip carries only kidphone.db.
    for suffix in ["-wal", "-shm", "-journal"] {
        assert!(!dir.path().join(format!("snapshot.db{suffix}")).exists());
    }

    // The live session is untouched.
    let page = app.get_page("/devices", &cookie).await;
    assert_eq!(page.status, StatusCode::OK);
}

/// The Pi's `actions.sh` leaves `data/restore_applied` (the backup's name); the next start takes
/// it and every session goes - a backup from before 0.24.0 still holds the sessions of its time.
#[tokio::test]
async fn a_restore_signs_everyone_out() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let dir = tempfile::tempdir().unwrap();
    assert_eq!(startup::take_restore_applied(dir.path()), None);

    std::fs::write(
        dir.path().join(startup::RESTORE_APPLIED),
        format!("backup-20261011-020000.zip\n{}", "x".repeat(500)),
    )
    .unwrap();
    let name = startup::take_restore_applied(dir.path()).unwrap();
    assert!(name.starts_with("backup-20261011-020000.zip"), "{name}");
    assert!(name.len() <= 100, "clipped: {}", name.len());
    assert!(!dir.path().join(startup::RESTORE_APPLIED).exists());

    startup::forget_sessions(&app.db).await;
    let page = app.get_page("/devices", &cookie).await;
    assert_eq!(page.status, StatusCode::SEE_OTHER);
    assert_eq!(page.location(), Some("/login"));
}

/// The migration that ends every session from before 0.24.0 (their ids are in older backups).
#[tokio::test]
async fn sessions_from_before_the_update_are_ended() {
    let sql = std::fs::read_to_string("migrations/0061_sessions_out_of_backups.sql").unwrap();
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    sqlx::raw_sql(&sql).execute(&app.db).await.unwrap();
    let page = app.get_page("/devices", &cookie).await;
    assert_eq!(page.location(), Some("/login"));
}
