//! Launcher UI, contact photos and the notification-listener warning (design
//! 05-ui-photos-i18n.md): `launcher_ui` in the policy and its form, photo upload/re-encode/remove,
//! the device photo route and its scoping.

use std::io::Cursor;

use axum::body::Body;
use axum::http::{Method, Request, StatusCode, header};
use image::{DynamicImage, ImageFormat, Rgb, RgbImage};
use serde_json::json;
use sha2::{Digest, Sha256};

use super::{Section, TestApp, TestResponse, assert_redirect, device_page, form_action_of};

/// `launcher_ui` without the wallpapers (tests/wallpapers.rs covers those).
async fn launcher_settings(app: &TestApp, token: &str) -> serde_json::Value {
    let mut ui = app.policy(token).await["launcher_ui"].clone();
    assert!(ui["wallpapers"].is_array(), "wallpapers always sent");
    assert!(
        ui["app_display"].is_array(),
        "app_display always sent (design 14)"
    );
    // Design 25, "Lock background": always sent, null until the parent chooses one.
    assert!(
        ui["lock_wallpaper"].is_null() || ui["lock_wallpaper"].is_i64(),
        "lock_wallpaper always sent"
    );
    assert!(ui.as_object().unwrap().contains_key("lock_wallpaper"));
    // Launcher round 7 §3: always sent; Lock Screen & PINs sets it, never this form.
    assert!(ui["lock_wake_on_notification"].is_boolean());
    ui.as_object_mut()
        .unwrap()
        .remove("lock_wake_on_notification");
    // Design 75: always sent; Device › Weather sets it, never this form.
    assert!(ui["lock_weather"].is_boolean());
    ui.as_object_mut().unwrap().remove("lock_weather");
    ui.as_object_mut().unwrap().remove("wallpapers");
    ui.as_object_mut().unwrap().remove("app_display");
    ui.as_object_mut().unwrap().remove("lock_wallpaper");
    ui
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
    upload_with(app, cookie, device, contact, bytes, &[]).await
}

/// The photo form as the cropper sends it: the file (maybe empty) and its `view` / `original` fields.
async fn upload_with(
    app: &TestApp,
    cookie: &str,
    device: i64,
    contact: i64,
    bytes: &[u8],
    fields: &[(&str, &str)],
) -> TestResponse {
    const BOUNDARY: &str = "handy-test-boundary";
    let mut body = Vec::new();
    for (name, value) in fields {
        body.extend_from_slice(
            format!(
                "--{BOUNDARY}\r\nContent-Disposition: form-data; name=\"{name}\"\r\n\r\n{value}\r\n"
            )
            .as_bytes(),
        );
    }
    body.extend_from_slice(
        format!(
            "--{BOUNDARY}\r\nContent-Disposition: form-data; name=\"photo\"; filename=\"p.jpg\"\r\n\
             Content-Type: application/octet-stream\r\n\r\n"
        )
        .as_bytes(),
    );
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

/// The contact's kept original and its view (migration 0123).
async fn photo_original(app: &TestApp, contact: i64) -> (Option<String>, Option<String>) {
    sqlx::query_as("SELECT photo_original_hash, photo_view FROM contacts WHERE id = ?")
        .bind(contact)
        .fetch_one(&app.db)
        .await
        .unwrap()
}

fn stored_files(app: &TestApp) -> Vec<String> {
    files_in(&app.state.photo_dir)
}

fn stored_originals(app: &TestApp) -> Vec<String> {
    files_in(&app.state.photo_original_dir)
}

fn files_in(dir: &std::path::Path) -> Vec<String> {
    let mut names: Vec<String> = std::fs::read_dir(dir)
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

/// The kinds of a JPEG's segments before its first scan (SOS), each once, sorted.
fn jpeg_markers(jpeg: &[u8]) -> Vec<u8> {
    assert_eq!(&jpeg[..2], b"\xFF\xD8", "SOI");
    let (mut at, mut kinds) = (2, Vec::new());
    while jpeg[at] == 0xFF && jpeg[at + 1] != 0xDA {
        let kind = jpeg[at + 1];
        if !kinds.contains(&kind) {
            kinds.push(kind);
        }
        at += 2 + usize::from(u16::from_be_bytes([jpeg[at + 2], jpeg[at + 3]]));
    }
    kinds.sort_unstable();
    kinds
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
        launcher_settings(&app, &token).await,
        json!({ "language": "system", "home_columns": 3, "clock_format": "system" })
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
    // Back to its group on Home Screen (design 31 §5.1.4).
    assert_redirect(&res, &device_page(id, Section::Home), Some("layout"));
    assert_eq!(nudges.try_recv().ok(), Some(id));
    assert_eq!(
        launcher_settings(&app, &token).await,
        json!({ "language": "nb", "home_columns": 4, "clock_format": "system" })
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
        launcher_settings(&app, &token).await,
        json!({ "language": "nb", "home_columns": 4, "clock_format": "system" })
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
        .get_page(&device_page(id, Section::Home), &cookie)
        .await
        .text();
    let action = format!("/devices/{id}/launcher#layout");
    assert_eq!(form_action_of(&page, "language"), Some(action.as_str()));
    assert_eq!(form_action_of(&page, "home_columns"), Some(action.as_str()));
    assert!(page.contains("<option value=\"nb\" selected>"));
    assert!(page.contains("<option value=\"4\" selected>"));
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
    // Back to the contact's row, open (design 31 step 8).
    super::assert_redirect(
        &res,
        &format!("/devices/{id}/calls?open={mamma}"),
        Some(&format!("contact-{mamma}")),
    );
    assert_eq!(nudges.try_recv().ok(), Some(id));

    let hash = photo_hash(&app, mamma).await.expect("photo hash stored");
    assert_eq!(stored_files(&app), [format!("{hash}.jpg")]);
    let contacts = app.policy(&token).await["call_policy"]["contacts"].clone();
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
    let calls = app.get_page(&format!("/devices/{id}/calls"), &cookie).await;
    // The crop dialog shows a picked photo from a blob: URL: the page's CSP must allow it (QA photo-crop H1).
    let csp = calls.headers[header::CONTENT_SECURITY_POLICY]
        .to_str()
        .unwrap();
    let img_src = csp
        .split(';')
        .map(str::trim)
        .find(|directive| directive.starts_with("img-src"))
        .unwrap_or_default();
    assert!(img_src.split(' ').any(|source| source == "blob:"), "{csp}");
    let page = calls.text();
    assert!(page.contains(&format!("src=\"/contact-photos/{hash}\"")));
    // The crop (static/photo-crop.js): "Adjust crop" on the stored photo, the dialog with the server's side and
    // limit, its words from the catalog.
    let words = |key| crate::i18n::t(crate::i18n::Lang::En, key);
    let (original, view) = photo_original(&app, mamma).await;
    let (original, view) = (original.unwrap(), view.unwrap());
    assert!(page.contains(&format!(
        "data-photo-adjust=\"/contact-photos/original/{original}\" data-photo-original=\"{original}\" \
         data-photo-view=\"{view}\" data-photo-square=\"/contact-photos/{hash}\" hidden>{}</button>",
        words("device.calls.adjust_crop")
    )));
    assert!(page.contains("<input type=\"hidden\" name=\"view\" value=\"\" data-photo-view>"));
    assert!(page.contains("<script src=\"/static/photo-crop.js?v=3\" defer></script>"));
    assert!(page.contains(&format!(
        "data-side=\"{}\" data-max-bytes=\"{}\"",
        crate::photos::MAX_SIDE,
        crate::photos::MAX_UPLOAD_BYTES
    )));
    for key in [
        "device.calls.crop.title",
        "device.calls.crop.zoom",
        "device.calls.photo_privacy",
    ] {
        assert!(page.replace("&#39;", "'").contains(words(key)), "{key}");
    }
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
        // The page again, the contact open with the reason under its photo (design 31 step 8).
        let res = upload(&app, &cookie, id, mamma, &bytes).await;
        assert_eq!(res.status, StatusCode::BAD_REQUEST);
        let html = res.text();
        let row = &html[html.find(&format!("id=\"contact-{mamma}\"")).unwrap()..];
        assert!(row.contains("<details class=\"edit\" open>"), "{row}");
        assert!(
            row.contains(&format!("id=\"photo-{mamma}-error\"")),
            "{row}"
        );
    }
    let res = upload(&app, &cookie, id, mamma, &vec![0u8; 11 * 1024 * 1024]).await;
    assert!(res.status.is_client_error(), "{}", res.status);
    assert_eq!(photo_hash(&app, mamma).await, None);
    assert!(stored_files(&app).is_empty());

    // A contact that isn't on this device: back to the list, saying nothing was saved; an unknown
    // phone is a 404.
    let (other, _) = app.enrolled_device("other").await;
    let stranger = contact(&app, other, "Stranger", "+4790000009").await;
    let png = encode(&half_and_half(), ImageFormat::Png);
    let res = upload(&app, &cookie, id, stranger, &png).await;
    super::assert_redirect(
        &res,
        &format!("/devices/{id}/calls?notice=contact_gone"),
        Some("contacts"),
    );
    assert_eq!(photo_hash(&app, stranger).await, None);
    let res = upload(&app, &cookie, 9999, stranger, &png).await;
    assert_eq!(res.status, StatusCode::NOT_FOUND);
    assert_eq!(photo_hash(&app, stranger).await, None);

    // Removing the photo of another phone's contact leaves it, and says nothing was saved
    // (qa-31-s8-code L3, N3); an unknown phone is a 404.
    upload(&app, &cookie, other, stranger, &png).await;
    let hash = photo_hash(&app, stranger)
        .await
        .expect("the stranger's photo");
    for (device, expected) in [(id, StatusCode::SEE_OTHER), (9999, StatusCode::NOT_FOUND)] {
        let res = app
            .request_form(
                Method::POST,
                &format!("/devices/{device}/contacts/{stranger}/photo/remove"),
                Some(&cookie),
                &[],
            )
            .await;
        if expected == StatusCode::NOT_FOUND {
            assert_eq!(res.status, expected);
        } else {
            assert_redirect(
                &res,
                &format!("/devices/{id}/calls?notice=contact_gone"),
                Some("contacts"),
            );
        }
        assert_eq!(
            photo_hash(&app, stranger).await.as_deref(),
            Some(hash.as_str())
        );
    }
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
        app.policy(&token).await["call_policy"]["contacts"][0]["photo"],
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

/// Vibb can't read notifications: Home Screen warns (the sentence visible, the adb command only
/// behind its ⓘ, design 31 §4.2) and the device page's Attention row links there.
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
    let warning = "Notification access off";
    let row = format!("href=\"/devices/{id}/home#layout\" data-attention=\"notification-access\"");

    let page = app
        .get_page(&device_page(id, Section::Home), &cookie)
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
            .get_page(&device_page(id, Section::Home), &cookie)
            .await
            .text();
        assert_eq!(page.contains(warning), shown);
        let summary = app
            .get_page(&device_page(id, Section::Summary), &cookie)
            .await
            .text();
        assert_eq!(summary.contains(&row), shown);
        if shown {
            // In the target's section, above its group; the command only in the ⓘ panel.
            let at = page.find(warning).unwrap();
            assert!(page[..at].contains("<section id=\"layout\""));
            assert!(!page[..at].contains("<form"));
            let command = page.find("allow_listener").expect("the fix");
            let panel = page[..command].rfind("<details class=\"info\">").unwrap();
            assert!(at < panel && !page[panel..command].contains("</details>"));
        }
    }
}

/// Design 34 P12: the Clock select is in the launcher form; every choice reaches the policy, a
/// present unknown value is a 400 that changes nothing, and a post without `clock_format` (a page
/// cached before the field existed) keeps the stored format (qa-34-design L10).
#[tokio::test]
async fn clock_format_form_keeps_a_missing_field() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("phone").await;
    let launcher = format!("/devices/{id}/launcher");
    for format in ["24", "12", "system", "12"] {
        let res = app
            .request_form(
                Method::POST,
                &launcher,
                Some(&cookie),
                &[
                    ("language", "en"),
                    ("home_columns", "3"),
                    ("clock_format", format),
                ],
            )
            .await;
        assert_redirect(&res, &device_page(id, Section::Home), Some("layout"));
        app.assert_lands(&res, &cookie).await;
        assert_eq!(
            launcher_settings(&app, &token).await["clock_format"],
            json!(format)
        );
    }
    let page = app
        .get_page(&device_page(id, Section::Home), &cookie)
        .await
        .text();
    let action = format!("{launcher}#layout");
    assert_eq!(form_action_of(&page, "clock_format"), Some(action.as_str()));
    assert!(page.contains("<option value=\"12\" selected>12 h</option>"));
    assert!(page.contains("<option value=\"system\">Follow the phone</option>"));

    // An old cached page: the language and columns change, the clock stays 12 h.
    let res = app
        .request_form(
            Method::POST,
            &launcher,
            Some(&cookie),
            &[("language", "nb"), ("home_columns", "4")],
        )
        .await;
    assert_redirect(&res, &device_page(id, Section::Home), Some("layout"));
    assert_eq!(
        launcher_settings(&app, &token).await,
        json!({ "language": "nb", "home_columns": 4, "clock_format": "12" })
    );

    // A present unknown value is a 400, and nothing is written.
    for bad in ["", "24h", "follow", "SYSTEM"] {
        let res = app
            .request_form(
                Method::POST,
                &launcher,
                Some(&cookie),
                &[
                    ("language", "en"),
                    ("home_columns", "3"),
                    ("clock_format", bad),
                ],
            )
            .await;
        assert_eq!(res.status, StatusCode::BAD_REQUEST, "{bad:?}");
    }
    assert_eq!(
        launcher_settings(&app, &token).await,
        json!({ "language": "nb", "home_columns": 4, "clock_format": "12" })
    );
}

/// qa-34-s6-code N1: migration 0075's CHECK holds even past the handler - a stored value outside
/// "system"/"24"/"12" is refused by the database itself, and a new row gets "system".
#[tokio::test]
async fn the_clock_format_column_takes_only_its_three_values() {
    let app = TestApp::new().await;
    let (id, _) = app.enrolled_device("phone").await;
    let stored: String =
        sqlx::query_scalar("SELECT clock_format FROM device_policy WHERE device_id = ?")
            .bind(id)
            .fetch_one(&app.db)
            .await
            .unwrap();
    assert_eq!(stored, "system");
    for bad in ["25", "", "follow", "SYSTEM"] {
        let res = sqlx::query("UPDATE device_policy SET clock_format = ? WHERE device_id = ?")
            .bind(bad)
            .bind(id)
            .execute(&app.db)
            .await;
        assert!(res.is_err(), "{bad:?} was stored");
    }
    for good in ["24", "12", "system"] {
        sqlx::query("UPDATE device_policy SET clock_format = ? WHERE device_id = ?")
            .bind(good)
            .bind(id)
            .execute(&app.db)
            .await
            .unwrap();
    }
}

/// Design 34 P12: the phone reports `clock_format_applied`; false while the policy asks for 24 h
/// or 12 h is a warning on Home Screen (the sentence visible, the adb grant only behind its ⓘ).
/// True, null, an older launcher (no field) and "Follow the phone" show nothing.
#[tokio::test]
async fn clock_format_not_applied_is_a_warning() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("phone").await;
    let warning = "Clock format not applied";
    let home = device_page(id, Section::Home);
    let launcher = format!("/devices/{id}/launcher");
    let set_format = async |format: &str| {
        let res = app
            .request_form(
                Method::POST,
                &launcher,
                Some(&cookie),
                &[
                    ("language", "system"),
                    ("home_columns", "3"),
                    ("clock_format", format),
                ],
            )
            .await;
        assert!(res.status.is_redirection());
    };
    let page = async || app.get_page(&home, &cookie).await.text();
    assert!(!page().await.contains(warning), "no report yet");

    set_format("24").await;
    for (applied, shown) in [
        (Some(json!(false)), true),
        (Some(json!(true)), false),
        (Some(json!(null)), false),
        (None, false),
        (Some(json!(false)), true),
    ] {
        let mut body = json!({ "lock_reason": "NONE", "kiosk_engaged": true });
        if let Some(applied) = &applied {
            body["clock_format_applied"] = applied.clone();
        }
        let res = app
            .request(
                Method::POST,
                "/api/devices/status",
                Some(&token),
                Some(body),
            )
            .await;
        assert!(res.status.is_success());
        let stored: Option<bool> = sqlx::query_scalar(
            "SELECT clock_format_applied FROM device_status WHERE device_id = ? \
             ORDER BY id DESC LIMIT 1",
        )
        .bind(id)
        .fetch_one(&app.db)
        .await
        .unwrap();
        assert_eq!(stored, applied.as_ref().and_then(|a| a.as_bool()));
        let html = page().await;
        assert_eq!(html.contains(warning), shown, "{stored:?}");
        if shown {
            // In the Language group's section, above its form; the command only in the ⓘ panel.
            let at = html.find(warning).unwrap();
            assert!(html[..at].contains("<section id=\"layout\""));
            assert!(!html[..at].contains("<form"));
            assert!(html[at..].contains("change its clock format yet."));
            let command = html.find("WRITE_SETTINGS allow").expect("the fix");
            let panel = html[..command].rfind("<details class=\"info\">").unwrap();
            assert!(at < panel && !html[panel..command].contains("</details>"));
            // The on-phone path names the launcher's label (design 34 P11, qa-34-s12-code L1) and
            // its Settings row (2026-10-24), before the adb shortcut.
            assert!(
                html[panel..command]
                    .contains("Settings › Pause all restrictions › Modify system settings")
                    && html[panel..command].contains("Vibb One"),
                "{html}"
            );
        }
    }
    // The last report still says false, but "Follow the phone" asks for nothing.
    set_format("system").await;
    assert!(!page().await.contains(warning));
    set_format("12").await;
    assert!(page().await.contains(warning));
}

#[tokio::test]
async fn admin_photo_route_needs_a_session() {
    let app = TestApp::new().await;
    let res = app
        .request(
            Method::GET,
            &format!("/contact-photos/{}", "a".repeat(64)),
            None,
            None,
        )
        .await;
    assert!(res.status.is_redirection(), "{}", res.status);
}

/// Migration 0123: the upload is kept as a downscaled original (upright, no metadata) and the square is cut from it
/// at the cropper's view - in the upright picture; "Adjust crop" sends only a new view and the square is cut again;
/// a bad view or a stale original changes nothing; removing the photo clears the original and prunes it.
#[tokio::test]
async fn the_original_is_kept_and_the_square_cut_at_the_view() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("phone").await;
    let mamma = contact(&app, id, "Mamma", "+4790000001").await;

    // 200x100 black|white with EXIF orientation 6: upright it is 100x200, black on top. The bottom square is
    // white only if the orientation was applied before the view.
    let jpeg = jpeg_with_exif(&half_and_half());
    let res = upload_with(&app, &cookie, id, mamma, &jpeg, &[("view", "0,0.5,1,0.5")]).await;
    assert_eq!(res.status, StatusCode::SEE_OTHER);
    let hash = photo_hash(&app, mamma).await.unwrap();
    let (original, view) = photo_original(&app, mamma).await;
    let original = original.expect("the original is kept");
    assert_eq!(view.as_deref(), Some("0.000000,0.500000,1.000000,0.500000"));
    assert_eq!(stored_originals(&app), [format!("{original}.jpg")]);
    let square = image::load_from_memory(
        &std::fs::read(app.state.photo_dir.join(format!("{hash}.jpg"))).unwrap(),
    )
    .unwrap()
    .into_rgb8();
    assert_eq!(square.dimensions(), (100, 100));
    // Away from the edge the halves meet at (JPEG rings a little there).
    let inner = |square: &RgbImage| -> Vec<u8> {
        square
            .enumerate_pixels()
            .filter(|(_, y, _)| (8..92).contains(y))
            .map(|(_, _, p)| p.0[0])
            .collect()
    };
    assert!(
        inner(&square).iter().all(|&red| red > 200),
        "the bottom, white"
    );
    let kept = std::fs::read(app.state.photo_original_dir.join(format!("{original}.jpg"))).unwrap();
    assert!(!kept.windows(4).any(|w| w == b"Exif"));
    assert!(!kept.windows(6).any(|w| w == b"secret"));
    // Before the scan only the JFIF header, tables and frame: no APPn (EXIF, XMP, ICC) and no comment (QA L6).
    assert_eq!(jpeg_markers(&kept), [0xC0, 0xC4, 0xDB, 0xE0]);
    let upright = image::load_from_memory(&kept).unwrap().into_rgb8();
    assert_eq!(upright.dimensions(), (100, 200));
    assert!(upright.get_pixel(50, 10).0[0] < 60, "upright: black on top");

    // Adjust crop: only the view, with the original it was chosen on - the top square, black, cut again.
    let mut nudges = app.state.command_notify.subscribe();
    let res = upload_with(
        &app,
        &cookie,
        id,
        mamma,
        b"",
        &[("view", "0,0,1,0.5"), ("original", &original)],
    )
    .await;
    assert_redirect(
        &res,
        &format!("/devices/{id}/calls?open={mamma}"),
        Some(&format!("contact-{mamma}")),
    );
    assert_eq!(nudges.try_recv().ok(), Some(id));
    let recut = photo_hash(&app, mamma).await.unwrap();
    assert_ne!(recut, hash);
    assert_eq!(
        photo_original(&app, mamma).await,
        (
            Some(original.clone()),
            Some("0.000000,0.000000,1.000000,0.500000".into())
        )
    );
    assert_eq!(
        stored_files(&app),
        [format!("{recut}.jpg")],
        "the old square pruned"
    );
    let res = app
        .request(
            Method::GET,
            &format!("/api/devices/contact-photos/{recut}"),
            Some(&token),
            None,
        )
        .await;
    let square = image::load_from_memory(&res.body).unwrap().into_rgb8();
    assert!(inner(&square).iter().all(|&red| red < 60), "the top, black");

    // Save again without a move (the view as the cropper rounds it): nothing committed, no phone nudged (QA L1).
    let res = upload_with(
        &app,
        &cookie,
        id,
        mamma,
        b"",
        &[("view", "0.000001,0,0.999999,0.5"), ("original", &original)],
    )
    .await;
    assert_eq!(res.status, StatusCode::SEE_OTHER);
    assert_eq!(
        photo_hash(&app, mamma).await.as_deref(),
        Some(recut.as_str())
    );
    assert!(nudges.try_recv().is_err(), "no phone told again");

    // Refused, nothing changed: a view that isn't a square (the reason under the photo), a view chosen on
    // another original (another tab replaced the photo), an adjust without a view.
    let words = |key| crate::i18n::t(crate::i18n::Lang::En, key);
    let stale = "c".repeat(64);
    for (bytes, fields, key) in [
        (
            jpeg.as_slice(),
            vec![("view", "0,0,1,0.3")],
            "device.calls.crop.not_square",
        ),
        (
            b"".as_slice(),
            vec![("view", "0,0,1,0.5"), ("original", stale.as_str())],
            "device.calls.crop.photo_changed",
        ),
        (
            b"".as_slice(),
            vec![("original", original.as_str())],
            "device.calls.crop.bad_view",
        ),
    ] {
        let res = upload_with(&app, &cookie, id, mamma, bytes, &fields).await;
        assert_eq!(res.status, StatusCode::BAD_REQUEST, "{key}");
        let html = res.text().replace("&#39;", "'");
        let error = format!("id=\"photo-{mamma}-error\">{}</p>", words(key));
        assert!(html.contains(&error), "{key}: {html}");
        assert_eq!(
            photo_hash(&app, mamma).await.as_deref(),
            Some(recut.as_str())
        );
    }
    assert_eq!(stored_originals(&app), [format!("{original}.jpg")]);

    // The original lost while the server runs (QA L2): its own line, the original and view forgotten, the square
    // kept - the page offers the stored square's adjust again.
    std::fs::remove_file(app.state.photo_original_dir.join(format!("{original}.jpg"))).unwrap();
    let res = upload_with(
        &app,
        &cookie,
        id,
        mamma,
        b"",
        &[("view", "0,0,1,0.5"), ("original", &original)],
    )
    .await;
    assert_eq!(res.status, StatusCode::BAD_REQUEST);
    let html = res.text();
    assert!(
        html.contains(&format!(
            "id=\"photo-{mamma}-error\">{}</p>",
            words("device.calls.crop.original_lost")
        )),
        "{html}"
    );
    assert!(html.contains(&format!(
        "data-photo-adjust=\"/contact-photos/{recut}\" hidden>"
    )));
    assert_eq!(
        photo_hash(&app, mamma).await.as_deref(),
        Some(recut.as_str())
    );
    assert_eq!(photo_original(&app, mamma).await, (None, None));

    // Removed: the original and its view go with the square, and the files are pruned.
    let res = app
        .request_form(
            Method::POST,
            &format!("/devices/{id}/contacts/{mamma}/photo/remove"),
            Some(&cookie),
            &[],
        )
        .await;
    assert_eq!(res.status, StatusCode::SEE_OTHER);
    assert_eq!(photo_hash(&app, mamma).await, None);
    assert_eq!(photo_original(&app, mamma).await, (None, None));
    assert!(stored_files(&app).is_empty());
    assert!(stored_originals(&app).is_empty());
}

/// The original is the parent's alone: on the admin session, only while a contact names it, never to a phone.
#[tokio::test]
async fn an_original_is_never_served_to_a_device() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("phone").await;
    let mamma = contact(&app, id, "Mamma", "+4790000001").await;
    upload(
        &app,
        &cookie,
        id,
        mamma,
        &encode(&half_and_half(), ImageFormat::Png),
    )
    .await;
    let original = photo_original(&app, mamma).await.0.unwrap();

    // Not in the policy, not on the device route, not on the admin route with the phone's token.
    let policy = app.policy(&token).await.to_string();
    assert!(!policy.contains(&original));
    let res = app
        .request(
            Method::GET,
            &format!("/api/devices/contact-photos/{original}"),
            Some(&token),
            None,
        )
        .await;
    assert_eq!(res.status, StatusCode::NOT_FOUND);
    let res = app
        .request(
            Method::GET,
            &format!("/contact-photos/original/{original}"),
            Some(&token),
            None,
        )
        .await;
    assert!(res.status.is_redirection(), "{}", res.status);
    assert!(!res.body.starts_with(b"\xFF\xD8"), "no JPEG");

    // The parent gets it, not kept in the browser's cache; a hash no contact names is a 404.
    let res = app
        .get_page(&format!("/contact-photos/original/{original}"), &cookie)
        .await;
    assert_eq!(res.status, StatusCode::OK);
    assert_eq!(res.headers[header::CONTENT_TYPE], "image/jpeg");
    assert_eq!(res.headers[header::CACHE_CONTROL], "private, no-store");
    assert_eq!(hex::encode(Sha256::digest(&res.body)), original);
    let stray = "d".repeat(64);
    std::fs::write(
        app.state.photo_original_dir.join(format!("{stray}.jpg")),
        b"not referenced",
    )
    .unwrap();
    let res = app
        .get_page(&format!("/contact-photos/original/{stray}"), &cookie)
        .await;
    assert_eq!(res.status, StatusCode::NOT_FOUND);

    // A prune keeps the referenced original and drops the stray one.
    crate::photos::prune(&app.state).await;
    assert_eq!(stored_originals(&app), [format!("{original}.jpg")]);
}

/// Two parents at once: uploads and removes on different contacts, each followed by a prune,
/// never delete a photo another request just stored (QA step 5 #3).
#[tokio::test]
async fn concurrent_uploads_and_removes_keep_referenced_photos() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, _) = app.enrolled_device("phone").await;
    let mut contacts = Vec::new();
    for i in 0..6 {
        contacts.push(contact(&app, id, &format!("C{i}"), &format!("+479000000{i}")).await);
    }
    let jobs = contacts.iter().enumerate().map(|(i, &c)| {
        let app = &app;
        let cookie = cookie.clone();
        async move {
            let img =
                DynamicImage::ImageRgb8(RgbImage::from_pixel(20, 20, Rgb([i as u8 * 40, 0, 0])));
            upload(app, &cookie, id, c, &encode(&img, ImageFormat::Png)).await;
            if i % 2 == 0 {
                app.request_form(
                    Method::POST,
                    &format!("/devices/{id}/contacts/{c}/photo/remove"),
                    Some(&cookie),
                    &[],
                )
                .await;
            }
        }
    });
    futures_join_all(jobs).await;

    let mut expected = Vec::new();
    for (i, &c) in contacts.iter().enumerate() {
        let hash = photo_hash(&app, c).await;
        assert_eq!(hash.is_some(), i % 2 == 1);
        if let Some(hash) = hash {
            expected.push(format!("{hash}.jpg"));
        }
    }
    expected.sort();
    assert_eq!(stored_files(&app), expected);
}

async fn futures_join_all<F: std::future::Future<Output = ()>>(jobs: impl Iterator<Item = F>) {
    let mut set = Vec::new();
    for job in jobs {
        set.push(Box::pin(job));
    }
    // Poll them together on this task (TestApp isn't 'static, so no spawn).
    std::future::poll_fn(|cx| {
        set.retain_mut(|job| job.as_mut().poll(cx).is_pending());
        if set.is_empty() {
            std::task::Poll::Ready(())
        } else {
            std::task::Poll::Pending
        }
    })
    .await;
}

#[tokio::test]
async fn backups_carry_photos_and_a_restore_gets_them_back() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("phone").await;
    let mamma = contact(&app, id, "Mamma", "+4790000001").await;
    let pappa = contact(&app, id, "Pappa", "+4790000002").await;
    upload(
        &app,
        &cookie,
        id,
        mamma,
        &encode(&half_and_half(), ImageFormat::Png),
    )
    .await;
    let hash = photo_hash(&app, mamma).await.unwrap();

    // A backup zip as the Backups page builds it.
    let backups = tempfile::tempdir().unwrap();
    let db = backups.path().join("db");
    std::fs::write(&db, b"not really a database").unwrap();
    let zip_path = backups.path().join("backup-20261005-120000.zip");
    crate::handlers::backups::build_backup_zip(
        db.to_str().unwrap(),
        zip_path.to_str().unwrap(),
        &app.state.photo_dir,
        &app.state.photo_original_dir,
        &app.state.wallpaper_dir,
        &app.state.music_cover_dir,
        &app.state.avatar_dir,
    )
    .unwrap();
    let original = photo_original(&app, mamma).await.0.unwrap();
    let mut archive = zip::ZipArchive::new(std::fs::File::open(&zip_path).unwrap()).unwrap();
    assert!(archive.by_name("kidphone.db").is_ok());
    assert!(archive.by_name(&crate::photos::zip_entry(&hash)).is_ok());
    // The kept original too (migration 0123), stored as it is: a JPEG doesn't compress further.
    assert_eq!(
        archive
            .by_name(&crate::photos::CONTACT_ORIGINALS.zip_entry(&original))
            .unwrap()
            .compression(),
        zip::CompressionMethod::Stored
    );
    drop(archive);

    // Two photos in no backup (QA photo-original L6): Mormor's original is lost (her square stays, the original
    // and view are forgotten), Morfar's square is lost (all three are cleared, though his original is still there).
    let mormor = contact(&app, id, "Mormor", "+4790000003").await;
    let morfar = contact(&app, id, "Morfar", "+4790000004").await;
    for (who, shade) in [(mormor, 90), (morfar, 180)] {
        let png = encode(
            &DynamicImage::ImageRgb8(RgbImage::from_pixel(300, 300, Rgb([shade, 40, 40]))),
            ImageFormat::Png,
        );
        upload(&app, &cookie, id, who, &png).await;
    }
    let mormor_square = photo_hash(&app, mormor).await.unwrap();
    let morfar_original = photo_original(&app, morfar).await.0.unwrap();
    let kept_square =
        std::fs::read(app.state.photo_dir.join(format!("{mormor_square}.jpg"))).unwrap();
    let kept_original = std::fs::read(
        app.state
            .photo_original_dir
            .join(format!("{morfar_original}.jpg")),
    )
    .unwrap();

    // "Restore": the database is back, the photo files are gone; Pappa names a photo that no
    // backup has (and a corrupt copy of it in a newer zip doesn't count).
    std::fs::remove_dir_all(&*app.state.photo_dir).unwrap();
    std::fs::remove_dir_all(&*app.state.photo_original_dir).unwrap();
    std::fs::create_dir_all(&*app.state.photo_dir).unwrap();
    std::fs::create_dir_all(&*app.state.photo_original_dir).unwrap();
    std::fs::write(
        app.state.photo_dir.join(format!("{mormor_square}.jpg")),
        &kept_square,
    )
    .unwrap();
    std::fs::write(
        app.state
            .photo_original_dir
            .join(format!("{morfar_original}.jpg")),
        &kept_original,
    )
    .unwrap();
    let ghost = "b".repeat(64);
    sqlx::query("UPDATE contacts SET photo_hash = ? WHERE id = ?")
        .bind(&ghost)
        .bind(pappa)
        .execute(&app.db)
        .await
        .unwrap();
    {
        let file =
            std::fs::File::create(backups.path().join("backup-20261006-120000.zip")).unwrap();
        let mut writer = zip::ZipWriter::new(file);
        writer
            .start_file(
                crate::photos::zip_entry(&ghost),
                zip::write::SimpleFileOptions::default(),
            )
            .unwrap();
        std::io::Write::write_all(&mut writer, b"not the right bytes").unwrap();
        writer.finish().unwrap();
    }

    crate::photos::recover_missing(&app.state, backups.path()).await;
    let mut squares = vec![format!("{hash}.jpg"), format!("{mormor_square}.jpg")];
    squares.sort();
    assert_eq!(stored_files(&app), squares);
    assert!(stored_originals(&app).contains(&format!("{original}.jpg")));
    assert_eq!(photo_hash(&app, mamma).await, Some(hash.clone()));
    assert_eq!(photo_hash(&app, pappa).await, None);
    assert_eq!(
        photo_hash(&app, mormor).await.as_deref(),
        Some(mormor_square.as_str())
    );
    assert_eq!(photo_original(&app, mormor).await, (None, None));
    assert_eq!(photo_hash(&app, morfar).await, None);
    assert_eq!(photo_original(&app, morfar).await, (None, None));
    let res = app
        .request(
            Method::GET,
            &format!("/api/devices/contact-photos/{hash}"),
            Some(&token),
            None,
        )
        .await;
    assert_eq!(res.status, StatusCode::OK);
}
