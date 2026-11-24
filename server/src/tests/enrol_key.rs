//! Design 129b: a new phone's enrolment requires a proven key (`enrol_key`), with the parent's per-phone exception,
//! the server's own states (D3), the older launcher's 426 (D13) and X's address-history wait (§8).

use axum::http::{Method, StatusCode, header};
use base64::Engine;
use serde_json::json;

use super::{TestApp, TestResponse};
use crate::device_key::testing::TestKey;

fn contract() -> serde_json::Value {
    serde_json::from_str(&std::fs::read_to_string("testdata/device_key.json").unwrap()).unwrap()
}

fn b64u(bytes: &[u8]) -> String {
    base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(bytes)
}

/// A phone's attempt secret, made once per code.
fn new_attempt() -> String {
    b64u(&crate::device_key::random_bytes::<16>())
}

/// The number the server derives from an attempt (D4).
fn number_of(attempt: &str) -> String {
    crate::enrol_key::number(&crate::enrol_key::attempt_hash(attempt).unwrap())
}

/// An enrolment try: `extra` goes into the fake attestation's verdict (`{"refuse": "root"}`), `None` sends no chain
/// (with `key_error` when given).
async fn try_enrol(
    app: &TestApp,
    code: &str,
    attempt: Option<&str>,
    extra: Option<serde_json::Value>,
    key_error: Option<&str>,
) -> TestResponse {
    let mut body = json!({ "enrollment_code": code });
    if let Some(attempt) = attempt {
        body["attempt"] = json!(attempt);
    }
    if let Some(extra) = extra {
        let key = TestKey::new();
        let challenge = crate::attestation::enroll_challenge(&crate::enrollment::normalize(code));
        let proof = key.sign(crate::attestation::proof_message(&challenge, "enroll").as_bytes());
        body["chain"] = json!(key.chain(&challenge, extra));
        body["proof"] = json!(b64u(&proof));
    }
    if let Some(word) = key_error {
        body["key_error"] = json!(word);
    }
    app.request(Method::POST, "/api/devices/enroll", None, Some(body))
        .await
}

/// A refused try (`root`) with `attempt`.
async fn refused(app: &TestApp, code: &str, attempt: &str) -> TestResponse {
    try_enrol(
        app,
        code,
        Some(attempt),
        Some(json!({"refuse": "root"})),
        None,
    )
    .await
}

/// The row's last try moved back past the 10 s pace.
async fn past_pace(app: &TestApp) {
    sqlx::query(
        "UPDATE enroll_key_attempts SET last_at = datetime('now', '-11 seconds'), \
         first_at = datetime(first_at, '-11 seconds')",
    )
    .execute(&app.db)
    .await
    .unwrap();
}

async fn rows(app: &TestApp, device_id: i64) -> i64 {
    sqlx::query_scalar("SELECT COUNT(*) FROM enroll_key_attempts WHERE device_id = ?")
        .bind(device_id)
        .fetch_one(&app.db)
        .await
        .unwrap()
}

async fn events(app: &TestApp, kind: &str) -> i64 {
    sqlx::query_scalar("SELECT COUNT(*) FROM security_events WHERE event_type = ?")
        .bind(kind)
        .fetch_one(&app.db)
        .await
        .unwrap()
}

/// The summary's (or Connect's) confirm form's tag.
fn tag_of(page: &str) -> String {
    let at = page.find("name=\"tag\" value=\"").expect("a confirm form") + 18;
    page[at..at + 16].to_string()
}

async fn confirm(
    app: &TestApp,
    cookie: &str,
    id: i64,
    number: &str,
    tag: &str,
    password: &str,
) -> TestResponse {
    app.request_form(
        Method::POST,
        &format!("/devices/{id}/key-exception"),
        Some(cookie),
        &[
            ("number", number),
            ("tag", tag),
            ("back", "summary"),
            ("password", password),
        ],
    )
    .await
}

const PASSWORD: &str = "correct horse battery staple";

/// An answer against the contract's: the same keys, the fixed values equal, `<...>` placeholders any value.
fn like(answer: &serde_json::Value, row: &serde_json::Value) {
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
                like(&a[k], v);
            }
        }
        _ => assert_eq!(answer, row),
    }
}

/// The contract's `enroll` answers through the real route: every waiting reason, the no-key words, the 426, the
/// server's two 503s and the pace.
#[tokio::test]
async fn the_contract_answers() {
    let c = contract();
    let answers = &c["enroll"]["answers"];
    let app = TestApp::new().await;
    let shaped = |res: &TestResponse, row: &serde_json::Value| {
        assert_eq!(
            res.status.as_u16() as i64,
            row["status"].as_i64().unwrap(),
            "{}",
            res.text()
        );
        if !row["body"].is_null() {
            like(&res.json(), &row["body"]);
        }
        if let Some(after) = row["retry_after"].as_str().filter(|a| !a.starts_with('<')) {
            assert_eq!(res.headers[header::RETRY_AFTER], after);
        }
    };
    let (_, code) = app.create_device("waits").await;
    for row in answers["waiting"].as_array().unwrap() {
        let reason = row["body"]["reason"].as_str().unwrap();
        let attempt = new_attempt();
        let res = match reason {
            "keystore" | "keys_unavailable" | "cap" => {
                try_enrol(&app, &code, Some(&attempt), None, Some(reason)).await
            }
            "challenge" => {
                // A chain for another code's challenge.
                let key = TestKey::new();
                let other = crate::attestation::enroll_challenge("ABCDEFGH");
                let proof =
                    key.sign(crate::attestation::proof_message(&other, "enroll").as_bytes());
                app.request(
                    Method::POST,
                    "/api/devices/enroll",
                    None,
                    Some(json!({
                        "enrollment_code": code, "attempt": attempt,
                        "chain": key.chain(&other, json!({})), "proof": b64u(&proof),
                    })),
                )
                .await
            }
            "proof" => {
                let key = TestKey::new();
                let challenge =
                    crate::attestation::enroll_challenge(&crate::enrollment::normalize(&code));
                let proof = key.sign(b"something else");
                app.request(
                    Method::POST,
                    "/api/devices/enroll",
                    None,
                    Some(json!({
                        "enrollment_code": code, "attempt": attempt,
                        "chain": key.chain(&challenge, json!({})), "proof": b64u(&proof),
                    })),
                )
                .await
            }
            word => {
                try_enrol(
                    &app,
                    &code,
                    Some(&attempt),
                    Some(json!({"refuse": word})),
                    None,
                )
                .await
            }
        };
        shaped(&res, row);
        assert_eq!(res.json()["number"], number_of(&attempt), "{reason}");
        sqlx::query("DELETE FROM enroll_key_attempts")
            .execute(&app.db)
            .await
            .unwrap();
    }
    // The no-key bodies: an unknown or missing key_error is `keystore`.
    for row in c["enroll"]["body_no_key"].as_array().unwrap() {
        let res = try_enrol(
            &app,
            &code,
            Some(&new_attempt()),
            None,
            row["body"]["key_error"].as_str(),
        )
        .await;
        assert_eq!(res.status, StatusCode::CONFLICT);
        assert_eq!(res.json()["reason"], row["reason"]);
        sqlx::query("DELETE FROM enroll_key_attempts")
            .execute(&app.db)
            .await
            .unwrap();
    }
    // The same attempt within 10 s: 429.
    let attempt = new_attempt();
    refused(&app, &code, &attempt).await;
    let res = refused(&app, &code, &attempt).await;
    shaped(&res, &answers["throttled"]);
    assert!(res.headers.contains_key(header::RETRY_AFTER));
    // An older launcher: 426 with the right code, 401 with a wrong one.
    let res = try_enrol(&app, &code, None, None, None).await;
    shaped(&res, &answers["update_app"]);
    let res = try_enrol(&app, "AAAAAAAAAAAAAAAAAAAAAAAAAA", None, None, None).await;
    shaped(&res, &answers["wrong_code"]);
    // The list stale: a chain that would pass is 503 (no row, a fetch asked); a refused one still waits.
    app.state
        .device_keys
        .status
        .take(
            br#"{"entries": {}}"#,
            chrono::Utc::now() - chrono::TimeDelta::days(8),
        )
        .unwrap();
    let (stale_id, stale_code) = app.create_device("stale").await;
    let asked = app.state.device_keys.status.fetches_asked();
    let res = try_enrol(
        &app,
        &stale_code,
        Some(&new_attempt()),
        Some(json!({})),
        None,
    )
    .await;
    shaped(&res, &answers["list_stale"]);
    assert_eq!(rows(&app, stale_id).await, 0);
    assert_eq!(app.state.device_keys.status.fetches_asked(), asked + 1);
    let res = refused(&app, &stale_code, &new_attempt()).await;
    assert_eq!(res.status, StatusCode::CONFLICT, "a real refusal wins");
    // A passing key then: observed, the code's rows gone.
    app.state
        .device_keys
        .status
        .take(br#"{"entries": {}}"#, chrono::Utc::now())
        .unwrap();
    let res = try_enrol(
        &app,
        &stale_code,
        Some(&new_attempt()),
        Some(json!({})),
        None,
    )
    .await;
    shaped(&res, &answers["observed"]);
    assert_eq!(rows(&app, stale_id).await, 0);

    // No LAUNCHER_SIGNATURE_CHECKSUM: 503 server_setup, also while an attempt waits.
    let mut config = crate::config::ForkConfig::for_tests();
    config.launcher_signature_checksum = None;
    let unset = TestApp::with_config(config).await;
    let (_, code) = unset.create_device("unset").await;
    let res = try_enrol(&unset, &code, Some(&new_attempt()), Some(json!({})), None).await;
    shaped(&res, &answers["server_setup"]);
    shaped(&res, &answers["server_setup_while_waiting"]);
}

/// D4/D5: a waiting try keeps the code live, isn't counted as a guess; the same attempt counts up; another attempt
/// is a second row; the ninth is no row.
#[tokio::test]
async fn attempts_are_rows_of_the_live_code() {
    let app = TestApp::new().await;
    let (id, _) = app.create_device("kid").await;
    let code = app.new_code(id, crate::enrollment::Kind::Typed).await;
    let attempt = new_attempt();
    let first = refused(&app, &code, &attempt).await;
    assert_eq!(first.status, StatusCode::CONFLICT);
    assert_eq!(first.json()["retry_after_s"], 15);
    assert_eq!(events(&app, "device_key_refused").await, 1);
    past_pace(&app).await;
    let again = refused(&app, &code, &attempt).await;
    assert_eq!(again.status, StatusCode::CONFLICT);
    assert_eq!(
        events(&app, "device_key_refused").await,
        1,
        "once per attempt"
    );
    let attempts: i64 = sqlx::query_scalar("SELECT attempts FROM enroll_key_attempts")
        .fetch_one(&app.db)
        .await
        .unwrap();
    assert_eq!(attempts, 2);
    assert_eq!(
        events(&app, "enroll_failed").await,
        0,
        "a waiting try is no guess"
    );
    assert!(
        !app.state
            .enrollment
            .typed_paused_until(chrono::Utc::now())
            .is_some()
    );
    for _ in 0..7 {
        refused(&app, &code, &new_attempt()).await;
    }
    assert_eq!(rows(&app, id).await, 8);
    let ninth = refused(&app, &code, &new_attempt()).await;
    assert_eq!(ninth.status, StatusCode::CONFLICT);
    assert_eq!(rows(&app, id).await, 8, "no ninth row");
    // A wrong code is still a counted 401.
    let wrong = refused(&app, "ABCDEFGH", &new_attempt()).await;
    assert_eq!(wrong.status, StatusCode::UNAUTHORIZED);
    // A new code: the rows are dead at once.
    app.new_code(id, crate::enrollment::Kind::Qr).await;
    assert_eq!(rows(&app, id).await, 0);
    let res = refused(&app, &code, &attempt).await;
    assert_eq!(res.status, StatusCode::UNAUTHORIZED);
}

/// D7, D9: the parent types the phone's number and re-authenticates; the phone's next try enrols exempt; the lost
/// answer twice; About and the policy say `exempt`; a passing registration ends it.
#[tokio::test]
async fn the_exception() {
    let c = contract();
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, code) = app.create_device("Ada").await;
    let attempt = new_attempt();
    let other = new_attempt();
    refused(&app, &code, &attempt).await;
    refused(&app, &code, &other).await;

    let page = app
        .get_page(&format!("/devices/{id}"), &cookie)
        .await
        .text();
    assert!(
        page.contains("Ada couldn&#39;t prove its key: unknown issuer"),
        "{page}"
    );
    assert!(page.contains("2 attempts"));
    assert!(
        !page.contains(&number_of(&attempt)),
        "the PWA never shows the number"
    );
    let tag = tag_of(&page);
    let list = app.get_page("/devices", &cookie).await.text();
    assert!(list.contains("data-attention-count=\"1\""), "{list}");
    let provision = app
        .get_page(&format!("/devices/{id}/provision"), &cookie)
        .await
        .text();
    assert!(provision.contains("data-warning=\"key-wait\""));

    // A wrong password: the field's error at #key-exception, counted, logged.
    let res = confirm(&app, &cookie, id, &number_of(&attempt), &tag, "nope").await;
    assert_eq!(
        res.location(),
        Some(format!("/devices/{id}?key_error=password#key-exception").as_str())
    );
    app.assert_lands(&res, &cookie).await;
    let wrong: i64 =
        sqlx::query_scalar("SELECT reauth_wrong FROM admin_users WHERE username = 'parent'")
            .fetch_one(&app.db)
            .await
            .unwrap();
    assert_eq!(wrong, 1);
    assert_eq!(events(&app, "device_key_exempt_refused").await, 1);
    let page = app
        .get_page(&format!("/devices/{id}?key_error=password"), &cookie)
        .await
        .text();
    assert!(
        page.contains("data-row=\"key-exception\" open"),
        "the confirm open"
    );
    // A wrong number.
    let res = confirm(&app, &cookie, id, "000 000", &tag, PASSWORD).await;
    assert_eq!(
        res.location(),
        Some(format!("/devices/{id}?key_error=number#key-exception").as_str())
    );
    // A stale tab: a tag of another code.
    let res = confirm(
        &app,
        &cookie,
        id,
        &number_of(&attempt),
        "00000000000000ff",
        PASSWORD,
    )
    .await;
    assert_eq!(
        res.location(),
        Some(format!("/devices/{id}?notice=key_exception_new_code#key-exception").as_str())
    );
    // Right: the one attempt allowed, the code's life at least 10 more minutes, logged.
    sqlx::query(
        "UPDATE devices SET enrollment_code_expires_at = datetime('now', '+1 minute') WHERE id = ?",
    )
    .bind(id)
    .execute(&app.db)
    .await
    .unwrap();
    let typed = format!(
        "{} {}",
        &number_of(&attempt)[..3],
        &number_of(&attempt)[3..]
    );
    let res = confirm(&app, &cookie, id, &typed, &tag, PASSWORD).await;
    assert_eq!(
        res.location(),
        Some(format!("/devices/{id}?notice=key_exception#key-exception").as_str())
    );
    app.assert_lands(&res, &cookie).await;
    let left: bool = sqlx::query_scalar(
        "SELECT enrollment_code_expires_at >= datetime('now', '+9 minutes') FROM devices WHERE id = ?",
    )
    .bind(id)
    .fetch_one(&app.db)
    .await
    .unwrap();
    assert!(left);
    assert_eq!(events(&app, "device_key_exempted").await, 1);
    // The other attempt still waits; the allowed one enrols exempt at its next try.
    past_pace(&app).await;
    let res = refused(&app, &code, &other).await;
    assert_eq!(res.status, StatusCode::CONFLICT);
    past_pace(&app).await;
    let res = refused(&app, &code, &attempt).await;
    assert_eq!(res.status, StatusCode::OK, "{}", res.text());
    let answer = res.json();
    let mut shape = answer.clone();
    shape["device_id"] = json!("<id>");
    shape["device_token"] = json!("<token>");
    assert_eq!(shape, c["enroll"]["answers"]["exempt"]["body"]);
    let token = answer["device_token"].as_str().unwrap().to_string();
    assert_eq!(
        rows(&app, id).await,
        1,
        "the used row kept, the others gone"
    );
    let exempt: Option<String> =
        sqlx::query_scalar("SELECT key_exempt_reason FROM devices WHERE id = ?")
            .bind(id)
            .fetch_one(&app.db)
            .await
            .unwrap();
    assert_eq!(exempt.as_deref(), Some("root"));
    let key = app
        .request(Method::GET, "/api/devices/key", Some(&token), None)
        .await;
    assert_eq!(key.json(), c["register"]["get_key_exempt"]);
    assert_eq!(
        app.policy(&token).await["device_key"],
        c["policy"]["exempt"]
    );
    let about = app
        .get_page(&format!("/devices/{id}/about"), &cookie)
        .await
        .text();
    assert!(about.contains("Without hardware key"));
    let words = super::security_words(&app, "device_enrolled", crate::i18n::Lang::En).await;
    assert!(
        words.iter().any(|w| w.contains("Without hardware key")),
        "{words:?}"
    );

    // The lost answer: twice with a fresh bearer (the old one refused), then 401.
    let again = refused(&app, &code, &attempt).await;
    assert_eq!(again.status, StatusCode::OK);
    let mut shape = again.json();
    shape["device_id"] = json!("<id>");
    shape["device_token"] = json!("<token>");
    assert_eq!(shape, c["enroll"]["answers"]["exempt_repeat"]["body"]);
    let old = app
        .request(Method::GET, "/api/devices/key", Some(&token), None)
        .await;
    assert_eq!(old.status, StatusCode::UNAUTHORIZED);
    assert_eq!(refused(&app, &code, &attempt).await.status, StatusCode::OK);
    let third = refused(&app, &code, &attempt).await;
    assert_eq!(third.status, StatusCode::UNAUTHORIZED);
}

/// D10: an exempt phone's passing registration ends the exception.
#[tokio::test]
async fn a_passing_registration_ends_the_exception() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, code) = app.create_device("Bo").await;
    let attempt = new_attempt();
    refused(&app, &code, &attempt).await;
    let page = app
        .get_page(&format!("/devices/{id}"), &cookie)
        .await
        .text();
    confirm(
        &app,
        &cookie,
        id,
        &number_of(&attempt),
        &tag_of(&page),
        PASSWORD,
    )
    .await;
    past_pace(&app).await;
    let token = refused(&app, &code, &attempt).await.json()["device_token"]
        .as_str()
        .unwrap()
        .to_string();
    // The address history an hour old (X), then a key that passes.
    app.request(Method::GET, "/api/devices/key", Some(&token), None)
        .await;
    sqlx::query("UPDATE device_addresses SET first_seen = datetime('now', '-2 hours')")
        .execute(&app.db)
        .await
        .unwrap();
    let challenge = app
        .request(
            Method::GET,
            "/api/devices/key/challenge",
            Some(&token),
            None,
        )
        .await;
    assert_eq!(challenge.status, StatusCode::OK, "{}", challenge.text());
    let bytes = base64::engine::general_purpose::URL_SAFE_NO_PAD
        .decode(challenge.json()["challenge"].as_str().unwrap())
        .unwrap();
    let key = TestKey::new();
    let proof = key.sign(crate::attestation::proof_message(&bytes, "register").as_bytes());
    let res = app
        .request(
            Method::POST,
            "/api/devices/key",
            Some(&token),
            Some(json!({
                "challenge": challenge.json()["challenge"],
                "chain": key.chain(&bytes, json!({})),
                "proof": b64u(&proof),
            })),
        )
        .await;
    assert_eq!(res.status, StatusCode::OK, "{}", res.text());
    let exempt: Option<String> =
        sqlx::query_scalar("SELECT key_exempt_at FROM devices WHERE id = ?")
            .bind(id)
            .fetch_one(&app.db)
            .await
            .unwrap();
    assert_eq!(exempt, None);
    let words = super::security_words(&app, "device_key_bound", crate::i18n::Lang::En).await;
    assert!(
        words.iter().any(|w| w.contains("Ends the exception.")),
        "{words:?}"
    );
}

/// D6: a passing try while a row is allowed enrols with the key, never exempt.
#[tokio::test]
async fn a_passing_key_wins() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, code) = app.create_device("Cy").await;
    let attempt = new_attempt();
    refused(&app, &code, &attempt).await;
    let page = app
        .get_page(&format!("/devices/{id}"), &cookie)
        .await
        .text();
    confirm(
        &app,
        &cookie,
        id,
        &number_of(&attempt),
        &tag_of(&page),
        PASSWORD,
    )
    .await;
    let res = try_enrol(&app, &code, Some(&attempt), Some(json!({})), None).await;
    assert_eq!(res.status, StatusCode::OK);
    assert_eq!(res.json()["key"]["state"], "observed");
    let exempt: Option<String> =
        sqlx::query_scalar("SELECT key_exempt_at FROM devices WHERE id = ?")
            .bind(id)
            .fetch_one(&app.db)
            .await
            .unwrap();
    assert_eq!(exempt, None);
    assert_eq!(rows(&app, id).await, 0);
    // Then the confirm's landing: connected.
    let res = confirm(
        &app,
        &cookie,
        id,
        &number_of(&attempt),
        &tag_of(&page),
        PASSWORD,
    )
    .await;
    assert_eq!(
        res.location(),
        Some(format!("/devices/{id}?notice=key_exception_connected#key-exception").as_str())
    );
}

/// D7: five wrong numbers end the code; D8: `app` has no button and can't be allowed.
#[tokio::test]
async fn wrong_numbers_and_the_app_reason() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, code) = app.create_device("Di").await;
    let attempt = new_attempt();
    try_enrol(
        &app,
        &code,
        Some(&attempt),
        Some(json!({"refuse": "app"})),
        None,
    )
    .await;
    let page = app
        .get_page(&format!("/devices/{id}"), &cookie)
        .await
        .text();
    assert!(page.contains("data-warning=\"key-wait\""));
    assert!(
        !page.contains("data-row=\"key-exception\""),
        "no button for app"
    );
    assert!(page.contains("Install Vibb One from the official release."));
    let tag: String = hex::encode(
        sqlx::query_scalar::<_, Vec<u8>>("SELECT enrollment_code_tag FROM devices WHERE id = ?")
            .bind(id)
            .fetch_one(&app.db)
            .await
            .unwrap(),
    );
    let res = confirm(&app, &cookie, id, &number_of(&attempt), &tag, PASSWORD).await;
    assert_eq!(res.status, StatusCode::BAD_REQUEST);
    for n in 1..5 {
        let res = confirm(&app, &cookie, id, "111111", &tag, PASSWORD).await;
        assert!(res.location().unwrap().contains("key_error=number"), "{n}");
    }
    let res = confirm(&app, &cookie, id, "111111", &tag, PASSWORD).await;
    assert!(
        res.location()
            .unwrap()
            .contains("notice=key_exception_new_code")
    );
    let code_left: Option<String> =
        sqlx::query_scalar("SELECT enrollment_code_hash FROM devices WHERE id = ?")
            .bind(id)
            .fetch_one(&app.db)
            .await
            .unwrap();
    assert_eq!(code_left, None);
    assert_eq!(rows(&app, id).await, 0);
    assert_eq!(
        refused(&app, &code, &attempt).await.status,
        StatusCode::UNAUTHORIZED
    );
}

/// M6: a try and a confirm on one row at once end allowed-and-waiting or enrolled, never lost; two tries of one
/// attempt at once make one row.
#[tokio::test]
async fn the_race() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, code) = app.create_device("Ed").await;
    let attempt = new_attempt();
    let (a, b) = tokio::join!(
        refused(&app, &code, &attempt),
        refused(&app, &code, &attempt)
    );
    assert!(a.status == StatusCode::CONFLICT || b.status == StatusCode::CONFLICT);
    assert_eq!(rows(&app, id).await, 1);
    let page = app
        .get_page(&format!("/devices/{id}"), &cookie)
        .await
        .text();
    let tag = tag_of(&page);
    past_pace(&app).await;
    let number = number_of(&attempt);
    let (tried, confirmed) = tokio::join!(
        refused(&app, &code, &attempt),
        confirm(&app, &cookie, id, &number, &tag, PASSWORD)
    );
    assert!(
        confirmed
            .location()
            .unwrap()
            .contains("notice=key_exception")
    );
    let allowed: Option<String> =
        sqlx::query_scalar("SELECT allowed_at FROM enroll_key_attempts WHERE device_id = ?")
            .bind(id)
            .fetch_one(&app.db)
            .await
            .unwrap();
    assert!(allowed.is_some(), "never lost");
    if tried.status == StatusCode::CONFLICT {
        past_pace(&app).await;
        assert_eq!(refused(&app, &code, &attempt).await.status, StatusCode::OK);
    } else {
        assert_eq!(tried.status, StatusCode::OK);
    }
}

/// D3, D13: no code of either kind without the checksum; the older launcher's line on the summary; the sweep.
#[tokio::test]
async fn server_setup_old_app_and_the_sweep() {
    let mut config = crate::config::ForkConfig::for_tests();
    config.launcher_signature_checksum = None;
    let unset = TestApp::with_config(config).await;
    let cookie = unset.admin_cookie().await;
    let (id, _) = unset.create_device("Fi").await;
    unset.set_parent_pin(id).await;
    let res = unset
        .request_form(
            Method::POST,
            &format!("/devices/{id}/regenerate-code"),
            Some(&cookie),
            &[],
        )
        .await;
    assert_eq!(
        res.location(),
        Some(format!("/devices/{id}/provision#setup-code").as_str())
    );
    let page = unset
        .get_page(&format!("/devices/{id}/provision"), &cookie)
        .await
        .text();
    assert!(page.contains("data-row=\"code-needs-setup\""));

    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, code) = app.create_device("Gu").await;
    assert_eq!(
        try_enrol(&app, &code, None, Some(json!({"refuse": "root"})), None)
            .await
            .status,
        StatusCode::UPGRADE_REQUIRED
    );
    let page = app
        .get_page(&format!("/devices/{id}"), &cookie)
        .await
        .text();
    assert!(page.contains("Gu has an older Vibb One"));
    // An empty section stands when nothing shows.
    let (other, _) = app.create_device("Ha").await;
    let page = app
        .get_page(&format!("/devices/{other}"), &cookie)
        .await
        .text();
    assert!(page.contains("<section id=\"key-exception\">"));

    // The sweep: a dead code's rows go.
    refused(&app, &code, &new_attempt()).await;
    sqlx::query("UPDATE devices SET enrollment_code_expires_at = datetime('now', '-1 minute')")
        .execute(&app.db)
        .await
        .unwrap();
    sqlx::query(crate::enrol_key::SWEEP_SQL)
        .execute(&app.db)
        .await
        .unwrap();
    assert_eq!(rows(&app, id).await, 0);
    // Revoke Access removes them too.
    let code = app.new_code(id, crate::enrollment::Kind::Qr).await;
    refused(&app, &code, &new_attempt()).await;
    assert_eq!(rows(&app, id).await, 1);
    app.request_form(
        Method::POST,
        &format!("/devices/{id}/revoke"),
        Some(&cookie),
        &[("confirm", "yes")],
    )
    .await;
    assert_eq!(rows(&app, id).await, 0);
}

/// X (§8): a fresh address history waits; an hour-old one registers, not unusual; a signed request notes its
/// address; S2's re-check rows.
#[tokio::test]
async fn the_address_history_waits() {
    let c = contract();
    let app = TestApp::new().await;
    let (id, token) = app.enrolled_device("Io").await;
    crate::device_key::remove_keys(&app.state, id).await;
    let res = app
        .request(
            Method::GET,
            "/api/devices/key/challenge",
            Some(&token),
            None,
        )
        .await;
    assert_eq!(res.status, StatusCode::SERVICE_UNAVAILABLE);
    let row = c["register"]["answers"]
        .as_array()
        .unwrap()
        .iter()
        .find(|r| r["case"] == "address_history")
        .unwrap()
        .clone();
    let wait = res.json()["retry_after_s"].as_i64().unwrap();
    assert!((3590..=3600).contains(&wait), "{wait}");
    let mut body = res.json();
    body["retry_after_s"] = json!(3600);
    assert_eq!(body, row["body"]);
    assert_eq!(res.headers[header::RETRY_AFTER], wait.to_string());
    sqlx::query("UPDATE device_addresses SET first_seen = datetime('now', '-50 minutes')")
        .execute(&app.db)
        .await
        .unwrap();
    let res = app
        .request(
            Method::POST,
            "/api/devices/key",
            Some(&token),
            Some(json!({"challenge": "x", "chain": [], "proof": "x"})),
        )
        .await;
    assert_eq!(res.status, StatusCode::SERVICE_UNAVAILABLE);
    let wait = res.json()["retry_after_s"].as_i64().unwrap();
    assert!((590..=600).contains(&wait), "{wait}");
    sqlx::query("UPDATE device_addresses SET first_seen = datetime('now', '-61 minutes')")
        .execute(&app.db)
        .await
        .unwrap();
    let res = app
        .request(
            Method::GET,
            "/api/devices/key/challenge",
            Some(&token),
            None,
        )
        .await;
    assert_eq!(res.status, StatusCode::OK);

    assert_eq!(crate::device_key::history_wait(None), Some(3600));
    assert_eq!(crate::device_key::history_wait(Some(3599)), Some(1));
    assert_eq!(crate::device_key::history_wait(Some(3600)), None);

    let at = |s: &str| s.parse::<chrono::DateTime<chrono::Utc>>().unwrap();
    let registered = at("2026-11-24T12:00:00Z");
    let now = at("2026-11-30T12:00:00Z");
    let rows = |last: &str| vec![("10.0.0.1".to_string(), at(last))];
    assert!(crate::device_key::unusual_cleared(
        registered,
        "10.0.0.1",
        &rows("2026-11-25T12:00:00Z"),
        now
    ));
    assert!(!crate::device_key::unusual_cleared(
        registered,
        "10.0.0.1",
        &rows("2026-11-25T11:59:59Z"),
        now
    ));
    assert!(!crate::device_key::unusual_cleared(
        registered,
        "10.0.0.2",
        &rows("2026-11-29T12:00:00Z"),
        now
    ));
    assert!(!crate::device_key::unusual_cleared(
        registered,
        "10.0.0.1",
        &rows("2026-11-29T12:00:00Z"),
        at("2027-01-30T12:00:00Z")
    ));
}
