//! Design 76: the launcher's certificate check through the routes - the shared contract `testdata/server_tls_report.json`
//! (byte-identical in the launcher's test resources; its `ServerPinsTest` encodes the report and runs every flag row),
//! the policy's `server_tls` per state, the device page's group and the parent's one-shots.

use axum::http::{Method, StatusCode};
use serde_json::{Value, json};

use super::{TestApp, assert_redirect};

const CONTRACT: &str = include_str!("../../testdata/server_tls_report.json");

#[test]
fn the_contract_matches_the_launchers_copy() {
    let copy_path = std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("../launcher/app/src/test/resources/server_tls_report.json");
    let copy = std::fs::read_to_string(&copy_path)
        .unwrap_or_else(|e| panic!("{}: {e}", copy_path.display()));
    assert_eq!(copy, CONTRACT, "{} differs", copy_path.display());
}

fn capable() -> Value {
    json!({"capabilities": [crate::server_tls::CAPABILITY]})
}

/// The contract's report, its instants moved to the last hour.
fn report_now() -> Value {
    let contract: Value = serde_json::from_str(CONTRACT).unwrap();
    let mut report = contract["report"].clone();
    let now = chrono::Utc::now().timestamp_millis();
    let tls = &mut report["server_tls"];
    tls["seen"]["at_ms"] = json!(now - 60_000);
    for (i, row) in tls["outside"]
        .as_array_mut()
        .unwrap()
        .iter_mut()
        .enumerate()
    {
        row["at_ms"] = json!(now - 3_600_000 + i as i64 * 60_000);
    }
    report["capabilities"] = json!([crate::server_tls::CAPABILITY]);
    report
}

async fn advanced(app: &TestApp, id: i64, cookie: &str, query: &str) -> Option<String> {
    let page = app
        .get_page(&format!("/devices/{id}/advanced{query}"), cookie)
        .await
        .text();
    let start = page.find("<section id=\"server-tls\"")?;
    let group = &page[start..];
    Some(group[..group.find("</section>").unwrap()].to_string())
}

async fn events(app: &TestApp, id: i64, kind: &str) -> i64 {
    sqlx::query_scalar("SELECT COUNT(*) FROM parent_events WHERE device_id = ? AND kind = ?")
        .bind(id)
        .bind(kind)
        .fetch_one(&app.db)
        .await
        .unwrap()
}

#[tokio::test]
async fn a_launcher_report_moves_the_state() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("Anna").await;
    // An older launcher: no `server_tls` in the policy, no group on the page.
    app.post_status(&token, json!({})).await;
    assert!(app.policy(&token).await.get("server_tls").is_none());
    assert_eq!(advanced(&app, id, &cookie, "").await, None);

    // The contract's report: one in-set sighting and two outside rows - stored, the switch-on stopped, pushed once.
    let report = report_now();
    app.post_status(&token, report.clone()).await;
    let row = crate::server_tls::load(&app.db, id).await.unwrap();
    assert_eq!(row.state, crate::server_tls::State::Watching);
    assert_eq!(row.outside.len(), 2);
    assert!(row.stopped_at.is_some());
    assert!(row.mobile_seen, "the outside row was on mobile data");
    assert_eq!(row.phone_enforcing, Some(false));
    assert_eq!(events(&app, id, "server_tls_seen").await, 1);
    app.post_status(&token, report).await;
    assert_eq!(events(&app, id, "server_tls_seen").await, 1, "one a day");
    assert_eq!(
        crate::server_tls::load(&app.db, id)
            .await
            .unwrap()
            .outside
            .len(),
        2,
        "the same rows again"
    );
    let group = advanced(&app, id, &cookie, "").await.unwrap();
    assert!(
        group.contains("this phone saw a certificate from emSign Root CA - G1 on Wi-Fi"),
        "{group}"
    );
    assert!(
        group.contains("Refused a certificate for your server from emSign Root CA - G1 on Wi-Fi"),
        "{group}"
    );
    assert!(group.contains(&format!(
        "action=\"/devices/{id}/server-tls/on#server-tls\""
    )));
    assert!(group.contains(&format!(
        "action=\"/devices/{id}/server-tls/off#server-tls\""
    )));

    // The phone says its check is on while the switch-on is stopped (code QA L-3): not adopted, a security line.
    let mut says_on = report_now();
    says_on["server_tls"]["enforcing"] = json!(true);
    app.post_status(&token, says_on.clone()).await;
    app.post_status(&token, says_on).await;
    assert_eq!(
        crate::server_tls::load(&app.db, id).await.unwrap().state,
        crate::server_tls::State::Watching
    );
    let phone_on: i64 = sqlx::query_scalar(
        "SELECT COUNT(*) FROM security_events WHERE event_type = 'server_tls_phone_on'",
    )
    .fetch_one(&app.db)
    .await
    .unwrap();
    assert_eq!(phone_on, 1, "told once");
    let group = advanced(&app, id, &cookie, "").await.unwrap();
    assert!(group.contains("The phone says its check is on."), "{group}");
    // No recovery address known: the page says how to find it (code QA H-1).
    assert!(group.contains("Tailnet recovery address"), "{group}");

    // The policy's rows: each state's `server_tls`, `watching` without `enforce` (R-1), with the recovery address
    // when this server knows it (here: the setup QR's own tailnet address).
    let contract: Value = serde_json::from_str(CONTRACT).unwrap();
    for row in contract["policy"].as_array().unwrap() {
        sqlx::query("UPDATE provisioning_settings SET server_url = ?")
            .bind(row["recovery_url"].as_str().unwrap_or(""))
            .execute(&app.db)
            .await
            .unwrap();
        sqlx::query("UPDATE device_server_tls SET state = ? WHERE device_id = ?")
            .bind(row["state"].as_str().unwrap())
            .bind(id)
            .execute(&app.db)
            .await
            .unwrap();
        assert_eq!(
            app.policy(&token).await["server_tls"],
            row["server_tls"],
            "{row}"
        );
    }
    let group = advanced(&app, id, &cookie, "").await.unwrap();
    assert!(
        group.contains("Recovery address: http://100.101.102.103:3101"),
        "{group}"
    );
    sqlx::query("UPDATE provisioning_settings SET server_url = ''")
        .execute(&app.db)
        .await
        .unwrap();

    // A phone that reports `enforcing: true` while watched stored it from a pinned `true`: adopted as on.
    let (other, other_token) = app.enrolled_device("Ola").await;
    let mut enforcing = report_now();
    enforcing["server_tls"]["enforcing"] = json!(true);
    enforcing["server_tls"]["outside"] = json!([]);
    app.post_status(&other_token, enforcing).await;
    assert_eq!(
        crate::server_tls::load(&app.db, other).await.unwrap().state,
        crate::server_tls::State::On
    );
    assert_eq!(
        app.policy(&other_token).await["server_tls"],
        json!({"enforce": true})
    );
    // Verification L-b: the adoption is a security line, by no parent, for this phone and naming it.
    let adopted: Vec<(Option<String>, Option<String>)> = sqlx::query_as(
        "SELECT detail, params FROM security_events WHERE event_type = 'server_tls_on' AND username IS NULL",
    )
    .fetch_all(&app.db)
    .await
    .unwrap();
    assert_eq!(adopted.len(), 1, "{adopted:?}");
    assert_eq!(adopted[0].0, Some(format!("device {other}")));
    let params = adopted[0].1.clone().unwrap_or_default();
    assert!(
        params.contains("Ola") && params.contains("server_tls.adopted"),
        "{params}"
    );
    let group = advanced(&app, other, &cookie, "").await.unwrap();
    assert!(
        group.contains("Certificate check: on - only Let&#39;s Encrypt or ZeroSSL"),
        "{group}"
    );
}

#[tokio::test]
async fn a_fresh_phone_is_watched_and_shows_when_it_switches_on() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("Anna").await;
    app.post_status(&token, capable()).await;
    assert_eq!(app.policy(&token).await["server_tls"], json!({}));
    let group = advanced(&app, id, &cookie, "").await.unwrap();
    assert!(
        group.contains("Certificate check: watching - on from"),
        "{group}"
    );
    assert!(
        !group.contains("server-tls/on#"),
        "no on-now while nothing was seen"
    );
    // Junk in the report never fails it.
    app.post_status(
        &token,
        json!({"capabilities": [crate::server_tls::CAPABILITY], "server_tls": "on"}),
    )
    .await;
    app.post_status(&token, json!({"capabilities": [crate::server_tls::CAPABILITY], "server_tls": {"outside": [1, {"kind": "x"}]}}))
        .await;
    assert_eq!(
        crate::server_tls::load(&app.db, id).await.unwrap(),
        crate::server_tls::Row::default()
    );
}

#[tokio::test]
async fn the_parents_one_shots_are_loud_and_at_once() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("Anna").await;
    app.post_status(&token, capable()).await;
    let mut nudges = app.state.command_notify.subscribe();

    // Off only when ticked.
    let res = app
        .request_form(
            Method::POST,
            &format!("/devices/{id}/server-tls/off"),
            Some(&cookie),
            &[],
        )
        .await;
    assert_redirect(
        &res,
        &format!("/devices/{id}/advanced?notice=server_tls_unconfirmed"),
        Some("server-tls"),
    );
    assert_eq!(app.policy(&token).await["server_tls"], json!({}));
    let res = app
        .request_form(
            Method::POST,
            &format!("/devices/{id}/server-tls/off"),
            Some(&cookie),
            &[("confirm", "yes")],
        )
        .await;
    assert_redirect(
        &res,
        &format!("/devices/{id}/advanced?notice=server_tls_off"),
        Some("server-tls"),
    );
    assert_eq!(
        nudges.try_recv().ok(),
        Some(id),
        "the phone is nudged at once"
    );
    assert_eq!(
        app.policy(&token).await["server_tls"],
        json!({"enforce": false})
    );
    assert_eq!(events(&app, id, "server_tls_off").await, 1);
    let logged: Vec<(String, Option<String>)> = sqlx::query_as(
        "SELECT event_type, username FROM security_events WHERE event_type LIKE 'server_tls_%'",
    )
    .fetch_all(&app.db)
    .await
    .unwrap();
    assert_eq!(
        logged,
        [("server_tls_off".to_string(), Some("parent".to_string()))]
    );
    let saves: i64 = sqlx::query_scalar(
        "SELECT COUNT(*) FROM security_events WHERE event_type = 'settings_saved'",
    )
    .fetch_one(&app.db)
    .await
    .unwrap();
    assert_eq!(saves, 0, "its own event, not the generic save");
    let group = advanced(&app, id, &cookie, "?notice=server_tls_off")
        .await
        .unwrap();
    assert!(group.contains("Off since"), "{group}");
    assert!(group.contains("The certificate check is off."), "{group}");
    assert!(group.contains(&format!(
        "action=\"/devices/{id}/server-tls/watch#server-tls\""
    )));
    assert!(group.contains(&format!(
        "action=\"/devices/{id}/server-tls/on#server-tls\""
    )));
    // An outside report while off: stored, never pushed.
    app.post_status(&token, report_now()).await;
    assert_eq!(events(&app, id, "server_tls_seen").await, 0);

    // Back to watching, then on now; each its own `server_tls_on` line.
    for (action, policy) in [("watch", json!({})), ("on", json!({"enforce": true}))] {
        let res = app
            .request_form(
                Method::POST,
                &format!("/devices/{id}/server-tls/{action}"),
                Some(&cookie),
                &[],
            )
            .await;
        assert_eq!(res.status, StatusCode::SEE_OTHER, "{action}");
        assert_eq!(app.policy(&token).await["server_tls"], policy, "{action}");
    }
    // "Turn it on again" only from off: from on it changes nothing (code QA nit).
    let res = app
        .request_form(
            Method::POST,
            &format!("/devices/{id}/server-tls/watch"),
            Some(&cookie),
            &[],
        )
        .await;
    assert_redirect(
        &res,
        &format!("/devices/{id}/advanced?notice=server_tls_unchanged"),
        Some("server-tls"),
    );
    assert_eq!(
        app.policy(&token).await["server_tls"],
        json!({"enforce": true})
    );
    let on_lines: i64 = sqlx::query_scalar(
        "SELECT COUNT(*) FROM security_events WHERE event_type = 'server_tls_on'",
    )
    .fetch_one(&app.db)
    .await
    .unwrap();
    assert_eq!(on_lines, 2);
    let res = app
        .request_form(
            Method::POST,
            &format!("/devices/{id}/server-tls/nonsense"),
            Some(&cookie),
            &[],
        )
        .await;
    assert_eq!(res.status, StatusCode::NOT_FOUND);
}
