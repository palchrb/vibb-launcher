//! Calendar links (design 72 step 1): the add form and its refusals, the page's status lines and "Calendars shown",
//! the phone's endpoint, the policy, the fetch cycle (304, the empty hold, the nudge, the backoff, a removal
//! mid-fetch, churn, the address rules), Remove and a deleted phone.

use axum::http::{Method, StatusCode, header};
use chrono::{TimeDelta, Timelike, Utc};
use serde_json::{Value, json};

use super::TestApp;
use super::music::{Canned, device_get};
use crate::calendar_feeds::{self, Outcome};

const LINK: &str = "https://skole.example.no/kalender/5b.ics?token=FAKE-TOKEN";

fn contract_body() -> Vec<u8> {
    std::fs::read("testdata/calendar_feeds/contract.ics").unwrap()
}

fn one_event(uid: &str, summary: &str) -> String {
    format!(
        "BEGIN:VCALENDAR\r\nBEGIN:VEVENT\r\nUID:{uid}\r\nDTSTART:{}T080000Z\r\nSUMMARY:{summary}\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n",
        (Utc::now() + TimeDelta::days(3)).format("%Y%m%d")
    )
}

const EMPTY: &str = "BEGIN:VCALENDAR\r\nX-WR-CALNAME:Tom\r\nEND:VCALENDAR\r\n";

async fn add(
    app: &TestApp,
    cookie: &str,
    device: i64,
    url: &str,
    name: &str,
) -> super::TestResponse {
    app.request_form(
        Method::POST,
        &format!("/devices/{device}/calendar/links"),
        Some(cookie),
        &[("url", url), ("name", name)],
    )
    .await
}

async fn source_id(app: &TestApp, uid: &str) -> Option<i64> {
    sqlx::query_scalar("SELECT id FROM calendar_sources WHERE uid = ?")
        .bind(uid)
        .fetch_optional(&app.db)
        .await
        .unwrap()
}

async fn sources(app: &TestApp) -> i64 {
    sqlx::query_scalar("SELECT COUNT(*) FROM calendar_sources")
        .fetch_one(&app.db)
        .await
        .unwrap()
}

fn uid() -> String {
    calendar_feeds::uid_of(&calendar_feeds::normalize(LINK).unwrap())
}

#[tokio::test]
async fn a_link_reaches_the_policy_and_the_phone() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("Anna").await;
    let (other, other_token) = app.enrolled_device("Ola").await;
    app.fetch.answer(LINK, 200, contract_body());
    let mut nudges = app.state.command_notify.subscribe();
    // The name left empty: the feed's X-WR-CALNAME.
    let res = add(
        &app,
        &cookie,
        id,
        &LINK.replace("https://", "webcal://"),
        "",
    )
    .await;
    assert_eq!(res.status, StatusCode::SEE_OTHER, "{}", res.text());
    let uid = uid();
    assert_eq!(
        res.location(),
        Some(format!("/devices/{id}/calendar#link-{uid}").as_str())
    );
    app.assert_lands(&res, &cookie).await;
    assert_eq!(nudges.try_recv().unwrap(), id);

    // The policy names it; the endpoint serves the set under its rev.
    let policy = app.policy(&token).await;
    let feeds = policy["calendar"]["feeds"].as_array().unwrap();
    assert_eq!(feeds.len(), 1);
    let rev = feeds[0]["rev"].as_str().unwrap().to_string();
    assert_eq!(feeds[0], json!({"id": uid, "name": "Skolen", "rev": rev}));
    let path = format!("/api/devices/calendar/feeds/{uid}");
    let res = device_get(&app, &path, &token, &[]).await;
    assert_eq!(res.status, StatusCode::OK);
    assert_eq!(res.headers[header::ETAG], format!("\"{rev}\"").as_str());
    let served = res.json();
    let parsed = crate::ics::parse(&contract_body(), None, Utc::now().date_naive()).unwrap();
    let (expected_rev, expected) = calendar_feeds::document(&uid, parsed.events);
    assert_eq!(rev, expected_rev);
    assert_eq!(served, serde_json::from_str::<Value>(&expected).unwrap());
    let res = device_get(
        &app,
        &path,
        &token,
        &[("if-none-match", &format!("\"{rev}\""))],
    )
    .await;
    assert_eq!(res.status, StatusCode::NOT_MODIFIED);
    let res = device_get(&app, &path, &token, &[("accept-encoding", "gzip")]).await;
    assert_eq!(res.headers[header::CONTENT_ENCODING], "gzip");
    // Another phone, an unknown uid, a uid that isn't one: 404 alike.
    for (path, token) in [
        (path.clone(), &other_token),
        (
            format!("/api/devices/calendar/feeds/{}", "0".repeat(32)),
            &token,
        ),
        ("/api/devices/calendar/feeds/..%2F".to_string(), &token),
    ] {
        assert_eq!(
            device_get(&app, &path, token, &[]).await.status,
            StatusCode::NOT_FOUND,
            "{path}"
        );
    }
    assert!(
        app.policy(&other_token).await["calendar"]["feeds"]
            .as_array()
            .unwrap()
            .is_empty()
    );

    // The same link on the sibling's page: attached, no second fetch, one source.
    let calls = app.fetch.count();
    let res = add(&app, &cookie, other, LINK, "Skole").await;
    assert_eq!(res.status, StatusCode::SEE_OTHER);
    assert_eq!(app.fetch.count(), calls);
    assert_eq!(sources(&app).await, 1);
    assert_eq!(
        app.policy(&other_token).await["calendar"]["feeds"][0]["name"],
        "Skole"
    );
}

#[tokio::test]
async fn the_page_shows_a_link_and_waits_for_the_phone() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, token) = app.enrolled_device("Anna").await;
    app.fetch.answer(LINK, 200, contract_body());
    add(&app, &cookie, id, LINK, "Skolen").await;
    let uid = uid();
    let key = calendar_feeds::calendar_key(&uid);
    let page = app
        .get_page(&format!("/devices/{id}/calendar"), &cookie)
        .await
        .text();
    assert!(page.contains(&format!("id=\"link-{uid}\"")));
    assert!(page.contains("skole.example.no"));
    assert!(
        !page.contains("FAKE-TOKEN") && !page.contains("/kalender/"),
        "the link is never shown"
    );
    assert!(page.contains("Fetched "), "{page}");
    // No `calendar_feeds_v1` yet: update Vibb One; the calendar is listed at once by its key.
    assert!(page.contains("Update Vibb One on the phone"));
    assert!(page.contains(&format!("data-calendar=\"{key}\"")));
    assert!(page.contains("Calendar link · Waiting for the phone"));

    // The phone claims the capability and reports the calendar: the row is a calendar link like any other.
    app.post_status(
        &token,
        json!({
            "capabilities": [calendar_feeds::CAPABILITY, "calendar_v1"],
            "calendar": {"readable": true, "calendars": [
                {"key": key, "account_type": "LOCAL", "name": "Skolen", "access_level": 200},
                {"key": "9f3a61c2d84be705", "account_type": "at.bitfire.icsdroid", "name": "Skole (ICSx5)",
                 "source": "skole.example.no", "access_level": 200}
            ], "rules_skipped": [], "feeds": [
                {"id": uid, "applied_rev": "0123456789abcdef", "refused": 2, "unknown_zone": 0}
            ]}
        }),
    )
    .await;
    let page = app
        .get_page(&format!("/devices/{id}/calendar"), &cookie)
        .await
        .text();
    assert!(!page.contains("Waiting for the phone"));
    assert!(!page.contains("Update Vibb One on the phone"));
    assert!(page.contains("save 2 events"), "{page}");
    assert!(page.contains("May be the same as the calendar link Skolen"));
    // The kid's calendar is never a link's.
    assert!(!page.contains(&format!("<option value=\"{key}\"")));
    // In Norwegian, for this request only (`?lang=nb`, design 70).
    let nb = app
        .get_page(&format!("/devices/{id}/calendar?lang=nb"), &cookie)
        .await
        .text();
    assert!(
        nb.contains("Kalenderlenker") && nb.contains("Hentet "),
        "{nb}"
    );
    assert!(nb.contains("Telefonen fikk ikke lagret 2 hendelser"));
}

#[tokio::test]
async fn a_refused_add_renders_the_form_as_typed() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, _) = app.enrolled_device("Anna").await;
    let html = std::fs::read("testdata/calendar_feeds/html.ics").unwrap();
    app.fetch
        .answer("https://login.example.no/k.ics", 200, html);
    app.fetch
        .answer("https://big.example.no/k.ics", 200, vec![b'x'; 5_000_001]);
    app.fetch
        .answer("https://gone.example.no/k.ics", 404, "nope");
    app.fetch.dns("public.example.no", &["93.184.216.34"]);
    for (url, words) in [
        (
            "http://public.example.no/k.ics",
            "Plain http to the internet is refused",
        ),
        (
            "https://login.example.no/k.ics",
            "gave a web page, not a calendar",
        ),
        ("https://big.example.no/k.ics", "larger than 5 MB"),
        ("https://gone.example.no/k.ics", "(404)"),
        (
            "https://user:pw@x.example.no/k.ics",
            "user name or password",
        ),
        ("skolen", "paste the calendar"),
    ] {
        let res = add(&app, &cookie, id, url, "Mitt navn").await;
        assert_eq!(res.status, StatusCode::BAD_REQUEST, "{url}");
        let page = res.text();
        assert!(page.contains(words), "{url}: {page}");
        assert!(
            page.contains("value=\"Mitt navn\""),
            "{url}: the form as typed"
        );
        assert!(!page.contains("Brukernavn"), "never the body");
    }
    assert_eq!(sources(&app).await, 0);

    // Without the music key nothing can be sealed: the page says why, the route refuses.
    let mut state = app.state.clone();
    state.music_key = None;
    let refused = calendar_feeds::add(&state, id, LINK, "", Utc::now()).await;
    assert!(matches!(
        refused,
        Err(calendar_feeds::AddError::Refused(
            "calendar.links.error.no_key"
        ))
    ));
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
    assert!(page.contains("data-links=\"off\"") && !page.contains("data-links=\"add\""));
}

#[tokio::test]
async fn plain_http_is_judged_by_what_the_name_resolves_to() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, _) = app.enrolled_device("Anna").await;
    app.fetch.dns("nas.home.arpa", &["192.168.1.10"]);
    app.fetch.dns("pi.tailnet.example", &["100.101.1.2"]);
    app.fetch
        .answer("http://nas.home.arpa/k.ics", 200, one_event("a", "NAS"));
    app.fetch
        .answer("http://pi.tailnet.example/k.ics", 200, one_event("b", "Pi"));
    for url in [
        "http://nas.home.arpa/k.ics",
        "http://pi.tailnet.example/k.ics",
    ] {
        let res = add(&app, &cookie, id, url, "").await;
        assert_eq!(res.status, StatusCode::SEE_OTHER, "{url}: {}", res.text());
    }
    // LAN only: saved with the warning; the tailnet: fine. Both pinned (the LAN reach).
    let page = app
        .get_page(&format!("/devices/{id}/calendar"), &cookie)
        .await
        .text();
    assert_eq!(page.matches("Plain http on the home network").count(), 1);
    let reaches = app.fetch.reaches.lock().unwrap().clone();
    assert!(
        reaches
            .iter()
            .all(|(_, r)| *r == crate::music_net::Reach::Any)
    );
    let names: Vec<String> =
        sqlx::query_scalar("SELECT name FROM device_calendar_feeds ORDER BY name")
            .fetch_all(&app.db)
            .await
            .unwrap();
    assert_eq!(
        names,
        ["nas.home.arpa", "pi.tailnet.example"],
        "no X-WR-CALNAME: the host"
    );
}

#[tokio::test]
async fn fetch_now_waits_five_minutes_and_is_not_a_save() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, _) = app.enrolled_device("Anna").await;
    app.fetch.answer(LINK, 200, contract_body());
    add(&app, &cookie, id, LINK, "Skolen").await;
    let uid = uid();
    let saves = |app: &TestApp| {
        let db = app.db.clone();
        async move {
            sqlx::query_scalar::<_, i64>(
                "SELECT COUNT(*) FROM security_events WHERE event_type = 'settings_saved' AND detail LIKE '%fetch%'",
            )
            .fetch_one(&db)
            .await
            .unwrap()
        }
    };
    let calls = app.fetch.count();
    let path = format!("/devices/{id}/calendar/links/{uid}/fetch");
    let res = app
        .request_form(Method::POST, &path, Some(&cookie), &[])
        .await;
    assert_eq!(
        res.location(),
        Some(format!("/devices/{id}/calendar#link-{uid}").as_str())
    );
    assert_eq!(app.fetch.count(), calls + 1);
    let res = app
        .request_form(Method::POST, &path, Some(&cookie), &[])
        .await;
    assert_eq!(
        res.location(),
        Some(format!("/devices/{id}/calendar?notice=fetch_wait#link-{uid}").as_str())
    );
    assert_eq!(app.fetch.count(), calls + 1);
    app.assert_lands(&res, &cookie).await;
    assert_eq!(saves(&app).await, 0);
    // Rename and remove are saves, back to their anchors; the log names no link.
    let res = app
        .request_form(
            Method::POST,
            &format!("/devices/{id}/calendar/links/{uid}/name"),
            Some(&cookie),
            &[("name", "  Skolen 5B ")],
        )
        .await;
    app.assert_lands(&res, &cookie).await;
    let name: String = sqlx::query_scalar("SELECT name FROM device_calendar_feeds")
        .fetch_one(&app.db)
        .await
        .unwrap();
    assert_eq!(name, "Skolen 5B");
    let logged: Vec<String> = sqlx::query_scalar(
        "SELECT COALESCE(detail, '') FROM security_events WHERE event_type = 'settings_saved'",
    )
    .fetch_all(&app.db)
    .await
    .unwrap();
    assert!(!logged.is_empty());
    assert!(
        logged
            .iter()
            .all(|d| !d.contains("FAKE-TOKEN") && !d.contains("skole.example.no")),
        "{logged:?}"
    );
}

#[tokio::test]
async fn the_fetch_cycle() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (anna, _) = app.enrolled_device("Anna").await;
    let (ola, _) = app.enrolled_device("Ola").await;
    let (_, _) = app.enrolled_device("Kari").await;
    app.fetch.canned(
        LINK,
        Canned {
            status: 200,
            body: one_event("a", "First").into_bytes(),
            etag: Some("\"e1\"".into()),
            ..Default::default()
        },
    );
    add(&app, &cookie, anna, LINK, "").await;
    add(&app, &cookie, ola, LINK, "").await;
    let id = source_id(&app, &uid()).await.unwrap();
    let state = &app.state;
    let t0 = Utc::now();
    let next_at = |app: &TestApp| {
        let db = app.db.clone();
        async move {
            let (next, error, rev): (String, Option<String>, String) =
                sqlx::query_as("SELECT next_at, error, rev FROM calendar_sources")
                    .fetch_one(&db)
                    .await
                    .unwrap();
            (crate::music_sweep::parse_stamp(&next).unwrap(), error, rev)
        }
    };

    // 304: the set kept, the hour's interval.
    assert_eq!(
        calendar_feeds::refresh(state, id, t0).await.unwrap(),
        Outcome::Unchanged
    );
    let (next, _, rev) = next_at(&app).await;
    assert_eq!(
        next - t0.with_nanosecond(0).unwrap(),
        TimeDelta::minutes(60)
    );

    // A new set nudges exactly the phones with the link.
    let mut nudges = app.state.command_notify.subscribe();
    app.fetch.answer(LINK, 200, one_event("a", "Changed"));
    assert_eq!(
        calendar_feeds::refresh(state, id, t0).await.unwrap(),
        Outcome::Changed
    );
    let mut nudged = vec![nudges.try_recv().unwrap(), nudges.try_recv().unwrap()];
    nudged.sort();
    assert_eq!(nudged, [anna, ola]);
    assert!(nudges.try_recv().is_err());
    let (_, _, changed) = next_at(&app).await;
    assert_ne!(changed, rev);

    // A failure keeps the set and backs off 15 minutes.
    app.fetch
        .fail(LINK, crate::music_net::FetchError::Network("down".into()));
    assert!(matches!(
        calendar_feeds::refresh(state, id, t0).await.unwrap(),
        Outcome::Failed(_)
    ));
    let (next, error, kept) = next_at(&app).await;
    assert_eq!(
        (error.as_deref(), kept.as_str()),
        (Some("network"), changed.as_str())
    );
    assert_eq!(
        next - t0.with_nanosecond(0).unwrap(),
        TimeDelta::minutes(15)
    );

    // An empty answer is held twice and accepted the third time.
    app.fetch.answer(LINK, 200, EMPTY);
    for _ in 0..2 {
        assert_eq!(
            calendar_feeds::refresh(state, id, t0).await.unwrap(),
            Outcome::HeldEmpty
        );
        assert_eq!(next_at(&app).await.1.as_deref(), Some("empty"));
    }
    let page = app
        .get_page(&format!("/devices/{anna}/calendar"), &cookie)
        .await
        .text();
    assert!(page.contains("The link gave no events - showing the last ones"));
    assert_eq!(
        calendar_feeds::refresh(state, id, t0).await.unwrap(),
        Outcome::Changed
    );
    assert_eq!(next_at(&app).await.1, None);
    // ... or 24 hours after the first.
    app.fetch.answer(LINK, 200, one_event("a", "Back"));
    calendar_feeds::refresh(state, id, t0).await.unwrap();
    app.fetch.answer(LINK, 200, EMPTY);
    assert_eq!(
        calendar_feeds::refresh(state, id, t0).await.unwrap(),
        Outcome::HeldEmpty
    );
    assert_eq!(
        calendar_feeds::refresh(state, id, t0 + TimeDelta::hours(25))
            .await
            .unwrap(),
        Outcome::Changed
    );

    // Every series new at three fetches in a row: every 6 hours.
    for n in 0..4 {
        app.fetch
            .answer(LINK, 200, one_event(&format!("churn-{n}"), "Churn"));
        calendar_feeds::refresh(state, id, t0).await.unwrap();
    }
    let churn: i64 = sqlx::query_scalar("SELECT churn FROM calendar_sources")
        .fetch_one(&app.db)
        .await
        .unwrap();
    assert!(churn >= 3);
    assert_eq!(
        next_at(&app).await.0 - t0.with_nanosecond(0).unwrap(),
        TimeDelta::hours(6)
    );

    // An https → http redirect is refused.
    app.fetch
        .redirect(LINK, 302, "http://skole.example.no/plain.ics");
    calendar_feeds::refresh(state, id, t0).await.unwrap();
    assert_eq!(next_at(&app).await.1.as_deref(), Some("insecure"));
    // A public link's name answering privately is refused (the public reach).
    app.fetch.answer(LINK, 200, one_event("a", "x"));
    app.fetch.dns("skole.example.no", &["10.0.0.5"]);
    calendar_feeds::refresh(state, id, t0).await.unwrap();
    assert_eq!(next_at(&app).await.1.as_deref(), Some("private_address"));
    app.fetch.dns("skole.example.no", &["93.184.216.34"]);
    // More than 5 MB keeps the set; a Latin-1 body is read by its Content-Type.
    app.fetch.answer(LINK, 200, vec![b'x'; 5_000_001]);
    calendar_feeds::refresh(state, id, t0).await.unwrap();
    assert_eq!(next_at(&app).await.1.as_deref(), Some("too_big"));
    let soon = (Utc::now() + TimeDelta::days(2))
        .format("%Y%m%d")
        .to_string();
    let body = [
        b"BEGIN:VCALENDAR\r\nBEGIN:VEVENT\r\nUID:l\r\nDTSTART:".as_slice(),
        soon.as_bytes(),
        b"T080000Z\r\nSUMMARY:Bl\xe5b\xe6r\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n",
    ]
    .concat();
    app.fetch.canned(
        LINK,
        Canned {
            status: 200,
            body,
            content_type: Some("text/calendar; charset=ISO-8859-1".into()),
            ..Default::default()
        },
    );
    calendar_feeds::refresh(state, id, t0).await.unwrap();
    let json: String = sqlx::query_scalar("SELECT feed_json FROM calendar_sources")
        .fetch_one(&app.db)
        .await
        .unwrap();
    assert!(json.contains("Blåbær"), "{json}");
}

#[tokio::test]
async fn a_removal_during_a_fetch_writes_nothing() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, _) = app.enrolled_device("Anna").await;
    app.fetch.answer(LINK, 200, one_event("a", "First"));
    add(&app, &cookie, id, LINK, "").await;
    let source = source_id(&app, &uid()).await.unwrap();
    app.fetch.answer(LINK, 200, one_event("a", "Changed"));
    app.fetch.hold();
    let state = app.state.clone();
    let fetching =
        tokio::spawn(async move { calendar_feeds::refresh(&state, source, Utc::now()).await });
    app.fetch.wait_in_flight().await;
    let res = app
        .request_form(
            Method::POST,
            &format!("/devices/{id}/calendar/links/{}/remove", uid()),
            Some(&cookie),
            &[],
        )
        .await;
    assert_eq!(
        res.location(),
        Some(format!("/devices/{id}/calendar#links").as_str())
    );
    app.fetch.release();
    assert_eq!(fetching.await.unwrap().unwrap(), Outcome::Gone);
    assert_eq!(sources(&app).await, 0);
}

#[tokio::test]
async fn remove_and_a_deleted_phone_delete_the_source() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (anna, anna_token) = app.enrolled_device("Anna").await;
    let (ola, _) = app.enrolled_device("Ola").await;
    app.fetch.answer(LINK, 200, contract_body());
    add(&app, &cookie, anna, LINK, "").await;
    add(&app, &cookie, ola, LINK, "").await;
    let uid = uid();
    // A setting on the link's calendar, by its key: it stays and comes back with the link.
    let key = calendar_feeds::calendar_key(&uid);
    sqlx::query("INSERT INTO device_calendars (device_id, calendar_key, shown) VALUES (?, ?, 0)")
        .bind(anna)
        .bind(&key)
        .execute(&app.db)
        .await
        .unwrap();
    let remove = format!("/devices/{anna}/calendar/links/{uid}/remove");
    let mut nudges = app.state.command_notify.subscribe();
    let res = app
        .request_form(Method::POST, &remove, Some(&cookie), &[])
        .await;
    app.assert_lands(&res, &cookie).await;
    assert_eq!(nudges.try_recv().unwrap(), anna);
    assert!(
        app.policy(&anna_token).await["calendar"]["feeds"]
            .as_array()
            .unwrap()
            .is_empty()
    );
    assert_eq!(sources(&app).await, 1, "Ola still has it");
    // The last phone's link gone: the source with its sealed link and its set.
    sqlx::query("DELETE FROM devices WHERE id = ?")
        .bind(ola)
        .execute(&app.db)
        .await
        .unwrap();
    assert_eq!(
        sources(&app).await,
        0,
        "a deleted phone's last link takes the source"
    );
    // Added again: the same calendar, its setting back.
    add(&app, &cookie, anna, LINK, "").await;
    let policy = app.policy(&anna_token).await;
    assert_eq!(policy["calendar"]["feeds"][0]["id"], uid.as_str());
    assert_eq!(policy["calendar"]["calendars"][0]["key"], key.as_str());
    assert_eq!(policy["calendar"]["calendars"][0]["shown"], false);
}

#[tokio::test]
async fn the_caps_per_phone() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (id, _) = app.enrolled_device("Anna").await;
    for n in 0..calendar_feeds::MAX_LINKS_PER_PHONE {
        let url = format!("https://skole.example.no/{n}.ics");
        app.fetch.answer(&url, 200, EMPTY);
        assert_eq!(
            add(&app, &cookie, id, &url, "").await.status,
            StatusCode::SEE_OTHER
        );
    }
    let page = app
        .get_page(&format!("/devices/{id}/calendar"), &cookie)
        .await
        .text();
    assert!(page.contains("data-links=\"full\"") && !page.contains("data-links=\"add\""));
    app.fetch
        .answer("https://skole.example.no/x.ics", 200, EMPTY);
    let res = add(&app, &cookie, id, "https://skole.example.no/x.ics", "").await;
    assert_eq!(res.status, StatusCode::BAD_REQUEST);
}
