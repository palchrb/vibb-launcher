//! Design 99 (Snarveier, the kid's quick panel in the shade): the shared contract `contracts/core/shortcuts.json` - the
//! report stored as the contract has it, the `clean` rows, the items' table - and the PWA's parts
//! (§5.2 the Home Screen row with its keep, sub-lines and ⓘ; §5.3 About This Phone's line), migration 0148's bit.

use axum::http::Method;
use serde_json::{Value, json};

use super::{TestApp, assert_redirect};
use crate::handlers::devices::{DEFAULT_QUICK_CONTROLS, QUICK_CONTROL_SHORTCUTS};
use crate::shortcuts::{ITEMS, MAX_ITEMS, clean};

const CONTRACT: &str = crate::contracts::contract!("core/shortcuts.json");

fn contract() -> Value {
    serde_json::from_str(CONTRACT).unwrap()
}

async fn stored(app: &TestApp, id: i64) -> Option<String> {
    sqlx::query_scalar(
        "SELECT shortcuts_json FROM device_status WHERE device_id = ? ORDER BY id DESC LIMIT 1",
    )
    .bind(id)
    .fetch_one(&app.state.db)
    .await
    .unwrap()
}

/// The report rows are stored exactly; a report without the key stores nothing.
#[tokio::test]
async fn the_report_is_stored_as_the_contract_has_it() {
    let app = TestApp::new().await;
    let (id, token) = app.enrolled_device("Emma").await;
    let contract = contract();
    assert_eq!(contract["bit"], QUICK_CONTROL_SHORTCUTS);
    for key in ["report", "report_hidden"] {
        let res = app
            .request(
                Method::POST,
                "/api/devices/status",
                Some(&token),
                Some(json!({
                    "lock_reason": "NONE",
                    "kiosk_engaged": true,
                    "shortcuts": contract[key],
                })),
            )
            .await;
        assert!(res.status.is_success(), "{} {}", res.status, res.text());
        let kept: Value = serde_json::from_str(&stored(&app, id).await.unwrap()).unwrap();
        assert_eq!(kept, contract[key], "{key}");
    }
    let res = app
        .request(
            Method::POST,
            "/api/devices/status",
            Some(&token),
            Some(json!({"lock_reason": "NONE", "kiosk_engaged": true})),
        )
        .await;
    assert!(res.status.is_success(), "{}", res.text());
    assert_eq!(stored(&app, id).await, None);
}

#[test]
fn the_clean_rows() {
    for row in contract()["clean"].as_array().unwrap() {
        assert_eq!(
            clean(&row["in"]).unwrap_or(Value::Null),
            row["out"],
            "{}",
            row["case"]
        );
    }
}

/// The server's table is the contract's `items` (key, gating bit, both names), in the default order (`defaults`), and
/// the panel's size and bit are the contract's.
#[test]
fn the_table_is_the_contracts() {
    let contract = contract();
    let items: Vec<Value> = ITEMS
        .iter()
        .map(|item| json!({"key": item.key, "bit": item.bit, "nb": item.nb, "en": item.en}))
        .collect();
    assert_eq!(Value::from(items), contract["items"]);
    let defaults: Vec<&str> = ITEMS.iter().map(|item| item.key).collect();
    assert_eq!(json!(defaults), contract["defaults"]);
    assert_eq!(contract["max_items"], MAX_ITEMS);
    assert_eq!(contract["bit"], 128);
    assert_ne!(DEFAULT_QUICK_CONTROLS & QUICK_CONTROL_SHORTCUTS, 0, "D2");
}

/// D2: migration 0148 sets bit 128 on every phone and keeps the other bits.
#[tokio::test]
async fn the_migration_sets_bit_128_on_every_phone() {
    let app = TestApp::new().await;
    let (emma, _) = app.create_device("Emma").await;
    let (jack, _) = app.create_device("Jack").await;
    sqlx::query("UPDATE device_policy SET quick_controls_mask = 1 | 64 WHERE device_id = ?")
        .bind(emma)
        .execute(&app.db)
        .await
        .unwrap();
    let update = include_str!("../../migrations/0148_shortcuts.sql")
        .lines()
        .find(|line| line.starts_with("UPDATE device_policy"))
        .unwrap();
    sqlx::query(update).execute(&app.db).await.unwrap();
    let mask = |id: i64| {
        sqlx::query_scalar::<_, i64>(
            "SELECT quick_controls_mask FROM device_policy WHERE device_id = ?",
        )
        .bind(id)
        .fetch_one(&app.db)
    };
    assert_eq!(mask(emma).await.unwrap(), 1 | 64 | 128);
    assert_eq!(mask(jack).await.unwrap(), 128);
}

// ---- The PWA (§5.2, §5.3) -----------------------------------------------------------------------------------------

async fn mask(app: &TestApp, token: &str) -> i64 {
    app.policy(token).await["quick_controls_mask"]
        .as_i64()
        .unwrap()
}

async fn set_mask(app: &TestApp, id: i64, mask: i64) {
    sqlx::query("UPDATE device_policy SET quick_controls_mask = ? WHERE device_id = ?")
        .bind(mask)
        .bind(id)
        .execute(&app.db)
        .await
        .unwrap();
}

/// A report with `shortcuts`.
async fn report(app: &TestApp, token: &str, shortcuts: Value) {
    app.post_status(token, json!({"shortcuts": shortcuts}))
        .await;
}

/// The Home Screen row's sub-line, if the page has one.
fn sub_line(html: &str) -> Option<&str> {
    let start = html.find("data-shortcuts-sub>")? + "data-shortcuts-sub>".len();
    Some(&html[start..start + html[start..].find("</span>")?])
}

/// §5.2: the Home Screen form always shows the Snarveier row - first - and takes bit 128 from its switch, keeping 32.
/// The ⓘ and the footer, in both languages; the page is a phone sub-page (no bell) and every save lands on
/// `#quick-controls`.
#[tokio::test]
async fn the_home_form_takes_bit_128_from_its_row() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("Emma").await;
    let home = format!("/devices/{id}/home");
    set_mask(&app, id, 1 | 32 | 64 | 128).await;
    let save = |fields: Vec<(&'static str, &'static str)>| {
        let (app, cookie) = (&app, cookie.clone());
        async move {
            app.request_form(
                Method::POST,
                &format!("/devices/{id}/policy"),
                Some(&cookie),
                &fields,
            )
            .await
        }
    };

    // A report without `shortcuts`: the row, no sub-line.
    app.post_status(&token, json!({})).await;
    let html = app.get_page(&home, &cookie).await.text();
    assert_eq!(
        super::input_checked(&html, "quick_control_shortcuts", "on"),
        Some(true)
    );
    assert_eq!(sub_line(&html), None);

    // A report with `shortcuts`: the row first, on, saving itself on #quick-controls; its sub-line, the ⓘ and the
    // footer.
    report(&app, &token, contract()["report"].clone()).await;
    let html = app.get_page(&home, &cookie).await.text();
    assert!(html.contains("Shortcuts in the notifications"), "{html}");
    let at = |name: &str| html.find(&format!("name=\"{name}\"")).unwrap();
    assert!(at("quick_control_shortcuts") < at("quick_control_airplane"));
    assert!(at("quick_control_shortcuts") < at("quick_control_wifi"));
    assert_eq!(
        super::input_checked(&html, "quick_control_shortcuts", "on"),
        Some(true)
    );
    assert!(super::autosaves(&html, "quick_control_shortcuts"));
    assert_eq!(
        super::form_action_of(&html, "quick_control_shortcuts"),
        Some(format!("/devices/{id}/policy#quick-controls").as_str())
    );
    assert_eq!(
        sub_line(&html),
        Some("Flashlight, Sound, Airplane mode, Wi-Fi")
    );
    assert!(html.contains("data-shortcuts-info"));
    assert!(html.contains(
        "Shortcuts is a fixed notification at the top of the notifications with up to four buttons. Emma picks them \
         from the switches here and the flashlight, and can hide and show them again under Settings › Shortcuts. It \
         never shows on the lock screen. When Emma has screen time today, it also shows the time left."
    ));
    assert!(html.contains("The switches apply to both Settings and Shortcuts."));
    assert!(!html.contains("title-bell"), "a phone sub-page has no bell");
    let nb = app
        .get_page(&format!("{home}?lang=nb"), &cookie)
        .await
        .text();
    assert!(nb.contains("Snarveier i varsellisten"), "{nb}");
    assert_eq!(sub_line(&nb), Some("Lommelykt, Lyd, Flymodus, Wi-Fi"));
    assert!(nb.contains(
        "Snarveier er et fast varsel øverst i varsellisten med inntil fire knapper. Emma velger dem blant bryterne \
         her og lommelykten, og kan skjule dem og vise dem igjen under Innstillinger › Snarveier. Det vises aldri på \
         låseskjermen. Når Emma har skjermtid i dag, viser den også tiden som er igjen."
    ));
    assert!(nb.contains("Bryterne gjelder både Innstillinger og Snarveier."));

    // The switch decides bit 128 (and Flymodus' 64, off in these saves); 32 is kept.
    let res = save(vec![("section", "quick"), ("quick_control_wifi", "on")]).await;
    assert_redirect(&res, &home, Some("quick-controls"));
    app.assert_lands(&res, &cookie).await;
    assert_eq!(mask(&app, &token).await, 1 | 32);
    let res = save(vec![
        ("section", "quick"),
        ("quick_control_wifi", "on"),
        ("quick_control_shortcuts", "on"),
    ])
    .await;
    assert!(res.status.is_redirection());
    assert_eq!(mask(&app, &token).await, 1 | 32 | 128);
    save(vec![("section", "quick"), ("quick_control_wifi", "on")]).await;
    assert_eq!(mask(&app, &token).await, 1 | 32);
    let res = save(vec![
        ("section", "quick"),
        ("quick_control_shortcuts", "on"),
    ])
    .await;
    assert_redirect(&res, &home, Some("quick-controls"));
    assert_eq!(mask(&app, &token).await, 32 | 128);
}

/// §5.2's sub-lines from the stored report: what the kid chose, that the kid hid it (the bit on or off), that no
/// switch under it is on (the bit on, no items), and nothing with the bit off and the panel not hidden; nb and en.
#[tokio::test]
async fn the_home_row_says_what_the_kid_did() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("Emma").await;
    let home = format!("/devices/{id}/home");
    let page = |lang: &'static str| {
        let (app, cookie, home) = (&app, cookie.clone(), home.clone());
        async move {
            let uri = if lang == "nb" {
                format!("{home}?lang=nb")
            } else {
                home
            };
            app.get_page(&uri, &cookie).await.text()
        }
    };
    set_mask(&app, id, 1 | 128).await;

    report(&app, &token, contract()["report_hidden"].clone()).await;
    let hidden_en = "Hidden by Emma";
    let hidden_nb = "Skjult av Emma";
    assert_eq!(sub_line(&page("en").await), Some(hidden_en));
    assert_eq!(sub_line(&page("nb").await), Some(hidden_nb));
    set_mask(&app, id, 1).await;
    let html = page("en").await;
    assert_eq!(
        sub_line(&html),
        Some(hidden_en),
        "hidden is said with the bit off too"
    );
    assert_eq!(
        super::input_checked(&html, "quick_control_shortcuts", "on"),
        Some(false)
    );

    // The bit off, not hidden: nothing to say.
    report(&app, &token, contract()["report"].clone()).await;
    assert_eq!(sub_line(&page("en").await), None);

    // The bit on, no items (nothing offered, or the kid chose none - C99-L3: true for both).
    set_mask(&app, id, 128).await;
    report(
        &app,
        &token,
        json!({"shown": false, "hidden": false, "items": []}),
    )
    .await;
    assert_eq!(sub_line(&page("en").await), Some("None"));
    assert_eq!(sub_line(&page("nb").await), Some("Ingen"));

    // A report that sent no `shortcuts` says nothing.
    app.post_status(&token, json!({})).await;
    assert_eq!(sub_line(&page("en").await), None);
}

/// The value of About This Phone's Snarveier row.
fn about_value(html: &str) -> Option<&str> {
    let row = &html[html.find("data-row=\"shortcuts\"")?..];
    let start = row.find("class=\"row-value\">")? + "class=\"row-value\">".len();
    Some(&row[start..start + row[start..].find("</span>")?])
}

/// §5.3: one line in About This Phone's list - the kid's items, "Hidden by Emma", or "Off" with bit 128 off - only
/// from a report with `shortcuts`; nb and en.
#[tokio::test]
async fn about_this_phone_says_the_shortcuts() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("Emma").await;
    let about = format!("/devices/{id}/about");
    let about_nb = format!("{about}?lang=nb");
    set_mask(&app, id, 128).await;

    app.post_status(&token, json!({"app_version": "0.40.0"}))
        .await;
    let html = app.get_page(&about, &cookie).await.text();
    assert_eq!(about_value(&html), None, "no `shortcuts`: no line");

    report(&app, &token, contract()["report"].clone()).await;
    let html = app.get_page(&about, &cookie).await.text();
    assert!(
        html.contains("<span class=\"row-label\">Shortcuts</span>"),
        "{html}"
    );
    assert_eq!(
        about_value(&html),
        Some("Flashlight, Sound, Airplane mode, Wi-Fi")
    );
    let nb = app.get_page(&about_nb, &cookie).await.text();
    assert!(
        nb.contains("<span class=\"row-label\">Snarveier</span>"),
        "{nb}"
    );
    assert_eq!(about_value(&nb), Some("Lommelykt, Lyd, Flymodus, Wi-Fi"));

    report(&app, &token, contract()["report_hidden"].clone()).await;
    let html = app.get_page(&about, &cookie).await.text();
    assert_eq!(about_value(&html), Some("Hidden by Emma"));
    let nb = app.get_page(&about_nb, &cookie).await.text();
    assert_eq!(about_value(&nb), Some("Skjult av Emma"));

    set_mask(&app, id, 1).await;
    let html = app.get_page(&about, &cookie).await.text();
    assert_eq!(about_value(&html), Some("Off"));
    let nb = app.get_page(&about_nb, &cookie).await.text();
    assert_eq!(about_value(&nb), Some("Av"));
}
