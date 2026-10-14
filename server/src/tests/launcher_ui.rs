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

async fn policy(app: &TestApp, token: &str) -> serde_json::Value {
    let res = app
        .request(Method::GET, "/api/devices/policy", Some(token), None)
        .await;
    assert_eq!(res.status, StatusCode::OK);
    res.json()
}

/// `launcher_ui` without the wallpapers (tests/wallpapers.rs covers those).
async fn launcher_settings(app: &TestApp, token: &str) -> serde_json::Value {
    let mut ui = policy(app, token).await["launcher_ui"].clone();
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
            // The Settings path names the launcher's label (design 34 P11, qa-34-s12-code L1).
            assert!(
                html[panel..].contains("Modify system settings › Vibb One › Allow."),
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
        &app.state.wallpaper_dir,
        &app.state.music_cover_dir,
    )
    .unwrap();
    let mut archive = zip::ZipArchive::new(std::fs::File::open(&zip_path).unwrap()).unwrap();
    assert!(archive.by_name("kidphone.db").is_ok());
    assert!(archive.by_name(&crate::photos::zip_entry(&hash)).is_ok());
    drop(archive);

    // "Restore": the database is back, the photo files are gone; Pappa names a photo that no
    // backup has (and a corrupt copy of it in a newer zip doesn't count).
    std::fs::remove_dir_all(&*app.state.photo_dir).unwrap();
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
    assert_eq!(stored_files(&app), [format!("{hash}.jpg")]);
    assert_eq!(photo_hash(&app, mamma).await, Some(hash.clone()));
    assert_eq!(photo_hash(&app, pappa).await, None);
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
