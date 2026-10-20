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

/// qa-31-s12-code L4 and the shared rule (`settings_pages::check_forms`): every action lands on
/// its own group, and every form that saves itself has a `<noscript>` Save.
#[track_caller]
fn check_forms(html: &str) {
    super::settings_pages::check_forms("/devices/locate", html);
}

/// §5.1's redirect table for the Locate tab, built from the rendered forms: each lands back on
/// the page - Update Location at the map (`#location`, where the wait shows), Ring, Stop Ringing,
/// Lock and Erase at their group (`#actions`, Recent right under it), Cancel at Recent where its
/// line is, the two pickers at Location Settings (the last group of a long page: a `<noscript>`
/// Save, or a save from a refused erase's page, must not land at the top - qa-31-s13-code M1).
/// Every form has a fragment, so none lands at the top. A form the table has no row for fails.
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
            format!("/devices/{id}/command/locate#location"),
            &[],
            (format!("{page}&requested=1"), Some("location")),
        ),
        (
            format!("/devices/{id}/command/ring#actions"),
            &[],
            (page.clone(), Some("actions")),
        ),
        (
            format!("/devices/{id}/command/stop-ring#actions"),
            &[],
            (page.clone(), Some("actions")),
        ),
        (
            format!("/devices/{id}/command/lock#actions"),
            &[],
            (page.clone(), Some("actions")),
        ),
        (
            format!("/devices/{id}/command/wipe#erase"),
            &[("confirm_name", "Anna")],
            (page.clone(), Some("actions")),
        ),
        (
            format!("/devices/{id}/commands/{lock}/cancel#recent"),
            &[],
            (format!("{page}&cancel=done"), Some("recent")),
        ),
        (
            format!("/devices/{id}/location-policy#location-settings"),
            &[("mode", "interval"), ("interval_minutes", "60")],
            (page.clone(), Some("location-settings")),
        ),
        (
            format!("/devices/{id}/location-retention#location-settings"),
            &[("days", "14")],
            (page.clone(), Some("location-settings")),
        ),
    ];
    let found = forms(&html);
    assert!(
        table.iter().all(|(_, _, (_, fragment))| fragment.is_some()),
        "every form lands on its group, also without JS"
    );
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
        app.assert_lands(&res, &cookie).await;
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
    assert!(html.contains("data-shown-text=\"10 min ago · within 40 m\""));
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
    for command in ["stop_ring", "wipe"] {
        waiting(&app, id, command, "datetime('now', '-2 minutes')").await;
    }
    let html = locate_page(&app, &cookie, &format!("?device={id}")).await;
    for (command, label) in [("stop_ring", "Stop Ringing"), ("wipe", "Erase Phone")] {
        let row = recent_row(&html, command);
        assert!(row.contains(&format!(">{label}<")), "{command}: {row}");
        assert!(row.contains("Waiting"), "{command}: {row}");
        assert!(row.contains(">Cancel</button>"), "{command}: {row}");
    }
    assert!(!html.contains(">stop_ring"), "the id shown");
    assert!(!html.contains("UTC"));
    assert!(html.contains("aria-label=\"Cancel Erase Phone asked for at "));

    let html = locate_page(&app, &cookie, &format!("?device={id}&cancel=done")).await;
    let recent = html.split("id=\"recent\"").nth(1).unwrap();
    assert!(recent.contains("data-flash=\"recent\">Cancelled. The phone will never get it."));
    let html = locate_page(&app, &cookie, &format!("?device={id}&cancel=bogus")).await;
    assert!(!html.contains("data-flash=\"recent\""));

    // An erase the phone answered while the system went down reads as a confirmed one
    // (qa-31-s13-code N2).
    sqlx::query(
        "UPDATE device_commands SET delivered_at = datetime('now'), received_at = datetime('now'), \
         acknowledged_at = datetime('now'), succeeded = 1, \
         result = 'wiping (the system shut down during the call)' WHERE command = 'wipe'",
    )
    .execute(&app.db)
    .await
    .unwrap();
    let html = locate_page(&app, &cookie, &format!("?device={id}")).await;
    let row = recent_row(&html, "wipe");
    assert!(row.contains(">Erasing<"), "{row}");
    assert!(row.contains("An erased phone stops reporting."), "{row}");
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
    sqlx::query("ALTER TABLE device_commands RENAME TO device_commands_gone")
        .execute(&app.db)
        .await
        .unwrap();
    let res = app
        .get_page(&format!("/devices/{id}/locate-result.json"), &cookie)
        .await;
    assert_eq!(res.status, StatusCode::INTERNAL_SERVER_ERROR);
}

/// Leaflet's `Icon.Default` prepends `imagePath` to its icon file names: an `iconUrl` of our own
/// was prefixed twice (`/static/leaflet/images//static/...`, a 404 and the marker's alt text).
#[test]
fn the_map_marker_uses_leaflets_image_path() {
    let template = include_str!("../../static/locate.js");
    assert!(template.contains("L.Icon.Default.imagePath = '/static/leaflet/images/';"));
    for option in ["iconUrl", "iconRetinaUrl", "shadowUrl"] {
        assert!(
            !template.contains(option),
            "{option} would be prefixed twice"
        );
    }
    for file in ["marker-icon.png", "marker-icon-2x.png", "marker-shadow.png"] {
        assert!(
            std::path::Path::new("static/leaflet/images")
                .join(file)
                .is_file()
        );
    }
}

// ---- Design 44: each Recent its own commands, the span, the Locations page ----

/// A fix `minutes_ago` old at latitude `lat` (the rows' and points' hook).
async fn fix(app: &TestApp, device: i64, lat: f64, minutes_ago: i64) -> i64 {
    let at = chrono::Utc::now() - chrono::Duration::minutes(minutes_ago);
    sqlx::query_scalar(
        "INSERT INTO device_locations (device_id, latitude, longitude, accuracy_meters, captured_at) \
         VALUES (?, ?, 10.7, 25, ?) RETURNING id",
    )
    .bind(device)
    .bind(lat)
    .bind(at.format("%Y-%m-%dT%H:%M:%S%.3fZ").to_string())
    .fetch_one(&app.db)
    .await
    .unwrap()
}

/// The `#recent` section of a page, or `None`.
fn recent_of(html: &str) -> Option<&str> {
    let start = html.find("<section id=\"recent\"")?;
    let end = html[start..]
        .find("</section>")
        .map_or(html.len(), |e| start + e);
    Some(&html[start..end])
}

/// Locate's Recent lists the page's own commands; the override commands (pressed on the summary)
/// are the summary's `#recent`, with Cancel back there (design 44 §1.1-§1.3).
#[tokio::test]
async fn each_recent_lists_its_own_commands_and_cancels_back_to_it() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, _) = app.enrolled_device("Anna").await;
    // Nothing yet: the summary has no Recent.
    let summary = app
        .get_page(&format!("/devices/{id}"), &cookie)
        .await
        .text();
    assert!(
        recent_of(&summary).is_none(),
        "an empty Recent on every summary"
    );

    for command in ["locate", "install_update"] {
        waiting(&app, id, command, "datetime('now', '-3 minutes')").await;
    }
    let lock_again = waiting(&app, id, "end_windows", "datetime('now', '-2 minutes')").await;
    for command in ["start_pause", "allow_blocked"] {
        waiting(&app, id, command, "datetime('now', '-1 minutes')").await;
    }
    let html = locate_page(&app, &cookie, &format!("?device={id}")).await;
    let locate = recent_of(&html).unwrap();
    assert!(locate.contains("data-command=\"locate\""), "{locate}");
    for other in [
        "end_windows",
        "start_pause",
        "allow_blocked",
        "install_update",
    ] {
        assert!(
            !locate.contains(&format!("data-command=\"{other}\"")),
            "{other} on Locate"
        );
    }
    assert!(locate.contains("<input type=\"hidden\" name=\"back\" value=\"locate\">"));

    let summary = app
        .get_page(&format!("/devices/{id}"), &cookie)
        .await
        .text();
    let recent = recent_of(&summary).expect("the summary's Recent");
    for (command, label) in [
        ("end_windows", "Lock again"),
        ("start_pause", "Unlock for 2 h"),
        ("allow_blocked", "Allow these apps"),
    ] {
        let row = recent_row(recent, command);
        assert!(row.contains(&format!(">{label}<")), "{command}: {row}");
        assert!(row.contains(">Cancel</button>"), "{command}: {row}");
    }
    for other in ["locate", "install_update"] {
        assert!(
            !recent.contains(&format!("data-command=\"{other}\"")),
            "{other} on the summary"
        );
    }
    assert!(recent.contains("<input type=\"hidden\" name=\"back\" value=\"summary\">"));
    // Under Quick Actions, where the buttons are.
    assert!(summary.find("id=\"quick\"").unwrap() < summary.find("id=\"recent\"").unwrap());

    // Cancel from the summary comes back to its Recent with the line.
    let cancel = format!("/devices/{id}/commands/{lock_again}/cancel");
    let res = app
        .request_form(Method::POST, &cancel, Some(&cookie), &[("back", "summary")])
        .await;
    assert_redirect(&res, &format!("/devices/{id}?cancel=done"), Some("recent"));
    app.assert_lands(&res, &cookie).await;
    let summary = app
        .get_page(&format!("/devices/{id}?cancel=done"), &cookie)
        .await
        .text();
    assert!(
        recent_of(&summary)
            .unwrap()
            .contains("data-flash=\"recent\">Cancelled. The phone will never get it.")
    );
    // Too late now; a `back` that isn't known (or none) goes to Locate.
    for fields in [&[("back", "https://evil.example")][..], &[][..]] {
        let res = app
            .request_form(Method::POST, &cancel, Some(&cookie), fields)
            .await;
        assert_redirect(
            &res,
            &format!("/devices/locate?device={id}&cancel=too_late"),
            Some("recent"),
        );
    }
}

/// `locations.json?since=<hours>`: the span's points oldest first, the newest added (`newest`)
/// only when nothing is that recent; no or a bad `since` is everything kept (design 44 §1.8).
#[tokio::test]
async fn locations_json_takes_a_span_and_keeps_the_newest() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, _) = app.enrolled_device("Anna").await;
    let json = |query: String| {
        let app = &app;
        let cookie = &cookie;
        async move {
            let res = app
                .get_page(&format!("/devices/{id}/locations.json{query}"), cookie)
                .await;
            assert_eq!(res.status, StatusCode::OK);
            res.json()
        }
    };
    assert_eq!(json("?since=12".into()).await, serde_json::json!([]));
    fix(&app, id, 1.0, 2 * 24 * 60).await;
    // Only a 2-day-old fix: it is the newest, flagged, outside the span.
    let points = json("?since=12".into()).await;
    assert_eq!(points.as_array().unwrap().len(), 1, "{points}");
    assert_eq!(points[0]["latitude"], 1.0);
    assert_eq!(points[0]["newest"], true);
    let week = json("?since=168".into()).await;
    assert_eq!(week[0].get("newest"), None, "inside the span: no flag");
    fix(&app, id, 2.0, 60).await;
    fix(&app, id, 3.0, 5).await;
    let points = json("?since=12".into()).await;
    let lats: Vec<f64> = points
        .as_array()
        .unwrap()
        .iter()
        .map(|p| p["latitude"].as_f64().unwrap())
        .collect();
    assert_eq!(lats, [2.0, 3.0], "the 2-day-old one left out, oldest first");
    assert!(
        points
            .as_array()
            .unwrap()
            .iter()
            .all(|p| p.get("newest").is_none())
    );
    for query in [
        "",
        "?since=0",
        "?since=-3",
        "?since=x",
        "?since=99999999999999",
    ] {
        let all = json(query.to_string()).await;
        assert_eq!(all.as_array().unwrap().len(), 3, "{query}");
    }
}

/// The Locations row on Locate counts the span's fixes (never the newest outside it), `?span=`
/// renders the others, and the pick marks the span shown (design 44 §1.4-§1.6).
#[tokio::test]
async fn the_locations_row_counts_the_span() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, _) = app.enrolled_device("Anna").await;
    let value = |html: &str| {
        html.split("data-locations-count>")
            .nth(1)
            .and_then(|rest| rest.split('<').next())
            .unwrap()
            .to_string()
    };
    let html = locate_page(&app, &cookie, &format!("?device={id}")).await;
    assert_eq!(value(&html), "None yet");
    fix(&app, id, 1.0, 3 * 24 * 60).await;
    let html = locate_page(&app, &cookie, &format!("?device={id}")).await;
    assert!(
        value(&html).starts_with("0 in the last 12 hours · newest "),
        "{}",
        value(&html)
    );
    fix(&app, id, 2.0, 20 * 60).await;
    fix(&app, id, 3.0, 60).await;
    for (query, expected, checked) in [
        ("", "1 in the last 12 hours", "12"),
        ("&span=5", "1 in the last 12 hours", "12"),
        ("&span=24", "2 in the last 24 hours", "24"),
        ("&span=168", "3 in the last 7 days", "168"),
        ("&span=0", "3 kept", "0"),
    ] {
        let html = locate_page(&app, &cookie, &format!("?device={id}{query}")).await;
        assert_eq!(value(&html), expected, "{query}");
        let pick = tags(&html, "a")
            .into_iter()
            .filter(|a| a.attr("role") == Some("radio"))
            .map(|a| {
                (
                    a.attr("data-span").unwrap_or("").to_string(),
                    a.attr("aria-checked").unwrap_or("").to_string(),
                )
            })
            .collect::<Vec<_>>();
        assert_eq!(pick.len(), 4, "{query}");
        for (span, aria) in pick {
            assert_eq!(aria == "true", span == checked, "{query}: {span} {aria}");
        }
        assert!(html.contains(&format!("href=\"/devices/{id}/locations?span={checked}\"")));
        assert!(html.contains(&format!("data-span=\"{checked}\"")));
    }
}

/// The Locations page: every fix of the span as a row, newest first, 200 a page; "Show older"
/// keys on `(captured_at, id)`, so fixes stored out of order (a backlog after offline) and fixes
/// with the same time are neither skipped nor repeated. No-store, nothing posts, 404 for an
/// unknown phone (design 44 §1.4).
#[tokio::test]
async fn the_locations_page_pages_every_fix_once() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, _) = app.enrolled_device("Anna").await;
    // 205 fixes, three to a minute, stored in a scrambled order (ids don't follow the time).
    let at = chrono::Utc::now() - chrono::Duration::minutes(1);
    for n in 0..205i64 {
        let i = n * 7 % 205;
        sqlx::query(
            "INSERT INTO device_locations (device_id, latitude, longitude, accuracy_meters, \
             captured_at) VALUES (?, ?, 10.7, ?, ?)",
        )
        .bind(id)
        .bind(i as f64)
        .bind(if i == 0 { None } else { Some(25.4) })
        .bind(
            (at - chrono::Duration::minutes(i / 3))
                .format("%Y-%m-%dT%H:%M:%S%.3fZ")
                .to_string(),
        )
        .execute(&app.db)
        .await
        .unwrap();
    }
    fix(&app, id, 999.0, 3 * 24 * 60).await;
    let lats = |html: &str| -> Vec<i64> {
        tags(html, "div")
            .into_iter()
            .filter_map(|div| {
                div.attr("data-lat")
                    .map(|l| l.parse::<f64>().unwrap() as i64)
            })
            .collect()
    };
    let res = app
        .get_page(&format!("/devices/{id}/locations"), &cookie)
        .await;
    assert_eq!(res.status, StatusCode::OK);
    assert_eq!(
        res.headers.get(axum::http::header::CACHE_CONTROL).unwrap(),
        "no-store"
    );
    let first = res.text();
    assert!(!first.contains("<form"), "nothing posts here");
    assert!(first.contains("± 25 m") && first.contains("accuracy unknown"));
    let older = tags(&first, "a")
        .into_iter()
        .find(|a| a.attr("data-row") == Some("older"))
        .and_then(|a| a.attr("href").map(str::to_string))
        .expect("Show older");
    assert!(
        older.starts_with(&format!("/devices/{id}/locations?span=12&amp;before=")),
        "{older}"
    );
    let second = app
        .get_page(&older.replace("&amp;", "&").replace("#fixes", ""), &cookie)
        .await
        .text();
    assert!(second.contains("data-row=\"newest\""));
    assert!(!second.contains("data-row=\"older\""));
    let mut shown = lats(&first);
    assert_eq!(shown.len(), 200);
    shown.extend(lats(&second));
    // Each once (the 3-day-old fix is outside the 12 hours), newest first (i / 3 is the age).
    let mut each = shown.clone();
    each.sort();
    assert_eq!(each, (0..205).collect::<Vec<_>>());
    assert!(shown.windows(2).all(|w| w[0] / 3 <= w[1] / 3), "{shown:?}");
    // All kept: it is there too, on the second page.
    let all = app
        .get_page(&format!("/devices/{id}/locations?span=0"), &cookie)
        .await
        .text();
    assert!(
        all.contains("/locations?span=0&amp;before="),
        "the span kept"
    );
    assert_eq!(
        app.get_page("/devices/999/locations", &cookie).await.status,
        StatusCode::NOT_FOUND
    );
}
