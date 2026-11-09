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
    assert!(add.contains("Anna suggested @ola:example.org"));
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
    let added = request_html(&page, 4);
    assert!(
        added.contains("Added") && !added.contains("<form"),
        "{added}"
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
        Some(format!("/devices/{id}/calls#request-{request}").as_str())
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
    let row = request_html(&page, 1);
    assert!(row.contains("Declined") && !row.contains("<form"), "{row}");
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
        &[("name", "Ola 2"), ("number", "91234568")],
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
