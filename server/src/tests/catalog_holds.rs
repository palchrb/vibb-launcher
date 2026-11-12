//! Design 90 (docs/design/90-catalog-update-holds.md): the held release's Approve and Keep, Ask
//! Again, the wait's field and the switch, the Apps tab's badge, the upload that widens the
//! accepted set, the backfill and migration 0141. The sync's own rules are tested in
//! `handlers::tracked_apps::tests` and `catalog_holds::tests`.

use super::*;
use crate::tests::catalog_pins::{fixture, upload};
use sha2::Digest;

/// The throwaway key the `perms-key3-*` fixtures are signed with (apksigner's digest).
const KEY3: &str = "5f5f8587aebaefa15a7e22637d3feaf83c36834ce98b7314d33eb0a74e0e2857";

/// An F-Droid app (its repo refuses connections: a sync fails at once) serving `perms-key3-v1`
/// as "v1" and holding `perms-key3-v2` as "v2" for asking for Contacts and more; returns its id
/// and the two files.
async fn held_app(app: &TestApp) -> (i64, std::path::PathBuf, std::path::PathBuf) {
    let id: i64 = sqlx::query_scalar(
        "INSERT INTO tracked_apps (name, package_name, source_type, github_repo, fdroid_repo, \
         fdroid_package, signer_sha256, latest_release_tag, latest_release_asset_id, \
         accepted_permissions) VALUES ('Fixture', 'me.vibb.permfixture', 'fdroid', '', \
         'http://127.0.0.1:9/repo', 'me.vibb.permfixture', ?, 'v1', 1, \
         '[\"android.permission.INTERNET\"]') RETURNING id",
    )
    .bind(KEY3)
    .fetch_one(&app.db)
    .await
    .unwrap();
    let dir = app.state.tracked_apps_dir.join(id.to_string());
    std::fs::create_dir_all(&dir).unwrap();
    let (served, held) = (dir.join("v1-1.apk"), dir.join("v2-2.apk"));
    std::fs::write(&served, fixture("perms-key3-v1.apk")).unwrap();
    let v2 = fixture("perms-key3-v2.apk");
    std::fs::write(&held, &v2).unwrap();
    sqlx::query("UPDATE tracked_apps SET latest_release_file_path = ? WHERE id = ?")
        .bind(served.to_str().unwrap())
        .bind(id)
        .execute(&app.db)
        .await
        .unwrap();
    sqlx::query(
        "INSERT INTO tracked_app_held_releases (tracked_app_id, release_tag, asset_id, file_path, \
         asset_name, asset_size, sha256, version_code, permissions, reason, new_permissions, \
         held_at) VALUES (?, 'v2', 2, ?, 'v2.apk', ?, ?, 2, NULL, 'permissions', \
         '[\"android.permission.ACCESS_FINE_LOCATION\",\"android.permission.READ_CONTACTS\"]', \
         '2026-11-12 10:00:00')",
    )
    .bind(id)
    .bind(held.to_str().unwrap())
    .bind(v2.len() as i64)
    .bind(hex::encode(sha2::Sha256::digest(&v2)))
    .execute(&app.db)
    .await
    .unwrap();
    (id, served, held)
}

async fn security_lines(app: &TestApp, kind: &str) -> Vec<Option<String>> {
    sqlx::query_scalar("SELECT params FROM security_events WHERE event_type = ? ORDER BY id")
        .bind(kind)
        .fetch_all(&app.db)
        .await
        .unwrap()
}

/// §7, §17.3 M3: Approve acts only on the exact held release and not while a check runs; then it
/// serves it, adds what it asks for to the accepted set, removes the old file, nudges the phone
/// with the app and writes the Security Log's line - no inbox row.
#[tokio::test]
async fn approve_serves_the_exact_held_release() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, served, held) = held_app(&app).await;
    let (phone, _) = app.enrolled_device("Anna").await;
    sqlx::query("INSERT INTO device_tracked_apps (device_id, tracked_app_id) VALUES (?, ?)")
        .bind(phone)
        .bind(id)
        .execute(&app.db)
        .await
        .unwrap();
    let mut nudges = app.state.command_notify.subscribe();
    let page = format!("/apps/tracked/{id}");
    let html = app.get_page(&page, &cookie).await.text();
    assert!(html.contains("2 waits for you"), "{html}");
    assert!(
        html.contains("Asks for Location and Contacts")
            || html.contains("Asks for Contacts and Location"),
        "{html}"
    );
    assert!(html.contains("Keep 1"), "{html}");
    let apps = app.get_page("/apps", &cookie).await.text();
    assert!(apps.contains("Waits for you"), "the Apps tab's badge");

    let approve = format!("{page}/held/approve");
    let res = app
        .request_form(
            Method::POST,
            &approve,
            Some(&cookie),
            &[("tag", "v1.9"), ("asset_id", "2")],
        )
        .await;
    assert_redirect(&res, &format!("{page}?held=stale"), Some("update"));
    app.assert_lands(&res, &cookie).await;
    let running = app.state.app_syncs.start(id).unwrap();
    let exact = [("tag", "v2"), ("asset_id", "2")];
    let res = app
        .request_form(Method::POST, &approve, Some(&cookie), &exact)
        .await;
    assert_redirect(&res, &format!("{page}?held=busy"), Some("update"));
    drop(running);

    let res = app
        .request_form(Method::POST, &approve, Some(&cookie), &exact)
        .await;
    assert_redirect(&res, &format!("{page}?held=approved"), Some("update"));
    app.assert_lands(&res, &cookie).await;
    let row: (Option<String>, Option<String>, Option<String>, Option<i64>) = sqlx::query_as(
        "SELECT latest_release_tag, latest_release_file_path, accepted_permissions, \
         latest_release_version_code FROM tracked_apps WHERE id = ?",
    )
    .bind(id)
    .fetch_one(&app.db)
    .await
    .unwrap();
    assert_eq!(row.0.as_deref(), Some("v2"));
    assert_eq!(row.1.as_deref(), held.to_str());
    assert_eq!(
        row.2.as_deref(),
        Some(
            r#"["android.permission.ACCESS_FINE_LOCATION","android.permission.INTERNET","android.permission.READ_CONTACTS","bind:android.permission.BIND_ACCESSIBILITY_SERVICE","bind:android.permission.BIND_DEVICE_ADMIN"]"#
        )
    );
    assert_eq!(row.3, Some(2));
    assert!(!served.exists(), "the old file goes");
    let held_rows: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM tracked_app_held_releases")
        .fetch_one(&app.db)
        .await
        .unwrap();
    assert_eq!(held_rows, 0);
    assert_eq!(nudges.try_recv().unwrap(), phone);
    let lines = security_lines(&app, "catalog_release_approved").await;
    assert_eq!(lines.len(), 1);
    assert!(
        lines[0].as_deref().unwrap_or_default().contains("contacts"),
        "{lines:?}"
    );
    let inbox: i64 = sqlx::query_scalar(
        "SELECT COUNT(*) FROM parent_events WHERE kind = 'app_update_available'",
    )
    .fetch_one(&app.db)
    .await
    .unwrap();
    assert_eq!(inbox, 0, "the parent just did it");
    // The served release's row now says what it asks for.
    let html = app.get_page(&page, &cookie).await.text();
    assert!(html.contains(r#"data-row="asks""#), "{html}");
    assert!(html.contains("android.permission.READ_CONTACTS"));
}

/// §17.3 M3: a held release whose checks fail at Approve (the pinned key changed meanwhile) is
/// dropped, never served, and the reason shows as the sync's.
#[tokio::test]
async fn approve_is_refused_when_the_pins_changed() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, served, held) = held_app(&app).await;
    sqlx::query("UPDATE tracked_apps SET signer_sha256 = 'aa' WHERE id = ?")
        .bind(id)
        .execute(&app.db)
        .await
        .unwrap();
    let page = format!("/apps/tracked/{id}");
    let res = app
        .request_form(
            Method::POST,
            &format!("{page}/held/approve"),
            Some(&cookie),
            &[("tag", "v2"), ("asset_id", "2")],
        )
        .await;
    assert_redirect(&res, &format!("{page}?held=refused"), Some("update"));
    app.assert_lands(&res, &cookie).await;
    let tag: Option<String> =
        sqlx::query_scalar("SELECT latest_release_tag FROM tracked_apps WHERE id = ?")
            .bind(id)
            .fetch_one(&app.db)
            .await
            .unwrap();
    assert_eq!(tag.as_deref(), Some("v1"));
    assert!(served.exists() && !held.exists());
    let html = app.get_page(&page, &cookie).await.text();
    assert!(
        html.contains(r#"data-sync="failed""#),
        "the reason as the sync's"
    );
}

/// §7: Keep declines the exact held release (row and file go, the Security Log says so), and Ask
/// Again forgets the declined one and starts a check.
#[tokio::test]
async fn keep_declines_and_ask_again_forgets_it() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, served, held) = held_app(&app).await;
    let page = format!("/apps/tracked/{id}");
    let exact = [("tag", "v2"), ("asset_id", "2")];
    let res = app
        .request_form(
            Method::POST,
            &format!("{page}/held/decline"),
            Some(&cookie),
            &exact,
        )
        .await;
    assert_redirect(&res, &format!("{page}?held=declined"), Some("update"));
    app.assert_lands(&res, &cookie).await;
    let declined: (Option<String>, Option<i64>) = sqlx::query_as(
        "SELECT declined_release_tag, declined_release_asset_id FROM tracked_apps WHERE id = ?",
    )
    .bind(id)
    .fetch_one(&app.db)
    .await
    .unwrap();
    assert_eq!(declined, (Some("v2".to_string()), Some(2)));
    assert!(served.exists() && !held.exists());
    assert_eq!(
        security_lines(&app, "catalog_release_declined").await.len(),
        1
    );
    let html = app.get_page(&page, &cookie).await.text();
    assert!(html.contains("Kept 1 · 2 declined"), "{html}");
    // Answered already: a second Keep is stale.
    let res = app
        .request_form(
            Method::POST,
            &format!("{page}/held/decline"),
            Some(&cookie),
            &exact,
        )
        .await;
    assert_redirect(&res, &format!("{page}?held=stale"), Some("update"));

    let res = app
        .request_form(
            Method::POST,
            &format!("{page}/declined/ask-again"),
            Some(&cookie),
            &exact,
        )
        .await;
    assert_redirect(&res, &format!("{page}?held=asked_again"), Some("update"));
    app.assert_lands(&res, &cookie).await;
    let declined: Option<String> =
        sqlx::query_scalar("SELECT declined_release_tag FROM tracked_apps WHERE id = ?")
            .bind(id)
            .fetch_one(&app.db)
            .await
            .unwrap();
    assert_eq!(declined, None);
}

/// §17.3 M2: the wait takes whole hours from 0 to 336 on the app's page and the Add form; a
/// refusal comes back at its field, focused, with what was typed; a change is a Security Log
/// line; the switch saves; neither is on a manual app's page.
#[tokio::test]
async fn the_wait_takes_whole_hours_and_the_switch_saves() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, _, _) = held_app(&app).await;
    let page = format!("/apps/tracked/{id}");
    let wait = format!("{page}/release-wait");
    for good in ["0", "336", "48"] {
        let res = app
            .request_form(
                Method::POST,
                &wait,
                Some(&cookie),
                &[("release_wait_hours", good)],
            )
            .await;
        assert_redirect(&res, &format!("{page}?open=source"), Some("updates"));
        app.assert_lands(&res, &cookie).await;
        let hours: i64 =
            sqlx::query_scalar("SELECT release_wait_hours FROM tracked_apps WHERE id = ?")
                .bind(id)
                .fetch_one(&app.db)
                .await
                .unwrap();
        assert_eq!(hours.to_string(), good);
    }
    assert_eq!(
        security_lines(&app, "catalog_release_wait_changed")
            .await
            .len(),
        3
    );
    for bad in ["", "-1", "337", "4.5", "x"] {
        let res = app
            .request_form(
                Method::POST,
                &wait,
                Some(&cookie),
                &[("release_wait_hours", bad)],
            )
            .await;
        assert_eq!(res.status, StatusCode::BAD_REQUEST, "{bad}");
        let html = res.text();
        assert!(html.contains(r#"id="release_wait_hours-error""#), "{bad}");
        assert!(html.contains("from 0 to 336"), "{bad}");
        assert!(html.contains(
            r#"aria-invalid="true" aria-describedby="release_wait_hours-error" autofocus"#
        ));
        assert!(
            html.contains(&format!(r#"name="release_wait_hours" value="{bad}""#)),
            "{bad}"
        );
        let source = tags(&html, "details")
            .into_iter()
            .find(|t| t.attr("id") == Some("source"))
            .unwrap();
        assert!(
            source.has("open"),
            "{bad}: the field's group comes back open"
        );
    }

    let res = app
        .request_form(
            Method::POST,
            &format!("{page}/always-ask"),
            Some(&cookie),
            &[("always_ask", "on")],
        )
        .await;
    assert_redirect(&res, &format!("{page}?open=source"), Some("updates"));
    let on: bool = sqlx::query_scalar("SELECT always_ask FROM tracked_apps WHERE id = ?")
        .bind(id)
        .fetch_one(&app.db)
        .await
        .unwrap();
    assert!(on);

    // Add App: 48 untouched for an f-droid.org app; a bad number refused at its field.
    let add = |wait: &'static str| {
        [
            ("name", "Etar"),
            ("source_type", "fdroid"),
            ("fdroid_repo", "https://f-droid.org/repo"),
            ("fdroid_package", "ws.xsoh.etar"),
            ("release_wait_hours", wait),
        ]
    };
    let res = app
        .request_form(Method::POST, "/apps/tracked/new", Some(&cookie), &add("x"))
        .await;
    assert_eq!(res.status, StatusCode::BAD_REQUEST);
    assert!(res.text().contains(r#"id="release_wait_hours-error""#));
    let res = app
        .request_form(Method::POST, "/apps/tracked/new", Some(&cookie), &add("48"))
        .await;
    assert!(res.status.is_redirection());
    // A post without the field (an older form) takes the suggested 48 too.
    let res = app
        .request_form(
            Method::POST,
            "/apps/tracked/new",
            Some(&cookie),
            &[
                ("name", "Older"),
                ("source_type", "github"),
                ("github_repo", "o/older"),
            ],
        )
        .await;
    assert!(res.status.is_redirection());
    let older: i64 =
        sqlx::query_scalar("SELECT release_wait_hours FROM tracked_apps WHERE name = 'Older'")
            .fetch_one(&app.db)
            .await
            .unwrap();
    assert_eq!(older, 48);
    let hours: i64 =
        sqlx::query_scalar("SELECT release_wait_hours FROM tracked_apps WHERE name = 'Etar'")
            .fetch_one(&app.db)
            .await
            .unwrap();
    assert_eq!(hours, 48);

    let manual: i64 = sqlx::query_scalar(
        "INSERT INTO tracked_apps (name, package_name, github_repo, source_type) \
         VALUES ('Notater', '', '', 'manual') RETURNING id",
    )
    .fetch_one(&app.db)
    .await
    .unwrap();
    let html = app
        .get_page(&format!("/apps/tracked/{manual}"), &cookie)
        .await
        .text();
    assert!(!html.contains("release-wait") && !html.contains("always-ask"));
}

/// §5: an upload is served at once and widens the accepted set with what it asks for.
#[tokio::test]
async fn an_upload_widens_the_accepted_set() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let id: i64 = sqlx::query_scalar(
        "INSERT INTO tracked_apps (name, package_name, github_repo, source_type) \
         VALUES ('Notater', '', '', 'manual') RETURNING id",
    )
    .fetch_one(&app.db)
    .await
    .unwrap();
    let res = upload(&app, &cookie, id, "2", &fixture("perms-key3-v2.apk")).await;
    assert!(res.status.is_redirection(), "{}", res.text());
    let (accepted, declared): (Option<String>, Option<String>) = sqlx::query_as(
        "SELECT accepted_permissions, latest_release_permissions FROM tracked_apps WHERE id = ?",
    )
    .bind(id)
    .fetch_one(&app.db)
    .await
    .unwrap();
    assert!(
        accepted
            .unwrap()
            .contains("android.permission.READ_CONTACTS")
    );
    assert!(declared.unwrap().contains("com.example.permission.OWN"));
}

/// §5: the cached release of an app from before 0141 seeds the accepted set at startup; a file
/// whose list can't be read leaves it unknown (its next sensitive release is held).
#[tokio::test]
async fn the_backfill_seeds_the_accepted_set() {
    let app = TestApp::new().await;
    let dir = tempfile::tempdir().unwrap();
    let (good, junk) = (dir.path().join("good.apk"), dir.path().join("junk.apk"));
    std::fs::write(&good, fixture("perms-key3-v2.apk")).unwrap();
    std::fs::write(&junk, b"PK\x03\x04not an apk").unwrap();
    for (name, path) in [("Good", &good), ("Junk", &junk)] {
        sqlx::query(
            "INSERT INTO tracked_apps (name, package_name, github_repo, latest_release_tag, \
             latest_release_file_path, latest_release_sha256, signer_sha256, \
             latest_release_version_code) VALUES (?, '', 'o/r', 'v1', ?, 'x', 'x', 1)",
        )
        .bind(name)
        .bind(path.to_str().unwrap())
        .execute(&app.db)
        .await
        .unwrap();
    }
    crate::handlers::tracked_apps::backfill_release_hashes(app.state.clone()).await;
    let rows: Vec<(String, Option<String>)> = sqlx::query_as(
        "SELECT name, accepted_permissions FROM tracked_apps WHERE name IN ('Good', 'Junk') \
             ORDER BY name",
    )
    .fetch_all(&app.db)
    .await
    .unwrap();
    assert_eq!(rows[0].0, "Good");
    assert!(
        rows[0]
            .1
            .as_deref()
            .is_some_and(|set| set.contains("READ_CONTACTS") && !set.contains("OWN")),
        "{rows:?}"
    );
    assert_eq!(rows[1], ("Junk".to_string(), None));
}

/// Migration 0141 on a copy of a 0140 database keeps every catalog row, each with the 48 hours.
#[tokio::test]
async fn migration_0141_keeps_the_catalog() {
    let dir = tempfile::tempdir().unwrap();
    let options = sqlx::sqlite::SqliteConnectOptions::new()
        .filename(dir.path().join("m.db"))
        .create_if_missing(true)
        .foreign_keys(true);
    let db = sqlx::SqlitePool::connect_with(options).await.unwrap();
    for migration in crate::MIGRATOR.iter().filter(|m| m.version <= 140) {
        sqlx::raw_sql(&migration.sql).execute(&db).await.unwrap();
    }
    sqlx::raw_sql(
        "INSERT INTO tracked_apps (id, name, package_name, github_repo, latest_release_tag) \
         VALUES (104, 'Signal', 'org.thoughtcrime.securesms', 'o/r', 'v7.2.0'); \
         INSERT INTO tracked_apps (id, name, package_name, source_type, github_repo, fdroid_repo, \
         fdroid_package) VALUES (105, 'Etar', 'ws.xsoh.etar', 'fdroid', '', \
         'https://f-droid.org/repo', 'ws.xsoh.etar');",
    )
    .execute(&db)
    .await
    .unwrap();
    let migration = crate::MIGRATOR.iter().find(|m| m.version == 141).unwrap();
    sqlx::raw_sql(&migration.sql).execute(&db).await.unwrap();
    type Row = (i64, String, Option<String>, i64, bool, Option<String>);
    let rows: Vec<Row> = sqlx::query_as(
        "SELECT id, name, latest_release_tag, release_wait_hours, always_ask, \
         accepted_permissions FROM tracked_apps WHERE id IN (104, 105) ORDER BY id",
    )
    .fetch_all(&db)
    .await
    .unwrap();
    assert_eq!(
        rows,
        [
            (
                104,
                "Signal".to_string(),
                Some("v7.2.0".to_string()),
                48,
                false,
                None
            ),
            (105, "Etar".to_string(), None, 48, false, None)
        ]
    );
    let refused = sqlx::query("UPDATE tracked_apps SET release_wait_hours = 337 WHERE id = 104")
        .execute(&db)
        .await;
    assert!(refused.is_err(), "the CHECK holds the wait to 14 days");
}

/// QA L2: a held release no longer ahead of the served one (an upload meanwhile) isn't approved -
/// Keep still answers it.
#[tokio::test]
async fn a_held_release_behind_the_served_one_is_not_approved() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, _, _) = held_app(&app).await;
    sqlx::query("UPDATE tracked_apps SET latest_release_tag = 'v3', latest_release_asset_id = 3 WHERE id = ?")
        .bind(id)
        .execute(&app.db)
        .await
        .unwrap();
    let page = format!("/apps/tracked/{id}");
    let exact = [("tag", "v2"), ("asset_id", "2")];
    let res = app
        .request_form(
            Method::POST,
            &format!("{page}/held/approve"),
            Some(&cookie),
            &exact,
        )
        .await;
    assert_redirect(&res, &format!("{page}?held=stale"), Some("update"));
    let res = app
        .request_form(
            Method::POST,
            &format!("{page}/held/decline"),
            Some(&cookie),
            &exact,
        )
        .await;
    assert_redirect(&res, &format!("{page}?held=declined"), Some("update"));
}

/// The first release waits too (for now, `catalog_holds::FIRST_RELEASE_WAITS`): the Apps tab, the
/// app's page and Add App say when it reaches the phones and that Update Now sends it - never "No
/// release".
#[tokio::test]
async fn a_waiting_first_release_says_when_it_comes() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let id: i64 = sqlx::query_scalar(
        "INSERT INTO tracked_apps (name, package_name, github_repo, waiting_release_tag, \
         waiting_release_asset_id, waiting_release_since) VALUES ('Signal', '', 'o/r', 'v7.3.0', \
         3, strftime('%Y-%m-%dT%H:%M:%SZ', 'now')) RETURNING id",
    )
    .fetch_one(&app.db)
    .await
    .unwrap();
    let apps = app.get_page("/apps", &cookie).await.text();
    assert!(apps.contains("First release reaches the phones"), "{apps}");
    let page = app
        .get_page(&format!("/apps/tracked/{id}"), &cookie)
        .await
        .text();
    assert!(page.contains("First release reaches the phones"), "{page}");
    assert!(page.contains("- or Update Now"));
    assert!(page.contains("/update-now#update"));
    let add = app.get_page("/apps/tracked/new", &cookie).await.text();
    assert!(add.contains("The first release waits too"));
}
