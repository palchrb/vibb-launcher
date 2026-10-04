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

// ---------------------------------------------------------------------------------------------
// Admin forms, through the real session/2FA middleware (QA 02 criterion T4).
// ---------------------------------------------------------------------------------------------

async fn contact_rows(app: &TestApp) -> Vec<(i64, String, String)> {
    sqlx::query_as("SELECT id, name, phone_number FROM contacts ORDER BY id")
        .fetch_all(&app.db)
        .await
        .unwrap()
}

async fn add(
    app: &TestApp,
    cookie: &str,
    device_id: i64,
    name: &str,
    number: &str,
) -> super::TestResponse {
    app.request_form(
        Method::POST,
        &format!("/devices/{device_id}/contacts"),
        Some(cookie),
        &[("name", name), ("number", number)],
    )
    .await
}

#[tokio::test]
async fn contact_number_is_normalized_on_add() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, _) = app.enrolled_device("phone").await;

    let res = add(&app, &cookie, id, "Mamma", "912 34 567").await;
    assert_eq!(
        res.location(),
        Some(format!("/devices/{id}/calls").as_str())
    );
    let rows = contact_rows(&app).await;
    assert_eq!(rows.len(), 1);
    assert_eq!(rows[0].2, "+4791234567");

    // Defaults: every flag on, appended at the end.
    add(&app, &cookie, id, "Pappa", "+46 70 123 45 67").await;
    let flags: Vec<(bool, bool, bool, i64)> = sqlx::query_as(
        "SELECT allow_inbound, allow_outbound, show_on_home, sort_order FROM device_contacts \
         WHERE device_id = ? ORDER BY sort_order",
    )
    .bind(id)
    .fetch_all(&app.db)
    .await
    .unwrap();
    assert_eq!(flags, [(true, true, true, 0), (true, true, true, 1)]);

    let page = app.get_page(&format!("/devices/{id}/calls"), &cookie).await;
    assert_eq!(page.status, StatusCode::OK);
    assert!(page.text().contains("Mamma") && page.text().contains("+46701234567"));
}

#[tokio::test]
async fn invalid_contact_input_is_400_with_no_row() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, _) = app.enrolled_device("phone").await;

    for (name, number) in [
        ("Mamma", "*21*91234567#"),
        ("Mamma", "12"),
        ("Mamma", "abc"),
        ("", "91234567"),
        (&"x".repeat(61), "91234567"),
    ] {
        let res = add(&app, &cookie, id, name, number).await;
        assert_eq!(res.status, StatusCode::BAD_REQUEST, "{name:?} {number:?}");
        // The form is shown again with the input and an error.
        assert!(res.text().contains("class=\"error\""));
    }
    assert!(contact_rows(&app).await.is_empty());

    // Unknown device: nothing written either.
    let res = add(&app, &cookie, 9999, "Mamma", "91234567").await;
    assert_eq!(res.status, StatusCode::NOT_FOUND);
    assert!(contact_rows(&app).await.is_empty());
}

#[tokio::test]
async fn same_number_on_two_devices_shares_contact() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (a, _) = app.enrolled_device("a").await;
    let (b, _) = app.enrolled_device("b").await;

    add(&app, &cookie, a, "Mormor", "91234567").await;
    add(&app, &cookie, b, "Bestemor", "+47 912 34 567").await;
    let rows = contact_rows(&app).await;
    assert_eq!(rows.len(), 1, "one address-book entry per number");
    assert_eq!(rows[0].1, "Bestemor", "the name is shared, last write wins");
    let attached: i64 =
        sqlx::query_scalar("SELECT COUNT(*) FROM device_contacts WHERE contact_id = ?")
            .bind(rows[0].0)
            .fetch_one(&app.db)
            .await
            .unwrap();
    assert_eq!(attached, 2);
}

#[tokio::test]
async fn remove_detaches_and_deletes_orphan() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (a, _) = app.enrolled_device("a").await;
    let (b, _) = app.enrolled_device("b").await;
    add(&app, &cookie, a, "Mormor", "91234567").await;
    add(&app, &cookie, b, "Mormor", "91234567").await;
    let contact = contact_rows(&app).await[0].0;

    let remove = |device: i64| {
        let cookie = cookie.clone();
        let app = &app;
        async move {
            app.request_form(
                Method::POST,
                &format!("/devices/{device}/contacts/{contact}/remove"),
                Some(&cookie),
                &[],
            )
            .await
        }
    };
    assert!(remove(a).await.status.is_redirection());
    assert_eq!(contact_rows(&app).await.len(), 1, "still on b");
    // Already detached from a: 404, b keeps it.
    assert_eq!(remove(a).await.status, StatusCode::NOT_FOUND);
    assert_eq!(contact_rows(&app).await.len(), 1);
    assert!(remove(b).await.status.is_redirection());
    assert!(contact_rows(&app).await.is_empty(), "orphan deleted");
}

#[tokio::test]
async fn contact_flags_are_scoped_to_the_device() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (a, _) = app.enrolled_device("a").await;
    let (b, _) = app.enrolled_device("b").await;
    add(&app, &cookie, b, "Mormor", "91234567").await;
    let contact = contact_rows(&app).await[0].0;

    // Not attached to a.
    let res = app
        .request_form(
            Method::POST,
            &format!("/devices/{a}/contacts/{contact}"),
            Some(&cookie),
            &[("inbound", "on")],
        )
        .await;
    assert_eq!(res.status, StatusCode::NOT_FOUND);

    // On b: missing checkboxes are false; the message button is saved.
    let res = app
        .request_form(
            Method::POST,
            &format!("/devices/{b}/contacts/{contact}"),
            Some(&cookie),
            &[
                ("inbound", "on"),
                ("message_app", "element"),
                ("message_address", " @mormor:matrix.org "),
            ],
        )
        .await;
    assert!(res.status.is_redirection());
    let row: (bool, bool, bool, Option<String>, Option<String>) = sqlx::query_as(
        "SELECT allow_inbound, allow_outbound, show_on_home, message_app, message_address \
         FROM device_contacts WHERE device_id = ? AND contact_id = ?",
    )
    .bind(b)
    .bind(contact)
    .fetch_one(&app.db)
    .await
    .unwrap();
    assert_eq!(
        row,
        (
            true,
            false,
            false,
            Some("element".to_string()),
            Some("@mormor:matrix.org".to_string())
        )
    );

    // An invalid Matrix ID or app changes nothing.
    for fields in [
        [("message_app", "element"), ("message_address", "mormor")],
        [("message_app", "whatsapp"), ("message_address", "")],
    ] {
        let res = app
            .request_form(
                Method::POST,
                &format!("/devices/{b}/contacts/{contact}"),
                Some(&cookie),
                &fields,
            )
            .await;
        assert_eq!(res.status, StatusCode::BAD_REQUEST);
    }
    let address: Option<String> = sqlx::query_scalar(
        "SELECT message_address FROM device_contacts WHERE device_id = ? AND contact_id = ?",
    )
    .bind(b)
    .bind(contact)
    .fetch_one(&app.db)
    .await
    .unwrap();
    assert_eq!(address.as_deref(), Some("@mormor:matrix.org"));
}

#[tokio::test]
async fn settings_form_missing_checkbox_means_false() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("phone").await;
    let uri = format!("/devices/{id}/calls/settings");

    let res = app
        .request_form(
            Method::POST,
            &uri,
            Some(&cookie),
            &[
                ("managed", "on"),
                ("calls_enabled", "on"),
                ("default_message_app", "signal"),
            ],
        )
        .await;
    assert!(res.status.is_redirection());
    let call_policy = policy(&app, &token).await["call_policy"].clone();
    assert_eq!(call_policy["managed"], json!(true));
    assert_eq!(call_policy["calls_enabled"], json!(true));
    assert_eq!(call_policy["sms_enabled"], json!(false));
    let default_app: String =
        sqlx::query_scalar("SELECT default_message_app FROM device_policy WHERE device_id = ?")
            .bind(id)
            .fetch_one(&app.db)
            .await
            .unwrap();
    assert_eq!(default_app, "signal");

    // Everything unchecked: unmanaged, explicitly.
    app.request_form(Method::POST, &uri, Some(&cookie), &[])
        .await;
    let call_policy = policy(&app, &token).await["call_policy"].clone();
    assert_eq!(call_policy["managed"], json!(false));

    let res = app
        .request_form(
            Method::POST,
            &uri,
            Some(&cookie),
            &[("default_message_app", "whatsapp")],
        )
        .await;
    assert_eq!(res.status, StatusCode::BAD_REQUEST);
    let res = app
        .request_form(
            Method::POST,
            "/devices/9999/calls/settings",
            Some(&cookie),
            &[],
        )
        .await;
    assert_eq!(res.status, StatusCode::NOT_FOUND);
}

#[tokio::test]
async fn calls_forms_need_an_admin_session() {
    let app = TestApp::new().await;
    let (id, _) = app.enrolled_device("phone").await;
    let res = app
        .request_form(
            Method::POST,
            &format!("/devices/{id}/contacts"),
            None,
            &[("name", "Mamma"), ("number", "91234567")],
        )
        .await;
    assert!(res.status.is_redirection());
    assert!(contact_rows(&app).await.is_empty());
}

#[tokio::test]
async fn writes_nudge_device() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, _) = app.enrolled_device("phone").await;
    let mut nudges = app.state.command_notify.subscribe();

    add(&app, &cookie, id, "Mamma", "91234567").await;
    assert_eq!(nudges.try_recv().ok(), Some(id));
    let contact = contact_rows(&app).await[0].0;
    for (uri, fields) in [
        (
            format!("/devices/{id}/calls/settings"),
            vec![("managed", "on")],
        ),
        (
            format!("/devices/{id}/contacts/{contact}"),
            vec![("inbound", "on")],
        ),
        (format!("/devices/{id}/contacts/{contact}/remove"), vec![]),
    ] {
        let res = app
            .request_form(Method::POST, &uri, Some(&cookie), &fields)
            .await;
        assert!(res.status.is_redirection(), "{uri}");
        assert_eq!(nudges.try_recv().ok(), Some(id), "{uri}");
    }
    // A failed write doesn't nudge.
    add(&app, &cookie, id, "Mamma", "abc").await;
    assert!(nudges.try_recv().is_err());
}

#[tokio::test]
async fn default_country_code_is_validated_and_used() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("phone").await;

    for bad in ["4x", "0", "1234", ""] {
        let res = app
            .request_form(
                Method::POST,
                "/settings/calls",
                Some(&cookie),
                &[("default_country_code", bad)],
            )
            .await;
        assert_eq!(res.status, StatusCode::BAD_REQUEST, "{bad:?}");
    }
    let res = app
        .request_form(
            Method::POST,
            "/settings/calls",
            Some(&cookie),
            &[
                ("default_country_code", "+46"),
                ("device_id", &id.to_string()),
            ],
        )
        .await;
    assert_eq!(
        res.location(),
        Some(format!("/devices/{id}/calls").as_str())
    );
    assert_eq!(
        policy(&app, &token).await["call_policy"]["default_country_code"],
        json!("46")
    );
    add(&app, &cookie, id, "Farmor", "070-123 45 67").await;
    assert_eq!(contact_rows(&app).await[0].2, "+46701234567");
}

#[tokio::test]
async fn device_page_summarises_calls() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, _) = app.enrolled_device("phone").await;
    let page = app
        .get_page(&format!("/devices/{id}"), &cookie)
        .await
        .text();
    assert!(page.contains("Not managed"), "{page}");
    set_managed(&app, id, true, false).await;
    add(&app, &cookie, id, "Mamma", "91234567").await;
    let page = app
        .get_page(&format!("/devices/{id}"), &cookie)
        .await
        .text();
    assert!(
        page.contains("Managed - 1 contact, calls on, SMS off."),
        "{page}"
    );
    assert!(page.contains(&format!("/devices/{id}/calls")));
}

// ---------------------------------------------------------------------------------------------
// Status report: capabilities and call state, and the warnings built from them.
// ---------------------------------------------------------------------------------------------

async fn post_status(app: &TestApp, token: &str, extra: Value) {
    let mut report = json!({ "lock_reason": "none", "kiosk_engaged": true });
    for (key, value) in extra.as_object().unwrap() {
        report[key] = value.clone();
    }
    let res = app
        .request(
            Method::POST,
            "/api/devices/status",
            Some(token),
            Some(report),
        )
        .await;
    assert_eq!(res.status, StatusCode::NO_CONTENT);
}

fn good_call_state() -> Value {
    json!({
        "state": "managed",
        "dialer_role_held": true,
        "redirection_role_held": true,
        "default_dialer": "com.kidslauncher.mdm",
        "system_dialer": "com.android.dialer",
        "sms_restricted": false,
        "outgoing_restricted": false,
        "default_sms_package": "com.google.android.apps.messaging",
        "last_error": null
    })
}

async fn calls_page(app: &TestApp, cookie: &str, id: i64) -> String {
    let res = app.get_page(&format!("/devices/{id}/calls"), cookie).await;
    assert_eq!(res.status, StatusCode::OK);
    res.text()
}

#[tokio::test]
async fn status_stores_capabilities_and_call_state() {
    let app = TestApp::new().await;
    let (id, token) = app.enrolled_device("phone").await;
    post_status(
        &app,
        &token,
        json!({ "capabilities": ["call_policy_v1"], "call_state": good_call_state() }),
    )
    .await;
    let (caps, call_state): (Option<String>, Option<String>) = sqlx::query_as(
        "SELECT capabilities_json, call_state_json FROM device_status WHERE device_id = ?",
    )
    .bind(id)
    .fetch_one(&app.db)
    .await
    .unwrap();
    assert_eq!(caps.as_deref(), Some(r#"["call_policy_v1"]"#));
    let call_state: Value = serde_json::from_str(&call_state.unwrap()).unwrap();
    assert_eq!(call_state, good_call_state());

    // An older launcher sends neither; a non-object call_state is not stored.
    post_status(&app, &token, json!({ "call_state": "garbage" })).await;
    let (caps, call_state): (Option<String>, Option<String>) = sqlx::query_as(
        "SELECT capabilities_json, call_state_json FROM device_status WHERE device_id = ? \
         ORDER BY id DESC LIMIT 1",
    )
    .bind(id)
    .fetch_one(&app.db)
    .await
    .unwrap();
    assert_eq!((caps, call_state), (None, None));
}

#[tokio::test]
async fn calls_page_warns_without_capability() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("phone").await;
    set_managed(&app, id, true, true).await;

    post_status(&app, &token, json!({})).await;
    let page = calls_page(&app, &cookie, id).await;
    assert!(page.contains("does not enforce calls yet"), "{page}");

    post_status(
        &app,
        &token,
        json!({ "capabilities": ["call_policy_v1"], "call_state": good_call_state() }),
    )
    .await;
    let page = calls_page(&app, &cookie, id).await;
    assert!(!page.contains("Check this phone"), "{page}");

    // QA #22: a launcher that used to enforce calls and stops reporting it.
    post_status(&app, &token, json!({})).await;
    let page = calls_page(&app, &cookie, id).await;
    assert!(page.contains("stopped reporting"), "{page}");

    // Unmanaged: no capability warning.
    set_unmanaged(&app, id).await;
    assert!(
        !calls_page(&app, &cookie, id)
            .await
            .contains("Check this phone")
    );
}

async fn set_unmanaged(app: &TestApp, id: i64) {
    sqlx::query("UPDATE device_policy SET calls_managed = 0 WHERE device_id = ?")
        .bind(id)
        .execute(&app.db)
        .await
        .unwrap();
}

#[tokio::test]
async fn calls_page_shows_role_problems_and_fail_closed() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("phone").await;
    set_managed(&app, id, true, true).await;
    let mut call_state = good_call_state();
    call_state["state"] = json!("fail_closed");
    call_state["dialer_role_held"] = json!(false);
    call_state["redirection_role_held"] = json!(false);
    call_state["last_error"] = json!("setDefaultDialerApplication: IllegalArgumentException");
    post_status(
        &app,
        &token,
        json!({ "capabilities": ["call_policy_v1"], "call_state": call_state }),
    )
    .await;
    let page = calls_page(&app, &cookie, id).await;
    for expected in [
        "only emergency calls work",
        "Phone app role not active",
        "Call-redirection role not active",
        "setDefaultDialerApplication: IllegalArgumentException",
    ] {
        assert!(page.contains(expected), "{expected}: {page}");
    }
}

#[tokio::test]
async fn allowlisted_messages_app_with_sms_off_is_flagged() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("phone").await;
    set_managed(&app, id, true, false).await;
    sqlx::query("UPDATE device_policy SET allowlist_json = ? WHERE device_id = ?")
        .bind(r#"["com.google.android.apps.messaging"]"#)
        .bind(id)
        .execute(&app.db)
        .await
        .unwrap();
    post_status(
        &app,
        &token,
        json!({ "capabilities": ["call_policy_v1"], "call_state": good_call_state() }),
    )
    .await;
    assert!(
        calls_page(&app, &cookie, id)
            .await
            .contains("SMS is off, but the Messages app")
    );

    set_managed(&app, id, true, true).await;
    assert!(
        !calls_page(&app, &cookie, id)
            .await
            .contains("SMS is off, but")
    );
}

#[tokio::test]
async fn emergency_call_is_shown_on_the_device_page() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("phone").await;
    set_managed(&app, id, true, true).await;
    let now = chrono::Utc::now();
    let mut call_state = good_call_state();
    call_state["last_emergency_call_at"] = json!((now - chrono::Duration::minutes(5)).to_rfc3339());
    call_state["callback_window_until"] = json!((now + chrono::Duration::minutes(55)).to_rfc3339());
    post_status(
        &app,
        &token,
        json!({ "capabilities": ["call_policy_v1"], "call_state": call_state }),
    )
    .await;
    let page = app
        .get_page(&format!("/devices/{id}"), &cookie)
        .await
        .text();
    assert!(page.contains("An emergency call was made"), "{page}");
    assert!(page.contains("anyone can call this phone until"), "{page}");

    // Old ones are not alerted again.
    let mut call_state = good_call_state();
    call_state["last_emergency_call_at"] = json!((now - chrono::Duration::days(8)).to_rfc3339());
    call_state["callback_window_until"] = json!((now - chrono::Duration::days(8)).to_rfc3339());
    post_status(
        &app,
        &token,
        json!({ "capabilities": ["call_policy_v1"], "call_state": call_state }),
    )
    .await;
    let page = app
        .get_page(&format!("/devices/{id}"), &cookie)
        .await
        .text();
    assert!(!page.contains("emergency"), "{page}");
}
