//! Design 98 (Flymodus through the Digital assistant role): the shared contract `testdata/airplane_mode.json` - the
//! report stored as the contract has it, the `clean` rows, the emergency line's rule, the copies identical.

use axum::http::Method;
use serde_json::{Value, json};

use super::{TestApp, assert_redirect};
use crate::airplane_mode::{Period, clean, emergency_line};

const CONTRACT: &str = include_str!("../../testdata/airplane_mode.json");

fn contract() -> Value {
    serde_json::from_str(CONTRACT).unwrap()
}

async fn stored(app: &TestApp, id: i64) -> Option<String> {
    sqlx::query_scalar(
        "SELECT airplane_mode_json FROM device_status WHERE device_id = ? ORDER BY id DESC LIMIT 1",
    )
    .bind(id)
    .fetch_one(&app.state.db)
    .await
    .unwrap()
}

/// The report rows are stored exactly; a report without the key stores nothing.
#[tokio::test]
async fn the_report_is_stored_as_the_contract_has_it() {
    let app = TestApp::new().await;
    let (id, token) = app.enrolled_device("Emma").await;
    let contract = contract();
    assert_eq!(contract["bit"], 64);
    for key in ["report", "report_open"] {
        let res = app
            .request(
                Method::POST,
                "/api/devices/status",
                Some(&token),
                Some(json!({
                    "lock_reason": "NONE",
                    "kiosk_engaged": true,
                    "airplane_mode": contract[key],
                })),
            )
            .await;
        assert!(res.status.is_success(), "{} {}", res.status, res.text());
        let kept: Value = serde_json::from_str(&stored(&app, id).await.unwrap()).unwrap();
        assert_eq!(kept, contract[key], "{key}");
    }
    let res = app
        .request(
            Method::POST,
            "/api/devices/status",
            Some(&token),
            Some(json!({"lock_reason": "NONE", "kiosk_engaged": true})),
        )
        .await;
    assert!(res.status.is_success(), "{}", res.text());
    assert_eq!(stored(&app, id).await, None);
}

#[test]
fn the_clean_rows() {
    for row in contract()["clean"].as_array().unwrap() {
        let now = row["now_ms"].as_i64().unwrap();
        let cleaned = clean(&row["in"], now).map(|s| serde_json::from_str::<Value>(&s).unwrap());
        assert_eq!(
            cleaned.unwrap_or(Value::Null),
            row["out"],
            "{}",
            row["case"]
        );
    }
}

#[test]
fn the_emergency_line_rows() {
    for row in contract()["emergency"]["line"].as_array().unwrap() {
        let period: Period = serde_json::from_value(json!({
            "on_ms": row["period"]["on_ms"],
            "on_by": "kid",
            "off_ms": row["period"]["off_ms"],
            "off_by": row["period"]["off_by"],
        }))
        .unwrap();
        assert_eq!(
            emergency_line(row["call_ms"].as_i64().unwrap(), &period),
            row["line"].as_bool().unwrap(),
            "{}",
            row["case"]
        );
    }
}

#[test]
fn the_contract_is_identical_to_the_launchers_copy() {
    let launcher = std::fs::read_to_string(concat!(
        env!("CARGO_MANIFEST_DIR"),
        "/../launcher/app/src/test/resources/airplane_mode.json"
    ))
    .unwrap();
    assert_eq!(launcher, CONTRACT, "the launcher's copy differs");
}

// ---- Step 5: the PWA (§6.1, §6.2, §7.1) ---------------------------------------------------------------------------

fn now_ms() -> i64 {
    chrono::Utc::now().timestamp_millis()
}

async fn mask(app: &TestApp, token: &str) -> i64 {
    app.policy(token).await["quick_controls_mask"]
        .as_i64()
        .unwrap()
}

/// A report with `airplane_mode` and `extra`'s keys.
async fn report(app: &TestApp, token: &str, airplane: Value, extra: Value) {
    let mut body = json!({"airplane_mode": airplane});
    for (key, value) in extra.as_object().unwrap() {
        body[key] = value.clone();
    }
    app.post_status(token, body).await;
}

fn airplane(on: bool, assistant: bool, wifi_kept: Value, periods: Value) -> Value {
    json!({"on": on, "going_on": false, "assistant": assistant, "wifi_kept": wifi_kept, "last_error": null,
           "default_apps_changed": [], "periods": periods})
}

/// §6.1: the Home Screen form always shows the Flymodus row and takes bit 64 from its switch, keeping 32. The row's
/// sub-lines (only from a report with `airplane_mode`) and ⓘ, in both languages; the page is a phone sub-page (no
/// bell) and every save lands on `#quick-controls`.
#[tokio::test]
async fn the_home_form_takes_bit_64_from_its_row() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("Emma").await;
    let home = format!("/devices/{id}/home");
    sqlx::query("UPDATE device_policy SET quick_controls_mask = 1 | 32 | 64 WHERE device_id = ?")
        .bind(id)
        .execute(&app.db)
        .await
        .unwrap();
    let save = |fields: Vec<(&'static str, &'static str)>| {
        let (app, cookie) = (&app, cookie.clone());
        async move {
            app.request_form(
                Method::POST,
                &format!("/devices/{id}/policy"),
                Some(&cookie),
                &fields,
            )
            .await
        }
    };

    // A report without `airplane_mode`: the row, no sub-line.
    app.post_status(&token, json!({})).await;
    let html = app.get_page(&home, &cookie).await.text();
    assert_eq!(
        super::input_checked(&html, "quick_control_airplane", "on"),
        Some(true)
    );
    assert!(!html.contains("data-airplane-sub"), "{html}");

    // Design 98's launcher, the role not chosen yet: the row, its sub-line and the ⓘ.
    report(
        &app,
        &token,
        airplane(false, false, Value::Null, json!([])),
        json!({}),
    )
    .await;
    let html = app.get_page(&home, &cookie).await.text();
    assert_eq!(
        super::input_checked(&html, "quick_control_airplane", "on"),
        Some(true)
    );
    assert!(super::autosaves(&html, "quick_control_airplane"));
    assert_eq!(
        super::form_action_of(&html, "quick_control_airplane"),
        Some(format!("/devices/{id}/policy#quick-controls").as_str())
    );
    // Bit 64 on and no role: design 114's setup line at #quick-controls, a link to the step (no sub-line).
    assert!(
        html.contains(&format!(
            "<a class=\"attention attention-action\" href=\"/devices/{id}/setup#step-assistant\" data-setup-warning=\"assistant\">"
        )),
        "{html}"
    );
    assert!(html.contains("Vibb One isn&#39;t the digital assistant</span>"));
    assert!(!html.contains("data-airplane-sub"), "{html}");
    // About: "Not chosen" is orange only while the kid's Flymodus switch (bit 64) is on (2026-11-18).
    let about_url = format!("/devices/{id}/about");
    let about = app.get_page(&about_url, &cookie).await.text();
    let value = &about[about.find("data-row=\"assistant\"").expect("the row")..];
    let value = &value[value.find("class=\"row-value").unwrap()..];
    let value = &value[..value.find('>').unwrap()];
    assert!(value.contains("row-value-action"), "{value}");
    sqlx::query("UPDATE device_policy SET quick_controls_mask = quick_controls_mask & ~64 WHERE device_id = ?")
        .bind(id)
        .execute(&app.db)
        .await
        .unwrap();
    let about = app.get_page(&about_url, &cookie).await.text();
    let value = &about[about.find("data-row=\"assistant\"").expect("the row")..];
    let value = &value[value.find("class=\"row-value").unwrap()..];
    let value = &value[..value.find('>').unwrap()];
    assert!(!value.contains("row-value-action"), "{value}");
    sqlx::query("UPDATE device_policy SET quick_controls_mask = quick_controls_mask | 64 WHERE device_id = ?")
        .bind(id)
        .execute(&app.db)
        .await
        .unwrap();
    assert!(html.contains("data-airplane-info"));
    assert!(!html.contains("title-bell"), "a phone sub-page has no bell");
    let nb = app
        .get_page(&format!("{home}?lang=nb"), &cookie)
        .await
        .text();
    assert!(nb.contains("Flymodus"), "{nb}");
    assert!(
        nb.contains("Vibb One er ikke digital assistent</span>"),
        "{nb}"
    );
    assert!(nb.contains("Nødsamtaler virker fortsatt"));

    // The switch decides bit 64, 32 is still kept.
    let res = save(vec![("section", "quick"), ("quick_control_wifi", "on")]).await;
    assert_redirect(&res, &home, Some("quick-controls"));
    app.assert_lands(&res, &cookie).await;
    assert_eq!(mask(&app, &token).await, 1 | 32);
    // Bit 64 off, still no role: nothing is said about the role, nothing orange.
    let html = app.get_page(&home, &cookie).await.text();
    assert!(!html.contains("digital assistant</span>"), "{html}");
    assert!(
        !html.contains("data-airplane-sub"),
        "no sub-line, so nothing orange: {html}"
    );
    let res = save(vec![
        ("section", "quick"),
        ("quick_control_wifi", "on"),
        ("quick_control_airplane", "on"),
    ])
    .await;
    assert!(res.status.is_redirection());
    assert_eq!(mask(&app, &token).await, 1 | 32 | 64);
    save(vec![("section", "quick"), ("quick_control_wifi", "on")]).await;
    assert_eq!(mask(&app, &token).await, 1 | 32);
    let res = save(vec![("section", "quick"), ("quick_control_airplane", "on")]).await;
    assert_redirect(&res, &home, Some("quick-controls"));
    assert_eq!(mask(&app, &token).await, 32 | 64);

    // The role held, Wi-Fi in airplane mode not set up: the setup line for it; an OEM that turns Wi-Fi off counts as
    // done (design 98 §6.2) - no line, no sub-line; the parent's block: its line.
    report(
        &app,
        &token,
        airplane(false, true, Value::Null, json!([])),
        json!({}),
    )
    .await;
    let html = app.get_page(&home, &cookie).await.text();
    assert!(
        html.contains("data-setup-warning=\"wifi_airplane\""),
        "{html}"
    );
    assert!(!html.contains("data-setup-warning=\"assistant\""), "{html}");
    report(
        &app,
        &token,
        airplane(false, true, json!(false), json!([])),
        json!({}),
    )
    .await;
    let html = app.get_page(&home, &cookie).await.text();
    assert!(
        !html.contains("data-setup-warning=\"wifi_airplane\""),
        "{html}"
    );
    assert!(!html.contains("data-airplane-sub"), "{html}");
    report(
        &app,
        &token,
        airplane(false, true, json!(true), json!([])),
        json!({}),
    )
    .await;
    let html = app.get_page(&home, &cookie).await.text();
    assert!(!html.contains("data-airplane-sub"), "{html}");
    sqlx::query("UPDATE device_policy SET disallow_airplane_mode = 1 WHERE device_id = ?")
        .bind(id)
        .execute(&app.db)
        .await
        .unwrap();
    let html = app.get_page(&home, &cookie).await.text();
    assert!(
        html.contains(
            "<span class=\"row-sub\" data-airplane-sub>Blocked under Restrictions</span>"
        ),
        "{html}"
    );
}

/// §6.2: "Flymodus på siden" in the header (from `on`, and from `going_on` for 10 minutes only), the emergency line;
/// design 114: the steps it needs counted in the setup row (no role-lost line, no Oppsett card), listed on Device ›
/// Oppsett with the moved roles; its open group's Åpne for oppsett lands back on it with the flash; About's rows.
#[tokio::test]
async fn the_summary_says_airplane_mode_and_counts_its_setup_steps() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("Emma").await;
    let summary = format!("/devices/{id}");
    let setup = format!("/devices/{id}/setup");
    sqlx::query("UPDATE device_policy SET quick_controls_mask = 1 | 64 WHERE device_id = ?")
        .bind(id)
        .execute(&app.db)
        .await
        .unwrap();
    let now = now_ms();
    let periods = json!([
        {"on_ms": now - 3 * 3_600_000, "on_by": "kid", "off_ms": now - 2 * 3_600_000, "off_by": "kid"},
        {"on_ms": now - 600_000, "on_by": "kid", "off_ms": null, "off_by": null}
    ]);
    let mut on = airplane(true, false, Value::Null, periods);
    on["default_apps_changed"] = json!(["call_screening", "browser"]);
    report(
        &app,
        &token,
        on.clone(),
        json!({"battery_optimization_exempt": false}),
    )
    .await;
    let html = app.get_page(&summary, &cookie).await.text();
    assert!(html.contains(" data-airplane title="), "{html}");
    assert!(html.contains("Airplane mode on since"), "{html}");
    // An ended period is no summary box any more (design 101): it is an Activity row.
    assert!(!html.contains("data-airplane-line=\"was-on\""), "{html}");
    let activity = app
        .get_page(&format!("{summary}/activity"), &cookie)
        .await
        .text();
    assert!(activity.contains("Airplane mode on "), "{activity}");
    assert!(!html.contains("data-airplane-line=\"role-lost\""));
    assert!(!html.contains("<section id=\"oppsett\""), "{html}");
    assert!(html.contains("Setup: 3 steps left"), "{html}");
    let page = app.get_page(&setup, &cookie).await.text();
    for step in ["background", "assistant", "default_apps"] {
        assert!(
            page.contains(&format!("id=\"step-{step}\"")),
            "{step}: {page}"
        );
    }
    assert!(
        !page.contains("id=\"step-wifi_airplane\""),
        "the role first"
    );
    assert!(page.contains("Call screening, Browser"), "{page}");
    assert!(page.contains(&format!("action=\"/devices/{id}/start-pause#open\"")));
    assert!(page.contains("<input type=\"hidden\" name=\"back\" value=\"setup\">"));
    let nb = app
        .get_page(&format!("{setup}?lang=nb"), &cookie)
        .await
        .text();
    for word in ["Oppsett", "Bakgrunnstilkobling", "Anropsfilter, Nettleser"] {
        assert!(nb.contains(word), "{word}: {nb}");
    }
    assert!(!nb.contains("Ikke nå"));

    // Åpne for oppsett: back to the open group with its flash.
    let res = app
        .request_form(
            Method::POST,
            &format!("/devices/{id}/start-pause"),
            Some(&cookie),
            &[("back", "setup")],
        )
        .await;
    assert_redirect(
        &res,
        &format!("{setup}?notice=setup_start_pause_sent"),
        Some("open"),
    );
    app.assert_lands(&res, &cookie).await;
    let flash = app.get_page(res.location().unwrap(), &cookie).await.text();
    assert!(flash.contains("data-flash=\"open\""), "{flash}");
    // The hide route is gone.
    let res = app
        .request_form(
            Method::POST,
            &format!("/devices/{id}/setup-card/hide"),
            Some(&cookie),
            &[],
        )
        .await;
    assert!(res.status.is_client_error(), "{}", res.status);

    // The role chosen: the Wi-Fi step is new; About says so.
    let mut held = on.clone();
    held["assistant"] = json!(true);
    report(
        &app,
        &token,
        held,
        json!({"battery_optimization_exempt": false}),
    )
    .await;
    let page = app.get_page(&setup, &cookie).await.text();
    assert!(page.contains("id=\"step-wifi_airplane\""), "{page}");
    let about = app
        .get_page(&format!("/devices/{id}/about"), &cookie)
        .await
        .text();
    assert!(
        about.contains("Digital assistant") && about.contains("Wi-Fi in airplane mode"),
        "{about}"
    );
    assert!(about.contains("Not set up"));
    // About's row of a missing step links to it, with no how-to (§5.5).
    assert!(about.contains(&format!(
        "<a class=\"row\" href=\"/devices/{id}/setup#step-wifi_airplane\" data-row=\"wifi-airplane\">"
    )));
    assert!(about.contains(&format!(
        "<a class=\"row\" href=\"/devices/{id}/setup#step-background\" data-row=\"battery\">"
    )));
    assert!(!about.contains("adb "));

    // Lost again: the step, never a role-lost line; About says Not chosen.
    report(
        &app,
        &token,
        on.clone(),
        json!({"battery_optimization_exempt": false}),
    )
    .await;
    let html = app.get_page(&summary, &cookie).await.text();
    assert!(!html.contains("data-airplane-line=\"role-lost\""), "{html}");
    let about = app
        .get_page(&format!("/devices/{id}/about?lang=nb"), &cookie)
        .await
        .text();
    assert!(
        about.contains("Digital assistent") && about.contains("Ikke valgt"),
        "{about}"
    );

    // An emergency call ended the last period: its line on the summary.
    let ended = airplane(
        false,
        true,
        json!(true),
        json!([{"on_ms": now - 1_800_000, "on_by": "kid", "off_ms": now - 600_000, "off_by": "emergency"}]),
    );
    report(
        &app,
        &token,
        ended,
        json!({"battery_optimization_exempt": true}),
    )
    .await;
    let html = app.get_page(&summary, &cookie).await.text();
    assert!(html.contains("data-airplane-line=\"emergency\""), "{html}");
    assert!(html.contains("An emergency call turned airplane mode off at"));
    assert!(!html.contains(" data-airplane title="), "off now");
    assert!(
        !html.contains("data-attention=\"setup\""),
        "nothing needed: {html}"
    );

    // `going_on` alone: on for 10 minutes, then not.
    let mut going = airplane(false, true, json!(true), json!([]));
    going["going_on"] = json!(true);
    report(&app, &token, going, json!({})).await;
    let html = app.get_page(&summary, &cookie).await.text();
    assert!(html.contains(" data-airplane title="), "{html}");
    sqlx::query(
        "UPDATE device_status SET reported_at = datetime('now', '-11 minutes') WHERE device_id = ?",
    )
    .bind(id)
    .execute(&app.db)
    .await
    .unwrap();
    let html = app.get_page(&summary, &cookie).await.text();
    assert!(!html.contains(" data-airplane title="), "{html}");
}

/// Design 114 §5.2: the QR page has no "Åpne for oppsett nå" - a phone set up with the QR opens itself in the setup
/// hold.
#[tokio::test]
async fn the_qr_page_has_no_open_for_setup() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let res = app
        .request_form(
            Method::POST,
            "/devices/new",
            Some(&cookie),
            &[("name", "Lily")],
        )
        .await;
    assert!(res.status.is_redirection());
    let id: i64 = sqlx::query_scalar("SELECT id FROM devices WHERE name = 'Lily'")
        .fetch_one(&app.db)
        .await
        .unwrap();
    sqlx::query(
        "UPDATE device_policy SET override_pin_hash = 'aGFzaA', override_pin_salt = 'c2FsdA' \
         WHERE device_id = ?",
    )
    .bind(id)
    .execute(&app.db)
    .await
    .unwrap();
    let qr = app
        .request_form(
            Method::POST,
            &format!("/devices/{id}/provision"),
            Some(&cookie),
            &[("wifi_ssid", "Hjemme")],
        )
        .await
        .text();
    assert!(qr.contains("data-row=\"qr\""), "the QR: {qr}");
    assert!(!qr.contains("data-row=\"open-setup\""), "{qr}");
    assert!(!qr.contains("start-pause"));
}

/// §7.1: the emergency call's event carries `airplane_off` when the same report has the period the call ended (its
/// push and inbox say "The call turned airplane mode off."); a later report brings the line to the inbox's entry,
/// with no second event.
#[tokio::test]
async fn the_emergency_call_says_it_turned_airplane_mode_off() {
    let app = TestApp::new().await;
    let (id, token) = app.enrolled_device("Emma").await;
    let call = chrono::Utc::now() - chrono::Duration::minutes(5);
    let call_state = json!({"state": "managed", "last_emergency_call_at": call.to_rfc3339()});
    let emergency_period = json!([{"on_ms": call.timestamp_millis() - 3_600_000, "on_by": "kid",
                                   "off_ms": call.timestamp_millis() + 2_000, "off_by": "emergency"}]);
    let params = |app: &TestApp| {
        let db = app.db.clone();
        async move {
            sqlx::query_scalar::<_, String>(
                "SELECT params FROM parent_events WHERE device_id = ? AND kind = 'emergency_call'",
            )
            .bind(id)
            .fetch_all(&db)
            .await
            .unwrap()
        }
    };
    let words = |params: &str, form: crate::parent_events::Form, lang: crate::i18n::Lang| {
        let (db, params) = (app.db.clone(), params.to_string());
        async move {
            let zone = crate::display_time::Zone::load(&db, lang).await;
            crate::parent_events::told_as(
                form,
                "emergency_call",
                String::new(),
                String::new(),
                Some(&params),
                &zone,
                chrono::Utc::now(),
            )
            .1
        }
    };

    // The same report: the line in the push and the inbox.
    report(
        &app,
        &token,
        airplane(false, true, json!(true), emergency_period.clone()),
        json!({"call_state": call_state}),
    )
    .await;
    let stored = params(&app).await;
    assert_eq!(stored.len(), 1);
    let stored: Value = serde_json::from_str(&stored[0]).unwrap();
    assert_eq!(stored["airplane_off"], true, "{stored}");
    let raw = stored.to_string();
    for form in [
        crate::parent_events::Form::Push,
        crate::parent_events::Form::Inbox,
    ] {
        assert!(
            words(&raw, form, crate::i18n::Lang::En)
                .await
                .ends_with(" · The call turned airplane mode off.")
        );
    }
    assert!(
        words(
            &raw,
            crate::parent_events::Form::Push,
            crate::i18n::Lang::Nb
        )
        .await
        .ends_with(" · Flymodus ble slått av av samtalen.")
    );

    // Another phone: the call first, the period in a later report - one event, its line added.
    let (other, other_token) = app.enrolled_device("Jack").await;
    report(
        &app,
        &other_token,
        airplane(true, true, json!(true), json!([])),
        json!({"call_state": call_state}),
    )
    .await;
    let first: Vec<String> = sqlx::query_scalar(
        "SELECT params FROM parent_events WHERE device_id = ? AND kind = 'emergency_call'",
    )
    .bind(other)
    .fetch_all(&app.db)
    .await
    .unwrap();
    assert_eq!(first.len(), 1);
    assert!(!first[0].contains("airplane_off"), "{}", first[0]);
    report(
        &app,
        &other_token,
        airplane(false, true, json!(true), emergency_period),
        json!({"call_state": call_state}),
    )
    .await;
    let later: Vec<String> = sqlx::query_scalar(
        "SELECT params FROM parent_events WHERE device_id = ? AND kind = 'emergency_call'",
    )
    .bind(other)
    .fetch_all(&app.db)
    .await
    .unwrap();
    assert_eq!(later.len(), 1, "no second event");
    let later: Value = serde_json::from_str(&later[0]).unwrap();
    assert_eq!(later["airplane_off"], true, "{later}");
}

/// Design 121 §4.3 (QA H2): Vibb's switch said `going_on` before the radios went and nothing came since - grey "Airplane
/// mode on since" on the Devices list and the summary, never "No contact", for as long as that holds; a stream opened
/// since is the normal state.
#[tokio::test]
async fn flight_mode_through_vibb_is_grey_until_the_phone_is_heard_from() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("Emma").await;
    let mut going = airplane(false, true, json!(false), json!([]));
    going["going_on"] = json!(true);
    report(&app, &token, going, json!({})).await;
    // Past the restart grace: the stream closed before the report, as the launcher closes it.
    app.state
        .command_streams
        .set_started(chrono::Utc::now() - chrono::Duration::hours(3));
    drop(app.state.command_streams.open(id));
    let list = app.get_page("/devices", &cookie).await.text();
    assert!(list.contains("avatar-dot dot-offline"), "{list}");
    assert!(list.contains("Airplane mode on since"), "{list}");
    assert!(!list.contains("No contact"), "{list}");
    let summary = app
        .get_page(&format!("/devices/{id}"), &cookie)
        .await
        .text();
    assert!(summary.contains("state-dot dot-offline"), "{summary}");
    assert_eq!(
        summary.matches("Airplane mode on since").count(),
        1,
        "{summary}"
    );
    // Heard from again (Wi-Fi kept): the normal state.
    let _stream = app.state.command_streams.open(id);
    let list = app.get_page("/devices", &cookie).await.text();
    assert!(list.contains("avatar-dot dot-online"), "{list}");
}
