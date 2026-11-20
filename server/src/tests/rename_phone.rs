//! Advanced's Name row (`POST /devices/{id}/name`): Add Phone's name rule, a refusal keeps the old
//! name and the typed one, any parent can rename, the save returns to `#name`, and the policy
//! is not touched (the name is the PWA's alone).

use axum::http::{Method, StatusCode};

use super::TestApp;

async fn name_of(app: &TestApp, id: i64) -> String {
    sqlx::query_scalar("SELECT name FROM devices WHERE id = ?")
        .bind(id)
        .fetch_one(&app.db)
        .await
        .unwrap()
}

async fn post_name(app: &TestApp, cookie: &str, id: i64, name: &str) -> super::TestResponse {
    app.request_form(
        Method::POST,
        &format!("/devices/{id}/name"),
        Some(cookie),
        &[("name", name)],
    )
    .await
}

async fn saves(app: &TestApp) -> i64 {
    sqlx::query_scalar("SELECT COUNT(*) FROM security_events WHERE event_type = 'settings_saved'")
        .fetch_one(&app.db)
        .await
        .unwrap()
}

#[tokio::test]
async fn a_rename_saves_trimmed_and_returns_to_its_row() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("Anna").await;
    let before = app
        .request(Method::GET, "/api/devices/policy", Some(&token), None)
        .await
        .text();
    let res = post_name(&app, &cookie, id, "  Emma ").await;
    assert_eq!(
        res.location(),
        Some(format!("/devices/{id}/advanced#name").as_str())
    );
    app.assert_lands(&res, &cookie).await;
    assert_eq!(name_of(&app, id).await, "Emma");
    let page = app
        .get_page(&format!("/devices/{id}/advanced"), &cookie)
        .await
        .text();
    assert!(page.contains(&format!("action=\"/devices/{id}/name#name\"")));
    assert!(page.contains("Emma"));
    // The name never reaches the phone.
    let after = app
        .request(Method::GET, "/api/devices/policy", Some(&token), None)
        .await
        .text();
    assert_eq!(before, after);
    // A save, in the settings log.
    assert_eq!(saves(&app).await, 1);
}

#[tokio::test]
async fn a_refused_name_keeps_the_old_one_and_shows_why() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, _) = app.enrolled_device("Anna").await;
    let res = post_name(&app, &cookie, id, "   ").await;
    assert_eq!(res.status, StatusCode::BAD_REQUEST);
    assert!(res.text().contains("field-error"));
    assert_eq!(name_of(&app, id).await, "Anna");
    let long = "x".repeat(41);
    let res = post_name(&app, &cookie, id, &long).await;
    assert_eq!(res.status, StatusCode::BAD_REQUEST);
    let html = res.text();
    assert!(html.contains("field-error"));
    assert!(html.contains(&format!("value=\"{long}\"")), "typed kept");
    assert_eq!(name_of(&app, id).await, "Anna");
    // Forty is fine.
    let ok = "y".repeat(40);
    assert!(
        post_name(&app, &cookie, id, &ok)
            .await
            .status
            .is_redirection()
    );
    assert_eq!(name_of(&app, id).await, ok);
    // Nothing was logged for the two refusals.
    assert_eq!(saves(&app).await, 1);
}

#[tokio::test]
async fn another_parent_can_rename() {
    let app = TestApp::new().await;
    let _first = app.admin_cookie().await;
    let second = app.admin_cookie_as("mamma", "Mamma").await;
    let (id, _) = app.enrolled_device("Anna").await;
    let res = post_name(&app, &second, id, "Ida").await;
    assert_eq!(
        res.location(),
        Some(format!("/devices/{id}/advanced#name").as_str())
    );
    assert_eq!(name_of(&app, id).await, "Ida");
}

#[tokio::test]
async fn a_rename_needs_a_session_and_a_phone() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, _) = app.enrolled_device("Anna").await;
    let res = app
        .request_form(
            Method::POST,
            &format!("/devices/{id}/name"),
            None,
            &[("name", "Z")],
        )
        .await;
    assert_ne!(res.status, StatusCode::OK);
    assert_eq!(name_of(&app, id).await, "Anna");
    let res = post_name(&app, &cookie, id + 99, "Z").await;
    assert_eq!(res.status, StatusCode::NOT_FOUND);
}
