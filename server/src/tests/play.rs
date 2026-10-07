//! Handy step 7's Play parts (Play as an app source, the DNS exceptions for other apps' FCM, DNS
//! nudges), the SSE keepalive, and design 19: FCM is gone - an old launcher's `push` report is
//! accepted and dropped, `device_push` is gone, and the device page says whether the phone holds
//! the command stream.

use axum::body::Body;
use axum::extract::ConnectInfo;
use axum::http::{Method, Request, StatusCode, header};
use axum::response::IntoResponse;
use serde_json::json;
use tower::ServiceExt;

use super::TestApp;

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

async fn security_details(app: &TestApp, event_type: &str) -> Vec<String> {
    sqlx::query_scalar("SELECT detail FROM security_events WHERE event_type = ? ORDER BY id")
        .bind(event_type)
        .fetch_all(&app.db)
        .await
        .unwrap()
}

// ---------------------------------------------------------------------------------------------
// Design 19: no FCM
// ---------------------------------------------------------------------------------------------

/// A 0.19-era launcher still reports `push` (with its FCM installation ID) and `fcm_push_v1`:
/// the report is accepted, the `push` object is stored nowhere, and the policy has no `push`.
#[tokio::test]
async fn an_old_launchers_push_report_is_accepted_and_dropped() {
    let app = TestApp::new().await;
    let (id, token) = app.enrolled_device("phone").await;
    post_status(
        &app,
        &token,
        json!({
            "push": { "fcm_token": "dIsVQ2QVRT-nQyYvE0SNfx", "fcm_token_kind": "fid",
                      "transport": "fcm", "fcm_configured": true, "gms_available": true,
                      "last_nudge_id": "0123456789abcdef", "reason": null },
            "capabilities": ["call_policy_v1", "fcm_push_v1", "play_policy_v1"]
        }),
    )
    .await;
    let (push_json, caps): (Option<String>, Option<String>) = sqlx::query_as(
        "SELECT push_state_json, capabilities_json FROM device_status WHERE device_id = ? \
         ORDER BY id DESC LIMIT 1",
    )
    .bind(id)
    .fetch_one(&app.db)
    .await
    .unwrap();
    assert_eq!(push_json, None, "the push report is not stored");
    // The capability list is stored as reported; nothing reads fcm_push_v1.
    assert!(caps.unwrap().contains("call_policy_v1"));

    let policy = app
        .request(Method::GET, "/api/devices/policy", Some(&token), None)
        .await;
    assert_eq!(policy.status, StatusCode::OK);
    assert!(policy.json().get("push").is_none(), "no vestigial push");

    let page = device_page(&app, id).await;
    assert!(!page.contains("FCM"), "{page}");
    assert!(page.contains("Play and kiosk"), "{page}");
}

#[tokio::test]
async fn migration_0048_drops_device_push_and_clears_stored_push_reports() {
    let (db, _dir) = super::cleanup::migrated_before(48).await;
    let id: i64 = sqlx::query_scalar("INSERT INTO devices (name) VALUES ('old') RETURNING id")
        .fetch_one(&db)
        .await
        .unwrap();
    sqlx::query("INSERT INTO device_push (device_id, fcm_token, fcm_ok) VALUES (?, 'fid-1', 1)")
        .bind(id)
        .execute(&db)
        .await
        .unwrap();
    sqlx::query(
        "INSERT INTO device_status (device_id, push_state_json, app_version) \
         VALUES (?, '{\"fcm_token\":\"fid-1\"}', '0.24.0'), (?, NULL, '0.24.1')",
    )
    .bind(id)
    .bind(id)
    .execute(&db)
    .await
    .unwrap();
    super::cleanup::finish(&db).await;

    let tables: i64 =
        sqlx::query_scalar("SELECT COUNT(*) FROM sqlite_master WHERE name LIKE 'device_push%'")
            .fetch_one(&db)
            .await
            .unwrap();
    assert_eq!(tables, 0, "table and its unique index are gone");
    let rows: Vec<(Option<String>, String)> = sqlx::query_as(
        "SELECT push_state_json, app_version FROM device_status WHERE device_id = ? ORDER BY id",
    )
    .bind(id)
    .fetch_all(&db)
    .await
    .unwrap();
    assert_eq!(
        rows,
        vec![(None, "0.24.0".to_string()), (None, "0.24.1".to_string())],
        "the status rows stay, without the ID"
    );
}

/// Design 19 Q1: an open command stream is counted in memory while its response lives.
#[tokio::test]
async fn the_device_page_says_whether_the_phone_holds_the_stream() {
    let app = TestApp::new().await;
    let (id, token) = app.enrolled_device("phone").await;
    let page = device_page(&app, id).await;
    assert!(
        page.contains("Instant changes: not connected since this server started"),
        "{page}"
    );

    let mut request = Request::builder()
        .method(Method::GET)
        .uri("/api/devices/commands/stream")
        .header(header::AUTHORIZATION, format!("Bearer {token}"))
        .body(Body::empty())
        .unwrap();
    request
        .extensions_mut()
        .insert(ConnectInfo(std::net::SocketAddr::from((
            [127, 0, 0, 1],
            40000,
        ))));
    let response = app.router.clone().oneshot(request).await.unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    assert_eq!(
        response.headers()[header::CONTENT_TYPE],
        "text/event-stream",
        "the launcher's SSE client refuses anything else"
    );
    assert_eq!(app.state.command_streams.state(id).unwrap().open, 1);
    let page = device_page(&app, id).await;
    assert!(page.contains("Instant changes: connected since"), "{page}");
    assert!(page.contains("as far as this server can tell"), "{page}");

    drop(response);
    assert_eq!(app.state.command_streams.state(id).unwrap().open, 0);
    let page = device_page(&app, id).await;
    assert!(
        page.contains("Instant changes: not connected since 20"),
        "{page}"
    );
}

// ---------------------------------------------------------------------------------------------
// Status wiring
// ---------------------------------------------------------------------------------------------

#[tokio::test]
async fn dns_changes_nudge_the_devices() {
    let app = TestApp::new().await;
    let (id, _) = app.enrolled_device("phone").await;
    let mut rx = app.state.command_notify.subscribe();
    let cookie = app.admin_cookie().await;
    let res = app
        .request_form(
            Method::POST,
            "/dns/domains/new",
            Some(&cookie),
            &[("domain", "ads.example"), ("list_type", "block")],
        )
        .await;
    assert!(res.status.is_redirection(), "{}", res.text());
    assert_eq!(rx.try_recv().unwrap(), id);
    let res = app
        .request_form(
            Method::POST,
            "/dns/upstream",
            Some(&cookie),
            &[("upstream", "quad9")],
        )
        .await;
    assert!(res.status.is_redirection(), "{}", res.text());
    assert_eq!(rx.try_recv().unwrap(), id);
}

#[tokio::test]
async fn status_stores_install_mode_installer_and_logs() {
    let app = TestApp::new().await;
    let (id, token) = app.enrolled_device("phone").await;
    post_status(
        &app,
        &token,
        json!({ "installed_apps": [{ "package_name": "a", "label": "A", "preinstalled": true }] }),
    )
    .await;
    let until = chrono::Utc::now().timestamp_millis() + 15 * 60_000;
    post_status(
        &app,
        &token,
        json!({
            "installed_apps": [
                { "package_name": "a", "label": "A", "preinstalled": true },
                { "package_name": "com.example.game", "label": "Game", "preinstalled": false,
                  "installer": "com.android.vending" }
            ],
            "install_mode": { "until_ms": until },
            "play_window_active": false,
            "capabilities": ["play_policy_v1"]
        }),
    )
    .await;
    let (until_stored, apps): (Option<i64>, Option<String>) = sqlx::query_as(
        "SELECT install_mode_until_ms, installed_apps_json FROM device_status \
         WHERE device_id = ? ORDER BY id DESC LIMIT 1",
    )
    .bind(id)
    .fetch_one(&app.db)
    .await
    .unwrap();
    assert_eq!(until_stored, Some(until));
    assert!(
        apps.unwrap()
            .contains(r#""installer":"com.android.vending""#)
    );

    let installs = security_details(&app, "app_installed").await;
    assert_eq!(installs.len(), 1, "{installs:?}");
    assert!(
        installs[0].contains("com.example.game") && installs[0].contains("com.android.vending")
    );
    assert_eq!(security_details(&app, "play_install_mode").await.len(), 1);

    let page = device_page(&app, id).await;
    assert!(page.contains("Play install mode is on"), "{page}");
    assert!(page.contains("Installed from Play"), "{page}");

    // The same window again isn't logged twice.
    post_status(
        &app,
        &token,
        json!({ "install_mode": { "until_ms": until } }),
    )
    .await;
    assert_eq!(security_details(&app, "play_install_mode").await.len(), 1);
}

// ---------------------------------------------------------------------------------------------
// Play
// ---------------------------------------------------------------------------------------------

#[tokio::test]
async fn play_core_is_never_in_the_allowlist_ui() {
    let app = TestApp::new().await;
    let (id, token) = app.enrolled_device("phone").await;
    post_status(
        &app,
        &token,
        json!({ "installed_apps": [
            { "package_name": "com.android.vending", "label": "Play Store", "preinstalled": true },
            { "package_name": "com.google.android.gms", "label": "Play services", "preinstalled": true },
            { "package_name": "com.google.android.gsf", "label": "GSF", "preinstalled": true },
            { "package_name": "org.example.app", "label": "Example", "preinstalled": false }
        ] }),
    )
    .await;
    // The first-heartbeat bootstrap leaves them out.
    let allowlist: Option<String> =
        sqlx::query_scalar("SELECT allowlist_json FROM device_policy WHERE device_id = ?")
            .bind(id)
            .fetch_one(&app.db)
            .await
            .unwrap();
    assert_eq!(allowlist.as_deref(), Some(r#"["org.example.app"]"#));

    let page = device_page(&app, id).await;
    assert!(page.contains("org.example.app"));
    for pkg in crate::play::PLAY_CORE {
        assert!(!page.contains(&format!("value=\"{pkg}\"")), "{pkg} listed");
    }

    for pkg in crate::play::PLAY_CORE {
        for selected in [true, false] {
            let mut form = std::collections::HashMap::new();
            form.insert("package_name".to_string(), pkg.to_string());
            if selected {
                form.insert("selected".to_string(), "on".to_string());
            }
            let status = crate::handlers::devices::toggle_app(
                axum::extract::State(app.state.clone()),
                axum::extract::Path(id),
                axum::Form(form),
            )
            .await
            .status();
            assert_eq!(status, StatusCode::BAD_REQUEST);
        }
    }
    let allowlist: Option<String> =
        sqlx::query_scalar("SELECT allowlist_json FROM device_policy WHERE device_id = ?")
            .bind(id)
            .fetch_one(&app.db)
            .await
            .unwrap();
    assert_eq!(allowlist.as_deref(), Some(r#"["org.example.app"]"#));
    let pending: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM device_pending_uninstalls")
        .fetch_one(&app.db)
        .await
        .unwrap();
    assert_eq!(pending, 0);
}

#[tokio::test]
async fn catalog_app_installed_from_play_is_refused() {
    let app = TestApp::new().await;
    let (id, token) = app.enrolled_device("phone").await;
    let tracked: i64 = sqlx::query_scalar(
        "INSERT INTO tracked_apps (name, package_name, github_repo, source_type) \
         VALUES ('Game', 'com.example.game', '', 'manual') RETURNING id",
    )
    .fetch_one(&app.db)
    .await
    .unwrap();
    post_status(
        &app,
        &token,
        json!({ "installed_apps": [{ "package_name": "com.example.game", "label": "Game",
                                     "preinstalled": false, "installer": "com.android.vending" }] }),
    )
    .await;
    let select = |app: &TestApp| {
        let mut form = std::collections::HashMap::new();
        form.insert("package_name".to_string(), "com.example.game".to_string());
        form.insert("tracked_app_id".to_string(), tracked.to_string());
        form.insert("selected".to_string(), "on".to_string());
        crate::handlers::devices::toggle_app(
            axum::extract::State(app.state.clone()),
            axum::extract::Path(id),
            axum::Form(form),
        )
    };
    let res = super::read_response(select(&app).await).await;
    assert_eq!(res.status, StatusCode::CONFLICT);
    assert!(res.text().contains("Play Store"));
    let selected: i64 =
        sqlx::query_scalar("SELECT COUNT(*) FROM device_tracked_apps WHERE device_id = ?")
            .bind(id)
            .fetch_one(&app.db)
            .await
            .unwrap();
    assert_eq!(selected, 0, "nothing written");

    // From another source it's fine.
    post_status(
        &app,
        &token,
        json!({ "installed_apps": [{ "package_name": "com.example.game", "label": "Game",
                                     "preinstalled": false, "installer": "me.vibb.launcher" }] }),
    )
    .await;
    assert!(select(&app).await.status().is_redirection());
}

// ---------------------------------------------------------------------------------------------
// SSE and DNS
// ---------------------------------------------------------------------------------------------

#[test]
fn sse_keepalive_defaults_and_validates() {
    use crate::config::ForkConfig;
    let vars = |v: &str| {
        std::collections::HashMap::from([("SSE_KEEPALIVE_SECS".to_string(), v.to_string())])
    };
    assert_eq!(
        ForkConfig::from_vars(&Default::default()).sse_keepalive_secs,
        120
    );
    assert_eq!(ForkConfig::from_vars(&vars("30")).sse_keepalive_secs, 30);
    assert_eq!(ForkConfig::from_vars(&vars("1")).sse_keepalive_secs, 120);
    // Must stay well under the launcher's 300 s read timeout (QA step 7 #4).
    assert_eq!(ForkConfig::from_vars(&vars("240")).sse_keepalive_secs, 240);
    assert_eq!(ForkConfig::from_vars(&vars("300")).sse_keepalive_secs, 120);
    assert_eq!(ForkConfig::from_vars(&vars("3600")).sse_keepalive_secs, 120);
    assert_eq!(ForkConfig::from_vars(&vars("abc")).sse_keepalive_secs, 120);
}

/// Other apps' FCM (Element X) needs these hosts; our own nudges no longer do (design 19).
#[tokio::test]
async fn dns_blocklist_never_blocks_fcm() {
    let app = TestApp::new().await;
    let (_, token) = app.enrolled_device("phone").await;
    {
        let mut compiled = app.state.dns_compiled.write().await;
        compiled.global_custom_block = ["mtalk.google.com", "googleapis.com", "ads.example"]
            .into_iter()
            .map(String::from)
            .collect();
    }
    let res = app
        .request(
            Method::GET,
            "/api/devices/dns-blocklist",
            Some(&token),
            None,
        )
        .await;
    assert_eq!(res.status, StatusCode::OK);
    let body = res.text();
    assert!(body.contains("ads.example"), "{body}");
    // The exact FCM host is dropped; a parent the parent blocked is still delivered (QA #1).
    assert!(!body.contains("mtalk"), "{body}");
    assert!(body.contains("\"googleapis.com\""), "{body}");
}
