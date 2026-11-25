//! The PWA's map tiles through the server (Locate, Locate's "Alle", the zone editor): the parent's browser asks
//! `GET /map-tiles/{z}/{x}/{y}.png` on its own session, and the server answers from its cache or fetches the tile from
//! OpenStreetMap once. OpenStreetMap then sees only the server's address, never the parent's browser or which family
//! looked where.
//!
//! - **Only the tile pattern**: `z` 0..=[MAX_ZOOM], `x` and `y` below `2^z`, plain digits; the upstream address is
//!   built here from the three numbers ([UPSTREAM]) - never a URL from the request, so this is no open proxy.
//! - **OpenStreetMap's tile usage policy**: its own User-Agent naming the software ([user_agent]), every tile kept at
//!   least [FRESH] (the policy's 7 days when the answer says nothing), at most [PARALLEL] downloads at once and
//!   [FETCHES_AN_HOUR] in a rolling hour, and nothing fetched ahead: a tile is fetched only when a browser asks for it.
//! - **Bounded**: a tile is at most [MAX_TILE_BYTES] and must be a PNG; a tile older than [KEEP] is deleted, and the
//!   cache is trimmed to under [CACHE_BYTES] by age ([sweep]). A stale tile (past [FRESH]) is still served while a
//!   new one can't be fetched.
//! - The cache is `data/map_tiles/<z>/<x>/<y>.png`, never in a backup; the fetch is `music_net`'s guarded client
//!   ([crate::music_net::Reach::Public], no proxy from the environment, https-only redirects).

use std::collections::VecDeque;
use std::path::{Path, PathBuf};
use std::sync::Mutex;
use std::sync::atomic::{AtomicU32, Ordering};
use std::time::{Duration, Instant, SystemTime};

use axum::extract::{Path as UrlPath, State};
use axum::http::{StatusCode, header};
use axum::response::{IntoResponse, Response};

use crate::AppState;
use crate::music_net::{Kind, Reach, Source, SourceRequest};

/// OpenStreetMap's standard tile layer.
pub const UPSTREAM: &str = "https://tile.openstreetmap.org";
/// The deepest zoom the maps ask (Leaflet's `maxZoom`, OpenStreetMap's own limit).
pub const MAX_ZOOM: u32 = 19;
/// A tile is asked again only after this long (OpenStreetMap's policy: at least 7 days).
pub const FRESH: Duration = Duration::from_secs(7 * 24 * 3600);
/// A tile is deleted after this long; until then a stale one is served while a new one can't be fetched.
pub const KEEP: Duration = Duration::from_secs(30 * 24 * 3600);
/// The cache's size; past it the oldest tiles go until it is under nine tenths of it.
pub const CACHE_BYTES: u64 = 256 * 1024 * 1024;
/// The largest tile read (OpenStreetMap's are a few to some tens of KB).
pub const MAX_TILE_BYTES: usize = 512 * 1024;
/// Downloads from OpenStreetMap at once.
pub const PARALLEL: usize = 2;
/// Downloads from OpenStreetMap in a rolling hour; past it only the cache answers.
pub const FETCHES_AN_HOUR: usize = 3000;
/// A sweep every this many stored tiles (and at the first one after a start).
const SWEEP_EVERY: u32 = 200;
/// The cache's folder under `data/`.
pub const DIR: &str = "map_tiles";

/// What OpenStreetMap is told this is: the software, generically, and its version - nothing about the household.
pub fn user_agent() -> String {
    format!(
        "vibb-server/{} (self-hosted map tile cache)",
        env!("CARGO_PKG_VERSION")
    )
}

/// The tile cache's state in memory: the download gate, the hour's downloads and the sweep counter.
pub struct MapTiles {
    gate: tokio::sync::Semaphore,
    fetched: Mutex<VecDeque<Instant>>,
    stored: AtomicU32,
}

impl Default for MapTiles {
    fn default() -> Self {
        MapTiles {
            gate: tokio::sync::Semaphore::new(PARALLEL),
            fetched: Mutex::new(VecDeque::new()),
            stored: AtomicU32::new(0),
        }
    }
}

impl MapTiles {
    /// Takes one download from the hour's budget, or `false` when it is spent.
    fn take_budget(&self, now: Instant) -> bool {
        let mut fetched = self.fetched.lock().unwrap_or_else(|e| e.into_inner());
        while fetched
            .front()
            .is_some_and(|at| now.duration_since(*at) >= Duration::from_secs(3600))
        {
            fetched.pop_front();
        }
        if fetched.len() >= FETCHES_AN_HOUR {
            return false;
        }
        fetched.push_back(now);
        true
    }
}

/// A tile's three numbers, checked: `z` 0..=[MAX_ZOOM], `x` and `y` below `2^z`; `y` ends in `.png`. Only ASCII
/// digits, at most 7 of them (no sign, no spaces).
pub fn parse_tile(z: &str, x: &str, file: &str) -> Option<(u32, u32, u32)> {
    let number = |text: &str| {
        (!text.is_empty() && text.len() <= 7 && text.bytes().all(|b| b.is_ascii_digit()))
            .then(|| text.parse::<u32>().ok())
            .flatten()
    };
    let z = number(z)?;
    if z > MAX_ZOOM {
        return None;
    }
    let side = 1u32 << z;
    let x = number(x).filter(|x| *x < side)?;
    let y = number(file.strip_suffix(".png")?).filter(|y| *y < side)?;
    Some((z, x, y))
}

fn tile_path(dir: &Path, (z, x, y): (u32, u32, u32)) -> PathBuf {
    dir.join(z.to_string())
        .join(x.to_string())
        .join(format!("{y}.png"))
}

const PNG_SIGNATURE: &[u8] = b"\x89PNG\r\n\x1a\n";

/// The cached tile and its age, when there is one younger than [KEEP].
async fn cached(path: &Path, now: SystemTime) -> Option<(Vec<u8>, Duration)> {
    let meta = tokio::fs::metadata(path).await.ok()?;
    let age = now
        .duration_since(meta.modified().ok()?)
        .unwrap_or_default();
    if age >= KEEP {
        return None;
    }
    let bytes = tokio::fs::read(path).await.ok()?;
    bytes.starts_with(PNG_SIGNATURE).then_some((bytes, age))
}

/// Writes a tile through a temporary file and a rename, so a reader never sees half of one.
async fn store(path: &Path, bytes: &[u8]) -> std::io::Result<()> {
    if let Some(parent) = path.parent() {
        tokio::fs::create_dir_all(parent).await?;
    }
    let partial = path.with_extension("png.partial");
    tokio::fs::write(&partial, bytes).await?;
    tokio::fs::rename(&partial, path).await
}

fn png(bytes: Vec<u8>) -> Response {
    (
        [
            (header::CONTENT_TYPE, "image/png"),
            // The parent's own browser keeps it a day; never a shared cache (the area a parent looked at).
            (header::CACHE_CONTROL, "private, max-age=86400"),
        ],
        bytes,
    )
        .into_response()
}

/// What OpenStreetMap answered for a tile.
enum Fetched {
    Tile(Vec<u8>),
    /// No such tile (a 404): nothing to show.
    Missing,
    /// No tile now: a network error, another status, not a PNG, too big, or the hour's budget spent.
    Failed,
}

async fn fetch(source: &dyn Source, tiles: &MapTiles, (z, x, y): (u32, u32, u32)) -> Fetched {
    if !tiles.take_budget(Instant::now()) {
        tracing::warn!("map tiles: the hour's {FETCHES_AN_HOUR} downloads are spent");
        return Fetched::Failed;
    }
    let mut request = SourceRequest::new(
        format!("{UPSTREAM}/{z}/{x}/{y}.png"),
        Kind::MapTile,
        Reach::Public,
    );
    request
        .headers
        .push((reqwest::header::USER_AGENT, user_agent()));
    match source.get(request).await {
        Ok(answer) if answer.status == 200 => {
            if answer.truncated || !answer.body.starts_with(PNG_SIGNATURE) {
                tracing::warn!("map tiles: {z}/{x}/{y} wasn't a tile");
                Fetched::Failed
            } else {
                Fetched::Tile(answer.body)
            }
        }
        Ok(answer) if answer.status == 404 => Fetched::Missing,
        Ok(answer) => {
            tracing::warn!("map tiles: {z}/{x}/{y} answered {}", answer.status);
            Fetched::Failed
        }
        Err(err) => {
            tracing::warn!("map tiles: {z}/{x}/{y}: {}", err.message());
            Fetched::Failed
        }
    }
}

/// `GET /map-tiles/{z}/{x}/{y}.png` (the parent's session): the tile from the cache while it is fresh, else from
/// OpenStreetMap (stored), else the stale one; 404 for anything that isn't a tile's address or a tile OpenStreetMap
/// doesn't have, 503 when there is neither a new nor a kept tile.
pub async fn tile(
    State(state): State<AppState>,
    UrlPath((z, x, file)): UrlPath<(String, String, String)>,
) -> Response {
    let Some(tile) = parse_tile(&z, &x, &file) else {
        return StatusCode::NOT_FOUND.into_response();
    };
    let dir = state.data_dir.join(DIR);
    let path = tile_path(&dir, tile);
    let kept = cached(&path, SystemTime::now()).await;
    if let Some((bytes, age)) = &kept
        && *age < FRESH
    {
        return png(bytes.clone());
    }
    let tiles = &state.map_tiles;
    let Ok(_permit) = tiles.gate.acquire().await else {
        return StatusCode::SERVICE_UNAVAILABLE.into_response();
    };
    // Another request may have fetched it while this one waited.
    if let Some((bytes, age)) = cached(&path, SystemTime::now()).await
        && age < FRESH
    {
        return png(bytes);
    }
    match fetch(state.tile_fetch.as_ref(), tiles, tile).await {
        Fetched::Tile(bytes) => {
            match store(&path, &bytes).await {
                Ok(()) => {
                    if tiles.stored.fetch_add(1, Ordering::Relaxed) % SWEEP_EVERY == 0 {
                        tokio::task::spawn_blocking(move || sweep(&dir, SystemTime::now()));
                    }
                }
                Err(err) => tracing::warn!("map tiles: couldn't store a tile: {err}"),
            }
            png(bytes)
        }
        Fetched::Missing => StatusCode::NOT_FOUND.into_response(),
        Fetched::Failed => match kept {
            Some((bytes, _)) => png(bytes),
            None => StatusCode::SERVICE_UNAVAILABLE.into_response(),
        },
    }
}

/// The cache's upkeep: a tile older than [KEEP] and a left-over `.partial` older than an hour are deleted, then the
/// oldest tiles until the rest is under nine tenths of [CACHE_BYTES] when it was over it. Returns the bytes kept.
pub fn sweep(dir: &Path, now: SystemTime) -> u64 {
    sweep_to(dir, now, CACHE_BYTES)
}

fn sweep_to(dir: &Path, now: SystemTime, cap: u64) -> u64 {
    let mut files = Vec::new();
    let mut dirs = vec![dir.to_path_buf()];
    while let Some(next) = dirs.pop() {
        let Ok(entries) = std::fs::read_dir(&next) else {
            continue;
        };
        for entry in entries.flatten() {
            let Ok(meta) = entry.metadata() else {
                continue;
            };
            if meta.is_dir() {
                dirs.push(entry.path());
                continue;
            }
            let age = meta
                .modified()
                .ok()
                .and_then(|at| now.duration_since(at).ok())
                .unwrap_or_default();
            let path = entry.path();
            let partial = path.extension().is_some_and(|e| e == "partial");
            if age >= KEEP || (partial && age >= Duration::from_secs(3600)) {
                let _ = std::fs::remove_file(&path);
                continue;
            }
            files.push((age, meta.len(), path));
        }
    }
    let mut total: u64 = files.iter().map(|(_, len, _)| len).sum();
    if total > cap {
        // The oldest first.
        files.sort_by_key(|a| std::cmp::Reverse(a.0));
        let target = cap / 10 * 9;
        for (_, len, path) in files {
            if total <= target {
                break;
            }
            if std::fs::remove_file(&path).is_ok() {
                total -= len;
            }
        }
    }
    total
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn only_a_tiles_address_is_a_tile() {
        assert_eq!(parse_tile("0", "0", "0.png"), Some((0, 0, 0)));
        assert_eq!(
            parse_tile("19", "524287", "524287.png"),
            Some((19, 524287, 524287))
        );
        assert_eq!(parse_tile("3", "7", "5.png"), Some((3, 7, 5)));
        for (z, x, file) in [
            ("20", "0", "0.png"),
            ("3", "8", "0.png"),
            ("3", "0", "8.png"),
            ("3", "1", "1"),
            ("3", "1", "1.jpg"),
            ("3", "+1", "1.png"),
            ("3", "-1", "1.png"),
            ("3", " 1", "1.png"),
            ("", "1", "1.png"),
            ("3", "1", ".png"),
            ("03", "1", "1.png.png"),
            ("3", "00000001", "1.png"),
            ("..", "1", "1.png"),
        ] {
            assert_eq!(parse_tile(z, x, file), None, "{z}/{x}/{file}");
        }
        // A leading zero is still the number (the path is built from the number, never the text).
        assert_eq!(parse_tile("03", "1", "1.png"), Some((3, 1, 1)));
    }

    #[test]
    fn the_user_agent_names_the_software_only() {
        let agent = user_agent();
        assert!(agent.starts_with("vibb-server/"), "{agent}");
        assert!(!agent.contains('@') && !agent.contains("github"), "{agent}");
    }

    #[test]
    fn the_hours_budget_is_bounded() {
        let tiles = MapTiles::default();
        let now = Instant::now();
        for _ in 0..FETCHES_AN_HOUR {
            assert!(tiles.take_budget(now));
        }
        assert!(!tiles.take_budget(now));
        assert!(tiles.take_budget(now + Duration::from_secs(3600)));
    }

    #[test]
    fn the_sweep_drops_old_tiles_and_keeps_the_size() {
        let dir = tempfile::tempdir().unwrap();
        let now = SystemTime::now();
        let write = |name: &str, len: usize, age: Duration| {
            let path = dir.path().join(name);
            std::fs::create_dir_all(path.parent().unwrap()).unwrap();
            std::fs::write(&path, vec![0u8; len]).unwrap();
            let file = std::fs::File::options().write(true).open(&path).unwrap();
            file.set_modified(now - age).unwrap();
            path
        };
        let day = Duration::from_secs(24 * 3600);
        let old = write("1/0/0.png", 10, KEEP + day);
        let partial = write("1/0/1.png.partial", 10, Duration::from_secs(7200));
        let oldest = write("1/1/0.png", 400, 5 * day);
        let older = write("1/1/1.png", 400, 3 * day);
        let newest = write("2/0/0.png", 400, day);
        let kept = sweep_to(dir.path(), now, 1000);
        assert!(!old.exists() && !partial.exists());
        // 1200 over 1000: the oldest go until under 900.
        assert!(!oldest.exists() && older.exists() && newest.exists());
        assert_eq!(kept, 800);
        // Under the cap nothing more goes.
        assert_eq!(sweep_to(dir.path(), now, 1000), 800);
    }
}
