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
        // Design 68: a lost list would hand the camera back.
        "device_app_permissions",
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
/// fails the whole decode). No `push` since design 19: every shipped launcher treats a missing
/// `push` like `fcm_enabled: false` (SSE), so no stub is sent.
#[tokio::test]
async fn policy_json_keys_snapshot() {
    let app = TestApp::new().await;
    let (device_id, token) = app.enrolled_device("phone").await;
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
            "app_permissions",
            "app_updates_wifi_only",
            "bedtime_end_minutes",
            "bedtime_start_minutes",
            "block_activity_start",
            "boot_cover",
            "calendar",
            "call_policy",
            "dns_filter_version",
            "dns_log_enabled",
            "dns_upstream",
            "dns_upstream_provider",
            "hardening",
            "kid_lock",
            "kiosk_desired",
            "launcher_ui",
            "location_policy",
            "lock_glance",
            "lock_task_features",
            "music",
            "notification_auto_cancel",
            "override_pin_hash",
            "override_pin_salt",
            "packages_to_uninstall",
            "pending_command",
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
        "app_permissions",
        "app_updates_wifi_only",
        "block_activity_start",
        "calendar",
        "call_policy",
        "dns_filter_version",
        "dns_log_enabled",
        "dns_upstream_provider",
        "hardening",
        "kiosk_desired",
        "launcher_ui",
        "location_policy",
        "lock_glance",
        "lock_task_features",
        "music",
        "notification_auto_cancel",
        "packages_to_uninstall",
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

    // Design 36: `dns_upstream` is null until Custom has a valid pair, then the launcher's
    // `DnsUpstream` (a target, `rev`, `backup`) and `DnsTarget` shapes.
    assert!(object["dns_upstream"].is_null());
    sqlx::query(
        "UPDATE dns_filter_settings SET upstream = 'custom', \
         custom_server = 'https://dns.example/dns-query', custom_backup = 'tls://1.1.1.3'",
    )
    .execute(&app.db)
    .await
    .unwrap();
    let custom = app
        .request(Method::GET, "/api/devices/policy", Some(&token), None)
        .await
        .json();
    let upstream = custom["dns_upstream"].as_object().unwrap();
    let mut upstream_keys: Vec<&str> = upstream.keys().map(String::as_str).collect();
    upstream_keys.sort_unstable();
    assert_eq!(upstream_keys, ["backup", "kind", "rev", "url"]);
    let mut backup_keys: Vec<&str> = upstream["backup"]
        .as_object()
        .unwrap()
        .keys()
        .map(String::as_str)
        .collect();
    backup_keys.sort_unstable();
    assert_eq!(backup_keys, ["host", "kind", "port"]);

    // The launcher's LauncherUi DTO (steps 5 and 8): every key, wallpapers always a list.
    let launcher_ui = object["launcher_ui"].as_object().unwrap();
    let mut ui_keys: Vec<&str> = launcher_ui.keys().map(String::as_str).collect();
    ui_keys.sort_unstable();
    assert_eq!(
        ui_keys,
        [
            "app_display",
            "clock_format",
            "home_columns",
            "language",
            "lock_wake_on_notification",
            "lock_wallpaper",
            "wallpapers"
        ]
    );
    // Launcher round 7 §3: always sent, off for a new phone (the launcher reads a missing key as off).
    assert_eq!(
        launcher_ui["lock_wake_on_notification"],
        serde_json::json!(false)
    );
    // Design 25, "Lock background": always sent, null = the lock shows Home's wallpaper.
    assert_eq!(launcher_ui["lock_wallpaper"], serde_json::json!(null));
    // Design 14 (QA #1): always a list, even when nothing is named.
    assert_eq!(launcher_ui["app_display"], serde_json::json!([]));
    assert!(
        launcher_ui["wallpapers"]
            .as_array()
            .is_some_and(|w| !w.is_empty())
    );

    // The launcher's TimePolicy / LocationPolicy DTOs (handy step 6); design 64 step 2's `zones`
    // always the key (`[]` unless Regularly with zones; a zone's keys in
    // `zone_watch::the_policy_serves_the_zones_and_the_floor`).
    let time_policy = object["time_policy"].as_object().unwrap();
    let mut time_keys: Vec<&str> = time_policy.keys().map(String::as_str).collect();
    time_keys.sort_unstable();
    assert_eq!(time_keys, ["daily_budget_minutes", "lifts", "rules"]);
    assert_eq!(
        object["location_policy"],
        serde_json::json!({"mode": "on_request", "interval_minutes": 30, "zones": []})
    );
    let mut location_keys: Vec<&str> = object["location_policy"]
        .as_object()
        .unwrap()
        .keys()
        .map(String::as_str)
        .collect();
    location_keys.sort_unstable();
    assert_eq!(location_keys, ["interval_minutes", "mode", "zones"]);

    // The lock's glance page (design 25): always an object, a fresh phone's default - Element X,
    // the sender only (per app since design 51). The launcher's `lockGlancePolicy` decodes exactly
    // this shape (`PolicyResponseCompatTest`).
    assert_eq!(
        object["lock_glance"],
        serde_json::json!({ "apps": [{ "package": "io.element.android.x", "show_text": false }] })
    );

    // Dagen (design 69 §4.1): always an object, a fresh phone's defaults - on Home, the kid may add events (to the
    // phone's own calendar), nothing on the lock, no calendars saved, no rules. The launcher's `calendarPolicy`
    // reads exactly this shape (`PolicyResponseCompatTest`); a rule's and a calendar's keys in
    // `calendar::the_policy_carries_the_rules_and_the_calendars`.
    assert_eq!(
        object["calendar"],
        serde_json::json!({
            "on_home": true,
            "kid_adds": true,
            "glance_next": false,
            "lock_reminders": "off",
            "calendars": [],
            "rules": []
        })
    );

    // The launcher's (nullable, step 1: ignored) music DTO (design 21): every key, a fresh phone's
    // defaults; only `library_version`, `volume_cap_pct` and `storage_limit_gb` may be null. `null`
    // as a whole only when it can't be read (tests/music.rs). Design 30: `mobile_data` stays (both
    // switches on), the two switches and the storage limit (a fresh phone's 2 GB) are added. Design
    // 26c: `spotify` (bound to an account; the launcher's DNS exemption).
    assert_eq!(
        object["music"],
        serde_json::json!({
            "library_version": null,
            "mobile_data": false,
            "stream_mobile_data": false,
            "download_mobile_data": false,
            "volume_cap_pct": null,
            "storage_limit_gb": 2,
            "storytel_generation": 0,
            "spotify": false
        })
    );

    // Design 68: always a list (`[]` for a fresh phone), each entry the launcher's `appPermissionsOf` shape.
    assert_eq!(object["app_permissions"], serde_json::json!([]));
    sqlx::query(
        "INSERT INTO device_app_permissions (device_id, package_name, camera) VALUES (?, 'com.whatsapp', 'deny')",
    )
    .bind(device_id)
    .execute(&app.db)
    .await
    .unwrap();
    let denied = app
        .request(Method::GET, "/api/devices/policy", Some(&token), None)
        .await
        .json();
    assert_eq!(
        denied["app_permissions"],
        serde_json::json!([{"package_name": "com.whatsapp", "camera": "deny"}])
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
/// and the notification filter on (2026-10-21), and its policy says so; a device that existed
/// before keeps what it had.
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
    let id: i64 = sqlx::query_scalar("SELECT id FROM devices WHERE name = 'new'")
        .fetch_one(&app.db)
        .await
        .unwrap();
    let code = app.new_code(id, crate::enrollment::Kind::Typed).await;
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
    // The notification filter starts on for a new phone (2026-10-21); an existing one keeps its
    // stored value.
    let filter = |device: i64| {
        sqlx::query_scalar::<_, bool>(
            "SELECT notification_auto_cancel FROM device_policy WHERE device_id = ?",
        )
        .bind(device)
        .fetch_one(&app.db)
    };
    assert!(filter(id).await.unwrap());
    assert!(!filter(existing).await.unwrap());

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
    assert_eq!(policy.json()["notification_auto_cancel"], true);
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

async fn device_page(app: &TestApp, id: i64) -> String {
    let response = crate::handlers::devices::view_device(
        axum::extract::State(app.state.clone()),
        axum::Extension(crate::security::CurrentAdmin(app.an_admin().await)),
        crate::i18n::Lang::En,
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
    app.post_status(
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

    // The device page: an Attention row; About This Phone: the problem and what it means (design
    // 31 step 3).
    let page = device_page(&app, id).await;
    assert!(
        page.contains("Phone can&#39;t apply its settings"),
        "{page}"
    );
    assert!(
        page.contains("data-attention=\"restrictions-paused\""),
        "{page}"
    );
    let about = about_page(&app, id).await;
    assert!(about.contains("Saved settings unreadable"), "{about}");
    assert!(about.contains("read its saved settings"), "{about}");
}

async fn about_page(app: &TestApp, id: i64) -> String {
    let response = crate::handlers::device_pages::view_about(
        axum::extract::State(app.state.clone()),
        axum::extract::Path(id),
    )
    .await;
    super::read_response(response).await.text()
}

#[tokio::test]
async fn status_report_from_older_launcher_has_no_warnings() {
    let app = TestApp::new().await;
    let (id, token) = app.enrolled_device("phone").await;
    app.post_status(&token, json!({})).await;

    let (state, paused): (Option<String>, bool) = sqlx::query_as(
        "SELECT policy_state, restrictions_paused FROM device_status WHERE device_id = ?",
    )
    .bind(id)
    .fetch_one(&app.db)
    .await
    .unwrap();
    assert_eq!(state, None);
    assert!(!paused);

    app.post_status(&token, json!({ "policy_state": "ok" }))
        .await;
    let page = device_page(&app, id).await;
    assert!(!page.contains("apply its settings"));
    assert!(!page.contains("All restrictions are paused"));
    let about = about_page(&app, id).await;
    assert!(about.contains(">Applied</span>"), "{about}");
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
    set_allowlist_json(&app, id, Some(r#"["org.example.a"]"#)).await;

    assert!(
        toggle(&app, id, "org.example.b", true)
            .await
            .is_redirection()
    );
    assert_eq!(
        allowlist_json(&app, id).await.as_deref(),
        Some(r#"["org.example.a","org.example.b"]"#)
    );
    assert!(
        toggle(&app, id, "org.example.a", false)
            .await
            .is_redirection()
    );
    assert_eq!(
        allowlist_json(&app, id).await.as_deref(),
        Some(r#"["org.example.b"]"#)
    );
}

/// A corrupt stored allowlist used to be read as `[]`: unchecking then silently did nothing (the
/// app stayed allowed) and checking overwrote the list.
#[tokio::test]
async fn toggle_app_with_corrupt_allowlist_fails_without_writing() {
    let app = TestApp::new().await;
    let (id, _) = app.enrolled_device("phone").await;
    set_allowlist_json(&app, id, Some("not json")).await;

    assert_eq!(
        toggle(&app, id, "org.example.a", false).await,
        StatusCode::INTERNAL_SERVER_ERROR
    );
    assert_eq!(
        toggle(&app, id, "org.example.a", true).await,
        StatusCode::INTERNAL_SERVER_ERROR
    );
    assert_eq!(allowlist_json(&app, id).await.as_deref(), Some("not json"));
}

#[tokio::test]
async fn heartbeat_bootstraps_only_a_null_allowlist() {
    let app = TestApp::new().await;
    let (id, token) = app.enrolled_device("phone").await;
    // Only the safe defaults among the installed apps (security review M10).
    let apps = json!({ "installed_apps": [
        { "package_name": "a", "label": "A" },
        { "package_name": "com.android.deskclock", "label": "Clock" },
    ] });

    app.post_status(&token, apps.clone()).await;
    assert_eq!(
        allowlist_json(&app, id).await.as_deref(),
        Some(r#"["com.android.deskclock"]"#)
    );

    set_allowlist_json(&app, id, Some("[]")).await;
    app.post_status(&token, apps).await;
    assert_eq!(allowlist_json(&app, id).await.as_deref(), Some("[]"));
}

#[tokio::test]
async fn server_error_policy_state_is_shown() {
    let app = TestApp::new().await;
    let (id, token) = app.enrolled_device("phone").await;
    app.post_status(&token, json!({ "policy_state": "server_error" }))
        .await;
    let about = about_page(&app, id).await;
    assert!(about.contains(">Server error</span>"), "{about}");
    assert!(
        about.contains("This server answered with an error"),
        "{about}"
    );
    assert!(about.contains("failed to build device policy"), "{about}");
}

async fn timeout(app: &TestApp, token: String) -> serde_json::Value {
    app.request(Method::GET, "/api/devices/policy", Some(&token), None)
        .await
        .json()["screen_timeout_seconds"]
        .clone()
}

/// Auto-lock (migrations/0032, emulator run 2026-10-06): the parent's screen timeout reaches the
/// policy, only the offered values are accepted, and the phone's applied value is shown when it
/// differs (Lock Screen & PINs › Screen, design 31 step 4).
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
    // Back to its group (§5.1.4): scroll-restore.js keeps the place over it.
    super::assert_redirect(
        &res,
        &super::device_page(id, super::Section::Lock),
        Some("screen"),
    );
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

    let lock = || async {
        app.get_page(&super::device_page(id, super::Section::Lock), &cookie)
            .await
            .text()
    };
    let page = lock().await;
    assert_eq!(
        super::form_action_of(&page, "screen_timeout_seconds"),
        Some(format!("/devices/{id}/screen-timeout#screen").as_str())
    );
    assert!(super::autosaves(&page, "screen_timeout_seconds"));
    assert!(page.contains("<option value=\"60\" selected>1 minute</option>"));
    assert!(!page.contains("data-row=\"timeout-applied\""));
    // The phone runs another value (it takes the new one at its next sync): shown.
    app.post_status(&token, json!({ "screen_timeout_seconds": 120 }))
        .await;
    let page = lock().await;
    let applied = &page[page.find("data-row=\"timeout-applied\"").expect("the row")..];
    assert!(
        applied.contains("<span class=\"row-value\">2 minutes</span>"),
        "{page}"
    );
    // The same value: nothing to say.
    app.post_status(&token, json!({ "screen_timeout_seconds": 60 }))
        .await;
    assert!(!lock().await.contains("timeout-applied"));
    // An implausible report isn't stored.
    app.post_status(&token, json!({ "screen_timeout_seconds": -5 }))
        .await;
    assert!(!lock().await.contains("timeout-applied"));
}

/// Every page template under `templates/`, subfolders included; `partials/` holds pieces of
/// pages, not pages.
pub(super) fn template_files() -> Vec<std::path::PathBuf> {
    template_files_in(std::path::Path::new("templates"))
}

/// The page templates under `root` (see `template_files`).
pub(super) fn template_files_in(root: &std::path::Path) -> Vec<std::path::PathBuf> {
    fn walk(dir: &std::path::Path, found: &mut Vec<std::path::PathBuf>) {
        for entry in std::fs::read_dir(dir).unwrap() {
            let path = entry.unwrap().path();
            if path.is_dir() {
                if path.file_name().is_some_and(|n| n != "partials") {
                    walk(&path, found);
                }
            } else if path.extension().is_some_and(|e| e == "html") {
                found.push(path);
            }
        }
    }
    let mut found = Vec::new();
    walk(root, &mut found);
    found.sort();
    found
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
    // The test files are named explicitly: Node 22's `--test <dir>` loads the directory as a
    // module instead of searching it (Node 20 searched it), and globs need Node 21+.
    let mut js_tests: Vec<String> = std::fs::read_dir("jstest")
        .unwrap()
        .map(|e| e.unwrap().path().to_string_lossy().into_owned())
        .filter(|p| p.ends_with(".test.js"))
        .collect();
    js_tests.sort();
    assert!(!js_tests.is_empty(), "no jstest/*.test.js files");
    match std::process::Command::new("node")
        .arg("--test")
        .args(&js_tests)
        .output()
    {
        Ok(out) => assert!(
            out.status.success(),
            "node --test {js_tests:?} failed:\n{}",
            String::from_utf8_lossy(&out.stdout)
        ),
        Err(_) => eprintln!("node not installed - jstest/ skipped here (CI runs it)"),
    }
    // Every page template, in subfolders too (design 31 M8: a `templates/device/` page can't skip
    // the head partial and its scroll restore).
    let pages = template_files();
    assert!(pages.len() >= 27, "{} page templates", pages.len());
    for path in pages {
        let text = std::fs::read_to_string(&path).unwrap();
        assert!(
            text.contains("{% include \"partials/head.html\" %}"),
            "{} lacks the head partial",
            path.display()
        );
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
