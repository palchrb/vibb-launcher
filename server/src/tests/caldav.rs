//! CalDAV, read-only (design 72 step 2): a fake CalDAV server in the test source (`CannedFetch::answer_method`,
//! `CannedFetch::basic`) answering as iCloud does (`testdata/caldav/icloud_*.xml`: a redirect chain at
//! `/.well-known/caldav`, the calendars on another host) and Nextcloud (`nextcloud_calendars.xml`). Test and its
//! answers, the hosts the password goes to, the ticks and the phone's policy, the ctag, a refused password and a new
//! host stopping the account (QA M5), Test's gap, removal.

use axum::http::{Method, StatusCode};
use chrono::Utc;

use super::TestApp;
use super::music::device_get;
use crate::caldav;
use crate::calendar_feeds::{self, Outcome};

const SERVER: &str = "dav.example.net";
const USER: &str = "forelder@example.net";
/// A throwaway value, nothing like a real app password.
const PASSWORD: &str = "test-only-not-a-password";
const LIST: &str = "https://p07-dav.example.net/1000000001/calendars/";
const HOME: &str = "https://p07-dav.example.net/1000000001/calendars/home/";
const FRITID: &str = "https://p07-dav.example.net/1000000001/calendars/fritid-AAAA-1111/";

fn fixture(name: &str) -> Vec<u8> {
    std::fs::read(format!("testdata/caldav/{name}")).unwrap()
}

fn report() -> Vec<u8> {
    let soon = (Utc::now() + chrono::TimeDelta::days(3))
        .format("%Y%m%d")
        .to_string();
    String::from_utf8(fixture("report.xml"))
        .unwrap()
        .replace("{SOON}", &soon)
        .into_bytes()
}

fn ctag(tag: &str) -> String {
    format!(
        r#"<?xml version="1.0"?><d:multistatus xmlns:d="DAV:" xmlns:cs="http://calendarserver.org/ns/"><d:response><d:href>/1000000001/calendars/home/</d:href><d:propstat><d:prop><cs:getctag>{tag}</cs:getctag></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response></d:multistatus>"#
    )
}

/// An iCloud-like server: `/.well-known/caldav` → `/caldav/` → `/`, the principal there, the calendars on another
/// host; both hosts ask for the password (a 401 without it).
fn fake_icloud(app: &TestApp) {
    let f = &app.fetch;
    f.basic(SERVER, USER, PASSWORD);
    f.basic("p07-dav.example.net", USER, PASSWORD);
    f.redirect_method(
        "PROPFIND",
        "https://dav.example.net/.well-known/caldav",
        301,
        "/caldav/",
    );
    f.redirect_method(
        "PROPFIND",
        "https://dav.example.net/caldav/",
        302,
        "https://dav.example.net/",
    );
    f.answer_method(
        "PROPFIND",
        "https://dav.example.net/",
        207,
        fixture("icloud_start.xml"),
    );
    f.answer_method(
        "PROPFIND",
        "https://dav.example.net/1000000001/principal/",
        207,
        fixture("icloud_principal.xml"),
    );
    f.answer_method("PROPFIND", LIST, 207, fixture("icloud_calendars.xml"));
    f.answer_method("PROPFIND", HOME, 207, ctag("ctag-1"));
    f.answer_method("REPORT", HOME, 207, report());
}

async fn test_form(
    app: &TestApp,
    cookie: &str,
    device: i64,
    server: &str,
    password: &str,
) -> super::TestResponse {
    app.request_form(
        Method::POST,
        &format!("/devices/{device}/calendar/caldav"),
        Some(cookie),
        &[
            ("server", server),
            ("username", USER),
            ("password", password),
        ],
    )
    .await
}

async fn account_id(app: &TestApp) -> i64 {
    sqlx::query_scalar("SELECT id FROM caldav_accounts")
        .fetch_one(&app.db)
        .await
        .unwrap()
}

async fn account_state(app: &TestApp) -> String {
    sqlx::query_scalar("SELECT state FROM caldav_accounts")
        .fetch_one(&app.db)
        .await
        .unwrap()
}

/// Ticks exactly `on` of the two calendars on `device`.
async fn tick(
    app: &TestApp,
    cookie: &str,
    device: i64,
    account: i64,
    on: &[&str],
) -> super::TestResponse {
    let (home, fritid) = (caldav::calendar_id(HOME), caldav::calendar_id(FRITID));
    let mut fields: Vec<(String, String)> = vec![
        ("cal".to_string(), home.clone()),
        ("cal".to_string(), fritid.clone()),
    ];
    for href in on {
        fields.push((
            format!("on_{}", caldav::calendar_id(href)),
            "on".to_string(),
        ));
    }
    let fields: Vec<(&str, &str)> = fields
        .iter()
        .map(|(k, v)| (k.as_str(), v.as_str()))
        .collect();
    app.request_form(
        Method::POST,
        &format!("/devices/{device}/calendar/caldav/{account}/calendars"),
        Some(cookie),
        &fields,
    )
    .await
}

fn home_uid() -> String {
    caldav::uid_of("https://dav.example.net/", USER, HOME)
}

async fn home_source(app: &TestApp) -> i64 {
    sqlx::query_scalar("SELECT id FROM calendar_sources WHERE uid = ?")
        .bind(home_uid())
        .fetch_one(&app.db)
        .await
        .unwrap()
}

/// Requests since `from`: (method, url, carried the password).
fn requests_since(app: &TestApp, from: usize) -> Vec<(String, String, bool)> {
    app.fetch.requests.lock().unwrap()[from..]
        .iter()
        .map(|r| {
            (
                r.method.clone(),
                r.url.clone(),
                r.header("authorization").is_some(),
            )
        })
        .collect()
}

#[tokio::test]
async fn test_lists_the_calendars_and_where_the_password_goes() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, _) = app.enrolled_device("Anna").await;
    fake_icloud(&app);
    let res = test_form(&app, &cookie, id, SERVER, &format!(" {PASSWORD} ")).await;
    assert_eq!(res.status, StatusCode::SEE_OTHER, "{}", res.text());
    let account = account_id(&app).await;
    assert_eq!(
        res.location(),
        Some(format!("/devices/{id}/calendar#caldav-{account}").as_str())
    );
    app.assert_lands(&res, &cookie).await;
    // One hop at a time: each host gets the password only after it asked for it, and no request is followed by
    // the client itself.
    let asked = requests_since(&app, 0);
    assert_eq!(
        asked,
        [
            (
                "PROPFIND",
                "https://dav.example.net/.well-known/caldav",
                false
            ),
            (
                "PROPFIND",
                "https://dav.example.net/.well-known/caldav",
                true
            ),
            ("PROPFIND", "https://dav.example.net/caldav/", true),
            ("PROPFIND", "https://dav.example.net/", true),
            (
                "PROPFIND",
                "https://dav.example.net/1000000001/principal/",
                true
            ),
            ("PROPFIND", LIST, false),
            ("PROPFIND", LIST, true),
        ]
        .map(|(m, u, a)| (m.to_string(), u.to_string(), a))
    );
    assert!(app.fetch.requests.lock().unwrap().iter().all(|r| !r.follow));
    let list = app.fetch.requests.lock().unwrap().last().unwrap().clone();
    assert_eq!(list.header("depth"), Some("1"));
    // Never a calendar link, never a save.
    assert_eq!(
        sqlx::query_scalar::<_, i64>("SELECT COUNT(*) FROM calendar_sources")
            .fetch_one(&app.db)
            .await
            .unwrap(),
        0
    );
    let saved: i64 = sqlx::query_scalar(
        "SELECT COUNT(*) FROM security_events WHERE event_type = 'settings_saved'",
    )
    .fetch_one(&app.db)
    .await
    .unwrap();
    assert_eq!(saved, 0);
    // Sealed: the password is in no column as written.
    let (sealed, state): (Vec<u8>, String) =
        sqlx::query_as("SELECT password_sealed, state FROM caldav_accounts")
            .fetch_one(&app.db)
            .await
            .unwrap();
    assert_eq!(state, "ok");
    assert!(
        !sealed
            .windows(PASSWORD.len())
            .any(|w| w == PASSWORD.as_bytes())
    );

    let page = app
        .get_page(&format!("/devices/{id}/calendar"), &cookie)
        .await
        .text();
    assert!(!page.contains(PASSWORD));
    let at = page.find(&format!("data-caldav=\"{account}\"")).unwrap();
    let cell = &page[at..at + page[at..].find("</details>").unwrap()];
    assert!(
        cell.contains(USER) && cell.contains(">dav.example.net<"),
        "{cell}"
    );
    assert!(cell.contains("Signed in"), "{cell}");
    assert!(cell.contains("The password goes only to: dav.example.net, p07-dav.example.net"));
    // The calendars with events: "Påminnelser" (tasks only) and the inbox are not offered.
    assert!(
        cell.contains(">Hjem<") && cell.contains(">Fotball &#38; korps<"),
        "{cell}"
    );
    assert!(!cell.contains("Påminnelser") && !cell.contains("inbox"));
    assert!(!cell.contains(" checked"));
    assert!(cell.contains(&format!("name=\"on_{}\"", caldav::calendar_id(HOME))));
    assert!(
        !cell.contains("p07-dav.example.net/1000000001"),
        "no address in the form"
    );
    let nb = app
        .get_page(&format!("/devices/{id}/calendar?lang=nb"), &cookie)
        .await
        .text();
    assert!(nb.contains("Passordet sendes bare til: dav.example.net, p07-dav.example.net"));
    assert!(nb.contains("Logget inn"));
}

#[tokio::test]
async fn a_ticked_calendar_reaches_the_phone_and_an_unchanged_ctag_asks_no_more() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (anna, token) = app.enrolled_device("Anna").await;
    let (ola, ola_token) = app.enrolled_device("Ola").await;
    fake_icloud(&app);
    test_form(&app, &cookie, anna, SERVER, PASSWORD).await;
    let account = account_id(&app).await;
    let mut nudges = app.state.command_notify.subscribe();
    let before = app.fetch.count();
    let res = tick(&app, &cookie, anna, account, &[HOME]).await;
    assert_eq!(res.status, StatusCode::SEE_OTHER, "{}", res.text());
    assert_eq!(
        res.location(),
        Some(format!("/devices/{anna}/calendar#caldav-{account}").as_str())
    );
    assert_eq!(nudges.try_recv().unwrap(), anna);
    // Fetched before the answer: the ctag, then the report - the password only to the confirmed hosts.
    let asked = requests_since(&app, before);
    assert_eq!(
        asked,
        [("PROPFIND", HOME, true), ("REPORT", HOME, true)].map(|(m, u, a)| (
            m.to_string(),
            u.to_string(),
            a
        ))
    );
    let report = app.fetch.requests.lock().unwrap().last().unwrap().clone();
    let body = String::from_utf8(report.body.unwrap()).unwrap();
    assert!(body.contains("<c:time-range start=\"") && body.contains("name=\"VEVENT\""));

    // The phone gets it exactly as a link: the policy's feeds, the endpoint's set.
    let uid = home_uid();
    let policy = app.policy(&token).await;
    let feeds = policy["calendar"]["feeds"].as_array().unwrap();
    assert_eq!(feeds.len(), 1);
    assert_eq!(feeds[0]["id"], uid.as_str());
    assert_eq!(feeds[0]["name"], "Hjem");
    let served = device_get(
        &app,
        &format!("/api/devices/calendar/feeds/{uid}"),
        &token,
        &[],
    )
    .await
    .json();
    let summaries: Vec<&str> = served["events"]
        .as_array()
        .unwrap()
        .iter()
        .map(|e| e["summary"].as_str().unwrap())
        .collect();
    assert_eq!(summaries, ["Tannlege & kontroll", "Fotballtrening"]);
    let (bad, events): (i64, i64) =
        sqlx::query_as("SELECT bad, events FROM calendar_sources WHERE uid = ?")
            .bind(&uid)
            .fetch_one(&app.db)
            .await
            .unwrap();
    assert_eq!(
        (bad, events),
        (1, 2),
        "the broken resource counted, not served"
    );
    assert!(
        app.policy(&ola_token).await["calendar"]["feeds"]
            .as_array()
            .unwrap()
            .is_empty()
    );
    // The link rows say what it is; the settings log names the route and ids only.
    let page = app
        .get_page(&format!("/devices/{anna}/calendar"), &cookie)
        .await
        .text();
    assert!(page.contains(&format!("CalDAV · {USER} · p07-dav.example.net")));
    assert!(page.contains("Fetched") && page.contains("2 events"));
    let details: Vec<String> = sqlx::query_scalar(
        "SELECT COALESCE(detail, '') FROM security_events WHERE event_type = 'settings_saved'",
    )
    .fetch_all(&app.db)
    .await
    .unwrap();
    assert_eq!(details.len(), 1);
    assert!(
        details[0].contains("/calendar/caldav/{account}/calendars"),
        "{details:?}"
    );
    assert!(!details[0].contains(USER) && !details[0].contains("example.net"));

    // The same ctag: one PROPFIND, no report.
    let source = home_source(&app).await;
    let before = app.fetch.count();
    let outcome = calendar_feeds::refresh(&app.state, source, Utc::now())
        .await
        .unwrap();
    assert_eq!(outcome, Outcome::Unchanged);
    assert_eq!(
        requests_since(&app, before),
        [("PROPFIND".to_string(), HOME.to_string(), true)]
    );
    // A changed ctag: the report again, a new rev, the phone nudged.
    while nudges.try_recv().is_ok() {}
    app.fetch
        .answer_method("PROPFIND", HOME, 207, ctag("ctag-2"));
    let changed =
        String::from_utf8(report_body_with("Fotballtrening", "Fotball (flyttet)")).unwrap();
    app.fetch.answer_method("REPORT", HOME, 207, changed);
    let outcome = calendar_feeds::refresh(&app.state, source, Utc::now())
        .await
        .unwrap();
    assert_eq!(outcome, Outcome::Changed);
    assert_eq!(nudges.try_recv().unwrap(), anna);

    // The sibling ticks it too: the same source, no fetch.
    let before = app.fetch.count();
    tick(&app, &cookie, ola, account, &[HOME]).await;
    assert_eq!(app.fetch.count(), before);
    assert_eq!(
        app.policy(&ola_token).await["calendar"]["feeds"][0]["id"],
        uid.as_str()
    );
    // Unticked on both: the source goes, and the account with it (its last calendar).
    tick(&app, &cookie, anna, account, &[]).await;
    assert!(
        app.policy(&token).await["calendar"]["feeds"]
            .as_array()
            .unwrap()
            .is_empty()
    );
    tick(&app, &cookie, ola, account, &[]).await;
    let left: (i64, i64) = sqlx::query_as(
        "SELECT (SELECT COUNT(*) FROM calendar_sources), (SELECT COUNT(*) FROM caldav_accounts)",
    )
    .fetch_one(&app.db)
    .await
    .unwrap();
    assert_eq!(left, (0, 0));
}

fn report_body_with(from: &str, to: &str) -> Vec<u8> {
    String::from_utf8(report())
        .unwrap()
        .replace(from, to)
        .into_bytes()
}

#[tokio::test]
async fn a_refused_password_stops_the_account_until_test_passes() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (anna, _) = app.enrolled_device("Anna").await;
    fake_icloud(&app);
    test_form(&app, &cookie, anna, SERVER, PASSWORD).await;
    let account = account_id(&app).await;
    tick(&app, &cookie, anna, account, &[HOME, FRITID]).await;
    let source = home_source(&app).await;
    // The app password is revoked on the server.
    app.fetch.basic(SERVER, USER, "revoked");
    app.fetch.basic("p07-dav.example.net", USER, "revoked");
    let before = app.fetch.count();
    let outcome = calendar_feeds::refresh(&app.state, source, Utc::now())
        .await
        .unwrap();
    assert!(matches!(outcome, Outcome::Failed(ref e) if e.code == "login_refused"));
    assert_eq!(app.fetch.count(), before + 1, "no retry after a 401");
    assert_eq!(account_state(&app).await, "login_refused");
    // No request for any of its calendars now (hourly retries can lock an Apple ID).
    let others: Vec<i64> = sqlx::query_scalar("SELECT id FROM calendar_sources")
        .fetch_all(&app.db)
        .await
        .unwrap();
    for id in others {
        let outcome = calendar_feeds::refresh(&app.state, id, Utc::now())
            .await
            .unwrap();
        assert!(matches!(outcome, Outcome::Failed(ref e) if e.code == "login_refused"));
    }
    assert_eq!(app.fetch.count(), before + 1);
    // Every row says so at once, and the account.
    let page = app
        .get_page(&format!("/devices/{anna}/calendar"), &cookie)
        .await
        .text();
    assert_eq!(
        page.matches("data-link-failing>The password was refused. Use an app password")
            .count(),
        2
    );
    assert!(page.contains("data-caldav-failing>The password was refused - nothing is fetched"));

    // Test again at once: too soon (the last Test was moments ago), nothing asked.
    let again = format!("/devices/{anna}/calendar/caldav/{account}/test");
    let res = app
        .request_form(Method::POST, &again, Some(&cookie), &[("password", "")])
        .await;
    assert_eq!(
        res.location(),
        Some(format!("/devices/{anna}/calendar?notice=caldav_wait#caldav-{account}").as_str())
    );
    assert_eq!(app.fetch.count(), before + 1);
    let wait = app.get_page(res.location().unwrap(), &cookie).await.text();
    assert!(wait.contains("Tested a moment ago - wait a few seconds."));
    // With the stored (revoked) password after the gap: one challenge, one refusal, no retry.
    let past = crate::music_sweep::stamp(Utc::now() - chrono::TimeDelta::seconds(11));
    sqlx::query("UPDATE caldav_accounts SET tested_at = ?")
        .bind(&past)
        .execute(&app.db)
        .await
        .unwrap();
    let before = app.fetch.count();
    app.request_form(Method::POST, &again, Some(&cookie), &[("password", "")])
        .await;
    assert_eq!(
        requests_since(&app, before),
        [
            (
                "PROPFIND",
                "https://dav.example.net/.well-known/caldav",
                false
            ),
            (
                "PROPFIND",
                "https://dav.example.net/.well-known/caldav",
                true
            ),
        ]
        .map(|(m, u, a)| (m.to_string(), u.to_string(), a))
    );
    assert_eq!(account_state(&app).await, "login_refused");
    // A new password: Test passes, the calendars are due at once and fetched again.
    sqlx::query("UPDATE caldav_accounts SET tested_at = ?")
        .bind(&past)
        .execute(&app.db)
        .await
        .unwrap();
    app.fetch.basic(SERVER, USER, PASSWORD);
    app.fetch.basic("p07-dav.example.net", USER, PASSWORD);
    app.request_form(
        Method::POST,
        &again,
        Some(&cookie),
        &[("password", PASSWORD)],
    )
    .await;
    assert_eq!(account_state(&app).await, "ok");
    let due: i64 = sqlx::query_scalar(
        "SELECT COUNT(*) FROM calendar_sources WHERE next_at <= datetime('now')",
    )
    .fetch_one(&app.db)
    .await
    .unwrap();
    assert_eq!(due, 2);
    app.fetch
        .answer_method("PROPFIND", HOME, 207, ctag("ctag-3"));
    let outcome = calendar_feeds::refresh(&app.state, source, Utc::now())
        .await
        .unwrap();
    assert_eq!(outcome, Outcome::Unchanged, "the same set, fetched again");
    let page = app
        .get_page(&format!("/devices/{anna}/calendar"), &cookie)
        .await
        .text();
    assert!(!page.contains("data-caldav-failing"));
}

#[tokio::test]
async fn another_host_stops_the_account_before_any_request_to_it() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (anna, _) = app.enrolled_device("Anna").await;
    fake_icloud(&app);
    test_form(&app, &cookie, anna, SERVER, PASSWORD).await;
    let account = account_id(&app).await;
    tick(&app, &cookie, anna, account, &[HOME]).await;
    let source = home_source(&app).await;
    app.fetch.redirect_method(
        "PROPFIND",
        HOME,
        301,
        "https://p09-dav.example.net/1000000001/calendars/home/",
    );
    let outcome = calendar_feeds::refresh(&app.state, source, Utc::now())
        .await
        .unwrap();
    assert!(matches!(outcome, Outcome::Failed(ref e) if e.code == "host_changed"));
    assert!(
        !app.fetch
            .calls
            .lock()
            .unwrap()
            .iter()
            .any(|u| u.contains("p09-dav")),
        "never a request to the new host"
    );
    assert_eq!(account_state(&app).await, "host_changed");
    let page = app
        .get_page(&format!("/devices/{anna}/calendar?lang=nb"), &cookie)
        .await
        .text();
    assert!(
        page.contains("Serveren svarte fra en ny vert - ingenting hentes før du tester på nytt")
    );
    // Test again (the stored password): the server lists the calendars where they were - fine again.
    sqlx::query("UPDATE caldav_accounts SET tested_at = NULL")
        .execute(&app.db)
        .await
        .unwrap();
    app.fetch
        .answer_method("PROPFIND", HOME, 207, ctag("ctag-1"));
    app.request_form(
        Method::POST,
        &format!("/devices/{anna}/calendar/caldav/{account}/test"),
        Some(&cookie),
        &[("password", "")],
    )
    .await;
    assert_eq!(account_state(&app).await, "ok");
}

#[tokio::test]
async fn a_refused_test_renders_the_form_as_typed_and_never_the_password() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, _) = app.enrolled_device("Anna").await;
    for url in [
        "https://familie.example/.well-known/caldav",
        "https://familie.example/",
    ] {
        app.fetch
            .answer_method("PROPFIND", url, 404, "<html>Familien</html>");
    }
    for (server, password, why) in [
        ("", PASSWORD, "Type the server&#39;s address"),
        (
            "https://u:p@dav.example.net/",
            PASSWORD,
            "in their own fields",
        ),
        (SERVER, "", "Type the app password."),
        // Plain http to a public name.
        (
            "http://dav.example.org/",
            PASSWORD,
            "Plain http to the internet is refused",
        ),
        // An e-mail's domain whose web host has no CalDAV and never asks for the password: it never gets it.
        (
            "forelder@familie.example",
            PASSWORD,
            "No CalDAV calendars were found",
        ),
    ] {
        let res = test_form(&app, &cookie, id, server, password).await;
        assert_eq!(res.status, StatusCode::BAD_REQUEST, "{server}");
        let page = res.text();
        assert!(page.contains(why), "{server}: {page}");
        assert!(page.contains("data-flash=\"caldav-refused\""));
        assert!(
            page.contains(&format!("value=\"{USER}\"")),
            "the form as typed"
        );
        assert!(!page.contains(PASSWORD), "never the password");
    }
    assert!(
        app.fetch
            .requests
            .lock()
            .unwrap()
            .iter()
            .all(|r| r.header("authorization").is_none())
    );
    // A failed first Test keeps no password and isn't listed.
    let kept: Vec<(Vec<u8>, String)> =
        sqlx::query_as("SELECT password_sealed, state FROM caldav_accounts")
            .fetch_all(&app.db)
            .await
            .unwrap();
    assert!(
        kept.iter().all(|(p, s)| p.is_empty() && s == "untested"),
        "{}",
        kept.len()
    );
    assert!(caldav::accounts(&app.db).await.unwrap().is_empty());
    // ... and goes a day after its Test.
    let later = Utc::now() + chrono::TimeDelta::hours(caldav::UNUSED_HOURS + 1);
    assert_eq!(
        caldav::sweep(&app.db, later).await.unwrap(),
        kept.len() as u64
    );

    // An account with calendars of tasks only.
    fake_icloud(&app);
    let tasks_only = String::from_utf8(fixture("icloud_calendars.xml"))
        .unwrap()
        .replace("<comp name=\"VEVENT\"/>", "<comp name=\"VTODO\"/>");
    app.fetch.answer_method("PROPFIND", LIST, 207, tasks_only);
    let res = test_form(&app, &cookie, id, SERVER, PASSWORD).await;
    assert!(
        res.text()
            .contains("The account has no calendars with events.")
    );

    // Without the music key: no form.
    let mut state = app.state.clone();
    state.music_key = None;
    let page = super::read_response(
        crate::handlers::calendar::view_calendar(
            axum::extract::State(state),
            crate::i18n::Lang::En,
            axum::extract::Path(id),
            axum::extract::Query(Default::default()),
        )
        .await,
    )
    .await
    .text();
    assert!(
        page.contains("data-caldav-form=\"off\"") && !page.contains("data-caldav-form=\"add\"")
    );
}

/// Nextcloud: the address typed with its path, the principal and the home set on the same host, a calendar named
/// only by its path (decoded), the inbox and the trash bin left out.
#[tokio::test]
async fn nextcloud_is_found_by_its_path() {
    let app = TestApp::new().await;
    let f = &app.fetch;
    f.basic("cloud.example.org", "forelder", PASSWORD);
    let ms = |href: &str, prop: &str| {
        format!(
            r#"<d:multistatus xmlns:d="DAV:" xmlns:cal="urn:ietf:params:xml:ns:caldav"><d:response><d:href>{href}</d:href><d:propstat><d:prop>{prop}</d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response></d:multistatus>"#
        )
    };
    f.answer_method(
        "PROPFIND",
        "https://cloud.example.org/remote.php/dav",
        207,
        ms(
            "/remote.php/dav/",
            "<d:current-user-principal><d:href>/remote.php/dav/principals/users/forelder/</d:href></d:current-user-principal>",
        ),
    );
    f.answer_method(
        "PROPFIND",
        "https://cloud.example.org/remote.php/dav/principals/users/forelder/",
        207,
        ms(
            "/remote.php/dav/principals/users/forelder/",
            "<cal:calendar-home-set><d:href>/remote.php/dav/calendars/forelder/</d:href></cal:calendar-home-set>",
        ),
    );
    f.answer_method(
        "PROPFIND",
        "https://cloud.example.org/remote.php/dav/calendars/forelder/",
        207,
        fixture("nextcloud_calendars.xml"),
    );
    let (server, _) = caldav::normalize_server("cloud.example.org/remote.php/dav").unwrap();
    let found = caldav::discover(
        app.state.music_fetch.as_ref(),
        &server,
        false,
        caldav::Login {
            username: "forelder",
            password: PASSWORD,
        },
    )
    .await
    .unwrap();
    let names: Vec<&str> = found.calendars.iter().map(|c| c.name.as_str()).collect();
    assert_eq!(names, ["Personlig", "skole og aktiviteter"]);
    assert_eq!(
        found.calendars[1].href,
        "https://cloud.example.org/remote.php/dav/calendars/forelder/skole%20og%20aktiviteter/"
    );
    assert_eq!(found.hosts, ["cloud.example.org"]);
    assert_eq!(
        found.home_set,
        "https://cloud.example.org/remote.php/dav/calendars/forelder/"
    );
}

#[tokio::test]
async fn removing_the_account_takes_its_calendars_off_every_phone() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (anna, token) = app.enrolled_device("Anna").await;
    let (ola, _) = app.enrolled_device("Ola").await;
    fake_icloud(&app);
    test_form(&app, &cookie, anna, SERVER, PASSWORD).await;
    let account = account_id(&app).await;
    tick(&app, &cookie, anna, account, &[HOME]).await;
    tick(&app, &cookie, ola, account, &[FRITID]).await;
    let mut nudges = app.state.command_notify.subscribe();
    let res = app
        .request_form(
            Method::POST,
            &format!("/devices/{ola}/calendar/caldav/{account}/remove"),
            Some(&cookie),
            &[],
        )
        .await;
    assert_eq!(
        res.location(),
        Some(format!("/devices/{ola}/calendar#caldav").as_str())
    );
    let mut nudged = vec![nudges.try_recv().unwrap(), nudges.try_recv().unwrap()];
    nudged.sort();
    assert_eq!(nudged, [anna, ola]);
    let left: (i64, i64, i64) = sqlx::query_as(
        "SELECT (SELECT COUNT(*) FROM calendar_sources), (SELECT COUNT(*) FROM device_calendar_feeds), \
         (SELECT COUNT(*) FROM caldav_accounts)",
    )
    .fetch_one(&app.db)
    .await
    .unwrap();
    assert_eq!(left, (0, 0, 0));
    assert!(
        app.policy(&token).await["calendar"]["feeds"]
            .as_array()
            .unwrap()
            .is_empty()
    );
}
