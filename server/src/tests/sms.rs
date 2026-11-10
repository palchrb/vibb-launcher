//! Design 77 step 1, the server's side: the policy's SMS keys against the shared contract
//! (`testdata/sms_contract.json`, byte-identical in the launcher's test resources - `sms::tests`
//! compares the copies), D2's derived old keys and migration, the report's `sms_state`, the
//! `sms-held` intake's checks, states and bound, and the Calls page's two channel rows.

use axum::http::{Method, StatusCode};
use serde_json::{Value, json};

use super::TestApp;

const CONTRACT: &str = include_str!("../../testdata/sms_contract.json");

fn contract() -> Value {
    serde_json::from_str(CONTRACT).unwrap()
}

async fn set_modes(app: &TestApp, id: i64, calls: &str, sms: &str) {
    sqlx::query("UPDATE device_policy SET calls_mode = ?, sms_mode = ? WHERE device_id = ?")
        .bind(calls)
        .bind(sms)
        .bind(id)
        .execute(&app.db)
        .await
        .unwrap();
}

/// A contact on the phone with its call flags `[in, out, home]`, its message app and text switches
/// `[sms_in, sms_out, mms]`.
async fn contact(
    app: &TestApp,
    id: i64,
    (name, number): (&str, &str),
    calls: [bool; 3],
    app_name: Option<&str>,
    texts: [bool; 3],
    sort: i64,
) {
    let contact: i64 =
        sqlx::query_scalar("INSERT INTO contacts (name, phone_number) VALUES (?, ?) RETURNING id")
            .bind(name)
            .bind(number)
            .fetch_one(&app.db)
            .await
            .unwrap();
    sqlx::query(
        "INSERT INTO device_contacts (device_id, contact_id, allow_inbound, allow_outbound, \
         show_on_home, message_app, sms_in, sms_out, mms, sort_order) \
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
    )
    .bind(id)
    .bind(contact)
    .bind(calls[0])
    .bind(calls[1])
    .bind(calls[2])
    .bind(app_name)
    .bind(texts[0])
    .bind(texts[1])
    .bind(texts[2])
    .bind(sort)
    .execute(&app.db)
    .await
    .unwrap();
}

/// The contract's `policy.call_policy`, exactly: the contacts' text switches (the 113 contact's
/// `sms_in` stored on, sent off - QA R2-H1), the names sorted by their fold, the own number, the
/// empty MMSC table and a held id the parent deleted.
#[tokio::test]
async fn the_policy_is_the_contracts() {
    let app = TestApp::new().await;
    let (id, token) = app.enrolled_device("phone").await;
    set_modes(&app, id, "managed", "managed").await;
    contact(
        &app,
        id,
        ("Mamma", "+4791234567"),
        [true, true, true],
        None,
        [true, true, true],
        0,
    )
    .await;
    contact(
        &app,
        id,
        ("Bestemor", "+4798765432"),
        [true, false, false],
        None,
        [false, true, false],
        1,
    )
    .await;
    contact(
        &app,
        id,
        ("Ambulanse", "113"),
        [true, true, false],
        Some("none"),
        [true, true, true],
        2,
    )
    .await;
    for (name, fold) in [("Posten", "posten"), ("DNB", "dnb")] {
        sqlx::query("INSERT INTO device_sms_names (device_id, name, fold) VALUES (?, ?, ?)")
            .bind(id)
            .bind(name)
            .bind(fold)
            .execute(&app.db)
            .await
            .unwrap();
    }
    sqlx::query(
        "INSERT INTO device_own_numbers (device_id, slot, number) VALUES (?, 1, '+4790000001')",
    )
    .bind(id)
    .execute(&app.db)
    .await
    .unwrap();
    let now = chrono::Utc::now().timestamp_millis();
    for (held, state, dropped) in [
        ("00112233445566778899aabbccddeeff", "held", Some("now")),
        ("10112233445566778899aabbccddeeff", "released", Some("now")),
        (
            "20112233445566778899aabbccddeeff",
            "held",
            Some("-25 hours"),
        ),
        ("30112233445566778899aabbccddeeff", "held", None),
    ] {
        sqlx::query(
            "INSERT INTO device_sms_held (device_id, held_id, at, kind, sender_class, sender, \
             expires_at, state, dropped_at) VALUES (?, ?, ?, 'sms', 'number', '+4791112222', ?, ?, \
             CASE WHEN ? IS NULL THEN NULL ELSE datetime('now', ?) END)",
        )
        .bind(id)
        .bind(held)
        .bind(now)
        .bind(now + 86_400_000)
        .bind(state)
        .bind(dropped)
        .bind(dropped.filter(|d| *d != "now").unwrap_or("+0 seconds"))
        .execute(&app.db)
        .await
        .unwrap();
    }
    // The kid's contact requests (design 77 §14) are their own contract, `contact_request.json`.
    let mut call_policy = app.policy(&token).await["call_policy"].clone();
    assert_eq!(
        call_policy
            .as_object_mut()
            .unwrap()
            .remove("contact_requests"),
        Some(serde_json::json!([]))
    );
    assert_eq!(call_policy, contract()["policy"]["call_policy"]);
    // Design 80 D13: "Gruppemeldinger" off - the contract's `groups_off`.
    sqlx::query("UPDATE device_policy SET sms_groups = 0")
        .execute(&app.db)
        .await
        .unwrap();
    assert_eq!(
        app.policy(&token).await["call_policy"]["sms_groups"],
        contract()["policy"]["groups_off"]["sms_groups"]
    );
}

/// D2's wire: each of the nine combinations sends its modes and the old keys derived from them
/// (failing closed for an older launcher: "Calls not managed, SMS managed" reads `managed: true,
/// calls_enabled: true, sms_enabled: false`). Every new key is sent in every state.
#[tokio::test]
async fn every_mode_sends_its_derived_old_keys() {
    let app = TestApp::new().await;
    let (id, token) = app.enrolled_device("phone").await;
    for row in contract()["modes"].as_array().unwrap() {
        set_modes(
            &app,
            id,
            row["calls_mode"].as_str().unwrap(),
            row["sms_mode"].as_str().unwrap(),
        )
        .await;
        let policy = app.policy(&token).await["call_policy"].clone();
        for key in [
            "calls_mode",
            "sms_mode",
            "managed",
            "calls_enabled",
            "sms_enabled",
        ] {
            assert_eq!(policy[key], row[key], "{key} in {row}");
        }
        for key in [
            "sms_lock_text",
            "sms_names",
            "own_numbers",
            "mmsc_hosts",
            "sms_held_drop",
        ] {
            assert!(
                policy.get(key).is_some_and(|v| !v.is_null()),
                "{key} in {row}"
            );
        }
    }
}

/// Code QA M2: a phone created from now on starts as before 0130 - both channels "Ikke styrt" - so an older launcher
/// from the setup QR reads exactly today's unmanaged `call_policy` (no contacts, nothing restricted), and its calls
/// are left alone.
#[tokio::test]
async fn a_new_phone_reads_as_before_0130() {
    let app = TestApp::new().await;
    let (id, token) = app.enrolled_device("phone").await;
    contact(
        &app,
        id,
        ("Mamma", "+4791234567"),
        [true, true, true],
        None,
        [true, true, true],
        0,
    )
    .await;
    let policy = app.policy(&token).await["call_policy"].clone();
    assert_eq!(policy["calls_mode"], "unmanaged");
    assert_eq!(policy["sms_mode"], "unmanaged");
    // The keys an older launcher reads: unmanaged, as before 0130.
    assert_eq!(policy["managed"], false);
    assert_eq!(policy["calls_enabled"], true);
    assert_eq!(policy["sms_enabled"], true);
    assert_eq!(policy["contacts"], json!([]));
    let stored: (String, String, bool) = sqlx::query_as(
        "SELECT calls_mode, sms_mode, calls_managed FROM device_policy WHERE device_id = ?",
    )
    .bind(id)
    .fetch_one(&app.db)
    .await
    .unwrap();
    assert_eq!(stored, ("unmanaged".into(), "unmanaged".into(), false));
}

/// Migration 0130 on rows from before it: D2's table (the contract's `migration` rows) - no phone
/// less restricted and none more - and each contact's texting where its calling was (D3).
#[tokio::test]
async fn the_migration_keeps_every_phone_as_it_was() {
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
        .filter(|m| m.version < 130)
        .cloned()
        .collect::<Vec<_>>()
        .into();
    old.run(&db).await.unwrap();
    let rows = contract()["migration"].as_array().unwrap().clone();
    let mut ids = Vec::new();
    for row in &rows {
        let id: i64 = sqlx::query_scalar("INSERT INTO devices (name) VALUES ('old') RETURNING id")
            .fetch_one(&db)
            .await
            .unwrap();
        sqlx::query(
            "INSERT INTO device_policy (device_id, kiosk_desired, calls_managed, calls_enabled, sms_enabled) \
             VALUES (?, 1, ?, ?, ?)",
        )
        .bind(id)
        .bind(row["calls_managed"].as_bool().unwrap())
        .bind(row["calls_enabled"].as_bool().unwrap())
        .bind(row["sms_enabled"].as_bool().unwrap())
        .execute(&db)
        .await
        .unwrap();
        ids.push(id);
    }
    let contact: i64 = sqlx::query_scalar(
        "INSERT INTO contacts (name, phone_number) VALUES ('Mamma', '+4791234567') RETURNING id",
    )
    .fetch_one(&db)
    .await
    .unwrap();
    sqlx::query(
        "INSERT INTO device_contacts (device_id, contact_id, allow_inbound, allow_outbound) VALUES (?, ?, 0, 1)",
    )
    .bind(ids[0])
    .bind(contact)
    .execute(&db)
    .await
    .unwrap();

    full.run(&db).await.unwrap();
    db.close().await;
    let options = sqlx::sqlite::SqliteConnectOptions::new().filename(dir.path().join("old.db"));
    let db = sqlx::SqlitePool::connect_with(options).await.unwrap();
    for (row, id) in rows.iter().zip(ids) {
        let modes: (String, String) =
            sqlx::query_as("SELECT calls_mode, sms_mode FROM device_policy WHERE device_id = ?")
                .bind(id)
                .fetch_one(&db)
                .await
                .unwrap();
        assert_eq!(modes.0, row["calls_mode"].as_str().unwrap(), "{row}");
        assert_eq!(modes.1, row["sms_mode"].as_str().unwrap(), "{row}");
    }
    let texts: (bool, bool, bool) =
        sqlx::query_as("SELECT sms_in, sms_out, mms FROM device_contacts")
            .fetch_one(&db)
            .await
            .unwrap();
    assert_eq!(texts, (false, true, true));
}

/// The contract's `report`: the capability and `sms_state`, stored through its known keys exactly.
#[tokio::test]
async fn the_report_stores_the_sms_state() {
    let app = TestApp::new().await;
    let (id, token) = app.enrolled_device("phone").await;
    let contract = contract();
    let state = contract["report"]["sms_state"].clone();
    app.post_status(
        &token,
        json!({ "capabilities": [contract["capability"]], "sms_state": state }),
    )
    .await;
    let (stored, capabilities): (String, String) = sqlx::query_as(
        "SELECT sms_state_json, capabilities_json FROM device_status WHERE device_id = ?",
    )
    .bind(id)
    .fetch_one(&app.db)
    .await
    .unwrap();
    assert_eq!(serde_json::from_str::<Value>(&stored).unwrap(), state);
    assert!(capabilities.contains("\"sms_engine_v1\""));
}

/// The contract's `held.request`, its instants moved into the last hour (the deltas kept).
fn held_request() -> Value {
    let mut request = contract()["held"]["request"].clone();
    let rows = request["rows"].as_array_mut().unwrap();
    let shift = chrono::Utc::now().timestamp_millis() - 3_600_000 - rows[0]["at"].as_i64().unwrap();
    for row in rows {
        for key in ["at", "expires_at"] {
            row[key] = json!(row[key].as_i64().unwrap() + shift);
        }
    }
    request
}

async fn post_held(app: &TestApp, token: &str, body: Value) -> super::TestResponse {
    app.request(
        Method::POST,
        "/api/devices/sms-held",
        Some(token),
        Some(body),
    )
    .await
}

async fn held_rows(app: &TestApp, id: i64) -> Vec<(String, String, String, i64)> {
    sqlx::query_as(
        "SELECT held_id, state, sender, at FROM device_sms_held WHERE device_id = ? ORDER BY held_id",
    )
    .bind(id)
    .fetch_all(&app.db)
    .await
    .unwrap()
}

/// The contract's answer; then a re-post moves a row only forward, never changes a sender or a
/// time, and counts the change it ignored.
#[tokio::test]
async fn held_rows_are_stored_and_move_only_forward() {
    let app = TestApp::new().await;
    let (id, token) = app.enrolled_device("phone").await;
    let request = held_request();
    let res = post_held(&app, &token, request.clone()).await;
    assert_eq!(res.status, StatusCode::OK, "{}", res.text());
    assert_eq!(res.json(), contract()["held"]["answers"][0]);
    let before = held_rows(&app, id).await;
    assert_eq!(before.len(), 5);

    let mut again = request.clone();
    // Row 0: held -> released (forward), with another sender and time, which are kept as stored.
    again["rows"][0]["state"] = "released".into();
    again["rows"][0]["sender"] = "+4799999999".into();
    again["rows"][0]["at"] = json!(again["rows"][0]["at"].as_i64().unwrap() - 1000);
    // Row 1: released -> held (backwards): ignored and counted.
    again["rows"][1]["state"] = "held".into();
    let res = post_held(&app, &token, again).await;
    assert_eq!(res.json(), json!({ "accepted": 5 }));
    let after = held_rows(&app, id).await;
    assert_eq!(after[0].1, "released");
    assert_eq!((&after[0].2, after[0].3), (&before[0].2, before[0].3));
    assert_eq!(after[1].1, "released");
    let ignored: i64 =
        sqlx::query_scalar("SELECT sms_ignored_transitions FROM devices WHERE id = ?")
            .bind(id)
            .fetch_one(&app.db)
            .await
            .unwrap();
    assert_eq!(ignored, 1);
}

/// One refused row refuses the post with its index and reason (the contract's `refusal`), and
/// stores nothing; every check has its reason.
#[tokio::test]
async fn a_refused_row_stores_nothing() {
    let app = TestApp::new().await;
    let (id, token) = app.enrolled_device("phone").await;
    let mut request = held_request();
    request["rows"][0]["sender"] = "+47\"<&1234".into();
    let res = post_held(&app, &token, request).await;
    assert_eq!(res.status, StatusCode::BAD_REQUEST);
    assert_eq!(res.json(), contract()["held"]["refusal"]);
    assert!(held_rows(&app, id).await.is_empty());

    let future = chrono::Utc::now().timestamp_millis() + 10 * 60 * 1000;
    for (key, value, reason) in [
        ("id", json!("ABCDEF0123456789"), "id"),
        ("kind", json!("rcs"), "kind"),
        ("sender_class", json!("letters"), "class"),
        ("sender", json!("DNB\u{202e}"), "sender"),
        ("at", json!(future), "time"),
        ("state", json!("shown"), "state"),
        ("at", json!("soon"), "rows"),
    ] {
        let mut request = held_request();
        request["rows"][1][key] = value;
        if key == "sender" {
            request["rows"][1]["sender_class"] = "alphanumeric".into();
        }
        let res = post_held(&app, &token, request).await;
        assert_eq!(
            res.json(),
            json!({ "refused": 1, "reason": reason }),
            "{key}"
        );
    }
    let many = json!({ "rows": vec![held_request()["rows"][0].clone(); 51] });
    assert_eq!(post_held(&app, &token, many).await.json()["reason"], "rows");
    assert!(held_rows(&app, id).await.is_empty());
}

/// QA R2-M3: past 500 rows a new id isn't stored (the contract's capped answer) and the security
/// log says so once; a state update to a stored row still applies.
#[tokio::test]
async fn the_bound_caps_new_ids() {
    let app = TestApp::new().await;
    let (id, token) = app.enrolled_device("phone").await;
    let now = chrono::Utc::now().timestamp_millis();
    for n in 0..crate::sms::HELD_ROWS_A_PHONE {
        sqlx::query(
            "INSERT INTO device_sms_held (device_id, held_id, at, kind, sender_class, sender, expires_at, state) \
             VALUES (?, ?, ?, 'sms', 'short', '2222', ?, 'held')",
        )
        .bind(id)
        .bind(format!("{n:032x}"))
        .bind(now)
        .bind(now + 1000)
        .execute(&app.db)
        .await
        .unwrap();
    }
    let request = held_request();
    let res = post_held(&app, &token, json!({ "rows": [request["rows"][0]] })).await;
    assert_eq!(res.json(), contract()["held"]["answers"][1]);
    let mut update = request["rows"][0].clone();
    update["id"] = json!(format!("{:032x}", 7));
    update["sender_class"] = "short".into();
    update["sender"] = "2222".into();
    update["state"] = "expired".into();
    let res = post_held(
        &app,
        &token,
        json!({ "rows": [update, request["rows"][1]] }),
    )
    .await;
    assert_eq!(res.json(), json!({ "accepted": 1, "capped": true }));
    let state: String =
        sqlx::query_scalar("SELECT state FROM device_sms_held WHERE device_id = ? AND held_id = ?")
            .bind(id)
            .bind(format!("{:032x}", 7))
            .fetch_one(&app.db)
            .await
            .unwrap();
    assert_eq!(state, "expired");
    let lines = super::security_words(&app, "sms_held_capped", crate::i18n::Lang::En).await;
    assert_eq!(lines.len(), 2);
    assert!(lines[0].contains("500"), "{lines:?}");
}

/// Code QA L5: a flood of blocked rows fills only their own bound - a later held row is still stored, and a blocked
/// row past its bound is answered `capped` without being stored.
#[tokio::test]
async fn blocked_rows_have_their_own_bound() {
    let app = TestApp::new().await;
    let (id, token) = app.enrolled_device("phone").await;
    let now = chrono::Utc::now().timestamp_millis();
    for n in 0..crate::sms::BLOCKED_ROWS_A_PHONE {
        sqlx::query(
            "INSERT INTO device_sms_held (device_id, held_id, at, kind, sender_class, sender, expires_at, state) \
             VALUES (?, ?, ?, 'sms', 'malformed', '', ?, 'blocked')",
        )
        .bind(id)
        .bind(format!("{n:032x}"))
        .bind(now)
        .bind(now)
        .execute(&app.db)
        .await
        .unwrap();
    }
    let request = held_request();
    // Row 0 is held, row 4 blocked (the contract's request).
    let res = post_held(&app, &token, json!({ "rows": [request["rows"][0]] })).await;
    assert_eq!(res.json(), json!({ "accepted": 1 }));
    let res = post_held(&app, &token, json!({ "rows": [request["rows"][4]] })).await;
    assert_eq!(res.json(), json!({ "accepted": 0, "capped": true }));
    let blocked: i64 = sqlx::query_scalar(
        "SELECT COUNT(*) FROM device_sms_held WHERE device_id = ? AND state = 'blocked'",
    )
    .bind(id)
    .fetch_one(&app.db)
    .await
    .unwrap();
    assert_eq!(blocked, crate::sms::BLOCKED_ROWS_A_PHONE);
}

/// Retention: a held row goes 30 days after its `at`.
#[tokio::test]
async fn held_rows_go_after_30_days() {
    let app = TestApp::new().await;
    let (id, _) = app.enrolled_device("phone").await;
    let now = chrono::Utc::now().timestamp_millis();
    for (n, days) in [(1, 31), (2, 29)] {
        sqlx::query(
            "INSERT INTO device_sms_held (device_id, held_id, at, kind, sender_class, sender, expires_at, state) \
             VALUES (?, ?, ?, 'sms', 'short', '2222', ?, 'expired')",
        )
        .bind(id)
        .bind(format!("{n:032x}"))
        .bind(now - days * 86_400_000)
        .bind(now - days * 86_400_000)
        .execute(&app.db)
        .await
        .unwrap();
    }
    assert_eq!(crate::retention::prune(&app.db).await.sms_held, 1);
    assert_eq!(held_rows(&app, id).await.len(), 1);
}

/// The Calls page's two rows (design 77 D2, the minimum until step 4): each channel's three
/// choices and the chosen sub-line, a save writes both modes (and the derived old columns for a
/// rollback) and stamps `roles_changed_at` only on a change; an unknown choice is refused.
#[tokio::test]
async fn the_calls_page_saves_both_channels() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("phone").await;
    let uri = format!("/devices/{id}/calls/settings");
    let page = app
        .get_page(&format!("/devices/{id}/calls"), &cookie)
        .await
        .text();
    assert!(page.contains("Anyone can text, and every messaging app works."));
    assert!(page.contains("Anyone can call, and the phone can call anyone."));
    assert!(super::autosaves(&page, "calls_mode") && super::autosaves(&page, "sms_mode"));

    let save = |calls: &'static str, sms: &'static str| {
        let (app, cookie, uri) = (&app, &cookie, &uri);
        async move {
            app.request_form(
                Method::POST,
                uri,
                Some(cookie),
                &[
                    ("calls_mode", calls),
                    ("sms_mode", sms),
                    ("default_message_app", "sms"),
                ],
            )
            .await
        }
    };
    let changed = || async {
        sqlx::query_scalar::<_, Option<String>>(
            "SELECT roles_changed_at FROM device_policy WHERE device_id = ?",
        )
        .bind(id)
        .fetch_one(&app.db)
        .await
        .unwrap()
    };
    assert!(save("unmanaged", "unmanaged").await.status.is_redirection());
    assert_eq!(changed().await, None, "nothing changed");
    let res = save("off", "unmanaged").await;
    super::assert_redirect(&res, &format!("/devices/{id}/calls"), Some("settings"));
    assert!(changed().await.is_some());
    let policy = app.policy(&token).await["call_policy"].clone();
    assert_eq!(
        (policy["calls_mode"].clone(), policy["sms_mode"].clone()),
        (json!("off"), json!("unmanaged"))
    );
    let old: (bool, bool, bool) = sqlx::query_as(
        "SELECT calls_managed, calls_enabled, sms_enabled FROM device_policy WHERE device_id = ?",
    )
    .bind(id)
    .fetch_one(&app.db)
    .await
    .unwrap();
    assert_eq!(old, (true, false, true));

    for (calls, sms) in [("on", "managed"), ("off", ""), ("managed", "maybe")] {
        assert_eq!(
            save(calls, sms).await.status,
            StatusCode::BAD_REQUEST,
            "{calls} {sms}"
        );
    }
    let res = app
        .request_form(
            Method::POST,
            &uri,
            Some(&cookie),
            &[("default_message_app", "sms")],
        )
        .await;
    assert_eq!(res.status, StatusCode::BAD_REQUEST);
}

async fn save_modes(
    app: &TestApp,
    cookie: &str,
    id: i64,
    calls: &str,
    sms: &str,
) -> super::TestResponse {
    app.request_form(
        Method::POST,
        &format!("/devices/{id}/calls/settings"),
        Some(cookie),
        &[
            ("calls_mode", calls),
            ("sms_mode", sms),
            ("default_message_app", "sms"),
        ],
    )
    .await
}

/// A channel radio's state on the page: (checked, disabled).
fn radio(page: &str, name: &str, value: &str) -> (bool, bool) {
    let tag = super::tags(page, "input")
        .into_iter()
        .find(|tag| tag.attr("name") == Some(name) && tag.attr("value") == Some(value))
        .unwrap_or_else(|| panic!("no radio {name}={value}"));
    (tag.has("checked"), tag.has("disabled"))
}

async fn stored_modes(app: &TestApp, id: i64) -> (String, String) {
    sqlx::query_as("SELECT calls_mode, sms_mode FROM device_policy WHERE device_id = ?")
        .bind(id)
        .fetch_one(&app.db)
        .await
        .unwrap()
}

/// Code QA M2, step 4's capability gate brought forward: without `sms_engine_v1` in the phone's last report, the
/// pairs an older launcher can't do show disabled with "Update Vibb One first" and a save of one is refused in place
/// (the page again, nothing written); with the capability they are taken, and SMS's "Managed by Vibb" is the
/// recommended one.
#[tokio::test]
async fn the_calls_page_gates_what_an_older_launcher_cant_do() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("phone").await;
    app.post_status(&token, json!({ "capabilities": ["call_policy_v1"] }))
        .await;
    let page = app
        .get_page(&format!("/devices/{id}/calls"), &cookie)
        .await
        .text();
    // Step 4: a segmented control of three radios; a choice the phone can't do is a disabled radio, with its line.
    assert_eq!(radio(&page, "sms_mode", "managed"), (false, true), "{page}");
    assert_eq!(radio(&page, "sms_mode", "off"), (false, true));
    assert_eq!(radio(&page, "sms_mode", "unmanaged"), (true, false));
    assert_eq!(radio(&page, "calls_mode", "off"), (false, false));
    assert!(
        page.contains("Managed by Vibb - Update Vibb One first"),
        "{page}"
    );
    assert!(page.contains("Off - Update Vibb One first"));
    assert!(!page.contains("segment-note"));

    for (calls, sms) in [
        ("unmanaged", "managed"),
        ("managed", "managed"),
        ("off", "managed"),
        ("unmanaged", "off"),
    ] {
        let res = save_modes(&app, &cookie, id, calls, sms).await;
        assert_eq!(res.status, StatusCode::BAD_REQUEST, "{calls} {sms}");
        assert!(
            res.text().contains("Update Vibb One first: the phone"),
            "{calls} {sms}"
        );
        assert_eq!(
            stored_modes(&app, id).await,
            ("unmanaged".into(), "unmanaged".into())
        );
    }
    // What today's wire can say still saves.
    assert!(
        save_modes(&app, &cookie, id, "managed", "off")
            .await
            .status
            .is_redirection()
    );
    // Calls "Ikke styrt" needs SMS "Ikke styrt" too: shown disabled while SMS is off.
    let page = app
        .get_page(&format!("/devices/{id}/calls"), &cookie)
        .await
        .text();
    assert_eq!(radio(&page, "calls_mode", "unmanaged"), (false, true));
    assert!(page.contains("Not managed - Update Vibb One first"));

    // With the engine: every pair, and the recommendation.
    app.post_status(
        &token,
        json!({ "capabilities": ["call_policy_v1", "sms_engine_v1"] }),
    )
    .await;
    let page = app
        .get_page(&format!("/devices/{id}/calls"), &cookie)
        .await
        .text();
    assert!(page.contains(r#"Managed by Vibb<span class="segment-note">recommended</span>"#));
    assert!(!page.contains("- Update Vibb One first"));
    assert!(
        super::tags(&page, "input")
            .iter()
            .filter(|tag| tag.attr("type") == Some("radio"))
            .all(|tag| !tag.has("disabled"))
    );
    assert!(
        save_modes(&app, &cookie, id, "unmanaged", "managed")
            .await
            .status
            .is_redirection()
    );
    assert_eq!(
        stored_modes(&app, id).await,
        ("unmanaged".into(), "managed".into())
    );
}

/// Code QA M2: a stored pair the phone's launcher can't do (set up again with an older build): the policy keeps the
/// fail-closed derived keys, the Calls page says what it costs until the update, and a save that keeps the pair is
/// still taken (another field's save loosens nothing).
#[tokio::test]
async fn a_stored_pair_an_older_launcher_cant_do_is_explained() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("phone").await;
    set_modes(&app, id, "unmanaged", "managed").await;
    app.post_status(&token, json!({ "capabilities": ["call_policy_v1"] }))
        .await;
    let policy = app.policy(&token).await["call_policy"].clone();
    assert_eq!(
        (
            policy["managed"].clone(),
            policy["calls_enabled"].clone(),
            policy["sms_enabled"].clone()
        ),
        (json!(true), json!(true), json!(false))
    );
    let page = app
        .get_page(&format!("/devices/{id}/calls"), &cookie)
        .await
        .text();
    assert!(page.contains("The phone needs an update"), "{page}");
    assert!(page.contains("SMS is lost and calls are contacts-only until then."));
    assert!(
        save_modes(&app, &cookie, id, "unmanaged", "managed")
            .await
            .status
            .is_redirection()
    );

    set_modes(&app, id, "managed", "managed").await;
    let page = app
        .get_page(&format!("/devices/{id}/calls"), &cookie)
        .await
        .text();
    assert!(page.contains("SMS is lost until then."));
    // Not with the engine.
    app.post_status(
        &token,
        json!({ "capabilities": ["call_policy_v1", "sms_engine_v1"] }),
    )
    .await;
    let page = app
        .get_page(&format!("/devices/{id}/calls"), &cookie)
        .await
        .text();
    assert!(!page.contains("The phone needs an update"));
}
