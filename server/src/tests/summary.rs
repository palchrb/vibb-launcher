//! The phone's summary (`/devices/{id}`, design 31 §3.2, step 10): the header, Quick actions,
//! Today, the not-set-up variant, where every form returns, and the reads that must fail loudly.
//! The design 24b groups (the foreign-app warning, blocked apps, the last relock) are pinned in
//! `window_relock.rs`, the Attention rows in `attention.rs` and `devices_list.rs`.

use axum::http::{Method, StatusCode};
use serde_json::json;

use super::settings_pages::forms;
use super::{Section, TestApp, assert_redirect, device_page};

async fn post_status(app: &TestApp, token: &str, body: serde_json::Value) {
    let mut report = json!({ "lock_reason": "NONE", "kiosk_engaged": true });
    for (key, value) in body.as_object().unwrap() {
        report[key] = value.clone();
    }
    let res = app
        .request(
            Method::POST,
            "/api/devices/status",
            Some(token),
            Some(report),
        )
        .await;
    assert_eq!(res.status, StatusCode::NO_CONTENT);
}

/// One group of the summary: `<section id="…">` to its end.
fn group<'a>(html: &'a str, id: &str) -> &'a str {
    let start = html
        .find(&format!("<section id=\"{id}\""))
        .unwrap_or_else(|| panic!("no #{id} in {html}"));
    let end = html[start..]
        .find("</section>")
        .map_or(html.len(), |e| start + e);
    &html[start..end]
}

fn header(html: &str) -> &str {
    let start = html
        .find("<header class=\"summary-head\"")
        .expect("the header");
    &html[start..start + html[start..].find("</header>").unwrap()]
}

async fn add_rule(app: &TestApp, name: &str) -> i64 {
    sqlx::query_scalar(
        "INSERT INTO time_rules (device_id, name, kind, calls_allowed, exempt_apps_json, days_json) \
         VALUES (NULL, ?, 'school', 1, '[]', \
         '[{\"start\":495,\"end\":870},null,null,null,null,null,null]') RETURNING id",
    )
    .bind(name)
    .fetch_one(&app.db)
    .await
    .unwrap()
}

/// §3.2 item 1, §3.10: the state word and "seen …", what it means for changes, Disconnected's
/// link, "Not seen yet"; the name escaped; the sub-page header (no brand bar).
#[tokio::test]
async fn the_header_says_whether_the_phone_is_connected() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("Anna <b>").await;
    let page = || {
        let (app, cookie) = (&app, &cookie);
        async move {
            app.get_page(&device_page(id, Section::Summary), cookie)
                .await
                .text()
        }
    };
    let html = page().await;
    let head = header(&html);
    assert!(head.contains("Anna &#60;b&#62;"), "{head}");
    assert!(!head.contains("seen seen"), "{head}");
    assert!(!head.contains("Anna <b>"), "{head}");
    assert!(
        head.contains("<span class=\"state-offline\">Not seen yet</span></p>"),
        "{head}"
    );
    assert!(
        html.contains("<a class=\"nav-back\" href=\"/devices\">"),
        "{html}"
    );
    assert!(!html.contains("class=\"app-header\""), "{html}");

    // A report, no stream: Away, and changes may be delayed.
    post_status(&app, &token, json!({})).await;
    let html = page().await;
    let head = header(&html);
    assert!(
        head.contains("<span class=\"state-away\">Away</span> · <span title=\""),
        "{head}"
    );
    assert!(
        head.contains("seen just now</span> · changes may be delayed"),
        "{head}"
    );
    // A stream open: Online, nothing about delays.
    let _stream = app.state.command_streams.open(id);
    let html = page().await;
    let head = header(&html);
    assert!(
        head.contains("<span class=\"state-online\">Online</span>"),
        "{head}"
    );
    assert!(!head.contains("changes"), "{head}");
    drop(_stream);
    // Two hours of silence (nothing in memory, as after a restart): Offline, changes wait.
    let (emil, _) = app.enrolled_device("Emil").await;
    sqlx::query("UPDATE devices SET last_seen_at = datetime('now', '-2 hours') WHERE id = ?")
        .bind(emil)
        .execute(&app.db)
        .await
        .unwrap();
    let html = app
        .get_page(&device_page(emil, Section::Summary), &cookie)
        .await
        .text();
    let head = header(&html);
    assert!(head.contains("Offline</span>"), "{head}");
    // Two hours ago is "2 h ago" on the same local day, "yesterday HH:MM" right after local
    // midnight (`display_time::relative`); the test runs at any hour.
    assert!(
        head.contains("seen 2 h ago</span> · changes wait until it&#39;s back")
            || (head.contains("seen yesterday ")
                && head.contains("</span> · changes wait until it&#39;s back")),
        "{head}"
    );
}

/// The lock badge only while a rule locks, the bar only with a limit and today's report.
#[tokio::test]
async fn the_header_shows_the_lock_and_todays_screen_time() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("Anna").await;
    let today = crate::display_time::Zone::load(&app.db)
        .await
        .today(chrono::Utc::now())
        .to_string();
    post_status(
        &app,
        &token,
        json!({ "time_state": { "lock_reason": "NONE", "day": today, "used_minutes": 42,
                                "budget_minutes": 90 } }),
    )
    .await;
    let html = app
        .get_page(&format!("/devices/{id}"), &cookie)
        .await
        .text();
    let head = header(&html);
    assert!(!head.contains("data-lock"), "{head}");
    assert!(
        head.contains("<span class=\"summary-screen\" data-screen-time>42 of 90 min today</span>"),
        "{head}"
    );
    assert!(head.contains("<i style=\"width: 46%\"></i>"), "{head}");
    post_status(
        &app,
        &token,
        json!({ "time_state": { "lock_reason": "BEDTIME", "active_rule_name": "Leggetid",
                                "day": today, "used_minutes": 42, "budget_minutes": 90 } }),
    )
    .await;
    let html = app
        .get_page(&format!("/devices/{id}"), &cookie)
        .await
        .text();
    let head = header(&html);
    assert!(head.contains("data-lock>Leggetid</span>"), "{head}");
}

/// §3.2 item 3: the tiles, Pause a rule… only with rules that apply, Unlock for 2 h… behind its
/// confirm only for a launcher that takes it, Locate a link; no auto-saving form on the page.
#[tokio::test]
async fn the_quick_actions_follow_the_phone() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("Anna").await;
    let html = app
        .get_page(&format!("/devices/{id}"), &cookie)
        .await
        .text();
    let quick = group(&html, "quick");
    for label in ["+15 min", "+30 min", "+1 h"] {
        assert!(
            quick.contains(&format!("</svg>{label}</button>")),
            "{label}: {quick}"
        );
    }
    assert!(
        quick.contains(&format!("href=\"/devices/locate?device={id}\"")),
        "{quick}"
    );
    assert!(!quick.contains("pause-rule"), "no rule applies: {quick}");
    assert!(!quick.contains("start-pause"), "no report yet: {quick}");
    let main = &html[html.find("<main").unwrap()..];
    assert!(!main.contains("data-autosave"), "{main}");

    add_rule(&app, "Skole <i>").await;
    post_status(
        &app,
        &token,
        json!({ "capabilities": ["window_relock_v1"] }),
    )
    .await;
    let html = app
        .get_page(&format!("/devices/{id}"), &cookie)
        .await
        .text();
    let quick = group(&html, "quick");
    let sheet = &quick[quick
        .find("<details class=\"act-sheet\" id=\"pause-rule\">")
        .expect("the sheet")..];
    assert!(sheet.contains(">Skole &#60;i&#62;</option>"), "{sheet}");
    assert!(
        sheet.contains("<option value=\"all\">All rules</option>"),
        "{sheet}"
    );
    assert!(
        quick.contains("<details class=\"act-sheet confirm\" id=\"unlock\">"),
        "{quick}"
    );
    assert!(!quick.contains("end-windows"), "{quick}");
}

/// §5.1: every form on the summary, posted with what it carries, returns to the group it is in -
/// the quick actions, Today, the blocked apps - with the flash there; built from the rendered forms
/// (a form that moves can't keep a stale target), each action's fragment the redirect's.
#[tokio::test]
async fn every_form_on_the_summary_returns_to_its_group() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("Anna").await;
    let rule = add_rule(&app, "Skole").await;
    // A pause running (End now), a launcher that takes the commands with Play install mode open
    // (Lock again and Unlock), and a blocked app (Allow these apps…).
    sqlx::query(
        "INSERT INTO time_lifts (device_id, target, rule_id, rule_name, minutes, expires_at) \
         VALUES (?, 'rule', ?, 'Skole', 30, datetime('now', '+30 minutes'))",
    )
    .bind(id)
    .bind(rule)
    .execute(&app.db)
    .await
    .unwrap();
    post_status(
        &app,
        &token,
        json!({ "capabilities": ["window_relock_v1"],
                "install_mode": { "until_ms": chrono::Utc::now().timestamp_millis() + 600_000 },
                "relock_blocks": [{ "package_name": "org.example.game", "signer": null,
                                    "reason": "outside_play", "found": "install", "at_ms": null }] }),
    )
    .await;
    let summary = device_page(id, Section::Summary);
    let html = app.get_page(&summary, &cookie).await.text();
    // (action, what the parent picks, the expected Location and fragment)
    type Row<'a> = (String, Vec<(&'a str, &'a str)>, String, &'a str);
    let table: Vec<Row> = vec![
        (
            format!("/devices/{id}/lifts#quick"),
            vec![],
            format!("{summary}?done=extra"),
            "quick",
        ),
        (
            format!("/devices/{id}/lifts#quick"),
            vec![],
            format!("{summary}?done=extra"),
            "quick",
        ),
        (
            format!("/devices/{id}/lifts#quick"),
            vec![],
            format!("{summary}?done=extra"),
            "quick",
        ),
        (
            format!("/devices/{id}/lifts#quick"),
            vec![("rule", "all"), ("minutes", "15")],
            format!("{summary}?done=pause"),
            "quick",
        ),
        (
            format!("/devices/{id}/end-windows#quick"),
            vec![],
            format!("{summary}?notice=end_windows_sent"),
            "quick",
        ),
        (
            format!("/devices/{id}/start-pause#quick"),
            vec![],
            format!("{summary}?notice=start_pause_sent"),
            "quick",
        ),
        (
            format!("/devices/{id}/lifts/1/end#today"),
            vec![],
            format!("{summary}?done=ended"),
            "today",
        ),
        (
            format!("/devices/{id}/allow-blocked#blocked-apps"),
            vec![],
            format!("{summary}?notice=allow_blocked_sent"),
            "blocked-apps",
        ),
    ];
    let found = forms(&html);
    assert_eq!(
        found.iter().map(|(a, _)| a.as_str()).collect::<Vec<_>>(),
        table.iter().map(|(a, ..)| a.as_str()).collect::<Vec<_>>(),
        "every form on the summary is in the table, in page order"
    );
    for ((action, hidden), (_, picked, path, fragment)) in found.iter().zip(&table) {
        assert!(action.ends_with(&format!("#{fragment}")), "{action}");
        let mut sent: Vec<(&str, &str)> = hidden
            .iter()
            .map(|(k, v)| (k.as_str(), v.as_str()))
            .collect();
        sent.extend(picked.iter().copied());
        let res = app
            .request_form(
                Method::POST,
                action.split('#').next().unwrap(),
                Some(&cookie),
                &sent,
            )
            .await;
        assert_redirect(&res, path, Some(fragment));
        app.assert_lands(&res, &cookie).await;
        // The flash is in that group, where the page lands.
        let page = app.get_page(path, &cookie).await.text();
        assert!(
            group(&page, fragment).contains(&format!("data-flash=\"{fragment}\"")),
            "{action}: {page}"
        );
    }
    // The quick actions sent `back=summary`; End now did too.
    for (action, hidden) in &found {
        if action.contains("/lifts") {
            assert!(
                hidden.contains(&("back".to_string(), "summary".to_string())),
                "{action}"
            );
        }
    }
    // A reload never repeats an action: every answer was a redirect (PRG), the GET changes nothing.
    let before: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM time_lifts")
        .fetch_one(&app.db)
        .await
        .unwrap();
    app.get_page(&format!("{summary}?done=extra"), &cookie)
        .await;
    let after: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM time_lifts")
        .fetch_one(&app.db)
        .await
        .unwrap();
    assert_eq!(before, after);
}

/// §3.2 item 4: Today has the lifts made today and the pauses still running, in local time with
/// the exact one in `title`; yesterday's finished ones are on Device › Time only.
#[tokio::test]
async fn today_lists_todays_lifts_in_local_time() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, _) = app.enrolled_device("Anna").await;
    sqlx::query("UPDATE provisioning_settings SET time_zone = 'Europe/Oslo'")
        .execute(&app.db)
        .await
        .unwrap();
    // A whole minute in the past, so the local clock time is known.
    let made = chrono::Utc::now() - chrono::Duration::minutes(2);
    let made = made - chrono::Duration::seconds(i64::from(chrono::Timelike::second(&made)));
    let stamp = made.format("%Y-%m-%d %H:%M:%S").to_string();
    for (target, minutes, created, expires) in [
        ("budget", 15, stamp.clone(), "datetime('now', '+12 hours')"),
        (
            "rule",
            30,
            "2020-01-01 10:00:00".to_string(),
            "'2020-01-01 10:30:00'",
        ),
    ] {
        sqlx::query(&format!(
            "INSERT INTO time_lifts (device_id, target, minutes, created_at, expires_at, created_by) \
             VALUES (?, ?, ?, ?, {expires}, 'parent')"
        ))
        .bind(id)
        .bind(target)
        .bind(minutes)
        .bind(&created)
        .execute(&app.db)
        .await
        .unwrap();
    }
    let html = app
        .get_page(&format!("/devices/{id}"), &cookie)
        .await
        .text();
    let today = group(&html, "today");
    let oslo = made.with_timezone(&chrono_tz::Europe::Oslo);
    assert!(today.contains("+15 min screen time"), "{today}");
    assert!(
        today.contains(&format!(
            "title=\"{}\">{} · parent</span>",
            oslo.format("%Y-%m-%d %H:%M:%S %Z"),
            oslo.format("%H:%M")
        )),
        "{today}"
    );
    assert!(!today.contains("All rules paused"), "{today}");
    // Nothing happened: no Today at all.
    let (emil, _) = app.enrolled_device("Emil").await;
    let html = app
        .get_page(&format!("/devices/{emil}"), &cookie)
        .await
        .text();
    assert!(!html.contains("id=\"today\""), "{html}");
}

/// §3.2's not-set-up variant: the header, Connect This Phone (New Setup Code is a POST, the QR a
/// link, the typed-code pause), no Attention or quick actions, and every Sections row live.
#[tokio::test]
async fn a_phone_not_set_up_shows_connect_this_phone() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, _) = app.create_device("Mia").await;
    let html = app
        .get_page(&format!("/devices/{id}"), &cookie)
        .await
        .text();
    assert!(header(&html).contains("<span class=\"state-offline\">Not set up</span></p>"));
    let access = group(&html, "access");
    assert!(access.contains("Connect This Phone"), "{access}");
    assert!(
        access.contains(&format!(
            "<form method=\"post\" action=\"/devices/{id}/regenerate-code\""
        )),
        "{access}"
    );
    assert!(
        access.contains(&format!("href=\"/devices/{id}/provision\"")),
        "{access}"
    );
    for missing in ["id=\"quick\"", "id=\"attention\"", "id=\"today\"", "/lifts"] {
        assert!(!html.contains(missing), "{missing}");
    }
    for row in [
        "calls", "apps", "music", "time", "lock", "home", "filter", "about", "advanced",
    ] {
        assert!(html.contains(&format!("data-row=\"{row}\"")), "{row}");
    }
    assert!(html.contains(&format!("href=\"/devices/{id}/lock\" data-row=\"lock\"")));
    // Managed without a Parent PIN: said above Connect This Phone, since there are no Attention
    // rows yet (qa-31-s10-code L1); gone once a PIN is set.
    let warning = format!("href=\"/devices/{id}/lock#parent-pin\" data-warning=\"no-parent-pin\"");
    assert!(!html.contains(&warning), "{html}");
    sqlx::query("UPDATE device_policy SET allowlist_json = '[]' WHERE device_id = ?")
        .bind(id)
        .execute(&app.db)
        .await
        .unwrap();
    let html = app
        .get_page(&format!("/devices/{id}"), &cookie)
        .await
        .text();
    assert!(html.contains(&warning), "{html}");
    assert!(html.find(&warning) < html.find("<section id=\"access\""));
    sqlx::query(
        "UPDATE device_policy SET override_pin_hash = 'aa', override_pin_salt = 'bb' \
         WHERE device_id = ?",
    )
    .bind(id)
    .execute(&app.db)
    .await
    .unwrap();
    let html = app
        .get_page(&format!("/devices/{id}"), &cookie)
        .await
        .text();
    assert!(!html.contains(&warning), "{html}");
}

/// Today keeps today's command answers and any still waiting (qa-31-s10-code N2), and a pause
/// still running behind more than ten newer lifts keeps its End now (N3).
#[tokio::test]
async fn today_is_today_and_keeps_running_pauses() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, _) = app.enrolled_device("Anna").await;
    for (command, acknowledged) in [
        ("end_windows", "NULL"),
        ("allow_blocked", "datetime('now', '-2 days')"),
    ] {
        sqlx::query(&format!(
            "INSERT INTO device_commands (device_id, command, requested_at, acknowledged_at) \
             VALUES (?, ?, datetime('now', '-2 days'), {acknowledged})"
        ))
        .bind(id)
        .bind(command)
        .execute(&app.db)
        .await
        .unwrap();
    }
    sqlx::query(
        "INSERT INTO time_lifts (device_id, target, rule_name, minutes, created_at, expires_at) \
         VALUES (?, 'rule', 'Skole', 120, datetime('now', '-1 minute'), datetime('now', '+2 hours'))",
    )
    .bind(id)
    .execute(&app.db)
    .await
    .unwrap();
    let pause: i64 = sqlx::query_scalar("SELECT MAX(id) FROM time_lifts")
        .fetch_one(&app.db)
        .await
        .unwrap();
    for _ in 0..11 {
        sqlx::query(
            "INSERT INTO time_lifts (device_id, target, minutes, expires_at) \
             VALUES (?, 'budget', 15, datetime('now', '+12 hours'))",
        )
        .bind(id)
        .execute(&app.db)
        .await
        .unwrap();
    }
    let html = app
        .get_page(&format!("/devices/{id}"), &cookie)
        .await
        .text();
    let today = group(&html, "today");
    assert!(
        today.contains(&format!("action=\"/devices/{id}/lifts/{pause}/end#today\"")),
        "{today}"
    );
    assert_eq!(today.matches("+15 min screen time").count(), 11, "{today}");
    // The waiting Lock again stays; the Allow answered two days ago is gone.
    assert!(today.contains("data-answer=\"Lock again\""), "{today}");
    assert!(today.contains("No answer yet"), "{today}");
    assert!(
        !today.contains("data-answer=\"Allow these apps\""),
        "{today}"
    );
}

/// The phone, its settings, its last report and its lifts are a 500 on a DB error - never an
/// empty summary that reads as "nothing to say"; an unknown phone is a 404.
#[tokio::test]
async fn the_summary_fails_loudly_on_a_db_error() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, _) = app.enrolled_device("Anna").await;
    // The catalog's pins and the commands' answers too: without them an imposter or an answered
    // Allow would be offered (qa-31-s10-code L4).
    for table in [
        "time_lifts",
        "device_status",
        "device_policy",
        "tracked_apps",
        "device_commands",
    ] {
        sqlx::query(&format!("ALTER TABLE {table} RENAME TO {table}_gone"))
            .execute(&app.db)
            .await
            .unwrap();
        let res = app.get_page(&format!("/devices/{id}"), &cookie).await;
        assert_eq!(res.status, StatusCode::INTERNAL_SERVER_ERROR, "{table}");
        assert!(res.text().contains("Couldn't read this phone"), "{table}");
        sqlx::query(&format!("ALTER TABLE {table}_gone RENAME TO {table}"))
            .execute(&app.db)
            .await
            .unwrap();
    }
    let res = app.get_page(&format!("/devices/{id}"), &cookie).await;
    assert_eq!(res.status, StatusCode::OK);
    let res = app.get_page("/devices/9999", &cookie).await;
    assert_eq!(res.status, StatusCode::NOT_FOUND);
}
