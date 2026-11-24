//! Design 129b §7 (H3): what the PWA says about `emulator_keys` - About's orange "Emulator test key" for a key the
//! emulator profile took (in any build: the stored root says it), Settings' orange line only while the feature is
//! compiled in - and `LAUNCHER_DEBUG_SIGNATURE_CHECKSUM` read only by such a build. The profile's own rows are
//! `attestation::emulator_tests`.

use super::TestApp;
use crate::attestation::{self, roots::Root};

/// A key stored for `device_id` as the emulator profile returns it (or the release profile, `root`).
async fn store_key(app: &TestApp, device_id: i64, root: Root, level: attestation::SecurityLevel) {
    let mut spki = vec![0x30; 26];
    spki.push(4);
    spki.extend([device_id as u8; 64]);
    let attested = attestation::Attested {
        public_key: spki[spki.len() - 65..].to_vec(),
        spki,
        level,
        attestation_version: 400,
        os_patch_level: Some(202507),
        root_of_trust: Some(attestation::RootOfTrust {
            verified_boot_key: vec![0; 32],
            device_locked: false,
            state: attestation::BootState::Unverified,
        }),
        serials: vec!["1".into(), "2".into(), "3".into()],
        root,
    };
    let mut conn = app.db.acquire().await.unwrap();
    crate::device_key::insert_key(
        &mut conn,
        device_id,
        &attested,
        "enroll",
        "127.0.0.1",
        false,
    )
    .await
    .unwrap();
}

#[tokio::test]
async fn an_emulator_key_is_orange_on_about() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (emulator, _) = app.enrolled_device("emulator").await;
    let (phone, _) = app.enrolled_device("phone").await;
    store_key(
        &app,
        emulator,
        Root::Emulator,
        attestation::SecurityLevel::Software,
    )
    .await;
    store_key(
        &app,
        phone,
        Root::GoogleEcCa1,
        attestation::SecurityLevel::TrustedEnvironment,
    )
    .await;

    for (lang, line, level) in [
        ("nb", "Testnøkkel fra emulator", "Bare programvare"),
        ("en", "Emulator test key", "Software only"),
    ] {
        let page = app
            .get_page(&format!("/devices/{emulator}/about?lang={lang}"), &cookie)
            .await
            .text();
        assert!(
            page.contains(&format!("row-value-action\">{line}")),
            "{lang}: {page}"
        );
        assert!(page.contains(level), "{lang}: {page}");
    }
    let page = app
        .get_page(&format!("/devices/{phone}/about?lang=en"), &cookie)
        .await
        .text();
    assert!(page.contains("Hardware key"), "{page}");
    assert!(!page.contains("Emulator test key"), "{page}");
}

#[tokio::test]
async fn settings_says_so_while_compiled_in() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    for (lang, line) in [
        ("nb", "Serveren godtar testnøkler fra emulatorer"),
        ("en", "The server accepts emulator test keys"),
    ] {
        let hub = app
            .get_page(&format!("/settings?lang={lang}"), &cookie)
            .await
            .text();
        assert_eq!(
            hub.contains("data-row=\"emulator-keys\"") && hub.contains(line),
            cfg!(feature = "emulator_keys"),
            "{lang}: {hub}"
        );
    }
}

#[test]
fn the_debug_checksum_is_read_only_with_the_feature() {
    let vars = std::collections::HashMap::from([(
        "LAUNCHER_DEBUG_SIGNATURE_CHECKSUM".to_string(),
        "B".repeat(43),
    )]);
    let config = crate::config::ForkConfig::from_vars(&vars);
    assert_eq!(
        config.launcher_debug_signature_checksum.is_some(),
        cfg!(feature = "emulator_keys")
    );
    assert_eq!(config.launcher_signature_checksum, None);
}
