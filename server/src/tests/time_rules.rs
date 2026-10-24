//! Time rules, screen time, lifts and the location policy (handy step 6).

use axum::http::{Method, StatusCode};
use serde_json::json;

use super::{Section, TestApp, assert_redirect, device_page};

async fn policy(app: &TestApp, token: &str) -> serde_json::Value {
    let res = app
        .request(Method::GET, "/api/devices/policy", Some(token), None)
        .await;
    assert_eq!(res.status, StatusCode::OK, "{}", res.text());
    res.json()
}

const SCHOOL_FORM: &[(&str, &str)] = &[
    ("scope", ""),
    ("name", "Skole"),
    ("kind", "school"),
    ("exempt_apps", "org.fossify.calendar"),
    ("d0_start", "08:15"),
    ("d0_end", "14:00"),
    ("d4_start", "08:15"),
    ("d4_end", "14:00"),
];

#[tokio::test]
async fn fresh_policy_has_no_rules_unlimited_budget_and_on_request_location() {
    let app = TestApp::new().await;
    let (_, token) = app.enrolled_device("phone").await;
    let p = policy(&app, &token).await;
    assert_eq!(
        p["time_policy"],
        json!({"rules": [], "daily_budget_minutes": [null, null, null, null, null, null, null], "lifts": []})
    );
    assert_eq!(
        p["location_policy"],
        json!({"mode": "on_request", "interval_minutes": 30, "zones": []})
    );
}

#[tokio::test]
async fn global_rule_reaches_every_device_and_nudges_them() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (a, token_a) = app.enrolled_device("a").await;
    let (b, token_b) = app.enrolled_device("b").await;
    let mut nudges = app.state.command_notify.subscribe();

    let res = app
        .request_form(Method::POST, "/schedules/rules", Some(&cookie), SCHOOL_FORM)
        .await;
    let rule_id: i64 = sqlx::query_scalar("SELECT id FROM time_rules")
        .fetch_one(&app.db)
        .await
        .unwrap();
    // A new rule opens its own page (design 31 step 9).
    assert_redirect(
        &res,
        &format!("/schedules/rules/{rule_id}?saved=added"),
        Some("rule"),
    );
    let mut nudged = vec![nudges.try_recv().unwrap(), nudges.try_recv().unwrap()];
    nudged.sort_unstable();
    assert_eq!(nudged, vec![a.min(b), a.max(b)]);

    for token in [&token_a, &token_b] {
        let rules = policy(&app, token).await["time_policy"]["rules"].clone();
        assert_eq!(rules.as_array().unwrap().len(), 1);
        let rule = &rules[0];
        assert_eq!(rule["name"], "Skole");
        assert_eq!(rule["kind"], "school");
        assert_eq!(rule["calls_allowed"], false);
        assert_eq!(rule["exempt_apps"], json!(["org.fossify.calendar"]));
        assert_eq!(
            rule["days"],
            json!([{"start": 495, "end": 840}, null, null, null, {"start": 495, "end": 840}, null, null])
        );
        assert!(rule["id"].as_i64().unwrap() > 0);
    }

    let page = app.get_page("/schedules", &cookie).await;
    assert_eq!(page.status, StatusCode::OK);
    assert!(page.text().contains("Skole"));
    assert!(page.text().contains("Mon 08:15-14:00"));
}

#[tokio::test]
async fn device_override_uses_its_own_rules_and_budget() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("phone").await;
    app.request_form(Method::POST, "/schedules/rules", Some(&cookie), SCHOOL_FORM)
        .await;
    app.request_form(
        Method::POST,
        "/schedules/global",
        Some(&cookie),
        &[("b0", "60"), ("b5", "120")],
    )
    .await;
    let scope = id.to_string();
    let res = app
        .request_form(
            Method::POST,
            "/schedules/rules",
            Some(&cookie),
            &[
                ("scope", &scope),
                ("name", "Leggetid"),
                ("kind", "bedtime"),
                ("calls_allowed", "on"),
                ("d6_start", "21:00"),
                ("d6_end", "07:00"),
            ],
        )
        .await;
    let own_rule: i64 = sqlx::query_scalar("SELECT id FROM time_rules WHERE device_id = ?")
        .bind(id)
        .fetch_one(&app.db)
        .await
        .unwrap();
    assert_redirect(
        &res,
        &format!("/schedules/rules/{own_rule}?saved=added"),
        Some("rule"),
    );

    // Not in force until the override is on.
    let p = policy(&app, &token).await;
    assert_eq!(p["time_policy"]["rules"][0]["name"], "Skole");
    assert_eq!(
        p["time_policy"]["daily_budget_minutes"],
        json!([60, null, null, null, null, 120, null])
    );

    let mut nudges = app.state.command_notify.subscribe();
    let res = app
        .request_form(
            Method::POST,
            &format!("/schedules/device/{id}"),
            Some(&cookie),
            &[("custom_schedule_enabled", "on"), ("b2", "45")],
        )
        .await;
    assert!(res.status.is_redirection(), "{}", res.text());
    assert_eq!(nudges.try_recv().ok(), Some(id));
    let p = policy(&app, &token).await;
    let rules = p["time_policy"]["rules"].as_array().unwrap();
    assert_eq!(rules.len(), 1);
    assert_eq!(rules[0]["name"], "Leggetid");
    assert_eq!(rules[0]["calls_allowed"], true);
    assert_eq!(rules[0]["days"][6], json!({"start": 1260, "end": 420}));
    assert_eq!(
        p["time_policy"]["daily_budget_minutes"],
        json!([null, null, 45, null, null, null, null])
    );

    let page = app.get_page(&device_page(id, Section::Time), &cookie).await;
    assert_eq!(page.status, StatusCode::OK);
    assert!(page.text().contains("Leggetid"));
}

#[tokio::test]
async fn rule_edit_and_delete_are_scoped() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("phone").await;
    app.request_form(Method::POST, "/schedules/rules", Some(&cookie), SCHOOL_FORM)
        .await;
    let rule_id: i64 = sqlx::query_scalar("SELECT id FROM time_rules")
        .fetch_one(&app.db)
        .await
        .unwrap();

    // Through a device's scope: 404, nothing changed.
    let scope = id.to_string();
    let mut wrong = SCHOOL_FORM.to_vec();
    wrong[0] = ("scope", &scope);
    wrong[1] = ("name", "Hacked");
    let res = app
        .request_form(
            Method::POST,
            &format!("/schedules/rules/{rule_id}"),
            Some(&cookie),
            &wrong,
        )
        .await;
    assert_eq!(res.status, StatusCode::NOT_FOUND);
    let res = app
        .request_form(
            Method::POST,
            &format!("/schedules/rules/{rule_id}/delete"),
            Some(&cookie),
            &[("scope", &scope)],
        )
        .await;
    assert_eq!(res.status, StatusCode::NOT_FOUND);
    // A rule that is gone (deleted in another tab): back to the list, nothing written; an
    // unknown device scope is a 404.
    let res = app
        .request_form(
            Method::POST,
            "/schedules/rules/9999",
            Some(&cookie),
            SCHOOL_FORM,
        )
        .await;
    assert_redirect(&res, "/schedules?notice=gone_unsaved", Some("rules"));
    let mut ghost = SCHOOL_FORM.to_vec();
    ghost[0] = ("scope", "9999");
    let res = app
        .request_form(Method::POST, "/schedules/rules", Some(&cookie), &ghost)
        .await;
    assert_eq!(res.status, StatusCode::NOT_FOUND);

    // Edit through the right scope.
    let mut edit = SCHOOL_FORM.to_vec();
    edit[1] = ("name", "School");
    edit.push(("calls_allowed", "on"));
    let res = app
        .request_form(
            Method::POST,
            &format!("/schedules/rules/{rule_id}"),
            Some(&cookie),
            &edit,
        )
        .await;
    assert_redirect(
        &res,
        &format!("/schedules/rules/{rule_id}?saved=1"),
        Some("rule"),
    );
    let rule = policy(&app, &token).await["time_policy"]["rules"][0].clone();
    assert_eq!(rule["name"], "School");
    assert_eq!(rule["calls_allowed"], true);

    let res = app
        .request_form(
            Method::POST,
            &format!("/schedules/rules/{rule_id}/delete"),
            Some(&cookie),
            &[("scope", "")],
        )
        .await;
    assert_redirect(&res, "/schedules?notice=deleted", Some("rules"));
    assert_eq!(
        policy(&app, &token).await["time_policy"]["rules"],
        json!([])
    );
    // A second tap on Delete lands on the list too, with nothing to delete.
    let res = app
        .request_form(
            Method::POST,
            &format!("/schedules/rules/{rule_id}/delete"),
            Some(&cookie),
            &[("scope", "")],
        )
        .await;
    assert_redirect(&res, "/schedules?notice=gone", Some("rules"));
}

#[tokio::test]
async fn invalid_rule_and_budget_forms_are_rejected() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let mut nudges = app.state.command_notify.subscribe();
    for bad in [
        vec![("scope", ""), ("name", ""), ("kind", "school")],
        vec![("scope", ""), ("name", "x"), ("kind", "nap")],
        vec![
            ("scope", ""),
            ("name", "x"),
            ("kind", "custom"),
            ("d1_start", "08:00"),
        ],
        vec![
            ("scope", ""),
            ("name", "x"),
            ("kind", "custom"),
            ("exempt_apps", "not a package"),
        ],
    ] {
        let res = app
            .request_form(Method::POST, "/schedules/rules", Some(&cookie), &bad)
            .await;
        assert_eq!(res.status, StatusCode::BAD_REQUEST, "{bad:?}");
    }
    let res = app
        .request_form(
            Method::POST,
            "/schedules/global",
            Some(&cookie),
            &[("b0", "2000")],
        )
        .await;
    assert_eq!(res.status, StatusCode::BAD_REQUEST);
    let res = app
        .request_form(
            Method::POST,
            "/schedules/device/9999",
            Some(&cookie),
            &[("b0", "10")],
        )
        .await;
    assert_eq!(res.status, StatusCode::NOT_FOUND);
    let rules: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM time_rules")
        .fetch_one(&app.db)
        .await
        .unwrap();
    assert_eq!(rules, 0);
    assert!(nudges.try_recv().is_err());
}

#[tokio::test]
async fn corrupt_stored_time_data_is_a_500_not_a_default() {
    for statement in [
        "INSERT INTO time_rules (name, kind, calls_allowed, days_json) VALUES ('x', 'custom', 1, '[1,2]')",
        "INSERT INTO time_rules (name, kind, calls_allowed, exempt_apps_json, days_json) \
         VALUES ('x', 'custom', 1, 'nope', '[null,null,null,null,null,null,null]')",
        "UPDATE global_schedule SET daily_budget_json = '[60]'",
    ] {
        let app = TestApp::new().await;
        let (_, token) = app.enrolled_device("phone").await;
        sqlx::query(statement).execute(&app.db).await.unwrap();
        let res = app
            .request(Method::GET, "/api/devices/policy", Some(&token), None)
            .await;
        assert_eq!(res.status, StatusCode::INTERNAL_SERVER_ERROR, "{statement}");
        assert!(res.body.is_empty());
    }
}

#[tokio::test]
async fn lifts_are_recorded_delivered_and_end() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("phone").await;
    app.request_form(Method::POST, "/schedules/rules", Some(&cookie), SCHOOL_FORM)
        .await;
    let rule_id: i64 = sqlx::query_scalar("SELECT id FROM time_rules")
        .fetch_one(&app.db)
        .await
        .unwrap();

    let mut nudges = app.state.command_notify.subscribe();
    let rule = rule_id.to_string();
    let res = app
        .request_form(
            Method::POST,
            &format!("/devices/{id}/lifts"),
            Some(&cookie),
            &[("target", "rule"), ("rule", &rule), ("minutes", "30")],
        )
        .await;
    // Without a `back`: the summary's quick actions, with the flash (design 31 step 10).
    assert_eq!(
        res.location(),
        Some(format!("/devices/{id}?done=pause#quick").as_str()),
        "{}",
        res.text()
    );
    assert_eq!(nudges.try_recv().ok(), Some(id));
    let res = app
        .request_form(
            Method::POST,
            &format!("/devices/{id}/lifts"),
            Some(&cookie),
            &[("target", "budget"), ("minutes", "15")],
        )
        .await;
    assert!(res.status.is_redirection());

    let lifts = policy(&app, &token).await["time_policy"]["lifts"].clone();
    let lifts = lifts.as_array().unwrap();
    assert_eq!(lifts.len(), 2);
    assert_eq!(lifts[0]["target"], "rule");
    assert_eq!(lifts[0]["rule_id"], rule_id);
    assert_eq!(lifts[0]["minutes"], 30);
    let now_ms = chrono::Utc::now().timestamp_millis();
    let expires = lifts[0]["expires_at_ms"].as_i64().unwrap();
    assert!((expires - now_ms - 30 * 60_000).abs() < 10_000, "{expires}");
    assert_eq!(lifts[1]["target"], "budget");
    assert_eq!(lifts[1]["rule_id"], serde_json::Value::Null);
    assert_eq!(lifts[1]["minutes"], 15);

    let events: i64 =
        sqlx::query_scalar("SELECT COUNT(*) FROM security_events WHERE event_type = 'time_lift'")
            .fetch_one(&app.db)
            .await
            .unwrap();
    assert_eq!(events, 2);
    let page = app.get_page(&device_page(id, Section::Time), &cookie).await;
    assert!(page.text().contains("Skole paused for 30 min"));
    assert!(page.text().contains("End now"));
    assert!(page.text().contains("+15 min screen time"));

    // End the rule pause early: no longer delivered; ending it again changes nothing and lands
    // back (a second tap); a pause this phone never had is a 404.
    let lift_id = lifts[0]["id"].as_i64().unwrap();
    let res = app
        .request_form(
            Method::POST,
            &format!("/devices/{id}/lifts/{lift_id}/end"),
            Some(&cookie),
            &[],
        )
        .await;
    assert!(res.status.is_redirection(), "{}", res.text());
    let lifts = policy(&app, &token).await["time_policy"]["lifts"].clone();
    assert_eq!(lifts.as_array().unwrap().len(), 1);
    let res = app
        .request_form(
            Method::POST,
            &format!("/devices/{id}/lifts/{lift_id}/end"),
            Some(&cookie),
            &[],
        )
        .await;
    assert_redirect(&res, &format!("/devices/{id}?done=over"), Some("quick"));
    let ended: i64 = sqlx::query_scalar(
        "SELECT COUNT(*) FROM security_events WHERE event_type = 'time_lift_ended'",
    )
    .fetch_one(&app.db)
    .await
    .unwrap();
    assert_eq!(ended, 1, "the second tap logs nothing");
    let res = app
        .request_form(
            Method::POST,
            &format!("/devices/{id}/lifts/999/end"),
            Some(&cookie),
            &[],
        )
        .await;
    assert_eq!(res.status, StatusCode::NOT_FOUND);
    // Another phone's running pause can't be ended through this phone's URL (qa-31-s9-code L4).
    let (other, _) = app.enrolled_device("other").await;
    let theirs: i64 = sqlx::query_scalar(
        "INSERT INTO time_lifts (device_id, target, minutes, expires_at) \
         VALUES (?, 'rule', 30, datetime('now', '+30 minutes')) RETURNING id",
    )
    .bind(other)
    .fetch_one(&app.db)
    .await
    .unwrap();
    let res = app
        .request_form(
            Method::POST,
            &format!("/devices/{id}/lifts/{theirs}/end"),
            Some(&cookie),
            &[("back", "time")],
        )
        .await;
    assert_eq!(res.status, StatusCode::NOT_FOUND);
    let ended: Option<String> =
        sqlx::query_scalar("SELECT ended_early_at FROM time_lifts WHERE id = ?")
            .bind(theirs)
            .fetch_one(&app.db)
            .await
            .unwrap();
    assert_eq!(ended, None, "the other phone's pause keeps running");

    // An expired lift isn't delivered.
    sqlx::query("UPDATE time_lifts SET expires_at = datetime('now', '-1 minute')")
        .execute(&app.db)
        .await
        .unwrap();
    assert_eq!(
        policy(&app, &token).await["time_policy"]["lifts"],
        json!([])
    );
    let page = app.get_page(&device_page(id, Section::Time), &cookie).await;
    assert!(page.text().contains("Ended early"));
}

#[tokio::test]
async fn invalid_lifts_are_rejected() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, _) = app.enrolled_device("phone").await;
    let (other, _) = app.enrolled_device("other").await;
    // A rule of another device (its own, override off) doesn't apply here.
    let scope = other.to_string();
    let mut form = SCHOOL_FORM.to_vec();
    form[0] = ("scope", &scope);
    app.request_form(Method::POST, "/schedules/rules", Some(&cookie), &form)
        .await;
    let foreign: i64 = sqlx::query_scalar("SELECT id FROM time_rules")
        .fetch_one(&app.db)
        .await
        .unwrap();
    let foreign = foreign.to_string();
    for bad in [
        vec![("target", "rule"), ("rule", "all"), ("minutes", "45")],
        vec![("target", "budget"), ("minutes", "120")],
        vec![("target", "everything"), ("minutes", "30")],
        vec![("target", "rule"), ("rule", "seven"), ("minutes", "30")],
    ] {
        let res = app
            .request_form(
                Method::POST,
                &format!("/devices/{id}/lifts"),
                Some(&cookie),
                &bad,
            )
            .await;
        assert_eq!(res.status, StatusCode::BAD_REQUEST, "{bad:?}");
    }
    // A rule that doesn't apply to this phone (deleted or switched off since the page loaded):
    // back to the page with nothing written.
    let res = app
        .request_form(
            Method::POST,
            &format!("/devices/{id}/lifts"),
            Some(&cookie),
            &[
                ("target", "rule"),
                ("rule", &foreign),
                ("minutes", "30"),
                ("back", "time"),
            ],
        )
        .await;
    assert_redirect(
        &res,
        &format!("/devices/{id}/time?done=gone"),
        Some("pause"),
    );
    // No rule applies to this phone at all: Pause a Rule still shows, with the reason, so the
    // fragment lands on it (qa-31-s9-code M1).
    let html = app
        .get_page(&format!("/devices/{id}/time?done=gone"), &cookie)
        .await
        .text();
    let pause = &html[html.find("<section id=\"pause\"").expect("the pause group")..];
    let pause = &pause[..pause.find("</section>").unwrap()];
    assert!(pause.contains("data-flash=\"pause\""), "{pause}");
    assert!(pause.contains("No rules apply to this phone right now."));
    assert!(pause.contains("flash-error"));
    // From the summary (or an older tab without `back`) the reason lands in its quick actions
    // (design 31 step 10; qa-31-s9-code N4 sent it to Device › Time while the summary had no word
    // for it).
    for back in [&[("back", "summary")][..], &[][..]] {
        let fields: Vec<(&str, &str)> = [("target", "rule"), ("rule", &foreign), ("minutes", "30")]
            .into_iter()
            .chain(back.iter().copied())
            .collect();
        let res = app
            .request_form(
                Method::POST,
                &format!("/devices/{id}/lifts"),
                Some(&cookie),
                &fields,
            )
            .await;
        assert_redirect(&res, &format!("/devices/{id}?done=gone"), Some("quick"));
    }
    let html = app
        .get_page(&format!("/devices/{id}?done=gone"), &cookie)
        .await
        .text();
    let quick = &html[html
        .find("<section id=\"quick\"")
        .expect("the quick actions")..];
    let quick = &quick[..quick.find("</section>").unwrap()];
    assert!(
        quick.contains("That rule no longer applies to this phone. Nothing was paused."),
        "{quick}"
    );
    assert!(quick.contains("flash-error"), "{quick}");
    // Without a refusal and without rules, there is no Pause group.
    let html = app
        .get_page(&device_page(id, Section::Time), &cookie)
        .await
        .text();
    assert!(!html.contains("<section id=\"pause\""));
    let res = app
        .request_form(
            Method::POST,
            "/devices/9999/lifts",
            Some(&cookie),
            &[("target", "rule"), ("minutes", "30")],
        )
        .await;
    assert_eq!(res.status, StatusCode::NOT_FOUND);
    let lifts: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM time_lifts")
        .fetch_one(&app.db)
        .await
        .unwrap();
    assert_eq!(lifts, 0);
}

#[tokio::test]
async fn time_state_is_stored_and_shown() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("phone").await;
    // An older launcher: no time_rules_v1 -> warning.
    app.request(
        Method::POST,
        "/api/devices/status",
        Some(&token),
        Some(json!({"lock_reason": "NONE", "kiosk_engaged": true, "capabilities": ["call_policy_v1"]})),
    )
    .await;
    let page = app.get_page(&device_page(id, Section::Time), &cookie).await;
    assert!(page.text().contains("Vibb One too old for time rules"));
    let today = chrono::Utc::now()
        .with_timezone(&chrono_tz::Europe::Oslo)
        .date_naive()
        .to_string();

    let res = app
        .request(
            Method::POST,
            "/api/devices/status",
            Some(&token),
            Some(json!({
                "lock_reason": "SCHOOL",
                "kiosk_engaged": true,
                "capabilities": ["call_policy_v1", "time_rules_v1"],
                "time_state": {
                    "day": today, "used_minutes": 42, "budget_minutes": 90,
                    "extra_minutes": 30, "active_rule_id": 1, "active_rule_name": "Skole",
                    "calls_blocked": true, "lock_reason": "SCHOOL", "lifts_active": []
                }
            })),
        )
        .await;
    assert_eq!(res.status, StatusCode::NO_CONTENT);
    let stored: Option<String> = sqlx::query_scalar(
        "SELECT time_state_json FROM device_status WHERE device_id = ? ORDER BY id DESC LIMIT 1",
    )
    .bind(id)
    .fetch_one(&app.db)
    .await
    .unwrap();
    assert!(stored.unwrap().contains("\"used_minutes\":42"));
    let page = app.get_page(&device_page(id, Section::Time), &cookie).await;
    let text = page.text();
    // Calls aren't managed here, so no "calls off"; no zone from the phone, so no end.
    assert!(
        text.contains("<span class=\"badge badge-lock\">Skole</span>"),
        "{text}"
    );
    assert!(text.contains(">42 of 90 min today<"), "{text}");
    assert!(text.contains("Includes 30 min extra"));
    assert!(!text.contains("Vibb One too old for time rules"));

    // Not an object, or too big: not stored.
    for bad in [json!("SCHOOL"), json!({"x": "y".repeat(5000)})] {
        app.request(
            Method::POST,
            "/api/devices/status",
            Some(&token),
            Some(json!({"lock_reason": "NONE", "kiosk_engaged": true, "time_state": bad})),
        )
        .await;
        let stored: Option<String> = sqlx::query_scalar(
            "SELECT time_state_json FROM device_status WHERE device_id = ? ORDER BY id DESC LIMIT 1",
        )
        .bind(id)
        .fetch_one(&app.db)
        .await
        .unwrap();
        assert!(stored.is_none());
    }
}

#[tokio::test]
async fn update_location_now_queues_locate() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("phone").await;
    let mut nudges = app.state.command_notify.subscribe();
    let res = app
        .request_form(
            Method::POST,
            &format!("/devices/{id}/command/locate"),
            Some(&cookie),
            &[],
        )
        .await;
    assert_eq!(
        res.location(),
        Some(format!("/devices/locate?device={id}&requested=1#location").as_str())
    );
    assert_eq!(nudges.try_recv().ok(), Some(id));
    let p = policy(&app, &token).await;
    assert_eq!(p["pending_command"]["command"], "locate");

    let page = app
        .get_page(&format!("/devices/locate?device={id}&requested=1"), &cookie)
        .await;
    let text = page.text();
    assert!(text.contains("Waiting for a fresh fix"));
    assert!(text.contains(">Update Location</button>"));
    // A newer fix reloads at the map, not the top (qa-31-s13-code L1): static/locate.js waits
    // while the map box says so (design 44).
    assert!(text.contains(" data-waiting>"));
    let script = std::fs::read_to_string("static/locate.js").unwrap();
    assert!(
        script.contains("win.location.replace(\"/devices/locate?device=\" + id + \"#location\")")
    );
    assert!(!text.contains("couple of minutes"));

    app.request(
        Method::POST,
        "/api/devices/status",
        Some(&token),
        Some(json!({
            "lock_reason": "NONE", "kiosk_engaged": true,
            "location": {"latitude": 59.9, "longitude": 10.7, "accuracy_meters": 12.4,
                         "captured_at": chrono::Utc::now().to_rfc3339()}
        })),
    )
    .await;
    let page = app
        .get_page(&format!("/devices/locate?device={id}"), &cookie)
        .await;
    assert!(page.text().contains(">just now<"), "{}", page.text());
    assert!(page.text().contains("Within 12 m"), "{}", page.text());
}

#[tokio::test]
async fn location_policy_form_round_trip() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("phone").await;
    let mut nudges = app.state.command_notify.subscribe();
    let res = app
        .request_form(
            Method::POST,
            &format!("/devices/{id}/location-policy"),
            Some(&cookie),
            &[("mode", "interval"), ("interval_minutes", "60")],
        )
        .await;
    assert!(res.status.is_redirection(), "{}", res.text());
    assert_eq!(nudges.try_recv().ok(), Some(id));
    assert_eq!(
        policy(&app, &token).await["location_policy"],
        json!({"mode": "interval", "interval_minutes": 60, "zones": []})
    );
    // Mode only keeps the interval.
    app.request_form(
        Method::POST,
        &format!("/devices/{id}/location-policy"),
        Some(&cookie),
        &[("mode", "off")],
    )
    .await;
    assert_eq!(
        policy(&app, &token).await["location_policy"],
        json!({"mode": "off", "interval_minutes": 60, "zones": []})
    );
    for bad in [
        vec![("mode", "always")],
        vec![("mode", "interval"), ("interval_minutes", "7")],
    ] {
        let res = app
            .request_form(
                Method::POST,
                &format!("/devices/{id}/location-policy"),
                Some(&cookie),
                &bad,
            )
            .await;
        assert_eq!(res.status, StatusCode::BAD_REQUEST);
    }
    let res = app
        .request_form(
            Method::POST,
            "/devices/9999/location-policy",
            Some(&cookie),
            &[("mode", "off")],
        )
        .await;
    assert_eq!(res.status, StatusCode::NOT_FOUND);
    let page = app
        .get_page(&format!("/devices/locate?device={id}"), &cookie)
        .await;
    assert!(page.text().contains("Location is off for this phone"));
}

#[tokio::test]
async fn legacy_schedules_become_rules_once() {
    // Every migration before 0025, the old schedule written the old way, then the rest.
    let dir = tempfile::tempdir().unwrap();
    let path = dir.path().join("old.db");
    let options = sqlx::sqlite::SqliteConnectOptions::new()
        .filename(&path)
        .create_if_missing(true)
        .foreign_keys(true);
    let db = sqlx::SqlitePool::connect_with(options).await.unwrap();
    let full = sqlx::migrate!("./migrations");
    let mut old = sqlx::migrate!("./migrations");
    old.migrations = full
        .migrations
        .iter()
        .filter(|m| m.version < 25)
        .cloned()
        .collect::<Vec<_>>()
        .into();
    old.run(&db).await.unwrap();
    sqlx::query(
        "UPDATE global_schedule SET weekday_start_minutes = 420, weekday_end_minutes = 1200, \
         weekend_start_minutes = 480, weekend_end_minutes = 1260, \
         bedtime_start_minutes = 1260, bedtime_end_minutes = 420",
    )
    .execute(&db)
    .await
    .unwrap();
    let custom: i64 = sqlx::query_scalar("INSERT INTO devices (name) VALUES ('a') RETURNING id")
        .fetch_one(&db)
        .await
        .unwrap();
    sqlx::query(
        "INSERT INTO device_policy (device_id, kiosk_desired, custom_schedule_enabled, \
         bedtime_start_minutes, bedtime_end_minutes) VALUES (?, 1, 1, 1200, 360)",
    )
    .bind(custom)
    .execute(&db)
    .await
    .unwrap();
    db.close().await;

    // connect_db runs the remaining migrations and the conversion - twice, like two restarts.
    let url = format!("sqlite://{}", path.display());
    let db = crate::connect_db(&url).await;
    db.close().await;
    let db = crate::connect_db(&url).await;

    let rows: Vec<(Option<i64>, String, String, bool, String)> = sqlx::query_as(
        "SELECT device_id, name, kind, calls_allowed, days_json FROM time_rules \
         ORDER BY device_id IS NOT NULL, sort_order",
    )
    .fetch_all(&db)
    .await
    .unwrap();
    assert_eq!(rows.len(), 3, "{rows:?}");
    assert_eq!(rows[0].0, None);
    assert_eq!(rows[0].2, "bedtime");
    assert!(rows[0].3);
    assert_eq!(rows[1].1, "Outside allowed hours");
    assert_eq!(rows[1].2, "custom");
    // Friday: locked from 20:00 until Saturday 08:00.
    let days: serde_json::Value = serde_json::from_str(&rows[1].4).unwrap();
    assert_eq!(days[4], json!({"start": 1200, "end": 480}));
    assert_eq!(rows[2].0, Some(custom));
    assert_eq!(rows[2].1, "Bedtime");
    let migrated: Vec<i64> = sqlx::query_scalar(
        "SELECT rules_migrated FROM global_schedule UNION ALL SELECT rules_migrated FROM device_policy",
    )
    .fetch_all(&db)
    .await
    .unwrap();
    assert_eq!(migrated, vec![1, 1]);

    // A device created afterwards has nothing to convert.
    let fresh: i64 = sqlx::query_scalar("INSERT INTO devices (name) VALUES ('b') RETURNING id")
        .fetch_one(&db)
        .await
        .unwrap();
    sqlx::query("INSERT INTO device_policy (device_id, kiosk_desired) VALUES (?, 1)")
        .bind(fresh)
        .execute(&db)
        .await
        .unwrap();
    let flag: i64 =
        sqlx::query_scalar("SELECT rules_migrated FROM device_policy WHERE device_id = ?")
            .bind(fresh)
            .fetch_one(&db)
            .await
            .unwrap();
    assert_eq!(flag, 1);
}

#[tokio::test]
async fn legacy_fields_are_still_sent_for_older_launchers() {
    let app = TestApp::new().await;
    let (_, token) = app.enrolled_device("phone").await;
    sqlx::query(
        "UPDATE global_schedule SET bedtime_start_minutes = 1260, bedtime_end_minutes = 420",
    )
    .execute(&app.db)
    .await
    .unwrap();
    let p = policy(&app, &token).await;
    assert_eq!(p["bedtime_start_minutes"], 1260);
    assert_eq!(p["bedtime_end_minutes"], 420);
}

// QA step 6 fix round

async fn count(app: &TestApp, sql: &str) -> i64 {
    sqlx::query_scalar(sql).fetch_one(&app.db).await.unwrap()
}

#[tokio::test]
async fn new_admin_routes_refuse_without_a_session_and_write_nothing() {
    let app = TestApp::new().await;
    let (id, _) = app.enrolled_device("phone").await;
    sqlx::query(
        "INSERT INTO time_rules (id, device_id, name, kind, calls_allowed, days_json) \
         VALUES (7, NULL, 'Skole', 'school', 0, '[null,null,null,null,null,null,null]')",
    )
    .execute(&app.db)
    .await
    .unwrap();
    sqlx::query(
        "INSERT INTO time_lifts (id, device_id, target, rule_id, minutes, expires_at) \
         VALUES (3, ?, 'rule', 7, 30, datetime('now', '+30 minutes'))",
    )
    .bind(id)
    .execute(&app.db)
    .await
    .unwrap();
    let before_policy: (String, String, i64) = sqlx::query_as(
        "SELECT daily_budget_json, location_mode, custom_schedule_enabled FROM device_policy \
         WHERE device_id = ?",
    )
    .bind(id)
    .fetch_one(&app.db)
    .await
    .unwrap();
    let before_global: String =
        sqlx::query_scalar("SELECT daily_budget_json FROM global_schedule WHERE id = 1")
            .fetch_one(&app.db)
            .await
            .unwrap();
    let mut nudges = app.state.command_notify.subscribe();

    let routes: Vec<(String, Vec<(&str, &str)>)> = vec![
        ("/schedules/rules".into(), SCHOOL_FORM.to_vec()),
        ("/schedules/rules/7".into(), SCHOOL_FORM.to_vec()),
        ("/schedules/rules/7/delete".into(), vec![("scope", "")]),
        ("/schedules/global".into(), vec![("b0", "5")]),
        (
            format!("/schedules/device/{id}"),
            vec![("custom_schedule_enabled", "on"), ("b0", "5")],
        ),
        (
            format!("/devices/{id}/lifts"),
            vec![("target", "budget"), ("minutes", "15")],
        ),
        (format!("/devices/{id}/lifts/3/end"), vec![]),
        (
            format!("/devices/{id}/location-policy"),
            vec![("mode", "off")],
        ),
        (format!("/devices/{id}/command/locate"), vec![]),
    ];
    for (path, fields) in &routes {
        let res = app.request_form(Method::POST, path, None, fields).await;
        assert!(
            res.location() == Some("/login") || res.status == StatusCode::UNAUTHORIZED,
            "{path}: {} {:?}",
            res.status,
            res.location()
        );
    }

    assert_eq!(count(&app, "SELECT COUNT(*) FROM time_rules").await, 1);
    assert_eq!(
        count(
            &app,
            "SELECT COUNT(*) FROM time_rules WHERE name = 'Skole' AND calls_allowed = 0"
        )
        .await,
        1
    );
    assert_eq!(count(&app, "SELECT COUNT(*) FROM time_lifts").await, 1);
    assert_eq!(
        count(
            &app,
            "SELECT COUNT(*) FROM time_lifts WHERE ended_early_at IS NOT NULL"
        )
        .await,
        0
    );
    assert_eq!(count(&app, "SELECT COUNT(*) FROM device_commands").await, 0);
    assert_eq!(count(&app, "SELECT COUNT(*) FROM security_events WHERE event_type LIKE 'time_%' OR event_type LIKE 'location_%' OR event_type = 'screen_time_saved' OR event_type = 'device_command_queued'").await, 0);
    let after_policy: (String, String, i64) = sqlx::query_as(
        "SELECT daily_budget_json, location_mode, custom_schedule_enabled FROM device_policy \
         WHERE device_id = ?",
    )
    .bind(id)
    .fetch_one(&app.db)
    .await
    .unwrap();
    assert_eq!(before_policy, after_policy);
    let after_global: String =
        sqlx::query_scalar("SELECT daily_budget_json FROM global_schedule WHERE id = 1")
            .fetch_one(&app.db)
            .await
            .unwrap();
    assert_eq!(before_global, after_global);
    assert!(nudges.try_recv().is_err());

    // The read-only JSON for the locate page is behind the session too.
    let res = app
        .request(
            Method::GET,
            &format!("/devices/{id}/locate-result.json"),
            None,
            None,
        )
        .await;
    assert!(res.location() == Some("/login") || res.status == StatusCode::UNAUTHORIZED);
}

#[tokio::test]
async fn a_calls_off_rule_on_a_device_with_unmanaged_calls_warns() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, _) = app.enrolled_device("phone").await;
    // Device › Time's warning, and the device page's Attention row (it links to Calls & Contacts).
    const WARNING: &str = "Incoming calls aren&#39;t blocked";
    const ROW: &str = "data-attention=\"incoming-calls\"";
    let time = device_page(id, Section::Time);
    let summary = device_page(id, Section::Summary);
    assert!(
        !app.get_page(&time, &cookie).await.text().contains(WARNING),
        "no rule yet"
    );
    assert!(!app.get_page(&summary, &cookie).await.text().contains(ROW));

    app.request_form(Method::POST, "/schedules/rules", Some(&cookie), SCHOOL_FORM)
        .await;
    let page = app.get_page(&time, &cookie).await.text();
    assert!(page.contains(WARNING), "{page}");
    assert!(app.get_page(&summary, &cookie).await.text().contains(ROW));

    sqlx::query("UPDATE device_policy SET calls_managed = 1 WHERE device_id = ?")
        .bind(id)
        .execute(&app.db)
        .await
        .unwrap();
    assert!(!app.get_page(&time, &cookie).await.text().contains(WARNING));
    assert!(!app.get_page(&summary, &cookie).await.text().contains(ROW));

    // Calls unmanaged but every rule allows calls: no warning.
    sqlx::query("UPDATE device_policy SET calls_managed = 0 WHERE device_id = ?")
        .bind(id)
        .execute(&app.db)
        .await
        .unwrap();
    sqlx::query("UPDATE time_rules SET calls_allowed = 1")
        .execute(&app.db)
        .await
        .unwrap();
    assert!(!app.get_page(&time, &cookie).await.text().contains(WARNING));
    assert!(!app.get_page(&summary, &cookie).await.text().contains(ROW));
}

async fn report_time_state(app: &TestApp, token: &str, state: serde_json::Value) {
    let res = app
        .request(
            Method::POST,
            "/api/devices/status",
            Some(token),
            Some(json!({
                "lock_reason": "NONE", "kiosk_engaged": true,
                "capabilities": ["call_policy_v1", "time_rules_v1"],
                "time_state": state
            })),
        )
        .await;
    assert_eq!(res.status, StatusCode::NO_CONTENT);
}

fn time_state(lifts_active: serde_json::Value) -> serde_json::Value {
    json!({
        "day": "2026-10-05", "used_minutes": 1, "budget_minutes": null, "extra_minutes": 0,
        "active_rule_id": null, "active_rule_name": null, "calls_blocked": false,
        "lock_reason": "NONE", "lifts_active": lifts_active
    })
}

#[tokio::test]
async fn lift_status_comes_from_the_phone() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("phone").await;
    app.request_form(Method::POST, "/schedules/rules", Some(&cookie), SCHOOL_FORM)
        .await;
    let rule: i64 = sqlx::query_scalar("SELECT id FROM time_rules")
        .fetch_one(&app.db)
        .await
        .unwrap();
    // An older report: the phone hasn't seen the lift yet.
    report_time_state(&app, &token, time_state(json!([]))).await;
    sqlx::query("UPDATE device_status SET reported_at = datetime('now', '-1 minute')")
        .execute(&app.db)
        .await
        .unwrap();
    app.request_form(
        Method::POST,
        &format!("/devices/{id}/lifts"),
        Some(&cookie),
        &[
            ("target", "rule"),
            ("rule", &rule.to_string()),
            ("minutes", "30"),
        ],
    )
    .await;
    let (lift, expires): (i64, String) = sqlx::query_as("SELECT id, expires_at FROM time_lifts")
        .fetch_one(&app.db)
        .await
        .unwrap();
    let time = device_page(id, Section::Time);
    let status = |text: &str| -> String {
        let row = &text[text.find(&format!("data-lift=\"{lift}\"")).unwrap()..];
        let value = &row[row.find("class=\"row-value\">").unwrap() + 18..];
        value[..value.find('<').unwrap()].to_string()
    };
    let text = app.get_page(&time, &cookie).await.text();
    assert_eq!(status(&text), "Waiting for the phone");

    // The phone runs it: until the server's expiry, in the Phone Setup zone (Oslo by default),
    // never the UTC clock.
    report_time_state(&app, &token, time_state(json!([lift]))).await;
    let text = app.get_page(&time, &cookie).await.text();
    let end = crate::display_time::parse_db_utc(&expires).unwrap();
    let oslo = crate::display_time::Zone::from_setting("Europe/Oslo");
    assert_eq!(
        status(&text),
        format!("Until {}", oslo.until(end, chrono::Utc::now()))
    );
    assert!(!status(&text).contains(&end.format("%H:%M").to_string()));

    // A reboot ended it on the phone, though the server's expiry hasn't passed.
    report_time_state(&app, &token, time_state(json!([]))).await;
    let text = app.get_page(&time, &cookie).await.text();
    assert_eq!(status(&text), "Ended on the phone");
    assert!(text.contains("End now"), "the server still sends it");
}

#[tokio::test]
async fn an_unreadable_ledger_is_shown() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("phone").await;
    let mut state = time_state(json!([]));
    report_time_state(&app, &token, state.clone()).await;
    let time = device_page(id, Section::Time);
    let summary = device_page(id, Section::Summary);
    assert!(
        !app.get_page(&time, &cookie)
            .await
            .text()
            .contains("Screen-time record unreadable")
    );
    state["ledger_unreadable"] = json!(true);
    report_time_state(&app, &token, state).await;
    let text = app.get_page(&time, &cookie).await.text();
    assert!(text.contains("Screen-time record unreadable"), "{text}");
    assert!(text.contains("screen time as used up"));
    // The summary keeps a signal: the Attention row to Device › Time, and the row's value.
    let text = app.get_page(&summary, &cookie).await.text();
    assert!(text.contains(&format!(
        "href=\"/devices/{id}/time\" data-attention=\"screen-time-record\""
    )));
    assert!(
        text.contains("row-value row-value-action\">Needs attention<"),
        "{text}"
    );
}

#[tokio::test]
async fn parent_edits_are_in_the_security_log() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, _) = app.enrolled_device("phone").await;
    app.request_form(Method::POST, "/schedules/rules", Some(&cookie), SCHOOL_FORM)
        .await;
    let rule: i64 = sqlx::query_scalar("SELECT id FROM time_rules")
        .fetch_one(&app.db)
        .await
        .unwrap();
    app.request_form(
        Method::POST,
        &format!("/schedules/rules/{rule}"),
        Some(&cookie),
        SCHOOL_FORM,
    )
    .await;
    app.request_form(
        Method::POST,
        &format!("/schedules/rules/{rule}/delete"),
        Some(&cookie),
        &[("scope", "")],
    )
    .await;
    app.request_form(
        Method::POST,
        "/schedules/global",
        Some(&cookie),
        &[("b0", "60")],
    )
    .await;
    app.request_form(
        Method::POST,
        &format!("/schedules/device/{id}"),
        Some(&cookie),
        &[("b0", "30")],
    )
    .await;
    app.request_form(
        Method::POST,
        &format!("/devices/{id}/location-policy"),
        Some(&cookie),
        &[("mode", "interval"), ("interval_minutes", "15")],
    )
    .await;
    let events: Vec<(String, String, String)> = sqlx::query_as(
        "SELECT event_type, username, detail FROM security_events \
         WHERE event_type IN ('time_rule_created', 'time_rule_updated', 'time_rule_deleted', \
         'screen_time_saved', 'location_policy_changed') ORDER BY id",
    )
    .fetch_all(&app.db)
    .await
    .unwrap();
    let kinds: Vec<&str> = events.iter().map(|e| e.0.as_str()).collect();
    assert_eq!(
        kinds,
        [
            "time_rule_created",
            "time_rule_updated",
            "time_rule_deleted",
            "screen_time_saved",
            "screen_time_saved",
            "location_policy_changed"
        ]
    );
    assert!(events.iter().all(|e| e.1 == "parent"));
    assert_eq!(events[0].2, "global: Skole");
    assert!(events[4].2.starts_with(&format!("device {id}: ")));
    assert_eq!(events[5].2, format!("device {id}: interval, every 15 min"));
}

#[tokio::test]
async fn the_phones_locate_answer_is_shown() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("phone").await;
    let res = app
        .get_page(&format!("/devices/{id}/locate-result.json"), &cookie)
        .await;
    assert_eq!(res.json(), json!({}));
    app.request_form(
        Method::POST,
        &format!("/devices/{id}/command/locate"),
        Some(&cookie),
        &[],
    )
    .await;
    let cmd = policy(&app, &token).await["pending_command"]["id"]
        .as_i64()
        .unwrap();
    let res = app
        .get_page(&format!("/devices/{id}/locate-result.json"), &cookie)
        .await;
    assert_eq!(res.json(), json!({"id": cmd, "result": null, "text": null}));
    app.request(
        Method::POST,
        "/api/devices/command-result",
        Some(&token),
        Some(json!({"command_id": cmd, "success": false, "message": "location is off for this device"})),
    )
    .await;
    let res = app
        .get_page(&format!("/devices/{id}/locate-result.json"), &cookie)
        .await;
    assert_eq!(
        res.json(),
        json!({"id": cmd, "result": "location is off for this device",
               "text": "Location is off on the phone"})
    );
    let text = app
        .get_page(&format!("/devices/locate?device={id}"), &cookie)
        .await
        .text();
    assert!(text.contains("Location is off on the phone"), "{text}");
    assert!(text.contains("<option value=\"off\">Off</option>"));
}

/// Security review L5: a rule's name never lands inside script - it goes into a `data-` attribute
/// (HTML-escaped) and the confirm reads it from there.
#[tokio::test]
async fn a_rule_name_is_never_script() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, _) = app.enrolled_device("phone").await;
    let name = "<b>x</b>');alert(1);(\"";
    let escaped = "&#60;b&#62;x&#60;/b&#62;&#39;);alert(1);(&#34;";
    for (rule, device) in [(7, None), (8, Some(id))] {
        sqlx::query(
            "INSERT INTO time_rules (id, device_id, name, kind, calls_allowed, days_json) \
             VALUES (?, ?, ?, 'school', 0, '[null,null,null,null,null,null,null]')",
        )
        .bind(rule)
        .bind(device)
        .bind(name)
        .execute(&app.db)
        .await
        .unwrap();
    }
    sqlx::query("UPDATE device_policy SET custom_schedule_enabled = 1 WHERE device_id = ?")
        .bind(id)
        .execute(&app.db)
        .await
        .unwrap();
    // The list, both editors (title, name field, the delete's sentence) and Device › Time (its own
    // rules and the Pause picker) show it escaped; no confirm() is built from it any more.
    for page in [
        "/schedules".to_string(),
        "/schedules/rules/7".to_string(),
        "/schedules/rules/8".to_string(),
        device_page(id, Section::Time),
    ] {
        let html = app.get_page(&page, &cookie).await.text();
        assert!(html.contains(escaped), "{page}: {html}");
        assert!(!html.contains("<b>x</b>"), "{page}: the raw name");
        assert!(!html.contains("alert(1);(\""), "{page}: the raw name");
        assert!(!html.contains("confirm("), "{page}");
    }
    let editor = app.get_page("/schedules/rules/7", &cookie).await.text();
    assert!(editor.contains(&format!("<title>{escaped} · Vibb Phone</title>")));
    assert!(editor.contains(&format!("value=\"{escaped}\"")));
}
