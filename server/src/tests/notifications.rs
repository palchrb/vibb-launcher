//! Design 35 step 1: the parent's events from their real inputs (a status report, `pin-events`),
//! the battery latch, the bell, the inbox and Notify Me About, retention.

use axum::http::{Method, StatusCode};
use serde_json::{Value, json};

use super::settings_pages::check_forms;
use super::{Section, TestApp, assert_redirect, device_page};

async fn report(app: &TestApp, token: &str, extra: Value) {
    let mut body = json!({ "lock_reason": "NONE", "kiosk_engaged": true });
    body.as_object_mut()
        .unwrap()
        .extend(extra.as_object().unwrap().clone());
    let res = app
        .request(Method::POST, "/api/devices/status", Some(token), Some(body))
        .await;
    assert_eq!(res.status, StatusCode::NO_CONTENT, "{}", res.text());
}

/// `(kind, title)` of a phone's events, oldest first.
async fn events(app: &TestApp, device: i64) -> Vec<(String, String)> {
    sqlx::query_as("SELECT kind, title FROM parent_events WHERE device_id = ? ORDER BY id")
        .bind(device)
        .fetch_all(&app.db)
        .await
        .unwrap()
}

async fn kinds(app: &TestApp, device: i64) -> Vec<String> {
    events(app, device)
        .await
        .into_iter()
        .map(|(k, _)| k)
        .collect()
}

fn ago(hours: i64) -> String {
    (chrono::Utc::now() - chrono::Duration::hours(hours))
        .to_rfc3339_opts(chrono::SecondsFormat::Millis, true)
}

fn call_state(at: &str) -> Value {
    json!({ "call_state": { "state": "managed", "last_emergency_call_at": at } })
}

async fn post_pin_events(app: &TestApp, token: &str, body: &Value) {
    let res = app
        .request(
            Method::POST,
            "/api/devices/pin-events",
            Some(token),
            Some(body.clone()),
        )
        .await;
    assert_eq!(res.status, StatusCode::NO_CONTENT);
}

#[tokio::test]
async fn each_kind_is_raised_once_from_its_real_input() {
    let app = TestApp::new().await;
    let (id, token) = app.enrolled_device("Anna").await;

    // An emergency call: raised once, however often the report repeats it.
    let call = ago(1);
    report(&app, &token, call_state(&call)).await;
    report(&app, &token, call_state(&call)).await;
    // Starts without the launcher: when the count rises.
    let boots = |count: i64| json!({ "unseen_boots": { "count": count, "last_at_ms": 1_760_000_000_000_i64 } });
    report(&app, &token, boots(1)).await;
    report(&app, &token, boots(1)).await;
    // A foreign app: when the set appears or changes, by the phone's name for it in the detail.
    let foreign = json!({
        "foreign_access": { "admins": ["org.example.spy"], "accessibility": [] },
        "installed_apps": [{ "package_name": "org.example.spy", "label": "Helper" }],
    });
    report(&app, &token, foreign.clone()).await;
    report(&app, &token, foreign.clone()).await;
    // Parent-PIN events: a use and a lockout (a wrong try is no event), posted twice.
    let now_ms = chrono::Utc::now().timestamp_millis();
    let pins = json!({ "events": [
        { "id": "0123456789abcdef0123456789abcdef", "kind": "unlocked", "entry": "override", "at_ms": now_ms - 60_000 },
        { "id": "1123456789abcdef0123456789abcdef", "kind": "wrong", "entry": "pin_lock", "at_ms": now_ms - 50_000, "failures": 1 },
        { "id": "2123456789abcdef0123456789abcdef", "kind": "lockout", "entry": "pin_lock", "at_ms": now_ms - 40_000,
          "until_ms": now_ms + 900_000, "level": 1 },
    ] });
    post_pin_events(&app, &token, &pins).await;
    post_pin_events(&app, &token, &pins).await;

    assert_eq!(
        events(&app, id).await,
        [
            ("emergency_call", "Anna called an emergency number"),
            ("unseen_boot", "Anna started without Vibb One"),
            ("foreign_access", "An app may read the PIN on Anna"),
            ("pin_used", "Parent PIN used on Anna"),
            ("pin_lockout", "Parent PIN locked on Anna"),
        ]
        .map(|(k, t)| (k.to_string(), t.to_string()))
    );
    let detail: Option<String> =
        sqlx::query_scalar("SELECT detail FROM parent_events WHERE kind = 'foreign_access'")
            .fetch_one(&app.db)
            .await
            .unwrap();
    assert_eq!(detail.as_deref(), Some("Device admin: Helper."));

    // The same again after it went away is a new event; a rising count is a new start.
    report(&app, &token, json!({})).await;
    report(&app, &token, foreign).await;
    report(&app, &token, boots(2)).await;
    // A newer call is a new event; the old one in a later report is not.
    report(&app, &token, call_state(&ago(0))).await;
    report(&app, &token, call_state(&call)).await;
    assert_eq!(
        kinds(&app, id).await[5..],
        ["foreign_access", "unseen_boot", "emergency_call"]
    );
}

/// The emergency call compares with the previous report (§2.1): a call already reported before
/// an upgrade is not announced, nor a days-old call in a first report or after a report without
/// `call_state` (qa-35-s1-code N4).
#[tokio::test]
async fn an_old_emergency_call_is_not_announced() {
    let app = TestApp::new().await;
    // An older server stored the call in the previous report: the same instant, no event.
    let (id, token) = app.enrolled_device("Anna").await;
    let call = ago(3);
    sqlx::query("INSERT INTO device_status (device_id, lock_reason, kiosk_engaged, call_state_json) VALUES (?, 'NONE', 1, ?)")
        .bind(id)
        .bind(call_state(&call)["call_state"].to_string())
        .execute(&app.db)
        .await
        .unwrap();
    report(&app, &token, call_state(&call)).await;
    assert!(events(&app, id).await.is_empty());
    // A phone's first report with a call two days old.
    let (ola, ola_token) = app.enrolled_device("Ola").await;
    let old = ago(48);
    report(&app, &ola_token, call_state(&old)).await;
    report(&app, &ola_token, call_state(&old)).await;
    // A report without `call_state`, then the old call again.
    report(&app, &ola_token, json!({})).await;
    report(&app, &ola_token, call_state(&old)).await;
    assert!(events(&app, ola).await.is_empty());
}

/// The latch's `AND battery_low_since IS NULL` (qa-35-s1-code L2): two reports that both read the
/// phone armed latch once, and the first latch's instant stays.
#[tokio::test]
async fn two_parallel_low_reports_latch_once() {
    let app = TestApp::new().await;
    let (id, _) = app.enrolled_device("Anna").await;
    let first = crate::parent_events::Phone::load(&app.db, id)
        .await
        .unwrap();
    let mut second = crate::parent_events::Phone::load(&app.db, id)
        .await
        .unwrap();
    second.now += chrono::Duration::seconds(5);
    let low = crate::models::BatteryReport {
        level: 12,
        charging: false,
        low: true,
    };
    crate::parent_events::battery_report(&app.state, &first, &low).await;
    crate::parent_events::battery_report(&app.state, &second, &low).await;
    assert_eq!(kinds(&app, id).await, ["low_battery"]);
    let latch: Option<String> =
        sqlx::query_scalar("SELECT battery_low_since FROM devices WHERE id = ?")
            .bind(id)
            .fetch_one(&app.db)
            .await
            .unwrap();
    assert_eq!(
        latch,
        Some(
            first
                .now
                .to_rfc3339_opts(chrono::SecondsFormat::Millis, true)
        )
    );
}

/// Design 35 §2.5: one event per latch; plugged in and out while low is the same latch; re-armed
/// on battery above Android's level.
#[tokio::test]
async fn the_battery_latches_once() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("Anna").await;
    let battery = |level: i64, charging: bool, low: bool| json!({ "battery": { "level": level, "charging": charging, "low": low } });
    let low_events = || async {
        kinds(&app, id)
            .await
            .into_iter()
            .filter(|k| k == "low_battery")
            .count()
    };
    let line = |html: &str| -> Option<String> {
        let start = html.find("data-battery>")? + "data-battery>".len();
        Some(html[start..start + html[start..].find('<').unwrap()].to_string())
    };

    // A report without `battery` (an older launcher): nothing, no line.
    report(&app, &token, json!({})).await;
    assert_eq!(low_events().await, 0);
    assert_eq!(line(&app.get_page("/devices", &cookie).await.text()), None);

    report(&app, &token, battery(14, false, true)).await;
    assert_eq!(low_events().await, 1);
    let title: String =
        sqlx::query_scalar("SELECT title FROM parent_events WHERE kind = 'low_battery'")
            .fetch_one(&app.db)
            .await
            .unwrap();
    assert_eq!(title, "Battery low on Anna (14 %)");
    // Plugged in (Android clears `low`), unplugged at 12 % (`low` again): still the one latch.
    report(&app, &token, battery(13, true, false)).await;
    report(&app, &token, battery(12, false, true)).await;
    report(&app, &token, battery(12, true, true)).await;
    assert_eq!(low_events().await, 1);
    // The line on the Devices row and the device page.
    for page in ["/devices".to_string(), device_page(id, Section::Summary)] {
        let html = app.get_page(&page, &cookie).await.text();
        assert_eq!(
            line(&html).as_deref(),
            Some("Battery 12 %, charging · just now"),
            "{page}"
        );
    }
    // On battery above the level: armed again; the next low is a second event.
    report(&app, &token, battery(25, false, false)).await;
    let latch: Option<String> =
        sqlx::query_scalar("SELECT battery_low_since FROM devices WHERE id = ?")
            .bind(id)
            .fetch_one(&app.db)
            .await
            .unwrap();
    assert_eq!(latch, None);
    report(&app, &token, battery(15, false, true)).await;
    assert_eq!(low_events().await, 2);

    // A level out of range drops the battery, never the report.
    report(&app, &token, json!({ "battery": { "level": 150, "charging": false, "low": true }, "lock_reason": "BEDTIME" })).await;
    report(&app, &token, json!({ "battery": { "level": "x" } })).await;
    let latest: Vec<(String, Option<i64>)> = sqlx::query_as(
        "SELECT lock_reason, battery_level FROM device_status WHERE device_id = ? ORDER BY id DESC LIMIT 2",
    )
    .bind(id)
    .fetch_all(&app.db)
    .await
    .unwrap();
    assert_eq!(
        latest,
        [("NONE".to_string(), None), ("BEDTIME".to_string(), None)]
    );
    assert_eq!(line(&app.get_page("/devices", &cookie).await.text()), None);
    assert_eq!(low_events().await, 2);
}

/// `n` unread events "Event 0." .. of the first phone, oldest first.
async fn many_events(app: &TestApp, n: i64) {
    sqlx::query(
        "WITH RECURSIVE c(x) AS (SELECT 0 UNION ALL SELECT x + 1 FROM c WHERE x + 1 < ?) \
         INSERT INTO parent_events (device_id, kind, dedup_key, title, summary, target, happened_at) \
         SELECT (SELECT MIN(id) FROM devices), 'pin_used', 'k' || x, 'Event ' || x || '.', 's', \
                '/devices', '2026-10-18T12:00:00.000Z' FROM c",
    )
    .bind(n)
    .execute(&app.db)
    .await
    .unwrap();
}

/// Three unread events of two phones, newest first.
async fn three_events(app: &TestApp) -> (i64, i64) {
    let (anna, anna_token) = app.enrolled_device("Anna").await;
    let (ola, ola_token) = app.enrolled_device("Ola <b>").await;
    report(app, &anna_token, call_state(&ago(1))).await;
    report(app, &ola_token, call_state(&ago(1))).await;
    report(
        app,
        &anna_token,
        json!({ "battery": { "level": 10, "charging": false, "low": true } }),
    )
    .await;
    (anna, ola)
}

#[tokio::test]
async fn the_bell_counts_on_every_tab_page() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    for (page, tab) in [
        ("/devices", "devices"),
        ("/apps", "apps"),
        ("/devices/locate", "locate"),
        ("/music", "music"),
        ("/settings", "settings"),
    ] {
        let html = app.get_page(page, &cookie).await.text();
        assert!(
            html.contains(&format!(
                "href=\"/notifications?from={tab}\" data-unread=\"0\" aria-label=\"Notifications\""
            )),
            "{page}: {html}"
        );
        assert!(!html.contains("bell-count"), "{page}");
    }
    three_events(&app).await;
    for page in [
        "/devices",
        "/apps",
        "/devices/locate",
        "/music",
        "/settings",
    ] {
        let html = app.get_page(page, &cookie).await.text();
        let bar = &html[html.find("class=\"title-bar").unwrap()..];
        let bar = &bar[..bar.find("</div>").unwrap()];
        assert!(
            bar.contains("data-unread=\"3\" aria-label=\"Notifications, 3 unread\"")
                && bar.contains("<span class=\"bell-count\" aria-hidden=\"true\">3</span>"),
            "{page}: {bar}"
        );
    }
    let hub = app.get_page("/settings", &cookie).await.text();
    let row = &hub[hub.find("data-row=\"notifications\"").unwrap()..];
    // The hub's row leads to Notification Settings, where no event is (design 45 §1.6): no
    // count on it - the Settings tab's bell has it (qa-r5-b-code L2).
    let row = &row[..row.find("</a>").unwrap()];
    assert!(
        !row.contains("unread") && !row.contains("row-value"),
        "{row}"
    );
    // 99+ at most.
    many_events(&app, 100).await;
    let html = app.get_page("/devices", &cookie).await.text();
    assert!(html.contains("aria-hidden=\"true\">99+</span>"), "{html}");
}

/// Back never shows a read event as unread (qa-35-s1-code L4): the inbox is never stored, so the
/// back-forward cache doesn't keep it. The tab pages keep their cache (the bell may lag one
/// navigation after Back - accepted).
#[tokio::test]
async fn the_inbox_is_never_stored_the_tab_pages_keep_their_cache() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    for (page, cache) in [
        ("/notifications", "no-store"),
        ("/devices", "private, no-cache"),
        ("/apps", "private, no-cache"),
        ("/devices/locate", "private, no-cache"),
        ("/music", "private, no-cache"),
        ("/settings", "private, no-cache"),
    ] {
        let res = app.get_page(page, &cookie).await;
        assert_eq!(
            res.headers
                .get(axum::http::header::CACHE_CONTROL)
                .and_then(|v| v.to_str().ok()),
            Some(cache),
            "{page}"
        );
    }
}

/// Design 45: the inbox is the page alone - no settings form, a gear to Notification Settings -
/// and opening it reads exactly the rows it shows: they keep their dot this once, the gear's
/// `data-unread` (the app badge's source) carries what is left, a `?device=` filter leaves the
/// other phone's rows unread.
#[tokio::test]
async fn opening_the_inbox_reads_the_rows_it_shows() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (anna, ola) = three_events(&app).await;
    let unread = |app: &TestApp| {
        let db = app.db.clone();
        async move {
            sqlx::query_scalar::<_, i64>(
                "SELECT device_id FROM parent_events e WHERE NOT EXISTS (SELECT 1 FROM parent_event_reads r WHERE r.event_id = e.id) ORDER BY id",
            )
            .fetch_all(&db)
            .await
            .unwrap()
        }
    };
    // Ola's phone only: its one row read, Anna's two still unread (the badge says 2).
    let html = app
        .get_page(&format!("/notifications?from=music&device={ola}"), &cookie)
        .await
        .text();
    assert_eq!(html.matches("data-event=").count(), 1);
    assert_eq!(html.matches("class=\"unread-dot\"").count(), 1);
    assert!(
        html.contains(&format!("<option value=\"{ola}\" selected>")),
        "{html}"
    );
    assert!(
        html.contains(&format!(
            "href=\"/notifications/settings?from=music&#38;device={ola}\" \
             id=\"notification-settings\" data-unread=\"2\" aria-label=\"Notification settings\""
        )),
        "{html}"
    );
    assert!(!html.contains("Mark all"), "nothing left under the filter");
    assert_eq!(unread(&app).await, [anna, anna]);

    // All phones: the two still unread carry their dot, everything is read now.
    let page = app.get_page("/notifications?from=music", &cookie).await;
    assert_eq!(page.status, StatusCode::OK);
    let html = page.text();
    check_forms("/notifications", &html);
    assert_eq!(super::nav_current(&html), "");
    assert!(
        html.contains("<a class=\"nav-back\" href=\"/music\">"),
        "{html}"
    );
    assert_eq!(html.matches("class=\"unread-dot\"").count(), 2);
    assert_eq!(
        html.matches("<span class=\"visually-hidden\">Unread: </span>")
            .count(),
        2
    );
    assert!(html.contains("data-unread=\"0\""), "{html}");
    // No settings here: they are behind the gear.
    for settings in [
        "push-on-form",
        "id=\"devices\"",
        "id=\"events\"",
        "push_low_battery",
    ] {
        assert!(!html.contains(settings), "{settings}");
    }
    assert!(!html.contains("notifications.js"));
    // Newest first, the name escaped.
    let at = |text: &str| html.find(text).unwrap_or_else(|| panic!("{text}: {html}"));
    assert!(at("Battery low on Anna (10 %)") < at("Ola &#60;b&#62; called an emergency number"));
    assert!(at("Ola &#60;b&#62; called") < at("Anna called an emergency number"));
    assert!(unread(&app).await.is_empty());
    // Back (a reload: the page is never stored) shows no dots - the rows are read, by design.
    let html = app
        .get_page("/notifications?from=music", &cookie)
        .await
        .text();
    assert!(!html.contains("unread-dot"));
    let html = app.get_page("/devices", &cookie).await.text();
    assert!(html.contains("data-unread=\"0\""), "the bell follows");
}

#[tokio::test]
async fn the_inbox_rows_open_and_mark_all_as_read() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (anna, ola) = three_events(&app).await;
    // A row's tap marks it read and goes where it happened.
    let call: i64 = sqlx::query_scalar(
        "SELECT id FROM parent_events WHERE device_id = ? AND kind = 'emergency_call'",
    )
    .bind(anna)
    .fetch_one(&app.db)
    .await
    .unwrap();
    let res = app
        .request_form(
            Method::POST,
            &format!("/notifications/{call}/open"),
            Some(&cookie),
            &[],
        )
        .await;
    assert_redirect(&res, &format!("/devices/{anna}/calls"), None);
    let html = app.get_page("/devices", &cookie).await.text();
    assert!(html.contains("data-unread=\"2\""), "{html}");
    // An event gone meanwhile, or a target that isn't a local path: back to the inbox.
    let res = app
        .request_form(
            Method::POST,
            "/notifications/999/open",
            Some(&cookie),
            &[("from", "music"), ("device", &ola.to_string())],
        )
        .await;
    assert_redirect(
        &res,
        &format!("/notifications?from=music&device={ola}"),
        Some("inbox"),
    );
    sqlx::query("UPDATE parent_events SET target = '//evil.example/x' WHERE id = ?")
        .bind(call)
        .execute(&app.db)
        .await
        .unwrap();
    let res = app
        .request_form(
            Method::POST,
            &format!("/notifications/{call}/open"),
            Some(&cookie),
            &[],
        )
        .await;
    assert_redirect(&res, "/notifications?from=devices", Some("inbox"));

    // Mark All as Read, for one phone: the rest of the shown phone's (an older page's) go.
    let res = app
        .request_form(
            Method::POST,
            "/notifications/read-all",
            Some(&cookie),
            &[("from", "apps"), ("device", &anna.to_string())],
        )
        .await;
    assert_redirect(
        &res,
        &format!("/notifications?from=apps&device={anna}"),
        Some("inbox"),
    );
    let unread: Vec<i64> =
        sqlx::query_scalar("SELECT device_id FROM parent_events e WHERE NOT EXISTS (SELECT 1 FROM parent_event_reads r WHERE r.event_id = e.id)")
            .fetch_all(&app.db)
            .await
            .unwrap();
    assert_eq!(unread, [ola]);
    let res = app
        .request_form(Method::POST, "/notifications/read-all", Some(&cookie), &[])
        .await;
    assert_redirect(&res, "/notifications?from=devices", Some("inbox"));
    app.assert_lands(&res, &cookie).await;
    // The routes need a session; a signed-out inbox marks nothing.
    sqlx::query("DELETE FROM parent_event_reads")
        .execute(&app.db)
        .await
        .unwrap();
    let res = app
        .request(
            Method::POST,
            &format!("/notifications/{call}/open"),
            None,
            None,
        )
        .await;
    assert_eq!(res.location(), Some("/login"));
    let res = app.request(Method::GET, "/notifications", None, None).await;
    assert_eq!(res.location(), Some("/login"));
    let unread: i64 =
        sqlx::query_scalar("SELECT COUNT(*) FROM parent_events e WHERE NOT EXISTS (SELECT 1 FROM parent_event_reads r WHERE r.event_id = e.id)")
            .fetch_one(&app.db)
            .await
            .unwrap();
    assert_eq!(unread, 3);
}

/// The 101st event is on the older page and stays unread until that page is opened; Mark All as
/// Read says how many are left.
#[tokio::test]
async fn an_empty_inbox_and_older_pages() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let html = app.get_page("/notifications", &cookie).await.text();
    assert!(
        html.contains("Nothing yet - what the phones report shows up here"),
        "{html}"
    );
    assert!(html.contains("<a class=\"nav-back\" href=\"/devices\">"));
    app.enrolled_device("Anna").await;
    many_events(&app, 101).await;
    let html = app.get_page("/notifications", &cookie).await.text();
    assert_eq!(html.matches("data-event=").count(), 100);
    assert!(!html.contains("Event 0."));
    assert!(html.contains("Mark all 1 as read"), "{html}");
    assert!(html.contains("data-unread=\"1\""));
    let start = html.find("data-row=\"older\"").expect("Show older");
    let href = &html[..start];
    let href = &href[href.rfind("href=\"").unwrap() + 6..];
    let href = href[..href.find('"').unwrap()].replace("&amp;", "&");
    let older = app.get_page(&href, &cookie).await.text();
    assert!(
        older.contains("Event 0.") && older.contains("data-row=\"newest\""),
        "{older}"
    );
    assert_eq!(older.matches("data-event=").count(), 1);
    assert_eq!(older.matches("class=\"unread-dot\"").count(), 1);
    assert!(!older.contains("Mark all"));
}

/// Notification Settings (design 45 §1.2): This Device, Getting Notifications and Notify Me About
/// behind the inbox's gear; the Settings hub's row and the back link.
#[tokio::test]
async fn the_settings_page_holds_the_three_groups() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    three_events(&app).await;
    let page = app
        .get_page("/notifications/settings?from=music", &cookie)
        .await;
    assert_eq!(page.status, StatusCode::OK);
    assert_eq!(
        page.headers
            .get(axum::http::header::CACHE_CONTROL)
            .and_then(|v| v.to_str().ok()),
        Some("no-store")
    );
    let html = page.text();
    check_forms("/notifications/settings", &html);
    assert_eq!(super::nav_current(&html), "");
    assert!(html.contains("<a class=\"nav-back\" href=\"/notifications?from=music\">"));
    assert!(html.contains("<h1 class=\"page-title\">Notification Settings</h1>"));
    for group in ["id=\"this-device\"", "id=\"devices\"", "id=\"events\""] {
        assert!(html.contains(group), "{group}");
    }
    assert!(!html.contains("data-event="), "no inbox here");
    let unread: i64 =
        sqlx::query_scalar("SELECT COUNT(*) FROM parent_events e WHERE NOT EXISTS (SELECT 1 FROM parent_event_reads r WHERE r.event_id = e.id)")
            .fetch_one(&app.db)
            .await
            .unwrap();
    assert_eq!(unread, 3, "the settings page reads nothing");
    let hub = app.get_page("/settings", &cookie).await.text();
    assert!(
        hub.contains("href=\"/notifications/settings?from=settings\" data-row=\"notifications\"")
    );
}

/// Notify Me About: one form, every switch written (a missing one off); the switches govern the
/// push only - a switched-off kind is still stored. The emergency call has no switch.
#[tokio::test]
async fn the_switches_are_saved_and_events_still_stored() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let html = app
        .get_page("/notifications/settings", &cookie)
        .await
        .text();
    for name in [
        "push_pin_used",
        "push_pin_lockout",
        "push_foreign_access",
        "push_unseen_boot",
        "push_low_battery",
    ] {
        assert_eq!(
            super::input_checked(&html, name, "on"),
            Some(true),
            "{name}"
        );
        assert!(super::autosaves(&html, name), "{name}");
    }
    assert!(html.contains("Emergency call<span class=\"row-sub\">Always on</span>"));
    let res = app
        .request_form(
            Method::POST,
            "/notifications/settings",
            Some(&cookie),
            &[
                ("from", "settings"),
                ("push_pin_used", "on"),
                ("push_emergency_call", "off"),
            ],
        )
        .await;
    assert_redirect(
        &res,
        "/notifications/settings?from=settings",
        Some("events"),
    );
    app.assert_lands(&res, &cookie).await;
    let stored: (bool, bool, bool, bool, bool) = sqlx::query_as(
        "SELECT push_pin_used, push_pin_lockout, push_foreign_access, push_unseen_boot, push_low_battery \
         FROM notification_prefs",
    )
    .fetch_one(&app.db)
    .await
    .unwrap();
    assert_eq!(stored, (true, false, false, false, false));
    let html = app
        .get_page("/notifications/settings", &cookie)
        .await
        .text();
    assert_eq!(
        super::input_checked(&html, "push_low_battery", "on"),
        Some(false)
    );

    let (id, token) = app.enrolled_device("Anna").await;
    report(
        &app,
        &token,
        json!({ "battery": { "level": 10, "charging": false, "low": true } }),
    )
    .await;
    assert_eq!(kinds(&app, id).await, ["low_battery"]);
}

#[tokio::test]
async fn events_go_with_their_phone_and_after_90_days() {
    let app = TestApp::new().await;
    let (anna, anna_token) = app.enrolled_device("Anna").await;
    let (ola, ola_token) = app.enrolled_device("Ola").await;
    report(&app, &anna_token, call_state(&ago(1))).await;
    report(&app, &ola_token, call_state(&ago(1))).await;
    report(&app, &ola_token, call_state(&ago(0))).await;
    // One of Ola's events 91 days old (gone), the other 89 (kept): the 90 days (qa-35-s1-code L3).
    for (age, order) in [("-91 days", "MIN"), ("-89 days", "MAX")] {
        sqlx::query(&format!(
            "UPDATE parent_events SET created_at = datetime('now', '{age}') \
             WHERE id = (SELECT {order}(id) FROM parent_events WHERE device_id = ?)"
        ))
        .bind(ola)
        .execute(&app.db)
        .await
        .unwrap();
    }
    // Push rows (step 2 fills them): a month-old delivery and a subscription turned off a month ago.
    let admin: i64 = sqlx::query_scalar(
        "INSERT INTO admin_users (username, password_hash) VALUES ('p', 'x') RETURNING id",
    )
    .fetch_one(&app.db)
    .await
    .unwrap();
    // Subscriptions turned off 31 days ago (gone) and 29 (kept); on the kept one, deliveries 31
    // days old (gone) and 29 (kept).
    let mut kept_sub = 0;
    for (n, age) in [(1, "-31 days"), (2, "-29 days")] {
        kept_sub = sqlx::query_scalar(
            "INSERT INTO push_subscriptions (admin_id, endpoint, p256dh, auth, origin, label, vapid_fp, state, gone_at) \
             VALUES (?, ?, x'00', x'00', 'https://pwa.example', 'Test', 'fp', 'gone', datetime('now', ?)) RETURNING id",
        )
        .bind(admin)
        .bind(format!("https://push.example/{n}"))
        .bind(age)
        .fetch_one(&app.db)
        .await
        .unwrap();
    }
    for age in ["-31 days", "-29 days"] {
        sqlx::query(
            "INSERT INTO push_deliveries (subscription_id, kind, token_hash, created_at) \
             VALUES (?, 'test', x'00', datetime('now', ?))",
        )
        .bind(kept_sub)
        .bind(age)
        .execute(&app.db)
        .await
        .unwrap();
    }
    let pruned = crate::retention::prune(&app.db).await.unwrap();
    assert_eq!((pruned.parent_events, pruned.push_rows), (1, 2));
    assert_eq!(events(&app, ola).await.len(), 1);
    let left: (i64, i64) = sqlx::query_as(
        "SELECT (SELECT COUNT(*) FROM push_subscriptions), (SELECT COUNT(*) FROM push_deliveries)",
    )
    .fetch_one(&app.db)
    .await
    .unwrap();
    assert_eq!(left, (1, 1));
    // Deleting a phone deletes its events.
    sqlx::query("DELETE FROM devices WHERE id = ?")
        .bind(anna)
        .execute(&app.db)
        .await
        .unwrap();
    assert!(events(&app, anna).await.is_empty());
}

/// A target that isn't a local path is never stored (§2.2).
#[tokio::test]
async fn a_target_off_this_server_is_refused() {
    let app = TestApp::new().await;
    let (id, _) = app.enrolled_device("Anna").await;
    let phone = crate::parent_events::Phone::load(&app.db, id)
        .await
        .unwrap();
    let mut event = phone.unseen_boot(1, None);
    let mut conn = app.db.acquire().await.unwrap();
    for target in ["//evil.example/x", "https://evil.example/"] {
        event.target = target.to_string();
        assert_eq!(
            crate::parent_events::raise(&mut conn, &event)
                .await
                .unwrap(),
            None
        );
    }
    event.target = "/devices".to_string();
    assert!(
        crate::parent_events::raise(&mut conn, &event)
            .await
            .unwrap()
            .is_some()
    );
    assert_eq!(
        crate::parent_events::raise(&mut conn, &event)
            .await
            .unwrap(),
        None
    );
}
