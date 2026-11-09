//! Design 80 steps 1 and 2 (`docs/design/80-calls-contacts-simpler.md`): a contact's four plain choices and its own
//! Avansert (D1-D4, D6), Add Contact's three ways in (D5), the page's order and its Avansert (D7, D8), the split
//! `/calls/settings` and the per-slot `/calls/sms`, the phone's own number by its source (§4.5) and where every save
//! returns (D9).

use axum::http::{Method, StatusCode};
use chrono::Utc;
use serde_json::json;

use super::settings_pages::part;
use super::{TestApp, assert_redirect, input_checked, tags};

/// A phone whose last report claims the engine, with both channels "Styrt av Vibb".
async fn managed_phone(app: &TestApp) -> (i64, String) {
    let (id, token) = app.enrolled_device("Anna").await;
    sqlx::query(
        "UPDATE device_policy SET calls_mode = 'managed', sms_mode = 'managed' WHERE device_id = ?",
    )
    .bind(id)
    .execute(&app.db)
    .await
    .unwrap();
    report(app, &token, json!({})).await;
    (id, token)
}

/// A status report with the engine and this `sms_state`.
async fn report(app: &TestApp, token: &str, sms_state: serde_json::Value) {
    app.post_status(
        token,
        json!({ "capabilities": ["call_policy_v1", "sms_engine_v1"], "sms_state": sms_state }),
    )
    .await;
}

/// A contact on the phone with its six flags `[inbound, outbound, home, sms_in, sms_out, mms]`.
async fn contact(app: &TestApp, id: i64, name: &str, number: &str, flags: [bool; 6]) -> i64 {
    let contact: i64 = sqlx::query_scalar(
        "INSERT INTO contacts (name, phone_number) VALUES (?, ?) \
         ON CONFLICT(phone_number) DO UPDATE SET name = excluded.name RETURNING id",
    )
    .bind(name)
    .bind(number)
    .fetch_one(&app.db)
    .await
    .unwrap();
    let [inbound, outbound, home, sms_in, sms_out, mms] = flags;
    sqlx::query(
        "INSERT INTO device_contacts (device_id, contact_id, allow_inbound, allow_outbound, show_on_home, \
         sms_in, sms_out, mms) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
    )
    .bind(id)
    .bind(contact)
    .bind(inbound)
    .bind(outbound)
    .bind(home)
    .bind(sms_in)
    .bind(sms_out)
    .bind(mms)
    .execute(&app.db)
    .await
    .unwrap();
    contact
}

/// The stored `[inbound, outbound, home, sms_in, sms_out, mms]`.
async fn flags(app: &TestApp, id: i64, contact: i64) -> [bool; 6] {
    let row: (bool, bool, bool, bool, bool, bool) = sqlx::query_as(
        "SELECT allow_inbound, allow_outbound, show_on_home, sms_in, sms_out, mms FROM device_contacts \
         WHERE device_id = ? AND contact_id = ?",
    )
    .bind(id)
    .bind(contact)
    .fetch_one(&app.db)
    .await
    .unwrap();
    [row.0, row.1, row.2, row.3, row.4, row.5]
}

/// What a browser would post from the form whose action starts with `action`: its hidden fields, the checked and
/// enabled switches, the text fields and the selected options.
fn fields_of(html: &str, action: &str) -> Vec<(String, String)> {
    let form = tags(html, "form")
        .into_iter()
        .find(|form| form.attr("action").is_some_and(|a| a.starts_with(action)))
        .unwrap_or_else(|| panic!("no form {action}"));
    let body = &html[form.start..];
    let body = &body[..body.find("</form>").unwrap()];
    let mut fields = Vec::new();
    for input in tags(body, "input") {
        let (Some(name), kind) = (input.attr("name"), input.attr("type").unwrap_or("text")) else {
            continue;
        };
        let value = input.attr("value").unwrap_or("").to_string();
        match kind {
            "checkbox" | "radio" if input.has("checked") && !input.has("disabled") => {
                fields.push((name.to_string(), value))
            }
            "checkbox" | "radio" | "file" => {}
            _ => fields.push((name.to_string(), value)),
        }
    }
    for select in tags(body, "select") {
        let name = select.attr("name").unwrap().to_string();
        let options = &body[select.start..];
        let options = &options[..options.find("</select>").unwrap()];
        let chosen = tags(options, "option")
            .into_iter()
            .find(|o| o.has("selected"))
            .and_then(|o| o.attr("value"))
            .unwrap_or("");
        fields.push((name, chosen.to_string()));
    }
    fields
}

/// `fields` with `name` set to `value` (`None`: left out, as an unticked switch).
fn with(
    mut fields: Vec<(String, String)>,
    name: &str,
    value: Option<&str>,
) -> Vec<(String, String)> {
    fields.retain(|(k, _)| k != name);
    if let Some(value) = value {
        fields.push((name.to_string(), value.to_string()));
    }
    fields
}

async fn post(
    app: &TestApp,
    cookie: &str,
    uri: &str,
    fields: &[(String, String)],
) -> super::TestResponse {
    let pairs: Vec<(&str, &str)> = fields
        .iter()
        .map(|(k, v)| (k.as_str(), v.as_str()))
        .collect();
    app.request_form(Method::POST, uri, Some(cookie), &pairs)
        .await
}

async fn page(app: &TestApp, cookie: &str, uri: &str) -> String {
    let res = app.get_page(uri, cookie).await;
    assert_eq!(res.status, StatusCode::OK, "{uri}");
    res.text().replace("&#39;", "'")
}

/// The contact's part of the page.
fn contact_html(html: &str, contact: i64) -> &str {
    part(html, &format!("id=\"contact-{contact}\""), "Remove Contact")
}

/// D3 through the contact's form, as autosave posts it: a two-way switch sets both flags, a one-way change under
/// "on" is taken, a split channel is "Tilpasset" and kept when another field saves, a stale page never undoes a
/// split (L1), `custom` and an older page take the pair as posted, and `more` brings the Avansert back (D9).
#[tokio::test]
async fn a_contacts_two_way_switches_set_both_flags() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, _) = managed_phone(&app).await;
    let c = contact(&app, id, "Mamma", "+4790000001", [true; 6]).await;
    let calls = format!("/devices/{id}/calls?open={c}");
    let action = format!("/devices/{id}/contacts/{c}");
    let html = page(&app, &cookie, &calls).await;
    let row = contact_html(&html, c);
    assert!(
        row.contains(">Calls and texts<span class=\"row-sub\">Home</span>"),
        "{row}"
    );
    assert_eq!(input_checked(row, "calls", "on"), Some(true));
    assert_eq!(input_checked(row, "texts", "on"), Some(true));
    assert!(
        row.contains("<details class=\"adv\" data-adv>"),
        "Avansert closed: {row}"
    );
    assert!(
        !row.contains("name=\"message_address\""),
        "no Matrix ID while unused"
    );

    // The calls switch turned off: both off; texts untouched.
    let res = post(
        &app,
        &cookie,
        &action,
        &with(fields_of(&html, &action), "calls", None),
    )
    .await;
    assert_redirect(
        &res,
        &format!("/devices/{id}/calls?open={c}"),
        Some(&format!("contact-{c}")),
    );
    assert_eq!(
        flags(&app, id, c).await,
        [false, false, true, true, true, true]
    );
    // On again: both on.
    let html = page(&app, &cookie, &calls).await;
    post(
        &app,
        &cookie,
        &action,
        &with(fields_of(&html, &action), "calls", Some("on")),
    )
    .await;
    assert_eq!(flags(&app, id, c).await, [true; 6]);
    // A one-way change in Avansert under "on": taken. The page then shows "Tilpasset", Avansert open, the row's
    // direction.
    let html = page(&app, &cookie, &calls).await;
    post(
        &app,
        &cookie,
        &action,
        &with(fields_of(&html, &action), "inbound", None),
    )
    .await;
    assert_eq!(
        flags(&app, id, c).await,
        [false, true, true, true, true, true]
    );
    let html = page(&app, &cookie, &calls).await;
    let row = contact_html(&html, c);
    assert!(
        !row.contains("name=\"calls\""),
        "no half-true switch: {row}"
    );
    assert!(row.contains(
        r#"<span class="row-label">Calls both ways</span><span class="row-value">Custom</span>"#
    ));
    assert!(
        row.contains("<details class=\"adv\" data-adv open>"),
        "{row}"
    );
    assert!(
        row.contains(">Calls out<span class=\"row-sub\">Texts both ways · Home</span>"),
        "{row}"
    );
    assert!(!row.contains("Only "), "no \"Bare …\" line: {row}");
    // Pictures saved: the split is kept.
    post(
        &app,
        &cookie,
        &action,
        &with(fields_of(&html, &action), "mms", None),
    )
    .await;
    assert_eq!(
        flags(&app, id, c).await,
        [false, true, true, true, true, false]
    );

    // L1: a page drawn "on" (another tab), then a split made elsewhere: saving Home keeps the split.
    sqlx::query("UPDATE device_contacts SET allow_inbound = 1 WHERE contact_id = ?")
        .bind(c)
        .execute(&app.db)
        .await
        .unwrap();
    let stale = page(&app, &cookie, &calls).await;
    sqlx::query("UPDATE device_contacts SET allow_outbound = 0 WHERE contact_id = ?")
        .bind(c)
        .execute(&app.db)
        .await
        .unwrap();
    post(
        &app,
        &cookie,
        &action,
        &with(fields_of(&stale, &action), "show_on_home", None),
    )
    .await;
    assert_eq!(
        flags(&app, id, c).await,
        [true, false, false, true, true, false]
    );

    // H2: `custom` with no switch stores the pair as posted.
    let custom = [
        ("calls_shown", "custom"),
        ("outbound", "on"),
        ("texts_shown", "custom"),
        ("sms_in", "on"),
    ];
    let custom: Vec<(String, String)> = custom
        .iter()
        .map(|(k, v)| (k.to_string(), v.to_string()))
        .collect();
    post(&app, &cookie, &action, &custom).await;
    assert_eq!(
        flags(&app, id, c).await,
        [false, true, false, true, false, false]
    );
    // An older page (no `*_shown`, the old six switches): today's behaviour; an unknown value too (N4).
    let old: Vec<(String, String)> = [
        ("inbound", "on"),
        ("sms_out", "on"),
        ("texts_shown", "v2"),
        ("texts", "on"),
    ]
    .iter()
    .map(|(k, v)| (k.to_string(), v.to_string()))
    .collect();
    post(&app, &cookie, &action, &old).await;
    assert_eq!(
        flags(&app, id, c).await,
        [true, false, false, false, true, false]
    );

    // D9: a save with the Avansert open comes back with it open.
    let html = page(&app, &cookie, &calls).await;
    let res = post(
        &app,
        &cookie,
        &action,
        &with(fields_of(&html, &action), "more", Some("1")),
    )
    .await;
    assert_redirect(
        &res,
        &format!("/devices/{id}/calls?open={c}&more={c}"),
        Some(&format!("contact-{c}")),
    );
    app.assert_lands(&res, &cookie).await;
    sqlx::query("UPDATE device_contacts SET allow_inbound = 1, allow_outbound = 1, sms_in = 1 WHERE contact_id = ?")
        .bind(c)
        .execute(&app.db)
        .await
        .unwrap();
    let html = page(
        &app,
        &cookie,
        &format!("/devices/{id}/calls?open={c}&more={c}"),
    )
    .await;
    assert!(contact_html(&html, c).contains("<details class=\"adv\" data-adv open>"));
    assert!(contact_html(&html, c).contains(r#"name="more" value="1""#));
}

/// L2: a refused Matrix ID redraws the form from the pairs the save would have stored - a two-way switch turned off
/// in the same post survives the redraw - with the Avansert open and the reason under the field.
#[tokio::test]
async fn a_refusal_is_redrawn_from_the_resolved_pairs() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, _) = managed_phone(&app).await;
    let c = contact(&app, id, "Mamma", "+4790000001", [true; 6]).await;
    sqlx::query(
        "UPDATE device_contacts SET message_app = 'element', message_address = '@m:example.org'",
    )
    .execute(&app.db)
    .await
    .unwrap();
    let action = format!("/devices/{id}/contacts/{c}");
    let html = page(&app, &cookie, &format!("/devices/{id}/calls?open={c}")).await;
    let sent = with(fields_of(&html, &action), "calls", None);
    let res = post(
        &app,
        &cookie,
        &action,
        &with(sent, "message_address", Some("not an id")),
    )
    .await;
    assert_eq!(res.status, StatusCode::BAD_REQUEST);
    let html = res.text();
    let row = contact_html(&html, c);
    assert_eq!(input_checked(row, "calls", "on"), Some(false));
    assert_eq!(input_checked(row, "inbound", "on"), Some(false));
    assert_eq!(input_checked(row, "outbound", "on"), Some(false));
    assert_eq!(input_checked(row, "sms_in", "on"), Some(true));
    assert!(row.contains("<details class=\"adv\" data-adv open>"));
    assert!(row.contains("A Matrix ID looks like"));
    assert_eq!(flags(&app, id, c).await, [true; 6], "nothing saved");
}

/// D2: the Avansert row says what it hides - the contact's own Message button, a stored but unused Matrix ID - and
/// the Matrix ID shows while it is used or stored ("Mangler Matrix-ID" while the button opens Element X without one);
/// a post without the field keeps the stored ID.
#[tokio::test]
async fn the_avansert_row_says_what_it_hides() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, _) = managed_phone(&app).await;
    let signal = contact(&app, id, "Signe", "+4790000001", [true; 6]).await;
    let stored = contact(&app, id, "Stein", "+4790000002", [true; 6]).await;
    let missing = contact(&app, id, "Mia", "+4790000003", [true; 6]).await;
    for (c, app_col, address) in [
        (signal, Some("signal"), None),
        (stored, None, Some("@stein:example.org")),
        (missing, Some("element"), None),
    ] {
        sqlx::query(
            "UPDATE device_contacts SET message_app = ?, message_address = ? WHERE contact_id = ?",
        )
        .bind(app_col)
        .bind(address)
        .bind(c)
        .execute(&app.db)
        .await
        .unwrap();
    }
    let html = page(&app, &cookie, &format!("/devices/{id}/calls")).await;
    let row = contact_html(&html, signal);
    assert!(
        row.contains(r#"<span class="row-value" data-adv-value>Signal / Molly</span>"#),
        "{row}"
    );
    assert!(!row.contains("name=\"message_address\""));
    let row = contact_html(&html, stored);
    assert!(
        row.contains(r#"data-adv-value>Matrix ID stored</span>"#),
        "{row}"
    );
    assert!(row.contains("Used when the button opens Element X"));
    assert!(row.contains("<details class=\"adv\" data-adv>"));
    let row = contact_html(&html, missing);
    assert!(row.contains(r#"data-adv-value>Element X</span>"#), "{row}");
    assert!(row.contains("Matrix ID missing"));
    assert!(row.contains("<details class=\"adv\" data-adv open>"));
    // The field drawn: kept by every save; not drawn: kept too.
    let action = format!("/devices/{id}/contacts/{signal}");
    sqlx::query(
        "UPDATE device_contacts SET message_address = '@s:example.org' WHERE contact_id = ?",
    )
    .bind(signal)
    .execute(&app.db)
    .await
    .unwrap();
    post(
        &app,
        &cookie,
        &action,
        &with(fields_of(&html, &action), "mms", None),
    )
    .await;
    let kept: Option<String> =
        sqlx::query_scalar("SELECT message_address FROM device_contacts WHERE contact_id = ?")
            .bind(signal)
            .fetch_one(&app.db)
            .await
            .unwrap();
    assert_eq!(kept.as_deref(), Some("@s:example.org"));
}

/// D1's sub-lines in the page by the modes (M2), and an emergency number's texts row (`sms_out` alone, R2-H1).
#[tokio::test]
async fn the_sub_lines_follow_the_channel_modes() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, _) = managed_phone(&app).await;
    let c = contact(
        &app,
        id,
        "Mamma",
        "+4790000001",
        [true, false, true, false, false, true],
    )
    .await;
    let ambulance = contact(
        &app,
        id,
        "Ambulanse",
        "113",
        [true, true, false, false, true, true],
    )
    .await;
    sqlx::query(
        "UPDATE device_policy SET calls_mode = 'unmanaged', sms_mode = 'off' WHERE device_id = ?",
    )
    .bind(id)
    .execute(&app.db)
    .await
    .unwrap();
    let html = page(&app, &cookie, &format!("/devices/{id}/calls")).await;
    let row = contact_html(&html, c);
    // Split calls: the one-line "Tilpasset" has no sub-line (L8).
    assert!(!row.contains("anyone can call"), "{row}");
    assert!(row.contains("Applies while SMS is managed by Vibb"));
    assert!(row.contains("Shows when Anna can call Mamma"));
    let row = contact_html(&html, ambulance);
    assert!(
        row.contains("Calls aren't managed - anyone can call. Ambulanse is in the phone book"),
        "{row}"
    );
    assert!(row.contains(r#"<span class="row-label">Anna can text Ambulanse<span class="row-sub">Emergency numbers"#), "{row}");
    assert_eq!(input_checked(row, "sms_out", "on"), Some(true));
    assert!(
        row.contains("name=\"sms_in\" value=\"on\" disabled"),
        "{row}"
    );
    assert!(!row.contains("name=\"texts\""));
    assert!(
        row.contains(">Calls and texts<"),
        "texts from sms_out alone (N6): {row}"
    );
    // Calls off.
    sqlx::query("UPDATE device_policy SET calls_mode = 'off' WHERE device_id = ?")
        .bind(id)
        .execute(&app.db)
        .await
        .unwrap();
    let html = page(&app, &cookie, &format!("/devices/{id}/calls")).await;
    assert!(
        contact_html(&html, ambulance)
            .contains("Calls are off - only emergency numbers can be called")
    );
}

/// D5: Add Contact's three ways in, each with its defaults; the held prefill posted unchanged stores exactly
/// `sms_in`; the phone's own number - stored or reported by a SIM - is refused and left out of the family list (L6).
#[tokio::test]
async fn add_contact_ways_in_and_the_own_number() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = managed_phone(&app).await;
    let action = format!("/devices/{id}/contacts#add-contact");
    let add = |html: &str| part(html, "id=\"add-contact\"", "</details>\n").to_string();
    let html = page(&app, &cookie, &format!("/devices/{id}/calls")).await;
    let form = add(&html);
    assert_eq!(input_checked(&form, "calls", "on"), Some(true));
    assert_eq!(input_checked(&form, "texts", "on"), Some(true));
    assert!(form.contains("<details class=\"adv\" data-adv>"));
    assert!(form.contains("The contact can call Anna"));
    assert!(form.contains("Anna can text the contact"));

    // From a held message: calls off, texts "Tilpasset" with only "Kontakten kan sende melding til", Avansert open.
    let html = page(
        &app,
        &cookie,
        &format!("/devices/{id}/calls?add_number=%2B4798765432"),
    )
    .await;
    let form = add(&html);
    assert_eq!(input_checked(&form, "calls", "on"), Some(false));
    assert!(form.contains(r#"name="texts_shown" value="custom""#));
    assert!(
        form.contains("<label class=\"row\" data-two-way=\"texts\" hidden>"),
        "{form}"
    );
    assert!(form.contains("<details class=\"adv\" data-adv open>"));
    assert!(form.contains("Shows when Anna can call the contact"));
    let sent = with(fields_of(&html, &action), "name", Some("Ukjent"));
    let res = post(&app, &cookie, &action, &sent).await;
    assert!(res.status.is_redirection(), "{}", res.text());
    let new: i64 = sqlx::query_scalar("SELECT id FROM contacts WHERE phone_number = '+4798765432'")
        .fetch_one(&app.db)
        .await
        .unwrap();
    assert_eq!(
        flags(&app, id, new).await,
        [false, false, false, true, false, false]
    );

    // L6: the stored own number and a SIM's reported one are refused, and a family contact with one isn't offered.
    sqlx::query(
        "INSERT INTO device_own_numbers (device_id, slot, number) VALUES (?, 1, '+4790000009')",
    )
    .bind(id)
    .execute(&app.db)
    .await
    .unwrap();
    report(
        &app,
        &token,
        json!({ "own_numbers": [{ "slot": 2, "number": "+4790000008" }] }),
    )
    .await;
    for number in ["90000009", "+4790000008"] {
        let res = app
            .request_form(
                Method::POST,
                &action,
                Some(&cookie),
                &[("name", "Meg"), ("number", number), ("switches", "on")],
            )
            .await;
        assert_eq!(res.status, StatusCode::BAD_REQUEST, "{number}");
        assert!(
            res.text().contains("That&#39;s the phone&#39;s own number"),
            "{number}"
        );
    }
    let (other, _) = app.enrolled_device("Ola").await;
    let own = contact(&app, other, "Annas telefon", "+4790000008", [true; 6]).await;
    contact(&app, other, "Bestemor", "+4790000007", [true; 6]).await;
    let html = page(&app, &cookie, &format!("/devices/{id}/calls")).await;
    let family = part(&html, "id=\"add-family\"", "</details>");
    assert!(
        family.contains("Bestemor") && !family.contains("Annas telefon"),
        "{family}"
    );
    let forged = app
        .request_form(
            Method::POST,
            &format!("/devices/{id}/contacts#add-family"),
            Some(&cookie),
            &[("contact_id", &own.to_string())],
        )
        .await;
    assert!(forged.status.is_redirection());
    let on_phone: bool = sqlx::query_scalar(
        "SELECT EXISTS(SELECT 1 FROM device_contacts WHERE device_id = ? AND contact_id = ?)",
    )
    .bind(id)
    .bind(own)
    .fetch_one(&app.db)
    .await
    .unwrap();
    assert!(!on_phone);
}

/// D8: `/calls/settings` split by its section marker - each saving one leaves the other unchanged - and no marker is
/// the write-all of before.
#[tokio::test]
async fn the_settings_form_is_split_by_section() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, _) = managed_phone(&app).await;
    let uri = format!("/devices/{id}/calls/settings");
    let stored = || async {
        sqlx::query_as::<_, (String, String, String)>(
            "SELECT calls_mode, sms_mode, default_message_app FROM device_policy WHERE device_id = ?",
        )
        .bind(id)
        .fetch_one(&app.db)
        .await
        .unwrap()
    };
    let res = app
        .request_form(
            Method::POST,
            &uri,
            Some(&cookie),
            &[("section", "button"), ("default_message_app", "signal")],
        )
        .await;
    assert_redirect(
        &res,
        &format!("/devices/{id}/calls?advanced=1"),
        Some("message-button"),
    );
    app.assert_lands(&res, &cookie).await;
    assert_eq!(
        stored().await,
        ("managed".into(), "managed".into(), "signal".into())
    );
    let res = app
        .request_form(
            Method::POST,
            &uri,
            Some(&cookie),
            &[
                ("section", "channels"),
                ("calls_mode", "unmanaged"),
                ("sms_mode", "managed"),
            ],
        )
        .await;
    assert_redirect(&res, &format!("/devices/{id}/calls"), Some("settings"));
    assert_eq!(
        stored().await,
        ("unmanaged".into(), "managed".into(), "signal".into())
    );
    // No marker: today's write-all (a missing button reads as SMS).
    app.request_form(
        Method::POST,
        &uri,
        Some(&cookie),
        &[("calls_mode", "managed"), ("sms_mode", "managed")],
    )
    .await;
    assert_eq!(
        stored().await,
        ("managed".into(), "managed".into(), "sms".into())
    );
    // A button post without its picker is refused.
    let res = app
        .request_form(Method::POST, &uri, Some(&cookie), &[("section", "button")])
        .await;
    assert_eq!(res.status, StatusCode::BAD_REQUEST);
}

/// §4.5: the phone's own number by its source - the SIM's row with "Bruk dette nummeret" (which stores the reported
/// number, never a posted one) and "Endre…", "Lagret", a stored slot for a SIM no longer in the phone, the field while
/// nothing is known, and the missing number said under SMS, on Avansert's row and on a blocked group row (M5).
/// `/calls/sms` writes only the slots it is sent.
#[tokio::test]
async fn the_phones_own_number_by_its_source() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = managed_phone(&app).await;
    let calls = format!("/devices/{id}/calls");
    // Nothing known: the field, the lines, and a blocked group row's reason.
    report(
        &app,
        &token,
        json!({ "own_numbers": [{ "slot": 1, "number": null }] }),
    )
    .await;
    let at = Utc::now().timestamp_millis() - 60_000;
    let res = app
        .request(
            Method::POST,
            "/api/devices/sms-held",
            Some(&token),
            Some(json!({ "rows": [{ "id": format!("{:032x}", 7), "at": at, "kind": "mms", "sender_class": "group",
                "sender": "", "expires_at": at, "state": "blocked" }] })),
        )
        .await;
    assert_eq!(res.status, StatusCode::OK, "{}", res.text());
    let html = page(&app, &cookie, &calls).await;
    let settings = part(&html, "id=\"sms-settings\"", "</section>");
    assert!(
        settings.contains("The phone doesn't know its number yet."),
        "{settings}"
    );
    assert!(
        settings.contains(
            r#"<label class="field-label" for="own_number_1">The phone's number</label>"#
        )
    );
    assert!(html.contains("data-number-line"), "the line under SMS");
    assert!(html.contains(
        r#"data-adv-line="number">The phone's number is missing - group texts are blocked"#
    ));
    assert!(html.contains(
        "data-blocked-reason>While the phone's number is missing, every group text is blocked"
    ));

    // The SIM reports it: the row, "Use this number" (its own small form) and "Change…"; no missing lines.
    report(
        &app,
        &token,
        json!({ "own_numbers": [{ "slot": 1, "number": "+4790000009" }] }),
    )
    .await;
    let html = page(&app, &cookie, &calls).await;
    let settings = part(&html, "id=\"sms-settings\"", "</section>");
    assert!(settings.contains("From the SIM card"), "{settings}");
    assert!(settings.contains(r#"form="own-use-1""#));
    assert!(
        settings.contains(r#"name="own_number_1" value="""#),
        "the field holds the stored number: {settings}"
    );
    assert!(!html.contains("data-number-line") && !html.contains("data-blocked-reason"));
    // The tap stores the reported number, whatever the form says.
    let use_uri = format!("/devices/{id}/calls/own-number/use");
    let res = app
        .request_form(
            Method::POST,
            &use_uri,
            Some(&cookie),
            &[("source", "sim:1"), ("number", "+4711111111")],
        )
        .await;
    assert_redirect(&res, &format!("{calls}?advanced=1"), Some("sms-settings"));
    app.assert_lands(&res, &cookie).await;
    let own: Vec<(i64, String)> =
        sqlx::query_as("SELECT slot, number FROM device_own_numbers WHERE device_id = ?")
            .bind(id)
            .fetch_all(&app.db)
            .await
            .unwrap();
    assert_eq!(own, [(1, "+4790000009".to_string())]);
    let html = page(&app, &cookie, &format!("{calls}?advanced=1")).await;
    assert!(html.contains("<details class=\"section\" id=\"advanced\" open>"));
    assert!(part(&html, "id=\"sms-settings\"", "</section>").contains("Saved"));
    // A source the report no longer has: the notice, nothing stored.
    let res = app
        .request_form(
            Method::POST,
            &use_uri,
            Some(&cookie),
            &[("source", "sim:2")],
        )
        .await;
    assert_redirect(
        &res,
        &format!("{calls}?advanced=1&notice=own_number_gone"),
        Some("sms-settings"),
    );
    let html = page(
        &app,
        &cookie,
        &format!("{calls}?advanced=1&notice=own_number_gone"),
    )
    .await;
    assert!(html.contains("The phone reported another number meanwhile"));

    // `/calls/sms`: an absent field keeps its slot, an empty one removes it; a refused one renders with Avansert open.
    let sms = format!("/devices/{id}/calls/sms");
    app.request_form(
        Method::POST,
        &sms,
        Some(&cookie),
        &[("own_number_2", "+4790000002")],
    )
    .await;
    let count: i64 =
        sqlx::query_scalar("SELECT COUNT(*) FROM device_own_numbers WHERE device_id = ?")
            .bind(id)
            .fetch_one(&app.db)
            .await
            .unwrap();
    assert_eq!(count, 2, "slot 1 kept");
    // L5: slot 2 stored for a SIM no longer in the phone: its own field, so it can be cleared.
    let html = page(&app, &cookie, &calls).await;
    assert!(html.contains("SIM 2 - not in the phone now"), "{html}");
    let res = app
        .request_form(Method::POST, &sms, Some(&cookie), &[("own_number_2", "")])
        .await;
    assert_redirect(&res, &format!("{calls}?advanced=1"), Some("sms-settings"));
    let count: i64 =
        sqlx::query_scalar("SELECT COUNT(*) FROM device_own_numbers WHERE device_id = ?")
            .bind(id)
            .fetch_one(&app.db)
            .await
            .unwrap();
    assert_eq!(count, 1);
    let res = app
        .request_form(
            Method::POST,
            &sms,
            Some(&cookie),
            &[("own_number_1", "113")],
        )
        .await;
    assert_eq!(res.status, StatusCode::BAD_REQUEST);
    let html = res.text();
    assert!(html.contains("<details class=\"section\" id=\"advanced\" open>"));
    assert!(
        html.contains("<details class=\"own-edit\" open>"),
        "the refused field open"
    );
    // A mismatch: the field with the stored number, and the finding above the groups.
    report(
        &app,
        &token,
        json!({ "own_numbers": [{ "slot": 1, "number": "+4790000008" }] }),
    )
    .await;
    let html = page(&app, &cookie, &calls).await;
    assert!(html.contains(r#"name="own_number_1" value="+4790000009""#));
    assert!(html.contains("Check The Phone's Number under Advanced"));
}

/// D7/D8: the page's order (what needs a decision, the channels, the contacts, then Avansert); Avansert's row names
/// the emergency menu with the orange count, the Message button, the sender names (plural-correct) and the lock text;
/// a refused Add Number draws it open; the RCS step is under SMS for 14 days after the change, then in Avansert (M4).
#[tokio::test]
async fn the_page_keeps_on_view_what_needs_a_decision() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, _) = managed_phone(&app).await;
    let calls = format!("/devices/{id}/calls");
    sqlx::query("UPDATE device_policy SET roles_changed_at = datetime('now', '-1 days') WHERE device_id = ?")
        .bind(id)
        .execute(&app.db)
        .await
        .unwrap();
    let html = page(&app, &cookie, &calls).await;
    let order: Vec<usize> = [
        "id=\"held\"",
        "id=\"requests\"",
        "id=\"settings\"",
        "id=\"contacts\"",
        "id=\"advanced\"",
        "id=\"emergency\"",
        "id=\"sms-settings\"",
        "id=\"sms-names\"",
    ]
    .iter()
    .map(|anchor| html.find(anchor).unwrap_or_else(|| panic!("{anchor}")))
    .collect();
    assert!(order.windows(2).all(|w| w[0] < w[1]), "{order:?}");
    assert!(
        html.contains("<details class=\"section\" id=\"advanced\">"),
        "closed"
    );
    assert!(html.contains("data-adv-line=\"emergency\">Emergency menu: 112<"));
    assert!(html.contains("data-adv-line=\"messages\">Message button: SMS<"));
    assert!(
        part(&html, "id=\"settings\"", "</section>")
            .contains("Turn off chat features in Messages if you haven't.")
    );
    assert!(!html.contains("data-rcs-row"));

    // A number that isn't an emergency one, a name, the lock text; the RCS step after 14 days.
    let cookie_form = |fields: &'static [(&'static str, &'static str)], uri: String| {
        let (app, cookie) = (&app, cookie.clone());
        async move {
            app.request_form(Method::POST, &uri, Some(&cookie), fields)
                .await
        }
    };
    let res = cookie_form(
        &[("number", "90000003"), ("name", "")],
        format!("/devices/{id}/calls/emergency"),
    )
    .await;
    let entry: i64 =
        sqlx::query_scalar("SELECT id FROM device_emergency_numbers WHERE number = '+4790000003'")
            .fetch_one(&app.db)
            .await
            .unwrap();
    assert_redirect(
        &res,
        &format!("{calls}?advanced=1"),
        Some(&format!("emergency-{entry}")),
    );
    for name in ["Posten", "DNB"] {
        let res = app
            .request_form(
                Method::POST,
                &format!("/devices/{id}/calls/names"),
                Some(&cookie),
                &[("name", name)],
            )
            .await;
        assert_redirect(&res, &format!("{calls}?advanced=1"), Some("sms-names"));
    }
    cookie_form(
        &[("sms_lock_text", "on")],
        format!("/devices/{id}/calls/sms"),
    )
    .await;
    sqlx::query("UPDATE device_policy SET roles_changed_at = datetime('now', '-15 days') WHERE device_id = ?")
        .bind(id)
        .execute(&app.db)
        .await
        .unwrap();
    let html = page(&app, &cookie, &calls).await;
    assert!(
        html.contains("data-adv-line=\"emergency\">Emergency menu: 112, +4790000003<"),
        "{html}"
    );
    assert!(html.contains("data-adv-line=\"emergency-rules\">1 number follows the call rules<"));
    assert!(html.contains("data-adv-line=\"messages\">Message button: SMS · 2 sender names · Message text on the lock screen<"));
    assert!(!part(&html, "id=\"settings\"", "</section>").contains("chat features in Messages if"));
    assert!(html.contains("data-rcs-row"));
    let nb = page(&app, &cookie, &format!("{calls}?lang=nb")).await;
    assert!(nb.contains("Meldingsknapp: SMS · 2 avsendernavn · Meldingstekst på låseskjermen"));
    assert!(nb.contains("1 nummer følger anropsreglene"));
    // A refused Add Number draws Avansert open.
    let res = cookie_form(
        &[("number", "12"), ("name", "")],
        format!("/devices/{id}/calls/emergency"),
    )
    .await;
    assert_eq!(res.status, StatusCode::BAD_REQUEST);
    assert!(
        res.text()
            .contains("<details class=\"section\" id=\"advanced\" open>")
    );
}
