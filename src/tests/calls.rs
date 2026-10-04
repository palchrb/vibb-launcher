//! Calls & SMS: `call_policy` in the device policy, the admin calls page and its forms, and the
//! call fields of the status report (design 02-calls.md, QA 02 criteria T2/T4).

use axum::http::{Method, StatusCode};
use serde_json::{Value, json};

use super::TestApp;

async fn policy(app: &TestApp, token: &str) -> Value {
    let res = app
        .request(Method::GET, "/api/devices/policy", Some(token), None)
        .await;
    assert_eq!(res.status, StatusCode::OK);
    res.json()
}

async fn set_managed(app: &TestApp, device_id: i64, calls: bool, sms: bool) {
    sqlx::query(
        "UPDATE device_policy SET calls_managed = 1, calls_enabled = ?, sms_enabled = ? \
         WHERE device_id = ?",
    )
    .bind(calls)
    .bind(sms)
    .bind(device_id)
    .execute(&app.db)
    .await
    .unwrap();
}

/// Inserts a contact (already normalised) and attaches it to a device.
async fn attach(
    app: &TestApp,
    device_id: i64,
    name: &str,
    number: &str,
    flags: [bool; 3],
    sort: i64,
) -> i64 {
    let id: i64 = sqlx::query_scalar(
        "INSERT INTO contacts (name, phone_number) VALUES (?, ?) \
         ON CONFLICT(phone_number) DO UPDATE SET name = excluded.name RETURNING id",
    )
    .bind(name)
    .bind(number)
    .fetch_one(&app.db)
    .await
    .unwrap();
    sqlx::query(
        "INSERT INTO device_contacts \
         (device_id, contact_id, allow_inbound, allow_outbound, show_on_home, sort_order) \
         VALUES (?, ?, ?, ?, ?, ?)",
    )
    .bind(device_id)
    .bind(id)
    .bind(flags[0])
    .bind(flags[1])
    .bind(flags[2])
    .bind(sort)
    .execute(&app.db)
    .await
    .unwrap();
    id
}

#[tokio::test]
async fn unmanaged_policy_has_explicit_managed_false() {
    let app = TestApp::new().await;
    let (id, token) = app.enrolled_device("phone").await;
    // Contacts attached to an unmanaged device are not sent.
    attach(&app, id, "Mamma", "+4791234567", [true, true, true], 0).await;

    let call_policy = &policy(&app, &token).await["call_policy"];
    assert_eq!(call_policy["managed"], json!(false));
    assert_eq!(call_policy["contacts"], json!([]));
    assert_eq!(call_policy["default_country_code"], json!("47"));
}

#[tokio::test]
async fn managed_policy_lists_contacts_with_flags_in_order() {
    let app = TestApp::new().await;
    let (id, token) = app.enrolled_device("phone").await;
    set_managed(&app, id, true, false).await;
    let pappa = attach(&app, id, "Pappa", "+4790000002", [true, false, false], 1).await;
    let mamma = attach(&app, id, "Mamma", "+4790000001", [true, true, true], 0).await;
    sqlx::query(
        "UPDATE device_contacts SET message_app = 'element', message_address = '@mamma:example.org' \
         WHERE contact_id = ?",
    )
    .bind(mamma)
    .execute(&app.db)
    .await
    .unwrap();

    let call_policy = policy(&app, &token).await["call_policy"].clone();
    assert_eq!(call_policy["managed"], json!(true));
    assert_eq!(call_policy["calls_enabled"], json!(true));
    assert_eq!(call_policy["sms_enabled"], json!(false));
    assert_eq!(
        call_policy["contacts"],
        json!([
            {
                "id": mamma, "name": "Mamma", "number": "+4790000001",
                "inbound": true, "outbound": true, "show_on_home": true,
                "message_app": "element", "message_address": "@mamma:example.org"
            },
            {
                "id": pappa, "name": "Pappa", "number": "+4790000002",
                "inbound": true, "outbound": false, "show_on_home": false,
                // No per-contact choice: the device default.
                "message_app": "sms", "message_address": null
            }
        ])
    );
}

#[tokio::test]
async fn contacts_of_other_devices_are_not_sent() {
    let app = TestApp::new().await;
    let (a, token_a) = app.enrolled_device("a").await;
    let (b, _) = app.enrolled_device("b").await;
    set_managed(&app, a, true, true).await;
    set_managed(&app, b, true, true).await;
    attach(&app, b, "Stranger", "+4790000009", [true, true, true], 0).await;
    assert_eq!(
        policy(&app, &token_a).await["call_policy"]["contacts"],
        json!([])
    );
}

#[tokio::test]
async fn unreadable_call_settings_is_500() {
    let app = TestApp::new().await;
    let (id, token) = app.enrolled_device("phone").await;
    set_managed(&app, id, true, true).await;
    sqlx::query("DROP TABLE call_settings")
        .execute(&app.db)
        .await
        .unwrap();
    let res = app
        .request(Method::GET, "/api/devices/policy", Some(&token), None)
        .await;
    assert_eq!(res.status, StatusCode::INTERNAL_SERVER_ERROR);
    assert!(res.body.is_empty());
}
