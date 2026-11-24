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

/// Code QA L2: a build without the feature never lets an emulator key sign - the key index leaves it out (a
/// database moved from a gate server), so its signatures are `unknown_key`. With the feature it is in.
#[tokio::test]
async fn an_emulator_key_signs_only_with_the_feature() {
    let app = TestApp::new().await;
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
    app.state.device_keys.index.reload(&app.db).await.unwrap();
    let want = if cfg!(feature = "emulator_keys") {
        2
    } else {
        1
    };
    assert_eq!(app.state.device_keys.index.len(), want);
}

/// Code QA M2: the Android 16-shaped vector through the real enrolment, with the real attestation check and
/// `LAUNCHER_DEBUG_SIGNATURE_CHECKSUM` set. An enrolment's challenge is the code's (design 129 §5.1), so the vector's
/// fixed QR code is set on the phone. Written against main's route (before design 129b's `attempt`).
#[cfg(feature = "emulator_keys")]
#[tokio::test]
async fn the_emulator_enrols_with_its_key() {
    use axum::body::Body;
    use axum::http::{Method, Request, StatusCode, header};

    let rows: serde_json::Value = serde_json::from_str(
        &std::fs::read_to_string("testdata/attestation/emulator/rows.json").unwrap(),
    )
    .unwrap();
    let enroll = &rows["enroll"];
    let mut config = crate::config::ForkConfig::for_tests();
    config.launcher_debug_signature_checksum = Some(enroll["checksum"].as_str().unwrap().into());
    let app = TestApp::with_config(config).await;
    let (id, _) = app.create_device("emulator").await;
    let code = enroll["code"].as_str().unwrap();
    sqlx::query(
        "UPDATE devices SET enrollment_code_hash = ?, enrollment_code_kind = 'qr', \
         enrollment_code_expires_at = datetime('now', '+30 minutes') WHERE id = ?",
    )
    .bind(crate::enrollment::code_hash(code))
    .bind(id)
    .execute(&app.db)
    .await
    .unwrap();

    // The real check, not the tests' fake; a fresh, empty revocation list.
    let keys = crate::device_key::DeviceKeys::default();
    keys.status
        .take(br#"{"entries": {}}"#, chrono::Utc::now())
        .unwrap();
    let mut state = app.state.clone();
    state.device_keys = std::sync::Arc::new(keys);
    let router = crate::build_device_router(state);

    let pem = std::fs::read_to_string(format!(
        "testdata/attestation/emulator/{}",
        enroll["chain"].as_str().unwrap()
    ))
    .unwrap();
    let chain: Vec<String> = attestation::x509::pem_certificates(&pem)
        .iter()
        .map(|der| base64::Engine::encode(&base64::engine::general_purpose::STANDARD, der))
        .collect();
    let body = serde_json::json!({
        "enrollment_code": code,
        "chain": chain,
        "proof": enroll["proof"],
    });
    let request = Request::builder()
        .method(Method::POST)
        .uri("/api/devices/enroll")
        .header(header::CONTENT_TYPE, "application/json")
        .body(Body::from(body.to_string()))
        .unwrap();
    let res = app.send_via(&router, super::LOOPBACK_PEER, request).await;
    assert_eq!(res.status, StatusCode::OK, "{}", res.text());
    assert_eq!(res.json()["key"]["state"], "observed", "{}", res.text());

    let root: String = sqlx::query_scalar("SELECT root FROM device_keys WHERE device_id = ?")
        .bind(id)
        .fetch_one(&app.db)
        .await
        .unwrap();
    assert_eq!(root, "emulator");
    let logged: i64 = sqlx::query_scalar(
        "SELECT COUNT(*) FROM security_events WHERE event_type = 'device_key_bound'",
    )
    .fetch_one(&app.db)
    .await
    .unwrap();
    assert_eq!(logged, 1);
}
