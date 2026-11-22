//! Design 93: a rule turned on now ("Slå på nå") and the Regler sheet's routes. The pure parts and
//! the contract are `lock_now::tests`.

use axum::http::{Method, StatusCode};
use serde_json::{Value, json};

use super::{TestApp, assert_redirect};

/// A zone where the local day has at least four hours left now, so "Ut dagen" and every length
/// are offered whenever the suite runs.
fn roomy_zone() -> &'static str {
    use chrono::Timelike;
    let utc = chrono::Utc::now();
    if utc.hour() < 20 { "UTC" } else { "Etc/GMT-12" }
}

/// Emma's phone with everyone's Skole (never scheduled), its report naming `zone`; returns the
/// app, the admin cookie, the phone, its token and Skole's id.
async fn emma(extra: Value) -> (TestApp, String, i64, String, i64) {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("Emma").await;
    let skole: i64 = sqlx::query_scalar(
        "INSERT INTO time_rules (name, kind, calls_allowed, exempt_apps_json, days_json) \
         VALUES ('Skole', 'school', 0, '[\"org.fossify.clock\"]', \
         '[null,null,null,null,null,null,null]') RETURNING id",
    )
    .fetch_one(&app.db)
    .await
    .unwrap();
    let mut body = json!({
        "time_state": {"day": "2026-11-13", "used_minutes": 0, "lock_reason": "NONE",
                       "lifts_active": [], "zone": roomy_zone()}
    });
    for (key, value) in extra.as_object().unwrap() {
        body[key] = value.clone();
    }
    app.post_status(&token, body).await;
    (app, cookie, id, token, skole)
}

async fn post(
    app: &TestApp,
    cookie: &str,
    path: &str,
    form: &[(&str, &str)],
) -> super::TestResponse {
    app.request_form(Method::POST, path, Some(cookie), form)
        .await
}

async fn count(app: &TestApp, sql: &str) -> i64 {
    sqlx::query_scalar(sql).fetch_one(&app.db).await.unwrap()
}

/// The rules the phone is served, with a copy's (negative id) first.
async fn copies(app: &TestApp, token: &str) -> Vec<Value> {
    app.policy(token).await["time_policy"]["rules"]
        .as_array()
        .unwrap()
        .iter()
        .filter(|r| r["id"].as_i64().unwrap() < 0)
        .cloned()
        .collect()
}

/// §5.1: the copy has Skole's mode and ends where asked; Skole's pause and an "all" pause end in
/// the same tap, and so does the opening for setup the report shows; a second start is refused
/// with nothing written; "Alle regler" and an unread zone are refused.
#[tokio::test]
async fn turning_a_rule_on_serves_a_copy_and_ends_what_would_lift_it() {
    let (app, cookie, id, token, skole) = emma(json!({"restrictions_paused": true})).await;
    // Design 123 §3.3: Skole has no days and isn't on: nothing to pause, nothing written.
    let res = post(
        &app,
        &cookie,
        &format!("/devices/{id}/rules-now"),
        &[
            ("mode", "pause"),
            ("rule", &skole.to_string()),
            ("pause_minutes", "30"),
        ],
    )
    .await;
    assert_redirect(
        &res,
        &format!("/devices/{id}?done=pause_not_in_force&open=rules&mode=pause&rule={skole}"),
        Some("quick"),
    );
    assert_eq!(count(&app, "SELECT COUNT(*) FROM time_lifts").await, 0);
    let page = app
        .get_page(
            &format!("/devices/{id}?done=pause_not_in_force&open=rules&mode=pause&rule={skole}"),
            &cookie,
        )
        .await;
    assert!(
        page.text().contains("Skole isn&#x27;t in use on Emma")
            || page.text().contains("Skole isn't in use on Emma"),
        "{}",
        page.text()
    );
    // With a day (Sunday 00:00-00:01) it can be paused.
    sqlx::query(
        "UPDATE time_rules SET days_json = \
         '[null,null,null,null,null,null,{\"start\":0,\"end\":1}]' WHERE id = ?",
    )
    .bind(skole)
    .execute(&app.db)
    .await
    .unwrap();
    for rule in [skole.to_string(), "all".to_string()] {
        let res = post(
            &app,
            &cookie,
            &format!("/devices/{id}/lifts"),
            &[("target", "rule"), ("rule", &rule), ("minutes", "30")],
        )
        .await;
        assert!(res.status.is_redirection());
    }
    let res = post(
        &app,
        &cookie,
        &format!("/devices/{id}/rules-now"),
        &[("mode", "on"), ("rule", "all"), ("on_len", "day")],
    )
    .await;
    assert_redirect(
        &res,
        &format!("/devices/{id}?done=on_needs_rule&open=rules&mode=on&rule=all"),
        Some("quick"),
    );

    let res = post(
        &app,
        &cookie,
        &format!("/devices/{id}/rules-now"),
        &[
            ("mode", "on"),
            ("rule", &skole.to_string()),
            ("on_len", "day"),
            ("til", ""),
        ],
    )
    .await;
    assert_redirect(&res, &format!("/devices/{id}?done=on"), Some("quick"));
    let copy = copies(&app, &token).await;
    assert_eq!(copy.len(), 1, "{copy:?}");
    assert_eq!(copy[0]["name"], "Skole");
    assert_eq!(copy[0]["kind"], "school");
    assert_eq!(copy[0]["calls_allowed"], false);
    assert_eq!(copy[0]["exempt_apps"], json!(["org.fossify.clock"]));
    assert_eq!(copy[0]["card_first"], true);
    let window = copy[0]["days"]
        .as_array()
        .unwrap()
        .iter()
        .find(|d| !d.is_null())
        .unwrap();
    assert_eq!(window["end"], 0, "Ut dagen is served until midnight");
    let ends_at: String = sqlx::query_scalar("SELECT ends_at FROM time_lock_now")
        .fetch_one(&app.db)
        .await
        .unwrap();
    let ends_ms = crate::display_time::parse_db_utc(&ends_at)
        .unwrap()
        .timestamp_millis();
    assert_eq!(copy[0]["until_ms"], ends_ms);
    assert_eq!(
        app.policy(&token).await["time_policy"]["lifts"],
        json!([]),
        "both pauses ended"
    );
    assert_eq!(
        count(
            &app,
            "SELECT COUNT(*) FROM device_commands WHERE command = 'end_windows'"
        )
        .await,
        1
    );
    assert_eq!(
        count(
            &app,
            "SELECT COUNT(*) FROM security_events WHERE event_type = 'time_lock_now'"
        )
        .await,
        1
    );

    let res = post(
        &app,
        &cookie,
        &format!("/devices/{id}/rules-now"),
        &[
            ("mode", "on"),
            ("rule", &skole.to_string()),
            ("on_len", "60"),
        ],
    )
    .await;
    assert_redirect(
        &res,
        &format!("/devices/{id}?done=on_busy&open=rules&mode=on&rule={skole}"),
        Some("quick"),
    );
    assert_eq!(count(&app, "SELECT COUNT(*) FROM time_lock_now").await, 1);
    let page = app
        .get_page(
            &format!("/devices/{id}?done=on_busy&open=rules&mode=on&rule={skole}"),
            &cookie,
        )
        .await;
    assert!(
        page.text()
            .contains("Emma already has Skole on until midnight."),
        "{}",
        page.text()
    );

    // Without a zone in the newest report nothing can start.
    sqlx::query("UPDATE time_lock_now SET ended_early_at = datetime('now')")
        .execute(&app.db)
        .await
        .unwrap();
    app.post_status(&token, json!({"time_state": {"lock_reason": "NONE"}}))
        .await;
    let res = post(
        &app,
        &cookie,
        &format!("/devices/{id}/rules-now"),
        &[
            ("mode", "on"),
            ("rule", &skole.to_string()),
            ("on_len", "day"),
        ],
    )
    .await;
    assert_redirect(
        &res,
        &format!("/devices/{id}?done=on_no_zone&open=rules&mode=on&rule={skole}"),
        Some("quick"),
    );
}

/// §5.2, §5.3: a change with the end the page showed moves `until_ms`; a stale one is another
/// parent's and is refused; End now stops the copy, and a second End now changes nothing.
#[tokio::test]
async fn a_turned_on_rule_is_changed_and_ended() {
    let (app, cookie, id, token, skole) = emma(json!({})).await;
    let res = post(
        &app,
        &cookie,
        &format!("/devices/{id}/rules-now"),
        &[
            ("mode", "on"),
            ("rule", &skole.to_string()),
            ("on_len", "60"),
        ],
    )
    .await;
    assert_redirect(&res, &format!("/devices/{id}?done=on"), Some("quick"));
    let (lock, seen): (i64, String) = sqlx::query_as("SELECT id, ends_at FROM time_lock_now")
        .fetch_one(&app.db)
        .await
        .unwrap();
    let before = copies(&app, &token).await[0]["until_ms"].as_i64().unwrap();
    let change = format!("/devices/{id}/lock-now/{lock}");
    let res = post(
        &app,
        &cookie,
        &change,
        &[("add", "30"), ("til", ""), ("seen", &seen)],
    )
    .await;
    assert_redirect(
        &res,
        &format!("/devices/{id}?done=on_changed"),
        Some("quick"),
    );
    let after = copies(&app, &token).await[0]["until_ms"].as_i64().unwrap();
    assert_eq!(after - before, 30 * 60_000);
    let res = post(&app, &cookie, &change, &[("add", "30"), ("seen", &seen)]).await;
    assert_redirect(
        &res,
        &format!("/devices/{id}?done=changed_elsewhere&open=rules&mode=on&rule={skole}"),
        Some("quick"),
    );
    assert_eq!(copies(&app, &token).await[0]["until_ms"], after);

    let end = format!("/devices/{id}/lock-now/{lock}/end");
    let res = post(&app, &cookie, &end, &[]).await;
    assert_redirect(&res, &format!("/devices/{id}?done=on_ended"), Some("quick"));
    assert!(copies(&app, &token).await.is_empty());
    let res = post(&app, &cookie, &end, &[]).await;
    assert_redirect(&res, &format!("/devices/{id}?done=on_over"), Some("quick"));
    assert_eq!(
        count(
            &app,
            "SELECT COUNT(*) FROM security_events WHERE event_type = 'time_lock_now_ended'"
        )
        .await,
        1
    );
    let res = post(
        &app,
        &cookie,
        &format!("/devices/{id}/lock-now/999/end"),
        &[],
    )
    .await;
    assert_eq!(res.status, StatusCode::NOT_FOUND);
}

/// §3, §4.3: a pause of the rule that is on is served with its twin for the copy; the copy goes
/// when its end has passed, and when its rule no longer applies to the phone.
#[tokio::test]
async fn the_copy_follows_its_pause_its_end_and_its_rule() {
    let (app, cookie, id, token, skole) = emma(json!({})).await;
    post(
        &app,
        &cookie,
        &format!("/devices/{id}/rules-now"),
        &[
            ("mode", "on"),
            ("rule", &skole.to_string()),
            ("on_len", "day"),
        ],
    )
    .await;
    let res = post(
        &app,
        &cookie,
        &format!("/devices/{id}/rules-now"),
        &[
            ("mode", "pause"),
            ("rule", &skole.to_string()),
            ("pause_minutes", "15"),
        ],
    )
    .await;
    assert_redirect(&res, &format!("/devices/{id}?done=pause"), Some("quick"));
    let lifts = app.policy(&token).await["time_policy"]["lifts"].clone();
    let lock: i64 = count(&app, "SELECT id FROM time_lock_now").await;
    assert_eq!(lifts.as_array().unwrap().len(), 2, "{lifts}");
    assert_eq!(lifts[1]["id"], -lifts[0]["id"].as_i64().unwrap());
    assert_eq!(lifts[1]["rule_id"], -lock);
    assert_eq!(lifts[1]["expires_at_ms"], lifts[0]["expires_at_ms"]);

    sqlx::query("INSERT INTO device_rule_off (device_id, rule_id) VALUES (?, ?)")
        .bind(id)
        .bind(skole)
        .execute(&app.db)
        .await
        .unwrap();
    assert!(
        copies(&app, &token).await.is_empty(),
        "a rule switched off isn't served"
    );
    // ...and its lock is over, with no parent named (Activity's "switched off"): switched on
    // again, it doesn't come back.
    sqlx::query("DELETE FROM device_rule_off")
        .execute(&app.db)
        .await
        .unwrap();
    assert!(copies(&app, &token).await.is_empty(), "not revived");
    assert_eq!(
        count(
            &app,
            "SELECT COUNT(*) FROM time_lock_now WHERE ended_early_at IS NOT NULL AND ended_by IS NULL"
        )
        .await,
        1
    );
    sqlx::query("UPDATE time_lock_now SET ended_early_at = NULL")
        .execute(&app.db)
        .await
        .unwrap();
    assert_eq!(copies(&app, &token).await.len(), 1);
    sqlx::query("UPDATE time_lock_now SET ends_at = datetime('now', '-1 minute')")
        .execute(&app.db)
        .await
        .unwrap();
    assert!(copies(&app, &token).await.is_empty(), "over at its end");
}

/// `<section id="quick">` to its end.
fn quick(html: &str) -> &str {
    let start = html.find("<section id=\"quick\"").expect("#quick");
    let end = html[start..]
        .find("</section>")
        .map_or(html.len(), |e| start + e);
    &html[start..end]
}

/// §6.1, §6.2, §6.5: three tiles (design 123: Slå på regel always, Pause regler while a rule can be paused); a
/// tile's link opens its sheet with its mode and rule; a refusal reopens it with the reason in it.
#[tokio::test]
async fn the_tiles_open_their_sheets() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, _) = app.enrolled_device("Emma").await;
    let page = |q: &str| format!("/devices/{id}{q}");
    let html = app.get_page(&page(""), &cookie).await.text();
    let q = quick(&html);
    assert!(
        q.contains(&format!("href=\"/devices/{id}?open=time#quick\"")),
        "{q}"
    );
    assert!(
        q.contains("id=\"time-sheet\" role=\"region\" aria-labelledby=\"time-sheet-title\" hidden")
    );
    // Design 123 §2: no rule in the family - Slå på regel still, its sheet says so and links to a new rule.
    assert!(q.contains("open=rules&amp;mode=on"), "{q}");
    assert!(!q.contains("open=rules&amp;mode=pause"), "{q}");
    let html = app
        .get_page(&page("?open=rules&mode=on"), &cookie)
        .await
        .text();
    let q = quick(&html);
    let sheet = &q[q.find("id=\"rules-sheet\"").unwrap()..];
    assert!(sheet.contains(">No rules</p>"), "{sheet}");
    assert!(
        sheet.contains("<a class=\"row row-button\" href=\"/schedules/rules/new\">Add Rule</a>"),
        "{sheet}"
    );
    assert!(!sheet.contains("<form"), "{sheet}");

    let (app, cookie, id, _, skole) = emma(json!({})).await;
    // Skole with a day, so it can be paused (design 123 §3.3).
    sqlx::query(
        "UPDATE time_rules SET days_json = \
         '[null,null,null,null,null,null,{\"start\":0,\"end\":1}]' WHERE id = ?",
    )
    .bind(skole)
    .execute(&app.db)
    .await
    .unwrap();
    let page = |q: &str| format!("/devices/{id}{q}");
    let html = app
        .get_page(&page("?open=rules&mode=pause"), &cookie)
        .await
        .text();
    let q = quick(&html);
    assert!(
        q.contains("id=\"rules-sheet\" role=\"region\" aria-labelledby=\"rules-sheet-title\">"),
        "{q}"
    );
    assert!(q.contains("id=\"m-pause\" checked"), "{q}");
    assert!(
        q.contains("<option value=\"all\" data-all data-pause selected>All rules</option>"),
        "{q}"
    );
    let html = app
        .get_page(&page("?open=rules&mode=on"), &cookie)
        .await
        .text();
    let q = quick(&html);
    assert!(q.contains("id=\"m-on\" checked"), "{q}");
    assert!(
        q.contains(&format!(
            "<option value=\"{skole}\" data-on data-pause data-on-default"
        )),
        "{q}"
    );
    assert!(q.contains(" selected>Skole</option>"), "{q}");
    assert!(
        q.contains("org.fossify.clock and emergency calls work."),
        "{q}"
    );

    let res = post(
        &app,
        &cookie,
        &format!("/devices/{id}/rules-now"),
        &[("mode", "on"), ("rule", "all"), ("on_len", "day")],
    )
    .await;
    let back = res
        .location()
        .unwrap()
        .split('#')
        .next()
        .unwrap()
        .to_string();
    let html = app.get_page(&back, &cookie).await.text();
    let q = quick(&html);
    let sheet = &q[q.find("id=\"rules-sheet\"").unwrap()..];
    assert!(
        sheet.contains("data-flash=\"rules-sheet\">Pick one rule to turn on.</p>"),
        "{q}"
    );
    assert!(sheet.contains("id=\"m-on\" checked"), "{q}");
}

/// §7: Pause regler says the rule in force only from a fresh report; Slå på regel says only a rule
/// turned on, "· waiting" until a report after it; the badge names the copy by its rule and its end.
#[tokio::test]
async fn the_state_lines_and_the_badge() {
    let (app, cookie, id, token, skole) = emma(json!({})).await;
    let zone = roomy_zone();
    sqlx::query("UPDATE provisioning_settings SET time_zone = ?")
        .bind(zone)
        .execute(&app.db)
        .await
        .unwrap();
    let today = chrono::Utc::now()
        .with_timezone(&zone.parse::<chrono_tz::Tz>().unwrap())
        .format("%Y-%m-%d")
        .to_string();
    let summary = format!("/devices/{id}");
    post(
        &app,
        &cookie,
        &format!("/devices/{id}/rules-now"),
        &[
            ("mode", "on"),
            ("rule", &skole.to_string()),
            ("on_len", "day"),
        ],
    )
    .await;
    let html = app.get_page(&summary, &cookie).await.text();
    assert!(
        quick(&html).contains("Skole on until midnight · waiting"),
        "{}",
        quick(&html)
    );
    let lock = count(&app, "SELECT id FROM time_lock_now").await;
    // The report below comes after the start (a report from the same second still waits).
    sqlx::query("UPDATE time_lock_now SET created_at = datetime('now', '-1 minute')")
        .execute(&app.db)
        .await
        .unwrap();
    let report = json!({
        "lock_reason": "SCHOOL",
        "time_state": {"day": today, "used_minutes": 0, "lock_reason": "SCHOOL", "active_rule_id": -lock,
                       "active_rule_name": "Skole", "lifts_active": [], "zone": zone}
    });
    app.post_status(&token, report).await;
    let html = app.get_page(&summary, &cookie).await.text();
    let q = quick(&html);
    assert!(q.contains("Skole is on now"), "{q}");
    assert!(q.contains(">Skole on until midnight<"), "{q}");
    assert!(
        html.contains("Skole until 00:00 tomorrow"),
        "the badge names the copy by its rule: {html}"
    );
    // A stale report says nothing of the rule in force.
    sqlx::query("UPDATE device_status SET reported_at = datetime('now', '-2 days')")
        .execute(&app.db)
        .await
        .unwrap();
    let html = app.get_page(&summary, &cookie).await.text();
    assert!(!quick(&html).contains("is on now"), "{}", quick(&html));
}

/// §6.6: Avansert's first row, "Åpne for oppsett", posts `back=advanced` and lands back there,
/// in its group, with the flash.
#[tokio::test]
async fn the_advanced_page_opens_for_setup_and_lands_back_there() {
    let (app, cookie, id, _token, _skole) = emma(json!({})).await;
    let page = app
        .get_page(&format!("/devices/{id}/advanced"), &cookie)
        .await
        .text();
    assert!(page.contains("id=\"setup-sheet\""), "{page}");
    // Design 103 §3: one row that opens in place - no second tile and title, no Close; design 113: no line, the button
    // says the 2 hours ("Open for 2 h").
    let words = |key| crate::i18n::t(crate::i18n::Lang::En, key);
    let sheet = |page: &str| -> String {
        let at = page.find("id=\"setup-sheet\"").unwrap();
        let end = page[at..].find("</details>").unwrap() + at;
        page[at..end].to_string()
    };
    let closed = sheet(&page);
    assert!(!closed.contains("qa-head"), "{closed}");
    assert!(!closed.contains("data-confirm-cancel"), "{closed}");
    assert!(
        !closed.contains(words("device.summary.qa.close")),
        "{closed}"
    );
    assert!(!closed.contains("qa-danger"), "{closed}");
    assert!(!closed.contains("Apps, Settings and Play open"), "{closed}");
    assert!(
        closed.contains(words("device.advanced.setup.open_now")),
        "{closed}"
    );
    assert!(!closed.contains("so you can set something up"), "{closed}");
    let res = post(
        &app,
        &cookie,
        &format!("/devices/{id}/start-pause"),
        &[("back", "advanced")],
    )
    .await;
    assert_redirect(
        &res,
        &format!("/devices/{id}/advanced?notice=start_pause_sent"),
        Some("setup"),
    );
    let page = app
        .get_page(
            &format!("/devices/{id}/advanced?notice=start_pause_sent"),
            &cookie,
        )
        .await
        .text();
    assert!(page.contains("data-flash=\"setup\""), "{page}");

    // 103-11: open with no known end (no answered start_pause): the row has no value, so the sheet says it is open.
    let (app, cookie, id, _token, _skole) = emma(json!({"restrictions_paused": true})).await;
    let page = app
        .get_page(&format!("/devices/{id}/advanced"), &cookie)
        .await
        .text();
    let open = sheet(&page);
    assert!(!open.contains("class=\"row-value\""), "{open}");
    let line = format!(
        "<p class=\"row row-note\">{}</p>",
        words("device.summary.qa.setup_open")
    );
    assert!(open.contains(&line), "{open}");
    assert!(open.contains(words("device.summary.lock_again")), "{open}");
    assert!(
        !open.contains(words("device.advanced.setup.open_now")),
        "{open}"
    );
}

/// Design 123 §3: a rule without days and a rule switched off for the phone are on the on-list and turned on as any
/// other - the copy served, the rule not -; only a deleted rule is `on_gone`; a rule that can't be paused isn't on the
/// pause list, and with nothing to pause the sheet is Slå på regel's alone (no Pause regler, no Hva). The on-list's
/// default is the first rule without days.
#[tokio::test]
async fn a_rule_without_days_or_switched_off_is_turned_on() {
    let (app, cookie, id, token, skole) = emma(json!({})).await;
    let lekser: i64 = sqlx::query_scalar(
        "INSERT INTO time_rules (name, kind, calls_allowed, exempt_apps_json, days_json, sort_order) \
         VALUES ('Lekser', 'custom', 1, '[]', \
         '[{\"start\":0,\"end\":1},null,null,null,null,null,null]', 9) RETURNING id",
    )
    .fetch_one(&app.db)
    .await
    .unwrap();
    sqlx::query("INSERT INTO device_rule_off (device_id, rule_id) VALUES (?, ?)")
        .bind(id)
        .bind(lekser)
        .execute(&app.db)
        .await
        .unwrap();
    let summary = format!("/devices/{id}");
    let html = app.get_page(&summary, &cookie).await.text();
    let q = quick(&html);
    assert!(q.contains("open=rules&amp;mode=on"), "{q}");
    assert!(
        !q.contains("open=rules&amp;mode=pause"),
        "nothing to pause: {q}"
    );
    let html = app
        .get_page(&format!("{summary}?open=rules&mode=pause"), &cookie)
        .await
        .text();
    let q = quick(&html);
    assert!(q.contains("id=\"m-on\" checked"), "on mode only: {q}");
    assert!(!q.contains("id=\"m-pause\""), "{q}");
    assert!(!q.contains("qa-hva"), "{q}");
    assert!(!q.contains("value=\"all\""), "{q}");
    assert!(
        q.contains(&format!(
            "<option value=\"{skole}\" data-on data-on-default"
        )),
        "{q}"
    );
    assert!(
        q.contains(&format!("<option value=\"{lekser}\" data-on data-line")),
        "{q}"
    );
    assert!(
        q.contains(">Lekser</option>"),
        "the rule off for the phone: {q}"
    );

    // Lekser, off for the phone, is turned on: its copy is served, the rule itself not.
    let res = post(
        &app,
        &cookie,
        &format!("/devices/{id}/rules-now"),
        &[
            ("mode", "on"),
            ("rule", &lekser.to_string()),
            ("on_len", "day"),
        ],
    )
    .await;
    assert_redirect(&res, &format!("/devices/{id}?done=on"), Some("quick"));
    let rules = app.policy(&token).await["time_policy"]["rules"].clone();
    let ids: Vec<i64> = rules
        .as_array()
        .unwrap()
        .iter()
        .map(|r| r["id"].as_i64().unwrap())
        .collect();
    assert!(!ids.contains(&lekser), "{rules}");
    let copy = copies(&app, &token).await;
    assert_eq!(copy.len(), 1, "{rules}");
    assert_eq!(copy[0]["name"], "Lekser");
    assert_eq!(copy[0]["copy_of"], lekser);
    // The rule turned on can be paused now; Skole (no days) still can't.
    let res = post(
        &app,
        &cookie,
        &format!("/devices/{id}/rules-now"),
        &[
            ("mode", "pause"),
            ("rule", &lekser.to_string()),
            ("pause_minutes", "15"),
        ],
    )
    .await;
    assert_redirect(&res, &format!("/devices/{id}?done=pause"), Some("quick"));
    let res = post(
        &app,
        &cookie,
        &format!("/devices/{id}/lifts"),
        &[
            ("target", "rule"),
            ("rule", &skole.to_string()),
            ("minutes", "30"),
        ],
    )
    .await;
    assert_redirect(
        &res,
        &format!("/devices/{id}?done=pause_not_in_force"),
        Some("quick"),
    );
    assert_eq!(count(&app, "SELECT COUNT(*) FROM time_lifts").await, 1);
    let lifts = app.policy(&token).await["time_policy"]["lifts"].clone();
    assert_eq!(
        lifts.as_array().unwrap().len(),
        2,
        "the pause and its twin: {lifts}"
    );

    // A rule without days turned on locks in its window like any copy.
    sqlx::query("UPDATE time_lock_now SET ended_early_at = datetime('now')")
        .execute(&app.db)
        .await
        .unwrap();
    let res = post(
        &app,
        &cookie,
        &format!("/devices/{id}/rules-now"),
        &[
            ("mode", "on"),
            ("rule", &skole.to_string()),
            ("on_len", "day"),
        ],
    )
    .await;
    assert_redirect(&res, &format!("/devices/{id}?done=on"), Some("quick"));
    let copy = copies(&app, &token).await;
    assert_eq!(copy.len(), 1);
    assert_eq!(copy[0]["copy_of"], skole);

    // Deleted since the page loaded: on_gone.
    sqlx::query("UPDATE time_lock_now SET ended_early_at = datetime('now')")
        .execute(&app.db)
        .await
        .unwrap();
    sqlx::query("DELETE FROM time_rules WHERE id = ?")
        .bind(lekser)
        .execute(&app.db)
        .await
        .unwrap();
    let res = post(
        &app,
        &cookie,
        &format!("/devices/{id}/rules-now"),
        &[
            ("mode", "on"),
            ("rule", &lekser.to_string()),
            ("on_len", "day"),
        ],
    )
    .await;
    assert_redirect(
        &res,
        &format!("/devices/{id}?done=on_gone&open=rules&mode=on&rule={lekser}"),
        Some("quick"),
    );
}

/// QA L3: on mode only and Slå på nå can't be chosen (no zone before the first report): the reason as a line, no
/// button. QA L7: a bedtime without days is never the default.
#[tokio::test]
async fn an_on_only_sheet_says_why_it_cant_turn_on() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, _) = app.enrolled_device("Emma").await;
    let lekser: i64 = sqlx::query_scalar(
        "INSERT INTO time_rules (name, kind, calls_allowed, exempt_apps_json, days_json) \
         VALUES ('Lekser', 'custom', 1, '[]', '[{\"start\":0,\"end\":1},null,null,null,null,null,null]') \
         RETURNING id",
    )
    .fetch_one(&app.db)
    .await
    .unwrap();
    sqlx::query("INSERT INTO device_rule_off (device_id, rule_id) VALUES (?, ?)")
        .bind(id)
        .bind(lekser)
        .execute(&app.db)
        .await
        .unwrap();
    sqlx::query(
        "INSERT INTO time_rules (device_id, name, kind, calls_allowed, exempt_apps_json, days_json, bedtime) \
         VALUES (?, 'Bedtime', 'bedtime', 1, '[]', '[null,null,null,null,null,null,null]', 1)",
    )
    .bind(id)
    .execute(&app.db)
    .await
    .unwrap();
    let html = app
        .get_page(&format!("/devices/{id}?open=rules&mode=on"), &cookie)
        .await
        .text();
    let q = quick(&html);
    let sheet = &q[q.find("id=\"rules-sheet\"").unwrap()..];
    let why = crate::i18n::t(crate::i18n::Lang::En, "device.summary.flash.on_no_zone");
    assert!(
        sheet.contains(&format!(
            "<p class=\"row row-note\" data-qa-on-off>{why}</p>"
        )),
        "{sheet}"
    );
    assert!(!sheet.contains("data-qa-button"), "{sheet}");
    assert!(
        sheet.contains(&format!(
            "<option value=\"{lekser}\" data-on data-on-default"
        )),
        "{sheet}"
    );
    assert_eq!(sheet.matches("data-on-default").count(), 1, "{sheet}");
}
