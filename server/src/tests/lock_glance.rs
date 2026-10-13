//! Design 25: the lock's glance page - the apps whose messages the PIN lock's first page shows and
//! the text switch (migrations/0066), `PolicyResponse.lock_glance`, and the device page's "On the
//! lock screen" subsection.

use axum::http::{Method, StatusCode};
use serde_json::{Value, json};

use super::TestApp;
use crate::lock_glance::ELEMENT_X;

/// The migration that adds the table, the switch and the default (renumbered at a rebase: here only).
const MIGRATION: i64 = 66;

async fn policy(app: &TestApp, token: &str) -> Value {
    let res = app
        .request(Method::GET, "/api/devices/policy", Some(token), None)
        .await;
    assert_eq!(res.status, StatusCode::OK, "{}", res.text());
    res.json()
}

async fn ticks(app: &TestApp, id: i64) -> Vec<String> {
    sqlx::query_scalar(
        "SELECT package_name FROM device_lock_glance_apps WHERE device_id = ? ORDER BY package_name",
    )
    .bind(id)
    .fetch_all(&app.db)
    .await
    .unwrap()
}

async fn text_switch(app: &TestApp, id: i64) -> bool {
    sqlx::query_scalar("SELECT lock_glance_text FROM device_policy WHERE device_id = ?")
        .bind(id)
        .fetch_one(&app.db)
        .await
        .unwrap()
}

async fn events(app: &TestApp, kind: &str) -> Vec<(String, Option<String>)> {
    sqlx::query_as("SELECT detail, username FROM security_events WHERE event_type = ? ORDER BY id")
        .bind(kind)
        .fetch_all(&app.db)
        .await
        .unwrap()
        .into_iter()
        .map(|(detail, user): (Option<String>, Option<String>)| (detail.unwrap_or_default(), user))
        .collect()
}

async fn tick(
    app: &TestApp,
    cookie: Option<&str>,
    id: i64,
    package: &str,
    on: bool,
) -> super::TestResponse {
    let mut fields = vec![("package_name", package)];
    if on {
        fields.push(("on", "on"));
    }
    app.request_form(
        Method::POST,
        &format!("/devices/{id}/lock-screen/apps"),
        cookie,
        &fields,
    )
    .await
}

async fn switch(app: &TestApp, cookie: Option<&str>, id: i64, on: bool) -> super::TestResponse {
    let fields: &[(&str, &str)] = if on { &[("show_text", "on")] } else { &[] };
    app.request_form(
        Method::POST,
        &format!("/devices/{id}/lock-screen"),
        cookie,
        fields,
    )
    .await
}

/// A status report with `installed` apps and `extra` fields.
async fn report(app: &TestApp, token: &str, installed: &[(&str, &str)], extra: Value) {
    let mut body = json!({
        "lock_reason": "NONE",
        "kiosk_engaged": true,
        "installed_apps": installed
            .iter()
            .map(|(p, l)| json!({ "package_name": p, "label": l }))
            .collect::<Vec<_>>(),
    });
    for (key, value) in extra.as_object().unwrap() {
        body[key] = value.clone();
    }
    let res = app
        .request(Method::POST, "/api/devices/status", Some(token), Some(body))
        .await;
    assert!(res.status.is_success(), "{}", res.text());
}

async fn set_allowlist(app: &TestApp, id: i64, allowlist: Option<&[&str]>) {
    sqlx::query("UPDATE device_policy SET allowlist_json = ? WHERE device_id = ?")
        .bind(allowlist.map(|a| serde_json::to_string(a).unwrap()))
        .bind(id)
        .execute(&app.db)
        .await
        .unwrap();
}

// ---- data -----------------------------------------------------------------------------------------

#[tokio::test]
async fn the_migration_gives_existing_devices_element_x_and_the_switch_off() {
    let (db, _dir) = super::cleanup::migrated_before(MIGRATION).await;
    let mut ids = Vec::new();
    for name in ["one", "two"] {
        let id: i64 = sqlx::query_scalar("INSERT INTO devices (name) VALUES (?) RETURNING id")
            .bind(name)
            .fetch_one(&db)
            .await
            .unwrap();
        sqlx::query("INSERT INTO device_policy (device_id, kiosk_desired) VALUES (?, 1)")
            .bind(id)
            .execute(&db)
            .await
            .unwrap();
        ids.push(id);
    }
    super::cleanup::finish(&db).await;
    for id in ids {
        let apps: Vec<String> = sqlx::query_scalar(
            "SELECT package_name FROM device_lock_glance_apps WHERE device_id = ?",
        )
        .bind(id)
        .fetch_all(&db)
        .await
        .unwrap();
        assert_eq!(apps, [ELEMENT_X]);
        let text: bool =
            sqlx::query_scalar("SELECT lock_glance_text FROM device_policy WHERE device_id = ?")
                .bind(id)
                .fetch_one(&db)
                .await
                .unwrap();
        assert!(!text);
    }
}

#[tokio::test]
async fn a_new_device_gets_element_x_by_the_trigger_and_a_delete_cascades() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    // The test helper's plain INSERT, as any other path that creates a device.
    let (helper, _) = app.create_device("helper").await;
    assert_eq!(ticks(&app, helper).await, [ELEMENT_X]);
    assert!(!text_switch(&app, helper).await);
    // The page's own create.
    let res = app
        .request_form(
            Method::POST,
            "/devices/new",
            Some(&cookie),
            &[("name", "page")],
        )
        .await;
    assert!(res.status.is_redirection(), "{}", res.text());
    let page: i64 = sqlx::query_scalar("SELECT id FROM devices WHERE name = 'page'")
        .fetch_one(&app.db)
        .await
        .unwrap();
    assert_eq!(ticks(&app, page).await, [ELEMENT_X]);

    let res = app
        .request_form(
            Method::POST,
            &format!("/devices/{helper}/delete"),
            Some(&cookie),
            &[],
        )
        .await;
    assert!(res.status.is_redirection(), "{}", res.text());
    assert!(ticks(&app, helper).await.is_empty());
    assert_eq!(ticks(&app, page).await, [ELEMENT_X]);
}

#[tokio::test]
async fn the_policy_carries_the_sorted_valid_apps_and_the_switch() {
    let app = TestApp::new().await;
    let (id, token) = app.enrolled_device("phone").await;
    assert_eq!(
        policy(&app, &token).await["lock_glance"],
        json!({ "apps": [ELEMENT_X], "show_text": false })
    );
    for package in [
        "org.thoughtcrime.securesms",
        "com.a.chat",
        "not a package",
        "x",
    ] {
        sqlx::query("INSERT INTO device_lock_glance_apps (device_id, package_name) VALUES (?, ?)")
            .bind(id)
            .bind(package)
            .execute(&app.db)
            .await
            .unwrap();
    }
    sqlx::query("UPDATE device_policy SET lock_glance_text = 1 WHERE device_id = ?")
        .bind(id)
        .execute(&app.db)
        .await
        .unwrap();
    assert_eq!(
        policy(&app, &token).await["lock_glance"],
        json!({
            "apps": ["com.a.chat", ELEMENT_X, "org.thoughtcrime.securesms"],
            "show_text": true,
        })
    );
    // Another phone's ticks are its own.
    let (_, other) = app.enrolled_device("other").await;
    assert_eq!(
        policy(&app, &other).await["lock_glance"]["apps"],
        json!([ELEMENT_X])
    );
}

#[tokio::test]
async fn an_unreadable_table_sends_no_apps_and_the_rest_of_the_policy() {
    let app = TestApp::new().await;
    let (id, token) = app.enrolled_device("phone").await;
    sqlx::query("UPDATE device_policy SET lock_glance_text = 1 WHERE device_id = ?")
        .bind(id)
        .execute(&app.db)
        .await
        .unwrap();
    let before = policy(&app, &token).await;
    // The trigger names the table, so it goes first.
    sqlx::query("DROP TRIGGER devices_get_lock_glance_default")
        .execute(&app.db)
        .await
        .unwrap();
    sqlx::query("DROP TABLE device_lock_glance_apps")
        .execute(&app.db)
        .await
        .unwrap();
    let after = policy(&app, &token).await;
    assert_eq!(
        after["lock_glance"],
        json!({ "apps": [], "show_text": true })
    );
    let strip = |mut v: Value| {
        v.as_object_mut().unwrap().remove("lock_glance");
        v
    };
    assert_eq!(strip(before), strip(after));
    // Lock Screen & PINs says so instead of showing every switch off.
    let cookie = app.admin_cookie().await;
    let page = app.get_page(&lock_page(id), &cookie).await;
    assert_eq!(page.status, StatusCode::OK);
    assert!(subsection(&page.text()).contains("The apps couldn't be read. Check the server log."));
}

// ---- the routes -------------------------------------------------------------------------------------

#[tokio::test]
async fn ticking_and_unticking_an_app_is_idempotent_logged_and_nudges() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("phone").await;
    let mut nudges = app.state.command_notify.subscribe();

    for _ in 0..2 {
        let res = tick(&app, Some(&cookie), id, "org.thoughtcrime.securesms", true).await;
        assert_eq!(res.status, StatusCode::SEE_OTHER, "{}", res.text());
        // Exactly the page, no fragment: scroll-restore.js keeps the parent's place.
        super::assert_redirect(&res, &lock_page(id), None);
        assert_eq!(nudges.try_recv().ok(), Some(id));
    }
    assert_eq!(
        ticks(&app, id).await,
        [ELEMENT_X, "org.thoughtcrime.securesms"]
    );
    assert_eq!(
        policy(&app, &token).await["lock_glance"]["apps"],
        json!([ELEMENT_X, "org.thoughtcrime.securesms"])
    );

    for _ in 0..2 {
        let res = tick(&app, Some(&cookie), id, ELEMENT_X, false).await;
        super::assert_redirect(&res, &lock_page(id), None);
        assert_eq!(nudges.try_recv().ok(), Some(id));
    }
    assert_eq!(ticks(&app, id).await, ["org.thoughtcrime.securesms"]);
    assert_eq!(
        events(&app, "lock_screen_app_changed").await,
        [
            "device {id}: org.thoughtcrime.securesms on the lock screen: on",
            "device {id}: org.thoughtcrime.securesms on the lock screen: on",
            "device {id}: io.element.android.x on the lock screen: off",
            "device {id}: io.element.android.x on the lock screen: off",
        ]
        .map(|d| (
            d.replace("{id}", &id.to_string()),
            Some("parent".to_string())
        ))
    );
}

#[tokio::test]
async fn the_text_switch_saves_logs_and_nudges() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("phone").await;
    let mut nudges = app.state.command_notify.subscribe();

    for on in [true, true, false, false] {
        let res = switch(&app, Some(&cookie), id, on).await;
        assert_eq!(res.status, StatusCode::SEE_OTHER, "{}", res.text());
        super::assert_redirect(&res, &lock_page(id), None);
        assert_eq!(nudges.try_recv().ok(), Some(id));
        assert_eq!(text_switch(&app, id).await, on);
        assert_eq!(
            policy(&app, &token).await["lock_glance"]["show_text"],
            json!(on)
        );
    }
    assert_eq!(
        events(&app, "lock_screen_text_changed").await,
        ["on", "on", "off", "off"].map(|s| (
            format!("device {id}: message text on the lock screen: {s}"),
            Some("parent".to_string())
        ))
    );
}

#[tokio::test]
async fn refused_saves_write_nothing() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, _) = app.enrolled_device("phone").await;
    let mut nudges = app.state.command_notify.subscribe();

    // 400: not a package name, or Play core.
    for bad in [
        "",
        "not a package",
        "x",
        "io.element.android.x;",
        "com.android.vending",
        "com.google.android.gms",
    ] {
        let res = tick(&app, Some(&cookie), id, bad, true).await;
        assert_eq!(res.status, StatusCode::BAD_REQUEST, "{bad}");
    }
    // 404: an unknown device, for both routes.
    assert_eq!(
        tick(&app, Some(&cookie), 999, "com.a.chat", true)
            .await
            .status,
        StatusCode::NOT_FOUND
    );
    assert_eq!(
        switch(&app, Some(&cookie), 999, true).await.status,
        StatusCode::NOT_FOUND
    );
    // No session: the login, and nothing written.
    let res = tick(&app, None, id, "com.a.chat", true).await;
    assert!(res.status.is_redirection());
    assert_ne!(res.location(), Some(lock_page(id).as_str()));
    let res = tick(&app, None, id, ELEMENT_X, false).await;
    assert!(res.status.is_redirection());
    let res = switch(&app, None, id, true).await;
    assert!(res.status.is_redirection());

    // 500: a database that refuses the write.
    for (statement, undo) in [
        (
            "CREATE TRIGGER fail_tick BEFORE INSERT ON device_lock_glance_apps BEGIN SELECT RAISE(ABORT, 'injected'); END",
            "DROP TRIGGER fail_tick",
        ),
        (
            "CREATE TRIGGER fail_untick BEFORE DELETE ON device_lock_glance_apps BEGIN SELECT RAISE(ABORT, 'injected'); END",
            "DROP TRIGGER fail_untick",
        ),
        (
            "CREATE TRIGGER fail_switch BEFORE UPDATE ON device_policy BEGIN SELECT RAISE(ABORT, 'injected'); END",
            "DROP TRIGGER fail_switch",
        ),
    ] {
        sqlx::query(statement).execute(&app.db).await.unwrap();
        let res = if statement.contains("fail_tick") {
            tick(&app, Some(&cookie), id, "com.a.chat", true).await
        } else if statement.contains("fail_untick") {
            tick(&app, Some(&cookie), id, ELEMENT_X, false).await
        } else {
            switch(&app, Some(&cookie), id, true).await
        };
        assert_eq!(res.status, StatusCode::INTERNAL_SERVER_ERROR, "{statement}");
        sqlx::query(undo).execute(&app.db).await.unwrap();
    }

    assert_eq!(ticks(&app, id).await, [ELEMENT_X]);
    assert!(!text_switch(&app, id).await);
    assert!(nudges.try_recv().is_err(), "nothing was nudged");
    assert!(events(&app, "lock_screen_app_changed").await.is_empty());
    assert!(events(&app, "lock_screen_text_changed").await.is_empty());
}

// ---- Lock Screen & PINs (design 31 step 4; the device page's Apps card before) ----------------

fn lock_page(id: i64) -> String {
    super::device_page(id, super::Section::Lock)
}

/// The "Lock screen" and "Notifications on the lock screen" groups' HTML.
fn subsection(page: &str) -> &str {
    let start = page
        .find(r#"<section id="lock-screen""#)
        .expect("the Lock screen group");
    let notifications = page[start..]
        .find(r#"<section id="notifications""#)
        .expect("the Notifications group")
        + start;
    let end = page[notifications..].find("</section>").unwrap() + notifications;
    &page[start..end]
}

/// The form of `package`: its switch and its note, if it is listed.
fn form_of(section: &str, package: &str) -> Option<String> {
    let id = section.find(&format!(r#"id="lock-screen-app-{package}""#))?;
    let rest = &section[section[..id].rfind("<form").unwrap()..];
    Some(rest[..rest.find("</form>").unwrap()].to_string())
}

fn switch_on(form: &str) -> bool {
    super::input_checked(form, "on", "on").expect("a switch")
}

#[tokio::test]
async fn the_page_lists_the_allowed_apps_with_their_ticks() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("phone").await;
    report(
        &app,
        &token,
        &[
            (ELEMENT_X, "Element X"),
            ("org.thoughtcrime.securesms", "Signal"),
            ("com.example.game", "Game"),
            ("com.android.vending", "Play Store"),
        ],
        json!({}),
    )
    .await;
    set_allowlist(
        &app,
        id,
        Some(&[
            ELEMENT_X,
            "org.thoughtcrime.securesms",
            "com.android.vending",
        ]),
    )
    .await;
    sqlx::query(
        "INSERT INTO device_lock_glance_apps (device_id, package_name) VALUES (?, 'com.old.chat')",
    )
    .bind(id)
    .execute(&app.db)
    .await
    .unwrap();

    let page = app.get_page(&lock_page(id), &cookie).await;
    assert_eq!(page.status, StatusCode::OK);
    let page = page.text();
    // Gone from the device page.
    let summary = app
        .get_page(&format!("/devices/{id}"), &cookie)
        .await
        .text();
    assert!(!summary.contains("lock-screen"), "{summary}");
    let section = subsection(&page);
    assert!(section.contains("Nothing can be answered or dismissed there"));
    assert!(section.contains("login codes show too"));
    assert!(section.contains("Missed calls always show."));
    assert_eq!(
        super::form_action_of(section, "show_text"),
        Some(format!("/devices/{id}/lock-screen").as_str())
    );
    assert!(super::autosaves(section, "show_text"));
    assert_eq!(
        super::input_checked(section, "show_text", "on"),
        Some(false)
    );

    let element = form_of(section, ELEMENT_X).expect("Element X listed");
    assert!(
        switch_on(&element) && element.contains("Element X"),
        "{element}"
    );
    assert!(element.contains(&format!(r#"action="/devices/{id}/lock-screen/apps""#)));
    assert!(element.contains(r#"role="switch""#));
    assert!(super::autosaves(&element, "on"));
    let signal = form_of(section, "org.thoughtcrime.securesms").expect("Signal listed");
    assert!(!switch_on(&signal) && signal.contains("Signal"), "{signal}");
    // Not allowed: not listed - unless ticked, then with its note so it can be removed.
    assert!(form_of(section, "com.example.game").is_none());
    assert!(form_of(section, "com.android.vending").is_none());
    let old = form_of(section, "com.old.chat").expect("the ticked, not allowed app");
    assert!(
        switch_on(&old) && old.contains("<span class=\"row-sub\">Not allowed</span>"),
        "{old}"
    );

    // The switch on shows on.
    switch(&app, Some(&cookie), id, true).await;
    let page = app.get_page(&lock_page(id), &cookie).await.text();
    assert_eq!(
        super::input_checked(subsection(&page), "show_text", "on"),
        Some(true)
    );
}

#[tokio::test]
async fn without_an_allowlist_every_reported_app_is_listed() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("phone").await;
    report(
        &app,
        &token,
        &[
            ("com.example.game", "Game"),
            ("org.thoughtcrime.securesms", "Signal"),
            ("com.google.android.gms", "Play services"),
            ("me.vibb.launcher", "Vibb"),
        ],
        json!({}),
    )
    .await;
    // Apps unmanaged (a calls-only phone): no allowlist, but the lock may still be on (QA Low 7).
    set_allowlist(&app, id, None).await;
    let page = app.get_page(&lock_page(id), &cookie).await.text();
    let section = subsection(&page);
    assert!(form_of(section, "com.example.game").is_some());
    assert!(form_of(section, "org.thoughtcrime.securesms").is_some());
    assert!(form_of(section, "com.google.android.gms").is_none());
    assert!(form_of(section, "me.vibb.launcher").is_none());
    // Element X is ticked by default but not on this phone: still listed, so it can be unticked.
    let element = form_of(section, ELEMENT_X).expect("the default tick");
    assert!(
        switch_on(&element) && element.contains("Not on the phone"),
        "{element}"
    );
    // Sorted by label: Game before Signal.
    assert!(
        section.find("lock-screen-app-com.example.game").unwrap()
            < section
                .find("lock-screen-app-org.thoughtcrime.securesms")
                .unwrap()
    );
}

/// Code QA Low 6: a ticked package that is allowed but neither reported nor a selected catalog app
/// isn't "not allowed"; an empty list says why.
#[tokio::test]
async fn unlisted_ticks_and_the_empty_list_say_the_right_thing() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("phone").await;
    // Before any report: "hasn't reported", Element X's default tick shown so it can be removed.
    let page = app.get_page(&lock_page(id), &cookie).await.text();
    assert!(form_of(subsection(&page), ELEMENT_X).is_some());
    report(&app, &token, &[("com.example.game", "Game")], json!({})).await;
    // Element X in the allowlist (Play-installed later, say) but not reported: not on the phone.
    set_allowlist(&app, id, Some(&[ELEMENT_X])).await;
    let page = app.get_page(&lock_page(id), &cookie).await.text();
    let element = form_of(subsection(&page), ELEMENT_X).expect("the tick");
    assert!(
        element.contains("Not on the phone") && !element.contains("Not allowed"),
        "{element}"
    );
    // The phone reported its apps, nothing is allowed and nothing is ticked: no "hasn't reported".
    set_allowlist(&app, id, Some(&[])).await;
    sqlx::query("DELETE FROM device_lock_glance_apps WHERE device_id = ?")
        .bind(id)
        .execute(&app.db)
        .await
        .unwrap();
    let page = app.get_page(&lock_page(id), &cookie).await.text();
    let section = subsection(&page);
    assert!(
        section.contains("No allowed apps to choose from"),
        "{section}"
    );
    assert!(!section.contains("hasn't reported its apps"));
}

#[tokio::test]
async fn the_page_shows_each_note() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("phone").await;
    let page =
        || async { subsection(&app.get_page(&lock_page(id), &cookie).await.text()).to_string() };

    // No report yet, no Child PIN: only the PIN warning.
    let html = page().await;
    assert!(html.contains("No lock without a Child PIN"), "{html}");
    assert!(!html.contains("Vibb can&#39;t show these yet"));
    assert!(!html.contains("Missed calls only"));

    // A launcher without the capability and without notification access; a Child PIN.
    sqlx::query(
        "UPDATE device_policy SET kid_pin_hash = 'aGFzaA', kid_pin_salt = 'c2FsdA', \
         kid_pin_length = 4 WHERE device_id = ?",
    )
    .bind(id)
    .execute(&app.db)
    .await
    .unwrap();
    report(
        &app,
        &token,
        &[],
        json!({ "capabilities": ["pin_lock_v1"], "notification_listener_enabled": false }),
    )
    .await;
    let html = page().await;
    assert!(!html.contains("without a Child PIN"));
    assert!(html.contains("Vibb can&#39;t show these yet"), "{html}");
    assert!(html.contains("Missed calls only"), "{html}");

    // An up-to-date launcher with access: no warnings.
    report(
        &app,
        &token,
        &[],
        json!({ "capabilities": ["pin_lock_v1", "lock_glance_v1"], "notification_listener_enabled": true }),
    )
    .await;
    let html = page().await;
    assert!(!html.contains("data-warning"), "{html}");
}

/// Home Screen's notification-access warning names the lock screen (design 31 step 5; the device
/// page's Launcher card before); Lock Screen & PINs says what its first page shows.
#[tokio::test]
async fn the_home_screen_warning_and_the_lock_page_name_the_lock_screen() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("phone").await;
    report(
        &app,
        &token,
        &[],
        json!({ "notification_listener_enabled": false }),
    )
    .await;
    let page = app
        .get_page(&super::device_page(id, super::Section::Home), &cookie)
        .await
        .text();
    assert!(page.contains("the lock screen shows only missed calls"));
    let lock = app.get_page(&lock_page(id), &cookie).await.text();
    assert!(lock.contains(
        "The lock's first page shows the clock, what's playing and the newest notifications."
    ));
}
