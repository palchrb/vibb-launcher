//! Startup and the container (design 22 §6, §9 "Server, contract and Docker"): the
//! pre-migration backup, the restore marker, the healthcheck, and `DEPLOY_KIND=docker` in the
//! app.

use std::io::Write;
use std::path::{Path, PathBuf};

use axum::http::{Method, StatusCode};

use super::TestApp;
use crate::handlers::{system_maintenance, system_update, updates};
use crate::startup;

/// A database in `dir/data/kidphone.db` migrated only up to `version`, as an older server left
/// it (only those migration files run), with one admin row so there's something to keep.
async fn old_database(dir: &Path, version: i64) -> (String, PathBuf) {
    let data = dir.join("data");
    std::fs::create_dir_all(&data).unwrap();
    let path = data.join("kidphone.db");
    for suffix in ["", "-wal", "-shm"] {
        let _ = std::fs::remove_file(format!("{}{suffix}", path.display()));
    }
    let older = tempfile::tempdir().unwrap();
    for entry in std::fs::read_dir("migrations").unwrap() {
        let entry = entry.unwrap();
        let name = entry.file_name().to_string_lossy().to_string();
        let number: i64 = name.split('_').next().unwrap().parse().unwrap();
        if number <= version {
            std::fs::copy(entry.path(), older.path().join(&name)).unwrap();
        }
    }
    let url = format!("sqlite://{}", path.display());
    let options = <sqlx::sqlite::SqliteConnectOptions as std::str::FromStr>::from_str(&url)
        .unwrap()
        .create_if_missing(true)
        .journal_mode(sqlx::sqlite::SqliteJournalMode::Wal);
    let db = sqlx::SqlitePool::connect_with(options).await.unwrap();
    sqlx::migrate::Migrator::new(older.path())
        .await
        .unwrap()
        .run(&db)
        .await
        .unwrap();
    sqlx::query(
        "INSERT INTO admin_users (username, password_hash, must_change_password) \
         VALUES ('old', 'x', 0)",
    )
    .execute(&db)
    .await
    .unwrap();
    // A signed-in session, as the session store keeps it.
    tower_sessions_sqlx_store::SqliteStore::new(db.clone())
        .migrate()
        .await
        .unwrap();
    sqlx::query("INSERT INTO tower_sessions (id, data, expiry_date) VALUES (?, x'00', 4102444800)")
        .bind(OLD_SESSION_ID)
        .execute(&db)
        .await
        .unwrap();
    db.close().await;
    (url, path)
}

const OLD_SESSION_ID: &str = "old-session-id-QA22-M2-probe";

/// The migration versions, oldest first (numbers can have gaps: another branch's migrations).
fn versions() -> Vec<i64> {
    let mut versions: Vec<i64> = crate::MIGRATOR.iter().map(|m| m.version).collect();
    versions.sort();
    versions
}

/// The version `back` steps before the newest.
fn back(back: usize) -> i64 {
    let versions = versions();
    versions[versions.len() - 1 - back]
}

fn pre_migrate_files(dir: &Path) -> Vec<String> {
    let mut names: Vec<String> = std::fs::read_dir(dir.join("data/backups"))
        .map(|entries| {
            entries
                .filter_map(Result::ok)
                .map(|e| e.file_name().to_string_lossy().to_string())
                .filter(|n| n.starts_with("pre-migrate-"))
                .collect()
        })
        .unwrap_or_default();
    names.sort();
    names
}

#[tokio::test]
async fn pending_migrations_are_backed_up_once_and_three_are_kept() {
    let dir = tempfile::tempdir().unwrap();
    let latest = back(0);
    // A fresh database needs no backup.
    assert!(pre_migrate_files(dir.path()).is_empty());

    let (url, _) = old_database(dir.path(), back(2)).await;
    let db = crate::try_connect_db(&url).await.unwrap();
    let names = pre_migrate_files(dir.path());
    assert_eq!(
        names,
        [format!("pre-migrate-{:04}-{latest:04}.db", back(2))]
    );
    // It is the database before the migrations: it opens, it has the admin, and it stops at the
    // old version.
    let backup = dir.path().join("data/backups").join(&names[0]);
    let old = sqlx::SqlitePool::connect(&format!("sqlite://{}", backup.display()))
        .await
        .unwrap();
    let version: i64 = sqlx::query_scalar("SELECT MAX(version) FROM _sqlx_migrations")
        .fetch_one(&old)
        .await
        .unwrap();
    assert_eq!(version, back(2));
    let admins: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM admin_users")
        .fetch_one(&old)
        .await
        .unwrap();
    assert_eq!(admins, 1);
    // Security review M2: no session in the copy, not even in its free pages; nothing left
    // beside the database.
    let sessions: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM tower_sessions")
        .fetch_one(&old)
        .await
        .unwrap();
    assert_eq!(sessions, 0);
    old.close().await;
    let bytes = std::fs::read(&backup).unwrap();
    assert!(
        !bytes
            .windows(OLD_SESSION_ID.len())
            .any(|w| w == OLD_SESSION_ID.as_bytes())
    );
    assert!(!dir.path().join("data/kidphone.db.pre-migrate-tmp").exists());
    db.close().await;

    // Taken once: nothing pending at the next start.
    crate::try_connect_db(&url).await.unwrap().close().await;
    assert_eq!(pre_migrate_files(dir.path()).len(), 1);

    // Three are kept (each an upgrade from an older version).
    for steps in [5, 4, 3, 1] {
        let (url, _) = old_database(dir.path(), back(steps)).await;
        crate::try_connect_db(&url).await.unwrap().close().await;
    }
    let names = pre_migrate_files(dir.path());
    assert_eq!(names.len(), 3, "{names:?}");
    assert!(names[2].starts_with(&format!("pre-migrate-{:04}-", back(1))));
}

/// A backup zip as `perform_backup` makes it, holding `db_file` as `kidphone.db` (or nothing).
fn backup_zip(dir: &Path, name: &str, db_file: Option<&Path>) {
    let backups = dir.join("data/backups");
    std::fs::create_dir_all(&backups).unwrap();
    let file = std::fs::File::create(backups.join(name)).unwrap();
    let mut zip = zip::ZipWriter::new(file);
    let options = zip::write::SimpleFileOptions::default();
    match db_file {
        Some(db) => {
            zip.start_file("kidphone.db", options).unwrap();
            zip.write_all(&std::fs::read(db).unwrap()).unwrap();
        }
        None => {
            zip.start_file("notes.txt", options).unwrap();
            zip.write_all(b"no database here").unwrap();
        }
    }
    zip.finish().unwrap();
}

async fn admin_names(url: &str) -> Vec<String> {
    let db = crate::connect_db(url).await;
    let names = sqlx::query_scalar("SELECT username FROM admin_users ORDER BY username")
        .fetch_all(&db)
        .await
        .unwrap();
    db.close().await;
    names
}

#[tokio::test]
async fn the_restore_marker_swaps_in_a_good_backup_and_refuses_bad_ones() {
    let dir = tempfile::tempdir().unwrap();
    let data = dir.path().join("data");
    // The backup's database has "from-backup"; the live one "live".
    let backup_dir = tempfile::tempdir().unwrap();
    let backup_db = backup_dir.path().join("b.db");
    let db = crate::connect_db(&format!("sqlite://{}", backup_db.display())).await;
    sqlx::query("INSERT INTO admin_users (username, password_hash) VALUES ('from-backup', 'x')")
        .execute(&db)
        .await
        .unwrap();
    sqlx::query("PRAGMA wal_checkpoint(TRUNCATE)")
        .execute(&db)
        .await
        .unwrap();
    db.close().await;
    std::fs::create_dir_all(&data).unwrap();
    let live = data.join("kidphone.db");
    let url = format!("sqlite://{}", live.display());
    let db = crate::connect_db(&url).await;
    sqlx::query("INSERT INTO admin_users (username, password_hash) VALUES ('live', 'x')")
        .execute(&db)
        .await
        .unwrap();
    // Everything in the main file before the pool goes: a connection that is still closing
    // would otherwise checkpoint (and delete -wal) after the restore moved the files. At startup
    // nothing is open yet, so this is only the test's concern.
    sqlx::query("PRAGMA wal_checkpoint(TRUNCATE)")
        .execute(&db)
        .await
        .unwrap();
    db.close().await;

    backup_zip(dir.path(), "backup-20261009-020000.zip", Some(&backup_db));
    backup_zip(dir.path(), "backup-20261009-030000.zip", None);
    let not_sqlite = dir.path().join("not-sqlite.db");
    std::fs::write(&not_sqlite, b"tiny").unwrap();
    backup_zip(dir.path(), "backup-20261009-031000.zip", Some(&not_sqlite));

    // No marker: nothing happens.
    assert!(
        startup::apply_restore_request(&data, &live)
            .unwrap()
            .is_none()
    );

    // Bad names, a missing file, a zip without a database: refused, the marker gone, the live
    // database untouched.
    for name in [
        "../kidphone.db",
        "backup-x/../../etc.zip",
        "kidphone.db",
        "backup-missing.zip",
        "backup-20261009-030000.zip",
        "backup-20261009-031000.zip",
    ] {
        std::fs::write(data.join(startup::RESTORE_REQUEST), name).unwrap();
        let result = startup::apply_restore_request(&data, &live);
        assert!(result.is_err(), "{name}: {result:?}");
        assert!(!data.join(startup::RESTORE_REQUEST).exists(), "{name}");
        assert_eq!(admin_names(&url).await, ["live"], "{name}");
    }

    // A good one: swapped in, the old database kept aside.
    std::fs::write(
        data.join(startup::RESTORE_REQUEST),
        "backup-20261009-020000.zip\n",
    )
    .unwrap();
    let restored = startup::apply_restore_request(&data, &live)
        .unwrap()
        .expect("applied");
    assert!(!data.join(startup::RESTORE_REQUEST).exists());
    assert_eq!(admin_names(&url).await, ["from-backup"]);
    let aside = restored.prerestore_dir.join("kidphone.db");
    assert!(aside.exists());
    assert_eq!(
        admin_names(&format!("sqlite://{}", aside.display())).await,
        ["live"]
    );
}

#[tokio::test]
async fn a_restored_database_that_wont_open_is_put_back() {
    let dir = tempfile::tempdir().unwrap();
    let data = dir.path().join("data");
    std::fs::create_dir_all(&data).unwrap();
    let live = data.join("kidphone.db");
    let url = format!("sqlite://{}", live.display());
    let db = crate::connect_db(&url).await;
    sqlx::query("INSERT INTO admin_users (username, password_hash) VALUES ('live', 'x')")
        .execute(&db)
        .await
        .unwrap();
    // Everything in the main file before the pool goes: a connection that is still closing
    // would otherwise checkpoint (and delete -wal) after the restore moved the files. At startup
    // nothing is open yet, so this is only the test's concern.
    sqlx::query("PRAGMA wal_checkpoint(TRUNCATE)")
        .execute(&db)
        .await
        .unwrap();
    db.close().await;
    // A "database" that isn't one.
    let junk = dir.path().join("junk.db");
    // An SQLite header with garbage after it (a file that isn't one at all is refused before the
    // swap - see the test above).
    let mut bytes = b"SQLite format 3\0".to_vec();
    bytes.extend(b"this is not an SQLite file at all. ".repeat(400));
    std::fs::write(&junk, bytes).unwrap();
    backup_zip(dir.path(), "backup-20261009-040000.zip", Some(&junk));
    std::fs::write(
        data.join(startup::RESTORE_REQUEST),
        "backup-20261009-040000.zip",
    )
    .unwrap();

    let restored = startup::apply_restore_request(&data, &live)
        .unwrap()
        .expect("applied");
    assert!(crate::try_connect_db(&url).await.is_err(), "junk opened");
    startup::undo_restore(&live, &restored).unwrap();
    let listing = |dir: &Path| -> Vec<String> {
        std::fs::read_dir(dir)
            .map(|entries| {
                entries
                    .filter_map(Result::ok)
                    .map(|e| format!("{:?} {:?}", e.file_name(), e.metadata().map(|m| m.len())))
                    .collect()
            })
            .unwrap_or_default()
    };
    assert_eq!(
        admin_names(&url).await,
        ["live"],
        "data: {:?}, aside: {:?}",
        listing(&data),
        listing(&restored.prerestore_dir)
    );
}

/// QA 22 Low 7: a kidphone.db over the cap is refused before anything is swapped, and only the
/// newest prerestore copies are kept.
#[tokio::test]
async fn a_restore_is_size_capped_and_old_prerestore_copies_go() {
    let dir = tempfile::tempdir().unwrap();
    let data = dir.path().join("data");
    std::fs::create_dir_all(&data).unwrap();
    let live = data.join("kidphone.db");
    let url = format!("sqlite://{}", live.display());
    let db = crate::connect_db(&url).await;
    sqlx::query("INSERT INTO admin_users (username, password_hash) VALUES ('live', 'x')")
        .execute(&db)
        .await
        .unwrap();
    sqlx::query("PRAGMA wal_checkpoint(TRUNCATE)")
        .execute(&db)
        .await
        .unwrap();
    db.close().await;
    let backup_db = dir.path().join("b.db");
    std::fs::copy(&live, &backup_db).unwrap();
    let size = std::fs::metadata(&backup_db).unwrap().len();
    backup_zip(dir.path(), "backup-20261009-050000.zip", Some(&backup_db));
    let backups = data.join("backups");

    std::fs::write(
        data.join(startup::RESTORE_REQUEST),
        "backup-20261009-050000.zip",
    )
    .unwrap();
    let refused = startup::apply_restore_request_capped(&data, &live, size - 1);
    assert!(refused.unwrap_err().contains("larger than"));
    assert!(!data.join("kidphone.db.restore-tmp").exists());
    assert_eq!(admin_names(&url).await, ["live"]);
    assert!(
        !std::fs::read_dir(&backups)
            .unwrap()
            .filter_map(Result::ok)
            .any(|e| e.file_name().to_string_lossy().starts_with("prerestore-")),
        "a refused restore makes no copy"
    );

    // Four older copies, one of them dated in the future (a clock set back): the new copy stays,
    // with the two newest of the rest.
    for name in [
        "prerestore-20260101-000000",
        "prerestore-20260201-000000",
        "prerestore-20260301-000000",
        "prerestore-29990101-000000",
    ] {
        std::fs::create_dir_all(backups.join(name)).unwrap();
    }
    std::fs::write(
        data.join(startup::RESTORE_REQUEST),
        "backup-20261009-050000.zip",
    )
    .unwrap();
    let restored = startup::apply_restore_request_capped(&data, &live, size)
        .unwrap()
        .expect("applied");
    let mut left: Vec<String> = std::fs::read_dir(&backups)
        .unwrap()
        .filter_map(Result::ok)
        .map(|e| e.file_name().to_string_lossy().to_string())
        .filter(|name| name.starts_with("prerestore-"))
        .collect();
    left.sort();
    let current = restored
        .prerestore_dir
        .file_name()
        .unwrap()
        .to_string_lossy()
        .to_string();
    assert_eq!(left.len(), startup::PRERESTORE_KEEP, "{left:?}");
    assert!(left.contains(&current), "{left:?}");
    assert!(left.contains(&"prerestore-29990101-000000".to_string()));
    assert!(left.contains(&"prerestore-20260301-000000".to_string()));
}

#[tokio::test]
async fn the_healthcheck_dials_the_admin_listener() {
    let app = TestApp::new().await;
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let port = listener.local_addr().unwrap().port();
    let router = crate::build_device_router(app.state.clone());
    tokio::spawn(async move {
        axum::serve(
            listener,
            router.into_make_service_with_connect_info::<std::net::SocketAddr>(),
        )
        .await
        .unwrap();
    });
    assert_eq!(
        startup::healthcheck(&format!("127.0.0.1:{port}")).await,
        Ok(())
    );
    // A wildcard bind is dialled on loopback.
    assert_eq!(
        startup::healthcheck(&format!("0.0.0.0:{port}")).await,
        Ok(())
    );
    // Nothing listening: unhealthy.
    let closed = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let closed_port = closed.local_addr().unwrap().port();
    drop(closed);
    assert!(
        startup::healthcheck(&format!("127.0.0.1:{closed_port}"))
            .await
            .is_err()
    );
}

fn app_data(latest: Option<&str>, update_available: bool) -> system_update::AppUpdateData {
    system_update::AppUpdateData {
        current_version: "v0.23.0".to_string(),
        latest_version: latest.map(str::to_string),
        update_available,
        check_failed: latest.is_none(),
        watcher_version: None,
        watcher_needs_update: true,
        reinstall_hint: "curl ... | sudo bash".to_string(),
        schedule: system_update::AppUpdateScheduleConfig::defaults(),
    }
}

#[test]
fn the_updates_page_says_how_to_update_the_image() {
    let page = updates::page(
        true,
        app_data(Some("v0.24.0"), true),
        system_maintenance::not_managed(),
        None,
        crate::i18n::Lang::En,
    )
    .0;
    assert!(page.contains("Version 0.24.0 is out"), "{page}");
    // The commands are behind the row's ⓘ, never in its visible text (design 31 §4.2).
    let row = &page[page.find("data-row=\"latest\"").unwrap()..];
    let row = &row[..row.find("</details>").unwrap()];
    let (summary, panel) = row.split_once("</summary>").unwrap();
    assert!(!summary.contains("HANDY_VERSION"), "{summary}");
    assert!(panel.contains("HANDY_VERSION=0.24.0"));
    assert!(panel.contains("docker compose pull &amp;&amp; docker compose up -d"));
    for gone in [
        "/update/trigger",
        "/update/restart",
        "/update/schedule",
        "/system/",
        "Raspberry Pi OS",
        "Tailscale</h2>",
        "installer",
        "Restart Server",
    ] {
        assert!(!page.contains(gone), "{gone} on the Docker page");
    }
    let current = updates::page(
        true,
        app_data(Some("v0.23.0"), false),
        system_maintenance::not_managed(),
        None,
        crate::i18n::Lang::En,
    )
    .0;
    assert!(current.contains("Up to date"));
    assert!(!current.contains("HANDY_VERSION"));

    // systemd keeps its page: the confirms, the installer warning with its command behind ⓘ.
    let pi = updates::page(
        false,
        app_data(Some("v0.24.0"), true),
        system_maintenance::not_managed(),
        None,
        crate::i18n::Lang::En,
    )
    .0;
    assert!(!pi.contains("HANDY_VERSION"));
    for confirm in [
        "/update/trigger#app",
        "/update/restart#app",
        "/system/os/upgrade#os",
    ] {
        let at = pi.find(&format!("action=\"{confirm}\"")).expect(confirm);
        let before = &pi[..at];
        assert!(
            before.rfind("<details class=\"confirm").unwrap() > before.rfind("</details>").unwrap(),
            "{confirm} is behind a <details> confirm"
        );
    }
    let warning = &pi[pi.find("data-warning=\"installer\"").unwrap()..];
    let warning = &warning[..warning.find("</details>").unwrap()];
    let (visible, panel) = warning.split_once("</summary>").unwrap();
    assert!(!visible.contains("sudo bash"), "{visible}");
    assert!(panel.contains("curl ... | sudo bash"));
    super::settings_pages::check_forms("/updates", &pi);
}

/// A result shows in the group whose form posted it, never at the page's top (design 31 §3.0).
#[test]
fn an_updates_result_shows_in_its_own_group() {
    use crate::handlers::settings::Flash;
    for (at, before, after) in [
        ("app", "id=\"app\"", "id=\"os\""),
        ("os", "id=\"os\"", "id=\"tailscale\""),
        ("tailscale", "id=\"tailscale\"", "id=\"app-schedule\""),
        ("app-schedule", "id=\"app-schedule\"", "id=\"os-schedule\""),
        ("os-schedule", "id=\"os-schedule\"", "</main>"),
    ] {
        let page = updates::page(
            false,
            app_data(Some("v0.23.0"), false),
            system_maintenance::not_managed(),
            Some(Flash::done(at, "Probe result.")),
            crate::i18n::Lang::En,
        )
        .0;
        let flash = page.find("Probe result.").expect(at);
        assert!(page.find(before).unwrap() < flash, "{at}");
        assert!(flash < page.find(after).unwrap(), "{at}");
        assert_eq!(page.matches("Probe result.").count(), 1, "{at}");
    }
    // A refusal with a reason: the title visible, the reason behind ⓘ.
    let page = updates::page(
        false,
        app_data(Some("v0.23.0"), false),
        system_maintenance::not_managed(),
        Some(Flash::refused("app", "Nothing was started").because("Probe reason.")),
        crate::i18n::Lang::En,
    )
    .0;
    let flash = &page[page.find("data-flash=\"app\"").unwrap()..];
    let (title, panel) = flash.split_once("</summary>").unwrap();
    assert!(title.contains("Nothing was started"));
    assert!(!title.contains("Probe reason."));
    assert!(panel[..panel.find("</details>").unwrap()].contains("Probe reason."));
}

#[tokio::test]

async fn in_docker_the_host_helper_routes_and_drive_features_are_gone() {
    let app = TestApp::with_env(&[("DEPLOY_KIND", "docker")]).await;
    let cookie = app.admin_cookie().await;
    for path in [
        "/update/trigger",
        "/update/restart",
        "/update/schedule",
        "/system/os/check",
        "/system/os/upgrade",
        "/system/tailscale/update",
        "/system/reboot",
        "/system/schedule",
        "/backups/format-drive",
    ] {
        let res = app
            .request_form(Method::POST, path, Some(&cookie), &[])
            .await;
        assert_eq!(res.status, StatusCode::NOT_FOUND, "{path}");
    }
    let page = app.get_page("/backups", &cookie).await.text();
    assert!(!page.contains("id=\"drive\""), "{page}");
    assert!(!page.contains("external drive"), "{page}");
    assert!(!page.contains("continuous_mirror_enabled"));
    assert!(page.contains("copy them off the host"));
    // On the Pi they are there.
    let pi = TestApp::new().await;
    let cookie = pi.admin_cookie().await;
    let page = pi.get_page("/backups", &cookie).await.text();
    assert!(page.contains("id=\"drive\""));
    assert!(page.contains("continuous_mirror_enabled"));
}
