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

use crate::music::MAX_ENTRIES;
use sha2::{Digest, Sha256};

use super::{
    Section, TestApp, TestResponse, assert_redirect, autosaves, device_page, form_action_of,
    input_checked, nav_current,
};

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
    /// A 3xx's `Location` (design 26c §3.1: a short link's hop).
    pub location: Option<String>,
}

/// One request as the canned fetch saw it (design 26b §2.2: the Storytel bookshelf's shape).
/// Never logged.
#[derive(Clone)]
pub struct Recorded {
    pub method: String,
    pub url: String,
    pub headers: Vec<(String, String)>,
    pub body: Option<Vec<u8>>,
    /// The request's `follow` (design 26c §3.1).
    pub follow: bool,
}

impl Recorded {
    pub fn header(&self, name: &str) -> Option<&str> {
        self.headers
            .iter()
            .find(|(n, _)| n.eq_ignore_ascii_case(name))
            .map(|(_, v)| v.as_str())
    }
}

/// Every music source fetch in the tests (`AppState.music_fetch`, behind the real gate): canned
/// answers by URL (anything else is a network error; `answer_once` answers go first, once each),
/// canned DNS (unknown hosts resolve to a public address), a public-only request to a host that
/// resolves only privately is refused like the real resolver does, `If-None-Match` matching a
/// canned ETag answers 304. Every URL asked for is recorded (with its method, headers and body in
/// `requests`), and how many requests were in flight at once.
#[derive(Default)]
pub struct CannedFetch {
    answers: Mutex<HashMap<String, Canned>>,
    once: Mutex<HashMap<String, std::collections::VecDeque<Canned>>>,
    pub requests: Mutex<Vec<Recorded>>,
    failures: Mutex<HashMap<String, crate::music_net::FetchError>>,
    dns: Mutex<HashMap<String, Vec<std::net::IpAddr>>>,
    pub calls: Mutex<Vec<String>>,
    /// The validators each request carried: (url, If-None-Match).
    pub conditional: Mutex<Vec<(String, Option<String>)>>,
    /// Which addresses each request was allowed to reach.
    pub reaches: Mutex<Vec<(String, crate::music_net::Reach)>>,
    /// `Retry-After` sent with every answer for a URL ([CannedFetch::retry_after], design 26c).
    retry_after: Mutex<HashMap<String, String>>,
    delay_ms: std::sync::atomic::AtomicU64,
    /// While armed ([CannedFetch::hold]), every request waits here until [CannedFetch::release].
    gate: Mutex<Option<std::sync::Arc<tokio::sync::Semaphore>>>,
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

    /// Answers `url` with a redirect to `location` (never followed here: a canned answer is the
    /// answer, as with `follow: false`).
    pub fn redirect(&self, url: &str, status: u16, location: &str) {
        self.canned(
            url,
            Canned {
                status,
                location: Some(location.to_string()),
                ..Default::default()
            },
        );
    }

    /// Answers the next request for `url` once, before the standing answer.
    pub fn answer_once(&self, url: &str, status: u16, body: impl Into<Vec<u8>>) {
        self.once
            .lock()
            .unwrap()
            .entry(url.to_string())
            .or_default()
            .push_back(Canned {
                status,
                body: body.into(),
                ..Default::default()
            });
    }

    pub fn canned(&self, url: &str, answer: Canned) {
        self.failures.lock().unwrap().remove(url);
        self.answers.lock().unwrap().insert(url.to_string(), answer);
    }

    pub fn fail(&self, url: &str, err: crate::music_net::FetchError) {
        self.answers.lock().unwrap().remove(url);
        self.failures.lock().unwrap().insert(url.to_string(), err);
    }

    /// Every answer for `url` carries `Retry-After: value` (design 26c §2.4).
    pub fn retry_after(&self, url: &str, value: &str) {
        self.retry_after
            .lock()
            .unwrap()
            .insert(url.to_string(), value.to_string());
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

    /// From now on every request waits (counted in [CannedFetch::in_flight]) until [release].
    pub fn hold(&self) {
        *self.gate.lock().unwrap() = Some(std::sync::Arc::new(tokio::sync::Semaphore::new(0)));
    }

    /// Lets every waiting and later request through.
    pub fn release(&self) {
        if let Some(gate) = self.gate.lock().unwrap().take() {
            gate.close();
        }
    }

    /// Waits (without a deadline that could race) until a request is held at the gate.
    pub async fn wait_in_flight(&self) {
        while self.in_flight() == 0 {
            tokio::task::yield_now().await;
            tokio::time::sleep(std::time::Duration::from_millis(1)).await;
        }
    }

    /// Requests being answered right now (the tests that act during a check wait for one).
    pub fn in_flight(&self) -> usize {
        self.in_flight.load(std::sync::atomic::Ordering::SeqCst)
    }

    pub fn clear_calls(&self) {
        self.calls.lock().unwrap().clear();
        self.requests.lock().unwrap().clear();
        self.conditional.lock().unwrap().clear();
        self.reaches.lock().unwrap().clear();
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
            let gate = self.gate.lock().unwrap().clone();
            if let Some(gate) = gate {
                // Closed by `release`.
                let _ = gate.acquire().await;
            }
            let delay = self.delay_ms.load(Ordering::SeqCst);
            if delay > 0 {
                tokio::time::sleep(std::time::Duration::from_millis(delay)).await;
            }
            self.in_flight.fetch_sub(1, Ordering::SeqCst);
            self.calls.lock().unwrap().push(request.url.clone());
            self.requests.lock().unwrap().push(Recorded {
                method: request.method.to_string(),
                url: request.url.clone(),
                headers: request
                    .headers
                    .iter()
                    .map(|(name, value)| (name.to_string(), value.clone()))
                    .collect(),
                body: request.body.clone(),
                follow: request.follow,
            });
            self.conditional
                .lock()
                .unwrap()
                .push((request.url.clone(), request.etag.clone()));
            self.reaches
                .lock()
                .unwrap()
                .push((request.url.clone(), request.reach));
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
            let once = self
                .once
                .lock()
                .unwrap()
                .get_mut(&request.url)
                .and_then(|queue| queue.pop_front());
            let Some(answer) =
                once.or_else(|| self.answers.lock().unwrap().get(&request.url).cloned())
            else {
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
                retry_after: self.retry_after.lock().unwrap().get(&request.url).cloned(),
                location: answer.location,
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

pub(super) async fn add_nrk(app: &TestApp, cookie: &str, slug: &str, title: &str) -> i64 {
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

/// What an entry's page says about the phone named `name`: its On phones row's sub-line (the
/// phone's last report on the entry), `None` without such a row (design 31 §3.4).
pub(super) fn phone_line(page: &str, name: &str) -> Option<String> {
    page.split("data-phone=\"").skip(1).find_map(|row| {
        let row = &row[..row.find("</a>")?];
        let label = row.split_once("class=\"row-label\">")?.1;
        let (row_name, rest) = label.split_once('<')?;
        if row_name != name {
            return None;
        }
        let line = rest.split_once("data-phone-line>")?.1;
        Some(line[..line.find("</span>")?].replace("&#39;", "'"))
    })
}

pub(super) async fn add_own(app: &TestApp, cookie: &str, name: &str) -> i64 {
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
    // Since 0073 (design 30) the one mobile-data switch is two, both from it; and the storage limit.
    let policy: (bool, bool, Option<i64>, bool, Option<i64>) = sqlx::query_as(
        "SELECT music_stream_mobile_data, music_download_mobile_data, music_volume_cap_pct, \
         music_storytel, music_storage_limit_gb FROM device_policy",
    )
    .fetch_one(&db)
    .await
    .unwrap();
    assert_eq!(
        policy,
        (false, false, None, false, Some(2)),
        "mobile data, cap and Storytel all off, the limit 2 GB"
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
async fn the_library_holds_at_most_max_entries() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    sqlx::query(
        "WITH RECURSIVE n(i) AS (SELECT 1 UNION ALL SELECT i + 1 FROM n WHERE i < ?) \
         INSERT INTO music_entries (name, category_id, source, key) \
         SELECT 'E' || i, 1, 'own', 'own-x' || i FROM n",
    )
    .bind(MAX_ENTRIES)
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
    assert!(res.text().contains(&format!("{MAX_ENTRIES} entries")));
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
        json!({
            "library_version": null,
            "mobile_data": false,
            "stream_mobile_data": false,
            "download_mobile_data": false,
            "volume_cap_pct": null,
            "storage_limit_gb": 2,
            "storytel_generation": 0,
            "spotify": false
        }),
        "nothing ticked, everything off, 2 GB"
    );
    let id = add_nrk(&app, &cookie, "abels_taarn", "Abels tårn").await;
    let res = tick(&app, &cookie, device, id, true).await;
    assert_eq!(
        res.location(),
        Some(format!("/devices/{device}/music#music-entry-{id}").as_str()),
        "back to that row (qa-21-step1-code #8)"
    );
    let music = music_policy(&app, &token).await;
    let version = music["library_version"].as_str().unwrap().to_string();
    assert_eq!(version.len(), 16);
    assert!(version.bytes().all(|b| b.is_ascii_hexdigit()));

    // Today's page (`v=2`): each switch saves alone.
    let res = app
        .request_form(
            Method::POST,
            &format!("/devices/{device}/music"),
            Some(&cookie),
            &[
                ("v", "2"),
                ("stream_mobile_data", "on"),
                ("volume_cap", "70"),
                ("storage_limit", "5"),
            ],
        )
        .await;
    assert_eq!(
        res.location(),
        Some(format!("/devices/{device}/music#music-settings").as_str())
    );
    let music = music_policy(&app, &token).await;
    assert_eq!(music["stream_mobile_data"], json!(true));
    assert_eq!(
        music["download_mobile_data"],
        json!(false),
        "a missing checkbox is off"
    );
    assert_eq!(music["mobile_data"], json!(false), "both only when both");
    assert_eq!(music["volume_cap_pct"], json!(70));
    assert_eq!(music["storage_limit_gb"], json!(5));
    assert_eq!(
        music["library_version"],
        json!(version),
        "settings aren't in the library"
    );
    assert_eq!(
        events(&app, "music_phone_settings").await.last().unwrap(),
        &format!(
            "device {device}: streaming on mobile data true, downloads on mobile data false, \
             storage limit 5 GB, volume cap 70 %, Storytel false"
        )
    );
    app.request_form(
        Method::POST,
        &format!("/devices/{device}/music"),
        Some(&cookie),
        &[("v", "2"), ("download_mobile_data", "on")],
    )
    .await;
    let music = music_policy(&app, &token).await;
    assert_eq!(
        (
            &music["stream_mobile_data"],
            &music["download_mobile_data"],
            &music["mobile_data"]
        ),
        (&json!(false), &json!(true), &json!(false))
    );
    assert_eq!(
        music["storage_limit_gb"],
        json!(5),
        "a post without the limit keeps it"
    );

    // The limit's choices; "none" is no limit; anything else is refused and changes nothing.
    for (choice, sent) in [("1", json!(1)), ("10", json!(10)), ("none", json!(null))] {
        let res = app
            .request_form(
                Method::POST,
                &format!("/devices/{device}/music"),
                Some(&cookie),
                &[("v", "2"), ("storage_limit", choice)],
            )
            .await;
        assert_eq!(res.status, StatusCode::SEE_OTHER, "{choice}");
        assert_eq!(music_policy(&app, &token).await["storage_limit_gb"], sent);
    }
    assert!(
        events(&app, "music_phone_settings")
            .await
            .last()
            .unwrap()
            .contains("storage limit no limit")
    );
    for bad in ["0", "3", "20", "-1", "", "x", "None"] {
        let res = app
            .request_form(
                Method::POST,
                &format!("/devices/{device}/music"),
                Some(&cookie),
                &[
                    ("v", "2"),
                    ("stream_mobile_data", "on"),
                    ("storage_limit", bad),
                ],
            )
            .await;
        assert_eq!(res.status, StatusCode::BAD_REQUEST, "{bad:?}");
    }
    let music = music_policy(&app, &token).await;
    assert_eq!(music["storage_limit_gb"], json!(null));
    assert_eq!(
        music["stream_mobile_data"],
        json!(false),
        "a refused post writes nothing"
    );
    app.request_form(
        Method::POST,
        &format!("/devices/{device}/music"),
        Some(&cookie),
        &[("v", "2"), ("storage_limit", "2")],
    )
    .await;

    // The volume cap: off and the other choices; anything else is refused and changes nothing.
    for cap in ["90", "80", "60"] {
        app.request_form(
            Method::POST,
            &format!("/devices/{device}/music"),
            Some(&cookie),
            &[("v", "2"), ("volume_cap", cap)],
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
                &[("v", "2"), ("volume_cap", bad)],
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
        &[("v", "2"), ("volume_cap", "off")],
    )
    .await;
    let music = music_policy(&app, &token).await;
    assert_eq!(music["volume_cap_pct"], json!(null));
    assert_eq!(music["storage_limit_gb"], json!(2));
    // A stored value that isn't a choice is sent as off (the cap) and as 2 GB (the limit).
    sqlx::query(
        "UPDATE device_policy SET music_volume_cap_pct = 45, music_storage_limit_gb = 7 \
         WHERE device_id = ?",
    )
    .bind(device)
    .execute(&app.db)
    .await
    .unwrap();
    let music = music_policy(&app, &token).await;
    assert_eq!(music["volume_cap_pct"], json!(null));
    assert_eq!(music["storage_limit_gb"], json!(2));

    // An unknown phone is a 404, with nothing written.
    let res = app
        .request_form(
            Method::POST,
            "/devices/9999/music",
            Some(&cookie),
            &[("v", "2"), ("storage_limit", "1")],
        )
        .await;
    assert_eq!(res.status, StatusCode::NOT_FOUND);
}

#[test]
fn mobile_data_on_the_wire_is_both_switches() {
    use crate::music::mobile_data_wire;
    // (mobile_data, stream_mobile_data, download_mobile_data)
    assert_eq!(mobile_data_wire(true, false), (false, true, false));
    assert_eq!(mobile_data_wire(false, true), (false, false, true));
    assert_eq!(mobile_data_wire(true, true), (true, true, true));
    assert_eq!(mobile_data_wire(false, false), (false, false, false));
}

#[tokio::test]
async fn migration_0073_gives_both_switches_the_old_value_and_every_phone_2_gb() {
    let (db, _dir) = super::cleanup::migrated_before(73).await;
    let mut devices = Vec::new();
    for (name, mobile_data) in [("on", 1), ("off", 0)] {
        let device: i64 = sqlx::query_scalar("INSERT INTO devices (name) VALUES (?) RETURNING id")
            .bind(name)
            .fetch_one(&db)
            .await
            .unwrap();
        sqlx::query(
            "INSERT INTO device_policy (device_id, kiosk_desired, music_mobile_data, \
             music_volume_cap_pct, music_storytel) VALUES (?, 1, ?, 70, 1)",
        )
        .bind(device)
        .bind(mobile_data)
        .execute(&db)
        .await
        .unwrap();
        devices.push(device);
    }
    super::cleanup::finish(&db).await;

    type Row = (i64, bool, bool, Option<i64>, Option<i64>, bool);
    let rows: Vec<Row> = sqlx::query_as(
        "SELECT device_id, music_stream_mobile_data, music_download_mobile_data, \
         music_storage_limit_gb, music_volume_cap_pct, music_storytel \
         FROM device_policy ORDER BY device_id",
    )
    .fetch_all(&db)
    .await
    .unwrap();
    assert_eq!(
        rows,
        vec![
            (devices[0], true, true, Some(2), Some(70), true),
            (devices[1], false, false, Some(2), Some(70), true),
        ],
        "both switches take the old value; 2 GB for every phone; the rest kept"
    );
    let old_column: i64 = sqlx::query_scalar(
        "SELECT COUNT(*) FROM pragma_table_info('device_policy') WHERE name = 'music_mobile_data'",
    )
    .fetch_one(&db)
    .await
    .unwrap();
    assert_eq!(old_column, 0, "music_mobile_data is gone");
    // A phone added after the migration gets the defaults.
    let device: i64 = sqlx::query_scalar("INSERT INTO devices (name) VALUES ('new') RETURNING id")
        .fetch_one(&db)
        .await
        .unwrap();
    sqlx::query("INSERT INTO device_policy (device_id, kiosk_desired) VALUES (?, 1)")
        .bind(device)
        .execute(&db)
        .await
        .unwrap();
    let fresh: (bool, bool, Option<i64>) = sqlx::query_as(
        "SELECT music_stream_mobile_data, music_download_mobile_data, music_storage_limit_gb \
         FROM device_policy WHERE device_id = ?",
    )
    .bind(device)
    .fetch_one(&db)
    .await
    .unwrap();
    assert_eq!(fresh, (false, false, Some(2)));
}

#[tokio::test]
async fn a_page_from_before_the_update_sets_both_switches_from_mobile_data() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (device, token) = app.enrolled_device("phone").await;
    app.request_form(
        Method::POST,
        &format!("/devices/{device}/music"),
        Some(&cookie),
        &[
            ("v", "2"),
            ("stream_mobile_data", "on"),
            ("storage_limit", "10"),
        ],
    )
    .await;
    // The old page: one checkbox, no `v`, no limit. It meant both, so it sets both.
    let res = app
        .request_form(
            Method::POST,
            &format!("/devices/{device}/music"),
            Some(&cookie),
            &[("mobile_data", "on"), ("volume_cap", "off")],
        )
        .await;
    assert_eq!(
        res.location(),
        Some(format!("/devices/{device}/music#music-settings").as_str())
    );
    let music = music_policy(&app, &token).await;
    assert_eq!(
        (
            &music["mobile_data"],
            &music["stream_mobile_data"],
            &music["download_mobile_data"]
        ),
        (&json!(true), &json!(true), &json!(true))
    );
    assert_eq!(music["storage_limit_gb"], json!(10), "the limit unchanged");
    // The event names the limit in force, not the absent posted one (qa-30-s1-code L2).
    assert_eq!(
        events(&app, "music_phone_settings").await.last().unwrap(),
        &format!(
            "device {device}: streaming on mobile data true, downloads on mobile data true, \
             storage limit 10 GB, volume cap off, Storytel false"
        )
    );
    // Its checkbox off: both off. Today's switch names without `v` aren't read.
    app.request_form(
        Method::POST,
        &format!("/devices/{device}/music"),
        Some(&cookie),
        &[("stream_mobile_data", "on"), ("volume_cap", "off")],
    )
    .await;
    let music = music_policy(&app, &token).await;
    assert_eq!(
        (&music["stream_mobile_data"], &music["download_mobile_data"]),
        (&json!(false), &json!(false))
    );
    assert_eq!(music["storage_limit_gb"], json!(10));
    // With `v=2` the old `mobile_data` means nothing.
    app.request_form(
        Method::POST,
        &format!("/devices/{device}/music"),
        Some(&cookie),
        &[("v", "2"), ("mobile_data", "on")],
    )
    .await;
    assert_eq!(
        music_policy(&app, &token).await["mobile_data"],
        json!(false)
    );
}

#[tokio::test]
async fn the_page_shows_two_switches_and_the_limit_and_saves_back_to_music_settings() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (device, _token) = app.enrolled_device("phone").await;
    let music = device_page(device, Section::Music);
    let page = app.get_page(&music, &cookie).await.text();
    // One form (design 31 §5.6, design 30's `v=2`): all five values and the marker, so a save of
    // one never writes the others' defaults; it returns to its own group.
    let action = format!("/devices/{device}/music#music-settings");
    for name in [
        "v",
        "stream_mobile_data",
        "download_mobile_data",
        "storage_limit",
        "volume_cap",
        "storytel",
    ] {
        assert_eq!(form_action_of(&page, name), Some(action.as_str()), "{name}");
    }
    let form = settings_form(&page);
    assert!(form.contains("<input type=\"hidden\" name=\"v\" value=\"2\">"));
    assert!(form.contains("Stream on mobile data"));
    assert!(form.contains("(not for an entry that keeps nothing)"));
    assert!(form.contains("Download on mobile data"));
    assert!(form.contains("Never while roaming. On Wi-Fi both always work"));
    assert!(form.contains("Storage limit"));
    assert!(form.contains("The offline episodes always stay"));
    for name in ["stream_mobile_data", "download_mobile_data", "storytel"] {
        assert_eq!(input_checked(form, name, "on"), Some(false), "{name}");
        assert!(autosaves(form, name), "{name}");
    }
    assert!(autosaves(form, "storage_limit") && autosaves(form, "volume_cap"));
    assert_eq!(
        select_options(form, "storage_limit"),
        [
            ("1", "1 GB", false),
            ("2", "2 GB", true),
            ("5", "5 GB", false),
            ("10", "10 GB", false),
            ("none", "No limit", false),
        ]
        .map(|(v, l, s)| (v.to_string(), l.to_string(), s))
    );
    assert!(
        !form.contains("name=\"mobile_data\""),
        "the old switch is gone"
    );

    let res = app
        .request_form(
            Method::POST,
            &format!("/devices/{device}/music"),
            Some(&cookie),
            &[
                ("v", "2"),
                ("download_mobile_data", "on"),
                ("storage_limit", "none"),
            ],
        )
        .await;
    assert_redirect(&res, &music, Some("music-settings"));
    let page = app.get_page(&music, &cookie).await.text();
    let form = settings_form(&page);
    assert_eq!(
        input_checked(form, "download_mobile_data", "on"),
        Some(true)
    );
    assert_eq!(input_checked(form, "stream_mobile_data", "on"), Some(false));
    let limit = select_options(form, "storage_limit");
    assert!(
        limit.iter().all(|(v, _, s)| *s == (v == "none")),
        "{limit:?}"
    );
}

/// The settings form on Device › Music, up to its end.
fn settings_form(page: &str) -> &str {
    let form = &page[page
        .find("id=\"music-settings\"")
        .expect("the settings form")..];
    &form[..form.find("</form>").unwrap()]
}

/// The `<select>` named `name`: each option's value, text and whether it is selected.
fn select_options(html: &str, name: &str) -> Vec<(String, String, bool)> {
    let select = super::tags(html, "select")
        .into_iter()
        .find(|tag| tag.attr("name") == Some(name))
        .expect(name);
    let body = &html[select.start..];
    let body = &body[..body.find("</select>").unwrap()];
    super::tags(body, "option")
        .into_iter()
        .map(|option| {
            let text = &body[option.start..];
            let text = &text[text.find('>').unwrap() + 1..];
            (
                option.attr("value").unwrap_or("").to_string(),
                text[..text.find('<').unwrap()].to_string(),
                option.has("selected"),
            )
        })
        .collect()
}

/// The value of the row `key` (`data-row`) on Device › Music, tags dropped.
pub(super) fn row_value(html: &str, key: &str) -> Option<String> {
    let row = &html[html.find(&format!("data-row=\"{key}\""))?..];
    let value = &row[row.find("class=\"row-value")?..];
    let value = &value[value.find('>')? + 1..];
    Some(value[..value.find('<')?].to_string())
}

/// A status report from the phone with this `music_state`.
async fn report_music_state(app: &TestApp, token: &str, state: Value) {
    let res = app
        .request(
            Method::POST,
            "/api/devices/status",
            Some(token),
            Some(json!({
                "lock_reason": "NONE",
                "kiosk_engaged": true,
                "capabilities": ["music_v1"],
                "music_state": state
            })),
        )
        .await;
    assert_eq!(res.status, StatusCode::NO_CONTENT);
}

/// Today's Music settings form with these switches.
async fn save_switches(app: &TestApp, cookie: &str, device: i64, stream: bool, download: bool) {
    let mut fields = vec![("v", "2")];
    if stream {
        fields.push(("stream_mobile_data", "on"));
    }
    if download {
        fields.push(("download_mobile_data", "on"));
    }
    let res = app
        .request_form(
            Method::POST,
            &format!("/devices/{device}/music"),
            Some(cookie),
            &fields,
        )
        .await;
    assert_eq!(res.status, StatusCode::SEE_OTHER);
}

/// The phone's Music page (Device › Music).
pub(super) async fn music_page(app: &TestApp, cookie: &str, device: i64) -> String {
    let res = app
        .get_page(&device_page(device, Section::Music), cookie)
        .await;
    assert_eq!(res.status, StatusCode::OK, "{}", res.text());
    res.text()
}

#[tokio::test]
async fn the_page_says_an_older_phone_uses_mobile_data_only_with_both() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (device, token) = app.enrolled_device("phone").await;
    const NOTE: &str = "uses mobile data for music only when both switches are on.";
    // No report yet: nothing, whatever the switches.
    save_switches(&app, &cookie, device, true, false).await;
    assert!(!music_page(&app, &cookie, device).await.contains(NOTE));
    // Only the launcher's fields (before the app's first report): not an app report.
    report_music_state(
        &app,
        &token,
        json!({"package": "me.vibb.music", "version_code": 1000, "bridge": "bound"}),
    )
    .await;
    assert!(!music_page(&app, &cookie, device).await.contains(NOTE));
    // An app report (`cache_bytes`) without `extra_bytes`: the switches differ, so the warning,
    // above the two switches.
    report_music_state(
        &app,
        &token,
        json!({"package": "me.vibb.music", "bridge": "bound", "cache_bytes": 1_000_000}),
    )
    .await;
    let shown = music_page(&app, &cookie, device).await;
    let form = settings_form(&shown);
    assert!(form.contains(NOTE), "{shown}");
    assert!(form.contains("Update Vibb One and the music app"));
    assert!(form.find(NOTE) < form.find("name=\"stream_mobile_data\""));
    // A music app new enough to send `extra_bytes` (music-v0.4.0): Vibb One dropped it, so only
    // Vibb One is named - on the page and in the summary's Attention row.
    report_music_state(
        &app,
        &token,
        json!({"package": "me.vibb.music", "version_code": 4_000, "bridge": "bound",
               "cache_bytes": 1_000_000}),
    )
    .await;
    let form = settings_form(&music_page(&app, &cookie, device).await).to_string();
    assert!(form.contains("Update Vibb One") && !form.contains("and the music app"));
    let summary = app
        .get_page(&format!("/devices/{device}"), &cookie)
        .await
        .text();
    assert!(summary.contains("Update Vibb One") && !summary.contains("and the music app"));
    save_switches(&app, &cookie, device, false, true).await;
    assert!(music_page(&app, &cookie, device).await.contains(NOTE));
    // Equal switches: nothing to say.
    save_switches(&app, &cookie, device, true, true).await;
    assert!(!music_page(&app, &cookie, device).await.contains(NOTE));
    save_switches(&app, &cookie, device, false, false).await;
    assert!(!music_page(&app, &cookie, device).await.contains(NOTE));
    // A report with `extra_bytes` (design 30 step 3a; 0 counts, it is always sent) is a phone
    // that reads both switches: no warning while they differ.
    save_switches(&app, &cookie, device, true, false).await;
    report_music_state(
        &app,
        &token,
        json!({"package": "me.vibb.music", "bridge": "bound", "cache_bytes": 1_000_000,
               "audio_bytes": 900_000, "extra_bytes": 0}),
    )
    .await;
    let shown = music_page(&app, &cookie, device).await;
    assert!(!shown.contains(NOTE), "{shown}");
    // The report landed (a refused one is stored as nothing, which would hide the warning too).
    assert_eq!(row_value(&shown, "stored").as_deref(), Some("1.0 MB"));
    assert_eq!(
        row_value(&shown, "downloads").as_deref(),
        Some("0.9 MB of 2 GB")
    );
}

#[tokio::test]
async fn the_storage_rows_show_the_limit_and_the_extras() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (device, token) = app.enrolled_device("phone").await;
    const OVER: &str = "Over the storage limit";
    // An app from before design 30 (no `audio_bytes`): what it stores, no warning, whatever the
    // limit.
    report_music_state(
        &app,
        &token,
        json!({"package": "me.vibb.music", "bridge": "bound", "cache_bytes": 3_500_000_000_i64}),
    )
    .await;
    for limit in ["1", "none"] {
        app.request_form(
            Method::POST,
            &format!("/devices/{device}/music"),
            Some(&cookie),
            &[("v", "2"), ("storage_limit", limit)],
        )
        .await;
        let page = music_page(&app, &cookie, device).await;
        assert_eq!(row_value(&page, "stored").as_deref(), Some("3500.0 MB"));
        assert_eq!(row_value(&page, "downloads"), None, "{limit}");
        assert!(!page.contains(OVER), "{limit}");
    }
    // A design 30 app (step 3a on) reports `audio_bytes` and `extra_bytes`: the downloads against
    // the limit, the extras, and the warning above the limit when the kept items alone pass it.
    report_music_state(
        &app,
        &token,
        json!({"package": "me.vibb.music", "bridge": "bound", "cache_bytes": 3_500_000_000_i64,
               "audio_bytes": 3_400_000_000_i64, "extra_bytes": 200_000_000}),
    )
    .await;
    app.request_form(
        Method::POST,
        &format!("/devices/{device}/music"),
        Some(&cookie),
        &[("v", "2"), ("storage_limit", "2")],
    )
    .await;
    let page = music_page(&app, &cookie, device).await;
    assert_eq!(row_value(&page, "stored").as_deref(), Some("3500.0 MB"));
    assert_eq!(
        row_value(&page, "downloads").as_deref(),
        Some("3400.0 MB of 2 GB")
    );
    assert_eq!(row_value(&page, "extras").as_deref(), Some("200.0 MB"));
    let form = settings_form(&page);
    assert!(
        form.contains("Kept episodes and books alone use 3.2 GB of the 2 GB limit."),
        "{form}"
    );
    assert!(form.find(OVER) < form.find("name=\"storage_limit\""));
    app.request_form(
        Method::POST,
        &format!("/devices/{device}/music"),
        Some(&cookie),
        &[("v", "2"), ("storage_limit", "5")],
    )
    .await;
    let page = music_page(&app, &cookie, device).await;
    assert_eq!(
        row_value(&page, "downloads").as_deref(),
        Some("3400.0 MB of 5 GB")
    );
    assert!(!page.contains(OVER), "{page}");
    // The pure rows' variants:
    use crate::music::{Storage, storage};
    assert_eq!(
        storage(1_234_500_000, Some(980_000_000), Some(210_000_000), Some(2)),
        Storage {
            stored: "1234.5 MB".to_string(),
            downloads: Some("980.0 MB of 2 GB".to_string()),
            extras: Some("210.0 MB".to_string()),
            over_limit: None,
        }
    );
    let rows = storage(1_234_500_000, Some(980_000_000), Some(0), Some(2));
    assert_eq!(rows.extras, None, "no extras, no row");
    assert_eq!(
        storage(1_234_500_000, Some(980_000_000), None, None).downloads,
        Some("980.0 MB (no limit)".to_string())
    );
    assert_eq!(
        storage(1_234_500_000, None, None, Some(2)),
        Storage {
            stored: "1234.5 MB".to_string(),
            downloads: None,
            extras: None,
            over_limit: None,
        },
        "an older app: what it stores only"
    );
}

#[test]
fn the_page_warns_when_kept_items_alone_pass_the_limit() {
    use crate::music::storage;
    let over = |cache, audio, extra, limit| storage(cache, audio, extra, limit).over_limit;
    // 3.4 GB of audio, 0.2 GB of it extras: 3.2 GB kept against 2 GB.
    assert_eq!(
        over(
            3_500_000_000,
            Some(3_400_000_000),
            Some(200_000_000),
            Some(2)
        ),
        Some(("3.2 GB".to_string(), 2))
    );
    // Over the limit only with the extras: they go first, no warning.
    assert_eq!(
        over(
            3_500_000_000,
            Some(3_400_000_000),
            Some(1_500_000_000),
            Some(2)
        ),
        None
    );
    // Exactly at the limit is within it.
    assert_eq!(over(0, Some(2_000_000_000), Some(0), Some(2)), None);
    // Just over the limit reads over it, never "2.0 GB of the 2 GB limit" (qa-30-s1-code L1).
    for kept in [2_000_000_001, 2_040_000_000, 2_099_999_999, 2_100_000_000] {
        assert_eq!(
            over(0, Some(kept), Some(0), Some(2)),
            Some(("2.1 GB".to_string(), 2)),
            "{kept}"
        );
    }
    assert_eq!(
        over(0, Some(2_100_000_001), Some(0), Some(2)).unwrap().0,
        "2.2 GB"
    );
    assert_eq!(
        over(0, Some(i64::MAX), Some(0), Some(10)).unwrap().1,
        10,
        "the round-up never overflows"
    );
    // No limit: never a warning. An older app (no `audio_bytes`): never a warning.
    assert_eq!(over(0, Some(i64::MAX), Some(0), None), None);
    assert_eq!(over(i64::MAX, None, None, Some(1)), None);
    // Phone numbers that don't add up never overflow.
    assert!(over(0, Some(i64::MIN), Some(i64::MAX), Some(10)).is_none());
    assert!(over(0, Some(i64::MAX), Some(i64::MIN), Some(10)).is_some());
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

/// The snapshot's two Spotify entries (design 26c §8 item 3): a playlist in list mode and an
/// artist in context mode.
pub(super) const SNAPSHOT_PLAYLIST: &str = "spotify:playlist:FAKEplaylist0000000001";
pub(super) const SNAPSHOT_ARTIST: &str = "spotify:artist:FAKEartist000000000001";

/// Fixed data for the snapshot: a podcast with a cover, an RSS feed, own files (one missing), a
/// Storytel series and two Spotify entries.
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
         (4, 'Not on this phone', 2, 'own', NULL, 'own-0b5e55ed4a11', 'auto', -1, 0, NULL, 40), \
         (5, 'Kokosbananas', 3, 'storytel', 'storytel:series:26175', ?, 'auto', -1, 1, NULL, 50), \
         (6, 'Kveldsliste', 1, 'spotify', ?, ?, 'auto', 0, 1, NULL, 60), \
         (7, 'Fake Artist', 1, 'spotify', ?, ?, 'auto', 0, 1, NULL, 70)",
    )
    .bind(&cover)
    .bind(crate::music::state_key("storytel:series:26175"))
    .bind(SNAPSHOT_PLAYLIST)
    .bind(crate::music::state_key(SNAPSHOT_PLAYLIST))
    .bind(SNAPSHOT_ARTIST)
    .bind(crate::music::state_key(SNAPSHOT_ARTIST))
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
        "INSERT INTO device_music_entries (device_id, entry_id) \
         VALUES (?, 1), (?, 2), (?, 3), (?, 5), (?, 6), (?, 7)",
    )
    .bind(device)
    .bind(device)
    .bind(device)
    .bind(device)
    .bind(device)
    .bind(device)
    .execute(&app.db)
    .await
    .unwrap();
    // The feed is listed (design 21b: `items` names its listing; its cover is the feed's own),
    // the podcast not yet (`items: null`); the Storytel series is listed from the shelf (design
    // 26b §2.4: anchored at its first book, its cover the series').
    sqlx::query(
        "INSERT INTO music_listings (entry_id, version, cover_hash, keep_end, listed_at) \
         VALUES (2, '0123456789abcdef', ?, NULL, '2026-10-09 12:00:00'), \
                (1, NULL, NULL, NULL, NULL), \
                (5, '00ff00ff00ff00ff', ?, 'first', '2026-10-09 12:00:00')",
    )
    .bind("c".repeat(64))
    .bind("d".repeat(64))
    .execute(&app.db)
    .await
    .unwrap();
    // The Spotify entries (design 26c §3.6): the playlist listed (list mode: its tracks), the
    // artist a context (Spotify's order, no items); neither has a cover yet.
    sqlx::query(
        "INSERT INTO music_listings (entry_id, version, cover_hash, keep_end, listed_at, \
         spotify_mode) VALUES \
         (6, '11ee11ee11ee11ee', NULL, 'first', '2026-10-13 12:00:00', 'list'), \
         (7, '22dd22dd22dd22dd', NULL, 'first', '2026-10-13 12:00:00', 'context')",
    )
    .execute(&app.db)
    .await
    .unwrap();
    // Storytel entries reach the phone only with its switch on and a login stored (26b §2.7).
    sqlx::query("UPDATE device_policy SET music_storytel = 1 WHERE device_id = ?")
        .bind(device)
        .execute(&app.db)
        .await
        .unwrap();
    sqlx::query(
        "UPDATE music_storytel SET ciphertext = x'00', nonce = x'00', key_fingerprint = 'fake' \
         WHERE id = 1",
    )
    .execute(&app.db)
    .await
    .unwrap();
    // Spotify entries reach the phone only while it plays as an account (design 26c §3.6): a fake
    // sealed login, never opened here.
    sqlx::query(
        "INSERT INTO music_spotify_accounts (id, spotify_user, display_name, ciphertext, nonce, \
         key_fingerprint, scopes, authorized_at) \
         VALUES (1, 'fakekid', 'Ella', x'00', x'00', 'fake', 'streaming', '2026-10-13 10:00:00')",
    )
    .execute(&app.db)
    .await
    .unwrap();
    sqlx::query("UPDATE device_policy SET music_spotify_account = 1 WHERE device_id = ?")
        .bind(device)
        .execute(&app.db)
        .await
        .unwrap();
    // The parent limited the phone to one Sonos room (design 26a §5.5; NULL = every room).
    sqlx::query(
        "UPDATE device_policy SET music_sonos_rooms_json = '[\"RINCON_000E58A0B1C201400\"]' \
         WHERE device_id = ?",
    )
    .bind(device)
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
        [
            "categories",
            "covers",
            "entries",
            "files",
            "sonos_rooms",
            "v",
            "version"
        ]
    );
    assert_eq!(served["sonos_rooms"], json!(["RINCON_000E58A0B1C201400"]));
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
            "cache", "category", "cover", "id", "items", "key", "lan", "name", "order", "resume",
            "sort", "source", "target"
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
    assert_eq!(served["entries"].as_array().unwrap().len(), 6);
    assert_eq!(served["categories"].as_array().unwrap().len(), 3);
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
        json!([
            "a".repeat(64),
            "b".repeat(64),
            "c".repeat(64),
            "d".repeat(64)
        ])
    );
    assert_eq!(served["entries"][1]["items"], json!("0123456789abcdef"));
    assert_eq!(served["entries"][0]["items"], Value::Null, "never listed");
    // The Storytel series (design 26b §2.7): its listing version and the shelf's cover, download-only
    // (cache -1), resume on, never on the LAN; the auto order stays (the phone reads it oldest first).
    let storytel = &served["entries"][3];
    assert_eq!(storytel["source"], json!("storytel"));
    assert_eq!(storytel["target"], json!("storytel:series:26175"));
    assert_eq!(storytel["items"], json!("00ff00ff00ff00ff"));
    assert_eq!(storytel["cover"], json!("d".repeat(64)));
    assert_eq!(
        (
            &storytel["cache"],
            &storytel["resume"],
            &storytel["lan"],
            &storytel["order"]
        ),
        (&json!(-1), &json!(true), &json!(false), &json!("auto"))
    );
    assert!(
        storytel.get("spotify").is_none(),
        "the mode is a Spotify entry's only"
    );
    // The Spotify entries (design 26c §3.6, §8 item 3): streamed (cache 0), resume on, never on the
    // LAN, their listing's version, and the new field `spotify` - the mode - on them alone.
    let playlist = &served["entries"][4];
    let artist = &served["entries"][5];
    assert_eq!(
        (&playlist["source"], &playlist["target"], &playlist["items"]),
        (
            &json!("spotify"),
            &json!(SNAPSHOT_PLAYLIST),
            &json!("11ee11ee11ee11ee")
        )
    );
    assert_eq!(
        (&artist["target"], &artist["items"]),
        (&json!(SNAPSHOT_ARTIST), &json!("22dd22dd22dd22dd"))
    );
    assert_eq!(playlist["spotify"], json!("list"));
    assert_eq!(artist["spotify"], json!("context"));
    for spotify in [playlist, artist] {
        assert_eq!(
            (
                &spotify["cache"],
                &spotify["resume"],
                &spotify["lan"],
                &spotify["order"],
                &spotify["cover"]
            ),
            (
                &json!(0),
                &json!(true),
                &json!(false),
                &json!("auto"),
                &Value::Null
            )
        );
    }
    let mut spotify_keys: Vec<&str> = playlist
        .as_object()
        .unwrap()
        .keys()
        .map(String::as_str)
        .collect();
    spotify_keys.sort_unstable();
    assert_eq!(
        spotify_keys,
        [
            "cache", "category", "cover", "id", "items", "key", "lan", "name", "order", "resume",
            "sort", "source", "spotify", "target"
        ]
    );
    for (i, e) in served["entries"].as_array().unwrap().iter().enumerate() {
        assert_eq!(e.get("spotify").is_some(), i >= 4, "{e}");
    }
    // The policy says the phone plays Spotify (design 26c §6.2).
    assert_eq!(music_policy(&app, &token).await["spotify"], json!(true));
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

/// `lan` (21b §2.7, qa-21b-step1c #14): true only for an RSS entry whose first check found a LAN
/// or tailnet feed; NRK is always public, and a feed not checked yet counts as public.
#[tokio::test]
async fn library_lan_only_for_a_lan_rss_feed() {
    let app = TestApp::new().await;
    let (device, token) = app.enrolled_device("phone").await;
    snapshot_library(&app, device).await;
    let lan_of = |served: &serde_json::Value| -> Vec<bool> {
        served["entries"]
            .as_array()
            .unwrap()
            .iter()
            .map(|e| e["lan"].as_bool().unwrap())
            .collect()
    };
    let res = device_get(&app, "/api/devices/music/library", &token, &[]).await;
    assert_eq!(lan_of(&res.json()), [false; 6]);
    sqlx::query("UPDATE music_listings SET lan = 1")
        .execute(&app.db)
        .await
        .unwrap();
    // The version moves with it (the same update that lists a feed writes its class).
    sqlx::query("UPDATE music_listings SET version = 'fedcba9876543210' WHERE entry_id = 2")
        .execute(&app.db)
        .await
        .unwrap();
    let res = device_get(&app, "/api/devices/music/library", &token, &[]).await;
    assert_eq!(
        lan_of(&res.json()),
        [false, true, false, false, false, false],
        "never a Storytel or a Spotify entry"
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
        Some(format!("/music/entries/{a}#settings").as_str())
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
    let page_with_notice = format!(
        "{}?music_notice=too_big&music_entry={big}",
        device_page(device, Section::Music)
    );
    assert_redirect(&res, &page_with_notice, Some(&format!("music-entry-{big}")));
    let ticked: Vec<i64> =
        sqlx::query_scalar("SELECT entry_id FROM device_music_entries WHERE device_id = ?")
            .bind(device)
            .fetch_all(&app.db)
            .await
            .unwrap();
    assert_eq!(ticked, vec![small]);
    let page = app.get_page(&page_with_notice, &cookie).await.text();
    // By the refused row (its switch off), not at the page's top or by another row.
    let cell = |entry: i64| {
        page.split(&format!("id=\"music-entry-{entry}\""))
            .nth(1)
            .and_then(|rest| rest.split("</div>").next())
            .unwrap()
            .to_string()
    };
    assert!(cell(big).contains("would pass 3 MB"), "{}", cell(big));
    assert_eq!(input_checked(&cell(big), "selected", ""), Some(false));
    assert!(!cell(small).contains("would pass 3 MB"));
    assert_eq!(page.matches("would pass 3 MB").count(), 1);
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
    assert!(list.contains("class=\"music-missing\">1 missing</span>"));

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
        Some(format!("/music/entries/{entry}#settings").as_str())
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
    assert_eq!(
        res.location(),
        Some(format!("/music?edit=1#entry-{b}").as_str())
    );
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
        Some(format!("/music/categories?open={godnatt}#category-{godnatt}").as_str())
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
    assert_eq!(res.location(), Some("/music/categories?open=4#category-4"));
    // Back on its editor, still open (qa-31-s12-code N8); the others closed.
    let page = app
        .get_page("/music/categories?open=4", &cookie)
        .await
        .text();
    assert!(page.contains("id=\"category-4\" open"));
    assert!(!page.contains("id=\"category-1\" open"));
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
        Some(format!("/music/categories?open={godnatt}#category-{godnatt}").as_str())
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
    assert_eq!(res.location(), Some("/music/categories#categories"));
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
async fn the_music_page_ticks_entries_and_switches_the_app() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (device, token) = app.enrolled_device("phone").await;
    let (other, _) = app.enrolled_device("other").await;
    let entry = add_nrk(&app, &cookie, "abels_taarn", "Abels tårn").await;
    let music = device_page(device, Section::Music);

    // No catalog row yet: the page says where to add it; Music › Settings adds it.
    let page = music_page(&app, &cookie, device).await;
    assert_eq!(nav_current(&page), "devices");
    assert_eq!(
        row_value(&page, "music-app").as_deref(),
        Some("Not in the catalog")
    );
    assert!(page.contains("href=\"/music/settings#app\""));
    let res = app
        .request_form(Method::POST, "/music/catalog-row", Some(&cookie), &[])
        .await;
    assert_eq!(res.location(), Some("/music/settings#app"));
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

    // The app switch is the catalog toggle (installs and allowlists), back to its group on this
    // page with `back=music` (design 31 §5.1.4; a page from before step 6 sent `anchor=music`,
    // which still works).
    let page = music_page(&app, &cookie, device).await;
    assert_eq!(
        form_action_of(&page, "back"),
        Some(format!("/devices/{device}/apps/toggle#music-app").as_str())
    );
    assert!(page.contains(r#"<input type="hidden" name="back" value="music">"#));
    assert_eq!(input_checked(&page, "selected", ""), Some(false));
    for back in ["back", "anchor"] {
        let res = app
            .request_form(
                Method::POST,
                &format!("/devices/{device}/apps/toggle"),
                Some(&cookie),
                &[
                    ("tracked_app_id", &app_id.to_string()),
                    ("package_name", "me.vibb.music"),
                    (back, "music"),
                    ("selected", "on"),
                ],
            )
            .await;
        assert_redirect(&res, &music, Some("music-app"));
    }
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
    let page = music_page(&app, &cookie, device).await;
    let app_group =
        &page[page.find("id=\"music-app\"").unwrap()..page.find("id=\"library\"").unwrap()];
    assert_eq!(input_checked(app_group, "selected", ""), Some(true));
    assert!(app_group.contains("No release yet"), "{app_group}");

    let mut rx = app.state.command_notify.subscribe();
    tick(&app, &cookie, device, entry, true).await;
    assert_eq!(nudged(&mut rx), vec![device], "only that phone");
    let page = music_page(&app, &cookie, device).await;
    let ticked = tick_form_of(&page, entry);
    assert!(ticked.contains("Abels tårn"));
    assert!(ticked.contains(&format!(
        "action=\"/devices/{device}/music/entries/{entry}#music-entry-{entry}\""
    )));
    assert_eq!(input_checked(&ticked, "selected", ""), Some(true), "on");
    assert!(autosaves(&ticked, "selected"), "auto-saving");
    assert!(ticked.contains("<noscript>"), "a Save without JS");
    // No report yet: no "On the phone" group, never the library's size (qa-31-s7-code L3).
    assert!(!page.contains("id=\"on-phone\""));
    assert!(!page.contains(" kB"));
    let other_page = music_page(&app, &cookie, other).await;
    assert_eq!(
        input_checked(&tick_form_of(&other_page, entry), "selected", ""),
        Some(false),
        "listed, off on the other phone"
    );
    assert!(other_page.contains(&format!(
        "action=\"/devices/{other}/music/entries/{entry}#music-entry-{entry}\""
    )));

    // Off again.
    tick(&app, &cookie, device, entry, false).await;
    assert_eq!(
        music_policy(&app, &token).await["library_version"],
        json!(null)
    );
    // An entry deleted since the page loaded: back to the list, nothing written; an unknown phone
    // is a 404.
    let res = tick(&app, &cookie, device, 999, true).await;
    assert_redirect(&res, &music, Some("library"));
    assert_eq!(
        tick(&app, &cookie, 999, entry, true).await.status,
        StatusCode::NOT_FOUND
    );
    let ticked: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM device_music_entries")
        .fetch_one(&app.db)
        .await
        .unwrap();
    assert_eq!(ticked, 0);
    assert_eq!(
        events(&app, "music_phone_entry").await,
        vec![
            format!("device {device}: Abels tårn on"),
            format!("device {device}: Abels tårn off")
        ]
    );
}

/// An entry's form on Device › Music, from its row to the form's end.
fn tick_form_of(page: &str, entry: i64) -> String {
    let at = page
        .find(&format!("id=\"music-entry-{entry}\""))
        .expect("the entry's row");
    let rest = &page[at..];
    rest[..rest.find("</form>").unwrap()].to_string()
}

/// A status report from `token`'s phone whose music app knows `rooms` (uid, name).
async fn report_rooms(app: &TestApp, token: &str, rooms: &[(&str, &str)]) {
    let rooms: Vec<Value> = rooms
        .iter()
        .map(|(uid, name)| json!({"uid": uid, "name": name}))
        .collect();
    let res = app
        .request(
            Method::POST,
            "/api/devices/status",
            Some(token),
            Some(json!({
                "lock_reason": "NONE",
                "kiosk_engaged": true,
                "capabilities": ["music_v1"],
                "music_state": {"package": "me.vibb.music", "bridge": "bound", "sonos_rooms": rooms}
            })),
        )
        .await;
    assert_eq!(res.status, StatusCode::NO_CONTENT);
}

async fn save_rooms(
    app: &TestApp,
    cookie: &str,
    device: i64,
    fields: &[(&str, &str)],
) -> TestResponse {
    app.request_form(
        Method::POST,
        &format!("/devices/{device}/music/sonos-rooms"),
        Some(cookie),
        fields,
    )
    .await
}

async fn stored_rooms(app: &TestApp, device: i64) -> Option<String> {
    sqlx::query_scalar("SELECT music_sonos_rooms_json FROM device_policy WHERE device_id = ?")
        .bind(device)
        .fetch_one(&app.db)
        .await
        .unwrap()
}

/// Device › Music's Sonos group, up to the next group.
fn sonos_group(page: &str) -> &str {
    let group = &page[page.find("id=\"music-sonos\"").expect("the Sonos group")..];
    &group[..group.find("</section>").unwrap()]
}

/// Design 26a §5.5: Device › Music's Sonos rooms - nothing to limit before the phone has seen a
/// speaker; the reported rooms with "All rooms" on (every room, NULL); off, one switch per room;
/// each save stored, in the phone's library (and its version), the phone nudged, logged, and back
/// at `#music-sonos`.
#[tokio::test]
async fn the_music_page_scopes_sonos_rooms() {
    const STUA: &str = "RINCON_000E58A0B1C201400";
    const KJOKKEN: &str = "RINCON_000E58A0B1C301400";
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (device, token) = app.enrolled_device("phone").await;
    let entry = add_nrk(&app, &cookie, "abels_taarn", "Abels tårn").await;
    tick(&app, &cookie, device, entry, true).await;
    let music = device_page(device, Section::Music);
    let library = || async {
        device_get(&app, "/api/devices/music/library", &token, &[])
            .await
            .json()
    };

    // No report yet: nothing to limit, every room allowed.
    let page = music_page(&app, &cookie, device).await;
    let sonos = sonos_group(&page);
    assert!(sonos.contains("No speakers seen yet."));
    assert!(sonos.contains("Until then the phone may play on any Sonos speaker it finds."));
    assert!(!sonos.contains("/music/sonos-rooms"));
    let all = library().await;
    assert_eq!(all["sonos_rooms"], Value::Null);

    // The phone reports two rooms: the switch, on, with every room as a hidden field.
    report_rooms(&app, &token, &[(STUA, "Stua"), (KJOKKEN, "Kjøkken <b>")]).await;
    let page = music_page(&app, &cookie, device).await;
    let sonos = sonos_group(&page);
    assert!(!sonos.contains("No speakers seen yet"));
    assert_eq!(
        form_action_of(sonos, "all"),
        Some(format!("/devices/{device}/music/sonos-rooms#music-sonos").as_str())
    );
    assert_eq!(input_checked(sonos, "all", "on"), Some(true));
    assert!(autosaves(sonos, "all"));
    assert!(sonos.contains("<noscript>"));
    assert!(sonos.contains(&format!(
        "<input type=\"hidden\" name=\"room\" value=\"{STUA}\">"
    )));
    assert!(sonos.contains(&format!(
        "<input type=\"hidden\" name=\"room\" value=\"{KJOKKEN}\">"
    )));

    // The switch off: the form sends every room, so they all stay allowed until one is off.
    let mut rx = app.state.command_notify.subscribe();
    let res = save_rooms(&app, &cookie, device, &[("room", STUA), ("room", KJOKKEN)]).await;
    assert_redirect(&res, &music, Some("music-sonos"));
    assert_eq!(nudged(&mut rx), vec![device]);
    assert_eq!(
        stored_rooms(&app, device).await.as_deref(),
        Some(format!("[\"{STUA}\",\"{KJOKKEN}\"]").as_str())
    );
    let both = library().await;
    assert_eq!(both["sonos_rooms"], json!([STUA, KJOKKEN]));
    assert_ne!(
        both["version"], all["version"],
        "hashed into library_version"
    );
    assert_eq!(
        music_policy(&app, &token).await["library_version"],
        both["version"]
    );

    // One room off: only the other. One switch per room, names escaped.
    save_rooms(&app, &cookie, device, &[("room", STUA)]).await;
    assert_eq!(library().await["sonos_rooms"], json!([STUA]));
    let page = music_page(&app, &cookie, device).await;
    let sonos = sonos_group(&page);
    assert_eq!(input_checked(sonos, "all", "on"), Some(false));
    assert_eq!(input_checked(sonos, "room", STUA), Some(true));
    assert_eq!(input_checked(sonos, "room", KJOKKEN), Some(false));
    assert!(autosaves(sonos, "room"));
    assert!(sonos.contains("Kjøkken &#60;b&#62;") || sonos.contains("Kjøkken &lt;b&gt;"));
    assert!(!page.contains("Kjøkken <b>"));
    assert!(!sonos.contains("No room on"));

    // A room the phone no longer reports stays listed while it is in the scope; its uid only in
    // the label's `title` (§4.2: no raw codes in visible text).
    report_rooms(&app, &token, &[(KJOKKEN, "Kjøkken")]).await;
    let page = music_page(&app, &cookie, device).await;
    let sonos = sonos_group(&page);
    assert!(sonos.contains(&format!(
        "<span class=\"row-label\" title=\"{STUA}\">A speaker not seen lately</span>"
    )));
    assert_eq!(
        sonos.matches(STUA).count(),
        2,
        "the title and the switch's value"
    );
    assert!(!sonos.contains(">Stua<"));

    // None on: none allowed.
    save_rooms(&app, &cookie, device, &[]).await;
    assert_eq!(stored_rooms(&app, device).await.as_deref(), Some("[]"));
    assert_eq!(library().await["sonos_rooms"], json!([]));
    let page = music_page(&app, &cookie, device).await;
    assert!(sonos_group(&page).contains("No room on: music plays only on the phone"));

    // All rooms again: NULL, and the library as it was.
    save_rooms(&app, &cookie, device, &[("all", "on"), ("room", KJOKKEN)]).await;
    assert_eq!(stored_rooms(&app, device).await, None);
    let again = library().await;
    assert_eq!(again["sonos_rooms"], Value::Null);
    assert_eq!(again["version"], all["version"]);

    // Refused: a uid that isn't a speaker's, too many - back to the rooms with the reason
    // (qa-26a-step3c-code L4); an unknown phone. Nothing changes.
    let res = save_rooms(&app, &cookie, device, &[("room", "192.168.1.20")]).await;
    assert_redirect(
        &res,
        &format!("{music}?music_notice=sonos_unknown"),
        Some("music-sonos"),
    );
    let many: Vec<String> = (0..33)
        .map(|i| format!("RINCON_000E58A0B1C2{i:02}400"))
        .collect();
    let fields: Vec<(&str, &str)> = many.iter().map(|uid| ("room", uid.as_str())).collect();
    let res = save_rooms(&app, &cookie, device, &fields).await;
    assert_redirect(
        &res,
        &format!("{music}?music_notice=sonos_too_many"),
        Some("music-sonos"),
    );
    assert_eq!(stored_rooms(&app, device).await, None);
    let page = app
        .get_page(&format!("{music}?music_notice=sonos_too_many"), &cookie)
        .await
        .text();
    assert!(sonos_group(&page).contains("Not saved: at most 32 speakers can be on."));
    assert_eq!(
        page.matches("Not saved: at most 32").count(),
        1,
        "by the rooms, nowhere else"
    );
    assert_eq!(
        save_rooms(&app, &cookie, 999, &[("all", "on")])
            .await
            .status,
        StatusCode::NOT_FOUND
    );

    assert_eq!(
        events(&app, "music_sonos_rooms_changed").await,
        vec![
            format!("device {device}: only Stua, Kjøkken <b>"),
            format!("device {device}: only Stua"),
            format!("device {device}: no room"),
            format!("device {device}: all rooms"),
        ]
    );

    // 32 long names: the event keeps a short detail (qa-26a-step3c-code L5).
    let long: Vec<(String, String)> = (0..32)
        .map(|i| {
            (
                format!("RINCON_000E58A0B1C2{i:02}400"),
                format!("{i:02} {}", "x".repeat(37)),
            )
        })
        .collect();
    let rooms: Vec<(&str, &str)> = long.iter().map(|(u, n)| (u.as_str(), n.as_str())).collect();
    report_rooms(&app, &token, &rooms).await;
    let fields: Vec<(&str, &str)> = long.iter().map(|(u, _)| ("room", u.as_str())).collect();
    save_rooms(&app, &cookie, device, &fields).await;
    assert_eq!(library().await["sonos_rooms"].as_array().unwrap().len(), 32);
    let detail = events(&app, "music_sonos_rooms_changed")
        .await
        .pop()
        .unwrap();
    assert!(detail.starts_with(&format!("device {device}: only 00 xxx")));
    assert_eq!(detail.chars().count(), 300);
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
                    "version_code": 10_020_039,
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
    let page = music_page(&app, &cookie, device).await;
    // What the phone reported, as rows (§4.2: the version, never the package or the version code
    // in visible text - those are behind the row's ⓘ).
    assert_eq!(row_value(&page, "app").as_deref(), Some("1.2.3"));
    assert!(page.contains("me.vibb.music, version code 10020039."));
    assert_eq!(row_value(&page, "library").as_deref(), Some("Up to date"));
    assert_eq!(row_value(&page, "waiting").as_deref(), Some("2"));
    assert_eq!(row_value(&page, "stored").as_deref(), Some("12.3 MB"));
    assert_eq!(row_value(&page, "storytel").as_deref(), Some("None"));
    // An entry's error by its own row.
    let row = tick_form_of(&page, entry);
    assert!(
        row.contains("On the phone: the source answered 503"),
        "{row}"
    );
    // The library's size only behind the row's ⓘ (qa-31-s7-code L3).
    assert!(page.contains("This phone&#39;s library: 1 entry, "));
    // ... and the error goes with the switch: off, it no longer shows (N3).
    tick(&app, &cookie, device, entry, false).await;
    let off = music_page(&app, &cookie, device).await;
    assert!(!tick_form_of(&off, entry).contains("On the phone:"));
    tick(&app, &cookie, device, entry, true).await;
    // The report's age under the group, in Phone Setup's zone, the exact local time in its
    // `title` (qa-31-s7-code L2): yesterday 10:00 UTC is 12:00 in Oslo in October.
    sqlx::query("UPDATE provisioning_settings SET time_zone = 'Europe/Oslo'")
        .execute(&app.db)
        .await
        .unwrap();
    sqlx::query(
        "UPDATE device_status SET reported_at = datetime('now', '-1 day', 'start of day', \
         '+10 hours') WHERE device_id = ?",
    )
    .bind(device)
    .execute(&app.db)
    .await
    .unwrap();
    let reported_at = (chrono::Utc::now() - chrono::Duration::days(1))
        .date_naive()
        .and_hms_opt(10, 0, 0)
        .unwrap()
        .and_utc();
    let oslo = crate::display_time::Zone::from_setting("Europe/Oslo");
    let page = music_page(&app, &cookie, device).await;
    let on_phone = &page[page.find("id=\"on-phone\"").unwrap()..];
    let age = oslo.relative(reported_at, chrono::Utc::now());
    assert!(!age.contains("10:00"), "{age}");
    assert!(
        on_phone.contains(&format!(
            "At the last report, <span title=\"{}\">{age}</span>.",
            oslo.exact(reported_at)
        )),
        "{on_phone}"
    );
    assert!(oslo.exact(reported_at).contains("12:00:00"));

    // The phone's library is older, or not there yet.
    for (applied, value) in [
        (Some("0123456789abcdef"), "Update waiting"),
        (None, "Not there yet"),
    ] {
        report_music_state(
            &app,
            &token,
            json!({"package": "me.vibb.music", "bridge": "bound", "library_version": applied}),
        )
        .await;
        let page = music_page(&app, &cookie, device).await;
        assert_eq!(row_value(&page, "library").as_deref(), Some(value));
    }
    // The bridge down: a warning at the top, its state never shown raw.
    report_music_state(
        &app,
        &token,
        json!({"package": "me.vibb.music", "bridge": "not_bound", "storytel": "login_failed"}),
    )
    .await;
    let page = music_page(&app, &cookie, device).await;
    assert!(
        page.find("Music app not reachable").expect("the warning")
            < page.find("id=\"music-app\"").unwrap()
    );
    assert!(!page.contains("not bound") && !page.contains("not_bound"));
    // A Storytel state the music app doesn't send shows no row, never its code (N1).
    assert_eq!(row_value(&page, "storytel"), None);
    assert!(!page.contains("login failed") && !page.contains("login_failed"));

    // A launcher with the bridge but no music app: it sends no music_state (qa-21-step3-code L13).
    let report_without_state = |apps: serde_json::Value| json!({"lock_reason": "NONE", "kiosk_engaged": true, "capabilities": ["music_v1"], "installed_apps": apps});
    app.request(
        Method::POST,
        "/api/devices/status",
        Some(&token),
        Some(report_without_state(
            json!([{"package_name": "com.example.game", "label": "Game"}]),
        )),
    )
    .await;
    let page = music_page(&app, &cookie, device).await;
    assert_eq!(row_value(&page, "app").as_deref(), Some("Not installed"));
    app.request(
        Method::POST,
        "/api/devices/status",
        Some(&token),
        Some(report_without_state(
            json!([{"package_name": "me.vibb.music", "label": "Musikk"}]),
        )),
    )
    .await;
    let page = music_page(&app, &cookie, device).await;
    assert_eq!(row_value(&page, "app").as_deref(), Some("No report yet"));
    // Before the phone's music report, no library row: nothing is on the phone yet, and its size
    // is no value for a parent (qa-31-s7-code L3).
    assert_eq!(row_value(&page, "library"), None);
    assert!(!page.contains(" kB"));

    // A launcher without music_v1 while entries are on: a warning at the top.
    app.request(
        Method::POST,
        "/api/devices/status",
        Some(&token),
        Some(json!({"lock_reason": "NONE", "kiosk_engaged": true})),
    )
    .await;
    let page = music_page(&app, &cookie, device).await;
    assert!(
        page.find("deliver music yet").expect("the warning")
            < page.find("id=\"music-app\"").unwrap()
    );
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
    assert_eq!(res.location(), Some("/music/settings#storytel"));
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
    for path in ["/music", "/music/settings"] {
        let page = app.get_page(path, &cookie).await.text();
        assert!(
            !page.contains(PASSWORD) && !page.contains(EMAIL),
            "never shown back on {path}"
        );
    }
    let page = app.get_page("/music/settings", &cookie).await.text();
    assert!(page.contains("Saved "));
    assert!(
        app.get_page("/music", &cookie)
            .await
            .text()
            .contains("Signed in")
    );
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
    assert_eq!(res.location(), Some("/music/settings#storytel"));
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
        app.get_page("/music/settings", &cookie)
            .await
            .text()
            .contains("Enter the login again")
    );

    // No key at all: 503, the form is replaced by the explanation, saving is refused.
    let mut state = app.state.clone();
    state.music_key = None;
    let res = crate::handlers::music_api::storytel(
        axum::extract::State(state.clone()),
        axum::Extension(crate::security::AuthedDevice(crate::security::DeviceRef {
            id: device,
        })),
    )
    .await;
    assert_eq!(res.status(), StatusCode::SERVICE_UNAVAILABLE);
    let page = super::read_response(crate::handlers::music::render_settings_plain(&state).await)
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
    assert_eq!(nav_current(&text), "music");
    for needle in [
        "/static/scroll-restore.js",
        "href=\"/apps\"",
        "id=\"entries\"",
        &format!("id=\"entry-{entry}\""),
        &format!("href=\"/music/entries/{entry}\""),
        "/static/music-icons/music_note.svg",
        "id=\"add-link\"",
        "id=\"add-own\"",
        "href=\"/music/storytel\"",
        "href=\"/music/import\"",
        "href=\"/music/categories\"",
        "href=\"/music/settings\"",
    ] {
        assert!(text.contains(needle), "{needle}");
    }
    // The tab's sub-pages (design 31 §3.4): each in the Music tab, with a back link to it and no
    // brand bar.
    for (path, needles) in [
        (
            "/music/settings",
            &["id=\"sweep\"", "id=\"storytel\"", "id=\"app\""][..],
        ),
        (
            "/music/categories",
            &["id=\"categories\"", "id=\"new-category\""][..],
        ),
        ("/music/import", &["id=\"import\"", "name=\"library\""][..]),
    ] {
        let page = app.get_page(path, &cookie).await;
        assert_eq!(page.status, StatusCode::OK, "{path}");
        let text = page.text();
        assert_eq!(nav_current(&text), "music", "{path}");
        assert!(
            text.contains("class=\"nav-back\" href=\"/music\""),
            "{path}"
        );
        assert!(!text.contains("class=\"app-header\""), "{path}");
        for needle in needles {
            assert!(text.contains(needle), "{path}: {needle}");
        }
    }
    let apps = app.get_page("/apps", &cookie).await.text();
    assert_eq!(nav_current(&apps), "apps");
    assert!(apps.contains("href=\"/music\""), "the Music tab");
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
    assert!(text.contains("tag prefix may only hold"));
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
    assert_eq!(
        res.location(),
        Some(format!("/apps/tracked/{id}?open=source#details").as_str())
    );
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
            {"id": "a4", "name": "Lydbok", "target": "storytel:series:123", "order": "auto", "cache": 0, "resume": true},
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
    assert_eq!(nav_current(&page), "music");
    assert!(page.contains("action=\"/music/import/confirm#import-preview\""));
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
        (
            "Spotify playlist · natural order · streamed, on no phone yet · continues where it \
             stopped",
            "spotify (design 26c §3.2: imported, on no phone)",
        ),
        (
            "Storytel series · natural order · every book downloaded · continues where it stopped",
            "storytel (design 26b §2.6: cache 0 is all)",
        ),
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
    assert_eq!(preview_rows(&page), vec!["0.0", "0.1", "0.2", "0.3", "1.0"]);
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
            ("row", "0.3"),
            ("row", "1.0"),
            ("phone", &anna_id),
            ("phone", &emil_id),
        ],
    )
    .await;
    assert_eq!(
        res.location(),
        Some("/music/import#import"),
        "{}",
        res.text()
    );
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
                "Lydbok".into(),
                "storytel".into(),
                Some("storytel:series:123".into()),
                crate::music::state_key("storytel:series:123"),
                "auto".into(),
                -1,
                true,
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
    assert_eq!(ticks, 8, "four entries on two phones");
    assert_eq!(
        music_policy(&app, &anna_token).await["library_version"]
            .as_str()
            .map(str::len),
        Some(16)
    );
    assert_eq!(
        events(&app, "music_library_imported").await,
        vec!["4 entries, 1 categories, 5 skipped".to_string()]
    );

    // The result, once, in the import group.
    let page = app.get_page("/music/import", &cookie).await.text();
    assert!(page.contains("data-flash=\"import\""));
    assert!(
        page.contains("Imported 4 entries and 1 new category, ticked on Anna, Emil. 5 skipped:"),
        "{page}"
    );
    // The Spotify row (design 26c §3.2) was offered and left unticked: not "skipped".
    assert!(!page.contains("podkast / Spilleliste"));
    assert!(page.contains("Eventyr og sånt / Abels tårn (igjen): it is in the file twice"));
    let again = app.get_page("/music/import", &cookie).await.text();
    assert!(!again.contains("Imported 4 entries"), "shown once");
    assert!(!again.contains("data-flash=\"import\""));

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
    assert_eq!(res.location(), Some("/music/import#import"));
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
    let page = app.get_page("/music/import", &cookie).await.text();
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
    assert_eq!(res.location(), Some("/music/import#import"));
    let page = app.get_page("/music/import", &cookie).await.text();
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
async fn an_import_stops_at_max_entries_and_skips_whats_in_the_library() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    sqlx::query(
        "WITH RECURSIVE n(i) AS (SELECT 1 UNION ALL SELECT i + 1 FROM n WHERE i < ?) \
         INSERT INTO music_entries (name, category_id, source, key, sort) \
         SELECT 'E' || i, 1, 'own', 'own-x' || i, i * 10 FROM n",
    )
    .bind(MAX_ENTRIES - 2)
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
    assert!(page.contains(&format!("the library holds at most {MAX_ENTRIES} entries")));
    assert_eq!(preview_rows(&page), vec!["0.1"]);
    let doc = preview_doc(&page);
    // A hand-made form ticking the third row too: re-checked, still the limit.
    let res = import_confirm(
        &app,
        &cookie,
        &[("doc", &doc), ("row", "0.1"), ("row", "0.2")],
    )
    .await;
    assert_eq!(res.location(), Some("/music/import#import"));
    let count: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM music_entries")
        .fetch_one(&app.db)
        .await
        .unwrap();
    assert_eq!(count, MAX_ENTRIES);
    let names: Vec<String> =
        sqlx::query_scalar("SELECT name FROM music_entries WHERE name IN ('One', 'Two')")
            .fetch_all(&app.db)
            .await
            .unwrap();
    assert_eq!(names, vec!["One"], "rows are taken in file order");
    let page = app.get_page("/music/import", &cookie).await.text();
    assert!(page.contains(&format!(
        "Podkast / Two: the library holds at most {MAX_ENTRIES} entries"
    )));
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
    assert_eq!(res.location(), Some("/music/import#import"));
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
    let page = app.get_page("/music/import", &cookie).await.text();
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

    // A document edited to carry a Spotify short link, a folder and MAX_ENTRIES + 1 entries: only what a fresh
    // upload would allow gets in.
    let mut entries = vec![
        json!({"name": "Spill", "target": "https://spotify.link/abc"}),
        json!({"name": "Mappe", "target": "/srv/x"}),
    ];
    entries.extend((0..MAX_ENTRIES + 1).map(
        |i| json!({"name": format!("F{i}"), "target": format!("https://example.org/{i}.rss")}),
    ));
    let tampered = json!({"sections": [{"name": "Podkast", "entries": entries}]}).to_string();
    let rows: Vec<String> = (0..MAX_ENTRIES + 3).map(|i| format!("0.{i}")).collect();
    let mut fields: Vec<(&str, &str)> = vec![("doc", tampered.as_str())];
    fields.extend(rows.iter().map(|r| ("row", r.as_str())));
    let phone_id = phone.to_string();
    fields.push(("phone", &phone_id));
    let res = import_confirm(&app, &cookie, &fields).await;
    assert_eq!(res.location(), Some("/music/import#import"));
    let names: Vec<String> = sqlx::query_scalar("SELECT name FROM music_entries ORDER BY sort")
        .fetch_all(&app.db)
        .await
        .unwrap();
    assert_eq!(names.len(), crate::music::MAX_ENTRIES as usize);
    assert_eq!(names.first().map(String::as_str), Some("F0"));
    assert!(
        !names
            .iter()
            .any(|n| n == "Spill" || n == "Mappe" || n == &format!("F{MAX_ENTRIES}"))
    );
    let page = app.get_page("/music/import", &cookie).await.text();
    assert!(page.contains(
        "Podkast / Spill: a Spotify short link - resolve it by adding it on the Spotify page"
    ));
    assert!(page.contains(&format!(
        "Podkast / F{MAX_ENTRIES}: the library holds at most {MAX_ENTRIES} entries"
    )));
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
        assert!(text.contains("aria-describedby=\"import-error\""));
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
    // The phone's Music page uses the same kept library.
    let start = builds();
    app.get_page(&device_page(device, Section::Music), &cookie)
        .await;
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
    assert_eq!(res.location(), Some("/music/import#import"));
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
    let page = app.get_page("/music/settings", &cookie).await.text();
    assert!(page.contains("data-row=\"orphans\""));
    assert!(page.contains("2 files</span>"), "{page}");
    assert!(page.contains("2.0 MB"), "{page}");
    // Behind a confirm (design 31 §5.6): the delete form sits inside its <details>.
    let confirm = &page[page.find("id=\"orphans-delete\"").unwrap()..];
    let confirm = &confirm[..confirm.find("</details>").unwrap()];
    assert!(confirm.contains("action=\"/music/orphans/delete#orphans\""));

    let res = app
        .request_form(Method::POST, "/music/orphans/delete", Some(&cookie), &[])
        .await;
    assert_eq!(res.location(), Some("/music/settings#orphans"));
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
    // The group is gone, its anchor stays: the redirect still lands where the parent was
    // (qa-browser-fixes-code L1).
    let page = app.get_page("/music/settings", &cookie).await.text();
    assert!(!page.contains("data-row=\"orphans\""));
    assert!(page.contains("id=\"orphans\""));
}

// ------------------------------------------------------------------------------------------------
// The Music tab (design 31 step 12): where every form returns, the confirms, Edit, On phones
// ------------------------------------------------------------------------------------------------

/// An attribute's value as the browser sends it.
fn unescape(value: &str) -> String {
    value
        .replace("&#34;", "\"")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&#60;", "<")
        .replace("&lt;", "<")
        .replace("&#62;", ">")
        .replace("&gt;", ">")
        .replace("&#38;", "&")
        .replace("&amp;", "&")
}

/// A form's fields as the browser sends them.
type Fields = Vec<(String, String)>;

/// Every POST form on `html` with its hidden fields, in page order.
fn post_forms(html: &str) -> Vec<(String, Fields)> {
    super::tags(html, "form")
        .into_iter()
        .filter(|form| form.attr("method") == Some("post"))
        .map(|form| {
            let body = &html[form.start..];
            let body = &body[..body.find("</form>").unwrap_or(body.len())];
            let hidden = super::tags(body, "input")
                .into_iter()
                .filter(|input| input.attr("type") == Some("hidden"))
                .map(|input| {
                    (
                        input.attr("name").unwrap_or("").to_string(),
                        unescape(input.attr("value").unwrap_or("")),
                    )
                })
                .collect();
            (form.attr("action").unwrap().replace("&amp;", "&"), hidden)
        })
        .collect()
}

/// What every form on a music page must have, with or without JS (design 31 §5.1, §5.2): the
/// shared check (`settings_pages::check_forms`).
#[track_caller]
fn check_forms(page: &str, html: &str) {
    super::settings_pages::check_forms(page, html);
}

/// A stray own file no entry names, old enough to be listed (`music::orphan_files`).
fn stray_file(app: &TestApp) {
    let stray = app.state.music_files_dir.join("999").join("gone.mp3");
    std::fs::create_dir_all(stray.parent().unwrap()).unwrap();
    std::fs::write(&stray, vec![0u8; 1000]).unwrap();
    std::fs::File::options()
        .write(true)
        .open(&stray)
        .unwrap()
        .set_modified(std::time::SystemTime::now() - std::time::Duration::from_secs(7200))
        .unwrap();
}

/// §5.1's redirect table for the Music tab: every form the tab and its pages render is posted
/// with valid values, and each lands back on the page it is on, at its row or group (§5.1.4:
/// without JS on the form, with JS scroll-restore keeps the place over it). A form the table has
/// no row for fails the test.
#[tokio::test]
async fn every_form_on_the_music_pages_returns_to_its_page() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (phone, _) = app.enrolled_device("Anna").await;
    let first = add_nrk(&app, &cookie, "forste", "Første").await;
    add_nrk(&app, &cookie, "andre", "Andre").await;
    tick(&app, &cookie, phone, first, true).await;
    let own = add_own(&app, &cookie, "Bilturen").await;
    let res = upload(
        &app,
        &cookie,
        own,
        &[("a.wav", wav(300)), ("b.wav", wav(400))],
    )
    .await;
    assert!(res.status.is_redirection(), "{}", res.text());
    let res = upload_cover(&app, &cookie, own, &png(64, 64)).await;
    assert!(res.status.is_redirection(), "{}", res.text());
    stray_file(&app);
    // A saved Storytel login (Storytel can't be reached here: saved, unchecked).
    let login = [
        ("email", "parent@example.invalid"),
        ("password", "not-a-real-password"),
    ];
    let res = app
        .request_form(Method::POST, "/music/storytel", Some(&cookie), &login)
        .await;
    assert_redirect(&res, "/music/settings", Some("storytel"));
    let preview = import_preview(&app, &cookie, vibb_library().to_string().as_bytes())
        .await
        .text();
    // Spotify (design 26c; qa-26c-merge2-code M3, L6): an app with the loopback redirect (so the
    // paste shows), a child's login, a listed playlist's entry page, and the album an add takes.
    use super::music_spotify::listing as spotify;
    spotify::ready(&app).await;
    let playlist = spotify::entry(&app, "playlist", spotify::PLAYLIST).await;
    app.fetch.answer(
        &spotify::url(&app, crate::spotify::Read::Album(spotify::ALBUM_X)),
        200,
        crate::spotify::tests::fixture("album.json"),
    );

    let mut forms: Vec<(String, String, Fields)> = Vec::new();
    for page in [
        "/music".to_string(),
        "/music?edit=1".to_string(),
        "/music/settings".to_string(),
        "/music/categories".to_string(),
        "/music/import".to_string(),
        format!("/music/entries/{first}"),
        format!("/music/entries/{own}"),
        format!("/music/entries/{playlist}"),
        "/music/spotify".to_string(),
    ] {
        let html = app.get_page(&page, &cookie).await.text();
        check_forms(&page, &html);
        for (action, hidden) in post_forms(&html) {
            forms.push((page.clone(), action, hidden));
        }
    }
    // The import's preview is the answer to its POST.
    check_forms("the import preview", &preview);
    for (action, hidden) in post_forms(&preview) {
        forms.push(("/music/import".to_string(), action, hidden));
    }
    // Deletes last: they take what the other forms need.
    forms.sort_by_key(|(_, action, _)| {
        let target = action.split('#').next().unwrap_or_default();
        // A clear takes the most (the Spotify app's takes its logins too): after every delete.
        let clear = target.ends_with("/clear");
        (
            clear || target.contains("delete") || target.contains("/accounts/"),
            clear,
        )
    });
    let mut seen = std::collections::HashSet::new();
    let mut added = 0;
    for (page, action, hidden) in forms {
        let (target, action_fragment) = match action.split_once('#') {
            Some((target, fragment)) => (target.to_string(), Some(fragment.to_string())),
            None => (action.clone(), None),
        };
        // "/music/entries/7/files/3/delete" -> (Some(7), "/files/3/delete").
        let id_and_rest = |prefix: &str| -> Option<(i64, String)> {
            let rest = target.strip_prefix(prefix)?;
            let (id, rest) = rest.split_at(rest.find('/').unwrap_or(rest.len()));
            Some((id.parse().ok()?, rest.to_string()))
        };
        let entry = id_and_rest("/music/entries/");
        let category = id_and_rest("/music/categories/");
        let account = id_and_rest("/music/spotify/accounts/");
        let storytel_phone = id_and_rest("/music/storytel/phones/");
        let kind = match (&entry, &category, &account) {
            _ if storytel_phone.is_some() => "storytel-phone".to_string(),
            (Some((_, rest)), _, _) if rest.starts_with("/files/") => {
                "entry/file-delete".to_string()
            }
            (Some((_, rest)), _, _) => format!("entry{rest}"),
            (_, Some((_, rest)), _) => format!("category{rest}"),
            (_, _, Some((_, rest))) => format!("spotify-account{rest}"),
            _ => target.clone(),
        };
        seen.insert(kind.clone());
        // A form whose refusal renders in the POST's answer posts to its own group, so the
        // parent lands where they were (pinned per kind; `check_forms` checks the ids exist).
        let refusal_lands_at = match kind.as_str() {
            "/music" => Some("add-link".to_string()),
            "/music/own" => Some("add-own".to_string()),
            "/music/storytel" => Some("storytel".to_string()),
            "/music/categories" => Some("new-category".to_string()),
            "category" => category.as_ref().map(|(id, _)| format!("category-{id}")),
            "/music/import" | "/music/import/confirm" => Some("import-preview".to_string()),
            "entry" => Some("settings".to_string()),
            "entry/cover" => Some("cover".to_string()),
            "entry/files" => Some("files".to_string()),
            // Design 47: the setup's forms are on Music › Settings, every id `spotify-` prefixed.
            "/music/spotify/app" => Some("spotify-app".to_string()),
            "/music/spotify/app/clear" => Some("spotify-app-clear".to_string()),
            "/music/spotify/paste" => Some("spotify-paste".to_string()),
            "/music/spotify/add" => Some("add".to_string()),
            "spotify-account/remove" | "spotify-account/plays-on" => account
                .as_ref()
                .map(|(id, _)| format!("spotify-account-{id}")),
            "storytel-phone" => Some("storytel-phones".to_string()),
            _ => None,
        };
        if refusal_lands_at.is_some() {
            assert_eq!(action_fragment, refusal_lands_at, "{action} on {page}");
        }
        // The login's form has no fragment (it would ride along on the 303 to Spotify): its
        // refusal focuses its error instead (`settings_group::the_refusals_render_music_settings_
        // with_prefixed_ids`, qa-r5-b-code M1), its success goes off this server (below).
        if kind == "/music/spotify/login" {
            assert_eq!(action_fragment, None, "{action} on {page}");
        }
        let entry_page = entry.as_ref().map(|(id, _)| format!("/music/entries/{id}"));
        let entry_anchor = entry.as_ref().map(|(id, _)| format!("entry-{id}"));
        let category_anchor = category.as_ref().map(|(id, _)| format!("category-{id}"));
        let mut fields: Fields = hidden;
        let mut add = |pairs: &[(&str, &str)]| {
            fields.extend(pairs.iter().map(|(k, v)| (k.to_string(), v.to_string())));
        };
        // Where it must land: the path (and query) and the fragment; "*" ends a prefix.
        let (path, fragment): (String, Option<String>) = match kind.as_str() {
            "/music" => {
                // A new podcast each time (the tab renders the form twice, once in Edit).
                added += 1;
                let slug = format!("ny{added}");
                app.fetch.answer(
                    &psapi("podcast", &slug),
                    200,
                    json!({"series": {"titles": {"title": slug}}}).to_string(),
                );
                let link = format!("https://radio.nrk.no/podkast/{slug}");
                fields.push(("link".into(), link));
                ("/music".into(), Some("entry-*".into()))
            }
            "/music/own" => {
                add(&[("name", "Nye filer")]);
                ("/music/entries/*".into(), Some("files".into()))
            }
            "entry/check" if page.starts_with("/music/entries/") => {
                (entry_page.unwrap(), Some("status".into()))
            }
            "entry/check" | "entry/offline" => {
                add(&[("cache", "5")]);
                ("/music".into(), entry_anchor)
            }
            "entry/move" => ("/music?edit=1".into(), entry_anchor),
            "entry" => {
                add(&[
                    ("name", "Nytt navn"),
                    ("category", "1"),
                    ("order", "auto"),
                    ("cache", "5"),
                    ("resume", "on"),
                ]);
                (entry_page.unwrap(), Some("settings".into()))
            }
            "entry/cover" | "entry/cover/remove" => (entry_page.unwrap(), Some("cover".into())),
            "entry/files" => (entry_page.unwrap(), Some("files".into())),
            "entry/file-delete" => (entry_page.unwrap(), Some("file*".into())),
            "entry/delete" => ("/music".into(), Some("entries".into())),
            "/music/sweep" => {
                add(&[("hours", "6")]);
                ("/music/settings".into(), Some("sweep".into()))
            }
            "/music/storytel" => {
                add(&login);
                ("/music/settings".into(), Some("storytel".into()))
            }
            "/music/storytel/clear" => ("/music/settings".into(), Some("storytel".into())),
            "/music/catalog-row" => ("/music/settings".into(), Some("app".into())),
            "/music/orphans/delete" => ("/music/settings".into(), Some("orphans".into())),
            "/music/categories" => {
                add(&[("name", "Sport"), ("icon", "star"), ("color", "teal")]);
                ("/music/categories?open=*".into(), Some("category-*".into()))
            }
            "category" => {
                add(&[("name", "Ny"), ("icon", "star"), ("color", "teal")]);
                (
                    format!("/music/categories?open={}", category.as_ref().unwrap().0),
                    category_anchor,
                )
            }
            "category/move" => (
                format!("/music/categories?open={}", category.as_ref().unwrap().0),
                category_anchor,
            ),
            "category/delete" => ("/music/categories".into(), Some("categories".into())),
            "/music/import" => ("(the page)".into(), None),
            "/music/import/confirm" => ("/music/import".into(), Some("import".into())),
            "/music/spotify/app" => {
                add(&[
                    ("client_id", crate::spotify::tests::CLIENT),
                    ("redirect_uri", crate::spotify::LOOPBACK_REDIRECT),
                ]);
                ("/music/settings?saved=*".into(), Some("spotify-app".into()))
            }
            "/music/spotify/app/clear" => {
                add(&[("confirm", "on")]);
                (
                    "/music/settings?cleared=1".into(),
                    Some("spotify-app".into()),
                )
            }
            // The login goes to Spotify (a 303 off this server): no fragment to carry along.
            "/music/spotify/login" => ("https://accounts.spotify.com/authorize?*".into(), None),
            "/music/spotify/paste" => {
                // A cancelled login nobody started: the outcome lands in the accounts group.
                let url = format!(
                    "{}?error=access_denied&state=0123456789abcdef0123456789abcdef",
                    crate::spotify::LOOPBACK_REDIRECT
                );
                fields.push(("url".into(), url));
                (
                    "/music/settings?login=*".into(),
                    Some("spotify-accounts".into()),
                )
            }
            "spotify-account/remove" => {
                add(&[("confirm", "on")]);
                (
                    "/music/settings?removed=1".into(),
                    Some("spotify-accounts".into()),
                )
            }
            "spotify-account/plays-on" => {
                fields.push(("device".into(), phone.to_string()));
                (
                    "/music/settings".into(),
                    account
                        .as_ref()
                        .map(|(id, _)| format!("spotify-account-{id}")),
                )
            }
            "storytel-phone" => {
                add(&[("on", "on")]);
                (
                    "/music/settings?storytel=phones".into(),
                    Some("storytel-phones".into()),
                )
            }
            "/music/spotify/add" => {
                add(&[(
                    "link",
                    &format!("https://open.spotify.com/album/{}", spotify::ALBUM_X),
                )]);
                ("/music/spotify?added=*".into(), Some("added".into()))
            }
            other => panic!("a form without a row in the redirect table: {other} on {page}"),
        };
        let id = entry.as_ref().map_or(0, |(id, _)| *id);
        let res = match kind.as_str() {
            "entry/cover" => upload_cover(&app, &cookie, id, &png(64, 64)).await,
            "entry/files" => upload(&app, &cookie, id, &[("c.wav", wav(500))]).await,
            "/music/import" => {
                import_preview(&app, &cookie, vibb_library().to_string().as_bytes()).await
            }
            _ => {
                let pairs: Vec<(&str, &str)> = fields
                    .iter()
                    .map(|(k, v)| (k.as_str(), v.as_str()))
                    .collect();
                app.request_form(Method::POST, &target, Some(&cookie), &pairs)
                    .await
            }
        };
        if kind == "/music/import" {
            // The preview is the import page itself (design 31 §3.4), in the Music tab.
            assert_eq!(res.status, StatusCode::OK, "{action}");
            assert_eq!(nav_current(&res.text()), "music");
            assert!(res.text().contains("id=\"import-preview\""));
            continue;
        }
        assert!(
            res.status.is_redirection(),
            "{action} on {page}: {} {}",
            res.status,
            res.text()
        );
        let location = res.location().unwrap();
        let (got_path, got_fragment) = match location.split_once('#') {
            Some((p, f)) => (p, Some(f)),
            None => (location, None),
        };
        let matches = |want: &str, got: &str| match want.strip_suffix('*') {
            Some(prefix) => got.starts_with(prefix),
            None => want == got,
        };
        assert!(matches(&path, got_path), "{action} on {page}: {location}");
        match (&fragment, got_fragment) {
            (Some(want), Some(got)) => assert!(matches(want, got), "{action}: {location}"),
            // A browser keeps the action's fragment over a redirect without one: such a form
            // must have none, so scroll-restore keeps the place.
            (None, None) => assert_eq!(action_fragment, None, "{action}"),
            _ => panic!("{action} on {page}: {location}"),
        }
        app.assert_lands(&res, &cookie).await;
    }
    for kind in [
        "/music",
        "/music/own",
        "entry/check",
        "entry/offline",
        "entry/move",
        "entry",
        "entry/cover",
        "entry/cover/remove",
        "entry/files",
        "entry/file-delete",
        "entry/delete",
        "/music/sweep",
        "/music/storytel",
        "/music/storytel/clear",
        "/music/orphans/delete",
        "/music/categories",
        "category",
        "category/move",
        "category/delete",
        "/music/import",
        "/music/import/confirm",
        "/music/spotify/app",
        "/music/spotify/app/clear",
        "/music/spotify/login",
        "/music/spotify/paste",
        "spotify-account/remove",
        "spotify-account/plays-on",
        "storytel-phone",
        "/music/spotify/add",
    ] {
        assert!(seen.contains(kind), "no form posted to {kind}");
    }
}

/// Every delete on the Music pages is a `<details>` confirm (design 31 §5.6, Q7): its form sits
/// inside the `<details>`, after the summary, so nothing is deleted without opening it - with or
/// without JS - and no page calls `confirm()` any more.
#[tokio::test]
async fn the_music_deletes_are_behind_a_confirm() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let own = add_own(&app, &cookie, "Bilturen").await;
    let res = upload(&app, &cookie, own, &[("a.wav", wav(300))]).await;
    assert!(res.status.is_redirection());
    stray_file(&app);
    let login = [
        ("email", "parent@example.invalid"),
        ("password", "not-a-real-password"),
    ];
    app.request_form(Method::POST, "/music/storytel", Some(&cookie), &login)
        .await;
    let mut deletes = 0;
    for page in [
        "/music".to_string(),
        "/music/settings".to_string(),
        "/music/categories".to_string(),
        format!("/music/entries/{own}"),
    ] {
        let html = app.get_page(&page, &cookie).await.text();
        assert!(
            !html.contains("confirm("),
            "{page} still asks with confirm()"
        );
        for (action, _) in post_forms(&html) {
            if !(action.contains("delete") || action.contains("/clear#")) {
                continue;
            }
            deletes += 1;
            let at = html.find(&format!("action=\"{action}\"")).unwrap();
            let open = html[..at]
                .rfind("<details")
                .expect("no <details> before it");
            let summary_end = html[open..at].find("</summary>");
            assert!(summary_end.is_some(), "{action}: after the summary");
            assert!(
                !html[open..at].contains("</details>"),
                "{action}: inside its <details>"
            );
        }
    }
    // Orphans, the Storytel login, three unused categories, the file, the entry.
    assert_eq!(deletes, 7);
}

/// A file is deleted only through its own entry's URL (qa-31-s12-code L4): another entry's id in
/// the path is a 404 and the file stays, on disk and in the library.
#[tokio::test]
async fn a_file_is_deleted_only_through_its_own_entry() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let own = add_own(&app, &cookie, "Bilturen").await;
    let other = add_own(&app, &cookie, "Annet").await;
    let res = upload(&app, &cookie, own, &[("a.wav", wav(300))]).await;
    assert!(res.status.is_redirection());
    let (file, path): (i64, String) = sqlx::query_as("SELECT id, path FROM music_files")
        .fetch_one(&app.db)
        .await
        .unwrap();
    let res = app
        .request_form(
            Method::POST,
            &format!("/music/entries/{other}/files/{file}/delete"),
            Some(&cookie),
            &[],
        )
        .await;
    assert_eq!(res.status, StatusCode::NOT_FOUND);
    let left: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM music_files WHERE id = ?")
        .bind(file)
        .fetch_one(&app.db)
        .await
        .unwrap();
    assert_eq!(left, 1);
    assert!(app.state.music_files_dir.join(path).exists());
}

/// The library (design 31 §3.4): a row per entry with its count and phones, Check now and the
/// Offline select on the row; ↑ ↓ only in Edit mode; On phones counts each phone's ticks.
#[tokio::test]
async fn the_library_rows_and_edit_mode() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (anna, _) = app.enrolled_device("Anna").await;
    app.create_device("Emil <b>").await;
    let a = add_nrk(&app, &cookie, "abels", "Abels tårn").await;
    let b = add_nrk(&app, &cookie, "ukas", "Ukas <i>nyheter</i>").await;
    tick(&app, &cookie, anna, a, true).await;
    let page = app.get_page("/music", &cookie).await.text();
    assert!(page.contains("NRK podcast · 1 phone</span>"), "{page}");
    assert!(page.contains("NRK podcast · no phones</span>"), "{page}");
    assert!(
        page.contains("Ukas &#60;i&#62;nyheter"),
        "names are escaped"
    );
    assert!(page.contains("Emil &#60;b&#62;"));
    assert!(!page.contains("<i>nyheter") && !page.contains("Emil <b>"));
    assert!(page.contains(&format!("action=\"/music/entries/{a}/check#entry-{a}\"")));
    assert!(autosaves(&page, "cache"));
    assert_eq!(
        form_action_of(&page, "cache"),
        Some(format!("/music/entries/{a}/offline#entry-{a}").as_str())
    );
    assert!(!page.contains("/move\""), "no ↑ ↓ outside Edit");
    assert!(page.contains("href=\"/music?edit=1#entries\""));
    // On phones: the ticks of the whole library, to the phone's music.
    assert!(page.contains(&format!("data-phone=\"{anna}\"")));
    assert!(page.contains("<span class=\"row-value\">1 of 2</span>"));
    assert!(page.contains("<span class=\"row-value\">0 of 2</span>"));
    assert!(page.contains(&format!("href=\"/devices/{anna}/music\"")));

    // A refused add: the tab again, that form open, the link kept and the reason by the field.
    let res = app
        .request_form(
            Method::POST,
            "/music",
            Some(&cookie),
            &[("link", "https://example.invalid/not a link")],
        )
        .await;
    assert_eq!(res.status, StatusCode::BAD_REQUEST);
    let text = res.text();
    assert!(text.contains("id=\"add-link\" open"));
    assert!(!text.contains("id=\"add-own\" open"));
    assert!(text.contains("aria-describedby=\"add_link-error\""));
    assert_eq!(form_action_of(&text, "link"), Some("/music#add-link"));
    let res = app
        .request_form(Method::POST, "/music/own", Some(&cookie), &[("name", " ")])
        .await;
    assert_eq!(res.status, StatusCode::BAD_REQUEST);
    assert!(res.text().contains("id=\"add-own\" open"));

    let edit = app.get_page("/music?edit=1", &cookie).await.text();
    assert!(edit.contains("href=\"/music#entries\""), "Done");
    assert!(!edit.contains("/check\""), "Edit shows only ↑ ↓");
    let moves: Vec<String> = post_forms(&edit)
        .into_iter()
        .filter(|(action, _)| action.contains("/move#"))
        .map(|(action, hidden)| format!("{action} {}", hidden[0].1))
        .collect();
    assert_eq!(
        moves,
        vec![
            format!("/music/entries/{a}/move#entry-{a} down"),
            format!("/music/entries/{b}/move#entry-{b} up"),
        ]
    );
}
