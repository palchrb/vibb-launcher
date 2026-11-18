//! Privacy review 2026-11-17 M1 and M2: a backup's Download, Upload and Restore ask for the password again, are logged
//! and told to every parent; a parent's change that matters to the kid's safety - an app allowed on a phone, a new
//! contact, the family's own DNS server, location set to Regularly - is told to every parent once per real change.

use axum::body::Body;
use axum::http::{Method, Request, StatusCode, header};

use super::{TestApp, assert_redirect};

const PASSWORD: &str = "correct horse battery staple";

/// The `parent_events` rows of `kind`: `(device_id, params)`.
async fn events(app: &TestApp, kind: &str) -> Vec<(Option<i64>, String)> {
    sqlx::query_as("SELECT device_id, params FROM parent_events WHERE kind = ? ORDER BY id")
        .bind(kind)
        .fetch_all(&app.db)
        .await
        .unwrap()
}

/// The security log's `event_type` rows: `(username, detail)`.
async fn lines(app: &TestApp, event_type: &str) -> Vec<(Option<String>, Option<String>)> {
    sqlx::query_as("SELECT username, detail FROM security_events WHERE event_type = ? ORDER BY id")
        .bind(event_type)
        .fetch_all(&app.db)
        .await
        .unwrap()
}

/// An active push subscription for the signed-in "parent", and the pushes queued so far for `kind`'s events.
async fn subscribe(app: &TestApp) {
    sqlx::query(
        "INSERT INTO push_subscriptions \
         (admin_id, endpoint, p256dh, auth, origin, label, vapid_fp, state, session_epoch) \
         SELECT id, 'https://push.example/1', x'00', x'00', 'https://pwa.example', 'Test', 'fp', 'active', \
         session_epoch FROM admin_users WHERE username = 'parent'",
    )
    .execute(&app.db)
    .await
    .unwrap();
}

async fn pushes(app: &TestApp, kind: &str) -> i64 {
    sqlx::query_scalar(
        "SELECT COUNT(*) FROM push_deliveries d JOIN parent_events e ON e.id = d.event_id WHERE e.kind = ?",
    )
    .bind(kind)
    .fetch_one(&app.db)
    .await
    .unwrap()
}

/// A backup made through Back Up Now: its file name.
async fn backup(app: &TestApp, cookie: &str) -> String {
    let res = app
        .request_form(Method::POST, "/backups/create", Some(cookie), &[])
        .await;
    assert_redirect(&res, "/backups?done=backup", Some("backups"));
    std::fs::read_dir(app.state.data_dir.join("backups"))
        .unwrap()
        .map(|e| e.unwrap().file_name().to_string_lossy().into_owned())
        .find(|name| name.starts_with("backup-"))
        .unwrap()
}

/// Upload's multipart form: the file, then the password.
async fn upload(app: &TestApp, cookie: &str, zip: &[u8], password: &str) -> super::TestResponse {
    const BOUNDARY: &str = "sec-round-2-boundary";
    let mut body = format!(
        "--{BOUNDARY}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"b.zip\"\r\n\
         Content-Type: application/zip\r\n\r\n"
    )
    .into_bytes();
    body.extend_from_slice(zip);
    body.extend_from_slice(
        format!(
            "\r\n--{BOUNDARY}\r\nContent-Disposition: form-data; name=\"password\"\r\n\r\n{password}\
             \r\n--{BOUNDARY}\r\nContent-Disposition: form-data; name=\"assertion\"\r\n\r\n\
             \r\n--{BOUNDARY}--\r\n"
        )
        .as_bytes(),
    );
    let request = Request::builder()
        .method(Method::POST)
        .uri("/backups/upload")
        .header(header::COOKIE, cookie)
        .header(
            header::CONTENT_TYPE,
            format!("multipart/form-data; boundary={BOUNDARY}"),
        )
        .body(Body::from(body))
        .unwrap();
    app.send(request).await
}

/// Download: a POST with the password again. A wrong one answers the page with this backup's Download open and the
/// reason, sends nothing, logs and tells nothing; the right one sends the zip, logs `backup_downloaded` with the parent
/// and tells every parent (no switch). The old GET link is gone.
#[tokio::test]
async fn a_download_asks_for_the_password_and_is_told() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    subscribe(&app).await;
    let file = backup(&app, &cookie).await;
    let url = format!("/backups/{file}/download");

    let page = app.get_page("/backups", &cookie).await.text();
    super::settings_pages::check_forms("/backups", &page);
    assert!(
        page.contains(&format!("action=\"{url}#backups\"")),
        "{page}"
    );
    assert!(!page.contains(&format!("href=\"{url}\"")), "{page}");

    let res = app.get_page(&url, &cookie).await;
    assert_eq!(res.status, StatusCode::METHOD_NOT_ALLOWED);

    let res = app
        .request_form(Method::POST, &url, Some(&cookie), &[("password", "not it")])
        .await;
    assert_eq!(res.status, StatusCode::BAD_REQUEST);
    let html = res.text();
    let item = super::settings_pages::part(
        &html,
        &format!("data-backup=\"{file}\""),
        "</details>\n            </details>",
    );
    assert!(item.contains("Wrong password."), "{item}");
    assert!(
        item.starts_with(&format!("data-backup=\"{file}\" open")),
        "{item}"
    );
    assert!(lines(&app, "backup_downloaded").await.is_empty());
    assert!(events(&app, "backup_downloaded").await.is_empty());

    let res = app
        .request_form(Method::POST, &url, Some(&cookie), &[("password", PASSWORD)])
        .await;
    assert_eq!(res.status, StatusCode::OK);
    assert_eq!(
        res.headers
            .get(header::CONTENT_TYPE)
            .and_then(|v| v.to_str().ok()),
        Some("application/zip")
    );
    assert_eq!(
        lines(&app, "backup_downloaded").await,
        [(Some("parent".to_string()), Some(file.clone()))]
    );
    let told = events(&app, "backup_downloaded").await;
    assert_eq!(told.len(), 1, "{told:?}");
    assert_eq!(told[0].0, None, "no phone");
    assert!(told[0].1.contains("\"parent\":\"parent\""), "{told:?}");
    assert_eq!(pushes(&app, "backup_downloaded").await, 1, "pushed");
    assert_eq!(
        crate::parent_events::Kind::BackupDownloaded.switch(),
        None,
        "every parent, no switch"
    );
}

/// Upload: the password too, checked once the form is read; a wrong one keeps nothing and says so under it, the right
/// one keeps the file and logs `backup_uploaded`.
#[tokio::test]
async fn an_upload_asks_for_the_password_and_is_logged() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let file = backup(&app, &cookie).await;
    let zip = std::fs::read(app.state.data_dir.join("backups").join(&file)).unwrap();
    let count = || {
        std::fs::read_dir(app.state.data_dir.join("backups"))
            .unwrap()
            .count()
    };

    let res = upload(&app, &cookie, &zip, "not it").await;
    assert_eq!(res.status, StatusCode::BAD_REQUEST);
    let html = res.text();
    let group = super::settings_pages::part(&html, "<section id=\"upload\"", "</section>");
    assert!(group.contains("Wrong password."), "{group}");
    assert!(
        group.contains("<details class=\"disclosure cell\" open>"),
        "{group}"
    );
    assert_eq!(count(), 1);
    assert!(lines(&app, "backup_uploaded").await.is_empty());

    let res = upload(&app, &cookie, &zip, PASSWORD).await;
    assert_redirect(&res, "/backups?done=upload", Some("upload"));
    assert_eq!(count(), 2);
    let logged = lines(&app, "backup_uploaded").await;
    assert_eq!(logged.len(), 1, "{logged:?}");
    assert_eq!(logged[0].0.as_deref(), Some("parent"));
}

/// Restore in the container: the typed name and the password; a wrong password asks nothing. The right one tells every
/// parent from this database before the restart; the next start writes the line, with who asked, and the inbox's
/// event (not pushed again) into the restored one.
#[tokio::test]
async fn a_restore_asks_for_the_password_and_is_told_in_both_databases() {
    let app = TestApp::with_env(&[("DEPLOY_KIND", "docker")]).await;
    let cookie = app.admin_cookie().await;
    subscribe(&app).await;
    let file = backup(&app, &cookie).await;
    let url = format!("/backups/{file}/restore");
    let marker = app.state.data_dir.join(crate::startup::RESTORE_REQUEST);

    let res = app
        .request_form(
            Method::POST,
            &url,
            Some(&cookie),
            &[("confirm_filename", file.as_str()), ("password", "not it")],
        )
        .await;
    assert_eq!(res.status, StatusCode::BAD_REQUEST);
    assert!(res.text().contains("Wrong password."));
    assert!(!marker.exists(), "nothing asked");
    assert!(events(&app, "backup_restored").await.is_empty());

    let res = app
        .request_form(
            Method::POST,
            &url,
            Some(&cookie),
            &[("confirm_filename", file.as_str()), ("password", PASSWORD)],
        )
        .await;
    assert_redirect(&res, "/backups?done=restore", Some("backups"));
    assert!(marker.exists());
    let told = events(&app, "backup_restored").await;
    assert_eq!(told.len(), 1, "{told:?}");
    assert_eq!(
        pushes(&app, "backup_restored").await,
        1,
        "pushed before the restart"
    );

    // The next start, in the restored database (here the same one, emptied of the event and its push first).
    sqlx::query("DELETE FROM push_deliveries; DELETE FROM parent_events")
        .execute(&app.db)
        .await
        .unwrap();
    let restore = crate::startup::AppliedRestore {
        filename: file.clone(),
        prerestore_dir: app.state.data_dir.join("backups/prerestore-x"),
        by: Some(("parent".to_string(), "Mamma".to_string())),
    };
    crate::startup::record_restore(&app.db, &restore).await;
    let logged = lines(&app, "backup_restored").await;
    assert_eq!(logged.len(), 1, "{logged:?}");
    assert_eq!(logged[0].0.as_deref(), Some("parent"));
    let told = events(&app, "backup_restored").await;
    assert_eq!(told.len(), 1, "{told:?}");
    assert!(told[0].1.contains("\"parent\":\"Mamma\""), "{told:?}");
    assert_eq!(
        pushes(&app, "backup_restored").await,
        0,
        "pushed from the database it replaced, not again"
    );
}

/// M2: each change is told once - a second save of the same choice tells nothing.
#[tokio::test]
async fn a_safety_change_is_told_once_per_real_change() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, _token) = app.enrolled_device("Emma").await;

    // An app allowed on the phone; allowing it again changes nothing.
    for _ in 0..2 {
        let res = app
            .request_form(
                Method::POST,
                &format!("/devices/{id}/apps/toggle"),
                Some(&cookie),
                &[("package_name", "com.example.game"), ("selected", "on")],
            )
            .await;
        assert_eq!(res.status, StatusCode::SEE_OTHER, "{}", res.text());
    }
    let told = events(&app, "app_added").await;
    assert_eq!(told.len(), 1, "{told:?}");
    assert_eq!(told[0].0, Some(id));
    assert!(
        told[0].1.contains("\"what\":\"com.example.game\""),
        "{told:?}"
    );

    // A new contact on the phone; the same number again adds no row.
    for _ in 0..2 {
        let mut fields = vec![("name", "Ola"), ("number", "+4791234567")];
        fields.extend(super::ADD_CONTACT_ALL_ON);
        let res = app
            .request_form(
                Method::POST,
                &format!("/devices/{id}/contacts"),
                Some(&cookie),
                &fields,
            )
            .await;
        assert_eq!(res.status, StatusCode::SEE_OTHER, "{}", res.text());
    }
    let told = events(&app, "contact_added").await;
    assert_eq!(told.len(), 1, "{told:?}");
    assert!(told[0].1.contains("\"what\":\"Ola\""), "{told:?}");

    // The family's own DNS server; the same pair saved again tells nothing, and the address is never in the event.
    let custom = [
        ("custom_server", "https://dns.example/dns-query/abc123"),
        ("custom_backup", "1.1.1.3"),
    ];
    for _ in 0..2 {
        let res = app
            .request_form(Method::POST, "/dns/upstream/custom", Some(&cookie), &custom)
            .await;
        assert_eq!(res.status, StatusCode::SEE_OTHER, "{}", res.text());
    }
    let told = events(&app, "custom_dns_set").await;
    assert_eq!(told.len(), 1, "{told:?}");
    assert_eq!(told[0].0, None);
    assert!(!told[0].1.contains("dns.example"), "{told:?}");

    // Location set to Regularly; again (another interval) and On Request tell nothing.
    for (mode, minutes) in [
        ("interval", Some("30")),
        ("interval", Some("60")),
        ("on_request", None),
    ] {
        let mut fields = vec![("mode", mode)];
        if let Some(minutes) = minutes {
            fields.push(("interval_minutes", minutes));
        }
        let res = app
            .request_form(
                Method::POST,
                &format!("/devices/{id}/location-policy"),
                Some(&cookie),
                &fields,
            )
            .await;
        assert_eq!(res.status, StatusCode::SEE_OTHER, "{}", res.text());
    }
    let told = events(&app, "location_regular").await;
    assert_eq!(told.len(), 1, "{told:?}");

    // Every one is pushed to every parent: none has a switch.
    for kind in [
        crate::parent_events::Kind::AppAdded,
        crate::parent_events::Kind::ContactAdded,
        crate::parent_events::Kind::CustomDnsSet,
        crate::parent_events::Kind::LocationRegular,
    ] {
        assert_eq!(kind.switch(), None, "{kind:?}");
    }
    // The inbox words them in the parent's language.
    let words = super::told_events(&app, id, crate::i18n::Lang::En).await;
    assert!(
        words
            .iter()
            .any(|(_, title, _)| title == "parent set Location to Regularly on Emma's phone"),
        "{words:?}"
    );
}
