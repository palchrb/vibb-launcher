//! Design 107 (blocked notification channels): the shared contract `contracts/core/notification_channels.json` - the report
//! stored as the contract has it - and the PWA's parts: About This Phone › Sound's
//! "Notifications" row (§5.2) and the summary's Attention row with the Devices list's badge (§5.3), every `pages` row.

use serde_json::{Value, json};

use super::{TestApp, tags};

const CONTRACT: &str = crate::contracts::contract!("core/notification_channels.json");

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

/// The three report rows are stored exactly; a junk object is cleaned; a report without the key stores nothing.
#[tokio::test]
async fn the_report_is_stored_as_the_contract_has_it() {
    let app = TestApp::new().await;
    let (id, token) = app.enrolled_device("Emma").await;
    let contract = contract();
    for key in ["report", "report_none", "report_all_off"] {
        app.post_status(&token, json!({"notification_channels": contract[key]}))
            .await;
        let kept: Value = serde_json::from_str(&stored(&app, id).await.unwrap()).unwrap();
        assert_eq!(kept, contract[key], "{key}");
    }
    app.post_status(
        &token,
        json!({"notification_channels": {"blocked": ["ring", "nope", "messages"], "x": 1}}),
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

/// The summary's setup row (design 114, which folds design 107's own row): its title.
fn setup_title(html: &str) -> Option<String> {
    let at = html.find("data-attention=\"setup\"")?;
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

/// Every `pages` row through About (en) and the summary: the value and its colour; an orange row is design 114's setup
/// step `vibb_notifications` - About's row links to it, the summary's setup row counts it, design 107's own row is gone.
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
        app.post_status(&token, json!({"notification_channels": row["report"]}))
            .await;
        let about = app
            .get_page(&format!("/devices/{id}/about"), &cookie)
            .await
            .text();
        let (value, orange) = about_row(&about).unwrap_or_else(|| panic!("{case}: no row"));
        assert_eq!(value, row["value"].as_str().unwrap(), "{case}");
        assert_eq!(orange, row["orange"].as_bool().unwrap(), "{case}");
        assert_eq!(
            about.contains(&format!(
                "<a class=\"row\" href=\"/devices/{id}/setup#step-vibb_notifications\" data-row=\"notifications\">"
            )),
            orange,
            "{case}"
        );
        let summary = app
            .get_page(&format!("/devices/{id}"), &cookie)
            .await
            .text();
        assert!(!summary.contains("notifications-off"), "{case}");
        assert_eq!(
            setup_title(&summary).is_some(),
            orange,
            "{case}: {:?}",
            setup_title(&summary)
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
            json!({"notification_channels": {"all_off": false, "blocked": ["command_listener", "shortcuts"]}}),
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
        json!({"notification_channels": {"all_off": false, "blocked": ["messages", "missed_calls", "app_install"]}}),
    )
    .await;
    // Design 114 §5.5: an orange row is a missing setup step - a link to it, the how on Device › Oppsett.
    let html = app.get_page(&about, &cookie).await.text();
    assert_eq!(about_detail(&html), None);
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
    let summary = app
        .get_page(&format!("/devices/{id}?lang=nb"), &cookie)
        .await
        .text();
    assert_eq!(
        setup_title(&summary).as_deref(),
        Some("Oppsett: 1 steg igjen")
    );

    // All on: no ⓘ.
    app.post_status(
        &token,
        json!({"notification_channels": contract()["report_none"]}),
    )
    .await;
    let html = app.get_page(&about, &cookie).await.text();
    assert_eq!(about_row(&html), Some(("All on".to_string(), false)));
    assert_eq!(about_detail(&html), None);
}

/// No false all-clear: a report without an object (the phone's read failed) shows nothing on About and raises nothing.
#[tokio::test]
async fn a_failed_read_has_no_row() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("Emma").await;
    set_policy(&app, id, "managed", "managed", "system", true).await;
    app.post_status(&token, json!({"ringer_mode": "normal"}))
        .await;
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
    assert_eq!(setup_title(&summary), None);
}

/// Design 114: the channels off and notification access off are two steps of one setup row, which the Devices list's
/// badge counts once; design 107's own row and the notification-access row are gone.
#[tokio::test]
async fn the_setup_row_counts_the_channels_once() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("Emma").await;
    set_policy(&app, id, "managed", "managed", "system", true).await;
    app.post_status(&token, json!({"notification_listener_enabled": false}))
        .await;
    let summary = app
        .get_page(&format!("/devices/{id}"), &cookie)
        .await
        .text();
    assert_eq!(setup_title(&summary).as_deref(), Some("Setup: 1 step left"));
    let before = attention_keys(&summary);
    let list = app.get_page("/devices", &cookie).await.text();
    assert_eq!(badge(&list, id), before.len());

    app.post_status(
        &token,
        json!({
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
    assert_eq!(keys, before, "one setup row for both");
    assert!(
        !keys
            .iter()
            .any(|k| k == "notifications-off" || k == "notification-access")
    );
    assert_eq!(
        setup_title(&summary).as_deref(),
        Some("Setup: 2 steps left")
    );
    assert!(summary.contains(&format!(
        "<a class=\"attention attention-action\" href=\"/devices/{id}/setup\" data-attention=\"setup\">"
    )));
    let list = app.get_page("/devices", &cookie).await.text();
    assert_eq!(badge(&list, id), keys.len());
}
