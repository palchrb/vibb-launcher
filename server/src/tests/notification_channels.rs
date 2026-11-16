//! Design 107 (blocked notification channels): the shared contract `testdata/notification_channels.json` - the report
//! stored as the contract has it, the copies identical - and the PWA's parts: About This Phone › Sound's
//! "Notifications" row (§5.2) and the summary's Attention row with the Devices list's badge (§5.3), every `pages` row.

use serde_json::{Value, json};

use super::{TestApp, tags};
use crate::notification_channels::CAPABILITY;

const CONTRACT: &str = include_str!("../../testdata/notification_channels.json");

fn contract() -> Value {
    serde_json::from_str(CONTRACT).unwrap()
}

async fn stored(app: &TestApp, id: i64) -> Option<String> {
    sqlx::query_scalar(
        "SELECT notification_channels_json FROM device_status WHERE device_id = ? ORDER BY id DESC LIMIT 1",
    )
    .bind(id)
    .fetch_one(&app.state.db)
    .await
    .unwrap()
}

/// The three report rows are stored exactly; a junk object is cleaned; an older launcher's report stores nothing.
#[tokio::test]
async fn the_report_is_stored_as_the_contract_has_it() {
    let app = TestApp::new().await;
    let (id, token) = app.enrolled_device("Emma").await;
    let contract = contract();
    assert_eq!(contract["capability"], CAPABILITY);
    for key in ["report", "report_none", "report_all_off"] {
        app.post_status(
            &token,
            json!({"capabilities": [CAPABILITY], "notification_channels": contract[key]}),
        )
        .await;
        let kept: Value = serde_json::from_str(&stored(&app, id).await.unwrap()).unwrap();
        assert_eq!(kept, contract[key], "{key}");
    }
    app.post_status(
        &token,
        json!({"capabilities": [CAPABILITY], "notification_channels": {"blocked": ["ring", "nope", "messages"], "x": 1}}),
    )
    .await;
    let kept: Value = serde_json::from_str(&stored(&app, id).await.unwrap()).unwrap();
    assert_eq!(
        kept,
        json!({"all_off": false, "blocked": ["messages", "ring"]})
    );
    app.post_status(&token, json!({})).await;
    assert_eq!(stored(&app, id).await, None);
}

#[test]
fn the_contract_is_identical_to_the_launchers_copy() {
    let launcher = std::fs::read_to_string(concat!(
        env!("CARGO_MANIFEST_DIR"),
        "/../launcher/app/src/test/resources/notification_channels.json"
    ))
    .unwrap();
    assert_eq!(launcher, CONTRACT, "the launcher's copy differs");
}

// ---- The PWA (§5.2, §5.3) -----------------------------------------------------------------------------------------

fn unescape(html: &str) -> String {
    html.replace("&#39;", "'")
        .replace("&#x27;", "'")
        .replace("&quot;", "\"")
        .replace("&amp;", "&")
}

/// About's Notifications row: its value and whether it is orange; `None` without the row.
fn about_row(html: &str) -> Option<(String, bool)> {
    let row = &html[html.find("data-row=\"notifications\"")?..];
    let start = row.find("<span class=\"row-value")?;
    let value = &row[start..];
    let orange = value[..value.find('>')?].contains("row-value-action");
    let text = &value[value.find('>')? + 1..];
    Some((unescape(&text[..text.find("</span>")?]), orange))
}

/// About's Notifications ⓘ, if it has one.
fn about_detail(html: &str) -> Option<String> {
    let row = &html[html.find("<details class=\"info cell\" data-row=\"notifications\">")?..];
    let row = &row[..row.find("</details>")?];
    let start = row.find("<div class=\"info-panel\"><p>")? + "<div class=\"info-panel\"><p>".len();
    Some(unescape(&row[start..start + row[start..].find("</p>")?]))
}

/// The summary's Attention row for the channels: its title.
fn attention_title(html: &str) -> Option<String> {
    let at = html.find("data-attention=\"notifications-off\"")?;
    let rest = &html[at..];
    let start =
        rest.find("<span class=\"attention-text\">")? + "<span class=\"attention-text\">".len();
    let text = &rest[start..];
    let end = text.find('<')?;
    Some(unescape(&text[..end]))
}

/// The `data-attention` keys of the summary's rows, in order.
fn attention_keys(html: &str) -> Vec<String> {
    tags(html, "a")
        .into_iter()
        .filter_map(|tag| tag.attr("data-attention").map(str::to_string))
        .collect()
}

/// The red badge's number on the Devices list's row for `id`; 0 without one.
fn badge(list: &str, id: i64) -> usize {
    let start = list.find(&format!("data-device=\"{id}\"")).unwrap();
    let row = &list[start..];
    let row = &row[..row.find("</a>").unwrap()];
    tags(row, "span")
        .into_iter()
        .find_map(|tag| tag.attr("data-attention-count"))
        .map_or(0, |n| n.parse().unwrap())
}

async fn set_policy(app: &TestApp, id: i64, sms: &str, calls: &str, language: &str, bit: bool) {
    sqlx::query(
        "UPDATE device_policy SET sms_mode = ?, calls_mode = ?, launcher_language = ?, \
         quick_controls_mask = CASE WHEN ? THEN quick_controls_mask | 128 ELSE quick_controls_mask & ~128 END \
         WHERE device_id = ?",
    )
    .bind(sms)
    .bind(calls)
    .bind(language)
    .bind(bit)
    .bind(id)
    .execute(&app.db)
    .await
    .unwrap();
}

/// Every `pages` row through About (en) and the summary's Attention: the value, its colour and the title (or no row).
#[tokio::test]
async fn the_pages_rows() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("Emma").await;
    for row in contract()["pages"].as_array().unwrap() {
        let case = row["case"].as_str().unwrap();
        set_policy(
            &app,
            id,
            row["sms_mode"].as_str().unwrap(),
            row["calls_mode"].as_str().unwrap(),
            row["language"].as_str().unwrap(),
            row["shortcuts_bit"].as_bool().unwrap(),
        )
        .await;
        app.post_status(
            &token,
            json!({"capabilities": [CAPABILITY], "notification_channels": row["report"]}),
        )
        .await;
        let about = app
            .get_page(&format!("/devices/{id}/about"), &cookie)
            .await
            .text();
        let (value, orange) = about_row(&about).unwrap_or_else(|| panic!("{case}: no row"));
        assert_eq!(value, row["value"].as_str().unwrap(), "{case}");
        assert_eq!(orange, row["orange"].as_bool().unwrap(), "{case}");
        let summary = app
            .get_page(&format!("/devices/{id}"), &cookie)
            .await
            .text();
        assert_eq!(
            attention_title(&summary).as_deref(),
            row["attention"].as_str(),
            "{case}"
        );
    }
}

/// The ⓘ: the lead, one sentence for each named channel with an effect, the fix; the anchor's sentence is the same with
/// bit 128 on or off (L1); "All on" has none. nb and en.
#[tokio::test]
async fn the_info_says_what_is_lost_and_how_to_fix_it() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("Emma").await;
    let about = format!("/devices/{id}/about");
    let fix_en = "To turn them on again: on the phone, open Vibb One's Settings (parent code), turn on “Pause all \
                  restrictions”, then tap “Vibb One notifications” on the same page. Turn on the ones named above, then \
                  turn the pause off again. If a switch is grey, first set Calls and SMS to “Not managed” under Calls \
                  and SMS, and set them back afterwards.";
    let anchor_en = "The “Vibb One” channel (time left): while the shortcuts aren't shown, the time left doesn't show \
                     in the notification shade either.";
    for bit in [true, false] {
        set_policy(&app, id, "managed", "managed", "system", bit).await;
        app.post_status(
            &token,
            json!({"capabilities": [CAPABILITY], "notification_channels": {"all_off": false, "blocked": ["command_listener", "shortcuts"]}}),
        )
        .await;
        let html = app.get_page(&about, &cookie).await.text();
        assert_eq!(
            about_detail(&html).unwrap(),
            format!(
                "These Vibb One notifications are turned off on the phone. {anchor_en} {fix_en}"
            ),
            "bit {bit}"
        );
    }

    app.post_status(
        &token,
        json!({"capabilities": [CAPABILITY], "notification_channels": {"all_off": false, "blocked": ["messages", "missed_calls", "app_install"]}}),
    )
    .await;
    let html = app.get_page(&about, &cookie).await.text();
    let detail = about_detail(&html).unwrap();
    assert!(detail.starts_with("These Vibb One notifications are turned off on the phone. “Messages”: new messages make no sound"), "{detail}");
    assert!(detail.contains(" “Missed calls”: a missed call doesn't show in the notification shade. The badge on the contact still shows. To turn"), "{detail}");
    assert!(
        !detail.contains("App installs"),
        "a minor channel has no sentence: {detail}"
    );
    let nb = app
        .get_page(&format!("{about}?lang=nb"), &cookie)
        .await
        .text();
    assert_eq!(
        about_row(&nb).unwrap(),
        (
            "Av: «Meldinger», «Tapte anrop», «Installering og oppdatering av apper»".to_string(),
            true
        )
    );
    assert!(
        nb.contains("<span class=\"row-label\">Varsler</span>"),
        "{nb}"
    );
    let detail = about_detail(&nb).unwrap();
    assert!(detail.starts_with("Disse varslene fra Vibb One er slått av på telefonen. «Meldinger»: nye meldinger gir ingen lyd"), "{detail}");
    assert!(
        detail.ends_with(
            "Slå på de som er nevnt over, og slå av pausen igjen. Er en bryter grå, sett først Samtaler og SMS til \
             «Ikke styrt» under Anrop og SMS, og sett dem tilbake etterpå."
        ),
        "{detail}"
    );
    let summary = app
        .get_page(&format!("/devices/{id}?lang=nb"), &cookie)
        .await
        .text();
    assert_eq!(
        attention_title(&summary).as_deref(),
        Some("Varsler slått av: Meldinger, Tapte anrop")
    );

    // All on: no ⓘ.
    app.post_status(
        &token,
        json!({"capabilities": [CAPABILITY], "notification_channels": contract()["report_none"]}),
    )
    .await;
    let html = app.get_page(&about, &cookie).await.text();
    assert_eq!(about_row(&html), Some(("All on".to_string(), false)));
    assert_eq!(about_detail(&html), None);
}

/// No false all-clear and no row from an older launcher: no capability, or the capability without an object (the
/// phone's read failed), shows nothing on About and raises nothing.
#[tokio::test]
async fn an_older_launcher_or_a_failed_read_has_no_row() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("Emma").await;
    set_policy(&app, id, "managed", "managed", "system", true).await;
    for body in [
        json!({"notification_channels": contract()["report_all_off"], "ringer_mode": "normal"}),
        json!({"capabilities": [CAPABILITY], "ringer_mode": "normal"}),
    ] {
        app.post_status(&token, body).await;
        let about = app
            .get_page(&format!("/devices/{id}/about"), &cookie)
            .await
            .text();
        assert!(about.contains("id=\"sound\""), "the group shows: {about}");
        assert_eq!(about_row(&about), None);
        let summary = app
            .get_page(&format!("/devices/{id}"), &cookie)
            .await
            .text();
        assert_eq!(attention_title(&summary), None);
    }
}

/// §5.3: the row sits right after Notification access off and before the calls rows, links to About's Sound group,
/// and the Devices list's badge counts it.
#[tokio::test]
async fn the_attention_row_has_its_place_and_counts() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("Emma").await;
    set_policy(&app, id, "managed", "managed", "system", true).await;
    app.post_status(
        &token,
        json!({"capabilities": [CAPABILITY], "notification_listener_enabled": false}),
    )
    .await;
    let before = attention_keys(
        &app.get_page(&format!("/devices/{id}"), &cookie)
            .await
            .text(),
    );
    let list = app.get_page("/devices", &cookie).await.text();
    assert_eq!(badge(&list, id), before.len());

    app.post_status(
        &token,
        json!({
            "capabilities": [CAPABILITY],
            "notification_listener_enabled": false,
            "notification_channels": contract()["report"],
        }),
    )
    .await;
    let summary = app
        .get_page(&format!("/devices/{id}"), &cookie)
        .await
        .text();
    let keys = attention_keys(&summary);
    let at = keys.iter().position(|k| k == "notifications-off").unwrap();
    assert_eq!(keys[at - 1], "notification-access", "{keys:?}");
    assert!(keys[at + 1..].iter().all(|k| k != "notification-access"));
    assert_eq!(keys.len(), before.len() + 1);
    assert!(summary.contains(&format!(
        "<a class=\"attention attention-action\" href=\"/devices/{id}/about#sound\" data-attention=\"notifications-off\">"
    )));
    let list = app.get_page("/devices", &cookie).await.text();
    assert_eq!(badge(&list, id), keys.len());
}
