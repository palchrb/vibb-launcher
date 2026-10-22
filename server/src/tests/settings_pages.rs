//! Design 31 step 14, the rest of Settings: Web Filter (`/dns`), Blocked Activity (`/dns/log`),
//! Wallpapers, Phone Setup, Account & Password, Security Log and Backups (Software Updates is in
//! `tests::docker`, built without the network). The redirect table is built from the rendered
//! forms (§5.1): every form returns to its own group, so a save never lands at the top.

use axum::http::{Method, StatusCode};
use image::ImageFormat;

use super::{TestApp, assert_redirect, tags};

/// Each form's action and hidden fields, in page order.
pub(crate) fn forms(html: &str) -> Vec<(String, Vec<(String, String)>)> {
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

/// Whether the element with `id` holds the position `at` (from its start tag to its matching end
/// tag, nested tags of the same name counted).
pub(crate) fn encloses(html: &str, id: &str, at: usize) -> bool {
    let Some(attr) = html.find(&format!("id=\"{id}\"")) else {
        return false;
    };
    let open = html[..attr].rfind('<').unwrap();
    let name: String = html[open + 1..]
        .chars()
        .take_while(|c| c.is_ascii_alphanumeric())
        .collect();
    let (start_tag, end_tag) = (format!("<{name}"), format!("</{name}>"));
    let mut depth = 0;
    let mut from = open;
    loop {
        let next_open = html[from..].find(&start_tag).map(|i| from + i);
        let next_close = html[from..].find(&end_tag).map(|i| from + i);
        match (next_open, next_close) {
            (Some(o), Some(c)) if o < c => {
                depth += 1;
                from = o + start_tag.len();
            }
            (_, Some(c)) => {
                depth -= 1;
                if depth == 0 {
                    return open <= at && at < c;
                }
                from = c + end_tag.len();
            }
            _ => return false,
        }
    }
}

/// The forms that leave their page, so no fragment of it: signing in and out, Dismiss (the warning
/// is at the top), the add flows' first steps, New Setup Code (the code page), the deletes that
/// go back to a list, and Spotify's login (a 303 off this server, design 26c §2.3). Matched on the
/// path without its fragment, so a leaving form that carries one fails `check_forms`
/// (qa-26c-merge3-code L1).
fn leaves_the_page(action: &str) -> bool {
    let target = action.split('#').next().unwrap_or_default();
    let path: Vec<&str> = target.split('/').skip(1).collect();
    let number = |segment: &str| segment.parse::<i64>().is_ok();
    matches!(
        path.as_slice(),
        ["login" | "logout"]
            | ["auth", _]
            | ["account", "sign-out-everywhere" | "dismiss-warning"]
            | ["devices" | "apps", "new"]
            | ["apps", "tracked", "new"]
            | ["devices", _, "regenerate-code"]
            // An inbox row marks its event read and goes where it happened (design 35 §5.1).
            | ["notifications", _, "open"]
            // Spotify's login answers with a 303 to Spotify's authorize page (design 26c §2.3).
            | ["music", "spotify", "login"]
    ) || matches!(
        path.as_slice(),
        ["devices", n, "delete"]
            | ["apps", "tracked", n, "delete"]
            | ["music", "entries", n, "delete"]
            | ["schedules", "rules", n, "delete"] if number(n)
    )
}

/// Every action's fragment names **the form's own group** - the element holding it (qa-31-s14-code
/// L4) - and every action has one, as its redirect (a form far down a long page must not land at
/// the top without JS, and with JS `scroll-restore.js` keeps the place over it - §5.1.4, the
/// browser pass of 2026-10-14); every auto-saving form has a `<noscript>` Save, and nothing asks
/// with `confirm()`. Only the forms that leave the page ([leaves_the_page]) have none.
#[track_caller]
pub(crate) fn check_forms(page: &str, html: &str) {
    assert!(!html.contains("confirm("), "{page} uses confirm()");
    for form in tags(html, "form") {
        let body = &html[form.start..];
        let body = &body[..body.find("</form>").unwrap_or(body.len())];
        let action = form.attr("action").unwrap_or("");
        if leaves_the_page(action) {
            assert!(!action.contains('#'), "{page}: {action} leaves the page");
            continue;
        }
        // A form answered with its own page, not a redirect (design 59's "Show Child PIN…":
        // the PIN in no address); scroll-restore.js's record keeps the place on the same path.
        if action == page {
            continue;
        }
        // The Vibb import's upload answers with its preview, a group the form page doesn't have.
        if action == "/music/import#import-preview" {
            continue;
        }
        let (_, fragment) = action
            .split_once('#')
            .unwrap_or_else(|| panic!("{page}: {action} has no fragment"));
        assert!(
            encloses(html, fragment, form.start),
            "{page}: {action} lands on #{fragment}, which isn't the form's own group"
        );
        if body.contains("data-autosave") {
            let noscript = body
                .split_once("<noscript>")
                .and_then(|(_, rest)| rest.split_once("</noscript>"))
                .map(|(inside, _)| inside);
            assert!(
                noscript.is_some_and(|inside| inside.contains("type=\"submit\"")),
                "{page}: {action} saves itself but has no <noscript> Save"
            );
        }
    }
}

/// `action`'s form sits inside a `<details class="confirm">`, after its summary.
#[track_caller]
fn behind_a_confirm(html: &str, action: &str) {
    let at = html
        .find(&format!("action=\"{action}\""))
        .unwrap_or_else(|| panic!("no form for {action}"));
    let before = &html[..at];
    let open = before
        .rfind("<details class=\"confirm")
        .unwrap_or_else(|| panic!("{action} has no confirm"));
    assert!(
        !before[open..].contains("</details>"),
        "{action} isn't inside its confirm"
    );
    assert!(before[open..].contains("</summary>"), "{action}");
}

/// The part of `html` from `marker` to the end of the element `close` that follows it.
pub(crate) fn part<'a>(html: &'a str, marker: &str, close: &str) -> &'a str {
    let rest = &html[html.find(marker).unwrap_or_else(|| panic!("no {marker}"))..];
    &rest[..rest.find(close).map_or(rest.len(), |e| e + close.len())]
}

async fn scalar(app: &TestApp, sql: &str) -> i64 {
    sqlx::query_scalar(sql).fetch_one(&app.db).await.unwrap()
}

/// §5.1's table for Web Filter, Wallpapers, Phone Setup and the Security Log, posted from the
/// rendered forms: each lands on its own group (`/dns#lists|blocked|allowed|server`, a photo's row,
/// Phone Setup's flash, Blocked Addresses). A form the table has no row for fails.
#[tokio::test]
async fn every_form_on_the_settings_pages_returns_to_its_group() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    app.enrolled_device("Anna").await;
    for (domain, list_type) in [
        ("old-block.example", "block"),
        ("old-allow.example", "allow"),
    ] {
        sqlx::query("INSERT INTO dns_custom_domains (domain, list_type) VALUES (?, ?)")
            .bind(domain)
            .bind(list_type)
            .execute(&app.db)
            .await
            .unwrap();
    }
    let photo: i64 = sqlx::query_scalar(
        "INSERT INTO wallpapers (kind, image_hash, label, sort, lock_screen) \
         VALUES ('image', ?, 'Hytta', 1000, 0) RETURNING id",
    )
    .bind("a".repeat(64))
    .fetch_one(&app.db)
    .await
    .unwrap();
    sqlx::query(
        "INSERT INTO banned_ips (ip_address, banned_until, reason) \
         VALUES ('2001:db8:1:2::/64', datetime('now', '+1 hours'), 'probe')",
    )
    .execute(&app.db)
    .await
    .unwrap();

    // (action as rendered, the fields a parent sends, the expected Location's path and fragment;
    // None: not posted here - the upload is multipart, tested below).
    type Row = (
        String,
        Vec<(&'static str, &'static str)>,
        Option<(String, String)>,
    );
    let check = |page: &'static str, table: Vec<Row>| {
        let app = &app;
        let cookie = &cookie;
        async move {
            let html = app.get_page(page, cookie).await.text();
            check_forms(page, &html);
            let found = forms(&html);
            assert_eq!(
                found.iter().map(|(a, _)| a.as_str()).collect::<Vec<_>>(),
                table.iter().map(|(a, _, _)| a.as_str()).collect::<Vec<_>>(),
                "every form on {page} is in the table, in page order"
            );
            for ((action, hidden), (_, fields, expected)) in found.iter().zip(&table) {
                let Some((path, fragment)) = expected else {
                    continue;
                };
                let mut sent: Vec<(&str, &str)> = hidden
                    .iter()
                    .map(|(k, v)| (k.as_str(), v.as_str()))
                    .collect();
                sent.extend_from_slice(fields);
                let res = app
                    .request_form(Method::POST, action, Some(cookie), &sent)
                    .await;
                assert_redirect(&res, path, Some(fragment.as_str()));
                app.assert_lands(&res, cookie).await;
            }
        }
    };
    let to = |path: &str, fragment: &str| Some((path.to_string(), fragment.to_string()));

    let (block, allow): (i64, i64) = sqlx::query_as(
        "SELECT (SELECT id FROM dns_custom_domains WHERE domain = 'old-block.example'), \
                (SELECT id FROM dns_custom_domains WHERE domain = 'old-allow.example')",
    )
    .fetch_one(&app.db)
    .await
    .unwrap();
    let lists: Vec<i64> = sqlx::query_scalar("SELECT id FROM dns_blocklists ORDER BY name")
        .fetch_all(&app.db)
        .await
        .unwrap();
    let mut table: Vec<Row> = lists
        .iter()
        .map(|id| {
            (
                format!("/dns/blocklists/{id}/toggle#lists"),
                vec![],
                to("/dns", "lists"),
            )
        })
        .collect();
    table.extend([
        (
            "/dns/blocklists/new#lists".to_string(),
            vec![("name", "Mine"), ("url", "https://lists.example/hosts.txt")],
            to("/dns", "lists"),
        ),
        (
            format!("/dns/domains/{block}/delete#blocked"),
            vec![],
            to("/dns", "blocked"),
        ),
        (
            "/dns/domains/new#blocked".to_string(),
            vec![("domain", "new-block.example")],
            to("/dns", "blocked"),
        ),
        (
            format!("/dns/domains/{allow}/delete#allowed"),
            vec![],
            to("/dns", "allowed"),
        ),
        (
            "/dns/domains/new#allowed".to_string(),
            vec![("domain", "New-Allow.Example.")],
            to("/dns", "allowed"),
        ),
        (
            "/dns/upstream#server".to_string(),
            vec![("upstream", "quad9")],
            to("/dns", "server"),
        ),
        // Design 36: typing a custom server means using it (Quad9 above gives way).
        (
            "/dns/upstream/custom#server".to_string(),
            vec![
                ("custom_server", "https://dns.example/dns-query"),
                ("custom_backup", "1.1.1.3"),
            ],
            to("/dns?open=custom-server", "server"),
        ),
    ]);
    check("/dns", table).await;

    check(
        "/wallpapers",
        vec![
            ("/wallpapers#add".to_string(), vec![], None),
            (
                format!("/wallpapers/{photo}/lock-screen#wallpaper-{photo}"),
                vec![("lock_screen", "on")],
                to("/wallpapers", &format!("wallpaper-{photo}")),
            ),
            (
                format!("/wallpapers/{photo}/delete#all"),
                vec![],
                to("/wallpapers", "all"),
            ),
        ],
    )
    .await;

    check(
        "/settings/provisioning",
        vec![
            (
                "/settings/provisioning#setup".to_string(),
                vec![
                    ("server_url", "https://pi.example"),
                    ("locale", "en_GB"),
                    ("time_zone", "Europe/London"),
                ],
                to("/settings/provisioning?saved=1", "saved"),
            ),
            // Design 31 step 8: the country code is Phone Setup's second form.
            (
                "/settings/calls#country-code".to_string(),
                vec![("default_country_code", "46")],
                to("/settings/provisioning?saved=country-code", "country-code"),
            ),
        ],
    )
    .await;

    check(
        "/security",
        vec![(
            "/security/unban#banned".to_string(),
            vec![],
            to("/security?unbanned=2001%3Adb8%3A1%3A2%3A%3A%2F64", "banned"),
        )],
    )
    .await;

    // Each did what it says.
    let custom: Vec<(String, String)> = sqlx::query_as(
        "SELECT domain, list_type FROM dns_custom_domains WHERE device_id IS NULL ORDER BY domain",
    )
    .fetch_all(&app.db)
    .await
    .unwrap();
    assert_eq!(
        custom,
        [
            ("new-allow.example".to_string(), "allow".to_string()),
            ("new-block.example".to_string(), "block".to_string()),
        ]
    );
    let off = scalar(
        &app,
        "SELECT COUNT(*) FROM dns_blocklists WHERE enabled = 0",
    )
    .await;
    assert_eq!(off as usize, lists.len(), "every list was posted unticked");
    let mine = scalar(
        &app,
        "SELECT COUNT(*) FROM dns_blocklists WHERE name = 'Mine' AND enabled = 1",
    )
    .await;
    assert_eq!(mine, 1);
    let upstream: String = sqlx::query_scalar("SELECT upstream FROM dns_filter_settings")
        .fetch_one(&app.db)
        .await
        .unwrap();
    assert_eq!(upstream, "custom");
    assert_eq!(
        scalar(&app, "SELECT COUNT(*) FROM wallpapers WHERE kind = 'image'").await,
        0
    );
    assert_eq!(scalar(&app, "SELECT COUNT(*) FROM banned_ips").await, 0);
    let (url, locale): (String, String) =
        sqlx::query_as("SELECT server_url, locale FROM provisioning_settings")
            .fetch_one(&app.db)
            .await
            .unwrap();
    assert_eq!(
        (url.as_str(), locale.as_str()),
        ("https://pi.example", "en_GB")
    );
    let code: String = sqlx::query_scalar("SELECT default_country_code FROM call_settings")
        .fetch_one(&app.db)
        .await
        .unwrap();
    assert_eq!(code, "46");
}

/// Web Filter's refusals land in their group with a short message (the phone's page does the
/// same, qa-31-s5-code L1): a name that isn't a domain, a list without a web address; a second tap
/// on Remove returns to its group; bad values are 400/404; an unreadable page a 500.
#[tokio::test]
async fn the_web_filter_refuses_in_its_groups() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    for (list_type, group) in [("block", "blocked"), ("allow", "allowed")] {
        let res = app
            .request_form(
                Method::POST,
                "/dns/domains/new",
                Some(&cookie),
                &[("list_type", list_type), ("domain", "not <a> domain")],
            )
            .await;
        // What was typed comes back in its field (qa-31-s14-code N3), escaped.
        let path = format!("/dns?notice=not_a_domain_{list_type}&typed=not+%3Ca%3E+domain");
        assert_redirect(&res, &path, Some(group));
        let page = app.get_page(&path, &cookie).await.text();
        let section = part(&page, &format!("<section id=\"{group}\""), "</section>");
        assert!(
            section.contains(&format!("data-flash=\"{group}\""))
                && section.contains("isn't a domain name")
                && section.contains("value=\"not &#60;a&#62; domain\""),
            "{section}"
        );
        assert!(!page.contains("<a> domain"));
        assert_eq!(page.matches("isn't a domain name").count(), 1);
    }
    assert_eq!(
        scalar(&app, "SELECT COUNT(*) FROM dns_custom_domains").await,
        0
    );

    let lists = scalar(&app, "SELECT COUNT(*) FROM dns_blocklists").await;
    for fields in [
        vec![("name", "Mine"), ("url", "ftp://lists.example/hosts")],
        vec![("name", " "), ("url", "https://lists.example/hosts")],
        vec![("name", "Mine")],
    ] {
        let res = app
            .request_form(Method::POST, "/dns/blocklists/new", Some(&cookie), &fields)
            .await;
        let location = res.location().unwrap_or_default();
        assert!(
            location.starts_with("/dns?notice=bad_list&name="),
            "{location}"
        );
        assert!(location.ends_with("#lists"), "{location}");
    }
    let page = app
        .get_page(
            "/dns?notice=bad_list&name=Mine&url=ftp%3A%2F%2Flists.example%2Fhosts",
            &cookie,
        )
        .await
        .text();
    assert!(page.contains("value=\"Mine\""));
    assert!(page.contains("value=\"ftp://lists.example/hosts\""));
    assert_eq!(
        scalar(&app, "SELECT COUNT(*) FROM dns_blocklists").await,
        lists
    );
    let page = app.get_page("/dns?notice=bad_list", &cookie).await.text();
    assert!(
        page.contains("<details class=\"disclosure cell\" id=\"add-list\" open>"),
        "{page}"
    );
    assert!(page.contains("id=\"list-error\""));
    let page = app.get_page("/dns", &cookie).await.text();
    assert!(page.contains("<details class=\"disclosure cell\" id=\"add-list\">"));
    assert!(!page.contains("data-flash"));

    // A second tap on Remove: nothing to delete, back to the group the form names.
    for (list_type, group) in [("block", "blocked"), ("allow", "allowed")] {
        let res = app
            .request_form(
                Method::POST,
                "/dns/domains/9999/delete",
                Some(&cookie),
                &[("list_type", list_type)],
            )
            .await;
        assert_redirect(&res, "/dns", Some(group));
    }
    let res = app
        .request_form(
            Method::POST,
            "/dns/upstream",
            Some(&cookie),
            &[("upstream", "google")],
        )
        .await;
    assert_eq!(res.status, StatusCode::BAD_REQUEST);
    let res = app
        .request_form(
            Method::POST,
            "/dns/blocklists/9999/toggle",
            Some(&cookie),
            &[("enabled", "on")],
        )
        .await;
    assert_eq!(res.status, StatusCode::NOT_FOUND);

    sqlx::query("ALTER TABLE dns_custom_domains RENAME TO broken")
        .execute(&app.db)
        .await
        .unwrap();
    let res = app.get_page("/dns", &cookie).await;
    assert_eq!(res.status, StatusCode::INTERNAL_SERVER_ERROR);
    let res = app
        .request_form(
            Method::POST,
            "/dns/domains/new",
            Some(&cookie),
            &[("list_type", "block"), ("domain", "x.example")],
        )
        .await;
    assert_eq!(res.status, StatusCode::INTERNAL_SERVER_ERROR);
}

/// Web Filter's look: a list's size or problem under its name, the download error only behind
/// its ⓘ, the DNS server last, names escaped.
#[tokio::test]
async fn the_web_filter_page_reads_as_rows() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    app.enrolled_device("<b>Anna</b>").await;
    let page = app.get_page("/dns", &cookie).await.text();
    assert_eq!(super::nav_current(&page), "settings");
    assert!(
        page.contains("href=\"/settings\""),
        "a back link to Settings"
    );
    assert!(
        !page.contains("class=\"app-header\""),
        "no brand bar on a sub-page"
    );
    assert!(page.contains("&#60;b&#62;Anna&#60;/b&#62;") && !page.contains("<b>Anna"));
    // Nothing loaded in a test: every seeded list says so in a few words.
    assert!(page.contains("<span class=\"row-sub text-alarm\">Not loaded yet</span>"));
    let lists = page.find("id=\"lists\"").unwrap();
    let server = page.find("id=\"server\"").unwrap();
    assert!(page.find("id=\"blocked\"").unwrap() > lists);
    assert!(
        page.find("id=\"phones\"").unwrap() < server,
        "the DNS server at the bottom"
    );
    for word in ["Upstream", "resolver", "on-device", "launcher"] {
        assert!(!page.contains(word), "{word}");
    }
}

/// Blocked Activity: a list of phones, its log off or today's count, each to its log.
#[tokio::test]
async fn blocked_activity_lists_the_phones() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (anna, _) = app.enrolled_device("Anna").await;
    let (emil, _) = app.enrolled_device("Emil").await;
    let (ella, _) = app.enrolled_device("Ella").await;
    sqlx::query("UPDATE device_policy SET dns_log_enabled = 1 WHERE device_id IN (?, ?)")
        .bind(anna)
        .bind(emil)
        .execute(&app.db)
        .await
        .unwrap();
    for (received, n) in [("datetime('now')", 3), ("datetime('now', '-3 days')", 2)] {
        for i in 0..n {
            sqlx::query(&format!(
                "INSERT INTO device_dns_events (device_id, domain, category, blocked_at, received_at) \
                 VALUES (?, ?, 'ads', '2026-10-13T08:00:00Z', {received})"
            ))
            .bind(anna)
            .bind(format!("d{i}.example"))
            .execute(&app.db)
            .await
            .unwrap();
        }
    }
    let page = app.get_page("/dns/log", &cookie).await.text();
    let row = |id: i64| part(&page, &format!("data-phone=\"{id}\""), "</a>").to_string();
    assert!(row(anna).contains(">3 today<"), "{}", row(anna));
    assert!(row(emil).contains(">None today<"));
    assert!(row(ella).contains(">Off<"));
    assert!(page.contains(&format!("href=\"/devices/{anna}/filter#log\"")));
    assert!(page.find(">Anna<").unwrap() < page.find(">Ella<").unwrap());
}

/// Wallpapers: a photo's Delete is a confirm, its switch saves itself, a refused upload says why
/// under the photo field (400, nothing stored), an upload lands on its row, a built-in can't go.
#[tokio::test]
async fn wallpapers_confirm_deletes_and_land_on_the_photo() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let res = super::wallpapers::upload(
        &app,
        &cookie,
        "Hytta <3",
        true,
        &super::wallpapers::encode(&super::wallpapers::top_black(90, 200), ImageFormat::Png),
    )
    .await;
    let photo: i64 = scalar(&app, "SELECT MAX(id) FROM wallpapers").await;
    assert_redirect(&res, "/wallpapers", Some(&format!("wallpaper-{photo}")));
    let page = app.get_page("/wallpapers", &cookie).await.text();
    check_forms("/wallpapers", &page);
    assert!(page.contains(&format!("id=\"wallpaper-{photo}\"")));
    behind_a_confirm(&page, &format!("/wallpapers/{photo}/delete#all"));
    assert!(page.contains("Delete \"Hytta &#60;3\" from this server and every phone?"));
    assert!(super::autosaves(&page, "lock_screen"));
    let row = part(&page, &format!("id=\"wallpaper-{photo}\""), "</form>");
    assert_eq!(
        super::input_checked(row, "lock_screen", "on"),
        Some(true),
        "{row}"
    );
    assert_eq!(
        super::input_checked(&page, "lock_screen", "on"),
        Some(false),
        "the upload's is off"
    );
    // Built-ins have no switch and no Delete; a hand-made post is refused.
    assert_eq!(page.matches("Delete Photo…").count(), 1);
    let res = app
        .request_form(Method::POST, "/wallpapers/1/delete", Some(&cookie), &[])
        .await;
    assert_eq!(res.status, StatusCode::BAD_REQUEST);

    let res = super::wallpapers::upload(&app, &cookie, "x", false, b"not an image").await;
    assert_eq!(res.status, StatusCode::BAD_REQUEST);
    let html = res.text();
    let field = part(&html, "id=\"wallpaper_image\"", "</p>");
    assert!(
        field.contains("aria-invalid=\"true\"") && field.contains("class=\"field-error\""),
        "{field}"
    );
    assert_eq!(
        scalar(&app, "SELECT COUNT(*) FROM wallpapers WHERE kind = 'image'").await,
        1
    );

    // Deleted twice (a second tab): the second lands on the list too.
    for _ in 0..2 {
        let res = app
            .request_form(
                Method::POST,
                &format!("/wallpapers/{photo}/delete"),
                Some(&cookie),
                &[],
            )
            .await;
        assert_redirect(&res, "/wallpapers", Some("all"));
    }
}

/// Phone Setup: a refusal renders the form again (400) with what was typed - never the key - and
/// the reason under its field; a save comes back to its flash; an unreadable row is a 500.
#[tokio::test]
async fn phone_setup_refuses_under_the_field_and_flashes_a_save() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let key = ["tskey-auth-", "TESTONLY", "-not-a-real-key-4x7q"].concat();
    for (field, locale, zone) in [
        ("locale", "nb-NO", "Europe/Oslo"),
        ("time_zone", "nb_NO", "Europe Oslo"),
    ] {
        let res = app
            .request_form(
                Method::POST,
                "/settings/provisioning",
                Some(&cookie),
                &[
                    ("server_url", "https://typed.example"),
                    ("tailscale_auth_key", &key),
                    ("locale", locale),
                    ("time_zone", zone),
                ],
            )
            .await;
        assert_eq!(res.status, StatusCode::BAD_REQUEST);
        let html = res.text();
        assert!(
            html.contains("value=\"https://typed.example\""),
            "the typed values are kept"
        );
        assert!(!html.contains("TESTONLY"), "the key is never sent back");
        let input = part(&html, &format!("id=\"{field}\""), "</p>");
        assert!(
            input.contains("aria-invalid=\"true\"") && input.contains("Nothing was saved."),
            "{input}"
        );
        // The pasted key isn't kept, and the page says so under its field (qa-31-s14-code L3).
        let key_field = part(&html, "id=\"tailscale_auth_key\"", "</p>");
        assert!(
            key_field.contains("The new key wasn't saved. Paste it again."),
            "{key_field}"
        );
        assert_eq!(html.matches("aria-invalid").count(), 2);
    }
    // Without a pasted key nothing is said about it; a ticked "Remove the stored key" stays ticked.
    sqlx::query("UPDATE provisioning_settings SET tailscale_auth_key = ?")
        .bind(&key)
        .execute(&app.db)
        .await
        .unwrap();
    let res = app
        .request_form(
            Method::POST,
            "/settings/provisioning",
            Some(&cookie),
            &[
                ("server_url", ""),
                ("tailscale_auth_key", ""),
                ("clear_tailscale_auth_key", "on"),
                ("locale", "nb_NO"),
                ("time_zone", "Europe Oslo"),
            ],
        )
        .await;
    assert_eq!(res.status, StatusCode::BAD_REQUEST);
    let html = res.text();
    assert!(!html.contains("Paste it again"));
    assert_eq!(
        super::input_checked(&html, "clear_tailscale_auth_key", "on"),
        Some(true)
    );
    sqlx::query("UPDATE provisioning_settings SET tailscale_auth_key = ''")
        .execute(&app.db)
        .await
        .unwrap();
    let stored: (String, String) =
        sqlx::query_as("SELECT server_url, tailscale_auth_key FROM provisioning_settings")
            .fetch_one(&app.db)
            .await
            .unwrap();
    assert_eq!(
        stored,
        (String::new(), String::new()),
        "nothing was written"
    );

    let page = app.get_page("/settings/provisioning", &cookie).await.text();
    assert!(!page.contains("id=\"saved\""));
    let page = app
        .get_page("/settings/provisioning?saved=1", &cookie)
        .await
        .text();
    assert!(part(&page, "id=\"saved\"", "</div>").contains("Saved."));

    sqlx::query("ALTER TABLE provisioning_settings RENAME TO broken")
        .execute(&app.db)
        .await
        .unwrap();
    let res = app.get_page("/settings/provisioning", &cookie).await;
    assert_eq!(res.status, StatusCode::INTERNAL_SERVER_ERROR);
}

/// Account & Password: each refusal under its field (400) and nothing changed; a change's flash
/// in the Password group; the 2FA reset's refusal opens its confirm; Sign Out Everywhere… is a
/// confirm; the live warning's lock in local time.
#[tokio::test]
async fn account_answers_in_its_groups() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let page = app.get_page("/account", &cookie).await.text();
    check_forms("/account", &page);
    behind_a_confirm(&page, "/account/sign-out-everywhere");
    behind_a_confirm(&page, "/account/reset-2fa#two-factor");
    assert!(page.contains("<details class=\"confirm cell\" id=\"reset-2fa\">"));
    let hash = || async {
        sqlx::query_scalar::<_, String>("SELECT password_hash FROM admin_users")
            .fetch_one(&app.db)
            .await
            .unwrap()
    };
    let before = hash().await;
    for (current, new, again, field) in [
        (
            "correct horse battery staple",
            "short",
            "short",
            "new_password",
        ),
        (
            "correct horse battery staple",
            "a long new password",
            "another long one",
            "confirm_password",
        ),
        (
            "wrong",
            "a long new password",
            "a long new password",
            "current_password",
        ),
    ] {
        let res = app
            .request_form(
                Method::POST,
                "/account/password",
                Some(&cookie),
                &[
                    ("current_password", current),
                    ("new_password", new),
                    ("confirm_password", again),
                ],
            )
            .await;
        assert_eq!(res.status, StatusCode::BAD_REQUEST, "{field}");
        let html = res.text();
        let input = part(&html, &format!("id=\"{field}\""), "</p>");
        assert!(
            input.contains("aria-invalid=\"true\"") && input.contains("Nothing was changed."),
            "{input}"
        );
        assert!(
            !html.contains("data-flash"),
            "the reason is under the field, not a flash"
        );
    }
    assert_eq!(hash().await, before);

    let res = app
        .request_form(
            Method::POST,
            "/account/reset-2fa",
            Some(&cookie),
            &[("current_password", "wrong")],
        )
        .await;
    assert_eq!(res.status, StatusCode::BAD_REQUEST);
    let html = res.text();
    assert!(html.contains("<details class=\"confirm cell\" id=\"reset-2fa\" open>"));
    assert!(
        part(&html, "id=\"reset_current_password\"", "</p>")
            .contains("Two-factor login was not reset.")
    );

    // Wrong codes and a lock: the end as a local clock time, no "UTC".
    sqlx::query(
        "UPDATE admin_users SET failed_login_attempts = 5, locked_until = datetime('now', '+15 minutes')",
    )
    .execute(&app.db)
    .await
    .unwrap();
    let page = app.get_page("/account", &cookie).await.text();
    let warning = part(&page, "id=\"live-warning\"", "</div>");
    assert!(
        warning.contains("new sign-ins are locked until "),
        "{warning}"
    );
    assert!(
        !warning.contains("UTC") && !warning.contains("2026-"),
        "{warning}"
    );

    let res = app
        .request_form(
            Method::POST,
            "/account/password",
            Some(&cookie),
            &[
                ("current_password", "correct horse battery staple"),
                ("new_password", "a brand new long password"),
                ("confirm_password", "a brand new long password"),
            ],
        )
        .await;
    // Post/redirect/get (qa-31-s14-code M1): a reload of the result asks nothing again.
    assert_redirect(&res, "/account?changed=password", Some("password"));
    let html = app
        .get_page("/account?changed=password", &cookie)
        .await
        .text();
    let group = part(&html, "<section id=\"password\"", "</section>");
    assert!(
        group.contains("data-flash=\"password\"") && group.contains("Password changed."),
        "{group}"
    );
    assert!(
        !html.contains("id=\"live-warning\""),
        "a new password lifts the lock"
    );
    assert_ne!(hash().await, before);
    let html = app.get_page("/account?changed=other", &cookie).await.text();
    assert!(!html.contains("data-flash"));
}

/// Sign Out Everywhere that can't move the epoch (here a trigger makes the UPDATE abort) signs out
/// nobody and says so: 500, and this session still works (qa-31-s14-code L1). Then, working, it
/// ends this session too.
#[tokio::test]
async fn sign_out_everywhere_fails_visibly() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let other = app.admin_cookie().await;
    sqlx::query(
        "CREATE TRIGGER no_epoch BEFORE UPDATE OF session_epoch ON admin_users \
         BEGIN SELECT RAISE(ABORT, 'test: no epoch'); END",
    )
    .execute(&app.db)
    .await
    .unwrap();
    let res = app
        .request_form(
            Method::POST,
            "/account/sign-out-everywhere",
            Some(&cookie),
            &[],
        )
        .await;
    assert_eq!(res.status, StatusCode::INTERNAL_SERVER_ERROR);
    assert!(res.text().contains("nothing was changed"));
    assert_eq!(
        app.get_page("/devices", &cookie).await.status,
        StatusCode::OK
    );
    assert_eq!(
        app.get_page("/devices", &other).await.status,
        StatusCode::OK
    );
    sqlx::query("DROP TRIGGER no_epoch")
        .execute(&app.db)
        .await
        .unwrap();
    let res = app
        .request_form(
            Method::POST,
            "/account/sign-out-everywhere",
            Some(&cookie),
            &[],
        )
        .await;
    assert_eq!(res.location(), Some("/login"));
    for session in [&cookie, &other] {
        assert_eq!(
            app.get_page("/devices", session).await.location(),
            Some("/login")
        );
    }
}

/// Security Log: labels, never the raw id outside the ⓘ; local times, no "UTC"; the detail
/// escaped behind the ⓘ; a blocked address with its end and Unban; an unreadable log a 500.
#[tokio::test]
async fn the_security_log_reads_as_labelled_rows() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    sqlx::query("DELETE FROM security_events")
        .execute(&app.db)
        .await
        .unwrap();
    crate::security::record_security_event(
        &app.db,
        "device_command_queued",
        Some("parent"),
        Some("192.0.2.9"),
        Some("<img src=x onerror=alert(1)>"),
    )
    .await;
    crate::security::record_security_event(&app.db, "music_entry_moved", None, None, None).await;
    sqlx::query(
        "INSERT INTO banned_ips (ip_address, banned_until, reason) \
         VALUES ('192.0.2.7', datetime('now', '+1 hours'), '20 failed login attempts')",
    )
    .execute(&app.db)
    .await
    .unwrap();
    let page = app.get_page("/security", &cookie).await.text();
    check_forms("/security", &page);
    let rows: Vec<&str> = page.split("data-event>").skip(1).collect();
    assert_eq!(rows.len(), 2);
    let (summary, panel) = rows[1].split_once("</summary>").unwrap();
    assert!(summary.contains("Command sent to phone"), "{summary}");
    assert!(summary.contains("parent · 192.0.2.9"));
    assert!(
        !summary.contains("device_command_queued"),
        "the id only behind ⓘ"
    );
    assert!(panel.contains("device_command_queued"));
    assert!(panel.contains("&#60;img src=x") && !page.contains("<img src=x"));
    assert!(rows[0].contains("Music moved"));
    assert!(!page.contains("UTC"), "local times (Oslo), not UTC");
    let ban = part(&page, "data-ban", "</form>");
    assert!(
        ban.contains("192.0.2.7")
            && ban.contains("Until ")
            && ban.contains("20 failed login attempts"),
        "{ban}"
    );

    // "Unbanned" only for an address that isn't blocked (qa-31-s14-code N1).
    let page = app
        .get_page("/security?unbanned=192.0.2.7", &cookie)
        .await
        .text();
    assert!(!page.contains("is unbanned."), "it is still blocked");
    let res = app
        .request_form(
            Method::POST,
            "/security/unban",
            Some(&cookie),
            &[("ip", "192.0.2.7")],
        )
        .await;
    assert_redirect(&res, "/security?unbanned=192.0.2.7", Some("banned"));
    let page = app
        .get_page("/security?unbanned=192.0.2.7", &cookie)
        .await
        .text();
    assert!(part(&page, "<section id=\"banned\"", "</section>").contains("192.0.2.7 is unbanned."));
    // An address that wasn't blocked: back to the group, no message.
    let res = app
        .request_form(
            Method::POST,
            "/security/unban",
            Some(&cookie),
            &[("ip", "192.0.2.8")],
        )
        .await;
    assert_redirect(&res, "/security", Some("banned"));

    sqlx::query("ALTER TABLE security_events RENAME TO broken")
        .execute(&app.db)
        .await
        .unwrap();
    let res = app.get_page("/security", &cookie).await;
    assert_eq!(res.status, StatusCode::INTERNAL_SERVER_ERROR);
}

/// Backups, in the test's own data dir (`AppState::data_dir`, qa-31-s14-code L1): Back Up Now, the
/// restore's typed name (wrong: 400, its confirm open, nothing asked; right: the request file), the
/// schedule (a refusal in its group, a save), Delete twice - every success a redirect to its group.
#[tokio::test]
async fn backups_answer_in_their_groups() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let data = app.state.data_dir.as_path().to_path_buf();
    let backups = data.join("backups");
    let names = || {
        let mut names: Vec<String> = std::fs::read_dir(&backups)
            .map(|d| {
                d.filter_map(|e| e.ok())
                    .map(|e| e.file_name().to_string_lossy().into_owned())
                    .collect()
            })
            .unwrap_or_default();
        names.sort();
        names
    };

    // Back Up Now: a backup in the temp data dir, then post/redirect/get (qa-31-s14-code M1).
    let res = app
        .request_form(Method::POST, "/backups/create", Some(&cookie), &[])
        .await;
    assert_redirect(&res, "/backups?done=backup", Some("backups"));
    let made = names();
    assert_eq!(made.len(), 1, "{made:?}");
    let file = made[0].clone();
    let page = app.get_page("/backups?done=backup", &cookie).await.text();
    check_forms("/backups", &page);
    let group = part(&page, "<section id=\"backups\"", "</section>");
    assert!(group.contains("data-flash=\"backups\"") && group.contains("Backup made."));
    behind_a_confirm(&page, &format!("/backups/{file}/restore#backups"));
    behind_a_confirm(&page, &format!("/backups/{file}/delete#backups"));
    let page = app.get_page("/backups?done=nonsense", &cookie).await.text();
    assert!(!page.contains("data-flash"));

    // Restore: the typed name must be the file's; a wrong one asks nothing of the helper.
    let flag = data.join("update_requested");
    let restore = format!("/backups/{file}/restore");
    let res = app
        .request_form(
            Method::POST,
            &restore,
            Some(&cookie),
            &[("confirm_filename", "backup-x.zip")],
        )
        .await;
    assert_eq!(res.status, StatusCode::BAD_REQUEST);
    let html = res.text();
    let item = part(
        &html,
        &format!("data-backup=\"{file}\""),
        "</details>\n            </details>",
    );
    assert!(
        item.contains(" open>"),
        "the backup and its confirm open: {item}"
    );
    assert!(
        item.contains(
            "id=\"restore-error\">That isn&#39;t the file&#39;s name - nothing was restored."
        ),
        "{item}"
    );
    assert!(!flag.exists(), "nothing asked of the root helper");
    let res = app
        .request_form(
            Method::POST,
            &restore,
            Some(&cookie),
            &[("confirm_filename", file.as_str())],
        )
        .await;
    assert_redirect(&res, "/backups?done=restore", Some("backups"));
    assert_eq!(
        std::fs::read_to_string(&flag).unwrap(),
        format!("restore_backup {file}")
    );
    // A backup that isn't there (any more): nothing restored, said in the group.
    let res = app
        .request_form(
            Method::POST,
            "/backups/backup-19700101-000000.zip/restore",
            Some(&cookie),
            &[("confirm_filename", "backup-19700101-000000.zip")],
        )
        .await;
    assert_eq!(res.status, StatusCode::BAD_REQUEST);
    let html = res.text();
    let group = part(&html, "<section id=\"backups\"", "</section>");
    assert!(
        group.contains("data-flash=\"backups\"") && group.contains("nothing was restored"),
        "{group}"
    );

    // The schedule: a refusal in its group (400, nothing written), a save redirects.
    let schedule = data.join("backup_schedule.conf");
    let fields = |time: &'static str| {
        vec![
            ("frequency", "weekly"),
            ("day_of_week", "3"),
            ("day_of_month", "1"),
            ("backup_time", time),
            ("retention_count", "14"),
        ]
    };
    let res = app
        .request_form(
            Method::POST,
            "/backups/schedule",
            Some(&cookie),
            &fields("3am"),
        )
        .await;
    assert_eq!(res.status, StatusCode::BAD_REQUEST);
    let html = res.text();
    let group = part(&html, "<section id=\"schedule\"", "</section>");
    assert!(
        group.contains("data-flash=\"schedule\"") && group.contains("nothing was saved"),
        "{group}"
    );
    assert!(!schedule.exists());
    let res = app
        .request_form(
            Method::POST,
            "/backups/schedule",
            Some(&cookie),
            &fields("03:30"),
        )
        .await;
    assert_redirect(&res, "/backups?done=schedule", Some("schedule"));
    let saved = std::fs::read_to_string(&schedule).unwrap();
    assert!(
        saved.contains("BACKUP_TIME=03:30") && saved.contains("DAY_OF_WEEK=3"),
        "{saved}"
    );
    let page = app.get_page("/backups?done=schedule", &cookie).await.text();
    assert!(part(&page, "<section id=\"schedule\"", "</section>").contains("Schedule saved."));
    assert!(page.contains("<option value=\"3\" selected>Wednesday</option>"));

    // Delete, twice (a second tab): each a redirect, the second says it was gone.
    let delete = format!("/backups/{file}/delete");
    let res = app
        .request_form(Method::POST, &delete, Some(&cookie), &[])
        .await;
    assert_redirect(&res, "/backups?done=deleted", Some("backups"));
    assert!(names().is_empty());
    let res = app
        .request_form(Method::POST, &delete, Some(&cookie), &[])
        .await;
    assert_redirect(&res, "/backups?done=gone", Some("backups"));
}

/// Software Updates: every request that worked redirects to its group with its line (a reload asks
/// the helper nothing again, qa-31-s14-code M1), the request file says what was asked, and the
/// page renders without the network (the GitHub check fails at once in tests).
#[tokio::test]
async fn updates_redirect_after_each_request() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let flag = app.state.data_dir.join("update_requested");
    std::fs::create_dir_all(app.state.data_dir.as_path()).unwrap();
    for (route, action, done, group) in [
        ("/update/restart", "restart", "restart", "app"),
        ("/system/os/check", "os_check", "os_check", "os"),
        ("/system/os/upgrade", "os_upgrade", "os_upgrade", "os"),
        (
            "/system/tailscale/update",
            "tailscale_update",
            "tailscale",
            "tailscale",
        ),
        ("/system/reboot", "reboot", "reboot", "os"),
    ] {
        let res = app
            .request_form(Method::POST, route, Some(&cookie), &[])
            .await;
        assert_redirect(&res, &format!("/updates?done={done}"), Some(group));
        assert_eq!(std::fs::read_to_string(&flag).unwrap(), action, "{route}");
    }
    for (route, file, done, group) in [
        (
            "/update/schedule",
            "app_update_schedule.conf",
            "app_schedule",
            "app-schedule",
        ),
        (
            "/system/schedule",
            "schedule.conf",
            "os_schedule",
            "os-schedule",
        ),
    ] {
        let res = app
            .request_form(
                Method::POST,
                route,
                Some(&cookie),
                &[
                    ("frequency", "daily"),
                    ("day_of_week", "0"),
                    ("day_of_month", "1"),
                    ("check_time", "04:15"),
                ],
            )
            .await;
        assert_redirect(&res, &format!("/updates?done={done}"), Some(group));
        let saved = std::fs::read_to_string(app.state.data_dir.join(file)).unwrap();
        assert!(saved.contains("CHECK_TIME=04:15"), "{saved}");
    }
    let page = app.get_page("/updates?done=reboot", &cookie).await.text();
    check_forms("/updates", &page);
    let group = part(&page, "<section id=\"os\"", "</section>");
    assert!(
        group.contains("data-flash=\"os\"") && group.contains("The Pi is restarting."),
        "{group}"
    );
    assert!(!page.contains("open this page again"));
}
