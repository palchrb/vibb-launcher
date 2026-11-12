//! What an APK says it is (security review M10): its package name and versionCode, from the
//! binary `AndroidManifest.xml`, and the SHA-256 of its signing certificate, from the APK Signing
//! Block (scheme v3, else v2). The catalog pins the package and the signer from an app's first
//! release and refuses a later release that differs (`handlers::tracked_apps`); the phones get
//! them with the app list. The versionCode is how a phone's report is known to run the stored
//! release (`app_updated`).
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

#[derive(Debug, PartialEq, Eq)]
pub struct ApkIdentity {
    /// The manifest's `package`; `None` when the manifest couldn't be read.
    pub package: Option<String>,
    /// The manifest's `android:versionCode`, with `android:versionCodeMajor` above it as Android's
    /// `PackageInfo.getLongVersionCode` reads them; `None` when it couldn't be read (a reference,
    /// not a number) or the manifest couldn't.
    pub version_code: Option<i64>,
    /// SHA-256 (lower-case hex) of the first signer's certificate; `None` without a v2/v3
    /// signature (a v1-only APK) or when the block couldn't be read.
    pub signer_sha256: Option<String>,
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
    Ok(ApkIdentity {
        package: manifest.package,
        version_code: manifest.version_code,
        signer_sha256,
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

/// The DER certificate of the first signer in the v3 block, else the v2 block.
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

    // ID-value pairs between the leading size and the footer.
    let pairs = block.get(8..block.len() - 24)?;
    let (mut v2, mut v3) = (None, None);
    let mut at = 0usize;
    while at < pairs.len() {
        let pair_len = usize::try_from(u64_at(pairs, at)?).ok()?;
        let value = pairs.get(at.checked_add(12)?..at.checked_add(8)?.checked_add(pair_len)?)?;
        match u32_at(pairs, at + 8)? {
            V2_BLOCK_ID => v2 = Some(value),
            V3_BLOCK_ID => v3 = Some(value),
            _ => {}
        }
        at = at.checked_add(8)?.checked_add(pair_len)?;
    }
    first_certificate(v3.or(v2)?)
}

/// A u32-length-prefixed slice at `at`, and where the next one starts.
fn prefixed(bytes: &[u8], at: usize) -> Option<(&[u8], usize)> {
    let len = u32_at(bytes, at)? as usize;
    let start = at.checked_add(4)?;
    let end = start.checked_add(len)?;
    Some((bytes.get(start..end)?, end))
}

/// v2 and v3 share the start: signers (prefixed) > first signer (prefixed) > signed data
/// (prefixed) > digests (prefixed), then certificates (prefixed) > first certificate (prefixed).
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
const RES_XML_RESOURCE_MAP_TYPE: u16 = 0x0180;
const UTF8_FLAG: u32 = 1 << 8;
const NO_INDEX: u32 = 0xffff_ffff;
const TYPE_STRING: u8 = 0x03;
const TYPE_INT_DEC: u8 = 0x10;
const TYPE_INT_HEX: u8 = 0x11;
/// `android.R.attr.versionCode` and `versionCodeMajor`.
const ATTR_VERSION_CODE: u32 = 0x0101_021b;
const ATTR_VERSION_CODE_MAJOR: u32 = 0x0101_0576;

/// What the binary manifest's first element (`<manifest>`) says.
#[derive(Debug, Default, PartialEq, Eq)]
struct Manifest {
    package: Option<String>,
    version_code: Option<i64>,
}

/// The `package`, `android:versionCode` and `android:versionCodeMajor` attributes of the binary
/// manifest's first element (`<manifest>`). An android attribute is known by its resource id (the
/// resource map), else by its name.
fn read_manifest(xml: &[u8]) -> Option<Manifest> {
    if u16_at(xml, 0)? != RES_XML_TYPE {
        return None;
    }
    let mut at = usize::from(u16_at(xml, 2)?);
    let mut strings: Option<&[u8]> = None;
    let mut resource_ids: Option<&[u8]> = None;
    while at < xml.len() {
        let kind = u16_at(xml, at)?;
        let header_size = usize::from(u16_at(xml, at + 2)?);
        let size = u32_at(xml, at + 4)? as usize;
        let chunk = xml.get(at..at.checked_add(size)?)?;
        if size < 8 {
            return None;
        }
        match kind {
            RES_STRING_POOL_TYPE => strings = Some(chunk),
            RES_XML_RESOURCE_MAP_TYPE => resource_ids = chunk.get(header_size..),
            RES_XML_START_ELEMENT_TYPE => {
                let pool = strings?;
                // ResXMLTree_attrExt follows the node header.
                let ext = chunk.get(header_size..)?;
                if pool_string(pool, u32_at(ext, 4)?)? != "manifest" {
                    return None;
                }
                let attribute_start = usize::from(u16_at(ext, 8)?);
                let attribute_size = usize::from(u16_at(ext, 10)?);
                let count = usize::from(u16_at(ext, 12)?);
                let mut manifest = Manifest::default();
                let (mut code, mut major) = (None, None);
                for index in 0..count {
                    let attribute = ext
                        .get(attribute_start.checked_add(index.checked_mul(attribute_size)?)?..)?;
                    let namespace = u32_at(attribute, 0)?;
                    let name = u32_at(attribute, 4)?;
                    let named = |wanted: &str| pool_string(pool, name).as_deref() == Some(wanted);
                    // A number: the typed value's data.
                    let int = || match *attribute.get(15)? {
                        TYPE_INT_DEC | TYPE_INT_HEX => u32_at(attribute, 16),
                        _ => None,
                    };
                    if namespace == NO_INDEX {
                        if !named("package") {
                            continue;
                        }
                        let raw = u32_at(attribute, 8)?;
                        let value = if raw != NO_INDEX {
                            Some(raw)
                        } else if *attribute.get(15)? == TYPE_STRING {
                            u32_at(attribute, 16)
                        } else {
                            None
                        };
                        manifest.package = value
                            .and_then(|value| pool_string(pool, value))
                            .filter(|name| !name.is_empty());
                        continue;
                    }
                    let id =
                        resource_ids.and_then(|ids| u32_at(ids, (name as usize).checked_mul(4)?));
                    let is =
                        |attr: u32, wanted: &str| id.map_or_else(|| named(wanted), |id| id == attr);
                    if is(ATTR_VERSION_CODE, "versionCode") {
                        code = int();
                    } else if is(ATTR_VERSION_CODE_MAJOR, "versionCodeMajor") {
                        major = int();
                    }
                }
                manifest.version_code = code.and_then(|code| long_version_code(code, major));
                return Some(manifest);
            }
            _ => {}
        }
        at = at.checked_add(size)?;
    }
    None
}

/// `PackageInfo.getLongVersionCode`: the major code in the high 32 bits, the code's 32 bits below;
/// `None` past what an `i64` holds.
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
mod tests {
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
                signer_sha256: Some(KEY1.to_string()),
            }
        );
        assert_eq!(read("fixture-key1-v2.apk").version_code, Some(2));
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
                signer_sha256: None,
            }
        );
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
}
