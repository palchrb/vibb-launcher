//! Design 59 step 1 (`docs/design/59-android-keyguard-design.md` §1, §2.1): the Child PIN sealed at
//! every save, the PIN the phone last confirmed kept beside it, `kid_lock.keyguard`/`pin_rev`, and
//! the one-shot `set_kid_pin` - when it is queued (the parent's saves and the status intake), what
//! it carries when it goes out, and when it is cancelled. The switch itself is step 2: these tests
//! set `kid_keyguard` in the database.

use axum::http::{Method, StatusCode, header};
use serde_json::{Value, json};

use super::TestApp;

const CAPS: [&str; 3] = ["pin_lock_v1", "command_receipt_v1", "android_keyguard_v1"];

/// An enrolled phone with a Parent PIN: `(app, cookie, id, token)`.
async fn setup() -> (TestApp, String, i64, String) {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("phone").await;
    let res = app
        .request_form(
            Method::POST,
            &format!("/devices/{id}/parent-pin"),
            Some(&cookie),
            &[("new_pin", "246810")],
        )
        .await;
    assert_eq!(res.status, StatusCode::SEE_OTHER);
    (app, cookie, id, token)
}

/// A Child PIN save through `router` (the app's own unless given); the redirect's notice.
async fn save_via(
    app: &TestApp,
    router: &axum::Router,
    cookie: &str,
    id: i64,
    pin: &str,
) -> String {
    let body = format!("new_kid_pin={pin}");
    let request = axum::http::Request::builder()
        .method(Method::POST)
        .uri(format!("/devices/{id}/kid-lock"))
        .header(header::CONTENT_TYPE, "application/x-www-form-urlencoded")
        .header(header::COOKIE, cookie)
        .body(axum::body::Body::from(body))
        .unwrap();
    let res = app.send_via(router, super::LOOPBACK_PEER, request).await;
    assert_eq!(res.status, StatusCode::SEE_OTHER, "{}", res.text());
    let location = res.location().unwrap();
    location
        .split("notice=")
        .nth(1)
        .and_then(|rest| rest.split('#').next())
        .unwrap()
        .to_string()
}

async fn save(app: &TestApp, cookie: &str, id: i64, pin: &str) -> String {
    save_via(app, &app.router, cookie, id, pin).await
}

#[derive(Debug, PartialEq, Eq)]
struct Kept {
    keyguard: String,
    /// The sealed PIN and the confirmed one, opened.
    pin: Option<String>,
    prev: Option<String>,
    prev_sealed: bool,
    prev_rev: Option<i64>,
    rev: i64,
}

async fn kept(app: &TestApp, id: i64) -> Kept {
    let (keyguard, sealed, prev_sealed, prev_rev, rev): (
        String,
        Option<String>,
        Option<String>,
        Option<i64>,
        i64,
    ) = sqlx::query_as(
        "SELECT kid_keyguard, kid_pin_sealed, kid_pin_prev_sealed, kid_pin_prev_rev, kid_pin_rev \
         FROM device_policy WHERE device_id = ?",
    )
    .bind(id)
    .fetch_one(&app.db)
    .await
    .unwrap();
    let key = app.state.kid_pin_key.as_deref().unwrap();
    let open = |s: &Option<String>| {
        s.as_deref().map(|s| {
            crate::kid_pin::open(key, id, s)
                .unwrap()
                .as_str()
                .to_string()
        })
    };
    Kept {
        keyguard,
        pin: open(&sealed),
        prev: open(&prev_sealed),
        prev_sealed: prev_sealed.is_some(),
        prev_rev,
        rev,
    }
}

async fn android(app: &TestApp, id: i64) {
    sqlx::query("UPDATE device_policy SET kid_keyguard = 'android' WHERE device_id = ?")
        .bind(id)
        .execute(&app.db)
        .await
        .unwrap();
}

/// A status report with `capabilities`, and `lock_state` when given.
async fn report(app: &TestApp, token: &str, caps: &[&str], lock_state: Option<Value>) {
    let mut body = json!({ "lock_reason": "NONE", "kiosk_engaged": true, "capabilities": caps });
    if let Some(state) = lock_state {
        body["lock_state"] = state;
    }
    let res = app
        .request(Method::POST, "/api/devices/status", Some(token), Some(body))
        .await;
    assert_eq!(res.status, StatusCode::NO_CONTENT, "{}", res.text());
}

fn lock_state(keyguard: &str, pin_rev: Value) -> Option<Value> {
    Some(
        json!({ "active": true, "locked": true, "keyguard": keyguard, "pin_rev": pin_rev,
                 "reset_token": true }),
    )
}

/// This phone's `set_kid_pin` rows, oldest first: `(id, delivered, cancel_reason)`.
async fn pin_rows(app: &TestApp, id: i64) -> Vec<(i64, bool, Option<String>)> {
    sqlx::query_as(
        "SELECT id, delivered_at IS NOT NULL, cancel_reason FROM device_commands \
         WHERE device_id = ? AND command = 'set_kid_pin' ORDER BY id",
    )
    .bind(id)
    .fetch_all(&app.db)
    .await
    .unwrap()
}

async fn fetch(app: &TestApp, token: &str) -> super::TestResponse {
    let res = app
        .request(Method::GET, "/api/devices/policy", Some(token), None)
        .await;
    assert_eq!(res.status, StatusCode::OK, "{}", res.text());
    res
}

async fn answer(app: &TestApp, token: &str, command: i64, success: bool, message: &str) {
    let res = app
        .request(
            Method::POST,
            "/api/devices/command-result",
            Some(token),
            Some(json!({ "command_id": command, "success": success, "message": message })),
        )
        .await;
    assert_eq!(res.status, StatusCode::NO_CONTENT);
}

fn keys(value: &Value) -> Vec<&str> {
    let mut keys: Vec<&str> = value
        .as_object()
        .unwrap()
        .keys()
        .map(String::as_str)
        .collect();
    keys.sort();
    keys
}

/// QA Q59-6 and qa-59-revision M-2: every save seals the PIN and bumps the rev; the PIN the phone
/// last confirmed survives any number of saves, a legacy PIN (no sealed copy) stays "an earlier
/// PIN" rather than the first new one, and the phone reporting the current rev forgets it.
#[tokio::test]
async fn saves_seal_the_pin_and_keep_the_one_the_phone_confirmed() {
    let (app, cookie, id, token) = setup().await;
    save(&app, &cookie, id, "1357").await;
    // The state of a PIN saved before this release: no sealed copy, rev 0.
    sqlx::query(
        "UPDATE device_policy SET kid_pin_sealed = NULL, kid_pin_rev = 0, \
         kid_pin_prev_sealed = NULL, kid_pin_prev_rev = NULL WHERE device_id = ?",
    )
    .bind(id)
    .execute(&app.db)
    .await
    .unwrap();

    assert_eq!(save(&app, &cookie, id, "2468").await, "saved");
    let after_b = kept(&app, id).await;
    assert_eq!(
        (
            after_b.pin.as_deref(),
            after_b.prev_sealed,
            after_b.prev_rev,
            after_b.rev
        ),
        (Some("2468"), false, Some(0), 1)
    );
    save(&app, &cookie, id, "8642").await;
    let after_c = kept(&app, id).await;
    assert_eq!(
        (
            after_c.pin.as_deref(),
            after_c.prev_sealed,
            after_c.prev_rev,
            after_c.rev
        ),
        (Some("8642"), false, Some(0), 2),
        "the phone still has the legacy PIN, not 2468"
    );

    // A report of another rev keeps it; the current one forgets it.
    report(&app, &token, &CAPS, lock_state("own", json!(1))).await;
    assert_eq!(kept(&app, id).await.prev_rev, Some(0));
    report(&app, &token, &CAPS, lock_state("own", json!(2))).await;
    let confirmed = kept(&app, id).await;
    assert_eq!((confirmed.prev_sealed, confirmed.prev_rev), (false, None));

    // From a confirmed sealed PIN: kept over two saves.
    save(&app, &cookie, id, "1122").await;
    save(&app, &cookie, id, "3344").await;
    assert_eq!(
        kept(&app, id).await,
        Kept {
            keyguard: "own".to_string(),
            pin: Some("3344".to_string()),
            prev: Some("8642".to_string()),
            prev_sealed: true,
            prev_rev: Some(2),
            rev: 4,
        }
    );
    let policy = fetch(&app, &token).await.json();
    assert_eq!(policy["kid_lock"]["pin_rev"], 4);
    assert_eq!(policy["kid_lock"]["keyguard"], "own");
    // The sealed PIN is never in the policy.
    let sealed: String =
        sqlx::query_scalar("SELECT kid_pin_sealed FROM device_policy WHERE device_id = ?")
            .bind(id)
            .fetch_one(&app.db)
            .await
            .unwrap();
    let text = policy.to_string();
    assert!(!text.contains(&sealed[3..20]), "{text}");
    assert!(policy["pending_command"].is_null());
}

/// N-B and §2.1: clearing the Child PIN forgets every copy, moves the rev on, sets Vibb One's lock
/// and cancels a waiting `set_kid_pin`.
#[tokio::test]
async fn clearing_forgets_the_pin_sets_own_and_cancels_the_waiting_command() {
    let (app, cookie, id, token) = setup().await;
    save(&app, &cookie, id, "1357").await;
    android(&app, id).await;
    report(&app, &token, &CAPS, None).await;
    save(&app, &cookie, id, "2468").await;
    assert_eq!(pin_rows(&app, id).await.len(), 1);

    let res = app
        .request_form(
            Method::POST,
            &format!("/devices/{id}/kid-lock"),
            Some(&cookie),
            &[("clear_kid_pin", "on")],
        )
        .await;
    assert_eq!(res.status, StatusCode::SEE_OTHER);
    assert_eq!(
        kept(&app, id).await,
        Kept {
            keyguard: "own".to_string(),
            pin: None,
            prev: None,
            prev_sealed: false,
            prev_rev: None,
            rev: 3,
        }
    );
    let rows = pin_rows(&app, id).await;
    assert_eq!(rows[0].2.as_deref(), Some("kid_pin_cleared"));
    let policy = fetch(&app, &token).await.json();
    assert!(policy["kid_lock"].is_null());
    assert!(policy["pending_command"].is_null());
}

/// §2.1: without the key file a PIN is saved (the lock never depends on it) but not kept, and
/// the flash says so; nothing is queued for a phone in android mode then.
#[tokio::test]
async fn without_the_key_a_pin_is_saved_but_not_kept() {
    let (app, cookie, id, token) = setup().await;
    save(&app, &cookie, id, "1357").await;
    android(&app, id).await;
    // The phone holds it (Android's PIN since a keypad migration).
    report(&app, &token, &CAPS, lock_state("android", json!(1))).await;
    let mut keyless = app.state.clone();
    keyless.kid_pin_key = None;
    let keyless = app.admin_router(keyless);
    assert_eq!(
        save_via(&app, &keyless, &cookie, id, "2468").await,
        "saved_not_kept"
    );
    let now = kept(&app, id).await;
    assert_eq!(
        (now.pin, now.prev.as_deref(), now.rev),
        (None, Some("1357"), 2)
    );
    assert!(pin_rows(&app, id).await.is_empty());
    let policy = fetch(&app, &token).await.json();
    assert!(crate::security::verify_pin(
        "2468",
        policy["kid_lock"]["pin_hash"].as_str().unwrap(),
        policy["kid_lock"]["pin_salt"].as_str().unwrap()
    ));
    let html = app
        .get_page(
            &format!("/devices/{id}/lock?notice=saved_not_kept"),
            &cookie,
        )
        .await
        .text();
    assert!(html.contains("keep a copy to show later"), "{html}");
    // A first Child PIN without the key says both things (qa-59-step1 N-1).
    let (two, _) = app.enrolled_device("two").await;
    let res = app
        .request_form(
            Method::POST,
            &format!("/devices/{two}/parent-pin"),
            Some(&cookie),
            &[("new_pin", "246810")],
        )
        .await;
    assert_eq!(res.status, StatusCode::SEE_OTHER);
    assert_eq!(
        save_via(&app, &keyless, &cookie, two, "1357").await,
        "saved_safe_boot_not_kept"
    );
    let html = app
        .get_page(
            &format!("/devices/{two}/lock?notice=saved_safe_boot_not_kept"),
            &cookie,
        )
        .await
        .text();
    assert!(
        html.contains("Block safe mode is now on") && html.contains("keep a copy to show later"),
        "{html}"
    );
}

/// §2.1's queue rule at a save: android, a sealed PIN, the capability in the last report and none
/// waiting - each off, no row; a row that went out doesn't block the next save's.
#[tokio::test]
async fn a_save_queues_set_kid_pin_only_for_a_phone_that_can_take_it() {
    let (app, cookie, id, token) = setup().await;
    // Vibb One's lock, a capable phone: nothing.
    report(&app, &token, &CAPS, None).await;
    save(&app, &cookie, id, "1357").await;
    assert!(pin_rows(&app, id).await.is_empty());
    // Android's, a launcher without the capability (an older one): nothing.
    android(&app, id).await;
    report(&app, &token, &["pin_lock_v1", "command_receipt_v1"], None).await;
    save(&app, &cookie, id, "2468").await;
    assert!(pin_rows(&app, id).await.is_empty());
    // With it: one row, and a second save while it waits adds none (it carries the newest PIN).
    report(&app, &token, &CAPS, None).await;
    save(&app, &cookie, id, "8642").await;
    save(&app, &cookie, id, "1122").await;
    assert_eq!(pin_rows(&app, id).await.len(), 1);
    // Out to the phone: the next save is a new row, never the id the phone may have run.
    assert_eq!(
        fetch(&app, &token).await.json()["pending_command"]["pin"],
        "1122"
    );
    save(&app, &cookie, id, "3344").await;
    let rows = pin_rows(&app, id).await;
    assert_eq!(rows.len(), 2);
    assert!(rows[0].1 && !rows[1].1);
}

/// §1/§2.1: `pending_command.pin` only on `set_kid_pin`, the PIN of the same answer's hash, only
/// in android mode for a phone whose last report has the capability, `no-store`; never after the
/// receipt; and the keys pinned with and without it.
#[tokio::test]
async fn set_kid_pin_carries_the_pin_only_where_it_may() {
    let (app, cookie, id, token) = setup().await;
    save(&app, &cookie, id, "1357").await;
    android(&app, id).await;
    report(&app, &token, &CAPS, None).await;
    save(&app, &cookie, id, "2468").await;

    let res = fetch(&app, &token).await;
    assert_eq!(res.headers.get(header::CACHE_CONTROL).unwrap(), "no-store");
    let policy = res.json();
    let command = &policy["pending_command"];
    assert_eq!(keys(command), ["command", "id", "pin", "requested_at"]);
    assert_eq!(command["command"], "set_kid_pin");
    assert_eq!(command["pin"], "2468");
    let kid_lock = &policy["kid_lock"];
    assert!(crate::security::verify_pin(
        "2468",
        kid_lock["pin_hash"].as_str().unwrap(),
        kid_lock["pin_salt"].as_str().unwrap()
    ));
    assert_eq!(kid_lock["keyguard"], "android");
    assert_eq!(kid_lock["pin_rev"], 2);
    // Confirmed: never handed out again.
    let res = app
        .request(
            Method::POST,
            "/api/devices/command-receipt",
            Some(&token),
            Some(json!({ "command_id": command["id"] })),
        )
        .await;
    assert_eq!(res.status, StatusCode::NO_CONTENT);
    // Past the re-delivery gap, where an unconfirmed row would go out again (qa-59-step1 N-3).
    sqlx::query(
        "UPDATE device_commands SET delivered_at = datetime('now', '-3 minutes') WHERE id = ?",
    )
    .bind(command["id"].as_i64().unwrap())
    .execute(&app.db)
    .await
    .unwrap();
    let res = fetch(&app, &token).await;
    assert!(res.json()["pending_command"].is_null());

    // Any other command: no pin key.
    let queue = |command: &'static str| {
        let db = app.db.clone();
        async move {
            sqlx::query("INSERT INTO device_commands (device_id, command) VALUES (?, ?)")
                .bind(id)
                .bind(command)
                .execute(&db)
                .await
                .unwrap();
        }
    };
    queue("ring").await;
    let policy = fetch(&app, &token).await.json();
    assert_eq!(
        keys(&policy["pending_command"]),
        ["command", "id", "requested_at"]
    );

    // A row the queue rule wouldn't make (a launcher without the capability enrolled into it,
    // Vibb One's lock again, another phone's sealed PIN): the command without the PIN.
    report(&app, &token, &["pin_lock_v1", "command_receipt_v1"], None).await;
    queue("set_kid_pin").await;
    sent_without_pin(&app, &token).await;
    report(&app, &token, &CAPS, None).await;
    sqlx::query("UPDATE device_policy SET kid_keyguard = 'own' WHERE device_id = ?")
        .bind(id)
        .execute(&app.db)
        .await
        .unwrap();
    queue("set_kid_pin").await;
    sent_without_pin(&app, &token).await;
    android(&app, id).await;
    let (other, _) = app.enrolled_device("other").await;
    let key = app.state.kid_pin_key.as_deref().unwrap();
    let theirs = crate::kid_pin::seal(key, other, &crate::kid_pin::KidPin::new("5555".into()));
    sqlx::query("UPDATE device_policy SET kid_pin_sealed = ? WHERE device_id = ?")
        .bind(theirs)
        .bind(id)
        .execute(&app.db)
        .await
        .unwrap();
    queue("set_kid_pin").await;
    sent_without_pin(&app, &token).await;
}

/// The next policy's command is a `set_kid_pin` without the PIN.
async fn sent_without_pin(app: &TestApp, token: &str) {
    let res = fetch(app, token).await;
    let command = res.json()["pending_command"].clone();
    assert_eq!(command["command"], "set_kid_pin");
    assert_eq!(keys(&command), ["command", "id", "requested_at"]);
}

/// §2.1 at the status intake, with qa-59-revision L-2: a phone in android mode gets the PIN when it
/// reports Vibb One's lock (updated after the switch) or a stale rev - never twice (waiting, in
/// flight), never for `null` (a PIN changed on the phone), never again once the newest row was
/// answered (a refusal, or a success the report didn't follow: no loop).
#[tokio::test]
async fn a_status_report_sends_the_pin_to_a_phone_that_needs_it() {
    let (app, cookie, id, token) = setup().await;
    // Saved and switched while the phone ran an older Vibb One: no row.
    report(&app, &token, &["pin_lock_v1", "command_receipt_v1"], None).await;
    save(&app, &cookie, id, "1357").await;
    android(&app, id).await;
    assert!(pin_rows(&app, id).await.is_empty());

    // Updated: it reports its own lock with the current rev. Queued once, also while in flight.
    report(&app, &token, &CAPS, lock_state("own", json!(1))).await;
    report(&app, &token, &CAPS, lock_state("own", json!(1))).await;
    assert_eq!(pin_rows(&app, id).await.len(), 1);
    let sent = fetch(&app, &token).await.json()["pending_command"].clone();
    assert_eq!(sent["pin"], "1357");
    report(&app, &token, &CAPS, lock_state("own", json!(1))).await;
    assert_eq!(pin_rows(&app, id).await.len(), 1, "in flight: no duplicate");
    // Answered, and the phone still reports its own lock (an unmanaged phone): no loop.
    answer(&app, &token, sent["id"].as_i64().unwrap(), true, "pin set").await;
    report(&app, &token, &CAPS, lock_state("own", json!(1))).await;
    assert_eq!(pin_rows(&app, id).await.len(), 1);

    // A new PIN whose row went out and was never confirmed within the window.
    save(&app, &cookie, id, "2468").await;
    fetch(&app, &token).await;
    sqlx::query(
        "UPDATE device_commands SET first_delivered_at = datetime('now', '-20 minutes'), \
         delivered_at = datetime('now', '-20 minutes') \
         WHERE device_id = ? AND command = 'set_kid_pin'",
    )
    .bind(id)
    .execute(&app.db)
    .await
    .unwrap();
    // The current rev, or `null`: nothing.
    report(&app, &token, &CAPS, lock_state("android", json!(2))).await;
    report(&app, &token, &CAPS, lock_state("android", Value::Null)).await;
    assert_eq!(pin_rows(&app, id).await.len(), 2);
    // Behind: healed with a new row.
    report(&app, &token, &CAPS, lock_state("android", json!(1))).await;
    let rows = pin_rows(&app, id).await;
    assert_eq!(rows.len(), 3);
    // Out and used up by a later pop (retired, still inside the window): not in flight (N-2).
    fetch(&app, &token).await;
    sqlx::query("UPDATE device_commands SET deliveries = 3 WHERE id = ?")
        .bind(rows[2].0)
        .execute(&app.db)
        .await
        .unwrap();
    report(&app, &token, &CAPS, lock_state("android", json!(1))).await;
    let rows = pin_rows(&app, id).await;
    assert_eq!(rows.len(), 4);
    // Refused: the parent's button retries, not the intake.
    answer(&app, &token, rows[3].0, false, "refused: no_token").await;
    report(&app, &token, &CAPS, lock_state("android", json!(1))).await;
    assert_eq!(pin_rows(&app, id).await.len(), 4);
}

/// qa-59-step1 S59-1: a save that commits while a poll is building its answer. The poll read the
/// older rev, so its pop leaves the row waiting - it never goes out with the older PIN, which the
/// phone would confirm and the intake then couldn't heal - and the next poll carries the new PIN.
#[tokio::test]
async fn a_save_racing_a_poll_never_loses_the_new_pin() {
    let (app, cookie, id, token) = setup().await;
    save(&app, &cookie, id, "1357").await;
    android(&app, id).await;
    report(&app, &token, &CAPS, None).await;
    // The poll read rev 1; the save of 2468 commits before its pop (the row it queued waits).
    save(&app, &cookie, id, "2468").await;
    assert_eq!(kept(&app, id).await.rev, 2);
    let stale = crate::handlers::device_api::pop_command(&app.db, id, true, 1)
        .await
        .unwrap();
    assert!(stale.is_none(), "{stale:?}");
    assert!(!pin_rows(&app, id).await[0].1, "still waiting");
    // Any other command still goes out from such a poll.
    sqlx::query("INSERT INTO device_commands (device_id, command) VALUES (?, 'ring')")
        .bind(id)
        .execute(&app.db)
        .await
        .unwrap();
    let ring = crate::handlers::device_api::pop_command(&app.db, id, true, 1)
        .await
        .unwrap();
    assert_eq!(ring.map(|r| r.1).as_deref(), Some("ring"));
    // The next poll: the new PIN, with the hash it belongs to.
    let policy = fetch(&app, &token).await.json();
    assert_eq!(policy["pending_command"]["pin"], "2468");
    assert_eq!(policy["kid_lock"]["pin_rev"], 2);
}

/// qa-59-step1 S59-2 and S59-3: a phone enrolled into the row anew. The earlier install's reports
/// don't make it capable, and its answered `set_kid_pin` doesn't stop the intake.
#[tokio::test]
async fn a_re_enrolled_phone_counts_only_its_own_reports_and_rows() {
    let (app, cookie, id, token) = setup().await;
    save(&app, &cookie, id, "1357").await;
    android(&app, id).await;
    report(&app, &token, &CAPS, lock_state("own", json!(1))).await;
    let sent = fetch(&app, &token).await.json()["pending_command"].clone();
    answer(&app, &token, sent["id"].as_i64().unwrap(), true, "pin set").await;
    // An hour later the phone is reset and enrolled into the row again.
    for table in [
        ("device_commands", "requested_at"),
        ("device_status", "reported_at"),
    ] {
        sqlx::query(&format!(
            "UPDATE {} SET {} = datetime('now', '-1 hour') WHERE device_id = ?",
            table.0, table.1
        ))
        .bind(id)
        .execute(&app.db)
        .await
        .unwrap();
    }
    let code = app.new_code(id, crate::enrollment::Kind::Qr).await;
    let res = app
        .request(
            Method::POST,
            "/api/devices/enroll",
            None,
            Some(json!({ "enrollment_code": code })),
        )
        .await;
    assert_eq!(res.status, StatusCode::OK);
    let token = res.json()["device_token"].as_str().unwrap().to_string();
    // Before its first report: the earlier install's capability counts for nothing (S59-3).
    save(&app, &cookie, id, "2468").await;
    assert_eq!(pin_rows(&app, id).await.len(), 1);
    // Its first report, on Vibb One's lock: the earlier answer doesn't block it (S59-2).
    report(&app, &token, &CAPS, lock_state("own", Value::Null)).await;
    assert_eq!(pin_rows(&app, id).await.len(), 2);
    assert_eq!(
        fetch(&app, &token).await.json()["pending_command"]["pin"],
        "2468"
    );
}
