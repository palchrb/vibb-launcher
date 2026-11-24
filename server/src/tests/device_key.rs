//! Design 129 S1 (observe): the phone key's routes, the edge's signature check and the shared contract
//! (`testdata/device_key.json`). The attestation itself is `attestation::tests`; here a fake one stands in (§7.2:
//! `device_key::testing::fake_attest`, a key made at each run).

use axum::body::Body;
use axum::http::{Method, Request, StatusCode, header};
use base64::Engine;
use sha2::Digest;

use super::{LOOPBACK_PEER, TestApp, TestResponse};
use crate::device_key::testing::TestKey;
use crate::device_key::{self, AuthReason};

fn contract() -> serde_json::Value {
    serde_json::from_str(&std::fs::read_to_string("testdata/device_key.json").unwrap()).unwrap()
}

fn b64u(bytes: &[u8]) -> String {
    base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(bytes)
}

fn unb64u(text: &str) -> Vec<u8> {
    base64::engine::general_purpose::URL_SAFE_NO_PAD
        .decode(text)
        .unwrap()
}

fn unb64(text: &str) -> Vec<u8> {
    base64::engine::general_purpose::STANDARD
        .decode(text)
        .unwrap()
}

/// The other valid form of a P-256 ECDSA signature, `(r, n - s)` (DER in, DER out).
fn malleable(der: &[u8]) -> Vec<u8> {
    // n of P-256, big-endian.
    let n =
        hex::decode("ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632551").unwrap();
    let seq =
        crate::attestation::der::parse_one(der, crate::attestation::der::Tag::SEQUENCE).unwrap();
    let mut fields = seq.reader();
    let r = fields.next().unwrap().content.to_vec();
    let s = fields.next().unwrap().content.to_vec();
    let mut s32 = vec![0u8; 32];
    let s_trim: Vec<u8> = s.iter().copied().skip_while(|b| *b == 0).collect();
    s32[32 - s_trim.len()..].copy_from_slice(&s_trim);
    // n - s, byte by byte with borrow.
    let mut out = vec![0u8; 32];
    let mut borrow = 0i16;
    for i in (0..32).rev() {
        let mut d = n[i] as i16 - s32[i] as i16 - borrow;
        borrow = if d < 0 { 1 } else { 0 };
        if d < 0 {
            d += 256;
        }
        out[i] = d as u8;
    }
    let int = |bytes: &[u8]| {
        let mut b: Vec<u8> = bytes.iter().copied().skip_while(|x| *x == 0).collect();
        if b.first().is_some_and(|x| x & 0x80 != 0) {
            b.insert(0, 0);
        }
        let mut e = vec![0x02, b.len() as u8];
        e.extend(b);
        e
    };
    let mut body = int(&r);
    body.extend(int(&out));
    let mut sig = vec![0x30, body.len() as u8];
    sig.extend(body);
    sig
}

/// A phone enrolled with a key (`key_id` from the enrol answer).
struct Phone {
    id: i64,
    token: String,
    key: TestKey,
    key_id: String,
}

/// `POST /api/devices/enroll` with a key for `code`; `extra` goes into the fake attestation's verdict.
async fn enroll_with(
    app: &TestApp,
    code: &str,
    key: &TestKey,
    extra: serde_json::Value,
) -> TestResponse {
    let challenge = crate::attestation::enroll_challenge(&crate::enrollment::normalize(code));
    let proof = key.sign(crate::attestation::proof_message(&challenge, "enroll").as_bytes());
    app.request(
        Method::POST,
        "/api/devices/enroll",
        None,
        Some(serde_json::json!({
            "enrollment_code": code,
            "chain": key.chain(&challenge, extra),
            "proof": b64u(&proof),
        })),
    )
    .await
}

async fn phone_with_key(app: &TestApp, name: &str) -> Phone {
    let (id, code) = app.create_device(name).await;
    let key = TestKey::new();
    let res = enroll_with(app, &code, &key, serde_json::json!({})).await;
    assert_eq!(res.status, StatusCode::OK, "{}", res.text());
    let answer = res.json();
    assert_eq!(answer["key"]["state"], "observed", "{answer}");
    Phone {
        id,
        token: answer["device_token"].as_str().unwrap().to_string(),
        key,
        key_id: answer["key"]["key_id"].as_str().unwrap().to_string(),
    }
}

/// A request signed with `phone`'s key at the server's signature clock (+ `skew_ms`), no bearer.
fn signed(
    app: &TestApp,
    phone: &Phone,
    method: Method,
    uri: &str,
    body: Vec<u8>,
    content_encoding: &str,
    skew_ms: i64,
) -> Request<Body> {
    let t = app.state.device_keys.clock.now_ms() + skew_ms;
    let target = device_key::signed_target(&uri.parse().unwrap()).unwrap();
    let (signature, body_hash) = phone.key.headers(
        &phone.key_id,
        t,
        method.as_str(),
        &target,
        content_encoding,
        &body,
    );
    let mut builder = Request::builder()
        .method(method)
        .uri(uri)
        .header(device_key::SIGNATURE_HEADER, signature)
        .header(device_key::BODY_HASH_HEADER, body_hash)
        .header(header::CONTENT_TYPE, "application/json");
    if !content_encoding.is_empty() {
        builder = builder.header(header::CONTENT_ENCODING, content_encoding);
    }
    builder.body(Body::from(body)).unwrap()
}

fn vibb_auth(res: &TestResponse) -> Option<String> {
    res.headers
        .get(device_key::AUTH_HEADER)
        .and_then(|v| v.to_str().ok())
        .map(str::to_string)
}

/// The launcher's copy (L1: its `DeviceKeyContractTest` runs the phone's rows) is byte-identical.
#[test]
fn the_contract_matches_the_launchers_copy() {
    let ours = std::fs::read_to_string("testdata/device_key.json").unwrap();
    let path = std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("../launcher/app/src/test/resources/device_key.json");
    let copy = std::fs::read_to_string(&path).unwrap_or_else(|e| panic!("{}: {e}", path.display()));
    assert_eq!(copy, ours, "{} differs", path.display());
}

/// The contract's crypto rows (the script's) through the server's own code.
#[test]
fn the_contract_rows() {
    let c = contract();
    for row in c["canonical"].as_array().unwrap() {
        let body = unb64(row["body_b64"].as_str().unwrap());
        let hash = hex::encode(sha2::Sha256::digest(&body));
        assert_eq!(hash, row["body_sha256"], "{}", row["name"]);
        let path = row["encoded_path"].as_str().unwrap();
        let uri = match row["encoded_query"].as_str() {
            Some(q) => format!("{path}?{q}"),
            None => path.to_string(),
        };
        let target = device_key::signed_target(&uri.parse().unwrap()).unwrap();
        assert_eq!(
            device_key::canonical(
                row["key_id"].as_str().unwrap(),
                row["t"].as_i64().unwrap(),
                row["nonce"].as_str().unwrap(),
                row["method"].as_str().unwrap(),
                &target,
                row["content_encoding"].as_str().unwrap(),
                &hash,
            ),
            row["signed"],
            "{}",
            row["name"]
        );
    }
    for row in c["signed"].as_array().unwrap() {
        let header =
            device_key::SignatureHeader::parse(row["signature"].as_str().unwrap()).unwrap();
        let path = row["encoded_path"].as_str().unwrap();
        let uri = match row["encoded_query"].as_str() {
            Some(q) => format!("{path}?{q}"),
            None => path.to_string(),
        };
        let message = device_key::canonical(
            &header.key_id,
            header.t,
            &header.nonce,
            row["method"].as_str().unwrap(),
            &device_key::signed_target(&uri.parse().unwrap()).unwrap(),
            row["content_encoding"].as_str().unwrap(),
            row["body_sha256"].as_str().unwrap(),
        );
        assert_eq!(
            device_key::verify_signature(
                &unb64u(row["public_key"].as_str().unwrap()),
                message.as_bytes(),
                &header.signature
            ),
            row["valid"] == true,
            "{}",
            row["name"]
        );
    }
    for row in c["proof"].as_array().unwrap() {
        let challenge = unb64u(row["challenge"].as_str().unwrap());
        let kind = row["kind"].as_str().unwrap();
        assert_eq!(
            crate::attestation::proof_message(&challenge, kind),
            row["signed"]
        );
        assert_eq!(
            crate::attestation::verify_proof(
                &unb64u(row["public_key"].as_str().unwrap()),
                &challenge,
                kind,
                &unb64u(row["proof"].as_str().unwrap())
            ),
            row["valid"] == true
        );
    }
    for row in c["enroll_challenge"].as_array().unwrap() {
        let normalised = crate::enrollment::normalize(row["code"].as_str().unwrap());
        assert_eq!(normalised, row["normalised"]);
        assert_eq!(
            b64u(&crate::attestation::enroll_challenge(&normalised)),
            row["challenge"]
        );
    }
    for row in c["clock"]["sig_now"].as_array().unwrap() {
        assert_eq!(
            device_key::sig_base(
                row["stored"].as_i64(),
                row["wall_at_start"].as_i64().unwrap()
            ) + row["elapsed"].as_i64().unwrap(),
            row["sig_now"].as_i64().unwrap(),
            "{}",
            row["name"]
        );
    }
    assert_eq!(c["window"]["past_ms"], device_key::WINDOW_PAST_MS);
    assert_eq!(c["window"]["future_ms"], device_key::WINDOW_FUTURE_MS);
    for row in c["window"]["rows"].as_array().unwrap() {
        let ok =
            device_key::in_window(row["t"].as_i64().unwrap(), row["sig_now"].as_i64().unwrap());
        assert_eq!(if ok { "ok" } else { "stale" }, row["verdict"]);
    }
    let reasons: Vec<&str> = c["answers"]
        .as_array()
        .unwrap()
        .iter()
        .map(|r| r["reason"].as_str().unwrap())
        .collect();
    assert_eq!(
        reasons,
        AuthReason::ALL.iter().map(|r| r.word()).collect::<Vec<_>>()
    );
    let words: Vec<&str> = c["enroll"]["refusal_words"]
        .as_array()
        .unwrap()
        .iter()
        .map(|w| w.as_str().unwrap())
        .collect();
    assert_eq!(
        words,
        crate::attestation::Refusal::ALL
            .iter()
            .map(|r| r.word())
            .collect::<Vec<_>>()
    );
    for (name, value) in [
        ("signature", device_key::SIGNATURE_HEADER),
        ("body_sha256", device_key::BODY_HASH_HEADER),
        ("time", device_key::TIME_HEADER),
        ("auth", device_key::AUTH_HEADER),
    ] {
        assert_eq!(
            c["headers"][name].as_str().unwrap().to_ascii_lowercase(),
            value
        );
    }
}

/// A route answer against the contract's: the same keys, the fixed values equal, `<...>` placeholders any value.
fn shaped(answer: &serde_json::Value, row: &serde_json::Value) {
    match (answer, row) {
        (_, serde_json::Value::String(s)) if s.starts_with('<') => {
            assert!(!answer.is_null(), "{answer} for {row}")
        }
        (serde_json::Value::Object(a), serde_json::Value::Object(r)) => {
            let mut ak: Vec<_> = a.keys().collect();
            let mut rk: Vec<_> = r.keys().collect();
            ak.sort();
            rk.sort();
            assert_eq!(ak, rk, "{answer} vs {row}");
            for (k, v) in r {
                shaped(&a[k], v);
            }
        }
        _ => assert_eq!(answer, row),
    }
}

/// The contract's route rows (`enroll`, `register`, `policy`) through the real routes.
#[tokio::test]
async fn the_contract_routes() {
    let c = contract();
    let app = TestApp::new().await;

    // Enrol: code only, with a key, refused, the lost answer's repeat, a stale list.
    let (_, code) = app.create_device("plain").await;
    let res = app
        .request(
            Method::POST,
            "/api/devices/enroll",
            None,
            Some(serde_json::json!({ "enrollment_code": code })),
        )
        .await;
    shaped(&res.json(), &c["enroll"]["answers"]["code_only"]);
    let plain_token = res.json()["device_token"].as_str().unwrap().to_string();

    let (_, code) = app.create_device("keyed").await;
    let key = TestKey::new();
    let first = enroll_with(&app, &code, &key, serde_json::json!({})).await;
    shaped(&first.json(), &c["enroll"]["answers"]["observed"]);
    let again = enroll_with(&app, &code, &key, serde_json::json!({})).await;
    shaped(&again.json(), &c["enroll"]["answers"]["repeat"]);
    assert_eq!(again.json()["key"]["key_id"], first.json()["key"]["key_id"]);

    let (_, code) = app.create_device("refused").await;
    let res = enroll_with(
        &app,
        &code,
        &TestKey::new(),
        serde_json::json!({"refuse": "app"}),
    )
    .await;
    shaped(&res.json(), &c["enroll"]["answers"]["refused"]);

    app.state
        .device_keys
        .status
        .take(
            br#"{"entries": {}}"#,
            chrono::Utc::now() - chrono::TimeDelta::days(8),
        )
        .unwrap();
    let (_, code) = app.create_device("stale").await;
    let res = enroll_with(&app, &code, &TestKey::new(), serde_json::json!({})).await;
    assert_eq!(
        res.status,
        StatusCode::OK,
        "S1 never refuses an enrolment for its key"
    );
    shaped(&res.json(), &c["enroll"]["answers"]["list_stale"]);
    let plain_challenge = app
        .request(
            Method::GET,
            "/api/devices/key/challenge",
            Some(&plain_token),
            None,
        )
        .await;
    let res = register(
        &app,
        &plain_token,
        &TestKey::new(),
        &plain_challenge.json(),
        serde_json::json!({}),
    )
    .await;
    assert_eq!(res.status, StatusCode::SERVICE_UNAVAILABLE);
    shaped(&res.json(), &c["register"]["answers"][5]["body"]);
    app.state
        .device_keys
        .status
        .take(br#"{"entries": {}}"#, chrono::Utc::now())
        .unwrap();

    // A conflict while the server names no key (L8).
    let res = app
        .request(
            Method::POST,
            "/api/devices/key/conflict",
            Some(&plain_token),
            None,
        )
        .await;
    assert_eq!(res.status, StatusCode::CONFLICT);
    shaped(&res.json(), &c["register"]["conflict_without_key"]["body"]);

    // Register.
    let none = app
        .request(Method::GET, "/api/devices/key", Some(&plain_token), None)
        .await;
    shaped(&none.json(), &c["register"]["get_key_none"]);
    let policy = app.policy(&plain_token).await;
    shaped(&policy["device_key"], &c["policy"]["none"]);
    let challenge = app
        .request(
            Method::GET,
            "/api/devices/key/challenge",
            Some(&plain_token),
            None,
        )
        .await
        .json();
    shaped(&challenge, &c["register"]["challenge"]);
    assert_eq!(unb64u(challenge["challenge"].as_str().unwrap()).len(), 32);

    let refused = register(
        &app,
        &plain_token,
        &TestKey::new(),
        &challenge,
        serde_json::json!({"refuse": "app"}),
    )
    .await;
    assert_eq!(refused.status, StatusCode::BAD_REQUEST);
    shaped(&refused.json(), &c["register"]["answers"][3]["body"]);

    let key = TestKey::new();
    let ok = register(&app, &plain_token, &key, &challenge, serde_json::json!({})).await;
    assert_eq!(ok.status, StatusCode::OK, "{}", ok.text());
    shaped(&ok.json(), &c["register"]["answers"][0]["body"]);
    let same = register(&app, &plain_token, &key, &challenge, serde_json::json!({})).await;
    assert_eq!(same.status, StatusCode::OK);
    assert_eq!(
        same.json(),
        ok.json(),
        "the same key again is the same answer"
    );
    let another = register(
        &app,
        &plain_token,
        &TestKey::new(),
        &challenge,
        serde_json::json!({}),
    )
    .await;
    assert_eq!(another.status, StatusCode::CONFLICT);
    shaped(&another.json(), &c["register"]["answers"][2]["body"]);

    let observed = app
        .request(Method::GET, "/api/devices/key", Some(&plain_token), None)
        .await
        .json();
    shaped(&observed, &c["register"]["get_key_observed"]);
    assert_eq!(
        observed["spki_sha256"],
        crate::attestation::spki_sha256(&key.spki())
    );
    let policy = app.policy(&plain_token).await;
    shaped(&policy["device_key"], &c["policy"]["observed"]);
    assert_eq!(policy["device_key"], observed);

    let conflict = app
        .request(
            Method::POST,
            "/api/devices/key/conflict",
            Some(&plain_token),
            None,
        )
        .await;
    assert_eq!(
        conflict.status.as_u16() as i64,
        c["register"]["conflict"]["status"].as_i64().unwrap()
    );

    // A used challenge: the key gone (as a restore would), the same challenge again.
    let (_, code) = app.create_device("used").await;
    let token = enroll_plain(&app, &code).await;
    let challenge = app
        .request(
            Method::GET,
            "/api/devices/key/challenge",
            Some(&token),
            None,
        )
        .await
        .json();
    let res = register(
        &app,
        &token,
        &TestKey::new(),
        &challenge,
        serde_json::json!({}),
    )
    .await;
    assert_eq!(res.status, StatusCode::OK);
    sqlx::query("DELETE FROM device_keys")
        .execute(&app.db)
        .await
        .unwrap();
    let res = register(
        &app,
        &token,
        &TestKey::new(),
        &challenge,
        serde_json::json!({}),
    )
    .await;
    assert_eq!(res.status, StatusCode::BAD_REQUEST);
    shaped(&res.json(), &c["register"]["answers"][4]["body"]);
}

async fn enroll_plain(app: &TestApp, code: &str) -> String {
    let res = app
        .request(
            Method::POST,
            "/api/devices/enroll",
            None,
            Some(serde_json::json!({ "enrollment_code": code })),
        )
        .await;
    assert_eq!(res.status, StatusCode::OK);
    res.json()["device_token"].as_str().unwrap().to_string()
}

/// `POST /api/devices/key` with `key`, for the challenge answer `challenge`, with the bearer.
async fn register(
    app: &TestApp,
    token: &str,
    key: &TestKey,
    challenge: &serde_json::Value,
    extra: serde_json::Value,
) -> TestResponse {
    register_via(app, LOOPBACK_PEER, token, key, challenge, extra).await
}

async fn register_via(
    app: &TestApp,
    peer: std::net::SocketAddr,
    token: &str,
    key: &TestKey,
    challenge: &serde_json::Value,
    extra: serde_json::Value,
) -> TestResponse {
    let bytes = unb64u(challenge["challenge"].as_str().unwrap());
    let proof = key.sign(crate::attestation::proof_message(&bytes, "register").as_bytes());
    let body = serde_json::json!({
        "challenge": challenge["challenge"],
        "chain": key.chain(&bytes, extra),
        "proof": b64u(&proof),
    });
    let request = Request::builder()
        .method(Method::POST)
        .uri("/api/devices/key")
        .header(header::AUTHORIZATION, format!("Bearer {token}"))
        .header(header::CONTENT_TYPE, "application/json")
        .body(Body::from(body.to_string()))
        .unwrap();
    app.send_via(&app.device_router(), peer, request).await
}

/// §4.5's edge rows: each request kind to its authentication and answer.
#[tokio::test]
async fn the_edge_rows() {
    let c = contract();
    let app = TestApp::new().await;
    let phone = phone_with_key(&app, "phone").await;
    let router = app.device_router();
    let policy = "/api/devices/policy";

    for row in c["edge"].as_array().unwrap() {
        let kind = row["kind"].as_str().unwrap();
        let request = match kind {
            "bearer_valid" => Request::get(policy)
                .header(header::AUTHORIZATION, format!("Bearer {}", phone.token))
                .body(Body::empty())
                .unwrap(),
            "bearer_invalid" => Request::get(policy)
                .header(header::AUTHORIZATION, "Bearer nope")
                .body(Body::empty())
                .unwrap(),
            "nothing" => Request::get(policy).body(Body::empty()).unwrap(),
            "signature_valid" => signed(&app, &phone, Method::GET, policy, vec![], "", 0),
            "signature_unknown_key" => {
                let stranger = Phone {
                    id: 0,
                    token: String::new(),
                    key: TestKey::new(),
                    key_id: b64u(&[7; 16]),
                };
                signed(&app, &stranger, Method::GET, policy, vec![], "", 0)
            }
            "signature_bad" => {
                let impostor = Phone {
                    id: phone.id,
                    token: String::new(),
                    key: TestKey::new(),
                    key_id: phone.key_id.clone(),
                };
                signed(&app, &impostor, Method::GET, policy, vec![], "", 0)
            }
            "signature_stale" => signed(&app, &phone, Method::GET, policy, vec![], "", -120_000),
            "signature_replayed" => {
                let request = signed(&app, &phone, Method::GET, policy, vec![], "", 0);
                let copy = Request::get(policy)
                    .header(
                        device_key::SIGNATURE_HEADER,
                        request.headers()[device_key::SIGNATURE_HEADER].clone(),
                    )
                    .header(
                        device_key::BODY_HASH_HEADER,
                        request.headers()[device_key::BODY_HASH_HEADER].clone(),
                    )
                    .body(Body::empty())
                    .unwrap();
                let first = app.send_via(&router, LOOPBACK_PEER, request).await;
                assert_eq!(first.status, StatusCode::OK);
                copy
            }
            "signature_no_body_hash" => {
                let mut request = signed(&app, &phone, Method::GET, policy, vec![], "", 0);
                request.headers_mut().remove(device_key::BODY_HASH_HEADER);
                request
            }
            "signature_body_changed" => {
                let body = br#"{"id":"00000000000000ff","asked_at_ms":1}"#.to_vec();
                let mut request = signed(
                    &app,
                    &phone,
                    Method::POST,
                    "/api/devices/time-request",
                    body,
                    "",
                    0,
                );
                *request.body_mut() = Body::from(r#"{"id":"00000000000000fe","asked_at_ms":1}"#);
                request
            }
            "signature_body_changed_with_own_bearer" => {
                let body = br#"{"id":"00000000000000ff","asked_at_ms":1}"#.to_vec();
                let mut request = signed(
                    &app,
                    &phone,
                    Method::POST,
                    "/api/devices/time-request",
                    body,
                    "",
                    0,
                );
                *request.body_mut() = Body::from(format!(
                    r#"{{"id":"00000000000000fe","asked_at_ms":{}}}"#,
                    chrono::Utc::now().timestamp_millis()
                ));
                request.headers_mut().insert(
                    header::AUTHORIZATION,
                    format!("Bearer {}", phone.token).parse().unwrap(),
                );
                request
            }
            "signature_bad_with_own_bearer" => {
                let impostor = Phone {
                    id: phone.id,
                    token: String::new(),
                    key: TestKey::new(),
                    key_id: phone.key_id.clone(),
                };
                let mut request = signed(&app, &impostor, Method::GET, policy, vec![], "", 0);
                request.headers_mut().insert(
                    header::AUTHORIZATION,
                    format!("Bearer {}", phone.token).parse().unwrap(),
                );
                request
            }
            other => panic!("an edge row the test doesn't know: {other}"),
        };
        let res = app.send_via(&router, LOOPBACK_PEER, request).await;
        assert_eq!(
            res.status.as_u16() as i64,
            row["status"].as_i64().unwrap(),
            "{kind}: {}",
            res.text()
        );
        assert_eq!(
            vibb_auth(&res).as_deref(),
            row["vibb_auth"].as_str(),
            "{kind}"
        );
        assert!(
            res.headers.contains_key(device_key::TIME_HEADER),
            "{kind}: every device answer carries Vibb-Time"
        );
    }
}

/// S1's own promise: a phone with a key keeps its bearer, signs or not, and a failing signature with its own bearer
/// is logged but never refused.
#[tokio::test]
async fn observe_mode_keeps_the_bearer() {
    let app = TestApp::new().await;
    let phone = phone_with_key(&app, "phone").await;
    app.policy(&phone.token).await;
    let signed_policy = app
        .send(signed(
            &app,
            &phone,
            Method::GET,
            "/api/devices/policy",
            vec![],
            "",
            0,
        ))
        .await;
    assert_eq!(signed_policy.status, StatusCode::OK);
    let signed_at: Option<String> =
        sqlx::query_scalar("SELECT last_signed_at FROM device_keys WHERE key_id = ?")
            .bind(&phone.key_id)
            .fetch_one(&app.db)
            .await
            .unwrap();
    assert!(signed_at.is_some(), "a verified signature is noted");
    // The enrolment and the key are in the security log.
    let bound = super::security_words(&app, "device_key_bound", crate::i18n::Lang::En).await;
    assert_eq!(bound.len(), 1, "{bound:?}");
    assert!(bound[0].contains("At setup"), "{bound:?}");
}

/// A gzip'd status report signed over its compressed bytes (§4.2: the hash is the body as sent, checked before
/// anything is inflated).
#[tokio::test]
async fn a_signed_gzip_report() {
    use std::io::Write;
    let app = TestApp::new().await;
    let phone = phone_with_key(&app, "phone").await;
    let mut gz = flate2::write::GzEncoder::new(Vec::new(), flate2::Compression::default());
    gz.write_all(br#"{"lock_reason": "NONE", "kiosk_engaged": true}"#)
        .unwrap();
    let body = gz.finish().unwrap();
    let res = app
        .send(signed(
            &app,
            &phone,
            Method::POST,
            "/api/devices/status",
            body.clone(),
            "gzip",
            0,
        ))
        .await;
    assert!(res.status.is_success(), "{}: {}", res.status, res.text());

    // Signed over other compressed bytes: refused before anything is inflated.
    let mut request = signed(
        &app,
        &phone,
        Method::POST,
        "/api/devices/status",
        body.clone(),
        "gzip",
        0,
    );
    let mut other = body.clone();
    let last = other.len() - 1;
    other[last] ^= 1;
    *request.body_mut() = Body::from(other);
    let res = app.send(request).await;
    assert_eq!(res.status, StatusCode::BAD_REQUEST);
    assert_eq!(vibb_auth(&res).as_deref(), Some("bad_body"));

    // Signed, but bytes after the gzip member: no report of ours.
    let mut trailing = body.clone();
    trailing.extend_from_slice(b"junk");
    let res = app
        .send(signed(
            &app,
            &phone,
            Method::POST,
            "/api/devices/status",
            trailing,
            "gzip",
            0,
        ))
        .await;
    assert_eq!(res.status, StatusCode::BAD_REQUEST, "{}", res.text());
    assert_eq!(vibb_auth(&res), None);
}

/// §4.5 step 4: the body limit stays each route's own - a signed kid-events body of 1 MiB passes its route's limit,
/// 512 KiB + 1 to a 512 KiB route is a 413; the hash is checked over a large body too.
#[tokio::test]
async fn each_route_keeps_its_body_limit() {
    let app = TestApp::new().await;
    let phone = phone_with_key(&app, "phone").await;
    let big = vec![b' '; crate::calendar_kid_events::BODY_LIMIT];
    let res = app
        .send(signed(
            &app,
            &phone,
            Method::POST,
            "/api/devices/calendar/kid-events",
            big,
            "",
            0,
        ))
        .await;
    assert_ne!(res.status, StatusCode::PAYLOAD_TOO_LARGE, "{}", res.text());
    assert_ne!(res.status, StatusCode::UNAUTHORIZED);
    assert_eq!(vibb_auth(&res), None);
    let over = vec![b' '; crate::limits::DEVICE_BODY_LIMIT + 1];
    let res = app
        .send(signed(
            &app,
            &phone,
            Method::POST,
            "/api/devices/dns-events",
            over,
            "",
            0,
        ))
        .await;
    assert_eq!(res.status, StatusCode::PAYLOAD_TOO_LARGE);
}

/// §5.1: the same code with the same key in the code's own life gets the answer again, with a new bearer (the old
/// one stops); another key with the used code is the ordinary "code used".
#[tokio::test]
async fn a_lost_enrolment_answer() {
    let app = TestApp::new().await;
    let (id, code) = app.create_device("phone").await;
    let key = TestKey::new();
    let first = enroll_with(&app, &code, &key, serde_json::json!({}))
        .await
        .json();
    let again = enroll_with(&app, &code, &key, serde_json::json!({})).await;
    assert_eq!(again.status, StatusCode::OK);
    let again = again.json();
    assert_eq!(again["device_id"], id);
    assert_eq!(again["key"], first["key"]);
    assert_ne!(again["device_token"], first["device_token"]);
    let old = app
        .request(
            Method::GET,
            "/api/devices/policy",
            first["device_token"].as_str(),
            None,
        )
        .await;
    assert_eq!(old.status, StatusCode::UNAUTHORIZED);
    app.policy(again["device_token"].as_str().unwrap()).await;
    let other = enroll_with(&app, &code, &TestKey::new(), serde_json::json!({})).await;
    assert_eq!(other.status, StatusCode::UNAUTHORIZED);
    // Code QA H1: the repeat's body replayed byte for byte (its proof's `r` seen) is refused, a third fresh repeat
    // too, and each repeat is in the security log.
    let third = enroll_with(&app, &code, &key, serde_json::json!({})).await;
    assert_eq!(third.status, StatusCode::OK, "the second repeat");
    let fourth = enroll_with(&app, &code, &key, serde_json::json!({})).await;
    assert_eq!(
        fourth.status,
        StatusCode::UNAUTHORIZED,
        "at most two repeats"
    );
    let again = super::security_words(&app, "device_enrolled", crate::i18n::Lang::En).await;
    assert_eq!(
        again.iter().filter(|w| w.contains("asked again")).count(),
        2,
        "{again:?}"
    );

    let (id, code) = app.create_device("replayed").await;
    let key = TestKey::new();
    let challenge = crate::attestation::enroll_challenge(&crate::enrollment::normalize(&code));
    let proof = key.sign(crate::attestation::proof_message(&challenge, "enroll").as_bytes());
    let body = serde_json::json!({
        "enrollment_code": code,
        "chain": key.chain(&challenge, serde_json::json!({})),
        "proof": b64u(&proof),
    });
    let first = app
        .request(
            Method::POST,
            "/api/devices/enroll",
            None,
            Some(body.clone()),
        )
        .await;
    assert_eq!(first.status, StatusCode::OK);
    let replayed = app
        .request(
            Method::POST,
            "/api/devices/enroll",
            None,
            Some(body.clone()),
        )
        .await;
    assert_eq!(
        replayed.status,
        StatusCode::UNAUTHORIZED,
        "a captured body never replays"
    );
    // Its malleable twin `(r, n - s)` has the same `r`: refused as well.
    let twin = malleable(&proof);
    assert_ne!(twin, proof);
    assert!(crate::attestation::verify_proof(
        &key.public_key(),
        &challenge,
        "enroll",
        &twin
    ));
    let mut twin_body = body.clone();
    twin_body["proof"] = b64u(&twin).into();
    let res = app
        .request(Method::POST, "/api/devices/enroll", None, Some(twin_body))
        .await;
    assert_eq!(res.status, StatusCode::UNAUTHORIZED, "the same r");
    // Revoke Access: the kept code is gone - a fresh repeat is refused.
    let cookie = app.admin_cookie().await;
    app.request_form(
        Method::POST,
        &format!("/devices/{id}/revoke"),
        Some(&cookie),
        &[("confirm", "yes")],
    )
    .await;
    let res = enroll_with(&app, &code, &key, serde_json::json!({})).await;
    assert_eq!(
        res.status,
        StatusCode::UNAUTHORIZED,
        "revoked stays revoked"
    );
    // A code-only enrolment can't be repeated.
    let (_, code) = app.create_device("plain").await;
    enroll_plain(&app, &code).await;
    let res = app
        .request(
            Method::POST,
            "/api/devices/enroll",
            None,
            Some(serde_json::json!({ "enrollment_code": code })),
        )
        .await;
    assert_eq!(res.status, StatusCode::UNAUTHORIZED);
}

/// A new setup code's phone replaces the row's keys; revoking access removes them (the key stops signing).
#[tokio::test]
async fn re_enrolment_and_revoke_remove_the_keys() {
    let app = TestApp::new().await;
    let phone = phone_with_key(&app, "phone").await;
    let code = app.new_code(phone.id, crate::enrollment::Kind::Qr).await;
    enroll_plain(&app, &code).await;
    let res = app
        .send(signed(
            &app,
            &phone,
            Method::GET,
            "/api/devices/policy",
            vec![],
            "",
            0,
        ))
        .await;
    assert_eq!(vibb_auth(&res).as_deref(), Some("unknown_key"));

    let phone = phone_with_key(&app, "second").await;
    let cookie = app.admin_cookie().await;
    let res = app
        .request_form(
            Method::POST,
            &format!("/devices/{}/revoke", phone.id),
            Some(&cookie),
            &[("confirm", "yes")],
        )
        .await;
    assert!(res.status.is_redirection(), "{}", res.status);
    let res = app
        .send(signed(
            &app,
            &phone,
            Method::GET,
            "/api/devices/policy",
            vec![],
            "",
            0,
        ))
        .await;
    assert_eq!(res.status, StatusCode::UNAUTHORIZED);
    assert_eq!(app.state.device_keys.index.len(), 0);
}

/// §5.2 (H3): a registration from an address the phone's bearer never came from (an hour before its challenge) is
/// `unusual`.
#[tokio::test]
async fn an_unusual_address_is_marked() {
    let app = TestApp::new().await;
    let router = app.device_router();
    let home: std::net::SocketAddr = "203.0.113.7:4000".parse().unwrap();
    let away: std::net::SocketAddr = "198.51.100.9:4000".parse().unwrap();
    for (name, peer, unusual) in [("home", home, false), ("away", away, true)] {
        let (id, code) = app.create_device(name).await;
        let token = enroll_plain(&app, &code).await;
        let res = app
            .send_via(
                &router,
                home,
                Request::get("/api/devices/policy")
                    .header(header::AUTHORIZATION, format!("Bearer {token}"))
                    .body(Body::empty())
                    .unwrap(),
            )
            .await;
        assert_eq!(res.status, StatusCode::OK);
        sqlx::query("UPDATE device_addresses SET first_seen = datetime('now', '-2 hours') WHERE device_id = ?")
            .bind(id)
            .execute(&app.db)
            .await
            .unwrap();
        let challenge = app
            .send_via(
                &router,
                peer,
                Request::get("/api/devices/key/challenge")
                    .header(header::AUTHORIZATION, format!("Bearer {token}"))
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .json();
        let res = register_via(
            &app,
            peer,
            &token,
            &TestKey::new(),
            &challenge,
            serde_json::json!({}),
        )
        .await;
        assert_eq!(res.status, StatusCode::OK, "{}", res.text());
        let stored: bool =
            sqlx::query_scalar("SELECT unusual FROM device_keys WHERE device_id = ?")
                .bind(id)
                .fetch_one(&app.db)
                .await
                .unwrap();
        assert_eq!(stored, unusual, "{name}");
    }
}

/// §4.3: the signature clock's floor - a stored value ahead of the wall clock wins at the next start.
#[tokio::test]
async fn the_clock_is_stored() {
    let app = TestApp::new().await;
    device_key::store_clock(&app.state).await;
    let stored = device_key::stored_clock(&app.db).await.unwrap();
    assert!(stored <= app.state.device_keys.clock.now_ms());
    let next = device_key::SigClock::starting(Some(stored), stored - 3_600_000);
    assert!(
        next.now_ms() >= stored + 100_000,
        "never back past the floor"
    );
}

/// About This Phone's quiet values and Settings' one orange line (§9.1, S1).
#[tokio::test]
async fn the_pwa_shows_the_key_quietly() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let phone = phone_with_key(&app, "keyed").await;
    let (plain, code) = app.create_device("plain").await;
    enroll_plain(&app, &code).await;
    let page = app
        .get_page(&format!("/devices/{}/about?lang=en", phone.id), &cookie)
        .await
        .text();
    assert!(page.contains("data-row=\"key\""), "{page}");
    assert!(page.contains("Hardware key"));
    assert!(page.contains("system, locked"), "{page}");
    let page = app
        .get_page(&format!("/devices/{plain}/about?lang=nb"), &cookie)
        .await
        .text();
    assert!(page.contains("Eldre innlogging"));
    assert!(
        !page.contains("row-value-action\">Eldre"),
        "never orange in S1"
    );

    let hub = app.get_page("/settings", &cookie).await.text();
    assert!(!hub.contains("key-list-stale"));
    app.state
        .device_keys
        .status
        .take(
            br#"{"entries": {}}"#,
            chrono::Utc::now() - chrono::TimeDelta::days(8),
        )
        .unwrap();
    let hub = app.get_page("/settings?lang=en", &cookie).await.text();
    assert!(hub.contains("data-row=\"key-list-stale\""));
    assert!(hub.contains("key list is out of date"));
}

/// Code QA L10: without LAUNCHER_SIGNATURE_CHECKSUM, Settings says it once; no phone is blamed for it.
#[tokio::test]
async fn no_checksum_is_said_once_on_settings() {
    let mut config = crate::config::ForkConfig::for_tests();
    config.launcher_signature_checksum = None;
    let app = TestApp::with_config(config).await;
    let cookie = app.admin_cookie().await;
    let (id, code) = app.create_device("phone").await;
    // The fake attestation refuses `app` without the digest, as the real one does.
    let res = enroll_with(&app, &code, &TestKey::new(), serde_json::json!({})).await;
    assert_eq!(res.status, StatusCode::OK);
    assert_eq!(res.json()["key"]["reason"], "app");
    let stored: Option<String> =
        sqlx::query_scalar("SELECT key_refused_reason FROM devices WHERE id = ?")
            .bind(id)
            .fetch_one(&app.db)
            .await
            .unwrap();
    assert_eq!(
        stored, None,
        "the server's setting, not the phone's refusal"
    );
    let hub = app.get_page("/settings", &cookie).await.text();
    assert!(hub.contains("data-row=\"key-no-checksum\""));
    let page = app
        .get_page(&format!("/devices/{id}/about?lang=nb"), &cookie)
        .await
        .text();
    assert!(page.contains("Eldre innlogging"));
    assert!(!page.contains("Nøkkel avvist"));
}

/// §4.5 step 3's assumption, pinned: every POST device route reads its whole body through an extractor before it
/// acts (a handler that never read it could act on a body that wasn't signed). GET and DELETE routes take no body:
/// an empty one is compared at the edge, and so is the conflict report's.
#[test]
fn every_device_post_reads_its_body() {
    // Where each route list lives, and the directory its relative handler paths start from.
    let lists = [
        ("src/device_routes.rs", "src"),
        (
            "src/integrations/map_links/mod.rs",
            "src/integrations/map_links",
        ),
        (
            "src/integrations/wifi_lookup/mod.rs",
            "src/integrations/wifi_lookup",
        ),
    ];
    let extractors = ["Json<", "Bytes", "Body", "body: String"];
    let mut checked = 0;
    for (list, base) in lists {
        let text = std::fs::read_to_string(list).unwrap();
        for chunk in text.split(".route(").skip(1) {
            if !chunk.trim_start().starts_with("\"/api/devices") {
                continue;
            }
            let Some(at) = chunk.find("post(") else {
                continue;
            };
            let path: String = chunk[at + 5..]
                .chars()
                .take_while(|c| c.is_alphanumeric() || *c == '_' || *c == ':')
                .collect();
            let mut parts: Vec<&str> = path.split("::").collect();
            let handler = parts.pop().unwrap().to_string();
            if handler == "post_conflict" {
                continue;
            }
            // `crate::a::b::f` -> src/a/b.rs (or b/mod.rs); `handlers::x::f` -> src/handlers/x.rs; `m::f` -> base/m.rs.
            let (root, parts) = match parts.first() {
                Some(&"crate") => ("src".to_string(), &parts[1..]),
                Some(&"handlers") => ("src".to_string(), &parts[..]),
                _ => (base.to_string(), &parts[..]),
            };
            let stem = format!("{root}/{}", parts.join("/"));
            let source = std::fs::read_to_string(format!("{stem}.rs"))
                .or_else(|_| std::fs::read_to_string(format!("{stem}/mod.rs")))
                .unwrap_or_else(|_| panic!("no module for {path}"));
            let signature = format!("async fn {handler}(");
            let at = source
                .find(&signature)
                .unwrap_or_else(|| panic!("no `{signature}` in {stem}"));
            let end = source[at..].find('{').expect("a body");
            let params = &source[at..at + end];
            // A handler that takes no body never acts on one; one that does reads it whole, through an extractor -
            // never a whole request it could stream from.
            assert!(
                extractors.iter().any(|e| params.contains(e)) || !params.contains("Request"),
                "{path} reads its body some other way: {params}"
            );
            checked += 1;
        }
    }
    assert!(checked >= 20, "{checked} POST routes checked");
}
