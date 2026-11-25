//! The PWA's map tiles through the server (Locate, Locate's "Alle", the zone editor): the parent's browser asks
//! `GET /map-tiles/{z}/{x}/{y}.png` on its own session, and the server answers from its cache or fetches the tile from
//! OpenStreetMap once. OpenStreetMap then sees only the server's address, never the parent's browser or which family
//! looked where.
//!
//! - **Only the tile pattern**: `z` 0..=[MAX_ZOOM], `x` and `y` below `2^z`, plain digits; the upstream address is
//!   built here from the three numbers ([UPSTREAM]) - never a URL from the request, so this is no open proxy.
//! - **OpenStreetMap's tile usage policy**: its own User-Agent naming the software ([user_agent]), every tile kept
//!   [FRESH] (7 days, the policy's minimum) before it is asked again, at most [PARALLEL] downloads at once and
//!   [FETCHES_AN_HOUR] in a rolling hour, one fetch per tile at a time, a [FAILURE_PAUSE] after a failed download
//!   (the cache alone answers), a 404 remembered for [MISSING_FOR], and nothing fetched ahead: a tile is fetched only
//!   when a browser asks for it.
//! - **Bounded**: a tile is at most [MAX_TILE_BYTES] and must be a PNG; a tile older than [KEEP] is deleted, and the
//!   cache is trimmed to under [CACHE_BYTES] by age ([sweep]). A stale tile (past [FRESH]) is served at once while a
//!   background refresh asks for a new one.
//! - The cache is `data/map_tiles/<z>/<x>/<y>.png`, never in a backup; the fetch is `music_net`'s guarded client
//!   ([crate::music_net::Reach::Public], no proxy from the environment, https-only redirects).

use std::collections::{HashMap, VecDeque};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU32, AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
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
/// A tile is asked again only after this long: 7 days, OpenStreetMap's minimum.
pub const FRESH: Duration = Duration::from_secs(7 * 24 * 3600);
/// A tile is deleted after this long; until then a stale one is served while a new one is fetched in the background.
pub const KEEP: Duration = Duration::from_secs(30 * 24 * 3600);
/// The cache's size; past it the oldest tiles go until it is under nine tenths of it.
pub const CACHE_BYTES: u64 = 256 * 1024 * 1024;
/// The largest tile read (OpenStreetMap's are a few to some tens of KB).
pub const MAX_TILE_BYTES: usize = 512 * 1024;
/// Downloads from OpenStreetMap at once.
pub const PARALLEL: usize = 2;
/// Downloads from OpenStreetMap in a rolling hour; past it only the cache answers.
pub const FETCHES_AN_HOUR: usize = 3000;
/// A failed download pauses every download this long: the tiles come from the cache alone meanwhile.
pub const FAILURE_PAUSE: Duration = Duration::from_secs(60);
/// A tile OpenStreetMap doesn't have (a 404) isn't asked again for this long.
pub const MISSING_FOR: Duration = Duration::from_secs(24 * 3600);
/// The most 404s remembered; past it the expired ones go, then all of them.
const MISSING_MAX: usize = 10_000;
/// A sweep every this many stored tiles (and once at start-up, `main`).
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

/// A tile's `z`, `x`, `y`.
pub type Tile = (u32, u32, u32);

/// The tile cache's state in memory: the download gate, the hour's downloads, the tiles being fetched, the pause
/// after a failure, the remembered 404s and the sweep counter.
pub struct MapTiles {
    gate: tokio::sync::Semaphore,
    fetched: Mutex<VecDeque<Instant>>,
    /// When the spent budget was last logged (once an hour).
    budget_warned: Mutex<Option<Instant>>,
    /// One fetch per tile at a time: a second asker waits for the first's lock, a background refresh skips.
    in_flight: Mutex<HashMap<Tile, Arc<tokio::sync::Mutex<()>>>>,
    paused_until: Mutex<Option<Instant>>,
    missing: Mutex<HashMap<Tile, Instant>>,
    stored: AtomicU32,
}

impl Default for MapTiles {
    fn default() -> Self {
        MapTiles {
            gate: tokio::sync::Semaphore::new(PARALLEL),
            fetched: Mutex::new(VecDeque::new()),
            budget_warned: Mutex::new(None),
            in_flight: Mutex::new(HashMap::new()),
            paused_until: Mutex::new(None),
            missing: Mutex::new(HashMap::new()),
            stored: AtomicU32::new(0),
        }
    }
}

fn locked<T>(mutex: &Mutex<T>) -> std::sync::MutexGuard<'_, T> {
    mutex.lock().unwrap_or_else(|e| e.into_inner())
}

impl MapTiles {
    /// Takes one download from the hour's budget, or `false` when it is spent (logged once an hour).
    fn take_budget(&self, now: Instant) -> bool {
        let mut fetched = locked(&self.fetched);
        while fetched
            .front()
            .is_some_and(|at| now.duration_since(*at) >= Duration::from_secs(3600))
        {
            fetched.pop_front();
        }
        if fetched.len() >= FETCHES_AN_HOUR {
            let mut warned = locked(&self.budget_warned);
            if warned.is_none_or(|at| now.duration_since(at) >= Duration::from_secs(3600)) {
                *warned = Some(now);
                tracing::warn!(
                    "map tiles: the hour's {FETCHES_AN_HOUR} downloads are spent - the cache alone answers"
                );
            }
            return false;
        }
        fetched.push_back(now);
        true
    }

    fn paused(&self, now: Instant) -> bool {
        locked(&self.paused_until).is_some_and(|until| now < until)
    }

    fn pause(&self, now: Instant) {
        *locked(&self.paused_until) = Some(now + FAILURE_PAUSE);
    }

    /// Ends the pause (the tests).
    #[cfg(test)]
    pub fn unpause(&self) {
        *locked(&self.paused_until) = None;
    }

    fn is_missing(&self, tile: Tile, now: Instant) -> bool {
        locked(&self.missing)
            .get(&tile)
            .is_some_and(|at| now.duration_since(*at) < MISSING_FOR)
    }

    fn remember_missing(&self, tile: Tile, now: Instant) {
        let mut missing = locked(&self.missing);
        if missing.len() >= MISSING_MAX {
            missing.retain(|_, at| now.duration_since(*at) < MISSING_FOR);
            if missing.len() >= MISSING_MAX {
                missing.clear();
            }
        }
        missing.insert(tile, now);
    }

    /// The tile's own lock (one fetch per tile at a time).
    fn tile_lock(&self, tile: Tile) -> Arc<tokio::sync::Mutex<()>> {
        locked(&self.in_flight).entry(tile).or_default().clone()
    }

    /// Forgets the tile's lock once no one else holds or waits for it.
    fn release_tile(&self, tile: Tile, lock: Arc<tokio::sync::Mutex<()>>) {
        let mut in_flight = locked(&self.in_flight);
        // The map's own reference and `lock`: nobody else.
        if Arc::strong_count(&lock) <= 2 {
            in_flight.remove(&tile);
        }
    }
}

/// A tile's three numbers, checked: `z` 0..=[MAX_ZOOM], `x` and `y` below `2^z`; `y` ends in `.png`. Only ASCII
/// digits, at most 7 of them (no sign, no spaces).
pub fn parse_tile(z: &str, x: &str, file: &str) -> Option<Tile> {
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

fn tile_path(dir: &Path, (z, x, y): Tile) -> PathBuf {
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

/// Writes a tile through a temporary file of its own (the process and a counter in its name) and a rename, so a
/// reader never sees half of one and two writers never share one.
async fn store(path: &Path, bytes: &[u8]) -> std::io::Result<()> {
    static WRITES: AtomicU64 = AtomicU64::new(0);
    if let Some(parent) = path.parent() {
        tokio::fs::create_dir_all(parent).await?;
    }
    let name = path
        .file_name()
        .and_then(|n| n.to_str())
        .unwrap_or("tile.png");
    let partial = path.with_file_name(format!(
        "{name}.{}.{}.partial",
        std::process::id(),
        WRITES.fetch_add(1, Ordering::Relaxed)
    ));
    if let Err(err) = tokio::fs::write(&partial, bytes).await {
        let _ = tokio::fs::remove_file(&partial).await;
        return Err(err);
    }
    let renamed = tokio::fs::rename(&partial, path).await;
    if renamed.is_err() {
        let _ = tokio::fs::remove_file(&partial).await;
    }
    renamed
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

/// What asking for a tile came to.
enum Fetched {
    Tile(Vec<u8>),
    /// No such tile (a 404, now or within [MISSING_FOR]): nothing to show.
    Missing,
    /// No tile now: a failure (which pauses downloads), the pause, the hour's budget spent, or another fetch of the
    /// same tile under way (a background refresh only).
    Failed,
}

async fn fetch(source: &dyn Source, tiles: &MapTiles, (z, x, y): Tile) -> Fetched {
    if !tiles.take_budget(Instant::now()) {
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
    let failed = |why: String| {
        tracing::warn!(
            "map tiles: {z}/{x}/{y}: {why} - the cache alone answers for {} s",
            FAILURE_PAUSE.as_secs()
        );
        tiles.pause(Instant::now());
        Fetched::Failed
    };
    match source.get(request).await {
        Ok(answer) if answer.status == 200 => {
            if answer.truncated || !answer.body.starts_with(PNG_SIGNATURE) {
                failed("not a tile".to_string())
            } else {
                Fetched::Tile(answer.body)
            }
        }
        Ok(answer) if answer.status == 404 => {
            tiles.remember_missing((z, x, y), Instant::now());
            Fetched::Missing
        }
        Ok(answer) => failed(format!("answered {}", answer.status)),
        Err(err) => failed(err.message()),
    }
}

/// Fetches a tile from OpenStreetMap and stores it, one fetch per tile at a time: `wait` waits for another fetch of
/// the same tile (and then takes the tile it stored), a background refresh doesn't. Nothing is asked during the pause
/// after a failure.
async fn refresh(state: &AppState, tile: Tile, wait: bool) -> Fetched {
    let tiles = &state.map_tiles;
    let dir = state.data_dir.join(DIR);
    let path = tile_path(&dir, tile);
    let lock = tiles.tile_lock(tile);
    let fetched = async {
        let _guard = if wait {
            lock.lock().await
        } else {
            match lock.try_lock() {
                Ok(guard) => guard,
                Err(_) => return Fetched::Failed,
            }
        };
        // Another request may have fetched it meanwhile.
        if let Some((bytes, age)) = cached(&path, SystemTime::now()).await
            && age < FRESH
        {
            return Fetched::Tile(bytes);
        }
        if tiles.is_missing(tile, Instant::now()) {
            return Fetched::Missing;
        }
        if tiles.paused(Instant::now()) {
            return Fetched::Failed;
        }
        let Ok(_permit) = tiles.gate.acquire().await else {
            return Fetched::Failed;
        };
        if tiles.paused(Instant::now()) {
            return Fetched::Failed;
        }
        let fetched = fetch(state.tile_fetch.as_ref(), tiles, tile).await;
        if let Fetched::Tile(bytes) = &fetched {
            match store(&path, bytes).await {
                Ok(()) => {
                    if tiles.stored.fetch_add(1, Ordering::Relaxed) % SWEEP_EVERY == SWEEP_EVERY - 1
                    {
                        tokio::task::spawn_blocking(move || sweep(&dir, SystemTime::now()));
                    }
                }
                Err(err) => tracing::warn!("map tiles: couldn't store a tile: {err}"),
            }
        }
        fetched
    }
    .await;
    tiles.release_tile(tile, lock);
    fetched
}

/// `GET /map-tiles/{z}/{x}/{y}.png` (the parent's session): a fresh tile from the cache; a stale one (past [FRESH])
/// from the cache at once while a background refresh asks OpenStreetMap for a new one; none from OpenStreetMap
/// (stored). 404 for anything that isn't a tile's address or a tile OpenStreetMap doesn't have, 503 when there is
/// neither a kept nor a new tile.
pub async fn tile(
    State(state): State<AppState>,
    UrlPath((z, x, file)): UrlPath<(String, String, String)>,
) -> Response {
    let Some(tile) = parse_tile(&z, &x, &file) else {
        return StatusCode::NOT_FOUND.into_response();
    };
    let path = tile_path(&state.data_dir.join(DIR), tile);
    if let Some((bytes, age)) = cached(&path, SystemTime::now()).await {
        if age >= FRESH {
            let state = state.clone();
            tokio::spawn(async move {
                refresh(&state, tile, false).await;
            });
        }
        return png(bytes);
    }
    if state.map_tiles.is_missing(tile, Instant::now()) {
        return StatusCode::NOT_FOUND.into_response();
    }
    match refresh(&state, tile, true).await {
        Fetched::Tile(bytes) => png(bytes),
        Fetched::Missing => StatusCode::NOT_FOUND.into_response(),
        Fetched::Failed => StatusCode::SERVICE_UNAVAILABLE.into_response(),
    }
}

/// The cache's upkeep (every [SWEEP_EVERY] stored tiles and once at start-up): a tile older than [KEEP] and a
/// left-over `.partial` older than an hour are deleted, then the oldest tiles until the rest is under nine tenths of
/// [CACHE_BYTES] when it was over it, then the folders left empty. Returns the bytes kept.
pub fn sweep(dir: &Path, now: SystemTime) -> u64 {
    sweep_to(dir, now, CACHE_BYTES)
}

fn sweep_to(dir: &Path, now: SystemTime, cap: u64) -> u64 {
    let mut files = Vec::new();
    let mut folders = Vec::new();
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
                folders.push(entry.path());
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
    // The deepest first; a folder with something in it stays (`remove_dir` refuses it).
    folders.sort_by_key(|f| std::cmp::Reverse(f.components().count()));
    for folder in folders {
        let _ = std::fs::remove_dir(&folder);
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
        assert!(!dir.path().join("1/0").exists(), "an emptied folder goes");
        // 1200 over 1000: the oldest go until under 900.
        assert!(!oldest.exists() && older.exists() && newest.exists());
        assert_eq!(kept, 800);
        // Under the cap nothing more goes.
        assert_eq!(sweep_to(dir.path(), now, 1000), 800);
    }
}
