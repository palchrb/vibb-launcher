//! Design 129 §7.2: every chain of `testdata/attestation/` against its row, the pins against the root certificates,
//! and the chain checks a row can't reach (a changed signature, a missing or reordered certificate, the shape).

use std::collections::HashSet;

use base64::Engine;

use super::*;

const DIR: &str = "testdata/attestation";

fn chain(name: &str) -> Vec<Vec<u8>> {
    let pem = std::fs::read_to_string(format!("{DIR}/chains/{name}")).unwrap();
    x509::pem_certificates(&pem)
}

fn b64(text: &str) -> Vec<u8> {
    base64::engine::general_purpose::STANDARD
        .decode(text)
        .unwrap()
}

fn at(rfc3339: &str) -> i64 {
    chrono::DateTime::parse_from_rfc3339(rfc3339)
        .unwrap()
        .timestamp()
}

fn listed() -> HashSet<String> {
    status_list::parse(&std::fs::read(format!("{DIR}/status.json")).unwrap()).unwrap()
}

#[test]
fn the_pins_are_the_roots() {
    let mut seen = HashSet::new();
    for entry in std::fs::read_dir(format!("{DIR}/roots")).unwrap() {
        let pem = std::fs::read_to_string(entry.unwrap().path()).unwrap();
        for der in x509::pem_certificates(&pem) {
            let cert = x509::Certificate::parse(&der).expect("a root certificate");
            let root = roots::pinned(cert.spki).expect("every root certificate is pinned");
            seen.insert(root);
        }
    }
    // Every pin has its certificate, and the four RSA certificates are one key.
    assert_eq!(seen.len(), roots::PINS.len());
}

#[test]
fn the_rows() {
    let rows: serde_json::Value =
        serde_json::from_str(&std::fs::read_to_string(format!("{DIR}/rows.json")).unwrap())
            .unwrap();
    let app = &rows["app"];
    let status = listed();
    let none: HashSet<String> = HashSet::new();
    let mut ok = 0;
    for row in rows["rows"].as_array().unwrap() {
        let name = row["name"].as_str().unwrap();
        let challenge = b64(row["challenge"].as_str().unwrap());
        let signature = b64(row["signature"]
            .as_str()
            .unwrap_or(app["signature"].as_str().unwrap()));
        let package = row["package"]
            .as_str()
            .unwrap_or(app["package"].as_str().unwrap());
        let expect = Expect {
            challenge: &challenge,
            package,
            signature_digest: &signature,
            debug_signature_digest: &[],
            now: at(row["now"].as_str().unwrap()),
        };
        let revocations: &dyn Revocations = if row["status"] == true {
            &status
        } else {
            &none
        };
        let result = verify(&chain(row["chain"].as_str().unwrap()), &expect, revocations);
        match row["verdict"].as_str().unwrap() {
            "ok" => {
                let attested = result.unwrap_or_else(|r| panic!("{name}: refused {}", r.word()));
                assert_eq!(attested.level.word(), row["level"], "{name}");
                assert_eq!(attested.attestation_version, row["version"], "{name}");
                assert_eq!(attested.os_patch_level, row["patch"].as_i64(), "{name}");
                let root = attested.root_of_trust.as_ref().unwrap();
                assert_eq!(root.state.word(), row["boot"], "{name}");
                assert_eq!(root.device_locked, row["locked"], "{name}");
                assert_eq!(
                    boot::Reading::of(Some(root)).word(),
                    row["reading"],
                    "{name}"
                );
                assert_eq!(attested.root.word(), row["root"], "{name}");
                assert_eq!(attested.serials.len() as u64, row["serials"], "{name}");
                assert_eq!(attested.public_key.len(), 65, "{name}");
                assert_eq!(attested.public_key[0], 4, "{name}");
                ok += 1;
            }
            word => {
                let refusal = Refusal::from_word(word).unwrap();
                assert_eq!(result.err(), Some(refusal), "{name}");
            }
        }
    }
    assert!(ok >= 6, "{ok} passing rows");
}

fn good() -> (Vec<Vec<u8>>, Vec<u8>, Vec<u8>, i64) {
    (
        chain("caiman-sdk36-TEE_EC_RKP.pem"),
        b64("ZDY4OGQ3NjMtNjExOC00Y2E2LTk0YjItZTZjZDllZDdlNGU0"),
        b64("EDk47kU35Z6O55L2VFBPuDRvxrNG0LvEQV/DOfz8jsE="),
        at("2025-09-28T00:00:00Z"),
    )
}

fn check(chain: &[Vec<u8>]) -> Result<Attested, Refusal> {
    let (_, challenge, digest, now) = good();
    verify(
        chain,
        &Expect {
            challenge: &challenge,
            package: "com.google.android.attestation",
            signature_digest: &digest,
            debug_signature_digest: &[],
            now,
        },
        &HashSet::new(),
    )
}

#[test]
fn a_changed_or_cut_chain_is_refused() {
    let (chain, ..) = good();
    assert!(check(&chain).is_ok());

    // A byte of the leaf's signed part changed: its signature no longer verifies.
    let mut changed = chain.clone();
    let last = changed[0].len() - 80;
    changed[0][last] ^= 1;
    assert!(check(&changed).is_err());

    // Without the root, the last certificate's key isn't a pin.
    assert_eq!(check(&chain[..chain.len() - 1]), Err(Refusal::Root));
    // An intermediate left out: the chain doesn't link.
    let mut gap = chain.clone();
    gap.remove(2);
    assert_eq!(check(&gap), Err(Refusal::Chain));
    // Two swapped.
    let mut swapped = chain.clone();
    swapped.swap(1, 2);
    assert_eq!(check(&swapped), Err(Refusal::Chain));
    // The leaf alone, too many, an oversized or empty certificate.
    assert_eq!(check(&chain[..1]), Err(Refusal::Chain));
    let mut long = chain.clone();
    long.insert(1, chain[1].clone());
    long.insert(1, chain[1].clone());
    assert_eq!(check(&long), Err(Refusal::Chain));
    let mut big = chain.clone();
    big[1] = vec![0x30; MAX_CERT_BYTES + 1];
    assert_eq!(check(&big), Err(Refusal::Chain));
    let mut empty = chain.clone();
    empty[1].clear();
    assert_eq!(check(&empty), Err(Refusal::Chain));
}

/// The KeyDescription must be the leaf's: a chain extended below the attested key (a certificate signed by the leaf,
/// the Kotlin library's `CHAIN_EXTENDED_FOR_KEY`) has its extension above the new "leaf" and is refused.
#[test]
fn a_chain_extended_below_the_key_is_refused() {
    let (chain, ..) = good();
    let mut extended = chain.clone();
    extended.insert(0, chain[0].clone());
    assert_eq!(check(&extended), Err(Refusal::Chain));
}

#[test]
fn the_proof_and_the_enrolment_challenge() {
    let key = crate::device_key::testing::TestKey::new();
    let challenge = enroll_challenge("ABCD-EFGH");
    assert_eq!(challenge.len(), 32);
    let proof = key.sign(proof_message(&challenge, "enroll").as_bytes());
    assert!(verify_proof(
        &key.public_key(),
        &challenge,
        "enroll",
        &proof
    ));
    assert!(!verify_proof(
        &key.public_key(),
        &challenge,
        "register",
        &proof
    ));
    assert!(!verify_proof(
        &key.public_key(),
        &enroll_challenge("ABCD-EFGX"),
        "enroll",
        &proof
    ));
}

#[test]
fn the_checksum_decodes_to_32_bytes() {
    assert_eq!(
        signature_digest(Some("B5bxgJ5HaiznJPE0G8sy0DGFt6ICw0aXh8V3XWXvS-M"))
            .unwrap()
            .len(),
        32
    );
    assert!(signature_digest(Some("short")).is_none());
    assert!(signature_digest(None).is_none());
}

#[test]
fn refusal_words_round_trip() {
    for r in Refusal::ALL {
        assert_eq!(Refusal::from_word(r.word()), Some(*r));
    }
}
