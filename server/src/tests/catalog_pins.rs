//! Security review M10, server part: a catalog app's package and signing key are pinned from its
//! first release and sent to the phones; a release that differs is refused; a new phone starts
//! with a safe allowlist. (The phone's own check before installing comes with the launcher's
//! security wave.)

use super::*;

const KEY1: &str = "0796f1809e476a2ce724f1341bcb32d03185b7a202c3469787c5775d65ef4be3";
const KEY2: &str = "7639db09b107ba0392d32bf2e80aebc4e728c8ea06c091a0d4330fddaaddd434";

pub(super) fn fixture(name: &str) -> Vec<u8> {
    std::fs::read(format!("testdata/apk/{name}")).unwrap()
}

/// "Upload a release" on the app's page.
pub(super) async fn upload(
    app: &TestApp,
    cookie: &str,
    id: i64,
    label: &str,
    bytes: &[u8],
) -> TestResponse {
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

/// The phone's status report: `(package, installer)` installed.
async fn report(app: &TestApp, token: &str, installed: &[(&str, Option<&str>)]) {
    let installed: Vec<serde_json::Value> = installed
        .iter()
        .map(|(package, installer)| {
            serde_json::json!({ "package_name": package, "label": package, "installer": installer })
        })
        .collect();
    let res = app
        .request(
            Method::POST,
            "/api/devices/status",
            Some(token),
            Some(serde_json::json!({
                "lock_reason": "none", "kiosk_engaged": true, "installed_apps": installed,
            })),
        )
        .await;
    assert!(res.status.is_success(), "{}", res.text());
}

/// Whether the phone's policy allows `package`.
async fn allowed(app: &TestApp, token: &str, package: &str) -> bool {
    app.request(Method::GET, "/api/devices/policy", Some(token), None)
        .await
        .json()["allowlist"]
        .as_array()
        .is_some_and(|list| list.contains(&serde_json::json!(package)))
}

pub(super) async fn manual_app(app: &TestApp, name: &str) -> i64 {
    sqlx::query_scalar(
        "INSERT INTO tracked_apps (name, package_name, github_repo, source_type) \
         VALUES (?, '', '', 'manual') RETURNING id",
    )
    .bind(name)
    .fetch_one(&app.db)
    .await
    .unwrap()
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

    // Not an APK at all: refused, nothing cached. The Upload group says so in a short title, the
    // raw error behind its ⓘ (design 31 §4.4), with Source & Signing open.
    let junk = upload(&app, &cookie, id, "0.9", b"PK\x03\x04not a zip").await;
    let page = junk.text().replace("&#39;", "'");
    assert!(page.contains("a zip: invalid Zip archive"), "{page}");
    let flash = &page[page
        .find(r#"data-flash="upload""#)
        .expect("the upload's flash")..];
    assert!(
        flash.starts_with(r#"data-flash="upload">"#) && flash.contains("Couldn't read the APK")
    );
    assert!(page.contains(r#"id="source" open"#), "{page}");
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
    // A release allows nothing by itself (QA of the security wave, Medium 2): only once the
    // launcher has installed the pinned package on the phone, never from another installer. The
    // upload nudges the phone that has the app, for its list (once).
    assert_eq!(nudges.try_recv().ok(), Some(device));
    assert!(nudges.try_recv().is_err());
    assert!(!allowed(&app, &token, "me.vibb.testfixture").await);
    report(
        &app,
        &token,
        &[("me.vibb.testfixture", Some("com.android.vending"))],
    )
    .await;
    assert!(!allowed(&app, &token, "me.vibb.testfixture").await);
    assert!(nudges.try_recv().is_err());
    report(
        &app,
        &token,
        &[("me.vibb.testfixture", Some("me.vibb.launcher"))],
    )
    .await;
    assert!(allowed(&app, &token, "me.vibb.testfixture").await);
    assert_eq!(nudges.try_recv().ok(), Some(device));
    // The phones get both pins with the app list.
    let listed = app
        .request(Method::GET, "/api/devices/apps", Some(&token), None)
        .await
        .json();
    let entry = &listed.as_array().unwrap()[0];
    assert_eq!(entry["package_name"], "me.vibb.testfixture");
    assert_eq!(entry["signer_sha256"], KEY1);

    // Another key, another package: refused, the cached release kept.
    for (bad, why, title) in [
        (
            "fixture-key2.apk",
            "signed with a different key",
            "Signed with a different key",
        ),
        (
            "other-package-key1.apk",
            "the Android package me.vibb.otherfixture",
            "Release is a different app",
        ),
    ] {
        let refused = upload(&app, &cookie, id, "2.0", &fixture(bad)).await;
        assert!(refused.text().contains(why), "{bad}: {}", refused.text());
        assert!(refused.text().contains(title), "{bad}: {}", refused.text());
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
    let other = dir.path().join("other.apk");
    std::fs::write(&other, fixture("other-package-key1.apk")).unwrap();
    let mut ids = Vec::new();
    for (package, file) in [
        ("", &other),
        ("me.vibb.testfixture", &path),
        ("org.example.wrong", &path),
        ("", &path),
    ] {
        ids.push(
            sqlx::query_scalar::<_, i64>(
                "INSERT INTO tracked_apps (name, package_name, github_repo, latest_release_tag, \
                 latest_release_file_path, latest_release_sha256) \
                 VALUES ('Cached', ?, 'owner/repo', 'v1', ?, 'x') RETURNING id",
            )
            .bind(package)
            .bind(file.to_str().unwrap())
            .fetch_one(&app.db)
            .await
            .unwrap(),
        );
    }
    crate::handlers::tracked_apps::backfill_release_hashes(app.state.clone()).await;
    assert_eq!(
        pins(&app, ids[0]).await.0,
        "me.vibb.otherfixture",
        "an empty package is filled in"
    );
    assert_eq!(pins(&app, ids[0]).await.1.as_deref(), Some(KEY1));
    assert_eq!(pins(&app, ids[1]).await.1.as_deref(), Some(KEY1));
    // Another row is that package already: one app, one row (qa-fdroid-source-code Q-6).
    assert_eq!(
        pins(&app, ids[3]).await,
        (String::new(), None, Some("v1".to_string()))
    );
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

/// QA of the security wave, Medium 2 (its probe): a release whose manifest names an app the phone
/// already has from Play - or a system package - is refused, and allows nothing.
#[tokio::test]
async fn a_release_naming_an_app_from_elsewhere_or_the_system_is_refused() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (device, token) = app.enrolled_device("phone").await;
    report(
        &app,
        &token,
        &[
            ("com.google.android.deskclock", None),
            ("org.mozilla.firefox", Some("com.android.vending")),
        ],
    )
    .await;
    assert!(!allowed(&app, &token, "org.mozilla.firefox").await);
    let id = manual_app(&app, "Totally a game").await;
    sqlx::query("INSERT INTO device_tracked_apps (device_id, tracked_app_id) VALUES (?, ?)")
        .bind(device)
        .bind(id)
        .execute(&app.db)
        .await
        .unwrap();

    let refused = upload(
        &app,
        &cookie,
        id,
        "1.0",
        &fixture("firefox-package-key1.apk"),
    )
    .await;
    assert!(
        refused
            .text()
            .contains("org.mozilla.firefox, which phone already has"),
        "{}",
        refused.text()
    );
    assert!(refused.text().contains("Package belongs to another app"));
    assert_eq!(pins(&app, id).await, (String::new(), None, None));
    assert!(!allowed(&app, &token, "org.mozilla.firefox").await);

    for system in ["com.android.settings", "android", "com.google.android.gms"] {
        assert!(
            crate::handlers::tracked_apps::never_a_catalog_package(system),
            "{system}"
        );
    }
    assert!(!crate::handlers::tracked_apps::never_a_catalog_package(
        "me.vibb.testfixture"
    ));
}

/// The signer and the package fail closed once pinned: a release without a v2/v3 signature, or
/// whose manifest can't be read, is refused.
#[tokio::test]
async fn a_pinned_app_refuses_what_it_cant_check() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let id = manual_app(&app, "Fixture").await;
    let first = upload(&app, &cookie, id, "1.0", &fixture("fixture-key1.apk")).await;
    assert!(first.status.is_redirection());
    let v1_only = upload(
        &app,
        &cookie,
        id,
        "1.1",
        &fixture("fixture-key1-v1-only.apk"),
    )
    .await;
    assert!(
        v1_only
            .text()
            .contains("no APK Signature Scheme v2/v3 signature"),
        "{}",
        v1_only.text()
    );
    assert!(
        v1_only
            .text()
            .replace("&#39;", "'")
            .contains("Release can't be checked")
    );
    assert_eq!(pins(&app, id).await.2.as_deref(), Some("1.0"));

    let pinned =
        sqlx::query_as::<_, crate::models::TrackedApp>("SELECT * FROM tracked_apps WHERE id = ?")
            .bind(id)
            .fetch_one(&app.db)
            .await
            .unwrap();
    let unreadable = crate::apk::ApkIdentity {
        package: None,
        signer_sha256: Some(KEY1.to_string()),
    };
    let refused = crate::handlers::tracked_apps::check_pins(&pinned, &unreadable).unwrap_err();
    assert_eq!(
        refused.problem,
        crate::handlers::tracked_apps::SyncProblem::CantCheck
    );
    assert!(refused.detail.contains("can't be read"), "{refused}");
}

/// QA of the security wave, Medium 3: a new phone keeps calls and texts - its phone and SMS apps,
/// by the roles it reports or by Google's and AOSP's names - and the device page says when the
/// allowlist takes them away.
#[tokio::test]
async fn a_new_phone_keeps_its_phone_and_sms_apps() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let status = |installed: &[&str], call_state: Option<serde_json::Value>| {
        let installed: Vec<serde_json::Value> = installed
            .iter()
            .map(|p| serde_json::json!({ "package_name": p, "label": p, "preinstalled": true }))
            .collect();
        let mut body = serde_json::json!({
            "lock_reason": "none", "kiosk_engaged": true, "installed_apps": installed,
        });
        if let Some(call_state) = call_state {
            body["call_state"] = call_state;
        }
        body
    };
    let allowlist = |token: String| {
        let app = &app;
        async move {
            let mut list: Vec<String> = app
                .request(Method::GET, "/api/devices/policy", Some(&token), None)
                .await
                .json()["allowlist"]
                .as_array()
                .unwrap()
                .iter()
                .map(|p| p.as_str().unwrap().to_string())
                .collect();
            list.sort();
            list
        }
    };

    // A maker's own phone and SMS apps, named by role.
    let (first, token) = app.enrolled_device("phone").await;
    let res = app
        .request(
            Method::POST,
            "/api/devices/status",
            Some(&token),
            Some(status(
                &[
                    "com.android.settings",
                    "com.android.chrome",
                    "com.maker.dialer",
                    "com.maker.sms",
                    "com.google.android.deskclock",
                ],
                Some(serde_json::json!({
                    "state": "unmanaged", "system_dialer": "com.maker.dialer",
                    "default_sms_package": "com.maker.sms",
                })),
            )),
        )
        .await;
    assert!(res.status.is_success());
    assert_eq!(
        allowlist(token.clone()).await,
        [
            "com.google.android.deskclock",
            "com.maker.dialer",
            "com.maker.sms"
        ]
    );
    let page = app
        .get_page(&format!("/devices/{first}"), &cookie)
        .await
        .text();
    assert!(!page.contains("Only emergency calls work"), "{page}");

    // An older launcher without call_state: Google's names.
    let (_, token) = app.enrolled_device("other phone").await;
    let res = app
        .request(
            Method::POST,
            "/api/devices/status",
            Some(&token),
            Some(status(
                &[
                    "com.google.android.dialer",
                    "com.google.android.apps.messaging",
                    "com.android.settings",
                ],
                None,
            )),
        )
        .await;
    assert!(res.status.is_success());
    assert_eq!(
        allowlist(token).await,
        [
            "com.google.android.apps.messaging",
            "com.google.android.dialer"
        ]
    );

    // The parent turns both off on Device › Apps (each back at its row): the device page says
    // what that costs.
    for package in ["com.maker.dialer", "com.maker.sms"] {
        let res = app
            .request_form(
                Method::POST,
                &format!("/devices/{first}/apps/toggle"),
                Some(&cookie),
                &[("package_name", package), ("back", "apps")],
            )
            .await;
        super::assert_redirect(
            &res,
            &super::device_page(first, super::Section::Apps),
            Some(&format!("app-{package}")),
        );
    }
    // Calls & Contacts says what it costs; the packages only behind the warnings' ⓘ (design 31
    // step 8).
    let page = app
        .get_page(&super::device_page(first, super::Section::Calls), &cookie)
        .await
        .text();
    assert!(page.contains("Only emergency calls work"), "{page}");
    assert!(page.contains("Its package name is com.maker.dialer."));
    assert!(page.contains("Messages app is hidden"));
    assert!(page.contains("Its package name is com.maker.sms."));
}
