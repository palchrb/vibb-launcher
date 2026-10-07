use axum::http::{Method, StatusCode};
use axum::response::IntoResponse;
use serde_json::json;

use super::TestApp;

#[tokio::test]
async fn enroll_returns_token_and_code_is_single_use() {
    let app = TestApp::new().await;
    let (id, code) = app.create_device("phone").await;

    let res = app
        .request(
            Method::POST,
            "/api/devices/enroll",
            None,
            Some(json!({ "enrollment_code": code })),
        )
        .await;
    assert_eq!(res.status, StatusCode::OK);
    let body = res.json();
    assert_eq!(body["device_id"], id);
    assert!(body["device_token"].as_str().is_some_and(|t| !t.is_empty()));

    let replay = app
        .request(
            Method::POST,
            "/api/devices/enroll",
            None,
            Some(json!({ "enrollment_code": code })),
        )
        .await;
    assert_eq!(replay.status, StatusCode::UNAUTHORIZED);
}

#[tokio::test]
async fn enroll_rejects_expired_code() {
    let app = TestApp::new().await;
    let (id, code) = app.create_device("phone").await;
    sqlx::query(
        "UPDATE devices SET enrollment_code_expires_at = datetime('now', '-1 minute') WHERE id = ?",
    )
    .bind(id)
    .execute(&app.db)
    .await
    .unwrap();

    let res = app
        .request(
            Method::POST,
            "/api/devices/enroll",
            None,
            Some(json!({ "enrollment_code": code })),
        )
        .await;
    assert_eq!(res.status, StatusCode::UNAUTHORIZED);
}

#[tokio::test]
async fn device_api_requires_valid_bearer_token() {
    let app = TestApp::new().await;
    app.enrolled_device("phone").await;

    let missing = app
        .request(Method::GET, "/api/devices/policy", None, None)
        .await;
    assert_eq!(missing.status, StatusCode::UNAUTHORIZED);

    let wrong = app
        .request(
            Method::GET,
            "/api/devices/policy",
            Some("not-a-token"),
            None,
        )
        .await;
    assert_eq!(wrong.status, StatusCode::UNAUTHORIZED);
}

#[tokio::test]
async fn policy_reflects_stored_allowlist_and_kiosk() {
    let app = TestApp::new().await;
    let (id, token) = app.enrolled_device("phone").await;
    sqlx::query("UPDATE device_policy SET allowlist_json = ? WHERE device_id = ?")
        .bind(r#"["org.example.music","org.example.chat"]"#)
        .bind(id)
        .execute(&app.db)
        .await
        .unwrap();

    let res = app
        .request(Method::GET, "/api/devices/policy", Some(&token), None)
        .await;
    assert_eq!(res.status, StatusCode::OK);
    let policy = res.json();
    assert_eq!(policy["kiosk_desired"], true);
    assert_eq!(
        policy["allowlist"],
        json!(["org.example.music", "org.example.chat"])
    );
}

#[tokio::test]
async fn pending_command_is_delivered_once() {
    let app = TestApp::new().await;
    let (id, token) = app.enrolled_device("phone").await;
    sqlx::query("INSERT INTO device_commands (device_id, command) VALUES (?, 'ring')")
        .bind(id)
        .execute(&app.db)
        .await
        .unwrap();

    let first = app
        .request(Method::GET, "/api/devices/policy", Some(&token), None)
        .await
        .json();
    assert_eq!(first["pending_command"]["command"], "ring");

    let second = app
        .request(Method::GET, "/api/devices/policy", Some(&token), None)
        .await
        .json();
    assert!(second["pending_command"].is_null());
}

#[tokio::test]
async fn devices_only_see_their_own_commands() {
    let app = TestApp::new().await;
    let (a, _) = app.enrolled_device("a").await;
    let (_, token_b) = app.enrolled_device("b").await;
    sqlx::query("INSERT INTO device_commands (device_id, command) VALUES (?, 'wipe')")
        .bind(a)
        .execute(&app.db)
        .await
        .unwrap();

    let policy_b = app
        .request(Method::GET, "/api/devices/policy", Some(&token_b), None)
        .await
        .json();
    assert!(policy_b["pending_command"].is_null());
}

/// A device whose policy row is missing must not be handed an unrestricted policy. Upstream
/// fell back to `DevicePolicy::default()` (kiosk off, no allowlist) and answered 200, which the
/// launcher applied as "no restrictions".
#[tokio::test]
async fn missing_policy_row_does_not_unlock() {
    let app = TestApp::new().await;
    let (id, token) = app.enrolled_device("phone").await;
    sqlx::query("DELETE FROM device_policy WHERE device_id = ?")
        .bind(id)
        .execute(&app.db)
        .await
        .unwrap();

    let res = app
        .request(Method::GET, "/api/devices/policy", Some(&token), None)
        .await;
    assert_eq!(res.status, StatusCode::INTERNAL_SERVER_ERROR);
    assert!(res.body.is_empty(), "error body: {}", res.text());
}

async fn set_allowlist_json(app: &TestApp, device_id: i64, json: Option<&str>) {
    sqlx::query("UPDATE device_policy SET allowlist_json = ? WHERE device_id = ?")
        .bind(json)
        .bind(device_id)
        .execute(&app.db)
        .await
        .unwrap();
}

#[tokio::test]
async fn corrupt_allowlist_json_is_500() {
    let app = TestApp::new().await;
    let (id, token) = app.enrolled_device("phone").await;
    set_allowlist_json(&app, id, Some("not json")).await;

    let res = app
        .request(Method::GET, "/api/devices/policy", Some(&token), None)
        .await;
    assert_eq!(res.status, StatusCode::INTERNAL_SERVER_ERROR);
}

#[tokio::test]
async fn db_error_is_500_not_default() {
    for table in [
        "dns_filter_settings",
        "global_schedule",
        "device_pending_uninstalls",
    ] {
        let app = TestApp::new().await;
        let (_, token) = app.enrolled_device("phone").await;
        sqlx::query(&format!("DROP TABLE {table}"))
            .execute(&app.db)
            .await
            .unwrap();

        let res = app
            .request(Method::GET, "/api/devices/policy", Some(&token), None)
            .await;
        assert_eq!(
            res.status,
            StatusCode::INTERNAL_SERVER_ERROR,
            "dropping {table} still produced a policy: {}",
            res.text()
        );
    }
}

#[tokio::test]
async fn failed_policy_does_not_consume_command() {
    let app = TestApp::new().await;
    let (id, token) = app.enrolled_device("phone").await;
    sqlx::query("INSERT INTO device_commands (device_id, command) VALUES (?, 'ring')")
        .bind(id)
        .execute(&app.db)
        .await
        .unwrap();

    set_allowlist_json(&app, id, Some("not json")).await;
    let failed = app
        .request(Method::GET, "/api/devices/policy", Some(&token), None)
        .await;
    assert_eq!(failed.status, StatusCode::INTERNAL_SERVER_ERROR);

    set_allowlist_json(&app, id, Some("[]")).await;
    let first = app
        .request(Method::GET, "/api/devices/policy", Some(&token), None)
        .await;
    assert_eq!(first.status, StatusCode::OK);
    assert_eq!(first.json()["pending_command"]["command"], "ring");

    let second = app
        .request(Method::GET, "/api/devices/policy", Some(&token), None)
        .await
        .json();
    assert!(second["pending_command"].is_null());
}

#[tokio::test]
async fn concurrent_polls_deliver_a_command_once() {
    let app = TestApp::new().await;
    let (id, token) = app.enrolled_device("phone").await;
    sqlx::query("INSERT INTO device_commands (device_id, command) VALUES (?, 'ring')")
        .bind(id)
        .execute(&app.db)
        .await
        .unwrap();

    // Interleaved on one task, so each request's DB queries overlap with the others'.
    let poll = || app.request(Method::GET, "/api/devices/policy", Some(&token), None);
    let (a, b, c, d) = tokio::join!(poll(), poll(), poll(), poll());
    let delivered = [a, b, c, d]
        .iter()
        .filter(|res| {
            assert_eq!(res.status, StatusCode::OK);
            !res.json()["pending_command"].is_null()
        })
        .count();
    assert_eq!(delivered, 1);
}

#[tokio::test]
async fn null_allowlist_stays_null() {
    let app = TestApp::new().await;
    let (_, token) = app.enrolled_device("phone").await;
    let res = app
        .request(Method::GET, "/api/devices/policy", Some(&token), None)
        .await;
    assert_eq!(res.status, StatusCode::OK);
    assert!(res.json()["allowlist"].is_null());
}

#[tokio::test]
async fn empty_allowlist_is_served_as_empty_not_null() {
    let app = TestApp::new().await;
    let (id, token) = app.enrolled_device("phone").await;
    set_allowlist_json(&app, id, Some("[]")).await;
    let policy = app
        .request(Method::GET, "/api/devices/policy", Some(&token), None)
        .await
        .json();
    assert_eq!(policy["allowlist"], json!([]));
}

/// Contract test against the launcher's `PolicyResponse` DTO
/// (kids-launcher-mdm `server/dto/PolicyResponse.kt`): the exact key set, and no `null` for a
/// field the launcher declares non-nullable (it has no `coerceInputValues`, so a null there
/// fails the whole decode).
#[tokio::test]
async fn policy_json_keys_snapshot() {
    let app = TestApp::new().await;
    let (_, token) = app.enrolled_device("phone").await;
    let policy = app
        .request(Method::GET, "/api/devices/policy", Some(&token), None)
        .await
        .json();
    let object = policy.as_object().expect("policy is a JSON object");

    let mut keys: Vec<&str> = object.keys().map(String::as_str).collect();
    keys.sort_unstable();
    assert_eq!(
        keys,
        [
            "allowlist",
            "app_updates_wifi_only",
            "bedtime_end_minutes",
            "bedtime_start_minutes",
            "block_activity_start",
            "boot_cover",
            "call_policy",
            "dns_filter_version",
            "dns_log_enabled",
            "dns_upstream_provider",
            "hardening",
            "kid_lock",
            "kiosk_desired",
            "launcher_ui",
            "location_policy",
            "lock_task_features",
            "notification_auto_cancel",
            "override_pin_hash",
            "override_pin_salt",
            "packages_to_uninstall",
            "pending_command",
            "push",
            "quick_controls_mask",
            "screen_timeout_seconds",
            "time_policy",
            "update_fence",
            "vpn_filter_enabled",
            "weekday_end_minutes",
            "weekday_start_minutes",
            "weekend_end_minutes",
            "weekend_start_minutes",
        ]
    );

    for non_null in [
        "app_updates_wifi_only",
        "block_activity_start",
        "call_policy",
        "dns_filter_version",
        "dns_log_enabled",
        "dns_upstream_provider",
        "hardening",
        "kiosk_desired",
        "launcher_ui",
        "location_policy",
        "lock_task_features",
        "notification_auto_cancel",
        "packages_to_uninstall",
        "push",
        "quick_controls_mask",
        "screen_timeout_seconds",
        "time_policy",
        "update_fence",
        "vpn_filter_enabled",
    ] {
        assert!(!object[non_null].is_null(), "{non_null} is null");
    }

    // The launcher's CallPolicy DTO: every key present and non-null, also when unmanaged.
    let call_policy = object["call_policy"].as_object().unwrap();
    let mut call_keys: Vec<&str> = call_policy.keys().map(String::as_str).collect();
    call_keys.sort_unstable();
    assert_eq!(
        call_keys,
        [
            "calls_enabled",
            "contacts",
            "default_country_code",
            "managed",
            "sms_enabled",
        ]
    );
    assert!(call_policy.values().all(|v| !v.is_null()));

    // The launcher's LauncherUi DTO (steps 5 and 8): every key, wallpapers always a list.
    let launcher_ui = object["launcher_ui"].as_object().unwrap();
    let mut ui_keys: Vec<&str> = launcher_ui.keys().map(String::as_str).collect();
    ui_keys.sort_unstable();
    assert_eq!(
        ui_keys,
        ["app_display", "home_columns", "language", "wallpapers"]
    );
    // Design 14 (QA #1): always a list, even when nothing is named.
    assert_eq!(launcher_ui["app_display"], serde_json::json!([]));
    assert!(
        launcher_ui["wallpapers"]
            .as_array()
            .is_some_and(|w| !w.is_empty())
    );

    // The launcher's TimePolicy / LocationPolicy DTOs (handy step 6).
    let time_policy = object["time_policy"].as_object().unwrap();
    let mut time_keys: Vec<&str> = time_policy.keys().map(String::as_str).collect();
    time_keys.sort_unstable();
    assert_eq!(time_keys, ["daily_budget_minutes", "lifts", "rules"]);
    assert_eq!(
        object["location_policy"],
        serde_json::json!({"mode": "on_request", "interval_minutes": 30})
    );
}

#[tokio::test]
async fn create_device_inserts_policy_row() {
    let app = TestApp::new().await;
    let response = crate::handlers::devices::create_device(
        axum::extract::State(app.state.clone()),
        axum::Form(crate::handlers::devices::CreateDeviceForm {
            name: "phone".to_string(),
        }),
    )
    .await;
    assert!(response.status().is_redirection());
    let (devices, policies): (i64, i64) = sqlx::query_as(
        "SELECT (SELECT COUNT(*) FROM devices), (SELECT COUNT(*) FROM device_policy)",
    )
    .fetch_one(&app.db)
    .await
    .unwrap();
    assert_eq!((devices, policies), (1, 1));
}

/// Kid Settings (Quick Controls): a new device starts with Wi-Fi, Bluetooth and brightness on,
/// and its policy says so; a device that existed before keeps what it had.
#[tokio::test]
async fn a_new_device_starts_with_all_kid_settings_on() {
    let app = TestApp::new().await;
    let (existing, _) = app.create_device("old").await;
    let response = crate::handlers::devices::create_device(
        axum::extract::State(app.state.clone()),
        axum::Form(crate::handlers::devices::CreateDeviceForm {
            name: "new".to_string(),
        }),
    )
    .await;
    assert!(response.status().is_redirection());
    let (id, code): (i64, String) =
        sqlx::query_as("SELECT id, enrollment_code FROM devices WHERE name = 'new'")
            .fetch_one(&app.db)
            .await
            .unwrap();
    let mask = |device: i64| {
        sqlx::query_scalar::<_, i64>(
            "SELECT quick_controls_mask FROM device_policy WHERE device_id = ?",
        )
        .bind(device)
        .fetch_one(&app.db)
    };
    assert_eq!(mask(id).await.unwrap(), 1 | 2 | 4 | 8);
    assert_eq!(
        crate::handlers::devices::DEFAULT_QUICK_CONTROLS,
        1 | 2 | 4 | 8,
        "Wi-Fi, Bluetooth, brightness, sound (design 18)"
    );
    assert_eq!(mask(existing).await.unwrap(), 0);

    let enrolled = app
        .request(
            Method::POST,
            "/api/devices/enroll",
            None,
            Some(serde_json::json!({ "enrollment_code": code })),
        )
        .await;
    let token = enrolled.json()["device_token"]
        .as_str()
        .unwrap()
        .to_string();
    let policy = app
        .request(Method::GET, "/api/devices/policy", Some(&token), None)
        .await;
    assert_eq!(policy.json()["quick_controls_mask"], 15);
}

#[tokio::test]
async fn failed_create_device_leaves_no_device_row() {
    let app = TestApp::new().await;
    sqlx::query(
        "CREATE TRIGGER fail_policy_insert BEFORE INSERT ON device_policy \
         BEGIN SELECT RAISE(ABORT, 'injected failure'); END",
    )
    .execute(&app.db)
    .await
    .unwrap();

    let response = crate::handlers::devices::create_device(
        axum::extract::State(app.state.clone()),
        axum::Form(crate::handlers::devices::CreateDeviceForm {
            name: "phone".to_string(),
        }),
    )
    .await;
    assert_eq!(response.status(), StatusCode::INTERNAL_SERVER_ERROR);
    let devices: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM devices")
        .fetch_one(&app.db)
        .await
        .unwrap();
    assert_eq!(devices, 0);
}

#[tokio::test]
async fn admin_pages_redirect_to_login_without_session() {
    let app = TestApp::new().await;
    let res = app.request(Method::GET, "/devices", None, None).await;
    assert!(
        res.status.is_redirection(),
        "expected a redirect, got {}",
        res.status
    );
}

#[tokio::test]
async fn admin_cookie_logs_in_through_password_and_totp() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let res = app.get_page("/devices", &cookie).await;
    assert_eq!(res.status, StatusCode::OK, "{}", res.text());
    assert!(res.text().contains("Devices"));
    // A made-up cookie is not a session.
    let res = app.get_page("/devices", "id=not-a-session").await;
    assert!(res.status.is_redirection());
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

#[tokio::test]
async fn status_report_stores_policy_state_and_pause() {
    let app = TestApp::new().await;
    let (id, token) = app.enrolled_device("phone").await;
    post_status(
        &app,
        &token,
        json!({ "policy_state": "cache_corrupt", "restrictions_paused": true }),
    )
    .await;

    let (state, paused): (Option<String>, bool) = sqlx::query_as(
        "SELECT policy_state, restrictions_paused FROM device_status WHERE device_id = ?",
    )
    .bind(id)
    .fetch_one(&app.db)
    .await
    .unwrap();
    assert_eq!(state.as_deref(), Some("cache_corrupt"));
    assert!(paused);

    let page = device_page(&app, id).await;
    assert!(page.contains("read its saved policy"), "{page}");
    assert!(page.contains("All restrictions are paused"));
}

#[tokio::test]
async fn status_report_from_older_launcher_has_no_warnings() {
    let app = TestApp::new().await;
    let (id, token) = app.enrolled_device("phone").await;
    post_status(&app, &token, json!({})).await;

    let (state, paused): (Option<String>, bool) = sqlx::query_as(
        "SELECT policy_state, restrictions_paused FROM device_status WHERE device_id = ?",
    )
    .bind(id)
    .fetch_one(&app.db)
    .await
    .unwrap();
    assert_eq!(state, None);
    assert!(!paused);

    post_status(&app, &token, json!({ "policy_state": "ok" })).await;
    let page = device_page(&app, id).await;
    assert!(!page.contains("saved policy"));
    assert!(!page.contains("All restrictions are paused"));
}

async fn toggle(app: &TestApp, id: i64, package: &str, selected: bool) -> axum::http::StatusCode {
    let mut form = std::collections::HashMap::new();
    form.insert("package_name".to_string(), package.to_string());
    if selected {
        form.insert("selected".to_string(), "on".to_string());
    }
    crate::handlers::devices::toggle_app(
        axum::extract::State(app.state.clone()),
        axum::extract::Path(id),
        axum::Form(form),
    )
    .await
    .status()
}

async fn allowlist_json(app: &TestApp, id: i64) -> Option<String> {
    sqlx::query_scalar("SELECT allowlist_json FROM device_policy WHERE device_id = ?")
        .bind(id)
        .fetch_one(&app.db)
        .await
        .unwrap()
}

#[tokio::test]
async fn toggle_app_adds_and_removes() {
    let app = TestApp::new().await;
    let (id, _) = app.enrolled_device("phone").await;
    set_allowlist_json(&app, id, Some(r#"["a"]"#)).await;

    assert!(toggle(&app, id, "b", true).await.is_redirection());
    assert_eq!(
        allowlist_json(&app, id).await.as_deref(),
        Some(r#"["a","b"]"#)
    );
    assert!(toggle(&app, id, "a", false).await.is_redirection());
    assert_eq!(allowlist_json(&app, id).await.as_deref(), Some(r#"["b"]"#));
}

/// A corrupt stored allowlist used to be read as `[]`: unchecking then silently did nothing (the
/// app stayed allowed) and checking overwrote the list.
#[tokio::test]
async fn toggle_app_with_corrupt_allowlist_fails_without_writing() {
    let app = TestApp::new().await;
    let (id, _) = app.enrolled_device("phone").await;
    set_allowlist_json(&app, id, Some("not json")).await;

    assert_eq!(
        toggle(&app, id, "a", false).await,
        StatusCode::INTERNAL_SERVER_ERROR
    );
    assert_eq!(
        toggle(&app, id, "a", true).await,
        StatusCode::INTERNAL_SERVER_ERROR
    );
    assert_eq!(allowlist_json(&app, id).await.as_deref(), Some("not json"));
}

#[tokio::test]
async fn heartbeat_bootstraps_only_a_null_allowlist() {
    let app = TestApp::new().await;
    let (id, token) = app.enrolled_device("phone").await;
    let apps = json!({ "installed_apps": [{ "package_name": "a", "label": "A" }] });

    post_status(&app, &token, apps.clone()).await;
    assert_eq!(allowlist_json(&app, id).await.as_deref(), Some(r#"["a"]"#));

    set_allowlist_json(&app, id, Some("[]")).await;
    post_status(&app, &token, apps).await;
    assert_eq!(allowlist_json(&app, id).await.as_deref(), Some("[]"));
}

#[tokio::test]
async fn server_error_policy_state_is_shown() {
    let app = TestApp::new().await;
    let (id, token) = app.enrolled_device("phone").await;
    post_status(&app, &token, json!({ "policy_state": "server_error" })).await;
    assert!(device_page(&app, id).await.contains("answered the phone"));
}

async fn timeout(app: &TestApp, token: String) -> serde_json::Value {
    app.request(Method::GET, "/api/devices/policy", Some(&token), None)
        .await
        .json()["screen_timeout_seconds"]
        .clone()
}

/// Auto-lock (migrations/0032, emulator run 2026-10-06): the parent's screen timeout reaches the
/// policy, only the offered values are accepted, and the phone's applied value is shown.
#[tokio::test]
async fn screen_timeout_policy_form_and_report() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("phone").await;
    assert_eq!(timeout(&app, token.clone()).await, json!(60));

    let mut nudges = app.state.command_notify.subscribe();
    let res = app
        .request_form(
            Method::POST,
            &format!("/devices/{id}/screen-timeout"),
            Some(&cookie),
            &[("screen_timeout_seconds", "300")],
        )
        .await;
    assert_eq!(res.location(), Some(format!("/devices/{id}").as_str()));
    assert_eq!(nudges.try_recv().ok(), Some(id));
    assert_eq!(timeout(&app, token.clone()).await, json!(300));

    for bad in ["0", "45", "-1", "never", ""] {
        let res = app
            .request_form(
                Method::POST,
                &format!("/devices/{id}/screen-timeout"),
                Some(&cookie),
                &[("screen_timeout_seconds", bad)],
            )
            .await;
        assert_eq!(res.status, StatusCode::BAD_REQUEST, "{bad}");
    }
    assert_eq!(timeout(&app, token.clone()).await, json!(300));
    let res = app
        .request_form(
            Method::POST,
            "/devices/999/screen-timeout",
            Some(&cookie),
            &[("screen_timeout_seconds", "60")],
        )
        .await;
    assert_eq!(res.status, StatusCode::NOT_FOUND);

    // A hand-written out-of-range value falls back to the default.
    sqlx::query("UPDATE device_policy SET screen_timeout_seconds = 7 WHERE device_id = ?")
        .bind(id)
        .execute(&app.state.db)
        .await
        .unwrap();
    assert_eq!(timeout(&app, token.clone()).await, json!(60));

    let page = device_page(&app, id).await;
    assert!(page.contains("action=\"/devices/"));
    assert!(page.contains("<option value=\"60\" selected>1 minute</option>"));
    assert!(!page.contains("The phone last reported"));
    post_status(&app, &token, json!({ "screen_timeout_seconds": 120 })).await;
    assert!(
        device_page(&app, id)
            .await
            .contains("The phone last reported: 2 minutes.")
    );
    // An implausible report isn't stored.
    post_status(&app, &token, json!({ "screen_timeout_seconds": -5 })).await;
    assert!(
        !device_page(&app, id)
            .await
            .contains("The phone last reported")
    );
}

/// No POST may make the page jump to the top (server/CLAUDE.md): every page template includes the
/// head partial with the scroll-restore script, and the rendered pages carry it.
#[tokio::test]
async fn pages_restore_scroll_after_auto_save() {
    let head = std::fs::read_to_string("templates/partials/head.html").unwrap();
    assert!(head.contains("<script src=\"/static/scroll-restore.js"));
    let script = std::fs::read_to_string("static/scroll-restore.js").unwrap();
    assert!(script.contains("handy-scroll") && script.contains("proto.submit = function"));
    // Its behaviour (path match, 20 s expiry, #fragment skip, the form.submit() hook) is tested
    // with node in jstest/ - run here when node is installed, always in CI.
    match std::process::Command::new("node")
        .args(["--test", "jstest/"])
        .output()
    {
        Ok(out) => assert!(
            out.status.success(),
            "node --test jstest/ failed:\n{}",
            String::from_utf8_lossy(&out.stdout)
        ),
        Err(_) => eprintln!("node not installed - jstest/ skipped here (CI runs it)"),
    }
    for entry in std::fs::read_dir("templates").unwrap() {
        let path = entry.unwrap().path();
        if path.extension().is_some_and(|e| e == "html") {
            let text = std::fs::read_to_string(&path).unwrap();
            assert!(
                text.contains("{% include \"partials/head.html\" %}"),
                "{} lacks the head partial",
                path.display()
            );
        }
    }
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, _) = app.enrolled_device("phone").await;
    for page in [
        format!("/devices/{id}"),
        format!("/devices/{id}/calls"),
        "/schedules".to_string(),
        "/dns".to_string(),
        "/settings".to_string(),
    ] {
        let text = app.get_page(&page, &cookie).await.text();
        assert!(text.contains("/static/scroll-restore.js"), "{page}");
    }
}
