//! Design 83: the MMS addresses a phone may fetch a picture from - `call_policy.mmsc_hosts` has a row only for the
//! SIMs the phone reports, with the list's unasked entries and the parent's allowed ones and never a hint; a
//! contact's picture at another address (`sms_state.mmsc_unlisted`) becomes one question on the Calls page, and its
//! forms ("Tillat", "Ikke tillat", "Fjern", "Tillat likevel") each land on their own anchor or notice.

use axum::http::Method;
use chrono::Utc;
use serde_json::{Value, json};

use super::TestApp;

/// A SIM operator Android's list doesn't have, and the contact the pictures are from.
const SIM: &str = "24299";
const MAMMA: &str = "+4791234567";

async fn contact(app: &TestApp, id: i64, name: &str, number: &str) {
    let contact: i64 =
        sqlx::query_scalar("INSERT INTO contacts (name, phone_number) VALUES (?, ?) RETURNING id")
            .bind(name)
            .bind(number)
            .fetch_one(&app.db)
            .await
            .unwrap();
    sqlx::query("INSERT INTO device_contacts (device_id, contact_id) VALUES (?, ?)")
        .bind(id)
        .bind(contact)
        .execute(&app.db)
        .await
        .unwrap();
}

/// A status report from a phone with these SIMs and these pictures `(mccmnc, host, from)`, each arrived a minute
/// ago.
async fn report(app: &TestApp, token: &str, sims: &[&str], unlisted: &[(&str, &str, &str)]) {
    let at = Utc::now().timestamp_millis() - 60_000;
    let sims: Vec<Value> = sims
        .iter()
        .map(|mccmnc| json!({"mccmnc": mccmnc, "name": "Eksempel Mobil"}))
        .collect();
    let unlisted: Vec<Value> = unlisted
        .iter()
        .map(|(mccmnc, host, from)| json!({"mccmnc": mccmnc, "host": host, "from": from, "at": at}))
        .collect();
    app.post_status(
        token,
        json!({"sms_state": {"sim_operators": sims, "mmsc_unlisted": unlisted}}),
    )
    .await;
}

/// A stored address in `state`, as a parent's answer or the intake left it.
async fn stored(app: &TestApp, id: i64, mccmnc: &str, host: &str, state: &str) -> i64 {
    sqlx::query_scalar(
        "INSERT INTO device_mmsc_hosts (device_id, mccmnc, host, state, picture_from, picture_at, reported_at) \
         VALUES (?, ?, ?, ?, ?, ?, ?) RETURNING id",
    )
    .bind(id)
    .bind(mccmnc)
    .bind(host)
    .bind(state)
    .bind(MAMMA)
    .bind(Utc::now().timestamp_millis())
    .bind(Utc::now().timestamp_millis())
    .fetch_one(&app.db)
    .await
    .unwrap()
}

/// `(id, mccmnc, host, state)` of a phone's rows, oldest first.
async fn rows(app: &TestApp, id: i64) -> Vec<(i64, String, String, String)> {
    sqlx::query_as(
        "SELECT id, mccmnc, host, state FROM device_mmsc_hosts WHERE device_id = ? ORDER BY id",
    )
    .bind(id)
    .fetch_all(&app.db)
    .await
    .unwrap()
}

async fn events(app: &TestApp, id: i64) -> i64 {
    sqlx::query_scalar(
        "SELECT COUNT(*) FROM parent_events WHERE device_id = ? AND kind = 'mmsc_host'",
    )
    .bind(id)
    .fetch_one(&app.db)
    .await
    .unwrap()
}

async fn mmsc_hosts(app: &TestApp, token: &str) -> Value {
    app.policy(token).await["call_policy"]["mmsc_hosts"].clone()
}

async fn post(app: &TestApp, cookie: &str, action: &str) -> super::TestResponse {
    app.request_form(Method::POST, action, Some(cookie), &[])
        .await
}

/// The policy's rows: `[]` before a report, then exactly the reported SIMs' allowed entries (the contract's
/// `mmsc_hosts_row`) - not another operator's, never one of the list's hints - and `[]` again from a launcher that
/// reports no SIMs. A report with another SIM than the last nudges the phone, the same SIMs don't.
#[tokio::test]
async fn the_policy_has_the_reported_sims_entries() {
    let app = TestApp::new().await;
    let (id, token) = app.enrolled_device("Emma").await;
    let contract: Value =
        serde_json::from_str(include_str!("../../testdata/sms_contract.json")).unwrap();
    let row = contract["policy"]["mmsc_hosts_row"].clone();
    for host in row["hosts"].as_array().unwrap().iter().rev() {
        stored(&app, id, SIM, host.as_str().unwrap(), "allowed").await;
    }
    stored(&app, id, "24298", "mms.example.invalid", "allowed").await;
    stored(&app, id, SIM, "mms2.example.invalid", "declined").await;
    stored(&app, id, SIM, "mms3.example.invalid", "open").await;
    assert_eq!(mmsc_hosts(&app, &token).await, json!([]));

    // 24204's line has a public name: a hint at most, whatever the generator's `UNASKED`.
    let tele2 = crate::mmsc_hosts::listed("24204").unwrap();
    assert!(!tele2.hints.is_empty());
    let mut nudges = app.state.command_notify.subscribe();
    report(&app, &token, &[SIM, "24204"], &[]).await;
    assert_eq!(nudges.try_recv().unwrap(), id);
    let sent = mmsc_hosts(&app, &token).await;
    let sent: Vec<&Value> = sent.as_array().unwrap().iter().collect();
    assert_eq!(sent.last(), Some(&&row));
    assert_eq!(sent.len(), 1 + usize::from(!tele2.unasked.is_empty()));
    for other in &sent[..sent.len() - 1] {
        assert_eq!(other["mccmnc"], "24204");
        for host in other["hosts"].as_array().unwrap() {
            assert!(!tele2.hints.contains(&host.as_str().unwrap()), "{host}");
        }
    }

    report(&app, &token, &[SIM, "24204"], &[]).await;
    assert!(nudges.try_recv().is_err(), "the same SIMs");
    app.post_status(&token, json!({"sms_state": {"role_held": true}}))
        .await;
    assert_eq!(mmsc_hosts(&app, &token).await, json!([]));
}

/// The intake: a contact's picture for a reported SIM becomes one open row and one event, and the next report of it
/// neither; a row for a SIM the report doesn't name, from a number that is no contact, or with a host that is no
/// entry is dropped; a fifth open address is not stored.
#[tokio::test]
async fn the_intake_keeps_a_contacts_picture_for_a_reported_sim() {
    let app = TestApp::new().await;
    let (id, token) = app.enrolled_device("Emma").await;
    contact(&app, id, "Mamma", MAMMA).await;
    let pictures = [
        (SIM, "mmsc.example.invalid", MAMMA),
        ("24298", "mms.example.invalid", MAMMA),
        (SIM, "mms2.example.invalid", "+4790000009"),
        (SIM, "MMS3.example.invalid", MAMMA),
    ];
    for _ in 0..2 {
        report(&app, &token, &[SIM], &pictures).await;
        let kept: Vec<(String, String, String)> = rows(&app, id)
            .await
            .into_iter()
            .map(|(_, mccmnc, host, state)| (mccmnc, host, state))
            .collect();
        assert_eq!(
            kept,
            [(SIM.into(), "mmsc.example.invalid".into(), "open".into())]
        );
        assert_eq!(events(&app, id).await, 1);
    }
    assert_eq!(mmsc_hosts(&app, &token).await, json!([]), "asked, not sent");

    let more = ["a", "b", "c", "d"].map(|n| format!("mms-{n}.example.invalid"));
    let more: Vec<(&str, &str, &str)> = more.iter().map(|h| (SIM, h.as_str(), MAMMA)).collect();
    report(&app, &token, &[SIM], &more).await;
    assert_eq!(rows(&app, id).await.len(), 4);
    assert_eq!(events(&app, id).await, 4);
}

/// The device page counts the questions and leads to them. "Tillat" on one: the address is in the phone's row, the
/// form lands on its row in "Bildeadresser" (drawn open), and the phone reporting the picture once more raises nothing. "Fjern" takes it out of the policy and it is
/// never asked about again; "Tillat likevel" puts it back. "Ikke tillat" on a question sends nothing.
#[tokio::test]
async fn allow_decline_and_take_back() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("Emma").await;
    contact(&app, id, "Mamma", MAMMA).await;
    let pictures = [
        (SIM, "mmsc.example.invalid", MAMMA),
        (SIM, "mms2.example.invalid", MAMMA),
    ];
    report(&app, &token, &[SIM], &pictures).await;
    let (row, other) = (rows(&app, id).await[0].0, rows(&app, id).await[1].0);
    let page = app
        .get_page(&format!("/devices/{id}/calls"), &cookie)
        .await
        .text();
    assert!(page.contains(&format!("id=\"mmsc-{row}\"")), "{page}");
    assert!(page.contains("mmsc.example.invalid. Allow?") && page.contains("from Mamma"));
    // The device page counts the two questions and leads to them: no message is held.
    let summary = app
        .get_page(&format!("/devices/{id}"), &cookie)
        .await
        .text();
    assert!(summary.contains("2 messages waiting"), "{summary}");
    assert!(
        summary.contains(&format!(
            "href=\"/devices/{id}/calls#mmsc\" data-row=\"sms\""
        )),
        "{summary}"
    );
    let action = |row: i64, to: &str| format!("/devices/{id}/calls/mmsc/{row}/{to}");
    let row_anchor = format!("/devices/{id}/calls?mmsc=1#mmsc-{row}");
    let sent = json!([{"mccmnc": SIM, "hosts": ["mmsc.example.invalid"]}]);

    let res = post(&app, &cookie, &action(row, "allow")).await;
    assert_eq!(res.location(), Some(row_anchor.as_str()));
    app.assert_lands(&res, &cookie).await;
    assert_eq!(mmsc_hosts(&app, &token).await, sent);
    report(&app, &token, &[SIM], &pictures).await;
    assert_eq!(events(&app, id).await, 2, "one for each address, once");

    let res = post(&app, &cookie, &action(other, "decline")).await;
    assert_eq!(
        res.location(),
        Some(format!("/devices/{id}/calls?mmsc=1#mmsc-{other}").as_str())
    );
    app.assert_lands(&res, &cookie).await;
    assert_eq!(mmsc_hosts(&app, &token).await, sent);

    let take_back = format!("{}?decided=1", action(row, "decline"));
    let res = post(&app, &cookie, &take_back).await;
    assert_eq!(res.location(), Some(row_anchor.as_str()));
    app.assert_lands(&res, &cookie).await;
    assert_eq!(mmsc_hosts(&app, &token).await, json!([]));
    report(&app, &token, &[SIM], &pictures).await;
    assert_eq!(events(&app, id).await, 2);
    let states: Vec<String> = rows(&app, id).await.into_iter().map(|r| r.3).collect();
    assert_eq!(states, ["declined", "declined"], "never asked again");

    // The same answer twice stored nothing: back on the notice of the group the form was in.
    let res = post(&app, &cookie, &take_back).await;
    assert_eq!(
        res.location(),
        Some(format!("/devices/{id}/calls?mmsc=1&notice=mmsc_gone#mmsc-decided").as_str())
    );
    app.assert_lands(&res, &cookie).await;
    let res = post(&app, &cookie, &action(row + 100, "allow")).await;
    assert_eq!(
        res.location(),
        Some(format!("/devices/{id}/calls?notice=mmsc_gone#mmsc").as_str())
    );
    app.assert_lands(&res, &cookie).await;

    let res = post(
        &app,
        &cookie,
        &format!("{}?decided=1", action(row, "allow")),
    )
    .await;
    assert_eq!(res.location(), Some(row_anchor.as_str()));
    assert_eq!(mmsc_hosts(&app, &token).await, sent);
}

/// Eight addresses are allowed for the SIM: the question says so in place of "Tillat", a ninth allow stores nothing
/// and lands on the notice where its form was, and another phone's row can't be answered.
#[tokio::test]
async fn the_ninth_allow_is_refused() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, _) = app.enrolled_device("Emma").await;
    let (ola, _) = app.enrolled_device("Ola").await;
    for n in 0..8 {
        let host = format!("mms-{n}.example.invalid");
        stored(&app, id, SIM, &host, "allowed").await;
    }
    let open = stored(&app, id, SIM, "mmsc.example.invalid", "open").await;
    let declined = stored(&app, id, SIM, "mms.example.invalid", "declined").await;
    let page = app
        .get_page(&format!("/devices/{id}/calls"), &cookie)
        .await
        .text();
    assert_eq!(page.matches("data-mmsc-full").count(), 2, "{page}");
    // No account answered these rows: "Allowed 10 Nov", with no "by" left hanging.
    assert!(
        page.contains("· Allowed ") && !page.contains(" by -"),
        "{page}"
    );
    assert!(!page.contains(&format!("/mmsc/{open}/allow")));

    let res = post(
        &app,
        &cookie,
        &format!("/devices/{id}/calls/mmsc/{open}/allow"),
    )
    .await;
    assert_eq!(
        res.location(),
        Some(format!("/devices/{id}/calls?notice=mmsc_full#mmsc").as_str())
    );
    app.assert_lands(&res, &cookie).await;
    let res = post(
        &app,
        &cookie,
        &format!("/devices/{id}/calls/mmsc/{declined}/allow?decided=1"),
    )
    .await;
    assert_eq!(
        res.location(),
        Some(format!("/devices/{id}/calls?mmsc=1&notice=mmsc_full#mmsc-decided").as_str())
    );
    app.assert_lands(&res, &cookie).await;
    let res = post(
        &app,
        &cookie,
        &format!("/devices/{ola}/calls/mmsc/{open}/decline"),
    )
    .await;
    assert_eq!(
        res.location(),
        Some(format!("/devices/{ola}/calls?notice=mmsc_gone#mmsc").as_str())
    );
    let states: Vec<String> = rows(&app, id).await.into_iter().map(|r| r.3).collect();
    assert_eq!(states[8..], ["open", "declined"]);
}

/// A phone keeps 32 declined addresses: one more decline drops the oldest of the others.
#[tokio::test]
async fn the_oldest_decline_goes_past_the_cap() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, _) = app.enrolled_device("Emma").await;
    let mut kept = Vec::new();
    for n in 0..crate::mmsc_hosts::DECLINED_MAX {
        let host = format!("mms-{n}.example.invalid");
        kept.push(stored(&app, id, SIM, &host, "declined").await);
    }
    let asked = stored(&app, id, SIM, "mmsc.example.invalid", "open").await;
    let res = post(
        &app,
        &cookie,
        &format!("/devices/{id}/calls/mmsc/{asked}/decline"),
    )
    .await;
    app.assert_lands(&res, &cookie).await;
    kept.remove(0);
    kept.push(asked);
    let left: Vec<i64> = rows(&app, id).await.into_iter().map(|r| r.0).collect();
    assert_eq!(left, kept);
}
