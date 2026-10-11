//! Design 24b: the phone's `relock` report, the Status card's warnings and buttons, and the two
//! commands with their own insert.

use axum::http::{Method, StatusCode};
use serde_json::{Value, json};

use super::TestApp;

const CAP: &str = "window_relock_v1";

/// Design 24b §1.7's literal, as the launcher sends it.
fn literal(at_ms: i64) -> Value {
    json!({ "at_ms": at_ms, "trigger": "timer", "windows": ["override"],
            "proxies_cleared": 1, "proxy_left": false, "ca_certs_removed": 0,
            "credential_removed": false, "credential_left": false,
            "blocked": ["com.example.app"], "accessibility_enabled": [], "admins_enabled": [],
            "apps_disabled": [], "notification_listeners": [], "own_notifications_off": false,
            "failures": [] })
}

fn now_ms() -> i64 {
    chrono::Utc::now().timestamp_millis()
}

async fn post_status(app: &TestApp, token: &str, extra: Value) {
    let mut body = json!({ "lock_reason": "NONE", "kiosk_engaged": true, "capabilities": [CAP] });
    for (key, value) in extra.as_object().unwrap() {
        body[key] = value.clone();
    }
    let res = app
        .request(Method::POST, "/api/devices/status", Some(token), Some(body))
        .await;
    assert_eq!(res.status, StatusCode::NO_CONTENT);
}

async fn events(app: &TestApp, event_type: &str) -> Vec<String> {
    sqlx::query_scalar("SELECT detail FROM security_events WHERE event_type = ? ORDER BY id")
        .bind(event_type)
        .fetch_all(&app.db)
        .await
        .unwrap()
}

async fn commands(app: &TestApp, id: i64) -> Vec<String> {
    sqlx::query_scalar(
        "SELECT command FROM device_commands WHERE device_id = ? AND delivered_at IS NULL ORDER BY id",
    )
    .bind(id)
    .fetch_all(&app.db)
    .await
    .unwrap()
}

/// The Status card's HTML: from its heading to the next card.
fn status_card(html: &str) -> &str {
    let start = html.find("<h2>Status</h2>").expect("status card");
    let end = html[start..]
        .find("id=\"access\"")
        .map_or(html.len(), |e| start + e);
    &html[start..end]
}

#[tokio::test]
async fn the_literal_is_stored_and_logged_once_per_relock() {
    let app = TestApp::new().await;
    let (id, token) = app.enrolled_device("phone").await;
    let at = now_ms() - 60_000;
    post_status(&app, &token, json!({ "relock": literal(at) })).await;
    let stored: Option<String> = sqlx::query_scalar(
        "SELECT relock_json FROM device_status WHERE device_id = ? ORDER BY id DESC LIMIT 1",
    )
    .bind(id)
    .fetch_one(&app.db)
    .await
    .unwrap();
    let stored: Value = serde_json::from_str(&stored.unwrap()).unwrap();
    assert_eq!(stored, literal(at));
    let logged = events(&app, "phone_relock_findings").await;
    assert_eq!(logged.len(), 1);
    assert!(
        logged[0].starts_with(&format!("device {id}, ")),
        "{}",
        logged[0]
    );
    assert!(
        logged[0].ends_with(
            "locked again (timer) - Wi-Fi proxies removed: 1; blocked: com.example.app."
        ),
        "{}",
        logged[0]
    );

    // The same relock in every later report: no new row; a newer one logs again.
    post_status(&app, &token, json!({ "relock": literal(at) })).await;
    assert_eq!(events(&app, "phone_relock_findings").await.len(), 1);
    post_status(&app, &token, json!({ "relock": literal(at + 1) })).await;
    assert_eq!(events(&app, "phone_relock_findings").await.len(), 2);
    // A relock that found nothing logs nothing.
    let mut quiet = literal(at + 2);
    quiet["proxies_cleared"] = json!(0);
    quiet["blocked"] = json!([]);
    post_status(&app, &token, json!({ "relock": quiet })).await;
    assert_eq!(events(&app, "phone_relock_findings").await.len(), 2);
    // Junk is dropped, the rest of the report kept.
    post_status(&app, &token, json!({ "relock": "<script>" })).await;
    let latest: (Option<String>, String) = sqlx::query_as(
        "SELECT relock_json, lock_reason FROM device_status WHERE device_id = ? ORDER BY id DESC LIMIT 1",
    )
    .bind(id)
    .fetch_one(&app.db)
    .await
    .unwrap();
    assert_eq!(latest, (None, "NONE".to_string()));
}

#[tokio::test]
async fn the_warnings_appear_on_the_status_card_and_expire_after_a_week() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("phone").await;
    sqlx::query(
        "UPDATE device_policy SET allowlist_json = '[\"com.example.app\"]' WHERE device_id = ?",
    )
    .bind(id)
    .execute(&app.db)
    .await
    .unwrap();
    let mut relock = literal(now_ms() - 3_600_000);
    relock["accessibility_enabled"] = json!(["com.evil.ally"]);
    relock["apps_disabled"] = json!(["com.android.inputmethod.latin"]);
    relock["own_notifications_off"] = json!(true);
    post_status(&app, &token, json!({ "relock": relock })).await;
    let html = app
        .get_page(&format!("/devices/{id}"), &cookie)
        .await
        .text();
    let card = status_card(&html);
    assert!(card.contains("it removed 1 Wi-Fi proxy."), "{card}");
    assert!(
        card.contains("Blocked: com.example.app (installed or replaced while it was open)."),
        "{card}"
    );
    assert!(
        card.contains("Still on: accessibility service com.evil.ally."),
        "{card}"
    );
    assert!(
        card.contains(
            "Turned off and can&#39;t be turned back on remotely: com.android.inputmethod.latin"
        ),
        "{card}"
    );
    assert!(
        card.contains("The launcher&#39;s notifications are off."),
        "{card}"
    );
    assert!(
        card.contains(&format!("action=\"/devices/{id}/allow-blocked\"")),
        "{card}"
    );

    // Older than a week: nothing.
    post_status(
        &app,
        &token,
        json!({ "relock": literal(now_ms() - 8 * 24 * 3_600_000) }),
    )
    .await;
    let html = app
        .get_page(&format!("/devices/{id}"), &cookie)
        .await
        .text();
    assert!(!html.contains("Wi-Fi proxy"), "{html}");
    assert!(!html.contains("allow-blocked"), "{html}");
}

#[tokio::test]
async fn lock_again_follows_the_status_and_the_capability() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("phone").await;
    async fn page(app: &TestApp, id: i64, cookie: &str) -> String {
        app.get_page(&format!("/devices/{id}"), cookie).await.text()
    }
    let button = format!("action=\"/devices/{id}/end-windows\"");

    // Nothing open: no button.
    post_status(&app, &token, json!({})).await;
    assert!(!page(&app, id, &cookie).await.contains(&button));
    // A pause: the button, and the pause line names it.
    post_status(&app, &token, json!({ "restrictions_paused": true })).await;
    let html = page(&app, id, &cookie).await;
    assert!(status_card(&html).contains(&button), "{html}");
    assert!(
        html.contains("The pause ends by itself, or press \"Lock the phone again now\"."),
        "{html}"
    );
    // Play install mode running.
    post_status(
        &app,
        &token,
        json!({ "install_mode": { "until_ms": now_ms() + 600_000 } }),
    )
    .await;
    assert!(page(&app, id, &cookie).await.contains(&button));
    // Install mode that has ended.
    post_status(
        &app,
        &token,
        json!({ "install_mode": { "until_ms": now_ms() - 600_000 } }),
    )
    .await;
    assert!(!page(&app, id, &cookie).await.contains(&button));
    // An older launcher: no button, the old pause line.
    let res = app
        .request(
            Method::POST,
            "/api/devices/status",
            Some(&token),
            Some(
                json!({ "lock_reason": "NONE", "kiosk_engaged": true, "restrictions_paused": true,
                         "capabilities": ["pin_lock_v1"] }),
            ),
        )
        .await;
    assert_eq!(res.status, StatusCode::NO_CONTENT);
    let html = page(&app, id, &cookie).await;
    assert!(!html.contains(&button));
    assert!(html.contains("The pause ends by itself.</div>"), "{html}");
}

#[tokio::test]
async fn both_commands_are_queued_logged_and_kept_by_other_commands() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, _token) = app.enrolled_device("phone").await;
    let mut nudges = app.state.command_notify.subscribe();

    for (path, command, notice) in [
        ("end-windows", "end_windows", "end_windows_sent"),
        ("allow-blocked", "allow_blocked", "allow_blocked_sent"),
    ] {
        let res = app
            .request_form(
                Method::POST,
                &format!("/devices/{id}/{path}"),
                Some(&cookie),
                &[],
            )
            .await;
        assert_eq!(res.status, StatusCode::SEE_OTHER, "{}", res.text());
        // No #fragment: scroll-restore.js keeps the place.
        assert_eq!(
            res.location(),
            Some(format!("/devices/{id}?notice={notice}").as_str())
        );
        assert_eq!(nudges.try_recv().ok(), Some(id));
        let logged = events(&app, "device_command_queued").await;
        assert_eq!(logged.last().map(String::as_str), Some(command));
    }
    // Neither deleted the other.
    assert_eq!(
        commands(&app, id).await,
        vec!["end_windows", "allow_blocked"]
    );
    // A lock queued after them replaces an undelivered ring, but not them.
    for path in ["ring", "lock"] {
        let res = app
            .request_form(
                Method::POST,
                &format!("/devices/{id}/command/{path}"),
                Some(&cookie),
                &[],
            )
            .await;
        assert_eq!(res.status, StatusCode::SEE_OTHER);
    }
    assert_eq!(
        commands(&app, id).await,
        vec!["end_windows", "allow_blocked", "lock"]
    );
    // And they delete nothing: an end_windows behind the lock keeps it.
    app.request_form(
        Method::POST,
        &format!("/devices/{id}/end-windows"),
        Some(&cookie),
        &[],
    )
    .await;
    assert_eq!(
        commands(&app, id).await,
        vec!["end_windows", "allow_blocked", "lock", "end_windows"]
    );

    // The notice renders inside the Status card.
    let html = app
        .get_page(&format!("/devices/{id}?notice=end_windows_sent"), &cookie)
        .await
        .text();
    assert!(
        status_card(&html).contains("Sent: the phone locks again as soon as it gets this"),
        "{html}"
    );
    // Unknown device: 404, nothing queued.
    let res = app
        .request_form(
            Method::POST,
            "/devices/9999/end-windows",
            Some(&cookie),
            &[],
        )
        .await;
    assert_eq!(res.status, StatusCode::NOT_FOUND);
}

#[tokio::test]
async fn the_phone_gets_them_one_by_one_oldest_first() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("phone").await;
    app.request_form(
        Method::POST,
        &format!("/devices/{id}/end-windows"),
        Some(&cookie),
        &[],
    )
    .await;
    app.request_form(
        Method::POST,
        &format!("/devices/{id}/command/lock"),
        Some(&cookie),
        &[],
    )
    .await;
    for expected in ["end_windows", "lock"] {
        let res = app
            .request(Method::GET, "/api/devices/policy", Some(&token), None)
            .await;
        assert_eq!(res.status, StatusCode::OK);
        assert_eq!(res.json()["pending_command"]["command"], expected);
    }
}

#[tokio::test]
async fn unauthenticated_posts_queue_nothing() {
    let app = TestApp::new().await;
    let (id, _token) = app.enrolled_device("phone").await;
    for path in ["end-windows", "allow-blocked"] {
        let res = app
            .request_form(Method::POST, &format!("/devices/{id}/{path}"), None, &[])
            .await;
        // The session gate sends a browser to the login page; nothing runs.
        assert!(
            !res.location().unwrap_or_default().contains("notice"),
            "{:?}",
            res.location()
        );
    }
    assert!(commands(&app, id).await.is_empty());
}

#[tokio::test]
async fn do_not_disturb_is_a_warning_on_the_status_card() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("phone").await;
    post_status(&app, &token, json!({ "interruption_filter": "alarms" })).await;
    let html = app
        .get_page(&format!("/devices/{id}"), &cookie)
        .await
        .text();
    assert!(
        status_card(&html)
            .contains("<p class=\"error\">Do Not Disturb at the last sync: on (alarms only)"),
        "{html}"
    );
    post_status(
        &app,
        &token,
        json!({ "interruption_filter": "all", "ringer_mode": "normal" }),
    )
    .await;
    let html = app
        .get_page(&format!("/devices/{id}"), &cookie)
        .await
        .text();
    assert!(!html.contains("Do Not Disturb at the last sync"), "{html}");
    assert!(
        html.contains("<p>Sound at the last sync: on.</p>"),
        "{html}"
    );
}
