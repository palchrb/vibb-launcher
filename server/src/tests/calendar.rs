//! Dagen (design 69): the policy's `calendar`, the status report's `calendar` and the shared contract
//! `testdata/calendar_status_report.json`; the Calendar pages further down.

use axum::http::{Method, StatusCode};
use serde_json::{Value, json};

use super::TestApp;

/// The launcher's body (`CalendarReportTest` encodes exactly this; byte-identical in its test resources).
const CALENDAR_REPORT: &str = include_str!("../../testdata/calendar_status_report.json");

/// A rule with this id, in order.
async fn add_rule(app: &TestApp, device: i64, id: i64, sort: i64, sql_values: &str) {
    sqlx::query(&format!(
        "INSERT INTO device_calendar_rules \
         (id, device_id, sort_order, calendar_key, field, match_kind, pattern, action, replacement) \
         VALUES ({id}, {device}, {sort}, {sql_values})"
    ))
    .execute(&app.db)
    .await
    .unwrap();
}

#[tokio::test]
async fn a_launcher_report_lists_the_calendars() {
    let app = TestApp::new().await;
    let (id, token) = app.enrolled_device("Anna").await;
    add_rule(
        &app,
        id,
        9,
        0,
        "NULL, 'title', 'contains', '6. og 7. trinn', 'hide', ''",
    )
    .await;
    let report: Value = serde_json::from_str(CALENDAR_REPORT).unwrap();
    let res = app
        .request(
            Method::POST,
            "/api/devices/status",
            Some(&token),
            Some(report.clone()),
        )
        .await;
    assert_eq!(res.status, StatusCode::NO_CONTENT);
    let kept = crate::calendar::last_report(&app.db, id)
        .await
        .unwrap()
        .unwrap();
    assert!(kept.readable);
    let keys: Vec<&str> = kept.calendars.iter().map(|c| c.key.as_str()).collect();
    assert_eq!(
        keys,
        ["9f3a61c2d84be705", "c04e7b19a2f6d853", "51d8e0aa3b7c9462"]
    );
    assert_eq!(
        kept.calendars[0].source.as_deref(),
        Some("skole.kommune.no")
    );
    assert_eq!(kept.calendars[2].source, None);
    assert_eq!(kept.calendars[1].access_level, 700);
    assert_eq!(kept.rules_skipped, [9]);

    // A report without `calendar` (an older launcher) leaves the last one; a rule id the phone doesn't have is
    // dropped from the next.
    let res = app
        .request(
            Method::POST,
            "/api/devices/status",
            Some(&token),
            Some(json!({"lock_reason": "NONE", "kiosk_engaged": true})),
        )
        .await;
    assert_eq!(res.status, StatusCode::NO_CONTENT);
    assert_eq!(
        crate::calendar::last_report(&app.db, id).await.unwrap(),
        Some(kept)
    );
    let mut again = report;
    again["calendar"]["rules_skipped"] = json!([9, 12]);
    again["calendar"]["readable"] = json!(false);
    app.request(
        Method::POST,
        "/api/devices/status",
        Some(&token),
        Some(again),
    )
    .await;
    let kept = crate::calendar::last_report(&app.db, id)
        .await
        .unwrap()
        .unwrap();
    assert!(!kept.readable);
    assert_eq!(kept.rules_skipped, [9]);
}

#[test]
fn the_contract_is_identical_to_the_launchers_copy() {
    let theirs =
        std::fs::read_to_string("../launcher/app/src/test/resources/calendar_status_report.json")
            .expect("the launcher's copy");
    assert_eq!(CALENDAR_REPORT, theirs);
}

#[tokio::test]
async fn the_policy_carries_the_rules_and_the_calendars() {
    let app = TestApp::new().await;
    let (id, token) = app.enrolled_device("Anna").await;
    add_rule(
        &app,
        id,
        7,
        1,
        "'9f3a61c2d84be705', 'title', 'starts', 'Gruppe 2- skoleåret 2026/2027:', 'prefix', 'Skole:'",
    )
    .await;
    add_rule(
        &app,
        id,
        9,
        0,
        "NULL, 'description', 'contains', '6. og 7. trinn', 'hide', ''",
    )
    .await;
    sqlx::query(
        "INSERT INTO device_calendars (device_id, calendar_key, shown, remind, remind_minutes, writable_for_kid) \
         VALUES (?, 'c04e7b19a2f6d853', 1, 'before', 15, 1)",
    )
    .bind(id)
    .execute(&app.db)
    .await
    .unwrap();
    sqlx::query(
        "UPDATE device_policy SET calendar_glance_next = 1, calendar_lock_reminders = 'row', \
         calendar_on_home = 0, calendar_kid_adds = 0 WHERE device_id = ?",
    )
    .bind(id)
    .execute(&app.db)
    .await
    .unwrap();
    let policy = app
        .request(Method::GET, "/api/devices/policy", Some(&token), None)
        .await
        .json();
    assert_eq!(
        policy["calendar"],
        json!({
            "on_home": false,
            "kid_adds": false,
            "glance_next": true,
            "lock_reminders": "row",
            "calendars": [{"key": "c04e7b19a2f6d853", "shown": true, "remind": "before", "remind_minutes": 15,
                           "writable_for_kid": true, "color": null}],
            "rules": [
                {"id": 9, "calendar": null, "field": "description", "match": "contains",
                 "pattern": "6. og 7. trinn", "action": "hide", "text": ""},
                {"id": 7, "calendar": "9f3a61c2d84be705", "field": "title", "match": "starts",
                 "pattern": "Gruppe 2- skoleåret 2026/2027:", "action": "prefix", "text": "Skole:"}
            ],
            "feeds": []
        })
    );
    // At most one calendar a phone is the kid's.
    let second = sqlx::query(
        "INSERT INTO device_calendars (device_id, calendar_key, writable_for_kid) VALUES (?, '9f3a61c2d84be705', 1)",
    )
    .bind(id)
    .execute(&app.db)
    .await;
    assert!(second.is_err());
}

// ---- the pages ---------------------------------------------------------------------------------

/// A phone that reported the contract's calendars.
async fn reported(app: &TestApp) -> (i64, String) {
    let (id, token) = app.enrolled_device("Anna").await;
    let report: Value = serde_json::from_str(CALENDAR_REPORT).unwrap();
    app.request(
        Method::POST,
        "/api/devices/status",
        Some(&token),
        Some(report),
    )
    .await;
    (id, token)
}

async fn settings_saved(app: &TestApp) -> i64 {
    sqlx::query_scalar("SELECT COUNT(*) FROM security_events WHERE event_type = 'settings_saved'")
        .fetch_one(&app.db)
        .await
        .unwrap()
}

fn rule_ids(policy: &Value) -> Vec<i64> {
    policy["calendar"]["rules"]
        .as_array()
        .unwrap()
        .iter()
        .map(|r| r["id"].as_i64().unwrap())
        .collect()
}

#[tokio::test]
async fn the_page_lists_the_groups_and_the_reported_calendars() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, _) = reported(&app).await;
    let page = app
        .get_page(&format!("/devices/{id}/calendar"), &cookie)
        .await;
    assert_eq!(page.status, StatusCode::OK);
    let html = page.text();
    // The lock group above the calendars, the rules last (§3).
    let at = |needle: &str| html.find(needle).unwrap_or_else(|| panic!("{needle}"));
    assert!(at(r#"id="dagen""#) < at(r#"id="lock""#));
    assert!(at(r#"id="lock""#) < at(r#"id="calendars""#));
    assert!(at(r#"id="calendars""#) < at(r#"id="rules""#));
    assert!(html.contains("ICSx5 · skole.kommune.no"), "{html}");
    assert!(html.contains("DAVx5 · nextcloud.example.no"));
    // The contract's report claims calendar_v1; the master switch is off, so each reminder says so.
    assert!(!html.contains("calendar-not-capable"));
    assert_eq!(html.matches("data-reminders-off").count(), 3);
    // The kid's calendar: off, the phone's own (the default) and the one writable DAVx5 calendar.
    assert!(
        html.contains(r#"<option value="phone" selected>"#),
        "{html}"
    );
    assert!(html.contains(r#"<option value="c04e7b19a2f6d853">Familie</option>"#));
    assert!(
        !html.contains(r#"<option value="9f3a61c2d84be705">"#),
        "ICSx5 is read-only"
    );
    // A phone without a report says what to do; Norwegian when the browser asks for it and the account has no
    // language yet (design 70: the account's own wins once stored).
    let (other, _) = app.enrolled_device("Ola").await;
    sqlx::query("UPDATE admin_users SET lang = NULL")
        .execute(&app.db)
        .await
        .unwrap();
    let request = axum::http::Request::builder()
        .uri(format!("/devices/{other}/calendar"))
        .header(axum::http::header::COOKIE, &cookie)
        .header(
            axum::http::header::ACCEPT_LANGUAGE,
            "nb-NO,nb;q=0.9,en;q=0.5",
        )
        .body(axum::body::Body::empty())
        .unwrap();
    let nb = app.send(request).await.text();
    assert!(nb.contains(r#"<html lang="nb">"#));
    assert!(nb.contains("Kalendere som vises") && nb.contains(r#"data-calendars="no-report""#));
    assert!(nb.contains("calendar-not-capable"));
    // That request stored Norwegian as the account's: the summary row is Norwegian too, without the header. Three
    // reported, none hidden.
    let summary = app
        .get_page(&format!("/devices/{id}"), &cookie)
        .await
        .text();
    assert!(summary.contains(r#"data-row="calendar""#));
    assert!(summary.contains("3 vises"), "{summary}");
    assert_eq!(
        app.get_page("/devices/999/calendar", &cookie).await.status,
        StatusCode::NOT_FOUND
    );
}

#[tokio::test]
async fn the_switches_and_the_calendars_save_back_to_their_groups() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = reported(&app).await;
    let page = format!("/devices/{id}/calendar");
    let post = |path: String, fields: Vec<(&'static str, &'static str)>| {
        let (app, cookie) = (&app, cookie.clone());
        async move {
            app.request_form(Method::POST, &path, Some(&cookie), &fields)
                .await
        }
    };
    let lock = post(
        format!("{page}/lock"),
        vec![("glance_next", "on"), ("reminders", "row")],
    )
    .await;
    super::assert_redirect(&lock, &page, Some("lock"));
    app.assert_lands(&lock, &cookie).await;
    let bad = post(format!("{page}/lock"), vec![("reminders", "loud")]).await;
    assert_eq!(bad.status, StatusCode::BAD_REQUEST);
    let dagen = post(format!("{page}/dagen"), vec![("kid", "c04e7b19a2f6d853")]).await;
    super::assert_redirect(&dagen, &page, Some("dagen"));
    // ICSx5's feed can't be the kid's.
    let feed = post(
        format!("{page}/dagen"),
        vec![("on_home", "on"), ("kid", "9f3a61c2d84be705")],
    )
    .await;
    assert_eq!(feed.status, StatusCode::BAD_REQUEST);
    // One form, exactly the rows sent: the feed hidden with a 15-minute reminder; the family calendar sent without
    // its reminder keeps it (and stays the kid's); a key that isn't one is ignored. The report itself coloured the
    // three in its order (sky, forest, sun; QA L7) and the pick cleared the family calendar's; the feed keeps its
    // colour hidden.
    let calendars = post(
        format!("{page}/calendars"),
        vec![
            ("key", "9f3a61c2d84be705"),
            ("remind_9f3a61c2d84be705", "15"),
            ("key", "c04e7b19a2f6d853"),
            ("shown_c04e7b19a2f6d853", "on"),
            ("key", "51d8e0aa3b7c9462"),
            ("shown_51d8e0aa3b7c9462", "on"),
            ("remind_51d8e0aa3b7c9462", "own"),
            ("key", "not-a-key"),
        ],
    )
    .await;
    super::assert_redirect(&calendars, &page, Some("calendars"));
    assert_eq!(
        app.policy(&token).await["calendar"],
        json!({
            "on_home": false, "kid_adds": true, "glance_next": true, "lock_reminders": "row",
            "calendars": [
                {"key": "51d8e0aa3b7c9462", "shown": true, "remind": "own", "remind_minutes": null, "writable_for_kid": false, "color": "sun"},
                {"key": "9f3a61c2d84be705", "shown": false, "remind": "before", "remind_minutes": 15, "writable_for_kid": false, "color": "sky"},
                {"key": "c04e7b19a2f6d853", "shown": true, "remind": "off", "remind_minutes": null, "writable_for_kid": true, "color": null}
            ],
            "rules": [],
            "feeds": []
        })
    );
    // Off for the kid (the family calendar, no longer the kid's, gets the next free colour and so stays a row); a
    // calendar gone from the phone shows so and is removed.
    post(
        format!("{page}/dagen"),
        vec![("on_home", "on"), ("kid", "off")],
    )
    .await;
    sqlx::query(
        "INSERT INTO device_calendars (device_id, calendar_key, shown) VALUES (?, 'aaaaaaaaaaaaaaaa', 0)",
    )
    .bind(id)
    .execute(&app.db)
    .await
    .unwrap();
    assert!(
        app.get_page(&page, &cookie)
            .await
            .text()
            .contains("Not on the phone now")
    );
    let remove = post(format!("{page}/calendars/aaaaaaaaaaaaaaaa/remove"), vec![]).await;
    super::assert_redirect(&remove, &page, Some("calendars"));
    let policy = app.policy(&token).await;
    assert_eq!(policy["calendar"]["kid_adds"], json!(false));
    assert_eq!(policy["calendar"]["on_home"], json!(true));
    let keys: Vec<&str> = policy["calendar"]["calendars"]
        .as_array()
        .unwrap()
        .iter()
        .map(|c| c["key"].as_str().unwrap())
        .collect();
    assert_eq!(
        keys,
        ["51d8e0aa3b7c9462", "9f3a61c2d84be705", "c04e7b19a2f6d853"]
    );
    // The hidden feed keeps sky reserved (every row's colour counts, hidden too - QA M1): the family calendar takes
    // the first free, forest.
    assert_eq!(policy["calendar"]["calendars"][2]["color"], json!("forest"));
}

/// The policy's calendars as (key, colour), in the policy's order (by key).
fn colors_of(policy: &Value) -> Vec<(String, Value)> {
    policy["calendar"]["calendars"]
        .as_array()
        .unwrap()
        .iter()
        .map(|c| (c["key"].as_str().unwrap().to_string(), c["color"].clone()))
        .collect()
}

/// A colour per calendar (2026-10-27): a chosen colour is saved and read back in the policy; the auto-pick gives every
/// other shown calendar the first colour no row has (never peach: it isn't one of the eight); an unknown name is a 400
/// that writes nothing; the kid's own pick has no Colour row and no colour.
#[tokio::test]
async fn a_colour_is_saved_picked_and_refused() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    assert!(!crate::calendar::COLORS.contains(&"peach"));
    // One calendar on a phone: no colour yet (a key would never show), "Pick for me" first in its select, no swatch.
    let (lonely, lonely_token) = app.enrolled_device("Ola").await;
    let mut one: Value = serde_json::from_str(CALENDAR_REPORT).unwrap();
    one["calendar"]["calendars"]
        .as_array_mut()
        .unwrap()
        .truncate(1);
    app.request(
        Method::POST,
        "/api/devices/status",
        Some(&lonely_token),
        Some(one),
    )
    .await;
    let fresh = app
        .get_page(&format!("/devices/{lonely}/calendar"), &cookie)
        .await
        .text();
    assert!(fresh.contains("name=\"color_9f3a61c2d84be705\""));
    assert!(fresh.contains("<option value=\"\" selected>Pick for me</option>"));
    assert!(!fresh.contains("cal-dot"));
    assert!(colors_of(&app.policy(&lonely_token).await).is_empty());
    // Three calendars: the report alone colours them in its order (QA L7).
    let (id, token) = reported(&app).await;
    let page = format!("/devices/{id}/calendar");
    assert_eq!(
        colors_of(&app.policy(&token).await),
        [
            ("51d8e0aa3b7c9462".to_string(), json!("sun")),
            ("9f3a61c2d84be705".to_string(), json!("sky")),
            ("c04e7b19a2f6d853".to_string(), json!("forest")),
        ]
    );
    let colors = |fields: Vec<(&'static str, &'static str)>| {
        let (app, cookie, path) = (&app, cookie.clone(), format!("{page}/calendars"));
        async move {
            app.request_form(Method::POST, &path, Some(&cookie), &fields)
                .await
        }
    };
    // The feed takes "sea" by hand; the two others keep the colours the report gave them.
    let saved = colors(vec![
        ("key", "c04e7b19a2f6d853"),
        ("shown_c04e7b19a2f6d853", "on"),
        ("key", "9f3a61c2d84be705"),
        ("shown_9f3a61c2d84be705", "on"),
        ("color_9f3a61c2d84be705", "sea"),
        ("key", "51d8e0aa3b7c9462"),
        ("shown_51d8e0aa3b7c9462", "on"),
    ])
    .await;
    super::assert_redirect(&saved, &page, Some("calendars"));
    let by_key = colors_of;
    let picked = by_key(&app.policy(&token).await);
    assert_eq!(
        picked,
        [
            ("51d8e0aa3b7c9462".to_string(), json!("sun")),
            ("9f3a61c2d84be705".to_string(), json!("sea")),
            ("c04e7b19a2f6d853".to_string(), json!("forest")),
        ]
    );
    // An unknown name: refused, nothing changed.
    let bad = colors(vec![
        ("key", "c04e7b19a2f6d853"),
        ("shown_c04e7b19a2f6d853", "on"),
        ("color_c04e7b19a2f6d853", "pink"),
    ])
    .await;
    assert_eq!(bad.status, StatusCode::BAD_REQUEST);
    assert_eq!(by_key(&app.policy(&token).await), picked);
    // The page: the swatch and the saved choice, no "Pick for me" any more.
    let shown = app.get_page(&page, &cookie).await.text();
    assert!(shown.contains("cal-dot cal-sea"));
    assert!(shown.contains("<option value=\"sea\" selected>Sea</option>"));
    assert!(!shown.contains("Pick for me"));
    // The family calendar as the kid's own: its colour goes, its Colour row too.
    let kid = app
        .request_form(
            Method::POST,
            &format!("{page}/dagen"),
            Some(&cookie),
            &[("on_home", "on"), ("kid", "c04e7b19a2f6d853")],
        )
        .await;
    super::assert_redirect(&kid, &page, Some("dagen"));
    assert_eq!(
        app.policy(&token).await["calendar"]["calendars"][2]["color"],
        Value::Null
    );
    let kids = app.get_page(&page, &cookie).await.text();
    assert!(!kids.contains("name=\"color_c04e7b19a2f6d853\""));
    assert!(kids.contains("name=\"color_51d8e0aa3b7c9462\""));
}

/// The auto-pick's other triggers: the report alone colours a phone's calendars in its order once it shows two or
/// more (QA L7); a save on a links form (a parent who sets the phone up from the links alone never touches the
/// calendars form) colours the link's own calendar before the phone has it; a report that brings a new calendar
/// gives it the next free colour.
#[tokio::test]
async fn a_link_save_and_a_new_report_extend_the_colours() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = reported(&app).await;
    let colors = colors_of;
    assert_eq!(
        colors(&app.policy(&token).await),
        [
            ("51d8e0aa3b7c9462".to_string(), json!("sun")),
            ("9f3a61c2d84be705".to_string(), json!("sky")),
            ("c04e7b19a2f6d853".to_string(), json!("forest")),
        ]
    );
    let link = "https://skole.example.no/kalender/5b.ics";
    app.fetch.answer(
        link,
        200,
        std::fs::read("testdata/calendar_feeds/contract.ics").unwrap(),
    );
    let added = app
        .request_form(
            Method::POST,
            &format!("/devices/{id}/calendar/links"),
            Some(&cookie),
            &[("url", link), ("name", "Skolen")],
        )
        .await;
    assert_eq!(added.status, StatusCode::SEE_OTHER, "{}", added.text());
    // The link's own calendar (its key is the server's before the phone has it) takes the fourth colour.
    let link_key = crate::calendar_feeds::calendar_key(&crate::calendar_feeds::uid_of(
        &crate::calendar_feeds::normalize(link).unwrap(),
    ));
    let mut expected = vec![
        ("51d8e0aa3b7c9462".to_string(), json!("sun")),
        ("9f3a61c2d84be705".to_string(), json!("sky")),
        ("c04e7b19a2f6d853".to_string(), json!("forest")),
        (link_key.clone(), json!("berry")),
    ];
    expected.sort_by(|a, b| a.0.cmp(&b.0));
    assert_eq!(colors(&app.policy(&token).await), expected);
    // The phone reports a fifth calendar: the scheme extends to it, the others keep theirs.
    let mut report: Value = serde_json::from_str(CALENDAR_REPORT).unwrap();
    report["calendar"]["calendars"]
        .as_array_mut()
        .unwrap()
        .push(json!({
            "key": "aaaaaaaaaaaaaaaa", "account_type": "at.bitfire.icsdroid", "name": "Fotball",
            "source": "fotball.example.no", "access_level": 200
        }));
    app.request(
        Method::POST,
        "/api/devices/status",
        Some(&token),
        Some(report.clone()),
    )
    .await;
    let mut extended = expected.clone();
    extended.push(("aaaaaaaaaaaaaaaa".to_string(), json!("sea")));
    extended.sort_by(|a, b| a.0.cmp(&b.0));
    assert_eq!(colors(&app.policy(&token).await), extended);
    // Another phone set up on the phone alone: its first report with four calendars colours them in its order.
    let (_, other) = app.enrolled_device("Ola").await;
    app.request(
        Method::POST,
        "/api/devices/status",
        Some(&other),
        Some(report),
    )
    .await;
    assert_eq!(
        colors(&app.policy(&other).await),
        [
            ("51d8e0aa3b7c9462".to_string(), json!("sun")),
            ("9f3a61c2d84be705".to_string(), json!("sky")),
            ("aaaaaaaaaaaaaaaa".to_string(), json!("berry")),
            ("c04e7b19a2f6d853".to_string(), json!("forest")),
        ]
    );
}

/// QA M1, L2, L3, L6: a hidden calendar keeps its colour reserved (shown again, no two dots alike); a gone calendar's
/// colour-only row goes with the next readable report (an unreadable one prunes nothing); the phone's own "Min
/// kalender" takes no colour and has no Colour row; the ninth calendar's select says "No free colour".
#[tokio::test]
async fn hidden_keeps_gone_frees_the_kids_own_takes_none_and_the_ninth_has_none() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = reported(&app).await;
    let page = format!("/devices/{id}/calendar");
    let report_with = |extra: Vec<Value>| {
        let mut report: Value = serde_json::from_str(CALENDAR_REPORT).unwrap();
        report["calendar"]["calendars"]
            .as_array_mut()
            .unwrap()
            .extend(extra);
        report
    };
    let row = |key: &str, name: &str, kind: &str| json!({"key": key, "account_type": kind, "name": name, "source": null, "access_level": 200});
    // Hide the family calendar (forest); the phone's next calendar gets sun's successor, not forest.
    let hide = app
        .request_form(
            Method::POST,
            &format!("{page}/calendars"),
            Some(&cookie),
            &[
                ("key", "9f3a61c2d84be705"),
                ("shown_9f3a61c2d84be705", "on"),
                ("key", "c04e7b19a2f6d853"),
                ("key", "51d8e0aa3b7c9462"),
                ("shown_51d8e0aa3b7c9462", "on"),
            ],
        )
        .await;
    super::assert_redirect(&hide, &page, Some("calendars"));
    app.request(
        Method::POST,
        "/api/devices/status",
        Some(&token),
        Some(report_with(vec![row(
            "aaaaaaaaaaaaaaaa",
            "Fotball",
            "at.bitfire.icsdroid",
        )])),
    )
    .await;
    let after = colors_of(&app.policy(&token).await);
    assert_eq!(after[2], ("aaaaaaaaaaaaaaaa".to_string(), json!("berry")));
    // Shown again: still forest, four distinct colours.
    let show = app
        .request_form(
            Method::POST,
            &format!("{page}/calendars"),
            Some(&cookie),
            &[
                ("key", "c04e7b19a2f6d853"),
                ("shown_c04e7b19a2f6d853", "on"),
            ],
        )
        .await;
    super::assert_redirect(&show, &page, Some("calendars"));
    let shown = colors_of(&app.policy(&token).await);
    assert_eq!(shown[3], ("c04e7b19a2f6d853".to_string(), json!("forest")));
    let distinct: std::collections::HashSet<&Value> = shown.iter().map(|(_, c)| c).collect();
    assert_eq!(distinct.len(), 4, "{shown:?}");
    // The football calendar leaves the phone: an unreadable report prunes nothing, the next readable one frees it.
    app.request(
        Method::POST,
        "/api/devices/status",
        Some(&token),
        Some(json!({"lock_reason": "NONE", "kiosk_engaged": true,
                    "calendar": {"readable": false, "calendars": [], "rules_skipped": []}})),
    )
    .await;
    assert_eq!(colors_of(&app.policy(&token).await).len(), 4);
    app.request(
        Method::POST,
        "/api/devices/status",
        Some(&token),
        Some(report_with(vec![])),
    )
    .await;
    let freed = colors_of(&app.policy(&token).await);
    assert_eq!(freed.len(), 3, "{freed:?}");
    assert!(freed.iter().all(|(key, _)| key != "aaaaaaaaaaaaaaaa"));
    // The phone's own "Min kalender" (the constant key): no colour, no Colour row; the next calendar takes berry.
    let kid_local = crate::calendar::KID_LOCAL_KEY;
    app.request(
        Method::POST,
        "/api/devices/status",
        Some(&token),
        Some(report_with(vec![
            row(kid_local, "Min kalender", "LOCAL"),
            row("bbbbbbbbbbbbbbbb", "Kor", "at.bitfire.icsdroid"),
        ])),
    )
    .await;
    let with_kid = colors_of(&app.policy(&token).await);
    assert!(
        with_kid.iter().all(|(key, _)| key != kid_local),
        "{with_kid:?}"
    );
    assert!(with_kid.contains(&("bbbbbbbbbbbbbbbb".to_string(), json!("berry"))));
    let html = app.get_page(&page, &cookie).await.text();
    assert!(html.contains("name=\"color_bbbbbbbbbbbbbbbb\""));
    assert!(!html.contains(&format!("name=\"color_{kid_local}\"")));
    // Nine calendars: the ninth gets none, and its select says so.
    let mut many = vec![row("bbbbbbbbbbbbbbbb", "Kor", "at.bitfire.icsdroid")];
    many.extend((1..=5).map(|i| {
        row(
            &format!("{i}{i}{i}{i}{i}{i}{i}{i}{i}{i}{i}{i}{i}{i}{i}{i}"),
            "Kalender",
            "at.bitfire.icsdroid",
        )
    }));
    app.request(
        Method::POST,
        "/api/devices/status",
        Some(&token),
        Some(report_with(many)),
    )
    .await;
    // Eight coloured rows; the ninth gets none - and so no row of its own (nothing to send the phone).
    let nine = colors_of(&app.policy(&token).await);
    assert_eq!(nine.len(), 8, "{nine:?}");
    assert!(nine.iter().all(|(_, c)| !c.is_null()), "{nine:?}");
    assert!(nine.iter().all(|(key, _)| key != "5555555555555555"));
    let html = app.get_page(&page, &cookie).await.text();
    assert_eq!(
        html.matches("<option value=\"\" selected disabled>No free colour</option>")
            .count(),
        1,
        "{html}"
    );
    assert!(!html.contains("Pick for me"));
}

#[tokio::test]
async fn a_rule_is_saved_refused_previewed_moved_and_deleted() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = reported(&app).await;
    let page = format!("/devices/{id}/calendar");
    let post = |path: String, fields: Vec<(&'static str, &'static str)>| {
        let (app, cookie) = (&app, cookie.clone());
        async move {
            app.request_form(Method::POST, &path, Some(&cookie), &fields)
                .await
        }
    };
    let school = vec![
        ("field", "title"),
        ("match", "starts"),
        ("pattern", "Gruppe 2- skoleåret 2026/2027:"),
        ("then", "show"),
        ("text", "Skole:"),
        ("keep_rest", "on"),
        ("calendar", "9f3a61c2d84be705"),
    ];
    let created = post(format!("{page}/rules"), school.clone()).await;
    super::assert_redirect(&created, &page, Some("rule-1"));
    app.assert_lands(&created, &cookie).await;
    let hide = vec![
        ("field", "description"),
        ("match", "contains"),
        ("pattern", "6. og 7. trinn"),
        ("then", "hide"),
    ];
    post(format!("{page}/rules"), hide.clone()).await;
    let policy = app.policy(&token).await;
    assert_eq!(rule_ids(&policy), [1, 2]);
    assert_eq!(policy["calendar"]["rules"][0]["action"], json!("prefix"));

    // A refused rule: the editor again (400) as typed, the reason in the bar; nothing written, no settings_saved.
    let before = settings_saved(&app).await;
    let refused = post(
        format!("{page}/rules/1"),
        vec![
            ("field", "description"),
            ("match", "contains"),
            ("pattern", "Typed text"),
            ("then", "show"),
            ("text", "x"),
        ],
    )
    .await;
    assert_eq!(refused.status, StatusCode::BAD_REQUEST);
    let html = refused.text();
    assert!(
        html.contains(r#"value="Typed text""#)
            && html.contains("A rule on the description can only hide"),
        "{html}"
    );
    assert_eq!(
        app.policy(&token).await["calendar"]["rules"][0]["field"],
        json!("title")
    );

    // The preview: 200 with the fragment only (never the form), nothing logged; a rule before it is named.
    let preview = post(
        format!("{page}/rules/preview"),
        vec![
            ("rule", "2"),
            ("field", "title"),
            ("match", "contains"),
            ("pattern", "R omer riket"),
            ("then", "hide"),
            ("calendar", "9f3a61c2d84be705"),
            (
                "preview_title",
                "Gruppe 2- skoleåret 2026/2027: Det kuleste fra Romerriket",
            ),
            ("fragment", "1"),
        ],
    )
    .await;
    assert_eq!(preview.status, StatusCode::OK);
    let fragment = preview.text();
    assert!(
        !fragment.contains("<form") && !fragment.contains("<html"),
        "{fragment}"
    );
    assert!(
        fragment.contains("Skole: Det kuleste fra Romerriket"),
        "{fragment}"
    );
    assert!(
        fragment.contains("Rule 1 matches this title first"),
        "{fragment}"
    );
    let alone = post(
        format!("{page}/rules/preview"),
        vec![
            ("field", "title"),
            ("match", "contains"),
            ("pattern", "romerriket"),
            ("then", "hide"),
            ("preview_title", "Det kuleste fra R omerriket"),
            ("fragment", "1"),
        ],
    )
    .await
    .text();
    assert!(alone.contains(r#"data-preview-after="hidden""#), "{alone}");
    // Without the script: the whole editor with the preview filled.
    let full_page = post(
        format!("{page}/rules/preview"),
        vec![
            ("field", "title"),
            ("match", "starts"),
            ("pattern", "Tur"),
            ("then", "show"),
            ("text", "Utflukt"),
            ("preview_title", "Tur til skogen"),
        ],
    )
    .await;
    assert_eq!(full_page.status, StatusCode::OK);
    let text = full_page.text();
    assert!(
        text.contains(r#"id="rule-form""#)
            && text.contains("<strong data-preview-after>Utflukt</strong>")
    );
    assert_eq!(settings_saved(&app).await, before);

    // Rule 2 up: the order follows, back to its row.
    let moved = post(format!("{page}/rules/2/move"), vec![("dir", "up")]).await;
    super::assert_redirect(&moved, &page, Some("rule-2"));
    assert_eq!(rule_ids(&app.policy(&token).await), [2, 1]);

    // Deleted, then gone: its page and its save are a 303 to #rules with the notice, marked refused.
    let deleted = post(format!("{page}/rules/1/delete"), vec![]).await;
    super::assert_redirect(&deleted, &page, Some("rules"));
    let before = settings_saved(&app).await;
    let gone = post(format!("{page}/rules/1"), school.clone()).await;
    super::assert_redirect(&gone, &format!("{page}?notice=rule_gone"), Some("rules"));
    assert_eq!(settings_saved(&app).await, before);
    let notice = app
        .get_page(&format!("{page}?notice=rule_gone"), &cookie)
        .await;
    assert!(notice.text().contains(r#"data-flash="rule-gone""#));
    assert!(
        app.get_page(&format!("{page}/rules/1"), &cookie)
            .await
            .status
            .is_redirection()
    );

    // At most 30 rules.
    for n in 0..29 {
        add_rule(
            &app,
            id,
            100 + n,
            9,
            "NULL, 'title', 'contains', 'x', 'hide', ''",
        )
        .await;
    }
    let full = post(format!("{page}/rules"), hide).await;
    assert_eq!(full.status, StatusCode::BAD_REQUEST);
    assert!(full.text().contains("30 rules already"));
}

/// QA M1: the kid's calendar the parent picked stays picked while the phone's report doesn't list it - the page shows
/// it selected ("not on the phone now"), and the Dagen group's next autosave (On Home off) keeps it.
#[tokio::test]
async fn the_kids_calendar_survives_a_report_without_it() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = reported(&app).await;
    let page = format!("/devices/{id}/calendar");
    let pick = app
        .request_form(
            Method::POST,
            &format!("{page}/dagen"),
            Some(&cookie),
            &[("on_home", "on"), ("kid", "c04e7b19a2f6d853")],
        )
        .await;
    super::assert_redirect(&pick, &page, Some("dagen"));
    // A report that can't read the provider: no calendars at all.
    app.request(
        Method::POST,
        "/api/devices/status",
        Some(&token),
        Some(json!({"lock_reason": "NONE", "kiosk_engaged": true,
                    "calendar": {"readable": false, "calendars": [], "rules_skipped": []}})),
    )
    .await;
    let html = app.get_page(&page, &cookie).await.text();
    assert!(
        html.contains(r#"<option value="c04e7b19a2f6d853" selected>The chosen calendar - not on the phone now</option>"#),
        "{html}"
    );
    let kid = &html[html.find(r#"name="kid""#).unwrap()..];
    let kid = &kid[..kid.find("</select>").unwrap()];
    assert_eq!(kid.matches(" selected>").count(), 1, "{kid}");
    // The browser sends what the select shows: the pick, with On Home now off.
    let off = app
        .request_form(
            Method::POST,
            &format!("{page}/dagen"),
            Some(&cookie),
            &[("kid", "c04e7b19a2f6d853")],
        )
        .await;
    super::assert_redirect(&off, &page, Some("dagen"));
    let policy = app.policy(&token).await;
    assert_eq!(policy["calendar"]["on_home"], json!(false));
    assert_eq!(policy["calendar"]["kid_adds"], json!(true));
    // (The pick's save coloured the other reported calendars, so the rows are three; the kid's by its key.)
    let kids_row = policy["calendar"]["calendars"]
        .as_array()
        .unwrap()
        .iter()
        .find(|c| c["key"] == json!("c04e7b19a2f6d853"))
        .expect("the kid's calendar is a row");
    assert_eq!(kids_row["writable_for_kid"], json!(true));
    // A key that is neither reported writable nor the saved pick is still refused.
    let other = app
        .request_form(
            Method::POST,
            &format!("{page}/dagen"),
            Some(&cookie),
            &[("kid", "aaaaaaaaaaaaaaaa")],
        )
        .await;
    assert_eq!(other.status, StatusCode::BAD_REQUEST);
}

/// `page` in Norwegian for this request only (`?lang=nb`, design 70): an `Accept-Language` would become the account's
/// language at its first request and turn the English checks Norwegian.
async fn page_nb(app: &TestApp, page: &str, cookie: &str) -> String {
    app.get_page(&format!("{page}?lang=nb"), cookie)
        .await
        .text()
}

/// The 2026-10-27 polish: each group's help behind its "i", the kid's calendar on a line of its own, and the lock
/// screen's reminders named for what they are ("Reminders on the lock screen": Off / Quiet row - a select, as a third
/// form is to come; the policy's words unchanged), back to `#lock`, round trip both ways.
#[tokio::test]
async fn the_help_is_behind_the_i_and_the_reminders_say_what_they_are() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = reported(&app).await;
    let page = format!("/devices/{id}/calendar");
    let html = page_nb(&app, &page, &cookie).await;
    // One "i" per group, its text in the panel; the old footers' long sentences and the select's words gone.
    for (label, text) in [
        ("Om Dagen", "Barnet sveiper fra dag til dag."),
        ("Om låseskjermen", "En tittel kan nevne et sted."),
        ("Om påminnelsene", "uansett hva du velger her."),
    ] {
        let at = html
            .find(&format!(r#"<summary aria-label="{label}">"#))
            .unwrap_or_else(|| panic!("{label}: {html}"));
        let panel = &html[at..];
        let panel = &panel[panel.find(r#"<div class="info-panel">"#).unwrap()..];
        let panel = &panel[..panel.find("</details>").unwrap()];
        assert!(panel.contains(text), "{label}: {panel}");
    }
    // Dagen, the lock, Calendars shown and (design 72) the calendars the server fetches - links and accounts, one.
    assert_eq!(html.matches(r#"<details class="info">"#).count(), 4);
    assert!(html.contains(r#"<summary aria-label="Om kalendere som hentes av serveren">"#));
    assert!(html.contains(r#"role="img" aria-label="Skole: Matte om 15 min, 08:30""#));
    for gone in [
        "Den rolige raden",
        "Barnets avtaler i<",
        "Dagen er barnets dag i Vibb One",
        "Det låseskjermen viser",
        "Påminnelsen velges per kalender",
    ] {
        assert!(!html.contains(gone), "{gone}");
    }
    // The kid's calendar: its label alone on the row's first line, the select under it.
    let kid = &html[html.find(r#"<label class="row row-stacked">"#).unwrap()..];
    assert!(
        kid.starts_with(
            r#"<label class="row row-stacked">
                <span class="row-label">Barnets avtaler</span>
                <select class="pick" name="kid" data-autosave>"#
        ),
        "{kid}"
    );
    // The master: Off by default, each calendar's reminder saying so.
    let master = r#"<span class="row-label">Påminnelser på låseskjermen<span class="row-sub">En stille rad før hendelsen - uten lyd, eller med én varsellyd når den kommer</span></span>
                <select class="pick" name="reminders" data-autosave>
                    <option value="off" selected>Av</option>
                    <option value="row">Stille rad</option>
                    <option value="ping">Stille rad + lyd</option>
                </select>"#;
    assert!(html.contains(master), "{html}");
    assert_eq!(html.matches("data-reminders-off").count(), 3);
    // The quiet row.
    let on = app
        .request_form(
            Method::POST,
            &format!("{page}/lock"),
            Some(&cookie),
            &[("reminders", "row")],
        )
        .await;
    super::assert_redirect(&on, &page, Some("lock"));
    assert_eq!(
        app.policy(&token).await["calendar"]["lock_reminders"],
        json!("row")
    );
    let html = page_nb(&app, &page, &cookie).await;
    assert!(html.contains(r#"<option value="row" selected>Stille rad</option>"#));
    assert!(!html.contains("data-reminders-off"));
    // Back to Off, the "Next" line on.
    let off = app
        .request_form(
            Method::POST,
            &format!("{page}/lock"),
            Some(&cookie),
            &[("glance_next", "on"), ("reminders", "off")],
        )
        .await;
    super::assert_redirect(&off, &page, Some("lock"));
    let policy = app.policy(&token).await;
    assert_eq!(policy["calendar"]["lock_reminders"], json!("off"));
    assert_eq!(policy["calendar"]["glance_next"], json!(true));
    assert!(
        page_nb(&app, &page, &cookie)
            .await
            .contains(r#"<option value="off" selected>Av</option>"#)
    );
    // English has its words too.
    let en = app.get_page(&page, &cookie).await.text();
    assert!(en.contains(">Reminders on the lock screen<"), "{en}");
    assert!(en.contains(r#"<option value="row">Quiet row</option>"#));
    assert!(en.contains(">The kid&#39;s events<"), "{en}");
    assert!(!en.contains("The calm row"));
}

/// The lock group's select, saved as the PWA's autosave posts it, back to `#lock`.
async fn save_reminders(app: &TestApp, page: &str, cookie: &str, value: &str) {
    let answer = app
        .request_form(
            Method::POST,
            &format!("{page}/lock"),
            Some(cookie),
            &[("reminders", value)],
        )
        .await;
    super::assert_redirect(&answer, page, Some("lock"));
}

/// The quiet row plus a ping (PLAN "Lock-screen reminders: a third option", migration 0113): the select's third value
/// saves (stored as the row with `calendar_lock_ping`), reads back selected, and the policy says `ping` only to a
/// launcher claiming `calendar_ping_v1` - an older one reads anything but `row` as off, so it is sent `row` and the
/// page says it shows the quiet row. Back to the row clears the ping.
#[tokio::test]
async fn the_ping_saves_and_reaches_only_a_launcher_that_knows_it() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = reported(&app).await;
    let page = format!("/devices/{id}/calendar");
    save_reminders(&app, &page, &cookie, "ping").await;
    let stored: (String, i64) = sqlx::query_as(
        "SELECT calendar_lock_reminders, calendar_lock_ping FROM device_policy WHERE device_id = ?",
    )
    .bind(id)
    .fetch_one(&app.db)
    .await
    .unwrap();
    assert_eq!(stored, ("row".to_string(), 1));
    // The report's launcher claims `calendar_v1` only: the row, and the page says so.
    assert_eq!(
        app.policy(&token).await["calendar"]["lock_reminders"],
        json!("row")
    );
    let html = page_nb(&app, &page, &cookie).await;
    assert!(
        html.contains(r#"<option value="ping" selected>Stille rad + lyd</option>"#),
        "{html}"
    );
    assert!(html.contains(
        r#"<span class="row-sub" data-ping-older>Vibb One på telefonen kan ikke lyden ennå - den viser den stille raden</span>"#
    ));
    assert!(!html.contains("data-reminders-off"));
    assert!(
        html.contains("«Stille rad + lyd» spiller telefonens varsellyd én gang når raden kommer.")
    );
    assert!(html.contains("i en samtale, eller mens telefonen er i bruk. Aldri vibrasjon."));
    // A launcher that knows the ping gets it; the note goes.
    let mut report: Value = serde_json::from_str(CALENDAR_REPORT).unwrap();
    report["capabilities"] = json!(["calendar_v1", crate::calendar::PING_CAPABILITY]);
    app.request(
        Method::POST,
        "/api/devices/status",
        Some(&token),
        Some(report),
    )
    .await;
    assert_eq!(
        app.policy(&token).await["calendar"]["lock_reminders"],
        json!("ping")
    );
    assert!(
        !page_nb(&app, &page, &cookie)
            .await
            .contains("data-ping-older")
    );
    let en = app.get_page(&page, &cookie).await.text();
    assert!(en.contains(r#"<option value="ping" selected>Quiet row + sound</option>"#));
    // Back to the quiet row: the ping goes with it; Off is off.
    save_reminders(&app, &page, &cookie, "row").await;
    assert_eq!(
        app.policy(&token).await["calendar"]["lock_reminders"],
        json!("row")
    );
    save_reminders(&app, &page, &cookie, "off").await;
    assert_eq!(
        app.policy(&token).await["calendar"]["lock_reminders"],
        json!("off")
    );
    // Anything else is refused and changes nothing.
    let odd = app
        .request_form(
            Method::POST,
            &format!("{page}/lock"),
            Some(&cookie),
            &[("reminders", "loud")],
        )
        .await;
    assert_eq!(odd.status, StatusCode::BAD_REQUEST);
    assert_eq!(
        app.policy(&token).await["calendar"]["lock_reminders"],
        json!("off")
    );
}

/// QA L5 (g): every Calendar route needs a signed-in parent - without the session each answers the sign-in redirect
/// and writes nothing.
#[tokio::test]
async fn every_calendar_route_needs_a_session() {
    let app = TestApp::new().await;
    let (id, _) = reported(&app).await;
    add_rule(&app, id, 1, 0, "NULL, 'title', 'contains', 'x', 'hide', ''").await;
    // Design 72: a calendar link, so its routes have one to act on.
    let link = "https://skole.example.no/k.ics";
    app.fetch
        .answer(link, 200, "BEGIN:VCALENDAR\r\nEND:VCALENDAR\r\n");
    let uid = crate::calendar_feeds::add(&app.state, id, link, "Skolen", chrono::Utc::now())
        .await
        .unwrap();
    let calls = app.fetch.count();
    let page = format!("/devices/{id}/calendar");
    let links = format!("{page}/links/{uid}");
    let posts: Vec<(String, Vec<(&str, &str)>)> = vec![
        (format!("{page}/dagen"), vec![("kid", "off")]),
        (
            format!("{page}/lock"),
            vec![("glance_next", "on"), ("reminders", "row")],
        ),
        (
            format!("{page}/calendars"),
            vec![
                ("key", "9f3a61c2d84be705"),
                ("remind_9f3a61c2d84be705", "15"),
            ],
        ),
        (format!("{page}/calendars/9f3a61c2d84be705/remove"), vec![]),
        (
            format!("{page}/rules"),
            vec![
                ("field", "title"),
                ("match", "starts"),
                ("pattern", "x"),
                ("then", "hide"),
            ],
        ),
        (
            format!("{page}/rules/preview"),
            vec![("preview_title", "x"), ("fragment", "1")],
        ),
        (
            format!("{page}/rules/1"),
            vec![
                ("field", "title"),
                ("match", "starts"),
                ("pattern", "y"),
                ("then", "hide"),
            ],
        ),
        (format!("{page}/rules/1/move"), vec![("dir", "down")]),
        (format!("{page}/rules/1/delete"), vec![]),
        (
            format!("{page}/links"),
            vec![("url", "https://other.example.no/k.ics"), ("name", "x")],
        ),
        (format!("{links}/name"), vec![("name", "Renamed")]),
        (format!("{links}/fetch"), vec![]),
        (format!("{links}/remove"), vec![]),
        // Design 72 step 2: CalDAV.
        (
            format!("{page}/caldav"),
            vec![
                ("server", "dav.example.net"),
                ("username", "u"),
                ("password", "test-only"),
            ],
        ),
        (format!("{page}/caldav/1/test"), vec![("password", "")]),
        (format!("{page}/caldav/1/calendars"), vec![("cal", "x")]),
        (format!("{page}/caldav/1/remove"), vec![]),
    ];
    for (path, fields) in posts {
        let res = app.request_form(Method::POST, &path, None, &fields).await;
        assert!(res.status.is_redirection(), "{path}: {}", res.status);
        assert!(
            res.location().is_some_and(|l| l.starts_with("/login")),
            "{path}: {:?}",
            res.location()
        );
    }
    for path in [
        page.clone(),
        format!("{page}/rules/new"),
        format!("{page}/rules/1"),
        // The calendars the server fetches (the look of 2026-11-01): Add Calendar, a link's and an account's page.
        format!("{page}/add"),
        format!("{page}/add?kind=caldav"),
        links.clone(),
        format!("{page}/caldav/1"),
    ] {
        let res = app
            .send(
                axum::http::Request::builder()
                    .uri(&path)
                    .body(axum::body::Body::empty())
                    .unwrap(),
            )
            .await;
        assert!(res.status.is_redirection(), "{path}: {}", res.status);
        assert!(
            res.location().is_some_and(|l| l.starts_with("/login")),
            "{path}: {:?}",
            res.location()
        );
    }
    let (glance, kid_adds): (bool, bool) = sqlx::query_as(
        "SELECT calendar_glance_next, calendar_kid_adds FROM device_policy WHERE device_id = ?",
    )
    .bind(id)
    .fetch_one(&app.db)
    .await
    .unwrap();
    assert!(!glance && kid_adds);
    let rules: Vec<(i64, String)> =
        sqlx::query_as("SELECT id, pattern FROM device_calendar_rules WHERE device_id = ?")
            .bind(id)
            .fetch_all(&app.db)
            .await
            .unwrap();
    assert_eq!(rules, [(1, "x".to_string())]);
    // The report's own colour rows are there (the auto-pick, QA L7); nothing else was set.
    let saved: i64 = sqlx::query_scalar(
        "SELECT COUNT(*) FROM device_calendars \
         WHERE NOT (shown = 1 AND remind = 'off' AND writable_for_kid = 0 AND color IS NOT NULL)",
    )
    .fetch_one(&app.db)
    .await
    .unwrap();
    assert_eq!(saved, 0);
    let names: Vec<String> = sqlx::query_scalar("SELECT name FROM device_calendar_feeds")
        .fetch_all(&app.db)
        .await
        .unwrap();
    assert_eq!(names, ["Skolen"]);
    assert_eq!(app.fetch.count(), calls, "no fetch without a session");
}
