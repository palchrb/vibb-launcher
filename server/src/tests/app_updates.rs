//! App update notices (2026-10-25, `docs/notes/server/notifications.md` "App update notices"): a new
//! release of a catalog app is one inbox event with no phone, whatever the sweeps; a phone's report
//! with the launcher's or the music app's new release installed is one event per phone and
//! version; both are pushed only under "App updates", off by default; migration 0099 keeps every
//! event, delivery and read.

use axum::http::{Method, StatusCode};
use chrono::Utc;
use serde_json::{Value, json};

use super::TestApp;
use super::web_push::{ENDPOINT, payload, subscribe};
use crate::parent_events::{app_update_available, raise_logged};

/// The launcher's catalog row on `launcher-v0.36.0` (versionCode 360009) and the music app's on
/// `music-v0.6.0` (60009).
async fn catalog(app: &TestApp) -> (i64, i64) {
    let launcher = sqlx::query_scalar(
        "INSERT INTO tracked_apps (name, package_name, github_repo, latest_release_tag, \
         latest_release_asset_id, is_launcher, release_tag_prefix) \
         VALUES ('Vibb One', 'me.vibb.launcher', 'palchrb/vibb-launcher', 'launcher-v0.36.0', 77, \
         1, 'launcher-v') RETURNING id",
    )
    .fetch_one(&app.db)
    .await
    .unwrap();
    let music = sqlx::query_scalar(
        "INSERT INTO tracked_apps (name, package_name, github_repo, latest_release_tag, \
         latest_release_asset_id, release_tag_prefix) \
         VALUES ('Musikk', 'me.vibb.music', 'palchrb/vibb-launcher', 'music-v0.6.0', 78, \
         'music-v') RETURNING id",
    )
    .fetch_one(&app.db)
    .await
    .unwrap();
    (launcher, music)
}

/// A status report from a phone running the launcher's `code` (named `name`) and the music app's.
async fn report(app: &TestApp, token: &str, name: &str, code: i64, music: Option<i64>) {
    let mut body = json!({
        "lock_reason": "NONE",
        "kiosk_engaged": true,
        "app_version": name,
        "app_version_code": code,
    });
    if let Some(music) = music {
        body["music_state"] = json!({"package": "me.vibb.music", "version_code": music});
    }
    let res = app
        .request(Method::POST, "/api/devices/status", Some(token), Some(body))
        .await;
    assert_eq!(res.status, StatusCode::NO_CONTENT, "{}", res.text());
}

/// `(device_id, kind, dedup_key, params)` of every app update event, oldest first.
async fn notices(app: &TestApp) -> Vec<(Option<i64>, String, String, Value)> {
    let rows: Vec<(Option<i64>, String, String, String)> = sqlx::query_as(
        "SELECT device_id, kind, dedup_key, params FROM parent_events \
         WHERE kind IN ('app_update_available', 'app_updated') ORDER BY id",
    )
    .fetch_all(&app.db)
    .await
    .unwrap();
    rows.into_iter()
        .map(|(d, k, key, p)| (d, k, key, serde_json::from_str(&p).unwrap()))
        .collect()
}

/// The titles the sender pushes now.
async fn pushed(app: &TestApp) -> Vec<String> {
    crate::push::send_due(&app.state, Utc::now()).await.unwrap();
    app.pushes
        .take()
        .iter()
        .map(|r| {
            payload(r)["notification"]["title"]
                .as_str()
                .unwrap()
                .to_string()
        })
        .collect()
}

/// One event per release, never per sweep: the same release stored again (the key is the release
/// as the sweep stores it) raises nothing; the next release is the second event. It is the
/// catalog's - no phone - and the inbox shows it under every phone, not under one phone's filter.
#[tokio::test]
async fn a_release_is_one_event_whatever_the_sweeps() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, _) = app.enrolled_device("Anna").await;
    let now = Utc::now();
    for _ in 0..3 {
        raise_logged(
            &app.state,
            &app_update_available(9, "Signal", "v7.2.0@41", Some("7.1.4"), "7.2.0", now),
        )
        .await;
    }
    raise_logged(
        &app.state,
        &app_update_available(9, "Signal", "v7.3.0@42", Some("7.2.0"), "7.3.0", now),
    )
    .await;
    let rows = notices(&app).await;
    assert_eq!(rows.len(), 2, "{rows:?}");
    assert_eq!(
        (rows[0].0, rows[0].1.as_str(), rows[0].2.as_str()),
        (None, "app_update_available", "9:v7.2.0@41")
    );
    assert_eq!(rows[0].3["app"], "Signal");
    assert_eq!(rows[0].3["from"], "7.1.4");
    assert_eq!(rows[0].3["to"], "7.2.0");
    // The inbox: the long words, a grey avatar with the app's letter.
    let html = app.get_page("/notifications", &cookie).await.text();
    assert!(html.contains("Signal 7.3.0 is available"), "{html}");
    assert!(html.contains("7.1.4 → 7.2.0"), "{html}");
    let filtered = app
        .get_page(&format!("/notifications?device={id}"), &cookie)
        .await
        .text();
    assert!(
        !filtered.contains("is available"),
        "a phone's filter has no catalog event"
    );
    // Its tap goes to the app's page.
    let target: String = sqlx::query_scalar(
        "SELECT target FROM parent_events WHERE kind = 'app_update_available' LIMIT 1",
    )
    .fetch_one(&app.db)
    .await
    .unwrap();
    assert_eq!(target, "/apps/tracked/9");
}

/// A phone's report with the launcher's stored release installed raises `app_updated` once for
/// that phone and version; the same code again, a code that isn't the release, and a phone's
/// first report raise nothing. The music app likewise by its versionCode.
#[tokio::test]
async fn a_report_with_the_new_release_raises_app_updated_once() {
    let app = TestApp::new().await;
    let (launcher, music) = catalog(&app).await;
    let (anna, anna_token) = app.enrolled_device("Anna").await;
    let (_, ola_token) = app.enrolled_device("Ola").await;
    // A first report is no update, whatever it runs.
    report(&app, &anna_token, "0.36.0", 360_009, Some(60_009)).await;
    assert!(notices(&app).await.is_empty());
    // qa-pwa-fixes-b-code Q-2: a phone ahead of the release (0.37.0-rc.1) that goes back to it
    // is a downgrade, and a report naming the music app after one that didn't is its first: no
    // event for either.
    report(&app, &anna_token, "0.37.0-rc.1", 370_001, None).await;
    report(&app, &anna_token, "0.36.0", 360_009, Some(60_009)).await;
    assert!(notices(&app).await.is_empty(), "{:?}", notices(&app).await);
    report(&app, &ola_token, "0.35.2", 350_029, Some(50_009)).await;
    // rc.1 of 0.36.0 isn't the stored release.
    report(&app, &ola_token, "0.36.0-rc.1", 360_001, Some(50_009)).await;
    assert!(notices(&app).await.is_empty());
    for _ in 0..2 {
        report(&app, &ola_token, "0.36.0", 360_009, Some(60_009)).await;
    }
    let rows = notices(&app).await;
    assert_eq!(rows.len(), 2, "{rows:?}");
    let ola: i64 = sqlx::query_scalar("SELECT id FROM devices WHERE name = 'Ola'")
        .fetch_one(&app.db)
        .await
        .unwrap();
    assert_ne!(ola, anna);
    assert_eq!(
        (rows[0].0, rows[0].1.as_str(), rows[0].2.clone()),
        (Some(ola), "app_updated", format!("{launcher}:360009"))
    );
    assert_eq!(rows[0].3["from"], "0.36.0-rc.1");
    assert_eq!(rows[0].3["to"], "0.36.0");
    assert_eq!(rows[0].3["phone"], "Ola");
    assert_eq!(rows[1].2, format!("{music}:60009"));
    assert_eq!(rows[1].3["from"], "0.5.0");
    assert_eq!(rows[1].3["to"], "0.6.0");
    // Activity on Ola's summary shows it without the row's own clock.
    let cookie = app.admin_cookie().await;
    let html = app
        .get_page(&format!("/devices/{ola}"), &cookie)
        .await
        .text();
    assert!(html.contains("Vibb One updated on Ola"), "{html}");
}

/// "App updates" is off by default - for a parent with no Notify Me About row too -: the events
/// are in the inbox, nothing is pushed; turned on, the next one is pushed in its short words.
#[tokio::test]
async fn app_updates_are_pushed_only_with_the_switch_on() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    subscribe(&app, &cookie, ENDPOINT).await;
    let now = Utc::now();
    raise_logged(
        &app.state,
        &app_update_available(9, "Signal", "v7.2.0@41", Some("7.1.4"), "7.2.0", now),
    )
    .await;
    assert!(pushed(&app).await.is_empty(), "off by default");
    let settings = app
        .get_page("/notifications/settings", &cookie)
        .await
        .text();
    let switch = settings
        .split("name=\"push_app_updates\"")
        .nth(1)
        .expect("the switch is listed")
        .split('>')
        .next()
        .unwrap()
        .to_string();
    assert!(!switch.contains("checked"), "{switch}");
    assert!(settings.contains("App updates"));
    // Every other switch is still on for a parent with no row.
    assert!(
        settings
            .split("name=\"push_low_battery\"")
            .nth(1)
            .unwrap()
            .split('>')
            .next()
            .unwrap()
            .contains("checked")
    );
    let on: Vec<(&str, &str)> = crate::parent_events::Kind::switches()
        .into_iter()
        .map(|(name, _)| (name, "on"))
        .collect();
    app.request_form(Method::POST, "/notifications/settings", Some(&cookie), &on)
        .await;
    raise_logged(
        &app.state,
        &app_update_available(9, "Signal", "v7.3.0@42", Some("7.2.0"), "7.3.0", now),
    )
    .await;
    assert_eq!(pushed(&app).await, ["Signal: update available"]);
}

/// Migration 0099 rebuilds `parent_events` (its `device_id` nullable) with its two children: every
/// event, delivery and read survives with its id, the id counters keep the highest id ever given,
/// the cascades still work, and a catalog event's key is unique.
#[tokio::test]
async fn migration_0099_keeps_the_events_their_deliveries_and_reads() {
    let dir = tempfile::tempdir().unwrap();
    let options = sqlx::sqlite::SqliteConnectOptions::new()
        .filename(dir.path().join("m.db"))
        .create_if_missing(true)
        .foreign_keys(true);
    let db = sqlx::SqlitePool::connect_with(options).await.unwrap();
    for migration in crate::MIGRATOR.iter().filter(|m| m.version <= 98) {
        sqlx::raw_sql(&migration.sql).execute(&db).await.unwrap();
    }
    sqlx::raw_sql(
        "INSERT INTO admin_users (id, username, password_hash) VALUES (1, 'mamma', 'x'); \
         INSERT INTO devices (id, name) VALUES (1, 'Anna'); \
         INSERT INTO parent_events (id, device_id, kind, dedup_key, title, summary, target, \
           happened_at, push_title, push_body) VALUES \
           (7, 1, 'low_battery', 'a', 't', 's', '/', '2026-10-22T10:00:00.000Z', 'pt', 'pb'), \
           (9, 1, 'phone_on', 'b', 't2', 's2', '/', '2026-10-22T11:00:00.000Z', NULL, NULL); \
         DELETE FROM parent_events WHERE id = 9; \
         INSERT INTO parent_events (id, device_id, kind, dedup_key, title, summary, target, \
           happened_at) VALUES (8, 1, 'pin_used', 'c', 't3', 's3', '/', '2026-10-22T12:00:00.000Z'); \
         INSERT INTO push_subscriptions (id, admin_id, endpoint, p256dh, auth, origin, label, vapid_fp) \
           VALUES (1, 1, 'https://web.push.apple.com/x', X'00', X'00', 'https://h', 'l', 'f'); \
         INSERT INTO push_deliveries (id, subscription_id, event_id, kind, token_hash, status) \
           VALUES (5, 1, 7, 'event', X'01', 'accepted'), (6, 1, 8, 'event', X'02', 'queued'); \
         INSERT INTO parent_event_reads (admin_id, event_id) VALUES (1, 7);",
    )
    .execute(&db)
    .await
    .unwrap();
    let last = crate::MIGRATOR.iter().find(|m| m.version == 99).unwrap();
    let mut tx = db.begin().await.unwrap();
    sqlx::raw_sql(&last.sql).execute(&mut *tx).await.unwrap();
    tx.commit().await.unwrap();

    let events: Vec<(i64, Option<i64>, String, Option<String>)> =
        sqlx::query_as("SELECT id, device_id, title, push_title FROM parent_events ORDER BY id")
            .fetch_all(&db)
            .await
            .unwrap();
    assert_eq!(
        events,
        [
            (7, Some(1), "t".to_string(), Some("pt".to_string())),
            (8, Some(1), "t3".to_string(), None)
        ]
    );
    let deliveries: Vec<(i64, Option<i64>, String)> =
        sqlx::query_as("SELECT id, event_id, status FROM push_deliveries ORDER BY id")
            .fetch_all(&db)
            .await
            .unwrap();
    assert_eq!(
        deliveries,
        [
            (5, Some(7), "accepted".to_string()),
            (6, Some(8), "queued".to_string())
        ]
    );
    let reads: Vec<(i64, i64)> =
        sqlx::query_as("SELECT admin_id, event_id FROM parent_event_reads")
            .fetch_all(&db)
            .await
            .unwrap();
    assert_eq!(reads, [(1, 7)]);
    // The counter keeps the pruned 9: the next event is 10, never 9 again.
    let next: i64 = sqlx::query_scalar(
        "INSERT INTO parent_events (device_id, kind, dedup_key, title, summary, target, happened_at) \
         VALUES (NULL, 'app_update_available', '3:v1@1', '', '', '/', '2026-10-25T10:00:00.000Z') \
         RETURNING id",
    )
    .fetch_one(&db)
    .await
    .unwrap();
    assert_eq!(next, 10);
    // A catalog event's key is unique; a phone's still is.
    let again = sqlx::query(
        "INSERT INTO parent_events (device_id, kind, dedup_key, title, summary, target, happened_at) \
         VALUES (NULL, 'app_update_available', '3:v1@1', '', '', '/', '2026-10-25T10:00:00.000Z') \
         ON CONFLICT DO NOTHING",
    )
    .execute(&db)
    .await
    .unwrap();
    assert_eq!(again.rows_affected(), 0);
    let delivery: i64 = sqlx::query_scalar(
        "INSERT INTO push_deliveries (subscription_id, event_id, kind, token_hash) \
         VALUES (1, 10, 'event', X'03') RETURNING id",
    )
    .fetch_one(&db)
    .await
    .unwrap();
    assert_eq!(delivery, 7);
    // The cascades: a deleted event takes its reads and lets go of its deliveries; a deleted
    // phone takes its events.
    sqlx::query("DELETE FROM parent_events WHERE id = 7")
        .execute(&db)
        .await
        .unwrap();
    let reads: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM parent_event_reads")
        .fetch_one(&db)
        .await
        .unwrap();
    assert_eq!(reads, 0);
    let orphan: Option<i64> =
        sqlx::query_scalar("SELECT event_id FROM push_deliveries WHERE id = 5")
            .fetch_one(&db)
            .await
            .unwrap();
    assert_eq!(orphan, None);
    sqlx::query("DELETE FROM devices WHERE id = 1")
        .execute(&db)
        .await
        .unwrap();
    let left: Vec<String> = sqlx::query_scalar("SELECT kind FROM parent_events")
        .fetch_all(&db)
        .await
        .unwrap();
    assert_eq!(left, ["app_update_available"]);
    let fk: Vec<(String,)> = sqlx::query_as("SELECT \"table\" FROM pragma_foreign_key_check")
        .fetch_all(&db)
        .await
        .unwrap();
    assert!(fk.is_empty(), "{fk:?}");
    let switch: String = sqlx::query_scalar(
        "SELECT dflt_value FROM pragma_table_info('notification_prefs') WHERE name = 'push_app_updates'",
    )
    .fetch_one(&db)
    .await
    .unwrap();
    assert_eq!(switch, "0");
}

/// A report naming each listed app's installed version (PLAN "Installed versionCode per catalog
/// app in the status report"; `version_code`/`version_name` in `installed_apps`).
async fn report_apps(app: &TestApp, token: &str, apps: Value) {
    let body = json!({
        "lock_reason": "NONE",
        "kiosk_engaged": true,
        "app_version": "0.36.0",
        "app_version_code": 360_009,
        "installed_apps": apps,
    });
    let res = app
        .request(Method::POST, "/api/devices/status", Some(token), Some(body))
        .await;
    assert_eq!(res.status, StatusCode::NO_CONTENT, "{}", res.text());
}

/// Every catalog app checked for the phone gets its `app_updated` from the listed versionCode: a
/// GitHub app when the new versionName is the stored tag's version, an F-Droid app when the new
/// code is the stored release's; once per version. An app not checked for the phone, a version
/// that isn't the stored release, and an older launcher's entries without the fields raise
/// nothing - and such a report is still taken.
#[tokio::test]
async fn every_checked_catalog_app_raises_app_updated_by_its_listed_code() {
    let app = TestApp::new().await;
    let (anna, token) = app.enrolled_device("Anna").await;
    let mut ids = Vec::new();
    for (name, package, source, tag, asset) in [
        (
            "Signal",
            "org.thoughtcrime.securesms",
            "github",
            "v7.2.0",
            41,
        ),
        ("Etar", "ws.xsoh.etar", "fdroid", "1.0.57", 57),
        ("Unchecked", "org.example.unchecked", "github", "v2.0.0", 3),
    ] {
        let id: i64 = sqlx::query_scalar(
            "INSERT INTO tracked_apps (name, package_name, github_repo, source_type, \
             latest_release_tag, latest_release_asset_id) VALUES (?, ?, 'o/r', ?, ?, ?) RETURNING id",
        )
        .bind(name)
        .bind(package)
        .bind(source)
        .bind(tag)
        .bind(asset)
        .fetch_one(&app.db)
        .await
        .unwrap();
        ids.push(id);
    }
    for id in &ids[..2] {
        sqlx::query("INSERT INTO device_tracked_apps (device_id, tracked_app_id) VALUES (?, ?)")
            .bind(anna)
            .bind(id)
            .execute(&app.db)
            .await
            .unwrap();
    }
    let listed = |signal: (i64, &str), etar: i64, other: (i64, &str)| {
        json!([
            {"package_name": "org.thoughtcrime.securesms", "label": "Signal", "preinstalled": false,
             "version_code": signal.0, "version_name": signal.1},
            {"package_name": "ws.xsoh.etar", "label": "Etar", "preinstalled": false,
             "version_code": etar, "version_name": "1.0.x"},
            {"package_name": "org.example.unchecked", "label": "U", "preinstalled": false,
             "version_code": other.0, "version_name": other.1},
        ])
    };
    // An older launcher's report (no fields), then the first with them: no update yet.
    report_apps(
        &app,
        &token,
        json!([{"package_name": "org.thoughtcrime.securesms", "label": "Signal", "preinstalled": false}]),
    )
    .await;
    report_apps(&app, &token, listed((1_400, "7.1.4"), 56, (1, "1.0.0"))).await;
    assert!(notices(&app).await.is_empty());
    // A higher code that isn't the stored release (a Play beta) is no update; nor is the same code.
    for _ in 0..2 {
        report_apps(
            &app,
            &token,
            listed((1_401, "7.1.5-beta"), 56, (1, "1.0.0")),
        )
        .await;
    }
    assert!(notices(&app).await.is_empty(), "{:?}", notices(&app).await);
    // The stored releases installed, twice: one event each, the unchecked app none.
    for _ in 0..2 {
        report_apps(&app, &token, listed((1_500, "v7.2.0"), 57, (2, "2.0.0"))).await;
    }
    let rows = notices(&app).await;
    assert_eq!(rows.len(), 2, "{rows:?}");
    assert_eq!(
        (rows[0].0, rows[0].1.as_str(), rows[0].2.clone()),
        (Some(anna), "app_updated", format!("{}:1500", ids[0]))
    );
    assert_eq!(rows[0].3["app"], "Signal");
    assert_eq!(rows[0].3["from"], "7.1.5-beta");
    assert_eq!(rows[0].3["to"], "7.2.0");
    assert_eq!(rows[1].2, format!("{}:57", ids[1]));
    assert_eq!(rows[1].3["to"], "1.0.57");
    // The intake keeps the fields with the list, a versionName trimmed and cut to 60; a code that
    // isn't a non-negative integer and a name that isn't text are none.
    report_apps(
        &app,
        &token,
        json!([
            {"package_name": "org.thoughtcrime.securesms", "label": "Signal", "preinstalled": false,
             "version_code": "x", "version_name": format!("  {}  ", "9".repeat(80))},
            {"package_name": "ws.xsoh.etar", "label": "Etar", "preinstalled": false,
             "version_code": -3, "version_name": 7},
        ]),
    )
    .await;
    let stored: String = sqlx::query_scalar(
        "SELECT installed_apps_json FROM device_status WHERE device_id = ? ORDER BY id DESC LIMIT 1",
    )
    .bind(anna)
    .fetch_one(&app.db)
    .await
    .unwrap();
    let stored: Value = serde_json::from_str(&stored).unwrap();
    assert_eq!(stored[0]["version_name"], "9".repeat(60));
    assert!(
        stored[0].get("version_code").is_none(),
        "a string is no code: {stored}"
    );
    assert!(stored[1].get("version_code").is_none(), "{stored}");
    assert!(stored[1].get("version_name").is_none(), "{stored}");
}

/// A catalog row for Signal on `v7.2.0`, checked for `device`; its id.
async fn checked_signal(app: &TestApp, device: i64) -> i64 {
    let id: i64 = sqlx::query_scalar(
        "INSERT INTO tracked_apps (name, package_name, github_repo, source_type, \
         latest_release_tag, latest_release_asset_id) \
         VALUES ('Signal', 'org.thoughtcrime.securesms', 'o/r', 'github', 'v7.2.0', 41) RETURNING id",
    )
    .fetch_one(&app.db)
    .await
    .unwrap();
    sqlx::query("INSERT INTO device_tracked_apps (device_id, tracked_app_id) VALUES (?, ?)")
        .bind(device)
        .bind(id)
        .execute(&app.db)
        .await
        .unwrap();
    id
}

/// qa-launcher-small-a-code Q-4: the listed code must be higher than the previous report's. A
/// phone whose checked app already runs the stored release when it updates to a launcher that
/// sends codes reports it twice in a row (the first with codes has nothing to compare, the second
/// the same code): no "updated"; a step back from a higher build onto the release is no update
/// either.
#[tokio::test]
async fn an_app_already_at_the_release_or_going_back_to_it_raises_nothing() {
    let app = TestApp::new().await;
    let (anna, token) = app.enrolled_device("Anna").await;
    checked_signal(&app, anna).await;
    let signal = |code: i64, name: &str| {
        json!([{"package_name": "org.thoughtcrime.securesms", "label": "Signal",
                "preinstalled": false, "version_code": code, "version_name": name}])
    };
    // An older launcher's report, then the update's first two reports at the release.
    report_apps(
        &app,
        &token,
        json!([{"package_name": "org.thoughtcrime.securesms", "label": "Signal", "preinstalled": false}]),
    )
    .await;
    for _ in 0..2 {
        report_apps(&app, &token, signal(1_500, "7.2.0")).await;
    }
    assert!(notices(&app).await.is_empty(), "{:?}", notices(&app).await);
    // A Play beta above the release, then back down onto it.
    report_apps(&app, &token, signal(1_600, "7.3.0-beta1")).await;
    report_apps(&app, &token, signal(1_500, "7.2.0")).await;
    assert!(notices(&app).await.is_empty(), "{:?}", notices(&app).await);
}

/// qa-launcher-small-a-code Q-5: the launcher's status body with the installed versions, shared
/// with its test resources (`SyncScheduleTest` encodes exactly it).
const INSTALLED_APPS_REPORT: &str = include_str!("../../testdata/installed_apps_report.json");

#[test]
fn the_installed_apps_contract_matches_the_launchers_copy() {
    let copy_path = std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("../launcher/app/src/test/resources/installed_apps_report.json");
    let copy = std::fs::read_to_string(&copy_path)
        .unwrap_or_else(|e| panic!("{}: {e}", copy_path.display()));
    assert_eq!(
        copy,
        INSTALLED_APPS_REPORT,
        "{} differs",
        copy_path.display()
    );
}

/// The contract posted as is, after a report of the same apps one release back: the checked
/// app's `app_updated`, with the fields stored as the launcher sent them.
#[tokio::test]
async fn the_installed_apps_contract_raises_app_updated() {
    let app = TestApp::new().await;
    let (anna, token) = app.enrolled_device("Anna").await;
    let signal = checked_signal(&app, anna).await;
    let contract: Value = serde_json::from_str(INSTALLED_APPS_REPORT).unwrap();
    let mut before = contract.clone();
    before["installed_apps"][0]["version_code"] = json!(1_400);
    before["installed_apps"][0]["version_name"] = json!("7.1.4");
    for body in [before, contract] {
        let res = app
            .request(
                Method::POST,
                "/api/devices/status",
                Some(&token),
                Some(body),
            )
            .await;
        assert_eq!(res.status, StatusCode::NO_CONTENT, "{}", res.text());
    }
    let rows = notices(&app).await;
    assert_eq!(rows.len(), 1, "{rows:?}");
    assert_eq!(
        (rows[0].0, rows[0].1.as_str(), rows[0].2.clone()),
        (Some(anna), "app_updated", format!("{signal}:1500"))
    );
    assert_eq!(rows[0].3["from"], "7.1.4");
    assert_eq!(rows[0].3["to"], "7.2.0");
    let stored: String = sqlx::query_scalar(
        "SELECT installed_apps_json FROM device_status WHERE device_id = ? ORDER BY id DESC LIMIT 1",
    )
    .bind(anna)
    .fetch_one(&app.db)
    .await
    .unwrap();
    let stored: Value = serde_json::from_str(&stored).unwrap();
    assert_eq!(stored[0]["version_code"], 1_500);
    assert_eq!(stored[0]["version_name"], "7.2.0");
    assert!(stored[1].get("version_code").is_none(), "{stored}");
}
