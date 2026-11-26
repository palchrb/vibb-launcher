//! Design 135 (`docs/design/135-family-zones.md` §10): zones shared by the family, picked per phone - migration
//! 0181's fold, the links judged per phone, the editor and the phone's page with their stale-form diffs, the caps,
//! the weather's family zone, and every crossing stored with each parent's own push filter. The phones are
//! "Telefon 1", "Telefon 2", "Telefon 3"; the families here have zone ids and link ids that differ.

use axum::http::{Method, StatusCode};
use chrono::Utc;

use super::TestApp;
use super::music::nudged;
use super::web_push::{ENDPOINT, subscribe};
use super::zones::{AWAY, NEAR, at, count, drawn, post, report, set, side, zone_of};

const SECOND_ENDPOINT: &str = "https://web.push.apple.com/QK1-second-parent-fake-endpoint";
const THIRD_ENDPOINT: &str = "https://web.push.apple.com/QK1-third-parent-fake-endpoint";

/// Home's place (r = 150 m) as the editor posts it.
const HOME: [(&str, &str); 4] = [
    ("name", "Hjem"),
    ("latitude", "59.9139"),
    ("longitude", "10.7522"),
    ("radius_m", "150"),
];

/// A new family zone through the editor (`POST /zones`) with `phones` ticked; its id.
async fn new_zone(app: &TestApp, cookie: &str, place: &[(&str, &str)], phones: &[i64]) -> i64 {
    let mut fields: Vec<(String, String)> = place
        .iter()
        .map(|(k, v)| (k.to_string(), v.to_string()))
        .collect();
    for phone in phones {
        fields.push((format!("phone_{phone}"), "on".to_string()));
    }
    let res = post(app, cookie, "/zones", &fields).await;
    assert_eq!(res.status, StatusCode::SEE_OTHER, "{}", res.text());
    count(app, "SELECT MAX(id) FROM family_zones").await
}

/// The link of `phone` to the family zone `zone`, if any.
async fn link_of(app: &TestApp, phone: i64, zone: i64) -> Option<i64> {
    sqlx::query_scalar("SELECT id FROM device_zones WHERE device_id = ? AND zone_id = ?")
        .bind(phone)
        .bind(zone)
        .fetch_optional(&app.db)
        .await
        .unwrap()
}

/// The phone's Zones page as drawn for `cookie`'s parent: its form's fields.
async fn phone_form(app: &TestApp, cookie: &str, phone: i64) -> Vec<(String, String)> {
    let html = app
        .get_page(&format!("/devices/{phone}/zones"), cookie)
        .await
        .text();
    drawn(&html, &format!("/devices/{phone}/zones#"))
}

/// The editor of `zone` as drawn: its form's fields.
async fn editor_form(app: &TestApp, cookie: &str, zone: i64) -> Vec<(String, String)> {
    let html = app.get_page(&format!("/zones/{zone}"), cookie).await.text();
    drawn(&html, &format!("/zones/{zone}#"))
}

/// The policy's zones of a phone in Regularly: `(id, radius_m)`.
async fn policy_zones(app: &TestApp, token: &str) -> Vec<(i64, i64)> {
    let policy = app.policy(token).await;
    policy["location_policy"]["zones"]
        .as_array()
        .unwrap()
        .iter()
        .map(|z| (z["id"].as_i64().unwrap(), z["radius_m"].as_i64().unwrap()))
        .collect()
}

async fn regularly(app: &TestApp) {
    sqlx::query("UPDATE device_policy SET location_mode = 'interval'")
        .execute(&app.db)
        .await
        .unwrap();
}

async fn admin_id(app: &TestApp, username: &str) -> i64 {
    sqlx::query_scalar("SELECT id FROM admin_users WHERE username = ?")
        .bind(username)
        .fetch_one(&app.db)
        .await
        .unwrap()
}

/// The admins whose subscriptions have a delivery of the event `kind`, sorted.
async fn delivered_to(app: &TestApp, kind: &str) -> Vec<i64> {
    sqlx::query_scalar(
        "SELECT s.admin_id FROM push_deliveries d JOIN push_subscriptions s ON s.id = d.subscription_id \
         JOIN parent_events e ON e.id = d.event_id WHERE e.kind = ? ORDER BY s.admin_id",
    )
    .bind(kind)
    .fetch_all(&app.db)
    .await
    .unwrap()
}

// ------------------------------------------------------------------------------------------------
// Migration 0181
// ------------------------------------------------------------------------------------------------

/// §10: the fold, every link kept with its id and state, the alerts for every parent, the weather on the family
/// zone, the events untouched, and the policy's zones equal value for value - the proof the phones see no change.
#[tokio::test]
async fn migration_0181_folds_and_keeps_every_phone() {
    let (db, _dir) = super::cleanup::migrated_before(181).await;
    let mut admins = Vec::new();
    for (name, removed) in [
        ("forelder1", None),
        ("forelder2", None),
        ("forelder3", Some("2026-11-01")),
    ] {
        let id: i64 = sqlx::query_scalar(
            "INSERT INTO admin_users (username, password_hash, must_change_password, totp_enabled, removed_at) \
             VALUES (?, 'x', 0, 1, ?) RETURNING id",
        )
        .bind(name)
        .bind(removed)
        .fetch_one(&db)
        .await
        .unwrap();
        admins.push(id);
    }
    let mut phones = Vec::new();
    for name in ["Telefon 1", "Telefon 2", "Telefon 3"] {
        let id: i64 = sqlx::query_scalar("INSERT INTO devices (name) VALUES (?) RETURNING id")
            .bind(name)
            .fetch_one(&db)
            .await
            .unwrap();
        sqlx::query("INSERT INTO device_policy (device_id, kiosk_desired) VALUES (?, 1)")
            .bind(id)
            .execute(&db)
            .await
            .unwrap();
        phones.push(id);
    }
    let [t1, t2, t3] = phones[..] else {
        unreachable!()
    };
    let zone = |device: i64,
                name: &str,
                lat: f64,
                lng: f64,
                radius: i64,
                arrive: i64,
                leave: i64| {
        let db = db.clone();
        let name = name.to_string();
        async move {
            sqlx::query_scalar::<_, i64>(
                "INSERT INTO device_zones (device_id, name, latitude, longitude, radius_m, notify_arrive, \
                 notify_leave, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, '2026-10-01 10:00:00') RETURNING id",
            )
            .bind(device)
            .bind(name)
            .bind(lat)
            .bind(lng)
            .bind(radius)
            .bind(arrive)
            .bind(leave)
            .fetch_one(&db)
            .await
            .unwrap()
        }
    };
    // Telefon 1's Hjem (Leave off, inside, a cursor and a pending side) and Telefon 2's " hjem" on the same circle.
    let hjem1 = zone(t1, "Hjem", 59.9139, 10.7522, 150, 1, 0).await;
    sqlx::query(
        "UPDATE device_zones SET side = 'inside', side_since = '2026-11-20T08:00:00.000Z', \
         pending_side = 'outside', pending_at = '2026-11-20T09:00:00.000Z', \
         evaluated_at = '2026-11-20T09:00:00.000Z', evaluated_fix_id = 77 WHERE id = ?",
    )
    .bind(hjem1)
    .execute(&db)
    .await
    .unwrap();
    let hjem2 = zone(t2, " hjem", 59.9139, 10.7522, 150, 1, 1).await;
    // Three Skole in three places, one per phone.
    let skole: Vec<i64> = {
        let mut ids = Vec::new();
        for (n, phone) in [t1, t2, t3].into_iter().enumerate() {
            ids.push(zone(phone, "Skole", 59.92 + n as f64 / 100.0, 10.75, 200, 1, 1).await);
        }
        ids
    };
    // A group with both switches off, and a name over 40 characters.
    let fotball = zone(t3, "Fotball", 59.95, 10.70, 300, 0, 0).await;
    let long_name = "x".repeat(45);
    let long = zone(t3, &long_name, 59.96, 10.71, 150, 1, 1).await;
    // Over 50 after the fold: 48 more places on Telefon 3 (the database never capped a phone).
    for n in 0..48 {
        zone(
            t3,
            &format!("Sted {n}"),
            60.0 + n as f64 / 100.0,
            11.0,
            150,
            1,
            1,
        )
        .await;
    }
    // Telefon 1's weather on its Hjem; events with params naming the links.
    sqlx::query("UPDATE device_policy SET weather_zone_id = ? WHERE device_id = ?")
        .bind(hjem1)
        .bind(t1)
        .execute(&db)
        .await
        .unwrap();
    let params = format!(
        "{{\"zone_id\":{hjem1},\"zone\":\"Hjem\",\"phone\":\"Telefon 1\",\"direction\":\"arrived\",\"at\":\"x\"}}"
    );
    sqlx::query(
        "INSERT INTO parent_events (device_id, kind, dedup_key, title, summary, target, happened_at, params) \
         VALUES (?, 'zone_arrived', 'k1', '', '', '/x', '', ?)",
    )
    .bind(t1)
    .bind(&params)
    .execute(&db)
    .await
    .unwrap();
    type Served = Vec<(i64, i64, f64, f64, i64)>;
    let before: Served = sqlx::query_as(
        "SELECT device_id, id, latitude, longitude, radius_m FROM device_zones ORDER BY device_id, id",
    )
    .fetch_all(&db)
    .await
    .unwrap();
    type State = Vec<(
        i64,
        Option<String>,
        Option<String>,
        Option<String>,
        Option<String>,
        Option<String>,
        Option<i64>,
        String,
    )>;
    let state_sql = "SELECT id, side, side_since, pending_side, pending_at, evaluated_at, evaluated_fix_id, \
                     created_at FROM device_zones ORDER BY id";
    let state_before: State = sqlx::query_as(state_sql).fetch_all(&db).await.unwrap();

    super::cleanup::finish(&db).await;

    // The fold: Hjem is one family zone for two phones, the three Skole stay three with their names.
    let family: Vec<(i64, String)> =
        sqlx::query_as("SELECT id, name FROM family_zones ORDER BY id")
            .fetch_all(&db)
            .await
            .unwrap();
    assert_eq!(family.len(), before.len() - 1, "only Hjem folds");
    assert!(family.len() > 50, "the cap only blocks adding");
    let hjem_zone: i64 = sqlx::query_scalar("SELECT zone_id FROM device_zones WHERE id = ?")
        .bind(hjem1)
        .fetch_one(&db)
        .await
        .unwrap();
    let hjem2_zone: i64 = sqlx::query_scalar("SELECT zone_id FROM device_zones WHERE id = ?")
        .bind(hjem2)
        .fetch_one(&db)
        .await
        .unwrap();
    assert_eq!(hjem_zone, hjem2_zone);
    assert_eq!(
        family.iter().find(|(id, _)| *id == hjem_zone).unwrap().1,
        "Hjem",
        "the oldest row's name, trimmed"
    );
    let skole_zones: Vec<(i64, String)> = sqlx::query_as(
        "SELECT DISTINCT f.id, f.name FROM device_zones d JOIN family_zones f ON f.id = d.zone_id \
         WHERE d.id IN (?, ?, ?) ORDER BY f.id",
    )
    .bind(skole[0])
    .bind(skole[1])
    .bind(skole[2])
    .fetch_all(&db)
    .await
    .unwrap();
    assert_eq!(skole_zones.len(), 3);
    assert!(skole_zones.iter().all(|(_, n)| n == "Skole"));
    // Every link keeps its id, state, cursor and created_at; no zone_id is NULL; a phone links a zone once.
    let state_after: State = sqlx::query_as(state_sql).fetch_all(&db).await.unwrap();
    assert_eq!(state_after, state_before);
    assert_eq!(
        sqlx::query_scalar::<_, i64>("SELECT COUNT(*) FROM device_zones WHERE zone_id IS NULL")
            .fetch_one(&db)
            .await
            .unwrap(),
        0
    );
    assert_eq!(
        sqlx::query_scalar::<_, i64>(
            "SELECT COUNT(*) FROM (SELECT device_id, zone_id FROM device_zones GROUP BY device_id, zone_id \
             HAVING COUNT(*) > 1)"
        )
        .fetch_one(&db)
        .await
        .unwrap(),
        0
    );
    // The alerts: Leave off on Telefon 1's Hjem for every parent, the removed one too; both off on Fotball; no
    // row for a link with both on.
    let alerts: Vec<(i64, i64, bool, bool)> = sqlx::query_as(
        "SELECT admin_id, device_zone_id, arrive, leave FROM zone_alerts ORDER BY device_zone_id, admin_id",
    )
    .fetch_all(&db)
    .await
    .unwrap();
    let mut expected: Vec<(i64, i64, bool, bool)> = Vec::new();
    for admin in &admins {
        expected.push((*admin, hjem1, true, false));
    }
    for admin in &admins {
        expected.push((*admin, fotball, false, false));
    }
    expected.sort_by_key(|(admin, link, _, _)| (*link, *admin));
    assert_eq!(alerts, expected);
    // The weather moved to the family zone.
    let weather: (Option<i64>, Option<i64>) = sqlx::query_as(
        "SELECT weather_zone_id, weather_family_zone_id FROM device_policy WHERE device_id = ?",
    )
    .bind(t1)
    .fetch_one(&db)
    .await
    .unwrap();
    assert_eq!(weather, (None, Some(hjem_zone)));
    // The events' params are unchanged.
    let stored: String = sqlx::query_scalar("SELECT params FROM parent_events")
        .fetch_one(&db)
        .await
        .unwrap();
    assert_eq!(stored, params);
    // The policy's zones, value for value (`zones::list` and `policy_zones`, what `build_policy` serves).
    let mut after: Served = Vec::new();
    for phone in [t1, t2, t3] {
        let list = crate::zones::list(&db, phone).await.unwrap();
        for z in crate::zones::policy_zones("interval", &list) {
            after.push((phone, z.id, z.latitude, z.longitude, z.radius_m));
        }
    }
    assert_eq!(after, before);
    // The over-40 name is kept as it was.
    let kept: String = sqlx::query_scalar(
        "SELECT f.name FROM device_zones d JOIN family_zones f ON f.id = d.zone_id WHERE d.id = ?",
    )
    .bind(long)
    .fetch_one(&db)
    .await
    .unwrap();
    assert_eq!(kept, long_name);
    // Unlinking Hjem from Telefon 1 keeps the weather; deleting Hjem clears it, with its links and alerts.
    sqlx::query("DELETE FROM device_zones WHERE id = ?")
        .bind(hjem1)
        .execute(&db)
        .await
        .unwrap();
    let weather: Option<i64> =
        sqlx::query_scalar("SELECT weather_family_zone_id FROM device_policy WHERE device_id = ?")
            .bind(t1)
            .fetch_one(&db)
            .await
            .unwrap();
    assert_eq!(weather, Some(hjem_zone));
    sqlx::query("DELETE FROM family_zones WHERE id = ?")
        .bind(hjem_zone)
        .execute(&db)
        .await
        .unwrap();
    let weather: Option<i64> =
        sqlx::query_scalar("SELECT weather_family_zone_id FROM device_policy WHERE device_id = ?")
            .bind(t1)
            .fetch_one(&db)
            .await
            .unwrap();
    assert_eq!(weather, None);
    assert_eq!(
        sqlx::query_scalar::<_, i64>("SELECT COUNT(*) FROM device_zones WHERE id = ?")
            .bind(hjem2)
            .fetch_one(&db)
            .await
            .unwrap(),
        0,
        "the family zone's links go with it"
    );
    assert_eq!(
        sqlx::query_scalar::<_, i64>("SELECT COUNT(*) FROM zone_alerts WHERE device_zone_id = ?")
            .bind(hjem1)
            .fetch_one(&db)
            .await
            .unwrap(),
        0
    );
}

/// §10: a migrated name over 40 saves a radius change unchanged (the name's rules apply only to a changed name), and
/// a family over 50 zones loses only "Add Zone" - every zone stays and can be edited.
#[tokio::test]
async fn a_long_name_and_a_full_family_keep_working() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (t1, _) = app.enrolled_device("Telefon 1").await;
    let long = "x".repeat(45);
    let link = super::zones::insert_zone(&app, t1, &long, 59.9, 10.7, 150).await;
    let zone = zone_of(&app, link).await;
    let mut fields = editor_form(&app, &cookie, zone).await;
    set(&mut fields, "radius_m", Some("300"));
    let res = post(&app, &cookie, &format!("/zones/{zone}"), &fields).await;
    assert_eq!(res.status, StatusCode::SEE_OTHER, "{}", res.text());
    let stored: (String, i64) =
        sqlx::query_as("SELECT name, radius_m FROM family_zones WHERE id = ?")
            .bind(zone)
            .fetch_one(&app.db)
            .await
            .unwrap();
    assert_eq!(stored, (long.clone(), 300));
    // A changed name is checked: over 40 is refused.
    let mut fields = editor_form(&app, &cookie, zone).await;
    set(&mut fields, "name", Some(&"y".repeat(41)));
    let res = post(&app, &cookie, &format!("/zones/{zone}"), &fields).await;
    assert_eq!(res.status, StatusCode::BAD_REQUEST);
    assert!(res.text().contains("id=\"name-error\""));
    // 50 family zones: no Add Zone on the list or the phone's page, a 51st refused with nothing written.
    for n in 1..50 {
        sqlx::query(
            "INSERT INTO family_zones (name, latitude, longitude, radius_m) VALUES (?, 60.0, 11.0, 150)",
        )
        .bind(format!("Sted {n}"))
        .execute(&app.db)
        .await
        .unwrap();
    }
    for page in ["/zones".to_string(), format!("/devices/{t1}/zones")] {
        let html = app.get_page(&page, &cookie).await.text();
        assert!(html.contains("data-row=\"zones-full\""), "{page}");
        assert!(html.contains("50 zones is the most"), "{page}");
        assert!(!html.contains("data-row=\"add-zone\""), "{page}");
    }
    let res = app
        .request_form(Method::POST, "/zones", Some(&cookie), &HOME)
        .await;
    assert_eq!(res.status, StatusCode::BAD_REQUEST);
    assert!(res.text().contains("data-flash=\"refused\""));
    assert_eq!(count(&app, "SELECT COUNT(*) FROM family_zones").await, 50);
}

// ------------------------------------------------------------------------------------------------
// The rule on links
// ------------------------------------------------------------------------------------------------

/// A zone linked to two phones is judged per phone; a moved family zone starts every link over silently and nudges
/// both phones; a rename keeps every side; a zone no phone uses is in no policy.
#[tokio::test]
async fn a_shared_zone_is_judged_per_phone() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (t1, token1) = app.enrolled_device("Telefon 1").await;
    let (t2, token2) = app.enrolled_device("Telefon 2").await;
    regularly(&app).await;
    // Hjem on both, then Skole on Telefon 2 alone, then Fotball on none: zone ids and link ids differ.
    let hjem = new_zone(&app, &cookie, &HOME, &[t1, t2]).await;
    let skole = new_zone(
        &app,
        &cookie,
        &[
            ("name", "Skole"),
            ("latitude", "59.95"),
            ("longitude", "10.7"),
            ("radius_m", "200"),
        ],
        &[t2],
    )
    .await;
    let fotball = new_zone(
        &app,
        &cookie,
        &[
            ("name", "Fotball"),
            ("latitude", "59.97"),
            ("longitude", "10.7"),
            ("radius_m", "300"),
        ],
        &[],
    )
    .await;
    let h1 = link_of(&app, t1, hjem).await.unwrap();
    let h2 = link_of(&app, t2, hjem).await.unwrap();
    let s2 = link_of(&app, t2, skole).await.unwrap();
    assert_ne!(h2, hjem);
    assert_ne!(s2, skole);
    assert_eq!(link_of(&app, t1, fotball).await, None);
    // The policy: each phone its own links, by the link's id; Fotball in none.
    assert_eq!(policy_zones(&app, &token1).await, [(h1, 150)]);
    assert_eq!(policy_zones(&app, &token2).await, [(h2, 150), (s2, 200)]);
    // Telefon 1 arrives; Telefon 2 raises nothing.
    for (minutes, place) in [(40, AWAY), (35, AWAY), (10, NEAR), (5, NEAR)] {
        report(&app, &token1, place, &at(minutes)).await;
    }
    report(&app, &token2, AWAY, &at(40)).await;
    let events: Vec<(i64, String)> = sqlx::query_as("SELECT device_id, params FROM parent_events")
        .fetch_all(&app.db)
        .await
        .unwrap();
    assert_eq!(events.len(), 1, "{events:?}");
    assert_eq!(events[0].0, t1);
    let params: serde_json::Value = serde_json::from_str(&events[0].1).unwrap();
    assert_eq!(params["zone_id"], h1, "the link's id, the phone's");
    assert_eq!(params["zone"], "Hjem");
    assert_eq!(side(&app, h1).await.as_deref(), Some("inside"));
    // A rename keeps every side and nudges nobody (the name never reaches a phone).
    let mut nudges = app.state.command_notify.subscribe();
    let mut fields = editor_form(&app, &cookie, hjem).await;
    set(&mut fields, "name", Some("Hjemme"));
    assert!(
        post(&app, &cookie, &format!("/zones/{hjem}"), &fields)
            .await
            .status
            .is_redirection()
    );
    assert_eq!(side(&app, h1).await.as_deref(), Some("inside"));
    assert!(nudged(&mut nudges).is_empty());
    // A moved centre: every link starts over silently, both phones nudged and served the new circle.
    let mut fields = editor_form(&app, &cookie, hjem).await;
    set(&mut fields, "latitude", Some("59.9140"));
    set(&mut fields, "radius_m", Some("250"));
    assert!(
        post(&app, &cookie, &format!("/zones/{hjem}"), &fields)
            .await
            .status
            .is_redirection()
    );
    assert_eq!(side(&app, h1).await, None);
    assert_eq!(side(&app, h2).await, None);
    let mut got = nudged(&mut nudges);
    got.sort_unstable();
    assert_eq!(got, [t1, t2]);
    assert_eq!(policy_zones(&app, &token1).await, [(h1, 250)]);
    report(&app, &token1, NEAR, &at(3)).await;
    report(&app, &token1, NEAR, &at(1)).await;
    assert_eq!(side(&app, h1).await.as_deref(), Some("inside"));
    assert_eq!(
        count(&app, "SELECT COUNT(*) FROM parent_events").await,
        1,
        "silent"
    );
    // Fotball, used by no phone, is never judged: it has no state anywhere.
    assert_eq!(
        count(&app, "SELECT COUNT(*) FROM device_zones WHERE zone_id IN (SELECT id FROM family_zones WHERE name = 'Fotball')").await,
        0
    );
    // The family list names the phones that use each zone, "no phone" quietly.
    let html = app.get_page("/zones?lang=nb", &cookie).await.text();
    assert!(html.contains("250 m · Telefon 1, Telefon 2"), "{html}");
    assert!(html.contains("200 m · Telefon 2"));
    assert!(html.contains("300 m · ingen telefon"));
    assert!(!html.contains("59.9"), "no coordinates on the list");
    // Locate's all-phones view: the row and its count.
    let locate = app.get_page("/devices/locate", &cookie).await.text();
    let row = locate.split("data-row=\"family-zones\"").nth(1).unwrap();
    assert!(row.split("</a>").next().unwrap().contains(">3</span>"));
}

/// Unlinking deletes the link's state and alerts and drops it from the policy; relinking gives a new id and a
/// silent first side; a delete cascades to the links, the alerts and the weather, and nudges the phones that used
/// the zone. The security log never carries coordinates.
#[tokio::test]
async fn unlink_relink_and_delete() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (t1, token1) = app.enrolled_device("Telefon 1").await;
    let (t2, _) = app.enrolled_device("Telefon 2").await;
    regularly(&app).await;
    sqlx::query("UPDATE device_policy SET lock_weather = 1")
        .execute(&app.db)
        .await
        .unwrap();
    let hjem = new_zone(&app, &cookie, &HOME, &[t1, t2]).await;
    let h1 = link_of(&app, t1, hjem).await.unwrap();
    for (minutes, place) in [(40, NEAR), (35, NEAR)] {
        report(&app, &token1, place, &at(minutes)).await;
    }
    assert_eq!(side(&app, h1).await.as_deref(), Some("inside"));
    // An alert off, and the weather on Hjem.
    let mut fields = phone_form(&app, &cookie, t1).await;
    set(&mut fields, &format!("leave_{h1}"), None);
    assert!(
        post(&app, &cookie, &format!("/devices/{t1}/zones"), &fields)
            .await
            .status
            .is_redirection()
    );
    assert_eq!(count(&app, "SELECT COUNT(*) FROM zone_alerts").await, 1);
    let res = app
        .request_form(
            Method::POST,
            &format!("/devices/{t1}/weather/zone"),
            Some(&cookie),
            &[("zone", &hjem.to_string())],
        )
        .await;
    assert!(res.status.is_redirection(), "{}", res.text());
    // Off for Telefon 1: the link, its state and its alerts go; the policy drops it; the weather stays.
    let mut nudges = app.state.command_notify.subscribe();
    let mut fields = phone_form(&app, &cookie, t1).await;
    set(&mut fields, &format!("use_{hjem}"), None);
    let res = post(&app, &cookie, &format!("/devices/{t1}/zones"), &fields).await;
    super::assert_redirect(
        &res,
        &format!("/devices/{t1}/zones?zone=saved&zone_id={hjem}"),
        Some(&format!("zone-{hjem}")),
    );
    app.assert_lands(&res, &cookie).await;
    assert_eq!(nudged(&mut nudges), [t1]);
    assert_eq!(link_of(&app, t1, hjem).await, None);
    assert_eq!(count(&app, "SELECT COUNT(*) FROM zone_alerts").await, 0);
    assert!(policy_zones(&app, &token1).await.is_empty());
    let weather = |phone: i64| {
        let app = &app;
        async move {
            sqlx::query_scalar::<_, Option<i64>>(
                "SELECT weather_family_zone_id FROM device_policy WHERE device_id = ?",
            )
            .bind(phone)
            .fetch_one(&app.db)
            .await
            .unwrap()
        }
    };
    assert_eq!(
        weather(t1).await,
        Some(hjem),
        "the weather needs a place, not a link"
    );
    let lock = app
        .get_page(&format!("/devices/{t1}/lock"), &cookie)
        .await
        .text();
    assert!(
        lock.contains(&format!("<option value=\"{hjem}\" selected>Hjem</option>")),
        "{lock}"
    );
    // On again: a new link, a silent first side.
    let mut fields = phone_form(&app, &cookie, t1).await;
    set(&mut fields, &format!("use_{hjem}"), Some("on"));
    assert!(
        post(&app, &cookie, &format!("/devices/{t1}/zones"), &fields)
            .await
            .status
            .is_redirection()
    );
    let again = link_of(&app, t1, hjem).await.unwrap();
    assert_ne!(again, h1);
    assert_eq!(side(&app, again).await, None);
    report(&app, &token1, NEAR, &at(2)).await;
    report(&app, &token1, NEAR, &at(1)).await;
    assert_eq!(side(&app, again).await.as_deref(), Some("inside"));
    assert_eq!(count(&app, "SELECT COUNT(*) FROM parent_events").await, 0);
    assert_eq!(policy_zones(&app, &token1).await, [(again, 150)]);
    // Delete: the links, the weather; both phones nudged.
    nudged(&mut nudges);
    let res = app
        .request_form(
            Method::POST,
            &format!("/zones/{hjem}/delete"),
            Some(&cookie),
            &[],
        )
        .await;
    super::assert_redirect(&res, "/zones?zone=deleted", Some("zones"));
    app.assert_lands(&res, &cookie).await;
    let mut got = nudged(&mut nudges);
    got.sort_unstable();
    assert_eq!(got, [t1, t2]);
    assert_eq!(count(&app, "SELECT COUNT(*) FROM device_zones").await, 0);
    assert_eq!(weather(t1).await, None);
    // The log: family lines and phone lines, never a coordinate.
    let mut log = Vec::new();
    for kind in ["zone_added", "zone_linked", "zone_unlinked", "zone_deleted"] {
        log.extend(super::security_words(&app, kind, crate::i18n::Lang::En).await);
    }
    assert_eq!(
        log,
        [
            "Hjem, 150 m".to_string(),
            format!("phone {t1} (Telefon 1): Hjem"),
            format!("phone {t2} (Telefon 2): Hjem"),
            format!("phone {t1} (Telefon 1): Hjem"),
            format!("phone {t1} (Telefon 1): Hjem"),
            "Hjem (Telefon 1, Telefon 2)".to_string(),
        ]
    );
    assert!(
        log.iter()
            .all(|line| !line.contains("59.") && !line.contains("10.75"))
    );
}

/// The weather's picker lists the family's zones (linked to the phone or not), and a zone the family doesn't have
/// is refused.
#[tokio::test]
async fn the_weather_picks_from_the_familys_zones() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (t1, _) = app.enrolled_device("Telefon 1").await;
    let (t2, _) = app.enrolled_device("Telefon 2").await;
    let skole = new_zone(
        &app,
        &cookie,
        &[
            ("name", "Skole"),
            ("latitude", "59.95"),
            ("longitude", "10.7"),
            ("radius_m", "200"),
        ],
        &[t2],
    )
    .await;
    sqlx::query("UPDATE device_policy SET lock_weather = 1")
        .execute(&app.db)
        .await
        .unwrap();
    let lock = app
        .get_page(&format!("/devices/{t1}/lock"), &cookie)
        .await
        .text();
    assert!(
        lock.contains(&format!("<option value=\"{skole}\">Skole</option>")),
        "{lock}"
    );
    let res = app
        .request_form(
            Method::POST,
            &format!("/devices/{t1}/weather/zone"),
            Some(&cookie),
            &[("zone", "999")],
        )
        .await;
    assert_eq!(res.status, StatusCode::BAD_REQUEST);
}

// ------------------------------------------------------------------------------------------------
// Caps
// ------------------------------------------------------------------------------------------------

/// An 11th link is refused from both pages with nothing written: the editor at its Phones group, the phone's page at
/// the zone's cell. A taken name is refused only when the name changed.
#[tokio::test]
async fn an_eleventh_link_is_refused_from_both_pages() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (t1, _) = app.enrolled_device("Telefon 1").await;
    for n in 0..10 {
        super::zones::insert_zone(
            &app,
            t1,
            &format!("Sted {n}"),
            60.0 + n as f64 / 100.0,
            11.0,
            150,
        )
        .await;
    }
    let spare = new_zone(&app, &cookie, &HOME, &[]).await;
    // The editor: a new zone with the full phone ticked.
    let mut fields: Vec<(String, String)> = [
        ("name", "Skole"),
        ("latitude", "59.95"),
        ("longitude", "10.7"),
        ("radius_m", "200"),
    ]
    .iter()
    .map(|(k, v)| (k.to_string(), v.to_string()))
    .collect();
    fields.push((format!("phone_{t1}"), "on".to_string()));
    let res = post(&app, &cookie, "/zones", &fields).await;
    assert_eq!(res.status, StatusCode::BAD_REQUEST);
    let html = res.text();
    assert!(html.contains("Telefon 1 already has 10 zones."), "{html}");
    assert!(html.contains("aria-invalid=\"true\" aria-describedby=\"phones-error\""));
    assert!(html.contains("#zone-form\" id=\"zone-form\""));
    assert_eq!(
        count(&app, "SELECT COUNT(*) FROM family_zones").await,
        11,
        "nothing written"
    );
    // The editor: ticking the full phone on an existing zone.
    let mut fields = editor_form(&app, &cookie, spare).await;
    set(&mut fields, &format!("phone_{t1}"), Some("on"));
    let res = post(&app, &cookie, &format!("/zones/{spare}"), &fields).await;
    assert_eq!(res.status, StatusCode::BAD_REQUEST);
    assert!(res.text().contains("Telefon 1 already has 10 zones."));
    // The phone's page: back at the zone's cell, refused, nothing written.
    let mut fields = phone_form(&app, &cookie, t1).await;
    set(&mut fields, &format!("use_{spare}"), Some("on"));
    let res = post(&app, &cookie, &format!("/devices/{t1}/zones"), &fields).await;
    super::assert_redirect(
        &res,
        &format!("/devices/{t1}/zones?refused=full&zone_id={spare}"),
        Some(&format!("zone-{spare}")),
    );
    app.assert_lands(&res, &cookie).await;
    assert_eq!(link_of(&app, t1, spare).await, None);
    assert_eq!(count(&app, "SELECT COUNT(*) FROM device_zones").await, 10);
    let html = app.get_page(res.location().unwrap(), &cookie).await.text();
    let cell = html.split(&format!("id=\"zone-{spare}\"")).nth(1).unwrap();
    assert!(
        cell.split("</div>")
            .next()
            .unwrap()
            .contains("10 zones is the most"),
        "{cell}"
    );
    assert_eq!(
        count(
            &app,
            "SELECT COUNT(*) FROM security_events WHERE event_type = 'settings_saved'"
        )
        .await,
        0,
        "a refusal is no save"
    );
    // A taken name, only when the name changes: "HJEM" for a new zone is taken.
    let res = app
        .request_form(
            Method::POST,
            "/zones",
            Some(&cookie),
            &[
                ("name", "HJEM"),
                ("latitude", "59.9"),
                ("longitude", "10.7"),
                ("radius_m", "150"),
            ],
        )
        .await;
    assert_eq!(res.status, StatusCode::BAD_REQUEST);
    assert!(
        res.text()
            .contains("The family already has a zone with that name.")
    );
    // Two migrated zones of one name (no rename, decision 5): the second saves its radius as it is.
    sqlx::query("UPDATE family_zones SET name = 'Hjem' WHERE name = 'Sted 0'")
        .execute(&app.db)
        .await
        .unwrap();
    let sted0: i64 =
        sqlx::query_scalar("SELECT id FROM family_zones WHERE name = 'Hjem' ORDER BY id LIMIT 1")
            .fetch_one(&app.db)
            .await
            .unwrap();
    let mut fields = editor_form(&app, &cookie, sted0).await;
    set(&mut fields, "radius_m", Some("400"));
    let res = post(&app, &cookie, &format!("/zones/{sted0}"), &fields).await;
    assert_eq!(res.status, StatusCode::SEE_OTHER, "{}", res.text());
}

// ------------------------------------------------------------------------------------------------
// Stale forms (H1)
// ------------------------------------------------------------------------------------------------

/// A stale phone page never undoes what it didn't change: a zone linked meanwhile stays linked, an alert changed
/// meanwhile stays, an alert for a link replaced meanwhile isn't written; a post that changed nothing lands on
/// `#zones`.
#[tokio::test]
async fn a_stale_phone_page_changes_only_what_it_changed() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (t2, _) = app.enrolled_device("Telefon 2").await;
    let hjem = new_zone(&app, &cookie, &HOME, &[t2]).await;
    let fotball = new_zone(
        &app,
        &cookie,
        &[
            ("name", "Fotball"),
            ("latitude", "59.97"),
            ("longitude", "10.7"),
            ("radius_m", "300"),
        ],
        &[],
    )
    .await;
    let h2 = link_of(&app, t2, hjem).await.unwrap();
    // Two tabs drawn at once (§11 step 6).
    let tab1 = phone_form(&app, &cookie, t2).await;
    let tab2 = phone_form(&app, &cookie, t2).await;
    // Tab 1 switches Fotball on.
    let mut fields = tab1.clone();
    set(&mut fields, &format!("use_{fotball}"), Some("on"));
    assert!(
        post(&app, &cookie, &format!("/devices/{t2}/zones"), &fields)
            .await
            .status
            .is_redirection()
    );
    assert!(link_of(&app, t2, fotball).await.is_some());
    // Tab 2 switches Kommer off for Hjem: Fotball stays linked.
    let mut fields = tab2.clone();
    set(&mut fields, &format!("arrive_{h2}"), None);
    let res = post(&app, &cookie, &format!("/devices/{t2}/zones"), &fields).await;
    super::assert_redirect(
        &res,
        &format!("/devices/{t2}/zones?zone=saved&zone_id={hjem}"),
        Some(&format!("zone-{hjem}")),
    );
    assert!(
        link_of(&app, t2, fotball).await.is_some(),
        "the stale tab didn't unlink it"
    );
    let alerts: Vec<(i64, bool, bool)> =
        sqlx::query_as("SELECT device_zone_id, arrive, leave FROM zone_alerts")
            .fetch_all(&app.db)
            .await
            .unwrap();
    assert_eq!(alerts, [(h2, false, true)]);
    // Tab 1 (still drawn with Kommer on, Drar on) turns Drar off: Kommer stays off.
    let mut fields = tab1.clone();
    set(&mut fields, &format!("leave_{h2}"), None);
    assert!(
        post(&app, &cookie, &format!("/devices/{t2}/zones"), &fields)
            .await
            .status
            .is_redirection()
    );
    let alerts: Vec<(i64, bool, bool)> =
        sqlx::query_as("SELECT device_zone_id, arrive, leave FROM zone_alerts")
            .fetch_all(&app.db)
            .await
            .unwrap();
    assert_eq!(alerts, [(h2, false, false)]);
    // Hjem unlinked and linked again meanwhile: a stale tab's alert for the old link writes nothing.
    let stale = phone_form(&app, &cookie, t2).await;
    let mut fields = phone_form(&app, &cookie, t2).await;
    set(&mut fields, &format!("use_{hjem}"), None);
    post(&app, &cookie, &format!("/devices/{t2}/zones"), &fields).await;
    let mut fields = phone_form(&app, &cookie, t2).await;
    set(&mut fields, &format!("use_{hjem}"), Some("on"));
    post(&app, &cookie, &format!("/devices/{t2}/zones"), &fields).await;
    let new_link = link_of(&app, t2, hjem).await.unwrap();
    assert_ne!(new_link, h2);
    let mut fields = stale.clone();
    set(&mut fields, &format!("arrive_{h2}"), Some("on"));
    let res = post(&app, &cookie, &format!("/devices/{t2}/zones"), &fields).await;
    super::assert_redirect(
        &res,
        &format!("/devices/{t2}/zones"),
        Some(&format!("zone-{hjem}")),
    );
    assert_eq!(
        count(&app, "SELECT COUNT(*) FROM zone_alerts").await,
        0,
        "nothing for the new link"
    );
    // A post that changed nothing (a stale page whose change was made already) lands on the first zone it named
    // (code QA L5), with no line; a post that names none lands on #zones.
    let res = post(&app, &cookie, &format!("/devices/{t2}/zones"), &stale).await;
    super::assert_redirect(
        &res,
        &format!("/devices/{t2}/zones"),
        Some(&format!("zone-{hjem}")),
    );
    app.assert_lands(&res, &cookie).await;
    let res = post(&app, &cookie, &format!("/devices/{t2}/zones"), &[]).await;
    super::assert_redirect(&res, &format!("/devices/{t2}/zones"), Some("zones"));
    app.assert_lands(&res, &cookie).await;
}

/// A stale editor keeps a link made meanwhile and another parent's radius; it writes only its own change.
#[tokio::test]
async fn a_stale_editor_keeps_other_changes() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let other = app.admin_cookie_as("forelder2", "Forelder 2").await;
    let (t1, _) = app.enrolled_device("Telefon 1").await;
    let (t2, _) = app.enrolled_device("Telefon 2").await;
    let hjem = new_zone(&app, &cookie, &HOME, &[t1]).await;
    let stale = editor_form(&app, &cookie, hjem).await;
    // Meanwhile: Telefon 2 links Hjem from its page, and the other parent widens it.
    let mut fields = phone_form(&app, &other, t2).await;
    set(&mut fields, &format!("use_{hjem}"), Some("on"));
    post(&app, &other, &format!("/devices/{t2}/zones"), &fields).await;
    let mut fields = editor_form(&app, &other, hjem).await;
    set(&mut fields, "radius_m", Some("400"));
    post(&app, &other, &format!("/zones/{hjem}"), &fields).await;
    // The stale editor renames.
    let mut fields = stale.clone();
    set(&mut fields, "name", Some("Hjemme"));
    let res = post(&app, &cookie, &format!("/zones/{hjem}"), &fields).await;
    super::assert_redirect(
        &res,
        &format!("/zones?zone=saved&zone_id={hjem}"),
        Some(&format!("zone-{hjem}")),
    );
    let stored: (String, i64) =
        sqlx::query_as("SELECT name, radius_m FROM family_zones WHERE id = ?")
            .bind(hjem)
            .fetch_one(&app.db)
            .await
            .unwrap();
    assert_eq!(stored, ("Hjemme".to_string(), 400));
    assert!(
        link_of(&app, t2, hjem).await.is_some(),
        "the link made meanwhile stays"
    );
    // The stale editor posted again unchanged: nothing changed, the row with no line.
    let res = post(&app, &cookie, &format!("/zones/{hjem}"), &stale).await;
    super::assert_redirect(&res, "/zones", Some(&format!("zone-{hjem}")));
    // `device` is whitelisted: an unknown phone lands on the family's list.
    let mut fields = editor_form(&app, &cookie, hjem).await;
    set(&mut fields, "device", Some("999"));
    set(&mut fields, "radius_m", Some("500"));
    let res = post(&app, &cookie, &format!("/zones/{hjem}"), &fields).await;
    super::assert_redirect(
        &res,
        &format!("/zones?zone=saved&zone_id={hjem}"),
        Some(&format!("zone-{hjem}")),
    );
    let html = app
        .get_page(&format!("/zones/{hjem}?device=999"), &cookie)
        .await
        .text();
    assert!(html.contains("class=\"nav-back\" href=\"/zones\""));
}

// ------------------------------------------------------------------------------------------------
// Alerts and the push (decision 7, §6)
// ------------------------------------------------------------------------------------------------

/// Every crossing is an inbox event, also with every switch off; each parent's own switch for the phone, zone and
/// direction filters only their push, when it is queued; a removed parent gets none; a new parent gets it; a cell just
/// switched on keeps its alerts on.
#[tokio::test]
async fn every_crossing_is_stored_and_each_parent_filters_the_push() {
    let app = TestApp::new().await;
    let first = app.admin_cookie().await;
    subscribe(&app, &first, ENDPOINT).await;
    let second = app.admin_cookie_as("forelder2", "Forelder 2").await;
    subscribe(&app, &second, SECOND_ENDPOINT).await;
    let (a, b) = (
        admin_id(&app, "parent").await,
        admin_id(&app, "forelder2").await,
    );
    let (t1, token) = app.enrolled_device("Telefon 1").await;
    let hjem = new_zone(&app, &first, &HOME, &[]).await;
    // Switched on from the phone's page: no alert fields, so every parent's alerts stay on (no rows).
    let mut fields = phone_form(&app, &first, t1).await;
    set(&mut fields, &format!("use_{hjem}"), Some("on"));
    post(&app, &first, &format!("/devices/{t1}/zones"), &fields).await;
    let h1 = link_of(&app, t1, hjem).await.unwrap();
    assert_eq!(count(&app, "SELECT COUNT(*) FROM zone_alerts").await, 0);
    let page = app
        .get_page(&format!("/devices/{t1}/zones"), &first)
        .await
        .text();
    assert_eq!(
        super::input_checked(&page, &format!("arrive_{h1}"), "on"),
        Some(true)
    );
    assert!(super::autosaves(&page, &format!("arrive_{h1}")));
    // The second parent turns Kommer off for Telefon 1's Hjem.
    let mut fields = phone_form(&app, &second, t1).await;
    set(&mut fields, &format!("arrive_{h1}"), None);
    post(&app, &second, &format!("/devices/{t1}/zones"), &fields).await;
    // The first parent's own page still shows Kommer on: the switches are each parent's.
    let page = app
        .get_page(&format!("/devices/{t1}/zones"), &first)
        .await
        .text();
    assert_eq!(
        super::input_checked(&page, &format!("arrive_{h1}"), "on"),
        Some(true)
    );
    report(&app, &token, AWAY, &at(40)).await;
    report(&app, &token, AWAY, &at(35)).await;
    report(&app, &token, NEAR, &at(4)).await;
    report(&app, &token, NEAR, &at(2)).await;
    assert_eq!(
        delivered_to(&app, "zone_arrived").await,
        [a],
        "the first parent only"
    );
    // The filter runs at queueing: the first parent's switch off afterwards leaves the queued delivery.
    let mut fields = phone_form(&app, &first, t1).await;
    set(&mut fields, &format!("arrive_{h1}"), None);
    post(&app, &first, &format!("/devices/{t1}/zones"), &fields).await;
    assert_eq!(delivered_to(&app, "zone_arrived").await, [a]);
    // A third parent, added now (no rows: on), and the second parent removed: leaving pushes to the first and third.
    let third = app.admin_cookie_as("forelder3", "Forelder 3").await;
    subscribe(&app, &third, THIRD_ENDPOINT).await;
    let c = admin_id(&app, "forelder3").await;
    sqlx::query("UPDATE admin_users SET removed_at = datetime('now') WHERE id = ?")
        .bind(b)
        .execute(&app.db)
        .await
        .unwrap();
    report(&app, &token, AWAY, &at(1)).await;
    report(&app, &token, AWAY, &at(0)).await;
    let mut want = vec![a, c];
    want.sort_unstable();
    assert_eq!(delivered_to(&app, "zone_left").await, want);
    // Every switch off for everyone: still an inbox event, no push.
    sqlx::query("INSERT OR REPLACE INTO zone_alerts (admin_id, device_zone_id, arrive, leave) VALUES (?, ?, 0, 0), (?, ?, 0, 0)")
        .bind(a)
        .bind(h1)
        .bind(c)
        .bind(h1)
        .execute(&app.db)
        .await
        .unwrap();
    let deliveries = count(&app, "SELECT COUNT(*) FROM push_deliveries").await;
    sqlx::query("UPDATE device_zones SET side = 'outside', pending_side = NULL")
        .execute(&app.db)
        .await
        .unwrap();
    let soon = |s: i64| (Utc::now() + chrono::Duration::seconds(s)).to_rfc3339();
    report(&app, &token, NEAR, &soon(30)).await;
    report(&app, &token, NEAR, &soon(100)).await;
    assert_eq!(
        count(
            &app,
            "SELECT COUNT(*) FROM parent_events WHERE kind = 'zone_arrived'"
        )
        .await,
        2,
        "stored with every switch off"
    );
    assert_eq!(
        count(&app, "SELECT COUNT(*) FROM push_deliveries").await,
        deliveries
    );
}

/// Code QA test gap: two links asked at once from 9 - the first would make 10, the second 11 - refuse the whole post:
/// the first link is rolled back with it, and the page comes back at the second zone's cell.
#[tokio::test]
async fn two_links_from_nine_are_refused_together() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (t1, _) = app.enrolled_device("Telefon 1").await;
    for n in 0..9 {
        super::zones::insert_zone(
            &app,
            t1,
            &format!("Sted {n}"),
            60.0 + n as f64 / 100.0,
            11.0,
            150,
        )
        .await;
    }
    let first = new_zone(&app, &cookie, &HOME, &[]).await;
    let second = new_zone(
        &app,
        &cookie,
        &[
            ("name", "Skole"),
            ("latitude", "59.95"),
            ("longitude", "10.7"),
            ("radius_m", "200"),
        ],
        &[],
    )
    .await;
    let mut nudges = app.state.command_notify.subscribe();
    let mut fields = phone_form(&app, &cookie, t1).await;
    set(&mut fields, &format!("use_{first}"), Some("on"));
    set(&mut fields, &format!("use_{second}"), Some("on"));
    let res = post(&app, &cookie, &format!("/devices/{t1}/zones"), &fields).await;
    super::assert_redirect(
        &res,
        &format!("/devices/{t1}/zones?refused=full&zone_id={second}"),
        Some(&format!("zone-{second}")),
    );
    assert_eq!(
        link_of(&app, t1, first).await,
        None,
        "rolled back with the refusal"
    );
    assert_eq!(link_of(&app, t1, second).await, None);
    assert_eq!(count(&app, "SELECT COUNT(*) FROM device_zones").await, 9);
    assert_eq!(
        count(
            &app,
            "SELECT COUNT(*) FROM security_events WHERE event_type = 'zone_linked'"
        )
        .await,
        0
    );
    assert!(nudged(&mut nudges).is_empty());
}

/// Code QA test gap: the handlers, not the foreign keys alone - unticking the phone in the editor keeps the weather's
/// family zone; deleting the zone from its editor clears it.
#[tokio::test]
async fn the_editor_unlink_keeps_the_weather_and_its_delete_clears_it() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (t1, token1) = app.enrolled_device("Telefon 1").await;
    regularly(&app).await;
    let hjem = new_zone(&app, &cookie, &HOME, &[t1]).await;
    let res = app
        .request_form(
            Method::POST,
            &format!("/devices/{t1}/weather/zone"),
            Some(&cookie),
            &[("zone", &hjem.to_string())],
        )
        .await;
    assert!(res.status.is_redirection(), "{}", res.text());
    let weather = || async {
        sqlx::query_scalar::<_, Option<i64>>(
            "SELECT weather_family_zone_id FROM device_policy WHERE device_id = ?",
        )
        .bind(t1)
        .fetch_one(&app.db)
        .await
        .unwrap()
    };
    assert_eq!(weather().await, Some(hjem));
    let mut fields = editor_form(&app, &cookie, hjem).await;
    set(&mut fields, &format!("phone_{t1}"), None);
    let res = post(&app, &cookie, &format!("/zones/{hjem}"), &fields).await;
    assert_eq!(res.status, StatusCode::SEE_OTHER, "{}", res.text());
    assert_eq!(link_of(&app, t1, hjem).await, None);
    assert!(policy_zones(&app, &token1).await.is_empty());
    assert_eq!(
        weather().await,
        Some(hjem),
        "the weather needs a place, not a link"
    );
    let res = app
        .request_form(
            Method::POST,
            &format!("/zones/{hjem}/delete"),
            Some(&cookie),
            &[],
        )
        .await;
    assert!(res.status.is_redirection(), "{}", res.text());
    assert_eq!(weather().await, None);
}
