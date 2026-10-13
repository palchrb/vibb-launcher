//! Security review L12: the phone confirms each remote command before running it
//! (`POST /api/devices/command-receipt`), and a command it didn't confirm is offered again.

use axum::http::{Method, StatusCode};
use serde_json::{Value, json};

use super::TestApp;

async fn policy(app: &TestApp, token: &str) -> Value {
    let res = app
        .request(Method::GET, "/api/devices/policy", Some(token), None)
        .await;
    assert_eq!(res.status, StatusCode::OK, "{}", res.text());
    res.json()
}

/// A status report that says the launcher confirms commands.
async fn report_receipts(app: &TestApp, token: &str) {
    let res = app
        .request(
            Method::POST,
            "/api/devices/status",
            Some(token),
            Some(json!({ "lock_reason": "NONE", "kiosk_engaged": true,
                         "capabilities": ["call_policy_v1", "command_receipt_v1"] })),
        )
        .await;
    assert_eq!(res.status, StatusCode::NO_CONTENT, "{}", res.text());
}

async fn queue(app: &TestApp, device: i64, command: &str) -> i64 {
    sqlx::query_scalar(
        "INSERT INTO device_commands (device_id, command) VALUES (?, ?) RETURNING id",
    )
    .bind(device)
    .bind(command)
    .fetch_one(&app.db)
    .await
    .unwrap()
}

/// Moves the command's last delivery `minutes` into the past.
async fn age_delivery(app: &TestApp, id: i64, minutes: i64) {
    sqlx::query("UPDATE device_commands SET delivered_at = datetime('now', ?) WHERE id = ?")
        .bind(format!("-{minutes} minutes"))
        .bind(id)
        .execute(&app.db)
        .await
        .unwrap();
}

async fn row(app: &TestApp, id: i64) -> (Option<String>, i64, Option<bool>, Option<String>) {
    sqlx::query_as(
        "SELECT received_at, deliveries, succeeded, result FROM device_commands WHERE id = ?",
    )
    .bind(id)
    .fetch_one(&app.db)
    .await
    .unwrap()
}

async fn receipt(app: &TestApp, token: &str, id: i64) -> StatusCode {
    app.request(
        Method::POST,
        "/api/devices/command-receipt",
        Some(token),
        Some(json!({ "command_id": id })),
    )
    .await
    .status
}

#[tokio::test]
async fn a_receipt_is_stored_for_the_phones_own_command_only() {
    let app = TestApp::new().await;
    let (a, token_a) = app.enrolled_device("a").await;
    let (_, token_b) = app.enrolled_device("b").await;
    let id = queue(&app, a, "wipe").await;

    assert_eq!(receipt(&app, &token_b, id).await, StatusCode::NO_CONTENT);
    assert_eq!(
        row(&app, id).await.0,
        None,
        "another phone can't confirm it"
    );

    assert_eq!(receipt(&app, &token_a, id).await, StatusCode::NO_CONTENT);
    let first = row(&app, id).await.0.expect("confirmed");
    sqlx::query("UPDATE device_commands SET received_at = '2026-10-11 10:00:00' WHERE id = ?")
        .bind(id)
        .execute(&app.db)
        .await
        .unwrap();
    assert_eq!(receipt(&app, &token_a, id).await, StatusCode::NO_CONTENT);
    assert_eq!(
        row(&app, id).await.0.as_deref(),
        Some("2026-10-11 10:00:00"),
        "the first receipt counts (was {first})"
    );

    let unauthenticated = app
        .request(
            Method::POST,
            "/api/devices/command-receipt",
            None,
            Some(json!({ "command_id": id })),
        )
        .await;
    assert_eq!(unauthenticated.status, StatusCode::UNAUTHORIZED);
}

#[tokio::test]
async fn an_unconfirmed_command_is_offered_again_to_a_phone_that_confirms() {
    let app = TestApp::new().await;
    let (device, token) = app.enrolled_device("phone").await;
    report_receipts(&app, &token).await;
    let id = queue(&app, device, "lock").await;

    assert_eq!(policy(&app, &token).await["pending_command"]["id"], id);
    assert!(
        policy(&app, &token).await["pending_command"].is_null(),
        "not twice within two minutes"
    );
    // The answer was lost: after two minutes it comes again, the same id.
    age_delivery(&app, id, 3).await;
    assert_eq!(policy(&app, &token).await["pending_command"]["id"], id);
    assert_eq!(row(&app, id).await.1, 2);
    // A third and last time.
    age_delivery(&app, id, 3).await;
    assert_eq!(policy(&app, &token).await["pending_command"]["id"], id);
    age_delivery(&app, id, 3).await;
    assert!(
        policy(&app, &token).await["pending_command"].is_null(),
        "at most three deliveries"
    );
    assert_eq!(row(&app, id).await.1, 3);

    // A confirmed one is never offered again.
    let ring = queue(&app, device, "ring").await;
    assert_eq!(policy(&app, &token).await["pending_command"]["id"], ring);
    assert_eq!(receipt(&app, &token, ring).await, StatusCode::NO_CONTENT);
    age_delivery(&app, ring, 30).await;
    assert!(policy(&app, &token).await["pending_command"].is_null());
}

#[tokio::test]
async fn an_older_launcher_gets_a_command_once() {
    let app = TestApp::new().await;
    let (device, token) = app.enrolled_device("phone").await;
    let id = queue(&app, device, "lock").await;
    assert_eq!(policy(&app, &token).await["pending_command"]["id"], id);
    age_delivery(&app, id, 30).await;
    assert!(policy(&app, &token).await["pending_command"].is_null());
    assert_eq!(row(&app, id).await.1, 1);
}

#[tokio::test]
async fn a_result_confirms_and_says_whether_it_worked() {
    let app = TestApp::new().await;
    let (device, token) = app.enrolled_device("phone").await;
    let id = queue(&app, device, "wipe").await;
    policy(&app, &token).await;
    let res = app
        .request(
            Method::POST,
            "/api/devices/command-result",
            Some(&token),
            Some(json!({ "command_id": id, "success": false,
                         "message": "wipe failed, the phone keeps its data: SecurityException: x" })),
        )
        .await;
    assert_eq!(res.status, StatusCode::NO_CONTENT);
    let (received, _, succeeded, result) = row(&app, id).await;
    assert!(received.is_some(), "a result implies the receipt");
    assert_eq!(succeeded, Some(false));
    assert!(result.unwrap().starts_with("wipe failed"));
}

#[tokio::test]
async fn a_new_command_stops_an_unconfirmed_one_coming_again() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (device, token) = app.enrolled_device("phone").await;
    report_receipts(&app, &token).await;
    let ring = queue(&app, device, "ring").await;
    assert_eq!(policy(&app, &token).await["pending_command"]["id"], ring);

    let res = app
        .request_form(
            Method::POST,
            &format!("/devices/{device}/command/stop-ring"),
            Some(&cookie),
            &[],
        )
        .await;
    assert!(res.status.is_redirection(), "{}", res.status);
    age_delivery(&app, ring, 3).await;
    // A command of the same kind replaces it (QA of cmd-expiry, Medium 1: only the same kind).
    assert_eq!(
        policy(&app, &token).await["pending_command"]["command"],
        "stop_ring"
    );
    age_delivery(&app, ring, 30).await;
    assert!(
        policy(&app, &token).await["pending_command"].is_null(),
        "the ring stays history"
    );
    assert!(row(&app, ring).await.1 >= crate::models::MAX_COMMAND_DELIVERIES);
}

#[tokio::test]
async fn the_locate_page_reads_receipts_and_failures() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (device, token) = app.enrolled_device("phone").await;
    report_receipts(&app, &token).await;
    for (command, delivered, deliveries, received, result, succeeded) in [
        (
            "wipe",
            "datetime('now', '-1 minutes')",
            1,
            "datetime('now')",
            "NULL",
            "NULL",
        ),
        (
            "lock",
            "datetime('now', '-30 minutes')",
            1,
            "datetime('now', '-30 minutes')",
            "NULL",
            "NULL",
        ),
        (
            "ring",
            "datetime('now', '-5 minutes')",
            1,
            "NULL",
            "NULL",
            "NULL",
        ),
        (
            "stop_ring",
            "datetime('now', '-5 minutes')",
            3,
            "NULL",
            "NULL",
            "NULL",
        ),
        (
            "locate",
            "datetime('now', '-1 minutes')",
            1,
            "NULL",
            "NULL",
            "NULL",
        ),
        (
            "end_windows",
            "datetime('now', '-1 minutes')",
            1,
            "datetime('now')",
            "'wipe failed, the phone keeps its data: SecurityException'",
            "0",
        ),
    ] {
        sqlx::query(&format!(
            "INSERT INTO device_commands (device_id, command, delivered_at, first_delivered_at, \
             deliveries, received_at, acknowledged_at, result, succeeded) VALUES (?, ?, {delivered}, \
             {delivered}, ?, {received}, \
             CASE WHEN {result} IS NULL THEN NULL ELSE '2026-10-11 10:00:00' END, {result}, {succeeded})"
        ))
        .bind(device)
        .bind(command)
        .bind(deliveries)
        .execute(&app.db)
        .await
        .unwrap();
    }
    let page = app
        .get_page(&format!("/devices/locate?device={device}"), &cookie)
        .await
        .text();
    let line = |command: &str| -> String {
        page.split(&format!("<td>{command}</td>"))
            .nth(1)
            .and_then(|rest| rest.split("</tr>").next())
            .unwrap_or_else(|| panic!("no row for {command}"))
            .to_string()
    };
    assert!(
        line("wipe").contains("the phone is wiping now"),
        "{}",
        line("wipe")
    );
    assert!(
        line("lock").contains("never said it finished"),
        "{}",
        line("lock")
    );
    assert!(line("ring").contains("offered again"), "{}", line("ring"));
    assert!(
        line("stop_ring").contains("send it again"),
        "{}",
        line("stop_ring")
    );
    assert!(
        line("locate").contains("not confirmed yet"),
        "{}",
        line("locate")
    );
    assert!(
        line("end_windows").contains("Failed 2026-10-11 10:00:00 UTC: wipe failed"),
        "{}",
        line("end_windows")
    );
}

/// QA of the launcher wave, High 1: everything delivered before migration 0065 was delivered at
/// most once (an older launcher never confirmed a wipe), so the migration marks it as used up.
#[tokio::test]
async fn the_migration_marks_old_deliveries_as_used_up() {
    let (db, _dir) = super::cleanup::migrated_before(65).await;
    let device: i64 = sqlx::query_scalar("INSERT INTO devices (name) VALUES ('kid') RETURNING id")
        .fetch_one(&db)
        .await
        .unwrap();
    for (command, delivered, acknowledged) in [
        ("wipe", "datetime('now', '-90 days')", "NULL"),
        ("start_pause", "datetime('now', '-3 days')", "NULL"),
        (
            "lock",
            "datetime('now', '-3 days')",
            "datetime('now', '-3 days')",
        ),
        ("ring", "NULL", "NULL"),
    ] {
        sqlx::query(&format!(
            "INSERT INTO device_commands (device_id, command, requested_at, delivered_at, acknowledged_at) \
             VALUES (?, ?, datetime('now', '-90 days'), {delivered}, {acknowledged})"
        ))
        .bind(device)
        .bind(command)
        .execute(&db)
        .await
        .unwrap();
    }
    super::cleanup::finish(&db).await;
    let rows: Vec<(String, i64, bool, bool)> = sqlx::query_as(
        "SELECT command, deliveries, first_delivered_at IS delivered_at, received_at IS NOT NULL \
         FROM device_commands ORDER BY id",
    )
    .fetch_all(&db)
    .await
    .unwrap();
    assert_eq!(
        rows,
        [
            ("wipe".to_string(), 3, true, false),
            ("start_pause".to_string(), 3, true, false),
            ("lock".to_string(), 3, true, true),
            ("ring".to_string(), 0, true, false),
        ]
    );
}

/// QA High 1: a wipe or pause from before the update (used up by the migration) is never sent
/// again, nor is a command whose first delivery is older than the window - however old its gap.
#[tokio::test]
async fn a_stale_command_is_never_sent_again() {
    let app = TestApp::new().await;
    let (device, token) = app.enrolled_device("phone").await;
    for (command, first, deliveries) in [
        ("wipe", "datetime('now', '-90 days')", 3),
        ("start_pause", "datetime('now', '-3 days')", 3),
        ("lock", "datetime('now', '-20 minutes')", 1),
    ] {
        sqlx::query(&format!(
            "INSERT INTO device_commands (device_id, command, requested_at, delivered_at, \
             first_delivered_at, deliveries) VALUES (?, ?, {first}, {first}, {first}, ?)"
        ))
        .bind(device)
        .bind(command)
        .bind(deliveries)
        .execute(&app.db)
        .await
        .unwrap();
    }
    report_receipts(&app, &token).await;
    assert!(
        policy(&app, &token).await["pending_command"].is_null(),
        "nothing old replays"
    );
}

/// QA High 1: a fresh unconfirmed command is offered again at most three times, all within the
/// window counted from its first delivery.
#[tokio::test]
async fn re_delivery_ends_with_the_window() {
    let app = TestApp::new().await;
    let (device, token) = app.enrolled_device("phone").await;
    report_receipts(&app, &token).await;
    let id = queue(&app, device, "lock").await;
    assert_eq!(policy(&app, &token).await["pending_command"]["id"], id);
    // The first delivery is 14 minutes ago: still in the window.
    sqlx::query(
        "UPDATE device_commands SET first_delivered_at = datetime('now', '-14 minutes'), \
         delivered_at = datetime('now', '-3 minutes') WHERE id = ?",
    )
    .bind(id)
    .execute(&app.db)
    .await
    .unwrap();
    assert_eq!(policy(&app, &token).await["pending_command"]["id"], id);
    // Past the window: never again, though only two of three deliveries were used.
    sqlx::query(
        "UPDATE device_commands SET first_delivered_at = datetime('now', '-16 minutes'), \
         delivered_at = datetime('now', '-3 minutes') WHERE id = ?",
    )
    .bind(id)
    .execute(&app.db)
    .await
    .unwrap();
    assert!(policy(&app, &token).await["pending_command"].is_null());
    assert_eq!(row(&app, id).await.1, 2);
}

/// QA Low 1: commands run in the order they were asked for. A lost "pause" isn't offered again
/// once a later "lock again" went out.
#[tokio::test]
async fn a_lost_pause_never_runs_after_a_later_lock_again() {
    let app = TestApp::new().await;
    let (device, token) = app.enrolled_device("phone").await;
    report_receipts(&app, &token).await;
    let pause = queue(&app, device, "start_pause").await;
    assert_eq!(policy(&app, &token).await["pending_command"]["id"], pause);
    // The answer was lost; a minute later the parent ends the windows.
    sqlx::query(
        "INSERT INTO device_commands (device_id, command, requested_at) \
         VALUES (?, 'end_windows', datetime('now', '+1 seconds'))",
    )
    .bind(device)
    .execute(&app.db)
    .await
    .unwrap();
    assert_eq!(
        policy(&app, &token).await["pending_command"]["command"],
        "end_windows"
    );
    age_delivery(&app, pause, 3).await;
    assert!(
        policy(&app, &token).await["pending_command"].is_null(),
        "the pause stays history"
    );
}

/// QA High 1: after a delivery to a phone that confirms, the server nudges it once the gap has
/// passed - only while the command is still unconfirmed.
#[tokio::test]
async fn an_unconfirmed_command_nudges_the_phone() {
    let app = TestApp::new().await;
    let (device, token) = app.enrolled_device("phone").await;
    report_receipts(&app, &token).await;
    let id = queue(&app, device, "ring").await;
    policy(&app, &token).await;
    let mut stream = app.state.command_notify.subscribe();
    assert!(
        crate::handlers::device_api::redelivery_nudge(
            &app.db,
            &app.state.command_notify,
            device,
            id
        )
        .await
    );
    assert_eq!(stream.recv().await.unwrap(), device);
    assert_eq!(receipt(&app, &token, id).await, StatusCode::NO_CONTENT);
    assert!(
        !crate::handlers::device_api::redelivery_nudge(
            &app.db,
            &app.state.command_notify,
            device,
            id
        )
        .await
    );
}

/// QA Low 2: a new enrollment into the same device row ends its pending commands - a wipe for a
/// lost phone never reaches the replacement - and the security log says so.
#[tokio::test]
async fn a_new_enrollment_never_gets_the_earlier_phones_commands() {
    let app = TestApp::new().await;
    let (device, token) = app.enrolled_device("phone").await;
    report_receipts(&app, &token).await;
    let lock = queue(&app, device, "lock").await;
    assert_eq!(policy(&app, &token).await["pending_command"]["id"], lock);
    queue(&app, device, "wipe").await;
    age_delivery(&app, lock, 3).await;

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
    let new_token = res.json()["device_token"].as_str().unwrap().to_string();
    assert!(
        policy(&app, &new_token).await["pending_command"].is_null(),
        "neither the wipe nor the lock"
    );
    let commands: Vec<(String, Option<String>)> = sqlx::query_as(
        "SELECT command, cancel_reason FROM device_commands WHERE device_id = ? ORDER BY id",
    )
    .bind(device)
    .fetch_all(&app.db)
    .await
    .unwrap();
    assert_eq!(
        commands,
        [
            ("lock".to_string(), None),
            ("wipe".to_string(), Some("enrolled".to_string()))
        ],
        "the undelivered wipe is cancelled, the lock stays history"
    );
    let detail: String = sqlx::query_scalar(
        "SELECT detail FROM security_events WHERE event_type = 'device_enrolled' ORDER BY id DESC LIMIT 1",
    )
    .fetch_one(&app.db)
    .await
    .unwrap();
    assert!(
        detail.contains("2 command(s) for the earlier phone"),
        "{detail}"
    );
}

/// QA Medium 1: an id is unique only together with `requested_at` - a restored backup winds the
/// id counter back. The policy names both, and an answer that names an older command with the
/// same id doesn't count for the new one.
#[tokio::test]
async fn an_answer_counts_only_for_the_command_it_names() {
    let app = TestApp::new().await;
    let (device, token) = app.enrolled_device("phone").await;
    report_receipts(&app, &token).await;
    let old = queue(&app, device, "ring").await;
    let first = policy(&app, &token).await["pending_command"].clone();
    assert_eq!(first["id"], old);
    let old_requested = first["requested_at"]
        .as_str()
        .expect("requested_at")
        .to_string();
    // The restore: the row and the counter go back; the next command gets the same id.
    sqlx::query("DELETE FROM device_commands WHERE id = ?")
        .bind(old)
        .execute(&app.db)
        .await
        .unwrap();
    sqlx::query("UPDATE sqlite_sequence SET seq = ? WHERE name = 'device_commands'")
        .bind(old - 1)
        .execute(&app.db)
        .await
        .unwrap();
    let new: i64 = sqlx::query_scalar(
        "INSERT INTO device_commands (device_id, command, requested_at) \
         VALUES (?, 'lock', datetime('now', '+1 minutes')) RETURNING id",
    )
    .bind(device)
    .fetch_one(&app.db)
    .await
    .unwrap();
    assert_eq!(new, old, "the restored counter hands out the id again");
    // The phone's outbox still holds the old ring's receipt and result.
    for (path, body) in [
        (
            "/api/devices/command-receipt",
            json!({ "command_id": old, "requested_at": old_requested }),
        ),
        (
            "/api/devices/command-result",
            json!({ "command_id": old, "success": true, "message": "ringing", "requested_at": old_requested }),
        ),
    ] {
        let res = app
            .request(Method::POST, path, Some(&token), Some(body))
            .await;
        assert_eq!(res.status, StatusCode::NO_CONTENT);
    }
    assert_eq!(
        row(&app, new).await.0,
        None,
        "the lock isn't confirmed by the ring's answers"
    );
    let next = policy(&app, &token).await["pending_command"].clone();
    assert_eq!(next["command"], "lock");
    assert_ne!(next["requested_at"], old_requested.as_str());
    // Its own receipt counts (one without requested_at - an older launcher - is the first test).
    let res = app
        .request(
            Method::POST,
            "/api/devices/command-receipt",
            Some(&token),
            Some(json!({ "command_id": new, "requested_at": next["requested_at"] })),
        )
        .await;
    assert_eq!(res.status, StatusCode::NO_CONTENT);
    assert!(row(&app, new).await.0.is_some());
}

/// QA Low 4: the phone reports whether it can wipe itself yet (Android 14 grants the new wipe
/// policy at the next restart); the Locate page says so while it can't.
#[tokio::test]
async fn the_locate_page_says_when_a_wipe_would_be_refused() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (device, token) = app.enrolled_device("phone").await;
    let warning = "Remote wipe works after the phone restarts once";
    for (ready, shown) in [(Some(false), true), (Some(true), false), (None, false)] {
        let mut report = json!({ "lock_reason": "NONE", "kiosk_engaged": true });
        if let Some(ready) = ready {
            report["remote_wipe_ready"] = json!(ready);
        }
        let res = app
            .request(
                Method::POST,
                "/api/devices/status",
                Some(&token),
                Some(report),
            )
            .await;
        assert_eq!(res.status, StatusCode::NO_CONTENT);
        let stored: Option<bool> = sqlx::query_scalar(
            "SELECT remote_wipe_ready FROM device_status WHERE device_id = ? ORDER BY id DESC LIMIT 1",
        )
        .bind(device)
        .fetch_one(&app.db)
        .await
        .unwrap();
        assert_eq!(stored, ready);
        let page = app
            .get_page(&format!("/devices/locate?device={device}"), &cookie)
            .await
            .text();
        assert_eq!(page.contains(warning), shown, "{ready:?}");
    }
}

/// A device-admin policy a launcher update added may only count after the phone restarts once; the
/// phone lists those not granted yet, and Attention and Advanced ask for a restart (the lock-screen
/// camera switch was refused that way on an Android 16 emulator).
#[tokio::test]
async fn the_device_page_asks_for_a_restart_while_admin_policies_are_pending() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (device, token) = app.enrolled_device("phone").await;
    let notice = "Restart the phone once";
    for (pending, shown) in [
        (
            json!(["disable-keyguard-features", "limit-password", "made-up"]),
            true,
        ),
        (json!(["wipe-data"]), false),
        (json!([]), false),
    ] {
        let res = app
            .request(
                Method::POST,
                "/api/devices/status",
                Some(&token),
                Some(json!({ "lock_reason": "NONE", "kiosk_engaged": true, "admin_policies_pending": pending })),
            )
            .await;
        assert_eq!(res.status, StatusCode::NO_CONTENT);
        // An Attention row on the device page, the reasons on Advanced › App screens (design 31
        // step 3).
        let page = app
            .get_page(&format!("/devices/{device}"), &cookie)
            .await
            .text();
        assert_eq!(page.contains(notice), shown, "{pending}");
        assert_eq!(
            page.contains(&format!("/devices/{device}/advanced#app-screens")),
            shown
        );
        let page = app
            .get_page(&format!("/devices/{device}/advanced"), &cookie)
            .await
            .text();
        assert_eq!(page.contains(notice), shown, "{pending}");
        if shown {
            assert!(page.contains("turn off the camera on the lock screen, and to notice at once"));
        }
    }
    // Bug 25b: the camera policy the lock needs (the power button's camera gesture).
    let res = app
        .request(
            Method::POST,
            "/api/devices/status",
            Some(&token),
            Some(json!({ "lock_reason": "NONE", "kiosk_engaged": true, "admin_policies_pending": ["disable-camera"] })),
        )
        .await;
    assert_eq!(res.status, StatusCode::NO_CONTENT);
    let page = app
        .get_page(&format!("/devices/{device}/advanced"), &cookie)
        .await
        .text();
    assert!(page.contains(notice));
    assert!(page.contains("camera gesture from opening the camera over the lock"));
    let stored: Option<String> = sqlx::query_scalar(
        "SELECT admin_policies_pending_json FROM device_status WHERE device_id = ? ORDER BY id LIMIT 1",
    )
    .bind(device)
    .fetch_one(&app.db)
    .await
    .unwrap();
    assert_eq!(
        stored.as_deref(),
        Some(r#"["disable-keyguard-features","limit-password"]"#),
        "known tags only"
    );
}
