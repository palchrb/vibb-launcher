//! The Attention rows, fact by fact (design 31 §3.2, qa-31-s2-code L1): each report field or
//! setting that should raise a row raises exactly that row on a phone that is otherwise clean,
//! and the call report types every warning it writes.

use serde_json::{Value, json};

use super::{TestApp, tags};

/// A status report.
async fn report(app: &TestApp, token: &str, extra: Value) {
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

/// 2026-11-04: a pause ended on the phone (Home's "Lock again") goes with the report that says so - the phone sends it
/// at once -, and one whose end never got through is over by the clock once the report is older than the longest pause.
#[tokio::test]
async fn a_pause_goes_with_its_report_or_the_clock() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = clean_phone(&app, "Paused").await;
    report(&app, &token, json!({"restrictions_paused": true})).await;
    assert_eq!(rows(&app, &cookie, id).await, ["restrictions-paused"]);
    report(&app, &token, json!({"restrictions_paused": false})).await;
    assert_eq!(rows(&app, &cookie, id).await, Vec::<String>::new());

    report(&app, &token, json!({"restrictions_paused": true})).await;
    let age = |minutes: i64| {
        sqlx::query("UPDATE device_status SET reported_at = datetime('now', ?) WHERE device_id = ?")
            .bind(format!("-{minutes} minutes"))
            .bind(id)
            .execute(&app.db)
    };
    age(119).await.unwrap();
    assert_eq!(rows(&app, &cookie, id).await, ["restrictions-paused"]);
    age(121).await.unwrap();
    assert_eq!(rows(&app, &cookie, id).await, Vec::<String>::new());
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
        // Design 114: notification access off is a setup step, counted in the setup row.
        ("setup", json!({"notification_listener_enabled": false})),
        (
            "screen-time-record",
            json!({"time_state": {"day": "2026-10-13", "used_minutes": 0, "budget_minutes": 60,
                                  "extra_minutes": 0, "active_rule_id": null,
                                  "active_rule_name": null, "calls_blocked": false,
                                  "lock_reason": "NONE", "lifts_active": [],
                                  "ledger_unreadable": true}}),
        ),
        // Design 114: Android's own mobile-data switch off is a setup step (design 113's own row folded, QA H1).
        ("setup", json!({"setup": {"mobile_data": false}})),
        // Design 114 §5.2 (QA M4): not device owner is its own red row, and the steps don't raise the setup row.
        (
            "not-device-owner",
            json!({"setup": {"device_owner": false, "location": false}}),
        ),
    ];
    for (key, extra) in cases {
        let (id, token) = clean_phone(&app, key).await;
        report(&app, &token, extra).await;
        assert_eq!(rows(&app, &cookie, id).await, [key], "{key}");
    }

    // Each needs a switch on this server and the report that goes with it (M4, L4). The switch
    // on its own, with a report that says nothing, raises nothing.
    let cases: Vec<(&str, &str, Value)> = vec![
        (
            "not-home-app",
            "update_fence = 1",
            json!({"update_fence": {"enabled": true, "state": "none", "home_role_held": false}}),
        ),
        (
            "boot-cover-not-shown",
            "boot_cover = 1",
            json!({"boot_cover": {"wanted": true, "last_armed_at_ms": now_ms - 3_600_000}}),
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
        if key == "boot-cover-not-shown" {
            // 2026-11-17: "not shown" only once the phone started after the arm.
            sqlx::query(
                "INSERT INTO parent_events (device_id, kind, dedup_key, title, summary, target, happened_at) \
                 VALUES (?, 'phone_on', 'on', 'on', 'on', '/devices', datetime('now'))",
            )
            .bind(id)
            .execute(&app.db)
            .await
            .unwrap();
        }
        report(&app, &token, json!({})).await;
        assert_eq!(rows(&app, &cookie, id).await, Vec::<String>::new(), "{key}");
        report(&app, &token, extra).await;
        assert_eq!(rows(&app, &cookie, id).await, [key], "{key}");
    }
    // Design 113: the kid's own switch off (Android's still on) raises nothing.
    let (kid_off, token) = clean_phone(&app, "kid off").await;
    report(
        &app,
        &token,
        json!({"mobile_data": {"enabled": true, "sink": "kid", "settings_locked": true, "kid": "off"}}),
    )
    .await;
    assert_eq!(rows(&app, &cookie, kid_off).await, Vec::<String>::new());

    // Install mode and an openable Play Store (block off) are the parent's choices: no row (2026-11-18).
    let (play_open, token) = clean_phone(&app, "play open").await;
    sqlx::query("UPDATE device_policy SET block_activity_start = 0 WHERE device_id = ?")
        .bind(play_open)
        .execute(&app.db)
        .await
        .unwrap();
    report(
        &app,
        &token,
        json!({"play_store_suspendable": false,
               "install_mode": {"until_ms": now_ms + 600_000}}),
    )
    .await;
    assert_eq!(rows(&app, &cookie, play_open).await, Vec::<String>::new());

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

    // A no-calls rule while calls aren't managed: the parent's choice, no row (2026-11-18).
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
    assert_eq!(rows(&app, &cookie, rule_phone).await, Vec::<String>::new());

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
        json!({"lock_state": {"active": false, "inactive": "crash_guard"}}),
    )
    .await;
    assert_eq!(rows(&app, &cookie, lock_phone).await, ["child-lock-off"]);
    // The same phone with its lock on: nothing.
    report(&app, &token, json!({"lock_state": {"active": true}})).await;
    assert_eq!(rows(&app, &cookie, lock_phone).await, Vec::<String>::new());

    // Access removed.
    let (revoked, _) = clean_phone(&app, "revoked").await;
    sqlx::query("UPDATE devices SET token_hash = NULL WHERE id = ?")
        .bind(revoked)
        .execute(&app.db)
        .await
        .unwrap();
    // The parent's own "Remove access": no Attention row (2026-11-18), a quiet line on Advanced.
    assert_eq!(rows(&app, &cookie, revoked).await, Vec::<String>::new());
    let adv = app
        .get_page(&format!("/devices/{revoked}/advanced"), &cookie)
        .await
        .text();
    assert!(adv.contains("data-note=\"access-removed\""));
    assert!(!adv.contains("data-warning=\"access-removed\""));
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
    let music = |state: Value| json!({"music_state": state});
    let cases: Vec<(&str, Value)> = vec![
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
    sqlx::query("UPDATE device_policy SET calls_mode = 'managed', sms_mode = 'unmanaged' WHERE device_id = ?")
        .bind(id)
        .execute(&app.db)
        .await
        .unwrap();
    assert_eq!(rows(&app, &cookie, id).await, Vec::<String>::new());
    let list = app.get_page("/devices", &cookie).await.text();
    assert!(!list.contains("data-attention-count"), "{list}");
}

/// Every finding of the call report is an Attention row, but SMS off with the Messages app ticked (2026-11-18: the
/// parent's choices, a quiet row on the calls page).
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
    report(&app, &token, json!({"call_state": call_state})).await;
    sqlx::query(
        "UPDATE device_policy SET calls_mode = 'managed', sms_mode = 'off', \
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
        crate::i18n::Lang::En,
        id,
        &crate::attention::Facts {
            calls: report.problems(),
            ..Default::default()
        },
    );
    let choices = report
        .problems()
        .iter()
        .filter(|p| **p == crate::handlers::calls::CallProblem::SmsAppAllowed)
        .count();
    assert_eq!(choices, 1, "{:?}", report.findings);
    assert_eq!(
        rows.len(),
        report.findings.len() - choices,
        "{:?}",
        report.findings
    );
    assert!(
        rows.iter()
            .all(|r| r.title != "Messages app allowed with SMS off")
    );
}

/// A silent time rule makes the launcher turn on its own Do Not Disturb (priority only): the rule's own, so not an
/// Attention row; anything else Do Not Disturb is.
#[tokio::test]
async fn a_silent_rules_own_do_not_disturb_is_no_row() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = clean_phone(&app, "Emma").await;
    let add = |name: &'static str, silent: bool| {
        sqlx::query_scalar::<_, i64>(
            "INSERT INTO time_rules (name, kind, calls_allowed, exempt_apps_json, silent, days_json) \
             VALUES (?, 'school', 1, '[]', ?, '[null,null,null,null,null,null,null]') RETURNING id",
        )
        .bind(name)
        .bind(silent)
        .fetch_one(&app.db)
    };
    let silent = add("Skole", true).await.unwrap();
    let loud = add("Middag", false).await.unwrap();
    let state = |rule: i64, filter: &str| {
        json!({
            "interruption_filter": filter,
            "time_state": {"lock_reason": "SCHOOL", "active_rule_id": rule, "active_rule_name": "Skole"},
        })
    };
    let dnd = ["do-not-disturb"];
    // A silent rule in force with priority: its own.
    report(&app, &token, state(silent, "priority")).await;
    assert_eq!(rows(&app, &cookie, id).await, Vec::<String>::new());
    // A non-silent rule, or a stricter filter: raised.
    report(&app, &token, state(loud, "priority")).await;
    assert_eq!(rows(&app, &cookie, id).await, dnd);
    report(&app, &token, state(silent, "none")).await;
    assert_eq!(rows(&app, &cookie, id).await, dnd);
    report(&app, &token, state(silent, "alarms")).await;
    assert_eq!(rows(&app, &cookie, id).await, dnd);
    // No rule in force: raised.
    report(&app, &token, json!({"interruption_filter": "priority"})).await;
    assert_eq!(rows(&app, &cookie, id).await, dnd);
    // "Slå på nå" copy of the silent rule (negative id): the original's `silent`.
    sqlx::query(
        "INSERT INTO time_lock_now (id, device_id, rule_id, rule_name, zone, local_date, \
         start_minute, end_minute, ends_at) VALUES (7, ?, ?, 'Skole', 'Europe/Oslo', \
         '2026-11-16', 720, 1080, '2099-01-01 00:00:00')",
    )
    .bind(id)
    .bind(silent)
    .execute(&app.db)
    .await
    .unwrap();
    report(&app, &token, state(-7, "priority")).await;
    assert_eq!(rows(&app, &cookie, id).await, Vec::<String>::new());
    report(&app, &token, state(-7, "none")).await;
    assert_eq!(rows(&app, &cookie, id).await, dnd);
}

/// Design 116 §2.3: an outside service's failure raises a row only on the phones whose features use it - an app on
/// one phone, Vibb One's own updates on every phone, a disabled app on none, a calendar link on the two phones that
/// have it, the weather only where it is on, a blocklist only where it is in force -, one row per title with the
/// failing scopes summed, to the page that says what the error is; a success clears it.
#[tokio::test]
async fn an_integrations_failure_shows_on_the_phones_that_use_it() {
    use crate::integration_health::{Class, Title, failed_at, for_device, ok};
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (emma, _) = clean_phone(&app, "Emma").await;
    let (jack, _) = clean_phone(&app, "Jack").await;
    let (sam, _) = clean_phone(&app, "Sam").await;
    let db = &app.db;
    let past = chrono::Utc::now() - chrono::Duration::hours(30);
    // A sustained transient run (`Down`) and a parent-class pair (`Fix`).
    let down = |key: &'static str, scope: String| async move {
        for _ in 0..3 {
            failed_at(db, key, &scope, "network", Class::Transient, past).await;
        }
    };
    let fix = |key: &'static str, scope: String| async move {
        for _ in 0..2 {
            failed_at(db, key, &scope, "not_found", Class::Parent, past).await;
        }
    };
    let app_row = |name: &'static str, enabled: bool, launcher: bool| async move {
        sqlx::query_scalar::<_, i64>(
            "INSERT INTO tracked_apps (name, package_name, github_repo, enabled, is_launcher) \
             VALUES (?, '', 'o/r', ?, ?) RETURNING id",
        )
        .bind(name)
        .bind(enabled)
        .bind(launcher)
        .fetch_one(db)
        .await
        .unwrap()
    };
    let tick = |device: i64, app: i64| async move {
        sqlx::query("INSERT INTO device_tracked_apps (device_id, tracked_app_id) VALUES (?, ?)")
            .bind(device)
            .bind(app)
            .execute(db)
            .await
            .unwrap();
    };

    // Apps: one on Emma, Vibb One (never in device_tracked_apps), one disabled on Emma.
    let mine = app_row("Fixture", true, false).await;
    tick(emma, mine).await;
    let vibb = app_row("Vibb One", true, true).await;
    let off = app_row("Old", false, false).await;
    tick(emma, off).await;
    fix("github", format!("app:{mine}")).await;
    fix("fdroid", format!("app:{vibb}")).await;
    down("github", format!("app:{off}")).await;

    // A calendar link on Emma and Jack.
    let source: i64 = sqlx::query_scalar(
        "INSERT INTO calendar_sources (uid, url_sealed, url_nonce, url_key_fp, host, rev, feed_json, next_at) \
         VALUES (?, x'00', x'00', 'fp', 'skole.example.no', 'r', '{}', datetime('now')) RETURNING id",
    )
    .bind("a".repeat(32))
    .fetch_one(db)
    .await
    .unwrap();
    for (device, name) in [(emma, "Skole"), (jack, "Skolen")] {
        sqlx::query(
            "INSERT INTO device_calendar_feeds (device_id, source_id, name) VALUES (?, ?, ?)",
        )
        .bind(device)
        .bind(source)
        .bind(name)
        .execute(db)
        .await
        .unwrap();
    }
    down("calendar_link", format!("source:{source}")).await;

    // The weather on Emma only.
    sqlx::query("UPDATE device_policy SET lock_weather = 1 WHERE device_id = ?")
        .bind(emma)
        .execute(db)
        .await
        .unwrap();
    down("met", String::new()).await;

    // Blocklists: Ads on for all but switched off on Emma; Kids off for all but on for Emma; Sam's filter off.
    let feed = |name: &'static str, enabled: bool| async move {
        sqlx::query_scalar::<_, i64>(
            "INSERT INTO dns_blocklists (name, url, enabled) VALUES (?, 'https://lists.example/' || ?, ?) \
             RETURNING id",
        )
        .bind(name)
        .bind(name)
        .bind(enabled)
        .fetch_one(db)
        .await
        .unwrap()
    };
    let ads = feed("Ads", true).await;
    let kids = feed("Kids", false).await;
    for (feed, enabled) in [(ads, false), (kids, true)] {
        sqlx::query(
            "INSERT INTO device_blocklist_overrides (device_id, blocklist_id, enabled) VALUES (?, ?, ?)",
        )
        .bind(emma)
        .bind(feed)
        .bind(enabled)
        .execute(db)
        .await
        .unwrap();
    }
    sqlx::query("UPDATE device_policy SET vpn_filter_enabled = 0 WHERE device_id = ?")
        .bind(sam)
        .execute(db)
        .await
        .unwrap();
    fix("blocklist", format!("feed:{ads}")).await;
    fix("blocklist", format!("feed:{kids}")).await;

    let now = chrono::Utc::now();
    assert_eq!(
        for_device(db, emma, now).await.unwrap(),
        [
            (Title::AppsFix, 2),
            (Title::MetDown, 1),
            (Title::CalendarLink, 1),
            (Title::Blocklist, 1),
        ]
    );
    assert_eq!(
        for_device(db, jack, now).await.unwrap(),
        [
            (Title::AppsFix, 1),
            (Title::CalendarLink, 1),
            (Title::Blocklist, 1),
        ]
    );
    assert_eq!(
        for_device(db, sam, now).await.unwrap(),
        [(Title::AppsFix, 1)]
    );

    // The rows on Emma's page: after every other row, one per title, worded and linked.
    let page = app
        .get_page(&format!("/devices/{emma}"), &cookie)
        .await
        .text();
    let links: Vec<(String, String)> = super::tags(&page, "a")
        .into_iter()
        .filter_map(|tag| {
            Some((
                tag.attr("data-attention")?.to_string(),
                tag.attr("href")?.to_string(),
            ))
        })
        .collect();
    assert_eq!(
        links,
        [
            ("integration-apps-fix", "/apps".to_string()),
            (
                "integration-met-down",
                format!("/devices/{emma}/lock#weather")
            ),
            (
                "integration-calendar-link",
                format!("/devices/{emma}/calendar")
            ),
            ("integration-blocklist", "/dns".to_string()),
        ]
        .map(|(k, h)| (k.to_string(), h))
    );
    assert!(page.contains("2 apps can&#39;t get updates"), "{page}");
    // The lock page says the same as the row.
    let lock = app
        .get_page(&format!("/devices/{emma}/lock"), &cookie)
        .await
        .text();
    assert!(lock.contains("data-note=\"weather-down\""));
    assert!(lock.contains("Weather isn&#39;t loading"));

    // A success clears it at once.
    ok(db, "met", "").await;
    ok(db, "calendar_link", &format!("source:{source}")).await;
    assert_eq!(
        for_device(db, jack, chrono::Utc::now()).await.unwrap(),
        [(Title::AppsFix, 1), (Title::Blocklist, 1)]
    );
    assert_eq!(rows(&app, &cookie, sam).await, ["integration-apps-fix"]);
}

/// Design 116 §3.4 (QA M10): "Web filter is down" from a report that says so for 10 minutes, red; marked "at last
/// report" when the phone is offline; none under 10 minutes, with the parent's switch off or the report paused. The
/// filter page says how long, the `/dns` page names the phone.
#[tokio::test]
async fn a_filter_down_report_raises_its_row_unless_a_choice_explains_it() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = clean_phone(&app, "Emma").await;
    let down =
        |ms: i64| json!({"vpn_filter": {"state": "down", "down_ms": ms, "period": "5:3000"}});
    report(&app, &token, down(599_000)).await;
    assert_eq!(rows(&app, &cookie, id).await, Vec::<String>::new());
    report(&app, &token, down(1_500_000)).await;
    assert_eq!(rows(&app, &cookie, id).await, ["filter-down"]);
    let filter = app
        .get_page(&format!("/devices/{id}/filter"), &cookie)
        .await
        .text();
    assert!(filter.contains("data-note=\"filter-down\""), "{filter}");
    assert!(filter.contains("Down for 25 min"), "{filter}");
    let dns = app.get_page("/dns", &cookie).await.text();
    assert!(dns.contains("Emma: down"), "{dns}");

    // Offline: the report's row is marked.
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
    let row = &page[page.find("data-attention=\"filter-down\"").unwrap()..];
    assert!(row[..row.find("</a>").unwrap()].contains("data-at-last-report"));

    // A pause on the phone, then the parent's own switch off: no row.
    report(
        &app,
        &token,
        json!({"restrictions_paused": true, "vpn_filter": {"state": "down", "down_ms": 1_500_000, "period": "5:3000"}}),
    )
    .await;
    assert!(
        !rows(&app, &cookie, id)
            .await
            .contains(&"filter-down".to_string())
    );
    report(&app, &token, down(1_500_000)).await;
    sqlx::query("UPDATE device_policy SET vpn_filter_enabled = 0 WHERE device_id = ?")
        .bind(id)
        .execute(&app.db)
        .await
        .unwrap();
    assert_eq!(rows(&app, &cookie, id).await, Vec::<String>::new());
}
