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

// ------------------------------------------------------------------------------------------------
// Helpers
// ------------------------------------------------------------------------------------------------

/// One canned answer.
#[derive(Clone, Default)]
pub struct Canned {
    pub status: u16,
    pub body: Vec<u8>,
    pub etag: Option<String>,
    pub last_modified: Option<String>,
}

/// Every music source fetch in the tests (`AppState.music_fetch`, behind the real gate): canned
/// answers by URL (anything else is a network error), canned DNS (unknown hosts resolve to a
/// public address), a public-only request to a host that resolves only privately is refused like
/// the real resolver does, `If-None-Match` matching a canned ETag answers 304. Every URL asked for
/// is recorded, and how many requests were in flight at once.
#[derive(Default)]
pub struct CannedFetch {
    answers: Mutex<HashMap<String, Canned>>,
    failures: Mutex<HashMap<String, crate::music_net::FetchError>>,
    dns: Mutex<HashMap<String, Vec<std::net::IpAddr>>>,
    pub calls: Mutex<Vec<String>>,
    /// The validators each request carried: (url, If-None-Match).
    pub conditional: Mutex<Vec<(String, Option<String>)>>,
    delay_ms: std::sync::atomic::AtomicU64,
    in_flight: std::sync::atomic::AtomicUsize,
    pub most_in_flight: std::sync::atomic::AtomicUsize,
}

impl CannedFetch {
    pub fn answer(&self, url: &str, status: u16, body: impl Into<Vec<u8>>) {
        self.canned(
            url,
            Canned {
                status,
                body: body.into(),
                ..Default::default()
            },
        );
    }

    pub fn canned(&self, url: &str, answer: Canned) {
        self.failures.lock().unwrap().remove(url);
        self.answers.lock().unwrap().insert(url.to_string(), answer);
    }

    pub fn fail(&self, url: &str, err: crate::music_net::FetchError) {
        self.answers.lock().unwrap().remove(url);
        self.failures.lock().unwrap().insert(url.to_string(), err);
    }

    pub fn dns(&self, host: &str, ips: &[&str]) {
        self.dns.lock().unwrap().insert(
            host.to_string(),
            ips.iter().map(|ip| ip.parse().unwrap()).collect(),
        );
    }

    /// Every answer takes this long (the gate tests).
    pub fn delay(&self, ms: u64) {
        self.delay_ms.store(ms, std::sync::atomic::Ordering::SeqCst);
    }

    pub fn count(&self) -> usize {
        self.calls.lock().unwrap().len()
    }

    pub fn clear_calls(&self) {
        self.calls.lock().unwrap().clear();
        self.conditional.lock().unwrap().clear();
    }

    fn lookup(&self, host: &str) -> Vec<std::net::IpAddr> {
        if let Some(ip) = crate::music_net::literal_ip(host) {
            return vec![ip];
        }
        self.dns
            .lock()
            .unwrap()
            .get(host)
            .cloned()
            .unwrap_or_else(|| vec!["93.184.216.34".parse().unwrap()])
    }
}

impl crate::music_net::Source for CannedFetch {
    fn get<'a>(
        &'a self,
        request: crate::music_net::SourceRequest,
    ) -> crate::music_net::BoxFut<
        'a,
        Result<crate::music_net::SourceResponse, crate::music_net::FetchError>,
    > {
        use std::sync::atomic::Ordering;
        Box::pin(async move {
            let now = self.in_flight.fetch_add(1, Ordering::SeqCst) + 1;
            self.most_in_flight.fetch_max(now, Ordering::SeqCst);
            let delay = self.delay_ms.load(Ordering::SeqCst);
            if delay > 0 {
                tokio::time::sleep(std::time::Duration::from_millis(delay)).await;
            }
            self.in_flight.fetch_sub(1, Ordering::SeqCst);
            self.calls.lock().unwrap().push(request.url.clone());
            self.conditional
                .lock()
                .unwrap()
                .push((request.url.clone(), request.etag.clone()));
            let host = crate::music_net::host_of(&request.url).unwrap_or_default();
            if request.reach == crate::music_net::Reach::Public
                && self
                    .lookup(&host)
                    .iter()
                    .all(|ip| crate::music_net::is_private(*ip))
            {
                return Err(crate::music_net::FetchError::Refused(format!(
                    "{host} resolves only to private addresses"
                )));
            }
            if let Some(err) = self.failures.lock().unwrap().get(&request.url) {
                return Err(err.clone());
            }
            let Some(answer) = self.answers.lock().unwrap().get(&request.url).cloned() else {
                return Err(crate::music_net::FetchError::Network(
                    "no route to host".to_string(),
                ));
            };
            if answer.etag.is_some() && request.etag == answer.etag {
                return Ok(crate::music_net::SourceResponse {
                    status: 304,
                    etag: answer.etag,
                    ..Default::default()
                });
            }
            let limit = request.kind.limit();
            Ok(crate::music_net::SourceResponse {
                status: answer.status,
                truncated: answer.body.len() > limit,
                body: answer.body[..answer.body.len().min(limit)].to_vec(),
                etag: answer.etag,
                last_modified: answer.last_modified,
            })
        })
    }

    fn resolve<'a>(
        &'a self,
        host: &'a str,
    ) -> crate::music_net::BoxFut<'a, Result<Vec<std::net::IpAddr>, crate::music_net::FetchError>>
    {
        Box::pin(async move { Ok(self.lookup(host)) })
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

pub(super) async fn tick(
    app: &TestApp,
    cookie: &str,
    device: i64,
    entry: i64,
    on: bool,
) -> TestResponse {
    let fields: &[(&str, &str)] = if on { &[("selected", "on")] } else { &[] };
    app.request_form(
        Method::POST,
        &format!("/devices/{device}/music/entries/{entry}"),
        Some(cookie),
        fields,
    )
    .await
}

pub(super) async fn music_policy(app: &TestApp, token: &str) -> Value {
    let res = app
        .request(Method::GET, "/api/devices/policy", Some(token), None)
        .await;
    assert_eq!(res.status, StatusCode::OK);
    res.json()["music"].clone()
}

/// A GET with a bearer token and extra headers.
pub(super) async fn device_get(
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

pub(super) fn nudged(rx: &mut tokio::sync::broadcast::Receiver<i64>) -> Vec<i64> {
    let mut ids = Vec::new();
    while let Ok(id) = rx.try_recv() {
        ids.push(id);
    }
    ids
}

pub(super) async fn events(app: &TestApp, kind: &str) -> Vec<String> {
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
    let (name, key, target, cache, resume, category) = row;
    assert_eq!(
        (name, target, cache, resume, category),
        ("Bilturen".into(), None, -1, false, "Musikk".into())
    );
    // "own-" and 12 random hex digits, unique over time (qa-21-step1-code #7) - not the id, which a
    // restored database hands out again.
    assert_eq!(key.len(), 16, "{key}");
    assert!(key.starts_with("own-") && key[4..].bytes().all(|b| b.is_ascii_hexdigit()));
    assert_ne!(key, format!("own-{id}"));
    let second = add_own(&app, &cookie, "Bilturen 2").await;
    let second_key: String = sqlx::query_scalar("SELECT key FROM music_entries WHERE id = ?")
        .bind(second)
        .fetch_one(&app.db)
        .await
        .unwrap();
    assert_ne!(second_key, key);
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
        Some(format!("/devices/{device}#music-entry-{id}").as_str()),
        "back to that row (qa-21-step1-code #8)"
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
        Some(format!("/devices/{device}#music-settings").as_str())
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
         (3, 'Bilturen', 1, 'own', NULL, 'own-5c0ffee1d0e5', 'auto', -1, 0, NULL, 30), \
         (4, 'Not on this phone', 2, 'own', NULL, 'own-0b5e55ed4a11', 'auto', -1, 0, NULL, 40)",
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
    // The feed is listed (design 21b: `items` names its listing; its cover is the feed's own),
    // the podcast not yet (`items: null`).
    sqlx::query(
        "INSERT INTO music_listings (entry_id, version, cover_hash, keep_end, listed_at) \
         VALUES (2, '0123456789abcdef', ?, NULL, '2026-10-09 12:00:00'), \
                (1, NULL, NULL, NULL, NULL)",
    )
    .bind("c".repeat(64))
    .execute(&app.db)
    .await
    .unwrap();
}

/// Compact JSON indented by two spaces, keys in the order they were sent (serde_json's `Value`
/// would sort them).
pub(super) fn pretty_json(compact: &str) -> String {
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
            "cache", "category", "cover", "id", "items", "key", "name", "order", "resume", "sort",
            "source", "target"
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
    // The feed's own cover stands in for a parent's (design 21b §2.6).
    assert_eq!(
        served["covers"],
        json!(["a".repeat(64), "b".repeat(64), "c".repeat(64)])
    );
    assert_eq!(served["entries"][1]["items"], json!("0123456789abcdef"));
    assert_eq!(served["entries"][0]["items"], Value::Null, "never listed");
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
        Some(
            format!("/devices/{device}?music_notice=too_big&music_entry={big}#music-entry-{big}")
                .as_str()
        )
    );
    let ticked: Vec<i64> =
        sqlx::query_scalar("SELECT entry_id FROM device_music_entries WHERE device_id = ?")
            .bind(device)
            .fetch_all(&app.db)
            .await
            .unwrap();
    assert_eq!(ticked, vec![small]);
    let page = app
        .get_page(
            &format!("/devices/{device}?music_notice=too_big&music_entry={big}"),
            &cookie,
        )
        .await
        .text();
    // By the refused row, not at the card's top.
    let row = page
        .split(&format!("id=\"music-entry-{big}\""))
        .nth(1)
        .and_then(|rest| rest.split("</form>").next())
        .unwrap();
    assert!(row.contains("bigger than 3 MB"), "{row}");
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
        vec![format!("entry {entry}: 2 file(s), 0 restored")]
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
    let b_wav: i64 = sqlx::query_scalar(
        "SELECT id FROM music_files WHERE entry_id = ? AND original_name = 'b.wav'",
    )
    .bind(entry)
    .fetch_one(&app.db)
    .await
    .unwrap();
    assert_eq!(
        res.location(),
        Some(format!("/music/entries/{entry}#file-{b_wav}").as_str()),
        "back to the next file, not the list's top (qa-21-step1-code #8)"
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
    let page = super::read_response(crate::handlers::music::render_plain(&state).await)
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
        ("/music/import".to_string(), vec![]),
        ("/music/orphans/delete".to_string(), vec![]),
        (
            "/music/import/confirm".to_string(),
            vec![
                (
                    "doc",
                    r#"{"sections":[{"name":"P","entries":[{"name":"X","target":"https://example.org/x.rss"}]}]}"#,
                ),
                ("row", "0.0"),
            ],
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

// ------------------------------------------------------------------------------------------------
// Import from Vibb (design 21a)
// ------------------------------------------------------------------------------------------------

async fn import_preview(app: &TestApp, cookie: &str, bytes: &[u8]) -> TestResponse {
    const BOUNDARY: &str = "handy-import-boundary";
    let mut body = format!(
        "--{BOUNDARY}\r\nContent-Disposition: form-data; name=\"library\"; filename=\"library.json\"\r\n\
         Content-Type: application/json\r\n\r\n"
    )
    .into_bytes();
    body.extend_from_slice(bytes);
    body.extend_from_slice(format!("\r\n--{BOUNDARY}--\r\n").as_bytes());
    let request = Request::builder()
        .method(Method::POST)
        .uri("/music/import")
        .header(header::COOKIE, cookie)
        .header(
            header::CONTENT_TYPE,
            format!("multipart/form-data; boundary={BOUNDARY}"),
        )
        .body(Body::from(body))
        .unwrap();
    app.send(request).await
}

/// The preview's hidden document, as the browser sends it back.
fn preview_doc(page: &str) -> String {
    let start = page
        .find("name=\"doc\" value=\"")
        .expect("no preview document")
        + 18;
    let end = start + page[start..].find('"').unwrap();
    page[start..end]
        .replace("&#34;", "\"")
        .replace("&#39;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&amp;", "&")
}

/// Every tick value the preview offers ("s.r").
fn preview_rows(page: &str) -> Vec<String> {
    page.split("name=\"row\" value=\"")
        .skip(1)
        .map(|rest| rest[..rest.find('"').unwrap()].to_string())
        .collect()
}

async fn import_confirm(app: &TestApp, cookie: &str, fields: &[(&str, &str)]) -> TestResponse {
    app.request_form(Method::POST, "/music/import/confirm", Some(cookie), fields)
        .await
}

/// A Pi's /etc/vibb/library.json: every source and every skip reason.
fn vibb_library() -> Value {
    json!({"version": 1, "sections": [
        {"id": "podkast", "name": " podkast ", "entries": [
            {"id": "a1", "name": "Abels tårn", "target": "https://radio.nrk.no/podkast/abels_taarn", "order": "auto", "cache": 5, "resume": true},
            {"id": "a2", "name": "Ukas nyheter", "target": "https://example.org/feed.xml", "order": "newest_first", "cache": 3, "resume": false},
            {"id": "a3", "name": "Spilleliste", "target": "spotify:playlist:37i9dQ", "order": "auto", "cache": 0, "resume": true},
            {"id": "a4", "name": "Lydbok", "target": "storytel:series:123", "order": "auto", "cache": -1, "resume": true},
            {"id": "a5", "name": "Egne sanger", "target": "/srv/vibb/music/egne", "order": "auto", "cache": 0, "resume": true},
            {"id": "a6", "name": "P1 direkte", "target": "https://radio.nrk.no/direkte/p1", "order": "auto", "cache": 0, "resume": true}
        ]},
        {"id": "eventyr", "name": "Eventyr og sånt", "entries": [
            {"id": "b1", "name": "Radioteatret", "target": "https://radio.nrk.no/serie/radioteatret", "order": "oldest_first", "cache": 42, "resume": true},
            {"id": "b2", "name": "Abels tårn (igjen)", "target": "https://radio.nrk.no/podkast/abels_taarn/l_99", "order": "auto", "cache": 5, "resume": true},
            {"id": "b3", "name": "", "target": "https://example.org/noname.rss", "order": "auto", "cache": 5, "resume": true}
        ]},
        {"id": "kari", "name": "Karis lister", "spotify_user": "kari", "entries": [
            {"id": "c1", "name": "Uke 41", "target": "https://example.org/uke41.rss", "order": "auto", "cache": 5, "resume": true}
        ]}
    ]})
}

#[tokio::test]
async fn a_vibb_library_is_previewed_then_imported_without_a_fetch() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (anna, anna_token) = app.enrolled_device("Anna").await;
    let (emil, _) = app.enrolled_device("Emil").await;

    let res = import_preview(&app, &cookie, vibb_library().to_string().as_bytes()).await;
    assert_eq!(res.status, StatusCode::OK, "{}", res.text());
    let page = res.text();
    assert!(page.contains("id=\"import\" open"), "the card is open");
    assert!(page.contains("action=\"/music/import/confirm#import\""));
    for (text, why) in [
        ("Abels tårn", "nrk"),
        (
            "NRK podcast · natural order · newest 5 kept offline · continues where it stopped",
            "settings",
        ),
        (
            "Podcast (RSS) · newest first · newest 3 kept offline · starts from the beginning",
            "rss settings",
        ),
        (
            "NRK series · oldest first · newest 42 kept offline",
            "a Pi value kept",
        ),
        ("Spotify comes in a later version", "spotify"),
        ("Storytel books come in a later version", "storytel"),
        ("upload the files as an own-files entry", "folder"),
        ("neither a podcast", "parse_link"),
        ("it is in the file twice", "repeat within the file"),
        ("it has no name", "no name"),
        ("it comes from a Spotify profile", "spotify_user"),
        ("New: Eventyr og sånt", "a new category"),
    ] {
        assert!(page.contains(text), "{why}: {text}");
    }
    // " podkast " matches the existing Podkast (trimmed, case-insensitive).
    assert!(page.contains("<option value=\"4\" selected>Podkast</option>"));
    assert!(page.contains("<option value=\"new\" selected>New: Eventyr og sånt</option>"));
    assert_eq!(preview_rows(&page), vec!["0.0", "0.1", "1.0"]);
    assert_eq!(
        sqlx::query_scalar::<_, i64>("SELECT COUNT(*) FROM music_entries")
            .fetch_one(&app.db)
            .await
            .unwrap(),
        0,
        "the preview stores nothing"
    );

    let doc = preview_doc(&page);
    let mut rx = app.state.command_notify.subscribe();
    let anna_id = anna.to_string();
    let emil_id = emil.to_string();
    let res = import_confirm(
        &app,
        &cookie,
        &[
            ("doc", &doc),
            ("category_0", "4"),
            ("category_1", "new"),
            ("row", "0.0"),
            ("row", "0.1"),
            ("row", "1.0"),
            ("phone", &anna_id),
            ("phone", &emil_id),
        ],
    )
    .await;
    assert_eq!(res.location(), Some("/music#import"), "{}", res.text());
    let mut nudges = nudged(&mut rx);
    nudges.sort_unstable();
    assert_eq!(nudges, vec![anna, emil], "one nudge per phone that changed");
    assert!(
        app.fetch.calls.lock().unwrap().is_empty(),
        "no outgoing request"
    );

    type Imported = (
        String,
        String,
        Option<String>,
        String,
        String,
        i64,
        bool,
        String,
    );
    let rows: Vec<Imported> = sqlx::query_as(
        "SELECT e.name, e.source, e.target, e.key, e.play_order, e.cache, e.resume, c.name \
         FROM music_entries e JOIN music_categories c ON c.id = e.category_id ORDER BY e.sort",
    )
    .fetch_all(&app.db)
    .await
    .unwrap();
    assert_eq!(
        rows,
        vec![
            (
                "Abels tårn".into(),
                "nrk".into(),
                Some("https://radio.nrk.no/podkast/abels_taarn".into()),
                "abels_taarn".into(),
                "auto".into(),
                5,
                true,
                "Podkast".into()
            ),
            (
                "Ukas nyheter".into(),
                "rss".into(),
                Some("https://example.org/feed.xml".into()),
                crate::music::state_key("https://example.org/feed.xml"),
                "newest_first".into(),
                3,
                false,
                "Podkast".into()
            ),
            (
                "Radioteatret".into(),
                "nrk".into(),
                Some("https://radio.nrk.no/serie/radioteatret".into()),
                crate::music::state_key("https://radio.nrk.no/serie/radioteatret"),
                "oldest_first".into(),
                42,
                true,
                "Eventyr og sånt".into()
            ),
        ]
    );
    // The new category: after the others, icon and colour of the podcast category (its first
    // row's source).
    let new: (String, String, i64) = sqlx::query_as(
        "SELECT icon, color, sort FROM music_categories WHERE name = 'Eventyr og sånt'",
    )
    .fetch_one(&app.db)
    .await
    .unwrap();
    assert_eq!(new, ("mic".into(), "teal".into(), 50));
    let ticks: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM device_music_entries")
        .fetch_one(&app.db)
        .await
        .unwrap();
    assert_eq!(ticks, 6, "three entries on two phones");
    assert_eq!(
        music_policy(&app, &anna_token).await["library_version"]
            .as_str()
            .map(str::len),
        Some(16)
    );
    assert_eq!(
        events(&app, "music_library_imported").await,
        vec!["3 entries, 1 categories, 7 skipped".to_string()]
    );

    // The result, once, at #import.
    let page = app.get_page("/music", &cookie).await.text();
    assert!(page.contains("id=\"import\" open"));
    assert!(
        page.contains("Imported 3 entries and 1 new category, ticked on Anna, Emil. 7 skipped:"),
        "{page}"
    );
    assert!(page.contains("podkast / Spilleliste: Spotify comes in a later version"));
    assert!(page.contains("Eventyr og sånt / Abels tårn (igjen): it is in the file twice"));
    let again = app.get_page("/music", &cookie).await.text();
    assert!(!again.contains("Imported 3 entries"), "shown once");
    assert!(!again.contains("id=\"import\" open"));

    // A Pi value outside the page's choices stays, and survives a save of the entry.
    let radioteatret: i64 =
        sqlx::query_scalar("SELECT id FROM music_entries WHERE name = 'Radioteatret'")
            .fetch_one(&app.db)
            .await
            .unwrap();
    let entry_page = app
        .get_page(&format!("/music/entries/{radioteatret}"), &cookie)
        .await
        .text();
    assert!(entry_page.contains("<option value=\"42\" selected>42 (from Vibb)</option>"));
    let category =
        sqlx::query_scalar::<_, i64>("SELECT category_id FROM music_entries WHERE id = ?")
            .bind(radioteatret)
            .fetch_one(&app.db)
            .await
            .unwrap()
            .to_string();
    let res = app
        .request_form(
            Method::POST,
            &format!("/music/entries/{radioteatret}"),
            Some(&cookie),
            &[
                ("name", "Radioteatret"),
                ("category", &category),
                ("order", "oldest_first"),
                ("cache", "42"),
                ("resume", "on"),
            ],
        )
        .await;
    assert!(res.status.is_redirection(), "{}", res.text());
}

#[tokio::test]
async fn the_pis_get_library_answer_imports_too_and_a_reimport_adds_nothing() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    // GET /library: the same shape plus image/new fields.
    let answer = json!({"version": 1, "sections": [{"id": "musikk", "name": "MUSIKK", "image": "/art/m.png", "entries": [
        {"id": "x1", "name": "Ukas nyheter", "target": "https://example.org/feed.xml", "order": "auto", "cache": 5, "resume": true, "image": "/art/x.jpg", "new": true},
        {"id": "x2", "name": "Godnatt", "target": "https://example.org/godnatt.rss", "order": "bogus", "cache": "lots", "resume": null, "new": false}
    ]}]});
    let page = import_preview(&app, &cookie, answer.to_string().as_bytes())
        .await
        .text();
    assert!(
        page.contains("<option value=\"1\" selected>Musikk</option>"),
        "matched by name"
    );
    let doc = preview_doc(&page);
    let res = import_confirm(
        &app,
        &cookie,
        &[
            ("doc", &doc),
            ("category_0", "1"),
            ("row", "0.0"),
            ("row", "0.1"),
        ],
    )
    .await;
    assert_eq!(res.location(), Some("/music#import"));
    let rows: Vec<(String, String, i64, bool, i64)> = sqlx::query_as(
        "SELECT name, play_order, cache, resume, category_id FROM music_entries ORDER BY sort",
    )
    .fetch_all(&app.db)
    .await
    .unwrap();
    assert_eq!(
        rows,
        vec![
            ("Ukas nyheter".into(), "auto".into(), 5, true, 1),
            ("Godnatt".into(), "auto".into(), 5, true, 1),
        ],
        "bad settings fall back to the defaults"
    );
    let page = app.get_page("/music", &cookie).await.text();
    assert!(page.contains("on no phone yet"), "no phone ticked");

    // The same file again: nothing to add, and it says so - in the preview and the result.
    let page = import_preview(&app, &cookie, answer.to_string().as_bytes())
        .await
        .text();
    assert!(preview_rows(&page).is_empty());
    assert!(page.contains("already in the library"));
    assert!(page.contains("Nothing here can be added"));
    let doc = preview_doc(&page);
    let res = import_confirm(&app, &cookie, &[("doc", &doc)]).await;
    assert_eq!(res.location(), Some("/music#import"));
    let page = app.get_page("/music", &cookie).await.text();
    assert!(page.contains("Nothing new to import. 2 skipped:"), "{page}");
    assert!(page.contains("MUSIKK / Godnatt: already in the library"));
    let count: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM music_entries")
        .fetch_one(&app.db)
        .await
        .unwrap();
    assert_eq!(count, 2);
    assert_eq!(events(&app, "music_library_imported").await.len(), 2);
}

#[tokio::test]
async fn an_import_stops_at_200_entries_and_skips_whats_in_the_library() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    sqlx::query(
        "WITH RECURSIVE n(i) AS (SELECT 1 UNION ALL SELECT i + 1 FROM n WHERE i < 198) \
         INSERT INTO music_entries (name, category_id, source, key, sort) \
         SELECT 'E' || i, 1, 'own', 'own-x' || i, i * 10 FROM n",
    )
    .execute(&app.db)
    .await
    .unwrap();
    // One already here by target, one by key (another URL of the same podcast slug).
    sqlx::query(
        "INSERT INTO music_entries (name, category_id, source, target, key, sort) VALUES \
         ('Old', 4, 'rss', 'https://example.org/old.rss', ?, 5000)",
    )
    .bind(crate::music::state_key("https://example.org/old.rss"))
    .execute(&app.db)
    .await
    .unwrap();
    let file = json!({"sections": [{"name": "Podkast", "entries": [
        {"name": "Old again", "target": "https://example.org/old.rss"},
        {"name": "One", "target": "https://example.org/1.rss"},
        {"name": "Two", "target": "https://example.org/2.rss"}
    ]}]});
    let page = import_preview(&app, &cookie, file.to_string().as_bytes())
        .await
        .text();
    assert!(page.contains("already in the library"));
    assert!(page.contains("the library holds at most 200 entries"));
    assert_eq!(preview_rows(&page), vec!["0.1"]);
    let doc = preview_doc(&page);
    // A hand-made form ticking the third row too: re-checked, still the limit.
    let res = import_confirm(
        &app,
        &cookie,
        &[("doc", &doc), ("row", "0.1"), ("row", "0.2")],
    )
    .await;
    assert_eq!(res.location(), Some("/music#import"));
    let count: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM music_entries")
        .fetch_one(&app.db)
        .await
        .unwrap();
    assert_eq!(count, 200);
    let names: Vec<String> =
        sqlx::query_scalar("SELECT name FROM music_entries WHERE name IN ('One', 'Two')")
            .fetch_all(&app.db)
            .await
            .unwrap();
    assert_eq!(names, vec!["One"], "rows are taken in file order");
    let page = app.get_page("/music", &cookie).await.text();
    assert!(page.contains("Podkast / Two: the library holds at most 200 entries"));
}

#[tokio::test]
async fn an_import_ticks_a_phone_only_as_far_as_its_library_allows() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (full, _) = app.enrolled_device("Full").await;
    let (roomy, _) = app.enrolled_device("Roomy").await;
    let entry = add_own(&app, &cookie, "Nesten full").await;
    tick(&app, &cookie, full, entry, true).await;
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
    let size = crate::music::device_library(&app.db, full)
        .await
        .unwrap()
        .unwrap()
        .json
        .len();
    sqlx::query("UPDATE music_files SET title = ? WHERE original_name = 'pad.mp3'")
        .bind("x".repeat(crate::music::MAX_LIBRARY_BYTES - size - 50))
        .execute(&app.db)
        .await
        .unwrap();
    let file = json!({"sections": [{"name": "Podkast", "entries": [
        {"name": "Ukas nyheter", "target": "https://example.org/feed.xml"}
    ]}]});
    let page = import_preview(&app, &cookie, file.to_string().as_bytes())
        .await
        .text();
    let doc = preview_doc(&page);
    let (full_id, roomy_id) = (full.to_string(), roomy.to_string());
    let res = import_confirm(
        &app,
        &cookie,
        &[
            ("doc", &doc),
            ("row", "0.0"),
            ("phone", &full_id),
            ("phone", &roomy_id),
        ],
    )
    .await;
    assert_eq!(res.location(), Some("/music#import"));
    let ticked: Vec<i64> = sqlx::query_scalar(
        "SELECT d.device_id FROM device_music_entries d JOIN music_entries e ON e.id = d.entry_id \
         WHERE e.name = 'Ukas nyheter'",
    )
    .fetch_all(&app.db)
    .await
    .unwrap();
    assert_eq!(
        ticked,
        vec![roomy],
        "in the library, unticked on the full phone"
    );
    let page = app.get_page("/music", &cookie).await.text();
    assert!(page.contains("ticked on Roomy."));
    assert!(page.contains("Not ticked on Full: &#34;Ukas nyheter&#34; - that phone"));
}

#[tokio::test]
async fn the_confirm_trusts_nothing_in_the_form() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (phone, _) = app.enrolled_device("phone").await;
    let file = json!({"sections": [{"name": "Podkast", "entries": [
        {"name": "Ukas nyheter", "target": "https://example.org/feed.xml"}
    ]}]});
    let page = import_preview(&app, &cookie, file.to_string().as_bytes())
        .await
        .text();
    let doc = preview_doc(&page);
    let count = || async {
        sqlx::query_scalar::<_, i64>(
            "SELECT (SELECT COUNT(*) FROM music_entries) + (SELECT COUNT(*) FROM music_categories)",
        )
        .fetch_one(&app.db)
        .await
        .unwrap()
    };
    let before = count().await;

    // An unknown category or phone: 400, the preview again with the reason, nothing stored.
    for (fields, message) in [
        (
            vec![("category_0", "999"), ("row", "0.0")],
            "category doesn",
        ),
        (vec![("row", "0.0"), ("phone", "999")], "phone doesn"),
        (vec![("row", "zero")], "filled in as expected"),
    ] {
        let mut all = vec![("doc", doc.as_str())];
        all.extend(fields);
        let res = import_confirm(&app, &cookie, &all).await;
        assert_eq!(res.status, StatusCode::BAD_REQUEST, "{message}");
        let text = res.text();
        assert!(text.contains(message), "{message}: {text}");
        assert!(text.contains("name=\"doc\""), "the preview is back");
        assert_eq!(count().await, before, "{message}");
    }
    // No or a broken document.
    for doc in [
        "",
        "{not json",
        &"x".repeat(crate::music_import::MAX_IMPORT_BYTES + 1),
    ] {
        let res = import_confirm(&app, &cookie, &[("doc", doc), ("row", "0.0")]).await;
        assert_eq!(res.status, StatusCode::BAD_REQUEST);
        assert!(res.text().contains("preview it again"));
    }
    let res = import_confirm(&app, &cookie, &[("row", "0.0")]).await;
    assert_eq!(res.status, StatusCode::BAD_REQUEST);

    // A document edited to carry a Spotify row, a folder and 201 entries: only what a fresh upload
    // would allow gets in.
    let mut entries = vec![
        json!({"name": "Spill", "target": "spotify:playlist:1"}),
        json!({"name": "Mappe", "target": "/srv/x"}),
    ];
    entries.extend((0..201).map(
        |i| json!({"name": format!("F{i}"), "target": format!("https://example.org/{i}.rss")}),
    ));
    let tampered = json!({"sections": [{"name": "Podkast", "entries": entries}]}).to_string();
    let rows: Vec<String> = (0..203).map(|i| format!("0.{i}")).collect();
    let mut fields: Vec<(&str, &str)> = vec![("doc", tampered.as_str())];
    fields.extend(rows.iter().map(|r| ("row", r.as_str())));
    let phone_id = phone.to_string();
    fields.push(("phone", &phone_id));
    let res = import_confirm(&app, &cookie, &fields).await;
    assert_eq!(res.location(), Some("/music#import"));
    let names: Vec<String> = sqlx::query_scalar("SELECT name FROM music_entries ORDER BY sort")
        .fetch_all(&app.db)
        .await
        .unwrap();
    assert_eq!(names.len(), crate::music::MAX_ENTRIES as usize);
    assert_eq!(names.first().map(String::as_str), Some("F0"));
    assert!(
        !names
            .iter()
            .any(|n| n == "Spill" || n == "Mappe" || n == "F200")
    );
    let page = app.get_page("/music", &cookie).await.text();
    assert!(page.contains("Podkast / Spill: Spotify comes in a later version"));
    assert!(page.contains("Podkast / F200: the library holds at most 200 entries"));
    assert!(app.fetch.calls.lock().unwrap().is_empty());
}

#[tokio::test]
async fn files_that_arent_a_vibb_library_are_refused_at_import() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    for (bytes, message) in [
        (b"<html>not json</html>".to_vec(), "isn&#39;t JSON"),
        (br#"{"entries": []}"#.to_vec(), "no &#34;sections&#34; list"),
        (
            vec![b' '; crate::music_import::MAX_IMPORT_BYTES + 1],
            "over 1 MB",
        ),
        (Vec::new(), "Choose the library file first"),
    ] {
        let res = import_preview(&app, &cookie, &bytes).await;
        assert_eq!(res.status, StatusCode::BAD_REQUEST, "{message}");
        let text = res.text();
        assert!(text.contains(message), "{message}: {text}");
        assert!(text.contains("id=\"import\" open"));
        let input = text
            .split("id=\"import_file\"")
            .nth(1)
            .and_then(|rest| rest.split('>').next())
            .unwrap();
        assert!(input.contains("autofocus"), "the file field is focused");
    }
    // Bigger than the route takes at all.
    let res = import_preview(
        &app,
        &cookie,
        &vec![b' '; 3 * crate::music_import::MAX_IMPORT_BYTES],
    )
    .await;
    assert_eq!(res.status, StatusCode::BAD_REQUEST);
    let count: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM music_entries")
        .fetch_one(&app.db)
        .await
        .unwrap();
    assert_eq!(count, 0);
    assert!(events(&app, "music_library_imported").await.is_empty());
}

// ------------------------------------------------------------------------------------------------
// Fixes after qa-21-step1-code.md
// ------------------------------------------------------------------------------------------------

fn builds() -> usize {
    crate::music::LIBRARY_BUILDS.with(|b| b.get())
}

/// #2: uploading a missing file again puts it back in place (same id and path, so the phones'
/// copies stay valid); the same content twice is refused.
#[tokio::test]
async fn reuploading_a_missing_file_restores_it_and_a_double_is_refused() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (device, token) = app.enrolled_device("phone").await;
    let entry = add_own(&app, &cookie, "Bilturen").await;
    tick(&app, &cookie, device, entry, true).await;
    let song = tagged_wav("Hjulene", "Koret", 1, Some(png(64, 64)));
    upload(&app, &cookie, entry, &[("a.wav", song.clone())]).await;
    let (id, path, art): (i64, String, String) =
        sqlx::query_as("SELECT id, path, art_hash FROM music_files")
            .fetch_one(&app.db)
            .await
            .unwrap();
    // A restore without the audio (and without the cover).
    let full = app.state.music_files_dir.join(&path);
    std::fs::remove_file(&full).unwrap();
    std::fs::remove_file(app.state.music_cover_dir.join(format!("{art}.jpg"))).unwrap();
    sqlx::query("UPDATE music_files SET art_hash = NULL")
        .execute(&app.db)
        .await
        .unwrap();
    crate::music::flag_missing_files(&app.db, &app.state.music_files_dir).await;

    let res = upload(&app, &cookie, entry, &[("other-name.wav", song.clone())]).await;
    assert_eq!(res.status, StatusCode::SEE_OTHER, "{}", res.text());
    let rows: Vec<(i64, String, bool, Option<String>)> =
        sqlx::query_as("SELECT id, path, missing, art_hash FROM music_files")
            .fetch_all(&app.db)
            .await
            .unwrap();
    assert_eq!(rows.len(), 1, "no second track");
    assert_eq!((rows[0].0, &rows[0].1, rows[0].2), (id, &path, false));
    assert_eq!(
        rows[0].3.as_deref(),
        Some(art.as_str()),
        "the art is made again"
    );
    assert_eq!(std::fs::read(&full).unwrap(), song);
    let res = device_get(&app, &format!("/api/devices/music/files/{id}"), &token, &[]).await;
    assert_eq!(res.status, StatusCode::OK);
    assert_eq!(
        events(&app, "music_files_added").await.last().unwrap(),
        &format!("entry {entry}: 0 file(s), 1 restored")
    );

    // The same file again, twice in one upload too: refused, nothing left behind.
    let res = upload(
        &app,
        &cookie,
        entry,
        &[("x.wav", song.clone()), ("y.wav", song)],
    )
    .await;
    assert_eq!(res.status, StatusCode::BAD_REQUEST);
    let text = res.text();
    assert!(text.contains("x.wav: already in this entry"), "{text}");
    assert!(text.contains("y.wav: already in this entry"), "{text}");
    let count: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM music_files")
        .fetch_one(&app.db)
        .await
        .unwrap();
    assert_eq!(count, 1);
    assert_eq!(
        std::fs::read_dir(app.state.music_files_dir.join(entry.to_string()))
            .unwrap()
            .count(),
        1
    );
}

/// #6: the 300-file limit is the INSERT's own condition.
#[tokio::test]
async fn an_entry_holds_at_most_300_files() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let entry = add_own(&app, &cookie, "Full").await;
    sqlx::query(
        "WITH RECURSIVE n(i) AS (SELECT 1 UNION ALL SELECT i + 1 FROM n WHERE i < 300) \
         INSERT INTO music_files (entry_id, path, original_name, size, sha256) \
         SELECT ?, ? || '/f' || i || '.mp3', 'f.mp3', 1, printf('%064d', i) FROM n",
    )
    .bind(entry)
    .bind(entry.to_string())
    .execute(&app.db)
    .await
    .unwrap();
    let res = upload(&app, &cookie, entry, &[("one-more.wav", wav(100))]).await;
    assert_eq!(res.status, StatusCode::BAD_REQUEST);
    assert!(res.text().contains("an entry holds at most 300 files"));
    let count: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM music_files")
        .fetch_one(&app.db)
        .await
        .unwrap();
    assert_eq!(count, 300);
    let on_disk = std::fs::read_dir(app.state.music_files_dir.join(entry.to_string()))
        .map(|d| d.count())
        .unwrap_or(0);
    assert_eq!(on_disk, 0, "the refused file went again");
}

/// #3: a phone's library is built once and reused until something it is built from changes; a
/// cover check builds nothing.
#[tokio::test]
async fn the_library_is_built_once_until_something_changes() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (device, token) = app.enrolled_device("phone").await;
    let entry = add_nrk(&app, &cookie, "abels_taarn", "Abels tårn").await;
    tick(&app, &cookie, device, entry, true).await;
    upload_cover(&app, &cookie, entry, &png(200, 200)).await;
    let hash: String = sqlx::query_scalar("SELECT cover_hash FROM music_entries")
        .fetch_one(&app.db)
        .await
        .unwrap();

    let start = builds();
    let first = music_policy(&app, &token).await["library_version"].clone();
    for _ in 0..3 {
        assert_eq!(music_policy(&app, &token).await["library_version"], first);
        let res = device_get(&app, "/api/devices/music/library", &token, &[]).await;
        assert_eq!(res.status, StatusCode::OK);
    }
    for _ in 0..5 {
        let res = device_get(
            &app,
            &format!("/api/devices/music/covers/{hash}"),
            &token,
            &[],
        )
        .await;
        assert_eq!(res.status, StatusCode::OK);
    }
    assert_eq!(
        builds() - start,
        1,
        "one build for 4 polls, 3 library GETs and 5 covers"
    );

    // Every table the library is built from moves the revision (triggers, migration 0050).
    let revision = || async {
        sqlx::query_scalar::<_, i64>("SELECT revision FROM music_library_revision")
            .fetch_one(&app.db)
            .await
            .unwrap()
    };
    for sql in [
        "UPDATE music_entries SET name = 'Abels tårn!'",
        "UPDATE music_categories SET name = 'Podcaster' WHERE id = 4",
        "INSERT INTO music_files (entry_id, path, original_name, size, sha256) \
         VALUES (1, '1/x.mp3', 'x.mp3', 1, 'x')",
        "DELETE FROM music_files",
        "DELETE FROM device_music_entries",
        "INSERT INTO device_music_entries (device_id, entry_id) VALUES (1, 1)",
    ] {
        let before = revision().await;
        sqlx::query(sql).execute(&app.db).await.unwrap();
        assert!(revision().await > before, "{sql}");
    }
    // ... so the next poll builds again and sees the change, also when written by plain SQL.
    let start = builds();
    let music = music_policy(&app, &token).await;
    assert_ne!(music["library_version"], first);
    let library = device_get(&app, "/api/devices/music/library", &token, &[])
        .await
        .json();
    assert_eq!(library["entries"][0]["name"], json!("Abels tårn!"));
    assert_eq!(library["categories"][0]["name"], json!("Podcaster"));
    assert_eq!(builds() - start, 1);
    // The device page uses the same kept library.
    let start = builds();
    app.get_page(&format!("/devices/{device}"), &cookie).await;
    assert_eq!(builds() - start, 0);
}

/// #3: an import builds each phone's library once, plus once to confirm - not once per entry and
/// phone inside the write transaction.
#[tokio::test]
async fn an_import_builds_each_phones_library_once() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (big, _) = app.enrolled_device("Big").await;
    let (small, _) = app.enrolled_device("Small").await;
    let own = add_own(&app, &cookie, "Album").await;
    sqlx::query(
        "WITH RECURSIVE n(i) AS (SELECT 1 UNION ALL SELECT i + 1 FROM n WHERE i < 2000) \
         INSERT INTO music_files (entry_id, path, original_name, size, sha256, title, track_no) \
         SELECT ?, ? || '/f' || i || '.mp3', 'f' || i || '.mp3', 1000, printf('%064d', i), \
                'Spor nummer ' || i, i FROM n",
    )
    .bind(own)
    .bind(own.to_string())
    .execute(&app.db)
    .await
    .unwrap();
    tick(&app, &cookie, big, own, true).await;
    let entries: Vec<Value> = (0..20)
        .map(|i| json!({"name": format!("Podkast {i}"), "target": format!("https://example.org/{i}.rss")}))
        .collect();
    let file = json!({"sections": [{"name": "Podkast", "entries": entries}]});
    let page = import_preview(&app, &cookie, file.to_string().as_bytes())
        .await
        .text();
    let doc = preview_doc(&page);
    let rows: Vec<String> = preview_rows(&page);
    let (big_id, small_id) = (big.to_string(), small.to_string());
    let mut fields: Vec<(&str, &str)> =
        vec![("doc", &doc), ("phone", &big_id), ("phone", &small_id)];
    fields.extend(rows.iter().map(|r| ("row", r.as_str())));
    let start = builds();
    let res = import_confirm(&app, &cookie, &fields).await;
    assert_eq!(res.location(), Some("/music#import"));
    assert_eq!(
        builds() - start,
        4,
        "two phones, one build each plus one to confirm"
    );
    let ticks: Vec<(i64, i64)> = sqlx::query_as(
        "SELECT device_id, COUNT(*) FROM device_music_entries GROUP BY device_id ORDER BY device_id",
    )
    .fetch_all(&app.db)
    .await
    .unwrap();
    assert_eq!(ticks, vec![(big, 21), (small, 20)]);
    // The estimate matched: both libraries are what a fresh build says, within the limit.
    for phone in [big, small] {
        let library = crate::music::device_library(&app.db, phone)
            .await
            .unwrap()
            .unwrap();
        assert!(library.json.len() <= crate::music::MAX_LIBRARY_BYTES);
    }
}

/// #10: a feed is judged by its first 20 MB (design 21b raised it from 5 MB) - a long feed with
/// its episodes up front is added, one whose first 20 MB hold no episode isn't.
#[tokio::test]
async fn a_feed_longer_than_the_cap_is_judged_by_its_start() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let notes = "<item><title>Gammel episode</title><description>".to_string()
        + &"Lange shownotes. ".repeat(200)
        + "</description><enclosure url=\"https://example.org/old.mp3\"/></item>";
    let mut long = String::from(
        "<rss><channel><title>Lang podkast</title>\
         <item><title>Ny</title><enclosure url=\"https://example.org/new.mp3\"/></item>",
    );
    while long.len() < 21_000_000 {
        long.push_str(&notes);
    }
    long.push_str("</channel></rss>");
    app.fetch
        .answer("https://example.org/long.rss", 200, long.as_bytes());
    let res = app
        .request_form(
            Method::POST,
            "/music",
            Some(&cookie),
            &[("link", "https://example.org/long.rss")],
        )
        .await;
    assert_eq!(res.status, StatusCode::SEE_OTHER, "{}", res.text());
    let name: String = sqlx::query_scalar("SELECT name FROM music_entries")
        .fetch_one(&app.db)
        .await
        .unwrap();
    assert_eq!(name, "Lang podkast");

    let mut late = String::from("<rss><channel><title>Sen</title>");
    while late.len() < 20_500_000 {
        late.push_str("<item><title>Uten lyd</title></item>");
    }
    late.push_str("<item><enclosure url=\"https://example.org/x.mp3\"/></item></channel></rss>");
    app.fetch
        .answer("https://example.org/late.rss", 200, late.as_bytes());
    let res = app
        .request_form(
            Method::POST,
            "/music",
            Some(&cookie),
            &[("link", "https://example.org/late.rss")],
        )
        .await;
    assert_eq!(res.status, StatusCode::BAD_REQUEST);
    assert!(
        res.text().contains("first 20 MB hold no episode"),
        "{}",
        res.text()
    );
}

/// Test gap: hostile audio - an MP4 that claims a huge atom, an ID3 picture that claims 100 MB -
/// is refused, leaves no file and the server goes on.
#[tokio::test]
async fn hostile_audio_is_refused_and_leaves_nothing() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let entry = add_own(&app, &cookie, "Rart").await;
    let mut mp4 = Vec::new();
    mp4.extend_from_slice(&0x18u32.to_be_bytes());
    mp4.extend_from_slice(b"ftypM4A \0\0\0\0M4A isom");
    mp4.extend_from_slice(&0x7fff_fff0u32.to_be_bytes());
    mp4.extend_from_slice(b"moov");
    mp4.extend_from_slice(&[0u8; 64]);
    let mut id3 = b"ID3\x03\x00\x00\x0f\x7f\x7f\x7f".to_vec();
    id3.extend_from_slice(b"APIC");
    id3.extend_from_slice(&100_000_000u32.to_be_bytes());
    id3.extend_from_slice(&[0, 0, 0]);
    id3.extend_from_slice(b"image/jpeg\0\x03\0");
    id3.extend_from_slice(&[0xffu8; 256]);
    let res = upload(
        &app,
        &cookie,
        entry,
        &[("huge-atom.m4a", mp4), ("huge-picture.mp3", id3)],
    )
    .await;
    assert_eq!(res.status, StatusCode::BAD_REQUEST);
    let text = res.text();
    assert!(text.contains("huge-atom.m4a: not an audio file"), "{text}");
    assert!(
        text.contains("huge-picture.mp3: not an audio file"),
        "{text}"
    );
    let on_disk = std::fs::read_dir(app.state.music_files_dir.join(entry.to_string()))
        .unwrap()
        .count();
    assert_eq!(on_disk, 0, "no temp file left");
    // Still serving.
    let res = upload(&app, &cookie, entry, &[("ok.wav", wav(200))]).await;
    assert_eq!(res.status, StatusCode::SEE_OTHER);
}

/// #7: own files no entry names (an older backup restored) are shown on the Music page and
/// deleted only on request; young files and the referenced ones stay.
#[tokio::test]
async fn files_no_entry_names_are_shown_and_deleted_on_request() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let entry = add_own(&app, &cookie, "Bilturen").await;
    upload(&app, &cookie, entry, &[("a.wav", wav(300))]).await;
    let dir = &app.state.music_files_dir;
    let old = std::time::SystemTime::now() - std::time::Duration::from_secs(3600);
    let write_old = |path: std::path::PathBuf, bytes: usize| {
        std::fs::create_dir_all(path.parent().unwrap()).unwrap();
        std::fs::write(&path, vec![0u8; bytes]).unwrap();
        std::fs::File::options()
            .write(true)
            .open(&path)
            .unwrap()
            .set_modified(old)
            .unwrap();
    };
    write_old(dir.join(entry.to_string()).join("stray.mp3"), 1_500_000);
    write_old(dir.join("999").join("gone.mp3"), 500_000);
    std::fs::write(
        dir.join(entry.to_string()).join("young.mp3"),
        b"just renamed",
    )
    .unwrap();
    let page = app.get_page("/music", &cookie).await.text();
    assert!(page.contains("id=\"orphans\""));
    assert!(page.contains("2 own files (2.0 MB)"), "{page}");

    let res = app
        .request_form(Method::POST, "/music/orphans/delete", Some(&cookie), &[])
        .await;
    assert_eq!(res.location(), Some("/music#entries"));
    assert!(!dir.join(entry.to_string()).join("stray.mp3").exists());
    assert!(!dir.join("999").exists(), "the gone entry's directory too");
    assert!(dir.join(entry.to_string()).join("young.mp3").exists());
    let path: String = sqlx::query_scalar("SELECT path FROM music_files")
        .fetch_one(&app.db)
        .await
        .unwrap();
    assert!(dir.join(path).exists(), "the referenced file stays");
    assert_eq!(
        events(&app, "music_orphans_deleted").await,
        vec!["2 file(s), 2.0 MB".to_string()]
    );
    assert!(
        !app.get_page("/music", &cookie)
            .await
            .text()
            .contains("id=\"orphans\"")
    );
}
