//! The `emulator_keys` profile (design 129b §7, building design 129 §7.3): the chain an Android emulator's Keystore
//! makes, taken so the debug launcher can enrol on the emulator gate. **Not a security check** - the root is known
//! by its name and its own signature, so anyone can make a chain this takes. Compiled only with the Cargo feature
//! `emulator_keys`, which `attestation/mod.rs` refuses to build outside the debug profile; while compiled in, the
//! server says so at every start and in orange on Settings, and every key taken here shows About's orange line.
//!
//! It runs only for a root that is **not** one of the pinned keys (a chain under a pinned root is the release
//! profile's alone) and accepts exactly what the gate images attest (read 2026-11-24, `docs/testing/emulator.md`
//! §6bj): three P-256 certificates, each signed by the next with ECDSA SHA-256; a self-signed root named exactly
//! `CN=Droid Unregistered Device CA, O=Google Test LLC`; the provisioning info on the intermediate and the root;
//! attestation and KeyMint version 300 (Android 14) or 400 (Android 16), the two the same; both security levels
//! `Software` with every tag in the software list and the hardware list empty; the debug launcher with the digest in
//! `LAUNCHER_DEBUG_SIGNATURE_CHECKSUM`; the revocation list and the validity as §3. The boot state is read from the
//! software list and never enforced (an emulator is never locked: `unknown`).
//!
//! Refusals: anything that isn't the emulator's root and shape (a fourth certificate, another curve, another name, not
//! self-signed, a TEE or StrongBox claim, the provisioning info missing) is `root` - the issuer is unknown, as without
//! the feature; a tag in the hardware list or another version is `properties`; the release package, another digest
//! or none set is `app`.

use super::key_description::SecurityLevel;
use super::x509::{self, Certificate, KeyKind};
use super::{Attested, Expect, Refusal, Revocations, roots};

/// The debug launcher's package: the only app this profile takes.
pub const DEBUG_PACKAGE: &str = "me.vibb.launcher.debug";

/// The root's name: `(OID, value)` pairs, compared as a set (the encoding's string type is not read).
const ROOT_NAME: &[(&[u8], &[u8])] = &[
    (OID_COMMON_NAME, b"Droid Unregistered Device CA"),
    (OID_ORGANIZATION, b"Google Test LLC"),
];
const OID_COMMON_NAME: &[u8] = &[0x55, 0x04, 0x03];
const OID_ORGANIZATION: &[u8] = &[0x55, 0x04, 0x0a];

/// The attestation (and KeyMint) versions the gate images make: 300 on Android 14, 400 on 16.
const VERSIONS: [i64; 2] = [300, 400];

/// The profile, on certificates `attestation::verify` parsed and whose root is not pinned.
pub fn verify(
    certs: &[Certificate],
    expect: &Expect,
    revocations: &dyn Revocations,
) -> Result<Attested, Refusal> {
    // The emulator's root and shape, else an unknown issuer.
    let [leaf, intermediate, root] = certs else {
        return Err(Refusal::Root);
    };
    if certs.iter().any(|c| c.key_kind != KeyKind::P256)
        || certs
            .iter()
            .any(|c| c.signature_alg != x509::OID_ECDSA_SHA256)
        || !root_named(root)
        || !x509::signed_by(root, root)
        || intermediate
            .extension(x509::OID_PROVISIONING_INFO)
            .is_none()
        || root.extension(x509::OID_PROVISIONING_INFO).is_none()
    {
        return Err(Refusal::Root);
    }
    super::signatures(certs)?;

    // Revocation and validity as §3; the root's certificate is read too (its key is not pinned).
    let serials = super::not_revoked(certs, revocations)?;
    for cert in [intermediate, root] {
        if expect.now < cert.not_before || expect.now > cert.not_after {
            return Err(Refusal::Expired);
        }
    }

    // The KeyDescription the leaf's alone; the provisioning info on the intermediate and the root, in its form.
    let description = super::key_description(certs, |i| i >= 1)?;
    if description.attestation_security_level != SecurityLevel::Software
        || description.keymint_security_level != SecurityLevel::Software
    {
        return Err(Refusal::Root);
    }
    if !VERSIONS.contains(&description.attestation_version)
        || description.keymint_version != description.attestation_version
        || description.hardware.entries != 0
    {
        return Err(Refusal::Properties);
    }
    if description.challenge != expect.challenge {
        return Err(Refusal::Challenge);
    }
    let sw = &description.software;
    super::properties(sw, leaf)?;
    super::app_ok(
        sw.application_id.as_ref(),
        DEBUG_PACKAGE,
        expect.debug_signature_digest,
    )?;

    Ok(Attested {
        spki: leaf.spki.to_vec(),
        public_key: leaf.key.to_vec(),
        level: SecurityLevel::Software,
        attestation_version: description.attestation_version,
        os_patch_level: sw.os_patch_level,
        root_of_trust: sw.root_of_trust.clone(),
        serials,
        root: roots::Root::Emulator,
    })
}

/// Exactly the two attributes of [ROOT_NAME], in any order.
fn root_named(root: &Certificate) -> bool {
    let values = root.subject_values();
    values.len() == ROOT_NAME.len()
        && ROOT_NAME
            .iter()
            .all(|(oid, value)| values.iter().any(|(o, v)| o == oid && v == value))
}
