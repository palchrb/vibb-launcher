//! Design 114 (the phone setup status): the shared contract `testdata/setup_steps.json` through the routes - the
//! report's `setup` stored with the hold's end, the phone's tick (`POST /api/devices/setup/ticks`) and the policy's
//! `setup`.

use axum::body::Body;
use axum::http::{Method, Request, StatusCode, header};
use serde_json::{Value, json};

use super::TestApp;

const CONTRACT: &str = include_str!("../../testdata/setup_steps.json");

pub(super) fn contract() -> Value {
    serde_json::from_str(CONTRACT).unwrap()
}

async fn stored(app: &TestApp, id: i64) -> (Option<String>, Option<i64>) {
    sqlx::query_as(
        "SELECT setup_json, setup_hold_until_ms FROM device_status WHERE device_id = ? \
         ORDER BY id DESC LIMIT 1",
    )
    .bind(id)
    .fetch_one(&app.state.db)
    .await
    .unwrap()
}

/// `report` is stored as the contract has it, and the hold's end is the earlier of its two clocks (QA L6);
/// `report_no_hold` stores no end; a report without `setup` stores nothing.
#[tokio::test]
async fn the_report_is_stored_with_the_holds_end() {
    let app = TestApp::new().await;
    let (id, token) = app.enrolled_device("Testtelefon").await;
    let contract = contract();
    let before = chrono::Utc::now().timestamp_millis();
    app.post_status(&token, json!({"setup": contract["report"]}))
        .await;
    let after = chrono::Utc::now().timestamp_millis();
    let (kept, until) = stored(&app, id).await;
    let kept: Value = serde_json::from_str(&kept.unwrap()).unwrap();
    assert_eq!(kept, contract["report"]);
    let left = contract["report"]["hold_left_ms"].as_i64().unwrap();
    let wall = contract["report"]["hold_until_ms"].as_i64().unwrap();
    let until = until.unwrap();
    // The contract's wall-clock end lies in the past of any test run: it wins the min.
    assert!(until <= wall.max(after + left));
    assert!(until == wall || (before + left..=after + left).contains(&until));

    app.post_status(&token, json!({"setup": contract["report_no_hold"]}))
        .await;
    let (kept, until) = stored(&app, id).await;
    let kept: Value = serde_json::from_str(&kept.unwrap()).unwrap();
    assert_eq!(kept, contract["report_no_hold"]);
    assert_eq!(until, None);

    app.post_status(&token, json!({})).await;
    assert_eq!(stored(&app, id).await, (None, None));
}

/// The hold's end comes from the elapsed clock when the phone's wall clock says later (QA L6): a report whose
/// `hold_until_ms` lies past `now + hold_left_ms` ends at the latter.
#[tokio::test]
async fn a_wall_clock_ahead_ends_at_the_time_left() {
    let app = TestApp::new().await;
    let (id, token) = app.enrolled_device("Testtelefon").await;
    let before = chrono::Utc::now().timestamp_millis();
    app.post_status(
        &token,
        json!({"restrictions_paused": true,
               "setup": {"device_owner": true, "hold_until_ms": before + 10 * 3_600_000_i64,
                         "hold_left_ms": 600_000}}),
    )
    .await;
    let after = chrono::Utc::now().timestamp_millis();
    let (_, until) = stored(&app, id).await;
    let until = until.unwrap();
    assert!((before + 600_000..=after + 600_000).contains(&until));
}

async fn ticks(app: &TestApp, id: i64) -> Vec<(String, bool)> {
    sqlx::query_as(
        "SELECT step, by_phone FROM device_setup_ticks WHERE device_id = ? ORDER BY step",
    )
    .bind(id)
    .fetch_all(&app.state.db)
    .await
    .unwrap()
}

/// Every `tick.answers` row through the phone's route: its status and body, the stored `by_phone` row, the phone line
/// for a new tick only, and the policy's `setup.ticked`.
#[tokio::test]
async fn the_phones_tick_answers_as_the_contract() {
    let app = TestApp::new().await;
    let (id, token) = app.enrolled_device("Testtelefon").await;
    let contract = contract();
    // "already ticked": the parent ticked it before.
    assert!(
        crate::setup_status::store_tick(&app.state.db, id, "vibration", false)
            .await
            .unwrap()
    );
    assert_eq!(
        app.policy(&token).await["setup"],
        json!({"ticked": ["vibration"]})
    );
    // The request is the contract's exact body.
    assert_eq!(
        contract["tick"]["request"],
        contract["tick"]["answers"][0]["body"]
    );
    for row in contract["tick"]["answers"].as_array().unwrap() {
        let res = app
            .request(
                Method::POST,
                "/api/devices/setup/ticks",
                Some(&token),
                Some(row["body"].clone()),
            )
            .await;
        assert_eq!(
            res.status.as_u16(),
            row["status"].as_u64().unwrap() as u16,
            "{}",
            row["name"]
        );
        match row.get("answer") {
            Some(answer) => assert_eq!(&res.json(), answer, "{}", row["name"]),
            None => assert!(res.body.is_empty(), "{}", row["name"]),
        }
    }
    assert_eq!(
        ticks(&app, id).await,
        [
            ("touch_feedback".to_string(), true),
            ("vibration".to_string(), false)
        ]
    );
    assert_eq!(
        app.policy(&token).await["setup"],
        json!({"ticked": ["vibration", "touch_feedback"]})
    );
    // One line, for the new tick only, in the reader's language.
    let lines = super::security_words(&app, "setup_tick_phone", crate::i18n::Lang::Nb).await;
    assert_eq!(lines.len(), 1, "{lines:?}");
    assert!(
        lines[0].contains("Vibrasjon ved trykk") && !lines[0].contains("avkrysset"),
        "{lines:?}"
    );
    // A retry ticks nothing new and writes no line.
    let res = app
        .request(
            Method::POST,
            "/api/devices/setup/ticks",
            Some(&token),
            Some(contract["tick"]["request"].clone()),
        )
        .await;
    assert_eq!(res.status, StatusCode::NO_CONTENT);
    assert_eq!(
        super::security_words(&app, "setup_tick_phone", crate::i18n::Lang::En)
            .await
            .len(),
        1
    );
}

/// A body past 256 bytes is refused before the handler (QA L7); no token, no tick.
#[tokio::test]
async fn the_tick_routes_edges() {
    let app = TestApp::new().await;
    let (id, token) = app.enrolled_device("Testtelefon").await;
    let long = format!(r#"{{"step": "vibration", "pad": "{}"}}"#, "x".repeat(260));
    assert!(long.len() > 256);
    let res = app
        .send(
            Request::builder()
                .method(Method::POST)
                .uri("/api/devices/setup/ticks")
                .header(header::AUTHORIZATION, format!("Bearer {token}"))
                .header(header::CONTENT_TYPE, "application/json")
                .body(Body::from(long))
                .unwrap(),
        )
        .await;
    assert_eq!(res.status, StatusCode::PAYLOAD_TOO_LARGE);
    let res = app
        .request(
            Method::POST,
            "/api/devices/setup/ticks",
            None,
            Some(json!({"step": "vibration"})),
        )
        .await;
    assert_eq!(res.status, StatusCode::UNAUTHORIZED);
    assert!(ticks(&app, id).await.is_empty());
}

/// Design 114 §5.8: without the phone's Parent PIN, Connect shows one line to it and no QR form or code; the QR's and
/// New Setup Code's POSTs make no code; the PIN saved from there (`back=provision`) lands on `#qr`, a refusal keeps
/// `back`.
#[tokio::test]
async fn no_setup_code_without_the_parent_pin() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, _) = app.create_device("Testtelefon").await;
    sqlx::query(
        "UPDATE devices SET enrollment_code_hash = NULL, enrollment_code_kind = NULL WHERE id = ?",
    )
    .bind(id)
    .execute(&app.db)
    .await
    .unwrap();
    let connect = app
        .get_page(&format!("/devices/{id}/provision"), &cookie)
        .await
        .text();
    assert!(connect.contains(&format!(
        "href=\"/devices/{id}/lock?back=provision#parent-pin\" data-warning=\"no-parent-pin\""
    )));
    assert!(!connect.contains("regenerate-code"));
    assert!(!connect.contains("name=\"wifi_ssid\""));
    let qr = app
        .request_form(
            Method::POST,
            &format!("/devices/{id}/provision"),
            Some(&cookie),
            &[("wifi_ssid", "Hjemme")],
        )
        .await;
    assert_eq!(qr.status, StatusCode::OK);
    assert!(!qr.text().contains("data-row=\"qr\""));
    let res = app
        .request_form(
            Method::POST,
            &format!("/devices/{id}/regenerate-code"),
            Some(&cookie),
            &[],
        )
        .await;
    super::assert_redirect(&res, &format!("/devices/{id}/provision"), Some("qr"));
    let hash: Option<String> =
        sqlx::query_scalar("SELECT enrollment_code_hash FROM devices WHERE id = ?")
            .bind(id)
            .fetch_one(&app.db)
            .await
            .unwrap();
    assert_eq!(hash, None, "no code made");
    let summary = app
        .get_page(&format!("/devices/{id}"), &cookie)
        .await
        .text();
    assert!(summary.contains("lock?back=provision#parent-pin"));
    assert!(!summary.contains("regenerate-code"));

    let lock = app
        .get_page(&format!("/devices/{id}/lock?back=provision"), &cookie)
        .await
        .text();
    assert!(lock.contains("<input type=\"hidden\" name=\"back\" value=\"provision\">"));
    let res = app
        .request_form(
            Method::POST,
            &format!("/devices/{id}/parent-pin"),
            Some(&cookie),
            &[("new_pin", "12"), ("back", "provision")],
        )
        .await;
    assert_eq!(
        res.location(),
        Some(
            format!("/devices/{id}/lock?back=provision&notice=parent_pin_invalid#parent-pin")
                .as_str()
        )
    );
    let res = app
        .request_form(
            Method::POST,
            &format!("/devices/{id}/parent-pin"),
            Some(&cookie),
            &[("new_pin", "48213957"), ("back", "provision")],
        )
        .await;
    super::assert_redirect(&res, &format!("/devices/{id}/provision"), Some("qr"));
    let connect = app
        .get_page(&format!("/devices/{id}/provision"), &cookie)
        .await
        .text();
    assert!(connect.contains("name=\"wifi_ssid\""));
    assert!(connect.contains("regenerate-code"));

    // Code QA L2: a code made (with the PIN, the page shows it), then the PIN removed - the code is void.
    let res = app
        .request_form(
            Method::POST,
            &format!("/devices/{id}/regenerate-code"),
            Some(&cookie),
            &[],
        )
        .await;
    assert_eq!(res.status, StatusCode::OK);
    let code = || async {
        sqlx::query_scalar::<_, Option<String>>(
            "SELECT enrollment_code_hash FROM devices WHERE id = ?",
        )
        .bind(id)
        .fetch_one(&app.db)
        .await
        .unwrap()
    };
    assert!(code().await.is_some());
    app.request_form(
        Method::POST,
        &format!("/devices/{id}/parent-pin"),
        Some(&cookie),
        &[("clear_pin", "1")],
    )
    .await;
    assert_eq!(code().await, None, "the code went with the PIN");

    // Code QA L1: an easy PIN from Connect stays on the PIN form with its warning, `back` kept.
    let res = app
        .request_form(
            Method::POST,
            &format!("/devices/{id}/parent-pin"),
            Some(&cookie),
            &[("new_pin", "123456"), ("back", "provision")],
        )
        .await;
    assert_eq!(
        res.location(),
        Some(
            format!("/devices/{id}/lock?back=provision&notice=parent_pin_saved_easy#parent-pin")
                .as_str()
        )
    );
}

/// Device › Oppsett (§5.1): the groups and rows' ids, a parent tick saved back on its row and moved to "Ticked", the
/// policy's `setup.ticked`; the summary's row; the hold's open group and the summary's hold row.
#[tokio::test]
async fn the_setup_page_and_its_ticks() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("Testtelefon").await;
    let page = format!("/devices/{id}/setup");
    let html = app.get_page(&page, &cookie).await.text();
    assert!(html.contains("data-setup-state=\"no-report\""), "{html}");
    app.post_status(
        &token,
        json!({"notification_listener_enabled": false, "battery_optimization_exempt": false,
               "setup": {"device_owner": true, "location": false, "data_saver": true}}),
    )
    .await;
    let html = app.get_page(&page, &cookie).await.text();
    for step in ["location", "notification_access", "background", "vibration"] {
        assert!(
            html.contains(&format!("id=\"step-{step}\"")),
            "{step}: {html}"
        );
    }
    assert!(html.contains("<section id=\"phone\""));
    assert!(html.contains("<section id=\"optional\""));
    assert!(!html.contains("<section id=\"check\""));
    assert!(html.contains(&format!("action=\"/devices/{id}/start-pause#open\"")));
    let summary = app
        .get_page(&format!("/devices/{id}"), &cookie)
        .await
        .text();
    assert!(summary.contains("Setup: 3 steps left"), "{summary}");
    assert!(summary.contains("data-row=\"setup\""));

    let res = app
        .request_form(
            Method::POST,
            &format!("{page}/ticks"),
            Some(&cookie),
            &[("step", "vibration"), ("on", "on")],
        )
        .await;
    super::assert_redirect(&res, &page, Some("step-vibration"));
    assert_eq!(
        app.policy(&token).await["setup"],
        json!({"ticked": ["vibration"]})
    );
    let html = app.get_page(&page, &cookie).await.text();
    assert!(html.contains("data-setup-ticked"), "{html}");
    let res = app
        .request_form(
            Method::POST,
            &format!("{page}/ticks"),
            Some(&cookie),
            &[("step", "vibration")],
        )
        .await;
    super::assert_redirect(&res, &page, Some("step-vibration"));
    assert_eq!(app.policy(&token).await["setup"], json!({"ticked": []}));
    let res = app
        .request_form(
            Method::POST,
            &format!("{page}/ticks"),
            Some(&cookie),
            &[("step", "location"), ("on", "on")],
        )
        .await;
    assert_eq!(res.status, StatusCode::BAD_REQUEST);

    // The hold: the open group says until when, the summary's pause row is the hold's, no setup row.
    let now = chrono::Utc::now().timestamp_millis();
    app.post_status(
        &token,
        json!({"restrictions_paused": true, "notification_listener_enabled": false,
               "setup": {"device_owner": true, "hold_until_ms": now + 3_600_000, "hold_left_ms": 3_600_000}}),
    )
    .await;
    let html = app.get_page(&page, &cookie).await.text();
    assert!(html.contains("data-setup-open=\"hold\""), "{html}");
    assert!(html.contains(&format!("action=\"/devices/{id}/end-windows#open\"")));
    let summary = app
        .get_page(&format!("/devices/{id}"), &cookie)
        .await
        .text();
    assert!(summary.contains("Setting up, no restrictions"), "{summary}");
    assert!(summary.contains(&format!(
        "href=\"/devices/{id}/setup#open\" data-attention=\"restrictions-paused\""
    )));
    assert!(!summary.contains("data-attention=\"setup\""));
    assert!(!html.contains("adb "));
}

/// Code QA M1: a line sits only at a choice that is on - Fjern varsler, Wi-Fi sign-in and app push turned off raise
/// nothing there, though the steps stay counted.
#[tokio::test]
async fn no_line_at_a_choice_that_is_off() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("Testtelefon").await;
    app.post_status(
        &token,
        json!({"notification_listener_enabled": false, "battery_optimization_exempt": false,
               "setup": {"device_owner": true, "location": false, "data_saver": false}}),
    )
    .await;
    let page = |path: &str| format!("/devices/{id}/{path}");
    // Wi-Fi sign-in on (the default): the location line; off: none.
    let html = app.get_page(&page("logins"), &cookie).await.text();
    assert!(html.contains("data-setup-warning=\"location\""), "{html}");
    // Fjern varsler off (the default): no notification-access line at #updates.
    let html = app.get_page(&page("advanced"), &cookie).await.text();
    assert!(!html.contains("data-setup-warning=\"notification_access\""));
    sqlx::query(
        "UPDATE device_policy SET captive_portal_enabled = 0, location_mode = 'off' WHERE device_id = ?",
    )
    .bind(id)
    .execute(&app.state.db)
    .await
    .unwrap();
    sqlx::query("UPDATE devices SET unified_push = 0 WHERE id = ?")
        .bind(id)
        .execute(&app.state.db)
        .await
        .unwrap();
    let html = app.get_page(&page("logins"), &cookie).await.text();
    assert!(!html.contains("data-setup-warning=\"location\""), "{html}");
    let html = app.get_page(&page("apps"), &cookie).await.text();
    assert!(!html.contains("data-setup-warning=\"background\""));
    assert!(!html.contains("data-setup-warning=\"data_saver\""));
    // Still counted on the summary.
    let summary = app
        .get_page(&format!("/devices/{id}"), &cookie)
        .await
        .text();
    assert!(summary.contains("data-attention=\"setup\""), "{summary}");
}

/// Code QA M2: before the first report the summary's Oppsett row has no value - never "Complete" for a phone nothing
/// has been read from.
#[tokio::test]
async fn the_setup_row_says_nothing_before_a_report() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let row_of = |html: &str| {
        html.split("data-row=\"setup\"")
            .nth(1)
            .and_then(|rest| rest.split("</a>").next())
            .unwrap_or_default()
            .to_string()
    };
    let (new, _) = app.create_device("Testtelefon").await;
    let html = app
        .get_page(&format!("/devices/{new}"), &cookie)
        .await
        .text();
    let row = row_of(&html);
    assert!(!row.contains("row-value"), "{row}");
    let (id, token) = app.enrolled_device("Andre").await;
    let html = app
        .get_page(&format!("/devices/{id}"), &cookie)
        .await
        .text();
    assert!(!row_of(&html).contains("Complete"));
    app.post_status(&token, json!({"battery_optimization_exempt": true}))
        .await;
    let html = app
        .get_page(&format!("/devices/{id}"), &cookie)
        .await
        .text();
    assert!(row_of(&html).contains("row-value"));
}

/// Design 114b A: a phone with no PIN has `child_pin` counted - an orange link on Device › Oppsett to Lock Screen &
/// PINs' Child PIN (never "Åpne for oppsett": the phone can't do it), one orange line there while no Child PIN is
/// stored, and nothing once the phone reports a PIN.
#[tokio::test]
async fn the_child_pin_step() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("Testtelefon").await;
    app.post_status(
        &token,
        json!({"setup": {"device_owner": true, "child_pin": false}}),
    )
    .await;
    let page = format!("/devices/{id}/setup");
    let html = app.get_page(&page, &cookie).await.text();
    assert!(
        html.contains(&format!(
            "<a class=\"attention attention-action\" id=\"step-child_pin\" href=\"/devices/{id}/lock#child-pin\""
        )),
        "{html}"
    );
    // Not a row of the phone's own group (its ⓘ rows), and never what makes the page offer "Åpne for oppsett".
    assert!(!html.contains("<details class=\"info cell\" id=\"step-child_pin\""));
    assert!(
        !crate::setup_status::STEPS
            .iter()
            .any(|step| step.key == "child_pin" && crate::setup_status::on_phone(step))
    );
    let summary = app
        .get_page(&format!("/devices/{id}"), &cookie)
        .await
        .text();
    assert!(summary.contains("Setup: 1 step left"), "{summary}");
    let lock_page = format!("/devices/{id}/lock");
    let lock = app.get_page(&lock_page, &cookie).await.text();
    assert!(lock.contains(">No Child PIN"), "{lock}");
    assert!(lock.contains("Choose a Child PIN below."));

    // Code QA M2: a PIN the kid or Android's setup set isn't the Child PIN - still missing, still the line.
    app.post_status(
        &token,
        json!({"setup": {"device_owner": true, "child_pin": true}}),
    )
    .await;
    let html = app.get_page(&page, &cookie).await.text();
    assert!(html.contains("id=\"step-child_pin\""), "{html}");
    let lock = app.get_page(&lock_page, &cookie).await.text();
    assert!(lock.contains(">No Child PIN"), "{lock}");

    // A Child PIN stored, the phone without a PIN and nothing on its way (code QA L1): "The phone has no PIN", never
    // "not the Child PIN" beside it, and no such Attention row.
    sqlx::query(
        "UPDATE device_policy SET kid_pin_hash = 'aGFzaA', kid_pin_salt = 'c2FsdA', kid_pin_length = 4, \
         kid_keyguard = 'android' WHERE device_id = ?",
    )
    .bind(id)
    .execute(&app.db)
    .await
    .unwrap();
    app.post_status(
        &token,
        json!({"capabilities": ["android_keyguard_v1"],
               "lock_state": {"keyguard": "android", "reset_token": true},
               "setup": {"device_owner": true, "child_pin": false}}),
    )
    .await;
    let lock = app.get_page(&lock_page, &cookie).await.text();
    assert!(lock.contains(">The phone has no PIN"), "{lock}");
    assert!(!lock.contains(">No Child PIN"), "{lock}");
    assert!(!lock.contains("isn&#39;t the Child PIN"), "{lock}");
    let summary = app
        .get_page(&format!("/devices/{id}"), &cookie)
        .await
        .text();
    assert!(!summary.contains("isn&#39;t the Child PIN"), "{summary}");

    // Stored and on the phone: done, no line.
    app.post_status(
        &token,
        json!({"capabilities": ["android_keyguard_v1"],
               "lock_state": {"keyguard": "android", "reset_token": true, "pin_rev": 0},
               "setup": {"device_owner": true, "child_pin": true}}),
    )
    .await;
    let html = app.get_page(&page, &cookie).await.text();
    assert!(!html.contains("id=\"step-child_pin\""), "{html}");
    let lock = app.get_page(&lock_page, &cookie).await.text();
    assert!(
        !lock.contains(">The phone has no PIN") && !lock.contains(">No Child PIN"),
        "{lock}"
    );
}

/// Design 114b B: while calls aren't managed the call roles are offered as a quiet optional row (never counted, never
/// a line on Anrop); managed, the required `call_filter` takes over, and roles granted early leave nothing.
#[tokio::test]
async fn the_call_filter_offered_early() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("Testtelefon").await;
    let call_state = |held: bool| {
        json!({"state": "unmanaged", "dialer_role_held": false, "redirection_role_held": held,
               "screening_role_held": held, "sms_restricted": false, "outgoing_restricted": false,
               "call_log_readable": true})
    };
    app.post_status(
        &token,
        json!({"call_state": call_state(false), "setup": {"device_owner": true}}),
    )
    .await;
    let page = format!("/devices/{id}/setup");
    let html = app.get_page(&page, &cookie).await.text();
    let optional = html
        .split("<section id=\"optional\"")
        .nth(1)
        .unwrap_or_default();
    assert!(optional.contains("id=\"step-call_filter_early\""), "{html}");
    assert!(!html.contains("id=\"step-call_filter\""));
    assert!(optional.contains("Only needed once Vibb manages calls."));
    let summary = app
        .get_page(&format!("/devices/{id}"), &cookie)
        .await
        .text();
    assert!(!summary.contains("data-attention=\"setup\""), "{summary}");

    sqlx::query("UPDATE device_policy SET calls_mode = 'managed' WHERE device_id = ?")
        .bind(id)
        .execute(&app.db)
        .await
        .unwrap();
    let html = app.get_page(&page, &cookie).await.text();
    assert!(html.contains("id=\"step-call_filter\""), "{html}");
    assert!(!html.contains("id=\"step-call_filter_early\""));

    app.post_status(
        &token,
        json!({"call_state": call_state(true), "setup": {"device_owner": true}}),
    )
    .await;
    let html = app.get_page(&page, &cookie).await.text();
    assert!(!html.contains("id=\"step-call_filter"), "{html}");
}
