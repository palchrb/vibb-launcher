//! The Attention rows, fact by fact (design 31 §3.2, qa-31-s2-code L1): each report field or
//! setting that should raise a row raises exactly that row on a phone that is otherwise clean,
//! and the call report types every warning it writes.

use axum::http::{Method, StatusCode};
use serde_json::{Value, json};

use super::{TestApp, tags};

async fn report(app: &TestApp, token: &str, extra: Value) {
    let mut body = json!({
        "lock_reason": "NONE",
        "kiosk_engaged": true,
        "capabilities": ["time_rules_v1"],
    });
    for (key, value) in extra.as_object().unwrap() {
        body[key] = value.clone();
    }
    let res = app
        .request(Method::POST, "/api/devices/status", Some(token), Some(body))
        .await;
    assert_eq!(res.status, StatusCode::NO_CONTENT, "{}", res.text());
}

/// An enrolled phone with a Parent PIN and a clean first report (a time-rules launcher): no
/// Attention rows.
async fn clean_phone(app: &TestApp, name: &str) -> (i64, String) {
    let (id, token) = app.enrolled_device(name).await;
    sqlx::query(
        "UPDATE device_policy SET override_pin_hash = 'aGFzaA', override_pin_salt = 'c2FsdA' \
         WHERE device_id = ?",
    )
    .bind(id)
    .execute(&app.db)
    .await
    .unwrap();
    report(app, &token, json!({})).await;
    (id, token)
}

/// The `data-attention` keys on the phone's page, in order.
async fn rows(app: &TestApp, cookie: &str, id: i64) -> Vec<String> {
    let page = app.get_page(&format!("/devices/{id}"), cookie).await.text();
    tags(&page, "a")
        .into_iter()
        .filter_map(|tag| tag.attr("data-attention").map(str::to_string))
        .collect()
}

#[tokio::test]
async fn each_fact_raises_its_own_row() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (clean, _) = clean_phone(&app, "Clean").await;
    assert_eq!(rows(&app, &cookie, clean).await, Vec::<String>::new());

    let now_ms = chrono::Utc::now().timestamp_millis();
    let cases: Vec<(&str, Value)> = vec![
        ("restrictions-paused", json!({"restrictions_paused": true})),
        (
            "foreign-access",
            json!({"foreign_access": {"admins": ["com.example.spy"], "accessibility": []}}),
        ),
        ("do-not-disturb", json!({"interruption_filter": "alarms"})),
        ("google-backup", json!({"backup_service_enabled": true})),
        ("policy-problem", json!({"policy_state": "cache_corrupt"})),
        (
            "unseen-restarts",
            json!({"unseen_boots": {"count": 1, "last_at_ms": now_ms - 60_000}}),
        ),
        (
            "blocked-apps",
            json!({"relock_blocks": [{"package_name": "com.example.game",
                                      "reason": "outside_play", "found": "install",
                                      "at_ms": now_ms - 60_000}]}),
        ),
        (
            "restart-once",
            json!({"admin_policies_pending": ["disable-keyguard-features"]}),
        ),
        (
            "notification-access",
            json!({"notification_listener_enabled": false}),
        ),
        (
            "screen-time-record",
            json!({"time_state": {"day": "2026-10-13", "used_minutes": 0, "budget_minutes": 60,
                                  "extra_minutes": 0, "active_rule_id": null,
                                  "active_rule_name": null, "calls_blocked": false,
                                  "lock_reason": "NONE", "lifts_active": [],
                                  "ledger_unreadable": true}}),
        ),
        ("launcher-without-rules", json!({"capabilities": []})),
        // Design 31 step 3 fix round (qa-31-s3-code M4): install mode opens the Play Store.
        (
            "play-install-mode",
            json!({"install_mode": {"until_ms": now_ms + 600_000}}),
        ),
    ];
    for (key, extra) in cases {
        let (id, token) = clean_phone(&app, key).await;
        report(&app, &token, extra).await;
        assert_eq!(rows(&app, &cookie, id).await, [key], "{key}");
    }

    // Each needs a switch on this server and the report that goes with it (M4, L4). The switch
    // on its own, with a report that says nothing, raises nothing.
    let escapes = ["time_rules_v1", "kiosk_escapes_v1", "boot_cover_v1"];
    let cases: Vec<(&str, &str, Value)> = vec![
        (
            "play-store-openable",
            "block_activity_start = 0",
            json!({"play_store_suspendable": false}),
        ),
        (
            "not-home-app",
            "update_fence = 1",
            json!({"capabilities": escapes,
                   "update_fence": {"enabled": true, "state": "none", "home_role_held": false}}),
        ),
        (
            "boot-cover-not-shown",
            "boot_cover = 1",
            json!({"capabilities": escapes,
                   "boot_cover": {"wanted": true, "last_armed_at_ms": now_ms - 3_600_000}}),
        ),
    ];
    for (key, setting, extra) in cases {
        let (id, token) = clean_phone(&app, key).await;
        sqlx::query(&format!(
            "UPDATE device_policy SET {setting} WHERE device_id = ?"
        ))
        .bind(id)
        .execute(&app.db)
        .await
        .unwrap();
        report(&app, &token, json!({"capabilities": escapes})).await;
        assert_eq!(rows(&app, &cookie, id).await, Vec::<String>::new(), "{key}");
        report(&app, &token, extra).await;
        assert_eq!(rows(&app, &cookie, id).await, [key], "{key}");
    }
    // Play not pausable with "Block other apps' screens" on: Play stays blocked, no row.
    let (blocked, token) = clean_phone(&app, "blocked").await;
    report(&app, &token, json!({"play_store_suspendable": false})).await;
    assert_eq!(rows(&app, &cookie, blocked).await, Vec::<String>::new());

    // The settings this server keeps.
    let (easy, _) = clean_phone(&app, "easy").await;
    sqlx::query("UPDATE device_policy SET override_pin_easy = 1 WHERE device_id = ?")
        .bind(easy)
        .execute(&app.db)
        .await
        .unwrap();
    assert_eq!(rows(&app, &cookie, easy).await, ["easy-parent-pin"]);

    let (no_pin, _) = clean_phone(&app, "no pin").await;
    sqlx::query("UPDATE device_policy SET override_pin_hash = NULL, allowlist_json = '[]' WHERE device_id = ?")
        .bind(no_pin)
        .execute(&app.db)
        .await
        .unwrap();
    assert_eq!(rows(&app, &cookie, no_pin).await, ["no-parent-pin"]);

    // A no-calls rule while calls aren't managed: incoming calls still ring.
    let (rule_phone, _) = clean_phone(&app, "rule").await;
    sqlx::query("UPDATE device_policy SET custom_schedule_enabled = 1 WHERE device_id = ?")
        .bind(rule_phone)
        .execute(&app.db)
        .await
        .unwrap();
    sqlx::query(
        "INSERT INTO time_rules (device_id, name, kind, calls_allowed, days_json) \
         VALUES (?, 'Skole', 'school', 0, '[null,null,null,null,null,null,null]')",
    )
    .bind(rule_phone)
    .execute(&app.db)
    .await
    .unwrap();
    assert_eq!(rows(&app, &cookie, rule_phone).await, ["incoming-calls"]);

    // A Child PIN set, but the lock off on the phone.
    let (lock_phone, token) = clean_phone(&app, "lock").await;
    sqlx::query(
        "UPDATE device_policy SET kid_pin_hash = 'aGFzaA', kid_pin_salt = 'c2FsdA', \
         kid_pin_length = 4, disallow_safe_boot = 1 WHERE device_id = ?",
    )
    .bind(lock_phone)
    .execute(&app.db)
    .await
    .unwrap();
    report(
        &app,
        &token,
        json!({"capabilities": ["time_rules_v1", "pin_lock_v1"],
               "lock_state": {"active": false, "inactive": "crash_guard"}}),
    )
    .await;
    assert_eq!(rows(&app, &cookie, lock_phone).await, ["child-lock-off"]);
    // The same phone with its lock on: nothing.
    report(
        &app,
        &token,
        json!({"capabilities": ["time_rules_v1", "pin_lock_v1"],
               "lock_state": {"active": true}}),
    )
    .await;
    assert_eq!(rows(&app, &cookie, lock_phone).await, Vec::<String>::new());

    // Access removed.
    let (revoked, _) = clean_phone(&app, "revoked").await;
    sqlx::query("UPDATE devices SET token_hash = NULL WHERE id = ?")
        .bind(revoked)
        .execute(&app.db)
        .await
        .unwrap();
    assert_eq!(rows(&app, &cookie, revoked).await, ["access-revoked"]);
}

#[tokio::test]
async fn an_offline_phones_report_rows_say_at_last_report() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = clean_phone(&app, "Emil").await;
    report(&app, &token, json!({"interruption_filter": "priority"})).await;
    sqlx::query("UPDATE device_policy SET override_pin_easy = 1 WHERE device_id = ?")
        .bind(id)
        .execute(&app.db)
        .await
        .unwrap();
    // Away (just reported): current.
    let page = app
        .get_page(&format!("/devices/{id}"), &cookie)
        .await
        .text();
    assert!(!page.contains("data-at-last-report"), "{page}");

    // Offline (nothing in memory, the last report three hours old): the report's row is marked,
    // the server's own isn't; both still count.
    sqlx::query("UPDATE devices SET last_seen_at = datetime('now', '-3 hours') WHERE id = ?")
        .bind(id)
        .execute(&app.db)
        .await
        .unwrap();
    app.state.limits.forget_device(id);
    let page = app
        .get_page(&format!("/devices/{id}"), &cookie)
        .await
        .text();
    let dnd = &page[page.find("data-attention=\"do-not-disturb\"").unwrap()..];
    let dnd = &dnd[..dnd.find("</a>").unwrap()];
    assert!(
        dnd.contains("data-at-last-report>At last report</span>"),
        "{dnd}"
    );
    let easy = &page[page.find("data-attention=\"easy-parent-pin\"").unwrap()..];
    let easy = &easy[..easy.find("</a>").unwrap()];
    assert!(!easy.contains("data-at-last-report"), "{easy}");
    let list = app.get_page("/devices", &cookie).await.text();
    assert!(list.contains("data-attention-count=\"2\""), "{list}");
}

#[tokio::test]
async fn a_phone_not_set_up_has_no_count() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    // Managed (calls) and no Parent PIN: an alarm once it's set up, nothing before.
    let (id, _) = app.create_device("Mia").await;
    sqlx::query("UPDATE device_policy SET calls_managed = 1 WHERE device_id = ?")
        .bind(id)
        .execute(&app.db)
        .await
        .unwrap();
    assert_eq!(rows(&app, &cookie, id).await, Vec::<String>::new());
    let list = app.get_page("/devices", &cookie).await.text();
    assert!(!list.contains("data-attention-count"), "{list}");
}

/// Every warning the call report writes has its typed twin (the Attention rows' titles).
#[tokio::test]
async fn every_call_warning_is_typed() {
    let app = TestApp::new().await;
    let (id, token) = app.enrolled_device("Anna").await;
    let now = chrono::Utc::now();
    let call_state = json!({
        "state": "fail_closed",
        "last_emergency_call_at": (now - chrono::Duration::hours(1)).to_rfc3339(),
        "callback_window_until": (now + chrono::Duration::minutes(30)).to_rfc3339(),
        "in_call_ui_failed_at": (now - chrono::Duration::hours(2)).to_rfc3339(),
        "dialer_role_held": false,
        "screening_role_held": false,
        "redirection_role_held": false,
        "call_log_readable": false,
        "boot_policy": "write_failed",
        "last_error": "boom",
        "default_sms_package": "com.example.sms",
    });
    report(
        &app,
        &token,
        json!({"capabilities": ["call_policy_v1"], "call_state": call_state}),
    )
    .await;
    sqlx::query(
        "UPDATE device_policy SET calls_managed = 1, sms_enabled = 0, \
         allowlist_json = '[\"com.example.sms\"]' WHERE device_id = ?",
    )
    .bind(id)
    .execute(&app.db)
    .await
    .unwrap();
    let policy = sqlx::query_as::<_, crate::models::DevicePolicy>(
        "SELECT * FROM device_policy WHERE device_id = ?",
    )
    .bind(id)
    .fetch_one(&app.db)
    .await
    .unwrap();
    let report = crate::handlers::calls::call_report(&app.state, &policy)
        .await
        .unwrap();
    assert!(report.warnings.len() >= 9, "{:?}", report.warnings);
    assert_eq!(
        report.warnings.len(),
        report.problems.len(),
        "{:?}",
        report.problems
    );
}
