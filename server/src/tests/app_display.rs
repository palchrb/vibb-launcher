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
async fn report_installed(app: &TestApp, token: &str, packages: &[&str]) {
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
        Some(format!("/devices/{a}#app-{ELEMENT}").as_str())
    );
    assert_eq!(
        app_display(&app, &token_a).await,
        serde_json::json!([
            {"package_name": ELEMENT, "label": "Melding", "icon": null, "color": "auto"}
        ])
    );
    assert_eq!(app_display(&app, &token_b).await, chat);

    // "Use the app's own" on phone a: nothing for it there, even with the catalog default.
    save(
        &app,
        &cookie,
        a,
        &[("package_name", ELEMENT), ("action", "own")],
    )
    .await;
    assert_eq!(app_display(&app, &token_a).await, serde_json::json!([]));
    // "Follow the catalog": the phone's row goes.
    save(
        &app,
        &cookie,
        a,
        &[("package_name", ELEMENT), ("action", "catalog")],
    )
    .await;
    assert_eq!(app_display(&app, &token_a).await, chat);
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

    // The catalog page shows the default; "Use the app's own" there clears it.
    let page = app
        .get_page(&format!("/apps/tracked/{element}"), &cookie)
        .await
        .text();
    assert!(page.contains("Chat, with the chat icon on peach"), "{page}");
    assert!(page.contains(r#"value="chat" checked"#));
    app.request_form(
        Method::POST,
        &format!("/apps/tracked/{element}/display"),
        Some(&cookie),
        &[("action", "own"), ("label", "ignored")],
    )
    .await;
    assert_eq!(app_display(&app, &token_b).await, serde_json::json!([]));
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
        .get_page(&format!("/devices/{id}"), &cookie)
        .await
        .text();
    // The first report allowlists everything installed: both get a form.
    assert!(page.contains(r#"id="app-com.spotify.music""#), "{page}");
    assert!(page.contains(r#"id="app-org.example.clock""#));
    assert!(page.contains("/static/app-display.js"));

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
    let page = app
        .get_page(&format!("/devices/{id}"), &cookie)
        .await
        .text();
    assert!(
        page.contains("Musikk, with the music note icon on sky"),
        "{page}"
    );

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

/// Refused input: 400, nothing written, the device page with that app's form open, the entered
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
            "Pick one of the icons",
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
        assert!(
            html.contains(&format!(r#"id="app-{ELEMENT}" open"#)),
            "{message}"
        );
        assert!(html.contains("autofocus"), "{message}");
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
    assert!(html.contains("At most 20 characters") && html.contains(r#"id="display" open"#));
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
