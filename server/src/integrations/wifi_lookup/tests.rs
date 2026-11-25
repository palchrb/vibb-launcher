//! Design 125 step 2's tests: the shared contract (`contracts/integrations/wifi_lookup/contract.json`, which the
//! launcher runs too), the server's own rows (`testdata/wifi_lookup/server.json`, written by `scripts/wifi-lookup-fixtures.py`) and
//! the flows through the real routes. No test reaches Apple: every call goes to the canned fetch or a local fake
//! SOCKS server.

use axum::body::Body;
use axum::http::{Method, Request, StatusCode, header};
use serde_json::{Value, json};
use tokio::time::Instant;

use super::cache::{self, CacheKey, Cached};
use super::cells;
use super::codec;
use super::route::{self, APPLE_URL, Failure, Pauses};
use super::{Proxy, WifiLookup};
use crate::integration_health::{self, Class, Title};
use crate::integrations::{Integration, Prune};
use crate::tests::TestApp;

fn read(path: impl AsRef<std::path::Path>) -> Value {
    serde_json::from_str(&std::fs::read_to_string(path).unwrap()).unwrap()
}

fn contract() -> Value {
    read(crate::contracts::path(
        "integrations/wifi_lookup/contract.json",
    ))
}

fn server_rows() -> Value {
    read("testdata/wifi_lookup/server.json")
}

/// A fake Apple answer of `server.json` by name.
fn apple(name: &str) -> Vec<u8> {
    let rows = server_rows();
    let row = rows["apple"]
        .as_array()
        .unwrap()
        .iter()
        .find(|r| r["name"] == name)
        .unwrap_or_else(|| panic!("no apple row {name}"))
        .clone();
    hex::decode(row["hex"].as_str().unwrap()).unwrap()
}

fn this_month() -> i64 {
    cache::month_of(chrono::Utc::now())
}

async fn phone_on(app: &TestApp) -> (i64, String) {
    let (id, token) = app.enrolled_device("phone").await;
    assert!(super::set_on(&app.state, id, true).await.unwrap());
    (id, token)
}

/// The phone's location mode, written as the Location page's save would.
async fn set_location(app: &TestApp, id: i64, mode: &str) {
    sqlx::query("UPDATE device_policy SET location_mode = ? WHERE device_id = ?")
        .bind(mode)
        .bind(id)
        .execute(&app.db)
        .await
        .unwrap();
}

/// `POST /api/devices/wifi-lookup` with `request` (a JSON string is sent as the raw body).
async fn lookup(app: &TestApp, token: &str, request: &Value) -> (StatusCode, Value) {
    let body = match request {
        Value::String(raw) => raw.clone(),
        other => other.to_string(),
    };
    let res = app
        .send(
            Request::builder()
                .method(Method::POST)
                .uri("/api/devices/wifi-lookup")
                .header(header::AUTHORIZATION, format!("Bearer {token}"))
                .header(header::CONTENT_TYPE, "application/json")
                .body(Body::from(body))
                .unwrap(),
        )
        .await;
    let json = serde_json::from_slice(&res.body).unwrap_or(Value::Null);
    (res.status, json)
}

fn bssids(request: &Value) -> Vec<String> {
    request["bssids"]
        .as_array()
        .unwrap()
        .iter()
        .map(|b| b.as_str().unwrap().to_string())
        .collect()
}

/// A8: the request is GrapheneOS's, byte for byte (the golden hex of the fixtures script's own encoder).
#[test]
fn the_request_is_the_golden_one() {
    let golden = &server_rows()["golden"];
    let body = codec::request(
        &bssids(golden),
        golden["surrounding"].as_u64().unwrap() as u32,
    );
    assert_eq!(hex::encode(body), golden["hex"].as_str().unwrap());
    // 0 surrounding would ask Apple for about 400: at least one.
    assert_eq!(
        codec::request(&bssids(golden), 0),
        codec::request(&bssids(golden), 1)
    );
}

/// A8: every fake answer reads as the fixtures script says - or is refused.
#[test]
fn every_apple_answer_reads_as_recorded() {
    for row in server_rows()["apple"].as_array().unwrap() {
        let name = row["name"].as_str().unwrap();
        let body = hex::decode(row["hex"].as_str().unwrap()).unwrap();
        let read = codec::answer(&body);
        if row["malformed"] == true {
            assert!(read.is_err(), "{name}: {read:?}");
            continue;
        }
        let read: Vec<Value> = read
            .unwrap_or_else(|_| panic!("{name} didn't read"))
            .into_iter()
            .map(|(bssid, position)| match position {
                Some(p) => json!([bssid, p.lat, p.lng, p.acc_m]),
                None => json!([bssid, null]),
            })
            .collect();
        assert_eq!(Value::Array(read), row["aps"], "{name}");
    }
    // Past the Kind's cut: not read.
    let mut big = apple("fresh");
    big.resize(codec::MAX_ANSWER_BYTES + 1, 0);
    assert!(codec::answer(&big).is_err());
}

#[tokio::test]
async fn the_route_answers_the_contract() {
    let contract = contract();
    for row in contract["answers"].as_array().unwrap() {
        let case = row["case"].as_str().unwrap();
        let request = &row["request"];
        let app = TestApp::new().await;
        let (id, token) = phone_on(&app).await;
        let wifi = app.state.integrations.wifi_lookup.clone();
        app.fetch.answer(APPLE_URL, 200, apple("fresh"));
        let first = &contract["request"];
        let (status, body) = match case {
            "fresh" => lookup(&app, &token, request).await,
            "cached" => {
                assert_eq!(lookup(&app, &token, first).await.0, StatusCode::OK);
                let answer = lookup(&app, &token, request).await;
                assert_eq!(app.fetch.count(), 1, "{case}: Apple asked again");
                answer
            }
            "stale_while_paused" => {
                assert_eq!(lookup(&app, &token, first).await.0, StatusCode::OK);
                sqlx::query(
                    "UPDATE wifi_lookup_aps SET fetched_month = fetched_month - 4, used_month = used_month - 4",
                )
                .execute(&app.db)
                .await
                .unwrap();
                wifi.pauses
                    .lock()
                    .unwrap()
                    .failed(Failure::Timeout, Instant::now());
                let answer = lookup(&app, &token, request).await;
                assert_eq!(app.fetch.count(), 1, "{case}: Apple asked while paused");
                answer
            }
            "over_limit" => {
                assert_eq!(lookup(&app, &token, first).await.0, StatusCode::OK);
                let now = Instant::now();
                while wifi.burst.take(now) {}
                let answer = lookup(&app, &token, request).await;
                assert_eq!(app.fetch.count(), 1, "{case}: Apple asked over the limit");
                answer
            }
            "off" => {
                super::set_on(&app.state, id, false).await.unwrap();
                lookup(&app, &token, request).await
            }
            "off_location" => {
                set_location(&app, id, "off").await;
                lookup(&app, &token, request).await
            }
            "limit" => {
                for _ in 0..route::PHONE_LOOKUPS_AN_HOUR {
                    assert_eq!(lookup(&app, &token, request).await.0, StatusCode::OK);
                }
                // The cache answered all but the first.
                assert_eq!(app.fetch.count(), 1);
                lookup(&app, &token, request).await
            }
            "busy_partly_cached" => {
                assert_eq!(lookup(&app, &token, first).await.0, StatusCode::OK);
                app.fetch.hold();
                let six = json!({"bssids": ["00:00:5e:00:53:06"]});
                let seven = json!({"bssids": ["00:00:5e:00:53:07"]});
                let (_, _, three) = tokio::join!(
                    lookup(&app, &token, &six),
                    lookup(&app, &token, &seven),
                    async {
                        app.fetch.wait_in_flight().await;
                        let third = lookup(&app, &token, request).await;
                        app.fetch.release();
                        third
                    }
                );
                three
            }
            "busy" => {
                app.fetch.hold();
                let (one, two, three) = tokio::join!(
                    lookup(&app, &token, request),
                    lookup(&app, &token, request),
                    async {
                        app.fetch.wait_in_flight().await;
                        let third = lookup(&app, &token, request).await;
                        app.fetch.release();
                        third
                    }
                );
                // Which of the two takes the gate first is the scheduler's (a slow runner let the second win,
                // 2026-11-22): one of them is answered, the other answered or busy.
                let answered = [&one.0, &two.0]
                    .iter()
                    .filter(|s| ***s == StatusCode::OK)
                    .count();
                assert!(answered >= 1, "{case}: neither answered {one:?} {two:?}");
                for (n, s) in [("first", &one.0), ("second", &two.0)] {
                    assert!(
                        *s == StatusCode::OK || *s == StatusCode::TOO_MANY_REQUESTS,
                        "{case}: the {n} {s:?}"
                    );
                }
                three
            }
            "unavailable" => {
                app.fetch.fail(
                    APPLE_URL,
                    crate::music_net::FetchError::Network("refused".to_string()),
                );
                lookup(&app, &token, request).await
            }
            bad if bad.starts_with("bad_") => lookup(&app, &token, request).await,
            other => panic!("a case the test doesn't know: {other}"),
        };
        assert_eq!(
            u64::from(status.as_u16()),
            row["status"].as_u64().unwrap(),
            "{case}: {body}"
        );
        assert_eq!(body, row["body"], "{case}");
    }
}

#[tokio::test]
async fn the_policy_and_the_group_follow_the_switch() {
    let contract = contract();
    let app = TestApp::new().await;
    let (id, token) = app.enrolled_device("phone").await;
    assert_eq!(
        app.policy(&token).await["wifi_lookup"],
        contract["policy"]["off"]
    );
    let cookie = app.admin_cookie().await;
    let page = format!("/devices/{id}/location-settings?from=device");
    let html = app.get_page(&page, &cookie).await.text();
    assert!(html.contains("id=\"wifi-lookup\"") && html.contains("Lookups at Apple"));
    assert!(html.contains(&format!("action=\"/devices/{id}/wifi-lookup#wifi-lookup\"")));
    // No proxy set: the ⓘ says the lookups go from the server's own address.
    assert!(html.contains("data-note=\"direct\""));
    assert!(!html.contains("data-warning=\"wifi-lookup\""));
    assert_eq!(crate::tests::input_checked(&html, "on", "on"), Some(false));
    assert!(crate::tests::autosaves(&html, "on"));

    // The save stays in place and the policy carries it.
    let res = app
        .request_form(
            Method::POST,
            &format!("/devices/{id}/wifi-lookup"),
            Some(&cookie),
            &[("on", "on"), ("from", "device")],
        )
        .await;
    assert_eq!(res.location(), Some(format!("{page}#wifi-lookup").as_str()));
    assert_eq!(
        app.policy(&token).await["wifi_lookup"],
        contract["policy"]["on"]
    );

    // A7: the pause at its day - the orange line here and the Attention row.
    integration_health::failed(&app.db, super::NAME, "", "blocked", Class::Stop).await;
    let html = app.get_page(&page, &cookie).await.text();
    assert!(html.contains("data-warning=\"wifi-lookup\"") && html.contains("Apple lookups aren"));
    let rows = integration_health::for_device(&app.db, id, chrono::Utc::now())
        .await
        .unwrap();
    assert_eq!(rows, vec![(Title::WifiLookup, 1)]);
    assert!(Title::WifiLookup.target(id).ends_with("#wifi-lookup"));

    // Off: no line, no row (the parent's choice).
    let res = app
        .request_form(
            Method::POST,
            &format!("/devices/{id}/wifi-lookup"),
            Some(&cookie),
            &[],
        )
        .await;
    assert_eq!(
        res.location(),
        Some(format!("/devices/{id}/location-settings#wifi-lookup").as_str())
    );
    let html = app.get_page(&page, &cookie).await.text();
    assert!(!html.contains("data-warning=\"wifi-lookup\""));
    assert!(
        integration_health::for_device(&app.db, id, chrono::Utc::now())
            .await
            .unwrap()
            .is_empty()
    );
    assert_eq!(
        app.policy(&token).await["wifi_lookup"],
        contract["policy"]["off"]
    );
}

/// A7's orange line from `server.json`'s failure histories, through the pause steps and `integration_health`.
#[tokio::test]
async fn the_health_rows_raise_the_line_as_recorded() {
    let base = chrono::Utc::now() - chrono::Duration::hours(30);
    for row in server_rows()["health"].as_array().unwrap() {
        let name = row["name"].as_str().unwrap();
        let app = TestApp::new().await;
        let (id, _) = app.enrolled_device("phone").await;
        super::set_on(&app.state, id, row["switch"].as_bool().unwrap())
            .await
            .unwrap();
        let at = |hours: f64| base + chrono::Duration::seconds((hours * 3600.0) as i64);
        let mut pauses = Pauses::default();
        for outcome in row["outcomes"].as_array().unwrap() {
            let when = at(outcome["at_h"].as_f64().unwrap());
            if outcome["ok"] == true {
                pauses.ok();
                integration_health::ok_at(&app.db, super::NAME, "", when).await;
                continue;
            }
            let failure = match outcome["failure"].as_str().unwrap() {
                "timeout" => Failure::Timeout,
                "proxy" => Failure::Proxy,
                "blocked" => Failure::Blocked,
                "format" => Failure::Format,
                other => panic!("{other}"),
            };
            let (_, day) = pauses.failed(failure, Instant::now());
            let class = if day { Class::Stop } else { Class::Transient };
            integration_health::failed_at(&app.db, super::NAME, "", failure.code(), class, when)
                .await;
        }
        let now = at(row["now_h"].as_f64().unwrap());
        let shown = integration_health::for_device(&app.db, id, now)
            .await
            .unwrap()
            .contains(&(Title::WifiLookup, 1));
        assert_eq!(shown, row["line"].as_bool().unwrap(), "{name}");
    }
}

/// L5: the steps - 5 min, then an hour doubling to a day; a non-200 or a format failure starts at the hour.
#[test]
fn the_pause_steps() {
    let now = Instant::now();
    let mut pauses = Pauses::default();
    let mut got = Vec::new();
    for _ in 0..8 {
        let (pause, day) = pauses.failed(Failure::Proxy, now);
        got.push((pause.as_secs() / 60, day));
    }
    assert_eq!(
        got,
        [
            (5, false),
            (60, false),
            (120, false),
            (240, false),
            (480, false),
            (960, false),
            (1440, true),
            (1440, true),
        ]
    );
    pauses.ok();
    assert_eq!(pauses.left(now), None);
    assert_eq!(pauses.failed(Failure::Blocked, now).0.as_secs(), 3600);
    let mut fresh = Pauses::default();
    assert_eq!(fresh.failed(Failure::Format, now).0.as_secs(), 3600);
    assert_eq!(fresh.failed(Failure::Timeout, now).0.as_secs(), 7200);
}

/// A4's 503 after a failed call, the pause holding later lookups away from Apple, and the health recorded.
#[tokio::test]
async fn a_failed_call_pauses_apple_and_a_success_clears_it() {
    let app = TestApp::new().await;
    let (_, token) = phone_on(&app).await;
    let request = contract()["request"].clone();
    app.fetch.answer(APPLE_URL, 403, "");
    let (status, body) = lookup(&app, &token, &request).await;
    assert_eq!(status, StatusCode::SERVICE_UNAVAILABLE);
    assert_eq!(body["retry_after_s"], 3600);
    let wifi = app.state.integrations.wifi_lookup.clone();
    assert_eq!(
        wifi.health().last_error.map(|(_, code)| code),
        Some("blocked")
    );
    let (status, body) = lookup(&app, &token, &request).await;
    assert_eq!(status, StatusCode::SERVICE_UNAVAILABLE);
    assert!(body["retry_after_s"].as_u64().unwrap() <= 3600);
    assert_eq!(app.fetch.count(), 1, "asked again while paused");
    let row = integration_health::row(&app.db, super::NAME, "")
        .await
        .unwrap()
        .unwrap();
    assert_eq!((row.failures, row.error.as_deref()), (1, Some("blocked")));

    // The pause over: a 200 clears it all.
    wifi.pauses.lock().unwrap().ok();
    app.fetch.answer(APPLE_URL, 200, apple("fresh"));
    assert_eq!(lookup(&app, &token, &request).await.0, StatusCode::OK);
    assert!(wifi.health().last_ok.is_some());
    let row = integration_health::row(&app.db, super::NAME, "")
        .await
        .unwrap()
        .unwrap();
    assert_eq!(row.failing_since, None);
    // An answer that doesn't read is a format failure.
    sqlx::query("DELETE FROM wifi_lookup_aps")
        .execute(&app.db)
        .await
        .unwrap();
    app.fetch.answer(APPLE_URL, 200, apple("group"));
    assert_eq!(
        lookup(&app, &token, &request).await.0,
        StatusCode::SERVICE_UNAVAILABLE
    );
    assert_eq!(
        wifi.health().last_error.map(|(_, code)| code),
        Some("format")
    );
}

/// A5: what the table holds - ids and seals, no BSSID or position in the clear; unknown is a negative; the cache
/// answers within three months, Apple again after.
#[tokio::test]
async fn the_cache_is_sealed_and_refreshed_after_three_months() {
    let app = TestApp::new().await;
    let (_, token) = phone_on(&app).await;
    let request = contract()["request"].clone();
    app.fetch.answer(APPLE_URL, 200, apple("fresh"));
    assert_eq!(lookup(&app, &token, &request).await.0, StatusCode::OK);
    let rows: Vec<(Vec<u8>, Option<Vec<u8>>, i64)> =
        sqlx::query_as("SELECT id, sealed, negative FROM wifi_lookup_aps")
            .fetch_all(&app.db)
            .await
            .unwrap();
    assert_eq!(rows.len(), 5);
    assert_eq!(rows.iter().filter(|r| r.2 == 1).count(), 1);
    for (id, sealed, negative) in &rows {
        assert_eq!(id.len(), 32);
        assert_eq!(sealed.is_none(), *negative == 1);
        let all = [id.as_slice(), sealed.as_deref().unwrap_or_default()].concat();
        assert!(
            !all.windows(8).any(|w| w == b"00:00:5e"),
            "a BSSID in the clear"
        );
        for coordinate in [599_100_000i32, 107_500_000] {
            assert!(!all.windows(4).any(|w| w == coordinate.to_be_bytes()));
        }
    }
    // The same month: the cache answers, the unknown one too (no Apple call).
    let (_, body) = lookup(&app, &token, &request).await;
    assert_eq!(app.fetch.count(), 1);
    assert_eq!(body["aps"].as_array().unwrap().len(), 2);
    assert_eq!(body["unknown"], json!(["00:00:5e:00:53:03"]));
    // The surrounding APs are cached, and answered only when asked for.
    let (_, body) = lookup(&app, &token, &json!({"bssids": ["00:00:5e:00:53:04"]})).await;
    assert_eq!(app.fetch.count(), 1);
    assert_eq!(body["aps"][0]["fresh_months_left"], 3);
    // Two months on: still the cache's, one month left (the negative ran out: 03 is asked alone).
    sqlx::query("UPDATE wifi_lookup_aps SET fetched_month = fetched_month - 2")
        .execute(&app.db)
        .await
        .unwrap();
    app.fetch.clear_calls();
    let (_, body) = lookup(&app, &token, &request).await;
    assert_eq!(body["aps"][0]["fresh_months_left"], 1);
    let asked = app.fetch.requests.lock().unwrap()[0].body.clone().unwrap();
    assert_eq!(
        asked,
        codec::request(&["00:00:5e:00:53:03".to_string()], 100)
    );
    // Three months: Apple again for the two, the rows renewed.
    sqlx::query("UPDATE wifi_lookup_aps SET fetched_month = fetched_month - 3 WHERE negative = 0")
        .execute(&app.db)
        .await
        .unwrap();
    app.fetch.clear_calls();
    let (_, body) = lookup(&app, &token, &request).await;
    assert_eq!(app.fetch.count(), 1);
    assert!(
        body["aps"]
            .as_array()
            .unwrap()
            .iter()
            .all(|r| r["fresh_months_left"] == cache::FRESH_MONTHS)
    );
}

/// A5: a new key empties the table (a restored backup, a lost key); the same key keeps it.
#[tokio::test]
async fn a_new_key_empties_the_cache() {
    let app = TestApp::new().await;
    let (_, token) = phone_on(&app).await;
    app.fetch.answer(APPLE_URL, 200, apple("fresh"));
    lookup(&app, &token, &contract()["request"]).await;
    let count = async || -> i64 {
        sqlx::query_scalar("SELECT COUNT(*) FROM wifi_lookup_aps")
            .fetch_one(&app.db)
            .await
            .unwrap()
    };
    assert_eq!(count().await, 5);
    let file = app.state.data_dir.join("keys").join(super::NAME);
    assert!(file.exists());
    // A restart with the key: kept.
    let again = WifiLookup::for_tests(app.fetch.clone());
    again.key(&app.state).await.unwrap();
    assert_eq!(count().await, 5);
    // The key gone: a new one, and nothing sealed under the old.
    std::fs::remove_file(&file).unwrap();
    let fresh = WifiLookup::for_tests(app.fetch.clone());
    fresh.key(&app.state).await.unwrap();
    assert_eq!(count().await, 0);
    // A mangled key is refused, never replaced - and raises the orange line at once (code QA L6).
    std::fs::write(&file, "not a key").unwrap();
    let mangled = WifiLookup::for_tests(app.fetch.clone());
    assert!(mangled.key(&app.state).await.is_err());
    let wanted = ["00:00:5e:00:53:01".to_string()];
    assert_eq!(
        mangled.lookup(&app.state, 1, &wanted).await,
        Err(route::PAUSE_STEPS[1])
    );
    assert_eq!(mangled.health().last_error.map(|(_, c)| c), Some("key"));
    let (device, _) = app.enrolled_device("other").await;
    assert!(super::set_on(&app.state, device, true).await.unwrap());
    assert!(
        integration_health::for_device(&app.db, device, chrono::Utc::now())
            .await
            .unwrap()
            .contains(&(Title::WifiLookup, 1))
    );
    assert_eq!(std::fs::read_to_string(&file).unwrap(), "not a key");
}

/// A5: the caps (negatives and positives each their own, the least recently used first), the sweep's lifetime and the
/// last phone with the switch on deleted.
#[tokio::test]
async fn the_caps_the_lifetime_and_a_deleted_phone() {
    let app = TestApp::new().await;
    // A phone the lookup is usable for: the sweep keeps the cache.
    let (on_phone, _) = phone_on(&app).await;
    let key = CacheKey::from_bytes(&[9; 32]);
    let now = this_month();
    let position = codec::Position {
        lat: 59.91,
        lng: 10.75,
        acc_m: 20,
    };
    let put = async |n: u8, negative: bool, used: i64| {
        let bssid = format!("00:00:5e:00:53:{n:02x}");
        sqlx::query(
            "INSERT INTO wifi_lookup_aps (id, sealed, fetched_month, used_month, negative) VALUES (?, ?, ?, ?, ?)",
        )
        .bind(key.id(&bssid))
        .bind((!negative).then(|| key.seal(&bssid, &position)))
        .bind(used)
        .bind(used)
        .bind(negative)
        .execute(&app.db)
        .await
        .unwrap();
        key.id(&bssid)
    };
    let old = put(1, false, now - 5).await;
    let newer = put(2, false, now - 1).await;
    let newest = put(3, false, now).await;
    let neg_old = put(4, true, now - 2).await;
    let neg_new = put(5, true, now).await;
    let ids = async || -> Vec<Vec<u8>> {
        sqlx::query_scalar("SELECT id FROM wifi_lookup_aps")
            .fetch_all(&app.db)
            .await
            .unwrap()
    };
    let mut conn = app.db.acquire().await.unwrap();
    cache::evict(&mut conn, false, 2).await.unwrap();
    cache::evict(&mut conn, true, 1).await.unwrap();
    drop(conn);
    let left = ids().await;
    assert!(!left.contains(&old) && !left.contains(&neg_old));
    assert!(left.contains(&newer) && left.contains(&newest) && left.contains(&neg_new));

    // The sweep: two years unused goes, a day short stays; a negative past its month goes.
    let gone = put(6, false, now - cache::CACHE_LIFETIME_MONTHS).await;
    let kept = put(7, false, now - cache::CACHE_LIFETIME_MONTHS + 1).await;
    let neg_gone = put(8, true, now - 1).await;
    let wifi = app.state.integrations.wifi_lookup.clone();
    wifi.prune(&app.state, Prune::Sweep).await;
    let left = ids().await;
    assert!(!left.contains(&gone) && !left.contains(&neg_gone));
    assert!(left.contains(&kept) && left.contains(&neg_new));
    assert!(matches!(
        cache::read(&app.db, &key, &["00:00:5e:00:53:07".to_string()], now)
            .await
            .unwrap()[..],
        [Cached::Known { .. }]
    ));

    // A deleted phone: kept while another has the switch on, emptied with the last.
    let (other, _) = app.enrolled_device("other").await;
    let cookie = app.admin_cookie().await;
    for (id, left) in [(other, 4usize), (on_phone, 0)] {
        let res = app
            .request_form(
                Method::POST,
                &format!("/devices/{id}/delete"),
                Some(&cookie),
                &[],
            )
            .await;
        assert!(res.status.is_redirection());
        assert_eq!(ids().await.len(), left);
    }
}

/// GrapheneOS's throttle: after an answer of 17 APs or more, the next call asks for 8 surrounding.
#[tokio::test]
async fn a_big_answer_throttles_the_next_call() {
    let app = TestApp::new().await;
    let (_, token) = phone_on(&app).await;
    app.fetch.answer(APPLE_URL, 200, apple("throttle"));
    let first = json!({"bssids": ["00:00:5e:00:53:14"]});
    let (_, body) = lookup(&app, &token, &first).await;
    // Only the asked one (code QA M3); the 16 surrounding are cached.
    assert_eq!(body["aps"].as_array().unwrap().len(), 1);
    assert_eq!(body["aps"][0]["bssid"], "00:00:5e:00:53:14");
    let rows: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM wifi_lookup_aps")
        .fetch_one(&app.db)
        .await
        .unwrap();
    assert_eq!(rows, 17);
    let second = json!({"bssids": ["00:00:5e:00:53:01"]});
    lookup(&app, &token, &second).await;
    let requests = app.fetch.requests.lock().unwrap().clone();
    assert_eq!(
        requests[0].body.as_deref(),
        Some(codec::request(&bssids(&first), route::SURROUNDING).as_slice())
    );
    assert_eq!(
        requests[1].body.as_deref(),
        Some(codec::request(&bssids(&second), route::SURROUNDING_THROTTLED).as_slice())
    );
    // macOS's locationd: GrapheneOS's headers, POST to the fixed URL, never a redirect followed.
    assert_eq!(requests[0].method, "POST");
    assert_eq!(requests[0].url, APPLE_URL);
    assert!(!requests[0].follow);
    assert!(
        requests[0]
            .header("user-agent")
            .is_some_and(|ua| ua.starts_with("locationd/"))
    );
    assert_eq!(requests[0].proxy, None);
}

#[test]
fn the_proxy_setting() {
    for (value, want) in [
        (None, Proxy::Direct),
        (Some("  "), Proxy::Direct),
        (
            Some("socks5h://tor:9050"),
            Proxy::Socks("socks5h://tor:9050".into()),
        ),
        (
            Some("socks5h://127.0.0.1"),
            Proxy::Socks("socks5h://127.0.0.1:1080".into()),
        ),
        (Some("socks5://tor:9050"), Proxy::Invalid),
        (Some("http://tor:9050"), Proxy::Invalid),
        (Some("socks5h://user:pass@tor:9050"), Proxy::Invalid),
        (Some("socks5h://tor:9050/x"), Proxy::Invalid),
        (Some("tor:9050"), Proxy::Invalid),
    ] {
        assert_eq!(Proxy::parse(value), want, "{value:?}");
    }
}

/// A7: with a proxy set every call carries it, and a proxy failure is a failure - never a second, direct call; an
/// unreadable setting sends nothing at all and raises the orange line at once (code QA L6).
#[tokio::test]
async fn a_proxy_failure_is_never_a_direct_call() {
    let app = TestApp::new().await;
    let wanted = vec!["00:00:5e:00:53:01".to_string()];
    for proxy in [Proxy::Socks("socks5h://tor:9050".into()), Proxy::Invalid] {
        app.fetch.clear_calls();
        app.fetch.fail(
            APPLE_URL,
            crate::music_net::FetchError::Network("proxy refused".to_string()),
        );
        let wifi = WifiLookup::with(
            app.fetch.clone(),
            proxy.clone(),
            std::time::Duration::from_millis(300),
        );
        let answer = wifi.lookup(&app.state, 1, &wanted).await;
        let row = integration_health::row(&app.db, super::NAME, "")
            .await
            .unwrap()
            .unwrap();
        let requests = app.fetch.requests.lock().unwrap().clone();
        match proxy {
            Proxy::Socks(url) => {
                assert_eq!(answer, Err(route::PAUSE_STEPS[0]));
                assert_eq!(wifi.health().last_error.map(|(_, c)| c), Some("proxy"));
                assert_eq!(requests.len(), 1);
                assert_eq!(requests[0].proxy.as_deref(), Some(url.as_str()));
            }
            _ => {
                assert_eq!(answer, Err(route::PAUSE_STEPS[1]));
                assert_eq!(
                    wifi.health().last_error.map(|(_, c)| c),
                    Some("proxy_setting")
                );
                assert_eq!(row.error.as_deref(), Some("proxy_setting"));
                assert!(requests.is_empty());
            }
        }
    }
}

/// The real client through a SOCKS5 proxy (a local fake that refuses every CONNECT): each call authenticates with its
/// own random credentials and names the host for the proxy to resolve (socks5h); the failure is the call's.
#[tokio::test]
async fn the_http_client_goes_through_the_socks_proxy() {
    use tokio::io::{AsyncReadExt, AsyncWriteExt};
    let _ = rustls::crypto::ring::default_provider().install_default();
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let port = listener.local_addr().unwrap().port();
    let seen = std::sync::Arc::new(std::sync::Mutex::new(
        Vec::<(String, String, String, u16)>::new(),
    ));
    let record = seen.clone();
    tokio::spawn(async move {
        loop {
            let Ok((mut socket, _)) = listener.accept().await else {
                return;
            };
            let record = record.clone();
            tokio::spawn(async move {
                let mut head = [0u8; 2];
                socket.read_exact(&mut head).await.unwrap();
                let mut methods = vec![0u8; head[1] as usize];
                socket.read_exact(&mut methods).await.unwrap();
                assert!(methods.contains(&2), "no user/password method offered");
                socket.write_all(&[5, 2]).await.unwrap();
                let text = async |socket: &mut tokio::net::TcpStream| {
                    let len = socket.read_u8().await.unwrap() as usize;
                    let mut buf = vec![0u8; len];
                    socket.read_exact(&mut buf).await.unwrap();
                    String::from_utf8(buf).unwrap()
                };
                assert_eq!(socket.read_u8().await.unwrap(), 1);
                let user = text(&mut socket).await;
                let pass = text(&mut socket).await;
                socket.write_all(&[1, 0]).await.unwrap();
                let mut connect = [0u8; 4];
                socket.read_exact(&mut connect).await.unwrap();
                assert_eq!(connect[3], 3, "the target as a name (socks5h)");
                let host = text(&mut socket).await;
                let port = socket.read_u16().await.unwrap();
                record.lock().unwrap().push((user, pass, host, port));
                // "Connection refused".
                let _ = socket.write_all(&[5, 5, 0, 1, 0, 0, 0, 0, 0, 0]).await;
            });
        }
    });
    let source = crate::music_net::HttpSource::new();
    for _ in 0..2 {
        let mut request = crate::music_net::SourceRequest::new(
            "https://wifi-lookup.invalid/clls/wloc",
            crate::music_net::Kind::AppleWps,
            crate::music_net::Reach::Public,
        );
        request.method = reqwest::Method::POST;
        request.proxy = Some(format!("socks5h://127.0.0.1:{port}"));
        assert!(
            crate::music_net::Source::get(&source, request)
                .await
                .is_err()
        );
    }
    let seen = seen.lock().unwrap().clone();
    assert_eq!(seen.len(), 2);
    for (user, pass, host, port) in &seen {
        assert_eq!((host.as_str(), *port), ("wifi-lookup.invalid", 443));
        assert_eq!((user.len(), pass.len()), (16, 16));
    }
    assert_ne!(seen[0].0, seen[1].0, "the same credentials twice");
}

/// A6: no lookup log - no `wifi_lookup` tracing line names a device, a BSSID or a coordinate.
#[test]
fn no_log_line_names_a_device_a_bssid_or_a_place() {
    for entry in std::fs::read_dir("src/integrations/wifi_lookup").unwrap() {
        let path = entry.unwrap().path();
        if path.ends_with("tests.rs") {
            continue;
        }
        let text = std::fs::read_to_string(&path).unwrap();
        for (at, _) in text.match_indices("tracing::") {
            // The macro's whole argument list, by its parentheses.
            let open = at + text[at..].find('(').unwrap();
            let mut depth = 0;
            let mut end = open;
            for (i, c) in text[open..].char_indices() {
                depth += match c {
                    '(' => 1,
                    ')' => -1,
                    _ => 0,
                };
                if depth == 0 {
                    end = open + i;
                    break;
                }
            }
            let call = &text[at..=end];
            // Design 125b: nor a cell's identity.
            for word in [
                "device",
                "bssid",
                "lat",
                "lng",
                "position",
                "mac",
                "mcc",
                "mnc",
                "cid",
                "canonical",
                "tower",
            ] {
                assert!(
                    !call.contains(word),
                    "{}: a log line names {word}: {call}",
                    path.display()
                );
            }
        }
    }
}

// --- Design 125b: cells ---

/// A fake answer of a cell call from `server.json` by name.
fn apple_cell(name: &str) -> Vec<u8> {
    let rows = server_rows();
    let row = rows["apple_cells"]
        .as_array()
        .unwrap()
        .iter()
        .find(|r| r["name"] == name)
        .unwrap_or_else(|| panic!("no apple_cells row {name}"))
        .clone();
    hex::decode(row["hex"].as_str().unwrap()).unwrap()
}

/// C6: the cell call is GrapheneOS's, byte for byte.
#[test]
fn the_cell_request_is_the_golden_one() {
    let golden = &server_rows()["golden_cells"];
    let towers: Vec<cells::Cell> = golden["cells"]
        .as_array()
        .unwrap()
        .iter()
        .map(|c| cells::Cell::from_canonical(c.as_str().unwrap()).unwrap())
        .collect();
    let body = cells::request(&towers, golden["throttled"].as_bool().unwrap());
    assert_eq!(hex::encode(body), golden["hex"].as_str().unwrap());
}

/// C6: every fake cell answer reads as the fixtures script says - or is refused.
#[test]
fn every_apple_cell_answer_reads_as_recorded() {
    for row in server_rows()["apple_cells"].as_array().unwrap() {
        let name = row["name"].as_str().unwrap();
        let body = hex::decode(row["hex"].as_str().unwrap()).unwrap();
        let read = cells::answer(&body);
        if row["malformed"] == true {
            assert!(read.is_err(), "{name}: {read:?}");
            continue;
        }
        let read: Vec<Value> = read
            .unwrap_or_else(|_| panic!("{name} didn't read"))
            .into_iter()
            .map(|(cell, position)| match position {
                Some(p) => json!([cell, p.lat, p.lng, p.acc_m]),
                None => json!([cell, null]),
            })
            .collect();
        assert_eq!(Value::Array(read), row["towers"], "{name}");
    }
}

/// C3: every `cell_forms` row, and the contract's rules are the server's constants.
#[test]
fn the_cell_forms_and_rules_are_the_contracts() {
    let contract = contract();
    for row in contract["cell_forms"].as_array().unwrap() {
        let cell = row["cell"].as_str().unwrap();
        assert_eq!(
            cells::Cell::parse(cell).is_some(),
            row["valid"].as_bool().unwrap(),
            "{}",
            row["why"]
        );
    }
    let rules = &contract["rules"];
    assert_eq!(rules["cells_max"], json!(cells::MAX_CELLS));
    assert_eq!(rules["cells_a_day"], json!(route::CELLS_A_DAY));
    // Code QA M2: the fixed answer time covers the slowest path that gets the call - the gate's wait, the Apple
    // call's whole deadline and a margin: through the proxy (the contract's) and direct.
    let source = std::sync::Arc::new(crate::tests::music::CannedFetch::default());
    let gate = std::time::Duration::from_secs(3);
    let proxied = WifiLookup::with(
        source.clone(),
        Proxy::Socks("socks5h://tor:9050".to_string()),
        gate,
    );
    assert_eq!(
        proxied.cell_answer_after,
        gate + crate::music_net::PROXIED + route::CELL_ANSWER_MARGIN
    );
    assert_eq!(
        rules["cell_answer_after_s"],
        json!(proxied.cell_answer_after.as_secs())
    );
    let direct = WifiLookup::with(source, Proxy::Direct, gate);
    assert_eq!(
        direct.cell_answer_after,
        gate + crate::music_net::Kind::AppleWps.overall() + route::CELL_ANSWER_MARGIN
    );
    assert_eq!(direct.cell_answer_after.as_secs(), 9);
    assert!(super::CELL_ANSWER_AFTER_IN_TESTS > std::time::Duration::from_millis(300));
}

/// A request's answer and how long it took.
async fn timed(
    app: &TestApp,
    token: &str,
    request: &Value,
) -> ((StatusCode, Value), std::time::Duration) {
    let started = Instant::now();
    let answer = lookup(app, token, request).await;
    (answer, started.elapsed())
}

/// C5: a cells 200 or 503 leaves at the fixed answer time - not sooner, and not later than a scheduling slack.
fn at_the_answer_time(took: std::time::Duration, what: &str) {
    let after = super::CELL_ANSWER_AFTER_IN_TESTS;
    assert!(
        took >= after && took < after + std::time::Duration::from_millis(400),
        "{what}: answered after {took:?}"
    );
}

/// C5: every `cell_answers` row through the real route - the same body and no sooner than the answer time from the
/// cache as from Apple, the 503 for every cells request while Apple can't be asked, the day's brake.
#[tokio::test]
async fn the_route_answers_the_cell_contract() {
    let contract = contract();
    for row in contract["cell_answers"].as_array().unwrap() {
        let case = row["case"].as_str().unwrap();
        let request = &row["request"];
        let app = TestApp::new().await;
        let (id, token) = phone_on(&app).await;
        let wifi = app.state.integrations.wifi_lookup.clone();
        app.fetch.answer(APPLE_URL, 200, apple_cell("cells_fresh"));
        let first = &contract["request_cells"];
        let ((status, mut body), took) = match case {
            "cells_fetched" | "cells_two_spellings" => timed(&app, &token, request).await,
            "cells_cached" => {
                assert_eq!(lookup(&app, &token, first).await.0, StatusCode::OK);
                let answer = timed(&app, &token, request).await;
                assert_eq!(app.fetch.count(), 1, "{case}: Apple asked again");
                answer
            }
            "cells_stale" => {
                assert_eq!(lookup(&app, &token, first).await.0, StatusCode::OK);
                sqlx::query(
                    "UPDATE wifi_lookup_aps SET fetched_month = fetched_month - 4, used_month = used_month - 4",
                )
                .execute(&app.db)
                .await
                .unwrap();
                let answer = timed(&app, &token, request).await;
                assert_eq!(app.fetch.count(), 2, "{case}: not refreshed");
                answer
            }
            "cells_paused_cached" | "cells_paused_uncached" => {
                if case == "cells_paused_cached" {
                    assert_eq!(lookup(&app, &token, first).await.0, StatusCode::OK);
                }
                wifi.pauses
                    .lock()
                    .unwrap()
                    .failed(Failure::Timeout, Instant::now());
                let answer = timed(&app, &token, request).await;
                assert!(app.fetch.count() <= 1, "{case}: Apple asked while paused");
                answer
            }
            "cells_day_cap" => {
                let now = chrono::Utc::now();
                wifi.cell_days.add(id, route::CELLS_A_DAY, now);
                let answer = timed(&app, &token, request).await;
                // The wait runs to UTC midnight; the contract's number stands for it.
                let midnight = (now.date_naive() + chrono::Days::new(1))
                    .and_hms_opt(0, 0, 0)
                    .unwrap()
                    .and_utc();
                let want = (midnight - now).num_seconds();
                let got = answer.0.1["retry_after_s"].as_i64().unwrap();
                assert!((got - want).abs() <= 2, "{case}: {got} against {want}");
                answer
            }
            "cells_limit_hourly" => {
                for _ in 0..route::PHONE_LOOKUPS_AN_HOUR {
                    assert!(wifi.phone_asks.ask(id, Instant::now()).is_ok());
                }
                let answer = timed(&app, &token, request).await;
                // Code QA L1: a refused request takes nothing of the day's count.
                assert_eq!(wifi.cell_days.used(id, chrono::Utc::now()), 0, "{case}");
                answer
            }
            "cells_busy" => {
                // Another lookup's Apple call holds the gate past its wait.
                let _gate = wifi.apple.lock().await;
                timed(&app, &token, request).await
            }
            "cells_off" => {
                super::set_on(&app.state, id, false).await.unwrap();
                timed(&app, &token, request).await
            }
            bad if bad.starts_with("bad_") => timed(&app, &token, request).await,
            other => panic!("a case the test doesn't know: {other}"),
        };
        if case == "cells_day_cap" {
            body["retry_after_s"] = row["body"]["retry_after_s"].clone();
        }
        assert_eq!(
            u64::from(status.as_u16()),
            row["status"].as_u64().unwrap(),
            "{case}: {body}"
        );
        assert_eq!(body, row["body"], "{case}");
        // C5: a 200 or a 503 leaves at the answer time, cached or not; refusals at once.
        if matches!(status.as_u16(), 200 | 503) {
            at_the_answer_time(took, case);
        } else {
            assert!(took < super::CELL_ANSWER_AFTER_IN_TESTS, "{case}: {took:?}");
        }
    }
}

/// Code QA H1: a cells request takes the admit permit and the gate before the cache is read - a held gate or two
/// lookups in flight answer a cached and an uncached request alike: the same 503, at the same answer time.
#[tokio::test]
async fn a_held_gate_answers_cached_and_uncached_alike() {
    let app = TestApp::new().await;
    let (_, token) = phone_on(&app).await;
    app.fetch.answer(APPLE_URL, 200, apple_cell("cells_fresh"));
    let cached = contract()["request_cells"].clone();
    assert_eq!(lookup(&app, &token, &cached).await.0, StatusCode::OK);
    let uncached = json!({"cells": ["gsm:001:01:2002:6002"]});
    let wifi = app.state.integrations.wifi_lookup.clone();
    let busy = json!({"error": "unavailable", "retry_after_s": 900});
    {
        let _gate = wifi.apple.lock().await;
        let (a, b) = tokio::join!(timed(&app, &token, &cached), timed(&app, &token, &uncached));
        for (((status, body), took), what) in [(a, "cached"), (b, "uncached")] {
            assert_eq!(status, StatusCode::SERVICE_UNAVAILABLE, "{what}: {body}");
            assert_eq!(body, busy, "{what}");
            at_the_answer_time(took, what);
        }
    }
    {
        // The requests before give their permits back at their answer time.
        let _two = tokio::time::timeout(
            std::time::Duration::from_secs(1),
            wifi.admit.acquire_many(2),
        )
        .await
        .unwrap()
        .unwrap();
        for (request, what) in [(&cached, "cached"), (&uncached, "uncached")] {
            let ((status, body), took) = timed(&app, &token, request).await;
            assert_eq!(status, StatusCode::SERVICE_UNAVAILABLE, "{what}: {body}");
            assert_eq!(body, busy, "{what}");
            at_the_answer_time(took, what);
        }
    }
    assert_eq!(app.fetch.count(), 1, "Apple asked while busy");
    // The permit is held to the answer time, cached or not: what is in flight meanwhile says nothing of the cache.
    let probe = async {
        tokio::time::sleep(super::CELL_ANSWER_AFTER_IN_TESTS / 2).await;
        wifi.admit.available_permits()
    };
    let (((status, _), _), free) = tokio::join!(timed(&app, &token, &cached), probe);
    assert_eq!(status, StatusCode::OK);
    assert_eq!(
        free, 1,
        "a cached request gave its permit back before the answer time"
    );
}

/// Code QA M1: every cells request takes one slot of the server-wide limits, whether Apple is called or not - so a
/// full window answers a cached and an uncached request alike, and a cached one fills it as an uncached one would.
#[tokio::test]
async fn the_limits_take_a_slot_whatever_the_cache_holds() {
    let app = TestApp::new().await;
    let (_, token) = phone_on(&app).await;
    app.fetch.answer(APPLE_URL, 200, apple_cell("cells_fresh"));
    let cached = contract()["request_cells"].clone();
    let uncached = json!({"cells": ["gsm:001:01:2002:6002"]});
    assert_eq!(lookup(&app, &token, &cached).await.0, StatusCode::OK);
    let wifi = app.state.integrations.wifi_lookup.clone();
    for _ in 1..route::BURST_CALLS - 1 {
        assert!(wifi.burst.take(Instant::now()));
    }
    // The tenth slot: a cached request takes it.
    assert!(wifi.burst.wait(Instant::now()).is_zero());
    assert_eq!(lookup(&app, &token, &cached).await.0, StatusCode::OK);
    assert!(
        !wifi.burst.wait(Instant::now()).is_zero(),
        "a cached request took no slot"
    );
    assert_eq!(app.fetch.count(), 1);
    let (a, b) = tokio::join!(timed(&app, &token, &cached), timed(&app, &token, &uncached));
    let ((sa, ba), ta) = a;
    let ((sb, bb), tb) = b;
    assert_eq!(sa, StatusCode::SERVICE_UNAVAILABLE, "{ba}");
    assert_eq!(sb, sa);
    assert_eq!(ba["error"], bb["error"]);
    let (ra, rb) = (
        ba["retry_after_s"].as_i64().unwrap(),
        bb["retry_after_s"].as_i64().unwrap(),
    );
    assert!((ra - rb).abs() <= 1, "{ra} against {rb}");
    at_the_answer_time(ta, "cached");
    at_the_answer_time(tb, "uncached");
    assert_eq!(app.fetch.count(), 1);
}

/// Code QA M2: an Apple call that runs past the fixed answer time answers 503 at it - the same as a busy gate - and
/// goes on to fill the cache; the next request answers from the cache at the same time.
#[tokio::test]
async fn an_overrun_answers_at_the_answer_time_and_fills_the_cache() {
    let app = TestApp::new().await;
    let (_, token) = phone_on(&app).await;
    app.fetch.answer(APPLE_URL, 200, apple_cell("cells_fresh"));
    app.fetch.delay(1500);
    let request = contract()["request_cells"].clone();
    let ((status, body), took) = timed(&app, &token, &request).await;
    assert_eq!(status, StatusCode::SERVICE_UNAVAILABLE, "{body}");
    assert_eq!(body, json!({"error": "unavailable", "retry_after_s": 900}));
    at_the_answer_time(took, "overrun");
    // The call goes on in its task and keeps Apple's answer.
    let mut rows = 0;
    for _ in 0..40 {
        rows = sqlx::query_scalar::<_, i64>("SELECT COUNT(*) FROM wifi_lookup_aps")
            .fetch_one(&app.db)
            .await
            .unwrap();
        if rows > 0 {
            break;
        }
        tokio::time::sleep(std::time::Duration::from_millis(100)).await;
    }
    assert!(rows > 0, "the overrun call didn't fill the cache");
    // The permit and the gate are free again once the call is done.
    tokio::time::sleep(std::time::Duration::from_millis(200)).await;
    app.fetch.delay(0);
    let ((status, body), took) = timed(&app, &token, &request).await;
    assert_eq!(status, StatusCode::OK, "{body}");
    at_the_answer_time(took, "cached");
    assert_eq!(app.fetch.count(), 1);
}

/// Code QA L1: the day's brake checks and counts in one step - concurrent requests never pass 1000 between them -,
/// and a request the hour's count refuses takes nothing of the day.
#[test]
fn the_day_brake_counts_in_one_step() {
    let days = std::sync::Arc::new(route::CellDays::default());
    let now = chrono::Utc::now();
    let threads: Vec<_> = (0..8)
        .map(|_| {
            let days = days.clone();
            std::thread::spawn(move || {
                (0..40)
                    .filter(|_| days.try_add(7, 12, now, || true).is_ok())
                    .count()
            })
        })
        .collect();
    let passed: usize = threads.into_iter().map(|t| t.join().unwrap()).sum();
    assert_eq!(passed, route::CELLS_A_DAY / 12);
    assert_eq!(days.used(7, now), route::CELLS_A_DAY / 12 * 12);
    // Full: refused with the wait to midnight, the hour's count never asked.
    assert!(matches!(
        days.try_add(7, 12, now, || panic!("asked")),
        Err(Some(_))
    ));
    // The hour refuses: nothing counted.
    assert_eq!(days.try_add(8, 3, now, || false), Err(None));
    assert_eq!(days.used(8, now), 0);
}

/// C7: a cell row is sealed under its canonical form - Apple's numeric MNC meets the asked `01` - and Apple's
/// surrounding towers are cached; an AP in a cell answer never is.
#[tokio::test]
async fn a_cell_row_is_sealed_under_its_canonical_form() {
    let app = TestApp::new().await;
    let (_, token) = phone_on(&app).await;
    app.fetch.answer(APPLE_URL, 200, apple_cell("cells_fresh"));
    let (status, _) = lookup(&app, &token, &contract()["request_cells"]).await;
    assert_eq!(status, StatusCode::OK);
    let wifi = app.state.integrations.wifi_lookup.clone();
    let key = wifi.key(&app.state).await.unwrap();
    let month = this_month();
    let read = cache::read(
        &app.db,
        &key,
        &[
            "lte:1:1:4711:12345678".to_string(),
            "lte:1:1:4711:12345679".to_string(),
            "gsm:1:1:1001:5001".to_string(),
        ],
        month,
    )
    .await
    .unwrap();
    assert!(matches!(read[0], Cached::Known { .. }), "{read:?}");
    assert!(
        matches!(read[1], Cached::Known { .. }),
        "the surrounding tower"
    );
    assert_eq!(read[2], Cached::Unknown);
    // Five towers placed and one negative: nothing under the asked spelling.
    let rows: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM wifi_lookup_aps")
        .fetch_one(&app.db)
        .await
        .unwrap();
    assert_eq!(rows, 5);
    // An AP in a cell answer is never cached.
    let app = TestApp::new().await;
    let (_, token) = phone_on(&app).await;
    app.fetch
        .answer(APPLE_URL, 200, apple_cell("cells_skipped"));
    let (status, body) = lookup(
        &app,
        &token,
        &json!({"cells": ["lte:001:01:4711:12345678"]}),
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{body}");
    let rows: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM wifi_lookup_aps")
        .fetch_one(&app.db)
        .await
        .unwrap();
    assert_eq!(rows, 1);
    // The sweep's counts carry the cells.
    let counts = app.state.integrations.wifi_lookup.counts.take();
    assert_eq!(&counts[5..], &[1, 1]);
}

/// C6: a cell answer of 10 towers or more throttles the next cell call to 4 in all; the Wi-Fi throttle is apart.
#[tokio::test]
async fn a_big_cell_answer_throttles_the_next_cell_call() {
    let app = TestApp::new().await;
    let (_, token) = phone_on(&app).await;
    app.fetch.answer(APPLE_URL, 200, apple_cell("cells_many"));
    let (status, _) = lookup(&app, &token, &json!({"cells": ["gsm:001:01:1001:5001"]})).await;
    assert_eq!(status, StatusCode::OK);
    let wifi = app.state.integrations.wifi_lookup.clone();
    assert!(wifi.cell_throttled_until.lock().unwrap().is_some());
    assert!(wifi.throttled_until.lock().unwrap().is_none());
    let cell = cells::Cell::parse("gsm:001:01:1001:5001").unwrap();
    assert_eq!(cells::surrounding(&[cell], true), [(cells::Radio::Gsm, 1)]);
}

#[test]
fn the_integration_is_named_and_healthy_from_the_start() {
    let wifi = WifiLookup::for_tests(std::sync::Arc::new(
        crate::tests::music::CannedFetch::default(),
    ));
    assert_eq!(wifi.name(), "wifi_lookup");
    assert_eq!(wifi.health(), crate::integrations::Health::default());
}

#[test]
fn the_route_takes_only_clean_bssids() {
    assert!(route::valid_bssid("00:00:5e:00:53:01"));
    assert!(route::valid_bssid("fc:00:5e:00:53:01"));
    for bad in [
        "01:00:5e:00:53:01",
        "02:00:5e:00:53:01",
        "00:00:00:00:00:00",
        "00:00:5E:00:53:01",
        "00:00:5e:00:53:0",
        "00:00:5e:00:53:011",
        "00-00-5e-00-53-01",
        "0g:00:5e:00:53:01",
    ] {
        assert!(!route::valid_bssid(bad), "{bad}");
    }
    // Duplicates dropped.
    assert_eq!(
        route::parse(br#"{"bssids": ["00:00:5e:00:53:01", "00:00:5e:00:53:01"]}"#),
        Some(vec!["00:00:5e:00:53:01".to_string()])
    );
}

async fn cache_rows(app: &TestApp) -> i64 {
    sqlx::query_scalar("SELECT COUNT(*) FROM wifi_lookup_aps")
        .fetch_one(&app.db)
        .await
        .unwrap()
}

async fn location_off(app: &TestApp, cookie: &str, device: i64) {
    let res = app
        .request_form(
            Method::POST,
            &format!("/devices/{device}/location-policy"),
            Some(cookie),
            &[("mode", "off")],
        )
        .await;
    assert!(res.status.is_redirection(), "{}", res.text());
}

/// The lookup follows the phone's location switch: Off stops the route (cells too) and the policy says off, and the
/// Off save empties the cache once no phone has both the switch and location on; location on again brings it back.
#[tokio::test]
async fn the_lookup_follows_the_location_switch() {
    let contract = contract();
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = phone_on(&app).await;
    let (other, _) = phone_on(&app).await;
    app.fetch.answer(APPLE_URL, 200, apple("fresh"));
    assert_eq!(
        lookup(&app, &token, &contract["request"]).await.0,
        StatusCode::OK
    );
    let cached = cache_rows(&app).await;
    assert!(cached > 0);

    // This phone's location off: refused and off in its policy; the other phone still uses the cache.
    location_off(&app, &cookie, id).await;
    let (status, body) = lookup(&app, &token, &contract["request"]).await;
    assert_eq!(status, StatusCode::CONFLICT);
    assert_eq!(body, json!({"error": "off"}));
    let (status, _) = lookup(&app, &token, &json!({"cells": ["gsm:001:01:1001:5001"]})).await;
    assert_eq!(status, StatusCode::CONFLICT);
    assert_eq!(
        app.policy(&token).await["wifi_lookup"],
        contract["policy"]["off"]
    );
    assert_eq!(cache_rows(&app).await, cached);

    // The last phone's location off: the cache goes.
    location_off(&app, &cookie, other).await;
    assert_eq!(cache_rows(&app).await, 0);

    // Location on again: the lookup is back.
    set_location(&app, id, "on_request").await;
    assert_eq!(
        app.policy(&token).await["wifi_lookup"],
        contract["policy"]["on"]
    );
    assert_eq!(
        lookup(&app, &token, &contract["request"]).await.0,
        StatusCode::OK
    );
}

/// Each phone gets its share of the hour's Apple calls: past it, it is answered from the cache alone, while another
/// phone is still served from Apple.
#[tokio::test]
async fn each_phone_gets_its_share_of_the_hour() {
    assert_eq!(route::phone_share(0), route::CALLS_AN_HOUR);
    assert_eq!(route::phone_share(1), route::CALLS_AN_HOUR);
    assert_eq!(route::phone_share(3), 20);
    assert_eq!(route::phone_share(60), route::BURST_CALLS);

    let app = TestApp::new().await;
    let (first, first_token) = phone_on(&app).await;
    let (_, second_token) = phone_on(&app).await;
    phone_on(&app).await;
    let wifi = app.state.integrations.wifi_lookup.clone();
    app.fetch.answer(APPLE_URL, 200, apple("fresh"));

    // A real call counts against the phone's share.
    let request = json!({"bssids": ["00:00:5e:00:53:01"]});
    assert_eq!(lookup(&app, &first_token, &request).await.0, StatusCode::OK);
    assert_eq!(app.fetch.count(), 1);
    assert!(
        !wifi
            .phone_calls
            .wait_under(first, 1, Instant::now())
            .is_zero()
    );

    // Three phones: 20 each. The first phone's share spent: the cache alone (nothing cached here, so the 503).
    let now = Instant::now();
    for _ in 1..route::phone_share(3) {
        crate::music_net::PhoneAsks::record(&wifi.phone_calls, first, now);
    }
    let unseen = json!({"bssids": ["00:00:5e:00:53:06"]});
    let (status, body) = lookup(&app, &first_token, &unseen).await;
    assert_eq!(status, StatusCode::SERVICE_UNAVAILABLE, "{body}");
    assert_eq!(app.fetch.count(), 1, "Apple asked past the share");
    // A cached BSSID still answers, with the wait.
    let both = json!({"bssids": ["00:00:5e:00:53:01", "00:00:5e:00:53:06"]});
    let (status, body) = lookup(&app, &first_token, &both).await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(body["aps"].as_array().unwrap().len(), 1);
    assert!(body["retry_after_s"].as_u64().unwrap() > 0);
    // Cells too: the 503.
    let (status, _) = lookup(
        &app,
        &first_token,
        &json!({"cells": ["gsm:001:01:1001:5001"]}),
    )
    .await;
    assert_eq!(status, StatusCode::SERVICE_UNAVAILABLE);
    assert_eq!(app.fetch.count(), 1);

    // The second phone is still served from Apple.
    let (status, _) = lookup(&app, &second_token, &unseen).await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(app.fetch.count(), 2);
}

/// Only a request that calls Apple counts against the phone's share: cells answered from the cache leave its Wi-Fi share.
#[tokio::test]
async fn cells_from_the_cache_leave_the_share() {
    let contract = contract();
    let app = TestApp::new().await;
    let (first, token) = phone_on(&app).await;
    phone_on(&app).await;
    phone_on(&app).await;
    let wifi = app.state.integrations.wifi_lookup.clone();
    let cells = &contract["request_cells"];
    app.fetch.answer(APPLE_URL, 200, apple_cell("cells_fresh"));
    assert_eq!(lookup(&app, &token, cells).await.0, StatusCode::OK);
    assert_eq!(app.fetch.count(), 1);
    // One short of the share (three phones: 20), the first call included.
    let now = Instant::now();
    for _ in 2..route::phone_share(3) {
        crate::music_net::PhoneAsks::record(&wifi.phone_calls, first, now);
    }
    // Cached cells: answered, Apple not called, nothing counted.
    for _ in 0..2 {
        assert_eq!(lookup(&app, &token, cells).await.0, StatusCode::OK);
    }
    assert_eq!(app.fetch.count(), 1);
    // The Wi-Fi share's last call is still there.
    app.fetch.answer(APPLE_URL, 200, apple("fresh"));
    let (status, body) = lookup(&app, &token, &contract["request"]).await;
    assert_eq!(status, StatusCode::OK, "{body}");
    assert_eq!(app.fetch.count(), 2);
    // Now spent: an uncached BSSID is the cache alone.
    let unseen = json!({"bssids": ["00:00:5e:00:53:06"]});
    assert_eq!(
        lookup(&app, &token, &unseen).await.0,
        StatusCode::SERVICE_UNAVAILABLE
    );
    assert_eq!(app.fetch.count(), 2);
}

/// Rows a lookup in flight wrote after the last usable phone's location Off go with the next sweep.
#[tokio::test]
async fn the_sweep_empties_the_cache_without_a_usable_phone() {
    let contract = contract();
    let app = TestApp::new().await;
    let (id, token) = phone_on(&app).await;
    app.fetch.answer(APPLE_URL, 200, apple("fresh"));
    assert_eq!(
        lookup(&app, &token, &contract["request"]).await.0,
        StatusCode::OK
    );
    let wifi = app.state.integrations.wifi_lookup.clone();
    wifi.prune(&app.state, Prune::Sweep).await;
    let cached = cache_rows(&app).await;
    assert!(cached > 0, "a usable phone keeps the cache");
    // Location off without the save's emptying: the rows stand in for a late write.
    set_location(&app, id, "off").await;
    assert_eq!(cache_rows(&app).await, cached);
    wifi.prune(&app.state, Prune::Sweep).await;
    assert_eq!(cache_rows(&app).await, 0);
}
