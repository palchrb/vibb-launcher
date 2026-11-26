//! Design 138: a zone's confirmed Wi-Fi routers - the report's `zone_watch.wifi` and
//! `wifi_struck`, the zone page's Confirm (bound to the phone's newest report), Remove, the orange
//! lines, the strike intake, a move past the radius, the policy's `networks`, and the shared
//! contract `contracts/core/zone_networks.json`.

use axum::http::{Method, StatusCode};
use serde_json::{Value, json};

use super::TestApp;
use crate::zone_networks::{self, ReportedWifi};

const CONTRACT: &str = crate::contracts::contract!("core/zone_networks.json");

fn watch(extra: Value) -> Value {
    let mut w = json!({"fences": 1, "motion_sensor": true, "location_on": true,
                       "background_location": true, "mock_ignored": 0});
    w.as_object_mut()
        .unwrap()
        .extend(extra.as_object().unwrap().clone());
    w
}

async fn report(app: &TestApp, token: &str, zone_watch: Value) {
    let body = json!({"lock_reason": "NONE", "kiosk_engaged": true, "zone_watch": zone_watch});
    let res = app
        .request(Method::POST, "/api/devices/status", Some(token), Some(body))
        .await;
    assert!(res.status.is_success(), "{}", res.text());
}

async fn on_net_a(app: &TestApp, token: &str, bssid: &str) {
    report(
        app,
        token,
        watch(json!({"wifi": {"ssid": "Net-A", "bssid": bssid}})),
    )
    .await;
}

async fn add_home(app: &TestApp, device: i64) -> i64 {
    sqlx::query_scalar(
        "INSERT INTO device_zones (device_id, name, latitude, longitude, radius_m) \
         VALUES (?, 'Home', 59.9139, 10.7522, 150) RETURNING id",
    )
    .bind(device)
    .fetch_one(&app.db)
    .await
    .unwrap()
}

async fn rows(app: &TestApp) -> Vec<(i64, String, String, Option<String>)> {
    sqlx::query_as("SELECT id, ssid, bssid, blocked_at FROM zone_networks ORDER BY id")
        .fetch_all(&app.db)
        .await
        .unwrap()
}

async fn networks(app: &TestApp, token: &str) -> Value {
    app.request(Method::GET, "/api/devices/policy", Some(token), None)
        .await
        .json()["location_policy"]["zones"][0]["networks"]
        .clone()
}

#[test]
fn the_contract_rows() {
    let c: Value = serde_json::from_str(CONTRACT).unwrap();
    for row in c["report_wifi"].as_array().unwrap() {
        let kept = zone_networks::clean_wifi(&row["sent"]);
        let want: Option<ReportedWifi> = serde_json::from_value(row["kept"].clone()).unwrap();
        assert_eq!(kept, want, "{row}");
        if let Some(k) = kept {
            assert_eq!(serde_json::to_value(k).unwrap(), row["kept"]);
        }
    }
    for row in c["report_struck"].as_array().unwrap() {
        let want: Vec<i64> = serde_json::from_value(row["kept"].clone()).unwrap();
        assert_eq!(zone_networks::clean_struck(&row["sent"]), want, "{row}");
    }
    for row in c["same_router"].as_array().unwrap() {
        assert_eq!(
            zone_networks::same_router(
                row["a"].as_str().unwrap(),
                row["a_mld"].as_str(),
                row["b"].as_str().unwrap(),
                row["b_mld"].as_str()
            ),
            row["same"].as_bool().unwrap(),
            "{row}"
        );
    }
}

/// The policy serves a zone's unblocked rows in the contract's shape.
#[tokio::test]
async fn the_policy_serves_the_contracts_networks() {
    let app = TestApp::new().await;
    let (id, token) = app.enrolled_device("Telefon 1").await;
    sqlx::query("UPDATE device_policy SET location_mode = 'interval' WHERE device_id = ?")
        .bind(id)
        .execute(&app.db)
        .await
        .unwrap();
    let home = add_home(&app, id).await;
    for (nid, ssid, bssid, mld, blocked) in [
        (7, "Net-A", "00:00:5e:00:53:01", None, false),
        (8, "Net-C", "00:00:5e:00:57:01", None, true),
        (
            9,
            "Net-B",
            "00:00:5e:00:54:01",
            Some("00:00:5e:00:55:10"),
            false,
        ),
    ] {
        sqlx::query(
            "INSERT INTO zone_networks (id, zone_id, ssid, bssid, mld, blocked_at) VALUES (?, ?, ?, ?, ?, ?)",
        )
        .bind(nid)
        .bind(home)
        .bind(ssid)
        .bind(bssid)
        .bind(mld)
        .bind(blocked.then_some("2026-11-26 10:00:00"))
        .execute(&app.db)
        .await
        .unwrap();
    }
    let c: Value = serde_json::from_str(CONTRACT).unwrap();
    assert_eq!(
        networks(&app, &token).await,
        c["policy"]["zones"][0]["networks"]
    );
}

/// D4: Confirm binds to the phone's newest report with `wifi`; the same router twice is one row;
/// another router or an old report writes nothing; Remove takes every row of the name.
#[tokio::test]
async fn confirm_binds_to_the_report_and_remove_takes_the_name() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("Telefon 1").await;
    sqlx::query("UPDATE device_policy SET location_mode = 'interval' WHERE device_id = ?")
        .bind(id)
        .execute(&app.db)
        .await
        .unwrap();
    let home = add_home(&app, id).await;
    let page = format!("/devices/{id}/zones/{home}");
    let confirm = format!("/devices/{id}/zones/{home}/wifi");
    // Nothing reported: no Confirm row.
    let html = app.get_page(&page, &cookie).await.text();
    assert!(html.contains("id=\"zone-wifi\""));
    assert!(!html.contains("data-wifi=\"current\""));
    on_net_a(&app, &token, "00:00:5e:00:53:01").await;
    // A newer report without `wifi` (a redacted read) hides nothing.
    report(&app, &token, watch(json!({}))).await;
    let html = app.get_page(&page, &cookie).await.text();
    assert!(html.contains("data-wifi=\"current\""), "{html}");
    assert!(html.contains("Net-A"));
    // Another router than the page showed: refused, nothing written.
    let res = app
        .request_form(
            Method::POST,
            &confirm,
            Some(&cookie),
            &[("bssid", "00:00:5e:00:54:01")],
        )
        .await;
    assert_eq!(res.status, StatusCode::SEE_OTHER);
    assert!(res.location().unwrap().ends_with("?wifi=changed#zone-wifi"));
    assert!(rows(&app).await.is_empty());
    // The router the page showed: one row, the report's name; twice is still one.
    for _ in 0..2 {
        let res = app
            .request_form(
                Method::POST,
                &confirm,
                Some(&cookie),
                &[("bssid", "00:00:5e:00:53:01")],
            )
            .await;
        assert_eq!(res.status, StatusCode::SEE_OTHER);
        assert!(res.location().unwrap().ends_with("?wifi=saved#zone-wifi"));
    }
    let stored = rows(&app).await;
    assert_eq!(stored.len(), 1);
    assert_eq!(
        (stored[0].1.as_str(), stored[0].2.as_str()),
        ("Net-A", "00:00:5e:00:53:01")
    );
    assert_eq!(
        networks(&app, &token).await[0]["bssid"],
        "00:00:5e:00:53:01"
    );
    // Confirmed: no Confirm row and no orange line; the security line names the zone only.
    let html = app.get_page(&page, &cookie).await.text();
    assert!(!html.contains("data-wifi=\"current\""));
    assert!(!html.contains("data-wifi=\"orange\""));
    let detail: String = sqlx::query_scalar(
        "SELECT COALESCE(detail, '') || COALESCE(params, '') FROM security_events WHERE event_type = 'zone_wifi_confirmed'",
    )
    .fetch_one(&app.db)
    .await
    .unwrap();
    assert!(
        !detail.contains("Net-A") && !detail.contains("00:00:5e"),
        "{detail}"
    );
    // Another router of the same name: Confirm and "Ukjent ruter".
    on_net_a(&app, &token, "00:00:5e:00:58:01").await;
    let html = app.get_page(&page, &cookie).await.text();
    assert!(html.contains("data-wifi=\"current\""));
    assert!(html.contains("data-wifi=\"orange\""));
    assert!(html.contains("Unknown router"));
    app.request_form(
        Method::POST,
        &confirm,
        Some(&cookie),
        &[("bssid", "00:00:5e:00:58:01")],
    )
    .await;
    assert_eq!(rows(&app).await.len(), 2);
    // An hour-old report: refused.
    sqlx::query("UPDATE device_status SET reported_at = datetime('now', '-2 hours')")
        .execute(&app.db)
        .await
        .unwrap();
    sqlx::query("DELETE FROM zone_networks WHERE bssid = '00:00:5e:00:58:01'")
        .execute(&app.db)
        .await
        .unwrap();
    let res = app
        .request_form(
            Method::POST,
            &confirm,
            Some(&cookie),
            &[("bssid", "00:00:5e:00:58:01")],
        )
        .await;
    assert!(res.location().unwrap().ends_with("?wifi=changed#zone-wifi"));
    // Remove: every row of the name goes.
    sqlx::query(
        "INSERT INTO zone_networks (zone_id, ssid, bssid) VALUES (?, 'Net-A', '00:00:5e:00:59:01')",
    )
    .bind(home)
    .execute(&app.db)
    .await
    .unwrap();
    let first = rows(&app).await[0].0;
    let res = app
        .request_form(
            Method::POST,
            &format!("/devices/{id}/zones/{home}/wifi/{first}/delete"),
            Some(&cookie),
            &[],
        )
        .await;
    assert!(res.location().unwrap().ends_with("#zone-wifi"));
    assert!(rows(&app).await.is_empty());
    // Another phone's zone: 404.
    let (other, _) = app.enrolled_device("Telefon 2").await;
    let res = app
        .request_form(
            Method::POST,
            &format!("/devices/{other}/zones/{home}/wifi"),
            Some(&cookie),
            &[("bssid", "00:00:5e:00:53:01")],
        )
        .await;
    assert_eq!(res.status, StatusCode::NOT_FOUND);
}

/// D7: a struck id of this phone is blocked once (one line, the policy drops it, the page says
/// "Seen elsewhere"); another phone's id does nothing; confirming the router again replaces it.
#[tokio::test]
async fn a_strike_blocks_and_a_new_confirm_replaces() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("Telefon 1").await;
    let (other, other_token) = app.enrolled_device("Telefon 2").await;
    sqlx::query("UPDATE device_policy SET location_mode = 'interval'")
        .execute(&app.db)
        .await
        .unwrap();
    let home = add_home(&app, id).await;
    let other_home = add_home(&app, other).await;
    let nid: i64 = sqlx::query_scalar(
        "INSERT INTO zone_networks (zone_id, ssid, bssid) VALUES (?, 'Net-A', '00:00:5e:00:53:01') RETURNING id",
    )
    .bind(home)
    .fetch_one(&app.db)
    .await
    .unwrap();
    sqlx::query(
        "INSERT INTO zone_networks (zone_id, ssid, bssid) VALUES (?, 'Net-A', '00:00:5e:00:53:01')",
    )
    .bind(other_home)
    .execute(&app.db)
    .await
    .unwrap();
    // The other phone names this phone's id: nothing.
    report(&app, &other_token, watch(json!({"wifi_struck": [nid]}))).await;
    assert!(rows(&app).await.iter().all(|r| r.3.is_none()));
    for _ in 0..2 {
        report(&app, &token, watch(json!({"wifi_struck": [nid]}))).await;
    }
    let stored = rows(&app).await;
    assert!(stored.iter().find(|r| r.0 == nid).unwrap().3.is_some());
    let lines: i64 = sqlx::query_scalar(
        "SELECT COUNT(*) FROM security_events WHERE event_type = 'zone_wifi_blocked'",
    )
    .fetch_one(&app.db)
    .await
    .unwrap();
    assert_eq!(lines, 1);
    assert_eq!(networks(&app, &token).await, json!([]));
    let page = format!("/devices/{id}/zones/{home}");
    let html = app.get_page(&page, &cookie).await.text();
    assert!(html.contains("Seen elsewhere"), "{html}");
    // Confirmed again: a new id, the block gone.
    on_net_a(&app, &token, "00:00:5e:00:53:01").await;
    app.request_form(
        Method::POST,
        &format!("/devices/{id}/zones/{home}/wifi"),
        Some(&cookie),
        &[("bssid", "00:00:5e:00:53:01")],
    )
    .await;
    let mine: Vec<_> = rows(&app)
        .await
        .into_iter()
        .filter(|r| r.0 != nid)
        .collect();
    assert!(mine.iter().any(|r| r.3.is_none() && r.0 > nid));
    assert_eq!(networks(&app, &token).await.as_array().unwrap().len(), 1);
}

/// D12: a move further than the radius clears the zone's routers; a smaller one keeps them.
#[tokio::test]
async fn a_move_past_the_radius_clears_the_routers() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, _) = app.enrolled_device("Telefon 1").await;
    let home = add_home(&app, id).await;
    sqlx::query(
        "INSERT INTO zone_networks (zone_id, ssid, bssid) VALUES (?, 'Net-A', '00:00:5e:00:53:01')",
    )
    .bind(home)
    .execute(&app.db)
    .await
    .unwrap();
    let save = |lat: &'static str| {
        [
            ("name", "Home"),
            ("latitude", lat),
            ("longitude", "10.7522"),
            ("radius_m", "150"),
            ("notify_arrive", "on"),
            ("notify_leave", "on"),
        ]
    };
    let uri = format!("/devices/{id}/zones/{home}");
    // About 50 m north: kept.
    app.request_form(Method::POST, &uri, Some(&cookie), &save("59.91435"))
        .await;
    assert_eq!(rows(&app).await.len(), 1);
    // About 220 m further: cleared.
    app.request_form(Method::POST, &uri, Some(&cookie), &save("59.91633"))
        .await;
    assert!(rows(&app).await.is_empty());
}
