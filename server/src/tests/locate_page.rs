//! The Locate tab (design 31 step 13, §3.6): the phone switcher as links, the fix as age and
//! accuracy in local time, the actions and Erase Phone…'s confirm, Location Settings, Recent with
//! labels instead of command ids, and §5.1's redirect table for every form on the page.

use axum::http::{Method, StatusCode};

use super::{TestApp, assert_redirect, autosaves, tags};

/// The Recent row of the newest `command` (its stored id is the row's `data-command` hook).
pub fn recent_row<'a>(page: &'a str, command: &str) -> &'a str {
    page.split(&format!("data-command=\"{command}\""))
        .nth(1)
        .and_then(|rest| rest.split("</div>").next())
        .unwrap_or_else(|| panic!("no Recent row for {command}"))
}

async fn locate_page(app: &TestApp, cookie: &str, query: &str) -> String {
    let res = app
        .get_page(&format!("/devices/locate{query}"), cookie)
        .await;
    assert_eq!(res.status, StatusCode::OK, "{query}: {}", res.text());
    res.text()
}

/// A command asked for at `requested` (an SQL time expression), not delivered yet.
async fn waiting(app: &TestApp, device: i64, command: &str, requested: &str) -> i64 {
    sqlx::query_scalar(&format!(
        "INSERT INTO device_commands (device_id, command, requested_at) VALUES (?, ?, {requested}) \
         RETURNING id"
    ))
    .bind(device)
    .bind(command)
    .fetch_one(&app.db)
    .await
    .unwrap()
}

async fn commands(app: &TestApp, device: i64) -> Vec<String> {
    sqlx::query_scalar("SELECT command FROM device_commands WHERE device_id = ? ORDER BY id")
        .bind(device)
        .fetch_all(&app.db)
        .await
        .unwrap()
}

/// Every form's action and hidden fields, in page order.
fn forms(html: &str) -> Vec<(String, Vec<(String, String)>)> {
    tags(html, "form")
        .iter()
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
            (form.attr("action").unwrap_or("").to_string(), hidden)
        })
        .collect()
}

/// qa-31-s12-code L4: every action's fragment names an element of the page, and every form that
/// saves itself has a `<noscript>` Save.
fn check_forms(html: &str) {
    for form in tags(html, "form") {
        let body = &html[form.start..];
        let body = &body[..body.find("</form>").unwrap_or(body.len())];
        let action = form.attr("action").unwrap_or("");
        if let Some((_, fragment)) = action.split_once('#') {
            assert!(
                html.contains(&format!("id=\"{fragment}\"")),
                "{action} lands on no element"
            );
        }
        if body.contains("data-autosave") {
            let noscript = body
                .split_once("<noscript>")
                .and_then(|(_, rest)| rest.split_once("</noscript>"))
                .map(|(inside, _)| inside);
            assert!(
                noscript.is_some_and(|inside| inside.contains("type=\"submit\"")),
                "{action} saves itself but has no <noscript> Save"
            );
        }
    }
}

/// §5.1's redirect table for the Locate tab, built from the rendered forms: each lands back on
/// the page - Update Location at the map (`#location`, where the wait shows), Ring, Stop Ringing,
/// Lock and Erase at their group (`#actions`, Recent right under it), Cancel at Recent where its
/// line is, the two pickers with no fragment (scroll-restore keeps the place). A form the table
/// has no row for fails the test.
#[tokio::test]
async fn every_form_on_the_locate_page_returns_to_its_group() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, _) = app.enrolled_device("Anna").await;
    app.enrolled_device("Emil").await;
    sqlx::query("UPDATE device_policy SET location_mode = 'interval' WHERE device_id = ?")
        .bind(id)
        .execute(&app.db)
        .await
        .unwrap();
    let lock = waiting(&app, id, "lock", "datetime('now', '-1 minutes')").await;
    let html = locate_page(&app, &cookie, &format!("?device={id}")).await;
    check_forms(&html);
    let page = format!("/devices/locate?device={id}");
    // (action as rendered, the fields a parent sends, the expected Location).
    type Row<'a> = (String, &'a [(&'a str, &'a str)], (String, Option<&'a str>));
    let table: [Row; 8] = [
        (
            format!("/devices/{id}/command/locate"),
            &[],
            (format!("{page}&requested=1"), Some("location")),
        ),
        (
            format!("/devices/{id}/command/ring"),
            &[],
            (page.clone(), Some("actions")),
        ),
        (
            format!("/devices/{id}/command/stop-ring"),
            &[],
            (page.clone(), Some("actions")),
        ),
        (
            format!("/devices/{id}/command/lock"),
            &[],
            (page.clone(), Some("actions")),
        ),
        (
            format!("/devices/{id}/command/wipe#erase"),
            &[("confirm_name", "Anna")],
            (page.clone(), Some("actions")),
        ),
        (
            format!("/devices/{id}/commands/{lock}/cancel"),
            &[],
            (format!("{page}&cancel=done"), Some("recent")),
        ),
        (
            format!("/devices/{id}/location-policy"),
            &[("mode", "interval"), ("interval_minutes", "60")],
            (page.clone(), None),
        ),
        (
            format!("/devices/{id}/location-retention"),
            &[("days", "14")],
            (page.clone(), None),
        ),
    ];
    let found = forms(&html);
    assert_eq!(
        found.iter().map(|(a, _)| a.as_str()).collect::<Vec<_>>(),
        table.iter().map(|(a, _, _)| a.as_str()).collect::<Vec<_>>(),
        "every form on the Locate page is in the table, in page order"
    );
    for ((action, hidden), (_, fields, (path, fragment))) in found.iter().zip(table.iter()) {
        let mut sent: Vec<(&str, &str)> = hidden
            .iter()
            .map(|(k, v)| (k.as_str(), v.as_str()))
            .collect();
        sent.extend_from_slice(fields);
        // A browser drops the fragment from the request.
        let target = action.split('#').next().unwrap();
        let res = app
            .request_form(Method::POST, target, Some(&cookie), &sent)
            .await;
        assert_redirect(&res, path, *fragment);
    }
    assert_eq!(
        commands(&app, id).await,
        ["lock", "locate", "ring", "stop_ring", "lock", "wipe"],
        "each action queued its command (the ring replaced by its stop, kept as history)"
    );
    // The two pickers saved.
    let (interval, days): (i64, i64) = sqlx::query_as(
        "SELECT location_interval_minutes, location_retention_days FROM device_policy \
         WHERE device_id = ?",
    )
    .bind(id)
    .fetch_one(&app.db)
    .await
    .unwrap();
    assert_eq!((interval, days), (60, 14));
}

/// A name that doesn't match erases nothing: the page comes back in the POST's answer with the
/// confirm open and the refusal in it, where the form's `#erase` lands.
#[tokio::test]
async fn a_wrong_name_opens_the_erase_confirm_with_the_refusal() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, _) = app.enrolled_device("Anna").await;
    let html = locate_page(&app, &cookie, &format!("?device={id}")).await;
    let confirm = html
        .split("<details class=\"confirm cell\" id=\"erase\"")
        .nth(1)
        .and_then(|rest| rest.split("</details>").next())
        .expect("Erase Phone… is a <details> confirm");
    assert!(confirm.starts_with('>'), "closed until asked");
    let summary_end = confirm.find("</summary>").unwrap();
    assert!(
        confirm.find("<form").is_some_and(|f| f > summary_end),
        "the form is inside the confirm, after its summary"
    );
    assert!(confirm.contains("Erase Anna's phone</button>"));

    for name in ["anna", "", "Ann"] {
        let res = app
            .request_form(
                Method::POST,
                &format!("/devices/{id}/command/wipe"),
                Some(&cookie),
                &[("confirm_name", name)],
            )
            .await;
        assert_eq!(res.status, StatusCode::OK, "{name:?}");
        let page = res.text();
        assert!(
            page.contains("<details class=\"confirm cell\" id=\"erase\" open>"),
            "{name:?}"
        );
        assert!(page.contains("data-flash=\"erase\""));
        assert!(page.contains("aria-invalid=\"true\""));
    }
    assert!(commands(&app, id).await.is_empty(), "nothing erased");
}

/// The phone switcher (§3.6, §5.4): links, the state in each `aria-label`, the chosen one marked;
/// with several phones and none chosen the page lists them; one phone is shown at once.
#[tokio::test]
async fn the_phone_switcher_and_the_list() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let html = locate_page(&app, &cookie, "").await;
    assert!(html.contains("No phones yet."));
    assert!(!html.contains("id=\"map\""));

    let (anna, _) = app.enrolled_device("Anna").await;
    // One phone: shown at once, the switcher holds it.
    let html = locate_page(&app, &cookie, "").await;
    assert!(html.contains("id=\"map\""));
    assert!(html.contains(&format!("/devices/{anna}/command/ring")));

    let (emil, _) = app.create_device("<b>Emil</b>").await;
    // Two phones, none chosen (or one that doesn't exist): the list, no map, no actions.
    for query in ["", "?device=999", "?device=x"] {
        let html = locate_page(&app, &cookie, query).await;
        assert!(!html.contains("id=\"map\""), "{query}");
        assert!(!html.contains("/command/"), "{query}");
        for id in [anna, emil] {
            assert!(
                html.contains(&format!("href=\"/devices/locate?device={id}\"")),
                "{query}"
            );
        }
        assert!(html.contains("Not seen yet"));
        assert!(html.contains("Not set up"));
    }

    let html = locate_page(&app, &cookie, &format!("?device={anna}")).await;
    let switch = html
        .split("<nav class=\"phone-switch\"")
        .nth(1)
        .and_then(|rest| rest.split("</nav>").next())
        .expect("the switcher");
    let links = tags(switch, "a");
    assert_eq!(links.len(), 2);
    let anna_link = links
        .iter()
        .find(|a| a.attr("href") == Some(&format!("/devices/locate?device={anna}")))
        .unwrap();
    assert_eq!(anna_link.attr("aria-label"), Some("Anna, not seen yet"));
    assert_eq!(anna_link.attr("aria-current"), Some("true"));
    let emil_link = links
        .iter()
        .find(|a| a.attr("href") == Some(&format!("/devices/locate?device={emil}")))
        .unwrap();
    assert_eq!(
        emil_link.attr("aria-label"),
        Some("&#60;b&#62;Emil&#60;/b&#62;, not set up")
    );
    assert!(!emil_link.has("aria-current"));
    assert!(!html.contains("<b>Emil</b>"), "names are escaped");
    // No inline script picks the phone (the old select needed JS).
    assert!(!html.contains("onchange"));
}

/// The fix as age and accuracy, in the Phone Setup zone, the exact time in `title`; the phone's
/// answer to Update Location in plain words with when it came; the map's popup in the same words.
#[tokio::test]
async fn the_fix_and_the_answer_read_in_local_time() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    sqlx::query("UPDATE provisioning_settings SET time_zone = 'Asia/Tokyo' WHERE id = 1")
        .execute(&app.db)
        .await
        .unwrap();
    let (id, _) = app.enrolled_device("Anna").await;
    let html = locate_page(&app, &cookie, &format!("?device={id}")).await;
    assert!(html.contains("No location reported yet."));

    let ten_min_ago = chrono::Utc::now() - chrono::Duration::minutes(10);
    sqlx::query(
        "INSERT INTO device_locations (device_id, latitude, longitude, accuracy_meters, captured_at) \
         VALUES (?, 59.9, 10.7, 39.6, ?)",
    )
    .bind(id)
    .bind(ten_min_ago.format("%Y-%m-%dT%H:%M:%S%.3fZ").to_string())
    .execute(&app.db)
    .await
    .unwrap();
    let locate = sqlx::query_scalar::<_, i64>(
        "INSERT INTO device_commands (device_id, command, delivered_at, acknowledged_at, result, \
         succeeded) VALUES (?, 'locate', datetime('now', '-2 minutes'), datetime('now', '-1 minutes'), \
         'location is off for this device', 0) RETURNING id",
    )
    .bind(id)
    .fetch_one(&app.db)
    .await
    .unwrap();
    let html = locate_page(&app, &cookie, &format!("?device={id}")).await;
    let fix = html
        .split("data-row=\"fix\"")
        .nth(1)
        .and_then(|rest| rest.split("</div>").next())
        .unwrap();
    assert!(fix.contains(">10 min ago<"), "{fix}");
    assert!(fix.contains("Within 40 m"), "{fix}");
    assert!(fix.contains(" JST\""), "the exact time in title: {fix}");
    assert!(html.contains("var shownText = \"10 min ago · within 40 m\";"));
    let answer = html
        .split("data-row=\"locate-answer\"")
        .nth(1)
        .and_then(|rest| rest.split("</div>").next())
        .unwrap();
    assert!(
        answer.contains("title=\"location is off for this device\">Location is off on the phone<"),
        "{answer}"
    );
    // The row in Recent says the same, with its word.
    let row = recent_row(&html, "locate");
    assert!(row.contains("Update Location"), "{row}");
    assert!(row.contains("Failed"), "{row}");
    assert!(row.contains("Location is off on the phone"), "{row}");
    assert!(!html.contains("UTC"), "local times, no UTC");
    let json = app
        .get_page(&format!("/devices/{id}/locate-result.json"), &cookie)
        .await
        .json();
    assert_eq!(json["id"], locate);
    assert_eq!(json["text"], "Location is off on the phone");

    // A fix days old says its date; the fallback zone says UTC.
    sqlx::query("UPDATE device_locations SET captured_at = '2026-01-02T10:00:00.000Z'")
        .execute(&app.db)
        .await
        .unwrap();
    sqlx::query("UPDATE provisioning_settings SET time_zone = '' WHERE id = 1")
        .execute(&app.db)
        .await
        .unwrap();
    let html = locate_page(&app, &cookie, &format!("?device={id}")).await;
    assert!(html.contains(">2 Jan"), "{html}");
}

/// Recent: labels for every command, never its id; times local; Cancel only while it waits; the
/// line after a Cancel in the group.
#[tokio::test]
async fn recent_names_each_command_and_cancels_what_waits() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, _) = app.enrolled_device("Anna").await;
    for command in [
        "end_windows",
        "start_pause",
        "allow_blocked",
        "stop_ring",
        "wipe",
    ] {
        waiting(&app, id, command, "datetime('now', '-2 minutes')").await;
    }
    let html = locate_page(&app, &cookie, &format!("?device={id}")).await;
    for (command, label) in [
        ("end_windows", "Lock again"),
        ("start_pause", "Unlock for 2 h"),
        ("allow_blocked", "Allow these apps"),
        ("stop_ring", "Stop Ringing"),
        ("wipe", "Erase Phone"),
    ] {
        let row = recent_row(&html, command);
        assert!(row.contains(&format!(">{label}<")), "{command}: {row}");
        assert!(row.contains("Waiting"), "{command}: {row}");
        assert!(row.contains(">Cancel</button>"), "{command}: {row}");
    }
    for raw in ["end_windows", "start_pause", "allow_blocked", "stop_ring"] {
        assert!(!html.contains(&format!(">{raw}")), "{raw} shown");
    }
    assert!(!html.contains("UTC"));
    assert!(html.contains("aria-label=\"Cancel Erase Phone asked for at "));

    let html = locate_page(&app, &cookie, &format!("?device={id}&cancel=done")).await;
    let recent = html.split("id=\"recent\"").nth(1).unwrap();
    assert!(recent.contains("data-flash=\"recent\">Cancelled. The phone will never get it."));
    let html = locate_page(&app, &cookie, &format!("?device={id}&cancel=bogus")).await;
    assert!(!html.contains("data-flash=\"recent\""));
}

/// The two warnings are orange rows above the actions; the mode picker saves its form.
#[tokio::test]
async fn warnings_and_location_settings() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, _) = app.enrolled_device("Anna").await;
    let html = locate_page(&app, &cookie, &format!("?device={id}")).await;
    assert!(!html.contains("data-warning"));
    assert!(autosaves(&html, "mode"));
    assert!(autosaves(&html, "days"));
    assert!(
        !html.contains("name=\"interval_minutes\""),
        "Every only for Regularly"
    );
    sqlx::query("UPDATE device_policy SET location_mode = 'off' WHERE device_id = ?")
        .bind(id)
        .execute(&app.db)
        .await
        .unwrap();
    let html = locate_page(&app, &cookie, &format!("?device={id}")).await;
    let warning = html.find("data-warning=\"location-off\"").unwrap();
    assert!(warning < html.find("id=\"actions\"").unwrap());
    assert!(html.contains("<option value=\"off\" selected>Off</option>"));
    sqlx::query("UPDATE device_policy SET location_mode = 'interval' WHERE device_id = ?")
        .bind(id)
        .execute(&app.db)
        .await
        .unwrap();
    let html = locate_page(&app, &cookie, &format!("?device={id}")).await;
    assert!(autosaves(&html, "interval_minutes"));
    assert!(html.contains("<option value=\"30\" selected>30 min</option>"));
    assert!(html.contains("<option value=\"60\">1 hour</option>"));
}

/// The actions are for an existing phone only: an unknown one is a 404 and nothing is queued; a
/// read that fails is a 500, never an empty Recent or a made-up setting.
#[tokio::test]
async fn unknown_phones_and_failed_reads() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, _) = app.enrolled_device("Anna").await;
    for path in ["ring", "stop-ring", "lock", "locate", "wipe"] {
        let res = app
            .request_form(
                Method::POST,
                &format!("/devices/999/command/{path}"),
                Some(&cookie),
                &[("confirm_name", "Anna")],
            )
            .await;
        assert_eq!(res.status, StatusCode::NOT_FOUND, "{path}");
    }
    let total: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM device_commands")
        .fetch_one(&app.db)
        .await
        .unwrap();
    assert_eq!(total, 0);

    sqlx::query("ALTER TABLE device_locations RENAME TO device_locations_gone")
        .execute(&app.db)
        .await
        .unwrap();
    let res = app
        .get_page(&format!("/devices/locate?device={id}"), &cookie)
        .await;
    assert_eq!(res.status, StatusCode::INTERNAL_SERVER_ERROR);
    let res = app
        .get_page(&format!("/devices/{id}/locations.json"), &cookie)
        .await;
    assert_eq!(res.status, StatusCode::INTERNAL_SERVER_ERROR);
}
