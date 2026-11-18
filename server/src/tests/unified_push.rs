//! Design 108 (Vibb as the UnifiedPush distributor): the shared contract `testdata/unified_push.json` (the policy rows
//! and every device API answer, produced through the routes; the copies identical), the push routes' rows
//! (`testdata/unified_push_gateway.json`, server-only), the ban, the allowlist writers' prune, the security log's
//! redaction, the switch on by default, nothing logged per message.

use axum::body::Body;
use axum::http::{Method, Request, StatusCode};
use serde_json::{Value, json};

use super::TestApp;
use crate::unified_push::CAPABILITY;

const CONTRACT: &str = include_str!("../../testdata/unified_push.json");
const GATEWAY: &str = include_str!("../../testdata/unified_push_gateway.json");
const VAPID: &str = include_str!("../../testdata/unified_push_vapid.json");
const ORIGIN: &str = "https://phones.example.com";

fn contract() -> Value {
    serde_json::from_str(CONTRACT).unwrap()
}

#[test]
fn the_contract_is_identical_to_the_launchers_copy() {
    let launcher = std::fs::read_to_string(concat!(
        env!("CARGO_MANIFEST_DIR"),
        "/../launcher/app/src/test/resources/unified_push.json"
    ))
    .unwrap();
    assert_eq!(launcher, CONTRACT, "the launcher's copy differs");
    assert_eq!(contract()["capability"], CAPABILITY);
    let pattern = contract()["endpoint"]["pattern"]
        .as_str()
        .unwrap()
        .to_string();
    assert_eq!(pattern, "^https://[^/]+/up/[A-Za-z0-9_-]{27}$");
    assert_eq!(contract()["event"], json!({"name": "up", "data": "up"}));
}

async fn set_origin(app: &TestApp, origin: Option<&str>) {
    crate::unified_push::save_origin(&app.state, origin)
        .await
        .unwrap();
}

async fn set_allowlist(app: &TestApp, id: i64, packages: &[&str]) {
    sqlx::query("UPDATE device_policy SET allowlist_json = ? WHERE device_id = ?")
        .bind(serde_json::to_string(packages).unwrap())
        .bind(id)
        .execute(&app.db)
        .await
        .unwrap();
}

/// An answer as the contract writes it: `{status, body}` (`null` for an empty body).
async fn answer(
    app: &TestApp,
    method: Method,
    path: &str,
    token: &str,
    body: Option<Value>,
) -> Value {
    let res = app.request(method, path, Some(token), body).await;
    let body = if res.body.is_empty() {
        Value::Null
    } else {
        res.json()
    };
    json!({"status": res.status.as_u16(), "body": body})
}

fn row<'a>(rows: &'a Value, case: &str) -> &'a Value {
    rows.as_array()
        .unwrap()
        .iter()
        .find(|r| r["case"] == case)
        .unwrap_or_else(|| panic!("no row {case}"))
}

fn expected(rows: &Value, case: &str) -> Value {
    let row = row(rows, case);
    json!({"status": row["status"], "body": row["body"]})
}

async fn registration_id(app: &TestApp, reg: &str) -> i64 {
    sqlx::query_scalar("SELECT id FROM up_registrations WHERE reg = ?")
        .bind(reg)
        .fetch_one(&app.db)
        .await
        .unwrap()
}

/// §9: the policy rows from a real flow, every `register` answer through the route, the unregister's 204 and the
/// messages answer from seeded rows.
#[tokio::test]
async fn the_contract() {
    let app = TestApp::new().await;
    let contract = contract();
    let (emma, token) = app.enrolled_device("Emma").await;
    app.post_status(&token, json!({"capabilities": [CAPABILITY]}))
        .await;
    let policy = &contract["policy"];
    let served = |p: Value| p["unified_push"].clone();
    let register = &contract["register"];
    let path = register["path"].as_str().unwrap();
    let answers = &register["answers"];

    // No address: off.
    assert_eq!(served(app.policy(&token).await), policy["off"]);
    assert_eq!(
        answer(
            &app,
            Method::POST,
            path,
            &token,
            Some(register["body"].clone())
        )
        .await,
        expected(answers, "off")
    );
    set_origin(&app, Some(ORIGIN)).await;
    assert_eq!(served(app.policy(&token).await), policy["on_empty"]);
    set_allowlist(
        &app,
        emma,
        &["org.unifiedpush.example", "io.element.android.x"],
    )
    .await;
    // A registration, and its retry after a lost answer.
    for case in ["ok", "retry"] {
        assert_eq!(
            answer(
                &app,
                Method::POST,
                path,
                &token,
                Some(register["body"].clone())
            )
            .await,
            expected(answers, case),
            "{case}"
        );
    }
    let second = answer(
        &app,
        Method::POST,
        path,
        &token,
        Some(register["body_second"].clone()),
    )
    .await;
    assert_eq!(second["status"], 200, "{second}");
    assert_eq!(served(app.policy(&token).await), policy["on_two"]);
    let endpoint = regex::Regex::new(contract["endpoint"]["pattern"].as_str().unwrap()).unwrap();
    assert!(endpoint.is_match(second["body"]["endpoint"].as_str().unwrap()));
    // R2/R3 refresh (108b §3.3, §4.4): an outside gateway's push marks the Element X registration, a push through our
    // own gateway ends it, and a refusal with no accepted push after it lists `refused`. Every listed reg is held.
    let outside_body =
        json!({"notification": {"event_id": "$fake:example.org", "room_id": "!fake:example.org",
        "type": "m.room.message", "devices": [{"app_id": "io.element.android.x", "pushkey": "x"}]}})
        .to_string()
        .into_bytes();
    let res = post_via_phone_listener(
        &app,
        "203.0.113.7",
        Method::POST,
        "/up/FakeSecretForTheContract002",
        &[],
        outside_body,
    )
    .await;
    assert_eq!(res.status, StatusCode::CREATED, "{}", res.text());
    assert_eq!(served(app.policy(&token).await), policy["refresh_outside"]);
    let notify = json!({"notification": {"event_id": "$fake:example.org", "room_id": "!fake:example.org",
        "type": "m.room.message", "devices": [{"pushkey": format!("{ORIGIN}/up/FakeSecretForTheContract002")}]}});
    let res = post_via_phone_listener(
        &app,
        "203.0.113.7",
        Method::POST,
        "/_matrix/push/v1/notify",
        &[],
        notify.to_string().into_bytes(),
    )
    .await;
    assert_eq!(res.json(), json!({"rejected": []}));
    assert_eq!(served(app.policy(&token).await), policy["on_two"]);
    sqlx::query(
        "UPDATE up_registrations SET last_push_at = datetime('now', '-1 hour'), \
         vapid_refused_at = datetime('now') WHERE reg = 'c0ffee00000000000000000000000002'",
    )
    .execute(&app.db)
    .await
    .unwrap();
    assert_eq!(served(app.policy(&token).await), policy["refresh_refused"]);
    for row in ["refresh_outside", "refresh_refused"] {
        for entry in policy[row]["refresh"].as_array().unwrap() {
            assert!(
                policy[row]["regs"]
                    .as_array()
                    .unwrap()
                    .contains(&entry["reg"])
            );
        }
    }
    sqlx::query("UPDATE up_registrations SET vapid_refused_at = NULL")
        .execute(&app.db)
        .await
        .unwrap();
    sqlx::query("DELETE FROM up_messages")
        .execute(&app.db)
        .await
        .unwrap();
    // The reg with another secret, a tombstoned secret: conflict. Off the list: not_allowed. Malformed: 400.
    let mut other = register["body"].clone();
    other["secret"] = json!("FakeSecretForTheContract009");
    assert_eq!(
        answer(&app, Method::POST, path, &token, Some(other)).await,
        expected(answers, "conflict")
    );
    let mut mastodon = register["body"].clone();
    mastodon["reg"] = json!("c0ffee00000000000000000000000009");
    mastodon["secret"] = json!("FakeSecretForTheContract010");
    mastodon["package"] = json!("org.joinmastodon.android");
    assert_eq!(
        answer(&app, Method::POST, path, &token, Some(mastodon)).await,
        expected(answers, "not_allowed")
    );
    assert_eq!(
        answer(&app, Method::POST, path, &token, Some(json!({"reg": "x"}))).await,
        expected(answers, "invalid")
    );

    // The origin changed and back (M4).
    set_origin(&app, Some("https://push.example.net")).await;
    assert_eq!(served(app.policy(&token).await), policy["origin_changed"]);
    set_origin(&app, Some(ORIGIN)).await;

    // R3 (108b §4.6): a key registers (200), another valid key is an update stored at once (200, the same endpoint, the
    // refusal's stamp cleared), a key that isn't a point is a 400 and changes nothing, and a keyless REGISTER of the
    // same token keeps the key (H1).
    let stored_key = || async {
        sqlx::query_scalar::<_, Option<String>>(
            "SELECT vapid FROM up_registrations WHERE reg = 'c0ffee00000000000000000000000003'",
        )
        .fetch_one(&app.db)
        .await
        .unwrap()
    };
    assert_eq!(
        answer(
            &app,
            Method::POST,
            path,
            &token,
            Some(register["body_vapid"].clone())
        )
        .await,
        expected(answers, "ok_vapid")
    );
    assert_eq!(
        stored_key().await,
        register["body_vapid"]["vapid"].as_str().map(str::to_string)
    );
    sqlx::query("UPDATE up_registrations SET vapid_refused_at = datetime('now') WHERE reg = 'c0ffee00000000000000000000000003'")
        .execute(&app.db)
        .await
        .unwrap();
    assert_eq!(
        answer(
            &app,
            Method::POST,
            path,
            &token,
            Some(register["body_vapid_changed"].clone())
        )
        .await,
        expected(answers, "vapid_changed")
    );
    assert_eq!(
        stored_key().await,
        register["body_vapid_changed"]["vapid"]
            .as_str()
            .map(str::to_string)
    );
    let refused_at: Option<String> = sqlx::query_scalar(
        "SELECT vapid_refused_at FROM up_registrations WHERE reg = 'c0ffee00000000000000000000000003'",
    )
    .fetch_one(&app.db)
    .await
    .unwrap();
    assert_eq!(refused_at, None, "an update clears the refusal");
    assert_eq!(
        answer(
            &app,
            Method::POST,
            path,
            &token,
            Some(register["body_vapid_not_a_point"].clone())
        )
        .await,
        expected(answers, "vapid_not_a_point")
    );
    let mut keyless = register["body_vapid"].clone();
    keyless.as_object_mut().unwrap().remove("vapid");
    assert_eq!(
        answer(&app, Method::POST, path, &token, Some(keyless)).await["status"],
        200
    );
    assert_eq!(
        stored_key().await,
        register["body_vapid_changed"]["vapid"]
            .as_str()
            .map(str::to_string),
        "a REGISTER without a key never drops one"
    );

    // The messages: two seeded rows (the second registration's Matrix body first), acks of keys no longer there.
    let messages = &contract["messages"];
    let now = chrono::Utc::now().timestamp_millis();
    for message in messages["answer"]["messages"].as_array().unwrap() {
        use base64::Engine as _;
        let body = base64::engine::general_purpose::STANDARD
            .decode(message["body"].as_str().unwrap())
            .unwrap();
        sqlx::query(
            "INSERT INTO up_messages (key, registration_id, device_id, body, urgency, created_at_ms, \
             expires_at_ms) VALUES (?, ?, ?, ?, ?, ?, ?)",
        )
        .bind(message["key"].as_str().unwrap())
        .bind(registration_id(&app, message["reg"].as_str().unwrap()).await)
        .bind(emma)
        .bind(body)
        .bind(message["urgency"].as_str().unwrap())
        .bind(now)
        .bind(now + 60_000)
        .execute(&app.db)
        .await
        .unwrap();
    }
    let res = app
        .request(
            Method::POST,
            messages["path"].as_str().unwrap(),
            Some(&token),
            Some(messages["body"].clone()),
        )
        .await;
    assert_eq!(res.status, StatusCode::OK, "{}", res.text());
    assert_eq!(res.json(), messages["answer"]);
    // Acknowledged: gone, and only this phone's keys count.
    let keys: Vec<Value> = messages["answer"]["messages"]
        .as_array()
        .unwrap()
        .iter()
        .map(|m| m["key"].clone())
        .collect();
    let res = app
        .request(
            Method::POST,
            messages["path"].as_str().unwrap(),
            Some(&token),
            Some(json!({"ack": keys})),
        )
        .await;
    assert_eq!(res.json(), json!({"messages": [], "more": false}));

    // Stop: the app's rows go, the package is on the list, its own new REGISTER is refused.
    crate::unified_push::stop(&app.state, emma, "org.unifiedpush.example")
        .await
        .unwrap();
    assert_eq!(served(app.policy(&token).await), policy["stopped"]);
    let mut again = register["body"].clone();
    again["reg"] = json!("c0ffee00000000000000000000000003");
    again["secret"] = json!("FakeSecretForTheContract003");
    assert_eq!(
        answer(&app, Method::POST, path, &token, Some(again)).await,
        expected(answers, "stopped")
    );
    // The unregister.
    let unregister = &contract["unregister"];
    let res = app
        .request(
            Method::DELETE,
            unregister["path"].as_str().unwrap(),
            Some(&token),
            None,
        )
        .await;
    assert_eq!(res.status.as_u16(), unregister["status"]);

    // Another phone: the caps and the churn limit (L10).
    let (jack, jack_token) = app.enrolled_device("Jack").await;
    set_allowlist(
        &app,
        jack,
        &["org.unifiedpush.example", "io.element.android.x"],
    )
    .await;
    for n in 0..20 {
        let res = app
            .request(
                Method::POST,
                path,
                Some(&jack_token),
                Some(json!({"reg": format!("{n:032x}"), "secret": format!("FakeSecretForTheBusyTest{n:03}"),
                    "package": "org.unifiedpush.example"})),
            )
            .await;
        assert_eq!(res.status, StatusCode::OK, "{n}: {}", res.text());
    }
    assert_eq!(
        answer(
            &app,
            Method::POST,
            path,
            &jack_token,
            Some(
                json!({"reg": format!("{:032x}", 20), "secret": "FakeSecretForTheBusyTest020",
                "package": "org.unifiedpush.example"})
            )
        )
        .await,
        expected(answers, "busy")
    );
    for n in 0..100 {
        sqlx::query(
            "INSERT INTO up_registrations (device_id, reg, secret_hash, package) \
             VALUES (?, ?, ?, 'io.element.android.x')",
        )
        .bind(jack)
        .bind(format!("{:032x}", 1000 + n))
        .bind(crate::unified_push::secret_hash(&format!(
            "FakeSecretForTheLimitTes{n:03}"
        )))
        .execute(&app.db)
        .await
        .unwrap();
    }
    assert_eq!(
        answer(
            &app,
            Method::POST,
            path,
            &jack_token,
            Some(
                json!({"reg": format!("{:032x}", 2000), "secret": "FakeSecretForTheLimitTes999",
                "package": "io.element.android.x"})
            )
        )
        .await,
        expected(answers, "limit")
    );
}

// ---- The push routes ------------------------------------------------------------------------------------------------

fn fill(text: &str, gateway: &Value) -> String {
    let mut out = text.to_string();
    for key in ["origin", "previous", "live", "gone", "unknown"] {
        out = out.replace(&format!("{{{key}}}"), gateway[key].as_str().unwrap());
    }
    out
}

fn body_of(value: &Value, gateway: &Value) -> Vec<u8> {
    match value {
        Value::String(text) => fill(text, gateway).into_bytes(),
        Value::Object(repeat) => repeat["repeat"]
            .as_str()
            .unwrap()
            .repeat(repeat["count"].as_u64().unwrap() as usize)
            .into_bytes(),
        _ => Vec::new(),
    }
}

/// A phone with one live registration (`live`) and a deleted one (`gone`), the origin changed from `previous` to
/// `origin` just now.
async fn gateway_app(gateway: &Value) -> (TestApp, i64) {
    let app = TestApp::new().await;
    let (emma, _) = app.enrolled_device("Emma").await;
    set_origin(&app, Some(gateway["previous"].as_str().unwrap())).await;
    set_origin(&app, Some(gateway["origin"].as_str().unwrap())).await;
    for (n, key) in [(1, "live"), (2, "gone")] {
        sqlx::query(
            "INSERT INTO up_registrations (device_id, reg, secret_hash, package) \
             VALUES (?, ?, ?, 'io.element.android.x')",
        )
        .bind(emma)
        .bind(format!("{n:032x}"))
        .bind(crate::unified_push::secret_hash(
            gateway[key].as_str().unwrap(),
        ))
        .execute(&app.db)
        .await
        .unwrap();
    }
    crate::unified_push::store::delete_registrations(
        &app.db,
        emma,
        crate::unified_push::store::Which::Reg(&format!("{:032x}", 2)),
    )
    .await
    .unwrap();
    (app, emma)
}

async fn post_via_phone_listener(
    app: &TestApp,
    peer: &str,
    method: Method,
    path: &str,
    headers: &[(&str, &str)],
    body: Vec<u8>,
) -> super::TestResponse {
    let mut request = Request::builder().method(method).uri(path);
    for (name, value) in headers {
        request = request.header(*name, *value);
    }
    let peer: std::net::SocketAddr = format!("{peer}:50000").parse().unwrap();
    app.send_via(
        &app.device_router(),
        peer,
        request.body(Body::from(body)).unwrap(),
    )
    .await
}

/// `testdata/unified_push_gateway.json`: ntfy's Matrix tests and §3.4's answer table through the phone listener, each
/// row's queue row checked (then cleared).
#[tokio::test]
async fn gateway_rows() {
    let gateway: Value = serde_json::from_str(GATEWAY).unwrap();
    let (app, _) = gateway_app(&gateway).await;
    for row in gateway["rows"].as_array().unwrap() {
        let case = row["case"].as_str().unwrap();
        let method = Method::from_bytes(row["method"].as_str().unwrap().as_bytes()).unwrap();
        let headers: Vec<(String, String)> = row["headers"]
            .as_object()
            .map(|h| {
                h.iter()
                    .map(|(k, v)| (k.clone(), v.as_str().unwrap().to_string()))
                    .collect()
            })
            .unwrap_or_default();
        let headers: Vec<(&str, &str)> = headers
            .iter()
            .map(|(k, v)| (k.as_str(), v.as_str()))
            .collect();
        let res = post_via_phone_listener(
            &app,
            "203.0.113.7",
            method,
            &fill(row["path"].as_str().unwrap(), &gateway),
            &headers,
            body_of(&row["body"], &gateway),
        )
        .await;
        assert_eq!(res.status.as_u16(), row["status"], "{case}: {}", res.text());
        if let Some(text) = row["answer_text"].as_str() {
            assert_eq!(res.text(), text, "{case}");
        }
        if !row["answer"].is_null() {
            let expected: Value =
                serde_json::from_str(&fill(&row["answer"].to_string(), &gateway)).unwrap();
            assert_eq!(res.json(), expected, "{case}");
        }
        if let Some(out) = row["headers_out"].as_object() {
            assert_eq!(res.headers["ttl"].to_str().unwrap(), out["ttl"], "{case}");
            let location = res.headers["location"].to_str().unwrap();
            let prefix = fill(out["location_prefix"].as_str().unwrap(), &gateway);
            let key = location
                .strip_prefix(&prefix)
                .unwrap_or_else(|| panic!("{case}: {location}"));
            let stored: Option<i64> = sqlx::query_scalar("SELECT 1 FROM up_messages WHERE key = ?")
                .bind(key)
                .fetch_optional(&app.db)
                .await
                .unwrap();
            assert!(
                stored.is_some(),
                "{case}: Location names the stored row's key"
            );
        }
        // Every answer of the push routes has the device API's headers (a path that is no route is the listener's).
        if !row["path"].as_str().unwrap().starts_with("/up/m/") {
            assert_eq!(res.headers["x-content-type-options"], "nosniff", "{case}");
            assert_eq!(res.headers["cache-control"], "no-store", "{case}");
        }
        let queued: Vec<(Vec<u8>, String, Option<String>)> =
            sqlx::query_as("SELECT body, urgency, topic FROM up_messages ORDER BY id")
                .fetch_all(&app.db)
                .await
                .unwrap();
        match row["queued"].as_object() {
            None => assert!(queued.is_empty(), "{case}: nothing queued"),
            Some(expected) => {
                assert_eq!(queued.len(), 1, "{case}");
                assert_eq!(
                    String::from_utf8(queued[0].0.clone()).unwrap(),
                    fill(expected["body"].as_str().unwrap(), &gateway),
                    "{case}"
                );
                assert_eq!(queued[0].1, expected["urgency"], "{case}");
                assert_eq!(queued[0].2.as_deref(), expected["topic"].as_str(), "{case}");
            }
        }
        sqlx::query("DELETE FROM up_messages")
            .execute(&app.db)
            .await
            .unwrap();
    }
    // A Topic replaces the pending row with the same topic.
    for _ in 0..2 {
        let res = post_via_phone_listener(
            &app,
            "203.0.113.7",
            Method::POST,
            &fill("/up/{live}", &gateway),
            &[("topic", "same")],
            b"x".to_vec(),
        )
        .await;
        assert_eq!(res.status, StatusCode::CREATED);
    }
    let pending: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM up_messages")
        .fetch_one(&app.db)
        .await
        .unwrap();
    assert_eq!(pending, 1);
    // Past the previous origin's 30 days, a pushkey on it is rejected.
    sqlx::query("UPDATE up_previous_origins SET until_at = datetime('now', '-1 minute')")
        .execute(&app.db)
        .await
        .unwrap();
    let pushkey = fill("{previous}/up/{live}", &gateway);
    let res = post_via_phone_listener(
        &app,
        "203.0.113.7",
        Method::POST,
        "/_matrix/push/v1/notify",
        &[],
        json!({"notification": {"devices": [{"pushkey": pushkey}]}})
            .to_string()
            .into_bytes(),
    )
    .await;
    assert_eq!(res.json(), json!({"rejected": [pushkey]}));
    // Cleared: discovery is 404, the secrets gone.
    set_origin(&app, None).await;
    let res = post_via_phone_listener(
        &app,
        "203.0.113.7",
        Method::GET,
        "/_matrix/push/v1/notify",
        &[],
        Vec::new(),
    )
    .await;
    assert_eq!(res.status, StatusCode::NOT_FOUND);
    let res = post_via_phone_listener(
        &app,
        "203.0.113.7",
        Method::POST,
        &fill("/up/{live}", &gateway),
        &[],
        b"x".to_vec(),
    )
    .await;
    assert_eq!(res.status, StatusCode::GONE);
}

/// A fresh ES256 token for the test key (the private scalar is SHA-256 of the vectors' fixed phrase, derived here and
/// never committed): Mastodon's shape, `aud` and `exp` as given.
fn sign_live(file: &Value, aud: &str, exp: i64) -> String {
    use base64::Engine as _;
    use sha2::Digest as _;
    let b64 = |bytes: &[u8]| base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(bytes);
    let public = base64::engine::general_purpose::URL_SAFE_NO_PAD
        .decode(file["registered_key"].as_str().unwrap())
        .unwrap();
    // The vectors' script derives the scalar as `SHA-256 mod (n - 1) + 1`: the digest (below n - 1) plus one.
    let mut scalar: [u8; 32] =
        sha2::Sha256::digest(b"vibb fake vapid key - test only, never a real sender").into();
    for byte in scalar.iter_mut().rev() {
        let (next, carried) = byte.overflowing_add(1);
        *byte = next;
        if !carried {
            break;
        }
    }
    let pair = ring::signature::EcdsaKeyPair::from_private_key_and_public_key(
        &ring::signature::ECDSA_P256_SHA256_FIXED_SIGNING,
        &scalar,
        &public,
        &ring::rand::SystemRandom::new(),
    )
    .expect("the derived scalar is the vectors' key");
    let input = format!(
        "{}.{}",
        b64(br#"{"typ":"JWT","alg":"ES256"}"#),
        b64(
            json!({"aud": aud, "exp": exp, "sub": "mailto:admin@example.org"})
                .to_string()
                .as_bytes()
        )
    );
    let signature = pair
        .sign(&ring::rand::SystemRandom::new(), input.as_bytes())
        .unwrap();
    format!(
        "vapid t={input}.{},k={}",
        b64(signature.as_ref()),
        file["registered_key"].as_str().unwrap()
    )
}

/// L4 (QA of R23-S): a push signed live with the throwaway test key is taken at the route (Mastodon's header
/// shape, the real clock, the current origin) and queued; a foreign `aud` is 503 and stamped `address`, an `exp` a
/// week out 503 and stamped `time`; `Crypto-Key` on two lines is read (L5).
#[tokio::test]
async fn a_signed_push_is_taken_at_the_route() {
    let gateway: Value = serde_json::from_str(GATEWAY).unwrap();
    let file: Value = serde_json::from_str(VAPID).unwrap();
    let (app, _) = gateway_app(&gateway).await;
    let reg = format!("{:032x}", 1);
    sqlx::query("UPDATE up_registrations SET vapid = ? WHERE reg = ?")
        .bind(file["registered_key"].as_str().unwrap())
        .bind(&reg)
        .execute(&app.db)
        .await
        .unwrap();
    let origin = gateway["origin"].as_str().unwrap();
    let path = fill("/up/{live}", &gateway);
    let now = chrono::Utc::now().timestamp();
    let post = |authorization: String, extra: Vec<(&'static str, String)>| {
        let (app, path) = (&app, path.clone());
        async move {
            let mut headers = vec![("authorization", authorization.as_str())];
            headers.extend(extra.iter().map(|(k, v)| (*k, v.as_str())));
            post_via_phone_listener(
                app,
                "203.0.113.7",
                Method::POST,
                &path,
                &headers,
                b"x".to_vec(),
            )
            .await
        }
    };
    let why = || async {
        sqlx::query_scalar::<_, Option<String>>(
            "SELECT vapid_refused_why FROM up_registrations WHERE reg = ?",
        )
        .bind(&reg)
        .fetch_one(&app.db)
        .await
        .unwrap()
    };
    let res = post(
        sign_live(&file, "https://elsewhere.example.net", now + 86_400),
        vec![],
    )
    .await;
    assert_eq!(res.status, StatusCode::SERVICE_UNAVAILABLE);
    assert_eq!(why().await.as_deref(), Some("address"));
    sqlx::query("UPDATE up_registrations SET vapid_refused_at = NULL")
        .execute(&app.db)
        .await
        .unwrap();
    let res = post(sign_live(&file, origin, now + 7 * 86_400), vec![]).await;
    assert_eq!(res.status, StatusCode::SERVICE_UNAVAILABLE);
    assert_eq!(why().await.as_deref(), Some("time"));
    let queued = || async {
        sqlx::query_scalar::<_, i64>("SELECT COUNT(*) FROM up_messages")
            .fetch_one(&app.db)
            .await
            .unwrap()
    };
    assert_eq!(queued().await, 0);
    let res = post(sign_live(&file, origin, now + 86_400), vec![]).await;
    assert_eq!(res.status, StatusCode::CREATED);
    assert_eq!(queued().await, 1);
    // The draft form with `Crypto-Key` on two lines: `dh=` first, `p256ecdsa=` on its own line.
    let key = file["registered_key"].as_str().unwrap();
    let token = sign_live(&file, origin, now + 86_400);
    let token = token
        .strip_prefix("vapid t=")
        .and_then(|t| t.split_once(',').map(|(t, _)| t.to_string()))
        .unwrap();
    let res = post(
        format!("WebPush {token}"),
        vec![
            ("crypto-key", "dh=AAAA".to_string()),
            ("crypto-key", format!("p256ecdsa={key}")),
        ],
    )
    .await;
    assert_eq!(
        res.status,
        StatusCode::CREATED,
        "both Crypto-Key lines read"
    );
}

/// 108b §4.3/§4.4/§4.5 at the routes: a keyed registration answers a push without a signature 401, with another key or
/// a bad signature 403, and a signed push whose `exp` has passed (the file's clock is in the past) 503 with
/// `Retry-After: 300`; none queues or writes the stamp twice within a minute (that a refusal charges no bucket and
/// counts toward no ban is the code's order - `accept` and `never_seen` come after the check - and isn't asserted
/// here). An unkeyed registration takes an unsigned push. The gateway rejects a keyed pushkey and stamps it; a gateway push
/// sets `gateway_at`.
#[tokio::test]
async fn vapid_at_the_route() {
    let gateway: Value = serde_json::from_str(GATEWAY).unwrap();
    let file: Value = serde_json::from_str(VAPID).unwrap();
    let (app, _) = gateway_app(&gateway).await;
    let rows = file["rows"].as_array().unwrap();
    let auth = |name: &str| {
        rows.iter().find(|r| r["name"] == name).unwrap()["authorization"]
            .as_str()
            .unwrap()
            .to_string()
    };
    let reg = format!("{:032x}", 1);
    // Unkeyed first: an unsigned push is taken.
    let post = |headers: Vec<(&'static str, String)>| {
        let app = &app;
        let path = fill("/up/{live}", &gateway);
        async move {
            let headers: Vec<(&str, &str)> =
                headers.iter().map(|(k, v)| (*k, v.as_str())).collect();
            post_via_phone_listener(
                app,
                "203.0.113.7",
                Method::POST,
                &path,
                &headers,
                b"x".to_vec(),
            )
            .await
        }
    };
    assert_eq!(post(vec![]).await.status, StatusCode::CREATED);
    sqlx::query("DELETE FROM up_messages")
        .execute(&app.db)
        .await
        .unwrap();
    sqlx::query("UPDATE up_registrations SET vapid = ? WHERE reg = ?")
        .bind(file["registered_key"].as_str().unwrap())
        .bind(&reg)
        .execute(&app.db)
        .await
        .unwrap();
    let res = post(vec![]).await;
    assert_eq!(res.status, StatusCode::UNAUTHORIZED);
    assert_eq!(res.headers["www-authenticate"], "vapid");
    assert_eq!(
        post(vec![("authorization", auth("wrong_key"))])
            .await
            .status,
        StatusCode::FORBIDDEN
    );
    assert_eq!(
        post(vec![("authorization", auth("bad_signature"))])
            .await
            .status,
        StatusCode::FORBIDDEN
    );
    // Correctly signed, but the real clock is past the file's `exp`: 503, Retry-After 300.
    let res = post(vec![("authorization", auth("rfc_mastodon"))]).await;
    assert_eq!(res.status, StatusCode::SERVICE_UNAVAILABLE);
    assert_eq!(res.headers["retry-after"], "300");
    let queued: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM up_messages")
        .fetch_one(&app.db)
        .await
        .unwrap();
    assert_eq!(queued, 0, "nothing queued");
    let stamp = |app: &TestApp| {
        let db = app.db.clone();
        let reg = reg.clone();
        async move {
            sqlx::query_scalar::<_, Option<String>>(
                "SELECT vapid_refused_at FROM up_registrations WHERE reg = ?",
            )
            .bind(reg)
            .fetch_one(&db)
            .await
            .unwrap()
        }
    };
    assert!(stamp(&app).await.is_some(), "the refusal is stamped");
    // A refusal within the minute writes nothing more.
    sqlx::query("UPDATE up_registrations SET vapid_refused_at = datetime('now', '-10 seconds')")
        .execute(&app.db)
        .await
        .unwrap();
    let before = stamp(&app).await;
    assert_eq!(post(vec![]).await.status, StatusCode::UNAUTHORIZED);
    assert_eq!(stamp(&app).await, before);
    // The refusal lists `refused` until an accepted push comes after it.
    let (device,): (i64,) = sqlx::query_as("SELECT device_id FROM up_registrations WHERE reg = ?")
        .bind(&reg)
        .fetch_one(&app.db)
        .await
        .unwrap();
    sqlx::query("UPDATE up_registrations SET last_push_at = datetime('now', '-1 hour')")
        .execute(&app.db)
        .await
        .unwrap();
    let listed = crate::unified_push::policy(&app.db, device, &[CAPABILITY.to_string()])
        .await
        .unwrap()
        .unwrap()
        .refresh;
    assert_eq!(listed.len(), 1);
    assert_eq!(
        (listed[0].reg.as_str(), listed[0].why),
        (reg.as_str(), "refused")
    );
    sqlx::query("UPDATE up_registrations SET last_push_at = datetime('now', '+1 minute')")
        .execute(&app.db)
        .await
        .unwrap();
    let again = crate::unified_push::policy(&app.db, device, &[CAPABILITY.to_string()])
        .await
        .unwrap()
        .unwrap();
    assert!(again.refresh.is_empty(), "a verified push ends it");
    // The gateway: a keyed registration's pushkey is rejected and stamped; an unkeyed one's push sets `gateway_at`.
    sqlx::query("UPDATE up_registrations SET vapid_refused_at = NULL")
        .execute(&app.db)
        .await
        .unwrap();
    let notify = |secret: &str| {
        json!({"notification": {"event_id": "$fake:example.org", "room_id": "!fake:example.org",
            "type": "m.room.message", "devices": [{"pushkey": format!("{}/up/{secret}", gateway["origin"].as_str().unwrap())}]}})
        .to_string()
        .into_bytes()
    };
    let live = gateway["live"].as_str().unwrap();
    let res = post_via_phone_listener(
        &app,
        "203.0.113.7",
        Method::POST,
        "/_matrix/push/v1/notify",
        &[],
        notify(live),
    )
    .await;
    assert_eq!(
        res.json(),
        json!({"rejected": [format!("{}/up/{live}", gateway["origin"].as_str().unwrap())]})
    );
    assert!(
        stamp(&app).await.is_some(),
        "the gateway's refusal is stamped (L9)"
    );
    sqlx::query("UPDATE up_registrations SET vapid = NULL")
        .execute(&app.db)
        .await
        .unwrap();
    let res = post_via_phone_listener(
        &app,
        "203.0.113.7",
        Method::POST,
        "/_matrix/push/v1/notify",
        &[],
        notify(live),
    )
    .await;
    assert_eq!(res.json(), json!({"rejected": []}));
    let marked: Option<String> =
        sqlx::query_scalar("SELECT gateway_at FROM up_registrations WHERE reg = ?")
            .bind(&reg)
            .fetch_one(&app.db)
            .await
            .unwrap();
    assert!(
        marked.is_some(),
        "a push through our gateway sets gateway_at"
    );
}

/// M5 and §12.2's rate rows end to end: 30 never-seen secrets from one client get it 429 for the rest - but a
/// resolving secret from that client still delivers and a tombstoned one is still 410; another client is untouched;
/// a phone's flooded registration gets 429 while its other one delivers (M3).
#[tokio::test]
async fn the_ban_and_the_buckets() {
    let gateway: Value = serde_json::from_str(GATEWAY).unwrap();
    let (app, emma) = gateway_app(&gateway).await;
    let post = |path: String, peer: &'static str| {
        let app = &app;
        async move { post_via_phone_listener(app, peer, Method::POST, &path, &[], b"x".to_vec()).await }
    };
    for n in 0..30 {
        let res = post(
            format!("/up/FakeSecretNeverIssuedHere{n:02}"),
            "203.0.113.9",
        )
        .await;
        assert_eq!(res.status, StatusCode::GONE, "{n}");
    }
    let res = post("/up/FakeSecretNeverIssuedHere99".into(), "203.0.113.9").await;
    assert_eq!(res.status, StatusCode::TOO_MANY_REQUESTS);
    let res = post(fill("/up/{live}", &gateway), "203.0.113.9").await;
    assert_eq!(
        res.status,
        StatusCode::CREATED,
        "a ban never blocks a resolving secret"
    );
    let res = post(fill("/up/{gone}", &gateway), "203.0.113.9").await;
    assert_eq!(
        res.status,
        StatusCode::GONE,
        "a tombstone is answered, never counted"
    );
    let res = post("/up/FakeSecretNeverIssuedHere98".into(), "203.0.113.10").await;
    assert_eq!(res.status, StatusCode::GONE, "another client");
    // M3: a second registration of the phone delivers while the first is flooded.
    sqlx::query(
        "INSERT INTO up_registrations (device_id, reg, secret_hash, package) VALUES (?, ?, ?, 'a.b')",
    )
    .bind(emma)
    .bind(format!("{:032x}", 3))
    .bind(crate::unified_push::secret_hash("FakeSecretForTheSecondReg01"))
    .execute(&app.db)
    .await
    .unwrap();
    let mut refused = 0;
    for _ in 0..50 {
        if post(fill("/up/{live}", &gateway), "198.51.100.1")
            .await
            .status
            == StatusCode::TOO_MANY_REQUESTS
        {
            refused += 1;
        }
    }
    assert!(
        refused >= 10,
        "the flooded registration's sub-bucket: {refused}"
    );
    // From another client: the flood's own request bucket (L3) may be empty by now.
    let res = post("/up/FakeSecretForTheSecondReg01".into(), "198.51.100.2").await;
    assert_eq!(res.status, StatusCode::CREATED, "the other app delivers");
}

// ---- The allowlist, the log, the switch -----------------------------------------------------------------------------

async fn seed(app: &TestApp, device: i64, n: u8, package: &str) -> String {
    let secret = format!("FakeSecretForThePruneTest{n:02}");
    sqlx::query(
        "INSERT INTO up_registrations (device_id, reg, secret_hash, package) VALUES (?, ?, ?, ?)",
    )
    .bind(device)
    .bind(format!("{n:032x}"))
    .bind(crate::unified_push::secret_hash(&secret))
    .bind(package)
    .execute(&app.db)
    .await
    .unwrap();
    secret
}

/// L10: an app switched off on Device › Apps loses its registrations (tombstoned, so its sender gets 410); the
/// others stay. The allowlist's first write (the bootstrap) prunes an unmanaged phone's too.
#[tokio::test]
async fn allowlist_save_removes() {
    use crate::unified_push::store::{Lookup, lookup};
    let app = TestApp::new().await;
    let (emma, token) = app.enrolled_device("Emma").await;
    // Unmanaged (NULL): any app may register.
    let kept = seed(&app, emma, 1, "com.google.android.deskclock").await;
    let other = seed(&app, emma, 2, "org.joinmastodon.android").await;
    app.post_status(
        &token,
        json!({"installed_apps": [{"package_name": "com.google.android.deskclock", "label": "Clock"},
            {"package_name": "org.joinmastodon.android", "label": "Mastodon"}]}),
    )
    .await;
    assert!(matches!(
        lookup(&app.db, &kept).await.unwrap(),
        Lookup::Live(_)
    ));
    assert_eq!(
        lookup(&app.db, &other).await.unwrap(),
        Lookup::Gone,
        "the bootstrap's list leaves it out"
    );
    let element = seed(&app, emma, 3, "io.element.android.x").await;
    crate::handlers::devices::add_to_allowlist(&app.state, emma, "io.element.android.x")
        .await
        .unwrap();
    assert!(matches!(
        lookup(&app.db, &element).await.unwrap(),
        Lookup::Live(_)
    ));
    crate::handlers::devices::remove_from_allowlist(&app.state, emma, "io.element.android.x")
        .await
        .unwrap();
    assert_eq!(lookup(&app.db, &element).await.unwrap(), Lookup::Gone);
    assert!(matches!(
        lookup(&app.db, &kept).await.unwrap(),
        Lookup::Live(_)
    ));
}

/// L10: every non-test `SET allowlist_json` and `INSERT INTO device_policy` sits in a function that calls
/// `unified_push::prune_disallowed`.
#[test]
fn every_allowlist_writer_prunes() {
    let mut writers = 0;
    let mut stack = vec![std::path::PathBuf::from(concat!(
        env!("CARGO_MANIFEST_DIR"),
        "/src"
    ))];
    while let Some(dir) = stack.pop() {
        for entry in std::fs::read_dir(&dir).unwrap() {
            let path = entry.unwrap().path();
            if path.is_dir() {
                if !path.ends_with("tests") {
                    stack.push(path);
                }
                continue;
            }
            if path.extension().is_none_or(|e| e != "rs") {
                continue;
            }
            let text = std::fs::read_to_string(&path).unwrap();
            let code = text.split("#[cfg(test)]").next().unwrap();
            for needle in ["SET allowlist_json", "INSERT INTO device_policy"] {
                let mut from = 0;
                while let Some(at) = code[from..].find(needle) {
                    let at = from + at;
                    from = at + needle.len();
                    let start = code[..at]
                        .rfind("\nasync fn ")
                        .max(code[..at].rfind("\npub async fn "))
                        .max(code[..at].rfind("\npub(crate) async fn "))
                        .unwrap_or_else(|| {
                            panic!("{}: a write outside a function", path.display())
                        });
                    let end = code[at..].find("\n}\n").map_or(code.len(), |e| at + e);
                    assert!(
                        code[start..end].contains("unified_push::prune_disallowed"),
                        "{}: the function writing `{needle}` doesn't prune app push",
                        path.display()
                    );
                    writers += 1;
                }
            }
        }
    }
    assert!(writers >= 3, "found only {writers} writers");
}

/// L5: a path under `/up/` never reaches the security log - the admin gate's refusal keeps `/up/…`.
#[tokio::test]
async fn security_log_redacts_up_paths() {
    let app = TestApp::new().await;
    let secret = "FakeSecretThatMustNotBeLog";
    let request = Request::builder()
        .method(Method::POST)
        .uri(format!("/up/{secret}"))
        .header("x-forwarded-for", "203.0.113.7")
        .body(Body::from("x"))
        .unwrap();
    let res = app.send(request).await;
    assert_eq!(res.status, StatusCode::FORBIDDEN);
    app.state.audit.flush(&app.db).await;
    let details: Vec<Option<String>> = sqlx::query_scalar("SELECT detail FROM security_events")
        .fetch_all(&app.db)
        .await
        .unwrap();
    let all = format!("{details:?}");
    assert!(all.contains("/up/…"), "{all}");
    assert!(!all.contains(secret), "{all}");
    assert_eq!(crate::audit::clip_path("/up/abc", 100), "/up/…");
    assert_eq!(crate::audit::clip_path("/updates", 100), "/updates");
}

/// Q3/A1: a phone made before any address has its switch on, and `on` stays false until the parent saves one; the
/// Phone Setup prefill never saves by itself. Off deletes the phone's registrations.
#[tokio::test]
async fn switch_on_by_default() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (emma, token) = app.enrolled_device("Emma").await;
    app.post_status(&token, json!({"capabilities": [CAPABILITY]}))
        .await;
    assert!(crate::unified_push::switch(&app.db, emma).await.unwrap());
    assert_eq!(app.policy(&token).await["unified_push"]["on"], false);
    sqlx::query(
        "UPDATE provisioning_settings SET server_url = 'https://phones.example.com/' WHERE id = 1",
    )
    .execute(&app.db)
    .await
    .unwrap();
    let page = app.get_page("/settings/push", &cookie).await;
    assert!(
        page.text().contains("value=\"https://phones.example.com\""),
        "prefilled"
    );
    assert_eq!(
        app.policy(&token).await["unified_push"]["on"],
        false,
        "never saved by itself"
    );
    let saved = app
        .request_form(
            Method::POST,
            "/settings/push",
            Some(&cookie),
            &[("public_origin", ORIGIN)],
        )
        .await;
    assert_eq!(saved.location(), Some("/settings/push?saved=1#saved"));
    app.assert_lands(&saved, &cookie).await;
    assert_eq!(app.policy(&token).await["unified_push"]["on"], true);
    // A refused address writes nothing.
    let refused = app
        .request_form(
            Method::POST,
            "/settings/push",
            Some(&cookie),
            &[("public_origin", "http://phones.example.com/x")],
        )
        .await;
    assert_eq!(refused.status, StatusCode::BAD_REQUEST);
    assert_eq!(
        crate::unified_push::settings(&app.db)
            .await
            .unwrap()
            .public_origin
            .as_deref(),
        Some(ORIGIN)
    );
    // The phone's switch off: its registrations go, and the policy says off.
    let secret = seed(&app, emma, 1, "io.element.android.x").await;
    let off = app
        .request_form(
            Method::POST,
            &format!("/devices/{emma}/app-push"),
            Some(&cookie),
            &[],
        )
        .await;
    assert_eq!(
        off.location(),
        Some(format!("/devices/{emma}/apps#app-push").as_str())
    );
    app.assert_lands(&off, &cookie).await;
    assert_eq!(app.policy(&token).await["unified_push"]["on"], false);
    assert_eq!(
        crate::unified_push::store::lookup(&app.db, &secret)
            .await
            .unwrap(),
        crate::unified_push::store::Lookup::Gone
    );
    let on = app
        .request_form(
            Method::POST,
            &format!("/devices/{emma}/app-push"),
            Some(&cookie),
            &[("on", "on")],
        )
        .await;
    assert_eq!(
        on.location(),
        Some(format!("/devices/{emma}/apps#app-push").as_str())
    );
    assert_eq!(app.policy(&token).await["unified_push"]["on"], true);
}

/// L6/L2/L3 (QA of R23-S), design 113: Device › Apps' group shows the outside-gateway line with the app's name behind
/// the row's ⓘ, "Pushes refused" with its cause behind the ⓘ (the sender's, or our clock, or our address), and
/// FluffyChat's own "open it" text. No "signed" line: the good state shows nothing.
#[tokio::test]
async fn the_apps_group_lines_of_108b() {
    let file: Value = serde_json::from_str(VAPID).unwrap();
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (emma, token) = app.enrolled_device("Emma").await;
    set_origin(&app, Some(ORIGIN)).await;
    app.post_status(
        &token,
        json!({"capabilities": [CAPABILITY], "installed_apps": [
            {"package_name": "chat.fluffy.fluffychat", "label": "FluffyChat"},
            {"package_name": "org.unifiedpush.example", "label": "UP-Example"}]}),
    )
    .await;
    set_allowlist(
        &app,
        emma,
        &["chat.fluffy.fluffychat", "org.unifiedpush.example"],
    )
    .await;
    seed(&app, emma, 1, "org.unifiedpush.example").await;
    let page = || async {
        app.get_page(&format!("/devices/{emma}/apps"), &cookie)
            .await
            .text()
    };
    let set = |sql: &'static str, binds: Vec<String>| {
        let db = app.db.clone();
        async move {
            let mut query = sqlx::query(sql);
            for bind in binds {
                query = query.bind(bind);
            }
            query.execute(&db).await.unwrap();
        }
    };
    let plain = page().await;
    assert!(!plain.contains("data-warning=\"outside\""));
    assert!(!plain.contains("data-warning=\"vapid-refused\""));
    assert!(
        plain.contains("Open FluffyChat once"),
        "FluffyChat's own text: {plain}"
    );
    set(
        "UPDATE up_registrations SET vapid = ?, outside_gateway_at = datetime('now'), \
         vapid_refused_at = datetime('now'), vapid_refused_why = NULL",
        vec![file["registered_key"].as_str().unwrap().to_string()],
    )
    .await;
    let marked = page().await;
    assert!(!marked.contains("data-row=\"signed\""));
    assert!(marked.contains("data-warning=\"outside\""));
    assert!(marked.contains("UP-Example&#39;s notifications go through an outside gateway"));
    assert!(marked.contains("Pushes refused"));
    assert!(marked.contains("wasn&#39;t signed by the app"));
    for (why, text) in [("time", "clock doesn"), ("address", "another address")] {
        set(
            "UPDATE up_registrations SET vapid_refused_why = ?",
            vec![why.to_string()],
        )
        .await;
        let marked = page().await;
        assert!(marked.contains("data-warning=\"vapid-refused\""));
        assert!(marked.contains(text), "{why}");
        assert!(marked.contains("Pushes refused"), "{why}");
        assert!(!marked.contains("wasn&#39;t signed"), "{why}");
    }
}

/// §3.10: Device › Apps' group - an app's row and Stop, the stopped list and Allow Again, the "not connected"
/// warning for an allowed Element X.
#[tokio::test]
async fn the_apps_group() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (emma, token) = app.enrolled_device("Emma").await;
    set_origin(&app, Some(ORIGIN)).await;
    app.post_status(
        &token,
        json!({"capabilities": [CAPABILITY], "installed_apps": [
            {"package_name": "io.element.android.x", "label": "Element X"},
            {"package_name": "org.unifiedpush.example", "label": "UP-Example"}]}),
    )
    .await;
    set_allowlist(
        &app,
        emma,
        &["io.element.android.x", "org.unifiedpush.example"],
    )
    .await;
    seed(&app, emma, 1, "org.unifiedpush.example").await;
    seed(&app, emma, 2, "org.unifiedpush.example").await;
    let page = app
        .get_page(&format!("/devices/{emma}/apps"), &cookie)
        .await
        .text();
    assert!(page.contains("id=\"app-push\""));
    assert!(page.contains("data-up-app=\"org.unifiedpush.example\""));
    assert!(page.contains("2 accounts"), "the row's line");
    assert!(
        page.contains("data-warning=\"up-missing\""),
        "Element X hasn't connected"
    );
    let stop = app
        .request_form(
            Method::POST,
            &format!("/devices/{emma}/app-push/stop"),
            Some(&cookie),
            &[("package", "org.unifiedpush.example")],
        )
        .await;
    assert_eq!(
        stop.location(),
        Some(format!("/devices/{emma}/apps#app-push").as_str())
    );
    let page = app
        .get_page(&format!("/devices/{emma}/apps"), &cookie)
        .await
        .text();
    assert!(page.contains("data-up-stopped=\"org.unifiedpush.example\""));
    assert!(!page.contains("data-up-app=\"org.unifiedpush.example\""));
    assert_eq!(
        app.policy(&token).await["unified_push"]["stopped"],
        json!(["org.unifiedpush.example"])
    );
    let allow = app
        .request_form(
            Method::POST,
            &format!("/devices/{emma}/app-push/allow"),
            Some(&cookie),
            &[("package", "org.unifiedpush.example")],
        )
        .await;
    assert_eq!(
        allow.location(),
        Some(format!("/devices/{emma}/apps#app-push").as_str())
    );
    assert_eq!(
        app.policy(&token).await["unified_push"]["stopped"],
        json!([])
    );
}

/// §3.5: nothing per message is logged - no secret, no `/up/` path, no body - at any level, through a registration,
/// a push to each route, a refused one and a pull.
#[tokio::test]
async fn nothing_logged() {
    super::music_spotify::install_capture();
    super::music_spotify::CAPTURE.with(|capture| *capture.borrow_mut() = Some(Vec::new()));
    let gateway: Value = serde_json::from_str(GATEWAY).unwrap();
    let (app, _) = gateway_app(&gateway).await;
    let (_, token) = app.enrolled_device("Jack").await;
    let secret = "FakeSecretForTheLogTest0001";
    let res = app
        .request(
            Method::POST,
            "/api/devices/up/registrations",
            Some(&token),
            Some(json!({"reg": "c0ffee000000000000000000000000aa", "secret": secret, "package": "a.b"})),
        )
        .await;
    assert_eq!(res.status, StatusCode::OK, "{}", res.text());
    let body = "a body that must not be logged";
    for (path, peer) in [
        (format!("/up/{secret}"), "203.0.113.7"),
        (fill("/up/{gone}", &gateway), "203.0.113.7"),
        (fill("/up/{unknown}", &gateway), "203.0.113.7"),
    ] {
        post_via_phone_listener(
            &app,
            peer,
            Method::POST,
            &path,
            &[],
            body.as_bytes().to_vec(),
        )
        .await;
    }
    let matrix = json!({"notification": {"devices": [{"pushkey": format!("{ORIGIN}/up/{secret}")}],
        "event_id": "$fakeEventThatMustNotBeLogged"}})
    .to_string();
    post_via_phone_listener(
        &app,
        "203.0.113.7",
        Method::POST,
        "/_matrix/push/v1/notify",
        &[],
        matrix.into_bytes(),
    )
    .await;
    app.request(
        Method::POST,
        "/api/devices/up/messages",
        Some(&token),
        Some(json!({"ack": []})),
    )
    .await;
    tokio::time::sleep(crate::unified_push::notify::WINDOW * 2).await;
    let lines = super::music_spotify::CAPTURE.with(|capture| capture.borrow_mut().take().unwrap());
    let all = lines.join("\n");
    for needle in [
        secret,
        gateway["gone"].as_str().unwrap(),
        gateway["unknown"].as_str().unwrap(),
        "/up/",
        body,
        "fakeEventThatMustNotBeLogged",
    ] {
        assert!(!all.contains(needle), "{needle} in the log:\n{all}");
    }
}

/// L7: a second change within 30 days keeps both replaced origins, each until its own end; clearing forgets them.
#[tokio::test]
async fn two_changes_keep_both_previous_origins() {
    let app = TestApp::new().await;
    for origin in [
        "https://a.example",
        "https://b.example",
        "https://c.example",
    ] {
        set_origin(&app, Some(origin)).await;
    }
    let mut accepted = crate::unified_push::accepted_origins(&app.db)
        .await
        .unwrap();
    assert_eq!(
        accepted.remove(0),
        "https://c.example",
        "the current one first"
    );
    accepted.sort();
    assert_eq!(accepted, ["https://a.example", "https://b.example"]);
    // Back to a: it is current again, never "previous" too.
    set_origin(&app, Some("https://a.example")).await;
    let accepted = crate::unified_push::accepted_origins(&app.db)
        .await
        .unwrap();
    assert_eq!(accepted.len(), 3, "{accepted:?}");
    assert_eq!(
        accepted
            .iter()
            .filter(|o| *o == "https://a.example")
            .count(),
        1
    );
    sqlx::query("UPDATE up_previous_origins SET until_at = datetime('now', '-1 minute') WHERE origin = 'https://b.example'")
        .execute(&app.db)
        .await
        .unwrap();
    let accepted = crate::unified_push::accepted_origins(&app.db)
        .await
        .unwrap();
    assert_eq!(accepted, ["https://a.example", "https://c.example"]);
    set_origin(&app, None).await;
    assert!(
        crate::unified_push::accepted_origins(&app.db)
            .await
            .unwrap()
            .is_empty()
    );
}

async fn rows_of(app: &TestApp, registration: i64) -> Vec<(String, i64)> {
    sqlx::query_as(
        "SELECT key, LENGTH(body) FROM up_messages WHERE registration_id = ? ORDER BY id",
    )
    .bind(registration)
    .fetch_all(&app.db)
    .await
    .unwrap()
}

async fn dropped_of(app: &TestApp, registration: i64) -> i64 {
    sqlx::query_scalar("SELECT dropped FROM up_registrations WHERE id = ?")
        .bind(registration)
        .fetch_one(&app.db)
        .await
        .unwrap()
}

async fn seed_message(app: &TestApp, registration: i64, device: i64, key: &str, size: i64) {
    let now = chrono::Utc::now().timestamp_millis();
    sqlx::query(
        "INSERT INTO up_messages (key, registration_id, device_id, body, urgency, created_at_ms, expires_at_ms) \
         VALUES (?, ?, ?, zeroblob(?), 'normal', ?, ?)",
    )
    .bind(key)
    .bind(registration)
    .bind(device)
    .bind(size)
    .bind(now)
    .bind(now + 3_600_000)
    .execute(&app.db)
    .await
    .unwrap();
}

/// M3 (R1-SR): the queue's caps and TTL 0 - a registration's 101st push drops its oldest (counted); a phone over
/// 1 MiB loses the oldest row of its registration with the most rows; a full store is 507; TTL 0 is dropped while
/// the phone's stream is closed and kept 30 s while it is open.
#[tokio::test]
async fn the_queue_caps_and_ttl_zero() {
    use crate::unified_push::store::{self, Push, Queued, Registration};
    let gateway: Value = serde_json::from_str(GATEWAY).unwrap();
    let (app, emma) = gateway_app(&gateway).await;
    let live = registration_id(&app, &format!("{:032x}", 1)).await;
    let registration = Registration {
        id: live,
        device_id: emma,
        reg: format!("{:032x}", 1),
        package: "io.element.android.x".to_string(),
        vapid: None,
        vapid_refused_at: None,
    };
    let now = chrono::Utc::now().timestamp_millis();
    let push = |body: &'static [u8]| Push {
        body,
        urgency: "normal",
        topic: None,
        ttl_secs: 60,
    };
    let mut first = None;
    for n in 0..101 {
        let Queued::Key(key) = store::enqueue(&app.db, &registration, &push(b"x"), now, None)
            .await
            .unwrap()
        else {
            panic!("{n}: refused");
        };
        first.get_or_insert(key);
    }
    let rows = rows_of(&app, live).await;
    assert_eq!(rows.len(), 100);
    assert!(
        rows.iter().all(|(key, _)| Some(key) != first.as_ref()),
        "the oldest went"
    );
    assert_eq!(dropped_of(&app, live).await, 1);
    sqlx::query("DELETE FROM up_messages")
        .execute(&app.db)
        .await
        .unwrap();
    sqlx::query("UPDATE up_registrations SET dropped = 0")
        .execute(&app.db)
        .await
        .unwrap();

    // Two registrations near 1 MiB: the one with more rows loses its oldest.
    sqlx::query("INSERT INTO up_registrations (device_id, reg, secret_hash, package) VALUES (?, ?, ?, 'a.b')")
        .bind(emma)
        .bind(format!("{:032x}", 7))
        .bind(crate::unified_push::secret_hash("FakeSecretForTheCapsTest007"))
        .execute(&app.db)
        .await
        .unwrap();
    let other = registration_id(&app, &format!("{:032x}", 7)).await;
    for n in 0..3 {
        seed_message(&app, other, emma, &format!("big{n}"), 300_000).await;
    }
    seed_message(&app, live, emma, "mid", 145_000).await;
    assert!(matches!(
        store::enqueue(&app.db, &registration, &push(&[7u8; 4096]), now, None)
            .await
            .unwrap(),
        Queued::Key(_)
    ));
    let big: Vec<String> = rows_of(&app, other)
        .await
        .into_iter()
        .map(|(k, _)| k)
        .collect();
    assert_eq!(big, ["big1", "big2"]);
    assert_eq!(dropped_of(&app, other).await, 1);
    assert_eq!(rows_of(&app, live).await.len(), 2, "its own rows stay");
    sqlx::query("DELETE FROM up_messages")
        .execute(&app.db)
        .await
        .unwrap();

    // The store full: 507 through the route.
    let (jack, _) = app.enrolled_device("Jack").await;
    sqlx::query("INSERT INTO up_registrations (device_id, reg, secret_hash, package) VALUES (?, ?, ?, 'a.b')")
        .bind(jack)
        .bind(format!("{:032x}", 8))
        .bind(crate::unified_push::secret_hash("FakeSecretForTheCapsTest008"))
        .execute(&app.db)
        .await
        .unwrap();
    let jacks = registration_id(&app, &format!("{:032x}", 8)).await;
    seed_message(&app, jacks, jack, "huge", store::STORE_BYTES).await;
    let res = post_via_phone_listener(
        &app,
        "203.0.113.7",
        Method::POST,
        &fill("/up/{live}", &gateway),
        &[],
        b"x".to_vec(),
    )
    .await;
    assert_eq!(res.status, StatusCode::INSUFFICIENT_STORAGE);
    sqlx::query("DELETE FROM up_messages")
        .execute(&app.db)
        .await
        .unwrap();

    // TTL 0: dropped with the stream closed (201, nothing stored), kept 30 s with it open.
    let res = post_via_phone_listener(
        &app,
        "203.0.113.7",
        Method::POST,
        &fill("/up/{live}", &gateway),
        &[("ttl", "0")],
        b"x".to_vec(),
    )
    .await;
    assert_eq!(res.status, StatusCode::CREATED);
    assert_eq!(res.headers["ttl"], "0");
    assert!(rows_of(&app, live).await.is_empty());
    let _stream = app.state.command_streams.open(emma);
    let res = post_via_phone_listener(
        &app,
        "203.0.113.7",
        Method::POST,
        &fill("/up/{live}", &gateway),
        &[("ttl", "0")],
        b"x".to_vec(),
    )
    .await;
    assert_eq!(res.status, StatusCode::CREATED);
    let (created, expires): (i64, i64) = sqlx::query_as(
        "SELECT created_at_ms, expires_at_ms FROM up_messages WHERE registration_id = ?",
    )
    .bind(live)
    .fetch_one(&app.db)
    .await
    .unwrap();
    assert_eq!(expires - created, store::TTL_ZERO_GRACE_SECS * 1000);
}

// ---- Design 108c: app push on Attention, and the delivery time -----------------------------------------------------

/// The app push rows (`data-attention` keys starting `app-push-`) on the phone's summary.
async fn push_rows(app: &TestApp, cookie: &str, id: i64) -> Vec<String> {
    let page = app.get_page(&format!("/devices/{id}"), cookie).await.text();
    super::tags(&page, "a")
        .into_iter()
        .filter_map(|tag| tag.attr("data-attention").map(str::to_string))
        .filter(|key| key.starts_with("app-push-"))
        .collect()
}

/// The Devices list's red number for the phone; 0 without one.
async fn list_badge(app: &TestApp, cookie: &str, id: i64) -> usize {
    let list = app.get_page("/devices", cookie).await.text();
    let start = list.find(&format!("data-device=\"{id}\"")).unwrap();
    let row = &list[start..];
    let row = &row[..row.find("</a>").unwrap()];
    super::tags(row, "span")
        .into_iter()
        .find_map(|tag| tag.attr("data-attention-count"))
        .map_or(0, |n| n.parse().unwrap())
}

async fn sql(app: &TestApp, query: &str) {
    sqlx::query(query).execute(&app.db).await.unwrap();
}

async fn registration_of(app: &TestApp, id: i64) -> crate::unified_push::store::Registration {
    sqlx::query_as(
        "SELECT id, device_id, reg, package, vapid, vapid_refused_at FROM up_registrations WHERE id = ?",
    )
    .bind(id)
    .fetch_one(&app.db)
    .await
    .unwrap()
}

async fn since_of(app: &TestApp, id: i64) -> Option<String> {
    sqlx::query_scalar("SELECT vapid_refused_since FROM up_registrations WHERE id = ?")
        .bind(id)
        .fetch_one(&app.db)
        .await
        .unwrap()
}

/// A `SELECT <condition> …` of one registration, as a bool.
async fn flag(app: &TestApp, query: &str, id: i64) -> bool {
    sqlx::query_scalar::<_, i64>(query)
        .bind(id)
        .fetch_one(&app.db)
        .await
        .unwrap()
        == 1
}

/// §1, §4: the three Attention rows at their thresholds, every column written through its real path (the stamps
/// through `note_refusal`, an accepted push, the publish route, the key update's route, `pull` and the policy
/// served), the page lines, and one count for the summary and the Devices list.
#[tokio::test]
async fn attention_rows_of_108c() {
    use crate::unified_push::store::{self, Push};
    let vapid: Value = serde_json::from_str(VAPID).unwrap();
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (emma, token) = app.enrolled_device("Emma").await;
    set_origin(&app, Some(ORIGIN)).await;
    app.post_status(&token, json!({"capabilities": [CAPABILITY]}))
        .await;
    set_allowlist(&app, emma, &["io.element.android.x"]).await;
    let secret = seed(&app, emma, 1, "io.element.android.x").await;
    let reg = registration_id(&app, &format!("{:032x}", 1)).await;
    let push = Push {
        body: b"x",
        urgency: "normal",
        topic: None,
        ttl_secs: 86_400,
    };
    let baseline = list_badge(&app, &cookie, emma).await;
    assert!(push_rows(&app, &cookie, emma).await.is_empty());

    // -- refused: an `unsigned` run of a day, the phone in touch a day in.
    let refuse = |why: &'static str| {
        let app = &app;
        async move {
            // The stamp is written at most once a minute: backdate it so the next one is due.
            sql(app, "UPDATE up_registrations SET vapid_refused_at = datetime(vapid_refused_at, '-2 minutes') WHERE vapid_refused_at IS NOT NULL").await;
            store::note_refusal(&app.db, &registration_of(app, reg).await, why).await;
        }
    };
    refuse("unsigned").await;
    assert!(since_of(&app, reg).await.is_some());
    assert!(
        push_rows(&app, &cookie, emma).await.is_empty(),
        "a single refusal"
    );
    sql(
        &app,
        "UPDATE up_registrations SET vapid_refused_since = datetime('now', '-30 hours')",
    )
    .await;
    assert_eq!(push_rows(&app, &cookie, emma).await, ["app-push-refused"]);
    assert_eq!(
        list_badge(&app, &cookie, emma).await,
        baseline + 1,
        "one count"
    );
    let apps = app
        .get_page(&format!("/devices/{emma}/apps"), &cookie)
        .await
        .text();
    assert!(apps.contains("This has gone on for over a day"), "{apps}");
    // The phone's report must come a day into the run.
    sql(
        &app,
        "UPDATE device_status SET reported_at = datetime('now', '-10 hours')",
    )
    .await;
    assert!(
        push_rows(&app, &cookie, emma).await.is_empty(),
        "no report a day in"
    );
    sql(
        &app,
        "UPDATE device_status SET reported_at = datetime('now')",
    )
    .await;
    // A `time` stamp never raises, never extends, and ends the run: the next `unsigned` starts a new one.
    refuse("time").await;
    assert!(
        push_rows(&app, &cookie, emma).await.is_empty(),
        "a time run"
    );
    let apps = app
        .get_page(&format!("/devices/{emma}/apps"), &cookie)
        .await
        .text();
    assert!(
        !apps.contains("over a day"),
        "the sentence never lands on a time line"
    );
    refuse("unsigned").await;
    assert!(
        push_rows(&app, &cookie, emma).await.is_empty(),
        "broken by a time stamp"
    );
    // A run is ended by an accepted push, by the key update through the register route, and by a TTL-0 Dropped.
    let make_run = || async {
        sql(&app, "UPDATE up_registrations SET vapid_refused_since = datetime('now', '-30 hours'), vapid_refused_why = 'unsigned', vapid_refused_at = datetime('now', '-3 minutes')").await;
        assert_eq!(push_rows(&app, &cookie, emma).await, ["app-push-refused"]);
    };
    make_run().await;
    store::enqueue(
        &app.db,
        &registration_of(&app, reg).await,
        &push,
        chrono::Utc::now().timestamp_millis(),
        None,
    )
    .await
    .unwrap();
    assert!(
        since_of(&app, reg).await.is_none(),
        "an accepted push ends the run"
    );
    sql(&app, "DELETE FROM up_messages").await;
    make_run().await;
    let res = answer(
        &app,
        Method::POST,
        "/api/devices/up/registrations",
        &token,
        Some(json!({"reg": format!("{:032x}", 1), "secret": secret,
            "package": "io.element.android.x", "vapid": vapid["registered_key"]})),
    )
    .await;
    assert_eq!(res["status"], 200, "{res}");
    assert!(
        since_of(&app, reg).await.is_none(),
        "the key update ends the run"
    );
    make_run().await;
    let dropped = Push {
        ttl_secs: 0,
        ..push.clone()
    };
    let taken =
        crate::unified_push::accept(&app.state, &registration_of(&app, reg).await, &dropped)
            .await
            .ok();
    assert!(matches!(
        taken,
        Some(crate::unified_push::Accepted::Dropped)
    ));
    assert!(
        since_of(&app, reg).await.is_none(),
        "a verified Dropped push ends the run"
    );
    // 7 quiet days: the next refusal starts a new run.
    sql(&app, "UPDATE up_registrations SET vapid_refused_since = datetime('now', '-9 days'), vapid_refused_at = datetime('now', '-8 days'), vapid_refused_why = 'unsigned'").await;
    assert!(push_rows(&app, &cookie, emma).await.is_empty());
    refuse("unsigned").await;
    assert!(flag(&app, "SELECT datetime(vapid_refused_since) > datetime('now', '-1 minute') FROM up_registrations WHERE id = ?", reg).await);
    // The key update made the registration keyed; the outside pushes below are unsigned ones from a gateway.
    sql(&app, "UPDATE up_registrations SET vapid = NULL, vapid_refused_since = NULL, vapid_refused_at = NULL, vapid_refused_why = NULL").await;

    // -- outside: an outside push a day or more into the run.
    let outside_body =
        json!({"notification": {"event_id": "$fake:example.org", "room_id": "!fake:example.org",
        "type": "m.room.message", "devices": [{"app_id": "io.element.android.x", "pushkey": "x"}]}})
        .to_string()
        .into_bytes();
    let path = format!("/up/{secret}");
    let outside_push = || async {
        let res = post_via_phone_listener(
            &app,
            "203.0.113.7",
            Method::POST,
            &path,
            &[],
            outside_body.clone(),
        )
        .await;
        assert_eq!(res.status, StatusCode::CREATED, "{}", res.text());
    };
    outside_push().await;
    assert!(
        push_rows(&app, &cookie, emma).await.is_empty(),
        "the run's first push"
    );
    // Its first hour only, three days later: a healed, quiet app (H1).
    sql(&app, "UPDATE up_registrations SET outside_since = datetime('now', '-3 days'), outside_gateway_at = datetime('now', '-3 days', '+1 hour')").await;
    assert!(
        push_rows(&app, &cookie, emma).await.is_empty(),
        "H1: the mark alone proves nothing"
    );
    // An outside push 30 hours into the run.
    sql(&app, "UPDATE up_registrations SET outside_since = datetime('now', '-30 hours'), outside_gateway_at = datetime('now', '-3 minutes')").await;
    outside_push().await;
    assert_eq!(push_rows(&app, &cookie, emma).await, ["app-push-outside"]);
    assert_eq!(list_badge(&app, &cookie, emma).await, baseline + 1);
    // A push through our own gateway ends the mark; the next outside push starts a new run.
    let notify = json!({"notification": {"event_id": "$fake:example.org", "room_id": "!fake:example.org",
        "type": "m.room.message", "devices": [{"pushkey": format!("{ORIGIN}/up/{secret}")}]}});
    let res = post_via_phone_listener(
        &app,
        "203.0.113.7",
        Method::POST,
        "/_matrix/push/v1/notify",
        &[],
        notify.to_string().into_bytes(),
    )
    .await;
    assert_eq!(res.json(), json!({"rejected": []}));
    assert!(
        push_rows(&app, &cookie, emma).await.is_empty(),
        "our own gateway ends it"
    );
    sql(&app, "UPDATE up_registrations SET outside_gateway_at = datetime('now', '-3 minutes'), gateway_at = datetime('now', '-2 minutes')").await;
    outside_push().await;
    assert!(
        flag(&app, "SELECT datetime(outside_since) > datetime('now', '-1 minute') FROM up_registrations WHERE id = ?", reg).await,
        "the run restarted"
    );
    assert!(push_rows(&app, &cookie, emma).await.is_empty());
    // N2: our own gateway's push inside the minute's throttle still ends an outside mark newer than its stamp.
    sql(&app, "UPDATE up_registrations SET outside_gateway_at = datetime('now', '-5 seconds'), gateway_at = datetime('now', '-20 seconds')").await;
    let res = post_via_phone_listener(
        &app,
        "203.0.113.7",
        Method::POST,
        "/_matrix/push/v1/notify",
        &[],
        notify.to_string().into_bytes(),
    )
    .await;
    assert_eq!(res.json(), json!({"rejected": []}));
    assert!(
        flag(
            &app,
            "SELECT gateway_at >= outside_gateway_at FROM up_registrations WHERE id = ?",
            reg
        )
        .await,
        "N2: a gateway push within the minute ends a newer outside mark"
    );
    sql(&app, "DELETE FROM up_messages").await;

    // -- waiting: the oldest push 40 minutes old.
    let now = chrono::Utc::now();
    let at = |minutes: i64| now + chrono::Duration::minutes(minutes);
    let queue = || async {
        sql(&app, "DELETE FROM up_messages").await;
        let Ok(store::Queued::Key(key)) = store::enqueue(
            &app.db,
            &registration_of(&app, reg).await,
            &push,
            at(-40).timestamp_millis(),
            Some(7),
        )
        .await
        else {
            panic!("not queued")
        };
        key
    };
    let served = &app.state.unified_push;
    let key = queue().await;
    for minutes in [-25, -15] {
        served.note_policy_served_at(emma, at(minutes));
    }
    assert!(
        push_rows(&app, &cookie, emma).await.is_empty(),
        "two served policies"
    );
    served.note_policy_served_at(emma, at(-5));
    assert_eq!(push_rows(&app, &cookie, emma).await, ["app-push-waiting"]);
    let apps = app
        .get_page(&format!("/devices/{emma}/apps"), &cookie)
        .await
        .text();
    assert!(apps.contains("data-warning=\"up-waiting\""));
    assert_eq!(list_badge(&app, &cookie, emma).await, baseline + 1);
    // A not-ok report in the window.
    sql(&app, "UPDATE device_status SET policy_state = 'error', reported_at = datetime('now', '-20 minutes')").await;
    app.post_status(&token, json!({"capabilities": [CAPABILITY]}))
        .await;
    assert!(
        push_rows(&app, &cookie, emma).await.is_empty(),
        "a report that couldn't apply the policy"
    );
    sql(
        &app,
        "DELETE FROM device_status WHERE policy_state = 'error'",
    )
    .await;
    assert_eq!(push_rows(&app, &cookie, emma).await, ["app-push-waiting"]);
    // The ack ends it.
    store::pull(&app.db, emma, &[key], now.timestamp_millis(), None)
        .await
        .unwrap();
    assert!(
        push_rows(&app, &cookie, emma).await.is_empty(),
        "acknowledged"
    );
    // Three policies, all in the push's first ten minutes: not counted.
    let key = queue().await;
    let _ = key;
    for minutes in [-39, -37, -35] {
        served.note_policy_served_at(emma, at(minutes));
    }
    assert!(
        push_rows(&app, &cookie, emma).await.is_empty(),
        "inside the first ten minutes"
    );
    // (b) fetched, handed out again 3 minutes later: raised; 30 s later: not.
    let _ = queue().await;
    store::pull(&app.db, emma, &[], at(-10).timestamp_millis(), Some(7))
        .await
        .unwrap();
    store::pull(
        &app.db,
        emma,
        &[],
        (at(-10) + chrono::Duration::seconds(30)).timestamp_millis(),
        Some(7),
    )
    .await
    .unwrap();
    assert!(
        push_rows(&app, &cookie, emma).await.is_empty(),
        "30 s after the first hand-out"
    );
    // A re-hand-out 30 s ago: the phone has had no time to acknowledge it (a catch-up after a lost ack).
    let thirty_ago = (at(0) - chrono::Duration::seconds(30)).timestamp_millis();
    store::pull(&app.db, emma, &[], thirty_ago, Some(7))
        .await
        .unwrap();
    assert!(
        push_rows(&app, &cookie, emma).await.is_empty(),
        "a hand-out 30 s ago"
    );
    store::pull(&app.db, emma, &[], at(-7).timestamp_millis(), Some(7))
        .await
        .unwrap();
    assert_eq!(push_rows(&app, &cookie, emma).await, ["app-push-waiting"]);
    // The 30-minute floor: a push 20 minutes old with three served policies in its window isn't waiting yet.
    sql(&app, "DELETE FROM up_messages").await;
    store::enqueue(
        &app.db,
        &registration_of(&app, reg).await,
        &push,
        at(-20).timestamp_millis(),
        Some(7),
    )
    .await
    .unwrap();
    for minutes in [-9, -6, -3] {
        served.note_policy_served_at(emma, at(minutes));
    }
    assert!(
        push_rows(&app, &cookie, emma).await.is_empty(),
        "under 30 minutes old"
    );
}

/// §1.1: a policy the phone took (200, and 304 on `If-None-Match`) is noted by the route, never by anything else.
#[tokio::test]
async fn the_policy_route_notes_served_policies() {
    let app = TestApp::new().await;
    let (emma, token) = app.enrolled_device("Emma").await;
    let served = || {
        app.state.unified_push.policies_served(
            emma,
            chrono::Utc::now() - chrono::Duration::minutes(5),
            chrono::Utc::now() + chrono::Duration::minutes(5),
        )
    };
    assert_eq!(served(), 0);
    let get = |tag: Option<String>| {
        let mut request = Request::builder()
            .method(Method::GET)
            .uri("/api/devices/policy")
            .header("authorization", format!("Bearer {token}"));
        if let Some(tag) = tag {
            request = request.header("if-none-match", tag);
        }
        app.send(request.body(Body::empty()).unwrap())
    };
    let first = get(None).await;
    assert_eq!(first.status, StatusCode::OK);
    assert_eq!(served(), 1);
    let tag = first.headers["etag"].to_str().unwrap().to_string();
    assert_eq!(get(Some(tag)).await.status, StatusCode::NOT_MODIFIED);
    assert_eq!(served(), 2);
}

/// §7.1: `accept` stores the phone's stream generation, `NULL` without a stream.
#[tokio::test]
async fn accept_stores_the_stream_generation() {
    let gateway: Value = serde_json::from_str(GATEWAY).unwrap();
    let (app, emma) = gateway_app(&gateway).await;
    let live = registration_id(&app, &format!("{:032x}", 1)).await;
    let post = || async {
        let res = post_via_phone_listener(
            &app,
            "203.0.113.7",
            Method::POST,
            &fill("/up/{live}", &gateway),
            &[],
            b"x".to_vec(),
        )
        .await;
        assert_eq!(res.status, StatusCode::CREATED);
    };
    post().await;
    let _stream = app.state.command_streams.open(emma);
    post().await;
    let streams: Vec<Option<i64>> = sqlx::query_scalar(
        "SELECT stream_ms FROM up_messages WHERE registration_id = ? ORDER BY id",
    )
    .bind(live)
    .fetch_all(&app.db)
    .await
    .unwrap();
    assert_eq!(streams.len(), 2);
    assert_eq!(streams[0], None);
    assert_eq!(
        streams[1],
        Some(
            app.state
                .command_streams
                .state(emma)
                .unwrap()
                .since
                .timestamp_millis()
        )
    );
}

/// §7.4, §7.5, designs 112/113: Settings › App push's "Delivery" group and Device › Apps' line, from seeded samples.
#[tokio::test]
async fn delivery_time_on_the_pages() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (emma, token) = app.enrolled_device("Emma").await;
    let (jack, _) = app.enrolled_device("Jack").await;
    app.post_status(&token, json!({"capabilities": [CAPABILITY]}))
        .await;
    set_origin(&app, Some(ORIGIN)).await;
    let settings = app.get_page("/settings/push", &cookie).await.text();
    assert!(
        settings.contains("data-row=\"delivery-none\""),
        "{settings}"
    );
    let now = chrono::Utc::now().timestamp_millis();
    for ms in [1_000, 2_000, 3_000] {
        sqlx::query("INSERT INTO up_delivery (device_id, acked_ms, ms) VALUES (?, ?, ?)")
            .bind(emma)
            .bind(now)
            .bind(ms)
            .execute(&app.db)
            .await
            .unwrap();
    }
    let settings = app.get_page("/settings/push", &cookie).await.text();
    assert!(settings.contains(">Delivery<"));
    assert!(settings.contains("data-row=\"delivery-usually\""));
    assert!(!settings.contains("95% within"), "no 95% below 20");
    assert!(settings.contains("3 pushes in the last 24 hours"));
    let line = "Usually delivered in 2 s · 3 pushes in the last 24 hours";
    let page = app
        .get_page(&format!("/devices/{emma}/apps"), &cookie)
        .await
        .text();
    assert!(page.contains(line), "{page}");
    let other = app
        .get_page(&format!("/devices/{jack}/apps"), &cookie)
        .await
        .text();
    assert!(!other.contains("data-row=\"app-push-delivery\""));
}

/// Design 113: Settings › App push's "Last seen from outside" says "Not yet" in orange only when a registration is
/// over an hour old and nothing has reached the address; a younger one, or a push since, keeps it quiet.
#[tokio::test]
async fn seen_late_on_settings() {
    const LATE: &str = "<span class=\"row-value row-value-action\">Not yet</span>";
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (emma, _) = app.enrolled_device("Emma").await;
    set_origin(&app, Some(ORIGIN)).await;
    let page = || async { app.get_page("/settings/push", &cookie).await.text() };
    let none = page().await;
    assert!(none.contains("data-row=\"seen\""), "{none}");
    assert!(!none.contains(LATE), "no registration: {none}");
    seed(&app, emma, 1, "org.unifiedpush.example").await;
    sql(
        &app,
        "UPDATE up_registrations SET created_at = datetime('now', '-10 minutes')",
    )
    .await;
    let young = page().await;
    assert!(young.contains(">Not yet<"), "{young}");
    assert!(!young.contains(LATE), "ten minutes old: {young}");
    sql(
        &app,
        "UPDATE up_registrations SET created_at = datetime('now', '-2 hours')",
    )
    .await;
    assert!(page().await.contains(LATE), "two hours old");
    sql(
        &app,
        "UPDATE up_registrations SET last_push_at = datetime('now', '-5 minutes')",
    )
    .await;
    let seen = page().await;
    assert!(!seen.contains(LATE), "a push reached it: {seen}");
    assert!(!seen.contains(">Not yet<"), "{seen}");
}

// ---- Design 115: app push held while the apps have no network ------------------------------------------------------

/// The `mobile_data` a held phone reports beside `unified_push` (design 95 rev. 3's sink reason).
fn mobile_data(sink: &str) -> Value {
    json!({"enabled": true, "sink": sink, "settings_locked": false})
}

async fn up_held_latest(app: &TestApp, device: i64) -> Option<String> {
    sqlx::query_scalar(
        "SELECT up_held FROM device_status WHERE device_id = ? ORDER BY reported_at DESC, id DESC LIMIT 1",
    )
    .bind(device)
    .fetch_one(&app.db)
    .await
    .unwrap()
}

async fn delivery_samples(app: &TestApp, device: i64) -> i64 {
    sqlx::query_scalar("SELECT COUNT(*) FROM up_delivery WHERE device_id = ?")
        .bind(device)
        .fetch_one(&app.db)
        .await
        .unwrap()
}

/// §2.8 and §2.5: the contract's report rows stored as `up_held`, every `clean` row, a `held` pull that leaves no
/// delivery sample while the next plain one does, and a held report carried through a heartbeat (QA L7).
#[tokio::test]
async fn the_contract_of_115() {
    use crate::unified_push::store::{self, Push};
    let contract = contract();
    for row in contract["clean"].as_array().unwrap() {
        assert_eq!(
            crate::unified_push::clean_report(&row["in"]),
            row["stored"].as_str().map(str::to_string),
            "{row}"
        );
    }
    let app = TestApp::new().await;
    let (emma, token) = app.enrolled_device("Emma").await;
    set_origin(&app, Some(ORIGIN)).await;
    let report = |key: &str| {
        let mut body = contract[key].clone();
        body["capabilities"] = json!([CAPABILITY]);
        body["mobile_data"] = mobile_data("parent");
        body
    };
    app.post_status(&token, report("report")).await;
    assert_eq!(
        up_held_latest(&app, emma).await.as_deref(),
        Some("no_network")
    );
    app.post_status(&token, report("report_open")).await;
    assert_eq!(up_held_latest(&app, emma).await, None);
    app.post_status(&token, json!({"capabilities": [CAPABILITY]}))
        .await;
    assert_eq!(up_held_latest(&app, emma).await, None, "an older launcher");

    // The held pull: a push accepted on the open stream is handed out with no generation, so its ack writes no sample.
    set_allowlist(&app, emma, &["io.element.android.x"]).await;
    seed(&app, emma, 1, "io.element.android.x").await;
    let reg = registration_of(&app, registration_id(&app, &format!("{:032x}", 1)).await).await;
    let _stream = app.state.command_streams.open(emma);
    let generation = crate::unified_push::stream_generation(&app.state, emma);
    assert!(generation.is_some());
    let push = Push {
        body: b"x",
        urgency: "normal",
        topic: None,
        ttl_secs: 86_400,
    };
    let enqueue = || async {
        let Ok(store::Queued::Key(key)) = store::enqueue(
            &app.db,
            &reg,
            &push,
            chrono::Utc::now().timestamp_millis() - 60_000,
            generation,
        )
        .await
        else {
            panic!("not queued")
        };
        key
    };
    let path = contract["messages"]["path"].as_str().unwrap();
    let key = enqueue().await;
    let res = app
        .request(
            Method::POST,
            path,
            Some(&token),
            Some(contract["messages"]["body_held"].clone()),
        )
        .await;
    assert_eq!(res.status, StatusCode::OK, "{}", res.text());
    assert_eq!(res.json()["messages"][0]["key"], json!(key));
    let res = app
        .request(
            Method::POST,
            path,
            Some(&token),
            Some(json!({"ack": [key]})),
        )
        .await;
    assert_eq!(res.json(), json!({"messages": [], "more": false}));
    assert_eq!(delivery_samples(&app, emma).await, 0, "a held pull's push");
    // The next plain pull's push leaves its sample.
    let key = enqueue().await;
    let res = app
        .request(Method::POST, path, Some(&token), Some(json!({"ack": []})))
        .await;
    assert_eq!(res.json()["messages"][0]["key"], json!(key));
    app.request(
        Method::POST,
        path,
        Some(&token),
        Some(json!({"ack": [key]})),
    )
    .await;
    assert_eq!(delivery_samples(&app, emma).await, 1, "a plain pull's push");

    // A heartbeat expands into the kept held report (QA L7).
    let heartbeat: Value =
        serde_json::from_str(include_str!("../../testdata/status_heartbeat.json")).unwrap();
    let mut full = heartbeat["full"].clone();
    full["unified_push"] = contract["report"]["unified_push"].clone();
    full["mobile_data"] = mobile_data("parent");
    let res = app
        .request(
            Method::POST,
            "/api/devices/status",
            Some(&token),
            Some(full),
        )
        .await;
    assert_eq!(res.status, StatusCode::NO_CONTENT, "{}", res.text());
    let res = app
        .request(
            Method::POST,
            "/api/devices/status",
            Some(&token),
            Some(heartbeat["heartbeat"].clone()),
        )
        .await;
    assert_eq!(res.status, StatusCode::NO_CONTENT, "{}", res.text());
    let rows: Vec<(Option<String>, Option<String>)> = sqlx::query_as(
        "SELECT up_held, lock_reason FROM device_status WHERE device_id = ? ORDER BY id DESC LIMIT 2",
    )
    .bind(emma)
    .fetch_all(&app.db)
    .await
    .unwrap();
    assert_eq!(
        rows,
        [
            (Some("no_network".into()), Some("TIME_RULE".into())),
            (Some("no_network".into()), Some("NONE".into()))
        ]
    );
}

/// §2.8: 108c's arm (a) counts only the policies served after the last held report - and a held report counts only
/// with a sink in the same report (QA M2).
#[tokio::test]
async fn a_hold_quiets_the_waiting_row() {
    use crate::unified_push::store::{self, Push};
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (emma, token) = app.enrolled_device("Emma").await;
    set_origin(&app, Some(ORIGIN)).await;
    app.post_status(&token, json!({"capabilities": [CAPABILITY]}))
        .await;
    set_allowlist(&app, emma, &["io.element.android.x"]).await;
    seed(&app, emma, 1, "io.element.android.x").await;
    let reg = registration_of(&app, registration_id(&app, &format!("{:032x}", 1)).await).await;
    let now = chrono::Utc::now();
    let at = |minutes: i64| now + chrono::Duration::minutes(minutes);
    let push = Push {
        body: b"x",
        urgency: "normal",
        topic: None,
        ttl_secs: 86_400,
    };
    store::enqueue(&app.db, &reg, &push, at(-40).timestamp_millis(), Some(7))
        .await
        .unwrap();
    let served = &app.state.unified_push;
    let reports = |rows: &'static [(i64, &'static str)]| {
        let (app, token) = (&app, &token);
        async move {
            sql(app, "DELETE FROM device_status").await;
            for (minutes_ago, sink) in rows {
                app.post_status(
                    token,
                    json!({"capabilities": [CAPABILITY], "unified_push": {"held": "no_network"},
                        "mobile_data": mobile_data(sink)}),
                )
                .await;
                sql(
                    app,
                    &format!(
                        "UPDATE device_status SET reported_at = datetime('now', '-{minutes_ago} minutes') \
                         WHERE id = (SELECT MAX(id) FROM device_status)"
                    ),
                )
                .await;
            }
            // The latest report (the page's own read) says nothing of the hold.
            app.post_status(token, json!({"capabilities": [CAPABILITY]}))
                .await;
        }
    };
    for minutes in [-25, -15, -5] {
        served.note_policy_served_at(emma, at(minutes));
    }
    reports(&[]).await;
    assert_eq!(
        push_rows(&app, &cookie, emma).await,
        ["app-push-waiting"],
        "no hold"
    );
    // Each served policy followed by a held report: nothing counted.
    reports(&[(24, "parent"), (14, "parent"), (4, "kid")]).await;
    assert!(
        push_rows(&app, &cookie, emma).await.is_empty(),
        "held throughout"
    );
    // A held report, then three policies after it: counted.
    reports(&[(28, "parent")]).await;
    assert_eq!(
        push_rows(&app, &cookie, emma).await,
        ["app-push-waiting"],
        "the hold ended"
    );
    // A held report before C + 10 min changes nothing.
    reports(&[(35, "parent")]).await;
    assert_eq!(
        push_rows(&app, &cookie, emma).await,
        ["app-push-waiting"],
        "too early"
    );
    // Held reports with no sink in the same report: the phone's word alone doesn't count (QA M2).
    reports(&[(24, "off"), (14, "off"), (4, "off")]).await;
    assert_eq!(
        push_rows(&app, &cookie, emma).await,
        ["app-push-waiting"],
        "no sink"
    );
}
