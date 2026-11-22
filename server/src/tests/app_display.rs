//! Design 14: how an app shows on the kid's launcher - the catalog default, a phone's own choice
//! (also for apps outside the catalog), the resolved `launcher_ui.app_display`, the forms.

use super::*;

/// The policy's `launcher_ui.app_display` for a phone.
async fn app_display(app: &TestApp, token: &str) -> serde_json::Value {
    app.request(Method::GET, "/api/devices/policy", Some(token), None)
        .await
        .json()["launcher_ui"]["app_display"]
        .clone()
}

/// A status report saying these packages are installed (so the device page lists them).
/// The phone reports `packages` installed, and the parent allows them all (a new phone starts
/// with only the safe defaults, security review M10).
pub(super) async fn report_installed(app: &TestApp, token: &str, packages: &[&str]) {
    let installed: Vec<serde_json::Value> = packages
        .iter()
        .map(|p| serde_json::json!({ "package_name": p, "label": format!("Label of {p}") }))
        .collect();
    let res = app
        .request(
            Method::POST,
            "/api/devices/status",
            Some(token),
            Some(serde_json::json!({
                "lock_reason": "NONE", "kiosk_engaged": true, "installed_apps": installed,
            })),
        )
        .await;
    assert!(res.status.is_success());
    sqlx::query("UPDATE device_policy SET allowlist_json = ?")
        .bind(serde_json::to_string(packages).unwrap())
        .execute(&app.db)
        .await
        .unwrap();
}

/// A catalog row for `package`; returns its id.
async fn catalog_app(app: &TestApp, name: &str, package: &str) -> i64 {
    sqlx::query_scalar(
        "INSERT INTO tracked_apps (name, package_name, github_repo) VALUES (?, ?, 'o/r') RETURNING id",
    )
    .bind(name)
    .bind(package)
    .fetch_one(&app.db)
    .await
    .unwrap()
}

async fn save(app: &TestApp, cookie: &str, device: i64, fields: &[(&str, &str)]) -> TestResponse {
    app.request_form(
        Method::POST,
        &format!("/devices/{device}/apps/display"),
        Some(cookie),
        fields,
    )
    .await
}

const ELEMENT: &str = "io.element.android.x";

/// An app's own page on a phone (design 127), where its Vises som is.
async fn app_page(app: &TestApp, cookie: &str, device: i64, package: &str) -> String {
    app.get_page(&format!("/devices/{device}/apps/{package}"), cookie)
        .await
        .text()
}

/// The page's Vises som form, up to its end.
fn display_form(page: &str) -> &str {
    let form = &page[page
        .find(r#"class="group app-display-form""#)
        .expect("Vises som")..];
    &form[..form.find("</form>").unwrap()]
}

#[tokio::test]
async fn a_catalog_default_reaches_every_phone_and_a_phones_choice_replaces_it() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (a, token_a) = app.enrolled_device("a").await;
    let (_, token_b) = app.enrolled_device("b").await;
    let element = catalog_app(&app, "Element X (admin name)", ELEMENT).await;
    assert_eq!(app_display(&app, &token_a).await, serde_json::json!([]));

    let mut nudges = app.state.command_notify.subscribe();
    let saved = app
        .request_form(
            Method::POST,
            &format!("/apps/tracked/{element}/display"),
            Some(&cookie),
            &[
                ("label", " Chat "),
                ("icon", "chat"),
                ("color", "peach"),
                ("action", "save"),
            ],
        )
        .await;
    assert_eq!(
        saved.location(),
        Some(format!("/apps/tracked/{element}#display").as_str())
    );
    assert!(nudges.try_recv().is_ok());
    let chat = serde_json::json!([
        {"package_name": ELEMENT, "label": "Chat", "icon": "chat", "color": "peach"}
    ]);
    // The catalog's own admin name is never used; every phone gets the default.
    assert_eq!(app_display(&app, &token_a).await, chat);
    assert_eq!(app_display(&app, &token_b).await, chat);

    // Phone a: its own name only - the row replaces the default as a whole.
    let res = save(
        &app,
        &cookie,
        a,
        &[
            ("package_name", ELEMENT),
            ("label", "Melding"),
            ("icon", ""),
            ("color", "auto"),
        ],
    )
    .await;
    assert_eq!(
        res.location(),
        Some(format!("/devices/{a}/apps/{ELEMENT}#display").as_str())
    );
    assert_eq!(
        app_display(&app, &token_a).await,
        serde_json::json!([
            {"package_name": ELEMENT, "label": "Melding", "icon": null, "color": "auto"}
        ])
    );
    assert_eq!(app_display(&app, &token_b).await, chat);

    // "Vibb style" and no name on phone a: nothing for it there, even with the catalog default
    // (an empty row).
    save(
        &app,
        &cookie,
        a,
        &[
            ("package_name", ELEMENT),
            ("label", ""),
            ("icon", ""),
            ("color", "auto"),
            ("action", "save"),
        ],
    )
    .await;
    assert_eq!(app_display(&app, &token_a).await, serde_json::json!([]));
    // "Reset" (design 34 P1): the phone's row goes, the catalog default is back.
    let res = save(
        &app,
        &cookie,
        a,
        &[("package_name", ELEMENT), ("action", "reset")],
    )
    .await;
    assert_eq!(
        res.location(),
        Some(format!("/devices/{a}/apps/{ELEMENT}#display").as_str())
    );
    assert_eq!(app_display(&app, &token_a).await, chat);
    // Another action (an older page's "own" or "catalog") is refused and writes nothing.
    for old in ["own", "catalog"] {
        let res = save(
            &app,
            &cookie,
            a,
            &[("package_name", ELEMENT), ("action", old)],
        )
        .await;
        assert_eq!(res.status, StatusCode::BAD_REQUEST, "{old}");
        assert_eq!(app_display(&app, &token_a).await, chat, "{old}");
    }
    // Saving exactly the default keeps no row (the phone follows later catalog changes).
    save(
        &app,
        &cookie,
        a,
        &[
            ("package_name", ELEMENT),
            ("label", "Chat"),
            ("icon", "chat"),
            ("color", "peach"),
        ],
    )
    .await;
    let rows: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM device_app_display")
        .fetch_one(&app.db)
        .await
        .unwrap();
    assert_eq!(rows, 0);

    // A default on a catalog row without a package yet applies to nobody.
    let unknown = catalog_app(&app, "Notes", "").await;
    app.request_form(
        Method::POST,
        &format!("/apps/tracked/{unknown}/display"),
        Some(&cookie),
        &[
            ("label", "Notater"),
            ("icon", "menu_book"),
            ("color", "auto"),
        ],
    )
    .await;
    assert_eq!(app_display(&app, &token_a).await, chat);

    // The catalog page shows the default (peach's tile is the rose tone); "Reset" there clears it.
    let page = app
        .get_page(&format!("/apps/tracked/{element}"), &cookie)
        .await
        .text();
    assert!(
        page.contains(r#"name="label" maxlength="20" value="Chat""#),
        "{page}"
    );
    assert!(page.contains(r#"value="symbol" checked"#));
    assert!(page.contains(r#"value="chat" checked"#));
    assert_eq!(input_checked(&page, "color", "peach"), Some(true));
    assert!(page.contains(r#"<button type="submit" name="action" value="reset""#));
    assert!(!page.contains("Use the app's own") && !page.contains("Follow the catalog"));
    app.request_form(
        Method::POST,
        &format!("/apps/tracked/{element}/display"),
        Some(&cookie),
        &[("action", "reset"), ("label", "ignored")],
    )
    .await;
    assert_eq!(app_display(&app, &token_b).await, serde_json::json!([]));
}

/// Design 34 P1 (QA H1): "The app's own logo" is the third state - kept without a catalog default
/// and with a name, sent as `own` with `auto`, a catalog default may be it, and the forms show it.
#[tokio::test]
async fn the_apps_own_logo_is_stored_sent_and_shown() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (a, token_a) = app.enrolled_device("a").await;
    let (_, token_b) = app.enrolled_device("b").await;
    report_installed(&app, &token_a, &[ELEMENT, "com.spotify.music"]).await;

    // No catalog default: the phone's row is kept, the colour sent never matters.
    save(
        &app,
        &cookie,
        a,
        &[
            ("package_name", "com.spotify.music"),
            ("icon", "own"),
            ("color", "sky"),
            ("action", "save"),
        ],
    )
    .await;
    // With a name too.
    save(
        &app,
        &cookie,
        a,
        &[
            ("package_name", ELEMENT),
            ("label", "Chat"),
            ("icon", "own"),
            ("action", "save"),
        ],
    )
    .await;
    assert_eq!(
        app_display(&app, &token_a).await,
        serde_json::json!([
            {"package_name": "com.spotify.music", "label": null, "icon": "own", "color": "auto"},
            {"package_name": ELEMENT, "label": "Chat", "icon": "own", "color": "auto"},
        ])
    );
    let stored: String = sqlx::query_scalar(
        "SELECT color_key FROM device_app_display WHERE package_name = 'com.spotify.music'",
    )
    .fetch_one(&app.db)
    .await
    .unwrap();
    assert_eq!(stored, "auto");

    // The app's page: Logo checked, the preview's word, Reset; the colours are there for Symbol.
    let page = app_page(&app, &cookie, a, "com.spotify.music").await;
    let form = display_form(&page);
    assert!(form.contains(r#"value="logo" checked"#), "{form}");
    assert!(form.contains("Logo"));
    assert!(form.contains(r#"<button type="submit" name="action" value="reset""#));
    assert!(form.contains("data-colours"));
    // Element ("Chat") has the logo too.
    let element = app_page(&app, &cookie, a, ELEMENT).await;
    assert!(display_form(&element).contains(r#"value="logo" checked"#));

    // A catalog default may be the logo: every phone with the app gets it.
    let row = catalog_app(&app, "Element X", ELEMENT).await;
    app.request_form(
        Method::POST,
        &format!("/apps/tracked/{row}/display"),
        Some(&cookie),
        &[("icon", "own"), ("color", "peach"), ("action", "save")],
    )
    .await;
    assert_eq!(
        app_display(&app, &token_b).await,
        serde_json::json!([
            {"package_name": ELEMENT, "label": null, "icon": "own", "color": "auto"},
        ])
    );
    let page = app
        .get_page(&format!("/apps/tracked/{row}"), &cookie)
        .await
        .text();
    assert!(page.contains(r#"value="logo" checked"#), "{page}");
}

/// Play apps and preinstalled apps the catalog doesn't know can be named per phone; Play core
/// and malformed package names can't.
#[tokio::test]
async fn any_allowed_app_can_be_named_on_its_phone() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("phone").await;
    report_installed(&app, &token, &["com.spotify.music", "org.example.clock"]).await;

    let page = app
        .get_page(&device_page(id, Section::Apps), &cookie)
        .await
        .text();
    // Both allowed: both rows open the app's page, whose Vises som posts back to it.
    for package in ["com.spotify.music", "org.example.clock"] {
        let row = &page[page.find(&format!(r#"id="app-{package}""#)).expect(package)..];
        let row = &row[..row.find("</a>").unwrap()];
        assert!(
            row.contains(&format!(r#"href="/devices/{id}/apps/{package}""#)),
            "{row}"
        );
        let own = app_page(&app, &cookie, id, package).await;
        assert!(
            own.contains(&format!(r#"action="/devices/{id}/apps/display#display""#)),
            "{own}"
        );
        assert!(own.contains("/static/app-display.js"));
    }

    save(
        &app,
        &cookie,
        id,
        &[
            ("package_name", "com.spotify.music"),
            ("label", "Musikk"),
            ("icon", "music_note"),
            ("color", "sky"),
        ],
    )
    .await;
    assert_eq!(
        app_display(&app, &token).await,
        serde_json::json!([
            {"package_name": "com.spotify.music", "label": "Musikk", "icon": "music_note", "color": "sky"}
        ])
    );
    let own = app_page(&app, &cookie, id, "com.spotify.music").await;
    assert_eq!(input_checked(&own, "icon_mode", "symbol"), Some(true));
    assert_eq!(input_checked(&own, "icon", "music_note"), Some(true));
    assert_eq!(input_checked(&own, "color", "sky"), Some(true));
    let page = app
        .get_page(&device_page(id, Section::Apps), &cookie)
        .await
        .text();
    // The row's tile shows the icon the phone shows.
    let row = &page[page.find(r#"id="app-com.spotify.music""#).unwrap()..];
    assert!(row[..row.find("</a>").unwrap()].contains(r#"src="/static/app-icons/music_note.svg""#));

    for bad in ["com.android.vending", "chat", "a..b", ""] {
        let res = save(&app, &cookie, id, &[("package_name", bad), ("label", "X")]).await;
        assert_eq!(res.status, StatusCode::BAD_REQUEST, "{bad}");
    }
    let unknown = save(
        &app,
        &cookie,
        9999,
        &[("package_name", ELEMENT), ("label", "X")],
    )
    .await;
    assert_eq!(unknown.status, StatusCode::NOT_FOUND);
}

/// Device › Apps names an app as the kid sees it (2026-11-03): this phone's name, else the catalog's, else the phone's
/// label - the label in the sub-line when it differs, as the Katalog does -, sorted by that name. The name field's
/// placeholder stays the app's own; "On Home"'s explanation is behind its ⓘ.
#[tokio::test]
async fn an_apps_row_shows_the_name_the_kid_sees() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("phone").await;
    report_installed(
        &app,
        &token,
        &["com.spotify.music", ELEMENT, "org.example.clock"],
    )
    .await;
    let element = catalog_app(&app, "Element X", ELEMENT).await;
    sqlx::query("UPDATE tracked_apps SET display_label = 'Chat' WHERE id = ?")
        .bind(element)
        .execute(&app.db)
        .await
        .unwrap();
    save(
        &app,
        &cookie,
        id,
        &[("package_name", "com.spotify.music"), ("label", "Musikk")],
    )
    .await;
    let page = app
        .get_page(&device_page(id, Section::Apps), &cookie)
        .await
        .text();
    let row = |package: &str| {
        let row = &page[page.find(&format!(r#"id="app-{package}""#)).expect(package)..];
        row[..row.find("</a>").unwrap()].to_string()
    };
    assert!(
        row("com.spotify.music").contains(
            r#"row-label">Musikk<span class="row-sub" data-installing="false" data-failed="false">Label of com.spotify.music<"#
        ),
        "{}",
        row("com.spotify.music")
    );
    assert!(
        row(ELEMENT).contains(&format!(
            r#"row-label">Chat<span class="row-sub" data-installing="false" data-failed="false">Label of {ELEMENT}<"#
        )),
        "{}",
        row(ELEMENT)
    );
    assert!(
        // Design 113: an installed app with its own name says nothing under it.
        row("org.example.clock").contains(r#"row-label">Label of org.example.clock</span>"#),
        "{}",
        row("org.example.clock")
    );
    let at = |package: &str| page.find(&format!(r#"id="app-{package}""#)).unwrap();
    assert!(
        at(ELEMENT) < at("org.example.clock") && at("org.example.clock") < at("com.spotify.music")
    );
    // The app's page: the field's placeholder is the app's own name; On Home is a plain row, what Off means
    // behind Vises som's ⓘ.
    let own = app_page(&app, &cookie, id, "com.spotify.music").await;
    assert!(own.contains(r#"placeholder="Label of com.spotify.music""#));
    assert!(
        display_form(&own).contains(r#"row-label">On Home</span>"#),
        "{own}"
    );
    assert!(own.contains("<p>Off: the app stays allowed"), "{own}");
}

/// Design 69 §4.4: "On Home" off sends `on_home: false` (a sync app stays allowed but off Home); on again, or Reset,
/// and the entry is gone. An older page without the switch never turns it off.
#[tokio::test]
async fn on_home_off_keeps_an_app_off_home() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("phone").await;
    report_installed(&app, &token, &["at.bitfire.icsdroid"]).await;
    let page = app_page(&app, &cookie, id, "at.bitfire.icsdroid").await;
    assert!(page.contains(r#"name="on_home_shown""#), "{page}");
    let off = save(
        &app,
        &cookie,
        id,
        &[
            ("package_name", "at.bitfire.icsdroid"),
            ("on_home_shown", "1"),
        ],
    )
    .await;
    assert_redirect(
        &off,
        &format!("/devices/{id}/apps/at.bitfire.icsdroid"),
        Some("display"),
    );
    assert_eq!(
        app_display(&app, &token).await,
        serde_json::json!([{"package_name": "at.bitfire.icsdroid", "label": null, "icon": null, "color": "auto",
                            "on_home": false}])
    );
    let page = app_page(&app, &cookie, id, "at.bitfire.icsdroid").await;
    assert_eq!(input_checked(&page, "on_home", "on"), Some(false), "{page}");
    // An older page (no switch) keeps the app's state as on: the row goes.
    save(
        &app,
        &cookie,
        id,
        &[("package_name", "at.bitfire.icsdroid")],
    )
    .await;
    assert_eq!(app_display(&app, &token).await, serde_json::json!([]));
    save(
        &app,
        &cookie,
        id,
        &[
            ("package_name", "at.bitfire.icsdroid"),
            ("on_home_shown", "1"),
        ],
    )
    .await;
    save(
        &app,
        &cookie,
        id,
        &[("package_name", "at.bitfire.icsdroid"), ("action", "reset")],
    )
    .await;
    assert_eq!(app_display(&app, &token).await, serde_json::json!([]));
}

/// Design 82 D9, D10, W1, W2: Meldinger, a part of Vibb One, has a row on Device › Apps - under Vibb One's, without
/// a switch - while SMS is managed, while the phone reports threads to read, or while something is chosen for it.
/// Its Name and icon has no "App logo" and previews Automatic as the chat glyph on teal; the save lands on its row
/// and reaches the phone as `sms_contract.json`'s `app.display`; `own` and every other reserved name are refused.
#[tokio::test]
async fn meldinger_has_a_row_and_a_name_and_icon_of_its_own() {
    use crate::builtin::MESSAGES;
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("phone").await;
    let contract: serde_json::Value =
        serde_json::from_str(include_str!("../../testdata/sms_contract.json")).unwrap();
    // Its row on the list (a link to its page, design 127), and its page.
    let cell = || async {
        let page = app
            .get_page(&device_page(id, Section::Apps), &cookie)
            .await
            .text();
        let at = page.find(&format!(r#"id="app-{MESSAGES}""#))?;
        let cell = &page[at..];
        Some(cell[..cell.find("</a>").expect("the row's end")].to_string())
    };
    let own = || app_page(&app, &cookie, id, MESSAGES);
    // SMS not managed, nothing chosen, nothing reported: no row.
    assert_eq!(cell().await, None);

    // The phone has threads to read: the row, saying where SMS stands.
    let res = app
        .request(
            Method::POST,
            "/api/devices/status",
            Some(&token),
            Some(serde_json::json!({
                "lock_reason": "NONE", "kiosk_engaged": true, "sms_state": { "has_history": true },
            })),
        )
        .await;
    assert!(res.status.is_success());
    let html = cell().await.expect("the row");
    assert!(
        html.contains(r#"row-label">Messages<span class="row-sub">SMS not managed by Vibb<"#),
        "{html}"
    );
    assert!(!html.contains("data-display-note"), "{html}");
    assert!(
        html.contains(&format!(r#"href="/devices/{id}/apps/{MESSAGES}""#)),
        "{html}"
    );
    assert!(
        html.contains(r##"style="background: #347470" aria-hidden="true"><img src="/static/app-icons/sms.svg""##),
        "{html}"
    );
    assert!(!html.contains("apps/toggle"), "no switch: {html}");
    let html = own().await;
    assert!(
        html.contains(r##"data-auto-glyph="sms" data-auto-tile="#347470""##),
        "{html}"
    );
    // No switch, no Logo, no permissions; its own words for "On Home".
    for absent in [
        "apps/toggle",
        r#"value="logo""#,
        "apps/permissions",
        "the app stays allowed",
    ] {
        assert!(!html.contains(absent), "{absent}: {html}");
    }
    assert!(
        html.contains("<p>Off: Messages still receives and notifies"),
        "{html}"
    );

    // SMS managed: the plain row.
    sqlx::query("UPDATE device_policy SET sms_mode = 'managed' WHERE device_id = ?")
        .bind(id)
        .execute(&app.db)
        .await
        .unwrap();
    let res = app
        .request(
            Method::POST,
            "/api/devices/status",
            Some(&token),
            Some(serde_json::json!({
                "lock_reason": "NONE", "kiosk_engaged": true,
            })),
        )
        .await;
    assert!(res.status.is_success());
    let html = cell().await.expect("the row");
    assert!(html.contains(r#"row-label">Messages</span>"#), "{html}");

    // The contract's entry: a name, a glyph on a colour, off Home.
    let display = &contract["app"]["display"];
    let field = |key: &str| display[key].as_str().unwrap();
    let saved = save(
        &app,
        &cookie,
        id,
        &[
            ("package_name", MESSAGES),
            ("label", field("label")),
            ("icon", field("icon")),
            ("color", field("color")),
            ("on_home_shown", "1"),
        ],
    )
    .await;
    assert_redirect(
        &saved,
        &format!("/devices/{id}/apps/{MESSAGES}"),
        Some("display"),
    );
    assert_eq!(
        app_display(&app, &token).await,
        serde_json::json!([display])
    );
    let html = cell().await.expect("the row");
    assert!(
        html.contains(r#"row-label">Tekst<span class="row-sub">Messages<"#),
        "{html}"
    );
    assert_eq!(input_checked(&own().await, "on_home", "on"), Some(false));
    // W1: the lock screen's row has the name too.
    let lock = app
        .get_page(&device_page(id, Section::Lock), &cookie)
        .await
        .text();
    let row = &lock[lock
        .find(&format!(r#"id="lock-screen-app-{MESSAGES}""#))
        .unwrap()..];
    assert!(row[..row.find("</form>").unwrap()].contains("Tekst"));

    // A colour only with a glyph, as for every app; a chosen glyph without a colour keeps the teal.
    save(
        &app,
        &cookie,
        id,
        &[
            ("package_name", MESSAGES),
            ("label", "Tekst"),
            ("color", "plum"),
        ],
    )
    .await;
    assert_eq!(
        app_display(&app, &token).await,
        serde_json::json!([{"package_name": MESSAGES, "label": "Tekst", "icon": null, "color": "auto"}])
    );
    save(
        &app,
        &cookie,
        id,
        &[("package_name", MESSAGES), ("icon", "mail")],
    )
    .await;
    let html = cell().await.expect("the row");
    assert!(
        html.contains(r##"style="background: #347470" aria-hidden="true"><img src="/static/app-icons/mail.svg""##),
        "{html}"
    );

    // No app logo for a part of Vibb One: refused at the field, the row kept.
    let own = save(
        &app,
        &cookie,
        id,
        &[("package_name", MESSAGES), ("icon", "own")],
    )
    .await;
    assert_eq!(own.status, StatusCode::BAD_REQUEST);
    let page = own.text();
    assert!(page.contains(r#"aria-invalid="true""#), "{page}");
    assert!(page.contains("Pick Automatic or a symbol."), "{page}");
    assert_eq!(app_display(&app, &token).await[0]["icon"], "mail");
    // Reset: back to its own look; the row stays while SMS is managed.
    save(
        &app,
        &cookie,
        id,
        &[("package_name", MESSAGES), ("action", "reset")],
    )
    .await;
    assert_eq!(app_display(&app, &token).await, serde_json::json!([]));
    assert!(cell().await.is_some());
    // Code QA S2: SMS not managed again. The last report says nothing of SMS (as one before the first unlock), so
    // the newest that does is read - the phone still has its threads, and the row.
    sqlx::query("UPDATE device_policy SET sms_mode = 'unmanaged' WHERE device_id = ?")
        .bind(id)
        .execute(&app.db)
        .await
        .unwrap();
    assert!(cell().await.is_some());
    // Code QA S1: a name is all that lists the row once the phone reports no threads; Reset takes the row off the
    // page, so the save lands on the group - never on an anchor that is gone.
    save(
        &app,
        &cookie,
        id,
        &[("package_name", MESSAGES), ("label", "Tekst")],
    )
    .await;
    let res = app
        .request(
            Method::POST,
            "/api/devices/status",
            Some(&token),
            Some(serde_json::json!({
                "lock_reason": "NONE", "kiosk_engaged": true, "sms_state": { "has_history": false },
            })),
        )
        .await;
    assert!(res.status.is_success());
    assert!(cell().await.is_some());
    let reset = save(
        &app,
        &cookie,
        id,
        &[("package_name", MESSAGES), ("action", "reset")],
    )
    .await;
    assert_redirect(&reset, &device_page(id, Section::Apps), Some("allowed"));
    app.assert_lands(&reset, &cookie).await;
    assert_eq!(cell().await, None);
    // Every other name under the prefix is nobody's.
    let other = save(
        &app,
        &cookie,
        id,
        &[("package_name", "me.vibb.builtin.other"), ("label", "X")],
    )
    .await;
    assert_eq!(other.status, StatusCode::BAD_REQUEST);
}

/// Refused input: 400, nothing written, Device › Apps with that app's form open, the entered
/// values kept and the field with the problem focused (no jump to the top).
#[tokio::test]
async fn refused_input_keeps_the_values_by_the_field() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("phone").await;
    report_installed(&app, &token, &[ELEMENT]).await;
    let cases: [(&[(&str, &str)], &str); 4] = [
        (
            &[("label", "Twenty-one characters")],
            "At most 20 characters",
        ),
        (&[("label", "Ch\u{7}at")], "control characters"),
        (
            &[("label", "Chat"), ("icon", "rocket")],
            "Pick Logo, Automatic or a symbol",
        ),
        (
            &[("label", "Chat"), ("color", "green")],
            "Pick one of the colours",
        ),
    ];
    for (fields, message) in cases {
        let mut all = vec![("package_name", ELEMENT), ("action", "save")];
        all.extend_from_slice(fields);
        let res = save(&app, &cookie, id, &all).await;
        assert_eq!(res.status, StatusCode::BAD_REQUEST, "{message}");
        let html = res.text();
        assert!(html.contains(message), "{message}: {html}");
        // The app's own page (design 127), the error at the field.
        assert!(
            html.contains(&format!(
                r#"<h1 class="page-title">Label of {ELEMENT}</h1>"#
            )),
            "{message}"
        );
        assert!(html.contains(r#"aria-invalid="true""#), "{message}");
        assert!(html.contains(r#"id="display-error""#), "{message}");
        assert!(html.contains("/static/scroll-restore.js"));
    }
    let html = save(
        &app,
        &cookie,
        id,
        &[
            ("package_name", ELEMENT),
            ("label", "Chat"),
            ("icon", "rocket"),
        ],
    )
    .await
    .text();
    assert!(html.contains(r#"value="Chat""#), "the entered name is kept");
    // Design 127 §2.4: the field has `maxlength="20"`; a refused save still shows the whole entered value (the
    // attribute only stops typing).
    assert!(html.contains(r#"id="display-label" name="label" maxlength="20" value="Chat""#));
    let rows: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM device_app_display")
        .fetch_one(&app.db)
        .await
        .unwrap();
    assert_eq!(rows, 0);

    // The catalog form refuses the same way, on its own page.
    let element = catalog_app(&app, "Element X", ELEMENT).await;
    let res = app
        .request_form(
            Method::POST,
            &format!("/apps/tracked/{element}/display"),
            Some(&cookie),
            &[("label", "x".repeat(21).as_str()), ("icon", "chat")],
        )
        .await;
    assert_eq!(res.status, StatusCode::BAD_REQUEST);
    let html = res.text();
    assert!(html.contains("At most 20 characters") && html.contains(r#"aria-invalid="true""#));
    assert!(html.contains(&format!(r#"value="{}""#, "x".repeat(21))));
    let missing = app
        .request_form(
            Method::POST,
            "/apps/tracked/9999/display",
            Some(&cookie),
            &[("label", "A")],
        )
        .await;
    assert_eq!(missing.status, StatusCode::NOT_FOUND);
}

/// A phone's rows go with the phone; at most 200 per phone.
#[tokio::test]
async fn rows_go_with_the_phone_and_are_capped() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, _) = app.enrolled_device("phone").await;
    for i in 0..crate::app_display::MAX_ROWS_PER_DEVICE {
        sqlx::query(
            "INSERT INTO device_app_display (device_id, package_name, label) VALUES (?, ?, 'A')",
        )
        .bind(id)
        .bind(format!("org.example.app{i}"))
        .execute(&app.db)
        .await
        .unwrap();
    }
    let res = save(
        &app,
        &cookie,
        id,
        &[("package_name", ELEMENT), ("label", "Chat")],
    )
    .await;
    assert_eq!(res.status, StatusCode::BAD_REQUEST);
    // Changing an existing one still works.
    let res = save(
        &app,
        &cookie,
        id,
        &[("package_name", "org.example.app0"), ("label", "B")],
    )
    .await;
    assert!(res.status.is_redirection());

    sqlx::query("DELETE FROM devices WHERE id = ?")
        .bind(id)
        .execute(&app.db)
        .await
        .unwrap();
    let rows: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM device_app_display")
        .fetch_one(&app.db)
        .await
        .unwrap();
    assert_eq!(rows, 0);
}

/// What the phones can't use never reaches them: a stored unknown icon is left out (the label
/// stays), an unknown colour is "auto", an entry with nothing left is dropped; a failing query
/// sends an empty list, not a 500.
#[tokio::test]
async fn stored_values_are_cleaned_and_a_failing_query_sends_none() {
    let app = TestApp::new().await;
    let (id, token) = app.enrolled_device("phone").await;
    for (package, label, icon, color) in [
        ("org.example.a", Some("A"), Some("rocket"), "peach"),
        ("org.example.b", None, Some("star"), "green"),
        ("org.example.c", None, Some("rocket"), "auto"),
    ] {
        sqlx::query(
            "INSERT INTO device_app_display (device_id, package_name, label, icon_key, color_key) \
             VALUES (?, ?, ?, ?, ?)",
        )
        .bind(id)
        .bind(package)
        .bind(label)
        .bind(icon)
        .bind(color)
        .execute(&app.db)
        .await
        .unwrap();
    }
    assert_eq!(
        app_display(&app, &token).await,
        serde_json::json!([
            {"package_name": "org.example.a", "label": "A", "icon": null, "color": "auto"},
            {"package_name": "org.example.b", "label": null, "icon": "star", "color": "auto"},
        ])
    );
    sqlx::query("DROP TABLE device_app_display")
        .execute(&app.db)
        .await
        .unwrap();
    let policy = app
        .request(Method::GET, "/api/devices/policy", Some(&token), None)
        .await;
    assert_eq!(policy.status, StatusCode::OK);
    assert_eq!(
        policy.json()["launcher_ui"]["app_display"],
        serde_json::json!([])
    );
}
