//! Storytel on the server, step 2a of design 26b (§2.1-§2.3, §7): the login's probe at save
//! (accepted, refused - the old login kept, unreachable - saved with a notice), the card's status
//! line, the shelf picker (series and standalone books, the kids filter, what is in the library),
//! Add (only shelf targets, a repeated `target`), clear, the entry page's choices, and that no
//! page, event or error holds the login or the session token. vibb's `tests/storytel_routes.py`
//! lands here; `storytel::tests` holds the client's own (`storytel_api.py`).

use axum::http::{Method, StatusCode};
use chrono::TimeDelta;

use super::TestApp;
use super::music::events;
use crate::music_net::FetchError;
use crate::music_sweep::parse_stamp;
use crate::storytel::{BOOKSHELF, LOGIN_URL, encrypt_password, login_url};

const EMAIL: &str = "k@example.invalid";
const PASSWORD: &str = "hemmelig-pw";

fn fixture(name: &str) -> Vec<u8> {
    std::fs::read(format!("testdata/storytel/{name}")).unwrap()
}

fn login(email: &str, password: &str) -> String {
    login_url(LOGIN_URL, email, password)
}

/// Storytel takes `email`/`password` and has the fixture shelf.
fn storytel_takes(app: &TestApp, email: &str, password: &str) {
    app.fetch
        .answer(&login(email, password), 200, fixture("login_ok.json"));
    app.fetch.answer(BOOKSHELF, 200, fixture("bookshelf.json"));
}

async fn save(app: &TestApp, cookie: &str, email: &str, password: &str) -> super::TestResponse {
    app.request_form(
        Method::POST,
        "/music/storytel",
        Some(cookie),
        &[("email", email), ("password", password)],
    )
    .await
}

type Row = (
    Option<Vec<u8>>,
    i64,
    Option<String>,
    Option<i64>,
    Option<String>,
    Option<String>,
);

async fn row(app: &TestApp) -> Row {
    sqlx::query_as(
        "SELECT ciphertext, generation, login_refused_at, login_refused_generation, \
         login_ok_at, login_error FROM music_storytel",
    )
    .fetch_one(&app.db)
    .await
    .unwrap()
}

/// The stored login, opened.
async fn stored(app: &TestApp) -> Option<(String, String)> {
    crate::storytel::credentials(&app.state)
        .await
        .ok()
        .map(|c| (c.login.email, c.login.password))
}

async fn revision(app: &TestApp) -> i64 {
    sqlx::query_scalar("SELECT revision FROM music_library_revision")
        .fetch_one(&app.db)
        .await
        .unwrap()
}

/// Nothing secret in `text`: the e-mail, the password, the encrypted password, the token.
fn assert_no_secret(text: &str, password: &str, what: &str) {
    for secret in [
        EMAIL,
        "k%40example",
        password,
        &encrypt_password(password),
        "pwd=",
        "FAKE-JWT",
        "Bearer",
    ] {
        assert!(!text.contains(secret), "{what} holds {secret:?}");
    }
}

async fn storytel_entries(app: &TestApp) -> Vec<(String, String, String, String, i64, bool, i64)> {
    sqlx::query_as(
        "SELECT name, target, key, play_order, cache, resume, category_id FROM music_entries \
         WHERE source = 'storytel' ORDER BY sort",
    )
    .fetch_all(&app.db)
    .await
    .unwrap()
}

#[tokio::test]
async fn routes_need_a_session() {
    let app = TestApp::new().await;
    storytel_takes(&app, EMAIL, PASSWORD);
    for (uri, fields) in [
        (
            "/music/storytel",
            vec![("email", EMAIL), ("password", PASSWORD)],
        ),
        (
            "/music/storytel/add",
            vec![("target", "storytel:series:26175"), ("category", "3")],
        ),
        ("/music/storytel/clear", vec![]),
    ] {
        let res = app.request_form(Method::POST, uri, None, &fields).await;
        assert!(res.status.is_redirection(), "{uri}: {}", res.status);
        assert_eq!(res.location(), Some("/login"), "{uri}");
    }
    let res = app
        .request(Method::GET, "/music/storytel", None, None)
        .await;
    assert_eq!(res.location(), Some("/login"));
    // Nothing was stored, added or asked of Storytel.
    assert_eq!(row(&app).await.1, 0);
    assert!(storytel_entries(&app).await.is_empty());
    assert_eq!(app.fetch.count(), 0);
}

#[tokio::test]
async fn save_probes_then_seals() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (device, _) = app.enrolled_device("phone").await;
    sqlx::query("UPDATE device_policy SET music_storytel = 1 WHERE device_id = ?")
        .bind(device)
        .execute(&app.db)
        .await
        .unwrap();
    let t0 = parse_stamp("2026-10-12 10:02:00").unwrap();
    app.state.music_sweep.set_clock(Some(t0));

    // Accepted: one login and one bookshelf, then sealed; the phones nudged.
    storytel_takes(&app, EMAIL, PASSWORD);
    let mut rx = app.state.command_notify.subscribe();
    let res = save(&app, &cookie, EMAIL, PASSWORD).await;
    assert_eq!(
        res.location(),
        Some("/music#storytel"),
        "{}",
        res.text().replace("&#39;", "'")
    );
    assert_eq!(super::music::nudged(&mut rx), vec![device]);
    let requests = app.fetch.requests.lock().unwrap().clone();
    assert_eq!(requests.len(), 2);
    assert!(requests[0].url.starts_with(LOGIN_URL));
    assert_eq!(requests[1].url, BOOKSHELF);
    let (_, generation, refused_at, _, ok_at, error) = row(&app).await;
    assert_eq!(generation, 1);
    assert_eq!(refused_at, None);
    assert_eq!(ok_at.as_deref(), Some("2026-10-12 10:02:00"));
    assert_eq!(error, None);
    assert_eq!(stored(&app).await, Some((EMAIL.into(), PASSWORD.into())));
    assert_eq!(events(&app, "storytel_login_saved").await.len(), 1);
    // The card: the notice once, the status line, the picker link; never the login.
    let page = app
        .get_page("/music", &cookie)
        .await
        .text()
        .replace("&#39;", "'");
    assert!(page.contains("Saved - Storytel shows 4 series and books."));
    assert!(page.contains("Storytel accepted the login (10:02 UTC)."));
    assert!(page.contains("href=\"/music/storytel\""));
    assert!(page.contains("Storytel Family, which isn't supported yet"));
    assert!(page.contains("Use a password you use only for Storytel"));
    assert_no_secret(&page, PASSWORD, "/music");
    let again = app
        .get_page("/music", &cookie)
        .await
        .text()
        .replace("&#39;", "'");
    assert!(
        !again.contains("Saved - Storytel shows"),
        "the notice shows once"
    );
    // The probe's session and shelf are kept: the picker logs in no more.
    app.fetch.clear_calls();
    let picker = app.get_page("/music/storytel", &cookie).await;
    assert_eq!(picker.status, StatusCode::OK);
    assert_eq!(
        *app.fetch.calls.lock().unwrap(),
        vec![BOOKSHELF.to_string()]
    );

    // Refused (a wrong password): nothing stored, the old login kept, a 400 by the field.
    app.fetch.answer(&login(EMAIL, "feil"), 401, "nope");
    let res = save(&app, &cookie, EMAIL, "feil").await;
    assert_eq!(res.status, StatusCode::BAD_REQUEST);
    let text = res.text().replace("&#39;", "'");
    assert!(
        text.contains("Storytel refused that e-mail and password. The saved login is unchanged.")
    );
    assert!(text.contains("id=\"storytel_email\" name=\"email\""));
    assert!(text.contains("autofocus"));
    assert_no_secret(&text, "feil", "the refused page");
    assert_eq!(row(&app).await.1, 1);
    assert_eq!(stored(&app).await, Some((EMAIL.into(), PASSWORD.into())));
    assert_eq!(
        events(&app, "storytel_login_refused").await,
        vec!["Storytel answered 401".to_string()]
    );
    // 200 without a session is refused the same way; the probe never starts the cooldown.
    app.fetch.answer(&login(EMAIL, "tom"), 200, "{}");
    assert_eq!(
        save(&app, &cookie, EMAIL, "tom").await.status,
        StatusCode::BAD_REQUEST
    );
    let (_, generation, refused_at, _, _, _) = row(&app).await;
    assert_eq!((generation, refused_at), (1, None));
    // Storytel down or rate-limiting (code QA L5): nothing stored either, but not called a wrong
    // password.
    for status in [503, 429] {
        app.fetch.answer(&login(EMAIL, "tom"), status, "");
        let res = save(&app, &cookie, EMAIL, "tom").await;
        assert_eq!(res.status, StatusCode::BAD_REQUEST);
        let text = res.text().replace("&#39;", "'");
        assert!(
            text.contains(&format!(
                "Storytel answered {status} - nothing was saved; the saved login is unchanged. \
                 Try again later."
            )),
            "{status}"
        );
        assert!(!text.contains("refused that e-mail and password"));
    }
    assert_eq!(row(&app).await.1, 1);
    assert_eq!(stored(&app).await, Some((EMAIL.into(), PASSWORD.into())));

    // Unreachable: saved anyway, with the notice; the card says Storytel couldn't be reached.
    app.fetch.fail(
        &login(EMAIL, "nytt-pw"),
        FetchError::Network("connection reset".to_string()),
    );
    let res = save(&app, &cookie, EMAIL, "nytt-pw").await;
    assert_eq!(res.location(), Some("/music#storytel"));
    let (_, generation, _, _, ok_at, error) = row(&app).await;
    assert_eq!(
        (generation, ok_at, error.as_deref()),
        (2, None, Some("network"))
    );
    assert_eq!(stored(&app).await, Some((EMAIL.into(), "nytt-pw".into())));
    let page = app
        .get_page("/music", &cookie)
        .await
        .text()
        .replace("&#39;", "'");
    assert!(page.contains("Saved, but Storytel couldn't be reached to check it."));
    assert!(
        page.contains("Storytel couldn't be reached (10:02 UTC)."),
        "{page}"
    );
    let error_at: Option<String> = sqlx::query_scalar("SELECT login_error_at FROM music_storytel")
        .fetch_one(&app.db)
        .await
        .unwrap();
    assert_eq!(error_at.as_deref(), Some("2026-10-12 10:02:00"));
    // The login works but the shelf doesn't: unreachable too, the login's time kept.
    app.fetch
        .answer(&login(EMAIL, "pw3"), 200, fixture("login_ok.json"));
    app.fetch.answer(BOOKSHELF, 503, "");
    let res = save(&app, &cookie, EMAIL, "pw3").await;
    assert_eq!(res.location(), Some("/music#storytel"));
    let (_, generation, _, _, ok_at, error) = row(&app).await;
    assert_eq!(
        (generation, ok_at.is_some(), error.as_deref()),
        (3, true, Some("http_503"))
    );
    assert!(
        app.get_page("/music", &cookie)
            .await
            .text()
            .replace("&#39;", "'")
            .contains("Storytel answered 503 (10:02 UTC).")
    );

    // A refusal in use starts the cooldown (shown with its times); a new save retries at once.
    app.fetch.answer(&login(EMAIL, "pw3"), 403, "");
    app.fetch.answer(BOOKSHELF, 401, "");
    app.state.storytel.forget().await;
    let picker = app
        .get_page("/music/storytel", &cookie)
        .await
        .text()
        .replace("&#39;", "'");
    assert!(
        picker.contains("Storytel refused the saved login"),
        "{picker}"
    );
    let page = app
        .get_page("/music", &cookie)
        .await
        .text()
        .replace("&#39;", "'");
    assert!(page.contains(
        "Storytel refused the login at 10:02 UTC. Nothing is tried again before 11:02 UTC - save \
         the password again to try now."
    ));
    app.fetch.clear_calls();
    app.state
        .music_sweep
        .set_clock(Some(t0 + TimeDelta::minutes(10)));
    let picker = app
        .get_page("/music/storytel", &cookie)
        .await
        .text()
        .replace("&#39;", "'");
    assert!(
        picker.contains("Nothing is tried again before 11:02 UTC"),
        "{picker}"
    );
    assert_eq!(app.fetch.count(), 0, "inside the cooldown nothing is asked");
    storytel_takes(&app, EMAIL, PASSWORD);
    let res = save(&app, &cookie, EMAIL, PASSWORD).await;
    assert_eq!(res.location(), Some("/music#storytel"));
    let (_, generation, refused_at, refused_generation, _, error) = row(&app).await;
    assert_eq!(
        (generation, refused_at, refused_generation, error),
        (4, None, None, None)
    );

    // No secret in the security log.
    let log: Vec<String> = sqlx::query_scalar("SELECT COALESCE(detail, '') FROM security_events")
        .fetch_all(&app.db)
        .await
        .unwrap();
    for detail in &log {
        for password in [PASSWORD, "feil", "tom", "nytt-pw", "pw3"] {
            assert_no_secret(detail, password, "the security log");
        }
    }
}

#[tokio::test]
async fn the_card_shows_no_login() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    storytel_takes(&app, EMAIL, PASSWORD);
    save(&app, &cookie, EMAIL, PASSWORD).await;
    let id = add(&app, &cookie, &["storytel:series:26175"]).await;
    assert_eq!(
        id.status,
        StatusCode::SEE_OTHER,
        "{}",
        id.text().replace("&#39;", "'")
    );
    let entry: i64 = sqlx::query_scalar("SELECT id FROM music_entries WHERE source = 'storytel'")
        .fetch_one(&app.db)
        .await
        .unwrap();
    for uri in [
        "/music".to_string(),
        "/music/storytel".to_string(),
        "/music/storytel?all=1".to_string(),
        format!("/music/entries/{entry}"),
    ] {
        let res = app.get_page(&uri, &cookie).await;
        assert_eq!(res.status, StatusCode::OK, "{uri}");
        assert_no_secret(&res.text().replace("&#39;", "'"), PASSWORD, &uri);
    }
}

async fn add(app: &TestApp, cookie: &str, targets: &[&str]) -> super::TestResponse {
    let mut fields: Vec<(&str, &str)> = targets.iter().map(|t| ("target", *t)).collect();
    fields.push(("category", ""));
    app.request_form(Method::POST, "/music/storytel/add", Some(cookie), &fields)
        .await
}

#[tokio::test]
async fn picker_lists_groups() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;

    // Without a login: a pointer to the card, nothing asked.
    let page = app.get_page("/music/storytel", &cookie).await;
    assert_eq!(page.status, StatusCode::OK);
    assert!(
        page.text()
            .replace("&#39;", "'")
            .contains("No Storytel login is saved")
    );
    assert!(
        page.text()
            .replace("&#39;", "'")
            .contains("href=\"/music#storytel\"")
    );
    assert_eq!(app.fetch.count(), 0);

    storytel_takes(&app, EMAIL, PASSWORD);
    save(&app, &cookie, EMAIL, PASSWORD).await;
    app.fetch.clear_calls();
    let text = app
        .get_page("/music/storytel", &cookie)
        .await
        .text()
        .replace("&#39;", "'");
    // A fresh bookshelf every time the page opens.
    assert_eq!(app.fetch.count(), 1);
    // Kids' books only by default, in shelf order; the adult standalone book hidden.
    let at = |needle: &str| {
        text.find(needle)
            .unwrap_or_else(|| panic!("{needle}: {text}"))
    };
    assert!(at("value=\"storytel:series:26175\"") < at("value=\"storytel:series:4242\""));
    assert!(at("value=\"storytel:series:4242\"") < at("value=\"storytel:book:777\""));
    assert!(!text.contains("storytel:book:999"));
    assert!(!text.contains("Voksenkrim"));
    assert!(text.contains("Series · 3 books · for kids"));
    assert!(text.contains("Book · for kids"));
    assert!(text.contains("1 book marked locked by Storytel - each is tried anyway"));
    assert!(text.contains("1 book not available in this country (per Storytel)"));
    // The books in reading order, with durations and flags.
    assert!(
        at("1. Kokosbananas og godteristøvsugeren · 0:12")
            < at("2. Kokosbananas og tomattorsken · 0:11")
    );
    assert!(text.contains("3. Kokosbananas og den låste boka · 0:10 · marked locked"));
    assert!(text.contains("1. Tall og bokstaver · 1:00 · not in this country"));
    // Lydbøker is the default category; text only (no remote images).
    assert!(text.contains("<option value=\"3\" selected>Lydbøker</option>"));
    assert!(!text.contains("<img"));
    assert!(!text.contains("c.example.invalid"));
    assert!(text.contains("href=\"/music/storytel?all=1#shelf\""));

    // ?all=1: every group, and the form keeps the filter.
    let all = app
        .get_page("/music/storytel?all=1", &cookie)
        .await
        .text()
        .replace("&#39;", "'");
    assert!(all.contains("value=\"storytel:book:999\""));
    assert!(all.contains("Voksenkrim"));
    assert!(all.contains("<input type=\"hidden\" name=\"all\" value=\"1\">"));
    assert!(all.contains("href=\"/music/storytel#shelf\""));

    // In the library: ticked, disabled, linked to its card.
    add(&app, &cookie, &["storytel:series:26175"]).await;
    let entry: i64 = sqlx::query_scalar("SELECT id FROM music_entries WHERE source = 'storytel'")
        .fetch_one(&app.db)
        .await
        .unwrap();
    let text = app
        .get_page("/music/storytel", &cookie)
        .await
        .text()
        .replace("&#39;", "'");
    assert!(text.contains("value=\"storytel:series:26175\" checked disabled"));
    assert!(text.contains(&format!(
        "<a href=\"/music#entry-{entry}\">In the library</a>"
    )));
    assert!(text.contains("value=\"storytel:series:4242\">"));

    // An empty shelf, and a shelf with no kids' books.
    app.fetch.answer(BOOKSHELF, 200, r#"{"items": {}}"#);
    assert!(
        app.get_page("/music/storytel", &cookie)
            .await
            .text()
            .replace("&#39;", "'")
            .contains("Nothing on the Storytel shelf yet.")
    );
    app.fetch.answer(
        BOOKSHELF,
        200,
        r#"{"items": {"1": {"model": {"id": 1, "title": "Voksen", "formats": [{"type": "abook"}]}}}}"#,
    );
    assert!(
        app.get_page("/music/storytel", &cookie)
            .await
            .text()
            .replace("&#39;", "'")
            .contains("No kids' books on the shelf")
    );
    // Storytel down: the reason, no list.
    app.fetch.answer(BOOKSHELF, 500, "");
    let text = app
        .get_page("/music/storytel", &cookie)
        .await
        .text()
        .replace("&#39;", "'");
    assert!(text.contains("Storytel answered 500 - try again later."));
    assert!(!text.contains("name=\"target\""));
}

#[tokio::test]
async fn add_takes_only_shelf_targets() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    storytel_takes(&app, EMAIL, PASSWORD);
    save(&app, &cookie, EMAIL, PASSWORD).await;

    // A raw target that isn't on the shelf: refused, nothing added (vibb 4c).
    let res = add(
        &app,
        &cookie,
        &["storytel:series:26175", "storytel:series:31337"],
    )
    .await;
    assert_eq!(res.status, StatusCode::BAD_REQUEST);
    assert!(
        res.text()
            .replace("&#39;", "'")
            .contains("isn't on the Storytel shelf")
    );
    assert!(storytel_entries(&app).await.is_empty());
    for junk in [
        "/etc",
        "https://radio.nrk.no/podkast/x",
        "storytel:book:888",
    ] {
        assert_eq!(
            add(&app, &cookie, &[junk]).await.status,
            StatusCode::BAD_REQUEST,
            "{junk}"
        );
    }
    // Nothing ticked.
    let res = add(&app, &cookie, &[]).await;
    assert_eq!(res.status, StatusCode::BAD_REQUEST);
    assert!(
        res.text()
            .replace("&#39;", "'")
            .contains("Tick a series or a book first.")
    );
    assert!(storytel_entries(&app).await.is_empty());

    // Two targets in one form (a repeated field, QA L3): both added, in the library's order.
    let mut rx = app.state.command_notify.subscribe();
    let res = add(
        &app,
        &cookie,
        &["storytel:series:26175", "storytel:book:777"],
    )
    .await;
    assert_eq!(res.location(), Some("/music/storytel?added=2#added"));
    assert_eq!(
        storytel_entries(&app).await,
        vec![
            (
                "Kokosbananas".to_string(),
                "storytel:series:26175".to_string(),
                crate::music::state_key("storytel:series:26175"),
                "auto".to_string(),
                -1,
                true,
                3,
            ),
            (
                "Godnatthistorier".to_string(),
                "storytel:book:777".to_string(),
                crate::music::state_key("storytel:book:777"),
                "auto".to_string(),
                -1,
                true,
                3,
            ),
        ]
    );
    assert!(
        super::music::nudged(&mut rx).is_empty(),
        "no phone is ticked"
    );
    let added = events(&app, "music_entry_added").await;
    assert_eq!(added.len(), 2);
    assert!(added[0].contains("Storytel: Kokosbananas (storytel:series:26175)"));
    let page = app
        .get_page("/music/storytel?added=2", &cookie)
        .await
        .text()
        .replace("&#39;", "'");
    assert!(page.contains("Added 2 - tick them for a phone on its Music card."));
    // On the shelf the shelf cache serves the add (no new bookshelf for each add).
    app.fetch.clear_calls();

    // Already in the library: skipped; a picked category is used; `all` is kept.
    let res = app
        .request_form(
            Method::POST,
            "/music/storytel/add",
            Some(&cookie),
            &[
                ("target", "storytel:series:26175"),
                ("target", "storytel:series:4242"),
                ("category", "2"),
                ("all", "1"),
            ],
        )
        .await;
    assert_eq!(res.location(), Some("/music/storytel?all=1&added=1#added"));
    assert_eq!(app.fetch.count(), 0, "the shelf from memory");
    let entries = storytel_entries(&app).await;
    assert_eq!(entries.len(), 3);
    assert_eq!(entries[2].1, "storytel:series:4242");
    assert_eq!(entries[2].6, 2);
    // A category that doesn't exist.
    let res = app
        .request_form(
            Method::POST,
            "/music/storytel/add",
            Some(&cookie),
            &[("target", "storytel:book:999"), ("category", "99")],
        )
        .await;
    assert_eq!(res.status, StatusCode::BAD_REQUEST);
    assert!(
        res.text()
            .replace("&#39;", "'")
            .contains("That category doesn't exist any more.")
    );

    // At most MAX_ENTRIES target fields are read (code QA L6), repeats included.
    let many: Vec<(&str, &str)> = std::iter::repeat_n(("target", "storytel:book:999"), 201)
        .chain([("category", "3")])
        .collect();
    let res = app
        .request_form(Method::POST, "/music/storytel/add", Some(&cookie), &many)
        .await;
    assert_eq!(res.status, StatusCode::BAD_REQUEST);
    assert!(
        res.text()
            .contains("More than 200 rows are ticked - nothing was added.")
    );
    assert_eq!(storytel_entries(&app).await.len(), 3);
    let two_hundred: Vec<(&str, &str)> =
        std::iter::repeat_n(("target", "storytel:book:999"), 200).collect();
    let res = app
        .request_form(
            Method::POST,
            "/music/storytel/add",
            Some(&cookie),
            &two_hundred,
        )
        .await;
    assert!(res.status.is_redirection(), "{}", res.status);
    assert_eq!(storytel_entries(&app).await.len(), 4, "the repeats are one");
    sqlx::query("DELETE FROM music_entries WHERE target = 'storytel:book:999'")
        .execute(&app.db)
        .await
        .unwrap();

    // The entry limit is the INSERT's own condition.
    sqlx::query(
        "WITH RECURSIVE n(i) AS (SELECT 1 UNION ALL SELECT i + 1 FROM n WHERE i < 197) \
         INSERT INTO music_entries (name, category_id, source, key, cache, resume, sort) \
         SELECT 'own ' || i, 1, 'own', 'own-' || i, -1, 0, 1000 + i FROM n",
    )
    .execute(&app.db)
    .await
    .unwrap();
    let res = add(&app, &cookie, &["storytel:book:999"]).await;
    assert_eq!(res.status, StatusCode::BAD_REQUEST);
    assert!(
        res.text()
            .replace("&#39;", "'")
            .contains("The library has 200 entries")
    );
}

#[tokio::test]
async fn clear_drops_storytel_from_libraries() {
    // Step 2a's half: clearing forgets the server's session and moves the library revision
    // (migration 0072's triggers); step 2b's: the phone's library loses its Storytel entries.
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (device, token) = app.enrolled_device("phone").await;
    let before = revision(&app).await;
    storytel_takes(&app, EMAIL, PASSWORD);
    save(&app, &cookie, EMAIL, PASSWORD).await;
    let saved = revision(&app).await;
    assert!(saved > before, "a login stored moves the revision");
    // A second save keeps a login stored: no move.
    save(&app, &cookie, EMAIL, PASSWORD).await;
    assert_eq!(revision(&app).await, saved);
    // The phone's switch moves it both ways.
    sqlx::query("UPDATE device_policy SET music_storytel = 1 WHERE device_id = ?")
        .bind(device)
        .execute(&app.db)
        .await
        .unwrap();
    let on = revision(&app).await;
    assert!(on > saved);
    sqlx::query("UPDATE device_policy SET music_storytel = 1 WHERE device_id = ?")
        .bind(device)
        .execute(&app.db)
        .await
        .unwrap();
    assert_eq!(revision(&app).await, on, "no change, no move");
    // A ticked series, listed: in the phone's library.
    add(&app, &cookie, &[SERIES]).await;
    let series: i64 = sqlx::query_scalar("SELECT id FROM music_entries WHERE target = ?")
        .bind(SERIES)
        .fetch_one(&app.db)
        .await
        .unwrap();
    tick(&app, &cookie, device, series, true).await;
    check(&app, series, chrono::Utc::now()).await;
    let library = device_get(&app, "/api/devices/music/library", &token, &[]).await;
    assert!(lib_entry(&library.json(), series).is_some());
    let on = revision(&app).await;

    let res = app
        .request_form(Method::POST, "/music/storytel/clear", Some(&cookie), &[])
        .await;
    assert_eq!(res.location(), Some("/music#storytel"));
    assert!(revision(&app).await > on);
    // The entry is gone from the library (phase 1's prune deletes the books), still ticked.
    let library = device_get(&app, "/api/devices/music/library", &token, &[]).await;
    assert_eq!(
        library.status,
        StatusCode::NOT_FOUND,
        "nothing else was ticked"
    );
    let ticked: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM device_music_entries")
        .fetch_one(&app.db)
        .await
        .unwrap();
    assert_eq!(ticked, 1, "the tick stays for the next login");
    let (ciphertext, generation, refused_at, refused_generation, ok_at, error) = row(&app).await;
    assert_eq!(
        (ciphertext, refused_at, refused_generation, ok_at, error),
        (None, None, None, None, None)
    );
    assert_eq!(generation, 3);
    let error_at: Option<String> = sqlx::query_scalar("SELECT login_error_at FROM music_storytel")
        .fetch_one(&app.db)
        .await
        .unwrap();
    assert_eq!(error_at, None);
    // The session went with it: the picker asks Storytel nothing.
    app.fetch.clear_calls();
    let page = app
        .get_page("/music/storytel", &cookie)
        .await
        .text()
        .replace("&#39;", "'");
    assert!(page.contains("No Storytel login is saved"));
    assert_eq!(app.fetch.count(), 0);
    assert!(
        app.state
            .storytel
            .shelf(&app.state, crate::storytel::SHELF_TTL)
            .await
            .is_err()
    );
}

#[tokio::test]
async fn the_entry_page_offers_storytels_choices() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    storytel_takes(&app, EMAIL, PASSWORD);
    save(&app, &cookie, EMAIL, PASSWORD).await;
    add(&app, &cookie, &["storytel:series:26175"]).await;
    let entry: i64 = sqlx::query_scalar("SELECT id FROM music_entries WHERE source = 'storytel'")
        .fetch_one(&app.db)
        .await
        .unwrap();
    let page = app
        .get_page(&format!("/music/entries/{entry}"), &cookie)
        .await
        .text()
        .replace("&#39;", "'");
    for choice in [
        "<option value=\"-1\" selected>All books</option>",
        "<option value=\"5\">The first 5</option>",
        "<option value=\"10\">The first 10</option>",
        "<option value=\"20\">The first 20</option>",
    ] {
        assert!(page.contains(choice), "{choice}");
    }
    assert!(!page.contains(">None</option>"));
    assert!(
        !page.contains("href=\"storytel:"),
        "the target isn't a link"
    );
    let post = |cache: &'static str| {
        let app = &app;
        let cookie = cookie.clone();
        async move {
            app.request_form(
                Method::POST,
                &format!("/music/entries/{entry}"),
                Some(&cookie),
                &[
                    ("name", "Kokosbananas"),
                    ("category", "3"),
                    ("order", "auto"),
                    ("cache", cache),
                    ("resume", "on"),
                ],
            )
            .await
        }
    };
    let cache = || async {
        sqlx::query_scalar::<_, i64>("SELECT cache FROM music_entries WHERE id = ?")
            .bind(entry)
            .fetch_one(&app.db)
            .await
            .unwrap()
    };
    assert!(post("10").await.status.is_redirection());
    assert_eq!(cache().await, 10);
    // 0 is "all" for Storytel (vibb library.py 87-89).
    assert!(post("0").await.status.is_redirection());
    assert_eq!(cache().await, -1);
    // A podcast's choice that isn't Storytel's: refused by the field.
    let res = post("3").await;
    assert_eq!(res.status, StatusCode::BAD_REQUEST);
    assert!(
        res.text()
            .replace("&#39;", "'")
            .contains("Pick one of the offline choices.")
    );
    // A stored value from elsewhere (an import) is offered and kept; a stored 0 shows as all.
    sqlx::query("UPDATE music_entries SET cache = 7 WHERE id = ?")
        .bind(entry)
        .execute(&app.db)
        .await
        .unwrap();
    let page = app
        .get_page(&format!("/music/entries/{entry}"), &cookie)
        .await
        .text()
        .replace("&#39;", "'");
    assert!(page.contains("<option value=\"7\" selected>The first 7 (from Vibb)</option>"));
    assert!(post("7").await.status.is_redirection());
    sqlx::query("UPDATE music_entries SET cache = 0 WHERE id = ?")
        .bind(entry)
        .execute(&app.db)
        .await
        .unwrap();
    let page = app
        .get_page(&format!("/music/entries/{entry}"), &cookie)
        .await
        .text()
        .replace("&#39;", "'");
    assert!(page.contains("<option value=\"-1\" selected>All books</option>"));
}

/// Every event the log filter for `rust_log` lets through, as "LEVEL target".
fn emitted(rust_log: Option<&str>) -> Vec<String> {
    use std::sync::{Arc, Mutex};
    use tracing_subscriber::layer::SubscriberExt;
    struct Capture(Arc<Mutex<Vec<String>>>);
    impl<S: tracing::Subscriber> tracing_subscriber::Layer<S> for Capture {
        fn on_event(
            &self,
            event: &tracing::Event<'_>,
            _: tracing_subscriber::layer::Context<'_, S>,
        ) {
            let meta = event.metadata();
            self.0
                .lock()
                .unwrap()
                .push(format!("{} {}", meta.level(), meta.target()));
        }
    }
    let seen = Arc::new(Mutex::new(Vec::new()));
    let subscriber = tracing_subscriber::registry()
        .with(crate::log_filter(rust_log))
        .with(Capture(seen.clone()));
    tracing::subscriber::with_default(subscriber, || {
        tracing::debug!(target: "reqwest::async_impl::client", "redirecting");
        tracing::trace!(target: "reqwest::connect::verbose", "raw bytes");
        tracing::debug!(target: "reqwest", "top level");
        tracing::info!(target: "reqwest::connect", "host only");
        tracing::debug!(target: "kid_phone_server::storytel", "ours");
    });
    seen.lock().unwrap().clone()
}

#[test]
fn the_log_floors_reqwest_at_info() {
    // QA L10, code QA L2: reqwest never logs below info, whatever RUST_LOG says - a more
    // specific reqwest directive included.
    for rust_log in [
        None,
        Some("debug"),
        Some("trace"),
        Some("reqwest=trace"),
        Some("reqwest"),
        Some("reqwest::async_impl=debug"),
        Some("debug,reqwest::connect=trace"),
        Some("info, reqwest::connect::verbose = trace"),
        Some("info,reqwest[request]=trace"),
        Some("bad=["),
    ] {
        let seen = emitted(rust_log);
        assert!(
            seen.contains(&"INFO reqwest::connect".to_string()),
            "{rust_log:?}: {seen:?}"
        );
        assert!(
            !seen
                .iter()
                .any(|e| e.starts_with("DEBUG reqwest") || e.starts_with("TRACE reqwest")),
            "{rust_log:?}: {seen:?}"
        );
    }
    // The rest of RUST_LOG still applies.
    for rust_log in ["debug", "kid_phone_server=debug,reqwest=trace"] {
        let seen = emitted(Some(rust_log));
        assert!(
            seen.contains(&"DEBUG kid_phone_server::storytel".to_string()),
            "{rust_log}: {seen:?}"
        );
    }
    assert!(!emitted(None).contains(&"DEBUG kid_phone_server::storytel".to_string()));
}

// ------------------------------------------------------------------------------------------------
// Step 2b (design 26b §2.4-§2.7): the sweep lists Storytel entries, the library's gate, the cards
// ------------------------------------------------------------------------------------------------

use super::music::{device_get, tick};
use super::music_art::png;
use crate::music_sweep::{self as sweep, CheckReport, stamp};

const SERIES: &str = "storytel:series:26175";
const GEO_SERIES: &str = "storytel:series:4242";
const BOOK: &str = "storytel:book:777";
const COVER_2: &str = "http://c.example.invalid/2-model.jpg";
/// Book 1's own cover (the abook format's), not the series'.
const COVER_1: &str = "http://c.example.invalid/1.jpg";

fn jpeg(shade: u8) -> Vec<u8> {
    let image = image::RgbImage::from_pixel(64, 64, image::Rgb([shade, 100, 50]));
    let mut out = std::io::Cursor::new(Vec::new());
    image::DynamicImage::ImageRgb8(image)
        .write_to(&mut out, image::ImageFormat::Jpeg)
        .unwrap();
    out.into_inner()
}

/// The fixture shelf without the items named in `drop`.
fn shelf_without(drop: &[&str]) -> Vec<u8> {
    let mut shelf: serde_json::Value = serde_json::from_slice(&fixture("bookshelf.json")).unwrap();
    let items = shelf["items"].as_object_mut().unwrap();
    for key in drop {
        items.remove(*key);
    }
    serde_json::to_vec(&shelf).unwrap()
}

/// The fixture shelf with `key` added as `item`.
fn shelf_with(key: &str, item: serde_json::Value) -> Vec<u8> {
    let mut shelf: serde_json::Value = serde_json::from_slice(&fixture("bookshelf.json")).unwrap();
    shelf["items"]
        .as_object_mut()
        .unwrap()
        .insert(key.to_string(), item);
    serde_json::to_vec(&shelf).unwrap()
}

/// A saved login (the probe's shelf kept), the phone's Storytel switch on, and the shelf's
/// `targets` added from the picker. Returns each target's entry id.
async fn storytel_library(app: &TestApp, cookie: &str, device: i64, targets: &[&str]) -> Vec<i64> {
    storytel_takes(app, EMAIL, PASSWORD);
    save(app, cookie, EMAIL, PASSWORD).await;
    sqlx::query("UPDATE device_policy SET music_storytel = 1 WHERE device_id = ?")
        .bind(device)
        .execute(&app.db)
        .await
        .unwrap();
    let res = add(app, cookie, targets).await;
    assert!(res.status.is_redirection(), "{}", res.text());
    let mut ids = Vec::new();
    for target in targets {
        ids.push(
            sqlx::query_scalar("SELECT id FROM music_entries WHERE target = ?")
                .bind(target)
                .fetch_one(&app.db)
                .await
                .unwrap(),
        );
    }
    ids
}

async fn check(app: &TestApp, entry: i64, now: chrono::DateTime<chrono::Utc>) -> CheckReport {
    sweep::check_entry(&app.state, entry, now)
        .await
        .unwrap()
        .expect("the entry is checked")
}

/// Check now, as the card's button asks for it (the limits are the handler's).
async fn check_now(app: &TestApp, entry: i64, now: chrono::DateTime<chrono::Utc>) -> CheckReport {
    sweep::request_check(&app.db, entry, now).await.unwrap();
    check(app, entry, now).await
}

async fn items(app: &TestApp, entry: i64) -> Vec<sweep::Item> {
    let mut conn = app.db.acquire().await.unwrap();
    sweep::load_items(&mut conn, entry).await.unwrap()
}

fn keys(items: &[sweep::Item]) -> Vec<&str> {
    items.iter().map(|i| i.key.as_str()).collect()
}

fn storytel_calls(app: &TestApp) -> Vec<String> {
    app.fetch
        .calls
        .lock()
        .unwrap()
        .iter()
        .filter(|url| url.starts_with(LOGIN_URL) || url.as_str() == BOOKSHELF)
        .map(|url| {
            if url.starts_with(LOGIN_URL) {
                "login".to_string()
            } else {
                "shelf".to_string()
            }
        })
        .collect()
}

fn lib_entry(library: &serde_json::Value, id: i64) -> Option<serde_json::Value> {
    library["entries"]
        .as_array()
        .unwrap()
        .iter()
        .find(|e| e["id"] == id)
        .cloned()
}

/// vibb `storytel_sync.py` 1/1b and `storytel_expand.py` 2 on the server's side (§2.4): a series is
/// listed like a feed - one `ok` item per book in reading order, keyed by `model.id`, no URL, the
/// shelf's duration and each book's own cover - the locked and the geo-flagged books included; the
/// listing is anchored at the first book, its title and cover the series'. The phone gets it through
/// the listing route and the library names its version. Step 4a: a book's own cover is its item's
/// `art` hash (design 27's art pass); the book whose cover is the series' has none (`same`).
#[tokio::test]
async fn the_sweep_lists_a_series_like_a_feed() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (device, token) = app.enrolled_device("phone").await;
    let ids = storytel_library(&app, &cookie, device, &[SERIES, GEO_SERIES, BOOK]).await;
    let (series, geo, book) = (ids[0], ids[1], ids[2]);
    tick(&app, &cookie, device, series, true).await;
    app.fetch.answer(COVER_2, 200, jpeg(40));
    app.fetch.answer(COVER_1, 200, png(0b1010_0101, 300, 300));
    app.fetch.clear_calls();

    // The probe's shelf is fresh: the check asks Storytel nothing; the series' cover is fetched,
    // then book 1's own (book 2's is the series' URL: no request; book 3 has none).
    let report = check(&app, series, chrono::Utc::now()).await;
    assert_eq!(report.error, None);
    assert_eq!(report.new_items, 3);
    assert_eq!(report.requests, 2, "the cover and book 1's");
    assert_eq!((report.art_own, report.art_same), (1, 1));
    assert!(storytel_calls(&app).is_empty());
    assert_eq!(
        *app.fetch.calls.lock().unwrap(),
        vec![COVER_2.to_string(), COVER_1.to_string()]
    );
    assert_eq!(
        report.phones,
        vec![device],
        "a new list nudges the phone with it"
    );

    let listed = items(&app, series).await;
    assert_eq!(keys(&listed), ["160661", "160662", "160663"]);
    assert!(
        listed
            .iter()
            .all(|i| i.state == "ok" && i.url.is_none() && !i.hls)
    );
    assert_eq!(
        listed.iter().map(|i| i.duration_ms).collect::<Vec<_>>(),
        [Some(721_893), Some(700_000), Some(650_000)]
    );
    assert_eq!(
        listed
            .iter()
            .map(|i| i.art_url.as_deref())
            .collect::<Vec<_>>(),
        [Some(COVER_1), Some(COVER_2), None],
        "each book's own cover"
    );
    assert_eq!(
        listed
            .iter()
            .map(|i| i.art_state.as_deref())
            .collect::<Vec<_>>(),
        [Some("own"), Some("same"), None]
    );
    let book_1_art = listed[0]
        .art_hash
        .clone()
        .expect("book 1's cover is stored");
    assert!(
        app.state
            .music_art_dir
            .join(format!("{book_1_art}.jpg"))
            .exists()
    );
    assert_eq!(
        (listed[1].art_hash.as_ref(), listed[2].art_hash.as_ref()),
        (None, None)
    );
    assert_eq!(
        listed[2].title.as_deref(),
        Some("Kokosbananas og den låste boka"),
        "a book Storytel marks locked is listed: the phone tries it"
    );
    let listing = sweep::load_listing(&app.db, series).await.unwrap().unwrap();
    assert_eq!(listing.title.as_deref(), Some("Kokosbananas"));
    assert_eq!(listing.keep_end.as_deref(), Some("first"));
    assert_eq!(
        listing.cover_url.as_deref(),
        Some(COVER_2),
        "the series' cover"
    );
    assert!(listing.cover_hash.is_some());
    assert_eq!(
        (listing.item_count, listing.capped, listing.lan),
        (3, false, Some(false))
    );
    let version = listing.version.clone().unwrap();

    // The listing route: url null, the art, the version; the library names it.
    let res = device_get(
        &app,
        &format!("/api/devices/music/entries/{series}/items"),
        &token,
        &[],
    )
    .await;
    assert_eq!(res.status, StatusCode::OK);
    let served = res.json();
    assert_eq!(served["version"], serde_json::json!(version));
    assert_eq!(served["items"][0]["key"], serde_json::json!("160661"));
    assert_eq!(served["items"][0]["url"], serde_json::Value::Null);
    // A listing's `art` is the hash of the item's own stored picture (design 27): book 1's cover;
    // book 2's is the series' (the phone shows the entry's cover), book 3 has none.
    assert_eq!(served["items"][0]["art"], serde_json::json!(book_1_art));
    assert_eq!(served["items"][1]["art"], serde_json::Value::Null);
    assert_eq!(served["items"][2]["art"], serde_json::Value::Null);
    assert_eq!(
        served["items"][2]["duration_ms"],
        serde_json::json!(650_000)
    );
    let library = device_get(&app, "/api/devices/music/library", &token, &[])
        .await
        .json();
    let entry = lib_entry(&library, series).unwrap();
    assert_eq!(entry["items"], serde_json::json!(version));
    assert_eq!(entry["cover"], serde_json::json!(listing.cover_hash));
    assert_eq!(entry["cache"], serde_json::json!(-1));
    assert_eq!(
        entry["order"],
        serde_json::json!("auto"),
        "auto: book 1 first on the phone"
    );
    let cover = listing.cover_hash.unwrap();
    let res = device_get(
        &app,
        &format!("/api/devices/music/covers/{cover}"),
        &token,
        &[],
    )
    .await;
    assert_eq!(
        res.status,
        StatusCode::OK,
        "the series' cover is the phone's"
    );
    let res = device_get(
        &app,
        &format!("/api/devices/music/covers/{book_1_art}"),
        &token,
        &[],
    )
    .await;
    assert_eq!(res.status, StatusCode::OK, "and book 1's");
    assert_eq!(
        hex::encode(<sha2::Sha256 as sha2::Digest>::digest(&res.body)),
        book_1_art,
        "the stored picture"
    );

    // A series whose one book is flagged "not in this country" and a standalone book: listed too.
    assert_eq!(check(&app, geo, chrono::Utc::now()).await.error, None);
    assert_eq!(keys(&items(&app, geo).await), ["555"]);
    assert_eq!(check(&app, book, chrono::Utc::now()).await.error, None);
    assert_eq!(keys(&items(&app, book).await), ["777"]);
    assert!(
        storytel_calls(&app).is_empty(),
        "one shelf served all three"
    );

    // The cards: "N books", no newest line, Check now and Storytel's offline choices.
    let page = app
        .get_page("/music", &cookie)
        .await
        .text()
        .replace("&#39;", "'");
    assert!(page.contains("3 books"), "{page}");
    assert!(page.contains("1 book<"));
    assert!(!page.contains("newest: Kokosbananas"));
    assert!(page.contains(&format!("action=\"/music/entries/{series}/check\"")));
    assert!(page.contains("Storytel series"));
    assert!(page.contains("Storytel book"));
    assert!(page.contains("<option value=\"-1\" selected>All books</option>"));
}

/// Check now reads a fresh shelf (QA L5); a book that leaves the shelf leaves the list (§2.4: no
/// `gone`), one that comes back is new again; a group no longer on the shelf fails the check
/// `not_on_shelf` and the phones keep the last good list (vibb leaves the old shelf.json).
#[tokio::test]
async fn check_now_follows_the_shelf() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (device, _) = app.enrolled_device("phone").await;
    let series = storytel_library(&app, &cookie, device, &[SERIES]).await[0];
    tick(&app, &cookie, device, series, true).await;
    let t0 = chrono::Utc::now();
    check(&app, series, t0).await;
    let first = sweep::load_listing(&app.db, series).await.unwrap().unwrap();

    // Book 3 left the shelf in Storytel's app: a routine check still reads the kept shelf...
    app.fetch.answer(BOOKSHELF, 200, shelf_without(&["160663"]));
    app.fetch.clear_calls();
    let routine = check(&app, series, t0 + TimeDelta::minutes(1)).await;
    assert_eq!(routine.error, None);
    assert!(storytel_calls(&app).is_empty());
    assert_eq!(keys(&items(&app, series).await).len(), 3);
    // ...Check now reads it fresh (the kept JWT, one request) and the book leaves the list.
    let report = check_now(&app, series, t0 + TimeDelta::minutes(2)).await;
    assert_eq!(report.error, None);
    assert_eq!(storytel_calls(&app), ["shelf"]);
    assert_eq!(report.requests, 1);
    assert!(report.changed);
    assert_eq!(keys(&items(&app, series).await), ["160661", "160662"]);
    let second = sweep::load_listing(&app.db, series).await.unwrap().unwrap();
    assert_ne!(second.version, first.version);
    assert_eq!(second.item_count, 2);
    // Back on the shelf: listed again, new.
    app.fetch.answer(BOOKSHELF, 200, fixture("bookshelf.json"));
    let report = check_now(&app, series, t0 + TimeDelta::minutes(3)).await;
    assert_eq!(report.new_items, 1);
    assert_eq!(
        keys(&items(&app, series).await),
        ["160661", "160662", "160663"]
    );
    assert_eq!(
        sweep::load_listing(&app.db, series)
            .await
            .unwrap()
            .unwrap()
            .version,
        first.version,
        "the same list, the same version"
    );

    // The series is gone from the shelf: the check fails, the last good list stays.
    app.fetch.answer(
        BOOKSHELF,
        200,
        shelf_without(&["160661", "160662", "160663"]),
    );
    let report = check_now(&app, series, t0 + TimeDelta::minutes(4)).await;
    assert_eq!(report.error.as_deref(), Some("not_on_shelf"));
    assert_eq!(keys(&items(&app, series).await).len(), 3);
    let failed = sweep::load_listing(&app.db, series).await.unwrap().unwrap();
    assert_eq!(failed.version, first.version);
    assert_eq!(failed.error.as_deref(), Some("not_on_shelf"));
    assert_eq!(failed.failures, 1);
    let page = app
        .get_page("/music", &cookie)
        .await
        .text()
        .replace("&#39;", "'");
    assert!(
        page.contains("no longer on the Storytel shelf. The phones keep the list"),
        "{page}"
    );

    // The card's Check now takes a Storytel entry (QA M3) and wakes the sweep.
    sqlx::query("DELETE FROM music_check_requests")
        .execute(&app.db)
        .await
        .unwrap();
    sqlx::query("UPDATE music_listings SET requested_at = NULL WHERE entry_id = ?")
        .bind(series)
        .execute(&app.db)
        .await
        .unwrap();
    let res = app
        .request_form(
            Method::POST,
            &format!("/music/entries/{series}/check"),
            Some(&cookie),
            &[],
        )
        .await;
    assert_eq!(res.location(), Some(&*format!("/music#entry-{series}")));
    assert!(app.state.music_sweep.woken().await);
    let requested: Option<String> =
        sqlx::query_scalar("SELECT requested_at FROM music_listings WHERE entry_id = ?")
            .bind(series)
            .fetch_one(&app.db)
            .await
            .unwrap();
    assert!(requested.is_some());
    // `/music/cards` answers for it too.
    let cards = app
        .get_page(&format!("/music/cards?ids={series}"), &cookie)
        .await
        .json();
    assert_eq!(cards["cards"][0]["id"], serde_json::json!(series));
}

/// The code QA of 2a: `Account::shelf` falls back to the kept shelf on a refusal, so the check reads
/// the row's cooldown first - inside it the check fails `login_refused` and Storytel is asked
/// nothing (vibb 10b); the last good list stays. Without a login: `storytel_off`, nothing asked.
#[tokio::test]
async fn a_refused_login_fails_the_check_without_a_request() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (device, _) = app.enrolled_device("phone").await;
    let series = storytel_library(&app, &cookie, device, &[SERIES]).await[0];
    let t0 = chrono::Utc::now();
    check(&app, series, t0).await;
    // Storytel refused the saved login (the picker, the other day's check) a minute ago.
    sqlx::query(
        "UPDATE music_storytel SET login_refused_at = ?, login_refused_generation = generation, \
         login_error = 'login_refused', login_error_at = ?",
    )
    .bind(stamp(t0))
    .bind(stamp(t0))
    .execute(&app.db)
    .await
    .unwrap();
    app.fetch.clear_calls();
    let report = check_now(&app, series, t0 + TimeDelta::minutes(1)).await;
    assert_eq!(report.error.as_deref(), Some("login_refused"));
    assert_eq!(report.requests, 0);
    assert_eq!(app.fetch.count(), 0, "inside the cooldown nothing is sent");
    assert_eq!(
        keys(&items(&app, series).await).len(),
        3,
        "the last good list"
    );
    let page = app
        .get_page("/music", &cookie)
        .await
        .text()
        .replace("&#39;", "'");
    assert!(page.contains("Storytel refused the login. The phones keep the list"));
    // An hour later the login is tried again (one login, then the shelf).
    app.state
        .music_sweep
        .set_clock(Some(t0 + TimeDelta::minutes(62)));
    app.state
        .storytel
        .advance(std::time::Duration::from_secs(3601));
    let report = check_now(&app, series, t0 + TimeDelta::minutes(62)).await;
    assert_eq!(report.error, None);
    assert_eq!(storytel_calls(&app), ["login", "shelf"]);
    assert_eq!(
        report.requests, 2,
        "the login counts against the check's budget"
    );

    // The login cleared: `storytel_off` without a request; a never-listed entry says why.
    app.request_form(Method::POST, "/music/storytel/clear", Some(&cookie), &[])
        .await;
    let fresh: i64 = sqlx::query_scalar(
        "INSERT INTO music_entries (name, category_id, source, target, key, play_order, cache, \
         resume, sort) VALUES ('Godnatt', 3, 'storytel', ?, ?, 'auto', -1, 1, 99) RETURNING id",
    )
    .bind(BOOK)
    .bind(crate::music::state_key(BOOK))
    .fetch_one(&app.db)
    .await
    .unwrap();
    app.fetch.clear_calls();
    let report = check(&app, fresh, t0 + TimeDelta::minutes(63)).await;
    assert_eq!(report.error.as_deref(), Some("storytel_off"));
    assert_eq!(app.fetch.count(), 0);
    let page = app
        .get_page("/music", &cookie)
        .await
        .text()
        .replace("&#39;", "'");
    assert!(page.contains("Couldn't list it: No Storytel login is saved on this server."));
}

/// vibb `storytel_sync.py` 6b: one pass with several Storytel entries reads the shelf once.
#[tokio::test]
async fn one_shelf_serves_a_pass() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (device, _) = app.enrolled_device("phone").await;
    let ids = storytel_library(&app, &cookie, device, &[SERIES, GEO_SERIES, BOOK]).await;
    // A restart: no JWT, no shelf in memory.
    app.state.storytel.forget().await;
    app.state
        .music_sweep
        .set_limits(sweep::MAX_CHECK_REQUESTS, std::time::Duration::ZERO);
    app.fetch.clear_calls();
    assert!(sweep::run_pass(&app.state).await);
    assert_eq!(storytel_calls(&app), ["login", "shelf"]);
    let mut counted = 0;
    for id in ids {
        let listing = sweep::load_listing(&app.db, id).await.unwrap().unwrap();
        assert!(listing.version.is_some(), "entry {id} listed");
        counted += listing.requests;
    }
    // Every request of the pass (the login, the shelf, the covers) is counted in some check.
    assert_eq!(counted as usize, app.fetch.count());
}

/// §2.7: a phone's library holds Storytel entries only while its switch is on and a login is
/// stored; the listing and the cover routes follow (404); each change moves the revision, so the
/// cached library is rebuilt and the phone prunes. Rows 4 and 5 of vibb `storytel_routes.py` on the
/// library's side: per-phone downloads on the card.
#[tokio::test]
async fn library_gates_storytel() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (device, token) = app.enrolled_device("phone").await;
    let (other, other_token) = app.enrolled_device("other").await;
    let series = storytel_library(&app, &cookie, device, &[SERIES]).await[0];
    tick(&app, &cookie, device, series, true).await;
    tick(&app, &cookie, other, series, true).await;
    app.fetch.answer(COVER_2, 200, jpeg(60));
    check(&app, series, chrono::Utc::now()).await;
    let cover = sweep::load_listing(&app.db, series)
        .await
        .unwrap()
        .unwrap()
        .cover_hash
        .unwrap();
    let has = |token: String| {
        let app = &app;
        let cover = cover.clone();
        async move {
            let library = device_get(app, "/api/devices/music/library", &token, &[]).await;
            let listing = device_get(
                app,
                &format!("/api/devices/music/entries/{series}/items"),
                &token,
                &[],
            )
            .await;
            let cover = device_get(
                app,
                &format!("/api/devices/music/covers/{cover}"),
                &token,
                &[],
            )
            .await;
            let in_library =
                library.status == StatusCode::OK && lib_entry(&library.json(), series).is_some();
            (in_library, listing.status, cover.status)
        }
    };
    // `device` has the switch on; `other` hasn't.
    assert_eq!(
        has(token.clone()).await,
        (true, StatusCode::OK, StatusCode::OK)
    );
    assert_eq!(
        has(other_token.clone()).await,
        (false, StatusCode::NOT_FOUND, StatusCode::NOT_FOUND)
    );
    // A parent's own cover on the entry (`cover_in_library`'s first branch) follows the gate too.
    sqlx::query("UPDATE music_entries SET cover_hash = ? WHERE id = ?")
        .bind(&cover)
        .bind(series)
        .execute(&app.db)
        .await
        .unwrap();
    assert_eq!(
        has(token.clone()).await,
        (true, StatusCode::OK, StatusCode::OK)
    );
    assert_eq!(
        has(other_token.clone()).await,
        (false, StatusCode::NOT_FOUND, StatusCode::NOT_FOUND)
    );
    // The library card says so per phone, and counts only the phone that gets it (QA L2).
    let music = app
        .get_page("/music", &cookie)
        .await
        .text()
        .replace("&#39;", "'");
    assert!(
        music.contains("other: not sent - Storytel is off on this phone"),
        "{music}"
    );
    assert!(music.contains("on 1 phone<") || music.contains("on 1 phone "));
    // Its device card says why.
    let page = app
        .get_page(&format!("/devices/{other}"), &cookie)
        .await
        .text()
        .replace("&#39;", "'");
    assert!(page.contains("Storytel is off on this phone: its Storytel entry isn't sent."));
    assert!(page.contains("This includes Storytel books, often several hundred MB each."));
    // The switch on: in.
    let before = revision(&app).await;
    sqlx::query("UPDATE device_policy SET music_storytel = 1 WHERE device_id = ?")
        .bind(other)
        .execute(&app.db)
        .await
        .unwrap();
    assert!(revision(&app).await > before);
    assert_eq!(
        has(other_token.clone()).await,
        (true, StatusCode::OK, StatusCode::OK)
    );
    // The login cleared: out on both phones, the card says why.
    let before = revision(&app).await;
    app.request_form(Method::POST, "/music/storytel/clear", Some(&cookie), &[])
        .await;
    assert!(revision(&app).await > before);
    for t in [token.clone(), other_token.clone()] {
        assert_eq!(
            has(t).await,
            (false, StatusCode::NOT_FOUND, StatusCode::NOT_FOUND)
        );
    }
    let page = app
        .get_page(&format!("/devices/{device}"), &cookie)
        .await
        .text()
        .replace("&#39;", "'");
    assert!(page.contains("No Storytel login is saved: its Storytel entry isn't sent."));
    // After the clear: every phone's line says why, and a failed check doesn't promise the list.
    let music = app
        .get_page("/music", &cookie)
        .await
        .text()
        .replace("&#39;", "'");
    assert!(music.contains("phone: not sent - no Storytel login is saved"));
    assert!(music.contains("other: not sent - no Storytel login is saved"));
    assert!(music.contains("on 0 phones"));
    let report = check(&app, series, chrono::Utc::now()).await;
    assert_eq!(report.error.as_deref(), Some("storytel_off"));
    let music = app
        .get_page("/music", &cookie)
        .await
        .text()
        .replace("&#39;", "'");
    assert!(
        music.contains(
            "no Storytel login on this server. The phones don't get it until a login is saved."
        ),
        "{music}"
    );
    assert!(!music.contains("The phones keep the list"));
}

/// The phone's report on the cards (§2.3, §2.5): books on the phone with the `login` wait, a
/// `no_audio` book, the phone's own login as words.
#[tokio::test]
async fn the_cards_show_the_phones_storytel() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (device, token) = app.enrolled_device("Ella").await;
    let series = storytel_library(&app, &cookie, device, &[SERIES]).await[0];
    tick(&app, &cookie, device, series, true).await;
    check(&app, series, chrono::Utc::now()).await;
    let version = sweep::load_listing(&app.db, series)
        .await
        .unwrap()
        .unwrap()
        .version
        .unwrap();
    let state = serde_json::json!({
        "storytel": "refused",
        "downloads": [{"entry": series, "version": version, "have": 1, "want": 3, "waiting": "login"}],
        "item_errors": [
            {"entry": series, "version": version, "item": "160662", "error": "http_403"},
            {"entry": series, "version": version, "item": "160663", "error": "no_audio"}
        ],
    });
    let res = app
        .request(
            Method::POST,
            "/api/devices/status",
            Some(&token),
            Some(serde_json::json!({
                "lock_reason": "NONE",
                "kiosk_engaged": true,
                "capabilities": ["music_v1"],
                "music_state": state
            })),
        )
        .await;
    assert!(res.status.is_success(), "{} {}", res.status, res.text());
    let page = app
        .get_page("/music", &cookie)
        .await
        .text()
        .replace("&#39;", "'");
    assert!(
        page.contains(
            "Ella: 1 of 3 books on the phone · waiting for the Storytel login on the phone · 2 \
             failing (Storytel answered 403)"
        ),
        "{page}"
    );
    let card = app
        .get_page(&format!("/devices/{device}"), &cookie)
        .await
        .text()
        .replace("&#39;", "'");
    assert!(card.contains(
        "Storytel refused the phone's login - it waits an hour; saving the password again \
         retries at once."
    ));
    // A Storytel item's error never flags a re-resolve, not even a 403 (§2.4: the phone mints its
    // own URL; `flag_reports` skips Storytel).
    let flagged: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM music_items WHERE recheck = 1")
        .fetch_one(&app.db)
        .await
        .unwrap();
    assert_eq!(flagged, 0);
}

/// QA M1 of 2b: the sweep's read never falls back to the kept shelf - a refused login after the
/// cooldown and the JWT have run out fails the check `login_refused` (one login, the list kept),
/// an outage on Check now fails it `network`; the card says so instead of "Checked".
#[tokio::test]
async fn the_sweeps_read_never_falls_back_to_a_kept_shelf() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (device, _) = app.enrolled_device("phone").await;
    let series = storytel_library(&app, &cookie, device, &[SERIES]).await[0];
    let t0 = chrono::Utc::now();
    app.state.music_sweep.set_clock(Some(t0));
    check(&app, series, t0).await;
    // Six hours on: the kept shelf, the JWT and any cooldown are long over; the password was
    // changed in Storytel's app.
    let later = t0 + TimeDelta::hours(6);
    app.state.music_sweep.set_clock(Some(later));
    app.state
        .storytel
        .advance(std::time::Duration::from_secs(6 * 3600));
    app.fetch.answer(&login(EMAIL, PASSWORD), 401, "nope");
    app.fetch.clear_calls();
    let report = check(&app, series, later).await;
    assert_eq!(report.error.as_deref(), Some("login_refused"));
    assert_eq!(storytel_calls(&app), ["login"]);
    assert_eq!(report.requests, 1);
    assert_eq!(
        keys(&items(&app, series).await).len(),
        3,
        "the last good list"
    );
    let page = app
        .get_page("/music", &cookie)
        .await
        .text()
        .replace("&#39;", "'");
    assert!(page.contains("Storytel refused the login. The phones keep the list"));
    assert!(!page.contains("Checked just now"));

    // The password saved again; then Storytel is unreachable on Check now: `network`.
    let saved = later + TimeDelta::minutes(1);
    app.state.music_sweep.set_clock(Some(saved));
    storytel_takes(&app, EMAIL, PASSWORD);
    save(&app, &cookie, EMAIL, PASSWORD).await;
    app.fetch.fail(
        BOOKSHELF,
        FetchError::Network("connection reset".to_string()),
    );
    app.fetch.clear_calls();
    let report = check_now(&app, series, saved + TimeDelta::minutes(1)).await;
    assert_eq!(report.error.as_deref(), Some("network"));
    assert_eq!(storytel_calls(&app), ["shelf"]);
    assert_eq!(keys(&items(&app, series).await).len(), 3);
    let page = app
        .get_page("/music", &cookie)
        .await
        .text()
        .replace("&#39;", "'");
    assert!(page.contains("Storytel couldn't be reached. The phones keep the list"));
    // The picker's Add still takes the kept shelf (vibb's "stale beats dead").
    assert!(
        app.state
            .storytel
            .shelf(&app.state, std::time::Duration::ZERO)
            .await
            .is_ok()
    );
}

/// QA L1 of 2b: an entry that failed for want of a login (imported first, the natural order coming
/// from the Pi) is due as soon as the login is saved, and the probe's shelf lists it.
#[tokio::test]
async fn saving_a_login_lists_the_entries_waiting_for_one() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    app.state
        .music_sweep
        .set_limits(sweep::MAX_CHECK_REQUESTS, std::time::Duration::ZERO);
    let entry: i64 = sqlx::query_scalar(
        "INSERT INTO music_entries (name, category_id, source, target, key, play_order, cache, \
         resume, sort) VALUES ('Kokosbananas', 3, 'storytel', ?, ?, 'auto', -1, 1, 10) \
         RETURNING id",
    )
    .bind(SERIES)
    .bind(crate::music::state_key(SERIES))
    .fetch_one(&app.db)
    .await
    .unwrap();
    let t0 = chrono::Utc::now();
    app.state.music_sweep.set_clock(Some(t0));
    assert!(sweep::run_pass(&app.state).await);
    let failed = sweep::load_listing(&app.db, entry).await.unwrap().unwrap();
    assert_eq!(failed.error.as_deref(), Some("storytel_off"));
    // A minute later the login is saved: the sweep is woken and the entry is due at once.
    app.state
        .music_sweep
        .set_clock(Some(t0 + TimeDelta::minutes(1)));
    storytel_takes(&app, EMAIL, PASSWORD);
    let _ = app.state.music_sweep.woken().await;
    save(&app, &cookie, EMAIL, PASSWORD).await;
    assert!(
        app.state.music_sweep.woken().await,
        "the save wakes the sweep"
    );
    let due = sweep::due(&app.db, t0 + TimeDelta::minutes(1))
        .await
        .unwrap();
    assert_eq!(due.iter().map(|s| s.entry_id).collect::<Vec<_>>(), [entry]);
    app.fetch.clear_calls();
    assert!(sweep::run_pass(&app.state).await);
    let listed = sweep::load_listing(&app.db, entry).await.unwrap().unwrap();
    assert!(listed.version.is_some());
    assert_eq!(listed.error, None);
    assert!(
        storytel_calls(&app).is_empty(),
        "the probe's shelf served it"
    );
}

/// A book the shelf gives no duration (0 or absent: vibb's `or 0`, "unknown") is listed with
/// `duration_ms: null`, never 0 - a 0-long book would read as finished (QA L5 of 2b).
#[tokio::test]
async fn a_book_without_a_duration_is_listed_without_one() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (device, token) = app.enrolled_device("phone").await;
    let series = storytel_library(&app, &cookie, device, &[SERIES]).await[0];
    tick(&app, &cookie, device, series, true).await;
    app.fetch.answer(
        BOOKSHELF,
        200,
        shelf_with(
            "160664",
            serde_json::json!({"model": {
                "id": "160664", "title": "Kokosbananas og den nye boka", "kidsBook": true,
                "seriesInfo": {"id": "26175", "name": "Kokosbananas", "orderInSeries": 4},
                "formats": [{"type": "abook", "id": "fmt-4", "durationInMilliseconds": 0}]
            }}),
        ),
    );
    let report = check_now(&app, series, chrono::Utc::now()).await;
    assert_eq!(report.error, None);
    let res = device_get(
        &app,
        &format!("/api/devices/music/entries/{series}/items"),
        &token,
        &[],
    )
    .await;
    let served = res.json();
    assert_eq!(served["items"][3]["key"], serde_json::json!("160664"));
    assert_eq!(served["items"][3]["duration_ms"], serde_json::Value::Null);
    assert_eq!(
        served["items"][0]["duration_ms"],
        serde_json::json!(721_893)
    );
}

// ------------------------------------------------------------------------------------------------
// Step 4a (design 26b §5.5, rebuilt on design 27): a book's own cover is its item's `art` hash
// ------------------------------------------------------------------------------------------------

/// The fixture shelf with these books' abook covers set to these URLs: book 1's replaced, another
/// book's added. Edited as text, since a re-serialized shelf would come out with its keys sorted
/// (this build's `serde_json::Map`) and the series' cover is the first-seen book's.
fn shelf_covers(covers: &[(&str, &str)]) -> Vec<u8> {
    let mut shelf = String::from_utf8(fixture("bookshelf.json")).unwrap();
    for (key, url) in covers {
        if *key == "160661" {
            shelf = shelf.replace(COVER_1, url);
            continue;
        }
        let at = shelf.find(&format!("\"{key}\": {{\"model\"")).unwrap();
        let abook = "{\"type\": \"abook\", ";
        let at = at + shelf[at..].find(abook).unwrap() + abook.len();
        shelf.insert_str(at, &format!("\"cover\": {{\"url\": \"{url}\"}}, "));
    }
    shelf.into_bytes()
}

/// How many requests went to `url`.
fn fetched(app: &TestApp, url: &str) -> usize {
    app.fetch
        .calls
        .lock()
        .unwrap()
        .iter()
        .filter(|u| u.as_str() == url)
        .count()
}

fn art_of(items: &[sweep::Item]) -> Vec<(Option<&str>, Option<&str>)> {
    items
        .iter()
        .map(|i| (i.art_state.as_deref(), i.art_hash.as_deref()))
        .collect()
}

async fn served_art(
    app: &TestApp,
    token: &str,
    entry: i64,
) -> (serde_json::Value, Vec<serde_json::Value>) {
    let res = device_get(
        app,
        &format!("/api/devices/music/entries/{entry}/items"),
        token,
        &[],
    )
    .await;
    assert_eq!(res.status, StatusCode::OK, "{}", res.text());
    let doc = res.json();
    let art = doc["items"]
        .as_array()
        .unwrap()
        .iter()
        .map(|i| i["art"].clone())
        .collect();
    (doc["version"].clone(), art)
}

/// A check keeps what the art pass found for a book (the row is carried over, not rebuilt): no
/// image request, the same hash, the same version. The same cover under a new query is adopted
/// without a request; a cover that moves to another path is fetched again like a feed episode's
/// (design 27: not within a week of its result, then once), and the new hash restamps the listing.
#[tokio::test]
async fn a_check_keeps_a_books_cover_and_a_moved_cover_is_fetched_again() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (device, token) = app.enrolled_device("phone").await;
    let series = storytel_library(&app, &cookie, device, &[SERIES]).await[0];
    tick(&app, &cookie, device, series, true).await;
    app.fetch.answer(COVER_2, 200, jpeg(40));
    app.fetch.answer(COVER_1, 200, png(0b1010_0101, 300, 300));
    let t0 = chrono::Utc::now();
    check(&app, series, t0).await;
    let first = items(&app, series).await;
    let hash_1 = first[0].art_hash.clone().unwrap();
    let (version, art) = served_art(&app, &token, series).await;
    assert_eq!(
        art,
        [
            serde_json::json!(hash_1),
            serde_json::Value::Null,
            serde_json::Value::Null
        ]
    );

    // A routine check (the kept shelf) and Check now (a fresh one): no picture asked for again.
    app.fetch.clear_calls();
    let routine = check(&app, series, t0 + TimeDelta::minutes(1)).await;
    assert_eq!((routine.error, routine.requests), (None, 0));
    assert!(!routine.changed);
    let now = check_now(&app, series, t0 + TimeDelta::minutes(2)).await;
    assert_eq!((now.error, now.requests), (None, 1), "the shelf only");
    assert_eq!(storytel_calls(&app), ["shelf"]);
    assert_eq!(app.fetch.count(), 1);
    assert!(!now.changed);
    let kept = items(&app, series).await;
    assert_eq!(kept, first, "every art field carried over");
    assert_eq!(served_art(&app, &token, series).await.0, version);

    // The same picture under a new query: adopted, no request.
    let query = "http://c.example.invalid/1.jpg?v=2";
    app.fetch
        .answer(BOOKSHELF, 200, shelf_covers(&[("160661", query)]));
    app.fetch.clear_calls();
    let report = check_now(&app, series, t0 + TimeDelta::minutes(3)).await;
    assert_eq!(report.requests, 1, "the shelf only");
    let item = &items(&app, series).await[0];
    assert_eq!(
        (item.art_from.as_deref(), item.art_hash.as_deref()),
        (Some(query), Some(hash_1.as_str()))
    );
    assert_eq!(served_art(&app, &token, series).await.0, version);

    // Another path: not within a week of the result, then once; the new picture restamps the
    // listing and nudges the phone, and the old one is pruned.
    let moved = "http://c.example.invalid/1b.jpg";
    app.fetch
        .answer(BOOKSHELF, 200, shelf_covers(&[("160661", moved)]));
    app.fetch.answer(moved, 200, png(0b0101_1010, 300, 300));
    app.fetch.clear_calls();
    check_now(&app, series, t0 + TimeDelta::days(3)).await;
    assert_eq!(fetched(&app, moved), 0, "within a week of its result");
    assert_eq!(
        items(&app, series).await[0].art_hash.as_deref(),
        Some(hash_1.as_str())
    );
    let report = check_now(&app, series, t0 + TimeDelta::days(8)).await;
    assert_eq!(fetched(&app, moved), 1);
    assert_eq!((report.art_own, report.art_same), (1, 0));
    let hash_2 = items(&app, series).await[0].art_hash.clone().unwrap();
    assert_ne!(hash_2, hash_1);
    assert!(report.changed);
    assert_eq!(report.phones, [device]);
    let (restamped, art) = served_art(&app, &token, series).await;
    assert_ne!(restamped, version);
    assert_eq!(art[0], serde_json::json!(hash_2));
    assert_eq!(
        sweep::load_listing(&app.db, series)
            .await
            .unwrap()
            .unwrap()
            .version,
        restamped.as_str().map(str::to_string),
        "the stored version is the served one"
    );
    assert!(
        !app.state
            .music_art_dir
            .join(format!("{hash_1}.jpg"))
            .exists()
    );
    check_now(&app, series, t0 + TimeDelta::days(8) + TimeDelta::hours(1)).await;
    assert_eq!(fetched(&app, moved), 1, "once");
}

/// The art pass reads a series from book 1 (reading order; `auto` makes a feed newest first): with
/// one picture a check, book 1's cover comes first and book 3's in the next check, whose new hash
/// restamps the listing's version and nudges the phone. A book whose cover is the series' costs
/// nothing.
#[tokio::test]
async fn the_art_pass_reads_a_series_in_reading_order() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (device, token) = app.enrolled_device("phone").await;
    let series = storytel_library(&app, &cookie, device, &[SERIES]).await[0];
    tick(&app, &cookie, device, series, true).await;
    let cover_3 = "http://c.example.invalid/3.jpg";
    app.fetch
        .answer(BOOKSHELF, 200, shelf_covers(&[("160663", cover_3)]));
    app.fetch.answer(COVER_2, 200, jpeg(40));
    app.fetch.answer(COVER_1, 200, png(0b1010_0101, 300, 300));
    app.fetch.answer(cover_3, 200, png(0b0011_1100, 300, 300));
    app.state.music_sweep.set_art_per_check(1);
    let t0 = chrono::Utc::now();
    app.fetch.clear_calls();
    let report = check_now(&app, series, t0).await;
    assert_eq!(report.error, None);
    assert_eq!((fetched(&app, COVER_1), fetched(&app, cover_3)), (1, 0));
    let listed = items(&app, series).await;
    let hash_1 = listed[0].art_hash.clone().unwrap();
    assert_eq!(
        art_of(&listed),
        [
            (Some("own"), Some(hash_1.as_str())),
            (Some("same"), None),
            (None, None)
        ],
        "book 3 waits for the next check"
    );
    let (version, art) = served_art(&app, &token, series).await;
    assert_eq!(art[2], serde_json::Value::Null);

    let report = check(&app, series, t0 + TimeDelta::minutes(1)).await;
    assert_eq!((fetched(&app, COVER_1), fetched(&app, cover_3)), (1, 1));
    let hash_3 = items(&app, series).await[2].art_hash.clone().unwrap();
    assert_ne!(hash_3, hash_1);
    assert!(report.changed, "a new picture changes the listing");
    assert_eq!(report.phones, [device]);
    let (restamped, art) = served_art(&app, &token, series).await;
    assert_ne!(restamped, version);
    assert_eq!(art[2], serde_json::json!(hash_3));
    let library = device_get(&app, "/api/devices/music/library", &token, &[])
        .await
        .json();
    assert_eq!(lib_entry(&library, series).unwrap()["items"], restamped);
}

/// The cover route serves a book's cover only to a phone that gets the entry (design 26b §2.7's
/// gate): ticked with the Storytel switch on; not one without the tick, nor one with the switch
/// off, nor any phone once the login is cleared.
#[tokio::test]
async fn a_books_cover_is_served_only_behind_the_gate() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (device, token) = app.enrolled_device("phone").await;
    let (unticked, unticked_token) = app.enrolled_device("unticked").await;
    let (off, off_token) = app.enrolled_device("off").await;
    let series = storytel_library(&app, &cookie, device, &[SERIES]).await[0];
    tick(&app, &cookie, device, series, true).await;
    tick(&app, &cookie, off, series, true).await;
    sqlx::query("UPDATE device_policy SET music_storytel = 1 WHERE device_id = ?")
        .bind(unticked)
        .execute(&app.db)
        .await
        .unwrap();
    app.fetch.answer(COVER_2, 200, jpeg(40));
    app.fetch.answer(COVER_1, 200, png(0b1010_0101, 300, 300));
    check(&app, series, chrono::Utc::now()).await;
    let hash = items(&app, series).await[0].art_hash.clone().unwrap();
    let cover = |token: String| {
        let app = &app;
        let hash = hash.clone();
        async move {
            device_get(
                app,
                &format!("/api/devices/music/covers/{hash}"),
                &token,
                &[],
            )
            .await
            .status
        }
    };
    assert_eq!(cover(token.clone()).await, StatusCode::OK);
    assert_eq!(
        cover(unticked_token).await,
        StatusCode::NOT_FOUND,
        "not ticked"
    );
    assert_eq!(
        cover(off_token).await,
        StatusCode::NOT_FOUND,
        "the switch off"
    );
    // The login cleared: no phone gets Storytel.
    let res = app
        .request_form(Method::POST, "/music/storytel/clear", Some(&cookie), &[])
        .await;
    assert!(res.status.is_redirection(), "{}", res.text());
    assert_eq!(cover(token).await, StatusCode::NOT_FOUND, "no login");
}

/// A book's cover lost from the store (a restore: the store isn't backed up) is forgotten, the
/// Storytel listing's version is stored again from its rows (design 27 code QA L6 covers Storytel
/// too), and the next check fetches the cover again.
#[tokio::test]
async fn a_lost_books_cover_restamps_the_listing_and_is_fetched_again() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (device, token) = app.enrolled_device("phone").await;
    let series = storytel_library(&app, &cookie, device, &[SERIES]).await[0];
    tick(&app, &cookie, device, series, true).await;
    app.fetch.answer(COVER_2, 200, jpeg(40));
    app.fetch.answer(COVER_1, 200, png(0b1010_0101, 300, 300));
    let t0 = chrono::Utc::now();
    check(&app, series, t0).await;
    let hash = items(&app, series).await[0].art_hash.clone().unwrap();
    let (version, _) = served_art(&app, &token, series).await;

    std::fs::remove_file(app.state.music_art_dir.join(format!("{hash}.jpg"))).unwrap();
    let backups = tempfile::tempdir().unwrap();
    crate::photos::recover_missing(&app.state, backups.path()).await;
    let (restamped, art) = served_art(&app, &token, series).await;
    assert_ne!(restamped, version, "the version follows the rows");
    assert_eq!(art[0], serde_json::Value::Null);
    let stored = sweep::load_listing(&app.db, series).await.unwrap().unwrap();
    assert_eq!(stored.version, restamped.as_str().map(str::to_string));

    app.fetch.clear_calls();
    check(&app, series, t0 + TimeDelta::minutes(1)).await;
    assert_eq!(fetched(&app, COVER_1), 1);
    assert!(app.state.music_art_dir.join(format!("{hash}.jpg")).exists());
    assert_eq!(
        served_art(&app, &token, series).await,
        (
            version,
            vec![
                serde_json::json!(hash),
                serde_json::Value::Null,
                serde_json::Value::Null
            ]
        )
    );
}

/// The kept book is its old row (qa-26b-merge27-code M1): a cover that failed keeps its failure, so
/// a check the same day sends no image request and the try count stays (design 27: a day later,
/// at most three tries). Rebuilt from `Item::new` it would be untried again - and with no stored
/// print `known_art` has nothing to restore - so every check would spend a request of the shared
/// budget on it.
#[tokio::test]
async fn a_failed_books_cover_waits_a_day_like_a_feeds() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (device, _) = app.enrolled_device("phone").await;
    let series = storytel_library(&app, &cookie, device, &[SERIES]).await[0];
    tick(&app, &cookie, device, series, true).await;
    app.fetch.answer(COVER_2, 200, jpeg(40));
    app.fetch.answer(COVER_1, 404, "");
    let t0 = chrono::Utc::now();
    app.fetch.clear_calls();
    assert_eq!(
        check(&app, series, t0).await.error,
        None,
        "never fails the check"
    );
    assert_eq!(fetched(&app, COVER_1), 1);
    let item = &items(&app, series).await[0];
    assert_eq!(
        (
            item.art_state.as_deref(),
            item.art_fails,
            item.art_hash.as_ref()
        ),
        (Some("failed"), 1, None)
    );
    let first = items(&app, series).await;

    // The same day, a routine check and Check now: no image request, the try count stays.
    let routine = check(&app, series, t0 + TimeDelta::minutes(1)).await;
    assert_eq!(routine.requests, 0);
    check_now(&app, series, t0 + TimeDelta::minutes(2)).await;
    assert_eq!(fetched(&app, COVER_1), 1, "not within a day");
    assert_eq!(items(&app, series).await, first, "the row carried over");
    // A day later: tried again, the second try.
    check(&app, series, t0 + TimeDelta::hours(25)).await;
    assert_eq!(fetched(&app, COVER_1), 2);
    assert_eq!(items(&app, series).await[0].art_fails, 2);
}
