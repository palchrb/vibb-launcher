//! The Devices list (design 31 step 2, §3.2): each phone's connection dot and word, "seen ...",
//! the lock part while a rule locks it, today's screen time and the Attention count - which
//! always equals the rows on the phone's own page.

use axum::http::{Method, StatusCode};
use serde_json::{Value, json};

use super::{TestApp, tags};
use crate::display_time::{Zone, parse_db_utc};

async fn report(app: &TestApp, token: &str, extra: Value) {
    let mut body = json!({ "lock_reason": "NONE", "kiosk_engaged": true });
    for (key, value) in extra.as_object().unwrap() {
        body[key] = value.clone();
    }
    let res = app
        .request(Method::POST, "/api/devices/status", Some(token), Some(body))
        .await;
    assert_eq!(res.status, StatusCode::NO_CONTENT, "{}", res.text());
}

/// One phone's row on the list: from its link to the link's end.
fn row(html: &str, id: i64) -> &str {
    let start = html
        .find(&format!("data-device=\"{id}\""))
        .unwrap_or_else(|| panic!("no row for phone {id}: {html}"));
    let rest = &html[start..];
    &rest[..rest.find("</a>").unwrap()]
}

/// The red badge's number; 0 without one.
fn badge(row: &str) -> usize {
    tags(row, "span")
        .into_iter()
        .find_map(|tag| tag.attr("data-attention-count"))
        .map_or(0, |n| n.parse().unwrap())
}

/// The `data-attention` keys of the device page's Attention rows, in order.
fn attention_rows(page: &str) -> Vec<String> {
    tags(page, "a")
        .into_iter()
        .filter_map(|tag| tag.attr("data-attention").map(str::to_string))
        .collect()
}

async fn last_seen(app: &TestApp, id: i64) -> chrono::DateTime<chrono::Utc> {
    let text: String = sqlx::query_scalar("SELECT last_seen_at FROM devices WHERE id = ?")
        .bind(id)
        .fetch_one(&app.db)
        .await
        .unwrap();
    parse_db_utc(&text).unwrap()
}

#[tokio::test]
async fn each_phone_shows_its_connection() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let zone = Zone::from_setting("Europe/Oslo");

    // Online: a stream open and a fresh report.
    let (anna, anna_token) = app.enrolled_device("Anna").await;
    report(&app, &anna_token, json!({})).await;
    let _stream = app.state.command_streams.open(anna);
    // Away: a fresh report, no stream.
    let (ella, ella_token) = app.enrolled_device("Ella").await;
    report(&app, &ella_token, json!({})).await;
    // Offline: last seen 3 hours ago (before a server restart: nothing in memory).
    let (emil, _) = app.enrolled_device("Emil").await;
    sqlx::query("UPDATE devices SET last_seen_at = datetime('now', '-3 hours') WHERE id = ?")
        .bind(emil)
        .execute(&app.db)
        .await
        .unwrap();
    // Not set up, not seen yet, disconnected.
    let (mia, _) = app.create_device("Mia").await;
    let (nora, _) = app.enrolled_device("Nora").await;
    let (otto, _) = app.enrolled_device("Otto").await;
    sqlx::query("UPDATE devices SET token_hash = NULL WHERE id = ?")
        .bind(otto)
        .execute(&app.db)
        .await
        .unwrap();

    let html = app.get_page("/devices", &cookie).await.text();
    let now = chrono::Utc::now();

    let anna_row = row(&html, anna);
    assert!(anna_row.contains("data-status=\"online\""), "{anna_row}");
    assert!(anna_row.contains("<span class=\"state-online\">Online</span> · "));
    assert!(anna_row.contains("avatar-dot dot-online"));
    assert!(anna_row.contains("seen just now"), "{anna_row}");

    let ella_row = row(&html, ella);
    assert!(
        ella_row.contains("<span class=\"state-away\">Away</span> · "),
        "{ella_row}"
    );
    assert!(ella_row.contains("avatar-dot dot-away"));
    assert!(ella_row.contains("seen just now"));
    // "Changes may be delayed" is the summary's, not the list's (H2).
    assert!(!ella_row.contains("delayed"));

    let emil_row = row(&html, emil);
    let seen = last_seen(&app, emil).await;
    assert!(
        emil_row.contains("<span class=\"state-offline\">Offline</span> · "),
        "{emil_row}"
    );
    assert!(
        emil_row.contains(&format!(
            "<span title=\"{}\">seen {}</span>",
            zone.exact(seen),
            zone.relative(seen, now)
        )),
        "{emil_row}"
    );
    // Local time in the title, never the raw database stamp.
    assert!(
        emil_row.contains(" CEST\"") || emil_row.contains(" CET\""),
        "{emil_row}"
    );

    let mia_row = row(&html, mia);
    assert!(mia_row.contains(">Not set up</span>"), "{mia_row}");
    assert!(mia_row.contains("avatar avatar-grey"));
    assert!(!mia_row.contains("seen"));
    assert!(row(&html, nora).contains(">Not seen yet</span>"));
    assert!(
        row(&html, otto).contains(">Disconnected</span> · access removed"),
        "{}",
        row(&html, otto)
    );

    // The raw stamps and the old wording are gone.
    assert!(!html.contains("Last seen 20"));
    assert!(!html.contains("Enrolled, not seen yet"));
    // Each row is a link to the phone's page; the list ends with Add Phone.
    assert!(anna_row.starts_with(&format!("data-device=\"{anna}\"")));
    assert!(html.contains(&format!(
        "<a class=\"row device-row\" href=\"/devices/{anna}\""
    )));
    assert!(
        html.contains("<a class=\"row row-button\" href=\"/devices/new\" data-row=\"add-phone\">")
    );
    assert!(html.contains(">Add Phone</span>"));
}

#[tokio::test]
async fn a_locking_rule_and_todays_screen_time_show_on_the_row() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let zone = Zone::from_setting("Europe/Oslo");
    let today = zone.today(chrono::Utc::now());
    // A rule that runs all of today (start == end: 24 h from 00:00) and no other day, so its
    // lock provably ends at the next midnight whatever the time of the test.
    let mut days = vec![json!(null); 7];
    days[chrono::Datelike::weekday(&today).num_days_from_monday() as usize] =
        json!({"start": 0, "end": 0});
    let rule: i64 = sqlx::query_scalar(
        "INSERT INTO time_rules (device_id, name, kind, calls_allowed, exempt_apps_json, \
         days_json) VALUES (NULL, 'Skole', 'school', 0, '[]', ?) RETURNING id",
    )
    .bind(serde_json::to_string(&days).unwrap())
    .fetch_one(&app.db)
    .await
    .unwrap();
    let (anna, token) = app.enrolled_device("Anna").await;
    // Calls managed and screened, so the school rule really blocks them.
    sqlx::query("UPDATE device_policy SET calls_managed = 1 WHERE device_id = ?")
        .bind(anna)
        .execute(&app.db)
        .await
        .unwrap();
    let _stream = app.state.command_streams.open(anna);
    // `zone`: the phone says it is in the Phone Setup zone (no launcher does yet).
    let time_state = |day: chrono::NaiveDate, zone: Option<&str>| {
        json!({
            "capabilities": ["time_rules_v1", "call_policy_v1"],
            "lock_reason": "SCHOOL",
            "time_state": {
                "day": day.format("%Y-%m-%d").to_string(), "used_minutes": 42,
                "budget_minutes": 90, "extra_minutes": 0, "active_rule_id": rule,
                "active_rule_name": "Skole", "calls_blocked": true, "lock_reason": "SCHOOL",
                "lifts_active": [], "zone": zone
            }
        })
    };
    report(&app, &token, time_state(today, Some("Europe/Oslo"))).await;

    let html = app.get_page("/devices", &cookie).await.text();
    let anna_row = row(&html, anna);
    assert!(
        anna_row.contains(
            "<span class=\"state-online\">Online</span> · <span class=\"lock-part\" data-lock>Skole until 00:00 tomorrow · calls off</span>"
        ),
        "{anna_row}"
    );

    // The same rule every day: the phone stays locked at midnight, so no end (M1).
    sqlx::query("UPDATE time_rules SET days_json = ? WHERE id = ?")
        .bind(serde_json::to_string(&vec![json!({"start": 0, "end": 0}); 7]).unwrap())
        .bind(rule)
        .execute(&app.db)
        .await
        .unwrap();
    let html = app.get_page("/devices", &cookie).await.text();
    assert!(
        row(&html, anna).contains("data-lock>Skole · calls off</span>"),
        "{}",
        row(&html, anna)
    );
    sqlx::query("UPDATE time_rules SET days_json = ? WHERE id = ?")
        .bind(serde_json::to_string(&days).unwrap())
        .bind(rule)
        .execute(&app.db)
        .await
        .unwrap();

    // A phone that doesn't say its zone (today's launchers): no end.
    report(&app, &token, time_state(today, None)).await;
    let html = app.get_page("/devices", &cookie).await.text();
    assert!(
        row(&html, anna).contains("data-lock>Skole · calls off</span>"),
        "{}",
        row(&html, anna)
    );

    // Calls not managed: the rule's flag alone isn't "calls off" (M3).
    sqlx::query("UPDATE device_policy SET calls_managed = 0 WHERE device_id = ?")
        .bind(anna)
        .execute(&app.db)
        .await
        .unwrap();
    let html = app.get_page("/devices", &cookie).await.text();
    let anna_row = row(&html, anna);
    assert!(anna_row.contains("data-lock>Skole</span>"), "{anna_row}");
    // Online with a lock: the lock replaces "seen just now" (the mockup's row).
    assert!(!anna_row.contains("seen"));
    assert!(
        anna_row.contains("<i style=\"width: 46%\"></i>"),
        "{anna_row}"
    );
    assert!(
        anna_row.contains("<span class=\"row-sub\" data-screen-time>42 of 90 min today</span>")
    );

    // A report from yesterday: no end, no bar, "at last report".
    report(
        &app,
        &token,
        time_state(today.pred_opt().unwrap(), Some("Europe/Oslo")),
    )
    .await;
    let html = app.get_page("/devices", &cookie).await.text();
    let anna_row = row(&html, anna);
    assert!(
        anna_row.contains("data-lock>Skole · at last report</span>"),
        "{anna_row}"
    );
    assert!(!anna_row.contains("data-screen-time"));
    assert!(!anna_row.contains("meter"));

    // No rule locking: no lock part.
    report(&app, &token, json!({"capabilities": ["time_rules_v1"]})).await;
    let html = app.get_page("/devices", &cookie).await.text();
    assert!(!row(&html, anna).contains("data-lock"));
}

#[tokio::test]
async fn the_badge_counts_the_device_pages_attention_rows() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    // Do Not Disturb, Backup to Google, no Parent PIN (the first report allows the safe
    // defaults: a managed phone) and a launcher without time rules.
    let (anna, token) = app.enrolled_device("Anna").await;
    report(
        &app,
        &token,
        json!({
            "interruption_filter": "priority",
            "backup_service_enabled": true,
            // Software has a row (the policy-problem target, qa-31-s3-code L5); "ok" adds none.
            "policy_state": "ok",
            "installed_apps": [{"package_name": "com.android.deskclock", "label": "Clock"}]
        }),
    )
    .await;
    // Access removed.
    let (otto, _) = app.enrolled_device("Otto").await;
    sqlx::query("UPDATE devices SET token_hash = NULL WHERE id = ?")
        .bind(otto)
        .execute(&app.db)
        .await
        .unwrap();
    // Nothing to say.
    let (mia, _) = app.create_device("Mia").await;

    let list = app.get_page("/devices", &cookie).await.text();
    for id in [anna, otto, mia] {
        let page = app
            .get_page(&format!("/devices/{id}"), &cookie)
            .await
            .text();
        let rows = attention_rows(&page);
        assert_eq!(badge(row(&list, id)), rows.len(), "phone {id}: {rows:?}");
    }
    let anna_page = app
        .get_page(&format!("/devices/{anna}"), &cookie)
        .await
        .text();
    let rows = attention_rows(&anna_page);
    for key in [
        "do-not-disturb",
        "google-backup",
        "no-parent-pin",
        "launcher-without-rules",
    ] {
        assert!(rows.iter().any(|r| r == key), "{key}: {rows:?}");
    }
    // Alarms first, each row a link to its fix.
    assert!(anna_page.contains(&format!(
        "<a class=\"attention attention-alarm\" href=\"/devices/{anna}/about#sound\" data-attention=\"do-not-disturb\">"
    )));
    assert!(anna_page.contains(&format!(
        "<a class=\"attention attention-alarm\" href=\"/devices/{anna}/advanced#restrictions\" data-attention=\"google-backup\">"
    )));
    assert!(anna_page.contains(">Do Not Disturb is on</span>"));
    assert!(anna_page.contains(&format!(
        "<a class=\"attention attention-action\" href=\"/devices/{anna}#time\" data-attention=\"launcher-without-rules\">"
    )));
    // The targets exist on their pages.
    for anchor in ["status", "time"] {
        assert!(anna_page.contains(&format!("id=\"{anchor}\"")), "{anchor}");
    }
    for (page, anchor) in [
        ("about", "sound"),
        ("about", "software"),
        ("about", "diagnostics"),
        ("advanced", "restrictions"),
        ("advanced", "app-screens"),
        ("advanced", "connection"),
        ("lock", "parent-pin"),
        ("lock", "child-pin"),
        ("home", "layout"),
    ] {
        let html = app
            .get_page(&format!("/devices/{anna}/{page}"), &cookie)
            .await
            .text();
        assert!(
            html.contains(&format!("id=\"{anchor}\"")),
            "{page}#{anchor}"
        );
    }
    assert!(badge(row(&list, anna)) >= 4);
    assert!(
        attention_rows(
            &app.get_page(&format!("/devices/{otto}"), &cookie)
                .await
                .text()
        )
        .contains(&"access-revoked".to_string())
    );
    assert_eq!(badge(row(&list, mia)), 0);
    assert!(!row(&list, mia).contains("count"));
    let mia_page = app
        .get_page(&format!("/devices/{mia}"), &cookie)
        .await
        .text();
    assert!(
        !mia_page.contains("id=\"attention\""),
        "no empty Attention group"
    );
}

#[tokio::test]
async fn an_unknown_time_zone_shows_utc_labelled() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    // `valid_time_zone` checks only the characters: this typo is stored.
    sqlx::query("UPDATE provisioning_settings SET time_zone = 'Europe/Olso' WHERE id = 1")
        .execute(&app.db)
        .await
        .unwrap();
    let (emil, _) = app.enrolled_device("Emil").await;
    sqlx::query("UPDATE devices SET last_seen_at = datetime('now', '-3 hours') WHERE id = ?")
        .bind(emil)
        .execute(&app.db)
        .await
        .unwrap();
    let html = app.get_page("/devices", &cookie).await.text();
    let seen = last_seen(&app, emil).await;
    assert!(
        row(&html, emil).contains(&format!(
            "title=\"{} UTC\"",
            seen.format("%Y-%m-%d %H:%M:%S")
        )),
        "{}",
        row(&html, emil)
    );
}

#[tokio::test]
async fn no_phones_yet() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let html = app.get_page("/devices", &cookie).await.text();
    assert!(html.contains("<h1 class=\"page-title\">Devices</h1>"));
    assert!(html.contains("No phones yet."));
    assert!(!html.contains("group-avatars"));
    assert!(html.contains(">Add Phone</span>"));
    // The setup explanation moved into the add flow.
    assert!(!html.contains("provision it by QR code or adb"));
    let add = app.get_page("/devices/new", &cookie).await.text();
    assert!(add.contains("Settings › Phone Setup"), "{add}");
}
