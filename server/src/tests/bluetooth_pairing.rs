//! Security review S-L5: Device › Home › Quick Controls' "Any Bluetooth Device" (`bluetooth_any_device`, migration
//! 0124) - off by default, saved with the quick controls' form only, sent in every policy.

use axum::http::Method;
use serde_json::json;

use super::TestApp;

#[tokio::test]
async fn any_bluetooth_device_is_off_until_the_parent_switches_it_on() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("phone").await;
    assert_eq!(
        app.policy(&token).await["bluetooth_any_device"],
        json!(false)
    );

    let save = |fields: Vec<(&'static str, &'static str)>| {
        let (app, cookie) = (&app, &cookie);
        async move {
            app.request_form(
                Method::POST,
                &format!("/devices/{id}/policy"),
                Some(cookie),
                &fields,
            )
            .await
        }
    };
    let res = save(vec![
        ("section", "quick"),
        ("quick_control_bluetooth", "on"),
        ("bluetooth_any_device", "on"),
    ])
    .await;
    super::assert_redirect(&res, &format!("/devices/{id}/home"), Some("quick-controls"));
    assert_eq!(
        app.policy(&token).await["bluetooth_any_device"],
        json!(true)
    );
    // The web filter's form doesn't carry it: it stays.
    save(vec![("section", "filter"), ("vpn_filter_enabled", "on")]).await;
    assert_eq!(
        app.policy(&token).await["bluetooth_any_device"],
        json!(true)
    );
    // Unticked is off, as every switch.
    save(vec![
        ("section", "quick"),
        ("quick_control_bluetooth", "on"),
    ])
    .await;
    assert_eq!(
        app.policy(&token).await["bluetooth_any_device"],
        json!(false)
    );
}
