//! The Locate tab (design 31 step 13, §3.6): the fix as age and accuracy in local time, the rows
//! under the map and their pages (design 88) - Zones, Find Phone with the actions, Erase Phone…'s
//! confirm and the requests with labels instead of command ids (design 48 §1.4), Location
//! Settings -, and §5.1's redirect table for every form on them.

use axum::http::{Method, StatusCode};

use super::{TestApp, assert_redirect, autosaves, tags};

/// The row of the newest `command` in Requests or Activity (its stored id is the row's
/// `data-command` hook).
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

/// One of a phone's pages under Locate (design 88): `zones`, `find`, `location-settings`, with
/// `query`.
async fn phone_page(app: &TestApp, cookie: &str, id: i64, page: &str, query: &str) -> String {
    let path = format!("/devices/{id}/{page}{query}");
    let res = app.get_page(&path, cookie).await;
    assert_eq!(res.status, StatusCode::OK, "{path}: {}", res.text());
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

/// The rows under a chosen phone's map (design 88): their group.
fn rows_of(html: &str) -> &str {
    html.split("data-group=\"locate-rows\"")
        .nth(1)
        .and_then(|rest| rest.split("</div>\n").next())
        .expect("the rows")
}

/// §5.1's redirect table for the Locate tab's pages (design 88), built from the rendered forms:
/// each lands back on its page - Update Location at Locate's map (`#location`, where the wait
/// shows), Locate Regularly at Zones (`#zones`), Ring, Stop Ringing, Lock and Erase at Find Phone's
/// tiles (`#actions`, the requests right under them), Cancel at the requests where its line is,
/// the two pickers at Location Settings (`#location-settings`: a save never lands at the top).
/// Every form has a fragment, so none lands at the top. A form the table has no row for fails.
#[tokio::test]
async fn every_form_on_the_locate_pages_returns_to_its_group() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, _) = app.enrolled_device("Anna").await;
    app.enrolled_device("Emil").await;
    // A zone with the phone in Only when asked (design 64, code QA Q-3): Zones' Locate Regularly
    // is on its page.
    sqlx::query(
        "INSERT INTO device_zones (device_id, name, latitude, longitude, radius_m) \
         VALUES (?, 'Home', 59.9139, 10.7522, 150)",
    )
    .bind(id)
    .execute(&app.db)
    .await
    .unwrap();
    let lock = waiting(&app, id, "lock", "datetime('now', '-1 minutes')").await;
    let mut found = Vec::new();
    for (path, html) in [
        (
            format!("/devices/locate?device={id}"),
            locate_page(&app, &cookie, &format!("?device={id}")).await,
        ),
        (
            format!("/devices/{id}/zones"),
            phone_page(&app, &cookie, id, "zones", "").await,
        ),
        (
            format!("/devices/{id}/find"),
            phone_page(&app, &cookie, id, "find", "").await,
        ),
        (
            format!("/devices/{id}/location-settings"),
            phone_page(&app, &cookie, id, "location-settings", "").await,
        ),
    ] {
        super::settings_pages::check_forms(&path, &html);
        found.extend(forms(&html));
    }
    let locate = format!("/devices/locate?device={id}");
    let find = format!("/devices/{id}/find");
    let settings = format!("/devices/{id}/location-settings");
    // (action as rendered, the fields a parent sends, the expected Location).
    type Row<'a> = (String, &'a [(&'a str, &'a str)], (String, Option<&'a str>));
    let table: [Row; 9] = [
        (
            format!("/devices/{id}/command/locate#location"),
            &[],
            (format!("{locate}&requested=1"), Some("location")),
        ),
        // Locate Regularly (its `mode` and `back=zones` are the form's own hidden fields).
        (
            format!("/devices/{id}/location-policy#zones"),
            &[],
            (format!("/devices/{id}/zones"), Some("zones")),
        ),
        (
            format!("/devices/{id}/command/ring#actions"),
            &[],
            (find.clone(), Some("actions")),
        ),
        (
            format!("/devices/{id}/command/stop-ring#actions"),
            &[],
            (find.clone(), Some("actions")),
        ),
        (
            format!("/devices/{id}/command/lock#actions"),
            &[],
            (find.clone(), Some("actions")),
        ),
        (
            format!("/devices/{id}/commands/{lock}/cancel#recent"),
            &[],
            (format!("{find}?cancel=done"), Some("recent")),
        ),
        (
            format!("/devices/{id}/command/wipe#erase"),
            &[("confirm_name", "Anna")],
            (find.clone(), Some("actions")),
        ),
        (
            format!("/devices/{id}/location-policy#location-settings"),
            &[("mode", "interval"), ("interval_minutes", "60")],
            (settings.clone(), Some("location-settings")),
        ),
        (
            format!("/devices/{id}/location-retention#location-settings"),
            &[("days", "14")],
            (settings.clone(), Some("location-settings")),
        ),
    ];
    assert!(
        table.iter().all(|(_, _, (_, fragment))| fragment.is_some()),
        "every form lands on its group, also without JS"
    );
    assert_eq!(
        found.iter().map(|(a, _)| a.as_str()).collect::<Vec<_>>(),
        table.iter().map(|(a, _, _)| a.as_str()).collect::<Vec<_>>(),
        "every form on the four pages is in the table, in page order"
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

/// Design 88: a chosen phone's Locate page ends in one group of four rows - Locations, Zones,
/// Find Phone, Location Settings - with their values, each to its page; the zone list, the tiles,
/// the requests and the pickers are on those pages, each with the phone band (back to Locate, the
/// phone's avatar) and no bell. A command lands on Find Phone with its request shown, the newest
/// highlighted, every one that can be cancelled and the others up to three in all, then Show all
/// to the summary's Activity.
#[tokio::test]
async fn the_rows_and_their_pages() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, _) = app.enrolled_device("Emma").await;
    let html = locate_page(&app, &cookie, &format!("?device={id}")).await;
    let rows = rows_of(&html);
    // The four, in this order (`find` panics on a missing one).
    let at = |row: &str| rows.find(&format!("data-row=\"{row}\"")).expect(row);
    let order = ["locations", "zones", "find", "location-settings"];
    assert!(order.windows(2).all(|w| at(w[0]) < at(w[1])), "{rows}");
    assert_eq!(rows.matches("class=\"tile tile-").count(), 4, "{rows}");
    for href in [
        format!("/devices/{id}/locations?span=12"),
        format!("/devices/{id}/zones"),
        format!("/devices/{id}/find"),
        format!("/devices/{id}/location-settings"),
    ] {
        assert!(rows.contains(&format!("href=\"{href}\"")), "{href}");
    }
    // Their values: no zones yet, Only when asked; Ring, lock, erase under Find Phone.
    let zones_row = rows.split("data-row=\"zones\"").nth(1).unwrap();
    let zones_row = zones_row.split("</a>").next().unwrap();
    assert!(zones_row.contains(">None yet</span>"), "{zones_row}");
    assert!(rows.contains(">Only when asked</span>"), "{rows}");
    assert!(rows.contains("Find Phone<span class=\"row-sub\">Ring, lock, erase</span>"));
    // None of what moved.
    for gone in [
        "id=\"zones\"",
        "id=\"actions\"",
        "id=\"erase\"",
        "id=\"recent\"",
        "id=\"location-settings\"",
        "/command/ring",
        "data-autosave",
        "data-row=\"add-zone\"",
        "The map shows the span picked above",
    ] {
        assert!(!html.contains(gone), "{gone} on Locate");
    }
    // The setting's words: Every 15 min, every hour, Off.
    for (mode, minutes, words) in [
        ("interval", 15, "Every 15 min"),
        ("interval", 60, "Every hour"),
        ("interval", 240, "Every 4 hours"),
        ("off", 30, "Off"),
    ] {
        sqlx::query(
            "UPDATE device_policy SET location_mode = ?, location_interval_minutes = ? \
             WHERE device_id = ?",
        )
        .bind(mode)
        .bind(minutes)
        .bind(id)
        .execute(&app.db)
        .await
        .unwrap();
        let html = locate_page(&app, &cookie, &format!("?device={id}")).await;
        assert!(
            rows_of(&html).contains(&format!(">{words}</span>")),
            "{words}"
        );
    }
    let nb = locate_page(&app, &cookie, &format!("?device={id}&lang=nb")).await;
    assert!(rows_of(&nb).contains(">Av</span>"));
    assert!(rows_of(&nb).contains("Finn telefonen<span class=\"row-sub\">Ring, lås, slett</span>"));

    // The three pages: the phone band, back to this phone's Locate, no bell.
    for (page, title) in [
        ("zones", "Zones"),
        ("find", "Find Phone"),
        ("location-settings", "Location Settings"),
    ] {
        let html = phone_page(&app, &cookie, id, page, "").await;
        assert!(
            html.contains(&format!("<h1 class=\"page-title\">{title}</h1>")),
            "{page}"
        );
        assert!(
            html.contains("class=\"band-who\""),
            "{page}: the phone's avatar"
        );
        assert!(
            html.contains(&format!(
                "class=\"nav-back\" href=\"/devices/locate?device={id}\""
            )),
            "{page}"
        );
        assert!(!html.contains("title-bell"), "{page}: a page under the tab");
    }
    let zones = phone_page(&app, &cookie, id, "zones", "").await;
    assert!(zones.contains("data-row=\"add-zone\""));
    let settings = phone_page(&app, &cookie, id, "location-settings", "").await;
    assert!(autosaves(&settings, "mode") && autosaves(&settings, "days"));

    // A command lands on Find Phone with its request shown and highlighted.
    let find = phone_page(&app, &cookie, id, "find", "").await;
    assert!(find.contains("id=\"actions\"") && find.contains("id=\"erase\""));
    assert!(recent_of(&find).is_none(), "nothing asked yet");
    for path in ["ring", "lock", "stop-ring", "lock"] {
        let res = app
            .request_form(
                Method::POST,
                &format!("/devices/{id}/command/{path}"),
                Some(&cookie),
                &[],
            )
            .await;
        assert_redirect(&res, &format!("/devices/{id}/find"), Some("actions"));
        let find = app.get_page(res.location().unwrap(), &cookie).await.text();
        let recent = recent_of(&find).expect("the request is shown");
        assert!(recent.contains(">Recent Requests</h2>"), "{recent}");
        assert!(
            recent.contains("class=\"group group-newest\""),
            "the newest highlighted"
        );
        assert!(recent.matches("data-command=").count() <= 3, "{recent}");
        let newest = recent.split("data-command=\"").nth(1).unwrap();
        let kind = path.replace('-', "_");
        assert!(newest.starts_with(&format!("{kind}\"")), "{path}: {recent}");
        assert!(recent.contains(&format!("href=\"/devices/{id}#activity\"")));
        assert!(recent.contains(">Show all</span>") && recent.contains(">Activity</span>"));
    }
    // Lock, Stop Ringing, Lock wait (each with its Cancel): the replaced Ring is left out.
    let recent = phone_page(&app, &cookie, id, "find", "").await;
    assert_eq!(
        recent_of(&recent).unwrap().matches("data-command=").count(),
        3
    );
}

/// Design 88: the links that led to Locate's groups lead to the pages now - Lock Screen's
/// weather notes, a zone's back chevron - and resolve.
#[tokio::test]
async fn links_to_the_moved_groups_resolve() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, _) = app.enrolled_device("Emma").await;
    let lock = std::fs::read_to_string("templates/device_lock.html").unwrap();
    let zone = std::fs::read_to_string("templates/device_zone.html").unwrap();
    for template in [&lock, &zone] {
        assert!(!template.contains("devices/locate?device="), "{template}");
    }
    assert!(lock.contains("href=\"/devices/{{ device.id }}/location-settings\""));
    assert!(lock.contains("href=\"/devices/{{ device.id }}/zones\""));
    assert!(zone.contains("\"/devices/\" ~ device.id ~ \"/zones\""));
    // Show all goes to the summary's Activity - or to the summary itself while its Activity is
    // empty (code QA L1): a done ring eight days old is still Find Phone's newest Ring.
    let (lily, _) = app.create_device("Lily").await;
    sqlx::query(
        "INSERT INTO device_commands (device_id, command, requested_at, delivered_at, \
         acknowledged_at, result) VALUES (?, 'ring', datetime('now', '-8 days'), \
         datetime('now', '-8 days'), datetime('now', '-8 days'), 'ok')",
    )
    .bind(lily)
    .execute(&app.db)
    .await
    .unwrap();
    let find = phone_page(&app, &cookie, lily, "find", "").await;
    assert!(find.contains("data-command=\"ring\""), "{find}");
    assert!(
        find.contains(&format!("href=\"/devices/{lily}\" data-row=\"activity\"")),
        "{find}"
    );
    let summary = app
        .get_page(&format!("/devices/{lily}"), &cookie)
        .await
        .text();
    assert!(!summary.contains("id=\"activity\""), "{summary}");
    let new_zone = app
        .get_page(&format!("/devices/{id}/zones/new"), &cookie)
        .await
        .text();
    assert!(new_zone.contains(&format!("class=\"nav-back\" href=\"/devices/{id}/zones\"")));
    for path in [
        format!("/devices/{id}/zones"),
        format!("/devices/{id}/location-settings"),
        format!("/devices/{id}/find"),
    ] {
        assert_eq!(
            app.get_page(&path, &cookie).await.status,
            StatusCode::OK,
            "{path}"
        );
        let unknown = path.replace(&format!("/{id}/"), "/999/");
        assert_eq!(
            app.get_page(&unknown, &cookie).await.status,
            StatusCode::NOT_FOUND,
            "{unknown}"
        );
    }
}

/// A name that doesn't match erases nothing: the page comes back in the POST's answer with the
/// confirm open and the refusal in it, where the form's `#erase` lands.
#[tokio::test]
async fn a_wrong_name_opens_the_erase_confirm_with_the_refusal() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, _) = app.enrolled_device("Anna").await;
    let html = phone_page(&app, &cookie, id, "find", "").await;
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
    // Askama escapes the word's apostrophe (the name is a parameter, design 70 §2).
    assert!(confirm.contains("Erase Anna&#39;s phone</button>"));

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
        assert!(page.contains("<h1 class=\"page-title\">Find Phone</h1>"));
    }
    assert!(commands(&app, id).await.is_empty(), "nothing erased");
}

/// Design 85 revision 3: one look for one phone or several. One phone is shown at once with no
/// switcher and no chips; several and none chosen is the Alle view; a chosen one has the Alle view's
/// chips over its map ("All" first, it current), its marker's data and the legend.
#[tokio::test]
async fn the_chips_and_the_alle_view() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let html = locate_page(&app, &cookie, "").await;
    assert!(html.contains("No phones yet."));
    assert!(!html.contains("id=\"map\""));

    let (emma, _) = app.enrolled_device("Emma").await;
    // One phone: shown at once, no switcher, no chips and no "All"; the marker in its tone, and the
    // legend under the map and as the full screen's strip.
    let html = locate_page(&app, &cookie, "").await;
    assert!(html.contains("id=\"map\""));
    assert!(html.contains(&format!("/devices/{emma}/command/ring")));
    assert!(!html.contains("data-locate-all"));
    assert!(!html.contains("<nav class=\"phone-switch\""));
    assert!(!html.contains("map-chips") && !html.contains("data-chips"));
    assert!(html.contains("data-marker-tone=\"tone-peach\" data-marker-initial=\"E\""));
    assert!(!html.contains("data-marker-tag"), "no position, no tag");
    assert!(html.contains("<p class=\"map-legend\"><span>"));
    assert!(html.contains("<p class=\"map-legend map-legend-over\" aria-hidden=\"true\">"));
    assert!(html.contains("<script src=\"/static/map-marker.js?v=1\"></script>\n    <script src=\"/static/locate.js?v=8\"></script>"));

    fix(&app, emma, 59.9, 2).await;
    let (jack, _) = app.create_device("<b>Jack</b>").await;
    let (lily, _) = app.enrolled_device("Lily").await;
    sqlx::query("UPDATE device_policy SET location_mode = 'off' WHERE device_id = ?")
        .bind(lily)
        .execute(&app.db)
        .await
        .unwrap();
    // Several phones, none chosen (or one that doesn't exist): the Alle view, no actions, no list.
    for query in ["", "?device=999", "?device=x"] {
        let html = locate_page(&app, &cookie, query).await;
        assert!(html.contains("<section id=\"all\""), "{query}");
        assert!(html.contains("data-locate-all"), "{query}");
        assert!(html.contains("/static/locate-all.js?v=4"), "{query}");
        assert!(!html.contains("/command/"), "{query}");
        assert!(!html.contains("data-group=\"phones\""), "{query}");
        check_forms(&html);
        let chips = html
            .split("<nav class=\"map-chips\" aria-label=\"Phones\">")
            .nth(1)
            .and_then(|rest| rest.split("</nav>").next())
            .expect("the chips");
        let links = tags(chips, "a");
        assert_eq!(links.len(), 4, "{chips}");
        assert_eq!(links[0].attr("href"), Some("/devices/locate"));
        assert_eq!(links[0].attr("aria-current"), Some("true"));
        let label = |id: i64| {
            links
                .iter()
                .find(|a| a.attr("href") == Some(&format!("/devices/locate?device={id}")))
                .and_then(|a| a.attr("aria-label"))
                .unwrap()
                .to_string()
        };
        assert_eq!(label(emma), "Emma, 2 min ago, within 25 m");
        assert_eq!(label(jack), "&#60;b&#62;Jack&#60;/b&#62;, not set up");
        assert_eq!(label(lily), "Lily, location is off");
        // The legend, the phones without a position with why, Update All.
        assert!(html.contains(">Recent</span>") && html.contains(">Older position</span>"));
        let none = html
            .split("data-group=\"no-position\"")
            .nth(1)
            .and_then(|rest| rest.split("</div>").next())
            .expect("the phones without a position");
        assert!(none.contains(&format!("data-none=\"{jack}\"")) && none.contains("Not set up"));
        assert!(
            none.contains(&format!("data-none=\"{lily}\"")) && none.contains("Location is off")
        );
        assert!(!none.contains(&format!("data-none=\"{emma}\"")));
        assert!(html.contains("action=\"/devices/locate/all#all\""));
        assert!(html.contains(">Update All</button>"));
        assert!(!html.contains("<b>Jack</b>"), "names are escaped");
    }

    let html = locate_page(&app, &cookie, &format!("?device={emma}")).await;
    assert!(
        !html.contains("<nav class=\"phone-switch\""),
        "the chips replace the switcher"
    );
    assert!(!html.contains("data-locate-all"));
    let chips = html
        .split("<nav class=\"map-chips\" aria-label=\"Phones\">")
        .nth(1)
        .and_then(|rest| rest.split("</nav>").next())
        .expect("the chips over the map");
    let links = tags(chips, "a");
    assert_eq!(links.len(), 4);
    // "All" first, back to every phone on one map; the chosen phone current.
    assert_eq!(links[0].attr("href"), Some("/devices/locate"));
    assert!(!links[0].has("aria-current"));
    assert!(chips.contains(">All</a>"));
    let emma_link = links
        .iter()
        .find(|a| a.attr("href") == Some(&format!("/devices/locate?device={emma}")))
        .unwrap();
    assert_eq!(
        emma_link.attr("aria-label"),
        Some("Emma, 2 min ago, within 25 m")
    );
    assert_eq!(emma_link.attr("aria-current"), Some("true"));
    let jack_link = links
        .iter()
        .find(|a| a.attr("href") == Some(&format!("/devices/locate?device={jack}")))
        .unwrap();
    assert_eq!(
        jack_link.attr("aria-label"),
        Some("&#60;b&#62;Jack&#60;/b&#62;, not set up")
    );
    assert!(!jack_link.has("aria-current"));
    assert!(
        chips.contains("avatar avatar-grey avatar-hollow"),
        "{chips}"
    );
    // The marker: Emma's tone, her tag, fresh, her label; the map box carries the chips.
    let boxed = tags(&html, "div")
        .into_iter()
        .find(|d| d.has("data-locate"))
        .expect("the map box");
    assert_eq!(boxed.attr("data-marker-tone"), Some("tone-peach"));
    assert_eq!(boxed.attr("data-marker-tag"), Some("2 min"));
    assert!(boxed.has("data-marker-fresh") && boxed.has("data-chips"));
    assert_eq!(
        boxed.attr("data-marker-label"),
        Some("Emma, 2 min ago, within 25 m")
    );
    assert!(html.contains("<p class=\"map-legend\"><span>"));
    assert!(!html.contains("<b>Jack</b>"), "names are escaped");
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
    // QA N1 of round S3: the newest fix is kept while location is on, so none means none yet.
    assert!(html.contains("No position yet."), "{html}");
    assert!(!html.contains("data-fix-aged"), "{html}");
    // The owner's decision on M1 of round S3: a fix kept past the window says how old it is.
    let nine_days_ago = chrono::Utc::now() - chrono::Duration::days(9) - chrono::Duration::hours(1);
    sqlx::query(
        "INSERT INTO device_locations (device_id, latitude, longitude, accuracy_meters, captured_at) \
         VALUES (?, 59.9, 10.7, 20.0, ?)",
    )
    .bind(id)
    .bind(crate::zones::instant(nine_days_ago))
    .execute(&app.db)
    .await
    .unwrap();
    let html = locate_page(&app, &cookie, &format!("?device={id}")).await;
    assert!(
        html.contains("<span class=\"row-sub\" data-fix-aged>Last seen 9 days ago</span>"),
        "{html}"
    );

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
    assert!(!html.contains("UTC"), "local times, no UTC");
    // The row in Find Phone's requests says the same, with its word (failed within the day).
    let find = phone_page(&app, &cookie, id, "find", "").await;
    let row = recent_row(&find, "locate");
    assert!(row.contains("Update Location"), "{row}");
    assert!(row.contains("Failed"), "{row}");
    assert!(row.contains("Location is off on the phone"), "{row}");
    assert!(!find.contains("UTC"), "local times, no UTC");
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

/// The answer row (2026-10-25, PLAN "Locate: the \"Answer to Update Location\" row goes"): "Asking
/// the phone…" while asked (not right after the press: the wait's line says it), "Found within
/// 5 m" for 10 minutes, then nothing; never "Answer to".
#[tokio::test]
async fn the_answer_row_shows_only_while_it_says_something() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, _) = app.enrolled_device("Anna").await;
    let row = |html: &str| {
        html.split("data-row=\"locate-answer\"")
            .nth(1)
            .and_then(|rest| rest.split("</div>").next())
            .map(str::to_string)
    };
    let page = format!("?device={id}");
    assert_eq!(row(&locate_page(&app, &cookie, &page).await), None);

    let locate = waiting(&app, id, "locate", "datetime('now', '-2 minutes')").await;
    let html = locate_page(&app, &cookie, &page).await;
    let asking = row(&html).expect("the asking row");
    assert!(asking.contains("data-state=\"asking\""), "{asking}");
    assert!(asking.contains(">Asking the phone…<"), "{asking}");
    let pressed = locate_page(&app, &cookie, &format!("{page}&requested=1")).await;
    assert_eq!(row(&pressed), None, "the wait's own line instead");
    assert!(pressed.contains("id=\"fix_wait\""));

    sqlx::query(
        "UPDATE device_commands SET delivered_at = datetime('now', '-1 minutes'), \
         acknowledged_at = datetime('now', '-1 minutes'), result = 'fix ±5 m, 3 s old', \
         succeeded = 1 WHERE id = ?",
    )
    .bind(locate)
    .execute(&app.db)
    .await
    .unwrap();
    // Its fix, taken between the ask and the answer (Q-8: the row shows only with it).
    let fix_at = |seconds_ago: i64| {
        (chrono::Utc::now() - chrono::Duration::seconds(seconds_ago))
            .format("%Y-%m-%dT%H:%M:%S%.3fZ")
            .to_string()
    };
    assert_eq!(
        row(&locate_page(&app, &cookie, &page).await),
        None,
        "no fix yet: the found row would say what the map doesn't"
    );
    sqlx::query(
        "INSERT INTO device_locations (device_id, latitude, longitude, accuracy_meters, captured_at) \
         VALUES (?, 59.9, 10.7, 5.0, ?)",
    )
    .bind(id)
    .bind(fix_at(90))
    .execute(&app.db)
    .await
    .unwrap();
    let found = row(&locate_page(&app, &cookie, &page).await).expect("the found row");
    assert!(found.contains("data-state=\"found\""), "{found}");
    assert!(found.contains(">Found within 5 m<"), "{found}");
    assert!(found.contains("class=\"row-value\""), "its time: {found}");

    sqlx::query(
        "UPDATE device_commands SET acknowledged_at = datetime('now', '-11 minutes') WHERE id = ?",
    )
    .bind(locate)
    .execute(&app.db)
    .await
    .unwrap();
    let html = locate_page(&app, &cookie, &page).await;
    assert_eq!(
        row(&html),
        None,
        "10 minutes on: the point and its time say it"
    );

    // Q-9: a failure, then an Update Location cancelled - the failure stays.
    sqlx::query(
        "UPDATE device_commands SET acknowledged_at = datetime('now', '-1 minutes'), \
         result = 'location is off for this device', succeeded = 0 WHERE id = ?",
    )
    .bind(locate)
    .execute(&app.db)
    .await
    .unwrap();
    let cancelled = waiting(&app, id, "locate", "datetime('now')").await;
    sqlx::query(
        "UPDATE device_commands SET cancelled_at = datetime('now'), cancel_reason = 'parent' \
         WHERE id = ?",
    )
    .bind(cancelled)
    .execute(&app.db)
    .await
    .unwrap();
    let failed = row(&locate_page(&app, &cookie, &page).await).expect("the failure stays");
    assert!(failed.contains("data-state=\"failed\""), "{failed}");
    assert!(
        failed.contains(">Location is off on the phone<"),
        "{failed}"
    );
    let template = std::fs::read_to_string("templates/device_locate.html").unwrap();
    assert!(!template.contains("Answer to"));
}

/// Find Phone's requests: labels for every command, never its id; times local; Cancel only while
/// it waits; the line after a Cancel in the group.
#[tokio::test]
async fn recent_names_each_command_and_cancels_what_waits() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, _) = app.enrolled_device("Anna").await;
    for command in ["stop_ring", "wipe"] {
        waiting(&app, id, command, "datetime('now', '-2 minutes')").await;
    }
    let html = phone_page(&app, &cookie, id, "find", "").await;
    for (command, label) in [("stop_ring", "Stop Ringing"), ("wipe", "Erase Phone")] {
        let row = recent_row(&html, command);
        assert!(row.contains(&format!(">{label}<")), "{command}: {row}");
        assert!(row.contains("Waiting"), "{command}: {row}");
        assert!(row.contains(">Cancel</button>"), "{command}: {row}");
    }
    assert!(!html.contains(">stop_ring"), "the id shown");
    assert!(!html.contains("UTC"));
    assert!(html.contains("aria-label=\"Cancel Erase Phone asked for at "));

    let html = phone_page(&app, &cookie, id, "find", "?cancel=done").await;
    let recent = html.split("id=\"recent\"").nth(1).unwrap();
    assert!(recent.contains("data-flash=\"recent\">Cancelled. The phone will never get it."));
    let html = phone_page(&app, &cookie, id, "find", "?cancel=bogus").await;
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
    let html = phone_page(&app, &cookie, id, "find", "").await;
    let row = recent_row(&html, "wipe");
    assert!(row.contains(">Erasing<"), "{row}");
    assert!(row.contains("An erased phone stops reporting."), "{row}");
}

/// Location off is an orange row above Locate's rows, erasing not ready one on Find Phone; the
/// mode picker saves its form on Location Settings.
#[tokio::test]
async fn warnings_and_location_settings() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, _) = app.enrolled_device("Anna").await;
    let html = locate_page(&app, &cookie, &format!("?device={id}")).await;
    assert!(!html.contains("data-warning"));
    let settings = || phone_page(&app, &cookie, id, "location-settings", "");
    let html = settings().await;
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
    assert!(warning < html.find("data-group=\"locate-rows\"").unwrap());
    assert!(
        settings()
            .await
            .contains("<option value=\"off\" selected>Off</option>")
    );
    sqlx::query("UPDATE device_policy SET location_mode = 'interval' WHERE device_id = ?")
        .bind(id)
        .execute(&app.db)
        .await
        .unwrap();
    let html = settings().await;
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
    for path in ["locations.json", "zones"] {
        let res = app
            .get_page(&format!("/devices/{id}/{path}"), &cookie)
            .await;
        assert_eq!(res.status, StatusCode::INTERNAL_SERVER_ERROR, "{path}");
    }
    sqlx::query("ALTER TABLE device_commands RENAME TO device_commands_gone")
        .execute(&app.db)
        .await
        .unwrap();
    for path in ["locate-result.json", "find"] {
        let res = app
            .get_page(&format!("/devices/{id}/{path}"), &cookie)
            .await;
        assert_eq!(res.status, StatusCode::INTERNAL_SERVER_ERROR, "{path}");
    }
    // Location Settings reads the zones (the interval they serve).
    sqlx::query("ALTER TABLE device_zones RENAME TO device_zones_gone")
        .execute(&app.db)
        .await
        .unwrap();
    let res = app
        .get_page(&format!("/devices/{id}/location-settings"), &cookie)
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

/// Find Phone's requests list Locate's own commands; the summary's Activity every command of the
/// phone, its Cancel back there (design 44 §1.1, design 48 §1.2, §1.5); an unknown `back` goes to
/// Find Phone (design 88).
#[tokio::test]
async fn requests_list_this_pages_commands_and_activity_every_one() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, _) = app.enrolled_device("Anna").await;
    // Nothing yet: no requests (nor Activity).
    let html = phone_page(&app, &cookie, id, "find", "").await;
    assert!(recent_of(&html).is_none(), "{html}");

    for command in ["locate", "install_update"] {
        waiting(&app, id, command, "datetime('now', '-3 minutes')").await;
    }
    let lock_again = waiting(&app, id, "end_windows", "datetime('now', '-2 minutes')").await;
    let html = phone_page(&app, &cookie, id, "find", "").await;
    let locate = recent_of(&html).unwrap();
    assert!(locate.contains(">Recent Requests</h2>"), "{locate}");
    assert!(locate.contains("data-command=\"locate\""), "{locate}");
    for other in ["end_windows", "install_update"] {
        assert!(
            !locate.contains(&format!("data-command=\"{other}\"")),
            "{other} on Find Phone"
        );
    }
    assert!(locate.contains("<input type=\"hidden\" name=\"back\" value=\"find\">"));

    let summary = app
        .get_page(&format!("/devices/{id}"), &cookie)
        .await
        .text();
    assert!(recent_of(&summary).is_none(), "{summary}");
    let activity = &summary[summary.find("<section id=\"activity\"").expect("Activity")..];
    for command in ["end_windows", "locate", "install_update"] {
        assert!(
            recent_row(activity, command).contains(">Cancel</button>"),
            "{command}: {activity}"
        );
    }
    assert!(activity.contains("<input type=\"hidden\" name=\"back\" value=\"summary\">"));
    let cancel = format!("/devices/{id}/commands/{lock_again}/cancel");
    let res = app
        .request_form(Method::POST, &cancel, Some(&cookie), &[("back", "summary")])
        .await;
    assert_redirect(
        &res,
        &format!("/devices/{id}?cancel=done"),
        Some("activity"),
    );
    // Too late now; a `back` that isn't known (or none) goes to Find Phone.
    for fields in [&[("back", "https://evil.example")][..], &[][..]] {
        let res = app
            .request_form(Method::POST, &cancel, Some(&cookie), fields)
            .await;
        assert_redirect(
            &res,
            &format!("/devices/{id}/find?cancel=too_late"),
            Some("recent"),
        );
    }
}

/// Requests' buckets (design 48 §1.4): in flight at any age; failed, expired or never confirmed
/// for a day; the newest Ring, Stop Ringing, Lock and Erase whatever became of it; never what the
/// parent cancelled, never a finished Update Location. Find Phone shows the newest three of them
/// (design 88). Cancelling the last waiting one leaves the group for its line.
#[tokio::test]
async fn requests_show_what_is_in_flight_or_just_went_wrong() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, _) = app.enrolled_device("Anna").await;
    // (command, asked, then) - `then` sets the answer columns.
    let failed = "delivered_at = requested_at, acknowledged_at = requested_at, succeeded = 0, \
                  result = 'no location fix'";
    let done = "delivered_at = requested_at, acknowledged_at = requested_at, result = 'ok'";
    let lost = "delivered_at = requested_at";
    // Each row asked at its own time, told apart by its exact time in `title` (in UTC here).
    sqlx::query("UPDATE provisioning_settings SET time_zone = 'UTC'")
        .execute(&app.db)
        .await
        .unwrap();
    let rows = [
        // In flight at any age: a lock waiting three days, behind a newer finished one.
        ("lock", "-72 hours", "", true),
        ("lock", "-48 hours", done, true),
        // Gone wrong (failed, expired, never confirmed): shown for a day.
        ("locate", "-2 hours", failed, true),
        ("locate", "-49 hours", failed, false),
        ("locate", "-3 hours", "", true),
        ("locate", "-74 hours", "", false),
        ("locate", "-4 hours", lost, true),
        ("locate", "-98 hours", lost, false),
        // A finished Update Location: never.
        ("locate", "-5 minutes", done, false),
        // The newest Ring whatever its age (a cancelled one after it doesn't count), not the one
        // before.
        ("ring", "-75 hours", done, true),
        ("ring", "-99 hours", done, false),
        // The parent's own Cancel: never, and it never hides the last real one of its kind.
        (
            "wipe",
            "-1 hours",
            "cancelled_at = requested_at, cancel_reason = 'parent'",
            false,
        ),
        (
            "ring",
            "-30 minutes",
            "cancelled_at = requested_at, cancel_reason = 'parent'",
            false,
        ),
    ];
    let mut ids = Vec::new();
    for (command, age, then, _) in rows {
        let row = waiting(&app, id, command, &format!("datetime('now', '{age}')")).await;
        if !then.is_empty() {
            sqlx::query(&format!("UPDATE device_commands SET {then} WHERE id = ?"))
                .bind(row)
                .execute(&app.db)
                .await
                .unwrap();
        }
        ids.push(row);
    }
    // Every bucket, from `requests` itself. Find Phone lists every one that can be cancelled,
    // then the newest others up to three in all (design 88, code QA L2).
    let zone = crate::display_time::Zone::load(&app.db, crate::i18n::Lang::En).await;
    let now = chrono::Utc::now();
    let all = crate::handlers::locate::requests(&app.state, id, None, &zone, now)
        .await
        .unwrap();
    let cancellable = all.rows.iter().filter(|r| r.cancel_id.is_some()).count();
    let on_find: Vec<&str> = all
        .rows
        .iter()
        .filter(|r| r.cancel_id.is_none())
        .take(3 - cancellable)
        .chain(all.rows.iter().filter(|r| r.cancel_id.is_some()))
        .map(|r| r.asked_exact.as_str())
        .collect();
    let html = phone_page(&app, &cookie, id, "find", "").await;
    let requests = recent_of(&html).unwrap();
    for ((command, age, _, shown), row) in rows.iter().zip(&ids) {
        let exact: String =
            sqlx::query_scalar("SELECT requested_at FROM device_commands WHERE id = ?")
                .bind(row)
                .fetch_one(&app.db)
                .await
                .unwrap();
        let title = format!("{exact} UTC");
        assert_eq!(
            all.rows.iter().any(|r| r.asked_exact == title),
            *shown,
            "{command} {age}"
        );
        assert_eq!(
            requests.contains(&format!("title=\"{title}\"")),
            on_find.contains(&title.as_str()),
            "{command} {age}: {requests}"
        );
    }
    assert_eq!(all.rows.len(), 6);
    assert_eq!(requests.matches("data-command=").count(), 3, "{requests}");
    // The lock waiting three days, fourth by age, is never hidden: its Cancel is on Find Phone.
    assert_eq!(cancellable, 1);
    assert!(requests.contains(&format!("/commands/{}/cancel#recent", ids[0])));

    // Cancelling the last waiting one: the group stays for its line.
    let (emil, _) = app.enrolled_device("Emil").await;
    let only = waiting(&app, emil, "locate", "datetime('now', '-1 minutes')").await;
    let res = app
        .request_form(
            Method::POST,
            &format!("/devices/{emil}/commands/{only}/cancel"),
            Some(&cookie),
            &[("back", "find")],
        )
        .await;
    assert_redirect(
        &res,
        &format!("/devices/{emil}/find?cancel=done"),
        Some("recent"),
    );
    app.assert_lands(&res, &cookie).await;
    let html = phone_page(&app, &cookie, emil, "find", "?cancel=done").await;
    let requests = recent_of(&html).expect("Requests for its line");
    assert!(
        requests.contains("data-flash=\"recent\">Cancelled."),
        "{requests}"
    );
    assert!(!requests.contains("data-command"), "{requests}");
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
        // The count carries locate.js's words (`data-w-*`, design 70 §5.3) before its text.
        html.split("data-locations-count")
            .nth(1)
            .and_then(|rest| rest.split_once('>'))
            .and_then(|(_, rest)| rest.split('<').next())
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

/// Design 85 revision 3: a chosen phone's chips are the Alle view's, in the reader's language - "Alle",
/// "Telefoner", each phone's label as its marker tells it; no switcher and no online dot (the phone's
/// state is on Devices and its summary).
#[tokio::test]
async fn the_chips_speak_the_readers_language() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (emma, _) = app.enrolled_device("Emma").await;
    app.enrolled_device("Jack").await;
    let html = locate_page(&app, &cookie, &format!("?device={emma}&lang=nb")).await;
    assert!(
        html.contains("<nav class=\"map-chips\" aria-label=\"Telefoner\">"),
        "{html}"
    );
    assert!(html.contains(">Alle</a>"), "{html}");
    assert!(
        html.contains("aria-label=\"Emma, ingen posisjon ennå\" aria-current=\"true\""),
        "{html}"
    );
    assert!(html.contains(">Nylig</span>") && html.contains(">Eldre posisjon</span>"));
    assert!(!html.contains("class=\"dot dot-"), "{html}");
}

/// Design 70 §5.1, slice 3c: the Locate tab in Norwegian - the page, its zone page and the
/// Locations page under `lang="nb"`, Requests and the Locations row worded by the server,
/// `locate-result.json`'s answer and an error body in the reader's language, and the words
/// `locate.js` reads come with the page (`data-w-*`, its `?lang=`).
#[tokio::test]
async fn the_locate_tab_speaks_norwegian() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, _) = app.enrolled_device("Anna").await;
    fix(&app, id, 59.9, 3 * 24 * 60).await;
    let ring = waiting(&app, id, "ring", "datetime('now', '-1 minute')").await;
    let locate = waiting(&app, id, "locate", "datetime('now', '-2 minutes')").await;
    sqlx::query(
        "UPDATE device_commands SET delivered_at = datetime('now'), \
         acknowledged_at = datetime('now'), result = 'location is off for this device', \
         succeeded = 0 WHERE id = ?",
    )
    .bind(locate)
    .execute(&app.db)
    .await
    .unwrap();

    let html = locate_page(&app, &cookie, &format!("?device={id}&lang=nb")).await;
    assert!(html.contains(r#"<html lang="nb">"#));
    for words in [
        "<h1 class=\"page-title\">Finn</h1>",
        "Posisjonsinnstillinger",
        "Oppdater posisjon</button>",
        "Finn telefonen",
        "0 de siste 12 timene · nyeste ",
        "data-w-in-24=\"{count} de siste 24 timene\"",
        "data-w-newest=\"Nyeste posisjon\"",
        "data-lang=\"nb\"",
        "Posisjon er av på telefonen",
    ] {
        assert!(html.contains(words), "{words}");
    }
    assert!(!html.contains("Location Settings") && !html.contains("Find Phone"));
    // Design 88's pages.
    let find = phone_page(&app, &cookie, id, "find", "?lang=nb").await;
    assert!(find.contains(">Siste forespørsler</h2>") && find.contains(">Vis alle</span>"));
    let row = recent_row(&find, "ring");
    assert!(row.contains(">Ring<") && row.contains("Venter"), "{row}");
    let _ = ring;
    let settings = phone_page(&app, &cookie, id, "location-settings", "?lang=nb").await;
    assert!(settings.contains("<option value=\"interval\">Jevnlig</option>"));
    assert!(settings.contains("<h1 class=\"page-title\">Posisjonsinnstillinger</h1>"));
    let zones = phone_page(&app, &cookie, id, "zones", "?lang=nb").await;
    assert!(
        zones.contains("<h1 class=\"page-title\">Soner</h1>") && zones.contains("Legg til sone")
    );

    // English stays as it was.
    let en = locate_page(&app, &cookie, &format!("?device={id}&lang=en")).await;
    assert!(en.contains("Location Settings") && en.contains("0 in the last 12 hours · newest "));

    let answer = app
        .get_page(
            &format!("/devices/{id}/locate-result.json?lang=nb"),
            &cookie,
        )
        .await
        .text();
    assert!(
        answer.contains("\"text\":\"Posisjon er av på telefonen\""),
        "{answer}"
    );

    let zone = app
        .get_page(&format!("/devices/{id}/zones/new?lang=nb"), &cookie)
        .await
        .text();
    assert!(zone.contains(r#"<html lang="nb">"#) && zone.contains("Ny sone"));
    assert!(zone.contains("Varsle når de kommer"));

    let locations = app
        .get_page(&format!("/devices/{id}/locations?lang=nb"), &cookie)
        .await
        .text();
    assert!(locations.contains(r#"<html lang="nb">"#));
    assert!(
        locations.contains("Posisjonene til Anna")
            && locations.contains("Ingen i dette tidsrommet.")
    );

    let missing = app
        .get_page("/devices/999/locations?lang=nb", &cookie)
        .await;
    assert_eq!(missing.status, StatusCode::NOT_FOUND);
    assert_eq!(missing.text(), "Fant ikke enheten");
}

// ---- Design 85: the Alle view ----

/// The phone's only fix: captured `minutes_ago`, re-sent as still current `resent_ago` minutes ago,
/// at School's centre within 25 m.
async fn only_fix(app: &TestApp, device: i64, minutes_ago: i64, resent_ago: Option<i64>) {
    let at = |m: i64| crate::zones::instant(chrono::Utc::now() - chrono::Duration::minutes(m));
    sqlx::query("DELETE FROM device_locations WHERE device_id = ?")
        .bind(device)
        .execute(&app.db)
        .await
        .unwrap();
    sqlx::query(
        "INSERT INTO device_locations (device_id, latitude, longitude, accuracy_meters, captured_at, \
         resent_at) VALUES (?, 59.9139, 10.7522, 25, ?, ?)",
    )
    .bind(device)
    .bind(at(minutes_ago))
    .bind(resent_ago.map(at))
    .execute(&app.db)
    .await
    .unwrap();
}

async fn set_location(app: &TestApp, device: i64, mode: &str, interval: i64) {
    sqlx::query(
        "UPDATE device_policy SET location_mode = ?, location_interval_minutes = ? \
         WHERE device_id = ?",
    )
    .bind(mode)
    .bind(interval)
    .bind(device)
    .execute(&app.db)
    .await
    .unwrap();
}

/// `phones.json`'s row for `device`.
async fn phone_row(app: &TestApp, cookie: &str, query: &str, device: i64) -> serde_json::Value {
    let res = app
        .get_page(&format!("/devices/locate/phones.json{query}"), cookie)
        .await;
    assert_eq!(res.status, StatusCode::OK);
    res.json()
        .as_array()
        .unwrap()
        .iter()
        .find(|row| row["id"] == device)
        .cloned()
        .unwrap()
}

/// Design 85 §4.6, §3.2: `phones.json`'s rows, fresh by the phone's own setting at its edges
/// (`zones::fresh`, "Here now"'s rule), a still re-send counting, the tags and the zone word, why a
/// phone has none, in the reader's language; no-store, and signed out the login's redirect.
#[tokio::test]
async fn phones_json_tells_each_phone_fresh_or_old_by_its_own_setting() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (emma, token) = app.enrolled_device("Emma").await;
    let (jack, _) = app.create_device("Jack").await;
    let (lily, _) = app.enrolled_device("Lily").await;
    set_location(&app, lily, "off", 30).await;

    let res = app.get_page("/devices/locate/phones.json", &cookie).await;
    assert_eq!(
        res.headers.get(axum::http::header::CACHE_CONTROL).unwrap(),
        "no-store"
    );
    let rows = res.json();
    let ids: Vec<i64> = rows
        .as_array()
        .unwrap()
        .iter()
        .map(|r| r["id"].as_i64().unwrap())
        .collect();
    assert_eq!(ids, [emma, jack, lily], "the page's order");
    for (id, none) in [(emma, "no_fix"), (jack, "not_set_up"), (lily, "off")] {
        let row = phone_row(&app, &cookie, "", id).await;
        assert_eq!(row["none"], none, "{row}");
        assert!(
            row.get("lat").is_none() && row.get("answered").is_none(),
            "{row}"
        );
    }
    let row = phone_row(&app, &cookie, "", jack).await;
    assert_eq!(row["tone"], "avatar-grey");
    assert_eq!(row["href"], format!("/devices/locate?device={jack}"));
    assert_eq!(row["label"], "Jack, not set up");
    assert_eq!(row["show"], "Show Jack");

    // Regularly every 15 min: fresh while younger than 30.
    set_location(&app, emma, "interval", 15).await;
    only_fix(&app, emma, 29, None).await;
    let row = phone_row(&app, &cookie, "", emma).await;
    for key in ["id", "name", "initial", "tone", "href", "label", "show"] {
        assert!(row.get(key).is_some(), "{key}: {row}");
    }
    assert_eq!(row["fresh"], true);
    assert_eq!(
        (row["lat"].as_f64(), row["lng"].as_f64()),
        (Some(59.9139), Some(10.7522))
    );
    assert_eq!(row["accuracy_m"], 25);
    assert_eq!(row["tag"], "29 min");
    assert_eq!(row["label"], "Emma, 29 min ago, within 25 m");
    assert_eq!(
        row["lines"],
        serde_json::json!(["Last seen 29 min ago", "Within 25 m"])
    );
    assert!(row.get("none").is_none());
    only_fix(&app, emma, 31, None).await;
    assert_eq!(phone_row(&app, &cookie, "", emma).await["fresh"], false);
    // A zone the phone watches: served 30 min, fresh while younger than an hour; the zone word.
    sqlx::query(
        "INSERT INTO device_zones (device_id, name, latitude, longitude, radius_m) \
         VALUES (?, 'Skolen', 59.9139, 10.7522, 150)",
    )
    .bind(emma)
    .execute(&app.db)
    .await
    .unwrap();
    app.post_status(
        &token,
        serde_json::json!({"capabilities": ["zones_v1"], "zone_watch": {"fences": 1,
            "motion_sensor": true, "location_on": true, "background_location": true,
            "mock_ignored": 0}}),
    )
    .await;
    only_fix(&app, emma, 59, None).await;
    let row = phone_row(&app, &cookie, "?lang=nb", emma).await;
    assert_eq!(row["fresh"], true);
    assert_eq!(row["lines"][1], "Innenfor 25 m · I sonen Skolen");
    assert_eq!(row["lines"][0], "Sist sett for 59 min siden");
    // Only when asked: an hour.
    set_location(&app, emma, "on_request", 15).await;
    only_fix(&app, emma, 59, None).await;
    assert_eq!(phone_row(&app, &cookie, "", emma).await["fresh"], true);
    only_fix(&app, emma, 61, None).await;
    let row = phone_row(&app, &cookie, "?lang=nb", emma).await;
    assert_eq!(row["fresh"], false);
    assert_eq!(row["tag"], "1 t");
    // A still re-send newer than the fix counts from the re-send and says since when.
    only_fix(&app, emma, 3 * 60, Some(2)).await;
    let row = phone_row(&app, &cookie, "?lang=nb", emma).await;
    assert_eq!(row["fresh"], true);
    assert_eq!(row["tag"], "2 min");
    assert!(
        row["lines"][2]
            .as_str()
            .unwrap()
            .starts_with("Har ikke flyttet seg siden "),
        "{row}"
    );
    // The tags.
    for (minutes, tag) in [
        (0, "nå"),
        (2, "2 min"),
        (3 * 60, "3 t"),
        (2 * 24 * 60 + 5, "2 d"),
    ] {
        only_fix(&app, emma, minutes, None).await;
        assert_eq!(phone_row(&app, &cookie, "?lang=nb", emma).await["tag"], tag);
    }
    // Switched to Off: every fix pruned, so no position.
    let res = app
        .request_form(
            Method::POST,
            &format!("/devices/{emma}/location-policy"),
            Some(&cookie),
            &[("mode", "off")],
        )
        .await;
    assert_eq!(res.status, StatusCode::SEE_OTHER);
    let row = phone_row(&app, &cookie, "?lang=nb", emma).await;
    assert_eq!(row["none"], "off");
    assert_eq!(row["label"], "Emma, posisjon er slått av");

    // Signed out: the login.
    let res = app
        .request(Method::GET, "/devices/locate/phones.json", None, None)
        .await;
    assert_eq!(res.location(), Some("/login"));
}

/// Design 85 §4.6 (O3): Update All asks every phone that is set up, not disconnected and not Off -
/// each logged, a waiting one replaced - and names the others with why under the wait; a command,
/// not a settings save; a GET is 405, signed out the login.
#[tokio::test]
async fn update_all_asks_every_phone_that_can_answer() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (emma, _) = app.enrolled_device("Emma").await;
    let (jack, _) = app.create_device("Jack").await;
    let (lily, _) = app.enrolled_device("Lily").await;
    set_location(&app, lily, "off", 30).await;
    let (pappa, _) = app.enrolled_device("Pappa").await;
    sqlx::query("UPDATE devices SET token_hash = NULL WHERE id = ?")
        .bind(pappa)
        .execute(&app.db)
        .await
        .unwrap();
    let old = waiting(&app, emma, "locate", "datetime('now', '-1 minute')").await;

    let res = app
        .request_form(Method::POST, "/devices/locate/all", None, &[])
        .await;
    assert_eq!(res.location(), Some("/login"));
    assert_eq!(commands(&app, emma).await, ["locate"]);
    assert_eq!(
        app.get_page("/devices/locate/all", &cookie).await.status,
        StatusCode::METHOD_NOT_ALLOWED
    );

    let res = app
        .request_form(Method::POST, "/devices/locate/all", Some(&cookie), &[])
        .await;
    assert_eq!(res.status, StatusCode::SEE_OTHER);
    let back = res.location().unwrap().to_string();
    assert!(
        back.starts_with("/devices/locate?requested=all&at=") && back.ends_with("#all"),
        "{back}"
    );
    assert_eq!(commands(&app, emma).await, ["locate", "locate"]);
    let replaced: Option<String> =
        sqlx::query_scalar("SELECT cancel_reason FROM device_commands WHERE id = ?")
            .bind(old)
            .fetch_one(&app.db)
            .await
            .unwrap();
    assert_eq!(replaced.as_deref(), Some("replaced"));
    for id in [jack, lily, pappa] {
        assert!(commands(&app, id).await.is_empty());
    }
    let queued: i64 = sqlx::query_scalar(
        "SELECT COUNT(*) FROM security_events WHERE event_type = 'device_command_queued'",
    )
    .fetch_one(&app.db)
    .await
    .unwrap();
    assert_eq!(queued, 1);
    let saves: i64 = sqlx::query_scalar(
        "SELECT COUNT(*) FROM security_events WHERE event_type = 'settings_saved'",
    )
    .fetch_one(&app.db)
    .await
    .unwrap();
    assert_eq!(saves, 0);

    // The wait, with who wasn't asked and why.
    let page = locate_page(
        &app,
        &cookie,
        back.split('#')
            .next()
            .unwrap()
            .trim_start_matches("/devices/locate"),
    )
    .await;
    assert!(page.contains("data-waiting"), "{page}");
    assert!(page.contains("role=\"status\" data-w-answered=\"{done} of {count} have answered\""));
    assert!(page.contains(">Asking 1 phone …</p>"), "{page}");
    for note in [
        "Jack: not set up",
        "Lily: location is off",
        "Pappa: access removed",
    ] {
        assert!(page.contains(&format!("data-skipped>{note}</p>")), "{note}");
    }
}

/// Design 85 §4.6: the wait shows only while `at` is under two minutes old and reads; a phone has
/// answered once its newest locate since `at` left Asking - a fix or a failure, never a fix that
/// came without one; with no phone to ask, no wait.
#[tokio::test]
async fn the_wait_after_update_all_counts_the_answers() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (emma, emma_token) = app.enrolled_device("Emma").await;
    let (mamma, mamma_token) = app.enrolled_device("Mamma").await;
    let at = |minutes: i64| {
        (chrono::Utc::now() - chrono::Duration::minutes(minutes))
            .format("%Y-%m-%dT%H:%M:%SZ")
            .to_string()
    };
    for stale in [at(3), "x".to_string()] {
        let page = locate_page(&app, &cookie, &format!("?requested=all&at={stale}")).await;
        assert!(!page.contains("all_wait"), "{stale}");
    }
    // Nothing asked since: no phone could be asked, no wait.
    let page = locate_page(&app, &cookie, &format!("?requested=all&at={}", at(1))).await;
    assert!(page.contains(">No phone can be asked</p>") && !page.contains("data-waiting"));

    let res = app
        .request_form(Method::POST, "/devices/locate/all", Some(&cookie), &[])
        .await;
    let start = res
        .location()
        .unwrap()
        .split("at=")
        .nth(1)
        .unwrap()
        .split('#')
        .next()
        .unwrap()
        .to_string();
    let page = locate_page(&app, &cookie, &format!("?requested=all&at={start}")).await;
    assert!(page.contains(">Asking 2 phones …</p>"), "{page}");
    let query = format!("?at={start}");
    for id in [emma, mamma] {
        assert_eq!(
            phone_row(&app, &cookie, &query, id).await["answered"],
            false
        );
    }
    // A fix without an answer (a regular one, a re-send) is no answer.
    only_fix(&app, emma, 0, None).await;
    assert_eq!(
        phone_row(&app, &cookie, &query, emma).await["answered"],
        false
    );
    // A found fix and a failure are answers.
    for (token, success, message) in [
        (&emma_token, true, "fix ±12 m, 3 s old"),
        (&mamma_token, false, "location is off for this device"),
    ] {
        let id = app.policy(token).await["pending_command"]["id"]
            .as_i64()
            .unwrap();
        app.request(
            Method::POST,
            "/api/devices/command-result",
            Some(token),
            Some(serde_json::json!({"command_id": id, "success": success, "message": message})),
        )
        .await;
    }
    for id in [emma, mamma] {
        assert_eq!(phone_row(&app, &cookie, &query, id).await["answered"], true);
    }
    // Without `at`, no answers at all.
    assert!(
        phone_row(&app, &cookie, "", emma)
            .await
            .get("answered")
            .is_none()
    );
}

/// Design 85 §4.7: the one-phone view's age line says fresh or old with the legend's dot (not the
/// online dot) and its hidden word, and since when a still phone hasn't moved.
#[tokio::test]
async fn the_age_line_says_fresh_or_old() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (emma, _) = app.enrolled_device("Emma").await;
    set_location(&app, emma, "interval", 15).await;
    let age = |html: &str| {
        html.split("<span class=\"fix-age\"")
            .nth(1)
            .and_then(|rest| rest.split("<span class=\"row-sub\">").next())
            .unwrap()
            .to_string()
    };
    only_fix(&app, emma, 10, None).await;
    let html = locate_page(&app, &cookie, "").await;
    let line = age(&html);
    assert!(
        line.contains("<span class=\"seen-dot seen-fresh\" aria-hidden=\"true\"></span>10 min ago"),
        "{line}"
    );
    assert!(
        line.contains("<span class=\"visually-hidden\"> Recent</span>"),
        "{line}"
    );
    assert!(!line.contains("dot-online"));
    assert!(!html.contains("data-fix-still"));
    only_fix(&app, emma, 40, None).await;
    let line = age(&locate_page(&app, &cookie, "").await);
    assert!(
        line.contains("seen-dot seen-old") && line.contains("> Older position</span>"),
        "{line}"
    );
    // A still re-send: its age, and since when.
    only_fix(&app, emma, 3 * 60, Some(2)).await;
    let html = locate_page(&app, &cookie, "").await;
    assert!(
        age(&html).contains("seen-fresh\" aria-hidden=\"true\"></span>2 min ago"),
        "{html}"
    );
    assert!(
        html.contains("data-fix-still>Hasn&#39;t moved since "),
        "{html}"
    );
    // Still for 25 h with a day's history (code QA M1): one age for the fix on the whole page - no
    // "Last seen 1 day ago" under "2 min ago", the map's and the Locations row's age the same.
    sqlx::query("UPDATE device_policy SET location_retention_days = 1 WHERE device_id = ?")
        .bind(emma)
        .execute(&app.db)
        .await
        .unwrap();
    only_fix(&app, emma, 25 * 60, Some(2)).await;
    let html = locate_page(&app, &cookie, "").await;
    assert!(age(&html).contains("</span>2 min ago"), "{html}");
    assert!(!html.contains("data-fix-aged"), "{html}");
    assert!(html.contains("data-newest-age=\"2 min ago\""), "{html}");
    assert!(!html.contains("1 day ago"), "{html}");
    // The one other time is the still line's own, since when it hasn't moved.
    assert!(
        html.contains("data-fix-still>Hasn&#39;t moved since yesterday "),
        "{html}"
    );
}

/// Design 85 §4.8 (the user's decision of 2026-11-12): both map views carry the full-screen
/// controls, hidden until the script, with their words in the reader's language.
#[tokio::test]
async fn both_map_views_offer_full_screen() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (emma, _) = app.enrolled_device("Emma").await;
    app.enrolled_device("Jack").await;
    for (lang, open, close) in [
        ("en", "Full screen", "Close full screen"),
        ("nb", "Fullskjerm", "Lukk fullskjerm"),
    ] {
        for query in [
            format!("?lang={lang}"),
            format!("?device={emma}&lang={lang}"),
        ] {
            let html = locate_page(&app, &cookie, &query).await;
            assert_eq!(html.matches("data-map-full").count(), 1, "{query}");
            let buttons = tags(&html, "button");
            let open_button = buttons.iter().find(|b| b.has("data-full-open")).expect("⤢");
            assert_eq!(open_button.attr("aria-label"), Some(open), "{query}");
            assert!(open_button.has("hidden"));
            let close_button = buttons
                .iter()
                .find(|b| b.has("data-full-close"))
                .expect("✕");
            assert_eq!(close_button.attr("aria-label"), Some(close), "{query}");
            assert!(html.contains("<script src=\"/static/map-full.js?v=2\"></script>"));
        }
    }
    // The legend strip for full screen, the eye's only - on both views (revision 3).
    for query in [String::new(), format!("?device={emma}")] {
        let html = locate_page(&app, &cookie, &query).await;
        assert!(
            html.contains("<p class=\"map-legend map-legend-over\" aria-hidden=\"true\">"),
            "{query}"
        );
    }
}
