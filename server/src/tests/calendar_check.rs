//! How often the server checks a calendar source (the owner's ask of 2026-11-28, migration 0188): a link's "Check"
//! per phone (15 / 30 / 60 min, 30 by default; the shared fetch takes the shortest), a CalDAV account's on its
//! owner's page (5 / 15 / 30 / 60, 5 by default). The defaults, the choices the routes take, the next check counted
//! again, the backoff, the scheduler's order with a 5-min and a 60-min source, and the landing at `#sources`.

use axum::http::{Method, StatusCode};
use chrono::{DateTime, TimeDelta, Timelike, Utc};

use super::TestApp;
use super::caldav::{
    HOME, PASSWORD, SERVER, account_id, fake_icloud, home_source, test_form, tick,
};
use crate::calendar_feeds::{self, Outcome};
use crate::music_sweep::{parse_stamp, stamp};

const LINK: &str = "https://skole.example.no/kalender/5b.ics?token=FAKE-TOKEN";

fn one_event() -> String {
    format!(
        "BEGIN:VCALENDAR\r\nBEGIN:VEVENT\r\nUID:a\r\nDTSTART:{}T080000Z\r\nSUMMARY:Prøve\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n",
        (Utc::now() + TimeDelta::days(3)).format("%Y%m%d")
    )
}

fn link_uid() -> String {
    calendar_feeds::uid_of(&calendar_feeds::normalize(LINK).unwrap())
}

async fn add_link(app: &TestApp, cookie: &str, device: i64) -> i64 {
    app.fetch.answer(LINK, 200, one_event());
    let res = app
        .request_form(
            Method::POST,
            &format!("/devices/{device}/calendar/links"),
            Some(cookie),
            &[("url", LINK), ("name", "")],
        )
        .await;
    assert_eq!(res.status, StatusCode::SEE_OTHER, "{}", res.text());
    sqlx::query_scalar("SELECT id FROM calendar_sources WHERE uid = ?")
        .bind(link_uid())
        .fetch_one(&app.db)
        .await
        .unwrap()
}

async fn post_check(app: &TestApp, cookie: &str, path: &str, value: &str) -> super::TestResponse {
    app.request_form(Method::POST, path, Some(cookie), &[("check", value)])
        .await
}

async fn link_check(app: &TestApp, device: i64, source: i64) -> i64 {
    sqlx::query_scalar(
        "SELECT check_minutes FROM device_calendar_feeds WHERE device_id = ? AND source_id = ?",
    )
    .bind(device)
    .bind(source)
    .fetch_one(&app.db)
    .await
    .unwrap()
}

async fn account_check(app: &TestApp, account: i64) -> i64 {
    sqlx::query_scalar("SELECT check_minutes FROM caldav_accounts WHERE id = ?")
        .bind(account)
        .fetch_one(&app.db)
        .await
        .unwrap()
}

async fn next_at(app: &TestApp, source: i64) -> DateTime<Utc> {
    let next: String = sqlx::query_scalar("SELECT next_at FROM calendar_sources WHERE id = ?")
        .bind(source)
        .fetch_one(&app.db)
        .await
        .unwrap();
    parse_stamp(&next).unwrap()
}

/// The source's "Check" form on the Calendar page: its HTML from its `<form` (the one with its `data-check`) to
/// `</form>`.
fn check_form(page: &str, id: &str) -> String {
    let mark = page
        .find(&format!("data-check=\"{id}\""))
        .unwrap_or_else(|| panic!("no Check for {id}: {page}"));
    let start = page[..mark].rfind("<form").unwrap();
    let rest = &page[start..];
    rest[..rest.find("</form>").unwrap()].to_string()
}

fn options(form: &str) -> Vec<(String, bool)> {
    form.split("<option value=\"")
        .skip(1)
        .map(|o| {
            let value = o.split('"').next().unwrap().to_string();
            (value, o.split('>').next().unwrap().contains("selected"))
        })
        .collect()
}

fn whole(t: DateTime<Utc>) -> DateTime<Utc> {
    t.with_nanosecond(0).unwrap()
}

/// A link is checked every 30 min, a CalDAV account's calendars every 5 by default; each row's select offers its
/// kind's choices with the default picked, in both languages; a CalDAV account's only on its owner's page.
#[tokio::test]
async fn each_kind_has_its_default_and_its_choices() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (anna, _) = app.enrolled_device("Anna").await;
    let (ola, _) = app.enrolled_device("Ola").await;
    let link = add_link(&app, &cookie, anna).await;
    assert_eq!(link_check(&app, anna, link).await, 30);
    assert_eq!(
        calendar_feeds::interval_of(&app.db, link).await.unwrap(),
        TimeDelta::minutes(30)
    );

    fake_icloud(&app);
    test_form(&app, &cookie, anna, SERVER, PASSWORD).await;
    let account = account_id(&app).await;
    assert_eq!(account_check(&app, account).await, 5);
    let before = Utc::now();
    let res = tick(&app, &cookie, anna, account, &[HOME]).await;
    assert_eq!(res.status, StatusCode::SEE_OTHER, "{}", res.text());
    // Fetched before the answer: next in 5 minutes.
    let home = home_source(&app).await;
    let next = next_at(&app, home).await;
    assert!(
        next >= whole(before) + TimeDelta::minutes(5) && next <= Utc::now() + TimeDelta::minutes(5),
        "{next}"
    );

    let page = app
        .get_page(&format!("/devices/{anna}/calendar?lang=nb"), &cookie)
        .await
        .text();
    let form = check_form(&page, &link_uid());
    assert!(form.contains(&format!(
        "action=\"/devices/{anna}/calendar/links/{}/check#sources\"",
        link_uid()
    )));
    assert!(form.contains(">Sjekk<") && form.contains("data-autosave"));
    assert_eq!(
        options(&form),
        [("15", false), ("30", true), ("60", false)].map(|(v, s)| (v.to_string(), s))
    );
    assert!(form.contains(">15 min<"));
    let form = check_form(&page, &account.to_string());
    assert!(form.contains(&format!(
        "action=\"/devices/{anna}/calendar/caldav/{account}/check#sources\""
    )));
    assert_eq!(
        options(&form),
        [("5", true), ("15", false), ("30", false), ("60", false)].map(|(v, s)| (v.to_string(), s))
    );
    let page = app
        .get_page(&format!("/devices/{anna}/calendar?lang=en"), &cookie)
        .await
        .text();
    assert!(check_form(&page, &link_uid()).contains(">Check<"));

    // Shared with Ola: listed on his page, its Check is Anna's alone.
    super::caldav::share(&app, &cookie, anna, account, true).await;
    let page = app
        .get_page(&format!("/devices/{ola}/calendar"), &cookie)
        .await
        .text();
    assert!(page.contains(&format!("data-caldav=\"{account}\"")));
    assert!(!page.contains(&format!("data-check=\"{account}\"")));
}

/// Only the kind's choices are saved; anything else is a 400 and writes nothing. A save lands on `#sources`; an
/// account another phone owns, or a CalDAV calendar's uid on the link route, is refused there with nothing written.
#[tokio::test]
async fn the_check_takes_only_its_choices() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (anna, _) = app.enrolled_device("Anna").await;
    let (ola, _) = app.enrolled_device("Ola").await;
    let link = add_link(&app, &cookie, anna).await;
    fake_icloud(&app);
    test_form(&app, &cookie, anna, SERVER, PASSWORD).await;
    let account = account_id(&app).await;
    tick(&app, &cookie, anna, account, &[HOME]).await;
    let link_path = format!("/devices/{anna}/calendar/links/{}/check", link_uid());
    let caldav_path = format!("/devices/{anna}/calendar/caldav/{account}/check");

    for value in ["5", "45", "0", "-15", "", "abc", "30 ", "1e2"] {
        let res = post_check(&app, &cookie, &link_path, value).await;
        assert_eq!(res.status, StatusCode::BAD_REQUEST, "link {value:?}");
    }
    for value in ["1", "10", "120", "", "x"] {
        let res = post_check(&app, &cookie, &caldav_path, value).await;
        assert_eq!(res.status, StatusCode::BAD_REQUEST, "caldav {value:?}");
    }
    assert_eq!(link_check(&app, anna, link).await, 30);
    assert_eq!(account_check(&app, account).await, 5);

    let res = post_check(&app, &cookie, &link_path, "60").await;
    assert_eq!(res.status, StatusCode::SEE_OTHER, "{}", res.text());
    assert_eq!(
        res.location(),
        Some(format!("/devices/{anna}/calendar#sources").as_str())
    );
    app.assert_lands(&res, &cookie).await;
    assert_eq!(link_check(&app, anna, link).await, 60);
    let res = post_check(&app, &cookie, &caldav_path, "15").await;
    assert_eq!(
        res.location(),
        Some(format!("/devices/{anna}/calendar#sources").as_str())
    );
    app.assert_lands(&res, &cookie).await;
    assert_eq!(account_check(&app, account).await, 15);
    // The page draws what was saved.
    let page = app
        .get_page(&format!("/devices/{anna}/calendar"), &cookie)
        .await
        .text();
    assert!(options(&check_form(&page, &link_uid())).contains(&("60".to_string(), true)));
    assert!(options(&check_form(&page, &account.to_string())).contains(&("15".to_string(), true)));

    // Another phone's account (shared or not), and a CalDAV calendar's uid on the link route: nothing written.
    super::caldav::share(&app, &cookie, anna, account, true).await;
    let res = post_check(
        &app,
        &cookie,
        &format!("/devices/{ola}/calendar/caldav/{account}/check"),
        "60",
    )
    .await;
    assert_eq!(
        res.location(),
        Some(format!("/devices/{ola}/calendar#sources").as_str())
    );
    assert_eq!(account_check(&app, account).await, 15);
    let res = post_check(
        &app,
        &cookie,
        &format!(
            "/devices/{anna}/calendar/links/{}/check",
            super::caldav::home_uid()
        ),
        "60",
    )
    .await;
    assert_eq!(res.status, StatusCode::SEE_OTHER);
    let home = home_source(&app).await;
    assert_eq!(link_check(&app, anna, home).await, 30);
    // A link this phone hasn't: back to #sources, Ola's phone has no row to write.
    let res = post_check(
        &app,
        &cookie,
        &format!("/devices/{ola}/calendar/links/{}/check", link_uid()),
        "15",
    )
    .await;
    assert_eq!(
        res.location(),
        Some(format!("/devices/{ola}/calendar#sources").as_str())
    );
    assert_eq!(link_check(&app, anna, link).await, 60);
}

/// A changed Check counts the next check from the last one; a failing source keeps its backoff, a due one stays
/// due. A link on two phones is checked at the shorter of their choices. The backoff is the one before the choice
/// (15 min, then 30), and a 60-min link's goes on to 60.
#[tokio::test]
async fn a_changed_check_counts_from_the_last_check() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (anna, _) = app.enrolled_device("Anna").await;
    let (ola, _) = app.enrolled_device("Ola").await;
    let link = add_link(&app, &cookie, anna).await;
    let path = format!("/devices/{anna}/calendar/links/{}/check", link_uid());
    let set = |tried: DateTime<Utc>, next: DateTime<Utc>, failures: i64| {
        let db = app.db.clone();
        async move {
            sqlx::query(
                "UPDATE calendar_sources SET tried_at = ?, next_at = ?, failures = ? WHERE id = ?",
            )
            .bind(stamp(tried))
            .bind(stamp(next))
            .bind(failures)
            .bind(link)
            .execute(&db)
            .await
            .unwrap();
        }
    };
    let tried = whole(Utc::now()) - TimeDelta::minutes(10);
    set(tried, tried + TimeDelta::minutes(30), 0).await;
    post_check(&app, &cookie, &path, "15").await;
    assert_eq!(next_at(&app, link).await, tried + TimeDelta::minutes(15));
    post_check(&app, &cookie, &path, "60").await;
    assert_eq!(next_at(&app, link).await, tried + TimeDelta::minutes(60));
    // Shorter than the time since the last check: due at the next pass.
    let tried = whole(Utc::now()) - TimeDelta::minutes(40);
    set(tried, tried + TimeDelta::minutes(60), 0).await;
    post_check(&app, &cookie, &path, "30").await;
    assert_eq!(next_at(&app, link).await, tried + TimeDelta::minutes(30));
    assert!(next_at(&app, link).await <= Utc::now());
    // Failing: the backoff's time stays. Due: stays due.
    let backoff = whole(Utc::now()) + TimeDelta::minutes(15);
    set(tried, backoff, 1).await;
    post_check(&app, &cookie, &path, "60").await;
    assert_eq!(next_at(&app, link).await, backoff);
    let due = whole(Utc::now()) - TimeDelta::minutes(1);
    set(tried, due, 0).await;
    post_check(&app, &cookie, &path, "15").await;
    assert_eq!(next_at(&app, link).await, due);

    // The same link on Ola's page: one source, checked at the shorter choice.
    post_check(&app, &cookie, &path, "60").await;
    add_link(&app, &cookie, ola).await;
    assert_eq!(link_check(&app, ola, link).await, 30);
    let t0 = whole(Utc::now());
    assert_eq!(
        calendar_feeds::refresh(&app.state, link, t0).await.unwrap(),
        Outcome::Unchanged
    );
    assert_eq!(next_at(&app, link).await, t0 + TimeDelta::minutes(30));
    post_check(
        &app,
        &cookie,
        &format!("/devices/{ola}/calendar/links/{}/check", link_uid()),
        "15",
    )
    .await;
    assert_eq!(
        calendar_feeds::interval_of(&app.db, link).await.unwrap(),
        TimeDelta::minutes(15)
    );
    assert_eq!(next_at(&app, link).await, t0 + TimeDelta::minutes(15));
    // Both at 60: an hour; a failure backs off 15 minutes, then the hour.
    post_check(
        &app,
        &cookie,
        &format!("/devices/{ola}/calendar/links/{}/check", link_uid()),
        "60",
    )
    .await;
    calendar_feeds::refresh(&app.state, link, t0).await.unwrap();
    assert_eq!(next_at(&app, link).await, t0 + TimeDelta::minutes(60));
    app.fetch
        .fail(LINK, crate::music_net::FetchError::Network("down".into()));
    for wait in [15, 60, 60] {
        calendar_feeds::refresh(&app.state, link, t0).await.unwrap();
        assert_eq!(next_at(&app, link).await, t0 + TimeDelta::minutes(wait));
    }
}

/// A CalDAV account checked every 5 minutes and a link every hour: the pass takes the one due longest first
/// whichever is checked more often, each is next due at its own interval, and a pass right after fetches nothing. A
/// failing 5-min calendar backs off as before (15, then 30), never every 5 minutes. A changed account Check counts
/// each of its calendars' next check again.
#[tokio::test]
async fn the_scheduler_serves_the_longest_due_first() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (anna, _) = app.enrolled_device("Anna").await;
    let link = add_link(&app, &cookie, anna).await;
    post_check(
        &app,
        &cookie,
        &format!("/devices/{anna}/calendar/links/{}/check", link_uid()),
        "60",
    )
    .await;
    fake_icloud(&app);
    test_form(&app, &cookie, anna, SERVER, PASSWORD).await;
    let account = account_id(&app).await;
    tick(&app, &cookie, anna, account, &[HOME]).await;
    let home = home_source(&app).await;

    let order = |link_at: &'static str, home_at: &'static str| {
        let app = &app;
        async move {
            for (id, at) in [(link, link_at), (home, home_at)] {
                sqlx::query("UPDATE calendar_sources SET next_at = ? WHERE id = ?")
                    .bind(at)
                    .bind(id)
                    .execute(&app.db)
                    .await
                    .unwrap();
            }
            let from = app.fetch.requests.lock().unwrap().len();
            calendar_feeds::run_due(&app.state, std::time::Duration::ZERO).await;
            app.fetch.requests.lock().unwrap()[from..]
                .iter()
                .map(|r| r.url.clone())
                .collect::<Vec<_>>()
        }
    };
    let link_first = order("2000-01-01 00:00:00", "2000-01-01 00:00:01").await;
    assert_eq!(link_first, [LINK, HOME]);
    let after = Utc::now();
    let (next_link, next_home) = (next_at(&app, link).await, next_at(&app, home).await);
    assert!(
        next_link > after + TimeDelta::minutes(59) && next_link <= after + TimeDelta::minutes(60)
    );
    assert!(
        next_home > after + TimeDelta::minutes(4) && next_home <= after + TimeDelta::minutes(5)
    );
    // Nothing due right after the pass.
    let from = app.fetch.requests.lock().unwrap().len();
    calendar_feeds::run_due(&app.state, std::time::Duration::ZERO).await;
    assert_eq!(app.fetch.requests.lock().unwrap().len(), from);
    let home_first = order("2000-01-01 00:00:01", "2000-01-01 00:00:00").await;
    assert_eq!(home_first, [HOME, LINK]);

    // The account's Check at 30: its calendar's next is counted from its last check.
    let tried: String = sqlx::query_scalar("SELECT tried_at FROM calendar_sources WHERE id = ?")
        .bind(home)
        .fetch_one(&app.db)
        .await
        .unwrap();
    post_check(
        &app,
        &cookie,
        &format!("/devices/{anna}/calendar/caldav/{account}/check"),
        "30",
    )
    .await;
    assert_eq!(
        next_at(&app, home).await,
        parse_stamp(&tried).unwrap() + TimeDelta::minutes(30)
    );
    post_check(
        &app,
        &cookie,
        &format!("/devices/{anna}/calendar/caldav/{account}/check"),
        "5",
    )
    .await;

    // A failing 5-min calendar: 15 minutes, then 30.
    app.fetch
        .fail(HOME, crate::music_net::FetchError::Network("down".into()));
    let t0 = whole(Utc::now());
    for wait in [15, 30, 30] {
        assert!(matches!(
            calendar_feeds::refresh(&app.state, home, t0).await.unwrap(),
            Outcome::Failed(_)
        ));
        assert_eq!(next_at(&app, home).await, t0 + TimeDelta::minutes(wait));
    }
}
