//! Security review M2: no admin session leaves the live database - not in a backup's snapshot or a
//! pre-migration copy (`tests/docker.rs`) - and a restore brings none back.

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

/// After a restore (`startup::apply_restore_request`, at the start) every session goes - a backup
/// from before 0.24.0 still holds the sessions of its time.
#[tokio::test]
async fn a_restore_signs_everyone_out() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
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

/// Design 116 §4: the restore path proven with the same file - a family backed up, the backup checked as a restore
/// would open it, then put through the restore at the start into a fresh data directory, migrated, and the phone read
/// back.
#[tokio::test]
async fn a_backup_restores() {
    use crate::handlers::backups;
    let app = TestApp::new().await;
    let (id, _) = app.enrolled_device("Emma").await;
    let name = backups::perform_backup(&app.state).await.unwrap();
    backups::check_and_record(&app.db, &app.state.data_dir, &name).await;
    let row = crate::integration_health::row(&app.db, "backup", &format!("file:{name}"))
        .await
        .unwrap()
        .unwrap();
    assert!(
        row.ok_at.is_some() && row.failing_since.is_none(),
        "{row:?}"
    );

    let fresh = tempfile::tempdir().unwrap();
    std::fs::create_dir_all(fresh.path().join("backups")).unwrap();
    std::fs::copy(
        app.state.data_dir.join("backups").join(&name),
        fresh.path().join("backups").join(&name),
    )
    .unwrap();
    std::fs::write(fresh.path().join(startup::RESTORE_REQUEST), &name).unwrap();
    let db_path = fresh.path().join("kidphone.db");
    let restored = startup::apply_restore_request(fresh.path(), &db_path).unwrap();
    assert!(restored.is_some());
    let db = crate::connect_db(&format!("sqlite://{}", db_path.display())).await;
    let phone: String = sqlx::query_scalar("SELECT name FROM devices WHERE id = ?")
        .bind(id)
        .fetch_one(&db)
        .await
        .unwrap();
    assert_eq!(phone, "Emma");
}

/// Design 116 §4: a backup that can't be read is one orange line on Backups and the family's row on the Devices list;
/// a good one shows a quiet "checked"; deleting the bad file clears both.
#[tokio::test]
async fn a_backup_that_cant_be_read_shows_until_it_is_gone() {
    use crate::handlers::backups;
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let good = backups::perform_backup(&app.state).await.unwrap();
    backups::check_and_record(&app.db, &app.state.data_dir, &good).await;
    let page = app.get_page("/backups", &cookie).await.text();
    assert!(page.contains("data-checked>checked<"), "{page}");
    assert!(!page.contains("data-note=\"backup-problem\""));

    // A newer backup whose database is gone.
    let bad = "backup-29991231-235959.zip";
    std::fs::write(app.state.data_dir.join("backups").join(bad), b"not a zip").unwrap();
    backups::check_and_record(&app.db, &app.state.data_dir, bad).await;
    let page = app.get_page("/backups", &cookie).await.text();
    assert!(page.contains("data-note=\"backup-problem\""), "{page}");
    assert!(
        page.contains("Backup can&#39;t be read") || page.contains("Backup can't be read"),
        "{page}"
    );
    let list = app.get_page("/devices", &cookie).await.text();
    assert!(list.contains("id=\"backup-warning\""), "{list}");

    let res = app
        .request_form(
            axum::http::Method::POST,
            &format!("/backups/{bad}/delete"),
            Some(&cookie),
            &[],
        )
        .await;
    assert_eq!(res.status, StatusCode::SEE_OTHER);
    let list = app.get_page("/devices", &cookie).await.text();
    assert!(!list.contains("id=\"backup-warning\""), "{list}");
    assert!(
        crate::integration_health::row(&app.db, "backup", &format!("file:{bad}"))
            .await
            .unwrap()
            .is_none()
    );
}
