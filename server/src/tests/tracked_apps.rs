//! The app catalog's forms (regex asset filters), the app page's file row, the device API's
//! streamed APK download, the package-name backfill's nudge, and the Apps tab, the app page and Add
//! App (design 31 step 11) with their redirect table.

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

/// Selects catalog app `tracked` for `device` (the device page's checkbox, without the
/// allowlist side effects).
async fn select_app(app: &TestApp, device: i64, tracked: i64) {
    sqlx::query("INSERT INTO device_tracked_apps (device_id, tracked_app_id) VALUES (?, ?)")
        .bind(device)
        .bind(tracked)
        .execute(&app.db)
        .await
        .unwrap();
}

/// A catalog app with a cached release file (`bytes`) at `<dir>/<name>`; returns its id.
async fn app_with_file(
    app: &TestApp,
    dir: &std::path::Path,
    name: &str,
    bytes: &[u8],
    launcher: bool,
) -> i64 {
    let path = dir.join(name);
    std::fs::write(&path, bytes).unwrap();
    sqlx::query_scalar(
        "INSERT INTO tracked_apps (name, package_name, github_repo, latest_release_tag, \
         latest_release_asset_id, latest_release_file_path, is_launcher) \
         VALUES (?, '', 'owner/repo', 'v1.0', 14, ?, ?) RETURNING id",
    )
    .bind(name)
    .bind(path.to_str().unwrap())
    .bind(launcher)
    .fetch_one(&app.db)
    .await
    .unwrap()
}

/// Sends a GET with extra headers through the router.
async fn get_with(app: &TestApp, uri: &str, token: &str, headers: &[(&str, &str)]) -> TestResponse {
    let mut builder = Request::builder()
        .method(Method::GET)
        .uri(uri)
        .header(header::AUTHORIZATION, format!("Bearer {token}"));
    for (name, value) in headers {
        builder = builder.header(*name, *value);
    }
    app.send(builder.body(Body::empty()).unwrap()).await
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
    assert_eq!(
        input_checked(&html, "include_prereleases", "on"),
        Some(true)
    );
    // The page posts back to its own path (so the scroll position is restored) and the field
    // with the problem takes the focus, not the name field; the error is under it, named by
    // `aria-describedby`, and More Options is open.
    assert_eq!(
        form_action_of(&html, "asset_pattern"),
        Some("/apps/tracked/new")
    );
    let field = tags(&html, "input")
        .into_iter()
        .find(|tag| tag.attr("name") == Some("asset_pattern"))
        .unwrap();
    assert!(field.has("autofocus") && field.attr("aria-invalid") == Some("true"));
    assert_eq!(field.attr("aria-describedby"), Some("asset_pattern-error"));
    assert!(html.contains(r#"id="asset_pattern-error""#));
    assert!(html.contains(r#"id="more" open"#));
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
    let page_path = format!("/apps/tracked/{id}#details");
    assert_eq!(
        form_action_of(&html, "asset_pattern"),
        Some(page_path.as_str())
    );
    assert!(
        !html.contains(r#"id="source" open"#),
        "Source & Signing starts closed"
    );

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
    assert!(
        html.contains(r#"id="source" open"#),
        "the error shows in Source & Signing"
    );
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
        Some(format!("/apps/tracked/{id}?open=source#details").as_str())
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
        html.contains(r#"<span class="row-label">File</span><span class="row-value">202609040.apk (326.1 MB)</span>"#),
        "{html}"
    );
    // The full tag in Source & Signing, the version at the top.
    assert!(html.contains("v26.09.4"));
    assert!(html.contains(r#"<span class="row-value">26.09.4</span>"#));

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
    assert!(html.contains(r#"data-row="release""#));
    assert!(!html.contains(r#"data-row="file""#));
}

#[tokio::test]
async fn the_cached_apk_is_streamed_with_its_length() {
    let app = TestApp::new().await;
    let (device, token) = app.enrolled_device("phone").await;
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
    // Only an app selected for this phone downloads (design 13 QA #4).
    select_app(&app, device, id).await;

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

/// QA of the security wave, Low 4: a phone's report never sets a catalog app's package, whatever
/// installer it claims. For an app whose APK didn't give one, the page offers what the launcher
/// installed on its phones, and the parent confirms; then the phone allows it and is nudged.
#[tokio::test]
async fn only_the_parent_confirms_a_package_a_phone_reports() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
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
            .map(|p| match p.split_once(" from ") {
                Some((p, installer)) => {
                    serde_json::json!({ "package_name": p, "label": p, "installer": installer })
                }
                None => serde_json::json!({ "package_name": p, "label": p }),
            })
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
    let catalog_package = || async {
        sqlx::query_scalar::<_, String>("SELECT package_name FROM tracked_apps WHERE id = ?")
            .bind(tracked)
            .fetch_one(&app.db)
            .await
            .unwrap()
    };

    post(report(&["org.example.clock", "org.example.notes"])).await;
    let mut nudges = app.state.command_notify.subscribe();
    // A fake phone's claims: a system package "from the launcher", and a real-looking new one.
    post(report(&[
        "org.example.clock",
        "com.android.settings from me.vibb.launcher",
        "io.element.android.x from me.vibb.launcher",
    ]))
    .await;
    assert_eq!(
        catalog_package().await,
        "",
        "a report never sets the package"
    );
    assert!(nudges.try_recv().is_err());

    let page = app
        .get_page(&format!("/apps/tracked/{tracked}"), &cookie)
        .await
        .text();
    assert!(
        page.contains(r#"name="package" value="io.element.android.x""#),
        "{page}"
    );
    assert!(!page.contains(r#"value="com.android.settings""#));
    let confirm = |package: &'static str| {
        let app = &app;
        let cookie = cookie.clone();
        async move {
            app.request_form(
                Method::POST,
                &format!("/apps/tracked/{tracked}/confirm-package"),
                Some(&cookie),
                &[("package", package)],
            )
            .await
        }
    };
    let refused = confirm("com.android.settings").await;
    assert_eq!(refused.status, StatusCode::BAD_REQUEST);
    // The refusal is in the Release group, which the form's #release brings into view.
    let refused = refused.text().replace("&#39;", "'");
    let release = &refused[refused.find(r#"id="release""#).unwrap()..];
    assert!(
        release.contains(r#"data-flash="package""#)
            && release.contains("Vibb One didn't install that app"),
        "{release}"
    );
    assert_eq!(catalog_package().await, "");

    let confirmed = confirm("io.element.android.x").await;
    assert_redirect(
        &confirmed,
        &format!("/apps/tracked/{tracked}?notice=package_confirmed"),
        Some("release"),
    );
    assert_eq!(catalog_package().await, "io.element.android.x");
    assert_eq!(nudges.try_recv().ok(), Some(id));
    let policy = app
        .request(Method::GET, "/api/devices/policy", Some(&token), None)
        .await
        .json();
    let allowlist = policy["allowlist"].as_array().expect("allowlist").clone();
    assert!(
        allowlist.contains(&serde_json::json!("io.element.android.x")),
        "{allowlist:?}"
    );
    assert!(!allowlist.contains(&serde_json::json!("com.android.settings")));
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
    assert_eq!(
        res.location(),
        Some(format!("/apps/tracked/{id}#check").as_str())
    );
    let html = app
        .get_page(&format!("/apps/tracked/{id}"), &cookie)
        .await
        .text();
    assert!(html.contains(r#"data-sync="running""#), "{html}");
    assert!(html.contains("Checking now"));

    running.finish(&Err(crate::handlers::tracked_apps::Failure::new(
        crate::handlers::tracked_apps::SyncProblem::GithubError,
        "GitHub API returned 403 Forbidden",
    )));
    let html = app
        .get_page(&format!("/apps/tracked/{id}"), &cookie)
        .await
        .text();
    assert!(!html.contains(r#"data-sync="running""#));
    // A short title on the page, the raw error behind its ⓘ (design 31 §4.4).
    let failed = &html[html.find(r#"data-sync="failed""#).expect("the failure row")..];
    let (summary, panel) = failed.split_once("</summary>").unwrap();
    assert!(summary.contains("GitHub returned an error"), "{summary}");
    assert!(!summary.contains("403"), "the raw error stays behind the ⓘ");
    assert!(
        panel.contains("GitHub API returned 403 Forbidden"),
        "{panel}"
    );
    // The catalog marks it.
    let list = app.get_page("/apps", &cookie).await.text();
    assert!(list.contains("Update failed"), "{list}");

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

    let upload = |label: &'static str, bytes: &[u8]| {
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

    let first_apk = std::fs::read("testdata/apk/fixture-key1.apk").unwrap();
    let second_apk = std::fs::read("testdata/apk/fixture-key1-v2.apk").unwrap();
    let first = app.send(upload("1.0", &first_apk)).await;
    assert_redirect(
        &first,
        &format!("/apps/tracked/{id}?notice=uploaded"),
        Some("upload"),
    );
    let first_path: String =
        sqlx::query_scalar("SELECT latest_release_file_path FROM tracked_apps WHERE id = ?")
            .bind(id)
            .fetch_one(&app.db)
            .await
            .unwrap();
    assert!(std::path::Path::new(&first_path).starts_with(&app_dir));
    assert_eq!(std::fs::read(&first_path).unwrap(), first_apk);

    let second = app.send(upload("1.1", &second_apk)).await;
    assert!(second.status.is_redirection(), "{}", second.text());
    let (path, name, size): (String, Option<String>, Option<i64>) = sqlx::query_as(
        "SELECT latest_release_file_path, latest_release_asset_name, latest_release_asset_size \
         FROM tracked_apps WHERE id = ?",
    )
    .bind(id)
    .fetch_one(&app.db)
    .await
    .unwrap();
    assert_eq!(
        (name.as_deref(), size),
        (Some("notes-1.1.apk"), Some(second_apk.len() as i64))
    );
    // The phones check the whole download against this (design 13 QA #2).
    let sha256: Option<String> =
        sqlx::query_scalar("SELECT latest_release_sha256 FROM tracked_apps WHERE id = ?")
            .bind(id)
            .fetch_one(&app.db)
            .await
            .unwrap();
    let expected = {
        use sha2::Digest;
        hex::encode(sha2::Sha256::digest(&second_apk))
    };
    assert_eq!(sha256, Some(expected));
    assert!(!std::path::Path::new(&first_path).exists());
    assert_eq!(std::fs::read(&path).unwrap(), second_apk);
    let page = app
        .get_page(&format!("/apps/tracked/{id}"), &cookie)
        .await
        .text();
    assert!(
        page.contains(r#"<span class="row-value">notes-1.1.apk (0.0 MB)</span>"#),
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

/// Design 13 §5: the download resumes. 200 with `Content-Length`, a strong `ETag` and the release
/// it belongs to; a `Range` with the first `ETag` as `If-Match` gets 206 and `Content-Range`; a
/// changed file 412; a range past the end 416 with the size.
#[tokio::test]
async fn downloads_resume_with_range_and_if_match() {
    let app = TestApp::new().await;
    let (device, token) = app.enrolled_device("phone").await;
    let dir = tempfile::tempdir().unwrap();
    let apk: Vec<u8> = b"PK\x03\x04"
        .iter()
        .copied()
        .chain((0..150_000u32).map(|i| (i % 251) as u8))
        .collect();
    let id = app_with_file(&app, dir.path(), "element.apk", &apk, false).await;
    select_app(&app, device, id).await;
    let uri = format!("/api/devices/apps/{id}/download");

    let whole = get_with(&app, &uri, &token, &[]).await;
    assert_eq!(whole.status, StatusCode::OK);
    assert_eq!(
        whole.headers[header::CONTENT_LENGTH],
        apk.len().to_string().as_str()
    );
    assert_eq!(
        whole.headers[header::CONTENT_TYPE],
        "application/vnd.android.package-archive"
    );
    assert_eq!(whole.headers["x-release-tag"], "v1.0@14");
    assert_eq!(whole.headers[header::ACCEPT_RANGES], "bytes");
    assert!(whole.body == apk);
    let etag = whole.headers[header::ETAG].to_str().unwrap().to_string();
    assert!(etag.starts_with('"'), "a strong ETag: {etag}");

    let have = 70_000;
    let rest = get_with(
        &app,
        &uri,
        &token,
        &[("range", &format!("bytes={have}-")), ("if-match", &etag)],
    )
    .await;
    assert_eq!(rest.status, StatusCode::PARTIAL_CONTENT);
    assert_eq!(
        rest.headers[header::CONTENT_RANGE],
        format!("bytes {have}-{}/{}", apk.len() - 1, apk.len()).as_str()
    );
    assert_eq!(
        rest.headers[header::CONTENT_LENGTH],
        (apk.len() - have).to_string().as_str()
    );
    assert_eq!(rest.headers["x-release-tag"], "v1.0@14");
    assert!(rest.body == apk[have..]);

    // Everything there already: 416 with the size, so the phone knows it is complete.
    let done = get_with(
        &app,
        &uri,
        &token,
        &[
            ("range", &format!("bytes={}-", apk.len())),
            ("if-match", &etag),
        ],
    )
    .await;
    assert_eq!(done.status, StatusCode::RANGE_NOT_SATISFIABLE);
    assert_eq!(
        done.headers[header::CONTENT_RANGE],
        format!("bytes */{}", apk.len()).as_str()
    );

    // The file changed since the phone started: 412, the phone starts over.
    let stale = get_with(
        &app,
        &uri,
        &token,
        &[("range", "bytes=10-"), ("if-match", "\"0.00000000-1\"")],
    )
    .await;
    assert_eq!(stale.status, StatusCode::PRECONDITION_FAILED);
    assert!(stale.body.is_empty());
}

/// Design 13 QA #4: a device downloads only what its own list advertises - its selected apps and
/// the launcher, enabled and with a release. Anything else is a 404, like an unknown id.
#[tokio::test]
async fn only_this_devices_apps_download() {
    let app = TestApp::new().await;
    let (phone, token) = app.enrolled_device("phone").await;
    let (other, other_token) = app.enrolled_device("other").await;
    let dir = tempfile::tempdir().unwrap();
    let element = app_with_file(&app, dir.path(), "element.apk", b"PK\x03\x04e", false).await;
    let launcher = app_with_file(&app, dir.path(), "launcher.apk", b"PK\x03\x04l", true).await;
    let status = |res: &TestResponse| res.status;

    let uri = format!("/api/devices/apps/{element}/download");
    assert_eq!(
        status(&get_with(&app, &uri, &token, &[]).await),
        StatusCode::NOT_FOUND
    );
    select_app(&app, other, element).await;
    assert_eq!(
        status(&get_with(&app, &uri, &token, &[]).await),
        StatusCode::NOT_FOUND
    );
    assert_eq!(
        status(&get_with(&app, &uri, &other_token, &[]).await),
        StatusCode::OK
    );
    select_app(&app, phone, element).await;
    assert_eq!(
        status(&get_with(&app, &uri, &token, &[]).await),
        StatusCode::OK
    );

    // The launcher is every phone's.
    let launcher_uri = format!("/api/devices/apps/{launcher}/download");
    assert_eq!(
        status(&get_with(&app, &launcher_uri, &token, &[]).await),
        StatusCode::OK
    );

    // Disabled, or no release (yet): neither listed nor served.
    sqlx::query("UPDATE tracked_apps SET enabled = 0 WHERE id = ?")
        .bind(element)
        .execute(&app.db)
        .await
        .unwrap();
    assert_eq!(
        status(&get_with(&app, &uri, &token, &[]).await),
        StatusCode::NOT_FOUND
    );
    sqlx::query("UPDATE tracked_apps SET enabled = 1, latest_release_tag = NULL WHERE id = ?")
        .bind(element)
        .execute(&app.db)
        .await
        .unwrap();
    assert_eq!(
        status(&get_with(&app, &uri, &token, &[]).await),
        StatusCode::NOT_FOUND
    );
    let list = app
        .request(Method::GET, "/api/devices/apps", Some(&token), None)
        .await
        .json();
    let ids: Vec<i64> = list
        .as_array()
        .unwrap()
        .iter()
        .map(|a| a["id"].as_i64().unwrap())
        .collect();
    assert_eq!(ids, [launcher]);
}

/// Design 13 QA #2: `/apps` carries each file's SHA-256 (`null` until known), an upload computes
/// it, and a cached file from before migration 0043 gets it at startup - unless the row moved on.
#[tokio::test]
async fn the_list_carries_each_files_hash() {
    let app = TestApp::new().await;
    let (device, token) = app.enrolled_device("phone").await;
    let dir = tempfile::tempdir().unwrap();
    let bytes = b"PK\x03\x04hash me";
    let id = app_with_file(&app, dir.path(), "notes.apk", bytes, false).await;
    select_app(&app, device, id).await;
    let moved = app_with_file(&app, dir.path(), "moved.apk", b"PK\x03\x04m", false).await;

    let list = app
        .request(Method::GET, "/api/devices/apps", Some(&token), None)
        .await
        .json();
    let entry = &list.as_array().unwrap()[0];
    assert_eq!(entry["release_tag"], "v1.0@14");
    assert!(entry["sha256"].is_null());
    assert_eq!(
        entry["download_url"],
        format!("/api/devices/apps/{id}/download")
    );

    // The second row's file is replaced while it is hashed: its hash is not stored.
    sqlx::query("UPDATE tracked_apps SET latest_release_file_path = '/nowhere.apk' WHERE id = ?")
        .bind(moved)
        .execute(&app.db)
        .await
        .unwrap();
    crate::handlers::tracked_apps::backfill_release_hashes(app.state.clone()).await;
    let list = app
        .request(Method::GET, "/api/devices/apps", Some(&token), None)
        .await
        .json();
    let expected = {
        use sha2::Digest;
        hex::encode(sha2::Sha256::digest(bytes))
    };
    assert_eq!(list.as_array().unwrap()[0]["sha256"], expected.as_str());
    let other: Option<String> =
        sqlx::query_scalar("SELECT latest_release_sha256 FROM tracked_apps WHERE id = ?")
            .bind(moved)
            .fetch_one(&app.db)
            .await
            .unwrap();
    assert_eq!(other, None);
}

/// `X-Release-Tag` keeps visible ASCII and percent-encodes the rest (a manual label may have
/// spaces or letters like "ø") - the launcher encodes the tag it expects the same way.
#[test]
fn the_release_tag_header_is_plain_ascii() {
    use crate::handlers::device_api::release_tag_header;
    assert_eq!(
        release_tag_header("launcher-v0.32.0@99"),
        "launcher-v0.32.0@99"
    );
    assert_eq!(release_tag_header("1.0 beta"), "1.0%20beta");
    assert_eq!(release_tag_header("bl\u{e5}b\u{e6}r"), "bl%C3%A5b%C3%A6r");
    assert_eq!(release_tag_header("100%"), "100%25");
}

/// Design 13 §2: "App updates only on Wi-Fi" - on for a new phone, sent in the policy, switched
/// by its own auto-saving form on Device › Apps, which nudges the phone and comes back to its group.
#[tokio::test]
async fn the_app_update_switch_saves_and_reaches_the_phone() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("phone").await;
    async fn policy(app: &TestApp, token: String) -> serde_json::Value {
        app.request(Method::GET, "/api/devices/policy", Some(&token), None)
            .await
            .json()
    }
    assert_eq!(
        policy(&app, token.clone()).await["app_updates_wifi_only"],
        true
    );
    let page = app
        .get_page(&device_page(id, Section::Apps), &cookie)
        .await
        .text();
    assert!(page.contains(&format!(r#"action="/devices/{id}/app-updates#downloads""#)));
    assert!(page.contains(r#"name="app_updates_wifi_only" value="on" checked"#));

    let mut nudges = app.state.command_notify.subscribe();
    let off = app
        .request_form(
            Method::POST,
            &format!("/devices/{id}/app-updates"),
            Some(&cookie),
            &[],
        )
        .await;
    // Back to its group at the bottom of the page: never the top, with or without JS.
    assert_redirect(&off, &device_page(id, Section::Apps), Some("downloads"));
    assert!(page.contains(r#"<section id="downloads""#));
    assert_eq!(nudges.try_recv().ok(), Some(id));
    assert_eq!(
        policy(&app, token.clone()).await["app_updates_wifi_only"],
        false
    );
    let page = app
        .get_page(&device_page(id, Section::Apps), &cookie)
        .await
        .text();
    assert!(page.contains(r#"name="app_updates_wifi_only""#));
    assert!(!page.contains(r#"name="app_updates_wifi_only" value="on" checked"#));

    let on = app
        .request_form(
            Method::POST,
            &format!("/devices/{id}/app-updates"),
            Some(&cookie),
            &[("app_updates_wifi_only", "on")],
        )
        .await;
    assert!(on.status.is_redirection());
    assert_eq!(policy(&app, token).await["app_updates_wifi_only"], true);

    let unknown = app
        .request_form(
            Method::POST,
            "/devices/9999/app-updates",
            Some(&cookie),
            &[],
        )
        .await;
    assert_eq!(unknown.status, StatusCode::NOT_FOUND);
}

/// Design 13 §8: the phone's `app_downloads` snapshot is stored (known fields only) and Device ›
/// Apps shows it per app - waiting for Wi-Fi since when (local time) and how far, the launcher's
/// grace - while a fresh progress report still wins for the live percentage.
#[tokio::test]
async fn app_downloads_are_stored_and_shown() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("phone").await;
    let dir = tempfile::tempdir().unwrap();
    let element = app_with_file(&app, dir.path(), "Element X", b"PK\x03\x04e", false).await;
    let notes = app_with_file(&app, dir.path(), "Notes", b"PK\x03\x04n", false).await;
    let launcher = app_with_file(&app, dir.path(), "Vibb launcher", b"PK\x03\x04l", true).await;
    select_app(&app, id, element).await;
    select_app(&app, id, notes).await;
    let oct_6 = 1_791_244_800_000i64 + 3_600_000;
    let day = 24 * 3_600_000i64;
    let report = serde_json::json!({
        "lock_reason": "NONE",
        "kiosk_engaged": true,
        "app_downloads": {
            "wifi_only": true,
            "network": "metered",
            "entries": [
                {"tracked_app_id": element, "release_tag": "v1.0@14", "state": "waiting_wifi",
                 "bytes": 120_000_000, "total": 326_000_000, "since_ms": oct_6, "any_network_at_ms": null},
                {"tracked_app_id": notes, "release_tag": "v1.0@14", "state": "downloading",
                 "bytes": 1_000_000, "total": 2_000_000, "since_ms": oct_6, "any_network_at_ms": null},
                {"tracked_app_id": launcher, "release_tag": "launcher-v0.32.0@99", "state": "waiting_wifi",
                 "bytes": 0, "total": null, "since_ms": oct_6, "any_network_at_ms": oct_6 + 3 * day,
                 "note": "dropped"},
            ],
        },
    });
    let res = app
        .request(
            Method::POST,
            "/api/devices/status",
            Some(&token),
            Some(report),
        )
        .await;
    assert!(res.status.is_success(), "{}", res.text());
    let stored: Option<String> = sqlx::query_scalar(
        "SELECT app_downloads_json FROM device_status WHERE device_id = ? ORDER BY id DESC LIMIT 1",
    )
    .bind(id)
    .fetch_one(&app.db)
    .await
    .unwrap();
    let stored = stored.expect("stored");
    assert!(
        stored.contains("waiting_wifi") && !stored.contains("dropped"),
        "{stored}"
    );

    // Notes is mid-download: its fresh progress row wins.
    let progress = serde_json::json!({"tracked_app_id": notes, "percent": 50});
    app.request(
        Method::POST,
        "/api/devices/apps/progress",
        Some(&token),
        Some(progress),
    )
    .await;
    // The report is from yesterday 10:00 UTC, read in Phone Setup's zone (qa-31-s6-code L2).
    sqlx::query("UPDATE provisioning_settings SET time_zone = 'Europe/Oslo'")
        .execute(&app.db)
        .await
        .unwrap();
    sqlx::query(
        "UPDATE device_status SET reported_at = datetime('now', '-1 day', 'start of day', \
         '+10 hours') WHERE device_id = ?",
    )
    .bind(id)
    .execute(&app.db)
    .await
    .unwrap();
    let reported_at = (chrono::Utc::now() - chrono::Duration::days(1))
        .date_naive()
        .and_hms_opt(10, 0, 0)
        .unwrap()
        .and_utc();
    let page = app
        .get_page(&device_page(id, Section::Apps), &cookie)
        .await
        .text();
    assert!(
        page.contains("Waiting for Wi-Fi · since 6 Oct · 120 of 326 MB"),
        "{page}"
    );
    assert!(page.contains("Installing 50%"));
    assert!(
        page.contains(
            "Installed · update 0.32.0 waiting for Wi-Fi · since 6 Oct · any network from 9 Oct"
        ),
        "{page}"
    );
    // The phone's network at its last report, with when: Oslo's clock (never the UTC 10:00), the
    // exact local time in `title`.
    let network = &page[page.find(r#"data-row="network""#).expect("the network row")..];
    let network = &network[..network.find("</details>").unwrap()];
    let oslo = crate::display_time::Zone::from_setting("Europe/Oslo");
    let when = oslo.when(reported_at, chrono::Utc::now());
    assert!(!when.contains("10:00"), "{when}");
    assert!(
        network.contains(&format!("Mobile data · {when}</span>")),
        "{network}"
    );
    assert!(
        network.contains(&format!("title=\"{}\"", oslo.exact(reported_at))),
        "{network}"
    );
    assert!(
        network.contains("At the phone&#39;s last report."),
        "{network}"
    );
    // Notes installs: the page reloads itself for the percentage.
    assert!(page.contains("/static/auto-reload.js"));
}

/// Security review M10 (the phone side): a release the launcher refused to install - another
/// package, another key, unreadable, or no pins on the server - comes back in `app_downloads` with
/// the reason, and Device › Apps says why, styled as failed, also over a fresh "Install failed".
#[tokio::test]
async fn a_refused_release_says_why_on_the_device_page() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("phone").await;
    let dir = tempfile::tempdir().unwrap();
    let element = app_with_file(&app, dir.path(), "Element X", b"PK\x03\x04e", false).await;
    let notes = app_with_file(&app, dir.path(), "Notes", b"PK\x03\x04n", false).await;
    sqlx::query("UPDATE tracked_apps SET package_name = 'org.example.notes' WHERE id = ?")
        .bind(notes)
        .execute(&app.db)
        .await
        .unwrap();
    select_app(&app, id, element).await;
    select_app(&app, id, notes).await;
    let report = serde_json::json!({
        "lock_reason": "NONE",
        "kiosk_engaged": true,
        "installed_apps": [{"package_name": "org.example.notes", "label": "Notes", "preinstalled": false,
                            "installer": "me.vibb.launcher"}],
        "app_downloads": {
            "wifi_only": false,
            "network": "unmetered",
            "entries": [
                {"tracked_app_id": element, "release_tag": "v1.0@14", "state": "refused_signer",
                 "bytes": 0, "total": null, "since_ms": 1_791_244_800_000i64, "any_network_at_ms": null},
                {"tracked_app_id": notes, "release_tag": "v1.0@14", "state": "refused_package",
                 "bytes": 0, "total": null, "since_ms": 1_791_244_800_000i64, "any_network_at_ms": null},
            ],
        },
    });
    let res = app
        .request(
            Method::POST,
            "/api/devices/status",
            Some(&token),
            Some(report),
        )
        .await;
    assert!(res.status.is_success(), "{}", res.text());
    // The phone also said "failed" for Element X a moment ago.
    app.request(
        Method::POST,
        "/api/devices/apps/progress",
        Some(&token),
        Some(serde_json::json!({"tracked_app_id": element, "percent": 0, "failed": true})),
    )
    .await;
    let page = app
        .get_page(&device_page(id, Section::Apps), &cookie)
        .await
        .text();
    let refused = "Refused: signed with another key";
    let at = page.find(refused).unwrap_or_else(|| panic!("{page}"));
    assert!(
        page[..at].ends_with("data-failed=\"true\">"),
        "styled as failed: {}",
        &page[at.saturating_sub(80)..at]
    );
    assert!(!page.contains("Install failed"), "the reason wins: {page}");
    let update = "Installed · update refused: it is another app";
    let at = page.find(update).unwrap_or_else(|| panic!("{page}"));
    assert!(page[..at].ends_with("data-failed=\"true\">"));
}

/// Unticking an installed app queues its silent uninstall; ticking it again before the phone syncs
/// must cancel that (the user's emulator: "Music was deleted by your administrator").
#[tokio::test]
async fn ticking_an_app_again_cancels_its_uninstall() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (device, token) = app.enrolled_device("phone").await;
    let res = app
        .request(
            Method::POST,
            "/api/devices/status",
            Some(&token),
            Some(serde_json::json!({
                "lock_reason": "NONE", "kiosk_engaged": true,
                "installed_apps": [{"package_name": "me.vibb.music", "label": "Music", "preinstalled": false,
                                    "installer": "me.vibb.launcher"}],
            })),
        )
        .await;
    assert!(res.status.is_success());
    let toggle = |selected: bool| {
        let mut fields = vec![("package_name", "me.vibb.music")];
        if selected {
            fields.push(("selected", "on"));
        }
        fields
    };
    for selected in [false, true] {
        let res = app
            .request_form(
                Method::POST,
                &format!("/devices/{device}/apps/toggle"),
                Some(&cookie),
                &toggle(selected),
            )
            .await;
        assert!(res.status.is_redirection(), "{}", res.status);
    }
    let policy = app
        .request(Method::GET, "/api/devices/policy", Some(&token), None)
        .await
        .json();
    assert_eq!(policy["packages_to_uninstall"], serde_json::json!([]));
    assert!(
        policy["allowlist"]
            .as_array()
            .unwrap()
            .contains(&serde_json::json!("me.vibb.music"))
    );
    // Unticked and left: it is uninstalled.
    app.request_form(
        Method::POST,
        &format!("/devices/{device}/apps/toggle"),
        Some(&cookie),
        &toggle(false),
    )
    .await;
    let policy = app
        .request(Method::GET, "/api/devices/policy", Some(&token), None)
        .await
        .json();
    assert_eq!(
        policy["packages_to_uninstall"],
        serde_json::json!(["me.vibb.music"])
    );
}

// ------------------------------------------------------------------------------------------------
// Design 31 step 11: the Apps tab, the app page and Add App.
// ------------------------------------------------------------------------------------------------

/// A catalog row with the columns the Apps tab shows; returns its id.
async fn catalog_row(
    app: &TestApp,
    name: &str,
    package: &str,
    tag: Option<&str>,
    prefix: Option<&str>,
) -> i64 {
    sqlx::query_scalar(
        "INSERT INTO tracked_apps (name, package_name, github_repo, latest_release_tag, \
         release_tag_prefix) VALUES (?, ?, 'owner/repo', ?, ?) RETURNING id",
    )
    .bind(name)
    .bind(package)
    .bind(tag)
    .bind(prefix)
    .fetch_one(&app.db)
    .await
    .unwrap()
}

/// The `<a ...>` row with `marker` (e.g. `data-app="3"`), up to its `</a>`.
fn row_of<'a>(html: &'a str, marker: &str) -> &'a str {
    let at = html.find(marker).unwrap_or_else(|| panic!("no {marker}"));
    let start = html[..at].rfind("<a ").unwrap();
    let end = at + html[at..].find("</a>").unwrap();
    &html[start..end]
}

/// The Apps tab (§3.3): a row per phone with how many apps it allows - the same count as the
/// ticks on its page -, then the catalog with the name phones show, the version, how many phones
/// have it and the badges, the launcher first, the footer and Add App.
#[tokio::test]
async fn the_apps_tab_lists_the_phones_and_the_catalog() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (anna, token) = app.enrolled_device("Anna").await;
    let (emil, _) = app.create_device("Emil").await;
    let launcher = catalog_row(
        &app,
        "Vibb One",
        "me.vibb.launcher",
        Some("launcher-v0.32.0"),
        Some("launcher-v"),
    )
    .await;
    sqlx::query("UPDATE tracked_apps SET is_launcher = 1 WHERE id = ?")
        .bind(launcher)
        .execute(&app.db)
        .await
        .unwrap();
    let element = catalog_row(
        &app,
        "Element X",
        "io.element.android.x",
        Some("v26.10.1"),
        None,
    )
    .await;
    sqlx::query(
        "UPDATE tracked_apps SET display_label = 'Chat', display_icon = 'chat', \
         display_color = 'peach' WHERE id = ?",
    )
    .bind(element)
    .execute(&app.db)
    .await
    .unwrap();
    let notes = catalog_row(&app, "Notes", "", None, None).await;
    let maps = catalog_row(&app, "Maps", "org.maps", Some("2.0"), None).await;
    sqlx::query("UPDATE tracked_apps SET enabled = 0 WHERE id = ?")
        .bind(maps)
        .execute(&app.db)
        .await
        .unwrap();
    select_app(&app, anna, element).await;

    // The first report allowlists the safe defaults it has (clock, calculator), not Firefox.
    let installed: Vec<serde_json::Value> = [
        "com.google.android.deskclock",
        "com.google.android.calculator",
        "org.mozilla.firefox",
    ]
    .iter()
    .map(|p| serde_json::json!({ "package_name": p, "label": p }))
    .collect();
    let res = app
        .request(
            Method::POST,
            "/api/devices/status",
            Some(&token),
            Some(serde_json::json!({
                "lock_reason": "none", "kiosk_engaged": true, "installed_apps": installed,
            })),
        )
        .await;
    assert!(res.status.is_success());
    // Play's core, allowed and reported, is never an app row (qa-31-s11-code L6).
    sqlx::query(
        "UPDATE device_policy SET allowlist_json = '[\"com.google.android.deskclock\", \
         \"com.google.android.calculator\", \"com.google.android.gms\"]' WHERE device_id = ?",
    )
    .bind(anna)
    .execute(&app.db)
    .await
    .unwrap();
    let installed: Vec<serde_json::Value> = [
        "com.google.android.deskclock",
        "com.google.android.calculator",
        "org.mozilla.firefox",
        "com.google.android.gms",
    ]
    .iter()
    .map(|p| serde_json::json!({ "package_name": p, "label": p }))
    .collect();
    let res = app
        .request(
            Method::POST,
            "/api/devices/status",
            Some(&token),
            Some(serde_json::json!({
                "lock_reason": "none", "kiosk_engaged": true, "installed_apps": installed,
            })),
        )
        .await;
    assert!(res.status.is_success());

    let html = app.get_page("/apps", &cookie).await.text();
    assert_eq!(nav_current(&html), "apps");
    // Anna allows the clock and the calculator, and Element X waits to be installed: as many as
    // her Apps page switches on, and her device page's Apps row says the same.
    let device = app
        .get_page(&device_page(anna, Section::Apps), &cookie)
        .await
        .text();
    let ticked = tags(&device, "input")
        .into_iter()
        .filter(|tag| tag.attr("name") == Some("selected") && tag.has("checked"))
        .count();
    assert_eq!(ticked, 3);
    let summary = app
        .get_page(&device_page(anna, Section::Summary), &cookie)
        .await
        .text();
    assert!(
        row_of(&summary, r#"data-row="apps""#).contains(">3 allowed<"),
        "{summary}"
    );
    let anna_row = row_of(&html, &format!(r#"data-phone="{anna}""#));
    assert!(
        anna_row.contains(&format!(r#"href="/devices/{anna}/apps?from=apps""#)),
        "{anna_row}"
    );
    assert!(anna_row.contains(">3 allowed<"), "{anna_row}");
    let emil_row = row_of(&html, &format!(r#"data-phone="{emil}""#));
    assert!(emil_row.contains(">Not set up<"), "{emil_row}");

    // The catalog: the launcher first, then by the name the phones show.
    let at = |id: i64| html.find(&format!(r#"data-app="{id}""#)).unwrap();
    assert!(at(launcher) < at(element) && at(element) < at(maps) && at(maps) < at(notes));
    let row = row_of(&html, &format!(r#"data-app="{launcher}""#));
    assert!(
        row.contains("Launcher · all phones") && row.contains(">0.32.0<"),
        "{row}"
    );
    let row = row_of(&html, &format!(r#"data-app="{element}""#));
    assert!(
        row.contains(">Chat<") && row.contains("Element X · 1 phone"),
        "{row}"
    );
    assert!(
        row.contains("/static/app-icons/chat.svg") && row.contains(">26.10.1<"),
        "{row}"
    );
    let row = row_of(&html, &format!(r#"data-app="{notes}""#));
    assert!(
        row.contains("No phones") && row.contains("No release yet"),
        "{row}"
    );
    let row = row_of(&html, &format!(r#"data-app="{maps}""#));
    assert!(
        row.contains("data-badge>Off<") && !row.contains("2.0"),
        "{row}"
    );
    assert!(html.contains("Apps here are kept up to date on every phone that has them."));
    assert!(html.contains(r#"href="/apps/tracked/new""#) && html.contains("Add App"));
}

/// The app page (§3.3): its own header (no brand bar), Check Now, Show on phones as, the phones
/// that have it (to their row on the phone's page), Source & Signing with the switches, the
/// Forget… and Remove App… confirms.
#[tokio::test]
async fn the_app_page_shows_its_phones_and_keeps_every_control() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (anna, _) = app.enrolled_device("Anna").await;
    let (emil, _) = app.enrolled_device("Emil").await;
    let element = catalog_row(
        &app,
        "Element X",
        "io.element.android.x",
        Some("v26.10.1"),
        None,
    )
    .await;
    select_app(&app, anna, element).await;
    let html = app
        .get_page(&format!("/apps/tracked/{element}"), &cookie)
        .await
        .text();
    assert_eq!(nav_current(&html), "apps");
    assert!(
        !html.contains(r#"class="app-header""#),
        "a sub-page has no brand bar"
    );
    assert!(html.contains(r#"class="nav-back" href="/apps""#));
    assert!(html.contains(&format!(r#"action="/apps/tracked/{element}/check#check""#)));
    let phone = row_of(&html, &format!(r#"data-phone="{anna}""#));
    assert!(
        phone.contains(&format!(
            r#"href="/devices/{anna}/apps?from=app:{element}#app-io.element.android.x""#
        )),
        "{phone}"
    );
    assert!(!html.contains(&format!(r#"data-phone="{emil}""#)));
    for (name, route) in [
        ("include_prereleases", "include-prereleases"),
        ("enabled", "enabled"),
        ("is_launcher", "is-launcher"),
    ] {
        assert!(autosaves(&html, name), "{name}");
        let action = format!("/apps/tracked/{element}/{route}#updates");
        assert_eq!(form_action_of(&html, name), Some(action.as_str()));
    }
    assert_eq!(input_checked(&html, "enabled", "on"), Some(true));
    // Forget and Remove sit behind <details> confirms (M6), so nothing happens in one tap: each
    // form is inside its confirm, before the confirm's </details> (qa-31-s11-code L4).
    for (id, route, button) in [
        ("forget", "forget-package-name", "Forget Package and Key"),
        ("remove", "delete", "Remove App"),
    ] {
        let at = html.find(&format!(r#"id="{id}""#)).unwrap();
        let opened = html[..at].rfind("<details").unwrap();
        assert!(
            html[opened..].starts_with(r#"<details class="confirm"#),
            "{id}"
        );
        let confirm = &html[at..at + html[at..].find("</details>").unwrap()];
        let form = confirm
            .find(&format!(r#"action="/apps/tracked/{element}/{route}"#))
            .unwrap_or_else(|| panic!("{id}: the form is outside its confirm"));
        assert!(confirm.find("</summary>").unwrap() < form, "{id}");
        assert!(confirm.contains(button), "{id}");
    }

    // Nobody has it: says so. The launcher: every phone set up, and no Remove.
    let none = catalog_row(&app, "Notes", "", None, None).await;
    let html = app
        .get_page(&format!("/apps/tracked/{none}"), &cookie)
        .await
        .text();
    assert!(html.contains(r#"data-row="no-phones""#));
    assert!(
        !html.contains(r#"id="forget""#),
        "no pins, nothing to forget"
    );
    sqlx::query("UPDATE tracked_apps SET is_launcher = 1 WHERE id = ?")
        .bind(none)
        .execute(&app.db)
        .await
        .unwrap();
    let html = app
        .get_page(&format!("/apps/tracked/{none}"), &cookie)
        .await
        .text();
    for phone in [anna, emil] {
        let row = row_of(&html, &format!(r#"data-phone="{phone}""#));
        assert!(
            row.contains(&format!(r#"href="/devices/{phone}/apps?from=app:{none}""#)),
            "{row}"
        );
    }
    assert!(!html.contains(r#"id="remove""#));

    // A manual app: Upload, no Check Now and no pre-releases.
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
    assert_eq!(
        form_action_of(&html, "release_label"),
        Some(format!("/apps/tracked/{manual}/upload#upload").as_str())
    );
    assert!(!html.contains("/check\""));
    assert!(!html.contains(r#"name="include_prereleases""#));
}

/// Add App (§3.3): name, source and repo; the filter, tag prefix, package and pre-releases under
/// More Options, closed until one is used. The source is two real radios ahead of the rows (CSS
/// hides the GitHub fields for uploads, no script).
#[tokio::test]
async fn add_app_keeps_the_extras_under_more_options() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let html = app.get_page("/apps/tracked/new", &cookie).await.text();
    assert_eq!(nav_current(&html), "apps");
    assert!(!html.contains(r#"class="app-header""#));
    assert!(
        html.contains(r#"<details class="section" id="more">"#),
        "closed"
    );
    assert_eq!(input_checked(&html, "source_type", "github"), Some(true));
    assert_eq!(input_checked(&html, "source_type", "manual"), Some(false));
    let radios = html.find(r#"id="source-manual""#).unwrap();
    assert!(radios < html.find(r#"class="github-only""#).unwrap());
    let more = html.find(r#"id="more""#).unwrap();
    for field in [
        "asset_pattern",
        "release_tag_prefix",
        "package_name",
        "include_prereleases",
    ] {
        assert!(
            html.find(&format!(r#"name="{field}""#)).unwrap() > more,
            "{field}"
        );
        assert_eq!(form_action_of(&html, field), Some("/apps/tracked/new"));
    }
    assert!(!html.contains("onchange"), "no script decides what shows");
}

/// §5.1's redirect table for the Apps pages: every form the app pages render posts back to its
/// own group on its page - the fragment its action carries too - and a form in a `<details>`
/// (Show on phones as, Source & Signing) comes back with it open (`?open=`), so a save never
/// jumps or folds up (§5.1.4, the browser pass of 2026-10-14, 6f); Remove goes to the catalog, Add
/// to the new app's page. Built from the rendered actions: a new form without a row here fails.
#[tokio::test]
async fn every_form_on_the_app_pages_returns_to_its_page() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let github = catalog_row(&app, "Element X", "io.element.android.x", Some("v1"), None).await;
    sqlx::query("UPDATE tracked_apps SET signer_sha256 = 'aa' WHERE id = ?")
        .bind(github)
        .execute(&app.db)
        .await
        .unwrap();
    let manual: i64 = sqlx::query_scalar(
        "INSERT INTO tracked_apps (name, package_name, github_repo, source_type) \
         VALUES ('Notater', '', '', 'manual') RETURNING id",
    )
    .fetch_one(&app.db)
    .await
    .unwrap();
    // A check that is already running answers at once (no GitHub in tests).
    let _running = app.state.app_syncs.start(github).unwrap();
    let apk = std::fs::read("testdata/apk/fixture-key1.apk").unwrap();
    // A phone has the manual app ticked and Vibb installed its package: the page offers it to
    // confirm (the fixture's own package, so the upload after it still passes the pins).
    let (phone, token) = app.enrolled_device("phone").await;
    select_app(&app, phone, manual).await;
    let res = app
        .request(
            Method::POST,
            "/api/devices/status",
            Some(&token),
            Some(serde_json::json!({
                "lock_reason": "none",
                "kiosk_engaged": true,
                "installed_apps": [{
                    "package_name": "me.vibb.testfixture",
                    "label": "Fixture",
                    "installer": "me.vibb.launcher",
                }],
            })),
        )
        .await;
    assert!(res.status.is_success());
    let mut seen = std::collections::HashSet::new();

    for id in [github, manual] {
        let page = format!("/apps/tracked/{id}");
        let html = app.get_page(&page, &cookie).await.text();
        let actions: Vec<String> = tags(&html, "form")
            .into_iter()
            .filter(|form| form.attr("method") == Some("post"))
            .map(|form| form.attr("action").unwrap().to_string())
            .collect();
        assert!(actions.len() >= 6, "{actions:?}");
        for action in actions {
            // A browser posts without the fragment and keeps it for the answer: Upload and the
            // package confirmation land on their group, with the result in it (M1).
            let (target, fragment) = match action.split_once('#') {
                Some((target, fragment)) => (target.to_string(), Some(fragment)),
                None => (action.clone(), None),
            };
            let route = target
                .strip_prefix(&page)
                .unwrap_or_else(|| panic!("{action}"));
            seen.insert(route.to_string());
            // (the action's fragment = the group, the `?open=` the page comes back with)
            let (group, open) = match route {
                "" => (Some("details"), Some("source")),
                "/display" => (Some("display"), Some("display")),
                "/enabled" | "/include-prereleases" | "/is-launcher" => {
                    (Some("updates"), Some("source"))
                }
                "/forget-package-name" => (Some("release"), Some("source")),
                "/upload" => (Some("upload"), None),
                "/confirm-package" => (Some("release"), None),
                "/check" => (Some("check"), None),
                "/delete" => (None, None),
                other => panic!("a form without a row in the redirect table: {other}"),
            };
            assert_eq!(fragment, group, "{action}");
            let fields: &[(&str, &str)] = match route {
                "" => &[("name", "Renamed"), ("github_repo", "owner/repo")],
                "/display" => &[("label", "Chat"), ("icon", "chat"), ("color", "peach")],
                "/enabled" => &[("enabled", "on")],
                "/include-prereleases" => &[("include_prereleases", "on")],
                "/confirm-package" => &[("package", "me.vibb.testfixture")],
                _ => &[],
            };
            let res = if route == "/upload" {
                let boundary = "XBOUNDARYX";
                let mut body = format!(
                    "--{boundary}\r\nContent-Disposition: form-data; name=\"release_label\"\r\n\r\n\
                     1.0\r\n--{boundary}\r\nContent-Disposition: form-data; name=\"apk\"; \
                     filename=\"a.apk\"\r\nContent-Type: application/octet-stream\r\n\r\n"
                )
                .into_bytes();
                body.extend_from_slice(&apk);
                body.extend_from_slice(format!("\r\n--{boundary}--\r\n").as_bytes());
                app.send(
                    Request::builder()
                        .method(Method::POST)
                        .uri(&target)
                        .header(header::COOKIE, &cookie)
                        .header(
                            header::CONTENT_TYPE,
                            format!("multipart/form-data; boundary={boundary}"),
                        )
                        .body(Body::from(body))
                        .unwrap(),
                )
                .await
            } else {
                app.request_form(Method::POST, &target, Some(&cookie), fields)
                    .await
            };
            match (route, open) {
                ("/delete", _) => assert_redirect(&res, "/apps", None),
                ("/upload", _) => {
                    assert_redirect(&res, &format!("{page}?notice=uploaded"), Some("upload"))
                }
                ("/confirm-package", _) => assert_redirect(
                    &res,
                    &format!("{page}?notice=package_confirmed"),
                    Some("release"),
                ),
                (_, Some(open)) => {
                    assert_redirect(&res, &format!("{page}?open={open}"), group);
                    // The page comes back with that <details> open.
                    let back = app.get_page(res.location().unwrap(), &cookie).await.text();
                    let tag = tags(&back, "details")
                        .into_iter()
                        .find(|t| t.attr("id") == Some(open))
                        .unwrap_or_else(|| panic!("{action}: no #{open}"));
                    assert!(tag.has("open"), "{action}: #{open} comes back closed");
                }
                (_, None) => assert_redirect(&res, &page, group),
            }
            app.assert_lands(&res, &cookie).await;
        }
    }
    for route in [
        "/upload",
        "/confirm-package",
        "/forget-package-name",
        "/check",
    ] {
        assert!(seen.contains(route), "{route} was never rendered");
    }

    let add = app.get_page("/apps/tracked/new", &cookie).await.text();
    assert_eq!(form_action_of(&add, "name"), Some("/apps/tracked/new"));
    let res = app
        .request_form(
            Method::POST,
            "/apps/tracked/new",
            Some(&cookie),
            &[("name", "Tailscale"), ("source_type", "manual")],
        )
        .await;
    let id: i64 = sqlx::query_scalar("SELECT id FROM tracked_apps WHERE name = 'Tailscale'")
        .fetch_one(&app.db)
        .await
        .unwrap();
    assert_redirect(&res, &format!("/apps/tracked/{id}"), None);
}

/// qa-31-s11-code M1: a refused or done upload and package confirmation show their result in
/// their own group, in an open Source & Signing, and the forms' fragments bring that group into
/// view (the redirect table pins the actions and the success redirects).
#[tokio::test]
async fn a_form_result_shows_in_its_group_where_the_parent_was() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let id: i64 = sqlx::query_scalar(
        "INSERT INTO tracked_apps (name, package_name, github_repo, source_type) \
         VALUES ('Notater', '', '', 'manual') RETURNING id",
    )
    .fetch_one(&app.db)
    .await
    .unwrap();
    let page = format!("/apps/tracked/{id}");
    let html = app.get_page(&page, &cookie).await.text();
    assert_eq!(
        form_action_of(&html, "release_label"),
        Some(format!("{page}/upload#upload").as_str())
    );
    assert!(!html.contains(r#"id="source" open"#));
    assert!(!html.contains("data-flash"));

    // A refused upload: the red title and its reason in the Upload group, Source & Signing open.
    let boundary = "XBOUNDARYX";
    let mut body = format!(
        "--{boundary}\r\nContent-Disposition: form-data; name=\"release_label\"\r\n\r\n1.0\r\n\
         --{boundary}\r\nContent-Disposition: form-data; name=\"apk\"; filename=\"a.apk\"\r\n\
         Content-Type: application/octet-stream\r\n\r\n"
    )
    .into_bytes();
    body.extend_from_slice(b"PK\x03\x04not a zip");
    body.extend_from_slice(format!("\r\n--{boundary}--\r\n").as_bytes());
    let refused = app
        .send(
            Request::builder()
                .method(Method::POST)
                .uri(format!("{page}/upload"))
                .header(header::COOKIE, &cookie)
                .header(
                    header::CONTENT_TYPE,
                    format!("multipart/form-data; boundary={boundary}"),
                )
                .body(Body::from(body))
                .unwrap(),
        )
        .await
        .text()
        .replace("&#39;", "'");
    assert!(refused.contains(r#"id="source" open"#));
    let upload = &refused[refused.find(r#"id="upload""#).unwrap()..];
    let upload = &upload[..upload.find("</form>").unwrap()];
    assert!(upload.contains(r#"data-flash="upload""#), "{upload}");
    assert!(upload.contains("Couldn't read the APK") && upload.contains("text-alarm"));
    assert!(upload.contains(r#"aria-label="More about the refused upload""#));

    // Done: the redirect's page says so, green, in the same group.
    for (notice, group, text) in [
        ("uploaded", r#"id="upload""#, "New version uploaded"),
        ("package_confirmed", r#"id="release""#, "Package confirmed"),
    ] {
        let html = app
            .get_page(&format!("{page}?notice={notice}"), &cookie)
            .await
            .text();
        assert!(html.contains(r#"id="source" open"#), "{notice}");
        let group = &html[html.find(group).unwrap()..];
        let flash = &group[group.find("data-flash").unwrap() - 30..];
        assert!(
            flash.contains(r#"class="flash"#) && flash.contains(text),
            "{notice}: {flash}"
        );
    }
    // Anything else in `notice` is ignored.
    let html = app
        .get_page(&format!("{page}?notice=bogus"), &cookie)
        .await
        .text();
    assert!(!html.contains("data-flash") && !html.contains(r#"id="source" open"#));
}

/// qa-31-s11-code L5: outside text - an app's name and display label, a phone's name, a sync
/// failure's raw error - is escaped on the Apps tab and the app page.
#[tokio::test]
async fn outside_text_is_escaped_on_the_apps_pages() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (phone, _) = app.enrolled_device("<b>Anna</b>").await;
    let id = catalog_row(
        &app,
        "Element <b>X</b>",
        "io.element.android.x",
        Some("v1"),
        None,
    )
    .await;
    select_app(&app, phone, id).await;
    let other = catalog_row(&app, "Notes", "org.notes", Some("v1"), None).await;
    sqlx::query("UPDATE tracked_apps SET display_label = '<b>C</b>' WHERE id = ?")
        .bind(other)
        .execute(&app.db)
        .await
        .unwrap();
    let running = app.state.app_syncs.start(id).unwrap();
    running.finish(&Err(crate::handlers::tracked_apps::Failure::new(
        crate::handlers::tracked_apps::SyncProblem::Download,
        "<b>boom</b>",
    )));
    for page in ["/apps".to_string(), format!("/apps/tracked/{id}")] {
        let html = app.get_page(&page, &cookie).await.text();
        assert!(!html.contains("<b>"), "{page}: {html}");
        assert!(html.contains("&#60;b&#62;"), "{page}");
    }
    let html = app
        .get_page(&format!("/apps/tracked/{id}"), &cookie)
        .await
        .text();
    assert!(html.contains("&#60;b&#62;boom&#60;/b&#62;"));
    let list = app.get_page("/apps", &cookie).await.text();
    assert!(
        list.contains("&#60;b&#62;C&#60;/b&#62;") && list.contains("&#60;b&#62;Anna&#60;/b&#62;")
    );
    // A refused form's reason (the shared flash partial, the Upload group's too) names what was
    // posted: escaped.
    let empty = catalog_row(&app, "Blank", "", None, None).await;
    let refused = app
        .request_form(
            Method::POST,
            &format!("/apps/tracked/{empty}/confirm-package"),
            Some(&cookie),
            &[("package", "<b>pkg</b>")],
        )
        .await
        .text();
    assert!(!refused.contains("<b>"), "{refused}");
    assert!(refused.contains("&#60;b&#62;pkg&#60;/b&#62;"));
}

/// The launcher's row says "all phones" only once a phone is set up (nit 7).
#[tokio::test]
async fn the_launcher_is_on_all_phones_only_once_there_are_some() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let launcher = catalog_row(
        &app,
        "Vibb One",
        "me.vibb.launcher",
        Some("launcher-v1.0.0"),
        None,
    )
    .await;
    sqlx::query("UPDATE tracked_apps SET is_launcher = 1 WHERE id = ?")
        .bind(launcher)
        .execute(&app.db)
        .await
        .unwrap();
    let _ = app.create_device("Not yet").await;
    let row_text = |html: &str| row_of(html, &format!(r#"data-app="{launcher}""#)).to_string();
    let html = app.get_page("/apps", &cookie).await.text();
    assert!(row_text(&html).contains("Launcher · no phones"), "{html}");
    let _ = app.enrolled_device("Anna").await;
    let html = app.get_page("/apps", &cookie).await.text();
    assert!(row_text(&html).contains("Launcher · all phones"), "{html}");
}

/// Design 34 P11 (migrations/0077): the launcher's catalog row is "Vibb One" when it still had a
/// default name ("Vibb", "Kids Launcher"); a name the parent chose, and a row that isn't the
/// launcher's, stay as they were.
#[tokio::test]
async fn the_launcher_row_becomes_vibb_one_only_from_a_default_name() {
    for (name, is_launcher, want) in [
        ("Vibb", 1, "Vibb One"),
        ("Kids Launcher", 1, "Vibb One"),
        ("Ellas telefon", 1, "Ellas telefon"),
        ("vibb", 1, "vibb"),
        ("Vibb", 0, "Vibb"),
        ("Kids Launcher", 0, "Kids Launcher"),
    ] {
        let (db, _dir) = super::cleanup::migrated_before(77).await;
        sqlx::query(
            "INSERT INTO tracked_apps (name, package_name, github_repo, is_launcher) \
             VALUES (?, 'me.vibb.launcher', 'palchrb/vibb-launcher', ?)",
        )
        .bind(name)
        .bind(is_launcher)
        .execute(&db)
        .await
        .unwrap();
        super::cleanup::finish(&db).await;
        let got: String = sqlx::query_scalar("SELECT name FROM tracked_apps")
            .fetch_one(&db)
            .await
            .unwrap();
        assert_eq!(got, want, "{name:?}, is_launcher {is_launcher}");
    }
}
