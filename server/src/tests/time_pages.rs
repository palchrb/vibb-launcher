//! Design 31 step 9: Settings › Time Rules (`/schedules`), the Daily Limit page, the rule editor
//! and Device › Time. The POST routes are the step-6 ones; this checks the pages, where every form
//! returns (the redirect table, §5.1, built from the rendered forms), the `section` split of
//! `/schedules/device/{id}` (§5.6, H1), refusals in place, local times and the old URLs.

use axum::http::{Method, StatusCode};
use serde_json::json;

use super::settings_pages::encloses;
use super::{
    Section, TestApp, assert_redirect, autosaves, device_page, form_action_of, input_checked,
    nav_current, tags,
};

/// The rule editor's sticky top bar (Save, and the save's flash or a refusal's reason).
fn bar(html: &str) -> &str {
    let at = html.find("data-bar=\"rule\"").expect("the top bar");
    let end = html[at..].find("<h1").unwrap();
    &html[at..at + end]
}

/// A form on a page: its action, where it starts, and its hidden fields.
type Form = (String, usize, Vec<(String, String)>);

/// Every form on `html`.
fn forms(html: &str) -> Vec<Form> {
    tags(html, "form")
        .into_iter()
        .map(|form| {
            let end = html[form.start..]
                .find("</form>")
                .map_or(html.len(), |e| form.start + e);
            let hidden = tags(&html[form.start..end], "input")
                .into_iter()
                .filter(|input| input.attr("type") == Some("hidden"))
                .map(|input| {
                    (
                        input.attr("name").unwrap_or("").to_string(),
                        input.attr("value").unwrap_or("").to_string(),
                    )
                })
                .collect();
            (
                form.attr("action").unwrap_or("").to_string(),
                form.start,
                hidden,
            )
        })
        .collect()
}

/// The section `id`'s markup.
fn section<'a>(html: &'a str, id: &str) -> &'a str {
    let at = html
        .find(&format!("<section id=\"{id}\""))
        .unwrap_or_else(|| panic!("no section {id}"));
    &html[at..at + html[at..].find("</section>").unwrap()]
}

async fn report(app: &TestApp, token: &str, state: serde_json::Value) {
    let res = app
        .request(
            Method::POST,
            "/api/devices/status",
            Some(token),
            Some(json!({
                "lock_reason": state["lock_reason"].as_str().unwrap_or("NONE"),
                "kiosk_engaged": true,
                "capabilities": ["call_policy_v1", "time_rules_v1"],
                "time_state": state
            })),
        )
        .await;
    assert_eq!(res.status, StatusCode::NO_CONTENT, "{}", res.text());
}

async fn add_rule(app: &TestApp, device: Option<i64>, name: &str, days: &str) -> i64 {
    sqlx::query_scalar(
        "INSERT INTO time_rules (device_id, name, kind, calls_allowed, exempt_apps_json, \
         days_json) VALUES (?, ?, 'school', 0, '[\"org.fossify.calendar\"]', ?) RETURNING id",
    )
    .bind(device)
    .bind(name)
    .bind(days)
    .fetch_one(&app.db)
    .await
    .unwrap()
}

const SCHOOL_DAYS: &str = r#"[{"start":495,"end":870},{"start":495,"end":870},{"start":495,"end":870},{"start":495,"end":870},{"start":495,"end":870},null,null]"#;

async fn budget_of(app: &TestApp, id: i64) -> (String, bool) {
    sqlx::query_as(
        "SELECT daily_budget_json, custom_schedule_enabled FROM device_policy WHERE device_id = ?",
    )
    .bind(id)
    .fetch_one(&app.db)
    .await
    .unwrap()
}

/// Settings › Time Rules: everyone's rules as rows with their days, the daily limit's row, a row
/// per phone to its Time page. Links only - no form, nothing to jump from.
#[tokio::test]
async fn time_rules_lists_rules_the_limit_and_the_phones() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (anna, _) = app.enrolled_device("Anna").await;
    let (emil, _) = app.enrolled_device("Emil").await;
    let skole = add_rule(&app, None, "Skole", SCHOOL_DAYS).await;
    let broken = add_rule(&app, None, "Broken", "[1,2]").await;
    add_rule(&app, Some(emil), "Emil's own", SCHOOL_DAYS).await;
    sqlx::query("UPDATE device_policy SET custom_schedule_enabled = 1 WHERE device_id = ?")
        .bind(emil)
        .execute(&app.db)
        .await
        .unwrap();
    sqlx::query(
        "UPDATE global_schedule SET daily_budget_json = '[90,90,90,90,90,120,120]' WHERE id = 1",
    )
    .execute(&app.db)
    .await
    .unwrap();

    let html = app.get_page("/schedules", &cookie).await.text();
    assert_eq!(nav_current(&html), "settings");
    assert!(html.contains("<a class=\"nav-back\" href=\"/settings\">"));
    assert!(html.contains("<h1 class=\"page-title\">Time Rules</h1>"));
    assert!(tags(&html, "form").is_empty(), "links only");
    let rules = section(&html, "rules");
    assert!(rules.contains(&format!("href=\"/schedules/rules/{skole}\"")));
    assert!(rules.contains("Mon-Fri 08:15-14:30 · calls off"), "{rules}");
    // A rule that doesn't read says so and still opens (to be saved again or deleted).
    assert!(rules.contains(&format!("href=\"/schedules/rules/{broken}\"")));
    assert!(rules.contains("Can&#39;t be read"));
    assert!(
        !rules.contains("Emil's own"),
        "a phone's own rule is on its Time page"
    );
    assert!(rules.contains("href=\"/schedules/rules/new\""));
    let limit = section(&html, "screen-time");
    assert!(limit.contains("href=\"/schedules/screen-time\""));
    assert!(limit.contains("Mon-Fri 90 min · Sat-Sun 2 h"), "{limit}");
    let phones = section(&html, "phones");
    let anna_row = &phones[phones
        .find(&format!("href=\"/devices/{anna}/time\""))
        .unwrap()..];
    assert!(anna_row[..anna_row.find("</a>").unwrap()].contains("Everyone's rules"));
    let emil_row = &phones[phones
        .find(&format!("href=\"/devices/{emil}/time\""))
        .unwrap()..];
    assert!(emil_row[..emil_row.find("</a>").unwrap()].contains("Own rules"));
    assert!(phones.contains("Emergency calls always work."));
    // No device picker any more.
    assert!(!html.contains("Select a device"));

    // A stored limit that doesn't read says so.
    sqlx::query("UPDATE global_schedule SET daily_budget_json = '[60]' WHERE id = 1")
        .execute(&app.db)
        .await
        .unwrap();
    let html = app.get_page("/schedules", &cookie).await.text();
    assert!(section(&html, "screen-time").contains("Can't be read"));
}

/// L5: an old per-phone bookmark goes to the phone's Time page; anything else in `device` is
/// ignored.
#[tokio::test]
async fn old_per_phone_schedule_urls_redirect_to_the_time_page() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, _) = app.enrolled_device("Anna").await;
    let res = app
        .get_page(&format!("/schedules?device={id}"), &cookie)
        .await;
    assert_redirect(&res, &format!("/devices/{id}/time"), None);
    let res = app.get_page("/schedules?device=anna", &cookie).await;
    assert_eq!(res.status, StatusCode::OK);
    let res = app.get_page("/schedules?device=", &cookie).await;
    assert_eq!(res.status, StatusCode::OK);
}

/// Daily Limit: one form, Save returns to its group with the flash; a refused day renders the page
/// again (400) as typed, the reason under that day, focused; nothing written.
#[tokio::test]
async fn the_daily_limit_saves_in_place_and_refuses_in_place() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let html = app.get_page("/schedules/screen-time", &cookie).await.text();
    assert_eq!(nav_current(&html), "settings");
    assert!(html.contains("<a class=\"nav-back\" href=\"/schedules\">"));
    assert_eq!(
        form_action_of(&html, "b0"),
        Some("/schedules/global#screen-time")
    );
    assert!(!html.contains("data-flash"));

    let res = app
        .request_form(
            Method::POST,
            "/schedules/global",
            Some(&cookie),
            &[("b0", "90"), ("b5", "120")],
        )
        .await;
    assert_redirect(&res, "/schedules/screen-time?saved=1", Some("screen-time"));
    let html = app
        .get_page("/schedules/screen-time?saved=1", &cookie)
        .await
        .text();
    assert!(section(&html, "screen-time").contains("data-flash=\"screen-time\""));
    assert!(html.contains("name=\"b0\" value=\"90\""));

    let res = app
        .request_form(
            Method::POST,
            "/schedules/global",
            Some(&cookie),
            &[("b0", "\"><b>x"), ("b3", "lots")],
        )
        .await;
    assert_eq!(res.status, StatusCode::BAD_REQUEST);
    let html = res.text();
    // The first bad day is refused; what was typed comes back escaped.
    assert!(html.contains("value=\"&#34;&#62;&#60;b&#62;x\""), "{html}");
    assert!(!html.contains("<b>x"));
    let b0 = tags(&html, "input")
        .into_iter()
        .find(|t| t.attr("name") == Some("b0"))
        .unwrap();
    assert!(b0.has("autofocus") && b0.attr("aria-invalid") == Some("true"));
    assert!(html.contains("id=\"b0-error\""));
    assert!(html.contains("value=\"lots\""));
    let stored: String =
        sqlx::query_scalar("SELECT daily_budget_json FROM global_schedule WHERE id = 1")
            .fetch_one(&app.db)
            .await
            .unwrap();
    assert_eq!(stored, "[90,null,null,null,null,120,null]");
}

/// The rule editor is one form (§5.6): Save at the top, the type as an inline select, the calls
/// switch, 7 day rows, the exceptions in a `<details>` inside the form; Delete Rule… is a
/// `<details>` confirm outside it. A phone's own rule goes back to its Time page.
#[tokio::test]
async fn the_rule_editor_is_one_form_with_a_confirm() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("Anna").await;
    app.request(
        Method::POST,
        "/api/devices/status",
        Some(&token),
        Some(json!({
            "lock_reason": "NONE", "kiosk_engaged": true,
            "installed_apps": [
                {"package_name": "org.fossify.calendar", "label": "Kalender"},
                {"package_name": "org.fossify.clock", "label": "Klokke"}
            ]
        })),
    )
    .await;
    let rule = add_rule(&app, None, "Skole", SCHOOL_DAYS).await;
    let html = app
        .get_page(&format!("/schedules/rules/{rule}"), &cookie)
        .await
        .text();
    assert_eq!(nav_current(&html), "settings");
    assert!(html.contains("<a class=\"nav-back\" href=\"/schedules\">"));
    assert!(html.contains("<h1 class=\"page-title\">Skole</h1>"));
    let all = forms(&html);
    let actions: Vec<&str> = all.iter().map(|(a, _, _)| a.as_str()).collect();
    let save = format!("/schedules/rules/{rule}#rule");
    let delete = format!("/schedules/rules/{rule}/delete");
    assert_eq!(actions, [save.as_str(), delete.as_str()]);
    for name in [
        "name",
        "kind",
        "calls_allowed",
        "d0_start",
        "d6_end",
        "exempt_apps",
    ] {
        assert_eq!(form_action_of(&html, name), Some(save.as_str()), "{name}");
    }
    // Save sits at the top of the form, before the fields.
    let form = &html[all[0].1..];
    assert!(form.find("data-save=\"rule\"").unwrap() < form.find("name=\"name\"").unwrap());
    assert!(html.contains("<option value=\"school\" selected>School</option>"));
    assert_eq!(input_checked(&html, "calls_allowed", ""), Some(false));
    assert!(html.contains("name=\"d0_start\" value=\"08:15\""));
    assert!(html.contains("name=\"d0_end\" value=\"14:30\""));
    assert!(html.contains("name=\"d5_start\" value=\"\""));
    // The exceptions: inside the form, in a <details>, the chosen app on.
    let exceptions = section(&html, "exceptions");
    assert!(exceptions.contains("<details class=\"cell disclosure\">"));
    assert!(exceptions.contains("data-value=\"apps\">Kalender<"));
    assert_eq!(
        input_checked(exceptions, "exempt_apps", "org.fossify.calendar"),
        Some(true)
    );
    assert_eq!(
        input_checked(exceptions, "exempt_apps", "org.fossify.clock"),
        Some(false)
    );
    // Delete is behind a confirm, after the rule's form.
    let confirm = section(&html, "delete");
    assert!(confirm.contains("<details class=\"confirm cell\">"));
    assert!(confirm.contains("Delete Rule&hellip;"));
    assert!(confirm.contains(&format!("action=\"{delete}\"")));
    assert!(!html.contains("confirm("));

    // A new rule: the same form, posting to /schedules/rules, no delete.
    let html = app.get_page("/schedules/rules/new", &cookie).await.text();
    assert!(html.contains("<h1 class=\"page-title\">New Rule</h1>"));
    assert_eq!(form_action_of(&html, "name"), Some("/schedules/rules#rule"));
    assert!(!html.contains("/delete"));
    // A phone's own: back to its Time page, its scope posted.
    let html = app
        .get_page(&format!("/schedules/rules/new?device={id}"), &cookie)
        .await
        .text();
    assert!(html.contains(&format!(
        "<a class=\"nav-back\" href=\"/devices/{id}/time\">"
    )));
    assert!(html.contains(&format!("name=\"scope\" value=\"{id}\"")));
    let own = add_rule(&app, Some(id), "Leggetid", SCHOOL_DAYS).await;
    let html = app
        .get_page(&format!("/schedules/rules/{own}"), &cookie)
        .await
        .text();
    assert!(html.contains(&format!(
        "<a class=\"nav-back\" href=\"/devices/{id}/time\">"
    )));
    assert!(section(&html, "delete").contains("Anna's phone is no longer locked by it."));

    // Unknown rule, unknown phone.
    let res = app.get_page("/schedules/rules/9999", &cookie).await;
    assert_eq!(res.status, StatusCode::NOT_FOUND);
    let res = app
        .get_page("/schedules/rules/new?device=9999", &cookie)
        .await;
    assert_eq!(res.status, StatusCode::NOT_FOUND);
    let res = app.get_page("/schedules/rules/new?device=x", &cookie).await;
    assert_eq!(res.status, StatusCode::BAD_REQUEST);
}

/// A refused rule renders its editor again (400) as typed, the reason under the field and that
/// field focused, so the answer lands on it; nothing is written.
#[tokio::test]
async fn a_refused_rule_renders_in_place_as_typed() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let rule = add_rule(&app, None, "Skole", SCHOOL_DAYS).await;
    let res = app
        .request_form(
            Method::POST,
            &format!("/schedules/rules/{rule}"),
            Some(&cookie),
            &[
                ("scope", ""),
                ("name", "Skole <ny>"),
                ("kind", "bedtime"),
                ("calls_allowed", "on"),
                ("exempt_apps", "org.fossify.calendar"),
                ("d0_start", "08:00"),
                ("d0_end", "13:00"),
                ("d3_start", "09:00"),
            ],
        )
        .await;
    assert_eq!(res.status, StatusCode::BAD_REQUEST);
    let html = res.text();
    assert!(html.contains("value=\"Skole &#60;ny&#62;\""));
    assert!(html.contains("<option value=\"bedtime\" selected>Bedtime</option>"));
    assert_eq!(input_checked(&html, "calls_allowed", ""), Some(true));
    assert_eq!(
        input_checked(&html, "exempt_apps", "org.fossify.calendar"),
        Some(true)
    );
    assert!(html.contains("name=\"d0_end\" value=\"13:00\""));
    let d3 = tags(&html, "input")
        .into_iter()
        .find(|t| t.attr("name") == Some("d3_start"))
        .unwrap();
    assert_eq!(d3.attr("value"), Some("09:00"));
    assert!(d3.has("autofocus") && d3.attr("aria-invalid") == Some("true"));
    assert!(html.contains("Thursday: give both times, or leave both empty."));
    // The reason is also in the top bar beside Save, so it is in view wherever the page opens.
    assert!(
        bar(&html).contains("Not saved. Thursday: give both times"),
        "{}",
        bar(&html)
    );
    let name: String = sqlx::query_scalar("SELECT name FROM time_rules WHERE id = ?")
        .bind(rule)
        .fetch_one(&app.db)
        .await
        .unwrap();
    assert_eq!(name, "Skole");

    // No name: the name field. Too many apps: the exceptions open with the reason.
    let res = app
        .request_form(
            Method::POST,
            "/schedules/rules",
            Some(&cookie),
            &[("scope", ""), ("name", " "), ("kind", "school")],
        )
        .await;
    assert_eq!(res.status, StatusCode::BAD_REQUEST);
    let html = res.text();
    let name = tags(&html, "input")
        .into_iter()
        .find(|t| t.attr("name") == Some("name"))
        .unwrap();
    assert!(name.has("autofocus"));
    assert!(html.contains("id=\"name-error\""));
    let apps: Vec<String> = (0..51).map(|i| format!("org.example.app{i}")).collect();
    let mut fields = vec![("scope", ""), ("name", "Mange"), ("kind", "custom")];
    fields.extend(apps.iter().map(|a| ("exempt_apps", a.as_str())));
    let res = app
        .request_form(Method::POST, "/schedules/rules", Some(&cookie), &fields)
        .await;
    assert_eq!(res.status, StatusCode::BAD_REQUEST);
    let html = res.text();
    let exceptions = section(&html, "exceptions");
    assert!(exceptions.contains("<details class=\"cell disclosure\" open>"));
    // In view: in the top bar, and right under the summary, above the 51 app rows (qa M2).
    assert!(bar(&html).contains("Not saved. Pick at most 50 apps."));
    let reason = exceptions.find("id=\"apps-error\"").unwrap();
    assert!(reason > exceptions.find("</summary>").unwrap());
    assert!(reason < exceptions.find("name=\"exempt_apps\"").unwrap());
    let count: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM time_rules")
        .fetch_one(&app.db)
        .await
        .unwrap();
    assert_eq!(count, 1);
}

/// §5.1's table for the time pages, posted from the rendered forms: each lands on its own group
/// (Device › Time's `#extra`, `#pause`, `#lifts`, `#rules`, `#screen-time`; Daily Limit's
/// `#screen-time`), a rule's save on its page's form (`#rule`: Save is at the top), a new rule on
/// its new page, a delete on the list it was in. Each fragment is checked to be the element that
/// holds its form, the action's fragment to be the redirect's, every auto-save to have a
/// `<noscript>` Save. A form the table doesn't know fails.
#[tokio::test]
async fn every_form_on_the_time_pages_returns_to_its_group() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, _) = app.enrolled_device("Anna").await;
    let global = add_rule(&app, None, "Skole", SCHOOL_DAYS).await;
    let own = add_rule(&app, Some(id), "Leggetid", SCHOOL_DAYS).await;
    // A running pause, for End now.
    sqlx::query(
        "INSERT INTO time_lifts (device_id, target, rule_id, rule_name, minutes, expires_at) \
         VALUES (?, 'rule', NULL, NULL, 30, datetime('now', '+30 minutes'))",
    )
    .bind(id)
    .execute(&app.db)
    .await
    .unwrap();
    let time = device_page(id, Section::Time);
    let pages = [
        time.clone(),
        "/schedules/screen-time".to_string(),
        "/schedules/rules/new".to_string(),
        format!("/schedules/rules/new?device={id}"),
        format!("/schedules/rules/{global}"),
        format!("/schedules/rules/{own}"),
    ];
    let mut seen = Vec::new();
    for page in pages {
        let html = app.get_page(&page, &cookie).await.text();
        for (action, start, hidden) in forms(&html) {
            let (target, action_fragment) = match action.split_once('#') {
                Some((t, f)) => (t.to_string(), Some(f.to_string())),
                None => (action.clone(), None),
            };
            let mut sent = hidden.clone();
            let add = |sent: &mut Vec<(String, String)>, k: &str, v: &str| {
                sent.push((k.to_string(), v.to_string()))
            };
            let new_id = || async {
                sqlx::query_scalar::<_, i64>("SELECT MAX(id) FROM time_rules")
                    .fetch_one(&app.db)
                    .await
                    .unwrap()
            };
            let mut expected: (String, Option<&str>) = if target == format!("/devices/{id}/lifts") {
                let back = hidden.iter().any(|(k, v)| k == "back" && v == "time");
                assert!(back, "{action}: back=time");
                if hidden.iter().any(|(k, v)| k == "target" && v == "rule") {
                    add(&mut sent, "rule", "all");
                    add(&mut sent, "minutes", "15");
                    (format!("{time}?done=pause"), Some("pause"))
                } else {
                    (format!("{time}?done=extra"), Some("extra"))
                }
            } else if target.starts_with(&format!("/devices/{id}/lifts/")) {
                (format!("{time}?done=ended"), Some("lifts"))
            } else if target == format!("/schedules/device/{id}") {
                if hidden.contains(&("section".to_string(), "switch".to_string())) {
                    add(&mut sent, "custom_schedule_enabled", "on");
                    (time.clone(), Some("rules"))
                } else {
                    add(&mut sent, "b0", "45");
                    (format!("{time}?saved=budget"), Some("screen-time"))
                }
            } else if target == "/schedules/global" {
                add(&mut sent, "b6", "120");
                (
                    "/schedules/screen-time?saved=1".to_string(),
                    Some("screen-time"),
                )
            } else if target == "/schedules/rules" {
                for (k, v) in [
                    ("name", "Ny"),
                    ("kind", "custom"),
                    ("d1_start", "10:00"),
                    ("d1_end", "11:00"),
                ] {
                    add(&mut sent, k, v);
                }
                (String::new(), Some("rule")) // the new id, after the post
            } else if target.ends_with("/delete") {
                if page.ends_with(&format!("/{own}")) {
                    (format!("{time}?notice=deleted"), Some("own-rules"))
                } else {
                    ("/schedules?notice=deleted".to_string(), Some("rules"))
                }
            } else if target.starts_with("/schedules/rules/") {
                for (k, v) in [
                    ("name", "Saved"),
                    ("kind", "school"),
                    ("d2_start", "08:00"),
                    ("d2_end", "09:00"),
                ] {
                    add(&mut sent, k, v);
                }
                (format!("{target}?saved=1"), Some("rule"))
            } else {
                panic!("{page}: a form the table doesn't know: {action}");
            };
            // A form whose answer is this page lands on the group that holds it (a delete goes to
            // the list it was in, another page).
            let same_page = expected.0.is_empty()
                || expected.0.split('?').next() == Some(page.split('?').next().unwrap());
            if let (Some(fragment), true) = (expected.1, same_page) {
                assert!(
                    encloses(&html, fragment, start),
                    "{page}: {action} lands on #{fragment}, which doesn't hold the form"
                );
            }
            // On its own page the action carries exactly the redirect's fragment, so a refusal
            // rendered in the POST's answer lands where the redirect would (a browser also keeps
            // an action's fragment over a redirect without one); elsewhere it has none.
            if same_page {
                assert_eq!(
                    action_fragment.as_deref(),
                    expected.1,
                    "{page}: {action}: the action's fragment is the redirect's"
                );
            } else {
                assert_eq!(action_fragment, None, "{page}: {action}");
            }
            let body = &html[start..start + html[start..].find("</form>").unwrap()];
            if body.contains("data-autosave") {
                assert!(
                    body.contains("<noscript>"),
                    "{page}: {action} has no <noscript> Save"
                );
            }
            let fields: Vec<(&str, &str)> =
                sent.iter().map(|(k, v)| (k.as_str(), v.as_str())).collect();
            let res = app
                .request_form(Method::POST, &target, Some(&cookie), &fields)
                .await;
            if target == "/schedules/rules" {
                expected.0 = format!("/schedules/rules/{}?saved=added", new_id().await);
            }
            assert_redirect(&res, &expected.0, expected.1);
            app.assert_lands(&res, &cookie).await;
            seen.push(target);
        }
    }
    let lift: i64 = sqlx::query_scalar("SELECT MIN(id) FROM time_lifts")
        .fetch_one(&app.db)
        .await
        .unwrap();
    let d = |r: &str| format!("/devices/{id}/{r}");
    assert_eq!(
        seen,
        [
            d("lifts"),
            d("lifts"),
            d("lifts"),
            d("lifts"),
            d(&format!("lifts/{lift}/end")),
            format!("/schedules/device/{id}"),
            format!("/schedules/device/{id}"),
            "/schedules/global".to_string(),
            "/schedules/rules".to_string(),
            "/schedules/rules".to_string(),
            format!("/schedules/rules/{global}"),
            format!("/schedules/rules/{global}/delete"),
            format!("/schedules/rules/{own}"),
            format!("/schedules/rules/{own}/delete"),
        ],
        "every form on the pages is in the table, in page order"
    );
    // Each did what it says.
    assert_eq!(
        budget_of(&app, id).await,
        ("[45,null,null,null,null,null,null]".to_string(), true)
    );
    let names: Vec<(Option<i64>, String)> =
        sqlx::query_as("SELECT device_id, name FROM time_rules ORDER BY id")
            .fetch_all(&app.db)
            .await
            .unwrap();
    assert_eq!(
        names,
        [(None, "Ny".to_string()), (Some(id), "Ny".to_string())]
    );
}

/// H1 (§5.6): the own-rules switch and the own limits share `/schedules/device/{id}`; a post with
/// a section writes only that section. A switch-only post keeps the limits (an unticked switch
/// sends nothing, and an absent limit used to mean "no limit"); a limits-only post keeps the
/// switch. A post without a section (an older tab) writes both, as before.
#[tokio::test]
async fn a_switch_only_post_keeps_the_limits() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, _) = app.enrolled_device("Anna").await;
    let route = format!("/schedules/device/{id}");
    let post = |fields: Vec<(&'static str, &'static str)>| {
        let (app, cookie, route) = (&app, &cookie, &route);
        async move {
            app.request_form(Method::POST, route, Some(cookie), &fields)
                .await
        }
    };
    let res = post(vec![("section", "budget"), ("b0", "60"), ("b6", "120")]).await;
    assert_redirect(
        &res,
        &format!("/devices/{id}/time?saved=budget"),
        Some("screen-time"),
    );
    assert_eq!(
        budget_of(&app, id).await,
        ("[60,null,null,null,null,null,120]".to_string(), false)
    );

    // The switch on, then off: the limits stay.
    for on in [true, false] {
        let mut fields = vec![("section", "switch")];
        if on {
            fields.push(("custom_schedule_enabled", "on"));
        }
        let res = post(fields).await;
        assert_redirect(&res, &format!("/devices/{id}/time"), Some("rules"));
        assert_eq!(
            budget_of(&app, id).await,
            ("[60,null,null,null,null,null,120]".to_string(), on)
        );
    }
    // The limits while the switch is on: the switch stays on.
    post(vec![
        ("section", "switch"),
        ("custom_schedule_enabled", "on"),
    ])
    .await;
    post(vec![("section", "budget"), ("b2", "30")]).await;
    assert_eq!(
        budget_of(&app, id).await,
        ("[null,null,30,null,null,null,null]".to_string(), true)
    );
    // No section: both, as an older tab expects.
    let res = post(vec![("b1", "15")]).await;
    assert_redirect(&res, &format!("/devices/{id}/time"), None);
    assert_eq!(
        budget_of(&app, id).await,
        ("[null,15,null,null,null,null,null]".to_string(), false)
    );
    // Another section is a 400; a refused limit renders Device › Time (400) as typed, nothing
    // written.
    let res = post(vec![("section", "everything")]).await;
    assert_eq!(res.status, StatusCode::BAD_REQUEST);
    let res = post(vec![("section", "budget"), ("b4", "1441")]).await;
    assert_eq!(res.status, StatusCode::BAD_REQUEST);
    let html = res.text();
    assert!(html.contains("<h1 class=\"page-title\">Time</h1>"));
    let b4 = tags(&html, "input")
        .into_iter()
        .find(|t| t.attr("name") == Some("b4"))
        .unwrap();
    assert_eq!(b4.attr("value"), Some("1441"));
    assert!(b4.has("autofocus"));
    assert!(section(&html, "screen-time").contains("Friday: 0 to 1440 minutes"));
    assert_eq!(
        budget_of(&app, id).await,
        ("[null,15,null,null,null,null,null]".to_string(), false)
    );
    let res = app
        .request_form(
            Method::POST,
            "/schedules/device/9999",
            Some(&cookie),
            &[("section", "switch")],
        )
        .await;
    assert_eq!(res.status, StatusCode::NOT_FOUND);
}

/// Device › Time: the groups in order, the own-rules switch saving itself (with a `<noscript>`
/// Save), the phone's own rules linking to the editor, Pause a rule only with rules to pause,
/// everyone's rules as a row while the switch is off.
#[tokio::test]
async fn device_time_holds_the_moved_card() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, _) = app.enrolled_device("Anna").await;
    let html = app
        .get_page(&device_page(id, Section::Time), &cookie)
        .await
        .text();
    assert_eq!(nav_current(&html), "devices");
    assert!(html.contains(&format!("<a class=\"nav-back\" href=\"/devices/{id}\">")));
    assert!(html.contains("<h1 class=\"page-title\">Time</h1>"));
    let order: Vec<usize> = ["now", "extra", "rules", "own-rules", "screen-time"]
        .iter()
        .map(|g| html.find(&format!("<section id=\"{g}\"")).expect(g))
        .collect();
    assert!(order.windows(2).all(|w| w[0] < w[1]), "{order:?}");
    // Nothing to pause and no lifts yet.
    assert!(!html.contains("<section id=\"pause\""));
    assert!(!html.contains("<section id=\"lifts\""));
    assert!(section(&html, "now").contains("No report yet"));
    assert!(autosaves(&html, "custom_schedule_enabled"));
    assert_eq!(
        input_checked(&html, "custom_schedule_enabled", ""),
        Some(false)
    );
    assert!(section(&html, "rules").contains("<noscript>"));
    assert!(section(&html, "rules").contains("href=\"/schedules\""));
    assert!(
        section(&html, "own-rules").contains(&format!("href=\"/schedules/rules/new?device={id}\""))
    );

    add_rule(&app, None, "Skole", SCHOOL_DAYS).await;
    let own = add_rule(&app, Some(id), "Leggetid", SCHOOL_DAYS).await;
    let html = app
        .get_page(&device_page(id, Section::Time), &cookie)
        .await
        .text();
    let pause = section(&html, "pause");
    assert!(pause.contains(">Skole</option>"), "everyone's rules apply");
    assert!(!pause.contains("Leggetid"));
    assert!(section(&html, "own-rules").contains(&format!("href=\"/schedules/rules/{own}\"")));
    assert!(section(&html, "own-rules").contains("Used once Own rules is on."));
    assert!(section(&html, "rules").contains("1 rule"));

    sqlx::query("UPDATE device_policy SET custom_schedule_enabled = 1 WHERE device_id = ?")
        .bind(id)
        .execute(&app.db)
        .await
        .unwrap();
    let html = app
        .get_page(&device_page(id, Section::Time), &cookie)
        .await
        .text();
    assert!(section(&html, "pause").contains(">Leggetid</option>"));
    assert!(!section(&html, "rules").contains("href=\"/schedules\""));
    assert!(!section(&html, "own-rules").contains("Used once"));

    // A flash from each lift form lands in its own group; an unknown code shows nothing.
    for (code, group) in [("extra", "extra"), ("pause", "pause"), ("gone", "pause")] {
        let html = app
            .get_page(
                &format!("{}?done={code}", device_page(id, Section::Time)),
                &cookie,
            )
            .await
            .text();
        assert!(
            section(&html, group).contains(&format!("data-flash=\"{group}\"")),
            "{code}"
        );
    }
    let html = app
        .get_page(
            &format!("{}?done=%3Cb%3E", device_page(id, Section::Time)),
            &cookie,
        )
        .await
        .text();
    assert!(!html.contains("data-flash"));
}

/// The phone's state on Device › Time comes from its last report, in the Phone Setup zone: the
/// report's age with the exact local time in `title`, the lock as §3.10's badge (an old report says
/// "at last report" and shows no screen time). The end ("until …") shows only once the phone sends
/// its time zone (`time_state.zone`): without it the badge degrades to the rule's name.
#[tokio::test]
async fn device_time_shows_the_phones_state_in_local_time() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    sqlx::query("UPDATE provisioning_settings SET time_zone = 'Asia/Tokyo' WHERE id = 1")
        .execute(&app.db)
        .await
        .unwrap();
    let (id, token) = app.enrolled_device("Anna").await;
    let zone = crate::display_time::Zone::from_setting("Asia/Tokyo");
    let now = chrono::Utc::now();
    let today = zone.today(now).to_string();
    let minute = {
        use chrono::Timelike;
        let local = zone.local(now);
        i64::from(local.hour() * 60 + local.minute())
    };
    // A rule running now on every day, ending in an hour (or tomorrow, past midnight).
    let window = json!({"start": (minute - 60).rem_euclid(1440), "end": (minute + 60) % 1440});
    let rule = add_rule(
        &app,
        None,
        "Skole",
        &serde_json::Value::Array(vec![window; 7]).to_string(),
    )
    .await;
    let state = |zone: Option<&str>, day: &str| {
        json!({
            "day": day, "used_minutes": 42, "budget_minutes": 90, "extra_minutes": 0,
            "active_rule_id": rule, "active_rule_name": "Skole", "calls_blocked": true,
            "lock_reason": "SCHOOL", "lifts_active": [], "zone": zone
        })
    };
    let time = device_page(id, Section::Time);

    // A launcher from before design 34 P13 sends no zone: the rule's name, no end.
    report(&app, &token, state(None, &today)).await;
    let html = app.get_page(&time, &cookie).await.text();
    let now_group = section(&html, "now");
    assert!(
        now_group.contains("<span class=\"badge badge-lock\">Skole</span>"),
        "{now_group}"
    );
    assert!(!now_group.contains("until"));
    assert!(now_group.contains(">42 of 90 min today<"));

    // Once a phone in the Phone Setup zone says so, the end shows.
    report(&app, &token, state(Some("Asia/Tokyo"), &today)).await;
    let html = app.get_page(&time, &cookie).await.text();
    assert!(section(&html, "now").contains("Skole until "), "{html}");

    // An old report (yesterday 10:00 UTC): its age, the exact local time in `title` - never the
    // UTC clock -, "at last report", no screen time.
    let yesterday = (now - chrono::Duration::days(1)).date_naive();
    let reported = yesterday.and_hms_opt(10, 0, 0).unwrap().and_utc();
    let phone_day = zone.local(reported).date_naive().to_string();
    report(&app, &token, state(Some("Asia/Tokyo"), &phone_day)).await;
    sqlx::query("UPDATE device_status SET reported_at = ? WHERE device_id = ?")
        .bind(reported.format("%Y-%m-%d %H:%M:%S").to_string())
        .bind(id)
        .execute(&app.db)
        .await
        .unwrap();
    sqlx::query("UPDATE devices SET last_seen_at = ? WHERE id = ?")
        .bind(reported.format("%Y-%m-%d %H:%M:%S").to_string())
        .bind(id)
        .execute(&app.db)
        .await
        .unwrap();
    let html = app.get_page(&time, &cookie).await.text();
    let now_group = section(&html, "now");
    assert!(
        now_group.contains(&format!("title=\"{}\"", zone.exact(reported))),
        "{now_group}"
    );
    assert!(now_group.contains("19:00:00 JST"));
    assert!(now_group.contains(&format!(">{}<", zone.relative(reported, now))));
    assert!(now_group.contains("Skole · at last report"), "{now_group}");
    assert!(!now_group.contains("until"));
    assert!(!now_group.contains("data-row=\"screen-time\""));
}

/// Device › Time fails loudly: an unknown phone is a 404, an unreadable part a 500 - never an
/// empty list that reads as "no rules". A phone not set up has the page (its rules reach it with
/// its first settings).
#[tokio::test]
async fn device_time_fails_loudly_and_works_before_setup() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let res = app
        .get_page(&device_page(9999, Section::Time), &cookie)
        .await;
    assert_eq!(res.status, StatusCode::NOT_FOUND);
    let (fresh, _) = app.create_device("New").await;
    let res = app
        .get_page(&device_page(fresh, Section::Time), &cookie)
        .await;
    assert_eq!(res.status, StatusCode::OK);
    assert!(res.text().contains("No report yet"));
    for table in ["time_lifts", "time_rules"] {
        let app = TestApp::new().await;
        let cookie = app.admin_cookie().await;
        let (id, _) = app.enrolled_device("Anna").await;
        sqlx::query(&format!("ALTER TABLE {table} RENAME TO gone"))
            .execute(&app.db)
            .await
            .unwrap();
        let res = app.get_page(&device_page(id, Section::Time), &cookie).await;
        assert_eq!(res.status, StatusCode::INTERNAL_SERVER_ERROR, "{table}");
        let res = app.get_page("/schedules", &cookie).await;
        if table == "time_rules" {
            assert_eq!(res.status, StatusCode::INTERNAL_SERVER_ERROR);
        }
    }
}

/// The summary: the Time card is a row now ("Time · Everyone's rules" / "Own rules"); its lifts
/// are the quick actions (design 31 step 10), never the rules' own forms.
#[tokio::test]
async fn the_device_page_has_a_time_row() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, _) = app.enrolled_device("Anna").await;
    add_rule(&app, None, "Skole", SCHOOL_DAYS).await;
    let html = app
        .get_page(&device_page(id, Section::Summary), &cookie)
        .await
        .text();
    let row = &html[html.find("data-row=\"time\"").unwrap()..];
    let row = &row[..row.find("</a>").unwrap()];
    assert!(html.contains(&format!("href=\"/devices/{id}/time\" data-row=\"time\"")));
    assert!(row.contains(">Everyone&#39;s rules<"), "{row}");
    assert!(!html.contains("id=\"time\""));
    assert!(!html.contains("/schedules/device/"));
    assert!(!html.contains("/schedules?device="));
    sqlx::query("UPDATE device_policy SET custom_schedule_enabled = 1 WHERE device_id = ?")
        .bind(id)
        .execute(&app.db)
        .await
        .unwrap();
    let html = app
        .get_page(&device_page(id, Section::Summary), &cookie)
        .await
        .text();
    let row = &html[html.find("data-row=\"time\"").unwrap()..];
    assert!(row[..row.find("</a>").unwrap()].contains(">Own rules<"));
}

/// The editor's Save stays in reach (a sticky top bar) and a save's flash shows in that bar, so it
/// is in view wherever the page returns (qa-31-s9-code L1); a phone's own rule saved while the
/// phone follows everyone's says it isn't in force yet (N2).
#[tokio::test]
async fn the_editor_bar_holds_save_and_its_flash() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, _) = app.enrolled_device("Anna").await;
    let css = std::fs::read_to_string("static/style.css").unwrap();
    let rule_css = &css[css.find(".editor-bar {").expect("the bar's rule")..];
    assert!(rule_css[..rule_css.find('}').unwrap()].contains("position: sticky"));
    let global = add_rule(&app, None, "Skole", SCHOOL_DAYS).await;
    let html = app
        .get_page(&format!("/schedules/rules/{global}?saved=1"), &cookie)
        .await
        .text();
    let top = bar(&html);
    assert!(top.contains("data-save=\"rule\""));
    assert!(top.contains("data-flash=\"rule\""));
    assert!(top.contains("The phones get it within seconds when online."));
    assert_eq!(
        html.matches("data-flash=").count(),
        1,
        "the flash only in the bar"
    );
    let own = add_rule(&app, Some(id), "Leggetid", SCHOOL_DAYS).await;
    let html = app
        .get_page(&format!("/schedules/rules/{own}?saved=1"), &cookie)
        .await
        .text();
    assert!(bar(&html).contains("Saved. Used once Own rules is on."));
    sqlx::query("UPDATE device_policy SET custom_schedule_enabled = 1 WHERE device_id = ?")
        .bind(id)
        .execute(&app.db)
        .await
        .unwrap();
    let html = app
        .get_page(&format!("/schedules/rules/{own}?saved=added"), &cookie)
        .await
        .text();
    assert!(bar(&html).contains("Rule added. The phones get it within seconds when online."));
}

/// An unreadable stored rule names what didn't read - it shows empty and a save writes it as
/// shown - also on a refused save of it (qa-31-s9-code L3); the device page's Time row says
/// "Needs attention" while a rule in force doesn't read (N5).
#[tokio::test]
async fn an_unreadable_rule_says_what_will_be_reset() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, _) = app.enrolled_device("Anna").await;
    let rule: i64 = sqlx::query_scalar(
        "INSERT INTO time_rules (name, kind, calls_allowed, exempt_apps_json, days_json) \
         VALUES ('Skole', 'school', 0, 'nope', '[1,2]') RETURNING id",
    )
    .fetch_one(&app.db)
    .await
    .unwrap();
    let html = app
        .get_page(&format!("/schedules/rules/{rule}"), &cookie)
        .await
        .text();
    assert!(html.contains("data-warning=\"rule-corrupt\""));
    assert!(
        html.contains("Its days and apps couldn&#39;t be read"),
        "{html}"
    );
    let res = app
        .request_form(
            Method::POST,
            &format!("/schedules/rules/{rule}"),
            Some(&cookie),
            &[("scope", ""), ("name", ""), ("kind", "school")],
        )
        .await;
    assert_eq!(res.status, StatusCode::BAD_REQUEST);
    assert!(
        res.text()
            .contains("Its days and apps couldn&#39;t be read")
    );
    let summary = app
        .get_page(&device_page(id, Section::Summary), &cookie)
        .await
        .text();
    let row = &summary[summary.find("data-row=\"time\"").unwrap()..];
    assert!(row[..row.find("</a>").unwrap()].contains(">Needs attention<"));
    // Saved again, it reads; the warning and the row's alarm go.
    let res = app
        .request_form(
            Method::POST,
            &format!("/schedules/rules/{rule}"),
            Some(&cookie),
            &[("scope", ""), ("name", "Skole"), ("kind", "school")],
        )
        .await;
    assert_redirect(
        &res,
        &format!("/schedules/rules/{rule}?saved=1"),
        Some("rule"),
    );
    let html = app
        .get_page(&format!("/schedules/rules/{rule}"), &cookie)
        .await
        .text();
    assert!(!html.contains("data-warning=\"rule-corrupt\""));
    let summary = app
        .get_page(&device_page(id, Section::Summary), &cookie)
        .await
        .text();
    let row = &summary[summary.find("data-row=\"time\"").unwrap()..];
    assert!(row[..row.find("</a>").unwrap()].contains(">Everyone&#39;s rules<"));
}

/// The Time row says "Couldn't read" when the rules can't be read - the summary's only sign of it
/// (qa-31-s9-code L4).
#[tokio::test]
async fn the_time_row_says_when_the_rules_cant_be_read() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, _) = app.enrolled_device("Anna").await;
    sqlx::query("ALTER TABLE time_rules RENAME TO gone")
        .execute(&app.db)
        .await
        .unwrap();
    let res = app
        .get_page(&device_page(id, Section::Summary), &cookie)
        .await;
    assert_eq!(res.status, StatusCode::OK);
    let html = res.text();
    let row = &html[html.find("data-row=\"time\"").unwrap()..];
    assert!(
        row[..row.find("</a>").unwrap()].contains(">Couldn&#39;t read<"),
        "{row}"
    );
}

/// Design 66 §3.3: the rule editor never handles a phone's bedtime row. Its old link goes to the
/// Bedtime page (303), a save or delete through the editor is a 404 with nothing written, and
/// neither list of rules shows it.
#[tokio::test]
async fn the_bedtime_row_is_never_a_day_rule() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, _) = app.enrolled_device("Anna").await;
    sqlx::query("UPDATE device_policy SET custom_schedule_enabled = 1 WHERE device_id = ?")
        .bind(id)
        .execute(&app.db)
        .await
        .unwrap();
    let bedtime: i64 = sqlx::query_scalar(
        "INSERT INTO time_rules (device_id, name, kind, calls_allowed, days_json, bedtime) \
         VALUES (?, 'Bedtime', 'bedtime', 1, ?, 1) RETURNING id",
    )
    .bind(id)
    .bind(r#"[{"start":1260,"end":420},{"start":1260,"end":420},{"start":1260,"end":420},{"start":1260,"end":420},{"start":1350,"end":540},{"start":1350,"end":540},{"start":1260,"end":420}]"#)
    .fetch_one(&app.db)
    .await
    .unwrap();
    let stored = || async {
        sqlx::query_as::<_, (String, String)>("SELECT name, days_json FROM time_rules WHERE id = ?")
            .bind(bedtime)
            .fetch_one(&app.db)
            .await
            .unwrap()
    };
    let before = stored().await;

    let res = app
        .get_page(&format!("/schedules/rules/{bedtime}"), &cookie)
        .await;
    assert_eq!(res.status, StatusCode::SEE_OTHER);
    assert_eq!(
        res.location(),
        Some(format!("/devices/{id}/bedtime").as_str())
    );
    let scope = id.to_string();
    for (path, fields) in [
        (
            format!("/schedules/rules/{bedtime}"),
            vec![
                ("scope", scope.as_str()),
                ("name", "Dag"),
                ("kind", "custom"),
                ("d0_start", "08:00"),
                ("d0_end", "09:00"),
            ],
        ),
        (
            format!("/schedules/rules/{bedtime}/delete"),
            vec![("scope", scope.as_str())],
        ),
    ] {
        let res = app
            .request_form(Method::POST, &path, Some(&cookie), &fields)
            .await;
        assert_eq!(res.status, StatusCode::NOT_FOUND, "{path}");
    }
    assert_eq!(stored().await, before);
    for page in ["/schedules".to_string(), device_page(id, Section::Time)] {
        let html = app.get_page(&page, &cookie).await.text();
        assert!(
            !html.contains(&format!("href=\"/schedules/rules/{bedtime}\"")),
            "{page} lists the bedtime as a rule"
        );
    }
}
