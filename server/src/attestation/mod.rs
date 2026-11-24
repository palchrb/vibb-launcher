//! Android key attestation (design 129 §3): a phone's Keystore key, proved to live in its secure hardware by a
//! certificate chain up to one of Google's attestation roots. Ports the rules of Google's Kotlin verifier
//! (`github.com/android/keyattestation`, Apache-2.0); its test chains are the vectors in
//! `testdata/attestation/` (`tests::the_rows`). Everything here runs without the database - before any transaction
//! (design 129 M4).
//!
//! The order (§3): the chain's shape, every certificate parsed, the root's key against the pins (`root`), each
//! certificate's signature by the next (`chain`), Google's revocation status (`revoked`), the validity (`expired`),
//! the KeyDescription's place and form (`chain`), its version (`properties`), the security levels (`software`,
//! `properties`), the challenge (`challenge`), the hardware-enforced properties (`properties`) and the app
//! (`app`). The proof of possession (§3 item 11) is [verify_proof], the caller's. The boot state (item 13) is read
//! ([boot::Reading]) and kept, not enforced, in S1.

pub mod boot;
pub mod der;
pub mod key_description;
pub mod roots;
pub mod status_list;
pub mod x509;

#[cfg(test)]
mod tests;

pub use key_description::{BootState, RootOfTrust, SecurityLevel};

/// At most this many certificates (§3 item 1), each at most [MAX_CERT_BYTES].
pub const MAX_CERTS: usize = 6;
pub const MIN_CERTS: usize = 2;
pub const MAX_CERT_BYTES: usize = 4 * 1024;

/// The lowest `attestationVersion` taken (§3 item 6): Keymaster 4.0 and every KeyMint.
pub const MIN_ATTESTATION_VERSION: i64 = 3;

/// The package the phone's key must belong to (§3 item 10). A debug launcher is never accepted (§7.3: only an
/// `emulator_keys` server would, and S1 builds none).
pub const LAUNCHER_PACKAGE: &str = "me.vibb.launcher";

/// Why a chain was refused: one word each (§3's list), never a weaker acceptance.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Refusal {
    Chain,
    Root,
    Revoked,
    Expired,
    Software,
    Properties,
    App,
    Boot,
    Lost,
    Challenge,
    Proof,
    ListStale,
}

impl Refusal {
    pub const ALL: &[Refusal] = &[
        Refusal::Chain,
        Refusal::Root,
        Refusal::Revoked,
        Refusal::Expired,
        Refusal::Software,
        Refusal::Properties,
        Refusal::App,
        Refusal::Boot,
        Refusal::Lost,
        Refusal::Challenge,
        Refusal::Proof,
        Refusal::ListStale,
    ];

    pub fn word(self) -> &'static str {
        match self {
            Refusal::Chain => "chain",
            Refusal::Root => "root",
            Refusal::Revoked => "revoked",
            Refusal::Expired => "expired",
            Refusal::Software => "software",
            Refusal::Properties => "properties",
            Refusal::App => "app",
            Refusal::Boot => "boot",
            Refusal::Lost => "lost",
            Refusal::Challenge => "challenge",
            Refusal::Proof => "proof",
            Refusal::ListStale => "list_stale",
        }
    }

    pub fn from_word(word: &str) -> Option<Refusal> {
        Refusal::ALL.iter().copied().find(|r| r.word() == word)
    }
}

/// What the chain must attest to: the challenge the server fixed, the app, and the time the RKP certificates are
/// read against.
pub struct Expect<'a> {
    pub challenge: &'a [u8],
    pub package: &'a str,
    /// `LAUNCHER_SIGNATURE_CHECKSUM` decoded: the release certificate's SHA-256, 32 bytes.
    pub signature_digest: &'a [u8],
    /// Unix seconds.
    pub now: i64,
}

/// A chain that passed: what the server keeps of it (§3 item 12).
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Attested {
    /// The leaf's SubjectPublicKeyInfo (DER).
    pub spki: Vec<u8>,
    /// The leaf's EC point (uncompressed, 65 bytes): what verifies the phone's signatures.
    pub public_key: Vec<u8>,
    pub level: SecurityLevel,
    pub attestation_version: i64,
    pub os_patch_level: Option<i64>,
    /// The hardware-enforced RootOfTrust; `None` when the list has none (read as unknown).
    pub root_of_trust: Option<RootOfTrust>,
    /// Every certificate's serial, leaf first, as the status list writes them.
    pub serials: Vec<String>,
    pub root: roots::Root,
}

/// The revocation status of a serial (lowercase hex, no leading zeros).
pub trait Revocations {
    fn revoked(&self, serial: &str) -> bool;
}

impl Revocations for std::collections::HashSet<String> {
    fn revoked(&self, serial: &str) -> bool {
        self.contains(serial)
    }
}

/// Design 129 §3 items 1-10: the chain (leaf first, DER) checked against `expect` and the revoked serials.
pub fn verify(
    chain: &[Vec<u8>],
    expect: &Expect,
    revocations: &dyn Revocations,
) -> Result<Attested, Refusal> {
    use x509::Certificate;

    // 1. Shape.
    if !(MIN_CERTS..=MAX_CERTS).contains(&chain.len())
        || chain
            .iter()
            .any(|c| c.is_empty() || c.len() > MAX_CERT_BYTES)
    {
        return Err(Refusal::Chain);
    }
    let certs: Vec<Certificate> = chain
        .iter()
        .map(|der| Certificate::parse(der))
        .collect::<Option<_>>()
        .ok_or(Refusal::Chain)?;
    let last = certs.len() - 1;

    // 2. The root's key is pinned; each certificate is signed by the next, and names it as its issuer.
    let root = roots::pinned(certs[last].spki).ok_or(Refusal::Root)?;
    for i in 0..last {
        if certs[i].issuer != certs[i + 1].subject || !x509::signed_by(&certs[i], &certs[i + 1]) {
            return Err(Refusal::Chain);
        }
    }

    // 3. Revocation: any certificate's serial.
    let serials: Vec<String> = certs.iter().map(Certificate::serial_hex).collect();
    if serials.iter().any(|s| revocations.revoked(s)) {
        return Err(Refusal::Revoked);
    }

    // 4. Validity, by the Kotlin library's rule: the leaf's is set on the phone and never read; the root's key is
    // pinned, not its certificate; a factory chain (its first intermediate names a serialNumber) under the RSA key
    // is never refused for an expired certificate (its keys can't be rotated); anything else - the remotely
    // provisioned chains - is read against the server's time.
    let factory = root == roots::Root::GoogleRsa
        && last >= 2
        && certs[last - 1].subject_has(x509::OID_SERIAL_NUMBER_ATTR);
    for cert in &certs[1..last] {
        if expect.now < cert.not_before || (!factory && expect.now > cert.not_after) {
            return Err(Refusal::Expired);
        }
    }

    // 5. The KeyDescription is the leaf's alone (no certificate above it may carry one); the provisioning info, when
    // there, is in the certificate right above the leaf.
    if certs[1..]
        .iter()
        .any(|c| c.extension(x509::OID_KEY_DESCRIPTION).is_some())
    {
        return Err(Refusal::Chain);
    }
    for (i, cert) in certs.iter().enumerate() {
        if let Some(info) = cert.extension(x509::OID_PROVISIONING_INFO) {
            if i != 1 || !provisioning_info_ok(info.value) {
                return Err(Refusal::Chain);
            }
        }
    }
    let extension = certs[0]
        .extension(x509::OID_KEY_DESCRIPTION)
        .ok_or(Refusal::Chain)?;
    let description =
        key_description::KeyDescription::parse(extension.value).ok_or(Refusal::Chain)?;

    // 6. The version.
    if description.attestation_version < MIN_ATTESTATION_VERSION {
        return Err(Refusal::Properties);
    }

    // 7. The security level: the same in both fields, and hardware.
    let level = description.attestation_security_level;
    if level == SecurityLevel::Software
        || description.keymint_security_level == SecurityLevel::Software
    {
        return Err(Refusal::Software);
    }
    if description.keymint_security_level != level {
        return Err(Refusal::Properties);
    }

    // 8. The challenge.
    if description.challenge != expect.challenge {
        return Err(Refusal::Challenge);
    }

    // 9. The hardware-enforced list alone: an EC P-256 signing key made in the hardware, SHA-256 among its digests,
    // usable without the user. VERIFY (3) beside SIGN is harmless for a public operation (Keystore adds it to some
    // keys); any other purpose is refused.
    let hw = &description.hardware;
    let purposes_ok = hw
        .purposes
        .as_ref()
        .is_some_and(|p| p.contains(&PURPOSE_SIGN) && p.iter().all(|x| [2, 3].contains(x)));
    let leaf = &certs[0];
    if !purposes_ok
        || hw.algorithm != Some(ALGORITHM_EC)
        || hw.ec_curve != Some(EC_CURVE_P256)
        || !hw
            .digests
            .as_ref()
            .is_some_and(|d| d.contains(&DIGEST_SHA256))
        || hw.origin != Some(ORIGIN_GENERATED)
        || !hw.no_auth_required
        || leaf.key_kind != x509::KeyKind::P256
        || leaf.key.len() != 65
    {
        return Err(Refusal::Properties);
    }

    // 10. The app: the framework's statement in the software list (never a hardware claim, §6.1) - exactly one
    // package, ours, and exactly one signing certificate, ours.
    let app = description
        .software
        .application_id
        .as_ref()
        .ok_or(Refusal::App)?;
    if expect.signature_digest.len() != 32
        || app.packages != [expect.package]
        || app.signatures.len() != 1
        || app.signatures[0] != expect.signature_digest
    {
        return Err(Refusal::App);
    }

    Ok(Attested {
        spki: leaf.spki.to_vec(),
        public_key: leaf.key.to_vec(),
        level,
        attestation_version: description.attestation_version,
        os_patch_level: hw.os_patch_level,
        root_of_trust: hw.root_of_trust.clone(),
        serials,
        root,
    })
}

const PURPOSE_SIGN: i64 = 2;
const ALGORITHM_EC: i64 = 3;
const EC_CURVE_P256: i64 = 1;
const DIGEST_SHA256: i64 = 4;
const ORIGIN_GENERATED: i64 = 0;

/// The provisioning info's form: an OCTET STRING holding a CBOR map (major type 5). Its only documented field
/// (`certs_issued`) is not read: the schema as read on 2026-11-24 has no lost-device flag, so `lost` stays a reserved
/// word until it has one.
fn provisioning_info_ok(value: &[u8]) -> bool {
    der::parse_one(value, der::Tag::OCTET_STRING)
        .and_then(|o| o.content.first().copied())
        .is_some_and(|first| first >> 5 == 5)
}

/// The SHA-256 of a key's SPKI, lowercase hex: how the server names a key to the phone (`spki_sha256`, §5.2).
pub fn spki_sha256(spki: &[u8]) -> String {
    use sha2::Digest;
    hex::encode(sha2::Sha256::digest(spki))
}

/// §3 item 11: the new key's signature (DER ECDSA P-256 SHA-256) over `vibb-key-v1\n<challenge Base64url>\n<kind>`.
pub fn verify_proof(public_key: &[u8], challenge: &[u8], kind: &str, proof: &[u8]) -> bool {
    ring::signature::UnparsedPublicKey::new(&ring::signature::ECDSA_P256_SHA256_ASN1, public_key)
        .verify(proof_message(challenge, kind).as_bytes(), proof)
        .is_ok()
}

/// The bytes a proof signs.
pub fn proof_message(challenge: &[u8], kind: &str) -> String {
    use base64::Engine;
    format!(
        "vibb-key-v1\n{}\n{kind}",
        base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(challenge)
    )
}

/// §5.1: an enrolment's attestation challenge, `SHA-256("vibb-enroll-v1" || 0x00 || normalised code)`.
pub fn enroll_challenge(normalised_code: &str) -> Vec<u8> {
    use sha2::Digest;
    let mut hasher = sha2::Sha256::new();
    hasher.update(b"vibb-enroll-v1");
    hasher.update([0u8]);
    hasher.update(normalised_code.as_bytes());
    hasher.finalize().to_vec()
}

/// `LAUNCHER_SIGNATURE_CHECKSUM` (the QR's Base64url form) decoded to the 32 bytes the app check compares; `None`
/// for an unset or malformed one - then every registration is refused (`app`, §3 item 10).
pub fn signature_digest(checksum: Option<&str>) -> Option<Vec<u8>> {
    use base64::Engine;
    let bytes = base64::engine::general_purpose::URL_SAFE_NO_PAD
        .decode(checksum?)
        .ok()?;
    (bytes.len() == 32).then_some(bytes)
}
