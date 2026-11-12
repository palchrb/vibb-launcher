//! The apps' real logos (design 87, migration 0140): each phone draws its installed apps' own icons as 128 px
//! PNGs, reports each one's SHA-256 in its status report (`installed_apps[].logo`), and uploads only the ones the
//! report's answer asks for; the PWA shows them where it drew a letter tile.
//!
//! - **The report** ([intake]): a valid `logo` (64 lowercase hex, `device_api::cap_installed_apps`) upserts the
//!   phone's `device_app_logos` row for that package; an app without `logo` keeps its row; a package missing from
//!   the report loses it. The logos never reach `device_status.installed_apps_json` ([take_logos]).
//! - **The answer** ([answer]): to a launcher claiming [CAPABILITY], `200 {"wanted_logos": [...]}` - the hashes
//!   this report names whose file isn't stored, not refused in the last day, within the phone's daily budget and
//!   the store's and the phone's caps, at most [MAX_WANTED], allowed apps first, then by package name; `204` to
//!   every other report, as before.
//! - **The upload** ([upload], `POST /api/devices/app-logos/{hash}`): raw `image/png`, at most [MAX_BYTES]; the
//!   hash must match the bytes and name one of this phone's rows; the PNG is decoded under limits ([reencode]:
//!   exactly [SIZE_PX] square) and written again by the server's own encoder, so no chunk of the phone's survives.
//!   A refused PNG is not asked for again for a day.
//! - **The files** (`data/app_logos/<hash>.png`, `AppState.app_logo_dir`): one per distinct logo, shared by every
//!   phone; a file no row names is deleted ([prune_hashes] after a report and a phone's delete, [prune_all] in the
//!   retention pass). Never backed up: after a restore the rows name missing files, which the next report wants.
//! - **Served** ([view], `GET /app-logos/{hash}`) behind the parent's session, only a hash some row names.

use std::collections::{HashMap, HashSet};
use std::io::Cursor;
use std::path::{Path, PathBuf};
use std::sync::Mutex as StdMutex;
use std::time::{Duration, Instant};

use axum::body::Bytes;
use axum::extract::{Path as UrlPath, State};
use axum::http::{HeaderMap, StatusCode, header};
use axum::response::{IntoResponse, Response};
use axum::{Extension, Json};
use image::codecs::png::{PngDecoder, PngEncoder};
use image::{DynamicImage, ExtendedColorType, ImageDecoder, ImageEncoder, Limits};
use sha2::{Digest, Sha256};
use tokio::sync::Mutex;

use crate::AppState;
use crate::models::InstalledApp;
use crate::photos::is_valid_hash;
use crate::security::AuthedDevice;

/// What a launcher that reports and uploads logos claims in its status report's `capabilities`.
pub const CAPABILITY: &str = "app_logos_v1";
/// A logo is exactly this many pixels a side.
pub const SIZE_PX: u32 = 128;
/// The largest upload (R2-L1: a 128 x 128 RGBA PNG that doesn't compress is about 65.7 KB); the route's body limit.
pub const MAX_BYTES: usize = 80 * 1024;
/// The most hashes one answer asks for.
pub const MAX_WANTED: usize = 40;
/// The store's caps: past either, nothing more is wanted (`app_logos_full`).
pub const MAX_LOGO_FILES: usize = 4_000;
pub const MAX_LOGO_BYTES: u64 = 64 * 1024 * 1024;
/// One phone's caps (R2-M2): the files its rows name, shared ones counted for every phone naming them.
pub const MAX_LOGOS_PER_PHONE: usize = 300;
pub const MAX_LOGO_BYTES_PER_PHONE: u64 = 12 * 1024 * 1024;
/// Uploads a phone may make in a day, charged before the decode (a refused one counts too).
pub const UPLOADS_A_DAY: u32 = 300;
/// Where the logos are served on the admin listener.
pub const ROUTE: &str = "/app-logos";
/// Vibb One's own row: the server's Vibb mark (the launcher reports no logo of its own).
pub const VIBB_MARK: &str = "/static/icons/icon-192.png";

/// The day a refusal and the upload budget last.
const DAY: Duration = Duration::from_secs(24 * 60 * 60);
/// The decoder's whole allocation: a 128 x 128 image at 16-bit RGBA is 128 KiB.
const MAX_DECODE_ALLOC: u64 = 1024 * 1024;
/// A temp file older than this is a crash's leftover ([prune_all]).
const STRAY_TEMP_AGE: Duration = Duration::from_secs(60 * 60);
/// The PNG signature: a body that doesn't start with it is never parsed.
const PNG_SIGNATURE: [u8; 8] = [0x89, b'P', b'N', b'G', 0x0D, 0x0A, 0x1A, 0x0A];

/// Serialises writing a logo file and pruning one, so a prune never deletes a file whose row an upload has just
/// checked (the upload checks the row again under it, after the decode).
static APP_LOGO_FILES: Mutex<()> = Mutex::const_new(());

/// What this module keeps in memory only (a restart resets it, which the caps and the device API's token bucket
/// keep harmless): the refused hashes, each phone's uploads in its current day and when a full store was last
/// logged. Every map is bounded by `limits::evict_oldest` at `limits::MAX_KEYS`.
#[derive(Default)]
pub struct Memory {
    refused: StdMutex<HashMap<String, Instant>>,
    uploads: StdMutex<HashMap<i64, (Instant, u32)>>,
    /// `None` for the store, `Some(device)` for one phone's cap.
    logged: StdMutex<HashMap<Option<i64>, Instant>>,
}

impl Memory {
    /// `hash` was refused within the last day.
    fn is_refused(&self, hash: &str, now: Instant) -> bool {
        let refused = self.refused.lock().unwrap_or_else(|e| e.into_inner());
        refused
            .get(hash)
            .is_some_and(|at| now.duration_since(*at) < DAY)
    }

    fn refuse(&self, hash: &str, now: Instant) {
        let mut refused = self.refused.lock().unwrap_or_else(|e| e.into_inner());
        refused.retain(|_, at| now.duration_since(*at) < DAY);
        if refused.len() >= crate::limits::MAX_KEYS {
            crate::limits::evict_oldest(&mut refused, |at| *at);
        }
        refused.insert(hash.to_string(), now);
    }

    /// The uploads `device` may still make in its current day.
    fn uploads_left(&self, device: i64, now: Instant) -> u32 {
        let uploads = self.uploads.lock().unwrap_or_else(|e| e.into_inner());
        match uploads.get(&device) {
            Some((since, count)) if now.duration_since(*since) < DAY => {
                UPLOADS_A_DAY.saturating_sub(*count)
            }
            _ => UPLOADS_A_DAY,
        }
    }

    /// Charges one upload to `device`; `false` when its day's budget is spent (nothing charged).
    fn charge(&self, device: i64, now: Instant) -> bool {
        let mut uploads = self.uploads.lock().unwrap_or_else(|e| e.into_inner());
        if !uploads.contains_key(&device) && uploads.len() >= crate::limits::MAX_KEYS {
            crate::limits::evict_oldest(&mut uploads, |(since, _)| *since);
        }
        let entry = uploads.entry(device).or_insert((now, 0));
        if now.duration_since(entry.0) >= DAY {
            *entry = (now, 0);
        }
        if entry.1 >= UPLOADS_A_DAY {
            return false;
        }
        entry.1 += 1;
        true
    }

    /// Whether a full store (`None`) or a phone's full share (`Some(device)`) is due its daily log line.
    fn log_due(&self, key: Option<i64>, now: Instant) -> bool {
        let mut logged = self.logged.lock().unwrap_or_else(|e| e.into_inner());
        if logged
            .get(&key)
            .is_some_and(|at| now.duration_since(*at) < DAY)
        {
            return false;
        }
        if !logged.contains_key(&key) && logged.len() >= crate::limits::MAX_KEYS {
            crate::limits::evict_oldest(&mut logged, |at| *at);
        }
        logged.insert(key, now);
        true
    }
}

/// `<dir>/<hash>.png`, `None` for a hash that isn't 64 lowercase hex.
pub fn path_for(dir: &Path, hash: &str) -> Option<PathBuf> {
    is_valid_hash(hash).then(|| dir.join(format!("{hash}.png")))
}

/// Whether a logo is stored at `path`: a file with bytes (an empty one - a crash's leftover - is missing).
async fn is_stored(path: &Path) -> bool {
    tokio::fs::metadata(path)
        .await
        .is_ok_and(|meta| meta.is_file() && meta.len() > 0)
}

/// The URL the PWA shows a stored logo at.
pub fn src_of(hash: &str) -> String {
    format!("{ROUTE}/{hash}")
}

/// The report's `(package, logo)` pairs, taken out of the apps (the intake has checked each logo): the logos go
/// to `device_app_logos`, never into the stored snapshot.
pub fn take_logos(apps: Option<&mut Vec<InstalledApp>>) -> Vec<(String, String)> {
    apps.map(|apps| {
        apps.iter_mut()
            .filter_map(|app| app.logo.take().map(|logo| (app.package_name.clone(), logo)))
            .collect()
    })
    .unwrap_or_default()
}

/// A report's apps move this phone's rows: `logos` (the report's pairs) upsert the rows whose hash changed, a
/// package not in `apps` loses its row, the rest stay. The files only the removed or replaced rows named are
/// deleted after the commit. Best effort: a failure is logged.
pub async fn intake(
    state: &AppState,
    device_id: i64,
    apps: &[InstalledApp],
    logos: &[(String, String)],
) {
    let lost = match apply_rows(&state.db, device_id, apps, logos).await {
        Ok(lost) => lost,
        Err(err) => {
            tracing::error!(device_id, %err, "can't store the phone's app logos");
            return;
        }
    };
    prune_hashes(state, &lost).await;
}

/// [intake]'s rows in one transaction; the hashes that lost a row.
async fn apply_rows(
    db: &sqlx::SqlitePool,
    device_id: i64,
    apps: &[InstalledApp],
    logos: &[(String, String)],
) -> Result<Vec<String>, sqlx::Error> {
    let installed: HashSet<&str> = apps.iter().map(|app| app.package_name.as_str()).collect();
    let reported: HashMap<&str, &str> = logos
        .iter()
        .map(|(package, hash)| (package.as_str(), hash.as_str()))
        .collect();
    let mut tx = db.begin().await?;
    let rows: Vec<(String, String)> =
        sqlx::query_as("SELECT package_name, logo_hash FROM device_app_logos WHERE device_id = ?")
            .bind(device_id)
            .fetch_all(&mut *tx)
            .await?;
    let mut lost = Vec::new();
    for (package, hash) in &rows {
        if !installed.contains(package.as_str()) {
            sqlx::query("DELETE FROM device_app_logos WHERE device_id = ? AND package_name = ?")
                .bind(device_id)
                .bind(package)
                .execute(&mut *tx)
                .await?;
            lost.push(hash.clone());
        } else if let Some(new) = reported.get(package.as_str()).copied()
            && new != hash.as_str()
        {
            sqlx::query(
                "UPDATE device_app_logos SET logo_hash = ?, updated_at = datetime('now') \
                 WHERE device_id = ? AND package_name = ?",
            )
            .bind(new)
            .bind(device_id)
            .bind(package)
            .execute(&mut *tx)
            .await?;
            lost.push(hash.clone());
        }
    }
    let known: HashSet<&str> = rows.iter().map(|(package, _)| package.as_str()).collect();
    for (package, hash) in logos {
        if !known.contains(package.as_str()) {
            sqlx::query(
                "INSERT INTO device_app_logos (device_id, package_name, logo_hash, updated_at) \
                 VALUES (?, ?, ?, datetime('now'))",
            )
            .bind(device_id)
            .bind(package)
            .bind(hash)
            .execute(&mut *tx)
            .await?;
        }
    }
    tx.commit().await?;
    Ok(lost)
}

/// The answer to a status report: the wanted list to a launcher claiming [CAPABILITY], else `204` as before.
pub async fn answer(
    state: &AppState,
    device_id: i64,
    capabilities: &[String],
    logos: &[(String, String)],
) -> Response {
    if !capabilities.iter().any(|c| c == CAPABILITY) {
        return StatusCode::NO_CONTENT.into_response();
    }
    let wanted_logos = wanted(state, device_id, logos).await;
    Json(serde_json::json!({ "wanted_logos": wanted_logos })).into_response()
}

/// The hashes of `logos` to ask this phone for (design 87 §4.2), allowed apps first, then by package name.
async fn wanted(state: &AppState, device_id: i64, logos: &[(String, String)]) -> Vec<String> {
    let now = Instant::now();
    let allowlist: Option<String> =
        sqlx::query_scalar("SELECT allowlist_json FROM device_policy WHERE device_id = ?")
            .bind(device_id)
            .fetch_optional(&state.db)
            .await
            .ok()
            .flatten()
            .flatten();
    let allowed: HashSet<String> = allowlist
        .as_deref()
        .and_then(|json| serde_json::from_str::<Vec<String>>(json).ok())
        .unwrap_or_default()
        .into_iter()
        .collect();
    let mut ordered: Vec<&(String, String)> = logos.iter().collect();
    ordered.sort_by(|a, b| (!allowed.contains(&a.0), &a.0).cmp(&(!allowed.contains(&b.0), &b.0)));
    let mut seen = HashSet::new();
    let mut candidates = Vec::new();
    for (_, hash) in ordered {
        if !seen.insert(hash.as_str()) || state.app_logos.is_refused(hash, now) {
            continue;
        }
        let Some(path) = path_for(&state.app_logo_dir, hash) else {
            continue;
        };
        if !is_stored(&path).await {
            candidates.push(hash.clone());
        }
    }
    if candidates.is_empty() {
        return candidates;
    }
    let room = room(state, device_id, now).await;
    candidates.truncate(room.min(MAX_WANTED));
    candidates
}

/// How many more logos this phone may send now: its day's budget, the store's caps and its own share's. 0 on a
/// read error (fail closed: nothing is asked for).
async fn room(state: &AppState, device_id: i64, now: Instant) -> usize {
    let budget = state.app_logos.uploads_left(device_id, now) as usize;
    if budget == 0 {
        return 0;
    }
    budget.min(caps_room(state, device_id, now).await)
}

/// How many more files the store's caps and this phone's share leave; 0 on a read error.
async fn caps_room(state: &AppState, device_id: i64, now: Instant) -> usize {
    let Some((files, bytes)) = store_usage(&state.app_logo_dir).await else {
        if state.app_logos.log_due(None, now) {
            tracing::warn!("app_logos_full: can't read the app logo store, counted as full");
        }
        return 0;
    };
    if files >= MAX_LOGO_FILES || bytes >= MAX_LOGO_BYTES {
        if state.app_logos.log_due(None, now) {
            tracing::warn!(files, bytes, "app_logos_full: the app logo store is full");
        }
        return 0;
    }
    let (phone_files, phone_bytes) = match phone_usage(state, device_id).await {
        Ok(usage) => usage,
        Err(err) => {
            tracing::error!(device_id, %err, "can't read the phone's app logos");
            return 0;
        }
    };
    if phone_files >= MAX_LOGOS_PER_PHONE || phone_bytes >= MAX_LOGO_BYTES_PER_PHONE {
        if state.app_logos.log_due(Some(device_id), now) {
            tracing::warn!(
                device_id,
                files = phone_files,
                bytes = phone_bytes,
                "app_logos_phone_full: the phone's share of the app logo store is full"
            );
        }
        return 0;
    }
    (MAX_LOGO_FILES - files).min(MAX_LOGOS_PER_PHONE - phone_files)
}

/// The stored logos: how many files and their bytes. `None` when the directory can't be read (anything but its
/// absence, which is an empty store): the caller counts it as full (fail closed). A directory listing is at most
/// [MAX_LOGO_FILES] entries, so it is read each time rather than kept in memory, where a file removed by hand or a
/// restore would leave the count wrong.
async fn store_usage(dir: &Path) -> Option<(usize, u64)> {
    let (mut files, mut bytes) = (0, 0);
    let mut entries = match tokio::fs::read_dir(dir).await {
        Ok(entries) => entries,
        Err(err) if err.kind() == std::io::ErrorKind::NotFound => return Some((0, 0)),
        Err(_) => return None,
    };
    while let Some(entry) = entries.next_entry().await.ok()? {
        if entry.file_name().to_string_lossy().ends_with(".png") {
            files += 1;
            bytes += entry.metadata().await.ok()?.len();
        }
    }
    Some((files, bytes))
}

/// The stored files this phone's rows name: how many and their bytes.
async fn phone_usage(state: &AppState, device_id: i64) -> Result<(usize, u64), sqlx::Error> {
    let hashes: Vec<String> =
        sqlx::query_scalar("SELECT DISTINCT logo_hash FROM device_app_logos WHERE device_id = ?")
            .bind(device_id)
            .fetch_all(&state.db)
            .await?;
    let (mut files, mut bytes) = (0, 0);
    for hash in hashes {
        if let Some(path) = path_for(&state.app_logo_dir, &hash)
            && let Ok(meta) = tokio::fs::metadata(&path).await
        {
            files += 1;
            bytes += meta.len();
        }
    }
    Ok((files, bytes))
}

/// Whether one of `device_id`'s rows names `hash`.
async fn names(db: &sqlx::SqlitePool, device_id: i64, hash: &str) -> Result<bool, sqlx::Error> {
    sqlx::query_scalar(
        "SELECT EXISTS (SELECT 1 FROM device_app_logos WHERE device_id = ? AND logo_hash = ?)",
    )
    .bind(device_id)
    .bind(hash)
    .fetch_one(db)
    .await
}

/// `POST /api/devices/app-logos/{hash}` (design 87 §4.3): the checks in the table's order - nothing is decoded
/// before the hash and the phone's own row agree, and the budget is charged before the decode.
pub async fn upload(
    State(state): State<AppState>,
    Extension(AuthedDevice(device)): Extension<AuthedDevice>,
    UrlPath(hash): UrlPath<String>,
    headers: HeaderMap,
    body: Bytes,
) -> Response {
    let Some(path) = path_for(&state.app_logo_dir, &hash) else {
        return StatusCode::BAD_REQUEST.into_response();
    };
    let png = headers
        .get(header::CONTENT_TYPE)
        .and_then(|value| value.to_str().ok())
        .and_then(|value| value.split(';').next())
        .is_some_and(|media| media.trim().eq_ignore_ascii_case("image/png"));
    if !png {
        return StatusCode::UNSUPPORTED_MEDIA_TYPE.into_response();
    }
    if body.len() > MAX_BYTES {
        return StatusCode::PAYLOAD_TOO_LARGE.into_response();
    }
    if hex::encode(Sha256::digest(&body)) != hash {
        return StatusCode::BAD_REQUEST.into_response();
    }
    match names(&state.db, device.id, &hash).await {
        Ok(true) => {}
        Ok(false) => return StatusCode::CONFLICT.into_response(),
        Err(err) => {
            tracing::error!(device_id = device.id, %err, "can't read the phone's app logos");
            return StatusCode::INTERNAL_SERVER_ERROR.into_response();
        }
    }
    if is_stored(&path).await {
        return StatusCode::NO_CONTENT.into_response();
    }
    // Refused within the day: refused again at once, nothing decoded or charged.
    let now = Instant::now();
    if state.app_logos.is_refused(&hash, now) {
        return StatusCode::UNPROCESSABLE_ENTITY.into_response();
    }
    if room(&state, device.id, now).await == 0 || !state.app_logos.charge(device.id, now) {
        return StatusCode::CONFLICT.into_response();
    }
    let encoded = {
        let _permit = crate::photos::PROCESSING
            .acquire()
            .await
            .expect("semaphore never closed");
        tokio::task::spawn_blocking(move || reencode(&body))
            .await
            .unwrap_or_else(|err| {
                tracing::error!(%err, "app logo decoding task failed");
                None
            })
    };
    let Some(encoded) = encoded else {
        state.app_logos.refuse(&hash, now);
        tracing::debug!(device_id = device.id, hash = %hash, "refused an app logo");
        return StatusCode::UNPROCESSABLE_ENTITY.into_response();
    };
    let _files = APP_LOGO_FILES.lock().await;
    // Its row again, under the lock: a report that dropped it since has pruned already or prunes after this.
    match names(&state.db, device.id, &hash).await {
        Ok(true) => {}
        Ok(false) => return StatusCode::CONFLICT.into_response(),
        Err(err) => {
            tracing::error!(device_id = device.id, %err, "can't read the phone's app logos");
            return StatusCode::INTERNAL_SERVER_ERROR.into_response();
        }
    }
    // The caps again, under the lock: parallel uploads write one at a time, so none passes a cap another has
    // just filled (the budget was charged above).
    if !is_stored(&path).await && caps_room(&state, device.id, now).await == 0 {
        return StatusCode::CONFLICT.into_response();
    }
    match write(&state.app_logo_dir, &hash, &path, &encoded).await {
        Ok(()) => StatusCode::NO_CONTENT.into_response(),
        Err(err) => {
            tracing::error!(device_id = device.id, %err, "can't store an app logo");
            StatusCode::INTERNAL_SERVER_ERROR.into_response()
        }
    }
}

/// A temp file, synced to disk, then a rename (a reader never sees half a file, a power cut no empty one); a stored
/// file is left alone.
async fn write(dir: &Path, hash: &str, path: &Path, bytes: &[u8]) -> std::io::Result<()> {
    use tokio::io::AsyncWriteExt as _;
    if is_stored(path).await {
        return Ok(());
    }
    tokio::fs::create_dir_all(dir).await?;
    let tmp = dir.join(format!(".{hash}.tmp"));
    let mut file = tokio::fs::File::create(&tmp).await?;
    file.write_all(bytes).await?;
    file.sync_all().await?;
    drop(file);
    tokio::fs::rename(&tmp, path).await
}

/// The phone's PNG decoded under limits and written again by the server's own encoder (R2-L2): the PNG signature
/// first (nothing else is parsed), the PNG decoder only - never a guessed format -, the IHDR's size checked against
/// [SIZE_PX] before any buffer, exactly [SIZE_PX] square, an APNG's default image only, always 8-bit RGBA out.
/// `None` for anything else. CPU work: on a blocking thread, under `photos::PROCESSING`.
pub(crate) fn reencode(bytes: &[u8]) -> Option<Vec<u8>> {
    if !bytes.starts_with(&PNG_SIGNATURE) {
        return None;
    }
    let decoder = PngDecoder::with_limits(Cursor::new(bytes), decode_limits()).ok()?;
    if decoder.dimensions() != (SIZE_PX, SIZE_PX) {
        return None;
    }
    let rgba = DynamicImage::from_decoder(decoder).ok()?.to_rgba8();
    let mut out = Vec::new();
    PngEncoder::new(&mut out)
        .write_image(rgba.as_raw(), SIZE_PX, SIZE_PX, ExtendedColorType::Rgba8)
        .ok()?;
    Some(out)
}

/// The decoder's limits: [SIZE_PX] a side (checked against the IHDR before any buffer) and [MAX_DECODE_ALLOC].
fn decode_limits() -> Limits {
    let mut limits = Limits::default();
    limits.max_image_width = Some(SIZE_PX);
    limits.max_image_height = Some(SIZE_PX);
    limits.max_alloc = Some(MAX_DECODE_ALLOC);
    limits
}

/// Deletes the files of `hashes` no row names any more. Best effort: a failure is logged.
pub async fn prune_hashes(state: &AppState, hashes: &[String]) {
    if hashes.is_empty() {
        return;
    }
    let _files = APP_LOGO_FILES.lock().await;
    for hash in hashes {
        let Some(path) = path_for(&state.app_logo_dir, hash) else {
            continue;
        };
        let named: Result<bool, _> = sqlx::query_scalar(
            "SELECT EXISTS (SELECT 1 FROM device_app_logos WHERE logo_hash = ?)",
        )
        .bind(hash)
        .fetch_one(&state.db)
        .await;
        match named {
            Ok(false) => remove(&path).await,
            Ok(true) => {}
            Err(err) => tracing::warn!(%err, "can't check an app logo before deleting it"),
        }
    }
}

/// The retention pass's full scan: every file no row names, and a temp file older than an hour (a crash between a
/// commit and its prune leaves no orphan for long).
pub async fn prune_all(state: &AppState) {
    let _files = APP_LOGO_FILES.lock().await;
    let named: HashSet<String> =
        match sqlx::query_scalar("SELECT DISTINCT logo_hash FROM device_app_logos")
            .fetch_all(&state.db)
            .await
        {
            Ok(hashes) => hashes.into_iter().collect(),
            Err(err) => {
                tracing::warn!(%err, "can't list the app logos to prune");
                return;
            }
        };
    let Ok(mut entries) = tokio::fs::read_dir(&*state.app_logo_dir).await else {
        return;
    };
    while let Ok(Some(entry)) = entries.next_entry().await {
        let name = entry.file_name().to_string_lossy().into_owned();
        let unused = if name.ends_with(".tmp") {
            entry
                .metadata()
                .await
                .ok()
                .and_then(|meta| meta.modified().ok())
                .and_then(|at| at.elapsed().ok())
                .is_some_and(|age| age > STRAY_TEMP_AGE)
        } else {
            name.strip_suffix(".png")
                .is_some_and(|hash| !named.contains(hash))
        };
        if unused {
            remove(&entry.path()).await;
        }
    }
}

async fn remove(path: &Path) {
    if let Err(err) = tokio::fs::remove_file(path).await
        && err.kind() != std::io::ErrorKind::NotFound
    {
        tracing::warn!(%err, file = %path.display(), "can't delete an unused app logo");
    }
}

/// The hashes `device_id`'s rows name, read before its delete (the rows go with the phone): [prune_hashes] then
/// deletes the files no other phone names.
pub async fn hashes_of(db: &sqlx::SqlitePool, device_id: i64) -> Vec<String> {
    sqlx::query_scalar("SELECT DISTINCT logo_hash FROM device_app_logos WHERE device_id = ?")
        .bind(device_id)
        .fetch_all(db)
        .await
        .unwrap_or_else(|err| {
            tracing::warn!(device_id, %err, "can't list the phone's app logos");
            Vec::new()
        })
}

/// `GET /app-logos/{hash}`, behind the parent's session: only a valid hash some row names and whose file exists,
/// else 404 (no listing). Content-addressed, so cached for good.
pub async fn view(State(state): State<AppState>, UrlPath(hash): UrlPath<String>) -> Response {
    let Some(path) = path_for(&state.app_logo_dir, &hash) else {
        return StatusCode::NOT_FOUND.into_response();
    };
    let named: Result<bool, _> =
        sqlx::query_scalar("SELECT EXISTS (SELECT 1 FROM device_app_logos WHERE logo_hash = ?)")
            .bind(&hash)
            .fetch_one(&state.db)
            .await;
    if !matches!(named, Ok(true)) {
        return StatusCode::NOT_FOUND.into_response();
    }
    match tokio::fs::read(&path).await {
        Ok(bytes) if !bytes.is_empty() => (
            [
                (header::CONTENT_TYPE, "image/png"),
                (header::X_CONTENT_TYPE_OPTIONS, "nosniff"),
                (
                    header::CACHE_CONTROL,
                    "private, max-age=31536000, immutable",
                ),
            ],
            bytes,
        )
            .into_response(),
        _ => StatusCode::NOT_FOUND.into_response(),
    }
}

/// This phone's logos for the PWA (§8.1, Q3: per phone): package -> URL, only where the file exists.
pub async fn phone_logos(
    state: &AppState,
    device_id: i64,
) -> Result<HashMap<String, String>, sqlx::Error> {
    let rows: Vec<(String, String)> =
        sqlx::query_as("SELECT package_name, logo_hash FROM device_app_logos WHERE device_id = ?")
            .bind(device_id)
            .fetch_all(&state.db)
            .await?;
    let mut logos = HashMap::new();
    for (package, hash) in rows {
        if let Some(path) = path_for(&state.app_logo_dir, &hash)
            && is_stored(&path).await
        {
            logos.insert(package, src_of(&hash));
        }
    }
    Ok(logos)
}

/// The catalog rows' logos (§8.1, R2-L4): per package the hash most rows name, a tie to the most recently
/// updated, whose file exists - package -> URL.
pub async fn catalog_logos(state: &AppState) -> Result<HashMap<String, String>, sqlx::Error> {
    let rows: Vec<(String, String)> = sqlx::query_as(
        "SELECT package_name, logo_hash FROM device_app_logos \
         GROUP BY package_name, logo_hash \
         ORDER BY package_name, COUNT(*) DESC, MAX(updated_at) DESC, logo_hash",
    )
    .fetch_all(&state.db)
    .await?;
    let mut logos = HashMap::new();
    for (package, hash) in rows {
        if logos.contains_key(&package) {
            continue;
        }
        if let Some(path) = path_for(&state.app_logo_dir, &hash)
            && is_stored(&path).await
        {
            logos.insert(package, src_of(&hash));
        }
    }
    Ok(logos)
}

#[cfg(test)]
mod tests {
    use super::*;

    /// A PNG of `side` x `side` in `color` (the image crate's encoder).
    fn png(side: u32, color: image::ExtendedColorType) -> Vec<u8> {
        let channels = color.channel_count() as usize;
        let bytes_per = (color.bits_per_pixel() as usize / 8) / channels;
        let raw = vec![0x7Fu8; side as usize * side as usize * channels * bytes_per];
        let mut out = Vec::new();
        PngEncoder::new(&mut out)
            .write_image(&raw, side, side, color)
            .unwrap();
        out
    }

    fn crc32(bytes: &[u8]) -> u32 {
        let mut crc = !0u32;
        for byte in bytes {
            crc ^= u32::from(*byte);
            for _ in 0..8 {
                crc = if crc & 1 == 1 {
                    (crc >> 1) ^ 0xEDB8_8320
                } else {
                    crc >> 1
                };
            }
        }
        !crc
    }

    fn chunk(kind: &[u8], data: &[u8]) -> Vec<u8> {
        let mut body = kind.to_vec();
        body.extend_from_slice(data);
        let mut out = (data.len() as u32).to_be_bytes().to_vec();
        out.extend_from_slice(&body);
        out.extend_from_slice(&crc32(&body).to_be_bytes());
        out
    }

    /// A [SIZE_PX] px 8-bit PNG built chunk by chunk: its colour type and interlace method, the chunks between IHDR
    /// and IDAT, and the filtered scanlines (deflated here).
    fn raw_png(
        color_type: u8,
        interlace: u8,
        before_idat: &[Vec<u8>],
        scanlines: &[u8],
    ) -> Vec<u8> {
        use std::io::Write as _;
        let mut ihdr = SIZE_PX.to_be_bytes().to_vec();
        ihdr.extend_from_slice(&SIZE_PX.to_be_bytes());
        ihdr.extend_from_slice(&[8, color_type, 0, 0, interlace]);
        let mut zlib = flate2::write::ZlibEncoder::new(Vec::new(), flate2::Compression::default());
        zlib.write_all(scanlines).unwrap();
        let mut out = PNG_SIGNATURE.to_vec();
        out.extend(chunk(b"IHDR", &ihdr));
        for extra in before_idat {
            out.extend_from_slice(extra);
        }
        out.extend(chunk(b"IDAT", &zlib.finish().unwrap()));
        out.extend(chunk(b"IEND", &[]));
        out
    }

    /// `rows` rows of `width` copies of `pixel`, each with filter byte 0.
    fn scanlines(rows: u32, width: u32, pixel: &[u8]) -> Vec<u8> {
        let mut out = Vec::new();
        for _ in 0..rows {
            out.push(0);
            out.extend(pixel.repeat(width as usize));
        }
        out
    }

    /// The re-encoded logo's top-left pixel, after checking it is a 128 px 8-bit RGBA PNG.
    fn first_pixel(out: &[u8]) -> [u8; 4] {
        let image = image::load_from_memory_with_format(out, image::ImageFormat::Png).unwrap();
        assert_eq!((image.width(), image.height()), (SIZE_PX, SIZE_PX));
        assert_eq!(image.color(), image::ColorType::Rgba8);
        image.to_rgba8().get_pixel(0, 0).0
    }

    #[test]
    fn palette_interlaced_and_animated_logos_become_one_rgba_image() {
        // A palette PNG with a transparent entry: RGBA with that alpha.
        let palette = raw_png(
            3,
            0,
            &[chunk(b"PLTE", &[0x10, 0x20, 0x30]), chunk(b"tRNS", &[0x80])],
            &scanlines(SIZE_PX, SIZE_PX, &[0]),
        );
        assert_eq!(
            first_pixel(&reencode(&palette).unwrap()),
            [0x10, 0x20, 0x30, 0x80]
        );

        // Adam7: the seven passes' rows.
        let pixel = [0x11, 0x22, 0x33, 0xFF];
        let mut passes = Vec::new();
        for (x0, y0, dx, dy) in [
            (0, 0, 8, 8),
            (4, 0, 8, 8),
            (0, 4, 4, 8),
            (2, 0, 4, 4),
            (0, 2, 2, 4),
            (1, 0, 2, 2),
            (0, 1, 1, 2),
        ] {
            let (w, h) = ((SIZE_PX - x0).div_ceil(dx), (SIZE_PX - y0).div_ceil(dy));
            passes.extend(scanlines(h, w, &pixel));
        }
        let interlaced = raw_png(6, 1, &[], &passes);
        assert_eq!(first_pixel(&reencode(&interlaced).unwrap()), pixel);

        // An APNG: its default image only, stored as a plain PNG (no animation chunks).
        let mut actl = 1u32.to_be_bytes().to_vec();
        actl.extend_from_slice(&0u32.to_be_bytes());
        let mut fctl = 0u32.to_be_bytes().to_vec();
        for value in [SIZE_PX, SIZE_PX, 0, 0] {
            fctl.extend_from_slice(&value.to_be_bytes());
        }
        fctl.extend_from_slice(&[0, 1, 0, 10, 0, 0]);
        let apng = raw_png(
            6,
            0,
            &[chunk(b"acTL", &actl), chunk(b"fcTL", &fctl)],
            &scanlines(SIZE_PX, SIZE_PX, &pixel),
        );
        let out = reencode(&apng).unwrap();
        assert_eq!(first_pixel(&out), pixel);
        assert!(!out.windows(4).any(|w| w == b"acTL" || w == b"fcTL"));
    }

    /// A PNG that is only a signature and an IHDR saying `width` x `height` (8-bit RGBA), then IEND.
    fn header_only(width: u32, height: u32) -> Vec<u8> {
        let mut out = PNG_SIGNATURE.to_vec();
        let mut chunk = b"IHDR".to_vec();
        chunk.extend_from_slice(&width.to_be_bytes());
        chunk.extend_from_slice(&height.to_be_bytes());
        chunk.extend_from_slice(&[8, 6, 0, 0, 0]);
        out.extend_from_slice(&13u32.to_be_bytes());
        out.extend_from_slice(&chunk);
        out.extend_from_slice(&crc32(&chunk).to_be_bytes());
        out.extend_from_slice(&0u32.to_be_bytes());
        out.extend_from_slice(b"IEND");
        out.extend_from_slice(&crc32(b"IEND").to_be_bytes());
        out
    }

    #[test]
    fn a_logo_is_decoded_under_limits_and_written_again() {
        // The right size in 8 and 16 bits: always 8-bit RGBA out.
        for color in [ExtendedColorType::Rgba8, ExtendedColorType::Rgb16] {
            let out = reencode(&png(SIZE_PX, color)).expect("a 128 px PNG");
            let image = image::load_from_memory_with_format(&out, image::ImageFormat::Png).unwrap();
            assert_eq!((image.width(), image.height()), (SIZE_PX, SIZE_PX));
            assert_eq!(image.color(), image::ColorType::Rgba8);
        }
        // Another size, a huge IHDR (refused before any buffer), no signature, another format.
        assert_eq!(reencode(&png(64, ExtendedColorType::Rgba8)), None);
        assert_eq!(reencode(&header_only(10_000, 10_000)), None);
        // ...refused by the decoder's own limits, from the IHDR alone.
        assert!(matches!(
            PngDecoder::with_limits(Cursor::new(header_only(10_000, 10_000)), decode_limits()),
            Err(image::ImageError::Limits(_))
        ));
        assert_eq!(reencode(b"not a png at all"), None);
        assert_eq!(reencode(&png(SIZE_PX, ExtendedColorType::Rgba8)[8..]), None);
        // Bytes after IEND don't survive.
        let mut appended = png(SIZE_PX, ExtendedColorType::Rgba8);
        appended.extend_from_slice(b"trailing bytes");
        let out = reencode(&appended).unwrap();
        assert!(!out.windows(8).any(|w| w == b"trailing"));
    }

    #[test]
    fn the_budget_and_refusals_last_a_day() {
        let memory = Memory::default();
        let now = Instant::now();
        for _ in 0..UPLOADS_A_DAY {
            assert!(memory.charge(1, now));
        }
        assert!(!memory.charge(1, now), "the 301st upload of a day");
        assert_eq!(memory.uploads_left(1, now), 0);
        assert_eq!(memory.uploads_left(2, now), UPLOADS_A_DAY);
        assert!(memory.charge(1, now + DAY), "a new day");
        memory.refuse("a", now);
        assert!(memory.is_refused("a", now + DAY - Duration::from_secs(1)));
        assert!(!memory.is_refused("a", now + DAY));
        assert!(memory.log_due(None, now));
        assert!(!memory.log_due(None, now));
        assert!(memory.log_due(Some(1), now));
    }
}
