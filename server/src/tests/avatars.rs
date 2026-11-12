//! A phone's own picture (server round C, migration 0100, `handlers::device_avatar`): the upload
//! from the summary's header, the picture on every phone row, Remove, a refused file, the backup,
//! the route without a session, and the push's icon.

use std::io::Cursor;

use axum::body::Body;
use axum::http::{Method, Request, StatusCode, header};
use image::{DynamicImage, ImageFormat, Rgb, RgbImage};

use super::{TestApp, TestResponse, tags};

async fn upload(app: &TestApp, cookie: &str, device: i64, bytes: &[u8]) -> TestResponse {
    const BOUNDARY: &str = "handy-avatar-boundary";
    let mut body = format!(
        "--{BOUNDARY}\r\nContent-Disposition: form-data; name=\"picture\"; filename=\"me.png\"\r\n\
         Content-Type: image/png\r\n\r\n"
    )
    .into_bytes();
    body.extend_from_slice(bytes);
    body.extend_from_slice(format!("\r\n--{BOUNDARY}--\r\n").as_bytes());
    let request = Request::builder()
        .method(Method::POST)
        .uri(format!("/devices/{device}/avatar"))
        .header(header::COOKIE, cookie)
        .header(
            header::CONTENT_TYPE,
            format!("multipart/form-data; boundary={BOUNDARY}"),
        )
        .body(Body::from(body))
        .unwrap();
    app.send(request).await
}

/// A small generated picture, 300×200 (wider than tall: the square is its middle).
fn png() -> Vec<u8> {
    let image = DynamicImage::ImageRgb8(RgbImage::from_fn(300, 200, |x, y| {
        Rgb([(x % 256) as u8, (y % 256) as u8, 120])
    }));
    let mut out = Cursor::new(Vec::new());
    image.write_to(&mut out, ImageFormat::Png).unwrap();
    out.into_inner()
}

async fn avatar_hash(app: &TestApp, device: i64) -> Option<String> {
    sqlx::query_scalar("SELECT avatar_hash FROM devices WHERE id = ?")
        .bind(device)
        .fetch_one(&app.db)
        .await
        .unwrap()
}

fn stored(app: &TestApp) -> Vec<String> {
    let mut names: Vec<String> = std::fs::read_dir(&*app.state.avatar_dir)
        .map(|dir| {
            dir.filter_map(|e| e.ok())
                .map(|e| e.file_name().to_string_lossy().into_owned())
                .collect()
        })
        .unwrap_or_default();
    names.sort();
    names
}

/// The picture's route, without a cookie (as the service worker's icon fetch).
async fn fetch(app: &TestApp, hash: &str) -> TestResponse {
    app.send(
        Request::builder()
            .uri(format!("/phone-avatars/{hash}"))
            .body(Body::empty())
            .unwrap(),
    )
    .await
}

fn back_to_avatar(res: &TestResponse, id: i64, query: Option<&str>) {
    assert_eq!(res.status, StatusCode::SEE_OTHER, "{}", res.text());
    let expected = match query {
        Some(q) => format!("/devices/{id}?avatar={q}#avatar"),
        None => format!("/devices/{id}#avatar"),
    };
    assert_eq!(res.location(), Some(expected.as_str()));
}

#[tokio::test]
async fn an_uploaded_picture_is_served_and_shown_on_every_phone_row() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("Anna").await;
    // A second phone, so Locate lists the phones.
    app.enrolled_device("Emil").await;

    // Before: the letter tile, and the header is the picker (saves itself, a <noscript> Save).
    let page = app
        .get_page(&format!("/devices/{id}"), &cookie)
        .await
        .text();
    assert!(!page.contains("avatar-img"));
    let input = tags(&page, "input")
        .into_iter()
        .find(|t| t.attr("name") == Some("picture"))
        .expect("the picker");
    assert_eq!(input.attr("type"), Some("file"));
    assert_eq!(input.attr("accept"), Some("image/*"));
    assert!(input.has("data-autosave"));
    assert!(page.contains(&format!("action=\"/devices/{id}/avatar#avatar\"")));
    assert!(page.contains("enctype=\"multipart/form-data\""));
    assert!(!page.contains("Remove picture"));

    let res = upload(&app, &cookie, id, &png()).await;
    back_to_avatar(&res, id, None);
    let hash = avatar_hash(&app, id).await.expect("the hash is stored");
    assert_eq!(stored(&app), [format!("{hash}.jpg")]);

    // Served without a session, as a square JPEG with nothing but pixels, cached for good.
    let res = fetch(&app, &hash).await;
    assert_eq!(res.status, StatusCode::OK);
    assert_eq!(res.headers.get(header::CONTENT_TYPE).unwrap(), "image/jpeg");
    assert_eq!(
        res.headers.get(header::CACHE_CONTROL).unwrap(),
        "private, max-age=31536000, immutable"
    );
    let image = image::load_from_memory_with_format(&res.body, ImageFormat::Jpeg).unwrap();
    assert_eq!((image.width(), image.height()), (200, 200));
    assert!(!res.body.windows(4).any(|w| w == b"Exif"));
    // Not a listing, not another file, not a hash no phone names.
    for path in [String::new(), "x".to_string(), "b".repeat(64)] {
        assert_eq!(
            fetch(&app, &path).await.status,
            StatusCode::NOT_FOUND,
            "{path}"
        );
    }

    // The picture over the letter, the dot kept: the header, Devices, Locate, Time Rules, Apps.
    let src = format!("src=\"/phone-avatars/{hash}\"");
    let page = app
        .get_page(&format!("/devices/{id}"), &cookie)
        .await
        .text();
    assert!(page.contains(&src), "{page}");
    assert!(page.contains("Remove picture"));
    // An event for the inbox's row and a catalog app for its page (code QA Q-5).
    let phone = crate::parent_events::Phone::load(&app.db, id)
        .await
        .unwrap();
    let battery = crate::models::BatteryReport {
        level: 9,
        charging: false,
        low: true,
    };
    crate::parent_events::raise_logged(&app.state, &phone.low_battery(&battery, phone.now)).await;
    let res = app
        .request_form(
            Method::POST,
            "/apps/tracked/new",
            Some(&cookie),
            &[("name", "Notes"), ("source_type", "manual")],
        )
        .await;
    let app_page = res
        .location()
        .expect("the new app's page")
        .split('#')
        .next()
        .unwrap()
        .split('?')
        .next()
        .unwrap()
        .to_string();
    // The app's page lists the phones that have it.
    let app_id: i64 = app_page.rsplit('/').next().unwrap().parse().unwrap();
    sqlx::query("INSERT INTO device_tracked_apps (device_id, tracked_app_id) VALUES (?, ?)")
        .bind(id)
        .bind(app_id)
        .execute(&app.db)
        .await
        .unwrap();
    for path in [
        "/devices",
        "/devices/locate",
        "/schedules",
        "/apps",
        "/music",
        "/notifications",
        app_page.as_str(),
    ] {
        let html = app.get_page(path, &cookie).await.text();
        let at = html
            .find(&src)
            .unwrap_or_else(|| panic!("{path} has no picture"));
        let tile = &html[html[..at].rfind("<span class=\"avatar").unwrap()..];
        let tile = &tile[..tile.find("</span>").unwrap()];
        assert!(tile.contains(&src), "{path}: {tile}");
        if matches!(path, "/devices" | "/devices/locate") {
            let after = &html[at..];
            assert!(
                after[..after.find("</span></span>").unwrap()].contains("avatar-dot"),
                "{path}: the dot stays"
            );
        }
    }

    // One of the phone's own pages: the picture, small, on the band - the same route, never spoken
    // (design 84, O1 c).
    let html = app
        .get_page(&format!("/devices/{id}/calls"), &cookie)
        .await
        .text();
    let band = &html[html.find("<header class=\"band").unwrap()..];
    let band = &band[..band.find("</header>").unwrap()];
    assert!(
        band.contains(&format!(
            "<span class=\"band-who\" aria-hidden=\"true\"><span class=\"avatar tone-peach\">A\
             <img class=\"avatar-img\" {src} alt=\"\" decoding=\"async\"></span></span>"
        )),
        "{band}"
    );

    // Never sent to the phone.
    let policy = app
        .request(Method::GET, "/api/devices/policy", Some(&token), None)
        .await
        .text();
    assert!(!policy.contains(&hash));
}

#[tokio::test]
async fn remove_and_replace_go_back_to_the_letter_and_prune_the_file() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, _) = app.enrolled_device("Anna").await;
    upload(&app, &cookie, id, &png()).await;
    let first = avatar_hash(&app, id).await.unwrap();

    // A new picture replaces the old one, whose file goes.
    let other = {
        let image = DynamicImage::ImageRgb8(RgbImage::from_pixel(64, 64, Rgb([10, 200, 30])));
        let mut out = Cursor::new(Vec::new());
        image.write_to(&mut out, ImageFormat::Png).unwrap();
        out.into_inner()
    };
    upload(&app, &cookie, id, &other).await;
    let second = avatar_hash(&app, id).await.unwrap();
    assert_ne!(first, second);
    assert_eq!(stored(&app), [format!("{second}.jpg")]);
    assert_eq!(fetch(&app, &first).await.status, StatusCode::NOT_FOUND);

    let res = app
        .request_form(
            Method::POST,
            &format!("/devices/{id}/avatar/remove"),
            Some(&cookie),
            &[],
        )
        .await;
    back_to_avatar(&res, id, None);
    assert_eq!(avatar_hash(&app, id).await, None);
    assert!(stored(&app).is_empty());
    assert_eq!(fetch(&app, &second).await.status, StatusCode::NOT_FOUND);
    let page = app
        .get_page(&format!("/devices/{id}"), &cookie)
        .await
        .text();
    assert!(!page.contains("avatar-img"));
    assert!(!page.contains("Remove picture"));

    // A deleted phone's picture goes with it.
    upload(&app, &cookie, id, &png()).await;
    assert_eq!(stored(&app).len(), 1);
    let res = app
        .request_form(
            Method::POST,
            &format!("/devices/{id}/delete"),
            Some(&cookie),
            &[],
        )
        .await;
    assert!(res.status.is_redirection());
    assert!(stored(&app).is_empty());

    // Unknown phones.
    let res = app
        .request_form(
            Method::POST,
            "/devices/999/avatar/remove",
            Some(&cookie),
            &[],
        )
        .await;
    assert_eq!(res.status, StatusCode::NOT_FOUND);
    assert_eq!(
        upload(&app, &cookie, 999, &png()).await.status,
        StatusCode::NOT_FOUND
    );
}

#[tokio::test]
async fn a_file_that_isnt_a_picture_is_refused_under_the_avatar() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, _) = app.enrolled_device("Anna").await;
    upload(&app, &cookie, id, &png()).await;
    let kept = avatar_hash(&app, id).await.unwrap();

    for (bytes, code, text) in [
        (
            b"%PDF-1.4 not a picture".to_vec(),
            "type",
            "Use a JPEG, PNG or WebP photo.",
        ),
        (Vec::new(), "empty", "Choose a photo first."),
        (
            // A PNG's signature and nothing after it.
            b"\x89PNG\r\n\x1a\n".to_vec(),
            "unreadable",
            "That photo couldn't be read - try another one.",
        ),
    ] {
        let res = upload(&app, &cookie, id, &bytes).await;
        back_to_avatar(&res, id, Some(code));
        assert_eq!(avatar_hash(&app, id).await.as_deref(), Some(kept.as_str()));
        let page = app
            .get_page(&format!("/devices/{id}?avatar={code}"), &cookie)
            .await
            .text();
        let page = page.replace("&#39;", "'");
        let error = &page[page.find("id=\"avatar-error\"").expect(code)..];
        assert!(
            error.starts_with(&format!("id=\"avatar-error\" role=\"alert\">{text}<")),
            "{error}"
        );
        assert!(page.contains("aria-describedby=\"avatar-error\""));
    }
    assert_eq!(stored(&app), [format!("{kept}.jpg")]);
    // A code that isn't one says nothing (never echoed).
    let page = app
        .get_page(&format!("/devices/{id}?avatar=%3Cscript%3E"), &cookie)
        .await
        .text();
    assert!(!page.contains("avatar-error"));
}

#[tokio::test]
async fn the_backup_carries_the_picture_and_a_restore_gets_it_back() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (anna, _) = app.enrolled_device("Anna").await;
    let (emil, _) = app.enrolled_device("Emil").await;
    upload(&app, &cookie, anna, &png()).await;
    let hash = avatar_hash(&app, anna).await.unwrap();

    let backups = tempfile::tempdir().unwrap();
    let db = backups.path().join("db");
    std::fs::write(&db, b"not really a database").unwrap();
    let zip_path = backups.path().join("backup-20261025-120000.zip");
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
    let mut archive = zip::ZipArchive::new(std::fs::File::open(&zip_path).unwrap()).unwrap();
    let entry = crate::photos::DEVICE_AVATARS.zip_entry(&hash);
    assert_eq!(entry, format!("device_avatars/{hash}.jpg"));
    assert!(archive.by_name(&entry).is_ok());
    drop(archive);

    // "Restore": the files are gone; Emil names a picture no backup has.
    std::fs::remove_dir_all(&*app.state.avatar_dir).unwrap();
    let ghost = "c".repeat(64);
    sqlx::query("UPDATE devices SET avatar_hash = ? WHERE id = ?")
        .bind(&ghost)
        .bind(emil)
        .execute(&app.db)
        .await
        .unwrap();
    crate::photos::recover_missing(&app.state, backups.path()).await;
    assert_eq!(stored(&app), [format!("{hash}.jpg")]);
    assert_eq!(avatar_hash(&app, anna).await, Some(hash.clone()));
    assert_eq!(avatar_hash(&app, emil).await, None, "the letter tile again");
    assert_eq!(fetch(&app, &hash).await.status, StatusCode::OK);
}

#[tokio::test]
async fn a_push_about_the_phone_carries_its_picture_as_the_icon() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    super::web_push::subscribe(&app, &cookie, super::web_push::ENDPOINT).await;
    let (anna, _) = app.enrolled_device("Anna").await;
    let (emil, _) = app.enrolled_device("Emil").await;
    upload(&app, &cookie, anna, &png()).await;
    let hash = avatar_hash(&app, anna).await.unwrap();

    for id in [anna, emil] {
        let phone = crate::parent_events::Phone::load(&app.db, id)
            .await
            .unwrap();
        let battery = crate::models::BatteryReport {
            level: 9,
            charging: false,
            low: true,
        };
        crate::parent_events::raise_logged(&app.state, &phone.low_battery(&battery, phone.now))
            .await;
    }
    crate::push::send_due(&app.state, chrono::Utc::now())
        .await
        .unwrap();
    let mut icons: Vec<(String, String)> = app
        .pushes
        .take()
        .iter()
        .map(|r| {
            let n = super::web_push::payload(r)["notification"].clone();
            (
                n["title"].as_str().unwrap().to_string(),
                n["data"]["icon"].as_str().unwrap().to_string(),
            )
        })
        .collect();
    icons.sort();
    assert_eq!(icons.len(), 2, "{icons:?}");
    assert!(icons[0].0.starts_with("Anna"), "{icons:?}");
    assert_eq!(icons[0].1, format!("/phone-avatars/{hash}"));
    assert!(icons[1].0.starts_with("Emil"), "{icons:?}");
    assert_eq!(icons[1].1, crate::push::APP_ICON);
}

/// The route without a session serves only a well-formed hash a phone names now (code QA Q-1): not
/// a file on disk no phone names, not an uppercase or a 65-character copy, not a path - not even
/// one a row names.
#[tokio::test]
async fn the_picture_route_serves_only_a_named_hash() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, _) = app.enrolled_device("Anna").await;
    let (emil, _) = app.enrolled_device("Emil").await;
    upload(&app, &cookie, id, &png()).await;
    let hash = avatar_hash(&app, id).await.unwrap();
    assert_eq!(fetch(&app, &hash).await.status, StatusCode::OK);

    // A valid-looking file no phone names (a prune not run yet, a stray copy).
    let stray = "d".repeat(64);
    std::fs::write(app.state.avatar_dir.join(format!("{stray}.jpg")), b"stray").unwrap();
    // A file outside the store, which only a path that isn't a hash reaches.
    std::fs::write(app.state.avatar_dir.join("../secret.jpg"), b"not for you").unwrap();
    // A row naming that path (a corrupt or restored database).
    sqlx::query("UPDATE devices SET avatar_hash = '../secret' WHERE id = ?")
        .bind(emil)
        .execute(&app.db)
        .await
        .unwrap();
    for path in [
        stray.clone(),
        hash.to_uppercase(),
        format!("{hash}0"),
        hash[..63].to_string(),
        format!("{hash}.jpg"),
        "..%2Fsecret".to_string(),
        "%2E%2E%2Fsecret".to_string(),
    ] {
        let res = fetch(&app, &path).await;
        assert_eq!(res.status, StatusCode::NOT_FOUND, "{path}");
        assert!(!res.text().contains("not for you"), "{path}");
    }
    // Named, the stray is served: the guard is the name as well as the file.
    sqlx::query("UPDATE devices SET avatar_hash = ? WHERE id = ?")
        .bind(&stray)
        .bind(emil)
        .execute(&app.db)
        .await
        .unwrap();
    assert_eq!(fetch(&app, &stray).await.status, StatusCode::OK);
}

/// The route takes a phone photo of up to 10 MB, not axum's 2 MB default (code QA Q-4); a body over
/// the limit is refused under the avatar and changes nothing.
#[tokio::test]
async fn the_upload_takes_a_photo_over_two_megabytes() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, _) = app.enrolled_device("Anna").await;
    // 1000×1000 of noise: a PNG that hardly compresses, about 3 MB.
    let mut seed: u32 = 12345;
    let noise = DynamicImage::ImageRgb8(RgbImage::from_fn(1000, 1000, |_, _| {
        seed = seed.wrapping_mul(1_103_515_245).wrapping_add(12345);
        let [a, b, c, _] = seed.to_le_bytes();
        Rgb([a, b, c])
    }));
    let mut out = Cursor::new(Vec::new());
    noise.write_to(&mut out, ImageFormat::Png).unwrap();
    let big = out.into_inner();
    assert!(big.len() > 2 * 1024 * 1024, "{}", big.len());
    let res = upload(&app, &cookie, id, &big).await;
    back_to_avatar(&res, id, None);
    let hash = avatar_hash(&app, id).await.expect("stored");

    // Over 10 MB + the framing: refused, the picture kept.
    let res = upload(&app, &cookie, id, &vec![0u8; 11 * 1024 * 1024]).await;
    back_to_avatar(&res, id, Some("too_large"));
    assert_eq!(avatar_hash(&app, id).await, Some(hash));
}

/// The admin gate still applies to the route without a session.
#[tokio::test]
async fn the_picture_route_is_behind_the_admin_gate() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, _) = app.enrolled_device("Anna").await;
    upload(&app, &cookie, id, &png()).await;
    let hash = avatar_hash(&app, id).await.unwrap();
    let res = app
        .send_via(
            &app.router,
            std::net::SocketAddr::new("203.0.113.7".parse().unwrap(), 50000),
            Request::builder()
                .uri(format!("/phone-avatars/{hash}"))
                .body(Body::empty())
                .unwrap(),
        )
        .await;
    assert_eq!(res.status, StatusCode::FORBIDDEN);
}

/// Every phone avatar on a page as `(its classes, its letter)` - the letter tiles of design 85's
/// tones (`<span class="avatar …">E`), not their dots or pictures.
fn tiles(html: &str) -> Vec<(String, char)> {
    html.match_indices("<span class=\"")
        .filter_map(|(at, open)| {
            let rest = &html[at + open.len()..];
            let classes = &rest[..rest.find('"')?];
            if classes.split(' ').next() != Some("avatar") {
                return None;
            }
            let letter = rest[rest.find('>')? + 1..].chars().next()?;
            Some((classes.to_string(), letter))
        })
        .collect()
}

async fn tone(app: &TestApp, device: i64) -> i64 {
    sqlx::query_scalar("SELECT avatar_tone FROM devices WHERE id = ?")
        .bind(device)
        .fetch_one(&app.db)
        .await
        .unwrap()
}

/// Add Phone's "Next", returning the new phone's id.
async fn add_phone(app: &TestApp, cookie: &str, name: &str) -> i64 {
    let res = app
        .request_form(
            Method::POST,
            "/devices/new",
            Some(cookie),
            &[("name", name)],
        )
        .await;
    let location = res.location().expect("on to Connect");
    location
        .strip_prefix("/devices/")
        .and_then(|rest| rest.strip_suffix("/provision"))
        .and_then(|id| id.parse().ok())
        .unwrap_or_else(|| panic!("{location}"))
}

/// Design 85 §5.2: a new phone takes the first tone no phone has; a removed phone's tone goes to
/// the next new one; past seven, the one the fewest phones have (the lowest on a tie). Stored, so
/// the others never move.
#[tokio::test]
async fn a_new_phone_takes_the_first_free_tone() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let mut ids = Vec::new();
    for name in ["Emma", "Jack", "Lily", "Mamma", "Pappa", "Emma 2", "Jack 2"] {
        ids.push(add_phone(&app, &cookie, name).await);
    }
    for (n, id) in ids.iter().enumerate() {
        assert_eq!(tone(&app, *id).await, n as i64, "phone {n}");
    }

    // Lily (butter) goes: the next new phone takes butter; nobody else moves.
    let res = app
        .request_form(
            Method::POST,
            &format!("/devices/{}/delete", ids[2]),
            Some(&cookie),
            &[],
        )
        .await;
    assert!(res.status.is_redirection());
    let next = add_phone(&app, &cookie, "Lily 2").await;
    assert_eq!(tone(&app, next).await, 2);
    assert_eq!(tone(&app, ids[1]).await, 1);
    assert_eq!(tone(&app, ids[6]).await, 6);

    // Seven phones, every tone once: the eighth takes peach, the ninth sky.
    let eighth = add_phone(&app, &cookie, "Emma 3").await;
    assert_eq!(tone(&app, eighth).await, 0);
    let ninth = add_phone(&app, &cookie, "Jack 3").await;
    assert_eq!(tone(&app, ninth).await, 1);
}

/// Migration 0138 numbers the phones there by id: the oldest keeps peach, past seven it starts over.
#[tokio::test]
async fn the_migration_numbers_the_phones_by_id() {
    let (db, _dir) = super::cleanup::migrated_before(138).await;
    for (id, name) in [
        (3, "Emma"),
        (5, "Jack"),
        (6, "Lily"),
        (9, "Mamma"),
        (10, "Pappa"),
        (11, "Emma 2"),
        (12, "Jack 2"),
        (20, "Lily 2"),
        (21, "Mamma 2"),
    ] {
        sqlx::query("INSERT INTO devices (id, name) VALUES (?, ?)")
            .bind(id)
            .bind(name)
            .execute(&db)
            .await
            .unwrap();
    }
    super::cleanup::finish(&db).await;
    let tones: Vec<(i64, i64)> = sqlx::query_as("SELECT id, avatar_tone FROM devices ORDER BY id")
        .fetch_all(&db)
        .await
        .unwrap();
    assert_eq!(
        tones,
        [
            (3, 0),
            (5, 1),
            (6, 2),
            (9, 3),
            (10, 4),
            (11, 5),
            (12, 6),
            (20, 0),
            (21, 1)
        ]
    );
}

/// Design 85 §5.4: the phone's tone is on every phone avatar - Devices, the summary, a phone page's
/// band, Apps' "On phones" and an app's page, Schedules, Music, Locate and the inbox; a phone not set
/// up stays grey, and a picture still covers the tile.
#[tokio::test]
async fn a_phones_tone_is_on_every_avatar() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    app.enrolled_device("Emma").await;
    let (jack, _) = app.enrolled_device("Jack").await;
    app.create_device("Lily").await;

    let phone = crate::parent_events::Phone::load(&app.db, jack)
        .await
        .unwrap();
    let battery = crate::models::BatteryReport {
        level: 9,
        charging: false,
        low: true,
    };
    crate::parent_events::raise_logged(&app.state, &phone.low_battery(&battery, phone.now)).await;
    let res = app
        .request_form(
            Method::POST,
            "/apps/tracked/new",
            Some(&cookie),
            &[("name", "Notes"), ("source_type", "manual")],
        )
        .await;
    let app_page = res
        .location()
        .expect("the new app's page")
        .split(['#', '?'])
        .next()
        .unwrap()
        .to_string();
    let app_id: i64 = app_page.rsplit('/').next().unwrap().parse().unwrap();
    sqlx::query("INSERT INTO device_tracked_apps (device_id, tracked_app_id) VALUES (?, ?)")
        .bind(jack)
        .bind(app_id)
        .execute(&app.db)
        .await
        .unwrap();

    for path in [
        "/devices".to_string(),
        format!("/devices/{jack}"),
        format!("/devices/{jack}/calls"),
        "/apps".to_string(),
        app_page,
        "/schedules".to_string(),
        "/music".to_string(),
        "/devices/locate".to_string(),
        "/notifications".to_string(),
    ] {
        let html = app.get_page(&path, &cookie).await.text();
        let tiles = tiles(&html);
        assert!(tiles.iter().any(|(_, l)| *l == 'J'), "{path}: {tiles:?}");
        if path == "/devices" {
            for letter in ['E', 'L'] {
                assert!(
                    tiles.iter().any(|(_, l)| *l == letter),
                    "{letter}: {tiles:?}"
                );
            }
        }
        for (classes, letter) in &tiles {
            let classes: Vec<&str> = classes.split(' ').collect();
            match letter {
                'E' => assert!(classes.contains(&"tone-peach"), "{path}: {classes:?}"),
                'J' => assert!(classes.contains(&"tone-sky"), "{path}: {classes:?}"),
                'L' => {
                    assert!(classes.contains(&"avatar-grey"), "{path}: {classes:?}");
                    assert!(!classes.iter().any(|c| c.starts_with("tone-")), "{path}");
                }
                _ => {}
            }
        }
    }

    // A picture covers the tone as before: the tile keeps its class, the picture over the letter.
    upload(&app, &cookie, jack, &png()).await;
    let hash = avatar_hash(&app, jack).await.unwrap();
    let html = app.get_page("/devices", &cookie).await.text();
    assert!(html.contains(&format!(
        "<span class=\"avatar tone-sky\" aria-hidden=\"true\">J<img class=\"avatar-img\" \
         src=\"/phone-avatars/{hash}\""
    )));
}

/// A stored tone out of range (a hand-edited row, a later shorter palette) still maps to a tone.
#[test]
fn a_tone_out_of_range_still_has_a_class() {
    use crate::handlers::devices::{AVATAR_TONES, avatar_class_of};
    assert_eq!(avatar_class_of(true, 0), "tone-peach");
    assert_eq!(avatar_class_of(true, 9), AVATAR_TONES[2]);
    assert_eq!(avatar_class_of(true, -1), AVATAR_TONES[6]);
    assert_eq!(avatar_class_of(false, 3), "avatar-grey");
}

/// `AVATAR_TONES`, the new phone's `INSERT` and `style.css` agree: one `--avatar-bg` rule per tone
/// and no other, as many `VALUES` rows as tones, and every colour under the night letter (`#0C0C14`)
/// at 9.7:1 or more - the decided palette's floor, rose's 9.76 (design 85 §5.1).
#[test]
fn the_tones_agree_with_the_stylesheet_and_carry_the_letter() {
    use crate::handlers::devices::{AVATAR_TONES, next_tone};
    let sql = next_tone();
    let values = &sql[sql.find("VALUES ").unwrap()..sql.find(" SELECT").unwrap()];
    assert_eq!(values.matches('(').count(), AVATAR_TONES.len(), "{values}");

    fn luminance(hex: &str) -> f64 {
        let channel = |i: usize| {
            let c = u8::from_str_radix(&hex[i..i + 2], 16).unwrap() as f64 / 255.0;
            if c <= 0.04045 {
                c / 12.92
            } else {
                ((c + 0.055) / 1.055).powf(2.4)
            }
        };
        0.2126 * channel(1) + 0.7152 * channel(3) + 0.0722 * channel(5)
    }
    let letter = luminance("#0C0C14");

    let css = std::fs::read_to_string("static/style.css").unwrap();
    assert_eq!(
        css.matches("\n.tone-").count(),
        AVATAR_TONES.len(),
        "a tone in style.css that AVATAR_TONES doesn't have"
    );
    for class in AVATAR_TONES {
        let open = format!("\n.{class} {{ --avatar-bg: #");
        assert_eq!(css.matches(&open).count(), 1, "{class}");
        let at = css.find(&open).unwrap() + open.len() - 1;
        let hex = &css[at..at + 7];
        assert_eq!(&css[at + 7..at + 10], "; }", "{class}");
        let ratio = (luminance(hex) + 0.05) / (letter + 0.05);
        assert!(ratio >= 9.7, "{class} {hex}: {ratio:.2}");
    }
}
