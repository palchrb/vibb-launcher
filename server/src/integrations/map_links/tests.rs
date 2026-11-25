//! Design 110's tests: the shared contract (`contracts/integrations/map_links/contract.json`, which the launcher runs
//! too), the server's own rows (`testdata/map_links/server.json`) and the flows through the real routes.

use axum::http::{Method, StatusCode};
use serde_json::{Value, json};

use super::preview::{self, Outcome};
use super::{clean, links, page, resolve};
use crate::integrations::Integration;
use crate::tests::music::Canned;
use crate::tests::{TestApp, assert_redirect};

fn read(path: impl AsRef<std::path::Path>) -> Value {
    serde_json::from_str(&std::fs::read_to_string(path).unwrap()).unwrap()
}

fn contract() -> Value {
    read(crate::contracts::path(
        "integrations/map_links/contract.json",
    ))
}

fn server_rows() -> Value {
    read("testdata/map_links/server.json")
}

/// A `place` row with its `null`s dropped (the answer leaves an unknown name or address out).
fn without_nulls(value: &Value) -> Value {
    match value {
        Value::Object(map) => Value::Object(
            map.iter()
                .filter(|(_, v)| !v.is_null())
                .map(|(k, v)| (k.clone(), without_nulls(v)))
                .collect(),
        ),
        other => other.clone(),
    }
}

#[test]
fn the_link_rules_follow_the_contract() {
    let contract = contract();
    for row in contract["short_links"].as_array().unwrap() {
        let url = row["url"].as_str().unwrap();
        assert_eq!(
            links::short_link(url).as_deref(),
            row["short"].as_str(),
            "short_links: {url}"
        );
    }
    for row in contract["long_hosts"].as_array().unwrap() {
        let url = row["url"].as_str().unwrap();
        assert_eq!(
            links::long_map_url(url),
            row["map"].as_bool().unwrap(),
            "long_hosts: {url}"
        );
    }
    for row in contract["feature_ids"].as_array().unwrap() {
        let url = row["url"].as_str().unwrap();
        assert_eq!(
            links::feature_id(url).as_deref(),
            row["ftid"].as_str(),
            "feature_ids: {url}"
        );
    }
}

#[test]
fn the_report_is_cleaned_as_the_contract_says() {
    let contract = contract();
    for row in contract["clean"].as_array().unwrap() {
        let out = clean(&row["in"]).unwrap_or(Value::Null);
        assert_eq!(out, row["out"], "clean: {}", row["in"]);
    }
    // Every report row is kept as it is.
    for key in [
        "report",
        "report_steps_left",
        "report_hidden",
        "report_held_by_maps",
    ] {
        assert_eq!(
            clean(&contract[key]).as_ref(),
            Some(&contract[key]),
            "{key}"
        );
    }
}

#[test]
fn every_preview_row_is_judged_as_recorded() {
    for row in server_rows()["preview"].as_array().unwrap() {
        let name = row["name"].as_str().unwrap();
        let (outcome, place) = preview::judge(
            row["status"].as_u64().unwrap() as u16,
            row["location"].as_str(),
            row["content_type"].as_str(),
            row["body"].as_str().unwrap().as_bytes(),
            row["ftid"].as_str().unwrap(),
            row["text"].as_str(),
        );
        assert_eq!(outcome.code(), row["outcome"].as_str().unwrap(), "{name}");
        let place = place
            .map(|p| serde_json::to_value(p).unwrap())
            .unwrap_or(Value::Null);
        assert_eq!(place, without_nulls(&row["place"]), "{name}");
    }
}

/// Code QA L6: design 114's rows 15-16 read from a report as the phone reads them (`setup_steps.json`'s `map_readings`,
/// which the launcher's `SetupStepsTest` runs through `playLinksDone` and `vibbLinksDone`).
#[test]
fn the_setup_readings_are_the_phones() {
    for row in read(crate::contracts::path("core/setup_steps.json"))["map_readings"]
        .as_array()
        .unwrap()
    {
        let name = row["name"].as_str().unwrap();
        let readings = super::setup_part(true, Some(&row["report"])).readings;
        for key in ["map_play_links", "map_vibb_links"] {
            let got = readings.iter().find(|(k, _)| *k == key).unwrap().1;
            assert_eq!(got, row[key].as_bool(), "{name}: {key}");
        }
    }
}

#[test]
fn the_pwa_lines_follow_the_rows() {
    for row in server_rows()["pwa_lines"].as_array().unwrap() {
        let name = row["name"].as_str().unwrap();
        let report = Some(&row["report"]).filter(|r| !r.is_null());
        let on = row["on"].as_bool().unwrap();
        // Design 114's map steps left by this report (a map app installed): their warning lines, not this group's.
        let steps_left = on
            && super::setup_part(on, report)
                .readings
                .iter()
                .any(|(_, done)| *done == Some(false));
        let got = page::line_code(on, report, steps_left)
            .map(|(code, orange)| (code.to_string(), orange));
        let want = row["line"]
            .as_str()
            .map(|code| (code.to_string(), row["orange"].as_bool().unwrap()));
        assert_eq!(got, want, "{name}");
    }
    for row in server_rows()["lookup_line"].as_array().unwrap() {
        let counts: Vec<(String, i64)> = row["counts"]
            .as_object()
            .unwrap()
            .iter()
            .map(|(k, v)| (k.clone(), v.as_i64().unwrap()))
            .collect();
        assert_eq!(
            resolve::lookup_broken(&counts),
            row["line"].as_bool().unwrap(),
            "{counts:?}"
        );
        for (outcome, _) in &counts {
            assert!(
                Outcome::ALL.iter().any(|o| o.code() == outcome),
                "{outcome}"
            );
        }
    }
}

/// A phone enrolled with its switch on.
async fn phone_on(app: &TestApp) -> (i64, String) {
    let (id, token) = app.enrolled_device("phone").await;
    assert!(super::set_on(&app.state, id, true).await.unwrap());
    (id, token)
}

async fn resolve(app: &TestApp, token: &str, body: Value) -> (StatusCode, Value) {
    let res = app
        .request(
            Method::POST,
            "/api/devices/map-links/resolve",
            Some(token),
            Some(body),
        )
        .await;
    let json = serde_json::from_slice(&res.body).unwrap_or(Value::Null);
    (res.status, json)
}

fn preview_row(name: &str) -> Value {
    server_rows()["preview"]
        .as_array()
        .unwrap()
        .iter()
        .find(|r| r["name"] == name)
        .unwrap()
        .clone()
}

/// The lookup's URL for `ftid` answered by the server row `name`.
fn answer_lookup(app: &TestApp, ftid: &str, name: &str) {
    let row = preview_row(name);
    app.fetch.canned(
        &preview::lookup_url(ftid, "no"),
        Canned {
            status: row["status"].as_u64().unwrap() as u16,
            body: row["body"].as_str().unwrap().as_bytes().to_vec(),
            location: row["location"].as_str().map(str::to_string),
            content_type: row["content_type"].as_str().map(str::to_string),
            ..Default::default()
        },
    );
}

#[tokio::test]
async fn the_hops_follow_the_rows() {
    for row in server_rows()["hops"].as_array().unwrap() {
        let name = row["name"].as_str().unwrap();
        let app = TestApp::new().await;
        let mut url = row["short"].as_str().unwrap().to_string();
        for answer in row["answers"].as_array().unwrap() {
            app.fetch.canned(
                &url,
                Canned {
                    status: answer["status"].as_u64().unwrap() as u16,
                    location: answer["location"].as_str().map(str::to_string),
                    ..Default::default()
                },
            );
            if let Some(next) = answer["location"].as_str().and_then(links::short_link) {
                url = next;
            }
        }
        let got = app
            .state
            .integrations
            .map_links
            .hops(row["short"].as_str().unwrap())
            .await;
        match row["outcome"].as_str().unwrap() {
            "long" => assert_eq!(got.as_deref().ok(), row["url"].as_str(), "{name}"),
            _ => assert_eq!(got, Err(resolve::Refusal::Dead), "{name}"),
        }
        // No hop past the third, and every one to a short link's own rebuilt address.
        let calls = app.fetch.calls.lock().unwrap().clone();
        assert!(calls.len() <= 3, "{name}: {calls:?}");
        assert!(
            calls
                .iter()
                .all(|c| links::short_link(c).as_deref() == Some(c.as_str())),
            "{name}: {calls:?}"
        );
    }
}

#[tokio::test]
async fn the_route_answers_the_contract() {
    let contract = contract();
    let requests = &contract["resolve"]["requests"];
    let short = requests["short"]["url"].as_str().unwrap();
    let ftid = requests["ftid"]["ftid"].as_str().unwrap();
    let hops = server_rows()["hops"].clone();
    let hop = |name: &str| {
        hops.as_array()
            .unwrap()
            .iter()
            .find(|r| r["name"] == name)
            .unwrap()["answers"][0]["location"]
            .as_str()
            .unwrap()
            .to_string()
    };
    for row in contract["resolve"]["answers"].as_array().unwrap() {
        if row["server"] == false {
            continue;
        }
        let case = row["case"].as_str().unwrap();
        let app = TestApp::new().await;
        let (id, token) = phone_on(&app).await;
        let request = requests[row["request"].as_str().unwrap()].clone();
        let request = match case {
            "short_place" => {
                app.fetch.redirect(short, 302, &hop("artbox"));
                answer_lookup(&app, ftid, "artbox");
                request
            }
            "short_url" => {
                app.fetch.redirect(short, 302, &hop("search"));
                request
            }
            "short_apple" => {
                app.fetch.redirect(short, 302, &hop("apple"));
                request
            }
            "ftid_place" => {
                answer_lookup(&app, ftid, "artbox");
                request
            }
            "ftid_none" => {
                answer_lookup(&app, ftid, "consent");
                request
            }
            "off" | "off_ftid" => {
                super::set_on(&app.state, id, false).await.unwrap();
                request
            }
            "not_short_link" => json!({"url": "https://www.google.com/maps/place/X"}),
            "dead" => {
                app.fetch.answer(short, 404, "");
                request
            }
            "busy" => {
                // 30 resolves an hour, then busy (the cache answers all but the first without a call).
                app.fetch.redirect(short, 302, &hop("search"));
                for _ in 0..resolve::PHONE_RESOLVES_AN_HOUR {
                    assert_eq!(
                        resolve(&app, &token, request.clone()).await.0,
                        StatusCode::OK
                    );
                }
                assert_eq!(app.fetch.count(), 1);
                request
            }
            "unreachable" => request,
            other => panic!("a case the test doesn't know: {other}"),
        };
        let (status, body) = resolve(&app, &token, request).await;
        assert_eq!(
            u64::from(status.as_u16()),
            row["status"].as_u64().unwrap(),
            "{case}: {body}"
        );
        assert_eq!(body, row["body"], "{case}");
    }
}

#[tokio::test]
async fn refusals_and_limits() {
    let app = TestApp::new().await;
    let (_, token) = phone_on(&app).await;
    for body in [
        json!({}),
        json!({"url": "https://maps.app.goo.gl/x", "ftid": "0x1:0x2"}),
        json!({"ftid": "not an id"}),
        json!("https://maps.app.goo.gl/x"),
    ] {
        assert_eq!(
            resolve(&app, &token, body.clone()).await,
            (StatusCode::BAD_REQUEST, json!({"error": "invalid"})),
            "{body}"
        );
    }
    // Over 1 KB is refused before it is read.
    let long = json!({"ftid": "0x1:0x2", "text": "x".repeat(2000)});
    assert_eq!(
        resolve(&app, &token, long).await.0,
        StatusCode::PAYLOAD_TOO_LARGE
    );
    // http is refused: the phone upgrades it first.
    assert_eq!(
        resolve(&app, &token, json!({"url": "http://maps.app.goo.gl/AbC"})).await,
        (
            StatusCode::UNPROCESSABLE_ENTITY,
            json!({"error": "not_short_link"})
        )
    );
    // No device token, no answer.
    let res = app
        .request(
            Method::POST,
            "/api/devices/map-links/resolve",
            None,
            Some(json!({"url": "https://maps.app.goo.gl/AbC"})),
        )
        .await;
    assert_eq!(res.status, StatusCode::UNAUTHORIZED);
}

#[tokio::test]
async fn a_third_caller_is_busy_at_once_and_a_held_call_runs_out() {
    let app = TestApp::new().await;
    let (_, token) = phone_on(&app).await;
    app.fetch.hold();
    let ask = |n: u32| {
        let app = &app;
        let token = token.clone();
        async move {
            resolve(
                app,
                &token,
                json!({"url": format!("https://maps.app.goo.gl/Held{n}")}),
            )
            .await
        }
    };
    let first = ask(1);
    let second = ask(2);
    let third = async {
        app.fetch.wait_in_flight().await;
        while app
            .state
            .integrations
            .map_links
            .gate
            .admit
            .available_permits()
            > 0
        {
            tokio::time::sleep(std::time::Duration::from_millis(1)).await;
        }
        // One in flight, one waiting at the gate: the third is told at once.
        let started = std::time::Instant::now();
        let answer = ask(3).await;
        assert!(started.elapsed() < std::time::Duration::from_millis(500));
        answer
    };
    let (first, second, third) = tokio::join!(first, second, third);
    assert_eq!(
        third,
        (StatusCode::TOO_MANY_REQUESTS, json!({"error": "busy"}))
    );
    // The held ones run out at the deadline: unreachable.
    for answer in [first, second] {
        assert_eq!(
            answer,
            (
                StatusCode::SERVICE_UNAVAILABLE,
                json!({"error": "unreachable"})
            )
        );
    }
    // A feature id that runs out is no error: the phone opens its fallback.
    let ftid = json!({"ftid": "0x46416db4338a09ff:0x7d7d788043d1f40e", "text": "Artbox AS"});
    assert_eq!(
        resolve(&app, &token, ftid).await,
        (StatusCode::OK, json!({}))
    );
    app.fetch.release();
    let counts = resolve::recent_counts(&app.db).await.unwrap();
    assert_eq!(counts, vec![("unreachable".to_string(), 1)]);
    assert_eq!(
        app.state
            .integrations
            .map_links
            .health()
            .last_error
            .map(|(_, c)| c),
        Some("unreachable")
    );
}

#[tokio::test]
async fn an_answer_is_kept_for_minutes_and_the_counter_breaks_the_line() {
    let contract = contract();
    let app = TestApp::new().await;
    let (id, token) = phone_on(&app).await;
    let ftid = contract["resolve"]["requests"]["ftid"].clone();
    let id_text = ftid["ftid"].as_str().unwrap();
    answer_lookup(&app, id_text, "artbox");
    for _ in 0..3 {
        assert_eq!(resolve(&app, &token, ftid.clone()).await.0, StatusCode::OK);
    }
    // One call: the cache answers the rest, and the lookup is counted once.
    assert_eq!(app.fetch.count(), 1);
    assert_eq!(app.state.integrations.map_links.cache.len(), 1);
    assert!(app.state.integrations.map_links.health().last_ok.is_some());
    // Only the feature id leaves the server, never the link's text.
    let calls = app.fetch.calls.lock().unwrap().clone();
    assert!(calls.iter().all(|c| !c.contains("Artbox")), "{calls:?}");

    // Three consent walls (other ids), and the group says the lookup is broken once nothing was found.
    let cookie = app.admin_cookie().await;
    let page = format!("/devices/{id}/apps");
    for n in 1..=3 {
        let other = format!("0x46416db4338a09ff:0x{n}");
        answer_lookup(&app, &other, "consent");
        assert_eq!(
            resolve(&app, &token, json!({"ftid": other})).await,
            (StatusCode::OK, json!({}))
        );
    }
    let html = app.get_page(&page, &cookie).await.text();
    assert!(
        !html.contains("data-warning=\"map-lookup\""),
        "a found answer today keeps it quiet"
    );
    sqlx::query("DELETE FROM map_link_lookups WHERE outcome = 'found'")
        .execute(&app.db)
        .await
        .unwrap();
    let html = app.get_page(&page, &cookie).await.text();
    assert!(html.contains("data-warning=\"map-lookup\""));
    // Kept 30 days.
    sqlx::query(
        "INSERT INTO map_link_lookups (day, outcome, n) VALUES (date('now', '-40 days'), 'found', 1)",
    )
    .execute(&app.db)
    .await
    .unwrap();
    answer_lookup(&app, "0x46416db4338a09ff:0x9", "consent");
    resolve(&app, &token, json!({"ftid": "0x46416db4338a09ff:0x9"})).await;
    let old: i64 = sqlx::query_scalar(
        "SELECT COUNT(*) FROM map_link_lookups WHERE day < date('now', '-30 days')",
    )
    .fetch_one(&app.db)
    .await
    .unwrap();
    assert_eq!(old, 0);
}

#[tokio::test]
async fn the_policy_and_the_group_follow_the_switch() {
    let contract = contract();
    let app = TestApp::new().await;
    let (id, token) = app.enrolled_device("phone").await;
    let policy = async |token: &str| -> Value {
        app.request(Method::GET, "/api/devices/policy", Some(token), None)
            .await
            .json()
    };
    assert_eq!(policy(&token).await["map_links"], contract["policy"]["off"]);
    let cookie = app.admin_cookie().await;
    let page = format!("/devices/{id}/apps");
    // Not offered before the phone names a target.
    assert!(
        !app.get_page(&page, &cookie)
            .await
            .text()
            .contains("id=\"map-links\"")
    );
    // Organic Maps installed (design 114's `map_links` input reads the installed apps).
    let organic = json!([{"package_name": "app.organicmaps", "label": "Organic Maps"}]);
    app.post_status(
        &token,
        json!({"map_links": contract["report_steps_left"], "installed_apps": organic}),
    )
    .await;
    let html = app.get_page(&page, &cookie).await.text();
    assert!(html.contains("id=\"map-links\"") && html.contains("Open map links in Organic Maps"));
    // Off: no setup step.
    assert!(!html.contains("data-setup-warning=\"map_play_links\""));
    assert!(html.contains(&format!("action=\"/devices/{id}/map-links#map-links\"")));
    // The save returns to the group and the policy carries it.
    let res = app
        .request_form(
            Method::POST,
            &format!("/devices/{id}/map-links"),
            Some(&cookie),
            &[("on", "on")],
        )
        .await;
    assert_redirect(&res, &page, Some("map-links"));
    assert_eq!(policy(&token).await["map_links"], contract["policy"]["on"]);
    // Design 114 §2.6: the two steps left are 114's lines in the group, to their rows on Device › Oppsett, and no line
    // of the group's own says them again.
    let html = app.get_page(&page, &cookie).await.text();
    for key in ["map_play_links", "map_vibb_links"] {
        assert!(
            html.contains(&format!("data-setup-warning=\"{key}\"")),
            "{key}"
        );
        assert!(
            html.contains(&format!("href=\"/devices/{id}/setup#step-{key}\"")),
            "{key}"
        );
    }
    assert!(!html.contains("data-line="));
    let setup = app
        .get_page(&format!("/devices/{id}/setup"), &cookie)
        .await
        .text();
    assert!(
        setup.contains("data-setup-step=\"map_play_links\"")
            && setup.contains("data-setup-step=\"map_vibb_links\"")
    );
    // In Norwegian.
    let html = app
        .get_page(&format!("{page}?lang=nb"), &cookie)
        .await
        .text();
    assert!(
        html.contains("Åpne kartlenker i Organic Maps")
            && html.contains("Play-tjenester tar kartlenkene")
    );
    // From the Apps tab the form carries `from`, and the save goes back there.
    let res = app
        .request_form(
            Method::POST,
            &format!("/devices/{id}/map-links"),
            Some(&cookie),
            &[("from", "apps")],
        )
        .await;
    assert_redirect(&res, &format!("{page}?from=apps"), Some("map-links"));
    assert_eq!(policy(&token).await["map_links"], contract["policy"]["off"]);
    // Off, with Play services' links still off: the quiet note.
    app.post_status(&token, json!({"map_links": contract["report"]}))
        .await;
    let html = app.get_page(&page, &cookie).await.text();
    assert!(html.contains("data-line=\"off_play\""));
    // An unknown phone.
    let res = app
        .request_form(
            Method::POST,
            "/devices/99999/map-links",
            Some(&cookie),
            &[("on", "on")],
        )
        .await;
    assert_eq!(res.status, StatusCode::NOT_FOUND);
}

#[tokio::test]
async fn a_pause_report_keeps_the_last_reach_and_its_own_hosts() {
    let contract = contract();
    let app = TestApp::new().await;
    let (id, token) = phone_on(&app).await;
    app.post_status(&token, json!({"map_links": contract["report_steps_left"]}))
        .await;
    // During a pause everything is unhidden: its reach isn't the phone's.
    app.post_status(
        &token,
        json!({"restrictions_paused": true, "map_links": contract["report"]}),
    )
    .await;
    let kept = super::report(&app.db, id).await.unwrap().unwrap();
    assert_eq!(kept["reach"], contract["report_steps_left"]["reach"]);
    // Code QA M1: the hosts are the paused report's own (step 2 is done in a pause or the hold), so design 114's row 16
    // ticks off with the phone's.
    assert_eq!(kept["vibb_hosts"], contract["report"]["vibb_hosts"]);
    assert_eq!(
        kept["play_services_links"],
        contract["report"]["play_services_links"]
    );
    let readings = super::setup_part(true, Some(&kept)).readings;
    assert!(readings.contains(&("map_vibb_links", Some(true))));
    app.post_status(&token, json!({"map_links": contract["report"]}))
        .await;
    assert_eq!(
        super::report(&app.db, id).await.unwrap().unwrap(),
        contract["report"]
    );
    // A report that isn't an object changes nothing.
    app.post_status(&token, json!({"map_links": "x"})).await;
    assert_eq!(
        super::report(&app.db, id).await.unwrap().unwrap(),
        contract["report"]
    );
}

#[test]
fn the_integration_is_named_and_healthy_from_the_start() {
    let source = std::sync::Arc::new(crate::tests::music::CannedFetch::default());
    let links = super::MapLinks::for_tests(source);
    assert_eq!(links.name(), "map_links");
    assert_eq!(links.health(), crate::integrations::Health::default());
}

/// Code QA M2: a short link's lookup that got no answer isn't kept with the long link - the next tap asks again and
/// gets the place, without asking the short link again; Google's answer without a place is kept a minute.
#[tokio::test]
async fn a_lookup_without_an_answer_is_asked_again() {
    let contract = contract();
    let app = TestApp::new().await;
    let (_, token) = phone_on(&app).await;
    let request = contract["resolve"]["requests"]["short"].clone();
    let short = request["url"].as_str().unwrap();
    let ftid = contract["resolve"]["requests"]["ftid"]["ftid"]
        .as_str()
        .unwrap();
    let long = server_rows()["hops"][0]["answers"][0]["location"]
        .as_str()
        .unwrap()
        .to_string();
    app.fetch.redirect(short, 302, &long);
    let lookup = preview::lookup_url(ftid, "no");
    app.fetch
        .fail(&lookup, crate::music_net::FetchError::Timeout);
    assert_eq!(
        resolve(&app, &token, request.clone()).await,
        (StatusCode::OK, json!({"url": long}))
    );
    answer_lookup(&app, ftid, "artbox");
    let (status, body) = resolve(&app, &token, request.clone()).await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(body["place"]["name"], "Artbox AS");
    let calls = app.fetch.calls.lock().unwrap().clone();
    assert_eq!(
        calls.iter().filter(|c| c.as_str() == short).count(),
        1,
        "{calls:?}"
    );
    // Google's answer without a place: kept a minute (the third tap doesn't ask).
    let app = TestApp::new().await;
    let (_, token) = phone_on(&app).await;
    app.fetch.redirect(short, 302, &long);
    answer_lookup(&app, ftid, "consent");
    assert_eq!(
        resolve(&app, &token, request.clone()).await.1,
        json!({"url": long})
    );
    answer_lookup(&app, ftid, "artbox");
    assert_eq!(resolve(&app, &token, request).await.1, json!({"url": long}));
    assert_eq!(app.fetch.count(), 2);
}

/// The emulator gate (2026-11-18): Google's wall pauses the lookups for an hour - nothing more is sent, each ask
/// counted `blocked`, and the PWA's line shows; and at most 10 calls go out in any 10 minutes.
#[tokio::test]
async fn the_wall_pauses_lookups_and_a_burst_is_capped() {
    let app = TestApp::new().await;
    let (id, token) = phone_on(&app).await;
    let first = "0x46416db4338a09ff:0x1";
    answer_lookup(&app, first, "wall_403");
    assert_eq!(
        resolve(&app, &token, json!({"ftid": first})).await,
        (StatusCode::OK, json!({}))
    );
    assert_eq!(app.fetch.count(), 1);
    // Another id during the pause: nothing sent.
    let second = "0x46416db4338a09ff:0x2";
    answer_lookup(&app, second, "artbox");
    assert_eq!(
        resolve(&app, &token, json!({"ftid": second})).await,
        (StatusCode::OK, json!({}))
    );
    assert_eq!(app.fetch.count(), 1);
    let counts = resolve::recent_counts(&app.db).await.unwrap();
    assert_eq!(counts, vec![("blocked".to_string(), 2)]);
    let cookie = app.admin_cookie().await;
    let html = app
        .get_page(&format!("/devices/{id}/apps"), &cookie)
        .await
        .text();
    assert!(html.contains("data-warning=\"map-lookup\""));

    // The burst: ten short links go out, the eleventh is busy.
    let app = TestApp::new().await;
    let (_, token) = phone_on(&app).await;
    let long = "https://www.google.com/maps/search/Kiwi";
    for n in 0..=resolve::BURST_CALLS {
        let short = format!("https://maps.app.goo.gl/Burst{n}");
        app.fetch.redirect(&short, 302, long);
        let (status, _) = resolve(&app, &token, json!({"url": short})).await;
        let want = if n < resolve::BURST_CALLS {
            StatusCode::OK
        } else {
            StatusCode::TOO_MANY_REQUESTS
        };
        assert_eq!(status, want, "{n}");
    }
    assert_eq!(app.fetch.count(), resolve::BURST_CALLS);
}
