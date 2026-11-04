//! Weather on the lock glance (design 75 §7.2): the two device routes through the router with canned MET and Nowcast
//! answers (no network), the cache rules, the Location Off erase and the age prune, the logs, and
//! Device › Weather's page and saves.

use axum::http::{Method, StatusCode, header};
use chrono::{DateTime, Duration, SecondsFormat, Utc};
use serde_json::{Value, json};

use super::music::Canned;
use super::{TestApp, TestResponse, assert_redirect};

const MET: &str = "https://api.met.no/weatherapi/locationforecast/2.0/complete?lat=59.94&lon=10.76";
const NOWCAST: &str = "https://api.met.no/weatherapi/nowcast/2.0/complete?lat=59.94&lon=10.76";
const MODIFIED: &str = "Tue, 03 Nov 2026 13:58:32 GMT";

/// 15:20 in Oslo on the recorded day.
fn now() -> DateTime<Utc> {
    DateTime::parse_from_rfc3339("2026-11-03T14:20:00Z")
        .unwrap()
        .with_timezone(&Utc)
}

fn fixture(name: &str) -> Vec<u8> {
    std::fs::read(format!("testdata/weather/{name}")).unwrap()
}

/// MET's recorded forecast, `Expires` 29.5 min after `Date` (as recorded).
fn met_ok(date: &str, expires: &str) -> Canned {
    Canned {
        status: 200,
        body: fixture("met_complete.json"),
        last_modified: Some(MODIFIED.to_string()),
        date: Some(date.to_string()),
        expires: Some(expires.to_string()),
        ..Default::default()
    }
}

/// The canned outside world: MET's forecast and nowcast for the Oslo cell.
fn outside(app: &TestApp) {
    app.fetch.canned(
        MET,
        met_ok(
            "Tue, 03 Nov 2026 14:00:22 GMT",
            "Tue, 03 Nov 2026 14:29:48 GMT",
        ),
    );
    app.fetch.answer(NOWCAST, 200, fixture("met_nowcast.json"));
}

/// An enrolled phone with the switch and the location mode.
async fn phone(app: &TestApp, name: &str, on: bool, mode: &str) -> (i64, String) {
    let (id, token) = app.enrolled_device(name).await;
    sqlx::query("UPDATE device_policy SET lock_weather = ?, location_mode = ? WHERE device_id = ?")
        .bind(on)
        .bind(mode)
        .bind(id)
        .execute(&app.db)
        .await
        .unwrap();
    (id, token)
}

/// A stored fix `age` before the weather clock (`mock` as stored).
async fn fix(app: &TestApp, id: i64, lat: f64, lon: f64, age: Duration, mock: Option<i64>) {
    sqlx::query(
        "INSERT INTO device_locations (device_id, latitude, longitude, accuracy_meters, captured_at, mock) \
         VALUES (?, ?, ?, 20, ?, ?)",
    )
    .bind(id)
    .bind(lat)
    .bind(lon)
    .bind((now() - age).to_rfc3339_opts(SecondsFormat::Millis, true))
    .bind(mock)
    .execute(&app.db)
    .await
    .unwrap();
}

async fn ask(app: &TestApp, token: &str, path: &str) -> TestResponse {
    app.request(Method::GET, path, Some(token), None).await
}

async fn weather(app: &TestApp, token: &str) -> Value {
    let res = ask(app, token, "/api/devices/weather").await;
    assert_eq!(res.status, StatusCode::OK, "{}", res.text());
    assert_eq!(res.headers.get(header::CACHE_CONTROL).unwrap(), "no-store");
    res.json()
}

async fn nowcast(app: &TestApp, token: &str) -> Value {
    let res = ask(app, token, "/api/devices/weather/now").await;
    assert_eq!(res.status, StatusCode::OK, "{}", res.text());
    res.json()
}

fn met_asks(app: &TestApp) -> usize {
    app.fetch
        .calls
        .lock()
        .unwrap()
        .iter()
        .filter(|url| url.starts_with("https://api.met.no/"))
        .count()
}

async fn cell_row(app: &TestApp) -> (Option<String>, Option<String>, i64, Option<String>) {
    sqlx::query_as(
        "SELECT checked_at, next_fetch_at, failures, last_error FROM weather_cells \
         WHERE lat_i = 2997 AND lon_i = 269",
    )
    .fetch_one(&app.db)
    .await
    .unwrap()
}

fn at(text: &Option<String>) -> DateTime<Utc> {
    DateTime::parse_from_rfc3339(text.as_deref().unwrap())
        .unwrap()
        .with_timezone(&Utc)
}

#[tokio::test]
async fn the_switch_off_answers_off_and_asks_nothing_outside() {
    let app = TestApp::new().await;
    app.state.weather.set_now(now());
    outside(&app);
    let (id, token) = phone(&app, "off", false, "interval").await;
    fix(&app, id, 59.9412, 10.7598, Duration::minutes(10), Some(0)).await;
    assert_eq!(
        weather(&app, &token).await,
        json!({"v": 1, "status": "off"})
    );
    assert_eq!(
        nowcast(&app, &token).await,
        json!({"v": 1, "status": "none"})
    );
    assert_eq!(app.fetch.count(), 0);
    // The policy says so, always sent (D17).
    assert_eq!(
        app.policy(&token).await["launcher_ui"]["lock_weather"],
        json!(false)
    );
}

/// D5's first source and D7's cache: one MET request with MET's User-Agent for a cell, shared by a second phone in
/// it; the answer carries no place, and nothing but MET is asked (2026-11-04: the place-name lookup went).
#[tokio::test]
async fn a_fresh_fix_is_one_met_request_shared_by_the_cell() {
    let app = TestApp::new().await;
    app.state.weather.set_now(now());
    outside(&app);
    let (id, token) = phone(&app, "a", true, "interval").await;
    fix(&app, id, 59.9412, 10.7598, Duration::hours(5), None).await;
    assert_eq!(
        app.policy(&token).await["launcher_ui"]["lock_weather"],
        json!(true)
    );
    let first = weather(&app, &token).await;
    assert_eq!(first["status"], "ok", "{first}");
    assert!(first.get("place").is_none(), "no place: {first}");
    assert_eq!(first["hours"].as_array().unwrap().len(), 4);
    assert!(
        !first.to_string().contains("59.9"),
        "no coordinates: {first}"
    );
    {
        let requests = app.fetch.requests.lock().unwrap();
        let met: Vec<_> = requests.iter().filter(|r| r.url == MET).collect();
        assert_eq!(met.len(), 1);
        assert_eq!(
            met[0].header("user-agent"),
            Some(app.state.config.met_user_agent().as_str())
        );
        assert!(
            met[0]
                .header("user-agent")
                .unwrap()
                .starts_with("vibb-server/")
        );
        assert_eq!(met[0].last_modified, None);
    }
    let second = weather(&app, &token).await;
    assert!(second.get("place").is_none(), "{second}");
    assert_eq!(app.fetch.count(), met_asks(&app), "only MET is asked");
    let (_, other) = phone(&app, "b", true, "on_request").await;
    let other_id: i64 = sqlx::query_scalar("SELECT id FROM devices WHERE name = 'b'")
        .fetch_one(&app.db)
        .await
        .unwrap();
    let zone = zone(&app, other_id, "Hjemme", 59.9399, 10.7601).await;
    set_zone(&app, other_id, Some(zone)).await;
    assert_eq!(weather(&app, &other).await["status"], "ok");
    assert_eq!(
        met_asks(&app),
        1,
        "the cell's cache answers every phone in it"
    );
    // gzip when asked.
    let res = app
        .send(
            axum::http::Request::builder()
                .uri("/api/devices/weather")
                .header(header::AUTHORIZATION, format!("Bearer {token}"))
                .header(header::ACCEPT_ENCODING, "gzip")
                .body(axum::body::Body::empty())
                .unwrap(),
        )
        .await;
    assert_eq!(res.headers.get(header::CONTENT_ENCODING).unwrap(), "gzip");
}

async fn zone(app: &TestApp, device: i64, name: &str, lat: f64, lon: f64) -> i64 {
    sqlx::query_scalar(
        "INSERT INTO device_zones (device_id, name, latitude, longitude, radius_m) \
         VALUES (?, ?, ?, ?, 150) RETURNING id",
    )
    .bind(device)
    .bind(name)
    .bind(lat)
    .bind(lon)
    .fetch_one(&app.db)
    .await
    .unwrap()
}

async fn set_zone(app: &TestApp, device: i64, zone: Option<i64>) {
    sqlx::query("UPDATE device_policy SET weather_zone_id = ? WHERE device_id = ?")
        .bind(zone)
        .bind(device)
        .execute(&app.db)
        .await
        .unwrap();
}

/// D5: only a real fix at most 6 h old and not in the future, and only while Location is Regularly; else the
/// chosen zone (with Location On request and Off too); else nothing - and nothing asked outside.
#[tokio::test]
async fn the_position_comes_from_a_fresh_real_fix_else_the_zone() {
    let app = TestApp::new().await;
    app.state.weather.set_now(now());
    outside(&app);
    let (id, token) = phone(&app, "p", true, "on_request").await;
    fix(&app, id, 59.9412, 10.7598, Duration::minutes(5), Some(0)).await;
    assert_eq!(
        weather(&app, &token).await["status"],
        "no_position",
        "On request: no fix counts"
    );
    assert_eq!(nowcast(&app, &token).await["status"], "none");
    sqlx::query("UPDATE device_policy SET location_mode = 'interval' WHERE device_id = ?")
        .bind(id)
        .execute(&app.db)
        .await
        .unwrap();
    sqlx::query("DELETE FROM device_locations")
        .execute(&app.db)
        .await
        .unwrap();
    for (age, mock) in [
        (Duration::minutes(5), Some(1)),
        (Duration::hours(7), Some(0)),
        (Duration::minutes(-30), Some(0)),
    ] {
        fix(&app, id, 59.9412, 10.7598, age, mock).await;
        assert_eq!(
            weather(&app, &token).await["status"],
            "no_position",
            "{age:?} {mock:?}"
        );
        sqlx::query("DELETE FROM device_locations")
            .execute(&app.db)
            .await
            .unwrap();
    }
    assert_eq!(app.fetch.count(), 0, "no position, nothing asked");
    // The zone stands in, with Location On request and Off alike - its own cell.
    let home = zone(&app, id, "Hjemme", 59.9399, 10.7601).await;
    set_zone(&app, id, Some(home)).await;
    for mode in ["on_request", "off", "interval"] {
        sqlx::query("UPDATE device_policy SET location_mode = ? WHERE device_id = ?")
            .bind(mode)
            .bind(id)
            .execute(&app.db)
            .await
            .unwrap();
        assert_eq!(weather(&app, &token).await["status"], "ok", "{mode}");
    }
    // A fresh fix wins over the zone: another cell.
    fix(&app, id, 63.4305, 10.3951, Duration::minutes(5), None).await;
    let trondheim =
        "https://api.met.no/weatherapi/locationforecast/2.0/complete?lat=63.44&lon=10.40";
    weather(&app, &token).await;
    assert!(
        app.fetch
            .calls
            .lock()
            .unwrap()
            .iter()
            .any(|u| u == trondheim)
    );
}

/// D7: past `Expires` the fetch is conditional on the stored `Last-Modified`, a 304 moves `checked_at`; the next fetch
/// is `Expires − Date` from now (a skewed `Date` changes nothing), never before 30 min; a 203 is served.
#[tokio::test]
async fn the_cache_follows_mets_rules() {
    let app = TestApp::new().await;
    app.state.weather.set_now(now());
    outside(&app);
    // MET's clock is a year off: its `Expires` is 45 min after its `Date`.
    app.fetch.canned(
        MET,
        met_ok(
            "Mon, 03 Nov 2025 10:00:00 GMT",
            "Mon, 03 Nov 2025 10:45:00 GMT",
        ),
    );
    let (id, token) = phone(&app, "c", true, "interval").await;
    fix(&app, id, 59.9412, 10.7598, Duration::minutes(5), None).await;
    assert_eq!(weather(&app, &token).await["status"], "ok");
    let (checked, next, _, _) = cell_row(&app).await;
    assert_eq!(at(&checked), now());
    let next = at(&next);
    assert!(
        next >= now() + Duration::minutes(45) && next <= now() + Duration::minutes(47),
        "{next}"
    );
    // Before `next_fetch_at` nothing is asked.
    app.state.weather.set_now(now() + Duration::minutes(40));
    weather(&app, &token).await;
    assert_eq!(met_asks(&app), 1);
    // Past it: conditional, and MET's 304 moves `checked_at`.
    let later = now() + Duration::minutes(48);
    app.state.weather.set_now(later);
    app.fetch.canned_once(
        MET,
        Canned {
            status: 304,
            ..Default::default()
        },
    );
    assert_eq!(weather(&app, &token).await["status"], "ok");
    assert_eq!(
        app.fetch
            .requests
            .lock()
            .unwrap()
            .last()
            .unwrap()
            .last_modified
            .as_deref(),
        Some(MODIFIED)
    );
    let (checked, next, _, _) = cell_row(&app).await;
    assert_eq!(at(&checked), later);
    // No `Expires`: the 30-minute floor.
    assert!(
        at(&next) >= later + Duration::minutes(30) && at(&next) <= later + Duration::minutes(32)
    );
    // A 203 (deprecated) is served like a 200.
    let later = later + Duration::minutes(33);
    app.state.weather.set_now(later);
    app.fetch.canned_once(
        MET,
        Canned {
            status: 203,
            ..met_ok(
                "Tue, 03 Nov 2026 15:00:00 GMT",
                "Tue, 03 Nov 2026 15:30:00 GMT",
            )
        },
    );
    let answer = weather(&app, &token).await;
    assert_eq!(answer["status"], "ok");
    assert_eq!(answer["checked_at_ms"], later.timestamp_millis());
}

/// D7: a 429 backs off (`Retry-After` as seconds or a date, at least 10 min) and the old hours are served; a 403
/// waits 6 h; a 5xx with no hours yet is `unavailable`.
#[tokio::test]
async fn failures_back_off_and_keep_the_old_hours() {
    let app = TestApp::new().await;
    app.state.weather.set_now(now());
    outside(&app);
    let (id, token) = phone(&app, "f", true, "interval").await;
    fix(&app, id, 59.9412, 10.7598, Duration::minutes(5), None).await;
    // No hours yet and MET failing: three quick tries (a 503, a 502, a 503), then the old backoff.
    for status in [503, 502, 503] {
        app.fetch.canned_once(
            MET,
            Canned {
                status,
                ..Default::default()
            },
        );
    }
    assert_eq!(weather(&app, &token).await["status"], "unavailable");
    let (_, next, failures, error) = cell_row(&app).await;
    assert_eq!((failures, error.as_deref()), (1, Some("status_5xx")));
    assert_eq!(at(&next), now() + Duration::minutes(5));
    assert_eq!(met_asks(&app), 3);
    // The next try fills it.
    let mut t = now() + Duration::minutes(6);
    app.state.weather.set_now(t);
    assert_eq!(weather(&app, &token).await["status"], "ok");
    for (retry, wait) in [
        ("60", Duration::minutes(10)),
        ("Tue, 03 Nov 2026 16:00:00 GMT", Duration::minutes(30)),
    ] {
        t += Duration::minutes(50);
        app.state.weather.set_now(t);
        app.fetch.canned_once(
            MET,
            Canned {
                status: 429,
                retry_after: Some(retry.to_string()),
                date: Some("Tue, 03 Nov 2026 15:30:00 GMT".to_string()),
                ..Default::default()
            },
        );
        let answer = weather(&app, &token).await;
        assert_eq!(answer["status"], "ok", "the old hours are served: {answer}");
        let (_, next, _, error) = cell_row(&app).await;
        assert_eq!(error.as_deref(), Some("status_429"));
        assert!(at(&next) >= t + wait, "{retry}: {next:?}");
    }
    t = at(&cell_row(&app).await.1);
    app.state.weather.set_now(t);
    app.fetch.canned_once(
        MET,
        Canned {
            status: 403,
            ..Default::default()
        },
    );
    weather(&app, &token).await;
    let (_, next, _, error) = cell_row(&app).await;
    assert_eq!(error.as_deref(), Some("status_403"));
    assert_eq!(at(&next), t + Duration::hours(6));
    // A 200 we can't read waits at least until the `Expires` it came with (code QA M2), 45 min here.
    let t = at(&next);
    app.state.weather.set_now(t);
    // Six hours on, the phone's fix is fresh again.
    sqlx::query("UPDATE device_locations SET captured_at = ? WHERE device_id = ?")
        .bind((t - Duration::minutes(5)).to_rfc3339_opts(SecondsFormat::Millis, true))
        .bind(id)
        .execute(&app.db)
        .await
        .unwrap();
    app.fetch.canned_once(
        MET,
        Canned {
            status: 200,
            body: b"<html>".to_vec(),
            date: Some("Tue, 03 Nov 2026 22:00:00 GMT".to_string()),
            expires: Some("Tue, 03 Nov 2026 22:45:00 GMT".to_string()),
            ..Default::default()
        },
    );
    assert_eq!(weather(&app, &token).await["status"], "ok", "the old hours");
    let (_, next, _, error) = cell_row(&app).await;
    assert_eq!(error.as_deref(), Some("unreadable"));
    assert!(at(&next) >= t + Duration::minutes(45), "{next:?}");
}

/// 2026-11-04: MET's edge answers a cold point 503 (`Retry-After: 5`), then serves it. The fetch tries again within
/// seconds and stores the forecast as any 200 - no failure, no error code, the next fetch by MET's `Expires`. A
/// `Retry-After` past a minute is never asked before: no quick try, the old backoff or longer.
#[tokio::test]
async fn a_503_is_tried_again_within_seconds() {
    let app = TestApp::new().await;
    app.state.weather.set_now(now());
    outside(&app);
    let (id, token) = phone(&app, "q", true, "interval").await;
    fix(&app, id, 59.9412, 10.7598, Duration::minutes(5), None).await;
    for retry_after in [Some("5"), None] {
        app.fetch.canned_once(
            MET,
            Canned {
                status: 503,
                retry_after: retry_after.map(str::to_string),
                body: b"<html>503 Backend fetch failed</html>".to_vec(),
                ..Default::default()
            },
        );
    }
    // "5 s" and "5 s" are 5 ms each here, inside the phone's wait.
    assert_eq!(weather(&app, &token).await["status"], "ok");
    assert_eq!(met_asks(&app), 3);
    let (checked, next, failures, error) = cell_row(&app).await;
    assert_eq!((failures, error), (0, None));
    assert_eq!(at(&checked), now());
    assert!(at(&next) >= now() + Duration::minutes(30), "{next:?}");

    // Due again: MET asks for ten minutes - one request, then at least that long.
    let t = at(&next);
    app.state.weather.set_now(t);
    app.fetch.canned_once(
        MET,
        Canned {
            status: 503,
            retry_after: Some("600".to_string()),
            ..Default::default()
        },
    );
    assert_eq!(weather(&app, &token).await["status"], "ok", "the old hours");
    assert_eq!(met_asks(&app), 4);
    let (_, next, failures, error) = cell_row(&app).await;
    assert_eq!((failures, error.as_deref()), (1, Some("status_5xx")));
    assert_eq!(at(&next), t + Duration::minutes(10));
}

/// E9 (2026-11-04): MET served a 200 cut short, from its cache until its `Expires`. Its whole hours are stored
/// (`cut`) and the cell is asked again only after that `Expires` - never a quick retry.
#[tokio::test]
async fn a_cut_body_keeps_its_whole_hours_until_expires() {
    let app = TestApp::new().await;
    app.state.weather.set_now(now());
    let (id, token) = phone(&app, "c", true, "interval").await;
    fix(&app, id, 59.9412, 10.7598, Duration::minutes(5), None).await;
    let mut cut = met_ok(
        "Tue, 03 Nov 2026 14:00:22 GMT",
        "Tue, 03 Nov 2026 14:59:22 GMT",
    );
    cut.body.truncate(cut.body.len() * 38 / 65);
    app.fetch.canned(MET, cut);
    assert_eq!(weather(&app, &token).await["status"], "ok");
    assert_eq!(met_asks(&app), 1);
    let (checked, next, failures, error) = cell_row(&app).await;
    assert_eq!((failures, error.as_deref()), (0, Some("cut")));
    assert_eq!(at(&checked), now());
    assert!(at(&next) >= now() + Duration::minutes(59), "{next:?}");
}

/// D7: a phone waits at most the fetch's budget (6 s in the server, 500 ms here); the fetch carries on and fills the
/// cache for the next ask.
#[tokio::test]
async fn a_slow_fetch_answers_in_time_and_fills_the_cache_after() {
    let app = TestApp::new().await;
    app.state.weather.set_now(now());
    outside(&app);
    let (id, token) = phone(&app, "s", true, "interval").await;
    fix(&app, id, 59.9412, 10.7598, Duration::minutes(5), None).await;
    app.fetch.delay(1500);
    let started = std::time::Instant::now();
    assert_eq!(weather(&app, &token).await["status"], "unavailable");
    assert!(started.elapsed() < std::time::Duration::from_millis(1400));
    for _ in 0..400 {
        if cell_row(&app).await.0.is_some() {
            break;
        }
        tokio::time::sleep(std::time::Duration::from_millis(10)).await;
    }
    app.fetch.delay(0);
    assert_eq!(weather(&app, &token).await["status"], "ok");
    assert_eq!(met_asks(&app), 1);
}

/// D9: the card's line from Nowcast at the weather clock's now; no coverage, dry or outside the Nordic area is
/// silence (outside is asked once a day at most).
#[tokio::test]
async fn the_nowcast_line_and_its_silences() {
    let app = TestApp::new().await;
    app.state.weather.set_now(now());
    outside(&app);
    let (id, token) = phone(&app, "n", true, "interval").await;
    fix(&app, id, 59.9412, 10.7598, Duration::minutes(5), None).await;
    // The recorded nowcast is dry.
    assert_eq!(
        nowcast(&app, &token).await,
        json!({"v": 1, "status": "ok", "line": null})
    );
    // Rain in 25 minutes, read from a fetch 3 min later.
    let steps: Vec<Value> = (0..23)
        .map(|i| {
            json!({"time": (now() + Duration::minutes(3 + 5 * i)).to_rfc3339_opts(SecondsFormat::Secs, true),
                   "data": {"instant": {"details": {"precipitation_rate": if i >= 5 { 1.2 } else { 0.0 },
                            "air_temperature": 4.0}}}})
        })
        .collect();
    let body = |coverage: &str| {
        json!({"properties": {"meta": {"radar_coverage": coverage}, "timeseries": steps}})
            .to_string()
    };
    app.state.weather.set_now(now() + Duration::minutes(4));
    app.fetch.answer_once(NOWCAST, 200, body("ok"));
    assert_eq!(
        nowcast(&app, &token).await,
        json!({"v": 1, "status": "ok", "line": {"key": "rain_in", "minutes": 25}})
    );
    // Within its reuse window nothing is asked again; the line counts from now.
    let asked = app.fetch.count();
    app.state.weather.set_now(now() + Duration::minutes(6));
    assert_eq!(nowcast(&app, &token).await["line"]["minutes"], 20);
    assert_eq!(app.fetch.count(), asked);
    app.state.weather.set_now(now() + Duration::minutes(10));
    app.fetch.answer_once(NOWCAST, 200, body("no coverage"));
    assert_eq!(nowcast(&app, &token).await["line"], Value::Null);
    // Outside the Nordic area: MET's 422, then silence without an ask.
    app.state.weather.set_now(now() + Duration::minutes(20));
    app.fetch.answer_once(NOWCAST, 422, "outside");
    assert_eq!(nowcast(&app, &token).await["line"], Value::Null);
    let asked = app.fetch.count();
    app.state.weather.set_now(now() + Duration::hours(3));
    assert_eq!(nowcast(&app, &token).await["line"], Value::Null);
    assert_eq!(app.fetch.count(), asked);
    assert_eq!(app.state.weather.nowcast_cells(), 1);
}

#[tokio::test]
async fn a_phones_asks_are_limited() {
    let app = TestApp::new().await;
    let (_, token) = phone(&app, "l", false, "interval").await;
    for _ in 0..crate::weather_cache::FORECAST_ASKS_AN_HOUR {
        assert_eq!(
            ask(&app, &token, "/api/devices/weather").await.status,
            StatusCode::OK
        );
    }
    let refused = ask(&app, &token, "/api/devices/weather").await;
    assert_eq!(refused.status, StatusCode::TOO_MANY_REQUESTS);
    assert!(refused.headers.get(header::RETRY_AFTER).is_some());
    for _ in 0..crate::weather_cache::NOWCAST_ASKS_AN_HOUR {
        assert_eq!(
            ask(&app, &token, "/api/devices/weather/now").await.status,
            StatusCode::OK
        );
    }
    assert_eq!(
        ask(&app, &token, "/api/devices/weather/now").await.status,
        StatusCode::TOO_MANY_REQUESTS
    );
    // Without a token, nothing.
    assert_eq!(
        app.request(Method::GET, "/api/devices/weather", None, None)
            .await
            .status,
        StatusCode::UNAUTHORIZED
    );
}

async fn weather_rows(app: &TestApp) -> i64 {
    sqlx::query_scalar("SELECT COUNT(*) FROM weather_cells")
        .fetch_one(&app.db)
        .await
        .unwrap()
}

async fn seed_caches(app: &TestApp) {
    sqlx::query(
        "INSERT INTO weather_cells (lat_i, lon_i, asked_at) VALUES (2997, 269, ?) \
         ON CONFLICT DO NOTHING",
    )
    .bind(Utc::now().to_rfc3339_opts(SecondsFormat::Secs, true))
    .execute(&app.db)
    .await
    .unwrap();
}

/// D13 (server QA H1): a change of a phone's Location to Off erases every weather cache at once; the hourly prune
/// with a phone already Off doesn't.
#[tokio::test]
async fn location_off_erases_the_caches_and_the_prune_does_not() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, _) = phone(&app, "o", true, "interval").await;
    seed_caches(&app).await;
    let res = app
        .request_form(
            Method::POST,
            &format!("/devices/{id}/location-policy"),
            Some(&cookie),
            &[("mode", "off")],
        )
        .await;
    assert!(res.status.is_redirection(), "{}", res.text());
    assert_eq!(weather_rows(&app).await, 0);
    // Already Off: neither a save of Off again nor the prune erases.
    seed_caches(&app).await;
    app.request_form(
        Method::POST,
        &format!("/devices/{id}/location-policy"),
        Some(&cookie),
        &[("mode", "off")],
    )
    .await;
    crate::retention::prune(&app.db).await;
    assert_eq!(weather_rows(&app).await, 1);
}

/// §3.1's retention, by age only: a cell nobody asked for in 48 h.
#[tokio::test]
async fn the_prune_drops_what_nobody_asked_for() {
    let app = TestApp::new().await;
    let (id, _) = phone(&app, "r", true, "interval").await;
    sqlx::query("UPDATE device_policy SET location_retention_days = 14 WHERE device_id = ?")
        .bind(id)
        .execute(&app.db)
        .await
        .unwrap();
    let ago = |hours: i64| {
        (Utc::now() - Duration::hours(hours)).to_rfc3339_opts(SecondsFormat::Secs, true)
    };
    for (lat, hours) in [(1, 47), (2, 49)] {
        sqlx::query("INSERT INTO weather_cells (lat_i, lon_i, asked_at) VALUES (?, 0, ?)")
            .bind(lat)
            .bind(ago(hours))
            .execute(&app.db)
            .await
            .unwrap();
    }
    assert_eq!(crate::retention::prune(&app.db).await.weather, 1);
    let left: Vec<i64> = sqlx::query_scalar("SELECT lat_i FROM weather_cells")
        .fetch_all(&app.db)
        .await
        .unwrap();
    assert_eq!(left, [1]);
}

/// Server QA L10: a failed fetch's log line and `last_error` name the cell's row and a code - never a coordinate, a
/// URL or a body. The stored code is checked here; every log macro of the weather's modules is read for what it
/// prints (a test can't capture another task's events reliably while the other tests run).
#[tokio::test]
async fn a_failed_fetch_keeps_only_a_code() {
    let app = TestApp::new().await;
    app.state.weather.set_now(now());
    let (id, token) = phone(&app, "g", true, "interval").await;
    fix(&app, id, 59.9412, 10.7598, Duration::minutes(5), None).await;
    app.fetch.fail(
        MET,
        crate::music_net::FetchError::Network(
            "error sending request for url (https://api.met.no/...?lat=59.94&lon=10.76)".into(),
        ),
    );
    assert_eq!(weather(&app, &token).await["status"], "unavailable");
    assert_eq!(cell_row(&app).await.3.as_deref(), Some("network"));
    for file in [
        "src/weather.rs",
        "src/weather_cache.rs",
        "src/handlers/weather.rs",
        "src/handlers/weather_page.rs",
    ] {
        let source = std::fs::read_to_string(file).unwrap();
        for (n, _) in source.match_indices("tracing::") {
            // The whole call (code QA L4): its fields before the message, the message's own `{...}` captures and
            // any format arguments after it are what a log prints besides its fixed words.
            let call = &source[n..];
            let call = &call[..call_end(call)];
            let (Some(first), Some(last)) = (call.find('"'), call.rfind('"')) else {
                panic!("{file} at byte {n}: a log without a message: {call}");
            };
            let (fields, message, tail) = (&call[..first], &call[first..=last], &call[last + 1..]);
            for word in [
                "lat",
                "lon",
                "query",
                "coordinates",
                "centre",
                "url",
                "body",
                "message",
                "name",
            ] {
                assert!(
                    !fields.contains(word),
                    "{file} at byte {n}: a log may print {word:?}: {call}"
                );
            }
            assert!(
                !message.contains('{'),
                "{file} at byte {n}: a log's message formats something: {call}"
            );
            assert!(
                tail.trim_matches(|c: char| c.is_whitespace() || c == ',')
                    .is_empty(),
                "{file} at byte {n}: a log formats arguments: {call}"
            );
        }
    }
}

/// Where a macro call that starts `text` ends: its closing parenthesis, counted past the ones in its strings.
fn call_end(text: &str) -> usize {
    let (mut depth, mut in_string, mut escaped) = (0, false, false);
    for (i, c) in text.char_indices() {
        match c {
            _ if escaped => escaped = false,
            '\\' if in_string => escaped = true,
            '"' => in_string = !in_string,
            '(' if !in_string => depth += 1,
            ')' if !in_string => {
                depth -= 1;
                if depth == 0 {
                    return i;
                }
            }
            _ => {}
        }
    }
    text.len()
}

/// Code QA L5: two phones of one cell asking at once make one MET request.
#[tokio::test]
async fn two_asks_at_once_make_one_fetch() {
    let app = TestApp::new().await;
    app.state.weather.set_now(now());
    outside(&app);
    let (a, first) = phone(&app, "a", true, "interval").await;
    let (b, second) = phone(&app, "b", true, "interval").await;
    fix(&app, a, 59.9412, 10.7598, Duration::minutes(5), None).await;
    fix(&app, b, 59.9399, 10.7601, Duration::minutes(5), None).await;
    app.fetch.delay(300);
    let (one, two) = tokio::join!(weather(&app, &first), weather(&app, &second));
    assert_eq!(
        (one["status"].as_str(), two["status"].as_str()),
        (Some("ok"), Some("ok"))
    );
    assert_eq!(met_asks(&app), 1);
}

/// Code QA L1: what was in flight when Location went Off writes nothing back - a nowcast fill, a forecast ask (which
/// answers `unavailable`, never a 500).
#[tokio::test]
async fn an_erase_keeps_what_was_in_flight_from_writing_back() {
    let app = TestApp::new().await;
    app.state.weather.set_now(now());
    outside(&app);
    let state = app.state.clone();
    let cell = crate::weather::Cell::of(59.94, 10.76).unwrap();
    // A forecast ask, then a nowcast fill, each held at MET while the erase runs.
    app.fetch.hold();
    let forecast_task = tokio::spawn({
        let state = state.clone();
        async move { crate::weather_cache::forecast(&state, cell).await }
    });
    app.fetch.wait_in_flight().await;
    crate::weather_cache::erase(&state).await.unwrap();
    app.fetch.release();
    assert!(
        forecast_task.await.unwrap().unwrap().is_none(),
        "the row went: unavailable, not a 500"
    );
    app.fetch.hold();
    let now_task = tokio::spawn({
        let state = state.clone();
        async move { crate::weather_cache::nowcast(&state, cell).await }
    });
    app.fetch.wait_in_flight().await;
    crate::weather_cache::erase(&state).await.unwrap();
    app.fetch.release();
    assert_eq!(now_task.await.unwrap(), None);
    assert_eq!(state.weather.nowcast_cells(), 0);
}

/// Code QA L3: with every phone's location history at one day, a cell goes after a day, not two.
#[tokio::test]
async fn a_one_day_history_prunes_cells_after_a_day() {
    let app = TestApp::new().await;
    let (id, _) = phone(&app, "d", true, "interval").await;
    sqlx::query("UPDATE device_policy SET location_retention_days = 1 WHERE device_id = ?")
        .bind(id)
        .execute(&app.db)
        .await
        .unwrap();
    for (lat, hours) in [(1, 23), (2, 25)] {
        sqlx::query("INSERT INTO weather_cells (lat_i, lon_i, asked_at) VALUES (?, 0, ?)")
            .bind(lat)
            .bind((Utc::now() - Duration::hours(hours)).to_rfc3339_opts(SecondsFormat::Secs, true))
            .execute(&app.db)
            .await
            .unwrap();
    }
    crate::retention::prune(&app.db).await;
    let left: Vec<i64> = sqlx::query_scalar("SELECT lat_i FROM weather_cells")
        .fetch_all(&app.db)
        .await
        .unwrap();
    assert_eq!(left, [1]);
}

// ---- Lock Screen & PINs › Weather ----------------------------------------------------------------

/// Lock Screen & PINs, where the weather's group is (design 75 §6, the user's move of 2026-11-03).
fn page(id: i64) -> String {
    format!("/devices/{id}/lock")
}

/// The page's Weather group, `<section id="weather">` to its end.
fn group(html: &str) -> String {
    let start = html
        .find(r#"<section id="weather""#)
        .expect("the Weather group");
    let end = html[start..].find("</section>").unwrap() + start;
    html[start..end].to_string()
}

async fn log_lines(app: &TestApp, kind: &str) -> Vec<String> {
    super::security_words(app, kind, crate::i18n::Lang::En).await
}

/// §6: the lock page's Weather group - the switch (off by default) and the zone row (while on), each save on its own
/// anchor of the lock page, logged once per change, the phone nudged; the old page's address leads to the group, and
/// the phone's page has no row of its own for it any more.
#[tokio::test]
async fn the_weather_group_saves_on_its_anchors() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("phone").await;
    let old = app
        .get_page(&format!("/devices/{id}/weather"), &cookie)
        .await;
    assert_redirect(&old, &page(id), Some("weather"));
    app.assert_lands(&old, &cookie).await;
    let html = group(&app.get_page(&page(id), &cookie).await.text());
    assert!(html.contains("Weather on the lock screen"), "{html}");
    assert_eq!(super::input_checked(&html, "on", "on"), Some(false));
    assert!(super::autosaves(&html, "on"));
    assert!(
        !html.contains(r#"id="weather-zone""#),
        "the zone row waits for the switch"
    );
    let summary = app
        .get_page(&format!("/devices/{id}"), &cookie)
        .await
        .text();
    assert!(
        !summary.contains(r#"data-row="weather""#),
        "no Weather row of its own"
    );

    let mut nudges = app.state.command_notify.subscribe();
    for on in [true, true, false, true] {
        let fields: &[(&str, &str)] = if on { &[("on", "on")] } else { &[] };
        let res = app
            .request_form(
                Method::POST,
                &format!("/devices/{id}/weather/lock"),
                Some(&cookie),
                fields,
            )
            .await;
        assert_redirect(&res, &page(id), Some("weather-lock"));
        app.assert_lands(&res, &cookie).await;
        assert_eq!(nudges.try_recv().ok(), Some(id));
        assert_eq!(
            app.policy(&token).await["launcher_ui"]["lock_weather"],
            json!(on)
        );
    }
    assert_eq!(
        log_lines(&app, "lock_weather_changed").await,
        ["on", "off", "on"].map(|s| format!("phone {id}: weather on the lock screen: {s}"))
    );

    // No zones yet: the row says so and links to Locate's zones.
    let html = group(&app.get_page(&page(id), &cookie).await.text());
    assert!(html.contains("This phone has no zones yet."), "{html}");
    assert!(html.contains(&format!("/devices/locate?device={id}#zones")));
    let home = zone(&app, id, "Hjemme", 59.9399, 10.7601).await;
    let html = group(&app.get_page(&page(id), &cookie).await.text());
    assert!(
        html.contains(r#"<option value="" selected>None</option>"#),
        "{html}"
    );
    assert!(super::autosaves(&html, "zone"));
    for (value, line) in [
        (
            home.to_string(),
            "phone ID: lock screen weather zone: Hjemme",
        ),
        (String::new(), "phone ID: lock screen weather zone: none"),
    ] {
        let res = app
            .request_form(
                Method::POST,
                &format!("/devices/{id}/weather/zone"),
                Some(&cookie),
                &[("zone", &value)],
            )
            .await;
        assert_redirect(&res, &page(id), Some("weather-zone"));
        app.assert_lands(&res, &cookie).await;
        assert_eq!(nudges.try_recv().ok(), Some(id));
        assert_eq!(
            log_lines(&app, "lock_weather_zone_changed")
                .await
                .last()
                .unwrap(),
            &line.replace("ID", &id.to_string())
        );
    }
    // A zone of another phone, or none at all: 400, nothing written.
    let (other, _) = app.enrolled_device("other").await;
    let theirs = zone(&app, other, "Skolen", 59.95, 10.77).await;
    for value in [theirs.to_string(), "9999".to_string(), "x".to_string()] {
        let res = app
            .request_form(
                Method::POST,
                &format!("/devices/{id}/weather/zone"),
                Some(&cookie),
                &[("zone", &value)],
            )
            .await;
        assert_eq!(res.status, StatusCode::BAD_REQUEST, "{value}");
    }
    let stored: Option<i64> =
        sqlx::query_scalar("SELECT weather_zone_id FROM device_policy WHERE device_id = ?")
            .bind(id)
            .fetch_one(&app.db)
            .await
            .unwrap();
    assert_eq!(stored, None);
    // An unknown phone, and a POST without a session: nothing written.
    let res = app
        .request_form(
            Method::POST,
            "/devices/999/weather/lock",
            Some(&cookie),
            &[("on", "on")],
        )
        .await;
    assert_eq!(res.status, StatusCode::NOT_FOUND);
    let res = app
        .request_form(
            Method::POST,
            &format!("/devices/{other}/weather/lock"),
            None,
            &[("on", "on")],
        )
        .await;
    assert!(res.status.is_redirection() || res.status.is_client_error());
    let theirs_on: bool =
        sqlx::query_scalar("SELECT lock_weather FROM device_policy WHERE device_id = ?")
            .bind(other)
            .fetch_one(&app.db)
            .await
            .unwrap();
    assert!(!theirs_on);
}

/// §6's notes: no fresh position and no zone, and a Vibb One that doesn't show weather yet.
#[tokio::test]
async fn the_page_says_when_the_lock_shows_no_weather() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = phone(&app, "n", true, "on_request").await;
    let html = group(&app.get_page(&page(id), &cookie).await.text());
    assert!(html.contains(r#"data-warning="needs-position""#), "{html}");
    assert!(html.contains(r#"data-warning="needs-update""#), "{html}");
    app.post_status(
        &token,
        json!({"capabilities": [crate::handlers::weather_page::CAPABILITY]}),
    )
    .await;
    let home = zone(&app, id, "Hjemme", 59.9399, 10.7601).await;
    set_zone(&app, id, Some(home)).await;
    let html = group(&app.get_page(&page(id), &cookie).await.text());
    assert!(!html.contains("data-warning"), "{html}");
    assert!(html.contains(&format!(
        r#"<option value="{home}" selected>Hjemme</option>"#
    )));
    // Regularly needs no zone.
    set_zone(&app, id, None).await;
    sqlx::query("UPDATE device_policy SET location_mode = 'interval' WHERE device_id = ?")
        .bind(id)
        .execute(&app.db)
        .await
        .unwrap();
    let html = group(&app.get_page(&page(id), &cookie).await.text());
    assert!(!html.contains("needs-position"), "{html}");
    // In Norwegian.
    let html = app
        .get_page(&format!("{}?lang=nb", page(id)), &cookie)
        .await
        .text();
    assert!(html.contains("Vær på låseskjermen"), "{html}");
}

/// §3.1 (server QA R1): a zone's delete clears the phone's weather zone through the foreign key and nudges the phone.
#[tokio::test]
async fn deleting_the_weather_zone_clears_it_and_nudges_the_phone() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, _) = phone(&app, "z", true, "on_request").await;
    let home = zone(&app, id, "Hjemme", 59.9399, 10.7601).await;
    set_zone(&app, id, Some(home)).await;
    let mut nudges = app.state.command_notify.subscribe();
    let res = app
        .request_form(
            Method::POST,
            &format!("/devices/{id}/zones/{home}/delete"),
            Some(&cookie),
            &[],
        )
        .await;
    assert!(res.status.is_redirection(), "{}", res.text());
    assert_eq!(nudges.try_recv().ok(), Some(id));
    let stored: Option<i64> =
        sqlx::query_scalar("SELECT weather_zone_id FROM device_policy WHERE device_id = ?")
            .bind(id)
            .fetch_one(&app.db)
            .await
            .unwrap();
    assert_eq!(stored, None);
}
