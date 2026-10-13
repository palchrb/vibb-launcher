//! Design 24: the parent PIN's own card and save (`/devices/{id}/parent-pin`), the easy-PIN
//! warning and its sweep, and the notices inside their cards.

use axum::http::{Method, StatusCode};
use serde_json::Value;

use super::TestApp;

async fn policy(app: &TestApp, token: &str) -> Value {
    let res = app
        .request(Method::GET, "/api/devices/policy", Some(token), None)
        .await;
    assert_eq!(res.status, StatusCode::OK);
    res.json()
}

/// `(hash, salt, easy)` as stored.
async fn stored(app: &TestApp, id: i64) -> (Option<String>, Option<String>, Option<bool>) {
    sqlx::query_as(
        "SELECT override_pin_hash, override_pin_salt, override_pin_easy FROM device_policy \
         WHERE device_id = ?",
    )
    .bind(id)
    .fetch_one(&app.db)
    .await
    .unwrap()
}

async fn events(app: &TestApp, event_type: &str) -> i64 {
    sqlx::query_scalar("SELECT COUNT(*) FROM security_events WHERE event_type = ?")
        .bind(event_type)
        .fetch_one(&app.db)
        .await
        .unwrap()
}

/// Posts the card's form; returns the redirect.
async fn save(app: &TestApp, cookie: &str, id: i64, fields: &[(&str, &str)]) -> String {
    let res = app
        .request_form(
            Method::POST,
            &format!("/devices/{id}/parent-pin"),
            Some(cookie),
            fields,
        )
        .await;
    assert_eq!(res.status, StatusCode::SEE_OTHER, "{}", res.text());
    res.location().unwrap_or_default().to_string()
}

fn verifies(row: &(Option<String>, Option<String>, Option<bool>), pin: &str) -> bool {
    match row {
        (Some(hash), Some(salt), _) => crate::security::verify_pin(pin, hash, salt),
        _ => false,
    }
}

#[tokio::test]
async fn a_pin_is_saved_hashed_and_sent_with_a_nudge() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("phone").await;
    let mut nudges = app.state.command_notify.subscribe();

    let res = app
        .request_form(
            Method::POST,
            &format!("/devices/{id}/parent-pin"),
            Some(&cookie),
            &[("new_pin", " 583920 ")],
        )
        .await;
    super::assert_redirect(
        &res,
        &format!("/devices/{id}?notice=parent_pin_saved"),
        Some("parent-pin"),
    );
    let row = stored(&app, id).await;
    assert!(verifies(&row, "583920"));
    assert_eq!(row.2, Some(false));
    assert_eq!(events(&app, "override_pin_changed").await, 1);
    assert_eq!(nudges.try_recv().ok(), Some(id));
    let policy = policy(&app, &token).await;
    assert_eq!(policy["override_pin_hash"].as_str(), row.0.as_deref());
    // The policy JSON has no new key.
    assert!(policy.get("override_pin_easy").is_none());
}

#[tokio::test]
async fn an_easy_pin_is_saved_with_a_warning() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, _) = app.enrolled_device("phone").await;
    for pin in ["123456", "000000", "987654", "121212", "1234567890"] {
        let location = save(&app, &cookie, id, &[("new_pin", pin)]).await;
        assert!(
            location.contains("notice=parent_pin_saved_easy"),
            "{pin}: {location}"
        );
        let row = stored(&app, id).await;
        assert!(verifies(&row, pin), "{pin}");
        assert_eq!(row.2, Some(true), "{pin}");
    }
    let html = app.get_page(&device_page(id), &cookie).await.text();
    assert!(html.contains("This parent PIN is easy to guess"), "{html}");
    // The status card links to the card.
    assert!(html.contains("The parent PIN is easy to guess."), "{html}");

    // A good PIN clears the warning.
    save(&app, &cookie, id, &[("new_pin", "4719305")]).await;
    assert_eq!(stored(&app, id).await.2, Some(false));
    let html = app.get_page(&device_page(id), &cookie).await.text();
    assert!(!html.contains("easy to guess (all one digit"), "{html}");
}

fn device_page(id: i64) -> String {
    format!("/devices/{id}")
}

#[tokio::test]
async fn invalid_pins_and_the_kids_pin_write_nothing() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, _) = app.enrolled_device("phone").await;
    save(&app, &cookie, id, &[("new_pin", "583920")]).await;
    let before = stored(&app, id).await;
    for bad in ["", "12345", "12345678901", "12a456", "123 456"] {
        let location = save(&app, &cookie, id, &[("new_pin", bad)]).await;
        assert!(
            location.contains("notice=parent_pin_invalid"),
            "{bad}: {location}"
        );
    }
    assert_eq!(stored(&app, id).await, before);

    // The kid's PIN can't be the parent PIN.
    let res = app
        .request_form(
            Method::POST,
            &format!("/devices/{id}/kid-lock"),
            Some(&cookie),
            &[("new_kid_pin", "135792")],
        )
        .await;
    assert!(res.location().unwrap().contains("notice=saved"));
    let location = save(&app, &cookie, id, &[("new_pin", "135792")]).await;
    assert!(
        location.contains("notice=override_is_kid_pin"),
        "{location}"
    );
    assert_eq!(stored(&app, id).await, before);
    assert_eq!(events(&app, "override_pin_changed").await, 1);
}

#[tokio::test]
async fn clearing_works_unless_the_kids_lock_needs_it() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("phone").await;
    save(&app, &cookie, id, &[("new_pin", "123456")]).await;

    // A kid PIN in the database - as if saved in parallel: the SQL keeps the parent PIN.
    sqlx::query(
        "UPDATE device_policy SET kid_pin_hash = 'aa', kid_pin_salt = 'bb', kid_pin_length = 4 \
         WHERE device_id = ?",
    )
    .bind(id)
    .execute(&app.db)
    .await
    .unwrap();
    let before = stored(&app, id).await;
    let location = save(&app, &cookie, id, &[("clear_pin", "on")]).await;
    assert!(
        location.contains("notice=override_needed_by_lock"),
        "{location}"
    );
    assert_eq!(stored(&app, id).await, before);
    assert_eq!(events(&app, "override_pin_cleared").await, 0);

    sqlx::query("UPDATE device_policy SET kid_pin_hash = NULL, kid_pin_salt = NULL, kid_pin_length = NULL WHERE device_id = ?")
        .bind(id)
        .execute(&app.db)
        .await
        .unwrap();
    // Clear wins over a typed PIN.
    let location = save(
        &app,
        &cookie,
        id,
        &[("new_pin", "583920"), ("clear_pin", "on")],
    )
    .await;
    assert!(location.contains("notice=parent_pin_cleared"), "{location}");
    assert_eq!(stored(&app, id).await, (None, None, None));
    assert_eq!(events(&app, "override_pin_cleared").await, 1);
    assert!(policy(&app, &token).await["override_pin_hash"].is_null());
}

#[tokio::test]
async fn the_route_needs_a_session_and_a_device() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, _) = app.enrolled_device("phone").await;
    let res = app
        .request_form(
            Method::POST,
            &format!("/devices/{id}/parent-pin"),
            None,
            &[("new_pin", "583920")],
        )
        .await;
    assert!(
        res.location().is_none_or(|l| l.contains("login")),
        "{:?}",
        res.location()
    );
    assert_eq!(stored(&app, id).await, (None, None, None));
    let res = app
        .request_form(
            Method::POST,
            "/devices/9999/parent-pin",
            Some(&cookie),
            &[("new_pin", "583920")],
        )
        .await;
    assert_eq!(res.status, StatusCode::NOT_FOUND);
}

#[tokio::test]
async fn the_policy_form_leaves_the_pin_alone() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, _) = app.enrolled_device("phone").await;
    save(&app, &cookie, id, &[("new_pin", "583920")]).await;
    let before = stored(&app, id).await;
    for fields in [
        vec![("vpn_filter_enabled", "on")],
        vec![("new_pin", "246810"), ("clear_pin", "on")],
    ] {
        let res = app
            .request_form(
                Method::POST,
                &format!("/devices/{id}/policy"),
                Some(&cookie),
                &fields,
            )
            .await;
        assert_eq!(res.status, StatusCode::SEE_OTHER);
        assert_eq!(stored(&app, id).await, before);
    }
}

#[tokio::test]
async fn the_sweep_flags_an_easy_stored_pin_once() {
    let app = TestApp::new().await;
    let (easy_id, _) = app.enrolled_device("easy").await;
    let (good_id, _) = app.enrolled_device("good").await;
    let (none_id, _) = app.enrolled_device("none").await;
    for (id, pin) in [(easy_id, "123456"), (good_id, "583920")] {
        let (hash, salt) = crate::security::hash_pin(pin);
        sqlx::query(
            "UPDATE device_policy SET override_pin_hash = ?, override_pin_salt = ? WHERE device_id = ?",
        )
        .bind(hash)
        .bind(salt)
        .bind(id)
        .execute(&app.db)
        .await
        .unwrap();
    }
    // A small candidate list keeps the test fast (the full list is pinned in parent_pin::tests).
    let candidates = vec!["000000".to_string(), "123456".to_string()];
    crate::parent_pin::run_easy_sweep(app.db.clone(), candidates.clone()).await;
    assert_eq!(stored(&app, easy_id).await.2, Some(true));
    assert_eq!(stored(&app, good_id).await.2, Some(false));
    assert_eq!(stored(&app, none_id).await.2, None);

    // Checked once: a later sweep doesn't look again (and doesn't overwrite a newer save).
    sqlx::query("UPDATE device_policy SET override_pin_easy = 0 WHERE device_id = ?")
        .bind(easy_id)
        .execute(&app.db)
        .await
        .unwrap();
    crate::parent_pin::run_easy_sweep(app.db.clone(), candidates).await;
    assert_eq!(stored(&app, easy_id).await.2, Some(false));
}

#[tokio::test]
async fn notices_render_inside_their_cards() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, _) = app.enrolled_device("phone").await;
    let page = |code: &str| format!("/devices/{id}?notice={code}");
    let card = |html: &str, card_id: &str| -> String {
        let start = html
            .find(&format!("id=\"{card_id}\""))
            .unwrap_or_else(|| panic!("no #{card_id}"));
        let end = html[start..].find("</form>\n").unwrap() + start;
        html[start..end].to_string()
    };

    let html = app
        .get_page(&page("parent_pin_invalid"), &cookie)
        .await
        .text();
    let text = "Parent PIN not changed: it must be 6 to 10 digits.";
    assert!(card(&html, "parent-pin").contains(text), "{html}");
    assert_eq!(html.matches(text).count(), 1);

    let html = app
        .get_page(&page("parent_pin_saved"), &cookie)
        .await
        .text();
    assert!(card(&html, "parent-pin").contains("<div class=\"success\">Parent PIN saved."));

    let html = app.get_page(&page("needs_override"), &cookie).await.text();
    let text = "Not saved: set a parent PIN first.";
    assert!(card(&html, "screen-lock").contains(text), "{html}");
    assert_eq!(html.matches(text).count(), 1);
    assert!(!card(&html, "parent-pin").contains(text));

    // Unknown codes show nothing.
    let html = app.get_page(&page("bogus"), &cookie).await.text();
    for card_id in ["parent-pin", "screen-lock"] {
        let inside = card(&html, card_id);
        assert!(!inside.contains("class=\"success\""), "{inside}");
        assert!(!inside.contains("bogus"), "{inside}");
    }
}

// ---------------------------------------------------------------------------------------------
// Step 3: the phone's events (`POST /api/devices/pin-events`)
// ---------------------------------------------------------------------------------------------

/// Design 24 §3.3's literal, as the launcher's PolicyResponseCompatTest encodes it.
const LITERAL: &str = r#"{ "events": [ { "id": "0123456789abcdef0123456789abcdef", "kind": "unlocked", "entry": "override",
                "at_ms": 1760000000000, "until_ms": 1760007200000, "failures": null, "level": null } ] }"#;

async fn post_events(app: &TestApp, token: &str, body: &str) -> StatusCode {
    let request = axum::http::Request::builder()
        .method(Method::POST)
        .uri("/api/devices/pin-events")
        .header(axum::http::header::AUTHORIZATION, format!("Bearer {token}"))
        .header(axum::http::header::CONTENT_TYPE, "application/json")
        .body(axum::body::Body::from(body.to_string()))
        .unwrap();
    app.send_via(&app.router, super::LOOPBACK_PEER, request)
        .await
        .status
}

/// `(event_id, kind, entry, occurred_at_ms, until_ms, failures, level, clock_off)` by row order.
type Row = (
    String,
    String,
    String,
    i64,
    Option<i64>,
    Option<i64>,
    Option<i64>,
    bool,
);

async fn stored_events(app: &TestApp, id: i64) -> Vec<Row> {
    sqlx::query_as(
        "SELECT event_id, kind, entry, occurred_at_ms, until_ms, failures, level, clock_off \
         FROM device_pin_events WHERE device_id = ? ORDER BY id",
    )
    .bind(id)
    .fetch_all(&app.db)
    .await
    .unwrap()
}

async fn security_details(app: &TestApp, event_type: &str) -> Vec<String> {
    sqlx::query_scalar("SELECT detail FROM security_events WHERE event_type = ? ORDER BY id")
        .bind(event_type)
        .fetch_all(&app.db)
        .await
        .unwrap()
}

fn event(id: &str, kind: &str, entry: &str, at_ms: i64, extra: Value) -> Value {
    let mut e = serde_json::json!({ "id": id, "kind": kind, "entry": entry, "at_ms": at_ms });
    for (k, v) in extra.as_object().unwrap() {
        e[k] = v.clone();
    }
    e
}

fn hex_id(n: u32) -> String {
    format!("{n:032x}")
}

#[tokio::test]
async fn the_literal_is_stored_once_with_one_security_row() {
    let app = TestApp::new().await;
    let (id, token) = app.enrolled_device("phone").await;
    let mut nudges = app.state.command_notify.subscribe();

    assert_eq!(
        post_events(&app, &token, LITERAL).await,
        StatusCode::NO_CONTENT
    );
    assert_eq!(
        stored_events(&app, id).await,
        vec![(
            "0123456789abcdef0123456789abcdef".to_string(),
            "unlocked".to_string(),
            "override".to_string(),
            1_760_000_000_000,
            Some(1_760_007_200_000),
            None,
            None,
            false
        )]
    );
    let details = security_details(&app, "parent_pin_used").await;
    assert_eq!(
        details,
        vec![format!(
            "device {id}, 2025-10-09 08:53 UTC: Restrictions lifted on the time-lock screen, until 9 \
             Oct 10:53 UTC at the latest."
        )]
    );
    // A re-send (the phone never saw the 204) stores and logs nothing.
    assert_eq!(
        post_events(&app, &token, LITERAL).await,
        StatusCode::NO_CONTENT
    );
    assert_eq!(stored_events(&app, id).await.len(), 1);
    assert_eq!(security_details(&app, "parent_pin_used").await.len(), 1);
    // No nudge: a sync would end the override (QA S1).
    assert!(nudges.try_recv().is_err());
}

#[tokio::test]
async fn wrong_tries_stay_off_the_security_log_and_lockouts_go_on_it() {
    let app = TestApp::new().await;
    let (id, token) = app.enrolled_device("phone").await;
    let at = chrono::Utc::now().timestamp_millis() - 60_000;
    let mut events: Vec<Value> = (1..=4)
        .map(|n| {
            event(
                &hex_id(n),
                "wrong",
                "pin_lock",
                at,
                serde_json::json!({ "failures": n }),
            )
        })
        .collect();
    events.push(event(
        &hex_id(5),
        "lockout",
        "pin_lock",
        at,
        serde_json::json!({ "level": 2, "until_ms": at + 30 * 60_000 }),
    ));
    let body = serde_json::json!({ "events": events }).to_string();
    assert_eq!(
        post_events(&app, &token, &body).await,
        StatusCode::NO_CONTENT
    );
    assert_eq!(stored_events(&app, id).await.len(), 5);
    assert!(security_details(&app, "parent_pin_wrong").await.is_empty());
    let lockouts = security_details(&app, "parent_pin_lockout").await;
    assert_eq!(lockouts.len(), 1);
    assert!(
        lockouts[0].starts_with(&format!("device {id}, "))
            && lockouts[0].contains(
                " UTC: 5 wrong parent PINs in a row (the last on the lock screen): the parent PIN \
                 is locked on the phone for 30 minutes, until "
            ),
        "{lockouts:?}"
    );
}

#[tokio::test]
async fn bad_events_are_skipped_or_cleaned_and_bad_bodies_refused() {
    let app = TestApp::new().await;
    let (id, token) = app.enrolled_device("phone").await;
    let now = chrono::Utc::now().timestamp_millis();
    let body = serde_json::json!({ "events": [
        event(&hex_id(1), "guessed", "settings", now, serde_json::json!({})),
        event("not-an-id", "unlocked", "settings", now, serde_json::json!({})),
        event(&hex_id(2), "unlocked", "garage", now, serde_json::json!({})),
        // Kept: the clock was off (QA #12), and out-of-range numbers are nulled.
        event(&hex_id(3), "wrong", "settings", now + 3 * 86_400_000, serde_json::json!({ "failures": 2 })),
        event(&hex_id(4), "wrong", "pause", now, serde_json::json!({ "failures": 9, "level": 3, "until_ms": now - 1 })),
        { "id": hex_id(5).to_uppercase(), "kind": "unlocked", "entry": "settings", "at_ms": "soon" },
    ]})
    .to_string();
    assert_eq!(
        post_events(&app, &token, &body).await,
        StatusCode::NO_CONTENT
    );
    let rows = stored_events(&app, id).await;
    assert_eq!(rows.len(), 3, "{rows:?}");
    let (ref eid, _, _, at, until, failures, _, clock_off) = rows[0];
    assert_eq!(eid, &hex_id(3));
    assert!(clock_off && (at - now).abs() < 60_000 && until.is_none() && failures == Some(2));
    assert_eq!(
        (rows[1].5, rows[1].6, rows[1].4, rows[1].7),
        (None, Some(3), None, false)
    );
    assert_eq!(rows[2].0, hex_id(5));
    assert!(rows[2].7);

    for bad in ["not json", "{}", r#"{"events": 3}"#, "[]"] {
        assert_eq!(
            post_events(&app, &token, bad).await,
            StatusCode::BAD_REQUEST,
            "{bad}"
        );
    }
    let many: Vec<Value> = (0..101)
        .map(|n| {
            event(
                &hex_id(100 + n),
                "wrong",
                "settings",
                now,
                serde_json::json!({}),
            )
        })
        .collect();
    let body = serde_json::json!({ "events": many }).to_string();
    assert_eq!(
        post_events(&app, &token, &body).await,
        StatusCode::PAYLOAD_TOO_LARGE
    );
    assert_eq!(stored_events(&app, id).await.len(), 3);
    // Without a token: refused.
    assert_eq!(
        post_events(&app, "bogus", LITERAL).await,
        StatusCode::UNAUTHORIZED
    );
}

#[tokio::test]
async fn the_card_lists_events_and_the_status_card_has_notices() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("phone").await;
    let page = || async {
        app.get_page(&format!("/devices/{id}"), &cookie)
            .await
            .text()
    };

    // An older launcher: no list, the old banner instead.
    post_status_caps(&app, &token, &["pin_lock_v1"], true).await;
    let html = page().await;
    assert!(html.contains("doesn't report parent-PIN use yet"), "{html}");
    assert!(html.contains("The offline override was used"), "{html}");

    // A new one: the list (empty first), no old banner.
    post_status_caps(&app, &token, &["pin_lock_v1", "parent_pin_events_v1"], true).await;
    let html = page().await;
    assert!(html.contains("hasn't reported any use"), "{html}");
    assert!(!html.contains("The offline override was used"), "{html}");

    let now = chrono::Utc::now().timestamp_millis();
    let body = serde_json::json!({ "events": [
        event(&hex_id(1), "wrong", "override", now - 3 * 3_600_000, serde_json::json!({ "failures": 1 })),
        event(&hex_id(2), "unlocked", "settings", now - 2 * 3_600_000, serde_json::json!({})),
        event(&hex_id(3), "unlocked", "pin_lock", now - 3_600_000, serde_json::json!({})),
    ]})
    .to_string();
    assert_eq!(
        post_events(&app, &token, &body).await,
        StatusCode::NO_CONTENT
    );
    let html = page().await;
    assert!(
        html.contains("(1 h ago): Lock screen opened with the parent code."),
        "{html}"
    );
    assert!(
        html.contains("(2 h ago): The launcher&#39;s Settings opened."),
        "{html}"
    );
    assert!(
        html.contains("Wrong parent PIN on the time-lock screen (1 in a row)."),
        "{html}"
    );
    // The status card: the latest use with the watched-typing tail, and the wrong try.
    assert!(html.contains("The parent PIN was used on the phone 1 h ago: Lock screen opened with the parent code. (1 more in the last 24 hours - see Parent PIN)"), "{html}");
    assert!(html.contains("Typed where your child could see?"), "{html}");
    assert!(
        html.contains("1 wrong parent PIN was typed on the phone in the last 24 hours."),
        "{html}"
    );

    // A lockout replaces the wrong-try notice.
    let body =
        serde_json::json!({ "events": [ event(&hex_id(4), "lockout", "override", now - 60_000,
        serde_json::json!({ "level": 1, "until_ms": now + 14 * 60_000 })) ] })
        .to_string();
    post_events(&app, &token, &body).await;
    let html = page().await;
    assert!(
        html.contains("The parent PIN was locked on the phone after repeated wrong tries"),
        "{html}"
    );
    assert!(!html.contains("wrong parent PIN was typed"), "{html}");
}

async fn post_status_caps(app: &TestApp, token: &str, caps: &[&str], override_used: bool) {
    let res = app
        .request(
            Method::POST,
            "/api/devices/status",
            Some(token),
            Some(serde_json::json!({
                "lock_reason": "none", "kiosk_engaged": true,
                "capabilities": caps, "offline_override_used": override_used,
            })),
        )
        .await;
    assert_eq!(res.status, StatusCode::NO_CONTENT);
}

#[tokio::test]
async fn old_events_are_pruned_after_90_days() {
    let app = TestApp::new().await;
    let (id, token) = app.enrolled_device("phone").await;
    assert_eq!(
        post_events(&app, &token, LITERAL).await,
        StatusCode::NO_CONTENT
    );
    sqlx::query(
        "INSERT INTO device_pin_events (device_id, event_id, kind, entry, occurred_at_ms, received_at) \
         VALUES (?, ?, 'wrong', 'settings', 0, datetime('now', '-91 days'))",
    )
    .bind(id)
    .bind(hex_id(9))
    .execute(&app.db)
    .await
    .unwrap();
    let pruned = crate::retention::prune(&app.db).await.unwrap();
    assert_eq!(pruned.pin_events, 1);
    assert_eq!(stored_events(&app, id).await.len(), 1);
}

/// qa-24-code Low 4: once the parent saved a new PIN, the Status card stops telling them to - the
/// card's list keeps every event.
#[tokio::test]
async fn a_new_pin_ends_the_notices_but_not_the_list() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("phone").await;
    save(&app, &cookie, id, &[("new_pin", "583920")]).await;
    let now = chrono::Utc::now().timestamp_millis();
    let body = serde_json::json!({ "events": [
        event(&hex_id(1), "wrong", "pin_lock", now + 1_000, serde_json::json!({ "failures": 1 })),
        event(&hex_id(2), "lockout", "pin_lock", now + 2_000,
            serde_json::json!({ "level": 1, "until_ms": now + 14 * 60_000 })),
    ]})
    .to_string();
    assert_eq!(
        post_events(&app, &token, &body).await,
        StatusCode::NO_CONTENT
    );
    let page = || async {
        app.get_page(&format!("/devices/{id}"), &cookie)
            .await
            .text()
    };
    let notice = "The parent PIN was locked on the phone after repeated wrong tries";
    assert!(page().await.contains(notice));

    save(&app, &cookie, id, &[("new_pin", "4719305")]).await;
    let html = page().await;
    assert!(!html.contains(notice), "{html}");
    assert!(
        html.contains("5 wrong parent PINs in a row (the last on the lock screen)"),
        "{html}"
    );
    // An event after the save shows again.
    let body = serde_json::json!({ "events": [ event(&hex_id(3), "wrong", "settings",
        chrono::Utc::now().timestamp_millis() + 3_000, serde_json::json!({ "failures": 1 })) ] })
    .to_string();
    post_events(&app, &token, &body).await;
    assert!(
        page()
            .await
            .contains("1 wrong parent PIN was typed on the phone")
    );
}
