//! Design 31 steps 3 and 4: a phone's About This Phone, Advanced and Lock Screen & PINs pages.
//! About is read-only (the connection, sound, software, crash reports, starts without Vibb, the
//! time lock - local times, the exact one in `title`); Advanced holds the forms the device page's
//! Hardening, Play and kiosk, Launcher updates and Access cards had, and Remove Phone; Lock Screen
//! & PINs the Screen lock, Parent PIN and Screen timeout cards, the lock background and the Apps
//! card's "On the lock screen". Their POST routes are unchanged; this checks where each returns to
//! (the redirect table, §5.1) and the confirms.

use axum::http::{Method, StatusCode};
use serde_json::json;

use super::{Section, TestApp, assert_redirect, autosaves, device_page, nav_current, tags};

async fn report(app: &TestApp, token: &str, extra: serde_json::Value) {
    let mut body = json!({ "lock_reason": "NONE", "kiosk_engaged": true });
    for (key, value) in extra.as_object().unwrap() {
        body[key] = value.clone();
    }
    let res = app
        .request(Method::POST, "/api/devices/status", Some(token), Some(body))
        .await;
    assert_eq!(res.status, StatusCode::NO_CONTENT, "{}", res.text());
}

/// The text of About's row `key` (`data-row`) value, tags dropped ("Online since 08:12").
fn value(html: &str, key: &str) -> Option<String> {
    let row = &html[html.find(&format!("data-row=\"{key}\""))?..];
    let value = &row[row.find("class=\"row-value")?..];
    let value = &value[value.find('>')? + 1..];
    // Up to the span's own end: a coloured word before the value is a span of its own.
    let (mut depth, mut text, mut rest) = (0, String::new(), value);
    loop {
        let at = rest.find('<')?;
        text.push_str(&rest[..at]);
        rest = &rest[at..];
        if rest.starts_with("</span>") {
            if depth == 0 {
                return Some(text);
            }
            depth -= 1;
        } else if rest.starts_with("<span") {
            depth += 1;
        }
        rest = &rest[rest.find('>')? + 1..];
    }
}

#[tokio::test]
async fn about_this_phone_shows_the_last_reports_facts() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("Anna").await;
    report(
        &app,
        &token,
        json!({
            "lock_reason": "SCHOOL",
            "app_version": "0.23.6-debug",
            "ringer_mode": "silent",
            "interruption_filter": "all",
            "policy_state": "cache_corrupt",
            "battery_optimization_exempt": true,
            "music_state": { "package": "me.vibb.music", "version_code": 3000 },
            "time_state": { "lock_reason": "SCHOOL", "active_rule_name": "Skole" },
        }),
    )
    .await;
    let res = app
        .get_page(&device_page(id, Section::About), &cookie)
        .await;
    assert_eq!(res.status, StatusCode::OK);
    let html = res.text();
    assert_eq!(nav_current(&html), "devices");
    // A sub-page: the back link to the phone and the large title, no brand bar.
    assert!(html.contains(&format!("<a class=\"nav-back\" href=\"/devices/{id}\">")));
    assert!(html.contains("<h1 class=\"page-title\">About This Phone</h1>"));
    assert!(!html.contains("class=\"app-header\""));

    // Connection: the report was a contact a moment ago, with no stream: Away.
    assert!(
        html.contains("<span class=\"state-away\">Away</span>"),
        "{html}"
    );
    assert!(
        value(&html, "last-contact")
            .unwrap()
            .contains(" · just now")
    );
    // Local times (Phone Setup's Europe/Oslo), the exact one in `title`, never UTC.
    let report_row = &html[html.find("data-row=\"last-report\"").unwrap()..];
    assert!(report_row.contains("title=\"20"), "{html}");
    assert!(!html.contains(" UTC"), "{html}");
    // The test's requests come from loopback, with no proxy.
    assert_eq!(value(&html, "network").as_deref(), Some("This machine"));
    assert!(html.contains("no client address"));
    assert_eq!(value(&html, "status").as_deref(), Some("Away"));

    // Sound, Software, Diagnostics.
    assert_eq!(value(&html, "ringer").as_deref(), Some("Silent"));
    assert!(html.contains("neither ring nor vibrate"));
    assert_eq!(value(&html, "do-not-disturb").as_deref(), Some("Off"));
    assert_eq!(value(&html, "vibb").as_deref(), Some("0.23.6"));
    assert!(html.contains("Full version: 0.23.6-debug."));
    assert_eq!(value(&html, "music-app").as_deref(), Some("0.3.0"));
    assert!(html.contains("me.vibb.music, version code 3000."));
    assert_eq!(
        value(&html, "settings").as_deref(),
        Some("Saved settings unreadable")
    );
    assert!(html.contains("can&#39;t read its saved settings"), "{html}");
    assert_eq!(value(&html, "battery").as_deref(), Some("Off"));
    assert_eq!(value(&html, "crashes").as_deref(), Some("None in 30 days"));
    assert_eq!(value(&html, "starts").as_deref(), Some("None"));
    assert_eq!(value(&html, "time-lock").as_deref(), Some("Skole"));

    // The device page: the facts are gone from it, its About row sums them up.
    let summary = app
        .get_page(&device_page(id, Section::Summary), &cookie)
        .await
        .text();
    assert!(summary.contains(&format!("href=\"/devices/{id}/about\"")));
    assert!(summary.contains("<span class=\"row-value\">Vibb 0.23.6</span>"));
    for gone in [
        "App version:",
        "Sound at the last sync",
        "Last reported",
        "Time lock at the last report",
        "Instant changes",
        "Last access:",
        "Launcher crashes",
        "can&#39;t read its saved",
    ] {
        assert!(!summary.contains(gone), "{gone}");
    }
    // The policy problem is an Attention row that leads to About.
    assert!(summary.contains(&format!(
        "href=\"/devices/{id}/about#software\" data-attention=\"policy-problem\""
    )));
}

#[tokio::test]
async fn about_says_applied_and_on_for_a_healthy_phone_and_a_time_rule_by_kind() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("Emil").await;
    report(
        &app,
        &token,
        json!({
            "lock_reason": "BEDTIME",
            "policy_state": "ok",
            "battery_optimization_exempt": false,
            "app_version": "0.24.0",
        }),
    )
    .await;
    let html = app
        .get_page(&device_page(id, Section::About), &cookie)
        .await
        .text();
    assert_eq!(value(&html, "settings").as_deref(), Some("Applied"));
    assert_eq!(value(&html, "battery").as_deref(), Some("On"));
    // No suffix: no ⓘ on the version.
    assert_eq!(value(&html, "vibb").as_deref(), Some("0.24.0"));
    assert!(!html.contains("Full version"));
    assert_eq!(value(&html, "time-lock").as_deref(), Some("Bedtime"));
    // No music state, no crash: no music row, "None in 30 days".
    assert!(!html.contains("data-row=\"music-app\""));
}

#[tokio::test]
async fn a_phone_not_set_up_has_little_to_say_and_can_still_be_removed() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, _) = app.create_device("Mia").await;
    let about = app
        .get_page(&device_page(id, Section::About), &cookie)
        .await
        .text();
    assert!(about.contains("<span class=\"state-offline\">Not set up</span>"));
    assert!(!about.contains("data-row=\"last-contact\""));
    assert!(!about.contains("id=\"diagnostics\""));
    let advanced = app
        .get_page(&device_page(id, Section::Advanced), &cookie)
        .await
        .text();
    // Restrictions can be set before setup; the rest needs a phone.
    assert!(advanced.contains(&format!("action=\"/devices/{id}/hardening\"")));
    assert!(advanced.contains(&format!("action=\"/devices/{id}/delete\"")));
    for missing in [
        "/kiosk-block",
        "/kiosk-escapes",
        "/revoke",
        "/regenerate-code",
    ] {
        assert!(!advanced.contains(missing), "{missing}");
    }
    // The device page keeps its "Not enrolled yet" card with the code.
    let summary = app
        .get_page(&device_page(id, Section::Summary), &cookie)
        .await
        .text();
    assert!(summary.contains("Not enrolled yet"));
    assert!(summary.contains(&format!("action=\"/devices/{id}/regenerate-code\"")));
}

#[tokio::test]
async fn unknown_phones_are_404() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    for section in [Section::About, Section::Advanced, Section::Lock] {
        let res = app.get_page(&device_page(9999, section), &cookie).await;
        assert_eq!(res.status, StatusCode::NOT_FOUND, "{section:?}");
    }
}

/// The forms a page holds: (action, hidden fields), in page order.
fn forms(html: &str) -> Vec<(String, Vec<(String, String)>)> {
    let all = tags(html, "form");
    all.iter()
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

/// §5.1's redirect table for Advanced: every form on the page, posted with valid values, returns
/// to Advanced. The three auto-saves have **no fragment**, so `scroll-restore.js` keeps the exact
/// position and a save never jumps (§5.1.4, qa-31-s3-code M3); Disconnect Phone lands on its
/// group, where its flash is; "New Setup Code" shows the code page, Remove Phone goes to the list.
/// Built from the rendered forms, so a form that moves can't keep a stale target.
#[tokio::test]
async fn advanced_forms_return_to_their_groups() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, _) = app.enrolled_device("Anna").await;
    let html = app
        .get_page(&device_page(id, Section::Advanced), &cookie)
        .await
        .text();
    let advanced = device_page(id, Section::Advanced);
    // (route, the fields a parent sends, the expected Location: path and fragment, or None for a
    // page that renders).
    type Row<'a> = (
        &'a str,
        &'a [(&'a str, &'a str)],
        Option<(String, Option<&'a str>)>,
    );
    let table: [Row; 6] = [
        (
            "hardening",
            &[("disallow_factory_reset", "on")],
            Some((advanced.clone(), None)),
        ),
        (
            "kiosk-block",
            &[("block_activity_start", "on")],
            Some((advanced.clone(), None)),
        ),
        (
            "kiosk-escapes",
            &[("update_fence", "on")],
            Some((advanced.clone(), None)),
        ),
        ("regenerate-code", &[], None),
        (
            "revoke",
            &[("confirm", "yes")],
            Some((
                format!("{advanced}?notice=access_revoked"),
                Some("connection"),
            )),
        ),
        ("delete", &[], Some(("/devices".to_string(), None))),
    ];
    let found = forms(&html);
    let actions: Vec<&str> = found.iter().map(|(a, _)| a.as_str()).collect();
    assert_eq!(
        actions,
        table
            .iter()
            .map(|(route, _, _)| format!("/devices/{id}/{route}"))
            .collect::<Vec<_>>(),
        "every form on Advanced is in the table, in page order"
    );
    for ((action, hidden), (route, fields, expected)) in found.iter().zip(table.iter()) {
        let mut sent: Vec<(&str, &str)> = hidden
            .iter()
            .map(|(k, v)| (k.as_str(), v.as_str()))
            .collect();
        sent.extend_from_slice(fields);
        let res = app
            .request_form(Method::POST, action, Some(&cookie), &sent)
            .await;
        match expected {
            Some((path, fragment)) => assert_redirect(&res, path, *fragment),
            None => {
                assert_eq!(res.status, StatusCode::OK, "{route}");
                assert!(res.text().contains("class=\"enrollment-code\""), "{route}");
                // Its Back link returns to Advanced › Connection.
                assert!(
                    res.text()
                        .contains(&format!("href=\"/devices/{id}/advanced#connection\"")),
                    "{route}"
                );
            }
        }
    }
    let gone: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM devices WHERE id = ?")
        .bind(id)
        .fetch_one(&app.db)
        .await
        .unwrap();
    assert_eq!(gone, 0, "Remove Phone removed it");
}

/// Disconnect Phone and Remove Phone are `<details>` confirms (no JS): the summary is the button,
/// the form inside names the action; Disconnect keeps its required box, and without it nothing
/// happens and the Connection group says so.
#[tokio::test]
async fn destructive_actions_confirm_with_details() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("Anna").await;
    let html = app
        .get_page(&device_page(id, Section::Advanced), &cookie)
        .await
        .text();
    for (action, summary, button) in [
        (
            "revoke",
            "Disconnect Phone&hellip;",
            "Disconnect Phone</button>",
        ),
        ("delete", "Remove Phone&hellip;", "Remove Phone</button>"),
    ] {
        let form_at = html
            .find(&format!("action=\"/devices/{id}/{action}\""))
            .unwrap();
        let details_at = html[..form_at]
            .rfind("<details class=\"confirm cell\">")
            .unwrap();
        let inside = &html[details_at..form_at];
        assert!(inside.contains(summary), "{action}: {inside}");
        assert!(
            !inside.contains("</details>"),
            "{action}: inside its confirm"
        );
        let form = &html[form_at..form_at + html[form_at..].find("</form>").unwrap()];
        assert!(form.contains(button), "{action}");
        assert!(form.contains("class=\"button-destructive\""), "{action}");
    }
    let revoke = &html[html
        .find(&format!("action=\"/devices/{id}/revoke\""))
        .unwrap()..];
    assert!(
        revoke[..revoke.find("</form>").unwrap()]
            .contains("name=\"confirm\" value=\"yes\" required")
    );

    // Without the box: refused, flagged in the group, the phone still connected.
    let res = app
        .request_form(
            Method::POST,
            &format!("/devices/{id}/revoke"),
            Some(&cookie),
            &[],
        )
        .await;
    let location = res.location().unwrap().to_string();
    let page = app
        .get_page(location.split('#').next().unwrap(), &cookie)
        .await
        .text();
    assert!(
        page.contains(
            "<p class=\"flash flash-error\" role=\"status\">Nothing changed: tick the box first.</p>"
        ),
        "{page}"
    );
    let res = app
        .request(Method::GET, "/api/devices/policy", Some(&token), None)
        .await;
    assert_eq!(res.status, StatusCode::OK);
}

/// A live setup code shows on Advanced › Connection as a row with its local end time, never the
/// code; typed codes paused shows as a warning.
#[tokio::test]
async fn a_live_code_shows_on_connection_without_the_code() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, _) = app.enrolled_device("Anna").await;
    let res = app
        .request_form(
            Method::POST,
            &format!("/devices/{id}/regenerate-code"),
            Some(&cookie),
            &[],
        )
        .await;
    assert_eq!(res.status, StatusCode::OK);
    let body = res.text();
    let start = body.find("class=\"enrollment-code\">").unwrap() + 24;
    let code = &body[start..start + 9];
    let html = app
        .get_page(&device_page(id, Section::Advanced), &cookie)
        .await
        .text();
    let row = &html[html.find("data-row=\"live-code\"").unwrap()..];
    let row = &row[..row.find("</div>").unwrap()];
    assert!(row.contains("Setup code"), "{row}");
    assert!(row.contains("Works until "), "{row}");
    // Always a clock time, also past midnight ("00:05 tomorrow", `Zone::until`, M2).
    let until = &row[row.find("Works until ").unwrap() + 12..];
    assert!(until.contains(':'), "{row}");
    assert!(!row.contains("UTC"), "{row}");
    assert!(!html.contains(code));

    // Typed codes paused (too many wrong ones this hour): a warning on Connection, with the end.
    let now = chrono::Utc::now();
    for _ in 0..crate::enrollment::TYPED_PAUSE_FAILURES {
        app.state.enrollment.typed_failed(now);
    }
    assert!(app.state.enrollment.typed_paused_until(now).is_some());
    let html = app
        .get_page(&device_page(id, Section::Advanced), &cookie)
        .await
        .text();
    let warning = &html[html
        .find("data-warning=\"typed-paused\"")
        .expect("the warning")..];
    let warning = &warning[..warning.find("</div>").unwrap()];
    assert!(warning.contains("Typed codes paused until "), "{warning}");
    assert!(
        warning.contains("A setup QR code still works."),
        "{warning}"
    );
}

/// A disconnected phone still shows when it was last in contact (qa-31-s3-code M1): "cut off a
/// minute ago" and "gone for weeks" read differently.
#[tokio::test]
async fn about_keeps_a_disconnected_phones_last_contact() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("Anna").await;
    report(&app, &token, json!({})).await;
    let before = app
        .get_page(&device_page(id, Section::About), &cookie)
        .await
        .text();
    let contact = value(&before, "last-contact").unwrap();
    assert!(contact.contains(" · just now"), "{contact}");
    let res = app
        .request_form(
            Method::POST,
            &format!("/devices/{id}/revoke"),
            Some(&cookie),
            &[("confirm", "yes")],
        )
        .await;
    assert!(res.status.is_redirection());
    let after = app
        .get_page(&device_page(id, Section::About), &cookie)
        .await
        .text();
    assert_eq!(
        value(&after, "status").as_deref(),
        Some("Disconnected · access removed")
    );
    assert_eq!(value(&after, "last-contact"), Some(contact), "{after}");
}

// ---- Step 4: Lock Screen & PINs ----------------------------------------------------------------

/// Sets both PINs as a parent would (the Parent PIN first: the Child PIN needs it).
async fn set_pins(app: &TestApp, cookie: &str, id: i64) {
    for (route, field, pin) in [
        ("parent-pin", "new_pin", "5829317"),
        ("kid-lock", "new_kid_pin", "4826"),
    ] {
        let res = app
            .request_form(
                Method::POST,
                &format!("/devices/{id}/{route}"),
                Some(cookie),
                &[(field, pin)],
            )
            .await;
        assert!(res.status.is_redirection(), "{route}");
    }
}

#[tokio::test]
async fn lock_screen_and_pins_holds_the_moved_forms() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, _) = app.enrolled_device("Anna").await;
    let summary = app
        .get_page(&device_page(id, Section::Summary), &cookie)
        .await
        .text();
    // The device page: one row, its value, and none of the forms.
    let row = &summary[summary.find("data-row=\"lock\"").expect("the row")..];
    let row = &row[..row.find("</a>").unwrap()];
    assert!(row.contains("Lock Screen &amp; PINs"), "{row}");
    assert!(
        row.contains("<span class=\"row-value\">PIN off</span>"),
        "{row}"
    );
    assert!(summary.contains(&format!("href=\"/devices/{id}/lock\"")));
    for gone in [
        "/kid-lock\"",
        "/parent-pin\"",
        "/screen-timeout\"",
        "/lock-wallpaper\"",
        "/lock-screen\"",
        "/lock-screen/apps\"",
        "id=\"screen-lock\"",
        "Reported by the phone",
    ] {
        assert!(!summary.contains(gone), "{gone}");
    }

    let res = app.get_page(&device_page(id, Section::Lock), &cookie).await;
    assert_eq!(res.status, StatusCode::OK);
    let html = res.text();
    assert_eq!(nav_current(&html), "devices");
    assert!(html.contains(&format!("<a class=\"nav-back\" href=\"/devices/{id}\">")));
    assert!(html.contains("<h1 class=\"page-title\">Lock Screen &amp; PINs</h1>"));
    assert!(!html.contains("class=\"app-header\""));
    // The groups in the mockup's order.
    let at = |id: &str| html.find(&format!("<section id=\"{id}\"")).expect(id);
    let order = [
        "child-pin",
        "parent-pin",
        "screen",
        "lock-screen",
        "notifications",
    ]
    .map(at);
    assert!(order.windows(2).all(|w| w[0] < w[1]), "{order:?}");
    assert!(html.contains("<span class=\"row-value\" data-value=\"child-pin\">Off</span>"));
    assert!(html.contains("<span class=\"row-value\" data-value=\"parent-pin\">Not set</span>"));
    // No Child PIN: no Remove; the PIN fields are in a <details> under their rows.
    assert!(!html.contains("Remove Child PIN"));
    for field in ["new_kid_pin", "new_pin"] {
        let input = html.find(&format!("name=\"{field}\"")).unwrap();
        let details = html[..input].rfind("<details class=\"edit cell\"").unwrap();
        assert!(!html[details..input].contains("</details>"), "{field}");
    }
    // The Parent PIN stays off the screen and out of autofill (qa-31-s4-code L2); the Child PIN
    // is read back to the child, so it is plain text.
    let input_of = |name: &str| {
        tags(&html, "input")
            .into_iter()
            .find(|tag| tag.attr("name") == Some(name))
            .unwrap()
    };
    let parent = input_of("new_pin");
    assert_eq!(parent.attr("type"), Some("password"));
    assert_eq!(parent.attr("autocomplete"), Some("new-password"));
    assert_eq!(input_of("new_kid_pin").attr("type"), Some("text"));

    set_pins(&app, &cookie, id).await;
    let summary = app
        .get_page(&device_page(id, Section::Summary), &cookie)
        .await
        .text();
    assert!(summary.contains("<span class=\"row-value\">PIN on</span>"));
    let html = app
        .get_page(&device_page(id, Section::Lock), &cookie)
        .await
        .text();
    assert!(
        html.contains("<span class=\"row-value\" data-value=\"child-pin\">On · 4 digits</span>")
    );
    assert!(html.contains("<span class=\"row-value\" data-value=\"parent-pin\">Set</span>"));
}

/// Remove Child PIN… is a `<details>` confirm (M6): the summary is the button, the form inside
/// names the action.
#[tokio::test]
async fn removing_the_child_pin_confirms_with_details() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, _) = app.enrolled_device("Anna").await;
    set_pins(&app, &cookie, id).await;
    let html = app
        .get_page(&device_page(id, Section::Lock), &cookie)
        .await
        .text();
    let hidden = html
        .find("name=\"clear_kid_pin\"")
        .expect("the remove form");
    let details = html[..hidden]
        .rfind("<details class=\"confirm cell\">")
        .unwrap();
    let inside = &html[details..hidden];
    assert!(!inside.contains("</details>"), "{inside}");
    assert!(inside.contains("<summary class=\"row\">Remove Child PIN&hellip;</summary>"));
    assert!(inside.contains(&format!("action=\"/devices/{id}/kid-lock\"")));
    let form = &html[hidden..hidden + html[hidden..].find("</form>").unwrap()];
    assert!(form.contains("Remove Anna's Child PIN?"), "{form}");
    assert!(form.contains("class=\"button-destructive\">Remove Child PIN</button>"));
}

/// §5.1's redirect table for Lock Screen & PINs: every form on the page, posted with valid values,
/// returns to it. The auto-saves have **no fragment** (scroll-restore keeps the exact place); the
/// two PIN forms land on their group, where the flash is. Built from the rendered forms, so a form
/// that moves can't keep a stale target.
#[tokio::test]
async fn lock_forms_return_to_the_page() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, _) = app.enrolled_device("Anna").await;
    set_pins(&app, &cookie, id).await;
    let html = app
        .get_page(&device_page(id, Section::Lock), &cookie)
        .await
        .text();
    let lock = device_page(id, Section::Lock);
    // (route, the fields a parent sends, the expected Location: path and fragment).
    type Row<'a> = (&'a str, &'a [(&'a str, &'a str)], (String, Option<&'a str>));
    let table: [Row; 7] = [
        (
            "kid-lock",
            &[("new_kid_pin", "7391")],
            (format!("{lock}?notice=saved"), Some("child-pin")),
        ),
        // Remove Child PIN… (its hidden `clear_kid_pin`).
        (
            "kid-lock",
            &[],
            (format!("{lock}?notice=cleared"), Some("child-pin")),
        ),
        (
            "parent-pin",
            &[("new_pin", "6150482")],
            (
                format!("{lock}?notice=parent_pin_saved"),
                Some("parent-pin"),
            ),
        ),
        (
            "screen-timeout",
            &[("screen_timeout_seconds", "120")],
            (lock.clone(), None),
        ),
        ("lock-wallpaper", &[("wallpaper", "")], (lock.clone(), None)),
        ("lock-screen", &[("show_text", "on")], (lock.clone(), None)),
        // Element X, ticked for every new phone (its hidden `package_name`).
        ("lock-screen/apps", &[], (lock.clone(), None)),
    ];
    let found = forms(&html);
    let actions: Vec<&str> = found.iter().map(|(a, _)| a.as_str()).collect();
    assert_eq!(
        actions,
        table
            .iter()
            .map(|(route, _, _)| format!("/devices/{id}/{route}"))
            .collect::<Vec<_>>(),
        "every form on Lock Screen & PINs is in the table, in page order"
    );
    for ((action, hidden), (_, fields, (path, fragment))) in found.iter().zip(table.iter()) {
        let mut sent: Vec<(&str, &str)> = hidden
            .iter()
            .map(|(k, v)| (k.as_str(), v.as_str()))
            .collect();
        sent.extend_from_slice(fields);
        let res = app
            .request_form(Method::POST, action, Some(&cookie), &sent)
            .await;
        assert_redirect(&res, path, *fragment);
    }
    // Each did what it says.
    let (kid, timeout, text): (Option<String>, i64, bool) = sqlx::query_as(
        "SELECT kid_pin_hash, screen_timeout_seconds, lock_glance_text FROM device_policy \
         WHERE device_id = ?",
    )
    .bind(id)
    .fetch_one(&app.db)
    .await
    .unwrap();
    assert_eq!((kid, timeout, text), (None, 120, true));
}

/// Without JS: every auto-saving control has a `<noscript>` Save in its form; the PIN forms save
/// with a button, never on change.
#[tokio::test]
async fn lock_auto_saves_have_a_save_without_js() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, _) = app.enrolled_device("Anna").await;
    let html = app
        .get_page(&device_page(id, Section::Lock), &cookie)
        .await
        .text();
    for name in ["screen_timeout_seconds", "wallpaper", "show_text", "on"] {
        assert!(autosaves(&html, name), "{name}");
        let control = html.find(&format!("name=\"{name}\"")).unwrap();
        let end = html[control..].find("</form>").unwrap() + control;
        assert!(html[control..end].contains("<noscript>"), "{name}");
    }
    assert!(!autosaves(&html, "new_kid_pin"));
    assert!(!autosaves(&html, "new_pin"));
}

/// A phone not set up yet has the page: its PINs can be set before setup.
#[tokio::test]
async fn a_phone_not_set_up_can_get_its_pins_first() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, _) = app.create_device("Mia").await;
    let html = app
        .get_page(&device_page(id, Section::Lock), &cookie)
        .await
        .text();
    assert!(html.contains(&format!("action=\"/devices/{id}/kid-lock\"")));
    assert!(html.contains(&format!("action=\"/devices/{id}/parent-pin\"")));
    assert!(html.contains("None reported yet."));
}

/// "On the phone" says when the phone reported it, so an old report never reads as now
/// (qa-31-s4-code L3).
#[tokio::test]
async fn the_lock_state_says_when_it_was_reported() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("Anna").await;
    set_pins(&app, &cookie, id).await;
    report(
        &app,
        &token,
        json!({ "capabilities": ["pin_lock_v1"],
                "lock_state": { "active": true, "locked": true } }),
    )
    .await;
    // The report is from yesterday (local, Phone Setup's Europe/Oslo).
    sqlx::query(
        "UPDATE device_status SET reported_at = datetime('now', '-1 day') WHERE device_id = ?",
    )
    .bind(id)
    .execute(&app.db)
    .await
    .unwrap();
    let html = app
        .get_page(&device_page(id, Section::Lock), &cookie)
        .await
        .text();
    let row = &html[html
        .find("<span class=\"row-label\">On the phone</span>")
        .expect("the row")..];
    let row = &row[..row.find("</summary>").unwrap()];
    assert!(
        row.contains("<span class=\"row-value\">Locked · yesterday "),
        "{row}"
    );
    assert!(!row.contains("UTC"), "{row}");
}

/// A failed read of the phone's settings is an error page, never "Child PIN · Off"
/// (qa-31-s4-code L4); the other sub-pages fail the same way.
#[tokio::test]
async fn an_unreadable_policy_is_an_error_not_off() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, _) = app.enrolled_device("Anna").await;
    // The settings table is gone: every read of it fails.
    sqlx::query("ALTER TABLE device_policy RENAME TO device_policy_gone")
        .execute(&app.db)
        .await
        .unwrap();
    for section in [Section::Lock, Section::About, Section::Advanced] {
        let res = app.get_page(&device_page(id, section), &cookie).await;
        assert_eq!(res.status, StatusCode::INTERNAL_SERVER_ERROR, "{section:?}");
        assert!(
            res.text().contains("Couldn't read this phone"),
            "{section:?}"
        );
        assert!(!res.text().contains("Not set"), "{section:?}");
    }
}
