//! Design 25: the lock's glance page - the apps whose messages the PIN lock's first page shows
//! (migrations/0066) and, per app, whether their text shows (design 51, migrations/0083),
//! `PolicyResponse.lock_glance`, and Lock Screen & PINs' Notifications group. Since design 82 the built-in Meldinger
//! has a row of its own in the table (migrations/0135), sent as `lock_glance.sms`.

use axum::http::{Method, StatusCode};
use serde_json::{Value, json};

use super::TestApp;
use crate::builtin::MESSAGES;
use crate::lock_glance::ELEMENT_X;

/// The migration that adds the table, the switch and the default (renumbered at a rebase: here only).
const MIGRATION: i64 = 66;

/// The ticked apps - without Meldinger's own row (design 82), which is [messages]'.
async fn ticks(app: &TestApp, id: i64) -> Vec<String> {
    sqlx::query_scalar(
        "SELECT package_name FROM device_lock_glance_apps WHERE device_id = ? AND package_name != ? \
         ORDER BY package_name",
    )
    .bind(id)
    .bind(MESSAGES)
    .fetch_all(&app.db)
    .await
    .unwrap()
}

/// Each ticked app with its text flag.
async fn texts(app: &TestApp, id: i64) -> Vec<(String, bool)> {
    sqlx::query_as(
        "SELECT package_name, show_text FROM device_lock_glance_apps WHERE device_id = ? \
         AND package_name != ? ORDER BY package_name",
    )
    .bind(id)
    .bind(MESSAGES)
    .fetch_all(&app.db)
    .await
    .unwrap()
}

/// Meldinger's stored level (design 82 D2): no row = Off, else its `show_text`.
async fn messages(app: &TestApp, id: i64) -> Option<bool> {
    messages_in(&app.db, id).await
}

async fn messages_in(db: &sqlx::SqlitePool, id: i64) -> Option<bool> {
    sqlx::query_scalar(
        "SELECT show_text FROM device_lock_glance_apps WHERE device_id = ? AND package_name = ?",
    )
    .bind(id)
    .bind(MESSAGES)
    .fetch_optional(db)
    .await
    .unwrap()
}

async fn sms_managed(app: &TestApp, id: i64) {
    sqlx::query("UPDATE device_policy SET sms_mode = 'managed' WHERE device_id = ?")
        .bind(id)
        .execute(&app.db)
        .await
        .unwrap();
}

fn text_of(package: &str, on: bool) -> (String, bool) {
    (package.to_string(), on)
}

async fn events(app: &TestApp, kind: &str) -> Vec<(String, Option<String>)> {
    let users: Vec<Option<String>> =
        sqlx::query_scalar("SELECT username FROM security_events WHERE event_type = ? ORDER BY id")
            .bind(kind)
            .fetch_all(&app.db)
            .await
            .unwrap();
    super::security_words(app, kind, crate::i18n::Lang::En)
        .await
        .into_iter()
        .zip(users)
        .collect()
}

/// Lock Screen & PINs' select for one app (2026-10-21): `off`, `name` or `text` (or anything, for
/// the refusals).
async fn show(
    app: &TestApp,
    cookie: Option<&str>,
    id: i64,
    package: &str,
    value: &str,
) -> super::TestResponse {
    app.request_form(
        Method::POST,
        &format!("/devices/{id}/lock-screen/apps"),
        cookie,
        &[("package_name", package), ("show", value)],
    )
    .await
}

/// On (Name only) or Off.
async fn tick(
    app: &TestApp,
    cookie: Option<&str>,
    id: i64,
    package: &str,
    on: bool,
) -> super::TestResponse {
    show(app, cookie, id, package, if on { "name" } else { "off" }).await
}

/// Full text or Name only (design 51's text switch).
async fn switch(
    app: &TestApp,
    cookie: Option<&str>,
    id: i64,
    package: &str,
    on: bool,
) -> super::TestResponse {
    show(app, cookie, id, package, if on { "text" } else { "name" }).await
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
            "SELECT package_name FROM device_lock_glance_apps WHERE device_id = ? ORDER BY package_name",
        )
        .bind(id)
        .fetch_all(&db)
        .await
        .unwrap();
        // And 0135's row for Meldinger (design 82).
        assert_eq!(apps, [ELEMENT_X, MESSAGES]);
        let text: bool =
            sqlx::query_scalar("SELECT lock_glance_text FROM device_policy WHERE device_id = ?")
                .bind(id)
                .fetch_one(&db)
                .await
                .unwrap();
        assert!(!text);
    }
}

/// Design 51: 0083 gives every existing tick its phone's old switch.
#[tokio::test]
async fn the_text_migration_takes_each_phones_old_switch() {
    let (db, _dir) = super::cleanup::migrated_before(83).await;
    let mut ids = Vec::new();
    for (name, text) in [("with text", 1), ("sender only", 0)] {
        let id: i64 = sqlx::query_scalar("INSERT INTO devices (name) VALUES (?) RETURNING id")
            .bind(name)
            .fetch_one(&db)
            .await
            .unwrap();
        sqlx::query(
            "INSERT INTO device_policy (device_id, kiosk_desired, lock_glance_text) VALUES (?, 1, ?)",
        )
        .bind(id)
        .bind(text)
        .execute(&db)
        .await
        .unwrap();
        sqlx::query(
            "INSERT INTO device_lock_glance_apps (device_id, package_name) VALUES (?, 'com.a.chat')",
        )
        .bind(id)
        .execute(&db)
        .await
        .unwrap();
        ids.push(id);
    }
    super::cleanup::finish(&db).await;
    for (id, text) in ids.into_iter().zip([true, false]) {
        let rows: Vec<(String, bool)> = sqlx::query_as(
            "SELECT package_name, show_text FROM device_lock_glance_apps WHERE device_id = ? \
             ORDER BY package_name",
        )
        .bind(id)
        .fetch_all(&db)
        .await
        .unwrap();
        // 0135's row for Meldinger follows `sms_lock_text` (off here), not this switch.
        assert_eq!(
            rows,
            [
                text_of("com.a.chat", text),
                text_of(ELEMENT_X, text),
                text_of(MESSAGES, false)
            ]
        );
    }
}

/// Design 82 D2: 0135 gives every existing phone Meldinger's row at what it shows today - Full text where the old
/// switch was on, else Name only (a device without a policy row too).
#[tokio::test]
async fn the_messages_migration_takes_each_phones_old_switch() {
    let (db, _dir) = super::cleanup::migrated_before(135).await;
    let mut ids = Vec::new();
    for (name, text) in [
        ("with text", Some(1)),
        ("name only", Some(0)),
        ("bare", None),
    ] {
        let id: i64 = sqlx::query_scalar("INSERT INTO devices (name) VALUES (?) RETURNING id")
            .bind(name)
            .fetch_one(&db)
            .await
            .unwrap();
        if let Some(text) = text {
            sqlx::query(
                "INSERT INTO device_policy (device_id, kiosk_desired, sms_lock_text) VALUES (?, 1, ?)",
            )
            .bind(id)
            .bind(text)
            .execute(&db)
            .await
            .unwrap();
        }
        ids.push(id);
    }
    super::cleanup::finish(&db).await;
    for (id, text) in ids.into_iter().zip([true, false, false]) {
        assert_eq!(messages_in(&db, id).await, Some(text));
    }
}

#[tokio::test]
async fn a_new_device_gets_element_x_by_the_trigger_and_a_delete_cascades() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    // The test helper's plain INSERT, as any other path that creates a device.
    let (helper, _) = app.create_device("helper").await;
    assert_eq!(texts(&app, helper).await, [text_of(ELEMENT_X, false)]);
    // Design 82 D2: and Meldinger at Name only.
    assert_eq!(messages(&app, helper).await, Some(false));
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
    assert_eq!(messages(&app, helper).await, None);
    assert_eq!(ticks(&app, page).await, [ELEMENT_X]);
    assert_eq!(messages(&app, page).await, Some(false));
}

#[tokio::test]
async fn the_policy_carries_the_sorted_valid_apps_each_with_its_text() {
    let app = TestApp::new().await;
    let (id, token) = app.enrolled_device("phone").await;
    assert_eq!(
        app.policy(&token).await["lock_glance"],
        json!({ "apps": [{ "package": ELEMENT_X, "show_text": false }], "sms": "name" })
    );
    for (package, text) in [
        ("org.thoughtcrime.securesms", true),
        ("com.a.chat", false),
        ("not a package", true),
        ("x", false),
    ] {
        sqlx::query(
            "INSERT INTO device_lock_glance_apps (device_id, package_name, show_text) \
             VALUES (?, ?, ?)",
        )
        .bind(id)
        .bind(package)
        .bind(text)
        .execute(&app.db)
        .await
        .unwrap();
    }
    assert_eq!(
        app.policy(&token).await["lock_glance"],
        json!({
            "apps": [
                { "package": "com.a.chat", "show_text": false },
                { "package": ELEMENT_X, "show_text": false },
                { "package": "org.thoughtcrime.securesms", "show_text": true },
            ],
            "sms": "name",
        })
    );
    // Another phone's ticks are its own.
    let (_, other) = app.enrolled_device("other").await;
    assert_eq!(
        app.policy(&other).await["lock_glance"]["apps"],
        json!([{ "package": ELEMENT_X, "show_text": false }])
    );
}

#[tokio::test]
async fn an_unreadable_table_sends_no_apps_and_the_rest_of_the_policy() {
    let app = TestApp::new().await;
    let (id, token) = app.enrolled_device("phone").await;
    // Design 82 D3: Meldinger at Full text on a managed phone, so the fallback below is seen to take it away.
    sms_managed(&app, id).await;
    assert_eq!(
        show(&app, Some(&app.admin_cookie().await), id, MESSAGES, "text")
            .await
            .status,
        StatusCode::SEE_OTHER
    );
    let before = app.policy(&token).await;
    assert_eq!(before["lock_glance"]["sms"], "text");
    assert_eq!(before["call_policy"]["sms_lock_text"], true);
    // The trigger names the table, so it goes first.
    sqlx::query("DROP TRIGGER devices_get_lock_glance_default")
        .execute(&app.db)
        .await
        .unwrap();
    sqlx::query("DROP TABLE device_lock_glance_apps")
        .execute(&app.db)
        .await
        .unwrap();
    let after = app.policy(&token).await;
    // Fail closed for Meldinger too: `sms` is "off", never absent (a new launcher would read an absent one as an
    // older server's rule), and the derived old key is false.
    assert_eq!(after["lock_glance"], json!({ "apps": [], "sms": "off" }));
    assert_eq!(after["call_policy"]["sms_lock_text"], false);
    let strip = |mut v: Value| {
        v.as_object_mut().unwrap().remove("lock_glance");
        v["call_policy"]
            .as_object_mut()
            .unwrap()
            .remove("sms_lock_text");
        v
    };
    assert_eq!(strip(before), strip(after));
    // Lock Screen & PINs says so instead of showing every switch off.
    let cookie = app.admin_cookie().await;
    let page = app.get_page(&lock_page(id), &cookie).await;
    assert_eq!(page.status, StatusCode::OK);
    assert!(
        subsection(&page.text()).contains("The apps couldn&#39;t be read. Check the server log.")
    );
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
        // Its row (§5.1.4): without JS on the switch in a long list, with JS the exact place.
        super::assert_redirect(
            &res,
            &lock_page(id),
            Some("lock-screen-app-org.thoughtcrime.securesms"),
        );
        assert_eq!(nudges.try_recv().ok(), Some(id));
    }
    assert_eq!(
        ticks(&app, id).await,
        [ELEMENT_X, "org.thoughtcrime.securesms"]
    );
    assert_eq!(
        app.policy(&token).await["lock_glance"]["apps"],
        json!([
            { "package": ELEMENT_X, "show_text": false },
            { "package": "org.thoughtcrime.securesms", "show_text": false },
        ])
    );

    for _ in 0..2 {
        let res = tick(&app, Some(&cookie), id, ELEMENT_X, false).await;
        super::assert_redirect(
            &res,
            &lock_page(id),
            Some(&format!("lock-screen-app-{ELEMENT_X}")),
        );
        assert_eq!(nudges.try_recv().ok(), Some(id));
    }
    assert_eq!(ticks(&app, id).await, ["org.thoughtcrime.securesms"]);
    assert_eq!(
        events(&app, "lock_screen_app_changed").await,
        // One line per change, not per save (2026-10-21).
        [
            "phone {id}: org.thoughtcrime.securesms on the lock screen: on",
            "phone {id}: io.element.android.x on the lock screen: off",
        ]
        .map(|d| (
            d.replace("{id}", &id.to_string()),
            Some("parent".to_string())
        ))
    );
}

/// Design 51: a ticked app's text switch is its own; a new tick, and a re-tick, is sender only.
#[tokio::test]
async fn an_apps_text_switch_saves_logs_nudges_and_a_retick_resets_it() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("phone").await;
    let signal = "org.thoughtcrime.securesms";
    tick(&app, Some(&cookie), id, signal, true).await;
    let mut nudges = app.state.command_notify.subscribe();

    for on in [true, true, false, true] {
        let res = switch(&app, Some(&cookie), id, signal, on).await;
        assert_eq!(res.status, StatusCode::SEE_OTHER, "{}", res.text());
        // Back to the app's row, never the top (§5.1.4).
        super::assert_redirect(
            &res,
            &lock_page(id),
            Some(&format!("lock-screen-app-{signal}")),
        );
        assert_eq!(nudges.try_recv().ok(), Some(id));
        assert_eq!(
            texts(&app, id).await,
            [text_of(ELEMENT_X, false), text_of(signal, on)],
            "only this app's flag"
        );
    }
    assert_eq!(
        app.policy(&token).await["lock_glance"]["apps"],
        json!([
            { "package": ELEMENT_X, "show_text": false },
            { "package": signal, "show_text": true },
        ])
    );
    assert_eq!(
        events(&app, "lock_screen_text_changed").await,
        ["on", "off", "on"].map(|s| (
            format!("phone {id}: {signal}'s message text on the lock screen: {s}"),
            Some("parent".to_string())
        ))
    );

    // Off and Name only again: no text.
    tick(&app, Some(&cookie), id, signal, false).await;
    tick(&app, Some(&cookie), id, signal, true).await;
    assert_eq!(
        texts(&app, id).await,
        [text_of(ELEMENT_X, false), text_of(signal, false)]
    );
}

/// 2026-10-21: every move of the select - Off, Name only, Full text in each order - writes exactly
/// that (no row, a row without text, a row with text), lands on the app's row, nudges the phone
/// and logs what changed; the policy says the same.
#[tokio::test]
async fn each_move_of_the_select_is_stored_as_the_lock_shows_it() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("phone").await;
    let signal = "org.thoughtcrime.securesms";
    let mut nudges = app.state.command_notify.subscribe();
    let stored = |value: &str| match value {
        "off" => None,
        "name" => Some(false),
        _ => Some(true),
    };
    let mut before = "off";
    for value in ["text", "off", "name", "text", "name", "off", "text"] {
        let res = show(&app, Some(&cookie), id, signal, value).await;
        assert_eq!(res.status, StatusCode::SEE_OTHER, "{value}: {}", res.text());
        super::assert_redirect(
            &res,
            &lock_page(id),
            Some(&format!("lock-screen-app-{signal}")),
        );
        assert_eq!(nudges.try_recv().ok(), Some(id));
        let row = texts(&app, id)
            .await
            .into_iter()
            .find(|(p, _)| p == signal)
            .map(|(_, text)| text);
        assert_eq!(row, stored(value), "{before} -> {value}");
        let apps = app.policy(&token).await["lock_glance"]["apps"].clone();
        let listed = apps
            .as_array()
            .unwrap()
            .iter()
            .find(|a| a["package"] == signal)
            .map(|a| a["show_text"].as_bool().unwrap());
        assert_eq!(listed, stored(value), "the policy: {before} -> {value}");
        before = value;
    }
    let on_off = |lines: Vec<(String, Option<String>)>| -> Vec<String> {
        lines
            .into_iter()
            .map(|(d, _)| d.rsplit(": ").next().unwrap().to_string())
            .collect()
    };
    // off->text on, text->off off, off->name on, name->off off, off->text on.
    assert_eq!(
        on_off(events(&app, "lock_screen_app_changed").await),
        ["on", "off", "on", "off", "on"]
    );
    // off->text on, name->text on, text->name off, off->text on.
    assert_eq!(
        on_off(events(&app, "lock_screen_text_changed").await),
        ["on", "on", "off", "on"]
    );
    // A row listed only because it is on (qa-browser-fixes-code L1; qa-srv-sweep-resume-code
    // L6c): Name only or Full text keep its own row, Off lands on the group.
    for (value, landing) in [
        ("text", "lock-screen-app-com.old.chat"),
        ("name", "lock-screen-app-com.old.chat"),
        ("off", "notifications"),
    ] {
        let res = app
            .request_form(
                Method::POST,
                &format!("/devices/{id}/lock-screen/apps"),
                Some(&cookie),
                &[
                    ("package_name", "com.old.chat"),
                    ("unlisted", "1"),
                    ("show", value),
                ],
            )
            .await;
        super::assert_redirect(&res, &lock_page(id), Some(landing));
    }
    // Element X, untouched.
    assert!(texts(&app, id).await.contains(&text_of(ELEMENT_X, false)));
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
        switch(&app, Some(&cookie), 999, ELEMENT_X, true)
            .await
            .status,
        StatusCode::NOT_FOUND
    );
    // 400: a value the select doesn't have, or none.
    for bad in ["", "on", "Text", "all", "1"] {
        assert_eq!(
            show(&app, Some(&cookie), id, ELEMENT_X, bad).await.status,
            StatusCode::BAD_REQUEST,
            "{bad}"
        );
    }
    let res = app
        .request_form(
            Method::POST,
            &format!("/devices/{id}/lock-screen/apps"),
            Some(&cookie),
            &[("package_name", ELEMENT_X)],
        )
        .await;
    assert_eq!(res.status, StatusCode::BAD_REQUEST, "no value");
    // A Lock page from before the select (qa-srv-sweep-resume-code L6b): its switch posts `on`,
    // its text switch the removed route - both say to reload, nothing written.
    for (route, fields) in [
        (
            "lock-screen/apps",
            vec![("package_name", ELEMENT_X), ("on", "on")],
        ),
        (
            "lock-screen/apps/text",
            vec![("package_name", ELEMENT_X), ("show_text", "on")],
        ),
    ] {
        let res = app
            .request_form(
                Method::POST,
                &format!("/devices/{id}/{route}"),
                Some(&cookie),
                &fields,
            )
            .await;
        assert_eq!(res.status, StatusCode::BAD_REQUEST, "{route}");
        assert_eq!(
            res.text(),
            "This page is out of date - reload it.",
            "{route}"
        );
    }
    // 400: the text of something that isn't a package.
    assert_eq!(
        switch(&app, Some(&cookie), id, "not a package", true)
            .await
            .status,
        StatusCode::BAD_REQUEST
    );
    // No session: the login, and nothing written.
    let res = tick(&app, None, id, "com.a.chat", true).await;
    assert!(res.status.is_redirection());
    assert_ne!(res.location(), Some(lock_page(id).as_str()));
    let res = tick(&app, None, id, ELEMENT_X, false).await;
    assert!(res.status.is_redirection());
    let res = switch(&app, None, id, ELEMENT_X, true).await;
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
            "CREATE TRIGGER fail_switch BEFORE UPDATE ON device_lock_glance_apps BEGIN SELECT RAISE(ABORT, 'injected'); END",
            "DROP TRIGGER fail_switch",
        ),
    ] {
        sqlx::query(statement).execute(&app.db).await.unwrap();
        let res = if statement.contains("fail_tick") {
            tick(&app, Some(&cookie), id, "com.a.chat", true).await
        } else if statement.contains("fail_untick") {
            tick(&app, Some(&cookie), id, ELEMENT_X, false).await
        } else {
            switch(&app, Some(&cookie), id, ELEMENT_X, true).await
        };
        assert_eq!(res.status, StatusCode::INTERNAL_SERVER_ERROR, "{statement}");
        sqlx::query(undo).execute(&app.db).await.unwrap();
    }

    assert_eq!(texts(&app, id).await, [text_of(ELEMENT_X, false)]);
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

/// The form of `package` (its cell): its select and its note, if it is listed.
fn form_of(section: &str, package: &str) -> Option<String> {
    let id = section.find(&format!(r#"id="lock-screen-app-{package}""#))?;
    let start = section[..id].rfind("<form").unwrap();
    let rest = &section[start..];
    Some(rest[..rest.find("</form>").unwrap()].to_string())
}

/// The select's chosen value: `off`, `name` or `text` - exactly one is selected.
fn shown(form: &str) -> &'static str {
    let chosen: Vec<&'static str> = ["off", "name", "text"]
        .into_iter()
        .filter(|v| form.contains(&format!(r#"<option value="{v}" selected>"#)))
        .collect();
    assert_eq!(chosen.len(), 1, "{form}");
    chosen[0]
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
    assert!(section.contains("SMS login codes included"));
    assert!(section.contains(
        "swiped away there, as on Android&#39;s own lock screen; nothing can be answered"
    ));
    // The footer says what Name only is for a chat and for any other app (the launcher's
    // `glanceLevel`), and what Full text costs.
    assert!(
        section.contains(
            "Name only: the sender&#39;s name for chats, otherwise the app&#39;s name and how many."
        ),
        "{section}"
    );
    assert!(section.contains("which anyone holding the phone can read"));
    assert!(section.contains("Missed calls always show."));
    // No global switch (design 51), no per-app switches (2026-10-21).
    assert!(!section.contains("lock-screen-text"));
    assert!(!section.contains("/lock-screen/apps/text"));
    assert!(!section.contains("Show message text") && !section.contains("Sender's name only"));

    // One row per app: a select labelled by the app's name - Off, Name only, Full text.
    let element = form_of(section, ELEMENT_X).expect("Element X listed");
    assert_eq!(shown(&element), "name", "{element}");
    assert!(element.contains(&format!(
        r#"action="/devices/{id}/lock-screen/apps#lock-screen-app-{ELEMENT_X}""#
    )));
    assert!(element.contains(&format!(r#"name="package_name" value="{ELEMENT_X}""#)));
    let label = &element[element.find("<label").unwrap()..element.find("</label>").unwrap()];
    assert!(label.contains("Element X") && label.contains(r#"<select class="pick" name="show""#));
    for option in [
        r#"value="off""#,
        r#">Off</option>"#,
        r#">Name only</option>"#,
        r#">Full text</option>"#,
    ] {
        assert!(element.contains(option), "{option}");
    }
    assert!(super::autosaves(&element, "show"));
    assert!(element.contains("<noscript><button type=\"submit\""));
    assert!(!element.contains(r#"role="switch""#));
    let signal = form_of(section, "org.thoughtcrime.securesms").expect("Signal listed");
    assert_eq!(shown(&signal), "off");
    assert!(signal.contains("Signal"));
    // Not allowed: not listed - unless on, then with its note so it can be set Off.
    assert!(form_of(section, "com.example.game").is_none());
    assert!(form_of(section, "com.android.vending").is_none());
    let old = form_of(section, "com.old.chat").expect("the ticked, not allowed app");
    assert_eq!(shown(&old), "name");
    assert!(
        old.contains("<span class=\"row-sub\">Not allowed</span>"),
        "{old}"
    );

    // Full text shows as Full text.
    switch(&app, Some(&cookie), id, ELEMENT_X, true).await;
    let page = app.get_page(&lock_page(id), &cookie).await.text();
    let element = form_of(subsection(&page), ELEMENT_X).unwrap();
    assert_eq!(shown(&element), "text");
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
            ("me.vibb.launcher", "Vibb One"),
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
    assert_eq!(shown(&element), "name");
    assert!(element.contains("Not on the phone"), "{element}");
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
    assert!(!section.contains("hasn&#39;t reported its apps"));
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
    assert!(!html.contains("Vibb One can&#39;t show these yet"));
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
    assert!(html.contains("Vibb One can&#39;t show these yet"), "{html}");
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

/// Design 82 D7: Meldinger's row is the group's first and only while SMS is managed; every save returns to its own
/// cell (Off too - it stays listed), reaches the policy as `lock_glance.sms` with the derived `sms_lock_text`, and
/// is logged without the key.
#[tokio::test]
async fn meldingers_row_is_first_only_while_sms_is_managed_and_saves_in_place() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("phone").await;
    let page =
        || async { subsection(&app.get_page(&lock_page(id), &cookie).await.text()).to_string() };
    let anchor = format!("lock-screen-app-{MESSAGES}");

    // SMS not managed: no row, and a stored Full text goes out as the name.
    assert_eq!(form_of(&page().await, MESSAGES), None);
    assert_eq!(app.policy(&token).await["lock_glance"]["sms"], "name");

    sms_managed(&app, id).await;
    let html = page().await;
    let form = form_of(&html, MESSAGES).expect("Meldinger's row");
    assert!(form.contains("Messages"), "{form}");
    assert_eq!(shown(&form), "name");
    assert!(
        form.contains(&format!("/devices/{id}/lock-screen/apps#{anchor}")),
        "{form}"
    );
    assert!(!form.contains("unlisted"), "{form}");
    assert!(!form.contains("data-hint"), "{form}");
    // Before every app's row (Element X, listed for its tick).
    assert!(
        html.find(&format!(r#"id="{anchor}""#)) < html.find(r#"id="lock-screen-app-io.element"#),
        "{html}"
    );

    for (value, sms, text) in [
        ("off", "off", false),
        ("text", "text", true),
        ("name", "name", false),
    ] {
        let res = show(&app, Some(&cookie), id, MESSAGES, value).await;
        super::assert_redirect(&res, &lock_page(id), Some(&anchor));
        app.assert_lands(&res, &cookie).await;
        let policy = app.policy(&token).await;
        assert_eq!(policy["lock_glance"]["sms"], sms);
        assert_eq!(policy["call_policy"]["sms_lock_text"], text);
        assert_eq!(
            policy["lock_glance"]["apps"],
            json!([{ "package": ELEMENT_X, "show_text": false }])
        );
        assert_eq!(shown(&form_of(&page().await, MESSAGES).unwrap()), value);
    }
    assert_eq!(
        events(&app, "lock_screen_app_changed").await,
        ["off", "on"].map(|s| (
            format!("phone {id}: Messages on the lock screen: {s}"),
            Some("parent".to_string())
        ))
    );
    assert_eq!(
        events(&app, "lock_screen_text_changed").await,
        ["on", "off"].map(|s| (
            format!("phone {id}: Messages' text on the lock screen: {s}"),
            Some("parent".to_string())
        ))
    );
}

/// Design 82 D7: the row's own line for Off on a phone that can't hide the row yet, and the no-access note that
/// counts Meldinger while its row shows.
#[tokio::test]
async fn meldingers_row_says_what_an_older_phone_shows_and_the_access_note_counts_it() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("phone").await;
    let page =
        || async { subsection(&app.get_page(&lock_page(id), &cookie).await.text()).to_string() };
    sms_managed(&app, id).await;
    const HINT: &str = "This phone still shows the name and how many - update Vibb One";

    // A launcher from before `messages_app_v1`, without notification access.
    report(
        &app,
        &token,
        &[],
        json!({ "capabilities": ["lock_glance_v1"], "notification_listener_enabled": false }),
    )
    .await;
    let html = page().await;
    assert!(html.contains("Missed calls and Messages only"), "{html}");
    assert!(!form_of(&html, MESSAGES).unwrap().contains(HINT));

    show(&app, Some(&cookie), id, MESSAGES, "off").await;
    let html = page().await;
    assert!(html.contains("Missed calls only"), "{html}");
    assert!(!html.contains("Missed calls and Messages only"), "{html}");
    let form = form_of(&html, MESSAGES).unwrap();
    assert!(form.contains(HINT), "{form}");
    // Still its own cell: the hint is not the "unlisted" note.
    assert!(!form.contains("unlisted"), "{form}");

    // An up-to-date launcher hides the row at Off: no line.
    report(
        &app,
        &token,
        &[],
        json!({
            "capabilities": ["lock_glance_v1", crate::lock_glance::MESSAGES_APP_CAPABILITY],
            "notification_listener_enabled": true,
        }),
    )
    .await;
    assert!(!page().await.contains(HINT));
}

/// Design 82 D1: the prefix is reserved. A reported app under it is dropped at intake, and neither the allow switch
/// nor an app's permissions take a reserved name.
#[tokio::test]
async fn a_reserved_name_is_never_an_installed_app() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("phone").await;
    report(
        &app,
        &token,
        &[
            (MESSAGES, "Fake"),
            ("me.vibb.builtin.other", "Other"),
            ("com.a.chat", "Chat"),
        ],
        json!({}),
    )
    .await;
    let stored: String = sqlx::query_scalar(
        "SELECT installed_apps_json FROM device_status WHERE device_id = ? ORDER BY id DESC LIMIT 1",
    )
    .bind(id)
    .fetch_one(&app.db)
    .await
    .unwrap();
    let stored: Vec<crate::models::InstalledApp> = serde_json::from_str(&stored).unwrap();
    assert_eq!(
        stored
            .iter()
            .map(|a| a.package_name.as_str())
            .collect::<Vec<_>>(),
        ["com.a.chat"]
    );

    let allowlist = || async {
        sqlx::query_scalar::<_, Option<String>>(
            "SELECT allowlist_json FROM device_policy WHERE device_id = ?",
        )
        .bind(id)
        .fetch_one(&app.db)
        .await
        .unwrap()
    };
    let before = allowlist().await;
    for (path, fields) in [
        (
            "apps/toggle",
            &[("package_name", MESSAGES), ("selected", "on")][..],
        ),
        ("apps/permissions", &[("package_name", MESSAGES)][..]),
        (
            "apps/lock-media",
            &[("package_name", MESSAGES), ("on", "on")][..],
        ),
    ] {
        let res = app
            .request_form(
                Method::POST,
                &format!("/devices/{id}/{path}"),
                Some(&cookie),
                fields,
            )
            .await;
        assert_eq!(res.status, StatusCode::BAD_REQUEST, "{path}");
    }
    assert_eq!(allowlist().await, before);
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
    assert!(lock.contains("What the lock shows, anyone near the phone can see."));
}

/// qa-browser-fixes-code L1: a row listed only because it is ticked (here the default Element X on
/// a phone that hasn't reported) goes with its tick, so its untick lands on the group - an anchor
/// the answer still has -, not on the gone row; a listed app's switch keeps its row.
#[tokio::test]
async fn unticking_a_row_listed_only_for_its_tick_lands_on_the_group() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("phone").await;
    let html = app.get_page(&lock_page(id), &cookie).await.text();
    let (action, hidden) = super::settings_pages::forms(&html)
        .into_iter()
        .find(|(action, _)| action.contains("/lock-screen/apps"))
        .expect("Element X's row");
    assert_eq!(
        action,
        format!("/devices/{id}/lock-screen/apps#notifications")
    );
    let mut fields: Vec<(&str, &str)> = hidden
        .iter()
        .map(|(k, v)| (k.as_str(), v.as_str()))
        .collect();
    assert!(fields.contains(&("unlisted", "1")));
    fields.push(("show", "off"));
    let res = app
        .request_form(Method::POST, &action, Some(&cookie), &fields)
        .await;
    super::assert_redirect(&res, &lock_page(id), Some("notifications"));
    app.assert_lands(&res, &cookie).await;
    assert!(ticks(&app, id).await.is_empty());

    // A listed app (reported and allowed) keeps its row, ticked or not.
    report(
        &app,
        &token,
        &[("org.thoughtcrime.securesms", "Signal")],
        json!({}),
    )
    .await;
    sqlx::query("UPDATE device_policy SET allowlist_json = '[\"org.thoughtcrime.securesms\"]'")
        .execute(&app.db)
        .await
        .unwrap();
    for on in [true, false] {
        let res = tick(&app, Some(&cookie), id, "org.thoughtcrime.securesms", on).await;
        super::assert_redirect(
            &res,
            &lock_page(id),
            Some("lock-screen-app-org.thoughtcrime.securesms"),
        );
        app.assert_lands(&res, &cookie).await;
    }
}

/// Launcher round 7 §3: "Wake the screen for a notification" - off for a new phone, its own cell in
/// the Lock screen group, a save that lands on `#lock-wake`, nudges the phone, logs a line and reaches
/// the policy's `launcher_ui`; an unknown phone is a 404 with nothing written.
#[tokio::test]
async fn the_wake_switch_saves_logs_nudges_and_reaches_the_policy() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("phone").await;
    assert_eq!(
        app.policy(&token).await["launcher_ui"]["lock_wake_on_notification"],
        json!(false)
    );
    let html = app.get_page(&lock_page(id), &cookie).await.text();
    let group = &html[html.find(r#"<section id="lock-screen""#).unwrap()..];
    let group = &group[..group.find("</section>").unwrap()];
    let cell = &group[group.find(r#"id="lock-wake""#).expect("the cell")..];
    let cell = &cell[..cell.find("</form>").unwrap()];
    assert!(
        cell.contains("Wake the screen for a notification"),
        "{cell}"
    );
    assert!(
        cell.contains("For the apps on the lock below and missed calls"),
        "{cell}"
    );
    assert!(!cell.contains(" checked"), "off by default: {cell}");
    assert!(group.contains("anyone near the phone can see"), "{group}");

    let mut nudges = app.state.command_notify.subscribe();
    // The second "off" changes nothing: no log line (qa-l7-b-code 6).
    for on in [true, false, false, true] {
        let fields: &[(&str, &str)] = if on { &[("on", "on")] } else { &[] };
        let res = app
            .request_form(
                Method::POST,
                &format!("/devices/{id}/lock-wake"),
                Some(&cookie),
                fields,
            )
            .await;
        super::assert_redirect(&res, &lock_page(id), Some("lock-wake"));
        app.assert_lands(&res, &cookie).await;
        assert_eq!(nudges.try_recv().ok(), Some(id));
        assert_eq!(
            app.policy(&token).await["launcher_ui"]["lock_wake_on_notification"],
            json!(on)
        );
    }
    let html = app.get_page(&lock_page(id), &cookie).await.text();
    let cell = &html[html.find(r#"id="lock-wake""#).unwrap()..];
    assert!(cell[..cell.find("</form>").unwrap()].contains(" checked"));
    assert_eq!(
        events(&app, "lock_wake_changed").await,
        ["on", "off", "on"].map(|s| (
            format!("phone {id}: wake the screen for a notification: {s}"),
            Some("parent".to_string())
        ))
    );

    let res = app
        .request_form(
            Method::POST,
            "/devices/999/lock-wake",
            Some(&cookie),
            &[("on", "on")],
        )
        .await;
    assert_eq!(res.status, StatusCode::NOT_FOUND);
    assert_eq!(events(&app, "lock_wake_changed").await.len(), 3);
}
