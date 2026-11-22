//! Design 127 step 1: an app's own page on a phone (`handlers::app_page`) - who gets one, its groups, where its saves
//! land, its back link per `from`, an update held for the parent - and the list's split row.

use super::*;
use serde_json::json;

const ELEMENT: &str = "io.element.android.x";
const CLOCK: &str = "com.android.deskclock";
const MUSIC: &str = "me.vibb.music";

/// Anna with Element X (a catalog app, third-party, asking for the camera and full-screen notifications, version
/// 7.24.1), the preinstalled clock and Vibb music, all allowed; returns the phone and Element's catalog id.
async fn phone(app: &TestApp) -> (i64, String, i64) {
    let (id, token) = app.enrolled_device("Anna").await;
    let element: i64 = sqlx::query_scalar(
        "INSERT INTO tracked_apps (name, package_name, github_repo, latest_release_tag) \
         VALUES ('Element X', ?, 'element-hq/element-x-android', 'v7.24.1') RETURNING id",
    )
    .bind(ELEMENT)
    .fetch_one(&app.db)
    .await
    .unwrap();
    sqlx::query("INSERT INTO device_tracked_apps (device_id, tracked_app_id) VALUES (?, ?)")
        .bind(id)
        .bind(element)
        .execute(&app.db)
        .await
        .unwrap();
    app.post_status(
        &token,
        json!({ "installed_apps": [
            { "package_name": ELEMENT, "label": "Element X", "preinstalled": false,
              "installer": "me.vibb.launcher", "version_name": "7.24.1",
              "access": { "camera": "requested", "full_screen": "denied" } },
            { "package_name": CLOCK, "label": "Clock", "preinstalled": true },
            { "package_name": MUSIC, "label": "Vibb music", "preinstalled": false,
              "access": { "camera": "requested" } },
            { "package_name": "org.example.game", "label": "Game", "preinstalled": false },
        ] }),
    )
    .await;
    sqlx::query("UPDATE device_policy SET allowlist_json = ? WHERE device_id = ?")
        .bind(json!([ELEMENT, CLOCK, MUSIC]).to_string())
        .bind(id)
        .execute(&app.db)
        .await
        .unwrap();
    (id, token, element)
}

fn page_of(id: i64, package: &str) -> String {
    format!("/devices/{id}/apps/{package}")
}

/// The text of `<section id="{id}"` up to its end, or "" when there is none.
fn section<'a>(html: &'a str, id: &str) -> &'a str {
    match html.find(&format!("<section id=\"{id}\"")) {
        Some(at) => &html[at..at + html[at..].find("</section>").unwrap()],
        None => "",
    }
}

/// §2.1, §2.3-§2.6, §5: each group only where it belongs; a package with no page is the list, never a 404.
#[tokio::test]
async fn every_allowed_app_has_a_page_with_only_its_groups() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, _, _) = phone(&app).await;

    // Code QA L4: the clock is kept off Home - a quiet part of its row.
    sqlx::query(
        "INSERT INTO device_app_display (device_id, package_name, color_key, on_home) VALUES (?, ?, 'auto', 0)",
    )
    .bind(id)
    .bind(CLOCK)
    .execute(&app.db)
    .await
    .unwrap();
    // The list: Element's name opens its page, its switch stays on the row behind the hairline, with its label.
    let list = app
        .get_page(&format!("/devices/{id}/apps"), &cookie)
        .await
        .text();
    let clock = &list[list.find(&format!("id=\"app-{CLOCK}\"")).unwrap()..];
    assert!(
        clock[..clock.find("</a>").unwrap()]
            .contains("<span class=\"row-sub\" data-off-home>Not on Home</span>"),
        "{clock}"
    );
    let row = &list[list.find(&format!("id=\"app-{ELEMENT}\"")).unwrap()..];
    let row = &row[..row.find("</form>").unwrap()];
    assert!(row.contains("<div class=\"row-split\">"), "{row}");
    assert!(
        row.contains(&format!(
            "<a class=\"row\" href=\"{}\" data-app-page>",
            page_of(id, ELEMENT)
        )),
        "{row}"
    );
    assert!(row.contains("class=\"split-sw\""), "{row}");
    assert!(row.contains("Element X allowed"), "{row}");
    // The game isn't allowed: a plain switch row, no page.
    let game = &list[list.find("id=\"app-org.example.game\"").unwrap()..];
    assert!(!game[..game.find("</form>").unwrap()].contains("row-split"));

    let html = app.get_page(&page_of(id, ELEMENT), &cookie).await;
    assert_eq!(html.status, StatusCode::OK);
    let html = html.text();
    assert_eq!(nav_current(&html), "devices");
    assert!(
        html.contains("<h1 class=\"page-title\">Element X</h1>"),
        "{html}"
    );
    let head = section(&html, "app-head");
    assert!(head.contains("Version 7.24.1"), "{head}");
    assert_eq!(
        form_action_of(head, "selected"),
        Some(format!("/devices/{id}/apps/toggle#app-head").as_str())
    );
    assert!(head.contains("name=\"back\" value=\"app\""), "{head}");
    // Not preinstalled: Tillatt's ⓘ says off removes it.
    assert!(head.contains("uninstalls it where possible"), "{head}");
    // Vises som: one auto-saving form, the three segments, no Save button but without JS.
    let display = section(&html, "display");
    assert!(display.contains("Shown as"), "{display}");
    for mode in ["logo", "auto", "symbol"] {
        assert!(
            display.contains(&format!("name=\"icon_mode\" value=\"{mode}\"")),
            "{mode}"
        );
    }
    assert!(autosaves(display, "label") && autosaves(display, "icon_mode"));
    // Saves by itself: the only visible Save is without JS; the hidden one is Enter's (never Tilbakestill).
    assert!(display.contains("<noscript><button type=\"submit\" name=\"action\" value=\"save\""));
    let first = &display[display.find("<button").unwrap()..];
    assert!(
        first.starts_with(
            "<button type=\"submit\" name=\"action\" value=\"save\" class=\"visually-hidden\""
        ),
        "{first}"
    );
    // One glyph is always checked: the first, nothing being chosen.
    assert_eq!(input_checked(display, "icon", "chat"), Some(true));
    assert_eq!(input_checked(display, "icon_mode", "auto"), Some(true));
    // Tilgang: the camera switch; the special access a quiet value, not orange.
    let access = section(&html, "access");
    assert_eq!(
        form_action_of(access, "camera"),
        Some(format!("/devices/{id}/apps/permissions#access").as_str())
    );
    assert!(access.contains("Full-screen notifications"), "{access}");
    assert!(
        access.contains("<span class=\"row-value\">Not approved</span>"),
        "{access}"
    );
    assert!(!access.contains("tone-orange"), "{access}");
    // The ⓘ: what off does first, then the way to approve the access.
    let info = &access[access.find("info-panel").unwrap()..];
    assert!(
        info.find("Off: the app can&#39;t use it").unwrap() < info.find("Camera off").unwrap(),
        "{info}"
    );
    assert!(access.contains("App access › Element X: Full-screen notifications"));
    let lock = section(&html, "lock-screen");
    assert_eq!(
        form_action_of(lock, "lock_media"),
        Some(format!("/devices/{id}/apps/lock-media#lock-screen").as_str())
    );
    assert!(lock.contains("Media player"), "{lock}");

    // The preinstalled clock: the head and Vises som only; off only hides it, so no removal note.
    let clock = app.get_page(&page_of(id, CLOCK), &cookie).await.text();
    assert!(section(&clock, "access").is_empty() && section(&clock, "lock-screen").is_empty());
    assert!(!section(&clock, "app-head").contains("uninstalls"));
    assert!(!section(&clock, "display").is_empty());
    // Vibb music: no Tilgang, no Låseskjerm (design 127 §5).
    let music = app.get_page(&page_of(id, MUSIC), &cookie).await.text();
    assert!(section(&music, "access").is_empty() && section(&music, "lock-screen").is_empty());

    // No page: not allowed (at its row), unknown (the list), Vibb One, Kalender's row.
    for (package, fragment) in [
        ("org.example.game", Some("app-org.example.game")),
        ("org.example.gone", None),
        ("calendar", None),
    ] {
        let res = app.get_page(&page_of(id, package), &cookie).await;
        assert_eq!(res.status, StatusCode::SEE_OTHER, "{package}");
        assert_redirect(&res, &format!("/devices/{id}/apps"), fragment);
    }
    let res = app
        .get_page(&page_of(id, "me.vibb.launcher"), &cookie)
        .await;
    assert_eq!(res.status, StatusCode::SEE_OTHER, "Vibb One");
    // The old Permissions address goes to the page.
    let res = app
        .get_page(
            &format!("/devices/{id}/apps?perms={ELEMENT}&from=apps"),
            &cookie,
        )
        .await;
    assert_redirect(
        &res,
        &format!("{}?from=apps", page_of(id, ELEMENT)),
        Some("access"),
    );
}

/// Meldinger (design 82): a page with no Tillatt and no Logo; its save lands on it.
#[tokio::test]
async fn meldinger_has_a_page_without_a_switch_or_a_logo() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, _, _) = phone(&app).await;
    let messages = crate::builtin::MESSAGES;
    let none = app.get_page(&page_of(id, messages), &cookie).await;
    assert_eq!(none.status, StatusCode::SEE_OTHER, "not listed: no page");
    sqlx::query("UPDATE device_policy SET sms_mode = 'managed' WHERE device_id = ?")
        .bind(id)
        .execute(&app.db)
        .await
        .unwrap();
    let list = app
        .get_page(&format!("/devices/{id}/apps"), &cookie)
        .await
        .text();
    assert!(list.contains(&format!("href=\"{}\" data-app-page", page_of(id, messages))));
    let html = app.get_page(&page_of(id, messages), &cookie).await.text();
    assert!(!html.contains("name=\"selected\""), "no Tillatt");
    assert!(!html.contains("value=\"logo\""), "no Logo");
    assert!(section(&html, "access").is_empty());
    let res = app
        .request_form(
            Method::POST,
            &format!("/devices/{id}/apps/display"),
            Some(&cookie),
            &[
                ("package_name", messages),
                ("label", "Chat"),
                ("icon_mode", "symbol"),
                ("icon", "mail"),
                ("color", "auto"),
                ("on_home_shown", "1"),
                ("on_home", "on"),
            ],
        )
        .await;
    assert_redirect(&res, &page_of(id, messages), Some("display"));
    // Logo is refused for a part of Vibb One, at the field.
    let res = app
        .request_form(
            Method::POST,
            &format!("/devices/{id}/apps/display"),
            Some(&cookie),
            &[("package_name", messages), ("icon_mode", "logo")],
        )
        .await;
    assert_eq!(res.status, StatusCode::BAD_REQUEST);
    assert!(res.text().contains("aria-invalid=\"true\""));
}

/// §2.3-§2.6: every save on the page lands on its group; Tillatt off lands on the list; Symbol saves a real glyph.
#[tokio::test]
async fn the_pages_saves_land_on_their_groups() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token, _) = phone(&app).await;
    let page = page_of(id, ELEMENT);
    let display = format!("/devices/{id}/apps/display");
    let post = |path: String, fields: Vec<(&'static str, &'static str)>| {
        let app = &app;
        let cookie = cookie.clone();
        async move {
            app.request_form(Method::POST, &path, Some(&cookie), &fields)
                .await
        }
    };
    let stored = || async {
        sqlx::query_as::<_, (Option<String>, Option<String>, String)>(
            "SELECT label, icon_key, color_key FROM device_app_display WHERE package_name = ?",
        )
        .bind(ELEMENT)
        .fetch_optional(&app.db)
        .await
        .unwrap()
    };

    // Symbol with no glyph posted saves the first; the colour applies.
    let res = post(
        display.clone(),
        vec![
            ("package_name", ELEMENT),
            ("icon_mode", "symbol"),
            ("color", "sky"),
        ],
    )
    .await;
    assert_redirect(&res, &page, Some("display"));
    app.assert_lands(&res, &cookie).await;
    assert_eq!(
        stored().await,
        Some((None, Some("chat".into()), "sky".into()))
    );
    // Logo wins over a posted glyph and drops the colour; Automatic is no icon (and with nothing else, no row).
    post(
        display.clone(),
        vec![
            ("package_name", ELEMENT),
            ("icon_mode", "logo"),
            ("icon", "mail"),
            ("color", "sky"),
        ],
    )
    .await;
    assert_eq!(
        stored().await,
        Some((None, Some("own".into()), "auto".into()))
    );
    post(
        display.clone(),
        vec![
            ("package_name", ELEMENT),
            ("icon_mode", "auto"),
            ("icon", "mail"),
            ("color", "sky"),
        ],
    )
    .await;
    assert_eq!(stored().await, None);
    let policy = app.policy(&token).await;
    assert_eq!(policy["launcher_ui"]["app_display"], json!([]));
    // A refused name: the page as a 400, the error at the field.
    let res = post(
        display.clone(),
        vec![
            ("package_name", ELEMENT),
            ("label", "This name is far too long to fit"),
        ],
    )
    .await;
    assert_eq!(res.status, StatusCode::BAD_REQUEST);
    let html = res.text();
    assert!(
        html.contains("aria-invalid=\"true\" aria-describedby=\"display-error\""),
        "{html}"
    );
    assert!(html.contains("id=\"display-error\""));
    // A mode the form never sends is the icon's refusal.
    let res = post(
        display.clone(),
        vec![("package_name", ELEMENT), ("icon_mode", "glyph")],
    )
    .await;
    assert_eq!(res.status, StatusCode::BAD_REQUEST);

    // The camera and the media player land on their groups.
    let res = post(
        format!("/devices/{id}/apps/permissions"),
        vec![("package_name", ELEMENT), ("from", "apps")],
    )
    .await;
    assert_redirect(&res, &format!("{page}?from=apps"), Some("access"));
    let html = app
        .get_page(&format!("{page}?from=apps"), &cookie)
        .await
        .text();
    assert_eq!(input_checked(&html, "camera", "default"), Some(false));
    assert!(section(&html, "access").contains("Waiting for the phone"));
    let res = post(
        format!("/devices/{id}/apps/lock-media"),
        vec![("package_name", ELEMENT), ("lock_media", "on")],
    )
    .await;
    assert_redirect(&res, &page, Some("lock-screen"));
    // Refused (the clock isn't offered the media player): at the lock screen's group, on the page.
    let res = post(
        format!("/devices/{id}/apps/lock-media"),
        vec![("package_name", CLOCK), ("lock_media", "on")],
    )
    .await;
    assert_redirect(
        &res,
        &format!("{}?notice=lock_media", page_of(id, CLOCK)),
        Some("lock-screen"),
    );
    // The clock's page has no such group: the reason at its head.
    let back = app
        .get_page(
            &format!("{}?notice=lock_media", page_of(id, CLOCK)),
            &cookie,
        )
        .await
        .text();
    assert!(
        section(&back, "app-head").contains("data-flash=\"app-head\">Not possible for this app."),
        "{back}"
    );

    // Code QA L6: a camera deny only where the page offers the switch - never the preinstalled clock or Vibb music;
    // an allow always.
    for package in [CLOCK, MUSIC] {
        let res = post(
            format!("/devices/{id}/apps/permissions"),
            vec![("package_name", package), ("camera", "deny")],
        )
        .await;
        assert_redirect(
            &res,
            &format!("{}?notice=perms", page_of(id, package)),
            Some("access"),
        );
        let res = post(
            format!("/devices/{id}/apps/permissions"),
            vec![("package_name", package), ("camera", "default")],
        )
        .await;
        assert_redirect(&res, &page_of(id, package), Some("access"));
    }
    let denied: Vec<String> =
        sqlx::query_scalar("SELECT package_name FROM device_app_permissions ORDER BY package_name")
            .fetch_all(&app.db)
            .await
            .unwrap();
    assert_eq!(denied, [ELEMENT]);

    // Tillatt on lands on the page; off on the list at the row.
    let toggle = format!("/devices/{id}/apps/toggle");
    let res = post(
        toggle.clone(),
        vec![
            ("package_name", ELEMENT),
            ("back", "app"),
            ("selected", "on"),
        ],
    )
    .await;
    assert_redirect(&res, &page, Some("app-head"));
    let res = post(toggle, vec![("package_name", ELEMENT), ("back", "app")]).await;
    assert_redirect(
        &res,
        &format!("/devices/{id}/apps"),
        Some(&format!("app-{ELEMENT}")),
    );
    let res = app.get_page(&page, &cookie).await;
    assert_eq!(
        res.status,
        StatusCode::SEE_OTHER,
        "no page once not allowed"
    );
}

/// §2.1: the tab and the back link per `from` - from this app's catalog page straight back there.
#[tokio::test]
async fn back_goes_where_the_page_was_opened_from() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, _, element) = phone(&app).await;
    let page = page_of(id, ELEMENT);
    let back = |html: &str| {
        let at =
            html.find("class=\"nav-back\" href=\"").unwrap() + "class=\"nav-back\" href=\"".len();
        html[at..at + html[at..].find('"').unwrap()].to_string()
    };
    let html = app.get_page(&page, &cookie).await.text();
    assert_eq!(back(&html), format!("/devices/{id}/apps#app-{ELEMENT}"));
    assert_eq!(nav_current(&html), "devices");
    let html = app
        .get_page(&format!("{page}?from=apps"), &cookie)
        .await
        .text();
    assert_eq!(
        back(&html),
        format!("/devices/{id}/apps?from=apps#app-{ELEMENT}")
    );
    assert_eq!(nav_current(&html), "apps");
    // Every form keeps it.
    assert!(html.matches("name=\"from\" value=\"apps\"").count() >= 3);
    let html = app
        .get_page(&format!("{page}?from=app:{element}"), &cookie)
        .await
        .text();
    assert_eq!(back(&html), format!("/apps/tracked/{element}#phone-{id}"));
    let html = app
        .get_page(&format!("{page}?from=app:999"), &cookie)
        .await
        .text();
    assert_eq!(
        back(&html),
        format!("/devices/{id}/apps?from=app:999#app-{ELEMENT}")
    );
    // The catalog page's phone rows open this page.
    let catalog = app
        .get_page(&format!("/apps/tracked/{element}"), &cookie)
        .await
        .text();
    assert!(
        catalog.contains(&format!("href=\"{page}?from=app:{element}\"")),
        "{catalog}"
    );
}

/// §2.2, §2.3: an update held for the parent - orange on the list and the page, Approve and Keep with
/// `back=device:<id>` landing on the page with the answer; a bad `back` is the catalog's.
#[tokio::test]
async fn a_held_update_waits_on_the_page() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, _, element) = phone(&app).await;
    let hold = || async {
        sqlx::query(
            "INSERT INTO tracked_app_held_releases (tracked_app_id, release_tag, asset_id, file_path, \
             sha256, reason, new_permissions, held_at) VALUES (?, 'v7.25.0', 4, '/nowhere/v7.apk', \
             'cc', 'permissions', '[\"android.permission.READ_CONTACTS\"]', '2026-11-12 10:00:00')",
        )
        .bind(element)
        .execute(&app.db)
        .await
        .unwrap();
    };
    hold().await;
    let list = app
        .get_page(&format!("/devices/{id}/apps"), &cookie)
        .await
        .text();
    let row = &list[list.find(&format!("id=\"app-{ELEMENT}\"")).unwrap()..];
    assert!(
        row[..row.find("</a>").unwrap()].contains(
            "<span class=\"row-sub tone-orange\" data-update-held>7.25.0 waits for you</span>"
        ),
        "{row}"
    );
    let page = page_of(id, ELEMENT);
    let html = app
        .get_page(&format!("{page}?from=apps"), &cookie)
        .await
        .text();
    let head = section(&html, "app-head");
    assert!(
        head.contains("<span class=\"tone-orange\">7.25.0 waits for you</span>"),
        "{head}"
    );
    assert!(head.contains("Keep 7.24.1"), "{head}");
    assert!(
        head.contains("Applies to the app on every phone."),
        "{head}"
    );
    assert!(head.contains(&format!("href=\"/apps/tracked/{element}#update\"")));
    assert!(head.contains(&format!("name=\"back\" value=\"device:{id}\"")));
    // Keep, from the page: back to it with the answer.
    let res = app
        .request_form(
            Method::POST,
            &format!("/apps/tracked/{element}/held/decline"),
            Some(&cookie),
            &[
                ("tag", "v7.25.0"),
                ("asset_id", "4"),
                ("back", &format!("device:{id}")),
                ("from", "apps"),
            ],
        )
        .await;
    assert_redirect(
        &res,
        &format!("{page}?held=declined&from=apps"),
        Some("app-head"),
    );
    let html = app.get_page(res.location().unwrap(), &cookie).await.text();
    assert!(html.contains("data-flash=\"update\""), "{html}");
    assert!(!html.contains("waits for you"));
    // A `back` naming no phone is the catalog's answer.
    hold().await;
    let res = app
        .request_form(
            Method::POST,
            &format!("/apps/tracked/{element}/held/decline"),
            Some(&cookie),
            &[
                ("tag", "v7.25.0"),
                ("asset_id", "4"),
                ("back", "device:9999"),
            ],
        )
        .await;
    assert_redirect(
        &res,
        &format!("/apps/tracked/{element}?held=declined"),
        Some("update"),
    );
    // Code QA L2: from a phone where the app is no longer allowed, the answer still shows - on the catalog's group.
    hold().await;
    sqlx::query("UPDATE device_policy SET allowlist_json = ? WHERE device_id = ?")
        .bind(json!([CLOCK]).to_string())
        .bind(id)
        .execute(&app.db)
        .await
        .unwrap();
    let res = app
        .request_form(
            Method::POST,
            &format!("/apps/tracked/{element}/held/decline"),
            Some(&cookie),
            &[
                ("tag", "v7.25.0"),
                ("asset_id", "4"),
                ("back", &format!("device:{id}")),
            ],
        )
        .await;
    let res = app.get_page(res.location().unwrap(), &cookie).await;
    assert_redirect(
        &res,
        &format!("/apps/tracked/{element}?held=declined"),
        Some("update"),
    );
}
