//! Design 98 (Flymodus through the Digital assistant role): the shared contract `testdata/airplane_mode.json` - the
//! report stored as the contract has it, the `clean` rows, the emergency line's rule, the copies identical.

use axum::http::Method;
use serde_json::{Value, json};

use super::{TestApp, assert_redirect};
use crate::airplane_mode::{CAPABILITY, Period, clean, emergency_line};

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
    assert_eq!(contract["capability"], CAPABILITY);
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
                    "capabilities": [CAPABILITY],
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

/// A report from design 98's launcher: `airplane_mode` and the capability, with `extra`'s keys.
async fn report(app: &TestApp, token: &str, airplane: Value, extra: Value) {
    let mut body =
        json!({"capabilities": [CAPABILITY, "window_relock_v1"], "airplane_mode": airplane});
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
    // Bit 64 on and no role: one short orange line (design 113 QA M6).
    assert!(
        html.contains(
            "<span class=\"row-sub tone-orange\" data-airplane-sub>Vibb One isn&#39;t the digital assistant</span>"
        ),
        "{html}"
    );
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

    // The role held, no Wi-Fi in airplane mode: that line; the parent's block: its line.
    report(
        &app,
        &token,
        airplane(false, true, json!(false), json!([])),
        json!({}),
    )
    .await;
    let html = app.get_page(&home, &cookie).await.text();
    assert!(
        html.contains(
            "<span class=\"row-sub\" data-airplane-sub>Fully offline in airplane mode</span>"
        ),
        "{html}"
    );
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

/// §6.2: "Flymodus på siden" in the header (from `on`, and from `going_on` for 10 minutes only), the period that
/// ended, the emergency line, the role lost (held before); the Oppsett card's steps, its two forms on `#oppsett` with
/// the flash in the card, "Ikke nå" until the needed steps change; About This Phone's two rows; both languages.
#[tokio::test]
async fn the_summary_says_airplane_mode_and_holds_the_setup_card() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("Emma").await;
    let summary = format!("/devices/{id}");
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
    assert!(!html.contains("Airplane mode was on"));
    let activity = app
        .get_page(&format!("{summary}/activity"), &cookie)
        .await
        .text();
    assert!(activity.contains("Airplane mode on "), "{activity}");
    assert!(!activity.contains("unread-dot"), "{activity}");
    assert!(
        !html.contains("data-airplane-line=\"role-lost\""),
        "never held: the card's step"
    );
    assert!(html.contains("<section id=\"oppsett\""), "{html}");
    for step in [
        "background",
        "assistant",
        "default_apps:call_screening+browser",
    ] {
        assert!(
            html.contains(&format!("data-setup-step=\"{step}\"")),
            "{step}"
        );
    }
    assert!(!html.contains("data-setup-step=\"wifi\""), "the role first");
    assert!(html.contains("Changed during setup: Call screening, Browser."));
    assert!(html.contains(&format!("action=\"/devices/{id}/start-pause#oppsett\"")));
    assert!(html.contains("<input type=\"hidden\" name=\"back\" value=\"setup\">"));
    let nb = app
        .get_page(&format!("{summary}?lang=nb"), &cookie)
        .await
        .text();
    for word in [
        "Flymodus på siden",
        "Oppsett",
        "Bakgrunnstilkobling",
        "Ikke nå",
        "Anropsfilter, Nettleser",
    ] {
        assert!(nb.contains(word), "{word}: {nb}");
    }

    // Åpne for oppsett: back to the card with its flash.
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
        &format!("{summary}?notice=setup_start_pause_sent"),
        Some("oppsett"),
    );
    app.assert_lands(&res, &cookie).await;
    let flash = app.get_page(res.location().unwrap(), &cookie).await.text();
    assert!(flash.contains("data-flash=\"oppsett\""), "{flash}");
    // "Ikke nå": hidden, with its flash in the card's place, until the needed steps change.
    let res = app
        .request_form(
            Method::POST,
            &format!("/devices/{id}/setup-card/hide"),
            Some(&cookie),
            &[],
        )
        .await;
    assert_redirect(
        &res,
        &format!("{summary}?notice=setup_hidden"),
        Some("oppsett"),
    );
    app.assert_lands(&res, &cookie).await;
    let html = app.get_page(&summary, &cookie).await.text();
    assert!(!html.contains("<section id=\"oppsett\""), "{html}");
    let res = app
        .request_form(
            Method::POST,
            "/devices/999/setup-card/hide",
            Some(&cookie),
            &[],
        )
        .await;
    assert_eq!(res.status, axum::http::StatusCode::NOT_FOUND);

    // The role chosen: the Wi-Fi step is new, so the card is back; About says so.
    let mut held = on.clone();
    held["assistant"] = json!(true);
    report(
        &app,
        &token,
        held,
        json!({"battery_optimization_exempt": false}),
    )
    .await;
    let html = app.get_page(&summary, &cookie).await.text();
    assert!(html.contains("data-setup-step=\"wifi\""), "{html}");
    let about = app
        .get_page(&format!("/devices/{id}/about"), &cookie)
        .await
        .text();
    assert!(
        about.contains("Digital assistant") && about.contains("Wi-Fi in airplane mode"),
        "{about}"
    );
    assert!(about.contains("Not set up"));

    // Lost again: the warning with its steps; About says Not chosen.
    report(
        &app,
        &token,
        on.clone(),
        json!({"battery_optimization_exempt": false}),
    )
    .await;
    let html = app.get_page(&summary, &cookie).await.text();
    assert!(html.contains("data-airplane-line=\"role-lost\""), "{html}");
    assert!(html.contains("Vibb One is no longer the digital assistant."));
    assert!(html.contains("To choose Vibb One: open the phone for setup"));
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
    report(&app, &token, ended, json!({})).await;
    let html = app.get_page(&summary, &cookie).await.text();
    assert!(html.contains("data-airplane-line=\"emergency\""), "{html}");
    assert!(html.contains("An emergency call turned airplane mode off at"));
    assert!(!html.contains(" data-airplane title="), "off now");
    assert!(
        !html.contains("<section id=\"oppsett\""),
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

/// D2: the QR page's Done step offers "Åpne for oppsett nå" - the start-pause with `back=setup` -, landing on the
/// summary's `#oppsett` with the flash, before the phone has reported.
#[tokio::test]
async fn the_qr_page_opens_the_phone_for_setup() {
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
    let qr = app
        .request_form(
            Method::POST,
            &format!("/devices/{id}/provision"),
            Some(&cookie),
            &[("wifi_ssid", "Hjemme")],
        )
        .await
        .text();
    assert!(qr.contains("data-row=\"open-setup\""), "{qr}");
    assert!(qr.contains(&format!("action=\"/devices/{id}/start-pause#oppsett\"")));
    assert!(qr.contains("Open for setup now"));
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
        &format!("/devices/{id}?notice=setup_start_pause_sent"),
        Some("oppsett"),
    );
    app.assert_lands(&res, &cookie).await;
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
