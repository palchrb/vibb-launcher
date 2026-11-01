//! The JAR (v1) signature of an F-Droid repo's `entry.jar` (round S4; docs/notes/server/play-and-updates.md, "F-Droid
//! source", "The repo's signature"). `entry.jar` holds `entry.json` (the index's name, SHA-256 and size) and the repo
//! key's JAR signature over it; [verify_entry_jar] gives back entry.json and the signing certificate's SHA-256 (what
//! F-Droid calls the repo's fingerprint) only when the whole chain checks out:
//!
//! 1. `META-INF/MANIFEST.MF`'s `Name: entry.json` section has a `SHA-256-Digest` = SHA-256 of the entry.json read;
//! 2. the one signature file `META-INF/<NAME>.SF` has a `SHA-256-Digest-Manifest` = SHA-256 of the whole manifest;
//! 3. `META-INF/<NAME>.RSA` (or `.EC`) is a CMS SignedData (RFC 5652): one certificate, one signer (that certificate,
//!    by issuer and serial), digest SHA-256, the signature - RSA PKCS#1 v1.5 (2048-8192 bits) or ECDSA P-256 - over
//!    the .SF itself or, with signed attributes, over them (their `messageDigest` the .SF's SHA-256, `contentType`
//!    data), verified by `ring` with the certificate's key.
//!
//! Strict on purpose (fail closed): SHA-1 digests, DSA, a missing whole-manifest digest, a second signer or
//! certificate, content inside the SignedData and indefinite DER lengths are all refused - fdroidserver's `entry.jar`
//! (`Created-By: 1.0 (Android)`, f-droid.org's and IzzyOnDroid's) has none of them. The certificate is a container
//! for the key: its dates and its own signature aren't checked (as Android and F-Droid's client don't for a pinned
//! key); the caller compares its SHA-256 with the repo's pin. No new crate: `zip`, `sha2`, `base64` and `ring` are
//! the server's already, and the DER reader below reads only the fixed shape of a SignedData.

use std::io::Read;

use base64::Engine;
use ring::signature::{ECDSA_P256_SHA256_ASN1, RSA_PKCS1_2048_8192_SHA256, UnparsedPublicKey};
use sha2::{Digest, Sha256};

/// The largest entry.json read out of the jar (it is ~2 KB).
const MAX_ENTRY_JSON: u64 = 1 << 20;
/// The largest manifest, signature file or signature block read (each is under 2 KB).
const MAX_META: u64 = 64 << 10;
/// An entry.jar has four files; more is not one.
const MAX_FILES: usize = 16;

/// What a good signature gives: entry.json as signed, and the signer.
#[derive(Debug)]
pub struct Signed {
    pub entry_json: Vec<u8>,
    /// SHA-256 (lower-case hex) of the signing certificate's DER: the repo's fingerprint.
    pub signer_sha256: String,
}

/// Checks `jar` (an `entry.jar`) as the module says. `Err` is the reason, in English for the ⓘ panel and the log.
pub fn verify_entry_jar(jar: &[u8]) -> Result<Signed, String> {
    let mut zip = zip::ZipArchive::new(std::io::Cursor::new(jar))
        .map_err(|e| format!("entry.jar isn't a JAR: {e}"))?;
    if zip.len() > MAX_FILES {
        return Err(format!(
            "entry.jar has {} files - not an F-Droid entry.jar",
            zip.len()
        ));
    }
    let names: Vec<String> = zip.file_names().map(str::to_string).collect();
    let mut seen = std::collections::HashSet::new();
    if let Some(twice) = names
        .iter()
        .find(|name| !seen.insert(name.to_ascii_uppercase()))
    {
        return Err(format!("entry.jar has {twice} twice"));
    }
    let in_meta = |name: &&String, ext: &str| {
        name.strip_prefix("META-INF/").is_some_and(|rest| {
            !rest.contains('/')
                && rest.len() > ext.len() + 1
                && rest.to_ascii_uppercase().ends_with(&format!(".{ext}"))
        })
    };
    let sfs: Vec<&String> = names.iter().filter(|n| in_meta(n, "SF")).collect();
    let sf_name = match sfs.as_slice() {
        [one] => one.to_string(),
        [] => return Err("entry.jar isn't signed: it has no META-INF/*.SF".to_string()),
        _ => return Err("entry.jar has more than one signature (META-INF/*.SF)".to_string()),
    };
    let base = sf_name[..sf_name.len() - 3].to_ascii_uppercase();
    let block_name = names
        .iter()
        .find(|n| {
            let upper = n.to_ascii_uppercase();
            upper == format!("{base}.RSA") || upper == format!("{base}.EC")
        })
        .cloned()
        .ok_or_else(|| {
            format!("entry.jar has {sf_name} but no RSA or EC signature block beside it")
        })?;
    let entry_json = read_file(&mut zip, "entry.json", MAX_ENTRY_JSON)?;
    let manifest = read_file(&mut zip, "META-INF/MANIFEST.MF", MAX_META)?;
    let sf = read_file(&mut zip, &sf_name, MAX_META)?;
    let block = read_file(&mut zip, &block_name, MAX_META)?;

    let sections = sections(&manifest, "META-INF/MANIFEST.MF")?;
    let named: Vec<&Section> = sections
        .iter()
        .skip(1)
        .filter(|section| attribute(section, "Name") == Some("entry.json"))
        .collect();
    let [section] = named.as_slice() else {
        return Err("META-INF/MANIFEST.MF doesn't list entry.json once".to_string());
    };
    let listed = only_attribute(
        section,
        "SHA-256-Digest",
        "META-INF/MANIFEST.MF's entry.json",
    )?;
    if listed != b64_sha256(&entry_json) {
        return Err("entry.json isn't what the repo signed: its SHA-256 isn't the one META-INF/MANIFEST.MF lists"
            .to_string());
    }
    let sf_sections = sections_of_sf(&sf, &sf_name)?;
    let whole = only_attribute(&sf_sections[0], "SHA-256-Digest-Manifest", &sf_name)?;
    if whole != b64_sha256(&manifest) {
        return Err(format!(
            "META-INF/MANIFEST.MF isn't what {sf_name} signed: its SHA-256 differs"
        ));
    }
    let signer_sha256 = verify_block(&block, &sf).map_err(|why| format!("{block_name}: {why}"))?;
    Ok(Signed {
        entry_json,
        signer_sha256,
    })
}

fn read_file(
    zip: &mut zip::ZipArchive<std::io::Cursor<&[u8]>>,
    name: &str,
    max: u64,
) -> Result<Vec<u8>, String> {
    let file = zip
        .by_name(name)
        .map_err(|e| format!("entry.jar has no {name} ({e})"))?;
    let mut bytes = Vec::new();
    file.take(max + 1)
        .read_to_end(&mut bytes)
        .map_err(|e| format!("entry.jar's {name} can't be read: {e}"))?;
    if bytes.len() as u64 > max {
        return Err(format!("entry.jar's {name} is over {} KB", max / 1024));
    }
    Ok(bytes)
}

fn b64_sha256(bytes: &[u8]) -> String {
    base64::engine::general_purpose::STANDARD.encode(Sha256::digest(bytes))
}

/// One section of a manifest or signature file: its `(name, value)` lines, continuation lines joined.
type Section = Vec<(String, String)>;

/// A JAR manifest's sections (the JAR File Specification: `Name: value` lines, a line starting with one space
/// continues the one before, a blank line ends a section; `\r\n` or `\n`). The first is the main section.
fn sections(bytes: &[u8], what: &str) -> Result<Vec<Section>, String> {
    let text = std::str::from_utf8(bytes).map_err(|_| format!("{what} isn't UTF-8"))?;
    let mut sections = Vec::new();
    let mut current: Section = Vec::new();
    for line in text
        .split('\n')
        .map(|line| line.strip_suffix('\r').unwrap_or(line))
    {
        if line.is_empty() {
            if !current.is_empty() {
                sections.push(std::mem::take(&mut current));
            }
        } else if let Some(more) = line.strip_prefix(' ') {
            let last = current
                .last_mut()
                .ok_or_else(|| format!("{what} starts a section with a continuation line"))?;
            last.1.push_str(more);
        } else {
            let (name, value) = line
                .split_once(": ")
                .ok_or_else(|| format!("{what} has a line that isn't \"Name: value\""))?;
            current.push((name.to_string(), value.to_string()));
        }
    }
    if !current.is_empty() {
        sections.push(current);
    }
    if sections.is_empty() {
        return Err(format!("{what} is empty"));
    }
    Ok(sections)
}

fn sections_of_sf(bytes: &[u8], what: &str) -> Result<Vec<Section>, String> {
    let sections = sections(bytes, what)?;
    if attribute(&sections[0], "Signature-Version").is_none() {
        return Err(format!(
            "{what} isn't a signature file (no Signature-Version)"
        ));
    }
    Ok(sections)
}

/// A header's value (names are case-insensitive), the first if there are several.
fn attribute<'a>(section: &'a Section, name: &str) -> Option<&'a str> {
    section
        .iter()
        .find(|(key, _)| key.eq_ignore_ascii_case(name))
        .map(|(_, value)| value.as_str())
}

/// A header that must be there exactly once.
fn only_attribute<'a>(section: &'a Section, name: &str, what: &str) -> Result<&'a str, String> {
    let mut values = section
        .iter()
        .filter(|(key, _)| key.eq_ignore_ascii_case(name));
    match (values.next(), values.next()) {
        (Some((_, value)), None) => Ok(value),
        (None, _) => Err(format!("{what} has no {name} (only SHA-256 is accepted)")),
        (Some(_), Some(_)) => Err(format!("{what} has {name} twice")),
    }
}

// DER tags (one byte; a high tag number is refused).
const INTEGER: u8 = 0x02;
const BIT_STRING: u8 = 0x03;
const OCTET_STRING: u8 = 0x04;
const OID: u8 = 0x06;
const SEQUENCE: u8 = 0x30;
const SET: u8 = 0x31;
const CONTEXT_0: u8 = 0xa0;
const CONTEXT_1: u8 = 0xa1;

// OIDs, as their DER contents.
const OID_SIGNED_DATA: &[u8] = &[0x2a, 0x86, 0x48, 0x86, 0xf7, 0x0d, 0x01, 0x07, 0x02];
const OID_DATA: &[u8] = &[0x2a, 0x86, 0x48, 0x86, 0xf7, 0x0d, 0x01, 0x07, 0x01];
const OID_SHA256: &[u8] = &[0x60, 0x86, 0x48, 0x01, 0x65, 0x03, 0x04, 0x02, 0x01];
const OID_RSA: &[u8] = &[0x2a, 0x86, 0x48, 0x86, 0xf7, 0x0d, 0x01, 0x01, 0x01];
const OID_SHA256_RSA: &[u8] = &[0x2a, 0x86, 0x48, 0x86, 0xf7, 0x0d, 0x01, 0x01, 0x0b];
const OID_EC_PUBLIC_KEY: &[u8] = &[0x2a, 0x86, 0x48, 0xce, 0x3d, 0x02, 0x01];
const OID_P256: &[u8] = &[0x2a, 0x86, 0x48, 0xce, 0x3d, 0x03, 0x01, 0x07];
const OID_ECDSA_SHA256: &[u8] = &[0x2a, 0x86, 0x48, 0xce, 0x3d, 0x04, 0x03, 0x02];
const OID_CONTENT_TYPE: &[u8] = &[0x2a, 0x86, 0x48, 0x86, 0xf7, 0x0d, 0x01, 0x09, 0x03];
const OID_MESSAGE_DIGEST: &[u8] = &[0x2a, 0x86, 0x48, 0x86, 0xf7, 0x0d, 0x01, 0x09, 0x04];

/// A DER reader over a definite-length encoding: each read takes one TLV off the front.
struct Der<'a>(&'a [u8]);

/// One TLV: its tag, its contents, and the whole encoding (tag and length included).
struct Tlv<'a> {
    tag: u8,
    content: &'a [u8],
    raw: &'a [u8],
}

impl<'a> Der<'a> {
    fn next(&mut self) -> Result<Tlv<'a>, String> {
        let all = self.0;
        let truncated = || "the DER is cut short".to_string();
        let (&tag, rest) = all.split_first().ok_or_else(truncated)?;
        if tag & 0x1f == 0x1f {
            return Err("the DER has a high tag number".to_string());
        }
        let (&first, rest) = rest.split_first().ok_or_else(truncated)?;
        let (len, rest) = if first < 0x80 {
            (usize::from(first), rest)
        } else {
            let n = usize::from(first & 0x7f);
            if n == 0 || n > 4 || rest.len() < n {
                return Err("the DER has an indefinite or oversized length".to_string());
            }
            let len = rest[..n]
                .iter()
                .fold(0usize, |len, &b| (len << 8) | usize::from(b));
            (len, &rest[n..])
        };
        if rest.len() < len {
            return Err(truncated());
        }
        let header = all.len() - rest.len();
        self.0 = &rest[len..];
        Ok(Tlv {
            tag,
            content: &rest[..len],
            raw: &all[..header + len],
        })
    }

    fn take(&mut self, tag: u8) -> Result<Tlv<'a>, String> {
        let tlv = self.next()?;
        if tlv.tag != tag {
            return Err(format!(
                "the DER has tag {:#04x} where {tag:#04x} belongs",
                tlv.tag
            ));
        }
        Ok(tlv)
    }

    fn expect(&mut self, tag: u8) -> Result<&'a [u8], String> {
        self.take(tag).map(|tlv| tlv.content)
    }

    fn optional(&mut self, tag: u8) -> Result<Option<Tlv<'a>>, String> {
        if self.0.first() == Some(&tag) {
            self.next().map(Some)
        } else {
            Ok(None)
        }
    }

    fn end(&self) -> Result<(), String> {
        if self.0.is_empty() {
            Ok(())
        } else {
            Err("the DER has bytes after its end".to_string())
        }
    }
}

/// An AlgorithmIdentifier's OID and its parameters' encoding (empty when absent).
fn algorithm(content: &[u8]) -> Result<(&[u8], &[u8]), String> {
    let mut der = Der(content);
    let oid = der.expect(OID)?;
    Ok((oid, der.0))
}

enum KeyKind {
    Rsa,
    EcP256,
}

/// What the check needs of an X.509 certificate.
struct Certificate<'a> {
    raw: &'a [u8],
    issuer: &'a [u8],
    serial: &'a [u8],
    kind: KeyKind,
    /// RSA: the RSAPublicKey DER; P-256: the uncompressed point - what `ring` takes.
    key: &'a [u8],
}

fn certificate(raw: &[u8]) -> Result<Certificate<'_>, String> {
    let mut outer = Der(raw);
    let mut cert = Der(outer.expect(SEQUENCE)?);
    outer.end()?;
    let mut tbs = Der(cert.expect(SEQUENCE)?);
    tbs.optional(CONTEXT_0)?;
    let serial = tbs.take(INTEGER)?.raw;
    tbs.expect(SEQUENCE)?;
    let issuer = tbs.take(SEQUENCE)?.raw;
    tbs.expect(SEQUENCE)?;
    tbs.expect(SEQUENCE)?;
    let mut spki = Der(tbs.expect(SEQUENCE)?);
    let (key_oid, params) = algorithm(spki.expect(SEQUENCE)?)?;
    let bits = spki.expect(BIT_STRING)?;
    spki.end()?;
    let key = match bits.split_first() {
        Some((0, key)) => key,
        _ => return Err("the certificate's key isn't a whole-byte bit string".to_string()),
    };
    let kind = if key_oid == OID_RSA {
        KeyKind::Rsa
    } else if key_oid == OID_EC_PUBLIC_KEY && Der(params).expect(OID)? == OID_P256 {
        KeyKind::EcP256
    } else {
        return Err("the certificate's key is neither RSA nor EC P-256".to_string());
    };
    Ok(Certificate {
        raw,
        issuer,
        serial,
        kind,
        key,
    })
}

/// Checks the CMS SignedData `block` over `signed` (the .SF); `Ok` is the certificate's SHA-256 (hex).
fn verify_block(block: &[u8], signed: &[u8]) -> Result<String, String> {
    let mut outer = Der(block);
    let mut content_info = Der(outer.expect(SEQUENCE)?);
    outer.end()?;
    if content_info.expect(OID)? != OID_SIGNED_DATA {
        return Err("not a CMS SignedData".to_string());
    }
    let mut explicit = Der(content_info.expect(CONTEXT_0)?);
    content_info.end()?;
    let mut signed_data = Der(explicit.expect(SEQUENCE)?);
    explicit.end()?;
    signed_data.expect(INTEGER)?;
    signed_data.expect(SET)?;
    let mut encapsulated = Der(signed_data.expect(SEQUENCE)?);
    if encapsulated.expect(OID)? != OID_DATA {
        return Err("the SignedData isn't over data".to_string());
    }
    if encapsulated.end().is_err() {
        return Err("the SignedData carries its content - a JAR's is detached".to_string());
    }
    let certificates = signed_data
        .optional(CONTEXT_0)?
        .ok_or_else(|| "the SignedData has no certificate".to_string())?;
    signed_data.optional(CONTEXT_1)?;
    let mut signer_infos = Der(signed_data.expect(SET)?);
    signed_data.end()?;

    let mut certificates = Der(certificates.content);
    let cert = certificate(certificates.take(SEQUENCE)?.raw)?;
    if certificates.end().is_err() {
        return Err("the SignedData has more than one certificate".to_string());
    }
    let mut info = Der(signer_infos.expect(SEQUENCE)?);
    if signer_infos.end().is_err() {
        return Err("the SignedData has more than one signer".to_string());
    }
    if info.expect(INTEGER)? != [1] {
        return Err("the signer isn't named by issuer and serial number".to_string());
    }
    let mut sid = Der(info.expect(SEQUENCE)?);
    let (issuer, serial) = (sid.take(SEQUENCE)?.raw, sid.take(INTEGER)?.raw);
    sid.end()?;
    if issuer != cert.issuer || serial != cert.serial {
        return Err("the signer isn't the certificate in the block".to_string());
    }
    if algorithm(info.expect(SEQUENCE)?)?.0 != OID_SHA256 {
        return Err("the signer's digest isn't SHA-256".to_string());
    }
    let attributes = info.optional(CONTEXT_0)?;
    let (signature_oid, _) = algorithm(info.expect(SEQUENCE)?)?;
    let signature = info.expect(OCTET_STRING)?;
    info.optional(CONTEXT_1)?;
    info.end()?;

    let message = match attributes {
        None => signed.to_vec(),
        Some(attributes) => {
            check_attributes(attributes.content, &Sha256::digest(signed))?;
            // Signed over as a SET (RFC 5652 §5.4), not as the [0] they are stored as.
            let mut message = attributes.raw.to_vec();
            message[0] = SET;
            message
        }
    };
    let algorithm: &dyn ring::signature::VerificationAlgorithm = match cert.kind {
        KeyKind::Rsa if signature_oid == OID_RSA || signature_oid == OID_SHA256_RSA => {
            &RSA_PKCS1_2048_8192_SHA256
        }
        KeyKind::EcP256
            if signature_oid == OID_ECDSA_SHA256 || signature_oid == OID_EC_PUBLIC_KEY =>
        {
            &ECDSA_P256_SHA256_ASN1
        }
        _ => return Err(
            "the signature's algorithm doesn't fit the key (only SHA-256 with RSA or ECDSA P-256)"
                .to_string(),
        ),
    };
    UnparsedPublicKey::new(algorithm, cert.key)
        .verify(&message, signature)
        .map_err(|_| "the signature doesn't verify with the certificate's key".to_string())?;
    Ok(hex::encode(Sha256::digest(cert.raw)))
}

/// Signed attributes must say content type data and the .SF's SHA-256, each once.
fn check_attributes(content: &[u8], digest: &[u8]) -> Result<(), String> {
    let mut attributes = Der(content);
    let (mut content_type, mut message_digest) = (None, None);
    while !attributes.0.is_empty() {
        let mut attribute = Der(attributes.expect(SEQUENCE)?);
        let oid = attribute.expect(OID)?;
        let mut values = Der(attribute.expect(SET)?);
        attribute.end()?;
        let slot = if oid == OID_CONTENT_TYPE {
            (&mut content_type, values.expect(OID)?)
        } else if oid == OID_MESSAGE_DIGEST {
            (&mut message_digest, values.expect(OCTET_STRING)?)
        } else {
            continue;
        };
        values.end()?;
        if slot.0.replace(slot.1).is_some() {
            return Err("a signed attribute is there twice".to_string());
        }
    }
    if content_type != Some(OID_DATA) {
        return Err("the signed attributes don't say content type data".to_string());
    }
    if message_digest != Some(digest) {
        return Err("the signed attributes' digest isn't the signature file's".to_string());
    }
    Ok(())
}

#[cfg(test)]
pub(crate) mod tests {
    use super::*;
    use ring::rand::SystemRandom;
    use ring::signature::{ECDSA_P256_SHA256_ASN1_SIGNING, EcdsaKeyPair, KeyPair};
    use std::io::Write;

    /// f-droid.org's and IzzyOnDroid's real `entry.jar` as fetched on 2026-11-01 (public, signed by their repo keys;
    /// docs/notes/server/play-and-updates.md, "The repo's signature").
    pub(crate) const FDROID_JAR: &[u8] = include_bytes!("../testdata/fdroid/f-droid.org-entry.jar");
    pub(crate) const IZZY_JAR: &[u8] = include_bytes!("../testdata/fdroid/izzysoft-entry.jar");

    fn tlv(tag: u8, content: &[u8]) -> Vec<u8> {
        let mut out = vec![tag];
        match content.len() {
            len @ 0..0x80 => out.push(len as u8),
            len @ 0x80..0x100 => out.extend([0x81, len as u8]),
            len => out.extend([0x82, (len >> 8) as u8, len as u8]),
        }
        out.extend_from_slice(content);
        out
    }

    fn seq(parts: &[&[u8]]) -> Vec<u8> {
        tlv(SEQUENCE, &parts.concat())
    }

    /// A throwaway ECDSA P-256 key made at run time, with a self-signed certificate around it: never a real key.
    pub(crate) struct TestKey {
        pair: EcdsaKeyPair,
        pub cert: Vec<u8>,
        issuer: Vec<u8>,
        serial: Vec<u8>,
    }

    impl TestKey {
        pub fn new(name: &str) -> TestKey {
            let rng = SystemRandom::new();
            let pkcs8 =
                EcdsaKeyPair::generate_pkcs8(&ECDSA_P256_SHA256_ASN1_SIGNING, &rng).unwrap();
            let pair =
                EcdsaKeyPair::from_pkcs8(&ECDSA_P256_SHA256_ASN1_SIGNING, pkcs8.as_ref(), &rng)
                    .unwrap();
            let algorithm = seq(&[&tlv(OID, OID_ECDSA_SHA256)]);
            let cn = [0x55, 0x04, 0x03];
            let name = seq(&[&tlv(
                SET,
                &seq(&[&tlv(OID, &cn), &tlv(0x0c, name.as_bytes())]),
            )]);
            let serial = tlv(INTEGER, &[0x01, 0x23]);
            let validity = seq(&[&tlv(0x17, b"260101000000Z"), &tlv(0x17, b"360101000000Z")]);
            let mut point = vec![0];
            point.extend_from_slice(pair.public_key().as_ref());
            let spki = seq(&[
                &seq(&[&tlv(OID, OID_EC_PUBLIC_KEY), &tlv(OID, OID_P256)]),
                &tlv(BIT_STRING, &point),
            ]);
            let tbs = seq(&[
                &tlv(CONTEXT_0, &tlv(INTEGER, &[2])),
                &serial,
                &algorithm,
                &name,
                &validity,
                &name,
                &spki,
            ]);
            let mut signature = vec![0];
            signature.extend_from_slice(pair.sign(&rng, &tbs).unwrap().as_ref());
            let cert = seq(&[&tbs, &algorithm, &tlv(BIT_STRING, &signature)]);
            TestKey {
                pair,
                cert,
                issuer: name,
                serial,
            }
        }

        pub fn sha256(&self) -> String {
            hex::encode(Sha256::digest(&self.cert))
        }

        /// The CMS SignedData over `signed` by this key, with signed attributes or without, naming `shown`'s
        /// certificate as the signer's (this key's own in a good block).
        fn block(&self, signed: &[u8], with_attributes: bool, shown: &TestKey) -> Vec<u8> {
            let rng = SystemRandom::new();
            let digest_algorithm = seq(&[&tlv(OID, OID_SHA256), &[0x05, 0x00]]);
            let mut info: Vec<Vec<u8>> = vec![
                tlv(INTEGER, &[1]),
                seq(&[&shown.issuer, &shown.serial]),
                digest_algorithm.clone(),
            ];
            let message = if with_attributes {
                let attributes = [
                    seq(&[&tlv(OID, OID_CONTENT_TYPE), &tlv(SET, &tlv(OID, OID_DATA))]),
                    seq(&[
                        &tlv(OID, OID_MESSAGE_DIGEST),
                        &tlv(SET, &tlv(OCTET_STRING, &Sha256::digest(signed))),
                    ]),
                ]
                .concat();
                info.push(tlv(CONTEXT_0, &attributes));
                tlv(SET, &attributes)
            } else {
                signed.to_vec()
            };
            info.push(seq(&[&tlv(OID, OID_ECDSA_SHA256)]));
            info.push(tlv(
                OCTET_STRING,
                self.pair.sign(&rng, &message).unwrap().as_ref(),
            ));
            let info: Vec<&[u8]> = info.iter().map(Vec::as_slice).collect();
            let signed_data = seq(&[
                &tlv(INTEGER, &[1]),
                &tlv(SET, &digest_algorithm),
                &seq(&[&tlv(OID, OID_DATA)]),
                &tlv(CONTEXT_0, &shown.cert),
                &tlv(SET, &seq(&info)),
            ]);
            seq(&[&tlv(OID, OID_SIGNED_DATA), &tlv(CONTEXT_0, &signed_data)])
        }
    }

    /// The files of a jar, in order.
    pub(crate) fn jar_of(files: &[(&str, &[u8])]) -> Vec<u8> {
        let mut writer = zip::ZipWriter::new(std::io::Cursor::new(Vec::new()));
        let options = zip::write::SimpleFileOptions::default()
            .compression_method(zip::CompressionMethod::Deflated);
        for (name, bytes) in files {
            writer.start_file(*name, options).unwrap();
            writer.write_all(bytes).unwrap();
        }
        writer.finish().unwrap().into_inner()
    }

    /// The manifest and signature file of `entry_json`, as fdroidserver writes them.
    fn manifest_and_sf(entry_json: &[u8]) -> (Vec<u8>, Vec<u8>) {
        let manifest = format!(
            "Manifest-Version: 1.0\r\n\r\nName: entry.json\r\nSHA-256-Digest: {}\r\n\r\n",
            b64_sha256(entry_json)
        );
        let sf = format!(
            "Signature-Version: 1.0\r\nCreated-By: 1.0 (Android)\r\nSHA-256-Digest-Manifest: {}\r\n\r\n",
            b64_sha256(manifest.as_bytes())
        );
        (manifest.into_bytes(), sf.into_bytes())
    }

    /// An entry.jar of `entry_json` signed by `key`, as fdroidserver builds one.
    pub(crate) fn signed_jar(entry_json: &[u8], key: &TestKey, with_attributes: bool) -> Vec<u8> {
        let (manifest, sf) = manifest_and_sf(entry_json);
        jar_of(&[
            ("entry.json", entry_json),
            ("META-INF/TEST.SF", &sf),
            ("META-INF/TEST.EC", &key.block(&sf, with_attributes, key)),
            ("META-INF/MANIFEST.MF", &manifest),
        ])
    }

    /// The jar's files, to change one.
    fn files_of(jar: &[u8]) -> Vec<(String, Vec<u8>)> {
        let mut zip = zip::ZipArchive::new(std::io::Cursor::new(jar)).unwrap();
        (0..zip.len())
            .map(|i| {
                let mut file = zip.by_index(i).unwrap();
                let mut bytes = Vec::new();
                file.read_to_end(&mut bytes).unwrap();
                (file.name().to_string(), bytes)
            })
            .collect()
    }

    fn rebuilt(files: &[(String, Vec<u8>)]) -> Vec<u8> {
        let files: Vec<(&str, &[u8])> = files
            .iter()
            .map(|(n, b)| (n.as_str(), b.as_slice()))
            .collect();
        jar_of(&files)
    }

    /// The real jars pass (RSA without signed attributes: fdroidserver's), each with its repo's pinned key; a test
    /// key's passes with signed attributes and without.
    #[test]
    fn a_good_entry_jar_passes() {
        let fdroid = verify_entry_jar(FDROID_JAR).unwrap();
        assert_eq!(
            fdroid.signer_sha256,
            "43238d512c1e5eb2d6569f4a3afbf5523418b82e0a3ed1552770abb9a9c9ccab"
        );
        assert!(fdroid.entry_json.starts_with(b"{"));
        let izzy = verify_entry_jar(IZZY_JAR).unwrap();
        assert_eq!(
            izzy.signer_sha256,
            "3bf0d6abfeae2f401707b6d966be743bf0eee49c2561b9ba39073711f628937a"
        );
        let key = TestKey::new("Vibb test repo (throwaway)");
        for with_attributes in [false, true] {
            let signed =
                verify_entry_jar(&signed_jar(b"{\"a\":1}", &key, with_attributes)).unwrap();
            assert_eq!(signed.entry_json, b"{\"a\":1}");
            assert_eq!(signed.signer_sha256, key.sha256());
        }
    }

    /// One byte of entry.json changed - in the real jar, and in a test key's with signed attributes and without:
    /// refused at each layer a forger could stop at. As it is: the manifest's digest. With a manifest made for it: the
    /// .SF's digest of the manifest. With both made for it: the signature over the .SF, or the signed attributes'
    /// digest of it.
    #[test]
    fn a_changed_entry_json_byte_fails() {
        let key = TestKey::new("Vibb test repo (throwaway)");
        let entry_json = br#"{"index":{"name":"/index-v2.json"}}"#;
        for (jar, sf_name) in [
            (FDROID_JAR.to_vec(), "META-INF/CIARANG.SF"),
            (signed_jar(entry_json, &key, false), "META-INF/TEST.SF"),
            (signed_jar(entry_json, &key, true), "META-INF/TEST.SF"),
        ] {
            let mut files = files_of(&jar);
            let entry = files.iter_mut().find(|(n, _)| n == "entry.json").unwrap();
            entry.1[3] ^= 0x01;
            let changed = entry.1.clone();
            let err = verify_entry_jar(&rebuilt(&files)).unwrap_err();
            assert!(err.contains("isn't what the repo signed"), "{err}");

            let (manifest, sf) = manifest_and_sf(&changed);
            let replace = |files: &mut Vec<(String, Vec<u8>)>, which: &str, with: &[u8]| {
                files.iter_mut().find(|(n, _)| n == which).unwrap().1 = with.to_vec();
            };
            replace(&mut files, "META-INF/MANIFEST.MF", &manifest);
            let err = verify_entry_jar(&rebuilt(&files)).unwrap_err();
            assert!(
                err.contains(&format!("isn't what {sf_name} signed")),
                "{err}"
            );

            replace(&mut files, sf_name, &sf);
            let err = verify_entry_jar(&rebuilt(&files)).unwrap_err();
            assert!(
                err.contains("doesn't verify") || err.contains("digest isn't the signature file's"),
                "{err}"
            );
        }
    }

    /// A block signed by another key than the certificate it names: refused, with signed attributes and without. A
    /// jar signed whole by another key verifies, but as that key's - the repo's pin refuses it
    /// (`fdroid::tests::a_repo_signed_by_another_key_is_refused`).
    #[test]
    fn a_wrong_key_fails() {
        let key = TestKey::new("Vibb test repo (throwaway)");
        let other = TestKey::new("Vibb other test repo (throwaway)");
        let mut files = files_of(&signed_jar(b"{}", &key, false));
        let sf = files
            .iter()
            .find(|(n, _)| n == "META-INF/TEST.SF")
            .unwrap()
            .1
            .clone();
        for with_attributes in [false, true] {
            for (name, bytes) in files.iter_mut() {
                if name == "META-INF/TEST.EC" {
                    *bytes = other.block(&sf, with_attributes, &key);
                }
            }
            let err = verify_entry_jar(&rebuilt(&files)).unwrap_err();
            assert!(err.contains("doesn't verify"), "{err}");
        }
        let theirs = verify_entry_jar(&signed_jar(b"{}", &other, false)).unwrap();
        assert_eq!(theirs.signer_sha256, other.sha256());
        assert_ne!(theirs.signer_sha256, key.sha256());
    }

    /// A jar without its signature files, without its block, with a SHA-1 manifest only, or not a jar: refused.
    #[test]
    fn a_stripped_or_unsigned_jar_fails() {
        let files = files_of(FDROID_JAR);
        let without = |drop: &str| -> Vec<(String, Vec<u8>)> {
            files
                .iter()
                .filter(|(n, _)| !n.ends_with(drop))
                .cloned()
                .collect()
        };
        let err = verify_entry_jar(&rebuilt(&without(".SF"))).unwrap_err();
        assert!(err.contains("isn't signed"), "{err}");
        let err = verify_entry_jar(&rebuilt(&without(".RSA"))).unwrap_err();
        assert!(err.contains("no RSA or EC signature block"), "{err}");
        let err = verify_entry_jar(&jar_of(&[("entry.json", b"{}")])).unwrap_err();
        assert!(err.contains("isn't signed"), "{err}");
        let sha1_only = files
            .iter()
            .map(|(n, b)| {
                let text = String::from_utf8_lossy(b);
                if n == "META-INF/MANIFEST.MF" {
                    (
                        n.clone(),
                        text.replace("SHA-256-Digest", "SHA1-Digest").into_bytes(),
                    )
                } else {
                    (n.clone(), b.clone())
                }
            })
            .collect::<Vec<_>>();
        let err = verify_entry_jar(&rebuilt(&sha1_only)).unwrap_err();
        assert!(err.contains("no SHA-256-Digest"), "{err}");
        assert!(
            verify_entry_jar(b"{\"index\":{}}")
                .unwrap_err()
                .contains("isn't a JAR")
        );
    }
}
