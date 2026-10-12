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
    // (migration 0066's triggers); the library's Storytel gate itself is step 2b's.
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (device, _) = app.enrolled_device("phone").await;
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

    let res = app
        .request_form(Method::POST, "/music/storytel/clear", Some(&cookie), &[])
        .await;
    assert_eq!(res.location(), Some("/music#storytel"));
    assert!(revision(&app).await > on);
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
