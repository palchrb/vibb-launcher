//! Settings saves in the security log (Q29, decision 10): a POST answered with a 303 back to its
//! page is one `settings_saved` row per parent and page for 10 minutes; a refusal (a 4xx, a form
//! rendered again) and a command are not saves; `/music` saves are.

use axum::http::{Method, StatusCode};

use super::TestApp;

/// `(username, detail)` of every `settings_saved`, oldest first.
async fn saves(app: &TestApp) -> Vec<(String, String)> {
    sqlx::query_as(
        "SELECT username, detail FROM security_events WHERE event_type = 'settings_saved' \
         ORDER BY id",
    )
    .fetch_all(&app.db)
    .await
    .unwrap()
}

async fn post(app: &TestApp, cookie: &str, path: &str, form: &[(&str, &str)]) -> StatusCode {
    app.request_form(Method::POST, path, Some(cookie), form)
        .await
        .status
}

#[tokio::test]
async fn saves_on_one_page_are_one_row_for_ten_minutes() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, _) = app.create_device("Anna").await;
    let hardening = format!("/devices/{id}/hardening");
    let kiosk = format!("/devices/{id}/kiosk-block");
    for path in [&hardening, &hardening, &kiosk] {
        assert_eq!(post(&app, &cookie, path, &[]).await, StatusCode::SEE_OTHER);
    }
    let page = format!("/devices/{id}/advanced");
    let row = format!(
        "{page} · /devices/{{id}}/hardening (id {id}) ×2; /devices/{{id}}/kiosk-block (id {id})"
    );
    assert_eq!(saves(&app).await, vec![("parent".to_string(), row.clone())]);

    // Another page: its own row; Music › Settings' sweep is a save too.
    assert_eq!(
        post(&app, &cookie, "/music/sweep", &[("hours", "6")]).await,
        StatusCode::SEE_OTHER
    );
    let music = "/music/settings · /music/sweep".to_string();
    assert_eq!(saves(&app).await[1], ("parent".to_string(), music));

    // Ten minutes after the row began, the page's next save starts a new one.
    sqlx::query(
        "UPDATE security_events SET created_at = datetime('now', '-11 minutes') \
         WHERE event_type = 'settings_saved'",
    )
    .execute(&app.db)
    .await
    .unwrap();
    post(&app, &cookie, &hardening, &[]).await;
    let all = saves(&app).await;
    assert_eq!(all.len(), 3);
    assert_eq!(all[0].1, row, "the old row is kept as it was");
    assert_eq!(
        all[2].1,
        format!("{page} · /devices/{{id}}/hardening (id {id})")
    );

    // Another parent's save on the same page is theirs.
    let pappa = app.admin_cookie_as("pappa", "Pappa").await;
    post(&app, &pappa, &kiosk, &[]).await;
    let all = saves(&app).await;
    assert_eq!(all.len(), 4);
    assert_eq!(all[3].0, "pappa");
}

#[tokio::test]
async fn a_refusal_or_a_command_is_not_a_save() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, _) = app.enrolled_device("Anna").await;
    // A choice that isn't one: a 400, nothing written.
    assert_eq!(
        post(&app, &cookie, "/music/sweep", &[("hours", "5")]).await,
        StatusCode::BAD_REQUEST
    );
    // A form rendered again with its error (Phone Setup's language).
    assert_eq!(
        post(
            &app,
            &cookie,
            "/settings/provisioning",
            &[("server_url", ""), ("locale", "norsk"), ("time_zone", "")]
        )
        .await,
        StatusCode::BAD_REQUEST
    );
    // An unknown phone.
    assert_eq!(
        post(&app, &cookie, "/devices/9999/hardening", &[]).await,
        StatusCode::NOT_FOUND
    );
    // A command answers 303 but is its own event.
    assert_eq!(
        post(&app, &cookie, &format!("/devices/{id}/command/ring"), &[]).await,
        StatusCode::SEE_OTHER
    );
    assert_eq!(saves(&app).await, Vec::<(String, String)>::new());
    // Signed out: nothing reaches the handler, nothing is logged.
    let res = app
        .request_form(Method::POST, "/music/sweep", None, &[("hours", "6")])
        .await;
    assert_ne!(res.status, StatusCode::INTERNAL_SERVER_ERROR);
    assert_eq!(saves(&app).await, Vec::<(String, String)>::new());
}
