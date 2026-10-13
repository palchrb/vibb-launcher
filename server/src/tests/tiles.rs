//! The Locate map's tiles through the server (design 33): `GET /tiles/{z}/{x}/{y}.png` against a
//! canned upstream - no test touches the network.

use std::collections::VecDeque;
use std::path::Path;
use std::sync::Arc;
use std::sync::Mutex;
use std::sync::atomic::{AtomicUsize, Ordering};
use std::time::{Duration, SystemTime};

use axum::body::Body;
use axum::http::{Request, StatusCode, header};

use super::{LOOPBACK_PEER, TestApp, TestResponse};
use crate::music_net::BoxFut;
use crate::tiles::{
    MAX_TILE_BYTES, TileError, TileFetch, TileKey, TileMeta, Tiles, UpstreamRequest,
    UpstreamResponse, prune_cache,
};

/// The tiles' upstream in the tests: answers queued with [CannedTiles::answer] (the last one
/// stays), every request recorded, an optional delay and an in-flight counter.
#[derive(Default)]
pub struct CannedTiles {
    answers: Mutex<VecDeque<Result<UpstreamResponse, String>>>,
    pub requests: Mutex<Vec<UpstreamRequest>>,
    delay_ms: AtomicUsize,
    in_flight: AtomicUsize,
    pub most_in_flight: AtomicUsize,
}

impl CannedTiles {
    /// Queues the next answer; the last queued one answers every later request too.
    pub fn answer(&self, answer: Result<UpstreamResponse, String>) {
        self.answers.lock().unwrap().push_back(answer);
    }

    pub fn count(&self) -> usize {
        self.requests.lock().unwrap().len()
    }

    pub fn last(&self) -> UpstreamRequest {
        self.requests
            .lock()
            .unwrap()
            .last()
            .cloned()
            .expect("a request")
    }

    fn delay(&self, ms: usize) {
        self.delay_ms.store(ms, Ordering::SeqCst);
    }
}

impl TileFetch for CannedTiles {
    fn fetch<'a>(
        &'a self,
        request: UpstreamRequest,
    ) -> BoxFut<'a, Result<UpstreamResponse, String>> {
        Box::pin(async move {
            self.requests.lock().unwrap().push(request);
            let now = self.in_flight.fetch_add(1, Ordering::SeqCst) + 1;
            self.most_in_flight.fetch_max(now, Ordering::SeqCst);
            let delay = self.delay_ms.load(Ordering::SeqCst);
            if delay > 0 {
                tokio::time::sleep(Duration::from_millis(delay as u64)).await;
            }
            self.in_flight.fetch_sub(1, Ordering::SeqCst);
            let mut answers = self.answers.lock().unwrap();
            if answers.len() > 1 {
                answers.pop_front().unwrap()
            } else {
                answers
                    .front()
                    .cloned()
                    .unwrap_or_else(|| Err("no canned answer".to_string()))
            }
        })
    }
}

/// A body that starts like a PNG.
fn png(marker: &str) -> Vec<u8> {
    let mut body = b"\x89PNG\r\n\x1a\n".to_vec();
    body.extend_from_slice(marker.as_bytes());
    body
}

fn png_answer(body: Vec<u8>, etag: Option<&str>) -> Result<UpstreamResponse, String> {
    Ok(UpstreamResponse {
        status: 200,
        content_type: Some("image/png".to_string()),
        body,
        etag: etag.map(str::to_string),
        last_modified: Some("Mon, 12 Oct 2026 10:00:00 GMT".to_string()),
        cache_control: Some("max-age=3600".to_string()),
        ..Default::default()
    })
}

fn not_modified() -> Result<UpstreamResponse, String> {
    Ok(UpstreamResponse {
        status: 304,
        ..Default::default()
    })
}

fn meta_path(app: &TestApp, z: u32, x: u32, y: u32) -> std::path::PathBuf {
    app.state.tiles.dir.join(format!("{z}/{x}/{y}.json"))
}

fn read_meta(app: &TestApp, z: u32, x: u32, y: u32) -> TileMeta {
    serde_json::from_slice(&std::fs::read(meta_path(app, z, x, y)).unwrap()).unwrap()
}

/// Makes a cached tile 8 days old.
fn make_stale(app: &TestApp, z: u32, x: u32, y: u32) {
    let mut meta = read_meta(app, z, x, y);
    meta.fetched -= 8 * 24 * 60 * 60;
    std::fs::write(meta_path(app, z, x, y), serde_json::to_vec(&meta).unwrap()).unwrap();
}

fn now_secs() -> i64 {
    chrono::Utc::now().timestamp()
}

fn assert_tile(res: &TestResponse, body: &[u8]) {
    assert_eq!(res.status, StatusCode::OK, "{}", res.text());
    assert_eq!(res.body, body);
    let header = |name| {
        res.headers
            .get(name)
            .and_then(|v| v.to_str().ok())
            .map(str::to_string)
    };
    assert_eq!(header(header::CONTENT_TYPE).as_deref(), Some("image/png"));
    assert_eq!(
        header(header::CACHE_CONTROL).as_deref(),
        Some("private, max-age=86400")
    );
    assert_eq!(
        header(header::X_CONTENT_TYPE_OPTIONS).as_deref(),
        Some("nosniff")
    );
}

#[tokio::test]
async fn a_signed_out_request_never_reaches_osm() {
    let app = TestApp::new().await;
    app.tiles.answer(png_answer(png("a"), None));
    for cookie in [None, Some("handy=not-a-session")] {
        let mut request = Request::builder().uri("/tiles/1/0/0.png");
        if let Some(cookie) = cookie {
            request = request.header(header::COOKIE, cookie);
        }
        let res = app
            .send_via(
                &app.router,
                LOOPBACK_PEER,
                request.body(Body::empty()).unwrap(),
            )
            .await;
        assert_eq!(res.status, StatusCode::SEE_OTHER, "{cookie:?}");
        assert_eq!(res.location(), Some("/login"));
    }
    assert_eq!(app.tiles.count(), 0);
    assert!(!app.state.tiles.dir.exists());
}

#[tokio::test]
async fn bad_coordinates_are_404_without_a_fetch() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    app.tiles.answer(png_answer(png("a"), None));
    for path in [
        "/tiles/20/0/0.png",
        "/tiles/1/2/0.png",
        "/tiles/1/0/2.png",
        "/tiles/0/0/1.png",
        "/tiles/1/0/0.jpg",
        "/tiles/1/0/0",
        "/tiles/a/0/0.png",
        "/tiles/1/+1/0.png",
        "/tiles/1/0/-1.png",
        "/tiles/19/524288/0.png",
        "/tiles/1/0",
        "/tiles/1/0/0/0.png",
    ] {
        let res = app.get_page(path, &cookie).await;
        assert_eq!(res.status, StatusCode::NOT_FOUND, "{path}");
    }
    assert_eq!(app.tiles.count(), 0);
}

#[tokio::test]
async fn a_tile_is_fetched_once_and_then_served_from_the_cache() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    app.tiles.answer(png_answer(png("one"), Some("\"v1\"")));

    let res = app.get_page("/tiles/1/0/1.png", &cookie).await;
    assert_tile(&res, &png("one"));
    assert_eq!(
        app.tiles.last(),
        UpstreamRequest {
            tile: TileKey { z: 1, x: 0, y: 1 },
            etag: None,
            last_modified: None,
        }
    );
    assert_eq!(
        std::fs::read(app.state.tiles.dir.join("1/0/1.png")).unwrap(),
        png("one")
    );
    let meta = read_meta(&app, 1, 0, 1);
    assert_eq!(meta.etag.as_deref(), Some("\"v1\""));
    // OSM's max-age of an hour is below the policy's 7 days.
    assert_eq!(meta.fresh_secs, 7 * 24 * 60 * 60);
    // No temp file is left next to it.
    let names: Vec<String> = std::fs::read_dir(app.state.tiles.dir.join("1/0"))
        .unwrap()
        .map(|e| e.unwrap().file_name().to_string_lossy().into_owned())
        .collect();
    assert_eq!(names.len(), 2, "{names:?}");

    // A fresh tile: no request at all, whatever OSM would say now.
    app.tiles.answer(Err("down".to_string()));
    let res = app.get_page("/tiles/1/0/1.png", &cookie).await;
    assert_tile(&res, &png("one"));
    assert_eq!(app.tiles.count(), 1);
}

#[tokio::test]
async fn a_stale_tile_is_revalidated_and_a_304_renews_it() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    app.tiles.answer(png_answer(png("old"), Some("\"v1\"")));
    app.tiles.answer(not_modified());
    assert_tile(
        &app.get_page("/tiles/2/1/3.png", &cookie).await,
        &png("old"),
    );
    make_stale(&app, 2, 1, 3);

    let res = app.get_page("/tiles/2/1/3.png", &cookie).await;
    assert_tile(&res, &png("old"));
    assert_eq!(app.tiles.count(), 2);
    let request = app.tiles.last();
    assert_eq!(request.etag.as_deref(), Some("\"v1\""));
    assert_eq!(
        request.last_modified.as_deref(),
        Some("Mon, 12 Oct 2026 10:00:00 GMT")
    );
    // Renewed: fresh again, validators kept.
    let meta = read_meta(&app, 2, 1, 3);
    assert!(now_secs() - meta.fetched < 60, "{meta:?}");
    assert_eq!(meta.etag.as_deref(), Some("\"v1\""));
    assert_tile(
        &app.get_page("/tiles/2/1/3.png", &cookie).await,
        &png("old"),
    );
    assert_eq!(app.tiles.count(), 2);

    // Stale again, and OSM has a new picture: it replaces the old one.
    make_stale(&app, 2, 1, 3);
    app.tiles.answers.lock().unwrap().clear();
    app.tiles.answer(png_answer(png("new"), Some("\"v2\"")));
    let res = app.get_page("/tiles/2/1/3.png", &cookie).await;
    assert_tile(&res, &png("new"));
    assert_eq!(read_meta(&app, 2, 1, 3).etag.as_deref(), Some("\"v2\""));
    assert_eq!(
        std::fs::read(app.state.tiles.dir.join("2/1/3.png")).unwrap(),
        png("new")
    );
}

#[tokio::test]
async fn osm_down_serves_the_stale_copy_and_nothing_is_a_502() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    app.tiles.answer(png_answer(png("kept"), None));
    app.tiles
        .answer(Err("error sending request: connection refused".to_string()));
    assert_tile(
        &app.get_page("/tiles/3/2/1.png", &cookie).await,
        &png("kept"),
    );
    make_stale(&app, 3, 2, 1);

    let res = app.get_page("/tiles/3/2/1.png", &cookie).await;
    assert_tile(&res, &png("kept"));
    assert_eq!(app.tiles.count(), 2);
    // No validators were given, so none were sent.
    assert_eq!(app.tiles.last().etag, None);
    // Still stale: the next request tries OSM again.
    assert_tile(
        &app.get_page("/tiles/3/2/1.png", &cookie).await,
        &png("kept"),
    );
    assert_eq!(app.tiles.count(), 3);

    // A 5xx is no better.
    app.tiles.answers.lock().unwrap().clear();
    app.tiles.answer(Ok(UpstreamResponse {
        status: 503,
        ..Default::default()
    }));
    assert_tile(
        &app.get_page("/tiles/3/2/1.png", &cookie).await,
        &png("kept"),
    );

    // Without a cached copy there is nothing to serve.
    let res = app.get_page("/tiles/3/0/0.png", &cookie).await;
    assert_eq!(res.status, StatusCode::BAD_GATEWAY);
    assert!(!app.state.tiles.dir.join("3/0/0.png").exists());
}

#[tokio::test]
async fn an_answer_that_isnt_a_png_is_refused() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let answers = [
        UpstreamResponse {
            status: 200,
            content_type: Some("text/html; charset=utf-8".to_string()),
            body: b"<html>Access blocked</html>".to_vec(),
            ..Default::default()
        },
        UpstreamResponse {
            status: 200,
            content_type: None,
            body: png("no type"),
            ..Default::default()
        },
        UpstreamResponse {
            status: 200,
            content_type: Some("image/jpeg".to_string()),
            body: png("jpeg type"),
            ..Default::default()
        },
        UpstreamResponse {
            status: 200,
            content_type: Some("image/png".to_string()),
            body: b"<svg onload=alert(1)>".to_vec(),
            ..Default::default()
        },
        UpstreamResponse {
            status: 403,
            content_type: Some("image/png".to_string()),
            body: png("blocked tile"),
            ..Default::default()
        },
    ];
    for (n, answer) in answers.into_iter().enumerate() {
        app.tiles.answer(Ok(answer));
        let res = app.get_page(&format!("/tiles/4/{n}/0.png"), &cookie).await;
        assert_eq!(res.status, StatusCode::BAD_GATEWAY, "answer {n}");
        assert!(
            !app.state.tiles.dir.join(format!("4/{n}/0.png")).exists(),
            "answer {n} was stored"
        );
        // The next answer replaces this one (the queue keeps the last).
        app.tiles.answers.lock().unwrap().clear();
    }
    // A content type with a parameter, in another case, is still a PNG.
    app.tiles.answer(Ok(UpstreamResponse {
        status: 200,
        content_type: Some("Image/PNG; foo=bar".to_string()),
        body: png("ok"),
        ..Default::default()
    }));
    assert_tile(&app.get_page("/tiles/4/9/0.png", &cookie).await, &png("ok"));
}

#[tokio::test]
async fn a_tile_over_1_mb_is_refused() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let big = |len: usize| {
        let mut body = png("");
        body.resize(len, 0);
        body
    };
    app.tiles.answer(png_answer(big(MAX_TILE_BYTES + 1), None));
    let res = app.get_page("/tiles/5/0/0.png", &cookie).await;
    assert_eq!(res.status, StatusCode::BAD_GATEWAY);
    assert!(!app.state.tiles.dir.join("5/0/0.png").exists());

    // The real fetcher stops reading past the cap and says so.
    app.tiles.answers.lock().unwrap().clear();
    app.tiles.answer(Ok(UpstreamResponse {
        too_large: true,
        ..png_answer(big(1000), None).unwrap()
    }));
    let res = app.get_page("/tiles/5/0/1.png", &cookie).await;
    assert_eq!(res.status, StatusCode::BAD_GATEWAY);

    // Exactly the cap is fine.
    app.tiles.answers.lock().unwrap().clear();
    app.tiles.answer(png_answer(big(MAX_TILE_BYTES), None));
    let res = app.get_page("/tiles/5/0/2.png", &cookie).await;
    assert_tile(&res, &big(MAX_TILE_BYTES));
}

#[tokio::test]
async fn the_phone_listener_has_no_tiles() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    app.tiles.answer(png_answer(png("a"), None));
    let router = app.device_router();
    for path in ["/tiles/1/0/0.png", "/tiles/0/0/0.png"] {
        let request = Request::builder()
            .uri(path)
            .header(header::COOKIE, &cookie)
            .body(Body::empty())
            .unwrap();
        let res = app.send_via(&router, LOOPBACK_PEER, request).await;
        assert_eq!(res.status, StatusCode::NOT_FOUND, "{path}");
        assert!(res.body.is_empty());
    }
    assert_eq!(app.tiles.count(), 0);
}

#[tokio::test]
async fn a_session_over_its_rate_gets_cached_tiles_only() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let canned = Arc::new(CannedTiles::default());
    canned.answer(png_answer(png("a"), None));
    let mut state = app.state.clone();
    state.tiles =
        Arc::new(Tiles::new(app.state.tiles.dir.clone(), canned.clone()).with_rate(2.0, 0.0));
    let router = app.admin_router(state);
    let get = |path: &str| {
        Request::builder()
            .uri(path)
            .header(header::COOKIE, &cookie)
            .body(Body::empty())
            .unwrap()
    };

    for path in ["/tiles/6/0/0.png", "/tiles/6/0/1.png"] {
        let res = app.send_via(&router, LOOPBACK_PEER, get(path)).await;
        assert_tile(&res, &png("a"));
    }
    let res = app
        .send_via(&router, LOOPBACK_PEER, get("/tiles/6/0/2.png"))
        .await;
    assert_eq!(res.status, StatusCode::TOO_MANY_REQUESTS);
    assert_eq!(canned.count(), 2);
    // Cache hits don't count.
    let res = app
        .send_via(&router, LOOPBACK_PEER, get("/tiles/6/0/0.png"))
        .await;
    assert_tile(&res, &png("a"));
    // A stale one is served as it is, without asking OSM.
    make_stale(&app, 6, 0, 1);
    let res = app
        .send_via(&router, LOOPBACK_PEER, get("/tiles/6/0/1.png"))
        .await;
    assert_tile(&res, &png("a"));
    assert_eq!(canned.count(), 2);
}

#[tokio::test]
async fn requests_for_one_tile_fetch_it_once_and_osm_sees_two_at_most() {
    let dir = tempfile::tempdir().unwrap();
    let canned = Arc::new(CannedTiles::default());
    canned.answer(png_answer(png("a"), None));
    canned.delay(50);
    let tiles = Arc::new(Tiles::new(dir.path().to_path_buf(), canned.clone()));

    let mut set = tokio::task::JoinSet::new();
    for _ in 0..6 {
        let tiles = tiles.clone();
        set.spawn(async move { tiles.get(TileKey { z: 7, x: 1, y: 1 }, "s").await });
    }
    while let Some(result) = set.join_next().await {
        assert_eq!(result.unwrap(), Ok(png("a")));
    }
    assert_eq!(canned.count(), 1);

    for y in 0..8 {
        let tiles = tiles.clone();
        set.spawn(async move { tiles.get(TileKey { z: 7, x: 2, y }, "s").await });
    }
    while let Some(result) = set.join_next().await {
        assert_eq!(result.unwrap(), Ok(png("a")));
    }
    assert_eq!(canned.count(), 9);
    assert_eq!(canned.most_in_flight.load(Ordering::SeqCst), 2);
}

#[tokio::test]
async fn without_any_answer_the_error_says_so() {
    let dir = tempfile::tempdir().unwrap();
    let tiles = Tiles::new(dir.path().to_path_buf(), Arc::new(CannedTiles::default()));
    assert!(matches!(
        tiles.get(TileKey { z: 0, x: 0, y: 0 }, "s").await,
        Err(TileError::Upstream(_))
    ));
}

/// Writes a cached tile of `size` bytes whose sidecar was last written `age` ago.
fn write_tile(dir: &Path, z: u32, x: u32, y: u32, size: usize, age: Duration) {
    let x_dir = dir.join(format!("{z}/{x}"));
    std::fs::create_dir_all(&x_dir).unwrap();
    std::fs::write(x_dir.join(format!("{y}.png")), vec![0u8; size]).unwrap();
    let meta = x_dir.join(format!("{y}.json"));
    std::fs::write(&meta, b"{}").unwrap();
    let file = std::fs::File::options().write(true).open(&meta).unwrap();
    file.set_modified(SystemTime::now() - age).unwrap();
}

#[test]
fn the_cache_bound_prunes_the_least_recently_fetched_tiles() {
    let dir = tempfile::tempdir().unwrap();
    let day = Duration::from_secs(24 * 60 * 60);
    // Five tiles of 1002 bytes each (1000 + a 2-byte sidecar), the oldest first.
    write_tile(dir.path(), 10, 1, 1, 1000, day * 9);
    write_tile(dir.path(), 10, 1, 2, 1000, day * 3);
    write_tile(dir.path(), 11, 5, 5, 1000, day * 8);
    write_tile(dir.path(), 12, 7, 7, 1000, day);
    write_tile(dir.path(), 12, 7, 8, 1000, Duration::ZERO);
    // A sidecar without its tile, an old temp file and a fresh one (a write in progress).
    std::fs::write(dir.path().join("10/1/9.json"), b"{}").unwrap();
    let old_temp = dir.path().join("10/1/.3.png.1-1.tmp");
    std::fs::write(&old_temp, b"x").unwrap();
    std::fs::File::options()
        .write(true)
        .open(&old_temp)
        .unwrap()
        .set_modified(SystemTime::now() - Duration::from_secs(2 * 60 * 60))
        .unwrap();
    let new_temp = dir.path().join("10/1/.4.png.1-2.tmp");
    std::fs::write(&new_temp, b"x").unwrap();

    // Within the bound: only the leftovers go.
    assert_eq!(prune_cache(dir.path(), 10_000).unwrap(), Default::default());
    assert!(!dir.path().join("10/1/9.json").exists());
    assert!(!old_temp.exists());
    assert!(new_temp.exists());

    // Room for two: the three least recently fetched go.
    let pruned = prune_cache(dir.path(), 2 * 1002).unwrap();
    assert_eq!(pruned.tiles, 3);
    assert_eq!(pruned.bytes, 3 * 1002);
    for (path, kept) in [
        ("10/1/1", false),
        ("11/5/5", false),
        ("10/1/2", false),
        ("12/7/7", true),
        ("12/7/8", true),
    ] {
        assert_eq!(
            dir.path().join(format!("{path}.png")).exists(),
            kept,
            "{path}"
        );
        assert_eq!(
            dir.path().join(format!("{path}.json")).exists(),
            kept,
            "{path}"
        );
    }

    // No cache yet is nothing to do.
    assert_eq!(
        prune_cache(&dir.path().join("missing"), 0).unwrap(),
        Default::default()
    );
}

#[tokio::test]
async fn the_locate_page_asks_the_server_for_tiles() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (device, _) = app.create_device("Ella").await;
    let page = app
        .get_page(&format!("/devices/locate?device={device}"), &cookie)
        .await;
    assert_eq!(page.status, StatusCode::OK);
    let html = page.text();
    assert!(
        html.contains("L.tileLayer('/tiles/{z}/{x}/{y}.png'"),
        "{html}"
    );
    assert!(!html.contains("tile.openstreetmap.org"));
    // The attribution stays.
    assert!(html.contains("https://www.openstreetmap.org/copyright"));
}
