//! The Locate page's map tiles, fetched and cached by the server (design
//! `docs/design/33-osm-tile-proxy.md`): OSM sees only the server - no parent browser, no tailnet
//! hostname, no `Referer` - and the server follows OSM's tile usage policy
//! (<https://operations.osmfoundation.org/policies/tiles/>): an identifying `User-Agent`, at least
//! 7 days of caching, conditional revalidation, at most 2 fetches at a time and no bulk
//! downloading (a per-session rate).
//!
//! - `GET /tiles/{z}/{x}/{y}.png` ([serve]) is an admin route behind the session; never on the
//!   phone listener.
//! - The cache is `data/tiles/{z}/{x}/{y}.png` with a `{y}.json` sidecar ([TileMeta]), both written
//!   by a temp file and a rename; [prune_cache] keeps it under [CACHE_LIMIT_BYTES] (hourly, from
//!   `retention::run_pruning`). Never backed up.

use std::collections::HashMap;
use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::sync::atomic::{AtomicU64, Ordering};
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use axum::extract::{Path as UrlPath, State};
use axum::http::{StatusCode, header};
use axum::response::{IntoResponse, Response};
use serde::{Deserialize, Serialize};
use tower_sessions::Session;

use crate::AppState;
use crate::music_net::BoxFut;

/// Where the cache lives in production (a temp dir in tests).
pub const TILE_DIR: &str = "data/tiles";
/// OSM's standard layer goes to zoom 19.
pub const MAX_ZOOM: u32 = 19;
/// The largest tile kept (a tile is about 100 KB at most).
pub const MAX_TILE_BYTES: usize = 1_000_000;
/// The cache's bound; [prune_cache] deletes the least recently fetched tiles past it.
pub const CACHE_LIMIT_BYTES: u64 = 200 * 1024 * 1024;
/// A tile is fresh at least this long (the policy's minimum) ...
pub const MIN_FRESH: Duration = Duration::from_secs(7 * 24 * 60 * 60);
/// ... and at most this long, whatever OSM's `max-age` says.
pub const MAX_FRESH: Duration = Duration::from_secs(30 * 24 * 60 * 60);
/// Upstream fetches in flight, server-wide.
pub const UPSTREAM_PARALLEL: usize = 2;
/// The per-session rate of upstream fetches: this many at once ...
pub const RATE_BURST: f64 = 300.0;
/// ... then this many a second (one every 2 s).
pub const RATE_PER_SEC: f64 = 0.5;
/// The browser keeps a tile this long.
const BROWSER_CACHE: &str = "private, max-age=86400";
/// Per the tile usage policy: the app, its version and where to reach the project.
pub const USER_AGENT: &str = concat!(
    "VibbPhoneServer/",
    env!("CARGO_PKG_VERSION"),
    " (self-hosted; +https://github.com/palchrb/vibb-launcher)"
);
const PNG_SIGNATURE: &[u8] = b"\x89PNG\r\n\x1a\n";
/// Same-tile requests wait on one of these (a tile's coordinates pick it).
const LOCK_STRIPES: usize = 64;
/// Rate buckets kept in memory before full (idle) ones are dropped.
const MAX_BUCKETS: usize = 1024;

/// One tile's coordinates, checked ([TileKey::parse]).
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub struct TileKey {
    pub z: u32,
    pub x: u32,
    pub y: u32,
}

impl TileKey {
    /// `z` 0..=[MAX_ZOOM], `x`/`y` in `0..2^z`, digits only (no sign, at most 7 of them), the
    /// last segment ending in `.png`.
    pub fn parse(z: &str, x: &str, file: &str) -> Option<TileKey> {
        fn number(text: &str) -> Option<u32> {
            if text.is_empty() || text.len() > 7 || !text.bytes().all(|b| b.is_ascii_digit()) {
                return None;
            }
            text.parse().ok()
        }
        let z = number(z)?;
        if z > MAX_ZOOM {
            return None;
        }
        let x = number(x)?;
        let y = number(file.strip_suffix(".png")?)?;
        let side = 1u32 << z;
        (x < side && y < side).then_some(TileKey { z, x, y })
    }

    pub fn url(&self) -> String {
        format!(
            "https://tile.openstreetmap.org/{}/{}/{}.png",
            self.z, self.x, self.y
        )
    }

    fn png(&self, dir: &Path) -> PathBuf {
        dir.join(self.z.to_string())
            .join(self.x.to_string())
            .join(format!("{}.png", self.y))
    }

    fn meta(&self, dir: &Path) -> PathBuf {
        dir.join(self.z.to_string())
            .join(self.x.to_string())
            .join(format!("{}.json", self.y))
    }

    fn stripe(&self) -> usize {
        let mixed = ((self.z as u64) << 40) ^ ((self.x as u64) << 20) ^ (self.y as u64);
        (mixed.wrapping_mul(0x9E37_79B9_7F4A_7C15) >> 58) as usize % LOCK_STRIPES
    }
}

/// One upstream request: the tile and, for a stale cached copy, its validators.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct UpstreamRequest {
    pub tile: TileKey,
    pub etag: Option<String>,
    pub last_modified: Option<String>,
}

/// What OSM answered. `too_large`: the body went past [MAX_TILE_BYTES] (and was cut there).
#[derive(Clone, Debug, Default)]
pub struct UpstreamResponse {
    pub status: u16,
    pub content_type: Option<String>,
    pub body: Vec<u8>,
    pub too_large: bool,
    pub etag: Option<String>,
    pub last_modified: Option<String>,
    pub cache_control: Option<String>,
}

/// The tile fetcher: [HttpTiles] in the server, a canned one in the tests.
pub trait TileFetch: Send + Sync {
    fn fetch<'a>(
        &'a self,
        request: UpstreamRequest,
    ) -> BoxFut<'a, Result<UpstreamResponse, String>>;
}

/// The real fetcher: its own client (never the music `Gated` one), public addresses only, no
/// proxy, no redirects (the host is fixed), no `Referer`, no cookies.
pub struct HttpTiles {
    client: reqwest::Client,
}

impl HttpTiles {
    pub fn new() -> Self {
        let client = reqwest::Client::builder()
            .user_agent(USER_AGENT)
            .no_proxy()
            .dns_resolver(Arc::new(crate::music_net::PublicResolver))
            .redirect(reqwest::redirect::Policy::none())
            .referer(false)
            .connect_timeout(Duration::from_secs(10))
            .timeout(Duration::from_secs(20))
            .build()
            .expect("the tile client builds");
        HttpTiles { client }
    }
}

impl Default for HttpTiles {
    fn default() -> Self {
        Self::new()
    }
}

fn header_text(response: &reqwest::Response, name: header::HeaderName) -> Option<String> {
    response
        .headers()
        .get(name)
        .and_then(|v| v.to_str().ok())
        .map(|v| v.chars().take(500).collect())
}

impl TileFetch for HttpTiles {
    fn fetch<'a>(
        &'a self,
        request: UpstreamRequest,
    ) -> BoxFut<'a, Result<UpstreamResponse, String>> {
        Box::pin(async move {
            let mut builder = self
                .client
                .get(request.tile.url())
                .header(header::ACCEPT, "image/png");
            if let Some(etag) = &request.etag {
                builder = builder.header(header::IF_NONE_MATCH, etag);
            }
            if let Some(modified) = &request.last_modified {
                builder = builder.header(header::IF_MODIFIED_SINCE, modified);
            }
            let fail = |err: reqwest::Error| err.without_url().to_string();
            let mut response = builder.send().await.map_err(fail)?;
            let mut answer = UpstreamResponse {
                status: response.status().as_u16(),
                content_type: header_text(&response, header::CONTENT_TYPE),
                etag: header_text(&response, header::ETAG),
                last_modified: header_text(&response, header::LAST_MODIFIED),
                cache_control: header_text(&response, header::CACHE_CONTROL),
                ..Default::default()
            };
            if response
                .content_length()
                .is_some_and(|n| n > MAX_TILE_BYTES as u64)
            {
                answer.too_large = true;
                return Ok(answer);
            }
            while let Some(chunk) = response.chunk().await.map_err(fail)? {
                if answer.body.len() + chunk.len() > MAX_TILE_BYTES {
                    answer.too_large = true;
                    break;
                }
                answer.body.extend_from_slice(&chunk);
            }
            Ok(answer)
        })
    }
}

/// A cached tile's sidecar (`{y}.json`).
#[derive(Clone, Debug, Default, Serialize, Deserialize, PartialEq, Eq)]
pub struct TileMeta {
    /// When it was fetched or last renewed by a 304 (Unix seconds, server clock).
    pub fetched: i64,
    /// How long it is fresh from then.
    pub fresh_secs: u64,
    pub etag: Option<String>,
    pub last_modified: Option<String>,
}

impl TileMeta {
    fn is_fresh(&self, now: i64) -> bool {
        // A stamp from a clock that ran ahead (the Pi has no RTC) is not trusted.
        let age = now - self.fetched;
        age >= -24 * 60 * 60 && age < self.fresh_secs as i64
    }
}

/// How long a tile OSM just sent is fresh: its `max-age`, within [MIN_FRESH]..=[MAX_FRESH].
pub fn fresh_secs(cache_control: Option<&str>) -> u64 {
    let max_age = cache_control.and_then(|value| {
        value.split(',').find_map(|part| {
            let (name, number) = part.trim().split_once('=')?;
            name.trim()
                .eq_ignore_ascii_case("max-age")
                .then(|| number.trim().trim_matches('"').parse::<u64>().ok())
                .flatten()
        })
    });
    max_age
        .unwrap_or(0)
        .clamp(MIN_FRESH.as_secs(), MAX_FRESH.as_secs())
}

fn now_secs() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_secs() as i64)
        .unwrap_or(0)
}

/// A token bucket of one session's upstream fetches.
struct Bucket {
    tokens: f64,
    at: Instant,
}

/// The cache, the fetcher and the limits (`AppState.tiles`).
pub struct Tiles {
    pub dir: PathBuf,
    fetch: Arc<dyn TileFetch>,
    upstream: tokio::sync::Semaphore,
    locks: Vec<tokio::sync::Mutex<()>>,
    buckets: std::sync::Mutex<HashMap<String, Bucket>>,
    burst: f64,
    per_sec: f64,
    temp_counter: AtomicU64,
}

/// Why a tile couldn't be served.
#[derive(Debug, PartialEq, Eq)]
pub enum TileError {
    /// The session is over its rate and there's no cached copy.
    TooMany,
    /// OSM gave nothing usable and there's no cached copy.
    Upstream(String),
}

/// What OSM's answer means for the cache.
enum Outcome {
    New(Vec<u8>, TileMeta),
    NotModified(TileMeta),
    Unusable(String),
}

impl Tiles {
    pub fn new(dir: PathBuf, fetch: Arc<dyn TileFetch>) -> Self {
        Tiles {
            dir,
            fetch,
            upstream: tokio::sync::Semaphore::new(UPSTREAM_PARALLEL),
            locks: (0..LOCK_STRIPES)
                .map(|_| tokio::sync::Mutex::new(()))
                .collect(),
            buckets: Default::default(),
            burst: RATE_BURST,
            per_sec: RATE_PER_SEC,
            temp_counter: AtomicU64::new(0),
        }
    }

    /// Another rate (tests).
    pub fn with_rate(mut self, burst: f64, per_sec: f64) -> Self {
        self.burst = burst;
        self.per_sec = per_sec;
        self
    }

    /// Takes one upstream fetch from `session`'s bucket, or says it's empty.
    fn take_token(&self, session: &str) -> bool {
        let mut buckets = self.buckets.lock().unwrap_or_else(|e| e.into_inner());
        let now = Instant::now();
        let refill = |bucket: &mut Bucket| {
            let gained = now.duration_since(bucket.at).as_secs_f64() * self.per_sec;
            bucket.tokens = (bucket.tokens + gained).min(self.burst);
            bucket.at = now;
        };
        if buckets.len() >= MAX_BUCKETS && !buckets.contains_key(session) {
            buckets.retain(|_, bucket| {
                refill(bucket);
                bucket.tokens < self.burst
            });
        }
        let bucket = buckets.entry(session.to_string()).or_insert(Bucket {
            tokens: self.burst,
            at: now,
        });
        refill(bucket);
        if bucket.tokens >= 1.0 {
            bucket.tokens -= 1.0;
            true
        } else {
            false
        }
    }

    async fn read_cached(&self, tile: TileKey) -> Option<(Vec<u8>, TileMeta)> {
        let png = tokio::fs::read(tile.png(&self.dir)).await.ok()?;
        // A tile without a readable sidecar is stale and has no validators.
        let meta = tokio::fs::read(tile.meta(&self.dir))
            .await
            .ok()
            .and_then(|bytes| serde_json::from_slice(&bytes).ok())
            .unwrap_or_default();
        Some((png, meta))
    }

    /// Writes `bytes` to `path` by a temp file in the same directory and a rename.
    async fn write_atomic(&self, path: &Path, bytes: &[u8]) -> std::io::Result<()> {
        let parent = path.parent().expect("a tile path has a parent");
        tokio::fs::create_dir_all(parent).await?;
        let n = self.temp_counter.fetch_add(1, Ordering::Relaxed);
        let name = path.file_name().and_then(|n| n.to_str()).unwrap_or("tile");
        let temp = parent.join(format!(".{name}.{}-{n}.tmp", std::process::id()));
        if let Err(err) = tokio::fs::write(&temp, bytes).await {
            let _ = tokio::fs::remove_file(&temp).await;
            return Err(err);
        }
        if let Err(err) = tokio::fs::rename(&temp, path).await {
            let _ = tokio::fs::remove_file(&temp).await;
            return Err(err);
        }
        Ok(())
    }

    async fn store(&self, tile: TileKey, png: Option<&[u8]>, meta: &TileMeta) {
        let meta_bytes = serde_json::to_vec(meta).expect("the tile meta serialises");
        let result = async {
            if let Some(png) = png {
                self.write_atomic(&tile.png(&self.dir), png).await?;
            }
            self.write_atomic(&tile.meta(&self.dir), &meta_bytes).await
        }
        .await;
        if let Err(err) = result {
            tracing::warn!(%err, "map tiles: couldn't cache {}/{}/{}", tile.z, tile.x, tile.y);
        }
    }

    /// Judges OSM's answer: a usable PNG, a 304 for the cached copy, or nothing.
    fn judge(response: UpstreamResponse, cached: Option<&TileMeta>, now: i64) -> Outcome {
        let fresh = fresh_secs(response.cache_control.as_deref());
        match response.status {
            200 => {
                let png_type = response.content_type.as_deref().is_some_and(|t| {
                    t.split(';')
                        .next()
                        .is_some_and(|t| t.trim().eq_ignore_ascii_case("image/png"))
                });
                if !png_type {
                    return Outcome::Unusable(format!(
                        "not image/png ({})",
                        response
                            .content_type
                            .as_deref()
                            .unwrap_or("no content type")
                    ));
                }
                if response.too_large || response.body.len() > MAX_TILE_BYTES {
                    return Outcome::Unusable("larger than 1 MB".to_string());
                }
                if !response.body.starts_with(PNG_SIGNATURE) {
                    return Outcome::Unusable("not a PNG".to_string());
                }
                Outcome::New(
                    response.body,
                    TileMeta {
                        fetched: now,
                        fresh_secs: fresh,
                        etag: response.etag,
                        last_modified: response.last_modified,
                    },
                )
            }
            304 => match cached {
                Some(old) => Outcome::NotModified(TileMeta {
                    fetched: now,
                    fresh_secs: fresh,
                    etag: response.etag.or_else(|| old.etag.clone()),
                    last_modified: response.last_modified.or_else(|| old.last_modified.clone()),
                }),
                None => Outcome::Unusable("304 without a cached copy".to_string()),
            },
            status => Outcome::Unusable(format!("HTTP {status}")),
        }
    }

    /// The tile's bytes: fresh from the cache, else from OSM (revalidating a stale copy), else the
    /// stale copy. `session` keys the rate.
    pub async fn get(&self, tile: TileKey, session: &str) -> Result<Vec<u8>, TileError> {
        if let Some((png, meta)) = self.read_cached(tile).await
            && meta.is_fresh(now_secs())
        {
            return Ok(png);
        }
        // One request per tile goes on; the others find its result in the cache.
        let _tile_lock = self.locks[tile.stripe()].lock().await;
        let cached = self.read_cached(tile).await;
        if let Some((png, meta)) = &cached
            && meta.is_fresh(now_secs())
        {
            return Ok(png.clone());
        }
        if !self.take_token(session) {
            return match cached {
                Some((png, _)) => Ok(png),
                None => Err(TileError::TooMany),
            };
        }
        let request = UpstreamRequest {
            tile,
            etag: cached.as_ref().and_then(|(_, m)| m.etag.clone()),
            last_modified: cached.as_ref().and_then(|(_, m)| m.last_modified.clone()),
        };
        let answer = {
            let _permit = self
                .upstream
                .acquire()
                .await
                .expect("the tile semaphore is never closed");
            self.fetch.fetch(request).await
        };
        let outcome = match answer {
            Ok(response) => Self::judge(response, cached.as_ref().map(|(_, m)| m), now_secs()),
            Err(err) => Outcome::Unusable(err),
        };
        match outcome {
            Outcome::New(png, meta) => {
                self.store(tile, Some(&png), &meta).await;
                Ok(png)
            }
            Outcome::NotModified(meta) => {
                self.store(tile, None, &meta).await;
                Ok(cached.expect("a 304 only with a cached copy").0)
            }
            Outcome::Unusable(why) => {
                tracing::warn!(
                    "map tiles: OSM gave nothing usable for {}/{}/{}: {why}{}",
                    tile.z,
                    tile.x,
                    tile.y,
                    if cached.is_some() {
                        " - serving the cached copy"
                    } else {
                        ""
                    }
                );
                match cached {
                    Some((png, _)) => Ok(png),
                    None => Err(TileError::Upstream(why)),
                }
            }
        }
    }
}

/// `GET /tiles/{z}/{x}/{y}.png` (admin, behind the session).
pub async fn serve(
    State(state): State<AppState>,
    session: Session,
    UrlPath((z, x, file)): UrlPath<(String, String, String)>,
) -> Response {
    let Some(tile) = TileKey::parse(&z, &x, &file) else {
        return StatusCode::NOT_FOUND.into_response();
    };
    // `require_full_auth` loaded the session, so it has an id.
    let key = session
        .id()
        .map(|id| id.to_string())
        .unwrap_or_else(|| "no-session".to_string());
    match state.tiles.get(tile, &key).await {
        Ok(png) => (
            [
                (header::CONTENT_TYPE, "image/png"),
                (header::CACHE_CONTROL, BROWSER_CACHE),
                (header::X_CONTENT_TYPE_OPTIONS, "nosniff"),
            ],
            png,
        )
            .into_response(),
        Err(TileError::TooMany) => StatusCode::TOO_MANY_REQUESTS.into_response(),
        Err(TileError::Upstream(_)) => StatusCode::BAD_GATEWAY.into_response(),
    }
}

/// What [prune_cache] deleted.
#[derive(Debug, Default, PartialEq, Eq)]
pub struct Pruned {
    pub tiles: u64,
    pub bytes: u64,
}

/// Keeps the cache under `limit` bytes: deletes the tiles least recently fetched or renewed (the
/// sidecar's mtime, else the PNG's) until the rest fits, plus sidecars without a tile and temp
/// files older than an hour. Blocking - run it on a blocking thread.
pub fn prune_cache(dir: &Path, limit: u64) -> std::io::Result<Pruned> {
    struct Entry {
        png: PathBuf,
        meta: PathBuf,
        size: u64,
        stamp: SystemTime,
    }
    let mut entries = Vec::new();
    let mut total = 0u64;
    let read_dir = match std::fs::read_dir(dir) {
        Ok(read_dir) => read_dir,
        Err(err) if err.kind() == std::io::ErrorKind::NotFound => return Ok(Pruned::default()),
        Err(err) => return Err(err),
    };
    let hour_ago = SystemTime::now() - Duration::from_secs(60 * 60);
    for z_dir in read_dir.flatten() {
        if !z_dir.file_type().is_ok_and(|t| t.is_dir()) {
            continue;
        }
        for x_dir in std::fs::read_dir(z_dir.path())?.flatten() {
            if !x_dir.file_type().is_ok_and(|t| t.is_dir()) {
                continue;
            }
            for file in std::fs::read_dir(x_dir.path())?.flatten() {
                let path = file.path();
                let Ok(info) = file.metadata() else { continue };
                if !info.is_file() {
                    continue;
                }
                let name = file.file_name();
                let name = name.to_string_lossy();
                let modified = info.modified().unwrap_or(UNIX_EPOCH);
                if name.ends_with(".tmp") {
                    if modified < hour_ago {
                        let _ = std::fs::remove_file(&path);
                    }
                } else if name.ends_with(".json") {
                    if !path.with_extension("png").exists() {
                        let _ = std::fs::remove_file(&path);
                    }
                } else if name.ends_with(".png") {
                    let meta = path.with_extension("json");
                    let meta_info = std::fs::metadata(&meta).ok();
                    let size = info.len() + meta_info.as_ref().map_or(0, |m| m.len());
                    let stamp = meta_info
                        .and_then(|m| m.modified().ok())
                        .unwrap_or(modified);
                    total += size;
                    entries.push(Entry {
                        png: path,
                        meta,
                        size,
                        stamp,
                    });
                }
            }
        }
    }
    let mut pruned = Pruned::default();
    if total <= limit {
        return Ok(pruned);
    }
    entries.sort_by_key(|entry| entry.stamp);
    for entry in entries {
        if total <= limit {
            break;
        }
        let _ = std::fs::remove_file(&entry.meta);
        if std::fs::remove_file(&entry.png).is_ok() {
            total -= entry.size;
            pruned.tiles += 1;
            pruned.bytes += entry.size;
        }
    }
    Ok(pruned)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn coordinates_follow_the_zoom() {
        assert_eq!(
            TileKey::parse("0", "0", "0.png"),
            Some(TileKey { z: 0, x: 0, y: 0 })
        );
        assert!(TileKey::parse("19", "524287", "524287.png").is_some());
        for (z, x, file) in [
            ("20", "0", "0.png"),
            ("0", "1", "0.png"),
            ("0", "0", "1.png"),
            ("3", "8", "0.png"),
            ("19", "524288", "0.png"),
            ("1", "0", "0"),
            ("1", "0", "0.jpg"),
            ("1", "+0", "0.png"),
            ("-1", "0", "0.png"),
            ("1", "", "0.png"),
            ("1", "0", ".png"),
            ("01234567", "0", "0.png"),
        ] {
            assert_eq!(TileKey::parse(z, x, file), None, "{z}/{x}/{file}");
        }
    }

    #[test]
    fn the_user_agent_names_the_app_its_version_and_the_project() {
        assert!(USER_AGENT.starts_with("VibbPhoneServer/"), "{USER_AGENT}");
        assert!(USER_AGENT.contains(env!("CARGO_PKG_VERSION")));
        assert!(USER_AGENT.contains("https://github.com/palchrb/vibb-launcher"));
    }

    #[test]
    fn freshness_honours_max_age_within_7_to_30_days() {
        let day = 24 * 60 * 60;
        assert_eq!(fresh_secs(None), 7 * day);
        assert_eq!(fresh_secs(Some("max-age=3600")), 7 * day);
        assert_eq!(fresh_secs(Some("public, max-age=864000")), 10 * day);
        assert_eq!(
            fresh_secs(Some("Max-Age=99999999, stale-while-revalidate=1")),
            30 * day
        );
        assert_eq!(fresh_secs(Some("no-cache")), 7 * day);
    }

    #[test]
    fn a_stamp_far_in_the_future_is_stale() {
        let meta = TileMeta {
            fetched: 1_000_000 + 2 * 24 * 60 * 60,
            fresh_secs: MIN_FRESH.as_secs(),
            ..Default::default()
        };
        assert!(!meta.is_fresh(1_000_000));
        let meta = TileMeta {
            fetched: 1_000_000 + 60,
            ..meta
        };
        assert!(meta.is_fresh(1_000_000));
    }
}
