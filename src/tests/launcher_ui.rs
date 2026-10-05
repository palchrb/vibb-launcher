//! Launcher UI, contact photos and the notification-listener warning (design
//! 05-ui-photos-i18n.md): `launcher_ui` in the policy and its form, photo upload/re-encode/remove,
//! the device photo route and its scoping.

use std::io::Cursor;

use axum::body::Body;
use axum::http::{Method, Request, StatusCode, header};
use image::{DynamicImage, ImageFormat, Rgb, RgbImage};
use serde_json::json;
use sha2::{Digest, Sha256};

use super::{TestApp, TestResponse};

async fn policy(app: &TestApp, token: &str) -> serde_json::Value {
    let res = app
        .request(Method::GET, "/api/devices/policy", Some(token), None)
        .await;
    assert_eq!(res.status, StatusCode::OK);
    res.json()
}

/// Manages calls on `device` and attaches a contact; returns its id.
async fn contact(app: &TestApp, device: i64, name: &str, number: &str) -> i64 {
    sqlx::query("UPDATE device_policy SET calls_managed = 1 WHERE device_id = ?")
        .bind(device)
        .execute(&app.db)
        .await
        .unwrap();
    let id: i64 = sqlx::query_scalar(
        "INSERT INTO contacts (name, phone_number) VALUES (?, ?) \
         ON CONFLICT(phone_number) DO UPDATE SET name = excluded.name RETURNING id",
    )
    .bind(name)
    .bind(number)
    .fetch_one(&app.db)
    .await
    .unwrap();
    sqlx::query("INSERT INTO device_contacts (device_id, contact_id) VALUES (?, ?)")
        .bind(device)
        .bind(id)
        .execute(&app.db)
        .await
        .unwrap();
    id
}

async fn upload(
    app: &TestApp,
    cookie: &str,
    device: i64,
    contact: i64,
    bytes: &[u8],
) -> TestResponse {
    const BOUNDARY: &str = "handy-test-boundary";
    let mut body = format!(
        "--{BOUNDARY}\r\nContent-Disposition: form-data; name=\"photo\"; filename=\"p.jpg\"\r\n\
         Content-Type: application/octet-stream\r\n\r\n"
    )
    .into_bytes();
    body.extend_from_slice(bytes);
    body.extend_from_slice(format!("\r\n--{BOUNDARY}--\r\n").as_bytes());
    let request = Request::builder()
        .method(Method::POST)
        .uri(format!("/devices/{device}/contacts/{contact}/photo"))
        .header(header::COOKIE, cookie)
        .header(
            header::CONTENT_TYPE,
            format!("multipart/form-data; boundary={BOUNDARY}"),
        )
        .body(Body::from(body))
        .unwrap();
    app.send(request).await
}

async fn photo_hash(app: &TestApp, contact: i64) -> Option<String> {
    sqlx::query_scalar("SELECT photo_hash FROM contacts WHERE id = ?")
        .bind(contact)
        .fetch_one(&app.db)
        .await
        .unwrap()
}

fn stored_files(app: &TestApp) -> Vec<String> {
    let mut names: Vec<String> = std::fs::read_dir(&*app.state.photo_dir)
        .map(|dir| {
            dir.filter_map(|e| e.ok())
                .map(|e| e.file_name().to_string_lossy().into_owned())
                .collect()
        })
        .unwrap_or_default();
    names.sort();
    names
}

fn encode(image: &DynamicImage, format: ImageFormat) -> Vec<u8> {
    let mut out = Cursor::new(Vec::new());
    image.write_to(&mut out, format).unwrap();
    out.into_inner()
}

/// 200×100, left half black, right half white.
fn half_and_half() -> DynamicImage {
    DynamicImage::ImageRgb8(RgbImage::from_fn(200, 100, |x, _| {
        if x < 100 {
            Rgb([0, 0, 0])
        } else {
            Rgb([255, 255, 255])
        }
    }))
}

/// A JPEG with an EXIF segment right after SOI: orientation 6 (rotate 90° clockwise) and a fake
/// GPS string, the kind of metadata that must not survive.
fn jpeg_with_exif(image: &DynamicImage) -> Vec<u8> {
    let jpeg = encode(image, ImageFormat::Jpeg);
    let mut tiff = b"MM\x00\x2a\x00\x00\x00\x08".to_vec();
    tiff.extend_from_slice(&[0x00, 0x01]); // one IFD entry
    tiff.extend_from_slice(&[
        0x01, 0x12, 0x00, 0x03, 0x00, 0x00, 0x00, 0x01, 0x00, 0x06, 0x00, 0x00,
    ]);
    tiff.extend_from_slice(&[0, 0, 0, 0]); // no next IFD
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

#[tokio::test]
async fn launcher_ui_defaults_and_form() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("phone").await;
    assert_eq!(
        policy(&app, &token).await["launcher_ui"],
        json!({ "language": "system", "home_columns": 3 })
    );

    let mut nudges = app.state.command_notify.subscribe();
    let res = app
        .request_form(
            Method::POST,
            &format!("/devices/{id}/launcher"),
            Some(&cookie),
            &[("language", "nb"), ("home_columns", "4")],
        )
        .await;
    assert_eq!(res.location(), Some(format!("/devices/{id}").as_str()));
    assert_eq!(nudges.try_recv().ok(), Some(id));
    assert_eq!(
        policy(&app, &token).await["launcher_ui"],
        json!({ "language": "nb", "home_columns": 4 })
    );

    for bad in [
        [("language", "de"), ("home_columns", "3")],
        [("language", "en"), ("home_columns", "5")],
    ] {
        let res = app
            .request_form(
                Method::POST,
                &format!("/devices/{id}/launcher"),
                Some(&cookie),
                &bad,
            )
            .await;
        assert_eq!(res.status, StatusCode::BAD_REQUEST);
    }
    assert_eq!(
        policy(&app, &token).await["launcher_ui"],
        json!({ "language": "nb", "home_columns": 4 })
    );

    let res = app
        .request_form(
            Method::POST,
            "/devices/999/launcher",
            Some(&cookie),
            &[("language", "en")],
        )
        .await;
    assert_eq!(res.status, StatusCode::NOT_FOUND);
    let page = app
        .get_page(&format!("/devices/{id}"), &cookie)
        .await
        .text();
    assert!(page.contains(&format!("action=\"/devices/{id}/launcher\"")));
    assert!(page.contains("<option value=\"nb\" selected>"));
}

#[tokio::test]
async fn photo_is_reencoded_and_reaches_the_device() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("phone").await;
    let (other, other_token) = app.enrolled_device("other").await;
    let mamma = contact(&app, id, "Mamma", "+4790000001").await;
    contact(&app, other, "Stranger", "+4790000009").await;

    let mut nudges = app.state.command_notify.subscribe();
    let res = upload(&app, &cookie, id, mamma, &jpeg_with_exif(&half_and_half())).await;
    assert_eq!(
        res.location(),
        Some(format!("/devices/{id}/calls").as_str()),
        "{}",
        res.text()
    );
    assert_eq!(nudges.try_recv().ok(), Some(id));

    let hash = photo_hash(&app, mamma).await.expect("photo hash stored");
    assert_eq!(stored_files(&app), [format!("{hash}.jpg")]);
    let contacts = policy(&app, &token).await["call_policy"]["contacts"].clone();
    assert_eq!(contacts[0]["photo"], json!(hash));

    let res = app
        .request(
            Method::GET,
            &format!("/api/devices/contact-photos/{hash}"),
            Some(&token),
            None,
        )
        .await;
    assert_eq!(res.status, StatusCode::OK);
    assert_eq!(res.headers[header::CONTENT_TYPE], "image/jpeg");
    assert_eq!(hex::encode(Sha256::digest(&res.body)), hash);
    // Metadata is gone, the orientation was applied before cropping: rotated 90° clockwise the
    // black left half is on top.
    assert!(!res.body.windows(4).any(|w| w == b"Exif"));
    assert!(!res.body.windows(6).any(|w| w == b"secret"));
    let stored = image::load_from_memory(&res.body).unwrap().into_rgb8();
    assert_eq!(stored.dimensions(), (100, 100));
    assert!(stored.get_pixel(50, 10).0[0] < 60, "top should be black");
    assert!(
        stored.get_pixel(50, 90).0[0] > 200,
        "bottom should be white"
    );

    // Another device's token, no token, or a malformed hash: nothing.
    let res = app
        .request(
            Method::GET,
            &format!("/api/devices/contact-photos/{hash}"),
            Some(&other_token),
            None,
        )
        .await;
    assert_eq!(res.status, StatusCode::NOT_FOUND);
    let res = app
        .request(
            Method::GET,
            &format!("/api/devices/contact-photos/{hash}"),
            None,
            None,
        )
        .await;
    assert_eq!(res.status, StatusCode::UNAUTHORIZED);
    let res = app
        .request(
            Method::GET,
            "/api/devices/contact-photos/..%2Ftest.db",
            Some(&token),
            None,
        )
        .await;
    assert_eq!(res.status, StatusCode::NOT_FOUND);

    // The admin page shows it.
    let page = app
        .get_page(&format!("/devices/{id}/calls"), &cookie)
        .await
        .text();
    assert!(page.contains(&format!("src=\"/contact-photos/{hash}\"")));
    let res = app
        .get_page(&format!("/contact-photos/{hash}"), &cookie)
        .await;
    assert_eq!(res.status, StatusCode::OK);
}

#[tokio::test]
async fn bad_uploads_change_nothing() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, _) = app.enrolled_device("phone").await;
    let mamma = contact(&app, id, "Mamma", "+4790000001").await;

    for bytes in [
        b"GIF89a\x01\x00\x01\x00\x00\x00\x00;".to_vec(),
        b"not an image at all".to_vec(),
        b"\x89PNG\r\n\x1a\n broken".to_vec(),
        Vec::new(),
    ] {
        let res = upload(&app, &cookie, id, mamma, &bytes).await;
        assert_eq!(res.status, StatusCode::BAD_REQUEST);
        assert!(res.text().contains("class=\"error\""));
    }
    let res = upload(&app, &cookie, id, mamma, &vec![0u8; 11 * 1024 * 1024]).await;
    assert!(res.status.is_client_error(), "{}", res.status);
    assert_eq!(photo_hash(&app, mamma).await, None);
    assert!(stored_files(&app).is_empty());

    // A contact that isn't on this device.
    let (other, _) = app.enrolled_device("other").await;
    let stranger = contact(&app, other, "Stranger", "+4790000009").await;
    let png = encode(&half_and_half(), ImageFormat::Png);
    let res = upload(&app, &cookie, id, stranger, &png).await;
    assert_eq!(res.status, StatusCode::NOT_FOUND);
    assert_eq!(photo_hash(&app, stranger).await, None);
}

#[tokio::test]
async fn replaced_and_removed_photos_are_deleted() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("phone").await;
    let (sibling, _) = app.enrolled_device("sibling").await;
    let mamma = contact(&app, id, "Mamma", "+4790000001").await;
    contact(&app, sibling, "Mamma", "+4790000001").await;

    upload(
        &app,
        &cookie,
        id,
        mamma,
        &encode(&half_and_half(), ImageFormat::Png),
    )
    .await;
    let first = photo_hash(&app, mamma).await.unwrap();
    let mut nudges = app.state.command_notify.subscribe();
    let webp = encode(&DynamicImage::new_rgb8(30, 30), ImageFormat::WebP);
    let res = upload(&app, &cookie, id, mamma, &webp).await;
    assert!(res.status.is_redirection(), "{}", res.text());
    let second = photo_hash(&app, mamma).await.unwrap();
    assert_ne!(first, second);
    assert_eq!(stored_files(&app), [format!("{second}.jpg")]);
    // Both phones with the contact are told.
    let mut told = vec![nudges.try_recv().unwrap(), nudges.try_recv().unwrap()];
    told.sort();
    assert_eq!(told, [id, sibling]);

    let res = app
        .request_form(
            Method::POST,
            &format!("/devices/{id}/contacts/{mamma}/photo/remove"),
            Some(&cookie),
            &[],
        )
        .await;
    assert!(res.status.is_redirection());
    assert_eq!(photo_hash(&app, mamma).await, None);
    assert!(stored_files(&app).is_empty());
    assert_eq!(
        policy(&app, &token).await["call_policy"]["contacts"][0]["photo"],
        json!(null)
    );

    // Removing the contact from the last device deletes its photo too.
    upload(&app, &cookie, id, mamma, &webp).await;
    assert_eq!(stored_files(&app).len(), 1);
    for device in [id, sibling] {
        app.request_form(
            Method::POST,
            &format!("/devices/{device}/contacts/{mamma}/remove"),
            Some(&cookie),
            &[],
        )
        .await;
    }
    assert!(stored_files(&app).is_empty());
}

#[tokio::test]
async fn missing_notification_access_is_a_warning() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("phone").await;
    let report = |enabled: serde_json::Value| {
        json!({
            "lock_reason": "NONE", "kiosk_engaged": true,
            "notification_listener_enabled": enabled,
        })
    };
    let warning = "can't read notifications";

    let page = app
        .get_page(&format!("/devices/{id}"), &cookie)
        .await
        .text();
    assert!(!page.contains(warning));
    for (enabled, shown) in [
        (json!(false), true),
        (json!(true), false),
        (json!(null), false),
    ] {
        let res = app
            .request(
                Method::POST,
                "/api/devices/status",
                Some(&token),
                Some(report(enabled)),
            )
            .await;
        assert!(res.status.is_success());
        let page = app
            .get_page(&format!("/devices/{id}"), &cookie)
            .await
            .text();
        assert_eq!(page.contains(warning), shown);
    }
}
