//! Spotify on the server, step 1 of design 26c (§2, §7.1, §9.3, §10's routes rows): the app
//! setting (its 400s, the environment winning, the redirect suggestion from `Host` and
//! `X-Forwarded-*`), Clear and a new client ID removing the accounts and unbinding phones, the
//! PKCE login (the start's 303 and cookie, the callback without a session - `state` and the
//! cookie required, single use, 10 minutes -, `error`, `/me`'s 403, the paste), a new login of the
//! same account, Remove, the 180-day line and its warning, and that the code, the tokens and the
//! verifier reach no log line, event or page. `spotify::tests` holds the client's own.

use std::collections::HashMap;
use std::sync::{Arc, Mutex};

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
    assert!(app.state.spotify.take_pending(&first).is_none());
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
    // A missing cookie didn't use the login up: the right browser still completes it...
    // ...but not after 10 minutes.
    app.state.spotify.advance(spotify::PENDING_TTL);
    let res = callback(
        &app,
        &code_query(&state, "FAKE-CODE-1"),
        Some(&started.cookie),
    )
    .await;
    assert_eq!(res.location(), Some("/music/spotify/done?login=expired"));
    assert_eq!(app.fetch.count(), 0);
    assert!(account_rows(&app).await.is_empty());
    let page = app
        .get_page("/music/spotify?login=expired", &cookie)
        .await
        .text()
        .replace("&#39;", "'");
    assert!(page.contains("took longer than 10 minutes"));
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

/// Everything an event or a log line writes, at every level (a capturing subscriber on this
/// test's thread: `#[tokio::test]` runs every task here).
#[derive(Clone, Default)]
struct Captured(Arc<Mutex<Vec<String>>>);

impl<S: tracing::Subscriber> tracing_subscriber::Layer<S> for Captured {
    fn on_event(&self, event: &tracing::Event<'_>, _: tracing_subscriber::layer::Context<'_, S>) {
        struct Fields(String);
        impl tracing::field::Visit for Fields {
            fn record_debug(&mut self, field: &tracing::field::Field, value: &dyn std::fmt::Debug) {
                self.0.push_str(&format!(" {}={:?}", field.name(), value));
            }
        }
        let mut fields = Fields(event.metadata().target().to_string());
        event.record(&mut fields);
        self.0.lock().unwrap().push(fields.0);
    }
}

/// §7.1: the code, the tokens and the verifier reach no log line (any level, reqwest's floor
/// aside), no event and no page - through a login, a paste, a failed exchange and refreshes.
#[tokio::test]
async fn no_secret_in_logs_events_or_pages() {
    // tracing caches each callsite's interest process-wide, and another test's scoped subscriber
    // can race this one's registration (a parallel run): a fresh subscriber registers again and
    // rebuilds every callsite's interest, so a run that missed this code's lines is repeated.
    for attempt in 1..=3 {
        if secrets_stay_out(attempt == 3).await {
            return;
        }
    }
}

/// [no_secret_in_logs_events_or_pages]'s run; `false` when the capture missed this code's lines
/// (and `last` isn't set).
async fn secrets_stay_out(last: bool) -> bool {
    use tracing_subscriber::layer::SubscriberExt;
    let captured = Captured::default();
    let subscriber = tracing_subscriber::registry()
        .with(tracing_subscriber::filter::LevelFilter::TRACE)
        .with(captured.clone());
    let _guard = tracing::subscriber::set_default(subscriber);

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

    let mut texts: Vec<String> = captured.0.lock().unwrap().clone();
    // The capture sees this code's lines (the probe works).
    let seen = texts.iter().any(|t| t.contains("spotify: a login started"))
        && texts.iter().any(|t| t.contains("spotify: a login failed"));
    if !seen && !last {
        return false;
    }
    assert!(seen, "the capture missed this code's lines: {texts:?}");
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
    true
}
