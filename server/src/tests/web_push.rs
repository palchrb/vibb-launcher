//! Design 35 step 2: Web Push - subscribe and its refusals, the sender against a scripted push
//! service (headers, the decrypted payload, every answer incl. H1's 403), the fold, the receipt,
//! health and its warnings, the key at startup, Sign Out Everywhere, backups, the delivery line.

use std::collections::VecDeque;
use std::sync::Mutex;

use axum::body::Body;
use axum::http::{Method, Request, StatusCode, header};
use serde_json::{Value, json};

use super::settings_pages::check_forms;
use super::{TestApp, TestResponse};
use crate::music_net::BoxFut;
use crate::push::{PushAnswer, PushRequest, Transport};
use crate::webpush::test_browser;

/// A push service in the tests: records every request and answers from a script (201 when it
/// runs out).
#[derive(Default)]
pub struct ScriptedPush {
    pub requests: Mutex<Vec<PushRequest>>,
    answers: Mutex<VecDeque<Result<PushAnswer, String>>>,
}

impl ScriptedPush {
    pub fn answer(&self, status: u16, reason: Option<&str>, retry_after: Option<&str>) {
        self.answers.lock().unwrap().push_back(Ok(PushAnswer {
            status,
            reason: reason.map(str::to_string),
            retry_after: retry_after.map(str::to_string),
            apns_id: None,
        }));
    }

    pub fn fail(&self) {
        self.answers
            .lock()
            .unwrap()
            .push_back(Err("connection refused".to_string()));
    }

    /// The requests so far, taken.
    pub fn take(&self) -> Vec<PushRequest> {
        std::mem::take(&mut *self.requests.lock().unwrap())
    }
}

impl Transport for ScriptedPush {
    fn send(&self, request: PushRequest) -> BoxFut<'_, Result<PushAnswer, String>> {
        self.requests.lock().unwrap().push(request);
        let answer = self
            .answers
            .lock()
            .unwrap()
            .pop_front()
            .unwrap_or(Ok(PushAnswer {
                status: 201,
                ..Default::default()
            }));
        Box::pin(async move { answer })
    }
}

const ORIGIN: &str = "https://kidphone.example.ts.net";
pub(super) const ENDPOINT: &str = "https://web.push.apple.com/QK1-fake-endpoint";
const IPHONE: &str = "Mozilla/5.0 (iPhone; CPU iPhone OS 18_4 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Mobile/15E148";

/// A POST as the browser sends This Device's form: same-origin, with `Origin` (unless `None`)
/// and an iPhone's User-Agent.
async fn post_as_browser(
    app: &TestApp,
    uri: &str,
    cookie: Option<&str>,
    origin: Option<&str>,
    fields: &[(&str, &str)],
) -> TestResponse {
    let body = form_urlencoded::Serializer::new(String::new())
        .extend_pairs(fields)
        .finish();
    let mut builder = Request::builder()
        .method(Method::POST)
        .uri(uri)
        .header(header::CONTENT_TYPE, "application/x-www-form-urlencoded")
        .header("sec-fetch-site", "same-origin")
        .header(header::USER_AGENT, IPHONE);
    if let Some(cookie) = cookie {
        builder = builder.header(header::COOKIE, cookie);
    }
    if let Some(origin) = origin {
        builder = builder.header(header::ORIGIN, origin);
    }
    app.send_via(
        &app.router,
        super::LOOPBACK_PEER,
        builder.body(Body::from(body)).unwrap(),
    )
    .await
}

/// Turns notifications on for `endpoint` with the RFC 8291 test browser's keys.
pub(super) async fn subscribe(app: &TestApp, cookie: &str, endpoint: &str) -> TestResponse {
    post_as_browser(
        app,
        "/notifications/subscriptions",
        Some(cookie),
        Some(ORIGIN),
        &[
            ("from", "devices"),
            ("endpoint", endpoint),
            ("p256dh", test_browser::UA_PUBLIC),
            ("auth", test_browser::AUTH),
        ],
    )
    .await
}

/// The inbox (the delivery lines, the warning rows) and Notification Settings (the device rows,
/// the clock warning) as one text, for words that may be on either (design 45). Opening the inbox
/// reads its rows.
async fn pages(app: &TestApp, cookie: &str) -> String {
    let inbox = app.get_page("/notifications", cookie).await.text();
    let settings = app.get_page("/notifications/settings", cookie).await.text();
    format!("{inbox}\n{settings}")
}

async fn scalar(app: &TestApp, sql: &str) -> i64 {
    sqlx::query_scalar(sql).fetch_one(&app.db).await.unwrap()
}

async fn send(app: &TestApp) {
    send_at(app, chrono::Utc::now()).await;
}

async fn send_at(app: &TestApp, now: chrono::DateTime<chrono::Utc>) {
    crate::push::send_due(&app.state, now).await.unwrap();
}

/// A phone, as the intake loads it for the words.
async fn phone(app: &TestApp, name: &str) -> crate::parent_events::Phone {
    let (id, _) = app.create_device(name).await;
    crate::parent_events::Phone::load(&app.db, id)
        .await
        .unwrap()
}

/// Raises `event` as an intake does (its own transaction, then the wake-up); its id.
async fn raise(app: &TestApp, event: &crate::parent_events::NewEvent) -> i64 {
    crate::parent_events::raise_logged(&app.state, event).await;
    scalar(app, "SELECT MAX(id) FROM parent_events").await
}

/// The decrypted payload of a recorded push.
pub(super) fn payload(request: &PushRequest) -> Value {
    let plain = test_browser::decrypt(&request.body).expect("the test browser can't read it");
    serde_json::from_slice(&plain).unwrap()
}

/// The JWT, its claims and the `k` of a request's `Authorization`.
fn vapid(request: &PushRequest) -> (String, Value, String) {
    let auth = request.header("Authorization").unwrap();
    let (t, k) = auth
        .strip_prefix("vapid t=")
        .and_then(|rest| rest.split_once(", k="))
        .unwrap();
    let claims = t.split('.').nth(1).unwrap();
    let claims =
        base64::Engine::decode(&base64::engine::general_purpose::URL_SAFE_NO_PAD, claims).unwrap();
    (
        t.to_string(),
        serde_json::from_slice(&claims).unwrap(),
        k.to_string(),
    )
}

/// The receipt as the worker posts it (no cookie), from the loopback peer.
async fn post_receipt(app: &TestApp, body: &str, site: &str) -> StatusCode {
    post_receipt_with(app, body, &[("sec-fetch-site", site)]).await
}

/// A receipt with exactly these headers besides the content type.
async fn post_receipt_with(app: &TestApp, body: &str, headers: &[(&str, &str)]) -> StatusCode {
    let mut request = Request::builder()
        .method(Method::POST)
        .uri("/notifications/receipt")
        .header(header::CONTENT_TYPE, "application/json");
    for (name, value) in headers {
        request = request.header(*name, *value);
    }
    let request = request.body(Body::from(body.to_string())).unwrap();
    app.send_via(&app.router, super::LOOPBACK_PEER, request)
        .await
        .status
}

/// The receipt is the token's, not the browser's (design 35 §7): a WebKit service worker posts it
/// with no Sec-Fetch-Site, and behind a proxy its Origin needn't match Host - the CSRF guard must
/// let it through to the handler (the user's iPhone, 2026-10-19: every push shown, every receipt
/// refused, "Nothing shown yet"). A forged one still gets a 404: it has no token.
#[tokio::test]
async fn a_receipt_needs_no_fetch_metadata_or_matching_origin() {
    let app = TestApp::new().await;
    let bad = json!({ "d": 1, "t": "AAAAAAAAAAAAAAAAAAAAAA", "a": "shown" }).to_string();
    // No Sec-Fetch-Site, an Origin of another host: the handler answers (404: no such token).
    assert_eq!(
        post_receipt_with(
            &app,
            &bad,
            &[(header::ORIGIN.as_str(), "https://pwa.example")]
        )
        .await,
        StatusCode::NOT_FOUND
    );
    // No metadata at all: the same.
    assert_eq!(
        post_receipt_with(&app, &bad, &[]).await,
        StatusCode::NOT_FOUND
    );
    // Even a cross-site one reaches the handler: there is nothing a cookie could lend it.
    assert_eq!(
        post_receipt_with(&app, &bad, &[("sec-fetch-site", "cross-site")]).await,
        StatusCode::NOT_FOUND
    );
    // Every other POST without a session is still the guard's business.
    let other = Request::builder()
        .method(Method::POST)
        .uri("/notifications/read-all")
        .header(header::CONTENT_TYPE, "application/x-www-form-urlencoded")
        .header(header::ORIGIN, "https://pwa.example")
        .body(Body::from("from=devices".to_string()))
        .unwrap();
    let res = app.send_via(&app.router, super::LOOPBACK_PEER, other).await;
    assert_eq!(res.status, StatusCode::FORBIDDEN);
}

fn receipt_of(request: &PushRequest, action: &str) -> String {
    let data = &payload(request)["notification"]["data"];
    json!({ "d": data["d"], "t": data["t"], "a": action }).to_string()
}

#[tokio::test]
async fn subscribe_refusals_upsert_and_unsubscribe() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let active = "SELECT COUNT(*) FROM push_subscriptions WHERE state = 'active'";

    // Nothing is stored without a session, or from another site.
    let res = post_as_browser(
        &app,
        "/notifications/subscriptions",
        None,
        Some(ORIGIN),
        &[("endpoint", ENDPOINT)],
    )
    .await;
    assert_eq!(res.location(), Some("/login"));
    let cross = Request::builder()
        .method(Method::POST)
        .uri("/notifications/subscriptions")
        .header(header::COOKIE, &cookie)
        .header("sec-fetch-site", "cross-site")
        .body(Body::empty())
        .unwrap();
    assert_eq!(
        app.send_via(&app.router, super::LOOPBACK_PEER, cross)
            .await
            .status,
        StatusCode::FORBIDDEN
    );

    // The refusals, each a flash back in #this-device.
    let mut off_curve = test_browser::bytes(test_browser::UA_PUBLIC);
    off_curve[64] ^= 1;
    let off_curve =
        base64::Engine::encode(&base64::engine::general_purpose::URL_SAFE_NO_PAD, off_curve);
    let (ua, auth) = (test_browser::UA_PUBLIC, test_browser::AUTH);
    for (endpoint, p256dh, auth, origin, notice) in [
        (
            "https://push.apple.com.example.net/x",
            ua,
            auth,
            Some(ORIGIN),
            "push_service",
        ),
        (
            "http://fcm.googleapis.com/fcm/send/x",
            ua,
            auth,
            Some(ORIGIN),
            "push_service",
        ),
        (ENDPOINT, off_curve.as_str(), auth, Some(ORIGIN), "push_key"),
        (ENDPOINT, ua, "c2hvcnQ", Some(ORIGIN), "push_key"),
        (ENDPOINT, ua, auth, None, "push_origin"),
    ] {
        let res = post_as_browser(
            &app,
            "/notifications/subscriptions",
            Some(&cookie),
            origin,
            &[
                ("from", "music"),
                ("endpoint", endpoint),
                ("p256dh", p256dh),
                ("auth", auth),
            ],
        )
        .await;
        assert_eq!(
            res.location(),
            Some(
                format!("/notifications/settings?from=music&notice={notice}#this-device").as_str()
            ),
            "{endpoint} {origin:?}"
        );
        app.assert_lands(&res, &cookie).await;
    }
    assert_eq!(scalar(&app, active).await, 0);

    // On: one row with the browser's label, a security line; the same endpoint again is the
    // same row (active again after it was turned off).
    let res = subscribe(&app, &cookie, ENDPOINT).await;
    assert_eq!(
        res.location(),
        Some("/notifications/settings?from=devices&notice=push_on#this-device")
    );
    let page = app.get_page(res.location().unwrap(), &cookie).await.text();
    // The row itself says On; no flash (the user, 2026-10-19: "rare grønne bokser og for mye tekst").
    assert!(!page.contains("class=\"flash"), "{page}");
    let (label, origin): (String, String) =
        sqlx::query_as("SELECT label, origin FROM push_subscriptions")
            .fetch_one(&app.db)
            .await
            .unwrap();
    assert_eq!(
        (label.as_str(), origin.as_str()),
        ("iPhone · Safari", ORIGIN)
    );
    assert_eq!(
        scalar(
            &app,
            "SELECT COUNT(*) FROM security_events WHERE event_type = 'push_subscribed'"
        )
        .await,
        1
    );
    sqlx::query("UPDATE push_subscriptions SET state = 'gone', gone_at = datetime('now')")
        .execute(&app.db)
        .await
        .unwrap();
    subscribe(&app, &cookie, ENDPOINT).await;
    assert_eq!(scalar(&app, active).await, 1);
    assert_eq!(
        scalar(
            &app,
            "SELECT COUNT(*) FROM push_subscriptions WHERE gone_at IS NULL"
        )
        .await,
        1
    );

    // At most 10 devices: the 11th is refused.
    for n in 2..=10 {
        subscribe(
            &app,
            &cookie,
            &format!("https://fcm.googleapis.com/fcm/send/fake-{n}"),
        )
        .await;
    }
    assert_eq!(scalar(&app, active).await, 10);
    let res = subscribe(&app, &cookie, "https://fcm.googleapis.com/fcm/send/fake-11").await;
    assert!(res.location().unwrap().contains("notice=push_many"));
    assert_eq!(scalar(&app, active).await, 10);

    // Turn Off: the row goes.
    let res = post_as_browser(
        &app,
        "/notifications/subscriptions/unsubscribe",
        Some(&cookie),
        Some(ORIGIN),
        &[("from", "devices"), ("endpoint", ENDPOINT)],
    )
    .await;
    assert_eq!(
        res.location(),
        Some("/notifications/settings?from=devices&notice=push_off#this-device")
    );
    app.assert_lands(&res, &cookie).await;
    assert_eq!(scalar(&app, active).await, 9);
}

/// §7: the page carries the public key and endpoint hash prefixes - never an endpoint, a browser
/// key, the auth secret or a token; every form lands on its own group.
#[tokio::test]
async fn the_page_carries_no_secret_and_every_form_lands() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    subscribe(&app, &cookie, ENDPOINT).await;
    let anna = phone(&app, "Anna").await;
    raise(&app, &anna.unseen_boot(1, None)).await;
    send(&app).await;
    let request = app.pushes.take().remove(0);
    let token = payload(&request)["notification"]["data"]["t"]
        .as_str()
        .unwrap()
        .to_string();
    // The other parent's subscription on a shared device is known too (qa-35-s2-code L1), so
    // This Device never drops it; a turned-off one isn't.
    let key = app.state.push.key.as_ref().unwrap();
    let other: i64 = sqlx::query_scalar(
        "INSERT INTO admin_users (username, password_hash) VALUES ('other', 'x') RETURNING id",
    )
    .fetch_one(&app.db)
    .await
    .unwrap();
    for (endpoint, state) in [
        ("https://fcm.googleapis.com/fcm/send/other-parent", "active"),
        ("https://fcm.googleapis.com/fcm/send/other-gone", "gone"),
    ] {
        sqlx::query(
            "INSERT INTO push_subscriptions \
             (admin_id, endpoint, p256dh, auth, origin, label, vapid_fp, state) \
             VALUES (?, ?, x'00', x'00', ?, 'iPad · Safari', ?, ?)",
        )
        .bind(other)
        .bind(endpoint)
        .bind(ORIGIN)
        .bind(&key.fingerprint)
        .bind(state)
        .execute(&app.db)
        .await
        .unwrap();
    }

    let html = app
        .get_page("/notifications/settings", &cookie)
        .await
        .text();
    let inbox = app.get_page("/notifications", &cookie).await.text();
    let json = app
        .get_page("/notifications/subscriptions.json", &cookie)
        .await
        .text();
    assert!(html.contains(&format!("data-key=\"{}\"", key.public)));
    assert!(html.contains(&format!(
        "data-subscribed=\"{},{}\"",
        crate::push::endpoint_hash(ENDPOINT),
        crate::push::endpoint_hash("https://fcm.googleapis.com/fcm/send/other-parent")
    )));
    for secret in [
        ENDPOINT,
        "QK1-fake",
        test_browser::UA_PUBLIC,
        test_browser::AUTH,
        token.as_str(),
    ] {
        for (page, text) in [("settings", &html), ("inbox", &inbox), ("json", &json)] {
            assert!(!text.contains(secret), "{secret} is on the {page} page");
        }
    }
    assert!(html.contains("<script src=\"/static/notifications.js?v=2\" defer></script>"));
    assert!(!inbox.contains("notifications.js"));
    check_forms("/notifications/settings", &html);
    check_forms("/notifications", &inbox);
    // Apple's service shows a declarative push itself: accepted is delivered (0.29.6).
    assert!(inbox.contains("Delivered to iPhone · Safari"));
}

/// §4.3, §5.3: the headers, the JWT, and the payload the browser decrypts - no `detail`.
#[tokio::test]
async fn a_push_carries_its_headers_and_an_encrypted_payload() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    subscribe(&app, &cookie, ENDPOINT).await;
    let anna = phone(&app, "Anna").await;
    let found = crate::window_relock::ForeignAccess {
        admins: vec!["org.example.spy".to_string()],
        accessibility: vec![],
    };
    let mut names = std::collections::HashMap::new();
    names.insert("org.example.spy".to_string(), "Helper".to_string());
    let foreign = raise(&app, &anna.foreign_access(&found, "{}", 0, &names)).await;
    assert!(app.state.push.woken().await, "the raise wakes the sender");
    let battery = crate::models::BatteryReport {
        level: 14,
        charging: false,
        low: true,
    };
    raise(&app, &anna.low_battery(&battery, anna.now)).await;
    send(&app).await;
    let requests = app.pushes.take();
    assert_eq!(requests.len(), 2);

    let first = &requests[0];
    assert_eq!(first.endpoint, ENDPOINT);
    assert_eq!(first.header("Content-Encoding"), Some("aes128gcm"));
    assert_eq!(
        first.header("Content-Type"),
        Some("application/octet-stream")
    );
    assert_eq!(
        (first.header("TTL"), first.header("Urgency")),
        (Some("43200"), Some("high"))
    );
    assert!(first.header("Topic").is_none());
    let (jwt, claims, k) = vapid(first);
    assert_eq!(k, app.state.push.key.as_ref().unwrap().public);
    assert_eq!(claims["aud"], "https://web.push.apple.com");
    assert_eq!(
        claims["sub"], ORIGIN,
        "no VAPID_SUBJECT: the subscription's origin"
    );
    let exp = claims["exp"].as_i64().unwrap();
    let now = chrono::Utc::now().timestamp();
    assert!(exp > now && exp <= now + 24 * 3600);
    // The second push reuses the JWT; a low battery is kept an hour.
    assert_eq!(vapid(&requests[1]).0, jwt);
    assert_eq!(
        (requests[1].header("TTL"), requests[1].header("Urgency")),
        (Some("3600"), Some("normal"))
    );

    let message = payload(first);
    let delivery = scalar(
        &app,
        &format!("SELECT id FROM push_deliveries WHERE event_id = {foreign}"),
    )
    .await;
    // Declarative by default (`PUSH_PAYLOAD` switches it; `push::tests::the_payload_switch`).
    assert_eq!(message["web_push"], 8030);
    // Never `mutable`: iOS 26 displays nothing after the worker ran (0.29.4/0.29.5).
    assert!(message.get("mutable").is_none());
    let n = &message["notification"];
    assert_eq!(n["title"], "An app may read the PIN on Anna");
    assert_eq!(n["body"], "1 app with device admin");
    assert_eq!(n["lang"], "en");
    assert_eq!(
        n["navigate"],
        format!(
            "{ORIGIN}/devices/{}?push={delivery}#foreign-access",
            anna.id
        ),
        "the marker before the fragment (server round 5 §1)"
    );
    assert_eq!(n["tag"], format!("e{foreign}"));
    assert_eq!(n["data"]["d"], delivery);
    assert_eq!(n["data"]["t"].as_str().unwrap().len(), 22);
    assert_eq!(n["data"]["b"], 2);
    assert!(
        !message.to_string().contains("Helper"),
        "the detail never leaves the server"
    );
    let (status, hash): (String, Vec<u8>) =
        sqlx::query_as("SELECT status, token_hash FROM push_deliveries WHERE id = ?")
            .bind(delivery)
            .fetch_one(&app.db)
            .await
            .unwrap();
    assert_eq!((status.as_str(), hash.len()), ("accepted", 32));
    send(&app).await;
    assert!(app.pushes.take().is_empty(), "nothing more to send");
}

async fn delivery_of(app: &TestApp, event: i64) -> (String, Option<i64>, Option<String>) {
    sqlx::query_as("SELECT status, http_status, reason FROM push_deliveries WHERE event_id = ?")
        .bind(event)
        .fetch_one(&app.db)
        .await
        .unwrap()
}

/// §4.4, H1: 410 turns the subscription off; a 403 BadJwtToken never does - a fresh JWT, one
/// retry, then failed and the clock warning; 429 waits for its Retry-After; a network error and
/// 5xx retry on the schedule; other 4xx fail; nothing goes after its TTL.
#[tokio::test]
async fn the_push_services_answers() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    subscribe(&app, &cookie, ENDPOINT).await;
    let anna = phone(&app, "Anna").await;
    let active = "SELECT COUNT(*) FROM push_subscriptions WHERE state = 'active'";

    // 403 BadJwtToken twice: still active, the retry carried a new JWT, the delivery failed.
    app.pushes.answer(403, Some("BadJwtToken"), None);
    app.pushes.answer(403, Some("BadJwtToken"), None);
    let one = raise(&app, &anna.unseen_boot(1, None)).await;
    send(&app).await;
    let tries = app.pushes.take();
    assert_eq!(tries.len(), 2);
    assert_ne!(
        vapid(&tries[0]).0,
        vapid(&tries[1]).0,
        "the retry mints a fresh JWT"
    );
    assert_eq!(
        delivery_of(&app, one).await,
        ("failed".into(), Some(403), Some("BadJwtToken".into()))
    );
    assert_eq!(scalar(&app, active).await, 1);
    let page = pages(&app, &cookie).await;
    assert!(page.contains("id=\"clock-warning\""));
    assert!(page.contains("Not sent"));

    // A 403 then a 201 (the clock was fixed): accepted, the warning goes.
    app.pushes.answer(403, Some("BadJwtToken"), None);
    let two = raise(&app, &anna.unseen_boot(2, None)).await;
    send(&app).await;
    assert_eq!(app.pushes.take().len(), 2);
    assert_eq!(delivery_of(&app, two).await.0, "accepted");
    let page = pages(&app, &cookie).await;
    assert!(!page.contains("id=\"clock-warning\""));

    // 429 with Retry-After: not again before it, then sent.
    app.pushes.answer(429, None, Some("120"));
    let three = raise(&app, &anna.unseen_boot(3, None)).await;
    let now = chrono::Utc::now();
    send_at(&app, now).await;
    assert_eq!(delivery_of(&app, three).await.0, "queued");
    send_at(&app, now + chrono::Duration::seconds(60)).await;
    assert_eq!(app.pushes.take().len(), 1, "nothing before the Retry-After");
    send_at(&app, now + chrono::Duration::seconds(121)).await;
    assert_eq!(app.pushes.take().len(), 1);
    assert_eq!(delivery_of(&app, three).await.0, "accepted");

    // A network error, then 503 four times: retried on the schedule, then failed.
    app.pushes.fail();
    for _ in 0..4 {
        app.pushes.answer(503, None, None);
    }
    let four = raise(&app, &anna.unseen_boot(4, None)).await;
    let mut at = chrono::Utc::now();
    for wait in [0, 60, 300, 1800, 7200] {
        at += chrono::Duration::seconds(wait);
        send_at(&app, at).await;
    }
    assert_eq!(app.pushes.take().len(), 5);
    assert_eq!(
        delivery_of(&app, four).await,
        ("failed".into(), Some(503), None)
    );

    // A low battery waiting to retry past its hour's TTL: failed, never sent late.
    app.pushes.answer(500, None, None);
    let battery = crate::models::BatteryReport {
        level: 9,
        charging: false,
        low: true,
    };
    let five = raise(&app, &anna.low_battery(&battery, anna.now)).await;
    let now = chrono::Utc::now();
    send_at(&app, now).await;
    send_at(&app, now + chrono::Duration::seconds(3700)).await;
    assert_eq!(app.pushes.take().len(), 1);
    assert_eq!(
        delivery_of(&app, five).await,
        ("failed".into(), Some(500), Some("expired".into()))
    );

    // Another 4xx: failed.
    app.pushes.answer(400, Some("BadDeviceToken"), None);
    let six = raise(&app, &anna.unseen_boot(6, None)).await;
    send(&app).await;
    assert_eq!(delivery_of(&app, six).await.0, "failed");

    // 410: dropped, the subscription turned off (a security line), nothing queued for it again.
    app.pushes.answer(410, Some("Unregistered"), None);
    let seven = raise(&app, &anna.unseen_boot(7, None)).await;
    send(&app).await;
    assert_eq!(delivery_of(&app, seven).await.0, "dropped");
    assert_eq!(scalar(&app, active).await, 0);
    assert_eq!(
        scalar(
            &app,
            "SELECT COUNT(*) FROM security_events WHERE event_type = 'push_subscription_gone'"
        )
        .await,
        1
    );
    raise(&app, &anna.unseen_boot(8, None)).await;
    assert_eq!(
        scalar(
            &app,
            "SELECT COUNT(*) FROM push_deliveries WHERE status = 'queued'"
        )
        .await,
        0
    );
    let page = pages(&app, &cookie).await;
    assert!(page.contains("Turned off on that device"));
    assert!(
        !page.contains(&crate::push::endpoint_hash(ENDPOINT)),
        "a turned-off one isn't known to This Device"
    );
}

/// §2.4, §4.6: a switched-off kind is stored but not pushed, the emergency call always is; a
/// burst from one intake is the newest two and one summary, the emergency call on its own.
#[tokio::test]
async fn switches_the_emergency_call_and_a_burst() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    subscribe(&app, &cookie, ENDPOINT).await;
    let (id, token) = app.enrolled_device("Anna").await;
    let anna = crate::parent_events::Phone::load(&app.db, id)
        .await
        .unwrap();

    sqlx::query(
        "INSERT INTO notification_prefs (admin_id, push_unseen_boot) SELECT id, 0 FROM admin_users",
    )
    .execute(&app.db)
    .await
    .unwrap();
    raise(&app, &anna.unseen_boot(1, None)).await;
    assert_eq!(
        scalar(&app, "SELECT COUNT(*) FROM push_deliveries").await,
        0
    );
    assert_eq!(scalar(&app, "SELECT COUNT(*) FROM parent_events").await, 1);

    // 20 PIN uses in one pin-events POST, and an emergency call.
    let now_ms = chrono::Utc::now().timestamp_millis();
    let events: Vec<Value> = (0..20)
        .map(|n| {
            json!({ "id": format!("{n:032x}"), "kind": "unlocked", "entry": "override",
                    "at_ms": now_ms - 60_000 + n })
        })
        .chain(std::iter::once(json!({
            "id": "f".repeat(32), "kind": "lockout", "entry": "pin_lock",
            "at_ms": now_ms - 59_990, "until_ms": now_ms + 900_000, "level": 1,
        })))
        .collect();
    let res = app
        .request(
            Method::POST,
            "/api/devices/pin-events",
            Some(&token),
            Some(json!({ "events": events })),
        )
        .await;
    assert_eq!(res.status, StatusCode::NO_CONTENT);
    assert!(
        app.state.push.woken().await,
        "pin-events wakes the sender after its commit"
    );
    raise(&app, &anna.emergency_call(anna.now)).await;
    send(&app).await;
    let pushed: Vec<(String, String)> = app
        .pushes
        .take()
        .iter()
        .map(|r| {
            let n = &payload(r)["notification"];
            (
                n["title"].as_str().unwrap().to_string(),
                n["tag"].as_str().unwrap().to_string(),
            )
        })
        .collect();
    assert_eq!(pushed.len(), 5, "{pushed:?}");
    assert_eq!(
        pushed[0].0, "Anna called an emergency number",
        "the emergency call first, on its own"
    );
    // An urgent kind is never folded (qa-35-s2-code L2): the lockout goes on its own, next.
    assert_eq!(pushed[1].0, "Parent PIN locked on Anna");
    let count = |title: &str| pushed.iter().filter(|(t, _)| t == title).count();
    assert_eq!(count("New notifications"), 1);
    assert_eq!(count("Parent PIN used on Anna"), 2);
    let summary = pushed
        .iter()
        .find(|(t, _)| t == "New notifications")
        .unwrap();
    assert!(summary.1.starts_with('s'));
    let tags: std::collections::HashSet<&String> = pushed.iter().map(|(_, tag)| tag).collect();
    assert_eq!(tags.len(), 5, "unique tags: nothing replaces another");
    assert_eq!(
        scalar(
            &app,
            "SELECT COUNT(*) FROM push_deliveries WHERE reason = 'folded'"
        )
        .await,
        18
    );
    // The two sent are the newest.
    let newest = scalar(
        &app,
        "SELECT MAX(id) FROM parent_events WHERE kind = 'pin_used'",
    )
    .await;
    let sent = scalar(
        &app,
        &format!("SELECT status = 'accepted' FROM push_deliveries WHERE event_id = {newest}"),
    )
    .await;
    assert_eq!(sent, 1);
    let page = pages(&app, &cookie).await;
    assert!(page.contains("In a summary"));
}

/// §7: the receipt needs no session, only the delivery's token; a repeat is harmless; clicked
/// marks the event read; cross-site and oversized are refused; misses count against the client.
#[tokio::test]
async fn the_receipt() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    subscribe(&app, &cookie, ENDPOINT).await;
    let anna = phone(&app, "Anna").await;
    let event = raise(&app, &anna.unseen_boot(1, None)).await;
    send(&app).await;
    let request = app.pushes.take().remove(0);
    let receipts = "SELECT COUNT(*) FROM push_deliveries WHERE receipt_at IS NOT NULL";

    let data = &payload(&request)["notification"]["data"];
    let wrong = json!({ "d": data["d"], "t": "AAAAAAAAAAAAAAAAAAAAAA", "a": "shown" }).to_string();
    assert_eq!(
        post_receipt(&app, &wrong, "same-origin").await,
        StatusCode::NOT_FOUND
    );
    // A receipt isn't the CSRF guard's business (2026-10-19: Safari's worker sends none of the
    // metadata): a cross-site one reaches the handler, where only the token counts.
    assert_eq!(
        post_receipt(&app, &wrong, "cross-site").await,
        StatusCode::NOT_FOUND
    );
    let big = format!("{{\"d\":1,\"t\":\"{}\",\"a\":\"shown\"}}", "x".repeat(1100));
    assert_eq!(
        post_receipt(&app, &big, "same-origin").await,
        StatusCode::PAYLOAD_TOO_LARGE
    );
    assert_eq!(scalar(&app, receipts).await, 0);

    // No cookie: the token is enough. Twice is the same.
    for _ in 0..2 {
        assert_eq!(
            post_receipt(&app, &receipt_of(&request, "shown"), "same-origin").await,
            StatusCode::NO_CONTENT
        );
    }
    assert_eq!(scalar(&app, receipts).await, 1);
    assert_eq!(
        scalar(
            &app,
            "SELECT COUNT(*) FROM push_subscriptions WHERE last_receipt_at IS NOT NULL"
        )
        .await,
        1
    );
    let unread =
        format!("SELECT NOT EXISTS (SELECT 1 FROM parent_event_reads WHERE event_id = {event})");
    assert_eq!(scalar(&app, &unread).await, 1);
    assert_eq!(
        post_receipt(&app, &receipt_of(&request, "clicked"), "same-origin").await,
        StatusCode::NO_CONTENT
    );
    assert_eq!(scalar(&app, &unread).await, 0, "clicked marks it read");
    assert_eq!(
        scalar(
            &app,
            "SELECT COUNT(*) FROM push_deliveries WHERE clicked_at IS NOT NULL"
        )
        .await,
        1
    );
    // A click is a tap as the `?push=` marker's: the device was opened (server round 5 §2).
    assert_eq!(
        scalar(
            &app,
            "SELECT COUNT(*) FROM push_subscriptions WHERE last_opened_at IS NOT NULL"
        )
        .await,
        1
    );
    let page = pages(&app, &cookie).await;
    assert!(page.contains("Opened on iPhone · Safari"));

    // Misses count: after 30 the client is refused, even with a good token.
    for _ in 0..29 {
        post_receipt(&app, &wrong, "same-origin").await;
    }
    assert_eq!(
        post_receipt(&app, &receipt_of(&request, "shown"), "same-origin").await,
        StatusCode::TOO_MANY_REQUESTS
    );
}

/// Apple's service shows a declarative push itself and no receipt ever comes: its acceptance is
/// the delivery (`reason` 'declarative'), never "may not be showing", "Last delivered" on its row
/// (the user, 2026-10-20: 201 = delivered).
#[tokio::test]
async fn an_apple_acceptance_is_the_delivery() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    subscribe(&app, &cookie, ENDPOINT).await;
    let anna = phone(&app, "Anna").await;
    // Three (a fourth would fold them into a summary): the "may not be showing" threshold.
    for n in 1..=3 {
        raise(&app, &anna.unseen_boot(n, None)).await;
    }
    send(&app).await;
    assert_eq!(
        scalar(
            &app,
            "SELECT COUNT(*) FROM push_deliveries WHERE status = 'accepted' \
             AND reason = 'declarative' AND receipt_at IS NULL"
        )
        .await,
        3
    );
    let inbox = pages(&app, &cookie).await;
    assert!(!inbox.contains("May not be showing"), "{inbox}");
    assert!(inbox.contains("Delivered to iPhone · Safari"));
    assert!(inbox.contains("Last delivered"));
    let devices = app.get_page("/devices", &cookie).await.text();
    assert!(!devices.contains("id=\"push-warning\""));
    // A receipt that does come (another browser, or `PUSH_PAYLOAD=classic`) still wins the words.
    let last = app.pushes.take().pop().unwrap();
    assert_eq!(
        post_receipt(&app, &receipt_of(&last, "shown"), "same-origin").await,
        StatusCode::NO_CONTENT
    );
    let inbox = pages(&app, &cookie).await;
    assert!(inbox.contains("Shown on iPhone · Safari"));
    assert!(inbox.contains("Last shown"));
}

/// §6: three pushes without a receipt, or one a day old, warn on /notifications and the Devices
/// list; a receipt for any clears it. A browser whose worker shows the push (Firefox here - Apple's
/// service shows it itself, [an_apple_acceptance_is_the_delivery]).
#[tokio::test]
async fn health_and_its_warnings() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    subscribe(
        &app,
        &cookie,
        "https://updates.push.services.mozilla.com/wpush/v2/fake-health",
    )
    .await;
    let anna = phone(&app, "Anna").await;
    let warned = || async {
        let inbox = pages(&app, &cookie).await;
        let devices = app.get_page("/devices", &cookie).await.text();
        (
            inbox.contains("data-warning=\"not-showing\"") && inbox.contains("May not be showing"),
            devices.contains("id=\"push-warning\""),
        )
    };
    let page = pages(&app, &cookie).await;
    assert!(page.contains("Nothing sent yet"));
    for n in 1..=2 {
        raise(&app, &anna.unseen_boot(n, None)).await;
    }
    send(&app).await;
    assert_eq!(warned().await, (false, false));
    raise(&app, &anna.unseen_boot(3, None)).await;
    send(&app).await;
    assert_eq!(warned().await, (true, true));
    let devices = app.get_page("/devices", &cookie).await.text();
    assert!(devices.contains("Notifications may not be showing on iPhone · Safari"));
    assert!(devices.contains("href=\"/notifications/settings?from=devices#devices\""));

    // A receipt for the newest clears it.
    let last = app.pushes.take().pop().unwrap();
    assert_eq!(
        post_receipt(&app, &receipt_of(&last, "shown"), "same-origin").await,
        StatusCode::NO_CONTENT
    );
    assert_eq!(warned().await, (false, false));
    let page = pages(&app, &cookie).await;
    assert!(page.contains("Last shown"));

    // One unconfirmed push, a day old: the warning again.
    raise(&app, &anna.unseen_boot(4, None)).await;
    send(&app).await;
    sqlx::query("UPDATE push_subscriptions SET last_receipt_at = datetime('now', '-26 hours')")
        .execute(&app.db)
        .await
        .unwrap();
    sqlx::query(
        "UPDATE push_deliveries SET accepted_at = datetime('now', '-25 hours') \
         WHERE id = (SELECT MAX(id) FROM push_deliveries)",
    )
    .execute(&app.db)
    .await
    .unwrap();
    assert_eq!(warned().await, (true, true));
}

/// §6: Send a Test is a visible push, one per 30 s; Remove ends a device's notifications. Both
/// come back to #devices.
#[tokio::test]
async fn send_a_test_and_remove() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    subscribe(&app, &cookie, ENDPOINT).await;
    let id = scalar(&app, "SELECT id FROM push_subscriptions").await;
    let test_uri = format!("/notifications/subscriptions/{id}/test");

    let res = app
        .request_form(
            Method::POST,
            &test_uri,
            Some(&cookie),
            &[("from", "settings")],
        )
        .await;
    assert_eq!(
        res.location(),
        Some(
            format!("/notifications/settings?from=settings&notice=test_sent&sub={id}#devices")
                .as_str()
        )
    );
    app.assert_lands(&res, &cookie).await;
    assert!(app.state.push.woken().await);
    let again = app
        .request_form(
            Method::POST,
            &test_uri,
            Some(&cookie),
            &[("from", "settings")],
        )
        .await;
    assert!(again.location().unwrap().contains("notice=test_wait"));
    send(&app).await;
    let requests = app.pushes.take();
    assert_eq!(requests.len(), 1);
    let n = &payload(&requests[0])["notification"];
    assert_eq!(n["title"], "Test from Vibb Phone");
    assert_eq!(
        n["body"],
        "If you see this, notifications work on iPhone · Safari"
    );
    assert!(n["tag"].as_str().unwrap().starts_with('t'));
    assert_eq!(
        (requests[0].header("TTL"), requests[0].header("Urgency")),
        (Some("600"), Some("high"))
    );

    let res = app
        .request_form(
            Method::POST,
            &format!("/notifications/subscriptions/{id}/delete"),
            Some(&cookie),
            &[("from", "devices")],
        )
        .await;
    assert_eq!(
        res.location(),
        Some("/notifications/settings?from=devices&notice=removed#devices")
    );
    app.assert_lands(&res, &cookie).await;
    assert_eq!(
        scalar(&app, "SELECT COUNT(*) FROM push_subscriptions").await,
        0
    );
    let res = app
        .request_form(Method::POST, &test_uri, Some(&cookie), &[])
        .await;
    assert!(res.location().unwrap().contains("notice=test_gone"));
}

/// §7: Sign Out Everywhere and `reset-2fa` delete every subscription; a backup's copy holds none.
#[tokio::test]
async fn sign_out_everywhere_reset_2fa_and_backups_hold_no_subscription() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    subscribe(&app, &cookie, ENDPOINT).await;
    let anna = phone(&app, "Anna").await;
    raise(&app, &anna.unseen_boot(1, None)).await;
    send(&app).await;

    let dir = tempfile::tempdir().unwrap();
    let target = dir.path().join("snapshot.db");
    crate::handlers::backups::snapshot(&app.db, &dir.path().join("scratch.db"), &target)
        .await
        .unwrap();
    let copy = sqlx::SqlitePool::connect(&format!("sqlite://{}", target.display()))
        .await
        .unwrap();
    let rows: (i64, i64) = sqlx::query_as(
        "SELECT (SELECT COUNT(*) FROM push_subscriptions), (SELECT COUNT(*) FROM push_deliveries)",
    )
    .fetch_one(&copy)
    .await
    .unwrap();
    copy.close().await;
    assert_eq!(rows, (0, 0));
    let bytes = std::fs::read(&target).unwrap();
    assert!(
        !bytes
            .windows(ENDPOINT.len())
            .any(|w| w == ENDPOINT.as_bytes())
    );

    let res = app
        .request_form(
            Method::POST,
            "/account/sign-out-everywhere",
            Some(&cookie),
            &[],
        )
        .await;
    assert_eq!(res.location(), Some("/login"));
    assert_eq!(
        scalar(&app, "SELECT COUNT(*) FROM push_subscriptions").await,
        0
    );

    let cookie = app.admin_cookie().await;
    subscribe(&app, &cookie, ENDPOINT).await;
    assert_eq!(
        scalar(&app, "SELECT COUNT(*) FROM push_subscriptions").await,
        1
    );
    std::fs::create_dir_all(&*app.state.data_dir).unwrap();
    crate::admin_auth::reset_2fa(&app.db, "parent", &app.state.data_dir)
        .await
        .unwrap();
    assert_eq!(
        scalar(&app, "SELECT COUNT(*) FROM push_subscriptions").await,
        0
    );
}

/// Security review M1: a new password and "Reset two-factor login" on Account delete every
/// subscription of the account, as Sign Out Everywhere does - one a stolen session made too.
#[tokio::test]
async fn a_new_password_and_a_2fa_reset_hold_no_subscription() {
    const PASSWORD: &str = "correct horse battery staple";
    const NEW_PASSWORD: &str = "a brand new long password";
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let stolen = app.admin_cookie().await;
    let res = subscribe(&app, &stolen, "https://web.push.apple.com/QK1-fake-stolen").await;
    assert!(res.location().unwrap().contains("notice=push_on"));
    subscribe(&app, &cookie, ENDPOINT).await;
    assert_eq!(
        scalar(&app, "SELECT COUNT(*) FROM push_subscriptions").await,
        2
    );

    let res = app
        .request_form(
            Method::POST,
            "/account/password",
            Some(&cookie),
            &[
                ("current_password", PASSWORD),
                ("new_password", NEW_PASSWORD),
                ("confirm_password", NEW_PASSWORD),
            ],
        )
        .await;
    super::assert_redirect(&res, "/account?changed=password", Some("password"));
    assert_eq!(
        scalar(&app, "SELECT COUNT(*) FROM push_subscriptions").await,
        0
    );
    let page = app.get_page("/account?changed=password", &cookie).await;
    assert!(
        page.text().contains("notifications are off"),
        "{}",
        page.text()
    );

    // This session goes on; it turns notifications on again, then resets two-factor login.
    let res = subscribe(&app, &cookie, ENDPOINT).await;
    assert!(res.location().unwrap().contains("notice=push_on"));
    assert_eq!(
        scalar(&app, "SELECT COUNT(*) FROM push_subscriptions").await,
        1
    );
    let res = app
        .request_form(
            Method::POST,
            "/account/reset-2fa",
            Some(&cookie),
            &[("current_password", NEW_PASSWORD)],
        )
        .await;
    assert_eq!(res.location(), Some("/auth/setup-2fa"));
    assert_eq!(
        scalar(&app, "SELECT COUNT(*) FROM push_subscriptions").await,
        0
    );
}

/// Security review M1: a subscription keeps its session's epoch; once the account's moved past it
/// without the explicit delete (a subscribe in flight while the sessions ended, or a sign-out
/// whose delete failed - here `bump_epoch` alone), Notification Settings shows it as gone, it
/// doesn't count toward the cap, and the sender sends it nothing and deletes it
/// (qa-pwa-fixes-a-code Q-1). A subscription made after the move is sent to.
#[tokio::test]
async fn a_subscription_from_a_signed_out_session_is_not_sent_to() {
    const OLD: &str = "https://web.push.apple.com/QK1-fake-old-session";
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    subscribe(&app, &cookie, OLD).await;
    let admin = app.an_admin().await;
    let epoch = crate::admin_auth::bump_epoch(&app.db, admin.id)
        .await
        .unwrap();
    assert_eq!(
        app.get_page("/devices", &cookie).await.location(),
        Some("/login"),
        "the old session is signed out"
    );
    let cookie = app.admin_cookie().await;
    let settings = app
        .get_page("/notifications/settings", &cookie)
        .await
        .text();
    assert!(!settings.contains("data-subscription="), "{settings}");
    assert!(
        !settings.contains(&crate::push::endpoint_hash(OLD)),
        "This Device shows Off"
    );
    // The old row doesn't count toward the cap: as many new browsers as ever still fit.
    for n in 0..crate::push::MAX_SUBSCRIPTIONS {
        let res = subscribe(&app, &cookie, &format!("{ENDPOINT}-{n}")).await;
        assert!(res.location().unwrap().contains("notice=push_on"), "{n}");
    }
    sqlx::query("DELETE FROM push_subscriptions WHERE endpoint LIKE '%-fake-endpoint-%'")
        .execute(&app.db)
        .await
        .unwrap();
    subscribe(&app, &cookie, ENDPOINT).await;
    let stored: Vec<(String, i64)> =
        sqlx::query_as("SELECT endpoint, session_epoch FROM push_subscriptions ORDER BY id")
            .fetch_all(&app.db)
            .await
            .unwrap();
    assert_eq!(
        stored,
        [(OLD.to_string(), epoch - 1), (ENDPOINT.to_string(), epoch)]
    );

    let anna = phone(&app, "Anna").await;
    raise(&app, &anna.unseen_boot(1, None)).await;
    send(&app).await;
    let requests = app.pushes.take();
    assert_eq!(
        requests
            .iter()
            .map(|r| r.endpoint.as_str())
            .collect::<Vec<_>>(),
        [ENDPOINT]
    );
    let left: Vec<String> = sqlx::query_scalar("SELECT endpoint FROM push_subscriptions")
        .fetch_all(&app.db)
        .await
        .unwrap();
    assert_eq!(left, [ENDPOINT]);
    assert_eq!(
        scalar(
            &app,
            "SELECT COUNT(*) FROM push_deliveries d \
             LEFT JOIN push_subscriptions s ON s.id = d.subscription_id WHERE s.id IS NULL"
        )
        .await,
        0,
        "its deliveries went with it"
    );
}

/// Sign Out with this browser's endpoint (the user, 2026-10-25; `static/register-sw.js` fills it)
/// deletes that subscription, only the signed-in parent's; without the field it signs out as
/// before and every subscription stays.
#[tokio::test]
async fn sign_out_removes_this_browsers_subscription_only() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let pappa = app.admin_cookie_as("pappa", "Pappa").await;
    subscribe(&app, &cookie, ENDPOINT).await;
    let count = || scalar(&app, "SELECT COUNT(*) FROM push_subscriptions");

    // Another parent's sign-out naming this endpoint: signed out, the row stays.
    let res = app
        .request_form(
            Method::POST,
            "/logout",
            Some(&pappa),
            &[("endpoint", ENDPOINT)],
        )
        .await;
    assert_eq!(res.location(), Some("/login"));
    assert_eq!(
        app.get_page("/devices", &pappa).await.location(),
        Some("/login")
    );
    assert_eq!(count().await, 1);

    // No field (no JS, no subscription) or an empty one: as before.
    for fields in [&[][..], &[("endpoint", "")][..]] {
        let cookie = app.admin_cookie().await;
        let res = app
            .request_form(Method::POST, "/logout", Some(&cookie), fields)
            .await;
        assert_eq!(res.location(), Some("/login"));
        assert_eq!(count().await, 1);
    }

    let res = app
        .request_form(
            Method::POST,
            "/logout",
            Some(&cookie),
            &[("endpoint", ENDPOINT)],
        )
        .await;
    assert_eq!(res.location(), Some("/login"));
    assert_eq!(
        app.get_page("/devices", &cookie).await.location(),
        Some("/login")
    );
    assert_eq!(count().await, 0);
}

/// 0095 gives every existing subscription its account's epoch (qa-pwa-fixes-a-code Q-5): without
/// the backfill a parent who ever changed the password would lose every device at the first pass.
#[tokio::test]
async fn migration_0095_keeps_existing_subscriptions() {
    let dir = tempfile::tempdir().unwrap();
    let options = sqlx::sqlite::SqliteConnectOptions::new()
        .filename(dir.path().join("m.db"))
        .create_if_missing(true)
        .foreign_keys(true);
    let db = sqlx::SqlitePool::connect_with(options).await.unwrap();
    for migration in crate::MIGRATOR.iter().filter(|m| m.version <= 94) {
        sqlx::raw_sql(&migration.sql).execute(&db).await.unwrap();
    }
    sqlx::raw_sql(
        "INSERT INTO admin_users (id, username, password_hash, session_epoch) \
           VALUES (1, 'mamma', 'x', 3), (2, 'pappa', 'x', 0); \
         INSERT INTO push_subscriptions (admin_id, endpoint, p256dh, auth, origin, label, vapid_fp) \
           VALUES (1, 'https://web.push.apple.com/QK1-fake-mamma', X'00', X'00', 'https://a', 'a', 'f'), \
                  (2, 'https://web.push.apple.com/QK1-fake-pappa', X'00', X'00', 'https://a', 'a', 'f');",
    )
    .execute(&db)
    .await
    .unwrap();
    let last = crate::MIGRATOR.iter().find(|m| m.version == 95).unwrap();
    sqlx::raw_sql(&last.sql).execute(&db).await.unwrap();
    crate::push::forget_stale(&db).await.unwrap();
    let rows: Vec<(i64, i64)> =
        sqlx::query_as("SELECT admin_id, session_epoch FROM push_subscriptions ORDER BY admin_id")
            .fetch_all(&db)
            .await
            .unwrap();
    assert_eq!(rows, [(1, 3), (2, 0)]);
}

/// qa-pwa-fixes-a-code Q-1: three wrong passwords at a form that asks for it again end every
/// session and every device's notifications at once. After signing in again Notification Settings
/// shows them off, and the next event is sent to nobody.
#[tokio::test]
async fn a_re_auth_lockout_turns_notifications_off_at_once() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    subscribe(&app, &cookie, ENDPOINT).await;
    for _ in 0..crate::admin_auth::REAUTH_MAX_WRONG {
        app.request_form(
            Method::POST,
            "/account/reset-2fa",
            Some(&cookie),
            &[("current_password", "not the password")],
        )
        .await;
    }
    assert_eq!(
        app.get_page("/devices", &cookie).await.location(),
        Some("/login"),
        "signed out"
    );
    assert_eq!(
        scalar(&app, "SELECT COUNT(*) FROM push_subscriptions").await,
        0
    );

    let cookie = app.admin_cookie().await;
    let settings = app
        .get_page("/notifications/settings", &cookie)
        .await
        .text();
    assert!(!settings.contains("data-subscription="), "{settings}");
    assert!(!settings.contains(&crate::push::endpoint_hash(ENDPOINT)));
    let anna = phone(&app, "Anna").await;
    raise(&app, &anna.unseen_boot(1, None)).await;
    send(&app).await;
    assert!(app.pushes.take().is_empty());
}

/// §4.1: the key file is made once, 0600; a damaged one turns push off and stays as it was; a
/// subscription made for another key is turned off at startup.
#[tokio::test]
async fn the_key_file_and_a_changed_key() {
    let dir = tempfile::tempdir().unwrap();
    let file = dir.path().join("data").join("keys").join("vapid-p256.pk8");
    let made = crate::push::load_key(&file).unwrap();
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        let mode = std::fs::metadata(&file).unwrap().permissions().mode();
        assert_eq!(mode & 0o777, 0o600);
    }
    assert_eq!(
        crate::push::load_key(&file).unwrap().fingerprint,
        made.fingerprint
    );
    let damaged = dir.path().join("damaged.pk8");
    std::fs::write(&damaged, b"not a key at all").unwrap();
    let err = crate::push::load_key(&damaged).unwrap_err();
    assert!(
        err.contains("damaged.pk8") && err.contains("remove it"),
        "{err}"
    );
    assert_eq!(std::fs::read(&damaged).unwrap(), b"not a key at all");

    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    subscribe(&app, &cookie, ENDPOINT).await;
    let key = app.state.push.key.clone().unwrap();
    let active = "SELECT COUNT(*) FROM push_subscriptions WHERE state = 'active'";
    crate::push::forget_other_keys(&app.db, &key).await;
    assert_eq!(scalar(&app, active).await, 1);
    crate::push::forget_other_keys(&app.db, &made).await;
    assert_eq!(scalar(&app, active).await, 0);
}

/// The page's text with Askama's apostrophes back.
fn words(html: String) -> String {
    html.replace("&#x27;", "'").replace("&#39;", "'")
}

/// qa-35-s2-code M1, N2: a device whose pushes never reach the push service says so - "Sending…"
/// right after Send a Test, then "Not getting through" with the reason and the orange rows once
/// the retries ran past the test's 10-minute TTL (failed at once, never a retry scheduled past
/// it); an accepted push ends it.
#[tokio::test]
async fn a_device_whose_pushes_dont_get_through_says_so() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    subscribe(&app, &cookie, ENDPOINT).await;
    let id = scalar(&app, "SELECT id FROM push_subscriptions").await;
    let res = app
        .request_form(
            Method::POST,
            &format!("/notifications/subscriptions/{id}/test"),
            Some(&cookie),
            &[("from", "devices")],
        )
        .await;
    let page = app.get_page(res.location().unwrap(), &cookie).await.text();
    assert!(page.contains("A test is on its way."));
    assert!(page.contains("Sending…"), "the row says a push waits");

    // The push service out of reach: tried at once, after 1 min and after 5 more; the next
    // retry (30 min) would be past the 600 s TTL, so the third failure ends it.
    for _ in 0..3 {
        app.pushes.fail();
    }
    let mut at = chrono::Utc::now();
    for (wait, status) in [(0, "queued"), (60, "queued"), (300, "failed")] {
        at += chrono::Duration::seconds(wait);
        send_at(&app, at).await;
        let now: String = sqlx::query_scalar("SELECT status FROM push_deliveries")
            .fetch_one(&app.db)
            .await
            .unwrap();
        assert_eq!(now, status, "after {wait} s");
    }
    assert_eq!(app.pushes.take().len(), 3);
    let page = words(pages(&app, &cookie).await);
    assert!(page.contains("Not getting through"));
    assert!(page.contains("The push service couldn't be reached"));
    assert!(page.contains("Notifications aren't reaching iPhone · Safari"));
    assert!(!page.contains("Nothing sent yet"));
    let devices = app.get_page("/devices", &cookie).await.text();
    assert!(devices.contains("id=\"push-warning\""));

    // A refusal says which; an accepted push afterwards ends the state.
    app.pushes.answer(400, Some("BadDeviceToken"), None);
    let anna = phone(&app, "Anna").await;
    raise(&app, &anna.unseen_boot(1, None)).await;
    send(&app).await;
    let page = words(pages(&app, &cookie).await);
    assert!(page.contains("The push service refused it (400)"));
    raise(&app, &anna.unseen_boot(2, None)).await;
    send(&app).await;
    let page = words(pages(&app, &cookie).await);
    assert!(!page.contains("Not getting through"));
    let devices = app.get_page("/devices", &cookie).await.text();
    assert!(!devices.contains("id=\"push-warning\""));
}

/// qa-35-s2-code L3: a receipt for a push whose request looked failed (the push service took it)
/// marks it accepted, and the retry never goes; a late receipt for a token a retry replaced is a
/// 404 that doesn't count against the parent's device.
#[tokio::test]
async fn a_receipt_before_the_retry_ends_it() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    subscribe(&app, &cookie, ENDPOINT).await;
    let anna = phone(&app, "Anna").await;
    app.pushes.fail();
    let event = raise(&app, &anna.unseen_boot(1, None)).await;
    let now = chrono::Utc::now();
    send_at(&app, now).await;
    let first = app.pushes.take().remove(0);
    assert_eq!(
        post_receipt(&app, &receipt_of(&first, "shown"), "same-origin").await,
        StatusCode::NO_CONTENT
    );
    send_at(&app, now + chrono::Duration::seconds(61)).await;
    assert!(app.pushes.take().is_empty(), "never sent again");
    assert_eq!(delivery_of(&app, event).await.0, "accepted");
    let page = pages(&app, &cookie).await;
    assert!(page.contains("Shown on iPhone · Safari"));

    // The receipt arrives only after the retry went out with a new token.
    app.pushes.fail();
    raise(&app, &anna.unseen_boot(2, None)).await;
    send_at(&app, now).await;
    let stale = app.pushes.take().remove(0);
    send_at(&app, now + chrono::Duration::seconds(61)).await;
    let retried = app.pushes.take().remove(0);
    for _ in 0..40 {
        assert_eq!(
            post_receipt(&app, &receipt_of(&stale, "shown"), "same-origin").await,
            StatusCode::NOT_FOUND
        );
    }
    // Not counted as misses: the current token still works.
    assert_eq!(
        post_receipt(&app, &receipt_of(&retried, "shown"), "same-origin").await,
        StatusCode::NO_CONTENT
    );
}

/// qa-35-s2-code N3: an `http://` Origin (a dev install on 127.0.0.1) is never the JWT's `sub` -
/// RFC 8292 takes `mailto:` or `https:` only; the push still goes, without one.
#[tokio::test]
async fn an_http_origin_is_no_jwt_subject() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    post_as_browser(
        &app,
        "/notifications/subscriptions",
        Some(&cookie),
        Some("http://127.0.0.1:3100"),
        &[
            ("endpoint", ENDPOINT),
            ("p256dh", test_browser::UA_PUBLIC),
            ("auth", test_browser::AUTH),
        ],
    )
    .await;
    let anna = phone(&app, "Anna").await;
    raise(&app, &anna.unseen_boot(1, None)).await;
    send(&app).await;
    let requests = app.pushes.take();
    let (_, claims, _) = vapid(&requests[0]);
    assert!(claims.get("sub").is_none(), "{claims}");
    assert_eq!(claims["aud"], "https://web.push.apple.com");
}

/// `(clicked, the subscription opened, the event read)` of a delivery.
async fn opened_state(app: &TestApp, delivery: i64) -> (bool, bool, Option<bool>) {
    sqlx::query_as(
        "SELECT d.clicked_at IS NOT NULL, s.last_opened_at IS NOT NULL, \
                (SELECT EXISTS (SELECT 1 FROM parent_event_reads r WHERE r.event_id = e.id) FROM parent_events e WHERE e.id = d.event_id) \
         FROM push_deliveries d JOIN push_subscriptions s ON s.id = d.subscription_id \
         WHERE d.id = ?",
    )
    .bind(delivery)
    .fetch_one(&app.db)
    .await
    .unwrap()
}

/// Server round 5 §1: Safari opens a declarative notification's `navigate` itself, so the URL
/// carries `?push=<delivery>`; a GET with it marks this admin's delivery opened (clicked, the
/// subscription's `last_opened_at`, the event read) and answers a 303 to the same URL without it.
/// A summary or a test has no event; another admin's delivery or garbage marks nothing; signed
/// out the marker is lost.
#[tokio::test]
async fn a_tap_on_a_notification_marks_it_opened() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    subscribe(&app, &cookie, ENDPOINT).await;
    let anna = phone(&app, "Anna").await;
    let event = raise(&app, &anna.unseen_boot(1, None)).await;
    send(&app).await;
    let delivery = scalar(&app, "SELECT MAX(id) FROM push_deliveries").await;
    let navigate = payload(&app.pushes.take()[0])["notification"]["navigate"]
        .as_str()
        .unwrap()
        .to_string();
    assert_eq!(
        navigate,
        format!(
            "{ORIGIN}/devices/{}/about?push={delivery}#diagnostics",
            anna.id
        )
    );
    // The fragment never reaches the server.
    let path = navigate
        .strip_prefix(ORIGIN)
        .unwrap()
        .split('#')
        .next()
        .unwrap();

    // Signed out: the login, nothing marked.
    let res = app.request(Method::GET, path, None, None).await;
    assert_eq!(res.location(), Some("/login"));
    assert_eq!(
        opened_state(&app, delivery).await,
        (false, false, Some(false))
    );
    // Another admin's delivery: the same 303, nothing marked.
    let other: i64 = sqlx::query_scalar(
        "INSERT INTO admin_users (username, password_hash) VALUES ('other', 'x') RETURNING id",
    )
    .fetch_one(&app.db)
    .await
    .unwrap();
    let foreign: i64 = sqlx::query_scalar(
        "INSERT INTO push_subscriptions (admin_id, endpoint, p256dh, auth, origin, label, vapid_fp) \
         VALUES (?, 'https://fcm.googleapis.com/fcm/send/other', x'00', x'00', ?, 'iPad', 'x') \
         RETURNING id",
    )
    .bind(other)
    .bind(ORIGIN)
    .fetch_one(&app.db)
    .await
    .unwrap();
    let theirs: i64 = sqlx::query_scalar(
        "INSERT INTO push_deliveries (subscription_id, kind, event_id, token_hash, status) \
         VALUES (?, 'event', ?, X'', 'accepted') RETURNING id",
    )
    .bind(foreign)
    .bind(event)
    .fetch_one(&app.db)
    .await
    .unwrap();
    let res = app
        .get_page(&format!("/devices/{}?push={theirs}", anna.id), &cookie)
        .await;
    assert_eq!(res.status, StatusCode::SEE_OTHER);
    assert_eq!(
        res.location(),
        Some(format!("/devices/{}", anna.id).as_str())
    );
    assert_eq!(
        opened_state(&app, theirs).await,
        (false, false, Some(false))
    );
    // Garbage: the other parameters kept, in order.
    let res = app
        .get_page("/notifications?from=music&push=x1&device=7", &cookie)
        .await;
    assert_eq!(res.location(), Some("/notifications?from=music&device=7"));

    // The tap: marked, and the page without the marker.
    let res = app.get_page(path, &cookie).await;
    assert_eq!(res.status, StatusCode::SEE_OTHER);
    assert_eq!(res.location(), Some(path.split('?').next().unwrap()));
    assert_eq!(opened_state(&app, delivery).await, (true, true, Some(true)));
    let inbox = app.get_page("/notifications", &cookie).await.text();
    // The line is this parent's devices only: the other parent's delivery of the same event isn't
    // one of them (design 62 §5, qa-62-step345-code P62b-1).
    assert!(inbox.contains("Opened on "), "{inbox}");
    assert!(!inbox.contains("Pushed to 2 devices"), "{inbox}");
    // A POST is never redirected by it.
    let res = app
        .request_form(
            Method::POST,
            &format!("/notifications/{event}/open?push={delivery}"),
            Some(&cookie),
            &[],
        )
        .await;
    assert_eq!(
        res.location(),
        Some(format!("/devices/{}/about#diagnostics", anna.id).as_str())
    );

    // A test has no event: clicked and opened only; an unread event stays unread.
    sqlx::query("UPDATE push_subscriptions SET last_opened_at = NULL")
        .execute(&app.db)
        .await
        .unwrap();
    let unread = raise(&app, &anna.unseen_boot(2, None)).await;
    let id = scalar(&app, "SELECT MIN(id) FROM push_subscriptions").await;
    app.request_form(
        Method::POST,
        &format!("/notifications/subscriptions/{id}/test"),
        Some(&cookie),
        &[],
    )
    .await;
    send(&app).await;
    let test = scalar(
        &app,
        "SELECT MAX(id) FROM push_deliveries WHERE kind = 'test'",
    )
    .await;
    let navigate = app
        .pushes
        .take()
        .iter()
        .map(|r| {
            payload(r)["notification"]["navigate"]
                .as_str()
                .unwrap()
                .to_string()
        })
        .find(|n| n.contains("/notifications/settings"))
        .unwrap();
    assert_eq!(
        navigate,
        format!("{ORIGIN}/notifications/settings?push={test}#devices")
    );
    let res = app
        .get_page(&format!("/notifications/settings?push={test}"), &cookie)
        .await;
    assert_eq!(res.location(), Some("/notifications/settings"));
    assert_eq!(opened_state(&app, test).await, (true, true, None));
    assert_eq!(
        scalar(
            &app,
            &format!(
                "SELECT NOT EXISTS (SELECT 1 FROM parent_event_reads WHERE event_id = {unread})"
            )
        )
        .await,
        1
    );
}

/// Server round 5 §2: the device row's quiet count - pushes delivered since the device last
/// confirmed or opened one; the basis outlives the deliveries' 30 days (`last_opened_at`).
#[tokio::test]
async fn the_device_row_counts_pushes_since_one_was_opened() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    subscribe(&app, &cookie, ENDPOINT).await;
    let anna = phone(&app, "Anna").await;
    let row = || async {
        let html = app
            .get_page("/notifications/settings", &cookie)
            .await
            .text();
        let at = html.find("data-sub>").expect("a device row") + "data-sub>".len();
        html[at..at + html[at..].find('<').unwrap()].to_string()
    };
    let now = chrono::Utc::now();
    for n in 1..=2 {
        raise(&app, &anna.unseen_boot(n, None)).await;
    }
    send_at(&app, now - chrono::Duration::minutes(10)).await;
    // An iPhone confirms nothing: never opened yet.
    assert!(
        row().await.ends_with(" · 2 not opened yet"),
        "{}",
        row().await
    );

    // Opened (the marker): nothing added; a later push counts.
    let first = scalar(&app, "SELECT MIN(id) FROM push_deliveries").await;
    app.get_page(&format!("/devices?push={first}"), &cookie)
        .await;
    assert!(!row().await.contains(" · "), "{}", row().await);
    raise(&app, &anna.unseen_boot(3, None)).await;
    send_at(&app, now + chrono::Duration::minutes(1)).await;
    assert!(
        row().await.ends_with(" · 1 since one was opened"),
        "{}",
        row().await
    );
    // The deliveries pruned after 30 days: the basis stays.
    sqlx::query("DELETE FROM push_deliveries WHERE id = ?")
        .bind(first)
        .execute(&app.db)
        .await
        .unwrap();
    assert!(
        row().await.ends_with(" · 1 since one was opened"),
        "{}",
        row().await
    );

    // A receipt is a basis too: a newer one counts nothing, an older one what came after it.
    sqlx::query(
        "UPDATE push_subscriptions SET last_opened_at = NULL, \
         last_receipt_at = datetime('now', '+5 minutes')",
    )
    .execute(&app.db)
    .await
    .unwrap();
    assert!(!row().await.contains(" · "), "{}", row().await);
    sqlx::query("UPDATE push_subscriptions SET last_receipt_at = datetime('now', '-1 hour')")
        .execute(&app.db)
        .await
        .unwrap();
    assert!(
        row().await.ends_with(" · 2 since one was opened"),
        "{}",
        row().await
    );
}

/// Server round 5 §5: `subscriptions.json` is this admin's device rows (badge and sub-line,
/// never an endpoint or a key), never stored; it needs a session.
#[tokio::test]
async fn the_device_rows_as_json() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    subscribe(&app, &cookie, ENDPOINT).await;
    let id = scalar(&app, "SELECT id FROM push_subscriptions").await;
    let res = app
        .get_page("/notifications/subscriptions.json", &cookie)
        .await;
    assert_eq!(res.status, StatusCode::OK);
    assert_eq!(
        res.headers
            .get(header::CACHE_CONTROL)
            .and_then(|v| v.to_str().ok()),
        Some("no-store")
    );
    let rows: Value = serde_json::from_slice(&res.body).unwrap();
    assert_eq!(
        rows,
        json!([{ "id": id, "badge": "On", "badge_class": "badge badge-on",
                 "sub": "Nothing sent yet" }])
    );
    let res = app
        .request(Method::GET, "/notifications/subscriptions.json", None, None)
        .await;
    assert_eq!(res.location(), Some("/login"));
}

// ---- Design 62 §5: each parent's switches, reads and badge -------------------------------------

const PAPPAS_ENDPOINT: &str = "https://web.push.apple.com/QK1-pappas-fake-endpoint";

/// Mamma's and Pappa's subscriptions; Pappa's switches with only the Parent PIN on.
async fn two_parents(app: &TestApp) -> (String, String) {
    let mamma = app.admin_cookie().await;
    subscribe(app, &mamma, ENDPOINT).await;
    let pappa = app.admin_cookie_as("pappa", "Pappa").await;
    subscribe(app, &pappa, PAPPAS_ENDPOINT).await;
    app.request_form(
        Method::POST,
        "/notifications/settings",
        Some(&pappa),
        &[("push_pin_used", "on")],
    )
    .await;
    (mamma, pappa)
}

/// Each parent's own switches decide their push; the emergency call goes to every parent; a
/// removed parent gets nothing.
#[tokio::test]
async fn push_follows_each_parents_switches() {
    let app = TestApp::new().await;
    two_parents(&app).await;
    let anna = phone(&app, "Anna").await;
    raise(&app, &anna.unseen_boot(1, None)).await;
    let to = |app: &TestApp| {
        let mut endpoints: Vec<String> =
            app.pushes.take().into_iter().map(|r| r.endpoint).collect();
        endpoints.sort();
        endpoints
    };
    send(&app).await;
    assert_eq!(to(&app), [ENDPOINT], "Pappa turned it off");
    raise(&app, &anna.emergency_call(anna.now)).await;
    send(&app).await;
    assert_eq!(
        to(&app),
        [ENDPOINT, PAPPAS_ENDPOINT],
        "the emergency call to both"
    );
    // Removed (by SQL; the subscription left on purpose): nothing for him, not even the call.
    sqlx::query("UPDATE admin_users SET removed_at = datetime('now') WHERE username = 'pappa'")
        .execute(&app.db)
        .await
        .unwrap();
    raise(
        &app,
        &anna.emergency_call(anna.now - chrono::Duration::minutes(5)),
    )
    .await;
    send(&app).await;
    assert_eq!(to(&app), [ENDPOINT]);
}

/// A tapped push reads the event for that subscription's parent only, and each push's badge is
/// its parent's unread count.
#[tokio::test]
async fn push_tap_reads_for_that_parent_and_badges_their_count() {
    let app = TestApp::new().await;
    let (mamma, _) = two_parents(&app).await;
    let anna = phone(&app, "Anna").await;
    // Mamma has read one older event; Pappa none.
    let older = raise(&app, &anna.unseen_boot(1, None)).await;
    app.pushes.take();
    sqlx::query("DELETE FROM push_deliveries")
        .execute(&app.db)
        .await
        .unwrap();
    app.get_page("/notifications", &mamma).await;
    let call = raise(&app, &anna.emergency_call(anna.now)).await;
    send(&app).await;
    let mut badges: Vec<(String, i64)> = app
        .pushes
        .take()
        .iter()
        .map(|r| {
            (
                r.endpoint.clone(),
                payload(r)["notification"]["data"]["b"].as_i64().unwrap(),
            )
        })
        .collect();
    badges.sort();
    assert_eq!(
        badges,
        [(ENDPOINT.to_string(), 1), (PAPPAS_ENDPOINT.to_string(), 2)]
    );
    // Pappa taps his: read for him, still unread for Mamma.
    let (pappa_id, delivery): (i64, i64) = sqlx::query_as(
        "SELECT s.admin_id, d.id FROM push_deliveries d \
         JOIN push_subscriptions s ON s.id = d.subscription_id WHERE s.endpoint = ?",
    )
    .bind(PAPPAS_ENDPOINT)
    .fetch_one(&app.db)
    .await
    .unwrap();
    assert!(
        crate::push::opened(&app.db, pappa_id, delivery)
            .await
            .unwrap()
    );
    let read_by: Vec<i64> =
        sqlx::query_scalar("SELECT admin_id FROM parent_event_reads WHERE event_id = ?")
            .bind(call)
            .fetch_all(&app.db)
            .await
            .unwrap();
    assert_eq!(read_by, [pappa_id]);
    let _ = older;
}

/// P62b-1: the inbox's delivery line is the parent's own devices. Pappa turned the kind off and got
/// no push: his row names none; Mamma's names hers.
#[tokio::test]
async fn inbox_delivery_line_is_each_parents() {
    let app = TestApp::new().await;
    let (mamma, pappa) = two_parents(&app).await;
    let anna = phone(&app, "Anna").await;
    raise(&app, &anna.unseen_boot(1, None)).await;
    send(&app).await;
    let to: Vec<String> = app.pushes.take().into_iter().map(|r| r.endpoint).collect();
    assert_eq!(to, [ENDPOINT], "only Mamma's went");
    let lines = |html: String| -> Vec<String> {
        html.lines()
            .filter(|l| {
                l.contains("Pushed to") || l.contains("Delivered to") || l.contains("Shown on")
            })
            .map(str::to_string)
            .collect()
    };
    let pappas = lines(app.get_page("/notifications", &pappa).await.text());
    assert!(pappas.is_empty(), "{pappas:?}");
    let mammas = lines(app.get_page("/notifications", &mamma).await.text());
    assert_eq!(mammas.len(), 1, "{mammas:?}");
}
