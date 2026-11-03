//! Design 74 step 3: the app lock ("Lås med Face ID") - the setting behind a passkey, the lock's enforcement in
//! `security::admin_or_why` (a navigation to `/lock?next=`, anything else 423, every route locked or in
//! `lock::EXEMPT`), what counts as a use, the lock screen and its unlock, the cookie carried into a new sign-in, and
//! the lock turned off with the last passkey - through the real router, with the fake authenticator.

use axum::body::Body;
use axum::http::{Method, Request, StatusCode, header};
use tower_sessions::SessionStore;

use super::passkeys::{
    HOST, ORIGIN, PASSWORD, add_passkey, challenge_of, reauth_assertion, scalar, send,
};
use super::{LOOPBACK_PEER, TestApp, TestResponse};
use crate::webauthn::fake::FakeAuthenticator;

/// How a request reaches the server.
#[derive(Clone, Copy, PartialEq, Eq)]
enum Way {
    /// A tapped link or tab: a navigation with `Sec-Fetch-User: ?1`.
    Tap,
    /// A script's `location.reload()`: a navigation without it.
    Reload,
    /// A script's `fetch`.
    Fetch,
}

/// A request at [HOST] the way a browser sends it.
async fn browse(app: &TestApp, method: Method, uri: &str, cookie: &str, way: Way) -> TestResponse {
    let mut builder = Request::builder()
        .method(method.clone())
        .uri(uri)
        .header(header::HOST, HOST)
        .header(header::COOKIE, cookie)
        .header("sec-fetch-site", "same-origin");
    builder = match way {
        Way::Tap => builder
            .header("sec-fetch-mode", "navigate")
            .header("sec-fetch-user", "?1")
            .header(header::ACCEPT, "text/html"),
        Way::Reload => builder
            .header("sec-fetch-mode", "navigate")
            .header(header::ACCEPT, "text/html"),
        Way::Fetch => builder.header("sec-fetch-mode", "cors"),
    };
    let body = if method == Method::POST {
        builder = builder.header(header::CONTENT_TYPE, "application/x-www-form-urlencoded");
        Body::from("")
    } else {
        Body::empty()
    };
    app.send_via(&app.router, LOOPBACK_PEER, builder.body(body).unwrap())
        .await
}

/// The `Set-Cookie` named `name` (`handy`, `handy-lock`), as `name=value`.
fn set_cookie(res: &TestResponse, name: &str) -> Option<String> {
    res.headers
        .get_all(header::SET_COOKIE)
        .iter()
        .filter_map(|v| v.to_str().ok())
        .find(|v| v.starts_with(&format!("{name}=")))
        .map(str::to_string)
}

/// The session cookie after `res` (a cycled id), else `cookie`.
fn session_after(res: &TestResponse, cookie: &str) -> String {
    set_cookie(res, "handy")
        .and_then(|c| c.split(';').next().map(str::to_string))
        .unwrap_or_else(|| cookie.to_string())
}

fn session_id(cookie: &str) -> tower_sessions::session::Id {
    cookie
        .split(';')
        .next()
        .and_then(|c| c.split_once('='))
        .unwrap()
        .1
        .parse()
        .unwrap()
}

fn store(app: &TestApp) -> tower_sessions_sqlx_store::SqliteStore {
    tower_sessions_sqlx_store::SqliteStore::new(app.db.clone())
}

/// The session's `used_at`, Unix seconds.
async fn used_at(app: &TestApp, cookie: &str) -> Option<u64> {
    let record = store(app).load(&session_id(cookie)).await.unwrap()?;
    record.data.get(crate::lock::USED_AT_KEY)?.as_u64()
}

/// Puts the session's last use `secs` ago (as if the app lay unused that long).
async fn unused_for(app: &TestApp, cookie: &str, secs: u64) {
    let store = store(app);
    let mut record = store.load(&session_id(cookie)).await.unwrap().unwrap();
    record.data.insert(
        crate::lock::USED_AT_KEY.to_string(),
        serde_json::json!(crate::lock::now_secs() - secs),
    );
    store.save(&record).await.unwrap();
}

async fn session_minutes(app: &TestApp, cookie: &str) -> Option<u64> {
    let record = store(app).load(&session_id(cookie)).await.unwrap()?;
    record.data.get(crate::lock::MINUTES_KEY)?.as_u64()
}

/// Turns the lock to `minutes` behind the fake's assertion; the answer.
async fn set_lock(
    app: &TestApp,
    cookie: &str,
    fake: &mut FakeAuthenticator,
    minutes: &str,
) -> TestResponse {
    let assertion = reauth_assertion(app, cookie, fake, "/account/lock").await;
    send(
        app,
        Method::POST,
        "/account/lock",
        HOST,
        Some(cookie),
        &[("minutes", minutes), ("assertion", &assertion)],
    )
    .await
}

/// A parent with a passkey and the lock on at `minutes`: (the session cookie, the lock cookie, the fake).
async fn locked_parent(app: &TestApp, minutes: &str) -> (String, String, FakeAuthenticator) {
    let cookie = app.admin_cookie().await;
    let mut fake = FakeAuthenticator::es256(b"");
    add_passkey(app, &cookie, &mut fake, "iPhone").await;
    let res = set_lock(app, &cookie, &mut fake, minutes).await;
    assert_eq!(
        res.location(),
        Some("/account?done=lock#app-lock"),
        "{}",
        res.text()
    );
    let lock = set_cookie(&res, "handy-lock").expect("the lock cookie");
    let lock = lock.split(';').next().unwrap().to_string();
    (session_after(&res, &cookie), lock, fake)
}

/// The unlock: the options, then the fake's assertion for them (`next` as the form sends it).
async fn unlock(
    app: &TestApp,
    cookie: &str,
    fake: &mut FakeAuthenticator,
    next: &str,
) -> TestResponse {
    let options = send(app, Method::POST, "/lock/options", HOST, Some(cookie), &[]).await;
    let json = options.json();
    assert_eq!(json["userVerification"], "required");
    assert_eq!(json["allowCredentials"].as_array().unwrap().len(), 1);
    let assertion = fake.assert(&challenge_of(&options), ORIGIN, HOST, false);
    send(
        app,
        Method::POST,
        "/lock",
        HOST,
        Some(cookie),
        &[("assertion", &assertion), ("next", next)],
    )
    .await
}

/// D3.1/D3.2: the setting needs a passkey that works here - none, no change; an empty or bad assertion changes
/// nothing (a bad one counts as a wrong re-auth); a good one keeps the time in the session and the cookie, logs
/// `app_lock_set` and lands on `#app-lock` with "Saved"; Off deletes the cookie.
#[tokio::test]
async fn turning_the_lock_on_needs_a_passkey_that_works_here() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    // No passkey: the row is dimmed with its reason, and the POST is refused.
    let page = send(&app, Method::GET, "/account", HOST, Some(&cookie), &[])
        .await
        .text();
    assert!(page.contains("id=\"app-lock\""), "{page}");
    assert!(page.contains("data-row=\"app-lock-disabled\""));
    assert!(page.contains("Add a passkey on this device first."));
    let refused = send(
        &app,
        Method::POST,
        "/account/lock",
        HOST,
        Some(&cookie),
        &[("minutes", "5"), ("assertion", "{}")],
    )
    .await;
    assert_eq!(refused.status, StatusCode::BAD_REQUEST);
    assert_eq!(session_minutes(&app, &cookie).await, None);

    let mut fake = FakeAuthenticator::es256(b"");
    add_passkey(&app, &cookie, &mut fake, "iPhone").await;
    let page = send(&app, Method::GET, "/account", HOST, Some(&cookie), &[])
        .await
        .text();
    // Off: a switch that turns it on at 5 minutes, behind the passkey (shown by passkey.js).
    assert!(page.contains("data-app-lock"), "{page}");
    assert_eq!(super::input_checked(&page, "minutes", "5"), Some(false));
    super::settings_pages::check_forms("/account", &page);
    // An empty assertion (a password can't do it) and a time that isn't one of the lock's.
    for fields in [
        &[("minutes", "5"), ("assertion", "")][..],
        &[("minutes", "5"), ("current_password", PASSWORD)][..],
    ] {
        let res = send(
            &app,
            Method::POST,
            "/account/lock",
            HOST,
            Some(&cookie),
            fields,
        )
        .await;
        assert_eq!(res.status, StatusCode::BAD_REQUEST);
        assert!(
            res.text()
                .contains("Confirm with a passkey. Nothing was changed.")
        );
    }
    let res = send(
        &app,
        Method::POST,
        "/account/lock",
        HOST,
        Some(&cookie),
        &[("minutes", "2"), ("assertion", "x")],
    )
    .await;
    assert_eq!(res.status, StatusCode::BAD_REQUEST);
    // A passkey that isn't the parent's: refused, counted.
    let mut stranger = FakeAuthenticator::es256(b"");
    let res = set_lock(&app, &cookie, &mut stranger, "5").await;
    assert_eq!(res.status, StatusCode::BAD_REQUEST);
    assert_eq!(
        scalar(
            &app,
            "SELECT reauth_wrong FROM admin_users WHERE username = 'parent'"
        )
        .await,
        1
    );
    assert_eq!(session_minutes(&app, &cookie).await, None);

    let res = set_lock(&app, &cookie, &mut fake, "5").await;
    assert_eq!(res.location(), Some("/account?done=lock#app-lock"));
    let lock = set_cookie(&res, "handy-lock").unwrap();
    assert!(lock.starts_with("handy-lock=5; Path=/;"), "{lock}");
    assert!(
        lock.contains("HttpOnly")
            && lock.contains("SameSite=Strict")
            && lock.contains("Max-Age=34560000")
    );
    assert_eq!(session_minutes(&app, &cookie).await, Some(5));
    assert!(used_at(&app, &cookie).await.is_some());
    app.assert_lands(&res, &cookie).await;
    let page = send(
        &app,
        Method::GET,
        "/account?done=lock",
        HOST,
        Some(&cookie),
        &[],
    )
    .await
    .text();
    assert!(page.contains("data-flash=\"app-lock\""), "{page}");
    // On: the picker with the session's time, every time and Off; the page carries the lock's numbers.
    assert!(
        page.contains("<option value=\"5\" selected>5 min</option>"),
        "{page}"
    );
    for choice in ["0\">Off", "1\">1 min", "15\">15 min", "60\">1 hour"] {
        assert!(
            page.contains(&format!("<option value=\"{choice}</option>")),
            "{choice}"
        );
    }
    assert!(
        page.contains("<html data-lock-ms=\"300000\" data-lock-idle=\""),
        "{page}"
    );
    assert_eq!(
        scalar(
            &app,
            "SELECT COUNT(*) FROM security_events WHERE event_type = 'app_lock_set'"
        )
        .await,
        1
    );
    assert_eq!(
        super::security_words(&app, "app_lock_set", crate::i18n::Lang::Nb).await,
        vec!["5 min".to_string()]
    );

    // Off (behind the passkey too) deletes the cookie and the session's lock.
    let res = set_lock(&app, &cookie, &mut fake, "0").await;
    assert_eq!(res.location(), Some("/account?done=lock#app-lock"));
    assert!(
        set_cookie(&res, "handy-lock")
            .unwrap()
            .contains("Max-Age=0")
    );
    assert_eq!(session_minutes(&app, &cookie).await, None);
    let page = send(&app, Method::GET, "/account", HOST, Some(&cookie), &[])
        .await
        .text();
    assert!(!page.contains("data-lock-ms"));
}

/// D3.4: past its time a navigation goes to `/lock?next=<path?query>` (a notification's path kept), a fetch, the ping
/// and `/api/session` answer 423, a save is refused; the lock screen itself, its options and Sign Out still answer.
/// Before it, everything is served.
#[tokio::test]
async fn past_its_time_the_app_is_locked() {
    let app = TestApp::new().await;
    let (cookie, _, _) = locked_parent(&app, "5").await;
    assert_eq!(
        browse(&app, Method::GET, "/devices", &cookie, Way::Tap)
            .await
            .status,
        StatusCode::OK
    );
    assert_eq!(
        browse(&app, Method::GET, "/api/session", &cookie, Way::Fetch)
            .await
            .status,
        StatusCode::NO_CONTENT
    );

    unused_for(&app, &cookie, 6 * 60 + 1).await;
    // A tapped notification's page (the service worker opens it): kept in `next`, `?push=` included.
    let res = browse(&app, Method::GET, "/devices/3?push=ab12", &cookie, Way::Tap).await;
    assert_eq!(res.status, StatusCode::SEE_OTHER);
    assert_eq!(
        res.location(),
        Some("/lock?next=%2Fdevices%2F3%3Fpush%3Dab12")
    );
    assert_eq!(res.headers[header::CACHE_CONTROL], "no-store");
    // A reload, the ping, the beacon, a poll, the session check: locked too, and nothing moves the use.
    let before = used_at(&app, &cookie).await;
    assert_eq!(
        browse(&app, Method::GET, "/devices", &cookie, Way::Reload)
            .await
            .location(),
        Some("/lock?next=%2Fdevices")
    );
    for (method, uri) in [
        (Method::GET, "/ping"),
        (Method::GET, "/api/session"),
        (Method::POST, "/api/used"),
        (Method::GET, "/notifications/subscriptions.json"),
        (Method::POST, "/account/name"),
    ] {
        let res = browse(&app, method, uri, &cookie, Way::Fetch).await;
        assert_eq!(res.status, StatusCode::LOCKED, "{uri}");
        assert!(res.body.is_empty(), "{uri}");
    }
    // A form's POST (a navigation) comes back to the start: nothing of what it sent is in the address.
    let res = browse(&app, Method::POST, "/account/name", &cookie, Way::Tap).await;
    assert_eq!(res.location(), Some("/lock"));
    assert_eq!(used_at(&app, &cookie).await, before);

    // The lock screen: no tab bar, no-store, nothing private.
    let screen = browse(
        &app,
        Method::GET,
        "/lock?next=%2Fdevices%2F3%3Fpush%3Dab12",
        &cookie,
        Way::Tap,
    )
    .await;
    assert_eq!(screen.status, StatusCode::OK);
    assert_eq!(screen.headers[header::CACHE_CONTROL], "no-store");
    let html = screen.text();
    assert!(html.contains("<title>Vibb</title>"), "{html}");
    assert!(!html.contains("tab-bar"));
    assert!(!html.contains("data-lock-ms"));
    assert!(!html.contains("parent"), "the name shows: {html}");
    assert!(html.contains("value=\"/devices/3?push=ab12\""));
    assert!(html.contains("action=\"/logout\""));
    // The whole screen is Unlock (§6.5, the user's pick A "Pust", 2026-11-03), the breathing mark inline; Sign Out is
    // its own form after the unlock form, so it never starts the prompt.
    assert!(html.contains("<h1 class=\"lock-title\">Vibb is locked</h1>"));
    assert!(html.contains("<svg class=\"vibb-breath\""));
    let tap = html
        .find("data-passkey-unlock-button")
        .expect("the tap target");
    assert!(html[..tap].contains("id=\"unlock\""));
    assert!(html[tap..].contains("aria-label=\"Unlock\""));
    let unlock_end = tap + html[tap..].find("</form>").unwrap();
    assert!(html[unlock_end..].contains("action=\"/logout\""));
    // An open redirect in `next` is dropped.
    let html = browse(
        &app,
        Method::GET,
        "/lock?next=%2F%2Fevil.example",
        &cookie,
        Way::Tap,
    )
    .await
    .text();
    assert!(html.contains("name=\"next\" value=\"\""), "{html}");
    // Sign Out works locked, and keeps where the parent was going for the next sign-in.
    let out = send(
        &app,
        Method::POST,
        "/logout",
        HOST,
        Some(&cookie),
        &[("endpoint", ""), ("next", "/devices/3")],
    )
    .await;
    assert_eq!(out.location(), Some("/login?next=%2Fdevices%2F3"));
    assert_eq!(
        browse(&app, Method::GET, "/devices", &cookie, Way::Tap)
            .await
            .location(),
        Some("/login")
    );
}

/// D3.3: only a use moves `used_at` - a tapped navigation, a save and the beacon; a scripted reload and a script's
/// GET never do. A tap moves it only once it moved 30 s; the beacon always.
#[tokio::test]
async fn only_a_use_moves_the_last_use() {
    let app = TestApp::new().await;
    let (cookie, _, _) = locked_parent(&app, "5").await;
    for (method, uri, way) in [
        (Method::GET, "/devices", Way::Reload),
        (Method::GET, "/ping", Way::Fetch),
        (Method::GET, "/api/session", Way::Fetch),
        (Method::GET, "/notifications/subscriptions.json", Way::Fetch),
    ] {
        unused_for(&app, &cookie, 120).await;
        let before = used_at(&app, &cookie).await.unwrap();
        let res = browse(&app, method, uri, &cookie, way).await;
        assert!(res.status.is_success(), "{uri}: {}", res.status);
        assert_eq!(used_at(&app, &cookie).await, Some(before), "{uri} moved it");
    }
    for (method, uri, way) in [
        (Method::GET, "/devices", Way::Tap),
        (Method::POST, "/api/used", Way::Fetch),
        (Method::POST, "/account/language", Way::Tap),
    ] {
        unused_for(&app, &cookie, 120).await;
        let before = used_at(&app, &cookie).await.unwrap();
        browse(&app, method, uri, &cookie, way).await;
        let after = used_at(&app, &cookie).await.unwrap();
        assert!(after >= before + 119, "{uri} didn't move it");
    }
    // Under 30 s a tap writes nothing; the beacon still does.
    unused_for(&app, &cookie, 10).await;
    let before = used_at(&app, &cookie).await.unwrap();
    browse(&app, Method::GET, "/devices", &cookie, Way::Tap).await;
    assert_eq!(used_at(&app, &cookie).await, Some(before));
    let res = browse(&app, Method::POST, "/api/used", &cookie, Way::Fetch).await;
    assert_eq!(res.status, StatusCode::NO_CONTENT);
    assert!(used_at(&app, &cookie).await.unwrap() >= before + 9);
    // The page's numbers: the lock time and how long since the last use.
    let page = browse(&app, Method::GET, "/devices", &cookie, Way::Reload)
        .await
        .text();
    assert!(page.contains("data-lock-ms=\"300000\""), "{page}");
}

/// D3.3: the server locks a minute after the page (the beacon's minute), so a save tapped just in time is served.
#[tokio::test]
async fn the_server_waits_a_minute_past_the_page() {
    let app = TestApp::new().await;
    let (cookie, _, _) = locked_parent(&app, "1").await;
    unused_for(&app, &cookie, 60 + 50).await;
    assert_eq!(
        browse(&app, Method::GET, "/api/session", &cookie, Way::Fetch)
            .await
            .status,
        StatusCode::NO_CONTENT
    );
    unused_for(&app, &cookie, 60 + 60).await;
    assert_eq!(
        browse(&app, Method::GET, "/api/session", &cookie, Way::Fetch)
            .await
            .status,
        StatusCode::LOCKED
    );
}

/// D3.5, D3.7: the unlock - the parent's own passkey only (another parent's, a bad one: `app_unlock_failed`, counted
/// in `reauth_wrong`, the third ends every session); done: `{next}` (a local one only), the use moved, the session id
/// cycled, the sign-in time kept, `app_unlocked` merged per hour.
#[tokio::test]
async fn the_unlock_takes_the_parents_own_passkey() {
    let app = TestApp::new().await;
    let (cookie, _, mut fake) = locked_parent(&app, "5").await;
    // Another ready parent with a passkey of their own.
    let pappa = app.admin_cookie_as("pappa", "Pappa").await;
    let mut theirs = FakeAuthenticator::es256(b"");
    add_passkey(&app, &pappa, &mut theirs, "Pappa's phone").await;

    // A re-auth challenge (fetched before the lock: locked, its route answers 423) isn't an unlock's.
    let reauth = reauth_assertion(&app, &cookie, &mut fake, "/account/lock").await;
    unused_for(&app, &cookie, 7 * 60).await;
    let signed_in_at = || async {
        let record = store(&app)
            .load(&session_id(&cookie))
            .await
            .unwrap()
            .unwrap();
        record
            .data
            .get(crate::admin_auth::SIGNED_IN_AT_KEY)
            .cloned()
    };
    let first_sign_in = signed_in_at().await;

    // Pappa's passkey (for an unlock challenge of this session) doesn't open it.
    let options = send(
        &app,
        Method::POST,
        "/lock/options",
        HOST,
        Some(&cookie),
        &[],
    )
    .await;
    let assertion = theirs.assert(&challenge_of(&options), ORIGIN, HOST, true);
    let res = send(
        &app,
        Method::POST,
        "/lock",
        HOST,
        Some(&cookie),
        &[("assertion", &assertion)],
    )
    .await;
    assert_eq!(res.status, StatusCode::BAD_REQUEST);
    assert_eq!(
        res.json()["error"],
        "Couldn't unlock. Try again, or sign out and sign in again."
    );
    let res = send(
        &app,
        Method::POST,
        "/lock",
        HOST,
        Some(&cookie),
        &[("assertion", &reauth)],
    )
    .await;
    assert_eq!(res.status, StatusCode::BAD_REQUEST);
    assert_eq!(
        scalar(
            &app,
            "SELECT COUNT(*) FROM security_events WHERE event_type = 'app_unlock_failed'"
        )
        .await,
        2
    );
    assert_eq!(
        scalar(
            &app,
            "SELECT reauth_wrong FROM admin_users WHERE username = 'parent'"
        )
        .await,
        2
    );

    let res = unlock(&app, &cookie, &mut fake, "//evil.example").await;
    assert_eq!(res.status, StatusCode::OK, "{}", res.text());
    assert_eq!(res.json()["next"], "/");
    let fresh = session_after(&res, &cookie);
    assert_ne!(fresh, cookie, "the id is cycled");
    assert_eq!(
        browse(&app, Method::GET, "/devices", &fresh, Way::Reload)
            .await
            .status,
        StatusCode::OK
    );
    assert_eq!(
        scalar(
            &app,
            "SELECT reauth_wrong FROM admin_users WHERE username = 'parent'"
        )
        .await,
        0
    );
    let record = store(&app)
        .load(&session_id(&fresh))
        .await
        .unwrap()
        .unwrap();
    assert_eq!(
        record
            .data
            .get(crate::admin_auth::SIGNED_IN_AT_KEY)
            .cloned(),
        first_sign_in
    );

    // Locked again and opened again within the hour: one `app_unlocked` row, "×2"; `next` kept.
    unused_for(&app, &fresh, 7 * 60).await;
    let res = unlock(&app, &fresh, &mut fake, "/devices/3?push=ab12").await;
    assert_eq!(res.json()["next"], "/devices/3?push=ab12");
    let rows: Vec<(Option<String>,)> =
        sqlx::query_as("SELECT detail FROM security_events WHERE event_type = 'app_unlocked'")
            .fetch_all(&app.db)
            .await
            .unwrap();
    assert_eq!(rows, vec![(Some("×2".to_string()),)]);
}

/// The user 2026-11-03 (the bank app's Face ID): the options' `hint` - the passkey this browser unlocked with last -
/// narrows `allowCredentials` to that one only when it is one of this parent's passkeys here; another parent's, an
/// unknown, a garbage or an oversized hint, and none, offer every passkey of the parent's. The narrowed challenge
/// opens the lock as any other.
#[tokio::test]
async fn the_hint_narrows_the_unlock_to_the_parents_own_passkey() {
    let app = TestApp::new().await;
    let (cookie, _, _) = locked_parent(&app, "5").await;
    let mut second = FakeAuthenticator::es256(b"");
    add_passkey(&app, &cookie, &mut second, "iPad").await;
    let pappa = app.admin_cookie_as("pappa", "Pappa").await;
    let mut theirs = FakeAuthenticator::es256(b"");
    add_passkey(&app, &pappa, &mut theirs, "Pappa's phone").await;
    unused_for(&app, &cookie, 7 * 60).await;

    let offered = |hint: Option<String>| {
        let app = &app;
        let cookie = cookie.clone();
        async move {
            let fields: Vec<(&str, &str)> =
                hint.as_deref().map(|h| ("hint", h)).into_iter().collect();
            let res = send(
                app,
                Method::POST,
                "/lock/options",
                HOST,
                Some(&cookie),
                &fields,
            )
            .await;
            assert_eq!(res.status, StatusCode::OK, "{}", res.text());
            let ids: Vec<String> = res.json()["allowCredentials"]
                .as_array()
                .unwrap()
                .iter()
                .map(|c| c["id"].as_str().unwrap().to_string())
                .collect();
            (ids, res)
        }
    };
    let own = crate::webauthn::b64url(&second.credential_id);
    let (ids, options) = offered(Some(own.clone())).await;
    assert_eq!(ids, vec![own.clone()], "only the hinted passkey");
    for hint in [
        None,
        Some(String::new()),
        Some(crate::webauthn::b64url(&theirs.credential_id)),
        Some(crate::webauthn::b64url(&[7u8; 32])),
        Some("not base64 at all!".to_string()),
        Some(format!("{own}=")),
        Some("A".repeat(2048)),
    ] {
        let (ids, _) = offered(hint.clone()).await;
        assert_eq!(ids.len(), 2, "every passkey of the parent's for {hint:?}");
        assert!(ids.contains(&own), "{hint:?}");
        assert!(
            !ids.contains(&crate::webauthn::b64url(&theirs.credential_id)),
            "never another parent's ({hint:?})"
        );
    }

    let assertion = second.assert(&challenge_of(&options), ORIGIN, HOST, false);
    let res = send(
        &app,
        Method::POST,
        "/lock",
        HOST,
        Some(&cookie),
        &[("assertion", &assertion), ("next", "/devices")],
    )
    .await;
    assert_eq!(res.status, StatusCode::OK, "{}", res.text());
    assert_eq!(res.json()["next"], "/devices");
}

/// D3.5: three bad unlocks in a row end every session (as the re-auth forms).
#[tokio::test]
async fn three_bad_unlocks_sign_out() {
    let app = TestApp::new().await;
    let (cookie, _, _) = locked_parent(&app, "5").await;
    unused_for(&app, &cookie, 7 * 60).await;
    let mut last = None;
    for _ in 0..3 {
        let options = send(
            &app,
            Method::POST,
            "/lock/options",
            HOST,
            Some(&cookie),
            &[],
        )
        .await;
        let mut stranger = FakeAuthenticator::es256(b"");
        let assertion = stranger.assert(&challenge_of(&options), ORIGIN, HOST, false);
        last = Some(
            send(
                &app,
                Method::POST,
                "/lock",
                HOST,
                Some(&cookie),
                &[("assertion", &assertion)],
            )
            .await,
        );
    }
    let last = last.unwrap();
    assert_eq!(last.status, StatusCode::UNAUTHORIZED);
    assert_eq!(last.json()["signed_out"], true);
    assert_eq!(
        browse(&app, Method::GET, "/devices", &cookie, Way::Tap)
            .await
            .location(),
        Some("/login")
    );
}

/// D3.1: a new sign-in in this browser takes the lock cookie's time - only while that parent has a passkey for this
/// RP ID (a password sign-in and a passkey sign-in alike); without one the lock stays off and the cookie is left.
#[tokio::test]
async fn the_cookie_comes_back_with_a_sign_in_that_can_open_it() {
    let app = TestApp::new().await;
    let (cookie, lock, mut fake) = locked_parent(&app, "15").await;
    assert_eq!(lock, "handy-lock=15");
    send(&app, Method::POST, "/logout", HOST, Some(&cookie), &[]).await;

    // A passkey sign-in in this browser (its options with the lock cookie along).
    let options = send(
        &app,
        Method::POST,
        "/auth/passkey/options",
        HOST,
        Some(&lock),
        &[],
    )
    .await;
    let pre = super::session_cookie(&options).unwrap();
    fake.user_handle =
        sqlx::query_scalar("SELECT webauthn_user_id FROM admin_users WHERE username = 'parent'")
            .fetch_one(&app.db)
            .await
            .unwrap();
    let assertion = fake.assert(&challenge_of(&options), ORIGIN, HOST, true);
    let res = send(
        &app,
        Method::POST,
        "/auth/passkey",
        HOST,
        Some(&format!("{pre}; {lock}")),
        &[("assertion", &assertion), ("next", "/devices/3")],
    )
    .await;
    // Step 3: a passkey sign-in honours a local `next`.
    assert_eq!(res.location(), Some("/devices/3"), "{}", res.text());
    let signed_in = session_after(&res, &pre);
    assert_eq!(session_minutes(&app, &signed_in).await, Some(15));
    assert!(used_at(&app, &signed_in).await.is_some());

    // Another parent in this browser, with no passkey here: the lock stays off, the cookie is left alone.
    let pappa = app.admin_cookie_as("pappa", "Pappa").await;
    send(&app, Method::POST, "/logout", HOST, Some(&pappa), &[]).await;
    let login = send(
        &app,
        Method::POST,
        "/login",
        HOST,
        Some(&lock),
        &[("username", "pappa"), ("password", PASSWORD)],
    )
    .await;
    let pending = super::session_cookie(&login).unwrap();
    let code = app
        .next_totp_code("pappa", "JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP")
        .await;
    let res = send(
        &app,
        Method::POST,
        "/auth/verify-2fa",
        HOST,
        Some(&format!("{pending}; {lock}")),
        &[("code", &code)],
    )
    .await;
    assert_eq!(res.location(), Some("/"));
    assert!(set_cookie(&res, "handy-lock").is_none());
    assert_eq!(
        session_minutes(&app, &session_after(&res, &pending)).await,
        None
    );

    // A password sign-in of the parent with the passkey here: back on.
    let login = send(
        &app,
        Method::POST,
        "/login",
        HOST,
        Some(&lock),
        &[("username", "parent"), ("password", PASSWORD)],
    )
    .await;
    let pending = super::session_cookie(&login).unwrap();
    let code = app
        .next_totp_code("parent", "JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP")
        .await;
    let res = send(
        &app,
        Method::POST,
        "/auth/verify-2fa",
        HOST,
        Some(&format!("{pending}; {lock}")),
        &[("code", &code)],
    )
    .await;
    assert_eq!(
        session_minutes(&app, &session_after(&res, &pending)).await,
        Some(15)
    );
}

/// D3.1: the last passkey for this RP ID removed, this session's lock goes off with the cookie (the other sessions
/// ended with the removal), so nothing loops on a lock it can't open.
#[tokio::test]
async fn losing_the_last_passkey_turns_the_lock_off() {
    let app = TestApp::new().await;
    let (cookie, _, _) = locked_parent(&app, "5").await;
    let id: i64 = scalar(&app, "SELECT id FROM admin_passkeys").await;
    let res = send(
        &app,
        Method::POST,
        &format!("/account/passkeys/{id}/remove"),
        HOST,
        Some(&cookie),
        &[("current_password", PASSWORD)],
    )
    .await;
    assert_eq!(
        res.location(),
        Some("/account?done=passkey_removed#passkeys")
    );
    assert!(
        set_cookie(&res, "handy-lock")
            .unwrap()
            .contains("Max-Age=0")
    );
    assert_eq!(session_minutes(&app, &cookie).await, None);
    unused_for(&app, &cookie, 3600).await;
    assert_eq!(
        browse(&app, Method::GET, "/devices", &cookie, Way::Tap)
            .await
            .status,
        StatusCode::OK
    );
    // With the lock off the lock screen has nothing to open: on to `next`.
    assert_eq!(
        browse(
            &app,
            Method::GET,
            "/lock?next=%2Fsettings",
            &cookie,
            Way::Tap
        )
        .await
        .location(),
        Some("/settings")
    );
}

/// D3.4: every route of the admin router - the admin, host, `require_session`, JSON and lock groups, `/api/session` -
/// either answers a locked session with the lock (a navigation 303 to `/lock`, a fetch 423) or is in `lock::EXEMPT`,
/// which is probed with its own methods; every exempt route is one (the list holds no stale entry). Read from
/// main.rs's own `.route(` calls, with the router's merges and services pinned (a group added another way would be
/// unseen) and every phone route under `/api/devices/` (qa-passkeys-3 server L2).
#[tokio::test]
async fn every_route_is_locked_or_exempt() {
    let app = TestApp::new().await;
    let (cookie, _, mut fake) = locked_parent(&app, "1").await;
    let main = include_str!("../main.rs");
    let router = &main[main.find("pub fn build_admin_router(").unwrap()..];
    let router = &router[..router.find("\n}\n").unwrap()];
    // Every way a route joins the router: the known groups, the phone API and the static files - nothing else.
    let joins: std::collections::BTreeSet<String> = regex::Regex::new(
        r"\.(merge|nest|nest_service|route_service|fallback_service)\(\s*([^\s(),]+)",
    )
    .unwrap()
    .captures_iter(router)
    .map(|c| format!("{} {}", &c[1], &c[2]))
    .collect();
    let known: std::collections::BTreeSet<String> = [
        "merge public_routes",
        "merge onboarding_routes",
        "merge lock_routes",
        "merge admin_routes",
        "merge host_routes",
        "merge device_routes::device_routes",
        "nest_service \"/static\"",
    ]
    .into_iter()
    .map(str::to_string)
    .collect();
    assert_eq!(joins, known, "a new way into the router: walk it here");
    let device = include_str!("../device_routes.rs");
    let mut rest = device;
    while let Some(at) = rest.find(".route(") {
        rest = &rest[at + ".route(".len()..];
        let path = rest.trim_start().trim_start_matches('"');
        assert!(
            path.starts_with("/api/devices/"),
            "a phone route outside /api/devices/: {}",
            &path[..40.min(path.len())]
        );
    }

    // (path, methods) of every `.route(` call.
    let get = regex::Regex::new(r"(^|[^a-z_])get\(").unwrap();
    let post = regex::Regex::new(r"(^|[^a-z_])post\(").unwrap();
    let mut routes: Vec<(String, Vec<Method>)> = Vec::new();
    let mut rest = router;
    while let Some(at) = rest.find(".route(") {
        rest = &rest[at + ".route(".len()..];
        let arg = rest.trim_start();
        let path = if let Some(quoted) = arg.strip_prefix('"') {
            quoted[..quoted.find('"').unwrap()].to_string()
        } else {
            match &arg[..arg.find(',').unwrap()] {
                "lock::BEACON_ROUTE" => crate::lock::BEACON_ROUTE.to_string(),
                "lock::LOCK_ROUTE" => crate::lock::LOCK_ROUTE.to_string(),
                "lock::OPTIONS_ROUTE" => crate::lock::OPTIONS_ROUTE.to_string(),
                other => panic!("a route named by {other}: teach this test its path"),
            }
        };
        let own = &rest[..rest.find(".route(").unwrap_or(rest.len())];
        let mut methods = Vec::new();
        if get.is_match(own) {
            methods.push(Method::GET);
        }
        if post.is_match(own) {
            methods.push(Method::POST);
        }
        assert!(!methods.is_empty(), "{path}: no method read");
        routes.push((path, methods));
    }
    assert!(routes.len() > 150, "{} routes read", routes.len());
    for exempt in crate::lock::EXEMPT {
        assert!(
            routes.iter().any(|(r, _)| r == exempt),
            "{exempt} is no route"
        );
    }
    unused_for(&app, &cookie, 3600).await;
    let fill = |route: &str| {
        route
            .split('/')
            .map(|part| if part.starts_with('{') { "1" } else { part })
            .collect::<Vec<_>>()
            .join("/")
    };
    // `/api/session` asks for no exemption: locked, it is 423 whatever the request.
    for way in [Way::Fetch, Way::Tap] {
        assert_eq!(
            browse(&app, Method::GET, "/api/session", &cookie, way)
                .await
                .status,
            StatusCode::LOCKED
        );
    }
    let not_locked = |res: &TestResponse| {
        res.status != StatusCode::LOCKED
            && !res.location().is_some_and(|to| to.starts_with("/lock"))
    };
    for (route, methods) in &routes {
        let exempt = crate::lock::EXEMPT.contains(&route.as_str());
        if route == "/api/session" || exempt {
            continue;
        }
        let uri = fill(route);
        let navigation = browse(&app, Method::GET, &uri, &cookie, Way::Tap).await;
        let fetch = browse(&app, Method::POST, &uri, &cookie, Way::Fetch).await;
        let locked = navigation
            .location()
            .is_some_and(|to| to == "/lock" || to.starts_with("/lock?next="))
            && fetch.status == StatusCode::LOCKED;
        assert!(
            locked,
            "{route} ({methods:?}): neither locked nor exempt ({} / {})",
            navigation.status, fetch.status
        );
    }
    // The exempt ones, each by its own methods. Sign Out and the code step (its page too, without a code pending) end
    // the session they come with: each gets a locked session of its own.
    let ending = ["/logout", "/auth/verify-2fa"];
    for (route, methods) in routes
        .iter()
        .filter(|(r, _)| crate::lock::EXEMPT.contains(&r.as_str()))
    {
        let uri = fill(route);
        for method in methods {
            let session = if ending.contains(&route.as_str()) {
                let other = app.admin_cookie().await;
                let res = set_lock(&app, &other, &mut fake, "1").await;
                let other = session_after(&res, &other);
                unused_for(&app, &other, 3600).await;
                assert_eq!(
                    browse(&app, Method::GET, "/devices", &other, Way::Tap)
                        .await
                        .location(),
                    Some("/lock?next=%2Fdevices"),
                    "the fresh session is locked"
                );
                other
            } else {
                cookie.clone()
            };
            let way = if *method == Method::GET {
                Way::Tap
            } else {
                Way::Fetch
            };
            let res = browse(&app, method.clone(), &uri, &session, way).await;
            assert!(
                not_locked(&res),
                "{method} {route} is exempt but locked ({})",
                res.status
            );
            assert_ne!(
                res.status,
                StatusCode::METHOD_NOT_ALLOWED,
                "{method} {route}"
            );
            if route == crate::lock::OPTIONS_ROUTE {
                assert_eq!(
                    res.status,
                    StatusCode::OK,
                    "the unlock's options while locked"
                );
            }
        }
    }
    // Still locked after the probes (none of them opened it).
    assert_eq!(
        browse(&app, Method::GET, "/devices", &cookie, Way::Tap)
            .await
            .location(),
        Some("/lock?next=%2Fdevices")
    );
    for file in [
        "/static/style.css",
        "/static/manifest.webmanifest",
        "/sw.js",
        "/healthz",
    ] {
        let res = browse(&app, Method::GET, file, &cookie, Way::Fetch).await;
        assert!(res.status.is_success(), "{file}: {}", res.status);
    }
}

/// qa-passkeys-3 client M1: a lock-on session's pages are never kept (`no-store`, so a history load goes to the server
/// and is sent to /lock), and the answer that turns the lock on clears the pages this browser kept from before.
#[tokio::test]
async fn a_lock_on_sessions_pages_are_never_kept() {
    let app = TestApp::new().await;
    let cookie = app.admin_cookie().await;
    let before = browse(&app, Method::GET, "/devices", &cookie, Way::Tap).await;
    assert_eq!(before.headers[header::CACHE_CONTROL], "private, no-cache");
    let mut fake = FakeAuthenticator::es256(b"");
    add_passkey(&app, &cookie, &mut fake, "iPhone").await;
    let on = set_lock(&app, &cookie, &mut fake, "5").await;
    assert_eq!(on.headers["clear-site-data"], "\"cache\"");
    for page in ["/devices", "/settings", "/devices/locate"] {
        let res = browse(&app, Method::GET, page, &cookie, Way::Tap).await;
        assert_eq!(res.status, StatusCode::OK, "{page}");
        assert_eq!(res.headers[header::CACHE_CONTROL], "no-store", "{page}");
    }
    // Another time keeps it on (cleared again, harmless); Off clears nothing more and the pages are kept again.
    let off = set_lock(&app, &cookie, &mut fake, "0").await;
    assert!(off.headers.get("clear-site-data").is_none());
    let after = browse(&app, Method::GET, "/devices", &cookie, Way::Tap).await;
    assert_eq!(after.headers[header::CACHE_CONTROL], "private, no-cache");
}

/// qa-passkeys-3 server L1: a new sign-in in a locked session starts unlocked - another parent's password sign-in in
/// it (no lock cookie, no passkey) isn't sent to a lock it could never open.
#[tokio::test]
async fn a_new_sign_in_in_a_locked_session_starts_unlocked() {
    let app = TestApp::new().await;
    let (cookie, _, _) = locked_parent(&app, "5").await;
    app.admin_cookie_as("pappa", "Pappa").await;
    unused_for(&app, &cookie, 3600).await;
    assert_eq!(
        browse(&app, Method::GET, "/devices", &cookie, Way::Tap)
            .await
            .location(),
        Some("/lock?next=%2Fdevices")
    );
    let login = send(
        &app,
        Method::POST,
        "/login",
        HOST,
        Some(&cookie),
        &[("username", "pappa"), ("password", PASSWORD)],
    )
    .await;
    assert_eq!(login.location(), Some("/auth/verify-2fa"));
    let pending = session_after(&login, &cookie);
    let code = app
        .next_totp_code("pappa", "JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP")
        .await;
    let res = send(
        &app,
        Method::POST,
        "/auth/verify-2fa",
        HOST,
        Some(&pending),
        &[("code", &code)],
    )
    .await;
    assert_eq!(res.location(), Some("/"));
    let signed_in = session_after(&res, &pending);
    assert_eq!(session_minutes(&app, &signed_in).await, None);
    assert_eq!(
        browse(&app, Method::GET, "/devices", &signed_in, Way::Tap)
            .await
            .status,
        StatusCode::OK
    );
}

/// qa-passkeys-3 server N2/N3: a locked form POST goes back to the page it was sent from (its Referer, this host's
/// only); the unlock's form takes an assertion past 4 KiB.
#[tokio::test]
async fn a_locked_form_comes_back_to_its_page() {
    let app = TestApp::new().await;
    let (cookie, _, _) = locked_parent(&app, "5").await;
    unused_for(&app, &cookie, 3600).await;
    let post = |referer: &'static str| {
        let request = Request::builder()
            .method(Method::POST)
            .uri("/account/name")
            .header(header::HOST, HOST)
            .header(header::COOKIE, cookie.clone())
            .header("sec-fetch-site", "same-origin")
            .header("sec-fetch-mode", "navigate")
            .header("sec-fetch-user", "?1")
            .header(header::REFERER, referer)
            .header(header::CONTENT_TYPE, "application/x-www-form-urlencoded")
            .body(Body::from("display_name=x"))
            .unwrap();
        app.send_via(&app.router, LOOPBACK_PEER, request)
    };
    assert_eq!(
        post("https://handy.example.com/account?x=1#name")
            .await
            .location(),
        Some("/lock?next=%2Faccount%3Fx%3D1")
    );
    assert_eq!(
        post("https://evil.example/account").await.location(),
        Some("/lock")
    );
    assert_eq!(
        post("https://handy.example.com//evil.example")
            .await
            .location(),
        Some("/lock")
    );
    let long = "a".repeat(5000);
    let res = send(
        &app,
        Method::POST,
        "/lock",
        HOST,
        Some(&cookie),
        &[("assertion", &long), ("next", "/devices")],
    )
    .await;
    assert_eq!(
        res.status,
        StatusCode::BAD_REQUEST,
        "read, refused - not too large"
    );
}
