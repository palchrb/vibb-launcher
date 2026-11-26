//! Design 138: a zone's confirmed Wi-Fi routers - the report's `zone_watch.wifi` and
//! `wifi_struck`, the zone page's Confirm (bound to the phone's newest report), Remove, the orange
//! lines, the strike intake, a move past the radius, the policy's `networks`, and the shared
//! contract `contracts/core/zone_networks.json`. With design 135 the page is the family zone's
//! editor, and a confirmation is the family zone's: every phone using the zone is served it.

use axum::http::{Method, StatusCode};
use serde_json::{Value, json};

use super::TestApp;
use super::zones::{drawn, post, set};
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

/// A family zone "Home" linked to `device`: `(family zone, link)`.
async fn add_home(app: &TestApp, device: i64) -> (i64, i64) {
    let link = super::zones::insert_zone(app, device, "Home", 59.9139, 10.7522, 150).await;
    (super::zones::zone_of(app, link).await, link)
}

/// Links `device` to the family zone `zone` as the app does (`zones::link`: the zone's routers
/// copied); the link's id.
async fn link(app: &TestApp, device: i64, zone: i64) -> i64 {
    let family = crate::zones::family_zone(&app.db, zone)
        .await
        .unwrap()
        .unwrap();
    let mut conn = app.db.acquire().await.unwrap();
    crate::zones::link(&mut conn, device, &family, None, chrono::Utc::now())
        .await
        .unwrap()
}

async fn rows(app: &TestApp) -> Vec<(i64, String, String, Option<String>)> {
    sqlx::query_as("SELECT id, ssid, bssid, blocked_at FROM zone_networks ORDER BY id")
        .fetch_all(&app.db)
        .await
        .unwrap()
}

/// The rows of one link.
async fn rows_of(app: &TestApp, link: i64) -> Vec<(i64, String, String, Option<String>)> {
    sqlx::query_as(
        "SELECT id, ssid, bssid, blocked_at FROM zone_networks WHERE zone_id = ? ORDER BY id",
    )
    .bind(link)
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

async fn regularly(app: &TestApp) {
    sqlx::query("UPDATE device_policy SET location_mode = 'interval'")
        .execute(&app.db)
        .await
        .unwrap();
}

/// Confirm from the family zone's page for `phone`'s network.
async fn confirm(
    app: &TestApp,
    cookie: &str,
    zone: i64,
    phone: i64,
    bssid: &str,
) -> super::TestResponse {
    let phone = phone.to_string();
    app.request_form(
        Method::POST,
        &format!("/zones/{zone}/wifi"),
        Some(cookie),
        &[("phone", phone.as_str()), ("bssid", bssid)],
    )
    .await
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
    let (_, home) = add_home(&app, id).await;
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
    regularly(&app).await;
    let (home, link) = add_home(&app, id).await;
    let page = format!("/zones/{home}");
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
    assert!(
        html.contains(&format!("name=\"phone\" value=\"{id}\"")),
        "{html}"
    );
    // Another router than the page showed: refused, nothing written.
    let res = confirm(&app, &cookie, home, id, "00:00:5e:00:54:01").await;
    assert_eq!(res.status, StatusCode::SEE_OTHER);
    assert!(res.location().unwrap().ends_with("?wifi=changed#zone-wifi"));
    assert!(rows(&app).await.is_empty());
    // The router the page showed: one row, the report's name; twice is still one.
    for _ in 0..2 {
        let res = confirm(&app, &cookie, home, id, "00:00:5e:00:53:01").await;
        assert_eq!(res.status, StatusCode::SEE_OTHER);
        assert!(res.location().unwrap().ends_with("?wifi=saved#zone-wifi"));
        app.assert_lands(&res, &cookie).await;
    }
    let stored = rows_of(&app, link).await;
    assert_eq!(stored.len(), 1);
    assert_eq!(
        (stored[0].1.as_str(), stored[0].2.as_str()),
        ("Net-A", "00:00:5e:00:53:01")
    );
    assert_eq!(
        networks(&app, &token).await[0]["bssid"],
        "00:00:5e:00:53:01"
    );
    // Confirmed: no Confirm row and no orange line; the security line names the phone and the zone only.
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
    // Another router of the same name: the Bekreft row, no orange line (revision 3).
    on_net_a(&app, &token, "00:00:5e:00:58:01").await;
    let html = app.get_page(&page, &cookie).await.text();
    assert!(html.contains("data-wifi=\"current\""));
    assert!(!html.contains("data-wifi=\"orange\""));
    confirm(&app, &cookie, home, id, "00:00:5e:00:58:01").await;
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
    let res = confirm(&app, &cookie, home, id, "00:00:5e:00:58:01").await;
    assert!(res.location().unwrap().ends_with("?wifi=changed#zone-wifi"));
    // Remove: every row of the name goes.
    sqlx::query(
        "INSERT INTO zone_networks (zone_id, ssid, bssid) VALUES (?, 'Net-A', '00:00:5e:00:59:01')",
    )
    .bind(link)
    .execute(&app.db)
    .await
    .unwrap();
    let first = rows(&app).await[0].0;
    let res = app
        .request_form(
            Method::POST,
            &format!("/zones/{home}/wifi/{first}/delete"),
            Some(&cookie),
            &[],
        )
        .await;
    assert!(res.location().unwrap().ends_with("#zone-wifi"));
    assert!(rows(&app).await.is_empty());
    // A phone that doesn't use the zone: nothing written; an unknown zone: 404.
    let (other, other_token) = app.enrolled_device("Telefon 2").await;
    on_net_a(&app, &other_token, "00:00:5e:00:53:01").await;
    let res = confirm(&app, &cookie, home, other, "00:00:5e:00:53:01").await;
    assert!(res.location().unwrap().ends_with("?wifi=changed#zone-wifi"));
    assert!(rows(&app).await.is_empty());
    let res = confirm(&app, &cookie, home + 100, id, "00:00:5e:00:53:01").await;
    assert_eq!(res.status, StatusCode::NOT_FOUND);
    // Opened from a phone's Zones page: the landing keeps it.
    on_net_a(&app, &token, "00:00:5e:00:53:01").await;
    let id_s = id.to_string();
    let res = app
        .request_form(
            Method::POST,
            &format!("/zones/{home}/wifi"),
            Some(&cookie),
            &[
                ("device", id_s.as_str()),
                ("phone", id_s.as_str()),
                ("bssid", "00:00:5e:00:53:01"),
            ],
        )
        .await;
    assert!(
        res.location()
            .unwrap()
            .ends_with(&format!("?wifi=saved&device={id}#zone-wifi")),
        "{:?}",
        res.location()
    );
}

/// Design 135: a confirmation is the family zone's. One phone's confirm serves every phone using
/// the zone (each under its own row ids), a phone on the same router is offered nothing more, a
/// phone linked later gets the routers, an unlinked phone loses them, and Remove takes the name
/// from every phone.
#[tokio::test]
async fn a_confirmation_serves_every_phone_of_the_family_zone() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (t1, token1) = app.enrolled_device("Telefon 1").await;
    let (t2, token2) = app.enrolled_device("Telefon 2").await;
    let (t3, token3) = app.enrolled_device("Telefon 3").await;
    regularly(&app).await;
    let (home, link1) = add_home(&app, t1).await;
    let link2 = link(&app, t2, home).await;
    let page = format!("/zones/{home}");
    // Both phones at home on two bands of one router: one Bekreft row, for the first phone.
    on_net_a(&app, &token1, "00:00:5e:00:53:01").await;
    on_net_a(&app, &token2, "00:00:5e:00:53:02").await;
    let html = app.get_page(&page, &cookie).await.text();
    assert_eq!(html.matches("data-wifi=\"current\"").count(), 1, "{html}");
    // Confirm from Telefon 2's report: every link has the router, both phones nudged.
    let mut nudges = app.state.command_notify.subscribe();
    let res = confirm(&app, &cookie, home, t2, "00:00:5e:00:53:02").await;
    assert!(res.location().unwrap().ends_with("?wifi=saved#zone-wifi"));
    let mut nudged = Vec::new();
    while let Ok(id) = nudges.try_recv() {
        nudged.push(id);
    }
    nudged.sort_unstable();
    assert_eq!(nudged, [t1, t2]);
    assert_eq!(rows_of(&app, link1).await.len(), 1);
    assert_eq!(rows_of(&app, link2).await.len(), 1);
    let (n1, n2) = (networks(&app, &token1).await, networks(&app, &token2).await);
    assert_eq!(n1[0]["bssid"], "00:00:5e:00:53:02");
    assert_eq!(n2[0]["bssid"], "00:00:5e:00:53:02");
    assert_ne!(n1[0]["id"], n2[0]["id"], "each link its own row");
    // Telefon 1's band of that router counts as confirmed: no Bekreft left.
    let html = app.get_page(&page, &cookie).await.text();
    assert!(!html.contains("data-wifi=\"current\""), "{html}");
    // The name shows once on the zone's page.
    assert_eq!(html.matches("data-wifi=\"").count(), 1, "{html}");
    // Telefon 3 linked later (its Zones page's switch, the editor's tick: both are zones::link):
    // served the router at once.
    let link3 = link(&app, t3, home).await;
    assert_eq!(rows_of(&app, link3).await.len(), 1);
    assert_eq!(
        networks(&app, &token3).await[0]["bssid"],
        "00:00:5e:00:53:02"
    );
    // Through the editor: Telefon 3 unticked loses its rows, ticked again gets them back.
    let html = app.get_page(&page, &cookie).await.text();
    let mut fields = drawn(&html, &format!("/zones/{home}#"));
    set(&mut fields, &format!("phone_{t3}"), None);
    post(&app, &cookie, &page, &fields).await;
    assert!(rows_of(&app, link3).await.is_empty());
    assert_eq!(networks(&app, &token3).await, Value::Null);
    let html = app.get_page(&page, &cookie).await.text();
    let mut fields = drawn(&html, &format!("/zones/{home}#"));
    set(&mut fields, &format!("phone_{t3}"), Some("on"));
    post(&app, &cookie, &page, &fields).await;
    assert_eq!(
        networks(&app, &token3).await[0]["bssid"],
        "00:00:5e:00:53:02"
    );
    // Remove from the zone's page: gone for every phone.
    let first = rows(&app).await[0].0;
    app.request_form(
        Method::POST,
        &format!("/zones/{home}/wifi/{first}/delete"),
        Some(&cookie),
        &[],
    )
    .await;
    assert!(rows(&app).await.is_empty());
    for token in [&token1, &token2, &token3] {
        assert_eq!(networks(&app, token).await, json!([]));
    }
}

/// D7: a struck id of this phone is blocked once (one line, the policy drops it, the page says
/// "Seen elsewhere") - and, design 135, on every phone using the zone; a phone that doesn't use the
/// zone names it for nothing; confirming the router again replaces it everywhere.
#[tokio::test]
async fn a_strike_blocks_on_every_link_and_a_new_confirm_replaces() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("Telefon 1").await;
    let (sibling, sibling_token) = app.enrolled_device("Telefon 2").await;
    let (other, other_token) = app.enrolled_device("Telefon 3").await;
    regularly(&app).await;
    let (home, _) = add_home(&app, id).await;
    // Telefon 3 uses another zone with the same router (its own family zone).
    let (_, other_home) = add_home(&app, other).await;
    on_net_a(&app, &token, "00:00:5e:00:53:01").await;
    confirm(&app, &cookie, home, id, "00:00:5e:00:53:01").await;
    link(&app, sibling, home).await;
    sqlx::query(
        "INSERT INTO zone_networks (zone_id, ssid, bssid) VALUES (?, 'Net-A', '00:00:5e:00:53:01')",
    )
    .bind(other_home)
    .execute(&app.db)
    .await
    .unwrap();
    let nid = networks(&app, &token).await[0]["id"].as_i64().unwrap();
    // Another phone names this phone's id: nothing.
    report(&app, &other_token, watch(json!({"wifi_struck": [nid]}))).await;
    assert!(rows(&app).await.iter().all(|r| r.3.is_none()));
    for _ in 0..2 {
        report(&app, &token, watch(json!({"wifi_struck": [nid]}))).await;
    }
    // Blocked on both links of Home; the other family zone's row untouched.
    let blocked: Vec<_> = rows(&app)
        .await
        .into_iter()
        .filter(|r| r.3.is_some())
        .collect();
    assert_eq!(blocked.len(), 2);
    assert!(rows_of(&app, other_home).await[0].3.is_none());
    let lines: i64 = sqlx::query_scalar(
        "SELECT COUNT(*) FROM security_events WHERE event_type = 'zone_wifi_blocked'",
    )
    .fetch_one(&app.db)
    .await
    .unwrap();
    assert_eq!(lines, 1);
    assert_eq!(networks(&app, &token).await, json!([]));
    assert_eq!(networks(&app, &sibling_token).await, json!([]));
    let html = app
        .get_page(&format!("/zones/{home}"), &cookie)
        .await
        .text();
    assert!(html.contains("Seen elsewhere"), "{html}");
    // Confirmed again: new ids, the block gone, on both.
    on_net_a(&app, &token, "00:00:5e:00:53:01").await;
    confirm(&app, &cookie, home, id, "00:00:5e:00:53:01").await;
    for t in [&token, &sibling_token] {
        let served = networks(&app, t).await;
        assert_eq!(served.as_array().unwrap().len(), 1);
        assert!(served[0]["id"].as_i64().unwrap() > nid);
    }
}

/// D12: a move further than the radius clears the zone's routers from every link; a smaller one
/// keeps them.
#[tokio::test]
async fn a_move_past_the_radius_clears_the_routers() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, _) = app.enrolled_device("Telefon 1").await;
    let (sibling, _) = app.enrolled_device("Telefon 2").await;
    let (home, link1) = add_home(&app, id).await;
    let link2 = link(&app, sibling, home).await;
    for l in [link1, link2] {
        sqlx::query(
            "INSERT INTO zone_networks (zone_id, ssid, bssid) VALUES (?, 'Net-A', '00:00:5e:00:53:01')",
        )
        .bind(l)
        .execute(&app.db)
        .await
        .unwrap();
    }
    let page = format!("/zones/{home}");
    // About 50 m north: kept; about 220 m further: cleared, the change's line says so.
    for (lat, kept) in [("59.91435", 2), ("59.91633", 0)] {
        let html = app.get_page(&page, &cookie).await.text();
        let mut fields = drawn(&html, &format!("/zones/{home}#"));
        set(&mut fields, "latitude", Some(lat));
        post(&app, &cookie, &page, &fields).await;
        assert_eq!(rows(&app).await.len(), kept, "{lat}");
    }
    let detail: String = sqlx::query_scalar(
        "SELECT COALESCE(detail, '') || COALESCE(params, '') FROM security_events \
         WHERE event_type = 'zone_changed' ORDER BY id DESC LIMIT 1",
    )
    .fetch_one(&app.db)
    .await
    .unwrap();
    assert!(detail.contains("wifi_removed"), "{detail}");
}

/// QA L1: only a fix at most an hour old surely outside hides Confirm; QA L9: removing a row that is
/// gone already lands back on the group.
#[tokio::test]
async fn an_old_fix_outside_hides_nothing_and_a_gone_row_lands_on_the_group() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("Telefon 1").await;
    let (home, _) = add_home(&app, id).await;
    on_net_a(&app, &token, "00:00:5e:00:53:01").await;
    let page = format!("/zones/{home}");
    let far = |minutes: i64| {
        (chrono::Utc::now() - chrono::Duration::minutes(minutes))
            .format("%Y-%m-%dT%H:%M:%S%.3fZ")
            .to_string()
    };
    sqlx::query(
        "INSERT INTO device_locations (device_id, latitude, longitude, accuracy_meters, captured_at) \
         VALUES (?, 59.95, 10.7522, 20, ?)",
    )
    .bind(id)
    .bind(far(120))
    .execute(&app.db)
    .await
    .unwrap();
    assert!(
        app.get_page(&page, &cookie)
            .await
            .text()
            .contains("data-wifi=\"current\"")
    );
    sqlx::query(
        "INSERT INTO device_locations (device_id, latitude, longitude, accuracy_meters, captured_at) \
         VALUES (?, 59.95, 10.7522, 20, ?)",
    )
    .bind(id)
    .bind(far(5))
    .execute(&app.db)
    .await
    .unwrap();
    assert!(
        !app.get_page(&page, &cookie)
            .await
            .text()
            .contains("data-wifi=\"current\"")
    );
    let res = app
        .request_form(
            Method::POST,
            &format!("/zones/{home}/wifi/999/delete"),
            Some(&cookie),
            &[],
        )
        .await;
    assert_eq!(res.status, StatusCode::SEE_OTHER);
    assert!(res.location().unwrap().ends_with("?wifi=saved#zone-wifi"));
}

/// Design 135's migration (0182) keeps a zone's confirmed routers: the link keeps its id, so the
/// rows stay on it, under the family zone the fold made - also when two phones' identical zones
/// fold into one, each link keeping its own rows.
#[tokio::test]
async fn the_family_zone_migration_keeps_the_routers() {
    let (db, _dir) = super::cleanup::migrated_before(182).await;
    let mut links = Vec::new();
    for name in ["Telefon 1", "Telefon 2"] {
        let device: i64 = sqlx::query_scalar("INSERT INTO devices (name) VALUES (?) RETURNING id")
            .bind(name)
            .fetch_one(&db)
            .await
            .unwrap();
        let link: i64 = sqlx::query_scalar(
            "INSERT INTO device_zones (device_id, name, latitude, longitude, radius_m) \
             VALUES (?, 'Home', 59.9139, 10.7522, 150) RETURNING id",
        )
        .bind(device)
        .fetch_one(&db)
        .await
        .unwrap();
        links.push(link);
    }
    sqlx::query(
        "INSERT INTO zone_networks (zone_id, ssid, bssid) VALUES (?, 'Net-A', '00:00:5e:00:53:01')",
    )
    .bind(links[0])
    .execute(&db)
    .await
    .unwrap();
    super::cleanup::finish(&db).await;
    let kept: Vec<(i64, i64)> = sqlx::query_as(
        "SELECT n.zone_id, z.zone_id FROM zone_networks n JOIN device_zones z ON z.id = n.zone_id",
    )
    .fetch_all(&db)
    .await
    .unwrap();
    let family: i64 = sqlx::query_scalar("SELECT zone_id FROM device_zones WHERE id = ?")
        .bind(links[1])
        .fetch_one(&db)
        .await
        .unwrap();
    assert_eq!(
        kept,
        [(links[0], family)],
        "one family zone, the row on its link"
    );
    let fks: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM pragma_foreign_key_check")
        .fetch_one(&db)
        .await
        .unwrap();
    assert_eq!(fks, 0);
}
