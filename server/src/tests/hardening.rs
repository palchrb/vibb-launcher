//! Hardening (design 04-hardening.md): the per-device restriction switches in the device policy,
//! Advanced › Restrictions (the device page's "Phone hardening" form until design 31 step 3), and
//! the corrupt-allowlist warning (QA step 1 #11).

use axum::http::{Method, StatusCode};
use serde_json::{Value, json};

use super::{
    Section, TestApp, assert_redirect, autosaves, device_page, form_action_of, input_checked,
};

const SWITCHES: [&str; 10] = [
    "disallow_add_user",
    "disallow_airplane_mode",
    "disallow_config_locale",
    "disallow_config_vpn",
    "disallow_debugging_features",
    "disallow_factory_reset",
    "disallow_modify_accounts",
    "disallow_safe_boot",
    "disallow_usb_file_transfer",
    "lock_location",
];

async fn hardening(app: &TestApp, token: &str) -> Value {
    let res = app
        .request(Method::GET, "/api/devices/policy", Some(token), None)
        .await;
    assert_eq!(res.status, StatusCode::OK, "{}", res.text());
    res.json()["hardening"].clone()
}

async fn save(app: &TestApp, cookie: &str, id: i64, on: &[&str]) -> super::TestResponse {
    let fields: Vec<(&str, &str)> = on.iter().map(|k| (*k, "on")).collect();
    app.request_form(
        Method::POST,
        &format!("/devices/{id}/hardening"),
        Some(cookie),
        &fields,
    )
    .await
}

#[tokio::test]
async fn new_device_gets_explicit_defaults() {
    let app = TestApp::new().await;
    let (_, token) = app.enrolled_device("phone").await;
    // Every switch explicit; safe boot off until a launcher build is proven on the phone.
    assert_eq!(
        hardening(&app, &token).await,
        json!({
            "disallow_factory_reset": true,
            "disallow_add_user": true,
            "disallow_modify_accounts": true,
            "disallow_config_vpn": true,
            "disallow_usb_file_transfer": true,
            "disallow_debugging_features": true,
            "disallow_safe_boot": false,
            "lock_location": true,
            // Airplane mode stays allowed unless the parent blocks it (the family travels).
            "disallow_airplane_mode": false,
            // The system language stays as set up (fix round 2026-10-06).
            "disallow_config_locale": true,
        })
    );
}

#[tokio::test]
async fn hardening_keys_snapshot() {
    // The launcher's HardeningPolicy DTO: exactly these keys, all booleans, never null.
    let app = TestApp::new().await;
    let (_, token) = app.enrolled_device("phone").await;
    let value = hardening(&app, &token).await;
    let object = value.as_object().expect("hardening is an object");
    let mut keys: Vec<&str> = object.keys().map(String::as_str).collect();
    keys.sort_unstable();
    assert_eq!(keys, SWITCHES);
    assert!(object.values().all(Value::is_boolean));
}

#[tokio::test]
async fn form_saves_every_switch_and_missing_means_off() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("phone").await;
    let mut nudges = app.state.command_notify.subscribe();

    let res = save(
        &app,
        &cookie,
        id,
        &["disallow_safe_boot", "lock_location", "disallow_add_user"],
    )
    .await;
    // Back to its group on Advanced (design 31 §5.1.4).
    assert_redirect(
        &res,
        &device_page(id, Section::Advanced),
        Some("restrictions"),
    );
    assert_eq!(nudges.try_recv().ok(), Some(id));
    let value = hardening(&app, &token).await;
    for key in SWITCHES {
        let expected = matches!(
            key,
            "disallow_safe_boot" | "lock_location" | "disallow_add_user"
        );
        assert_eq!(value[key], json!(expected), "{key}");
    }

    // Everything on, then everything off.
    save(&app, &cookie, id, &SWITCHES).await;
    let value = hardening(&app, &token).await;
    assert!(SWITCHES.iter().all(|k| value[*k] == json!(true)));
    save(&app, &cookie, id, &[]).await;
    let value = hardening(&app, &token).await;
    assert!(SWITCHES.iter().all(|k| value[*k] == json!(false)));
}

#[tokio::test]
async fn switches_are_per_device() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (a, token_a) = app.enrolled_device("a").await;
    let (_, token_b) = app.enrolled_device("b").await;
    save(&app, &cookie, a, &[]).await;
    assert_eq!(
        hardening(&app, &token_a).await["disallow_debugging_features"],
        json!(false)
    );
    assert_eq!(
        hardening(&app, &token_b).await["disallow_debugging_features"],
        json!(true)
    );
}

#[tokio::test]
async fn unknown_device_is_404_and_session_is_required() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let res = save(&app, &cookie, 999, &[]).await;
    assert_eq!(res.status, StatusCode::NOT_FOUND);

    let (id, token) = app.enrolled_device("phone").await;
    let res = app
        .request_form(Method::POST, &format!("/devices/{id}/hardening"), None, &[])
        .await;
    assert!(res.status.is_redirection());
    // Nothing was turned off without a session.
    assert_eq!(
        hardening(&app, &token).await["disallow_debugging_features"],
        json!(true)
    );
}

#[tokio::test]
async fn db_error_is_500_and_does_not_nudge() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, _) = app.enrolled_device("phone").await;
    let mut nudges = app.state.command_notify.subscribe();
    sqlx::query("ALTER TABLE device_policy DROP COLUMN lock_location")
        .execute(&app.db)
        .await
        .unwrap();
    let res = save(&app, &cookie, id, &[]).await;
    assert_eq!(res.status, StatusCode::INTERNAL_SERVER_ERROR);
    assert!(nudges.try_recv().is_err());
}

#[tokio::test]
async fn advanced_shows_the_switches_as_one_form() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, _) = app.enrolled_device("phone").await;
    let page = app
        .get_page(&device_page(id, Section::Advanced), &cookie)
        .await;
    assert_eq!(page.status, StatusCode::OK);
    let html = page.text();
    let action = format!("/devices/{id}/hardening#restrictions");
    // All ten in one form (H1: a missing box is off), each a switch that saves itself.
    for (name, on) in [
        ("disallow_factory_reset", true),
        ("disallow_add_user", true),
        ("disallow_modify_accounts", true),
        ("disallow_config_vpn", true),
        ("disallow_usb_file_transfer", true),
        ("lock_location", true),
        ("disallow_airplane_mode", false),
        ("disallow_config_locale", true),
        ("disallow_debugging_features", true),
        ("disallow_safe_boot", false),
    ] {
        assert_eq!(input_checked(&html, name, "on"), Some(on), "{name}");
        assert!(autosaves(&html, name), "{name}");
        assert_eq!(form_action_of(&html, name), Some(action.as_str()), "{name}");
    }
    assert_eq!(html.matches(&format!("action=\"{action}\"")).count(), 1);
    assert!(html.contains("Block safe mode") && html.contains("Block USB debugging"));
    // The device page no longer has the form; its Advanced row leads here.
    let summary = app
        .get_page(&device_page(id, Section::Summary), &cookie)
        .await
        .text();
    assert_eq!(form_action_of(&summary, "disallow_safe_boot"), None);
    assert!(summary.contains(&format!("href=\"/devices/{id}/advanced\"")));
    assert!(!summary.contains("stored app list for this device can't be read"));
}

#[tokio::test]
async fn corrupt_allowlist_is_a_warning_on_the_device_page() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("phone").await;
    sqlx::query("UPDATE device_policy SET allowlist_json = '[\"org.example' WHERE device_id = ?")
        .bind(id)
        .execute(&app.db)
        .await
        .unwrap();
    // The phone gets a 500 and keeps its cache ...
    let res = app
        .request(Method::GET, "/api/devices/policy", Some(&token), None)
        .await;
    assert_eq!(res.status, StatusCode::INTERNAL_SERVER_ERROR);
    // ... and the parent sees why: on Device › Apps (the cause behind its ⓘ), and as the Apps
    // row's value on the device page.
    let page = app.get_page(&device_page(id, Section::Apps), &cookie).await;
    assert_eq!(page.status, StatusCode::OK);
    let html = page.text();
    let warning = &html[html.find("data-warning").expect("the warning")..];
    assert!(warning.contains("App list unreadable"), "{warning}");
    assert!(
        warning.contains("failed to build device policy"),
        "{warning}"
    );
    let summary = app
        .get_page(&device_page(id, Section::Summary), &cookie)
        .await
        .text();
    let row = &summary[summary.find("data-row=\"apps\"").unwrap()..];
    assert!(
        row[..row.find("</a>").unwrap()].contains("List unreadable"),
        "{row}"
    );
}

#[tokio::test]
async fn managed_device_without_pin_is_warned() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, _) = app.enrolled_device("phone").await;
    // The summary's Attention row (design 31 step 10; the device page's red card before) and the
    // warning on Lock Screen & PINs it links to.
    let row = format!("href=\"/devices/{id}/lock#parent-pin\" data-attention=\"no-parent-pin\"");
    let warning = "data-warning=\"no-parent-pin\"";
    async fn pages(app: &TestApp, id: i64, cookie: &str) -> (String, String) {
        (
            app.get_page(&format!("/devices/{id}"), cookie).await.text(),
            app.get_page(&format!("/devices/{id}/lock"), cookie)
                .await
                .text(),
        )
    }
    // Unmanaged (no allowlist, calls unmanaged): nothing to warn about yet.
    let (summary, lock) = pages(&app, id, &cookie).await;
    assert!(!summary.contains(&row) && !lock.contains(warning));

    sqlx::query("UPDATE device_policy SET allowlist_json = '[]' WHERE device_id = ?")
        .bind(id)
        .execute(&app.db)
        .await
        .unwrap();
    let (summary, lock) = pages(&app, id, &cookie).await;
    assert!(summary.contains(&row), "{summary}");
    assert!(lock.contains(warning), "{lock}");
    assert!(lock.contains("only a reset gets the phone back"), "{lock}");

    sqlx::query(
        "UPDATE device_policy SET override_pin_hash = 'aa', override_pin_salt = 'bb' \
         WHERE device_id = ?",
    )
    .bind(id)
    .execute(&app.db)
    .await
    .unwrap();
    let (summary, lock) = pages(&app, id, &cookie).await;
    assert!(!summary.contains(&row) && !lock.contains(warning));
}

#[tokio::test]
async fn rows_from_before_the_migration_get_the_defaults() {
    // Run every migration before 0023, add a device the old way, then the rest.
    let dir = tempfile::tempdir().unwrap();
    let options = sqlx::sqlite::SqliteConnectOptions::new()
        .filename(dir.path().join("old.db"))
        .create_if_missing(true)
        .foreign_keys(true);
    let db = sqlx::SqlitePool::connect_with(options).await.unwrap();
    let full = sqlx::migrate!("./migrations");
    let mut old = sqlx::migrate!("./migrations");
    old.migrations = full
        .migrations
        .iter()
        .filter(|m| m.version < 23)
        .cloned()
        .collect::<Vec<_>>()
        .into();
    old.run(&db).await.unwrap();
    let id: i64 = sqlx::query_scalar("INSERT INTO devices (name) VALUES ('old') RETURNING id")
        .fetch_one(&db)
        .await
        .unwrap();
    sqlx::query("INSERT INTO device_policy (device_id, kiosk_desired) VALUES (?, 1)")
        .bind(id)
        .execute(&db)
        .await
        .unwrap();

    full.run(&db).await.unwrap();
    // A fresh connection: one that saw the table before the ALTERs keeps its old column count.
    db.close().await;
    let options = sqlx::sqlite::SqliteConnectOptions::new().filename(dir.path().join("old.db"));
    let db = sqlx::SqlitePool::connect_with(options).await.unwrap();
    let policy = sqlx::query_as::<_, crate::models::DevicePolicy>(
        "SELECT * FROM device_policy WHERE device_id = ?",
    )
    .bind(id)
    .fetch_one(&db)
    .await
    .unwrap();
    assert_eq!(policy.hardening, crate::models::Hardening::default());
    assert!(policy.hardening.disallow_debugging_features);
    assert!(!policy.hardening.disallow_safe_boot);
}

async fn report_backup(app: &TestApp, token: &str, backup: Option<Value>) {
    let mut report = json!({ "lock_reason": "none", "kiosk_engaged": true });
    if let Some(value) = backup {
        report["backup_service_enabled"] = value;
    }
    let res = app
        .request(
            Method::POST,
            "/api/devices/status",
            Some(token),
            Some(report),
        )
        .await;
    assert_eq!(res.status, StatusCode::NO_CONTENT, "{}", res.text());
}

async fn stored_backup(app: &TestApp, id: i64) -> Option<bool> {
    sqlx::query_scalar(
        "SELECT backup_service_enabled FROM device_status WHERE device_id = ? \
         ORDER BY reported_at DESC, id DESC LIMIT 1",
    )
    .bind(id)
    .fetch_one(&app.db)
    .await
    .unwrap()
}

/// Advanced › Restrictions: the text from its section to the section's end.
fn restrictions(html: &str) -> &str {
    let start = html
        .find("<section id=\"restrictions\"")
        .expect("Restrictions");
    let end = start
        + html[start..]
            .find("</section>")
            .expect("end of Restrictions");
    &html[start..end]
}

/// The "Backup to Google" row's value, if the row is there.
fn backup_row(html: &str) -> Option<&str> {
    let row = &html[html.find("data-row=\"google-backup\"")?..];
    let value = &row[row.find("row-value")?..];
    let value = &value[value.find('>')? + 1..];
    Some(&value[..value.find('<')?])
}

/// Google account on the phone (docs/setup/google-account.md, migrations/0041): the launcher
/// reports whether Android's backup service is on. Off is a quiet "Off" row in Advanced ›
/// Restrictions, on is a warning above the group (and an Attention alarm), and a report without
/// it (an older launcher, or the phone couldn't read it) shows nothing.
#[tokio::test]
async fn backup_service_state_is_stored_and_shown() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("phone").await;
    let advanced = || async {
        app.get_page(&device_page(id, Section::Advanced), &cookie)
            .await
            .text()
    };
    let warning = "Backup to Google is on";

    // No report yet.
    let html = advanced().await;
    assert_eq!(backup_row(&html), None);
    assert!(!html.contains(warning));

    // An older launcher: the field is missing - stored as NULL, nothing shown.
    report_backup(&app, &token, None).await;
    assert_eq!(stored_backup(&app, id).await, None);
    let html = advanced().await;
    assert!(
        backup_row(&html).is_none() && !html.contains(warning),
        "{html}"
    );

    report_backup(&app, &token, Some(json!(false))).await;
    assert_eq!(stored_backup(&app, id).await, Some(false));
    let html = advanced().await;
    assert_eq!(backup_row(restrictions(&html)), Some("Off"), "{html}");
    assert!(!html.contains(warning));
    // The page keeps the scroll position after the auto-saving switches (no jump to the top).
    assert!(html.contains("/static/scroll-restore.js"));

    report_backup(&app, &token, Some(json!(true))).await;
    assert_eq!(stored_backup(&app, id).await, Some(true));
    let html = advanced().await;
    assert!(restrictions(&html).contains(warning), "{html}");
    assert_eq!(backup_row(restrictions(&html)), Some("On"));
    // The Attention row on the device page links to it.
    let summary = app
        .get_page(&device_page(id, Section::Summary), &cookie)
        .await
        .text();
    assert!(summary.contains(&format!("href=\"/devices/{id}/advanced#restrictions\"")));

    // Unknown again (an explicit null): only the latest report counts, so nothing is shown.
    report_backup(&app, &token, Some(Value::Null)).await;
    assert_eq!(stored_backup(&app, id).await, None);
    let html = advanced().await;
    assert!(
        backup_row(&html).is_none() && !html.contains(warning),
        "{html}"
    );
}

#[tokio::test]
async fn backup_service_state_is_per_device() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (a, token_a) = app.enrolled_device("a").await;
    let (b, _) = app.enrolled_device("b").await;
    report_backup(&app, &token_a, Some(json!(true))).await;
    assert!(
        app.get_page(&device_page(a, Section::Advanced), &cookie)
            .await
            .text()
            .contains("Backup to Google is on")
    );
    let html = app
        .get_page(&device_page(b, Section::Advanced), &cookie)
        .await
        .text();
    assert!(!html.contains("Backup to Google"));
}

/// Design 24b §3 (H4): boots the launcher didn't see are stored, logged when the count rises and
/// warned about on the Status card for 30 days - with advice that follows the safe-mode switch.
#[tokio::test]
async fn unseen_boots_are_stored_warned_and_logged() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("phone").await;
    let report = |unseen: Value| {
        let token = token.clone();
        let app = &app;
        async move {
            let res = app
                .request(
                    Method::POST,
                    "/api/devices/status",
                    Some(&token),
                    Some(json!({ "lock_reason": "NONE", "kiosk_engaged": true, "unseen_boots": unseen })),
                )
                .await;
            assert_eq!(res.status, StatusCode::NO_CONTENT);
        }
    };
    let logged = || async {
        sqlx::query_scalar::<_, String>(
            "SELECT detail FROM security_events WHERE event_type = 'boot_unseen' ORDER BY id",
        )
        .fetch_all(&app.db)
        .await
        .unwrap()
    };
    let stored = || async {
        sqlx::query_as::<_, (Option<i64>, Option<i64>)>(
            "SELECT unseen_boots_count, unseen_boots_last_at_ms FROM device_status WHERE device_id = ? ORDER BY id DESC LIMIT 1",
        )
        .bind(id)
        .fetch_one(&app.db)
        .await
        .unwrap()
    };
    // About This Phone's "Starts without Vibb" row (design 31 step 3), up to the next row.
    let page = || async {
        let html = app
            .get_page(&device_page(id, Section::About), &cookie)
            .await
            .text();
        let start = html.find("data-row=\"starts\"").expect("the starts row");
        let end = start
            + html[start..]
                .find("data-row=\"time-lock\"")
                .expect("next row");
        html[start..end].to_string()
    };
    let warning = "1 time</span>";

    // The first report of the witness's build: 0, nothing shown or logged.
    report(json!({ "count": 0, "last_at_ms": null })).await;
    assert_eq!(stored().await, (Some(0), None));
    assert!(logged().await.is_empty());
    assert!(page().await.contains(">None</span>"), "{}", page().await);

    // A safe-mode boot: count 1, logged once, warned with the switch's advice.
    let at = chrono::Utc::now().timestamp_millis() - 60_000;
    report(json!({ "count": 1, "last_at_ms": at })).await;
    assert_eq!(stored().await, (Some(1), Some(at)));
    assert_eq!(
        logged().await,
        vec![format!(
            "device {id}: 1 start(s) without Vibb One running noticed (1 in all)"
        )]
    );
    let html = page().await;
    assert!(html.contains(warning), "{html}");
    assert!(html.contains("row-value-alarm"), "{html}");
    assert!(
        html.contains("Turn on Block safe mode in Advanced."),
        "{html}"
    );
    // An Attention alarm on the device page, linking here.
    let summary = app
        .get_page(&device_page(id, Section::Summary), &cookie)
        .await
        .text();
    assert!(summary.contains(&format!("href=\"/devices/{id}/about#diagnostics\"")));
    // The same count again: not logged again.
    report(json!({ "count": 1, "last_at_ms": at })).await;
    assert_eq!(logged().await.len(), 1);

    // Safe mode blocked after that boot: it may still have been safe mode (QA 24b Low 12).
    let res = save(&app, &cookie, id, &["disallow_safe_boot"]).await;
    assert_eq!(res.status, StatusCode::SEE_OTHER);
    let html = page().await;
    assert!(
        html.contains("Safe mode is blocked now, but maybe not yet at that start"),
        "{html}"
    );
    // Saving the card again keeps when it went on.
    let since: Option<i64> = sqlx::query_scalar(
        "SELECT disallow_safe_boot_set_at_ms FROM device_policy WHERE device_id = ?",
    )
    .bind(id)
    .fetch_one(&app.db)
    .await
    .unwrap();
    assert!(since.is_some_and(|ms| ms > at));
    save(&app, &cookie, id, &["disallow_safe_boot"]).await;
    let again: Option<i64> = sqlx::query_scalar(
        "SELECT disallow_safe_boot_set_at_ms FROM device_policy WHERE device_id = ?",
    )
    .bind(id)
    .fetch_one(&app.db)
    .await
    .unwrap();
    assert_eq!(again, since);
    // A boot noticed after it: probably a start cut short.
    let later = chrono::Utc::now().timestamp_millis() + 1_000;
    report(json!({ "count": 2, "last_at_ms": later })).await;
    assert!(
        page()
            .await
            .contains("Safe mode is blocked, so it was probably a start cut short")
    );
    // Off clears it.
    save(&app, &cookie, id, &[]).await;
    let off: Option<i64> = sqlx::query_scalar(
        "SELECT disallow_safe_boot_set_at_ms FROM device_policy WHERE device_id = ?",
    )
    .bind(id)
    .fetch_one(&app.db)
    .await
    .unwrap();
    assert_eq!(off, None);

    // Older than 30 days: no warning. Junk: dropped, the report kept.
    let old = chrono::Utc::now().timestamp_millis() - 31 * 24 * 3_600_000;
    report(json!({ "count": 1, "last_at_ms": old })).await;
    assert!(!page().await.contains(warning));
    report(json!("<script>")).await;
    assert_eq!(stored().await, (None, None));
}
