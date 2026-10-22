//! Spotify on the server, step 1 of design 26c (§2, §7.1, §9.3, §10's routes rows): the app
//! setting (its 400s, the environment winning, the redirect suggestion from `Host` and
//! `X-Forwarded-*`), Clear and a new client ID removing the accounts and unbinding phones, the
//! PKCE login (the start's 303 and cookie, the callback without a session - `state` and the
//! cookie required, single use, 10 minutes -, `error`, `/me`'s 403, the paste), a new login of the
//! same account, Remove, the 180-day line and its warning, and that the code, the tokens and the
//! verifier reach no log line, event or page. `spotify::tests` holds the client's own.

use std::collections::HashMap;
use std::sync::Arc;

use axum::body::Body;
use axum::http::{Method, Request, StatusCode, header};

use super::music::events;
use super::{TestApp, TestResponse};
use crate::music_sweep::parse_stamp;
use crate::spotify::tests::{CLIENT, ME_URL, TOKEN_URL, fixture, stored_refresh};
use crate::spotify::{self, LOOPBACK_REDIRECT};

const HTTPS_REDIRECT: &str = "https://pi.example.ts.net/music/spotify/callback";

async fn save_app(app: &TestApp, cookie: &str, fields: &[(&str, &str)]) -> TestResponse {
    app.request_form(Method::POST, "/music/spotify/app", Some(cookie), fields)
        .await
}

async fn set_up(app: &TestApp, cookie: &str, redirect: &str) {
    let res = save_app(
        app,
        cookie,
        &[("client_id", CLIENT), ("redirect_uri", redirect)],
    )
    .await;
    assert_eq!(
        res.status,
        StatusCode::SEE_OTHER,
        "{}",
        res.text().replace("&#39;", "'")
    );
}

/// Every `Set-Cookie` of a response.
fn set_cookies(res: &TestResponse) -> Vec<String> {
    res.headers
        .get_all(header::SET_COOKIE)
        .iter()
        .map(|v| v.to_str().unwrap().to_string())
        .collect()
}

/// A started login: the authorize URL's query and the `spotify_login` cookie's value.
struct Started {
    query: HashMap<String, String>,
    cookie: String,
    set_cookie: String,
}

async fn start(app: &TestApp, cookie: &str) -> Started {
    let res = app
        .request_form(Method::POST, "/music/spotify/login", Some(cookie), &[])
        .await;
    assert_eq!(
        res.status,
        StatusCode::SEE_OTHER,
        "{}",
        res.text().replace("&#39;", "'")
    );
    let location = res.location().unwrap().to_string();
    let url = reqwest::Url::parse(&location).unwrap();
    let set_cookie = set_cookies(&res)
        .into_iter()
        .find(|c| c.starts_with("spotify_login="))
        .expect("the login cookie");
    let value = set_cookie
        .trim_start_matches("spotify_login=")
        .split(';')
        .next()
        .unwrap()
        .to_string();
    Started {
        query: url.query_pairs().into_owned().collect(),
        cookie: value,
        set_cookie,
    }
}

/// `GET /music/spotify/callback?<query>` as Spotify's redirect arrives: no session, the login
/// cookie (if any).
async fn callback(app: &TestApp, query: &str, login_cookie: Option<&str>) -> TestResponse {
    let mut builder = Request::builder().uri(format!("/music/spotify/callback?{query}"));
    if let Some(value) = login_cookie {
        builder = builder.header(header::COOKIE, format!("spotify_login={value}"));
    }
    app.send_via(
        &app.router,
        super::LOOPBACK_PEER,
        builder.body(Body::empty()).unwrap(),
    )
    .await
}

fn code_query(state: &str, code: &str) -> String {
    form_urlencoded::Serializer::new(String::new())
        .append_pair("code", code)
        .append_pair("state", state)
        .finish()
}

/// Spotify answers the exchange with the fixture tokens and `/me` with `me`.
fn spotify_takes(app: &TestApp, me: &str) {
    app.fetch.answer(TOKEN_URL, 200, fixture("token_code.json"));
    app.fetch.answer(ME_URL, 200, fixture(me));
}

/// A whole login through the callback; the outcome code from the redirect.
async fn log_in(app: &TestApp, cookie: &str, me: &str, code: &str) -> String {
    spotify_takes(app, me);
    let started = start(app, cookie).await;
    let res = callback(
        app,
        &code_query(&started.query["state"], code),
        Some(&started.cookie),
    )
    .await;
    assert_eq!(res.status, StatusCode::SEE_OTHER);
    res.location()
        .unwrap()
        .trim_start_matches("/music/spotify/done?login=")
        .to_string()
}

type AccountRow = (i64, String, String, i64, i64, String);

async fn account_rows(app: &TestApp) -> Vec<AccountRow> {
    sqlx::query_as(
        "SELECT id, spotify_user, display_name, generation, needs_login, authorized_at \
         FROM music_spotify_accounts ORDER BY id",
    )
    .fetch_all(&app.db)
    .await
    .unwrap()
}

async fn revision(app: &TestApp) -> i64 {
    sqlx::query_scalar("SELECT revision FROM music_library_revision")
        .fetch_one(&app.db)
        .await
        .unwrap()
}

async fn bind(app: &TestApp, device: i64, account: i64) {
    sqlx::query("UPDATE device_policy SET music_spotify_account = ? WHERE device_id = ?")
        .bind(account)
        .bind(device)
        .execute(&app.db)
        .await
        .unwrap();
}

async fn binding(app: &TestApp, device: i64) -> Option<i64> {
    sqlx::query_scalar("SELECT music_spotify_account FROM device_policy WHERE device_id = ?")
        .bind(device)
        .fetch_one(&app.db)
        .await
        .unwrap()
}

/// Design 30's 0073, then Spotify's 0074 (qa-26c-merge3-code L3), then design 34's 0075 (the clock
/// format). `startup::pre_migration_backup` compares only `MAX(version)`: a 0073 reaching a server
/// that has run 0074 would be applied out of order with no backup first, so neither side may
/// renumber past the other. Design 34 step 6 relaxed it to "74 is Spotify's, just above design
/// 30's 73", as this test's note asked once 0074 was on `main`, and renamed it (it was
/// `the_migrations_end_with_design_30_then_spotify`; qa-34-s6-code N2). Design 34 step 9 added
/// its 0076 (the softer wallpapers) and renamed it again (was `migrations_73_74_75_in_order`), and
/// step 12 its 0077 (the launcher's catalog row named "Vibb One"; was `migrations_73_to_76_in_order`).
/// Design 36 added 0079 (the custom DNS server; was `migrations_73_to_77_in_order`): 0078 is
/// design 35's, and must reach a server before or with 0079, never after. Design 38 added 0080 (the
/// phone's power switches; was `migrations_73_to_79_in_order`). Server round 5 added 0082 (the
/// episode-picture backfill and `push_subscriptions.last_opened_at`; was `migrations_73_to_81_in_order`).
/// Launcher round 7 §3 added 0084 (the wake switch; was `migrations_73_to_83_in_order`); design 59 0085
/// and its step 2's fix round 0086 (was `migrations_73_to_85_in_order`).
#[test]
fn migrations_73_to_88_in_order() {
    let versions: Vec<(i64, String)> = crate::MIGRATOR
        .iter()
        .filter(|m| (73..=88).contains(&m.version))
        .map(|m| (m.version, m.description.to_string()))
        .collect();
    assert_eq!(
        versions,
        [
            (73, "music data and storage".to_string()),
            (74, "music spotify".to_string()),
            (75, "clock format".to_string()),
            (76, "softer wallpapers".to_string()),
            (77, "vibb one".to_string()),
            (78, "parent events".to_string()),
            (79, "custom dns".to_string()),
            (80, "phone power".to_string()),
            (81, "music art more".to_string()),
            (82, "art backfill and last opened".to_string()),
            (83, "lock glance text per app".to_string()),
            (84, "lock wake on notification".to_string()),
            (85, "kid keyguard".to_string()),
            (86, "reauth wrong".to_string()),
            (87, "parent accounts".to_string()),
            (88, "parent reads and prefs".to_string())
        ],
        "0073 (design 30), 0074 (Spotify), 0075 (the clock format), 0076 (the softer \
         wallpapers), 0077 (Vibb One), 0078 (design 35), 0079 (the custom DNS server), 0080 \
         (design 38), 0081 (episode pictures ASAP), 0082 (server round 5), 0083 (design 51), 0084 (launcher round 7 §3), 0085 (design 59), 0086 (design 59 step 2's fix round), 0087 and 0088 (design 62) stay in this order: the backup before \
         migrating compares only the highest version"
    );
}

#[tokio::test]
async fn routes_need_a_session() {
    let app = TestApp::new().await;
    for path in [
        "/music/spotify/app",
        "/music/spotify/app/clear",
        "/music/spotify/login",
        "/music/spotify/paste",
        "/music/spotify/accounts/1/remove",
        "/music/spotify/add",
        "/devices/1/music/spotify",
    ] {
        let res = app
            .request_form(Method::POST, path, None, &[("confirm", "on")])
            .await;
        assert_eq!(res.location(), Some("/login"), "{path}");
    }
    let res = app
        .send_via(
            &app.router,
            super::LOOPBACK_PEER,
            Request::builder()
                .uri("/music/spotify")
                .body(Body::empty())
                .unwrap(),
        )
        .await;
    assert_eq!(res.location(), Some("/login"));
    let count: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM music_spotify_app")
        .fetch_one(&app.db)
        .await
        .unwrap();
    assert_eq!(count, 0);
    // The callback and the done page need none (the callback without a state does nothing).
    let res = callback(&app, "code=FAKE-CODE-1", None).await;
    assert_eq!(res.location(), Some("/music/spotify/done?login=expired"));
    let res = app
        .send_via(
            &app.router,
            super::LOOPBACK_PEER,
            Request::builder()
                .uri("/music/spotify/done?login=ok")
                .body(Body::empty())
                .unwrap(),
        )
        .await;
    assert_eq!(res.status, StatusCode::OK);
    assert!(
        res.text()
            .replace("&#39;", "'")
            .contains("content=\"0;url=/music/settings?login=ok#spotify-accounts\""),
        "{}",
        res.text().replace("&#39;", "'")
    );
    // The head partial's stylesheet and name, like every page (qa-26c-merge3-code L4).
    let head = std::fs::read_to_string(concat!(
        env!("CARGO_MANIFEST_DIR"),
        "/templates/partials/head.html"
    ))
    .unwrap();
    let stylesheet = head
        .lines()
        .find(|line| line.contains("style.css?v="))
        .unwrap();
    assert!(res.text().contains(stylesheet), "{}", res.text());
    assert!(
        res.text()
            .contains("<title>Spotify Login · Vibb Phone</title>")
    );
    // Anything but a known outcome is "expired" (code QA L5): no reflection.
    let res = app
        .send_via(
            &app.router,
            super::LOOPBACK_PEER,
            Request::builder()
                .uri("/music/spotify/done?login=%22%3E%3Cscript%3E")
                .body(Body::empty())
                .unwrap(),
        )
        .await;
    let text = res.text();
    assert!(
        text.contains("content=\"0;url=/music/settings?login=expired#spotify-accounts\""),
        "{text}"
    );
    assert!(!text.contains("<script>") && !text.contains("%3Cscript"));
}

/// "Not configured" changes nothing else: the music page links to the Spotify page, the Spotify
/// page explains how to register an app, and a phone's library is the same.
#[tokio::test]
async fn not_configured_shows_the_steps() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let (phone, _) = app.create_device("Ella").await;
    let own = app
        .request_form(
            Method::POST,
            "/music/own",
            Some(&cookie),
            &[("name", "Sanger")],
        )
        .await;
    let entry: i64 = own
        .location()
        .unwrap()
        .trim_start_matches("/music/entries/")
        .trim_end_matches("#files")
        .parse()
        .unwrap();
    super::music::tick(&app, &cookie, phone, entry, true).await;
    let library = || async {
        let mut conn = app.db.acquire().await.unwrap();
        let library = crate::music::library_for(&mut conn, phone, None)
            .await
            .unwrap()
            .unwrap();
        (library.version, library.json)
    };
    let before = library().await;
    let music = app
        .get_page("/music", &cookie)
        .await
        .text()
        .replace("&#39;", "'");
    assert!(music.contains("href=\"/music/spotify\""), "{music}");
    let res = app.get_page("/music/settings", &cookie).await;
    assert_eq!(res.status, StatusCode::OK);
    let page = res.text().replace("&#39;", "'");
    assert!(page.contains("No Spotify app is set up on this server yet"));
    assert!(page.contains("developer.spotify.com/dashboard"));
    assert!(page.contains("At most 5 accounts can use a Development-mode app"));
    assert!(page.contains("<details id=\"spotify-setup\" open"));
    // The account model (design 26c step 8): one account per phone, the child's own; the
    // parent's is never logged in - the parent pastes links; a playlist lists only for a
    // child's account that owns it or collaborates on it.
    assert!(page.contains(
        "your children's Spotify accounts: one account per phone, the child's own. Your own \
         Spotify account is never logged in here - you paste the links on Add from Spotify."
    ));
    assert!(page.contains(
        "add each child's Spotify account - one per phone, the child's own, never yours - by \
         name and e-mail"
    ));
    assert!(page.contains(
        "a phone shows a playlist's tracks only when a child's account here owns it or \
         collaborates on it - make it in the Spotify app while logged in as the child, or \
         invite the child to collaborate on yours (in Spotify, the playlist's ⋯ › Invite \
         collaborators; then open the invite link while logged in as the child - the private \
         window you logged the child in from - and join)."
    ));
    assert!(!page.contains("yours (if it reads your playlists)"));
    assert!(
        !page.contains("Log in with Spotify"),
        "no login without an app"
    );
    // Plain http: the loopback redirect is suggested.
    assert!(page.contains(&format!("value=\"{LOOPBACK_REDIRECT}\"")));
    // The page's CSP lets its login form's 303 go to Spotify's accounts service (and nowhere
    // else); every other page keeps ADMIN_CSP.
    let csp = res.headers[header::CONTENT_SECURITY_POLICY]
        .to_str()
        .unwrap();
    assert!(
        csp.contains("form-action 'self' https://accounts.spotify.com;"),
        "{csp}"
    );
    let music_csp = app.get_page("/music", &cookie).await.headers[header::CONTENT_SECURITY_POLICY]
        .to_str()
        .unwrap()
        .to_string();
    assert_eq!(music_csp, crate::admin_auth::ADMIN_CSP);
    assert_eq!(before, library().await);
}

#[tokio::test]
async fn the_app_setting() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    // 400s by their field, nothing written.
    for (fields, field, text) in [
        (
            vec![("client_id", "short"), ("redirect_uri", LOOPBACK_REDIRECT)],
            "client_id",
            "A Client ID is 32 letters and digits",
        ),
        (
            vec![
                ("client_id", CLIENT),
                ("redirect_uri", "http://pi.lan:8080/music/spotify/callback"),
            ],
            "redirect_uri",
            "The Redirect URI is https://",
        ),
        (
            vec![("client_id", CLIENT), ("redirect_uri", "")],
            "redirect_uri",
            "The Redirect URI is https://",
        ),
    ] {
        let res = save_app(&app, &cookie, &fields).await;
        assert_eq!(res.status, StatusCode::BAD_REQUEST, "{fields:?}");
        let page = res.text().replace("&#39;", "'");
        assert!(page.contains(text), "{page}");
        // The re-rendered Music › Settings: the values, the prefixed field and error ids (design
        // 47 §1.2).
        let id = match field {
            "client_id" => "spotify-client-id",
            _ => "spotify-redirect-uri-field",
        };
        let input = &page[page.find(&format!("id=\"{id}\"")).expect(id)..];
        assert!(
            input[..input.find('>').unwrap()]
                .contains("aria-describedby=\"spotify-app-error\" autofocus"),
            "{field}"
        );
        assert!(page.contains("<p class=\"field-error\" id=\"spotify-app-error\">"));
        assert!(page.contains("<h1 class=\"page-title\">Settings</h1>"));
        // The CSP of the re-rendered page is the Spotify page's.
        assert!(
            res.headers[header::CONTENT_SECURITY_POLICY]
                .to_str()
                .unwrap()
                .contains("accounts.spotify.com")
        );
    }
    assert!(spotify::app(&app.state).await.unwrap().is_none());

    // Saved: back to #app, the last four and the redirect URI large; an event.
    let res = save_app(
        &app,
        &cookie,
        &[("client_id", CLIENT), ("redirect_uri", HTTPS_REDIRECT)],
    )
    .await;
    assert_eq!(res.location(), Some("/music/settings?saved=0#spotify-app"));
    let page = app
        .get_page("/music/settings?saved=0", &cookie)
        .await
        .text()
        .replace("&#39;", "'");
    // Design 31's rows (qa-26c-merge2-code M3): the Client ID's last four as the row's value, the
    // exact Redirect URI as a copyable value, the save's flash in its group.
    assert!(
        page.contains("<span class=\"row-label\">Client ID</span>")
            && page.contains("<span class=\"row-value\">ending in cdef</span>"),
        "{page}"
    );
    assert!(page.contains(&format!(
        "<code class=\"key\" id=\"spotify-redirect-uri\" style=\"user-select: all;\">{HTTPS_REDIRECT}</code>"
    )));
    assert!(page.contains("<p class=\"flash\" role=\"status\" data-flash=\"app\">Saved.</p>"));
    assert!(page.contains("Log in with Spotify"));
    assert!(page.contains(
        "Log in each child's own account - one per phone, never yours. Whoever is logged into \
         Spotify in this browser logs in, so open this page in a private window"
    ));
    assert!(!page.contains("<details id=\"spotify-setup\" open"));
    assert_eq!(
        events(&app, "spotify_app_saved").await,
        [format!(
            "Spotify app: client ID ending in cdef, redirect {HTTPS_REDIRECT}"
        )]
    );
    let saved = spotify::app(&app.state).await.unwrap().unwrap();
    assert_eq!(saved.client_id, CLIENT);
    assert_eq!(saved.redirect_uri.as_deref(), Some(HTTPS_REDIRECT));
    assert!(!saved.client_from_env && saved.saved);
}

/// §2.1: the environment's client ID (and redirect URI) win; their fields are read-only.
#[tokio::test]
async fn the_environment_wins() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let mut state = app.state.clone();
    state.spotify = Arc::new(spotify::Accounts::new(spotify::Config::from_vars(
        &[(
            "SPOTIFY_CLIENT_ID".to_string(),
            "fedcba9876543210fedcba9876543210".to_string(),
        )]
        .into(),
    )));
    let router = app.admin_router(state.clone());
    let get = |router: axum::Router| {
        let cookie = cookie.clone();
        let app = &app;
        async move {
            app.send_via(
                &router,
                super::LOOPBACK_PEER,
                Request::builder()
                    .uri("/music/settings")
                    .header(header::COOKIE, &cookie)
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .text()
            .replace("&#39;", "'")
        }
    };
    let page = get(router.clone()).await;
    assert!(page.contains(
        "Client ID<span class=\"row-sub\">Set by the server's configuration</span></span>"
    ));
    assert!(page.contains("<span class=\"row-value\">ending in 3210</span>"));
    assert!(page.contains("value=\"fedcba9876543210fedcba9876543210\""));
    assert!(page.contains(" readonly"));
    assert!(page.contains("No Redirect URI is saved yet"));
    // A save takes the environment's client ID whatever the field says.
    let body = form_urlencoded::Serializer::new(String::new())
        .extend_pairs([("client_id", CLIENT), ("redirect_uri", LOOPBACK_REDIRECT)])
        .finish();
    let res = app
        .send_via(
            &router,
            super::LOOPBACK_PEER,
            Request::builder()
                .method(Method::POST)
                .uri("/music/spotify/app")
                .header(header::COOKIE, &cookie)
                .header(header::CONTENT_TYPE, "application/x-www-form-urlencoded")
                .body(Body::from(body))
                .unwrap(),
        )
        .await;
    assert_eq!(
        res.status,
        StatusCode::SEE_OTHER,
        "{}",
        res.text().replace("&#39;", "'")
    );
    let effective = spotify::app(&state).await.unwrap().unwrap();
    assert_eq!(effective.client_id, "fedcba9876543210fedcba9876543210");
    assert!(effective.client_from_env);
    assert_eq!(effective.redirect_uri.as_deref(), Some(LOOPBACK_REDIRECT));
    // Without the environment, the row is what was saved (the environment's value).
    assert_eq!(
        spotify::app(&app.state).await.unwrap().unwrap().client_id,
        "fedcba9876543210fedcba9876543210"
    );
}

/// §2.1 (QA L5): the suggestion is https only when a trusted proxy said the request was https.
#[tokio::test]
async fn the_redirect_suggestion_follows_the_request() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let page = |headers: Vec<(&'static str, &'static str)>| {
        let cookie = cookie.clone();
        let app = &app;
        async move {
            let mut builder = Request::builder()
                .uri("/music/settings")
                .header(header::COOKIE, &cookie);
            for (name, value) in headers {
                builder = builder.header(name, value);
            }
            app.send_via(
                &app.router,
                super::LOOPBACK_PEER,
                builder.body(Body::empty()).unwrap(),
            )
            .await
            .text()
            .replace("&#39;", "'")
        }
    };
    // From the trusted loopback proxy (tailscale serve) with https.
    let https = page(vec![
        ("host", "127.0.0.1:3100"),
        ("x-forwarded-host", "pi.example.ts.net"),
        ("x-forwarded-proto", "https"),
    ])
    .await;
    assert!(
        https.contains(&format!("value=\"{HTTPS_REDIRECT}\"")),
        "{https}"
    );
    // Plain http by name (Docker on the LAN): the loopback form.
    let http = page(vec![("host", "pi.lan:8080")]).await;
    assert!(http.contains(&format!("value=\"{LOOPBACK_REDIRECT}\"")));
    assert!(http.contains("This page didn't come over https"));
}

#[tokio::test]
async fn the_login_starts_with_pkce() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    // No app: refused by the button.
    let res = app
        .request_form(Method::POST, "/music/spotify/login", Some(&cookie), &[])
        .await;
    assert_eq!(res.status, StatusCode::BAD_REQUEST);
    assert!(
        res.text()
            .replace("&#39;", "'")
            .contains("Set up your Spotify app first")
    );
    set_up(&app, &cookie, HTTPS_REDIRECT).await;
    let started = start(&app, &cookie).await;
    let q = &started.query;
    assert_eq!(q["response_type"], "code");
    assert_eq!(q["client_id"], CLIENT);
    assert_eq!(q["scope"], spotify::SCOPES);
    assert_eq!(q["redirect_uri"], HTTPS_REDIRECT);
    assert_eq!(q["code_challenge_method"], "S256");
    assert_eq!(q["code_challenge"].len(), 43);
    assert_eq!(q["state"].len(), 32);
    assert_eq!(q["show_dialog"], "true");
    assert_eq!(started.cookie, spotify::state_cookie(&q["state"]));
    // HttpOnly, Lax, the callback's path, 10 minutes, Secure (no INSECURE_COOKIES here).
    for part in [
        "Path=/music/spotify/callback",
        "Max-Age=600",
        "HttpOnly",
        "SameSite=Lax",
        "Secure",
    ] {
        assert!(started.set_cookie.contains(part), "{}", started.set_cookie);
    }
    // At most 8 unfinished; a ninth drops the oldest.
    let first = started.query["state"].clone();
    for _ in 0..8 {
        start(&app, &cookie).await;
    }
    assert_eq!(app.state.spotify.pending_count(), spotify::PENDING_MAX);
    assert!(app.state.spotify.take_pending(&first, None).is_none());
    // Without the music key: 503, nothing started.
    let mut keyless = app.state.clone();
    keyless.music_key = None;
    let router = app.admin_router(keyless);
    let res = app
        .send_via(
            &router,
            super::LOOPBACK_PEER,
            Request::builder()
                .method(Method::POST)
                .uri("/music/spotify/login")
                .header(header::COOKIE, &cookie)
                .body(Body::empty())
                .unwrap(),
        )
        .await;
    assert_eq!(res.status, StatusCode::SERVICE_UNAVAILABLE);
}

#[tokio::test]
async fn the_callback_logs_an_account_in() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    set_up(&app, &cookie, HTTPS_REDIRECT).await;
    spotify_takes(&app, "me_kid.json");
    let started = start(&app, &cookie).await;
    let state = started.query["state"].clone();
    let res = callback(
        &app,
        &code_query(&state, "FAKE-CODE-1"),
        Some(&started.cookie),
    )
    .await;
    // A 303 (never a page on the code's URL), the cookie cleared.
    assert_eq!(res.status, StatusCode::SEE_OTHER);
    assert_eq!(res.location(), Some("/music/spotify/done?login=ok"));
    let cleared = set_cookies(&res)
        .into_iter()
        .find(|c| c.starts_with("spotify_login="))
        .unwrap();
    assert!(cleared.starts_with("spotify_login=;") && cleared.contains("Max-Age=0"));
    // The exchange: authorization_code with the verifier whose S256 the authorize URL carried.
    let requests = app.fetch.requests.lock().unwrap().clone();
    assert_eq!(requests[0].url, TOKEN_URL);
    let form: HashMap<String, String> =
        form_urlencoded::parse(requests[0].body.as_deref().unwrap())
            .into_owned()
            .collect();
    assert_eq!(form["grant_type"], "authorization_code");
    assert_eq!(form["code"], "FAKE-CODE-1");
    assert_eq!(form["redirect_uri"], HTTPS_REDIRECT);
    assert_eq!(form["client_id"], CLIENT);
    assert_eq!(
        spotify::s256(&form["code_verifier"]),
        started.query["code_challenge"]
    );
    assert_eq!(requests[1].url, ME_URL);
    assert_eq!(
        requests[1].header("authorization"),
        Some("Bearer FAKE-ACCESS-1")
    );
    // The row: the /me id and name, generation 1, the refresh token sealed.
    let rows = account_rows(&app).await;
    assert_eq!(rows.len(), 1);
    let (id, user, name, generation, needs_login, _) = &rows[0];
    assert_eq!(
        (user.as_str(), name.as_str(), *generation, *needs_login),
        ("fakekid", "Ella", 1, 0)
    );
    assert_eq!(
        stored_refresh(&app, *id).await.as_deref(),
        Some("FAKE-REFRESH-1")
    );
    let scopes: String = sqlx::query_scalar("SELECT scopes FROM music_spotify_accounts")
        .fetch_one(&app.db)
        .await
        .unwrap();
    assert_eq!(scopes, spotify::SCOPES);
    assert_eq!(
        events(&app, "spotify_account_added").await,
        ["Spotify account Ella logged in"]
    );
    // The exchange's access token is kept: no refresh to use it.
    app.fetch.clear_calls();
    let minted = app
        .state
        .spotify
        .access_token(&app.state, *id, spotify::READ_MIN_LEFT)
        .await
        .unwrap();
    assert_eq!(minted.token.secret(), "FAKE-ACCESS-1");
    assert_eq!(app.fetch.count(), 0);
    // The page then says who logged in, with the 6-month line.
    let page = app
        .get_page("/music/settings?login=ok", &cookie)
        .await
        .text()
        .replace("&#39;", "'");
    assert!(page.contains("Logged in as Ella."), "{page}");
    assert!(page.contains("log in again before"));

    // A replay of the same callback finds nothing.
    let res = callback(
        &app,
        &code_query(&state, "FAKE-CODE-1"),
        Some(&started.cookie),
    )
    .await;
    assert_eq!(res.location(), Some("/music/spotify/done?login=expired"));
    assert_eq!(account_rows(&app).await.len(), 1);
}

/// The callback needs a live state and its cookie (§2.3 L6): no cookie, another browser's, an
/// unknown state, one older than 10 minutes - nothing is asked of Spotify.
#[tokio::test]
async fn the_callback_needs_its_state_and_cookie() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    set_up(&app, &cookie, HTTPS_REDIRECT).await;
    spotify_takes(&app, "me_kid.json");
    let started = start(&app, &cookie).await;
    let state = started.query["state"].clone();
    for (query, login_cookie) in [
        (code_query(&state, "FAKE-CODE-1"), None),
        (
            code_query(&state, "FAKE-CODE-1"),
            Some(spotify::state_cookie("another")),
        ),
        (
            code_query("0123456789abcdef0123456789abcdef", "FAKE-CODE-1"),
            Some(spotify::state_cookie("0123456789abcdef0123456789abcdef")),
        ),
        ("code=FAKE-CODE-1".to_string(), Some(started.cookie.clone())),
    ] {
        let res = callback(&app, &query, login_cookie.as_deref()).await;
        assert_eq!(
            res.location(),
            Some("/music/spotify/done?login=expired"),
            "{query}"
        );
    }
    assert_eq!(app.fetch.count(), 0);
    // A missing or wrong cookie didn't use the login up: the right browser still completes it
    // (code QA L5)...
    let res = callback(
        &app,
        &code_query(&state, "FAKE-CODE-1"),
        Some(&started.cookie),
    )
    .await;
    assert_eq!(res.location(), Some("/music/spotify/done?login=ok"));
    assert_eq!(account_rows(&app).await.len(), 1);
    // ...but not after 10 minutes (another login).
    let second = start(&app, &cookie).await;
    app.fetch.clear_calls();
    app.state.spotify.advance(spotify::PENDING_TTL);
    let res = callback(
        &app,
        &code_query(&second.query["state"], "FAKE-CODE-2"),
        Some(&second.cookie),
    )
    .await;
    assert_eq!(res.location(), Some("/music/spotify/done?login=expired"));
    assert_eq!(app.fetch.count(), 0);
    assert_eq!(account_rows(&app).await.len(), 1);
    let page = app
        .get_page("/music/settings?login=expired", &cookie)
        .await
        .text()
        .replace("&#39;", "'");
    assert!(page.contains("took longer than 10 minutes"));
    assert!(page.contains("was started at another address of this server"));
}

#[tokio::test]
async fn refusals_store_nothing() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    set_up(&app, &cookie, HTTPS_REDIRECT).await;
    // The parent said no.
    let started = start(&app, &cookie).await;
    let query = format!("error=access_denied&state={}", started.query["state"]);
    let res = callback(&app, &query, Some(&started.cookie)).await;
    assert_eq!(res.location(), Some("/music/spotify/done?login=cancelled"));
    assert_eq!(app.fetch.count(), 0);
    // /me 403: not under User Management.
    app.fetch.answer(TOKEN_URL, 200, fixture("token_code.json"));
    app.fetch
        .answer(ME_URL, 403, fixture("me_not_registered.json"));
    let started = start(&app, &cookie).await;
    let res = callback(
        &app,
        &code_query(&started.query["state"], "FAKE-CODE-2"),
        Some(&started.cookie),
    )
    .await;
    assert_eq!(
        res.location(),
        Some("/music/spotify/done?login=not_registered")
    );
    let page = app
        .get_page("/music/settings?login=not_registered", &cookie)
        .await
        .text()
        .replace("&#39;", "'");
    assert!(page.contains("add it under User Management in the dashboard"));
    // A stale code, a wrong client ID, an outage, an answer without a refresh token.
    for (status, body, expected) in [
        (400, fixture("token_invalid_grant.json"), "refused"),
        (400, fixture("token_invalid_client.json"), "refused"),
        (503, Vec::new(), "unreachable"),
        (
            200,
            br#"{"access_token":"FAKE-ACCESS-1","expires_in":3600}"#.to_vec(),
            "refused",
        ),
    ] {
        app.fetch.answer(TOKEN_URL, status, body);
        let started = start(&app, &cookie).await;
        let res = callback(
            &app,
            &code_query(&started.query["state"], "FAKE-CODE-3"),
            Some(&started.cookie),
        )
        .await;
        assert_eq!(
            res.location(),
            Some(format!("/music/spotify/done?login={expected}").as_str()),
            "{status}"
        );
    }
    app.fetch.fail(
        TOKEN_URL,
        crate::music_net::FetchError::Network("reset".to_string()),
    );
    let started = start(&app, &cookie).await;
    let res = callback(
        &app,
        &code_query(&started.query["state"], "FAKE-CODE-4"),
        Some(&started.cookie),
    )
    .await;
    assert_eq!(
        res.location(),
        Some("/music/spotify/done?login=unreachable")
    );
    assert!(account_rows(&app).await.is_empty());
    assert_eq!(
        events(&app, "spotify_login_failed").await,
        [
            "not_registered",
            "invalid_grant",
            "invalid_client",
            "http_503",
            "no_refresh_token",
            "network"
        ]
    );
    // Another app since the login started: its code belongs to the old one.
    let started = start(&app, &cookie).await;
    set_up(&app, &cookie, LOOPBACK_REDIRECT).await;
    spotify_takes(&app, "me_kid.json");
    let res = callback(
        &app,
        &code_query(&started.query["state"], "FAKE-CODE-5"),
        Some(&started.cookie),
    )
    .await;
    assert_eq!(res.location(), Some("/music/spotify/done?login=expired"));
    assert!(account_rows(&app).await.is_empty());
}

/// §2.2: a second login of the same account keeps its row and its phones, replaces the token and
/// bumps the generation; another account is a second row.
#[tokio::test]
async fn a_new_login_of_the_same_account() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    set_up(&app, &cookie, HTTPS_REDIRECT).await;
    assert_eq!(
        log_in(&app, &cookie, "me_kid.json", "FAKE-CODE-1").await,
        "ok"
    );
    let id = account_rows(&app).await[0].0;
    let (phone, _) = app.create_device("Ella").await;
    bind(&app, phone, id).await;
    // The login ended meanwhile.
    sqlx::query(
        "UPDATE music_spotify_accounts SET needs_login = 1, refresh_error = 'invalid_grant', \
         authorized_at = '2026-01-01 10:00:00'",
    )
    .execute(&app.db)
    .await
    .unwrap();
    app.fetch.answer(
        TOKEN_URL,
        200,
        String::from_utf8(fixture("token_code.json"))
            .unwrap()
            .replace("FAKE-REFRESH-1", "FAKE-REFRESH-5"),
    );
    app.fetch.answer(ME_URL, 200, fixture("me_kid.json"));
    let started = start(&app, &cookie).await;
    let res = callback(
        &app,
        &code_query(&started.query["state"], "FAKE-CODE-2"),
        Some(&started.cookie),
    )
    .await;
    assert_eq!(res.location(), Some("/music/spotify/done?login=ok"));
    let rows = account_rows(&app).await;
    assert_eq!(rows.len(), 1);
    let (same_id, _, _, generation, needs_login, authorized_at) = &rows[0];
    assert_eq!((*same_id, *generation, *needs_login), (id, 2, 0));
    assert_ne!(
        authorized_at, "2026-01-01 10:00:00",
        "the 6-month clock restarts"
    );
    let error: Option<String> =
        sqlx::query_scalar("SELECT refresh_error FROM music_spotify_accounts")
            .fetch_one(&app.db)
            .await
            .unwrap();
    assert_eq!(error, None);
    assert_eq!(
        binding(&app, phone).await,
        Some(id),
        "the phone stays bound"
    );
    assert_eq!(
        stored_refresh(&app, id).await.as_deref(),
        Some("FAKE-REFRESH-5")
    );
    assert_eq!(
        events(&app, "spotify_account_added").await,
        [
            "Spotify account Ella logged in",
            "Spotify account Ella logged in again"
        ]
    );
    // The parent's account: a second row.
    assert_eq!(
        log_in(&app, &cookie, "me_parent.json", "FAKE-CODE-3").await,
        "ok"
    );
    let rows = account_rows(&app).await;
    assert_eq!(
        rows.iter().map(|r| r.2.as_str()).collect::<Vec<_>>(),
        ["Ella", "Forelder"]
    );
}

/// §2.3: the loopback redirect's paste runs the callback's code path, for the admin who started
/// the login; a pasted address is never shown back.
#[tokio::test]
async fn the_paste_finishes_a_loopback_login() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    set_up(&app, &cookie, LOOPBACK_REDIRECT).await;
    let page = app
        .get_page("/music/settings", &cookie)
        .await
        .text()
        .replace("&#39;", "'");
    assert!(page.contains("Paste the address your browser shows after logging in"));
    spotify_takes(&app, "me_parent.json");
    let started = start(&app, &cookie).await;
    assert_eq!(started.query["redirect_uri"], LOOPBACK_REDIRECT);
    let paste = |url: String| {
        let cookie = cookie.clone();
        let app = &app;
        async move {
            app.request_form(
                Method::POST,
                "/music/spotify/paste",
                Some(&cookie),
                &[("url", &url)],
            )
            .await
        }
    };
    // Not the loopback callback, or no code: a 400 by the field, the text not shown back.
    for bad in [
        format!(
            "https://pi.example.ts.net/music/spotify/callback?code=FAKE-CODE-1&state={}",
            started.query["state"]
        ),
        format!(
            "http://127.0.0.1:43118/callback?code=FAKE-CODE-1&state={}",
            started.query["state"]
        ),
        "http://127.0.0.1:43117/callback?code=FAKE-CODE-1".to_string(),
        "not an address".to_string(),
    ] {
        let res = paste(bad.clone()).await;
        assert_eq!(res.status, StatusCode::BAD_REQUEST, "{bad}");
        let text = res.text().replace("&#39;", "'");
        assert!(text.contains("Paste the whole address"));
        assert!(!text.contains("FAKE-CODE-1"), "{bad}");
    }
    assert_eq!(app.fetch.count(), 0);
    let res = paste(format!(
        "  {LOOPBACK_REDIRECT}?code=FAKE-CODE-1&state={}  ",
        started.query["state"]
    ))
    .await;
    assert_eq!(
        res.location(),
        Some("/music/settings?login=ok#spotify-accounts")
    );
    let rows = account_rows(&app).await;
    assert_eq!(rows[0].1, "fakeparent");
    // The exchange used the loopback redirect URI.
    let form: HashMap<String, String> = form_urlencoded::parse(
        app.fetch.requests.lock().unwrap()[0]
            .body
            .as_deref()
            .unwrap(),
    )
    .into_owned()
    .collect();
    assert_eq!(form["redirect_uri"], LOOPBACK_REDIRECT);
    // Used: a second paste finds nothing.
    let res = paste(format!(
        "{LOOPBACK_REDIRECT}?code=FAKE-CODE-1&state={}",
        started.query["state"]
    ))
    .await;
    assert_eq!(
        res.location(),
        Some("/music/settings?login=expired#spotify-accounts")
    );
    // A login another admin started can't be finished by this one.
    let other = app
        .state
        .spotify
        .start_login("someone-else", CLIENT, LOOPBACK_REDIRECT);
    let state = reqwest::Url::parse(&other.authorize_url)
        .unwrap()
        .query_pairs()
        .find(|(k, _)| k == "state")
        .unwrap()
        .1
        .into_owned();
    let res = paste(format!(
        "{LOOPBACK_REDIRECT}?code=FAKE-CODE-2&state={state}"
    ))
    .await;
    assert_eq!(
        res.location(),
        Some("/music/settings?login=expired#spotify-accounts")
    );
    // ...and doesn't use it up (code QA L1): its own browser still finishes it.
    assert_eq!(app.state.spotify.pending_count(), 1);
    let res = callback(
        &app,
        &code_query(&state, "FAKE-CODE-2"),
        Some(&other.cookie),
    )
    .await;
    assert_eq!(res.location(), Some("/music/spotify/done?login=ok"));
    // The parent cancelled.
    let started = start(&app, &cookie).await;
    let res = paste(format!(
        "{LOOPBACK_REDIRECT}?error=access_denied&state={}",
        started.query["state"]
    ))
    .await;
    assert_eq!(
        res.location(),
        Some("/music/settings?login=cancelled#spotify-accounts")
    );
    assert_eq!(account_rows(&app).await.len(), 1);
}

/// §2.1/§2.2: Remove one account, Clear everything, and a new client ID removing the accounts -
/// each unbinding its phones first (the library revision moves) and forgetting the tokens.
#[tokio::test]
async fn remove_clear_and_a_new_client_id() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    set_up(&app, &cookie, HTTPS_REDIRECT).await;
    log_in(&app, &cookie, "me_kid.json", "FAKE-CODE-1").await;
    log_in(&app, &cookie, "me_parent.json", "FAKE-CODE-2").await;
    let rows = account_rows(&app).await;
    let (kid, parent) = (rows[0].0, rows[1].0);
    let (ella, _) = app.create_device("Ella").await;
    let (ola, _) = app.create_device("Ola").await;
    let before = revision(&app).await;
    bind(&app, ella, kid).await;
    bind(&app, ola, parent).await;
    assert_eq!(
        revision(&app).await,
        before + 2,
        "binding moves the revision"
    );

    // Remove: the box is needed; an unknown account is a 404.
    let remove = |id: i64, confirm: bool| {
        let cookie = cookie.clone();
        let app = &app;
        async move {
            let fields: &[(&str, &str)] = if confirm { &[("confirm", "on")] } else { &[] };
            app.request_form(
                Method::POST,
                &format!("/music/spotify/accounts/{id}/remove"),
                Some(&cookie),
                fields,
            )
            .await
        }
    };
    let res = remove(kid, false).await;
    assert_eq!(res.status, StatusCode::BAD_REQUEST);
    assert!(
        res.text()
            .replace("&#39;", "'")
            .contains("Tick the box to remove this account's login.")
    );
    assert_eq!(remove(999, true).await.status, StatusCode::NOT_FOUND);
    let res = remove(kid, true).await;
    assert_eq!(
        res.location(),
        Some("/music/settings?removed=1#spotify-accounts")
    );
    assert_eq!(binding(&app, ella).await, None);
    assert_eq!(binding(&app, ola).await, Some(parent));
    assert_eq!(revision(&app).await, before + 3);
    assert_eq!(account_rows(&app).await.len(), 1);
    assert_eq!(
        events(&app, "spotify_account_removed").await,
        ["Spotify account Ella removed"]
    );
    assert!(matches!(
        app.state
            .spotify
            .access_token(&app.state, kid, spotify::READ_MIN_LEFT)
            .await,
        Err(spotify::SpotifyError::NoAccount)
    ));

    // A new client ID with accounts present: only with the box ticked; then the accounts go.
    const OTHER: &str = "fedcba9876543210fedcba9876543210";
    let res = save_app(
        &app,
        &cookie,
        &[("client_id", OTHER), ("redirect_uri", HTTPS_REDIRECT)],
    )
    .await;
    assert_eq!(res.status, StatusCode::BAD_REQUEST);
    assert!(
        res.text()
            .replace("&#39;", "'")
            .contains("Tick the box to save another Client ID: it removes the 1 logged-in")
    );
    assert_eq!(account_rows(&app).await.len(), 1);
    // The same client ID with another redirect keeps them.
    set_up(&app, &cookie, LOOPBACK_REDIRECT).await;
    assert_eq!(account_rows(&app).await.len(), 1);
    let res = save_app(
        &app,
        &cookie,
        &[
            ("client_id", OTHER),
            ("redirect_uri", HTTPS_REDIRECT),
            ("replace_accounts", "on"),
        ],
    )
    .await;
    assert_eq!(res.location(), Some("/music/settings?saved=1#spotify-app"));
    assert!(account_rows(&app).await.is_empty());
    assert_eq!(binding(&app, ola).await, None);
    assert_eq!(revision(&app).await, before + 4);
    let page = app
        .get_page("/music/settings?saved=1", &cookie)
        .await
        .text()
        .replace("&#39;", "'");
    assert!(page.contains("the account logged in with the old app was removed"));

    // Clear: the box is needed; then the app row and every account go.
    log_in(&app, &cookie, "me_kid.json", "FAKE-CODE-3").await;
    let kid = account_rows(&app).await[0].0;
    bind(&app, ella, kid).await;
    let res = app
        .request_form(Method::POST, "/music/spotify/app/clear", Some(&cookie), &[])
        .await;
    assert_eq!(res.status, StatusCode::BAD_REQUEST);
    assert!(
        res.text()
            .replace("&#39;", "'")
            .contains("Tick the box to remove the Spotify app")
    );
    let res = app
        .request_form(
            Method::POST,
            "/music/spotify/app/clear",
            Some(&cookie),
            &[("confirm", "on")],
        )
        .await;
    assert_eq!(
        res.location(),
        Some("/music/settings?cleared=1#spotify-app")
    );
    assert!(spotify::app(&app.state).await.unwrap().is_none());
    assert!(account_rows(&app).await.is_empty());
    assert_eq!(binding(&app, ella).await, None);
    assert_eq!(
        events(&app, "spotify_app_cleared").await,
        ["Spotify app and 1 accounts removed"]
    );
    let page = app
        .get_page("/music/settings?cleared=1", &cookie)
        .await
        .text()
        .replace("&#39;", "'");
    assert!(page.contains("No Spotify app is set up on this server yet"));
}

/// §2.2: "log in again before" 180 days on, a warning from 14 days before, and the ended login.
#[tokio::test]
async fn the_six_month_line() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    set_up(&app, &cookie, HTTPS_REDIRECT).await;
    let t0 = parse_stamp("2026-10-12 10:00:00").unwrap();
    app.state.music_sweep.set_clock(Some(t0));
    log_in(&app, &cookie, "me_kid.json", "FAKE-CODE-1").await;
    let page = app
        .get_page("/music/settings", &cookie)
        .await
        .text()
        .replace("&#39;", "'");
    assert!(
        page.contains("Logged in 12 Oct 2026 - log in again before 10 Apr 2027"),
        "{page}"
    );
    assert!(!page.contains("login ends on"));
    app.state
        .music_sweep
        .set_clock(Some(t0 + chrono::TimeDelta::days(166)));
    let page = app
        .get_page("/music/settings", &cookie)
        .await
        .text()
        .replace("&#39;", "'");
    assert!(
        page.contains("Ella's Spotify login ends on 10 Apr: log in again on this page."),
        "{page}"
    );
    app.state
        .music_sweep
        .set_clock(Some(t0 + chrono::TimeDelta::days(181)));
    let page = app
        .get_page("/music/settings", &cookie)
        .await
        .text()
        .replace("&#39;", "'");
    assert!(page.contains("Ella's Spotify login has ended"));
    // invalid_grant: "log in again as Ella"; invalid_client: the app card says so.
    sqlx::query(
        "UPDATE music_spotify_accounts SET needs_login = 1, refresh_error = 'invalid_grant'",
    )
    .execute(&app.db)
    .await
    .unwrap();
    let page = app
        .get_page("/music/settings", &cookie)
        .await
        .text()
        .replace("&#39;", "'");
    assert!(page.contains("Spotify ended this login"));
    assert!(page.contains("log in again as Ella."));
    sqlx::query(
        "UPDATE music_spotify_accounts SET needs_login = 0, refresh_error = 'invalid_client'",
    )
    .execute(&app.db)
    .await
    .unwrap();
    let page = app
        .get_page("/music/settings", &cookie)
        .await
        .text()
        .replace("&#39;", "'");
    assert!(page.contains("Spotify refused this Client ID"));
    assert!(page.contains("The app was deleted, or Spotify switched it off."));
}

thread_local! {
    /// This thread's capture while [no_secret_in_logs_events_or_pages] runs, else `None`.
    pub(crate) static CAPTURE: std::cell::RefCell<Option<Vec<String>>> = const { std::cell::RefCell::new(None) };
}

/// A process-wide subscriber that records every event, at every level, of a thread whose
/// [CAPTURE] is on (`#[tokio::test]` runs every task of a test on its thread), and nothing else.
///
/// Why not a scoped `set_default` (code QA L6): tracing caches each callsite's interest
/// process-wide, computed from the dispatchers alive when the callsite registers. A scoped
/// subscriber of this test racing a callsite's first use on another test's thread could leave
/// that callsite "never" - measured: in a parallel run the first lines (`a login started`) were
/// usually missed. A global subscriber, installed once and always registered, says
/// "sometimes" for every callsite from then on, so no callsite is cached "never" and the
/// capture is decided per event by [CAPTURE] alone.
struct ThreadCapture;

impl<S: tracing::Subscriber> tracing_subscriber::Layer<S> for ThreadCapture {
    fn register_callsite(
        &self,
        _: &'static tracing::Metadata<'static>,
    ) -> tracing::subscriber::Interest {
        tracing::subscriber::Interest::sometimes()
    }

    fn enabled(
        &self,
        _: &tracing::Metadata<'_>,
        _: tracing_subscriber::layer::Context<'_, S>,
    ) -> bool {
        CAPTURE.with(|capture| capture.borrow().is_some())
    }

    fn on_event(&self, event: &tracing::Event<'_>, _: tracing_subscriber::layer::Context<'_, S>) {
        struct Fields(String);
        impl tracing::field::Visit for Fields {
            fn record_debug(&mut self, field: &tracing::field::Field, value: &dyn std::fmt::Debug) {
                self.0.push_str(&format!(" {}={:?}", field.name(), value));
            }
        }
        let mut fields = Fields(event.metadata().target().to_string());
        event.record(&mut fields);
        CAPTURE.with(|capture| {
            if let Some(lines) = capture.borrow_mut().as_mut() {
                lines.push(fields.0);
            }
        });
    }
}

/// Installs [ThreadCapture] as the global default once (no other test sets one), and rebuilds
/// every callsite's interest so callsites registered before it know it too.
pub(crate) fn install_capture() {
    use tracing_subscriber::layer::SubscriberExt;
    static INSTALLED: std::sync::OnceLock<()> = std::sync::OnceLock::new();
    INSTALLED.get_or_init(|| {
        tracing::subscriber::set_global_default(tracing_subscriber::registry().with(ThreadCapture))
            .expect("no other global subscriber in the tests");
    });
    tracing::callsite::rebuild_interest_cache();
}

/// §7.1: the code, the tokens and the verifier reach no log line (any level, reqwest's floor
/// aside), no event and no page - through a login, a paste, a failed exchange and refreshes.
#[tokio::test]
async fn no_secret_in_logs_events_or_pages() {
    install_capture();
    CAPTURE.with(|capture| *capture.borrow_mut() = Some(Vec::new()));
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    set_up(&app, &cookie, HTTPS_REDIRECT).await;
    spotify_takes(&app, "me_kid.json");
    let started = start(&app, &cookie).await;
    let res = callback(
        &app,
        &code_query(&started.query["state"], "FAKE-CODE-1"),
        Some(&started.cookie),
    )
    .await;
    assert_eq!(res.location(), Some("/music/spotify/done?login=ok"));
    let verifier = {
        let requests = app.fetch.requests.lock().unwrap().clone();
        let form: HashMap<String, String> =
            form_urlencoded::parse(requests[0].body.as_deref().unwrap())
                .into_owned()
                .collect();
        form["code_verifier"].clone()
    };
    // A failed exchange, a refresh that rotates and one that fails.
    app.fetch
        .answer(TOKEN_URL, 400, fixture("token_invalid_grant.json"));
    let started = start(&app, &cookie).await;
    callback(
        &app,
        &code_query(&started.query["state"], "FAKE-CODE-2"),
        Some(&started.cookie),
    )
    .await;
    let id = account_rows(&app).await[0].0;
    app.fetch
        .answer(TOKEN_URL, 200, fixture("token_refresh_rotated.json"));
    app.state
        .spotify
        .access_token(&app.state, id, std::time::Duration::MAX)
        .await
        .unwrap();
    app.fetch.answer(TOKEN_URL, 503, "FAKE-REFRESH-2 echoed");
    let _ = app
        .state
        .spotify
        .access_token(&app.state, id, std::time::Duration::MAX)
        .await;
    // The paste of a loopback login, and a refused paste.
    set_up(&app, &cookie, LOOPBACK_REDIRECT).await;
    spotify_takes(&app, "me_parent.json");
    let started = start(&app, &cookie).await;
    app.request_form(
        Method::POST,
        "/music/spotify/paste",
        Some(&cookie),
        &[(
            "url",
            &format!(
                "{LOOPBACK_REDIRECT}?code=FAKE-CODE-3&state={}",
                started.query["state"]
            ),
        )],
    )
    .await;
    app.request_form(
        Method::POST,
        "/music/spotify/paste",
        Some(&cookie),
        &[("url", "http://127.0.0.1:43117/callback?code=FAKE-CODE-4")],
    )
    .await;

    let mut texts: Vec<String> = CAPTURE
        .with(|capture| capture.borrow_mut().take())
        .unwrap_or_default();
    // The capture saw every line this run must log (the probe: a partial capture fails here,
    // it never passes without having looked).
    let count = |message: &str| texts.iter().filter(|t| t.contains(message)).count();
    for (message, times) in [
        ("spotify: a login started", 3),
        ("spotify: an account logged in", 2),
        ("spotify: a login failed", 1),
        ("spotify: access token refreshed", 1),
        ("spotify: a refresh failed", 1),
    ] {
        assert_eq!(count(message), times, "{message}: {texts:?}");
    }
    let events: Vec<(String, Option<String>)> =
        sqlx::query_as("SELECT event_type, detail FROM security_events")
            .fetch_all(&app.db)
            .await
            .unwrap();
    texts.extend(events.into_iter().map(|e| format!("{e:?}")));
    for page in ["/music/spotify", "/music/settings?login=ok", "/music"] {
        texts.push(
            app.get_page(page, &cookie)
                .await
                .text()
                .replace("&#39;", "'"),
        );
    }
    let stored: Vec<(String, Option<String>)> =
        sqlx::query_as("SELECT scopes, refresh_error FROM music_spotify_accounts")
            .fetch_all(&app.db)
            .await
            .unwrap();
    texts.push(format!("{stored:?}"));
    for text in &texts {
        for secret in [
            "FAKE-CODE",
            "FAKE-ACCESS",
            "FAKE-REFRESH",
            verifier.as_str(),
        ] {
            assert!(!text.contains(secret), "{secret} in {text}");
        }
    }
}

/// Code QA M1: with an https redirect, a login starts only from the redirect URI's address -
/// elsewhere it would come back to a host without its cookie and end "expired".
#[tokio::test]
async fn a_login_starts_only_at_the_redirects_address() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    set_up(&app, &cookie, HTTPS_REDIRECT).await;
    let start_from = |headers: Vec<(&'static str, &'static str)>| {
        let cookie = cookie.clone();
        let app = &app;
        async move {
            let mut builder = Request::builder()
                .method(Method::POST)
                .uri("/music/spotify/login")
                .header(header::COOKIE, &cookie);
            for (name, value) in headers {
                builder = builder.header(name, value);
            }
            app.send_via(
                &app.router,
                super::LOOPBACK_PEER,
                builder.body(Body::empty()).unwrap(),
            )
            .await
        }
    };
    for headers in [
        vec![("host", "pi.lan:8080")],
        vec![("host", "192.168.1.5:3100")],
        vec![
            ("host", "127.0.0.1:3100"),
            ("x-forwarded-host", "other.example.ts.net"),
            ("x-forwarded-proto", "https"),
        ],
    ] {
        let res = start_from(headers.clone()).await;
        assert_eq!(res.status, StatusCode::BAD_REQUEST, "{headers:?}");
        let page = res.text().replace("&#39;", "'");
        assert!(
            page.contains(
                "Spotify sends this login back to https://pi.example.ts.net: open this page there \
                 to log in."
            ),
            "{page}"
        );
        assert!(
            page.contains("href=\"https://pi.example.ts.net/music/settings#spotify-accounts\"")
        );
        // The button is focused, so the group is in view on a long Music › Settings (M1).
        assert!(page.contains("class=\"row row-button\" autofocus>Log in with Spotify</button>"));
        assert!(
            set_cookies(&res)
                .iter()
                .all(|c| !c.starts_with("spotify_login="))
        );
        assert_eq!(app.state.spotify.pending_count(), 0);
    }
    // At the redirect's address (directly, or through the trusted proxy): started.
    for headers in [
        vec![("host", "pi.example.ts.net")],
        vec![("host", "PI.example.ts.net:443")],
        vec![
            ("host", "127.0.0.1:3100"),
            ("x-forwarded-host", "pi.example.ts.net"),
            ("x-forwarded-proto", "https"),
        ],
    ] {
        let res = start_from(headers.clone()).await;
        assert_eq!(res.status, StatusCode::SEE_OTHER, "{headers:?}");
        assert!(
            set_cookies(&res)
                .iter()
                .any(|c| c.starts_with("spotify_login="))
        );
    }
    assert_eq!(app.state.spotify.pending_count(), 3);
    // The loopback redirect (the paste) starts from any address.
    set_up(&app, &cookie, LOOPBACK_REDIRECT).await;
    let res = start_from(vec![("host", "pi.lan:8080")]).await;
    assert_eq!(res.status, StatusCode::SEE_OTHER);
}

// ------------------------------------------------------------------------------------------------
// Step 2a: the listing, the modes and the sweep's check (§3.1-§3.5, §10's sweep rows)
// ------------------------------------------------------------------------------------------------

pub(in crate::tests) mod listing {
    use std::sync::Arc;

    use axum::http::StatusCode;
    use chrono::TimeDelta;
    use serde_json::{Value, json};

    use super::super::TestApp;
    use super::super::music::device_get;
    use super::super::music_art::png;
    use super::super::music_sweep::{check, items, listing, t0, tick_on};
    use super::{bind, revision};
    use crate::music::Link;
    use crate::music_sweep::{self as sweep, stamp};
    use crate::spotify::tests::{TOKEN_URL, fixture, save_app, store_account};
    use crate::spotify::{self, Accounts, Config, LOOPBACK_REDIRECT, LinkKind, Read};

    pub(in crate::tests) const PLAYLIST: &str = "0FakePlaylistKveld0001";
    pub(in crate::tests) const ALBUM_X: &str = "2FakeAlbumX00000000001";
    const ALBUM_Y: &str = "2FakeAlbumY00000000002";
    const ALBUM_Z: &str = "2FakeAlbumZ00000000003";
    pub(super) const TRACK_A: &str = "1FakeTrackA00000000001";
    pub(super) const TRACK_C: &str = "1FakeTrackC00000000003";
    pub(super) const TRACK_D: &str = "1FakeTrackD00000000004";
    pub(super) const ARTIST: &str = "3FakeArtist00000000001";
    pub(super) const SHOW: &str = "4FakeShow0000000000001";
    pub(super) const MOSAIC: &str = "https://mosaic.scdn.co/640/fake-mosaic-kveld";
    pub(super) const ART_X: &str = "https://i.scdn.co/image/fake-album-x-300";
    pub(super) const ART_Y: &str = "https://i.scdn.co/image/fake-album-y-640";

    /// A Spotify entry straight into the table (2b's add form makes them).
    pub(in crate::tests) async fn entry(app: &TestApp, kind: &str, id: &str) -> i64 {
        let target = format!("spotify:{kind}:{id}");
        sqlx::query_scalar(
            "INSERT INTO music_entries (name, category_id, source, target, key, play_order, cache, \
             resume, sort, created_at) VALUES (?, 4, 'spotify', ?, ?, 'auto', 0, 1, \
             (SELECT COALESCE(MAX(sort), 0) + 10 FROM music_entries), '2026-10-09 11:00:00') \
             RETURNING id",
        )
        .bind(format!("Spotify {kind}"))
        .bind(&target)
        .bind(crate::music::state_key(&target))
        .fetch_one(&app.db)
        .await
        .unwrap()
    }

    /// The app set up, one account (the kid's) logged in, the accounts service answering.
    pub(in crate::tests) async fn ready(app: &TestApp) -> i64 {
        app.state.music_sweep.set_art_per_check(0);
        save_app(app, LOOPBACK_REDIRECT).await;
        app.fetch
            .answer(TOKEN_URL, 200, fixture("token_refresh.json"));
        store_account(app, "fakekid", "Ella", "FAKE-REFRESH-1").await
    }

    pub(in crate::tests) fn url(app: &TestApp, read: Read<'_>) -> String {
        app.state.spotify.read_url(read)
    }

    /// The fixture playlist's metadata and items, and its mosaic cover.
    pub(super) fn answer_playlist(app: &TestApp) {
        app.fetch.answer(
            &url(app, Read::Playlist(PLAYLIST)),
            200,
            fixture("playlist.json"),
        );
        app.fetch.answer(
            &url(app, Read::PlaylistItems(PLAYLIST, 0)),
            200,
            fixture("playlist_items.json"),
        );
        app.fetch.answer(MOSAIC, 200, png(0b1000_0001, 300, 300));
    }

    fn token(access: &str) -> String {
        json!({"access_token": access, "token_type": "Bearer", "expires_in": 3600}).to_string()
    }

    fn keys(list: &[sweep::Item]) -> Vec<String> {
        list.iter().map(|i| i.key.clone()).collect()
    }

    fn calls_to(app: &TestApp, url: &str) -> usize {
        app.fetch
            .calls
            .lock()
            .unwrap()
            .iter()
            .filter(|u| u.as_str() == url)
            .count()
    }

    /// The bearer each request to `url` carried, in order.
    fn bearers(app: &TestApp, url: &str) -> Vec<String> {
        app.fetch
            .requests
            .lock()
            .unwrap()
            .iter()
            .filter(|r| r.url == url)
            .map(|r| r.header("authorization").unwrap_or_default().to_string())
            .collect()
    }

    async fn served(app: &TestApp, token: &str, id: i64) -> (StatusCode, Value) {
        let res = device_get(
            app,
            &format!("/api/devices/music/entries/{id}/items"),
            token,
            &[],
        )
        .await;
        let status = res.status;
        (
            status,
            if status == StatusCode::OK {
                res.json()
            } else {
                Value::Null
            },
        )
    }

    /// The acceptance's first line: a check over CannedFetch lists the fixture playlist (§3.4):
    /// the metadata read, the items over `items[].item` (left out: an episode, a local file, an
    /// unplayable track, a removed one; a repeat listed once), "Name — A, B", `url` NULL, the album
    /// picture as `art_url`, the listing row's Spotify state; the route serves it to a phone that
    /// plays as an account, and to no other.
    #[tokio::test]
    async fn a_check_lists_the_fixture_playlist() {
        let app = TestApp::new().await;
        let kid = ready(&app).await;
        let id = entry(&app, "playlist", PLAYLIST).await;
        answer_playlist(&app);
        let report = check(&app, id, t0()).await;
        assert_eq!(report.error, None);
        // The token, the metadata, the items, the cover.
        assert_eq!(report.requests, 4);
        assert_eq!(report.new_items, 3);
        assert!(report.changed);
        let list = items(&app, id).await;
        assert_eq!(keys(&list), [TRACK_A, TRACK_C, TRACK_D]);
        assert_eq!(
            list.iter().map(|i| i.title.as_deref()).collect::<Vec<_>>(),
            [
                Some("Stille natt — Ola Fjell, Kari Li"),
                Some("Månesang — Kari Li"),
                Some("Uten artist")
            ]
        );
        assert!(
            list.iter()
                .all(|i| i.state == "ok" && i.url.is_none() && !i.hls)
        );
        assert_eq!(
            list.iter()
                .map(|i| i.art_url.as_deref())
                .collect::<Vec<_>>(),
            [Some(ART_X), Some(ART_X), Some(ART_Y)]
        );
        assert_eq!(list[0].duration_ms, Some(201_000));
        let row = listing(&app, id).await;
        assert!(row.version.is_some());
        assert_eq!(row.title.as_deref(), Some("Kveldsmusikk"));
        assert_eq!(row.spotify_mode.as_deref(), Some("list"));
        assert_eq!(
            row.spotify_snapshot.as_deref(),
            Some("AAAAA0fakeSnapshotKveld1")
        );
        assert_eq!(row.spotify_items_at, Some(stamp(t0())));
        assert_eq!(row.spotify_reader, Some(kid));
        assert_eq!(row.spotify_skipped, 4);
        assert_eq!(row.keep_end.as_deref(), Some("first"));
        assert_eq!((row.capped, row.lan, row.failures), (false, Some(false), 0));
        assert_eq!(row.cover_url.as_deref(), Some(MOSAIC));
        assert!(row.cover_hash.is_some());
        // The reads: the bearer, JSON, public addresses only, built from the canonical id.
        let requests = app.fetch.requests.lock().unwrap().clone();
        let read = requests
            .iter()
            .find(|r| r.url == url(&app, Read::PlaylistItems(PLAYLIST, 0)))
            .unwrap();
        assert_eq!(read.header("authorization"), Some("Bearer FAKE-ACCESS-2"));
        assert_eq!(read.header("accept"), Some("application/json"));
        assert!(
            app.fetch
                .reaches
                .lock()
                .unwrap()
                .iter()
                .all(|(_, reach)| *reach == crate::music_net::Reach::Public)
        );
        assert_eq!(
            requests[1].url,
            format!(
                "https://api.spotify.com/v1/playlists/{PLAYLIST}?fields=name%2Csnapshot_id%2C\
                 images%2Cowner%28id%2Cdisplay_name%29"
            )
        );

        // The listing route: ticked but Spotify off on the phone - nothing (§3.6); bound - it.
        let (phone, token) = app.enrolled_device("Ella").await;
        tick_on(&app, phone, id).await;
        assert_eq!(served(&app, &token, id).await.0, StatusCode::NOT_FOUND);
        bind(&app, phone, kid).await;
        let (status, doc) = served(&app, &token, id).await;
        assert_eq!(status, StatusCode::OK);
        assert_eq!(doc["version"], row.version.clone().unwrap().as_str());
        assert_eq!(
            doc["items"]
                .as_array()
                .unwrap()
                .iter()
                .map(|i| (i["key"].as_str().unwrap(), i["url"].is_null()))
                .collect::<Vec<_>>(),
            [(TRACK_A, true), (TRACK_C, true), (TRACK_D, true)]
        );
        assert_eq!(doc["items"][1]["title"], "Månesang — Kari Li");

        // The parent empties the playlist: listed so (no items), and served so.
        let mut empty: Value = serde_json::from_slice(&fixture("playlist_items.json")).unwrap();
        empty["items"] = json!([]);
        empty["total"] = json!(0);
        app.fetch.answer(
            &url(&app, Read::PlaylistItems(PLAYLIST, 0)),
            200,
            empty.to_string(),
        );
        let now = t0() + TimeDelta::hours(1);
        sweep::request_check(&app.db, id, now).await.unwrap();
        assert_eq!(check(&app, id, now).await.error, None);
        assert!(items(&app, id).await.is_empty());
        let emptied = listing(&app, id).await.version.unwrap();
        assert_ne!(Some(emptied.clone()), row.version);
        let (status, doc) = served(&app, &token, id).await;
        assert_eq!(status, StatusCode::OK);
        assert_eq!(
            (doc["version"].as_str(), &doc["items"]),
            (Some(emptied.as_str()), &json!([]))
        );
    }

    /// The snapshot gate (vibb `_precache_due`): an unchanged playlist costs one request; its
    /// items are read again after a day, for Check now and when the snapshot moves.
    #[tokio::test]
    async fn the_snapshot_gate() {
        let app = TestApp::new().await;
        ready(&app).await;
        let id = entry(&app, "playlist", PLAYLIST).await;
        answer_playlist(&app);
        let items_url = url(&app, Read::PlaylistItems(PLAYLIST, 0));
        check(&app, id, t0()).await;
        let version = listing(&app, id).await.version;

        app.fetch.clear_calls();
        let report = check(&app, id, t0() + TimeDelta::hours(6)).await;
        assert_eq!((report.requests, report.error.clone()), (1, None));
        assert_eq!(calls_to(&app, &items_url), 0);
        assert!(!report.changed);
        let row = listing(&app, id).await;
        assert_eq!(row.version, version);
        assert_eq!(row.spotify_items_at, Some(stamp(t0())), "not read again");
        assert_eq!(row.listed_at, Some(stamp(t0() + TimeDelta::hours(6))));

        // A day, a week, a month later: an unchanged snapshot never reads the items again
        // (2026-10-21, the user: the snapshot moves with any change to the list).
        for later in [25, 24 * 8, 24 * 31] {
            app.fetch.clear_calls();
            let report = check(&app, id, t0() + TimeDelta::hours(later)).await;
            assert_eq!((report.requests, report.error.clone()), (1, None));
            assert_eq!(calls_to(&app, &items_url), 0);
        }
        assert_eq!(listing(&app, id).await.spotify_items_at, Some(stamp(t0())));

        // Check now ignores the gate.
        let now = t0() + TimeDelta::hours(24 * 31 + 1);
        sweep::request_check(&app.db, id, now).await.unwrap();
        app.fetch.clear_calls();
        check(&app, id, now).await;
        assert_eq!(calls_to(&app, &items_url), 1);
        assert_eq!(listing(&app, id).await.spotify_items_at, Some(stamp(now)));

        // A new snapshot: the items, a new list.
        let mut meta: Value = serde_json::from_slice(&fixture("playlist.json")).unwrap();
        meta["snapshot_id"] = json!("AAAAA0fakeSnapshotKveld2");
        app.fetch
            .answer(&url(&app, Read::Playlist(PLAYLIST)), 200, meta.to_string());
        let mut page: Value = serde_json::from_slice(&fixture("playlist_items.json")).unwrap();
        page["items"].as_array_mut().unwrap().truncate(1);
        app.fetch.answer(&items_url, 200, page.to_string());
        app.fetch.clear_calls();
        let report = check(&app, id, t0() + TimeDelta::hours(24 * 31 + 2)).await;
        assert_eq!(calls_to(&app, &items_url), 1);
        assert!(report.changed);
        assert_eq!(keys(&items(&app, id).await), [TRACK_A]);
        let row = listing(&app, id).await;
        assert_eq!(
            row.spotify_snapshot.as_deref(),
            Some("AAAAA0fakeSnapshotKveld2")
        );
        assert_eq!(row.spotify_skipped, 0);
        assert_ne!(row.version, version);
    }

    /// The acceptance's second line, with the readers (§3.3): every account gets 403 on the
    /// items - context mode (no items, a version, the library revision moved, the route serves
    /// it); once an account may list it, list mode again, read by that account, which reads first
    /// from then on.
    #[tokio::test]
    async fn a_403_playlist_goes_to_context_mode_and_back() {
        let app = TestApp::new().await;
        app.state.music_sweep.set_art_per_check(0);
        save_app(&app, LOOPBACK_REDIRECT).await;
        let kid = store_account(&app, "fakekid", "Ella", "FAKE-REFRESH-1").await;
        let parent = store_account(&app, "fakeparent", "Forelder", "FAKE-REFRESH-9").await;
        // Both logged in before the checks (a newer login makes a context playlist try at once).
        sqlx::query("UPDATE music_spotify_accounts SET authorized_at = '2026-10-08 12:00:00'")
            .execute(&app.db)
            .await
            .unwrap();
        // The kid's refresh comes first (the readers by id), then the parent's.
        app.fetch
            .answer_once(TOKEN_URL, 200, token("FAKE-ACCESS-KID"));
        app.fetch
            .answer(TOKEN_URL, 200, token("FAKE-ACCESS-PARENT"));
        let id = entry(&app, "playlist", PLAYLIST).await;
        answer_playlist(&app);
        let items_url = url(&app, Read::PlaylistItems(PLAYLIST, 0));
        let meta_url = url(&app, Read::Playlist(PLAYLIST));
        app.fetch.answer(&items_url, 403, fixture("forbidden.json"));
        let (phone, token) = app.enrolled_device("Ella").await;
        tick_on(&app, phone, id).await;
        bind(&app, phone, kid).await;
        let before = revision(&app).await;

        let report = check(&app, id, t0()).await;
        assert_eq!(report.error, None);
        assert_eq!(
            bearers(&app, &items_url),
            ["Bearer FAKE-ACCESS-KID", "Bearer FAKE-ACCESS-PARENT"],
            "each reader in turn"
        );
        assert!(items(&app, id).await.is_empty());
        let row = listing(&app, id).await;
        assert_eq!(row.spotify_mode.as_deref(), Some("context"));
        assert_eq!(row.spotify_reader, Some(kid), "the metadata's reader");
        assert_eq!(row.title.as_deref(), Some("Kveldsmusikk"));
        let context = row.version.clone().expect("listed");
        assert!(revision(&app).await > before);
        let (status, doc) = served(&app, &token, id).await;
        assert_eq!(status, StatusCode::OK);
        assert_eq!(doc["version"], context.as_str());
        assert_eq!(doc["items"], json!([]));
        assert_eq!(row.spotify_items_at, Some(stamp(t0())), "the try");
        // Context mode tries the items again at most daily (2026-10-21): the checks in between
        // read the metadata only - not one 403 per account at every sweep.
        for later in [1, 6, 23] {
            app.fetch.clear_calls();
            let report = check(&app, id, t0() + TimeDelta::hours(later)).await;
            assert_eq!((report.requests, report.error.clone()), (1, None));
            assert_eq!(calls_to(&app, &meta_url), 1);
            assert_eq!(calls_to(&app, &items_url), 0);
        }
        let row = listing(&app, id).await;
        assert_eq!(row.version, Some(context.clone()));
        assert_eq!(row.spotify_mode.as_deref(), Some("context"));
        assert_eq!(row.spotify_items_at, Some(stamp(t0())));
        // A day after the try: every reader again.
        app.fetch.clear_calls();
        check(&app, id, t0() + TimeDelta::hours(24)).await;
        assert_eq!(calls_to(&app, &items_url), 2);
        assert_eq!(listing(&app, id).await.version, Some(context.clone()));
        // Check now tries at once.
        let now = t0() + TimeDelta::hours(25);
        sweep::request_check(&app.db, id, now).await.unwrap();
        app.fetch.clear_calls();
        check(&app, id, now).await;
        assert_eq!(calls_to(&app, &items_url), 2);

        // The parent may list it now (the kid still may not), and logs in again: a login newer
        // than the try makes the next check try at once - list mode, read by the parent.
        sqlx::query("UPDATE music_spotify_accounts SET authorized_at = ? WHERE id = ?")
            .bind(stamp(t0() + TimeDelta::hours(25) + TimeDelta::minutes(5)))
            .bind(parent)
            .execute(&app.db)
            .await
            .unwrap();
        app.fetch
            .answer_once(&items_url, 403, fixture("forbidden.json"));
        app.fetch
            .answer(&items_url, 200, fixture("playlist_items.json"));
        app.fetch.clear_calls();
        let report = check(&app, id, t0() + TimeDelta::hours(26)).await;
        assert_eq!(report.error, None);
        assert_eq!(
            bearers(&app, &items_url),
            ["Bearer FAKE-ACCESS-KID", "Bearer FAKE-ACCESS-PARENT"]
        );
        assert_eq!(keys(&items(&app, id).await), [TRACK_A, TRACK_C, TRACK_D]);
        let row = listing(&app, id).await;
        assert_eq!(row.spotify_mode.as_deref(), Some("list"));
        assert_eq!(row.spotify_reader, Some(parent));
        assert_ne!(row.version, Some(context));
        let (_, doc) = served(&app, &token, id).await;
        assert_eq!(doc["items"].as_array().unwrap().len(), 3);

        // The reader is remembered: the next read asks the parent first.
        app.fetch.clear_calls();
        let now = t0() + TimeDelta::hours(27);
        sweep::request_check(&app.db, id, now).await.unwrap();
        check(&app, id, now).await;
        assert_eq!(bearers(&app, &meta_url), ["Bearer FAKE-ACCESS-PARENT"]);
        assert_eq!(bearers(&app, &items_url), ["Bearer FAKE-ACCESS-PARENT"]);

        // A removed reader counts for nothing: the kid reads (and gets 403: context again).
        crate::spotify::remove_account(&app.state, parent)
            .await
            .unwrap();
        app.fetch.answer(&items_url, 403, fixture("forbidden.json"));
        app.fetch.clear_calls();
        let now = t0() + TimeDelta::hours(28);
        sweep::request_check(&app.db, id, now).await.unwrap();
        check(&app, id, now).await;
        assert_eq!(bearers(&app, &items_url), ["Bearer FAKE-ACCESS-KID"]);
        assert_eq!(
            listing(&app, id).await.spotify_mode.as_deref(),
            Some("context")
        );
        assert!(items(&app, id).await.is_empty());
    }

    /// An artist is a context (one request a day); with `SPOTIFY_ARTIST_ALBUMS` it is listed as
    /// its albums' tracks, newest album first, each track with its album's picture - and Spotify
    /// refusing the albums falls back to context mode (§3.3, QA L12).
    #[tokio::test]
    async fn an_artist_is_a_context_unless_the_option_lists_its_albums() {
        let app = TestApp::new().await;
        let kid = ready(&app).await;
        let id = entry(&app, "artist", ARTIST).await;
        app.fetch.answer(
            &url(&app, Read::Artist(ARTIST)),
            200,
            fixture("artist.json"),
        );
        let picture = "https://i.scdn.co/image/fake-artist-320";
        app.fetch.answer(picture, 200, png(0b0100_0010, 320, 320));
        let report = check(&app, id, t0()).await;
        assert_eq!((report.error.clone(), report.requests), (None, 3));
        let row = listing(&app, id).await;
        assert_eq!(row.spotify_mode.as_deref(), Some("context"));
        assert_eq!(row.title.as_deref(), Some("Ola Fjell"));
        assert_eq!(row.cover_url.as_deref(), Some(picture));
        assert!(row.version.is_some());
        assert!(items(&app, id).await.is_empty());

        // The option (read once at start: a server with the variable set).
        let mut state = app.state.clone();
        state.spotify = Arc::new(Accounts::new(Config::from_vars(
            &[("SPOTIFY_ARTIST_ALBUMS".to_string(), "1".to_string())].into(),
        )));
        let albums_url = state.spotify.read_url(Read::ArtistAlbums(ARTIST, 0));
        app.fetch
            .answer(&albums_url, 200, fixture("artist_albums.json"));
        app.fetch
            .answer(&url(&app, Read::Album(ALBUM_X)), 200, fixture("album.json"));
        app.fetch.answer(
            &url(&app, Read::AlbumTracks(ALBUM_X, 50)),
            200,
            fixture("album_tracks.json"),
        );
        let single = json!({
            "name": "Fjellsanger", "artists": [{"name": "Ola Fjell"}],
            "images": [{"url": "https://i.scdn.co/image/fake-album-y-300", "width": 300}],
            "tracks": {"items": [{"type": "track", "id": "1FakeTrackH00000000008",
                "name": "Fjellvise", "artists": [{"name": "Ola Fjell"}],
                "disc_number": 1, "track_number": 1, "duration_ms": 190000}],
                "next": null}
        });
        app.fetch
            .answer(&url(&app, Read::Album(ALBUM_Y)), 200, single.to_string());
        // An album that is gone is passed over.
        app.fetch.answer(&url(&app, Read::Album(ALBUM_Z)), 404, "");
        let report = sweep::check_entry(&state, id, t0() + TimeDelta::hours(25))
            .await
            .unwrap()
            .unwrap();
        assert_eq!(report.error, None);
        let list = items(&app, id).await;
        assert_eq!(
            keys(&list),
            [
                "1FakeTrackH00000000008",
                TRACK_A,
                TRACK_C,
                "1FakeTrackG00000000007"
            ],
            "the single (2026-03) before the album (2025-11-14), each in track order"
        );
        assert_eq!(
            list.iter()
                .map(|i| i.art_url.as_deref())
                .collect::<Vec<_>>(),
            [
                Some("https://i.scdn.co/image/fake-album-y-300"),
                Some(ART_X),
                Some(ART_X),
                Some(ART_X)
            ]
        );
        let row = listing(&app, id).await;
        assert_eq!(row.spotify_mode.as_deref(), Some("list"));
        assert_eq!(row.spotify_reader, Some(kid));
        assert_eq!(row.spotify_skipped, 1);
        assert_eq!(row.keep_end.as_deref(), Some("first"));
        assert!(!row.capped);

        // Spotify refuses the albums: played as Spotify picks.
        app.fetch
            .answer(&albums_url, 403, fixture("forbidden.json"));
        let report = sweep::check_entry(&state, id, t0() + TimeDelta::hours(50))
            .await
            .unwrap()
            .unwrap();
        assert_eq!(report.error, None);
        assert_eq!(
            listing(&app, id).await.spotify_mode.as_deref(),
            Some("context")
        );
        assert!(items(&app, id).await.is_empty());
    }

    /// List mode for an album (all pages, disc and track order), a track and an episode (one
    /// item each), a show (newest first from Spotify, kept oldest first like a podcast).
    #[tokio::test]
    async fn albums_tracks_shows_and_episodes_are_listed() {
        let app = TestApp::new().await;
        ready(&app).await;
        let album = entry(&app, "album", ALBUM_X).await;
        app.fetch
            .answer(&url(&app, Read::Album(ALBUM_X)), 200, fixture("album.json"));
        app.fetch.answer(
            &url(&app, Read::AlbumTracks(ALBUM_X, 50)),
            200,
            fixture("album_tracks.json"),
        );
        assert_eq!(check(&app, album, t0()).await.error, None);
        let list = items(&app, album).await;
        assert_eq!(keys(&list), [TRACK_A, TRACK_C, "1FakeTrackG00000000007"]);
        assert!(
            list.iter().all(|i| i.art_url.is_none()),
            "the entry's cover"
        );
        let row = listing(&app, album).await;
        assert_eq!(row.title.as_deref(), Some("Nattlys — Ola Fjell"));
        assert_eq!(row.spotify_skipped, 1);

        let track = entry(&app, "track", "1FakeTrackH00000000008").await;
        app.fetch.answer(
            &url(&app, Read::Track("1FakeTrackH00000000008")),
            200,
            fixture("track.json"),
        );
        assert_eq!(check(&app, track, t0()).await.error, None);
        let list = items(&app, track).await;
        assert_eq!(keys(&list), ["1FakeTrackH00000000008"]);
        assert_eq!(
            list[0].title.as_deref(),
            Some("Fjellvise — Ola Fjell, Kari Li")
        );
        assert_eq!(
            list[0].art_url.as_deref(),
            Some("https://i.scdn.co/image/fake-album-y-300")
        );

        let show = entry(&app, "show", SHOW).await;
        app.fetch
            .answer(&url(&app, Read::Show(SHOW)), 200, fixture("show.json"));
        app.fetch.answer(
            &url(&app, Read::ShowEpisodes(SHOW, 0)),
            200,
            fixture("show_episodes.json"),
        );
        assert_eq!(check(&app, show, t0()).await.error, None);
        let list = items(&app, show).await;
        assert_eq!(
            keys(&list),
            ["5FakeEpisode0000000001", "5FakeEpisode0000000003"],
            "oldest first"
        );
        assert_eq!(list[1].title.as_deref(), Some("Episode 3: Trollet"));
        let row = listing(&app, show).await;
        assert_eq!(row.keep_end.as_deref(), Some("newest"));
        assert_eq!((row.spotify_skipped, row.capped), (2, false));

        let episode = entry(&app, "episode", "5FakeEpisode0000000003").await;
        app.fetch.answer(
            &url(&app, Read::Episode("5FakeEpisode0000000003")),
            200,
            fixture("episode.json"),
        );
        assert_eq!(check(&app, episode, t0()).await.error, None);
        assert_eq!(
            keys(&items(&app, episode).await),
            ["5FakeEpisode0000000003"]
        );
        assert_eq!(
            listing(&app, episode).await.title.as_deref(),
            Some("Episode 3: Trollet")
        );
    }

    /// The show gate (2026-10-21): after a full read, a check asks for the newest episode only
    /// (`limit=1`) and stops when it and the count are the same; a new newest episode, a changed
    /// count or Check now reads the episodes as before.
    #[tokio::test]
    async fn a_show_reads_its_episodes_only_when_the_newest_or_the_count_moves() {
        let app = TestApp::new().await;
        ready(&app).await;
        let show = entry(&app, "show", SHOW).await;
        app.fetch
            .answer(&url(&app, Read::Show(SHOW)), 200, fixture("show.json"));
        for size in [640, 300, 64] {
            let cover = format!("https://i.scdn.co/image/fake-show-{size}");
            app.fetch.answer(&cover, 200, png(0b1000_0001, 300, 300));
        }
        let episodes_url = url(&app, Read::ShowEpisodes(SHOW, 0));
        let newest_url = url(&app, Read::ShowNewest(SHOW));
        let full: Value = serde_json::from_slice(&fixture("show_episodes.json")).unwrap();
        let newest = |page: &Value| {
            let mut one = page.clone();
            one["items"].as_array_mut().unwrap().truncate(1);
            one["limit"] = json!(1);
            one["next"] = json!("https://api.spotify.com/next");
            one.to_string()
        };
        app.fetch.answer(&episodes_url, 200, full.to_string());
        app.fetch.answer(&newest_url, 200, newest(&full));
        assert_eq!(check(&app, show, t0()).await.error, None);
        assert_eq!(calls_to(&app, &newest_url), 0, "no gate before a full read");
        let row = listing(&app, show).await;
        assert_eq!(
            row.spotify_snapshot.as_deref(),
            Some("4:5FakeEpisode0000000003")
        );
        let version = row.version;

        // Unchanged: the metadata and the one-episode read, nothing else.
        for later in [6, 24 * 8] {
            app.fetch.clear_calls();
            let report = check(&app, show, t0() + TimeDelta::hours(later)).await;
            assert_eq!((report.requests, report.error.clone()), (2, None));
            assert_eq!(calls_to(&app, &newest_url), 1);
            assert_eq!(calls_to(&app, &episodes_url), 0);
            assert!(!report.changed);
        }
        assert_eq!(listing(&app, show).await.version, version);

        // Check now reads in full.
        let now = t0() + TimeDelta::hours(24 * 8 + 1);
        sweep::request_check(&app.db, show, now).await.unwrap();
        app.fetch.clear_calls();
        check(&app, show, now).await;
        assert_eq!(
            (calls_to(&app, &newest_url), calls_to(&app, &episodes_url)),
            (0, 1)
        );

        // The same newest episode but another count (an old one removed): read in full.
        let mut fewer = full.clone();
        fewer["items"].as_array_mut().unwrap().pop();
        fewer["total"] = json!(3);
        app.fetch.answer(&episodes_url, 200, fewer.to_string());
        app.fetch.answer(&newest_url, 200, newest(&fewer));
        app.fetch.clear_calls();
        let report = check(&app, show, t0() + TimeDelta::hours(24 * 8 + 2)).await;
        assert_eq!(
            (calls_to(&app, &newest_url), calls_to(&app, &episodes_url)),
            (1, 1)
        );
        assert!(report.changed);
        assert_eq!(keys(&items(&app, show).await), ["5FakeEpisode0000000003"]);

        // A new newest episode with the same count: read in full, the new mark kept.
        let mut moved = fewer.clone();
        moved["items"][0]["id"] = json!("5FakeEpisode0000000004");
        moved["items"][0]["uri"] = json!("spotify:episode:5FakeEpisode0000000004");
        app.fetch.answer(&episodes_url, 200, moved.to_string());
        app.fetch.answer(&newest_url, 200, newest(&moved));
        app.fetch.clear_calls();
        check(&app, show, t0() + TimeDelta::hours(24 * 8 + 3)).await;
        assert_eq!(calls_to(&app, &episodes_url), 1);
        assert_eq!(keys(&items(&app, show).await), ["5FakeEpisode0000000004"]);
        assert_eq!(
            listing(&app, show).await.spotify_snapshot.as_deref(),
            Some("3:5FakeEpisode0000000004")
        );
        // And unchanged from there: the gate holds again.
        app.fetch.clear_calls();
        check(&app, show, t0() + TimeDelta::hours(24 * 8 + 4)).await;
        assert_eq!(calls_to(&app, &episodes_url), 0);
    }

    fn synthetic_track(n: usize) -> Value {
        json!({"type": "track", "id": format!("1FakeTrackN{n:011}"), "name": format!("Spor {n}"),
               "artists": [{"name": "Ola Fjell"}], "duration_ms": 1000 + n})
    }

    /// The caps: a playlist lists its first 1000 tracks (`capped`, 21 reads with the metadata),
    /// a show its newest 100 episodes.
    #[tokio::test]
    async fn long_playlists_and_shows_are_capped() {
        let app = TestApp::new().await;
        ready(&app).await;
        let id = entry(&app, "playlist", PLAYLIST).await;
        app.fetch.answer(
            &url(&app, Read::Playlist(PLAYLIST)),
            200,
            fixture("playlist.json"),
        );
        for page in 0..22 {
            let offset = page * 50;
            let tracks: Vec<Value> = (offset..offset + 50)
                .map(|n| json!({"is_local": false, "item": synthetic_track(n)}))
                .collect();
            let next = (page < 21).then(|| format!("https://api.spotify.com/next/{page}"));
            app.fetch.answer(
                &url(&app, Read::PlaylistItems(PLAYLIST, offset)),
                200,
                json!({"items": tracks, "next": next, "total": 1100}).to_string(),
            );
        }
        let report = check(&app, id, t0()).await;
        assert_eq!(report.error, None);
        // The token, the metadata, 20 pages, the cover's try.
        assert_eq!(report.requests, 23);
        let list = items(&app, id).await;
        assert_eq!(list.len(), spotify::MAX_LIST_TRACKS);
        assert_eq!(list[999].key, "1FakeTrackN00000000999");
        let row = listing(&app, id).await;
        assert!(row.capped);
        assert_eq!(row.keep_end.as_deref(), Some("first"));
        assert_eq!(
            calls_to(&app, &url(&app, Read::PlaylistItems(PLAYLIST, 1000))),
            0
        );

        let show = entry(&app, "show", SHOW).await;
        app.fetch
            .answer(&url(&app, Read::Show(SHOW)), 200, fixture("show.json"));
        for page in 0..3 {
            let offset = page * 50;
            // Newest first: episode 149 is the newest.
            let episodes: Vec<Value> = (offset..offset + 50)
                .map(|n| {
                    json!({"type": "episode", "id": format!("5FakeEpisodeN{:09}", 149 - n),
                           "name": format!("Episode {}", 149 - n), "duration_ms": 60000})
                })
                .collect();
            let next = (page < 2).then(|| "https://api.spotify.com/next".to_string());
            app.fetch.answer(
                &url(&app, Read::ShowEpisodes(SHOW, offset)),
                200,
                json!({"items": episodes, "next": next}).to_string(),
            );
        }
        assert_eq!(check(&app, show, t0()).await.error, None);
        let list = items(&app, show).await;
        assert_eq!(list.len(), spotify::MAX_SHOW_EPISODES);
        assert_eq!(list[0].key, "5FakeEpisodeN000000050", "the oldest kept");
        assert_eq!(list[99].key, "5FakeEpisodeN000000149", "the newest last");
        let row = listing(&app, show).await;
        assert!(row.capped);
        assert_eq!(row.keep_end.as_deref(), Some("newest"));
    }

    /// No app, no account, every login ended: the check fails with its code and asks nothing;
    /// a refused or limited read fails it too - and every failure keeps the last good list.
    #[tokio::test]
    async fn failures_keep_the_last_good_list() {
        let app = TestApp::new().await;
        let kid = ready(&app).await;
        let id = entry(&app, "playlist", PLAYLIST).await;
        answer_playlist(&app);
        check(&app, id, t0()).await;
        let good = listing(&app, id).await;
        let good_items = items(&app, id).await;
        let meta_url = url(&app, Read::Playlist(PLAYLIST));

        let mut at = t0();
        let mut fails = |code: &'static str, requests: u32| {
            at += TimeDelta::hours(30);
            let now = at;
            let app = &app;
            let good = good.clone();
            let good_items = good_items.clone();
            async move {
                app.fetch.clear_calls();
                let report = check(app, id, now).await;
                assert_eq!(report.error.as_deref(), Some(code));
                assert_eq!(report.requests, requests, "{code}");
                assert_eq!(app.fetch.count() as u32, requests, "{code}");
                let row = listing(app, id).await;
                assert_eq!(row.version, good.version, "{code}");
                assert_eq!(row.error.as_deref(), Some(code));
                assert_eq!(row.spotify_mode, good.spotify_mode, "{code}");
                assert_eq!(row.spotify_reader, good.spotify_reader, "{code}");
                assert_eq!(row.spotify_skipped, good.spotify_skipped, "{code}");
                assert_eq!(items(app, id).await, good_items, "{code}");
            }
        };
        // Spotify's rate limit (a Retry-After past 5 s is the answer).
        app.fetch
            .answer(&meta_url, 429, fixture("rate_limited.json"));
        app.fetch.retry_after(&meta_url, "30");
        fails("rate_limited", 1).await;
        // 404 for every reader; Spotify's 5xx; an answer that isn't Spotify's.
        app.fetch.answer(&meta_url, 404, "");
        fails("not_found", 1).await;
        app.fetch.answer(&meta_url, 503, "");
        fails("http_503", 1).await;
        app.fetch.answer(&meta_url, 200, "<html>");
        fails("bad_answer", 1).await;
        // The login ended (needs_login): no request.
        sqlx::query("UPDATE music_spotify_accounts SET needs_login = 1 WHERE id = ?")
            .bind(kid)
            .execute(&app.db)
            .await
            .unwrap();
        fails("reauthorize", 0).await;
        // No account at all; no app.
        sqlx::query("DELETE FROM music_spotify_accounts")
            .execute(&app.db)
            .await
            .unwrap();
        fails("no_account", 0).await;
        sqlx::query("DELETE FROM music_spotify_app")
            .execute(&app.db)
            .await
            .unwrap();
        fails("spotify_off", 0).await;
        assert_eq!(
            crate::music::sweep_error_text("spotify", "spotify_off"),
            "no Spotify app is set up on this server"
        );
        assert_eq!(
            crate::music::CheckError::Spotify("no_account".to_string()).message(),
            "No Spotify account is logged in."
        );
    }

    /// qa-26c-step2a-code M1 (its probe, kept): a listed playlist whose remembered reader now
    /// gets 403 and whose next reader hits anything that isn't a refusal - the quota, a 503 -
    /// fails the check with that code and keeps the list, the mode, the version and the library
    /// revision; only every reader refusing makes it a context. An account that can't be used
    /// (another key) isn't a refusal either: its code, the list kept.
    #[tokio::test]
    async fn a_transient_after_a_403_keeps_the_listed_playlist() {
        let app = TestApp::new().await;
        app.state.music_sweep.set_art_per_check(0);
        save_app(&app, LOOPBACK_REDIRECT).await;
        let kid = store_account(&app, "fakekid", "Ella", "FAKE-REFRESH-1").await;
        let parent = store_account(&app, "fakeparent", "Forelder", "FAKE-REFRESH-9").await;
        app.fetch
            .answer_once(TOKEN_URL, 200, token("FAKE-ACCESS-KID"));
        app.fetch
            .answer(TOKEN_URL, 200, token("FAKE-ACCESS-PARENT"));
        let id = entry(&app, "playlist", PLAYLIST).await;
        answer_playlist(&app);
        let items_url = url(&app, Read::PlaylistItems(PLAYLIST, 0));
        check(&app, id, t0()).await;
        let good = listing(&app, id).await;
        assert_eq!(good.spotify_reader, Some(kid));
        let good_items = items(&app, id).await;
        let mut at = t0();
        for (second, code) in [
            ((503, Vec::new()), "http_503"),
            ((429, fixture("quota.json")), "quota"),
        ] {
            at += TimeDelta::hours(1);
            app.fetch
                .answer_once(&items_url, 403, fixture("forbidden.json"));
            app.fetch.answer_once(&items_url, second.0, second.1);
            let before = revision(&app).await;
            sweep::request_check(&app.db, id, at).await.unwrap();
            app.fetch.clear_calls();
            let report = check(&app, id, at).await;
            assert_eq!(report.error.as_deref(), Some(code));
            assert_eq!(
                bearers(&app, &items_url),
                ["Bearer FAKE-ACCESS-KID", "Bearer FAKE-ACCESS-PARENT"],
                "{code}"
            );
            let row = listing(&app, id).await;
            assert_eq!(row.spotify_mode.as_deref(), Some("list"), "{code}");
            assert_eq!(row.version, good.version, "{code}");
            assert_eq!(row.spotify_reader, Some(kid), "{code}");
            assert_eq!(items(&app, id).await, good_items, "{code}");
            assert_eq!(revision(&app).await, before, "{code}");
        }
        app.state.spotify.advance(spotify::QUOTA_PAUSE);
        // The kid refused, the parent's login sealed for another user (the key can't open it).
        sqlx::query("UPDATE music_spotify_accounts SET spotify_user = 'moved' WHERE id = ?")
            .bind(parent)
            .execute(&app.db)
            .await
            .unwrap();
        app.state.spotify.forget(parent);
        app.fetch
            .answer_once(&items_url, 403, fixture("forbidden.json"));
        at += TimeDelta::hours(1);
        sweep::request_check(&app.db, id, at).await.unwrap();
        let report = check(&app, id, at).await;
        assert_eq!(report.error.as_deref(), Some("key"));
        let row = listing(&app, id).await;
        assert_eq!(row.spotify_mode.as_deref(), Some("list"));
        assert_eq!(items(&app, id).await, good_items);
        assert_eq!(
            crate::music::sweep_error_text("spotify", "key"),
            "a saved Spotify login was locked with another key - log in again"
        );
    }

    /// The artist option's albums: a 403 then a 503 fails the check (not a context); the albums
    /// are read 10 a page (the endpoint's maximum, qa-26c-step2a-code M2), at most 10 pages, the
    /// newest 20 picked from all of them - `capped` when pages were left.
    #[tokio::test]
    async fn the_artist_options_albums_page_by_ten() {
        let app = TestApp::new().await;
        app.state.music_sweep.set_art_per_check(0);
        save_app(&app, LOOPBACK_REDIRECT).await;
        store_account(&app, "fakekid", "Ella", "FAKE-REFRESH-1").await;
        store_account(&app, "fakeparent", "Forelder", "FAKE-REFRESH-9").await;
        app.fetch
            .answer(TOKEN_URL, 200, fixture("token_refresh.json"));
        let mut state = app.state.clone();
        state.spotify = Arc::new(Accounts::new(Config::from_vars(
            &[("SPOTIFY_ARTIST_ALBUMS".to_string(), "1".to_string())].into(),
        )));
        let id = entry(&app, "artist", ARTIST).await;
        app.fetch.answer(
            &url(&app, Read::Artist(ARTIST)),
            200,
            fixture("artist.json"),
        );
        let albums_url = |offset| state.spotify.read_url(Read::ArtistAlbums(ARTIST, offset));
        assert!(albums_url(0).contains("&limit=10&offset=0&"));
        // A 403 for the kid, a 503 for the parent: the check fails, nothing becomes a context.
        app.fetch
            .answer_once(&albums_url(0), 403, fixture("forbidden.json"));
        app.fetch.answer_once(&albums_url(0), 503, "");
        let report = sweep::check_entry(&state, id, t0()).await.unwrap().unwrap();
        assert_eq!(report.error.as_deref(), Some("http_503"));
        assert_eq!(listing(&app, id).await.spotify_mode, None);

        // Eleven pages of ten albums (the 11th is never asked); album n released on day n, so
        // the newest 20 are 99..=80, spread over the pages in Spotify's order.
        for page in 0..11 {
            let albums: Vec<Value> = (page * 10..page * 10 + 10)
                .map(|n| {
                    json!({"id": format!("2FakeAlbumN{n:011}"),
                           "release_date": format!("2026-{:02}-{:02}", 1 + n / 28, 1 + n % 28)})
                })
                .collect();
            app.fetch.answer(
                &albums_url(page * 10),
                200,
                json!({"items": albums, "next": "https://api.spotify.com/next"}).to_string(),
            );
        }
        for n in 0..110 {
            let album = format!("2FakeAlbumN{n:011}");
            app.fetch.answer(
                &url(&app, Read::Album(&album)),
                200,
                json!({"name": format!("Plate {n}"), "artists": [{"name": "Ola Fjell"}],
                       "tracks": {"items": [{"type": "track", "id": format!("1FakeTrackN{n:011}"),
                           "name": format!("Spor {n}"), "artists": [{"name": "Ola Fjell"}]}],
                           "next": null}})
                .to_string(),
            );
        }
        app.fetch.clear_calls();
        let report = sweep::check_entry(&state, id, t0() + TimeDelta::hours(25))
            .await
            .unwrap()
            .unwrap();
        assert_eq!(report.error, None);
        assert_eq!(
            calls_to(&app, &albums_url(10)),
            1,
            "the second page at offset 10"
        );
        assert_eq!(calls_to(&app, &albums_url(90)), 1);
        assert_eq!(calls_to(&app, &albums_url(100)), 0, "at most 10 pages");
        let list = items(&app, id).await;
        assert_eq!(list.len(), spotify::MAX_ARTIST_ALBUMS);
        assert_eq!(
            list[0].key, "1FakeTrackN00000000099",
            "the newest album first"
        );
        assert_eq!(list[19].key, "1FakeTrackN00000000080");
        let row = listing(&app, id).await;
        assert_eq!(row.spotify_mode.as_deref(), Some("list"));
        assert!(row.capped);
    }

    /// qa-26c-step2a-code L2: a first fill with nothing playable is `no_items` (the card's
    /// sentence); the metadata read moves to the next reader; a later page failing keeps the last
    /// good list; a track repeated on a later page is listed once, at its first place.
    #[tokio::test]
    async fn first_fills_readers_pages_and_repeats() {
        let app = TestApp::new().await;
        app.state.music_sweep.set_art_per_check(0);
        save_app(&app, LOOPBACK_REDIRECT).await;
        let kid = store_account(&app, "fakekid", "Ella", "FAKE-REFRESH-1").await;
        let parent = store_account(&app, "fakeparent", "Forelder", "FAKE-REFRESH-9").await;
        app.fetch
            .answer_once(TOKEN_URL, 200, token("FAKE-ACCESS-KID"));
        app.fetch
            .answer(TOKEN_URL, 200, token("FAKE-ACCESS-PARENT"));
        let _ = kid;

        // A track entry whose one track isn't playable here: `no_items`, no list.
        let track = entry(&app, "track", "1FakeTrackH00000000008").await;
        let mut unplayable: Value = serde_json::from_slice(&fixture("track.json")).unwrap();
        unplayable["is_playable"] = json!(false);
        app.fetch.answer(
            &url(&app, Read::Track("1FakeTrackH00000000008")),
            200,
            unplayable.to_string(),
        );
        let report = check(&app, track, t0()).await;
        assert_eq!(report.error.as_deref(), Some("no_items"));
        let row = listing(&app, track).await;
        assert_eq!((row.version, row.failures), (None, 1));
        assert_eq!(
            crate::music::CheckError::Spotify("no_items".to_string()).message(),
            "Nothing in it can be played here."
        );
        assert_eq!(
            crate::music::sweep_error_text("spotify", "no_items"),
            "nothing in it can be played here"
        );

        // An album the kid may not see (404) and the parent may: read by the parent.
        let album = entry(&app, "album", ALBUM_X).await;
        let album_url = url(&app, Read::Album(ALBUM_X));
        app.fetch.answer_once(&album_url, 404, "");
        app.fetch.answer(&album_url, 200, fixture("album.json"));
        app.fetch.answer(
            &url(&app, Read::AlbumTracks(ALBUM_X, 50)),
            200,
            fixture("album_tracks.json"),
        );
        app.fetch.clear_calls();
        assert_eq!(check(&app, album, t0()).await.error, None);
        assert_eq!(
            bearers(&app, &album_url),
            ["Bearer FAKE-ACCESS-KID", "Bearer FAKE-ACCESS-PARENT"]
        );
        assert_eq!(listing(&app, album).await.spotify_reader, Some(parent));

        // A playlist over two pages, track 0 repeated on the second: once, at its first place.
        let id = entry(&app, "playlist", PLAYLIST).await;
        app.fetch.answer(
            &url(&app, Read::Playlist(PLAYLIST)),
            200,
            fixture("playlist.json"),
        );
        let page = |numbers: &[usize], next: bool| {
            let items: Vec<Value> = numbers
                .iter()
                .map(|n| json!({"is_local": false, "item": synthetic_track(*n)}))
                .collect();
            json!({"items": items, "next": next.then_some("https://api.spotify.com/next")})
                .to_string()
        };
        let first: Vec<usize> = (0..50).collect();
        app.fetch.answer(
            &url(&app, Read::PlaylistItems(PLAYLIST, 0)),
            200,
            page(&first, true),
        );
        let second_url = url(&app, Read::PlaylistItems(PLAYLIST, 50));
        app.fetch
            .answer(&second_url, 200, page(&[0, 50, 51], false));
        assert_eq!(check(&app, id, t0()).await.error, None);
        let list = items(&app, id).await;
        assert_eq!(list.len(), 52);
        assert_eq!(list[0].key, "1FakeTrackN00000000000");
        assert_eq!(list[50].key, "1FakeTrackN00000000050");
        let good = listing(&app, id).await;

        // The second page fails next time: the check fails, the list stays.
        app.fetch.answer(&second_url, 503, "");
        let now = t0() + TimeDelta::hours(1);
        sweep::request_check(&app.db, id, now).await.unwrap();
        let report = check(&app, id, now).await;
        assert_eq!(report.error.as_deref(), Some("http_503"));
        let row = listing(&app, id).await;
        assert_eq!(row.version, good.version);
        assert_eq!(row.spotify_mode.as_deref(), Some("list"));
        assert_eq!(items(&app, id).await, list);
    }

    /// `QUOTA_EXCEEDED` (§2.4, QA L13): the check fails `quota`, and every Spotify check after
    /// it for 60 minutes fails `quota` without a request - the phones' play calls draw on the
    /// same developer account; the pass's other checks go on.
    #[tokio::test]
    async fn the_quota_ends_the_passs_spotify_checks() {
        let app = TestApp::new().await;
        ready(&app).await;
        app.state
            .music_sweep
            .set_limits(250, std::time::Duration::ZERO);
        app.state.music_sweep.set_clock(Some(t0()));
        let first = entry(&app, "playlist", PLAYLIST).await;
        let second = entry(&app, "album", ALBUM_X).await;
        let feed = super::super::music_sweep::entry(
            &app,
            "https://example.org/q.rss",
            "Q",
            "auto",
            "2026-10-09 10:00:00",
        )
        .await;
        app.fetch.answer(
            "https://example.org/q.rss",
            200,
            "<?xml version=\"1.0\"?><rss><channel><title>Q</title><item><title>1</title>\
             <guid>1</guid><enclosure url=\"https://example.org/1.mp3\"/></item></channel></rss>",
        );
        let meta_url = url(&app, Read::Playlist(PLAYLIST));
        let album_url = url(&app, Read::Album(ALBUM_X));
        app.fetch.answer(&meta_url, 429, fixture("quota.json"));
        app.fetch.answer(&album_url, 429, fixture("quota.json"));
        assert!(sweep::run_pass(&app.state).await);
        let asked = calls_to(&app, &meta_url) + calls_to(&app, &album_url);
        assert_eq!(asked, 1, "one Spotify read, then none");
        for id in [first, second] {
            let row = listing(&app, id).await;
            assert_eq!(row.error.as_deref(), Some("quota"));
        }
        assert!(listing(&app, feed).await.version.is_some(), "RSS goes on");
        assert_eq!(
            crate::music::sweep_error_text("spotify", "quota"),
            "Spotify's limit for this app was reached - it tries again later"
        );
        // An hour later Spotify is asked again.
        app.state.spotify.advance(spotify::QUOTA_PAUSE);
        answer_playlist(&app);
        app.fetch.clear_calls();
        let report = check(&app, first, t0() + TimeDelta::hours(2)).await;
        assert_eq!(report.error, None);
    }

    /// The cadence by kind (§3.4): a playlist and a show at the setting, an album, track, episode
    /// and artist daily; an entry no phone has daily.
    #[test]
    fn the_interval_follows_the_kind() {
        let link = |kind| Link::Spotify {
            kind,
            id: "x".to_string(),
        };
        for setting in [1, 6, 24] {
            for kind in [LinkKind::Playlist, LinkKind::Show] {
                assert_eq!(
                    sweep::interval(&link(kind), true, setting),
                    TimeDelta::hours(setting)
                );
            }
            for kind in [
                LinkKind::Album,
                LinkKind::Track,
                LinkKind::Episode,
                LinkKind::Artist,
            ] {
                assert_eq!(
                    sweep::interval(&link(kind), true, setting),
                    TimeDelta::hours(24)
                );
            }
            assert_eq!(
                sweep::interval(&link(LinkKind::Playlist), false, setting),
                TimeDelta::hours(24)
            );
        }
    }

    /// No picture budget since 2026-10-19 (the user: all pictures ASAP): a check of any source
    /// may fetch them all; the tests' knob caps it.
    #[test]
    fn no_source_has_a_picture_budget() {
        let s = sweep::Sweep::default();
        assert_eq!(s.art_per_check(), u32::MAX);
        s.set_art_per_check(7);
        assert_eq!(s.art_per_check(), 7);
    }

    /// Art (§3.5): a playlist's track pictures are each its own (never the playlist's mosaic,
    /// never `same`), one request per URL in a pass - a picture that failed is not asked twice;
    /// a show's episode pictures are judged against the show's picture.
    #[tokio::test]
    async fn track_art_is_own_and_each_url_is_asked_once() {
        let app = TestApp::new().await;
        ready(&app).await;
        app.state.music_sweep.set_art_per_check(20);
        let id = entry(&app, "playlist", PLAYLIST).await;
        answer_playlist(&app);
        app.fetch.answer(ART_X, 200, png(0b1000_0001, 300, 300));
        app.fetch.answer(ART_Y, 200, png(0b0011_0110, 640, 640));
        check(&app, id, t0()).await;
        assert_eq!(calls_to(&app, ART_X), 1, "two tracks, one album picture");
        let list = items(&app, id).await;
        assert_eq!(
            list.iter()
                .map(|i| i.art_state.as_deref())
                .collect::<Vec<_>>(),
            [Some("own"); 3],
            "the album picture equals the mosaic here, and is still the track's own"
        );
        assert_eq!(list[0].art_hash, list[1].art_hash);
        assert_ne!(list[0].art_hash, list[2].art_hash);

        // A picture that fails: asked once for both tracks.
        let other = entry(&app, "album", ALBUM_X).await;
        let _ = other;
        let id2 = {
            let target_id = "0FakePlaylistOther0002";
            let id2 = entry(&app, "playlist", target_id).await;
            app.fetch.answer(
                &url(&app, Read::Playlist(target_id)),
                200,
                fixture("playlist.json"),
            );
            let mut page: Value = serde_json::from_slice(&fixture("playlist_items.json")).unwrap();
            for item in page["items"].as_array_mut().unwrap() {
                if let Some(images) = item["item"]["album"]["images"].as_array_mut() {
                    for image in images {
                        image["url"] = json!("https://i.scdn.co/image/fake-broken");
                    }
                }
            }
            app.fetch.answer(
                &url(&app, Read::PlaylistItems(target_id, 0)),
                200,
                page.to_string(),
            );
            id2
        };
        app.fetch
            .answer("https://i.scdn.co/image/fake-broken", 404, "");
        check(&app, id2, t0()).await;
        assert_eq!(calls_to(&app, "https://i.scdn.co/image/fake-broken"), 1);
        let list = items(&app, id2).await;
        assert!(
            list.iter()
                .all(|i| i.art_state.as_deref() == Some("failed") && i.art_fails == 1)
        );

        // A show: the episode whose picture is the show's own URL is `same`, no request.
        let show = entry(&app, "show", SHOW).await;
        app.fetch
            .answer(&url(&app, Read::Show(SHOW)), 200, fixture("show.json"));
        app.fetch.answer(
            &url(&app, Read::ShowEpisodes(SHOW, 0)),
            200,
            fixture("show_episodes.json"),
        );
        let show_picture = "https://i.scdn.co/image/fake-show-300";
        app.fetch
            .answer(show_picture, 200, png(0b0101_0101, 300, 300));
        app.fetch.answer(
            "https://i.scdn.co/image/fake-episode-3",
            200,
            png(0b1111_0000, 640, 640),
        );
        check(&app, show, t0()).await;
        assert_eq!(calls_to(&app, show_picture), 1, "the cover only");
        let list = items(&app, show).await;
        assert_eq!(
            list.iter()
                .map(|i| i.art_state.as_deref())
                .collect::<Vec<_>>(),
            [Some("same"), Some("own")]
        );
        // Spotify items take part in the art pass although they have no URL.
        assert!(sweep::art_candidate(
            &list[1],
            &Link::Spotify {
                kind: LinkKind::Show,
                id: SHOW.to_string()
            }
        ));
    }

    /// The acceptance's third line: a restore that forgets the pictures restamps a Spotify
    /// listing like the others, and the route serves the restamped version; a context listing
    /// restamps to itself.
    #[tokio::test]
    async fn a_restore_restamps_a_spotify_listing() {
        let app = TestApp::new().await;
        let kid = ready(&app).await;
        app.state.music_sweep.set_art_per_check(20);
        let id = entry(&app, "playlist", PLAYLIST).await;
        answer_playlist(&app);
        app.fetch.answer(ART_X, 200, png(0b1000_0001, 300, 300));
        app.fetch.answer(ART_Y, 200, png(0b0011_0110, 640, 640));
        check(&app, id, t0()).await;
        let artist = entry(&app, "artist", ARTIST).await;
        app.fetch.answer(
            &url(&app, Read::Artist(ARTIST)),
            200,
            fixture("artist.json"),
        );
        check(&app, artist, t0()).await;
        let context = listing(&app, artist).await.version.unwrap();
        let version = listing(&app, id).await.version.unwrap();
        let hashes: Vec<String> = items(&app, id)
            .await
            .iter()
            .filter_map(|i| i.art_hash.clone())
            .collect();
        assert_eq!(hashes.len(), 3);
        for hash in &hashes {
            let _ = std::fs::remove_file(app.state.music_art_dir.join(format!("{hash}.jpg")));
        }
        let backups = tempfile::tempdir().unwrap();
        crate::photos::recover_missing(&app.state, backups.path()).await;
        let restamped = listing(&app, id).await.version.unwrap();
        assert_ne!(restamped, version);
        assert!(items(&app, id).await.iter().all(|i| i.art_hash.is_none()));
        assert_eq!(listing(&app, artist).await.version.unwrap(), context);
        let (phone, token) = app.enrolled_device("Ella").await;
        tick_on(&app, phone, id).await;
        tick_on(&app, phone, artist).await;
        bind(&app, phone, kid).await;
        assert_eq!(
            served(&app, &token, id).await.1["version"],
            restamped.as_str()
        );
        assert_eq!(
            served(&app, &token, artist).await.1["version"],
            context.as_str()
        );
        // Nothing left to restamp.
        assert_eq!(sweep::restamp_versions(&app.db).await.unwrap(), 0);
    }

    /// A phone's report never flags a Spotify item (§3.4: nothing to re-resolve; the phone's
    /// `unplayable` is its own).
    #[tokio::test]
    async fn reports_never_flag_spotify_items() {
        let app = TestApp::new().await;
        let kid = ready(&app).await;
        let id = entry(&app, "playlist", PLAYLIST).await;
        answer_playlist(&app);
        check(&app, id, t0()).await;
        let (phone, _) = app.enrolled_device("Ella").await;
        tick_on(&app, phone, id).await;
        bind(&app, phone, kid).await;
        let version = listing(&app, id).await.version.unwrap();
        let report = crate::music::MusicState {
            item_errors: vec![crate::music::ItemError {
                entry: id,
                version,
                item: TRACK_A.to_string(),
                error: "http_404".to_string(),
            }],
            ..Default::default()
        };
        assert_eq!(
            sweep::flag_reports(&app.db, phone, &report, t0())
                .await
                .unwrap(),
            0
        );
    }
}

/// Step 2b of design 26c (§3.2, §3.6, §2.5, §10's add/ticks/binding/cards/gate/import rows): the
/// add form with its check and per-phone ticks, the library gate on the library, cover and art
/// routes, the library cards and the entry page, Check now, the device card's "Spotify plays as",
/// and the entries a new login or app save makes due.
mod entries {
    use std::time::Duration;

    use axum::http::{Method, StatusCode};
    use chrono::TimeDelta;
    use serde_json::{Value, json};

    use super::super::music::{device_get, events, nudged, tick};
    use super::super::music_sweep::{check, items, lib_entry, listing, t0, tick_on};
    use super::super::{TestApp, TestResponse};
    use super::listing::{ALBUM_X, PLAYLIST, SHOW, answer_playlist, entry, ready, url};
    use super::{bind, binding, revision};
    use crate::music_sweep::{self as sweep, stamp};
    use crate::spotify::tests::{fixture, save_app, store_account};
    use crate::spotify::{LOOPBACK_REDIRECT, LinkKind, Read};

    fn page_text(res: &TestResponse) -> String {
        res.text().replace("&#39;", "'").replace("&quot;", "\"")
    }

    async fn add(app: &TestApp, cookie: &str, fields: &[(&str, &str)]) -> TestResponse {
        app.request_form(Method::POST, "/music/spotify/add", Some(cookie), fields)
            .await
    }

    /// The Music page's "From Spotify ›" row (design 31 §3.4's Add rows): its link and value.
    async fn from_spotify(app: &TestApp, cookie: &str) -> String {
        let page = app.get_page("/music", cookie).await.text();
        let row = &page[page.find("data-row=\"from-spotify\"").expect("the row")..];
        assert!(page.contains(
            "<a class=\"row\" href=\"/music/spotify\" id=\"spotify\" data-row=\"from-spotify\">"
        ));
        let value = &row[row.find("class=\"row-value\">").unwrap() + 18..];
        value[..value.find('<').unwrap()].to_string()
    }

    async fn entry_count(app: &TestApp) -> i64 {
        sqlx::query_scalar("SELECT COUNT(*) FROM music_entries")
            .fetch_one(&app.db)
            .await
            .unwrap()
    }

    async fn ticks(app: &TestApp, entry: i64) -> Vec<i64> {
        sqlx::query_scalar(
            "SELECT device_id FROM device_music_entries WHERE entry_id = ? ORDER BY device_id",
        )
        .bind(entry)
        .fetch_all(&app.db)
        .await
        .unwrap()
    }

    /// A phone's library: whether it has `entry`, and the entry as served.
    async fn in_library(app: &TestApp, token: &str, entry: i64) -> Option<Value> {
        let res = device_get(app, "/api/devices/music/library", token, &[]).await;
        if res.status != StatusCode::OK {
            return None;
        }
        res.json()["entries"]
            .as_array()
            .unwrap()
            .iter()
            .find(|e| e["id"] == entry)
            .cloned()
    }

    async fn report(app: &TestApp, device: i64, state: Value) {
        sqlx::query(
            "INSERT INTO device_status (device_id, lock_reason, kiosk_engaged, music_state_json, \
             reported_at) VALUES (?, 'NONE', 1, ?, datetime('now'))",
        )
        .bind(device)
        .bind(state.to_string())
        .execute(&app.db)
        .await
        .unwrap();
    }

    /// The acceptance's add: a share link (`open.spotify.com` with `?si=`) is read with the kid's
    /// account (the playlist's metadata, then its first items page), stored as a Spotify entry
    /// (Spotify's name, Musikk by its kind, order auto, cache 0, resume on, vibb's key) and ticked
    /// on the picked phone that plays as an account - the other picked phone is named, not
    /// ticked. The sweep is woken, the ticked phone nudged, the event written; the page after it
    /// says so at `#added`.
    #[tokio::test]
    async fn a_link_is_added_and_ticked_on_the_phones_that_play_spotify() {
        let app = TestApp::new().await;
        let cookie = app.admin_cookie().await;
        let kid = ready(&app).await;
        let (ella, ella_token) = app.enrolled_device("Ella").await;
        let (ola, ola_token) = app.enrolled_device("Ola").await;
        bind(&app, ella, kid).await;
        answer_playlist(&app);
        let mut nudges = app.state.command_notify.subscribe();
        let ella_id = ella.to_string();
        let ola_id = ola.to_string();
        let link = format!("https://open.spotify.com/playlist/{PLAYLIST}?si=abc123");
        let res = add(
            &app,
            &cookie,
            &[
                ("link", link.as_str()),
                ("category", ""),
                ("device", ella_id.as_str()),
                ("device", ola_id.as_str()),
            ],
        )
        .await;
        assert_eq!(res.status, StatusCode::SEE_OTHER, "{}", page_text(&res));
        let row: (i64, String, i64, String, String, String, i64, bool) = sqlx::query_as(
            "SELECT id, name, category_id, source, target, play_order, cache, resume \
             FROM music_entries",
        )
        .fetch_one(&app.db)
        .await
        .unwrap();
        let id = row.0;
        let target = format!("spotify:playlist:{PLAYLIST}");
        assert_eq!(
            row,
            (
                id,
                "Kveldsmusikk".to_string(),
                1,
                "spotify".to_string(),
                target.clone(),
                "auto".to_string(),
                0,
                true
            )
        );
        let key: String = sqlx::query_scalar("SELECT key FROM music_entries WHERE id = ?")
            .bind(id)
            .fetch_one(&app.db)
            .await
            .unwrap();
        assert_eq!(key, crate::music::state_key(&target));
        assert_eq!(
            res.location(),
            Some(format!("/music/spotify?added={id}&on={ella}&off={ola}#added").as_str())
        );
        assert_eq!(ticks(&app, id).await, [ella]);
        // The add check: the token, the metadata, the first items page (with the kid's account).
        let calls = app.fetch.calls.lock().unwrap().clone();
        assert_eq!(
            calls,
            [
                crate::spotify::tests::TOKEN_URL.to_string(),
                url(&app, Read::Playlist(PLAYLIST)),
                url(&app, Read::PlaylistItems(PLAYLIST, 0)),
            ]
        );
        assert!(app.state.music_sweep.woken().await);
        assert_eq!(nudged(&mut nudges), [ella]);
        let added = events(&app, "music_entry_added").await;
        assert_eq!(added.len(), 1);
        assert!(added[0].contains("Spotify: Kveldsmusikk"), "{added:?}");
        // The page after it: what happened, at `#added`.
        let page = page_text(
            &app.get_page(res.location().unwrap().trim_end_matches("#added"), &cookie)
                .await,
        );
        assert!(page.contains("id=\"added\""), "{page}");
        assert!(page.contains("On Ella within a few minutes."), "{page}");
        assert!(page.contains("Ola: not ticked - Spotify is off on this phone"));
        assert!(!page.contains("No logged-in account owns this playlist"));

        // Before its first check the phone hides it: no mode, no items (§3.6).
        let served = in_library(&app, &ella_token, id).await.unwrap();
        assert!(served.get("spotify").is_none(), "{served}");
        assert_eq!(served["items"], Value::Null);
        assert_eq!(
            (served["cache"].as_i64(), served["lan"].as_bool()),
            (Some(0), Some(false))
        );
        // The first check lists it: the library says `list` and the listing's version.
        assert_eq!(check(&app, id, t0()).await.error, None);
        let served = in_library(&app, &ella_token, id).await.unwrap();
        assert_eq!(served["spotify"], "list");
        assert_eq!(
            served["items"].as_str(),
            listing(&app, id).await.version.as_deref()
        );
        assert_eq!(served["source"], "spotify");
        // Ola, ticked on the device card but not playing as an account: nothing (the gate).
        assert_eq!(
            tick(&app, &cookie, ola, id, true).await.status,
            StatusCode::SEE_OTHER
        );
        assert_eq!(ticks(&app, id).await, [ella, ola]);
        assert_eq!(in_library(&app, &ola_token, id).await, None);
        bind(&app, ola, kid).await;
        assert_eq!(
            in_library(&app, &ola_token, id).await.unwrap()["spotify"],
            "list"
        );
    }

    /// §3.2's refusals: each a 400 by the field with the values kept and nothing stored, and no
    /// request where none is needed.
    #[tokio::test]
    async fn the_add_form_refuses_with_nothing_stored() {
        let app = TestApp::new().await;
        let cookie = app.admin_cookie().await;
        let link = format!("https://open.spotify.com/playlist/{PLAYLIST}");
        let refused = |res: TestResponse, text: &str| {
            assert_eq!(res.status, StatusCode::BAD_REQUEST, "{text}");
            let page = page_text(&res);
            assert!(page.contains(text), "{text}: {page}");
            page
        };
        // Design 47 §1.3: while the setup isn't done the add page says what is missing, on top,
        // linking to Music › Settings' group, and the form is disabled below it.
        let not_ready = |page: &str, title: &str, fragment: &str| {
            let row = &page[page
                .find("data-row=\"spotify-not-ready\"")
                .expect("the row")..];
            let start = page.find("data-row=\"spotify-not-ready\"").unwrap();
            let open = page[..start].rfind("<a ").unwrap();
            assert!(
                page[open..start].contains(&format!("href=\"/music/settings#{fragment}\"")),
                "{fragment}"
            );
            assert!(row[..row.find("</a>").unwrap()].contains(title), "{title}");
            assert!(
                page.contains("id=\"spotify_link\"")
                    && page.contains("<button type=\"submit\" class=\"button-wide\" disabled>Add"),
                "the form disabled"
            );
        };
        // No app: a POST is refused.
        let page = refused(
            add(&app, &cookie, &[("link", link.as_str())]).await,
            "Set up your Spotify app first.",
        );
        not_ready(
            &page,
            "No Spotify app is saved - set it up first",
            "spotify",
        );
        let shown = page_text(&app.get_page("/music/spotify", &cookie).await);
        not_ready(
            &shown,
            "No Spotify app is saved - set it up first",
            "spotify",
        );
        assert!(shown.contains("<span class=\"row-value\">Not set up yet</span>"));
        assert_eq!(from_spotify(&app, &cookie).await, "No app");
        save_app(&app, LOOPBACK_REDIRECT).await;
        assert_eq!(from_spotify(&app, &cookie).await, "No login");
        // No account.
        let page = refused(
            add(&app, &cookie, &[("link", link.as_str())]).await,
            "Log in with a Spotify account first.",
        );
        not_ready(&page, "No child's account is logged in yet", "spotify");
        assert!(page.contains("<span class=\"row-value\">App saved, no account yet</span>"));
        assert!(
            page.contains(&format!("value=\"{link}\"")),
            "the link is kept"
        );
        // Every login ended.
        let kid = store_account(&app, "fakekid", "Ella", "FAKE-REFRESH-1").await;
        sqlx::query("UPDATE music_spotify_accounts SET needs_login = 1")
            .execute(&app.db)
            .await
            .unwrap();
        let page = refused(
            add(&app, &cookie, &[("link", link.as_str())]).await,
            "The Spotify logins need renewing",
        );
        not_ready(
            &page,
            "An account needs to log in again",
            "spotify-accounts",
        );
        assert!(page.contains("<span class=\"row-value\">1 account</span>"));
        assert_eq!(from_spotify(&app, &cookie).await, "No login");
        sqlx::query("UPDATE music_spotify_accounts SET needs_login = 0")
            .execute(&app.db)
            .await
            .unwrap();
        assert_eq!(from_spotify(&app, &cookie).await, "Signed in");
        app.fetch.answer(
            crate::spotify::tests::TOKEN_URL,
            200,
            fixture("token_refresh.json"),
        );
        assert!(app.fetch.calls.lock().unwrap().is_empty());
        // Links that aren't one, or can't be played here.
        refused(
            add(&app, &cookie, &[("link", "https://example.org/x")]).await,
            "That isn't a Spotify link",
        );
        refused(
            add(
                &app,
                &cookie,
                &[(
                    "link",
                    "https://open.spotify.com/audiobook/7FakeBook0000000000001",
                )],
            )
            .await,
            "Spotify audiobooks can't be played here.",
        );
        refused(
            add(&app, &cookie, &[("link", "spotify:user:kari:collection")]).await,
            "Liked Songs and Spotify profiles can't be added",
        );
        // A phone that doesn't exist, a category that doesn't.
        refused(
            add(&app, &cookie, &[("link", link.as_str()), ("device", "999")]).await,
            "One of the phones doesn't exist any more",
        );
        refused(
            add(
                &app,
                &cookie,
                &[("link", link.as_str()), ("category", "999")],
            )
            .await,
            "That category doesn't exist any more.",
        );
        assert!(app.fetch.calls.lock().unwrap().is_empty());
        // Spotify won't open it for any account (404), or can't be reached.
        app.fetch
            .answer(&url(&app, Read::Playlist(PLAYLIST)), 404, "{}");
        refused(
            add(&app, &cookie, &[("link", link.as_str())]).await,
            "Spotify doesn't let this app open it - Spotify's own playlists (made by Spotify) \
             aren't available to developer apps, or the link is private or deleted.",
        );
        app.fetch
            .answer(&url(&app, Read::Playlist(PLAYLIST)), 503, "{}");
        refused(
            add(&app, &cookie, &[("link", link.as_str())]).await,
            "Spotify couldn't be reached - try again.",
        );
        // No status code on the form (qa-26c-step2b-code L4): a 401 after the refresh.
        app.fetch
            .answer(&url(&app, Read::Playlist(PLAYLIST)), 401, "{}");
        let page = refused(
            add(&app, &cookie, &[("link", link.as_str())]).await,
            "Spotify couldn't open it just now - try again.",
        );
        assert!(!page.contains("Spotify answered"));
        // The quota: when to try again, not "it tries again later".
        app.fetch.answer(
            &url(&app, Read::Playlist(PLAYLIST)),
            429,
            fixture("quota.json"),
        );
        let page = refused(
            add(&app, &cookie, &[("link", link.as_str())]).await,
            "Spotify's limit for this app was reached - try again in an hour.",
        );
        assert!(!page.contains("tries again later"));
        assert_eq!(entry_count(&app).await, 0);
        // Already in the library: a link to its card.
        let id = entry(&app, "playlist", PLAYLIST).await;
        let page = refused(
            add(
                &app,
                &cookie,
                &[("link", format!("spotify:playlist:{PLAYLIST}").as_str())],
            )
            .await,
            "Already in the library.",
        );
        assert!(
            page.contains(&format!("href=\"/music#entry-{id}\"")),
            "{page}"
        );
        assert_eq!(entry_count(&app).await, 1);
        assert!(events(&app, "music_entry_added").await.is_empty());
        let _ = kid;
    }

    /// A playlist no logged-in account may list (§3.2 step 2): added with the context notice; a
    /// short link is followed hop by hop at add (§3.1) and stored canonical; a show goes to the
    /// podcasts by its kind; an album is named "<album> — <first artist>".
    #[tokio::test]
    async fn context_playlists_short_links_and_kinds() {
        let app = TestApp::new().await;
        let cookie = app.admin_cookie().await;
        ready(&app).await;
        store_account(&app, "fakeparent", "Forelder", "FAKE-REFRESH-2").await;
        app.fetch.answer(
            &url(&app, Read::Playlist(PLAYLIST)),
            200,
            fixture("playlist.json"),
        );
        app.fetch.answer(
            &url(&app, Read::PlaylistItems(PLAYLIST, 0)),
            403,
            fixture("forbidden.json"),
        );
        let res = add(
            &app,
            &cookie,
            &[("link", format!("spotify:playlist:{PLAYLIST}").as_str())],
        )
        .await;
        assert_eq!(res.status, StatusCode::SEE_OTHER, "{}", page_text(&res));
        let location = res.location().unwrap().to_string();
        assert!(location.contains("&context=1"), "{location}");
        // Both accounts were asked for the items.
        assert_eq!(
            app.fetch
                .calls
                .lock()
                .unwrap()
                .iter()
                .filter(|u| **u == url(&app, Read::PlaylistItems(PLAYLIST, 0)))
                .count(),
            2
        );
        let page = page_text(
            &app.get_page(location.trim_end_matches("#added"), &cookie)
                .await,
        );
        assert!(page.contains(
            "No logged-in account owns this playlist or collaborates on it, so the phones can't \
             show its tracks: they play it in Spotify's order. For the track list, make the \
             playlist in your child's Spotify account, or invite your child to collaborate on \
             yours and join from your child's account."
        ));
        assert!(page.contains("Turn it on for a phone on that phone's Music page."));

        // A short link: two hops, then the album.
        app.fetch.redirect(
            "https://spotify.link/AbC123",
            302,
            "https://spotify.app.link/XyZ789?x=1",
        );
        app.fetch.redirect(
            "https://spotify.app.link/XyZ789?x=1",
            307,
            &format!("https://open.spotify.com/album/{ALBUM_X}?si=zz"),
        );
        app.fetch
            .answer(&url(&app, Read::Album(ALBUM_X)), 200, fixture("album.json"));
        let res = add(&app, &cookie, &[("link", "https://spotify.link/AbC123")]).await;
        assert_eq!(res.status, StatusCode::SEE_OTHER, "{}", page_text(&res));
        let (name, category, location): (String, i64, Option<String>) =
            sqlx::query_as("SELECT name, category_id, NULL FROM music_entries WHERE target = ?")
                .bind(format!("spotify:album:{ALBUM_X}"))
                .fetch_one(&app.db)
                .await
                .unwrap();
        assert_eq!(
            (name.as_str(), category, location),
            ("Nattlys — Ola Fjell", 1, None)
        );
        // The same short link again is the same entry.
        let res = add(&app, &cookie, &[("link", "https://spotify.link/AbC123")]).await;
        assert_eq!(res.status, StatusCode::BAD_REQUEST);
        assert!(page_text(&res).contains("Already in the library."));
        // A dead short link.
        app.fetch
            .answer("https://spotify.link/Dead00", 200, "<html></html>");
        let res = add(&app, &cookie, &[("link", "https://spotify.link/Dead00")]).await;
        assert_eq!(res.status, StatusCode::BAD_REQUEST);
        assert!(page_text(&res).contains("That Spotify link didn't lead anywhere."));

        // A show: Podkast by its kind.
        app.fetch
            .answer(&url(&app, Read::Show(SHOW)), 200, fixture("show.json"));
        let res = add(
            &app,
            &cookie,
            &[(
                "link",
                format!("https://open.spotify.com/show/{SHOW}").as_str(),
            )],
        )
        .await;
        assert_eq!(res.status, StatusCode::SEE_OTHER, "{}", page_text(&res));
        let category: i64 =
            sqlx::query_scalar("SELECT category_id FROM music_entries WHERE target = ?")
                .bind(format!("spotify:show:{SHOW}"))
                .fetch_one(&app.db)
                .await
                .unwrap();
        assert_eq!(category, 4, "a show goes to Podkast");
        // A picked category wins over the kind.
        let other_show = "4FakeShow0000000000002";
        app.fetch.answer(
            &url(&app, Read::Show(other_show)),
            200,
            fixture("show.json"),
        );
        let res = add(
            &app,
            &cookie,
            &[
                ("link", format!("spotify:show:{other_show}").as_str()),
                ("category", "2"),
            ],
        )
        .await;
        assert_eq!(res.status, StatusCode::SEE_OTHER, "{}", page_text(&res));
        let category: i64 =
            sqlx::query_scalar("SELECT category_id FROM music_entries WHERE target = ?")
                .bind(format!("spotify:show:{other_show}"))
                .fetch_one(&app.db)
                .await
                .unwrap();
        assert_eq!(category, 2);
        assert_eq!(entry_count(&app).await, 4);

        // A playlist Spotify gives no name (blank): named by its kind's word, never the source's -
        // the name is the kid's tile and lock screen (design 26c step 8, qa-26c-step8-code L2).
        const NAMELESS: &str = "0FakePlaylistBlank001";
        let meta = String::from_utf8(fixture("playlist.json"))
            .unwrap()
            .replace("\"Kveldsmusikk\"", "\"  \"");
        assert!(!meta.contains("Kveldsmusikk"));
        app.fetch
            .answer(&url(&app, Read::Playlist(NAMELESS)), 200, meta);
        app.fetch.answer(
            &url(&app, Read::PlaylistItems(NAMELESS, 0)),
            403,
            fixture("forbidden.json"),
        );
        let res = add(
            &app,
            &cookie,
            &[("link", format!("spotify:playlist:{NAMELESS}").as_str())],
        )
        .await;
        assert_eq!(res.status, StatusCode::SEE_OTHER, "{}", page_text(&res));
        let name: String = sqlx::query_scalar("SELECT name FROM music_entries WHERE target = ?")
            .bind(format!("spotify:playlist:{NAMELESS}"))
            .fetch_one(&app.db)
            .await
            .unwrap();
        assert_eq!(name, "Playlist");
        assert!(!name.to_lowercase().contains("spotify"));
        for kind in [
            LinkKind::Playlist,
            LinkKind::Album,
            LinkKind::Track,
            LinkKind::Artist,
            LinkKind::Show,
            LinkKind::Episode,
        ] {
            assert!(
                !kind.plain_name().to_lowercase().contains("spotify"),
                "{kind:?}"
            );
        }
    }

    /// The library's gate (§3.6) on the library, the listing, the cover and the art routes: a
    /// bound phone gets the entry, its listing cover, the parent's cover and a track's art; an
    /// unbound one none of them; unbinding drops them, a login that needs renewing doesn't.
    #[tokio::test]
    async fn the_gate_holds_on_library_covers_and_art() {
        let app = TestApp::new().await;
        let kid = ready(&app).await;
        // The tracks' pictures are fetched (`ready` turns the art pass off).
        app.state.music_sweep.set_art_per_check(20);
        let (ella, ella_token) = app.enrolled_device("Ella").await;
        let (ola, ola_token) = app.enrolled_device("Ola").await;
        let id = entry(&app, "playlist", PLAYLIST).await;
        tick_on(&app, ella, id).await;
        tick_on(&app, ola, id).await;
        bind(&app, ella, kid).await;
        answer_playlist(&app);
        app.fetch.answer(
            super::listing::ART_X,
            200,
            super::super::music_art::png(0b0110_0110, 300, 300),
        );
        app.fetch.answer(
            super::listing::ART_Y,
            200,
            super::super::music_art::png(0b1010_0101, 640, 640),
        );
        assert_eq!(check(&app, id, t0()).await.error, None);
        let cover = listing(&app, id).await.cover_hash.unwrap();
        let art = items(&app, id)
            .await
            .into_iter()
            .find_map(|i| i.art_hash)
            .expect("a track's picture");
        let has = |token: String| {
            let app = &app;
            let cover = cover.clone();
            let art = art.clone();
            async move {
                let library = in_library(app, &token, id).await.is_some();
                let listing = device_get(
                    app,
                    &format!("/api/devices/music/entries/{id}/items"),
                    &token,
                    &[],
                )
                .await
                .status;
                let cover = device_get(
                    app,
                    &format!("/api/devices/music/covers/{cover}"),
                    &token,
                    &[],
                )
                .await
                .status;
                let art = device_get(
                    app,
                    &format!("/api/devices/music/covers/{art}"),
                    &token,
                    &[],
                )
                .await
                .status;
                (library, listing, cover, art)
            }
        };
        let ok = (true, StatusCode::OK, StatusCode::OK, StatusCode::OK);
        let none = (
            false,
            StatusCode::NOT_FOUND,
            StatusCode::NOT_FOUND,
            StatusCode::NOT_FOUND,
        );
        assert_eq!(has(ella_token.clone()).await, ok);
        assert_eq!(has(ola_token.clone()).await, none);
        // The parent's own cover (`cover_in_library`'s first branch) follows the gate too.
        sqlx::query("UPDATE music_entries SET cover_hash = ? WHERE id = ?")
            .bind(&cover)
            .bind(id)
            .execute(&app.db)
            .await
            .unwrap();
        assert_eq!(has(ella_token.clone()).await, ok);
        assert_eq!(has(ola_token.clone()).await, none);
        // A login that needs renewing keeps the tiles (§3.6).
        sqlx::query("UPDATE music_spotify_accounts SET needs_login = 1")
            .execute(&app.db)
            .await
            .unwrap();
        assert_eq!(has(ella_token.clone()).await, ok);
        // Unbinding drops it (the revision moves: the cached library is rebuilt).
        let before = revision(&app).await;
        sqlx::query("UPDATE device_policy SET music_spotify_account = NULL WHERE device_id = ?")
            .bind(ella)
            .execute(&app.db)
            .await
            .unwrap();
        assert!(revision(&app).await > before);
        assert_eq!(has(ella_token.clone()).await, none);
        assert_eq!(binding(&app, ella).await, None);
    }

    /// The library cards (§3.6): the kind, "N tracks · read as <account>", what was left out,
    /// per-phone lines ("plays as", "not sent - Spotify is off on this phone", `unplayable`), "on N
    /// phones" counting only the bound, the link back to Spotify, no offline select; context
    /// mode's notice; no app: every phone gated. Check now on the card lists the playlist (the
    /// acceptance), `/music/cards` serves its status, the offline save stores 0.
    #[tokio::test]
    async fn the_cards_and_check_now() {
        let app = TestApp::new().await;
        let cookie = app.admin_cookie().await;
        let kid = ready(&app).await;
        let (ella, ella_token) = app.enrolled_device("Ella").await;
        let (ola, _) = app.enrolled_device("Ola").await;
        let id = entry(&app, "playlist", PLAYLIST).await;
        tick_on(&app, ella, id).await;
        tick_on(&app, ola, id).await;
        bind(&app, ella, kid).await;
        answer_playlist(&app);
        let music = || {
            let cookie = cookie.clone();
            let app = &app;
            async move { page_text(&app.get_page("/music", &cookie).await) }
        };
        // The entry's page holds the card's details and the phones' lines (design 31 step 12).
        let entry_page = || {
            let cookie = cookie.clone();
            let app = &app;
            async move { page_text(&app.get_page(&format!("/music/entries/{id}"), &cookie).await) }
        };
        // Not listed yet: waiting for the first check; Check now asks for it.
        let page = music().await;
        assert!(page.contains("Spotify playlist"), "{page}");
        assert!(page.contains("Waiting for the first check"), "{page}");
        let res = app
            .request_form(
                Method::POST,
                &format!("/music/entries/{id}/check"),
                Some(&cookie),
                &[],
            )
            .await;
        assert_eq!(res.status, StatusCode::SEE_OTHER);
        assert_eq!(res.location(), Some(format!("/music#entry-{id}").as_str()));
        assert!(listing(&app, id).await.requested_at.is_some());
        assert_eq!(events(&app, "music_check_requested").await.len(), 1);
        // The sweep's next pass takes it: the playlist is listed.
        let now = app.state.music_sweep.now();
        let due = sweep::due(&app.db, now).await.unwrap();
        assert_eq!(
            due.first().map(|d| (d.entry_id, d.why)),
            Some((id, sweep::Why::CheckNow))
        );
        assert_eq!(check(&app, id, now).await.error, None);
        assert_eq!(items(&app, id).await.len(), 3);
        report(
            &app,
            ella,
            json!({"item_errors": [{"entry": id, "version": listing(&app, id).await.version.unwrap(),
                     "item": super::listing::TRACK_A, "error": "unplayable"}]}),
        )
        .await;
        // The library row: the count and the phones the gate lets through, the link back, no
        // offline select.
        let page = music().await;
        assert!(
            page.contains("Spotify playlist · 3 tracks · 1 phone"),
            "{page}"
        );
        assert!(page.contains(&format!(
            "href=\"https://open.spotify.com/playlist/{PLAYLIST}\" rel=\"noopener noreferrer\""
        )));
        assert!(!page.contains(&format!("/music/entries/{id}/offline")));
        // The entry page: the line with the reader, what was left out, each phone's line.
        let page = entry_page().await;
        assert!(page.contains("3 tracks · read as Ella"), "{page}");
        assert!(page.contains("4 left out: not playable here, local files or podcast episodes"));
        assert_eq!(
            super::super::music::phone_line(&page, "Ella").as_deref(),
            Some("Plays as Ella · 1 failing (Spotify couldn't play this track on the phone)")
        );
        assert_eq!(
            super::super::music::phone_line(&page, "Ola").as_deref(),
            Some("Not sent - Spotify is off on this phone")
        );
        // The in-place status.
        let res = app
            .get_page(&format!("/music/cards?ids={id}"), &cookie)
            .await;
        assert_eq!(res.status, StatusCode::OK);
        assert_eq!(res.json()["cards"][0]["id"], id);
        assert!(
            res.json()["cards"][0]["html"]
                .as_str()
                .unwrap()
                .contains("Checked")
        );
        // The offline save stores 0, whatever is posted.
        let res = app
            .request_form(
                Method::POST,
                &format!("/music/entries/{id}/offline"),
                Some(&cookie),
                &[("cache", "5")],
            )
            .await;
        assert_eq!(res.status, StatusCode::SEE_OTHER);
        let cache: i64 = sqlx::query_scalar("SELECT cache FROM music_entries WHERE id = ?")
            .bind(id)
            .fetch_one(&app.db)
            .await
            .unwrap();
        assert_eq!(cache, 0);

        // Context mode: its notice and no track count.
        app.fetch.answer(
            &url(&app, Read::PlaylistItems(PLAYLIST, 0)),
            403,
            fixture("forbidden.json"),
        );
        let later = now + TimeDelta::hours(1);
        sweep::request_check(&app.db, id, later).await.unwrap();
        assert_eq!(check(&app, id, later).await.error, None);
        let page = entry_page().await;
        assert!(
            page.contains("Spotify picks the order · read as Ella"),
            "{page}"
        );
        assert!(page.contains("No logged-in account owns this playlist or collaborates on it"));
        // The row: no track count in context mode.
        assert!(music().await.contains("Spotify playlist · 1 phone"));
        assert!(page.contains(
            "make the playlist in your child's Spotify account, or invite your child to \
             collaborate on yours and join from your child's account."
        ));
        assert!(!page.contains("Log in as its owner"));
        // No app (an environment client ID gone after a restart, bindings left): the card says
        // what the gate does (qa-26c-step2b-code L1) - the bound phone still gets the entry and
        // its line says it can't play; the unbound one isn't sent it.
        sqlx::query("DELETE FROM music_spotify_app")
            .execute(&app.db)
            .await
            .unwrap();
        assert!(in_library(&app, &ella_token, id).await.is_some());
        let page = entry_page().await;
        assert_eq!(
            super::super::music::phone_line(&page, "Ella").as_deref(),
            Some("Plays as Ella - no Spotify app is set up, so it can't play")
        );
        assert_eq!(
            super::super::music::phone_line(&page, "Ola").as_deref(),
            Some("Not sent - no Spotify app is set up")
        );
        assert!(music().await.contains("Spotify playlist · 1 phone"));
    }

    /// The entry page's list header per source (design 31 step 12, qa-26c-merge2-code M1): a
    /// Storytel series' "Books", a Spotify playlist's "Tracks", a Spotify show's and an NRK
    /// series' "Episodes".
    #[tokio::test]
    async fn the_entry_pages_list_header_follows_the_source() {
        let app = TestApp::new().await;
        let cookie = app.admin_cookie().await;
        let other = |source: &'static str, target: &'static str| {
            let app = &app;
            async move {
                sqlx::query_scalar::<_, i64>(
                    "INSERT INTO music_entries (name, category_id, source, target, key, sort) \
                     VALUES (?, 4, ?, ?, ?, 900) RETURNING id",
                )
                .bind(source)
                .bind(source)
                .bind(target)
                .bind(crate::music::state_key(target))
                .fetch_one(&app.db)
                .await
                .unwrap()
            }
        };
        let storytel = other("storytel", "storytel:series:26175").await;
        let nrk = other("nrk", "https://radio.nrk.no/podkast/fake_podkast").await;
        let playlist = entry(&app, "playlist", PLAYLIST).await;
        let show = entry(&app, "show", SHOW).await;
        for (id, header) in [
            (storytel, "Books"),
            (playlist, "Tracks"),
            (show, "Episodes"),
            (nrk, "Episodes"),
        ] {
            let page = app
                .get_page(&format!("/music/entries/{id}"), &cookie)
                .await
                .text();
            assert!(
                page.contains(&format!("<h2 class=\"group-header\">{header}</h2>")),
                "{id}: {header}"
            );
        }
    }

    /// The entry page (§3.6): the kind, the link back to Spotify, no offline choice; in context
    /// mode no order either, and a save keeps the stored order; a save stores cache 0.
    #[tokio::test]
    async fn the_entry_page_has_no_offline_and_in_context_mode_no_order() {
        let app = TestApp::new().await;
        let cookie = app.admin_cookie().await;
        ready(&app).await;
        let id = entry(&app, "playlist", PLAYLIST).await;
        answer_playlist(&app);
        assert_eq!(check(&app, id, t0()).await.error, None);
        let path = format!("/music/entries/{id}");
        let page = page_text(&app.get_page(&path, &cookie).await);
        assert!(page.contains("Spotify playlist"));
        assert!(page.contains("Open in Spotify"));
        assert!(page.contains("name=\"order\""));
        assert!(!page.contains("name=\"cache\""), "{page}");
        assert!(page.contains("Check Now"));
        let res = app
            .request_form(
                Method::POST,
                &path,
                Some(&cookie),
                &[
                    ("name", "Kveld"),
                    ("category", "1"),
                    ("order", "newest_first"),
                    ("resume", "on"),
                ],
            )
            .await;
        assert_eq!(res.status, StatusCode::SEE_OTHER, "{}", page_text(&res));
        let row: (String, i64) =
            sqlx::query_as("SELECT play_order, cache FROM music_entries WHERE id = ?")
                .bind(id)
                .fetch_one(&app.db)
                .await
                .unwrap();
        assert_eq!(row, ("newest_first".to_string(), 0));
        // Context mode: no order select; a save keeps the order.
        app.fetch.answer(
            &url(&app, Read::PlaylistItems(PLAYLIST, 0)),
            403,
            fixture("forbidden.json"),
        );
        let later = t0() + TimeDelta::hours(1);
        sweep::request_check(&app.db, id, later).await.unwrap();
        assert_eq!(check(&app, id, later).await.error, None);
        let page = page_text(&app.get_page(&path, &cookie).await);
        assert!(!page.contains("name=\"order\""), "{page}");
        assert!(page.contains("Spotify picks the order"));
        assert!(page.contains("no logged-in account may list this one's tracks"));
        let res = app
            .request_form(
                Method::POST,
                &path,
                Some(&cookie),
                &[("name", "Kveld"), ("category", "1"), ("cache", "5")],
            )
            .await;
        assert_eq!(res.status, StatusCode::SEE_OTHER, "{}", page_text(&res));
        let row: (String, i64, bool) =
            sqlx::query_as("SELECT play_order, cache, resume FROM music_entries WHERE id = ?")
                .bind(id)
                .fetch_one(&app.db)
                .await
                .unwrap();
        assert_eq!(row, ("newest_first".to_string(), 0, false));
    }

    /// An artist's entry page (emulator check §6l A, F5): context mode with the artist's own hint, never the
    /// playlist's "no logged-in account may list"; a playlist in context mode keeps that one.
    #[tokio::test]
    async fn an_artist_entry_page_has_the_artist_hint_not_the_playlist_one() {
        let app = TestApp::new().await;
        let cookie = app.admin_cookie().await;
        ready(&app).await;
        let id = entry(&app, "artist", super::listing::ARTIST).await;
        app.fetch.answer(
            &url(&app, Read::Artist(super::listing::ARTIST)),
            200,
            fixture("artist.json"),
        );
        app.fetch.answer(
            "https://i.scdn.co/image/fake-artist-320",
            200,
            crate::tests::music_art::png(0b0100_0010, 320, 320),
        );
        assert_eq!(check(&app, id, t0()).await.error, None);
        assert_eq!(
            listing(&app, id).await.spotify_mode.as_deref(),
            Some("context")
        );
        let page = page_text(&app.get_page(&format!("/music/entries/{id}"), &cookie).await);
        assert!(!page.contains("name=\"order\""), "{page}");
        assert!(
            page.contains("Spotify picks the order: an artist plays as Spotify does."),
            "{page}"
        );
        assert!(!page.contains("no logged-in account may list"), "{page}");
        assert!(
            page.contains("An artist plays as Spotify picks"),
            "the TRACKS note stays"
        );
    }

    /// The add route's other limits (§3.2, qa-26c-step2b-code L5): a picked phone whose library
    /// would pass 3 MB isn't ticked and is named; the shared budget - a check or a short link
    /// that outlasts it - answers "couldn't be reached" with nothing stored; a full library
    /// refuses.
    #[tokio::test]
    async fn the_size_the_budget_and_the_entry_limit() {
        let app = TestApp::new().await;
        let cookie = app.admin_cookie().await;
        let kid = ready(&app).await;
        let (ella, _) = app.enrolled_device("Ella").await;
        bind(&app, ella, kid).await;
        // Ella's library just under 3 MB (one own-files entry with a padding title).
        let own: i64 = sqlx::query_scalar(
            "INSERT INTO music_entries (name, category_id, source, key) VALUES ('Egne', 1, \
             'own', ?) RETURNING id",
        )
        .bind(crate::music::new_own_key())
        .fetch_one(&app.db)
        .await
        .unwrap();
        tick_on(&app, ella, own).await;
        sqlx::query(
            "INSERT INTO music_files (entry_id, path, original_name, size, sha256, title) \
             VALUES (?, ?, 'pad.mp3', 1, ?, 'x')",
        )
        .bind(own)
        .bind(format!("{own}/pad.mp3"))
        .bind("0".repeat(64))
        .execute(&app.db)
        .await
        .unwrap();
        let size = crate::music::device_library(&app.db, ella)
            .await
            .unwrap()
            .unwrap()
            .json
            .len();
        sqlx::query("UPDATE music_files SET title = ? WHERE entry_id = ?")
            .bind("x".repeat(crate::music::MAX_LIBRARY_BYTES - size - 50))
            .bind(own)
            .execute(&app.db)
            .await
            .unwrap();
        answer_playlist(&app);
        let ella_id = ella.to_string();
        let res = add(
            &app,
            &cookie,
            &[
                ("link", format!("spotify:playlist:{PLAYLIST}").as_str()),
                ("device", ella_id.as_str()),
            ],
        )
        .await;
        assert_eq!(res.status, StatusCode::SEE_OTHER, "{}", page_text(&res));
        let location = res.location().unwrap().to_string();
        assert!(
            location.ends_with(&format!("&big={ella}#added")),
            "{location}"
        );
        let id: i64 = sqlx::query_scalar("SELECT id FROM music_entries WHERE source = 'spotify'")
            .fetch_one(&app.db)
            .await
            .unwrap();
        assert!(ticks(&app, id).await.is_empty());
        let page = page_text(
            &app.get_page(location.trim_end_matches("#added"), &cookie)
                .await,
        );
        assert!(page.contains("Ella: not ticked - its music library would be bigger than 3 MB"));

        // The budget: a check held past it, then a short link held past it - nothing stored.
        app.state.spotify.set_add_budget(Duration::from_millis(100));
        app.fetch
            .answer(&url(&app, Read::Album(ALBUM_X)), 200, fixture("album.json"));
        app.fetch.hold();
        for link in [
            format!("spotify:album:{ALBUM_X}"),
            "https://spotify.link/AbC123".to_string(),
        ] {
            let res = add(&app, &cookie, &[("link", link.as_str())]).await;
            assert_eq!(res.status, StatusCode::BAD_REQUEST, "{link}");
            assert!(page_text(&res).contains("Spotify couldn't be reached - try again."));
        }
        app.fetch.release();
        assert_eq!(entry_count(&app).await, 2);
        app.state.spotify.set_add_budget(crate::spotify::ADD_BUDGET);

        // A full library: refused after the check, nothing stored.
        sqlx::query(
            "WITH RECURSIVE n(i) AS (SELECT 1 UNION ALL SELECT i + 1 FROM n WHERE i < ?) \
             INSERT INTO music_entries (name, category_id, source, target, key, sort) \
             SELECT 'F' || i, 4, 'rss', 'https://example.org/' || i || '.rss', 'k' || i, 100 + i \
             FROM n",
        )
        .bind(crate::music::MAX_ENTRIES - 2)
        .execute(&app.db)
        .await
        .unwrap();
        assert_eq!(entry_count(&app).await, crate::music::MAX_ENTRIES);
        let res = add(
            &app,
            &cookie,
            &[("link", format!("spotify:album:{ALBUM_X}").as_str())],
        )
        .await;
        assert_eq!(res.status, StatusCode::BAD_REQUEST);
        assert!(page_text(&res).contains(&format!(
            "The library has {} entries - delete one first.",
            crate::music::MAX_ENTRIES
        )));
        assert_eq!(entry_count(&app).await, crate::music::MAX_ENTRIES);
    }

    /// The device card's "Spotify plays as" (§2.5, §3.6): absent until an app is set up; the
    /// select binds (the revision moves, the phone is nudged, the event), "off" unbinds, an unknown
    /// account is refused back at `#music-spotify` with nothing changed, an unknown phone is a
    /// 404; the account's state, one account on two phones, the phone's own outcome in words, the
    /// mobile-data text and the DNS note while bound.
    #[tokio::test]
    async fn the_device_card_binds_a_phone_to_an_account() {
        let app = TestApp::new().await;
        let cookie = app.admin_cookie().await;
        let (ella, _) = app.enrolled_device("Ella").await;
        let (ola, _) = app.enrolled_device("Ola").await;
        let card = |device: i64| {
            let cookie = cookie.clone();
            let app = &app;
            async move {
                page_text(
                    &app.get_page(&format!("/devices/{device}/music"), &cookie)
                        .await,
                )
            }
        };
        // The phone's Web Filter page carries the DNS note (design 31 step 5).
        let filter = |device: i64| {
            let cookie = cookie.clone();
            let app = &app;
            async move {
                page_text(
                    &app.get_page(&format!("/devices/{device}/filter"), &cookie)
                        .await,
                )
            }
        };
        let page = card(ella).await;
        assert!(
            !page.contains("id=\"music-spotify\""),
            "no Spotify control before an app"
        );
        // Design 30: the streaming switch (not the downloads one) is the one Spotify obeys.
        assert!(page.contains(
            "This includes Spotify, which streams (about 60-150 MB an hour) and is never saved on the phone."
        ));
        let streaming = page
            .find("<p>Streaming:")
            .expect("the streaming switch's ⓘ");
        let downloads = page
            .find("<p>Downloads:")
            .expect("the downloads switch's ⓘ");
        let spotify = page.find("This includes Spotify").unwrap();
        assert!(
            streaming < spotify && spotify < downloads,
            "Spotify's line is the streaming switch's"
        );
        save_app(&app, LOOPBACK_REDIRECT).await;
        let kid = store_account(&app, "fakekid", "Ella", "FAKE-REFRESH-1").await;
        let parent = store_account(&app, "fakeparent", "Forelder", "FAKE-REFRESH-2").await;
        let page = card(ella).await;
        assert!(page.contains("id=\"music-spotify\""));
        assert!(
            page.contains(&format!("<option value=\"{kid}\">Ella</option>")),
            "{page}"
        );
        // Design 31's shapes (qa-26c-merge2-code M2): one footer sentence, a row to the accounts,
        // and the picker's no-JS Save (L2).
        assert!(page.contains(
            "<p class=\"group-footer\">One account per phone: the child's own Premium account, \
             never yours.</p>"
        ));
        assert!(
            page.contains(
                "href=\"/music/settings#spotify-accounts\" data-row=\"spotify-accounts\""
            )
        );
        let form = &page[page
            .find(&format!(
                "action=\"/devices/{ella}/music/spotify#music-spotify\""
            ))
            .expect("the Spotify form")..];
        let form = &form[..form.find("</form>").unwrap()];
        assert!(
            form.contains(
                "<noscript><button type=\"submit\" class=\"row row-button\">Save</button></noscript>"
            ),
            "{form}"
        );
        assert!(
            !filter(ella)
                .await
                .contains("Spotify's hosts are allowed on this phone")
        );
        let save = |device: i64, account: String| {
            let cookie = cookie.clone();
            let app = &app;
            async move {
                app.request_form(
                    Method::POST,
                    &format!("/devices/{device}/music/spotify"),
                    Some(&cookie),
                    &[("account", account.as_str())],
                )
                .await
            }
        };
        let mut nudges = app.state.command_notify.subscribe();
        let before = revision(&app).await;
        let res = save(ella, kid.to_string()).await;
        assert_eq!(res.status, StatusCode::SEE_OTHER);
        assert_eq!(
            res.location(),
            Some(format!("/devices/{ella}/music#music-spotify").as_str())
        );
        assert_eq!(binding(&app, ella).await, Some(kid));
        assert!(revision(&app).await > before);
        assert_eq!(nudged(&mut nudges), [ella]);
        assert_eq!(
            events(&app, "music_spotify_binding_changed").await,
            [format!("device {ella}: Spotify plays as Ella")]
        );
        let page = card(ella).await;
        assert!(page.contains(&format!("<option value=\"{kid}\" selected>Ella</option>")));
        assert!(
            page.contains("<span class=\"row-sub\" data-row=\"spotify-login\">Log in again before"),
            "{page}"
        );
        // The DNS note, now that step 3 makes it true (qa-26c-step2b-code L3): the delivered list
        // and the launcher's filter leave Spotify's hosts out while the phone is bound.
        assert!(
            filter(ella)
                .await
                .contains("Spotify's hosts are allowed on this phone while it plays Spotify")
        );
        // An account that isn't logged in here, or junk: refused, nothing changed.
        for junk in ["999".to_string(), "x".to_string()] {
            let res = save(ella, junk).await;
            assert_eq!(
                res.location(),
                Some(
                    format!("/devices/{ella}/music?music_notice=spotify_unknown#music-spotify")
                        .as_str()
                )
            );
            assert_eq!(binding(&app, ella).await, Some(kid));
        }
        let page = page_text(
            &app.get_page(
                &format!("/devices/{ella}/music?music_notice=spotify_unknown"),
                &cookie,
            )
            .await,
        );
        assert!(page.contains("Not saved: that Spotify account isn't logged in here"));
        assert_eq!(
            save(999, kid.to_string()).await.status,
            StatusCode::NOT_FOUND
        );
        // One account on two phones: a note (a short title, one sentence) on both pages, and the
        // Attention row on both summaries (qa-26c-merge2-code L3).
        save(ola, kid.to_string()).await;
        for device in [ella, ola] {
            let page = card(device).await;
            assert!(page.contains("Two phones share one account"), "{page}");
            assert!(page.contains(
                "Ella's and Ola's phones both play as Ella, and Spotify plays on one phone at a \
                 time: give each child their own."
            ));
            let section = &page[page.find("<section id=\"music-spotify\"").unwrap()..];
            let section = &section[..section.find("</section>").unwrap()];
            assert!(
                !section.replace("/devices/", "").contains("device"),
                "phone, never device: {section}"
            );
            let summary = page_text(&app.get_page(&format!("/devices/{device}"), &cookie).await);
            assert!(
                summary.contains("Two phones share a Spotify account"),
                "{summary}"
            );
        }
        let (kari, _) = app.enrolled_device("Kari").await;
        save(kari, kid.to_string()).await;
        assert!(card(kari).await.contains(
            "Ella's, Kari's and Ola's phones all play as Ella, and Spotify plays on one phone at a \
             time: give each child their own."
        ));
        save(kari, "off".to_string()).await;
        save(ola, parent.to_string()).await;
        assert!(!card(ella).await.contains("share one account"));
        let summary = page_text(&app.get_page(&format!("/devices/{ella}"), &cookie).await);
        assert!(!summary.contains("Two phones share a Spotify account"));
        // Every word the phone reports (§3.6): the passing ones as an "On the phone" row, the ones
        // that need the parent as notes - a short title and one sentence (§4.2).
        for (word, text) in [
            ("ok", "<span class=\"row-label\">Spotify</span>"),
            ("unreachable", "Couldn't reach Spotify"),
            ("quota", "Limit reached"),
            ("reauthorize", "Spotify login needs renewing"),
            ("refused", "Spotify refused the account"),
            ("no_widevine", "This phone can't play Spotify"),
        ] {
            report(&app, ella, json!({"spotify": word})).await;
            let page = card(ella).await;
            assert!(page.contains(text), "{word}: {page}");
            assert!(!page.contains("Widevine"));
        }
        report(&app, ella, json!({"spotify": "ok"})).await;
        let page = card(ella).await;
        let row = &page[page
            .find("data-row=\"spotify\"")
            .expect("the On the phone row")..];
        assert!(
            row[..row.find("</div>").unwrap()].contains(">Plays</span>"),
            "{row}"
        );
        report(&app, ella, json!({"spotify": "reauthorize"})).await;
        assert!(
            card(ella)
                .await
                .contains("Log in again as Ella on the Spotify page.")
        );
        // The phone's own outcome, and the account's state.
        report(&app, ella, json!({"spotify": "not_premium"})).await;
        let page = card(ella).await;
        assert!(page.contains("No Spotify Premium"));
        assert!(
            page.contains("Spotify says Ella has no Premium, so the phone can't play Spotify.")
        );
        // ... and an Attention row on the phone's summary (design 31 §3.8, as the music warnings):
        // to Device › Music's Spotify group.
        let summary = page_text(&app.get_page(&format!("/devices/{ella}"), &cookie).await);
        assert!(
            summary.contains("Spotify can't play on this phone"),
            "{summary}"
        );
        assert!(summary.contains(&format!("/devices/{ella}/music#music-spotify")));
        report(&app, ella, json!({"spotify": "ok"})).await;
        let summary = page_text(&app.get_page(&format!("/devices/{ella}"), &cookie).await);
        assert!(!summary.contains("Spotify can't play on this phone"));
        report(&app, ella, json!({"spotify": "not_premium"})).await;
        sqlx::query("UPDATE music_spotify_accounts SET needs_login = 1 WHERE id = ?")
            .bind(kid)
            .execute(&app.db)
            .await
            .unwrap();
        let page = card(ella).await;
        assert!(page.contains("Spotify login needs renewing"));
        assert!(page.contains("Log in again as Ella on the Spotify page."));
        // The account's side raises its own row; the phone's `not_premium` its own.
        let summary = page_text(&app.get_page(&format!("/devices/{ella}"), &cookie).await);
        assert!(
            summary.contains("Spotify login needs renewing"),
            "{summary}"
        );
        assert!(summary.contains("Spotify can't play on this phone"));
        // A renewing login and the phone's `reauthorize` are one row, not two.
        report(&app, ella, json!({"spotify": "reauthorize"})).await;
        let summary = page_text(&app.get_page(&format!("/devices/{ella}"), &cookie).await);
        assert_eq!(summary.matches("Spotify login needs renewing").count(), 1);
        assert!(!summary.contains("Spotify can't play on this phone"));
        // Ticked Spotify entries on an unbound phone: why they aren't sent.
        let id = entry(&app, "playlist", PLAYLIST).await;
        tick_on(&app, ella, id).await;
        let res = save(ella, "off".to_string()).await;
        assert_eq!(res.status, StatusCode::SEE_OTHER);
        assert_eq!(binding(&app, ella).await, None);
        let page = card(ella).await;
        assert!(page.contains(
            "<span class=\"row-sub\" data-row=\"spotify-off\">Its Spotify entry isn't sent.</span>"
        ));
        assert!(
            !filter(ella)
                .await
                .contains("Spotify's hosts are allowed on this phone")
        );
        // Unbound, neither its outcome nor its account raises a row.
        let summary = page_text(&app.get_page(&format!("/devices/{ella}"), &cookie).await);
        assert!(!summary.contains("Spotify can't play on this phone"));
        assert!(!summary.contains("Spotify login needs renewing"));
        assert_eq!(
            events(&app, "music_spotify_binding_changed")
                .await
                .last()
                .unwrap(),
            &format!("device {ella}: Spotify off")
        );
    }

    /// Entries failing for want of an app or a login are due at once after an app save or a new
    /// login (as Storytel's after a save), not after their backoff.
    #[tokio::test]
    async fn a_new_login_or_app_save_makes_the_waiting_entries_due() {
        let app = TestApp::new().await;
        app.state.music_sweep.set_art_per_check(0);
        let id = entry(&app, "playlist", PLAYLIST).await;
        let t = t0();
        assert_eq!(
            check(&app, id, t).await.error.as_deref(),
            Some("spotify_off")
        );
        let due = |now| {
            let app = &app;
            async move {
                sweep::due(&app.db, now)
                    .await
                    .unwrap()
                    .iter()
                    .any(|d| d.entry_id == id)
            }
        };
        assert!(!due(t + TimeDelta::minutes(1)).await, "backing off");
        // The app saved after the failure: due now.
        sqlx::query(
            "INSERT INTO music_spotify_app (id, client_id, redirect_uri, saved_at) VALUES \
             (1, ?, ?, ?)",
        )
        .bind(crate::spotify::tests::CLIENT)
        .bind(LOOPBACK_REDIRECT)
        .bind(stamp(t + TimeDelta::seconds(30)))
        .execute(&app.db)
        .await
        .unwrap();
        assert!(due(t + TimeDelta::minutes(1)).await);
        let t2 = t + TimeDelta::minutes(1);
        assert_eq!(
            check(&app, id, t2).await.error.as_deref(),
            Some("no_account")
        );
        assert!(!due(t2 + TimeDelta::minutes(1)).await, "backing off again");
        // A login after that failure: due now.
        let kid = store_account(&app, "fakekid", "Ella", "FAKE-REFRESH-1").await;
        sqlx::query("UPDATE music_spotify_accounts SET authorized_at = ? WHERE id = ?")
            .bind(stamp(t2 + TimeDelta::seconds(30)))
            .bind(kid)
            .execute(&app.db)
            .await
            .unwrap();
        assert!(due(t2 + TimeDelta::minutes(1)).await);
        // A login from before the failure cures nothing.
        sqlx::query("UPDATE music_spotify_accounts SET authorized_at = ? WHERE id = ?")
            .bind(stamp(t2 - TimeDelta::seconds(30)))
            .bind(kid)
            .execute(&app.db)
            .await
            .unwrap();
        assert!(!due(t2 + TimeDelta::minutes(1)).await);
    }

    /// The import (§3.2) through the routes: a Spotify URI and an `open.spotify.com` link are
    /// imported as Spotify entries (cache 0) on no phone, though the phones were picked; a short
    /// link is skipped with its reason. Nothing is fetched.
    #[tokio::test]
    async fn a_vibb_library_imports_spotify_entries_on_no_phone() {
        let app = TestApp::new().await;
        let cookie = app.admin_cookie().await;
        let (ella, _) = app.enrolled_device("Ella").await;
        let doc = json!({"sections": [{"name": "Musikk", "entries": [
            {"name": "Kveld", "target": format!("spotify:playlist:{PLAYLIST}"), "order": "auto", "cache": 5, "resume": true},
            {"name": "Album", "target": format!("https://open.spotify.com/album/{ALBUM_X}?si=x"), "cache": -1},
            {"name": "Kort", "target": "https://spotify.link/AbC123"},
            {"name": "Feed", "target": "https://example.org/feed.rss"},
        ]}]})
        .to_string();
        let ella_id = ella.to_string();
        let res = app
            .request_form(
                Method::POST,
                "/music/import/confirm",
                Some(&cookie),
                &[
                    ("doc", doc.as_str()),
                    ("row", "0.0"),
                    ("row", "0.1"),
                    ("row", "0.2"),
                    ("row", "0.3"),
                    ("section_0", "1"),
                    ("phone", ella_id.as_str()),
                ],
            )
            .await;
        assert_eq!(res.status, StatusCode::SEE_OTHER, "{}", page_text(&res));
        let rows: Vec<(String, String, i64)> =
            sqlx::query_as("SELECT source, target, cache FROM music_entries ORDER BY id")
                .fetch_all(&app.db)
                .await
                .unwrap();
        assert_eq!(
            rows,
            [
                (
                    "spotify".to_string(),
                    format!("spotify:playlist:{PLAYLIST}"),
                    0
                ),
                ("spotify".to_string(), format!("spotify:album:{ALBUM_X}"), 0),
                (
                    "rss".to_string(),
                    "https://example.org/feed.rss".to_string(),
                    5
                ),
            ]
        );
        let ticked: Vec<String> = sqlx::query_scalar(
            "SELECT e.source FROM device_music_entries d JOIN music_entries e ON e.id = d.entry_id",
        )
        .fetch_all(&app.db)
        .await
        .unwrap();
        assert_eq!(ticked, ["rss"]);
        assert!(app.fetch.calls.lock().unwrap().is_empty());
        let page = page_text(&app.get_page("/music/import", &cookie).await);
        assert!(
            page.contains("a Spotify short link - resolve it by adding it on the Spotify page"),
            "{page}"
        );
        let _ = lib_entry;
    }
}

/// Step 3 (design 26c §2.6, §6.2, §8): the phone's token, the policy's `music.spotify`, the DNS
/// version and the delivered blocklist.
mod phones {
    use std::time::Duration;

    use axum::http::{Method, StatusCode, header};
    use serde_json::{Value, json};

    use super::super::music::music_policy;
    use super::super::{TestApp, TestResponse};
    use super::bind;
    use super::listing::ready;
    use crate::spotify::tests::{TOKEN_URL, fixture, store_account};
    use crate::spotify::{PHONE_TOKENS_PER_WINDOW, remove_account};

    const ROUTE: &str = "/api/devices/music/spotify/token";

    async fn ask(app: &TestApp, token: &str) -> TestResponse {
        app.request(Method::POST, ROUTE, Some(token), None).await
    }

    /// The answer's status, its JSON and whether it is `no-store`.
    async fn answer(app: &TestApp, token: &str) -> (StatusCode, Value) {
        let res = ask(app, token).await;
        assert_eq!(
            res.headers.get(header::CACHE_CONTROL).unwrap(),
            "no-store",
            "{}",
            res.text()
        );
        assert_eq!(
            res.headers.get(header::CONTENT_TYPE).unwrap(),
            "application/json"
        );
        let body = res.json();
        if body["status"] != json!("ok") {
            assert!(body.get("access_token").is_none(), "{body}");
            assert!(!res.text().contains("FAKE-ACCESS"), "{body}");
        }
        (res.status, body)
    }

    fn token_requests(app: &TestApp) -> usize {
        app.fetch
            .calls
            .lock()
            .unwrap()
            .iter()
            .filter(|u| u.as_str() == TOKEN_URL)
            .count()
    }

    async fn dns_version(app: &TestApp, token: &str) -> String {
        let res = app
            .request(Method::GET, "/api/devices/policy", Some(token), None)
            .await;
        assert_eq!(res.status, StatusCode::OK);
        res.json()["dns_filter_version"]
            .as_str()
            .unwrap()
            .to_string()
    }

    /// §2.6's six answers, each `no-store` JSON with no token outside `ok`; the token minted once
    /// and kept; `account` = "<id>:<generation>".
    #[tokio::test]
    async fn the_token_route_answers() {
        let app = TestApp::new().await;
        let (ella, token) = app.enrolled_device("Ella").await;
        // Nothing set up: off.
        assert_eq!(
            answer(&app, &token).await,
            (StatusCode::NOT_FOUND, json!({"status": "off"}))
        );
        let account = ready(&app).await;
        // An app and an account, but this phone isn't bound: off, nothing asked of Spotify.
        assert_eq!(
            answer(&app, &token).await,
            (StatusCode::NOT_FOUND, json!({"status": "off"}))
        );
        assert_eq!(token_requests(&app), 0);
        bind(&app, ella, account).await;
        let (status, body) = answer(&app, &token).await;
        assert_eq!(status, StatusCode::OK);
        assert_eq!(
            body,
            json!({
                "status": "ok",
                "access_token": "FAKE-ACCESS-2",
                "expires_in_s": 3540,
                "account": format!("{account}:1"),
            })
        );
        assert_eq!(token_requests(&app), 1);
        // The kept token, while it has more than 10 minutes left; then a refresh.
        app.state.spotify.advance(Duration::from_secs(2899));
        let (_, body) = answer(&app, &token).await;
        assert_eq!(body["expires_in_s"], json!(640));
        assert_eq!(token_requests(&app), 1);
        app.state.spotify.advance(Duration::from_secs(60));
        let (_, body) = answer(&app, &token).await;
        assert_eq!(body["expires_in_s"], json!(3540));
        assert_eq!(token_requests(&app), 2, "under 10 minutes left: refreshed");

        // Every failure of the accounts service, on a fresh mint.
        let failing = |status: u16, body: Vec<u8>, retry_after: Option<&'static str>| {
            let app = &app;
            let token = token.clone();
            async move {
                app.state.spotify.forget(account);
                app.fetch.answer(TOKEN_URL, status, body);
                if let Some(value) = retry_after {
                    app.fetch.retry_after(TOKEN_URL, value);
                }
                answer(app, &token).await
            }
        };
        assert_eq!(
            failing(429, fixture("rate_limited.json"), Some("7")).await,
            (
                StatusCode::TOO_MANY_REQUESTS,
                json!({"status": "busy", "retry_after_s": 7})
            )
        );
        assert_eq!(
            failing(503, Vec::new(), None).await,
            (StatusCode::BAD_GATEWAY, json!({"status": "unreachable"}))
        );
        assert_eq!(
            failing(400, fixture("token_invalid_client.json"), None).await,
            (StatusCode::CONFLICT, json!({"status": "reauthorize"}))
        );
        assert_eq!(
            failing(400, fixture("token_invalid_grant.json"), None).await,
            (StatusCode::CONFLICT, json!({"status": "reauthorize"}))
        );
        // The login ended: no request any more.
        let before = token_requests(&app);
        assert_eq!(
            answer(&app, &token).await,
            (StatusCode::CONFLICT, json!({"status": "reauthorize"}))
        );
        assert_eq!(token_requests(&app), before);

        // A login sealed with another key: key.
        let other = store_account(&app, "fakeparent", "Forelder", "FAKE-REFRESH-9").await;
        sqlx::query("UPDATE music_spotify_accounts SET key_fingerprint = 'another' WHERE id = ?")
            .bind(other)
            .execute(&app.db)
            .await
            .unwrap();
        bind(&app, ella, other).await;
        assert_eq!(
            answer(&app, &token).await,
            (StatusCode::SERVICE_UNAVAILABLE, json!({"status": "key"}))
        );

        // No app any more (an environment's client ID gone between restarts): off, though bound.
        sqlx::query("DELETE FROM music_spotify_app")
            .execute(&app.db)
            .await
            .unwrap();
        assert_eq!(
            answer(&app, &token).await,
            (StatusCode::NOT_FOUND, json!({"status": "off"}))
        );
        // The account removed: its phones are unbound - off.
        crate::spotify::tests::save_app(&app, crate::spotify::LOOPBACK_REDIRECT).await;
        remove_account(&app.state, other).await.unwrap();
        assert_eq!(
            answer(&app, &token).await,
            (StatusCode::NOT_FOUND, json!({"status": "off"}))
        );
        // Not a phone: the bearer is required.
        let res = app.request(Method::POST, ROUTE, None, None).await;
        assert_eq!(res.status, StatusCode::UNAUTHORIZED);
    }

    /// §2.6: 20 asks a rolling hour per phone, then `busy` with the wait; another phone isn't
    /// held back.
    #[tokio::test]
    async fn the_token_route_is_limited_per_phone() {
        let app = TestApp::new().await;
        let (ella, token) = app.enrolled_device("Ella").await;
        let (ola, ola_token) = app.enrolled_device("Ola").await;
        let account = ready(&app).await;
        bind(&app, ella, account).await;
        bind(&app, ola, account).await;
        for _ in 0..PHONE_TOKENS_PER_WINDOW {
            assert_eq!(answer(&app, &token).await.0, StatusCode::OK);
        }
        let (status, body) = answer(&app, &token).await;
        assert_eq!(status, StatusCode::TOO_MANY_REQUESTS);
        assert_eq!(body["status"], json!("busy"));
        let wait = body["retry_after_s"].as_u64().unwrap();
        assert!((3500..=3600).contains(&wait), "{wait}");
        assert_eq!(answer(&app, &ola_token).await.0, StatusCode::OK);
        assert_eq!(token_requests(&app), 1, "one refresh, the rest kept");
        app.state.spotify.advance(Duration::from_secs(3600));
        assert_eq!(answer(&app, &token).await.0, StatusCode::OK);
    }

    /// A refresh held past the route's budget answers `unreachable`; it isn't cancelled, and the
    /// next ask gets its token without another request (qa-26c-step2b-code M1, step 1's note).
    #[tokio::test]
    async fn a_slow_refresh_is_unreachable_then_kept() {
        let app = TestApp::new().await;
        let (ella, token) = app.enrolled_device("Ella").await;
        let account = ready(&app).await;
        bind(&app, ella, account).await;
        app.state
            .spotify
            .set_phone_token_budget(Duration::from_millis(100));
        app.fetch.hold();
        assert_eq!(
            answer(&app, &token).await,
            (StatusCode::BAD_GATEWAY, json!({"status": "unreachable"}))
        );
        app.fetch.release();
        app.state
            .spotify
            .set_phone_token_budget(crate::spotify::PHONE_TOKEN_BUDGET);
        let (status, body) = answer(&app, &token).await;
        assert_eq!(status, StatusCode::OK);
        assert_eq!(body["access_token"], json!("FAKE-ACCESS-2"));
        assert_eq!(token_requests(&app), 1);
    }

    /// §6.2: `music.spotify` is the binding; the DNS version moves on bind and on unbind (QA M7)
    /// and an unbound phone's version is what it was before step 3; the delivered list drops
    /// Spotify's hosts - a suffix's subdomains and Spotify's own parents, the exact CDN hosts but
    /// not their parent - for the bound phone only.
    #[tokio::test]
    async fn the_policy_and_the_blocklist_follow_the_binding() {
        let app = TestApp::new().await;
        let (ella, token) = app.enrolled_device("Ella").await;
        let (_, ola_token) = app.enrolled_device("Ola").await;
        {
            let mut compiled = app.state.dns_compiled.write().await;
            compiled.global_custom_block = [
                "spotify.com",
                "spclient.wg.spotify.com",
                "scdn.co",
                "audio-ak-spotify-com.akamaized.net",
                "akamaized.net",
                "evilspotify.com",
                "ads.example",
                "mtalk.google.com",
            ]
            .into_iter()
            .map(String::from)
            .collect();
        }
        let blocked = |token: String| {
            let app = &app;
            async move {
                let res = app
                    .request(
                        Method::GET,
                        "/api/devices/dns-blocklist",
                        Some(&token),
                        None,
                    )
                    .await;
                assert_eq!(res.status, StatusCode::OK);
                let mut domains: Vec<String> = res.json()[0]["domains"]
                    .as_array()
                    .unwrap()
                    .iter()
                    .map(|d| d.as_str().unwrap().to_string())
                    .collect();
                domains.sort();
                domains
            }
        };
        let everything = [
            "ads.example",
            "akamaized.net",
            "audio-ak-spotify-com.akamaized.net",
            "evilspotify.com",
            "scdn.co",
            "spclient.wg.spotify.com",
            "spotify.com",
        ];
        assert_eq!(blocked(token.clone()).await, everything);
        assert_eq!(music_policy(&app, &token).await["spotify"], json!(false));
        let unbound = dns_version(&app, &token).await;
        assert_eq!(
            unbound,
            dns_version(&app, &ola_token).await,
            "no binding, no change to the version"
        );

        let account = ready(&app).await;
        bind(&app, ella, account).await;
        assert_eq!(music_policy(&app, &token).await["spotify"], json!(true));
        let bound = dns_version(&app, &token).await;
        assert_ne!(bound, unbound, "binding moves the version");
        assert_eq!(
            blocked(token.clone()).await,
            ["ads.example", "akamaized.net", "evilspotify.com"]
        );
        // The other phone keeps the parent's list.
        assert_eq!(
            music_policy(&app, &ola_token).await["spotify"],
            json!(false)
        );
        assert_eq!(blocked(ola_token.clone()).await, everything);
        assert_eq!(dns_version(&app, &ola_token).await, unbound);

        sqlx::query("UPDATE device_policy SET music_spotify_account = NULL WHERE device_id = ?")
            .bind(ella)
            .execute(&app.db)
            .await
            .unwrap();
        assert_eq!(music_policy(&app, &token).await["spotify"], json!(false));
        assert_eq!(
            dns_version(&app, &token).await,
            unbound,
            "unbinding moves it back"
        );
        assert_eq!(blocked(token).await, everything);
    }

    /// qa-26c-step3-code L5: where the parent types a block (the phone's Web Filter page,
    /// `/devices/{id}/filter`, since design 31 step 5), a block of one of Spotify's hosts says it
    /// isn't applied while the phone plays Spotify - its own and a site-wide one - and nothing else
    /// is marked; an unbound phone's page marks nothing.
    #[tokio::test]
    async fn the_web_filter_page_says_what_spotify_overrides() {
        let app = TestApp::new().await;
        let cookie = app.admin_cookie().await;
        let (ella, _) = app.enrolled_device("Ella").await;
        for (domain, device) in [
            ("spotify.com", Some(ella)),
            ("ads.example", Some(ella)),
            ("i.scdn.co", None),
            ("tracker.example", None),
        ] {
            sqlx::query(
                "INSERT INTO dns_custom_domains (domain, list_type, device_id) VALUES (?, 'block', ?)",
            )
            .bind(domain)
            .bind(device)
            .execute(&app.db)
            .await
            .unwrap();
        }
        let page = || {
            let app = &app;
            let cookie = cookie.clone();
            async move {
                let res = app
                    .get_page(&format!("/devices/{ella}/filter"), &cookie)
                    .await;
                assert_eq!(res.status, StatusCode::OK);
                res.text()
            }
        };
        let unbound = page().await;
        assert!(unbound.contains("spotify.com"));
        assert!(
            !unbound.contains("Not applied while Spotify is on"),
            "{unbound}"
        );
        assert!(!unbound.contains("while this phone plays Spotify"));
        assert!(!unbound.contains("Spotify's hosts are allowed"));

        let account = ready(&app).await;
        bind(&app, ella, account).await;
        let bound = page().await;
        assert!(
            bound.contains(
                "spotify.com<span class=\"row-sub\" data-spotify-override>Not applied while this phone plays Spotify</span>"
            ),
            "{bound}"
        );
        assert!(bound.contains("ads.example</span>"), "{bound}");
        assert_eq!(bound.matches("data-spotify-override").count(), 1);
        // One footer under the filter switch while bound; the rest behind its ⓘ (qa-26c-merge2-code
        // M2).
        assert!(bound.contains(
            "Blocks the lists and domains below, except Spotify's while this phone plays Spotify."
        ));
        let filter = &bound[bound.find("<section id=\"filter\"").unwrap()..];
        let filter = &filter[..filter.find("</section>").unwrap()];
        assert_eq!(filter.matches("group-footer").count(), 1, "{filter}");
        let panel = &filter[filter.find("info-panel").unwrap()..];
        assert!(panel.contains(
            "Not applied on Ella for that reason, from Settings › Web Filter: i.scdn.co."
        ));
        assert!(panel.contains("Spotify's hosts are allowed on this phone while it plays Spotify"));
        // `/dns?device=` (an old link) goes to that page.
        let res = app.get_page(&format!("/dns?device={ella}"), &cookie).await;
        assert_eq!(
            res.location(),
            Some(format!("/devices/{ella}/filter").as_str())
        );
    }
}

/// Design 47: Add from Spotify opens on the add form; the setup is a group of Music › Settings,
/// with "Plays on" per account and Storytel per phone.
mod settings_group {
    use axum::http::{Method, StatusCode, header};

    use super::super::TestApp;
    use super::super::settings_pages::check_forms;
    use super::{bind, binding, revision};
    use crate::spotify::LOOPBACK_REDIRECT;
    use crate::spotify::tests::{save_app, store_account};

    fn text(res: &super::TestResponse) -> String {
        res.text().replace("&#39;", "'")
    }

    #[tokio::test]
    async fn the_add_page_first_and_the_setup_in_music_settings() {
        let app = TestApp::new().await;
        let cookie = app.admin_cookie().await;
        save_app(&app, LOOPBACK_REDIRECT).await;
        store_account(&app, "fakekid", "Ella", "FAKE-REFRESH-1").await;
        store_account(&app, "fakekid2", "Ola", "FAKE-REFRESH-2").await;

        let page = text(&app.get_page("/music/spotify", &cookie).await);
        check_forms("/music/spotify", &page);
        assert!(page.contains("<h1 class=\"page-title\">Add from Spotify</h1>"));
        assert!(page.contains("action=\"/music/spotify/add#add\""));
        assert!(!page.contains("data-row=\"spotify-not-ready\""), "set up");
        assert!(!page.contains("disabled>Add"));
        let row = &page[page
            .find("data-row=\"spotify-setup\"")
            .expect("the setup row")..];
        assert!(
            row[..row.find("</a>").unwrap()]
                .contains("<span class=\"row-value\">2 accounts</span>")
        );
        assert!(page.contains("href=\"/music/settings#spotify\" data-row=\"spotify-setup\""));
        for setup in [
            "client_id",
            "/music/spotify/app",
            "/music/spotify/login",
            "Children's Accounts",
        ] {
            assert!(!page.contains(setup), "{setup} on the add page");
        }

        let res = app.get_page("/music/settings", &cookie).await;
        let csp = res.headers[header::CONTENT_SECURITY_POLICY]
            .to_str()
            .unwrap();
        assert!(
            csp.contains("form-action 'self' https://accounts.spotify.com;"),
            "{csp}"
        );
        let settings = text(&res);
        check_forms("/music/settings", &settings);
        let at = |needle: &str| settings.find(needle).unwrap_or_else(|| panic!("{needle}"));
        assert!(
            at("id=\"storytel\"") < at("id=\"spotify\""),
            "after Storytel"
        );
        for id in [
            "spotify",
            "spotify-app",
            "spotify-app-form",
            "spotify-setup",
            "spotify-app-clear",
            "spotify-accounts",
            "spotify-login",
            "spotify-paste",
            "spotify-client-id",
            "spotify-redirect-uri",
            "spotify-redirect-uri-field",
            "spotify-paste-url",
        ] {
            assert!(settings.contains(&format!("id=\"{id}\"")), "{id}");
        }
        // No unprefixed id of the moved markup is left (`#app` is the music app's group).
        for old in [
            "id=\"app-form\"",
            "id=\"setup\"",
            "id=\"app-clear\"",
            "id=\"accounts\"",
            "id=\"account-",
            "id=\"login\"",
            "id=\"paste\"",
            "id=\"key\"",
            "id=\"client_id\"",
            "id=\"redirect_uri\"",
            "id=\"paste_url\"",
            "id=\"redirect-uri\"",
        ] {
            assert!(!settings.contains(old), "{old}");
        }
        assert_eq!(settings.matches("id=\"app\"").count(), 1, "the music app's");
    }

    /// The refusals' error ids carry the prefix too, and each re-renders Music › Settings.
    #[tokio::test]
    async fn the_refusals_render_music_settings_with_prefixed_ids() {
        let app = TestApp::new().await;
        let cookie = app.admin_cookie().await;
        save_app(&app, LOOPBACK_REDIRECT).await;
        let ella = store_account(&app, "fakekid", "Ella", "FAKE-REFRESH-1").await;
        for (path, fields, error) in [
            (
                "/music/spotify/app/clear".to_string(),
                vec![],
                "spotify-clear-error",
            ),
            (
                format!("/music/spotify/accounts/{ella}/remove"),
                vec![],
                &*format!("spotify-remove-error-{ella}"),
            ),
            (
                "/music/spotify/paste".to_string(),
                vec![("url", "https://example.org/")],
                "spotify-paste-error",
            ),
        ] {
            let res = app
                .request_form(Method::POST, &path, Some(&cookie), &fields)
                .await;
            assert_eq!(res.status, StatusCode::BAD_REQUEST, "{path}");
            let page = text(&res);
            assert!(page.contains(&format!("id=\"{error}\"")), "{path}: {page}");
            assert!(
                page.contains(&format!("aria-describedby=\"{error}\" autofocus")),
                "{path}"
            );
            assert!(
                page.contains("<h1 class=\"page-title\">Settings</h1>"),
                "{path}"
            );
        }
        // A refused login (its form has no fragment: one would ride along on the 303 to Spotify)
        // focuses its error, so the browser scrolls the Spotify group into view instead of
        // leaving the parent at the top of a long page (qa-r5-b-code M1): here with no app. The
        // other-address refusal focuses the button (`a_login_starts_only_at_the_redirects_address`).
        sqlx::query("DELETE FROM music_spotify_app")
            .execute(&app.db)
            .await
            .unwrap();
        let res = app
            .request_form(Method::POST, "/music/spotify/login", Some(&cookie), &[])
            .await;
        assert_eq!(res.status, StatusCode::BAD_REQUEST);
        let page = text(&res);
        assert_eq!(
            page.matches("data-flash=\"login\" tabindex=\"-1\" autofocus>")
                .count(),
            1,
            "{page}"
        );
        assert_eq!(
            page.matches(" autofocus").count(),
            1,
            "one focus on the page"
        );
    }

    /// §1.6: one phone per account. Picking a phone binds it (moving it from another account) and
    /// unbinds the account's other phones; "No phone" unbinds them all; an unknown phone or
    /// account is a 400 with nothing written. The row says where it plays.
    #[tokio::test]
    async fn plays_on_binds_one_phone_per_account() {
        let app = TestApp::new().await;
        let cookie = app.admin_cookie().await;
        save_app(&app, LOOPBACK_REDIRECT).await;
        let ella = store_account(&app, "fakekid", "Ella", "FAKE-REFRESH-1").await;
        let ola = store_account(&app, "fakekid2", "Ola", "FAKE-REFRESH-2").await;
        let (anna_phone, _) = app.enrolled_device("Annas telefon").await;
        let (ola_phone, _) = app.enrolled_device("Olas telefon").await;
        let (spare, _) = app.enrolled_device("Reserve").await;
        let plays_on = |account: i64| {
            let app = &app;
            let cookie = cookie.clone();
            async move {
                let page = text(&app.get_page("/music/settings", &cookie).await);
                let cell = &page[page
                    .find(&format!("id=\"spotify-account-{account}\""))
                    .unwrap()..];
                let line = &cell[cell.find("data-plays-on>").unwrap() + 14..];
                let select = &cell[cell
                    .find(&format!("id=\"spotify-account-{account}-plays-on\""))
                    .unwrap()..];
                let select = &select[..select.find("</select>").unwrap()];
                let chosen = select
                    .split("<option")
                    .find(|o| o.contains(" selected>"))
                    .map(|o| o[o.find('>').unwrap() + 1..o.find('<').unwrap()].to_string());
                (line[..line.find('<').unwrap()].to_string(), chosen)
            }
        };
        let post = |account: i64, device: &str| {
            let app = &app;
            let cookie = cookie.clone();
            let device = device.to_string();
            async move {
                app.request_form(
                    Method::POST,
                    &format!("/music/spotify/accounts/{account}/plays-on"),
                    Some(&cookie),
                    &[("device", device.as_str())],
                )
                .await
            }
        };
        assert_eq!(
            plays_on(ella).await,
            ("Plays on no phone yet".into(), Some("No phone".into()))
        );

        // Several phones (Device › Music bound them): the picker says so.
        bind(&app, anna_phone, ella).await;
        bind(&app, spare, ella).await;
        bind(&app, ola_phone, ola).await;
        assert_eq!(
            plays_on(ella).await,
            (
                "Plays on Annas telefon, Reserve".into(),
                Some("Several phones".into())
            )
        );

        // Ola's phone for Ella: it moves from Ola, Ella's two others are unbound.
        let before = revision(&app).await;
        let mut nudges = app.state.command_notify.subscribe();
        let res = post(ella, &ola_phone.to_string()).await;
        assert_eq!(
            res.location(),
            Some(format!("/music/settings#spotify-account-{ella}").as_str())
        );
        // Every phone that changed is nudged at once, and only those (qa-r5-b-code L3).
        let mut nudged: Vec<i64> = std::iter::from_fn(|| nudges.try_recv().ok()).collect();
        nudged.sort();
        let mut changed = vec![ola_phone, anna_phone, spare];
        changed.sort();
        assert_eq!(nudged, changed);
        app.assert_lands(&res, &cookie).await;
        assert_eq!(
            (
                binding(&app, ola_phone).await,
                binding(&app, anna_phone).await,
                binding(&app, spare).await
            ),
            (Some(ella), None, None)
        );
        assert!(revision(&app).await > before, "the phones sync");
        assert_eq!(
            plays_on(ella).await,
            ("Plays on Olas telefon".into(), Some("Olas telefon".into()))
        );
        assert_eq!(plays_on(ola).await.0, "Plays on no phone yet");
        let logged: Vec<String> = super::events(&app, "music_spotify_binding_changed").await;
        assert_eq!(logged.len(), 3, "{logged:?}");
        assert!(logged.contains(&format!("device {ola_phone}: Spotify plays as Ella")));
        assert!(logged.contains(&format!("device {spare}: Spotify off")));

        // Unknown phone, unknown account, no choice: 400, nothing written.
        for (account, device) in [(ella, "999"), (999, &*anna_phone.to_string()), (ella, "x")] {
            let res = post(account, device).await;
            assert_eq!(res.status, StatusCode::BAD_REQUEST, "{account} {device}");
        }
        let res = app
            .request_form(
                Method::POST,
                &format!("/music/spotify/accounts/{ella}/plays-on"),
                Some(&cookie),
                &[],
            )
            .await;
        assert_eq!(res.status, StatusCode::BAD_REQUEST);
        assert_eq!(binding(&app, ola_phone).await, Some(ella));
        assert_eq!(binding(&app, anna_phone).await, None);

        // No phone: every phone unbound.
        let res = post(ella, "none").await;
        assert_eq!(
            res.location(),
            Some(format!("/music/settings#spotify-account-{ella}").as_str())
        );
        assert_eq!(binding(&app, ola_phone).await, None);
        assert_eq!(
            plays_on(ella).await,
            ("Plays on no phone yet".into(), Some("No phone".into()))
        );
    }

    /// §1.7: Storytel per phone from Music › Settings sets `music_storytel` alone - the phone's
    /// mobile-data switches, volume cap and storage limit stay as they are.
    #[tokio::test]
    async fn storytel_on_a_phone_from_music_settings() {
        let app = TestApp::new().await;
        let cookie = app.admin_cookie().await;
        let (anna, _) = app.enrolled_device("Anna").await;
        let (ola, _) = app.enrolled_device("Ola").await;
        // A saved login (Storytel can't be reached here: saved, unchecked): the row shows.
        let login = [
            ("email", "parent@example.invalid"),
            ("password", "not-a-real-password"),
        ];
        app.request_form(Method::POST, "/music/storytel", Some(&cookie), &login)
            .await;
        sqlx::query(
            "UPDATE device_policy SET music_stream_mobile_data = 1, music_download_mobile_data = 1, \
             music_volume_cap_pct = 70, music_storage_limit_gb = 8, music_storytel = 1 \
             WHERE device_id = ?",
        )
        .bind(ola)
        .execute(&app.db)
        .await
        .unwrap();
        type Settings = (bool, bool, Option<i64>, Option<i64>, bool);
        let settings = |device: i64| {
            let db = app.db.clone();
            async move {
                sqlx::query_as::<_, Settings>(
                    "SELECT music_stream_mobile_data, music_download_mobile_data, \
                     music_volume_cap_pct, music_storage_limit_gb, music_storytel \
                     FROM device_policy WHERE device_id = ?",
                )
                .bind(device)
                .fetch_one(&db)
                .await
                .unwrap()
            }
        };
        let mut nudges = app.state.command_notify.subscribe();
        let res = app
            .request_form(
                Method::POST,
                &format!("/music/storytel/phones/{ola}"),
                Some(&cookie),
                &[],
            )
            .await;
        assert_eq!(
            res.location(),
            Some("/music/settings?storytel=phones#storytel-phones")
        );
        // That phone is nudged, and no other (qa-r5-b-code L3).
        assert_eq!(nudges.try_recv().ok(), Some(ola));
        assert!(nudges.try_recv().is_err());
        assert_eq!(settings(ola).await, (true, true, Some(70), Some(8), false));
        let res = app
            .request_form(
                Method::POST,
                &format!("/music/storytel/phones/{anna}"),
                Some(&cookie),
                &[("on", "on")],
            )
            .await;
        assert!(settings(anna).await.4);
        assert_eq!(
            super::events(&app, "music_phone_settings").await,
            [
                format!("device {ola}: Storytel false"),
                format!("device {anna}: Storytel true")
            ]
        );
        // Back with the row open, the switches as stored; an unknown phone is a 400.
        let page = text(&app.get_page(res.location().unwrap(), &cookie).await);
        assert!(page.contains("<details class=\"cell disclosure\" id=\"storytel-phones\" open>"));
        assert!(page.contains(
            "<span class=\"row-label\">On phones</span><span class=\"row-value\">1</span>"
        ));
        assert!(page.contains(&format!(
            "action=\"/music/storytel/phones/{anna}#storytel-phones\""
        )));
        let res = app
            .request_form(
                Method::POST,
                "/music/storytel/phones/999",
                Some(&cookie),
                &[("on", "on")],
            )
            .await;
        assert_eq!(res.status, StatusCode::BAD_REQUEST);
        // Opened from anywhere else, the row is closed.
        let page = text(&app.get_page("/music/settings", &cookie).await);
        assert!(page.contains("<details class=\"cell disclosure\" id=\"storytel-phones\">"));
    }
}
