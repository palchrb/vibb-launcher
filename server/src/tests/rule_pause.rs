//! Design 98 §4.4 (Foreldrekode): the shared contract `testdata/rule_pause.json` - the phone's parent-code pause of the
//! rule in force, posted to `POST /api/devices/rule-pause` (`handlers::rule_pause`) - and the PWA's side of it.

use axum::http::{Method, StatusCode};
use serde_json::{Value, json};

use super::TestApp;

const CONTRACT: &str = include_str!("../../testdata/rule_pause.json");

/// The contract's instant the answers' bodies are built around (`request.started_at_ms`).
const STARTED_AT_MS: i64 = 1_794_826_200_000;

fn contract() -> Value {
    serde_json::from_str(CONTRACT).unwrap()
}

#[test]
fn the_contract_is_identical_to_the_launchers_copy() {
    let contract = contract();
    let lengths: Vec<i64> = contract["lengths"]
        .as_array()
        .unwrap()
        .iter()
        .map(|m| m.as_i64().unwrap())
        .collect();
    assert_eq!(lengths, crate::time_rules::RULE_LIFT_MINUTES);
    assert_eq!(contract["request"], contract["answers"][0]["body"]);
    let launcher = std::fs::read_to_string(concat!(
        env!("CARGO_MANIFEST_DIR"),
        "/../launcher/app/src/test/resources/rule_pause.json"
    ))
    .unwrap();
    assert_eq!(launcher, CONTRACT, "the launcher's copy differs");
}

/// `value` with every `*_ms` number moved by `shift` (the contract's instants into the last minutes).
fn shifted(value: &Value, shift: i64) -> Value {
    match value {
        Value::Object(map) => Value::Object(
            map.iter()
                .map(|(key, v)| {
                    let v = match v.as_i64() {
                        Some(ms) if key.ends_with("_ms") => json!(ms + shift),
                        _ => shifted(v, shift),
                    };
                    (key.clone(), v)
                })
                .collect(),
        ),
        Value::Array(items) => Value::Array(items.iter().map(|v| shifted(v, shift)).collect()),
        other => other.clone(),
    }
}

/// `ms` as `time_lifts` stores an instant (`datetime()` text, whole seconds).
fn db_time(ms: i64) -> String {
    crate::lock_now::db_time(chrono::DateTime::from_timestamp_millis(ms).unwrap())
}

/// Emma's phone with its own Leggetid as rule 2 and a running copy of it (design 93) as `time_lock_now` 1, no lift:
/// the ids the contract's answers name.
async fn emma() -> (TestApp, i64, String) {
    let app = TestApp::new().await;
    let (id, token) = app.enrolled_device("Emma").await;
    let night = r#"{"start":1260,"end":420}"#;
    sqlx::query(&format!(
        "INSERT INTO time_rules (id, device_id, name, kind, calls_allowed, days_json) \
         VALUES (2, ?, 'Leggetid', 'custom', 1, '[{n},{n},{n},{n},{n},{n},{n}]')",
        n = night
    ))
    .bind(id)
    .execute(&app.db)
    .await
    .unwrap();
    sqlx::query(
        "INSERT INTO time_lock_now (id, device_id, rule_id, rule_name, zone, local_date, start_minute, \
         end_minute, ends_at) VALUES (1, ?, 2, 'Leggetid', 'UTC', date('now'), 0, 1439, \
         datetime('now', '+1 hour'))",
    )
    .bind(id)
    .execute(&app.db)
    .await
    .unwrap();
    (app, id, token)
}

async fn post(app: &TestApp, token: &str, body: Value) -> super::TestResponse {
    app.request(
        Method::POST,
        "/api/devices/rule-pause",
        Some(token),
        Some(body),
    )
    .await
}

/// A `time_lifts` row as the test reads it: id, rule, name, minutes, created_at, expires_at, created_by, phone_key,
/// ended_early_at.
type Row = (
    i64,
    Option<i64>,
    Option<String>,
    i64,
    String,
    String,
    Option<String>,
    Option<String>,
    Option<String>,
);

async fn rows(app: &TestApp) -> Vec<Row> {
    sqlx::query_as(
        "SELECT id, rule_id, rule_name, minutes, created_at, expires_at, created_by, phone_key, ended_early_at \
         FROM time_lifts ORDER BY id",
    )
    .fetch_all(&app.db)
    .await
    .unwrap()
}

/// §4.4.5: every `answers` row of the contract (its instants moved to the last minutes) gets its status and answer
/// exactly - one row for the rule and its copy, the same ids on a retry, an over row, `unknown_rule`, each refusal.
/// The rows: `created_by` NULL, `created_at` the pause's start, `phone_key`; a retry adds none and never revives a row
/// the parent ended. The phone line `time_lift_phone`. The policy then serves the lift and the copy's twin with
/// `phone_key`, each with the contract's `policy.time_policy.lifts` keys.
#[tokio::test]
async fn the_contract_answers() {
    let (app, _id, token) = emma().await;
    let contract = contract();
    let shift = chrono::Utc::now().timestamp_millis() - 60_000 - STARTED_AT_MS;
    for row in contract["answers"].as_array().unwrap() {
        let res = post(&app, &token, shifted(&row["body"], shift)).await;
        assert_eq!(
            i64::from(res.status.as_u16()),
            row["status"].as_i64().unwrap(),
            "{}: {}",
            row["case"],
            res.text()
        );
        if res.status == StatusCode::OK {
            assert_eq!(res.json(), row["answer"], "{}", row["case"]);
        }
    }

    let request = shifted(&contract["request"], shift);
    let key = request["key"].as_str().unwrap();
    let started = request["started_at_ms"].as_i64().unwrap();
    let until = request["rules"][0]["until_ms"].as_i64().unwrap();
    let over = shifted(&contract["answers"][2]["body"], shift);
    let stored = rows(&app).await;
    assert_eq!(stored.len(), 2, "{stored:?}");
    assert_eq!(
        stored[0],
        (
            1,
            Some(2),
            Some("Leggetid".to_string()),
            30,
            db_time(started),
            db_time(until),
            None,
            Some(key.to_string()),
            None
        )
    );
    assert_eq!(
        (
            stored[1].0,
            stored[1].4.clone(),
            stored[1].5.clone(),
            stored[1].6.clone()
        ),
        (
            2,
            db_time(over["started_at_ms"].as_i64().unwrap()),
            db_time(over["rules"][0]["until_ms"].as_i64().unwrap()),
            None
        )
    );
    // The phone line, once per new pause (the retry wrote none).
    let lines = super::security_words(&app, "time_lift_phone", crate::i18n::Lang::En).await;
    assert_eq!(lines.len(), 2, "{lines:?}");
    assert!(
        lines
            .iter()
            .all(|l| l.contains("Leggetid paused for") && l.contains("with the parent code")),
        "{lines:?}"
    );

    // The policy: the lift and the copy's twin, with the key, as the contract's rows.
    let policy = app.policy(&token).await;
    let lifts: Vec<&Value> = policy["time_policy"]["lifts"]
        .as_array()
        .unwrap()
        .iter()
        .collect();
    let ids: Vec<i64> = lifts.iter().map(|l| l["id"].as_i64().unwrap()).collect();
    assert_eq!(ids, [1, -1], "{lifts:?}");
    let keys = |lift: &Value| {
        let mut keys: Vec<String> = lift.as_object().unwrap().keys().cloned().collect();
        keys.sort();
        keys
    };
    let contract_lifts: Vec<&Value> = contract["policy"]["time_policy"]["lifts"]
        .as_array()
        .unwrap()
        .iter()
        .filter(|l| l.get("phone_key").is_some())
        .collect();
    for (served, expected) in lifts.iter().zip(&contract_lifts) {
        assert_eq!(keys(served), keys(expected), "{served}");
        assert_eq!(served["phone_key"], key);
        assert_eq!(served["minutes"], 30);
        assert_eq!(served["expires_at_ms"], json!(until / 1000 * 1000));
    }
    assert_eq!(
        (lifts[0]["rule_id"].clone(), lifts[1]["rule_id"].clone()),
        (json!(2), json!(-1))
    );
    let copy = policy["time_policy"]["rules"]
        .as_array()
        .unwrap()
        .iter()
        .find(|r| r["id"] == -1)
        .cloned()
        .unwrap();
    assert_eq!(copy["copy_of"], 2);
    // A lift without a key serves none.
    assert!(
        contract["policy"]["time_policy"]["lifts"][0]
            .get("phone_key")
            .is_none()
    );

    // The parent ends it; the phone's retry neither adds a row nor revives it.
    sqlx::query("UPDATE time_lifts SET ended_early_at = datetime('now') WHERE id = 1")
        .execute(&app.db)
        .await
        .unwrap();
    let res = post(&app, &token, request.clone()).await;
    assert_eq!(res.json(), json!({"accepted": true, "lift_ids": [1]}));
    let stored = rows(&app).await;
    assert_eq!(stored.len(), 2);
    assert!(stored[0].8.is_some(), "still ended");
    assert!(
        app.policy(&token).await["time_policy"]["lifts"]
            .as_array()
            .unwrap()
            .is_empty()
    );
}

/// A post without a token is refused; a body that doesn't parse is a 400 with nothing stored.
#[tokio::test]
async fn a_bad_post_stores_nothing() {
    let (app, _id, token) = emma().await;
    let request = contract()["request"].clone();
    let res = app
        .request(Method::POST, "/api/devices/rule-pause", None, Some(request))
        .await;
    assert_eq!(res.status, StatusCode::UNAUTHORIZED);
    let res = post(
        &app,
        &token,
        json!({"key": "0123456789abcdef0123456789abcdef"}),
    )
    .await;
    assert_eq!(res.status, StatusCode::BAD_REQUEST);
    assert!(rows(&app).await.is_empty());
}

/// §4.4.5's PWA side: the summary's running pause has "Set with the parent code on the phone" and End now ends it;
/// Activity's row reads "Leggetid paused 21:10–21:40 (parent code)"; the parent-PIN event's entry `rule_pause` is
/// "The time-rule screen" in the inbox (with its end) and the push; the Time page's ⓘ for a phone with
/// `rule_pause_v1`; Norwegian too.
#[tokio::test]
async fn the_pwa_words_the_phones_pause() {
    let (app, id, token) = emma().await;
    let cookie = app.admin_cookie().await;
    let now = chrono::Utc::now().timestamp_millis();
    let started = now - 60_000;
    let until = started + 30 * 60_000;
    let res = post(
        &app,
        &token,
        json!({"key": "0123456789abcdef0123456789abcdef", "minutes": 30, "started_at_ms": started,
               "rules": [{"rule_id": 2, "until_ms": until}]}),
    )
    .await;
    assert_eq!(res.json(), json!({"accepted": true, "lift_ids": [1]}));
    app.post_status(
        &token,
        json!({"time_state": {"day": "2026-11-14", "used_minutes": 0, "lock_reason": "NONE", "lifts_active": [1]}}),
    )
    .await;
    let summary = format!("/devices/{id}");
    let html = app.get_page(&summary, &cookie).await.text();
    let lift = super::settings_pages::part(&html, "data-lift=\"1\"", "</div>");
    assert!(
        lift.contains("Set with the parent code on the phone"),
        "{lift}"
    );
    assert!(
        lift.contains(&format!("action=\"/devices/{id}/lifts/1/end#quick\"")),
        "{lift}"
    );
    let nb = app
        .get_page(&format!("{summary}?lang=nb"), &cookie)
        .await
        .text();
    assert!(nb.contains("Satt med foreldrekoden på telefonen"), "{nb}");
    let time = app
        .get_page(&format!("/devices/{id}/time"), &cookie)
        .await
        .text();
    assert!(time.contains("data-rule-pause-info"), "{time}");
    // Design 113: the ⓘ alone, its label only the aria-label.
    assert!(!time.contains(">About the parent code"), "{time}");
    assert!(time.contains(
        "The parent code on the phone can pause the rule in force for 15 minutes to 2 hours."
    ));
    assert!(!time.contains("title-bell"), "a phone sub-page has no bell");

    // End now: the phone's pause ends as any lift does, and Activity words it by its window.
    let res = app
        .request_form(
            Method::POST,
            &format!("/devices/{id}/lifts/1/end"),
            Some(&cookie),
            &[],
        )
        .await;
    super::assert_redirect(&res, &format!("{summary}?done=ended"), Some("quick"));
    app.assert_lands(&res, &cookie).await;
    let activity = app
        .get_page(&format!("/devices/{id}/activity"), &cookie)
        .await
        .text();
    assert!(activity.contains("Leggetid paused "), "{activity}");
    assert!(activity.contains(" (parent code)"), "{activity}");
    let activity = app
        .get_page(&format!("/devices/{id}/activity?lang=nb"), &cookie)
        .await
        .text();
    assert!(activity.contains(" (foreldrekoden)"), "{activity}");

    // The parent PIN typed on the time-rule screen: its entry and end, in the inbox and the push.
    let pin = json!({"events": [{"id": "fedcba9876543210fedcba9876543210", "kind": "unlocked",
                                 "entry": "rule_pause", "at_ms": started, "until_ms": until}]});
    let res = app
        .request(
            Method::POST,
            "/api/devices/pin-events",
            Some(&token),
            Some(pin),
        )
        .await;
    assert!(res.status.is_success(), "{} {}", res.status, res.text());
    let params: String = sqlx::query_scalar(
        "SELECT params FROM parent_events WHERE device_id = ? AND kind = 'pin_used'",
    )
    .bind(id)
    .fetch_one(&app.db)
    .await
    .unwrap();
    let words = |form, lang| {
        let (db, params) = (app.db.clone(), params.clone());
        async move {
            let zone = crate::display_time::Zone::load(&db, lang).await;
            crate::parent_events::told_as(
                form,
                "pin_used",
                String::new(),
                String::new(),
                Some(&params),
                &zone,
                chrono::Utc::now(),
            )
        }
    };
    use crate::i18n::Lang;
    use crate::parent_events::Form;
    let (title, inbox) = words(Form::Inbox, Lang::En).await;
    assert_eq!(title, "Parent PIN used on Emma's phone");
    assert!(inbox.starts_with("The time-rule screen until "), "{inbox}");
    let (_, push) = words(Form::Push, Lang::En).await;
    assert!(push.starts_with("The time-rule screen · "), "{push}");
    let (title, inbox) = words(Form::Inbox, Lang::Nb).await;
    assert_eq!(title, "Foreldre-PIN brukt på Emmas mobil");
    assert!(inbox.starts_with("Regelskjermen til "), "{inbox}");
    let lines = super::security_words(&app, "parent_pin_used", Lang::En).await;
    assert!(
        lines
            .iter()
            .any(|l| l.contains("paused on the time-rule screen, until")),
        "{lines:?}"
    );
}

/// Security review 2026-11-17 M2, by the user's choice (2026-11-18): no cap - every phone-set pause is told. A second
/// pause while the first lifts is stored like any other, and each stored pause is the inbox's parent-PIN line for the
/// time-rule screen, one row with the phone's own PIN event for it, whichever comes first; a retry adds none.
#[tokio::test]
async fn every_phone_pause_is_told_once() {
    let (app, id, token) = emma().await;
    let now = chrono::Utc::now().timestamp_millis();
    let key = |n: u8| format!("0123456789abcdef0123456789abcd{n:02x}");
    let body = |key: &str, started: i64| {
        json!({"key": key, "minutes": 15, "started_at_ms": started,
               "rules": [{"rule_id": 2, "until_ms": started + 15 * 60_000}]})
    };
    let first = body(&key(0x11), now - 60_000);
    let res = post(&app, &token, first.clone()).await;
    assert_eq!(res.json(), json!({"accepted": true, "lift_ids": [1]}));
    let res = post(&app, &token, body(&key(0x12), now - 30_000)).await;
    assert_eq!(res.json(), json!({"accepted": true, "lift_ids": [2]}));
    let res = post(&app, &token, first.clone()).await;
    assert_eq!(res.json(), json!({"accepted": true, "lift_ids": [1]}));
    assert_eq!(rows(&app).await.len(), 2);

    let pin_used = || async {
        sqlx::query_scalar::<_, i64>(
            "SELECT COUNT(*) FROM parent_events WHERE device_id = ? AND kind = 'pin_used'",
        )
        .bind(id)
        .fetch_one(&app.db)
        .await
        .unwrap()
    };
    assert_eq!(pin_used().await, 2);
    let until = first["rules"][0]["until_ms"].as_i64().unwrap();
    let pin = json!({"events": [{"id": "fedcba9876543210fedcba9876543210", "kind": "unlocked",
                                 "entry": "rule_pause", "at_ms": now - 60_000, "until_ms": until}]});
    let res = app
        .request(
            Method::POST,
            "/api/devices/pin-events",
            Some(&token),
            Some(pin),
        )
        .await;
    assert!(res.status.is_success(), "{} {}", res.status, res.text());
    assert_eq!(pin_used().await, 2);
    let words = super::told_events(&app, id, crate::i18n::Lang::En).await;
    assert!(
        words.iter().all(
            |(_, title, summary)| title == "Parent PIN used on Emma's phone"
                && summary.starts_with("The time-rule screen until ")
        ),
        "{words:?}"
    );
}

/// Code QA M1: a phone pause past the phone's hourly budget of events is still told in the inbox (stored without a
/// push of its own), never dropped - it lifts a rule.
#[tokio::test]
async fn a_pause_past_the_hourly_budget_is_still_told() {
    let (app, id, token) = emma().await;
    let now = chrono::Utc::now().timestamp_millis();
    let n = crate::parent_events::EVENTS_AN_HOUR + 1;
    for i in 0..n {
        let started = now - 60_000 - i * 1_000;
        let res = post(
            &app,
            &token,
            json!({"key": format!("0123456789abcdef0123456789ab{i:04x}"), "minutes": 15,
                   "started_at_ms": started, "rules": [{"rule_id": 2, "until_ms": started + 15 * 60_000}]}),
        )
        .await;
        assert_eq!(res.status, StatusCode::OK, "{}", res.text());
    }
    let told: i64 = sqlx::query_scalar(
        "SELECT COUNT(*) FROM parent_events WHERE device_id = ? AND kind = 'pin_used'",
    )
    .bind(id)
    .fetch_one(&app.db)
    .await
    .unwrap();
    assert_eq!(told, n);
}
