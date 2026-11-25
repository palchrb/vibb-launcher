//! The apps with notification access and Advanced › Android Auto: the shared contract
//! `contracts/core/notification_listeners.json` - the policy rows from the switch's saves, the report stored as the contract
//! has it - and About This Phone's row (quiet for an app the parent gave, orange for one it didn't).

use axum::http::{Method, StatusCode};
use serde_json::{Value, json};

use super::TestApp;

const CONTRACT: &str = crate::contracts::contract!("core/notification_listeners.json");

fn contract() -> Value {
    serde_json::from_str(CONTRACT).unwrap()
}

async fn stored(app: &TestApp, id: i64) -> Option<String> {
    sqlx::query_scalar(
        "SELECT notification_listeners_json FROM device_status WHERE device_id = ? \
         ORDER BY id DESC LIMIT 1",
    )
    .bind(id)
    .fetch_one(&app.state.db)
    .await
    .unwrap()
}

/// A new phone hides Android Auto (`policy.off`); the switch's saves give `policy.on` and back, each answered at the
/// switch's own group and nudging the phone.
#[tokio::test]
async fn the_switch_makes_the_policy_rows() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("Emma").await;
    let rows = &contract()["policy"];
    let key = "allow_android_auto";
    assert_eq!(app.policy(&token).await[key], rows["off"][key]);
    let mut nudges = app.state.command_notify.subscribe();
    for (form, row) in [(&[(key, "on")][..], "on"), (&[][..], "off")] {
        let res = app
            .request_form(
                Method::POST,
                &format!("/devices/{id}/android-auto"),
                Some(&cookie),
                form,
            )
            .await;
        super::assert_redirect(
            &res,
            &format!("/devices/{id}/advanced"),
            Some("android-auto"),
        );
        assert_eq!(nudges.try_recv().ok(), Some(id));
        assert_eq!(app.policy(&token).await[key], rows[row][key], "{row}");
    }
    let res = app
        .request_form(
            Method::POST,
            "/devices/9999/android-auto",
            Some(&cookie),
            &[(key, "on")],
        )
        .await;
    assert_eq!(res.status, StatusCode::NOT_FOUND);
    let html = app
        .get_page(&format!("/devices/{id}/advanced"), &cookie)
        .await
        .text();
    assert_eq!(super::input_checked(&html, key, "on"), Some(false));
}

/// The report rows are stored exactly; a report without the key stores nothing.
#[tokio::test]
async fn the_report_is_stored_as_the_contract_has_it() {
    let app = TestApp::new().await;
    let (id, token) = app.enrolled_device("Emma").await;
    let contract = contract();
    for key in ["report", "report_none"] {
        app.post_status(&token, json!({"notification_listeners": contract[key]}))
            .await;
        let kept: Value = serde_json::from_str(&stored(&app, id).await.unwrap()).unwrap();
        assert_eq!(kept, contract[key], "{key}");
    }
    app.post_status(&token, json!({})).await;
    assert_eq!(stored(&app, id).await, None);
}

/// About's row: orange while an app the parent didn't give reads, quiet once it is an allowed app; Vibb's own off
/// ones never count, and one that came with the phone is named quietly.
#[tokio::test]
async fn about_names_an_unknown_reader_in_orange() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("Emma").await;
    app.post_status(
        &token,
        json!({
            "installed_apps": [{"package_name": "com.example.watch", "label": "Watch"}],
            "notification_listeners": contract()["report"],
        }),
    )
    .await;
    let about = format!("/devices/{id}/about");
    let html = app.get_page(&about, &cookie).await.text();
    assert!(html.contains("data-row=\"listeners\""), "{html}");
    assert!(
        html.contains("<span class=\"row-value row-value-action\">Watch</span>"),
        "{html}"
    );
    assert!(!html.contains("gearhead"), "{html}");
    sqlx::query(
        "UPDATE device_policy SET allowlist_json = '[\"com.example.watch\"]' WHERE device_id = ?",
    )
    .bind(id)
    .execute(&app.db)
    .await
    .unwrap();
    let html = app.get_page(&about, &cookie).await.text();
    assert!(
        html.contains("<span class=\"row-value\">com.android.launcher3, Watch</span>"),
        "{html}"
    );
    assert!(!html.contains("row-value-action\">Watch"), "{html}");
}
