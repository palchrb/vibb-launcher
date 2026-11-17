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
        let Queued::Key(key) = store::enqueue(&app.db, &registration, &push(b"x"), now)
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
        store::enqueue(&app.db, &registration, &push(&[7u8; 4096]), now)
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
