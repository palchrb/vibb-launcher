//! Design 59 step 2 (`docs/design/59-android-keyguard-design.md` §2.2, §2.3) and design 132 (Android's lock the
//! only lock): Lock Screen & PINs' side of Android's lock - "Show Child PIN…" behind the password (and the one
//! re-auth rule it shares with the password and 2FA forms), "Send the Child PIN again", the Child PIN never removed,
//! and what the page and the summary say about the phone. The words per case are `kid_lock::tests`'. The queue rule
//! end to end is [queue] (design 59 step 1, `tests::kid_keyguard` until design 132).

use axum::http::{Method, StatusCode, header};
use serde_json::{Value, json};

use super::TestApp;

const PASSWORD: &str = "correct horse battery staple";
const CAPS: [&str; 3] = ["pin_lock_v1", "command_receipt_v1", "android_keyguard_v1"];

/// An enrolled phone with a Parent PIN and the Child PIN 4826: `(app, cookie, id, token)`.
async fn setup() -> (TestApp, String, i64, String) {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("Anna").await;
    for (route, field, pin) in [
        ("parent-pin", "new_pin", "5829317"),
        ("kid-lock", "new_kid_pin", "4826"),
    ] {
        let res = app
            .request_form(
                Method::POST,
                &format!("/devices/{id}/{route}"),
                Some(&cookie),
                &[(field, pin)],
            )
            .await;
        assert_eq!(res.status, StatusCode::SEE_OTHER, "{route}");
    }
    (app, cookie, id, token)
}

async fn report(app: &TestApp, token: &str, caps: &[&str], lock_state: Value) {
    let body = json!({ "lock_reason": "NONE", "kiosk_engaged": true, "capabilities": caps,
                       "lock_state": lock_state });
    let res = app
        .request(Method::POST, "/api/devices/status", Some(token), Some(body))
        .await;
    assert_eq!(res.status, StatusCode::NO_CONTENT, "{}", res.text());
}

fn lock_state(keyguard: &str, pin_rev: Value, reset_token: bool) -> Value {
    json!({ "active": true, "locked": true, "keyguard": keyguard, "pin_rev": pin_rev,
            "reset_token": reset_token })
}

async fn reveal(app: &TestApp, cookie: &str, id: i64, password: &str) -> super::TestResponse {
    app.request_form(
        Method::POST,
        &format!("/devices/{id}/lock"),
        Some(cookie),
        &[("action", "reveal_kid_pin"), ("password", password)],
    )
    .await
}

async fn page(app: &TestApp, cookie: &str, id: i64) -> String {
    let res = app.get_page(&format!("/devices/{id}/lock"), cookie).await;
    assert_eq!(res.status, StatusCode::OK);
    plain(res.text())
}

/// A page's text with Askama's apostrophes as typed.
fn plain(html: String) -> String {
    html.replace("&#39;", "'")
}

/// `(command, cancel_reason)` of this phone's `set_kid_pin` rows, oldest first.
async fn pin_rows(app: &TestApp, id: i64) -> Vec<(i64, Option<String>)> {
    sqlx::query_as(
        "SELECT id, cancel_reason FROM device_commands \
         WHERE device_id = ? AND command = 'set_kid_pin' ORDER BY id",
    )
    .bind(id)
    .fetch_all(&app.db)
    .await
    .unwrap()
}

async fn events(app: &TestApp, kind: &str) -> Vec<Option<String>> {
    super::security_words(app, kind, crate::i18n::Lang::En)
        .await
        .into_iter()
        .map(|words| (!words.is_empty()).then_some(words))
        .collect()
}

/// Design 132: Android's lock is the only lock - no switch on the page, its route gone, the policy's `keyguard`
/// always "android"; the first Child PIN goes out to a phone that can take it, and the page's ⓘ keeps what a parent
/// acts on.
#[tokio::test]
async fn one_lock_no_switch() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("Anna").await;
    let res = app
        .request_form(
            Method::POST,
            &format!("/devices/{id}/kid-lock/keyguard"),
            Some(&cookie),
            &[("keyguard", "own")],
        )
        .await;
    assert_eq!(
        res.status,
        StatusCode::NOT_FOUND,
        "the switch's route is gone"
    );
    let html = page(&app, &cookie, id).await;
    assert!(!html.contains("name=\"keyguard\""), "{html}");
    assert!(!html.contains("Vibb One's lock"), "{html}");
    assert!(!html.contains("Starts when the Child PIN is set"));
    let res = app
        .request_form(
            Method::POST,
            &format!("/devices/{id}/parent-pin"),
            Some(&cookie),
            &[("new_pin", "5829317")],
        )
        .await;
    assert!(res.status.is_redirection());
    report(&app, &token, &CAPS, json!({ "active": false })).await;
    let res = app
        .request_form(
            Method::POST,
            &format!("/devices/{id}/kid-lock"),
            Some(&cookie),
            &[("new_kid_pin", "4826")],
        )
        .await;
    assert!(res.status.is_redirection());
    app.assert_lands(&res, &cookie).await;
    assert_eq!(pin_rows(&app, id).await.len(), 1, "the PIN goes out");
    let policy = app.policy(&token).await;
    assert_eq!(policy["kid_lock"]["keyguard"], "android");
    let html = page(&app, &cookie, id).await;
    assert!(html.contains("Child PIN to the phone"), "the command's row");
    assert!(html.contains("data-privacy=\"android\""));
    // The ⓘs keep what a parent acts on (qa-pwa-polish-a Q-2, Q-3), the footer's sentence among them.
    assert!(html.contains("and alarms may not ring, until the Child PIN is typed"));
    assert!(html.contains("Android's lock: the PIN pad"));
    // The time lock asks for no code since 2026-11-01 (PWA only): the PIN lifts no rule there.
    assert!(!html.contains("lifts the rules for up to two hours"));
    assert!(html.contains("the wait ends once the phone checks in"));
    // Find Phone says what waits for the PIN after a restart.
    let locate = format!("/devices/{id}/find");
    assert!(
        app.get_page(&locate, &cookie)
            .await
            .text()
            .contains("data-note=\"android-lock\"")
    );
    assert!(events(&app, "kid_keyguard_changed").await.is_empty());
}

/// §2.2: the PIN only after the password, only in the page's own answer (no-store, no redirect,
/// nothing in the log but the phone); three wrong passwords in a row end the session, a right one
/// in between resets the count.
#[tokio::test]
async fn show_child_pin_needs_the_password() {
    let (app, cookie, id, _) = setup().await;
    let wrong = reveal(&app, &cookie, id, "not it").await;
    assert_eq!(wrong.status, StatusCode::BAD_REQUEST);
    let text = wrong.text();
    assert!(!text.contains("4826"));
    assert!(text.contains("Wrong password."));
    assert!(text.contains("id=\"show-pin\" open"));

    let right = reveal(&app, &cookie, id, PASSWORD).await;
    assert_eq!(right.status, StatusCode::OK);
    assert_eq!(
        right.headers.get(header::CACHE_CONTROL).unwrap(),
        "no-store"
    );
    let text = right.text();
    assert!(text.contains("<p class=\"pin-digits\" translate=\"no\">4826</p>"));
    // S2P-5/6: hidden after a minute (CSS) and on leaving, the history entry made a GET (JS).
    assert!(text.contains("Hidden after a minute."));
    assert!(text.contains("<script src=\"/static/reveal.js?v=5\" data-swap-ready></script>"));
    assert!(
        text.contains("<body data-post-answer"),
        "never reloaded in place"
    );
    // The GET page is no-store too, and holds no PIN.
    let get = app.get_page(&format!("/devices/{id}/lock"), &cookie).await;
    assert_eq!(get.headers.get(header::CACHE_CONTROL).unwrap(), "no-store");
    assert!(!get.text().contains("4826"));
    assert_eq!(
        events(&app, "kid_pin_shown").await,
        [Some(format!("phone {id}"))]
    );
    assert_eq!(events(&app, "kid_pin_reveal_refused").await.len(), 1);
    let logged: Vec<String> = sqlx::query_scalar(
        "SELECT event_type || COALESCE(detail, '') || COALESCE(params, '') FROM security_events",
    )
    .fetch_all(&app.db)
    .await
    .unwrap();
    assert!(
        logged.iter().all(|line| !line.contains("4826")),
        "{logged:?}"
    );
    // A form from an older page.
    let res = app
        .request_form(
            Method::POST,
            &format!("/devices/{id}/lock"),
            Some(&cookie),
            &[("password", PASSWORD)],
        )
        .await;
    assert_eq!(res.status, StatusCode::BAD_REQUEST);

    // The right one reset the count: two more wrong are still inside the session...
    for left in [2, 1] {
        let res = reveal(&app, &cookie, id, "not it").await;
        assert_eq!(res.status, StatusCode::BAD_REQUEST);
        assert_eq!(
            res.text().contains("One more wrong one signs you out"),
            left == 1
        );
    }
    // ...and the third ends it.
    let res = reveal(&app, &cookie, id, "not it").await;
    assert_eq!(res.location(), Some("/login"));
    assert_eq!(events(&app, "reauth_signed_out").await.len(), 1);
    let after = app.get_page(&format!("/devices/{id}/lock"), &cookie).await;
    assert_eq!(after.location(), Some("/login"), "signed out");
}

/// qa-59-step2-code S2P-1: wrong passwords sent at once each count (the count is the account's,
/// one `UPDATE` each), so three in parallel end the session as three in a row do.
#[tokio::test]
async fn parallel_wrong_passwords_all_count() {
    let (app, cookie, id, _) = setup().await;
    let epoch = || async {
        sqlx::query_scalar::<_, i64>("SELECT session_epoch FROM admin_users")
            .fetch_one(&app.db)
            .await
            .unwrap()
    };
    let before = epoch().await;
    let (a, b, c) = tokio::join!(
        reveal(&app, &cookie, id, "not it"),
        reveal(&app, &cookie, id, "not it 2"),
        reveal(&app, &cookie, id, "not it 3"),
    );
    let signed_out = [&a, &b, &c]
        .iter()
        .filter(|res| res.location() == Some("/login"))
        .count();
    assert_eq!(signed_out, 1, "{:?}", [a.status, b.status, c.status]);
    let after = app.get_page(&format!("/devices/{id}/lock"), &cookie).await;
    assert_eq!(after.location(), Some("/login"));
    assert_eq!(events(&app, "reauth_signed_out").await.len(), 1);
    // Every session of the account ends (the epoch), and the count starts over.
    assert_eq!(epoch().await, before + 1);
    let wrong: i64 = sqlx::query_scalar("SELECT reauth_wrong FROM admin_users")
        .fetch_one(&app.db)
        .await
        .unwrap();
    assert_eq!(wrong, 0);
}

/// S2P-1: a request that passed the session check before the third wrong password ended the
/// session, and finishes afterwards, doesn't bring it back (the count is no longer in its copy of
/// the session, and the third one moved the account's session epoch - pinned by
/// [parallel_wrong_passwords_all_count]); its own wrong password still counts.
#[tokio::test]
async fn a_held_request_cant_bring_the_session_back() {
    use tower::ServiceExt;
    let (app, cookie, id, _) = setup().await;
    let (tx, rx) = tokio::sync::mpsc::channel::<Result<axum::body::Bytes, std::io::Error>>(1);
    let mut held = axum::http::Request::builder()
        .method(Method::POST)
        .uri(format!("/devices/{id}/lock"))
        .header(header::CONTENT_TYPE, "application/x-www-form-urlencoded")
        .header(header::COOKIE, &cookie)
        .body(axum::body::Body::from_stream(
            tokio_stream::wrappers::ReceiverStream::new(rx),
        ))
        .unwrap();
    held.extensions_mut()
        .insert(axum::extract::ConnectInfo(super::LOOPBACK_PEER));
    let router = app.router.clone();
    let task = tokio::spawn(async move { router.oneshot(held).await.unwrap() });
    // Past the session check, waiting for its body.
    tokio::time::sleep(std::time::Duration::from_millis(200)).await;
    for _ in 0..2 {
        assert_eq!(
            reveal(&app, &cookie, id, "not it").await.status,
            StatusCode::BAD_REQUEST
        );
    }
    assert_eq!(
        reveal(&app, &cookie, id, "not it").await.location(),
        Some("/login")
    );
    tx.send(Ok(axum::body::Bytes::from_static(
        b"action=reveal_kid_pin&password=not+it",
    )))
    .await
    .unwrap();
    drop(tx);
    let answer = task.await.unwrap();
    assert_eq!(
        answer.status(),
        StatusCode::BAD_REQUEST,
        "it counted, nothing more"
    );
    let after = app.get_page(&format!("/devices/{id}/lock"), &cookie).await;
    assert_eq!(after.location(), Some("/login"), "still signed out");
}

/// Q59-11: one count for every form that asks for the password again - wrong at the password
/// change, the 2FA reset and Show Child PIN add up, and the third ends the session.
#[tokio::test]
async fn the_password_forms_share_one_count() {
    let (app, cookie, id, _) = setup().await;
    let res = app
        .request_form(
            Method::POST,
            "/account/password",
            Some(&cookie),
            &[
                ("current_password", "not it"),
                ("new_password", "a new password, long enough"),
                ("confirm_password", "a new password, long enough"),
            ],
        )
        .await;
    assert_eq!(res.status, StatusCode::BAD_REQUEST);
    let res = app
        .request_form(
            Method::POST,
            "/account/reset-2fa",
            Some(&cookie),
            &[("current_password", "not it")],
        )
        .await;
    assert_eq!(res.status, StatusCode::BAD_REQUEST);
    assert!(res.text().contains("One more wrong one signs you out"));
    let res = reveal(&app, &cookie, id, "not it").await;
    assert_eq!(res.location(), Some("/login"));
    // Each wrong one is in the security log, the password change's too (security review L7).
    assert_eq!(
        events(&app, "password_change_refused").await,
        [Some("Wrong current password".to_string())]
    );
    assert_eq!(events(&app, "totp_reset_refused").await.len(), 1);
    let account = app.get_page("/account", &cookie).await;
    assert_eq!(account.location(), Some("/login"));
    // Nothing was changed on the way.
    let enabled: bool = sqlx::query_scalar("SELECT totp_enabled FROM admin_users")
        .fetch_one(&app.db)
        .await
        .unwrap();
    assert!(enabled);
}

/// §2.2: next to the new PIN, the one the phone still has (kept until it confirms the new one),
/// "an earlier PIN" when that one isn't kept, the newest alone for a launcher without Android's
/// lock, and why nothing shows for a PIN this server keeps no copy of.
#[tokio::test]
async fn show_child_pin_names_the_one_the_phone_still_has() {
    let (app, cookie, id, token) = setup().await;
    // The phone confirms 4826 (rev 1), then the parent saves 7391.
    report(&app, &token, &CAPS, lock_state("android", json!(1), true)).await;
    app.request_form(
        Method::POST,
        &format!("/devices/{id}/kid-lock"),
        Some(&cookie),
        &[("new_kid_pin", "7391")],
    )
    .await;
    let text = reveal(&app, &cookie, id, PASSWORD).await.text();
    assert!(text.contains("<p class=\"field-label\">New Child PIN</p>"));
    assert!(text.contains(">7391</p>"));
    assert!(
        text.contains("its PIN is still <span class=\"pin-inline\" translate=\"no\">4826</span>.")
    );
    // A rev the server keeps no PIN for.
    report(&app, &token, &CAPS, lock_state("android", json!(0), true)).await;
    let text = reveal(&app, &cookie, id, PASSWORD).await.text();
    assert!(text.contains("The phone still has an earlier PIN"));
    assert!(!text.contains("4826"));
    // An older launcher reports no rev: the newest alone.
    report(&app, &token, &["pin_lock_v1"], json!({ "active": true })).await;
    let text = reveal(&app, &cookie, id, PASSWORD).await.text();
    assert!(
        text.contains(">7391</p>") && !text.contains("<p class=\"field-label\">New Child PIN</p>")
    );
    // A PIN saved before this version (no copy).
    sqlx::query("UPDATE device_policy SET kid_pin_sealed = NULL WHERE device_id = ?")
        .bind(id)
        .execute(&app.db)
        .await
        .unwrap();
    let text = reveal(&app, &cookie, id, PASSWORD).await.text();
    assert!(text.contains("Not stored on this server"));
    assert!(!text.contains("7391"));
}

/// §2.3: "Send the Child PIN again" shows while the phone doesn't run the current PIN and nothing
/// is on its way; it queues the command, or says why it can't.
#[tokio::test]
async fn send_again_queues_the_pin_when_it_can() {
    let (app, cookie, id, token) = setup().await;
    // A PIN changed on the phone (`pin_rev` null): the intake never sends for it, the parent does.
    report(
        &app,
        &token,
        &CAPS,
        lock_state("android", json!(null), true),
    )
    .await;
    assert!(pin_rows(&app, id).await.is_empty());
    let route = format!("/devices/{id}/kid-pin/send");
    let send = || app.request_form(Method::POST, &route, Some(&cookie), &[]);
    let html = page(&app, &cookie, id).await;
    assert!(html.contains("id=\"send-pin\""));
    assert!(html.contains("Phone's PIN isn't the Child PIN"));
    let res = send().await;
    assert_eq!(
        res.location(),
        Some(format!("/devices/{id}/lock?notice=pin_sent#child-pin").as_str())
    );
    app.assert_lands(&res, &cookie).await;
    // Queued: no button while it waits, and a second send is refused.
    let html = page(&app, &cookie, id).await;
    assert!(!html.contains("id=\"send-pin\""));
    assert!(
        send()
            .await
            .location()
            .unwrap()
            .contains("notice=pin_not_sent")
    );
    // The phone takes it and refuses (no token): the row says so, the button is back.
    let pin = app
        .request(Method::GET, "/api/devices/policy", Some(&token), None)
        .await
        .json()["pending_command"]["id"]
        .as_i64()
        .unwrap();
    let res = app
        .request(
            Method::POST,
            "/api/devices/command-result",
            Some(&token),
            Some(json!({ "command_id": pin, "success": false, "message": "refused: no_token" })),
        )
        .await;
    assert_eq!(res.status, StatusCode::NO_CONTENT);
    let html = page(&app, &cookie, id).await;
    assert!(html.contains("Refused · "), "the command row");
    assert!(html.contains("id=\"send-pin\""));
    assert!(send().await.location().unwrap().contains("notice=pin_sent"));
    assert_eq!(pin_rows(&app, id).await.len(), 2);
    assert_eq!(events(&app, "kid_pin_sent").await.len(), 2);
    // No copy kept: set it again.
    sqlx::query("UPDATE device_policy SET kid_pin_sealed = NULL WHERE device_id = ?")
        .bind(id)
        .execute(&app.db)
        .await
        .unwrap();
    assert!(
        send()
            .await
            .location()
            .unwrap()
            .contains("notice=pin_not_kept")
    );
    // The summary says it too, and the Lock row says the PIN is on.
    let summary = plain(
        app.get_page(&format!("/devices/{id}"), &cookie)
            .await
            .text(),
    );
    assert!(
        summary.contains("Phone's PIN isn't the Child PIN"),
        "the Attention row"
    );
    assert!(summary.contains(">PIN on<"), "{summary}");
    assert!(!summary.contains("Android's lock<"), "{summary}");
}

/// Design 132: an older launcher (no capability) gets no update note, and own mode's notes ("switching back",
/// "Android's PIN is still on") are gone - an older build's own words read as the generic line.
#[tokio::test]
async fn the_page_has_no_own_mode_words() {
    let (app, cookie, id, token) = setup().await;
    report(&app, &token, &["pin_lock_v1"], json!({ "active": true })).await;
    let html = page(&app, &cookie, id).await;
    assert!(!html.contains("Update Vibb One"));

    let mut state = lock_state("android", json!(1), true);
    state["inactive"] = json!("android_credential");
    report(&app, &token, &CAPS, state).await;
    let html = page(&app, &cookie, id).await;
    for gone in [
        "Android's PIN is still on",
        "screen lock is still on",
        "Switching back",
        "Vibb One's lock",
    ] {
        assert!(!html.contains(gone), "{gone}: {html}");
    }
    assert!(html.contains("android_credential"), "behind the ⓘ: {html}");
}

/// A phone added on the Devices tab: without a Child PIN the policy has no `kid_lock` (no lock at all) and the page
/// offers no Remove; the first Child PIN makes the policy's `keyguard` "android" and queues the PIN for a phone that
/// can take it. A phone made before design 132 (once `kid_keyguard = 'own'`) gets "android" too (design 132 §6).
#[tokio::test]
async fn a_new_phone_gets_androids_lock_once_the_child_pin_is_set() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let res = app
        .request_form(
            Method::POST,
            "/devices/new",
            Some(&cookie),
            &[("name", "Emma")],
        )
        .await;
    assert!(res.status.is_redirection(), "{}", res.text());
    let id: i64 = sqlx::query_scalar("SELECT id FROM devices WHERE name = 'Emma'")
        .fetch_one(&app.db)
        .await
        .unwrap();
    let code = app.new_code(id, crate::enrollment::Kind::Qr).await;
    let res = app
        .request(
            Method::POST,
            "/api/devices/enroll",
            None,
            Some(crate::tests::enroll_body(&code)),
        )
        .await;
    assert_eq!(res.status, StatusCode::OK);
    let token = res.json()["device_token"].as_str().unwrap().to_string();

    // No Child PIN: no lock on the phone, and no word of Android's lock to it either.
    assert!(app.policy(&token).await["kid_lock"].is_null());
    report(&app, &token, &CAPS, json!({ "active": false })).await;
    let html = page(&app, &cookie, id).await;
    assert!(!html.contains("name=\"clear_kid_pin\""), "no PIN to remove");

    // The Child PIN: Android's lock from the first policy that carries it.
    for (route, field, pin) in [
        ("parent-pin", "new_pin", "5829317"),
        ("kid-lock", "new_kid_pin", "4826"),
    ] {
        let res = app
            .request_form(
                Method::POST,
                &format!("/devices/{id}/{route}"),
                Some(&cookie),
                &[(field, pin)],
            )
            .await;
        assert_eq!(res.status, StatusCode::SEE_OTHER, "{route}");
    }
    assert_eq!(pin_rows(&app, id).await.len(), 1, "the PIN goes out");
    let html = page(&app, &cookie, id).await;
    assert!(html.contains("Child PIN to the phone"));
    let policy = app.policy(&token).await;
    assert_eq!(policy["kid_lock"]["keyguard"], "android");
    assert_eq!(policy["pending_command"]["command"], "set_kid_pin");

    // A phone made by the test helper's plain insert (as phones made before 2026-11-16 were): "android" too.
    let (old, old_token) = app.enrolled_device("Mamma's old phone").await;
    for (route, field, pin) in [
        ("parent-pin", "new_pin", "5829317"),
        ("kid-lock", "new_kid_pin", "4826"),
    ] {
        app.request_form(
            Method::POST,
            &format!("/devices/{old}/{route}"),
            Some(&cookie),
            &[(field, pin)],
        )
        .await;
    }
    assert_eq!(
        app.policy(&old_token).await["kid_lock"]["keyguard"],
        "android"
    );
}

/// Design 132 Q1 (a): the Child PIN is changed, never removed - the page has no Remove form and no row about it, a
/// post from an older page is refused with nothing written. The labels in Norwegian.
#[tokio::test]
async fn the_child_pin_is_changed_never_removed() {
    let (app, cookie, id, token) = setup().await;
    let html = page(&app, &cookie, id).await;
    assert!(!html.contains("name=\"clear_kid_pin\""), "{html}");
    assert!(!html.contains("data-row=\"remove-needs-pin\""), "{html}");
    assert!(!html.contains("Remove Child PIN"), "{html}");

    let res = app
        .request_form(
            Method::POST,
            &format!("/devices/{id}/kid-lock"),
            Some(&cookie),
            &[("clear_kid_pin", "on")],
        )
        .await;
    assert_eq!(
        res.location(),
        Some(format!("/devices/{id}/lock?notice=not_removed#child-pin").as_str())
    );
    let policy = app.policy(&token).await;
    assert_eq!(policy["kid_lock"]["keyguard"], "android");
    assert!(events(&app, "kid_pin_cleared").await.is_empty());

    let nb = plain(
        app.get_page(
            &format!("/devices/{id}/lock?lang=nb&notice=not_removed"),
            &cookie,
        )
        .await
        .text(),
    );
    assert!(nb.contains("Ikke fjernet: barne-PIN-en kan byttes, ikke fjernes."));
    assert!(!nb.contains("Fjern barne-PIN"), "{nb}");
    assert!(!nb.contains("Vibb Ones lås"), "{nb}");
}

/// Design 132 §5 (QA L3): migration 0180 drops `kid_keyguard`. From the previous head's schema, with an own and an
/// android row (and a legacy PIN without a sealed copy): the column goes, both rows serve `keyguard: "android"`, the
/// legacy PIN is counted (§4.2's start-up line); and a backup of that older database passes the backup check (it is
/// migrated forward into this schema, as a restore is).
#[tokio::test]
async fn the_lock_switch_column_goes_and_every_row_serves_androids_lock() {
    let dir = tempfile::tempdir().unwrap();
    let path = dir.path().join("old.db");
    let options = sqlx::sqlite::SqliteConnectOptions::new()
        .filename(&path)
        .create_if_missing(true)
        .foreign_keys(true);
    let db = sqlx::SqlitePool::connect_with(options).await.unwrap();
    let full = sqlx::migrate!("./migrations");
    let mut old = sqlx::migrate!("./migrations");
    old.migrations = full
        .migrations
        .iter()
        .filter(|m| m.version < 180)
        .cloned()
        .collect::<Vec<_>>()
        .into();
    old.run(&db).await.unwrap();
    let (hash, salt) = crate::security::hash_pin("4826");
    let mut ids = Vec::new();
    for (keyguard, sealed) in [
        ("own", Some("sealed")),
        ("android", Some("sealed")),
        ("own", None),
    ] {
        let id: i64 = sqlx::query_scalar("INSERT INTO devices (name) VALUES ('old') RETURNING id")
            .fetch_one(&db)
            .await
            .unwrap();
        sqlx::query(
            "INSERT INTO device_policy (device_id, kiosk_desired, kid_pin_hash, kid_pin_salt, kid_pin_length, \
             kid_keyguard, kid_pin_sealed) VALUES (?, 1, ?, ?, 4, ?, ?)",
        )
        .bind(id)
        .bind(&hash)
        .bind(&salt)
        .bind(keyguard)
        .bind(sealed)
        .execute(&db)
        .await
        .unwrap();
        ids.push(id);
    }
    db.close().await;

    // A backup of the older database: the check migrates a scratch copy and compares it with this schema.
    let data = dir.path().join("data");
    let backups = data.join("backups");
    std::fs::create_dir_all(&backups).unwrap();
    let zip_path = backups.join("vibb-backup-old.zip");
    {
        use std::io::Write;
        let mut writer = zip::ZipWriter::new(std::fs::File::create(&zip_path).unwrap());
        writer
            .start_file("kidphone.db", zip::write::SimpleFileOptions::default())
            .unwrap();
        writer.write_all(&std::fs::read(&path).unwrap()).unwrap();
        writer.finish().unwrap();
    }
    assert!(
        matches!(
            crate::handlers::backups::check_file(&data, &zip_path).await,
            crate::handlers::backups::Checked::Ok
        ),
        "the older backup restores into the new schema"
    );

    // The live database migrated forward.
    let options = sqlx::sqlite::SqliteConnectOptions::new()
        .filename(&path)
        .foreign_keys(true);
    let db = sqlx::SqlitePool::connect_with(options).await.unwrap();
    full.run(&db).await.unwrap();
    let column: i64 = sqlx::query_scalar(
        "SELECT COUNT(*) FROM pragma_table_info('device_policy') WHERE name = 'kid_keyguard'",
    )
    .fetch_one(&db)
    .await
    .unwrap();
    assert_eq!(column, 0);
    for id in &ids {
        let policy = sqlx::query_as::<_, crate::models::DevicePolicy>(
            "SELECT * FROM device_policy WHERE device_id = ?",
        )
        .bind(id)
        .fetch_one(&db)
        .await
        .unwrap();
        let lock = crate::kid_lock::policy_kid_lock(&policy).expect("the Child PIN stays");
        assert_eq!(lock.keyguard, "android", "{id}");
    }
    assert_eq!(crate::kid_lock::legacy_pins(&db).await.unwrap(), [ids[2]]);
}

/// Design 59 step 1 (`docs/design/59-android-keyguard-design.md` §1, §2.1; `tests::kid_keyguard` until design 132
/// folded it in here): the Child PIN sealed at every save, the PIN the phone last confirmed kept beside it,
/// `kid_lock.keyguard`/`pin_rev`, and the one-shot `set_kid_pin` - when it is queued (the parent's saves and the
/// status intake), what it carries when it goes out.
mod queue {
    use axum::http::{Method, StatusCode, header};
    use serde_json::{Value, json};

    use crate::tests::TestApp;

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
        let res = app
            .send_via(router, crate::tests::LOOPBACK_PEER, request)
            .await;
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
        /// The sealed PIN and the confirmed one, opened.
        pin: Option<String>,
        prev: Option<String>,
        prev_sealed: bool,
        prev_rev: Option<i64>,
        rev: i64,
    }

    async fn kept(app: &TestApp, id: i64) -> Kept {
        let (sealed, prev_sealed, prev_rev, rev): (
            Option<String>,
            Option<String>,
            Option<i64>,
            i64,
        ) = sqlx::query_as(
            "SELECT kid_pin_sealed, kid_pin_prev_sealed, kid_pin_prev_rev, kid_pin_rev \
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
            pin: open(&sealed),
            prev: open(&prev_sealed),
            prev_sealed: prev_sealed.is_some(),
            prev_rev,
            rev,
        }
    }

    /// A status report with `capabilities`, and `lock_state` when given.
    async fn report(app: &TestApp, token: &str, caps: &[&str], lock_state: Option<Value>) {
        let mut body =
            json!({ "lock_reason": "NONE", "kiosk_engaged": true, "capabilities": caps });
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

    async fn fetch(app: &TestApp, token: &str) -> crate::tests::TestResponse {
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
        report(&app, &token, &CAPS, lock_state("android", json!(1))).await;
        assert_eq!(kept(&app, id).await.prev_rev, Some(0));
        report(&app, &token, &CAPS, lock_state("android", json!(2))).await;
        let confirmed = kept(&app, id).await;
        assert_eq!((confirmed.prev_sealed, confirmed.prev_rev), (false, None));

        // From a confirmed sealed PIN: kept over two saves.
        save(&app, &cookie, id, "1122").await;
        save(&app, &cookie, id, "3344").await;
        assert_eq!(
            kept(&app, id).await,
            Kept {
                pin: Some("3344".to_string()),
                prev: Some("8642".to_string()),
                prev_sealed: true,
                prev_rev: Some(2),
                rev: 4,
            }
        );
        let policy = fetch(&app, &token).await.json();
        assert_eq!(policy["kid_lock"]["pin_rev"], 4);
        assert_eq!(policy["kid_lock"]["keyguard"], "android");
        // The sealed PIN is never in the policy.
        let sealed: String =
            sqlx::query_scalar("SELECT kid_pin_sealed FROM device_policy WHERE device_id = ?")
                .bind(id)
                .fetch_one(&app.db)
                .await
                .unwrap();
        let text = policy.to_string();
        assert!(!text.contains(&sealed[3..20]), "{text}");
        // The two saves' row went out with this fetch, carrying the newest PIN.
        assert_eq!(policy["pending_command"]["pin"], "3344");
    }

    /// Design 132 Q1 (a): a Remove from an older page is refused - the PIN, its rev and the waiting command kept.
    #[tokio::test]
    async fn removing_is_refused_and_the_command_still_waits() {
        let (app, cookie, id, token) = setup().await;
        save(&app, &cookie, id, "1357").await;
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
        assert_eq!(
            res.location(),
            Some(format!("/devices/{id}/lock?notice=not_removed#child-pin").as_str())
        );
        let still = kept(&app, id).await;
        assert_eq!((still.pin.as_deref(), still.rev), (Some("2468"), 2));
        let rows = pin_rows(&app, id).await;
        assert_eq!(
            (rows.len(), rows[0].1, rows[0].2.as_deref()),
            (1, false, None),
            "still waiting"
        );
        assert_eq!(
            fetch(&app, &token).await.json()["pending_command"]["pin"],
            "2468"
        );
    }

    /// Design 132 §4.2: without the key file the PIN could never reach Android's lock, so a save is refused with
    /// nothing written (the phone keeps the PIN it has) and the flash says why in one sentence.
    #[tokio::test]
    async fn without_the_key_a_pin_is_not_saved() {
        let (app, cookie, id, token) = setup().await;
        save(&app, &cookie, id, "1357").await;
        report(&app, &token, &CAPS, lock_state("android", json!(1))).await;
        let mut keyless = app.state.clone();
        keyless.kid_pin_key = None;
        let keyless = app.admin_router(keyless);
        assert_eq!(
            save_via(&app, &keyless, &cookie, id, "2468").await,
            "save_not_kept"
        );
        let now = kept(&app, id).await;
        assert_eq!((now.pin.as_deref(), now.rev), (Some("1357"), 1));
        assert!(pin_rows(&app, id).await.is_empty());
        let policy = fetch(&app, &token).await.json();
        assert!(crate::security::verify_pin(
            "1357",
            policy["kid_lock"]["pin_hash"].as_str().unwrap(),
            policy["kid_lock"]["pin_salt"].as_str().unwrap()
        ));
        let html = app
            .get_page(&format!("/devices/{id}/lock?notice=save_not_kept"), &cookie)
            .await
            .text();
        assert!(
            html.contains("Not saved: this server can&#39;t read its key file."),
            "{html}"
        );
        // A first Child PIN without the key: nothing either (no safe-boot block, no PIN).
        let (two, two_token) = app.enrolled_device("two").await;
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
            "save_not_kept"
        );
        assert!(fetch(&app, &two_token).await.json()["kid_lock"].is_null());
    }

    /// §2.1's queue rule at a save: a sealed PIN, the capability in the last report and none waiting - each off, no
    /// row; a row that went out doesn't block the next save's.
    #[tokio::test]
    async fn a_save_queues_set_kid_pin_only_for_a_phone_that_can_take_it() {
        let (app, cookie, id, token) = setup().await;
        // A launcher without the capability (an older one): nothing.
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
    /// for a phone whose last report has the capability, `no-store`; never after the
    /// receipt; and the keys pinned with and without it.
    #[tokio::test]
    async fn set_kid_pin_carries_the_pin_only_where_it_may() {
        let (app, cookie, id, token) = setup().await;
        save(&app, &cookie, id, "1357").await;
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
        // another phone's sealed PIN): the command without the PIN.
        report(&app, &token, &["pin_lock_v1", "command_receipt_v1"], None).await;
        queue("set_kid_pin").await;
        sent_without_pin(&app, &token).await;
        report(&app, &token, &CAPS, None).await;
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

    /// §2.1 at the status intake, with qa-59-revision L-2 and design 132 §4.5: a phone gets the PIN when it has no
    /// Android PIN (`inactive: no_android_pin`, or an older build's own lock) or reports a stale rev - never twice
    /// (waiting, in flight), never for `null` (a PIN changed on the phone), never again once the newest row was answered
    /// (a refusal, or a success the report didn't follow: no loop).
    #[tokio::test]
    async fn a_status_report_sends_the_pin_to_a_phone_that_needs_it() {
        let (app, cookie, id, token) = setup().await;
        // Saved while the phone ran an older Vibb One: no row.
        report(&app, &token, &["pin_lock_v1", "command_receipt_v1"], None).await;
        save(&app, &cookie, id, "1357").await;
        assert!(pin_rows(&app, id).await.is_empty());

        // Updated to a design 59 build: it reports its own lock with the current rev. Queued once, also while in flight.
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
        // Design 132's launcher with no Android PIN: the same rule - answered already, no loop; the parent's Send again
        // tries again.
        report(&app, &token, &CAPS, no_android_pin()).await;
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

    /// Design 132 §4.5: what a launcher without the keypad reports when Android has no credential.
    fn no_android_pin() -> Option<Value> {
        Some(
            json!({ "active": false, "inactive": "no_android_pin", "keyguard": "android",
                 "pin_rev": null, "reset_token": true }),
        )
    }

    /// Design 132 §4.5: a phone without an Android PIN is quiet while the Child PIN is on its way (waiting, in flight);
    /// once the newest was answered without the PIN landing - a refusal, or the PIN removed on the phone after it was
    /// set - it is the orange line and Attention's alarm, with Send again; the intake never loops.
    #[tokio::test]
    async fn no_android_pin_is_quiet_only_while_the_pin_is_on_its_way() {
        let (app, cookie, id, token) = setup().await;
        report(&app, &token, &CAPS, None).await;
        save(&app, &cookie, id, "1357").await;
        let line = "The phone has no PIN";
        let alarm = "data-attention=\"child-lock-off\"";
        // Waiting.
        report(&app, &token, &CAPS, no_android_pin()).await;
        assert_eq!(pin_rows(&app, id).await.len(), 1);
        assert!(
            !lock_page(&app, &cookie, id).await.contains(line),
            "waiting: quiet"
        );
        assert!(!summary(&app, &cookie, id).await.contains(alarm));
        // Out to the phone: still quiet.
        let sent = fetch(&app, &token).await.json()["pending_command"].clone();
        assert!(
            !lock_page(&app, &cookie, id).await.contains(line),
            "in flight: quiet"
        );
        assert!(!summary(&app, &cookie, id).await.contains(alarm));
        // Refused: the orange line and the alarm, Send again offered; no new row.
        answer(
            &app,
            &token,
            sent["id"].as_i64().unwrap(),
            false,
            "refused: failed",
        )
        .await;
        report(&app, &token, &CAPS, no_android_pin()).await;
        assert_eq!(pin_rows(&app, id).await.len(), 1, "no loop");
        let page = lock_page(&app, &cookie, id).await;
        assert_eq!(page.matches(line).count(), 1, "{page}");
        assert!(page.contains("Send the Child PIN again below."), "{page}");
        assert!(page.contains("id=\"send-pin\""), "{page}");
        assert!(summary(&app, &cookie, id).await.contains(alarm));
        // The same for an older build's own lock.
        report(&app, &token, &CAPS, lock_state("own", json!(1))).await;
        assert!(summary(&app, &cookie, id).await.contains(alarm));
        // Send again: quiet until it is answered; set, the phone runs it.
        let res = app
            .request_form(
                Method::POST,
                &format!("/devices/{id}/kid-pin/send"),
                Some(&cookie),
                &[],
            )
            .await;
        assert_eq!(
            res.location(),
            Some(format!("/devices/{id}/lock?notice=pin_sent#child-pin").as_str())
        );
        assert!(!lock_page(&app, &cookie, id).await.contains(line));
        assert!(!summary(&app, &cookie, id).await.contains(alarm));
        let sent = fetch(&app, &token).await.json()["pending_command"].clone();
        answer(&app, &token, sent["id"].as_i64().unwrap(), true, "pin set").await;
        report(&app, &token, &CAPS, lock_state("android", json!(1))).await;
        assert!(!lock_page(&app, &cookie, id).await.contains(line));
        // The PIN removed on the phone afterwards: said at once, the intake doesn't send it again by itself.
        report(&app, &token, &CAPS, no_android_pin()).await;
        assert_eq!(pin_rows(&app, id).await.len(), 2);
        assert_eq!(lock_page(&app, &cookie, id).await.matches(line).count(), 1);
        assert!(summary(&app, &cookie, id).await.contains(alarm));
    }

    async fn lock_page(app: &TestApp, cookie: &str, id: i64) -> String {
        app.get_page(&format!("/devices/{id}/lock"), cookie)
            .await
            .text()
    }

    async fn summary(app: &TestApp, cookie: &str, id: i64) -> String {
        app.get_page(&format!("/devices/{id}"), cookie).await.text()
    }

    /// qa-59-step1 S59-1: a save that commits while a poll is building its answer. The poll read the
    /// older rev, so its pop leaves the row waiting - it never goes out with the older PIN, which the
    /// phone would confirm and the intake then couldn't heal - and the next poll carries the new PIN.
    #[tokio::test]
    async fn a_save_racing_a_poll_never_loses_the_new_pin() {
        let (app, cookie, id, token) = setup().await;
        save(&app, &cookie, id, "1357").await;
        report(&app, &token, &CAPS, None).await;
        // The poll read rev 1; the save of 2468 commits before its pop (the row it queued waits).
        save(&app, &cookie, id, "2468").await;
        assert_eq!(kept(&app, id).await.rev, 2);
        let stale = crate::handlers::device_api::pop_command(&app.db, id, 1)
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
        let ring = crate::handlers::device_api::pop_command(&app.db, id, 1)
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
                Some(crate::tests::enroll_body(&code)),
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
}
