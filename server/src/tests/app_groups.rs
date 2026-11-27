//! Design 139 part A: app groups - the pages (save, add, refuse in place, delete, the return table's group rows in
//! `time_pages::every_form_on_the_time_pages_returns_to_its_group`, D2's overlap, another phone's group), the policy
//! against `contracts/core/app_groups.json`, the group lift with its Activity line (`fits` refusing it is in
//! `time_request::a_fitting_lift_answers_the_open_request`), the migration's rebuild of `time_lifts`, the report's
//! groups through `TIME_STATE_FIELDS`, and the setup step's line on Device › Time Rules.

use axum::http::{Method, StatusCode};
use serde_json::{Value, json};

use super::{Section, TestApp, assert_redirect, device_page};

const CONTRACT: &str = crate::contracts::contract!("core/app_groups.json");

fn contract() -> Value {
    serde_json::from_str(CONTRACT).unwrap()
}

/// The section `id`'s markup.
fn section<'a>(html: &'a str, id: &str) -> &'a str {
    let at = html
        .find(&format!("<section id=\"{id}\""))
        .unwrap_or_else(|| panic!("no section {id}"));
    &html[at..at + html[at..].find("</section>").unwrap()]
}

/// The phone reports these apps installed, and its allowlist allows them.
async fn report_apps(app: &TestApp, token: &str, apps: &[(&str, &str)]) {
    let allowed: Vec<&str> = apps.iter().map(|(package, _)| *package).collect();
    sqlx::query("UPDATE device_policy SET allowlist_json = ?")
        .bind(serde_json::to_string(&allowed).unwrap())
        .execute(&app.db)
        .await
        .unwrap();
    let installed: Vec<Value> = apps
        .iter()
        .map(|(package, label)| json!({"package_name": package, "label": label}))
        .collect();
    app.post_status(token, json!({ "installed_apps": installed }))
        .await;
}

async fn groups_of(app: &TestApp, id: i64) -> Vec<(String, String, String)> {
    sqlx::query_as(
        "SELECT name, apps_json, days_json FROM app_groups WHERE device_id = ? ORDER BY id",
    )
    .bind(id)
    .fetch_all(&app.db)
    .await
    .unwrap()
}

#[tokio::test]
async fn the_group_pages_add_save_refuse_and_delete_in_place() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("Anna").await;
    let (other, _) = app.enrolled_device("Emil").await;
    report_apps(
        &app,
        &token,
        &[
            ("org.example.game", "Game"),
            ("org.example.puzzle", "Puzzle"),
            ("org.example.video", "Video"),
            ("me.vibb.music", "Vibb music"),
        ],
    )
    .await;
    let time = device_page(id, Section::Time);

    // Device › Time Rules has the group, empty, with Add.
    let html = app.get_page(&time, &cookie).await.text();
    let groups = section(&html, "groups");
    assert!(groups.contains("App groups"));
    assert!(groups.contains(&format!(
        "href=\"/devices/{id}/time/groups/new\">Add group</a>"
    )));
    assert!(!groups.contains("data-group="));

    // A new group offers the phone's apps, never ours.
    let new = format!("/devices/{id}/time/groups/new");
    let html = app.get_page(&new, &cookie).await.text();
    assert!(html.contains("data-save=\"group\">Add</button>"));
    assert!(html.contains("value=\"org.example.game\""));
    assert!(
        !html.contains("value=\"me.vibb.music\""),
        "Vibb music is never in a group"
    );
    assert!(
        groups_of(&app, id).await.is_empty(),
        "nothing stored by opening it"
    );

    // Refused in place: no name, no app - the page again (400) as typed, the reason under the field and in the bar.
    let res = app
        .request_form(
            Method::POST,
            &new,
            Some(&cookie),
            &[("name", ""), ("apps", "org.example.game")],
        )
        .await;
    assert_eq!(res.status, StatusCode::BAD_REQUEST);
    let html = res.text();
    assert!(html.contains("data-flash=\"name\">Give the group a name</p>"));
    assert!(html.contains("data-flash=\"refused\">"));
    assert!(html.contains("aria-describedby=\"name-error\" autofocus"));
    let res = app
        .request_form(Method::POST, &new, Some(&cookie), &[("name", "Spill <b>")])
        .await;
    assert_eq!(res.status, StatusCode::BAD_REQUEST);
    let html = res.text();
    assert!(html.contains("data-flash=\"apps\">Choose at least one app</p>"));
    assert!(
        html.contains("value=\"Spill ") && !html.contains("Spill <b>"),
        "as typed, escaped"
    );
    assert!(groups_of(&app, id).await.is_empty());

    // Add: the new group's page.
    let mut nudges = app.state.command_notify.subscribe();
    let res = app
        .request_form(
            Method::POST,
            &new,
            Some(&cookie),
            &[
                ("name", "Spill"),
                ("apps", "org.example.game"),
                ("apps", "org.example.puzzle"),
                ("b0", "60"),
                ("b1", "60"),
                ("b2", "60"),
                ("b3", "60"),
                ("b4", "60"),
                ("b5", "120"),
                ("b6", "120"),
            ],
        )
        .await;
    let gid: i64 = sqlx::query_scalar("SELECT MAX(id) FROM app_groups")
        .fetch_one(&app.db)
        .await
        .unwrap();
    let page = format!("/devices/{id}/time/groups/{gid}");
    assert_redirect(&res, &format!("{page}?saved=added"), Some("group"));
    app.assert_lands(&res, &cookie).await;
    assert_eq!(nudges.try_recv().ok(), Some(id));
    assert_eq!(
        groups_of(&app, id).await,
        [(
            "Spill".to_string(),
            r#"["org.example.game","org.example.puzzle"]"#.to_string(),
            "[60,60,60,60,60,120,120]".to_string()
        )]
    );
    let html = app
        .get_page(&format!("{page}?saved=added"), &cookie)
        .await
        .text();
    assert!(html.contains("data-flash=\"group\">Saved.</p>"));
    assert!(html.contains("data-save=\"group\">Save</button>"));
    assert!(html.contains("Delete group"));

    // Device › Time Rules: its row, the days and apps under the name.
    let html = app.get_page(&time, &cookie).await.text();
    let groups = section(&html, "groups");
    assert!(groups.contains(&format!(
        "href=\"/devices/{id}/time/groups/{gid}\" data-group=\"{gid}\""
    )));
    assert!(
        groups.contains("Mon-Fri 1 h · Sat-Sun 2 h · 2 apps"),
        "{groups}"
    );

    // D2: a second group offers neither of the first's apps, and a post with one is refused, naming it.
    let html = app.get_page(&new, &cookie).await.text();
    assert!(!html.contains("value=\"org.example.game\""));
    assert!(html.contains("value=\"org.example.video\""));
    let res = app
        .request_form(
            Method::POST,
            &new,
            Some(&cookie),
            &[("name", "Video"), ("apps", "org.example.game")],
        )
        .await;
    assert_eq!(res.status, StatusCode::BAD_REQUEST);
    assert!(res.text().contains("Game is in Spill"));
    assert_eq!(groups_of(&app, id).await.len(), 1);

    // Save: back to its form; a refused limit names its day.
    let res = app
        .request_form(
            Method::POST,
            &page,
            Some(&cookie),
            &[
                ("name", "Spill"),
                ("apps", "org.example.game"),
                ("b0", "45"),
            ],
        )
        .await;
    assert_redirect(&res, &format!("{page}?saved=1"), Some("group"));
    assert_eq!(groups_of(&app, id).await[0].1, r#"["org.example.game"]"#);
    let res = app
        .request_form(
            Method::POST,
            &page,
            Some(&cookie),
            &[
                ("name", "Spill"),
                ("apps", "org.example.game"),
                ("b3", "2000"),
            ],
        )
        .await;
    assert_eq!(res.status, StatusCode::BAD_REQUEST);
    assert!(
        res.text()
            .contains("aria-describedby=\"b3-error\" autofocus")
    );

    // Another phone's group is a 404, for a page and a post alike; nothing written.
    let elsewhere = format!("/devices/{other}/time/groups/{gid}");
    assert_eq!(
        app.get_page(&elsewhere, &cookie).await.status,
        StatusCode::NOT_FOUND
    );
    let res = app
        .request_form(
            Method::POST,
            &format!("{elsewhere}/delete"),
            Some(&cookie),
            &[],
        )
        .await;
    assert_eq!(res.status, StatusCode::NOT_FOUND);
    assert_eq!(groups_of(&app, id).await.len(), 1);

    // Delete: Device › Time Rules' group with its flash; a second delete (another tab) lands there too.
    let res = app
        .request_form(Method::POST, &format!("{page}/delete"), Some(&cookie), &[])
        .await;
    assert_redirect(
        &res,
        &format!("{time}?notice=group_deleted"),
        Some("groups"),
    );
    app.assert_lands(&res, &cookie).await;
    assert!(groups_of(&app, id).await.is_empty());
    let html = app
        .get_page(&format!("{time}?notice=group_deleted"), &cookie)
        .await
        .text();
    assert!(section(&html, "groups").contains("data-flash=\"groups\">The group is deleted.</p>"));
    for res in [
        app.request_form(Method::POST, &format!("{page}/delete"), Some(&cookie), &[])
            .await,
        app.request_form(
            Method::POST,
            &page,
            Some(&cookie),
            &[("name", "Spill"), ("apps", "org.example.game")],
        )
        .await,
    ] {
        assert_redirect(&res, &format!("{time}?notice=group_gone"), Some("groups"));
    }
    assert!(groups_of(&app, id).await.is_empty(), "nothing written");

    // Each write is in the security log with the group's name.
    let events: Vec<String> = sqlx::query_scalar(
        "SELECT event_type FROM security_events WHERE event_type LIKE 'app_group%' ORDER BY id",
    )
    .fetch_all(&app.db)
    .await
    .unwrap();
    assert_eq!(
        events,
        ["app_group_saved", "app_group_saved", "app_group_deleted"]
    );
}

#[tokio::test]
async fn the_policy_serves_the_groups_and_a_group_lift_as_the_contract() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie_as("mamma", "Mamma").await;
    let (id, token) = app.enrolled_device("Anna").await;
    let contract = contract();
    let policy = app.policy(&token).await;
    assert_eq!(policy["time_policy"]["groups"], json!([]), "always the key");

    let wanted = &contract["policy"]["groups"][0];
    sqlx::query(
        "INSERT INTO app_groups (device_id, name, apps_json, days_json) VALUES (?, ?, ?, ?)",
    )
    .bind(id)
    .bind(wanted["name"].as_str().unwrap())
    .bind(wanted["apps"].to_string())
    .bind(wanted["days"].to_string())
    .execute(&app.db)
    .await
    .unwrap();
    // The Skjermtid sheet's Hva names it, and gives it extra time.
    let summary = app
        .get_page(&format!("/devices/{id}"), &cookie)
        .await
        .text();
    assert!(
        summary.contains("name=\"what\" value=\"group-1\""),
        "the Hva choice"
    );
    let mut nudges = app.state.command_notify.subscribe();
    let res = app
        .request_form(
            Method::POST,
            &format!("/devices/{id}/lifts"),
            Some(&cookie),
            &[("what", "group-1"), ("minutes", "15")],
        )
        .await;
    assert_redirect(&res, &format!("/devices/{id}?done=extra"), Some("quick"));
    assert_eq!(nudges.try_recv().ok(), Some(id));

    let policy = app.policy(&token).await;
    let time = &policy["time_policy"];
    assert_eq!(time["groups"], contract["policy"]["groups"]);
    let lift = &time["lifts"][0];
    let expected = &contract["policy"]["lifts"][0];
    let keys = |v: &Value| {
        let mut k: Vec<String> = v.as_object().unwrap().keys().cloned().collect();
        k.sort();
        k
    };
    assert_eq!(keys(lift), keys(expected));
    for key in ["id", "target", "rule_id", "minutes", "group_id", "by"] {
        assert_eq!(lift[key], expected[key], "{key}");
    }

    // Activity names the group; the summary's Today too.
    let activity = app
        .get_page(&format!("/devices/{id}/activity"), &cookie)
        .await
        .text();
    assert!(activity.contains("Spill: 15"), "{activity}");

    // A group of another phone (or none) is a 404, nothing written.
    let (other, _) = app.enrolled_device("Emil").await;
    let res = app
        .request_form(
            Method::POST,
            &format!("/devices/{other}/lifts"),
            Some(&cookie),
            &[("target", "group"), ("group", "1"), ("minutes", "15")],
        )
        .await;
    assert_eq!(res.status, StatusCode::NOT_FOUND);
    let lifts: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM time_lifts")
        .fetch_one(&app.db)
        .await
        .unwrap();
    assert_eq!(lifts, 1);

    // A group that doesn't read is a 500, never fewer groups.
    sqlx::query("UPDATE app_groups SET days_json = '[60]'")
        .execute(&app.db)
        .await
        .unwrap();
    let res = app
        .request(Method::GET, "/api/devices/policy", Some(&token), None)
        .await;
    assert_eq!(res.status, StatusCode::INTERNAL_SERVER_ERROR);
    // Once the group is gone its lift stops being delivered; Activity keeps its name.
    sqlx::query("DELETE FROM app_groups")
        .execute(&app.db)
        .await
        .unwrap();
    let policy = app.policy(&token).await;
    assert_eq!(policy["time_policy"]["lifts"], json!([]));
    let activity = app
        .get_page(&format!("/devices/{id}/activity"), &cookie)
        .await
        .text();
    assert!(activity.contains("Spill: 15"));
}

#[tokio::test]
async fn the_report_keeps_the_groups_known_keys() {
    use crate::handlers::device_api::{TIME_STATE_FIELDS, known_fields};
    for row in contract()["report_kept"].as_array().unwrap() {
        let kept = known_fields(&row["sent"], TIME_STATE_FIELDS)
            .map(|k| serde_json::from_str::<Value>(&k).unwrap());
        assert_eq!(kept.unwrap_or(Value::Null), row["kept"], "{}", row["sent"]);
    }
    // At most 50 groups.
    let many: Vec<Value> = (0..51).map(|i| json!({"id": i})).collect();
    assert_eq!(
        known_fields(&json!({"groups": many}), TIME_STATE_FIELDS),
        None
    );
}

#[tokio::test]
async fn device_time_rules_shows_todays_state_and_the_setup_line() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("Anna").await;
    let gid: i64 = sqlx::query_scalar(
        "INSERT INTO app_groups (device_id, name, apps_json, days_json) VALUES (?, 'Spill', \
         '[\"org.example.game\"]', '[60,60,60,60,60,60,60]') RETURNING id",
    )
    .bind(id)
    .fetch_one(&app.db)
    .await
    .unwrap();
    let today = crate::display_time::Zone::load(&app.db, crate::i18n::Lang::En)
        .await
        .today(chrono::Utc::now())
        .format("%Y-%m-%d")
        .to_string();
    let time = device_page(id, Section::Time);
    // App usage data off: design 114's line at the group, linking to the step.
    app.post_status(
        &token,
        json!({"time_state": {"day": today, "used_minutes": 0, "budget_minutes": null, "extra_minutes": 0,
            "active_rule_id": null, "active_rule_name": null, "calls_blocked": false, "lock_reason": "NONE",
            "lifts_active": [], "usage_access": false,
            "groups": [{"id": gid, "used_minutes": 40, "extra_minutes": 15, "closed": true}]}}),
    )
    .await;
    let html = app.get_page(&time, &cookie).await.text();
    let groups = section(&html, "groups");
    assert!(
        groups.contains(&format!("href=\"/devices/{id}/setup#step-usage_access\"")),
        "{groups}"
    );
    assert!(groups.contains("App usage data is off"));
    assert!(groups.contains("40 of 75 min"), "{groups}");
    let setup = app
        .get_page(&format!("/devices/{id}/setup"), &cookie)
        .await
        .text();
    assert!(setup.contains("App usage data"));
    // On, and the limit reached: no line, "Used up".
    app.post_status(
        &token,
        json!({"time_state": {"day": today, "used_minutes": 0, "budget_minutes": null, "extra_minutes": 0,
            "active_rule_id": null, "active_rule_name": null, "calls_blocked": false, "lock_reason": "NONE",
            "lifts_active": [], "usage_access": true,
            "groups": [{"id": gid, "used_minutes": 60, "extra_minutes": 0, "closed": true}]}}),
    )
    .await;
    let html = app.get_page(&time, &cookie).await.text();
    let groups = section(&html, "groups");
    assert!(!groups.contains("data-setup-warning"));
    assert!(groups.contains(">Used up<"), "{groups}");
    // A group with no limit on any day needs no access: no line even with it off.
    sqlx::query("UPDATE app_groups SET days_json = '[null,null,null,null,null,null,null]'")
        .execute(&app.db)
        .await
        .unwrap();
    app.post_status(
        &token,
        json!({"time_state": {"day": today, "used_minutes": 0, "budget_minutes": null, "extra_minutes": 0,
            "active_rule_id": null, "active_rule_name": null, "calls_blocked": false, "lock_reason": "NONE",
            "lifts_active": [], "usage_access": false, "groups": []}}),
    )
    .await;
    let html = app.get_page(&time, &cookie).await.text();
    let groups = section(&html, "groups");
    assert!(!groups.contains("data-setup-warning"));
    assert!(groups.contains(">No limit<"));
}

/// §5.1: the rebuild of `time_lifts` and `time_request_answers` keeps every row, id and `lift_id`, and the counter.
#[tokio::test]
async fn the_migration_keeps_every_lift_and_answer() {
    let (db, _dir) = super::cleanup::migrated_before(186).await;
    let device: i64 = sqlx::query_scalar("INSERT INTO devices (name) VALUES ('Anna') RETURNING id")
        .fetch_one(&db)
        .await
        .unwrap();
    for (id, target, rule, key) in [
        (3, "rule", Some(7), None),
        (5, "budget", None, None),
        (6, "rule", Some(7), Some("k1")),
        (9, "budget", None, None),
    ] {
        sqlx::query(
            "INSERT INTO time_lifts (id, device_id, target, rule_id, rule_name, minutes, expires_at, created_by, \
             phone_key) VALUES (?, ?, ?, ?, 'Skole', 30, datetime('now', '+1 hour'), 'mamma', ?)",
        )
        .bind(id)
        .bind(device)
        .bind(target)
        .bind(rule)
        .bind(key)
        .execute(&db)
        .await
        .unwrap();
    }
    // The highest id given was pruned since: the counter still remembers it.
    sqlx::query("DELETE FROM time_lifts WHERE id = 9")
        .execute(&db)
        .await
        .unwrap();
    for (request, answer, lift) in [
        ("r1", "yes", Some(5)),
        ("r2", "no", None),
        ("r3", "yes", Some(3)),
    ] {
        sqlx::query(
            "INSERT INTO time_request_answers (device_id, request_id, answer, lift_id, by_name) \
             VALUES (?, ?, ?, ?, 'Mamma')",
        )
        .bind(device)
        .bind(request)
        .bind(answer)
        .bind(lift)
        .execute(&db)
        .await
        .unwrap();
    }
    type Lift = (i64, String, Option<i64>, Option<String>);
    let lifts = |db: &sqlx::SqlitePool| {
        let db = db.clone();
        async move {
            sqlx::query_as::<_, Lift>(
                "SELECT id, target, rule_id, phone_key FROM time_lifts ORDER BY id",
            )
            .fetch_all(&db)
            .await
            .unwrap()
        }
    };
    let answers = |db: &sqlx::SqlitePool| {
        let db = db.clone();
        async move {
            sqlx::query_as::<_, (String, String, Option<i64>)>(
                "SELECT request_id, answer, lift_id FROM time_request_answers ORDER BY request_id",
            )
            .fetch_all(&db)
            .await
            .unwrap()
        }
    };
    let (lifts_before, answers_before) = (lifts(&db).await, answers(&db).await);
    super::cleanup::finish(&db).await;
    assert_eq!(lifts(&db).await, lifts_before);
    assert_eq!(answers(&db).await, answers_before);
    let next: i64 = sqlx::query_scalar(
        "INSERT INTO time_lifts (device_id, target, minutes, expires_at, group_name) \
         VALUES (?, 'group', 15, datetime('now'), 'Spill') RETURNING id",
    )
    .bind(device)
    .fetch_one(&db)
    .await
    .unwrap();
    assert_eq!(next, 10, "a lift id is never reused");
    // The child still follows its parent: a deleted lift clears its answer's `lift_id`.
    sqlx::query("DELETE FROM time_lifts WHERE id = 5")
        .execute(&db)
        .await
        .unwrap();
    assert_eq!(
        answers(&db).await[0],
        ("r1".to_string(), "yes".to_string(), None)
    );
    // A group's delete keeps its lift with its name.
    let group: i64 = sqlx::query_scalar(
        "INSERT INTO app_groups (device_id, name, days_json) VALUES (?, 'Spill', '[]') RETURNING id",
    )
    .bind(device)
    .fetch_one(&db)
    .await
    .unwrap();
    sqlx::query("UPDATE time_lifts SET group_id = ? WHERE id = ?")
        .bind(group)
        .bind(next)
        .execute(&db)
        .await
        .unwrap();
    sqlx::query("DELETE FROM app_groups")
        .execute(&db)
        .await
        .unwrap();
    let kept: (Option<i64>, Option<String>) =
        sqlx::query_as("SELECT group_id, group_name FROM time_lifts WHERE id = ?")
            .bind(next)
            .fetch_one(&db)
            .await
            .unwrap();
    assert_eq!(kept, (None, Some("Spill".to_string())));
}
