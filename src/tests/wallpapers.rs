//! Curated wallpapers (design 08-ui-polish.md, QA qa-08-design.md): the built-ins, uploads
//! (upright, cut to the phone's shape, no metadata), which phone may use which, the device image
//! route, delete/prune, backups and recovery - and that the wallpaper store never touches
//! contact photos (or the other way round).

use std::io::Cursor;

use axum::body::Body;
use axum::http::{Method, Request, StatusCode, header};
use image::{DynamicImage, ImageFormat, Rgb, RgbImage};
use serde_json::json;
use sha2::{Digest, Sha256};

use super::{TestApp, TestResponse};

async fn wallpapers(app: &TestApp, token: &str) -> Vec<serde_json::Value> {
    let res = app
        .request(Method::GET, "/api/devices/policy", Some(token), None)
        .await;
    assert_eq!(res.status, StatusCode::OK);
    res.json()["launcher_ui"]["wallpapers"]
        .as_array()
        .expect("wallpapers always sent")
        .clone()
}

fn encode(image: &DynamicImage, format: ImageFormat) -> Vec<u8> {
    let mut out = Cursor::new(Vec::new());
    image.write_to(&mut out, format).unwrap();
    out.into_inner()
}

/// `w`×`h`, the top half black and the bottom half white.
fn top_black(w: u32, h: u32) -> DynamicImage {
    DynamicImage::ImageRgb8(RgbImage::from_fn(w, h, |_, y| {
        if y < h / 2 {
            Rgb([0, 0, 0])
        } else {
            Rgb([255, 255, 255])
        }
    }))
}

/// `w`×`h`, the left half black and the right half white.
fn left_black(w: u32, h: u32) -> DynamicImage {
    DynamicImage::ImageRgb8(RgbImage::from_fn(w, h, |x, _| {
        if x < w / 2 {
            Rgb([0, 0, 0])
        } else {
            Rgb([255, 255, 255])
        }
    }))
}

/// A JPEG with EXIF orientation 6 (rotate 90° clockwise) and a fake GPS string - what a phone
/// camera writes for a portrait photo.
fn jpeg_with_orientation_6(image: &DynamicImage) -> Vec<u8> {
    let jpeg = encode(image, ImageFormat::Jpeg);
    let mut tiff = b"MM\x00\x2a\x00\x00\x00\x08".to_vec();
    tiff.extend_from_slice(&[0x00, 0x01]);
    tiff.extend_from_slice(&[
        0x01, 0x12, 0x00, 0x03, 0x00, 0x00, 0x00, 0x01, 0x00, 0x06, 0x00, 0x00,
    ]);
    tiff.extend_from_slice(&[0, 0, 0, 0]);
    tiff.extend_from_slice(b"GPSLatitude 59.9139 N secret-place");
    let mut app1 = b"Exif\x00\x00".to_vec();
    app1.extend_from_slice(&tiff);
    let len = (app1.len() + 2) as u16;
    let mut out = jpeg[..2].to_vec();
    out.extend_from_slice(&[0xFF, 0xE1]);
    out.extend_from_slice(&len.to_be_bytes());
    out.extend_from_slice(&app1);
    out.extend_from_slice(&jpeg[2..]);
    out
}

async fn upload(
    app: &TestApp,
    cookie: &str,
    label: &str,
    lock: bool,
    bytes: &[u8],
) -> TestResponse {
    const BOUNDARY: &str = "handy-wallpaper-boundary";
    let mut body = format!(
        "--{BOUNDARY}\r\nContent-Disposition: form-data; name=\"label\"\r\n\r\n{label}\r\n"
    )
    .into_bytes();
    if lock {
        body.extend_from_slice(
            format!(
                "--{BOUNDARY}\r\nContent-Disposition: form-data; name=\"lock_screen\"\r\n\r\non\r\n"
            )
            .as_bytes(),
        );
    }
    body.extend_from_slice(
        format!(
            "--{BOUNDARY}\r\nContent-Disposition: form-data; name=\"image\"; filename=\"w.jpg\"\r\n\
             Content-Type: application/octet-stream\r\n\r\n"
        )
        .as_bytes(),
    );
    body.extend_from_slice(bytes);
    body.extend_from_slice(format!("\r\n--{BOUNDARY}--\r\n").as_bytes());
    let request = Request::builder()
        .method(Method::POST)
        .uri("/wallpapers")
        .header(header::COOKIE, cookie)
        .header(
            header::CONTENT_TYPE,
            format!("multipart/form-data; boundary={BOUNDARY}"),
        )
        .body(Body::from(body))
        .unwrap();
    app.send(request).await
}

/// The newest upload's `(id, hash)`.
async fn newest(app: &TestApp) -> (i64, String) {
    sqlx::query_as(
        "SELECT id, image_hash FROM wallpapers WHERE kind = 'image' ORDER BY id DESC LIMIT 1",
    )
    .fetch_one(&app.db)
    .await
    .unwrap()
}

fn files(dir: &std::path::Path) -> Vec<String> {
    let mut names: Vec<String> = std::fs::read_dir(dir)
        .map(|d| {
            d.filter_map(|e| e.ok())
                .map(|e| e.file_name().to_string_lossy().into_owned())
                .collect()
        })
        .unwrap_or_default();
    names.sort();
    names
}

async fn tick(app: &TestApp, cookie: &str, device: i64, ids: &[i64]) -> TestResponse {
    let values: Vec<String> = ids.iter().map(i64::to_string).collect();
    let fields: Vec<(&str, &str)> = values.iter().map(|v| ("wallpaper", v.as_str())).collect();
    app.request_form(
        Method::POST,
        &format!("/devices/{device}/wallpapers"),
        Some(cookie),
        &fields,
    )
    .await
}

async fn fetch(app: &TestApp, token: &str, hash: &str) -> TestResponse {
    app.request(
        Method::GET,
        &format!("/api/devices/wallpapers/{hash}"),
        Some(token),
        None,
    )
    .await
}

#[tokio::test]
async fn every_phone_gets_the_builtins_with_every_key() {
    let app = TestApp::new().await;
    let (_, token) = app.enrolled_device("phone").await;
    let list = wallpapers(&app, &token).await;
    let keys: Vec<&str> = list
        .iter()
        .map(|w| w["builtin_key"].as_str().unwrap())
        .collect();
    assert_eq!(keys, ["navy", "forest", "plum", "green", "sky", "sunset"]);
    assert_eq!(
        list[0],
        json!({
            "id": 1, "kind": "color", "colors": ["#14213D"], "image": null,
            "label": "Navy", "builtin_key": "navy", "lock_screen": false
        })
    );
    assert_eq!(list[5]["kind"], "gradient");
    assert_eq!(list[5]["colors"], json!(["#F76707", "#862E9C"]));
    for w in &list {
        let mut k: Vec<&str> = w.as_object().unwrap().keys().map(String::as_str).collect();
        k.sort_unstable();
        assert_eq!(
            k,
            [
                "builtin_key",
                "colors",
                "id",
                "image",
                "kind",
                "label",
                "lock_screen"
            ]
        );
    }
}

#[tokio::test]
async fn uploads_are_upright_cut_to_the_phone_and_on_no_phone_at_first() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("phone").await;
    let (_, other_token) = app.enrolled_device("other").await;

    // A phone-camera portrait: stored landscape (2400×1600, left half black) with EXIF 6.
    // Upright it is 1600×2400 with the black half on top; cut to 9:20 that is 1080×2400.
    let res = upload(
        &app,
        &cookie,
        "Hytta",
        false,
        &jpeg_with_orientation_6(&left_black(2400, 1600)),
    )
    .await;
    assert_eq!(res.location(), Some("/wallpapers"), "{}", res.text());
    let (portrait, hash) = newest(&app).await;
    assert_eq!(files(&app.state.wallpaper_dir), [format!("{hash}.jpg")]);
    // Nothing reached the contact-photo store.
    assert!(files(&app.state.photo_dir).is_empty());

    // On no phone yet: not in the policy, and the route says 404.
    assert!(
        wallpapers(&app, &token)
            .await
            .iter()
            .all(|w| w["id"] != json!(portrait))
    );
    assert_eq!(
        fetch(&app, &token, &hash).await.status,
        StatusCode::NOT_FOUND
    );

    // Ticked on this phone (with navy): in its policy, fetchable, upright, no metadata.
    let mut nudges = app.state.command_notify.subscribe();
    let res = tick(&app, &cookie, id, &[1, portrait]).await;
    assert_eq!(res.location(), Some(format!("/devices/{id}").as_str()));
    assert_eq!(nudges.try_recv().ok(), Some(id));
    let list = wallpapers(&app, &token).await;
    assert_eq!(list.len(), 2);
    assert_eq!(
        list[1],
        json!({
            "id": portrait, "kind": "image", "colors": [], "image": hash,
            "label": "Hytta", "builtin_key": null, "lock_screen": false
        })
    );
    let res = fetch(&app, &token, &hash).await;
    assert_eq!(res.status, StatusCode::OK);
    assert_eq!(res.headers[header::CONTENT_TYPE], "image/jpeg");
    assert_eq!(hex::encode(Sha256::digest(&res.body)), hash);
    assert!(!res.body.windows(4).any(|w| w == b"Exif"));
    assert!(!res.body.windows(6).any(|w| w == b"secret"));
    let stored = image::load_from_memory(&res.body).unwrap().into_rgb8();
    assert_eq!(stored.dimensions(), (1080, 2400));
    assert!(stored.get_pixel(540, 100).0[0] < 60, "top should be black");
    assert!(
        stored.get_pixel(540, 2300).0[0] > 200,
        "bottom should be white"
    );

    // Another phone's token: 404; no token: 401; a bad hash: 404.
    assert_eq!(
        fetch(&app, &other_token, &hash).await.status,
        StatusCode::NOT_FOUND
    );
    let res = app
        .request(
            Method::GET,
            &format!("/api/devices/wallpapers/{hash}"),
            None,
            None,
        )
        .await;
    assert_eq!(res.status, StatusCode::UNAUTHORIZED);
    assert_eq!(
        fetch(&app, &token, "..%2Ftest.db").await.status,
        StatusCode::NOT_FOUND
    );

    // A landscape photo (4000×2400, top half black) keeps its middle: 1080×2400, upright.
    let res = upload(
        &app,
        &cookie,
        "",
        true,
        &encode(&top_black(4000, 2400), ImageFormat::Png),
    )
    .await;
    assert_eq!(res.status, StatusCode::SEE_OTHER, "{}", res.text());
    let (landscape, hash2) = newest(&app).await;
    let label: String = sqlx::query_scalar("SELECT label FROM wallpapers WHERE id = ?")
        .bind(landscape)
        .fetch_one(&app.db)
        .await
        .unwrap();
    assert_eq!(label, "Photo");
    let bytes = std::fs::read(app.state.wallpaper_dir.join(format!("{hash2}.jpg"))).unwrap();
    let stored = image::load_from_memory(&bytes).unwrap().into_rgb8();
    assert_eq!(stored.dimensions(), (1080, 2400));
    assert!(stored.get_pixel(540, 100).0[0] < 60);
    assert!(stored.get_pixel(540, 2300).0[0] > 200);

    // A small one is cut but never enlarged: 300×300 -> 135×300.
    upload(
        &app,
        &cookie,
        "small",
        false,
        &encode(&top_black(300, 300), ImageFormat::Png),
    )
    .await;
    let (_, hash3) = newest(&app).await;
    let bytes = std::fs::read(app.state.wallpaper_dir.join(format!("{hash3}.jpg"))).unwrap();
    assert_eq!(
        image::load_from_memory(&bytes)
            .unwrap()
            .into_rgb8()
            .dimensions(),
        (135, 300)
    );

    // The pages show them.
    let page = app.get_page("/wallpapers", &cookie).await.text();
    assert!(page.contains(&format!("src=\"/wallpaper-images/{hash}\"")));
    assert!(page.contains("Hytta"));
    let page = app
        .get_page(&format!("/devices/{id}"), &cookie)
        .await
        .text();
    assert!(page.contains(&format!("action=\"/devices/{id}/wallpapers\"")));
    assert!(page.contains(&format!("value=\"{portrait}\" checked")));
    assert_eq!(
        app.get_page(&format!("/wallpaper-images/{hash}"), &cookie)
            .await
            .status,
        StatusCode::OK
    );
}

#[tokio::test]
async fn bad_uploads_store_nothing() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    for bytes in [
        b"GIF89a\x01\x00\x01\x00\x00\x00\x00;".to_vec(),
        b"not an image at all".to_vec(),
        b"\x89PNG\r\n\x1a\n broken".to_vec(),
        Vec::new(),
        vec![0xFF; crate::photos::MAX_UPLOAD_BYTES + 1],
    ] {
        let res = upload(&app, &cookie, "x", false, &bytes).await;
        assert!(
            res.status == StatusCode::BAD_REQUEST || res.status == StatusCode::PAYLOAD_TOO_LARGE,
            "{}",
            res.status
        );
    }
    let uploads: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM wallpapers WHERE kind = 'image'")
        .fetch_one(&app.db)
        .await
        .unwrap();
    assert_eq!(uploads, 0);
    assert!(files(&app.state.wallpaper_dir).is_empty());
}

#[tokio::test]
async fn unticking_or_deleting_takes_it_off_the_phone_and_prunes_only_wallpapers() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("phone").await;
    upload(
        &app,
        &cookie,
        "a",
        false,
        &encode(&top_black(90, 200), ImageFormat::Png),
    )
    .await;
    let (a, hash_a) = newest(&app).await;
    tick(&app, &cookie, id, &[a]).await;
    assert_eq!(fetch(&app, &token, &hash_a).await.status, StatusCode::OK);

    // Unticked: gone from the policy and the route, the file stays for the other pages.
    tick(&app, &cookie, id, &[1]).await;
    assert_eq!(wallpapers(&app, &token).await.len(), 1);
    assert_eq!(
        fetch(&app, &token, &hash_a).await.status,
        StatusCode::NOT_FOUND
    );
    // An unknown id changes nothing.
    assert_eq!(
        tick(&app, &cookie, id, &[999]).await.status,
        StatusCode::BAD_REQUEST
    );
    assert_eq!(wallpapers(&app, &token).await.len(), 1);
    assert_eq!(
        tick(&app, &cookie, 999, &[1]).await.status,
        StatusCode::NOT_FOUND
    );

    // A contact photo and a stray file sit in the photo store; deleting the wallpaper prunes
    // the wallpaper store only.
    std::fs::create_dir_all(&*app.state.photo_dir).unwrap();
    let stray = format!("{}.jpg", "c".repeat(64));
    std::fs::write(app.state.photo_dir.join(&stray), b"x").unwrap();
    tick(&app, &cookie, id, &[1, a]).await;
    let mut nudges = app.state.command_notify.subscribe();
    let res = app
        .request_form(
            Method::POST,
            &format!("/wallpapers/{a}/delete"),
            Some(&cookie),
            &[],
        )
        .await;
    assert_eq!(res.location(), Some("/wallpapers"));
    assert_eq!(nudges.try_recv().ok(), Some(id));
    assert!(files(&app.state.wallpaper_dir).is_empty());
    assert_eq!(files(&app.state.photo_dir), [stray]);
    let rows: i64 =
        sqlx::query_scalar("SELECT COUNT(*) FROM device_wallpapers WHERE wallpaper_id = ?")
            .bind(a)
            .fetch_one(&app.db)
            .await
            .unwrap();
    assert_eq!(rows, 0);
    assert_eq!(
        fetch(&app, &token, &hash_a).await.status,
        StatusCode::NOT_FOUND
    );

    // And the contact-photo prune never looks at wallpapers.
    upload(
        &app,
        &cookie,
        "b",
        false,
        &encode(&top_black(90, 200), ImageFormat::Png),
    )
    .await;
    let (_, hash_b) = newest(&app).await;
    crate::photos::prune(&app.state).await;
    assert_eq!(files(&app.state.wallpaper_dir), [format!("{hash_b}.jpg")]);
    assert!(files(&app.state.photo_dir).is_empty());

    // Built-ins can't be deleted.
    let res = app
        .request_form(Method::POST, "/wallpapers/1/delete", Some(&cookie), &[])
        .await;
    assert_eq!(res.status, StatusCode::BAD_REQUEST);
}

#[tokio::test]
async fn lock_screen_choice_reaches_the_phone() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("phone").await;
    upload(
        &app,
        &cookie,
        "a",
        false,
        &encode(&top_black(90, 200), ImageFormat::Png),
    )
    .await;
    let (a, _) = newest(&app).await;
    tick(&app, &cookie, id, &[a]).await;
    assert_eq!(
        wallpapers(&app, &token).await[0]["lock_screen"],
        json!(false)
    );
    let mut nudges = app.state.command_notify.subscribe();
    let res = app
        .request_form(
            Method::POST,
            &format!("/wallpapers/{a}/lock-screen"),
            Some(&cookie),
            &[("lock_screen", "on")],
        )
        .await;
    assert_eq!(res.location(), Some("/wallpapers"));
    assert_eq!(nudges.try_recv().ok(), Some(id));
    assert_eq!(
        wallpapers(&app, &token).await[0]["lock_screen"],
        json!(true)
    );
    // Colours have no such choice.
    let res = app
        .request_form(
            Method::POST,
            "/wallpapers/1/lock-screen",
            Some(&cookie),
            &[("lock_screen", "on")],
        )
        .await;
    assert_eq!(res.status, StatusCode::BAD_REQUEST);
}

#[tokio::test]
async fn backups_carry_wallpapers_and_a_restore_gets_them_back() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("phone").await;
    upload(
        &app,
        &cookie,
        "a",
        false,
        &encode(&top_black(90, 200), ImageFormat::Png),
    )
    .await;
    let (a, hash) = newest(&app).await;
    tick(&app, &cookie, id, &[a]).await;

    let backups = tempfile::tempdir().unwrap();
    let db = backups.path().join("db");
    std::fs::write(&db, b"not really a database").unwrap();
    let zip_path = backups.path().join("backup-20261005-120000.zip");
    crate::handlers::backups::build_backup_zip(
        db.to_str().unwrap(),
        zip_path.to_str().unwrap(),
        &app.state.photo_dir,
        &app.state.wallpaper_dir,
    )
    .unwrap();
    let mut archive = zip::ZipArchive::new(std::fs::File::open(&zip_path).unwrap()).unwrap();
    assert!(archive.by_name(&format!("wallpapers/{hash}.jpg")).is_ok());
    drop(archive);

    // "Restore": files gone; a second upload's file is in no backup.
    upload(
        &app,
        &cookie,
        "ghost",
        false,
        &encode(&top_black(80, 200), ImageFormat::Png),
    )
    .await;
    let (ghost, _) = newest(&app).await;
    std::fs::remove_dir_all(&*app.state.wallpaper_dir).unwrap();
    crate::photos::recover_missing(&app.state, backups.path()).await;
    assert_eq!(files(&app.state.wallpaper_dir), [format!("{hash}.jpg")]);
    assert_eq!(fetch(&app, &token, &hash).await.status, StatusCode::OK);
    let left: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM wallpapers WHERE id = ?")
        .bind(ghost)
        .fetch_one(&app.db)
        .await
        .unwrap();
    assert_eq!(left, 0, "a wallpaper whose image is lost is deleted");
}

#[tokio::test]
async fn a_wallpaper_query_error_never_costs_the_phone_its_policy() {
    let app = TestApp::new().await;
    let (_, token) = app.enrolled_device("phone").await;
    // Break the wallpaper query (the trigger names the table, so it goes first).
    for sql in [
        "DROP TRIGGER devices_get_builtin_wallpapers",
        "DROP TABLE device_wallpapers",
    ] {
        sqlx::query(sql).execute(&app.db).await.unwrap();
    }
    let res = app
        .request(Method::GET, "/api/devices/policy", Some(&token), None)
        .await;
    assert_eq!(res.status, StatusCode::OK);
    let policy = res.json();
    assert_eq!(policy["launcher_ui"]["wallpapers"], json!([]));
    assert!(policy["time_policy"].is_object());
    assert!(policy["call_policy"].is_object());
}

#[cfg(unix)]
#[test]
fn a_file_deleted_during_a_backup_is_skipped() {
    // A dangling link reads as NotFound - exactly what a prune between read_dir and read gives.
    let dir = tempfile::tempdir().unwrap();
    let gone = format!("{}.jpg", "d".repeat(64));
    std::os::unix::fs::symlink(dir.path().join("nowhere"), dir.path().join(&gone)).unwrap();
    let kept = "e".repeat(64);
    std::fs::write(dir.path().join(format!("{kept}.jpg")), b"jpeg").unwrap();
    let mut writer = zip::ZipWriter::new(Cursor::new(Vec::new()));
    crate::photos::WALLPAPERS
        .add_to_zip(&mut writer, dir.path())
        .expect("a vanished file doesn't fail the backup");
    let mut archive = zip::ZipArchive::new(writer.finish().unwrap()).unwrap();
    assert!(archive.by_name(&format!("wallpapers/{kept}.jpg")).is_ok());
    assert_eq!(archive.len(), 1);
}
