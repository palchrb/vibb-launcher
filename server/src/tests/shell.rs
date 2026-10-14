//! Design 31 step 1, the shell: the 5-tab bar set per template, the Settings hub, the "Vibb Phone"
//! name, `autosave.js` instead of inline `onchange`, and the page-markup helpers in `tests/mod.rs`.

use axum::http::StatusCode;

use super::{
    Section, TestApp, assert_redirect, autosaves, device_page, form_action_of, input_checked,
    nav_current,
};

/// Every template that has the tab bar marks its own tab (L1), with no script: one landing page
/// and one inner page per tab.
#[tokio::test]
async fn every_page_marks_its_own_tab() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, _) = app.enrolled_device("phone").await;
    let entry = super::music::add_own(&app, &cookie, "Bilturen").await;
    let pages = [
        ("/devices".to_string(), "devices"),
        (device_page(id, Section::Summary), "devices"),
        (device_page(id, Section::Calls), "devices"),
        (device_page(id, Section::About), "devices"),
        (device_page(id, Section::Advanced), "devices"),
        (device_page(id, Section::Home), "devices"),
        (device_page(id, Section::Filter), "devices"),
        (device_page(id, Section::Time), "devices"),
        ("/devices/new".to_string(), "devices"),
        (format!("/devices/{id}/provision"), "devices"),
        ("/apps".to_string(), "apps"),
        ("/apps/tracked/new".to_string(), "apps"),
        ("/devices/locate".to_string(), "locate"),
        (format!("/devices/locate?device={id}"), "locate"),
        ("/music".to_string(), "music"),
        (format!("/music/entries/{entry}"), "music"),
        ("/settings".to_string(), "settings"),
        ("/schedules".to_string(), "settings"),
        ("/schedules/screen-time".to_string(), "settings"),
        ("/schedules/rules/new".to_string(), "settings"),
        ("/dns".to_string(), "settings"),
        ("/dns/log".to_string(), "settings"),
        ("/wallpapers".to_string(), "settings"),
        ("/settings/provisioning".to_string(), "settings"),
        ("/account".to_string(), "settings"),
        ("/security".to_string(), "settings"),
        ("/backups".to_string(), "settings"),
        // `/updates` asks GitHub when it renders; its tab is checked in the template below.
    ];
    for (page, tab) in pages {
        let res = app.get_page(&page, &cookie).await;
        assert_eq!(res.status, StatusCode::OK, "{page}");
        let html = res.text();
        assert_eq!(nav_current(&html), tab, "{page}");
        assert_eq!(
            html.matches("aria-current=\"page\"").count(),
            1,
            "{page}: one current tab"
        );
        // The bar has the 5 tabs, in order, and no script marks them.
        let bar = &html[html.find("<nav class=\"tab-bar\"").unwrap()..];
        let order: Vec<usize> = ["Devices", "Apps", "Locate", "Music", "Settings"]
            .iter()
            .map(|label| bar.find(&format!("<span>{label}</span>")).unwrap())
            .collect();
        assert!(order.windows(2).all(|w| w[0] < w[1]), "{page}: tab order");
        assert!(!html.contains("data-match="), "{page}: no path matcher");
        assert!(!html.contains("DNS/Filters"), "{page}: the old tab");
    }
    // The sign-in page has no tab bar.
    let login = app.get_page("/login", "").await.text();
    assert_eq!(nav_current(&login), "");
    assert!(login.contains("<h1>Vibb Phone</h1>"));
}

/// Every template with the tab bar sets its tab right before including it, to one of the five
/// (the template decides, not the path - §3.1). A rebuilt sub-page includes the bar alone, without
/// the brand bar (§3.0: its back link and large title replace it).
#[test]
fn every_template_with_the_bar_sets_its_tab() {
    let mut with_bar = 0;
    let mut bar_alone = 0;
    for path in super::device_api::template_files() {
        let text = std::fs::read_to_string(&path).unwrap();
        let header = text.find("{% include \"partials/app_header.html\" %}");
        let alone = text.find("{% include \"partials/tab_bar.html\" %}");
        assert!(
            header.is_none() || alone.is_none(),
            "{}: the bar twice",
            path.display()
        );
        let Some(at) = header.or(alone) else {
            continue;
        };
        if alone.is_some() {
            bar_alone += 1;
            assert!(
                text.contains("class=\"nav-back\""),
                "{}: a sub-page without its back link",
                path.display()
            );
        }
        with_bar += 1;
        let before = text[..at].trim_end();
        let line = before.lines().last().unwrap_or("").trim();
        let tab = line
            .strip_prefix("{% let tab = \"")
            .and_then(|rest| rest.strip_suffix("\" %}"))
            .unwrap_or_else(|| panic!("{}: no tab before the header", path.display()));
        assert!(
            ["devices", "apps", "locate", "music", "settings"].contains(&tab),
            "{}: {tab}",
            path.display()
        );
    }
    assert!(with_bar >= 25, "{with_bar} templates with the bar");
    assert!(
        bar_alone >= 4,
        "About This Phone, Advanced, the app page and Add App"
    );
    let updates = std::fs::read_to_string("templates/updates.html").unwrap();
    assert!(updates.contains("{% let tab = \"settings\" %}"));
}

/// Apps and Music are tabs of their own now: the Apps|Music switch is gone from both pages.
#[tokio::test]
async fn apps_and_music_have_no_sub_switch() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    for page in ["/apps", "/music"] {
        let html = app.get_page(page, &cookie).await.text();
        assert!(!html.contains("section-tabs"), "{page}");
        assert!(!html.contains("aria-label=\"Apps and music\""), "{page}");
    }
}

/// The name is "Vibb Phone" (§5.3, L4); the manifest keeps its URL and identity.
#[tokio::test]
async fn the_app_is_called_vibb_phone_with_a_stable_identity() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let manifest = app
        .get_page("/static/manifest.webmanifest", &cookie)
        .await
        .text();
    let manifest: serde_json::Value = serde_json::from_str(&manifest).unwrap();
    assert_eq!(manifest["id"], "/");
    assert_eq!(manifest["start_url"], "/");
    assert_eq!(manifest["scope"], "/");
    assert_eq!(manifest["display"], "standalone");
    assert_eq!(manifest["name"], "Vibb Phone");
    assert_eq!(manifest["short_name"], "Vibb Phone");

    let html = app.get_page("/devices", &cookie).await.text();
    for needle in [
        "<title>Devices · Vibb Phone</title>",
        "<a href=\"/\" class=\"brand\">Vibb Phone</a>",
        "<meta name=\"apple-mobile-web-app-title\" content=\"Vibb Phone\">",
        "<meta name=\"color-scheme\" content=\"light dark\">",
        "<meta name=\"theme-color\" content=\"#F9F9FA\" media=\"(prefers-color-scheme: light)\">",
        "<meta name=\"theme-color\" content=\"#161618\" media=\"(prefers-color-scheme: dark)\">",
        // The manifest URL stays: older Chrome keys a WebAPK to it.
        "<link rel=\"manifest\" href=\"/static/manifest.webmanifest\">",
        // Not deferred: a switch tapped while the page still loads saves too (qa-31-s1-code L1).
        "<script src=\"/static/autosave.js?v=2\"></script>",
    ] {
        assert!(html.contains(needle), "{needle}");
    }
    assert!(!html.contains("Kids Device MDM") && !html.contains("Kids MDM"));
    // The script that marked the tab is gone.
    assert!(!html.contains("bottom-nav"));
    // The service worker's registration is ES5 (§5.7).
    let register = std::fs::read_to_string("static/register-sw.js").unwrap();
    assert!(!register.contains("=>") && register.contains("function"));
}

/// The 54 inline `onchange="this.form.submit()"` became `data-autosave` (L7), handled by one
/// static script that saves through the hooked `form.submit()`.
#[tokio::test]
async fn auto_saving_controls_use_the_static_script() {
    for path in super::device_api::template_files() {
        let text = std::fs::read_to_string(&path).unwrap();
        assert!(
            !text.contains("this.form.submit()"),
            "{} still submits inline",
            path.display()
        );
    }
    let script = std::fs::read_to_string("static/autosave.js").unwrap();
    assert!(script.contains("data-autosave") && script.contains("form.submit()"));

    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, _) = app.enrolled_device("phone").await;
    let advanced = app
        .get_page(&device_page(id, Section::Advanced), &cookie)
        .await
        .text();
    assert!(autosaves(&advanced, "disallow_factory_reset"));
    assert_eq!(
        form_action_of(&advanced, "disallow_factory_reset"),
        Some(format!("/devices/{id}/hardening").as_str())
    );
    let html = app
        .get_page(&device_page(id, Section::Lock), &cookie)
        .await
        .text();
    assert!(autosaves(&html, "screen_timeout_seconds"));
    // A Save button form doesn't save itself.
    assert!(!autosaves(&html, "new_pin"));
}

/// The markup helpers read attributes, not their order or spacing.
#[test]
fn the_markup_helpers_read_attributes() {
    let html = r#"
        <form method="post" action="/a"><input type="checkbox" name="x" value="on"  data-autosave>
        <input type="hidden" name="back" value="apps"></form>
        <form action='/b'>
          <input role="switch" class="sw" checked name="x" value="2" onchange="this.form.submit()">
          <select name="pick" data-autosave><option>1</option></select>
          <input type="text" name="plain" value="a > b">
        </form>
        <input name="loose">
        <nav class="tab-bar" aria-label="Main"><a href="/x" data-tab="apps">A</a>
          <a data-tab="music" aria-current="page" href="/music">M</a></nav>"#;
    assert_eq!(input_checked(html, "x", "on"), Some(false));
    assert_eq!(input_checked(html, "x", "2"), Some(true));
    assert_eq!(input_checked(html, "x", ""), Some(false), "the first one");
    assert_eq!(input_checked(html, "x", "3"), None);
    assert_eq!(input_checked(html, "missing", ""), None);
    assert!(autosaves(html, "x"));
    assert!(autosaves(html, "pick"));
    assert!(!autosaves(html, "plain"));
    assert!(!autosaves(html, "back"));
    assert_eq!(form_action_of(html, "back"), Some("/a"));
    assert_eq!(form_action_of(html, "pick"), Some("/b"));
    assert_eq!(form_action_of(html, "plain"), Some("/b"));
    assert_eq!(form_action_of(html, "loose"), None, "outside every form");
    assert_eq!(nav_current(html), "music");
    assert_eq!(
        nav_current("<nav class=\"tab-bar\"><a data-tab=\"apps\">"),
        ""
    );
}

#[tokio::test]
async fn redirects_are_checked_by_path_and_fragment() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let res = app
        .request_form(axum::http::Method::POST, "/logout", Some(&cookie), &[])
        .await;
    assert_redirect(&res, "/login", None);
}

/// Settings is the hub (§3.6): every entrance the old Time rules and DNS/Filters tabs had, with
/// values from the database.
#[tokio::test]
async fn the_settings_hub_links_everything_with_cheap_values() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, _) = app.enrolled_device("phone").await;
    let days = "[null,null,null,null,null,null,null]";
    for (device, name) in [(None, "Skole"), (None, "Leggetid"), (Some(id), "Own")] {
        sqlx::query(
            "INSERT INTO time_rules (device_id, name, kind, calls_allowed, days_json) \
             VALUES (?, ?, 'custom', 1, ?)",
        )
        .bind(device)
        .bind(name)
        .bind(days)
        .execute(&app.db)
        .await
        .unwrap();
    }
    sqlx::query("UPDATE dns_blocklists SET enabled = 1 WHERE id IN (1, 2, 3)")
        .execute(&app.db)
        .await
        .unwrap();
    for received in [
        "datetime('now')",
        "datetime('now')",
        "'2001-01-01 00:00:00'",
    ] {
        sqlx::query(&format!(
            "INSERT INTO device_dns_events (device_id, domain, category, blocked_at, received_at) \
             VALUES (?, 'ads.example', 'ads', '2026-10-13T10:00:00Z', {received})"
        ))
        .bind(id)
        .execute(&app.db)
        .await
        .unwrap();
    }
    let wallpapers: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM wallpapers")
        .fetch_one(&app.db)
        .await
        .unwrap();

    let html = app.get_page("/settings", &cookie).await.text();
    assert_eq!(nav_current(&html), "settings");
    let row = |name: &str| -> String {
        let start = html
            .find(&format!("data-row=\"{name}\""))
            .unwrap_or_else(|| panic!("row {name}"));
        let start = html[..start].rfind("<a").unwrap();
        let rest = &html[start..];
        rest[..rest.find("</a>").unwrap()].to_string()
    };
    for (name, href, value) in [
        ("time-rules", "/schedules", Some("2 rules".to_string())),
        ("web-filter", "/dns", Some("3 lists".to_string())),
        ("blocked-activity", "/dns/log", Some("2 today".to_string())),
        ("wallpapers", "/wallpapers", Some(wallpapers.to_string())),
        ("phone-setup", "/settings/provisioning", None),
        ("software-updates", "/updates", None),
        ("account", "/account", None),
        ("security-log", "/security", None),
        (
            "about",
            "/updates",
            Some(format!(
                "Server {}",
                crate::APP_VERSION.trim_start_matches('v')
            )),
        ),
    ] {
        let row = row(name);
        assert!(row.contains(&format!("href=\"{href}\"")), "{name}: {row}");
        match value {
            Some(value) => assert!(
                row.contains(&format!("<span class=\"row-value\">{value}</span>")),
                "{name}: {row}"
            ),
            None => assert!(!row.contains("row-value"), "{name}: {row}"),
        }
    }
    // Backups shows the newest file's day when there is one (`hub_values` below); always a link.
    assert!(row("backups").contains("href=\"/backups\""));
    assert!(html.contains("<form method=\"post\" action=\"/logout\" class=\"group\">"));
    assert!(html.contains(">Sign Out</button>"));
    // Outline icons on tiles, not emoji.
    assert!(!html.contains("&#128"));
}

#[tokio::test]
async fn hub_values_say_none_when_there_is_nothing() {
    let app = TestApp::new().await;
    sqlx::query("UPDATE dns_blocklists SET enabled = 0")
        .execute(&app.db)
        .await
        .unwrap();
    let dir = tempfile::tempdir().unwrap();
    let now = chrono::Utc::now();
    let zone = crate::display_time::Zone::from_setting("Europe/Oslo");
    let values =
        crate::handlers::settings_hub::hub_values(&app.db, &dir.path().join("none"), &zone, now)
            .await;
    assert_eq!(values.time_rules.as_deref(), Some("No rules"));
    assert_eq!(values.web_filter.as_deref(), Some("No lists"));
    assert_eq!(values.blocked_today.as_deref(), Some("None today"));
    assert_eq!(values.backups, None, "no backup directory");

    // The newest backup zip by its time; other files don't count, however new.
    let day = std::time::Duration::from_secs(86_400);
    let made = std::time::SystemTime::now();
    let put = |name: &str, age: std::time::Duration| -> std::time::SystemTime {
        let path = dir.path().join(name);
        std::fs::write(&path, b"x").unwrap();
        let time = made - age;
        std::fs::File::options()
            .write(true)
            .open(&path)
            .unwrap()
            .set_modified(time)
            .unwrap();
        time
    };
    put("backup-20260904-030000.zip", day * 40);
    let newest = put("backup-20261010-030000.zip", day * 3);
    put("notes.txt", std::time::Duration::ZERO);
    put("backup-20261013-030000.zip.part", std::time::Duration::ZERO);
    let values = crate::handlers::settings_hub::hub_values(&app.db, dir.path(), &zone, now).await;
    let expected = crate::handlers::settings_hub::backup_label(
        &zone,
        chrono::DateTime::<chrono::Utc>::from(newest),
        now,
    );
    assert!(!expected.starts_with("Today"), "{expected}");
    assert_eq!(values.backups.as_deref(), Some(expected.as_str()));
}

/// The head-partial check walks subfolders (M8), and skips `partials/`.
#[test]
fn template_files_walk_subfolders_but_not_partials() {
    let dir = tempfile::tempdir().unwrap();
    for file in [
        "a.html",
        "device/b.html",
        "device/deeper/c.html",
        "partials/p.html",
        "sw.js",
    ] {
        let path = dir.path().join(file);
        std::fs::create_dir_all(path.parent().unwrap()).unwrap();
        std::fs::write(path, "").unwrap();
    }
    let found: Vec<String> = super::device_api::template_files_in(dir.path())
        .iter()
        .map(|p| {
            p.strip_prefix(dir.path())
                .unwrap()
                .to_string_lossy()
                .replace('\\', "/")
        })
        .collect();
    assert_eq!(found, ["a.html", "device/b.html", "device/deeper/c.html"]);
}

/// The hub never waits for the network (M3): its module names no client or remote, its
/// handler reads only the database, and opening it fetches nothing.
#[tokio::test]
async fn the_settings_hub_makes_no_network_call() {
    let source = std::fs::read_to_string("src/handlers/settings_hub.rs").unwrap();
    for word in [
        "reqwest",
        "system_update",
        "music_fetch",
        "storytel",
        "github",
        "http://",
        "https://",
        "tokio::net",
    ] {
        assert!(!source.contains(word), "settings_hub.rs names {word}");
    }
    let uses: Vec<&str> = source
        .match_indices("state.")
        .map(|(i, _)| &source[i..i + 14])
        .collect();
    assert!(
        uses.iter()
            .all(|u| u.starts_with("state.db") || *u == "state.data_dir"),
        "the hub reads only state.db and the backups in state.data_dir: {uses:?}"
    );

    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let res = app.get_page("/settings", &cookie).await;
    assert_eq!(res.status, StatusCode::OK);
    assert!(app.fetch.requests.lock().unwrap().is_empty());
    assert!(app.fetch.calls.lock().unwrap().is_empty());
}
