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

    // The phones' Tailscale key isn't in a copy either (QA of the security wave, a note on M2).
    let tailscale_key = ["tskey", "auth", "TESTONLY", "kCopyProbe"].join("-");
    sqlx::query("UPDATE provisioning_settings SET tailscale_auth_key = ? WHERE id = 1")
        .bind(&tailscale_key)
        .execute(&app.db)
        .await
        .unwrap();

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
    assert!(
        !contains(&bytes, &tailscale_key),
        "the Tailscale key is in the file"
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

/// Design 36 §7: the custom DNS server and its backup stay in a copy - stripping them would make a
/// restore lose them (the TOTP secret's precedent), and they unlock nothing of ours.
#[tokio::test]
async fn a_snapshot_keeps_the_custom_dns_server() {
    let app = TestApp::new().await;
    let pair = ("https://dns.example/dns-query/abc123", "tls://1.1.1.3");
    sqlx::query("UPDATE dns_filter_settings SET custom_server = ?, custom_backup = ?")
        .bind(pair.0)
        .bind(pair.1)
        .execute(&app.db)
        .await
        .unwrap();
    let dir = tempfile::tempdir().unwrap();
    let target = dir.path().join("copy.db");
    crate::handlers::backups::snapshot(&app.db, &dir.path().join("scratch.db"), &target)
        .await
        .unwrap();
    let copy = sqlx::SqlitePool::connect(&format!("sqlite://{}", target.display()))
        .await
        .unwrap();
    let copied: (String, String) =
        sqlx::query_as("SELECT custom_server, custom_backup FROM dns_filter_settings")
            .fetch_one(&copy)
            .await
            .unwrap();
    assert_eq!(copied, (pair.0.to_string(), pair.1.to_string()));
    copy.close().await;
}

#[tokio::test]
async fn the_live_database_keeps_the_tailscale_key() {
    let app = TestApp::new().await;
    let key = ["tskey", "auth", "TESTONLY", "kLive"].join("-");
    sqlx::query("UPDATE provisioning_settings SET tailscale_auth_key = ? WHERE id = 1")
        .bind(&key)
        .execute(&app.db)
        .await
        .unwrap();
    let dir = tempfile::tempdir().unwrap();
    crate::handlers::backups::snapshot(
        &app.db,
        &dir.path().join("scratch.db"),
        &dir.path().join("copy.db"),
    )
    .await
    .unwrap();
    let live: String =
        sqlx::query_scalar("SELECT tailscale_auth_key FROM provisioning_settings WHERE id = 1")
            .fetch_one(&app.db)
            .await
            .unwrap();
    assert_eq!(live, key);
    let copy = sqlx::SqlitePool::connect(&format!(
        "sqlite://{}",
        dir.path().join("copy.db").display()
    ))
    .await
    .unwrap();
    let copied: String =
        sqlx::query_scalar("SELECT tailscale_auth_key FROM provisioning_settings WHERE id = 1")
            .fetch_one(&copy)
            .await
            .unwrap();
    assert_eq!(copied, "");
    copy.close().await;
}
