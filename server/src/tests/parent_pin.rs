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

    let location = save(&app, &cookie, id, &[("new_pin", " 583920 ")]).await;
    assert_eq!(
        location,
        format!("/devices/{id}?notice=parent_pin_saved#parent-pin")
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
