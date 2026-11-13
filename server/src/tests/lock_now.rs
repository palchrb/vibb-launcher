//! Design 93: a rule turned on now ("Slå på nå") and the Regler sheet's routes. The pure parts and
//! the contract are `lock_now::tests`.

use axum::http::{Method, StatusCode};
use serde_json::{Value, json};

use super::{TestApp, assert_redirect};

/// A zone where the local day has at least four hours left now, so "Ut dagen" and every length
/// are offered whenever the suite runs.
fn roomy_zone() -> &'static str {
    use chrono::Timelike;
    let utc = chrono::Utc::now();
    if utc.hour() < 20 { "UTC" } else { "Etc/GMT-12" }
}

/// Emma's phone with everyone's Skole (never scheduled), its report naming `zone`; returns the
/// app, the admin cookie, the phone, its token and Skole's id.
async fn emma(extra: Value) -> (TestApp, String, i64, String, i64) {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("Emma").await;
    let skole: i64 = sqlx::query_scalar(
        "INSERT INTO time_rules (name, kind, calls_allowed, exempt_apps_json, days_json) \
         VALUES ('Skole', 'school', 0, '[\"org.fossify.clock\"]', \
         '[null,null,null,null,null,null,null]') RETURNING id",
    )
    .fetch_one(&app.db)
    .await
    .unwrap();
    let mut body = json!({
        "capabilities": ["time_rules_v1", "window_relock_v1"],
        "time_state": {"day": "2026-11-13", "used_minutes": 0, "lock_reason": "NONE",
                       "lifts_active": [], "zone": roomy_zone()}
    });
    for (key, value) in extra.as_object().unwrap() {
        body[key] = value.clone();
    }
    app.post_status(&token, body).await;
    (app, cookie, id, token, skole)
}

async fn post(
    app: &TestApp,
    cookie: &str,
    path: &str,
    form: &[(&str, &str)],
) -> super::TestResponse {
    app.request_form(Method::POST, path, Some(cookie), form)
        .await
}

async fn count(app: &TestApp, sql: &str) -> i64 {
    sqlx::query_scalar(sql).fetch_one(&app.db).await.unwrap()
}

/// The rules the phone is served, with a copy's (negative id) first.
async fn copies(app: &TestApp, token: &str) -> Vec<Value> {
    app.policy(token).await["time_policy"]["rules"]
        .as_array()
        .unwrap()
        .iter()
        .filter(|r| r["id"].as_i64().unwrap() < 0)
        .cloned()
        .collect()
}

/// §5.1: the copy has Skole's mode and ends where asked; Skole's pause and an "all" pause end in
/// the same tap, and so does the opening for setup the report shows; a second start is refused
/// with nothing written; "Alle regler" and an unread zone are refused.
#[tokio::test]
async fn turning_a_rule_on_serves_a_copy_and_ends_what_would_lift_it() {
    let (app, cookie, id, token, skole) = emma(json!({"restrictions_paused": true})).await;
    for rule in [skole.to_string(), "all".to_string()] {
        let res = post(
            &app,
            &cookie,
            &format!("/devices/{id}/lifts"),
            &[("target", "rule"), ("rule", &rule), ("minutes", "30")],
        )
        .await;
        assert!(res.status.is_redirection());
    }
    let res = post(
        &app,
        &cookie,
        &format!("/devices/{id}/rules-now"),
        &[("mode", "on"), ("rule", "all"), ("on_len", "day")],
    )
    .await;
    assert_redirect(
        &res,
        &format!("/devices/{id}?done=on_needs_rule&open=rules&mode=on&rule=all"),
        Some("quick"),
    );

    let res = post(
        &app,
        &cookie,
        &format!("/devices/{id}/rules-now"),
        &[
            ("mode", "on"),
            ("rule", &skole.to_string()),
            ("on_len", "day"),
            ("til", ""),
        ],
    )
    .await;
    assert_redirect(&res, &format!("/devices/{id}?done=on"), Some("quick"));
    let copy = copies(&app, &token).await;
    assert_eq!(copy.len(), 1, "{copy:?}");
    assert_eq!(copy[0]["name"], "Skole");
    assert_eq!(copy[0]["kind"], "school");
    assert_eq!(copy[0]["calls_allowed"], false);
    assert_eq!(copy[0]["exempt_apps"], json!(["org.fossify.clock"]));
    assert_eq!(copy[0]["card_first"], true);
    let window = copy[0]["days"]
        .as_array()
        .unwrap()
        .iter()
        .find(|d| !d.is_null())
        .unwrap();
    assert_eq!(window["end"], 0, "Ut dagen is served until midnight");
    let ends_at: String = sqlx::query_scalar("SELECT ends_at FROM time_lock_now")
        .fetch_one(&app.db)
        .await
        .unwrap();
    let ends_ms = crate::display_time::parse_db_utc(&ends_at)
        .unwrap()
        .timestamp_millis();
    assert_eq!(copy[0]["until_ms"], ends_ms);
    assert_eq!(
        app.policy(&token).await["time_policy"]["lifts"],
        json!([]),
        "both pauses ended"
    );
    assert_eq!(
        count(
            &app,
            "SELECT COUNT(*) FROM device_commands WHERE command = 'end_windows'"
        )
        .await,
        1
    );
    assert_eq!(
        count(
            &app,
            "SELECT COUNT(*) FROM security_events WHERE event_type = 'time_lock_now'"
        )
        .await,
        1
    );

    let res = post(
        &app,
        &cookie,
        &format!("/devices/{id}/rules-now"),
        &[
            ("mode", "on"),
            ("rule", &skole.to_string()),
            ("on_len", "60"),
        ],
    )
    .await;
    assert_redirect(
        &res,
        &format!("/devices/{id}?done=on_busy&open=rules&mode=on&rule={skole}"),
        Some("quick"),
    );
    assert_eq!(count(&app, "SELECT COUNT(*) FROM time_lock_now").await, 1);
    let page = app
        .get_page(
            &format!("/devices/{id}?done=on_busy&open=rules&mode=on&rule={skole}"),
            &cookie,
        )
        .await;
    assert!(
        page.text()
            .contains("Emma already has Skole on until midnight."),
        "{}",
        page.text()
    );

    // Without a zone in the newest report nothing can start.
    sqlx::query("UPDATE time_lock_now SET ended_early_at = datetime('now')")
        .execute(&app.db)
        .await
        .unwrap();
    app.post_status(&token, json!({"time_state": {"lock_reason": "NONE"}}))
        .await;
    let res = post(
        &app,
        &cookie,
        &format!("/devices/{id}/rules-now"),
        &[
            ("mode", "on"),
            ("rule", &skole.to_string()),
            ("on_len", "day"),
        ],
    )
    .await;
    assert_redirect(
        &res,
        &format!("/devices/{id}?done=on_no_zone&open=rules&mode=on&rule={skole}"),
        Some("quick"),
    );
}

/// §5.2, §5.3: a change with the end the page showed moves `until_ms`; a stale one is another
/// parent's and is refused; End now stops the copy, and a second End now changes nothing.
#[tokio::test]
async fn a_turned_on_rule_is_changed_and_ended() {
    let (app, cookie, id, token, skole) = emma(json!({})).await;
    let res = post(
        &app,
        &cookie,
        &format!("/devices/{id}/rules-now"),
        &[
            ("mode", "on"),
            ("rule", &skole.to_string()),
            ("on_len", "60"),
        ],
    )
    .await;
    assert_redirect(&res, &format!("/devices/{id}?done=on"), Some("quick"));
    let (lock, seen): (i64, String) = sqlx::query_as("SELECT id, ends_at FROM time_lock_now")
        .fetch_one(&app.db)
        .await
        .unwrap();
    let before = copies(&app, &token).await[0]["until_ms"].as_i64().unwrap();
    let change = format!("/devices/{id}/lock-now/{lock}");
    let res = post(
        &app,
        &cookie,
        &change,
        &[("add", "30"), ("til", ""), ("seen", &seen)],
    )
    .await;
    assert_redirect(
        &res,
        &format!("/devices/{id}?done=on_changed"),
        Some("quick"),
    );
    let after = copies(&app, &token).await[0]["until_ms"].as_i64().unwrap();
    assert_eq!(after - before, 30 * 60_000);
    let res = post(&app, &cookie, &change, &[("add", "30"), ("seen", &seen)]).await;
    assert_redirect(
        &res,
        &format!("/devices/{id}?done=changed_elsewhere&open=rules&mode=on&rule={skole}"),
        Some("quick"),
    );
    assert_eq!(copies(&app, &token).await[0]["until_ms"], after);

    let end = format!("/devices/{id}/lock-now/{lock}/end");
    let res = post(&app, &cookie, &end, &[]).await;
    assert_redirect(&res, &format!("/devices/{id}?done=on_ended"), Some("quick"));
    assert!(copies(&app, &token).await.is_empty());
    let res = post(&app, &cookie, &end, &[]).await;
    assert_redirect(&res, &format!("/devices/{id}?done=on_over"), Some("quick"));
    assert_eq!(
        count(
            &app,
            "SELECT COUNT(*) FROM security_events WHERE event_type = 'time_lock_now_ended'"
        )
        .await,
        1
    );
    let res = post(
        &app,
        &cookie,
        &format!("/devices/{id}/lock-now/999/end"),
        &[],
    )
    .await;
    assert_eq!(res.status, StatusCode::NOT_FOUND);
}

/// §3, §4.3: a pause of the rule that is on is served with its twin for the copy; the copy goes
/// when its end has passed, and when its rule no longer applies to the phone.
#[tokio::test]
async fn the_copy_follows_its_pause_its_end_and_its_rule() {
    let (app, cookie, id, token, skole) = emma(json!({})).await;
    post(
        &app,
        &cookie,
        &format!("/devices/{id}/rules-now"),
        &[
            ("mode", "on"),
            ("rule", &skole.to_string()),
            ("on_len", "day"),
        ],
    )
    .await;
    let res = post(
        &app,
        &cookie,
        &format!("/devices/{id}/rules-now"),
        &[
            ("mode", "pause"),
            ("rule", &skole.to_string()),
            ("pause_minutes", "15"),
        ],
    )
    .await;
    assert_redirect(&res, &format!("/devices/{id}?done=pause"), Some("quick"));
    let lifts = app.policy(&token).await["time_policy"]["lifts"].clone();
    let lock: i64 = count(&app, "SELECT id FROM time_lock_now").await;
    assert_eq!(lifts.as_array().unwrap().len(), 2, "{lifts}");
    assert_eq!(lifts[1]["id"], -lifts[0]["id"].as_i64().unwrap());
    assert_eq!(lifts[1]["rule_id"], -lock);
    assert_eq!(lifts[1]["expires_at_ms"], lifts[0]["expires_at_ms"]);

    sqlx::query("INSERT INTO device_rule_off (device_id, rule_id) VALUES (?, ?)")
        .bind(id)
        .bind(skole)
        .execute(&app.db)
        .await
        .unwrap();
    assert!(
        copies(&app, &token).await.is_empty(),
        "a rule switched off isn't served"
    );
    sqlx::query("DELETE FROM device_rule_off")
        .execute(&app.db)
        .await
        .unwrap();
    assert_eq!(copies(&app, &token).await.len(), 1);
    sqlx::query("UPDATE time_lock_now SET ends_at = datetime('now', '-1 minute')")
        .execute(&app.db)
        .await
        .unwrap();
    assert!(copies(&app, &token).await.is_empty(), "over at its end");
}
