//! Design 87: the apps' real logos - the contract with the launcher (`testdata/app_logos.json`), the report's
//! rows, the wanted list, the upload's answers, the files and the PWA's tiles.

use axum::body::Body;
use axum::http::{Method, Request, StatusCode, header};
use base64::Engine as _;
use serde_json::json;
use sha2::{Digest, Sha256};

use super::{Section, TestApp, TestResponse, device_page};
use crate::app_logos::{CAPABILITY, MAX_BYTES, MAX_LOGOS_PER_PHONE, MAX_WANTED, SIZE_PX};

const CONTRACT: &str = include_str!("../../testdata/app_logos.json");

fn sha(bytes: &[u8]) -> String {
    hex::encode(Sha256::digest(bytes))
}

/// A `side` px PNG of one colour (`shade` tells two apart), drawn by the image crate.
fn png(side: u32, shade: u8) -> Vec<u8> {
    let image = image::RgbaImage::from_pixel(side, side, image::Rgba([shade, 0x80, 0x40, 0xFF]));
    let mut out = std::io::Cursor::new(Vec::new());
    image.write_to(&mut out, image::ImageFormat::Png).unwrap();
    out.into_inner()
}

/// A status body from a launcher claiming `app_logos_v1` with these apps.
fn status_body(apps: serde_json::Value) -> serde_json::Value {
    json!({
        "lock_reason": "NONE",
        "kiosk_engaged": true,
        "capabilities": [CAPABILITY],
        "installed_apps": apps,
    })
}

/// One reported app, with `logo` when it is `Some`.
fn app_row(package: &str, label: &str, logo: Option<&str>) -> serde_json::Value {
    let mut row = json!({ "package_name": package, "label": label, "preinstalled": false });
    if let Some(logo) = logo {
        row["logo"] = json!(logo);
    }
    row
}

async fn report(app: &TestApp, token: &str, body: serde_json::Value) -> TestResponse {
    app.request(Method::POST, "/api/devices/status", Some(token), Some(body))
        .await
}

/// A report's wanted list (a 200).
async fn wanted(app: &TestApp, token: &str, body: serde_json::Value) -> serde_json::Value {
    let res = report(app, token, body).await;
    assert_eq!(res.status, StatusCode::OK, "{}", res.text());
    res.json()["wanted_logos"].clone()
}

async fn upload(
    app: &TestApp,
    token: &str,
    hash: &str,
    content_type: &str,
    body: Vec<u8>,
) -> TestResponse {
    let request = Request::builder()
        .method(Method::POST)
        .uri(format!("/api/devices/app-logos/{hash}"))
        .header(header::AUTHORIZATION, format!("Bearer {token}"))
        .header(header::CONTENT_TYPE, content_type)
        .body(Body::from(body))
        .unwrap();
    app.send(request).await
}

/// Reports `apps` and uploads every logo the answer asks for (each one of `logos`).
async fn report_and_upload(app: &TestApp, token: &str, apps: serde_json::Value, logos: &[&[u8]]) {
    for hash in wanted(app, token, status_body(apps))
        .await
        .as_array()
        .unwrap()
    {
        let hash = hash.as_str().unwrap();
        let body = logos.iter().find(|logo| sha(logo) == hash).unwrap();
        let res = upload(app, token, hash, "image/png", body.to_vec()).await;
        assert_eq!(res.status, StatusCode::NO_CONTENT, "{}", res.text());
    }
}

async fn rows(app: &TestApp, device: i64) -> Vec<(String, String)> {
    sqlx::query_as(
        "SELECT package_name, logo_hash FROM device_app_logos WHERE device_id = ? ORDER BY package_name",
    )
    .bind(device)
    .fetch_all(&app.db)
    .await
    .unwrap()
}

fn file(app: &TestApp, hash: &str) -> std::path::PathBuf {
    app.state.app_logo_dir.join(format!("{hash}.png"))
}

/// The shared contract: the report's answer, the upload and every answer row; the stored file is the server's own
/// encoding; the launcher's copy is identical.
#[tokio::test]
async fn the_contract() {
    let app = TestApp::new().await;
    let (_, token) = app.enrolled_device("Emma").await;
    let contract: serde_json::Value = serde_json::from_str(CONTRACT).unwrap();
    assert_eq!(contract["capability"], CAPABILITY);
    assert_eq!(contract["size_px"], SIZE_PX);
    assert_eq!(contract["max_bytes"], MAX_BYTES);
    assert_eq!(contract["max_per_phone"], MAX_LOGOS_PER_PHONE);
    assert_eq!(contract["max_wanted"], MAX_WANTED);
    let png_bytes = base64::engine::general_purpose::STANDARD
        .decode(contract["png_base64"].as_str().unwrap())
        .unwrap();
    let hash = contract["png_sha256"].as_str().unwrap();
    assert_eq!(sha(&png_bytes), hash);

    let res = report(&app, &token, contract["report"].clone()).await;
    assert_eq!(res.status, StatusCode::OK, "{}", res.text());
    assert_eq!(res.json(), contract["answer"]);
    let upload_row = &contract["upload"];
    assert_eq!(
        upload_row["path"],
        format!("/api/devices/app-logos/{hash}").as_str()
    );
    let content_type = upload_row["content_type"].as_str().unwrap();
    let res = upload(&app, &token, hash, content_type, png_bytes.clone()).await;
    assert_eq!(res.status, StatusCode::NO_CONTENT, "{}", res.text());
    // Stored: a second report wants nothing.
    let res = report(&app, &token, contract["report"].clone()).await;
    assert_eq!(res.json(), json!({ "wanted_logos": [] }));
    // Written again by the server: 128 x 128, without the test PNG's text chunk.
    let stored = std::fs::read(file(&app, hash)).unwrap();
    assert_ne!(stored, png_bytes);
    assert!(!stored.windows(4).any(|w| w == b"tEXt"));
    let image = image::load_from_memory_with_format(&stored, image::ImageFormat::Png).unwrap();
    assert_eq!((image.width(), image.height()), (SIZE_PX, SIZE_PX));

    // Two more apps whose logos the server will refuse; asked for by package name (none is allowed).
    let wrong_size = png(64, 1);
    let not_png = b"GIF89a, not a PNG".to_vec();
    let mut body = contract["report"].clone();
    let apps = body["installed_apps"].as_array_mut().unwrap();
    apps.push(app_row(
        "org.example.small",
        "Small",
        Some(&sha(&wrong_size)),
    ));
    apps.push(app_row("org.example.other", "Other", Some(&sha(&not_png))));
    assert_eq!(
        wanted(&app, &token, body.clone()).await,
        json!([sha(&not_png), sha(&wrong_size)])
    );
    let mut not_reported = png_bytes.clone();
    not_reported.extend_from_slice(b"appended");
    let too_large = vec![0u8; MAX_BYTES + 1];
    for row in contract["answers"].as_array().unwrap() {
        let case = row["case"].as_str().unwrap();
        let (path_hash, content_type, bytes) = match case {
            "stored" => (hash.to_string(), "image/png", png_bytes.clone()),
            "bad_hash" => (hash.to_uppercase(), "image/png", png_bytes.clone()),
            "mismatch" => (sha(b"other bytes"), "image/png", png_bytes.clone()),
            "type" => (hash.to_string(), "image/webp", png_bytes.clone()),
            "too_large" => (sha(&too_large), "image/png", too_large.clone()),
            "not_reported" => (sha(&not_reported), "image/png", not_reported.clone()),
            "wrong_size" => (sha(&wrong_size), "image/png", wrong_size.clone()),
            "not_png" => (sha(&not_png), "image/png", not_png.clone()),
            other => panic!("a case the test doesn't know: {other}"),
        };
        let res = upload(&app, &token, &path_hash, content_type, bytes).await;
        assert_eq!(
            u64::from(res.status.as_u16()),
            row["status"].as_u64().unwrap(),
            "{case}: {}",
            res.text()
        );
    }
    // A refused logo isn't asked for again (for a day).
    assert_eq!(wanted(&app, &token, body).await, json!([]));
    assert!(!file(&app, &sha(&wrong_size)).exists());

    // Gradle's and cargo's working directories differ; the copies are byte-identical.
    let launcher_copy =
        std::fs::read_to_string("../launcher/app/src/test/resources/app_logos.json").unwrap();
    assert_eq!(launcher_copy, CONTRACT);
}

/// R2-M5: a malformed logo is no logo, never a refused report; an older launcher gets 204 and no rows; a report
/// without `logo` keeps the row, one without the package drops it and the file; a missing file (a restore) is
/// wanted again.
#[tokio::test]
async fn the_rows_follow_the_reports_and_the_files_follow_the_rows() {
    let app = TestApp::new().await;
    let (id, token) = app.enrolled_device("Jack").await;
    let logo = png(SIZE_PX, 10);
    let hash = sha(&logo);

    // An older launcher: 204 as before, no rows.
    let res = report(
        &app,
        &token,
        json!({
            "lock_reason": "NONE",
            "kiosk_engaged": true,
            "installed_apps": [app_row("org.example.drawing", "Drawing", None)],
        }),
    )
    .await;
    assert_eq!(res.status, StatusCode::NO_CONTENT, "{}", res.text());
    assert!(res.body.is_empty());
    assert!(rows(&app, id).await.is_empty());

    // A number, an object and an uppercase hash: the apps are stored, without a logo.
    let mut apps = json!([
        app_row("org.example.drawing", "Drawing", None),
        app_row("org.example.notes", "Notes", None),
        app_row("org.example.maps", "Maps", Some(&hash.to_uppercase())),
    ]);
    apps[0]["logo"] = json!(42);
    apps[1]["logo"] = json!({ "a": 1 });
    assert_eq!(wanted(&app, &token, status_body(apps)).await, json!([]));
    assert!(rows(&app, id).await.is_empty());
    let snapshot: String = sqlx::query_scalar(
        "SELECT installed_apps_json FROM device_status WHERE device_id = ? ORDER BY id DESC LIMIT 1",
    )
    .bind(id)
    .fetch_one(&app.db)
    .await
    .unwrap();
    assert!(snapshot.contains("org.example.maps"), "{snapshot}");
    assert!(!snapshot.contains("logo"), "{snapshot}");

    // A real one: its row, wanted, uploaded - and never in the stored snapshot.
    let with_logo = json!([
        app_row("org.example.drawing", "Drawing", Some(&hash)),
        app_row("org.example.notes", "Notes", None),
    ]);
    report_and_upload(&app, &token, with_logo.clone(), &[&logo]).await;
    assert!(file(&app, &hash).exists());
    let snapshot: String = sqlx::query_scalar(
        "SELECT installed_apps_json FROM device_status WHERE device_id = ? ORDER BY id DESC LIMIT 1",
    )
    .bind(id)
    .fetch_one(&app.db)
    .await
    .unwrap();
    assert!(!snapshot.contains(&hash), "{snapshot}");

    // Without `logo` the row stays.
    let without = json!([
        app_row("org.example.drawing", "Drawing", None),
        app_row("org.example.notes", "Notes", None),
    ]);
    assert_eq!(wanted(&app, &token, status_body(without)).await, json!([]));
    assert_eq!(
        rows(&app, id).await,
        [("org.example.drawing".to_string(), hash.clone())]
    );

    // A restore brings the rows back without the files: the next report wants it again.
    std::fs::remove_file(file(&app, &hash)).unwrap();
    assert_eq!(
        wanted(&app, &token, status_body(with_logo.clone())).await,
        json!([hash])
    );
    let res = upload(&app, &token, &hash, "image/png", logo.clone()).await;
    assert_eq!(res.status, StatusCode::NO_CONTENT, "{}", res.text());

    // The package gone from the report: its row and its file go.
    let gone = json!([app_row("org.example.notes", "Notes", None)]);
    assert_eq!(wanted(&app, &token, status_body(gone)).await, json!([]));
    assert!(rows(&app, id).await.is_empty());
    assert!(!file(&app, &hash).exists());
}

/// R2-M2: a phone whose rows name 300 stored files is asked for nothing more, while another phone still is.
#[tokio::test]
async fn a_full_phone_is_asked_for_nothing_more() {
    let app = TestApp::new().await;
    let (full, full_token) = app.enrolled_device("Lily").await;
    let (_, other_token) = app.enrolled_device("Emma").await;
    std::fs::create_dir_all(&*app.state.app_logo_dir).unwrap();
    for n in 0..MAX_LOGOS_PER_PHONE {
        let hash = sha(n.to_string().as_bytes());
        std::fs::write(file(&app, &hash), b"x").unwrap();
        sqlx::query(
            "INSERT INTO device_app_logos (device_id, package_name, logo_hash, updated_at) \
             VALUES (?, ?, ?, datetime('now'))",
        )
        .bind(full)
        .bind(format!("org.example.app{n}"))
        .bind(&hash)
        .execute(&app.db)
        .await
        .unwrap();
    }
    let new = sha(b"a new logo");
    let mut apps: Vec<serde_json::Value> = (0..MAX_LOGOS_PER_PHONE)
        .map(|n| app_row(&format!("org.example.app{n}"), "App", None))
        .collect();
    apps.push(app_row("org.example.new", "New", Some(&new)));
    assert_eq!(
        wanted(&app, &full_token, status_body(json!(apps))).await,
        json!([])
    );
    let res = upload(&app, &full_token, &new, "image/png", b"a new logo".to_vec()).await;
    assert_eq!(res.status, StatusCode::CONFLICT);
    let apps = json!([app_row("org.example.new", "New", Some(&new))]);
    assert_eq!(
        wanted(&app, &other_token, status_body(apps)).await,
        json!([new])
    );
}

/// R2-M2: a phone whose rows name 12 MB of stored files is asked for nothing more.
#[tokio::test]
async fn a_phone_over_its_bytes_is_asked_for_nothing_more() {
    let app = TestApp::new().await;
    let (full, token) = app.enrolled_device("Jack").await;
    std::fs::create_dir_all(&*app.state.app_logo_dir).unwrap();
    let big = sha(b"a big one");
    let size = usize::try_from(crate::app_logos::MAX_LOGO_BYTES_PER_PHONE).unwrap();
    std::fs::write(file(&app, &big), vec![1u8; size]).unwrap();
    sqlx::query(
        "INSERT INTO device_app_logos (device_id, package_name, logo_hash, updated_at) \
         VALUES (?, 'org.example.big', ?, datetime('now'))",
    )
    .bind(full)
    .bind(&big)
    .execute(&app.db)
    .await
    .unwrap();
    let apps = json!([
        app_row("org.example.big", "Big", None),
        app_row("org.example.new", "New", Some(&sha(b"new"))),
    ]);
    assert_eq!(wanted(&app, &token, status_body(apps)).await, json!([]));
}

/// §4.2: the allowed apps' logos are asked for first, then the rest by package name.
#[tokio::test]
async fn allowed_apps_are_asked_for_first() {
    let app = TestApp::new().await;
    let (id, token) = app.enrolled_device("Lily").await;
    sqlx::query(
        "UPDATE device_policy SET allowlist_json = '[\"org.example.zeta\"]' WHERE device_id = ?",
    )
    .bind(id)
    .execute(&app.db)
    .await
    .unwrap();
    let (alpha, beta, zeta) = (sha(b"alpha"), sha(b"beta"), sha(b"zeta"));
    let apps = json!([
        app_row("org.example.beta", "Beta", Some(&beta)),
        app_row("org.example.zeta", "Zeta", Some(&zeta)),
        app_row("org.example.alpha", "Alpha", Some(&alpha)),
    ]);
    assert_eq!(
        wanted(&app, &token, status_body(apps)).await,
        json!([zeta, alpha, beta])
    );
}

/// §6: only a valid hash some phone names, behind the session, cached for good (the phones' port: `listeners`).
#[tokio::test]
async fn only_a_named_logo_is_served_behind_the_session() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (_, token) = app.enrolled_device("Jack").await;
    let logo = png(SIZE_PX, 40);
    let hash = sha(&logo);
    report_and_upload(
        &app,
        &token,
        json!([app_row("org.example.drawing", "Drawing", Some(&hash))]),
        &[&logo],
    )
    .await;

    let res = app.get_page(&format!("/app-logos/{hash}"), &cookie).await;
    assert_eq!(res.status, StatusCode::OK);
    assert_eq!(res.headers.get(header::CONTENT_TYPE).unwrap(), "image/png");
    assert_eq!(
        res.headers.get(header::X_CONTENT_TYPE_OPTIONS).unwrap(),
        "nosniff"
    );
    assert_eq!(
        res.headers.get(header::CACHE_CONTROL).unwrap(),
        "private, max-age=31536000, immutable"
    );
    assert_eq!(res.body, std::fs::read(file(&app, &hash)).unwrap());

    let unnamed = sha(b"no phone names this");
    std::fs::write(file(&app, &unnamed), b"x").unwrap();
    for bad in [
        hash.to_uppercase(),
        hash[..63].to_string(),
        format!("{hash}a"),
        format!("{hash}.png"),
        "%2E%2E%2Fkidphone.db".to_string(),
        unnamed,
    ] {
        let res = app.get_page(&format!("/app-logos/{bad}"), &cookie).await;
        assert_eq!(res.status, StatusCode::NOT_FOUND, "{bad}");
    }

    let res = app
        .send(
            Request::builder()
                .uri(format!("/app-logos/{hash}"))
                .body(Body::empty())
                .unwrap(),
        )
        .await;
    assert_ne!(res.status, StatusCode::OK);
    assert!(!res.body.starts_with(b"\x89PNG"));
}

/// §8: Device › Apps shows each phone's own logo (Q3), the letter without one, a chosen glyph over it - a phone
/// row with "App logo" over a catalog glyph (R2-L5); the Apps tab takes the hash most phones name (R2-L4); a
/// deleted phone's only logos go.
#[tokio::test]
async fn the_pages_show_the_logos() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (emma, emma_token) = app.enrolled_device("Emma").await;
    let (_, jack_token) = app.enrolled_device("Jack").await;
    let (lily, lily_token) = app.enrolled_device("Lily").await;
    let common = png(SIZE_PX, 50);
    let own = png(SIZE_PX, 60);
    for (token, logo) in [
        (&emma_token, &common),
        (&jack_token, &common),
        (&lily_token, &own),
    ] {
        let apps = json!([
            app_row("org.example.drawing", "Drawing", Some(&sha(logo))),
            app_row("org.example.notes", "Notes", None),
        ]);
        report_and_upload(&app, token, apps, &[logo]).await;
    }
    let tile = |hash: &str| format!("<img src=\"/app-logos/{hash}\"");

    let emma_page = app
        .get_page(&device_page(emma, Section::Apps), &cookie)
        .await
        .text();
    assert!(emma_page.contains(&tile(&sha(&common))), "{emma_page}");
    assert!(!emma_page.contains(&tile(&sha(&own))));
    assert!(emma_page.contains("<span class=\"app-row-tile\" aria-hidden=\"true\">N</span>"));
    let lily_page = app
        .get_page(&device_page(lily, Section::Apps), &cookie)
        .await
        .text();
    assert!(lily_page.contains(&tile(&sha(&own))), "{lily_page}");

    // The Apps tab: the logo two phones name, not the third's.
    let catalog: i64 = sqlx::query_scalar(
        "INSERT INTO tracked_apps (name, package_name, github_repo) VALUES ('Drawing', 'org.example.drawing', 'o/r') \
         RETURNING id",
    )
    .fetch_one(&app.db)
    .await
    .unwrap();
    let apps_tab = app.get_page("/apps", &cookie).await.text();
    assert!(apps_tab.contains(&tile(&sha(&common))), "{apps_tab}");
    assert!(!apps_tab.contains(&tile(&sha(&own))));

    // A catalog glyph wins on the Apps tab and on a phone without a row of its own; Emma's row with "App logo"
    // beats it.
    sqlx::query("UPDATE tracked_apps SET display_icon = 'chat' WHERE id = ?")
        .bind(catalog)
        .execute(&app.db)
        .await
        .unwrap();
    let apps_tab = app.get_page("/apps", &cookie).await.text();
    assert!(!apps_tab.contains(&tile(&sha(&common))));
    let lily_page = app
        .get_page(&device_page(lily, Section::Apps), &cookie)
        .await
        .text();
    assert!(!lily_page.contains(&tile(&sha(&own))));
    assert!(lily_page.contains("/static/app-icons/chat.svg"));
    sqlx::query(
        "INSERT INTO device_app_display (device_id, package_name, icon_key) VALUES (?, 'org.example.drawing', 'own')",
    )
    .bind(emma)
    .execute(&app.db)
    .await
    .unwrap();
    let emma_page = app
        .get_page(&device_page(emma, Section::Apps), &cookie)
        .await
        .text();
    assert!(emma_page.contains(&tile(&sha(&common))), "{emma_page}");

    // Deleting Lily's phone deletes the logo only it named.
    let res = app
        .request_form(
            Method::POST,
            &format!("/devices/{lily}/delete"),
            Some(&cookie),
            &[],
        )
        .await;
    assert!(res.status.is_redirection());
    assert!(!file(&app, &sha(&own)).exists());
    assert!(file(&app, &sha(&common)).exists());
}
