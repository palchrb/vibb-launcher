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

use image::{DynamicImage, ImageDecoder, ImageFormat, ImageReader, Limits};
use sha2::{Digest, Sha256};

/// Largest upload accepted (the route's body limit is a bit higher, for the multipart framing).
pub const MAX_UPLOAD_BYTES: usize = 10 * 1024 * 1024;
/// Largest side of a stored photo. The biggest view on the phone is 132 dp (~400 px at xxhdpi).
pub const MAX_SIDE: u32 = 512;
const MAX_INPUT_SIDE: u32 = 8000;
const MAX_DECODE_ALLOC: u64 = 256 * 1024 * 1024;
const JPEG_QUALITY: u8 = 85;

#[derive(Debug, PartialEq, Eq)]
pub enum PhotoError {
    Empty,
    TooLarge,
    UnsupportedType,
    Undecodable,
}

impl PhotoError {
    /// Parent-facing text for the calls page.
    pub fn message(&self) -> &'static str {
        match self {
            PhotoError::Empty => "Choose a photo first.",
            PhotoError::TooLarge => "That photo is too big (at most 10 MB).",
            PhotoError::UnsupportedType => "Use a JPEG, PNG or WebP photo.",
            PhotoError::Undecodable => "That photo couldn't be read - try another one.",
        }
    }
}

/// A photo ready to store: JPEG bytes and their SHA-256 (lowercase hex).
pub struct ProcessedPhoto {
    pub jpeg: Vec<u8>,
    pub hash: String,
}

/// Decodes, normalises and re-encodes an upload. CPU-heavy on a Pi: call it from
/// `spawn_blocking`.
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
    let mut decoder = reader.into_decoder().map_err(|_| PhotoError::Undecodable)?;
    let orientation = decoder.orientation().map_err(|_| PhotoError::Undecodable)?;
    let mut image = DynamicImage::from_decoder(decoder).map_err(|_| PhotoError::Undecodable)?;
    image.apply_orientation(orientation);

    let (width, height) = (image.width(), image.height());
    if width == 0 || height == 0 {
        return Err(PhotoError::Undecodable);
    }
    let side = width.min(height);
    let square = image.crop_imm((width - side) / 2, (height - side) / 2, side, side);
    let target = side.min(MAX_SIDE);
    let resized = if side > target {
        square.thumbnail(target, target)
    } else {
        square
    };
    // JPEG has no alpha: flatten onto white rather than letting transparent pixels go black.
    let rgb = flatten_on_white(resized);

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
pub async fn prune(state: &crate::AppState) {
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
        let keep = name
            .strip_suffix(".jpg")
            .is_some_and(|hash| referenced.iter().any(|r| r == hash));
        if !keep && let Err(err) = tokio::fs::remove_file(entry.path()).await {
            tracing::warn!(%err, file = name, "couldn't delete an unused contact photo");
        }
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
