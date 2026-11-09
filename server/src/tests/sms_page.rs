//! Design 77 step 4, the PWA and the server's side of SMS: "Meldinger som venter" with each allow path's defaults
//! (only "Kan sende melding til", never an emergency number or a letterless name), "Slett nå" and "Slett alle som
//! venter", the sender names' rule, the SMS settings, the contact's "Meldinger" group, the `sms_held` event with its
//! push budget, the pools' finding - every form landing on its own group.

use axum::http::{Method, StatusCode};
use chrono::Utc;
use serde_json::json;

use super::web_push::{ENDPOINT, subscribe};
use super::{TestApp, input_checked};

/// A phone whose last report claims the engine, with SMS "Styrt av Vibb".
async fn managed_phone(app: &TestApp) -> (i64, String) {
    let (id, token) = app.enrolled_device("Anna").await;
    sqlx::query("UPDATE device_policy SET sms_mode = 'managed' WHERE device_id = ?")
        .bind(id)
        .execute(&app.db)
        .await
        .unwrap();
    app.post_status(
        &token,
        json!({ "capabilities": ["call_policy_v1", "sms_engine_v1"] }),
    )
    .await;
    (id, token)
}

/// A held row as the phone posts it, received `ago_min` minutes ago with its 24-hour window.
fn held_row(n: u32, class: &str, sender: &str, ago_min: i64) -> serde_json::Value {
    let at = Utc::now().timestamp_millis() - ago_min * 60_000;
    json!({
        "id": format!("{n:032x}"), "at": at, "kind": "sms", "sender_class": class, "sender": sender,
        "expires_at": at + 86_400_000, "state": "held",
    })
}

async fn post_held(app: &TestApp, token: &str, rows: Vec<serde_json::Value>) {
    let res = app
        .request(
            Method::POST,
            "/api/devices/sms-held",
            Some(token),
            Some(json!({ "rows": rows })),
        )
        .await;
    assert_eq!(res.status, StatusCode::OK, "{}", res.text());
}

/// A contact on the phone with its call flags off and `sms_in` as given.
async fn contact(app: &TestApp, id: i64, name: &str, number: &str, sms_in: bool) -> i64 {
    let contact: i64 =
        sqlx::query_scalar("INSERT INTO contacts (name, phone_number) VALUES (?, ?) RETURNING id")
            .bind(name)
            .bind(number)
            .fetch_one(&app.db)
            .await
            .unwrap();
    sqlx::query(
        "INSERT INTO device_contacts (device_id, contact_id, allow_inbound, allow_outbound, show_on_home, \
         sms_in, sms_out, mms) VALUES (?, ?, 1, 1, 0, ?, 0, 0)",
    )
    .bind(id)
    .bind(contact)
    .bind(sms_in)
    .execute(&app.db)
    .await
    .unwrap();
    contact
}

/// The part of the page that is `#held-<id>`'s row.
fn held_html(page: &str, n: u32) -> &str {
    let start = page
        .find(&format!("id=\"held-{n:032x}\""))
        .unwrap_or_else(|| panic!("no row {n}"));
    let rest = &page[start..];
    let end = rest[1..]
        .find("id=\"held-")
        .map(|end| end + 1)
        .or_else(|| rest.find("</section>"))
        .unwrap_or(rest.len());
    &rest[..end]
}

/// Each allow path grants exactly one thing (R2-M1) and never for an emergency number or a letterless name (R2-H1):
/// "Opprett kontakt…" opens Add Contact with the number and only "Kan sende melding til" on, and saves exactly that;
/// "Tillat meldinger fra" turns that switch alone on; "Tillat avsendernavnet" adds the name behind D17's warning;
/// "113" and an alphanumeric "+4791234567" offer only "Slett nå"; a row past its window by the server's clock offers
/// nothing. Every form lands on its row.
#[tokio::test]
async fn each_allow_path_grants_texts_in_alone() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = managed_phone(&app).await;
    let mamma = contact(&app, id, "Mamma", "+4790000001", false).await;
    post_held(
        &app,
        &token,
        vec![
            held_row(1, "number", "+4790000099", 5),
            held_row(2, "number", "+4790000001", 4),
            held_row(3, "alphanumeric", "DNB", 3),
            held_row(4, "short", "113", 2),
            held_row(5, "alphanumeric", "+4791234567", 1),
            // Received 25 hours ago: its window is over by the server's clock.
            held_row(6, "number", "+4790000098", 25 * 60),
        ],
    )
    .await;
    let page = app
        .get_page(&format!("/devices/{id}/calls"), &cookie)
        .await
        .text();
    assert!(page.contains("Messages Waiting"), "{page}");
    // A stranger's number: Create Contact, prefilled through the link's encoding.
    let stranger = held_html(&page, 1);
    assert!(stranger.contains("Number +4790000099"));
    assert!(stranger.contains(&format!(
        "href=\"/devices/{id}/calls?add_number=%2B4790000099#add-contact\""
    )));
    assert!(stranger.contains("Delete Now"));
    // A contact without texts in: Allow Texts from Mamma.
    assert!(held_html(&page, 2).contains("Allow Texts from Mamma"));
    // A sender name: behind a confirm that says the warning.
    let name = held_html(&page, 3);
    assert!(name.contains("Sender name «DNB»") && name.contains("Allow the Sender Name"));
    assert!(name.contains("A fake sender with the same name gets through too."));
    // An emergency number and a name that reads as a number: Delete Now alone, with why.
    for (n, why) in [
        (4, "Emergency numbers can&#39;t text here"),
        (5, "Pretends to be a number"),
    ] {
        let row = held_html(&page, n);
        assert!(row.contains(why), "{row}");
        assert!(
            !row.contains("/allow#") && !row.contains("add_number"),
            "{row}"
        );
        assert!(row.contains("Delete Now"));
    }
    let expired = held_html(&page, 6);
    assert!(
        expired.contains("Expired") && !expired.contains("<form"),
        "{expired}"
    );

    // Create Contact: Add Contact open with the number, only "Can text Anna" on.
    let add = app
        .get_page(
            &format!("/devices/{id}/calls?add_number=%2B4790000099"),
            &cookie,
        )
        .await
        .text();
    assert!(add.contains(r#"<details class="edit cell" id="add-contact" open>"#));
    assert!(add.contains(r#"value="+4790000099""#));
    for (switch, on) in [
        ("inbound", false),
        ("outbound", false),
        ("show_on_home", false),
        ("sms_in", true),
        ("sms_out", false),
        ("mms", false),
    ] {
        let checked = super::tags(&add, "input")
            .into_iter()
            .rfind(|tag| tag.attr("name") == Some(switch))
            .map(|tag| tag.has("checked"));
        assert_eq!(checked, Some(on), "{switch}");
    }
    let res = app
        .request_form(
            Method::POST,
            &format!("/devices/{id}/contacts"),
            Some(&cookie),
            &[
                ("name", "Trener"),
                ("number", "+4790000099"),
                ("switches", "on"),
                ("sms_in", "on"),
            ],
        )
        .await;
    assert!(res.status.is_redirection(), "{}", res.text());
    app.assert_lands(&res, &cookie).await;
    let flags: (bool, bool, bool, bool, bool, bool) = sqlx::query_as(
        "SELECT allow_inbound, allow_outbound, show_on_home, sms_in, sms_out, mms FROM device_contacts dc \
         JOIN contacts c ON c.id = dc.contact_id WHERE dc.device_id = ? AND c.phone_number = '+4790000099'",
    )
    .bind(id)
    .fetch_one(&app.db)
    .await
    .unwrap();
    assert_eq!(flags, (false, false, false, true, false, false));
    // A plain Add Contact (no switches field, as before) still gets every flag on.
    let res = app
        .request_form(
            Method::POST,
            &format!("/devices/{id}/contacts"),
            Some(&cookie),
            &[("name", "Pappa"), ("number", "+4790000002")],
        )
        .await;
    assert!(res.status.is_redirection());
    let flags: (bool, bool, bool) = sqlx::query_as(
        "SELECT sms_in, sms_out, mms FROM device_contacts dc JOIN contacts c ON c.id = dc.contact_id \
         WHERE dc.device_id = ? AND c.phone_number = '+4790000002'",
    )
    .bind(id)
    .fetch_one(&app.db)
    .await
    .unwrap();
    assert_eq!(flags, (true, true, true));

    // Allow Texts from Mamma: sms_in alone.
    let allow = |n: u32| format!("/devices/{id}/calls/held/{n:032x}/allow");
    let res = app
        .request_form(Method::POST, &allow(2), Some(&cookie), &[])
        .await;
    super::assert_redirect(
        &res,
        &format!("/devices/{id}/calls"),
        Some(&format!("held-{:032x}", 2)),
    );
    app.assert_lands(&res, &cookie).await;
    let flags: (bool, bool, bool, bool, bool, bool) = sqlx::query_as(
        "SELECT allow_inbound, allow_outbound, show_on_home, sms_in, sms_out, mms FROM device_contacts \
         WHERE device_id = ? AND contact_id = ?",
    )
    .bind(id)
    .bind(mamma)
    .fetch_one(&app.db)
    .await
    .unwrap();
    assert_eq!(flags, (true, true, false, true, false, false));
    let policy = app.policy(&token).await;
    let names = &policy["call_policy"]["sms_names"];
    assert_eq!(names, &json!([]));
    // Allow the sender name: on the list, in the policy.
    let res = app
        .request_form(Method::POST, &allow(3), Some(&cookie), &[])
        .await;
    app.assert_lands(&res, &cookie).await;
    assert_eq!(
        app.policy(&token).await["call_policy"]["sms_names"],
        json!(["DNB"])
    );
    // The row now says it's allowed, without the allow.
    let page = app
        .get_page(&format!("/devices/{id}/calls"), &cookie)
        .await
        .text();
    let name = held_html(&page, 3);
    assert!(name.contains("Allowed - delivered when the phone gets its rules"));
    assert!(!name.contains("/allow#"));
    // The emergency number, the letterless name and the expired row: refused, nothing written.
    for n in [4, 5, 6] {
        let res = app
            .request_form(Method::POST, &allow(n), Some(&cookie), &[])
            .await;
        assert!(res.status.is_redirection());
        assert_eq!(
            res.location().unwrap(),
            format!("/devices/{id}/calls?notice=held_gone#held")
        );
        app.assert_lands(&res, &cookie).await;
    }
    let names: i64 =
        sqlx::query_scalar("SELECT COUNT(*) FROM device_sms_names WHERE device_id = ?")
            .bind(id)
            .fetch_one(&app.db)
            .await
            .unwrap();
    assert_eq!(names, 1);
    assert_eq!(
        app.policy(&token).await["call_policy"]["contacts"]
            .as_array()
            .unwrap()
            .len(),
        3
    );
}

/// A held number's prefill goes through the form's escaping (R2-M2): `"<&` never reaches the page raw.
#[tokio::test]
async fn a_prefill_is_escaped() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, _) = app.enrolled_device("Anna").await;
    let page = app
        .get_page(
            &format!("/devices/{id}/calls?add_number=%22%3E%3Cb%3E%26x"),
            &cookie,
        )
        .await
        .text();
    assert!(!page.contains("\"><b>"), "{page}");
    assert!(
        page.contains("value=\"&#34;&#62;&#60;b&#62;&#38;x\""),
        "{page}"
    );
}

/// "Slett nå" and "Slett alle som venter" (D16, R2-M4): the ids go into the policy's `sms_held_drop`, the rows read
/// "Deleted" with no action, and each form lands on its own group.
#[tokio::test]
async fn delete_now_and_delete_everything_waiting() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = managed_phone(&app).await;
    post_held(
        &app,
        &token,
        (1..=3)
            .map(|n| held_row(n, "number", &format!("+479000009{n}"), 5))
            .collect(),
    )
    .await;
    let res = app
        .request_form(
            Method::POST,
            &format!("/devices/{id}/calls/held/{:032x}/drop", 1),
            Some(&cookie),
            &[],
        )
        .await;
    super::assert_redirect(
        &res,
        &format!("/devices/{id}/calls"),
        Some(&format!("held-{:032x}", 1)),
    );
    app.assert_lands(&res, &cookie).await;
    assert_eq!(
        app.policy(&token).await["call_policy"]["sms_held_drop"],
        json!([format!("{:032x}", 1)])
    );
    let page = app
        .get_page(&format!("/devices/{id}/calls"), &cookie)
        .await
        .text();
    let dropped = held_html(&page, 1);
    assert!(
        dropped.contains("Deleted") && !dropped.contains("<form"),
        "{dropped}"
    );
    assert!(page.contains("Delete the 2 messages waiting?"));
    let res = app
        .request_form(
            Method::POST,
            &format!("/devices/{id}/calls/held/drop-all"),
            Some(&cookie),
            &[],
        )
        .await;
    super::assert_redirect(&res, &format!("/devices/{id}/calls"), Some("held"));
    app.assert_lands(&res, &cookie).await;
    let mut drop: Vec<String> =
        serde_json::from_value(app.policy(&token).await["call_policy"]["sms_held_drop"].clone())
            .unwrap();
    drop.sort();
    assert_eq!(
        drop,
        (1..=3).map(|n| format!("{n:032x}")).collect::<Vec<_>>()
    );
    // Another phone's row is out of reach.
    let (other, _) = app.enrolled_device("Ola").await;
    let res = app
        .request_form(
            Method::POST,
            &format!("/devices/{other}/calls/held/{:032x}/drop", 2),
            Some(&cookie),
            &[],
        )
        .await;
    assert_eq!(
        res.location().unwrap(),
        format!("/devices/{other}/calls?notice=held_gone#held")
    );
}

/// D17's rule in the form: a letter and 1-11 characters after the shared cleaner, unique by the fold, at most 50;
/// a refusal renders in place (400) with what was typed; Remove by the fold. Every save lands on `#sms-names`.
#[tokio::test]
async fn the_sender_names_follow_the_rule() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("Anna").await;
    let add = |name: &'static str| {
        let (app, cookie) = (&app, &cookie);
        async move {
            app.request_form(
                Method::POST,
                &format!("/devices/{id}/calls/names"),
                Some(cookie),
                &[("name", name)],
            )
            .await
        }
    };
    let res = add(" Posten\u{202e} ").await;
    super::assert_redirect(&res, &format!("/devices/{id}/calls"), Some("sms-names"));
    app.assert_lands(&res, &cookie).await;
    for (name, why) in [
        ("POSTEN", "That name is on the list already."),
        ("+4791234567", "at least one letter"),
        ("112", "at least one letter"),
        ("Postkontoret", "1-11 characters"),
    ] {
        let res = add(name).await;
        assert_eq!(res.status, StatusCode::BAD_REQUEST, "{name}");
        let page = res.text();
        assert!(page.contains(why), "{name}: {page}");
        assert!(page.contains(r#"id="add-sms-name" open"#), "{name}");
    }
    assert_eq!(
        app.policy(&token).await["call_policy"]["sms_names"],
        json!(["Posten"])
    );
    let page = app
        .get_page(&format!("/devices/{id}/calls"), &cookie)
        .await
        .text();
    assert!(page.contains("A fake sender with the same name gets through too."));
    // The 50th name is the last.
    for n in 1..crate::handlers::calls_sms::NAMES_MAX {
        sqlx::query("INSERT INTO device_sms_names (device_id, name, fold) VALUES (?, ?, ?)")
            .bind(id)
            .bind(format!("N{n}"))
            .bind(format!("n{n}"))
            .execute(&app.db)
            .await
            .unwrap();
    }
    let res = add("Kiwi").await;
    assert_eq!(res.status, StatusCode::BAD_REQUEST);
    assert!(res.text().contains("The list has 50 names"));
    let res = app
        .request_form(
            Method::POST,
            &format!("/devices/{id}/calls/names/remove"),
            Some(&cookie),
            &[("fold", "posten")],
        )
        .await;
    app.assert_lands(&res, &cookie).await;
    assert!(
        !app.policy(&token).await["call_policy"]["sms_names"]
            .as_array()
            .unwrap()
            .contains(&json!("Posten"))
    );
}

/// The SMS settings form (D12, §6.8): the lock-text switch and the own numbers, normalised; a number that isn't one
/// is refused in place with what was sent; an empty field removes the number. Lands on `#sms-settings`.
#[tokio::test]
async fn the_sms_settings_save_in_place() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("Anna").await;
    let save = |fields: &'static [(&'static str, &'static str)]| {
        let (app, cookie) = (&app, &cookie);
        async move {
            app.request_form(
                Method::POST,
                &format!("/devices/{id}/calls/sms"),
                Some(cookie),
                fields,
            )
            .await
        }
    };
    let res = save(&[
        ("sms_lock_text", "on"),
        ("own_number_1", "900 00 001"),
        ("own_number_2", ""),
    ])
    .await;
    super::assert_redirect(&res, &format!("/devices/{id}/calls"), Some("sms-settings"));
    app.assert_lands(&res, &cookie).await;
    let policy = app.policy(&token).await["call_policy"].clone();
    assert_eq!(policy["sms_lock_text"], json!(true));
    assert_eq!(
        policy["own_numbers"],
        json!([{ "slot": 1, "number": "+4790000001" }])
    );
    let res = save(&[("own_number_1", "+4790000001"), ("own_number_2", "12ab")]).await;
    assert_eq!(res.status, StatusCode::BAD_REQUEST);
    let page = res.text();
    assert!(page.contains(r#"id="own_number_2-error""#), "{page}");
    assert!(page.contains(r#"value="12ab""#));
    assert_eq!(
        input_checked(&page, "sms_lock_text", "on"),
        Some(false),
        "what was sent"
    );
    let res = save(&[("own_number_1", ""), ("own_number_2", "")]).await;
    assert!(res.status.is_redirection());
    let policy = app.policy(&token).await["call_policy"].clone();
    assert_eq!(policy["sms_lock_text"], json!(false));
    assert_eq!(policy["own_numbers"], json!([]));
}

/// D3's "Meldinger" group: three switches saved with the call ones and shown in the row's summary; an emergency
/// number's "Kan sende melding til" is disabled with why and never stored on (R2-H1).
#[tokio::test]
async fn the_contacts_messages_group() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, _) = app.enrolled_device("Anna").await;
    let mamma = contact(&app, id, "Mamma", "+4790000001", false).await;
    let ambulance = contact(&app, id, "Ambulanse", "113", false).await;
    let save = |contact: i64, fields: &'static [(&'static str, &'static str)]| {
        let (app, cookie) = (&app, &cookie);
        async move {
            app.request_form(
                Method::POST,
                &format!("/devices/{id}/contacts/{contact}"),
                Some(cookie),
                fields,
            )
            .await
        }
    };
    let res = save(mamma, &[("inbound", "on"), ("sms_in", "on"), ("mms", "on")]).await;
    app.assert_lands(&res, &cookie).await;
    let res = save(ambulance, &[("inbound", "on"), ("sms_in", "on")]).await;
    assert!(res.status.is_redirection());
    let texts: Vec<(bool, bool, bool)> = sqlx::query_as(
        "SELECT sms_in, sms_out, mms FROM device_contacts WHERE device_id = ? ORDER BY contact_id",
    )
    .bind(id)
    .fetch_all(&app.db)
    .await
    .unwrap();
    assert_eq!(texts, vec![(true, false, true), (false, false, false)]);
    let page = app
        .get_page(&format!("/devices/{id}/calls"), &cookie)
        .await
        .text();
    assert!(
        page.contains(r#"Calls in only<span class="row-sub">Texts in</span>"#),
        "{page}"
    );
    let ambulance_row = &page[page.find(&format!("id=\"contact-{ambulance}\"")).unwrap()..];
    let ambulance_row = &ambulance_row[..ambulance_row.find("Remove Contact").unwrap()];
    assert!(ambulance_row.contains("Emergency numbers can&#39;t text here"));
    let switch = super::tags(ambulance_row, "input")
        .into_iter()
        .find(|tag| tag.attr("name") == Some("sms_in"))
        .unwrap();
    assert!(switch.has("disabled") && !switch.has("checked"));
}

/// QA R2-M3: the `sms_held` event is raised once per held row; within the budget (3 an hour, 1 a sender) it pushes,
/// the rest go to the inbox only and into one digest held until the hour's end, worded with the count; a row that
/// moves on marks its event read.
#[tokio::test]
async fn the_push_budget() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    subscribe(&app, &cookie, ENDPOINT).await;
    let (id, token) = managed_phone(&app).await;
    post_held(
        &app,
        &token,
        vec![
            held_row(1, "number", "+4790000091", 6),
            // The same sender again: past its per-sender budget.
            held_row(2, "number", "+4790000091", 5),
            held_row(3, "alphanumeric", "DNB", 4),
            held_row(4, "short", "2222", 3),
            // The hour's fourth sender: past the hourly budget.
            held_row(5, "alphanumeric", "Posten", 2),
            held_row(6, "number", "+4790000096", 1),
        ],
    )
    .await;
    // A re-post raises nothing more.
    post_held(&app, &token, vec![held_row(1, "number", "+4790000091", 6)]).await;
    let events: Vec<(String, Option<String>, Option<String>)> = sqlx::query_as(
        "SELECT dedup_key, push_budget_key, params FROM parent_events WHERE device_id = ? \
         AND kind = 'sms_held' ORDER BY id",
    )
    .bind(id)
    .fetch_all(&app.db)
    .await
    .unwrap();
    let keys: Vec<(&str, Option<&str>)> = events
        .iter()
        .map(|(key, budget, _)| (key.as_str(), budget.as_deref()))
        .collect();
    let id_of = |n: u32| format!("{n:032x}");
    let (one, two, three, four, five, six) =
        (id_of(1), id_of(2), id_of(3), id_of(4), id_of(5), id_of(6));
    assert_eq!(keys.len(), 7, "six rows and one digest: {keys:?}");
    assert_eq!(keys[0], (one.as_str(), Some("number:+4790000091")));
    assert_eq!(keys[1], (two.as_str(), None));
    assert!(keys[2].0.starts_with("digest:"));
    assert_eq!(keys[3], (three.as_str(), Some("name:DNB")));
    assert_eq!(keys[4], (four.as_str(), Some("number:2222")));
    assert_eq!(keys[5], (five.as_str(), None));
    assert_eq!(keys[6], (six.as_str(), None));
    let digest: serde_json::Value = serde_json::from_str(events[2].2.as_deref().unwrap()).unwrap();
    assert_eq!(digest["waiting"], 3);
    // Three pushes now; the digest waits for the hour's end.
    let held: Vec<(String, Option<String>)> = sqlx::query_as(
        "SELECT e.dedup_key, d.next_try_at FROM push_deliveries d JOIN parent_events e ON e.id = d.event_id \
         ORDER BY d.id",
    )
    .fetch_all(&app.db)
    .await
    .unwrap();
    assert_eq!(held.len(), 4, "{held:?}");
    for (key, at) in &held {
        assert_eq!(key.starts_with("digest:"), at.is_some(), "{held:?}");
    }
    crate::push::send_due(&app.state, Utc::now()).await.unwrap();
    let titles: Vec<String> = app
        .pushes
        .take()
        .iter()
        .map(|r| {
            super::web_push::payload(r)["notification"]["title"]
                .as_str()
                .unwrap()
                .to_string()
        })
        .collect();
    assert_eq!(
        titles,
        [
            "Anna: message from unknown number +4790000091",
            "Anna: message from sender name «DNB»",
            "Anna: message from unknown number 2222",
        ]
    );
    crate::push::send_due(&app.state, Utc::now() + chrono::Duration::minutes(61))
        .await
        .unwrap();
    let digest = app.pushes.take();
    assert_eq!(digest.len(), 1);
    assert_eq!(
        super::web_push::payload(&digest[0])["notification"]["title"],
        "Anna: 3 messages are waiting"
    );
    // Released: the event is read.
    let mut released = held_row(1, "number", "+4790000091", 6);
    released["state"] = "released".into();
    post_held(&app, &token, vec![released]).await;
    let read: bool = sqlx::query_scalar(
        "SELECT EXISTS(SELECT 1 FROM parent_event_reads r JOIN parent_events e ON e.id = r.event_id \
         WHERE e.dedup_key = ?)",
    )
    .bind(&one)
    .fetch_one(&app.db)
    .await
    .unwrap();
    assert!(read);
    let page = app
        .get_page(&format!("/devices/{id}/calls"), &cookie)
        .await
        .text();
    assert!(held_html(&page, 1).contains("Delivered"));
}

/// The findings (§7): a full name pool says until when, with the drop-all hint; the role not ours says messages are
/// lost; a SIM's other own number; the MMS rows and the parse failures are rows, not warnings. The device page's
/// row counts what waits and what was blocked this week.
#[tokio::test]
async fn the_pools_and_the_findings() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = managed_phone(&app).await;
    post_held(
        &app,
        &token,
        vec![
            held_row(1, "alphanumeric", "DNB", 30),
            held_row(2, "alphanumeric", "Posten", 20),
            json!({
                "id": format!("{:032x}", 3), "at": Utc::now().timestamp_millis() - 60_000, "kind": "sms",
                "sender_class": "malformed", "sender": "", "expires_at": Utc::now().timestamp_millis() - 60_000,
                "state": "blocked",
            }),
        ],
    )
    .await;
    sqlx::query(
        "INSERT INTO device_own_numbers (device_id, slot, number) VALUES (?, 1, '+4790000001')",
    )
    .bind(id)
    .execute(&app.db)
    .await
    .unwrap();
    app.post_status(
        &token,
        json!({
            "capabilities": ["call_policy_v1", "sms_engine_v1"],
            "sms_state": {
                "role_held": false, "mode_applied": "managed", "parse_failures": 2, "mmsc_known": false,
                "mms_data_allowed": true, "hold_full": { "numbers": false, "names": true },
                "own_numbers": [{ "slot": 1, "number": "+4790000002" }, { "slot": 2, "number": null }],
            },
        }),
    )
    .await;
    let page = app
        .get_page(&format!("/devices/{id}/calls"), &cookie)
        .await
        .text();
    for words in [
        "The waiting list is full",
        "New unknown senders aren't held until",
        "SMS app isn't Vibb One - messages lost",
        "Vibb's number doesn't match the SIM card",
        "SIM 1 says its number is +4790000002.",
        "2 messages couldn't be shown on the phone.",
        "MMS can't be checked on this SIM",
        "Blocked Messages",
        "A sender the phone couldn't read",
    ] {
        assert!(
            page.contains(&words.replace('\'', "&#39;")),
            "{words}: {page}"
        );
    }
    let warnings = page.matches("data-warning").count();
    assert_eq!(warnings, 3, "the pool, the role and the own number");
    let summary = app
        .get_page(&format!("/devices/{id}"), &cookie)
        .await
        .text();
    assert!(
        summary.contains("2 messages waiting · 1 blocked this week"),
        "{summary}"
    );
    // In Norwegian, the finding says the messages are lost.
    let nb = app
        .get_page(&format!("/devices/{id}/calls?lang=nb"), &cookie)
        .await
        .text();
    assert!(nb.contains("SMS-appen er ikke Vibb One - meldinger går tapt"));
    assert!(nb.contains("Ventelisten er full"));
}
