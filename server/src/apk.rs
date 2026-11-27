//! What an APK says it is (security review M10): its package name, versionCode and versionName, from
//! the binary `AndroidManifest.xml`, and the SHA-256 of its signing certificate, from the APK Signing
//! Block (the v3 signer Android 14 to 17 pick, or v2 without a v3 block; [phone_signer]). The
//! catalog pins the package and the signer from an app's first release and refuses a later release that differs (`handlers::tracked_apps`); the phones get
//! them with the app list. The versionCode is how a phone's report is known to run the stored
//! release (`app_updated`). The versionName is what the setup QR's check shows (design 86,
//! `launcher_check`). The permissions it declares are what an update may newly ask for (design 90,
//! `catalog_holds`): read all-or-nothing, by resource id only.
//!
//! Read-only parsing of an untrusted file: every offset and length is checked, nothing panics, and
//! the manifest and the signing block are capped in size. The digest is lower-case hex of the DER
//! certificate, as `apksigner verify --print-certs` prints it and the launcher's `SelfUpdate.signers`
//! computes it.

use std::io::{Read, Seek, SeekFrom};
use std::path::Path;

use sha2::{Digest, Sha256};

/// The largest `AndroidManifest.xml` read (a real one is a few KB, a big app's tens of KB).
const MAX_MANIFEST_BYTES: u64 = 4 * 1024 * 1024;
/// The largest APK Signing Block read (a few KB per signer).
const MAX_SIGNING_BLOCK_BYTES: u64 = 16 * 1024 * 1024;
const SIGNING_BLOCK_MAGIC: &[u8; 16] = b"APK Sig Block 42";
const V2_BLOCK_ID: u32 = 0x7109_871a;
const V3_BLOCK_ID: u32 = 0xf053_68c0;
/// APK Signature Scheme v3.1 (a key rotated for newer SDKs): not read, only seen.
const V31_BLOCK_ID: u32 = 0x1b93_ad61;

#[derive(Debug, PartialEq, Eq)]
pub struct ApkIdentity {
    /// The manifest's `package`; `None` when the manifest couldn't be read.
    pub package: Option<String>,
    /// The manifest's `android:versionCode`, with `android:versionCodeMajor` above it as Android's
    /// `PackageInfo.getLongVersionCode` reads them; `None` when it couldn't be read (a reference,
    /// not a number) or the manifest couldn't.
    pub version_code: Option<i64>,
    /// The manifest's `android:versionName` as a plain string ("0.39.0-rc.6"); `None` when it is a
    /// reference, empty or unread.
    pub version_name: Option<String>,
    /// `<uses-sdk android:minSdkVersion>`: the oldest Android API level the app installs on (34 =
    /// Android 14); `None` when it is a codename or unread.
    pub min_sdk: Option<u32>,
    /// SHA-256 (lower-case hex) of the certificate Android 14 to 17 verify the APK by
    /// ([phone_signer]); `None` without a v2/v3 signature (a v1-only APK), when the block couldn't
    /// be read or holds two v2 or two v3 blocks, or when that certificate differs between those
    /// Android versions.
    pub signer_sha256: Option<String>,
    /// Every permission the manifest declares (design 90 §4): each `<uses-permission>`,
    /// `<uses-permission-sdk-23>` and `<uses-permission-sdk-m>` anywhere (one whose
    /// `android:maxSdkVersion` is below [crate::catalog_holds::PHONE_MIN_SDK] left out), and the
    /// `android:permission` of any element that is one of [BIND_PERMISSIONS], as
    /// `bind:<name>`, and every permission Android adds to those for the app's target SDK
    /// ([SPLIT_PERMISSIONS]); sorted, deduplicated. `None` when it can't be read whole - never
    /// "none".
    pub permissions: Option<Vec<String>>,
    /// The target SDK the split permissions were added for (`<uses-sdk>`'s, [target_sdk]); `None`
    /// when `permissions` is.
    pub split_target: Option<i64>,
    /// `<manifest android:sharedUserId>` is there (by resource id or by name, any value): the app
    /// would run as one user ID with other apps of its key and share their permissions, so its
    /// own list doesn't say what it can do (`catalog_holds::judged_permissions`).
    pub shared_user_id: bool,
    /// The oldest Vibb server the build needs (design 140, [MIN_SERVER_ENTRY]): read for every APK,
    /// used only for ours (`catalog_holds::needs_server`, the setup QR's check).
    pub min_server: MinServer,
}

/// Design 140 §1: the file every build of ours carries, the oldest server it needs as `X.Y.Z` and
/// one newline (`contracts/music/min_server.json`). Inside the signed APK: the server doesn't
/// verify the signature, but a phone refuses a forged file, so a lowered value only travels with
/// an APK no phone installs and a raised one only holds a release.
pub const MIN_SERVER_ENTRY: &str = "assets/vibb/min_server";
/// The longest [MIN_SERVER_ENTRY] read: more is [MinServer::Unreadable].
const MIN_SERVER_MAX_BYTES: u64 = 32;

/// What [MIN_SERVER_ENTRY] says.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub enum MinServer {
    /// No such entry: a build from before design 140, or not ours.
    #[default]
    Absent,
    /// There, but more than [MIN_SERVER_MAX_BYTES] or not exactly `X.Y.Z` with at most one
    /// trailing newline.
    Unreadable,
    Version((u64, u64, u64)),
}

/// [MIN_SERVER_ENTRY]'s bytes as [MinServer]: `X.Y.Z`, each part 1 to 4 ASCII digits without a
/// leading zero (but `0`), and at most one `\n` after it; anything else is unreadable.
pub fn parse_min_server(bytes: &[u8]) -> MinServer {
    if bytes.len() as u64 > MIN_SERVER_MAX_BYTES {
        return MinServer::Unreadable;
    }
    let text = bytes.strip_suffix(b"\n").unwrap_or(bytes);
    let mut parts = text.split(|b| *b == b'.');
    let mut next = || -> Option<u64> {
        let part = parts.next()?;
        let digits = !part.is_empty() && part.len() <= 4 && part.iter().all(u8::is_ascii_digit);
        if !digits || (part.len() > 1 && part[0] == b'0') {
            return None;
        }
        std::str::from_utf8(part).ok()?.parse().ok()
    };
    match (next(), next(), next(), parts.next()) {
        (Some(x), Some(y), Some(z), None) => MinServer::Version((x, y, z)),
        _ => MinServer::Unreadable,
    }
}

/// [MIN_SERVER_ENTRY] read from `archive`, through at most [MIN_SERVER_MAX_BYTES] + 1 bytes.
fn read_min_server<R: Read + Seek>(archive: &mut zip::ZipArchive<R>) -> MinServer {
    match archive.by_name(MIN_SERVER_ENTRY) {
        Ok(entry) => {
            let mut bytes = Vec::new();
            match entry.take(MIN_SERVER_MAX_BYTES + 1).read_to_end(&mut bytes) {
                Ok(_) => parse_min_server(&bytes),
                Err(_) => MinServer::Unreadable,
            }
        }
        Err(zip::result::ZipError::FileNotFound) => MinServer::Absent,
        Err(_) => MinServer::Unreadable,
    }
}

/// Reads the facts; a file that isn't a zip at all is an error, a part that can't be read is
/// `None`.
pub fn read_identity(path: &Path) -> Result<ApkIdentity, String> {
    let mut file = std::fs::File::open(path).map_err(|err| format!("can't open the APK: {err}"))?;
    let signer_sha256 =
        signing_certificate(&mut file).map(|cert| hex::encode(Sha256::digest(cert)));
    file.seek(SeekFrom::Start(0))
        .map_err(|err| format!("can't read the APK: {err}"))?;
    let mut archive =
        zip::ZipArchive::new(file).map_err(|err| format!("the APK isn't a zip: {err}"))?;
    let manifest = archive
        .by_name("AndroidManifest.xml")
        .ok()
        .and_then(|entry| {
            let mut bytes = Vec::new();
            entry
                .take(MAX_MANIFEST_BYTES)
                .read_to_end(&mut bytes)
                .ok()?;
            read_manifest(&bytes)
        })
        .unwrap_or_default();
    let min_server = read_min_server(&mut archive);
    Ok(ApkIdentity {
        package: manifest.package,
        version_code: manifest.version_code,
        version_name: manifest.version_name,
        min_sdk: manifest.min_sdk,
        signer_sha256,
        permissions: manifest.permissions,
        split_target: manifest.split_target,
        shared_user_id: manifest.shared_user_id,
        min_server,
    })
}

fn u16_at(bytes: &[u8], at: usize) -> Option<u16> {
    Some(u16::from_le_bytes(
        bytes.get(at..at.checked_add(2)?)?.try_into().ok()?,
    ))
}

fn u32_at(bytes: &[u8], at: usize) -> Option<u32> {
    Some(u32::from_le_bytes(
        bytes.get(at..at.checked_add(4)?)?.try_into().ok()?,
    ))
}

fn u64_at(bytes: &[u8], at: usize) -> Option<u64> {
    Some(u64::from_le_bytes(
        bytes.get(at..at.checked_add(8)?)?.try_into().ok()?,
    ))
}

/// The DER certificate Android verifies the APK by on our phones ([phone_signer]).
fn signing_certificate(file: &mut std::fs::File) -> Option<Vec<u8>> {
    let len = file.metadata().ok()?.len();
    // The End of Central Directory record: 22 bytes plus a comment of up to 64 KiB.
    let tail_len = len.min(22 + 65_535);
    let mut tail = vec![0u8; tail_len as usize];
    file.seek(SeekFrom::Start(len - tail_len)).ok()?;
    file.read_exact(&mut tail).ok()?;
    let eocd = (0..=tail.len().checked_sub(22)?)
        .rev()
        .find(|&at| tail[at..at + 4] == [0x50, 0x4b, 0x05, 0x06])?;
    let central_directory = u64::from(u32_at(&tail, eocd + 16)?);

    // The block sits right before the central directory, ending in its size and the magic.
    let footer_at = central_directory.checked_sub(24)?;
    let mut footer = [0u8; 24];
    file.seek(SeekFrom::Start(footer_at)).ok()?;
    file.read_exact(&mut footer).ok()?;
    if &footer[8..] != SIGNING_BLOCK_MAGIC {
        return None;
    }
    let block_size = u64_at(&footer, 0)?;
    if !(24..=MAX_SIGNING_BLOCK_BYTES).contains(&block_size) {
        return None;
    }
    // The size field at the start isn't counted in `block_size`.
    let block_start = central_directory.checked_sub(block_size.checked_add(8)?)?;
    let mut block = vec![0u8; (block_size + 8) as usize];
    file.seek(SeekFrom::Start(block_start)).ok()?;
    file.read_exact(&mut block).ok()?;
    if u64_at(&block, 0)? != block_size {
        return None;
    }

    // ID-value pairs between the leading size and the footer. Android takes the first pair of an
    // id; a second v2, v3 or v3.1 pair could be another reader's, so the block is unreadable.
    let pairs = block.get(8..block.len() - 24)?;
    let (mut v2, mut v3, mut v31) = (None, None, None);
    let mut at = 0usize;
    while at < pairs.len() {
        let pair_len = usize::try_from(u64_at(pairs, at)?).ok()?;
        let value = pairs.get(at.checked_add(12)?..at.checked_add(8)?.checked_add(pair_len)?)?;
        let slot = match u32_at(pairs, at + 8)? {
            V2_BLOCK_ID => Some(&mut v2),
            V3_BLOCK_ID => Some(&mut v3),
            V31_BLOCK_ID => Some(&mut v31),
            _ => None,
        };
        if let Some(slot) = slot {
            if slot.is_some() {
                return None;
            }
            *slot = Some(value);
        }
        at = at.checked_add(8)?.checked_add(pair_len)?;
    }
    phone_signer(v2, v3, v31.is_some())
}

/// The SDKs our phones run: Android 14 to 17.
const PHONE_SDKS: std::ops::RangeInclusive<i32> =
    crate::catalog_holds::PHONE_MIN_SDK as i32..=crate::catalog_holds::PHONE_MAX_SDK as i32;

/// The certificate Android verifies the APK by on every one of [PHONE_SDKS], as its verifier
/// picks it: with no v3 block, the v2 block's first signer; with one, the one v3 signer whose SDK
/// range holds the SDK - none is "No signers found" there, so `None` here, unless a v3.1 block is
/// present (Android tries v3.1 first and verifies by its rotated key). The v3.1 block isn't read:
/// beside it the original key stands as the pin (the v3 signer for the SDK if any, else v2's, else
/// the v3 block's only signer), and the phone's `CatalogApkCheck` takes the rotated key only
/// through that key's lineage (`signingCertificateHistory`) - that check is the gate for the v3.1
/// key. (A v3.1 signer may target a development release, which a released build of that SDK
/// skips for v3; either way the key is the original or one in its lineage.) `None` also when the
/// keys differ between those SDKs (the key a phone checks would depend on its Android), when two
/// v3 signers hold one SDK (Android refuses that), or when what is needed can't be read.
fn phone_signer(v2: Option<&[u8]>, v3: Option<&[u8]>, v31: bool) -> Option<Vec<u8>> {
    let v3 = match v3 {
        Some(block) => Some(v3_signers(block)?),
        None => None,
    };
    let mut chosen: Option<Vec<u8>> = None;
    for sdk in PHONE_SDKS {
        let certificate = match &v3 {
            None => first_certificate(v2?)?,
            Some(signers) => {
                let mut holding = signers
                    .iter()
                    .filter(|(min, max, _)| *min <= sdk && sdk <= *max);
                match (holding.next(), holding.next()) {
                    (Some(_), Some(_)) => return None,
                    (Some((_, _, signer)), None) => v3_certificate(signer)?,
                    (None, _) if !v31 => return None,
                    (None, _) => match (v2, signers.as_slice()) {
                        (Some(v2), _) => first_certificate(v2)?,
                        (None, [(_, _, only)]) => v3_certificate(only)?,
                        (None, _) => return None,
                    },
                }
            }
        };
        match &chosen {
            Some(earlier) if *earlier != certificate => return None,
            Some(_) => {}
            None => chosen = Some(certificate),
        }
    }
    chosen
}

/// A v3 block's signers, each as `(minSdk, maxSdk, signer)`: the range outside the signed data,
/// which Android reads (as Java `int`s) to pick the signer for its SDK. `None` when the list
/// can't be read.
fn v3_signers(block: &[u8]) -> Option<Vec<(i32, i32, &[u8])>> {
    let (signers, _) = prefixed(block, 0)?;
    let mut found = Vec::new();
    let mut at = 0usize;
    while at < signers.len() {
        let (signer, next) = prefixed(signers, at)?;
        let (_signed_data, after) = prefixed(signer, 0)?;
        let min = i32::from_le_bytes(u32_at(signer, after)?.to_le_bytes());
        let max = i32::from_le_bytes(u32_at(signer, after.checked_add(4)?)?.to_le_bytes());
        found.push((min, max, signer));
        at = next;
    }
    Some(found)
}

/// A v3 signer's first certificate, its signed data's SDK range the same as the one outside it
/// (Android refuses the APK otherwise).
fn v3_certificate(signer: &[u8]) -> Option<Vec<u8>> {
    let (signed_data, after) = prefixed(signer, 0)?;
    let outside = signer.get(after..after.checked_add(8)?)?;
    let (_digests, next) = prefixed(signed_data, 0)?;
    let (certificates, next) = prefixed(signed_data, next)?;
    let inside = signed_data.get(next..next.checked_add(8)?)?;
    if inside != outside {
        return None;
    }
    let (certificate, _) = prefixed(certificates, 0)?;
    (!certificate.is_empty()).then(|| certificate.to_vec())
}

/// A u32-length-prefixed slice at `at`, and where the next one starts.
fn prefixed(bytes: &[u8], at: usize) -> Option<(&[u8], usize)> {
    let len = u32_at(bytes, at)? as usize;
    let start = at.checked_add(4)?;
    let end = start.checked_add(len)?;
    Some((bytes.get(start..end)?, end))
}

/// A v2 block: signers (prefixed) > first signer (prefixed) > signed data (prefixed) > digests
/// (prefixed), then certificates (prefixed) > first certificate (prefixed).
fn first_certificate(scheme_block: &[u8]) -> Option<Vec<u8>> {
    let (signers, _) = prefixed(scheme_block, 0)?;
    let (signer, _) = prefixed(signers, 0)?;
    let (signed_data, _) = prefixed(signer, 0)?;
    let (_digests, next) = prefixed(signed_data, 0)?;
    let (certificates, _) = prefixed(signed_data, next)?;
    let (certificate, _) = prefixed(certificates, 0)?;
    (!certificate.is_empty()).then(|| certificate.to_vec())
}

const RES_STRING_POOL_TYPE: u16 = 0x0001;
const RES_XML_TYPE: u16 = 0x0003;
const RES_XML_START_ELEMENT_TYPE: u16 = 0x0102;
/// `RES_XML_FIRST_CHUNK_TYPE`..=`RES_XML_LAST_CHUNK_TYPE`: the XML tree's nodes.
const RES_XML_FIRST_NODE_TYPE: u16 = 0x0100;
const RES_XML_LAST_NODE_TYPE: u16 = 0x017f;
const TYPE_NULL: u8 = 0x00;
const RES_XML_RESOURCE_MAP_TYPE: u16 = 0x0180;
const UTF8_FLAG: u32 = 1 << 8;
const NO_INDEX: u32 = 0xffff_ffff;
const TYPE_STRING: u8 = 0x03;
const TYPE_INT_DEC: u8 = 0x10;
const TYPE_INT_HEX: u8 = 0x11;
/// `android.R.attr.versionCode`, `versionName` and `versionCodeMajor`.
const ATTR_VERSION_CODE: u32 = 0x0101_021b;
const ATTR_VERSION_NAME: u32 = 0x0101_021c;
/// `android.R.attr.minSdkVersion` and `targetSdkVersion`, on `<uses-sdk>`.
const ATTR_MIN_SDK_VERSION: u32 = 0x0101_020c;
const ATTR_TARGET_SDK_VERSION: u32 = 0x0101_0270;
const ATTR_VERSION_CODE_MAJOR: u32 = 0x0101_0576;
/// `android.R.attr.sharedUserId` and `sharedUserMaxSdkVersion`, on `<manifest>`.
const ATTR_SHARED_USER_ID: u32 = 0x0101_000b;
const ATTR_SHARED_USER_MAX_SDK_VERSION: u32 = 0x0101_064d;
/// `android.R.attr.name`, `permission` and `maxSdkVersion` (design 90 §4).
const ATTR_NAME: u32 = 0x0101_0003;
const ATTR_PERMISSION: u32 = 0x0101_0006;
const ATTR_MAX_SDK_VERSION: u32 = 0x0101_0271;
/// The most permission names a manifest may declare before the list reads as unreadable.
const MAX_PERMISSIONS: usize = 500;
/// The longest permission name read, in bytes.
const MAX_PERMISSION_BYTES: usize = 200;
/// The elements that declare a permission the app asks for (Android reads them by name, whatever
/// the namespace).
const USES_PERMISSION: [&str; 3] = [
    "uses-permission",
    "uses-permission-sdk-23",
    "uses-permission-sdk-m",
];
/// A component's `android:permission` that Android binds a service of power over the phone by
/// (design 90 §3, L4/L5): stored as `bind:<name>`.
pub const BIND_PERMISSIONS: [&str; 7] = [
    "android.permission.BIND_NOTIFICATION_LISTENER_SERVICE",
    "android.permission.BIND_ACCESSIBILITY_SERVICE",
    "android.permission.BIND_DEVICE_ADMIN",
    "android.permission.BIND_VPN_SERVICE",
    "android.permission.BIND_INPUT_METHOD",
    "android.permission.BIND_AUTOFILL_SERVICE",
    "android.permission.BIND_CREDENTIAL_PROVIDER_SERVICE",
];
/// The prefix a component's bind permission is stored under.
pub const BIND_PREFIX: &str = "bind:";

/// A split's `targetSdk` when `platform.xml` gives none: every app (`CUR_DEVELOPMENT + 1`).
const EVERY_TARGET: i64 = 10_001;
/// Android's split permissions (`frameworks/base/data/etc/platform.xml`, `<split-permission>`,
/// the same in Android 14 to 17 but for the two `health` rows 16 added): an app whose target SDK
/// is below a row's asks for its new permission too whenever it asks for the old one. Each row is
/// `(old, new, targetSdk)`; a feature-flagged row counts as on.
const SPLIT_PERMISSIONS: &[(&str, &str, i64)] = &[
    (
        "ACCESS_FINE_LOCATION",
        "ACCESS_COARSE_LOCATION",
        EVERY_TARGET,
    ),
    (
        "WRITE_EXTERNAL_STORAGE",
        "READ_EXTERNAL_STORAGE",
        EVERY_TARGET,
    ),
    ("READ_CONTACTS", "READ_CALL_LOG", 16),
    ("WRITE_CONTACTS", "WRITE_CALL_LOG", 16),
    ("ACCESS_FINE_LOCATION", "ACCESS_BACKGROUND_LOCATION", 29),
    ("ACCESS_COARSE_LOCATION", "ACCESS_BACKGROUND_LOCATION", 29),
    ("READ_EXTERNAL_STORAGE", "ACCESS_MEDIA_LOCATION", 29),
    ("WRITE_EXTERNAL_STORAGE", "ACCESS_MEDIA_LOCATION", 29),
    ("BLUETOOTH", "BLUETOOTH_SCAN", 31),
    ("BLUETOOTH", "BLUETOOTH_CONNECT", 31),
    ("BLUETOOTH", "BLUETOOTH_ADVERTISE", 31),
    ("BLUETOOTH_ADMIN", "BLUETOOTH_SCAN", 31),
    ("BLUETOOTH_ADMIN", "BLUETOOTH_CONNECT", 31),
    ("BLUETOOTH_ADMIN", "BLUETOOTH_ADVERTISE", 31),
    ("BODY_SENSORS", "BODY_SENSORS_BACKGROUND", 33),
    ("BODY_SENSORS", "health.READ_HEART_RATE", 36),
    (
        "BODY_SENSORS_BACKGROUND",
        "health.READ_HEALTH_DATA_IN_BACKGROUND",
        36,
    ),
    ("READ_EXTERNAL_STORAGE", "READ_MEDIA_AUDIO", 33),
    ("READ_EXTERNAL_STORAGE", "READ_MEDIA_VIDEO", 33),
    ("READ_EXTERNAL_STORAGE", "READ_MEDIA_IMAGES", 33),
    ("WRITE_EXTERNAL_STORAGE", "READ_MEDIA_AUDIO", 33),
    ("WRITE_EXTERNAL_STORAGE", "READ_MEDIA_VIDEO", 33),
    ("WRITE_EXTERNAL_STORAGE", "READ_MEDIA_IMAGES", 33),
    (
        "READ_MEDIA_IMAGES",
        "READ_MEDIA_VISUAL_USER_SELECTED",
        EVERY_TARGET,
    ),
    (
        "READ_MEDIA_VIDEO",
        "READ_MEDIA_VISUAL_USER_SELECTED",
        EVERY_TARGET,
    ),
    (
        "ACCESS_MEDIA_LOCATION",
        "READ_MEDIA_VISUAL_USER_SELECTED",
        EVERY_TARGET,
    ),
];
/// The prefix of every [SPLIT_PERMISSIONS] name.
const ANDROID_PERMISSION: &str = "android.permission.";

/// `names` with every permission Android adds for an app targeting `target` ([SPLIT_PERMISSIONS]),
/// until none is added (Android's one pass in table order adds no more than this).
pub fn with_split_permissions(names: &mut Vec<String>, target: i64) {
    loop {
        let mut added = false;
        for (old, new, below) in SPLIT_PERMISSIONS {
            if target >= *below {
                continue;
            }
            let old = format!("{ANDROID_PERMISSION}{old}");
            let new = format!("{ANDROID_PERMISSION}{new}");
            if names.contains(&old) && !names.contains(&new) {
                names.push(new);
                added = true;
            }
        }
        if !added {
            return;
        }
    }
}

/// What the binary manifest's first element (`<manifest>`) says.
#[derive(Debug, Default, PartialEq, Eq)]
struct Manifest {
    package: Option<String>,
    version_code: Option<i64>,
    version_name: Option<String>,
    /// `<uses-sdk android:minSdkVersion>` when it is a number.
    min_sdk: Option<u32>,
    /// [ApkIdentity::permissions].
    permissions: Option<Vec<String>>,
    /// [ApkIdentity::split_target].
    split_target: Option<i64>,
    /// [ApkIdentity::shared_user_id].
    shared_user_id: bool,
    /// Every attribute of `<manifest>` was read (a damaged one ends the reading).
    attributes_whole: bool,
}

/// The `package`, `android:versionCode`, `android:versionName` and `android:versionCodeMajor` attributes of the binary
/// manifest's first element (`<manifest>`). An android attribute is known by its resource id (the
/// resource map), else by its name. The permissions only by resource id, and only when the whole
/// document read cleanly to its end (design 90 §4).
fn read_manifest(xml: &[u8]) -> Option<Manifest> {
    let mut found = None;
    let mut permissions = Some(Vec::new());
    let mut target = None;
    let whole = walk_manifest(xml, &mut found, &mut permissions, &mut target).is_some()
        && u32_at(xml, 4).is_some_and(|size| size as usize == xml.len());
    let mut manifest = found?;
    // No `<uses-sdk>`: Android's target is 0, below every split.
    let target = target.unwrap_or(0);
    // A `<manifest>` whose attributes couldn't all be read may hide a shared user ID.
    let whole = whole && manifest.attributes_whole;
    manifest.permissions = permissions.filter(|_| whole).map(|mut names| {
        with_split_permissions(&mut names, target);
        names.sort();
        names.dedup();
        names
    });
    manifest.split_target = manifest.permissions.is_some().then_some(target);
    Some(manifest)
}

/// [read_manifest]'s walk to the document's end: `found` is the `<manifest>` element once read,
/// and its `<uses-sdk>`'s `android:minSdkVersion` is added when that element follows; every later
/// element's declared permission goes to `permissions` ([permission_of]), which turns `None` at
/// the first that can't be read. `target` is the lowest target SDK of any `<uses-sdk>`
/// ([target_sdk]). A damaged chunk after `<manifest>` ends the walk (`None`); what was read of the
/// package stays.
fn walk_manifest(
    xml: &[u8],
    found: &mut Option<Manifest>,
    permissions: &mut Option<Vec<String>>,
    target: &mut Option<i64>,
) -> Option<()> {
    if u16_at(xml, 0)? != RES_XML_TYPE {
        return None;
    }
    let mut at = usize::from(u16_at(xml, 2)?);
    let mut strings: Option<&[u8]> = None;
    let mut resource_ids: Option<&[u8]> = None;
    // Android's `ResXMLTree::setTo` takes the string pool and the resource map only before the
    // first XML node (the last of each there); `nextNode` skips any chunk after it. So a pool or
    // map after the first node is never ours either - and, since a reader that took it would see
    // other names than Android, the permission list is unreadable (design 90 QA H1).
    let mut in_nodes = false;
    while at < xml.len() {
        let kind = u16_at(xml, at)?;
        let header_size = usize::from(u16_at(xml, at + 2)?);
        let size = u32_at(xml, at + 4)? as usize;
        let chunk = xml.get(at..at.checked_add(size)?)?;
        if size < 8 {
            return None;
        }
        match kind {
            RES_STRING_POOL_TYPE | RES_XML_RESOURCE_MAP_TYPE if in_nodes => *permissions = None,
            RES_STRING_POOL_TYPE => strings = Some(chunk),
            RES_XML_RESOURCE_MAP_TYPE => resource_ids = chunk.get(header_size..),
            RES_XML_FIRST_NODE_TYPE..=RES_XML_LAST_NODE_TYPE => in_nodes = true,
            _ => {}
        }
        if kind == RES_XML_START_ELEMENT_TYPE {
            let pool = strings?;
            // ResXMLTree_attrExt follows the node header.
            let ext = chunk.get(header_size..)?;
            let element = pool_string(pool, u32_at(ext, 4)?)?;
            if let Some(manifest) = found {
                if element == "uses-sdk" && manifest.min_sdk.is_none() {
                    manifest.min_sdk = int_attribute(
                        pool,
                        resource_ids,
                        ext,
                        ATTR_MIN_SDK_VERSION,
                        "minSdkVersion",
                    );
                }
                if element == "uses-sdk" {
                    match target_sdk(resource_ids, ext) {
                        Some(sdk) => *target = Some(target.map_or(sdk, |low| low.min(sdk))),
                        None => *permissions = None,
                    }
                }
                // Android's string reader stops at a NUL: a name with one (or any control
                // character) may read differently there - unreadable (QA H2).
                if element.chars().any(char::is_control) {
                    *permissions = None;
                }
                // Android finds an element's attributes by walking them in resource-id order: one
                // it can't reach that way, or an id it might map to another, it never reads - so
                // a list that relied on it would not be the one Android grants.
                if !in_android_order(resource_ids, ext) {
                    *permissions = None;
                }
                if let Some(names) = permissions {
                    match permission_of(pool, resource_ids, ext, &element) {
                        Some(Some(name)) if names.len() < MAX_PERMISSIONS => names.push(name),
                        Some(None) => {}
                        _ => *permissions = None,
                    }
                }
                at = at.checked_add(size)?;
                continue;
            }
            if element != "manifest" {
                return None;
            }
            let attribute_start = usize::from(u16_at(ext, 8)?);
            let attribute_size = usize::from(u16_at(ext, 10)?);
            let count = usize::from(u16_at(ext, 12)?);
            let mut manifest = Manifest::default();
            let (mut code, mut major) = (None, None);
            let mut package_seen = false;
            // `android:sharedUserMaxSdkVersion` by resource id: every plain number read.
            let mut shared_max: Vec<Option<u32>> = Vec::new();
            manifest.attributes_whole = true;
            for index in 0..count {
                // A short or damaged attribute ends the reading; what was read stays (the
                // package reads as it always did: the first `package`, or none).
                let Some(attribute) = index
                    .checked_mul(attribute_size)
                    .and_then(|offset| attribute_start.checked_add(offset))
                    .and_then(|start| ext.get(start..))
                else {
                    manifest.attributes_whole = false;
                    break;
                };
                let (Some(namespace), Some(name)) = (u32_at(attribute, 0), u32_at(attribute, 4))
                else {
                    manifest.attributes_whole = false;
                    break;
                };
                let named = |wanted: &str| pool_string(pool, name).as_deref() == Some(wanted);
                // A number: the typed value's data.
                let int = || match *attribute.get(15)? {
                    TYPE_INT_DEC | TYPE_INT_HEX => u32_at(attribute, 16),
                    _ => None,
                };
                let id = resource_ids.and_then(|ids| u32_at(ids, (name as usize).checked_mul(4)?));
                // Counted by its id or its name, in any namespace: more than Android reads, never
                // less.
                if id == Some(ATTR_SHARED_USER_ID) || named("sharedUserId") {
                    manifest.shared_user_id = true;
                }
                if id == Some(ATTR_SHARED_USER_MAX_SDK_VERSION) {
                    shared_max.push(int());
                }
                if namespace == NO_INDEX {
                    let Some(attribute_name) = pool_string(pool, name) else {
                        manifest.attributes_whole = false;
                        break;
                    };
                    if attribute_name == "package" && !package_seen {
                        package_seen = true;
                        manifest.package = string_value(pool, attribute);
                    }
                    continue;
                }
                let is =
                    |attr: u32, wanted: &str| id.map_or_else(|| named(wanted), |id| id == attr);
                if is(ATTR_VERSION_CODE, "versionCode") {
                    code = int();
                } else if is(ATTR_VERSION_CODE_MAJOR, "versionCodeMajor") {
                    major = int();
                } else if is(ATTR_VERSION_NAME, "versionName") {
                    manifest.version_name = string_value(pool, attribute);
                }
            }
            // A shared user ID capped below our phones' Android (`sharedUserMaxSdkVersion`, given
            // once as a plain number, not 0) isn't joined on a new install there.
            if let [Some(max)] = shared_max[..]
                && max != 0
                && max < crate::catalog_holds::PHONE_MIN_SDK
            {
                manifest.shared_user_id = false;
            }
            manifest.version_code = code.and_then(|code| long_version_code(code, major));
            *found = Some(manifest);
        }
        at = at.checked_add(size)?;
    }
    Some(())
}

/// A start element's declared permission (design 90 §4): `Some(Some(name))` for a counted
/// `<uses-permission*>` or a component's bind permission, `Some(None)` for nothing to count, `None`
/// when it can't be read - its `android:name` (or `android:permission`) not found by resource id,
/// present twice, not a plain string, too long; a `maxSdkVersion` that isn't a number.
fn permission_of(
    pool: &[u8],
    resource_ids: Option<&[u8]>,
    ext: &[u8],
    element: &str,
) -> Option<Option<String>> {
    if USES_PERMISSION.contains(&element) {
        let name = by_id(resource_ids, ext, ATTR_NAME)?;
        let name = attribute_string(pool, name?)?;
        let max_sdk = match by_id(resource_ids, ext, ATTR_MAX_SDK_VERSION)? {
            Some(attribute) => Some(match *attribute.get(15)? {
                TYPE_INT_DEC | TYPE_INT_HEX => u32_at(attribute, 16)?,
                _ => return None,
            }),
            None => None,
        };
        let counted = max_sdk.is_none_or(|max| max >= crate::catalog_holds::PHONE_MIN_SDK);
        return Some(counted.then_some(name));
    }
    match by_id(resource_ids, ext, ATTR_PERMISSION)? {
        None => Some(None),
        Some(attribute) => {
            let name = attribute_string(pool, attribute)?;
            Some(
                BIND_PERMISSIONS
                    .contains(&name.as_str())
                    .then(|| format!("{BIND_PREFIX}{name}")),
            )
        }
    }
}

/// The one attribute of a start element whose resource id is `attr` (Android reads attributes by
/// id; a name alone counts for nothing): `Some(None)` when there is none, `None` when there are two
/// or the attributes can't be read.
fn by_id<'a>(resource_ids: Option<&[u8]>, ext: &'a [u8], attr: u32) -> Option<Option<&'a [u8]>> {
    let attribute_start = usize::from(u16_at(ext, 8)?);
    let attribute_size = usize::from(u16_at(ext, 10)?);
    let count = usize::from(u16_at(ext, 12)?);
    if attribute_size < 20 {
        return None;
    }
    let mut found = None;
    for index in 0..count {
        let start = attribute_start.checked_add(index.checked_mul(attribute_size)?)?;
        let attribute = ext.get(start..start.checked_add(attribute_size)?)?;
        let name_index = u32_at(attribute, 4)?;
        let id = resource_ids.and_then(|ids| u32_at(ids, (name_index as usize).checked_mul(4)?));
        if id == Some(attr) {
            if found.is_some() {
                return None;
            }
            found = Some(attribute);
        }
    }
    Some(found)
}

/// Whether Android's attribute lookup sees a start element's attributes as this reader does.
/// `AttributeResolution`'s `RetrieveAttributes` (what `obtainAttributes` runs while the package is
/// parsed) walks the element's attributes and the wanted ids together, both taken as sorted: an
/// attribute after one with a higher id is never found. An attribute with no id (none in the
/// resource map, or 0) is never wanted and is skipped wherever it is. An id outside the android
/// package (`0x01`) may be rewritten by the APK's own dynamic-reference table before that walk,
/// so its order there can't be known. True when every id is 0 or an android id and those rise
/// strictly; false otherwise or when the attributes can't be read.
fn in_android_order(resource_ids: Option<&[u8]>, ext: &[u8]) -> bool {
    let ordered = || -> Option<bool> {
        let attribute_start = usize::from(u16_at(ext, 8)?);
        let attribute_size = usize::from(u16_at(ext, 10)?);
        let count = usize::from(u16_at(ext, 12)?);
        if attribute_size < 20 {
            return None;
        }
        let mut last = 0u32;
        for index in 0..count {
            let start = attribute_start.checked_add(index.checked_mul(attribute_size)?)?;
            let attribute = ext.get(start..start.checked_add(attribute_size)?)?;
            let name_index = u32_at(attribute, 4)?;
            let id = resource_ids
                .and_then(|ids| u32_at(ids, (name_index as usize).checked_mul(4)?))
                .unwrap_or(0);
            if id == 0 {
                continue;
            }
            if id >> 24 != 0x01 || id <= last {
                return Some(false);
            }
            last = id;
        }
        Some(true)
    };
    ordered() == Some(true)
}

/// A `<uses-sdk>`'s target SDK as Android's parser takes it for the split permissions, by resource
/// id only: `android:targetSdkVersion`, else `android:minSdkVersion`, else 1. A value that isn't a
/// plain number (a codename, a reference) counts as 1, below every split - never a higher target
/// than Android's. `None` when an attribute is there twice or can't be read.
fn target_sdk(resource_ids: Option<&[u8]>, ext: &[u8]) -> Option<i64> {
    let attribute = match by_id(resource_ids, ext, ATTR_TARGET_SDK_VERSION)? {
        Some(attribute) => Some(attribute),
        None => by_id(resource_ids, ext, ATTR_MIN_SDK_VERSION)?,
    };
    let Some(attribute) = attribute else {
        return Some(1);
    };
    Some(match *attribute.get(15)? {
        TYPE_INT_DEC | TYPE_INT_HEX => i64::from(u32_at(attribute, 16)? as i32),
        _ => 1,
    })
}

/// A permission attribute's string: the typed string, which the raw value must equal when both are
/// there (design 90 L3), else the raw value with a null type; anything else (a reference) `None`,
/// and an empty or over-long name too.
fn attribute_string(pool: &[u8], attribute: &[u8]) -> Option<String> {
    let raw = u32_at(attribute, 8)?;
    let raw = (raw != NO_INDEX).then(|| pool_string(pool, raw));
    let value = match *attribute.get(15)? {
        TYPE_STRING => {
            let typed = pool_string(pool, u32_at(attribute, 16)?)?;
            match raw {
                Some(raw) if raw.as_deref() != Some(typed.as_str()) => return None,
                _ => typed,
            }
        }
        TYPE_NULL => raw??,
        _ => return None,
    };
    // A NUL ends a string for Android's reader (QA H2): a value with one, or any control
    // character, would read differently there.
    (!value.is_empty()
        && value.len() <= MAX_PERMISSION_BYTES
        && !value.chars().any(char::is_control))
    .then_some(value)
}

/// The `android:` attribute `attr` (by its resource id, else by `name`) of a start element's
/// ResXMLTree_attrExt as a number; `None` when it isn't there, isn't a number, or can't be read.
fn int_attribute(
    pool: &[u8],
    resource_ids: Option<&[u8]>,
    ext: &[u8],
    attr: u32,
    name: &str,
) -> Option<u32> {
    let attribute_start = usize::from(u16_at(ext, 8)?);
    let attribute_size = usize::from(u16_at(ext, 10)?);
    let count = usize::from(u16_at(ext, 12)?);
    for index in 0..count {
        let start = attribute_start.checked_add(index.checked_mul(attribute_size)?)?;
        let attribute = ext.get(start..)?;
        let (namespace, name_index) = (u32_at(attribute, 0)?, u32_at(attribute, 4)?);
        if namespace == NO_INDEX {
            continue;
        }
        let id = resource_ids.and_then(|ids| u32_at(ids, (name_index as usize).checked_mul(4)?));
        let matches = match id {
            Some(id) => id == attr,
            None => pool_string(pool, name_index).as_deref() == Some(name),
        };
        if matches {
            return match *attribute.get(15)? {
                TYPE_INT_DEC | TYPE_INT_HEX => u32_at(attribute, 16),
                _ => None,
            };
        }
    }
    None
}

/// A string attribute's value (`package`, `android:versionName`): its raw string, else a typed
/// string; `None` otherwise (a reference) or when empty.
fn string_value(pool: &[u8], attribute: &[u8]) -> Option<String> {
    let raw = u32_at(attribute, 8)?;
    let value = if raw != NO_INDEX {
        raw
    } else if *attribute.get(15)? == TYPE_STRING {
        u32_at(attribute, 16)?
    } else {
        return None;
    };
    pool_string(pool, value).filter(|name| !name.is_empty())
}

/// `PackageInfo.getLongVersionCode`: the major code in the high 32 bits, the code's 32 bits below;
/// `None` past what an `i64` holds - a negative versionCodeMajor, so the row falls back to the
/// versionName (`tracked_apps::catalog_release_installed`).
fn long_version_code(code: u32, major: Option<u32>) -> Option<i64> {
    i64::try_from((u64::from(major.unwrap_or(0)) << 32) | u64::from(code)).ok()
}

/// String `index` of a ResStringPool chunk (UTF-8 or UTF-16).
fn pool_string(pool: &[u8], index: u32) -> Option<String> {
    let count = u32_at(pool, 8)?;
    if index >= count {
        return None;
    }
    let flags = u32_at(pool, 16)?;
    let strings_start = u32_at(pool, 20)? as usize;
    let header_size = usize::from(u16_at(pool, 2)?);
    let offset = u32_at(pool, header_size.checked_add(index as usize * 4)?)? as usize;
    let at = strings_start.checked_add(offset)?;
    if flags & UTF8_FLAG != 0 {
        // The UTF-16 length, then the UTF-8 length (each 1 or 2 bytes), then the bytes.
        let skip = |at: usize| -> Option<(usize, usize)> {
            let first = usize::from(*pool.get(at)?);
            if first & 0x80 != 0 {
                Some((
                    ((first & 0x7f) << 8) | usize::from(*pool.get(at + 1)?),
                    at + 2,
                ))
            } else {
                Some((first, at + 1))
            }
        };
        let (_, at) = skip(at)?;
        let (len, at) = skip(at)?;
        String::from_utf8(pool.get(at..at.checked_add(len)?)?.to_vec()).ok()
    } else {
        let first = usize::from(u16_at(pool, at)?);
        let (len, at) = if first & 0x8000 != 0 {
            (
                ((first & 0x7fff) << 16) | usize::from(u16_at(pool, at + 2)?),
                at + 4,
            )
        } else {
            (first, at + 2)
        };
        let units: Option<Vec<u16>> = (0..len).map(|i| u16_at(pool, at + i * 2)).collect();
        String::from_utf16(&units?).ok()
    }
}

#[cfg(test)]
pub(crate) mod tests {
    use super::*;

    const KEY1: &str = "0796f1809e476a2ce724f1341bcb32d03185b7a202c3469787c5775d65ef4be3";
    const KEY2: &str = "7639db09b107ba0392d32bf2e80aebc4e728c8ea06c091a0d4330fddaaddd434";

    /// The fixtures: aapt2 + apksigner (v2 and v3), two throwaway keys; the digests are what
    /// `apksigner verify --print-certs` prints.
    #[test]
    fn reads_the_package_and_the_signer_like_apksigner() {
        let read = |name: &str| read_identity(Path::new(&format!("testdata/apk/{name}"))).unwrap();
        assert_eq!(
            read("fixture-key1.apk"),
            ApkIdentity {
                package: Some("me.vibb.testfixture".to_string()),
                version_code: Some(1),
                version_name: Some("1".to_string()),
                min_sdk: Some(26),
                signer_sha256: Some(KEY1.to_string()),
                permissions: Some(Vec::new()),
                split_target: Some(36),
                shared_user_id: false,
                min_server: MinServer::Absent,
            }
        );
        assert_eq!(read("fixture-key1-v2.apk").version_code, Some(2));
        assert_eq!(
            read("fixture-key1-v2.apk").version_name.as_deref(),
            Some("2")
        );
        assert_eq!(
            read("fixture-key2.apk").signer_sha256.as_deref(),
            Some(KEY2)
        );
        assert_eq!(
            read("other-package-key1.apk").package.as_deref(),
            Some("me.vibb.otherfixture")
        );
        assert_eq!(
            read("firefox-package-key1.apk").package.as_deref(),
            Some("org.mozilla.firefox")
        );
        // JAR (v1) signing only: no signer read, the package still is.
        assert_eq!(
            read("fixture-key1-v1-only.apk"),
            ApkIdentity {
                package: Some("me.vibb.testfixture".to_string()),
                version_code: Some(1),
                version_name: Some("1".to_string()),
                min_sdk: Some(26),
                signer_sha256: None,
                permissions: Some(Vec::new()),
                split_target: Some(36),
                shared_user_id: false,
                min_server: MinServer::Absent,
            }
        );
    }

    /// A binary manifest by hand: a UTF-8 string pool (no resource map) and the `<manifest>`
    /// element claiming `count` attributes, each `[namespace, name, raw, type, data]`.
    fn manifest_xml(attributes: &[[u32; 5]], count: u16) -> Vec<u8> {
        xml_doc(
            &[
                "manifest",
                "package",
                "me.vibb.testfixture",
                "versionCode",
                "versionCodeMajor",
                "http://schemas.android.com/apk/res/android",
            ],
            &[],
            &[(0, attributes.to_vec(), count)],
        )
    }

    /// A binary XML document by hand: a UTF-8 string pool of `strings`, a resource map giving the
    /// first `ids.len()` strings those ids (none when empty), then one start element per
    /// `(name string, attributes, claimed count)`.
    fn xml_doc(strings: &[&str], ids: &[u32], elements: &[(u32, Vec<[u32; 5]>, u16)]) -> Vec<u8> {
        let mut chunks = vec![pool_chunk(strings)];
        if !ids.is_empty() {
            chunks.push(map_chunk(ids));
        }
        for (name, attributes, count) in elements {
            chunks.push(element_chunk(*name, attributes, *count));
        }
        document(&chunks)
    }

    /// A UTF-8 string pool of `strings` (each under 128 bytes).
    fn pool_chunk(strings: &[&str]) -> Vec<u8> {
        let mut data = Vec::new();
        let mut offsets = Vec::new();
        for text in strings {
            offsets.extend((data.len() as u32).to_le_bytes());
            data.extend([text.len() as u8, text.len() as u8]);
            data.extend(text.as_bytes());
            data.push(0);
        }
        while data.len() % 4 != 0 {
            data.push(0);
        }
        let mut pool = Vec::new();
        pool.extend(RES_STRING_POOL_TYPE.to_le_bytes());
        pool.extend(28u16.to_le_bytes());
        pool.extend(((28 + offsets.len() + data.len()) as u32).to_le_bytes());
        for word in [
            strings.len() as u32,
            0,
            UTF8_FLAG,
            28 + offsets.len() as u32,
            0,
        ] {
            pool.extend(word.to_le_bytes());
        }
        pool.extend(offsets);
        pool.extend(data);
        pool
    }

    /// A resource map giving the first strings `ids`.
    fn map_chunk(ids: &[u32]) -> Vec<u8> {
        let mut map = Vec::new();
        map.extend(RES_XML_RESOURCE_MAP_TYPE.to_le_bytes());
        map.extend(8u16.to_le_bytes());
        map.extend(((8 + 4 * ids.len()) as u32).to_le_bytes());
        for id in ids {
            map.extend(id.to_le_bytes());
        }
        map
    }

    /// A start element named by string `name` claiming `count` attributes, each `[namespace,
    /// name, raw, type, data]`.
    fn element_chunk(name: u32, attributes: &[[u32; 5]], count: u16) -> Vec<u8> {
        let mut element = Vec::new();
        element.extend(RES_XML_START_ELEMENT_TYPE.to_le_bytes());
        element.extend(16u16.to_le_bytes());
        element.extend(((36 + 20 * attributes.len()) as u32).to_le_bytes());
        element.extend(
            [0u32, NO_INDEX, NO_INDEX, name]
                .iter()
                .flat_map(|w| w.to_le_bytes()),
        );
        for half in [20u16, 20, count, 0, 0, 0] {
            element.extend(half.to_le_bytes());
        }
        for [namespace, name, raw, kind, value] in attributes {
            for word in [namespace, name, raw] {
                element.extend(word.to_le_bytes());
            }
            element.extend([8, 0, 0, *kind as u8]);
            element.extend(value.to_le_bytes());
        }
        element
    }

    /// A namespace start node (`RES_XML_START_NAMESPACE_TYPE`) for prefix and uri strings.
    fn namespace_chunk(prefix: u32, uri: u32) -> Vec<u8> {
        let mut node = Vec::new();
        node.extend(0x0100u16.to_le_bytes());
        node.extend(16u16.to_le_bytes());
        node.extend(24u32.to_le_bytes());
        for word in [0u32, NO_INDEX, prefix, uri] {
            node.extend(word.to_le_bytes());
        }
        node
    }

    /// The document of `chunks`, its size in its header.
    fn document(chunks: &[Vec<u8>]) -> Vec<u8> {
        let body: Vec<u8> = chunks.concat();
        let mut xml = Vec::new();
        xml.extend(RES_XML_TYPE.to_le_bytes());
        xml.extend(8u16.to_le_bytes());
        xml.extend(((8 + body.len()) as u32).to_le_bytes());
        xml.extend(body);
        xml
    }

    /// Without a resource map an attribute is known by its name, and only in the android
    /// namespace; a hex code and the major above it; a short attribute after the package keeps it.
    #[test]
    fn the_codes_by_name_in_the_android_namespace() {
        const ANDROID: u32 = 5;
        let package = [NO_INDEX, 1, 2, u32::from(TYPE_STRING), 2];
        let code = |namespace| [namespace, 3, NO_INDEX, u32::from(TYPE_INT_HEX), 7];
        let major = [ANDROID, 4, NO_INDEX, u32::from(TYPE_INT_DEC), 1];
        let read = |attributes: &[[u32; 5]], count| read_manifest(&manifest_xml(attributes, count));
        assert_eq!(
            read(&[package, code(ANDROID), major], 3),
            Some(Manifest {
                package: Some("me.vibb.testfixture".to_string()),
                version_code: Some((1 << 32) + 7),
                version_name: None,
                min_sdk: None,
                permissions: Some(Vec::new()),
                split_target: Some(0),
                shared_user_id: false,
                attributes_whole: true,
            })
        );
        assert_eq!(
            read(&[package, code(NO_INDEX)], 2),
            Some(Manifest {
                package: Some("me.vibb.testfixture".to_string()),
                version_code: None,
                version_name: None,
                min_sdk: None,
                permissions: Some(Vec::new()),
                split_target: Some(0),
                shared_user_id: false,
                attributes_whole: true,
            }),
            "a versionCode outside the android namespace isn't the code"
        );
        assert_eq!(
            read(&[package, code(ANDROID)], 4).and_then(|m| m.package),
            Some("me.vibb.testfixture".to_string()),
            "attributes past the element's end end the reading, the package kept"
        );
    }

    /// Design 90 §4: every `<uses-permission*>` (one capped below the phones' Android left out),
    /// and a component's bind permission, as `bind:`; another app's permission is still declared.
    #[test]
    fn the_declared_permissions_are_read_by_resource_id() {
        let read = |name: &str| read_identity(Path::new(&format!("testdata/apk/{name}"))).unwrap();
        assert_eq!(
            read("perms-key3-v2.apk").permissions,
            Some(
                [
                    "android.permission.ACCESS_COARSE_LOCATION",
                    "android.permission.ACCESS_FINE_LOCATION",
                    "android.permission.INTERNET",
                    "android.permission.READ_CONTACTS",
                    "bind:android.permission.BIND_ACCESSIBILITY_SERVICE",
                    "bind:android.permission.BIND_DEVICE_ADMIN",
                    "com.example.permission.OWN",
                ]
                .map(str::to_string)
                .to_vec()
            )
        );
        let v1 = read("perms-key3-v1.apk");
        assert_eq!(
            v1.permissions,
            Some(
                [
                    "android.permission.INTERNET",
                    "android.permission.POST_NOTIFICATIONS"
                ]
                .map(str::to_string)
                .to_vec()
            )
        );
        assert_eq!(v1.package.as_deref(), Some("me.vibb.permfixture"));
        assert_eq!(v1.min_sdk, Some(26), "uses-sdk read as before");
    }

    /// Design 90 §4: what Android reads and we might not is never "no permissions" - a name by
    /// the name alone, a reference, a name twice, typed and raw values that disagree, a damaged
    /// chunk after `<uses-sdk>`: `None`, the package still read.
    #[test]
    fn a_permission_list_that_cant_be_read_whole_is_none() {
        const STRINGS: [&str; 7] = [
            "name",
            "maxSdkVersion",
            "manifest",
            "package",
            "me.vibb.testfixture",
            "uses-permission",
            "android.permission.READ_CONTACTS",
        ];
        let ids = [ATTR_NAME, ATTR_MAX_SDK_VERSION];
        let ns = 9; // any namespace: Android goes by the id
        let manifest = (2, vec![[NO_INDEX, 3, 4, u32::from(TYPE_STRING), 4]], 1);
        let uses = |attributes: Vec<[u32; 5]>| (5, attributes.clone(), attributes.len() as u16);
        let name = [ns, 0, 6, u32::from(TYPE_STRING), 6];
        let read = |ids: &[u32], elements: Vec<(u32, Vec<[u32; 5]>, u16)>| {
            let mut all = vec![manifest.clone()];
            all.extend(elements);
            read_manifest(&xml_doc(&STRINGS, ids, &all)).unwrap()
        };
        assert_eq!(
            read(&ids, vec![uses(vec![name])]).permissions,
            Some(vec![
                // No `<uses-sdk>`: below every split's target.
                "android.permission.READ_CALL_LOG".to_string(),
                "android.permission.READ_CONTACTS".to_string()
            ])
        );
        assert_eq!(
            read(
                &ids,
                vec![uses(vec![
                    name,
                    [ns, 1, NO_INDEX, u32::from(TYPE_INT_DEC), 32]
                ])]
            )
            .permissions,
            Some(Vec::new()),
            "capped below Android 14: never requested on our phones"
        );
        let unreadable = [
            (
                "by its name only (no resource map)",
                &[][..],
                vec![uses(vec![name])],
            ),
            (
                "a reference",
                &ids[..],
                vec![uses(vec![[ns, 0, NO_INDEX, 0x01, 0x7f01_0000]])],
            ),
            ("the name twice", &ids[..], vec![uses(vec![name, name])]),
            (
                "typed and raw disagree",
                &ids[..],
                vec![uses(vec![[ns, 0, 4, u32::from(TYPE_STRING), 6]])],
            ),
            ("no name", &ids[..], vec![uses(vec![])]),
            (
                "maxSdkVersion not a number",
                &ids[..],
                vec![uses(vec![name, [ns, 1, 6, u32::from(TYPE_STRING), 6]])],
            ),
        ];
        for (case, ids, elements) in unreadable {
            let read = read(ids, elements);
            assert_eq!(read.permissions, None, "{case}");
            assert_eq!(
                read.package.as_deref(),
                Some("me.vibb.testfixture"),
                "{case}"
            );
        }
        // A damaged chunk after the elements: the walk doesn't reach the document's end.
        let mut xml = xml_doc(&STRINGS, &ids, &[manifest.clone(), uses(vec![name])]);
        xml.extend([0x02, 0x01, 0x10, 0x00, 0x04, 0x00, 0x00, 0x00]);
        let size = xml.len() as u32;
        xml[4..8].copy_from_slice(&size.to_le_bytes());
        let read = read_manifest(&xml).unwrap();
        assert_eq!(read.permissions, None);
        assert_eq!(read.package.as_deref(), Some("me.vibb.testfixture"));
        // A document cut short of its declared size.
        let mut xml = xml_doc(&STRINGS, &ids, &[manifest, uses(vec![name])]);
        let size = xml.len() as u32 + 8;
        xml[4..8].copy_from_slice(&size.to_le_bytes());
        assert_eq!(read_manifest(&xml).unwrap().permissions, None);
    }

    /// Android reads an element's attributes by walking them in resource-id order: attributes out
    /// of that order, or an id outside the android package (which the APK's own tables may map to
    /// another), are unreadable - a `maxSdkVersion` Android never sees must not drop a permission.
    /// An attribute without an id is skipped wherever it is.
    #[test]
    fn attributes_out_of_androids_order_are_unreadable() {
        const STRINGS: [&str; 8] = [
            "name",
            "maxSdkVersion",
            "label",
            "manifest",
            "package",
            "me.vibb.testfixture",
            "uses-permission",
            "android.permission.ACCESS_FINE_LOCATION",
        ];
        let ns = 9;
        let manifest = (3, vec![[NO_INDEX, 4, 5, u32::from(TYPE_STRING), 5]], 1);
        let uses = |attributes: Vec<[u32; 5]>| (6, attributes.clone(), attributes.len() as u16);
        let name = [ns, 0, 7, u32::from(TYPE_STRING), 7];
        let capped = [ns, 1, NO_INDEX, u32::from(TYPE_INT_DEC), 32];
        let no_id = [NO_INDEX, 3, NO_INDEX, u32::from(TYPE_INT_DEC), 1];
        let read = |ids: &[u32], attributes: Vec<[u32; 5]>| {
            read_manifest(&xml_doc(
                &STRINGS,
                ids,
                &[manifest.clone(), uses(attributes)],
            ))
            .unwrap()
        };
        let ids = [ATTR_NAME, ATTR_MAX_SDK_VERSION];
        assert_eq!(
            read(&ids, vec![name, capped]).permissions,
            Some(Vec::new()),
            "in order: capped below Android 14, left out"
        );
        assert_eq!(
            read(&ids, vec![no_id, name, no_id, capped, no_id]).permissions,
            Some(Vec::new()),
            "attributes without an id anywhere"
        );
        let swapped = read(&ids, vec![capped, name]);
        assert_eq!(swapped.permissions, None, "maxSdkVersion before the name");
        assert_eq!(swapped.package.as_deref(), Some("me.vibb.testfixture"));
        // An id outside the android package, here before the name.
        let ids = [ATTR_NAME, ATTR_MAX_SDK_VERSION, 0x0501_0001];
        let label = [ns, 2, 7, u32::from(TYPE_STRING), 7];
        assert_eq!(read(&ids, vec![label, name]).permissions, None);
        assert_eq!(read(&ids, vec![name, label]).permissions, None);
        // The order matters on every element, not only `<uses-permission>`.
        let ids = [ATTR_NAME, ATTR_PERMISSION];
        let other = read_manifest(&xml_doc(
            &STRINGS,
            &ids,
            &[
                manifest.clone(),
                uses(vec![name]),
                (2, vec![[ns, 1, 7, u32::from(TYPE_STRING), 7], name], 2),
            ],
        ))
        .unwrap();
        assert_eq!(other.permissions, None);
    }

    /// Android adds split permissions for a low target SDK (`targetSdkVersion`, else
    /// `minSdkVersion`, else none at all): an update that lowers it with fine location asks for
    /// background location too, and is held for it.
    #[test]
    fn a_lower_target_sdk_adds_the_split_permissions() {
        const USES_SDK: [&str; 9] = [
            "name",
            "minSdkVersion",
            "targetSdkVersion",
            "manifest",
            "package",
            "me.vibb.testfixture",
            "uses-permission",
            "android.permission.ACCESS_FINE_LOCATION",
            "uses-sdk",
        ];
        let ids = [ATTR_NAME, ATTR_MIN_SDK_VERSION, ATTR_TARGET_SDK_VERSION];
        let ns = 9;
        let manifest = (3, vec![[NO_INDEX, 4, 5, u32::from(TYPE_STRING), 5]], 1);
        let fine = (6, vec![[ns, 0, 7, u32::from(TYPE_STRING), 7]], 1);
        let int = |name: u32, value: u32| [ns, name, NO_INDEX, u32::from(TYPE_INT_DEC), value];
        let sdk = |attributes: Vec<[u32; 5]>| (8, attributes.clone(), attributes.len() as u16);
        let read = |uses_sdk: Option<Vec<[u32; 5]>>| {
            let mut elements = vec![manifest.clone()];
            elements.extend(uses_sdk.map(sdk));
            elements.push(fine.clone());
            read_manifest(&xml_doc(&USES_SDK, &ids, &elements))
                .unwrap()
                .permissions
        };
        let names = |list: &[&str]| -> Vec<String> {
            list.iter()
                .map(|name| format!("android.permission.{name}"))
                .collect()
        };
        let now = names(&["ACCESS_COARSE_LOCATION", "ACCESS_FINE_LOCATION"]);
        let old = names(&[
            "ACCESS_BACKGROUND_LOCATION",
            "ACCESS_COARSE_LOCATION",
            "ACCESS_FINE_LOCATION",
        ]);
        assert_eq!(read(Some(vec![int(1, 26), int(2, 34)])), Some(now.clone()));
        assert_eq!(read(Some(vec![int(1, 26), int(2, 29)])), Some(now.clone()));
        assert_eq!(read(Some(vec![int(1, 26), int(2, 28)])), Some(old.clone()));
        assert_eq!(
            read(Some(vec![int(1, 28)])),
            Some(old.clone()),
            "no target: the min"
        );
        assert_eq!(
            read(Some(vec![int(1, 34)])),
            Some(now.clone()),
            "no target: the min"
        );
        assert_eq!(read(Some(vec![])), Some(old.clone()), "neither: 1");
        assert_eq!(read(None), Some(old.clone()), "no <uses-sdk>: 0");
        let codename = [ns, 2, 7, u32::from(TYPE_STRING), 7];
        assert_eq!(
            read(Some(vec![codename])),
            Some(old.clone()),
            "a codename: 1"
        );
        assert_eq!(
            read(Some(vec![int(2, 34), int(2, 34)])),
            None,
            "a target twice"
        );
        // The update that lowers the target is held for background location alone.
        let accepted = crate::catalog_holds::sensitive(&now);
        assert_eq!(
            crate::catalog_holds::judge(false, Some(&old), Some(&accepted), false),
            crate::catalog_holds::Verdict::Hold(
                crate::catalog_holds::Reason::Permissions,
                names(&["ACCESS_BACKGROUND_LOCATION"])
            )
        );
        assert_eq!(
            crate::catalog_holds::judge(false, Some(&now), Some(&accepted), false),
            crate::catalog_holds::Verdict::Serve
        );
        // An accepted set stored without splits ([FINE]), widened by the served release at its
        // target: an unchanged update at the same target isn't held; a lowered target still is.
        let stored = names(&["ACCESS_FINE_LOCATION"]);
        let judge = |update: &[String], accepted: &[String]| {
            crate::catalog_holds::judge(false, Some(update), Some(accepted), false)
        };
        assert!(matches!(
            judge(&now, &stored),
            crate::catalog_holds::Verdict::Hold(..)
        ));
        let widened = crate::catalog_holds::with_splits(&stored, &now, 34);
        assert_eq!(widened, now);
        assert_eq!(judge(&now, &widened), crate::catalog_holds::Verdict::Serve);
        let widened_old = crate::catalog_holds::with_splits(&stored, &old, 28);
        assert_eq!(widened_old, old);
        assert_eq!(
            judge(&old, &widened_old),
            crate::catalog_holds::Verdict::Serve
        );
        assert_eq!(
            judge(&old, &widened),
            crate::catalog_holds::Verdict::Hold(
                crate::catalog_holds::Reason::Permissions,
                names(&["ACCESS_BACKGROUND_LOCATION"])
            )
        );
        // Only what the accepted names bring: a split of a name not accepted isn't added.
        assert_eq!(
            crate::catalog_holds::with_splits(&[], &old, 28),
            Vec::<String>::new()
        );
    }

    /// Design 90 QA H1, H2, L5: Android takes the string pool and resource map only before the
    /// first node, so a later one makes the list unreadable and never renames the package; a NUL
    /// or control character in an element's name or a permission is unreadable; `-sdk-23` counts;
    /// over 500 names is unreadable.
    #[test]
    fn what_android_reads_otherwise_is_unreadable() {
        let strings = [
            "name",
            "manifest",
            "package",
            "me.vibb.testfixture",
            "uses-permission",
            "android.permission.READ_CONTACTS",
            "uses-permission-sdk-23",
            "android.permission.CAMERA\0",
            "uses-permission\0x",
            "android",
            "http://schemas.android.com/apk/res/android",
        ];
        let ids = [ATTR_NAME];
        let manifest = element_chunk(1, &[[NO_INDEX, 2, 3, u32::from(TYPE_STRING), 3]], 1);
        let uses = |element: u32, value: u32| {
            element_chunk(element, &[[9, 0, value, u32::from(TYPE_STRING), value]], 1)
        };
        let read = |chunks: Vec<Vec<u8>>| read_manifest(&document(&chunks)).unwrap();
        let base = || vec![pool_chunk(&strings), map_chunk(&ids), manifest.clone()];

        let mut sdk23 = base();
        sdk23.push(uses(6, 5));
        assert_eq!(
            read(sdk23).permissions,
            Some(vec![
                // No `<uses-sdk>`: below every split's target.
                "android.permission.READ_CALL_LOG".to_string(),
                "android.permission.READ_CONTACTS".to_string()
            ])
        );
        // A second pool (or map) after the first node: Android ignores it, we can't follow.
        for late in [pool_chunk(&strings), map_chunk(&ids)] {
            let mut chunks = base();
            chunks.push(late);
            chunks.push(uses(4, 5));
            let read = read(chunks);
            assert_eq!(read.permissions, None);
            assert_eq!(read.package.as_deref(), Some("me.vibb.testfixture"));
        }
        // A pool between the namespace node and <manifest> doesn't rename the package.
        let other = ["name", "manifest", "package", "org.example.other"];
        let renamed = read(vec![
            pool_chunk(&strings),
            map_chunk(&ids),
            namespace_chunk(9, 10),
            pool_chunk(&other),
            manifest.clone(),
        ]);
        assert_eq!(renamed.package.as_deref(), Some("me.vibb.testfixture"));
        assert_eq!(renamed.permissions, None);
        // A NUL in a permission's value, or in an element's name.
        for (element, value) in [(4, 7), (8, 5)] {
            let mut chunks = base();
            chunks.push(uses(element, value));
            assert_eq!(read(chunks).permissions, None, "{element} {value}");
        }
        // 500 names read, 501 don't.
        for (count, readable) in [(500, true), (501, false)] {
            let mut chunks = base();
            chunks.extend((0..count).map(|_| uses(4, 5)));
            assert_eq!(read(chunks).permissions.is_some(), readable, "{count}");
        }
    }

    #[test]
    fn damaged_input_is_none_never_a_panic() {
        let good = std::fs::read("testdata/apk/fixture-key1.apk").unwrap();
        let dir = tempfile::tempdir().unwrap();
        // Every truncation, and a byte flipped at many places: no panic, and never a wrong
        // signer (a damaged block reads as none or as the right one).
        for cut in (0..good.len()).step_by(97) {
            let path = dir.path().join("cut.apk");
            std::fs::write(&path, &good[..cut]).unwrap();
            if let Ok(identity) = read_identity(&path) {
                assert!(
                    identity.signer_sha256.is_none_or(|s| s == KEY1),
                    "cut {cut}"
                );
            }
        }
        for at in (0..good.len()).step_by(13) {
            let mut bytes = good.clone();
            bytes[at] ^= 0xff;
            let path = dir.path().join("flip.apk");
            std::fs::write(&path, &bytes).unwrap();
            let _ = read_identity(&path);
        }
        for junk in [&b""[..], b"PK\x03\x04", b"<html>not an apk</html>"] {
            let path = dir.path().join("junk.apk");
            std::fs::write(&path, junk).unwrap();
            assert!(read_identity(&path).is_err());
        }
        assert_eq!(read_manifest(b""), None);
        assert_eq!(read_manifest(&[3, 0, 8, 0, 8, 0, 0, 0]), None);
        // versionCodeMajor above the code, as Android reads them; the code's sign bit is a bit.
        assert_eq!(long_version_code(26_111_139, None), Some(26_111_139));
        assert_eq!(long_version_code(7, Some(1)), Some((1 << 32) + 7));
        assert_eq!(long_version_code(u32::MAX, None), Some(0xffff_ffff));
        assert_eq!(long_version_code(0, Some(u32::MAX)), None);
    }

    /// The ID-value pairs of an APK's signing block, in order.
    fn signing_pairs(apk: &[u8]) -> Vec<(u32, Vec<u8>)> {
        let (block_start, central_directory, _) = block_bounds(apk);
        let pairs = &apk[block_start + 8..central_directory - 24];
        let mut found = Vec::new();
        let mut at = 0;
        while at < pairs.len() {
            let len = u64_at(pairs, at).unwrap() as usize;
            found.push((
                u32_at(pairs, at + 8).unwrap(),
                pairs[at + 12..at + 8 + len].to_vec(),
            ));
            at += 8 + len;
        }
        found
    }

    /// A test APK (design 140): `apk`'s entries with [MIN_SERVER_ENTRY] holding `text` (none for
    /// `None`, an earlier one dropped), and `apk`'s signing block - so the signer reads as before.
    pub(crate) fn with_min_server(apk: &[u8], text: Option<&[u8]>) -> Vec<u8> {
        let mut archive = zip::ZipArchive::new(std::io::Cursor::new(apk)).unwrap();
        let mut zip = zip::ZipWriter::new(std::io::Cursor::new(Vec::new()));
        for i in 0..archive.len() {
            let entry = archive.by_index_raw(i).unwrap();
            if entry.name() != MIN_SERVER_ENTRY {
                zip.raw_copy_file(entry).unwrap();
            }
        }
        if let Some(text) = text {
            let stored = zip::write::SimpleFileOptions::default()
                .compression_method(zip::CompressionMethod::Stored);
            zip.start_file(MIN_SERVER_ENTRY, stored).unwrap();
            std::io::Write::write_all(&mut zip, text).unwrap();
        }
        let unsigned = zip.finish().unwrap().into_inner();
        with_signing_pairs(&unsigned, &signing_pairs(apk))
    }

    /// Design 140 §4.1: [MIN_SERVER_ENTRY] read from an APK the test writes, the signer kept;
    /// the contract's rows (`contracts/music/min_server.json`) through [parse_min_server].
    #[test]
    fn the_min_server_entry_is_read() {
        let base = std::fs::read("testdata/apk/launcher-key4.apk").unwrap();
        let dir = tempfile::tempdir().unwrap();
        let read = |text: Option<&[u8]>| {
            let path = dir.path().join("min.apk");
            std::fs::write(&path, with_min_server(&base, text)).unwrap();
            read_identity(&path).unwrap()
        };
        let plain = read_identity(Path::new("testdata/apk/launcher-key4.apk")).unwrap();
        assert_eq!(plain.min_server, MinServer::Absent);
        let with = read(Some(b"0.95.0\n"));
        assert_eq!(with.min_server, MinServer::Version((0, 95, 0)));
        assert_eq!(with.signer_sha256, plain.signer_sha256);
        assert_eq!(with.package, plain.package);
        assert_eq!(read(None).min_server, MinServer::Absent);
        assert_eq!(
            read(Some(b"0.95.0")).min_server,
            MinServer::Version((0, 95, 0))
        );
        for bad in [
            &b"00.1.0"[..],
            b"0.95",
            b"",
            &[b'1'; 33][..],
            b"0.95.0\n                         x",
        ] {
            assert_eq!(read(Some(bad)).min_server, MinServer::Unreadable, "{bad:?}");
        }
        // 33 bytes is one too many even when the first 32 would read; 32 is read whole.
        let mut long = b"0.95.0\n".to_vec();
        long.resize(33, b' ');
        assert_eq!(parse_min_server(&long), MinServer::Unreadable);

        let contract: serde_json::Value =
            serde_json::from_str(crate::contracts::contract!("music/min_server.json")).unwrap();
        assert_eq!(contract["entry"], MIN_SERVER_ENTRY);
        assert_eq!(contract["max_bytes"], MIN_SERVER_MAX_BYTES);
        let rows = contract["files"].as_array().unwrap();
        assert!(rows.len() >= 10);
        for row in rows {
            let text = row["text"].as_str().unwrap();
            let want = match row["version"].as_array() {
                Some(v) => MinServer::Version((
                    v[0].as_u64().unwrap(),
                    v[1].as_u64().unwrap(),
                    v[2].as_u64().unwrap(),
                )),
                None => MinServer::Unreadable,
            };
            assert_eq!(parse_min_server(text.as_bytes()), want, "{text:?}");
        }
    }

    /// Where an APK's signing block starts, where its central directory starts, and where the
    /// End of Central Directory record is.
    fn block_bounds(apk: &[u8]) -> (usize, usize, usize) {
        let eocd = (0..=apk.len() - 22)
            .rev()
            .find(|&at| apk[at..at + 4] == [0x50, 0x4b, 0x05, 0x06])
            .unwrap();
        let central_directory = u32_at(apk, eocd + 16).unwrap() as usize;
        let size = u64_at(apk, central_directory - 24).unwrap() as usize;
        (central_directory - size - 8, central_directory, eocd)
    }

    /// A test APK: `apk` (or a zip without a signing block, which then gets one) with its signing
    /// block replaced by one of `pairs`, the central directory's offset moved to match. The
    /// signatures in it are never checked here; only what the reader picks.
    pub(crate) fn with_signing_pairs(apk: &[u8], pairs: &[(u32, Vec<u8>)]) -> Vec<u8> {
        let eocd = (0..=apk.len() - 22)
            .rev()
            .find(|&at| apk[at..at + 4] == [0x50, 0x4b, 0x05, 0x06])
            .unwrap();
        let central_directory = u32_at(apk, eocd + 16).unwrap() as usize;
        let signed = central_directory >= 24
            && &apk[central_directory - 16..central_directory] == SIGNING_BLOCK_MAGIC;
        let block_start = if signed {
            block_bounds(apk).0
        } else {
            central_directory
        };
        let mut body = Vec::new();
        for (id, value) in pairs {
            body.extend(((value.len() + 4) as u64).to_le_bytes());
            body.extend(id.to_le_bytes());
            body.extend(value);
        }
        let size = (body.len() + 24) as u64;
        let mut block = size.to_le_bytes().to_vec();
        block.extend(body);
        block.extend(size.to_le_bytes());
        block.extend(SIGNING_BLOCK_MAGIC);
        let mut out = apk[..block_start].to_vec();
        out.extend(&block);
        let new_directory = out.len() as u32;
        out.extend(&apk[central_directory..]);
        let eocd = eocd - central_directory + new_directory as usize;
        out[eocd + 16..eocd + 20].copy_from_slice(&new_directory.to_le_bytes());
        out
    }

    /// `bytes` with its u32 length before it.
    fn with_length(bytes: &[u8]) -> Vec<u8> {
        let mut out = (bytes.len() as u32).to_le_bytes().to_vec();
        out.extend(bytes);
        out
    }

    /// A v3 signer for `certificate` over `min..=max` (outside the signed data; `inside` the
    /// signed data's own range), with no digests, signatures or key - never checked here.
    fn v3_signer(certificate: &[u8], (min, max): (i32, i32), inside: (i32, i32)) -> Vec<u8> {
        let mut signed_data = with_length(&[]);
        signed_data.extend(with_length(&with_length(certificate)));
        signed_data.extend(inside.0.to_le_bytes());
        signed_data.extend(inside.1.to_le_bytes());
        signed_data.extend(with_length(&[]));
        let mut signer = with_length(&signed_data);
        signer.extend(min.to_le_bytes());
        signer.extend(max.to_le_bytes());
        signer.extend(with_length(&[]));
        signer.extend(with_length(&[]));
        signer
    }

    /// A v3 block of `signers`, each `(certificate, range)`.
    fn v3_block(signers: &[(&[u8], (i32, i32))]) -> Vec<u8> {
        let all: Vec<u8> = signers
            .iter()
            .flat_map(|(certificate, range)| with_length(&v3_signer(certificate, *range, *range)))
            .collect();
        with_length(&all)
    }

    /// The signer read from `fixture-key1.apk` with its signing block made of `pairs`.
    fn signer_with(pairs: &[(u32, Vec<u8>)]) -> Option<String> {
        let apk = with_signing_pairs(
            &std::fs::read("testdata/apk/fixture-key1.apk").unwrap(),
            pairs,
        );
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("test.apk");
        std::fs::write(&path, apk).unwrap();
        read_identity(&path).unwrap().signer_sha256
    }

    /// One block of each kind: a second v2, v3 or v3.1 block (Android reads the first) makes the signer
    /// unreadable; another id beside them changes nothing.
    #[test]
    fn a_second_signing_block_of_a_kind_is_unreadable() {
        let pairs = signing_pairs(&std::fs::read("testdata/apk/fixture-key1.apk").unwrap());
        let ids: Vec<u32> = pairs.iter().map(|(id, _)| *id).collect();
        assert!(
            ids.contains(&V2_BLOCK_ID) && ids.contains(&V3_BLOCK_ID),
            "{ids:x?}"
        );
        assert_eq!(signer_with(&pairs).as_deref(), Some(KEY1), "unchanged");
        let mut other = pairs.clone();
        other.push((0x4242_4242, vec![0; 16]));
        assert_eq!(signer_with(&other).as_deref(), Some(KEY1));
        let mut with_v31 = pairs.clone();
        with_v31.push((V31_BLOCK_ID, vec![0; 16]));
        assert_eq!(signer_with(&with_v31).as_deref(), Some(KEY1), "one v3.1");
        with_v31.push((V31_BLOCK_ID, vec![0; 16]));
        assert_eq!(signer_with(&with_v31), None, "two v3.1");
        for id in [V2_BLOCK_ID, V3_BLOCK_ID] {
            let mut twice = pairs.clone();
            let again = pairs.iter().find(|(pair, _)| *pair == id).unwrap().clone();
            twice.push(again);
            assert_eq!(signer_with(&twice), None, "{id:x}");
        }
    }

    /// The v3 signer is the one Android picks on each SDK our phones run (34 to 37), else v2's:
    /// one certificate for all four, or none.
    #[test]
    fn the_signer_is_the_one_android_picks_on_every_phone_sdk() {
        let first = |name: &str| {
            let pairs = signing_pairs(&std::fs::read(format!("testdata/apk/{name}")).unwrap());
            let v2 = pairs
                .iter()
                .find(|(id, _)| *id == V2_BLOCK_ID)
                .unwrap()
                .1
                .clone();
            (first_certificate(&v2).unwrap(), v2)
        };
        let (key1, v2_key1) = first("fixture-key1.apk");
        let (key2, v2_key2) = first("fixture-key2.apk");
        let digest = |certificate: &[u8]| hex::encode(Sha256::digest(certificate));
        assert_eq!(digest(&key1), KEY1);
        assert_eq!(digest(&key2), KEY2);
        let max = i32::MAX;
        let read = |v2: Option<&Vec<u8>>, signers: &[(&[u8], (i32, i32))]| {
            let mut pairs = Vec::new();
            if let Some(v2) = v2 {
                pairs.push((V2_BLOCK_ID, v2.clone()));
            }
            pairs.push((V3_BLOCK_ID, v3_block(signers)));
            signer_with(&pairs)
        };
        let k1: &[u8] = &key1;
        let k2: &[u8] = &key2;
        // One signer for our SDKs, another for older ones.
        assert_eq!(
            read(Some(&v2_key1), &[(k1, (24, 33)), (k2, (34, max))]).as_deref(),
            Some(KEY2)
        );
        // Android 14 would check one key, 15 to 17 another: refused.
        assert_eq!(
            read(Some(&v2_key1), &[(k1, (28, 34)), (k2, (35, max))]),
            None
        );
        assert_eq!(
            read(Some(&v2_key1), &[(k2, (28, 36)), (k1, (37, max))]),
            None
        );
        // Two signers for one SDK: Android refuses the APK, the same key or not.
        assert_eq!(
            read(Some(&v2_key1), &[(k1, (28, max)), (k1, (34, 34))]),
            None
        );
        assert_eq!(
            read(Some(&v2_key1), &[(k1, (28, max)), (k2, (37, max))]),
            None
        );
        // A v3 block with no signer for an SDK: Android finds no signer there (never v2's).
        assert_eq!(read(Some(&v2_key1), &[(k1, (28, 33))]), None);
        assert_eq!(read(None, &[(k1, (28, 33))]), None, "and no v2");
        assert_eq!(read(Some(&v2_key1), &[(k1, (28, 35))]), None, "36 and 37");
        // The range as Android's Java `int`: a max of 0xffffffff is -1, holding no SDK.
        assert_eq!(read(Some(&v2_key1), &[(k1, (28, -1))]), None);
        // With a v3.1 block (a key rotated for newer SDKs, not read) the original key stands:
        // the v3 signer where it holds the SDK, else v2's, else the v3 block's only signer.
        let rotated = |v2: Option<&Vec<u8>>, signers: &[(&[u8], (i32, i32))]| {
            let mut pairs = Vec::new();
            if let Some(v2) = v2 {
                pairs.push((V2_BLOCK_ID, v2.clone()));
            }
            pairs.push((V3_BLOCK_ID, v3_block(signers)));
            pairs.push((V31_BLOCK_ID, v3_block(&[(k2, (34, max))])));
            signer_with(&pairs)
        };
        assert_eq!(
            rotated(Some(&v2_key1), &[(k1, (28, 33))]).as_deref(),
            Some(KEY1)
        );
        assert_eq!(rotated(None, &[(k1, (28, 33))]).as_deref(), Some(KEY1));
        assert_eq!(
            rotated(Some(&v2_key1), &[(k1, (28, 35))]).as_deref(),
            Some(KEY1)
        );
        assert_eq!(rotated(Some(&v2_key2), &[(k1, (28, 35))]), None, "two keys");
        // The signed data's range must be the one outside it.
        let mismatched = with_length(&with_length(&v3_signer(k1, (28, max), (28, 33))));
        assert_eq!(
            signer_with(&[(V2_BLOCK_ID, v2_key1.clone()), (V3_BLOCK_ID, mismatched)]),
            None
        );
    }

    /// A test APK of `base`'s signing block with a manifest of package `package` declaring a
    /// shared user ID and `INTERNET` (built by hand here: no build tool or key is involved).
    pub(crate) fn shared_user_apk(base: &[u8], package: &str) -> Vec<u8> {
        const ANDROID: u32 = 4;
        let manifest = xml_doc(
            &[
                "sharedUserId",
                "name",
                "manifest",
                "package",
                "http://schemas.android.com/apk/res/android",
                "uses-permission",
                "android.permission.INTERNET",
                "me.vibb.shared",
                package,
            ],
            &[ATTR_SHARED_USER_ID, ATTR_NAME],
            &[
                (
                    2,
                    vec![
                        [ANDROID, 0, 7, u32::from(TYPE_STRING), 7],
                        [NO_INDEX, 3, 8, u32::from(TYPE_STRING), 8],
                    ],
                    2,
                ),
                (5, vec![[ANDROID, 1, 6, u32::from(TYPE_STRING), 6]], 1),
            ],
        );
        let mut zip = zip::ZipWriter::new(std::io::Cursor::new(Vec::new()));
        let stored = zip::write::SimpleFileOptions::default()
            .compression_method(zip::CompressionMethod::Stored);
        zip.start_file("AndroidManifest.xml", stored).unwrap();
        std::io::Write::write_all(&mut zip, &manifest).unwrap();
        let unsigned = zip.finish().unwrap().into_inner();
        with_signing_pairs(&unsigned, &signing_pairs(base))
    }

    /// `android:sharedUserId` on `<manifest>`, by its resource id or by its name: declared, and
    /// the permission list is held as unreadable; a `<manifest>` whose attributes can't all be
    /// read could hide one, so its list is unreadable too.
    #[test]
    fn a_shared_user_id_is_read_from_the_manifest() {
        let base = std::fs::read("testdata/apk/perms-key3-v1.apk").unwrap();
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("shared.apk");
        std::fs::write(&path, shared_user_apk(&base, "me.vibb.permfixture")).unwrap();
        let shared = read_identity(&path).unwrap();
        assert!(shared.shared_user_id);
        assert_eq!(shared.package.as_deref(), Some("me.vibb.permfixture"));
        assert_eq!(
            shared.signer_sha256,
            read_identity(Path::new("testdata/apk/perms-key3-v1.apk"))
                .unwrap()
                .signer_sha256
        );
        assert_eq!(
            shared.permissions,
            Some(vec!["android.permission.INTERNET".to_string()])
        );
        assert_eq!(crate::catalog_holds::judged_permissions(&shared), None);
        let plain = read_identity(Path::new("testdata/apk/perms-key3-v1.apk")).unwrap();
        assert!(!plain.shared_user_id);
        assert_eq!(
            crate::catalog_holds::judged_permissions(&plain),
            plain.permissions.as_deref()
        );

        // By name alone (no resource map), in any namespace.
        let strings = [
            "manifest",
            "package",
            "me.vibb.testfixture",
            "sharedUserId",
            "x",
        ];
        let package = [NO_INDEX, 1, 2, u32::from(TYPE_STRING), 2];
        for namespace in [NO_INDEX, 9] {
            let shared = [namespace, 3, 4, u32::from(TYPE_STRING), 4];
            let read = read_manifest(&xml_doc(&strings, &[], &[(0, vec![package, shared], 2)]));
            assert!(read.unwrap().shared_user_id, "{namespace}");
        }
        // Capped below our phones' Android by `sharedUserMaxSdkVersion` (by resource id, once, a
        // plain number): not joined there. Capped at 34 or above, 0, twice, or by name: joined.
        let capped = [
            "sharedUserMaxSdkVersion",
            "manifest",
            "package",
            "me.vibb.testfixture",
            "sharedUserId",
            "x",
        ];
        let joins = |caps: &[u32], ids: &[u32]| {
            let mut attributes = vec![
                [NO_INDEX, 2, 3, u32::from(TYPE_STRING), 3],
                [9, 4, 5, u32::from(TYPE_STRING), 5],
            ];
            attributes.extend(
                caps.iter()
                    .map(|cap| [9, 0, NO_INDEX, u32::from(TYPE_INT_DEC), *cap]),
            );
            let count = attributes.len() as u16;
            read_manifest(&xml_doc(&capped, ids, &[(1, attributes, count)]))
                .unwrap()
                .shared_user_id
        };
        let cap_id = [ATTR_SHARED_USER_MAX_SDK_VERSION];
        assert!(!joins(&[32], &cap_id));
        assert!(!joins(&[33], &cap_id));
        for (caps, ids) in [
            (&[34][..], &cap_id[..]),
            (&[37], &cap_id),
            (&[0], &cap_id),
            (&[32, 32], &cap_id),
            (&[32], &[]),
        ] {
            assert!(joins(caps, ids), "{caps:?} {ids:x?}");
        }
        // Claiming more attributes than are there: the list is unreadable.
        let cut = read_manifest(&xml_doc(&strings, &[], &[(0, vec![package], 3)])).unwrap();
        assert_eq!(cut.package.as_deref(), Some("me.vibb.testfixture"));
        assert!(!cut.shared_user_id);
        assert_eq!(cut.permissions, None);
    }
}
