//! Design 108 (Vibb as the UnifiedPush distributor): the shared contract `testdata/unified_push.json` - the policy's
//! `unified_push` rows from seeded data, the copies identical.

use serde_json::{Value, json};

use super::TestApp;
use crate::unified_push::CAPABILITY;

const CONTRACT: &str = include_str!("../../testdata/unified_push.json");

fn contract() -> Value {
    serde_json::from_str(CONTRACT).unwrap()
}

#[test]
fn the_contract_is_identical_to_the_launchers_copy() {
    let launcher = std::fs::read_to_string(concat!(
        env!("CARGO_MANIFEST_DIR"),
        "/../launcher/app/src/test/resources/unified_push.json"
    ))
    .unwrap();
    assert_eq!(launcher, CONTRACT, "the launcher's copy differs");
    assert_eq!(contract()["capability"], CAPABILITY);
}

async fn set_origin(app: &TestApp, origin: Option<&str>) {
    sqlx::query("UPDATE unified_push_settings SET public_origin = ? WHERE id = 1")
        .bind(origin)
        .execute(&app.db)
        .await
        .unwrap();
}

async fn seed_registration(app: &TestApp, device: i64, reg: &str, secret: &str, package: &str) {
    sqlx::query(
        "INSERT INTO up_registrations (device_id, reg, secret_hash, package) VALUES (?, ?, ?, ?)",
    )
    .bind(device)
    .bind(reg)
    .bind(crate::unified_push::secret_hash(secret))
    .bind(package)
    .execute(&app.db)
    .await
    .unwrap();
}

/// The policy rows: none for a launcher without the capability; off without an address; on with it, the regs and the
/// stopped apps; a changed address.
#[tokio::test]
async fn the_policy_rows() {
    let app = TestApp::new().await;
    let (id, token) = app.enrolled_device("Emma").await;
    assert!(app.policy(&token).await.get("unified_push").is_none());
    app.post_status(&token, json!({"capabilities": [CAPABILITY]}))
        .await;
    let rows = &contract()["policy"];
    let served = |policy: Value| policy["unified_push"].clone();
    assert_eq!(served(app.policy(&token).await), rows["off"]);
    set_origin(&app, Some("https://phones.example.com")).await;
    assert_eq!(served(app.policy(&token).await), rows["on_empty"]);
    let body = &contract()["register"];
    for key in ["body", "body_second"] {
        let row = &body[key];
        seed_registration(
            &app,
            id,
            row["reg"].as_str().unwrap(),
            row["secret"].as_str().unwrap(),
            row["package"].as_str().unwrap(),
        )
        .await;
    }
    assert_eq!(served(app.policy(&token).await), rows["on_two"]);
    set_origin(&app, Some("https://push.example.net")).await;
    assert_eq!(served(app.policy(&token).await), rows["origin_changed"]);
    set_origin(&app, Some("https://phones.example.com")).await;
    sqlx::query("DELETE FROM up_registrations WHERE package = 'org.unifiedpush.example'")
        .execute(&app.db)
        .await
        .unwrap();
    sqlx::query(
        "INSERT INTO up_stopped (device_id, package) VALUES (?, 'org.unifiedpush.example')",
    )
    .bind(id)
    .execute(&app.db)
    .await
    .unwrap();
    assert_eq!(served(app.policy(&token).await), rows["stopped"]);
}
