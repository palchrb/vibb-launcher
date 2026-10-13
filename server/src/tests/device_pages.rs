//! Design 31 steps 3 and 4: a phone's About This Phone, Advanced and Lock Screen & PINs pages.
//! About is read-only (the connection, sound, software, crash reports, starts without Vibb, the
//! time lock - local times, the exact one in `title`); Advanced holds the forms the device page's
//! Hardening, Play and kiosk, Launcher updates and Access cards had, and Remove Phone; Lock Screen
//! & PINs the Screen lock, Parent PIN and Screen timeout cards, the lock background and the Apps
//! card's "On the lock screen". Their POST routes are unchanged; this checks where each returns to
//! (the redirect table, §5.1) and the confirms.

use axum::http::{Method, StatusCode};
use serde_json::json;

use super::{
    Section, TestApp, assert_redirect, autosaves, device_page, form_action_of, input_checked,
    nav_current, tags,
};

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
    for section in [
        Section::Lock,
        Section::About,
        Section::Advanced,
        Section::Home,
        Section::Filter,
    ] {
        let res = app.get_page(&device_page(id, section), &cookie).await;
        assert_eq!(res.status, StatusCode::INTERNAL_SERVER_ERROR, "{section:?}");
        assert!(
            res.text().contains("Couldn't read this phone"),
            "{section:?}"
        );
        assert!(!res.text().contains("Not set"), "{section:?}");
    }
}

// ---- Step 5: Home Screen and Web Filter --------------------------------------------------------

async fn policy_row(app: &TestApp, id: i64) -> (i64, bool, String, i64, bool) {
    sqlx::query_as(
        "SELECT quick_controls_mask, vpn_filter_enabled, launcher_language, home_columns, \
         dns_log_enabled FROM device_policy WHERE device_id = ?",
    )
    .bind(id)
    .fetch_one(&app.db)
    .await
    .unwrap()
}

/// Every quick control on, as `create_device` makes a phone (the test helper inserts a bare row).
async fn all_quick_controls(app: &TestApp, id: i64) {
    sqlx::query("UPDATE device_policy SET quick_controls_mask = 15 WHERE device_id = ?")
        .bind(id)
        .execute(&app.db)
        .await
        .unwrap();
}

/// The sub-page header and the groups in order; the tab is Devices, no brand bar.
#[track_caller]
fn assert_subpage(html: &str, id: i64, title: &str, groups: &[&str]) {
    assert_eq!(nav_current(html), "devices");
    assert!(html.contains(&format!("<a class=\"nav-back\" href=\"/devices/{id}\">")));
    assert!(html.contains(&format!("<h1 class=\"page-title\">{title}</h1>")));
    assert!(!html.contains("class=\"app-header\""));
    let order: Vec<usize> = groups
        .iter()
        .map(|g| html.find(&format!("<section id=\"{g}\"")).expect(g))
        .collect();
    assert!(order.windows(2).all(|w| w[0] < w[1]), "{order:?}");
}

/// Every auto-saving control has a `<noscript>` Save in its own form (§5.2).
#[track_caller]
fn assert_saves_without_js(html: &str, names: &[&str]) {
    for name in names {
        assert!(autosaves(html, name), "{name}");
        let control = html.find(&format!("name=\"{name}\"")).unwrap();
        let end = html[control..].find("</form>").unwrap() + control;
        assert!(html[control..end].contains("<noscript>"), "{name}");
    }
}

#[tokio::test]
async fn home_screen_holds_the_moved_forms() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, _) = app.enrolled_device("Anna").await;
    let summary = app
        .get_page(&device_page(id, Section::Summary), &cookie)
        .await
        .text();
    // The device page: two rows with their values, none of the forms.
    all_quick_controls(&app, id).await;
    for (key, href, value) in [("home", "home", "3 per row"), ("filter", "filter", "On")] {
        let row = &summary[summary.find(&format!("data-row=\"{key}\"")).expect(key)..];
        let row = &row[..row.find("</a>").unwrap()];
        assert!(
            row.contains(&format!("<span class=\"row-value\">{value}</span>")),
            "{row}"
        );
        assert!(summary.contains(&format!("href=\"/devices/{id}/{href}\" data-row=\"{key}\"")));
    }
    // In the group of rows, after Lock Screen & PINs and before About This Phone.
    let at = |key: &str| summary.find(&format!("data-row=\"{key}\"")).unwrap();
    assert!(at("lock") < at("home") && at("home") < at("filter") && at("filter") < at("about"));
    for gone in [
        "/launcher\"",
        "/wallpapers\"",
        "/policy\"",
        "quick_control_",
        "vpn_filter_enabled",
        "id=\"launcher\"",
        "id=\"wallpapers\"",
        "allow_listener",
    ] {
        assert!(!summary.contains(gone), "{gone}");
    }

    let res = app.get_page(&device_page(id, Section::Home), &cookie).await;
    assert_eq!(res.status, StatusCode::OK);
    let html = res.text();
    assert_subpage(
        &html,
        id,
        "Home Screen",
        &["layout", "wallpapers", "quick-controls"],
    );
    let launcher = format!("/devices/{id}/launcher");
    assert_eq!(form_action_of(&html, "language"), Some(launcher.as_str()));
    assert_eq!(
        form_action_of(&html, "home_columns"),
        Some(launcher.as_str())
    );
    assert_eq!(
        form_action_of(&html, "wallpaper"),
        Some(format!("/devices/{id}/wallpapers").as_str())
    );
    // The four quick controls are one form with its section marker (H1); a new phone has all on.
    let policy = format!("/devices/{id}/policy");
    for name in [
        "quick_control_wifi",
        "quick_control_bluetooth",
        "quick_control_brightness",
        "quick_control_sound",
    ] {
        assert_eq!(form_action_of(&html, name), Some(policy.as_str()), "{name}");
        assert_eq!(input_checked(&html, name, "on"), Some(true), "{name}");
    }
    let quick = &html[html.find("<section id=\"quick-controls\"").unwrap()..];
    assert!(quick.contains("<input type=\"hidden\" name=\"section\" value=\"quick\">"));
    assert!(!html.contains("vpn_filter_enabled"));
    assert_eq!(input_checked(&html, "wallpaper", "1"), Some(true));
    assert_saves_without_js(
        &html,
        &[
            "language",
            "home_columns",
            "wallpaper",
            "quick_control_wifi",
        ],
    );
    // No warning while the phone can read notifications (or hasn't said).
    assert!(!html.contains("Notification access off"));
}

#[tokio::test]
async fn web_filter_holds_the_phones_parts() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("Anna").await;
    let (other, _) = app.enrolled_device("Emil").await;
    for (domain, list_type, device) in [
        ("only-anna.example", "block", Some(id)),
        ("allowed-anna.example", "allow", Some(id)),
        ("only-emil.example", "block", Some(other)),
        ("everyone.example", "block", None),
    ] {
        sqlx::query(
            "INSERT INTO dns_custom_domains (domain, list_type, device_id) VALUES (?, ?, ?)",
        )
        .bind(domain)
        .bind(list_type)
        .bind(device)
        .execute(&app.db)
        .await
        .unwrap();
    }
    // "Ads & tracking" on for everyone, off for Anna.
    sqlx::query("UPDATE dns_blocklists SET enabled = 1 WHERE id = 1")
        .execute(&app.db)
        .await
        .unwrap();
    sqlx::query(
        "INSERT INTO device_blocklist_overrides (device_id, blocklist_id, enabled) VALUES (?, 1, 0)",
    )
    .bind(id)
    .execute(&app.db)
    .await
    .unwrap();

    let res = app
        .get_page(&device_page(id, Section::Filter), &cookie)
        .await;
    assert_eq!(res.status, StatusCode::OK);
    let html = res.text();
    assert_subpage(
        &html,
        id,
        "Web Filter",
        &["filter", "lists", "blocked", "allowed", "log"],
    );
    assert_eq!(
        form_action_of(&html, "vpn_filter_enabled"),
        Some(format!("/devices/{id}/policy").as_str())
    );
    assert_eq!(input_checked(&html, "vpn_filter_enabled", "on"), Some(true));
    let filter = &html[html.find("<section id=\"filter\"").unwrap()..];
    assert!(filter.contains("<input type=\"hidden\" name=\"section\" value=\"filter\">"));
    assert!(!html.contains("quick_control_"));
    // Each list: as everyone has it, or on/off for this phone.
    let list = |list_id: i64| {
        let at = html.find(&format!("data-list=\"{list_id}\"")).unwrap();
        &html[at..at + html[at..].find("</form>").unwrap()]
    };
    assert!(list(1).contains("<option value=\"off\" selected>Off</option>"));
    assert!(list(1).contains("<option value=\"default\">Default (On)</option>"));
    assert!(list(2).contains("<option value=\"default\" selected>Default (Off)</option>"));
    assert!(list(1).contains("<input type=\"hidden\" name=\"back\" value=\"filter\">"));
    // Only this phone's domains, each with Remove; the add rows carry the phone and `back`.
    let group = |id: &str| {
        let at = html.find(&format!("<section id=\"{id}\"")).unwrap();
        &html[at..at + html[at..].find("</section>").unwrap()]
    };
    assert!(group("blocked").contains("only-anna.example"));
    assert!(group("allowed").contains("allowed-anna.example"));
    assert!(!html.contains("only-emil.example") && !html.contains("everyone.example"));
    for g in ["blocked", "allowed"] {
        let g = group(g);
        assert!(g.contains("action=\"/dns/domains/new\""));
        assert!(g.contains(&format!(
            "<input type=\"hidden\" name=\"device_id\" value=\"{id}\">"
        )));
        assert_eq!(
            g.matches("name=\"back\" value=\"filter\"").count(),
            2,
            "{g}"
        );
        let field = tags(g, "input")
            .into_iter()
            .find(|t| t.attr("name") == Some("domain"))
            .unwrap();
        assert!(field.has("required") && field.has("pattern"));
    }
    // The log: off by default, no list; the switch saves itself.
    assert_eq!(input_checked(&html, "dns_log_enabled", "on"), Some(false));
    assert!(!html.contains("data-group=\"log-events\""));
    assert_saves_without_js(&html, &["vpn_filter_enabled", "setting", "dns_log_enabled"]);
    assert!(!autosaves(&html, "domain"));

    // With the log on: the phone's entries, newest first, in local time (the exact one in
    // `title`), a time the phone sent garbled as "Time unknown", its text escaped.
    sqlx::query("UPDATE device_policy SET dns_log_enabled = 1 WHERE device_id = ?")
        .bind(id)
        .execute(&app.db)
        .await
        .unwrap();
    let now = chrono::Utc::now();
    let events = json!([
        { "domain": "ads.example", "category": "Ads & tracking",
          "blocked_at": (now - chrono::Duration::minutes(5)).to_rfc3339() },
        { "domain": "<b>bold</b>.example", "category": "<i>Custom</i>",
          "blocked_at": (now - chrono::Duration::minutes(10)).to_rfc3339() },
        { "domain": "odd.example", "category": "Custom", "blocked_at": "yesterday-ish" },
    ]);
    let res = app
        .request(
            Method::POST,
            "/api/devices/dns-events",
            Some(&token),
            Some(events),
        )
        .await;
    assert_eq!(res.status, StatusCode::NO_CONTENT);
    let html = app
        .get_page(&device_page(id, Section::Filter), &cookie)
        .await
        .text();
    let log = &html[html.find("data-group=\"log-events\"").expect("the log")..];
    let log = &log[..log.find("</section>").unwrap()];
    let ads = log.find("ads.example").unwrap();
    assert!(ads < log.find("bold").unwrap());
    assert!(log.contains("&#60;b&#62;bold&#60;/b&#62;.example") && !log.contains("<b>"));
    assert!(log.contains("&#60;i&#62;Custom&#60;/i&#62;") && !log.contains("<i>"));
    assert!(log.contains(">Time unknown</span>") && !log.contains("yesterday-ish"));
    let row = &log[ads..];
    let row = &row[..row.find("</div>").unwrap()];
    assert!(
        row.contains("<span class=\"row-sub\">Ads &#38; tracking</span>"),
        "{row}"
    );
    assert!(row.contains("title=\""), "{row}");
    assert!(!log.contains("UTC") && !log.contains("+00:00"), "{log}");
}

/// §5.1's redirect table for Home Screen and Web Filter: every form on both pages, posted with
/// valid values, returns to its page with **no fragment** (scroll-restore keeps the exact place,
/// §5.1.4) - the shared domain and list routes through `back=filter`. Built from the rendered
/// forms, so a form that moves can't keep a stale target; then each did what it says.
#[tokio::test]
async fn home_and_filter_forms_return_to_their_page() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, _) = app.enrolled_device("Anna").await;
    for (domain, list_type) in [
        ("old-block.example", "block"),
        ("old-allow.example", "allow"),
    ] {
        sqlx::query(
            "INSERT INTO dns_custom_domains (domain, list_type, device_id) VALUES (?, ?, ?)",
        )
        .bind(domain)
        .bind(list_type)
        .bind(id)
        .execute(&app.db)
        .await
        .unwrap();
    }
    let lists: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM dns_blocklists")
        .fetch_one(&app.db)
        .await
        .unwrap();
    assert_eq!(lists, 4, "the seeded lists");

    type Row<'a> = (String, &'a [(&'a str, &'a str)]);
    let check = |page: Section, table: Vec<Row<'static>>| {
        let app = &app;
        let cookie = &cookie;
        async move {
            let back = device_page(id, page);
            let html = app.get_page(&back, cookie).await.text();
            let found = forms(&html);
            let actions: Vec<&str> = found.iter().map(|(a, _)| a.as_str()).collect();
            assert_eq!(
                actions,
                table.iter().map(|(a, _)| a.as_str()).collect::<Vec<_>>(),
                "every form on {back} is in the table, in page order"
            );
            for ((action, hidden), (_, fields)) in found.iter().zip(table.iter()) {
                let mut sent: Vec<(&str, &str)> = hidden
                    .iter()
                    .map(|(k, v)| (k.as_str(), v.as_str()))
                    .collect();
                sent.extend_from_slice(fields);
                let res = app
                    .request_form(Method::POST, action, Some(cookie), &sent)
                    .await;
                assert_redirect(&res, &back, None);
            }
        }
    };
    let d = |route: &str| format!("/devices/{id}/{route}");
    check(
        Section::Home,
        vec![
            (d("launcher"), &[("language", "en"), ("home_columns", "4")]),
            // Navy only.
            (d("wallpapers"), &[("wallpaper", "1")]),
            (d("policy"), &[("quick_control_wifi", "on")]),
        ],
    )
    .await;
    let (block_id, allow_id): (i64, i64) = sqlx::query_as(
        "SELECT (SELECT id FROM dns_custom_domains WHERE domain = 'old-block.example'), \
                (SELECT id FROM dns_custom_domains WHERE domain = 'old-allow.example')",
    )
    .fetch_one(&app.db)
    .await
    .unwrap();
    let list_ids: Vec<i64> = sqlx::query_scalar("SELECT id FROM dns_blocklists ORDER BY name")
        .fetch_all(&app.db)
        .await
        .unwrap();
    let mut filter: Vec<Row> = vec![(d("policy"), &[])];
    filter.extend(list_ids.iter().map(|list| {
        (
            format!("/dns/devices/{id}/blocklists/{list}/override"),
            &[("setting", "off")] as &[_],
        )
    }));
    filter.extend([
        (format!("/dns/domains/{block_id}/delete"), &[] as &[_]),
        (
            "/dns/domains/new".to_string(),
            &[("domain", "new-block.example")],
        ),
        (format!("/dns/domains/{allow_id}/delete"), &[]),
        (
            "/dns/domains/new".to_string(),
            &[("domain", "New-Allow.Example.")],
        ),
        (format!("/dns/log/{id}"), &[("dns_log_enabled", "on")]),
    ]);
    check(Section::Filter, filter).await;

    // Each did what it says: Home's three, then the filter off, every list off for Anna, the
    // domains swapped, the log on.
    assert_eq!(
        policy_row(&app, id).await,
        (1, false, "en".to_string(), 4, true)
    );
    let ticked: Vec<i64> =
        sqlx::query_scalar("SELECT wallpaper_id FROM device_wallpapers WHERE device_id = ?")
            .bind(id)
            .fetch_all(&app.db)
            .await
            .unwrap();
    assert_eq!(ticked, [1]);
    let off: i64 = sqlx::query_scalar(
        "SELECT COUNT(*) FROM device_blocklist_overrides WHERE device_id = ? AND enabled = 0",
    )
    .bind(id)
    .fetch_one(&app.db)
    .await
    .unwrap();
    assert_eq!(off, 4);
    let domains: Vec<(String, String)> = sqlx::query_as(
        "SELECT domain, list_type FROM dns_custom_domains WHERE device_id = ? ORDER BY domain",
    )
    .bind(id)
    .fetch_all(&app.db)
    .await
    .unwrap();
    assert_eq!(
        domains,
        [
            ("new-allow.example".to_string(), "allow".to_string()),
            ("new-block.example".to_string(), "block".to_string()),
        ]
    );
}

/// H1 (§5.6): the quick controls (Home Screen) and the filter switch (Web Filter) share
/// `/devices/{id}/policy`; a post with a section writes only that section, so saving one page
/// never turns off what the other page set. A post without one (an older tab) writes both.
#[tokio::test]
async fn a_policy_section_leaves_the_other_section_alone() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, _) = app.enrolled_device("Anna").await;
    let post = |fields: Vec<(&'static str, &'static str)>| {
        let app = &app;
        let cookie = &cookie;
        async move {
            app.request_form(
                Method::POST,
                &format!("/devices/{id}/policy"),
                Some(cookie),
                &fields,
            )
            .await
        }
    };
    let state = |app: &TestApp| {
        let db = app.db.clone();
        async move {
            sqlx::query_as::<_, (i64, bool, i64)>(
                "SELECT quick_controls_mask, vpn_filter_enabled, kiosk_desired FROM device_policy \
                 WHERE device_id = ?",
            )
            .bind(id)
            .fetch_one(&db)
            .await
            .unwrap()
        }
    };
    all_quick_controls(&app, id).await;
    assert_eq!(state(&app).await, (15, true, 1));

    // Quick controls: only Wi-Fi; the filter (absent from the form) stays on.
    let res = post(vec![("section", "quick"), ("quick_control_wifi", "on")]).await;
    assert_redirect(&res, &device_page(id, Section::Home), None);
    assert_eq!(state(&app).await, (1, true, 1));
    // The filter off; the quick controls (absent) stay.
    let res = post(vec![("section", "filter")]).await;
    assert_redirect(&res, &device_page(id, Section::Filter), None);
    assert_eq!(state(&app).await, (1, false, 1));
    let res = post(vec![("section", "filter"), ("vpn_filter_enabled", "on")]).await;
    assert_redirect(&res, &device_page(id, Section::Filter), None);
    assert_eq!(state(&app).await, (1, true, 1));
    // No section: both, as before, back to the device page.
    let res = post(vec![("quick_control_sound", "on")]).await;
    assert_redirect(&res, &device_page(id, Section::Summary), None);
    assert_eq!(state(&app).await, (8, false, 1));
    // An unknown section writes nothing; an unknown phone is a 404.
    let res = post(vec![("section", "all"), ("quick_control_wifi", "on")]).await;
    assert_eq!(res.status, StatusCode::BAD_REQUEST);
    assert_eq!(state(&app).await, (8, false, 1));
    let res = app
        .request_form(
            Method::POST,
            "/devices/999/policy",
            Some(&cookie),
            &[("section", "filter")],
        )
        .await;
    assert_eq!(res.status, StatusCode::NOT_FOUND);
}

/// Old bookmarks and links (§3.1, L5): `/dns?device=` and `/dns/log?device=` open the phone's
/// Web Filter page (the log at its group); the global pages link each phone there.
#[tokio::test]
async fn old_per_phone_filter_urls_redirect_to_the_filter_page() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, _) = app.enrolled_device("Anna").await;
    let res = app.get_page(&format!("/dns?device={id}"), &cookie).await;
    assert_redirect(&res, &device_page(id, Section::Filter), None);
    let res = app
        .get_page(&format!("/dns/log?device={id}"), &cookie)
        .await;
    assert_redirect(&res, &device_page(id, Section::Filter), Some("log"));
    for page in ["/dns", "/dns?device=", "/dns/log"] {
        let res = app.get_page(page, &cookie).await;
        assert_eq!(res.status, StatusCode::OK, "{page}");
        let html = res.text();
        assert!(
            html.contains(&format!("href=\"/devices/{id}/filter")),
            "{page}"
        );
        // No per-phone form is left on the global pages.
        assert!(!html.contains("/override\""), "{page}");
        assert!(!html.contains("name=\"device_id\""), "{page}");
        assert!(
            !html.contains(&format!("action=\"/dns/log/{id}\"")),
            "{page}"
        );
    }
    let log = app.get_page("/dns/log", &cookie).await.text();
    assert!(log.contains("<span class=\"row-value\">Log off</span>"));
}

/// The shared routes refuse what they can't save, and the phone comes from the path or the row,
/// never from `back`.
#[tokio::test]
async fn the_filter_routes_refuse_bad_input() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, _) = app.enrolled_device("Anna").await;
    let count = || {
        let db = app.db.clone();
        async move {
            sqlx::query_scalar::<_, i64>("SELECT COUNT(*) FROM dns_custom_domains")
                .fetch_one(&db)
                .await
                .unwrap()
        }
    };
    let add = |fields: Vec<(&'static str, String)>| {
        let app = &app;
        let cookie = &cookie;
        async move {
            let fields: Vec<(&str, &str)> = fields.iter().map(|(k, v)| (*k, v.as_str())).collect();
            app.request_form(Method::POST, "/dns/domains/new", Some(cookie), &fields)
                .await
        }
    };
    let phone = id.to_string();
    for (fields, status) in [
        // Not a domain.
        (
            vec![
                ("domain", "not a domain".to_string()),
                ("device_id", phone.clone()),
            ],
            StatusCode::BAD_REQUEST,
        ),
        (
            vec![("domain", "https://x.example/".to_string())],
            StatusCode::BAD_REQUEST,
        ),
        // A phone id that isn't a number never becomes "every phone".
        (
            vec![
                ("domain", "x.example".to_string()),
                ("device_id", "anna".to_string()),
            ],
            StatusCode::BAD_REQUEST,
        ),
        // An unknown phone.
        (
            vec![
                ("domain", "x.example".to_string()),
                ("device_id", "999".to_string()),
            ],
            StatusCode::NOT_FOUND,
        ),
    ] {
        let res = add(fields.clone()).await;
        assert_eq!(res.status, status, "{fields:?}");
    }
    assert_eq!(count().await, 0);
    // Without `back` a phone's domain returns to /dns, with it to the phone's page; `back` names
    // no phone of its own.
    let res = add(vec![
        ("domain", "x.example".to_string()),
        ("device_id", phone.clone()),
    ])
    .await;
    assert_redirect(&res, "/dns", None);
    let res = add(vec![
        ("domain", "y.example".to_string()),
        ("back", "filter".to_string()),
    ])
    .await;
    assert_redirect(&res, "/dns", None);
    let everyone: Option<i64> =
        sqlx::query_scalar("SELECT device_id FROM dns_custom_domains WHERE domain = 'y.example'")
            .fetch_one(&app.db)
            .await
            .unwrap();
    assert_eq!(everyone, None);
    // Delete: the phone from the row; a second tap changes nothing.
    let x: i64 = sqlx::query_scalar("SELECT id FROM dns_custom_domains WHERE domain = 'x.example'")
        .fetch_one(&app.db)
        .await
        .unwrap();
    for expected in [device_page(id, Section::Filter), "/dns".to_string()] {
        let res = app
            .request_form(
                Method::POST,
                &format!("/dns/domains/{x}/delete"),
                Some(&cookie),
                &[("back", "filter")],
            )
            .await;
        assert_redirect(&res, &expected, None);
    }
    assert_eq!(count().await, 1);

    // The list setting: default / on / off, anything else a 400; an unknown list or phone a 404.
    let set = |path: String, setting: &'static str| {
        let app = &app;
        let cookie = &cookie;
        async move {
            app.request_form(
                Method::POST,
                &path,
                Some(cookie),
                &[("setting", setting), ("back", "filter")],
            )
            .await
        }
    };
    let own = || {
        let db = app.db.clone();
        async move {
            sqlx::query_scalar::<_, bool>(
                "SELECT enabled FROM device_blocklist_overrides WHERE device_id = ? \
                 AND blocklist_id = 2",
            )
            .bind(id)
            .fetch_optional(&db)
            .await
            .unwrap()
        }
    };
    let list = format!("/dns/devices/{id}/blocklists/2/override");
    for (setting, stored) in [("on", Some(true)), ("off", Some(false)), ("default", None)] {
        let res = set(list.clone(), setting).await;
        assert_redirect(&res, &device_page(id, Section::Filter), None);
        assert_eq!(own().await, stored, "{setting}");
    }
    assert_eq!(
        set(list.clone(), "maybe").await.status,
        StatusCode::BAD_REQUEST
    );
    assert_eq!(
        set(format!("/dns/devices/{id}/blocklists/99/override"), "on")
            .await
            .status,
        StatusCode::NOT_FOUND
    );
    assert_eq!(
        set("/dns/devices/999/blocklists/2/override".to_string(), "on")
            .await
            .status,
        StatusCode::NOT_FOUND
    );
    assert_eq!(own().await, None);
}

/// A failed read is an error page, never an empty grid or list: a Save from an empty wallpaper
/// grid would untick every wallpaper, an empty domain list reads as "nothing blocked".
#[tokio::test]
async fn home_and_filter_fail_on_an_unreadable_part() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, _) = app.enrolled_device("Anna").await;
    for (table, section) in [
        ("device_wallpapers", Section::Home),
        ("dns_blocklists", Section::Filter),
        ("dns_custom_domains", Section::Filter),
    ] {
        sqlx::query(&format!("ALTER TABLE {table} RENAME TO {table}_gone"))
            .execute(&app.db)
            .await
            .unwrap();
        let res = app.get_page(&device_page(id, section), &cookie).await;
        assert_eq!(res.status, StatusCode::INTERNAL_SERVER_ERROR, "{table}");
        assert!(res.text().contains("Couldn't read this phone"), "{table}");
        sqlx::query(&format!("ALTER TABLE {table}_gone RENAME TO {table}"))
            .execute(&app.db)
            .await
            .unwrap();
    }
}

/// A phone not set up yet has both pages: what is saved reaches it with its first settings.
#[tokio::test]
async fn a_phone_not_set_up_has_home_and_filter() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, _) = app.create_device("Mia").await;
    for section in [Section::Home, Section::Filter] {
        let res = app.get_page(&device_page(id, section), &cookie).await;
        assert_eq!(res.status, StatusCode::OK, "{section:?}");
    }
    for section in [Section::Home, Section::Filter] {
        let res = app.get_page(&device_page(999, section), &cookie).await;
        assert_eq!(res.status, StatusCode::NOT_FOUND, "{section:?}");
    }
}
