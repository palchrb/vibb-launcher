//! Design 73: a phone's emergency menu - `call_policy.emergency_menu` and Device › Calls' Emergency Menu group (add,
//! move, remove, the cap, the suggestions). Nothing here dials anything.

use axum::http::{Method, StatusCode};
use serde_json::json;

use super::{TestApp, assert_redirect};

async fn page(app: &TestApp, cookie: &str, id: i64) -> String {
    let res = app.get_page(&format!("/devices/{id}/calls"), cookie).await;
    assert_eq!(res.status, StatusCode::OK);
    res.text()
}

async fn add(
    app: &TestApp,
    cookie: &str,
    id: i64,
    number: &str,
    name: &str,
) -> super::TestResponse {
    app.request_form(
        Method::POST,
        &format!("/devices/{id}/calls/emergency"),
        Some(cookie),
        &[("number", number), ("name", name)],
    )
    .await
}

async fn entry(app: &TestApp, id: i64, number: &str) -> i64 {
    sqlx::query_scalar("SELECT id FROM device_emergency_numbers WHERE device_id = ? AND number = ?")
        .bind(id)
        .bind(number)
        .fetch_one(&app.db)
        .await
        .unwrap()
}

async fn menu(app: &TestApp, token: &str) -> serde_json::Value {
    app.policy(token).await["call_policy"]["emergency_menu"].clone()
}

#[tokio::test]
async fn the_policy_offers_112_alone_by_default_managed_or_not() {
    let app = TestApp::new().await;
    let (id, token) = app.enrolled_device("phone").await;
    assert_eq!(
        menu(&app, &token).await,
        json!([{"number": "112", "name": ""}])
    );
    sqlx::query("UPDATE device_policy SET calls_mode = 'managed', sms_mode = 'unmanaged' WHERE device_id = ?")
        .bind(id)
        .execute(&app.db)
        .await
        .unwrap();
    assert_eq!(
        menu(&app, &token).await,
        json!([{"number": "112", "name": ""}])
    );
}

#[tokio::test]
async fn adding_keeps_112_first_and_normalises_the_number() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("phone").await;
    let res = add(&app, &cookie, id, "1 1 3", " Ambulanse ").await;
    let e113 = entry(&app, id, "113").await;
    assert_redirect(
        &res,
        &format!("/devices/{id}/calls?advanced=1"),
        Some(&format!("emergency-{e113}")),
    );
    add(&app, &cookie, id, "900 00 001", "Mamma").await;
    assert_eq!(
        menu(&app, &token).await,
        json!([
            {"number": "112", "name": ""},
            {"number": "113", "name": "Ambulanse"},
            {"number": "+4790000001", "name": "Mamma"},
        ])
    );
    let html = page(&app, &cookie, id).await;
    assert!(html.contains("First and largest on the phone"), "{html}");
    assert!(html.contains("Emergency number: always works"));
    assert!(
        html.contains(
            "row-sub tone-orange\">Not an emergency number to the server: follows the call rules"
        ),
        "Mamma's row is orange"
    );
}

#[tokio::test]
async fn a_refused_number_stays_in_the_open_form() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("phone").await;
    for (number, name, why) in [
        ("*112#", "", "That number can&#39;t be used"),
        ("112", "", "That number is on the menu already."),
        ("113", &"x".repeat(41), "A name has at most 40 characters."),
    ] {
        let res = add(&app, &cookie, id, number, name).await;
        assert_eq!(res.status, StatusCode::BAD_REQUEST, "{number}");
        let html = res.text();
        assert!(html.contains(why), "{number}: {html}");
        assert!(html.contains("id=\"add-emergency\" open"), "{number}");
    }
    assert_eq!(
        menu(&app, &token).await,
        json!([{"number": "112", "name": ""}])
    );
}

#[tokio::test]
async fn at_most_six_numbers() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("phone").await;
    for n in 1..=5 {
        let res = add(&app, &cookie, id, &format!("9000000{n}"), "").await;
        assert!(res.status.is_redirection(), "{n}");
    }
    assert_eq!(menu(&app, &token).await.as_array().unwrap().len(), 6);
    let html = page(&app, &cookie, id).await;
    assert!(
        !html.contains("id=\"add-emergency\""),
        "no Add Number when full"
    );
    assert!(
        !html.contains("data-suggestion"),
        "no suggestions when full"
    );
    let res = add(&app, &cookie, id, "113", "Ambulanse").await;
    assert_eq!(res.status, StatusCode::BAD_REQUEST);
    assert!(res.text().contains("The menu has 6 numbers already."));
    assert_eq!(menu(&app, &token).await.as_array().unwrap().len(), 6);
}

#[tokio::test]
async fn moving_swaps_with_the_neighbour_and_returns_to_the_row() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("phone").await;
    add(&app, &cookie, id, "113", "Ambulanse").await;
    add(&app, &cookie, id, "110", "Brann").await;
    let e110 = entry(&app, id, "110").await;
    let res = app
        .request_form(
            Method::POST,
            &format!("/devices/{id}/calls/emergency/{e110}/move"),
            Some(&cookie),
            &[("dir", "up")],
        )
        .await;
    assert_redirect(
        &res,
        &format!("/devices/{id}/calls?advanced=1"),
        Some(&format!("emergency-{e110}")),
    );
    let e112 = entry(&app, id, "112").await;
    app.request_form(
        Method::POST,
        &format!("/devices/{id}/calls/emergency/{e112}/move"),
        Some(&cookie),
        &[("dir", "down")],
    )
    .await;
    let numbers: Vec<String> = menu(&app, &token)
        .await
        .as_array()
        .unwrap()
        .iter()
        .map(|e| e["number"].as_str().unwrap().to_string())
        .collect();
    assert_eq!(numbers, ["110", "112", "113"]);
    // Another phone's row, or a bad direction, changes nothing.
    let (other, _) = app.enrolled_device("other").await;
    let res = app
        .request_form(
            Method::POST,
            &format!("/devices/{other}/calls/emergency/{e110}/move"),
            Some(&cookie),
            &[("dir", "down")],
        )
        .await;
    assert_redirect(
        &res,
        &format!("/devices/{other}/calls?advanced=1&notice=emergency_gone"),
        Some("emergency"),
    );
    let res = app
        .request_form(
            Method::POST,
            &format!("/devices/{id}/calls/emergency/{e110}/move"),
            Some(&cookie),
            &[("dir", "sideways")],
        )
        .await;
    assert_eq!(res.status, StatusCode::BAD_REQUEST);
}

#[tokio::test]
async fn the_menu_keeps_an_emergency_number() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("phone").await;
    add(&app, &cookie, id, "90000001", "Mamma").await;
    let e112 = entry(&app, id, "112").await;
    let remove = |entry: i64| format!("/devices/{id}/calls/emergency/{entry}/remove");
    // 112 is the only emergency number: no Remove, and a POST anyway is refused.
    let html = page(&app, &cookie, id).await;
    assert!(!html.contains(&remove(e112)), "{html}");
    let res = app
        .request_form(Method::POST, &remove(e112), Some(&cookie), &[])
        .await;
    assert_redirect(
        &res,
        &format!("/devices/{id}/calls?advanced=1&notice=emergency_kept"),
        Some("emergency"),
    );
    assert!(
        app.get_page(res.location().unwrap(), &cookie)
            .await
            .text()
            .contains("The menu keeps at least one emergency number")
    );
    // With 113 on it, 112 may go (replaced), and Mamma may go any time.
    add(&app, &cookie, id, "113", "Ambulanse").await;
    let res = app
        .request_form(Method::POST, &remove(e112), Some(&cookie), &[])
        .await;
    assert_redirect(
        &res,
        &format!("/devices/{id}/calls?advanced=1"),
        Some("emergency"),
    );
    let mamma = entry(&app, id, "+4790000001").await;
    app.request_form(Method::POST, &remove(mamma), Some(&cookie), &[])
        .await;
    assert_eq!(
        menu(&app, &token).await,
        json!([{"number": "113", "name": "Ambulanse"}])
    );
    // A second tap on a removed row.
    let res = app
        .request_form(Method::POST, &remove(mamma), Some(&cookie), &[])
        .await;
    assert_redirect(
        &res,
        &format!("/devices/{id}/calls?advanced=1&notice=emergency_gone"),
        Some("emergency"),
    );
}

#[tokio::test]
async fn the_suggestions_are_ambulance_and_fire_while_missing() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, _) = app.enrolled_device("phone").await;
    let html = page(&app, &cookie, id).await;
    assert!(html.contains("data-suggestion=\"113\""));
    assert!(html.contains("data-suggestion=\"110\""));
    assert_eq!(
        html.matches("data-suggestion=").count(),
        2,
        "no Legevakt (the user, 2026-11-01)"
    );
    add(&app, &cookie, id, "113", "Ambulance").await;
    let html = page(&app, &cookie, id).await;
    assert!(!html.contains("data-suggestion=\"113\""));
    assert!(html.contains("data-suggestion=\"110\""));
    // Norway's numbers only: another default country code has none.
    sqlx::query("UPDATE call_settings SET default_country_code = '46'")
        .execute(&app.db)
        .await
        .unwrap();
    assert!(!page(&app, &cookie, id).await.contains("data-suggestion"));
}

#[tokio::test]
async fn a_removed_phone_takes_its_menu() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, _) = app.enrolled_device("phone").await;
    add(&app, &cookie, id, "113", "").await;
    sqlx::query("DELETE FROM devices WHERE id = ?")
        .bind(id)
        .execute(&app.db)
        .await
        .unwrap();
    let left: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM device_emergency_numbers")
        .fetch_one(&app.db)
        .await
        .unwrap();
    assert_eq!(left, 0);
}

/// Code QA L4: on a managed phone a non-emergency number that isn't an outbound contact says the phone dims it.
#[tokio::test]
async fn a_number_outside_the_phone_book_says_it_is_dimmed() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, _) = app.enrolled_device("phone").await;
    add(&app, &cookie, id, "90000001", "Mamma").await;
    let dimmed = "Not in the phone book - dimmed on the phone";
    assert!(
        !page(&app, &cookie, id).await.contains(dimmed),
        "unmanaged: no line"
    );
    sqlx::query(
        "UPDATE device_policy SET calls_mode = 'managed', sms_mode = 'unmanaged' WHERE device_id = ?",
    )
    .bind(id)
    .execute(&app.db)
    .await
    .unwrap();
    assert!(page(&app, &cookie, id).await.contains(dimmed));
    let res = app
        .request_form(
            Method::POST,
            &format!("/devices/{id}/contacts"),
            Some(&cookie),
            &[("name", "Mamma"), ("number", "90000001")],
        )
        .await;
    assert!(res.status.is_redirection());
    let html = page(&app, &cookie, id).await;
    assert!(!html.contains(dimmed), "in the phone book now: {html}");
}

async fn numbers(app: &TestApp, token: &str) -> Vec<String> {
    menu(app, token)
        .await
        .as_array()
        .unwrap()
        .iter()
        .map(|e| e["number"].as_str().unwrap().to_string())
        .collect()
}

async fn remove_112(app: &TestApp, cookie: &str, id: i64) {
    let e112 = entry(app, id, "112").await;
    let res = app
        .request_form(
            Method::POST,
            &format!("/devices/{id}/calls/emergency/{e112}/remove"),
            Some(cookie),
            &[],
        )
        .await;
    assert!(res.status.is_redirection());
}

/// Code QA L3: a new default country code that leaves a menu without an emergency number puts 112 first, and the
/// menu stays at most six.
#[tokio::test]
async fn a_new_country_code_puts_112_back_first() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("phone").await;
    let (full, full_token) = app.enrolled_device("full").await;
    add(&app, &cookie, id, "113", "Ambulanse").await;
    remove_112(&app, &cookie, id).await;
    add(&app, &cookie, full, "113", "").await;
    for n in 1..=4 {
        add(&app, &cookie, full, &format!("9000000{n}"), "").await;
    }
    remove_112(&app, &cookie, full).await;
    add(&app, &cookie, full, "110", "").await;
    assert_eq!(numbers(&app, &full_token).await.len(), 6);
    let res = app
        .request_form(
            Method::POST,
            "/settings/calls",
            Some(&cookie),
            &[("default_country_code", "46")],
        )
        .await;
    assert!(res.status.is_redirection(), "{}", res.text());
    assert_eq!(numbers(&app, &token).await, ["112", "113"]);
    assert_eq!(
        numbers(&app, &full_token).await,
        [
            "112",
            "113",
            "+4790000001",
            "+4790000002",
            "+4790000003",
            "+4790000004"
        ],
        "112 first, the last one (110) gone"
    );
}
