//! Design 125 step 2's tests: the shared contract (`testdata/wifi_lookup/contract.json`, compared with the launcher's
//! copy), the server's own rows (`testdata/wifi_lookup/server.json`, written by `scripts/wifi-lookup-fixtures.py`) and
//! the flows through the real routes. No test reaches Apple: every call goes to the canned fetch or a local fake
//! SOCKS server.

use axum::body::Body;
use axum::http::{Method, Request, StatusCode, header};
use serde_json::{Value, json};
use tokio::time::Instant;

use super::cache::{self, CacheKey, Cached};
use super::codec;
use super::route::{self, APPLE_URL, Failure, Pauses};
use super::{Proxy, WifiLookup};
use crate::integration_health::{self, Class, Title};
use crate::integrations::{Integration, Prune};
use crate::tests::TestApp;

fn read(path: &str) -> Value {
    serde_json::from_str(&std::fs::read_to_string(path).unwrap()).unwrap()
}

fn contract() -> Value {
    read("testdata/wifi_lookup/contract.json")
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

/// The contract's months: `2026-11` is this month, `2026-07` four months before it.
fn contract_months(mut body: Value) -> Value {
    let now = cache::month_text(this_month());
    let stale = cache::month_text(this_month() - 4);
    if let Some(rows) = body.get_mut("aps").and_then(Value::as_array_mut) {
        for row in rows {
            let month = row["month"].as_str().unwrap().to_string();
            row["month"] = json!(if month == now {
                "2026-11".to_string()
            } else if month == stale {
                "2026-07".to_string()
            } else {
                month
            });
        }
    }
    body
}

async fn phone_on(app: &TestApp) -> (i64, String) {
    let (id, token) = app.enrolled_device("phone").await;
    assert!(super::set_on(&app.state, id, true).await.unwrap());
    (id, token)
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

#[test]
fn the_contract_is_the_launchers() {
    let ours = std::fs::read("testdata/wifi_lookup/contract.json").unwrap();
    let theirs =
        std::fs::read("../launcher/app/src/test/resources/wifi_lookup/contract.json").unwrap();
    assert!(
        ours == theirs,
        "testdata/wifi_lookup/contract.json differs from the launcher's copy"
    );
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
            "limit" => {
                for _ in 0..route::PHONE_LOOKUPS_AN_HOUR {
                    assert_eq!(lookup(&app, &token, request).await.0, StatusCode::OK);
                }
                // The cache answered all but the first.
                assert_eq!(app.fetch.count(), 1);
                lookup(&app, &token, request).await
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
                assert_eq!(one.0, StatusCode::OK, "{case}: the first");
                assert!(
                    two.0 == StatusCode::OK || two.0 == StatusCode::TOO_MANY_REQUESTS,
                    "{case}: the second {two:?}"
                );
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
        assert_eq!(contract_months(body), row["body"], "{case}");
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
    assert!(html.contains("id=\"wifi-lookup\"") && html.contains("Wi-Fi lookup at Apple"));
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
    // Two months on: still the cache's (the negative ran out: 03 is asked alone).
    sqlx::query("UPDATE wifi_lookup_aps SET fetched_month = fetched_month - 2")
        .execute(&app.db)
        .await
        .unwrap();
    app.fetch.clear_calls();
    lookup(&app, &token, &request).await;
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
            .all(|r| r["month"] == cache::month_text(this_month()))
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
    // A mangled key is refused, never replaced.
    std::fs::write(&file, "not a key").unwrap();
    assert!(
        WifiLookup::for_tests(app.fetch.clone())
            .key(&app.state)
            .await
            .is_err()
    );
    assert_eq!(std::fs::read_to_string(&file).unwrap(), "not a key");
}

/// A5: the caps (negatives and positives each their own, the least recently used first), the sweep's lifetime and the
/// last phone with the switch on deleted.
#[tokio::test]
async fn the_caps_the_lifetime_and_a_deleted_phone() {
    let app = TestApp::new().await;
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
    let (on_phone, _) = phone_on(&app).await;
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
    // The asked one, then the surrounding (16).
    assert_eq!(body["aps"].as_array().unwrap().len(), 17);
    assert_eq!(body["aps"][0]["bssid"], "00:00:5e:00:53:14");
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
/// unreadable setting sends nothing at all.
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
        let answer = wifi.lookup(&app.state, &wanted).await;
        assert_eq!(answer, Err(route::PAUSE_STEPS[0]), "{proxy:?}");
        assert_eq!(wifi.health().last_error.map(|(_, c)| c), Some("proxy"));
        let requests = app.fetch.requests.lock().unwrap().clone();
        match proxy {
            Proxy::Socks(url) => {
                assert_eq!(requests.len(), 1);
                assert_eq!(requests[0].proxy.as_deref(), Some(url.as_str()));
            }
            _ => assert!(requests.is_empty()),
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
            for word in ["device", "bssid", "lat", "lng", "position", "mac"] {
                assert!(
                    !call.contains(word),
                    "{}: a log line names {word}: {call}",
                    path.display()
                );
            }
        }
    }
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
