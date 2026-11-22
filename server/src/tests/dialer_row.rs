//! Design 109 B: the system Phone app's row on Device › Apps. Calls managed: a link to the Calls page under Vibb One,
//! no switch, no Name and icon, no Permissions, and every post that would change it refused; calls unmanaged: the
//! switch as before with a line on what off costs, and the dialer back on the allowlist after a switch to unmanaged.

use super::*;

const DIALER: &str = "com.google.android.dialer";
const OTHER: &str = "org.example.chess";

/// A phone whose last report has the dialer (named as `system_dialer`) and another app installed, with the allowlist
/// and `calls_mode` as given (`None` allowlist: apps not managed), and Vibb One in the catalog.
async fn phone(
    app: &TestApp,
    allowlist: Option<&[&str]>,
    calls_mode: &str,
    report: bool,
) -> (i64, String) {
    let (id, token) = app.enrolled_device("Emma").await;
    sqlx::query(
        "INSERT OR IGNORE INTO tracked_apps (name, package_name, github_repo, is_launcher, release_tag_prefix) \
         VALUES ('Vibb One', 'me.vibb.launcher', 'palchrb/vibb-launcher', 1, 'launcher-v')",
    )
    .execute(&app.db)
    .await
    .unwrap();
    if report {
        app.post_status(
            &token,
            serde_json::json!({
                "installed_apps": [
                    {"package_name": DIALER, "label": "Phone", "preinstalled": true},
                    {"package_name": OTHER, "label": "Chess"},
                ],
                "call_state": {"system_dialer": DIALER},
            }),
        )
        .await;
    }
    set_policy(app, id, allowlist, calls_mode).await;
    (id, token)
}

async fn set_policy(app: &TestApp, id: i64, allowlist: Option<&[&str]>, calls_mode: &str) {
    sqlx::query("UPDATE device_policy SET allowlist_json = ?, calls_mode = ? WHERE device_id = ?")
        .bind(allowlist.map(|list| serde_json::to_string(list).unwrap()))
        .bind(calls_mode)
        .bind(id)
        .execute(&app.db)
        .await
        .unwrap();
}

async fn allowlist(app: &TestApp, id: i64) -> Option<String> {
    sqlx::query_scalar("SELECT allowlist_json FROM device_policy WHERE device_id = ?")
        .bind(id)
        .fetch_one(&app.db)
        .await
        .unwrap()
}

/// The `Device › Apps` page in `lang`.
async fn page(app: &TestApp, cookie: &str, id: i64, lang: &str, query: &str) -> String {
    let uri = format!("/devices/{id}/apps?lang={lang}{query}");
    let res = app.get_page(&uri, cookie).await;
    assert_eq!(res.status, StatusCode::OK, "{uri}");
    res.text()
}

/// The row's cell, `id="app-<package>"` to the next cell.
fn cell<'a>(html: &'a str, package: &str) -> &'a str {
    let start = html
        .find(&format!("id=\"app-{package}\""))
        .unwrap_or_else(|| panic!("no row for {package}"));
    let rest = &html[start..];
    let end = rest[1..]
        .find("class=\"cell\"")
        .map_or(rest.len(), |at| at + 1);
    &rest[..end]
}

fn position(html: &str, needle: &str) -> usize {
    html.find(needle)
        .unwrap_or_else(|| panic!("no {needle} on the page"))
}

#[tokio::test]
async fn the_managed_row_links_to_calls() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    // The dialer is missing from the allowlist: still not in Not allowed.
    let (id, token) = phone(&app, Some(&[OTHER]), "managed", true).await;
    // Kalender's built-in row is on the page too (and Meldinger's with SMS managed).
    app.post_status(
        &token,
        serde_json::json!({
            "installed_apps": [
                {"package_name": DIALER, "label": "Phone", "preinstalled": true},
                {"package_name": OTHER, "label": "Chess"},
            ],
            "call_state": {"system_dialer": DIALER},
        }),
    )
    .await;
    sqlx::query("UPDATE device_policy SET sms_mode = 'managed' WHERE device_id = ?")
        .bind(id)
        .execute(&app.db)
        .await
        .unwrap();
    sqlx::query(
        "INSERT INTO device_app_display (device_id, package_name, label) VALUES (?, ?, 'Ring')",
    )
    .bind(id)
    .bind(DIALER)
    .execute(&app.db)
    .await
    .unwrap();
    for (lang, label) in [
        ("en", "Phone → Vibb&#39;s phone book"),
        ("nb", "Phone → Vibbs telefonbok"),
    ] {
        let html = page(&app, &cookie, id, lang, "").await;
        let row = cell(&html, DIALER);
        assert!(
            row.contains(&format!("href=\"/devices/{id}/calls#settings\"")),
            "{row}"
        );
        assert!(
            row.contains(label) || row.contains(&label.replace("&#39;", "'")),
            "{lang}: {row}"
        );
        // The title says it (design 113): no sub-line, no value on the right, only the chevron.
        assert!(!row.contains("row-sub"), "{lang}: {row}");
        assert!(!row.contains("row-value"), "{lang}: {row}");
        assert!(row.contains("class=\"chev\""), "{lang}: {row}");
        assert!(!row.contains("name=\"selected\""), "no switch");
        assert!(!row.contains("name=\"package_name\""), "no form at all");
        assert!(!row.contains("/apps/display"), "no Name and icon");
        assert!(!row.contains("/apps/permissions"), "no Permissions");
        // Directly under Vibb One, in Allowed, never in Not allowed.
        let launcher = position(&html, "id=\"app-me.vibb.launcher\"");
        let dialer = position(&html, &format!("id=\"app-{DIALER}\""));
        let chess = position(&html, &format!("id=\"app-{OTHER}\""));
        assert!(launcher < dialer && dialer < chess, "{lang}");
        // Home's order: the phone book first, before the built-in Calendar and Meldinger rows.
        let calendar = position(&html, "id=\"app-calendar\"");
        assert!(dialer < calendar, "{lang}: before Kalender");
        if let Some(messages) = html.find("id=\"app-me.vibb.builtin.messages\"") {
            assert!(dialer < messages, "{lang}: before Meldinger");
        }
        let not_allowed = html.find("id=\"not-allowed\"");
        assert!(
            not_allowed.is_none_or(|at| dialer < at),
            "{lang}: not in Not allowed"
        );
    }
    // Off: the off sub-line and word.
    set_policy(&app, id, Some(&[OTHER]), "off").await;
    let html = page(&app, &cookie, id, "en", "").await;
    let row = cell(&html, DIALER);
    assert!(
        row.contains("<span class=\"row-sub\">Emergency numbers only</span>"),
        "{row}"
    );
    assert!(!row.contains("name=\"selected\""));
    let html = page(&app, &cookie, id, "nb", "").await;
    assert!(cell(&html, DIALER).contains("<span class=\"row-sub\">Bare nødnumre</span>"));
}

#[tokio::test]
async fn the_unmanaged_row_says_what_off_costs() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, _) = phone(&app, Some(&[OTHER]), "unmanaged", true).await;
    // Unticked on a phone with an allowlist: the switch, and what it costs.
    let html = page(&app, &cookie, id, "en", "").await;
    let row = cell(&html, DIALER);
    assert!(row.contains("name=\"selected\""), "the switch as today");
    assert!(row.contains("Off: only emergency calls work"), "{row}");
    assert!(!row.contains("text-alarm\" data-dialer-note"));
    let html = page(&app, &cookie, id, "nb", "").await;
    assert!(cell(&html, DIALER).contains("Av: bare nødanrop virker"));
    // A NULL allowlist (apps not managed): no line; a corrupt one neither.
    set_policy(&app, id, None, "unmanaged").await;
    let html = page(&app, &cookie, id, "en", "").await;
    assert!(!cell(&html, DIALER).contains("data-dialer-note"));
    sqlx::query("UPDATE device_policy SET allowlist_json = 'not json' WHERE device_id = ?")
        .bind(id)
        .execute(&app.db)
        .await
        .unwrap();
    let html = page(&app, &cookie, id, "en", "").await;
    assert!(!cell(&html, DIALER).contains("data-dialer-note"));
    // Ticked and on Home: neither; ticked with On Home off: the alarm line.
    set_policy(&app, id, Some(&[DIALER, OTHER]), "unmanaged").await;
    let html = page(&app, &cookie, id, "en", "").await;
    assert!(!cell(&html, DIALER).contains("data-dialer-note"));
    sqlx::query(
        "INSERT INTO device_app_display (device_id, package_name, on_home) VALUES (?, ?, 0)",
    )
    .bind(id)
    .bind(DIALER)
    .execute(&app.db)
    .await
    .unwrap();
    let html = page(&app, &cookie, id, "en", "").await;
    let row = cell(&html, DIALER);
    assert!(
        row.contains("Not on Home: the kid has no way to make a call"),
        "{row}"
    );
    assert!(row.contains("text-alarm"), "{row}");
    let html = page(&app, &cookie, id, "nb", "").await;
    assert!(cell(&html, DIALER).contains("Ikke på Hjem: barnet har ingen måte å ringe på"));
}

/// Every table a row form writes, for the phone: byte for byte.
async fn rows_of(app: &TestApp, id: i64) -> String {
    let mut out = format!("{:?}\n", allowlist(app, id).await);
    for query in [
        "SELECT json_group_array(json_array(package_name, label, icon_key, color_key, on_home, updated_at)) \
         FROM (SELECT * FROM device_app_display WHERE device_id = ?1 ORDER BY package_name)",
        "SELECT json_group_array(json_array(package_name, camera, updated_at)) \
         FROM (SELECT * FROM device_app_permissions WHERE device_id = ?1 ORDER BY package_name)",
        "SELECT json_group_array(json_array(package_name, updated_at)) \
         FROM (SELECT * FROM device_lock_media_apps WHERE device_id = ?1 ORDER BY package_name)",
    ] {
        let rows: String = sqlx::query_scalar(query)
            .bind(id)
            .fetch_one(&app.db)
            .await
            .unwrap();
        out.push_str(&rows);
        out.push('\n');
    }
    out
}

#[tokio::test]
async fn a_managed_dialer_is_never_changed() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, _) = phone(&app, Some(&[OTHER]), "managed", true).await;
    let before = rows_of(&app, id).await;
    let want = format!("/devices/{id}/apps?notice=dialer_managed&app=app-{DIALER}#app-{DIALER}");
    let long = "x".repeat(200);
    let posts: Vec<(&str, Vec<(&str, &str)>)> = vec![
        ("toggle", vec![("package_name", DIALER), ("selected", "on")]),
        ("toggle", vec![("package_name", DIALER)]),
        (
            "display",
            vec![
                ("package_name", DIALER),
                ("label", "Ring"),
                ("icon", ""),
                ("color", "auto"),
            ],
        ),
        (
            "display",
            vec![
                ("package_name", DIALER),
                ("label", &long),
                ("icon", ""),
                ("color", "auto"),
            ],
        ),
        (
            "display",
            vec![("package_name", DIALER), ("action", "reset")],
        ),
        (
            "permissions",
            vec![("package_name", DIALER), ("camera", "deny")],
        ),
        (
            "lock-media",
            vec![("package_name", DIALER), ("lock_media", "on")],
        ),
    ];
    for (route, fields) in &posts {
        let uri = match *route {
            "toggle" => format!("/devices/{id}/apps/toggle"),
            other => format!("/devices/{id}/apps/{other}"),
        };
        let res = app
            .request_form(Method::POST, &uri, Some(&cookie), fields)
            .await;
        assert_eq!(res.location(), Some(want.as_str()), "{route} {fields:?}");
    }
    assert_eq!(rows_of(&app, id).await, before, "nothing written");
    // Device > Music's switch (`back=music`) is guarded too, and answers on its own page.
    let res = app
        .request_form(
            Method::POST,
            &format!("/devices/{id}/apps/toggle"),
            Some(&cookie),
            &[("package_name", DIALER), ("back", "music")],
        )
        .await;
    assert_eq!(
        res.location(),
        Some(format!("/devices/{id}/music?music_notice=dialer_managed#music-app").as_str())
    );
    assert_eq!(
        rows_of(&app, id).await,
        before,
        "nothing written by back=music"
    );
    // A GET of the refusal URL shows the reason inside the row's cell, in both languages.
    for (lang, text) in [
        (
            "en",
            "Managed by Vibb&#39;s phone book - see Calls &amp; Contacts",
        ),
        ("nb", "Styrt av Vibbs telefonbok - se Anrop og kontakter"),
    ] {
        let html = page(
            &app,
            &cookie,
            id,
            lang,
            &format!("&notice=dialer_managed&app=app-{DIALER}"),
        )
        .await;
        let row = cell(&html, DIALER);
        assert!(
            row.contains(&text.replace("&amp;", "&#38;")),
            "{lang}: {row}"
        );
        assert!(row.contains("flash-error"), "{lang}");
    }
    // Another app still saves; with calls unmanaged the dialer's toggle saves as before.
    let res = app
        .request_form(
            Method::POST,
            &format!("/devices/{id}/apps/toggle"),
            Some(&cookie),
            &[("package_name", OTHER)],
        )
        .await;
    assert_eq!(
        res.location(),
        Some(format!("/devices/{id}/apps#app-{OTHER}").as_str())
    );
    assert_eq!(allowlist(&app, id).await.as_deref(), Some("[]"));
    let res = app
        .request_form(
            Method::POST,
            &format!("/devices/{id}/apps/display"),
            Some(&cookie),
            &[
                ("package_name", OTHER),
                ("label", "Sjakk"),
                ("icon", ""),
                ("color", "auto"),
            ],
        )
        .await;
    // Design 127: a name lands on the app's own page.
    assert_eq!(
        res.location(),
        Some(format!("/devices/{id}/apps/{OTHER}#display").as_str())
    );
    set_policy(&app, id, Some(&[]), "unmanaged").await;
    let res = app
        .request_form(
            Method::POST,
            &format!("/devices/{id}/apps/toggle"),
            Some(&cookie),
            &[("package_name", DIALER), ("selected", "on")],
        )
        .await;
    assert_eq!(
        res.location(),
        Some(format!("/devices/{id}/apps#app-{DIALER}").as_str())
    );
    assert_eq!(allowlist(&app, id).await, Some(format!("[\"{DIALER}\"]")));
}

async fn calls_save(app: &TestApp, cookie: &str, id: i64, calls_mode: &str) {
    let res = app
        .request_form(
            Method::POST,
            &format!("/devices/{id}/calls/settings"),
            Some(cookie),
            &[
                ("section", "channels"),
                ("calls_mode", calls_mode),
                ("sms_mode", "unmanaged"),
            ],
        )
        .await;
    assert_eq!(
        res.location(),
        Some(format!("/devices/{id}/calls#settings").as_str()),
        "{}",
        res.text()
    );
}

#[tokio::test]
async fn unmanaged_again_puts_the_dialer_back() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, _) = phone(&app, Some(&[OTHER]), "managed", true).await;
    let with = format!("[\"{OTHER}\",\"{DIALER}\"]");
    // managed -> unmanaged adds it once.
    calls_save(&app, &cookie, id, "unmanaged").await;
    assert_eq!(allowlist(&app, id).await, Some(with.clone()));
    calls_save(&app, &cookie, id, "unmanaged").await;
    assert_eq!(allowlist(&app, id).await, Some(with.clone()), "once");
    // off -> unmanaged adds it too.
    set_policy(&app, id, Some(&[OTHER]), "off").await;
    calls_save(&app, &cookie, id, "unmanaged").await;
    assert_eq!(allowlist(&app, id).await, Some(with.clone()));
    // unmanaged -> unmanaged and managed -> off do not.
    set_policy(&app, id, Some(&[OTHER]), "unmanaged").await;
    calls_save(&app, &cookie, id, "unmanaged").await;
    assert_eq!(
        allowlist(&app, id).await.as_deref(),
        Some("[\"org.example.chess\"]")
    );
    set_policy(&app, id, Some(&[OTHER]), "managed").await;
    calls_save(&app, &cookie, id, "off").await;
    assert_eq!(
        allowlist(&app, id).await.as_deref(),
        Some("[\"org.example.chess\"]")
    );
    // A NULL allowlist stays NULL (apps not managed); an unreadable one stays as it is.
    set_policy(&app, id, None, "managed").await;
    calls_save(&app, &cookie, id, "unmanaged").await;
    assert_eq!(allowlist(&app, id).await, None);
    sqlx::query("UPDATE device_policy SET allowlist_json = 'not json', calls_mode = 'managed' WHERE device_id = ?")
        .bind(id)
        .execute(&app.db)
        .await
        .unwrap();
    calls_save(&app, &cookie, id, "unmanaged").await;
    assert_eq!(allowlist(&app, id).await.as_deref(), Some("not json"));
    let mode: String =
        sqlx::query_scalar("SELECT calls_mode FROM device_policy WHERE device_id = ?")
            .bind(id)
            .fetch_one(&app.db)
            .await
            .unwrap();
    assert_eq!(mode, "unmanaged", "the mode save went through");
    // No report: the allowlist is as it was.
    let (quiet, _) = phone(&app, Some(&[OTHER]), "managed", false).await;
    calls_save(&app, &cookie, quiet, "unmanaged").await;
    assert_eq!(
        allowlist(&app, quiet).await.as_deref(),
        Some("[\"org.example.chess\"]")
    );
}

/// A report whose `call_state` names no `system_dialer`: nothing is the dialer, so the Phone app is an app like any
/// other even while calls are managed - a plain switch row, and its toggle saves.
#[tokio::test]
async fn without_a_reported_dialer_the_phone_app_is_a_plain_row() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("Jack").await;
    app.post_status(
        &token,
        serde_json::json!({
            "installed_apps": [{"package_name": DIALER, "label": "Phone", "preinstalled": true}],
            "call_state": {},
        }),
    )
    .await;
    set_policy(&app, id, Some(&[]), "managed").await;
    let html = page(&app, &cookie, id, "en", "").await;
    let row = cell(&html, DIALER);
    assert!(row.contains("name=\"selected\""), "a plain switch: {row}");
    assert!(!row.contains("Vibb&#39;s phone book"), "{row}");
    let res = app
        .request_form(
            Method::POST,
            &format!("/devices/{id}/apps/toggle"),
            Some(&cookie),
            &[("package_name", DIALER), ("selected", "on")],
        )
        .await;
    assert_eq!(
        res.location(),
        Some(format!("/devices/{id}/apps#app-{DIALER}").as_str())
    );
    assert_eq!(
        allowlist(&app, id).await.as_deref(),
        Some(format!("[\"{DIALER}\"]").as_str())
    );
}
