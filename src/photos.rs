//! Contact photos (design docs/design/05-ui-photos-i18n.md in the handy workspace).
//!
//! An uploaded photo is never stored as sent: it is decoded (JPEG, PNG or WebP, detected from the
//! bytes, not the file name), turned upright from its EXIF orientation, centre-cropped to a square,
//! shrunk to at most [`MAX_SIDE`] pixels and encoded again as JPEG. Re-encoding drops every bit of
//! metadata (EXIF with GPS position, XMP, comments) and anything that isn't pixels. The file is
//! named after the SHA-256 of what was stored (`<hash>.jpg` in `AppState.photo_dir`), and that hash
//! is what `contacts.photo_hash` and the phone's `call_policy` carry - the phone caches by it and
//! checks it after download.

use std::io::Cursor;
use std::path::{Path, PathBuf};

use image::{DynamicImage, ImageDecoder, ImageError, ImageFormat, ImageReader, Limits, imageops};
use sha2::{Digest, Sha256};
use tokio::sync::{Mutex, MutexGuard, Semaphore};

/// Largest upload accepted (the route's body limit is a bit higher, for the multipart framing).
pub const MAX_UPLOAD_BYTES: usize = 10 * 1024 * 1024;
/// Largest side of a stored photo. The biggest view on the phone is 132 dp (~400 px at xxhdpi).
pub const MAX_SIDE: u32 = 512;
const MAX_INPUT_SIDE: u32 = 8000;
/// 40 megapixels - more than any phone camera's default, and checked before decoding.
const MAX_INPUT_PIXELS: u64 = 40_000_000;
/// The decoder's own buffer (a 40 MP RGB JPEG is 120 MB). Everything after it works on the
/// ≤ 512 px crop, so this is about the peak per upload - and [PROCESSING] allows one at a time.
const MAX_DECODE_ALLOC: u64 = 128 * 1024 * 1024;
/// Biggest stored photo accepted back from a backup zip ([recover_missing]).
const MAX_STORED_BYTES: u64 = 2 * 1024 * 1024;

/// One photo is decoded at a time: a Pi has little memory and uploads are rare.
static PROCESSING: Semaphore = Semaphore::const_new(1);

/// Serialises everything that writes photo files or changes which ones are referenced:
/// store + `photo_hash` commit, [prune], [recover_missing]. Without it a prune running between
/// another request's store and its commit would delete the new file.
static FILES: Mutex<()> = Mutex::const_new(());

/// Take before storing a photo and committing its hash; release before calling [prune].
pub async fn lock_files() -> MutexGuard<'static, ()> {
    FILES.lock().await
}
const JPEG_QUALITY: u8 = 85;

#[derive(Debug, PartialEq, Eq)]
pub enum PhotoError {
    Empty,
    TooLarge,
    UnsupportedType,
    TooManyPixels,
    Undecodable,
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
        }
    }
}

/// A photo ready to store: JPEG bytes and their SHA-256 (lowercase hex).
pub struct ProcessedPhoto {
    pub jpeg: Vec<u8>,
    pub hash: String,
}

/// [process] on a blocking thread, one upload at a time.
pub async fn process_limited(bytes: axum::body::Bytes) -> Result<ProcessedPhoto, PhotoError> {
    let _permit = PROCESSING.acquire().await.expect("semaphore never closed");
    tokio::task::spawn_blocking(move || process(&bytes))
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
/// Memory: the decoded image once (≤ [MAX_DECODE_ALLOC]); the square crop is a view, shrunk
/// straight to ≤ [MAX_SIDE] px, and only that small image is turned upright (a centre square
/// crop is the same pixels whichever way the photo is rotated or mirrored).
pub fn process(bytes: &[u8]) -> Result<ProcessedPhoto, PhotoError> {
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

    let side = width.min(height);
    let square = imageops::crop_imm(&image, (width - side) / 2, (height - side) / 2, side, side);
    let target = side.min(MAX_SIDE);
    let small = if side > target {
        imageops::thumbnail(&*square, target, target)
    } else {
        square.to_image()
    };
    drop(image);
    let mut small = DynamicImage::ImageRgba8(small);
    small.apply_orientation(orientation);
    // JPEG has no alpha: flatten onto white rather than letting transparent pixels go black.
    let rgb = flatten_on_white(small);

    let mut jpeg = Vec::new();
    image::codecs::jpeg::JpegEncoder::new_with_quality(&mut jpeg, JPEG_QUALITY)
        .encode_image(&rgb)
        .map_err(|_| PhotoError::Undecodable)?;
    let hash = hex::encode(Sha256::digest(&jpeg));
    Ok(ProcessedPhoto { jpeg, hash })
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
    let _files = FILES.lock().await;
    let referenced: Vec<String> = match sqlx::query_scalar(
        "SELECT DISTINCT photo_hash FROM contacts WHERE photo_hash IS NOT NULL",
    )
    .fetch_all(&state.db)
    .await
    {
        Ok(hashes) => hashes,
        Err(err) => {
            tracing::warn!(%err, "couldn't list contact photos to prune");
            return;
        }
    };
    let Ok(mut entries) = tokio::fs::read_dir(&*state.photo_dir).await else {
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
            tracing::warn!(%err, file = name, "couldn't delete an unused contact photo");
        }
    }
}

/// Zip entry name of a stored photo in a backup.
pub fn zip_entry(hash: &str) -> String {
    format!("contact_photos/{hash}.jpg")
}

/// Adds every stored photo to a backup zip (blocking; called from `backups::build_backup_zip`).
/// Content-addressed, so a photo is the same file in every backup that has it.
pub fn add_to_zip<W: std::io::Write + std::io::Seek>(
    writer: &mut zip::ZipWriter<W>,
    dir: &Path,
) -> std::io::Result<()> {
    let Ok(entries) = std::fs::read_dir(dir) else {
        return Ok(()); // no photos yet
    };
    let options =
        zip::write::SimpleFileOptions::default().compression_method(zip::CompressionMethod::Stored); // already JPEG
    for entry in entries.flatten() {
        let name = entry.file_name().to_string_lossy().into_owned();
        let Some(hash) = name.strip_suffix(".jpg").filter(|h| is_valid_hash(h)) else {
            continue;
        };
        writer.start_file(zip_entry(hash), options)?;
        std::io::Write::write_all(writer, &std::fs::read(entry.path())?)?;
    }
    Ok(())
}

/// After a restore (or any start): every `photo_hash` whose file is missing is looked up in the
/// backup zips in `backup_dir` (newest first) and extracted if its bytes really hash to that
/// name; whatever can't be found is set to NULL, so the phones and the calls page fall back to
/// the initial instead of asking for a file that will never exist. Run at startup.
pub async fn recover_missing(state: &crate::AppState, backup_dir: &Path) {
    let _files = FILES.lock().await;
    let referenced: Vec<String> = match sqlx::query_scalar(
        "SELECT DISTINCT photo_hash FROM contacts WHERE photo_hash IS NOT NULL",
    )
    .fetch_all(&state.db)
    .await
    {
        Ok(hashes) => hashes,
        Err(err) => {
            tracing::warn!(%err, "couldn't list contact photos to check");
            return;
        }
    };
    let dir = state.photo_dir.as_path().to_path_buf();
    let missing: Vec<String> = referenced
        .into_iter()
        .filter(|h| path_for(&dir, h).is_some_and(|p| !p.exists()))
        .collect();
    if missing.is_empty() {
        return;
    }
    let backup_dir = backup_dir.to_path_buf();
    let still_missing =
        tokio::task::spawn_blocking(move || extract_from_backups(&dir, &backup_dir, missing))
            .await
            .unwrap_or_default();
    for hash in still_missing {
        tracing::warn!(
            hash,
            "contact photo missing and in no backup - removing the reference"
        );
        if let Err(err) = sqlx::query("UPDATE contacts SET photo_hash = NULL WHERE photo_hash = ?")
            .bind(&hash)
            .execute(&state.db)
            .await
        {
            tracing::warn!(%err, "couldn't clear a missing contact photo");
        }
    }
}

/// Returns the hashes it couldn't restore.
fn extract_from_backups(dir: &Path, backup_dir: &Path, mut missing: Vec<String>) -> Vec<String> {
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
            let Ok(entry) = archive.by_name(&zip_entry(hash)) else {
                return true;
            };
            let mut bytes = Vec::new();
            if std::io::Read::read_to_end(
                &mut std::io::Read::take(entry, MAX_STORED_BYTES + 1),
                &mut bytes,
            )
            .is_err()
                || bytes.len() as u64 > MAX_STORED_BYTES
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
        let photo = process(&encode(&wide, ImageFormat::Png)).unwrap();
        let stored = image::load_from_memory(&photo.jpeg).unwrap();
        assert_eq!((stored.width(), stored.height()), (MAX_SIDE, MAX_SIDE));
        assert_eq!(image::guess_format(&photo.jpeg).unwrap(), ImageFormat::Jpeg);
        assert!(is_valid_hash(&photo.hash));
    }

    #[test]
    fn small_photos_are_not_enlarged() {
        let small = DynamicImage::new_rgba8(40, 60);
        let photo = process(&encode(&small, ImageFormat::Png)).unwrap();
        let stored = image::load_from_memory(&photo.jpeg).unwrap();
        assert_eq!((stored.width(), stored.height()), (40, 40));
    }

    #[test]
    fn rejects_other_types_and_garbage() {
        assert_eq!(process(b"").err(), Some(PhotoError::Empty));
        assert_eq!(
            process(b"GIF89a\x01\x00\x01\x00").err(),
            Some(PhotoError::UnsupportedType)
        );
        assert_eq!(
            process(b"<svg xmlns='http://www.w3.org/2000/svg'/>").err(),
            Some(PhotoError::UnsupportedType)
        );
        // A PNG signature followed by nothing useful.
        assert_eq!(
            process(b"\x89PNG\r\n\x1a\n garbage").err(),
            Some(PhotoError::Undecodable)
        );
        assert_eq!(
            process(&vec![0xFF; MAX_UPLOAD_BYTES + 1]).err(),
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
                process(&png).err(),
                Some(PhotoError::TooManyPixels),
                "{w}x{h}"
            );
        }
        // Just under both: accepted.
        let png = encode(&DynamicImage::new_luma8(6000, 6000), ImageFormat::Png);
        assert!(process(&png).is_ok());
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
