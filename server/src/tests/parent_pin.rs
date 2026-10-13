//! Design 24: the parent PIN's save (`/devices/{id}/parent-pin`), the easy-PIN warning and its
//! sweep, and the flashes inside their groups - on Lock Screen & PINs since design 31 step 4.

use axum::http::{Method, StatusCode};
use serde_json::Value;

use super::{Section, TestApp, device_page};

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
        &format!("{}?notice=parent_pin_saved", device_page(id, Section::Lock)),
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
    let lock = device_page(id, Section::Lock);
    let html = app.get_page(&lock, &cookie).await.text();
    assert!(html.contains("data-warning=\"easy-parent-pin\""), "{html}");
    assert!(html.contains("Parent PIN easy to guess"), "{html}");
    // The device page's Status card links to it.
    let summary = app
        .get_page(&device_page(id, Section::Summary), &cookie)
        .await
        .text();
    assert!(
        summary.contains(&format!(
            "The Parent PIN is easy to guess. <a href=\"{lock}#parent-pin\">"
        )),
        "{summary}"
    );

    // A good PIN clears the warning.
    save(&app, &cookie, id, &[("new_pin", "4719305")]).await;
    assert_eq!(stored(&app, id).await.2, Some(false));
    let html = app.get_page(&lock, &cookie).await.text();
    assert!(!html.contains("easy-parent-pin"), "{html}");
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

/// The flashes show inside their groups on Lock Screen & PINs (§3.0), once, and never an unknown
/// code.
#[tokio::test]
async fn flashes_render_inside_their_groups() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, _) = app.enrolled_device("phone").await;
    let page = |code: &str| format!("{}?notice={code}", device_page(id, Section::Lock));
    let group = |html: &str, id: &str| -> String {
        let start = html
            .find(&format!("<section id=\"{id}\""))
            .unwrap_or_else(|| panic!("no #{id}"));
        let end = html[start..].find("</section>").unwrap() + start;
        html[start..end].to_string()
    };

    let html = app
        .get_page(&page("parent_pin_invalid"), &cookie)
        .await
        .text();
    let text = "Parent PIN not changed: it must be 6 to 10 digits.";
    assert!(group(&html, "parent-pin").contains(text), "{html}");
    assert_eq!(html.matches(text).count(), 1);
    // A refusal opens the form again.
    assert!(group(&html, "parent-pin").contains("<details class=\"edit cell\" open>"));

    let html = app
        .get_page(&page("parent_pin_saved"), &cookie)
        .await
        .text();
    assert!(group(&html, "parent-pin").contains(
        "<p class=\"flash\" role=\"status\" data-flash=\"parent-pin\">Parent PIN saved."
    ));
    assert!(!group(&html, "parent-pin").contains("<details class=\"edit cell\" open>"));

    let html = app.get_page(&page("needs_override"), &cookie).await.text();
    let text = "Not saved: set a Parent PIN first.";
    assert!(group(&html, "child-pin").contains(text), "{html}");
    assert_eq!(html.matches(text).count(), 1);
    assert!(!group(&html, "parent-pin").contains(text));
    assert!(group(&html, "child-pin").contains("flash flash-error"));
    // A refused Child PIN opens its form again (qa-31-s4-code L1); a saved one doesn't.
    assert!(group(&html, "child-pin").contains("<details class=\"edit cell\" open>"));
    let html = app.get_page(&page("saved"), &cookie).await.text();
    assert!(
        group(&html, "child-pin").contains("Child PIN saved."),
        "{html}"
    );
    assert!(!group(&html, "child-pin").contains("<details class=\"edit cell\" open>"));

    // Unknown codes show nothing.
    let html = app.get_page(&page("bogus"), &cookie).await.text();
    assert!(!html.contains("class=\"flash"), "{html}");
    assert!(!html.contains("bogus"), "{html}");
    // The device page shows no PIN flash any more.
    let summary = app
        .get_page(
            &format!(
                "{}?notice=parent_pin_saved",
                device_page(id, Section::Summary)
            ),
            &cookie,
        )
        .await
        .text();
    assert!(!summary.contains("Parent PIN saved"), "{summary}");
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

/// Lock Screen & PINs lists what the phone reported ("Recent use", local times); the device page's
/// Status card has the notices, which link there.
#[tokio::test]
async fn the_group_lists_events_and_the_status_card_has_notices() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("phone").await;
    let lock = device_page(id, Section::Lock);
    let page = |path: String| {
        let app = &app;
        let cookie = &cookie;
        async move { app.get_page(&path, cookie).await.text() }
    };
    let events_group = |html: &str| -> String {
        let start = html.find("data-group=\"pin-events\"").unwrap();
        let end = html[start..].find("</section>").unwrap() + start;
        html[start..end].to_string()
    };

    // An older launcher: no list, the old flag instead.
    post_status_caps(&app, &token, &["pin_lock_v1"], true).await;
    let html = page(lock.clone()).await;
    assert!(
        events_group(&html).contains("Vibb on this phone doesn't report this yet."),
        "{html}"
    );
    assert!(html.contains("data-warning=\"override-used\""), "{html}");

    // A new one: the list (empty first), no old flag.
    post_status_caps(&app, &token, &["pin_lock_v1", "parent_pin_events_v1"], true).await;
    let html = page(lock.clone()).await;
    assert!(events_group(&html).contains("None reported yet."), "{html}");
    assert!(!html.contains("override-used"), "{html}");

    // Minutes ago, so "N min ago" whatever the local time of day.
    let now = chrono::Utc::now().timestamp_millis();
    let body = serde_json::json!({ "events": [
        event(&hex_id(1), "wrong", "override", now - 30 * 60_000, serde_json::json!({ "failures": 1 })),
        event(&hex_id(2), "unlocked", "settings", now - 20 * 60_000, serde_json::json!({})),
        event(&hex_id(3), "unlocked", "pin_lock", now - 10 * 60_000, serde_json::json!({})),
    ]})
    .to_string();
    assert_eq!(
        post_events(&app, &token, &body).await,
        StatusCode::NO_CONTENT
    );
    let html = page(lock.clone()).await;
    let group = events_group(&html);
    // Newest first, local times with the exact one in `title`, never UTC.
    let opened = group.find("Lock screen opened").expect("the use");
    let settings = group
        .find("Vibb&#39;s Settings opened")
        .expect("the other use");
    let wrong = group.find("Wrong Parent PIN").expect("the wrong try");
    assert!(opened < settings && settings < wrong, "{group}");
    assert!(
        group.contains("<span class=\"row-sub\">On the time-lock screen · 1 in a row</span>"),
        "{group}"
    );
    assert_eq!(group.matches("data-event").count(), 3);
    assert!(!group.contains("UTC"), "{group}");

    // The Status card: the latest use with the watched-typing tail, and the wrong try.
    let summary = page(device_page(id, Section::Summary)).await;
    assert!(
        summary.contains(
            "The Parent PIN was used on the phone 10 min ago: Lock screen opened. 1 more in the \
             last 24 hours."
        ),
        "{summary}"
    );
    assert!(
        summary.contains(&format!(
            "Typed where your child could see? <a href=\"{lock}#parent-pin\">"
        )),
        "{summary}"
    );
    assert!(
        summary.contains("1 wrong Parent PIN was typed on the phone in the last 24 hours."),
        "{summary}"
    );

    // A lockout replaces the wrong-try notice, and is red in the list.
    let body =
        serde_json::json!({ "events": [ event(&hex_id(4), "lockout", "override", now - 60_000,
        serde_json::json!({ "level": 1, "until_ms": now + 14 * 60_000 })) ] })
        .to_string();
    post_events(&app, &token, &body).await;
    let summary = page(device_page(id, Section::Summary)).await;
    assert!(
        summary.contains("The Parent PIN was locked on the phone after repeated wrong tries"),
        "{summary}"
    );
    assert!(!summary.contains("wrong Parent PIN was typed"), "{summary}");
    let group = events_group(&page(lock).await);
    assert!(
        group.contains("<span class=\"text-alarm\">Parent PIN locked</span>"),
        "{group}"
    );
    assert!(group.contains("Locked for 15 minutes, until "), "{group}");
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
/// list on Lock Screen & PINs keeps every event.
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
    let page = |section: Section| {
        let (app, cookie) = (&app, &cookie);
        async move { app.get_page(&device_page(id, section), cookie).await.text() }
    };
    let notice = "The Parent PIN was locked on the phone after repeated wrong tries";
    assert!(page(Section::Summary).await.contains(notice));

    save(&app, &cookie, id, &[("new_pin", "4719305")]).await;
    let html = page(Section::Summary).await;
    assert!(!html.contains(notice), "{html}");
    let html = page(Section::Lock).await;
    assert!(
        html.contains("5 wrong in a row, the last on the lock screen"),
        "{html}"
    );
    // An event after the save shows again.
    let body = serde_json::json!({ "events": [ event(&hex_id(3), "wrong", "settings",
        chrono::Utc::now().timestamp_millis() + 3_000, serde_json::json!({ "failures": 1 })) ] })
    .to_string();
    post_events(&app, &token, &body).await;
    assert!(
        page(Section::Summary)
            .await
            .contains("1 wrong Parent PIN was typed on the phone")
    );
}
