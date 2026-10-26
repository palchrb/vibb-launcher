//! Design 24b: the phone's `relock` report, the summary's warnings, blocked apps and quick
//! actions (design 31 step 10), and the commands with their own insert.

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

/// A status report from a launcher with [CAP] (`extra`'s `capabilities` replace it).
async fn post_status(app: &TestApp, token: &str, mut extra: Value) {
    let keys = extra.as_object_mut().unwrap();
    keys.entry("capabilities").or_insert_with(|| json!([CAP]));
    app.post_status(token, extra).await;
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

/// One group of the summary (design 31 step 10): `<section id="…">` to its end.
fn group<'a>(html: &'a str, id: &str) -> &'a str {
    let start = html
        .find(&format!("<section id=\"{id}\""))
        .unwrap_or_else(|| panic!("no #{id} in {html}"));
    let end = html[start..]
        .find("</section>")
        .map_or(html.len(), |e| start + e);
    &html[start..end]
}

#[tokio::test]
async fn the_last_relock_shows_on_the_summary_for_a_week() {
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
    relock["failures"] = json!(["ca_certs"]);
    post_status(
        &app,
        &token,
        json!({ "relock": relock,
                "installed_apps": [{ "package_name": "com.evil.ally", "label": "Ally <b>" }] }),
    )
    .await;
    let html = app
        .get_page(&format!("/devices/{id}"), &cookie)
        .await
        .text();
    let found = group(&html, "relock");
    assert!(found.contains("Its time was up"), "{found}");
    // An hour ago reads "1 h ago" on the same local day, "yesterday HH:MM" right after local
    // midnight (`display_time::relative`); the test runs at any hour.
    assert!(
        found.contains("1 h ago") || found.contains("yesterday "),
        "{found}"
    );
    // Removed is a warning too (24b §1.7, qa-31-s10-code L2).
    assert!(
        found.contains("<span class=\"row-label text-alarm\">Removed<span class=\"row-sub\">1 Wi-Fi proxy</span>"),
        "{found}"
    );
    // By the phone's name, escaped; the package where it reported none.
    assert!(found.contains("Accessibility: Ally &#60;b&#62;"), "{found}");
    assert!(!found.contains("Ally <b>"), "{found}");
    assert!(found.contains("com.android.inputmethod.latin"), "{found}");
    assert!(
        found.contains("data-row=\"relock-notifications\""),
        "{found}"
    );
    // Words, never the step's code.
    assert!(found.contains("Certificates"), "{found}");
    assert!(!found.contains("ca_certs"), "{found}");
    // An older launcher's relock blocks: listed with Allow these apps….
    let blocked = group(&html, "blocked-apps");
    assert!(
        blocked.contains("data-block=\"com.example.app\""),
        "{blocked}"
    );
    assert!(blocked.contains("3f2a0000…0000c0de"), "{blocked}");
    assert!(
        blocked.contains(&format!(
            "action=\"/devices/{id}/allow-blocked#blocked-apps\""
        )),
        "{blocked}"
    );
    assert!(html.contains("data-attention=\"blocked-apps\""), "{html}");

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
    assert!(!html.contains("id=\"relock\""), "{html}");
}

/// The relock's time is the phone's, shown in the Phone Setup zone - never the UTC clock.
#[tokio::test]
async fn relock_times_are_local() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("phone").await;
    sqlx::query("UPDATE provisioning_settings SET time_zone = 'Europe/Oslo'")
        .execute(&app.db)
        .await
        .unwrap();
    // A whole minute an hour ago, so the clock time is known in both zones.
    let at = chrono::Utc::now() - chrono::Duration::hours(1);
    let at = at - chrono::Duration::seconds(i64::from(chrono::Timelike::second(&at)));
    post_status(
        &app,
        &token,
        json!({ "relock": literal(at.timestamp_millis()) }),
    )
    .await;
    let html = app
        .get_page(&format!("/devices/{id}"), &cookie)
        .await
        .text();
    let oslo = at.with_timezone(&chrono_tz::Europe::Oslo);
    let found = group(&html, "relock");
    assert!(found.contains(&oslo.format("%H:%M").to_string()), "{found}");
    assert!(
        found.contains(&format!("title=\"{}", oslo.format("%Y-%m-%d %H:%M"))),
        "{found}"
    );
    assert!(!found.contains("UTC"), "{found}");
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
    let blocked = group(&html, "blocked-apps");
    assert!(
        blocked.contains("Not the catalog&#39;s app: uninstall it"),
        "{blocked}"
    );
    assert!(blocked.contains("99999999…99999999"), "{blocked}");
    assert!(!blocked.contains("allow-blocked"), "{blocked}");
    // An older launcher's imposter waits for nothing: no Attention row for it.
    assert!(!html.contains("data-attention=\"blocked-apps\""), "{html}");
}

#[tokio::test]
async fn lock_again_follows_the_status_and_the_capability() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("phone").await;
    async fn page(app: &TestApp, id: i64, cookie: &str) -> String {
        app.get_page(&format!("/devices/{id}"), cookie).await.text()
    }
    let button = format!("action=\"/devices/{id}/end-windows#quick\"");

    let paused = "data-warning=\"restrictions-paused\"";

    // Nothing open: no button, no word of a pause.
    post_status(&app, &token, json!({})).await;
    let html = page(&app, id, &cookie).await;
    assert!(!html.contains(&button) && !html.contains(paused), "{html}");
    // A pause: the button among the quick actions, and where the Attention row lands one plain
    // line says what Lock again does (qa-31-s10-code M1) - the Attention row is the one red alert
    // (the user, 2026-10-26).
    post_status(&app, &token, json!({ "restrictions_paused": true })).await;
    let html = page(&app, id, &cookie).await;
    let quick = group(&html, "quick");
    assert!(quick.contains(&button), "{html}");
    assert!(
        quick.contains(&format!(
            "<p class=\"quick-note\" {paused}>The pause ends by itself, or tap Lock again to end it now.</p>"
        )),
        "{quick}"
    );
    assert!(!quick.contains("attention-alarm"), "{quick}");
    assert!(
        html.contains(&format!(
            "href=\"/devices/{id}#quick\" data-attention=\"restrictions-paused\""
        )),
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
    // An older launcher: no button, but the pause is still an alarm.
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
    assert!(!html.contains("start-pause"), "{html}");
    // ... and its quick actions still say that the pause ends by itself.
    let quick = group(&html, "quick");
    assert!(quick.contains(paused), "{quick}");
    assert!(
        quick.contains(
            "The pause ends by itself; this version of Vibb One can't be locked again from here."
        ),
        "{quick}"
    );
    assert!(
        html.contains("data-attention=\"restrictions-paused\""),
        "{html}"
    );
}

#[tokio::test]
async fn the_commands_are_queued_logged_and_kept_by_other_commands() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, _token) = app.enrolled_device("phone").await;
    let mut nudges = app.state.command_notify.subscribe();

    let allow = [("blocks", "org.example.app:")];
    for (path, command, notice, fields, logged_as, group_id) in [
        (
            "end-windows",
            "end_windows",
            "end_windows_sent",
            &[][..],
            "end_windows",
            "quick",
        ),
        (
            "allow-blocked",
            "allow_blocked",
            "allow_blocked_sent",
            &allow[..],
            "allow_blocked: org.example.app",
            "blocked-apps",
        ),
        (
            "start-pause",
            "start_pause",
            "start_pause_sent",
            &[][..],
            "start_pause",
            "quick",
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
        // To the summary group the form is in, where the flash shows (design 31 step 10).
        let location = format!("/devices/{id}?notice={notice}#{group_id}");
        assert_eq!(res.location(), Some(location.as_str()));
        assert_eq!(nudges.try_recv().ok(), Some(id));
        let logged = events(&app, "device_command_queued").await;
        assert_eq!(
            logged.last().map(String::as_str),
            Some(logged_as),
            "{command}"
        );
        // The flash is in that group - also the blocked apps' when the list is already gone.
        let html = app.get_page(&location, &cookie).await.text();
        assert!(
            group(&html, group_id).contains("data-flash=\""),
            "{command}: {html}"
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

    // The notice renders in the quick actions; an unknown one renders nothing.
    let html = app
        .get_page(&format!("/devices/{id}?notice=end_windows_sent"), &cookie)
        .await
        .text();
    assert!(
        group(&html, "quick").contains("Sent. The phone locks again within seconds"),
        "{html}"
    );
    // The Lock again just pressed keeps its "Sent." while the summary waits (qa-pwa-fixes-a-code
    // Q-2): pressed long ago, nothing waits.
    sqlx::query(
        "UPDATE device_commands SET requested_at = datetime('now', '-1 hours') WHERE device_id = ?",
    )
    .bind(id)
    .execute(&app.db)
    .await
    .unwrap();
    let html = app
        .get_page(&format!("/devices/{id}?notice=%3Cb%3Ex"), &cookie)
        .await
        .text();
    assert!(!html.contains("data-flash"), "{html}");
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
    let button = format!("action=\"/devices/{id}/allow-blocked#blocked-apps\"");
    let html = app
        .get_page(&format!("/devices/{id}"), &cookie)
        .await
        .text();
    let blocked = group(&html, "blocked-apps");
    assert!(blocked.contains("From outside Play"), "{blocked}");
    assert!(blocked.contains("d3b00000…00006a6e"), "{blocked}");
    assert!(
        blocked.contains("Noticed when it was installed."),
        "{blocked}"
    );
    assert!(blocked.contains(&button), "{blocked}");
    // The confirm names it (M6), by its package while the phone reported no name.
    assert!(
        blocked.contains("Show me.vibb.music.debug again on phone's phone?"),
        "{blocked}"
    );
    assert!(!blocked.contains("more the phone doesn"), "{blocked}");
    assert!(
        html.contains(&format!(
            "href=\"/devices/{id}#blocked-apps\" data-attention=\"blocked-apps\""
        )),
        "{html}"
    );
    // More than the phone lists (QA of cmd-expiry, Low 2): said under the list; the phone's name
    // for the app now.
    post_status(
        &app,
        &token,
        json!({ "relock_blocks": [music.clone()], "relock_blocks_total": 18,
                "installed_apps": [{ "package_name": "me.vibb.music.debug", "label": "Mus<i>ic" }] }),
    )
    .await;
    let html = app
        .get_page(&format!("/devices/{id}"), &cookie)
        .await
        .text();
    let blocked = group(&html, "blocked-apps");
    assert!(
        blocked.contains("And 17 more the phone doesn't list"),
        "{blocked}"
    );
    // The phone's label, escaped in the row and in the confirm (qa-31-s10-code L3).
    assert!(
        blocked.contains("<span class=\"row-label\">Mus&#60;i&#62;ic<span"),
        "{blocked}"
    );
    assert!(blocked.contains("Show Mus&#60;i&#62;ic again"), "{blocked}");
    assert!(!blocked.contains("<i>"), "{blocked}");
    // Ticked under Apps or not, per app (QA of cmd-expiry, Low 3).
    for (allowlist, value, says) in [
        (
            "[]",
            "Not allowed",
            "Not allowed under Apps: it stays hidden until you <a href=\"/devices/{id}/apps#app-me.vibb.music.debug\">turn it on there</a> too.",
        ),
        (
            "[\"me.vibb.music.debug\"]",
            "Allowed",
            "Allowed under Apps: it shows again after Allow these apps.",
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
        let blocked = group(&html, "blocked-apps");
        assert!(blocked.contains(&says), "{html}");
        assert!(
            blocked.contains(&format!("<span class=\"row-value\">{value}</span>")),
            "{blocked}"
        );
    }
    // "turn it on" lands on the app's switch on Device › Apps (design 31 step 6), under Not allowed.
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

    // The button sends what the page listed, with the key it showed (QA of cmd-expiry, Low 1).
    let html = app
        .get_page(&format!("/devices/{id}"), &cookie)
        .await
        .text();
    let listed = format!("me.vibb.music.debug:{key}");
    assert!(
        group(&html, "blocked-apps").contains(&format!("name=\"blocks\" value=\"{listed}\"")),
        "{html}"
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
    assert!(!html.contains("id=\"blocked-apps\""), "{html}");
    assert!(!html.contains(&button), "{html}");
    let activity = group(&html, "activity");
    assert!(
        activity.contains("data-command=\"allow_blocked\""),
        "{activity}"
    );
    assert!(
        activity.contains("title=\"allowed: 1 apps\">1 app allowed<"),
        "{activity}"
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
    // With a relock report from the same time: its blocked app gives way to the phone's list.
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
    let blocked = group(&html, "blocked-apps");
    assert!(
        blocked.contains("Not the catalog&#39;s app: uninstall it"),
        "{blocked}"
    );
    assert!(!blocked.contains(&button), "{blocked}");
    assert!(!blocked.contains("com.example.app"), "{blocked}");
    // It waits all the same (the Attention row counts it), and the relock's findings stay.
    assert!(html.contains("1 blocked app waits"), "{html}");
    assert!(group(&html, "relock").contains("1 Wi-Fi proxy"), "{html}");
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
/// each command's answer - or that there is none - shows in Activity, its row (design 48).
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
    let button = format!("action=\"/devices/{id}/allow-blocked#blocked-apps\"");
    app.request_form(
        Method::POST,
        &format!("/devices/{id}/allow-blocked"),
        Some(&cookie),
        &[("blocks", "com.example.app:")],
    )
    .await;
    // Sent, not answered: the button stays, and Activity says so.
    let html = app
        .get_page(&format!("/devices/{id}"), &cookie)
        .await
        .text();
    assert!(group(&html, "blocked-apps").contains(&button));
    assert!(group(&html, "activity").contains(">Waiting<"), "{html}");
    assert!(html.contains("1 blocked app waits"), "{html}");
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
    assert!(!html.contains(&button), "{html}");
    assert!(
        group(&html, "activity").contains(">1 app allowed<"),
        "{html}"
    );
    // The list itself stays with the report it came from; nothing waits any more.
    assert!(
        group(&html, "blocked-apps").contains("data-block=\"com.example.app\""),
        "{html}"
    );
    assert!(!html.contains("blocked app waits"), "{html}");
}

#[tokio::test]
async fn the_pause_button_shows_while_no_pause_is_on() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("phone").await;
    let button = format!("action=\"/devices/{id}/start-pause#quick\"");
    post_status(&app, &token, json!({})).await;
    let html = app
        .get_page(&format!("/devices/{id}"), &cookie)
        .await
        .text();
    // Behind a confirm (M6) that says what opens.
    let quick = group(&html, "quick");
    let confirm = quick
        .find("<details class=\"act-sheet confirm\" id=\"unlock\">")
        .expect("the confirm");
    assert!(quick[confirm..].contains(&button), "{quick}");
    assert!(
        quick.contains("Unlock phone's phone for 2 hours? Apps, Settings and the Play Store open."),
        "{quick}"
    );
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
                                    "accessibility": ["com.evil.admin"] },
                "installed_apps": [{ "package_name": "com.evil.admin", "label": "Spy <i>Kit" }] }),
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
    let warning = group(&html, "foreign-access");
    assert!(
        warning.contains("Don't type the Parent PIN on the phone"),
        "{warning}"
    );
    // By the phone's name, escaped; the package behind the ⓘ.
    assert!(
        warning.contains("Spy &#60;i&#62;Kit can control the phone or read its screen"),
        "{warning}"
    );
    assert!(!warning.contains("<i>Kit"), "{warning}");
    assert!(
        warning.contains("Device admin: com.evil.admin. Accessibility: com.evil.admin."),
        "{warning}"
    );
    assert!(warning.contains("Unlock 2 h"), "{warning}");
    assert!(
        warning.contains("Security › Device admin apps"),
        "{warning}"
    );
    assert!(warning.contains("Settings › Accessibility"), "{warning}");
    assert!(warning.contains("Lock again"), "{warning}");
    // The link goes to the Parent PIN on Lock Screen & PINs (design 31 step 4).
    assert!(warning.contains(&format!(
        "<a href=\"{}#parent-pin\">change the Parent PIN</a>",
        super::device_page(id, super::Section::Lock)
    )));
    // The Attention row lands on it.
    assert!(
        html.contains(&format!(
            "href=\"/devices/{id}#foreign-access\" data-attention=\"foreign-access\""
        )),
        "{html}"
    );
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
    assert!(!html.contains("id=\"foreign-access\""));
    assert!(!html.contains("data-attention=\"foreign-access\""));
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

/// The phone answers its newest command, as the launcher does after running it.
async fn answer_newest(app: &TestApp, token: &str, id: i64, success: bool) {
    let command: i64 =
        sqlx::query_scalar("SELECT MAX(id) FROM device_commands WHERE device_id = ?")
            .bind(id)
            .fetch_one(&app.db)
            .await
            .unwrap();
    let res = app
        .request(
            Method::POST,
            "/api/devices/command-result",
            Some(token),
            Some(json!({ "command_id": command, "success": success, "message": "done" })),
        )
        .await;
    assert_eq!(res.status, StatusCode::NO_CONTENT);
}

/// PLAN's fix backlog (pwa-fixes-a): after Lock again or Unlock 2 h… the summary loads
/// `auto-reload.js` until the newest report shows what the phone did - unanswered, or answered but
/// not reported yet - and never 2 minutes after the press; with nothing pending it loads nothing.
#[tokio::test]
async fn the_summary_reloads_until_the_phone_shows_lock_again_or_unlock() {
    const SCRIPT: &str = "<script src=\"/static/auto-reload.js?v=4\" defer></script>";
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("phone").await;
    post_status(&app, &token, json!({})).await;
    let page = format!("/devices/{id}");
    let press = |path: &'static str| format!("/devices/{id}/{path}");
    let reloads = |html: String| html.contains(SCRIPT);
    assert!(!reloads(app.get_page(&page, &cookie).await.text()));

    // Unlock: unanswered, then answered but not in a report, then reported.
    let res = app
        .request_form(Method::POST, &press("start-pause"), Some(&cookie), &[])
        .await;
    let landing = res.location().unwrap().to_string();
    assert!(reloads(app.get_page(&landing, &cookie).await.text()));
    // The reload's page has no ?notice= (auto-reload.js drops it): the "Sent." line stays while it
    // waits (qa-pwa-fixes-a-code Q-2), and goes once the report shows the pause.
    const UNLOCK_SENT: &str = "Sent. The phone unlocks for 2 hours";
    let html = app.get_page(&page, &cookie).await.text();
    assert!(reloads(html.clone()), "{html}");
    assert!(group(&html, "quick").contains(UNLOCK_SENT), "{html}");
    answer_newest(&app, &token, id, true).await;
    let html = app.get_page(&page, &cookie).await.text();
    assert!(reloads(html.clone()));
    assert!(group(&html, "quick").contains(UNLOCK_SENT), "{html}");
    post_status(&app, &token, json!({ "restrictions_paused": true })).await;
    let html = app.get_page(&page, &cookie).await.text();
    assert!(!reloads(html.clone()));
    assert!(!html.contains(UNLOCK_SENT), "{html}");

    // Lock again: the same, until the report has the pause gone.
    app.request_form(Method::POST, &press("end-windows"), Some(&cookie), &[])
        .await;
    let html = app.get_page(&page, &cookie).await.text();
    assert!(reloads(html.clone()));
    assert!(
        group(&html, "quick").contains("Sent. The phone locks again"),
        "{html}"
    );
    answer_newest(&app, &token, id, true).await;
    assert!(reloads(app.get_page(&page, &cookie).await.text()));
    post_status(&app, &token, json!({ "restrictions_paused": false })).await;
    assert!(!reloads(app.get_page(&page, &cookie).await.text()));

    // A refusal ends the wait; so do 2 minutes without an answer.
    app.request_form(Method::POST, &press("start-pause"), Some(&cookie), &[])
        .await;
    answer_newest(&app, &token, id, false).await;
    assert!(!reloads(app.get_page(&page, &cookie).await.text()));
    app.request_form(Method::POST, &press("start-pause"), Some(&cookie), &[])
        .await;
    assert!(reloads(app.get_page(&page, &cookie).await.text()));
    // Every command of the phone moved back, so the unanswered Unlock stays the newer of the two
    // (qa-pwa-fixes-a-code Q-4): still waiting a minute after the press, never at two.
    let back = |minutes: &'static str| {
        sqlx::query(
            "UPDATE device_commands SET requested_at = datetime('now', ?) WHERE device_id = ?",
        )
        .bind(minutes)
        .bind(id)
        .execute(&app.db)
    };
    back("-1 minutes").await.unwrap();
    assert!(reloads(app.get_page(&page, &cookie).await.text()));
    back("-2 minutes").await.unwrap();
    assert!(!reloads(app.get_page(&page, &cookie).await.text()));
}
