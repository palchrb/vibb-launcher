//! Design 31 step 1, the shell: the 5-tab bar set per template, the Settings hub, the "Vibb Phone"
//! name, `autosave.js` instead of inline `onchange`, and the page-markup helpers in `tests/mod.rs`.

use axum::http::{Method, StatusCode};

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
        ("/music/spotify".to_string(), "music"),
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
    assert!(login.contains("<h1 class=\"auth-title\">Vibb Phone</h1>"));
}

/// The tabs' landing pages: since step 15 they open on their large title, without the brand bar.
const LANDING_PAGES: [&str; 5] = [
    "devices_list.html",
    "apps_list.html",
    "device_locate.html",
    "music.html",
    "settings_hub.html",
];

/// Every template with the tab bar sets its tab right before including it, to one of the five
/// (the template decides, not the path - §3.1). There is no brand bar any more (§3.0, steps 10 and
/// 15): a landing page opens on its large title, every other page on its back link and large title.
#[test]
fn every_template_with_the_bar_sets_its_tab() {
    let mut with_bar = 0;
    assert!(!std::path::Path::new("templates/partials/app_header.html").exists());
    for path in super::device_api::template_files() {
        let text = std::fs::read_to_string(&path).unwrap();
        let file = path.file_name().unwrap().to_str().unwrap();
        assert!(!text.contains("app_header"), "{file}: the brand bar");
        let Some(at) = text.find("{% include \"partials/tab_bar.html\" %}") else {
            continue;
        };
        let landing = LANDING_PAGES.contains(&file);
        assert_eq!(
            text.contains("class=\"nav-back\""),
            !landing,
            "{file}: a sub-page has its back link, a landing page none"
        );
        with_bar += 1;
        let before = text[..at].trim_end();
        let line = before.lines().last().unwrap_or("").trim();
        let tab = line
            .strip_prefix("{% let tab = \"")
            .and_then(|rest| rest.strip_suffix("\" %}"))
            .unwrap_or_else(|| panic!("{}: no tab before the header", path.display()));
        // Notifications is reached from the bell on every tab page: no tab is current (design 35
        // §5.1).
        assert!(
            ["devices", "apps", "locate", "music", "settings"].contains(&tab)
                || (matches!(file, "notifications.html" | "notifications_settings.html")
                    && tab.is_empty()),
            "{}: {tab}",
            path.display()
        );
    }
    assert!(with_bar >= 36, "{with_bar} templates with the bar");
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
        "<meta name=\"apple-mobile-web-app-title\" content=\"Vibb Phone\">",
        "<meta name=\"color-scheme\" content=\"light dark\">",
        "<meta name=\"theme-color\" content=\"#F2F2F7\" media=\"(prefers-color-scheme: light)\">",
        "<meta name=\"theme-color\" content=\"#0C0C14\" media=\"(prefers-color-scheme: dark)\">",
        // The manifest URL stays: older Chrome keys a WebAPK to it.
        "<link rel=\"manifest\" href=\"/static/manifest.webmanifest\">",
        // Not deferred: a switch tapped while the page still loads saves too (qa-31-s1-code L1).
        "<script src=\"/static/autosave.js?v=2\"></script>",
        // Edge to edge on an iPhone, so the safe-area insets are real (style.css takes them).
        "content=\"width=device-width, initial-scale=1.0, viewport-fit=cover\"",
        "<link rel=\"apple-touch-icon\" href=\"/static/icons/icon-180.png\">",
    ] {
        assert!(html.contains(needle), "{needle}");
    }
    // The vibb identity (2026-10-22): the icons the manifest and the head name are there at their
    // sizes, and the headings' Nunito comes from this server, never from a font service.
    let mut icons: Vec<(String, u32)> = manifest["icons"]
        .as_array()
        .unwrap()
        .iter()
        .map(|icon| {
            let sizes = icon["sizes"].as_str().unwrap();
            let src = icon["src"].as_str().unwrap().to_string();
            (src, sizes.split('x').next().unwrap().parse().unwrap())
        })
        .collect();
    icons.push(("/static/icons/icon-180.png".to_string(), 180));
    for (src, size) in icons {
        let png = std::fs::read(src.trim_start_matches('/')).unwrap();
        let side = |at: usize| u32::from_be_bytes(png[at..at + 4].try_into().unwrap());
        assert_eq!(
            (&png[1..4], side(16), side(20)),
            (&b"PNG"[..], size, size),
            "{src}"
        );
    }
    let css = std::fs::read_to_string("static/style.css").unwrap();
    assert!(css.contains("src: url(\"fonts/nunito-latin-wght.woff2\") format(\"woff2\");"));
    assert!(
        std::fs::read("static/fonts/nunito-latin-wght.woff2")
            .unwrap()
            .starts_with(b"wOF2")
    );
    assert!(
        std::fs::metadata("static/fonts/OFL.txt").is_ok(),
        "the font's licence"
    );
    for text in [&css, &html] {
        assert!(!text.contains("googleapis") && !text.contains("gstatic"));
    }
    // Round 1's relayout script is gone with the fixed bar (design 43); pinch zoom stays.
    assert!(!html.contains("tab-bar.js"));
    assert!(!html.contains("user-scalable") && !html.contains("maximum-scale"));
    assert!(!html.contains("Kids Device MDM") && !html.contains("Kids MDM"));
    // The brand bar is gone from the tab pages (step 15).
    assert!(!html.contains("class=\"app-header\""));
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
        Some(format!("/devices/{id}/hardening#restrictions").as_str())
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
        .request_form(Method::POST, "/logout", Some(&cookie), &[])
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

/// The tabs' landing pages open on their large title, as the mockups draw them (design 31 step 15):
/// no brand bar and no back link; the title comes first, or after the 44px bar that holds the
/// page's action (Devices and Apps: "+", Music: Edit).
#[tokio::test]
async fn the_tab_pages_open_on_their_large_title() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    app.enrolled_device("Anna").await;
    for (page, title, add) in [
        ("/devices", "Devices", Some("/devices/new")),
        ("/apps", "Apps", Some("/apps/tracked/new")),
        ("/devices/locate", "Locate", None),
        ("/music", "Music", None),
        ("/settings", "Settings", None),
    ] {
        let html = app.get_page(page, &cookie).await.text();
        assert!(
            !html.contains("class=\"app-header\""),
            "{page}: the brand bar"
        );
        assert!(!html.contains("class=\"brand\""), "{page}");
        // vibb's brand row is the home page's alone (the vibb identity, direction D).
        assert_eq!(
            html.contains("class=\"vibb-brand\""),
            page == "/devices",
            "{page}"
        );
        assert!(!html.contains("class=\"nav-back\""), "{page}");
        let main = &html[html.find("<main class=\"container").unwrap()..];
        let main = &main[main.find('>').unwrap() + 1..];
        let title_at = main
            .find(&format!("<h1 class=\"page-title\">{title}</h1>"))
            .unwrap_or_else(|| panic!("{page}: no large title"));
        let before = &main[..title_at];
        assert!(
            before.trim().is_empty() || before.trim_start().starts_with("<div class=\"title-bar\""),
            "{page}: something before the title: {before}"
        );
        if let Some(href) = add {
            let bar = &before[before.find("title-bar").unwrap()..];
            assert!(
                bar.contains(&format!("href=\"{href}\"")) && bar.contains("aria-label=\"Add "),
                "{page}: the bar's +"
            );
        }
    }
}

/// A save never jumps to the top (§5.1.4, the browser pass of 2026-10-14): on every page of the
/// PWA - with a phone that has apps, a contact, a rule, a catalog app and a music entry, so the
/// row forms render too - every form's action names the form's own group, which is also where its
/// redirect lands (each area's redirect table posts its forms); only the forms that leave the page
/// have none (`settings_pages::check_forms`).
#[tokio::test]
async fn every_form_on_every_page_lands_on_its_own_group() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("Anna").await;
    let (not_set_up, _) = app.create_device("Ola").await;
    super::app_display::report_installed(&app, &token, &["org.example.one", "org.example.two"])
        .await;
    let entry = super::music::add_own(&app, &cookie, "Bilturen").await;
    // Spotify (design 26c): an app, a child's login bound to Anna's phone (Device › Music's
    // Spotify group, Web Filter's notes and the override mark on a blocked Spotify host), a
    // Spotify entry, and the Spotify page itself.
    let account = super::music_spotify::listing::ready(&app).await;
    sqlx::query("UPDATE device_policy SET music_spotify_account = ? WHERE device_id = ?")
        .bind(account)
        .bind(id)
        .execute(&app.db)
        .await
        .unwrap();
    sqlx::query(
        "INSERT INTO dns_custom_domains (domain, list_type, device_id) VALUES ('spotify.com', 'block', ?)",
    )
    .bind(id)
    .execute(&app.db)
    .await
    .unwrap();
    let spotify_entry = super::music_spotify::listing::entry(
        &app,
        "playlist",
        super::music_spotify::listing::PLAYLIST,
    )
    .await;
    for (path, fields) in [
        (
            format!("/devices/{id}/contacts"),
            vec![("name", "Mamma"), ("number", "91234567")],
        ),
        (
            "/schedules/rules".to_string(),
            vec![("scope", ""), ("name", "Skole"), ("kind", "school")],
        ),
        (
            format!("/schedules/rules?device={id}"),
            vec![("scope", "1"), ("name", "Egen"), ("kind", "custom")],
        ),
        (
            "/apps/tracked/new".to_string(),
            vec![("name", "Notes"), ("source_type", "manual")],
        ),
    ] {
        let res = app
            .request_form(Method::POST, &path, Some(&cookie), &fields)
            .await;
        assert!(res.status.is_redirection(), "{path}: {}", res.text());
    }
    let mut pages = vec![
        "/devices".to_string(),
        "/devices/new".to_string(),
        "/apps".to_string(),
        "/apps/tracked/new".to_string(),
        "/apps/tracked/1".to_string(),
        "/devices/locate".to_string(),
        format!("/devices/locate?device={id}"),
        "/music".to_string(),
        "/music?edit=1".to_string(),
        format!("/music/entries/{entry}"),
        format!("/music/entries/{spotify_entry}"),
        "/music/spotify".to_string(),
        "/music/settings".to_string(),
        "/music/categories".to_string(),
        "/music/import".to_string(),
        "/settings".to_string(),
        "/schedules".to_string(),
        "/schedules/screen-time".to_string(),
        "/schedules/rules/new".to_string(),
        "/schedules/rules/1".to_string(),
        "/schedules/rules/2".to_string(),
        "/dns".to_string(),
        "/dns/log".to_string(),
        "/wallpapers".to_string(),
        "/settings/provisioning".to_string(),
        "/account".to_string(),
        "/security".to_string(),
        "/backups".to_string(),
        "/notifications".to_string(),
    ];
    for phone in [id, not_set_up] {
        for section in [
            Section::Summary,
            Section::Calls,
            Section::Apps,
            Section::Music,
            Section::Time,
            Section::Lock,
            Section::Home,
            Section::Filter,
            Section::About,
            Section::Advanced,
        ] {
            pages.push(device_page(phone, section));
        }
        pages.push(format!("/devices/{phone}/provision"));
    }
    // The Spotify parts are on the pages the loop checks.
    for (page, part) in [
        (
            device_page(id, Section::Music),
            format!("action=\"/devices/{id}/music/spotify#music-spotify\""),
        ),
        (
            device_page(id, Section::Filter),
            "data-spotify-override".to_string(),
        ),
        (
            "/music/spotify".to_string(),
            "action=\"/music/spotify/add#add\"".to_string(),
        ),
        (
            format!("/music/entries/{spotify_entry}"),
            "data-hint=\"spotify-streams\"".to_string(),
        ),
    ] {
        let html = app.get_page(&page, &cookie).await.text();
        assert!(html.contains(&part), "{page} has no {part}");
    }
    let mut forms = 0;
    for page in pages {
        let res = app.get_page(&page, &cookie).await;
        assert_eq!(res.status, StatusCode::OK, "{page}");
        let html = res.text();
        super::settings_pages::check_forms(&page, &html);
        forms += html.matches("<form").count();
    }
    // A floor, not a count: some forms show only at certain times of day (a rule in force, a
    // pause), so the number moves (149 in CI one morning). Every form found is checked above.
    assert!(forms > 120, "{forms} forms checked");
}

/// Where `style.css` breaks the shape every browser on the floor reads the same way (§5.7: no CSS
/// nesting): a `{` while a declaration block is open, a `}` with nothing open, a block, comment or
/// string still open at the end. A tiny tokenizer (comments, strings, braces), not a parser. One
/// lost `}` once turned the last 160 lines into nested rules that never applied (the browser pass
/// of 2026-10-14, Finding 1).
fn css_shape_error(css: &str) -> Option<String> {
    // The at-rules whose block holds rules; every other block holds declarations.
    const RULE_LISTS: [&str; 6] = [
        "@media",
        "@supports",
        "@keyframes",
        "@-webkit-keyframes",
        "@container",
        "@layer",
    ];
    let line = |at: usize| css[..at].matches('\n').count() + 1;
    // Each open block: whether it holds declarations, and the line it opened on.
    let mut open: Vec<(bool, usize)> = Vec::new();
    let mut prelude = String::new();
    let bytes = css.as_bytes();
    let mut at = 0;
    while at < bytes.len() {
        let c = bytes[at];
        match c {
            b'/' if bytes.get(at + 1) == Some(&b'*') => {
                let Some(end) = css[at + 2..].find("*/") else {
                    return Some(format!("line {}: a comment is never closed", line(at)));
                };
                at += 2 + end + 2;
                continue;
            }
            b'"' | b'\'' => {
                let start = at;
                at += 1;
                loop {
                    match bytes.get(at) {
                        Some(b'\\') => at += 2,
                        Some(&q) if q == c => break,
                        Some(b'\n') | None => {
                            return Some(format!("line {}: a string is never closed", line(start)));
                        }
                        Some(_) => at += 1,
                    }
                }
                prelude.push_str(&css[start..=at]);
            }
            b'{' => {
                if let Some(&(true, opened)) = open.last() {
                    return Some(format!(
                        "line {}: `{}` opens a rule inside the declaration block of line {opened} \
                         (a missing `}}`?)",
                        line(at),
                        prelude.trim()
                    ));
                }
                // The at-rule's name ends at a space or a `(`: `@media(min-width: 600px)` is valid.
                let head = prelude.trim_start();
                let name = &head[..head
                    .find(|c: char| c.is_whitespace() || c == '(')
                    .unwrap_or(head.len())];
                open.push((!RULE_LISTS.contains(&name), line(at)));
                prelude.clear();
            }
            b'}' => {
                if open.pop().is_none() {
                    return Some(format!("line {}: a `}}` with no block open", line(at)));
                }
                prelude.clear();
            }
            b';' => prelude.clear(),
            _ => {
                let len = css[at..].chars().next().map_or(1, char::len_utf8);
                prelude.push_str(&css[at..at + len]);
                at += len;
                continue;
            }
        }
        at += 1;
    }
    open.last()
        .map(|(_, opened)| format!("the block of line {opened} is never closed"))
}

#[test]
fn style_css_has_the_shape_browsers_read() {
    let css = std::fs::read_to_string("static/style.css").unwrap();
    assert_eq!(css_shape_error(&css), None);

    // The guard catches what it is for, and reads comments and strings as what they are.
    let fine = "@media (x) {\n a { content: \"}{\"; /* } { */ }\n}\n@font-face { src: url('a;b') }";
    assert_eq!(css_shape_error(fine), None);
    // Valid CSS it must not flag (qa-browser-fixes-code N1): an at-rule's condition with no space
    // before it, and the other at-rules that hold rules.
    for fine in [
        "@media(min-width: 600px) { a { b: c } }",
        "@supports(display: grid) { a { b: c } }",
        "@container card (min-width: 400px) { a { b: c } }",
        "@layer base { a { b: c } }\n@layer base, extra;",
    ] {
        assert_eq!(css_shape_error(fine), None, "{fine}");
    }
    let lost_brace =
        ".a {\n    padding-left: 64px;\n\n/* step 14 */\n.b,\n.c {\n    margin: 0;\n}\n";
    assert_eq!(
        css_shape_error(lost_brace).as_deref(),
        Some(
            "line 6: `.b,\n.c` opens a rule inside the declaration block of line 1 \
             (a missing `}`?)"
        )
    );
    for (css, error) in [
        ("a { b: c; }\n}", "line 2: a `}` with no block open"),
        (
            "@media (x) {\n a { b: c; }\n",
            "the block of line 1 is never closed",
        ),
        ("a { content: \"x }", "line 1: a string is never closed"),
        ("a { b: c } /* x", "line 1: a comment is never closed"),
    ] {
        assert_eq!(css_shape_error(css).as_deref(), Some(error), "{css}");
    }
}

/// Design 43, the app shell: the document doesn't scroll, `main.container` does, and nothing is
/// positioned against the viewport (an iOS standalone app's stale viewport moved the fixed bar).
/// Double-tap zoom is off where it is tapped - the scroller and the bar, since a scroller resets
/// an inherited `touch-action` -, never on `*` (Leaflet's own rules win on the map).
#[test]
fn the_app_shell_scrolls_main_not_the_document() {
    let mut css = std::fs::read_to_string("static/style.css").unwrap();
    while let Some(start) = css.find("/*") {
        let end = css[start..].find("*/").map_or(css.len(), |e| start + e + 2);
        css.replace_range(start..end, " ");
    }
    // The declarations of the first top-level rule for exactly `selector`.
    let block = |selector: &str| -> String {
        let open = format!("\n{selector} {{");
        let at = css
            .find(&open)
            .unwrap_or_else(|| panic!("no rule {selector}"))
            + open.len();
        let end = css[at..].find('}').unwrap() + at;
        css[at..end]
            .split_whitespace()
            .collect::<Vec<_>>()
            .join(" ")
    };
    let compact: String = css.split_whitespace().collect();
    assert!(!compact.contains("position:fixed"), "a fixed box again");
    let body = block("body");
    for needle in [
        "height: 100vh; height: 100dvh;",
        "overflow: hidden;",
        "flex-direction: column;",
    ] {
        assert!(body.contains(needle), "body: {needle} in {body}");
    }
    assert!(
        !body.contains("min-height"),
        "never taller than the screen: {body}"
    );
    let main = block(".container");
    for needle in [
        "flex: 1 1 auto;",
        "min-height: 0;",
        "overflow-y: auto;",
        "touch-action: manipulation;",
    ] {
        assert!(main.contains(needle), ".container: {needle} in {main}");
    }
    assert!(
        !main.contains("max-width"),
        "the scroller spans the window: {main}"
    );
    // The editor bar sticks inside `main`, and a sticky offset counts from inside the scroller's
    // padding: its `top: -8px` cancels exactly the 8 px above the status bar's inset in main's
    // `padding-top`, so it sits right under the status bar. Change one, change the other.
    assert!(
        main.contains("padding-top: calc(8px + env(safe-area-inset-top, 0px));"),
        "{main}"
    );
    let editor = block(".editor-bar");
    assert!(editor.contains("top: -8px;"), "{editor}");
    let bar = block(".tab-bar");
    for needle in ["flex: none;", "order: 1;", "touch-action: manipulation;"] {
        assert!(bar.contains(needle), ".tab-bar: {needle} in {bar}");
    }
    assert!(!css.contains("\n* {\n    touch-action") && !body.contains("touch-action"));
    // The auth pages keep the document scroll.
    let auth = block(".auth-page");
    assert!(
        auth.contains("height: auto;") && auth.contains("overflow: visible;"),
        "{auth}"
    );
    // Every page has the shell's scroller, or is an auth page (body.auth-page, main.auth-card).
    for path in super::device_api::template_files() {
        let text = std::fs::read_to_string(&path).unwrap();
        if !text.contains("<body") {
            continue;
        }
        assert!(
            text.contains("<main class=\"container")
                || (text.contains("<body class=\"auth-page\">")
                    && text.contains("<main class=\"auth-card\">")),
            "{} is neither in the shell nor an auth page",
            path.display()
        );
    }
}

/// "dvh only after a vh fallback" (style.css's header; iOS 14, the floor, drops a `dvh` declaration
/// whole): every declaration with `dvh` follows the same property with `vh` in the same block.
#[test]
fn every_dvh_follows_a_vh_fallback() {
    fn missing(css: &str) -> Vec<String> {
        let mut text = css.to_string();
        while let Some(start) = text.find("/*") {
            let end = text[start..]
                .find("*/")
                .map_or(text.len(), |e| start + e + 2);
            text.replace_range(start..end, " ");
        }
        let parts: Vec<&str> = text.split([';', '{', '}']).map(str::trim).collect();
        let property = |decl: &str| decl.split(':').next().unwrap_or("").trim().to_string();
        (1..parts.len())
            .filter(|&i| parts[i].contains("dvh"))
            .filter(|&i| {
                let prev = parts[i - 1];
                property(prev) != property(parts[i]) || !prev.contains("vh") || prev.contains("dvh")
            })
            .map(|i| parts[i].to_string())
            .collect()
    }
    let css = std::fs::read_to_string("static/style.css").unwrap();
    assert!(css.contains("100dvh"), "the guard has something to check");
    assert_eq!(missing(&css), Vec::<String>::new());
    assert_eq!(
        missing("a {\n min-height: 100dvh;\n}"),
        ["min-height: 100dvh"]
    );
    assert_eq!(
        missing("a { height: 100vh; min-height: 100dvh }"),
        ["min-height: 100dvh"]
    );
    assert!(missing("a { min-height: 100vh; /* x */ min-height: 100dvh }").is_empty());
}

/// The class names a template's `class="..."` attributes use, with `{{ }}` and `{% %}` taken out:
/// `dot-{{ x }}` gives `dot-`, a dynamic prefix.
fn template_classes(html: &str) -> Vec<String> {
    let mut found = Vec::new();
    let mut rest = html;
    while let Some(at) = rest.find("class=\"") {
        rest = &rest[at + "class=\"".len()..];
        let Some(end) = rest.find('"') else { break };
        let mut value = rest[..end].to_string();
        for (open, close) in [("{{", "}}"), ("{%", "%}")] {
            while let Some(start) = value.find(open) {
                let stop = value[start..]
                    .find(close)
                    .map_or(value.len(), |e| start + e + close.len());
                value.replace_range(start..stop, " ");
            }
        }
        found.extend(value.split_whitespace().map(str::to_string));
        rest = &rest[end..];
    }
    found
}

/// Every `.class` named anywhere in `css` outside comments (a declaration's `url(a.svg)` adds
/// `svg`, which no template uses as a class - harmless).
fn css_classes(css: &str) -> std::collections::HashSet<String> {
    let mut text = String::new();
    let mut rest = css;
    while let Some(start) = rest.find("/*") {
        text.push_str(&rest[..start]);
        rest = rest[start..]
            .find("*/")
            .map_or("", |end| &rest[start + end + 2..]);
    }
    text.push_str(rest);
    text.match_indices('.')
        .filter(|(at, _)| {
            text.as_bytes()
                .get(at + 1)
                .is_some_and(|c| c.is_ascii_alphabetic())
        })
        .map(|(at, _)| {
            text[at + 1..]
                .chars()
                .take_while(|c| c.is_ascii_alphanumeric() || *c == '-' || *c == '_')
                .collect()
        })
        .collect()
}

/// Every class a template uses is defined in `style.css` (qa-26c-merge3-code L2): design 31's
/// clean-up deleted 36 classes, and a template keeping one would silently lose its style. The
/// allowlist holds the names that have no rule on purpose.
#[test]
fn every_class_a_template_uses_is_in_style_css() {
    const NO_RULE: [(&str, &str); 11] = [
        (
            "attention-",
            "a dynamic prefix (attention-{{ level }}): the levels have the rules",
        ),
        (
            "dot-",
            "a dynamic prefix (dot-{{ state }}): dot-online, dot-away ... have the rules",
        ),
        (
            "music-status-",
            "a dynamic prefix (music-status-{{ kind }}): the kinds have the rules",
        ),
        (
            "state-",
            "a dynamic prefix (state-{{ state }}): state-online ... have the rules",
        ),
        (
            "tone-",
            "a dynamic prefix (tone-{{ tone }}): the tones have the rules",
        ),
        (
            "app-display-form",
            "a hook for static/app-display.js; styled as any form",
        ),
        (
            "app-row",
            "names the Apps tab's catalog rows; styled by .row",
        ),
        (
            "day-row",
            "names the rule editor's day rows; styled by .row",
        ),
        (
            "lock-part",
            "a hook for tests::devices_list (the lock part of a phone's line)",
        ),
        (
            "source-choice",
            "names the Add App source radios' wrapper; no style of its own",
        ),
        (
            "summary-more",
            "names the summary's group of Settings rows; styled by .group",
        ),
    ];
    let css = css_classes(&std::fs::read_to_string("static/style.css").unwrap());
    let mut missing = std::collections::BTreeSet::new();
    let mut dirs = vec![std::path::PathBuf::from("templates")];
    while let Some(dir) = dirs.pop() {
        for entry in std::fs::read_dir(&dir).unwrap() {
            let path = entry.unwrap().path();
            if path.is_dir() {
                dirs.push(path);
            } else if path.extension().is_some_and(|e| e == "html") {
                let html = std::fs::read_to_string(&path).unwrap();
                for class in template_classes(&html) {
                    if !css.contains(&class) && !NO_RULE.iter().any(|(name, _)| *name == class) {
                        missing.insert(format!("{class} ({})", path.display()));
                    }
                }
            }
        }
    }
    assert!(
        missing.is_empty(),
        "classes style.css doesn't define: {missing:?}"
    );
    // The allowlist holds only names that still have no rule.
    for (name, _) in NO_RULE {
        assert!(
            !css.contains(name),
            "{name} has a rule now: take it off the list"
        );
    }
    // The guard catches what it is for: .card went in the clean-up.
    assert_eq!(
        template_classes("<p class=\"row {% if x %}card{% endif %} dot-{{ s }}\">"),
        ["row", "card", "dot-"]
    );
    assert!(!css.contains("card"));
}

/// 2026-10-21, a tap after being away never "does nothing": every page carries
/// `static/reconnect.js` (deferred, from the head partial), and its ping `GET /ping` is an empty
/// 204 that is never cached with a session and the login's redirect without one. The backup
/// download is marked `download`, so the script never waits for a page that won't come.
#[tokio::test]
async fn every_page_carries_the_connecting_script_and_its_ping() {
    let head = std::fs::read_to_string("templates/partials/head.html").unwrap();
    assert!(head.contains("<script src=\"/static/reconnect.js?v=2\" defer></script>"));
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, _) = app.enrolled_device("phone").await;
    for page in [
        "/devices".to_string(),
        device_page(id, Section::Summary),
        "/apps".to_string(),
        "/music".to_string(),
        "/settings".to_string(),
    ] {
        let html = app.get_page(&page, &cookie).await.text();
        assert!(html.contains("/static/reconnect.js?v=2"), "{page}");
    }
    let signed_out = app.request(Method::GET, "/login", None, None).await;
    assert!(signed_out.text().contains("/static/reconnect.js?v=2"));

    let ping = app.get_page("/ping", &cookie).await;
    assert_eq!(ping.status, StatusCode::NO_CONTENT);
    assert!(ping.body.is_empty());
    assert_eq!(
        ping.headers
            .get(axum::http::header::CACHE_CONTROL)
            .and_then(|v| v.to_str().ok()),
        Some("no-store")
    );
    let ping = app.request(Method::GET, "/ping", None, None).await;
    assert_redirect(&ping, "/login", None);
    // The phone listener stays the device API and /healthz (qa-srv-sweep-resume-code L6a).
    let request = axum::http::Request::builder()
        .uri("/ping")
        .body(axum::body::Body::empty())
        .unwrap();
    let on_the_phones = app
        .send_via(&app.device_router(), super::LOOPBACK_PEER, request)
        .await;
    assert_eq!(on_the_phones.status, StatusCode::NOT_FOUND);

    let backups = std::fs::read_to_string("templates/backups.html").unwrap();
    assert!(backups.contains("/download\" class=\"button-small\" download>"));
}

/// qa-srv-sweep-resume-code M1: a page that is a POST's own answer carries
/// `<body data-post-answer`, so reconnect.js never reloads it (a reload would resend the form) -
/// the setup code (200), a refused form shown again (400), a sign-in error; a page reached by a
/// GET doesn't, nor a POST's redirect.
#[tokio::test]
async fn a_posts_own_page_is_marked_so_it_is_never_reloaded() {
    let marked = |html: &str| html.contains("<body data-post-answer");
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, _) = app.create_device("Anna").await;

    let code = app
        .request_form(
            Method::POST,
            &format!("/devices/{id}/regenerate-code"),
            Some(&cookie),
            &[],
        )
        .await;
    assert_eq!(code.status, StatusCode::OK);
    assert!(marked(&code.text()), "the setup code");

    let refused = app
        .request_form(
            Method::POST,
            &format!("/devices/{id}/provision"),
            Some(&cookie),
            &[("wifi_ssid", ""), ("wifi_password", "secret-wifi")],
        )
        .await;
    assert!(refused.text().contains("<body"), "{}", refused.status);
    assert!(marked(&refused.text()), "the setup form shown again");

    let wrong = app
        .request_form(
            Method::POST,
            "/login",
            None,
            &[("username", "parent"), ("password", "not the password")],
        )
        .await;
    assert!(
        marked(&wrong.text()),
        "the sign-in page's error: {}",
        wrong.status
    );

    for page in ["/devices".to_string(), format!("/devices/{id}/provision")] {
        let html = app.get_page(&page, &cookie).await.text();
        assert!(html.contains("<body") && !marked(&html), "{page}");
    }
    let redirect = app
        .request_form(
            Method::POST,
            &format!("/devices/{id}/lock-wake"),
            Some(&cookie),
            &[("on", "on")],
        )
        .await;
    assert!(redirect.status.is_redirection() && !marked(&redirect.text()));

    assert_eq!(
        crate::admin_auth::mark_body(b"<html><body class=\"x\">hi</body></html>"),
        b"<html><body data-post-answer class=\"x\">hi</body></html>"
    );
    assert_eq!(crate::admin_auth::mark_body(b"no page"), b"no page");
}
