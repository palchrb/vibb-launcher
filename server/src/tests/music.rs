//! Vibb music, phase 1 (design 21 §1): migration 0049, the `/music` pages and the device card,
//! the policy's `music` object, the library endpoint (ETag/304, gzip, scoping, the 3 MB guard),
//! covers and own files (Range, If-Match, missing), uploads, the add check on canned bodies, the
//! Storytel login (sealed, write-only, generations, 404/503), nudges, auth and the status report.

use std::collections::HashMap;
use std::io::{Cursor, Read};
use std::sync::Mutex;

use axum::body::Body;
use axum::http::{Method, Request, StatusCode, header};
use image::{DynamicImage, ImageFormat, Rgb, RgbImage};
use serde_json::{Value, json};
use sha2::{Digest, Sha256};

use super::{TestApp, TestResponse};
use crate::music::{FetchError, Fetched};

// ------------------------------------------------------------------------------------------------
// Helpers
// ------------------------------------------------------------------------------------------------

/// The add check's GET in the tests: canned answers by URL (anything else is a network error), and
/// every URL asked for.
#[derive(Default)]
pub struct CannedFetch {
    answers: Mutex<HashMap<String, (u16, Vec<u8>)>>,
    pub calls: Mutex<Vec<String>>,
}

impl CannedFetch {
    pub fn answer(&self, url: &str, status: u16, body: impl Into<Vec<u8>>) {
        self.answers
            .lock()
            .unwrap()
            .insert(url.to_string(), (status, body.into()));
    }
}

impl crate::music::Fetch for CannedFetch {
    fn get<'a>(
        &'a self,
        url: &'a str,
    ) -> std::pin::Pin<Box<dyn std::future::Future<Output = Result<Fetched, FetchError>> + Send + 'a>>
    {
        Box::pin(async move {
            self.calls.lock().unwrap().push(url.to_string());
            match self.answers.lock().unwrap().get(url) {
                Some((status, body)) => Ok(Fetched {
                    status: *status,
                    body: body.clone(),
                }),
                None => Err(FetchError::Network("no route to host".to_string())),
            }
        })
    }
}

const FEED: &str = r#"<?xml version="1.0" encoding="UTF-8"?>
<rss version="2.0"><channel><title>Ukas nyheter</title>
<item><title>Uke 41</title><enclosure url="https://example.org/41.mp3" type="audio/mpeg"/></item>
</channel></rss>"#;

fn psapi(kind: &str, slug: &str) -> String {
    format!("https://psapi.nrk.no/radio/catalog/{kind}/{slug}")
}

async fn add_nrk(app: &TestApp, cookie: &str, slug: &str, title: &str) -> i64 {
    app.fetch.answer(
        &psapi("podcast", slug),
        200,
        json!({"series": {"titles": {"title": title}}}).to_string(),
    );
    let res = app
        .request_form(
            Method::POST,
            "/music",
            Some(cookie),
            &[("link", &format!("https://radio.nrk.no/podkast/{slug}"))],
        )
        .await;
    assert_eq!(res.status, StatusCode::SEE_OTHER, "{}", res.text());
    entry_id_by_key(app, slug).await
}

async fn entry_id_by_key(app: &TestApp, key: &str) -> i64 {
    sqlx::query_scalar("SELECT id FROM music_entries WHERE key = ?")
        .bind(key)
        .fetch_one(&app.db)
        .await
        .unwrap()
}

async fn add_own(app: &TestApp, cookie: &str, name: &str) -> i64 {
    let res = app
        .request_form(Method::POST, "/music/own", Some(cookie), &[("name", name)])
        .await;
    assert_eq!(res.status, StatusCode::SEE_OTHER, "{}", res.text());
    let location = res.location().unwrap().to_string();
    let id: i64 = location
        .trim_start_matches("/music/entries/")
        .trim_end_matches("#files")
        .parse()
        .unwrap();
    id
}

async fn tick(app: &TestApp, cookie: &str, device: i64, entry: i64, on: bool) -> TestResponse {
    let fields: &[(&str, &str)] = if on { &[("selected", "on")] } else { &[] };
    app.request_form(
        Method::POST,
        &format!("/devices/{device}/music/entries/{entry}"),
        Some(cookie),
        fields,
    )
    .await
}

async fn music_policy(app: &TestApp, token: &str) -> Value {
    let res = app
        .request(Method::GET, "/api/devices/policy", Some(token), None)
        .await;
    assert_eq!(res.status, StatusCode::OK);
    res.json()["music"].clone()
}

/// A GET with a bearer token and extra headers.
async fn device_get(
    app: &TestApp,
    uri: &str,
    token: &str,
    headers: &[(&str, &str)],
) -> TestResponse {
    let mut builder = Request::builder()
        .uri(uri)
        .header(header::AUTHORIZATION, format!("Bearer {token}"));
    for (name, value) in headers {
        builder = builder.header(*name, *value);
    }
    app.send(builder.body(Body::empty()).unwrap()).await
}

/// Silent 16-bit mono PCM WAV, `ms` long.
fn wav(ms: u32) -> Vec<u8> {
    let samples = 8 * ms;
    let data_len = samples * 2;
    let mut out = Vec::new();
    out.extend_from_slice(b"RIFF");
    out.extend_from_slice(&(36 + data_len).to_le_bytes());
    out.extend_from_slice(b"WAVEfmt ");
    out.extend_from_slice(&16u32.to_le_bytes());
    out.extend_from_slice(&1u16.to_le_bytes());
    out.extend_from_slice(&1u16.to_le_bytes());
    out.extend_from_slice(&8000u32.to_le_bytes());
    out.extend_from_slice(&16000u32.to_le_bytes());
    out.extend_from_slice(&2u16.to_le_bytes());
    out.extend_from_slice(&16u16.to_le_bytes());
    out.extend_from_slice(b"data");
    out.extend_from_slice(&data_len.to_le_bytes());
    out.resize(out.len() + data_len as usize, 0);
    out
}

fn png(w: u32, h: u32) -> Vec<u8> {
    let image = DynamicImage::ImageRgb8(RgbImage::from_fn(w, h, |x, _| {
        Rgb([if x < w / 2 { 200 } else { 20 }, 80, 120])
    }));
    let mut out = Cursor::new(Vec::new());
    image.write_to(&mut out, ImageFormat::Png).unwrap();
    out.into_inner()
}

/// A WAV with an ID3v2 tag: title, artist, track and (optionally) a front cover - written by lofty.
fn tagged_wav(title: &str, artist: &str, track: u32, cover: Option<Vec<u8>>) -> Vec<u8> {
    use lofty::config::WriteOptions;
    use lofty::picture::{MimeType, Picture, PictureType};
    use lofty::prelude::*;
    use lofty::tag::{Tag, TagType};

    let dir = tempfile::tempdir().unwrap();
    let path = dir.path().join("t.wav");
    std::fs::write(&path, wav(1500)).unwrap();
    let mut tag = Tag::new(TagType::Id3v2);
    tag.set_title(title.to_string());
    tag.set_artist(artist.to_string());
    tag.set_album("Album".to_string());
    tag.set_track(track);
    if let Some(cover) = cover {
        tag.push_picture(
            Picture::unchecked(cover)
                .pic_type(PictureType::CoverFront)
                .mime_type(MimeType::Png)
                .build(),
        );
    }
    tag.save_to_path(&path, WriteOptions::default()).unwrap();
    std::fs::read(&path).unwrap()
}

async fn upload(
    app: &TestApp,
    cookie: &str,
    entry: i64,
    files: &[(&str, Vec<u8>)],
) -> TestResponse {
    const BOUNDARY: &str = "handy-music-boundary";
    let mut body = Vec::new();
    for (name, bytes) in files {
        body.extend_from_slice(
            format!(
                "--{BOUNDARY}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"{name}\"\r\n\
                 Content-Type: application/octet-stream\r\n\r\n"
            )
            .as_bytes(),
        );
        body.extend_from_slice(bytes);
        body.extend_from_slice(b"\r\n");
    }
    body.extend_from_slice(format!("--{BOUNDARY}--\r\n").as_bytes());
    let request = Request::builder()
        .method(Method::POST)
        .uri(format!("/music/entries/{entry}/files"))
        .header(header::COOKIE, cookie)
        .header(
            header::CONTENT_TYPE,
            format!("multipart/form-data; boundary={BOUNDARY}"),
        )
        .body(Body::from(body))
        .unwrap();
    app.send(request).await
}

async fn upload_cover(app: &TestApp, cookie: &str, entry: i64, bytes: &[u8]) -> TestResponse {
    const BOUNDARY: &str = "handy-cover-boundary";
    let mut body = format!(
        "--{BOUNDARY}\r\nContent-Disposition: form-data; name=\"cover\"; filename=\"c.png\"\r\n\
         Content-Type: application/octet-stream\r\n\r\n"
    )
    .into_bytes();
    body.extend_from_slice(bytes);
    body.extend_from_slice(format!("\r\n--{BOUNDARY}--\r\n").as_bytes());
    let request = Request::builder()
        .method(Method::POST)
        .uri(format!("/music/entries/{entry}/cover"))
        .header(header::COOKIE, cookie)
        .header(
            header::CONTENT_TYPE,
            format!("multipart/form-data; boundary={BOUNDARY}"),
        )
        .body(Body::from(body))
        .unwrap();
    app.send(request).await
}

fn nudged(rx: &mut tokio::sync::broadcast::Receiver<i64>) -> Vec<i64> {
    let mut ids = Vec::new();
    while let Ok(id) = rx.try_recv() {
        ids.push(id);
    }
    ids
}

async fn events(app: &TestApp, kind: &str) -> Vec<String> {
    sqlx::query_scalar(
        "SELECT COALESCE(detail, '') FROM security_events WHERE event_type = ? ORDER BY id",
    )
    .bind(kind)
    .fetch_all(&app.db)
    .await
    .unwrap()
}

// ------------------------------------------------------------------------------------------------
// Migration 0049
// ------------------------------------------------------------------------------------------------

#[tokio::test]
async fn migration_0049_is_safe_on_existing_data_and_sets_up_the_catalog() {
    let (db, _dir) = super::cleanup::migrated_before(49).await;
    let device: i64 = sqlx::query_scalar("INSERT INTO devices (name) VALUES ('old') RETURNING id")
        .fetch_one(&db)
        .await
        .unwrap();
    sqlx::query("INSERT INTO device_policy (device_id, kiosk_desired) VALUES (?, 1)")
        .bind(device)
        .execute(&db)
        .await
        .unwrap();
    sqlx::query("INSERT INTO device_status (device_id, app_version) VALUES (?, '0.30.0')")
        .bind(device)
        .execute(&db)
        .await
        .unwrap();
    sqlx::query(
        "INSERT INTO tracked_apps (name, package_name, github_repo, is_launcher, \
         latest_release_tag, latest_release_asset_id) \
         VALUES ('Launcher', 'me.vibb.launcher', 'palchrb/vibb-launcher', 1, 'launcher-v0.30.0', 4), \
                ('Element X', 'io.element.android.x', 'element-hq/element-x-android', 0, 'v26', 9)",
    )
    .execute(&db)
    .await
    .unwrap();
    super::cleanup::finish(&db).await;

    let categories: Vec<(String, String, String, Option<String>)> = sqlx::query_as(
        "SELECT name, icon, color, default_kind FROM music_categories ORDER BY sort",
    )
    .fetch_all(&db)
    .await
    .unwrap();
    assert_eq!(
        categories,
        vec![
            (
                "Musikk".into(),
                "music_note".into(),
                "rose".into(),
                Some("music".into())
            ),
            ("Eventyr".into(), "star".into(), "gold".into(), None),
            (
                "Lydbøker".into(),
                "menu_book".into(),
                "terracotta".into(),
                Some("audiobook".into())
            ),
            (
                "Podkast".into(),
                "mic".into(),
                "teal".into(),
                Some("podcast".into())
            ),
        ]
    );
    for (_, icon, color, _) in &categories {
        assert!(crate::music::icon_known(icon) && crate::music::color_hex(color).is_some());
    }
    type Row = (String, Option<String>, Option<String>, Option<String>, bool);
    let rows: Vec<Row> = sqlx::query_as(
        "SELECT name, release_tag_prefix, asset_pattern, latest_release_tag, include_prereleases \
         FROM tracked_apps ORDER BY id",
    )
    .fetch_all(&db)
    .await
    .unwrap();
    assert_eq!(
        rows,
        vec![
            (
                "Launcher".into(),
                Some("launcher-v".into()),
                Some(r"^kids-launcher-mdm\.apk$".into()),
                Some("launcher-v0.30.0".into()),
                false
            ),
            ("Element X".into(), None, None, Some("v26".into()), false),
            (
                "Vibb Musikk".into(),
                Some("music-v".into()),
                Some(r"^vibb-music\.apk$".into()),
                None,
                false
            ),
        ],
        "the launcher's row and existing rows keep everything else; the music row is added"
    );
    let music_row: (String, String, String, String, String, bool, bool) = sqlx::query_as(
        "SELECT package_name, github_repo, display_label, display_icon, display_color, enabled, \
         is_launcher FROM tracked_apps WHERE release_tag_prefix = 'music-v'",
    )
    .fetch_one(&db)
    .await
    .unwrap();
    assert_eq!(
        music_row,
        (
            "me.vibb.music".into(),
            "palchrb/vibb-launcher".into(),
            "Musikk".into(),
            "music_note".into(),
            "peach".into(),
            true,
            false
        )
    );
    let policy: (bool, Option<i64>, bool) = sqlx::query_as(
        "SELECT music_mobile_data, music_volume_cap_pct, music_storytel FROM device_policy",
    )
    .fetch_one(&db)
    .await
    .unwrap();
    assert_eq!(
        policy,
        (false, None, false),
        "mobile data, cap and Storytel all off"
    );
    let status: (String, Option<String>) =
        sqlx::query_as("SELECT app_version, music_state_json FROM device_status")
            .fetch_one(&db)
            .await
            .unwrap();
    assert_eq!(status, ("0.30.0".into(), None));
    let generation: i64 = sqlx::query_scalar("SELECT generation FROM music_storytel")
        .fetch_one(&db)
        .await
        .unwrap();
    assert_eq!(generation, 0);
}

#[tokio::test]
async fn migration_0049_leaves_other_launcher_rows_alone() {
    let (db, _dir) = super::cleanup::migrated_before(49).await;
    sqlx::query(
        "INSERT INTO tracked_apps (name, package_name, github_repo, is_launcher, \
         latest_release_tag, asset_pattern) \
         VALUES ('Launcher', 'me.vibb.launcher', 'someone/fork', 1, 'pre-release', 'mdm.apk')",
    )
    .execute(&db)
    .await
    .unwrap();
    super::cleanup::finish(&db).await;
    let rows: Vec<(Option<String>, Option<String>)> =
        sqlx::query_as("SELECT release_tag_prefix, asset_pattern FROM tracked_apps")
            .fetch_all(&db)
            .await
            .unwrap();
    assert_eq!(
        rows,
        vec![(None, Some("mdm.apk".into()))],
        "a launcher row on another tag scheme is untouched and gets no music row (QA #10)"
    );
}

// ------------------------------------------------------------------------------------------------
// Adding entries (the add check on canned bodies)
// ------------------------------------------------------------------------------------------------

#[tokio::test]
async fn nrk_and_rss_links_are_checked_once_and_named_after_their_title() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;

    let id = add_nrk(&app, &cookie, "abels_taarn", "Abels tårn").await;
    let entry: (
        String,
        String,
        Option<String>,
        String,
        String,
        i64,
        bool,
        i64,
    ) = sqlx::query_as(
        "SELECT e.name, e.source, e.target, e.key, c.name, e.cache, e.resume, e.sort \
         FROM music_entries e JOIN music_categories c ON c.id = e.category_id WHERE e.id = ?",
    )
    .bind(id)
    .fetch_one(&app.db)
    .await
    .unwrap();
    assert_eq!(
        entry,
        (
            "Abels tårn".into(),
            "nrk".into(),
            Some("https://radio.nrk.no/podkast/abels_taarn".into()),
            "abels_taarn".into(),
            "Podkast".into(),
            5,
            true,
            10
        )
    );
    assert_eq!(
        app.fetch.calls.lock().unwrap().as_slice(),
        [psapi("podcast", "abels_taarn")]
    );

    // An episode link of the same podcast is the same entry.
    let dup = app
        .request_form(
            Method::POST,
            "/music",
            Some(&cookie),
            &[("link", "https://radio.nrk.no/podkast/abels_taarn/l_123")],
        )
        .await;
    assert_eq!(dup.status, StatusCode::BAD_REQUEST);
    assert!(dup.text().contains("already in the library"));
    assert!(
        dup.text()
            .contains("value=\"https://radio.nrk.no/podkast/abels_taarn/l_123\"")
    );

    // A series, from a programme on, into a chosen category.
    app.fetch.answer(
        &psapi("series", "radioteatret"),
        200,
        json!({"titles": {"title": "Radioteatret"}}).to_string(),
    );
    let eventyr: i64 = sqlx::query_scalar("SELECT id FROM music_categories WHERE name = 'Eventyr'")
        .fetch_one(&app.db)
        .await
        .unwrap();
    let res = app
        .request_form(
            Method::POST,
            "/music",
            Some(&cookie),
            &[
                (
                    "link",
                    "radio.nrk.no/serie/radioteatret/sesong/2019/MSPO30001119",
                ),
                ("category", &eventyr.to_string()),
            ],
        )
        .await;
    assert_eq!(res.status, StatusCode::SEE_OTHER);
    let (target, key, category): (String, String, i64) = sqlx::query_as(
        "SELECT target, key, category_id FROM music_entries WHERE name = 'Radioteatret'",
    )
    .fetch_one(&app.db)
    .await
    .unwrap();
    assert_eq!(
        target,
        "https://radio.nrk.no/serie/radioteatret/MSPO30001119"
    );
    assert_eq!(key, crate::music::state_key(&target));
    assert_eq!(key.len(), 12);
    assert_eq!(category, eventyr);
    assert_eq!(
        res.location(),
        Some(format!("/music#entry-{}", entry_id_by_key(&app, &key).await).as_str()),
        "back to the new entry, not the top"
    );

    // RSS: a feed is added under its title; its key is sha1(target)[:12].
    app.fetch.answer("https://example.org/feed.xml", 200, FEED);
    let res = app
        .request_form(
            Method::POST,
            "/music",
            Some(&cookie),
            &[("link", "https://example.org/feed.xml#x")],
        )
        .await;
    assert_eq!(res.status, StatusCode::SEE_OTHER, "{}", res.text());
    let (name, source, key): (String, String, String) = sqlx::query_as(
        "SELECT name, source, key FROM music_entries WHERE target = 'https://example.org/feed.xml'",
    )
    .fetch_one(&app.db)
    .await
    .unwrap();
    assert_eq!((name.as_str(), source.as_str()), ("Ukas nyheter", "rss"));
    assert_eq!(key, crate::music::state_key("https://example.org/feed.xml"));
    assert_eq!(events(&app, "music_entry_added").await.len(), 3);
}

#[tokio::test]
async fn links_that_dont_check_out_are_refused_with_the_link_kept() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    app.fetch
        .answer(&psapi("podcast", "finnes_ikke"), 404, "{}".as_bytes());
    app.fetch.answer(
        "https://example.org/page",
        200,
        "<!doctype html><html><title>Hei</title></html>".as_bytes(),
    );
    app.fetch.answer(
        "https://example.org/empty.rss",
        200,
        "<rss><channel><title>T</title></channel></rss>".as_bytes(),
    );
    app.fetch
        .answer("https://example.org/down.rss", 503, "".as_bytes());
    for (link, message) in [
        (
            "https://radio.nrk.no/podkast/finnes_ikke",
            "know that podcast or series",
        ),
        ("https://example.org/page", "a podcast feed (RSS)"),
        ("https://example.org/empty.rss", "no episodes with audio"),
        ("https://example.org/down.rss", "answered 503"),
        ("https://unreachable.example/feed", "reach the site"),
        ("ftp://example.org/x", "Only http and https"),
        ("https://radio.nrk.no/direkte/p1", "neither a podcast"),
        ("", "Paste a link first"),
    ] {
        let res = app
            .request_form(Method::POST, "/music", Some(&cookie), &[("link", link)])
            .await;
        assert_eq!(res.status, StatusCode::BAD_REQUEST, "{link}");
        let text = res.text();
        assert!(text.contains(message), "{link}: {text}");
        assert!(text.contains("id=\"add_link\""), "{link}");
        assert!(text.contains("autofocus"), "{link}: the field is focused");
    }
    let entries: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM music_entries")
        .fetch_one(&app.db)
        .await
        .unwrap();
    assert_eq!(entries, 0, "nothing was written");
}

#[tokio::test]
async fn own_entries_get_their_key_after_the_id_and_play_everything() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let id = add_own(&app, &cookie, "  Bilturen ").await;
    let row: (String, String, Option<String>, i64, bool, String) = sqlx::query_as(
        "SELECT e.name, e.key, e.target, e.cache, e.resume, c.name FROM music_entries e \
         JOIN music_categories c ON c.id = e.category_id WHERE e.id = ?",
    )
    .bind(id)
    .fetch_one(&app.db)
    .await
    .unwrap();
    assert_eq!(
        row,
        (
            "Bilturen".into(),
            format!("own-{id}"),
            None,
            -1,
            false,
            "Musikk".into()
        )
    );
    let res = app
        .request_form(
            Method::POST,
            "/music/own",
            Some(&cookie),
            &[("name", "   ")],
        )
        .await;
    assert_eq!(res.status, StatusCode::BAD_REQUEST);
    assert!(res.text().contains("Give it a name"));
}

#[tokio::test]
async fn the_library_holds_at_most_200_entries() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    sqlx::query(
        "WITH RECURSIVE n(i) AS (SELECT 1 UNION ALL SELECT i + 1 FROM n WHERE i < 200) \
         INSERT INTO music_entries (name, category_id, source, key) \
         SELECT 'E' || i, 1, 'own', 'own-x' || i FROM n",
    )
    .execute(&app.db)
    .await
    .unwrap();
    let res = app
        .request_form(
            Method::POST,
            "/music/own",
            Some(&cookie),
            &[("name", "One more")],
        )
        .await;
    assert_eq!(res.status, StatusCode::BAD_REQUEST);
    assert!(res.text().contains("200 entries"));
}

// ------------------------------------------------------------------------------------------------
// The policy and the library
// ------------------------------------------------------------------------------------------------

#[tokio::test]
async fn the_policy_carries_a_small_music_object() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (device, token) = app.enrolled_device("phone").await;
    assert_eq!(
        music_policy(&app, &token).await,
        json!({"library_version": null, "mobile_data": false, "volume_cap_pct": null, "storytel_generation": 0}),
        "nothing ticked, everything off"
    );
    let id = add_nrk(&app, &cookie, "abels_taarn", "Abels tårn").await;
    let res = tick(&app, &cookie, device, id, true).await;
    assert_eq!(
        res.location(),
        Some(format!("/devices/{device}#music").as_str())
    );
    let music = music_policy(&app, &token).await;
    let version = music["library_version"].as_str().unwrap().to_string();
    assert_eq!(version.len(), 16);
    assert!(version.bytes().all(|b| b.is_ascii_hexdigit()));

    let res = app
        .request_form(
            Method::POST,
            &format!("/devices/{device}/music"),
            Some(&cookie),
            &[("mobile_data", "on"), ("volume_cap", "70")],
        )
        .await;
    assert_eq!(
        res.location(),
        Some(format!("/devices/{device}#music").as_str())
    );
    let music = music_policy(&app, &token).await;
    assert_eq!(music["mobile_data"], json!(true));
    assert_eq!(music["volume_cap_pct"], json!(70));
    assert_eq!(
        music["library_version"],
        json!(version),
        "settings aren't in the library"
    );

    // Off and the other choices; anything else is refused and changes nothing.
    for cap in ["90", "80", "60"] {
        app.request_form(
            Method::POST,
            &format!("/devices/{device}/music"),
            Some(&cookie),
            &[("volume_cap", cap)],
        )
        .await;
        assert_eq!(
            music_policy(&app, &token).await["volume_cap_pct"],
            json!(cap.parse::<i64>().unwrap())
        );
    }
    for bad in ["100", "50", "75", "x"] {
        let res = app
            .request_form(
                Method::POST,
                &format!("/devices/{device}/music"),
                Some(&cookie),
                &[("volume_cap", bad)],
            )
            .await;
        assert_eq!(res.status, StatusCode::BAD_REQUEST, "{bad}");
    }
    assert_eq!(
        music_policy(&app, &token).await["volume_cap_pct"],
        json!(60)
    );
    app.request_form(
        Method::POST,
        &format!("/devices/{device}/music"),
        Some(&cookie),
        &[("volume_cap", "off")],
    )
    .await;
    let music = music_policy(&app, &token).await;
    assert_eq!(music["volume_cap_pct"], json!(null));
    assert_eq!(
        music["mobile_data"],
        json!(false),
        "a missing checkbox is off"
    );
    // A stored value that isn't a choice is sent as off.
    sqlx::query("UPDATE device_policy SET music_volume_cap_pct = 45 WHERE device_id = ?")
        .bind(device)
        .execute(&app.db)
        .await
        .unwrap();
    assert_eq!(
        music_policy(&app, &token).await["volume_cap_pct"],
        json!(null)
    );
}

#[tokio::test]
async fn a_failing_library_query_sends_music_null_and_never_fails_the_policy() {
    let app = TestApp::new().await;
    let (_, token) = app.enrolled_device("phone").await;
    sqlx::query("DROP TABLE device_music_entries")
        .execute(&app.db)
        .await
        .unwrap();
    let res = app
        .request(Method::GET, "/api/devices/policy", Some(&token), None)
        .await;
    assert_eq!(res.status, StatusCode::OK);
    let policy = res.json();
    assert_eq!(
        policy["music"],
        json!(null),
        "null, never an empty library (QA #3)"
    );
    assert!(policy["time_policy"].is_object());
}

/// Fixed data for the snapshot: a podcast with a cover, an RSS feed and own files (one missing).
async fn snapshot_library(app: &TestApp, device: i64) {
    let cover = "a".repeat(64);
    let art = "b".repeat(64);
    sqlx::query(
        "INSERT INTO music_entries (id, name, category_id, source, target, key, play_order, cache, \
         resume, cover_hash, sort) VALUES \
         (1, 'Abels tårn', 4, 'nrk', 'https://radio.nrk.no/podkast/abels_taarn', 'abels_taarn', \
          'auto', 5, 1, ?, 10), \
         (2, 'Ukas nyheter', 4, 'rss', 'https://example.org/feed.xml', '40201d4c16fc', \
          'newest_first', 3, 1, NULL, 20), \
         (3, 'Bilturen', 1, 'own', NULL, 'own-3', 'auto', -1, 0, NULL, 30), \
         (4, 'Not on this phone', 2, 'own', NULL, 'own-4', 'auto', -1, 0, NULL, 40)",
    )
    .bind(&cover)
    .execute(&app.db)
    .await
    .unwrap();
    sqlx::query(
        "INSERT INTO music_files (id, entry_id, path, original_name, size, sha256, title, artist, \
         album, track_no, duration_ms, art_hash, sort, missing) VALUES \
         (1, 3, '3/a.mp3', '01 Hjulene.mp3', 1000, ?, 'Hjulene på bussen', 'Barnekoret', 'Bil', \
          1, 125000, ?, 0, 0), \
         (2, 3, '3/b.mp3', '02 Lille.mp3', 2000, ?, NULL, NULL, NULL, 2, NULL, NULL, 1, 1), \
         (3, 4, '4/c.mp3', 'c.mp3', 3, ?, 'c', NULL, NULL, NULL, NULL, NULL, 0, 0)",
    )
    .bind("1".repeat(64))
    .bind(&art)
    .bind("2".repeat(64))
    .bind("3".repeat(64))
    .execute(&app.db)
    .await
    .unwrap();
    sqlx::query(
        "INSERT INTO device_music_entries (device_id, entry_id) VALUES (?, 1), (?, 2), (?, 3)",
    )
    .bind(device)
    .bind(device)
    .bind(device)
    .execute(&app.db)
    .await
    .unwrap();
}

/// Compact JSON indented by two spaces, keys in the order they were sent (serde_json's `Value`
/// would sort them).
fn pretty_json(compact: &str) -> String {
    let mut out = String::new();
    let mut depth = 0usize;
    let mut in_string = false;
    let mut escaped = false;
    let mut chars = compact.chars().peekable();
    let newline = |out: &mut String, depth: usize| {
        out.push('\n');
        out.push_str(&"  ".repeat(depth));
    };
    while let Some(c) = chars.next() {
        if in_string {
            out.push(c);
            match c {
                _ if escaped => escaped = false,
                '\\' => escaped = true,
                '"' => in_string = false,
                _ => {}
            }
            continue;
        }
        match c {
            '"' => {
                in_string = true;
                out.push(c);
            }
            '{' | '[' => {
                out.push(c);
                if matches!(chars.peek(), Some('}') | Some(']')) {
                    out.push(chars.next().unwrap());
                } else {
                    depth += 1;
                    newline(&mut out, depth);
                }
            }
            '}' | ']' => {
                depth -= 1;
                newline(&mut out, depth);
                out.push(c);
            }
            ',' => {
                out.push(c);
                newline(&mut out, depth);
            }
            ':' => out.push_str(": "),
            c => out.push(c),
        }
    }
    out.push('\n');
    out
}

/// `server/testdata/music_library.json` pins the library's shape (the music app keeps a
/// byte-identical copy). `MUSIC_LIBRARY_SNAPSHOT_WRITE=1` rewrites it.
#[tokio::test]
async fn music_library_snapshot() {
    let app = TestApp::new().await;
    let (device, token) = app.enrolled_device("phone").await;
    snapshot_library(&app, device).await;
    let res = device_get(&app, "/api/devices/music/library", &token, &[]).await;
    assert_eq!(res.status, StatusCode::OK);
    assert_eq!(
        res.headers.get(header::CONTENT_TYPE).unwrap(),
        "application/json"
    );
    let served = res.json();
    let pretty = pretty_json(&res.text());
    let path = "testdata/music_library.json";
    if std::env::var("MUSIC_LIBRARY_SNAPSHOT_WRITE").is_ok() {
        std::fs::write(path, &pretty).unwrap();
    }
    assert_eq!(
        pretty,
        std::fs::read_to_string(path).unwrap(),
        "the library changed (fields, order or values) - update both copies together"
    );
    assert_eq!(
        crate::music::state_key("https://example.org/feed.xml"),
        served["entries"][1]["key"]
    );
    let object = served.as_object().unwrap();
    let mut keys: Vec<&str> = object.keys().map(String::as_str).collect();
    keys.sort_unstable();
    assert_eq!(
        keys,
        ["categories", "covers", "entries", "files", "v", "version"]
    );
    assert_eq!(served["v"], json!(1));
    // The served bytes are in the documented order, with the version second.
    assert!(res.text().starts_with("{\"v\":1,\"version\":\""));
    let mut entry_keys: Vec<&str> = served["entries"][0]
        .as_object()
        .unwrap()
        .keys()
        .map(String::as_str)
        .collect();
    entry_keys.sort_unstable();
    assert_eq!(
        entry_keys,
        [
            "cache", "category", "cover", "id", "key", "name", "order", "resume", "sort", "source",
            "target"
        ]
    );
    let mut file_keys: Vec<&str> = served["files"][0]
        .as_object()
        .unwrap()
        .keys()
        .map(String::as_str)
        .collect();
    file_keys.sort_unstable();
    assert_eq!(
        file_keys,
        [
            "art",
            "artist",
            "duration_ms",
            "entry",
            "id",
            "missing",
            "sha256",
            "size",
            "sort",
            "title",
            "track"
        ]
    );
    let mut category_keys: Vec<&str> = served["categories"][0]
        .as_object()
        .unwrap()
        .keys()
        .map(String::as_str)
        .collect();
    category_keys.sort_unstable();
    assert_eq!(category_keys, ["color", "icon", "id", "name", "sort"]);
    // Only this phone's entries, their categories, files and covers.
    assert_eq!(served["entries"].as_array().unwrap().len(), 3);
    assert_eq!(served["categories"].as_array().unwrap().len(), 2);
    assert_eq!(served["files"].as_array().unwrap().len(), 2);
    assert_eq!(served["files"][1]["missing"], json!(true));
    assert_eq!(
        served["files"][1]["title"],
        json!("02 Lille"),
        "the file name without a tag"
    );
    assert_eq!(served["covers"], json!(["a".repeat(64), "b".repeat(64)]));
    // The policy names the same version as the document and its ETag.
    let version = served["version"].as_str().unwrap();
    assert_eq!(
        music_policy(&app, &token).await["library_version"],
        json!(version)
    );
    assert_eq!(
        res.headers.get(header::ETAG).unwrap().to_str().unwrap(),
        format!("\"{version}\"")
    );
}

#[tokio::test]
async fn the_library_is_etagged_gzipped_and_only_this_phones() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (device, token) = app.enrolled_device("phone").await;
    let (other, other_token) = app.enrolled_device("other").await;
    let res = device_get(&app, "/api/devices/music/library", &token, &[]).await;
    assert_eq!(res.status, StatusCode::NOT_FOUND, "nothing ticked");
    let a = add_nrk(&app, &cookie, "abels_taarn", "Abels tårn").await;
    let b = add_nrk(&app, &cookie, "radiodokumentaren", "Radiodokumentaren").await;
    tick(&app, &cookie, device, a, true).await;
    tick(&app, &cookie, other, b, true).await;

    let res = device_get(&app, "/api/devices/music/library", &token, &[]).await;
    assert_eq!(res.status, StatusCode::OK);
    let etag = res
        .headers
        .get(header::ETAG)
        .unwrap()
        .to_str()
        .unwrap()
        .to_string();
    assert_eq!(
        res.json()["entries"].as_array().unwrap().len(),
        1,
        "not the other phone's"
    );
    assert_eq!(res.json()["entries"][0]["id"], json!(a));

    // If-None-Match -> 304 without a body; weak or in a list too.
    for header_value in [etag.clone(), format!("W/{etag}"), format!("\"x\", {etag}")] {
        let res = device_get(
            &app,
            "/api/devices/music/library",
            &token,
            &[("if-none-match", &header_value)],
        )
        .await;
        assert_eq!(res.status, StatusCode::NOT_MODIFIED, "{header_value}");
        assert!(res.body.is_empty());
        assert_eq!(
            res.headers.get(header::ETAG).unwrap().to_str().unwrap(),
            etag
        );
    }

    // gzip when asked for.
    let res = device_get(
        &app,
        "/api/devices/music/library",
        &token,
        &[("accept-encoding", "gzip")],
    )
    .await;
    assert_eq!(res.headers.get(header::CONTENT_ENCODING).unwrap(), "gzip");
    let mut plain = String::new();
    flate2::read::GzDecoder::new(res.body.as_slice())
        .read_to_string(&mut plain)
        .unwrap();
    let parsed: Value = serde_json::from_str(&plain).unwrap();
    assert_eq!(parsed["entries"][0]["id"], json!(a));

    // A change to the entry is a new version; the other phone's isn't touched.
    let other_version = music_policy(&app, &other_token).await["library_version"].clone();
    let res = app
        .request_form(
            Method::POST,
            &format!("/music/entries/{a}"),
            Some(&cookie),
            &[
                ("name", "Abels tårn 2"),
                ("category", "4"),
                ("order", "oldest_first"),
                ("cache", "10"),
                ("resume", "on"),
            ],
        )
        .await;
    assert_eq!(
        res.location(),
        Some(format!("/music/entries/{a}#entry-{a}").as_str())
    );
    let res = device_get(
        &app,
        "/api/devices/music/library",
        &token,
        &[("if-none-match", &etag)],
    )
    .await;
    assert_eq!(res.status, StatusCode::OK);
    assert_ne!(
        res.headers.get(header::ETAG).unwrap().to_str().unwrap(),
        etag
    );
    assert_eq!(res.json()["entries"][0]["order"], json!("oldest_first"));
    assert_eq!(res.json()["entries"][0]["cache"], json!(10));
    assert_eq!(
        music_policy(&app, &other_token).await["library_version"],
        other_version
    );

    // No token, no library.
    let res = app
        .request(Method::GET, "/api/devices/music/library", None, None)
        .await;
    assert_eq!(res.status, StatusCode::UNAUTHORIZED);
}

#[tokio::test]
async fn ticks_past_3_mb_are_refused() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (device, token) = app.enrolled_device("phone").await;
    let small = add_own(&app, &cookie, "Small").await;
    let big = add_own(&app, &cookie, "Big").await;
    // 6000 rows with long titles (the upload limit doesn't apply to rows written here).
    sqlx::query(
        "WITH RECURSIVE n(i) AS (SELECT 1 UNION ALL SELECT i + 1 FROM n WHERE i < 6000) \
         INSERT INTO music_files (entry_id, path, original_name, size, sha256, title) \
         SELECT ?, ? || '/f' || i || '.mp3', 'f.mp3', 1, ?, printf('%.400c', 'x') FROM n",
    )
    .bind(big)
    .bind(big.to_string())
    .bind("0".repeat(64))
    .execute(&app.db)
    .await
    .unwrap();
    assert_eq!(
        tick(&app, &cookie, device, small, true).await.status,
        StatusCode::SEE_OTHER
    );
    let res = tick(&app, &cookie, device, big, true).await;
    assert_eq!(
        res.location(),
        Some(format!("/devices/{device}?music_notice=too_big#music").as_str())
    );
    let ticked: Vec<i64> =
        sqlx::query_scalar("SELECT entry_id FROM device_music_entries WHERE device_id = ?")
            .bind(device)
            .fetch_all(&app.db)
            .await
            .unwrap();
    assert_eq!(ticked, vec![small]);
    let page = app
        .get_page(&format!("/devices/{device}?music_notice=too_big"), &cookie)
        .await
        .text();
    assert!(page.contains("bigger than 3 MB"));
    assert!(music_policy(&app, &token).await["library_version"].is_string());
}

// ------------------------------------------------------------------------------------------------
// Own files and covers
// ------------------------------------------------------------------------------------------------

#[tokio::test]
async fn uploads_are_read_by_content_ordered_and_scoped() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (device, token) = app.enrolled_device("phone").await;
    let (_, other_token) = app.enrolled_device("other").await;
    let entry = add_own(&app, &cookie, "Bilturen").await;
    tick(&app, &cookie, device, entry, true).await;
    let mut rx = app.state.command_notify.subscribe();

    let second = tagged_wav("Lille Petter", "Koret", 2, None);
    let first = tagged_wav("Hjulene på bussen", "Koret", 1, Some(png(600, 400)));
    let res = upload(
        &app,
        &cookie,
        entry,
        &[("b.wav", second.clone()), ("a.mp3", first.clone())],
    )
    .await;
    assert_eq!(res.status, StatusCode::SEE_OTHER, "{}", res.text());
    assert_eq!(
        res.location(),
        Some(format!("/music/entries/{entry}#files").as_str())
    );
    assert_eq!(nudged(&mut rx), vec![device]);

    let library = device_get(&app, "/api/devices/music/library", &token, &[])
        .await
        .json();
    let files = library["files"].as_array().unwrap();
    assert_eq!(files.len(), 2);
    assert_eq!(files[0]["title"], json!("Hjulene på bussen"), "track order");
    assert_eq!(files[0]["track"], json!(1));
    assert_eq!(files[0]["artist"], json!("Koret"));
    assert_eq!(files[0]["duration_ms"], json!(1500));
    assert_eq!(files[1]["title"], json!("Lille Petter"));
    assert_eq!(
        files[0]["sha256"],
        json!(hex::encode(Sha256::digest(&first))),
        "hashed while streaming"
    );
    assert_eq!(files[0]["size"], json!(first.len()));
    let art = files[0]["art"].as_str().unwrap().to_string();
    assert_eq!(files[1]["art"], json!(null));
    assert_eq!(library["covers"], json!([art.clone()]));
    let (path, stored_ext): (String, String) = sqlx::query_as(
        "SELECT path, original_name FROM music_files WHERE title = 'Hjulene på bussen'",
    )
    .fetch_one(&app.db)
    .await
    .unwrap();
    assert!(
        path.ends_with(".wav"),
        "stored by what it is, not its name: {path}"
    );
    assert_eq!(stored_ext, "a.mp3");

    // The embedded art: a square JPEG of at most 512 px, served only to a phone with the entry.
    let res = device_get(
        &app,
        &format!("/api/devices/music/covers/{art}"),
        &token,
        &[],
    )
    .await;
    assert_eq!(res.status, StatusCode::OK);
    assert_eq!(res.headers.get(header::CONTENT_TYPE).unwrap(), "image/jpeg");
    let image = image::load_from_memory(&res.body).unwrap();
    assert_eq!((image.width(), image.height()), (400, 400));
    let res = device_get(
        &app,
        &format!("/api/devices/music/covers/{art}"),
        &other_token,
        &[],
    )
    .await;
    assert_eq!(res.status, StatusCode::NOT_FOUND);
    let res = device_get(&app, "/api/devices/music/covers/nothex", &token, &[]).await;
    assert_eq!(res.status, StatusCode::NOT_FOUND);

    // The file itself: whole, a Range, If-Match, past the end; another phone gets nothing.
    let id = files[0]["id"].as_i64().unwrap();
    let uri = format!("/api/devices/music/files/{id}");
    let res = device_get(&app, &uri, &token, &[]).await;
    assert_eq!(res.status, StatusCode::OK);
    assert_eq!(res.body, first);
    assert_eq!(
        res.headers
            .get("x-content-sha256")
            .unwrap()
            .to_str()
            .unwrap(),
        hex::encode(Sha256::digest(&first))
    );
    let etag = res
        .headers
        .get(header::ETAG)
        .unwrap()
        .to_str()
        .unwrap()
        .to_string();
    let res = device_get(
        &app,
        &uri,
        &token,
        &[("range", "bytes=10-"), ("if-match", &etag)],
    )
    .await;
    assert_eq!(res.status, StatusCode::PARTIAL_CONTENT);
    assert_eq!(res.body, first[10..]);
    let res = device_get(
        &app,
        &uri,
        &token,
        &[("range", "bytes=10-"), ("if-match", "\"other\"")],
    )
    .await;
    assert_eq!(res.status, StatusCode::PRECONDITION_FAILED);
    let res = device_get(
        &app,
        &uri,
        &token,
        &[("range", &format!("bytes={}-", first.len() + 5))],
    )
    .await;
    assert_eq!(res.status, StatusCode::RANGE_NOT_SATISFIABLE);
    assert_eq!(
        res.headers
            .get(header::CONTENT_RANGE)
            .unwrap()
            .to_str()
            .unwrap(),
        format!("bytes */{}", first.len())
    );
    assert_eq!(
        device_get(&app, &uri, &other_token, &[]).await.status,
        StatusCode::NOT_FOUND
    );
    assert_eq!(
        device_get(&app, "/api/devices/music/files/999", &token, &[])
            .await
            .status,
        StatusCode::NOT_FOUND
    );

    // Unticked: gone for this phone too.
    tick(&app, &cookie, device, entry, false).await;
    assert_eq!(
        device_get(&app, &uri, &token, &[]).await.status,
        StatusCode::NOT_FOUND
    );
    assert_eq!(
        device_get(
            &app,
            &format!("/api/devices/music/covers/{art}"),
            &token,
            &[]
        )
        .await
        .status,
        StatusCode::NOT_FOUND
    );
    assert_eq!(
        events(&app, "music_files_added").await,
        vec![format!("entry {entry}: 2 file(s)")]
    );
}

#[tokio::test]
async fn untagged_files_sort_by_name_and_non_audio_is_refused() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let entry = add_own(&app, &cookie, "Opptak").await;
    let res = upload(
        &app,
        &cookie,
        entry,
        &[
            ("b.wav", wav(500)),
            ("notes.txt", b"just some text, not audio".to_vec()),
            ("A.wav", wav(700)),
            ("../../evil.wav", wav(300)),
        ],
    )
    .await;
    assert_eq!(res.status, StatusCode::BAD_REQUEST);
    let text = res.text();
    assert!(text.contains("notes.txt: not an audio file"), "{text}");
    assert!(text.contains("Saved 3 files"), "{text}");
    assert!(text.contains("id=\"music_files\""));
    let names: Vec<String> = sqlx::query_scalar(
        "SELECT original_name FROM music_files WHERE entry_id = ? ORDER BY sort",
    )
    .bind(entry)
    .fetch_all(&app.db)
    .await
    .unwrap();
    assert_eq!(
        names,
        vec!["A.wav", "b.wav", "evil.wav"],
        "by file name; no path parts"
    );
    // Nothing else on disk: no temp file, no refused file.
    let on_disk = std::fs::read_dir(app.state.music_files_dir.join(entry.to_string()))
        .unwrap()
        .count();
    assert_eq!(on_disk, 3);

    // A podcast entry takes no uploads.
    let podcast = add_nrk(&app, &cookie, "abels_taarn", "Abels tårn").await;
    let res = upload(&app, &cookie, podcast, &[("a.wav", wav(100))]).await;
    assert_eq!(res.status, StatusCode::BAD_REQUEST);
    assert!(res.text().contains("Only an own-files entry"));

    // Deleting a file removes it from disk and re-sorts.
    let first: i64 = sqlx::query_scalar(
        "SELECT id FROM music_files WHERE entry_id = ? AND original_name = 'A.wav'",
    )
    .bind(entry)
    .fetch_one(&app.db)
    .await
    .unwrap();
    let res = app
        .request_form(
            Method::POST,
            &format!("/music/entries/{entry}/files/{first}/delete"),
            Some(&cookie),
            &[],
        )
        .await;
    assert_eq!(
        res.location(),
        Some(format!("/music/entries/{entry}#files").as_str())
    );
    let rows: Vec<(String, i64)> = sqlx::query_as(
        "SELECT original_name, sort FROM music_files WHERE entry_id = ? ORDER BY sort",
    )
    .bind(entry)
    .fetch_all(&app.db)
    .await
    .unwrap();
    assert_eq!(rows, vec![("b.wav".into(), 0), ("evil.wav".into(), 1)]);
    assert_eq!(
        std::fs::read_dir(app.state.music_files_dir.join(entry.to_string()))
            .unwrap()
            .count(),
        2
    );
    let res = app
        .request_form(
            Method::POST,
            &format!("/music/entries/{podcast}/files/{first}/delete"),
            Some(&cookie),
            &[],
        )
        .await;
    assert_eq!(res.status, StatusCode::NOT_FOUND, "another entry's file id");
}

#[tokio::test]
async fn an_upload_that_would_pass_3_mb_on_a_phone_is_taken_back() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (device, _) = app.enrolled_device("phone").await;
    let entry = add_own(&app, &cookie, "Nesten full").await;
    tick(&app, &cookie, device, entry, true).await;
    // Fill the library to just under the limit with one padding file.
    sqlx::query(
        "INSERT INTO music_files (entry_id, path, original_name, size, sha256, title) \
         VALUES (?, ?, 'pad.mp3', 1, ?, 'x')",
    )
    .bind(entry)
    .bind(format!("{entry}/pad.mp3"))
    .bind("0".repeat(64))
    .execute(&app.db)
    .await
    .unwrap();
    let size = crate::music::device_library(&app.db, device)
        .await
        .unwrap()
        .unwrap()
        .json
        .len();
    let pad = crate::music::MAX_LIBRARY_BYTES - size - 20 + 1;
    sqlx::query("UPDATE music_files SET title = ? WHERE original_name = 'pad.mp3'")
        .bind("x".repeat(pad))
        .execute(&app.db)
        .await
        .unwrap();
    let res = upload(&app, &cookie, entry, &[("a.wav", wav(100))]).await;
    assert_eq!(res.status, StatusCode::BAD_REQUEST);
    assert!(res.text().contains("bigger than 3 MB"), "{}", res.text());
    let files: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM music_files WHERE entry_id = ?")
        .bind(entry)
        .fetch_one(&app.db)
        .await
        .unwrap();
    assert_eq!(files, 1, "only the padding row is left");
    assert_eq!(
        std::fs::read_dir(app.state.music_files_dir.join(entry.to_string()))
            .unwrap()
            .count(),
        0,
        "the uploaded file went again"
    );
}

#[tokio::test]
async fn missing_files_stay_listed_and_are_not_served() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (device, token) = app.enrolled_device("phone").await;
    let entry = add_own(&app, &cookie, "Bilturen").await;
    tick(&app, &cookie, device, entry, true).await;
    upload(&app, &cookie, entry, &[("a.wav", wav(200))]).await;
    let (id, path): (i64, String) = sqlx::query_as("SELECT id, path FROM music_files")
        .fetch_one(&app.db)
        .await
        .unwrap();
    let full = app.state.music_files_dir.join(&path);

    // A restore without the audio: flagged at startup, still listed, a 404 for the file.
    std::fs::rename(&full, full.with_extension("away")).unwrap();
    crate::music::flag_missing_files(&app.db, &app.state.music_files_dir).await;
    let library = device_get(&app, "/api/devices/music/library", &token, &[])
        .await
        .json();
    assert_eq!(library["files"][0]["id"], json!(id));
    assert_eq!(library["files"][0]["missing"], json!(true));
    let res = device_get(&app, &format!("/api/devices/music/files/{id}"), &token, &[]).await;
    assert_eq!(res.status, StatusCode::NOT_FOUND);
    let page = app
        .get_page(&format!("/music/entries/{entry}"), &cookie)
        .await
        .text();
    assert!(page.contains("missing on this server"));
    let list = app.get_page("/music", &cookie).await.text();
    assert!(list.contains("1 missing on this server"));

    // Back on disk: no longer missing.
    std::fs::rename(full.with_extension("away"), &full).unwrap();
    crate::music::flag_missing_files(&app.db, &app.state.music_files_dir).await;
    let library = device_get(&app, "/api/devices/music/library", &token, &[])
        .await
        .json();
    assert_eq!(library["files"][0]["missing"], json!(false));
    let res = device_get(&app, &format!("/api/devices/music/files/{id}"), &token, &[]).await;
    assert_eq!(res.status, StatusCode::OK);

    // Gone while serving: flagged then and there.
    std::fs::remove_file(&full).unwrap();
    let res = device_get(&app, &format!("/api/devices/music/files/{id}"), &token, &[]).await;
    assert_eq!(res.status, StatusCode::NOT_FOUND);
    let missing: bool = sqlx::query_scalar("SELECT missing FROM music_files WHERE id = ?")
        .bind(id)
        .fetch_one(&app.db)
        .await
        .unwrap();
    assert!(missing);
}

#[tokio::test]
async fn covers_are_uploaded_processed_backed_up_and_pruned() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (device, token) = app.enrolled_device("phone").await;
    let entry = add_nrk(&app, &cookie, "abels_taarn", "Abels tårn").await;
    tick(&app, &cookie, device, entry, true).await;
    let res = upload_cover(&app, &cookie, entry, &png(900, 600)).await;
    assert_eq!(
        res.location(),
        Some(format!("/music/entries/{entry}#cover").as_str())
    );
    let hash: String = sqlx::query_scalar("SELECT cover_hash FROM music_entries WHERE id = ?")
        .bind(entry)
        .fetch_one(&app.db)
        .await
        .unwrap();
    let library = device_get(&app, "/api/devices/music/library", &token, &[])
        .await
        .json();
    assert_eq!(library["entries"][0]["cover"], json!(hash));
    let res = device_get(
        &app,
        &format!("/api/devices/music/covers/{hash}"),
        &token,
        &[],
    )
    .await;
    let image = image::load_from_memory(&res.body).unwrap();
    assert_eq!((image.width(), image.height()), (512, 512));
    let res = app
        .get_page(&format!("/music-covers/{hash}"), &cookie)
        .await;
    assert_eq!(res.status, StatusCode::OK);

    // In the backup zip; recovered after a restore; forgotten when in no backup.
    let backups = tempfile::tempdir().unwrap();
    let db = backups.path().join("snapshot.db");
    std::fs::write(&db, b"db").unwrap();
    let zip_path = backups.path().join("backup-1.zip");
    crate::handlers::backups::build_backup_zip(
        db.to_str().unwrap(),
        zip_path.to_str().unwrap(),
        &app.state.photo_dir,
        &app.state.wallpaper_dir,
        &app.state.music_cover_dir,
    )
    .unwrap();
    let mut archive = zip::ZipArchive::new(std::fs::File::open(&zip_path).unwrap()).unwrap();
    assert!(archive.by_name(&format!("music_covers/{hash}.jpg")).is_ok());
    let cover_file = app.state.music_cover_dir.join(format!("{hash}.jpg"));
    std::fs::remove_file(&cover_file).unwrap();
    crate::photos::recover_missing(&app.state, backups.path()).await;
    assert!(cover_file.exists(), "taken from the backup");
    std::fs::remove_file(&cover_file).unwrap();
    std::fs::remove_file(&zip_path).unwrap();
    crate::photos::recover_missing(&app.state, backups.path()).await;
    let gone: Option<String> =
        sqlx::query_scalar("SELECT cover_hash FROM music_entries WHERE id = ?")
            .bind(entry)
            .fetch_one(&app.db)
            .await
            .unwrap();
    assert_eq!(
        gone, None,
        "in no backup: the entry falls back to the source's art"
    );

    // A new cover, then removed: the file is pruned.
    upload_cover(&app, &cookie, entry, &png(300, 300)).await;
    let hash: String = sqlx::query_scalar("SELECT cover_hash FROM music_entries WHERE id = ?")
        .bind(entry)
        .fetch_one(&app.db)
        .await
        .unwrap();
    let res = app
        .request_form(
            Method::POST,
            &format!("/music/entries/{entry}/cover/remove"),
            Some(&cookie),
            &[],
        )
        .await;
    assert_eq!(
        res.location(),
        Some(format!("/music/entries/{entry}#cover").as_str())
    );
    assert!(
        !app.state
            .music_cover_dir
            .join(format!("{hash}.jpg"))
            .exists()
    );

    // Not an image: 400, the field focused.
    let res = upload_cover(&app, &cookie, entry, b"not an image").await;
    assert_eq!(res.status, StatusCode::BAD_REQUEST);
    assert!(res.text().contains("id=\"cover_file\""));
}

#[tokio::test]
async fn deleting_an_entry_removes_its_ticks_files_and_covers() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (device, token) = app.enrolled_device("phone").await;
    let entry = add_own(&app, &cookie, "Bilturen").await;
    tick(&app, &cookie, device, entry, true).await;
    upload(
        &app,
        &cookie,
        entry,
        &[("a.wav", tagged_wav("A", "B", 1, Some(png(64, 64))))],
    )
    .await;
    let art: String = sqlx::query_scalar("SELECT art_hash FROM music_files")
        .fetch_one(&app.db)
        .await
        .unwrap();
    let mut rx = app.state.command_notify.subscribe();
    let res = app
        .request_form(
            Method::POST,
            &format!("/music/entries/{entry}/delete"),
            Some(&cookie),
            &[],
        )
        .await;
    assert_eq!(res.location(), Some("/music#entries"));
    assert_eq!(nudged(&mut rx), vec![device]);
    let left: (i64, i64) = sqlx::query_as(
        "SELECT (SELECT COUNT(*) FROM music_files), (SELECT COUNT(*) FROM device_music_entries)",
    )
    .fetch_one(&app.db)
    .await
    .unwrap();
    assert_eq!(left, (0, 0));
    assert!(!app.state.music_files_dir.join(entry.to_string()).exists());
    assert!(
        !app.state
            .music_cover_dir
            .join(format!("{art}.jpg"))
            .exists()
    );
    assert_eq!(
        music_policy(&app, &token).await["library_version"],
        json!(null)
    );
    assert_eq!(
        events(&app, "music_entry_deleted").await,
        vec![format!("entry {entry}: Bilturen")]
    );
}

// ------------------------------------------------------------------------------------------------
// Entries, categories and the device card
// ------------------------------------------------------------------------------------------------

#[tokio::test]
async fn entry_settings_are_checked_and_keep_the_place() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (device, _) = app.enrolled_device("phone").await;
    let entry = add_nrk(&app, &cookie, "abels_taarn", "Abels tårn").await;
    tick(&app, &cookie, device, entry, true).await;
    let mut rx = app.state.command_notify.subscribe();
    let uri = format!("/music/entries/{entry}");
    for (fields, field, message) in [
        (
            vec![
                ("name", ""),
                ("category", "4"),
                ("order", "auto"),
                ("cache", "5"),
            ],
            "entry_name",
            "Give it a name",
        ),
        (
            vec![
                ("name", "X"),
                ("category", "99"),
                ("order", "auto"),
                ("cache", "5"),
            ],
            "entry_category",
            "Pick one of the categories",
        ),
        (
            vec![
                ("name", "X"),
                ("category", "4"),
                ("order", "random"),
                ("cache", "5"),
            ],
            "entry_order",
            "Pick one of the orders",
        ),
        (
            vec![
                ("name", "X"),
                ("category", "4"),
                ("order", "auto"),
                ("cache", "7"),
            ],
            "entry_cache",
            "Pick one of the offline",
        ),
    ] {
        let res = app
            .request_form(Method::POST, &uri, Some(&cookie), &fields)
            .await;
        assert_eq!(res.status, StatusCode::BAD_REQUEST, "{message}");
        let text = res.text();
        assert!(text.contains(message), "{text}");
        let field_tag = text
            .split(&format!("id=\"{field}\""))
            .nth(1)
            .and_then(|rest| rest.split('>').next())
            .unwrap();
        assert!(field_tag.contains("autofocus"), "{field} is focused");
    }
    assert!(nudged(&mut rx).is_empty(), "nothing saved, nothing nudged");
    let res = app
        .request_form(
            Method::POST,
            &uri,
            Some(&cookie),
            &[
                ("name", "Abels tårn"),
                ("category", "2"),
                ("order", "newest_first"),
                ("cache", "-1"),
            ],
        )
        .await;
    assert_eq!(
        res.location(),
        Some(format!("/music/entries/{entry}#entry-{entry}").as_str())
    );
    assert_eq!(nudged(&mut rx), vec![device]);
    let row: (i64, String, i64, bool) = sqlx::query_as(
        "SELECT category_id, play_order, cache, resume FROM music_entries WHERE id = ?",
    )
    .bind(entry)
    .fetch_one(&app.db)
    .await
    .unwrap();
    assert_eq!(
        row,
        (2, "newest_first".into(), -1, false),
        "a missing resume box is off"
    );

    // Own files: order and depth stay fixed whatever is posted.
    let own = add_own(&app, &cookie, "Bilturen").await;
    app.request_form(
        Method::POST,
        &format!("/music/entries/{own}"),
        Some(&cookie),
        &[
            ("name", "Bilturen"),
            ("category", "1"),
            ("order", "newest_first"),
            ("cache", "3"),
            ("resume", "on"),
        ],
    )
    .await;
    let row: (String, i64, bool) =
        sqlx::query_as("SELECT play_order, cache, resume FROM music_entries WHERE id = ?")
            .bind(own)
            .fetch_one(&app.db)
            .await
            .unwrap();
    assert_eq!(row, ("auto".into(), -1, true));
    let page = app
        .get_page(&format!("/music/entries/{own}"), &cookie)
        .await
        .text();
    assert!(
        !page.contains("id=\"entry_cache\""),
        "no offline depth for own files"
    );
    assert!(page.contains("id=\"files\""));
}

#[tokio::test]
async fn entries_and_categories_move_and_categories_in_use_stay() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (device, token) = app.enrolled_device("phone").await;
    let a = add_nrk(&app, &cookie, "a", "A").await;
    let b = add_nrk(&app, &cookie, "b", "B").await;
    tick(&app, &cookie, device, a, true).await;
    tick(&app, &cookie, device, b, true).await;
    let res = app
        .request_form(
            Method::POST,
            &format!("/music/entries/{b}/move"),
            Some(&cookie),
            &[("direction", "up")],
        )
        .await;
    assert_eq!(res.location(), Some(format!("/music#entry-{b}").as_str()));
    let library = device_get(&app, "/api/devices/music/library", &token, &[])
        .await
        .json();
    assert_eq!(
        library["entries"][0]["id"],
        json!(b),
        "the carousel order follows"
    );

    // A new category: checked, then added last.
    for (fields, message) in [
        (
            vec![("name", ""), ("icon", "star"), ("color", "teal")],
            "Give the category a name",
        ),
        (
            vec![("name", "Sport"), ("icon", "rocket"), ("color", "teal")],
            "Pick one of the icons",
        ),
        (
            vec![("name", "Sport"), ("icon", "star"), ("color", "red")],
            "Pick one of the colours",
        ),
        (
            vec![
                ("name", "A name far too long here"),
                ("icon", "star"),
                ("color", "teal"),
            ],
            "at most 20",
        ),
    ] {
        let res = app
            .request_form(Method::POST, "/music/categories", Some(&cookie), &fields)
            .await;
        assert_eq!(res.status, StatusCode::BAD_REQUEST, "{message}");
        assert!(res.text().contains(message), "{message}");
    }
    let res = app
        .request_form(
            Method::POST,
            "/music/categories",
            Some(&cookie),
            &[
                ("name", "Godnatt"),
                ("icon", "bedtime"),
                ("color", "indigo"),
            ],
        )
        .await;
    let godnatt: i64 = sqlx::query_scalar("SELECT id FROM music_categories WHERE name = 'Godnatt'")
        .fetch_one(&app.db)
        .await
        .unwrap();
    assert_eq!(
        res.location(),
        Some(format!("/music#category-{godnatt}").as_str())
    );

    // Saving one in use nudges its phones and changes the library; a bad save keeps the values.
    let mut rx = app.state.command_notify.subscribe();
    let res = app
        .request_form(
            Method::POST,
            "/music/categories/4",
            Some(&cookie),
            &[
                ("name", "Podcaster"),
                ("icon", "headphones"),
                ("color", "sky"),
            ],
        )
        .await;
    assert_eq!(res.location(), Some("/music#category-4"));
    assert_eq!(nudged(&mut rx), vec![device]);
    let library = device_get(&app, "/api/devices/music/library", &token, &[])
        .await
        .json();
    assert_eq!(
        library["categories"],
        json!([{"id": 4, "name": "Podcaster", "icon": "headphones", "color": "sky", "sort": 40}])
    );
    let res = app
        .request_form(
            Method::POST,
            "/music/categories/4",
            Some(&cookie),
            &[("name", "Nytt navn"), ("icon", "nope"), ("color", "sky")],
        )
        .await;
    assert_eq!(res.status, StatusCode::BAD_REQUEST);
    assert!(res.text().contains("value=\"Nytt navn\""));

    // Moving: the tiles' order.
    let res = app
        .request_form(
            Method::POST,
            &format!("/music/categories/{godnatt}/move"),
            Some(&cookie),
            &[("direction", "up")],
        )
        .await;
    assert_eq!(
        res.location(),
        Some(format!("/music#category-{godnatt}").as_str())
    );
    let order: Vec<String> =
        sqlx::query_scalar("SELECT name FROM music_categories ORDER BY sort, id")
            .fetch_all(&app.db)
            .await
            .unwrap();
    assert_eq!(
        order,
        vec!["Musikk", "Eventyr", "Lydbøker", "Godnatt", "Podcaster"]
    );

    // In use: refused, the card says why. Unused: deleted.
    let res = app
        .request_form(
            Method::POST,
            "/music/categories/4/delete",
            Some(&cookie),
            &[],
        )
        .await;
    assert_eq!(res.status, StatusCode::BAD_REQUEST);
    assert!(res.text().contains("2 entries use this category"));
    let res = app
        .request_form(
            Method::POST,
            &format!("/music/categories/{godnatt}/delete"),
            Some(&cookie),
            &[],
        )
        .await;
    assert_eq!(res.location(), Some("/music#categories"));
    let left: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM music_categories")
        .fetch_one(&app.db)
        .await
        .unwrap();
    assert_eq!(left, 4);
    for kind in [
        "music_category_added",
        "music_category_changed",
        "music_category_moved",
        "music_category_deleted",
        "music_entry_moved",
    ] {
        assert_eq!(events(&app, kind).await.len(), 1, "{kind}");
    }
}

#[tokio::test]
async fn the_device_card_ticks_entries_and_switches_the_app() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (device, token) = app.enrolled_device("phone").await;
    let (other, _) = app.enrolled_device("other").await;
    let entry = add_nrk(&app, &cookie, "abels_taarn", "Abels tårn").await;

    // No catalog row yet: the card says where to add it; /music adds it.
    let page = app
        .get_page(&format!("/devices/{device}"), &cookie)
        .await
        .text();
    assert!(page.contains("id=\"music\""));
    assert!(page.contains("isn't in the Apps catalog yet"));
    let res = app
        .request_form(Method::POST, "/music/catalog-row", Some(&cookie), &[])
        .await;
    assert_eq!(res.location(), Some("/music#app"));
    let (app_id, repo, prefix): (i64, String, String) = sqlx::query_as(
        "SELECT id, github_repo, release_tag_prefix FROM tracked_apps WHERE package_name = 'me.vibb.music'",
    )
    .fetch_one(&app.db)
    .await
    .unwrap();
    assert_eq!(
        (repo.as_str(), prefix.as_str()),
        ("palchrb/vibb-launcher", "music-v")
    );
    app.request_form(Method::POST, "/music/catalog-row", Some(&cookie), &[])
        .await;
    let rows: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM tracked_apps")
        .fetch_one(&app.db)
        .await
        .unwrap();
    assert_eq!(rows, 1, "only once");

    // The app switch is the catalog toggle (installs and allowlists), back to the card.
    let res = app
        .request_form(
            Method::POST,
            &format!("/devices/{device}/apps/toggle"),
            Some(&cookie),
            &[
                ("tracked_app_id", &app_id.to_string()),
                ("package_name", "me.vibb.music"),
                ("anchor", "music"),
                ("selected", "on"),
            ],
        )
        .await;
    assert_eq!(
        res.location(),
        Some(format!("/devices/{device}#music").as_str())
    );
    let policy = app
        .request(Method::GET, "/api/devices/policy", Some(&token), None)
        .await
        .json();
    assert!(
        policy["allowlist"]
            .as_array()
            .unwrap()
            .contains(&json!("me.vibb.music"))
    );

    let mut rx = app.state.command_notify.subscribe();
    tick(&app, &cookie, device, entry, true).await;
    assert_eq!(nudged(&mut rx), vec![device], "only that phone");
    let page = app
        .get_page(&format!("/devices/{device}"), &cookie)
        .await
        .text();
    let card = page.split("id=\"music\"").nth(1).unwrap();
    assert!(card.contains("Abels tårn"));
    assert!(card.contains(&format!(
        "action=\"/devices/{device}/music/entries/{entry}\""
    )));
    assert!(card.contains("checked onchange"), "ticked and auto-saving");
    assert!(card.contains("1 entry"));
    let other_page = app
        .get_page(&format!("/devices/{other}"), &cookie)
        .await
        .text();
    let other_card = other_page.split("id=\"music\"").nth(1).unwrap();
    assert!(
        !other_card
            .contains("checked onchange=\"this.form.submit()\">\n                    <span>Abels")
    );

    // Unticking.
    tick(&app, &cookie, device, entry, false).await;
    assert_eq!(
        music_policy(&app, &token).await["library_version"],
        json!(null)
    );
    assert_eq!(
        tick(&app, &cookie, device, 999, true).await.status,
        StatusCode::NOT_FOUND
    );
    assert_eq!(
        tick(&app, &cookie, 999, entry, true).await.status,
        StatusCode::NOT_FOUND
    );
    assert_eq!(
        events(&app, "music_phone_entry").await,
        vec![
            format!("device {device}: Abels tårn on"),
            format!("device {device}: Abels tårn off")
        ]
    );
}

#[tokio::test]
async fn the_phone_reports_music_state_with_known_fields_only() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (device, token) = app.enrolled_device("phone").await;
    let entry = add_nrk(&app, &cookie, "abels_taarn", "Abels tårn").await;
    tick(&app, &cookie, device, entry, true).await;
    let version = music_policy(&app, &token).await["library_version"]
        .as_str()
        .unwrap()
        .to_string();
    let res = app
        .request(
            Method::POST,
            "/api/devices/status",
            Some(&token),
            Some(json!({
                "lock_reason": "NONE",
                "kiosk_engaged": true,
                "capabilities": ["music_v1"],
                "music_state": {
                    "package": "me.vibb.music",
                    "version_code": 1000,
                    "bridge": "bound",
                    "library_version": version,
                    "cache_bytes": 12_300_000,
                    "downloads_waiting": 2,
                    "entry_errors": [{"entry": entry, "error": "http_503"}],
                    "storytel": "none",
                    "now_playing": {"title": "secret"},
                    "position_ms": 1234
                }
            })),
        )
        .await;
    assert_eq!(res.status, StatusCode::NO_CONTENT);
    let stored: String = sqlx::query_scalar("SELECT music_state_json FROM device_status")
        .fetch_one(&app.db)
        .await
        .unwrap();
    assert!(!stored.contains("secret") && !stored.contains("position"));
    let page = app
        .get_page(&format!("/devices/{device}"), &cookie)
        .await
        .text();
    let card = page.split("id=\"music\"").nth(1).unwrap();
    assert!(card.contains("Music app: me.vibb.music (version code 1000)"));
    assert!(card.contains("Library: up to date on the phone"));
    assert!(card.contains("Downloads waiting: 2"));
    assert!(card.contains("12.3 MB"));
    assert!(
        card.contains("Abels tårn&#34; on the phone: the source answered 503"),
        "{card}"
    );

    // A launcher without music_v1 while entries are ticked: a warning.
    app.request(
        Method::POST,
        "/api/devices/status",
        Some(&token),
        Some(json!({"lock_reason": "NONE", "kiosk_engaged": true})),
    )
    .await;
    let page = app
        .get_page(&format!("/devices/{device}"), &cookie)
        .await
        .text();
    assert!(page.contains("handle music yet"));
}

// ------------------------------------------------------------------------------------------------
// Storytel
// ------------------------------------------------------------------------------------------------

#[tokio::test]
async fn the_storytel_login_is_write_only_sealed_and_generational() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (device, token) = app.enrolled_device("phone").await;
    const PASSWORD: &str = "hemmelig-passord-123";
    const EMAIL: &str = "familien@example.org";

    // Nothing stored: 404 whatever the switch.
    let res = app
        .request(
            Method::GET,
            "/api/devices/music/storytel",
            Some(&token),
            None,
        )
        .await;
    assert_eq!(res.status, StatusCode::NOT_FOUND);

    let res = app
        .request_form(
            Method::POST,
            "/music/storytel",
            Some(&cookie),
            &[("email", EMAIL), ("password", PASSWORD)],
        )
        .await;
    assert_eq!(res.location(), Some("/music#storytel"));
    let (ciphertext, generation): (Vec<u8>, i64) =
        sqlx::query_as("SELECT ciphertext, generation FROM music_storytel")
            .fetch_one(&app.db)
            .await
            .unwrap();
    assert_eq!(generation, 1);
    assert!(
        !ciphertext
            .windows(PASSWORD.len())
            .any(|w| w == PASSWORD.as_bytes())
    );
    // Not in the database file, the page, the security log.
    sqlx::query("PRAGMA wal_checkpoint(TRUNCATE)")
        .execute(&app.db)
        .await
        .unwrap();
    let db_dir = app._dir.path();
    for entry in std::fs::read_dir(db_dir).unwrap() {
        let path = entry.unwrap().path();
        if path.is_file() {
            let bytes = std::fs::read(&path).unwrap();
            assert!(
                !bytes
                    .windows(PASSWORD.len())
                    .any(|w| w == PASSWORD.as_bytes()),
                "{} holds the password",
                path.display()
            );
        }
    }
    let page = app.get_page("/music", &cookie).await.text();
    assert!(
        !page.contains(PASSWORD) && !page.contains(EMAIL),
        "never shown back"
    );
    assert!(page.contains("Saved "));
    let log: Vec<String> = sqlx::query_scalar("SELECT COALESCE(detail, '') FROM security_events")
        .fetch_all(&app.db)
        .await
        .unwrap();
    assert!(
        log.iter()
            .all(|d| !d.contains(PASSWORD) && !d.contains(EMAIL))
    );
    assert_eq!(events(&app, "storytel_login_saved").await.len(), 1);

    // The switch off: 404, generation 0. On: the login, never cached.
    let res = app
        .request(
            Method::GET,
            "/api/devices/music/storytel",
            Some(&token),
            None,
        )
        .await;
    assert_eq!(res.status, StatusCode::NOT_FOUND);
    assert_eq!(
        music_policy(&app, &token).await["storytel_generation"],
        json!(0)
    );
    let mut rx = app.state.command_notify.subscribe();
    app.request_form(
        Method::POST,
        &format!("/devices/{device}/music"),
        Some(&cookie),
        &[("storytel", "on")],
    )
    .await;
    assert_eq!(nudged(&mut rx), vec![device]);
    assert_eq!(
        music_policy(&app, &token).await["storytel_generation"],
        json!(1)
    );
    let res = app
        .request(
            Method::GET,
            "/api/devices/music/storytel",
            Some(&token),
            None,
        )
        .await;
    assert_eq!(res.status, StatusCode::OK);
    assert_eq!(res.headers.get(header::CACHE_CONTROL).unwrap(), "no-store");
    assert_eq!(
        res.json(),
        json!({"generation": 1, "email": EMAIL, "password": PASSWORD})
    );
    assert_eq!(
        events(&app, "storytel_login_sent").await,
        vec![format!("device {device}: generation 1")]
    );

    // Saved again: a new generation, the phones with the switch are nudged.
    app.request_form(
        Method::POST,
        "/music/storytel",
        Some(&cookie),
        &[("email", EMAIL), ("password", "nytt-passord")],
    )
    .await;
    assert_eq!(nudged(&mut rx), vec![device]);
    assert_eq!(
        music_policy(&app, &token).await["storytel_generation"],
        json!(2)
    );

    // Bad input: refused, nothing changes.
    for fields in [
        vec![("email", "not-an-email"), ("password", "x")],
        vec![("email", EMAIL), ("password", "")],
    ] {
        let res = app
            .request_form(Method::POST, "/music/storytel", Some(&cookie), &fields)
            .await;
        assert_eq!(res.status, StatusCode::BAD_REQUEST);
    }
    assert_eq!(
        music_policy(&app, &token).await["storytel_generation"],
        json!(2)
    );

    // Cleared: 404 for the phone, generation 0 in its policy, one more in the row.
    let res = app
        .request_form(Method::POST, "/music/storytel/clear", Some(&cookie), &[])
        .await;
    assert_eq!(res.location(), Some("/music#storytel"));
    assert_eq!(
        music_policy(&app, &token).await["storytel_generation"],
        json!(0)
    );
    let generation: i64 = sqlx::query_scalar("SELECT generation FROM music_storytel")
        .fetch_one(&app.db)
        .await
        .unwrap();
    assert_eq!(generation, 3);
    let res = app
        .request(
            Method::GET,
            "/api/devices/music/storytel",
            Some(&token),
            None,
        )
        .await;
    assert_eq!(res.status, StatusCode::NOT_FOUND);
    assert_eq!(events(&app, "storytel_login_cleared").await.len(), 1);
}

#[tokio::test]
async fn storytel_without_a_usable_key_is_a_503_and_the_form_says_so() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (device, token) = app.enrolled_device("phone").await;
    sqlx::query("UPDATE device_policy SET music_storytel = 1 WHERE device_id = ?")
        .bind(device)
        .execute(&app.db)
        .await
        .unwrap();
    // Sealed with another key (a restored database): 503, and the page asks to enter it again.
    let other = crate::music_secret::MusicKey::from_bytes(&[1; 32]);
    let (ciphertext, nonce) = other.seal(br#"{"email":"a@b.c","password":"p"}"#);
    sqlx::query(
        "UPDATE music_storytel SET ciphertext = ?, nonce = ?, key_fingerprint = ?, \
         saved_at = datetime('now'), generation = 5",
    )
    .bind(&ciphertext)
    .bind(&nonce)
    .bind(&other.fingerprint)
    .execute(&app.db)
    .await
    .unwrap();
    let res = app
        .request(
            Method::GET,
            "/api/devices/music/storytel",
            Some(&token),
            None,
        )
        .await;
    assert_eq!(res.status, StatusCode::SERVICE_UNAVAILABLE);
    assert!(
        app.get_page("/music", &cookie)
            .await
            .text()
            .contains("Enter it again")
    );

    // No key at all: 503, the form is replaced by the explanation, saving is refused.
    let mut state = app.state.clone();
    state.music_key = None;
    let res = crate::handlers::music_api::storytel(
        axum::extract::State(state.clone()),
        axum::Extension(crate::security::AuthedDevice(
            sqlx::query_as::<_, crate::models::Device>("SELECT * FROM devices WHERE id = ?")
                .bind(device)
                .fetch_one(&app.db)
                .await
                .unwrap(),
        )),
    )
    .await;
    assert_eq!(res.status(), StatusCode::SERVICE_UNAVAILABLE);
    let page = super::read_response(
        crate::handlers::music::show(axum::extract::State(state.clone())).await,
    )
    .await
    .text();
    assert!(page.contains("Storytel is off"));
    assert!(!page.contains("id=\"storytel_password\""));
}

// ------------------------------------------------------------------------------------------------
// Pages, auth, the catalog
// ------------------------------------------------------------------------------------------------

#[tokio::test]
async fn the_music_pages_render_and_link_both_ways() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let entry = add_own(&app, &cookie, "Bilturen").await;
    let page = app.get_page("/music", &cookie).await;
    assert_eq!(page.status, StatusCode::OK);
    let text = page.text();
    for needle in [
        "/static/scroll-restore.js",
        "href=\"/apps\"",
        "id=\"categories\"",
        "id=\"storytel\"",
        &format!("id=\"entry-{entry}\""),
        "/static/music-icons/music_note.svg",
    ] {
        assert!(text.contains(needle), "{needle}");
    }
    assert!(
        app.get_page("/apps", &cookie)
            .await
            .text()
            .contains("href=\"/music\"")
    );
    let entry_page = app
        .get_page(&format!("/music/entries/{entry}"), &cookie)
        .await;
    assert_eq!(entry_page.status, StatusCode::OK);
    assert!(entry_page.text().contains("/static/scroll-restore.js"));
    assert_eq!(
        app.get_page("/music/entries/999", &cookie).await.status,
        StatusCode::NOT_FOUND
    );
    for icon in crate::music_icons::ICONS {
        assert!(
            std::path::Path::new(&format!("static/music-icons/{}.svg", icon.0)).exists(),
            "{}",
            icon.0
        );
    }
}

#[test]
fn the_music_icon_table_matches_its_json() {
    let table: Value =
        serde_json::from_str(&std::fs::read_to_string("testdata/music_icons.json").unwrap())
            .unwrap();
    let icons: Vec<(&str, &str)> = table["icons"]
        .as_object()
        .unwrap()
        .iter()
        .map(|(k, v)| (k.as_str(), v.as_str().unwrap()))
        .collect();
    let mut generated: Vec<(&str, &str)> = crate::music_icons::ICONS.to_vec();
    let mut sorted = icons.clone();
    sorted.sort_unstable();
    generated.sort_unstable();
    assert_eq!(generated, sorted);
    let colors = table["colors"].as_object().unwrap();
    assert_eq!(colors.len(), crate::music_icons::COLORS.len());
    for (key, hex) in crate::music_icons::COLORS {
        assert_eq!(colors[*key], json!(hex));
        // White on the tile >= 3:1 (WCAG relative luminance).
        let channel = |i: usize| {
            let c = u8::from_str_radix(&hex[1 + 2 * i..3 + 2 * i], 16).unwrap() as f64 / 255.0;
            if c <= 0.03928 {
                c / 12.92
            } else {
                ((c + 0.055) / 1.055).powf(2.4)
            }
        };
        let luminance = 0.2126 * channel(0) + 0.7152 * channel(1) + 0.0722 * channel(2);
        assert!(1.05 / (luminance + 0.05) >= 3.0, "{key}");
    }
    for (key, _) in crate::music_icons::ICONS {
        let svg = std::fs::read_to_string(format!("static/music-icons/{key}.svg")).unwrap();
        assert!(svg.contains("Converted from Material Symbols"), "{key}");
    }
}

#[tokio::test]
async fn music_routes_refuse_without_a_session_and_write_nothing() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (device, _) = app.enrolled_device("phone").await;
    let entry = add_own(&app, &cookie, "Bilturen").await;
    let before: (i64, i64, i64, i64) = sqlx::query_as(
        "SELECT (SELECT COUNT(*) FROM music_entries), (SELECT COUNT(*) FROM music_categories), \
         (SELECT COUNT(*) FROM device_music_entries), (SELECT generation FROM music_storytel)",
    )
    .fetch_one(&app.db)
    .await
    .unwrap();
    for (uri, fields) in [
        (
            "/music".to_string(),
            vec![("link", "https://radio.nrk.no/podkast/x")],
        ),
        ("/music/own".to_string(), vec![("name", "X")]),
        ("/music/catalog-row".to_string(), vec![]),
        (
            "/music/categories".to_string(),
            vec![("name", "X"), ("icon", "star"), ("color", "teal")],
        ),
        (
            "/music/categories/1".to_string(),
            vec![("name", "X"), ("icon", "star"), ("color", "teal")],
        ),
        (
            "/music/categories/2/move".to_string(),
            vec![("direction", "up")],
        ),
        ("/music/categories/2/delete".to_string(), vec![]),
        (
            "/music/storytel".to_string(),
            vec![("email", "a@b.c"), ("password", "p")],
        ),
        ("/music/storytel/clear".to_string(), vec![]),
        (
            format!("/music/entries/{entry}"),
            vec![("name", "X"), ("category", "1")],
        ),
        (
            format!("/music/entries/{entry}/move"),
            vec![("direction", "up")],
        ),
        (format!("/music/entries/{entry}/delete"), vec![]),
        (format!("/music/entries/{entry}/cover/remove"), vec![]),
        (format!("/music/entries/{entry}/files"), vec![]),
        (format!("/music/entries/{entry}/files/1/delete"), vec![]),
        (format!("/devices/{device}/music"), vec![("storytel", "on")]),
        (
            format!("/devices/{device}/music/entries/{entry}"),
            vec![("selected", "on")],
        ),
    ] {
        let res = app.request_form(Method::POST, &uri, None, &fields).await;
        assert!(res.status.is_redirection(), "{uri}: {}", res.status);
        assert_eq!(res.location(), Some("/login"), "{uri}");
    }
    for uri in ["/music", "/music-covers/abc"] {
        let res = app.request(Method::GET, uri, None, None).await;
        assert_eq!(res.location(), Some("/login"), "{uri}");
    }
    let after: (i64, i64, i64, i64) = sqlx::query_as(
        "SELECT (SELECT COUNT(*) FROM music_entries), (SELECT COUNT(*) FROM music_categories), \
         (SELECT COUNT(*) FROM device_music_entries), (SELECT generation FROM music_storytel)",
    )
    .fetch_one(&app.db)
    .await
    .unwrap();
    assert_eq!(before, after);
    let storytel_on: bool =
        sqlx::query_scalar("SELECT music_storytel FROM device_policy WHERE device_id = ?")
            .bind(device)
            .fetch_one(&app.db)
            .await
            .unwrap();
    assert!(!storytel_on);
    for uri in [
        "/api/devices/music/library",
        "/api/devices/music/covers/abc",
        "/api/devices/music/files/1",
        "/api/devices/music/storytel",
    ] {
        let res = app.request(Method::GET, uri, None, None).await;
        assert_eq!(res.status, StatusCode::UNAUTHORIZED, "{uri}");
    }
}

#[tokio::test]
async fn the_release_tag_prefix_is_edited_on_the_app_page() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let id: i64 = sqlx::query_scalar(
        "INSERT INTO tracked_apps (name, package_name, github_repo) \
         VALUES ('Launcher', 'me.vibb.launcher', 'palchrb/vibb-launcher') RETURNING id",
    )
    .fetch_one(&app.db)
    .await
    .unwrap();
    let res = app
        .request_form(
            Method::POST,
            &format!("/apps/tracked/{id}"),
            Some(&cookie),
            &[
                ("name", "Launcher"),
                ("github_repo", "palchrb/vibb-launcher"),
                ("release_tag_prefix", "bad prefix"),
            ],
        )
        .await;
    assert_eq!(res.status, StatusCode::BAD_REQUEST);
    let text = res.text();
    assert!(text.contains("release tag prefix may only hold"));
    assert!(text.contains("value=\"bad prefix\""));
    let res = app
        .request_form(
            Method::POST,
            &format!("/apps/tracked/{id}"),
            Some(&cookie),
            &[
                ("name", "Launcher"),
                ("github_repo", "palchrb/vibb-launcher"),
                ("release_tag_prefix", " launcher-v "),
            ],
        )
        .await;
    assert_eq!(res.location(), Some(format!("/apps/tracked/{id}").as_str()));
    let prefix: Option<String> =
        sqlx::query_scalar("SELECT release_tag_prefix FROM tracked_apps WHERE id = ?")
            .bind(id)
            .fetch_one(&app.db)
            .await
            .unwrap();
    assert_eq!(prefix.as_deref(), Some("launcher-v"));
    // And on the add form.
    let res = app
        .request_form(
            Method::POST,
            "/apps/tracked/new",
            Some(&cookie),
            &[
                ("name", "Music"),
                ("source_type", "github"),
                ("github_repo", "palchrb/vibb-launcher"),
                ("release_tag_prefix", "music-v"),
            ],
        )
        .await;
    assert!(res.status.is_redirection());
    let prefix: Option<String> =
        sqlx::query_scalar("SELECT release_tag_prefix FROM tracked_apps WHERE name = 'Music'")
            .fetch_one(&app.db)
            .await
            .unwrap();
    assert_eq!(prefix.as_deref(), Some("music-v"));
}
