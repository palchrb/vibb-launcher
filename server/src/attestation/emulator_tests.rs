//! Design 129b §7: the `emulator_keys` profile's rows (`testdata/attestation/emulator/`, made by
//! `scripts/emulator-attestation-vectors.py` from throwaway keys). With the feature each row's verdict; without it
//! every row is `root` - a release server never takes an emulator's chain. CI runs both builds.

use std::collections::HashSet;

use base64::Engine;

use super::*;

const DIR: &str = "testdata/attestation/emulator";

fn b64(text: &str) -> Vec<u8> {
    base64::engine::general_purpose::STANDARD
        .decode(text)
        .unwrap()
}

fn chain(name: &str) -> Vec<Vec<u8>> {
    x509::pem_certificates(&std::fs::read_to_string(format!("{DIR}/{name}")).unwrap())
}

fn rows() -> serde_json::Value {
    serde_json::from_str(&std::fs::read_to_string(format!("{DIR}/rows.json")).unwrap()).unwrap()
}

/// One row through [verify]: the chain, the expectation and the row's revoked serials.
fn run(row: &serde_json::Value, app: &serde_json::Value) -> Result<Attested, Refusal> {
    let challenge = b64(row["challenge"].as_str().unwrap());
    let debug = b64(row["signature"]
        .as_str()
        .unwrap_or(app["signature"].as_str().unwrap()));
    // The release digest is the debug one too: the profile must still read the debug package and the debug setting.
    let expect = Expect {
        challenge: &challenge,
        package: LAUNCHER_PACKAGE,
        signature_digest: &debug,
        debug_signature_digest: &debug,
        now: chrono::DateTime::parse_from_rfc3339(row["now"].as_str().unwrap())
            .unwrap()
            .timestamp(),
    };
    let revoked: HashSet<String> = row["revoked"]
        .as_array()
        .map(|a| a.iter().map(|s| s.as_str().unwrap().to_string()).collect())
        .unwrap_or_default();
    verify(&chain(row["chain"].as_str().unwrap()), &expect, &revoked)
}

#[test]
fn the_rows() {
    let rows = rows();
    let app = &rows["app"];
    let mut ok = 0;
    for row in rows["rows"].as_array().unwrap() {
        let name = row["name"].as_str().unwrap();
        let result = run(row, app);
        if !cfg!(feature = "emulator_keys") {
            assert_eq!(result.err(), Some(Refusal::Root), "{name}");
            continue;
        }
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
                ok += 1;
            }
            word => assert_eq!(
                result.err(),
                Some(Refusal::from_word(word).unwrap()),
                "{name}"
            ),
        }
    }
    if cfg!(feature = "emulator_keys") {
        assert_eq!(ok, 2, "both gate images pass");
    }
}

/// A changed byte anywhere in a passing chain is refused - by its signature, or as not the emulator's root.
#[test]
fn a_changed_chain_is_refused() {
    let rows = rows();
    let row = &rows["rows"][0];
    let mut certs = chain(row["chain"].as_str().unwrap());
    let last = certs[0].len() - 5;
    certs[0][last] ^= 1;
    let challenge = b64(row["challenge"].as_str().unwrap());
    let debug = b64(rows["app"]["signature"].as_str().unwrap());
    let expect = Expect {
        challenge: &challenge,
        package: LAUNCHER_PACKAGE,
        signature_digest: &debug,
        debug_signature_digest: &debug,
        now: chrono::DateTime::parse_from_rfc3339(row["now"].as_str().unwrap())
            .unwrap()
            .timestamp(),
    };
    let refused = verify(&certs, &expect, &HashSet::new()).err();
    let want = if cfg!(feature = "emulator_keys") {
        Refusal::Chain
    } else {
        Refusal::Root
    };
    assert_eq!(refused, Some(want));
}

/// A pinned root never meets the profile: a release chain with the debug package is still `app` (the release profile
/// alone reads it).
#[test]
fn a_pinned_root_is_the_release_profiles_alone() {
    let pem =
        std::fs::read_to_string("testdata/attestation/chains/caiman-sdk36-TEE_EC_RKP.pem").unwrap();
    let challenge = b64("ZDY4OGQ3NjMtNjExOC00Y2E2LTk0YjItZTZjZDllZDdlNGU0");
    let digest = b64("EDk47kU35Z6O55L2VFBPuDRvxrNG0LvEQV/DOfz8jsE=");
    let expect = Expect {
        challenge: &challenge,
        package: "me.vibb.launcher.debug",
        signature_digest: &digest,
        debug_signature_digest: &digest,
        now: chrono::DateTime::parse_from_rfc3339("2025-09-28T00:00:00Z")
            .unwrap()
            .timestamp(),
    };
    let result = verify(&x509::pem_certificates(&pem), &expect, &HashSet::new());
    assert_eq!(result.err(), Some(Refusal::App));
}
