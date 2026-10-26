//! Design 31 steps 3 and 4: a phone's About This Phone, Advanced and Lock Screen & PINs pages.
//! About is read-only (the connection, sound, software, crash reports, starts without Vibb, the
//! time lock - local times, the exact one in `title`); Advanced holds the forms the device page's
//! Hardening, Play and kiosk, Launcher updates and Access cards had, and Remove Phone; Lock Screen
//! & PINs the Screen lock, Parent PIN and Screen timeout cards, the lock background and the Apps
//! card's "On the lock screen"; steps 5 and 6 Home Screen, Web Filter and Apps (the Apps card).
//! Their POST routes are unchanged; this checks where each returns to (the redirect table, §5.1)
//! and the confirms.

use axum::http::{Method, StatusCode};
use serde_json::json;

use super::{
    Section, TestApp, assert_redirect, autosaves, device_page, form_action_of, input_checked,
    nav_current, tags,
};

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
    app.post_status(
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
    assert!(summary.contains("<span class=\"row-value\">Vibb One 0.23.6</span>"));
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
    app.post_status(
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
    assert!(advanced.contains(&format!("action=\"/devices/{id}/hardening#restrictions\"")));
    assert!(advanced.contains(&format!("action=\"/devices/{id}/delete\"")));
    for missing in [
        "/kiosk-block",
        "/kiosk-escapes",
        "/revoke",
        "/regenerate-code",
    ] {
        assert!(!advanced.contains(missing), "{missing}");
    }
    // The summary's Connect This Phone group (design 31 step 10) has the code.
    let summary = app
        .get_page(&device_page(id, Section::Summary), &cookie)
        .await
        .text();
    assert!(summary.contains("<section id=\"access\""), "{summary}");
    assert!(summary.contains(&format!("action=\"/devices/{id}/regenerate-code\"")));
}

#[tokio::test]
async fn unknown_phones_are_404() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    for section in [
        Section::Summary,
        Section::About,
        Section::Advanced,
        Section::Lock,
    ] {
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
/// to Advanced. The three auto-saves return to their groups, the fragment their actions carry too
/// (§5.1.4: without JS the answer lands on the switch, with JS `scroll-restore.js` keeps the exact
/// position - the browser pass of 2026-10-14); Disconnect Phone lands on its
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
            "hardening#restrictions",
            &[("disallow_factory_reset", "on")],
            Some((advanced.clone(), Some("restrictions"))),
        ),
        (
            "kiosk-block#app-screens",
            &[("block_activity_start", "on")],
            Some((advanced.clone(), Some("app-screens"))),
        ),
        (
            "kiosk-escapes#updates",
            &[("update_fence", "on")],
            Some((advanced.clone(), Some("updates"))),
        ),
        ("regenerate-code", &[], None),
        (
            "revoke#connection",
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
            Some((path, fragment)) => {
                assert_redirect(&res, path, *fragment);
                app.assert_lands(&res, &cookie).await;
            }
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
            .find(&format!("action=\"/devices/{id}/{action}"))
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
        .find(&format!("action=\"/devices/{id}/revoke#connection\""))
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
    app.post_status(&token, json!({})).await;
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
    assert!(inside.contains(&format!("action=\"/devices/{id}/kid-lock#child-pin\"")));
    let form = &html[hidden..hidden + html[hidden..].find("</form>").unwrap()];
    assert!(form.contains("Remove Anna's Child PIN?"), "{form}");
    assert!(form.contains("class=\"button-destructive\">Remove Child PIN</button>"));
}

/// §5.1's redirect table for Lock Screen & PINs: every form on the page, posted with valid values,
/// returns to it, at its group or row - the fragment its action carries too (§5.1.4: without JS on
/// the form, with JS scroll-restore keeps the exact place); the two PIN forms' flash is in their
/// group. Built from the rendered forms, so a form that moves can't keep a stale target.
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
    let table: [Row; 9] = [
        (
            "kid-lock#child-pin",
            &[("new_kid_pin", "7391")],
            (format!("{lock}?notice=saved"), Some("child-pin")),
        ),
        // Design 59: the lock switch, back to its row.
        (
            "kid-lock/keyguard#lock-keyguard",
            &[("keyguard", "android")],
            (lock.clone(), Some("lock-keyguard")),
        ),
        // "Show Child PIN…": answered with the page itself on its own path (no redirect, the
        // PIN in no address), the form at its own row's fragment (qa-59-step2-code S2P-3).
        (
            "lock#show-pin",
            &[("password", "correct horse battery staple")],
            (lock.clone(), Some("show-pin")),
        ),
        // Remove Child PIN… (its hidden `clear_kid_pin`).
        (
            "kid-lock#child-pin",
            &[],
            (format!("{lock}?notice=cleared"), Some("child-pin")),
        ),
        (
            "parent-pin#parent-pin",
            &[("new_pin", "6150482")],
            (
                format!("{lock}?notice=parent_pin_saved"),
                Some("parent-pin"),
            ),
        ),
        (
            "screen-timeout#screen",
            &[("screen_timeout_seconds", "120")],
            (lock.clone(), Some("screen")),
        ),
        (
            "lock-wallpaper#lock-wallpaper",
            &[("wallpaper", "")],
            (lock.clone(), Some("lock-wallpaper")),
        ),
        // Launcher round 7 §3: the wake switch, back to its cell.
        (
            "lock-wake#lock-wake",
            &[("on", "on")],
            (lock.clone(), Some("lock-wake")),
        ),
        // Element X, on for every new phone (its hidden `package_name`); listed only for being
        // on (no report yet): kept on here with its text (2026-10-21's one select per app), back
        // to its row (Off's landing on the group is tests::lock_glance's).
        (
            "lock-screen/apps#notifications",
            &[("show", "text")],
            (lock.clone(), Some("lock-screen-app-io.element.android.x")),
        ),
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
        if *action == format!("{lock}#show-pin") {
            assert_eq!(res.status, StatusCode::OK, "{action}");
            assert!(res.text().contains("data-shown=\"child-pin\""), "{action}");
            continue;
        }
        assert_redirect(&res, path, *fragment);
        app.assert_lands(&res, &cookie).await;
    }
    // Each did what it says.
    let (kid, timeout, text, wake): (Option<String>, i64, bool, bool) = sqlx::query_as(
        "SELECT kid_pin_hash, screen_timeout_seconds, \
         (SELECT show_text FROM device_lock_glance_apps g WHERE g.device_id = p.device_id), \
         lock_wake_on_notification FROM device_policy p WHERE device_id = ?",
    )
    .bind(id)
    .fetch_one(&app.db)
    .await
    .unwrap();
    assert_eq!((kid, timeout, text, wake), (None, 120, true, true));
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
    for name in ["screen_timeout_seconds", "wallpaper", "show", "on"] {
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
    assert!(html.contains(&format!("action=\"/devices/{id}/kid-lock#child-pin\"")));
    assert!(html.contains(&format!("action=\"/devices/{id}/parent-pin#parent-pin\"")));
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
    app.post_status(
        &token,
        json!({ "capabilities": ["pin_lock_v1"],
                "lock_state": { "active": true, "locked": true } }),
    )
    .await;
    // The report is from yesterday noon, local (Phone Setup's Europe/Oslo), by the zone's own
    // dates: `datetime('now', '-1 day')` was "today" on the night the clocks went back
    // (qa-pwa-fixes-d N-2).
    use chrono::TimeZone;
    let oslo: chrono_tz::Tz = "Europe/Oslo".parse().unwrap();
    let yesterday =
        chrono::Utc::now().with_timezone(&oslo).date_naive() - chrono::Duration::days(1);
    let noon = oslo
        .from_local_datetime(&yesterday.and_hms_opt(12, 0, 0).unwrap())
        .single()
        .unwrap()
        .with_timezone(&chrono::Utc);
    sqlx::query("UPDATE device_status SET reported_at = ? WHERE device_id = ?")
        .bind(noon.format("%Y-%m-%d %H:%M:%S").to_string())
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
    let launcher = format!("/devices/{id}/launcher#layout");
    assert_eq!(form_action_of(&html, "language"), Some(launcher.as_str()));
    assert_eq!(
        form_action_of(&html, "home_columns"),
        Some(launcher.as_str())
    );
    // Design 34 P12: the clock format is in the same form.
    assert_eq!(
        form_action_of(&html, "clock_format"),
        Some(launcher.as_str())
    );
    assert_eq!(
        form_action_of(&html, "wallpaper"),
        Some(format!("/devices/{id}/wallpapers#wallpapers").as_str())
    );
    // The five quick controls are one form with its section marker (H1); a new phone has all on but
    // touch sounds (design r9 §4: they need the app-op first).
    let policy = format!("/devices/{id}/policy#quick-controls");
    for (name, on) in [
        ("quick_control_wifi", true),
        ("quick_control_bluetooth", true),
        ("quick_control_brightness", true),
        ("quick_control_sound", true),
        ("quick_control_touch", false),
    ] {
        assert_eq!(form_action_of(&html, name), Some(policy.as_str()), "{name}");
        assert_eq!(input_checked(&html, name, "on"), Some(on), "{name}");
        // Each saves in place (data-autosave), never a plain submit that jumps to the top (qa-r9-code R9C-2).
        assert!(autosaves(&html, name), "{name}");
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
            "clock_format",
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
        Some(format!("/devices/{id}/policy#filter").as_str())
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
    for name in ["blocked", "allowed"] {
        let g = group(name);
        assert!(g.contains(&format!("action=\"/dns/domains/new#{name}\"")));
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
    // On a whole minute, so the clock and the exact time can be checked (Phone Setup's zone is
    // Europe/Oslo; qa-31-s5-code L3).
    let ads_at = chrono::DurationRound::duration_trunc(
        now - chrono::Duration::minutes(5),
        chrono::Duration::minutes(1),
    )
    .unwrap();
    let local = ads_at.with_timezone(&chrono_tz::Europe::Oslo);
    // In the order the phone queued them: a garbled time last, as it arrived. A domain that
    // isn't a hostname is never stored (security review L3).
    let events = json!([
        { "domain": "ads.example", "category": "Ads & tracking",
          "blocked_at": ads_at.to_rfc3339() },
        { "domain": "<b>bold</b>.example", "category": "Custom",
          "blocked_at": (now - chrono::Duration::minutes(10)).to_rfc3339() },
        { "domain": "bold.example", "category": "<i>Custom</i>",
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
    // Newest first by the phone's time; an unreadable time is stored as the arrival (L3).
    assert!(log.find("odd.example").unwrap() < ads);
    assert!(ads < log.find("bold.example").unwrap());
    assert!(!log.contains("&#60;b&#62;") && !log.contains("<b>"));
    assert!(log.contains("&#60;i&#62;Custom&#60;/i&#62;") && !log.contains("<i>"));
    assert!(!log.contains("Time unknown") && !log.contains("yesterday-ish"));
    let row = &log[ads..];
    let row = &row[..row.find("</div>").unwrap()];
    assert!(
        row.contains("<span class=\"row-sub\">Ads &#38; tracking</span>"),
        "{row}"
    );
    // The local clock time, the exact local time in `title`.
    assert!(
        row.contains(&format!(" title=\"{}", local.format("%Y-%m-%d %H:%M:%S"))),
        "{row}"
    );
    assert!(
        row.contains(&format!("{}</span>", local.format("%H:%M"))),
        "{row}"
    );
    assert!(!log.contains("UTC") && !log.contains("+00:00"), "{log}");
}

/// §5.1's redirect table for Home Screen and Web Filter: every form on both pages, posted with
/// valid values, returns to its page at its group - the fragment its action carries (§5.1.4:
/// without JS on the form, with JS scroll-restore keeps the exact place) - the shared domain and
/// list routes through `back=filter`. Built from the rendered forms, so a form that moves can't
/// keep a stale target; then each did what it says.
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
                assert_redirect(&res, &back, action.split_once('#').map(|(_, group)| group));
                app.assert_lands(&res, cookie).await;
            }
        }
    };
    let d = |route: &str| format!("/devices/{id}/{route}");
    check(
        Section::Home,
        vec![
            (
                d("launcher#layout"),
                &[
                    ("language", "en"),
                    ("home_columns", "4"),
                    ("clock_format", "24"),
                ],
            ),
            // Navy only.
            (d("wallpapers#wallpapers"), &[("wallpaper", "1")]),
            (d("policy#quick-controls"), &[("quick_control_wifi", "on")]),
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
    let mut filter: Vec<Row> = vec![(d("policy#filter"), &[])];
    filter.extend(list_ids.iter().map(|list| {
        (
            format!("/dns/devices/{id}/blocklists/{list}/override#lists"),
            &[("setting", "off")] as &[_],
        )
    }));
    filter.extend([
        (
            format!("/dns/domains/{block_id}/delete#blocked"),
            &[] as &[_],
        ),
        (
            "/dns/domains/new#blocked".to_string(),
            &[("domain", "new-block.example")],
        ),
        (format!("/dns/domains/{allow_id}/delete#allowed"), &[]),
        (
            "/dns/domains/new#allowed".to_string(),
            &[("domain", "New-Allow.Example.")],
        ),
        (format!("/dns/log/{id}#log"), &[("dns_log_enabled", "on")]),
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
    assert_redirect(
        &res,
        &device_page(id, Section::Home),
        Some("quick-controls"),
    );
    assert_eq!(state(&app).await, (1, true, 1));
    // The filter off; the quick controls (absent) stay.
    let res = post(vec![("section", "filter")]).await;
    assert_redirect(&res, &device_page(id, Section::Filter), Some("filter"));
    assert_eq!(state(&app).await, (1, false, 1));
    let res = post(vec![("section", "filter"), ("vpn_filter_enabled", "on")]).await;
    assert_redirect(&res, &device_page(id, Section::Filter), Some("filter"));
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
    assert!(log.contains("<span class=\"row-value\">Off</span>"));
    // The global page's domain fields check the shape before posting too (qa-31-s5-code L1).
    let global = app.get_page("/dns", &cookie).await.text();
    let fields: Vec<_> = tags(&global, "input")
        .into_iter()
        .filter(|t| t.attr("name") == Some("domain"))
        .collect();
    assert_eq!(fields.len(), 2);
    assert!(fields.iter().all(|t| t.has("pattern") && t.has("title")));
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
        // Not a domain: back to its group with the refusal (step 14), nothing added.
        (
            vec![
                ("domain", "not a domain".to_string()),
                ("device_id", phone.clone()),
            ],
            StatusCode::SEE_OTHER,
        ),
        (
            vec![("domain", "https://x.example/".to_string())],
            StatusCode::SEE_OTHER,
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
    // From the phone's page (qa-31-s5-code L1): back to the group with the refusal under its add
    // row, nothing added.
    for (list_type, group) in [("block", "blocked"), ("allow", "allowed")] {
        let res = add(vec![
            ("domain", "https://www.example.com/watch?v=1".to_string()),
            ("list_type", list_type.to_string()),
            ("device_id", phone.clone()),
            ("back", "filter".to_string()),
        ])
        .await;
        let page = format!(
            "{}?notice=not_a_domain_{list_type}",
            device_page(id, Section::Filter)
        );
        assert_redirect(&res, &page, Some(group));
        let html = app.get_page(&page, &cookie).await.text();
        let at = html.find(&format!("<section id=\"{group}\"")).unwrap();
        let section = &html[at..at + html[at..].find("</section>").unwrap()];
        let flash = section
            .find(&format!("data-flash=\"{group}\""))
            .expect("the refusal in its group");
        assert!(section[..flash].contains(&format!("action=\"/dns/domains/new#{group}\"")));
        assert!(section[flash..].contains("isn't a domain name"));
        assert_eq!(html.matches("data-flash=").count(), 1);
    }
    let html = app
        .get_page(
            &format!("{}?notice=bogus", device_page(id, Section::Filter)),
            &cookie,
        )
        .await
        .text();
    assert!(!html.contains("data-flash="));
    assert_eq!(count().await, 0);
    // Without `back` a phone's domain returns to /dns, with it to the phone's page; `back` names
    // no phone of its own.
    let res = add(vec![
        ("domain", "x.example".to_string()),
        ("device_id", phone.clone()),
    ])
    .await;
    assert_redirect(&res, "/dns", Some("blocked"));
    let res = add(vec![
        ("domain", "y.example".to_string()),
        ("back", "filter".to_string()),
    ])
    .await;
    assert_redirect(&res, "/dns", Some("blocked"));
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
    // A double tap (qa-31-s5-code M1): the second post deletes nothing and still lands on the
    // phone's page, from the form's `device_id`.
    for _ in 0..2 {
        let res = app
            .request_form(
                Method::POST,
                &format!("/dns/domains/{x}/delete"),
                Some(&cookie),
                &[("back", "filter"), ("device_id", &phone)],
            )
            .await;
        assert_redirect(&res, &device_page(id, Section::Filter), Some("blocked"));
    }
    assert_eq!(count().await, 1);
    // A form `device_id` naming another phone never chooses the row (the path does) nor, when a
    // row is deleted, the page (the row's phone does).
    let (emil, _) = app.enrolled_device("Emil").await;
    sqlx::query(
        "INSERT INTO dns_custom_domains (domain, list_type, device_id) VALUES \
         ('emil.example', 'block', ?), ('anna.example', 'block', ?)",
    )
    .bind(emil)
    .bind(id)
    .execute(&app.db)
    .await
    .unwrap();
    let anna_row: i64 =
        sqlx::query_scalar("SELECT id FROM dns_custom_domains WHERE domain = 'anna.example'")
            .fetch_one(&app.db)
            .await
            .unwrap();
    let emil_text = emil.to_string();
    let res = app
        .request_form(
            Method::POST,
            &format!("/dns/domains/{anna_row}/delete"),
            Some(&cookie),
            &[("back", "filter"), ("device_id", &emil_text)],
        )
        .await;
    assert_redirect(&res, &device_page(id, Section::Filter), Some("blocked"));
    let left: Vec<String> = sqlx::query_scalar(
        "SELECT domain FROM dns_custom_domains WHERE device_id IS NOT NULL ORDER BY domain",
    )
    .fetch_all(&app.db)
    .await
    .unwrap();
    assert_eq!(left, ["emil.example"]);
    sqlx::query("DELETE FROM dns_custom_domains WHERE domain = 'emil.example'")
        .execute(&app.db)
        .await
        .unwrap();
    // The same domain twice in one list is kept once (qa-31-s5-code N4).
    for _ in 0..2 {
        let res = add(vec![
            ("domain", "twice.example".to_string()),
            ("device_id", phone.clone()),
            ("back", "filter".to_string()),
        ])
        .await;
        assert_redirect(&res, &device_page(id, Section::Filter), Some("blocked"));
    }
    let twice: i64 = sqlx::query_scalar(
        "SELECT COUNT(*) FROM dns_custom_domains WHERE domain = 'twice.example'",
    )
    .fetch_one(&app.db)
    .await
    .unwrap();
    assert_eq!(twice, 1);
    sqlx::query("DELETE FROM dns_custom_domains WHERE domain = 'twice.example'")
        .execute(&app.db)
        .await
        .unwrap();
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
        assert_redirect(&res, &device_page(id, Section::Filter), Some("lists"));
        assert_eq!(own().await, stored, "{setting}");
    }
    assert_eq!(
        set(list.clone(), "maybe").await.status,
        StatusCode::BAD_REQUEST
    );
    // `default` too (qa-31-s5-code L4: a DELETE that matches nothing is no foreign key).
    for setting in ["on", "default"] {
        assert_eq!(
            set(format!("/dns/devices/{id}/blocklists/99/override"), setting)
                .await
                .status,
            StatusCode::NOT_FOUND,
            "{setting}"
        );
        assert_eq!(
            set(
                "/dns/devices/999/blocklists/2/override".to_string(),
                setting
            )
            .await
            .status,
            StatusCode::NOT_FOUND,
            "{setting}"
        );
    }
    assert_eq!(own().await, None);
}

/// A failed read is an error page, never an empty grid or list: a Save from an empty wallpaper
/// grid would untick every wallpaper, an empty domain list reads as "nothing blocked".
#[tokio::test]
async fn home_and_filter_fail_on_an_unreadable_part() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, _) = app.enrolled_device("Anna").await;
    // The log is read only while it is on (qa-31-s5-code L2: an empty log reads "all clear").
    sqlx::query("UPDATE device_policy SET dns_log_enabled = 1 WHERE device_id = ?")
        .bind(id)
        .execute(&app.db)
        .await
        .unwrap();
    for (table, section) in [
        ("device_wallpapers", Section::Home),
        ("dns_blocklists", Section::Filter),
        ("dns_custom_domains", Section::Filter),
        ("device_dns_events", Section::Filter),
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

// ---- Step 6: Device › Apps ----------------------------------------------------------------------

/// Anna's apps: the phone reports its clock (preinstalled; allowed by the first report's
/// defaults) and a game (not allowed, a name with markup); the catalog has Vibb, Element X (chosen
/// for her, not installed yet) and Notes (no package, no release). Returns (Anna, Vibb, Element X,
/// Notes).
async fn apps_phone(app: &TestApp) -> (i64, i64, i64, i64) {
    let (id, token) = app.enrolled_device("Anna").await;
    let catalog = |name: &'static str,
                   package: &'static str,
                   tag: Option<&'static str>,
                   launcher| async move {
        sqlx::query_scalar::<_, i64>(
            "INSERT INTO tracked_apps (name, package_name, github_repo, latest_release_tag, \
             is_launcher) VALUES (?, ?, 'owner/repo', ?, ?) RETURNING id",
        )
        .bind(name)
        .bind(package)
        .bind(tag)
        .bind(launcher)
        .fetch_one(&app.db)
        .await
        .unwrap()
    };
    let vibb = catalog(
        "Vibb One",
        "me.vibb.launcher",
        Some("launcher-v1.0.0"),
        true,
    )
    .await;
    let element = catalog("Element X", "io.element.android.x", Some("v26.10.1"), false).await;
    let notes = catalog("Notes", "", None, false).await;
    sqlx::query("INSERT INTO device_tracked_apps (device_id, tracked_app_id) VALUES (?, ?)")
        .bind(id)
        .bind(element)
        .execute(&app.db)
        .await
        .unwrap();
    app.post_status(
        &token,
        json!({ "installed_apps": [
            { "package_name": "com.android.deskclock", "label": "Clock", "preinstalled": true },
            { "package_name": "org.example.game", "label": "<b>Game</b>", "preinstalled": false },
        ] }),
    )
    .await;
    (id, vibb, element, notes)
}

/// The text of the `<section id="{id}"` up to its end.
fn section<'a>(html: &'a str, id: &str) -> &'a str {
    let at = html
        .find(&format!("<section id=\"{id}\""))
        .unwrap_or_else(|| panic!("no section {id}"));
    &html[at..at + html[at..].find("</section>").unwrap()]
}

/// Every auto-saving form on the page has a `<noscript>` Save (§5.2).
#[track_caller]
fn every_auto_save_has_a_save_without_js(html: &str) {
    let mut seen = 0;
    for form in tags(html, "form") {
        let body = &html[form.start..form.start + html[form.start..].find("</form>").unwrap()];
        if body.contains("data-autosave") {
            seen += 1;
            assert!(body.contains("<noscript>"), "{body}");
        }
    }
    assert!(seen > 0);
}

#[tokio::test]
async fn apps_holds_the_moved_forms() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, _, _, notes) = apps_phone(&app).await;

    // The device page: a row with the count, none of the card's forms or its reload.
    let summary = app
        .get_page(&device_page(id, Section::Summary), &cookie)
        .await
        .text();
    let row = &summary[summary.find("data-row=\"apps\"").expect("the Apps row")..];
    let row = &row[..row.find("</a>").unwrap()];
    assert!(
        row.contains("<span class=\"row-value\">2 allowed</span>"),
        "{row}"
    );
    assert!(summary.contains(&format!("href=\"/devices/{id}/apps\"")));
    for gone in [
        "/apps/toggle",
        "/app-updates",
        "/apps/display",
        "auto-reload.js",
    ] {
        assert!(!summary.contains(gone), "{gone}");
    }

    let html = app
        .get_page(&device_page(id, Section::Apps), &cookie)
        .await
        .text();
    assert_subpage(&html, id, "Apps", &["allowed", "not-allowed", "downloads"]);
    // Allowed: Vibb first and always on (no switch), the clock, Element X waiting to install.
    let allowed = section(&html, "allowed");
    let at = |part: &str, anchor: &str| {
        part.find(&format!("id=\"{anchor}\""))
            .unwrap_or_else(|| panic!("{anchor}: {part}"))
    };
    let launcher = &allowed[at(allowed, "app-me.vibb.launcher")..];
    let launcher = &launcher[..launcher.find("</div>\n").unwrap()];
    assert!(
        launcher.contains("Always on") && !launcher.contains("<form"),
        "{launcher}"
    );
    assert!(at(allowed, "app-me.vibb.launcher") < at(allowed, "app-com.android.deskclock"));
    assert!(allowed.contains("id=\"app-io.element.android.x\""));
    let element = &allowed[at(allowed, "app-io.element.android.x")..];
    assert!(element.contains("Not installed"), "{element}");
    // Not allowed: the game (its name escaped) and Notes, which has no release yet.
    let not_allowed = section(&html, "not-allowed");
    assert!(not_allowed.contains("id=\"app-org.example.game\""));
    assert!(not_allowed.contains("&#60;b&#62;Game"), "{not_allowed}");
    assert!(!html.contains("<b>Game"));
    let notes_row = &not_allowed[at(not_allowed, &format!("catalog-{notes}"))..];
    assert!(
        notes_row.contains("Not installed · No release yet"),
        "{notes_row}"
    );
    let switches: Vec<bool> = tags(&html, "input")
        .into_iter()
        .filter(|t| t.attr("name") == Some("selected"))
        .map(|t| t.has("checked"))
        .collect();
    assert_eq!(
        switches,
        [true, true, false, false],
        "on in Allowed, off below"
    );
    // Name and icon under each allowed app with a package, never for Vibb or one not allowed.
    for package in ["com.android.deskclock", "io.element.android.x"] {
        assert!(
            allowed.contains(&format!("id=\"display-{package}\"")),
            "{package}"
        );
    }
    assert!(!html.contains("id=\"display-org.example.game\""));
    assert!(!html.contains("id=\"display-me.vibb.launcher\""));
    // Every row's id once; no search field (§6.0); the keyboard note behind the ⓘ.
    let mut ids: Vec<&str> = tags(&html, "div")
        .into_iter()
        .filter_map(|t| t.attr("data-app"))
        .collect();
    let all = ids.len();
    ids.sort_unstable();
    ids.dedup();
    assert_eq!(ids.len(), all);
    assert_eq!(all, 5);
    assert!(!html.contains("type=\"search\""));
    let footer = &not_allowed[not_allowed.find("<details class=\"info\">").unwrap()..];
    assert!(
        footer.contains("keyboard or accessibility service"),
        "{footer}"
    );
    // The Wi-Fi switch, on for a new phone; nothing installs, so no reload.
    assert_eq!(
        form_action_of(&html, "app_updates_wifi_only"),
        Some(format!("/devices/{id}/app-updates#downloads").as_str())
    );
    assert_eq!(
        input_checked(&html, "app_updates_wifi_only", "on"),
        Some(true)
    );
    assert!(!html.contains("auto-reload.js"));
    every_auto_save_has_a_save_without_js(&html);
    for name in ["selected", "app_updates_wifi_only"] {
        assert!(autosaves(&html, name), "{name}");
    }
}

/// §5.1's redirect table for Device › Apps, built from the rendered forms: a switch returns to its
/// app's row (`#app-<package>`, `#catalog-<id>` without a package) - the row changes group, and
/// the fragment finds it again with or without JS -, a Name and icon save to the same row (its
/// action has the fragment too, for a refusal rendered in the answer), the Wi-Fi switch to its
/// group at the bottom. Every fragment names an element of the page; then each did what it says,
/// on Anna only (the phone from the path).
#[tokio::test]
async fn apps_forms_return_to_their_rows() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, _, element, notes) = apps_phone(&app).await;
    let (emil, _) = app.enrolled_device("Emil").await;
    let page = device_page(id, Section::Apps);
    let html = app.get_page(&page, &cookie).await.text();
    let (mut toggles, mut names, mut updates) = (0, 0, 0);
    for (action, hidden) in forms(&html) {
        let field = |name: &str| {
            hidden
                .iter()
                .find(|(k, _)| k == name)
                .map(|(_, v)| v.as_str())
        };
        let (target, action_fragment) = match action.split_once('#') {
            Some((target, fragment)) => (target.to_string(), Some(fragment.to_string())),
            None => (action.clone(), None),
        };
        let mut sent: Vec<(&str, &str)> = hidden
            .iter()
            .map(|(k, v)| (k.as_str(), v.as_str()))
            .collect();
        let expected = if target == format!("/devices/{id}/apps/toggle") {
            toggles += 1;
            assert_eq!(field("back"), Some("apps"));
            // Flip it: on what was off.
            let package = field("package_name").unwrap();
            let row = match package {
                "" => format!("catalog-{}", field("tracked_app_id").unwrap()),
                package => format!("app-{package}"),
            };
            assert_eq!(action_fragment.as_deref(), Some(row.as_str()));
            let was_on = html[html.find(&format!("id=\"{row}\"")).unwrap()..]
                .split("</label>")
                .next()
                .unwrap()
                .contains(" checked");
            if !was_on {
                sent.push(("selected", "on"));
            }
            row
        } else if target == format!("/devices/{id}/apps/display") {
            names += 1;
            sent.extend([("label", "Mine"), ("icon", "chat"), ("color", "auto")]);
            let row = format!("app-{}", field("package_name").unwrap());
            assert_eq!(action_fragment.as_deref(), Some(row.as_str()));
            row
        } else if target == format!("/devices/{id}/app-updates") {
            updates += 1;
            assert_eq!(action_fragment.as_deref(), Some("downloads"));
            "downloads".to_string()
        } else {
            panic!("a form the table doesn't know: {action}");
        };
        assert!(
            html.contains(&format!("id=\"{expected}\"")),
            "{expected} names nothing on the page"
        );
        let res = app
            .request_form(Method::POST, &target, Some(&cookie), &sent)
            .await;
        assert_redirect(&res, &page, Some(&expected));
        app.assert_lands(&res, &cookie).await;
    }
    assert_eq!((toggles, names, updates), (4, 2, 1));

    // Each did what it says: the clock and Element X off, the game and Notes on; two names; the
    // Wi-Fi switch off. All on Anna.
    let allowlist: String =
        sqlx::query_scalar("SELECT allowlist_json FROM device_policy WHERE device_id = ?")
            .bind(id)
            .fetch_one(&app.db)
            .await
            .unwrap();
    let allowlist: Vec<String> = serde_json::from_str(&allowlist).unwrap();
    assert!(
        allowlist.contains(&"org.example.game".to_string()),
        "{allowlist:?}"
    );
    assert!(
        !allowlist.contains(&"com.android.deskclock".to_string()),
        "{allowlist:?}"
    );
    assert!(
        !allowlist.contains(&"io.element.android.x".to_string()),
        "{allowlist:?}"
    );
    let chosen: Vec<i64> =
        sqlx::query_scalar("SELECT tracked_app_id FROM device_tracked_apps WHERE device_id = ?")
            .bind(id)
            .fetch_all(&app.db)
            .await
            .unwrap();
    assert_eq!(chosen, [notes]);
    assert_ne!(chosen, [element]);
    let named: Vec<(i64, String)> = sqlx::query_as(
        "SELECT device_id, package_name FROM device_app_display ORDER BY package_name",
    )
    .fetch_all(&app.db)
    .await
    .unwrap();
    assert_eq!(
        named,
        [
            (id, "com.android.deskclock".to_string()),
            (id, "io.element.android.x".to_string())
        ]
    );
    let (wifi_only, emil_wifi_only): (bool, bool) = sqlx::query_as(
        "SELECT (SELECT app_updates_wifi_only FROM device_policy WHERE device_id = ?), \
                (SELECT app_updates_wifi_only FROM device_policy WHERE device_id = ?)",
    )
    .bind(id)
    .bind(emil)
    .fetch_one(&app.db)
    .await
    .unwrap();
    assert_eq!((wifi_only, emil_wifi_only), (false, true));
    // The preinstalled clock is only hidden, never queued for uninstall.
    let uninstalls: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM device_pending_uninstalls")
        .fetch_one(&app.db)
        .await
        .unwrap();
    assert_eq!(uninstalls, 0);
    // The rows moved group; the fragments still find them.
    let html = app.get_page(&page, &cookie).await.text();
    assert!(section(&html, "allowed").contains("id=\"app-org.example.game\""));
    assert!(section(&html, "not-allowed").contains("id=\"app-com.android.deskclock\""));
}

/// Design 68 §4.1: an allowed app's Permissions - the sub-line, the camera switch only for an app that asks for the
/// camera, a declared access with the way to approve it - and its form: a save lands on its own details, open
/// (`?perms=<package>#perms-<package>`), writes the row the policy sends and nudges; a bad camera value is a 400, Play's
/// core and Vibb are refused at their row; the update note while the launcher lacks `app_permissions_v1`.
#[tokio::test]
async fn the_permissions_form_returns_to_its_open_details() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("Anna").await;
    let element = "io.element.android.x";
    let report = |caps: serde_json::Value| {
        json!({
            "capabilities": caps,
            "installed_apps": [
                { "package_name": element, "label": "Element X", "preinstalled": false,
                  "access": { "camera": "requested", "manage_media": "denied" } },
                { "package_name": "org.example.plain", "label": "Plain", "preinstalled": false },
            ],
        })
    };
    app.post_status(&token, report(json!([]))).await;
    sqlx::query("UPDATE device_policy SET allowlist_json = ? WHERE device_id = ?")
        .bind(format!("[\"{element}\", \"org.example.plain\"]"))
        .bind(id)
        .execute(&app.db)
        .await
        .unwrap();
    let page = device_page(id, Section::Apps);
    let html = app.get_page(&page, &cookie).await.text();
    assert!(
        html.contains("data-row=\"update-note\""),
        "an older launcher: the note"
    );
    app.post_status(&token, report(json!(["app_permissions_v1"])))
        .await;
    let html = app.get_page(&page, &cookie).await.text();
    assert!(!html.contains("data-row=\"update-note\""));
    let anchor = format!("perms-{element}");
    assert!(
        html.contains(&format!("<details class=\"app-display\" id=\"{anchor}\">")),
        "closed at first"
    );
    assert!(
        !html.contains("id=\"perms-org.example.plain\""),
        "nothing to say: no details"
    );
    assert!(html.contains("Media management: not approved"));
    assert!(html.contains("App access › Element X: Media management"));
    let action = format!("/devices/{id}/apps/permissions#{anchor}");
    assert!(html.contains(&format!("action=\"{action}\"")));

    // Signed out: sent to sign in, nothing written (the route is behind the session and 2FA).
    let target = format!("/devices/{id}/apps/permissions");
    let res = app
        .request_form(Method::POST, &target, None, &[("package_name", element)])
        .await;
    assert!(res.status.is_redirection(), "{}", res.status);
    assert!(
        !res.location().unwrap_or("").contains("/apps"),
        "{:?}",
        res.location()
    );
    let rows: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM device_app_permissions")
        .fetch_one(&app.db)
        .await
        .unwrap();
    assert_eq!(rows, 0);

    // Off: the switch's field missing (an auto-saving checkbox); the phone is nudged.
    let mut nudges = app.state.command_notify.subscribe();
    let res = app
        .request_form(
            Method::POST,
            &target,
            Some(&cookie),
            &[("package_name", element)],
        )
        .await;
    let back = format!("{page}?perms={element}");
    assert_redirect(&res, &back, Some(&anchor));
    assert_eq!(nudges.try_recv().ok(), Some(id));
    app.assert_lands(&res, &cookie).await;
    let html = app.get_page(&back, &cookie).await.text();
    assert!(
        html.contains(&format!("id=\"{anchor}\" open")),
        "the details stay open"
    );
    assert!(html.contains("Camera off · waiting for the phone · Media management: not approved"));
    let policy = app
        .request(Method::GET, "/api/devices/policy", Some(&token), None)
        .await
        .json();
    assert_eq!(
        policy["app_permissions"],
        json!([{"package_name": element, "camera": "deny"}])
    );

    // Back on.
    let res = app
        .request_form(
            Method::POST,
            &target,
            Some(&cookie),
            &[("package_name", element), ("camera", "default")],
        )
        .await;
    assert_redirect(&res, &back, Some(&anchor));
    let rows: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM device_app_permissions")
        .fetch_one(&app.db)
        .await
        .unwrap();
    assert_eq!(rows, 0);

    // Refused: a value no page sends; Play's core and Vibb at their row, nothing written.
    let res = app
        .request_form(
            Method::POST,
            &target,
            Some(&cookie),
            &[("package_name", element), ("camera", "allow")],
        )
        .await;
    assert_eq!(res.status, StatusCode::BAD_REQUEST);
    let res = app
        .request_form(
            Method::POST,
            &target,
            Some(&cookie),
            &[("package_name", "not a package")],
        )
        .await;
    assert_eq!(res.status, StatusCode::BAD_REQUEST);
    for package in ["com.android.vending", "me.vibb.launcher"] {
        let res = app
            .request_form(
                Method::POST,
                &target,
                Some(&cookie),
                &[("package_name", package)],
            )
            .await;
        assert_redirect(
            &res,
            &format!("{page}?notice=perms&app=app-{package}"),
            Some(&format!("app-{package}")),
        );
    }
    let rows: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM device_app_permissions")
        .fetch_one(&app.db)
        .await
        .unwrap();
    assert_eq!(rows, 0);
}

/// Design 68: at most `MAX_ROWS_PER_DEVICE` camera-off apps a phone - a new one past it is refused at its row and
/// nothing is written; a stored one can still be saved again.
#[tokio::test]
async fn the_permissions_cap_refuses_the_201st_app() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, _) = app.enrolled_device("Anna").await;
    for n in 0..crate::app_permissions::MAX_ROWS_PER_DEVICE {
        sqlx::query(
            "INSERT INTO device_app_permissions (device_id, package_name, camera) VALUES (?, ?, 'deny')",
        )
        .bind(id)
        .bind(format!("org.example.app{n}"))
        .execute(&app.db)
        .await
        .unwrap();
    }
    let target = format!("/devices/{id}/apps/permissions");
    let page = device_page(id, Section::Apps);
    let res = app
        .request_form(
            Method::POST,
            &target,
            Some(&cookie),
            &[("package_name", "org.example.new")],
        )
        .await;
    assert_redirect(
        &res,
        &format!("{page}?notice=perms_full&app=app-org.example.new"),
        Some("app-org.example.new"),
    );
    let count = || async {
        sqlx::query_scalar::<_, i64>("SELECT COUNT(*) FROM device_app_permissions")
            .fetch_one(&app.db)
            .await
            .unwrap()
    };
    assert_eq!(count().await, crate::app_permissions::MAX_ROWS_PER_DEVICE);
    let res = app
        .request_form(
            Method::POST,
            &target,
            Some(&cookie),
            &[("package_name", "org.example.app7")],
        )
        .await;
    assert_redirect(
        &res,
        &format!("{page}?perms=org.example.app7"),
        Some("perms-org.example.app7"),
    );
    assert_eq!(count().await, crate::app_permissions::MAX_ROWS_PER_DEVICE);
}

/// A switch that can't be what it says changes nothing: not a package name, nothing at all, Play's
/// core, Vibb itself (400), an unknown phone (404); a catalog app deleted since the page loaded
/// returns to the page (a second tap lands where the first did). A failed read is a 500, never a
/// shorter list.
#[tokio::test]
async fn the_apps_switch_refuses_bad_input_and_the_page_fails_loudly() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, vibb, element, _) = apps_phone(&app).await;
    let toggle = |path_id: i64, fields: Vec<(&'static str, String)>| {
        let app = &app;
        let cookie = &cookie;
        async move {
            let fields: Vec<(&str, &str)> = fields.iter().map(|(k, v)| (*k, v.as_str())).collect();
            app.request_form(
                Method::POST,
                &format!("/devices/{path_id}/apps/toggle"),
                Some(cookie),
                &fields,
            )
            .await
        }
    };
    let before = || async {
        sqlx::query_as::<_, (Option<String>, i64)>(
            "SELECT allowlist_json, (SELECT COUNT(*) FROM device_tracked_apps) \
             FROM device_policy WHERE device_id = ?",
        )
        .bind(id)
        .fetch_one(&app.db)
        .await
        .unwrap()
    };
    let start = before().await;
    for (fields, status) in [
        (
            vec![("package_name", "not a package".to_string())],
            StatusCode::BAD_REQUEST,
        ),
        (
            vec![("package_name", "<b>x</b>.y".to_string())],
            StatusCode::BAD_REQUEST,
        ),
        (
            vec![("package_name", String::new())],
            StatusCode::BAD_REQUEST,
        ),
        (
            vec![("package_name", "com.android.vending".to_string())],
            StatusCode::BAD_REQUEST,
        ),
        // Vibb by its package alone (qa-31-s6-code L3).
        (
            vec![("package_name", "me.vibb.launcher".to_string())],
            StatusCode::BAD_REQUEST,
        ),
        (
            vec![
                ("package_name", "me.vibb.launcher".to_string()),
                ("tracked_app_id", vibb.to_string()),
            ],
            StatusCode::BAD_REQUEST,
        ),
    ] {
        let mut fields = fields;
        fields.push(("selected", "on".to_string()));
        let res = toggle(id, fields.clone()).await;
        assert_eq!(res.status, status, "{fields:?}");
    }
    let res = toggle(9999, vec![("package_name", "org.example.game".to_string())]).await;
    assert_eq!(res.status, StatusCode::NOT_FOUND);
    // A catalog app that is gone: back to the page, nothing written.
    let res = toggle(
        id,
        vec![
            ("package_name", "org.example.gone".to_string()),
            ("tracked_app_id", "9999".to_string()),
            ("selected", "on".to_string()),
        ],
    )
    .await;
    // ... to the group the tap came from (it was a turn-on: Not allowed), never the top (N1).
    assert_redirect(&res, &device_page(id, Section::Apps), Some("not-allowed"));
    assert_eq!(before().await, start);

    // A failed write is a 500, and nothing else is written.
    sqlx::query("ALTER TABLE device_tracked_apps RENAME TO device_tracked_apps_gone")
        .execute(&app.db)
        .await
        .unwrap();
    let res = toggle(
        id,
        vec![
            ("package_name", "io.element.android.x".to_string()),
            ("tracked_app_id", element.to_string()),
        ],
    )
    .await;
    assert_eq!(res.status, StatusCode::INTERNAL_SERVER_ERROR);
    let allowlist: String =
        sqlx::query_scalar("SELECT allowlist_json FROM device_policy WHERE device_id = ?")
            .bind(id)
            .fetch_one(&app.db)
            .await
            .unwrap();
    assert_eq!(Some(allowlist), start.0);
    // ... and every read the page needs fails as a 500.
    let res = app.get_page(&device_page(id, Section::Apps), &cookie).await;
    assert_eq!(res.status, StatusCode::INTERNAL_SERVER_ERROR);
    assert!(res.text().contains("Couldn't read this phone"));
    sqlx::query("ALTER TABLE device_tracked_apps_gone RENAME TO device_tracked_apps")
        .execute(&app.db)
        .await
        .unwrap();
    for table in ["device_app_display", "device_status", "tracked_apps"] {
        sqlx::query(&format!("ALTER TABLE {table} RENAME TO {table}_gone"))
            .execute(&app.db)
            .await
            .unwrap();
        let res = app.get_page(&device_page(id, Section::Apps), &cookie).await;
        assert_eq!(res.status, StatusCode::INTERNAL_SERVER_ERROR, "{table}");
        assert!(res.text().contains("Couldn't read this phone"), "{table}");
        sqlx::query(&format!("ALTER TABLE {table}_gone RENAME TO {table}"))
            .execute(&app.db)
            .await
            .unwrap();
    }
    assert_eq!(
        app.get_page(&device_page(9999, Section::Apps), &cookie)
            .await
            .status,
        StatusCode::NOT_FOUND
    );
}

/// A phone not set up has the page: a catalog app turned on installs with its first settings, and
/// the page says the phone hasn't reported its own apps.
#[tokio::test]
async fn a_phone_not_set_up_has_its_apps_page() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, _) = app.create_device("Mia").await;
    let html = app
        .get_page(&device_page(id, Section::Apps), &cookie)
        .await
        .text();
    assert!(html.contains("data-row=\"not-reported\""));
    assert!(!html.contains("Nothing else on this phone."));
    assert!(html.contains(&format!("action=\"/devices/{id}/app-updates#downloads\"")));
}

/// A refused Name and icon save is the answer to a POST: it never carries the installing reload,
/// which would ask to send the form again once it is closed; the page itself does while something
/// installs (qa-31-s6-code L1; the pause and the stop on a save are `jstest/auto-reload`).
#[tokio::test]
async fn a_refused_name_save_never_reloads() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, _, element, _) = apps_phone(&app).await;
    sqlx::query(
        "INSERT INTO device_install_progress (device_id, tracked_app_id, percent) VALUES (?, ?, 40)",
    )
    .bind(id)
    .bind(element)
    .execute(&app.db)
    .await
    .unwrap();
    let page = app
        .get_page(&device_page(id, Section::Apps), &cookie)
        .await
        .text();
    assert!(page.contains("Installing 40%"));
    assert!(page.contains("<script src=\"/static/auto-reload.js?v=4\" defer></script>"));
    let res = app
        .request_form(
            Method::POST,
            &format!("/devices/{id}/apps/display"),
            Some(&cookie),
            &[
                ("package_name", "com.android.deskclock"),
                ("label", "Twenty-one characters"),
            ],
        )
        .await;
    assert_eq!(res.status, StatusCode::BAD_REQUEST);
    let html = res.text();
    assert!(html.contains("id=\"display-com.android.deskclock\" open"));
    assert!(!html.contains("auto-reload.js"), "{html}");
}

/// A catalog app whose package is an older catalog's free text stays switchable, on and off, at
/// its row; a free-text package that isn't the catalog row's lands on the row with a short reason,
/// and one with no catalog row is a 400 (qa-31-s6-code L4).
#[tokio::test]
async fn a_legacy_catalog_package_stays_switchable() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, _, _, _) = apps_phone(&app).await;
    let legacy: i64 = sqlx::query_scalar(
        "INSERT INTO tracked_apps (name, package_name, github_repo) \
         VALUES ('Chat', 'Element X', 'owner/repo') RETURNING id",
    )
    .fetch_one(&app.db)
    .await
    .unwrap();
    let page = device_page(id, Section::Apps);
    let html = app.get_page(&page, &cookie).await.text();
    let row = format!("catalog-{legacy}");
    assert!(html.contains(&format!("id=\"{row}\"")));
    let chosen = || async {
        sqlx::query_scalar::<_, i64>(
            "SELECT COUNT(*) FROM device_tracked_apps WHERE device_id = ? AND tracked_app_id = ?",
        )
        .bind(id)
        .bind(legacy)
        .fetch_one(&app.db)
        .await
        .unwrap()
    };
    let toggle = |package: &'static str, on: bool| {
        let (app, cookie) = (&app, &cookie);
        async move {
            let tid = legacy.to_string();
            let mut fields = vec![
                ("package_name", package),
                ("tracked_app_id", tid.as_str()),
                ("back", "apps"),
            ];
            if on {
                fields.push(("selected", "on"));
            }
            app.request_form(
                Method::POST,
                &format!("/devices/{id}/apps/toggle"),
                Some(cookie),
                &fields,
            )
            .await
        }
    };
    assert_redirect(&toggle("Element X", true).await, &page, Some(&row));
    assert_eq!(chosen().await, 1);
    assert_redirect(&toggle("Element X", false).await, &page, Some(&row));
    assert_eq!(chosen().await, 0);
    // Another free text with that row: back to the row, the reason under it, nothing written.
    let res = toggle("Other X", true).await;
    assert_redirect(
        &res,
        &format!("{page}?notice=not_an_app&app={row}"),
        Some(&row),
    );
    assert_eq!(chosen().await, 0);
    let html = app
        .get_page(&format!("{page}?notice=not_an_app&app={row}"), &cookie)
        .await
        .text();
    let at = &html[html.find(&format!("id=\"{row}\"")).unwrap()..];
    assert!(
        at[..at.find("</div>\n").unwrap()].contains("This app changed in the catalog"),
        "{at}"
    );
    // Without a catalog row the free text is no app at all.
    let res = app
        .request_form(
            Method::POST,
            &format!("/devices/{id}/apps/toggle"),
            Some(&cookie),
            &[("package_name", "Element X"), ("selected", "on")],
        )
        .await;
    assert_eq!(res.status, StatusCode::BAD_REQUEST);
}

/// The device page's Apps row tells a corrupt list from a failed read (qa-31-s6-code N2).
#[tokio::test]
async fn the_apps_row_says_when_the_list_couldnt_be_read() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, _, _, _) = apps_phone(&app).await;
    sqlx::query("ALTER TABLE device_tracked_apps RENAME TO device_tracked_apps_gone")
        .execute(&app.db)
        .await
        .unwrap();
    let summary = app
        .get_page(&device_page(id, Section::Summary), &cookie)
        .await
        .text();
    let row = &summary[summary.find("data-row=\"apps\"").unwrap()..];
    let row = &row[..row.find("</a>").unwrap()];
    assert!(row.contains("Couldn"), "{row}");
    assert!(!row.contains("List unreadable"), "{row}");
}

// ---- Step 7: Device › Music --------------------------------------------------------------------

const STUA: &str = "RINCON_000E58A0B1C201400";
const KJOKKEN: &str = "RINCON_000E58A0B1C301400";

/// A phone with everything Device › Music shows: the music app in the catalog, two own-files
/// entries (the first on), two Sonos rooms reported. Returns the phone, its token, the entries and
/// the music app's catalog id.
async fn music_phone(app: &TestApp, cookie: &str) -> (i64, String, [i64; 2], i64) {
    let (id, token) = app.enrolled_device("Anna").await;
    let a = super::music::add_own(app, cookie, "Eventyr <b>").await;
    let b = super::music::add_own(app, cookie, "Sanger").await;
    super::music::tick(app, cookie, id, a, true).await;
    let res = app
        .request_form(Method::POST, "/music/catalog-row", Some(cookie), &[])
        .await;
    assert_eq!(res.status, StatusCode::SEE_OTHER);
    let music_app: i64 =
        sqlx::query_scalar("SELECT id FROM tracked_apps WHERE package_name = 'me.vibb.music'")
            .fetch_one(&app.db)
            .await
            .unwrap();
    app.post_status(&token,
        json!({
            "capabilities": ["music_v1"],
            "music_state": {"package": "me.vibb.music", "bridge": "bound",
                            "sonos_rooms": [{"uid": STUA, "name": "Stua"}, {"uid": KJOKKEN, "name": "Kjøkken"}]}
        }),
    )
    .await;
    (id, token, [a, b], music_app)
}

#[tokio::test]
async fn music_holds_the_moved_card() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, _, [a, _], _) = music_phone(&app, &cookie).await;
    let (emil, _) = app.enrolled_device("Emil").await;

    // The device page: a row with the entries on, none of the card's forms.
    let summary = app
        .get_page(&device_page(id, Section::Summary), &cookie)
        .await
        .text();
    let row = &summary[summary
        .find(&format!("href=\"/devices/{id}/music\""))
        .expect("the row")..];
    let row = &row[..row.find("</a>").unwrap()];
    assert!(row.contains("data-row=\"music\""), "{row}");
    assert!(
        row.contains("<span class=\"row-value\">1 in library</span>"),
        "{row}"
    );
    for gone in [
        "/music/entries/",
        "id=\"music-settings\"",
        "/music/sonos-rooms",
        "name=\"storage_limit\"",
        "value=\"music\"",
    ] {
        assert!(!summary.contains(gone), "{gone}");
    }
    // After the Apps row, as the mockup orders them.
    assert!(summary.find("data-row=\"apps\"") < summary.find("data-row=\"music\""));
    let emil_summary = app
        .get_page(&device_page(emil, Section::Summary), &cookie)
        .await
        .text();
    let emil_row = &emil_summary[emil_summary.find("data-row=\"music\"").unwrap()..];
    assert!(emil_row[..emil_row.find("</a>").unwrap()].contains(">None<"));

    // The page: the sub-page header, the groups in order, every save without JS too.
    let html = app
        .get_page(&device_page(id, Section::Music), &cookie)
        .await
        .text();
    assert_eq!(nav_current(&html), "devices");
    assert!(html.contains(&format!("<a class=\"nav-back\" href=\"/devices/{id}\">")));
    assert!(html.contains("<h1 class=\"page-title\">Music</h1>"));
    assert!(!html.contains("class=\"app-header\""));
    let order: Vec<usize> = [
        "id=\"music-app\"",
        "id=\"library\"",
        "id=\"music-settings\"",
        "id=\"music-sonos\"",
        "id=\"on-phone\"",
    ]
    .iter()
    .map(|g| html.find(g).expect(g))
    .collect();
    assert!(order.windows(2).all(|w| w[0] < w[1]), "{order:?}");
    every_auto_save_has_a_save_without_js(&html);
    for name in [
        "selected",
        "stream_mobile_data",
        "download_mobile_data",
        "storage_limit",
        "volume_cap",
        "storytel",
        "all",
    ] {
        assert!(autosaves(&html, name), "{name}");
    }
    // The 5-field form is one form with `v=2` (§5.6, M7).
    let settings = form_action_of(&html, "v");
    assert!(settings.is_some());
    for name in [
        "stream_mobile_data",
        "download_mobile_data",
        "storage_limit",
        "volume_cap",
        "storytel",
    ] {
        assert_eq!(form_action_of(&html, name), settings, "{name}");
    }
    // The entry on is on; the app switch off.
    assert_eq!(
        input_checked(section(&html, "music-app"), "selected", ""),
        Some(false)
    );
    let at = html.find(&format!("id=\"music-entry-{a}\"")).unwrap();
    let entry = &html[at..at + html[at..].find("</form>").unwrap()];
    assert_eq!(input_checked(entry, "selected", ""), Some(true));
    // Its links: the library to the Music tab, no login yet to Music › Settings.
    assert!(html.contains("href=\"/music\""));
    assert!(html.contains("href=\"/music/settings#storytel\""));
    // Third-party titles are escaped (qa-31-s7-code L1: a feed or Storytel names the entry).
    assert!(html.contains("Eventyr &#60;b&#62;") || html.contains("Eventyr &lt;b&gt;"));
    assert!(!html.contains("Eventyr <b>"));
    // Never a room's code as text (§4.2): with "All rooms" on they are hidden fields.
    assert!(section(&html, "music-sonos").contains(&format!("value=\"{STUA}\"")));
    assert!(!html.contains(&format!(">{STUA}<")));
    // An unknown phone.
    assert_eq!(
        app.get_page(&device_page(9999, Section::Music), &cookie)
            .await
            .status,
        StatusCode::NOT_FOUND
    );
}

/// §5.1's redirect table for Device › Music, built from the rendered forms: the music app's switch
/// returns to its group (`#music-app`), an entry's switch to its row (`#music-entry-<id>`, a long
/// list), the settings form and Sonos to their groups lower on the page - without JS a save never
/// lands at the top, with JS scroll-restore keeps the place. Each fragment names the element that
/// holds the form (§5.1.4, qa-31-s14-code L4), and an action's own fragment is the same; then each
/// did what it says, on Anna only (the phone from the path).
#[tokio::test]
async fn music_forms_return_to_their_groups() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token, [a, b], music_app) = music_phone(&app, &cookie).await;
    let (emil, _) = app.enrolled_device("Emil").await;
    // A Spotify app and a child's login: the page shows its "Plays as" group (design 26c §2.5).
    crate::spotify::tests::save_app(&app, crate::spotify::LOOPBACK_REDIRECT).await;
    let kid = crate::spotify::tests::store_account(&app, "fakekid", "Ella", "FAKE-REFRESH-1").await;
    let page = device_page(id, Section::Music);
    let html = app.get_page(&page, &cookie).await.text();
    let mut seen = Vec::new();
    for form in tags(&html, "form") {
        let action = form.attr("action").unwrap().to_string();
        let end = form.start + html[form.start..].find("</form>").unwrap();
        let hidden: Vec<(String, String)> = tags(&html[form.start..end], "input")
            .into_iter()
            .filter(|input| input.attr("type") == Some("hidden"))
            .map(|input| {
                (
                    input.attr("name").unwrap().to_string(),
                    input.attr("value").unwrap().to_string(),
                )
            })
            .collect();
        let (target, action_fragment) = match action.split_once('#') {
            Some((target, fragment)) => (target.to_string(), Some(fragment.to_string())),
            None => (action.clone(), None),
        };
        let mut sent: Vec<(String, String)> = hidden.clone();
        let on = |name: &str| (name.to_string(), "on".to_string());
        let expected: Option<String> = if target == format!("/devices/{id}/apps/toggle") {
            assert!(hidden.contains(&("back".to_string(), "music".to_string())));
            sent.push(on("selected"));
            Some("music-app".to_string())
        } else if let Some(entry) = target
            .strip_prefix(&format!("/devices/{id}/music/entries/"))
            .and_then(|e| e.parse::<i64>().ok())
        {
            // Flip it.
            if entry == b {
                sent.push(on("selected"));
            }
            Some(format!("music-entry-{entry}"))
        } else if target == format!("/devices/{id}/music") {
            sent.extend([
                on("stream_mobile_data"),
                on("storytel"),
                ("storage_limit".to_string(), "5".to_string()),
                ("volume_cap".to_string(), "70".to_string()),
            ]);
            Some("music-settings".to_string())
        } else if target == format!("/devices/{id}/music/spotify") {
            sent.push(("account".to_string(), kid.to_string()));
            Some("music-spotify".to_string())
        } else if target == format!("/devices/{id}/music/sonos-rooms") {
            // "All rooms" off: the hidden rooms keep both allowed.
            Some("music-sonos".to_string())
        } else {
            panic!("a form the table doesn't know: {action}");
        };
        if let Some(fragment) = &expected {
            // The element with that id holds the form (or is it).
            let at = html
                .find(&format!("id=\"{fragment}\""))
                .unwrap_or_else(|| panic!("{fragment} names nothing on the page"));
            let open = html[..at].rfind('<').unwrap();
            let tag: String = html[open + 1..]
                .chars()
                .take_while(|c| c.is_ascii_alphabetic())
                .collect();
            assert!(open <= form.start, "{fragment} is after its form");
            assert!(
                !html[open..form.start].contains(&format!("</{tag}>")),
                "{fragment} closes before its form ({action})"
            );
        }
        assert_eq!(
            action_fragment, expected,
            "{action}: the action's fragment is the redirect's"
        );
        let fields: Vec<(&str, &str)> =
            sent.iter().map(|(k, v)| (k.as_str(), v.as_str())).collect();
        let res = app
            .request_form(Method::POST, &target, Some(&cookie), &fields)
            .await;
        assert_redirect(&res, &page, expected.as_deref());
        app.assert_lands(&res, &cookie).await;
        seen.push(target);
    }
    assert_eq!(
        seen,
        [
            format!("/devices/{id}/apps/toggle"),
            format!("/devices/{id}/music/entries/{a}"),
            format!("/devices/{id}/music/entries/{b}"),
            format!("/devices/{id}/music"),
            format!("/devices/{id}/music/spotify"),
            format!("/devices/{id}/music/sonos-rooms"),
        ],
        "every form on the page is in the table, in page order"
    );

    // Each did what it says, on Anna only.
    let chosen: Vec<i64> =
        sqlx::query_scalar("SELECT tracked_app_id FROM device_tracked_apps WHERE device_id = ?")
            .bind(id)
            .fetch_all(&app.db)
            .await
            .unwrap();
    assert_eq!(chosen, [music_app]);
    let ticks: Vec<(i64, i64)> =
        sqlx::query_as("SELECT device_id, entry_id FROM device_music_entries ORDER BY device_id")
            .fetch_all(&app.db)
            .await
            .unwrap();
    assert_eq!(ticks, [(id, b)]);
    let bound: Vec<(i64, Option<i64>)> = sqlx::query_as(
        "SELECT device_id, music_spotify_account FROM device_policy ORDER BY device_id",
    )
    .fetch_all(&app.db)
    .await
    .unwrap();
    assert!(bound.contains(&(id, Some(kid))) && bound.contains(&(emil, None)));
    type Settings = (bool, bool, Option<i64>, Option<i64>, bool, Option<String>);
    let settings = |device: i64| {
        let db = app.db.clone();
        async move {
            sqlx::query_as::<_, Settings>(
                "SELECT music_stream_mobile_data, music_download_mobile_data, \
                 music_storage_limit_gb, music_volume_cap_pct, music_storytel, \
                 music_sonos_rooms_json FROM device_policy WHERE device_id = ?",
            )
            .bind(device)
            .fetch_one(&db)
            .await
            .unwrap()
        }
    };
    assert_eq!(
        settings(id).await,
        (
            true,
            false,
            Some(5),
            Some(70),
            true,
            Some(format!("[\"{STUA}\",\"{KJOKKEN}\"]"))
        )
    );
    assert_eq!(
        settings(emil).await,
        (false, false, Some(2), None, false, None)
    );
    let music = app
        .request(Method::GET, "/api/devices/policy", Some(&token), None)
        .await
        .json()["music"]
        .clone();
    assert_eq!(music["stream_mobile_data"], json!(true));
    assert_eq!(music["storage_limit_gb"], json!(5));
}

/// A refused music app switch lands in its group with the reason, once; a catalog row deleted
/// since the page loaded lands there too, with nothing written. A failed read is a 500, never an
/// empty library or default settings; the device page's row says it couldn't count.
#[tokio::test]
async fn music_refusals_land_in_their_group_and_the_page_fails_loudly() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token, _, music_app) = music_phone(&app, &cookie).await;
    let page = device_page(id, Section::Music);
    // The music app came from the Play Store: one source per package.
    app.post_status(
        &token,
        json!({
            "capabilities": ["music_v1"],
            "installed_apps": [{"package_name": "me.vibb.music", "label": "Musikk",
                                "installer": "com.android.vending"}]
        }),
    )
    .await;
    let toggle = |tracked: i64| {
        let (app, cookie) = (&app, &cookie);
        async move {
            let tracked = tracked.to_string();
            app.request_form(
                Method::POST,
                &format!("/devices/{id}/apps/toggle"),
                Some(cookie),
                &[
                    ("tracked_app_id", tracked.as_str()),
                    ("package_name", "me.vibb.music"),
                    ("back", "music"),
                    ("selected", "on"),
                ],
            )
            .await
        }
    };
    let res = toggle(music_app).await;
    let refused = format!("{page}?music_notice=from_play");
    assert_redirect(&res, &refused, Some("music-app"));
    let html = app.get_page(&refused, &cookie).await.text();
    assert!(section(&html, "music-app").contains("Installed from the Play Store"));
    assert_eq!(html.matches("Installed from the Play Store").count(), 1);
    // A catalog row that is gone.
    let res = toggle(9999).await;
    assert_redirect(&res, &page, Some("music-app"));
    let chosen: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM device_tracked_apps")
        .fetch_one(&app.db)
        .await
        .unwrap();
    assert_eq!(chosen, 0, "nothing written");

    // Every read the page needs.
    for table in [
        "music_entries",
        "music_storytel",
        "device_status",
        "device_policy",
    ] {
        sqlx::query(&format!("ALTER TABLE {table} RENAME TO {table}_gone"))
            .execute(&app.db)
            .await
            .unwrap();
        let res = app.get_page(&page, &cookie).await;
        assert_eq!(res.status, StatusCode::INTERNAL_SERVER_ERROR, "{table}");
        assert!(res.text().contains("Couldn't read this phone"), "{table}");
        sqlx::query(&format!("ALTER TABLE {table}_gone RENAME TO {table}"))
            .execute(&app.db)
            .await
            .unwrap();
    }
    sqlx::query("ALTER TABLE device_music_entries RENAME TO device_music_entries_gone")
        .execute(&app.db)
        .await
        .unwrap();
    let summary = app
        .get_page(&device_page(id, Section::Summary), &cookie)
        .await
        .text();
    let row = &summary[summary.find("data-row=\"music\"").unwrap()..];
    assert!(row[..row.find("</a>").unwrap()].contains("Couldn"));
}

/// A phone not set up has its Music page: what is saved reaches it with its first settings.
#[tokio::test]
async fn a_phone_not_set_up_has_its_music_page() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, _) = app.create_device("Mia").await;
    let res = app
        .get_page(&device_page(id, Section::Music), &cookie)
        .await;
    assert_eq!(res.status, StatusCode::OK, "{}", res.text());
    let html = res.text();
    assert!(html.contains(&format!("action=\"/devices/{id}/music#music-settings\"")));
    assert!(html.contains("Nothing in the library yet."));
    assert!(html.contains("No speakers seen yet."));
    assert!(!html.contains("At the last report"));
    // Nothing is on the phone yet: no "On the phone" group at all (qa-31-s7-code L3).
    assert!(!html.contains("id=\"on-phone\""));
}

/// The Music tab and an entry's page link each phone to its Music page - the entry's page to that
/// entry's row there (step 12's `phone_music_href`, repointed here).
#[tokio::test]
async fn the_music_pages_link_to_the_phones_music_page() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, _, [a, _], _) = music_phone(&app, &cookie).await;
    let tab = app.get_page("/music", &cookie).await.text();
    assert!(tab.contains(&format!("href=\"/devices/{id}/music\"")));
    let entry = app
        .get_page(&format!("/music/entries/{a}"), &cookie)
        .await
        .text();
    assert!(entry.contains(&format!("href=\"/devices/{id}/music#music-entry-{a}\"")));
    // A downloading entry's offline ⓘ names the switch and where it is (M7).
    let nrk = super::music::add_nrk(&app, &cookie, "abels_taarn", "Abels tårn").await;
    let entry = app
        .get_page(&format!("/music/entries/{nrk}"), &cookie)
        .await
        .text()
        .replace("&#39;", "'");
    assert!(entry.contains("'Download on mobile data' is on (the phone's Music page)"));
    let html = app
        .get_page(&device_page(id, Section::Music), &cookie)
        .await
        .text();
    assert!(html.contains(&format!("id=\"music-entry-{a}\"")));
}

/// The `.nav-back` link: its address and its words.
fn nav_back(html: &str) -> (String, String) {
    let at = html.find("class=\"nav-back\"").expect("a back link");
    let link = &html[at..at + html[at..].find("</a>").unwrap()];
    let href = link
        .split("href=\"")
        .nth(1)
        .and_then(|rest| rest.split('"').next())
        .unwrap()
        .to_string();
    (href, link.rsplit("</svg>").next().unwrap().to_string())
}

/// The user, 2026-10-24 ("på apps, om jeg går inn på feks Sverre sin tlf og så trykker tilbake -
/// så kommer jeg til device taben for Sverre?"): opened from the Apps tab (`?from=apps`, or
/// `?from=app:<id>` from a catalog app's page), Device › Apps lights the Apps tab and its back link
/// goes back there, at the phone's row. From the phone's page, or with any other `from`, nothing
/// changes; a catalog app deleted since is the list.
#[tokio::test]
async fn apps_from_the_apps_tab_go_back_there() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, _, element, notes) = apps_phone(&app).await;
    let page = device_page(id, Section::Apps);
    let get = |query: String| {
        let app = &app;
        let cookie = &cookie;
        let page = &page;
        async move { app.get_page(&format!("{page}{query}"), cookie).await.text() }
    };
    let froms = |html: &str| -> Vec<String> {
        forms(html)
            .into_iter()
            .map(|(_, hidden)| {
                hidden
                    .into_iter()
                    .find(|(k, _)| k == "from")
                    .map(|(_, v)| v)
                    .unwrap_or_default()
            })
            .collect()
    };

    // From the phone's page, and every value that isn't one of the two shapes: as before.
    let mut plain = vec![String::new()];
    for bad in [
        "",
        "app",
        "app:",
        "app:0",
        "app:-1",
        "app:+1",
        "app:1x",
        "app: 1",
        "apps2",
        "Apps",
        "devices",
        "/evil",
        "app:99999999999999999999",
        "app:1&from=apps",
    ] {
        plain.push(format!(
            "?from={}",
            bad.replace('&', "%26").replace(' ', "%20")
        ));
    }
    for query in plain {
        let html = get(query.clone()).await;
        assert_eq!(nav_current(&html), "devices", "{query}");
        assert_eq!(
            nav_back(&html),
            (format!("/devices/{id}"), "Anna".to_string()),
            "{query}"
        );
        let froms = froms(&html);
        assert!(
            !froms.is_empty() && froms.iter().all(String::is_empty),
            "{query}"
        );
    }

    // From the list; from a catalog app's page; from one deleted since (the list).
    for (query, back, label, from) in [
        (
            "?from=apps".to_string(),
            format!("/apps#phone-{id}"),
            "Apps",
            "apps".to_string(),
        ),
        (
            format!("?from=app:{element}"),
            format!("/apps/tracked/{element}#phone-{id}"),
            "Element X",
            format!("app:{element}"),
        ),
        (
            "?from=app:99999".to_string(),
            format!("/apps#phone-{id}"),
            "Apps",
            "apps".to_string(),
        ),
    ] {
        let html = get(query.clone()).await;
        assert_eq!(nav_current(&html), "apps", "{query}");
        assert_eq!(
            nav_back(&html),
            (back.clone(), label.to_string()),
            "{query}"
        );
        assert!(froms(&html).iter().all(|f| *f == from), "{query}");
        // The back link lands on the phone's row there.
        let (target, fragment) = back.split_once('#').unwrap();
        let there = app.get_page(target, &cookie).await.text();
        assert!(there.contains(&format!("id=\"{fragment}\"")), "{back}");
    }

    // Every form keeps `from` through its redirect, to the same fragment as without it, and the
    // page it lands on is the Apps tab's again.
    let from = format!("app:{element}");
    let html = get(format!("?from={from}")).await;
    let mut posted = 0;
    for (action, hidden) in forms(&html) {
        let (target, fragment) = action.split_once('#').unwrap();
        let mut sent: Vec<(&str, &str)> = hidden
            .iter()
            .map(|(k, v)| (k.as_str(), v.as_str()))
            .collect();
        if target.ends_with("/apps/display") {
            sent.extend([("label", "Mine"), ("icon", "chat"), ("color", "auto")]);
        }
        let res = app
            .request_form(Method::POST, target, Some(&cookie), &sent)
            .await;
        assert_redirect(&res, &format!("{page}?from={from}"), Some(fragment));
        app.assert_lands(&res, &cookie).await;
        let landed = app
            .get_page(res.location().unwrap().split('#').next().unwrap(), &cookie)
            .await
            .text();
        assert_eq!(nav_current(&landed), "apps", "{action}");
        posted += 1;
    }
    assert!(posted >= 4, "{posted} forms");
    // Element X is off on Anna now, so its page doesn't list her: Back goes to its phones' group
    // (qa-pwa-polish-a Q-10), which is always there.
    let html = get(format!("?from={from}")).await;
    let back = format!("/apps/tracked/{element}#phones");
    assert_eq!(nav_back(&html).0, back);
    let there = app
        .get_page(&format!("/apps/tracked/{element}"), &cookie)
        .await
        .text();
    assert!(there.contains("id=\"phones\"") && !there.contains(&format!("id=\"phone-{id}\"")));

    // A refused switch and a refused name keep it too: the reason's address, and the answer.
    let res = app
        .request_form(
            Method::POST,
            &format!("/devices/{id}/apps/toggle"),
            Some(&cookie),
            &[
                ("package_name", ""),
                ("tracked_app_id", "99999"),
                ("selected", "on"),
                ("back", "apps"),
                ("from", "apps"),
            ],
        )
        .await;
    assert_redirect(&res, &format!("{page}?from=apps"), Some("not-allowed"));
    // A refusal with its reason (`toggle_refusal`): `?notice=…&app=…`, then `from` (Q-7).
    let res = app
        .request_form(
            Method::POST,
            &format!("/devices/{id}/apps/toggle"),
            Some(&cookie),
            &[
                ("package_name", "Not A Package"),
                ("tracked_app_id", &notes.to_string()),
                ("selected", "on"),
                ("back", "apps"),
                ("from", &from),
            ],
        )
        .await;
    let row = format!("catalog-{notes}");
    assert_redirect(
        &res,
        &format!("{page}?notice=not_an_app&app={row}&from={from}"),
        Some(&row),
    );
    app.assert_lands(&res, &cookie).await;
    let res = app
        .request_form(
            Method::POST,
            &format!("/devices/{id}/apps/display"),
            Some(&cookie),
            &[
                ("package_name", "io.element.android.x"),
                ("label", "Mine"),
                ("icon", "no-such-icon"),
                ("color", "auto"),
                ("from", "apps"),
            ],
        )
        .await;
    assert_eq!(res.status, StatusCode::BAD_REQUEST);
    let answer = res.text();
    assert_eq!(nav_current(&answer), "apps");
    assert_eq!(nav_back(&answer).0, format!("/apps#phone-{id}"));
    assert!(froms(&answer).iter().all(|f| f == "apps"));
    // A refusal's own address (`?notice=…&app=…`) with `from`: the reason under the row, the tab.
    let html = get("?notice=not_an_app&app=app-io.element.android.x&from=apps".to_string()).await;
    assert_eq!(nav_current(&html), "apps");
    assert!(
        html.contains("data-flash=\"app-io.element.android.x\""),
        "{html}"
    );
}
