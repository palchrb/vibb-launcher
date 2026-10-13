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

#[tokio::test]
async fn routes_need_a_session() {
    let app = TestApp::new().await;
    for path in [
        "/music/spotify/app",
        "/music/spotify/app/clear",
        "/music/spotify/login",
        "/music/spotify/paste",
        "/music/spotify/accounts/1/remove",
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
            .contains("content=\"0;url=/music/spotify?login=ok#accounts\""),
        "{}",
        res.text().replace("&#39;", "'")
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
        text.contains("content=\"0;url=/music/spotify?login=expired#accounts\""),
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
    let res = app.get_page("/music/spotify", &cookie).await;
    assert_eq!(res.status, StatusCode::OK);
    let page = res.text().replace("&#39;", "'");
    assert!(page.contains("No Spotify app is set up on this server yet"));
    assert!(page.contains("developer.spotify.com/dashboard"));
    assert!(page.contains("At most 5 accounts can use a Development-mode app"));
    assert!(page.contains("<details id=\"setup\" open"));
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
        assert!(
            page.contains(&format!("id=\"{field}\"")) && page.contains("autofocus"),
            "{field}"
        );
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
    assert_eq!(res.location(), Some("/music/spotify?saved=0#app"));
    let page = app
        .get_page("/music/spotify?saved=0", &cookie)
        .await
        .text()
        .replace("&#39;", "'");
    assert!(
        page.contains("Client ID ending in <strong>cdef</strong>"),
        "{page}"
    );
    assert!(page.contains(&format!(
        "<code class=\"sp-uri\" id=\"redirect-uri\">{HTTPS_REDIRECT}</code>"
    )));
    assert!(page.contains("Saved."));
    assert!(page.contains("Log in with Spotify"));
    assert!(!page.contains("<details id=\"setup\" open"));
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
                    .uri("/music/spotify")
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
    assert!(
        page.contains(
            "Client ID ending in <strong>3210</strong> - set by the server's configuration"
        )
    );
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
                .uri("/music/spotify")
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
        .get_page("/music/spotify?login=ok", &cookie)
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
        .get_page("/music/spotify?login=expired", &cookie)
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
        .get_page("/music/spotify?login=not_registered", &cookie)
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
        ["Ella", "Pål"]
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
        .get_page("/music/spotify", &cookie)
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
    assert_eq!(res.location(), Some("/music/spotify?login=ok#accounts"));
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
        Some("/music/spotify?login=expired#accounts")
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
        Some("/music/spotify?login=expired#accounts")
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
        Some("/music/spotify?login=cancelled#accounts")
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
    assert_eq!(res.location(), Some("/music/spotify?removed=1#accounts"));
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
    assert_eq!(res.location(), Some("/music/spotify?saved=1#app"));
    assert!(account_rows(&app).await.is_empty());
    assert_eq!(binding(&app, ola).await, None);
    assert_eq!(revision(&app).await, before + 4);
    let page = app
        .get_page("/music/spotify?saved=1", &cookie)
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
    assert_eq!(res.location(), Some("/music/spotify?cleared=1#app"));
    assert!(spotify::app(&app.state).await.unwrap().is_none());
    assert!(account_rows(&app).await.is_empty());
    assert_eq!(binding(&app, ella).await, None);
    assert_eq!(
        events(&app, "spotify_app_cleared").await,
        ["Spotify app and 1 accounts removed"]
    );
    let page = app
        .get_page("/music/spotify?cleared=1", &cookie)
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
        .get_page("/music/spotify", &cookie)
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
        .get_page("/music/spotify", &cookie)
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
        .get_page("/music/spotify", &cookie)
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
        .get_page("/music/spotify", &cookie)
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
        .get_page("/music/spotify", &cookie)
        .await
        .text()
        .replace("&#39;", "'");
    assert!(page.contains("Spotify doesn't accept this client ID any more"));
}

thread_local! {
    /// This thread's capture while [no_secret_in_logs_events_or_pages] runs, else `None`.
    static CAPTURE: std::cell::RefCell<Option<Vec<String>>> = const { std::cell::RefCell::new(None) };
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
fn install_capture() {
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
    for page in ["/music/spotify", "/music/spotify?login=ok", "/music"] {
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
        assert!(page.contains("href=\"https://pi.example.ts.net/music/spotify#accounts\""));
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

mod listing {
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

    const PLAYLIST: &str = "0FakePlaylistKveld0001";
    const ALBUM_X: &str = "2FakeAlbumX00000000001";
    const ALBUM_Y: &str = "2FakeAlbumY00000000002";
    const ALBUM_Z: &str = "2FakeAlbumZ00000000003";
    const TRACK_A: &str = "1FakeTrackA00000000001";
    const TRACK_C: &str = "1FakeTrackC00000000003";
    const TRACK_D: &str = "1FakeTrackD00000000004";
    const ARTIST: &str = "3FakeArtist00000000001";
    const SHOW: &str = "4FakeShow0000000000001";
    const MOSAIC: &str = "https://mosaic.scdn.co/640/fake-mosaic-kveld";
    const ART_X: &str = "https://i.scdn.co/image/fake-album-x-300";
    const ART_Y: &str = "https://i.scdn.co/image/fake-album-y-640";

    /// A Spotify entry straight into the table (2b's add form makes them).
    async fn entry(app: &TestApp, kind: &str, id: &str) -> i64 {
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
    async fn ready(app: &TestApp) -> i64 {
        app.state.music_sweep.set_art_per_check(0);
        save_app(app, LOOPBACK_REDIRECT).await;
        app.fetch
            .answer(TOKEN_URL, 200, fixture("token_refresh.json"));
        store_account(app, "fakekid", "Ella", "FAKE-REFRESH-1").await
    }

    fn url(app: &TestApp, read: Read<'_>) -> String {
        app.state.spotify.read_url(read)
    }

    /// The fixture playlist's metadata and items, and its mosaic cover.
    fn answer_playlist(app: &TestApp) {
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

        // A day after the items were read: read again.
        app.fetch.clear_calls();
        let report = check(&app, id, t0() + TimeDelta::hours(25)).await;
        assert_eq!(report.requests, 2);
        assert_eq!(calls_to(&app, &items_url), 1);
        assert_eq!(
            listing(&app, id).await.spotify_items_at,
            Some(stamp(t0() + TimeDelta::hours(25)))
        );

        // Check now ignores the gate.
        let now = t0() + TimeDelta::hours(26);
        sweep::request_check(&app.db, id, now).await.unwrap();
        app.fetch.clear_calls();
        check(&app, id, now).await;
        assert_eq!(calls_to(&app, &items_url), 1);

        // A new snapshot: the items, a new list.
        let mut meta: Value = serde_json::from_slice(&fixture("playlist.json")).unwrap();
        meta["snapshot_id"] = json!("AAAAA0fakeSnapshotKveld2");
        app.fetch
            .answer(&url(&app, Read::Playlist(PLAYLIST)), 200, meta.to_string());
        let mut page: Value = serde_json::from_slice(&fixture("playlist_items.json")).unwrap();
        page["items"].as_array_mut().unwrap().truncate(1);
        app.fetch.answer(&items_url, 200, page.to_string());
        app.fetch.clear_calls();
        let report = check(&app, id, t0() + TimeDelta::hours(27)).await;
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
        let parent = store_account(&app, "fakeparent", "Pål", "FAKE-REFRESH-9").await;
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
        // Context mode is tried again at every check (no snapshot gate).
        app.fetch.clear_calls();
        check(&app, id, t0() + TimeDelta::hours(1)).await;
        assert_eq!(calls_to(&app, &items_url), 2);
        assert_eq!(listing(&app, id).await.version, Some(context.clone()));

        // The parent may list it now (the kid still may not): list mode, read by the parent.
        app.fetch
            .answer_once(&items_url, 403, fixture("forbidden.json"));
        app.fetch
            .answer(&items_url, 200, fixture("playlist_items.json"));
        app.fetch.clear_calls();
        let report = check(&app, id, t0() + TimeDelta::hours(2)).await;
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
        let now = t0() + TimeDelta::hours(3);
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
        let now = t0() + TimeDelta::hours(4);
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
        let parent = store_account(&app, "fakeparent", "Pål", "FAKE-REFRESH-9").await;
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
        store_account(&app, "fakeparent", "Pål", "FAKE-REFRESH-9").await;
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
        let parent = store_account(&app, "fakeparent", "Pål", "FAKE-REFRESH-9").await;
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
