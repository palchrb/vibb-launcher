//! Design 38: `POST /api/devices/power` - the phone turned off and on, its words, the held
//! `phone_off` push and a restart's drop, the switches, the device page's "Off since" line and the
//! shared contract `testdata/power_report.json`.

use axum::http::{Method, StatusCode};
use chrono::{DateTime, Duration, Utc};
use serde_json::{Value, json};

use super::web_push::{ENDPOINT, payload, subscribe};
use super::{Section, TestApp, device_page};

/// The launcher's body (`PowerEventsTest` encodes exactly this; byte-identical in its test
/// resources).
const POWER_REPORT: &str = include_str!("../../testdata/power_report.json");

#[test]
fn the_power_contract_matches_the_launchers_copy() {
    let copy_path = std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("../launcher/app/src/test/resources/power_report.json");
    let copy = std::fs::read_to_string(&copy_path)
        .unwrap_or_else(|e| panic!("{}: {e}", copy_path.display()));
    assert_eq!(copy, POWER_REPORT, "{} differs", copy_path.display());
}

async fn post(app: &TestApp, token: &str, body: Value) -> StatusCode {
    app.request(Method::POST, "/api/devices/power", Some(token), Some(body))
        .await
        .status
}

fn ms(t: DateTime<Utc>) -> i64 {
    t.timestamp_millis()
}

fn off(at: DateTime<Utc>, boot: i64, extra: Value) -> Value {
    let mut off = json!({
        "at_ms": ms(at), "boot_count": boot,
        "battery": { "level": 2, "charging": false, "low": true },
    });
    off.as_object_mut()
        .unwrap()
        .extend(extra.as_object().unwrap().clone());
    off
}

fn on(at: DateTime<Utc>, boot: i64) -> Value {
    json!({ "at_ms": ms(at), "boot_count": boot,
            "battery": { "level": 60, "charging": true, "low": false } })
}

/// `(kind, title, summary)` of a phone's events, oldest first.
async fn events(app: &TestApp, device: i64) -> Vec<(String, String, String)> {
    sqlx::query_as("SELECT kind, title, summary FROM parent_events WHERE device_id = ? ORDER BY id")
        .bind(device)
        .fetch_all(&app.db)
        .await
        .unwrap()
}

/// `(status, reason, next_try_at)` of the deliveries of a phone's events of `kind`.
async fn deliveries(app: &TestApp, kind: &str) -> Vec<(String, Option<String>, Option<String>)> {
    sqlx::query_as(
        "SELECT d.status, d.reason, d.next_try_at FROM push_deliveries d \
         JOIN parent_events e ON e.id = d.event_id WHERE e.kind = ? ORDER BY d.id",
    )
    .bind(kind)
    .fetch_all(&app.db)
    .await
    .unwrap()
}

/// The titles the sender pushes at `now`.
async fn send_at(app: &TestApp, now: DateTime<Utc>) -> Vec<String> {
    crate::push::send_due(&app.state, now).await.unwrap();
    app.pushes
        .take()
        .iter()
        .map(|r| {
            payload(r)["notification"]["title"]
                .as_str()
                .unwrap()
                .to_string()
        })
        .collect()
}

/// A phone that reports, and a parent with one browser subscribed.
async fn setup() -> (TestApp, i64, String) {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    subscribe(&app, &cookie, ENDPOINT).await;
    let (id, token) = app.enrolled_device("Anna").await;
    (app, id, token)
}

/// The launcher's own body, posted as sent but for its instants (moved to the last hour, the gap
/// kept, so the 30-day rule keeps the off): the off is told with the on, unpushed; the on pushed.
#[tokio::test]
async fn a_launcher_report_raises_both() {
    let (app, id, token) = setup().await;
    let mut body: Value = serde_json::from_str(POWER_REPORT).unwrap();
    let gap = body["on"]["at_ms"].as_i64().unwrap() - body["off"]["at_ms"].as_i64().unwrap();
    // Design 59 §1: when the PIN was typed after the boot (step 2 words it).
    let unlocked_after =
        body["on"]["unlocked_at_ms"].as_i64().unwrap() - body["on"]["at_ms"].as_i64().unwrap();
    // 2026-10-25: the shutdown's last stored fix, taken this long before it.
    let fix_before =
        body["off"]["at_ms"].as_i64().unwrap() - body["off"]["location"]["at_ms"].as_i64().unwrap();
    let on_at = Utc::now() - Duration::minutes(20);
    body["on"]["at_ms"] = json!(ms(on_at));
    body["on"]["unlocked_at_ms"] = json!(ms(on_at) + unlocked_after);
    body["off"]["at_ms"] = json!(ms(on_at) - gap);
    body["off"]["location"]["at_ms"] = json!(ms(on_at) - gap - fix_before);
    let parsed: crate::models::PowerReport = serde_json::from_value(body.clone()).unwrap();
    assert_eq!(
        parsed.on.unwrap().unlocked_at_ms,
        Some(ms(on_at) + unlocked_after)
    );
    // Anything but a whole number is unknown, never a refused body.
    let odd: crate::models::PowerReport = serde_json::from_value(
        json!({ "on": { "at_ms": 1, "boot_count": 1, "unlocked_at_ms": "soon" } }),
    )
    .unwrap();
    assert_eq!(odd.on.unwrap().unlocked_at_ms, None);
    assert_eq!(
        post(&app, &token, body.clone()).await,
        StatusCode::NO_CONTENT
    );
    let rows = events(&app, id).await;
    assert_eq!(rows.len(), 2, "{rows:?}");
    assert_eq!(
        (rows[0].0.as_str(), rows[0].1.as_str()),
        ("phone_off", "Anna was turned off")
    );
    assert!(
        rows[0]
            .2
            .ends_with("battery 3 %, probably flat · told at the next start"),
        "{rows:?}"
    );
    assert_eq!(
        (rows[1].0.as_str(), rows[1].1.as_str()),
        ("phone_on", "Anna was turned on")
    );
    assert!(rows[1].2.contains("off since"), "{rows:?}");
    assert!(rows[1].2.ends_with(", battery was 3 %"), "{rows:?}");
    assert!(deliveries(&app, "phone_off").await.is_empty());
    assert_eq!(send_at(&app, Utc::now()).await, ["Anna was turned on"]);
    // The off's last position: one of the phone's fixes, marked as the shutdown's, and in the
    // event's parameters.
    let fixes = shutdown_fixes(&app, id).await;
    assert_eq!(fixes.len(), 1, "{fixes:?}");
    assert_eq!((fixes[0].0, fixes[0].1), (59.9139, 10.7522));
    let off_at = DateTime::from_timestamp_millis(ms(on_at) - gap).unwrap();
    assert_eq!(
        fixes[0].2.as_deref(),
        Some(crate::zones::instant(off_at).as_str())
    );
    let (params, _) = off_params(&app, id).await;
    assert_eq!(params["location"]["provider"], "fused");
    assert_eq!(params["location"]["accuracy_m"], 25.0);

    // The same body again: nothing new (the kept off is resent with every later start's on).
    assert_eq!(post(&app, &token, body).await, StatusCode::NO_CONTENT);
    assert_eq!(events(&app, id).await.len(), 2);
    assert!(send_at(&app, Utc::now()).await.is_empty());
}

/// The phone's fixes marked as a shutdown's: `(latitude, longitude, shutdown_at)`.
async fn shutdown_fixes(app: &TestApp, device: i64) -> Vec<(f64, f64, Option<String>)> {
    sqlx::query_as(
        "SELECT latitude, longitude, shutdown_at FROM device_locations WHERE device_id = ? \
         ORDER BY id",
    )
    .bind(device)
    .fetch_all(&app.db)
    .await
    .unwrap()
}

/// The newest `phone_off`'s parameters (`Value::Null` without) and inbox detail.
async fn off_params(app: &TestApp, device: i64) -> (Value, Option<String>) {
    let (params, detail): (Option<String>, Option<String>) = sqlx::query_as(
        "SELECT params, detail FROM parent_events WHERE device_id = ? AND kind = 'phone_off' \
         ORDER BY id DESC LIMIT 1",
    )
    .bind(device)
    .fetch_one(&app.db)
    .await
    .unwrap();
    let params = params.map_or(Value::Null, |p| serde_json::from_str(&p).unwrap());
    (params, detail)
}

/// 2026-10-25 (PLAN "A last position at shutdown and at low battery"): the shutdown's last stored
/// fix is stored once as a fix of the phone (the row it had reported is marked, not copied), Locate
/// says it is the last position before the phone went off until the phone reports again, the
/// event's parameters carry it, the inbox says where to see it - and no text, the push's included,
/// has a coordinate.
#[tokio::test]
async fn the_shutdowns_last_position_is_on_locate() {
    let (app, id, token) = setup().await;
    let now = Utc::now();
    let fix_at = now - Duration::minutes(10);
    let captured_at = fix_at.to_rfc3339_opts(chrono::SecondsFormat::Millis, true);
    // The phone had reported this fix already.
    let status = |extra: Value| {
        let mut body = json!({ "lock_reason": "NONE", "kiosk_engaged": true });
        body.as_object_mut()
            .unwrap()
            .extend(extra.as_object().unwrap().clone());
        body
    };
    let report = status(
        json!({ "location": { "latitude": 59.9139, "longitude": 10.7522,
        "accuracy_meters": 25.0, "captured_at": captured_at, "provider": "fused" } }),
    );
    let res = app
        .request(
            Method::POST,
            "/api/devices/status",
            Some(&token),
            Some(report),
        )
        .await;
    assert_eq!(res.status, StatusCode::NO_CONTENT);
    let shutdown = Utc::now() + Duration::seconds(1);
    let location = json!({ "lat": 59.9139, "lng": 10.7522, "accuracy_m": 25.0,
                           "at_ms": ms(fix_at), "provider": "fused" });
    assert_eq!(
        post(
            &app,
            &token,
            json!({ "off": off(shutdown, 41, json!({ "location": location })) })
        )
        .await,
        StatusCode::NO_CONTENT
    );
    let fixes = shutdown_fixes(&app, id).await;
    assert_eq!(
        fixes.len(),
        1,
        "the reported row is marked, not copied: {fixes:?}"
    );
    assert_eq!(
        fixes[0].2.as_deref(),
        Some(crate::zones::instant(shutdown).as_str())
    );
    // The event: its words as before (no coordinates, the push unchanged), the position in its
    // parameters, the inbox's detail without a place.
    let (params, detail) = off_params(&app, id).await;
    assert_eq!(params["location"]["lat"], 59.9139);
    assert_eq!(params["location"]["at_ms"], ms(fix_at));
    let detail = detail.unwrap();
    assert!(
        detail.starts_with("Last position from ") && detail.ends_with(": shown on Locate"),
        "{detail}"
    );
    let rows = events(&app, id).await;
    assert_eq!(rows[0].1, "Anna was turned off");
    for text in [&rows[0].2, &detail] {
        assert!(!text.contains("59.") && !text.contains("10.75"), "{text}");
    }
    crate::push::send_due(&app.state, now + Duration::minutes(6))
        .await
        .unwrap();
    let pushed = app.pushes.take();
    assert_eq!(pushed.len(), 1);
    let push = payload(&pushed[0]);
    assert_eq!(push["notification"]["title"], "Anna was turned off");
    let push_text = push.to_string();
    assert!(
        !push_text.contains("59.9") && !push_text.contains("Locate"),
        "{push_text}"
    );
    let cookie = app.admin_cookie().await;
    let inbox = app.get_page("/notifications", &cookie).await.text();
    assert!(inbox.contains("shown on Locate"), "{inbox}");
    // Locate: the newest fix is the shutdown's, the phone hasn't reported since.
    let path = format!("/devices/locate?device={id}");
    let page = app.get_page(&path, &cookie).await.text();
    assert!(page.contains("data-fix-before-off"), "{page}");
    assert!(page.contains("Last position before the phone went off"));
    assert!(page.contains("a sudden power loss or airplane mode leaves nothing"));
    // On again: a report (with the same fix re-sent) ends the line; the fix stays.
    sqlx::query(
        "UPDATE device_status SET reported_at = datetime('now', '+2 minutes') WHERE device_id = ?",
    )
    .bind(id)
    .execute(&app.db)
    .await
    .unwrap();
    let page = app.get_page(&path, &cookie).await.text();
    assert!(!page.contains("data-fix-before-off"), "on again");
    assert_eq!(shutdown_fixes(&app, id).await.len(), 1);
}

/// A shutdown's position that isn't one costs the position only: the off is stored and told,
/// nothing reaches the fixes, the event has no parameters.
#[tokio::test]
async fn a_bad_shutdown_position_costs_the_position_only() {
    let (app, id, token) = setup().await;
    let now = Utc::now();
    for (n, location) in [
        json!({ "lat": 200.0, "lng": 10.7, "at_ms": ms(now - Duration::minutes(5)) }),
        json!({ "lat": 59.9, "lng": 10.7, "at_ms": ms(now + Duration::hours(1)) }),
        json!({ "lat": "north", "lng": 10.7, "at_ms": 1 }),
        json!("here"),
    ]
    .into_iter()
    .enumerate()
    {
        let at = now - Duration::minutes(n as i64);
        assert_eq!(
            post(
                &app,
                &token,
                json!({ "off": off(at, 41, json!({ "location": location })) })
            )
            .await,
            StatusCode::NO_CONTENT,
            "{n}"
        );
        assert_eq!(off_params(&app, id).await, (Value::Null, None), "{n}");
    }
    assert_eq!(events(&app, id).await.len(), 4);
    assert!(shutdown_fixes(&app, id).await.is_empty());
}

/// qa-38-design H1: the shutdown's POST got through, then the next boot's start - within the
/// gap a restart (one push), past it two.
#[tokio::test]
async fn a_restart_drops_the_held_off_push() {
    let (app, id, token) = setup().await;
    let now = Utc::now();
    let shutdown = now - Duration::seconds(30);
    assert_eq!(
        post(&app, &token, json!({ "off": off(shutdown, 41, json!({})) })).await,
        StatusCode::NO_CONTENT
    );
    let held = deliveries(&app, "phone_off").await;
    assert_eq!(held.len(), 1);
    let until = crate::display_time::parse_db_utc(held[0].2.as_deref().unwrap()).unwrap();
    assert!(
        (until - (now + Duration::minutes(5))).num_seconds().abs() <= 2,
        "{held:?}"
    );
    assert!(
        send_at(&app, now + Duration::minutes(4)).await.is_empty(),
        "held"
    );
    // The inbox's off row says "Sending" during the hold.
    let cookie = app.admin_cookie().await;
    let inbox = app.get_page("/notifications", &cookie).await.text();
    assert_eq!(delivery_lines(&inbox), ["Sending"]);

    // The start 90 s after the shutdown: "restarted", the held push dropped, never sent.
    let start = shutdown + Duration::seconds(90);
    let body = json!({ "off": off(shutdown, 41, json!({})), "on": on(start, 42) });
    assert_eq!(post(&app, &token, body).await, StatusCode::NO_CONTENT);
    assert_eq!(
        deliveries(&app, "phone_off").await,
        [("dropped".to_string(), Some("restart".to_string()), None)]
    );
    let rows = events(&app, id).await;
    assert_eq!(rows.len(), 2);
    assert_eq!(rows[1].1, "Anna restarted");
    assert!(rows[1].2.ends_with(" · battery 60 %"), "{rows:?}");
    assert_eq!(
        send_at(&app, now + Duration::minutes(6)).await,
        ["Anna restarted"]
    );
    // The off row has no delivery line (not "Not sent"): only the start's.
    let inbox = app.get_page("/notifications", &cookie).await.text();
    assert_eq!(delivery_lines(&inbox), ["Delivered to iPhone · Safari"]);
}

/// The inbox rows' delivery lines.
fn delivery_lines(html: &str) -> Vec<String> {
    html.split("<span class=\"row-sub inbox-delivery\">")
        .skip(1)
        .map(|rest| rest[..rest.find('<').unwrap()].to_string())
        .collect()
}

/// The same with the start 10 min after the shutdown: the held push goes at the hold's end, then
/// "turned on" with its off.
#[tokio::test]
async fn an_off_without_a_quick_start_is_pushed_after_the_hold() {
    let (app, id, token) = setup().await;
    let now = Utc::now();
    let shutdown = now - Duration::minutes(10);
    post(&app, &token, json!({ "off": off(shutdown, 41, json!({})) })).await;
    assert_eq!(
        send_at(&app, now + Duration::minutes(5) + Duration::seconds(3)).await,
        ["Anna was turned off"]
    );
    let body = json!({ "off": off(shutdown, 41, json!({})), "on": on(now, 42) });
    post(&app, &token, body).await;
    assert_eq!(
        send_at(&app, now + Duration::minutes(6)).await,
        ["Anna was turned on"]
    );
    let rows = events(&app, id).await;
    assert_eq!(rows.len(), 2);
    assert!(rows[1].2.contains(" · off since "), "{rows:?}");
}

/// A power-off and a start inside the hold, not a restart (the phone said so): the held push goes
/// at once, before the "turned on" - never after it.
#[tokio::test]
async fn a_quick_start_after_a_power_off_sends_the_off_first() {
    let (app, _, token) = setup().await;
    let now = Utc::now();
    let shutdown = now - Duration::minutes(2);
    let power_off = off(shutdown, 41, json!({ "reboot": false }));
    post(&app, &token, json!({ "off": power_off.clone() })).await;
    let body = json!({ "off": power_off, "on": on(now - Duration::seconds(5), 42) });
    post(&app, &token, body).await;
    assert_eq!(
        send_at(&app, now).await,
        ["Anna was turned off", "Anna was turned on"]
    );
}

/// The restart flag the phone could read: nothing at the shutdown, one push at the start.
#[tokio::test]
async fn a_known_restart_pushes_only_the_start() {
    let (app, _, token) = setup().await;
    let now = Utc::now();
    let shutdown = now - Duration::minutes(20);
    let restarting = off(
        shutdown,
        41,
        json!({ "reboot": true, "reason": "userrequested" }),
    );
    post(&app, &token, json!({ "off": restarting.clone() })).await;
    assert!(deliveries(&app, "phone_off").await.is_empty());
    let body = json!({ "off": restarting, "on": on(now, 42) });
    post(&app, &token, body).await;
    assert_eq!(send_at(&app, now).await, ["Anna restarted"]);
}

/// A start with no shutdown seen says the last report before the boot; a switch off stores the
/// event without a push; a re-enrollment's start is a new row.
#[tokio::test]
async fn a_start_without_a_shutdown_switches_and_re_enrollment() {
    let (app, id, token) = setup().await;
    let report = json!({ "lock_reason": "NONE", "kiosk_engaged": true,
                         "battery": { "level": 5, "charging": false, "low": true } });
    let res = app
        .request(
            Method::POST,
            "/api/devices/status",
            Some(&token),
            Some(report),
        )
        .await;
    assert_eq!(res.status, StatusCode::NO_CONTENT);
    sqlx::query(
        "INSERT INTO notification_prefs (admin_id, push_phone_on) SELECT id, 0 FROM admin_users",
    )
    .execute(&app.db)
    .await
    .unwrap();
    let boot = Utc::now() + Duration::seconds(2);
    post(&app, &token, json!({ "on": on(boot, 7) })).await;
    let rows = events(&app, id).await;
    let summary = &rows.last().unwrap().2;
    assert!(
        summary.contains(" · no shutdown seen · last report ")
            && summary.ends_with(", battery 5 %, probably flat"),
        "{summary}"
    );
    assert!(
        deliveries(&app, "phone_on").await.is_empty(),
        "switched off"
    );

    // The same count after a re-enrollment (a new enrolled_at) is another boot.
    post(&app, &token, json!({ "on": on(boot, 7) })).await;
    assert_eq!(events(&app, id).await.len(), rows.len());
    sqlx::query("UPDATE devices SET enrolled_at = '2030-01-01 00:00:00' WHERE id = ?")
        .bind(id)
        .execute(&app.db)
        .await
        .unwrap();
    post(&app, &token, json!({ "on": on(boot, 7) })).await;
    assert_eq!(events(&app, id).await.len(), rows.len() + 1);
}

#[tokio::test]
async fn the_route_refuses_what_isnt_a_power_report() {
    let app = TestApp::new().await;
    let (id, token) = app.enrolled_device("Anna").await;
    let now = Utc::now();
    assert_eq!(post(&app, &token, json!({})).await, StatusCode::BAD_REQUEST);
    assert_eq!(
        post(&app, &token, json!({ "off": { "boot_count": 3 } })).await,
        StatusCode::BAD_REQUEST
    );
    let big = off(now, 3, json!({ "reason": "x".repeat(2100) }));
    assert_eq!(
        post(&app, &token, json!({ "off": big })).await,
        StatusCode::PAYLOAD_TOO_LARGE
    );
    let res = app
        .request(
            Method::POST,
            "/api/devices/power",
            None,
            Some(json!({ "on": on(now, 1) })),
        )
        .await;
    assert_eq!(res.status, StatusCode::UNAUTHORIZED);
    // A bad battery or reason costs only itself; an unknown reason is never shown.
    let odd = off(
        now,
        3,
        // A whole battery object, so only its level range refuses it (qa-38-code L1 b).
        json!({ "battery": { "level": 140, "charging": false, "low": false }, "reason": "<b>flat</b>" }),
    );
    assert_eq!(
        post(&app, &token, json!({ "off": odd })).await,
        StatusCode::NO_CONTENT
    );
    assert_eq!(
        events(&app, id).await,
        [(
            "phone_off".to_string(),
            "Anna was turned off".to_string(),
            crate::display_time::Zone::load(&app.db).await.at(
                DateTime::from_timestamp_millis(ms(now)).unwrap(),
                Utc::now()
            )
        )]
    );
}

/// §3.4: the summary's "Off since 21:14 · battery was 2 %" while the newest off is later than the
/// newest on, gone with the next start.
#[tokio::test]
async fn the_device_page_says_off_since_until_the_start() {
    let (app, id, token) = setup().await;
    let cookie = app.admin_cookie().await;
    let page = || async {
        app.get_page(&device_page(id, Section::Summary), &cookie)
            .await
            .text()
    };
    // The off's push held no more: it arrived longer than OFF_HOLD ago.
    let held_over = || async {
        sqlx::query("UPDATE parent_events SET created_at = datetime('now', '-6 minutes')")
            .execute(&app.db)
            .await
            .unwrap();
    };
    assert!(!page().await.contains("data-power-off"));
    let shutdown = Utc::now() - Duration::minutes(30);
    post(&app, &token, json!({ "off": off(shutdown, 41, json!({})) })).await;
    // Not while its push is held (a restart's start may still fold it, qa-38-code nit 1).
    assert!(!page().await.contains("data-power-off"));
    held_over().await;
    let html = page().await;
    let when = crate::display_time::Zone::load(&app.db).await.at(
        DateTime::from_timestamp_millis(ms(shutdown)).unwrap(),
        Utc::now(),
    );
    assert!(
        html.contains(&format!(
            "<span class=\"summary-screen\" data-power-off>Off since {when} · battery was 2 %</span>"
        )),
        "{html}"
    );
    post(&app, &token, json!({ "on": on(Utc::now(), 42) })).await;
    assert!(!page().await.contains("data-power-off"));

    // A known restart never says "Off since".
    let restarting = Utc::now() + Duration::seconds(5);
    post(
        &app,
        &token,
        json!({ "off": off(restarting, 42, json!({ "reboot": true })) }),
    )
    .await;
    held_over().await;
    assert!(!page().await.contains("data-power-off"));
}

/// qa-38-code M1: a status report after the shutdown ends the line, whatever start never arrived
/// (a re-enrollment after a factory reset, a boot count of -1, a dropped start) - compared as
/// times (`datetime()`), not as the two columns' different texts.
#[tokio::test]
async fn a_report_after_the_shutdown_ends_off_since() {
    let (app, id, token) = setup().await;
    let cookie = app.admin_cookie().await;
    let page = || async {
        app.get_page(&device_page(id, Section::Summary), &cookie)
            .await
            .text()
    };
    let report = json!({ "lock_reason": "NONE", "kiosk_engaged": true });
    let status = app
        .request(
            Method::POST,
            "/api/devices/status",
            Some(&token),
            Some(report.clone()),
        )
        .await;
    assert_eq!(status.status, StatusCode::NO_CONTENT);
    // A report hours before the shutdown (within the same day): the line shows.
    sqlx::query("UPDATE device_status SET reported_at = datetime('now', '-3 hours')")
        .execute(&app.db)
        .await
        .unwrap();
    let shutdown = Utc::now() - Duration::minutes(30);
    post(&app, &token, json!({ "off": off(shutdown, 41, json!({})) })).await;
    sqlx::query("UPDATE parent_events SET created_at = datetime('now', '-6 minutes')")
        .execute(&app.db)
        .await
        .unwrap();
    assert!(page().await.contains("data-power-off"));
    // The phone reports again, with no start: it is on.
    let status = app
        .request(
            Method::POST,
            "/api/devices/status",
            Some(&token),
            Some(report),
        )
        .await;
    assert_eq!(status.status, StatusCode::NO_CONTENT);
    assert!(!page().await.contains("data-power-off"));
}

/// qa-38-code nit 3: two shutdowns before a start got through - the newest off is kept and told,
/// the older's held push dropped (no line in the inbox), so only one "turned off" goes.
#[tokio::test]
async fn a_newer_shutdown_supersedes_an_older_held_push() {
    let (app, id, token) = setup().await;
    let now = Utc::now();
    post(
        &app,
        &token,
        json!({ "off": off(now - Duration::minutes(3), 41, json!({})) }),
    )
    .await;
    post(
        &app,
        &token,
        json!({ "off": off(now - Duration::seconds(10), 42, json!({})) }),
    )
    .await;
    assert_eq!(events(&app, id).await.len(), 2);
    let held = deliveries(&app, "phone_off").await;
    assert_eq!(
        (held[0].0.as_str(), held[0].1.as_deref()),
        ("dropped", Some("superseded"))
    );
    assert_eq!(held[1].0, "queued");
    assert_eq!(
        send_at(&app, now + Duration::minutes(6)).await,
        ["Anna was turned off"]
    );
    // The older off arriving again (resent) supersedes nothing.
    post(
        &app,
        &token,
        json!({ "off": off(now - Duration::minutes(3), 41, json!({})) }),
    )
    .await;
    assert_eq!(deliveries(&app, "phone_off").await[1].0, "accepted");
    let cookie = app.admin_cookie().await;
    let inbox = app.get_page("/notifications", &cookie).await.text();
    assert_eq!(delivery_lines(&inbox), ["Delivered to iPhone · Safari"]);
}

/// qa-38-design N4: the sender claims a row before its request - one dropped since the pass read
/// it is not sent.
#[tokio::test]
async fn the_sender_claims_only_a_queued_row() {
    let (app, _, token) = setup().await;
    post(
        &app,
        &token,
        json!({ "off": off(Utc::now(), 41, json!({})) }),
    )
    .await;
    let id: i64 = sqlx::query_scalar("SELECT id FROM push_deliveries")
        .fetch_one(&app.db)
        .await
        .unwrap();
    assert!(crate::push::claim(&app.db, id, b"hash").await.unwrap());
    sqlx::query("UPDATE push_deliveries SET status = 'dropped', reason = 'restart'")
        .execute(&app.db)
        .await
        .unwrap();
    assert!(!crate::push::claim(&app.db, id, b"hash").await.unwrap());
    let attempts: i64 = sqlx::query_scalar("SELECT attempts FROM push_deliveries")
        .fetch_one(&app.db)
        .await
        .unwrap();
    assert_eq!(attempts, 1, "the refused claim counted nothing");
}
