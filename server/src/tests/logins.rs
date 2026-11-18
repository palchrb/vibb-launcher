//! Design 102: Wi-Fi sign-in pages and in-app logins without free Chrome - the shared contract
//! `testdata/login_windows.json`, Device › Sign-in and its saves, Advanced's `#wifi-signin` row, the report and its
//! Activity events, and the Wi-Fi sign-in's parent-code entry.

use axum::http::{Method, StatusCode};
use serde_json::{Value, json};

use super::{TestApp, assert_redirect};
use crate::logins::{clean_browser, clean_portal, valid_entry};

const CONTRACT: &str = include_str!("../../testdata/login_windows.json");

fn contract() -> Value {
    serde_json::from_str(CONTRACT).unwrap()
}

async fn save(
    app: &TestApp,
    cookie: &str,
    id: i64,
    fields: &[(&str, &str)],
) -> super::TestResponse {
    app.request_form(
        Method::POST,
        &format!("/devices/{id}/logins"),
        Some(cookie),
        fields,
    )
    .await
}

fn pick(policy: &Value) -> Value {
    json!({"captive_portal": policy["captive_portal"], "login_browser": policy["login_browser"]})
}

fn row(contract: &Value, name: &str) -> Value {
    let row = contract["policy"]
        .as_array()
        .unwrap()
        .iter()
        .find(|r| r["name"] == name)
        .unwrap_or_else(|| panic!("no policy row {name}"));
    json!({"captive_portal": row["captive_portal"], "login_browser": row["login_browser"]})
}

async fn report(app: &TestApp, token: &str, extra: Value) {
    app.post_status(token, extra).await;
}

async fn stored(app: &TestApp, id: i64) -> (Option<String>, Option<String>) {
    sqlx::query_as(
        "SELECT captive_portal_json, login_browser_json FROM device_status WHERE device_id = ? \
         ORDER BY id DESC LIMIT 1",
    )
    .bind(id)
    .fetch_one(&app.state.db)
    .await
    .unwrap()
}

/// Each `policy` row produced from the saves on the page; the reports stored as the contract has them; the `clean`
/// and `entries` rows; the copies identical.
#[tokio::test]
async fn the_contract() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("Emma").await;
    let contract = contract();

    assert_eq!(pick(&app.policy(&token).await), row(&contract, "new_phone"));
    for fields in [
        vec![("section", "wifi")],
        vec![("section", "wifi_signin")],
        vec![("section", "grant")],
        vec![("section", "apps"), ("on", "on")],
        vec![("section", "preset"), ("preset", "google"), ("on", "on")],
        vec![
            ("section", "site"),
            ("action", "add"),
            ("entry", "accounts.google.com"),
        ],
        vec![
            ("section", "site"),
            ("action", "add"),
            ("entry", "https://school.example/login?term=1"),
            ("note", "The school's portal"),
        ],
    ] {
        let res = save(&app, &cookie, id, &fields).await;
        assert!(res.status.is_redirection(), "{fields:?}: {}", res.status);
    }
    assert_eq!(
        pick(&app.policy(&token).await),
        row(&contract, "wifi_off_code_off_grant_role_on")
    );
    save(
        &app,
        &cookie,
        id,
        &[("section", "preset"), ("preset", "google")],
    )
    .await;
    assert_eq!(
        pick(&app.policy(&token).await),
        row(&contract, "preset_unticked")
    );
    sqlx::query(
        "UPDATE device_policy SET allowlist_json = '[\"com.android.chrome\"]' WHERE device_id = ?",
    )
    .bind(id)
    .execute(&app.db)
    .await
    .unwrap();
    assert_eq!(
        pick(&app.policy(&token).await),
        row(&contract, "chrome_ticked")
    );

    for name in ["report", "report_off"] {
        report(&app, &token, contract[name].clone()).await;
        let (portal, browser) = stored(&app, id).await;
        let portal: Value = serde_json::from_str(&portal.unwrap()).unwrap();
        let browser: Value = serde_json::from_str(&browser.unwrap()).unwrap();
        assert_eq!(portal, contract[name]["captive_portal"], "{name}");
        assert_eq!(browser, contract[name]["login_browser"], "{name}");
    }

    for row in contract["clean"].as_array().unwrap() {
        let cleaned = match row["part"].as_str().unwrap() {
            "captive_portal" => clean_portal(&row["in"]),
            _ => clean_browser(&row["in"]),
        };
        assert_eq!(cleaned.unwrap_or(Value::Null), row["stored"], "{row}");
    }
    for row in contract["entries"].as_array().unwrap() {
        assert_eq!(
            valid_entry(row["in"].as_str().unwrap())
                .map(Value::from)
                .unwrap_or(Value::Null),
            row["entry"],
            "{row}"
        );
    }

    let launcher = std::fs::read_to_string(concat!(
        env!("CARGO_MANIFEST_DIR"),
        "/../launcher/app/src/test/resources/login_windows.json"
    ))
    .unwrap();
    assert_eq!(launcher, CONTRACT, "the launcher's copy differs");
}

/// The page's groups and their anchors; each save lands on its own; the role's switch is disabled while Chrome is
/// ticked; Advanced's row is on by default and its save lands back on `#wifi-signin`; a refused site says why; an
/// unknown section or phone writes nothing.
#[tokio::test]
async fn the_pages_and_their_saves() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, _token) = app.enrolled_device("Emma").await;
    let page = format!("/devices/{id}/logins");

    let html = app.get_page(&page, &cookie).await.text();
    super::settings_pages::check_forms(&page, &html);
    for anchor in ["logins-wifi", "logins-apps", "logins-sites"] {
        assert!(html.contains(&format!("id=\"{anchor}\"")), "{anchor}");
    }
    assert!(html.contains(&format!("href=\"/devices/{id}/advanced#wifi-signin\"")));
    // Design 113: the parent code's row is a label and its state; Chrome's clear note shows while it is off.
    let code = &html[html.find("data-row=\"wifi-code\"").unwrap()..];
    let code = &code[..code.find("</a>").unwrap()];
    assert!(
        code.contains("<span class=\"row-label\">Parent code</span>")
            && code.contains("<span class=\"row-value\">On</span>"),
        "{code}"
    );
    assert!(html.contains("Turning this on clears what Chrome has stored"));
    // The Web Filter page links here, with the sub-line.
    let filter = app
        .get_page(&format!("/devices/{id}/filter"), &cookie)
        .await
        .text();
    assert!(filter.contains(&format!(
        "href=\"/devices/{id}/logins\" data-row=\"logins\""
    )));
    assert!(filter.contains("Wi-Fi: on · Apps: off"), "{filter}");

    // Advanced: the requirement on by default (migration 0150), its save back on its anchor.
    let advanced = app
        .get_page(&format!("/devices/{id}/advanced"), &cookie)
        .await
        .text();
    let group = &advanced[advanced
        .find("<section id=\"wifi-signin\"")
        .expect("#wifi-signin")..];
    let group = &group[..group.find("</section>").unwrap()];
    assert!(group.contains("name=\"section\" value=\"wifi_signin\""));
    assert!(group.contains(" checked"), "on by default");
    assert!(group.contains("data-autosave"));
    let res = save(&app, &cookie, id, &[("section", "wifi_signin")]).await;
    assert_redirect(
        &res,
        &format!("/devices/{id}/advanced"),
        Some("wifi-signin"),
    );
    app.assert_lands(&res, &cookie).await;
    let require: bool = sqlx::query_scalar(
        "SELECT captive_portal_require_code FROM device_policy WHERE device_id = ?",
    )
    .bind(id)
    .fetch_one(&app.db)
    .await
    .unwrap();
    assert!(!require);
    // With the code off every opening is free: no grant button, no word about it behind the (i).
    let off = app.get_page(&page, &cookie).await.text();
    assert!(
        !off.contains("data-grant") && !off.contains("Allow one more sign-in now"),
        "{off}"
    );
    save(
        &app,
        &cookie,
        id,
        &[("section", "wifi_signin"), ("on", "on")],
    )
    .await;
    let on = app.get_page(&page, &cookie).await.text();
    assert!(on.contains("data-grant"), "{on}");

    for (fields, anchor) in [
        (vec![("section", "wifi"), ("on", "on")], "logins-wifi"),
        (vec![("section", "apps")], "logins-apps"),
        (
            vec![("section", "preset"), ("preset", "feide"), ("on", "on")],
            "logins-sites",
        ),
        (
            vec![
                ("section", "site"),
                ("action", "remove"),
                ("entry", "none.example"),
            ],
            "logins-sites",
        ),
    ] {
        let res = save(&app, &cookie, id, &fields).await;
        assert_redirect(&res, &page, Some(anchor));
        app.assert_lands(&res, &cookie).await;
    }
    // "One more" bumps the counter and says so.
    let res = save(&app, &cookie, id, &[("section", "grant")]).await;
    assert_redirect(&res, &format!("{page}?notice=granted"), Some("logins-wifi"));
    let granted = app
        .get_page(&format!("{page}?notice=granted"), &cookie)
        .await
        .text();
    assert!(granted.contains("data-flash=\"granted\""));
    save(&app, &cookie, id, &[("section", "grant")]).await;
    let grant: i64 =
        sqlx::query_scalar("SELECT captive_portal_grant FROM device_policy WHERE device_id = ?")
            .bind(id)
            .fetch_one(&app.db)
            .await
            .unwrap();
    assert_eq!(grant, 2);

    // A site that isn't one: back with the reason, nothing stored.
    let res = save(
        &app,
        &cookie,
        id,
        &[
            ("section", "site"),
            ("action", "add"),
            ("entry", "*.example"),
        ],
    )
    .await;
    assert_redirect(
        &res,
        &format!("{page}?notice=entry_invalid"),
        Some("logins-sites"),
    );
    let refused = app
        .get_page(&format!("{page}?notice=entry_invalid"), &cookie)
        .await
        .text();
    assert!(refused.contains("data-flash=\"site\""));
    let sites: i64 =
        sqlx::query_scalar("SELECT COUNT(*) FROM login_role_sites WHERE device_id = ?")
            .bind(id)
            .fetch_one(&app.db)
            .await
            .unwrap();
    assert_eq!(sites, 0);

    // Chrome ticked: the switch is disabled with the reason, and turning it on is refused.
    sqlx::query(
        "UPDATE device_policy SET allowlist_json = '[\"com.android.chrome\"]' WHERE device_id = ?",
    )
    .bind(id)
    .execute(&app.db)
    .await
    .unwrap();
    let ticked = app.get_page(&page, &cookie).await.text();
    assert!(ticked.contains("data-row=\"chrome-ticked\""));
    // A quiet line (2026-11-18: the parent ticked Chrome), the fix behind its ⓘ.
    let line = &ticked[ticked.find("data-row=\"chrome-ticked\"").unwrap()..];
    let line = &line[..line.find("</details>").unwrap()];
    assert!(
        line.contains("<span class=\"row-label\">Chrome is allowed as a normal app</span>"),
        "{line}"
    );
    assert!(line.contains("Untick it under Apps"), "{line}");
    save(&app, &cookie, id, &[("section", "apps"), ("on", "on")]).await;
    let on: bool =
        sqlx::query_scalar("SELECT login_browser_enabled FROM device_policy WHERE device_id = ?")
            .bind(id)
            .fetch_one(&app.db)
            .await
            .unwrap();
    assert!(!on);

    let res = save(&app, &cookie, id, &[("section", "laser")]).await;
    assert_eq!(res.status, StatusCode::BAD_REQUEST);
    let res = save(&app, &cookie, 9999, &[("section", "wifi")]).await;
    assert_eq!(res.status, StatusCode::NOT_FOUND);
}

/// The Apps page's Chrome row says it is only for sign-in while the role is on.
#[tokio::test]
async fn the_apps_page_names_the_login_role() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("Emma").await;
    app.post_status(
        &token,
        json!({"installed_apps": [{"package_name": "com.android.chrome", "label": "Chrome"}]}),
    )
    .await;
    let apps = format!("/devices/{id}/apps");
    assert!(
        !app.get_page(&apps, &cookie)
            .await
            .text()
            .contains("data-login-only")
    );
    save(&app, &cookie, id, &[("section", "apps"), ("on", "on")]).await;
    assert!(
        app.get_page(&apps, &cookie)
            .await
            .text()
            .contains("data-login-only")
    );
    // Design 113: on, the clear note has said its piece.
    let logins = app
        .get_page(&format!("/devices/{id}/logins"), &cookie)
        .await
        .text();
    assert!(!logins.contains("Turning this on clears"), "{logins}");
}

/// `wifi_signin_wide` once across two reports (its key is the session's `opened_ms`), never for blocked
/// sub-resources alone; an older launcher's spent daily count raises nothing and shows nowhere (no daily count since
/// 2026-11-18); the parent code's Wi-Fi entry worded.
#[tokio::test]
async fn the_reports_events() {
    let app = TestApp::new().await;
    let (id, token) = app.enrolled_device("Emma").await;
    let now = chrono::Utc::now().timestamp_millis();
    let session = |opened: i64, visited: &[&str], frames: &[&str]| {
        json!({"opened_ms": opened, "closed_ms": opened + 60_000, "network": "Hotel Fjord",
            "first_host": "portal.example", "outcome": "validated", "visited": visited,
            "visited_count": visited.len(), "blocked_hosts": ["ads.example"], "blocked_frames": frames,
            "start": "system", "approved": "code"})
    };
    let body = json!({"captive_portal": {"handler": "vibb", "parent_code_set": true,
    "sessions": [
        session(now - 600_000, &["portal.example"], &[]),
        session(now - 300_000, &["portal.example", "a.example", "b.example", "c.example"], &[]),
    ]}});
    report(&app, &token, body.clone()).await;
    report(&app, &token, body).await;
    let kinds = |events: Vec<(String, String, String)>| -> Vec<String> {
        events.into_iter().map(|(kind, ..)| kind).collect()
    };
    let told = super::told_events(&app, id, crate::i18n::Lang::En).await;
    assert_eq!(kinds(told.clone()), ["wifi_signin_wide"]);
    assert_eq!(told[0].1, "Emma: the Wi-Fi sign-in visited 4 sites");
    let target: String = sqlx::query_scalar("SELECT target FROM parent_events WHERE device_id = ?")
        .bind(id)
        .fetch_one(&app.db)
        .await
        .unwrap();
    assert_eq!(target, format!("/devices/{id}/logins#logins-wifi"));

    // An older launcher's `today` with its daily count spent: dropped, no event, nothing on the page.
    let spent = json!({"captive_portal": {"handler": "vibb", "parent_code_set": true,
        "today": {"openings": 4, "ms": 1_200_000, "grant_used": 3}, "sessions": []}});
    report(&app, &token, spent).await;
    assert_eq!(
        kinds(super::told_events(&app, id, crate::i18n::Lang::En).await),
        ["wifi_signin_wide"]
    );
    let portal = stored(&app, id).await.0.unwrap();
    assert!(!portal.contains("today"), "{portal}");
    let cookie = app.admin_cookie().await;
    let page = app
        .get_page(&format!("/devices/{id}/logins"), &cookie)
        .await
        .text();
    assert!(
        !page.contains("wifi-today")
            && !page.contains("Today without a code")
            && !page.contains(" a day"),
        "{page}"
    );
    assert!(page.contains("data-grant"), "the grant button stays");

    // The Wi-Fi keypad's right code: a stored PIN event, worded as the Wi-Fi sign-in's.
    let body = json!({"events": [{"id": "00112233445566778899aabbccddeeff", "kind": "unlocked",
        "entry": "wifi_signin", "at_ms": now - 1_000}]});
    let res = app
        .request(
            Method::POST,
            "/api/devices/pin-events",
            Some(&token),
            Some(body),
        )
        .await;
    assert_eq!(res.status, StatusCode::NO_CONTENT, "{}", res.text());
    let entry: String =
        sqlx::query_scalar("SELECT entry FROM device_pin_events WHERE device_id = ?")
            .bind(id)
            .fetch_one(&app.db)
            .await
            .unwrap();
    assert_eq!(entry, "wifi_signin");
    let words = super::security_words(&app, "parent_pin_used", crate::i18n::Lang::En).await;
    assert!(
        words
            .iter()
            .any(|w| w.contains("Wi-Fi sign-in opened with the parent code.")),
        "{words:?}"
    );
}

/// The user, 2026-11-18: with the parent code off (`approved: "none"`) a timeout alone raises nothing, and the sites
/// and frames events land in the inbox but push once an hour a phone, with no digest; with the code on, unchanged.
#[tokio::test]
async fn code_off_events_push_once_an_hour() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    super::web_push::subscribe(&app, &cookie, super::web_push::ENDPOINT).await;
    let (id, token) = app.enrolled_device("Emma").await;
    let now = chrono::Utc::now().timestamp_millis();
    let session = |ago_min: i64, sites: usize, approved: &str| {
        let visited: Vec<String> = (0..sites).map(|n| format!("s{n}.example")).collect();
        json!({"opened_ms": now - ago_min * 60_000, "closed_ms": now - ago_min * 60_000 + 600_000,
            "outcome": "timeout", "visited": visited, "visited_count": sites, "blocked_hosts": [],
            "blocked_frames": [], "start": "system", "approved": approved})
    };
    let sessions = vec![
        session(90, 1, "none"),
        session(80, 1, "none"),
        session(70, 4, "none"),
        session(60, 4, "none"),
        session(50, 4, "none"),
        session(40, 1, "code"),
        session(30, 1, "code"),
    ];
    let body = json!({"captive_portal": {"handler": "vibb", "parent_code_set": true, "sessions": sessions}});
    report(&app, &token, body).await;
    let rows: Vec<(String, Option<String>)> = sqlx::query_as(
        "SELECT dedup_key, push_budget_key FROM parent_events WHERE device_id = ? \
         AND kind = 'wifi_signin_wide' ORDER BY happened_at",
    )
    .bind(id)
    .fetch_all(&app.db)
    .await
    .unwrap();
    let keys: Vec<Option<&str>> = rows.iter().map(|(_, k)| k.as_deref()).collect();
    assert_eq!(
        keys,
        [Some("code_off"), None, None, None, None],
        "three code-off sites events (one pushed), two code-on timeouts, no code-off timeout"
    );
    let deliveries: Vec<String> = sqlx::query_scalar("SELECT kind FROM push_deliveries")
        .fetch_all(&app.db)
        .await
        .unwrap();
    assert_eq!(
        deliveries.len(),
        3,
        "one code-off push and both code-on: {deliveries:?}"
    );
    assert!(!deliveries.iter().any(|k| k == "digest"), "{deliveries:?}");
}
