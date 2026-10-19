//! Install Now (design 37): the launcher page's button per phone, its command with the release
//! tag, Cancel, the redirects that land on the phone's row or the group, the poll, the 25 h limit's
//! server side, and the page's 500s.

use super::*;
use serde_json::{Value, json};

const TAG: &str = "launcher-v1.4.0@77";

/// The launcher's catalog row with release `launcher-v1.4.0` (asset 77).
async fn launcher(app: &TestApp) -> i64 {
    sqlx::query_scalar(
        "INSERT INTO tracked_apps (name, package_name, github_repo, latest_release_tag, \
         latest_release_asset_id, is_launcher, release_tag_prefix) \
         VALUES ('Vibb One', 'me.vibb.launcher', 'palchrb/vibb-launcher', 'launcher-v1.4.0', 77, 1, \
         'launcher-v') RETURNING id",
    )
    .fetch_one(&app.db)
    .await
    .unwrap()
}

/// A status report from a launcher running `code` (1.3.2 = 1003002), with Install Now when
/// `capable`, and `extra` fields merged in.
async fn report(app: &TestApp, token: &str, code: i64, capable: bool, extra: Value) {
    let mut body = json!({
        "lock_reason": "none",
        "kiosk_engaged": true,
        "app_version": format!("{}.{}.{}", code / 1_000_000, code / 1_000 % 1_000, code % 1_000),
        "app_version_code": code,
        "capabilities": if capable {
            json!(["command_receipt_v1", "install_now_v1"])
        } else {
            json!(["command_receipt_v1"])
        },
    });
    for (key, value) in extra.as_object().unwrap() {
        body[key] = value.clone();
    }
    let res = app
        .request(Method::POST, "/api/devices/status", Some(token), Some(body))
        .await;
    assert!(res.status.is_success(), "{}", res.text());
}

/// A set-up phone on 1.3.2 that knows Install Now.
async fn behind_phone(app: &TestApp, name: &str) -> (i64, String) {
    let (id, token) = app.enrolled_device(name).await;
    report(app, &token, 1_003_002, true, json!({})).await;
    (id, token)
}

async fn post(app: &TestApp, cookie: &str, uri: &str) -> TestResponse {
    app.request_form(Method::POST, uri, Some(cookie), &[]).await
}

fn install_uri(app_id: i64, device: i64) -> String {
    format!("/apps/tracked/{app_id}/install-now/{device}")
}

/// The phone's `install_update` rows: (id, payload, delivered, cancel_reason).
async fn commands(app: &TestApp, device: i64) -> Vec<(i64, Option<String>, bool, Option<String>)> {
    sqlx::query_as(
        "SELECT id, payload, delivered_at IS NOT NULL, cancel_reason FROM device_commands \
         WHERE device_id = ? AND command = 'install_update' ORDER BY id",
    )
    .bind(device)
    .fetch_all(&app.db)
    .await
    .unwrap()
}

async fn policy(app: &TestApp, token: &str) -> Value {
    let res = app
        .request(Method::GET, "/api/devices/policy", Some(token), None)
        .await;
    assert_eq!(res.status, StatusCode::OK, "{}", res.text());
    res.json()
}

async fn receipt(app: &TestApp, token: &str, id: i64) {
    let res = app
        .request(
            Method::POST,
            "/api/devices/command-receipt",
            Some(token),
            Some(json!({ "command_id": id })),
        )
        .await;
    assert_eq!(res.status, StatusCode::NO_CONTENT);
}

/// The phone's row on the launcher page (from `<div ... id="phone-N"` to the next row).
fn row(html: &str, device: i64) -> String {
    let start = html
        .find(&format!("id=\"phone-{device}\""))
        .unwrap_or_else(|| panic!("no row for phone {device}"));
    let rest = &html[start..];
    let end = rest[1..].find("id=\"phone-").map_or(rest.len(), |e| e + 1);
    rest[..end].to_string()
}

#[tokio::test]
async fn install_now_queues_one_command_with_the_tag_and_the_pop_sends_it() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let launcher = launcher(&app).await;
    let (phone, token) = behind_phone(&app, "Anna").await;

    let page = app
        .get_page(&format!("/apps/tracked/{launcher}"), &cookie)
        .await
        .text();
    let html = row(&page, phone);
    assert!(html.contains("1.4.0 not downloaded yet"), "{html}");
    assert!(html.contains(">Install Now</button>"), "{html}");
    assert!(
        page.contains("calls aren't filtered for that minute"),
        "the ⓘ (R-M1)"
    );
    // Every form on the page posts to a fragment that encloses it (the Install Now form its row).
    super::settings_pages::check_forms(&format!("/apps/tracked/{launcher}"), &page);

    let mut nudges = app.state.command_notify.subscribe();
    let sent = post(&app, &cookie, &install_uri(launcher, phone)).await;
    assert_redirect(
        &sent,
        &format!("/apps/tracked/{launcher}?install=sent&phone={phone}"),
        Some(&format!("phone-{phone}")),
    );
    app.assert_lands(&sent, &cookie).await;
    assert_eq!(nudges.try_recv().unwrap(), phone);
    let rows = commands(&app, phone).await;
    assert_eq!(rows.len(), 1);
    assert_eq!(rows[0].1.as_deref(), Some(TAG));
    let detail: String = sqlx::query_scalar(
        "SELECT detail FROM security_events WHERE event_type = 'device_command_queued' \
         ORDER BY id DESC LIMIT 1",
    )
    .fetch_one(&app.db)
    .await
    .unwrap();
    assert_eq!(detail, format!("install_update {TAG}, device {phone}"));

    let page = app
        .get_page(
            &format!("/apps/tracked/{launcher}?install=sent&phone={phone}"),
            &cookie,
        )
        .await
        .text();
    let html = row(&page, phone);
    assert!(
        html.contains("Install Now: waiting for the phone"),
        "{html}"
    );
    assert!(html.contains(">Cancel</button>"), "{html}");
    assert!(!html.contains(">Install Now</button>"), "{html}");
    assert!(html.contains("Install Now sent."), "the flash in the row");
    assert!(
        page.contains("data-install-poll="),
        "the poll runs while it waits"
    );
    assert!(page.contains("/static/install-now.js"));

    // A second tap before the phone fetched it replaces the first.
    let again = post(&app, &cookie, &install_uri(launcher, phone)).await;
    assert!(again.location().unwrap().contains("install=sent"));
    let rows = commands(&app, phone).await;
    assert_eq!(rows.len(), 2);
    assert_eq!(rows[0].3.as_deref(), Some("replaced"));
    assert_eq!(rows[1].3, None);

    // The pop: release_tag only for Install Now.
    let pending = policy(&app, &token).await["pending_command"].clone();
    assert_eq!(pending["command"], "install_update");
    assert_eq!(pending["release_tag"], TAG);
    assert!(pending.get("allow").is_none());
    let id = pending["id"].as_i64().unwrap();
    // Fetched, then confirmed: a third tap answers in_progress and queues nothing.
    let fetched = post(&app, &cookie, &install_uri(launcher, phone)).await;
    assert!(fetched.location().unwrap().contains("install=in_progress"));
    receipt(&app, &token, id).await;
    let working = post(&app, &cookie, &install_uri(launcher, phone)).await;
    assert!(working.location().unwrap().contains("install=in_progress"));
    app.assert_lands(&working, &cookie).await;
    assert_eq!(commands(&app, phone).await.len(), 2);

    // Another command carries no release_tag.
    sqlx::query("INSERT INTO device_commands (device_id, command) VALUES (?, 'ring')")
        .bind(phone)
        .execute(&app.db)
        .await
        .unwrap();
    let ring = policy(&app, &token).await["pending_command"].clone();
    assert_eq!(ring["command"], "ring");
    assert!(ring.get("release_tag").is_none(), "{ring}");
}

#[tokio::test]
async fn every_refusal_lands_on_an_element_the_page_renders() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let launcher = launcher(&app).await;
    let (current, token) = app.enrolled_device("Current").await;
    report(&app, &token, 1_004_000, true, json!({})).await;
    let (old, token) = app.enrolled_device("Old").await;
    report(&app, &token, 1_003_002, false, json!({})).await;
    let (silent, _) = app.enrolled_device("Silent").await;
    let (not_set_up, _) = app.create_device("New").await;

    for (device, word, fragment) in [
        (current, "up_to_date", format!("phone-{current}")),
        (old, "too_old", format!("phone-{old}")),
        (silent, "not_reported", format!("phone-{silent}")),
        (not_set_up, "not_set_up", "phones".to_string()),
        (9999, "unknown_phone", "phones".to_string()),
    ] {
        let res = post(&app, &cookie, &install_uri(launcher, device)).await;
        let location = res.location().unwrap().to_string();
        assert!(location.contains(&format!("install={word}")), "{location}");
        assert!(location.ends_with(&format!("#{fragment}")), "{location}");
        app.assert_lands(&res, &cookie).await;
        let landed = app
            .get_page(location.split('#').next().unwrap(), &cookie)
            .await
            .text();
        let text = crate::install_now::flash(word)
            .unwrap()
            .0
            .replace('\'', "&#39;");
        assert!(landed.contains(&text), "{word}: the flash shows");
    }
    let page = app
        .get_page(&format!("/apps/tracked/{launcher}"), &cookie)
        .await
        .text();
    assert!(row(&page, old).contains("Install Now works from this update on."));
    assert!(row(&page, silent).contains("reported yet"));
    assert!(
        !page.contains(">Install Now</button>"),
        "nobody is behind and able"
    );

    // The row turned off: no release to install, for a phone that is behind.
    let (behind, _) = behind_phone(&app, "Behind").await;
    sqlx::query("UPDATE tracked_apps SET enabled = 0 WHERE id = ?")
        .bind(launcher)
        .execute(&app.db)
        .await
        .unwrap();
    let res = post(&app, &cookie, &install_uri(launcher, behind)).await;
    assert!(res.location().unwrap().contains("install=no_release"));
    app.assert_lands(&res, &cookie).await;
    // No phones at all: the group is still there for the answer.
    let empty = TestApp::new().await;
    let cookie = empty.admin_cookie().await;
    let launcher = self::launcher(&empty).await;
    let res = post(&empty, &cookie, &install_uri(launcher, 5)).await;
    assert!(res.location().unwrap().ends_with("#phones"));
    empty.assert_lands(&res, &cookie).await;
    assert!(
        commands(&app, behind).await.is_empty(),
        "nothing was queued"
    );
}

#[tokio::test]
async fn cancel_stops_it_before_the_fetch_only() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let launcher = launcher(&app).await;
    let (phone, token) = behind_phone(&app, "Anna").await;

    post(&app, &cookie, &install_uri(launcher, phone)).await;
    // The rendered Cancel form posts to its row.
    let page = app
        .get_page(&format!("/apps/tracked/{launcher}"), &cookie)
        .await
        .text();
    super::settings_pages::check_forms(&format!("/apps/tracked/{launcher}"), &page);
    let cancel = tags(&page, "form")
        .into_iter()
        .filter_map(|f| f.attr("action").map(str::to_string))
        .find(|a| a.contains("/cancel/"))
        .expect("a Cancel form");
    assert!(cancel.ends_with(&format!("#phone-{phone}")), "{cancel}");
    let target = cancel.split('#').next().unwrap();
    let res = post(&app, &cookie, target).await;
    assert_redirect(
        &res,
        &format!("/apps/tracked/{launcher}?install=cancelled&phone={phone}"),
        Some(&format!("phone-{phone}")),
    );
    app.assert_lands(&res, &cookie).await;
    assert_eq!(commands(&app, phone).await[0].3.as_deref(), Some("parent"));
    let logged: i64 = sqlx::query_scalar(
        "SELECT COUNT(*) FROM security_events WHERE event_type = 'device_command_cancelled'",
    )
    .fetch_one(&app.db)
    .await
    .unwrap();
    assert_eq!(logged, 1);
    // Cancelled: the button is back, the answer is in the row for a day.
    let page = app
        .get_page(&format!("/apps/tracked/{launcher}"), &cookie)
        .await
        .text();
    let html = row(&page, phone);
    assert!(html.contains(">Install Now</button>"), "{html}");
    assert!(page.contains(&format!("data-install-answer=\"{phone}\"")));

    // Fetched: too late.
    post(&app, &cookie, &install_uri(launcher, phone)).await;
    let id = policy(&app, &token).await["pending_command"]["id"]
        .as_i64()
        .unwrap();
    let res = post(
        &app,
        &cookie,
        &format!("/apps/tracked/{launcher}/install-now/{phone}/cancel/{id}"),
    )
    .await;
    assert!(res.location().unwrap().contains("install=too_late"));
    app.assert_lands(&res, &cookie).await;
    // Only Install Now: a waiting Lock isn't cancelled from here.
    let lock: i64 = sqlx::query_scalar(
        "INSERT INTO device_commands (device_id, command) VALUES (?, 'lock') RETURNING id",
    )
    .bind(phone)
    .fetch_one(&app.db)
    .await
    .unwrap();
    let res = post(
        &app,
        &cookie,
        &format!("/apps/tracked/{launcher}/install-now/{phone}/cancel/{lock}"),
    )
    .await;
    assert!(res.location().unwrap().contains("install=too_late"));
    let cancelled: Option<String> =
        sqlx::query_scalar("SELECT cancelled_at FROM device_commands WHERE id = ?")
            .bind(lock)
            .fetch_one(&app.db)
            .await
            .unwrap();
    assert_eq!(cancelled, None);
}

#[tokio::test]
async fn only_the_launchers_page_and_only_an_admin() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let launcher = launcher(&app).await;
    let (phone, _) = behind_phone(&app, "Anna").await;
    let other: i64 = sqlx::query_scalar(
        "INSERT INTO tracked_apps (name, package_name, github_repo, latest_release_tag) \
         VALUES ('Element X', 'io.element.android.x', 'element-hq/element-x-android', 'v1') \
         RETURNING id",
    )
    .fetch_one(&app.db)
    .await
    .unwrap();
    for uri in [
        install_uri(other, phone),
        format!("/apps/tracked/{other}/install-now/{phone}/cancel/1"),
        install_uri(4242, phone),
    ] {
        assert_eq!(
            post(&app, &cookie, &uri).await.status,
            StatusCode::NOT_FOUND,
            "{uri}"
        );
    }
    let json = app
        .get_page(&format!("/apps/tracked/{other}/install-now.json"), &cookie)
        .await;
    assert_eq!(json.status, StatusCode::NOT_FOUND);
    // Another app's page has no Install Now.
    let page = app
        .get_page(&format!("/apps/tracked/{other}"), &cookie)
        .await
        .text();
    assert!(!page.contains("Install Now"));

    let anonymous = app
        .request_form(Method::POST, &install_uri(launcher, phone), None, &[])
        .await;
    assert!(!anonymous.status.is_success());
    assert!(commands(&app, phone).await.is_empty(), "nothing written");
    // An anonymous Cancel cancels nothing either (qa-37-code nit 1).
    post(&app, &cookie, &install_uri(launcher, phone)).await;
    let id = commands(&app, phone).await[0].0;
    let anonymous = app
        .request_form(
            Method::POST,
            &format!("/apps/tracked/{launcher}/install-now/{phone}/cancel/{id}"),
            None,
            &[],
        )
        .await;
    assert!(!anonymous.status.is_success());
    assert_eq!(commands(&app, phone).await[0].3, None, "still waiting");
}

#[tokio::test]
async fn a_server_start_keeps_it_and_a_confirmed_one_works_for_25_hours() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let launcher = launcher(&app).await;
    let (phone, token) = behind_phone(&app, "Anna").await;
    post(&app, &cookie, &install_uri(launcher, phone)).await;
    // Not an expiring command: a start leaves it waiting.
    crate::handlers::device_api::end_expiring_at_start(&app.db).await;
    assert_eq!(commands(&app, phone).await[0].3, None);

    let id = policy(&app, &token).await["pending_command"]["id"]
        .as_i64()
        .unwrap();
    receipt(&app, &token, id).await;
    // Received 11 minutes ago, the report after it says it downloads: the row words it.
    sqlx::query(
        "UPDATE device_commands SET received_at = datetime('now', '-11 minutes') WHERE id = ?",
    )
    .bind(id)
    .execute(&app.db)
    .await
    .unwrap();
    report(
        &app,
        &token,
        1_003_002,
        true,
        json!({
            "update_fence": { "enabled": false, "state": "none", "waiting_for": "install_now_download" },
            "app_downloads": { "wifi_only": true, "network": "metered", "entries": [{
                "tracked_app_id": launcher, "release_tag": TAG, "state": "downloading",
                "bytes": 40_000_000, "total": 80_000_000,
            }]},
        }),
    )
    .await;
    let page = app
        .get_page(&format!("/apps/tracked/{launcher}"), &cookie)
        .await
        .text();
    assert!(
        row(&page, phone).contains("Downloading 40 of 80 MB"),
        "{}",
        row(&page, phone)
    );
    let polled = app
        .get_page(
            &format!("/apps/tracked/{launcher}/install-now.json"),
            &cookie,
        )
        .await
        .json();
    assert_eq!(
        polled,
        json!({"phones": [{"id": phone, "state": "working", "sub": "Downloading 40 of 80 MB"}]})
    );
    // Recent on the Locate tab: "Install Now", still Received (not "No answer" at 11 minutes).
    let locate = app
        .get_page(&format!("/devices/locate?device={phone}"), &cookie)
        .await
        .text();
    assert!(locate.contains("Install Now"));
    assert!(!locate.contains("No answer"), "not at 11 minutes");
    // 25 hours: "No answer", and the button is back.
    sqlx::query(
        "UPDATE device_commands SET received_at = datetime('now', '-1501 minutes') WHERE id = ?",
    )
    .bind(id)
    .execute(&app.db)
    .await
    .unwrap();
    let locate = app
        .get_page(&format!("/devices/locate?device={phone}"), &cookie)
        .await
        .text();
    assert!(locate.contains("No answer"));
    let page = app
        .get_page(&format!("/apps/tracked/{launcher}"), &cookie)
        .await
        .text();
    assert!(row(&page, phone).contains(">Install Now</button>"));
    assert!(
        !page.contains("data-install-poll="),
        "no poll with nothing waiting"
    );
}

#[tokio::test]
async fn the_answer_shows_in_the_row() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let launcher = launcher(&app).await;
    let (phone, token) = behind_phone(&app, "Anna").await;
    post(&app, &cookie, &install_uri(launcher, phone)).await;
    let pending = policy(&app, &token).await["pending_command"].clone();
    let id = pending["id"].as_i64().unwrap();
    receipt(&app, &token, id).await;
    let res = app
        .request(
            Method::POST,
            "/api/devices/command-result",
            Some(&token),
            Some(json!({ "command_id": id, "success": true, "message": "installed: 1.4.0" })),
        )
        .await;
    assert_eq!(res.status, StatusCode::NO_CONTENT);
    // qa-37-code M1/L3: in one sync the launcher answers before it reports. Before the new report
    // lands the row is still working - no Install Now over the old version - and the poll goes on.
    sqlx::query("UPDATE device_status SET reported_at = datetime('now', '-5 minutes')")
        .execute(&app.db)
        .await
        .unwrap();
    let page = app
        .get_page(&format!("/apps/tracked/{launcher}"), &cookie)
        .await
        .text();
    let html = row(&page, phone);
    assert!(!html.contains(">Install Now</button>"), "{html}");
    assert!(html.contains("waiting for the phone"), "{html}");
    assert!(page.contains("data-install-poll="), "the poll goes on");
    let polled = app
        .get_page(
            &format!("/apps/tracked/{launcher}/install-now.json"),
            &cookie,
        )
        .await
        .json();
    assert_eq!(polled["phones"][0]["state"], "working");
    let tap = post(&app, &cookie, &install_uri(launcher, phone)).await;
    assert!(tap.location().unwrap().contains("install=in_progress"));
    // The report with the new version: the row reads it.
    report(&app, &token, 1_004_000, true, json!({})).await;
    let page = app
        .get_page(&format!("/apps/tracked/{launcher}"), &cookie)
        .await
        .text();
    let html = row(&page, phone);
    assert!(
        html.contains("<span class=\"row-value\">1.4.0</span>"),
        "{html}"
    );
    assert!(!html.contains(">Install Now</button>"));
    assert!(html.contains("Installed 1.4.0"), "{html}");
    assert!(html.contains(">Done<"), "{html}");
}

/// The pop sends `release_tag` for Install Now only - not for `allow_blocked`, whose payload is
/// JSON (qa-37-code S1).
#[tokio::test]
async fn release_tag_goes_only_with_install_now() {
    let app = TestApp::new().await;
    let (phone, token) = behind_phone(&app, "Anna").await;
    sqlx::query(
        "INSERT INTO device_commands (device_id, command, payload) VALUES (?, 'allow_blocked', ?)",
    )
    .bind(phone)
    .bind(r#"[{"package_name":"org.example.game","signer":null}]"#)
    .execute(&app.db)
    .await
    .unwrap();
    let pending = policy(&app, &token).await["pending_command"].clone();
    assert_eq!(pending["command"], "allow_blocked");
    assert!(pending["allow"].is_array(), "{pending}");
    assert!(pending.get("release_tag").is_none(), "{pending}");
}

/// The app page fails closed on a failed read of the app or its phones (qa-37-code S9/S13).
#[tokio::test]
async fn an_app_page_is_a_500_when_the_app_or_its_phones_cant_be_read() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let launcher = launcher(&app).await;
    behind_phone(&app, "Anna").await;
    let other: i64 = sqlx::query_scalar(
        "INSERT INTO tracked_apps (name, package_name, github_repo, latest_release_tag) \
         VALUES ('Element X', 'io.element.android.x', 'element-hq/element-x-android', 'v1') \
         RETURNING id",
    )
    .fetch_one(&app.db)
    .await
    .unwrap();
    for (table, pages) in [
        ("tracked_apps", vec![launcher, other]),
        ("devices", vec![launcher]),
        ("device_tracked_apps", vec![other]),
    ] {
        sqlx::query(&format!("ALTER TABLE {table} RENAME TO {table}_gone"))
            .execute(&app.db)
            .await
            .unwrap();
        for id in pages {
            let res = app.get_page(&format!("/apps/tracked/{id}"), &cookie).await;
            assert_eq!(
                res.status,
                StatusCode::INTERNAL_SERVER_ERROR,
                "{table}, app {id}: {}",
                res.text()
            );
        }
        sqlx::query(&format!("ALTER TABLE {table}_gone RENAME TO {table}"))
            .execute(&app.db)
            .await
            .unwrap();
    }
}

#[tokio::test]
async fn the_launcher_page_and_its_routes_fail_closed() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let launcher = launcher(&app).await;
    let (phone, _) = behind_phone(&app, "Anna").await;
    sqlx::query("ALTER TABLE device_commands RENAME TO device_commands_gone")
        .execute(&app.db)
        .await
        .unwrap();
    for res in [
        app.get_page(&format!("/apps/tracked/{launcher}"), &cookie)
            .await,
        app.get_page(
            &format!("/apps/tracked/{launcher}/install-now.json"),
            &cookie,
        )
        .await,
        post(&app, &cookie, &install_uri(launcher, phone)).await,
        post(
            &app,
            &cookie,
            &format!("/apps/tracked/{launcher}/install-now/{phone}/cancel/1"),
        )
        .await,
    ] {
        assert_eq!(
            res.status,
            StatusCode::INTERNAL_SERVER_ERROR,
            "{}",
            res.text()
        );
    }
}
