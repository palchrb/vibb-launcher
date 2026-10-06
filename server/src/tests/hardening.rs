//! Hardening (design 04-hardening.md): the per-device restriction switches in the device policy,
//! the device page's "Phone hardening" form, and the corrupt-allowlist warning (QA step 1 #11).

use axum::http::{Method, StatusCode};
use serde_json::{Value, json};

use super::TestApp;

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
    assert_eq!(res.location(), Some(format!("/devices/{id}").as_str()));
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
async fn device_page_shows_the_switches() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, _) = app.enrolled_device("phone").await;
    let page = app.get_page(&format!("/devices/{id}"), &cookie).await;
    assert_eq!(page.status, StatusCode::OK);
    let html = page.text();
    assert!(html.contains(&format!("action=\"/devices/{id}/hardening\"")));
    assert!(html.contains(
        "name=\"disallow_debugging_features\" value=\"on\" checked onchange=\"this.form.submit()\""
    ));
    assert!(
        html.contains("name=\"disallow_safe_boot\" value=\"on\"  onchange=\"this.form.submit()\"")
    );
    assert!(
        html.contains(
            "name=\"disallow_airplane_mode\" value=\"on\"  onchange=\"this.form.submit()\""
        )
    );
    assert!(!html.contains("stored app list for this device can't be read"));
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
    // ... and the parent sees why.
    let page = app.get_page(&format!("/devices/{id}"), &cookie).await;
    assert_eq!(page.status, StatusCode::OK);
    assert!(
        page.text()
            .contains("stored app list for this device can't be read"),
        "{}",
        page.text()
    );
}

#[tokio::test]
async fn managed_device_without_pin_is_warned() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, _) = app.enrolled_device("phone").await;
    let warning = "No unlock code (offline override PIN) is set";
    // Unmanaged (no allowlist, calls unmanaged): nothing to warn about yet.
    let page = app.get_page(&format!("/devices/{id}"), &cookie).await;
    assert!(!page.text().contains(warning));

    sqlx::query("UPDATE device_policy SET allowlist_json = '[]' WHERE device_id = ?")
        .bind(id)
        .execute(&app.db)
        .await
        .unwrap();
    let page = app.get_page(&format!("/devices/{id}"), &cookie).await;
    assert!(page.text().contains(warning));
    assert!(page.text().contains("with USB debugging blocked"));

    sqlx::query(
        "UPDATE device_policy SET override_pin_hash = 'aa', override_pin_salt = 'bb' \
         WHERE device_id = ?",
    )
    .bind(id)
    .execute(&app.db)
    .await
    .unwrap();
    let page = app.get_page(&format!("/devices/{id}"), &cookie).await;
    assert!(!page.text().contains(warning));
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
