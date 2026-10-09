//! Contact photos (design docs/design/05-ui-photos-i18n.md in the handy workspace) and wallpaper
//! images (08-ui-polish.md) - two separate stores ([Store]) that share the processing.
//!
//! An uploaded photo is never stored as sent: it is decoded (JPEG, PNG or WebP, detected from the
//! bytes, not the file name), turned upright from its EXIF orientation, centre-cropped to a square,
//! shrunk to at most [`MAX_SIDE`] pixels and encoded again as JPEG. Re-encoding drops every bit of
//! metadata (EXIF with GPS position, XMP, comments) and anything that isn't pixels. The file is
//! named after the SHA-256 of what was stored (`<hash>.jpg` in `AppState.photo_dir`), and that hash
//! is what `contacts.photo_hash` and the phone's `call_policy` carry - the phone caches by it and
//! checks it after download.
//!
//! Wallpapers ([Shape::Portrait]) are turned upright first, then centre-cropped to the phone's
//! 9:20 shape and shrunk to at most 1080×2400; they live in their own directory
//! (`AppState.wallpaper_dir`) with their own lock, prune, backup prefix and recovery
//! ([WALLPAPERS]) - a prune of one store never looks at the other's files.

use std::io::Cursor;
use std::path::{Path, PathBuf};

use image::{DynamicImage, ImageDecoder, ImageError, ImageFormat, ImageReader, Limits, imageops};
use sha2::{Digest, Sha256};
use tokio::sync::{Mutex, MutexGuard, Semaphore};

/// Largest upload accepted (the route's body limit is a bit higher, for the multipart framing).
pub const MAX_UPLOAD_BYTES: usize = 10 * 1024 * 1024;
/// Largest side of a stored photo. The biggest view on the phone is 132 dp (~400 px at xxhdpi).
pub const MAX_SIDE: u32 = 512;
/// A stored wallpaper: the phone's shape (9:20), at most this size - a 1080p-class screen.
pub const WALLPAPER_WIDTH: u32 = 1080;
pub const WALLPAPER_HEIGHT: u32 = 2400;
/// Largest stored wallpaper: the phone refuses anything bigger (`WallpaperCache`, 3 MB).
pub const MAX_WALLPAPER_BYTES: usize = 3 * 1024 * 1024;
const MAX_INPUT_SIDE: u32 = 8000;
/// 40 megapixels - more than any phone camera's default, and checked before decoding.
const MAX_INPUT_PIXELS: u64 = 40_000_000;
/// The decoder's own buffer (a 40 MP RGB JPEG is 120 MB). Everything after it works on the
/// ≤ 512 px crop, so this is about the peak per upload - and [PROCESSING] allows one at a time.
const MAX_DECODE_ALLOC: u64 = 128 * 1024 * 1024;
/// Biggest stored contact photo accepted back from a backup zip ([recover_missing]).
const MAX_STORED_BYTES: u64 = 2 * 1024 * 1024;

/// One photo is decoded at a time: a Pi has little memory and uploads are rare.
static PROCESSING: Semaphore = Semaphore::const_new(1);

/// Serialises everything that writes photo files or changes which ones are referenced:
/// store + `photo_hash` commit, [prune], [recover_missing]. Without it a prune running between
/// another request's store and its commit would delete the new file.
static FILES: Mutex<()> = Mutex::const_new(());
/// The same for wallpaper images (store + `wallpapers` insert, prune, recovery).
static WALLPAPER_FILES: Mutex<()> = Mutex::const_new(());
/// The same for music covers (design 21: entry covers and own files' embedded art).
static MUSIC_COVER_FILES: Mutex<()> = Mutex::const_new(());

/// One content-addressed image store: its lock, which hashes the database references, what a
/// lost file does to the database, its backup zip prefix and its size limit.
pub struct Store {
    pub what: &'static str,
    lock: &'static Mutex<()>,
    referenced_sql: &'static str,
    /// Run in order, each with the hash bound, when a referenced file is in no backup.
    forget_sql: &'static [&'static str],
    zip_prefix: &'static str,
    max_stored_bytes: u64,
}

/// Contact photos (`AppState.photo_dir`, `contacts.photo_hash`).
pub static CONTACT_PHOTOS: Store = Store {
    what: "contact photo",
    lock: &FILES,
    referenced_sql: "SELECT DISTINCT photo_hash FROM contacts WHERE photo_hash IS NOT NULL",
    forget_sql: &["UPDATE contacts SET photo_hash = NULL WHERE photo_hash = ?"],
    zip_prefix: "contact_photos",
    max_stored_bytes: MAX_STORED_BYTES,
};

/// Wallpaper images (`AppState.wallpaper_dir`, `wallpapers.image_hash`). A wallpaper whose image
/// is lost is deleted (its `device_wallpapers` rows go with it), so no phone asks for it.
pub static WALLPAPERS: Store = Store {
    what: "wallpaper",
    lock: &WALLPAPER_FILES,
    referenced_sql: "SELECT DISTINCT image_hash FROM wallpapers WHERE image_hash IS NOT NULL",
    forget_sql: &["DELETE FROM wallpapers WHERE image_hash = ?"],
    zip_prefix: "wallpapers",
    max_stored_bytes: MAX_WALLPAPER_BYTES as u64,
};

/// Music covers (`AppState.music_cover_dir`, design 21): a parent's cover for an entry
/// (`music_entries.cover_hash`) and an own file's embedded art (`music_files.art_hash`), both square
/// JPEGs of at most [MAX_SIDE]. Backed up (unlike the audio); a lost one is dropped from the rows.
pub static MUSIC_COVERS: Store = Store {
    what: "music cover",
    lock: &MUSIC_COVER_FILES,
    referenced_sql: "SELECT cover_hash FROM music_entries WHERE cover_hash IS NOT NULL \
                     UNION SELECT art_hash FROM music_files WHERE art_hash IS NOT NULL",
    forget_sql: &[
        "UPDATE music_entries SET cover_hash = NULL WHERE cover_hash = ?",
        "UPDATE music_files SET art_hash = NULL WHERE art_hash = ?",
    ],
    zip_prefix: "music_covers",
    max_stored_bytes: MAX_STORED_BYTES,
};

impl Store {
    /// Take before storing a file and committing its hash; release before calling [Store::prune].
    pub async fn lock(&self) -> MutexGuard<'static, ()> {
        self.lock.lock().await
    }

    /// Zip entry name of a stored file in a backup.
    pub fn zip_entry(&self, hash: &str) -> String {
        format!("{}/{hash}.jpg", self.zip_prefix)
    }
}

/// Take before storing a photo and committing its hash; release before calling [prune].
pub async fn lock_files() -> MutexGuard<'static, ()> {
    CONTACT_PHOTOS.lock().await
}
const JPEG_QUALITY: u8 = 85;

#[derive(Debug, PartialEq, Eq)]
pub enum PhotoError {
    Empty,
    TooLarge,
    UnsupportedType,
    TooManyPixels,
    Undecodable,
    /// Re-encoded, the image is still over [MAX_WALLPAPER_BYTES] (a very noisy photo).
    TooDetailed,
}

/// What an upload is turned into.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Shape {
    /// A contact photo: centre square, at most [MAX_SIDE] a side.
    Square,
    /// A wallpaper: upright, centre-cropped to the phone's 9:20 and at most
    /// [WALLPAPER_WIDTH]×[WALLPAPER_HEIGHT] - never enlarged.
    Portrait,
}

impl PhotoError {
    /// Parent-facing text for the calls page.
    pub fn message(&self) -> &'static str {
        match self {
            PhotoError::Empty => "Choose a photo first.",
            PhotoError::TooLarge => "That photo is too big (at most 10 MB).",
            PhotoError::UnsupportedType => "Use a JPEG, PNG or WebP photo.",
            PhotoError::TooManyPixels => {
                "That photo has too many pixels (at most 40 megapixels and 8000 pixels a side)."
            }
            PhotoError::Undecodable => "That photo couldn't be read - try another one.",
            PhotoError::TooDetailed => {
                "That photo is too detailed to send to a phone (over 3 MB as a wallpaper) - try another one."
            }
        }
    }
}

/// A photo ready to store: JPEG bytes and their SHA-256 (lowercase hex).
pub struct ProcessedPhoto {
    pub jpeg: Vec<u8>,
    pub hash: String,
}

/// [process] on a blocking thread, one upload at a time (photos and wallpapers together).
pub async fn process_limited(
    bytes: axum::body::Bytes,
    shape: Shape,
) -> Result<ProcessedPhoto, PhotoError> {
    let _permit = PROCESSING.acquire().await.expect("semaphore never closed");
    tokio::task::spawn_blocking(move || process(&bytes, shape))
        .await
        .unwrap_or_else(|err| {
            tracing::error!(%err, "photo processing task failed");
            Err(PhotoError::Undecodable)
        })
}

fn decode_error(err: ImageError) -> PhotoError {
    match err {
        ImageError::Limits(_) => PhotoError::TooManyPixels,
        _ => PhotoError::Undecodable,
    }
}

/// Decodes, normalises and re-encodes an upload. CPU-heavy on a Pi: use [process_limited].
/// Memory: the decoded image once (≤ [MAX_DECODE_ALLOC]); the crop is a view, shrunk straight
/// to its target size, and only that small image is turned upright. That is correct for both
/// shapes because a *centred* crop commutes with every EXIF orientation: the crop is computed on
/// the upright size (width and height swapped for the 90° orientations, QA 08 #3) and mapped back.
pub fn process(bytes: &[u8], shape: Shape) -> Result<ProcessedPhoto, PhotoError> {
    if bytes.is_empty() {
        return Err(PhotoError::Empty);
    }
    if bytes.len() > MAX_UPLOAD_BYTES {
        return Err(PhotoError::TooLarge);
    }
    let mut reader = ImageReader::new(Cursor::new(bytes))
        .with_guessed_format()
        .map_err(|_| PhotoError::Undecodable)?;
    match reader.format() {
        Some(ImageFormat::Jpeg | ImageFormat::Png | ImageFormat::WebP) => {}
        _ => return Err(PhotoError::UnsupportedType),
    }
    let mut limits = Limits::default();
    limits.max_image_width = Some(MAX_INPUT_SIDE);
    limits.max_image_height = Some(MAX_INPUT_SIDE);
    limits.max_alloc = Some(MAX_DECODE_ALLOC);
    reader.limits(limits);
    let mut decoder = reader.into_decoder().map_err(decode_error)?;
    let (width, height) = decoder.dimensions();
    if width == 0 || height == 0 {
        return Err(PhotoError::Undecodable);
    }
    if u64::from(width) * u64::from(height) > MAX_INPUT_PIXELS {
        return Err(PhotoError::TooManyPixels);
    }
    let orientation = decoder.orientation().map_err(decode_error)?;
    let image = DynamicImage::from_decoder(decoder).map_err(decode_error)?;

    let swaps = swaps_axes(orientation);
    let (upright_w, upright_h) = if swaps {
        (height, width)
    } else {
        (width, height)
    };
    // Crop and target in upright coordinates...
    let ((crop_w, crop_h), (target_w, target_h)) = match shape {
        Shape::Square => {
            let side = upright_w.min(upright_h);
            let target = side.min(MAX_SIDE);
            ((side, side), (target, target))
        }
        Shape::Portrait => portrait_crop(upright_w, upright_h),
    };
    // ...mapped back to the stored pixels.
    let (raw_crop_w, raw_crop_h, raw_target_w, raw_target_h) = if swaps {
        (crop_h, crop_w, target_h, target_w)
    } else {
        (crop_w, crop_h, target_w, target_h)
    };
    let cropped = imageops::crop_imm(
        &image,
        (width - raw_crop_w) / 2,
        (height - raw_crop_h) / 2,
        raw_crop_w,
        raw_crop_h,
    );
    let small = if (raw_crop_w, raw_crop_h) != (raw_target_w, raw_target_h) {
        imageops::thumbnail(&*cropped, raw_target_w, raw_target_h)
    } else {
        cropped.to_image()
    };
    drop(image);
    let mut small = DynamicImage::ImageRgba8(small);
    small.apply_orientation(orientation);
    // JPEG has no alpha: flatten onto white rather than letting transparent pixels go black.
    let rgb = flatten_on_white(small);

    let limit = match shape {
        Shape::Square => usize::MAX,
        Shape::Portrait => MAX_WALLPAPER_BYTES,
    };
    for quality in [JPEG_QUALITY, 75, 65] {
        let mut jpeg = Vec::new();
        image::codecs::jpeg::JpegEncoder::new_with_quality(&mut jpeg, quality)
            .encode_image(&rgb)
            .map_err(|_| PhotoError::Undecodable)?;
        if jpeg.len() <= limit {
            let hash = hex::encode(Sha256::digest(&jpeg));
            return Ok(ProcessedPhoto { jpeg, hash });
        }
    }
    Err(PhotoError::TooDetailed)
}

/// Whether an EXIF orientation turns the image by 90° (upright width = stored height).
fn swaps_axes(orientation: image::metadata::Orientation) -> bool {
    use image::metadata::Orientation::*;
    matches!(
        orientation,
        Rotate90 | Rotate270 | Rotate90FlipH | Rotate270FlipH
    )
}

/// The centred 9:20 crop of an upright `w`×`h` image and the size it is shrunk to (at most
/// [WALLPAPER_WIDTH]×[WALLPAPER_HEIGHT], never enlarged): `((crop_w, crop_h), (out_w, out_h))`.
pub fn portrait_crop(w: u32, h: u32) -> ((u32, u32), (u32, u32)) {
    let (w64, h64) = (u64::from(w), u64::from(h));
    let (cw, ch) = if w64 * 20 > h64 * 9 {
        // Wider than the phone: full height, cut the sides.
        (((h64 * 9) / 20).max(1), h64)
    } else {
        // Taller: full width, cut top and bottom.
        (w64, ((w64 * 20) / 9).min(h64).max(1))
    };
    let (ow, oh) = if cw > u64::from(WALLPAPER_WIDTH) {
        (
            u64::from(WALLPAPER_WIDTH),
            (ch * u64::from(WALLPAPER_WIDTH) / cw).clamp(1, u64::from(WALLPAPER_HEIGHT)),
        )
    } else {
        (cw, ch)
    };
    ((cw as u32, ch as u32), (ow as u32, oh as u32))
}

fn flatten_on_white(image: DynamicImage) -> image::RgbImage {
    if !image.color().has_alpha() {
        return image.into_rgb8();
    }
    let rgba = image.into_rgba8();
    image::RgbImage::from_fn(rgba.width(), rgba.height(), |x, y| {
        let [r, g, b, a] = rgba.get_pixel(x, y).0;
        let blend =
            |c: u8| ((u16::from(c) * u16::from(a) + 255 * (255 - u16::from(a))) / 255) as u8;
        image::Rgb([blend(r), blend(g), blend(b)])
    })
}

/// A photo hash as we produce it: 64 lowercase hex characters. Anything else never reaches the
/// file system.
pub fn is_valid_hash(hash: &str) -> bool {
    hash.len() == 64 && hash.bytes().all(|b| matches!(b, b'0'..=b'9' | b'a'..=b'f'))
}

/// `<dir>/<hash>.jpg`, `None` for an invalid hash.
pub fn path_for(dir: &Path, hash: &str) -> Option<PathBuf> {
    is_valid_hash(hash).then(|| dir.join(format!("{hash}.jpg")))
}

/// Writes the photo (temp file + rename, so a reader never sees half a file). Content-addressed:
/// an existing file with the same hash is left alone.
pub async fn store(dir: &Path, photo: &ProcessedPhoto) -> std::io::Result<()> {
    tokio::fs::create_dir_all(dir).await?;
    let path = path_for(dir, &photo.hash).expect("hash computed by process()");
    if tokio::fs::try_exists(&path).await.unwrap_or(false) {
        return Ok(());
    }
    let tmp = dir.join(format!(".{}.tmp", photo.hash));
    tokio::fs::write(&tmp, &photo.jpeg).await?;
    tokio::fs::rename(&tmp, &path).await
}

/// Deletes every stored photo no contact references any more (after a replace, a remove, or a
/// contact/device delete). Best effort: a failure is logged, never an error for the parent.
/// Holds the file lock, so it never runs between another request's store and its commit.
pub async fn prune(state: &crate::AppState) {
    CONTACT_PHOTOS.prune(&state.db, &state.photo_dir).await;
}

/// Zip entry name of a stored photo in a backup.
#[cfg(test)]
pub fn zip_entry(hash: &str) -> String {
    CONTACT_PHOTOS.zip_entry(hash)
}

/// Adds every stored photo to a backup zip (blocking; called from `backups::build_backup_zip`).
pub fn add_to_zip<W: std::io::Write + std::io::Seek>(
    writer: &mut zip::ZipWriter<W>,
    dir: &Path,
) -> std::io::Result<()> {
    CONTACT_PHOTOS.add_to_zip(writer, dir)
}

/// After a restore (or any start): see [Store::recover_missing]. Run at startup for every store.
pub async fn recover_missing(state: &crate::AppState, backup_dir: &Path) {
    CONTACT_PHOTOS
        .recover_missing(&state.db, &state.photo_dir, backup_dir)
        .await;
    WALLPAPERS
        .recover_missing(&state.db, &state.wallpaper_dir, backup_dir)
        .await;
    MUSIC_COVERS
        .recover_missing(&state.db, &state.music_cover_dir, backup_dir)
        .await;
}

impl Store {
    /// Deletes every file in `dir` (and only there) the database no longer references. Best
    /// effort: a failure is logged. Holds this store's lock.
    pub async fn prune(&self, db: &sqlx::SqlitePool, dir: &Path) {
        let _files = self.lock().await;
        let referenced: Vec<String> =
            match sqlx::query_scalar(self.referenced_sql).fetch_all(db).await {
                Ok(hashes) => hashes,
                Err(err) => {
                    tracing::warn!(%err, what = self.what, "couldn't list stored files to prune");
                    return;
                }
            };
        let Ok(mut entries) = tokio::fs::read_dir(dir).await else {
            return;
        };
        while let Ok(Some(entry)) = entries.next_entry().await {
            let name = entry.file_name().to_string_lossy().into_owned();
            // Temp files only exist inside the lock, but never touch one anyway.
            let keep = name.ends_with(".tmp")
                || name
                    .strip_suffix(".jpg")
                    .is_some_and(|hash| referenced.iter().any(|r| r == hash));
            if !keep && let Err(err) = tokio::fs::remove_file(entry.path()).await {
                tracing::warn!(%err, file = name, what = self.what, "couldn't delete an unused file");
            }
        }
    }

    /// Adds every stored file to a backup zip under this store's prefix (blocking).
    /// Content-addressed, so a file is the same in every backup that has it.
    pub fn add_to_zip<W: std::io::Write + std::io::Seek>(
        &self,
        writer: &mut zip::ZipWriter<W>,
        dir: &Path,
    ) -> std::io::Result<()> {
        let Ok(entries) = std::fs::read_dir(dir) else {
            return Ok(()); // nothing stored yet
        };
        let options = zip::write::SimpleFileOptions::default()
            .compression_method(zip::CompressionMethod::Stored); // already JPEG
        for entry in entries.flatten() {
            let name = entry.file_name().to_string_lossy().into_owned();
            let Some(hash) = name.strip_suffix(".jpg").filter(|h| is_valid_hash(h)) else {
                continue;
            };
            // Read before starting the entry: a file deleted (pruned) since `read_dir` is simply
            // not in this backup, instead of failing the whole run (qa-08-code.md #8).
            let bytes = match std::fs::read(entry.path()) {
                Ok(bytes) => bytes,
                Err(err) if err.kind() == std::io::ErrorKind::NotFound => continue,
                Err(err) => return Err(err),
            };
            writer.start_file(self.zip_entry(hash), options)?;
            std::io::Write::write_all(writer, &bytes)?;
        }
        Ok(())
    }

    /// Every referenced hash whose file is missing is looked up in the backup zips in
    /// `backup_dir` (newest first) and extracted if its bytes really hash to that name; whatever
    /// can't be found is forgotten (contact photo → NULL, wallpaper → deleted), so the phones
    /// and the admin pages don't ask for a file that will never exist. Run at startup.
    pub async fn recover_missing(
        &'static self,
        db: &sqlx::SqlitePool,
        dir: &Path,
        backup_dir: &Path,
    ) {
        let _files = self.lock().await;
        let referenced: Vec<String> =
            match sqlx::query_scalar(self.referenced_sql).fetch_all(db).await {
                Ok(hashes) => hashes,
                Err(err) => {
                    tracing::warn!(%err, what = self.what, "couldn't list stored files to check");
                    return;
                }
            };
        let dir = dir.to_path_buf();
        let missing: Vec<String> = referenced
            .into_iter()
            .filter(|h| path_for(&dir, h).is_some_and(|p| !p.exists()))
            .collect();
        if missing.is_empty() {
            return;
        }
        let backup_dir = backup_dir.to_path_buf();
        let still_missing = tokio::task::spawn_blocking(move || {
            self.extract_from_backups(&dir, &backup_dir, missing)
        })
        .await
        .unwrap_or_default();
        for hash in still_missing {
            tracing::warn!(
                hash,
                what = self.what,
                "stored file missing and in no backup - removing the reference"
            );
            for sql in self.forget_sql {
                if let Err(err) = sqlx::query(sql).bind(&hash).execute(db).await {
                    tracing::warn!(%err, what = self.what, "couldn't forget a missing file");
                }
            }
        }
    }

    /// Returns the hashes it couldn't restore.
    fn extract_from_backups(
        &self,
        dir: &Path,
        backup_dir: &Path,
        mut missing: Vec<String>,
    ) -> Vec<String> {
        let mut zips: Vec<PathBuf> = std::fs::read_dir(backup_dir)
            .map(|entries| {
                entries
                    .flatten()
                    .map(|e| e.path())
                    .filter(|p| p.extension().is_some_and(|e| e == "zip"))
                    .collect()
            })
            .unwrap_or_default();
        zips.sort();
        zips.reverse(); // backup-<timestamp>: newest first
        let _ = std::fs::create_dir_all(dir);
        for zip_path in zips {
            if missing.is_empty() {
                break;
            }
            let Ok(file) = std::fs::File::open(&zip_path) else {
                continue;
            };
            let Ok(mut archive) = zip::ZipArchive::new(file) else {
                continue;
            };
            missing.retain(|hash| {
                let Ok(entry) = archive.by_name(&self.zip_entry(hash)) else {
                    return true;
                };
                let mut bytes = Vec::new();
                if std::io::Read::read_to_end(
                    &mut std::io::Read::take(entry, self.max_stored_bytes + 1),
                    &mut bytes,
                )
                .is_err()
                    || bytes.len() as u64 > self.max_stored_bytes
                    || hex::encode(Sha256::digest(&bytes)) != *hash
                {
                    return true;
                }
                let tmp = dir.join(format!(".{hash}.tmp"));
                let restored = std::fs::write(&tmp, &bytes).is_ok()
                    && std::fs::rename(&tmp, dir.join(format!("{hash}.jpg"))).is_ok();
                let _ = std::fs::remove_file(&tmp);
                !restored
            });
        }
        missing
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn encode(image: &DynamicImage, format: ImageFormat) -> Vec<u8> {
        let mut out = Cursor::new(Vec::new());
        image.write_to(&mut out, format).unwrap();
        out.into_inner()
    }

    #[test]
    fn crops_square_and_shrinks() {
        let wide = DynamicImage::new_rgb8(1600, 900);
        let photo = process(&encode(&wide, ImageFormat::Png), Shape::Square).unwrap();
        let stored = image::load_from_memory(&photo.jpeg).unwrap();
        assert_eq!((stored.width(), stored.height()), (MAX_SIDE, MAX_SIDE));
        assert_eq!(image::guess_format(&photo.jpeg).unwrap(), ImageFormat::Jpeg);
        assert!(is_valid_hash(&photo.hash));
    }

    #[test]
    fn small_photos_are_not_enlarged() {
        let small = DynamicImage::new_rgba8(40, 60);
        let photo = process(&encode(&small, ImageFormat::Png), Shape::Square).unwrap();
        let stored = image::load_from_memory(&photo.jpeg).unwrap();
        assert_eq!((stored.width(), stored.height()), (40, 40));
    }

    #[test]
    fn rejects_other_types_and_garbage() {
        assert_eq!(process(b"", Shape::Square).err(), Some(PhotoError::Empty));
        assert_eq!(
            process(b"GIF89a\x01\x00\x01\x00", Shape::Portrait).err(),
            Some(PhotoError::UnsupportedType)
        );
        assert_eq!(
            process(b"<svg xmlns='http://www.w3.org/2000/svg'/>", Shape::Square).err(),
            Some(PhotoError::UnsupportedType)
        );
        // A PNG signature followed by nothing useful.
        assert_eq!(
            process(b"\x89PNG\r\n\x1a\n garbage", Shape::Square).err(),
            Some(PhotoError::Undecodable)
        );
        assert_eq!(
            process(&vec![0xFF; MAX_UPLOAD_BYTES + 1], Shape::Portrait).err(),
            Some(PhotoError::TooLarge)
        );
    }

    #[test]
    fn oversized_images_are_rejected_before_decoding() {
        // Over 8000 px a side (the decoder's limit) and over 40 MP (our own check). Both are
        // cheap to produce as PNGs of one colour.
        for (w, h) in [(9000, 10), (7000, 7000)] {
            let png = encode(&DynamicImage::new_luma8(w, h), ImageFormat::Png);
            assert!(png.len() < MAX_UPLOAD_BYTES, "{w}x{h} fixture too big");
            assert_eq!(
                process(&png, Shape::Square).err(),
                Some(PhotoError::TooManyPixels),
                "{w}x{h}"
            );
        }
        // Just under both: accepted.
        let png = encode(&DynamicImage::new_luma8(6000, 6000), ImageFormat::Png);
        assert!(process(&png, Shape::Square).is_ok());
    }

    #[test]
    fn wallpapers_are_cut_to_the_phone_shape_and_never_enlarged() {
        // Landscape: full height, the middle 9:20.
        assert_eq!(portrait_crop(4000, 3000), ((1350, 3000), (1080, 2400)));
        // Taller than the phone: full width.
        assert_eq!(portrait_crop(1080, 4000), ((1080, 2400), (1080, 2400)));
        // Small: cut, not enlarged.
        assert_eq!(portrait_crop(300, 300), ((135, 300), (135, 300)));
        assert_eq!(portrait_crop(1, 1), ((1, 1), (1, 1)));
        let wide = DynamicImage::new_rgb8(3000, 2000);
        let photo = process(&encode(&wide, ImageFormat::Png), Shape::Portrait).unwrap();
        let stored = image::load_from_memory(&photo.jpeg).unwrap();
        assert_eq!((stored.width(), stored.height()), (900, 2000));
    }

    #[test]
    fn hashes_are_checked_before_touching_paths() {
        let dir = Path::new("/x");
        assert!(path_for(dir, &"a".repeat(64)).is_some());
        for bad in [
            "",
            "../etc/passwd",
            &"A".repeat(64),
            &"a".repeat(63),
            &"g".repeat(64),
        ] {
            assert!(path_for(dir, bad).is_none(), "{bad}");
        }
    }
}
