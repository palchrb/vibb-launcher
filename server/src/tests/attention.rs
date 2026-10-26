//! The Attention rows, fact by fact (design 31 §3.2, qa-31-s2-code L1): each report field or
//! setting that should raise a row raises exactly that row on a phone that is otherwise clean,
//! and the call report types every warning it writes.

use serde_json::{Value, json};

use super::{TestApp, tags};

/// A status report from a launcher with time rules (`extra`'s `capabilities` replace them).
async fn report(app: &TestApp, token: &str, mut extra: Value) {
    let keys = extra.as_object_mut().unwrap();
    keys.entry("capabilities")
        .or_insert_with(|| json!(["time_rules_v1"]));
    app.post_status(token, extra).await;
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

/// Device › Music's warnings keep a signal on the summary (qa-31-s7-code M1): each raises its own
/// orange row on an otherwise clean phone, pointing at the place on the Music page that says it;
/// the Music row says "Needs attention" while any shows, and the list's badge counts the same rows.
#[tokio::test]
async fn each_music_warning_raises_its_row_and_marks_the_music_row() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let entry = super::music::add_own(&app, &cookie, "Eventyr").await;
    let other = super::music::add_own(&app, &cookie, "Sanger").await;
    let music =
        |state: Value| json!({"capabilities": ["time_rules_v1", "music_v1"], "music_state": state});
    let cases: Vec<(&str, Value)> = vec![
        // A launcher without music while an entry is on.
        ("music-cant-deliver", json!({})),
        (
            "music-unreachable",
            music(json!({"package": "me.vibb.music", "bridge": "unbound"})),
        ),
        (
            "music-storytel-refused",
            music(json!({"package": "me.vibb.music", "bridge": "bound", "storytel": "refused"})),
        ),
        (
            "music-over-limit",
            music(json!({"package": "me.vibb.music", "bridge": "bound",
                         "cache_bytes": 3_500_000_000_i64, "audio_bytes": 3_400_000_000_i64,
                         "extra_bytes": 0})),
        ),
        (
            "music-entries",
            music(json!({"package": "me.vibb.music", "bridge": "bound",
                         "entry_errors": [{"entry": entry, "error": "not_found"},
                                          {"entry": other, "error": "not_found"}]})),
        ),
    ];
    for (key, extra) in cases {
        let (id, token) = clean_phone(&app, key).await;
        // Before an entry is on (and with a clean report): nothing, and the row's plain count.
        let summary = app
            .get_page(&format!("/devices/{id}"), &cookie)
            .await
            .text();
        assert!(music_row(&summary).contains(">None<"), "{key}");
        super::music::tick(&app, &cookie, id, entry, true).await;
        report(&app, &token, extra).await;
        assert_eq!(rows(&app, &cookie, id).await, [key], "{key}");
        let summary = app
            .get_page(&format!("/devices/{id}"), &cookie)
            .await
            .text();
        let attention = &summary[summary.find(&format!("data-attention=\"{key}\"")).unwrap()..];
        let start = summary[..summary.find(&format!("data-attention=\"{key}\"")).unwrap()]
            .rfind("<a")
            .unwrap();
        assert!(
            summary[start..].starts_with("<a class=\"attention attention-action\""),
            "{key}"
        );
        let target = tags(&summary[start..], "a")[0]
            .attr("href")
            .unwrap()
            .to_string();
        // The target is the phone's Music page, at an element it has.
        assert!(
            target.starts_with(&format!("/devices/{id}/music")),
            "{key}: {target}"
        );
        let page = app
            .get_page(&format!("/devices/{id}/music"), &cookie)
            .await
            .text();
        if let Some((_, fragment)) = target.split_once('#') {
            assert!(
                page.contains(&format!("id=\"{fragment}\"")),
                "{key}: {target}"
            );
        }
        assert!(!attention.is_empty());
        let row = music_row(&summary);
        assert!(
            row.contains("row-value-action\">Needs attention<"),
            "{key}: {row}"
        );
        let list = app.get_page("/devices", &cookie).await.text();
        let list_row = &list[list.find(&format!("data-device=\"{id}\"")).unwrap()..];
        let list_row = &list_row[..list_row.find("</a>").unwrap()];
        assert!(
            list_row.contains("data-attention-count=\"1\""),
            "{key}: {list_row}"
        );
    }
    // Spotify (design 26c, qa-26c-merge2-code L1/L3): a phone that plays as an account raises a
    // row for its own refusal (`not_premium`) and for the account's side (a login to renew), each
    // to Device › Music's Spotify group, with the badge and "Needs attention".
    crate::spotify::tests::save_app(&app, crate::spotify::LOOPBACK_REDIRECT).await;
    let kid = crate::spotify::tests::store_account(&app, "fakekid", "Ella", "FAKE-REFRESH-1").await;
    for (key, extra, renew) in [
        (
            "music-spotify",
            music(json!({"package": "me.vibb.music", "bridge": "bound", "spotify": "not_premium"})),
            false,
        ),
        (
            "music-spotify-login",
            music(json!({"package": "me.vibb.music", "bridge": "bound", "spotify": "ok"})),
            true,
        ),
    ] {
        let (id, token) = clean_phone(&app, key).await;
        sqlx::query("UPDATE device_policy SET music_spotify_account = ? WHERE device_id = ?")
            .bind(kid)
            .bind(id)
            .execute(&app.db)
            .await
            .unwrap();
        sqlx::query("UPDATE music_spotify_accounts SET needs_login = ? WHERE id = ?")
            .bind(renew)
            .bind(kid)
            .execute(&app.db)
            .await
            .unwrap();
        report(&app, &token, extra).await;
        assert_eq!(rows(&app, &cookie, id).await, [key], "{key}");
        let summary = app
            .get_page(&format!("/devices/{id}"), &cookie)
            .await
            .text();
        let at = summary.find(&format!("data-attention=\"{key}\"")).unwrap();
        let start = summary[..at].rfind("<a").unwrap();
        let target = tags(&summary[start..], "a")[0]
            .attr("href")
            .unwrap()
            .to_string();
        assert_eq!(
            target,
            format!("/devices/{id}/music#music-spotify"),
            "{key}"
        );
        let page = app
            .get_page(&format!("/devices/{id}/music"), &cookie)
            .await
            .text();
        assert!(page.contains("id=\"music-spotify\""), "{key}");
        let row = music_row(&summary);
        assert!(
            row.contains("row-value-action\">Needs attention<"),
            "{key}: {row}"
        );
        let list = app.get_page("/devices", &cookie).await.text();
        let list_row = &list[list.find(&format!("data-device=\"{id}\"")).unwrap()..];
        let list_row = &list_row[..list_row.find("</a>").unwrap()];
        assert!(
            list_row.contains("data-attention-count=\"1\""),
            "{key}: {list_row}"
        );
        // Unbound, nothing.
        sqlx::query("UPDATE device_policy SET music_spotify_account = NULL WHERE device_id = ?")
            .bind(id)
            .execute(&app.db)
            .await
            .unwrap();
        assert!(rows(&app, &cookie, id).await.is_empty(), "{key}");
    }
    // The older-phone warning: the two switches differ while the phone sends no `extra_bytes`.
    let (id, token) = clean_phone(&app, "older").await;
    sqlx::query("UPDATE device_policy SET music_stream_mobile_data = 1 WHERE device_id = ?")
        .bind(id)
        .execute(&app.db)
        .await
        .unwrap();
    report(
        &app,
        &token,
        music(json!({"package": "me.vibb.music", "bridge": "bound", "cache_bytes": 1_000_000})),
    )
    .await;
    assert_eq!(rows(&app, &cookie, id).await, ["music-older-phone"]);
    // An error for an entry that isn't on for the phone is nothing to fix (N3).
    let (id, token) = clean_phone(&app, "off").await;
    report(
        &app,
        &token,
        music(json!({"package": "me.vibb.music", "bridge": "bound",
                     "entry_errors": [{"entry": other, "error": "not_found"}]})),
    )
    .await;
    assert_eq!(rows(&app, &cookie, id).await, Vec::<String>::new());
    let summary = app
        .get_page(&format!("/devices/{id}"), &cookie)
        .await
        .text();
    assert!(!music_row(&summary).contains("Needs attention"));
}

/// The device page's Music row, up to its end.
fn music_row(summary: &str) -> &str {
    let row = &summary[summary.find("data-row=\"music\"").expect("the Music row")..];
    &row[..row.find("</a>").unwrap()]
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

/// Every finding of the call report is an Attention row.
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
    // One problem per finding, each an Attention row (design 31 step 8: the report is typed; the
    // calls page words the same findings).
    assert!(report.findings.len() >= 9, "{:?}", report.findings);
    let rows = crate::attention::items(
        id,
        &crate::attention::Facts {
            calls: report.problems(),
            ..Default::default()
        },
    );
    assert_eq!(rows.len(), report.findings.len(), "{:?}", report.findings);
}
