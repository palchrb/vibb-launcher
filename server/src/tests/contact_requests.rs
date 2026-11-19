//! Design 77 §14 (step 6): `POST /api/devices/contact-request` - the kid asks for a new contact from the phone book's
//! "+" - with the answers of the shared contract `testdata/contact_request.json`, the day's and the open caps, a
//! retried id, `known` and `invalid`; the policy's `call_policy.contact_requests`; the Calls page's "Forespørsler" card
//! with "Legg til…" (Add Contact prefilled, §14's switches, the Matrix ID only when ticked) and "Avslå"; the push and
//! its switch; retention.

use axum::http::{Method, StatusCode};
use chrono::{Duration, Utc};
use serde_json::{Value, json};

use crate::display_time::Zone;

use super::web_push::{ENDPOINT, subscribe};
use super::{TestApp, input_checked};

/// The launcher's body, the answers, the check vectors and the policy rows (`ContactRequestsTest` reads exactly
/// these; byte-identical in its test resources).
const CONTRACT: &str = include_str!("../../testdata/contact_request.json");

fn contract() -> Value {
    serde_json::from_str(CONTRACT).unwrap()
}

#[test]
fn the_contact_request_contract_matches_the_launchers_copy() {
    let copy_path = std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("../launcher/app/src/test/resources/contact_request.json");
    let copy = std::fs::read_to_string(&copy_path)
        .unwrap_or_else(|e| panic!("{}: {e}", copy_path.display()));
    assert_eq!(copy, CONTRACT, "{} differs", copy_path.display());
}

/// A Phone Setup zone whose day began at least an hour ago and ends at least two hours from now (as
/// `time_request`'s), so three requests in a minute are in one day at any hour the suite runs.
async fn steady_zone(app: &TestApp) {
    let now = Utc::now();
    for name in ["Europe/Oslo", "Asia/Tokyo", "America/New_York"] {
        let zone = Zone::from_setting(name);
        let into = now - zone.start_of_today(now);
        if into >= Duration::hours(1) && into <= Duration::hours(22) {
            sqlx::query("UPDATE provisioning_settings SET time_zone = ? WHERE id = 1")
                .bind(name)
                .execute(&app.db)
                .await
                .unwrap();
            return;
        }
    }
    unreachable!("three zones 6-8 hours apart: one is between 01:00 and 22:00")
}

/// A request with an id made from `n`, asked a minute ago.
fn body(n: u32, name: &str, number: &str) -> Value {
    json!({
        "id": format!("{n:032x}"),
        "name": name,
        "number": number,
        "asked_at_ms": (Utc::now() - Duration::minutes(1)).timestamp_millis(),
    })
}

async fn post(app: &TestApp, token: &str, body: Value) -> (StatusCode, Value) {
    let res = app
        .request(
            Method::POST,
            "/api/devices/contact-request",
            Some(token),
            Some(body),
        )
        .await;
    let json = if res.status == StatusCode::OK {
        res.json()
    } else {
        Value::Null
    };
    (res.status, json)
}

async fn rows(app: &TestApp, device: i64) -> Vec<(String, String, String, Option<String>, String)> {
    sqlx::query_as(
        "SELECT id, name, number, matrix_id, state FROM device_contact_requests WHERE device_id = ? ORDER BY id",
    )
    .bind(device)
    .fetch_all(&app.db)
    .await
    .unwrap()
}

async fn events(app: &TestApp, device: i64) -> i64 {
    sqlx::query_scalar(
        "SELECT COUNT(*) FROM parent_events WHERE device_id = ? AND kind = 'contact_request'",
    )
    .bind(device)
    .fetch_one(&app.db)
    .await
    .unwrap()
}

/// A contact on the phone.
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

/// The contract's requests (their instants moved to a minute ago) and every answer row: accepted - a retry of the
/// same id too, one row and one event -, the day's fourth `capped`, every `invalid` vector, and a number on the phone
/// `known`. The handler's own encodings are the rows, byte for byte; the policy's rows have the contract's keys.
#[tokio::test]
async fn the_contract_answers() {
    let app = TestApp::new().await;
    steady_zone(&app).await;
    let (id, token) = app.enrolled_device("Anna").await;
    contact(&app, id, "Mamma", "+4790000001").await;
    let contract = contract();
    let answers = contract["answers"].as_array().unwrap();
    let minute_ago = json!((Utc::now() - Duration::minutes(1)).timestamp_millis());
    let mut request = contract["request"].clone();
    request["asked_at_ms"] = minute_ago.clone();
    assert_eq!(
        post(&app, &token, request.clone()).await,
        (StatusCode::OK, answers[0].clone())
    );
    // A retry: the same answer, no second row or event.
    assert_eq!(post(&app, &token, request).await.1, answers[0]);
    assert_eq!(events(&app, id).await, 1);
    // Every invalid vector, before the cap counts anything.
    for (n, row) in contract["invalid"].as_array().unwrap().iter().enumerate() {
        let mut invalid = body(100 + n as u32, "", "");
        for key in ["name", "number", "matrix_id"] {
            if let Some(value) = row.get(key) {
                invalid[key] = value.clone();
            }
        }
        assert_eq!(post(&app, &token, invalid).await.1, answers[2], "{row}");
    }
    // A number on the phone, and one another open request has: known.
    assert_eq!(
        post(&app, &token, body(20, "Mor", "900 00 001")).await.1,
        answers[3]
    );
    assert_eq!(
        post(&app, &token, body(21, "Ola igjen", "+4791234567"))
            .await
            .1,
        answers[3]
    );
    let mut without = contract["request_without_matrix"].clone();
    without["asked_at_ms"] = minute_ago;
    assert_eq!(post(&app, &token, without).await.1, answers[0]);
    assert_eq!(
        post(&app, &token, body(3, "Trener", "+4791234569")).await.1,
        answers[0]
    );
    // The fourth of the day.
    assert_eq!(
        post(&app, &token, body(4, "Nabo", "+4791234570")).await.1,
        answers[1]
    );
    let stored = rows(&app, id).await;
    assert_eq!(stored.len(), 3, "{stored:?}");
    let ola = stored
        .iter()
        .find(|r| r.0 == "0123456789abcdef0123456789abcdef")
        .unwrap();
    assert_eq!(
        (
            ola.1.as_str(),
            ola.2.as_str(),
            ola.3.as_deref(),
            ola.4.as_str()
        ),
        (
            "Ola Nordmann",
            "+4791234567",
            Some("@ola:example.org"),
            "open"
        )
    );
    assert_eq!(events(&app, id).await, 3);
    // The handler's encodings are the contract's rows.
    use crate::handlers::contact_requests::Answer;
    let encoded: Vec<Value> = [
        Answer::ACCEPTED,
        Answer::CAPPED,
        Answer::INVALID,
        Answer::KNOWN,
    ]
    .iter()
    .map(|a| serde_json::to_value(a).unwrap())
    .collect();
    assert_eq!(&encoded, answers);
    // The policy carries the rows with the contract's keys, newest first.
    let policy = app.policy(&token).await["call_policy"]["contact_requests"].clone();
    let rows = policy.as_array().unwrap();
    assert_eq!(rows.len(), 3);
    for row in rows {
        let mut keys: Vec<&str> = row
            .as_object()
            .unwrap()
            .keys()
            .map(String::as_str)
            .collect();
        keys.sort_unstable();
        assert_eq!(keys, ["id", "state"]);
        assert_eq!(row["state"], "open");
    }
    for row in contract["policy"]["contact_requests"].as_array().unwrap() {
        let mut keys: Vec<&str> = row
            .as_object()
            .unwrap()
            .keys()
            .map(String::as_str)
            .collect();
        keys.sort_unstable();
        assert_eq!(keys, ["id", "state"]);
    }
}

/// At most five open at once, whatever day they came; the body's limits.
#[tokio::test]
async fn five_open_and_the_bodys_limits() {
    let app = TestApp::new().await;
    let (id, token) = app.enrolled_device("Anna").await;
    let two_days_ago = (Utc::now() - Duration::days(2)).timestamp_millis();
    for n in 1..=5 {
        sqlx::query(
            "INSERT INTO device_contact_requests (device_id, id, name, number, asked_at, received_at) \
             VALUES (?, ?, 'Venn', ?, ?, ?)",
        )
        .bind(id)
        .bind(format!("{n:032x}"))
        .bind(format!("+479100000{n}"))
        .bind(two_days_ago)
        .bind(two_days_ago)
        .execute(&app.db)
        .await
        .unwrap();
    }
    assert_eq!(
        post(&app, &token, body(9, "Ny", "+4791234567")).await.1,
        json!({"accepted": false, "reason": "capped"})
    );
    sqlx::query("UPDATE device_contact_requests SET state = 'declined' WHERE id = ?")
        .bind(format!("{:032x}", 1))
        .execute(&app.db)
        .await
        .unwrap();
    assert_eq!(
        post(&app, &token, body(9, "Ny", "+4791234567")).await.1,
        json!({"accepted": true})
    );
    // A bad id, a body that isn't the shape, one past 1 KiB, no token.
    let mut bad = body(10, "Ny", "+4791234568");
    bad["id"] = json!("NOT-HEX");
    assert_eq!(post(&app, &token, bad).await.0, StatusCode::BAD_REQUEST);
    assert_eq!(
        post(&app, &token, json!({"id": format!("{:032x}", 11)}))
            .await
            .0,
        StatusCode::BAD_REQUEST
    );
    let big = body(12, &"x".repeat(1100), "+4791234568");
    assert_eq!(
        post(&app, &token, big).await.0,
        StatusCode::PAYLOAD_TOO_LARGE
    );
    let res = app
        .request(
            Method::POST,
            "/api/devices/contact-request",
            None,
            Some(body(13, "Ny", "+4791234568")),
        )
        .await;
    assert_eq!(res.status, StatusCode::UNAUTHORIZED);
    // A request older than a week is gone on the phone.
    let mut old = body(14, "Gammel", "+4791234571");
    old["asked_at_ms"] = json!((Utc::now() - Duration::days(8)).timestamp_millis());
    assert_eq!(
        post(&app, &token, old).await.1,
        json!({"accepted": false, "reason": "invalid"})
    );
}

/// The part of the page that is `#request-<id>`'s row.
fn request_html(page: &str, n: u32) -> &str {
    let start = page
        .find(&format!("id=\"request-{n:032x}\""))
        .unwrap_or_else(|| panic!("no row {n}"));
    let rest = &page[start..];
    let end = rest[1..]
        .find("id=\"request-")
        .or_else(|| rest.find("</section>"))
        .map(|end| end + 1)
        .unwrap_or(rest.len());
    &rest[..end]
}

/// The card and "Legg til…" (§14, R2-M1): each row with its number's kind and the kid's Matrix ID as a suggestion;
/// Add Contact prefilled from the stored request with §14's switches and "Bruk Matrix-ID-en" unticked; saved
/// unticked, the contact has no Matrix ID and the request is `added` (the policy says so, the event is read for every
/// parent); a forged `use_matrix` for another phone's request saves nothing of it; ticked, the stored ID is the
/// contact's.
#[tokio::test]
async fn add_from_a_request() {
    let app = TestApp::new().await;
    steady_zone(&app).await;
    let cookie = app.admin_cookie().await;
    let _pappa = app.admin_cookie_as("pappa", "Pappa").await;
    let (id, token) = app.enrolled_device("Anna").await;
    let (other, other_token) = app.enrolled_device("Per").await;
    let mut ola = body(1, "Ola <b>&", "+4791234567");
    ola["matrix_id"] = json!("@ola:example.org");
    assert_eq!(post(&app, &token, ola).await.1["accepted"], true);
    assert_eq!(
        post(&app, &token, body(2, "Pizza", "1881")).await.1["accepted"],
        true
    );
    let mut theirs = body(3, "Lur", "+4791234599");
    theirs["matrix_id"] = json!("@lur:example.org");
    assert_eq!(post(&app, &other_token, theirs).await.1["accepted"], true);

    let page = app
        .get_page(&format!("/devices/{id}/calls"), &cookie)
        .await
        .text();
    let row = request_html(&page, 1);
    assert!(row.contains("Ola &#60;b&#62;&#38;"), "{row}");
    assert!(row.contains("+4791234567"));
    assert!(
        row.contains("Suggested Matrix ID: @ola:example.org"),
        "{row}"
    );
    assert!(row.contains(&format!(
        "href=\"/devices/{id}/calls?request={:032x}#add-contact\"",
        1
    )));
    assert!(row.contains("Decline"));
    assert!(request_html(&page, 2).contains("Short number - may cost money"));
    assert!(
        !page.contains(&format!("{:032x}", 3)),
        "another phone's request"
    );

    // "Legg til…": prefilled from the stored row.
    let add = app
        .get_page(&format!("/devices/{id}/calls?request={:032x}", 1), &cookie)
        .await
        .text();
    assert!(add.contains(r#"<details class="edit cell" id="add-contact" open>"#));
    assert!(add.contains(r#"value="Ola &#60;b&#62;&#38;""#), "escaped");
    assert!(add.contains(r#"value="+4791234567""#));
    for (switch, on) in [
        ("inbound", true),
        ("outbound", true),
        ("show_on_home", false),
        ("sms_in", true),
        ("sms_out", true),
        ("mms", false),
        ("use_matrix", false),
    ] {
        assert_eq!(input_checked(&add, switch, "on"), Some(on), "{switch}");
    }
    assert!(add.contains("Use the Matrix ID"));
    // Design 113: the suggestion and the impersonation caution, short.
    assert!(
        add.contains("Anna suggested @ola:example.org - check it&#39;s the right person."),
        "{add}"
    );
    assert!(add.contains(&format!(
        r#"<input type="hidden" name="request" value="{:032x}">"#,
        1
    )));
    // Another phone's request isn't prefilled here.
    let foreign = app
        .get_page(&format!("/devices/{id}/calls?request={:032x}", 3), &cookie)
        .await
        .text();
    assert!(!foreign.contains("name=\"use_matrix\""));

    // Saved without the tick: no Matrix ID, the request added.
    let request = format!("{:032x}", 1);
    let res = app
        .request_form(
            Method::POST,
            &format!("/devices/{id}/contacts"),
            Some(&cookie),
            &[
                ("name", "Ola"),
                ("number", "+4791234567"),
                ("switches", "on"),
                ("calls_shown", "custom"),
                ("texts_shown", "custom"),
                ("inbound", "on"),
                ("outbound", "on"),
                ("sms_in", "on"),
                ("sms_out", "on"),
                ("request", &request),
            ],
        )
        .await;
    assert!(res.status.is_redirection());
    assert!(res.location().unwrap().contains("#contact-"));
    let address: Option<String> = sqlx::query_scalar(
        "SELECT dc.message_address FROM device_contacts dc JOIN contacts c ON c.id = dc.contact_id \
         WHERE dc.device_id = ? AND c.phone_number = '+4791234567'",
    )
    .bind(id)
    .fetch_one(&app.db)
    .await
    .unwrap();
    assert_eq!(address, None);
    let policy = app.policy(&token).await["call_policy"].clone();
    assert!(
        policy["contact_requests"]
            .as_array()
            .unwrap()
            .contains(&json!({"id": request, "state": "added"}))
    );
    let reads: i64 = sqlx::query_scalar(
        "SELECT COUNT(*) FROM parent_event_reads r JOIN parent_events e ON e.id = r.event_id \
         WHERE e.device_id = ? AND e.dedup_key = ?",
    )
    .bind(id)
    .bind(&request)
    .fetch_one(&app.db)
    .await
    .unwrap();
    assert_eq!(reads, 2, "read for both parents");

    // A forged tick with the other phone's request: nothing of it is saved, and it stays open.
    let theirs = format!("{:032x}", 3);
    app.request_form(
        Method::POST,
        &format!("/devices/{id}/contacts"),
        Some(&cookie),
        &[
            ("name", "Lur"),
            ("number", "+4791234598"),
            ("switches", "on"),
            ("calls_shown", "custom"),
            ("texts_shown", "custom"),
            ("request", &theirs),
            ("use_matrix", "on"),
        ],
    )
    .await;
    let forged: Option<String> = sqlx::query_scalar(
        "SELECT dc.message_address FROM device_contacts dc JOIN contacts c ON c.id = dc.contact_id \
         WHERE dc.device_id = ? AND c.phone_number = '+4791234598'",
    )
    .bind(id)
    .fetch_one(&app.db)
    .await
    .unwrap();
    assert_eq!(forged, None);
    assert_eq!(rows(&app, other).await[0].4, "open");

    // Ticked: the stored Matrix ID is the contact's.
    let mut kari = body(4, "Kari", "+4791234580");
    kari["matrix_id"] = json!("@kari:example.org");
    assert_eq!(post(&app, &token, kari).await.1["accepted"], true);
    let request = format!("{:032x}", 4);
    app.request_form(
        Method::POST,
        &format!("/devices/{id}/contacts"),
        Some(&cookie),
        &[
            ("name", "Kari"),
            ("number", "+4791234580"),
            ("switches", "on"),
            ("calls_shown", "custom"),
            ("texts_shown", "custom"),
            ("request", &request),
            ("use_matrix", "on"),
        ],
    )
    .await;
    let kept: Option<String> = sqlx::query_scalar(
        "SELECT dc.message_address FROM device_contacts dc JOIN contacts c ON c.id = dc.contact_id \
         WHERE dc.device_id = ? AND c.phone_number = '+4791234580'",
    )
    .bind(id)
    .fetch_one(&app.db)
    .await
    .unwrap();
    assert_eq!(kept.as_deref(), Some("@kari:example.org"));
    // The card: added rows read "Added", without actions.
    let page = app
        .get_page(&format!("/devices/{id}/calls"), &cookie)
        .await
        .text();
    assert!(
        !page.contains(&format!("id=\"request-{:032x}\"", 4)),
        "an added request leaves the card"
    );
}

/// "Avslå": the row is declined (the policy says so, the card reads "Declined", back to the row); a second decline
/// lands on the card's notice; the Norwegian words.
#[tokio::test]
async fn decline_a_request() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("Anna").await;
    assert_eq!(
        post(&app, &token, body(1, "Ola", "+4791234567")).await.1["accepted"],
        true
    );
    let nb = app
        .get_page(&format!("/devices/{id}/calls?lang=nb"), &cookie)
        .await
        .text();
    assert!(nb.contains("Forespørsler") && nb.contains("Avslå") && nb.contains("Legg til"));
    let request = format!("{:032x}", 1);
    let action = format!("/devices/{id}/calls/requests/{request}/decline");
    let res = app
        .request_form(Method::POST, &action, Some(&cookie), &[])
        .await;
    assert_eq!(
        res.location(),
        Some(format!("/devices/{id}/calls#requests").as_str())
    );
    assert!(
        app.policy(&token).await["call_policy"]["contact_requests"]
            .as_array()
            .unwrap()
            .contains(&json!({"id": request, "state": "declined"}))
    );
    let page = app
        .get_page(&format!("/devices/{id}/calls"), &cookie)
        .await
        .text();
    assert!(
        !page.contains(&format!("id=\"request-{:032x}\"", 1)),
        "a declined request leaves the card"
    );
    let again = app
        .request_form(Method::POST, &action, Some(&cookie), &[])
        .await;
    assert_eq!(
        again.location(),
        Some(format!("/devices/{id}/calls?notice=request_gone#requests").as_str())
    );
    let notice = app
        .get_page(&format!("/devices/{id}/calls?notice=request_gone"), &cookie)
        .await
        .text();
    assert!(notice.contains("That request is no longer waiting"));
    // Any Add Contact with the number answers an open request; the declined one stays declined.
    assert_eq!(
        post(&app, &token, body(2, "Ola", "+4791234568")).await.1["accepted"],
        true
    );
    app.request_form(
        Method::POST,
        &format!("/devices/{id}/contacts"),
        Some(&cookie),
        &[
            [("name", "Ola 2"), ("number", "91234568")].as_slice(),
            &super::ADD_CONTACT_ALL_ON,
        ]
        .concat(),
    )
    .await;
    let states: Vec<String> = sqlx::query_scalar(
        "SELECT state FROM device_contact_requests WHERE device_id = ? ORDER BY id",
    )
    .bind(id)
    .fetch_all(&app.db)
    .await
    .unwrap();
    assert_eq!(states, ["declined", "added"]);
}

/// The push: "Anna asks for a new contact: Ola", to the row; the switch "Asks for a new contact" on by default and
/// listed once; off, no push.
#[tokio::test]
async fn the_push_and_its_switch() {
    let app = TestApp::new().await;
    steady_zone(&app).await;
    let mamma = app.admin_cookie().await;
    subscribe(&app, &mamma, ENDPOINT).await;
    let (id, token) = app.enrolled_device("Anna").await;
    assert_eq!(
        post(&app, &token, body(1, "Ola", "+4791234567")).await.1["accepted"],
        true
    );
    crate::push::send_due(&app.state, Utc::now()).await.unwrap();
    let titles: Vec<String> = app
        .pushes
        .take()
        .into_iter()
        .map(|r| {
            super::web_push::payload(&r)["notification"]["title"]
                .as_str()
                .unwrap_or_default()
                .to_string()
        })
        .collect();
    assert_eq!(titles, ["Anna asks for a new contact: Ola"]);
    let target: String =
        sqlx::query_scalar("SELECT target FROM parent_events WHERE kind = 'contact_request'")
            .fetch_one(&app.db)
            .await
            .unwrap();
    assert_eq!(target, format!("/devices/{id}/calls#request-{:032x}", 1));
    let settings = app.get_page("/notifications/settings", &mamma).await.text();
    assert_eq!(settings.matches("name=\"push_contact_request\"").count(), 1);
    assert_eq!(
        input_checked(&settings, "push_contact_request", "on"),
        Some(true)
    );
    let on: Vec<(&str, &str)> = crate::parent_events::Kind::switches()
        .into_iter()
        .filter(|(name, _)| *name != "push_contact_request")
        .map(|(name, _)| (name, "on"))
        .collect();
    app.request_form(Method::POST, "/notifications/settings", Some(&mamma), &on)
        .await;
    assert_eq!(
        post(&app, &token, body(2, "Kari", "+4791234568")).await.1["accepted"],
        true
    );
    crate::push::send_due(&app.state, Utc::now()).await.unwrap();
    assert!(app.pushes.take().is_empty());
    let inbox = app.get_page("/notifications", &mamma).await.text();
    assert!(
        inbox.contains("Anna asks for a new contact: Kari"),
        "{inbox}"
    );
}

/// An open row received `days` ago, as the intake would have stored it.
async fn stored(app: &TestApp, device: i64, n: u32, number: &str, days: i64) {
    let at = (Utc::now() - Duration::days(days)).timestamp_millis();
    sqlx::query(
        "INSERT INTO device_contact_requests (device_id, id, name, number, matrix_id, asked_at, received_at) \
         VALUES (?, ?, 'Venn', ?, '@venn:example.org', ?, ?)",
    )
    .bind(device)
    .bind(format!("{n:032x}"))
    .bind(number)
    .bind(at)
    .bind(at)
    .execute(&app.db)
    .await
    .unwrap();
}

/// QA H1: an open request older than 7 days has lapsed everywhere - it blocks nothing (`known`, the five open), isn't
/// prefilled or counted in the digest, and isn't marked by a later Add Contact.
#[tokio::test]
async fn a_lapsed_request_blocks_nothing() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("Anna").await;
    for n in 1..=5 {
        stored(&app, id, n, &format!("+479123456{n}"), 8).await;
    }
    let digest = crate::parent_events::waiting_count(
        &app.db,
        crate::parent_events::Kind::ContactRequest,
        id,
        Utc::now(),
    )
    .await
    .unwrap();
    assert_eq!(digest, Some(0));
    let page = app
        .get_page(&format!("/devices/{id}/calls?request={:032x}", 1), &cookie)
        .await
        .text();
    assert!(
        !page.contains("name=\"request\""),
        "a lapsed request isn't prefilled"
    );
    assert!(
        !page.contains(&format!("request-{:032x}", 1)),
        "nor on the card"
    );
    // A new request, and one for a lapsed request's number: both taken.
    assert_eq!(
        post(&app, &token, body(9, "Ny", "+4791234500")).await.1,
        json!({"accepted": true})
    );
    assert_eq!(
        post(&app, &token, body(10, "Venn igjen", "+4791234561"))
            .await
            .1,
        json!({"accepted": true})
    );
    // Add Contact with a lapsed number answers the live request, not the lapsed one.
    app.request_form(
        Method::POST,
        &format!("/devices/{id}/contacts"),
        Some(&cookie),
        &[
            [("name", "Venn"), ("number", "+4791234561")].as_slice(),
            &super::ADD_CONTACT_ALL_ON,
        ]
        .concat(),
    )
    .await;
    let states: Vec<(String, String)> = sqlx::query_as(
        "SELECT id, state FROM device_contact_requests WHERE device_id = ? AND number = '+4791234561' ORDER BY id",
    )
    .bind(id)
    .fetch_all(&app.db)
    .await
    .unwrap();
    assert_eq!(
        states,
        [
            (format!("{:032x}", 1), "open".to_string()),
            (format!("{:032x}", 10), "added".to_string())
        ]
    );
}

/// The live window counts from when the kid asked (`asked_at`), as the phone drops its record: a request asked 8 days
/// ago and received 3 days ago has lapsed everywhere - the policy, the card, the prefill, `known` - and its "Avslå"
/// lands on the card's notice, never on an anchor that isn't there; one asked 6 days ago and received yesterday is live.
#[tokio::test]
async fn the_window_counts_from_the_ask() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("Anna").await;
    for (n, number, asked, received) in [(1, "+4791234561", 8, 3), (2, "+4791234562", 6, 1)] {
        sqlx::query(
            "INSERT INTO device_contact_requests (device_id, id, name, number, asked_at, received_at) \
             VALUES (?, ?, 'Venn', ?, ?, ?)",
        )
        .bind(id)
        .bind(format!("{n:032x}"))
        .bind(number)
        .bind((Utc::now() - Duration::days(asked)).timestamp_millis())
        .bind((Utc::now() - Duration::days(received)).timestamp_millis())
        .execute(&app.db)
        .await
        .unwrap();
    }
    let policy = app.policy(&token).await["call_policy"]["contact_requests"].clone();
    assert_eq!(
        policy,
        json!([{"id": format!("{:032x}", 2), "state": "open"}])
    );
    let page = app
        .get_page(&format!("/devices/{id}/calls?request={:032x}", 1), &cookie)
        .await
        .text();
    assert!(!page.contains(&format!("request-{:032x}", 1)) && !page.contains("name=\"request\""));
    assert!(page.contains(&format!("request-{:032x}", 2)));
    assert_eq!(
        post(&app, &token, body(3, "Venn", "+4791234561")).await.1,
        json!({"accepted": true})
    );
    assert_eq!(
        post(&app, &token, body(4, "Venn", "+4791234562")).await.1,
        json!({"accepted": false, "reason": "known"})
    );
    let res = app
        .request_form(
            Method::POST,
            &format!("/devices/{id}/calls/requests/{:032x}/decline", 1),
            Some(&cookie),
            &[],
        )
        .await;
    assert_eq!(
        res.location(),
        Some(format!("/devices/{id}/calls?notice=request_gone#requests").as_str())
    );
    assert_eq!(
        rows(&app, id).await[0].4,
        "open",
        "a lapsed row isn't declined"
    );
}

/// QA M1: "Legg til…" for a number the family already has keeps that contact's name - prefilled and said - and the
/// save never renames it on the other phone: another typed name is refused in place with the kept one, judged by the
/// number in the field. Ticked, the Matrix ID sets the Message button to Element X (design 80 M6).
#[tokio::test]
async fn a_request_never_renames_a_family_contact() {
    let app = TestApp::new().await;
    steady_zone(&app).await;
    let cookie = app.admin_cookie().await;
    let (anna, anna_token) = app.enrolled_device("Anna").await;
    let (per, per_token) = app.enrolled_device("Per").await;
    sqlx::query("UPDATE device_policy SET calls_mode = 'managed' WHERE device_id = ?")
        .bind(per)
        .execute(&app.db)
        .await
        .unwrap();
    contact(&app, per, "Mamma", "+4790000001").await;
    let mut dum = body(1, "Dum", "900 00 001");
    dum["matrix_id"] = json!("@mamma:example.org");
    assert_eq!(post(&app, &anna_token, dum).await.1["accepted"], true);
    let add = app
        .get_page(
            &format!("/devices/{anna}/calls?request={:032x}", 1),
            &cookie,
        )
        .await
        .text();
    assert!(
        add.contains(
            r#"value="Mamma" maxlength="60" required aria-describedby="contact_name-kept">"#
        ),
        "{add}"
    );
    assert!(add.contains("Already a contact as «Mamma» - the name is kept. Anna called it «Dum»."));
    let request = format!("{:032x}", 1);
    let save = |name: &'static str, number: &'static str| {
        let (app, cookie, request) = (&app, &cookie, &request);
        async move {
            app.request_form(
                Method::POST,
                &format!("/devices/{anna}/contacts"),
                Some(cookie),
                &[
                    ("name", name),
                    ("number", number),
                    ("switches", "on"),
                    ("calls_shown", "custom"),
                    ("texts_shown", "custom"),
                    ("outbound", "on"),
                    ("request", request),
                    ("use_matrix", "on"),
                ],
            )
            .await
        }
    };
    // The kid's name typed back in: refused in place, the kept name in the field and why - never silently ignored.
    let refused = save("Dum", "+4790000001").await;
    assert_eq!(refused.status, StatusCode::BAD_REQUEST);
    let html = refused.text();
    assert!(
        html.contains(r#"value="Mamma" maxlength="60" required aria-invalid="true""#),
        "{html}"
    );
    assert!(html.contains("from a request its name is kept"));
    assert!(
        !html.contains("data-request-kept"),
        "one line for the field, not two"
    );
    // The hint follows the number in the field: another family number names its own contact.
    contact(&app, per, "Pappa", "+4790000002").await;
    let other = save("Dum", "+4790000002").await;
    assert!(other.text().contains(r#"value="Pappa""#));
    // Saved under the kept name.
    let res = save("Mamma", "+4790000001").await;
    assert!(res.status.is_redirection(), "{}", res.text());
    let name: String =
        sqlx::query_scalar("SELECT name FROM contacts WHERE phone_number = '+4790000001'")
            .fetch_one(&app.db)
            .await
            .unwrap();
    assert_eq!(name, "Mamma");
    let per_policy = app.policy(&per_token).await["call_policy"]["contacts"].clone();
    assert_eq!(per_policy[0]["name"], "Mamma");
    let (app_col, address): (Option<String>, Option<String>) = sqlx::query_as(
        "SELECT dc.message_app, dc.message_address FROM device_contacts dc JOIN contacts c ON c.id = dc.contact_id \
         WHERE dc.device_id = ? AND c.phone_number = '+4790000001'",
    )
    .bind(anna)
    .fetch_one(&app.db)
    .await
    .unwrap();
    assert_eq!(
        (app_col.as_deref(), address.as_deref()),
        (Some("element"), Some("@mamma:example.org"))
    );
}

/// QA L1-L3 and the L4 gaps on the form: one prefill source wins; a retry is `accepted` before any check; a short
/// number starts with calls and texts off and says the parent chooses; a decided request doesn't open Add Contact; a refused
/// Add Contact from a request keeps the request and the tick.
#[tokio::test]
async fn the_prefill_and_retry_rules() {
    let app = TestApp::new().await;
    steady_zone(&app).await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("Anna").await;
    let mut ola = body(1, "Ola", "+4791234567");
    ola["matrix_id"] = json!("@ola:example.org");
    assert_eq!(post(&app, &token, ola.clone()).await.1["accepted"], true);
    // L2: the same id, now past a week by its own clock: still the request the server holds.
    ola["asked_at_ms"] = json!((Utc::now() - Duration::days(8)).timestamp_millis());
    assert_eq!(post(&app, &token, ola).await.1, json!({"accepted": true}));
    // L1: a held number's link wins over a request's id.
    let mixed = app
        .get_page(
            &format!(
                "/devices/{id}/calls?request={:032x}&add_number=%2B4799999999",
                1
            ),
            &cookie,
        )
        .await
        .text();
    assert!(mixed.contains(r#"value="+4799999999""#));
    assert!(!mixed.contains("name=\"request\"") && !mixed.contains("name=\"use_matrix\""));
    // L3: a short number.
    assert_eq!(
        post(&app, &token, body(2, "Konkurranse", "2160")).await.1["accepted"],
        true
    );
    let short = app
        .get_page(&format!("/devices/{id}/calls?request={:032x}", 2), &cookie)
        .await
        .text();
    // The user (2026-11-09, design 80): the parent chooses - calls and texts both start off.
    for (switch, on) in [
        ("calls", false),
        ("texts", false),
        ("inbound", false),
        ("outbound", false),
        ("sms_in", false),
        ("sms_out", false),
        // Code QA L6: Home and pictures off too - every switch.
        ("mms", false),
        ("show_on_home", false),
    ] {
        assert_eq!(input_checked(&short, switch, "on"), Some(on), "{switch}");
    }
    assert!(short.contains("data-request-short"));
    // Design 113: the cost warning alone (the switches start off and show it).
    assert!(
        short.contains(r#"data-request-short>Short number - may cost money.</p>"#),
        "{short}"
    );
    // A refused save from the request keeps it and the tick.
    let request = format!("{:032x}", 1);
    let refused = app
        .request_form(
            Method::POST,
            &format!("/devices/{id}/contacts"),
            Some(&cookie),
            &[
                ("name", "Ola"),
                ("number", "12"),
                ("switches", "on"),
                ("calls_shown", "custom"),
                ("texts_shown", "custom"),
                ("request", &request),
                ("use_matrix", "on"),
            ],
        )
        .await;
    assert_eq!(refused.status, StatusCode::BAD_REQUEST);
    let html = refused.text();
    assert!(html.contains(&format!(r#"name="request" value="{request}""#)));
    assert_eq!(input_checked(&html, "use_matrix", "on"), Some(true));
    // A declined request doesn't open Add Contact.
    app.request_form(
        Method::POST,
        &format!("/devices/{id}/calls/requests/{request}/decline"),
        Some(&cookie),
        &[],
    )
    .await;
    let declined = app
        .get_page(&format!("/devices/{id}/calls?request={request}"), &cookie)
        .await
        .text();
    assert!(!declined.contains(r#"id="add-contact" open"#));
}

/// QA L4: decisions stay on their own phone - another phone's id lands on the notice and stays open, an unknown phone
/// is a 404, Anna's Add Contact with Per's number leaves Per's request open; "Add from the family" answers the request.
#[tokio::test]
async fn decisions_stay_on_their_phone() {
    let app = TestApp::new().await;
    steady_zone(&app).await;
    let cookie = app.admin_cookie().await;
    let (anna, _) = app.enrolled_device("Anna").await;
    let (per, per_token) = app.enrolled_device("Per").await;
    assert_eq!(
        post(&app, &per_token, body(1, "Ola", "+4791234567"))
            .await
            .1["accepted"],
        true
    );
    let pers = format!("{:032x}", 1);
    let res = app
        .request_form(
            Method::POST,
            &format!("/devices/{anna}/calls/requests/{pers}/decline"),
            Some(&cookie),
            &[],
        )
        .await;
    assert_eq!(
        res.location(),
        Some(format!("/devices/{anna}/calls?notice=request_gone#requests").as_str())
    );
    let unknown = app
        .request_form(
            Method::POST,
            &format!("/devices/9999/calls/requests/{pers}/decline"),
            Some(&cookie),
            &[],
        )
        .await;
    assert_eq!(unknown.status, StatusCode::NOT_FOUND);
    app.request_form(
        Method::POST,
        &format!("/devices/{anna}/contacts"),
        Some(&cookie),
        &[
            [("name", "Ola"), ("number", "+4791234567")].as_slice(),
            &super::ADD_CONTACT_ALL_ON,
        ]
        .concat(),
    )
    .await;
    assert_eq!(rows(&app, per).await[0].4, "open");
    // "Add from the family": Per gets the contact Anna has.
    let contact_id: i64 =
        sqlx::query_scalar("SELECT id FROM contacts WHERE phone_number = '+4791234567'")
            .fetch_one(&app.db)
            .await
            .unwrap();
    app.request_form(
        Method::POST,
        &format!("/devices/{per}/contacts"),
        Some(&cookie),
        &[("contact_id", &contact_id.to_string())],
    )
    .await;
    assert_eq!(rows(&app, per).await[0].4, "added");
}

/// QA L4: a name with markup is escaped in the inbox and plain text in the push; past the push budget (three an
/// hour, two days' caps across midnight) a request has no push of its own and the hour's digest counts what is live.
#[tokio::test]
async fn the_words_escape_and_the_budget_digests() {
    let app = TestApp::new().await;
    steady_zone(&app).await;
    let mamma = app.admin_cookie().await;
    subscribe(&app, &mamma, ENDPOINT).await;
    let (id, token) = app.enrolled_device("Anna").await;
    for n in 1..=3 {
        sqlx::query(
            "INSERT INTO parent_events (device_id, kind, dedup_key, title, summary, target, happened_at, \
             push_budget_key) VALUES (?, 'contact_request', ?, '', '', '/devices/1/calls', ?, 'request')",
        )
        .bind(id)
        .bind(format!("earlier{n}"))
        .bind(Utc::now().to_rfc3339())
        .execute(&app.db)
        .await
        .unwrap();
    }
    assert_eq!(
        post(&app, &token, body(1, "\"<&x", "+4791234567")).await.1["accepted"],
        true
    );
    let titles = |at| {
        let app = &app;
        async move {
            crate::push::send_due(&app.state, at).await.unwrap();
            app.pushes
                .take()
                .iter()
                .map(|r| {
                    super::web_push::payload(r)["notification"]["title"]
                        .as_str()
                        .unwrap()
                        .to_string()
                })
                .collect::<Vec<String>>()
        }
    };
    assert!(
        titles(Utc::now()).await.is_empty(),
        "past the budget: no push of its own"
    );
    assert_eq!(
        titles(Utc::now() + Duration::minutes(61)).await,
        ["Anna: 1 contact request is waiting"]
    );
    let inbox = app.get_page("/notifications", &mamma).await.text();
    assert!(
        inbox.contains("Anna asks for a new contact: &#34;&#60;&#38;x"),
        "{inbox}"
    );
    // Within the budget the title carries the name as plain text.
    sqlx::query("DELETE FROM parent_events WHERE dedup_key LIKE 'earlier%'")
        .execute(&app.db)
        .await
        .unwrap();
    assert_eq!(
        post(&app, &token, body(2, "\"<&y", "+4791234568")).await.1["accepted"],
        true
    );
    assert_eq!(
        titles(Utc::now()).await,
        ["Anna asks for a new contact: \"<&y"]
    );
}

/// Retention: a request 30 days after the server got it is gone, a newer one stays.
#[tokio::test]
async fn retention_keeps_thirty_days() {
    let app = TestApp::new().await;
    let (id, _) = app.enrolled_device("Anna").await;
    for (n, days) in [(1, 31), (2, 29)] {
        let at = (Utc::now() - Duration::days(days)).timestamp_millis();
        sqlx::query(
            "INSERT INTO device_contact_requests (device_id, id, name, number, asked_at, received_at) \
             VALUES (?, ?, 'Venn', '+4791234567', ?, ?)",
        )
        .bind(id)
        .bind(format!("{n:032x}"))
        .bind(at)
        .bind(at)
        .execute(&app.db)
        .await
        .unwrap();
    }
    let pruned = crate::retention::prune(&app.db).await;
    assert_eq!(pruned.contact_requests, 1);
    assert_eq!(rows(&app, id).await.len(), 1);
}

// Calls & Contacts tidied (6): the request's picture (`contact_request.json`'s `photo`).

/// A `w`×`h` picture in `format`, a gradient (generated: no real photo in the tree).
fn picture(w: u32, h: u32, format: image::ImageFormat) -> Vec<u8> {
    let image = image::DynamicImage::ImageRgb8(image::RgbImage::from_fn(w, h, |x, y| {
        image::Rgb([(x * 4) as u8, (y * 4) as u8, 120])
    }));
    let mut out = std::io::Cursor::new(Vec::new());
    image.write_to(&mut out, format).unwrap();
    out.into_inner()
}

/// The phone's upload of request `id`'s picture through `router` from `peer`; the status.
async fn upload_photo_via(
    app: &TestApp,
    router: &axum::Router,
    peer: std::net::SocketAddr,
    token: &str,
    id: &str,
    content_type: &str,
    body: Vec<u8>,
) -> StatusCode {
    let request = axum::http::Request::builder()
        .method(Method::POST)
        .uri(format!("/api/devices/contact-request/{id}/photo"))
        .header(axum::http::header::AUTHORIZATION, format!("Bearer {token}"))
        .header(axum::http::header::CONTENT_TYPE, content_type)
        .body(axum::body::Body::from(body))
        .unwrap();
    app.send_via(router, peer, request).await.status
}

/// The upload on the admin listener from loopback.
async fn upload_photo(
    app: &TestApp,
    token: &str,
    id: &str,
    content_type: &str,
    body: Vec<u8>,
) -> StatusCode {
    upload_photo_via(
        app,
        &app.router,
        super::LOOPBACK_PEER,
        token,
        id,
        content_type,
        body,
    )
    .await
}

type StoredPhoto = (Option<String>, Option<String>, Option<String>);

/// A request's stored picture: the square's hash, the original's and the view.
async fn request_photo(app: &TestApp, device: i64, n: u32) -> StoredPhoto {
    sqlx::query_as(
        "SELECT photo_hash, photo_original_hash, photo_view FROM device_contact_requests \
         WHERE device_id = ? AND id = ?",
    )
    .bind(device)
    .bind(format!("{n:032x}"))
    .fetch_one(&app.db)
    .await
    .unwrap()
}

fn stored_file(dir: &std::path::Path, hash: &str) -> bool {
    crate::photos::path_for(dir, hash).unwrap().exists()
}

/// Every server row of `photo.answers`, the limits the contract names, and both listeners: the admin listener from
/// loopback, and the phone listener straight from a tailnet address (no reverse proxy). Stored, the picture is the
/// contact photo's processing - a square JPEG, its original and view kept, the files on disk - and the card shows it.
#[tokio::test]
async fn the_photo_answers() {
    let app = TestApp::new().await;
    steady_zone(&app).await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("Anna").await;
    let (other, other_token) = app.enrolled_device("Per").await;
    let contract = contract();
    let photo = &contract["photo"];
    assert_eq!(
        photo["max_bytes"],
        json!(crate::handlers::contact_requests::PHOTO_MAX_BYTES)
    );
    assert_eq!(
        photo["max_px"],
        json!(crate::photos::REQUEST_PHOTO_MAX_SIDE)
    );
    assert_eq!(
        photo["content_types"],
        json!(crate::handlers::contact_requests::PHOTO_TYPES)
    );
    assert_eq!(photo["route"], "/api/devices/contact-request/{id}/photo");
    for (n, number) in [(1, "+4791234561"), (2, "+4791234562"), (3, "+4791234563")] {
        assert_eq!(
            post(&app, &token, body(n, "Venn", number)).await.1["accepted"],
            true
        );
    }
    assert_eq!(
        post(&app, &other_token, body(9, "Lur", "+4791234569"))
            .await
            .1["accepted"],
        true
    );
    app.request_form(
        Method::POST,
        &format!("/devices/{id}/calls/requests/{:032x}/decline", 3),
        Some(&cookie),
        &[],
    )
    .await;
    let send_px = photo["send_px"].as_u64().unwrap() as u32;
    let jpeg = picture(send_px, send_px, image::ImageFormat::Jpeg);
    let mut too_large = picture(8, 8, image::ImageFormat::Png);
    too_large.resize(crate::handlers::contact_requests::PHOTO_MAX_BYTES + 1, 0);
    let wide = picture(
        crate::photos::REQUEST_PHOTO_MAX_SIDE + 1,
        4,
        image::ImageFormat::Png,
    );
    let tailnet: std::net::SocketAddr = "100.101.102.103:50123".parse().unwrap();
    let device_router = app.device_router();
    let (first, second) = (format!("{:032x}", 1), format!("{:032x}", 2));
    for row in photo["answers"].as_array().unwrap() {
        let case = row["case"].as_str().unwrap();
        let want = StatusCode::from_u16(row["status"].as_u64().unwrap() as u16).unwrap();
        let (request, content_type, bytes) = match case {
            "stored" => (first.clone(), "image/jpeg", jpeg.clone()),
            "again" => (first.clone(), "image/jpeg", jpeg.clone()),
            "declined" => (format!("{:032x}", 3), "image/jpeg", jpeg.clone()),
            "another_phones" => (format!("{:032x}", 9), "image/jpeg", jpeg.clone()),
            "bad_id" => ("NOT-AN-ID".to_string(), "image/jpeg", jpeg.clone()),
            "wrong_type" => (second.clone(), "image/gif", jpeg.clone()),
            "too_large" => (second.clone(), "image/png", too_large.clone()),
            "undecodable" => (second.clone(), "image/png", b"not a picture".to_vec()),
            "too_many_pixels" => (second.clone(), "image/png", wide.clone()),
            other => panic!("a case the server doesn't make: {other}"),
        };
        let got = if case == "stored" {
            // On the phone listener straight from a tailnet address, as a phone without a proxy sends it.
            upload_photo_via(
                &app,
                &device_router,
                tailnet,
                &token,
                &request,
                content_type,
                bytes,
            )
            .await
        } else {
            upload_photo(&app, &token, &request, content_type, bytes).await
        };
        assert_eq!(got, want, "{case}");
    }
    // Stored as a parent's upload is: the square and its original on disk, the view a full cover.
    let (hash, original, view) = request_photo(&app, id, 1).await;
    let (hash, original) = (hash.unwrap(), original.unwrap());
    assert!(stored_file(&app.state.photo_dir, &hash));
    assert!(stored_file(&app.state.photo_original_dir, &original));
    assert_eq!(view.as_deref(), Some("0.000000,0.000000,1.000000,1.000000"));
    // Refused ones store nothing; another phone's request is untouched.
    assert_eq!(request_photo(&app, id, 2).await, (None, None, None));
    assert_eq!(request_photo(&app, other, 9).await, (None, None, None));
    // The card shows it in the request's avatar; a row without one shows its letter.
    let page = app
        .get_page(&format!("/devices/{id}/calls"), &cookie)
        .await
        .text();
    let row = request_html(&page, 1);
    assert!(
        row.contains(&format!("src=\"/contact-photos/{hash}\"")),
        "{row}"
    );
    assert!(!request_html(&page, 2).contains("data-request-photo"));
    assert!(request_html(&page, 2).contains(">V</span>"));
    // The phone may send a PNG too.
    assert_eq!(
        upload_photo(
            &app,
            &token,
            &second,
            "image/png",
            picture(64, 64, image::ImageFormat::Png)
        )
        .await,
        StatusCode::NO_CONTENT
    );
}

/// Add Contact from request `n` of phone `id`, with `name` and `number`.
async fn add_from(app: &TestApp, cookie: &str, id: i64, name: &str, number: &str, n: u32) {
    let request = format!("{n:032x}");
    let res = app
        .request_form(
            Method::POST,
            &format!("/devices/{id}/contacts"),
            Some(cookie),
            &[
                ("name", name),
                ("number", number),
                ("switches", "on"),
                ("calls_shown", "custom"),
                ("texts_shown", "custom"),
                ("inbound", "on"),
                ("outbound", "on"),
                ("sms_in", "on"),
                ("sms_out", "on"),
                ("request", &request),
            ],
        )
        .await;
    assert!(res.status.is_redirection(), "{}", res.text());
}

/// "Legg til…" from a request with a picture: Add Contact shows it, and the new contact's photo is it - the square, the
/// original and the view, as a parent's upload - which the phone gets; the request forgets it. A number the family
/// already has keeps its contact's own (no) photo, and the unused files go. "Avslå" drops the picture too.
#[tokio::test]
async fn the_photo_follows_legg_til() {
    let app = TestApp::new().await;
    steady_zone(&app).await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("Anna").await;
    let (sibling, _) = app.enrolled_device("Per").await;
    contact(&app, sibling, "Mormor", "+4791234570").await;
    for (n, name, number) in [
        (1, "Ola", "+4791234567"),
        (2, "Mormor", "+4791234570"),
        (3, "Kari", "+4791234580"),
    ] {
        assert_eq!(
            post(&app, &token, body(n, name, number)).await.1["accepted"],
            true
        );
        // Each its own picture, so each has its own files.
        let jpeg = picture(100 + 20 * n, 120, image::ImageFormat::Jpeg);
        assert_eq!(
            upload_photo(&app, &token, &format!("{n:032x}"), "image/jpeg", jpeg).await,
            StatusCode::NO_CONTENT
        );
    }
    let (hash, original, view) = request_photo(&app, id, 1).await;
    let hash = hash.unwrap();

    // Add Contact from the request shows the picture; for a number the family has, it doesn't.
    let add = app
        .get_page(&format!("/devices/{id}/calls?request={:032x}", 1), &cookie)
        .await
        .text();
    assert!(add.contains("data-request-photo-row"), "{add}");
    assert!(add.contains("Photo from Anna"));
    let family = app
        .get_page(&format!("/devices/{id}/calls?request={:032x}", 2), &cookie)
        .await
        .text();
    assert!(!family.contains("data-request-photo-row"));
    let nb = app
        .get_page(
            &format!("/devices/{id}/calls?request={:032x}&lang=nb", 1),
            &cookie,
        )
        .await
        .text();
    assert!(nb.contains("Bilde fra Anna"));

    add_from(&app, &cookie, id, "Ola", "+4791234567", 1).await;
    let photo: StoredPhoto = sqlx::query_as(
        "SELECT photo_hash, photo_original_hash, photo_view FROM contacts WHERE phone_number = '+4791234567'",
    )
    .fetch_one(&app.db)
    .await
    .unwrap();
    assert_eq!(photo, (Some(hash.clone()), original, view));
    assert_eq!(request_photo(&app, id, 1).await, (None, None, None));
    assert!(
        stored_file(&app.state.photo_dir, &hash),
        "the contact's now"
    );
    // The phone may fetch it now: it is a contact's photo on this phone.
    let res = app
        .request(
            Method::GET,
            &format!("/api/devices/contact-photos/{hash}"),
            Some(&token),
            None,
        )
        .await;
    assert_eq!(res.status, StatusCode::OK);

    // A family number: Mormor keeps no photo, the request's picture is gone from the row and the disk.
    let (mormor, mormor_original, _) = request_photo(&app, id, 2).await;
    let (mormor, mormor_original) = (mormor.unwrap(), mormor_original.unwrap());
    add_from(&app, &cookie, id, "Mormor", "+4791234570", 2).await;
    let kept: Option<String> =
        sqlx::query_scalar("SELECT photo_hash FROM contacts WHERE phone_number = '+4791234570'")
            .fetch_one(&app.db)
            .await
            .unwrap();
    assert_eq!(kept, None, "never on the other phones");
    assert_eq!(request_photo(&app, id, 2).await, (None, None, None));
    assert!(!stored_file(&app.state.photo_dir, &mormor));
    assert!(!stored_file(
        &app.state.photo_original_dir,
        &mormor_original
    ));

    // "Avslå": the picture goes with the decision.
    let (kari, kari_original, _) = request_photo(&app, id, 3).await;
    let (kari, kari_original) = (kari.unwrap(), kari_original.unwrap());
    assert!(stored_file(&app.state.photo_dir, &kari));
    app.request_form(
        Method::POST,
        &format!("/devices/{id}/calls/requests/{:032x}/decline", 3),
        Some(&cookie),
        &[],
    )
    .await;
    assert_eq!(request_photo(&app, id, 3).await, (None, None, None));
    assert!(!stored_file(&app.state.photo_dir, &kari));
    assert!(!stored_file(&app.state.photo_original_dir, &kari_original));
}

/// Retention: a request that lapsed (7 days from the ask) forgets its picture; a live one keeps it.
#[tokio::test]
async fn a_lapsed_requests_photo_goes() {
    let app = TestApp::new().await;
    let (id, _) = app.enrolled_device("Anna").await;
    stored(&app, id, 1, "+4791234567", 8).await;
    stored(&app, id, 2, "+4791234568", 1).await;
    for n in [1u32, 2] {
        sqlx::query(
            "UPDATE device_contact_requests SET photo_hash = ?, photo_original_hash = ?, photo_view = '0,0,1,1' \
             WHERE id = ?",
        )
        .bind(format!("{n:064x}"))
        .bind(format!("{:064x}", n + 10))
        .bind(format!("{n:032x}"))
        .execute(&app.db)
        .await
        .unwrap();
    }
    let pruned = crate::retention::prune(&app.db).await;
    assert_eq!(pruned.contact_request_photos, 1);
    assert_eq!(request_photo(&app, id, 1).await, (None, None, None));
    assert_eq!(
        request_photo(&app, id, 2).await.0,
        Some(format!("{:064x}", 2))
    );
}
