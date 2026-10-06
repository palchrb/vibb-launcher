//! Handy step 7: FCM nudges (sender, health, status/policy wiring), Play as an app source and the
//! DNS exceptions for FCM.

use std::sync::{Arc, Mutex};
use std::time::Duration;

use axum::http::{Method, StatusCode};
use axum::response::IntoResponse;
use serde_json::json;

use super::TestApp;
use crate::fcm::testing::FakeSender;
use crate::fcm::{
    FcmErrorClass, FcmSender, HttpFcmConfig, HttpFcmSender, SendOutcome, Target, TargetKind,
    classify_fcm_error, load_service_account, parse_service_account, token_hash,
};

const FIXTURE_KEY: &str = include_str!("../../testdata/fcm_test_fixture_key.pem");

fn service_account_json(token_uri: &str) -> String {
    json!({
        "type": "service_account",
        "project_id": "handy-test",
        "client_email": "fcm-sender@handy-test.iam.gserviceaccount.com",
        "private_key": FIXTURE_KEY,
        "token_uri": token_uri,
    })
    .to_string()
}

async fn app_with_fake() -> (TestApp, Arc<FakeSender>) {
    let fake = Arc::new(FakeSender::default());
    let app = TestApp::with_fcm(Some(fake.clone() as crate::fcm::SharedSender)).await;
    (app, fake)
}

async fn post_status(app: &TestApp, token: &str, extra: serde_json::Value) {
    let mut report = json!({ "lock_reason": "none", "kiosk_engaged": true });
    for (key, value) in extra.as_object().unwrap() {
        report[key] = value.clone();
    }
    let res = app
        .request(
            Method::POST,
            "/api/devices/status",
            Some(token),
            Some(report),
        )
        .await;
    assert_eq!(res.status, StatusCode::NO_CONTENT);
}

async fn policy_push(app: &TestApp, token: &str) -> serde_json::Value {
    let res = app
        .request(Method::GET, "/api/devices/policy", Some(token), None)
        .await;
    assert_eq!(res.status, StatusCode::OK);
    res.json()["push"].clone()
}

/// Waits (real time, bounded) until the fake has at least `n` sends.
async fn wait_for_sends(fake: &FakeSender, n: usize) -> Vec<(String, String)> {
    for _ in 0..200 {
        let sends = fake.sends();
        if sends.len() >= n {
            return sends;
        }
        tokio::time::sleep(Duration::from_millis(10)).await;
    }
    panic!("expected {n} sends, got {:?}", fake.sends().len());
}

async fn device_page(app: &TestApp, id: i64) -> String {
    let response = crate::handlers::devices::view_device(
        axum::extract::State(app.state.clone()),
        axum::extract::Path(id),
        axum::extract::Query(Default::default()),
    )
    .await
    .into_response();
    super::read_response(response).await.text()
}

async fn security_details(app: &TestApp, event_type: &str) -> Vec<String> {
    sqlx::query_scalar("SELECT detail FROM security_events WHERE event_type = ? ORDER BY id")
        .bind(event_type)
        .fetch_all(&app.db)
        .await
        .unwrap()
}

// ---------------------------------------------------------------------------------------------
// Policy and status wiring
// ---------------------------------------------------------------------------------------------

#[tokio::test]
async fn push_policy_without_fcm_says_off() {
    let app = TestApp::new().await;
    let (_, token) = app.enrolled_device("phone").await;
    post_status(
        &app,
        &token,
        json!({ "push": { "fcm_token": "tok-1", "transport": "sse" } }),
    )
    .await;
    assert_eq!(
        policy_push(&app, &token).await,
        json!({ "fcm_enabled": false, "fcm_ok": false, "fcm_token_hash": token_hash("tok-1") })
    );
}

#[tokio::test]
async fn new_token_gets_a_test_nudge_and_an_ack_proves_it() {
    let (app, fake) = app_with_fake().await;
    let (id, token) = app.enrolled_device("phone").await;
    assert_eq!(
        policy_push(&app, &token).await,
        json!({ "fcm_enabled": true, "fcm_ok": false, "fcm_token_hash": null })
    );

    post_status(
        &app,
        &token,
        json!({ "push": { "fcm_token": "tok-1", "transport": "sse", "fcm_configured": true,
                          "gms_available": true, "reason": "not_proven" } }),
    )
    .await;
    let sends = wait_for_sends(&fake, 1).await;
    assert_eq!(sends[0].0, "tok-1");
    let nonce = sends[0].1.clone();
    assert_eq!(nonce.len(), 16);
    // Give the spawned send time to record itself.
    for _ in 0..100 {
        let pending: Option<String> =
            sqlx::query_scalar("SELECT last_send_nonce FROM device_push WHERE device_id = ?")
                .bind(id)
                .fetch_one(&app.db)
                .await
                .unwrap();
        if pending.is_some() {
            break;
        }
        tokio::time::sleep(Duration::from_millis(10)).await;
    }
    let push = policy_push(&app, &token).await;
    assert_eq!(push["fcm_ok"], false, "not proven before the ack");
    assert_eq!(push["fcm_token_hash"], token_hash("tok-1"));

    // A wrong nonce isn't an ack.
    post_status(
        &app,
        &token,
        json!({ "push": { "fcm_token": "tok-1", "last_nudge_id": "0000000000000000" } }),
    )
    .await;
    assert_eq!(policy_push(&app, &token).await["fcm_ok"], false);

    post_status(
        &app,
        &token,
        json!({ "push": { "fcm_token": "tok-1", "transport": "fcm", "last_nudge_id": nonce,
                          "last_nudge_ms": 1_790_000_000_000i64, "last_priority": "high",
                          "last_original_priority": "high" } }),
    )
    .await;
    let push = policy_push(&app, &token).await;
    assert_eq!(push["fcm_ok"], true);

    let page = device_page(&app, id).await;
    assert!(page.contains("FCM confirmed working"), "{page}");
    assert!(page.contains("FCM nudges (low battery use)"), "{page}");

    // A new token starts unproven again.
    post_status(&app, &token, json!({ "push": { "fcm_token": "tok-2" } })).await;
    let push = policy_push(&app, &token).await;
    assert_eq!(push["fcm_ok"], false);
    assert_eq!(push["fcm_token_hash"], token_hash("tok-2"));
    wait_for_sends(&fake, 2).await;
}

#[tokio::test]
async fn dead_token_is_cleared_and_logged() {
    let (app, fake) = app_with_fake().await;
    let (id, token) = app.enrolled_device("phone").await;
    fake.answer(SendOutcome::TokenDead("UNREGISTERED".into()));
    post_status(
        &app,
        &token,
        json!({ "push": { "fcm_token": "dead-token" } }),
    )
    .await;
    wait_for_sends(&fake, 1).await;
    for _ in 0..200 {
        if !security_details(&app, "fcm_token_cleared").await.is_empty() {
            break;
        }
        tokio::time::sleep(Duration::from_millis(10)).await;
    }
    let push = policy_push(&app, &token).await;
    assert_eq!(
        push,
        json!({ "fcm_enabled": true, "fcm_ok": false, "fcm_token_hash": null })
    );
    let events = security_details(&app, "fcm_token_cleared").await;
    assert_eq!(events.len(), 1);
    assert!(events[0].contains("UNREGISTERED"));
    assert!(!events[0].contains("dead-token"), "token never logged");
    let page = device_page(&app, id).await;
    assert!(page.contains("token rejected by FCM"), "{page}");
}

#[tokio::test]
async fn a_rejected_token_reported_again_is_ignored() {
    let (app, fake) = app_with_fake().await;
    let (_, token) = app.enrolled_device("phone").await;
    fake.answer(SendOutcome::TokenDead("UNREGISTERED".into()));
    post_status(
        &app,
        &token,
        json!({ "push": { "fcm_token": "dead-token" } }),
    )
    .await;
    wait_for_sends(&fake, 1).await;
    for _ in 0..200 {
        if !security_details(&app, "fcm_token_cleared").await.is_empty() {
            break;
        }
        tokio::time::sleep(Duration::from_millis(10)).await;
    }
    // The phone keeps reporting it until it renews: not stored, not nudged, not logged again.
    for _ in 0..3 {
        post_status(
            &app,
            &token,
            json!({ "push": { "fcm_token": "dead-token" } }),
        )
        .await;
    }
    tokio::time::sleep(Duration::from_millis(100)).await;
    assert_eq!(fake.sends().len(), 1);
    assert_eq!(security_details(&app, "fcm_token_cleared").await.len(), 1);
    assert_eq!(
        policy_push(&app, &token).await["fcm_token_hash"],
        json!(null)
    );
    // A renewed token is taken (and tested) again.
    fake.answer(SendOutcome::Sent);
    post_status(
        &app,
        &token,
        json!({ "push": { "fcm_token": "fresh-token" } }),
    )
    .await;
    wait_for_sends(&fake, 2).await;
    assert_eq!(
        policy_push(&app, &token).await["fcm_token_hash"],
        json!(token_hash("fresh-token"))
    );
}

#[tokio::test]
async fn a_token_belongs_to_one_device() {
    let (app, fake) = app_with_fake().await;
    let (_, old) = app.enrolled_device("old row").await;
    let (_, new) = app.enrolled_device("re-enrolled").await;
    post_status(&app, &old, json!({ "push": { "fcm_token": "same-phone" } })).await;
    wait_for_sends(&fake, 1).await;
    post_status(&app, &new, json!({ "push": { "fcm_token": "same-phone" } })).await;
    wait_for_sends(&fake, 2).await;
    assert_eq!(policy_push(&app, &old).await["fcm_token_hash"], json!(null));
    assert_eq!(
        policy_push(&app, &new).await["fcm_token_hash"],
        json!(token_hash("same-phone"))
    );
    let holders: i64 =
        sqlx::query_scalar("SELECT COUNT(*) FROM device_push WHERE fcm_token = 'same-phone'")
            .fetch_one(&app.db)
            .await
            .unwrap();
    assert_eq!(holders, 1);
}

#[tokio::test]
async fn dns_changes_nudge_the_devices() {
    let app = TestApp::new().await;
    let (id, _) = app.enrolled_device("phone").await;
    let mut rx = app.state.command_notify.subscribe();
    let cookie = app.admin_cookie().await;
    let res = app
        .request_form(
            Method::POST,
            "/dns/domains/new",
            Some(&cookie),
            &[("domain", "ads.example"), ("list_type", "block")],
        )
        .await;
    assert!(res.status.is_redirection(), "{}", res.text());
    assert_eq!(rx.try_recv().unwrap(), id);
    let res = app
        .request_form(
            Method::POST,
            "/dns/upstream",
            Some(&cookie),
            &[("upstream", "quad9")],
        )
        .await;
    assert!(res.status.is_redirection(), "{}", res.text());
    assert_eq!(rx.try_recv().unwrap(), id);
}

#[tokio::test]
async fn policy_save_nudge_reaches_fcm_through_the_dispatcher() {
    let (app, fake) = app_with_fake().await;
    let (id, token) = app.enrolled_device("phone").await;
    post_status(&app, &token, json!({ "push": { "fcm_token": "tok-1" } })).await;
    wait_for_sends(&fake, 1).await; // the new-token test nudge
    crate::push::spawn(app.state.clone());
    tokio::time::sleep(Duration::from_millis(50)).await; // dispatcher subscribed

    let cookie = app.admin_cookie().await;
    let res = app
        .request_form(
            Method::POST,
            &format!("/devices/{id}/hardening"),
            Some(&cookie),
            &[("disallow_factory_reset", "on")],
        )
        .await;
    assert!(res.status.is_redirection(), "{}", res.text());
    let sends = wait_for_sends(&fake, 2).await;
    assert_eq!(sends[1].0, "tok-1");
}

#[tokio::test]
async fn health_tick_turns_fcm_off_after_missed_acks() {
    let (app, _fake) = app_with_fake().await;
    let (id, token) = app.enrolled_device("phone").await;
    // Inserted directly: a status report would also fire a test nudge racing the setup below.
    sqlx::query("INSERT INTO device_push (device_id, fcm_token) VALUES (?, 'tok-1')")
        .bind(id)
        .execute(&app.db)
        .await
        .unwrap();
    // Proven, then two sends that were never acknowledged.
    let now = chrono::Utc::now().timestamp();
    sqlx::query(
        "UPDATE device_push SET fcm_ok = 1, unacked_sends = 1, send_pending = 1, \
         last_send_at = ?, last_send_nonce = 'abc' WHERE device_id = ?",
    )
    .bind(now - 120)
    .bind(id)
    .execute(&app.db)
    .await
    .unwrap();
    assert_eq!(policy_push(&app, &token).await["fcm_ok"], true);
    let permits = Arc::new(tokio::sync::Semaphore::new(1));
    crate::push::health_tick(&app.state, &permits).await;
    assert_eq!(policy_push(&app, &token).await["fcm_ok"], false);
    let page = device_page(&app, id).await;
    assert!(
        page.contains("FCM nudges stopped reaching the phone"),
        "{page}"
    );
}

#[tokio::test]
async fn status_stores_push_install_mode_installer_and_logs() {
    let app = TestApp::new().await;
    let (id, token) = app.enrolled_device("phone").await;
    post_status(
        &app,
        &token,
        json!({ "installed_apps": [{ "package_name": "a", "label": "A", "preinstalled": true }] }),
    )
    .await;
    let until = chrono::Utc::now().timestamp_millis() + 15 * 60_000;
    post_status(
        &app,
        &token,
        json!({
            "installed_apps": [
                { "package_name": "a", "label": "A", "preinstalled": true },
                { "package_name": "com.example.game", "label": "Game", "preinstalled": false,
                  "installer": "com.android.vending" }
            ],
            "install_mode": { "until_ms": until },
            "play_window_active": false,
            "push": { "fcm_token": null, "transport": "sse", "reason": "no_config" },
            "capabilities": ["fcm_push_v1", "play_policy_v1"]
        }),
    )
    .await;
    let (until_stored, push_json, apps): (Option<i64>, Option<String>, Option<String>) =
        sqlx::query_as(
            "SELECT install_mode_until_ms, push_state_json, installed_apps_json FROM device_status \
             WHERE device_id = ? ORDER BY id DESC LIMIT 1",
        )
        .bind(id)
        .fetch_one(&app.db)
        .await
        .unwrap();
    assert_eq!(until_stored, Some(until));
    assert!(push_json.unwrap().contains("no_config"));
    assert!(
        apps.unwrap()
            .contains(r#""installer":"com.android.vending""#)
    );

    let installs = security_details(&app, "app_installed").await;
    assert_eq!(installs.len(), 1, "{installs:?}");
    assert!(
        installs[0].contains("com.example.game") && installs[0].contains("com.android.vending")
    );
    assert_eq!(security_details(&app, "play_install_mode").await.len(), 1);

    let page = device_page(&app, id).await;
    assert!(page.contains("Play install mode is on"), "{page}");
    assert!(page.contains("no FCM config"), "{page}");
    assert!(page.contains("Installed from Play"), "{page}");

    // The same window again isn't logged twice.
    post_status(
        &app,
        &token,
        json!({ "install_mode": { "until_ms": until } }),
    )
    .await;
    assert_eq!(security_details(&app, "play_install_mode").await.len(), 1);
}

// ---------------------------------------------------------------------------------------------
// Play
// ---------------------------------------------------------------------------------------------

#[tokio::test]
async fn play_core_is_never_in_the_allowlist_ui() {
    let app = TestApp::new().await;
    let (id, token) = app.enrolled_device("phone").await;
    post_status(
        &app,
        &token,
        json!({ "installed_apps": [
            { "package_name": "com.android.vending", "label": "Play Store", "preinstalled": true },
            { "package_name": "com.google.android.gms", "label": "Play services", "preinstalled": true },
            { "package_name": "com.google.android.gsf", "label": "GSF", "preinstalled": true },
            { "package_name": "org.example.app", "label": "Example", "preinstalled": false }
        ] }),
    )
    .await;
    // The first-heartbeat bootstrap leaves them out.
    let allowlist: Option<String> =
        sqlx::query_scalar("SELECT allowlist_json FROM device_policy WHERE device_id = ?")
            .bind(id)
            .fetch_one(&app.db)
            .await
            .unwrap();
    assert_eq!(allowlist.as_deref(), Some(r#"["org.example.app"]"#));

    let page = device_page(&app, id).await;
    assert!(page.contains("org.example.app"));
    for pkg in crate::play::PLAY_CORE {
        assert!(!page.contains(&format!("value=\"{pkg}\"")), "{pkg} listed");
    }

    for pkg in crate::play::PLAY_CORE {
        for selected in [true, false] {
            let mut form = std::collections::HashMap::new();
            form.insert("package_name".to_string(), pkg.to_string());
            if selected {
                form.insert("selected".to_string(), "on".to_string());
            }
            let status = crate::handlers::devices::toggle_app(
                axum::extract::State(app.state.clone()),
                axum::extract::Path(id),
                axum::Form(form),
            )
            .await
            .status();
            assert_eq!(status, StatusCode::BAD_REQUEST);
        }
    }
    let allowlist: Option<String> =
        sqlx::query_scalar("SELECT allowlist_json FROM device_policy WHERE device_id = ?")
            .bind(id)
            .fetch_one(&app.db)
            .await
            .unwrap();
    assert_eq!(allowlist.as_deref(), Some(r#"["org.example.app"]"#));
    let pending: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM device_pending_uninstalls")
        .fetch_one(&app.db)
        .await
        .unwrap();
    assert_eq!(pending, 0);
}

#[tokio::test]
async fn catalog_app_installed_from_play_is_refused() {
    let app = TestApp::new().await;
    let (id, token) = app.enrolled_device("phone").await;
    let tracked: i64 = sqlx::query_scalar(
        "INSERT INTO tracked_apps (name, package_name, github_repo, source_type) \
         VALUES ('Game', 'com.example.game', '', 'manual') RETURNING id",
    )
    .fetch_one(&app.db)
    .await
    .unwrap();
    post_status(
        &app,
        &token,
        json!({ "installed_apps": [{ "package_name": "com.example.game", "label": "Game",
                                     "preinstalled": false, "installer": "com.android.vending" }] }),
    )
    .await;
    let select = |app: &TestApp| {
        let mut form = std::collections::HashMap::new();
        form.insert("package_name".to_string(), "com.example.game".to_string());
        form.insert("tracked_app_id".to_string(), tracked.to_string());
        form.insert("selected".to_string(), "on".to_string());
        crate::handlers::devices::toggle_app(
            axum::extract::State(app.state.clone()),
            axum::extract::Path(id),
            axum::Form(form),
        )
    };
    let res = super::read_response(select(&app).await).await;
    assert_eq!(res.status, StatusCode::CONFLICT);
    assert!(res.text().contains("Play Store"));
    let selected: i64 =
        sqlx::query_scalar("SELECT COUNT(*) FROM device_tracked_apps WHERE device_id = ?")
            .bind(id)
            .fetch_one(&app.db)
            .await
            .unwrap();
    assert_eq!(selected, 0, "nothing written");

    // From another source it's fine.
    post_status(
        &app,
        &token,
        json!({ "installed_apps": [{ "package_name": "com.example.game", "label": "Game",
                                     "preinstalled": false, "installer": "com.kidslauncher.mdm" }] }),
    )
    .await;
    assert!(select(&app).await.status().is_redirection());
}

// ---------------------------------------------------------------------------------------------
// The HTTP sender against a fake Google
// ---------------------------------------------------------------------------------------------

/// A scripted response for the fake FCM endpoint: (status, Retry-After, body).
type Scripted = (u16, Option<&'static str>, &'static str);

/// Scripted responses for the fake FCM endpoint. Empty = 200.
#[derive(Clone, Default)]
struct FakeGoogle {
    script: Arc<Mutex<Vec<Scripted>>>,
    sends: Arc<Mutex<Vec<(String, serde_json::Value)>>>,
    assertions: Arc<Mutex<Vec<String>>>,
    token_fails: Arc<Mutex<bool>>,
    hang: Arc<Mutex<bool>>,
}

async fn start_fake_google(fake: FakeGoogle) -> String {
    use axum::routing::post;
    let token_fake = fake.clone();
    let send_fake = fake.clone();
    let router = axum::Router::new()
        .route(
            "/token",
            post(move |body: String| {
                let fake = token_fake.clone();
                async move {
                    let assertion = form_urlencoded::parse(body.as_bytes())
                        .find(|(k, _)| k == "assertion")
                        .map(|(_, v)| v.into_owned())
                        .unwrap_or_default();
                    fake.assertions.lock().unwrap().push(assertion);
                    if *fake.token_fails.lock().unwrap() {
                        return (
                            StatusCode::BAD_REQUEST,
                            axum::Json(json!({"error": "invalid_grant"})),
                        );
                    }
                    let n = fake.assertions.lock().unwrap().len();
                    (
                        StatusCode::OK,
                        axum::Json(
                            json!({"access_token": format!("access-{n}"), "expires_in": 3600}),
                        ),
                    )
                }
            }),
        )
        .route(
            "/v1/projects/{project}/messages:send",
            post(
                move |headers: axum::http::HeaderMap,
                      axum::Json(body): axum::Json<serde_json::Value>| {
                    let fake = send_fake.clone();
                    async move {
                        if *fake.hang.lock().unwrap() {
                            tokio::time::sleep(Duration::from_secs(30)).await;
                        }
                        let auth = headers
                            .get("authorization")
                            .and_then(|v| v.to_str().ok())
                            .unwrap_or_default()
                            .to_string();
                        fake.sends.lock().unwrap().push((auth, body));
                        let next = {
                            let mut script = fake.script.lock().unwrap();
                            if script.is_empty() {
                                None
                            } else {
                                Some(script.remove(0))
                            }
                        };
                        let (status, retry, body) = next.unwrap_or((200, None, r#"{"name":"x"}"#));
                        let mut response =
                            (StatusCode::from_u16(status).unwrap(), body.to_string())
                                .into_response();
                        if let Some(retry) = retry {
                            response
                                .headers_mut()
                                .insert("retry-after", retry.parse().unwrap());
                        }
                        response
                    }
                },
            ),
        );
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let addr = listener.local_addr().unwrap();
    tokio::spawn(async move { axum::serve(listener, router).await.unwrap() });
    format!("http://{addr}")
}

fn test_config(base: &str) -> HttpFcmConfig {
    HttpFcmConfig {
        fcm_base_url: base.to_string(),
        request_timeout: Duration::from_millis(500),
        max_tries: 3,
        retry_budget: Duration::from_secs(5),
        default_backoff: Duration::from_millis(10),
    }
}

async fn http_sender(fake: &FakeGoogle) -> HttpFcmSender {
    let _ = rustls::crypto::ring::default_provider().install_default();
    let base = start_fake_google(fake.clone()).await;
    let account = parse_service_account(&service_account_json(&format!("{base}/token"))).unwrap();
    HttpFcmSender::new(account, test_config(&base)).unwrap()
}

#[tokio::test]
async fn http_sender_sends_a_constant_high_priority_message_with_a_valid_jwt() {
    let fake = FakeGoogle::default();
    let sender = http_sender(&fake).await;
    assert_eq!(
        sender.send(Target::token("reg-token"), "nonce1").await,
        SendOutcome::Sent
    );
    assert_eq!(
        sender.send(Target::token("reg-token"), "nonce2").await,
        SendOutcome::Sent
    );

    let assertions = fake.assertions.lock().unwrap().clone();
    assert_eq!(assertions.len(), 1, "access token cached");
    let sends = fake.sends.lock().unwrap().clone();
    assert_eq!(sends[0].0, "Bearer access-1");
    assert_eq!(
        sends[0].1,
        json!({"message": {"token": "reg-token", "data": {"k": "sync", "n": "nonce1"},
                           "android": {"priority": "HIGH", "ttl": "600s"}}})
    );

    // The JWT: RS256 over header.claims, verifiable with the fixture's public key.
    use base64::Engine;
    let b64 = base64::engine::general_purpose::URL_SAFE_NO_PAD;
    let parts: Vec<&str> = assertions[0].split('.').collect();
    assert_eq!(parts.len(), 3);
    let header: serde_json::Value = serde_json::from_slice(&b64.decode(parts[0]).unwrap()).unwrap();
    assert_eq!(header, json!({"alg": "RS256", "typ": "JWT"}));
    let claims: serde_json::Value = serde_json::from_slice(&b64.decode(parts[1]).unwrap()).unwrap();
    assert_eq!(
        claims["iss"],
        "fcm-sender@handy-test.iam.gserviceaccount.com"
    );
    assert_eq!(claims["scope"], crate::fcm::FCM_SCOPE);
    assert!(claims["aud"].as_str().unwrap().ends_with("/token"));
    assert_eq!(
        claims["exp"].as_i64().unwrap() - claims["iat"].as_i64().unwrap(),
        3600
    );
    let account = parse_service_account(&service_account_json("https://t")).unwrap();
    ring::signature::UnparsedPublicKey::new(
        &ring::signature::RSA_PKCS1_2048_8192_SHA256,
        account.public_key_der(),
    )
    .verify(
        format!("{}.{}", parts[0], parts[1]).as_bytes(),
        &b64.decode(parts[2]).unwrap(),
    )
    .expect("signature verifies");
}

/// qa-fixround-2026-10-06 #2: an FID goes in `message.fid`, never in the deprecated `token`.
#[tokio::test]
async fn http_sender_targets_an_fid_with_the_fid_field() {
    let fake = FakeGoogle::default();
    let sender = http_sender(&fake).await;
    let fid = "dIsVQ2QVRT-nQyYvE0SNfx";
    assert_eq!(sender.send(Target::fid(fid), "n1").await, SendOutcome::Sent);
    let sends = fake.sends.lock().unwrap().clone();
    assert_eq!(
        sends[0].1,
        json!({"message": {"fid": fid, "data": {"k": "sync", "n": "n1"},
                           "android": {"priority": "HIGH", "ttl": "600s"}}})
    );
}

#[test]
fn target_kind_from_report_and_shape() {
    let fid = "dIsVQ2QVRT-nQyYvE0SNfx";
    let token = "dIsVQ2QVRT-nQyYvE0SNfx:APA91bH_long-registration-token";
    assert_eq!(TargetKind::of(fid, Some("fid")), TargetKind::Fid);
    assert_eq!(TargetKind::of(fid, None), TargetKind::Fid);
    assert_eq!(TargetKind::of(fid, Some("token")), TargetKind::Token);
    assert_eq!(TargetKind::of(token, None), TargetKind::Token);
    // A token always has ':' - a contradicting report doesn't make it an FID.
    assert_eq!(TargetKind::of(token, Some("fid")), TargetKind::Token);
    assert_eq!(TargetKind::of("tok-1", None), TargetKind::Token);
    assert_eq!(TargetKind::of("tok-1", Some("fid")), TargetKind::Fid);
}

/// The phone says what it registered; the test nudge goes to the matching field, and a report
/// without the kind (an older launcher) falls back to the shape.
#[tokio::test]
async fn reported_kind_picks_the_fcm_field() {
    let (app, fake) = app_with_fake().await;
    let (_, token) = app.enrolled_device("phone").await;
    let fid = "eAbcdefghijklmnopqrstu";
    post_status(
        &app,
        &token,
        json!({ "push": { "fcm_token": fid, "fcm_token_kind": "fid", "transport": "sse",
                          "fcm_configured": true, "gms_available": true } }),
    )
    .await;
    wait_for_sends(&fake, 1).await;
    assert_eq!(fake.kinds(), [TargetKind::Fid]);

    let (_, token2) = app.enrolled_device("phone2").await;
    post_status(
        &app,
        &token2,
        json!({ "push": { "fcm_token": "abc:legacy-token", "transport": "sse",
                          "fcm_configured": true, "gms_available": true } }),
    )
    .await;
    wait_for_sends(&fake, 2).await;
    assert_eq!(fake.kinds(), [TargetKind::Fid, TargetKind::Token]);
}

#[tokio::test]
async fn http_sender_classifies_and_retries() {
    let fake = FakeGoogle::default();
    let sender = http_sender(&fake).await;

    // 404 UNREGISTERED: dead token, no retry.
    fake.script.lock().unwrap().push((
        404,
        None,
        r#"{"error":{"status":"NOT_FOUND","details":[{"errorCode":"UNREGISTERED"}]}}"#,
    ));
    assert_eq!(
        sender.send(Target::token("t"), "n").await,
        SendOutcome::TokenDead("UNREGISTERED".into())
    );
    assert_eq!(fake.sends.lock().unwrap().len(), 1);

    // 429 with Retry-After: 1 s, then fine.
    fake.sends.lock().unwrap().clear();
    fake.script.lock().unwrap().push((
        429,
        Some("1"),
        r#"{"error":{"status":"RESOURCE_EXHAUSTED"}}"#,
    ));
    let started = std::time::Instant::now();
    assert_eq!(
        sender.send(Target::token("t"), "n").await,
        SendOutcome::Sent
    );
    assert!(
        started.elapsed() >= Duration::from_secs(1),
        "Retry-After honoured"
    );
    assert_eq!(fake.sends.lock().unwrap().len(), 2);

    // 503 three times: gives up after max_tries.
    fake.sends.lock().unwrap().clear();
    for _ in 0..3 {
        fake.script
            .lock()
            .unwrap()
            .push((503, None, r#"{"error":{"status":"UNAVAILABLE"}}"#));
    }
    assert!(matches!(
        sender.send(Target::token("t"), "n").await,
        SendOutcome::Failed(_)
    ));
    assert_eq!(fake.sends.lock().unwrap().len(), 3);

    // A Retry-After beyond the budget: no waiting it out.
    fake.sends.lock().unwrap().clear();
    fake.script.lock().unwrap().push((
        429,
        Some("600"),
        r#"{"error":{"status":"RESOURCE_EXHAUSTED"}}"#,
    ));
    assert!(matches!(
        sender.send(Target::token("t"), "n").await,
        SendOutcome::Failed(_)
    ));
    assert_eq!(fake.sends.lock().unwrap().len(), 1);

    // 401: new access token, one retry.
    fake.sends.lock().unwrap().clear();
    fake.script
        .lock()
        .unwrap()
        .push((401, None, r#"{"error":{"status":"UNAUTHENTICATED"}}"#));
    let before = fake.assertions.lock().unwrap().len();
    assert_eq!(
        sender.send(Target::token("t"), "n").await,
        SendOutcome::Sent
    );
    assert_eq!(fake.assertions.lock().unwrap().len(), before + 1);
}

#[tokio::test]
async fn http_sender_times_out_against_a_hanging_server() {
    let fake = FakeGoogle::default();
    let sender = http_sender(&fake).await;
    *fake.hang.lock().unwrap() = true;
    let started = std::time::Instant::now();
    let outcome = sender.send(Target::token("t"), "n").await;
    assert!(matches!(outcome, SendOutcome::Failed(_)), "{outcome:?}");
    assert!(
        started.elapsed() < Duration::from_secs(5),
        "bounded: {:?}",
        started.elapsed()
    );
}

#[tokio::test]
async fn token_endpoint_failure_is_reported_for_the_device_page() {
    let fake = FakeGoogle::default();
    let sender = http_sender(&fake).await;
    *fake.token_fails.lock().unwrap() = true;
    assert!(matches!(
        sender.send(Target::token("t"), "n").await,
        SendOutcome::Failed(_)
    ));
    let problem = sender.server_problem().expect("problem reported");
    assert!(problem.contains("token endpoint"), "{problem}");
    *fake.token_fails.lock().unwrap() = false;
    assert_eq!(
        sender.send(Target::token("t"), "n").await,
        SendOutcome::Sent
    );
    assert_eq!(sender.server_problem(), None);
}

#[test]
fn error_classes() {
    let dead = |code: &str| {
        format!(r#"{{"error":{{"status":"X","details":[{{"errorCode":"{code}"}}]}}}}"#)
    };
    assert_eq!(
        classify_fcm_error(404, &dead("UNREGISTERED"), None),
        FcmErrorClass::TokenDead("UNREGISTERED".into())
    );
    assert_eq!(
        classify_fcm_error(403, &dead("SENDER_ID_MISMATCH"), None),
        FcmErrorClass::TokenDead("SENDER_ID_MISMATCH".into())
    );
    assert_eq!(
        classify_fcm_error(400, r#"{"error":{"status":"INVALID_ARGUMENT"}}"#, None),
        FcmErrorClass::TokenDead("INVALID_ARGUMENT".into())
    );
    assert_eq!(
        classify_fcm_error(401, "", None),
        FcmErrorClass::Unauthorized
    );
    assert_eq!(
        classify_fcm_error(429, &dead("QUOTA_EXCEEDED"), Some("30")),
        FcmErrorClass::Retryable {
            code: "QUOTA_EXCEEDED".into(),
            retry_after: Some(Duration::from_secs(30))
        }
    );
    assert!(matches!(
        classify_fcm_error(503, "garbage", None),
        FcmErrorClass::Retryable {
            retry_after: None,
            ..
        }
    ));
    assert!(matches!(
        classify_fcm_error(500, "", None),
        FcmErrorClass::Retryable { .. }
    ));
    assert!(matches!(
        classify_fcm_error(403, r#"{"error":{"status":"PERMISSION_DENIED"}}"#, None),
        FcmErrorClass::Fatal(_)
    ));
}

// ---------------------------------------------------------------------------------------------
// Key handling
// ---------------------------------------------------------------------------------------------

#[cfg(unix)]
#[test]
fn key_file_must_be_private_and_outside_the_data_dir() {
    use std::os::unix::fs::PermissionsExt;
    let dir = tempfile::tempdir().unwrap();
    let data = dir.path().join("data");
    std::fs::create_dir_all(&data).unwrap();
    let write = |path: &std::path::Path, mode: u32| {
        std::fs::write(path, service_account_json("https://t")).unwrap();
        std::fs::set_permissions(path, std::fs::Permissions::from_mode(mode)).unwrap();
    };

    let good = dir.path().join("fcm.json");
    write(&good, 0o600);
    let account = load_service_account(&good, &data).expect("a private key outside data loads");
    assert_eq!(account.project_id, "handy-test");

    let world = dir.path().join("world.json");
    write(&world, 0o644);
    assert!(matches!(
        load_service_account(&world, &data),
        Err(crate::fcm::LoadError::Permissions(0o644))
    ));
    let group = dir.path().join("group.json");
    write(&group, 0o640);
    assert!(load_service_account(&group, &data).is_err());

    let inside = data.join("fcm.json");
    write(&inside, 0o600);
    assert!(matches!(
        load_service_account(&inside, &data),
        Err(crate::fcm::LoadError::InsideDataDir(_))
    ));

    assert!(load_service_account(&dir.path().join("missing.json"), &data).is_err());
    assert!(
        parse_service_account(r#"{"project_id":"p","client_email":"e","private_key":"x"}"#)
            .is_err()
    );
}

#[test]
fn secrets_never_in_debug_output() {
    let account = parse_service_account(&service_account_json("https://t")).unwrap();
    let debug = format!("{account:?}");
    assert!(debug.contains("handy-test") && debug.contains("<redacted>"));
    assert!(!debug.contains("BEGIN") && !debug.contains("MII"));
    let jwt = account.sign_jwt(1_700_000_000).unwrap();
    assert_eq!(format!("{jwt:?}"), "SignedJwt(<redacted>)");
    let _ = rustls::crypto::ring::default_provider().install_default();
    let sender = HttpFcmSender::new(account, HttpFcmConfig::default()).unwrap();
    let debug = format!("{sender:?}");
    assert!(!debug.contains("MII") && !debug.contains(&jwt.0[..20]));
}

// ---------------------------------------------------------------------------------------------
// SSE and DNS
// ---------------------------------------------------------------------------------------------

#[test]
fn sse_keepalive_defaults_and_validates() {
    use crate::config::ForkConfig;
    let vars = |v: &str| {
        std::collections::HashMap::from([("SSE_KEEPALIVE_SECS".to_string(), v.to_string())])
    };
    assert_eq!(
        ForkConfig::from_vars(&Default::default()).sse_keepalive_secs,
        120
    );
    assert_eq!(ForkConfig::from_vars(&vars("30")).sse_keepalive_secs, 30);
    assert_eq!(ForkConfig::from_vars(&vars("1")).sse_keepalive_secs, 120);
    // Must stay well under the launcher's 300 s read timeout (QA step 7 #4).
    assert_eq!(ForkConfig::from_vars(&vars("240")).sse_keepalive_secs, 240);
    assert_eq!(ForkConfig::from_vars(&vars("300")).sse_keepalive_secs, 120);
    assert_eq!(ForkConfig::from_vars(&vars("3600")).sse_keepalive_secs, 120);
    assert_eq!(ForkConfig::from_vars(&vars("abc")).sse_keepalive_secs, 120);
}

#[tokio::test]
async fn dns_blocklist_never_blocks_fcm() {
    let app = TestApp::new().await;
    let (_, token) = app.enrolled_device("phone").await;
    {
        let mut compiled = app.state.dns_compiled.write().await;
        compiled.global_custom_block = ["mtalk.google.com", "googleapis.com", "ads.example"]
            .into_iter()
            .map(String::from)
            .collect();
    }
    let res = app
        .request(
            Method::GET,
            "/api/devices/dns-blocklist",
            Some(&token),
            None,
        )
        .await;
    assert_eq!(res.status, StatusCode::OK);
    let body = res.text();
    assert!(body.contains("ads.example"), "{body}");
    // The exact FCM host is dropped; a parent the parent blocked is still delivered (QA #1).
    assert!(!body.contains("mtalk"), "{body}");
    assert!(body.contains("\"googleapis.com\""), "{body}");
}
