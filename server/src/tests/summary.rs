//! The phone's summary (`/devices/{id}`, design 31 §3.2, step 10): the header, Quick actions,
//! Now and Activity (design 48), the not-set-up variant, where every form returns, and the reads
//! that must fail loudly.
//! The design 24b groups (the foreign-app warning, blocked apps, the last relock) are pinned in
//! `window_relock.rs`, the Attention rows in `attention.rs` and `devices_list.rs`.

use axum::http::{Method, StatusCode};
use chrono::{DateTime, Duration, Utc};
use serde_json::json;

use super::settings_pages::forms;
use super::{Section, TestApp, assert_redirect, device_page};

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
    app.post_status(&token, json!({})).await;
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
    app.post_status(
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
    app.post_status(
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
/// confirm only for a launcher that takes it, Locate a link; no auto-saving form on the page but
/// the header's picture (server round C).
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
    // The header's picture picker saves itself (server round C, `tests::avatars`); nothing else.
    let main = &html[html.find("<main").unwrap()..];
    assert_eq!(main.matches("data-autosave").count(), 1, "{main}");
    assert!(
        main.contains("name=\"picture\" accept=\"image/*\" data-autosave"),
        "{main}"
    );

    add_rule(&app, "Skole <i>").await;
    app.post_status(&token, json!({ "capabilities": ["window_relock_v1"] }))
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
/// the quick actions (End now at their end), the blocked apps - with the flash there; built from the rendered forms
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
    app.post_status(&token,
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
            format!("/devices/{id}/lifts/1/end#quick"),
            vec![],
            format!("{summary}?done=ended"),
            "quick",
        ),
        (
            format!("/devices/{id}/allow-blocked#blocked-apps"),
            vec![],
            format!("{summary}?notice=allow_blocked_sent"),
            "blocked-apps",
        ),
    ];
    let mut found = forms(&html);
    // The header's picture comes first: a multipart upload, `tests::avatars`.
    assert_eq!(found.remove(0).0, format!("/devices/{id}/avatar#avatar"));
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

/// §3.2 item 4: Quick Actions ends with the pauses End now can still end (Now went, the user
/// 2026-10-20), in local time with the exact one in `title`; a pause of today that is over and
/// added screen time are history - Device › Time's only, nowhere on the summary.
#[tokio::test]
async fn quick_actions_list_running_pauses_in_local_time() {
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
    let mut ids = Vec::new();
    for (target, rule, expires) in [
        ("rule", "Skole", "datetime('now', '+1 hour')"),
        ("rule", "Kveld", "datetime('now', '-1 minute')"),
        ("budget", "", "datetime('now', '+12 hours')"),
    ] {
        let lift: i64 = sqlx::query_scalar(&format!(
            "INSERT INTO time_lifts (device_id, target, rule_name, minutes, created_at, expires_at, created_by) \
             VALUES (?, ?, NULLIF(?, ''), 15, ?, {expires}, 'parent') RETURNING id"
        ))
        .bind(id)
        .bind(target)
        .bind(rule)
        .bind(&stamp)
        .fetch_one(&app.db)
        .await
        .unwrap();
        ids.push(lift);
    }
    let html = app
        .get_page(&format!("/devices/{id}"), &cookie)
        .await
        .text();
    let quick = group(&html, "quick");
    let oslo = made.with_timezone(&chrono_tz::Europe::Oslo);
    assert!(
        quick.contains(&format!("data-lift=\"{}\"", ids[0])),
        "{quick}"
    );
    assert!(quick.contains("Skole paused for 15 min"), "{quick}");
    assert!(
        quick.contains(&format!(
            "title=\"{}\">{} · parent</span>",
            oslo.format("%Y-%m-%d %H:%M:%S %Z"),
            oslo.format("%H:%M")
        )),
        "{quick}"
    );
    // The pause that is over and the screen time: nowhere on the summary.
    for lift in &ids[1..] {
        assert!(!html.contains(&format!("data-lift=\"{lift}\"")), "{html}");
    }
    assert!(!html.contains("Kveld"), "{html}");
    assert!(!html.contains("+15 min screen time"), "{html}");
    // Nothing happened: no lift rows, no Now, no Activity.
    let (emil, _) = app.enrolled_device("Emil").await;
    let html = app
        .get_page(&format!("/devices/{emil}"), &cookie)
        .await
        .text();
    assert!(!html.contains("data-lift"), "{html}");
    assert!(!html.contains("id=\"now\""), "{html}");
    assert!(!html.contains("id=\"activity\""), "{html}");
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

/// A pause still running behind more than ten newer lifts keeps its End now in Quick Actions
/// (qa-31-s10-code N3). A command is one row, in Activity: the lift rows have no answer lines
/// (design 48 §1.1).
#[tokio::test]
async fn quick_actions_keep_running_pauses_and_no_answer_lines() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, _) = app.enrolled_device("Anna").await;
    command(&app, id, "end_windows", Utc::now() - Duration::days(2), "").await;
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
    let quick = group(&html, "quick");
    assert!(
        quick.contains(&format!("action=\"/devices/{id}/lifts/{pause}/end#quick\"")),
        "{quick}"
    );
    assert!(!quick.contains("+15 min screen time"), "{quick}");
    assert!(!html.contains("data-answer"), "{html}");
    assert!(
        group(&html, "activity").contains("data-command=\"end_windows\""),
        "{html}"
    );
}

/// End now on a pause made yesterday: once ended it is neither running nor today's, so its row
/// is gone and Quick Actions has the flash where the save lands (no jump).
#[tokio::test]
async fn end_now_lands_on_quick_actions_when_nothing_else_is_left() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, _) = app.enrolled_device("Anna").await;
    sqlx::query(
        "INSERT INTO time_lifts (device_id, target, rule_name, minutes, created_at, expires_at) \
         VALUES (?, 'rule', 'Skole', 1500, datetime('now', '-1 day'), datetime('now', '+2 hours'))",
    )
    .bind(id)
    .execute(&app.db)
    .await
    .unwrap();
    let html = app
        .get_page(&format!("/devices/{id}"), &cookie)
        .await
        .text();
    let (action, hidden) = forms(group(&html, "quick"))
        .into_iter()
        .find(|(action, _)| action.ends_with("/end#quick"))
        .expect("End now");
    let sent: Vec<(&str, &str)> = hidden
        .iter()
        .map(|(k, v)| (k.as_str(), v.as_str()))
        .collect();
    let res = app
        .request_form(
            Method::POST,
            action.split('#').next().unwrap(),
            Some(&cookie),
            &sent,
        )
        .await;
    assert_redirect(&res, &format!("/devices/{id}?done=ended"), Some("quick"));
    app.assert_lands(&res, &cookie).await;
    let html = app
        .get_page(&format!("/devices/{id}?done=ended"), &cookie)
        .await
        .text();
    let quick = group(&html, "quick");
    assert!(quick.contains("data-flash=\"quick\""), "{quick}");
    assert!(!html.contains("data-lift"), "{html}");
    assert!(!html.contains("id=\"now\""), "{html}");
}

/// Stored like `datetime('now')`.
fn db_time(t: DateTime<Utc>) -> String {
    t.format("%Y-%m-%d %H:%M:%S").to_string()
}

/// A command asked at `requested`; `then` sets more columns (SQL, may name `requested_at`).
async fn command(
    app: &TestApp,
    device: i64,
    command: &str,
    requested: DateTime<Utc>,
    then: &str,
) -> i64 {
    let id = sqlx::query_scalar(
        "INSERT INTO device_commands (device_id, command, requested_at) VALUES (?, ?, ?) \
         RETURNING id",
    )
    .bind(device)
    .bind(command)
    .bind(db_time(requested))
    .fetch_one(&app.db)
    .await
    .unwrap();
    if !then.is_empty() {
        sqlx::query(&format!("UPDATE device_commands SET {then} WHERE id = ?"))
            .bind(id)
            .execute(&app.db)
            .await
            .unwrap();
    }
    id
}

/// One of the phone's events, happened at `at` (stored as `parent_events::raise` does).
async fn event(app: &TestApp, device: i64, title: &str, at: DateTime<Utc>, target: &str) -> i64 {
    sqlx::query_scalar(
        "INSERT INTO parent_events (device_id, kind, dedup_key, title, summary, target, happened_at) \
         VALUES (?, 'pin_used', ?, ?, 'Lock screen opened', ?, ?) RETURNING id",
    )
    .bind(device)
    .bind(title)
    .bind(title)
    .bind(target)
    .bind(at.to_rfc3339_opts(chrono::SecondsFormat::Millis, true))
    .fetch_one(&app.db)
    .await
    .unwrap()
}

/// Where each needle first is in `haystack`.
fn at(haystack: &str, needle: &str) -> usize {
    haystack
        .find(needle)
        .unwrap_or_else(|| panic!("no {needle} in {haystack}"))
}

/// Design 48 §1.2, §1.3: commands and events in one list, newest first by when each was asked or
/// happened, each with the clock in the Phone Setup zone ("13:41", "yesterday 14:50", "15 Oct
/// 14:02"); an event without its avatar; the last row leads to this phone's inbox.
#[tokio::test]
async fn activity_mixes_commands_and_events_newest_first_with_the_clock() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, _) = app.enrolled_device("Anna").await;
    sqlx::query("UPDATE provisioning_settings SET time_zone = 'Europe/Oslo'")
        .execute(&app.db)
        .await
        .unwrap();
    let oslo = chrono_tz::Europe::Oslo;
    let now = Utc::now();
    let today = now.with_timezone(&oslo).date_naive();
    let local = |days: i64, h: u32, m: u32| {
        (today - Duration::days(days))
            .and_hms_opt(h, m, 0)
            .unwrap()
            .and_local_timezone(oslo)
            .single()
            .unwrap()
            .with_timezone(&Utc)
    };
    let this_minute = DateTime::from_timestamp(now.timestamp() - now.timestamp() % 60, 0).unwrap();
    let done = "delivered_at = requested_at, acknowledged_at = requested_at, result = 'ok'";
    command(
        &app,
        id,
        "end_windows",
        this_minute,
        &done.replace("'ok'", "'ended: pause'"),
    )
    .await;
    let pin = event(&app, id, "Parent PIN used", local(1, 14, 50), "/devices").await;
    command(&app, id, "ring", local(3, 14, 2), done).await;
    let off = event(&app, id, "Phone turned off", local(4, 9, 0), "/devices").await;

    let html = app
        .get_page(&format!("/devices/{id}"), &cookie)
        .await
        .text();
    let activity = group(&html, "activity");
    let order = [
        at(activity, "data-command=\"end_windows\""),
        at(activity, &format!("data-event=\"{pin}\"")),
        at(activity, "data-command=\"ring\""),
        at(activity, &format!("data-event=\"{off}\"")),
        at(activity, "data-row=\"everything\""),
    ];
    assert!(order.is_sorted(), "{activity}");
    let date = |t: DateTime<Utc>| {
        let t = t.with_timezone(&oslo);
        if t.date_naive().format("%Y").to_string() == today.format("%Y").to_string() {
            t.format("%-d %b %H:%M").to_string()
        } else {
            t.format("%-d %b %Y %H:%M").to_string()
        }
    };
    for words in [
        format!(
            ">{} · <span class=\"tone-green\">Done<",
            this_minute.with_timezone(&oslo).format("%H:%M")
        ),
        ">yesterday 14:50<".to_string(),
        format!(
            ">{} · <span class=\"tone-green\">Done<",
            date(local(3, 14, 2))
        ),
        format!(">{}<", date(local(4, 9, 0))),
    ] {
        assert!(activity.contains(&words), "{words}: {activity}");
    }
    assert!(!activity.contains("class=\"avatar"), "{activity}");
    assert!(
        activity.contains(&format!(
            "href=\"/notifications?from=devices&amp;device={id}\""
        )),
        "{activity}"
    );
    // Under Quick actions.
    assert!(html.find("id=\"quick\"").unwrap() < html.find("id=\"activity\"").unwrap());
    super::settings_pages::check_forms(&format!("/devices/{id}"), &html);
}

/// One clock per Activity row (the user, 2026-10-20: "· 09:20 · 09:20"): design 38's start and
/// the Parent PIN's use carry their clock in the summary, which the row shows once, after it.
#[tokio::test]
async fn an_event_row_shows_its_clock_once() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, _) = app.enrolled_device("Sverre").await;
    sqlx::query("UPDATE provisioning_settings SET time_zone = 'Europe/Oslo'")
        .execute(&app.db)
        .await
        .unwrap();
    let now = Utc::now() - Duration::minutes(1);
    let happened = DateTime::from_timestamp(now.timestamp() - now.timestamp() % 60, 0).unwrap();
    let clock = happened
        .with_timezone(&chrono_tz::Europe::Oslo)
        .format("%H:%M")
        .to_string();
    // The last report's own clock, three hours earlier: never the row's.
    let earlier = (happened - Duration::hours(3))
        .with_timezone(&chrono_tz::Europe::Oslo)
        .format("%H:%M")
        .to_string();
    let mut ids = Vec::new();
    for (kind, title, summary) in [
        (
            "phone_on",
            "Sverre was turned on",
            format!("{clock} · no shutdown seen · last report {earlier}, battery 10 %"),
        ),
        (
            "pin_used",
            "Parent PIN used on Sverre",
            format!("Vibb One's Settings · {clock}"),
        ),
    ] {
        let event: i64 = sqlx::query_scalar(
            "INSERT INTO parent_events (device_id, kind, dedup_key, title, summary, target, happened_at) \
             VALUES (?, ?, ?, ?, ?, '/devices', ?) RETURNING id",
        )
        .bind(id)
        .bind(kind)
        .bind(kind)
        .bind(title)
        .bind(&summary)
        .bind(happened.to_rfc3339_opts(chrono::SecondsFormat::Millis, true))
        .fetch_one(&app.db)
        .await
        .unwrap();
        ids.push(event);
    }
    let html = app
        .get_page(&format!("/devices/{id}"), &cookie)
        .await
        .text();
    let activity = group(&html, "activity");
    let row = |event: i64| {
        let start = at(activity, &format!("data-event=\"{event}\""));
        let end = start + at(&activity[start..], "</button>");
        &activity[start..end]
    };
    let (on, pin) = (row(ids[0]), row(ids[1]));
    assert!(
        on.contains(&format!(
            "no shutdown seen · last report {earlier}, battery 10 % · <span title="
        )),
        "{on}"
    );
    assert!(
        pin.contains("Vibb One&#39;s Settings · <span title="),
        "{pin}"
    );
    for row in [on, pin] {
        // Once in the text; the exact time in `title=` ("… 18:56:00 CEST") is not shown.
        let exact = row.matches(&format!("{clock}:00")).count();
        assert_eq!(row.matches(&clock).count() - exact, 1, "{row}");
        assert!(row.contains(&format!(">{clock}</span>")), "{row}");
    }
}

/// Design 48 §1.2, 48-7: the last week and at most ten rows - but a command that can still be
/// cancelled is shown at any age, outside both, with its Cancel back to `#activity`, where the
/// line shows even when nothing else is left.
#[tokio::test]
async fn activity_keeps_a_week_ten_rows_and_what_can_be_cancelled() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, _) = app.enrolled_device("Anna").await;
    let now = Utc::now();
    let eight_days = now - Duration::days(8);
    let waiting = command(&app, id, "end_windows", eight_days, "").await;
    let done = "delivered_at = requested_at, acknowledged_at = requested_at, result = 'ok'";
    command(&app, id, "ring", eight_days, done).await;
    event(&app, id, "Old", eight_days, "/devices").await;
    for hour in 1..=12 {
        event(
            &app,
            id,
            &format!("Event {hour}"),
            now - Duration::hours(hour),
            "/devices",
        )
        .await;
    }
    let summary = format!("/devices/{id}");
    let html = app.get_page(&summary, &cookie).await.text();
    let activity = group(&html, "activity");
    assert_eq!(activity.matches("data-event=").count(), 10, "{activity}");
    assert!(activity.contains(">Event 10<"), "{activity}");
    assert!(!activity.contains(">Event 11<"), "{activity}");
    assert!(!activity.contains(">Old<"), "{activity}");
    assert!(!activity.contains("data-command=\"ring\""), "{activity}");
    let row = super::locate_page::recent_row(activity, "end_windows");
    assert!(row.contains(">Cancel</button>"), "{row}");
    let cancel = format!("/devices/{id}/commands/{waiting}/cancel#activity");
    assert!(
        activity.contains(&format!("action=\"{cancel}\"")),
        "{activity}"
    );

    // The only row of another phone: cancelled, the group stays for its line.
    let (emil, _) = app.enrolled_device("Emil").await;
    let allow = command(&app, emil, "allow_blocked", eight_days, "").await;
    let html = app
        .get_page(&format!("/devices/{emil}"), &cookie)
        .await
        .text();
    let (action, hidden) = forms(group(&html, "activity"))
        .into_iter()
        .find(|(action, _)| action.contains(&format!("/commands/{allow}/cancel")))
        .expect("the Cancel");
    assert!(action.ends_with("#activity"), "{action}");
    let sent: Vec<(&str, &str)> = hidden
        .iter()
        .map(|(k, v)| (k.as_str(), v.as_str()))
        .collect();
    let res = app
        .request_form(
            Method::POST,
            action.split('#').next().unwrap(),
            Some(&cookie),
            &sent,
        )
        .await;
    assert_redirect(
        &res,
        &format!("/devices/{emil}?cancel=done"),
        Some("activity"),
    );
    app.assert_lands(&res, &cookie).await;
    let html = app
        .get_page(&format!("/devices/{emil}?cancel=done"), &cookie)
        .await
        .text();
    let activity = group(&html, "activity");
    assert!(
        activity.contains("data-flash=\"activity\">Cancelled. The phone will never get it."),
        "{activity}"
    );
    assert!(
        !activity.contains("data-command"),
        "8 days old and done: {activity}"
    );
}

/// Design 48 §1.2, 48-10: an event's row marks it read and goes where it happened; its form
/// carries this phone, so one pruned meanwhile (or a target that isn't a local path) comes back
/// to this phone's inbox, not the top of the summary.
#[tokio::test]
async fn an_event_opens_where_it_happened_or_this_phones_inbox() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, _) = app.enrolled_device("Anna").await;
    let target = format!("/devices/{id}/lock#parent-pin");
    let pin = event(&app, id, "Parent PIN used", Utc::now(), &target).await;
    let html = app
        .get_page(&format!("/devices/{id}"), &cookie)
        .await
        .text();
    let (action, hidden) = forms(group(&html, "activity"))
        .into_iter()
        .find(|(action, _)| action == &format!("/notifications/{pin}/open"))
        .expect("the event's form");
    assert_eq!(
        hidden,
        [("from", "devices".to_string()), ("device", id.to_string())]
            .map(|(k, v)| (k.to_string(), v))
    );
    let sent: Vec<(&str, &str)> = hidden
        .iter()
        .map(|(k, v)| (k.as_str(), v.as_str()))
        .collect();
    let res = app
        .request_form(Method::POST, &action, Some(&cookie), &sent)
        .await;
    assert_redirect(&res, &format!("/devices/{id}/lock"), Some("parent-pin"));
    let read: bool =
        sqlx::query_scalar("SELECT EXISTS (SELECT 1 FROM parent_event_reads WHERE event_id = ?)")
            .bind(pin)
            .fetch_one(&app.db)
            .await
            .unwrap();
    assert!(read);
    sqlx::query("DELETE FROM parent_events")
        .execute(&app.db)
        .await
        .unwrap();
    let res = app
        .request_form(Method::POST, &action, Some(&cookie), &sent)
        .await;
    assert_redirect(
        &res,
        &format!("/notifications?from=devices&device={id}"),
        Some("inbox"),
    );
    app.assert_lands(&res, &cookie).await;
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
