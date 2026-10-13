//! Design 31 step 14, the rest of Settings: Web Filter (`/dns`), Blocked Activity (`/dns/log`),
//! Wallpapers, Phone Setup, Account & Password, Security Log and Backups (Software Updates is in
//! `tests::docker`, built without the network). The redirect table is built from the rendered
//! forms (§5.1): every form returns to its own group, so a save never lands at the top.

use axum::http::{Method, StatusCode};
use image::ImageFormat;

use super::{TestApp, assert_redirect, tags};

/// Each form's action and hidden fields, in page order.
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

/// Every action's fragment names an element of the page and every action has one (a form far
/// down a long page must not land at the top without JS - §5.1.4), every auto-saving form has a
/// `<noscript>` Save, and nothing asks with `confirm()`. Sign Out Everywhere (to the sign-in page)
/// and Dismiss (the warning is at the top) are the two that leave without one.
#[track_caller]
pub(super) fn check_forms(page: &str, html: &str) {
    assert!(!html.contains("confirm("), "{page} uses confirm()");
    for form in tags(html, "form") {
        let body = &html[form.start..];
        let body = &body[..body.find("</form>").unwrap_or(body.len())];
        let action = form.attr("action").unwrap_or("");
        if ["/account/sign-out-everywhere", "/account/dismiss-warning"].contains(&action) {
            continue;
        }
        let (_, fragment) = action
            .split_once('#')
            .unwrap_or_else(|| panic!("{page}: {action} has no fragment"));
        assert!(
            html.contains(&format!("id=\"{fragment}\"")),
            "{page}: {action} lands on no element"
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
fn part<'a>(html: &'a str, marker: &str, close: &str) -> &'a str {
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
        vec![(
            "/settings/provisioning#setup".to_string(),
            vec![
                ("server_url", "https://pi.example"),
                ("locale", "en_GB"),
                ("time_zone", "Europe/London"),
            ],
            to("/settings/provisioning?saved=1", "saved"),
        )],
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
    assert_eq!(upstream, "quad9");
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
                &[("list_type", list_type), ("domain", "not a domain")],
            )
            .await;
        let path = format!("/dns?notice=not_a_domain_{list_type}");
        assert_redirect(&res, &path, Some(group));
        let page = app.get_page(&path, &cookie).await.text();
        let section = part(&page, &format!("<section id=\"{group}\""), "</section>");
        assert!(
            section.contains(&format!("data-flash=\"{group}\""))
                && section.contains("isn't a domain name"),
            "{section}"
        );
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
        assert_redirect(&res, "/dns?notice=bad_list", Some("lists"));
    }
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
        assert_eq!(html.matches("aria-invalid").count(), 1);
    }
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
    assert_eq!(res.status, StatusCode::OK);
    let html = res.text();
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
    assert!(rows[0].contains("Music entry moved"));
    assert!(!page.contains("UTC"), "local times (Oslo), not UTC");
    let ban = part(&page, "data-ban", "</form>");
    assert!(
        ban.contains("192.0.2.7")
            && ban.contains("Until ")
            && ban.contains("20 failed login attempts"),
        "{ban}"
    );

    let page = app
        .get_page("/security?unbanned=192.0.2.7", &cookie)
        .await
        .text();
    assert!(part(&page, "<section id=\"banned\"", "</section>").contains("192.0.2.7 is unbanned."));

    sqlx::query("ALTER TABLE security_events RENAME TO broken")
        .execute(&app.db)
        .await
        .unwrap();
    let res = app.get_page("/security", &cookie).await;
    assert_eq!(res.status, StatusCode::INTERNAL_SERVER_ERROR);
}

/// Backups: the confirms, every action with its group's fragment, and the refusals that write
/// nothing render in their group - a restore of a backup that isn't there, a schedule time that
/// isn't HH:MM. Nothing here makes a backup or
/// writes a schedule: those touch `data/` in the working directory.
#[tokio::test]
async fn backups_answer_in_their_groups() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let page = app.get_page("/backups", &cookie).await.text();
    check_forms("/backups", &page);
    for confirm in ["Restore…", "Delete…"] {
        // Every backup's two (if this checkout has any); the page offers them only that way.
        assert_eq!(
            page.matches(&format!("<summary class=\"row\">{confirm}</summary>"))
                .count(),
            page.matches("data-backup=").count(),
        );
    }
    assert!(page.contains("Back Up Now"));

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

    let res = app
        .request_form(
            Method::POST,
            "/backups/schedule",
            Some(&cookie),
            &[
                ("frequency", "daily"),
                ("day_of_week", "0"),
                ("day_of_month", "1"),
                ("backup_time", "3am"),
                ("retention_count", "14"),
            ],
        )
        .await;
    assert_eq!(res.status, StatusCode::BAD_REQUEST);
    let html = res.text();
    let group = part(&html, "<section id=\"schedule\"", "</section>");
    assert!(
        group.contains("data-flash=\"schedule\"") && group.contains("nothing was saved"),
        "{group}"
    );
}
