//! Design 140 (docs/design/140-launcher-needs-server.md): a release of ours that names a newer server than this one -
//! the pages' orange lines, Approve and Keep answering `stale`, the manual upload's refusal, migration 0187. The sync
//! and the start's release are tested in `handlers::tracked_apps::tests`, the rule in `catalog_holds::tests`, the
//! entry in `apk::tests`, the setup QR in `launcher_check::tests`.

use super::*;

/// The throwaway release key of `testdata/apk/*-key4.apk`, as the QR's checksum.
const KEY4_CHECKSUM: &str = "9wK758cEFHgRiaJUfORL-tf8hHZGhA40-0qj3QsJmvc";

async fn launcher_row(app: &TestApp, served: Option<&str>) -> i64 {
    sqlx::query_scalar(
        "INSERT INTO tracked_apps (name, package_name, github_repo, is_launcher, release_tag_prefix, \
         latest_release_tag, latest_release_asset_id) VALUES ('Vibb One', 'me.vibb.launcher', 'o/r', 1, \
         'launcher-v', ?, ?) RETURNING id",
    )
    .bind(served)
    .bind(served.map(|_| 1))
    .fetch_one(&app.db)
    .await
    .unwrap()
}

async fn hold_for_server(app: &TestApp, id: i64) {
    sqlx::query(
        "INSERT INTO tracked_app_held_releases (tracked_app_id, release_tag, asset_id, file_path, sha256, reason, \
         held_at, min_server) VALUES (?, 'launcher-v1.1.0', 2, '/nowhere/l.apk', 'cc', 'server', \
         '2026-11-27 10:00:00', '9.0.0')",
    )
    .bind(id)
    .execute(&app.db)
    .await
    .unwrap();
}

/// The running server's version parses (Software Updates' parser): it is what every release of ours is judged by.
#[test]
fn server_version_is_x_y_z() {
    let version = crate::server_version();
    assert_ne!(version, (0, 0, 0));
    assert_eq!(
        crate::catalog_holds::version_text(version),
        env!("CARGO_PKG_VERSION")
    );
}

/// §4.3: the catalog app's page (its line and (i), no Approve or Keep - also with nothing served yet), the Apps tab's
/// badge and Software Updates' line, in both languages.
#[tokio::test]
async fn the_pages_show_a_release_held_for_the_server() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let id = launcher_row(&app, Some("launcher-v1.0.0")).await;
    hold_for_server(&app, id).await;
    let page = app
        .get_page(&format!("/apps/tracked/{id}"), &cookie)
        .await
        .text();
    let group = &page[page.find("id=\"update\"").expect("the update group")..];
    assert!(group.contains("data-update=\"held-server\""), "{group}");
    assert!(
        group.contains("<span class=\"tone-orange\">1.1.0 waits for server 9.0.0</span>"),
        "{group}"
    );
    assert!(
        group.contains(
            "The phones stay on 1.0.0 until this server runs 9.0.0 or newer; then 1.1.0 goes out by itself."
        ),
        "{group}"
    );
    assert!(!page.contains("/held/approve"));
    assert!(!page.contains("/held/decline"));
    let nb = app
        .get_page(&format!("/apps/tracked/{id}?lang=nb"), &cookie)
        .await
        .text();
    assert!(nb.contains("1.1.0 venter på server 9.0.0"), "{nb}");

    let apps = app.get_page("/apps", &cookie).await.text();
    assert!(apps.contains("Waits for the server"), "{apps}");
    assert!(!apps.contains("Waits for you"));

    let updates = app.get_page("/updates", &cookie).await.text();
    assert!(
        updates.contains(
            "<div class=\"row\" data-row=\"held-for-server\"><span class=\"row-label tone-orange\">\
             Vibb One 1.1.0 waits for server 9.0.0</span></div>"
        ),
        "{updates}"
    );
    let updates = app.get_page("/updates?lang=nb", &cookie).await.text();
    assert!(updates.contains("Vibb One 1.1.0 venter på server 9.0.0"));

    // Nothing served yet (a first release held - the music app's row here): the (i) says no phone gets it.
    let first: i64 = sqlx::query_scalar(
        "INSERT INTO tracked_apps (name, package_name, github_repo, release_tag_prefix) \
         VALUES ('Vibb music', 'me.vibb.music', ?, 'music-v') RETURNING id",
    )
    .bind(&app.state.config.server_release_repo)
    .fetch_one(&app.db)
    .await
    .unwrap();
    hold_for_server(&app, first).await;
    sqlx::query(
        "UPDATE tracked_app_held_releases SET release_tag = 'music-v1.1.0' WHERE tracked_app_id = ?",
    )
    .bind(first)
    .execute(&app.db)
    .await
    .unwrap();
    let page = app
        .get_page(&format!("/apps/tracked/{first}"), &cookie)
        .await
        .text();
    assert!(
        page.contains(
            "No phone gets 1.1.0 until this server runs 9.0.0 or newer; then it goes out by itself."
        ),
        "{page}"
    );
}

/// §4.2: Approve and Keep never take a release held for the server - their POSTs answer `stale`, the hold stays.
#[tokio::test]
async fn approve_and_keep_answer_stale_for_a_server_hold() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let id = launcher_row(&app, Some("launcher-v1.0.0")).await;
    hold_for_server(&app, id).await;
    for answer in ["approve", "decline"] {
        let res = app
            .request_form(
                Method::POST,
                &format!("/apps/tracked/{id}/held/{answer}"),
                Some(&cookie),
                &[("tag", "launcher-v1.1.0"), ("asset_id", "2")],
            )
            .await;
        assert_eq!(
            res.location(),
            Some(format!("/apps/tracked/{id}?held=stale#update").as_str()),
            "{answer}"
        );
        app.assert_lands(&res, &cookie).await;
    }
    let held: i64 = sqlx::query_scalar(
        "SELECT COUNT(*) FROM tracked_app_held_releases WHERE tracked_app_id = ? AND reason = 'server'",
    )
    .bind(id)
    .fetch_one(&app.db)
    .await
    .unwrap();
    assert_eq!(held, 1);
    let declined: Option<String> =
        sqlx::query_scalar("SELECT declined_release_tag FROM tracked_apps WHERE id = ?")
            .bind(id)
            .fetch_one(&app.db)
            .await
            .unwrap();
    assert_eq!(declined, None);
}

/// §4.2 (QA M3): a manual upload of ours that needs a newer server, or whose need can't be read, is refused at once
/// with its flash on the upload group (400, nothing stored); one that needs this server is stored.
#[tokio::test]
async fn an_upload_that_needs_a_newer_server_is_refused() {
    let mut config = crate::config::ForkConfig::for_tests();
    config.launcher_signature_checksum = Some(KEY4_CHECKSUM.to_string());
    let app = TestApp::with_config(config).await;
    let cookie = app.admin_cookie().await;
    let id = launcher_row(&app, None).await;
    // The launcher's row as an uploaded one (the form is a manual row's; the route takes any row).
    sqlx::query("UPDATE tracked_apps SET source_type = 'manual' WHERE id = ?")
        .bind(id)
        .execute(&app.db)
        .await
        .unwrap();
    let (x, y, z) = app.state.server_version;
    let base = crate::tests::catalog_pins::fixture("launcher-key4.apk");
    let with = |text: &str| crate::apk::tests::with_min_server(&base, Some(text.as_bytes()));
    let stored = || async {
        sqlx::query_scalar::<_, Option<String>>(
            "SELECT latest_release_tag FROM tracked_apps WHERE id = ?",
        )
        .bind(id)
        .fetch_one(&app.db)
        .await
        .unwrap()
    };

    let next = format!("{x}.{y}.{}", z + 1);
    let res = crate::tests::catalog_pins::upload(&app, &cookie, id, "1.0.1", &with(&next)).await;
    assert_eq!(res.status, StatusCode::BAD_REQUEST);
    let text = res.text();
    let upload = &text[text.find("id=\"upload\"").expect("the upload group")..];
    assert!(
        upload.contains(&format!("This file needs server {next}")),
        "{upload}"
    );
    assert_eq!(stored().await, None);

    let res =
        crate::tests::catalog_pins::upload(&app, &cookie, id, "1.0.2", &with("00.1.0\n")).await;
    assert_eq!(res.status, StatusCode::BAD_REQUEST);
    assert!(
        res.text()
            .contains("Couldn&#39;t read which server this file needs")
            || res
                .text()
                .contains("Couldn&#x27;t read which server this file needs"),
        "{}",
        res.text()
    );
    assert_eq!(stored().await, None);

    let own = format!("{x}.{y}.{z}");
    let res = crate::tests::catalog_pins::upload(&app, &cookie, id, "1.0.3", &with(&own)).await;
    assert_eq!(
        res.location(),
        Some(format!("/apps/tracked/{id}?notice=uploaded#upload").as_str())
    );
    assert_eq!(stored().await.as_deref(), Some("1.0.3"));
}

/// 0187: the held table keeps its cascade, takes `server` only with `min_server`, and the 0141 reasons as before.
#[tokio::test]
async fn migration_0187_keeps_the_cascade_and_checks_the_reason() {
    let app = TestApp::new().await;
    let id = launcher_row(&app, None).await;
    let insert = |reason: &'static str, min: Option<&'static str>| {
        let db = app.db.clone();
        async move {
            sqlx::query(
                "INSERT INTO tracked_app_held_releases (tracked_app_id, release_tag, asset_id, file_path, sha256, \
                 reason, held_at, min_server) VALUES (?, 'launcher-v1.1.0', 2, '/x.apk', 'cc', ?, \
                 '2026-11-27 10:00:00', ?)",
            )
            .bind(id)
            .bind(reason)
            .bind(min)
            .execute(&db)
            .await
        }
    };
    assert!(insert("server", None).await.is_err());
    assert!(insert("other", None).await.is_err());
    insert("permissions", None).await.unwrap();
    sqlx::query("DELETE FROM tracked_app_held_releases")
        .execute(&app.db)
        .await
        .unwrap();
    insert("server", Some("9.0.0")).await.unwrap();
    sqlx::query("DELETE FROM tracked_apps WHERE id = ?")
        .bind(id)
        .execute(&app.db)
        .await
        .unwrap();
    let left: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM tracked_app_held_releases")
        .fetch_one(&app.db)
        .await
        .unwrap();
    assert_eq!(left, 0, "the cascade kept");
}
