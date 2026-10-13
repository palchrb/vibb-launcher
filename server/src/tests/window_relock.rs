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
            "blocked": ["com.example.app"],
            "blocked_signers": {"com.example.app": "3f2a00000000000000000000000000000000000000000000000000000000c0de"},
            "accessibility_enabled": [], "admins_enabled": [],
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
        card.contains("Blocked: com.example.app (signing key 3f2a0000…0000c0de) - installed or replaced from outside Play"),
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

/// Fix round QA Medium 2: a blocked app signed with another key than the catalog pinned stays
/// blocked - the page says why and offers no "Allow these apps" for it.
#[tokio::test]
async fn a_blocked_app_with_another_key_than_the_catalogs_is_called_out() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("phone").await;
    sqlx::query(
        "INSERT INTO tracked_apps (name, source_type, github_repo, package_name, signer_sha256) \
         VALUES ('Example', 'manual', '', 'com.example.app', ?)",
    )
    .bind("9".repeat(64))
    .execute(&app.db)
    .await
    .unwrap();
    post_status(
        &app,
        &token,
        json!({ "relock": literal(now_ms() - 60_000) }),
    )
    .await;
    let html = app
        .get_page(&format!("/devices/{id}"), &cookie)
        .await
        .text();
    let card = status_card(&html);
    assert!(
        card.contains("com.example.app is signed with another key than the catalog&#39;s (99999999…99999999), so it stays blocked: uninstall it."),
        "{card}"
    );
    assert!(!card.contains("allow-blocked"), "{card}");
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
async fn the_commands_are_queued_logged_and_kept_by_other_commands() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, _token) = app.enrolled_device("phone").await;
    let mut nudges = app.state.command_notify.subscribe();

    let allow = [("blocks", "org.example.app:")];
    for (path, command, notice, fields, logged_as) in [
        (
            "end-windows",
            "end_windows",
            "end_windows_sent",
            &[][..],
            "end_windows",
        ),
        (
            "allow-blocked",
            "allow_blocked",
            "allow_blocked_sent",
            &allow[..],
            "allow_blocked: org.example.app",
        ),
        (
            "start-pause",
            "start_pause",
            "start_pause_sent",
            &[][..],
            "start_pause",
        ),
    ] {
        let res = app
            .request_form(
                Method::POST,
                &format!("/devices/{id}/{path}"),
                Some(&cookie),
                fields,
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
        assert_eq!(
            logged.last().map(String::as_str),
            Some(logged_as),
            "{command}"
        );
    }
    // None deleted another.
    assert_eq!(
        commands(&app, id).await,
        vec!["end_windows", "allow_blocked", "start_pause"]
    );
    // A lock queued after them replaces nothing - not them, and not an undelivered ring (QA of
    // cmd-expiry, Medium 1: only a command of the same kind replaces one).
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
        vec![
            "end_windows",
            "allow_blocked",
            "start_pause",
            "ring",
            "lock"
        ]
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
        vec![
            "end_windows",
            "allow_blocked",
            "start_pause",
            "ring",
            "lock",
            "end_windows"
        ]
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

/// Fix of 2026-10-12: a block the install check set outside any window (no relock report carries
/// it) reaches the page with its key and why, "Allow these apps" lets it out, and the next report
/// clears it; a catalog imposter stays, with no button for it.
#[tokio::test]
async fn an_install_time_block_is_listed_allowed_and_cleared() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("phone").await;
    let key = format!("d3b0{}6a6e", "0".repeat(56));
    let music = json!({ "package_name": "me.vibb.music.debug", "signer": key,
                        "reason": "outside_play", "found": "install", "at_ms": now_ms() - 60_000 });
    post_status(&app, &token, json!({ "relock_blocks": [music.clone()] })).await;
    let button = format!("action=\"/devices/{id}/allow-blocked\"");
    let html = app
        .get_page(&format!("/devices/{id}"), &cookie)
        .await
        .text();
    let card = status_card(&html);
    assert!(
        card.contains(
            "me.vibb.music.debug (signing key d3b00000…00006a6e): installed from outside Play \
             (a download or adb) - noticed at its install"
        ),
        "{card}"
    );
    assert!(card.contains(&button), "{card}");
    assert!(!card.contains("more - the phone lists at most"), "{card}");
    // More than the phone lists (QA of cmd-expiry, Low 2): said under the list.
    post_status(
        &app,
        &token,
        json!({ "relock_blocks": [music.clone()], "relock_blocks_total": 18,
                "installed_apps": [{ "package_name": "me.vibb.music.debug", "label": "Music" }] }),
    )
    .await;
    let html = app
        .get_page(&format!("/devices/{id}"), &cookie)
        .await
        .text();
    assert!(
        status_card(&html).contains("And 17 more - the phone lists at most 16."),
        "{html}"
    );
    // Ticked under Apps or not, per app (QA of cmd-expiry, Low 3).
    for (allowlist, says) in [
        (
            "[]",
            "Not ticked under Apps: after \"Allow these apps\" it stays hidden until \
             you <a href=\"/devices/{id}/apps#app-me.vibb.music.debug\">tick it</a> too.",
        ),
        (
            "[\"me.vibb.music.debug\"]",
            "Ticked under Apps: it shows again after \"Allow these apps\".",
        ),
    ] {
        sqlx::query("UPDATE device_policy SET allowlist_json = ? WHERE device_id = ?")
            .bind(allowlist)
            .bind(id)
            .execute(&app.db)
            .await
            .unwrap();
        let html = app
            .get_page(&format!("/devices/{id}"), &cookie)
            .await
            .text();
        let says = says.replace("{id}", &id.to_string());
        assert!(status_card(&html).contains(&says), "{html}");
    }
    // "tick it" lands on the app's switch on Device › Apps (design 31 step 6), under Not allowed.
    sqlx::query("UPDATE device_policy SET allowlist_json = '[]' WHERE device_id = ?")
        .bind(id)
        .execute(&app.db)
        .await
        .unwrap();
    let apps = app
        .get_page(&super::device_page(id, super::Section::Apps), &cookie)
        .await
        .text();
    let row = apps
        .find("id=\"app-me.vibb.music.debug\"")
        .expect("the app's row");
    assert!(apps.find("<section id=\"not-allowed\"").unwrap() < row);
    // Logged once.
    post_status(&app, &token, json!({ "relock_blocks": [music.clone()] })).await;
    let logged = events(&app, "phone_app_blocked").await;
    assert_eq!(logged.len(), 1);
    assert!(
        logged[0].contains("me.vibb.music.debug (outside_play, install"),
        "{logged:?}"
    );

    // The button sends what the card listed, with the key it showed (QA of cmd-expiry, Low 1).
    let listed = format!("me.vibb.music.debug:{key}");
    assert!(
        card.contains(&format!("name=\"blocks\" value=\"{listed}\"")),
        "{card}"
    );
    // Nothing listed (a stale or hand-made form): nothing is queued.
    for blocks in ["", "not a package:", "org.example.app:XYZ"] {
        let res = app
            .request_form(
                Method::POST,
                &format!("/devices/{id}/allow-blocked"),
                Some(&cookie),
                &[("blocks", blocks)],
            )
            .await;
        assert_eq!(res.status, StatusCode::BAD_REQUEST, "{blocks}");
    }
    assert!(commands(&app, id).await.is_empty());

    // The parent allows it; the phone takes the command - with the list -, answers, and reports
    // no block.
    app.request_form(
        Method::POST,
        &format!("/devices/{id}/allow-blocked"),
        Some(&cookie),
        &[("blocks", &listed)],
    )
    .await;
    let res = app
        .request(Method::GET, "/api/devices/policy", Some(&token), None)
        .await;
    assert_eq!(res.json()["pending_command"]["command"], "allow_blocked");
    assert_eq!(
        res.json()["pending_command"]["allow"],
        json!([{ "package_name": "me.vibb.music.debug", "signer": key }])
    );
    let command_id = res.json()["pending_command"]["id"].as_i64().unwrap();
    app.request(
        Method::POST,
        "/api/devices/command-result",
        Some(&token),
        Some(json!({ "command_id": command_id, "success": true, "message": "allowed: 1 apps" })),
    )
    .await;
    post_status(&app, &token, json!({ "relock_blocks": [] })).await;
    let html = app
        .get_page(&format!("/devices/{id}"), &cookie)
        .await
        .text();
    let card = status_card(&html);
    assert!(!card.contains("me.vibb.music.debug"), "{card}");
    assert!(!card.contains(&button), "{card}");
    assert!(
        card.contains("the phone answered &#34;allowed: 1 apps&#34;."),
        "{card}"
    );

    // An imposter of a catalog app: listed, why it stays, and no button for it alone.
    sqlx::query(
        "INSERT INTO tracked_apps (name, source_type, github_repo, package_name, signer_sha256) \
         VALUES ('Chat', 'manual', '', 'org.example.chat', ?)",
    )
    .bind("9".repeat(64))
    .execute(&app.db)
    .await
    .unwrap();
    let imposter = json!({ "package_name": "org.example.chat", "signer": key,
                           "reason": "outside_play", "found": "install", "at_ms": now_ms() });
    // With a relock report from the same time: its "Blocked:" line gives way to the list.
    post_status(
        &app,
        &token,
        json!({ "relock_blocks": [imposter], "relock": literal(now_ms() - 60_000) }),
    )
    .await;
    let html = app
        .get_page(&format!("/devices/{id}"), &cookie)
        .await
        .text();
    let card = status_card(&html);
    assert!(
        card.contains("so it isn&#39;t the catalog&#39;s app and stays blocked: uninstall it"),
        "{card}"
    );
    assert!(!card.contains(&button), "{card}");
    assert!(!card.contains("Blocked: com.example.app"), "{card}");
    assert!(card.contains("it removed 1 Wi-Fi proxy."), "{card}");
    // A total without the list is dropped.
    post_status(&app, &token, json!({ "relock_blocks_total": 18 })).await;
    let stored: Option<i64> = sqlx::query_scalar(
        "SELECT relock_blocks_total FROM device_status ORDER BY id DESC LIMIT 1",
    )
    .fetch_one(&app.db)
    .await
    .unwrap();
    assert_eq!(stored, None);
}

/// QA Low 10: "Allow these apps" goes once the phone answered one pressed after the relock, and
/// each command's answer - or that there is none - shows on the Status card.
#[tokio::test]
async fn the_phones_answers_show_and_an_answered_allow_hides_its_button() {
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
    post_status(
        &app,
        &token,
        json!({ "relock": literal(now_ms() - 3_600_000) }),
    )
    .await;
    let button = format!("action=\"/devices/{id}/allow-blocked\"");
    app.request_form(
        Method::POST,
        &format!("/devices/{id}/allow-blocked"),
        Some(&cookie),
        &[("blocks", "com.example.app:")],
    )
    .await;
    // Sent, not answered: the button stays, and the card says so.
    let html = app
        .get_page(&format!("/devices/{id}"), &cookie)
        .await
        .text();
    assert!(status_card(&html).contains(&button));
    assert!(
        status_card(&html).contains("no answer from the phone yet."),
        "{html}"
    );
    // The phone takes it and answers.
    let res = app
        .request(Method::GET, "/api/devices/policy", Some(&token), None)
        .await;
    let command_id = res.json()["pending_command"]["id"].as_i64().unwrap();
    let res = app
        .request(
            Method::POST,
            "/api/devices/command-result",
            Some(&token),
            Some(
                json!({ "command_id": command_id, "success": true, "message": "allowed: 1 apps" }),
            ),
        )
        .await;
    assert_eq!(res.status, StatusCode::NO_CONTENT);
    let html = app
        .get_page(&format!("/devices/{id}"), &cookie)
        .await
        .text();
    let card = status_card(&html);
    assert!(!card.contains(&button), "{card}");
    assert!(
        card.contains("the phone answered &#34;allowed: 1 apps&#34;."),
        "{card}"
    );
    // The warning itself stays with the report it came from.
    assert!(card.contains("Blocked: com.example.app"), "{card}");
}

#[tokio::test]
async fn the_pause_button_shows_while_no_pause_is_on() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("phone").await;
    let button = format!("action=\"/devices/{id}/start-pause\"");
    post_status(&app, &token, json!({})).await;
    let html = app
        .get_page(&format!("/devices/{id}"), &cookie)
        .await
        .text();
    assert!(status_card(&html).contains(&button), "{html}");
    post_status(&app, &token, json!({ "restrictions_paused": true })).await;
    let html = app
        .get_page(&format!("/devices/{id}"), &cookie)
        .await
        .text();
    assert!(!html.contains(&button));
}

#[tokio::test]
async fn an_app_that_could_read_the_parent_pin_is_a_red_warning_with_the_steps() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("phone").await;
    post_status(
        &app,
        &token,
        json!({ "foreign_access": { "admins": ["com.evil.admin", "not a package"],
                                    "accessibility": ["com.evil.admin"] } }),
    )
    .await;
    let stored: Option<String> = sqlx::query_scalar(
        "SELECT foreign_access_json FROM device_status WHERE device_id = ? ORDER BY id DESC LIMIT 1",
    )
    .bind(id)
    .fetch_one(&app.db)
    .await
    .unwrap();
    assert_eq!(
        stored.as_deref(),
        Some(r#"{"admins":["com.evil.admin"],"accessibility":["com.evil.admin"]}"#)
    );
    let html = app
        .get_page(&format!("/devices/{id}"), &cookie)
        .await
        .text();
    let card = status_card(&html);
    assert!(
        card.contains(
            "The phone refuses the parent PIN because of device admin com.evil.admin and \
             accessibility service com.evil.admin, from an app you haven&#39;t allowed."
        ),
        "{card}"
    );
    assert!(card.contains("Pause restrictions for 2 hours"), "{card}");
    // The link goes to the Parent PIN on Lock Screen & PINs (design 31 step 4).
    assert!(card.contains(&format!(
        "<a href=\"{}#parent-pin\">Change the Parent PIN</a>",
        super::device_page(id, super::Section::Lock)
    )));
    // Logged once when it appears, not again while it stays.
    post_status(
        &app,
        &token,
        json!({ "foreign_access": { "admins": ["com.evil.admin"], "accessibility": ["com.evil.admin"] } }),
    )
    .await;
    assert_eq!(
        events(&app, "phone_foreign_access").await,
        vec![format!(
            "device {id}: the parent PIN is refused on the phone - device admin: com.evil.admin; \
             accessibility on: com.evil.admin."
        )]
    );
    // Gone: the warning goes, and the log says the PIN works again.
    post_status(&app, &token, json!({})).await;
    let html = app
        .get_page(&format!("/devices/{id}"), &cookie)
        .await
        .text();
    assert!(!html.contains("refuses the parent PIN"));
    let logged = events(&app, "phone_foreign_access").await;
    assert_eq!(logged.len(), 2);
    assert!(logged[1].contains("takes the parent PIN again"));
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
    for path in ["end-windows", "allow-blocked", "start-pause"] {
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

/// Do Not Disturb on is an Attention alarm on the device page, linking to About This Phone's
/// Sound group, where it is a red value (design 31 step 3; a red line in the Status card before).
#[tokio::test]
async fn do_not_disturb_is_an_alarm_that_links_to_about() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("phone").await;
    post_status(&app, &token, json!({ "interruption_filter": "alarms" })).await;
    let html = app
        .get_page(&format!("/devices/{id}"), &cookie)
        .await
        .text();
    assert!(
        html.contains(&format!(
            "<a class=\"attention attention-alarm\" href=\"/devices/{id}/about#sound\" data-attention=\"do-not-disturb\">"
        )),
        "{html}"
    );
    let about = app
        .get_page(&format!("/devices/{id}/about"), &cookie)
        .await
        .text();
    assert!(
        about.contains("<span class=\"row-value row-value-alarm\">Alarms only</span>"),
        "{about}"
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
    assert!(
        !html.contains("data-attention=\"do-not-disturb\""),
        "{html}"
    );
    let about = app
        .get_page(&format!("/devices/{id}/about"), &cookie)
        .await
        .text();
    assert!(
        about.contains("<span class=\"row-value\">Off</span>"),
        "{about}"
    );
}
