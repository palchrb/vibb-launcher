//! Security review S-L10: Advanced › Connection's "Leave Tailnet…" queues `leave_tailnet` behind a ticked box, and
//! the launcher's answers (the shared contract `testdata/leave_tailnet.json`, byte-identical in the launcher's test
//! resources; its `SecurityLowsBTest` produces each row) are worded. The row shows only while the last status report
//! says `tailnet_joined` (the contract's `report`, which the launcher encodes).

use axum::http::{Method, StatusCode};
use serde_json::Value;

use super::{TestApp, assert_redirect};
use crate::i18n::Lang;

const CONTRACT: &str = include_str!("../../testdata/leave_tailnet.json");

#[test]
fn the_leave_tailnet_contract_matches_the_launchers_copy() {
    let copy_path = std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("../launcher/app/src/test/resources/leave_tailnet.json");
    let copy = std::fs::read_to_string(&copy_path)
        .unwrap_or_else(|e| panic!("{}: {e}", copy_path.display()));
    assert_eq!(copy, CONTRACT, "{} differs", copy_path.display());
}

#[test]
fn every_answer_of_the_contract_is_worded() {
    let contract: Value = serde_json::from_str(CONTRACT).unwrap();
    let command = contract["command"].as_str().unwrap();
    assert_eq!(command, crate::handlers::devices::LEAVE_TAILNET);
    assert_ne!(
        crate::models::command_label(command),
        "locate.command.unknown"
    );
    for row in contract["answers"].as_array().unwrap() {
        let message = row["message"].as_str().unwrap();
        for lang in [Lang::En, Lang::Nb] {
            let worded = crate::handlers::locate::reword(lang, message);
            assert!(worded.as_deref().is_some_and(|w| w != message), "{message}");
        }
    }
}

#[tokio::test]
async fn leave_tailnet_is_queued_only_when_ticked() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("phone").await;
    let path = format!("/devices/{id}/leave-tailnet");
    let advanced = |notice: &'static str| {
        let (app, cookie) = (&app, &cookie);
        async move {
            let page = app
                .get_page(&format!("/devices/{id}/advanced{notice}"), cookie)
                .await
                .text();
            let connection = &page[page.find("<section id=\"connection\"").unwrap()..];
            connection[..connection.find("</section>").unwrap()].to_string()
        }
    };
    let form = format!("action=\"/devices/{id}/leave-tailnet#connection\"");
    // Hidden without a report that says so (an older launcher), and while the phone says it isn't on one.
    assert!(!advanced("").await.contains(&form));
    app.post_status(&token, serde_json::json!({"tailnet_joined": false}))
        .await;
    assert!(!advanced("").await.contains(&form));
    let contract: Value = serde_json::from_str(CONTRACT).unwrap();
    app.post_status(&token, contract["report"].clone()).await;
    assert!(advanced("").await.contains(&form));

    let res = app
        .request_form(Method::POST, &path, Some(&cookie), &[])
        .await;
    assert_redirect(
        &res,
        &format!("/devices/{id}/advanced?notice=tailnet_unconfirmed"),
        Some("connection"),
    );
    let res = app
        .request(Method::GET, "/api/devices/policy", Some(&token), None)
        .await;
    assert!(res.json()["pending_command"].is_null(), "nothing queued");

    let res = app
        .request_form(Method::POST, &path, Some(&cookie), &[("confirm", "yes")])
        .await;
    assert_redirect(
        &res,
        &format!("/devices/{id}/advanced?notice=tailnet_leave_queued"),
        Some("connection"),
    );
    let res = app
        .request(Method::GET, "/api/devices/policy", Some(&token), None)
        .await;
    assert_eq!(res.status, StatusCode::OK);
    assert_eq!(res.json()["pending_command"]["command"], "leave_tailnet");

    // The flash lands in Connection, where the button is.
    let connection = advanced("?notice=tailnet_leave_queued").await;
    assert!(connection.contains(&form));
    assert!(connection.contains("The phone leaves the tailnet when it next connects."));
}
