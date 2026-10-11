//! Security review M10, server part: a catalog app's package and signing key are pinned from its
//! first release and sent to the phones; a release that differs is refused; a new phone starts
//! with a safe allowlist. (The phone's own check before installing comes with the launcher's
//! security wave.)

use super::*;

const KEY1: &str = "0796f1809e476a2ce724f1341bcb32d03185b7a202c3469787c5775d65ef4be3";
const KEY2: &str = "7639db09b107ba0392d32bf2e80aebc4e728c8ea06c091a0d4330fddaaddd434";

fn fixture(name: &str) -> Vec<u8> {
    std::fs::read(format!("testdata/apk/{name}")).unwrap()
}

/// "Upload a release" on the app's page.
async fn upload(app: &TestApp, cookie: &str, id: i64, label: &str, bytes: &[u8]) -> TestResponse {
    let boundary = "XBOUNDARYX";
    let mut body = Vec::new();
    body.extend_from_slice(
        format!(
            "--{boundary}\r\nContent-Disposition: form-data; name=\"release_label\"\r\n\r\n\
             {label}\r\n--{boundary}\r\nContent-Disposition: form-data; name=\"apk\"; \
             filename=\"app-{label}.apk\"\r\nContent-Type: application/octet-stream\r\n\r\n"
        )
        .as_bytes(),
    );
    body.extend_from_slice(bytes);
    body.extend_from_slice(format!("\r\n--{boundary}--\r\n").as_bytes());
    app.send(
        Request::builder()
            .method(Method::POST)
            .uri(format!("/apps/tracked/{id}/upload"))
            .header(header::COOKIE, cookie)
            .header(
                header::CONTENT_TYPE,
                format!("multipart/form-data; boundary={boundary}"),
            )
            .body(Body::from(body))
            .unwrap(),
    )
    .await
}

async fn pins(app: &TestApp, id: i64) -> (String, Option<String>, Option<String>) {
    sqlx::query_as(
        "SELECT package_name, signer_sha256, latest_release_tag FROM tracked_apps WHERE id = ?",
    )
    .bind(id)
    .fetch_one(&app.db)
    .await
    .unwrap()
}

#[tokio::test]
async fn the_first_release_pins_the_app_and_a_different_one_is_refused() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (device, token) = app.enrolled_device("phone").await;
    let id: i64 = sqlx::query_scalar(
        "INSERT INTO tracked_apps (name, package_name, github_repo, source_type) \
         VALUES ('Fixture', '', '', 'manual') RETURNING id",
    )
    .fetch_one(&app.db)
    .await
    .unwrap();
    // Checked for the phone before its package was known: nothing could be allowlisted yet.
    sqlx::query("INSERT INTO device_tracked_apps (device_id, tracked_app_id) VALUES (?, ?)")
        .bind(device)
        .bind(id)
        .execute(&app.db)
        .await
        .unwrap();

    // Not an APK at all: refused, nothing cached.
    let junk = upload(&app, &cookie, id, "0.9", b"PK\x03\x04not a zip").await;
    assert!(
        junk.text().contains("a zip: invalid Zip archive"),
        "{}",
        junk.text()
    );
    assert_eq!(pins(&app, id).await, (String::new(), None, None));

    let mut nudges = app.state.command_notify.subscribe();
    let first = upload(&app, &cookie, id, "1.0", &fixture("fixture-key1.apk")).await;
    assert!(first.status.is_redirection(), "{}", first.text());
    assert_eq!(
        pins(&app, id).await,
        (
            "me.vibb.testfixture".to_string(),
            Some(KEY1.to_string()),
            Some("1.0".to_string())
        )
    );
    // The package learned from the APK is allowed where the app is checked, and the phone told.
    assert_eq!(nudges.try_recv().ok(), Some(device));
    let policy = app
        .request(Method::GET, "/api/devices/policy", Some(&token), None)
        .await
        .json();
    assert!(
        policy["allowlist"]
            .as_array()
            .unwrap()
            .contains(&serde_json::json!("me.vibb.testfixture"))
    );
    // The phones get both pins with the app list.
    let listed = app
        .request(Method::GET, "/api/devices/apps", Some(&token), None)
        .await
        .json();
    let entry = &listed.as_array().unwrap()[0];
    assert_eq!(entry["package_name"], "me.vibb.testfixture");
    assert_eq!(entry["signer_sha256"], KEY1);

    // Another key, another package: refused, the cached release kept.
    for (bad, why) in [
        ("fixture-key2.apk", "signed with a different key"),
        (
            "other-package-key1.apk",
            "the Android package me.vibb.otherfixture",
        ),
    ] {
        let refused = upload(&app, &cookie, id, "2.0", &fixture(bad)).await;
        assert!(refused.text().contains(why), "{bad}: {}", refused.text());
        assert_eq!(pins(&app, id).await.2.as_deref(), Some("1.0"), "{bad}");
    }
    // The same key again is a normal update.
    let update = upload(&app, &cookie, id, "1.1", &fixture("fixture-key1-v2.apk")).await;
    assert!(update.status.is_redirection(), "{}", update.text());
    assert_eq!(pins(&app, id).await.2.as_deref(), Some("1.1"));

    // The page shows the pins; "Forget" clears both, and the next release pins its own.
    let page = app
        .get_page(&format!("/apps/tracked/{id}"), &cookie)
        .await
        .text();
    assert!(page.contains(KEY1), "{page}");
    let forgot = app
        .request_form(
            Method::POST,
            &format!("/apps/tracked/{id}/forget-package-name"),
            Some(&cookie),
            &[],
        )
        .await;
    assert!(forgot.status.is_redirection());
    let rekeyed = upload(&app, &cookie, id, "3.0", &fixture("fixture-key2.apk")).await;
    assert!(rekeyed.status.is_redirection(), "{}", rekeyed.text());
    assert_eq!(pins(&app, id).await.1.as_deref(), Some(KEY2));
}

/// Releases cached before the pins existed are pinned from the cached file at startup.
#[tokio::test]
async fn a_release_cached_before_the_update_is_pinned_from_its_file() {
    let app = TestApp::new().await;
    let dir = tempfile::tempdir().unwrap();
    let path = dir.path().join("cached.apk");
    std::fs::write(&path, fixture("fixture-key1.apk")).unwrap();
    let mut ids = Vec::new();
    for package in ["", "me.vibb.testfixture", "org.example.wrong"] {
        ids.push(
            sqlx::query_scalar::<_, i64>(
                "INSERT INTO tracked_apps (name, package_name, github_repo, latest_release_tag, \
                 latest_release_file_path, latest_release_sha256) \
                 VALUES ('Cached', ?, 'owner/repo', 'v1', ?, 'x') RETURNING id",
            )
            .bind(package)
            .bind(path.to_str().unwrap())
            .fetch_one(&app.db)
            .await
            .unwrap(),
        );
    }
    crate::handlers::tracked_apps::backfill_release_hashes(app.state.clone()).await;
    assert_eq!(
        pins(&app, ids[0]).await.0,
        "me.vibb.testfixture",
        "an empty package is filled in"
    );
    assert_eq!(pins(&app, ids[0]).await.1.as_deref(), Some(KEY1));
    assert_eq!(pins(&app, ids[1]).await.1.as_deref(), Some(KEY1));
    // A catalog package that isn't the file's: left alone, not pinned (logged).
    assert_eq!(
        pins(&app, ids[2]).await,
        (
            "org.example.wrong".to_string(),
            None,
            Some("v1".to_string())
        )
    );
}

/// A new phone's allowlist starts with the safe defaults it has - never Settings, a browser, a file
/// manager or the store, whatever it reports.
#[tokio::test]
async fn a_new_phone_starts_with_the_safe_defaults_only() {
    let app = TestApp::new().await;
    let (_, token) = app.enrolled_device("phone").await;
    let installed: Vec<serde_json::Value> = [
        "com.android.settings",
        "com.android.chrome",
        "com.google.android.documentsui",
        "com.android.vending",
        "com.google.android.deskclock",
        "com.google.android.calculator",
        "me.vibb.music",
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
                "lock_reason": "none",
                "kiosk_engaged": true,
                "installed_apps": installed,
            })),
        )
        .await;
    assert!(res.status.is_success());
    let policy = app
        .request(Method::GET, "/api/devices/policy", Some(&token), None)
        .await
        .json();
    let mut allowed: Vec<String> = policy["allowlist"]
        .as_array()
        .unwrap()
        .iter()
        .map(|p| p.as_str().unwrap().to_string())
        .collect();
    allowed.sort();
    assert_eq!(
        allowed,
        [
            "com.google.android.calculator",
            "com.google.android.deskclock",
            "me.vibb.music"
        ]
    );
    for never in [
        "com.android.settings",
        "com.android.chrome",
        "com.google.android.documentsui",
    ] {
        assert!(!crate::handlers::device_api::SAFE_DEFAULT_APPS.contains(&never));
    }
}
