//! An F-Droid repo as a release source (PLAN "An F-Droid repo as a release source; Etar first"):
//! Add App's F-Droid choice and its refusals, the app's page, one source per app (the edit form,
//! the launcher switch, an upload), and the catalog the phones get. The sync itself is tested
//! against an in-process repo in `handlers::tracked_apps::tests` and `fdroid::tests`.

use super::*;

/// An F-Droid catalog row (f-droid.org unless `repo`), with a cached release when `release`.
async fn fdroid_app(app: &TestApp, package: &str, repo: &str, release: bool) -> i64 {
    sqlx::query_scalar(
        "INSERT INTO tracked_apps (name, package_name, source_type, github_repo, fdroid_repo, \
         fdroid_package, latest_release_tag, latest_release_asset_id) \
         VALUES ('Etar', '', 'fdroid', '', ?, ?, ?, ?) RETURNING id",
    )
    .bind(repo)
    .bind(package)
    .bind(release.then_some("v1.0.57"))
    .bind(release.then_some(57))
    .fetch_one(&app.db)
    .await
    .unwrap()
}

async fn source_of(app: &TestApp, id: i64) -> (String, Option<String>, Option<String>) {
    sqlx::query_as("SELECT source_type, fdroid_repo, fdroid_package FROM tracked_apps WHERE id = ?")
        .bind(id)
        .fetch_one(&app.db)
        .await
        .unwrap()
}

async fn app_count(app: &TestApp) -> i64 {
    sqlx::query_scalar("SELECT COUNT(*) FROM tracked_apps")
        .fetch_one(&app.db)
        .await
        .unwrap()
}

async fn newest_id(app: &TestApp) -> i64 {
    sqlx::query_scalar("SELECT MAX(id) FROM tracked_apps")
        .fetch_one(&app.db)
        .await
        .unwrap()
}

/// The refused field: marked, focused, its reason under it and named by `aria-describedby`.
fn assert_field_refused(html: &str, field: &str) {
    let input = tags(html, "input")
        .into_iter()
        .find(|tag| tag.attr("name") == Some(field))
        .unwrap_or_else(|| panic!("no {field}"));
    assert_eq!(input.attr("aria-invalid"), Some("true"), "{field}");
    assert!(input.has("autofocus"), "{field}");
    let error_id = format!("{field}-error");
    assert_eq!(input.attr("aria-describedby"), Some(error_id.as_str()));
    assert!(html.contains(&format!(r#"id="{error_id}""#)), "{field}");
}

/// Add App offers F-Droid next to GitHub and uploads: its own fields (the repo prefilled), the
/// one-source line, no script; a save stores the repo normalised and the package, nothing of
/// GitHub's, and no package pin (that comes from the first APK).
#[tokio::test]
async fn an_fdroid_app_is_added_with_its_repo_and_package() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let html = app
        .get_page("/apps/tracked/new", &cookie)
        .await
        .text()
        .replace("&#39;", "'");
    assert_eq!(input_checked(&html, "source_type", "github"), Some(true));
    assert_eq!(input_checked(&html, "source_type", "fdroid"), Some(false));
    assert_eq!(input_checked(&html, "source_type", "manual"), Some(false));
    let radios = html.find(r#"id="source-manual""#).unwrap();
    assert!(radios < html.find(r#"class="fdroid-only""#).unwrap());
    let repo = tags(&html, "input")
        .into_iter()
        .find(|tag| tag.attr("name") == Some("fdroid_repo"))
        .unwrap();
    assert_eq!(repo.attr("value"), Some("https://f-droid.org/repo"));
    for field in ["fdroid_repo", "fdroid_package"] {
        assert_eq!(form_action_of(&html, field), Some("/apps/tracked/new"));
    }
    assert!(html.contains(
        "F-Droid signs apps with its own key: an app from F-Droid can't later switch to GitHub or \
         Play without a reinstall."
    ));
    assert!(!html.contains("onchange"), "no script decides what shows");

    let saved = app
        .request_form(
            Method::POST,
            "/apps/tracked/new",
            Some(&cookie),
            &[
                ("name", "Etar"),
                ("source_type", "fdroid"),
                ("fdroid_repo", " https://f-droid.org/repo/ "),
                ("fdroid_package", " ws.xsoh.etar "),
                // GitHub's fields, hidden for F-Droid: ignored, an invalid filter too.
                ("github_repo", "owner/repo"),
                ("asset_pattern", "^("),
                ("include_prereleases", "on"),
            ],
        )
        .await;
    let id = newest_id(&app).await;
    assert_eq!(
        saved.location(),
        Some(format!("/apps/tracked/{id}").as_str())
    );
    let row: (String, String, Option<String>, bool, String) = sqlx::query_as(
        "SELECT source_type, github_repo, asset_pattern, include_prereleases, package_name \
         FROM tracked_apps WHERE id = ?",
    )
    .bind(id)
    .fetch_one(&app.db)
    .await
    .unwrap();
    assert_eq!(
        row,
        ("fdroid".into(), String::new(), None, false, String::new())
    );
    assert_eq!(
        source_of(&app, id).await,
        (
            "fdroid".to_string(),
            Some("https://f-droid.org/repo".to_string()),
            Some("ws.xsoh.etar".to_string())
        )
    );

    // A blank repo is F-Droid's; IzzyOnDroid's is kept as typed, without its slash.
    for (typed, stored, package) in [
        ("", "https://f-droid.org/repo", "org.fossify.calendar"),
        (
            "https://apt.izzysoft.de/fdroid/repo/",
            "https://apt.izzysoft.de/fdroid/repo",
            "com.example.izzy",
        ),
    ] {
        let saved = app
            .request_form(
                Method::POST,
                "/apps/tracked/new",
                Some(&cookie),
                &[
                    ("name", "App"),
                    ("source_type", "fdroid"),
                    ("fdroid_repo", typed),
                    ("fdroid_package", package),
                ],
            )
            .await;
        assert!(saved.status.is_redirection(), "{}", saved.text());
        let id = newest_id(&app).await;
        assert_eq!(source_of(&app, id).await.1.as_deref(), Some(stored));
    }
}

/// Every refusal: 400, nothing written, the entered values kept with F-Droid still chosen, the
/// field marked and the reason under it.
#[tokio::test]
async fn add_app_refuses_a_bad_fdroid_source_by_its_field() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    sqlx::query(
        "INSERT INTO tracked_apps (name, package_name, github_repo) \
         VALUES ('Etar (GitHub)', 'ws.xsoh.etar', 'Etar-Group/Etar-Calendar')",
    )
    .execute(&app.db)
    .await
    .unwrap();
    // Q-6: an F-Droid row waiting for its first release (no package pinned yet) has its package.
    fdroid_app(
        &app,
        "org.fossify.calendar",
        "https://f-droid.org/repo",
        false,
    )
    .await;
    let cases = [
        (
            "http://f-droid.org/repo",
            "org.fossify.calendar",
            "fdroid_repo",
            "must be an https:// address",
        ),
        (
            "f-droid.org/repo",
            "org.fossify.calendar",
            "fdroid_repo",
            "must be an https:// address",
        ),
        (
            "https://f-droid.org/repo",
            "etar",
            "fdroid_package",
            "must be an Android package name",
        ),
        (
            "",
            "me.vibb.launcher",
            "fdroid_package",
            "Vibb One updates itself from its own GitHub releases",
        ),
        (
            "",
            "me.vibb.music",
            "fdroid_package",
            "Vibb music comes from this project's GitHub releases",
        ),
        (
            "",
            "com.android.settings",
            "fdroid_package",
            "belongs to the system",
        ),
        (
            "",
            "ws.xsoh.etar",
            "fdroid_package",
            "Etar (GitHub) in the catalog is already ws.xsoh.etar",
        ),
        (
            "",
            "org.fossify.calendar",
            "fdroid_package",
            "Etar in the catalog is already org.fossify.calendar",
        ),
        (
            "https://192.168.1.10/fdroid/repo",
            "org.example.lan",
            "fdroid_repo",
            "can't be a private, loopback or link-local IP address",
        ),
    ];
    for (repo, package, field, reason) in cases {
        let refused = app
            .request_form(
                Method::POST,
                "/apps/tracked/new",
                Some(&cookie),
                &[
                    ("name", "Etar"),
                    ("source_type", "fdroid"),
                    ("fdroid_repo", repo),
                    ("fdroid_package", package),
                ],
            )
            .await;
        assert_eq!(refused.status, StatusCode::BAD_REQUEST, "{package}");
        let html = refused.text().replace("&#39;", "'");
        assert!(html.contains(reason), "{package}: {html}");
        assert!(html.contains("Nothing was saved."));
        assert_field_refused(&html, field);
        assert_eq!(input_checked(&html, "source_type", "fdroid"), Some(true));
        assert!(html.contains(&format!(r#"value="{package}""#)));
        assert!(html.contains(&format!(r#"value="{repo}""#)));
        assert!(
            html.contains(r#"<details class="section" id="more">"#),
            "GitHub's More Options stays closed"
        );
        assert_eq!(form_action_of(&html, field), Some("/apps/tracked/new"));
    }
    assert_eq!(app_count(&app).await, 2, "nothing written");
}

/// The app's page: "F-Droid · <package>" where a GitHub app shows its repo (linked to the
/// package's page on f-droid.org) with the one-source line under it, Check Now, the F-Droid
/// fields in Details, and no upload and no launcher switch. Another repo shows its host, unlinked.
#[tokio::test]
async fn the_app_page_shows_the_fdroid_source() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let id = fdroid_app(&app, "ws.xsoh.etar", "https://f-droid.org/repo", true).await;
    let html = app
        .get_page(&format!("/apps/tracked/{id}"), &cookie)
        .await
        .text()
        .replace("&#39;", "'");
    assert!(html.contains("F-Droid · ws.xsoh.etar"), "{html}");
    assert!(html.contains(r#"href="https://f-droid.org/packages/ws.xsoh.etar/""#));
    assert!(html.contains(
        "One source per app: F-Droid signs it with its own key, so it can't switch to GitHub or \
         Play without a reinstall."
    ));
    assert!(
        html.contains(r#"<span class="row-value">F-Droid</span>"#),
        "Source & Signing"
    );
    assert!(html.contains(&format!(r#"action="/apps/tracked/{id}/check#check""#)));
    assert!(!html.contains("Last upload"));
    assert!(
        html.contains("1.0.57"),
        "the version as GitHub rows show it"
    );
    assert!(!html.contains(r#"name="release_label""#), "no upload");
    assert!(
        !html.contains(r#"name="is_launcher""#),
        "never the launcher"
    );
    assert!(!html.contains(r#"name="include_prereleases""#));
    assert!(!html.contains(r#"name="github_repo""#));
    let details = format!("/apps/tracked/{id}#details");
    for field in ["fdroid_repo", "fdroid_package", "source_type"] {
        assert_eq!(
            form_action_of(&html, field),
            Some(details.as_str()),
            "{field}"
        );
    }
    assert!(html.contains(r#"<input type="hidden" name="source_type" value="fdroid">"#));

    let izzy = fdroid_app(
        &app,
        "com.example.izzy",
        "https://apt.izzysoft.de/fdroid/repo",
        false,
    )
    .await;
    let html = app
        .get_page(&format!("/apps/tracked/{izzy}"), &cookie)
        .await
        .text()
        .replace("&#39;", "'");
    assert!(
        html.contains("apt.izzysoft.de · com.example.izzy"),
        "{html}"
    );
    assert!(!html.contains("f-droid.org/packages"));
    assert!(html.contains("needs a reinstall when the repo's signing key isn't the developer's"));

    // The Apps tab lists it like any other app.
    let list = app
        .get_page("/apps", &cookie)
        .await
        .text()
        .replace("&#39;", "'");
    assert!(list.contains(&format!(r#"href="/apps/tracked/{id}""#)));
}

/// One source per app: a posted source other than the app's is refused (400, nothing written),
/// with a cached release because the phones would need a reinstall, without one by remove and
/// add; the launcher's row stays GitHub. The F-Droid fields are checked as on Add App and saved
/// normalised.
#[tokio::test]
async fn the_edit_form_keeps_one_source_per_app() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let edit = |id: i64, fields: Vec<(&'static str, &'static str)>| {
        let app = &app;
        let cookie = cookie.clone();
        async move {
            app.request_form(
                Method::POST,
                &format!("/apps/tracked/{id}"),
                Some(&cookie),
                &fields,
            )
            .await
        }
    };

    let etar = fdroid_app(&app, "ws.xsoh.etar", "https://f-droid.org/repo", true).await;
    let refused = edit(
        etar,
        vec![
            ("name", "Etar"),
            ("source_type", "github"),
            ("github_repo", "Etar-Group/Etar-Calendar"),
        ],
    )
    .await;
    assert_eq!(refused.status, StatusCode::BAD_REQUEST);
    let html = refused.text().replace("&#39;", "'");
    assert!(
        html.contains("This app's releases come from F-Droid: its source can't change"),
        "{html}"
    );
    assert!(html.contains("reinstall"));
    assert!(html.contains(r#"data-error="source_type""#));
    assert!(html.contains(r#"id="source" open"#));
    assert_eq!(source_of(&app, etar).await.0, "fdroid");

    let github: i64 = sqlx::query_scalar(
        "INSERT INTO tracked_apps (name, package_name, github_repo) \
         VALUES ('Etar', '', 'Etar-Group/Etar-Calendar') RETURNING id",
    )
    .fetch_one(&app.db)
    .await
    .unwrap();
    let refused = edit(
        github,
        vec![
            ("name", "Etar"),
            ("source_type", "fdroid"),
            ("fdroid_package", "org.fossify.calendar"),
        ],
    )
    .await;
    assert_eq!(refused.status, StatusCode::BAD_REQUEST);
    assert!(
        refused
            .text()
            .replace("&#39;", "'")
            .contains("remove the app and add it again")
    );
    assert_eq!(
        source_of(&app, github).await,
        ("github".to_string(), None, None)
    );

    sqlx::query("UPDATE tracked_apps SET is_launcher = 1, latest_release_tag = 'launcher-v0.36.0' WHERE id = ?")
        .bind(github)
        .execute(&app.db)
        .await
        .unwrap();
    let refused = edit(
        github,
        vec![
            ("name", "Vibb One"),
            ("source_type", "fdroid"),
            ("fdroid_package", "me.vibb.launcher"),
        ],
    )
    .await;
    assert_eq!(refused.status, StatusCode::BAD_REQUEST);
    assert!(
        refused
            .text()
            .replace("&#39;", "'")
            .contains("This app's releases come from GitHub")
    );
    assert_eq!(source_of(&app, github).await.0, "github");

    // The F-Droid fields: refused by the field, then saved normalised.
    let refused = edit(
        etar,
        vec![
            ("name", "Etar"),
            ("source_type", "fdroid"),
            ("fdroid_repo", "http://x.org/repo"),
            ("fdroid_package", "ws.xsoh.etar"),
        ],
    )
    .await;
    assert_eq!(refused.status, StatusCode::BAD_REQUEST);
    let html = refused.text().replace("&#39;", "'");
    assert_field_refused(&html, "fdroid_repo");
    assert!(html.contains(r#"value="http://x.org/repo""#));
    let refused = edit(
        etar,
        vec![
            ("name", "Etar"),
            ("fdroid_repo", ""),
            ("fdroid_package", "me.vibb.music"),
        ],
    )
    .await;
    assert_eq!(refused.status, StatusCode::BAD_REQUEST);
    assert_field_refused(&refused.text(), "fdroid_package");
    assert_eq!(
        source_of(&app, etar).await.1.as_deref(),
        Some("https://f-droid.org/repo"),
        "nothing written"
    );
    let saved = edit(
        etar,
        vec![
            ("name", "Etar Calendar"),
            ("source_type", "fdroid"),
            ("fdroid_repo", "https://apt.izzysoft.de/fdroid/repo/"),
            ("fdroid_package", "ws.xsoh.etar"),
        ],
    )
    .await;
    assert_eq!(
        saved.location(),
        Some(format!("/apps/tracked/{etar}?open=source#details").as_str())
    );
    assert_eq!(
        source_of(&app, etar).await,
        (
            "fdroid".to_string(),
            Some("https://apt.izzysoft.de/fdroid/repo".to_string()),
            Some("ws.xsoh.etar".to_string())
        )
    );
}

/// An F-Droid app is never the launcher (Vibb One updates itself from its GitHub releases) and
/// takes no upload (only the repo's APKs, checked against its index).
#[tokio::test]
async fn an_fdroid_app_is_never_the_launcher_and_takes_no_upload() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let id = fdroid_app(&app, "ws.xsoh.etar", "https://f-droid.org/repo", false).await;
    let answer = app
        .request_form(
            Method::POST,
            &format!("/apps/tracked/{id}/is-launcher"),
            Some(&cookie),
            &[("is_launcher", "on")],
        )
        .await;
    assert!(answer.status.is_redirection());
    let launcher: bool = sqlx::query_scalar("SELECT is_launcher FROM tracked_apps WHERE id = ?")
        .bind(id)
        .fetch_one(&app.db)
        .await
        .unwrap();
    assert!(!launcher);

    let boundary = "XBOUNDARYX";
    let mut body = format!(
        "--{boundary}\r\nContent-Disposition: form-data; name=\"release_label\"\r\n\r\n1.0\r\n\
         --{boundary}\r\nContent-Disposition: form-data; name=\"apk\"; filename=\"etar.apk\"\r\n\
         Content-Type: application/octet-stream\r\n\r\n"
    )
    .into_bytes();
    body.extend_from_slice(&std::fs::read("testdata/apk/fixture-key1.apk").unwrap());
    body.extend_from_slice(format!("\r\n--{boundary}--\r\n").as_bytes());
    let upload = app
        .send(
            Request::builder()
                .method(Method::POST)
                .uri(format!("/apps/tracked/{id}/upload"))
                .header(header::COOKIE, cookie.clone())
                .header(
                    header::CONTENT_TYPE,
                    format!("multipart/form-data; boundary={boundary}"),
                )
                .body(Body::from(body))
                .unwrap(),
        )
        .await;
    assert_eq!(upload.status, StatusCode::BAD_REQUEST);
    let file: Option<String> =
        sqlx::query_scalar("SELECT latest_release_file_path FROM tracked_apps WHERE id = ?")
            .bind(id)
            .fetch_one(&app.db)
            .await
            .unwrap();
    assert_eq!(file, None);
    assert!(!app.state.tracked_apps_dir.join(id.to_string()).exists());
}

/// The phones get an F-Droid app exactly as a GitHub one: the same keys, the server's own
/// download URL, the tag `v<versionName>@<versionCode>`, the file's hash - and the download.
#[tokio::test]
async fn the_phone_gets_an_fdroid_app_as_any_other() {
    let app = TestApp::new().await;
    let (device, token) = app.enrolled_device("phone").await;
    let dir = tempfile::tempdir().unwrap();
    let apk = std::fs::read("testdata/apk/fixture-key1.apk").unwrap();
    let sha = {
        use sha2::Digest;
        hex::encode(sha2::Sha256::digest(&apk))
    };
    let mut ids = Vec::new();
    for (name, source, tag, asset) in [
        ("GitHub app", "github", "v2.0", 14),
        ("Etar", "fdroid", "v1.0.57", 57),
    ] {
        let path = dir.path().join(format!("{name}.apk"));
        std::fs::write(&path, &apk).unwrap();
        let id: i64 = sqlx::query_scalar(
            "INSERT INTO tracked_apps (name, package_name, source_type, github_repo, fdroid_repo, \
             fdroid_package, latest_release_tag, latest_release_asset_id, \
             latest_release_file_path, latest_release_sha256, signer_sha256) \
             VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'aa') RETURNING id",
        )
        .bind(name)
        .bind(if source == "fdroid" {
            "ws.xsoh.etar"
        } else {
            "org.example.app"
        })
        .bind(source)
        .bind(if source == "fdroid" { "" } else { "owner/repo" })
        .bind((source == "fdroid").then_some("https://f-droid.org/repo"))
        .bind((source == "fdroid").then_some("ws.xsoh.etar"))
        .bind(tag)
        .bind(asset)
        .bind(path.to_str().unwrap())
        .bind(&sha)
        .fetch_one(&app.db)
        .await
        .unwrap();
        sqlx::query("INSERT INTO device_tracked_apps (device_id, tracked_app_id) VALUES (?, ?)")
            .bind(device)
            .bind(id)
            .execute(&app.db)
            .await
            .unwrap();
        ids.push(id);
    }

    let list = app
        .request(Method::GET, "/api/devices/apps", Some(&token), None)
        .await
        .json();
    let entries = list.as_array().unwrap();
    let entry = |id: i64| {
        entries
            .iter()
            .find(|e| e["id"] == id)
            .unwrap_or_else(|| panic!("no {id}: {list}"))
    };
    let keys = |e: &serde_json::Value| {
        let mut keys: Vec<String> = e.as_object().unwrap().keys().cloned().collect();
        keys.sort();
        keys
    };
    let (github, etar) = (entry(ids[0]), entry(ids[1]));
    assert_eq!(keys(etar), keys(github), "the same shape");
    assert_eq!(etar["release_tag"], "v1.0.57@57");
    assert_eq!(etar["package_name"], "ws.xsoh.etar");
    assert_eq!(etar["sha256"], sha.as_str());
    assert_eq!(
        etar["download_url"],
        format!("/api/devices/apps/{}/download", ids[1])
    );
    let download = app
        .request(
            Method::GET,
            &format!("/api/devices/apps/{}/download", ids[1]),
            Some(&token),
            None,
        )
        .await;
    assert_eq!(download.status, StatusCode::OK);
    assert_eq!(download.body, apk);
}

/// Q-5: once the app is pinned by its first release, Details keeps its package and repo - another
/// package is another app, another repo may sign with another key; the reasons by the fields.
/// The name still saves.
#[tokio::test]
async fn a_pinned_fdroid_app_keeps_its_package_and_repo() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let id = fdroid_app(&app, "ws.xsoh.etar", "https://f-droid.org/repo", true).await;
    sqlx::query(
        "UPDATE tracked_apps SET package_name = 'ws.xsoh.etar', signer_sha256 = 'aa' WHERE id = ?",
    )
    .bind(id)
    .execute(&app.db)
    .await
    .unwrap();
    let edit = |repo: &'static str, package: &'static str| {
        let app = &app;
        let cookie = cookie.clone();
        async move {
            app.request_form(
                Method::POST,
                &format!("/apps/tracked/{id}"),
                Some(&cookie),
                &[
                    ("name", "Etar Calendar"),
                    ("source_type", "fdroid"),
                    ("fdroid_repo", repo),
                    ("fdroid_package", package),
                ],
            )
            .await
        }
    };
    let refused = edit("https://f-droid.org/repo", "org.fossify.calendar").await;
    assert_eq!(refused.status, StatusCode::BAD_REQUEST);
    let html = refused.text().replace("&#39;", "'");
    assert!(
        html.contains(
            "This app is ws.xsoh.etar - pinned from its release: another package is another app"
        ),
        "{html}"
    );
    assert_field_refused(&html, "fdroid_package");
    let refused = edit("https://apt.izzysoft.de/fdroid/repo", "ws.xsoh.etar").await;
    assert_eq!(refused.status, StatusCode::BAD_REQUEST);
    let html = refused.text().replace("&#39;", "'");
    assert!(
        html.contains("This app's signing key is pinned from its release from F-Droid"),
        "{html}"
    );
    assert!(html.contains("reinstall"));
    assert_field_refused(&html, "fdroid_repo");
    assert_eq!(
        source_of(&app, id).await,
        (
            "fdroid".to_string(),
            Some("https://f-droid.org/repo".to_string()),
            Some("ws.xsoh.etar".to_string())
        ),
        "nothing written"
    );
    // The same repo (as typed with its slash) and package: the name saves.
    let saved = edit("https://f-droid.org/repo/", "ws.xsoh.etar").await;
    assert!(saved.status.is_redirection(), "{}", saved.text());
    let name: String = sqlx::query_scalar("SELECT name FROM tracked_apps WHERE id = ?")
        .bind(id)
        .fetch_one(&app.db)
        .await
        .unwrap();
    assert_eq!(name, "Etar Calendar");
}

/// Q-4: Add App says which apps the F-Droid source suits (one APK for every phone); Q-8: removing
/// an F-Droid app removes its repo's cached index once no other app uses that repo.
#[tokio::test]
async fn the_abi_line_and_the_cached_index_goes_with_the_last_app() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let html = app
        .get_page("/apps/tracked/new", &cookie)
        .await
        .text()
        .replace("&#39;", "'");
    assert!(html.contains(
        "For apps with one APK for every phone: for an app the repo builds per processor type \
         (ABI), the newest build may be one the phone can't install."
    ));

    let first = fdroid_app(&app, "ws.xsoh.etar", "https://f-droid.org/repo", false).await;
    let second = fdroid_app(
        &app,
        "org.fossify.calendar",
        "https://f-droid.org/repo",
        false,
    )
    .await;
    let izzy = fdroid_app(
        &app,
        "com.example.izzy",
        "https://apt.izzysoft.de/fdroid/repo",
        false,
    )
    .await;
    let cache = app.state.tracked_apps_dir.join(crate::fdroid::CACHE_DIR);
    std::fs::create_dir_all(&cache).unwrap();
    let key = |repo: &str| {
        use sha2::Digest;
        hex::encode(sha2::Sha256::digest(repo.as_bytes()))[..16].to_string()
    };
    let file = |repo: &str| cache.join(format!("{}-{}.json", key(repo), "cd".repeat(32)));
    let fdroid_org = file("https://f-droid.org/repo");
    let izzy_file = file("https://apt.izzysoft.de/fdroid/repo");
    std::fs::write(&fdroid_org, b"{}").unwrap();
    std::fs::write(&izzy_file, b"{}").unwrap();
    let delete = |id: i64| {
        let app = &app;
        let cookie = cookie.clone();
        async move {
            let res = app
                .request_form(
                    Method::POST,
                    &format!("/apps/tracked/{id}/delete"),
                    Some(&cookie),
                    &[],
                )
                .await;
            assert!(res.status.is_redirection());
        }
    };
    delete(first).await;
    assert!(fdroid_org.exists(), "another app still uses f-droid.org");
    delete(second).await;
    assert!(!fdroid_org.exists(), "its last app gone");
    assert!(izzy_file.exists());
    delete(izzy).await;
    assert!(!izzy_file.exists());
}
