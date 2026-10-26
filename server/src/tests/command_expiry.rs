//! Command expiry and cancel (the user's decision, 2026-10-12): a lock or a wipe waits for the phone
//! however long it takes; a pause, a ring, its stop or a location update expires when the phone
//! doesn't fetch it within an hour; a command still waiting can be cancelled on the Locate page.
//! A cancelled or expired command never reaches the phone - first, again (L12) or after an
//! enrollment.

use axum::http::{Method, StatusCode};
use serde_json::json;

use super::TestApp;

/// A command asked for `requested` (an SQL time expression), not delivered yet.
async fn insert(app: &TestApp, device: i64, command: &str, requested: &str) -> i64 {
    sqlx::query_scalar(&format!(
        "INSERT INTO device_commands (device_id, command, requested_at) VALUES (?, ?, {requested}) \
         RETURNING id"
    ))
    .bind(device)
    .bind(command)
    .fetch_one(&app.db)
    .await
    .unwrap()
}

async fn report_receipts(app: &TestApp, token: &str) {
    let res = app
        .request(
            Method::POST,
            "/api/devices/status",
            Some(token),
            Some(json!({ "lock_reason": "NONE", "kiosk_engaged": true,
                         "capabilities": ["command_receipt_v1"] })),
        )
        .await;
    assert_eq!(res.status, StatusCode::NO_CONTENT);
}

async fn cancel(app: &TestApp, cookie: &str, device: i64, command: i64) -> Option<String> {
    let res = app
        .request_form(
            Method::POST,
            &format!("/devices/{device}/commands/{command}/cancel"),
            Some(cookie),
            &[],
        )
        .await;
    assert!(res.status.is_redirection(), "{}", res.status);
    res.location().map(str::to_string)
}

async fn locate_page(app: &TestApp, cookie: &str, device: i64) -> String {
    app.get_page(&format!("/devices/locate?device={device}"), cookie)
        .await
        .text()
}

/// `command`'s newest row in the Locate page's Requests or the summary's Activity.
fn row<'a>(page: &'a str, command: &str) -> &'a str {
    super::locate_page::recent_row(page, command)
}

#[tokio::test]
async fn a_pause_ring_stop_or_locate_expires_after_an_hour_at_the_boundary() {
    let app = TestApp::new().await;
    let (device, token) = app.enrolled_device("phone").await;
    let past = "datetime('now', '-3601 seconds')";
    for command in ["start_pause", "ring", "locate"] {
        insert(&app, device, command, past).await;
    }
    let stop = insert(&app, device, "stop_ring", "datetime('now', '-59 minutes')").await;
    assert_eq!(
        app.policy(&token).await["pending_command"]["id"],
        stop,
        "59 minutes: still sent; an hour and a second: never"
    );
    assert!(app.policy(&token).await["pending_command"].is_null());
}

#[tokio::test]
async fn a_lock_or_wipe_waits_however_long_it_takes() {
    let app = TestApp::new().await;
    let (device, token) = app.enrolled_device("phone").await;
    let lock = insert(&app, device, "lock", "datetime('now', '-21 days')").await;
    let wipe = insert(&app, device, "wipe", "datetime('now', '-20 days')").await;
    let again = insert(&app, device, "end_windows", "datetime('now', '-2 hours')").await;
    for expected in [lock, wipe, again] {
        assert_eq!(app.policy(&token).await["pending_command"]["id"], expected);
    }
}

/// L12's re-delivery stops at the hour too; a lock's goes on.
#[tokio::test]
async fn an_expiring_command_is_never_sent_again_past_its_hour() {
    let app = TestApp::new().await;
    let (device, token) = app.enrolled_device("phone").await;
    report_receipts(&app, &token).await;
    for command in ["ring", "lock"] {
        sqlx::query(
            "INSERT INTO device_commands (device_id, command, requested_at, delivered_at, \
             first_delivered_at, deliveries) VALUES (?, ?, datetime('now', '-61 minutes'), \
             datetime('now', '-3 minutes'), datetime('now', '-5 minutes'), 1)",
        )
        .bind(device)
        .bind(command)
        .execute(&app.db)
        .await
        .unwrap();
    }
    assert_eq!(
        app.policy(&token).await["pending_command"]["command"],
        "lock"
    );
    assert!(app.policy(&token).await["pending_command"].is_null());
    let cookie = app.admin_cookie().await;
    let page = locate_page(&app, &cookie, device).await;
    assert!(
        row(&page, "ring").contains("Not confirmed"),
        "{}",
        row(&page, "ring")
    );
}

#[tokio::test]
async fn a_cancelled_command_never_reaches_the_phone() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (device, token) = app.enrolled_device("phone").await;
    report_receipts(&app, &token).await;
    let wipe = insert(&app, device, "wipe", "datetime('now', '-1 minutes')").await;
    let lock = insert(&app, device, "lock", "datetime('now')").await;
    let page = locate_page(&app, &cookie, device).await;
    for id in [wipe, lock] {
        assert!(
            page.contains(&format!("/devices/{device}/commands/{id}/cancel")),
            "a waiting command has its Cancel"
        );
    }
    assert_eq!(
        cancel(&app, &cookie, device, wipe).await.as_deref(),
        Some(format!("/devices/locate?device={device}&cancel=done#recent").as_str()),
        "back to Recent, where the line says it was cancelled"
    );
    // The newer lock still goes; the cancelled wipe never, also once time has passed.
    assert_eq!(app.policy(&token).await["pending_command"]["id"], lock);
    sqlx::query(
        "UPDATE device_commands SET requested_at = datetime('now', '-1 days') WHERE id = ?",
    )
    .bind(wipe)
    .execute(&app.db)
    .await
    .unwrap();
    assert!(app.policy(&token).await["pending_command"].is_null());
    let page = app
        .get_page(
            &format!("/devices/locate?device={device}&cancel=done"),
            &cookie,
        )
        .await
        .text();
    assert!(page.contains("Cancelled. The phone will never get it."));
    // Requests leaves out what the parent cancelled (its line says so, design 48 §1.4); the
    // summary's Activity keeps it.
    assert!(!page.contains("data-command=\"wipe\""), "{page}");
    let page = app
        .get_page(&format!("/devices/{device}"), &cookie)
        .await
        .text();
    assert!(
        row(&page, "wipe").contains(">Cancelled<"),
        "{}",
        row(&page, "wipe")
    );
    assert!(row(&page, "wipe").contains("Never sent."));
    // Local time, never UTC (design 31 M4; QA of cmd-expiry Low 5 labelled it while it was UTC).
    assert!(
        !row(&page, "wipe").contains("UTC"),
        "{}",
        row(&page, "wipe")
    );
    assert!(
        !page.contains(&format!("/commands/{wipe}/cancel")),
        "no Cancel any more"
    );
    let (actor, detail): (Option<String>, Option<String>) = sqlx::query_as(
        "SELECT username, detail FROM security_events WHERE event_type = 'device_command_cancelled'",
    )
    .fetch_one(&app.db)
    .await
    .unwrap();
    assert!(actor.is_some(), "the parent who cancelled");
    assert!(detail.unwrap().starts_with("wipe (asked "));
}

/// Cancel races the policy's pop: whichever comes first wins. A command the phone fetched (and
/// may get again under L12) can't be cancelled, nor can an expired one, nor another phone's.
#[tokio::test]
async fn too_late_to_cancel() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (device, token) = app.enrolled_device("phone").await;
    let (other, _) = app.enrolled_device("sibling").await;
    report_receipts(&app, &token).await;
    let lock = insert(&app, device, "lock", "datetime('now')").await;
    assert_eq!(app.policy(&token).await["pending_command"]["id"], lock);
    let too_late = Some(format!(
        "/devices/locate?device={device}&cancel=too_late#recent"
    ));
    assert_eq!(cancel(&app, &cookie, device, lock).await, too_late);
    // Still offered again after the gap: L12 goes on for what the phone may have lost.
    sqlx::query(
        "UPDATE device_commands SET delivered_at = datetime('now', '-3 minutes') WHERE id = ?",
    )
    .bind(lock)
    .execute(&app.db)
    .await
    .unwrap();
    assert_eq!(app.policy(&token).await["pending_command"]["id"], lock);

    let expired = insert(&app, device, "ring", "datetime('now', '-2 hours')").await;
    assert_eq!(cancel(&app, &cookie, device, expired).await, too_late);
    let page = app
        .get_page(
            &format!("/devices/locate?device={device}&cancel=too_late"),
            &cookie,
        )
        .await
        .text();
    assert!(page.contains("Too late to cancel"));

    let wipe = insert(&app, device, "wipe", "datetime('now')").await;
    assert_eq!(
        cancel(&app, &cookie, other, wipe).await,
        Some(format!(
            "/devices/locate?device={other}&cancel=too_late#recent"
        )),
        "another phone's path can't cancel it"
    );
    let cancelled: Option<String> =
        sqlx::query_scalar("SELECT cancelled_at FROM device_commands WHERE id = ?")
            .bind(wipe)
            .fetch_one(&app.db)
            .await
            .unwrap();
    assert_eq!(cancelled, None);
}

/// The Locate page's states: waiting (with when an expiring one expires) and its Cancel, expired
/// (no Cancel), cancelled by an enrollment.
#[tokio::test]
async fn the_locate_page_shows_waiting_expired_and_cancelled() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (device, _) = app.enrolled_device("phone").await;
    let ring = insert(&app, device, "ring", "datetime('now', '-2 hours')").await;
    let fresh = insert(&app, device, "locate", "datetime('now', '-5 minutes')").await;
    let lock = insert(&app, device, "lock", "datetime('now', '-3 days')").await;
    let page = locate_page(&app, &cookie, device).await;
    assert!(row(&page, "ring").contains(">Expired<"));
    assert!(
        row(&page, "ring").contains("Never sent: the phone wasn&#39;t back within an hour."),
        "{}",
        row(&page, "ring")
    );
    assert!(
        !page.contains(&format!("/commands/{ring}/cancel")),
        "nothing to cancel"
    );
    assert!(row(&page, "locate").contains(">Waiting<"));
    assert!(
        row(&page, "locate").contains("Expires ")
            && row(&page, "locate").contains(" if the phone isn&#39;t back."),
        "{}",
        row(&page, "locate")
    );
    // Asked and expiring in local time, the exact time in `title` (design 31 M4).
    assert!(!page.contains("UTC"), "{}", row(&page, "locate"));
    assert!(page.contains(&format!("/commands/{fresh}/cancel")));
    assert!(row(&page, "lock").contains("Goes out when the phone is back."));
    assert!(
        !row(&page, "lock").contains("Expires"),
        "a lock doesn't expire"
    );
    assert!(page.contains(&format!("/commands/{lock}/cancel")));
    assert!(page.contains("aria-label=\"Cancel Lock asked for at"));

    // A new phone enrolled on the page: what waited is cancelled, never sent.
    let code = app.new_code(device, crate::enrollment::Kind::Qr).await;
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
    assert!(app.policy(&token).await["pending_command"].is_null());
    let page = locate_page(&app, &cookie, device).await;
    assert!(
        row(&page, "lock").contains("Never sent: the phone was set up again."),
        "{}",
        row(&page, "lock")
    );
    assert!(
        row(&page, "ring").contains("Expired"),
        "an expired one stays expired"
    );
}

/// The summary's Activity (design 48): a pause never fetched within the hour expired, a cancelled
/// one says so - the command's own row, the one line for it.
#[tokio::test]
async fn the_device_page_says_a_pause_expired_or_was_cancelled() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (device, _) = app.enrolled_device("phone").await;
    insert(&app, device, "start_pause", "datetime('now', '-2 hours')").await;
    let page = app
        .get_page(&format!("/devices/{device}"), &cookie)
        .await
        .text();
    let expired = row(&page, "start_pause");
    assert!(expired.contains(">Expired<"), "{expired}");
    assert!(
        expired.contains("Never sent: the phone wasn&#39;t back within an hour."),
        "{expired}"
    );
    let pause = insert(&app, device, "start_pause", "datetime('now')").await;
    cancel(&app, &cookie, device, pause).await;
    let page = app
        .get_page(&format!("/devices/{device}"), &cookie)
        .await
        .text();
    let cancelled = row(&page, "start_pause");
    assert!(cancelled.contains(">Cancelled<"), "{cancelled}");
    assert_eq!(page.matches("data-command=\"start_pause\"").count(), 2);
}

async fn press(app: &TestApp, cookie: &str, device: i64, path: &str) {
    let fields: &[(&str, &str)] = if path == "wipe" {
        &[("confirm_name", "phone")]
    } else {
        &[]
    };
    let res = app
        .request_form(
            Method::POST,
            &format!("/devices/{device}/command/{path}"),
            Some(cookie),
            fields,
        )
        .await;
    assert!(res.status.is_redirection(), "{path}: {}", res.status);
}

/// QA of cmd-expiry, Medium 1 (its probe): a wipe waiting for a lost phone survives a later "Update
/// location now", Ring, Stop ringing and Lock - and still reaches the phone after the hour that
/// expires the locate and the ring.
#[tokio::test]
async fn a_waiting_wipe_is_never_replaced() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (device, token) = app.enrolled_device("phone").await;
    for path in ["wipe", "locate", "ring", "stop-ring", "lock"] {
        press(&app, &cookie, device, path).await;
    }
    let rows: Vec<(String, Option<String>)> = sqlx::query_as(
        "SELECT command, cancel_reason FROM device_commands WHERE device_id = ? ORDER BY id",
    )
    .bind(device)
    .fetch_all(&app.db)
    .await
    .unwrap();
    let rows: Vec<(&str, Option<&str>)> = rows
        .iter()
        .map(|(c, r)| (c.as_str(), r.as_deref()))
        .collect();
    assert_eq!(
        rows,
        [
            ("wipe", None),
            ("locate", None),
            ("ring", Some("replaced")),
            ("stop_ring", None),
            ("lock", None),
        ],
        "only the ring was replaced, by its own kind; nothing deleted"
    );
    // 61 minutes later: the locate and the stop expired, the wipe and the lock still go.
    sqlx::query("UPDATE device_commands SET requested_at = datetime('now', '-61 minutes')")
        .execute(&app.db)
        .await
        .unwrap();
    assert_eq!(
        app.policy(&token).await["pending_command"]["command"],
        "wipe"
    );
    assert_eq!(
        app.policy(&token).await["pending_command"]["command"],
        "lock"
    );
    assert!(app.policy(&token).await["pending_command"].is_null());
    let replaced: String = sqlx::query_scalar(
        "SELECT detail FROM security_events WHERE event_type = 'device_command_replaced'",
    )
    .fetch_one(&app.db)
    .await
    .unwrap();
    assert!(
        replaced.starts_with("1 waiting ring/stop_ring replaced by a new stop_ring"),
        "{replaced}"
    );
    let page = locate_page(&app, &cookie, device).await;
    assert!(
        row(&page, "ring").contains(">Replaced<"),
        "{}",
        row(&page, "ring")
    );
}

/// A lock waits too: a later wipe or lock doesn't replace it.
#[tokio::test]
async fn a_waiting_lock_is_never_replaced() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (device, token) = app.enrolled_device("phone").await;
    for path in ["lock", "lock", "wipe"] {
        press(&app, &cookie, device, path).await;
    }
    for expected in ["lock", "lock", "wipe"] {
        assert_eq!(
            app.policy(&token).await["pending_command"]["command"],
            expected
        );
    }
}

/// QA of cmd-expiry, Low 4: an enrollment cancels every waiting command, an expired one too (the
/// clock may be behind after a power cut) - the page still says it expired - and a server start
/// ends every waiting ring, pause or location update, whatever the clock says, while a wipe waits.
#[tokio::test]
async fn an_enrollment_or_a_restart_ends_what_could_expire() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (device, _) = app.enrolled_device("phone").await;
    insert(&app, device, "ring", "datetime('now', '-2 hours')").await;
    let code = app.new_code(device, crate::enrollment::Kind::Qr).await;
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
    let reason: Option<String> =
        sqlx::query_scalar("SELECT cancel_reason FROM device_commands WHERE command = 'ring'")
            .fetch_one(&app.db)
            .await
            .unwrap();
    assert_eq!(
        reason.as_deref(),
        Some("enrolled"),
        "cancelled, so a clock running behind can't revive it"
    );
    let page = locate_page(&app, &cookie, device).await;
    assert!(
        row(&page, "ring").contains("Expired"),
        "{}",
        row(&page, "ring")
    );

    let locate = insert(&app, device, "locate", "datetime('now')").await;
    let wipe = insert(&app, device, "wipe", "datetime('now')").await;
    crate::handlers::device_api::end_expiring_at_start(&app.db).await;
    assert_eq!(app.policy(&token).await["pending_command"]["id"], wipe);
    // Not in Locate's Requests (no bucket of design 48 §1.4 holds it): the summary's Activity.
    let page = app
        .get_page(&format!("/devices/{device}"), &cookie)
        .await
        .text();
    assert!(
        row(&page, "locate").contains("Never sent: the server restarted first."),
        "{}",
        row(&page, "locate")
    );
    assert!(!page.contains(&format!("/commands/{locate}/cancel")));
}
