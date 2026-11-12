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
//!
//! A contact photo also keeps a downscaled original ([CONTACT_ORIGINALS], migration 0123; the user, 2026-11-03):
//! [process_contact] turns the upload upright, shrinks it to [original_size] (at most [ORIGINAL_LONG] on the long
//! side and [ORIGINAL_PIXELS] in all; a panorama keeps its short side up to [ORIGINAL_SHORT]) and re-encodes it as JPEG -
//! no EXIF, GPS or camera data, as every stored picture - then cuts the contact's square from it at the parent's
//! [View] (validated by [view_square]). "Adjust crop" sends only a new view and [recut] cuts again from that
//! original, so a re-crop can widen again and never loses a generation. The original is the parent's alone: served
//! on the admin session, never named in a policy or served to a phone, which only ever gets the square.

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
/// The decoder's limits, which the cropper also reads (`data-max-input-*`): a bigger photo goes up as its copy.
pub const MAX_INPUT_SIDE: u32 = 8000;
/// 40 megapixels - more than any phone camera's default, and checked before decoding.
pub const MAX_INPUT_PIXELS: u64 = 40_000_000;
/// The decoder's own buffer (a 40 MP RGB JPEG is 120 MB). Everything after it works on the
/// ≤ 512 px crop, so this is about the peak per upload - and [PROCESSING] allows one at a time.
const MAX_DECODE_ALLOC: u64 = 128 * 1024 * 1024;
/// Biggest stored contact photo accepted back from a backup zip ([recover_missing]).
const MAX_STORED_BYTES: u64 = 2 * 1024 * 1024;
/// A contact photo's kept original ([original_size]): its long side at most this...
pub const ORIGINAL_LONG: u32 = 2048;
/// ...unless that would squeeze a panorama's short side below this (as far as [ORIGINAL_PIXELS] allows).
pub const ORIGINAL_SHORT: u32 = 1024;
/// The original's pixels at most, whatever its shape: a 4:3 photo at [ORIGINAL_LONG] (a square one is 1774 px).
pub const ORIGINAL_PIXELS: u64 = 2048 * 1536;
/// The original is the source of every later cut: a little finer than the square's [JPEG_QUALITY].
const ORIGINAL_QUALITY: u8 = 90;
/// Biggest kept original accepted back from a backup zip (a noisy 3 MP JPEG at q90 is 2-3 MB).
const MAX_ORIGINAL_BYTES: u64 = 8 * 1024 * 1024;
/// The least side, in the original's pixels, of a chosen square: the phone's 76 dp circle at 3x. The cropper
/// stops at 230 (`static/photo-crop.js`, `MIN_ADJUST_SIDE`), so its rounding never trips this. An original
/// smaller than this can only be cut at cover.
pub const MIN_VIEW_SIDE: u32 = 228;
/// How far, in the original's pixels, a view may miss (the cropper works on its own copy's pixels).
const VIEW_SLACK: f64 = 2.0;

/// One photo is decoded at a time: a Pi has little memory and uploads are rare. The apps' logos (design 87) share it.
pub(crate) static PROCESSING: Semaphore = Semaphore::const_new(1);

/// Serialises everything that writes photo files or changes which ones are referenced:
/// store + `photo_hash` commit, [prune], [recover_missing]. Without it a prune running between
/// another request's store and its commit would delete the new file.
static FILES: Mutex<()> = Mutex::const_new(());
/// The same for wallpaper images (store + `wallpapers` insert, prune, recovery).
static WALLPAPER_FILES: Mutex<()> = Mutex::const_new(());
/// The same for music covers (design 21: entry covers and own files' embedded art).
static MUSIC_COVER_FILES: Mutex<()> = Mutex::const_new(());
/// The same for episode art (design 27).
static MUSIC_ART_FILES: Mutex<()> = Mutex::const_new(());
/// The same for the phones' own pictures (server round C).
static DEVICE_AVATAR_FILES: Mutex<()> = Mutex::const_new(());

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
    // The original and its view go with the square: no Adjust crop without a photo.
    forget_sql: &[
        "UPDATE contacts SET photo_hash = NULL, photo_original_hash = NULL, photo_view = NULL \
         WHERE photo_hash = ?",
    ],
    zip_prefix: "contact_photos",
    max_stored_bytes: MAX_STORED_BYTES,
};

/// A contact photo's kept original (`AppState.photo_original_dir`, `contacts.photo_original_hash`, migration 0123):
/// the upright, re-encoded, downscaled upload the square is cut from. Its own directory, but the contact photos'
/// lock, so a store of both files and their commit is one step for [prune] and [recover_missing]. Backed up; a lost
/// one is forgotten with its view (the square stays, an adjust refines it as before 0123).
pub static CONTACT_ORIGINALS: Store = Store {
    what: "contact photo original",
    lock: &FILES,
    referenced_sql: "SELECT DISTINCT photo_original_hash FROM contacts \
                     WHERE photo_original_hash IS NOT NULL",
    forget_sql: &[
        "UPDATE contacts SET photo_original_hash = NULL, photo_view = NULL \
         WHERE photo_original_hash = ?",
    ],
    zip_prefix: "contact_photo_originals",
    max_stored_bytes: MAX_ORIGINAL_BYTES,
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
/// (`music_entries.cover_hash`), an own file's embedded art (`music_files.art_hash`) and a source's
/// cover the sweep fetched (`music_listings.cover_hash`, design 21b), all square JPEGs of at most
/// [MAX_SIDE]. Backed up (unlike the audio); a lost one is dropped from the rows.
pub static MUSIC_COVERS: Store = Store {
    what: "music cover",
    lock: &MUSIC_COVER_FILES,
    referenced_sql: "SELECT cover_hash FROM music_entries WHERE cover_hash IS NOT NULL \
                     UNION SELECT art_hash FROM music_files WHERE art_hash IS NOT NULL \
                     UNION SELECT cover_hash FROM music_listings WHERE cover_hash IS NOT NULL",
    forget_sql: &[
        "UPDATE music_entries SET cover_hash = NULL WHERE cover_hash = ?",
        "UPDATE music_files SET art_hash = NULL WHERE art_hash = ?",
        // The source's cover (design 21b §2.6): its URL stays, so the next check fetches it again.
        "UPDATE music_listings SET cover_hash = NULL WHERE cover_hash = ?",
    ],
    zip_prefix: "music_covers",
    max_stored_bytes: MAX_STORED_BYTES,
};

/// Episode art (`AppState.music_art_dir`, design 27 §1.2): an NRK/RSS item's own picture that the
/// sweep fetched (`music_items.art_hash`), a square JPEG of at most [MAX_SIDE]. **Not backed up**:
/// it can be fetched again, and thousands of pictures would swell every zip. After a restore no
/// zip has it, so [Store::recover_missing] forgets the rows and the sweep's budget fills them in.
pub static MUSIC_ART: Store = Store {
    what: "episode picture",
    lock: &MUSIC_ART_FILES,
    referenced_sql: "SELECT DISTINCT art_hash FROM music_items WHERE art_hash IS NOT NULL",
    forget_sql: &[
        "UPDATE music_items SET art_hash = NULL, art_state = NULL, art_from = NULL, \
         art_print = NULL, art_tone = NULL WHERE art_hash = ?",
    ],
    // Never written (`backups.rs` doesn't add this store): a restore finds nothing under it.
    zip_prefix: "music_art",
    max_stored_bytes: MAX_STORED_BYTES,
};

/// A phone's own picture (`AppState.avatar_dir`, `devices.avatar_hash`, migration 0100): the
/// parent's PWA shows it instead of the letter tile and a push about the phone carries it as its
/// icon; never sent to a phone. A square JPEG of at most [MAX_SIDE] ([Shape::Square], the contact
/// photos' processing). Backed up; a lost one is forgotten (the letter tile again).
pub static DEVICE_AVATARS: Store = Store {
    what: "phone picture",
    lock: &DEVICE_AVATAR_FILES,
    referenced_sql: "SELECT DISTINCT avatar_hash FROM devices WHERE avatar_hash IS NOT NULL",
    forget_sql: &["UPDATE devices SET avatar_hash = NULL WHERE avatar_hash = ?"],
    zip_prefix: "device_avatars",
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
    /// The i18n key of the parent-facing line (`photo.error.*`, en + nb).
    pub fn key(&self) -> &'static str {
        match self {
            PhotoError::Empty => "photo.error.empty",
            PhotoError::TooLarge => "photo.error.too_large",
            PhotoError::UnsupportedType => "photo.error.unsupported_type",
            PhotoError::TooManyPixels => "photo.error.too_many_pixels",
            PhotoError::Undecodable => "photo.error.undecodable",
            PhotoError::TooDetailed => "photo.error.too_detailed",
        }
    }

    /// The parent-facing line in `lang`, the limits filled in from the constants.
    pub fn text(&self, lang: crate::i18n::Lang) -> String {
        let mb = |bytes: usize| (bytes / (1024 * 1024)).to_string();
        let (upload, wallpaper) = (mb(MAX_UPLOAD_BYTES), mb(MAX_WALLPAPER_BYTES));
        let (mp, side) = (
            (MAX_INPUT_PIXELS / 1_000_000).to_string(),
            MAX_INPUT_SIDE.to_string(),
        );
        let params: &[(&str, &str)] = match self {
            PhotoError::TooLarge => &[("mb", &upload)],
            PhotoError::TooManyPixels => &[("mp", &mp), ("side", &side)],
            PhotoError::TooDetailed => &[("mb", &wallpaper)],
            _ => &[],
        };
        crate::i18n::t_params(lang, self.key(), params)
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

/// An upload decoded within the limits (format sniffed from the bytes), and its EXIF orientation - not yet
/// applied: the callers shrink first and turn only the small image.
fn decode(bytes: &[u8]) -> Result<(DynamicImage, image::metadata::Orientation), PhotoError> {
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
    Ok((image, orientation))
}

/// Decodes, normalises and re-encodes an upload. CPU-heavy on a Pi: use [process_limited].
/// Memory: the decoded image once (≤ [MAX_DECODE_ALLOC]); the crop is a view, shrunk straight
/// to its target size, and only that small image is turned upright. That is correct for both
/// shapes because a *centred* crop commutes with every EXIF orientation: the crop is computed on
/// the upright size (width and height swapped for the 90° orientations, QA 08 #3) and mapped back.
pub fn process(bytes: &[u8], shape: Shape) -> Result<ProcessedPhoto, PhotoError> {
    let (image, orientation) = decode(bytes)?;
    let (width, height) = (image.width(), image.height());

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
        let photo = encode_jpeg(&rgb, quality)?;
        if photo.jpeg.len() <= limit {
            return Ok(photo);
        }
    }
    Err(PhotoError::TooDetailed)
}

/// `rgb` as a JPEG (nothing but pixels) and its hash.
fn encode_jpeg(rgb: &image::RgbImage, quality: u8) -> Result<ProcessedPhoto, PhotoError> {
    let mut jpeg = Vec::new();
    image::codecs::jpeg::JpegEncoder::new_with_quality(&mut jpeg, quality)
        .encode_image(rgb)
        .map_err(|_| PhotoError::Undecodable)?;
    let hash = hex::encode(Sha256::digest(&jpeg));
    Ok(ProcessedPhoto { jpeg, hash })
}

/// The size a contact photo's original is kept at, from its upright `w`×`h`: never enlarged, never over
/// [ORIGINAL_PIXELS] (applied last, so a square photo is 1774×1774); the long side at most [ORIGINAL_LONG], unless
/// that squeezes the short side below [ORIGINAL_SHORT] (a panorama, a banner) - then the short side is kept up to
/// [ORIGINAL_SHORT] and the long side may pass [ORIGINAL_LONG], within the pixels. The cropper mirrors it
/// (`static/photo-crop.js`, `originalSize`) for its zoom limit.
pub fn original_size(w: u32, h: u32) -> (u32, u32) {
    let (wf, hf) = (f64::from(w.max(1)), f64::from(h.max(1)));
    let by_long = f64::from(ORIGINAL_LONG) / wf.max(hf);
    let by_short = f64::from(ORIGINAL_SHORT) / wf.min(hf);
    let by_area = (ORIGINAL_PIXELS as f64 / (wf * hf)).sqrt();
    let scale = by_long.max(by_short.min(by_area)).min(by_area).min(1.0);
    let side = |n: f64| ((n * scale).round() as u32).max(1);
    (side(wf), side(hf))
}

/// The square the parent chose in a contact photo's original: its left, top and side as fractions of the
/// original's width and height (`x,y,w,h`; the square's side is `w` of the width and `h` of the height). That way
/// it means the same square in the cropper's own copy and in the original, whatever their pixels.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct View {
    pub x: f64,
    pub y: f64,
    pub w: f64,
    pub h: f64,
}

/// Why a view was refused (each a line under the photo, `ViewError::key`).
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum ViewError {
    /// Not four numbers.
    Malformed,
    /// Past the photo's edge.
    OutOfBounds,
    NotSquare,
    /// Under [MIN_VIEW_SIDE] of the original (or under its short side, when that is smaller).
    TooSmall,
}

impl ViewError {
    /// The i18n key of the line under the photo.
    pub fn key(self) -> &'static str {
        match self {
            ViewError::Malformed => "device.calls.crop.bad_view",
            ViewError::OutOfBounds => "device.calls.crop.outside",
            ViewError::NotSquare => "device.calls.crop.not_square",
            ViewError::TooSmall => "device.calls.crop.too_small",
        }
    }
}

impl View {
    /// `x,y,w,h`: four finite numbers, nothing else. The geometry is [view_square]'s to judge.
    pub fn parse(text: &str) -> Result<View, ViewError> {
        let numbers: Vec<f64> = text
            .split(',')
            .map(|part| part.trim().parse::<f64>())
            .collect::<Result<_, _>>()
            .map_err(|_| ViewError::Malformed)?;
        match numbers[..] {
            [x, y, w, h] if numbers.iter().all(|n| n.is_finite()) => Ok(View { x, y, w, h }),
            _ => Err(ViewError::Malformed),
        }
    }

    /// The square `(left, top, side)` of a `width`×`height` original as a view (what `contacts.photo_view` keeps).
    pub fn of_square(left: u32, top: u32, side: u32, width: u32, height: u32) -> View {
        let (w, h) = (f64::from(width), f64::from(height));
        View {
            x: f64::from(left) / w,
            y: f64::from(top) / h,
            w: f64::from(side) / w,
            h: f64::from(side) / h,
        }
    }

    /// The stored and wire form, `x,y,w,h` to six places.
    pub fn text(&self) -> String {
        format!("{:.6},{:.6},{:.6},{:.6}", self.x, self.y, self.w, self.h)
    }
}

/// A view as a square of a `width`×`height` original: `(left, top, side)` in its pixels, always inside it. `None`
/// is cover, centred - the server's own crop (an upload without the cropper). Refused: a square that leaves the
/// photo, isn't a square, or is under [MIN_VIEW_SIDE] (cover only, for an original smaller than that) - each by
/// more than [VIEW_SLACK] px, which the cropper's rounding never reaches.
pub fn view_square(
    view: Option<&View>,
    width: u32,
    height: u32,
) -> Result<(u32, u32, u32), ViewError> {
    let short = width.min(height);
    let Some(view) = view else {
        return Ok(((width - short) / 2, (height - short) / 2, short));
    };
    let (w, h) = (f64::from(width), f64::from(height));
    let (left, top) = (view.x * w, view.y * h);
    let (side_x, side_y) = (view.w * w, view.h * h);
    if side_x <= 0.0 || side_y <= 0.0 {
        return Err(ViewError::TooSmall);
    }
    if left < -VIEW_SLACK
        || top < -VIEW_SLACK
        || left + side_x > w + VIEW_SLACK
        || top + side_y > h + VIEW_SLACK
    {
        return Err(ViewError::OutOfBounds);
    }
    if (side_x - side_y).abs() > VIEW_SLACK {
        return Err(ViewError::NotSquare);
    }
    let side_f = (side_x + side_y) / 2.0;
    if side_f + VIEW_SLACK < f64::from(MIN_VIEW_SIDE.min(short)) {
        return Err(ViewError::TooSmall);
    }
    let side = (side_f.round() as u32).clamp(1, short);
    let left = (left.round().max(0.0) as u32).min(width - side);
    let top = (top.round().max(0.0) as u32).min(height - side);
    Ok((left, top, side))
}

/// What [process_contact] and [recut] give: the contact's square, and its view as stored (`contacts.photo_view`).
pub struct ContactCut {
    pub square: ProcessedPhoto,
    pub view: String,
}

/// A contact photo upload: the original as kept ([CONTACT_ORIGINALS]) and the square cut from it at `view`.
pub struct ContactPhoto {
    pub original: ProcessedPhoto,
    pub cut: ContactCut,
}

/// Why a contact photo couldn't be stored: the picture, or the chosen view.
#[derive(Debug, PartialEq, Eq)]
pub enum ContactPhotoError {
    Photo(PhotoError),
    View(ViewError),
}

impl From<PhotoError> for ContactPhotoError {
    fn from(err: PhotoError) -> Self {
        ContactPhotoError::Photo(err)
    }
}

/// A contact photo upload (CPU-heavy, [process_contact_limited]): decoded within the limits, shrunk to
/// [original_size] (only that small copy is turned upright), flattened on white and re-encoded at
/// [ORIGINAL_QUALITY] - the kept original, no metadata - and the square cut from it at `view` (`None`: cover,
/// centred). The view is judged on the original's size before anything is shrunk.
pub fn process_contact(
    bytes: &[u8],
    view: Option<&View>,
) -> Result<ContactPhoto, ContactPhotoError> {
    let (image, orientation) = decode(bytes)?;
    let (width, height) = (image.width(), image.height());
    let swaps = swaps_axes(orientation);
    let (upright_w, upright_h) = if swaps {
        (height, width)
    } else {
        (width, height)
    };
    let (out_w, out_h) = original_size(upright_w, upright_h);
    let square = view_square(view, out_w, out_h).map_err(ContactPhotoError::View)?;
    let (raw_w, raw_h) = if swaps {
        (out_h, out_w)
    } else {
        (out_w, out_h)
    };
    let mut small = if (raw_w, raw_h) != (width, height) {
        let small = DynamicImage::ImageRgba8(imageops::thumbnail(&image, raw_w, raw_h));
        drop(image);
        small
    } else {
        image
    };
    small.apply_orientation(orientation);
    let rgb = flatten_on_white(small);
    let original = encode_jpeg(&rgb, ORIGINAL_QUALITY)?;
    let cut = cut_square(&rgb, square)?;
    Ok(ContactPhoto { original, cut })
}

/// A new square from a kept original (its JPEG bytes) at `view`: "Adjust crop" sends only the view.
pub fn recut(original: &[u8], view: &View) -> Result<ContactCut, ContactPhotoError> {
    let (image, _) = decode(original)?;
    let rgb = flatten_on_white(image);
    let square =
        view_square(Some(view), rgb.width(), rgb.height()).map_err(ContactPhotoError::View)?;
    cut_square(&rgb, square).map_err(ContactPhotoError::Photo)
}

/// The square `(left, top, side)` of an upright original, shrunk to at most [MAX_SIDE] (never enlarged), JPEG at
/// [JPEG_QUALITY], and its view.
fn cut_square(
    rgb: &image::RgbImage,
    (left, top, side): (u32, u32, u32),
) -> Result<ContactCut, PhotoError> {
    let part = imageops::crop_imm(rgb, left, top, side, side);
    let target = side.min(MAX_SIDE);
    let small = if target != side {
        imageops::thumbnail(&*part, target, target)
    } else {
        part.to_image()
    };
    Ok(ContactCut {
        square: encode_jpeg(&small, JPEG_QUALITY)?,
        view: View::of_square(left, top, side, rgb.width(), rgb.height()).text(),
    })
}

/// [process_contact] on a blocking thread, one at a time with every other upload ([process_limited]).
pub async fn process_contact_limited(
    bytes: axum::body::Bytes,
    view: Option<View>,
) -> Result<ContactPhoto, ContactPhotoError> {
    let _permit = PROCESSING.acquire().await.expect("semaphore never closed");
    tokio::task::spawn_blocking(move || process_contact(&bytes, view.as_ref()))
        .await
        .unwrap_or_else(|err| {
            tracing::error!(%err, "photo processing task failed");
            Err(ContactPhotoError::Photo(PhotoError::Undecodable))
        })
}

/// [recut] on a blocking thread, one at a time with every upload.
pub async fn recut_limited(original: Vec<u8>, view: View) -> Result<ContactCut, ContactPhotoError> {
    let _permit = PROCESSING.acquire().await.expect("semaphore never closed");
    tokio::task::spawn_blocking(move || recut(&original, &view))
        .await
        .unwrap_or_else(|err| {
            tracing::error!(%err, "photo processing task failed");
            Err(ContactPhotoError::Photo(PhotoError::Undecodable))
        })
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

/// Deletes every stored photo and kept original no contact references any more (after a replace, a remove, or a
/// contact/device delete). Best effort: a failure is logged, never an error for the parent.
/// Holds the file lock (one for both), so it never runs between another request's store and its commit.
pub async fn prune(state: &crate::AppState) {
    CONTACT_PHOTOS.prune(&state.db, &state.photo_dir).await;
    CONTACT_ORIGINALS
        .prune(&state.db, &state.photo_original_dir)
        .await;
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
    // The squares first: a lost one forgets its original too.
    CONTACT_PHOTOS
        .recover_missing(&state.db, &state.photo_dir, backup_dir)
        .await;
    CONTACT_ORIGINALS
        .recover_missing(&state.db, &state.photo_original_dir, backup_dir)
        .await;
    WALLPAPERS
        .recover_missing(&state.db, &state.wallpaper_dir, backup_dir)
        .await;
    MUSIC_COVERS
        .recover_missing(&state.db, &state.music_cover_dir, backup_dir)
        .await;
    DEVICE_AVATARS
        .recover_missing(&state.db, &state.avatar_dir, backup_dir)
        .await;
    let forgotten = MUSIC_ART
        .recover_missing(&state.db, &state.music_art_dir, backup_dir)
        .await;
    // Forgotten pictures change the listings the phones are served: store their versions again,
    // so the library names what the route serves (design 27 code QA L6).
    if !forgotten.is_empty()
        && let Err(err) = crate::music_sweep::restamp_versions(&state.db).await
    {
        tracing::warn!(%err, "couldn't store the music listings' versions again");
    }
}

/// Two pictures within this many bits of [print] are the same picture (design 27 §1.3): the
/// series cover under another URL, size or re-encode.
pub const SAME_PICTURE_BITS: u32 = 5;

/// A 64-bit difference hash (dHash) of an image: shrunk to 9×8 grey pixels, one bit per pair of
/// horizontal neighbours (left brighter than right). Robust to resizing and re-encoding, unlike a
/// byte compare. `None` when the bytes don't decode.
pub fn print(bytes: &[u8]) -> Option<i64> {
    let image = ImageReader::new(Cursor::new(bytes))
        .with_guessed_format()
        .ok()?
        .decode()
        .ok()?;
    Some(print_image(&image))
}

/// [print] of a decoded image.
pub fn print_image(image: &DynamicImage) -> i64 {
    let grey = imageops::resize(&image.to_luma8(), 9, 8, imageops::FilterType::Triangle);
    let mut bits: u64 = 0;
    for y in 0..8 {
        for x in 0..8 {
            bits <<= 1;
            if grey.get_pixel(x, y).0[0] > grey.get_pixel(x + 1, y).0[0] {
                bits |= 1;
            }
        }
    }
    bits as i64
}

/// [print] on a blocking thread.
pub async fn print_limited(bytes: Vec<u8>) -> Option<i64> {
    tokio::task::spawn_blocking(move || print(&bytes))
        .await
        .ok()
        .flatten()
}

/// Two pictures whose coarse colours ([tone_image]) differ by more than this many steps (of 32) in any
/// quarter and channel are different pictures (design 27 code QA L4: dHash is grey only).
pub const SAME_TONE_STEPS: i64 = 3;
/// A print with at most this many bits set, or at least 64 minus it, has too little structure
/// to tell pictures apart by (a flat field, small text on one background, a smooth gradient).
pub const LOW_DETAIL_BITS: u32 = 8;

/// A picture's coarse colour: the mean RGB of its four quarters, 5 bits each (60 bits).
pub fn tone_image(image: &DynamicImage) -> i64 {
    let small = imageops::resize(&image.to_rgb8(), 2, 2, imageops::FilterType::Triangle);
    let mut bits: u64 = 0;
    for pixel in small.pixels() {
        for channel in pixel.0 {
            bits = (bits << 5) | u64::from(channel >> 3);
        }
    }
    bits as i64
}

/// Whether two [tone_image]s are the same colours, quarter by quarter.
pub fn same_tone(a: i64, b: i64) -> bool {
    (0..12).all(|n| {
        let channel = |tone: i64| (tone >> (n * 5)) & 31;
        (channel(a) - channel(b)).abs() <= SAME_TONE_STEPS
    })
}

/// Whether a print has too little structure to tell pictures apart by.
pub fn low_detail(print: i64) -> bool {
    let ones = print.count_ones();
    ones <= LOW_DETAIL_BITS || ones >= 64 - LOW_DETAIL_BITS
}

/// [print] and [tone_image] of the same bytes, on a blocking thread.
pub async fn fingerprint_limited(bytes: Vec<u8>) -> Option<(i64, i64)> {
    tokio::task::spawn_blocking(move || {
        let image = ImageReader::new(Cursor::new(bytes))
            .with_guessed_format()
            .ok()?
            .decode()
            .ok()?;
        Some((print_image(&image), tone_image(&image)))
    })
    .await
    .ok()
    .flatten()
}

/// How many bits two prints differ in.
pub fn print_distance(a: i64, b: i64) -> u32 {
    (a ^ b).count_ones()
}

/// Whether two prints are the same picture ([SAME_PICTURE_BITS]).
pub fn same_picture(a: i64, b: i64) -> bool {
    print_distance(a, b) <= SAME_PICTURE_BITS
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

    /// Forgets `hash` in the database as a lost file is forgotten ([Store::recover_missing]). The caller holds the
    /// store's lock.
    pub async fn forget(&self, db: &sqlx::SqlitePool, hash: &str) -> Result<(), sqlx::Error> {
        for sql in self.forget_sql {
            sqlx::query(sql).bind(hash).execute(db).await?;
        }
        Ok(())
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
    /// and the admin pages don't ask for a file that will never exist. Run at startup. Returns
    /// the hashes it forgot.
    pub async fn recover_missing(
        &'static self,
        db: &sqlx::SqlitePool,
        dir: &Path,
        backup_dir: &Path,
    ) -> Vec<String> {
        let _files = self.lock().await;
        let referenced: Vec<String> =
            match sqlx::query_scalar(self.referenced_sql).fetch_all(db).await {
                Ok(hashes) => hashes,
                Err(err) => {
                    tracing::warn!(%err, what = self.what, "couldn't list stored files to check");
                    return Vec::new();
                }
            };
        let dir = dir.to_path_buf();
        let missing: Vec<String> = referenced
            .into_iter()
            .filter(|h| path_for(&dir, h).is_some_and(|p| !p.exists()))
            .collect();
        if missing.is_empty() {
            return Vec::new();
        }
        let backup_dir = backup_dir.to_path_buf();
        let still_missing = tokio::task::spawn_blocking(move || {
            self.extract_from_backups(&dir, &backup_dir, missing)
        })
        .await
        .unwrap_or_default();
        for hash in &still_missing {
            tracing::warn!(
                hash,
                what = self.what,
                "stored file missing and in no backup - removing the reference"
            );
            for sql in self.forget_sql {
                if let Err(err) = sqlx::query(sql).bind(hash).execute(db).await {
                    tracing::warn!(%err, what = self.what, "couldn't forget a missing file");
                }
            }
        }
        still_missing
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

    /// A picture with structure (a diagonal gradient and a block), so its print isn't all zeros.
    fn picture(seed: u32, w: u32, h: u32) -> DynamicImage {
        DynamicImage::ImageRgb8(image::RgbImage::from_fn(w, h, |x, y| {
            let fx = x * 255 / w;
            let fy = y * 255 / h;
            let block = if (x * 4 / w + y * 4 / h + seed).is_multiple_of(3) {
                120
            } else {
                0
            };
            image::Rgb([
                ((fx + seed * 40) % 256) as u8,
                ((fy * (seed + 1)) % 256) as u8,
                ((fx + fy) / 2 + block).min(255) as u8,
            ])
        }))
    }

    #[test]
    fn prints_survive_resizing_and_re_encoding_but_tell_pictures_apart() {
        let big = picture(1, 1200, 1200);
        let stored = process(&encode(&big, ImageFormat::Png), Shape::Square).unwrap();
        let original = print(&stored.jpeg).unwrap();
        // The same picture at 300 px, through the same pipeline (NRK's other variant).
        let small = DynamicImage::ImageRgba8(imageops::thumbnail(&big, 300, 300));
        let variant = process(&encode(&small, ImageFormat::Png), Shape::Square).unwrap();
        assert_ne!(variant.hash, stored.hash, "a byte compare would miss it");
        assert!(same_picture(original, print(&variant.jpeg).unwrap()));
        // Re-encoded as a low-quality JPEG.
        let mut lossy = Vec::new();
        image::codecs::jpeg::JpegEncoder::new_with_quality(&mut lossy, 40)
            .encode_image(&big.to_rgb8())
            .unwrap();
        assert!(same_picture(original, print(&lossy).unwrap()));
        // Another picture.
        let other = process(
            &encode(&picture(2, 600, 600), ImageFormat::Png),
            Shape::Square,
        )
        .unwrap();
        assert!(!same_picture(original, print(&other.jpeg).unwrap()));
        assert_eq!(print(b"not an image"), None);
    }

    /// A flat field with a small dark "title" at `x`: little structure for a dHash.
    fn titled(x: u32, colour: [u8; 3]) -> DynamicImage {
        DynamicImage::ImageRgb8(image::RgbImage::from_fn(512, 512, |px, py| {
            if (x..x + 90).contains(&px) && (400..416).contains(&py) {
                image::Rgb([10, 10, 10])
            } else {
                image::Rgb(colour)
            }
        }))
    }

    #[test]
    fn tones_tell_colours_apart_and_flat_pictures_are_low_detail() {
        let a = picture(3, 600, 600);
        let stored = process(&encode(&a, ImageFormat::Png), Shape::Square).unwrap();
        let decoded = image::load_from_memory(&stored.jpeg).unwrap();
        assert!(
            same_tone(tone_image(&a), tone_image(&decoded)),
            "re-encoded"
        );
        // The same structure in other colours: the same print, another tone.
        let recoloured = DynamicImage::ImageRgb8(image::RgbImage::from_fn(600, 600, |x, y| {
            let [r, g, b] = a.to_rgb8().get_pixel(x, y).0;
            image::Rgb([b, r, g])
        }));
        assert!(!same_tone(tone_image(&a), tone_image(&recoloured)));
        assert!(!low_detail(print_image(&a)));
        // Two titles on one background print alike, and are low-detail.
        let one = print_image(&titled(60, [40, 90, 160]));
        let two = print_image(&titled(300, [40, 90, 160]));
        assert!(low_detail(one) && low_detail(two), "{one:b} {two:b}");
        assert!(low_detail(print_image(&DynamicImage::new_rgb8(64, 64))));
    }

    #[test]
    fn the_same_picture_line_is_5_bits() {
        let a = 0x0f0f_0f0f_0f0f_0f0f_i64;
        assert!(same_picture(a, a ^ 0b1_1111));
        assert!(!same_picture(a, a ^ 0b11_1111));
        assert_eq!(print_distance(a, a ^ i64::MIN), 1, "the sign bit counts");
    }

    #[test]
    fn the_original_keeps_2048_on_the_long_side_and_a_panoramas_short_side() {
        // The cropper's mirror (`originalSize`, jstest/photo-crop.test.js) pins the same rows.
        assert_eq!(original_size(4000, 3000), (2048, 1536));
        assert_eq!(original_size(4000, 2250), (2048, 1152));
        // The pixels are capped last: a square photo, a banner whose long side stays (QA photo-original L3).
        assert_eq!(original_size(4000, 4000), (1774, 1774));
        assert_eq!(original_size(300, 8000), (300, 8000));
        assert_eq!(original_size(1000, 8000), (627, 5017));
        assert_eq!(original_size(8000, 1000), (5017, 627));
        assert_eq!(original_size(600, 600), (600, 600));
        assert_eq!(original_size(1, 1), (1, 1));
    }

    #[test]
    fn a_view_must_be_inside_square_and_big_enough() {
        let view = |text: &str| View::parse(text).unwrap();
        // Cover, centred, without a view; a chosen square, snapped to pixels.
        assert_eq!(view_square(None, 2048, 1536), Ok((256, 0, 1536)));
        assert_eq!(
            view_square(Some(&view("0.3125,0.25,0.375,0.5")), 2048, 1536),
            Ok((640, 384, 768))
        );
        // The cropper's rounding (its own copy's pixels) is within the slack, and clamped inside.
        assert_eq!(
            view_square(Some(&view("0.6255,0.0005,0.375,0.5")), 2048, 1536),
            Ok((1280, 1, 768))
        );
        for (text, err) in [
            ("0.7,0,0.375,0.5", ViewError::OutOfBounds),
            ("-0.1,0,0.375,0.5", ViewError::OutOfBounds),
            ("0,0.6,0.375,0.5", ViewError::OutOfBounds),
            ("0,0,0.375,0.4", ViewError::NotSquare),
            ("0,0,0.5,0.5", ViewError::NotSquare),
            ("0,0,0.1,0.133333", ViewError::TooSmall),
            ("0,0,0,0", ViewError::TooSmall),
        ] {
            assert_eq!(
                view_square(Some(&view(text)), 2048, 1536),
                Err(err),
                "{text}"
            );
        }
        // A small original can only be cut at cover.
        assert_eq!(
            view_square(Some(&view("0,0,0.5,0.5")), 200, 200),
            Err(ViewError::TooSmall)
        );
        assert_eq!(
            view_square(Some(&view("0,0,1,1")), 200, 200),
            Ok((0, 0, 200))
        );
        for bad in [
            "",
            "1,2,3",
            "1,2,3,4,5",
            "a,b,c,d",
            "NaN,0,1,1",
            "inf,0,1,1",
        ] {
            assert_eq!(View::parse(bad), Err(ViewError::Malformed), "{bad}");
        }
    }

    #[test]
    fn the_square_is_cut_from_the_kept_original_at_512() {
        // 2400x1200, left half red, right half blue: kept at 2048x1024, the right square is blue.
        let photo = DynamicImage::ImageRgb8(image::RgbImage::from_fn(2400, 1200, |x, _| {
            if x < 1200 {
                image::Rgb([220, 0, 0])
            } else {
                image::Rgb([0, 0, 220])
            }
        }));
        let png = encode(&photo, ImageFormat::Png);
        let right = View::parse("0.5,0,0.5,1").unwrap();
        let made = process_contact(&png, Some(&right)).unwrap();
        let original = image::load_from_memory(&made.original.jpeg).unwrap();
        assert_eq!((original.width(), original.height()), (2048, 1024));
        assert_eq!(
            made.original.hash,
            hex::encode(Sha256::digest(&made.original.jpeg))
        );
        let square = image::load_from_memory(&made.cut.square.jpeg)
            .unwrap()
            .into_rgb8();
        assert_eq!(square.dimensions(), (MAX_SIDE, MAX_SIDE));
        // Away from the edge the halves meet at (the shrink and JPEG blur it a little).
        let inner = |square: &image::RgbImage, test: &dyn Fn([u8; 3]) -> bool| {
            square
                .enumerate_pixels()
                .filter(|(x, _, _)| (8..MAX_SIDE - 8).contains(x))
                .all(|(_, _, p)| test(p.0))
        };
        assert!(inner(&square, &|[r, _, b]| b > 180 && r < 40), "all blue");
        assert_eq!(made.cut.view, "0.500000,0.000000,0.500000,1.000000");
        // Cut again from the original, wider than before is no question: the left square, red, at 512.
        let left = recut(&made.original.jpeg, &View::parse("0,0,0.5,1").unwrap()).unwrap();
        let square = image::load_from_memory(&left.square.jpeg)
            .unwrap()
            .into_rgb8();
        assert_eq!(square.dimensions(), (MAX_SIDE, MAX_SIDE));
        assert!(inner(&square, &|[r, _, b]| r > 180 && b < 40), "all red");
        // A view the original refuses changes nothing.
        assert_eq!(
            process_contact(&png, Some(&View::parse("0.6,0,0.5,1").unwrap())).err(),
            Some(ContactPhotoError::View(ViewError::OutOfBounds))
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
