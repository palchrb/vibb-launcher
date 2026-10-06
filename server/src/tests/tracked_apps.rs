//! The app catalog's forms (regex asset filters), the status card's file line, the device API's
//! streamed APK download, and the package-name backfill's nudge.

use super::*;

/// Inserts a GitHub-sourced catalog row and returns its id.
async fn insert_app(app: &TestApp, name: &str, pattern: Option<&str>) -> i64 {
    sqlx::query_scalar(
        "INSERT INTO tracked_apps (name, package_name, github_repo, asset_pattern) \
         VALUES (?, '', 'element-hq/element-x-android', ?) RETURNING id",
    )
    .bind(name)
    .bind(pattern)
    .fetch_one(&app.db)
    .await
    .unwrap()
}

#[tokio::test]
async fn an_invalid_asset_regex_is_refused_on_add_with_the_values_kept() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;

    let refused = app
        .request_form(
            Method::POST,
            "/apps/tracked/new",
            Some(&cookie),
            &[
                ("name", "Element X"),
                ("source_type", "github"),
                (
                    "github_repo",
                    "https://github.com/element-hq/element-x-android",
                ),
                ("asset_pattern", r"^(\d+\.apk"),
                ("include_prereleases", "on"),
            ],
        )
        .await;
    assert_eq!(refused.status, StatusCode::BAD_REQUEST);
    let html = refused.text();
    assert!(html.contains("not a valid regular expression"), "{html}");
    assert!(html.contains("Nothing was saved"));
    assert!(html.contains(r#"value="Element X""#));
    assert!(html.contains(r#"value="https://github.com/element-hq/element-x-android""#));
    assert!(html.contains(r#"value="^(\d+\.apk""#));
    assert!(html.contains(r#"name="include_prereleases" value="on" checked"#));
    // The page posts back to its own path (so the scroll position is restored) and the field
    // with the problem takes the focus, not the name field.
    assert!(html.contains(r#"action="/apps/tracked/new""#));
    assert!(html.contains("placeholder=\"Leave blank to use the first .apk found\" autofocus"));
    assert!(!html.contains("required autofocus"));
    assert!(html.contains("/static/scroll-restore.js"));
    let rows: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM tracked_apps")
        .fetch_one(&app.db)
        .await
        .unwrap();
    assert_eq!(rows, 0);

    // A valid one is saved as entered.
    let saved = app
        .request_form(
            Method::POST,
            "/apps/tracked/new",
            Some(&cookie),
            &[
                ("name", "Element X"),
                ("source_type", "github"),
                ("github_repo", "element-hq/element-x-android"),
                ("asset_pattern", r"^\d+\.apk$"),
            ],
        )
        .await;
    assert!(saved.status.is_redirection(), "{}", saved.text());
    let pattern: Option<String> = sqlx::query_scalar("SELECT asset_pattern FROM tracked_apps")
        .fetch_one(&app.db)
        .await
        .unwrap();
    assert_eq!(pattern.as_deref(), Some(r"^\d+\.apk$"));
}

#[tokio::test]
async fn an_invalid_asset_regex_is_refused_on_edit_with_the_values_kept() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let id = insert_app(&app, "Element X", Some(r"^\d+\.apk$")).await;

    let page = app.get_page(&format!("/apps/tracked/{id}"), &cookie).await;
    let html = page.text();
    assert!(html.contains(
        r"Text the file name must contain, or a regular expression starting with ^ (e.g. ^\d+\.apk$)"
    ));
    assert!(!html.contains("autofocus"));
    // The Details form posts to the page's own path (qa-catalog #3): a refused save comes back
    // at the same path, so scroll-restore keeps the position and later forms redirect right.
    assert!(html.contains(&format!(
        r#"<form method="post" action="/apps/tracked/{id}">"#
    )));

    let refused = app
        .request_form(
            Method::POST,
            &format!("/apps/tracked/{id}"),
            Some(&cookie),
            &[
                ("name", "Element"),
                ("github_repo", "element-hq/element-x-android"),
                ("asset_pattern", "^[0-9"),
            ],
        )
        .await;
    assert_eq!(refused.status, StatusCode::BAD_REQUEST);
    let html = refused.text();
    assert!(html.contains("not a valid regular expression"), "{html}");
    assert!(html.contains(r#"value="Element""#));
    assert!(html.contains(r#"value="^[0-9""#));
    assert!(html.contains("autofocus"));
    assert!(html.contains("/static/scroll-restore.js"));
    let (name, pattern): (String, Option<String>) =
        sqlx::query_as("SELECT name, asset_pattern FROM tracked_apps WHERE id = ?")
            .bind(id)
            .fetch_one(&app.db)
            .await
            .unwrap();
    assert_eq!(
        (name.as_str(), pattern.as_deref()),
        ("Element X", Some(r"^\d+\.apk$"))
    );

    // A plain substring filter (the launcher row's) still saves.
    let saved = app
        .request_form(
            Method::POST,
            &format!("/apps/tracked/{id}"),
            Some(&cookie),
            &[
                ("name", "Element X"),
                ("github_repo", "element-hq/element-x-android"),
                ("asset_pattern", "kids-launcher-mdm.apk"),
            ],
        )
        .await;
    assert_eq!(
        saved.location(),
        Some(format!("/apps/tracked/{id}").as_str())
    );
}

#[tokio::test]
async fn the_status_card_names_the_synced_file() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let synced = insert_app(&app, "Element X", Some(r"^\d+\.apk$")).await;
    sqlx::query(
        "UPDATE tracked_apps SET latest_release_tag = 'v26.09.4', latest_release_asset_id = 14, \
         latest_release_asset_name = '202609040.apk', latest_release_asset_size = 326123456 \
         WHERE id = ?",
    )
    .bind(synced)
    .execute(&app.db)
    .await
    .unwrap();
    let html = app
        .get_page(&format!("/apps/tracked/{synced}"), &cookie)
        .await
        .text();
    assert!(
        html.contains("File: <strong>202609040.apk (326.1 MB)</strong>"),
        "{html}"
    );

    // Synced before the file was recorded: nothing until the next sync.
    let older = insert_app(&app, "Older", None).await;
    sqlx::query("UPDATE tracked_apps SET latest_release_tag = 'v1' WHERE id = ?")
        .bind(older)
        .execute(&app.db)
        .await
        .unwrap();
    let html = app
        .get_page(&format!("/apps/tracked/{older}"), &cookie)
        .await
        .text();
    assert!(html.contains("Current synced release"));
    assert!(!html.contains("File: "));
}

#[tokio::test]
async fn the_cached_apk_is_streamed_with_its_length() {
    let app = TestApp::new().await;
    let (_, token) = app.enrolled_device("phone").await;
    let dir = tempfile::tempdir().unwrap();
    let path = dir.path().join("v1-14.apk");
    // Larger than one 64 KiB chunk.
    let apk: Vec<u8> = b"PK\x03\x04"
        .iter()
        .copied()
        .chain((0..200_000u32).map(|i| (i % 251) as u8))
        .collect();
    std::fs::write(&path, &apk).unwrap();
    let id = insert_app(&app, "Element X", None).await;
    sqlx::query(
        "UPDATE tracked_apps SET latest_release_tag = 'v1', latest_release_file_path = ? \
         WHERE id = ?",
    )
    .bind(path.to_str().unwrap())
    .bind(id)
    .execute(&app.db)
    .await
    .unwrap();

    let uri = format!("/api/devices/apps/{id}/download");
    let response = app.request(Method::GET, &uri, Some(&token), None).await;
    assert_eq!(response.status, StatusCode::OK);
    assert_eq!(
        response.headers[header::CONTENT_TYPE],
        "application/vnd.android.package-archive"
    );
    assert_eq!(
        response.headers[header::CONTENT_LENGTH],
        apk.len().to_string().as_str()
    );
    assert!(response.body == apk);

    // Bearer auth as before; a missing file or an unknown app is a 404.
    let anonymous = app.request(Method::GET, &uri, None, None).await;
    assert_eq!(anonymous.status, StatusCode::UNAUTHORIZED);
    let unknown = app
        .request(
            Method::GET,
            "/api/devices/apps/9999/download",
            Some(&token),
            None,
        )
        .await;
    assert_eq!(unknown.status, StatusCode::NOT_FOUND);
    std::fs::remove_file(&path).unwrap();
    let gone = app.request(Method::GET, &uri, Some(&token), None).await;
    assert_eq!(gone.status, StatusCode::NOT_FOUND);
}

/// A catalog app without a package name is matched to the package that newly appeared in the
/// status report; it is allowlisted and the phone is nudged at once, so it doesn't stay hidden
/// and suspended until the next backstop sync.
#[tokio::test]
async fn a_backfilled_app_is_allowlisted_and_the_phone_nudged() {
    let app = TestApp::new().await;
    let (id, token) = app.enrolled_device("phone").await;
    let tracked = insert_app(&app, "Element X", Some(r"^\d+\.apk$")).await;
    sqlx::query("INSERT INTO device_tracked_apps (device_id, tracked_app_id) VALUES (?, ?)")
        .bind(id)
        .bind(tracked)
        .execute(&app.db)
        .await
        .unwrap();
    let report = |packages: &[&str]| {
        let installed: Vec<serde_json::Value> = packages
            .iter()
            .map(|p| serde_json::json!({ "package_name": p, "label": p }))
            .collect();
        serde_json::json!({
            "lock_reason": "none",
            "kiosk_engaged": true,
            "installed_apps": installed,
        })
    };
    let post = |body: serde_json::Value| {
        let app = &app;
        let token = token.clone();
        async move {
            let res = app
                .request(
                    Method::POST,
                    "/api/devices/status",
                    Some(&token),
                    Some(body),
                )
                .await;
            assert!(res.status.is_success(), "{}", res.text());
        }
    };

    // Two apps on the first report, so nothing is "the one new package" yet.
    post(report(&["org.example.clock", "org.example.notes"])).await;
    let mut nudges = app.state.command_notify.subscribe();
    post(report(&[
        "org.example.clock",
        "org.example.notes",
        "io.element.android.x",
    ]))
    .await;
    assert_eq!(nudges.try_recv().ok(), Some(id));
    assert!(nudges.try_recv().is_err());
    let package: String = sqlx::query_scalar("SELECT package_name FROM tracked_apps WHERE id = ?")
        .bind(tracked)
        .fetch_one(&app.db)
        .await
        .unwrap();
    assert_eq!(package, "io.element.android.x");
    let policy = app
        .request(Method::GET, "/api/devices/policy", Some(&token), None)
        .await
        .json();
    let allowlist = policy["allowlist"].as_array().expect("allowlist").clone();
    assert!(
        allowlist.contains(&serde_json::json!("io.element.android.x")),
        "{allowlist:?}"
    );

    // Nothing new on the next report: no backfill, no nudge.
    post(report(&[
        "org.example.clock",
        "org.example.notes",
        "io.element.android.x",
    ]))
    .await;
    assert!(nudges.try_recv().is_err());
}

/// One sync per app (qa-catalog #1): "Check now" while a sync of the app runs starts no second
/// one and comes back to the app's page (its own path), which says it is already running; a
/// failed sync is shown there afterwards.
#[tokio::test]
async fn check_now_while_a_sync_runs_says_so() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let id = insert_app(&app, "Element X", Some(r"^\d+\.apk$")).await;

    let running = app.state.app_syncs.start(id).expect("no sync runs yet");
    let res = app
        .request_form(
            Method::POST,
            &format!("/apps/tracked/{id}/check"),
            Some(&cookie),
            &[],
        )
        .await;
    assert_eq!(res.location(), Some(format!("/apps/tracked/{id}").as_str()));
    let html = app
        .get_page(&format!("/apps/tracked/{id}"), &cookie)
        .await
        .text();
    assert!(
        html.contains("A check of this app is already running"),
        "{html}"
    );

    running.finish(&Err("GitHub API returned 403 Forbidden".to_string()));
    let html = app
        .get_page(&format!("/apps/tracked/{id}"), &cookie)
        .await
        .text();
    assert!(!html.contains("A check of this app is already running"));
    assert!(
        html.contains("failed: GitHub API returned 403 Forbidden"),
        "{html}"
    );

    let unknown = app
        .request_form(Method::POST, "/apps/tracked/9999/check", Some(&cookie), &[])
        .await;
    assert_eq!(unknown.status, StatusCode::NOT_FOUND);
}

/// A manual upload is stored under the app's directory, the row then names it (and its file
/// name and size), and only then is the old file removed (qa-catalog #7); the answer redirects
/// back to the page. Deleting the app removes its whole directory (#4).
#[tokio::test]
async fn upload_replaces_the_file_after_the_update_and_delete_removes_the_dir() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let id: i64 = sqlx::query_scalar(
        "INSERT INTO tracked_apps (name, package_name, github_repo, source_type) \
         VALUES ('Notes', '', '', 'manual') RETURNING id",
    )
    .fetch_one(&app.db)
    .await
    .unwrap();
    let app_dir = app.state.tracked_apps_dir.join(id.to_string());

    let upload = |label: &'static str, bytes: &'static [u8]| {
        let boundary = "XBOUNDARYX";
        let mut body = Vec::new();
        body.extend_from_slice(
            format!(
                "--{boundary}\r\nContent-Disposition: form-data; name=\"release_label\"\r\n\r\n\
                 {label}\r\n--{boundary}\r\nContent-Disposition: form-data; name=\"apk\"; \
                 filename=\"notes-{label}.apk\"\r\nContent-Type: application/octet-stream\r\n\r\n"
            )
            .as_bytes(),
        );
        body.extend_from_slice(bytes);
        body.extend_from_slice(format!("\r\n--{boundary}--\r\n").as_bytes());
        Request::builder()
            .method(Method::POST)
            .uri(format!("/apps/tracked/{id}/upload"))
            .header(header::COOKIE, cookie.clone())
            .header(
                header::CONTENT_TYPE,
                format!("multipart/form-data; boundary={boundary}"),
            )
            .body(Body::from(body))
            .unwrap()
    };

    let first = app.send(upload("1.0", b"PK\x03\x04first")).await;
    assert_eq!(
        first.location(),
        Some(format!("/apps/tracked/{id}").as_str()),
        "{}",
        first.text()
    );
    let first_path: String =
        sqlx::query_scalar("SELECT latest_release_file_path FROM tracked_apps WHERE id = ?")
            .bind(id)
            .fetch_one(&app.db)
            .await
            .unwrap();
    assert!(std::path::Path::new(&first_path).starts_with(&app_dir));
    assert_eq!(std::fs::read(&first_path).unwrap(), b"PK\x03\x04first");

    let second = app.send(upload("1.1", b"PK\x03\x04second!")).await;
    assert!(second.status.is_redirection(), "{}", second.text());
    let (path, name, size): (String, Option<String>, Option<i64>) = sqlx::query_as(
        "SELECT latest_release_file_path, latest_release_asset_name, latest_release_asset_size \
         FROM tracked_apps WHERE id = ?",
    )
    .bind(id)
    .fetch_one(&app.db)
    .await
    .unwrap();
    assert_eq!((name.as_deref(), size), (Some("notes-1.1.apk"), Some(11)));
    assert!(!std::path::Path::new(&first_path).exists());
    assert_eq!(std::fs::read(&path).unwrap(), b"PK\x03\x04second!");
    let page = app
        .get_page(&format!("/apps/tracked/{id}"), &cookie)
        .await
        .text();
    assert!(
        page.contains("File: <strong>notes-1.1.apk (0.0 MB)</strong>"),
        "{page}"
    );

    // A stray file (e.g. a download finishing after the delete) goes with the directory.
    std::fs::write(app_dir.join("late.apk"), b"PK").unwrap();
    let deleted = app
        .request_form(
            Method::POST,
            &format!("/apps/tracked/{id}/delete"),
            Some(&cookie),
            &[],
        )
        .await;
    assert_eq!(deleted.location(), Some("/apps"));
    assert!(!app_dir.exists());
}
