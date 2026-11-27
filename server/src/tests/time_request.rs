//! Design r13 §2.5-2.8: `POST /api/devices/time-request` - the kid's "Be om mer tid" from the
//! phone's notification: the three answers of the shared contract `contracts/core/time_request.json`,
//! one row per request id, the day's cap and a retried third request, the news rule, a stale
//! request, the push and each parent's "Asks for more time" switch. Design 100 §14: the answer card
//! on the summary until a parent answers or the day ends, each answer, D9's pause under a rule, the
//! first answer winning, a fitting lift answering, a newer request superseding, the policy's answer
//! (the contract's `policy_*` rows) and migration 0149's backfill.

use axum::http::{Method, StatusCode};
use chrono::{Duration, Utc};
use serde_json::{Value, json};

use crate::display_time::Zone;

use super::web_push::{ENDPOINT, subscribe};
use super::{TestApp, assert_redirect};

/// The launcher's body and the three answers (`TimeRequestsTest` encodes and decodes exactly
/// these).
const TIME_REQUEST: &str = crate::contracts::contract!("core/time_request.json");

const PAPPAS_ENDPOINT: &str = "https://web.push.apple.com/QK1-pappas-fake-endpoint";

fn contract() -> Value {
    serde_json::from_str(TIME_REQUEST).unwrap()
}

/// A request `minutes_ago`, with an id made from `n`.
fn body(n: u32, minutes_ago: i64) -> Value {
    json!({
        "id": format!("{n:032x}"),
        "asked_at_ms": (Utc::now() - Duration::minutes(minutes_ago)).timestamp_millis(),
    })
}

async fn post(app: &TestApp, token: &str, body: Value) -> (StatusCode, Value) {
    let res = app
        .request(
            Method::POST,
            "/api/devices/time-request",
            Some(token),
            Some(body),
        )
        .await;
    let json = if res.status == StatusCode::OK {
        res.json()
    } else {
        Value::Null
    };
    (res.status, json)
}

async fn count(app: &TestApp, device: i64) -> i64 {
    sqlx::query_scalar(
        "SELECT COUNT(*) FROM parent_events WHERE device_id = ? AND kind = 'extra_time_request'",
    )
    .bind(device)
    .fetch_one(&app.db)
    .await
    .unwrap()
}

/// qa-r13-rule-card-code Q-8: a Phone Setup zone whose day began at least an hour ago and ends at
/// least two hours from now, so "a minute ago" and "31 minutes ago" are today and "26 hours ago"
/// is before it, at any hour the suite runs (one of three zones 6-8 hours apart always is).
async fn steady_zone(app: &TestApp) -> Zone {
    let now = Utc::now();
    for name in ["Europe/Oslo", "Asia/Tokyo", "America/New_York"] {
        let zone = Zone::from_setting(name);
        let into = now - zone.start_of_today(now);
        if into >= Duration::hours(1) && into <= Duration::hours(22) {
            sqlx::query("UPDATE provisioning_settings SET time_zone = ? WHERE id = 1")
                .bind(name)
                .execute(&app.db)
                .await
                .unwrap();
            assert_eq!(Zone::load(&app.db, crate::i18n::Lang::En).await, zone);
            return zone;
        }
    }
    unreachable!("three zones 6-8 hours apart: one is between 01:00 and 22:00")
}

/// A status report saying how much screen time is left today: the day is Phone Setup's today
/// (Q-8: never the UTC date).
async fn report(app: &TestApp, token: &str, lock_reason: &str, used: i64, budget: i64) {
    let day = Zone::load(&app.db, crate::i18n::Lang::En)
        .await
        .today(Utc::now());
    let res = app
        .request(
            Method::POST,
            "/api/devices/status",
            Some(token),
            Some(json!({
                "lock_reason": lock_reason,
                "kiosk_engaged": true,
                "time_state": {
                    "day": day.to_string(),
                    "used_minutes": used, "budget_minutes": budget, "extra_minutes": 0,
                    "lock_reason": lock_reason, "calls_blocked": false, "lifts_active": [],
                },
            })),
        )
        .await;
    assert_eq!(res.status, StatusCode::NO_CONTENT, "{}", res.text());
}

/// Mamma and Pappa each with a subscription; Pappa's "Asks for more time" off.
async fn two_parents(app: &TestApp) -> String {
    let mamma = app.admin_cookie().await;
    subscribe(app, &mamma, ENDPOINT).await;
    let pappa = app.admin_cookie_as("pappa", "Pappa").await;
    subscribe(app, &pappa, PAPPAS_ENDPOINT).await;
    let on: Vec<(&str, &str)> = crate::parent_events::Kind::switches()
        .into_iter()
        .filter(|(name, _)| *name != "push_extra_time")
        .map(|(name, _)| (name, "on"))
        .collect();
    app.request_form(Method::POST, "/notifications/settings", Some(&pappa), &on)
        .await;
    mamma
}

/// The endpoints the sender pushes to now, and the titles.
async fn pushed(app: &TestApp) -> Vec<(String, String)> {
    crate::push::send_due(&app.state, Utc::now()).await.unwrap();
    let mut to: Vec<(String, String)> = app
        .pushes
        .take()
        .into_iter()
        .map(|r| {
            let title = super::web_push::payload(&r)["notification"]["title"]
                .as_str()
                .unwrap_or_default()
                .to_string();
            (r.endpoint, title)
        })
        .collect();
    to.sort();
    to
}

/// The contract's request (its instant moved to a minute ago) and every answer row: the first
/// post, a fourth of the day, one asked yesterday.
#[tokio::test]
async fn the_contract_answers() {
    let app = TestApp::new().await;
    steady_zone(&app).await;
    let (_, token) = app.enrolled_device("Anna").await;
    let contract = contract();
    let answers = contract["answers"].as_array().unwrap();
    let mut request = contract["request"].clone();
    request["asked_at_ms"] = json!((Utc::now() - Duration::minutes(1)).timestamp_millis());
    assert_eq!(
        post(&app, &token, request).await,
        (StatusCode::OK, answers[0].clone())
    );
    for n in 1..=2 {
        assert_eq!(post(&app, &token, body(n, 1)).await.1, answers[0]);
    }
    assert_eq!(
        post(&app, &token, body(9, 1)).await,
        (StatusCode::OK, answers[1].clone())
    );
    let yesterday = body(10, 26 * 60);
    assert_eq!(
        post(&app, &token, yesterday).await,
        (StatusCode::OK, answers[2].clone())
    );
    // The handler's own encodings are the rows, byte for byte.
    use crate::handlers::device_api::TimeRequestAnswer;
    let encoded: Vec<Value> = [
        TimeRequestAnswer::ACCEPTED,
        TimeRequestAnswer::CAPPED,
        TimeRequestAnswer::STALE,
    ]
    .iter()
    .map(|a| serde_json::to_value(a).unwrap())
    .collect();
    assert_eq!(&encoded, answers);
}

/// The first request: one row, worded from the server's own report, a push for the parent with
/// the switch on and none for the one with it off; the same id twice is one row.
#[tokio::test]
async fn a_request_raises_one_event_and_pushes_by_each_parents_switch() {
    let app = TestApp::new().await;
    steady_zone(&app).await;
    let mamma = two_parents(&app).await;
    // Q-7: Mamma saved Notify Me About before the switch existed - a row that names no
    // `push_extra_time`: its default is on (migration 0096).
    sqlx::query(
        "INSERT INTO notification_prefs (admin_id) SELECT id FROM admin_users \
         WHERE id NOT IN (SELECT admin_id FROM notification_prefs)",
    )
    .execute(&app.db)
    .await
    .unwrap();
    let rows: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM notification_prefs")
        .fetch_one(&app.db)
        .await
        .unwrap();
    assert_eq!(rows, 2, "both parents have a row");
    let (id, token) = app.enrolled_device("Anna").await;
    report(&app, &token, "NONE", 10, 90).await;
    assert_eq!(
        post(&app, &token, body(1, 0)).await.1,
        json!({"accepted": true})
    );
    assert_eq!(count(&app, id).await, 1);
    let to = pushed(&app).await;
    assert_eq!(
        to,
        [(ENDPOINT.to_string(), "Anna: asks for more time".to_string())],
        "Mamma's switch is on, Pappa's off"
    );
    // A retried POST of the same id: one row, no second push.
    assert_eq!(
        post(&app, &token, body(1, 0)).await.1,
        json!({"accepted": true})
    );
    assert_eq!(count(&app, id).await, 1);
    assert!(pushed(&app).await.is_empty());
    // The inbox tells it from the report: 80 minutes left.
    let inbox = app.get_page("/notifications", &mamma).await.text();
    assert!(inbox.contains("Anna asks for more screen time"), "{inbox}");
    assert!(inbox.contains("1\u{a0}h 20\u{a0}min left"), "{inbox}");
    let target: String =
        sqlx::query_scalar("SELECT target FROM parent_events WHERE kind = 'extra_time_request'")
            .fetch_one(&app.db)
            .await
            .unwrap();
    assert_eq!(
        target,
        format!("/devices/{id}#ask"),
        "the push opens the card"
    );
    // Notify Me About lists the switch once, on by default.
    let settings = app.get_page("/notifications/settings", &mamma).await.text();
    assert_eq!(settings.matches("name=\"push_extra_time\"").count(), 1);
    assert!(settings.contains("Asks for more time"));
    assert_eq!(
        super::input_checked(&settings, "push_extra_time", "on"),
        Some(true)
    );
}

/// The day's cap (D5): the fourth request isn't stored and answers `capped`; a retry of the third
/// is taken, not counted as a fourth.
#[tokio::test]
async fn three_a_day_and_a_retried_third_is_no_fourth() {
    let app = TestApp::new().await;
    let (id, token) = app.enrolled_device("Anna").await;
    for n in 1..=3 {
        assert_eq!(
            post(&app, &token, body(n, 0)).await.1,
            json!({"accepted": true})
        );
    }
    assert_eq!(
        post(&app, &token, body(3, 0)).await.1,
        json!({"accepted": true}),
        "the third again"
    );
    assert_eq!(count(&app, id).await, 3);
    assert_eq!(
        post(&app, &token, body(4, 0)).await.1,
        json!({"accepted": false, "reason": "capped"})
    );
    assert_eq!(count(&app, id).await, 3, "the fourth isn't stored");
    // Another phone has its own three.
    let (other, other_token) = app.enrolled_device("Ola").await;
    assert_eq!(
        post(&app, &other_token, body(4, 0)).await.1,
        json!({"accepted": true})
    );
    assert_eq!(count(&app, other).await, 1);
}

/// Asked 31 minutes ago: a row, no push (old news); asked yesterday: no row, `stale`; a phone clock
/// ahead counts as now; a bad id or body is 400.
#[tokio::test]
async fn news_stale_and_refused_bodies() {
    let app = TestApp::new().await;
    steady_zone(&app).await;
    let cookie = app.admin_cookie().await;
    subscribe(&app, &cookie, ENDPOINT).await;
    let (id, token) = app.enrolled_device("Anna").await;
    assert_eq!(
        post(&app, &token, body(1, 31)).await.1,
        json!({"accepted": true})
    );
    assert_eq!(count(&app, id).await, 1);
    assert!(pushed(&app).await.is_empty(), "no push after 30 min");
    assert_eq!(
        post(&app, &token, body(2, 26 * 60)).await.1,
        json!({"accepted": false, "reason": "stale"})
    );
    assert_eq!(count(&app, id).await, 1);
    // A future instant is stored as now.
    assert_eq!(
        post(&app, &token, body(3, -120)).await.1,
        json!({"accepted": true})
    );
    let at: String =
        sqlx::query_scalar("SELECT happened_at FROM parent_events WHERE dedup_key = ?")
            .bind(format!("{:032x}", 3))
            .fetch_one(&app.db)
            .await
            .unwrap();
    let at = crate::display_time::parse_db_utc(&at).unwrap();
    assert!(
        at <= Utc::now() && at > Utc::now() - Duration::minutes(1),
        "{at}"
    );
    for bad in [
        json!({"id": "0123456789ABCDEF0123456789ABCDEF", "asked_at_ms": 1}),
        json!({"id": "0123456789abcde", "asked_at_ms": 1}),
        json!({"id": "x".repeat(32), "asked_at_ms": 1}),
        json!({"id": "a".repeat(65), "asked_at_ms": 1}),
        json!({"asked_at_ms": 1}),
        json!({"id": "a".repeat(32)}),
        json!("nope"),
    ] {
        assert_eq!(
            post(&app, &token, bad.clone()).await.0,
            StatusCode::BAD_REQUEST,
            "{bad}"
        );
    }
    // Without a token, nothing.
    let res = app
        .request(
            Method::POST,
            "/api/devices/time-request",
            None,
            Some(body(5, 0)),
        )
        .await;
    assert_eq!(res.status, StatusCode::UNAUTHORIZED);
}

#[test]
fn request_ids_are_lowercase_hex_of_16_to_64() {
    use crate::handlers::device_api::time_request_id_ok;
    assert!(time_request_id_ok(&"a".repeat(16)));
    assert!(time_request_id_ok(&"0f".repeat(32)));
    assert!(!time_request_id_ok(&"a".repeat(15)));
    assert!(!time_request_id_ok(&"a".repeat(65)));
    assert!(!time_request_id_ok("0123456789abcdeG"));
    assert!(!time_request_id_ok(""));
}

/// Q-7 (mutation S4): a parent whose Notify Me About row was saved before migration 0096 - every
/// other switch explicit, one off - has "Asks for more time" on after it.
#[tokio::test]
async fn a_parent_saved_before_the_migration_has_the_switch_on() {
    let (db, _dir) = super::cleanup::migrated_before(96).await;
    let admin: i64 = sqlx::query_scalar(
        "INSERT INTO admin_users (username, password_hash) VALUES ('mamma', 'x') RETURNING id",
    )
    .fetch_one(&db)
    .await
    .unwrap();
    sqlx::query(
        "INSERT INTO notification_prefs (admin_id, push_pin_used, push_pin_lockout, \
         push_foreign_access, push_unseen_boot, push_low_battery, push_phone_off, push_phone_on, \
         push_zones) VALUES (?, 1, 1, 1, 1, 1, 0, 1, 1)",
    )
    .bind(admin)
    .execute(&db)
    .await
    .unwrap();
    super::cleanup::finish(&db).await;
    let on: bool =
        sqlx::query_scalar("SELECT push_extra_time FROM notification_prefs WHERE admin_id = ?")
            .bind(admin)
            .fetch_one(&db)
            .await
            .unwrap();
    assert!(on, "on by default for an existing parent (U7)");
}

/// Q-2: the cap counts the phone's own day - the zone its report names - not Phone Setup's.
/// Three requests stored in the window where the two days differ: the fourth is judged by the
/// phone's day (accepted when they were the phone's yesterday, capped when they are its today).
#[tokio::test]
async fn the_cap_counts_the_phones_own_day() {
    let app = TestApp::new().await;
    let setup = steady_zone(&app).await;
    let (id, token) = app.enrolled_device("Anna").await;
    let now = Utc::now();
    let setup_start = setup.start_of_today(now);
    // A zone 13 hours away from every candidate's: its day starts at least 5 minutes off Phone
    // Setup's, one way or the other.
    let (name, phone) = ["Pacific/Kiritimati", "Pacific/Niue"]
        .into_iter()
        .map(|name| (name, Zone::from_setting(name)))
        .find(|(_, zone)| (zone.start_of_today(now) - setup_start).num_minutes().abs() >= 5)
        .expect("two zones 25 hours apart can't both start their day with Phone Setup's");
    let phone_start = phone.start_of_today(now);
    let res = app
        .request(
            Method::POST,
            "/api/devices/status",
            Some(&token),
            Some(json!({
                "lock_reason": "NONE",
                "kiosk_engaged": true,
                "time_state": {
                    "day": phone.today(now).to_string(), "used_minutes": 10, "budget_minutes": 90,
                    "lock_reason": "NONE", "zone": name,
                },
            })),
        )
        .await;
    assert_eq!(res.status, StatusCode::NO_CONTENT, "{}", res.text());
    // Three requests a minute before the later of the two midnights: today for the zone whose
    // day began first, yesterday for the other.
    let at = setup_start.max(phone_start) - Duration::minutes(1);
    for n in 1..=3 {
        sqlx::query(
            "INSERT INTO parent_events (device_id, kind, dedup_key, title, summary, target, \
             happened_at) VALUES (?, 'extra_time_request', ?, '', '', '/devices', ?)",
        )
        .bind(id)
        .bind(format!("{n:032x}"))
        .bind(at.to_rfc3339_opts(chrono::SecondsFormat::Millis, true))
        .execute(&app.db)
        .await
        .unwrap();
    }
    let expected = if phone_start > setup_start {
        json!({"accepted": true})
    } else {
        json!({"accepted": false, "reason": "capped"})
    };
    assert_eq!(
        post(&app, &token, body(4, 0)).await.1,
        expected,
        "phone {name}"
    );
    // Without a zone in the report, Phone Setup's day counts.
    assert_eq!(crate::handlers::device_api::phone_zone(None, &setup), setup);
    let report = crate::time_state::TimeReport {
        zone: Some("Not/AZone".to_string()),
        ..Default::default()
    };
    assert_eq!(
        crate::handlers::device_api::phone_zone(Some(&report), &setup),
        setup
    );
}

// ---- Design 100 §14: the answer ----

/// The contract's request id (`time_request.json`'s `request.id`).
const RID: &str = "0123456789abcdef0123456789abcdef";

/// A parent's answer from the card: `value` is `15`, `30`, `60` or `no`.
async fn answer(
    app: &TestApp,
    cookie: &str,
    id: i64,
    request: &str,
    value: &str,
) -> super::TestResponse {
    app.request_form(
        Method::POST,
        &format!("/devices/{id}/time-request/answer"),
        Some(cookie),
        &[("request", request), ("answer", value)],
    )
    .await
}

/// The answer card on the summary (`None` without one).
fn card(html: &str) -> Option<&str> {
    let start = html.find("<section id=\"ask\"")?;
    let end = html[start..].find("</section>").map(|e| start + e)?;
    Some(&html[start..end])
}

/// The request's answer row: `(answer, lift_id)`.
async fn answer_row(app: &TestApp, device: i64, request: &str) -> Option<(String, Option<i64>)> {
    sqlx::query_as(
        "SELECT answer, lift_id FROM time_request_answers WHERE device_id = ? AND request_id = ?",
    )
    .bind(device)
    .bind(request)
    .fetch_optional(&app.db)
    .await
    .unwrap()
}

/// `served` (the policy's `time_policy`) is the contract's `row`: only the row's keys (and `answer`, which must be
/// absent when the row has none) compared, the instants the run's own - checked to be now and now + 12 h first.
#[track_caller]
fn assert_served(contract: &Value, row: &str, served: &Value) {
    let expected = &contract[row]["time_policy"];
    let mut served = served.clone();
    let now = Utc::now().timestamp_millis();
    let object = served.as_object_mut().unwrap();
    object.retain(|key, _| expected.get(key).is_some() || key == "answer");
    if let (Some(answer), Some(want)) = (object.get_mut("answer"), expected.get("answer")) {
        let at = answer["at_ms"].as_i64().unwrap();
        assert!((now - at).abs() < 120_000, "{row}: answered at {at}");
        answer["at_ms"] = want["at_ms"].clone();
    }
    if let (Some(lifts), Some(want)) = (
        object.get_mut("lifts").and_then(Value::as_array_mut),
        expected["lifts"].as_array(),
    ) {
        for (lift, want) in lifts.iter_mut().zip(want) {
            let at = lift["at_ms"].as_i64().unwrap();
            assert!((now - at).abs() < 120_000, "{row}: given at {at}");
            let expires = lift["expires_at_ms"].as_i64().unwrap();
            assert!(
                (expires - at - 12 * 3_600_000).abs() < 120_000,
                "{row}: sent 12 h"
            );
            lift["at_ms"] = want["at_ms"].clone();
            lift["expires_at_ms"] = want["expires_at_ms"].clone();
        }
    }
    assert_eq!(&served, expected, "{row}");
}

/// Design 100 §14.5: the contract's `policy_*` rows are what this server serves (with the instants of the run):
/// Pappa's "Nei", Mamma's "+30 min", "+30 min" from a parent with no display name (no `by`: never the login, D11),
/// and Mamma's Quick actions lift with no request (no `answer`). The
/// rows pin `answer`'s and a lift's keys (`policy_json_keys_snapshot` pins `time_policy`'s own).
#[tokio::test]
async fn the_contract_policies() {
    let contract = contract();
    assert_eq!(contract["request"]["id"], RID);
    for (row, parent, value) in [
        ("policy_no", Some(("pappa", "Pappa")), "no"),
        ("policy_yes", Some(("mamma", "Mamma")), "30"),
        ("policy_unnamed", None, "30"),
    ] {
        let app = TestApp::new().await;
        steady_zone(&app).await;
        let cookie = match parent {
            Some((username, name)) => app.admin_cookie_as(username, name).await,
            None => app.admin_cookie().await,
        };
        let (id, token) = app.enrolled_device("Emma").await;
        let mut request = contract["request"].clone();
        request["asked_at_ms"] = json!((Utc::now() - Duration::minutes(1)).timestamp_millis());
        assert_eq!(
            post(&app, &token, request).await.1,
            json!({"accepted": true})
        );
        let res = answer(&app, &cookie, id, RID, value).await;
        assert!(res.status.is_redirection(), "{row}: {}", res.text());
        assert_served(&contract, row, &app.policy(&token).await["time_policy"]);
    }
    // Code QA L8: a yes under a rule (D9's pause), then "End now": the answer stays a yes but names no lift and no
    // minutes, so the phone says nothing of time given.
    let app = TestApp::new().await;
    steady_zone(&app).await;
    let cookie = app.admin_cookie_as("mamma", "Mamma").await;
    let (id, token) = app.enrolled_device("Emma").await;
    report(&app, &token, "BEDTIME", 10, 90).await;
    let mut request = contract["request"].clone();
    request["asked_at_ms"] = json!((Utc::now() - Duration::minutes(1)).timestamp_millis());
    assert_eq!(
        post(&app, &token, request).await.1,
        json!({"accepted": true})
    );
    let res = answer(&app, &cookie, id, RID, "30").await;
    assert_redirect(&res, &format!("/devices/{id}?done=pause"), Some("quick"));
    let ended = app
        .request_form(
            Method::POST,
            &format!("/devices/{id}/lifts/1/end"),
            Some(&cookie),
            &[],
        )
        .await;
    assert!(ended.status.is_redirection(), "{}", ended.text());
    assert_served(
        &contract,
        "policy_ended",
        &app.policy(&token).await["time_policy"],
    );

    let app = TestApp::new().await;
    let cookie = app.admin_cookie_as("mamma", "Mamma").await;
    let (id, token) = app.enrolled_device("Emma").await;
    let lifted = app
        .request_form(
            Method::POST,
            &format!("/devices/{id}/lifts"),
            Some(&cookie),
            &[("target", "budget"), ("minutes", "30")],
        )
        .await;
    assert_redirect(&lifted, &format!("/devices/{id}?done=extra"), Some("quick"));
    assert_served(
        &contract,
        "policy_lift_only",
        &app.policy(&token).await["time_policy"],
    );
}

/// §14.4: the card right after the header (never the sheet's first child) and before Quick actions, with the
/// request's own words and four answers; the old "Asked for more time at" note is gone. "+15 min" is a lift and a
/// `yes` row in one go, lands on #quick with the flash, nudges the phone, and the card is gone; the inbox words the
/// answer for the other parent. A lift made before the request answers nothing.
#[tokio::test]
async fn the_card_shows_the_open_request_until_an_answer() {
    let app = TestApp::new().await;
    steady_zone(&app).await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("Anna").await;
    let page = format!("/devices/{id}");
    let html = app.get_page(&page, &cookie).await.text();
    assert!(card(&html).is_none(), "no request yet");
    // A lift before the request (a minute earlier) answers nothing.
    let lifted = app
        .request_form(
            Method::POST,
            &format!("/devices/{id}/lifts"),
            Some(&cookie),
            &[("target", "budget"), ("minutes", "15"), ("back", "summary")],
        )
        .await;
    assert_redirect(&lifted, &format!("{page}?done=extra"), Some("quick"));
    sqlx::query("UPDATE time_lifts SET created_at = datetime('now', '-1 minute')")
        .execute(&app.db)
        .await
        .unwrap();
    report(&app, &token, "SCREEN_TIME", 90, 90).await;
    let request = format!("{:032x}", 1);
    assert_eq!(
        post(&app, &token, body(1, 0)).await.1,
        json!({"accepted": true})
    );
    assert_eq!(answer_row(&app, id, &request).await, None);

    let html = app.get_page(&page, &cookie).await.text();
    let at = html.find("<section id=\"ask\"").expect("the card");
    assert!(html.find("</header>").unwrap() < at, "after the header");
    assert!(
        at < html.find("<section id=\"quick\"").unwrap(),
        "above Quick actions"
    );
    let ask = card(&html).unwrap();
    assert!(
        ask.contains("aria-label=\"Request for more screen time\""),
        "{ask}"
    );
    assert!(ask.contains("Anna asks for more screen time"), "{ask}");
    assert!(ask.contains("Screen time used up"), "{ask}");
    assert!(
        ask.contains(&format!("action=\"/devices/{id}/time-request/answer#ask\"")),
        "{ask}"
    );
    assert!(
        ask.contains(&format!("name=\"request\" value=\"{request}\"")),
        "{ask}"
    );
    for value in ["15", "30", "60", "no"] {
        assert!(
            ask.contains(&format!("name=\"answer\" value=\"{value}\"")),
            "{value}: {ask}"
        );
    }
    assert!(ask.contains(">+15 min<") && ask.contains(">+1 h<") && ask.contains(">No<"));
    assert!(!ask.contains("data-ask-pauses"), "screen time, not a rule");
    assert!(!html.contains("data-asked"), "the old note is gone");
    super::settings_pages::check_forms(&page, &html);

    let mut nudges = app.state.command_notify.subscribe();
    let res = answer(&app, &cookie, id, &request, "15").await;
    assert_redirect(&res, &format!("{page}?done=extra"), Some("quick"));
    app.assert_lands(&res, &cookie).await;
    assert_eq!(nudges.try_recv().ok(), Some(id));
    let lift: (i64, String, i64) =
        sqlx::query_as("SELECT id, target, minutes FROM time_lifts ORDER BY id DESC LIMIT 1")
            .fetch_one(&app.db)
            .await
            .unwrap();
    assert_eq!((lift.1.as_str(), lift.2), ("budget", 15));
    assert_eq!(
        answer_row(&app, id, &request).await,
        Some(("yes".to_string(), Some(lift.0)))
    );
    let html = app.get_page(&page, &cookie).await.text();
    assert!(card(&html).is_none(), "answered");
    let inbox = app.get_page("/notifications", &cookie).await.text();
    assert!(inbox.contains("parent gave 15\u{a0}min"), "{inbox}");
}

/// "Nei": no lift, a `no` row, the security line and the nudge; the flash says the kid sees it on the phone (§14.7);
/// the inbox says who said no.
#[tokio::test]
async fn no_is_written_and_seen() {
    let (flash, words) = ("answer_no_seen", "You said no. Anna sees it on the phone.");
    let app = TestApp::new().await;
    steady_zone(&app).await;
    let cookie = app.admin_cookie_as("pappa", "Pappa").await;
    let (id, token) = app.enrolled_device("Anna").await;
    app.post_status(&token, json!({})).await;
    assert_eq!(
        post(&app, &token, body(1, 0)).await.1,
        json!({"accepted": true})
    );
    let mut nudges = app.state.command_notify.subscribe();
    let res = answer(&app, &cookie, id, &format!("{:032x}", 1), "no").await;
    assert_redirect(&res, &format!("/devices/{id}?done={flash}"), Some("quick"));
    assert_eq!(nudges.try_recv().ok(), Some(id));
    assert_eq!(
        answer_row(&app, id, &format!("{:032x}", 1)).await,
        Some(("no".to_string(), None))
    );
    let lifts: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM time_lifts")
        .fetch_one(&app.db)
        .await
        .unwrap();
    assert_eq!(lifts, 0);
    let lines = super::security_words(&app, "time_request_no", crate::i18n::Lang::En).await;
    assert_eq!(lines.len(), 1, "{lines:?}");
    assert!(
        lines[0].contains("no to a request for more time"),
        "{lines:?}"
    );
    let html = app
        .get_page(&format!("/devices/{id}?done={flash}"), &cookie)
        .await
        .text();
    assert!(html.contains(words), "{html}");
    assert!(card(&html).is_none());
    let inbox = app.get_page("/notifications", &cookie).await.text();
    assert!(inbox.contains("Pappa said no"), "{inbox}");
    // The phone: the answer with Pappa's name.
    let policy = app.policy(&token).await;
    assert_eq!(policy["time_policy"]["answer"]["answer"], "no");
    assert_eq!(policy["time_policy"]["answer"]["by"], "Pappa");
}

/// D9 decided at the answer (QA R2-M2): asked with the screen time used up, then Leggetid locks (a fresh report):
/// the card says its buttons pause the rules, and "+30 min" pauses every rule for 30 minutes - a `yes` with that lift.
#[tokio::test]
async fn under_a_rule_the_minutes_pause_the_rules() {
    let app = TestApp::new().await;
    steady_zone(&app).await;
    let cookie = app.admin_cookie_as("mamma", "Mamma").await;
    let (id, token) = app.enrolled_device("Anna").await;
    report(&app, &token, "SCREEN_TIME", 90, 90).await;
    assert_eq!(
        post(&app, &token, body(1, 0)).await.1,
        json!({"accepted": true})
    );
    report(&app, &token, "BEDTIME", 90, 90).await;
    let html = app
        .get_page(&format!("/devices/{id}"), &cookie)
        .await
        .text();
    let ask = card(&html).expect("the card");
    assert!(
        ask.contains("Screen time used up"),
        "the ask's own words: {ask}"
    );
    assert!(
        ask.contains("The buttons pause the rules in force."),
        "{ask}"
    );
    let res = answer(&app, &cookie, id, &format!("{:032x}", 1), "30").await;
    assert_redirect(&res, &format!("/devices/{id}?done=pause"), Some("quick"));
    let lift: (i64, String, Option<i64>, i64) =
        sqlx::query_as("SELECT id, target, rule_id, minutes FROM time_lifts")
            .fetch_one(&app.db)
            .await
            .unwrap();
    assert_eq!((lift.1.as_str(), lift.2, lift.3), ("rule", None, 30));
    let policy = app.policy(&token).await;
    let served = &policy["time_policy"];
    assert_eq!(served["answer"]["lift_id"], lift.0);
    assert_eq!(served["answer"]["minutes"], 30);
    assert_eq!(served["answer"]["by"], "Mamma");
    assert_eq!(served["lifts"][0]["target"], "rule");
    assert_eq!(served["lifts"][0]["by"], "Mamma");
}

/// Code QA L9: asked under a rule that a fresh report says no longer locks, the card says its buttons add screen time
/// (the sub-line keeps the ask's own words).
#[tokio::test]
async fn asked_under_a_rule_that_ended_the_minutes_add_time() {
    let app = TestApp::new().await;
    steady_zone(&app).await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("Anna").await;
    report(&app, &token, "BEDTIME", 90, 90).await;
    assert_eq!(
        post(&app, &token, body(1, 0)).await.1,
        json!({"accepted": true})
    );
    report(&app, &token, "SCREEN_TIME", 90, 90).await;
    let html = app
        .get_page(&format!("/devices/{id}"), &cookie)
        .await
        .text();
    let ask = card(&html).expect("the card");
    assert!(ask.contains("data-ask-adds"), "{ask}");
    assert!(
        ask.contains("No rule locks now: the buttons add screen time."),
        "{ask}"
    );
    assert!(!ask.contains("data-ask-pauses"), "{ask}");
}

/// The first answer wins (§14.4): the second parent's card writes nothing and says who answered; "Nei" after a
/// yes too. One lift.
#[tokio::test]
async fn the_first_answer_wins() {
    let app = TestApp::new().await;
    steady_zone(&app).await;
    let mamma = app.admin_cookie_as("mamma", "Mamma").await;
    let pappa = app.admin_cookie_as("pappa", "Pappa").await;
    let (id, token) = app.enrolled_device("Anna").await;
    assert_eq!(
        post(&app, &token, body(1, 0)).await.1,
        json!({"accepted": true})
    );
    let request = format!("{:032x}", 1);
    let res = answer(&app, &mamma, id, &request, "30").await;
    assert_redirect(&res, &format!("/devices/{id}?done=extra"), Some("quick"));
    for value in ["15", "no"] {
        let res = answer(&app, &pappa, id, &request, value).await;
        assert_redirect(
            &res,
            &format!("/devices/{id}?done=answered_already&request={request}"),
            Some("quick"),
        );
    }
    let lifts: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM time_lifts")
        .fetch_one(&app.db)
        .await
        .unwrap();
    assert_eq!(lifts, 1);
    let html = app
        .get_page(
            &format!("/devices/{id}?done=answered_already&request={request}"),
            &pappa,
        )
        .await
        .text();
    assert!(html.contains("Mamma has already answered."), "{html}");
    // Code QA L6: the stale card names who answered its own request, not the newest answer.
    let second = format!("{:032x}", 2);
    assert_eq!(
        post(&app, &token, body(2, 0)).await.1,
        json!({"accepted": true})
    );
    let res = answer(&app, &pappa, id, &second, "no").await;
    assert_redirect(
        &res,
        &format!("/devices/{id}?done=answer_no_seen"),
        Some("quick"),
    );
    let html = app
        .get_page(
            &format!("/devices/{id}?done=answered_already&request={request}"),
            &pappa,
        )
        .await
        .text();
    assert!(html.contains("Mamma has already answered."), "{html}");
    // An answer that isn't one, and a request the phone never asked.
    let res = answer(&app, &pappa, id, &request, "45").await;
    assert_eq!(res.status, StatusCode::BAD_REQUEST);
    let res = answer(&app, &pappa, id, &format!("{:032x}", 7), "no").await;
    assert_eq!(res.status, StatusCode::NOT_FOUND);
}

/// QA R2-M2: a lift from Quick actions answers the open request only when it can unlock what locks - a rule pause
/// leaves a used-up request open, added screen time answers it (by the parent who gave it).
#[tokio::test]
async fn a_fitting_lift_answers_the_open_request() {
    let app = TestApp::new().await;
    steady_zone(&app).await;
    let cookie = app.admin_cookie_as("mamma", "Mamma").await;
    let (id, token) = app.enrolled_device("Anna").await;
    report(&app, &token, "SCREEN_TIME", 90, 90).await;
    assert_eq!(
        post(&app, &token, body(1, 0)).await.1,
        json!({"accepted": true})
    );
    let request = format!("{:032x}", 1);
    let res = app
        .request_form(
            Method::POST,
            &format!("/devices/{id}/lifts"),
            Some(&cookie),
            &[("target", "rule"), ("rule", "all"), ("minutes", "30")],
        )
        .await;
    assert!(res.status.is_redirection(), "{}", res.text());
    assert_eq!(answer_row(&app, id, &request).await, None, "a rule pause");
    // Design 139 §5.2: extra time for an app group never answers a request for screen time (`fits`).
    let group: i64 = sqlx::query_scalar(
        "INSERT INTO app_groups (device_id, name, apps_json, days_json) VALUES (?, 'Spill', \
         '[\"org.example.game\"]', '[60,60,60,60,60,60,60]') RETURNING id",
    )
    .bind(id)
    .fetch_one(&app.db)
    .await
    .unwrap();
    let res = app
        .request_form(
            Method::POST,
            &format!("/devices/{id}/lifts"),
            Some(&cookie),
            &[("what", &format!("group-{group}")), ("minutes", "15")],
        )
        .await;
    assert!(res.status.is_redirection(), "{}", res.text());
    assert_eq!(
        answer_row(&app, id, &request).await,
        None,
        "a group's extra time"
    );
    let html = app
        .get_page(&format!("/devices/{id}"), &cookie)
        .await
        .text();
    assert!(card(&html).is_some(), "still open");
    let res = app
        .request_form(
            Method::POST,
            &format!("/devices/{id}/lifts"),
            Some(&cookie),
            &[("target", "budget"), ("minutes", "15")],
        )
        .await;
    assert!(res.status.is_redirection(), "{}", res.text());
    let budget: i64 = sqlx::query_scalar("SELECT MAX(id) FROM time_lifts")
        .fetch_one(&app.db)
        .await
        .unwrap();
    assert_eq!(
        answer_row(&app, id, &request).await,
        Some(("yes".to_string(), Some(budget)))
    );
    let answer = &app.policy(&token).await["time_policy"]["answer"];
    assert_eq!(answer["by"], "Mamma");
    assert_eq!(answer["minutes"], 15);
}

/// Code QA L7: a request asked under a rule turned on now (design 93's copy, run on the phone as a negative id) is
/// answered by a pause of the rule it copies - the twin lifts the copy too; a pause of another rule isn't.
#[tokio::test]
async fn a_pause_of_the_copied_rule_answers_a_request_under_its_copy() {
    let app = TestApp::new().await;
    steady_zone(&app).await;
    let cookie = app.admin_cookie_as("mamma", "Mamma").await;
    let (id, token) = app.enrolled_device("Anna").await;
    let days = r#"[{"start":1080,"end":1110},{"start":1080,"end":1110},{"start":1080,"end":1110},{"start":1080,"end":1110},{"start":1080,"end":1110},{"start":1080,"end":1110},{"start":1080,"end":1110}]"#;
    for (rule, name) in [(41, "Middag"), (42, "Lekser")] {
        sqlx::query(
            "INSERT INTO time_rules (id, device_id, name, kind, calls_allowed, exempt_apps_json, days_json, \
             sort_order) VALUES (?, ?, ?, 'custom', 1, '[]', ?, ?)",
        )
        .bind(rule)
        .bind(id)
        .bind(name)
        .bind(days)
        .bind(rule)
        .execute(&app.db)
        .await
        .unwrap();
    }
    let today = Zone::load(&app.db, crate::i18n::Lang::En)
        .await
        .today(Utc::now());
    sqlx::query(
        "INSERT INTO time_lock_now (id, device_id, rule_id, rule_name, zone, local_date, start_minute, \
         end_minute, ends_at) VALUES (7, ?, 41, 'Middag', 'Europe/Oslo', ?, 0, 1439, datetime('now', '+2 hours'))",
    )
    .bind(id)
    .bind(today.to_string())
    .execute(&app.db)
    .await
    .unwrap();
    let res = app
        .request(
            Method::POST,
            "/api/devices/status",
            Some(&token),
            Some(json!({
                "lock_reason": "RULE",
                "kiosk_engaged": true,
                "time_state": {
                    "day": today.to_string(),
                    "used_minutes": 10, "budget_minutes": 90, "extra_minutes": 0,
                    "lock_reason": "RULE", "active_rule_id": -7, "calls_blocked": false, "lifts_active": [],
                },
            })),
        )
        .await;
    assert_eq!(res.status, StatusCode::NO_CONTENT, "{}", res.text());
    assert_eq!(
        post(&app, &token, body(1, 0)).await.1,
        json!({"accepted": true})
    );
    let request = format!("{:032x}", 1);
    for rule in ["42", "41"] {
        let res = app
            .request_form(
                Method::POST,
                &format!("/devices/{id}/lifts"),
                Some(&cookie),
                &[("target", "rule"), ("rule", rule), ("minutes", "30")],
            )
            .await;
        assert!(res.status.is_redirection(), "{}", res.text());
        if rule == "42" {
            assert_eq!(answer_row(&app, id, &request).await, None, "another rule");
        }
    }
    let lift: i64 = sqlx::query_scalar("SELECT MAX(id) FROM time_lifts")
        .fetch_one(&app.db)
        .await
        .unwrap();
    assert_eq!(
        answer_row(&app, id, &request).await,
        Some(("yes".to_string(), Some(lift))),
        "the rule the copy copies"
    );
}

/// §14.4 (QA R2-L6): a newer request of the day supersedes the open one - the card shows the newer, the older's card says so; a request of another day is no longer open; a
/// retry of the newer one supersedes nothing more.
#[tokio::test]
async fn a_second_request_supersedes_the_first() {
    let app = TestApp::new().await;
    steady_zone(&app).await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("Anna").await;
    assert_eq!(
        post(&app, &token, body(1, 31)).await.1,
        json!({"accepted": true})
    );
    assert_eq!(
        post(&app, &token, body(2, 0)).await.1,
        json!({"accepted": true})
    );
    assert_eq!(
        post(&app, &token, body(2, 0)).await.1,
        json!({"accepted": true})
    );
    assert_eq!(
        answer_row(&app, id, &format!("{:032x}", 1)).await,
        Some(("superseded".to_string(), None))
    );
    assert_eq!(answer_row(&app, id, &format!("{:032x}", 2)).await, None);
    let html = app
        .get_page(&format!("/devices/{id}"), &cookie)
        .await
        .text();
    let ask = card(&html).expect("the newer one's card");
    assert!(ask.contains(&format!("value=\"{:032x}\"", 2)), "{ask}");
    let res = answer(&app, &cookie, id, &format!("{:032x}", 1), "30").await;
    assert_redirect(
        &res,
        &format!("/devices/{id}?done=asked_again"),
        Some("quick"),
    );
    let html = app
        .get_page(&format!("/devices/{id}?done=asked_again"), &cookie)
        .await
        .text();
    assert!(html.contains("Anna asked again."), "{html}");
    // Superseded is never the phone's answer.
    assert!(
        app.policy(&token).await["time_policy"]
            .get("answer")
            .is_none()
    );
    // Yesterday's request (stored before the day's start) is no longer open.
    sqlx::query(
        "INSERT INTO parent_events (device_id, kind, dedup_key, title, summary, target, happened_at) \
         VALUES (?, 'extra_time_request', ?, '', '', '/devices', datetime('now', '-30 hours'))",
    )
    .bind(id)
    .bind(format!("{:032x}", 9))
    .execute(&app.db)
    .await
    .unwrap();
    let res = answer(&app, &cookie, id, &format!("{:032x}", 9), "no").await;
    assert_redirect(
        &res,
        &format!("/devices/{id}?done=request_gone"),
        Some("quick"),
    );
}

/// QA R2-M4: migration 0149 answers what a lift answered before it (any lift at or after the request, r13's rule),
/// so the open-request rule is one query: the first such lift's `yes` with its parent; a request no lift followed
/// stays open; a phone-key pause never answered one.
#[tokio::test]
async fn the_migration_answers_the_requests_a_lift_answered() {
    let (db, _dir) = super::cleanup::migrated_before(149).await;
    let mamma: i64 = sqlx::query_scalar(
        "INSERT INTO admin_users (username, password_hash, display_name) VALUES ('mamma', 'x', 'Mamma') RETURNING id",
    )
    .fetch_one(&db)
    .await
    .unwrap();
    let device: i64 = sqlx::query_scalar("INSERT INTO devices (name) VALUES ('Anna') RETURNING id")
        .fetch_one(&db)
        .await
        .unwrap();
    for (key, ago) in [("r1", "-3 hours"), ("r2", "-30 minutes")] {
        sqlx::query(
            "INSERT INTO parent_events (device_id, kind, dedup_key, title, summary, target, happened_at, \
             created_at) VALUES (?, 'extra_time_request', ?, '', '', '/devices', datetime('now', ?), \
             datetime('now', ?))",
        )
        .bind(device)
        .bind(key)
        .bind(ago)
        .bind(ago)
        .execute(&db)
        .await
        .unwrap();
    }
    // Before r1 (answers nothing), after r1 (its answer, and not r2's), a phone-key pause after r2 (nothing).
    for (ago, by, key) in [
        ("-4 hours", Some("mamma"), None),
        ("-2 hours", Some("mamma"), None),
        ("-1 hours", Some("mamma"), None),
        (
            "-10 minutes",
            None,
            Some("0123456789abcdef0123456789abcdef"),
        ),
    ] {
        sqlx::query(
            "INSERT INTO time_lifts (device_id, target, rule_id, minutes, created_at, expires_at, created_by, \
             phone_key) VALUES (?, 'budget', NULL, 15, datetime('now', ?), datetime('now', '+1 hour'), ?, ?)",
        )
        .bind(device)
        .bind(ago)
        .bind(by)
        .bind(key)
        .execute(&db)
        .await
        .unwrap();
    }
    // Code QA L10: a rule pause that closed a request under r13 is a yes that names no lift (no minutes).
    let ola: i64 = sqlx::query_scalar("INSERT INTO devices (name) VALUES ('Ola') RETURNING id")
        .fetch_one(&db)
        .await
        .unwrap();
    sqlx::query(
        "INSERT INTO parent_events (device_id, kind, dedup_key, title, summary, target, happened_at, created_at) \
         VALUES (?, 'extra_time_request', 'r3', '', '', '/devices', datetime('now', '-3 hours'), \
         datetime('now', '-3 hours'))",
    )
    .bind(ola)
    .execute(&db)
    .await
    .unwrap();
    sqlx::query(
        "INSERT INTO time_lifts (device_id, target, rule_id, minutes, created_at, expires_at, created_by) \
         VALUES (?, 'rule', NULL, 15, datetime('now', '-2 hours'), datetime('now', '-1 hours'), 'mamma')",
    )
    .bind(ola)
    .execute(&db)
    .await
    .unwrap();
    super::cleanup::finish(&db).await;
    type Row = (String, String, Option<i64>, Option<i64>, Option<String>);
    let rows: Vec<Row> = sqlx::query_as(
        "SELECT request_id, answer, lift_id, answered_by, by_name FROM time_request_answers ORDER BY request_id",
    )
    .fetch_all(&db)
    .await
    .unwrap();
    assert_eq!(
        rows,
        [
            (
                "r1".to_string(),
                "yes".to_string(),
                Some(2),
                Some(mamma),
                Some("Mamma".to_string())
            ),
            (
                "r3".to_string(),
                "yes".to_string(),
                None,
                Some(mamma),
                Some("Mamma".to_string())
            ),
        ]
    );
}
