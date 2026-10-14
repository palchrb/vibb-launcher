//! Calls & Contacts: `call_policy` in the device policy, the admin calls page and its forms, and
//! the call fields of the status report (design 02-calls.md, QA 02 criteria T2/T4; the page
//! rebuilt in design 31 step 8, the country code on Phone Setup).

use axum::http::{Method, StatusCode};
use serde_json::{Value, json};

use super::settings_pages::part;
use super::{TestApp, assert_redirect, tags};

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
                "message_app": "element", "message_address": "@mamma:example.org",
                "photo": null
            },
            {
                "id": pappa, "name": "Pappa", "number": "+4790000002",
                "inbound": true, "outbound": false, "show_on_home": false,
                // No per-contact choice: the device default.
                "message_app": "sms", "message_address": null,
                "photo": null
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
    let rows = contact_rows(&app).await;
    assert_eq!(rows.len(), 1);
    assert_eq!(rows[0].2, "+4791234567");
    // Back to the new contact's row (design 31 step 8).
    assert_redirect(
        &res,
        &format!("/devices/{id}/calls"),
        Some(&format!("contact-{}", rows[0].0)),
    );

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

    for (name, number, field) in [
        ("Mamma", "*21*91234567#", "contact_number"),
        ("Mamma", "12", "contact_number"),
        ("Mamma", "abc", "contact_number"),
        ("", "91234567", "contact_name"),
        (&"x".repeat(61), "91234567", "contact_name"),
    ] {
        let res = add(&app, &cookie, id, name, number).await;
        assert_eq!(res.status, StatusCode::BAD_REQUEST, "{name:?} {number:?}");
        // Add Contact open again, what was typed kept, the reason under its field (design 31
        // step 8).
        let html = res.text();
        let form = part(&html, "id=\"add-contact\"", "</details>");
        assert!(form.starts_with("id=\"add-contact\" open"), "{form}");
        assert!(form.contains(&format!("value=\"{number}\"")), "{form}");
        assert!(
            form.contains(&format!("id=\"{field}-error\"")),
            "{field}: {form}"
        );
        assert_eq!(form.matches("class=\"field-error\"").count(), 1);
    }
    assert!(contact_rows(&app).await.is_empty());

    // What was typed goes back into the fields escaped (qa-31-s8-code L3).
    let res = add(&app, &cookie, id, "\"><b>x", "\"><b>y").await;
    assert_eq!(res.status, StatusCode::BAD_REQUEST);
    let html = res.text();
    let form = part(&html, "id=\"add-contact\"", "</details>");
    assert!(form.contains("value=\"&#34;&#62;&#60;b&#62;x\""), "{form}");
    assert!(form.contains("value=\"&#34;&#62;&#60;b&#62;y\""), "{form}");
    assert!(!html.contains("\"><b>"), "{form}");
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
    assert_redirect(
        &remove(a).await,
        &format!("/devices/{a}/calls"),
        Some("contacts"),
    );
    assert_eq!(contact_rows(&app).await.len(), 1, "still on b");
    // Already detached from a (a second tap): back to the same group, b keeps it.
    assert_redirect(
        &remove(a).await,
        &format!("/devices/{a}/calls"),
        Some("contacts"),
    );
    assert_eq!(contact_rows(&app).await.len(), 1);
    assert!(remove(b).await.status.is_redirection());
    assert!(contact_rows(&app).await.is_empty(), "orphan deleted");
    // An unknown phone is a 404.
    assert_eq!(remove(9999).await.status, StatusCode::NOT_FOUND);
}

#[tokio::test]
async fn contact_flags_are_scoped_to_the_device() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (a, _) = app.enrolled_device("a").await;
    let (b, _) = app.enrolled_device("b").await;
    add(&app, &cookie, b, "Mormor", "91234567").await;
    let contact = contact_rows(&app).await[0].0;

    // Not attached to a: nothing written, back to a's list saying so (a contact removed in
    // another tab reads the same); an unknown phone is a 404.
    let res = app
        .request_form(
            Method::POST,
            &format!("/devices/{a}/contacts/{contact}"),
            Some(&cookie),
            &[("inbound", "on")],
        )
        .await;
    assert_redirect(
        &res,
        &format!("/devices/{a}/calls?notice=contact_gone"),
        Some("contacts"),
    );
    let html = app
        .get_page(&format!("/devices/{a}/calls?notice=contact_gone"), &cookie)
        .await
        .text();
    assert!(
        part(&html, "id=\"contacts\"", "</section>").contains("already removed"),
        "{html}"
    );
    // A refused Matrix ID for a contact not on the phone (removed in another tab) has no row to
    // open: the same landing, not a page that opens at its top (qa-31-s8-code N2).
    let res = app
        .request_form(
            Method::POST,
            &format!("/devices/{a}/contacts/{contact}"),
            Some(&cookie),
            &[("message_address", "not-an-id")],
        )
        .await;
    assert_redirect(
        &res,
        &format!("/devices/{a}/calls?notice=contact_gone"),
        Some("contacts"),
    );
    let res = app
        .request_form(
            Method::POST,
            &format!("/devices/9999/contacts/{contact}"),
            Some(&cookie),
            &[("inbound", "on")],
        )
        .await;
    assert_eq!(res.status, StatusCode::NOT_FOUND);
    let flags: (bool, bool) = sqlx::query_as(
        "SELECT allow_inbound, allow_outbound FROM device_contacts WHERE contact_id = ?",
    )
    .bind(contact)
    .fetch_one(&app.db)
    .await
    .unwrap();
    assert_eq!(flags, (true, true), "b's row untouched");

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
    // Back to its row, open (design 31 step 8).
    assert_redirect(
        &res,
        &format!("/devices/{b}/calls?open={contact}"),
        Some(&format!("contact-{contact}")),
    );
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

    // An invalid Matrix ID or app changes nothing. The Matrix ID's refusal renders the page with
    // the row open, what was sent kept and the reason under the field.
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
    let res = app
        .request_form(
            Method::POST,
            &format!("/devices/{b}/contacts/{contact}#contact-{contact}"),
            Some(&cookie),
            &[
                ("outbound", "on"),
                ("message_app", "element"),
                ("message_address", "mormor"),
            ],
        )
        .await;
    assert_eq!(res.status, StatusCode::BAD_REQUEST);
    let html = res.text();
    let row = part(&html, &format!("id=\"contact-{contact}\""), "</form>");
    assert!(row.contains("<details class=\"edit\" open>"), "{row}");
    assert!(row.contains("value=\"mormor\""), "{row}");
    assert!(row.contains("A Matrix ID looks like"), "{row}");
    assert_eq!(super::input_checked(row, "inbound", "on"), Some(false));
    assert_eq!(super::input_checked(row, "outbound", "on"), Some(true));
    assert!(row.contains("<option value=\"element\" selected>"), "{row}");
    assert!(!html.contains("/static/auto-reload.js"));
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

/// The default country code is Phone Setup's second form (design 31 step 8, L6): validated, used
/// for new numbers, sent to every phone, and back to its own flash; a refusal renders Phone Setup
/// with what was typed under the field.
#[tokio::test]
async fn default_country_code_is_validated_and_used() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("phone").await;
    let mut nudges = app.state.command_notify.subscribe();

    for bad in ["4x", "0", "1234", "", "++46"] {
        let res = app
            .request_form(
                Method::POST,
                "/settings/calls",
                Some(&cookie),
                &[("default_country_code", bad)],
            )
            .await;
        assert_eq!(res.status, StatusCode::BAD_REQUEST, "{bad:?}");
        let html = res.text();
        let field = part(&html, "id=\"country-code\"", "</section>");
        assert!(field.contains(&format!("value=\"{bad}\"")), "{field}");
        assert!(field.contains("aria-invalid=\"true\""), "{field}");
        assert!(field.contains("Nothing was saved."), "{field}");
        // The other form shows what is stored, untouched.
        assert!(!part(&html, "id=\"setup\"", "</form>").contains("aria-invalid"));
    }
    assert!(nudges.try_recv().is_err(), "a refusal nudges nobody");
    assert_eq!(
        policy(&app, &token).await["call_policy"]["default_country_code"],
        json!("47")
    );
    // An older Calls page's form (`device_id`) lands on Phone Setup too.
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
    assert_redirect(
        &res,
        "/settings/provisioning?saved=country-code",
        Some("country-code"),
    );
    assert_eq!(nudges.try_recv().ok(), Some(id));
    assert_eq!(
        policy(&app, &token).await["call_policy"]["default_country_code"],
        json!("46")
    );
    let html = app
        .get_page("/settings/provisioning?saved=country-code", &cookie)
        .await
        .text();
    let group = part(&html, "id=\"country-code\"", "</section>");
    assert!(group.contains("value=\"46\""), "{group}");
    assert!(group.contains("data-flash=\"country-code\""), "{group}");
    assert!(!html.contains("data-flash=\"setup\""));
    add(&app, &cookie, id, "Farmor", "070-123 45 67").await;
    assert_eq!(contact_rows(&app).await[0].2, "+46701234567");
    // The calls page names it and where to change it, but has no form for it any more.
    let html = calls_page(&app, &cookie, id).await;
    assert!(
        html.contains("+46 is used (Settings › Phone Setup)"),
        "{html}"
    );
    assert!(!html.contains("/settings/calls"));

    // An unreadable setting is a 500 on Phone Setup, never an empty field.
    sqlx::query("ALTER TABLE call_settings RENAME TO broken")
        .execute(&app.db)
        .await
        .unwrap();
    let res = app.get_page("/settings/provisioning", &cookie).await;
    assert_eq!(res.status, StatusCode::INTERNAL_SERVER_ERROR);
}

/// The device page's row (design 31 §3.2): "Not managed", else the number of contacts.
#[tokio::test]
async fn device_page_summarises_calls() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, _) = app.enrolled_device("phone").await;
    let row = |page: &str| part(page, "data-row=\"calls\"", "</a>").to_string();
    let page = app
        .get_page(&format!("/devices/{id}"), &cookie)
        .await
        .text();
    assert!(row(&page).contains("Not managed"), "{page}");
    assert!(page.contains(&format!("href=\"/devices/{id}/calls\" data-row=\"calls\"")));
    set_managed(&app, id, true, false).await;
    add(&app, &cookie, id, "Mamma", "91234567").await;
    let page = app
        .get_page(&format!("/devices/{id}"), &cookie)
        .await
        .text();
    assert!(row(&page).contains(">1 contact<"), "{page}");
    add(&app, &cookie, id, "Pappa", "91234568").await;
    let page = app
        .get_page(&format!("/devices/{id}"), &cookie)
        .await
        .text();
    assert!(row(&page).contains(">2 contacts<"), "{page}");
    // The old card is gone: its sentences are on Calls & Contacts.
    assert!(!page.contains("Calls &amp; SMS</h2>"));
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
        "default_dialer": "me.vibb.launcher",
        "system_dialer": "com.android.dialer",
        "sms_restricted": false,
        "outgoing_restricted": false,
        "default_sms_package": "com.google.android.apps.messaging",
        "last_error": null,
        "call_log_readable": true
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

/// The warnings above the groups (`partials/note_warning.html`), one per finding.
fn warnings(html: &str) -> usize {
    html.matches("data-warning>").count()
}

/// The page as a parent reads it: without the ⓘ panels (`<details class="info…">`), where an
/// expert detail - a package name, an adb command, the phone's own error - may sit (§4.2).
fn visible(html: &str) -> String {
    let mut out = String::new();
    let mut rest = html;
    while let Some(at) = rest.find("<details class=\"info") {
        out.push_str(&rest[..at]);
        let after = &rest[at..];
        rest = &after[after.find("</details>").map_or(after.len(), |e| e + 10)..];
    }
    out.push_str(rest);
    out
}

#[tokio::test]
async fn calls_page_warns_without_capability() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("phone").await;
    set_managed(&app, id, true, true).await;

    post_status(&app, &token, json!({})).await;
    let page = calls_page(&app, &cookie, id).await;
    assert!(page.contains("Calls aren&#39;t screened"), "{page}");
    assert!(page.contains("too old to screen calls"), "{page}");

    post_status(
        &app,
        &token,
        json!({ "capabilities": ["call_policy_v1"], "call_state": good_call_state() }),
    )
    .await;
    let page = calls_page(&app, &cookie, id).await;
    assert_eq!(warnings(&page), 0, "{page}");

    // QA #22: a launcher that used to enforce calls and stops reporting it.
    post_status(&app, &token, json!({})).await;
    let page = calls_page(&app, &cookie, id).await;
    assert!(
        page.contains("stopped reporting that it screens calls"),
        "{page}"
    );

    // Unmanaged: no capability warning.
    set_unmanaged(&app, id).await;
    assert_eq!(warnings(&calls_page(&app, &cookie, id).await), 0);
}

async fn set_unmanaged(app: &TestApp, id: i64) {
    sqlx::query("UPDATE device_policy SET calls_managed = 0 WHERE device_id = ?")
        .bind(id)
        .execute(&app.db)
        .await
        .unwrap();
}

/// Every role, rule and error problem is a warning with its short title (design 31 §4.2); the
/// adb command and the phone's own error sit behind the ⓘ, never in the visible text (§2.2b).
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
    call_state["call_log_readable"] = json!(false);
    call_state["boot_policy"] = json!("write_failed");
    post_status(
        &app,
        &token,
        json!({ "capabilities": ["call_policy_v1"], "call_state": call_state }),
    )
    .await;
    let page = calls_page(&app, &cookie, id).await;
    for expected in [
        "Phone can&#39;t read its call rules",
        "Anyone can call this phone",
        "Blocked numbers may ring briefly",
        "Emergency callback may not work",
        "Calls blocked after a restart",
        "Phone reported a calls problem",
    ] {
        assert!(page.contains(expected), "{expected}: {page}");
    }
    assert_eq!(warnings(&page), 6, "{page}");
    let shown = visible(&page);
    for hidden in [
        "IllegalArgumentException",
        "adb shell",
        "CALL_REDIRECTION",
        "launcher",
        "UTC",
    ] {
        assert!(!shown.contains(hidden), "{hidden} visible: {shown}");
    }
    assert!(page.contains("The phone said: setDefaultDialerApplication: IllegalArgumentException"));
    assert!(page.contains("android.app.role.CALL_REDIRECTION me.vibb.launcher"));
}

/// Security review L7: incoming calls are filtered by the phone app's screening or the
/// call-screening role; the page says when neither does (a warning), and - only as a row in the
/// Settings group - that the screening role is missing while Vibb is the phone app (the user's
/// emulator, 2026-10-12). The device page has the same problems as Attention rows.
#[tokio::test]
async fn calls_page_says_whether_incoming_calls_are_filtered() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("phone").await;
    set_managed(&app, id, true, true).await;
    let anyone = "Anyone can call this phone";
    let not_phone_app = "Vibb isn&#39;t the phone app";
    let screening = "Call screening";
    for (dialer, held, present, absent) in [
        (false, None, vec![anyone], vec![not_phone_app, screening]),
        (
            false,
            Some(false),
            vec![anyone],
            vec![not_phone_app, screening],
        ),
        (
            false,
            Some(true),
            vec![not_phone_app],
            vec![anyone, screening],
        ),
        (
            true,
            Some(false),
            vec![screening],
            vec![anyone, not_phone_app],
        ),
        (
            true,
            Some(true),
            vec![],
            vec![anyone, not_phone_app, screening],
        ),
        (true, None, vec![], vec![anyone, not_phone_app, screening]),
    ] {
        let mut call_state = good_call_state();
        call_state["dialer_role_held"] = json!(dialer);
        if let Some(held) = held {
            call_state["screening_role_held"] = json!(held);
        }
        post_status(
            &app,
            &token,
            json!({ "capabilities": ["call_policy_v1"], "call_state": call_state }),
        )
        .await;
        let page = calls_page(&app, &cookie, id).await;
        let summary = app
            .get_page(&format!("/devices/{id}"), &cookie)
            .await
            .text();
        for text in &present {
            let at = page
                .find(text)
                .unwrap_or_else(|| panic!("{dialer} {held:?} {text}: {page}"));
            if *text == screening {
                // A row in the Settings group, not a warning; its fix behind the row's ⓘ.
                let group = part(&page, "id=\"settings\"", "</section>");
                assert!(group.contains(screening), "{group}");
                assert!(group.contains("android.app.role.CALL_SCREENING"), "{group}");
                assert_eq!(warnings(&page), 0, "{page}");
            } else {
                let open = page[..at].rfind("<div class=\"attention ").unwrap();
                assert!(
                    page[open..at].contains("data-warning>"),
                    "{text} is a warning"
                );
                assert!(summary.contains(text), "{text} is an Attention row");
            }
        }
        for text in &absent {
            assert!(!page.contains(text), "{dialer} {held:?} not {text}");
        }
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
    let page = calls_page(&app, &cookie, id).await;
    assert!(page.contains("Messages app allowed with SMS off"), "{page}");
    // Its package only behind the ⓘ.
    assert!(page.contains("Its package name is com.google.android.apps.messaging."));
    assert!(!visible(&page).contains("com.google.android.apps.messaging"));

    set_managed(&app, id, true, true).await;
    assert!(
        !calls_page(&app, &cookie, id)
            .await
            .contains("Messages app allowed")
    );
}

/// An emergency call and the callback window: Attention rows on the device page, and on Calls &
/// Contacts in Phone Setup's zone, never UTC (design 31 M4).
#[tokio::test]
async fn emergency_call_is_shown_on_the_device_page() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("phone").await;
    set_managed(&app, id, true, true).await;
    sqlx::query("UPDATE provisioning_settings SET time_zone = 'Europe/Oslo'")
        .execute(&app.db)
        .await
        .unwrap();
    let now = chrono::Utc::now();
    let (called, until) = (
        now - chrono::Duration::minutes(5),
        now + chrono::Duration::minutes(55),
    );
    let mut call_state = good_call_state();
    call_state["last_emergency_call_at"] = json!(called.to_rfc3339());
    call_state["callback_window_until"] = json!(until.to_rfc3339());
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
    assert!(
        page.contains("Emergency call made from this phone"),
        "{page}"
    );
    assert!(
        page.contains("Emergency services can call back now"),
        "{page}"
    );
    let oslo = crate::display_time::Zone::from_setting("Europe/Oslo");
    let page = calls_page(&app, &cookie, id).await;
    assert!(
        page.contains(&format!("emergency number ({})", oslo.when(called, now))),
        "{page}"
    );
    assert!(
        page.contains(&format!("until {}, so", oslo.until(until, now))),
        "{page}"
    );
    assert!(!visible(&page).contains("UTC"));

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
    assert!(!page.contains("Emergency call made"), "{page}");
    assert!(!page.contains("Emergency services can"), "{page}");
    assert_eq!(warnings(&calls_page(&app, &cookie, id).await), 0);
}

/// §5.1's table for Calls & Contacts, built from the rendered forms: the switches return to their
/// group (`#settings`; scroll-restore keeps the place), a contact's forms to its row, open,
/// Add Contact to the new row, Remove Contact… to `#contacts`. Every fragment is the form's own
/// element, every auto-saving form has a `<noscript>` Save, and Remove Contact… is a confirm.
#[tokio::test]
async fn every_form_on_the_calls_page_returns_to_its_place() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, _) = app.enrolled_device("phone").await;
    let c = attach(&app, id, "Mamma", "+4790000001", [true, true, true], 0).await;
    sqlx::query("UPDATE contacts SET photo_hash = ? WHERE id = ?")
        .bind("a".repeat(64))
        .bind(c)
        .execute(&app.db)
        .await
        .unwrap();
    let html = calls_page(&app, &cookie, id).await;
    assert!(!html.contains("confirm("));
    let calls = format!("/devices/{id}/calls");
    let open = format!("{calls}?open={c}");
    let row = format!("contact-{c}");
    // (action, fields, Location's path and fragment; None: multipart, tested in launcher_ui).
    type Row<'a> = (
        String,
        Vec<(&'a str, &'a str)>,
        Option<(&'a str, Option<&'a str>)>,
    );
    let table: Vec<Row> = vec![
        (
            format!("/devices/{id}/calls/settings#settings"),
            vec![("managed", "on"), ("default_message_app", "sms")],
            Some((calls.as_str(), Some("settings"))),
        ),
        (
            format!("/devices/{id}/contacts/{c}#{row}"),
            vec![("inbound", "on"), ("message_app", "")],
            Some((open.as_str(), Some(row.as_str()))),
        ),
        (
            format!("/devices/{id}/contacts/{c}/photo#{row}"),
            vec![],
            None,
        ),
        (
            format!("/devices/{id}/contacts/{c}/photo/remove#{row}"),
            vec![],
            Some((open.as_str(), Some(row.as_str()))),
        ),
        (
            format!("/devices/{id}/contacts/{c}/remove#contacts"),
            vec![],
            Some((calls.as_str(), Some("contacts"))),
        ),
        (
            format!("/devices/{id}/contacts#add-contact"),
            vec![("name", "Pappa"), ("number", "90000002")],
            Some((calls.as_str(), Some("NEW"))),
        ),
    ];
    let found = super::settings_pages::forms(&html);
    assert_eq!(
        found.iter().map(|(a, _)| a.as_str()).collect::<Vec<_>>(),
        table.iter().map(|(a, _, _)| a.as_str()).collect::<Vec<_>>(),
        "every form on the page is in the table, in page order"
    );
    for form in tags(&html, "form") {
        let action = form.attr("action").unwrap();
        let body = &html[form.start..];
        let body = &body[..body.find("</form>").unwrap()];
        if let Some((_, fragment)) = action.split_once('#') {
            assert!(
                super::settings_pages::encloses(&html, fragment, form.start),
                "{action}: #{fragment} isn't the form's own element"
            );
        }
        if body.contains("data-autosave") {
            assert!(
                body.split_once("<noscript>")
                    .is_some_and(|(_, rest)| rest.contains("type=\"submit\"")),
                "{action} saves itself but has no <noscript> Save"
            );
        }
    }
    let confirm = part(&html, "<details class=\"confirm", "</details>");
    assert!(
        confirm.contains(&format!("/contacts/{c}/remove#contacts")),
        "{confirm}"
    );
    assert!(
        confirm.contains("Remove Mamma from phone's phone?"),
        "{confirm}"
    );

    for ((action, hidden), (_, fields, expected)) in found.iter().zip(&table) {
        let Some((path, fragment)) = expected else {
            continue;
        };
        let mut sent: Vec<(&str, &str)> = hidden
            .iter()
            .map(|(k, v)| (k.as_str(), v.as_str()))
            .collect();
        sent.extend_from_slice(fields);
        let res = app
            .request_form(Method::POST, action, Some(&cookie), &sent)
            .await;
        if *fragment == Some("NEW") {
            let new: i64 =
                sqlx::query_scalar("SELECT id FROM contacts WHERE phone_number = '+4790000002'")
                    .fetch_one(&app.db)
                    .await
                    .unwrap();
            assert_redirect(&res, path, Some(&format!("contact-{new}")));
        } else {
            assert_redirect(&res, path, *fragment);
        }
        app.assert_lands(&res, &cookie).await;
    }
    // Each did what it says: managed, Mamma saved, her photo gone, then her too, Pappa added.
    let managed: bool =
        sqlx::query_scalar("SELECT calls_managed FROM device_policy WHERE device_id = ?")
            .bind(id)
            .fetch_one(&app.db)
            .await
            .unwrap();
    assert!(managed);
    let names: Vec<(String, Option<String>)> =
        sqlx::query_as("SELECT name, photo_hash FROM contacts ORDER BY id")
            .fetch_all(&app.db)
            .await
            .unwrap();
    assert_eq!(names, [("Pappa".to_string(), None)]);

    // A row opened by ?open= (where a save returns): only that one.
    let pappa = contact_rows(&app).await[0].0;
    let html = app
        .get_page(&format!("/devices/{id}/calls?open={pappa}"), &cookie)
        .await
        .text();
    assert_eq!(html.matches(" open>").count(), 1, "{html}");
    assert!(part(&html, &format!("id=\"contact-{pappa}\""), "</summary>").contains(" open>"));
    let html = app
        .get_page(
            &format!("/devices/{id}/calls?open=x&notice=nonsense"),
            &cookie,
        )
        .await
        .text();
    assert!(!html.contains(" open>") && !html.contains("data-flash"));
}

/// What a parent or a phone typed is escaped: a contact's name and number, a Matrix ID, the
/// phone's error.
#[tokio::test]
async fn the_calls_page_escapes_what_it_shows() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("phone").await;
    set_managed(&app, id, true, true).await;
    let c = attach(
        &app,
        id,
        "<b>Mor</b>",
        "+4790000001",
        [true, false, true],
        0,
    )
    .await;
    sqlx::query(
        "UPDATE device_contacts SET message_address = '\"><script>x</script>' WHERE contact_id = ?",
    )
    .bind(c)
    .execute(&app.db)
    .await
    .unwrap();
    let mut call_state = good_call_state();
    call_state["last_error"] = json!("<img src=x onerror=alert(1)>");
    post_status(
        &app,
        &token,
        json!({ "capabilities": ["call_policy_v1"], "call_state": call_state }),
    )
    .await;
    let html = calls_page(&app, &cookie, id).await;
    assert!(!html.contains("<b>Mor"), "{html}");
    assert!(html.contains("&#60;b&#62;Mor&#60;/b&#62;"), "{html}");
    assert!(!html.contains("<script>x") && !html.contains("<img src=x"));
    // The row's value: which way calls go.
    assert!(part(&html, &format!("id=\"contact-{c}\""), "</summary>").contains(">Calls in only<"));
}

/// A read the page needs fails as a 500, never as an empty list (its saves would read as off).
#[tokio::test]
async fn an_unreadable_contact_list_is_a_500() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, _) = app.enrolled_device("phone").await;
    sqlx::query("ALTER TABLE device_contacts RENAME TO broken")
        .execute(&app.db)
        .await
        .unwrap();
    let res = app.get_page(&format!("/devices/{id}/calls"), &cookie).await;
    assert_eq!(res.status, StatusCode::INTERNAL_SERVER_ERROR);
    let res = app.get_page("/devices/9999/calls", &cookie).await;
    assert_eq!(res.status, StatusCode::NOT_FOUND);
}
