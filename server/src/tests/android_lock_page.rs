//! Design 59 step 2 (`docs/design/59-android-keyguard-design.md` §2.2, §2.3): Lock Screen & PINs'
//! side of Android's lock - the switch, "Show Child PIN…" behind the password (and the one re-auth
//! rule it shares with the password and 2FA forms), "Send the Child PIN again", and what the
//! page and the summary say about the phone. The words per case are `kid_lock::tests`'.

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

async fn switch(app: &TestApp, cookie: &str, id: i64, keyguard: &str) -> super::TestResponse {
    app.request_form(
        Method::POST,
        &format!("/devices/{id}/kid-lock/keyguard"),
        Some(cookie),
        &[("keyguard", keyguard)],
    )
    .await
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
    sqlx::query_scalar("SELECT detail FROM security_events WHERE event_type = ? ORDER BY id")
        .bind(kind)
        .fetch_all(&app.db)
        .await
        .unwrap()
}

/// §2.3: the switch needs a Child PIN, saves, logs a change, queues the PIN for a phone that can
/// take it and cancels it again on the way back; every answer lands on the Child PIN group.
#[tokio::test]
async fn the_switch_saves_queues_and_cancels() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("Anna").await;
    // No Child PIN: refused, nothing written, the select disabled.
    let res = switch(&app, &cookie, id, "android").await;
    assert_eq!(
        res.location(),
        Some(format!("/devices/{id}/lock?notice=keyguard_no_pin#lock-keyguard").as_str())
    );
    assert!(
        page(&app, &cookie, id)
            .await
            .contains("name=\"keyguard\" data-autosave disabled")
    );
    let res = app
        .request_form(
            Method::POST,
            &format!("/devices/{id}/parent-pin"),
            Some(&cookie),
            &[("new_pin", "5829317")],
        )
        .await;
    assert!(res.status.is_redirection());
    report(&app, &token, &CAPS, lock_state("own", json!(0), true)).await;
    let res = app
        .request_form(
            Method::POST,
            &format!("/devices/{id}/kid-lock"),
            Some(&cookie),
            &[("new_kid_pin", "4826")],
        )
        .await;
    assert!(res.status.is_redirection());
    assert!(pin_rows(&app, id).await.is_empty(), "own: nothing to send");

    for bad in ["", "ANDROID", "pin"] {
        assert_eq!(
            switch(&app, &cookie, id, bad).await.status,
            StatusCode::BAD_REQUEST
        );
    }
    assert_eq!(
        switch(&app, &cookie, 999, "android").await.status,
        StatusCode::NOT_FOUND
    );

    let res = switch(&app, &cookie, id, "android").await;
    assert_eq!(
        res.location(),
        Some(format!("/devices/{id}/lock#lock-keyguard").as_str())
    );
    app.assert_lands(&res, &cookie).await;
    assert_eq!(pin_rows(&app, id).await.len(), 1, "the kept PIN goes out");
    // The same choice again: no second row, no second log line.
    switch(&app, &cookie, id, "android").await;
    assert_eq!(pin_rows(&app, id).await.len(), 1);
    let html = page(&app, &cookie, id).await;
    assert!(html.contains("<option value=\"android\" selected>Android's lock</option>"));
    assert!(html.contains("Sending the PIN"), "the waiting row");
    assert!(html.contains("data-privacy=\"android\""));
    // Locate says what waits for the PIN after a restart.
    let locate = format!("/devices/locate?device={id}");
    assert!(
        app.get_page(&locate, &cookie)
            .await
            .text()
            .contains("data-note=\"android-lock\"")
    );

    switch(&app, &cookie, id, "own").await;
    assert_eq!(
        pin_rows(&app, id).await[0].1.as_deref(),
        Some("keyguard_own"),
        "the waiting row is cancelled"
    );
    assert_eq!(
        events(&app, "kid_keyguard_changed").await,
        [
            Some(format!("device {id}: Android's lock")),
            Some(format!("device {id}: Vibb One's lock")),
        ]
    );
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
    assert!(text.contains("<script src=\"/static/reveal.js?v=1\"></script>"));
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
        [Some(format!("device {id}"))]
    );
    assert_eq!(events(&app, "kid_pin_reveal_refused").await.len(), 1);
    let logged: Vec<String> =
        sqlx::query_scalar("SELECT event_type || COALESCE(detail, '') FROM security_events")
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
    report(&app, &token, &CAPS, lock_state("own", json!(1), true)).await;
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
    report(&app, &token, &CAPS, lock_state("own", json!(0), true)).await;
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
    report(
        &app,
        &token,
        &CAPS,
        lock_state("android", json!(null), true),
    )
    .await;
    let route = format!("/devices/{id}/kid-pin/send");
    let send = || app.request_form(Method::POST, &route, Some(&cookie), &[]);
    // Vibb One's lock is chosen: nothing to send.
    assert!(
        send()
            .await
            .location()
            .unwrap()
            .contains("notice=pin_not_sent#child-pin")
    );
    let res = switch(&app, &cookie, id, "android").await;
    assert!(res.status.is_redirection());
    // The switch queued it (a kept PIN): no button while it waits, and a second send is refused.
    let html = page(&app, &cookie, id).await;
    assert!(!html.contains("id=\"send-pin\""));
    assert!(
        html.contains("Phone's PIN isn't the Child PIN"),
        "pin_rev null in android mode"
    );
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
    let res = send().await;
    assert_eq!(
        res.location(),
        Some(format!("/devices/{id}/lock?notice=pin_sent#child-pin").as_str())
    );
    app.assert_lands(&res, &cookie).await;
    assert_eq!(pin_rows(&app, id).await.len(), 2);
    assert_eq!(events(&app, "kid_pin_sent").await.len(), 1);
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
    // The summary says it too, and the Lock row names Android's lock.
    let summary = plain(
        app.get_page(&format!("/devices/{id}"), &cookie)
            .await
            .text(),
    );
    assert!(
        summary.contains("Phone's PIN isn't the Child PIN"),
        "the Attention row"
    );
    assert!(summary.contains("PIN on · Android's lock"));
}

/// §2.3 and qa-59-revision N-C: an older launcher with Android's lock chosen needs an update; a
/// phone still on Android's lock while Vibb One's is chosen again without its token says
/// "switching back" with the way out, never own mode's "remove Android's lock".
#[tokio::test]
async fn the_page_words_the_phone_against_the_choice() {
    let (app, cookie, id, token) = setup().await;
    switch(&app, &cookie, id, "android").await;
    report(&app, &token, &["pin_lock_v1"], json!({ "active": true })).await;
    let html = page(&app, &cookie, id).await;
    assert!(html.contains("Update Vibb One for Android"));

    switch(&app, &cookie, id, "own").await;
    let mut state = lock_state("android", json!(1), false);
    state["inactive"] = json!("android_credential");
    report(&app, &token, &CAPS, state).await;
    let html = page(&app, &cookie, id).await;
    assert!(html.contains("Android's PIN is still on"));
    assert!(!html.contains("screen lock is still on"));
    let summary = plain(
        app.get_page(&format!("/devices/{id}"), &cookie)
            .await
            .text(),
    );
    assert!(
        !summary.contains("Lock is off on the phone"),
        "Android's PIN is the gate"
    );
}
