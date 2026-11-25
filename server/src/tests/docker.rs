//! Startup and the container (design 22 §6, §9 "Server, contract and Docker"): the
//! pre-migration backup, the restore marker, the healthcheck, and `DEPLOY_KIND=docker` in the
//! app.

use std::io::Write;
use std::path::{Path, PathBuf};

use axum::http::{Method, StatusCode};

use super::TestApp;
use crate::handlers::{system_update, updates};
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
            .await
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
        let result = startup::apply_restore_request(&data, &live).await;
        assert!(result.is_err(), "{name}: {result:?}");
        assert!(!data.join(startup::RESTORE_REQUEST).exists(), "{name}");
        assert_eq!(admin_names(&url).await, ["live"], "{name}");
    }

    // A good one, the PWA's marker naming who asked (privacy review 2026-11-17 M1): swapped in, the old database kept
    // aside.
    std::fs::write(
        data.join(startup::RESTORE_REQUEST),
        crate::handlers::backups::restore_marker(
            "backup-20261009-020000.zip",
            "parent",
            "Mamma",
            chrono::DateTime::from_timestamp_millis(1_794_826_200_000).unwrap(),
        ),
    )
    .unwrap();
    let restored = startup::apply_restore_request(&data, &live)
        .await
        .unwrap()
        .expect("applied");
    assert_eq!(
        restored.by,
        Some(("parent".to_string(), "Mamma".to_string()))
    );
    assert_eq!(
        restored.asked_at.map(|at| at.timestamp_millis()),
        Some(1_794_826_200_000)
    );
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
async fn a_restored_database_is_put_back_by_the_undo() {
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

    // A database that won't open is refused by the backup check before anything is swapped.
    let refused = startup::apply_restore_request(&data, &live).await;
    assert!(refused.is_err(), "{refused:?}");
    assert_eq!(admin_names(&url).await, ["live"]);

    // A backup that passes: swapped in, then put back by the undo (the start's path when the restored database
    // doesn't open).
    let good = dir.path().join("good.db");
    let db = crate::connect_db(&format!("sqlite://{}", good.display())).await;
    sqlx::query("INSERT INTO admin_users (username, password_hash) VALUES ('from-backup', 'x')")
        .execute(&db)
        .await
        .unwrap();
    sqlx::query("PRAGMA wal_checkpoint(TRUNCATE)")
        .execute(&db)
        .await
        .unwrap();
    db.close().await;
    backup_zip(dir.path(), "backup-20261009-041000.zip", Some(&good));
    std::fs::write(
        data.join(startup::RESTORE_REQUEST),
        "backup-20261009-041000.zip",
    )
    .unwrap();
    let restored = startup::apply_restore_request(&data, &live)
        .await
        .unwrap()
        .expect("applied");
    // The owner's one-line marker over SSH names no one.
    assert_eq!((restored.by.as_ref(), restored.asked_at), (None, None));
    assert_eq!(admin_names(&url).await, ["from-backup"]);
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
    let refused = startup::apply_restore_request_capped(&data, &live, size - 1).await;
    assert!(refused.unwrap_err().message.contains("larger than"));
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
        .await
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
        checking: false,
    }
}

/// Software Updates shows versions only (the user, 2026-11-04): no form, no host state; how to
/// update is behind the Version row's ⓘ, never in its visible text (design 31 §4.2, design 113) - in Docker the image
/// host's `./update-image.sh` with the newer version (privacy review 2026-11-17 H1), on a systemd
/// install the update.sh command for the configured repo.
#[test]
fn the_updates_page_says_how_to_update() {
    let page = |docker: bool, latest: &str, newer: bool| {
        updates::page(
            docker,
            false,
            app_data(Some(latest), newer),
            "someone/vibb-launcher",
            crate::i18n::Lang::En,
        )
        .0
    };
    let how = |page: &str| {
        let row = &page[page.find("data-row=\"version\"").unwrap()..];
        let row = &row[..row.find("</details>").unwrap()];
        let (summary, panel) = row.split_once("</summary>").unwrap();
        (summary.to_string(), panel.to_string())
    };
    let docker = page(true, "v0.24.0", true);
    assert!(docker.contains("Version 0.24.0 is out"), "{docker}");
    let (summary, panel) = how(&docker);
    assert!(!summary.contains("docker compose"), "{summary}");
    // Privacy review 2026-11-17 H1, the user's choice (2026-11-18): Watchtower, and the host script that checks the
    // attestation.
    assert!(panel.contains("Watchtower"), "{panel}");
    assert!(
        panel.contains("<code>./update-image.sh 0.24.0</code>"),
        "{panel}"
    );
    assert!(!panel.contains("update.sh"));
    let current = page(true, "v0.23.0", false);
    // Design 113: up to date shows no latest row.
    assert!(!current.contains("data-row=\"latest\""), "{current}");
    assert!(current.contains("./update-image.sh X.Y.Z"), "{current}");

    let pi = page(false, "v0.24.0", true);
    assert!(pi.contains("Version 0.24.0 is out"));
    let (summary, panel) = how(&pi);
    assert!(!summary.contains("sudo"), "{summary}");
    // Security QA L1: always the project's command; another SERVER_RELEASE_REPO is named, never used.
    let project = "raw.githubusercontent.com/palchrb/vibb-launcher/main/server/deploy/update.sh \
                   &#38;&#38; sudo bash update.sh";
    assert!(panel.contains(project), "{panel}");
    assert!(!panel.contains("KPS_REPO"), "{panel}");
    assert!(
        panel.contains("looks for releases in someone/vibb-launcher (SERVER_RELEASE_REPO in .env)"),
        "{panel}"
    );
    let own = updates::page(
        false,
        true,
        app_data(Some("v0.24.0"), true),
        crate::config::DEFAULT_SERVER_RELEASE_REPO,
        crate::i18n::Lang::En,
    )
    .0;
    let (_, own_panel) = how(&own);
    assert!(own_panel.contains(project));
    assert!(!own.contains("data-repo=\"other\""));
    // The update-image.sh line is a Docker install's only.
    assert!(!own.contains("data-row=\"image-script\""), "{own}");
    // Security review L10: downloaded to a file, never piped into a root shell.
    assert!(!panel.contains("| sudo"));
    assert!(!pi.contains("HANDY_VERSION"));
    for page in [&docker, &current, &pi] {
        assert!(!page.contains("<form"), "{page}");
        assert!(page.contains("never changes anything on the machine it runs on"));
        for gone in [
            "/update/",
            "/system/",
            "Raspberry Pi OS",
            "Tailscale</h2>",
            "installer",
            "Restart",
            "data-warning",
            "Automatic",
        ] {
            assert!(!page.contains(gone), "{gone}: {page}");
        }
    }
    super::settings_pages::check_forms("/updates", &pi);
}

/// The image is now signed with the release key and only the new update-image.sh checks it: a Docker install's Software
/// Updates shows one orange line, the step behind its ⓘ, until the parent's Done - which lands back on the group,
/// never the top of the page, and hides it for good. A systemd install never shows it.
#[tokio::test]
async fn docker_shows_the_update_image_line_until_done() {
    let app = TestApp::with_env(&[("DEPLOY_KIND", "docker")]).await;
    let cookie = app.admin_cookie().await;
    let page = app.get_page("/updates", &cookie).await.text();
    let line = &page[page
        .find("data-row=\"image-script\"")
        .expect("the line on a Docker install")..];
    let line = &line[..line.find("</details>").unwrap()];
    let (summary, panel) = line.split_once("</summary>").unwrap();
    assert!(
        summary.contains("tone-orange\">update-image.sh needs replacing once</span>"),
        "{summary}"
    );
    assert!(panel.contains("Replace yours once"), "{panel}");
    assert!(
        panel.contains("action=\"/updates/image-script/done#app\""),
        "{panel}"
    );
    super::settings_pages::check_forms("/updates", &page);
    let nb = app.get_page("/updates?lang=nb", &cookie).await.text();
    assert!(nb.contains("update-image.sh må byttes én gang"), "{nb}");

    let res = app
        .request_form(
            Method::POST,
            "/updates/image-script/done",
            Some(&cookie),
            &[],
        )
        .await;
    super::assert_redirect(&res, "/updates", Some("app"));
    let page = app.get_page("/updates", &cookie).await.text();
    assert!(!page.contains("data-row=\"image-script\""), "{page}");
    assert!(!page.contains("<form"), "{page}");
    // Done twice (another tab) is still Done.
    let res = app
        .request_form(
            Method::POST,
            "/updates/image-script/done",
            Some(&cookie),
            &[],
        )
        .await;
    super::assert_redirect(&res, "/updates", Some("app"));

    let pi = TestApp::new().await;
    let cookie = pi.admin_cookie().await;
    let page = pi.get_page("/updates", &cookie).await.text();
    assert!(!page.contains("data-row=\"image-script\""), "{page}");
}

/// Nothing the server could ask of its host is left (the user, 2026-11-04): the old routes are
/// gone in Docker and on a systemd install alike, and Backups has no external drive anywhere.
#[tokio::test]
async fn no_route_asks_the_host_for_anything() {
    for env in [&[("DEPLOY_KIND", "docker")][..], &[][..]] {
        let app = TestApp::with_env(env).await;
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
            assert_eq!(res.status, StatusCode::NOT_FOUND, "{path} {env:?}");
        }
        assert!(!app.state.data_dir.join("update_requested").exists());
        let page = app.get_page("/backups", &cookie).await.text();
        assert!(!page.contains("id=\"drive\""), "{page}");
        assert!(!page.contains("external drive"), "{page}");
        assert!(!page.contains("continuous_mirror_enabled"));
        assert!(!page.contains("external_copy_enabled"));
    }
    let app = TestApp::with_env(&[("DEPLOY_KIND", "docker")]).await;
    let cookie = app.admin_cookie().await;
    let page = app.get_page("/backups", &cookie).await.text();
    assert!(page.contains("copy them off the host"));
}

/// A fake GitHub release list: `tag` (`None`: a 500), and how many reads it answered.
async fn fake_github(
    tag: std::sync::Arc<std::sync::Mutex<Option<&'static str>>>,
    hits: std::sync::Arc<std::sync::atomic::AtomicUsize>,
) -> String {
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let url = format!("http://{}", listener.local_addr().unwrap());
    use axum::response::IntoResponse;
    let releases = move || async move {
        hits.fetch_add(1, std::sync::atomic::Ordering::SeqCst);
        match *tag.lock().unwrap() {
            Some(tag) => axum::Json(serde_json::json!([{ "tag_name": tag }])).into_response(),
            None => StatusCode::INTERNAL_SERVER_ERROR.into_response(),
        }
    };
    let router = axum::Router::new().route(
        "/repos/{owner}/{repo}/releases",
        axum::routing::get(releases),
    );
    tokio::spawn(async move { axum::serve(listener, router).await });
    url
}

/// Software Updates never waits on GitHub (2026-11-03): it shows the release read in the
/// background, a due read starts once however many pages ask, and a failed read keeps the last
/// good tag.
#[tokio::test]
async fn the_updates_page_shows_the_release_read_in_the_background() {
    use std::sync::atomic::Ordering::SeqCst;
    let tag = std::sync::Arc::new(std::sync::Mutex::new(Some("server-v99.0.0")));
    let hits = std::sync::Arc::new(std::sync::atomic::AtomicUsize::new(0));
    let mut config = crate::config::ForkConfig::for_tests();
    config.github_api = fake_github(tag.clone(), hits.clone()).await;
    let app = TestApp::with_net_config(Default::default(), config, Default::default()).await;
    let cookie = app.admin_cookie().await;
    let release = &app.state.server_release;
    let read_ended = || async {
        for _ in 0..500 {
            if !release.latest().1 {
                return;
            }
            tokio::time::sleep(std::time::Duration::from_millis(10)).await;
        }
        panic!("the background read never ended");
    };
    let latest_row = |page: &str| {
        let row = &page[page.find("data-row=\"latest\"").unwrap()..];
        row[..row.find("</").unwrap()].to_string()
    };

    // Nothing read yet: checking, not a failure; the read starts in the background.
    let page = app.get_page("/updates", &cookie).await.text();
    assert!(
        latest_row(&page).contains("Checking for a new version"),
        "{page}"
    );
    read_ended().await;
    assert_eq!(hits.load(SeqCst), 1);
    let page = app.get_page("/updates", &cookie).await.text();
    assert!(
        latest_row(&page).contains("Version 99.0.0 is out"),
        "{page}"
    );
    assert_eq!(hits.load(SeqCst), 1, "a fresh release is never read again");

    // Due: the page shows what it has, and two pages start one read.
    release.set(Some("v99.0.0"), true, false);
    *tag.lock().unwrap() = Some("server-v99.1.0");
    let page = app.get_page("/updates", &cookie).await.text();
    assert!(
        latest_row(&page).contains("Version 99.0.0 is out"),
        "{page}"
    );
    app.get_page("/updates", &cookie).await;
    read_ended().await;
    assert_eq!(hits.load(SeqCst), 2);
    assert_eq!(release.latest().0.as_deref(), Some("v99.1.0"));

    // A failed read keeps the last good tag.
    release.set(Some("v99.1.0"), true, false);
    *tag.lock().unwrap() = None;
    app.get_page("/updates", &cookie).await;
    read_ended().await;
    assert_eq!(hits.load(SeqCst), 3);
    let page = app.get_page("/updates", &cookie).await.text();
    assert!(
        latest_row(&page).contains("Version 99.1.0 is out"),
        "{page}"
    );
    assert_eq!(hits.load(SeqCst), 3, "a failed read waits before the next");

    // Only a failed read with nothing to show says so.
    release.set(None, false, true);
    let page = app.get_page("/updates", &cookie).await.text();
    assert!(latest_row(&page).contains("Couldn&#39;t check"), "{page}");
}

/// A database as this server makes it (every migration), one admin `from-backup`, with `extra` run on it, all of it in
/// its main file.
async fn made_database(path: &Path, extra: &str) {
    let db = crate::connect_db(&format!("sqlite://{}", path.display())).await;
    sqlx::query("INSERT INTO admin_users (username, password_hash) VALUES ('from-backup', 'x')")
        .execute(&db)
        .await
        .unwrap();
    if !extra.is_empty() {
        sqlx::query(extra).execute(&db).await.unwrap();
    }
    sqlx::query("PRAGMA wal_checkpoint(TRUNCATE)")
        .execute(&db)
        .await
        .unwrap();
    db.close().await;
}

/// A restore takes only a database whose schema is exactly what the migrations make: a trigger, a view, an index or
/// a changed table is refused before anything is swapped - the marker gone, the live database untouched, no copy
/// aside - and the backup check refuses the same file.
#[tokio::test]
async fn a_restore_refuses_a_schema_the_migrations_dont_make() {
    use crate::handlers::backups::{CheckCode, Checked, check_file};
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

    let cases = [
        (
            "trigger",
            "CREATE TRIGGER keep_admins AFTER DELETE ON admin_users BEGIN \
             INSERT INTO admin_users (username, password_hash) VALUES (old.username, old.password_hash); END",
        ),
        (
            "trigger on the migrations table",
            "CREATE TRIGGER on_migrate AFTER INSERT ON _sqlx_migrations BEGIN \
             UPDATE admin_users SET password_hash = 'y'; END",
        ),
        (
            "view",
            "CREATE VIEW every_admin AS SELECT username FROM admin_users",
        ),
        ("altered table", "ALTER TABLE devices ADD COLUMN note TEXT"),
        (
            "index",
            "CREATE INDEX admin_users_by_hash ON admin_users (password_hash)",
        ),
        ("table", "CREATE TABLE notes (body TEXT)"),
    ];
    for (i, (what, extra)) in cases.iter().enumerate() {
        let file = dir.path().join(format!("case-{i}.db"));
        made_database(&file, extra).await;
        let name = format!("backup-20261124-0{i}0000.zip");
        backup_zip(dir.path(), &name, Some(&file));
        assert_eq!(
            check_file(&data, &data.join("backups").join(&name)).await,
            Checked::Failed(CheckCode::Schema),
            "{what}"
        );
        std::fs::write(
            data.join(startup::RESTORE_REQUEST),
            crate::handlers::backups::restore_marker(&name, "parent", "Mamma", chrono::Utc::now()),
        )
        .unwrap();
        let refused = startup::apply_restore_request(&data, &live)
            .await
            .expect_err(what);
        assert_eq!(
            refused.not_vibb,
            Some((name.clone(), Some("parent".to_string()))),
            "{what}"
        );
        assert!(!data.join(startup::RESTORE_REQUEST).exists(), "{what}");
        assert!(!data.join("kidphone.db.restore-tmp").exists(), "{what}");
        assert_eq!(admin_names(&url).await, ["live"], "{what}");
        let scratch_left = std::fs::read_dir(&data)
            .unwrap()
            .filter_map(Result::ok)
            .any(|e| {
                e.file_name()
                    .to_string_lossy()
                    .starts_with(crate::handlers::backups::CHECK_SCRATCH_PREFIX)
            });
        assert!(!scratch_left, "{what}");
    }
    let copies = std::fs::read_dir(data.join("backups"))
        .unwrap()
        .filter_map(Result::ok)
        .any(|e| e.file_name().to_string_lossy().starts_with("prerestore-"));
    assert!(!copies, "a refused restore makes no copy");

    // The same database without anything added restores.
    let file = dir.path().join("plain.db");
    made_database(&file, "").await;
    backup_zip(dir.path(), "backup-20261124-090000.zip", Some(&file));
    std::fs::write(
        data.join(startup::RESTORE_REQUEST),
        "backup-20261124-090000.zip",
    )
    .unwrap();
    startup::apply_restore_request(&data, &live)
        .await
        .unwrap()
        .expect("applied");
    assert_eq!(admin_names(&url).await, ["from-backup"]);
}

/// A backup from the previous migration version (with the session store's table, as a running server has it) passes
/// the check and restores: it is migrated forward.
#[tokio::test]
async fn a_backup_from_the_previous_version_restores() {
    let dir = tempfile::tempdir().unwrap();
    let older = tempfile::tempdir().unwrap();
    let (_, old) = old_database(older.path(), back(1)).await;
    let data = dir.path().join("data");
    std::fs::create_dir_all(&data).unwrap();
    let live = data.join("kidphone.db");
    let url = format!("sqlite://{}", live.display());
    crate::connect_db(&url).await.close().await;
    backup_zip(dir.path(), "backup-20261124-100000.zip", Some(&old));
    assert_eq!(
        crate::handlers::backups::check_file(
            &data,
            &data.join("backups").join("backup-20261124-100000.zip")
        )
        .await,
        crate::handlers::backups::Checked::Ok
    );
    std::fs::write(
        data.join(startup::RESTORE_REQUEST),
        "backup-20261124-100000.zip",
    )
    .unwrap();
    startup::apply_restore_request(&data, &live)
        .await
        .unwrap()
        .expect("applied");
    assert_eq!(admin_names(&url).await, ["old"]);
}
